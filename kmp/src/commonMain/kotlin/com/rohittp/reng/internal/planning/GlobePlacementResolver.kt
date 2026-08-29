package com.rohittp.reng.internal.planning

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Placement
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.GlobeLimbPlane
import com.rohittp.reng.internal.projection.MercatorPosition
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.cameraRelativeLogicalPosition
import com.rohittp.reng.internal.projection.globeEastNorthUpBasis
import com.rohittp.reng.internal.projection.globeMetresToLogicalPixels
import com.rohittp.reng.internal.projection.projectGlobe
import com.rohittp.reng.internal.projection.validateMercatorMapPosition

/**
 * One [Placement] resolved against a globe camera, plus the horizon verdict that only a globe has.
 *
 * The two are carried together rather than separately because they are answers about the same
 * object derived from the same intermediate — the placement's globe-fixed position — and asking for
 * one without the other is what would let a caller draw a sticker standing on the far side of the
 * planet.
 *
 * [beyondHorizon] is **not a failure**. Nothing is wrong with a placement the planet happens to be
 * standing in front of; the caller draws the rest of the frame and skips this one. It is also not a
 * viewport test: a placement can be beyond the horizon and still project to a pixel in the middle of
 * the screen, which is precisely the case [GlobeLimbPlane] exists for.
 */
internal data class GlobePlacement(
    val placement: ResolvedPlacement,
    val beyondHorizon: Boolean,
)

/**
 * [resolvePlacement]'s globe arm: the same five outputs, derived on a sphere.
 *
 * ## What changes, and what deliberately does not
 *
 * **Position changes.** The Mercator arm scales a Mercator offset by the world size onto a plane
 * ([resolveCameraRelativeMapPosition]); this one puts the anchor on the sphere with [projectGlobe]
 * and rotates it into the camera's own east/north/up frame with [cameraRelativeLogicalPosition].
 * Both produce the same *kind* of value — camera-relative logical pixels — which is what lets
 * `SceneContent`'s matrix composition, `ScreenProjection`'s perspective divide and
 * [ResolvedPlacement] itself stay one shape across both modes.
 *
 * **Screen anchoring does not change at all.** `CONTEXT.md`'s Screen Anchoring is resolution against
 * continuous output-pixel screen space and never involves the camera's view or projection matrix, so
 * a screen-positioned placement resolves bit-for-bit as it does under Mercator and a
 * screen-composited sticker is never culled by the horizon. Copying the arm rather than sharing it
 * would be the mistake; the code below is deliberately the identical expression.
 *
 * **Map-anchored rotation keeps its shape and changes its basis source.** The Mercator arm composes
 * `viewBasis * cameraEnu^T * anchorEnu * localRotation` out of [wgs84LocalFrame] bases, which is
 * already an ECEF-referenced formulation rather than a plane one — under Mercator it is a deliberate
 * choice that makes a distant model tilt as though the earth were round, and on a globe it is simply
 * the true answer. What moves is where the two bases come from: [globeEastNorthUpBasis] for the
 * anchor and the camera's own stored [ResolvedGlobeCamera.anchorEast] / `anchorNorth` / `anchorUp`
 * for the camera, so the frame a position is expressed in and the frame a rotation is expressed in
 * are the same object rather than two derivations that can disagree. They agree with
 * [wgs84LocalFrame]'s basis to within a few ULP anyway — a geodetic latitude is defined by the
 * ellipsoid normal — which is exactly why using the camera's own is free.
 *
 * ## The cosine that must not be copied
 *
 * **`resolvePlacement`'s map scale divides by `cos(latitude)` and this one must not**, and neither
 * must the altitude term. Both are Mercator's own area distortion; a sphere has none. Copying either
 * makes a map-scaled object **2x too large at latitude 60** while agreeing **exactly at the
 * equator**, so a fixture written at the natural place would be green against the defect
 * (`docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` section 8). The altitude half is
 * handled inside [projectGlobe], which documents [MercatorPosition.z] as deliberately unused; the
 * scale half is [globeMetresToLogicalPixels] here.
 *
 * There is a second trap stacked on the first, and it is why the scale test in this file's suite
 * uses a placement latitude away from the camera's. ADR 0037's latitude-matched zoom already scales
 * the whole globe by `1 / cos(cameraLatitude)`, so at a placement sitting at the **camera's own**
 * latitude the correct globe scale and the buggy cosine-divided one are numerically identical. The
 * convention that makes the two modes agree where they should is the same convention that hides the
 * defect where a fixture would naturally sit.
 *
 * ## The one guard that survives, and the one that does not
 *
 * `ScreenProjection`'s globe arm records that a camera-relative position on a sphere is bounded by
 * `2 * radius + cameraDistance` and needs no GPU-representability guard. That is true of the
 * *sphere*, and **not** of a placement, because a placement carries an altitude and
 * [validateMercatorMapPosition] bounds altitude only by finiteness: an altitude of `1e300` metres
 * resolves to a finite `Double` radius and an infinite `Float` one, and this arm's output is
 * narrowed to `Float` by `SceneContent`'s matrix composition where `ScreenProjection`'s is not. The
 * guard is therefore live here and reports [DiagnosticField.MAP_POSITION_ALTITUDE] for all three
 * components rather than the Mercator arm's per-axis fields — on a sphere every bounded term is
 * bounded, so altitude is the only input that can produce one.
 */
internal fun resolveGlobePlacement(
    placement: Placement,
    camera: ResolvedGlobeCamera,
    limbPlane: GlobeLimbPlane? = camera.limbPlane,
): SpatialOutcome<GlobePlacement> {
    val anchorMercator: MercatorPosition
    val logicalPosition: DoubleVector3
    val drawRegime: DrawRegime
    val screenCompositeZ: Double?
    val beyondHorizon: Boolean

    when (placement.positionMode) {
        AnchoringMode.MAP -> {
            val geographicAnchor = GeographicPosition(
                latitude = placement.position.x,
                unwrappedLongitude = placement.position.y,
                altitudeMetres = placement.position.z,
            )
            val projectedOutcome = validateMercatorMapPosition(geographicAnchor)
            if (projectedOutcome is SpatialOutcome.Failure) return projectedOutcome
            anchorMercator = (projectedOutcome as SpatialOutcome.Success).value

            // The globe-fixed position is kept rather than only its camera-relative form, because
            // the horizon test is a plane in the globe-fixed frame and inverting the rotation to get
            // back there would be a second derivation of a transform this file already has.
            val globeFixedPosition = projectGlobe(
                mercatorX = anchorMercator.x,
                mercatorY = anchorMercator.y,
                altitudeMetres = geographicAnchor.altitudeMetres,
                radiusLogicalPixels = camera.radiusLogicalPixels,
            )
            val cameraRelative = cameraRelativeLogicalPosition(camera, globeFixedPosition)
            if (!isGpuRepresentable(cameraRelative.x) ||
                !isGpuRepresentable(cameraRelative.y) ||
                !isGpuRepresentable(cameraRelative.z)
            ) {
                return gpuRepresentabilityFailure(DiagnosticField.MAP_POSITION_ALTITUDE)
            }

            logicalPosition = cameraRelative
            drawRegime = DrawRegime.MAP_OCCLUDED
            screenCompositeZ = null
            beyondHorizon = limbPlane?.isBeyondHorizon(globeFixedPosition) ?: false
        }

        AnchoringMode.SCREEN -> {
            if (!isGpuRepresentable(placement.position.x)) {
                return gpuRepresentabilityFailure(DiagnosticField.SCREEN_POSITION_X)
            }
            if (!isGpuRepresentable(placement.position.y)) {
                return gpuRepresentabilityFailure(DiagnosticField.SCREEN_POSITION_Y)
            }

            anchorMercator = camera.mercatorAnchor
            logicalPosition = DoubleVector3(
                x = placement.position.x,
                y = placement.position.y,
                z = 0.0,
            )
            drawRegime = DrawRegime.SCREEN_COMPOSITED
            screenCompositeZ = placement.position.z
            // A screen-composited thing is a HUD element in output-pixel space; the planet is not
            // between the camera and it, and there is no globe-fixed position to test.
            beyondHorizon = false
        }
    }

    val localRotation = DoubleMatrix3.rotationXyzDegrees(
        x = placement.rotation.x,
        y = placement.rotation.y,
        z = placement.rotation.z,
    )
    val directionTransform = when (placement.rotationMode) {
        AnchoringMode.SCREEN -> localRotation
        AnchoringMode.MAP -> {
            val viewBasis = DoubleMatrix3.fromRows(
                listOf(
                    listOf(camera.right.x, camera.right.y, camera.right.z),
                    listOf(camera.cameraUp.x, camera.cameraUp.y, camera.cameraUp.z),
                    listOf(camera.cameraBack.x, camera.cameraBack.y, camera.cameraBack.z),
                ),
            )
            val cameraBasis = DoubleMatrix3.fromColumns(
                camera.anchorEast,
                camera.anchorNorth,
                camera.anchorUp,
            )
            val anchorBasis = globeEastNorthUpBasis(anchorMercator.x, anchorMercator.y)
            viewBasis * cameraBasis.transpose() * anchorBasis * localRotation
        }
    }

    val logicalScale = when (placement.scaleMode) {
        AnchoringMode.SCREEN -> placement.scale
        AnchoringMode.MAP ->
            placement.scale * globeMetresToLogicalPixels(camera.radiusLogicalPixels)
    }
    if (!isGpuRepresentable(logicalScale)) {
        return gpuRepresentabilityFailure(DiagnosticField.PLACEMENT_SCALE)
    }

    return SpatialOutcome.Success(
        GlobePlacement(
            placement = ResolvedPlacement(
                drawRegime = drawRegime,
                logicalPosition = logicalPosition,
                directionTransform = directionTransform,
                logicalScale = logicalScale,
                screenCompositeZ = screenCompositeZ,
            ),
            beyondHorizon = beyondHorizon,
        ),
    )
}
