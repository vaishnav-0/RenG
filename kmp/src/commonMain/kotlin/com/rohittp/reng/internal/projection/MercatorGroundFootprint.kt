package com.rohittp.reng.internal.projection

import kotlin.math.max
import kotlin.math.min

internal sealed interface ClosedMercatorFootprint : FrameGroundFootprint {
    data object Empty : ClosedMercatorFootprint

    data class Point(val point: MercatorGroundPoint) : ClosedMercatorFootprint

    data class Segment(
        val start: MercatorGroundPoint,
        val end: MercatorGroundPoint,
    ) : ClosedMercatorFootprint

    class Polygon(vertices: List<MercatorGroundPoint>) : ClosedMercatorFootprint {
        private val vertexSnapshot: List<MercatorGroundPoint> = vertices.toList()

        val vertices: List<MercatorGroundPoint> get() = vertexSnapshot.toMutableList()

        override fun equals(other: Any?): Boolean =
            other is Polygon && vertexSnapshot == other.vertexSnapshot

        override fun hashCode(): Int = vertexSnapshot.hashCode()

        override fun toString(): String = "Polygon(vertices=$vertexSnapshot)"
    }
}

internal fun clippedPhysicalPixelFootprint(
    camera: ResolvedMercatorCamera,
): ClosedMercatorFootprint {
    var firstAdmissibleRow = -1
    var lastAdmissibleRow = -1
    for (pixelY in 0 until camera.outputPixelSize.height) {
        if (physicalPixelGroundRay(camera, pixelX = 0, pixelY = pixelY) is GroundRayResult.Hit) {
            if (firstAdmissibleRow < 0) firstAdmissibleRow = pixelY
            lastAdmissibleRow = pixelY
        }
    }
    if (firstAdmissibleRow < 0) return ClosedMercatorFootprint.Empty

    return footprintForAdmissibleRowRange(camera, firstAdmissibleRow, lastAdmissibleRow)
}

/**
 * The same four-corner sampling [clippedPhysicalPixelFootprint] performs, over an explicit range of
 * rows rather than over every admissible one (ADR 0065).
 *
 * A per-tile level of detail decomposes the frame into horizontal bands, and each band needs the
 * ground quad of *its* rows -- so this is factored out rather than duplicated, and
 * [clippedPhysicalPixelFootprint] is now its whole-frame case. The caller owns the promise every
 * row in `[firstRow, lastRow]` carries ground, which is the same promise the whole-frame case has
 * always made: [groundHit] hard-casts, and on a plane a row's classification is a function of the
 * row alone, so column zero deciding for every column is what makes that sound.
 *
 * Two adjacent ranges share exactly one edge, because `lastRow` of one and `firstRow` of the next
 * are adjacent rows and the quads are built from the same ray function. They therefore partition
 * the ground rather than overlapping it -- though the *tiles* each range admits still overlap
 * wherever one straddles the shared edge, which is ADR 0065's accepted cost.
 */
internal fun footprintForAdmissibleRowRange(
    camera: ResolvedMercatorCamera,
    firstRow: Int,
    lastRow: Int,
): ClosedMercatorFootprint {
    require(firstRow in 0..lastRow && lastRow < camera.outputPixelSize.height) {
        "row range must be ordered and within the resolved output size"
    }

    val lastColumn = camera.outputPixelSize.width - 1
    val pixelCentreRectangle = listOf(
        groundHit(camera, pixelX = 0, pixelY = firstRow),
        groundHit(camera, pixelX = lastColumn, pixelY = firstRow),
        groundHit(camera, pixelX = lastColumn, pixelY = lastRow),
        groundHit(camera, pixelX = 0, pixelY = lastRow),
    )
    return when (val footprint = classifyFootprint(pixelCentreRectangle)) {
        ClosedMercatorFootprint.Empty -> ClosedMercatorFootprint.Empty
        is ClosedMercatorFootprint.Point -> clipPoint(footprint.point)
        is ClosedMercatorFootprint.Segment -> clipSegment(footprint.start, footprint.end)
        is ClosedMercatorFootprint.Polygon -> clipPolygon(footprint.vertices)
    }
}

private fun groundHit(
    camera: ResolvedMercatorCamera,
    pixelX: Int,
    pixelY: Int,
): MercatorGroundPoint =
    (physicalPixelGroundRay(camera, pixelX, pixelY) as GroundRayResult.Hit).point

private fun clipPoint(point: MercatorGroundPoint): ClosedMercatorFootprint =
    if (isWithinMercatorPlanningSupport(point.x, point.y)) {
        ClosedMercatorFootprint.Point(point)
    } else {
        ClosedMercatorFootprint.Empty
    }

private fun clipSegment(
    start: MercatorGroundPoint,
    end: MercatorGroundPoint,
): ClosedMercatorFootprint {
    if (!start.isFinite() || !end.isFinite()) return ClosedMercatorFootprint.Empty

    val deltaX = end.x - start.x
    val deltaY = end.y - start.y
    var minimumT = 0.0
    var maximumT = 1.0

    fun retainAxisInterval(origin: Double, delta: Double, lower: Double, upper: Double): Boolean {
        if (delta == 0.0) return origin >= lower && origin <= upper
        val first = (lower - origin) / delta
        val second = (upper - origin) / delta
        minimumT = max(minimumT, min(first, second))
        maximumT = min(maximumT, max(first, second))
        return minimumT <= maximumT
    }

    if (!retainAxisInterval(start.x, deltaX, MINIMUM_SUPPORT_X, MAXIMUM_SUPPORT_X) ||
        !retainAxisInterval(start.y, deltaY, MINIMUM_SUPPORT_Y, MAXIMUM_SUPPORT_Y)
    ) {
        return ClosedMercatorFootprint.Empty
    }

    val clippedStart = pointAlong(start, deltaX, deltaY, minimumT).clampedToSupport()
    val clippedEnd = pointAlong(start, deltaX, deltaY, maximumT).clampedToSupport()
    return classifyFootprint(listOf(clippedStart, clippedEnd))
}

private fun clipPolygon(vertices: List<MercatorGroundPoint>): ClosedMercatorFootprint {
    if (vertices.any { !it.isFinite() }) return ClosedMercatorFootprint.Empty

    var clipped = clipAgainstVerticalPlane(vertices, MINIMUM_SUPPORT_X, keepGreater = true)
    clipped = clipAgainstVerticalPlane(clipped, MAXIMUM_SUPPORT_X, keepGreater = false)
    clipped = clipAgainstHorizontalPlane(clipped, MINIMUM_SUPPORT_Y, keepGreater = true)
    clipped = clipAgainstHorizontalPlane(clipped, MAXIMUM_SUPPORT_Y, keepGreater = false)
    return classifyFootprint(clipped.map(MercatorGroundPoint::clampedToSupport))
}

private fun clipAgainstVerticalPlane(
    vertices: List<MercatorGroundPoint>,
    boundary: Double,
    keepGreater: Boolean,
): List<MercatorGroundPoint> = clipAgainstPlane(
    vertices = vertices,
    isInside = { point -> if (keepGreater) point.x >= boundary else point.x <= boundary },
    intersection = { start, end ->
        val fraction = (boundary - start.x) / (end.x - start.x)
        MercatorGroundPoint(
            x = boundary,
            y = start.y + (end.y - start.y) * fraction,
        )
    },
)

private fun clipAgainstHorizontalPlane(
    vertices: List<MercatorGroundPoint>,
    boundary: Double,
    keepGreater: Boolean,
): List<MercatorGroundPoint> = clipAgainstPlane(
    vertices = vertices,
    isInside = { point -> if (keepGreater) point.y >= boundary else point.y <= boundary },
    intersection = { start, end ->
        val fraction = (boundary - start.y) / (end.y - start.y)
        MercatorGroundPoint(
            x = start.x + (end.x - start.x) * fraction,
            y = boundary,
        )
    },
)

private fun clipAgainstPlane(
    vertices: List<MercatorGroundPoint>,
    isInside: (MercatorGroundPoint) -> Boolean,
    intersection: (MercatorGroundPoint, MercatorGroundPoint) -> MercatorGroundPoint,
): List<MercatorGroundPoint> {
    if (vertices.isEmpty()) return emptyList()

    val result = mutableListOf<MercatorGroundPoint>()
    var previous = vertices.last()
    var previousInside = isInside(previous)
    for (current in vertices) {
        val currentInside = isInside(current)
        if (previousInside != currentInside) result += intersection(previous, current)
        if (currentInside) result += current
        previous = current
        previousInside = currentInside
    }
    return result
}

private fun classifyFootprint(vertices: List<MercatorGroundPoint>): ClosedMercatorFootprint {
    val unique = mutableListOf<MercatorGroundPoint>()
    for (vertex in vertices) {
        if (unique.lastOrNull() != vertex) unique += vertex
    }
    if (unique.size > 1 && unique.first() == unique.last()) unique.removeAt(unique.lastIndex)

    return when (unique.size) {
        0 -> ClosedMercatorFootprint.Empty
        1 -> ClosedMercatorFootprint.Point(unique.single())
        2 -> orderedSegment(unique[0], unique[1])
        else -> ClosedMercatorFootprint.Polygon(rotateToDeterministicStart(unique))
    }
}

private fun orderedSegment(
    first: MercatorGroundPoint,
    second: MercatorGroundPoint,
): ClosedMercatorFootprint =
    if (pointComesBefore(first, second)) {
        ClosedMercatorFootprint.Segment(first, second)
    } else {
        ClosedMercatorFootprint.Segment(second, first)
    }

private fun rotateToDeterministicStart(vertices: List<MercatorGroundPoint>): List<MercatorGroundPoint> {
    var firstIndex = 0
    for (index in 1 until vertices.size) {
        if (pointComesBefore(vertices[index], vertices[firstIndex])) firstIndex = index
    }
    return List(vertices.size) { offset -> vertices[(firstIndex + offset) % vertices.size] }
}

private fun pointComesBefore(first: MercatorGroundPoint, second: MercatorGroundPoint): Boolean =
    first.y < second.y || (first.y == second.y && first.x <= second.x)

private fun pointAlong(
    start: MercatorGroundPoint,
    deltaX: Double,
    deltaY: Double,
    fraction: Double,
): MercatorGroundPoint = MercatorGroundPoint(
    x = start.x + deltaX * fraction,
    y = start.y + deltaY * fraction,
)

private fun MercatorGroundPoint.isFinite(): Boolean = x.isFinite() && y.isFinite()

private fun MercatorGroundPoint.clampedToSupport(): MercatorGroundPoint = MercatorGroundPoint(
    x = x.coerceIn(MINIMUM_SUPPORT_X, MAXIMUM_SUPPORT_X),
    y = y.coerceIn(MINIMUM_SUPPORT_Y, MAXIMUM_SUPPORT_Y),
)

private const val MINIMUM_SUPPORT_X: Double = -16384.0
private const val MAXIMUM_SUPPORT_X: Double = 16385.0
private const val MINIMUM_SUPPORT_Y: Double = 0.0
private const val MAXIMUM_SUPPORT_Y: Double = 1.0
