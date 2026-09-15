package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.ClosedMercatorFootprint
import com.rohittp.reng.internal.projection.GroundRayResult
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.footprintForAdmissibleRowRange
import com.rohittp.reng.internal.projection.physicalPixelGroundRay
import com.rohittp.reng.internal.projection.pixelRayCoordinates
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.sqrt

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

/**
 * One horizontal band of the frame whose rows all resolve to a single basemap [lod] (ADR 0065).
 * [firstRow] and [lastRow] are inclusive physical pixel rows, and adjacent bands abut exactly.
 */
internal data class GroundLodBand(
    val firstRow: Int,
    val lastRow: Int,
    val lod: Int,
)

/**
 * The frame's ground, decomposed into bands that each carry their own LOD (ADR 0065).
 *
 * **The decomposition is one-dimensional, and that is the whole reason this is a row walk rather
 * than a quadtree descent.** On a plane with a level horizon a ray's angle from the downward axis is
 * `theta = pitch + atan(v)` exactly, and `v` is a function of the row, so every pixel in a row sees
 * the ground at the same angle and wants the same level of detail. MapLibre GL JS descends a
 * quadtree for the same rule because it must also serve a globe and terrain, where distance is
 * genuinely two-dimensional; ADR 0067 keeps both of those on one LOD per frame here, which is what
 * leaves this free to be a scan.
 *
 * Bands come back in row order, which is coarse to fine: the offsets decrease monotonically from the
 * bottom of the frame toward the horizon. Two consequences ride on that ordering and neither is
 * incidental -- a short band always merges into the *finer* of its neighbours with no tie to break,
 * and the draw order that results puts the finer tile on top wherever two bands' tiles overlap at a
 * shared edge.
 */
internal fun groundLodBands(
    camera: ResolvedMercatorCamera,
    selectedLod: Int,
): List<GroundLodBand> {
    require(selectedLod in MINIMUM_LOD..MAXIMUM_LOD) {
        "selectedLod must be within the Mercator LOD range"
    }

    val height = camera.outputPixelSize.height
    var firstAdmissibleRow = -1
    var lastAdmissibleRow = -1
    for (pixelY in 0 until height) {
        if (physicalPixelGroundRay(camera, pixelX = 0, pixelY = pixelY) is GroundRayResult.Hit) {
            if (firstAdmissibleRow < 0) firstAdmissibleRow = pixelY
            lastAdmissibleRow = pixelY
        }
    }
    if (firstAdmissibleRow < 0) return emptyList()

    val cosinePitch = camera.cameraBack.z
    val raw = ArrayList<GroundLodBand>()
    for (pixelY in firstAdmissibleRow..lastAdmissibleRow) {
        val hit = physicalPixelGroundRay(camera, pixelX = 0, pixelY = pixelY) as GroundRayResult.Hit
        val v = pixelRayCoordinates(camera.outputPixelSize, 0, pixelY).v
        val lod = groundLodForRow(q = hit.q, v = v, cosinePitch = cosinePitch, selectedLod = selectedLod)
        val open = raw.lastOrNull()
        if (open != null && open.lod == lod) {
            raw[raw.size - 1] = open.copy(lastRow = pixelY)
        } else {
            raw.add(GroundLodBand(firstRow = pixelY, lastRow = pixelY, lod = lod))
        }
    }

    return mergeBandsTooShortToEarnTheirOwnSelection(raw, height)
}

/**
 * `lod(row) = selectedLod + floor(1.5 * log2(cos(theta) / cos(pitch)) + 0.5)`, the equal-screen-area
 * rule of ADR 0065 with MapLibre's tuning exponent `b` at 1.
 *
 * The cosine is not computed from an angle: with `theta = pitch + atan(v)`,
 * `cos(theta) = q / sqrt(1 + v * v)`, and both `q` and `v` are already in hand, so a row costs one
 * square root and one logarithm.
 *
 * Written as an offset from [selectedLod] rather than as an absolute level, which is the deliberate
 * divergence from MapLibre recorded in ADR 0065: their centre tile coarsens with pitch, and anchoring
 * here keeps the centre of the frame exactly as sharp as [observeMercatorLod] chose to make it. The
 * clamp is the same `0..22` every other LOD on this path carries, and it is what keeps a camera a
 * fraction of a degree off the horizon -- where `cos(pitch)` vanishes and the ratio runs away -- from
 * asking for a level that does not exist.
 */
private fun groundLodForRow(
    q: Double,
    v: Double,
    cosinePitch: Double,
    selectedLod: Int,
): Int {
    val cosineRatio = q / (cosinePitch * sqrt(1.0 + v * v))
    if (!cosineRatio.isFinite() || cosineRatio <= 0.0) return selectedLod
    val offset = floor(EQUAL_SCREEN_AREA_EXPONENT * log2(cosineRatio) + 0.5)
    if (!offset.isFinite()) return selectedLod
    return (selectedLod + offset.toInt()).coerceIn(MINIMUM_LOD, MAXIMUM_LOD)
}

/**
 * A band costs its own tile selection, and `selectBasemapTiles` admits every tile *intersecting* a
 * footprint, so each extra band pays for the tiles straddling one more shared edge (ADR 0065).
 *
 * Measured, that overhead is not hypothetical: at 20 degrees of pitch the unmerged rule peels a
 * 34-row band off the top of a 1080x1920 frame -- 1.8% of its height -- and the split costs two
 * tiles more than the coarser level saves. Requiring a band to be worth at least a sixteenth of the
 * frame removes that regression outright and is also the best of the thresholds measured at the
 * steep end.
 *
 * A band merges forward, into the next band in row order, which is always the finer one -- so a
 * merged band is never blurrier than the rule asked for. A trailing band too short to stand merges
 * backward instead, adopting its predecessor's level for the same reason in the only direction left.
 */
private fun mergeBandsTooShortToEarnTheirOwnSelection(
    bands: List<GroundLodBand>,
    outputHeight: Int,
): List<GroundLodBand> {
    if (bands.size <= 1) return bands
    val minimumRows = outputHeight / MINIMUM_BAND_FRACTION_OF_FRAME

    val merged = ArrayList<GroundLodBand>()
    var carriedFirstRow = -1
    for ((index, band) in bands.withIndex()) {
        val firstRow = if (carriedFirstRow >= 0) carriedFirstRow else band.firstRow
        val rows = band.lastRow - firstRow + 1
        if (rows < minimumRows && index < bands.size - 1) {
            carriedFirstRow = firstRow
            continue
        }
        carriedFirstRow = -1
        merged.add(band.copy(firstRow = firstRow))
    }

    val last = merged.last()
    if (merged.size > 1 && last.lastRow - last.firstRow + 1 < minimumRows) {
        val previous = merged[merged.size - 2]
        merged.removeAt(merged.size - 1)
        merged[merged.size - 1] = previous.copy(lastRow = last.lastRow)
    }
    return merged
}

/**
 * Every band's tiles, selected at that band's own level and unioned (ADR 0065).
 *
 * `selectBasemapTiles` is called once per band and is not modified: a band is a footprint and a
 * level, which is exactly what it already takes. Bands never share a level -- the offsets are
 * monotone in row -- so concatenating the selections cannot produce a duplicate instance, and the
 * row order the bands arrive in is the coarse-to-fine draw order ADR 0065 relies on.
 *
 * The budget is applied to the total rather than to each band, because a caller who asked for 512
 * instances asked for 512 in the frame. Each band is nonetheless offered the whole budget so that
 * no single band fails on a share of it that the frame as a whole would not have needed.
 */
internal fun selectBandedBasemapTiles(
    camera: ResolvedMercatorCamera,
    selectedLod: Int,
    maximumBasemapTileInstances: Int,
): TileSelectionOutcome {
    require(maximumBasemapTileInstances > 0) { "maximumBasemapTileInstances must be positive" }

    val bands = groundLodBands(camera, selectedLod)
    if (bands.isEmpty()) return selectBasemapTiles(ClosedMercatorFootprint.Empty, selectedLod, maximumBasemapTileInstances)

    val instances = ArrayList<BasemapTileInstance>()
    val canonicalResources = ArrayList<CanonicalBasemapTile>()
    var total = 0L
    for (band in bands) {
        val footprint = footprintForAdmissibleRowRange(camera, band.firstRow, band.lastRow)
        when (
            val selection = selectBasemapTiles(
                footprint = footprint,
                lod = band.lod,
                maximumInstances = maximumBasemapTileInstances,
            )
        ) {
            is TileSelectionOutcome.OverBudget -> return selection
            is TileSelectionOutcome.Success -> {
                val selected = selection.instances
                total += selected.size.toLong()
                if (total > maximumBasemapTileInstances.toLong()) {
                    return TileSelectionOutcome.OverBudget(
                        limit = maximumBasemapTileInstances,
                        actual = total,
                    )
                }
                instances.addAll(selected)
                canonicalResources.addAll(selection.canonicalResources)
            }
        }
    }
    return TileSelectionOutcome.Success(instances, canonicalResources)
}

/**
 * `b / 2 + 1` with MapLibre's tuning exponent `b` at 1: the equal-screen-area case, where a tile's
 * projected area on screen is the same near the camera as it is near the horizon, and the frame's
 * tile count therefore stops growing with pitch. See ADR 0065 for why `b = 1` is written here
 * directly rather than through the `maxZoomLevelsOnScreen` indirection MapLibre expresses it with.
 */
private const val EQUAL_SCREEN_AREA_EXPONENT: Double = 1.5

/** A band shorter than a thirty-second of the frame is merged rather than selected for separately. */
private const val MINIMUM_BAND_FRACTION_OF_FRAME: Int = 32
