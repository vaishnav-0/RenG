package com.rohittp.reng.internal.planning

import com.rohittp.reng.Geometry
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.globeCameraRelativePosition
import com.rohittp.reng.internal.projection.validateMercatorGeometryPosition

internal class ResolvedGeometry(
    cornersClockwiseFromTopLeft: List<DoubleVector3>,
    val shaderPair: ShaderPair,
) {
    private val cornerSnapshot: ArrayList<DoubleVector3> = ArrayList(cornersClockwiseFromTopLeft)

    val cornersClockwiseFromTopLeft: List<DoubleVector3>
        get() = ArrayList(cornerSnapshot)

    init {
        require(cornerSnapshot.size == CORNER_COUNT) { "resolved geometry requires exactly four corners" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResolvedGeometry &&
            cornerSnapshot == other.cornerSnapshot &&
            shaderPair == other.shaderPair

    override fun hashCode(): Int = 31 * cornerSnapshot.hashCode() + shaderPair.hashCode()

    private companion object {
        const val CORNER_COUNT: Int = 4
    }
}

internal fun resolveGeometry(
    geometry: Geometry,
    camera: ResolvedMercatorCamera,
): SpatialOutcome<ResolvedGeometry> {
    val geographicCorners = listOf(
        geometry.topLeft,
        Vector3(geometry.topLeft.x, geometry.bottomRight.y, geometry.topLeft.z),
        geometry.bottomRight,
        Vector3(geometry.bottomRight.x, geometry.topLeft.y, geometry.bottomRight.z),
    )
    val resolvedCorners = ArrayList<DoubleVector3>(geographicCorners.size)

    for (corner in geographicCorners) {
        val projectedOutcome = validateMercatorGeometryPosition(
            GeographicPosition(
                latitude = corner.x,
                unwrappedLongitude = corner.y,
                altitudeMetres = corner.z,
            ),
        )
        if (projectedOutcome is SpatialOutcome.Failure) return projectedOutcome
        val projected = (projectedOutcome as SpatialOutcome.Success).value
        val logicalOutcome = resolveCameraRelativeMapPosition(
            projected = projected,
            camera = camera,
            latitudeField = DiagnosticField.GEOMETRY_LATITUDE,
            longitudeField = DiagnosticField.GEOMETRY_UNWRAPPED_LONGITUDE,
            altitudeField = DiagnosticField.GEOMETRY_ALTITUDE,
        )
        if (logicalOutcome is SpatialOutcome.Failure) return logicalOutcome
        resolvedCorners += (logicalOutcome as SpatialOutcome.Success).value
    }

    return SpatialOutcome.Success(
        ResolvedGeometry(
            cornersClockwiseFromTopLeft = resolvedCorners,
            shaderPair = geometry.shaderPair,
        ),
    )
}

/**
 * [resolveGeometry]'s globe arm: the same four corners, put on the sphere instead of on the plane.
 *
 * **The corners are validated in [resolveGeometry]'s own order and with its own three diagnostic
 * fields**, so a geometry the globe refuses is refused with the identical code, stage and field the
 * plane refuses it with. `internal.gl.geometryGrid`'s globe arm validates the same four corners the
 * same way at draw time; the two agreeing is what keeps a geometry that passed planning from
 * failing inside a draw call.
 *
 * **This is not what gets drawn**, and that is the point of it existing separately. A globe frame's
 * geometry is a subdivided, CPU-projected grid (`internal.gl.geometryGrid`), because a Mercator-to-
 * sphere map is nonlinear and cannot be a matrix; the four corners here are what the *plan* carries,
 * which is a validation result and the shader pair the frame's programs are keyed by. The Mercator
 * arm is in exactly the same position — [resolveGeometry]'s corners stopped being drawn when Cycle G
 * task 9 subdivided both modes — and the two arms staying symmetric is what lets one spatial plan
 * serve both.
 *
 * The GPU-representability guard is [ResolvedGeometry]'s rather than the sphere's: a camera-relative
 * position on a sphere is bounded by `2 * radius + cameraDistance`, but a geometry carries an
 * altitude and [validateMercatorGeometryPosition] bounds altitude only by finiteness, so an altitude
 * of `1e300` metres is a finite `Double` and an infinite `Float`. That is `resolveGlobePlacement`'s
 * own reasoning applied to the one other consumer-supplied altitude in the API.
 */
internal fun resolveGlobeGeometry(
    geometry: Geometry,
    camera: ResolvedGlobeCamera,
): SpatialOutcome<ResolvedGeometry> {
    val geographicCorners = listOf(
        geometry.topLeft,
        Vector3(geometry.topLeft.x, geometry.bottomRight.y, geometry.topLeft.z),
        geometry.bottomRight,
        Vector3(geometry.bottomRight.x, geometry.topLeft.y, geometry.bottomRight.z),
    )
    val resolvedCorners = ArrayList<DoubleVector3>(geographicCorners.size)

    for (corner in geographicCorners) {
        val projectedOutcome = validateMercatorGeometryPosition(
            GeographicPosition(
                latitude = corner.x,
                unwrappedLongitude = corner.y,
                altitudeMetres = corner.z,
            ),
        )
        if (projectedOutcome is SpatialOutcome.Failure) return projectedOutcome
        val projected = (projectedOutcome as SpatialOutcome.Success).value
        val logical = globeCameraRelativePosition(
            camera = camera,
            mercatorX = projected.x,
            mercatorY = projected.y,
            altitudeMetres = corner.z,
        )
        if (!isGpuRepresentable(logical.x)) {
            return gpuRepresentabilityFailure(DiagnosticField.GEOMETRY_UNWRAPPED_LONGITUDE)
        }
        if (!isGpuRepresentable(logical.y)) {
            return gpuRepresentabilityFailure(DiagnosticField.GEOMETRY_LATITUDE)
        }
        if (!isGpuRepresentable(logical.z)) {
            return gpuRepresentabilityFailure(DiagnosticField.GEOMETRY_ALTITUDE)
        }
        resolvedCorners += logical
    }

    return SpatialOutcome.Success(
        ResolvedGeometry(
            cornersClockwiseFromTopLeft = resolvedCorners,
            shaderPair = geometry.shaderPair,
        ),
    )
}
