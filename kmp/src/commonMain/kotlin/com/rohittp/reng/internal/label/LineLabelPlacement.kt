package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.reng.internal.gl.ResolvedGlyphQuad
import com.rohittp.reng.internal.gl.ResolvedLabelPaint
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectGeographicPosition
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelGlyphAtlas
import com.rohittp.rentile.LabelLinePoint
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.SymbolAlignment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Line placement: `symbol-placement: line` and `line-center`, which are 375 layers across 31 of the
 * 34 corpus styles and the largest single piece of this cycle.
 *
 * **Rentile does none of the curve.** It hands over the full source line as geographic points, a
 * tangent hint, `symbol-spacing`, `text-max-angle` and `text-keep-upright` -- and then lays the
 * glyph quads out as **one horizontal row for all three placement modes**, because `LabelLayout` has
 * no concept of placement at all. Everything between that row and a road name following a road is
 * here:
 *
 *  1. **Project the line.** Every source point goes through
 *     [projectGeographicPosition], which is a sealed answer rather than a pixel precisely so that a
 *     point behind the camera cannot become a plausible pixel on the wrong side of the screen. See
 *     [projectLineRuns] for what a line that is only partly on screen becomes.
 *  2. **Walk it at `symbolSpacing`.** [LabelPlacement.LINE] repeats the label along the run;
 *     [LabelPlacement.LINE_CENTER] places one instance at the centre instead.
 *  3. **Distribute the glyphs along the curve.** Each glyph is sampled at its own arc length and
 *     drawn in its own [GlyphFrame], so the row bends with the road rather than turning rigidly.
 *  4. **Enforce `maxAngleDegrees`.** An instance whose glyph-to-glyph turn exceeds the ceiling is
 *     refused rather than drawn contorted -- see [exceedsBendCeiling].
 *  5. **Honour `keepUpright`.** A line running right-to-left is read backwards so the text still
 *     reads left-to-right; see [uprightDirection].
 *
 * **The tangent hint is deliberately not used.** `LabelCandidate.rotationDegrees` is
 * `atan2` over two *tile* coordinates at the line's midpoint, taken with no camera in the room, so
 * it is the tangent the line would have on a north-up, unpitched screen and nothing else. Under any
 * bearing or any pitch it disagrees with the tangent the line actually has, and the projected
 * polyline is what the glyphs are drawn against, so consulting the hint would be a second answer to
 * a question that already has one. Nothing here reads it.
 *
 * **`text-rotate` is not added on top either.** For line placement the glyph's orientation is the
 * line's; an authored rotation applied as well would tilt the text off the road it is naming, which
 * is the one thing this whole file exists to prevent. It stays a point-placement property.
 */
internal fun layOutLineLabels(
    camera: ResolvedMercatorCamera,
    atlas: LabelGlyphAtlas,
    candidate: LabelCandidate,
    candidateIndex: Int,
    sprites: SpriteAtlasManifest? = null,
    viewport: LabelScreenBox = LabelScreenBox(
        left = 0.0,
        top = 0.0,
        right = camera.outputPixelSize.width.toDouble(),
        bottom = camera.outputPixelSize.height.toDouble(),
    ),
): List<PlacedLabel> {
    if (!candidate.hasFinitePlacementInputs()) return emptyList()
    if (!candidate.hasFiniteLinePlacementInputs()) return emptyList()
    if (candidate.line.size < MINIMUM_LINE_POINTS) return emptyList()
    if (candidate.glyphs.isEmpty()) return emptyList()

    // Every glyph is validated once, before any sampling: line placement needs each cell's own
    // extent to decide where along the polyline to sample it from, and a candidate carrying one
    // undrawable glyph drops as a whole rather than as a label with a hole in it.
    val cells = candidate.glyphs.map { atlas.glyphCellOrNull(it) ?: return emptyList() }
    var earliestLocalX = Double.POSITIVE_INFINITY
    var latestLocalX = Double.NEGATIVE_INFINITY
    for (cell in cells) {
        earliestLocalX = min(earliestLocalX, cell.left)
        latestLocalX = max(latestLocalX, cell.right)
    }

    // `text-translate` moves the whole instance on screen after it has been bent, exactly as it
    // moves a point label's anchor: applying it before the walk would slide the label off the line
    // it is naming, and the property is a screen displacement rather than a change of anchor.
    val translatesWithMap = candidate.translateAlignment != SymbolAlignment.VIEWPORT
    val translateX = if (translatesWithMap) {
        rotatedX(candidate.translateX, candidate.translateY, camera.right.x, camera.right.y)
    } else {
        candidate.translateX
    }
    val translateY = if (translatesWithMap) {
        rotatedY(candidate.translateX, candidate.translateY, camera.right.x, camera.right.y)
    } else {
        candidate.translateY
    }

    val runs = projectLineRuns(camera, candidate.line)
    if (runs.isEmpty()) return emptyList()

    val instance = LineInstanceInputs(
        candidate = candidate,
        candidateIndex = candidateIndex,
        cells = cells,
        earliestLocalX = earliestLocalX,
        latestLocalX = latestLocalX,
        translateX = translateX,
        translateY = translateY,
        sprites = sprites,
        viewport = viewport,
    )

    if (candidate.placement == LabelPlacement.LINE_CENTER) {
        // One instance, so exactly one run has to be chosen, and the longest is the only choice
        // that does not depend on which end of the feature the engine happened to emit first. The
        // centre of the *whole* line is not available: a line broken by the near plane has a gap
        // whose length is not a screen distance, so a midpoint measured across it names no pixel.
        val longest = runs.maxByOrNull { it.length } ?: return emptyList()
        return listOfNotNull(instance.layOut(longest, longest.length / 2.0))
    }

    val spacing = candidate.symbolSpacing
    // Below one pixel every repeat of the label lands on the pixel its neighbour did, so the floor
    // costs nothing that could be seen. It is also what keeps [MAXIMUM_ANCHORS_PER_RUN] a bound on
    // a pathological *run* rather than one a spacing of `1e-9` would reach on any line at all.
    if (!(spacing >= MINIMUM_LINE_SPACING_PIXELS) || !spacing.isFinite()) return emptyList()

    val placed = ArrayList<PlacedLabel>()
    for (run in runs) {
        // Anchors sit at half a spacing from the run's start and every spacing after it, so the
        // repeats are centred within the run and the set is unchanged when the run is reversed --
        // which matters because `keepUpright` reverses the *reading* direction and must not
        // therefore move the labels.
        var distance = spacing / 2.0
        var anchors = 0
        while (distance <= run.length && anchors < MAXIMUM_ANCHORS_PER_RUN) {
            instance.layOut(run, distance)?.let { placed += it }
            distance += spacing
            anchors += 1
        }
    }
    return placed
}

/**
 * The per-candidate half of one line instance, so that the per-anchor half is two arguments rather
 * than nine. Nothing here varies between the repeats of one candidate.
 */
private class LineInstanceInputs(
    val candidate: LabelCandidate,
    val candidateIndex: Int,
    val cells: List<GlyphCell>,
    val earliestLocalX: Double,
    val latestLocalX: Double,
    val translateX: Double,
    val translateY: Double,
    val sprites: SpriteAtlasManifest?,
    val viewport: LabelScreenBox,
)

/**
 * One repeat of the label, anchored at [anchorDistance] along [run], or `null` when it does not fit,
 * bends too far, or produces a corner that is not a number.
 *
 * The label's own coordinates become arc length along the line: a glyph whose cell sits at
 * label-local x `c` is sampled at `anchorDistance + direction * c`, and drawn in the frame the
 * polyline has *there*. That is the whole of "distribute the glyphs along the curve" -- there is no
 * separate bending step, because a glyph placed at its own arc length with its own tangent is
 * already bent.
 */
private fun LineInstanceInputs.layOut(run: ProjectedRun, anchorDistance: Double): PlacedLabel? {
    val anchor = run.sampleAt(anchorDistance)
    val direction = uprightDirection(candidate.keepUpright, anchor.tangentX)

    // The label's extent as arc length, which is mirrored about the anchor when the line is read
    // backwards. Refusing an instance that runs off either end is what keeps a road name off the
    // end of its road: clamping instead would pile the overhanging glyphs onto the last vertex.
    val firstDistance = anchorDistance + direction * earliestLocalX
    val lastDistance = anchorDistance + direction * latestLocalX
    if (min(firstDistance, lastDistance) < 0.0) return null
    if (max(firstDistance, lastDistance) > run.length) return null

    val quads = ArrayList<ResolvedGlyphQuad>(cells.size)
    val sampleDistances = DoubleArray(cells.size)
    val tangentsX = DoubleArray(cells.size)
    val tangentsY = DoubleArray(cells.size)
    var paint: ResolvedLabelPaint? = null
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY

    for (index in cells.indices) {
        val cell = cells[index]
        val centre = cell.centreLocalX
        val distance = anchorDistance + direction * centre
        val sample = run.sampleAt(distance)
        sampleDistances[index] = distance
        tangentsX[index] = sample.tangentX * direction
        tangentsY[index] = sample.tangentY * direction

        val existing = paint
        paint = if (existing != null && existing.scale == cell.scale) {
            existing
        } else {
            candidate.resolvePaint(cell.scale)
        }

        val quad = cell.resolveQuad(
            frame = GlyphFrame(
                originX = sample.x + translateX,
                originY = sample.y + translateY,
                axisX = tangentsX[index],
                axisY = tangentsY[index],
                pivotLocalX = centre,
            ),
            paint = paint,
        ) ?: return null
        quads += quad

        for (corner in 0 until QUAD_CORNERS) {
            val cornerX = quad.cornersXy[corner * 2].toDouble()
            val cornerY = quad.cornersXy[corner * 2 + 1].toDouble()
            left = min(left, cornerX)
            right = max(right, cornerX)
            top = min(top, cornerY)
            bottom = max(bottom, cornerY)
        }
    }

    if (exceedsBendCeiling(sampleDistances, tangentsX, tangentsY, candidate.maxAngleDegrees)) return null

    // **`padding` is applied here, and this is the one place it is not a double count.** The engine's
    // own `boundingBox` already carries `text-padding` on all four sides, which is why point
    // placement uses that box as given and never adds it again -- but that box describes a
    // *horizontal row*, and a row bent along a road occupies a different rectangle entirely. So the
    // box below is rebuilt from the bent cells, by the engine's own rule (the union of every quad's
    // cell extent), and `padding` is the same evaluated value the engine would have expanded it by.
    val collisionBox = LabelScreenBox(
        left = left - candidate.padding,
        top = top - candidate.padding,
        right = right + candidate.padding,
        bottom = bottom + candidate.padding,
    )
    if (!collisionBox.isFinite()) return null

    return PlacedLabel(
        candidateIndex = candidateIndex,
        anchorPixelX = anchor.x + translateX,
        anchorPixelY = anchor.y + translateY,
        collisionBox = collisionBox,
        quads = quads,
        // **Every repeat carries its own icon, at its own anchor and in its own frame.** A road
        // name repeated four times along a road is four symbols, and a shield drawn once at the
        // candidate's midpoint would belong to none of them.
        //
        // The anchor is `sample`, without `text-translate`: that displacement is the text's, and
        // `icon-translate` is applied inside [resolveIcon] against the frame `icon-translate-anchor`
        // names. The frame is the polyline's **raw** tangent rather than the direction the glyphs
        // were read in -- `text-keep-upright` reverses the reading order of the letters and must not
        // turn the shield they sit on, which has an `icon-keep-upright` of its own.
        icon = resolveIcon(
            sprites = sprites,
            candidate = candidate,
            anchorPixelX = anchor.x,
            anchorPixelY = anchor.y,
            frameCosine = anchor.tangentX,
            frameSine = anchor.tangentY,
            viewport = viewport,
        ),
    )
}

/**
 * `-1` when the label should be read backwards along the line, `+1` otherwise.
 *
 * `text-keep-upright` asks that text never render upside-down. A glyph drawn in the polyline's own
 * frame reads upside-down exactly when that frame's along-label axis points leftward on screen, so
 * the test is the sign of the tangent's x and the repair is to walk the line the other way: the
 * axis becomes its own negation, which points rightward, and the glyphs -- whose label-local x is
 * also negated into arc length -- come out in the same left-to-right order they were shaped in.
 *
 * A perfectly vertical tangent is left alone. It is upside-down in neither direction, and flipping
 * it would make the placement depend on which side of zero a rounding error fell.
 *
 * With `keepUpright` off the label is drawn as the line runs, upside-down included, which is what
 * the property asks for: the style author who set it to `false` wants the symbol to follow the
 * geometry rather than the reader.
 */
private fun uprightDirection(keepUpright: Boolean, anchorTangentX: Double): Double =
    if (keepUpright && anchorTangentX < 0.0) -1.0 else 1.0

/**
 * Whether any two glyphs adjacent **along the line** turn by more than [maxAngleDegrees].
 *
 * Adjacency is by arc length rather than by glyph order, and the difference matters for a label the
 * engine wrapped onto more than one row: the second row restarts at the label's left edge, so in
 * glyph order the pair straddling the row break jumps backwards along the road and reports a turn
 * that no reader would see. Sorting by the distance each glyph was actually sampled at makes the
 * check describe the shape of the ink instead of the order of the array.
 *
 * The turn is [atan2] of the cross and dot products rather than a difference of two angles, which
 * keeps it in `(-180, 180]` with no wrap-around arithmetic. It is also invariant under
 * [uprightDirection]: negating both tangents leaves both products unchanged, so flipping a label to
 * read upright can never change whether it bends too far.
 */
private fun exceedsBendCeiling(
    sampleDistances: DoubleArray,
    tangentsX: DoubleArray,
    tangentsY: DoubleArray,
    maxAngleDegrees: Double,
): Boolean {
    if (sampleDistances.size < 2) return false
    val order = sampleDistances.indices.sortedBy { sampleDistances[it] }
    for (position in 1 until order.size) {
        val previous = order[position - 1]
        val current = order[position]
        val cross = tangentsX[previous] * tangentsY[current] - tangentsY[previous] * tangentsX[current]
        val dot = tangentsX[previous] * tangentsX[current] + tangentsY[previous] * tangentsY[current]
        if (abs(atan2(cross, dot)) * DEGREES_PER_RADIAN > maxAngleDegrees) return true
    }
    return false
}

/**
 * One maximal stretch of the source line that is entirely on screen, in output pixels, with the
 * cumulative arc length of every point.
 *
 * Arc length is the parameter everything else is expressed in -- `symbolSpacing` is a pixel
 * distance, and so is the label's own width -- so it is computed once here rather than rediscovered
 * per glyph.
 */
private class ProjectedRun(
    private val pointsX: DoubleArray,
    private val pointsY: DoubleArray,
    private val cumulative: DoubleArray,
) {
    val length: Double get() = cumulative[cumulative.size - 1]

    /**
     * The point and unit tangent at [distance] along this run, which must lie in `[0, length]`.
     *
     * A distance falling exactly on a vertex takes the tangent of the segment that *starts* there,
     * which is arbitrary but has to be decided: the two segments meeting at a vertex have different
     * tangents by definition, and the alternative is a tangent that depends on the direction the
     * sampler happened to approach from.
     */
    fun sampleAt(distance: Double): LineSample {
        val segment = segmentAt(distance)
        val segmentLength = cumulative[segment + 1] - cumulative[segment]
        val tangentX = (pointsX[segment + 1] - pointsX[segment]) / segmentLength
        val tangentY = (pointsY[segment + 1] - pointsY[segment]) / segmentLength
        val along = distance - cumulative[segment]
        return LineSample(
            x = pointsX[segment] + tangentX * along,
            y = pointsY[segment] + tangentY * along,
            tangentX = tangentX,
            tangentY = tangentY,
        )
    }

    /** The last segment whose start is at or before [distance], clamped to the run's own range. */
    private fun segmentAt(distance: Double): Int {
        var low = 0
        var high = pointsX.size - 2
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (cumulative[middle] <= distance) low = middle else high = middle - 1
        }
        return low
    }
}

private class LineSample(
    val x: Double,
    val y: Double,
    val tangentX: Double,
    val tangentY: Double,
)

/**
 * The source line as the runs of it that have pixels.
 *
 * **A line is not dropped because part of it is off screen, and it is not stitched across the gap
 * either.** With any pitch at all a road crossing the horizon has vertices in front of the camera
 * and vertices behind it, and the screen positions of the two groups are not on one straight
 * segment between them -- a point behind the near plane has no pixel to interpolate towards, which
 * is the whole reason [ScreenProjection] is a sealed type. So the run ends at the gap and a new one
 * begins after it, and each is walked as the separate on-screen line it is.
 *
 * Points closer together than [MINIMUM_SEGMENT_PIXELS] are folded into their predecessor. A tile's
 * geometry is quantised to an integer grid and several source vertices can project onto one pixel;
 * a zero-length segment has no tangent, and one kept in the array would be a division by zero in
 * [ProjectedRun.sampleAt] rather than a visible defect.
 */
private fun projectLineRuns(
    camera: ResolvedMercatorCamera,
    line: List<LabelLinePoint>,
): List<ProjectedRun> {
    val runs = ArrayList<ProjectedRun>()
    var pointsX = ArrayList<Double>()
    var pointsY = ArrayList<Double>()

    fun finishRun() {
        if (pointsX.size >= MINIMUM_LINE_POINTS) {
            val cumulative = DoubleArray(pointsX.size)
            for (index in 1 until pointsX.size) {
                val stepX = pointsX[index] - pointsX[index - 1]
                val stepY = pointsY[index] - pointsY[index - 1]
                cumulative[index] = cumulative[index - 1] + sqrt(stepX * stepX + stepY * stepY)
            }
            runs += ProjectedRun(pointsX.toDoubleArray(), pointsY.toDoubleArray(), cumulative)
        }
        pointsX = ArrayList()
        pointsY = ArrayList()
    }

    for (point in line) {
        val projected = projectGeographicPosition(
            camera,
            GeographicPosition(
                latitude = point.latitude,
                unwrappedLongitude = point.longitude,
                altitudeMetres = 0.0,
            ),
        )
        if (projected !is ScreenProjection.Projected) {
            finishRun()
            continue
        }
        if (pointsX.isNotEmpty()) {
            val stepX = projected.pixelX - pointsX[pointsX.size - 1]
            val stepY = projected.pixelY - pointsY[pointsY.size - 1]
            if (sqrt(stepX * stepX + stepY * stepY) < MINIMUM_SEGMENT_PIXELS) continue
        }
        pointsX += projected.pixelX
        pointsY += projected.pixelY
    }
    finishRun()

    return runs
}

/**
 * The scalars line placement reads that point placement does not, checked in one place before any of
 * them reaches an arithmetic that would swallow them.
 *
 * A non-finite `maxAngleDegrees` is the subtle one: `turn > NaN` is false for every turn, so a NaN
 * ceiling would not refuse a bend, it would accept **every** bend, which is the opposite of what a
 * missing value should mean. `padding` reaches a collision box and `symbolSpacing` reaches the walk
 * itself, which is checked again where the loop needs a positive lower bound rather than only a
 * finite one.
 */
private fun LabelCandidate.hasFiniteLinePlacementInputs(): Boolean =
    maxAngleDegrees.isFinite() &&
        padding.isFinite() && padding >= 0.0 &&
        symbolSpacing.isFinite()

/** Two points, below which there is no segment and therefore no line. */
private const val MINIMUM_LINE_POINTS: Int = 2

/** See [projectLineRuns]. Far below anything a reader could see, and above zero. */
private const val MINIMUM_SEGMENT_PIXELS: Double = 1.0e-6

/** The `symbolSpacing` floor. See the walk in [layOutLineLabels]. */
private const val MINIMUM_LINE_SPACING_PIXELS: Double = 1.0

/**
 * The bound on one run's walk, because **a run's length is a projected pixel distance and that is
 * not bounded by anything the caller controls.**
 *
 * A run is measured on screen, not on the ground, and the perspective divide is by a `w` the near
 * plane only holds at or above one logical pixel. A ground vertex just in front of that plane -- a
 * road passing beneath a pitched camera, which is an ordinary tile rather than a pathological one --
 * projects hundreds of thousands of pixels away: measured at **390,000** on this cycle's own fixture
 * camera, for a vertex whose `w` is 1.05. Multiply that by the [MINIMUM_LINE_SPACING_PIXELS] floor
 * and the walk below is a loop with no ceiling in a function that runs inside `prepare()`.
 *
 * Set far above what a screen can show: a 4K viewport's diagonal is about 4,400 pixels, so a run
 * lying entirely within one cannot ask for more anchors than this even at the one-pixel floor, and a
 * real style's `symbol-spacing` is two orders of magnitude above that floor. When it does bite, the
 * anchors lost are at the far end of a run that is overwhelmingly off screen, and the alternative it
 * is chosen over is a frame that never finishes preparing.
 */
internal const val MAXIMUM_ANCHORS_PER_RUN: Int = 4096

private const val QUAD_CORNERS: Int = 4

private const val DEGREES_PER_RADIAN: Double = 180.0 / PI
