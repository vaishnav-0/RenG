package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cycle G task 4.
 *
 * **Every case that can carries the tilted fixture** -- latitude 48.8566, a longitude two world
 * copies east, bearing 37 and pitch 52 at once. A camera at latitude 0, longitude 0, bearing 0 and
 * pitch 0 is symmetric in every axis and discriminates none of a transposed frame, a sign-flipped
 * axis or a bearing applied the wrong way round, and a bug in any one of bearing, pitch and
 * latitude hides while the other two are zero.
 */
class GlobeCameraTest {
    @Test
    fun anchorFrameMatchesTheEllipsoidBasisAndStaysRightHanded() {
        val resolved = resolveTilted()
        val ellipsoid = wgs84LocalFrame(resolved.geographicGroundAnchor).basisEastNorthUp

        assertVectorClose(ellipsoid.column(0), resolved.anchorEast)
        assertVectorClose(ellipsoid.column(1), resolved.anchorNorth)
        assertVectorClose(ellipsoid.column(2), resolved.anchorUp)
        assertClose(1.0, sqrt(resolved.anchorEast.dot(resolved.anchorEast)))
        assertClose(1.0, sqrt(resolved.anchorNorth.dot(resolved.anchorNorth)))
        assertClose(1.0, sqrt(resolved.anchorUp.dot(resolved.anchorUp)))
        assertClose(0.0, resolved.anchorEast.dot(resolved.anchorNorth))
        assertClose(0.0, resolved.anchorEast.dot(resolved.anchorUp))
        assertClose(0.0, resolved.anchorNorth.dot(resolved.anchorUp))
        assertVectorClose(resolved.anchorUp, resolved.anchorEast.cross(resolved.anchorNorth))
    }

    @Test
    fun effectiveZoomAndRadiusScaleTheGlobeByTheInverseCosineOfLatitude() {
        val equatorial = resolve(camera(latitude = 0.0, zoom = 4.0))
        val sixty = resolve(camera(latitude = 60.0, zoom = 4.0))
        val mercator = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(camera(latitude = 60.0, zoom = 4.0), OUTPUT),
        ).value

        assertClose(4.0, equatorial.effectiveZoom)
        assertClose(8192.0, equatorial.worldSizeLogicalPixels, tolerance = 1e-9)
        assertClose(5.0, sixty.effectiveZoom, tolerance = 1e-12)
        assertClose(2.0 * mercator.worldSizeLogicalPixels, sixty.worldSizeLogicalPixels, 1e-9)
        assertClose(
            sixty.worldSizeLogicalPixels / (2.0 * PI),
            sixty.radiusLogicalPixels,
            tolerance = 1e-9,
        )
        assertClose(
            4.0 - log2(cos(48.8566 * PI / 180.0)),
            resolveTilted().effectiveZoom,
            tolerance = 1e-12,
        )
    }

    @Test
    fun theGroundAnchorSitsAtTheOriginOfTheCameraFrameAndTheCentreOfTheScreen() {
        val resolved = resolveTilted()
        val anchorRelative = globeCameraRelativePosition(resolved, resolved.geographicGroundAnchor)
        val projected = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(resolved, resolved.geographicGroundAnchor),
        )

        assertVectorClose(DoubleVector3(0.0, 0.0, 0.0), anchorRelative, tolerance = 1e-9)
        assertClose(480.0, projected.pixelX, tolerance = 1e-8)
        assertClose(270.0, projected.pixelY, tolerance = 1e-8)
        assertClose(resolved.cameraDistanceLogicalPixels, projected.w, tolerance = 1e-9)
    }

    @Test
    fun aMeridianOffsetLandsOnTheAnalyticArcAndItsCurvatureDrop() {
        val resolved = resolveTilted()
        val arcLogicalPixels = 270.0
        val centralAngle = arcLogicalPixels / resolved.radiusLogicalPixels
        val northward = GeographicPosition(
            latitude = TILTED_LATITUDE + centralAngle * 180.0 / PI,
            unwrappedLongitude = TILTED_LONGITUDE,
            altitudeMetres = 0.0,
        )

        val relative = globeCameraRelativePosition(resolved, northward)

        assertClose(0.0, relative.x, tolerance = 1e-9)
        assertClose(resolved.radiusLogicalPixels * sin(centralAngle), relative.y, tolerance = 1e-9)
        assertClose(
            resolved.radiusLogicalPixels * (cos(centralAngle) - 1.0),
            relative.z,
            tolerance = 1e-9,
        )
        // A globe that is secretly a tangent plane returns z == 0 here. At zoom 4 the drop across
        // half a frame is 18 logical pixels, which is why the cross-mode case has to be taken at a
        // low zoom: by zoom 12 it is under a thousandth of a pixel and any comparison passes.
        assertTrue(relative.z < -18.0 && relative.z > -19.0, "curvature drop was ${relative.z}")
    }

    @Test
    fun everyProbePixelRoundTripsFromItsGroundHitBackToItsOwnCentre() {
        val resolved = resolveTilted()
        var hits = 0
        var misses = 0
        var roundTrips = 0
        var worstPixelError = 0.0
        var worstDepthError = 0.0

        for (pixelY in PROBE_ROWS) {
            for (pixelX in PROBE_COLUMNS) {
                when (val ray = physicalPixelGlobeRay(resolved, pixelX, pixelY)) {
                    GlobeRayResult.BeyondLimb -> misses += 1
                    GlobeRayResult.NearClipped -> misses += 1
                    is GlobeRayResult.Hit -> {
                        hits += 1
                        if (!isWithinMercatorPlanningSupport(ray.point.x, ray.point.y)) continue
                        val projected = assertIs<ScreenProjection.Projected>(
                            projectCameraRelativeLogicalPosition(
                                resolved,
                                globeCameraRelativePosition(
                                    camera = resolved,
                                    mercatorX = ray.point.x,
                                    mercatorY = ray.point.y,
                                    altitudeMetres = 0.0,
                                ),
                            ),
                        )
                        roundTrips += 1
                        worstPixelError = maxOf(
                            worstPixelError,
                            abs(projected.pixelX - (pixelX + 0.5)),
                            abs(projected.pixelY - (pixelY + 0.5)),
                        )
                        worstDepthError =
                            maxOf(worstDepthError, abs(projected.w - ray.t) / ray.t)
                    }
                }
            }
        }

        assertTrue(worstPixelError <= 1e-6, "worst round-trip pixel error was $worstPixelError")
        assertTrue(worstDepthError <= 1e-12, "worst round-trip depth error was $worstDepthError")
        assertTrue(hits >= 8, "only $hits probe pixels found ground")
        assertTrue(misses >= 4, "only $misses probe pixels passed the limb")
        assertTrue(roundTrips >= 8, "only $roundTrips hits were inside the Mercator domain")
    }

    @Test
    fun groundHitsKeepTheCameraOwnWorldCopyAndCrossTheAntimeridianContinuously() {
        val wound = resolveTilted()
        val unwound = resolve(
            camera(
                latitude = TILTED_LATITUDE,
                unwrappedLongitude = TILTED_LONGITUDE - 720.0,
                zoom = 4.0,
                bearing = 37.0,
                pitch = 52.0,
            ),
        )
        val here = assertIs<GlobeRayResult.Hit>(physicalPixelGlobeRay(wound, 480, 400))
        val there = assertIs<GlobeRayResult.Hit>(physicalPixelGlobeRay(unwound, 480, 400))

        // The round trip cannot see this: everything downstream wraps the Mercator x, so a hit
        // reported in the wrong world copy projects back to the same pixel. Tile selection is what
        // reads it, and tiles are chosen in the camera's own copy.
        assertClose(2.0, here.point.x - there.point.x, tolerance = 1e-9)
        assertClose(there.point.y, here.point.y, tolerance = 1e-12)
        assertTrue(
            abs(here.point.x - wound.mercatorAnchor.x) < 0.25,
            "the hit left the camera's world copy at ${here.point.x}",
        )

        val straddling = resolve(camera(unwrappedLongitude = 179.97, zoom = 6.0))
        val across = listOf(0, 240, 480, 720, 959).map {
            assertIs<GlobeRayResult.Hit>(physicalPixelGlobeRay(straddling, it, 270)).point.x
        }

        assertTrue(across.first() < 1.0, "the west edge should sit before the antimeridian: $across")
        assertTrue(across.last() > 1.0, "the east edge should sit past the antimeridian: $across")
        across.zipWithNext().forEach { (west, east) ->
            assertTrue(east > west && east - west < 0.05, "the seam is not continuous: $across")
        }
    }

    @Test
    fun theEyeIsTheCameraItselfWrittenInGlobeFixedCoordinates() {
        val resolved = resolveTilted()
        val radius = resolved.radiusLogicalPixels
        val distance = resolved.cameraDistanceLogicalPixels

        assertVectorClose(
            resolved.cameraBack * distance,
            cameraRelativeLogicalPosition(resolved, resolved.eyeGlobeFixed),
            tolerance = 1e-8,
        )
        assertClose(
            sqrt(distance * distance + 2.0 * radius * distance * resolved.cameraBack.z +
                radius * radius),
            sqrt(resolved.eyeGlobeFixed.dot(resolved.eyeGlobeFixed)),
            tolerance = 1e-8,
        )
        assertTrue(
            sqrt(resolved.eyeGlobeFixed.dot(resolved.eyeGlobeFixed)) > radius,
            "the camera must sit outside the sphere it orbits",
        )
    }

    @Test
    fun bearingRotatesTheScreenUnderAFixedWorldRatherThanTheWorldUnderTheScreen() {
        val resolved = resolve(
            camera(
                latitude = TILTED_LATITUDE,
                unwrappedLongitude = TILTED_LONGITUDE,
                zoom = 6.0,
                bearing = 90.0,
                pitch = 0.0,
            ),
        )
        val east = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(
                resolved,
                GeographicPosition(TILTED_LATITUDE, TILTED_LONGITUDE + 0.5, 0.0),
            ),
        )
        val north = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(
                resolved,
                GeographicPosition(TILTED_LATITUDE + 0.5, TILTED_LONGITUDE, 0.0),
            ),
        )

        assertTrue(east.pixelY < 240.0, "east should sit above centre at bearing 90, was ${east.pixelY}")
        assertTrue(abs(east.pixelX - 480.0) < 5.0, "east drifted sideways to ${east.pixelX}")
        assertTrue(north.pixelX < 440.0, "north should sit left of centre at bearing 90, was ${north.pixelX}")
        assertTrue(abs(north.pixelY - 270.0) < 5.0, "north drifted vertically to ${north.pixelY}")
    }

    @Test
    fun theAntipodeKeepsALargePositiveDepthDivisorRatherThanFailingTheNearPlane() {
        val resolved = resolveTilted()
        val antipode = GeographicPosition(
            latitude = -TILTED_LATITUDE,
            unwrappedLongitude = TILTED_LONGITUDE - 180.0,
            altitudeMetres = 0.0,
        )

        val relative = globeCameraRelativePosition(resolved, antipode)
        val projected = assertIs<ScreenProjection.Projected>(
            projectGeographicPosition(resolved, antipode),
        )

        assertVectorClose(
            DoubleVector3(0.0, 0.0, -2.0 * resolved.radiusLogicalPixels),
            relative,
            tolerance = 1e-8,
        )
        // The correction Cycle G's own documents needed twice: `w` is the distance in front of the
        // camera *plane*, not a horizon test. A camera outside the sphere has the whole planet in
        // front of it, so the far side is Projected with a large positive `w` and culling it is a
        // limb-plane dot product asked somewhere else.
        assertClose(
            resolved.cameraDistanceLogicalPixels +
                2.0 * resolved.radiusLogicalPixels * resolved.cameraBack.z,
            projected.w,
            tolerance = 1e-8,
        )
        assertTrue(
            projected.w > resolved.radiusLogicalPixels,
            "the far side must stay well in front of the camera plane, was ${projected.w}",
        )
    }

    @Test
    fun theWindingCountOfUnwrappedLongitudeIsInertOnASphere() {
        val near = resolve(camera(latitude = TILTED_LATITUDE, unwrappedLongitude = 2.3522))
        val wound = resolve(camera(latitude = TILTED_LATITUDE, unwrappedLongitude = 2.3522 + 720.0))
        val place = GeographicPosition(12.0, 77.6, 0.0)

        assertNotEquals(
            near.geographicGroundAnchor.unwrappedLongitude,
            wound.geographicGroundAnchor.unwrappedLongitude,
        )
        assertVectorClose(near.anchorEast, wound.anchorEast)
        assertVectorClose(near.anchorNorth, wound.anchorNorth)
        assertVectorClose(near.anchorUp, wound.anchorUp)
        assertClose(near.radiusLogicalPixels, wound.radiusLogicalPixels, tolerance = 1e-9)
        assertVectorClose(
            globeCameraRelativePosition(near, place),
            globeCameraRelativePosition(wound, place),
            tolerance = 1e-8,
        )
    }

    @Test
    fun theUploadedFrameTransformAgreesWithTheCameraRelativeConversion() {
        val resolved = resolveTilted()
        val samples = listOf(
            resolved.anchorUp * resolved.radiusLogicalPixels,
            DoubleVector3(resolved.radiusLogicalPixels, 0.0, 0.0),
            DoubleVector3(-317.0, 4211.0, -1900.0),
        )

        samples.forEach { globeFixed ->
            val matrix = resolved.globeFixedToCameraRelative
            val expected = DoubleVector3(
                x = matrix[0, 0] * globeFixed.x + matrix[0, 1] * globeFixed.y +
                    matrix[0, 2] * globeFixed.z + matrix[0, 3],
                y = matrix[1, 0] * globeFixed.x + matrix[1, 1] * globeFixed.y +
                    matrix[1, 2] * globeFixed.z + matrix[1, 3],
                z = matrix[2, 0] * globeFixed.x + matrix[2, 1] * globeFixed.y +
                    matrix[2, 2] * globeFixed.z + matrix[2, 3],
            )
            assertVectorClose(expected, cameraRelativeLogicalPosition(resolved, globeFixed), 1e-9)
            assertClose(1.0, matrix[3, 3])
            assertClose(0.0, matrix[3, 0])
            assertClose(0.0, matrix[3, 1])
            assertClose(0.0, matrix[3, 2])
        }
    }

    @Test
    fun theGlobeFixedViewProjectionComposesInTheOrderTheDrawPathNeeds() {
        val resolved = resolveTilted()
        val globeFixed = projectGlobe(
            GeographicPosition(50.1, TILTED_LONGITUDE + 0.4, 1200.0),
            resolved.radiusLogicalPixels,
        )
        val composed = globeFixedViewProjection(resolved)
        val stepwise = assertIs<ScreenProjection.Projected>(
            projectCameraRelativeLogicalPosition(
                resolved,
                cameraRelativeLogicalPosition(resolved, globeFixed),
            ),
        )
        val w = homogeneousRow(composed, 3, globeFixed)
        val pixelX = (homogeneousRow(composed, 0, globeFixed) / w + 1.0) * 0.5 * 960.0
        val pixelY = (1.0 - homogeneousRow(composed, 1, globeFixed) / w) * 0.5 * 540.0

        assertClose(stepwise.w, w, tolerance = 1e-8)
        assertClose(stepwise.pixelX, pixelX, tolerance = 1e-8)
        assertClose(stepwise.pixelY, pixelY, tolerance = 1e-8)
    }

    @Test
    fun theLimbBoundsWhichPixelsFindGroundInBothMissRegimes() {
        val small = resolve(camera(zoom = 0.0))

        assertIs<GlobeRayResult.Hit>(physicalPixelGlobeRay(small, 480, 270))
        assertEquals(GlobeRayResult.BeyondLimb, physicalPixelGlobeRay(small, 480, 60))
        assertEquals(GlobeRayResult.BeyondLimb, physicalPixelGlobeRay(small, 0, 0))

        val grazing = resolve(camera(zoom = 20.0, pitch = 80.0))

        assertEquals(GlobeRayResult.BeyondLimb, physicalPixelGlobeRay(grazing, 480, 0))
        assertIs<GlobeRayResult.Hit>(physicalPixelGlobeRay(grazing, 480, 539))
    }

    @Test
    fun theNearPlaneBoundaryIsClosedAndTheSurfaceInsideItIsRefused() {
        val onBoundary = syntheticCamera(cameraDistanceLogicalPixels = 1.0)
        val insideBoundary = syntheticCamera(cameraDistanceLogicalPixels = 0.5)

        assertClose(1.0, assertIs<GlobeRayResult.Hit>(physicalPixelGlobeRay(onBoundary, 0, 0)).t)
        assertEquals(GlobeRayResult.NearClipped, physicalPixelGlobeRay(insideBoundary, 0, 0))
    }

    @Test
    fun rayIndicesAreCheckedAgainstTheResolvedOutputSize() {
        val resolved = resolveTilted()

        assertFailsWith<IllegalArgumentException> { physicalPixelGlobeRay(resolved, -1, 0) }
        assertFailsWith<IllegalArgumentException> { physicalPixelGlobeRay(resolved, 960, 0) }
        assertFailsWith<IllegalArgumentException> { physicalPixelGlobeRay(resolved, 0, -1) }
        assertFailsWith<IllegalArgumentException> { physicalPixelGlobeRay(resolved, 0, 540) }
    }

    @Test
    fun cameraResolutionPreservesMercatorLatitudeThenWorldCopyFailureMapping() {
        val invalidLatitude = resolveGlobeCamera(
            camera = camera(latitude = MERCATOR_MAXIMUM_LATITUDE_DEGREES + 1.0),
            outputPixelSize = OUTPUT,
        )
        val invalidCopy = resolveGlobeCamera(
            camera = camera(unwrappedLongitude = 16385.0 * 360.0 - 180.0),
            outputPixelSize = OUTPUT,
        )

        assertSpatialFailure(invalidLatitude, "camera.latitude")
        assertSpatialFailure(invalidCopy, "camera.unwrappedLongitude")
    }

    @Test
    fun aPositionOutsideTheMercatorDomainStillHasNoPixelOnAGlobe() {
        val resolved = resolveTilted()

        assertEquals(
            ScreenProjection.OutsideSupportedDomain,
            projectGeographicPosition(resolved, GeographicPosition(89.0, 0.0, 0.0)),
        )
    }

    @Test
    fun theViewportHalfOfScreenProjectionIsSharedByBothProjectionModes() {
        val plan = camera(
            latitude = TILTED_LATITUDE,
            unwrappedLongitude = TILTED_LONGITUDE,
            zoom = 4.0,
            bearing = 37.0,
            pitch = 52.0,
        )
        val globe = resolve(plan)
        val mercator = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(plan, OUTPUT),
        ).value
        val ahead = DoubleVector3(133.0, -207.0, 44.0)
        val behind = DoubleVector3(0.0, 0.0, 4000.0)

        assertEquals(globe.viewMatrix, mercator.viewMatrix)
        assertEquals(globe.projectionMatrix, mercator.projectionMatrix)
        assertEquals(
            projectCameraRelativeLogicalPosition(mercator, ahead),
            projectCameraRelativeLogicalPosition(globe, ahead),
        )
        assertIs<ScreenProjection.BehindNearPlane>(
            projectCameraRelativeLogicalPosition(globe, behind),
        )
        assertEquals(
            projectCameraRelativeLogicalPosition(mercator, behind),
            projectCameraRelativeLogicalPosition(globe, behind),
        )
    }

    private fun resolveTilted(): ResolvedGlobeCamera = resolve(
        camera(
            latitude = TILTED_LATITUDE,
            unwrappedLongitude = TILTED_LONGITUDE,
            zoom = 4.0,
            bearing = 37.0,
            pitch = 52.0,
        ),
    )

    private fun resolve(
        camera: Camera,
        outputPixelSize: OutputPixelSize = OUTPUT,
    ): ResolvedGlobeCamera = assertIs<SpatialOutcome.Success<ResolvedGlobeCamera>>(
        resolveGlobeCamera(camera, outputPixelSize),
    ).value

    private fun camera(
        latitude: Double = 0.0,
        unwrappedLongitude: Double = 0.0,
        zoom: Double = 0.0,
        bearing: Double = 0.0,
        pitch: Double = 0.0,
    ): Camera = Camera(latitude, unwrappedLongitude, zoom, bearing, pitch)

    /** A one-by-one camera looking straight down a sphere of radius 512, so the single pixel's ray
     * is the view axis and its hit is at exactly [ResolvedGlobeCamera.cameraDistanceLogicalPixels]
     * -- which is what makes the near-plane boundary reachable at all. */
    private fun syntheticCamera(cameraDistanceLogicalPixels: Double): ResolvedGlobeCamera {
        val resolved = resolve(camera(), OutputPixelSize(width = 1, height = 1))
        return resolved.copy(
            radiusLogicalPixels = 512.0,
            cameraDistanceLogicalPixels = cameraDistanceLogicalPixels,
        )
    }

    private fun homogeneousRow(matrix: DoubleMatrix4, row: Int, point: DoubleVector3): Double =
        matrix[row, 0] * point.x + matrix[row, 1] * point.y + matrix[row, 2] * point.z +
            matrix[row, 3]

    private fun assertSpatialFailure(outcome: SpatialOutcome<*>, fieldName: String) {
        val failure = assertIs<SpatialOutcome.Failure>(outcome).failure
        assertEquals(RenGErrorCode.INVALID_VALUE, failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.stage)
        assertEquals(fieldName, requireNotNull(failure.diagnostic).fieldName)
    }

    private fun assertVectorClose(
        expected: DoubleVector3,
        actual: DoubleVector3,
        tolerance: Double = 1e-12,
    ) {
        assertClose(expected.x, actual.x, tolerance)
        assertClose(expected.y, actual.y, tolerance)
        assertClose(expected.z, actual.z, tolerance)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-12) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")
    }

    private companion object {
        const val TILTED_LATITUDE: Double = 48.8566
        const val TILTED_LONGITUDE: Double = 722.3522
        val OUTPUT: OutputPixelSize = OutputPixelSize(width = 960, height = 540)
        val PROBE_ROWS: List<Int> = listOf(0, 50, 130, 250, 380, 539)
        val PROBE_COLUMNS: List<Int> = listOf(0, 137, 480, 823, 959)
    }
}
