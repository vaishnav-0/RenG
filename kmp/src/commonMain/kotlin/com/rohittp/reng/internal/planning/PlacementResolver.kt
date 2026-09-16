package com.rohittp.reng.internal.planning

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Placement
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.MercatorPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.WORLD_CIRCUMFERENCE_METRES
import com.rohittp.reng.internal.projection.validateMercatorMapPosition
import kotlin.math.PI
import kotlin.math.cos

internal enum class DrawRegime {
    MAP_OCCLUDED,
    SCREEN_COMPOSITED,
}

internal data class ResolvedPlacement(
    val drawRegime: DrawRegime,
    val logicalPosition: DoubleVector3,
    val directionTransform: DoubleMatrix3,
    val logicalScale: Double,
    val screenCompositeZ: Double?,
) {
    init {
        require((screenCompositeZ != null) == (drawRegime == DrawRegime.SCREEN_COMPOSITED)) {
            "screenCompositeZ must be present exactly for screen-composited placements"
        }
    }
}

internal fun resolvePlacement(
    placement: Placement,
    camera: ResolvedMercatorCamera,
): SpatialOutcome<ResolvedPlacement> {
    val geographicAnchor: GeographicPosition
    val logicalPosition: DoubleVector3
    val drawRegime: DrawRegime
    val screenCompositeZ: Double?

    when (placement.positionMode) {
        AnchoringMode.MAP -> {
            geographicAnchor = GeographicPosition(
                latitude = placement.position.x,
                unwrappedLongitude = placement.position.y,
                altitudeMetres = placement.position.z,
            )
            val projectedOutcome = validateMercatorMapPosition(geographicAnchor)
            if (projectedOutcome is SpatialOutcome.Failure) return projectedOutcome
            val projected = (projectedOutcome as SpatialOutcome.Success).value
            val logicalOutcome = resolveCameraRelativeMapPosition(
                projected = projected,
                camera = camera,
                latitudeField = DiagnosticField.MAP_POSITION_LATITUDE,
                longitudeField = DiagnosticField.MAP_POSITION_UNWRAPPED_LONGITUDE,
                altitudeField = DiagnosticField.MAP_POSITION_ALTITUDE,
            )
            if (logicalOutcome is SpatialOutcome.Failure) return logicalOutcome

            logicalPosition = (logicalOutcome as SpatialOutcome.Success).value
            drawRegime = DrawRegime.MAP_OCCLUDED
            screenCompositeZ = null
        }

        AnchoringMode.SCREEN -> {
            if (!isGpuRepresentable(placement.position.x)) {
                return gpuRepresentabilityFailure(DiagnosticField.SCREEN_POSITION_X)
            }
            if (!isGpuRepresentable(placement.position.y)) {
                return gpuRepresentabilityFailure(DiagnosticField.SCREEN_POSITION_Y)
            }

            geographicAnchor = camera.geographicGroundAnchor
            logicalPosition = DoubleVector3(
                x = placement.position.x,
                y = placement.position.y,
                z = 0.0,
            )
            drawRegime = DrawRegime.SCREEN_COMPOSITED
            screenCompositeZ = placement.position.z
        }
    }

    val localRotation = DoubleMatrix3.rotationXyzDegrees(
        x = placement.rotation.x,
        y = placement.rotation.y,
        z = placement.rotation.z,
    )
    val directionTransform = when (placement.rotationMode) {
        AnchoringMode.SCREEN -> localRotation
        // **A map-anchored rotation is transported flatly here, not spherically** (ADR 0063).
        //
        // Upstream composed `viewBasis * cameraEnu^T * anchorEnu * localRotation`, which
        // `GlobePlacementResolver`'s KDoc calls deliberate: under Mercator it "makes a distant model
        // tilt as though the earth were round". On a globe that is right. This camera draws a flat
        // map, where north is straight up at every point -- so the transport between two anchors IS
        // the identity, and a spherical one rotates by an angle the projection already flattened away.
        //
        // Measured at latitude 20, camera level, model stationary: 4 degrees of drift at 10 degrees
        // of separation, 12 at 20, and 34 plus a 37% loss of drawn area at 40, the model tipping
        // while nothing else in the frame does. `GlobePlacementResolver` keeps the spherical form.
        AnchoringMode.MAP -> {
            val viewBasis = DoubleMatrix3.fromRows(
                listOf(
                    listOf(camera.right.x, camera.right.y, camera.right.z),
                    listOf(camera.cameraUp.x, camera.cameraUp.y, camera.cameraUp.z),
                    listOf(camera.cameraBack.x, camera.cameraBack.y, camera.cameraBack.z),
                ),
            )
            viewBasis * localRotation
        }
    }

    val logicalScale = when (placement.scaleMode) {
        AnchoringMode.SCREEN -> placement.scale
        AnchoringMode.MAP -> placement.scale * camera.worldSizeLogicalPixels /
            (WORLD_CIRCUMFERENCE_METRES * cos(geographicAnchor.latitude.degreesToRadians()))
    }
    if (!isGpuRepresentable(logicalScale)) {
        return gpuRepresentabilityFailure(DiagnosticField.PLACEMENT_SCALE)
    }

    return SpatialOutcome.Success(
        ResolvedPlacement(
            drawRegime = drawRegime,
            logicalPosition = logicalPosition,
            directionTransform = directionTransform,
            logicalScale = logicalScale,
            screenCompositeZ = screenCompositeZ,
        ),
    )
}

internal fun resolveCameraRelativeMapPosition(
    projected: MercatorPosition,
    camera: ResolvedMercatorCamera,
    latitudeField: DiagnosticField,
    longitudeField: DiagnosticField,
    altitudeField: DiagnosticField,
): SpatialOutcome<DoubleVector3> {
    val logicalPosition = DoubleVector3(
        x = (projected.x - camera.mercatorAnchor.x) * camera.worldSizeLogicalPixels,
        y = (camera.mercatorAnchor.y - projected.y) * camera.worldSizeLogicalPixels,
        z = projected.z * camera.worldSizeLogicalPixels,
    )
    if (!isGpuRepresentable(logicalPosition.x)) return gpuRepresentabilityFailure(longitudeField)
    if (!isGpuRepresentable(logicalPosition.y)) return gpuRepresentabilityFailure(latitudeField)
    if (!isGpuRepresentable(logicalPosition.z)) return gpuRepresentabilityFailure(altitudeField)
    return SpatialOutcome.Success(logicalPosition)
}

private fun Double.degreesToRadians(): Double = this * PI / 180.0
