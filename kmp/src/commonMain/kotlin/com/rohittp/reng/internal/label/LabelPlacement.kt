package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.gl.ResolvedGlyphQuad
import com.rohittp.reng.internal.gl.ResolvedLabelPaint
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectGeographicPosition
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateBatch
import com.rohittp.rentile.LabelGlyphAtlas
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Point label placement and collision: where each label's glyph quads land in output pixels, and
 * which labels lose their place to a higher-priority neighbour.
 *
 * **This is the first thing in RenG that exists to drop content, and that is deliberate rather
 * than an oversight in the layer above it.** `CONTEXT.md`'s Tile Budget entry states the house rule
 * every other budget follows -- "RenG never drops required tiles"; a resource ceiling reached during
 * preparation fails the frame instead of quietly rendering less than the caller asked for, because a
 * caller who asked for a model and got a frame without one has no way to tell. Collision inverts
 * that on purpose, for two reasons that do not apply anywhere else:
 *
 *  1. **Nobody asked for these.** Every other drawn thing is an entry in the caller's own
 *     `FramePlan`. Labels are derived by the engine from the style and the tiles, so there is no
 *     request to fail and no consumer field to name in a diagnostic. Failing the frame because two
 *     engine-derived labels wanted the same 40 pixels would make `drawLabels = true` a coin toss.
 *  2. **Dropping *is* the feature.** Two labels drawn over each other are two labels nobody can
 *     read, so a renderer that placed both would produce strictly less information than one that
 *     placed one. Every map renderer resolves it the same way, and a style that does not want it
 *     says `text-overlap: always`.
 *
 * What is *not* dropped silently is the count: task 14's `LABEL_CONTENT_EXCLUDED` diagnostic reports
 * engine-side exclusions, and this pass's own losses are visible in the placed list's length. This
 * comment exists because a reader who knows the house rule will otherwise read the `continue` in
 * [placeLabels] as a bug.
 *
 * **Scope.** This file owns point placement, priority and collision; [layOutLineLabels] owns the
 * other two placement modes and hands back the same [PlacedLabel]s, which then take exactly the same
 * route through the index below. A line candidate yields *several* of them -- one per repeat along
 * the line -- and two repeats of one road name are as capable of colliding with each other as two
 * different labels are, which is why the loop iterates a list rather than an optional. Icons are
 * task 12's and fade is task 13's; `zOrder` and `avoidEdges` are carried by the engine and not yet
 * honoured here, which is recorded in the cycle's ledger rather than hidden.
 */
internal fun placeLabels(
    camera: ResolvedMercatorCamera,
    batch: LabelCandidateBatch,
): List<PlacedLabel> {
    if (batch.candidates.isEmpty()) return emptyList()

    val viewport = LabelScreenBox(
        left = 0.0,
        top = 0.0,
        right = camera.outputPixelSize.width.toDouble(),
        bottom = camera.outputPixelSize.height.toDouble(),
    )
    val index = LabelCollisionIndex(viewport)
    val placed = ArrayList<PlacedLabel>()

    for (candidateIndex in batch.candidates.indices.sortedByPriority(batch)) {
        val candidate = batch.candidates[candidateIndex]
        val labels = when (candidate.placement) {
            LabelPlacement.POINT ->
                listOfNotNull(layOutPointLabel(camera, batch.atlas, candidate, candidateIndex))

            LabelPlacement.LINE, LabelPlacement.LINE_CENTER ->
                layOutLineLabels(camera, batch.atlas, candidate, candidateIndex)
        }

        for (label in labels) {
            if (!label.collisionBox.intersects(viewport)) continue

            // The drop. `never` -- and `cooperative`, see [resolvesAsNever] -- yields to anything
            // already placed; `always` never yields. Either way the label occupies the index
            // afterwards unless `text-ignore-placement` says it is invisible to the pass, which is
            // the style's way of asking for a label that neither yields nor blocks.
            if (candidate.overlap.resolvesAsNever() && index.intersectsAnything(label.collisionBox)) continue
            if (!candidate.ignorePlacement) index.insert(label.collisionBox)
            placed += label
        }
    }

    // Highest priority last, so that where two labels do share pixels -- which only `always` and
    // `ignorePlacement` allow -- the one that would have won the collision paints over the other.
    // The pipeline draws a batch in exactly the order it is handed, and sorts nothing itself.
    return placed.asReversed()
}

/**
 * One label that survived, in output-pixel screen space.
 *
 * [candidateIndex] indexes [LabelCandidateBatch.candidates] and is carried rather than derived so
 * that a caller can recover the engine's own record of a placed label -- task 13's fade needs
 * exactly that, and recomputing it by identity comparison over a data class would be O(n) per label.
 *
 * **It is not unique across a frame, and [lineRepeat] rather than the anchor is what tells the
 * repeats apart.** A `line`-placed candidate repeats its label every `symbol-spacing` pixels along
 * its source line, and every repeat is one of these carrying that one candidate's index.
 * [anchorPixelX] and [anchorPixelY] do separate them *within* this frame, and they are still the
 * wrong thing for a consumer to reach for: they are where the label landed under **this** camera, so
 * anything keyed on them is keyed on a number that changes the moment the map moves. A consumer that
 * needs a per-label identity -- `deriveLabelIdentity` does -- takes [candidateIndex] together with
 * [lineRepeat], which says which repeat this is in terms no camera move rewrites.
 */
internal class PlacedLabel(
    val candidateIndex: Int,
    val lineRepeat: LineRepeat?,
    val anchorPixelX: Double,
    val anchorPixelY: Double,
    val collisionBox: LabelScreenBox,
    val quads: List<ResolvedGlyphQuad>,
)

/**
 * Which repeat of one `line`-placed candidate a [PlacedLabel] is, in the parameter the walk itself
 * steps in rather than in the pixels the walk produced.
 *
 * `null` means the candidate placed exactly **one** instance, so there is nothing to tell apart:
 * every point label, and every `line-center` one. `line-center` is the arm worth stating outright,
 * because it does have an along-line distance and that distance must not be carried -- its single
 * anchor sits at half the *projected* run's length, which is a different number every time the
 * camera zooms or pitches, so recording it would record a camera fact under the name of a feature
 * one.
 *
 * [anchorDistancePixels] is half a `symbol-spacing` plus one spacing per repeat before it, which is
 * a function of the candidate's own spacing and of *which* repeat this is and of nothing else. The
 * camera decides how many repeats a run has room for; it does not decide the distance at which
 * repeat *k* sits. [runIndex] is the run that distance is measured along, because a line broken by
 * the near plane restarts its arc length at every run -- so distance alone would put the first
 * repeat of run 0 and the first repeat of run 1 at the same number and collapse two labels that are
 * nowhere near each other on the road.
 */
internal class LineRepeat(
    val runIndex: Int,
    val anchorDistancePixels: Double,
)

/**
 * An axis-aligned rectangle in `CONTEXT.md`'s continuous output-pixel screen space: origin top-left,
 * positive y downward, so [top] is always the smaller of the two vertical bounds.
 *
 * **Axis-aligned even for a rotated label.** A rotated label's collision geometry is the screen-space
 * bounding rectangle of its rotated box, which is the shape MapLibre also collides with and is
 * conservative in the safe direction: it claims slightly more room than the ink needs, so it drops a
 * label that would have just fitted rather than admitting one that would have overlapped.
 */
internal data class LabelScreenBox(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    /**
     * Strict on all four edges, so two boxes that merely touch do not collide.
     *
     * All four comparisons are required and each one is load-bearing: dropping either axis leaves a
     * check that reports a collision for any pair overlapping in the *other* axis alone, which is
     * every pair of labels sharing a row of the screen.
     */
    fun intersects(other: LabelScreenBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * A uniform grid over the viewport, so that placement stays linear in the candidate count.
 *
 * E5 ships **no public ceiling** on how many labels a frame may carry and task 17 measures the real
 * counts before one is proposed, so this pass has to be honest about the quadratic it would
 * otherwise be: every candidate tested against every placed label is the shape that turns a dense
 * downtown viewport into a frame-time cliff, and it would not show up in any fixture small enough to
 * write by hand.
 *
 * **Only boxes that intersect the viewport are ever inserted or queried**, which is what lets a
 * box's cell range be clamped to the viewport's own cells. Two rectangles that both meet the
 * viewport and meet each other necessarily meet each other *inside* the viewport -- their overlap
 * interval on each axis has its far end inside the viewport's own interval on that axis -- so the
 * clamp cannot hide an intersection.
 */
private class LabelCollisionIndex(private val viewport: LabelScreenBox) {
    private val cells = HashMap<Long, MutableList<LabelScreenBox>>()

    fun intersectsAnything(box: LabelScreenBox): Boolean {
        forEachCell(box) { key ->
            val occupants = cells[key] ?: return@forEachCell
            for (occupant in occupants) if (box.intersects(occupant)) return true
        }
        return false
    }

    fun insert(box: LabelScreenBox) {
        forEachCell(box) { key ->
            // A box spanning several cells is listed in each of them, so a query touching any one
            // of those cells finds it. Duplicates within one cell are impossible; duplicates across
            // cells are the point.
            cells.getOrPut(key) { ArrayList(SMALL_CELL_CAPACITY) } += box
        }
    }

    private inline fun forEachCell(box: LabelScreenBox, action: (Long) -> Unit) {
        val firstColumn = cellIndex(max(box.left, viewport.left))
        val lastColumn = cellIndex(min(box.right, viewport.right))
        val firstRow = cellIndex(max(box.top, viewport.top))
        val lastRow = cellIndex(min(box.bottom, viewport.bottom))
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                action(row.toLong() shl Int.SIZE_BITS or (column.toLong() and COLUMN_MASK))
            }
        }
    }

    private fun cellIndex(coordinate: Double): Int =
        floor(coordinate / LABEL_COLLISION_CELL_PIXELS).toInt()

    private companion object {
        const val SMALL_CELL_CAPACITY: Int = 4
        const val COLUMN_MASK: Long = 0xffffffffL
    }
}

/**
 * Projects one point candidate's anchor, moves it by `text-translate`, and lays its label-local
 * quads out around the result, or returns `null` when the label has no place on this screen.
 *
 * `null` is never a frame failure. [projectGeographicPosition] is total precisely so that one
 * unprojectable anchor drops one label -- an anchor behind the camera, past the horizon, or outside
 * Mercator support has no pixel, and a label with no pixel is not a candidate for anything. The same
 * applies to the finiteness guard: engine-derived numbers cross the firewall, and a NaN corner would
 * compare false against every bound, survive the viewport cull and then poison collision for every
 * label after it.
 */
private fun layOutPointLabel(
    camera: ResolvedMercatorCamera,
    atlas: LabelGlyphAtlas,
    candidate: LabelCandidate,
    candidateIndex: Int,
): PlacedLabel? {
    if (!candidate.hasFinitePlacementInputs()) return null

    val anchor = projectGeographicPosition(
        camera,
        GeographicPosition(
            latitude = candidate.latitude,
            unwrappedLongitude = candidate.longitude,
            altitudeMetres = 0.0,
        ),
    )
    if (anchor !is ScreenProjection.Projected) return null

    // The map frame's own rotation, read off the camera's basis rather than from `Camera.bearing`,
    // for the same reason `projectGeographicPosition` reuses the camera's matrices: two derivations
    // of the same angle are two chances to disagree. `right` is the world direction that maps to
    // screen +x, so `right.x` is the bearing's cosine and `-right.y` its sine, and the map-to-screen
    // rotation is by the negated bearing -- clockwise in a y-down space.
    val cosineMap = camera.right.x
    val sineMap = camera.right.y

    // `text-translate-anchor` defaults to `map` in the style specification, and Rentile refuses
    // `auto` for it, so the `AUTO` arm is unreachable rather than merely unlikely -- it takes the
    // documented default rather than inventing a third behaviour.
    val translatesWithMap = candidate.translateAlignment != SymbolAlignment.VIEWPORT
    val translateX: Double
    val translateY: Double
    if (translatesWithMap) {
        translateX = rotatedX(candidate.translateX, candidate.translateY, cosineMap, sineMap)
        translateY = rotatedY(candidate.translateX, candidate.translateY, cosineMap, sineMap)
    } else {
        translateX = candidate.translateX
        translateY = candidate.translateY
    }
    val anchorX = anchor.pixelX + translateX
    val anchorY = anchor.pixelY + translateY

    // `text-rotate` is clockwise on screen, and clockwise in a y-down space is the ordinary positive
    // rotation. Under `text-rotation-alignment: map` the whole label additionally turns with the
    // map; `auto` means `viewport` for point placement, which is what the style specification says
    // and the only reading under which an unrotated map leaves point labels horizontal.
    val cosineText = cos(candidate.textRotationDegrees * RADIANS_PER_DEGREE)
    val sineText = sin(candidate.textRotationDegrees * RADIANS_PER_DEGREE)
    val alignedToMap = candidate.rotationAlignment == SymbolAlignment.MAP
    val cosine = if (alignedToMap) cosineText * cosineMap - sineText * sineMap else cosineText
    val sine = if (alignedToMap) sineText * cosineMap + cosineText * sineMap else sineText

    val quads = ArrayList<ResolvedGlyphQuad>(candidate.glyphs.size)
    var paint: ResolvedLabelPaint? = null
    for (glyph in candidate.glyphs) {
        val cell = atlas.glyphCellOrNull(glyph) ?: return null

        // One paint per label rather than per glyph: `text-size` is a layer property, so every quad
        // of one label carries the same scale, and the pipeline reads the paint per quad anyway.
        // Re-deriving it when a scale does differ costs one allocation and keeps the seam honest.
        val existing = paint
        paint = if (existing != null && existing.scale == cell.scale) {
            existing
        } else {
            candidate.resolvePaint(cell.scale)
        }

        // The whole label shares one frame: its origin is the anchor, its along-label axis is the
        // label's own rotation, and label-local x is measured from the anchor rather than from the
        // glyph, which is [GlyphFrame.pivotLocalX] = 0.
        quads += cell.resolveQuad(
            frame = GlyphFrame(
                originX = anchorX,
                originY = anchorY,
                axisX = cosine,
                axisY = sine,
                pivotLocalX = 0.0,
            ),
            paint = paint,
        ) ?: return null
    }

    val box = candidate.boundingBox
    // The engine's box is label-local and **already carries `text-padding` on all four sides** --
    // `LabelBox`'s own KDoc says so and Rentile's layout applies it there -- so `padding` is read
    // here for nothing but the record. Expanding by it again would double every style's padding,
    // and a doubled padding is invisible in a screenshot and wrong in every collision.
    val corners = doubleArrayOf(
        rotatedX(box.left, box.top, cosine, sine), rotatedY(box.left, box.top, cosine, sine),
        rotatedX(box.right, box.top, cosine, sine), rotatedY(box.right, box.top, cosine, sine),
        rotatedX(box.right, box.bottom, cosine, sine), rotatedY(box.right, box.bottom, cosine, sine),
        rotatedX(box.left, box.bottom, cosine, sine), rotatedY(box.left, box.bottom, cosine, sine),
    )
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY
    for (corner in corners.indices step 2) {
        left = min(left, corners[corner])
        right = max(right, corners[corner])
        top = min(top, corners[corner + 1])
        bottom = max(bottom, corners[corner + 1])
    }
    val collisionBox = LabelScreenBox(
        left = anchorX + left,
        top = anchorY + top,
        right = anchorX + right,
        bottom = anchorY + bottom,
    )
    if (!collisionBox.isFinite()) return null

    return PlacedLabel(
        candidateIndex = candidateIndex,
        // A point candidate places one label, so it has no repeat to be distinguished from.
        lineRepeat = null,
        anchorPixelX = anchorX,
        anchorPixelY = anchorY,
        collisionBox = collisionBox,
        quads = quads,
    )
}

/**
 * Priority order: `symbol-sort-key` first, then the layer's own position in the style, both
 * ascending scales on which the **larger value wins**.
 *
 * The two are ranked rather than combined, and in that order, because they answer different
 * questions. `sortKey` is what the style author wrote about *these features* -- 680 layers across 28
 * corpus styles carry one -- and layer order is the implicit default that applies when they wrote
 * nothing; `LabelLayerStyle.priority`'s own KDoc says it "exists so that two consumers resolve
 * **ties** the same way", which is the tiebreaker's job description.
 *
 * [sortedBy] is stable, so candidates equal on both keys keep the engine's own batch order and the
 * pass stays deterministic -- which ADR 0035 requires, since collision resolves during `prepare()`
 * and a Prepared Frame may be drawn repeatedly.
 */
private fun IntRange.sortedByPriority(batch: LabelCandidateBatch): List<Int> =
    sortedWith(
        compareByDescending<Int> { batch.candidates[it].sortKey }
            .thenByDescending { batch.layerPriorityOf(batch.candidates[it]) },
    )

/**
 * The layer's declaration order, or [Int.MIN_VALUE] when the candidate names an entry the batch does
 * not carry. An out-of-range index is not representable through Rentile's own assembler; treating it
 * as the lowest priority loses a place rather than an index bound.
 */
private fun LabelCandidateBatch.layerPriorityOf(candidate: LabelCandidate): Int =
    layerStyles.getOrNull(candidate.layerStyleIndex)?.priority ?: Int.MIN_VALUE

/**
 * **`cooperative` is resolved as `never`, by decision rather than by omission.** It occurs zero times
 * in the 34-style corpus, and Rentile deliberately keeps it distinct rather than collapsing it, so
 * the collapse happens here where it can be seen. Cooperative placement negotiates a mutual offset
 * between two symbols, which is a placement search this cycle does not have; refusing the label is
 * the conservative half of that negotiation.
 */
private fun SymbolOverlap.resolvesAsNever(): Boolean = this != SymbolOverlap.ALWAYS

/**
 * The candidate's paint in the pipeline's vocabulary: straight RGBA unpacked from the engine's
 * `0xAARRGGBB`, with `text-opacity` carried separately because [ResolvedLabelPaint] folds it into
 * both alphas at vertex assembly and task 13's fade multiplies into the same field.
 */
internal fun LabelCandidate.resolvePaint(scale: Float): ResolvedLabelPaint = ResolvedLabelPaint(
    textColour = straightRgba(color),
    haloColour = straightRgba(haloColor),
    opacity = opacity.toFloat(),
    haloWidthPixels = haloWidth.toFloat(),
    haloBlurPixels = haloBlur.toFloat(),
    scale = scale,
)

/** `0xAARRGGBB` as four straight components in `[0, 1]`, in RGBA order. */
internal fun straightRgba(packed: Int): FloatArray = floatArrayOf(
    ((packed ushr 16) and BYTE_MASK) / BYTE_MAXIMUM,
    ((packed ushr 8) and BYTE_MASK) / BYTE_MAXIMUM,
    (packed and BYTE_MASK) / BYTE_MAXIMUM,
    ((packed ushr 24) and BYTE_MASK) / BYTE_MAXIMUM,
)

/**
 * Every scalar read before a pixel is computed, checked in one place.
 *
 * `sortKey` is here rather than in the ordering because a non-finite key would order consistently
 * and then place a label whose priority means nothing; `opacity`, `haloWidth` and `haloBlur` are
 * here because they reach a `FloatArray` the GPU reads. Rentile validates all of these on its own
 * side -- this is the firewall's half of that, and it drops one label rather than a frame.
 */
internal fun LabelCandidate.hasFinitePlacementInputs(): Boolean =
    sortKey.isFinite() &&
        translateX.isFinite() && translateY.isFinite() &&
        textRotationDegrees.isFinite() &&
        opacity.isFinite() && opacity >= 0.0 &&
        haloWidth.isFinite() && haloWidth >= 0.0 &&
        haloBlur.isFinite() && haloBlur >= 0.0 &&
        boundingBox.left.isFinite() && boundingBox.top.isFinite() &&
        boundingBox.right.isFinite() && boundingBox.bottom.isFinite()

internal fun LabelScreenBox.isFinite(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

/** `(x, y)` turned clockwise on a y-down screen: `x cos - y sin`. */
internal fun rotatedX(x: Double, y: Double, cosine: Double, sine: Double): Double =
    x * cosine - y * sine

/** `(x, y)` turned clockwise on a y-down screen: `x sin + y cos`. */
internal fun rotatedY(x: Double, y: Double, cosine: Double, sine: Double): Double =
    x * sine + y * cosine

/**
 * The collision grid's cell edge, in output pixels. Wide enough that an ordinary label spans one or
 * two cells and narrow enough that a dense viewport spreads over many; nothing about correctness
 * depends on the value, only the amount of work.
 */
private const val LABEL_COLLISION_CELL_PIXELS: Double = 64.0

private const val BYTE_MASK: Int = 0xff
private const val BYTE_MAXIMUM: Float = 255.0f
private const val RADIANS_PER_DEGREE: Double = PI / 180.0
