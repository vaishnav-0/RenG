package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveCameraRelativeMapPosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every fixture here is asymmetric in both screen axes on purpose.
 *
 * A projection fixture at the viewport centre, at latitude 0, longitude 0, integer zoom, bearing 0
 * and pitch 0 is symmetric in every axis, and discriminates none of the four bugs this file exists
 * to catch: a transposed x and y, a sign-flipped y (world space is y-up, screen space is y-down), a
 * bearing applied in the wrong direction, and a viewport transform that forgot where pixel centres
 * lie. So the oblique camera has a non-square viewport of odd dimensions, a non-integer zoom, and a
 * bearing and a pitch that are both non-zero at once — because either bug can hide when the other
 * angle is zero — and its target point sits off-centre by different amounts in x and in y.
 */
class ScreenProjectionTest {
    @Test
    fun obliqueGroundAnchorProjectsToTheAnalyticallyDerivedPixel() {
        val camera = resolve(OBLIQUE)
        val projection = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(camera, TARGET_GROUND),
        )
        val expected = analyticProjection(OBLIQUE, TARGET_GROUND)

        // Roughly (636.14, 177.07) in an 853 by 509 viewport whose centre is (426.5, 254.5):
        // 209 pixels right of centre and 77 above it, so neither axis can stand in for the other.
        assertClose(expected.pixelX, projection.pixelX)
        assertClose(expected.pixelY, projection.pixelY)
        assertClose(expected.w, projection.w)
        assertTrue(projection.pixelX > camera.outputPixelSize.width / 2.0)
        assertTrue(projection.pixelY < camera.outputPixelSize.height / 2.0)
    }

    @Test
    fun altitudeLiftsTheProjectedPixelAndShortensTheDepthDivisor() {
        val camera = resolve(OBLIQUE)
        val ground = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(camera, TARGET_GROUND),
        )
        val raised = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(camera, TARGET_RAISED),
        )
        val expected = analyticProjection(OBLIQUE, TARGET_RAISED)

        assertClose(expected.pixelX, raised.pixelX)
        assertClose(expected.pixelY, raised.pixelY)
        assertClose(expected.w, raised.w)

        // Altitude is not a no-op on a pitched camera: raising the point moves it up the screen and
        // brings it nearer the camera plane, and it moves in x as well because the whole divisor
        // changed. All three would be invisible if the metres-to-Mercator-z step were dropped.
        assertTrue(raised.pixelY < ground.pixelY - 1.0)
        assertTrue(raised.w < ground.w - 1.0)
        assertTrue(abs(raised.pixelX - ground.pixelX) > 1.0)
    }

    @Test
    fun pixelCentresRoundTripThroughTheGroundRayBackToThemselves() {
        val camera = resolve(OBLIQUE)

        // Deliberately not the viewport centre and not the diagonal: two corners, an off-diagonal
        // interior pixel, and a pixel on the far edge.
        for ((pixelIndexX, pixelIndexY) in listOf(0 to 0, 137 to 71, 700 to 430, 852 to 508, 1 to 507)) {
            val hit = assertIs<GroundRayResult.Hit>(
                physicalPixelGroundRay(camera, pixelIndexX, pixelIndexY),
            )
            val logical = cameraRelative(camera, hit.point)
            val projection = assertIs<ScreenProjection.Projected>(
                projectCameraRelativeLogicalPosition(camera, logical),
            )

            // A pixel INDEX names the pixel whose centre is at index + 0.5 in the continuous screen
            // space `CONTEXT.md` defines. Forgetting that half is the classic viewport-transform
            // bug, and it is worth exactly one pixel of label displacement — invisible in a frame.
            assertClose(pixelIndexX + 0.5, projection.pixelX, ROUND_TRIP_PIXEL_TOLERANCE)
            assertClose(pixelIndexY + 0.5, projection.pixelY, ROUND_TRIP_PIXEL_TOLERANCE)

            // w and the ground ray's own parameter are the same quantity measured two ways.
            assertClose(hit.t, projection.w, ROUND_TRIP_PIXEL_TOLERANCE)
        }
    }

    @Test
    fun aGroundPointRoundTripsThroughItsPixelBackToItsOwnMercatorPosition() {
        val camera = resolve(OBLIQUE)
        val projection = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(camera, TARGET_GROUND),
        )
        val pixelIndexX = round(projection.pixelX - 0.5).toInt()
        val pixelIndexY = round(projection.pixelY - 0.5).toInt()
        val landed = groundPoint(camera, pixelIndexX, pixelIndexY)
        val target = projectMercator(TARGET_GROUND)

        // Rounding to a pixel index throws away up to half a pixel in each axis, so the tolerance
        // is one pixel's worth of ground, measured from the two neighbouring pixels of the very
        // pixel we landed on rather than assumed. The achieved error is about 0.42 of that step.
        val step = maxOf(
            mercatorDistance(landed, groundPoint(camera, pixelIndexX + 1, pixelIndexY)),
            mercatorDistance(landed, groundPoint(camera, pixelIndexX, pixelIndexY + 1)),
        )
        val error = mercatorDistance(landed, MercatorGroundPoint(target.x, target.y))

        assertTrue(step > 0.0, "the ground step must be measurable")
        assertTrue(error <= step, "round trip missed by $error, one pixel of ground is $step")
    }

    @Test
    fun aPositionBehindTheCameraIsRejectedInsteadOfProjectingToAPlausiblePixel() {
        val camera = resolve(OBLIQUE)
        val rejected = assertIs<ScreenProjection.BehindNearPlane>(
            projectGeographicPosition(camera, TARGET_BEHIND),
        )

        // This is the whole reason w is returned. The point sits to the camera's LEFT and behind
        // it; divided through by a negative w anyway, it lands at a finite, plausible-looking pixel
        // roughly 613 pixels RIGHT of centre — the mirror image of where it is — and would then be
        // boxed and collided against labels it can never have met.
        assertTrue(rejected.w < 0.0, "expected a negative depth divisor but was ${rejected.w}")

        val naive = analyticProjection(OBLIQUE, TARGET_BEHIND)
        assertTrue(naive.viewX < 0.0, "the fixture must sit left of the camera axis")
        assertTrue(naive.pixelX.isFinite() && naive.pixelX > OBLIQUE.output.width / 2.0)
    }

    @Test
    fun theNearPlaneRatherThanTheSignOfDepthIsTheAcceptanceBoundary() {
        val camera = resolve(OBLIQUE)
        val justInFront = NEAR_DISTANCE_LOGICAL_PIXELS + 0.5
        val justBehind = NEAR_DISTANCE_LOGICAL_PIXELS - 0.5
        val accepted = assertIs<ScreenProjection.Projected>(
            projectCameraRelativeLogicalPosition(camera, logicalPositionAtDepth(justInFront)),
        )
        val rejected = assertIs<ScreenProjection.BehindNearPlane>(
            projectCameraRelativeLogicalPosition(camera, logicalPositionAtDepth(justBehind)),
        )

        assertClose(justInFront, accepted.w, BOUNDARY_TOLERANCE)

        // The discriminating half: this point is in FRONT of the camera plane and is still rejected,
        // because the boundary is the near plane the inverse direction near-clips at, not zero. A
        // `w > 0` test would have accepted it and drawn a label from inside the lens.
        assertClose(justBehind, rejected.w, BOUNDARY_TOLERANCE)
        assertTrue(rejected.w > 0.0)
    }

    @Test
    fun nonFiniteDepthAndOverflowingPixelsAreRejectedRatherThanEscapingAsNumbers() {
        val camera = resolve(OBLIQUE)

        // The classic one: a NaN coordinate makes w NaN, and `w < near` is FALSE for NaN, so a
        // comparison written that way would divide into NaN pixels. A NaN pixel compares false
        // against every bound, so it survives a viewport cull and then poisons collision.
        val notANumber = assertIs<ScreenProjection.BehindNearPlane>(
            projectCameraRelativeLogicalPosition(camera, DoubleVector3(Double.NaN, 0.0, 0.0)),
        )
        assertTrue(notANumber.w.isNaN(), "expected a NaN depth divisor but was ${notANumber.w}")

        // Finite coordinates are enough to overflow w on their own, in either direction.
        val infinitelyFarBehind = assertIs<ScreenProjection.BehindNearPlane>(
            projectCameraRelativeLogicalPosition(
                camera,
                DoubleVector3(-Double.MAX_VALUE, -Double.MAX_VALUE, 0.0),
            ),
        )
        assertTrue(infinitelyFarBehind.w == Double.NEGATIVE_INFINITY)

        // And the half the near-plane guard cannot see: w stays finite and comfortably in front of
        // the near plane while the pixel itself overflows. There is no pixel, so there is no
        // Projected — this is the branch that keeps an infinity from leaving as a coordinate.
        assertIs<ScreenProjection.OutsideSupportedDomain>(
            projectCameraRelativeLogicalPosition(camera, DoubleVector3(Double.MAX_VALUE, 0.0, 0.0)),
        )
        assertIs<ScreenProjection.OutsideSupportedDomain>(
            projectCameraRelativeLogicalPosition(
                camera,
                DoubleVector3(Double.MAX_VALUE, Double.MAX_VALUE, 0.0),
            ),
        )
    }

    @Test
    fun geographicPositionsOutsideMercatorSupportAreRejectedWithoutAPixel() {
        val camera = resolve(OBLIQUE)

        for (position in listOf(
            GeographicPosition(MERCATOR_MAXIMUM_LATITUDE_DEGREES + 1.0, 2.294694, 0.0),
            GeographicPosition(48.858093, Double.NaN, 0.0),
            GeographicPosition(48.858093, 2.294694, Double.POSITIVE_INFINITY),
            GeographicPosition(48.858093, 16385.0 * 360.0 - 180.0, 0.0),
        )) {
            assertIs<ScreenProjection.OutsideSupportedDomain>(
                projectGeographicPosition(camera, position),
                "expected $position to be outside the supported domain",
            )
        }
    }

    @Test
    fun bearingPutsNorthOnTheLeftWhenTheCameraFacesEast() {
        // Facing east, screen-right is south, so a point due north of the camera must appear to the
        // LEFT of the vertical centre line and a point due east must appear ABOVE the horizontal
        // one. A bearing applied in the wrong direction mirrors both, and no analytic formula
        // derived alongside the implementation would notice, because it would carry the same sign.
        val facingEast = OBLIQUE.copy(bearing = 90.0, pitch = 30.0)
        val camera = resolve(facingEast)
        val halfWidth = facingEast.output.width / 2.0
        val halfHeight = facingEast.output.height / 2.0

        val toTheNorth = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(
                camera,
                GeographicPosition(facingEast.latitude + 0.004, facingEast.unwrappedLongitude, 0.0),
            ),
        )
        val toTheEast = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(
                camera,
                GeographicPosition(facingEast.latitude, facingEast.unwrappedLongitude + 0.004, 0.0),
            ),
        )

        assertTrue(toTheNorth.pixelX < halfWidth - 1.0, "north sat at ${toTheNorth.pixelX}")
        assertClose(halfHeight, toTheNorth.pixelY, BOUNDARY_TOLERANCE)
        assertTrue(toTheEast.pixelY < halfHeight - 1.0, "east sat at ${toTheEast.pixelY}")
        assertClose(halfWidth, toTheEast.pixelX, BOUNDARY_TOLERANCE)

        // Facing east leaves the northward point exactly its own ground distance to the left of
        // centre, whatever the pitch, because it lies in the camera's own plane of constant depth.
        val expected = analyticProjection(facingEast, GeographicPosition(
            facingEast.latitude + 0.004,
            facingEast.unwrappedLongitude,
            0.0,
        ))
        assertClose(expected.pixelX, toTheNorth.pixelX)
    }

    /**
     * The expected pixel, derived from the camera's own definition rather than from the code under
     * test: no matrix is multiplied and no function in `internal.projection` other than the Mercator
     * primitive is called.
     *
     * With `right`, `cameraUp` and `cameraBack` written out from bearing and pitch, and the camera
     * `distance` of `height * (1 + sqrt(2)) / 2`, the whole projection collapses to
     * `pixelX = width / 2 + distance * viewX / w` and `pixelY = height / 2 - distance * viewY / w`,
     * because the projection matrix's `focal / aspect` cancels the viewport's `width / 2` down to
     * `height * focal / 2`. The minus sign in front of `viewY` is world-up meeting screen-down.
     */
    private fun analyticProjection(fixture: Fixture, position: GeographicPosition): Analytic {
        val anchor = projectMercator(
            GeographicPosition(fixture.latitude, fixture.unwrappedLongitude, 0.0),
        )
        val target = projectMercator(position)
        val world = 512.0 * 2.0.pow(fixture.zoom)
        val east = (target.x - anchor.x) * world
        val north = (anchor.y - target.y) * world
        val up = target.z * world

        val bearing = fixture.bearing * PI / 180.0
        val pitch = fixture.pitch * PI / 180.0
        val viewX = cos(bearing) * east - sin(bearing) * north
        val forward = sin(bearing) * east + cos(bearing) * north
        val viewY = cos(pitch) * forward + sin(pitch) * up
        val distance = fixture.output.height * FOCAL_LENGTH_SCALE / 2.0
        val w = distance + sin(pitch) * forward - cos(pitch) * up

        return Analytic(
            pixelX = fixture.output.width / 2.0 + distance * viewX / w,
            pixelY = fixture.output.height / 2.0 - distance * viewY / w,
            w = w,
            viewX = viewX,
        )
    }

    /**
     * A camera-relative logical position whose depth divisor is exactly [depth], built by solving
     * `w = distance + sin(pitch) * forward - cos(pitch) * up` for `up`. Its x and y stay non-zero so
     * the result is still off-centre in both axes.
     */
    private fun logicalPositionAtDepth(depth: Double): DoubleVector3 {
        val bearing = OBLIQUE.bearing * PI / 180.0
        val pitch = OBLIQUE.pitch * PI / 180.0
        val east = 289.6
        val north = -33.2
        val forward = sin(bearing) * east + cos(bearing) * north
        val distance = OBLIQUE.output.height * FOCAL_LENGTH_SCALE / 2.0
        return DoubleVector3(east, north, (distance + sin(pitch) * forward - depth) / cos(pitch))
    }

    private fun groundPoint(
        camera: ResolvedMercatorCamera,
        pixelIndexX: Int,
        pixelIndexY: Int,
    ): MercatorGroundPoint =
        assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(camera, pixelIndexX, pixelIndexY)).point

    private fun cameraRelative(
        camera: ResolvedMercatorCamera,
        point: MercatorGroundPoint,
    ): DoubleVector3 = assertIs<SpatialOutcome.Success<DoubleVector3>>(
        resolveCameraRelativeMapPosition(
            projected = MercatorPosition(point.x, point.y, 0.0),
            camera = camera,
            latitudeField = DiagnosticField.MAP_POSITION_LATITUDE,
            longitudeField = DiagnosticField.MAP_POSITION_UNWRAPPED_LONGITUDE,
            altitudeField = DiagnosticField.MAP_POSITION_ALTITUDE,
        ),
    ).value

    private fun mercatorDistance(first: MercatorGroundPoint, second: MercatorGroundPoint): Double {
        val deltaX = first.x - second.x
        val deltaY = first.y - second.y
        return sqrt(deltaX * deltaX + deltaY * deltaY)
    }

    private fun resolve(fixture: Fixture): ResolvedMercatorCamera =
        assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(
                camera = Camera(
                    latitude = fixture.latitude,
                    unwrappedLongitude = fixture.unwrappedLongitude,
                    zoom = fixture.zoom,
                    bearing = fixture.bearing,
                    pitch = fixture.pitch,
                ),
                outputPixelSize = fixture.output,
            ),
        ).value

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = ANALYTIC_TOLERANCE) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")
    }

    private data class Fixture(
        val latitude: Double,
        val unwrappedLongitude: Double,
        val zoom: Double,
        val bearing: Double,
        val pitch: Double,
        val output: OutputPixelSize,
    )

    private data class Analytic(
        val pixelX: Double,
        val pixelY: Double,
        val w: Double,
        val viewX: Double,
    )

    private companion object {
        val FOCAL_LENGTH_SCALE: Double = 1.0 + sqrt(2.0)

        /** Odd non-square viewport, non-integer zoom, and a bearing and pitch both off zero. */
        val OBLIQUE: Fixture = Fixture(
            latitude = 48.858093,
            unwrappedLongitude = 2.294694,
            zoom = 13.7,
            bearing = 37.5,
            pitch = 52.0,
            output = OutputPixelSize(width = 853, height = 509),
        )

        /** About 250 logical pixels to the camera's right and 150 ahead of it: off-centre in both. */
        val TARGET_GROUND: GeographicPosition =
            GeographicPosition(latitude = 48.856939, unwrappedLongitude = 2.309995, altitudeMetres = 0.0)

        val TARGET_RAISED: GeographicPosition = TARGET_GROUND.copy(altitudeMetres = 320.0)

        /** Far enough behind the camera plane that w goes negative rather than merely small. */
        val TARGET_BEHIND: GeographicPosition =
            GeographicPosition(latitude = 48.832363, unwrappedLongitude = 2.244501, altitudeMetres = 0.0)

        /** Measured maxima over the fixtures below are 3.5e-10 pixels and 1.9e-10 in w. */
        const val ROUND_TRIP_PIXEL_TOLERANCE: Double = 1e-8

        const val ANALYTIC_TOLERANCE: Double = 1e-8

        /** Depths near 1 are a difference of two quantities near 600, so they lose that much. */
        const val BOUNDARY_TOLERANCE: Double = 1e-6
    }
}
