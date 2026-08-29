package com.rohittp.reng.internal.terrain

/**
 * One tile address in the XYZ scheme, as **RenG's own** type rather than Rentile's `TileId` — the same
 * boundary rule [DemEncoding] follows, and the one `BasemapEngineHost.engineTileIdOf` already draws in
 * the other direction. `x` is deliberately **not** required to be canonical: a world-wrapped request is
 * exactly one of the two cases that make a requested tile differ from the tile Rentile answers with,
 * and canonicalising it is this file's job rather than the caller's.
 */
internal data class DemTileCoordinate(val z: Int, val x: Int, val y: Int)

/**
 * The sub-rectangle of an ancestor DEM tile that one requested tile occupies, in the ancestor's own
 * normalized texture coordinates.
 *
 * `u` runs eastward from the source tile's western edge and `v` runs **southward** from its northern
 * edge, which is the DEM image's own row order: row `0` of an XYZ tile's PNG is its northern row, so
 * `v = 0` is the first row and no flip is involved anywhere. [DemElevationTile] indexes to match.
 *
 * Every bound is an exact `Double`: [childScale] is a power of two and [childX] and [childY] are
 * integers below it, so `childX / childScale` is exact for every reachable value and the two sides of
 * a shared edge — `(childX + 1) / childScale` of one tile against `childX / childScale` of the next —
 * are bit-identical rather than merely close.
 */
internal data class DemTileWindow(
    val childScale: Int,
    val childX: Int,
    val childY: Int,
) {
    init {
        require(childScale >= 1) { "a child scale is at least 1" }
        require(childX in 0 until childScale && childY in 0 until childScale) {
            "a child offset lies inside its scale"
        }
    }

    /** The western edge, in the source tile's `u`. */
    val uMinimum: Double get() = childX.toDouble() / childScale.toDouble()

    /** The eastern edge, in the source tile's `u`. */
    val uMaximum: Double get() = (childX + 1).toDouble() / childScale.toDouble()

    /** The **northern** edge, in the source tile's `v`, which increases southward. */
    val vMinimum: Double get() = childY.toDouble() / childScale.toDouble()

    /** The **southern** edge, in the source tile's `v`, which increases southward. */
    val vMaximum: Double get() = (childY + 1).toDouble() / childScale.toDouble()
}

/**
 * The window of [source] that [requested] occupies, or `null` when [source] is not the tile Rentile
 * would have answered [requested] with.
 *
 * **Why this exists at all.** `ValidatedDemTile` carries `requestedTile` and `sourceTile` and nothing
 * between them. Rentile computed the window — `RasterSample` (`internal/raster/RasterResource.kt:9-21`)
 * carries `childScale`, `childX` and `childY` — and then dropped it on the way out through
 * `acquireTerrainTiles` (`internal/DefaultBasemapRasterizer.kt:657-684`), which rebuilds a `TileId`
 * from `sourceZ/sourceX/sourceY` alone. The two tiles differ in exactly two cases, both from
 * `CompiledRasterSource.sampleFor` (`RasterResource.kt:34-54`): a request above the source's
 * `maximumZoom`, and a world-wrapped `x`. Substitution is not one of them — `acquireTerrainTiles`
 * calls the acquirer directly and the ancestor-substitution machinery is reachable only from the
 * render path — so the arithmetic below is a complete account of the difference, not a heuristic.
 *
 * ```
 * childScale = 1 shl (requested.z - source.z)
 * childX     = floorMod(requested.x, 1 shl requested.z) % childScale
 * childY     = requested.y % childScale
 * ```
 *
 * **`floorMod` and `%` are not interchangeable, and this is the one trap here.** Rentile canonicalises
 * `x` with `floorMod` before dividing (`RasterResource.kt:38`, then `:44` and `:51`), because the
 * world is cylindrical and a request for `x = -1` at `z = 2` is a request for the tile at `x = 3`.
 * Kotlin's `%` keeps the sign of its left operand, so `-1 % 4` is `-1`: with `%` in place of
 * `floorMod` a wrapped request either
 * selects the wrong column of the ancestor or, at `childScale = 1`, computes an ancestor that does not
 * exist — which is why the ancestor agreement below is checked against the *canonicalised* `x` and why
 * a wrapped request is a test case rather than a curiosity.
 *
 * **`null` rather than a throw, and rather than a best effort.** ADR 0041 makes terrain the one
 * basemap resource that degrades instead of failing a frame; a DEM tile whose source is not the
 * ancestor of its request is a tile RenG cannot sample, so it joins the tiles that draw flat. Guessing
 * a window for it would displace the ground with somebody else's mountains, which is worse than flat.
 *
 * **Depth is unbounded** below the compatibility profile's `maximumOutputZoom = 22`: a source with
 * `maxzoom: 12` reaches `childScale = 1024` at zoom 22, putting an entire ground tile inside a single
 * DEM texel. Nothing here caps that, and nothing overflows at it — the largest reachable `childScale`
 * is `1 shl 22 = 4_194_304`, and the wrapping arithmetic is done in `Long` before any narrowing.
 */
internal fun demTileWindowFor(requested: DemTileCoordinate, source: DemTileCoordinate): DemTileWindow? {
    if (requested.z !in 0..MAXIMUM_TILE_ZOOM || source.z !in 0..MAXIMUM_TILE_ZOOM) return null
    if (source.z > requested.z) return null

    val requestedDimension = 1L shl requested.z
    if (requested.y < 0L || requested.y >= requestedDimension) return null

    val childScale = 1L shl (requested.z - source.z)
    val canonicalX = floorModOf(requested.x.toLong(), requestedDimension)

    // The ancestor agreement, which is also what makes the canonicalisation observable at zoomDelta 0:
    // `sourceX` there is the canonical x itself, so a request for x = -1 at z = 2 must match a source
    // tile at x = 3 or this returns null.
    if (source.x.toLong() != canonicalX / childScale) return null
    if (source.y.toLong() != requested.y.toLong() / childScale) return null

    return DemTileWindow(
        childScale = childScale.toInt(),
        childX = (canonicalX % childScale).toInt(),
        childY = (requested.y.toLong() % childScale).toInt(),
    )
}

/**
 * The Mercator LOD ceiling both tile selectors already carry as a private `MAXIMUM_LOD`, restated here
 * because they are private to their own files. It is Rentile's compatibility-profile
 * `maximumOutputZoom` too, so it bounds a *requested* tile as well as a selected one — and bounding it
 * is what keeps `1L shl requested.z` an honest shift rather than one Kotlin has masked to 63.
 */
private const val MAXIMUM_TILE_ZOOM: Int = 22

/** Kotlin's `%` keeps the dividend's sign; a cylindrical world needs the divisor's. */
private fun floorModOf(value: Long, divisor: Long): Long {
    val remainder = value % divisor
    return if (remainder < 0L) remainder + divisor else remainder
}
