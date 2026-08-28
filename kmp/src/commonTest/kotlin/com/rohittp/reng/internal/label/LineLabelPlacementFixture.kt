package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.gl.ResolvedGlyphQuad
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectGeographicPosition
import com.rohittp.rentile.LabelLinePoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The measuring instruments the line cases assert against.
 *
 * **Nothing here re-derives what line placement computes; it measures the result geometrically.** A
 * case does not restate "the glyph should be at anchor plus its offset along the polyline" -- that
 * would test the restatement. It asks two questions of each glyph quad that a wrong answer cannot
 * satisfy: *is its origin on the line at all*, and *how far along the line is it*. Both are answered
 * by [ScreenPolyline.nearestOn], which projects a point onto every segment and keeps the closest --
 * an operation with no notion of anchors, glyph rows, spacing or reading direction.
 */

/** A polyline in output-pixel screen space, built by the case rather than by the code under test. */
internal class ScreenPolyline(private val x: DoubleArray, private val y: DoubleArray) {
    /** The arc length at each point, so that a case can name a vertex by distance. */
    val cumulative: DoubleArray = DoubleArray(x.size).also { lengths ->
        for (index in 1 until x.size) {
            lengths[index] = lengths[index - 1] + distance(x[index - 1], y[index - 1], x[index], y[index])
        }
    }

    val length: Double get() = cumulative[cumulative.size - 1]

    val firstX: Double get() = x[0]
    val lastX: Double get() = x[x.size - 1]

    /** The point at [distance] along the polyline, found by walking rather than by searching. */
    fun pointAt(distance: Double): DoubleArray {
        for (segment in 0 until x.size - 1) {
            val start = cumulative[segment]
            val end = cumulative[segment + 1]
            if (distance <= end || segment == x.size - 2) {
                val fraction = (distance - start) / (end - start)
                return doubleArrayOf(
                    x[segment] + (x[segment + 1] - x[segment]) * fraction,
                    y[segment] + (y[segment + 1] - y[segment]) * fraction,
                )
            }
        }
        error("empty polyline")
    }

    /**
     * How far `(pointX, pointY)` is from this polyline, and how far along it the closest point sits.
     *
     * The closest point on each segment is the perpendicular foot clamped to the segment's own ends,
     * which is the standard point-to-segment answer and owes nothing to how the glyph got there.
     */
    fun nearestOn(pointX: Double, pointY: Double): NearestPoint {
        var best = NearestPoint(offLine = Double.POSITIVE_INFINITY, alongLine = 0.0)
        for (segment in 0 until x.size - 1) {
            val stepX = x[segment + 1] - x[segment]
            val stepY = y[segment + 1] - y[segment]
            val segmentLength = distance(x[segment], y[segment], x[segment + 1], y[segment + 1])
            val raw = ((pointX - x[segment]) * stepX + (pointY - y[segment]) * stepY) /
                (segmentLength * segmentLength)
            val fraction = min(1.0, max(0.0, raw))
            val footX = x[segment] + stepX * fraction
            val footY = y[segment] + stepY * fraction
            val offLine = distance(pointX, pointY, footX, footY)
            if (offLine < best.offLine) {
                best = NearestPoint(
                    offLine = offLine,
                    alongLine = cumulative[segment] + segmentLength * fraction,
                )
            }
        }
        return best
    }
}

internal class NearestPoint(val offLine: Double, val alongLine: Double)

/**
 * The projectable runs of a source line, as the cases see them.
 *
 * Written as a fold over the points rather than as the production walk, and deliberately without the
 * production pass's degenerate-segment fold, so that a case whose fixture accidentally repeated a
 * pixel would show up as a disagreement rather than be hidden by both sides making it.
 */
internal fun screenRunsOf(
    camera: ResolvedMercatorCamera,
    line: List<LabelLinePoint>,
): List<ScreenPolyline> {
    val projected = line.map { point ->
        projectGeographicPosition(
            camera,
            GeographicPosition(
                latitude = point.latitude,
                unwrappedLongitude = point.longitude,
                altitudeMetres = 0.0,
            ),
        )
    }
    val runs = ArrayList<ScreenPolyline>()
    var start = 0
    while (start < projected.size) {
        if (projected[start] !is ScreenProjection.Projected) {
            start += 1
            continue
        }
        var end = start
        while (end + 1 < projected.size && projected[end + 1] is ScreenProjection.Projected) end += 1
        if (end > start) {
            val slice = projected.subList(start, end + 1).map { it as ScreenProjection.Projected }
            runs += ScreenPolyline(
                slice.map { it.pixelX }.toDoubleArray(),
                slice.map { it.pixelY }.toDoubleArray(),
            )
        }
        start = end + 1
    }
    return runs
}

/** The one run of a line every point of which projects. */
internal fun screenRunOf(camera: ResolvedMercatorCamera, line: List<LabelLinePoint>): ScreenPolyline =
    screenRunsOf(camera, line).single()

/**
 * The frame one glyph was drawn in, recovered from its four screen corners alone.
 *
 * A quad is `origin + (x - centre) * axis + y * normal` over its cell's four label-local corners, so
 * the top edge divided by the cell's width is the axis, and the quad's own centre backed off by the
 * cell's mid-height along the normal is the origin. Recovering them this way rather than reading
 * them out of the implementation is what lets a case ask whether the origin is *on the line*.
 */
internal fun recoverGlyphFrame(quad: ResolvedGlyphQuad, cellWidth: Double, midLocalY: Double): RecoveredFrame {
    val corners = quad.cornersXy
    val axisX = (corners[2] - corners[0]).toDouble() / cellWidth
    val axisY = (corners[3] - corners[1]).toDouble() / cellWidth
    val centreX = (corners[0].toDouble() + corners[4].toDouble()) / 2.0
    val centreY = (corners[1].toDouble() + corners[5].toDouble()) / 2.0
    return RecoveredFrame(
        originX = centreX - midLocalY * -axisY,
        originY = centreY - midLocalY * axisX,
        axisX = axisX,
        axisY = axisY,
    )
}

internal class RecoveredFrame(
    val originX: Double,
    val originY: Double,
    val axisX: Double,
    val axisY: Double,
)

/** The mid-height of the shared glyph cell in label-local units, for [recoverGlyphFrame]. */
internal const val LINE_GLYPH_MID_LOCAL_Y: Double = -13.25 + (20.0 * 0.75) / 2.0

private fun distance(fromX: Double, fromY: Double, toX: Double, toY: Double): Double {
    val stepX = toX - fromX
    val stepY = toY - fromY
    return sqrt(stepX * stepX + stepY * stepY)
}
