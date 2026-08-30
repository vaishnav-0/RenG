package com.rohittp.reng.internal.terrain

import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.projectMercator
import kotlin.math.floor

/**
 * One ground tile's DEM, CPU-side: which source image describes it, and which sub-rectangle of that
 * image it occupies.
 *
 * The same pair `resolveDemTextures` hands the GPU as a [com.rohittp.reng.internal.gl.SceneTileDem],
 * minus the texture name — deliberately, because a texture name is the one thing the CPU cannot read.
 */
internal class GroundSurfaceTile(val source: DemTileCoordinate, val window: DemTileWindow)

/**
 * **The surface the ground actually draws, answered on the CPU at an arbitrary map position.**
 *
 * ADR 0040's `GROUND_RELATIVE` altitude is an offset above "the terrain surface RenG drew under it in
 * that same frame", and this is the only thing in RenG that can say what that surface's height is
 * anywhere other than at a grid vertex.
 *
 * ## It reconstructs a surface, not a texel, and that distinction is measured rather than argued
 *
 * ADR 0039 originally proposed a *shared nearest-texel rule* — the CPU reads the texel the GPU reads,
 * so both paths see one number. Task 14's spike
 * (`docs/research/2026-08-30-e-terrain-coplanar-depth-spike.md`) measured it at **3.049 m** of
 * vertical error and ADR 0039's 2026-08-30 erratum records the refutation. The reason is arithmetic:
 * a ground cell spans as many DEM texels as the frame's granularity leaves it, and the drawn surface
 * inside that cell is the *interpolation between the cell's corner samples*, not the value of the
 * texel underneath the point. Reading the texel underneath gives a staircase; the ground draws a mesh.
 *
 * So the rule here is the one the spike measured at **0.000 m**:
 *
 * 1. find the ground grid cell containing the point, at the frame's own reconciled
 *    `groundCellsPerTileSide` — never terrain's unreconciled claim, because the globe's curvature can
 *    raise it;
 * 2. take the nearest-texel elevation at that cell's **four corners** with
 *    [com.rohittp.reng.internal.gl.GROUND_ELEVATION_SOURCE]'s exact `floor(source * interior)` rule,
 *    including its reach into the padded ring at the tile's far edge;
 * 3. interpolate across the ground grid's own **NE–SW diagonal**, which is what [groundGridIndices]
 *    emits — `(NW, SW, NE)` then `(NE, SW, SE)`.
 *
 * Interpolating the same four corners across the *other* diagonal — which is what
 * `assembleGeometryGrid` used to triangulate on — measured **1.800 m** in the same spike. The diagonal
 * is not a detail.
 *
 * ## Two exactness limits, both stated rather than discovered
 *
 * **The shader is `Float` and this is `Double`.** [GROUND_ELEVATION_SOURCE] mixes the window, scales
 * by the interior and floors, all in 32-bit; this does the identical arithmetic in 64-bit. The two
 * choose a different texel only for a point sitting within a `Float` ULP of a texel boundary, and the
 * spike's quantum probe says the depth buffer resolves well under a millimetre of terrain height — so
 * this is one of the reasons a coplanar drape needs a lift and cannot be made exact.
 *
 * **This answers metres, and the ground draws logical pixels.** Mercator's vertex shader multiplies
 * metres by `cosh(PI * (1 - 2y))` per vertex, so the drawn surface's *height* is a linear
 * interpolation of the corners' already-scaled values while a caller adding these metres to an
 * altitude gets the point's own latitude term. Over one ground cell the two differ by the variation
 * of `1 / cos(latitude)` across a cell — under two centimetres at zoom 13 and under a metre anywhere
 * a cell is smaller than a degree. Metres are the currency every caller wants and the currency ADR
 * 0040 is written in, so the residual is carried rather than the vocabulary bent around it.
 *
 * ## Nothing here decodes anything
 *
 * Rentile `0.7.0` hands over already-decoded texels, so a lookup is three array reads and
 * [demElevationMetres]. The design's §6 argument for a *sparse* CPU decode — that decoding the
 * visible set would cost about 224 MiB — describes a decode that no longer exists. What this does
 * cost is one [com.rohittp.reng.internal.image.DecodedImage.rgbaSnapshot] per distinct source DEM,
 * taken once at construction, which is why the renderer builds this only for a frame that actually
 * carries ground-relative content.
 */
internal class GroundSurface(
    /** The LOD every ground tile in this frame is drawn at; both tile selectors emit exactly one. */
    val lod: Int,
    /** The DEM source's interior edge length in texels, before padding. */
    val interiorSizePx: Int,
    /** The style's `terrain.exaggeration`, applied here exactly as the vertex shader applies it. */
    val exaggeration: Double,
    private val encoding: DemEncoding,
    tiles: Map<DemTileCoordinate, GroundSurfaceTile>,
    texelsBySource: Map<DemTileCoordinate, DemTexels>,
) {
    private val tileSnapshot: Map<DemTileCoordinate, GroundSurfaceTile> = LinkedHashMap(tiles)

    /**
     * One `ByteArray` per distinct source image, snapshotted once. [DecodedImage.rgbaSnapshot] copies
     * on every call, so holding the copy is what stops a per-node lookup from being a per-node
     * megabyte.
     */
    private val bytesBySource: Map<DemTileCoordinate, ByteArray> =
        texelsBySource.mapValues { (_, texels) -> texels.image.rgbaSnapshot() }

    init {
        require(lod in 0..MAXIMUM_GROUND_SURFACE_ZOOM) { "a ground surface sits at a real tile zoom" }
        require(interiorSizePx > 0) { "a DEM tile has a positive interior" }
    }

    /** True when no tile in this frame carries a DEM, so every lookup would answer `null` anyway. */
    val isEmpty: Boolean get() = tileSnapshot.isEmpty()

    /**
     * The drawn surface's height in metres above the ellipsoid at a normalised Mercator position, or
     * `null` where this frame drew no displaced ground under it.
     *
     * **`null` is ADR 0040's "absent terrain resolves `GROUND_RELATIVE` as `ABSOLUTE`"**, and it is
     * the answer for a frame with no terrain, a tile Rentile had no DEM for, and a position outside
     * the world's latitude band alike. The caller adds nothing rather than guessing at sea level,
     * which is the same number by a different argument and would stop being so the day a coverage
     * fallback grows a value.
     *
     * [mercatorX] may lie outside `[0, 1)`: a world copy is a real position, and the tile it lands in
     * is found by wrapping exactly as `padDemTexture` wraps a neighbour.
     */
    fun elevationMetresAt(mercatorX: Double, mercatorY: Double, cellsPerTileSide: Int): Double? {
        require(cellsPerTileSide >= 1) { "a ground grid has at least one cell a side" }
        if (!mercatorX.isFinite() || !mercatorY.isFinite()) return null

        val dimension = 1L shl lod
        val worldY = mercatorY * dimension.toDouble()
        val tileYd = floor(worldY)
        if (tileYd < 0.0 || tileYd >= dimension.toDouble()) return null
        val worldX = mercatorX * dimension.toDouble()
        val tileXd = floor(worldX)
        if (!tileXd.isFinite() || tileXd < MINIMUM_WRAPPABLE_TILE || tileXd > MAXIMUM_WRAPPABLE_TILE) {
            return null
        }

        val tile = tileSnapshot[
            DemTileCoordinate(
                z = lod,
                x = floorModOf(tileXd.toLong(), dimension).toInt(),
                y = tileYd.toInt(),
            ),
        ] ?: return null

        // The ground grid's own lattice: `groundGridVertices` puts a node at every `column / cells`
        // and `row / cells` of the tile, with `v` running north to south exactly as Mercator `y` does.
        val cells = cellsPerTileSide.toDouble()
        val u = (worldX - tileXd).coerceIn(0.0, 1.0)
        val v = (worldY - tileYd).coerceIn(0.0, 1.0)
        val column = floor(u * cells).toInt().coerceIn(0, cellsPerTileSide - 1)
        val row = floor(v * cells).toInt().coerceIn(0, cellsPerTileSide - 1)
        val westU = column.toDouble() / cells
        val eastU = (column + 1).toDouble() / cells
        val northV = row.toDouble() / cells
        val southV = (row + 1).toDouble() / cells

        val northWest = cornerMetres(tile, westU, northV)
        val northEast = cornerMetres(tile, eastU, northV)
        val southWest = cornerMetres(tile, westU, southV)
        val southEast = cornerMetres(tile, eastU, southV)

        // Cell-local coordinates, `s` eastward and `t` southward. The ground's shared edge runs from
        // the south-west corner `(0, 1)` to the north-east corner `(1, 0)`, which is the line
        // `s + t = 1`: the north-west triangle is below it and the south-east triangle above.
        val s = ((u - westU) / (eastU - westU)).coerceIn(0.0, 1.0)
        val t = ((v - northV) / (southV - northV)).coerceIn(0.0, 1.0)
        val metres = if (s + t <= 1.0) {
            northWest + s * (northEast - northWest) + t * (southWest - northWest)
        } else {
            southEast + (1.0 - s) * (southWest - southEast) + (1.0 - t) * (northEast - southEast)
        }
        return metres * exaggeration
    }

    /**
     * [elevationMetresAt] from geographic degrees, for a caller that never held a Mercator pair.
     *
     * A separate name rather than an overload: both take three arguments of which two are `Double`,
     * and a caller that reached the wrong one would get a plausible elevation at the wrong place.
     */
    fun elevationMetresBeneath(
        latitude: Double,
        unwrappedLongitude: Double,
        cellsPerTileSide: Int,
    ): Double? {
        if (!latitude.isFinite() || !unwrappedLongitude.isFinite()) return null
        val projected = projectMercator(
            GeographicPosition(
                latitude = latitude,
                unwrappedLongitude = unwrappedLongitude,
                altitudeMetres = 0.0,
            ),
        )
        return elevationMetresAt(projected.x, projected.y, cellsPerTileSide)
    }

    /**
     * One ground-grid node's elevation, by [GROUND_ELEVATION_SOURCE]'s rule exactly: map the tile-local
     * grid coordinate through the DEM window into the source image, and snap to the texel containing
     * it.
     *
     * At `grid = 1` on a window that reaches the source's own far edge, `floor(1.0 * interior)` is
     * `interior` — one past the last interior texel — and the shader's `+ 1.5` lands it on the padded
     * texture's **east or south ring**, which [padDemTexture] fills from the neighbouring tile. That
     * is not an edge case to clamp away: it is what makes two tiles agree along their shared boundary,
     * and clamping it here would put the drape a full texel of relief away from the ground along every
     * tile edge in the frame.
     */
    private fun cornerMetres(tile: GroundSurfaceTile, gridU: Double, gridV: Double): Double {
        val window = tile.window
        val sourceU = window.uMinimum + (window.uMaximum - window.uMinimum) * gridU
        val sourceV = window.vMinimum + (window.vMaximum - window.vMinimum) * gridV
        val interior = interiorSizePx
        val texelX = floor(sourceU * interior.toDouble()).toInt().coerceIn(0, interior)
        val texelY = floor(sourceV * interior.toDouble()).toInt().coerceIn(0, interior)
        return paddedTexelMetres(tile.source, texelX, texelY)
    }

    /**
     * The elevation of one texel of [source]'s **padded** image, addressed in interior coordinates
     * `0 .. interiorSizePx` where `interiorSizePx` itself is the ring.
     *
     * [padDemTexture]'s rule is reproduced rather than shared, because that function assembles bytes
     * and this reads one: a neighbour that exists supplies its own facing texel, and a neighbour that
     * does not — absent, or north/south of the world — replicates this tile's own facing edge. Getting
     * the two rules to disagree would put the drape and the ground on different sides of every tile
     * boundary, which is exactly the seam `assertAdjacentDisplacedTilesLeaveNoCrackInEitherProjection`
     * measures for the ground alone.
     */
    private fun paddedTexelMetres(source: DemTileCoordinate, texelX: Int, texelY: Int): Double {
        val interior = interiorSizePx
        val eastward = texelX >= interior
        val southward = texelY >= interior
        if (eastward || southward) {
            val neighbour = neighbourOf(source, if (eastward) 1 else 0, if (southward) 1 else 0)
            val bytes = neighbour?.let { bytesFor(it) }
            if (bytes != null) {
                return texelMetres(
                    bytes = bytes,
                    x = if (eastward) 0 else texelX,
                    y = if (southward) 0 else texelY,
                )
            }
        }
        val own = bytesFor(source) ?: return 0.0
        return texelMetres(
            bytes = own,
            x = if (eastward) interior - 1 else texelX,
            y = if (southward) interior - 1 else texelY,
        )
    }

    private fun bytesFor(tile: DemTileCoordinate): ByteArray? {
        val bytes = bytesBySource[tile] ?: return null
        val expected = interiorSizePx.toLong() * interiorSizePx.toLong() * RGBA_CHANNELS.toLong()
        return if (bytes.size.toLong() == expected) bytes else null
    }

    private fun neighbourOf(source: DemTileCoordinate, deltaX: Int, deltaY: Int): DemTileCoordinate? {
        val dimension = 1L shl source.z
        val y = source.y.toLong() + deltaY
        if (y !in 0 until dimension) return null
        return DemTileCoordinate(
            z = source.z,
            x = floorModOf(source.x.toLong() + deltaX, dimension).toInt(),
            y = y.toInt(),
        )
    }

    private fun texelMetres(bytes: ByteArray, x: Int, y: Int): Double {
        val offset = (y * interiorSizePx + x) * RGBA_CHANNELS
        return demElevationMetres(
            red = bytes[offset].toInt() and 0xFF,
            green = bytes[offset + 1].toInt() and 0xFF,
            blue = bytes[offset + 2].toInt() and 0xFF,
            encoding = encoding,
        )
    }

    override fun toString(): String =
        "GroundSurface(lod=$lod, interior=$interiorSizePx, exaggeration=$exaggeration, " +
            "tiles=${tileSnapshot.size})"
}

private const val RGBA_CHANNELS: Int = 4

/** `padDemTexture`'s own bound, restated here because a lookup outside it can address no tile. */
private const val MAXIMUM_GROUND_SURFACE_ZOOM: Int = 30

/**
 * The band of tile columns a world copy may legitimately reach before `toLong` stops being faithful.
 * `resolveCameraRelativeMapPosition` has already refused anything this far out as unrepresentable on
 * the GPU, so this is a guard against `Double.toLong`'s saturation rather than a policy.
 */
private const val MINIMUM_WRAPPABLE_TILE: Double = -1.0e15
private const val MAXIMUM_WRAPPABLE_TILE: Double = 1.0e15

private fun floorModOf(value: Long, divisor: Long): Long {
    val remainder = value % divisor
    return if (remainder < 0L) remainder + divisor else remainder
}
