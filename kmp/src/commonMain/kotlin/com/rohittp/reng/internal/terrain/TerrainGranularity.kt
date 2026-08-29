package com.rohittp.reng.internal.terrain

import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.internal.gl.MAXIMUM_GROUND_CELLS_PER_TILE_SIDE
import com.rohittp.reng.internal.planning.basemapTileSideLogicalPixels

/**
 * How finely a displaced ground tile is subdivided, and **why this is a budget rather than an error
 * bound**.
 *
 * The globe's curvature rule is an error bound and can be: the sagitta of a chord is computable from
 * the radius and the span alone, so `globeGroundCellsPerTileSide` can promise half a logical pixel of
 * deviation without looking at any data. **Terrain has no such luxury.** How far a grid cell departs
 * from the elevation it stands in for is a property of the DEM's own contents, and RenG does not
 * decode DEM texels on the path that chooses a granularity — deciding by inspection would mean
 * decoding every visible tile on the CPU to choose how finely to draw it, which is the cost the
 * vertex-texture-fetch decision exists to avoid.
 *
 * So this bounds *work* instead, from two ceilings that are both knowable without the data:
 *
 * **The DEM cannot express more than it has.** A source serving `tileSizePx` texels a side carries no
 * detail beyond `tileSizePx` cells, so subdividing past it samples the same texel repeatedly and buys
 * literally nothing.
 *
 * **The screen cannot show more than it has.** A tile covering `basemapTileSideLogicalPixels` on
 * screen cannot resolve detail finer than a pixel, and a cell smaller than one pixel is a triangle
 * nobody can see.
 *
 * **And [MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE] caps both**, because the two ceilings above are
 * per-tile and the cost is per-frame. That constant's own KDoc carries the arithmetic.
 */

/**
 * The ceiling on a displaced tile's subdivision, and the one number here that is chosen rather than
 * derived from a property of the data.
 *
 * **It is derived from X2's measured tile counts, not picked for looking sensible.** A grid of `c`
 * cells is `(c + 1)²` vertices per tile, and X2 measured a 3840×2160 viewport at pitch 0 reaching
 * **93 tiles with no LOD history and 167 with one frame of hysteresis**:
 *
 * | cells | vertices/tile | at 93 tiles | at 167 tiles |
 * |---|---|---|---|
 * | 32 | 1,089 | 101,277 | 181,863 |
 * | **64** | **4,225** | **392,925** | **705,575** |
 * | 128 | 16,641 | 1,547,613 | 2,779,047 |
 *
 * **The globe's own cap of 128 is not wrong and is not reused.** It was chosen for *curvature*, which
 * demands its finest subdivision at zoom 0 — where a frame holds a handful of tiles and 128 costs
 * almost nothing. Terrain demands its finest at *city* zooms, where a frame holds 167, and the same
 * number would cost 2.78 million vertices a frame. The two rules want different ceilings because they
 * peak at opposite ends of the zoom range.
 *
 * A frame that wants more than this gets less, silently and by design: the alternative is a frame that
 * is correct and does not arrive.
 */
internal const val MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE: Int = 64

/**
 * The granularity terrain alone would ask for, as a power of two in `1..`[MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE].
 *
 * Rounded **down** to a power of two rather than up. Down, because both inputs are ceilings on what
 * can be *seen*: exceeding either buys nothing, so overshooting is pure waste, where the globe's
 * curvature rule rounds up because falling short there is visible error. Powers of two because
 * `groundGrid` caches by granularity and the cache is bounded only if the key set is.
 */
internal fun terrainCellsPerTileSide(
    demTileSizePx: Int,
    tileSideLogicalPixels: Double,
): Int {
    require(demTileSizePx > 0) { "a DEM tile has a positive side" }
    require(tileSideLogicalPixels > 0.0 && tileSideLogicalPixels.isFinite()) {
        "a ground tile covers a positive, finite number of logical pixels"
    }
    val ceiling = minOf(
        demTileSizePx.toDouble(),
        tileSideLogicalPixels,
        MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE.toDouble(),
    )
    var cells = 1
    while (cells * 2 <= ceiling) cells *= 2
    return cells
}

/**
 * The one granularity a frame draws its whole ground at, terrain and curvature reconciled.
 *
 * **One number for the frame, never one per tile.** MapLibre's most expensive globe defect was two
 * meshes sharing an edge at different granularities, and the rule that came out of it — recorded in
 * `GlobeGroundPipeline` — is that any two meshes sharing an edge on the sphere must be subdivided
 * identically or a sliver of background shows through. Terrain does not relax that; it adds a second
 * claimant to the same number.
 *
 * **The two claims are reconciled by `max`, not by `min` or by preference.** Each is a floor on what
 * its own subject needs: curvature below [curvatureCells] shows a faceted limb, and terrain below
 * [terrainCells] shows a ground coarser than the elevation it was given. Taking the larger satisfies
 * both; taking the smaller satisfies neither, and choosing between them would make one subject's
 * correctness depend on the other's presence.
 *
 * The result is capped at [MAXIMUM_GROUND_CELLS_PER_TILE_SIDE] — the grid's own hard limit, which a
 * 16-bit index sets and no budget may exceed — rather than at terrain's softer ceiling, because a
 * globe at zoom 0 legitimately wants 128 for curvature and holds few enough tiles to afford it.
 */
internal fun groundCellsPerTileSide(curvatureCells: Int, terrainCells: Int): Int {
    require(curvatureCells >= 1) { "a ground grid has at least one cell a side" }
    require(terrainCells >= 1) { "a ground grid has at least one cell a side" }
    return minOf(maxOf(curvatureCells, terrainCells), MAXIMUM_GROUND_CELLS_PER_TILE_SIDE)
}

/**
 * [terrainCellsPerTileSide] for the frame a camera is looking at, or **1 when the frame has no
 * terrain** — which is the value that makes a grid draw exactly the flat ground it drew before this
 * cycle, in the 28 of the corpus's 34 styles that declare no `terrain` block.
 */
internal fun terrainCellsPerTileSide(
    terrain: TerrainGranularityInputs?,
    projectionMode: ProjectionMode,
    zoom: Double,
    latitude: Double,
    selectedLod: Int,
): Int {
    if (terrain == null) return 1
    return terrainCellsPerTileSide(
        demTileSizePx = terrain.demTileSizePx,
        tileSideLogicalPixels = basemapTileSideLogicalPixels(
            projectionMode = projectionMode,
            zoom = zoom,
            latitude = latitude,
            selectedLod = selectedLod,
        ),
    )
}

/**
 * What the granularity rule needs to know about a frame's terrain source, which is only its texel
 * count — deliberately not the source itself, so this file names nothing from the firewall.
 */
internal class TerrainGranularityInputs(val demTileSizePx: Int) {
    init {
        require(demTileSizePx > 0) { "a DEM tile has a positive side" }
    }
}
