package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0038's horizon test: the plane through the limb circle, on the CPU, in `Double`.
 *
 * The one case this file exists for is
 * [theAntipodalPointHasTheLargestPositiveWOfAnyPointOnThePlanetAndLandsDeadCentre]. Both the cycle's
 * specification and its plan first described this test as "the sign of `w` that `ScreenProjection`
 * already computes", and that would have shipped a cull that culls nothing while looking correct.
 * The fixture there is deliberately the hardest possible one: the culled point projects to the exact
 * centre of the frame and carries the *largest* `w` any point of the globe can carry, so neither a
 * `w` test nor a viewport test can reject it. Only the plane can.
 */
class GlobeHorizonTest {

    // --- the plane itself ------------------------------------------------------------------------

    @Test fun thePlaneNormalIsTheCameraDirectionAndTheOffsetIsTheRadiusSquaredOverTheDistance() {
        val plane = requireNotNull(globeLimbPlane(cameraPosition(), RADIUS))
        assertEquals(1.0, sqrt(plane.normal.dot(plane.normal)), 1e-12, "the normal must be a unit vector")
        assertEquals(0.0, plane.normal.x, 1e-12)
        assertEquals(0.0, plane.normal.y, 1e-12)
        assertEquals(1.0, plane.normal.z, 1e-12)
        assertEquals(RADIUS * RADIUS / cameraDistance(), plane.offsetLogicalPixels, 1e-9)
    }

    /**
     * The plane is not merely *a* plane with the camera's normal — it is the one containing the
     * horizon circle, where the tangent lines from the camera touch. That circle sits at half-angle
     * `acos(R / d)` from the sub-camera point, and every point of it must measure exactly zero.
     *
     * Sampled around the whole circle rather than at one azimuth, because a plane built from a normal
     * that had lost a component would still measure zero at the azimuth it was derived from.
     */
    @Test fun everyPointOfTheHorizonCircleSitsExactlyOnThePlane() {
        val plane = requireNotNull(globeLimbPlane(cameraPosition(), RADIUS))
        val limbAngle = acos(RADIUS / cameraDistance())
        (0 until 37).forEach { step ->
            val azimuth = 2.0 * PI * step / 37.0
            val point = surfacePoint(limbAngle, azimuth)
            assertEquals(
                0.0,
                plane.signedDistanceLogicalPixels(point),
                1e-8,
                "the limb point at azimuth $azimuth must sit on the plane",
            )
            assertFalse(plane.isBeyondHorizon(point), "the limb itself is visible, inclusively")
        }
    }

    /**
     * The offset is load-bearing, and this is the case that says so. A point on the equator of the
     * camera's own view — 90 degrees round from the sub-camera point — has a dot product of exactly
     * zero against the camera direction, so a test written as `normal.dot(p) >= 0` calls it visible.
     * It is not: the horizon is at `acos(R / d)`, which is strictly less than 90 degrees for every
     * camera outside the sphere, so a quarter-turn round the planet is already well past the limb.
     */
    @Test fun aPointAQuarterTurnRoundThePlanetIsCulledEvenThoughItsDotProductIsZero() {
        val plane = requireNotNull(globeLimbPlane(cameraPosition(), RADIUS))
        val point = surfacePoint(PI / 2.0, 0.0)
        assertEquals(0.0, plane.normal.dot(point), 1e-9, "the fixture must sit exactly on the dot-product zero")
        assertTrue(plane.isBeyondHorizon(point), "a quarter turn round the planet is beyond the horizon")
    }

    @Test fun justInsideTheLimbIsVisibleAndJustOutsideItIsNot() {
        val plane = requireNotNull(globeLimbPlane(cameraPosition(), RADIUS))
        val limbAngle = acos(RADIUS / cameraDistance())
        val inside = surfacePoint(limbAngle - 1e-3, 1.1)
        val outside = surfacePoint(limbAngle + 1e-3, 1.1)
        assertTrue(plane.signedDistanceLogicalPixels(inside) > 0.4, "just inside must be measurably positive")
        assertTrue(plane.signedDistanceLogicalPixels(outside) < -0.4, "just outside must be measurably negative")
        assertFalse(plane.isBeyondHorizon(inside))
        assertTrue(plane.isBeyondHorizon(outside))
    }

    // --- the case the `w` misconception would have failed -----------------------------------------

    /**
     * The discriminating fixture, and the reason ADR 0038 was written before this task.
     *
     * A camera outside a sphere has the whole sphere in front of it. The antipodal point — the single
     * most thoroughly hidden point on the planet — is therefore *further in front of the camera plane*
     * than anything else on it, and it sits on the view axis, so it lands on the exact centre pixel.
     * A horizon test written as "the sign of `w`" keeps it. A viewport test keeps it. The plane is
     * what rejects it, and this case fails under either substitute.
     *
     * The `w` and the pixel are measured through [projectCameraRelativeLogicalPosition] — RenG's own
     * projection, the same matrices the GPU is handed — rather than argued about.
     */
    @Test fun theAntipodalPointHasTheLargestPositiveWOfAnyPointOnThePlanetAndLandsDeadCentre() {
        val camera = topDownCamera()
        val plane = requireNotNull(globeLimbPlane(cameraPosition(), RADIUS))

        // The globe-fixed frame puts the sphere's centre at the origin and the camera at (0, 0, d).
        // The camera's own logical frame puts the *ground anchor* at the origin and the camera at
        // (0, 0, h), so the two differ by exactly the radius along z.
        val nearestPoint = DoubleVector3(0.0, 0.0, RADIUS)
        val antipodalPoint = DoubleVector3(0.0, 0.0, -RADIUS)
        val nearestProjection = projectCameraRelativeLogicalPosition(camera, nearestPoint.toCameraFrame())
        val antipodalProjection = projectCameraRelativeLogicalPosition(camera, antipodalPoint.toCameraFrame())

        val nearest = assertProjected(nearestProjection)
        val antipodal = assertProjected(antipodalProjection)

        assertTrue(
            antipodal.w > nearest.w,
            "the antipodal point must be further in front of the camera plane than the nearest one: " +
                "${antipodal.w} vs ${nearest.w}",
        )
        assertTrue(
            antipodal.w >= NEAR_DISTANCE_LOGICAL_PIXELS,
            "a w test would keep the antipodal point, which is the whole point of this case",
        )
        assertEquals(FRAME_PIXELS / 2.0, antipodal.pixelX, 1e-9, "it lands on the frame's own centre column")
        assertEquals(FRAME_PIXELS / 2.0, antipodal.pixelY, 1e-9, "it lands on the frame's own centre row")
        assertEquals(nearest.pixelX, antipodal.pixelX, 1e-9, "the hidden point shares the visible one's pixel")
        assertEquals(nearest.pixelY, antipodal.pixelY, 1e-9)

        assertFalse(plane.isBeyondHorizon(nearestPoint), "the sub-camera point is visible")
        assertTrue(plane.isBeyondHorizon(antipodalPoint), "the antipodal point is behind the planet")
    }

    // --- agreement with an independent reference --------------------------------------------------

    /**
     * The plane test, checked against something that shares no algebra with it: project the sphere's
     * centre onto the segment from the camera to the point, and see whether that segment goes strictly
     * inside the sphere. Occlusion is defined that way; the plane is a closed form for it, and a sign
     * flip, a missing offset or an unnormalised normal all break the agreement.
     *
     * Sampled asymmetrically — 71 polar steps against a 71-step domain of unequal azimuths, neither a
     * divisor of the other — because the poles and the sub-camera point are symmetry points of this
     * whole calculation. Points within five logical pixels of the limb are excluded, and the band is
     * derived rather than picked: the chord's penetration into the sphere is **second order** in the
     * angle past the limb (the tangent line touches, so the first-order term vanishes), measuring
     * 0.0019 logical pixels one pixel past the limb against 0.046 five pixels past it. Below that the
     * reference is measuring its own rounding. The limb itself is pinned by the two cases above.
     */
    @Test fun thePlaneAgreesWithAnIndependentSegmentTestOutsideTheLimbBand() {
        val camera = cameraPosition()
        val plane = requireNotNull(globeLimbPlane(camera, RADIUS))
        var decided = 0
        var beyond = 0
        (0 until 71).forEach { polarStep ->
            val polar = PI * (polarStep + 0.37) / 71.0
            (0 until 71).forEach { azimuthStep ->
                val azimuth = 2.0 * PI * (azimuthStep * 13 % 71 + 0.11) / 71.0
                val point = surfacePoint(polar, azimuth)
                val distance = plane.signedDistanceLogicalPixels(point)
                if (abs(distance) <= 5.0) return@forEach
                decided += 1
                if (plane.isBeyondHorizon(point)) beyond += 1
                assertEquals(
                    segmentEntersTheSphere(camera, point),
                    plane.isBeyondHorizon(point),
                    "the plane and an independent segment test must agree at polar $polar azimuth $azimuth " +
                        "(signed distance $distance)",
                )
            }
        }
        assertTrue(decided > 4000, "the sweep must actually decide most of its samples, not $decided")
        assertTrue(beyond > 1000, "the sweep must contain culled samples or it proves nothing")
        assertTrue(decided - beyond > 500, "the sweep must contain visible samples too, not ${decided - beyond}")
    }

    // --- ADR 0038's accepted gap, recorded rather than repaired -------------------------------------

    /**
     * **This case asserts a known defect, deliberately.** ADR 0038 accepts that content straddling the
     * limb is handled wrongly: a **Placement** resolves to one anchor, so the horizon test is one bit
     * for a whole object and a tall model just beyond the horizon is culled entirely rather than
     * having its top drawn.
     *
     * The point below is genuinely visible — the segment from the camera to it never enters the
     * sphere, so nothing occludes it — and the plane test culls it anyway, because the shadow region
     * is the tangent *cone* and the plane is the whole half-space. If a future cycle closes this gap,
     * this case is the one that must be rewritten, and ADR 0038 is where the reasoning to weigh lives:
     * every fix is one of the two alternatives that ADR rejected.
     *
     * **The window is narrow, and its ends are what make the case honest.** At 0.02 radians past the
     * limb the two bounds are `r >= 1000.29` to clear the tangent cone and `r < 1011.9` to stay behind
     * the plane, so the fixture's `1.005 R` — five logical pixels of altitude on a thousand-pixel globe
     * — sits inside both. Raise it and the point clears the plane too and is correctly kept, which is
     * exactly what an earlier draft of this case did at `1.05 R`: it failed, and the failure was the
     * fixture rather than the gap.
     */
    @Test fun aPointHighAboveTheGroundJustBeyondTheLimbIsCulledThoughNothingOccludesIt() {
        val camera = cameraPosition()
        val plane = requireNotNull(globeLimbPlane(camera, RADIUS))
        val limbAngle = acos(RADIUS / cameraDistance())
        val direction = surfacePoint(limbAngle + 0.02, 0.0) * (1.0 / RADIUS)
        val onTheGround = direction * RADIUS
        val highAbove = direction * (RADIUS * 1.005)

        assertTrue(plane.isBeyondHorizon(onTheGround), "the fixture's own footprint is beyond the limb")
        assertTrue(segmentEntersTheSphere(camera, onTheGround), "and the planet really does hide it")

        assertFalse(
            segmentEntersTheSphere(camera, highAbove),
            "the point above it is genuinely visible: nothing stands between it and the camera",
        )
        assertTrue(
            plane.isBeyondHorizon(highAbove),
            "and RenG culls it anyway -- ADR 0038's accepted gap, asserted so a fix has to come here",
        )
    }

    // --- the degenerate camera, which fails towards drawing rather than towards an empty map --------

    @Test fun aCameraOnOrInsideTheSphereHasNoLimbPlaneAndCullsNothing() {
        listOf(RADIUS, RADIUS * 0.5, 0.0).forEach { distance ->
            val inside = DoubleVector3(0.0, 0.0, distance)
            assertNull(globeLimbPlane(inside, RADIUS), "a camera at $distance has no horizon")
            assertFalse(
                isBeyondGlobeHorizon(inside, DoubleVector3(0.0, 0.0, -RADIUS), RADIUS),
                "with no plane the antipodal point must be drawn rather than the whole planet culled",
            )
        }
    }

    @Test fun anUnusableRadiusOrCameraHasNoLimbPlane() {
        assertNull(globeLimbPlane(cameraPosition(), 0.0))
        assertNull(globeLimbPlane(cameraPosition(), -1.0))
        assertNull(globeLimbPlane(cameraPosition(), Double.NaN))
        assertNull(globeLimbPlane(DoubleVector3(Double.NaN, 0.0, 0.0), RADIUS))
        assertNull(globeLimbPlane(DoubleVector3(0.0, 0.0, Double.POSITIVE_INFINITY), RADIUS))
    }

    /**
     * A non-finite position is culled rather than kept. `NaN < 0` is false, so the naive spelling
     * would keep it, and a `NaN` anchor goes on to produce a `NaN` pixel — which compares false
     * against every viewport bound and survives a viewport cull too.
     */
    @Test fun aNonFinitePositionIsCulledRatherThanSurvivingEveryComparison() {
        val plane = requireNotNull(globeLimbPlane(cameraPosition(), RADIUS))
        assertTrue(plane.isBeyondHorizon(DoubleVector3(Double.NaN, 0.0, 0.0)))
        assertTrue(plane.isBeyondHorizon(DoubleVector3(0.0, Double.NaN, RADIUS)))
        assertFalse(
            plane.isBeyondHorizon(DoubleVector3(0.0, 0.0, Double.POSITIVE_INFINITY)),
            "an infinitely distant point along the camera axis is still in front of the plane",
        )
    }

    @Test fun theConvenienceOverloadAgreesWithThePlaneItBuilds() {
        val camera = cameraPosition()
        val plane = requireNotNull(globeLimbPlane(camera, RADIUS))
        listOf(0.3, 0.9, 1.4, 2.0, 3.0).forEach { polar ->
            val point = surfacePoint(polar, 0.7)
            assertEquals(
                plane.isBeyondHorizon(point),
                isBeyondGlobeHorizon(camera, point, RADIUS),
                "the overload must not disagree with the plane at polar $polar",
            )
        }
    }

    // --- fixture ------------------------------------------------------------------------------------

    /**
     * Whether the segment from [from] to [to] passes strictly inside the sphere — the definition of
     * occlusion, reached by projecting the sphere's centre onto the segment rather than by any
     * rearrangement of `P . C >= R^2`, so it shares no algebra with the plane it is checking.
     *
     * The clamp is what keeps the endpoints out of it: for a point on the sphere the nearest point of
     * the segment is that point itself, at exactly `R`, and touching is not occluding. The `1e-6`
     * tolerance sits four orders of magnitude below the sphere and two below the sweep's own guard
     * band, so nothing this decides is decided by rounding.
     */
    private fun segmentEntersTheSphere(from: DoubleVector3, to: DoubleVector3): Boolean {
        val along = to - from
        val lengthSquared = along.dot(along)
        if (lengthSquared <= 0.0) return false
        val parameter = (-from.dot(along) / lengthSquared).coerceIn(0.0, 1.0)
        val nearest = from + along * parameter
        return sqrt(nearest.dot(nearest)) < RADIUS - 1e-6
    }

    private fun surfacePoint(polarAngleFromCameraAxis: Double, azimuth: Double): DoubleVector3 =
        DoubleVector3(
            x = RADIUS * sin(polarAngleFromCameraAxis) * cos(azimuth),
            y = RADIUS * sin(polarAngleFromCameraAxis) * sin(azimuth),
            z = RADIUS * cos(polarAngleFromCameraAxis),
        )

    /** The globe-fixed frame is centred on the sphere; the camera's logical frame on the ground anchor. */
    private fun DoubleVector3.toCameraFrame(): DoubleVector3 = DoubleVector3(x, y, z - RADIUS)

    private fun cameraDistance(): Double = RADIUS + eyeHeightLogicalPixels()

    private fun cameraPosition(): DoubleVector3 = DoubleVector3(0.0, 0.0, cameraDistance())

    private fun eyeHeightLogicalPixels(): Double = topDownCamera().cameraDistanceLogicalPixels

    private fun topDownCamera(): ResolvedMercatorCamera = (
        resolveMercatorCamera(
            camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 3.0, bearing = 0.0, pitch = 0.0),
            outputPixelSize = OutputPixelSize(FRAME_PIXELS.toInt(), FRAME_PIXELS.toInt()),
        ) as SpatialOutcome.Success
        ).value

    private fun assertProjected(projection: ScreenProjection): ScreenProjection.Projected {
        if (projection !is ScreenProjection.Projected) {
            throw AssertionError("the fixture must project, or this case tests nothing it claims: $projection")
        }
        return projection
    }

    private companion object {
        const val RADIUS: Double = 1000.0
        const val FRAME_PIXELS: Double = 128.0
    }
}
