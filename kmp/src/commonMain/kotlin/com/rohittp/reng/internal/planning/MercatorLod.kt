package com.rohittp.reng.internal.planning

import kotlin.math.ceil

internal data class LodObservation(val selectedLod: Int)

/**
 * Chooses the basemap LOD one frame draws its ground from, given the camera [zoom] and the LOD the
 * previous frame settled on.
 *
 * **Why the thresholds are asymmetric.** A rendered tile is a fixed 512-texel raster
 * (`RenderOptions.SUPPORTED_OUTPUT_SIZES` offers nothing larger), and
 * [resolveBasemapTileQuad] gives it a side of `512 * 2^(zoom - selectedLod)` output pixels. So the
 * selection decides, exactly, how many screen pixels one texel is stretched across:
 *
 * ```
 * screenPixelsPerTexel = 2^(zoom - selectedLod)
 * ```
 *
 * **This rule is shared with `ProjectionMode.GLOBE`, and that is a result rather than an
 * assumption.** The identity above holds on the plane only because a Mercator tile's output-pixel
 * width does not depend on latitude; on a sphere it does, by `cos(latitude)`. G1 restores it by
 * scaling the globe by `1 / cos(latitude)` instead — [latitudeMatchedGlobeZoom] — so the two factors
 * cancel at the camera's own latitude, which is the latitude a single per-frame LOD is chosen at.
 * The premise is executable as [basemapTileSideLogicalPixels] and is asserted at latitudes where
 * neither factor is near 1. What a globe camera must *not* do is observe its LOD at
 * [latitudeMatchedGlobeZoom]: that is a scale exponent, and reading it here selects a LOD several
 * levels too fine and multiplies the tile count instead of holding it.
 *
 * Above 1.0 the tile is magnified and every antialiased road edge in it is smeared by the sampler
 * before the eye ever sees it; below 1.0 it is minified and the raster carries more detail than the
 * screen can show. Measured on the visual harness at 960x540 over a real MapTiler style, the
 * contrast-weighted width of a road edge is affine in that ratio — roughly `0.89 + 2.27 * ratio`
 * output pixels, a floor of about a pixel plus a term the ratio controls outright. Two frames of the
 * same view either side of a LOD boundary make the point on their own: zoom 11.49 at LOD 11
 * (ratio 1.404) measures 4.08 pixels of edge, and zoom 11.51 at LOD 12 (ratio 0.712) measures 2.51.
 *
 * The historyless rule is round-to-nearest, which keeps the ratio inside `[0.707, 1.414]` and leaves
 * a tile's features at their styled size on average. Hysteresis exists so a zoom hovering on a
 * boundary does not thrash the tile set, but a symmetric band spends half of itself *coarser* than
 * that rule — and coarser is the blurry direction. [UPPER_HYSTERESIS_OFFSET] therefore sits exactly
 * on the historyless boundary and the whole 0.5-level band is spent below it: **a remembered LOD may
 * only ever be finer than the LOD this same zoom would have selected from scratch, never coarser.**
 * The band is the same width it was when it was symmetric, so the thrash it exists to prevent is
 * prevented just as well.
 *
 * The two comparisons differ in strictness on purpose. The upward one is strict (`>`) so the
 * threshold agrees with `ceil(zoom - 0.5)` at the exact boundary instead of overshooting it by one
 * level. The downward one is closed (`<=`) so that LOD 0 stays reachable: `zoom` is clamped to
 * `[0, 22]`, so a strict `<` at an offset of a whole level would leave the coarsest LOD selectable
 * only by a renderer with no LOD history at all.
 *
 * A remembered LOD is therefore retained exactly while `zoom` is in `(selectedLod - 1,
 * selectedLod + 0.5]`, which bounds `screenPixelsPerTexel` to `(0.5, 1.414]`.
 */
internal fun observeMercatorLod(
    zoom: Double,
    previousSelectedLod: Int?,
): LodObservation {
    require(zoom.isFinite() && zoom in MINIMUM_LOD.toDouble()..MAXIMUM_LOD.toDouble()) {
        "zoom must be within the Mercator LOD range"
    }
    require(previousSelectedLod == null || previousSelectedLod in MINIMUM_LOD..MAXIMUM_LOD) {
        "previousSelectedLod must be within the Mercator LOD range"
    }

    if (previousSelectedLod == null) {
        val selected = ceil(zoom - 0.5).toInt().coerceIn(MINIMUM_LOD, MAXIMUM_LOD)
        return LodObservation(selected)
    }

    var selected = previousSelectedLod
    while (selected < MAXIMUM_LOD && zoom > selected.toDouble() + UPPER_HYSTERESIS_OFFSET) {
        selected += 1
    }
    while (selected > MINIMUM_LOD && zoom <= selected.toDouble() - LOWER_HYSTERESIS_OFFSET) {
        selected -= 1
    }
    return LodObservation(selected)
}

private const val MINIMUM_LOD: Int = 0
private const val MAXIMUM_LOD: Int = 22

/**
 * The historyless boundary itself: a remembered LOD is abandoned the moment `ceil(zoom - 0.5)` would
 * have chosen a finer one, so hysteresis never holds the ground at a coarser, blurrier level.
 */
private const val UPPER_HYSTERESIS_OFFSET: Double = 0.5

/**
 * The historyless boundary plus the whole hysteresis band, so a remembered LOD is held one half
 * level past the point round-to-nearest would have dropped it. Finer than necessary costs tiles;
 * coarser than necessary costs sharpness, and sharpness is the one the eye reads as quality.
 */
private const val LOWER_HYSTERESIS_OFFSET: Double = 1.0
