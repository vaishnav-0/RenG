package com.rohittp.reng.internal.projection

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveCameraRelativeMapPosition

/**
 * The forward direction of [physicalPixelGroundRay]: where a point in the world lands on the
 * screen, rather than what a pixel is looking at.
 *
 * Nothing in `commonMain` computed this before Cycle E-labels. The perspective divide and the
 * viewport transform lived only inside the GPU, because every drawn thing until labels handed the
 * GPU a model-view-projection matrix and let the fixed-function pipeline finish the job. A label
 * cannot: collision resolves on the CPU during `prepare()`, so the pixel has to exist there.
 *
 * "Not visible" is deliberately not representable as a pixel. A point behind the camera divides by
 * a negative `w` and lands on a perfectly plausible-looking pixel on the *wrong side* of the
 * screen, where it would then collide with labels it should never have met.
 */
internal sealed interface ScreenProjection {
    /**
     * The point is in front of the near plane and therefore has a pixel.
     *
     * [pixelX] and [pixelY] are `CONTEXT.md`'s continuous output-pixel screen space: bounds
     * `[0, width]` by `[0, height]`, pixel centres at half-integer coordinates, origin top-left,
     * positive x rightward and positive y **downward**. World space is y-up and screen space is
     * y-down, so [pixelY] is not [pixelX]'s mirror image and the two are not interchangeable.
     *
     * Being projected does **not** mean being inside the viewport — [pixelX] and [pixelY] may lie
     * outside those bounds, and for a label that is a decision for the collision pass rather than
     * for this function, since a label box can straddle an edge.
     *
     * [w] is the clip-space `w` the GPU would divide by, which for RenG's projection matrix (bottom
     * row `(0, 0, -1, 0)`) is exactly `-z_view`: the distance in front of the camera plane, in
     * logical pixels. It is always at least [NEAR_DISTANCE_LOGICAL_PIXELS] here. It is the same
     * quantity [GroundRayResult.Hit.t] reports for the inverse direction.
     */
    data class Projected(
        val pixelX: Double,
        val pixelY: Double,
        val w: Double,
    ) : ScreenProjection

    /**
     * The point is at or behind the near plane, so no pixel exists for it.
     *
     * [w] is the measured value rather than a flag: below [NEAR_DISTANCE_LOGICAL_PIXELS], possibly
     * zero or negative (strictly behind the camera plane), and non-finite when the position was or
     * when the clip-space row overflowed. Callers that want to distinguish "just too close" from
     * "behind me" read [w]; callers that only want to drop the label ignore it.
     */
    data class BehindNearPlane(val w: Double) : ScreenProjection

    /**
     * The position has no pixel at all, for one of three reasons: it is outside RenG's Mercator
     * planning support, or its camera-relative logical position is not GPU-representable — the two
     * guards [com.rohittp.reng.internal.planning.resolvePlacement] already applies to a consumer's
     * map position — or it is in front of the near plane but so far off-axis that the pixel itself
     * overflows `Double`.
     *
     * That third reason is the reason this case carries no `w`: the depth divisor can be perfectly
     * finite while the pixel is not, so there is no single number that describes the rejection.
     */
    data object OutsideSupportedDomain : ScreenProjection
}

/**
 * Projects `(latitude, longitude, altitude)` to a screen pixel and a depth divisor, in `Double`, on
 * the CPU.
 *
 * Reuses the pieces that already exist rather than re-deriving the camera:
 * [validateMercatorMapPosition] applies the Mercator domain, [resolveCameraRelativeMapPosition]
 * turns the absolute Mercator position into camera-relative logical pixels, and
 * [projectCameraRelativeLogicalPosition] applies [ResolvedMercatorCamera]'s own view and projection
 * matrices — the same two matrices [com.rohittp.reng.internal.gl.composeGeometryViewProjection]
 * hands the GPU. Nothing about the camera is restated here, so the CPU and the GPU cannot disagree.
 *
 * Both guards collapse to [ScreenProjection.OutsideSupportedDomain] and the
 * [com.rohittp.reng.internal.failure.FailureDescriptor] they built is deliberately discarded: a
 * label anchor is engine-derived rather than consumer-supplied, so one unprojectable anchor must
 * drop that label rather than fail the frame, and there is no consumer field to name in a
 * diagnostic. The descriptors carry only static field names, never adapter data, so nothing is
 * lost. [DiagnosticField.MAP_POSITION_LATITUDE] and its two siblings are passed only because
 * [resolveCameraRelativeMapPosition] requires them to build a descriptor this function throws away.
 */
internal fun projectGeographicPosition(
    camera: ResolvedMercatorCamera,
    position: GeographicPosition,
): ScreenProjection {
    val projected = validateMercatorMapPosition(position)
    if (projected !is SpatialOutcome.Success) return ScreenProjection.OutsideSupportedDomain

    val logical = resolveCameraRelativeMapPosition(
        projected = projected.value,
        camera = camera,
        latitudeField = DiagnosticField.MAP_POSITION_LATITUDE,
        longitudeField = DiagnosticField.MAP_POSITION_UNWRAPPED_LONGITUDE,
        altitudeField = DiagnosticField.MAP_POSITION_ALTITUDE,
    )
    if (logical !is SpatialOutcome.Success) return ScreenProjection.OutsideSupportedDomain

    return projectCameraRelativeLogicalPosition(camera, logical.value)
}

/**
 * The half of [projectGeographicPosition] that starts from an already camera-relative logical
 * position — the shape [resolveCameraRelativeMapPosition] and
 * [com.rohittp.reng.internal.planning.ResolvedPlacement.logicalPosition] both produce, so a caller
 * holding one does not pay the Mercator conversion twice.
 *
 * Applies `projectionMatrix * viewMatrix` in `Double`, then the perspective divide, then the
 * viewport transform. The viewport transform is the exact inverse of the one
 * [com.rohittp.reng.internal.gl.composeScreenModelViewProjection]'s orthographic matrix applies in
 * the other direction (`ndcX = 2 * pixelX / width - 1`, `ndcY = 1 - 2 * pixelY / height`), which is
 * what puts pixel centres at half-integers and makes the round trip through
 * [physicalPixelGroundRay] — which converts a pixel *index* by adding that same half — land back on
 * `index + 0.5`.
 *
 * The near-plane test is written as `!(w >= near)` rather than `w < near` so that a non-finite `w`
 * is rejected instead of dividing into NaN pixels. A NaN pixel is worse than a missing one: it
 * compares false against every bound, so it would silently survive a viewport cull and then poison
 * collision. Coordinates large enough to overflow the clip-space row while leaving `w` finite are
 * caught by the second guard, on the pixels themselves; between them no non-finite number can leave
 * this function as a [ScreenProjection.Projected].
 */
internal fun projectCameraRelativeLogicalPosition(
    camera: ResolvedMercatorCamera,
    logicalPosition: DoubleVector3,
): ScreenProjection {
    val viewProjection = camera.projectionMatrix * camera.viewMatrix
    val w = viewProjection.homogeneousRow(HOMOGENEOUS_W_ROW, logicalPosition)
    if (!(w >= NEAR_DISTANCE_LOGICAL_PIXELS)) return ScreenProjection.BehindNearPlane(w)

    val normalisedX = viewProjection.homogeneousRow(0, logicalPosition) / w
    val normalisedY = viewProjection.homogeneousRow(1, logicalPosition) / w
    val pixelX = (normalisedX + 1.0) * 0.5 * camera.outputPixelSize.width.toDouble()
    val pixelY = (1.0 - normalisedY) * 0.5 * camera.outputPixelSize.height.toDouble()
    if (!pixelX.isFinite() || !pixelY.isFinite()) return ScreenProjection.OutsideSupportedDomain

    return ScreenProjection.Projected(pixelX = pixelX, pixelY = pixelY, w = w)
}

/** One row of `matrix * (point, 1)`, without materialising a four-component vector type. */
private fun DoubleMatrix4.homogeneousRow(row: Int, point: DoubleVector3): Double =
    this[row, 0] * point.x + this[row, 1] * point.y + this[row, 2] * point.z + this[row, 3]

private const val HOMOGENEOUS_W_ROW: Int = 3
