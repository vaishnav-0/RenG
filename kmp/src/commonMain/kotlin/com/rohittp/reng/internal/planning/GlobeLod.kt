package com.rohittp.reng.internal.planning

import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.internal.projection.MERCATOR_MAXIMUM_LATITUDE_DEGREES
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow

/**
 * One rendered basemap tile is a fixed square raster of this many texels on a side
 * (`RenderOptions.SUPPORTED_OUTPUT_SIZES` offers nothing larger), and it is also Mercator's world
 * size in logical pixels at zoom zero (`CameraMatrices.kt:89`). The two being the same number is
 * exactly why `screenPixelsPerTexel = 2^(zoom - selectedLod)` comes out as cleanly as it does in
 * [observeMercatorLod]'s derivation.
 */
internal const val BASEMAP_TILE_TEXELS: Double = 512.0

/**
 * **G1, the latitude-matched convention: `z_eff = zoom - log2(cos latitude)`.**
 *
 * Mercator's world size is `512 * 2^zoom` at every latitude (`CameraMatrices.kt:89`). A globe's is
 * not: the sphere is scaled up by `1 / cos(latitude)`, as MapLibre does, so that the on-screen
 * ground scale at the camera's *own* latitude is the one Mercator would have shown at the same
 * `zoom`. At latitude 82 that is a factor of 7.19, and at the equator it is exactly 1 — which is
 * why no fixture at latitude 0 can tell this convention from its absence.
 *
 * **What it buys is the tile count, and the mechanism is worth stating exactly.** A Mercator tile
 * at LOD `L` is a square of `1 / 2^L` in Mercator space, so on the plane it is always
 * `worldSize / 2^L` output pixels across ([resolveBasemapTileQuad]) — latitude-independent, which
 * is the sole reason [observeMercatorLod]'s `screenPixelsPerTexel = 2^(zoom - selectedLod)` holds.
 * On a sphere that same tile is a ground square only `cos(latitude)` the side of an equatorial one,
 * so its on-screen side picks up a `cos(latitude)` factor and the identity fails by `1/cos^2` in
 * *area* — the measured 4 tiles to 132 at latitude 82, zoom 6, and 24 to 686 on a phone viewport at
 * latitude 82, zoom 8, the second of which is past `maximumBasemapTileInstances`' default of 512 and
 * therefore fails the frame closed rather than rendering it slowly
 * (`docs/research/2026-08-28-g-globe-rentile-tiles.md`, §3.2).
 *
 * Scaling the sphere by `2^(z_eff - zoom) = 1 / cos(latitude)` cancels that factor **exactly**, at
 * the camera's own latitude, which is where the LOD is chosen. So the LOD rule itself does not
 * change: [observeMercatorLod] keeps reading the camera's `zoom` in both projection modes, and
 * [basemapTileSideLogicalPixels] is the executable statement of the premise that makes it right.
 *
 * **The trap this function exists to be read alongside:** `z_eff` is a *scale* exponent, not a LOD.
 * Feeding it to [observeMercatorLod] selects a LOD `-log2(cos latitude)` levels too fine and
 * reproduces the very explosion the convention removes (154 tiles for the 132-tile case, measured in
 * `GlobeLodTest`). It is also not clamped to the LOD range: at latitude 85.0511 it exceeds `22` by
 * 3.54, and clamping it there would shrink the globe by 11.6x rather than saturate a LOD.
 *
 * [latitude] must already be within Mercator's supported band. Every camera reaching frame planning
 * has been through `validateMercatorCamera`, which refuses anything wider with a typed
 * `INVALID_VALUE` failure long before a LOD is observed.
 */
internal fun latitudeMatchedGlobeZoom(zoom: Double, latitude: Double): Double {
    require(zoom.isFinite()) { "zoom must be finite" }
    require(latitude.isFinite() && abs(latitude) <= MERCATOR_MAXIMUM_LATITUDE_DEGREES) {
        "latitude must be within the Mercator-supported band"
    }
    return zoom - log2(cos(latitude * PI / 180.0))
}

/**
 * The globe's world size in logical pixels: `512 * 2^z_eff`, the sphere's equatorial circumference
 * on screen, and the direct counterpart of `ResolvedMercatorCamera.worldSizeLogicalPixels`.
 *
 * A globe camera builds its sphere radius from `this / (2 * PI)`. Building it from
 * `512 * 2^zoom` instead is the naive reading of G1 and is what the 132- and 686-tile measurements
 * are measurements *of*.
 */
internal fun globeWorldSizeLogicalPixels(zoom: Double, latitude: Double): Double =
    BASEMAP_TILE_TEXELS * 2.0.pow(latitudeMatchedGlobeZoom(zoom, latitude))

/**
 * The on-screen side, in logical pixels, of one basemap tile at [selectedLod] lying at the camera's
 * own [latitude] — the numerator of the `screenPixelsPerTexel` ratio [observeMercatorLod] selects
 * against, with [BASEMAP_TILE_TEXELS] as its denominator.
 *
 * Under [ProjectionMode.MERCATOR] this is `worldSize / 2^selectedLod` and agrees with
 * [resolveBasemapTileQuad]'s `sideLogicalPixels` for any tile in the frame; latitude does not
 * appear. Under [ProjectionMode.GLOBE] the tile is a ground square `cos(latitude)` the side of an
 * equatorial one, so the globe's larger world size ([globeWorldSizeLogicalPixels]) and that cosine
 * multiply — and, under G1, cancel to the same number Mercator would have produced.
 *
 * Both modes therefore return `512 * 2^(zoom - selectedLod)`. That equality is the whole content of
 * G1; it is asserted rather than assumed, and asserted at latitudes where the two factors are far
 * from 1.
 */
internal fun basemapTileSideLogicalPixels(
    projectionMode: ProjectionMode,
    zoom: Double,
    latitude: Double,
    selectedLod: Int,
): Double {
    require(selectedLod >= 0) { "selectedLod must be non-negative" }
    return when (projectionMode) {
        ProjectionMode.MERCATOR -> {
            require(zoom.isFinite()) { "zoom must be finite" }
            BASEMAP_TILE_TEXELS * 2.0.pow(zoom - selectedLod)
        }

        ProjectionMode.GLOBE ->
            globeWorldSizeLogicalPixels(zoom, latitude) / 2.0.pow(selectedLod.toDouble()) *
                cos(latitude * PI / 180.0)
    }
}
