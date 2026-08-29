package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.GlobeGroundFootprint

/**
 * Which basemap tiles a globe frame draws — [selectBasemapTiles]'s counterpart, and not a
 * reparameterisation of it.
 *
 * ## What the Mercator selector assumes that a sphere does not supply
 *
 * [selectBasemapTiles] walks a **band of candidate rows**, projects the footprint's x-interval into
 * each row's horizontal strip and emits the cells the interval touches. That "rows times an
 * x-interval" topology is the Mercator plane's, in two ways that no change of projection function
 * repairs:
 *
 * - **it needs the visible ground to be an x-interval at every row.** On a globe near a pole the
 *   visible cap contains every longitude, so the interval is the whole world and the row is solid;
 *   near the antimeridian it straddles the wrap. Both are ordinary globe cameras rather than edge
 *   cases;
 * - **it needs a bounded row band to walk.** The band comes from the footprint's own `y` extent, and
 *   a sphere's visible ground has no such extent to hand until the tiles themselves have been
 *   decided.
 *
 * ## What replaces it
 *
 * A descent of the tile quadtree. One cell at LOD 0, subdivided four ways per level down to
 * [lod], keeping at every level the cells that could still show visible ground
 * ([GlobeGroundFootprint.mayAdmitMercatorCell]) and deciding exactly
 * ([GlobeGroundFootprint.admitsMercatorCell]) at the selected LOD alone. Work is proportional to the
 * perimeter of the answer rather than to the area of any bounding box, and no row band, no
 * x-interval and no [CANDIDATE_NEIGHBOUR_PADDING]-style neighbour padding appears anywhere: a cell
 * is kept because its own spherical rectangle meets the visible ground, which is as true across the
 * antimeridian and over a pole as it is anywhere else.
 *
 * The loose test is used during the descent and the exact one only at the end because a cell that
 * shows visible ground always has a parent that does, so no true tile can be lost by pruning
 * loosely; and a cell falsely kept at one level costs only its own four children at the next, since
 * every child is judged on its own rectangle rather than on its parent's verdict.
 *
 * ## World copies collapse, and the two counts converge
 *
 * A Mercator world repeats east and west, so [selectBasemapTiles] emits one
 * [BasemapTileInstance] per visible copy and deduplicates to [CanonicalBasemapTile] afterwards. A
 * sphere has no copies — [unitSphereDirection] wraps its Mercator `x`, so a camera at longitude 400
 * sees exactly what one at longitude 40 sees, and tile `canonicalX` and tile `canonicalX + 2^lod`
 * are not two places but one. Every instance here is therefore canonical:
 * [BasemapTileInstance.instanceCopy] is `0` and [BasemapTileInstance.unwrappedX] equals
 * [BasemapTileInstance.canonicalX], the two lists have equal length, and
 * `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.1's "on a globe the two converge" is what
 * that sentence means in code. Emitting a second copy would draw one patch of sphere twice.
 *
 * ## The Tile Budget
 *
 * Unchanged in meaning: over budget is [TileSelectionOutcome.OverBudget] and the caller fails the
 * frame closed before acquiring anything. The count compared against the budget is the **exact**
 * one at the selected LOD. [FRONTIER_BUDGET_HEADROOM] bounds the descent's own work; it is
 * deliberately four times the budget so that the loose test's halo cannot refuse a frame that the
 * exact count would have admitted, and it is a work ceiling rather than a second budget.
 */
internal fun selectGlobeTiles(
    footprint: GlobeGroundFootprint,
    lod: Int,
    maximumInstances: Int,
): TileSelectionOutcome {
    require(lod in MINIMUM_LOD..MAXIMUM_LOD) { "lod must be within the Mercator LOD range" }
    require(maximumInstances > 0) { "maximumInstances must be positive" }

    var frontier: List<GlobeTileCandidate> = listOf(GlobeTileCandidate(0L, 0L))
    var level = 0
    if (!footprint.mayAdmitMercatorCell(0.0, 1.0, 0.0, 1.0)) return emptyGlobeTileSelection()

    val frontierCeiling = maximumInstances.toLong() * FRONTIER_BUDGET_HEADROOM
    while (level < lod) {
        val childCount = 1L shl (level + 1)
        val next = ArrayList<GlobeTileCandidate>(frontier.size * 4)
        for (candidate in frontier) {
            for (childY in 0..1) {
                for (childX in 0..1) {
                    val child = GlobeTileCandidate(
                        tileX = candidate.tileX * 2L + childX,
                        tileY = candidate.tileY * 2L + childY,
                    )
                    if (footprint.mayAdmitMercatorCell(
                            minimumX = child.tileX.toDouble() / childCount,
                            maximumX = (child.tileX + 1L).toDouble() / childCount,
                            minimumY = child.tileY.toDouble() / childCount,
                            maximumY = (child.tileY + 1L).toDouble() / childCount,
                        )
                    ) {
                        next += child
                    }
                }
            }
        }
        if (next.isEmpty()) return emptyGlobeTileSelection()
        if (next.size > frontierCeiling) {
            return TileSelectionOutcome.OverBudget(
                limit = maximumInstances,
                actual = next.size.toLong(),
            )
        }
        frontier = next
        level += 1
    }

    val tileCount = 1L shl lod
    val admitted = frontier.filter { candidate ->
        footprint.admitsMercatorCell(
            minimumX = candidate.tileX.toDouble() / tileCount,
            maximumX = (candidate.tileX + 1L).toDouble() / tileCount,
            minimumY = candidate.tileY.toDouble() / tileCount,
            maximumY = (candidate.tileY + 1L).toDouble() / tileCount,
        )
    }
    if (admitted.size > maximumInstances) {
        return TileSelectionOutcome.OverBudget(
            limit = maximumInstances,
            actual = admitted.size.toLong(),
        )
    }
    if (admitted.isEmpty()) return emptyGlobeTileSelection()

    val ordered = admitted.sortedWith(
        compareBy<GlobeTileCandidate> { it.tileY }.thenBy { it.tileX },
    )
    val instances = ordered.map { candidate ->
        check(candidate.tileX in 0L until tileCount && candidate.tileY in 0L until tileCount)
        BasemapTileInstance(
            lod = lod,
            tileY = candidate.tileY.toInt(),
            unwrappedX = candidate.tileX,
            instanceCopy = 0,
            canonicalX = candidate.tileX.toInt(),
        )
    }
    val canonicalResources = instances.map { instance ->
        CanonicalBasemapTile(
            lod = instance.lod,
            tileY = instance.tileY,
            canonicalX = instance.canonicalX,
        )
    }
    return TileSelectionOutcome.Success(instances, canonicalResources)
}

private data class GlobeTileCandidate(val tileX: Long, val tileY: Long)

private fun emptyGlobeTileSelection(): TileSelectionOutcome.Success =
    TileSelectionOutcome.Success(emptyList(), emptyList())

private const val MINIMUM_LOD: Int = 0
private const val MAXIMUM_LOD: Int = 22
private const val FRONTIER_BUDGET_HEADROOM: Long = 4L
