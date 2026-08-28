package com.rohittp.reng.internal.label

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectGeographicPosition
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import com.rohittp.rentile.LabelBox
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateBatch
import com.rohittp.rentile.LabelGlyphAtlas
import com.rohittp.rentile.LabelGlyphEntry
import com.rohittp.rentile.LabelGlyphQuad
import com.rohittp.rentile.LabelLayerStyle
import com.rohittp.rentile.LabelLinePoint
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import com.rohittp.rentile.SymbolZOrder
import com.rohittp.rentile.TileId
import kotlin.test.assertIs

/*
 * Fixtures for point placement and collision.
 *
 * **Nothing here is symmetric, and every asymmetry is load-bearing.** The camera has an odd
 * non-square viewport, a non-integer zoom, and a bearing and a pitch that are both non-zero at once;
 * the shared anchor sits off-centre by different amounts in x and in y; the label box is wider than
 * it is tall and extends four times further left of the anchor than right of it; the glyph cell is
 * neither square nor centred, and the atlas is twice as tall as it is wide. A fixture at the
 * viewport centre with a centred square box discriminates none of the four bugs these cases exist to
 * catch -- a transposed x and y, a rotation applied in the wrong direction, a collision check that
 * reads one axis, and a padding counted twice.
 *
 * The candidates are built by hand rather than acquired from the engine on purpose: an acquisition
 * ends in Rentile's Skia glyph packer, which the Android host runtime cannot load, and every value
 * these cases turn on is a number the engine would have to be coaxed into producing exactly.
 */

/** Odd non-square viewport, non-integer zoom, bearing and pitch both off zero. */
internal val PLACEMENT_OUTPUT: OutputPixelSize = OutputPixelSize(width = 853, height = 509)

internal fun placementCamera(bearing: Double = 37.5): Camera = Camera(
    latitude = 48.858093,
    unwrappedLongitude = 2.294694,
    zoom = 13.7,
    bearing = bearing,
    pitch = 52.0,
)

/**
 * The anchor every collision case shares, roughly 209 pixels right of centre and 77 above it, so
 * neither screen axis can stand in for the other and no box below straddles a centre line.
 */
internal val PLACEMENT_ANCHOR: GeographicPosition =
    GeographicPosition(latitude = 48.856939, unwrappedLongitude = 2.309995, altitudeMetres = 0.0)

/** Far enough behind the camera plane that `w` goes negative rather than merely small. */
internal val PLACEMENT_ANCHOR_BEHIND: GeographicPosition =
    GeographicPosition(latitude = 48.832363, unwrappedLongitude = 2.244501, altitudeMetres = 0.0)

/**
 * The tile every candidate comes out of unless a case says otherwise. Task 13's label identity is
 * scoped by it, so a case about two labels that differ only in provenance varies this and nothing
 * else.
 */
internal val PLACEMENT_TILE: TileId = TileId(z = 13, x = 4237, y = 2887)

/** Twice as tall as it is wide, so a transposed atlas extent produces the wrong `v`. */
internal const val ATLAS_WIDTH: Int = 128
internal const val ATLAS_HEIGHT: Int = 256

/** A cell that is neither square nor at the atlas origin, with a non-unit scale. */
internal val PLACEMENT_ENTRY: LabelGlyphEntry = LabelGlyphEntry(
    fontStackDigest = "0f".repeat(32),
    codepoint = 'R'.code,
    x = 33,
    y = 51,
    width = 12,
    height = 20,
    left = 2,
    top = -17,
    advance = 14,
)

internal val PLACEMENT_ATLAS: LabelGlyphAtlas = LabelGlyphAtlas(
    pngBytes = ByteArray(0),
    width = ATLAS_WIDTH,
    height = ATLAS_HEIGHT,
    contentKey = "placement-atlas",
    entries = listOf(PLACEMENT_ENTRY),
)

/** Off the anchor in both axes and by different amounts on all four sides. */
internal val PLACEMENT_GLYPH: LabelGlyphQuad =
    LabelGlyphQuad(entryIndex = 0, x = 7.5, y = -13.25, scale = 0.75)

internal fun resolvedPlacementCamera(
    camera: Camera = placementCamera(),
    output: OutputPixelSize = PLACEMENT_OUTPUT,
): ResolvedMercatorCamera = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
    resolveMercatorCamera(camera = camera, outputPixelSize = output),
).value

internal fun projectedAnchor(
    camera: ResolvedMercatorCamera,
    position: GeographicPosition = PLACEMENT_ANCHOR,
): ScreenProjection.Projected =
    assertIs<ScreenProjection.Projected>(projectGeographicPosition(camera, position))

/**
 * One point candidate. Every collision-relevant field is a parameter and every irrelevant one is a
 * neutral default, so a case reads as the two or three numbers it is actually about.
 */
@Suppress("LongParameterList")
internal fun placementCandidate(
    left: Double = -30.0,
    top: Double = -11.0,
    right: Double = 70.0,
    bottom: Double = 9.0,
    sortKey: Double = 0.0,
    layerStyleIndex: Int = 0,
    overlap: SymbolOverlap = SymbolOverlap.NEVER,
    ignorePlacement: Boolean = false,
    padding: Double = 0.0,
    position: GeographicPosition = PLACEMENT_ANCHOR,
    sourceTile: TileId = PLACEMENT_TILE,
    placement: LabelPlacement = LabelPlacement.POINT,
    glyphs: List<LabelGlyphQuad> = listOf(PLACEMENT_GLYPH),
    translateX: Double = 0.0,
    translateY: Double = 0.0,
    translateAlignment: SymbolAlignment = SymbolAlignment.VIEWPORT,
    textRotationDegrees: Double = 0.0,
    rotationAlignment: SymbolAlignment = SymbolAlignment.VIEWPORT,
    color: Int = 0x4C1A2B3C,
    haloColor: Int = 0xE0F1D2C3.toInt(),
    opacity: Double = 1.0,
    haloWidth: Double = 0.0,
    haloBlur: Double = 0.0,
): LabelCandidate = LabelCandidate(
    layerStyleIndex = layerStyleIndex,
    requestedTile = sourceTile,
    sourceTile = sourceTile,
    longitude = position.unwrappedLongitude,
    latitude = position.latitude,
    placement = placement,
    line = emptyList(),
    rotationDegrees = 0.0,
    symbolSpacing = 250.0,
    keepUpright = true,
    avoidEdges = false,
    zOrder = SymbolZOrder.AUTO,
    textRotationDegrees = textRotationDegrees,
    maxAngleDegrees = 45.0,
    rotationAlignment = rotationAlignment,
    pitchAlignment = SymbolAlignment.AUTO,
    textOptional = false,
    glyphs = glyphs,
    boundingBox = LabelBox(left = left, top = top, right = right, bottom = bottom),
    icon = null,
    overlap = overlap,
    ignorePlacement = ignorePlacement,
    padding = padding,
    sortKey = sortKey,
    color = color,
    haloColor = haloColor,
    opacity = opacity,
    haloWidth = haloWidth,
    haloBlur = haloBlur,
    translateX = translateX,
    translateY = translateY,
    translateAlignment = translateAlignment,
)

/**
 * A batch over [candidates], with one layer style per distinct `layerStyleIndex` so that a case
 * choosing an index always finds a priority behind it. Layer priorities are the index times seven
 * plus three, which keeps them non-contiguous and never equal to the index itself.
 */
internal fun placementBatch(vararg candidates: LabelCandidate): LabelCandidateBatch {
    val layers = (0..(candidates.maxOfOrNull { it.layerStyleIndex } ?: 0)).map { index ->
        LabelLayerStyle(layerId = "layer-$index", zoom = 13, priority = index * 7 + 3)
    }
    return LabelCandidateBatch(
        candidates = candidates.toList(),
        layerStyles = layers,
        atlas = PLACEMENT_ATLAS,
        contentKey = "placement-batch",
        diagnostics = emptyList(),
    )
}

/*
 * Fixtures for line placement.
 *
 * **The symmetry points of line placement are all in the *line*, not in the camera**, and every one
 * of them is closed here rather than in the cases:
 *
 *  - a straight horizontal line gives every glyph the same tangent, never triggers `keepUpright` and
 *    never reaches the bend ceiling, so a completely broken tangent produces the right picture.
 *    [BENT_LINE] turns, and turns by more on one side of its vertex than the other;
 *  - a line running left-to-right never exercises `keepUpright`. [BACKWARD_LINE] runs the other way;
 *  - a symmetric arc cannot tell a tangent from a sign-flipped one, so [BENT_LINE]'s two legs have
 *    different lengths and its turn is not bisected by its midpoint;
 *  - a line of equal segments puts a `line-center` anchor on a vertex, which is the one position
 *    where interpolating along a segment and snapping to the nearer vertex agree. Every line here
 *    has unequal segments, and the walking case asserts that of the one it matters
 *    for;
 *  - a glyph row symmetric about its anchor makes a reversed reading order land on the same set of
 *    pixels. [LINE_GLYPH_LOCAL_X] is irregularly spaced and extends further right of the anchor
 *    than left of it.
 */

/**
 * Six glyph cells with irregular gaps, spanning `[-28, +40]` about the label anchor.
 *
 * Neither the spacing nor the extent is symmetric, so reversing the reading order moves every glyph
 * and changes which anchors the label fits at.
 */
internal val LINE_GLYPH_LOCAL_X: DoubleArray =
    doubleArrayOf(-28.0, -17.5, -5.0, 6.5, 20.0, 31.0)

/** The glyph row [LINE_GLYPH_LOCAL_X] describes, at the shared entry, cell and scale. */
internal fun lineGlyphRow(y: Double = -13.25, scale: Double = 0.75): List<LabelGlyphQuad> =
    LINE_GLYPH_LOCAL_X.map { x -> LabelGlyphQuad(entryIndex = 0, x = x, y = y, scale = scale) }

/**
 * A three-glyph row spanning `[-14, +18]`, short enough to fit on a run of a line broken by the
 * camera. Irregularly spaced and off-centre for the same reason [LINE_GLYPH_LOCAL_X] is.
 */
internal val SHORT_LINE_GLYPH_LOCAL_X: DoubleArray = doubleArrayOf(-14.0, -3.5, 9.0)

/** The glyph row [SHORT_LINE_GLYPH_LOCAL_X] describes. */
internal fun shortLineGlyphRow(y: Double = -13.25, scale: Double = 0.75): List<LabelGlyphQuad> =
    SHORT_LINE_GLYPH_LOCAL_X.map { x -> LabelGlyphQuad(entryIndex = 0, x = x, y = y, scale = scale) }

/**
 * Three glyphs spanning `[-48, +21]`, wide enough that a `line-center` instance on [LONG_LINE]
 * straddles **both** of its vertices -- the one case where the total turn across the label and the
 * largest turn between two neighbouring glyphs are different numbers.
 */
internal val WIDE_LINE_GLYPH_LOCAL_X: DoubleArray = doubleArrayOf(-48.0, -20.0, 12.0)

/** The glyph row [WIDE_LINE_GLYPH_LOCAL_X] describes. */
internal fun wideLineGlyphRow(y: Double = -13.25): List<LabelGlyphQuad> =
    WIDE_LINE_GLYPH_LOCAL_X.map { x -> LabelGlyphQuad(entryIndex = 0, x = x, y = y, scale = 0.75) }

/**
 * The same row wrapped onto two lines, the way Rentile lays a label out past `text-max-width`: the
 * second row restarts at the label's own left edge, one line height further down.
 *
 * In array order the pair straddling the row break jumps from the label's right edge back to its
 * left, which is a turn no reader ever sees.
 */
internal fun twoRowLineGlyphs(): List<LabelGlyphQuad> =
    wideLineGlyphRow() + wideLineGlyphRow(y = -13.25 + 24.0)

/** Each cell's width in label-local units: the entry's own width at the row's scale. */
internal const val LINE_GLYPH_CELL_WIDTH: Double = 12.0 * 0.75

/** A long, gently irregular line running roughly west to east, for the walking cases. */
internal val LONG_LINE: List<LabelLinePoint> = listOf(
    LabelLinePoint(longitude = 2.30190, latitude = 48.85520),
    LabelLinePoint(longitude = 2.30585, latitude = 48.85604),
    LabelLinePoint(longitude = 2.31130, latitude = 48.85661),
    LabelLinePoint(longitude = 2.31940, latitude = 48.85712),
)

/** Two legs of different lengths meeting at an asymmetric turn. */
internal val BENT_LINE: List<LabelLinePoint> = listOf(
    LabelLinePoint(longitude = 2.30640, latitude = 48.85410),
    LabelLinePoint(longitude = 2.30930, latitude = 48.85690),
    LabelLinePoint(longitude = 2.31710, latitude = 48.85742),
)

/** The same shape traversed the other way, so the screen tangent points leftward. */
internal val BACKWARD_LINE: List<LabelLinePoint> = LONG_LINE.reversed()

/** Far shorter than [LINE_GLYPH_LOCAL_X]'s row, so nothing fits on it. */
internal val STUB_LINE: List<LabelLinePoint> = listOf(
    LabelLinePoint(longitude = 2.30980, latitude = 48.85655),
    LabelLinePoint(longitude = 2.31005, latitude = 48.85661),
)

/**
 * [LONG_LINE] with a point far behind the camera spliced into the middle, so it projects to two
 * runs with a gap that no straight segment spans.
 */
internal val BROKEN_LINE: List<LabelLinePoint> = listOf(
    LONG_LINE[0],
    LONG_LINE[1],
    LabelLinePoint(
        longitude = PLACEMENT_ANCHOR_BEHIND.unwrappedLongitude,
        latitude = PLACEMENT_ANCHOR_BEHIND.latitude,
    ),
    LONG_LINE[2],
    LONG_LINE[3],
)

/**
 * A line from a vertex **just** in front of the near plane to the shared anchor, which projects to a
 * run about 390,000 pixels long.
 *
 * Nothing about it is exotic: it is a road passing beneath a camera pitched at 52 degrees, and its
 * far vertex has a `w` of 1.05 logical pixels. It is here because a projected run's length is
 * bounded by nothing the caller sets, which is what `MAXIMUM_ANCHORS_PER_RUN` exists for.
 */
internal val NEAR_PLANE_LINE: List<LabelLinePoint> = listOf(
    LabelLinePoint(longitude = 2.2500, latitude = 48.84655),
    LabelLinePoint(
        longitude = PLACEMENT_ANCHOR.unwrappedLongitude,
        latitude = PLACEMENT_ANCHOR.latitude,
    ),
)

/** One line candidate: [placementCandidate] with the line-only fields exposed. */
@Suppress("LongParameterList")
internal fun lineCandidate(
    line: List<LabelLinePoint> = LONG_LINE,
    placement: LabelPlacement = LabelPlacement.LINE,
    symbolSpacing: Double = 250.0,
    maxAngleDegrees: Double = 45.0,
    keepUpright: Boolean = true,
    padding: Double = 0.0,
    sortKey: Double = 0.0,
    overlap: SymbolOverlap = SymbolOverlap.NEVER,
    glyphs: List<LabelGlyphQuad> = lineGlyphRow(),
    translateX: Double = 0.0,
    translateY: Double = 0.0,
    translateAlignment: SymbolAlignment = SymbolAlignment.VIEWPORT,
): LabelCandidate = placementCandidate(
    placement = placement,
    glyphs = glyphs,
    padding = padding,
    sortKey = sortKey,
    overlap = overlap,
    translateX = translateX,
    translateY = translateY,
    translateAlignment = translateAlignment,
).copy(
    line = line,
    symbolSpacing = symbolSpacing,
    maxAngleDegrees = maxAngleDegrees,
    keepUpright = keepUpright,
)
