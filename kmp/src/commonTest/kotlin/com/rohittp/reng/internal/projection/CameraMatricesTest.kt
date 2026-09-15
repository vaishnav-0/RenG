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
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CameraMatricesTest {
    @Test
    fun resolutionRetainsExactCameraGroundAnchorAtHighLatitudeAndNonzeroWorldCopy() {
        val latitude = 84.98765432101234
        val unwrappedLongitude = 444363.4567890123
        val resolved = resolve(
            camera = camera(
                latitude = latitude,
                unwrappedLongitude = unwrappedLongitude,
            ),
        )

        assertEquals(latitude.toBits(), resolved.geographicGroundAnchor.latitude.toBits())
        assertEquals(
            unwrappedLongitude.toBits(),
            resolved.geographicGroundAnchor.unwrappedLongitude.toBits(),
        )
        assertEquals(0.0.toBits(), resolved.geographicGroundAnchor.altitudeMetres.toBits())
    }

    @Test
    fun zeroBearingAndPitchResolveNorthUpStraightDownView() {
        val outputSize = OutputPixelSize(width = 640, height = 480)
        val resolved = resolve(camera = camera(bearing = 0.0, pitch = 0.0), outputPixelSize = outputSize)
        val expectedDistance = 240.0 * (1.0 + sqrt(2.0))

        assertEquals(outputSize, resolved.outputPixelSize)
        assertEquals(MercatorPosition(0.5, 0.5, 0.0), resolved.mercatorAnchor)
        assertEquals(512.0, resolved.worldSizeLogicalPixels)
        assertVectorClose(DoubleVector3(1.0, 0.0, 0.0), resolved.right)
        assertVectorClose(DoubleVector3(0.0, 1.0, 0.0), resolved.cameraUp)
        assertVectorClose(DoubleVector3(0.0, 0.0, 1.0), resolved.cameraBack)
        assertClose(expectedDistance, resolved.cameraDistanceLogicalPixels)
        assertMatrixClose(
            listOf(
                listOf(1.0, 0.0, 0.0, 0.0),
                listOf(0.0, 1.0, 0.0, 0.0),
                listOf(0.0, 0.0, 1.0, -expectedDistance),
                listOf(0.0, 0.0, 0.0, 1.0),
            ),
            resolved.viewMatrix,
        )
    }

    @Test
    fun ninetyDegreeBearingAndNearHorizonPitchUseExactRightHandedBasis() {
        val eastAtTop = resolve(camera = camera(bearing = 90.0, pitch = 0.0))

        assertVectorClose(DoubleVector3(0.0, -1.0, 0.0), eastAtTop.right)
        assertVectorClose(DoubleVector3(1.0, 0.0, 0.0), eastAtTop.cameraUp)
        assertVectorClose(DoubleVector3(0.0, 0.0, 1.0), eastAtTop.cameraBack)
        assertVectorClose(eastAtTop.cameraBack, eastAtTop.right.cross(eastAtTop.cameraUp))

        val nearHorizon = resolve(camera = camera(bearing = 0.0, pitch = 89.0))
        assertVectorClose(
            DoubleVector3(0.0, 0.0174524064372836, 0.9998476951563913),
            nearHorizon.cameraUp,
        )
        assertVectorClose(
            DoubleVector3(0.0, -0.9998476951563913, 0.0174524064372836),
            nearHorizon.cameraBack,
        )
        assertVectorClose(nearHorizon.cameraBack, nearHorizon.right.cross(nearHorizon.cameraUp))
    }

    @Test
    fun nontrivialBearingAndPitchKeepViewBasisInMathematicalRows() {
        val resolved = resolve(
            camera = camera(bearing = 30.0, pitch = 60.0),
            outputPixelSize = OutputPixelSize(width = 7, height = 4),
        )

        assertMatrixClose(
            listOf(
                listOf(0.8660254037844386, -0.5, 0.0, 0.0),
                listOf(0.25, 0.4330127018922193, 0.8660254037844386, 0.0),
                listOf(-0.4330127018922193, -0.75, 0.5, -4.82842712474619),
                listOf(0.0, 0.0, 0.0, 1.0),
            ),
            resolved.viewMatrix,
        )
    }

    @Test
    fun nonSquareProjectionUsesFortyFiveDegreeVerticalFovAndInfiniteFarReverseZ() {
        val resolved = resolve(
            camera = camera(),
            outputPixelSize = OutputPixelSize(width = 800, height = 400),
        )
        val f = 1.0 + sqrt(2.0)

        assertMatrixClose(
            listOf(
                listOf(f / 2.0, 0.0, 0.0, 0.0),
                listOf(0.0, f, 0.0, 0.0),
                listOf(0.0, 0.0, 1.0, 2.0),
                listOf(0.0, 0.0, -1.0, 0.0),
            ),
            resolved.projectionMatrix,
        )
        assertClose(1.0, windowDepthAtViewDepth(resolved.projectionMatrix, 1.0))
        assertClose(0.5, windowDepthAtViewDepth(resolved.projectionMatrix, 2.0))
        assertClose(0.1, windowDepthAtViewDepth(resolved.projectionMatrix, 10.0))
    }

    @Test
    fun raysUsePhysicalHalfIntegerPixelCentresAndReturnFiniteGroundHits() {
        val resolved = resolve(
            camera = camera(),
            outputPixelSize = OutputPixelSize(width = 2, height = 2),
        )
        val topLeft = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(resolved, 0, 0))
        val bottomRight = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(resolved, 1, 1))
        val f = 1.0 + sqrt(2.0)

        assertClose(1.0, topLeft.q)
        assertClose(f, topLeft.t)
        assertClose(0.4990234375, topLeft.point.x)
        assertClose(0.4990234375, topLeft.point.y)
        assertClose(1.0, bottomRight.q)
        assertClose(f, bottomRight.t)
        assertClose(0.5009765625, bottomRight.point.x)
        assertClose(0.5009765625, bottomRight.point.y)
        assertTrue(topLeft.point.x.isFinite())
        assertTrue(topLeft.point.y.isFinite())
        assertTrue(bottomRight.point.x.isFinite())
        assertTrue(bottomRight.point.y.isFinite())
    }

    @Test
    fun ninetyDegreeBearingRotatesAsymmetricRayIntoEastAndNorth() {
        val resolved = resolve(
            camera = camera(bearing = 90.0),
            outputPixelSize = OutputPixelSize(width = 4, height = 2),
        )
        val hit = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(resolved, 3, 0))

        assertClose(1.0, hit.q)
        assertClose(2.414213562373095, hit.t)
        assertClose(0.5009765625, hit.point.x)
        assertClose(0.5029296875, hit.point.y)
    }

    @Test
    fun fractionalZoomScalesRayHitByTheResolvedLogicalWorldSize() {
        val resolved = resolve(
            camera = camera(zoom = 1.5),
            outputPixelSize = OutputPixelSize(width = 4, height = 2),
        )
        val hit = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(resolved, 3, 0))

        assertClose(1448.1546878700494, resolved.worldSizeLogicalPixels)
        assertClose(0.5010358009490037, hit.point.x)
        assertClose(0.49965473301699875, hit.point.y)
    }

    @Test
    fun nonzeroWorldCopyRayHitRetainsUnwrappedMercatorX() {
        val resolved = resolve(
            camera = camera(unwrappedLongitude = 360.0),
            outputPixelSize = OutputPixelSize(width = 2, height = 2),
        )
        val hit = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(resolved, 0, 0))

        assertClose(1.5, resolved.mercatorAnchor.x)
        assertClose(1.4990234375, hit.point.x)
        assertClose(0.4990234375, hit.point.y)
    }

    @Test
    fun exactHorizonQZeroAndNearBoundaryTOneUseClosedClassifications() {
        val f = 1.0 + sqrt(2.0)
        val topCentreV = 0.5 / f
        val exactHorizon = syntheticCamera(
            outputPixelSize = OutputPixelSize(width = 1, height = 2),
            cameraUp = DoubleVector3(0.0, 0.0, 1.0),
            cameraBack = DoubleVector3(0.0, 0.0, topCentreV),
            cameraDistanceLogicalPixels = 1.0,
        )

        assertEquals(GroundRayResult.HorizonOrSky, physicalPixelGroundRay(exactHorizon, 0, 0))

        val nearBoundary = syntheticCamera(
            outputPixelSize = OutputPixelSize(width = 1, height = 1),
            cameraUp = DoubleVector3(0.0, 1.0, 0.0),
            cameraBack = DoubleVector3(0.0, 0.0, 1.0),
            cameraDistanceLogicalPixels = 1.0,
        )
        val hit = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(nearBoundary, 0, 0))

        assertEquals(1.0, hit.q)
        assertEquals(1.0, hit.t)
        assertEquals(MercatorGroundPoint(0.5, 0.5), hit.point)
    }

    @Test
    fun highPitchRaysClassifySkyGroundAndNearClipWithoutAThresholdFallback() {
        val pitched = resolve(
            camera = camera(pitch = 80.0),
            outputPixelSize = OutputPixelSize(width = 1, height = 1000),
        )

        assertEquals(GroundRayResult.HorizonOrSky, physicalPixelGroundRay(pitched, 0, 0))
        val finiteHit = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(pitched, 0, 999))
        assertTrue(finiteHit.q > 0.0)
        assertTrue(finiteHit.t >= 1.0)
        assertTrue(finiteHit.point.x.isFinite())
        assertTrue(finiteHit.point.y.isFinite())

        val almostHorizontal = resolve(
            camera = camera(pitch = 89.9999),
            outputPixelSize = OutputPixelSize(width = 1, height = 2),
        )
        assertEquals(GroundRayResult.NearClipped, physicalPixelGroundRay(almostHorizontal, 0, 1))
    }

    @Test
    fun rayIndicesAreCheckedAgainstTheResolvedOutputSize() {
        val resolved = resolve(
            camera = camera(),
            outputPixelSize = OutputPixelSize(width = 3, height = 2),
        )

        assertFailsWith<IllegalArgumentException> { physicalPixelGroundRay(resolved, -1, 0) }
        assertFailsWith<IllegalArgumentException> { physicalPixelGroundRay(resolved, 3, 0) }
        assertFailsWith<IllegalArgumentException> { physicalPixelGroundRay(resolved, 0, -1) }
        assertFailsWith<IllegalArgumentException> { physicalPixelGroundRay(resolved, 0, 2) }
    }

    @Test
    fun cameraResolutionPreservesMercatorLatitudeThenWorldCopyFailureMapping() {
        val invalidLatitude = resolveMercatorCamera(
            camera = camera(latitude = MERCATOR_MAXIMUM_LATITUDE_DEGREES + 1.0),
            outputPixelSize = OutputPixelSize(1, 1),
        )
        val invalidCopy = resolveMercatorCamera(
            camera = camera(unwrappedLongitude = 16385.0 * 360.0 - 180.0),
            outputPixelSize = OutputPixelSize(1, 1),
        )

        assertSpatialFailure(invalidLatitude, "camera.latitude")
        assertSpatialFailure(invalidCopy, "camera.unwrappedLongitude")
    }

    /**
     * ADR 0064. Between the last row the horizon rejects and the first row that carries usable
     * ground there is now a band the angle bound rejects instead, and the row indices here are the
     * whole claim: without the bound row 287 is an ordinary `Hit` whose ground point is further
     * from the anchor than the Mercator support box, which is how a steep frame used to reach tile
     * selection as thousands of world copies.
     */
    @Test
    fun aRayNearerTheHorizonThanTheMaximumGroundAngleIsRejectedRatherThanReturningAnEnormousHit() {
        val pitched = resolve(
            camera = camera(pitch = 80.0),
            outputPixelSize = OutputPixelSize(width = 1, height = 1000),
        )

        assertEquals(GroundRayResult.HorizonOrSky, physicalPixelGroundRay(pitched, 0, 286))
        assertEquals(GroundRayResult.BeyondHorizonAngle, physicalPixelGroundRay(pitched, 0, 287))
        assertEquals(GroundRayResult.BeyondHorizonAngle, physicalPixelGroundRay(pitched, 0, 302))

        val firstAdmissible = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(pitched, 0, 303))
        assertEquals(15718.536094, firstAdmissible.t, absoluteTolerance = 1e-6)

        // And the bound is generous where it matters: the deepest ray it still admits reaches almost
        // 75 times the camera's own height above the ground. The far plane this ADR declines to adopt
        // cut at 1.5 of those, which is the difference between losing 1.6% of the frame and half of it.
        val cameraHeight = pitched.cameraDistanceLogicalPixels * pitched.cameraBack.z
        assertTrue(firstAdmissible.t > 70.0 * cameraHeight)
    }

    /**
     * ADR 0064 states the angle comparison is closed, and a synthetic basis is the only way to
     * observe that: a real pixel centre never lands exactly on the bound, because `eta` is
     * `1 - 2 * (row + 0.5) / height` and so is strictly inside `(-1, 1)`. This camera puts the single
     * pixel's `v` at zero and makes `sinePitch` exactly the tangent the bound compares against, so
     * both sides of the inequality are the same `Double` and only its sense decides the answer --
     * the same construction, and the same purpose, as
     * [exactHorizonQZeroAndNearBoundaryTOneUseClosedClassifications].
     */
    @Test
    fun aRayExactlyAtTheMaximumGroundAngleIsAdmittedBecauseTheBoundIsClosed() {
        val exactlyAtTheBound = syntheticCamera(
            outputPixelSize = OutputPixelSize(width = 1, height = 1),
            cameraUp = DoubleVector3(0.0, 0.0, tan(MAXIMUM_GROUND_ANGLE_DEGREES.degreesToRadians())),
            cameraBack = DoubleVector3(0.0, 0.0, 1.0),
            cameraDistanceLogicalPixels = 1.0,
        )

        val hit = assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(exactlyAtTheBound, 0, 0))
        assertEquals(1.0, hit.q)
        assertEquals(1.0, hit.t)
    }

    /**
     * ADR 0064. The bound bites at `MAXIMUM_GROUND_ANGLE_DEGREES` less the 22.5 degree vertical half
     * field of view, because `theta = pitch + atan(v)` exactly and the top row's `v` is `1 /
     * FOCAL_LENGTH_SCALE`. That identity is what keeps the bound invisible below the ceiling
     * `Camera.MAXIMUM_GROUND_FILLING_PITCH_DEGREES` publishes rather than merely small.
     */
    @Test
    fun theGroundAngleBoundFirstBitesAtTheMaximumAngleLessTheHalfFieldOfView() {
        val firstBite = MAXIMUM_GROUND_ANGLE_DEGREES - HALF_VERTICAL_FIELD_OF_VIEW_DEGREES
        assertEquals(66.75, firstBite, absoluteTolerance = 1e-12)

        assertEquals(0, rowsBeyondTheGroundAngle(pitch = firstBite))
        assertTrue(rowsBeyondTheGroundAngle(pitch = firstBite + 0.1) > 0)
    }

    /**
     * `MercatorGroundFootprint.groundHit` hard-casts a corner ray to `Hit` on the strength of column
     * zero having been one, so every rejection on this path must depend on the row alone. The angle
     * bound is a function of `v`, which is a function of the row, and this is that invariant stated
     * where a future bound written against `u` would break it.
     */
    @Test
    fun theGroundAngleBoundDependsOnTheRowAloneSoEveryColumnInARowAgrees() {
        val wide = resolve(
            camera = camera(pitch = 67.4, bearing = 37.0),
            outputPixelSize = OutputPixelSize(width = 64, height = 400),
        )

        var sawRejection = false
        for (pixelY in 0 until wide.outputPixelSize.height) {
            val reference = physicalPixelGroundRay(wide, 0, pixelY)
            if (reference is GroundRayResult.BeyondHorizonAngle) sawRejection = true
            for (pixelX in 1 until wide.outputPixelSize.width) {
                val other = physicalPixelGroundRay(wide, pixelX, pixelY)
                assertEquals(
                    reference is GroundRayResult.BeyondHorizonAngle,
                    other is GroundRayResult.BeyondHorizonAngle,
                )
            }
        }
        assertTrue(sawRejection)
    }

    private fun rowsBeyondTheGroundAngle(pitch: Double): Int {
        val resolved = resolve(
            camera = camera(pitch = pitch),
            outputPixelSize = OutputPixelSize(width = 1, height = 1920),
        )
        return (0 until 1920).count {
            physicalPixelGroundRay(resolved, 0, it) is GroundRayResult.BeyondHorizonAngle
        }
    }

    private fun resolve(
        camera: Camera,
        outputPixelSize: OutputPixelSize = OutputPixelSize(width = 1, height = 1),
    ): ResolvedMercatorCamera =
        assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(camera, outputPixelSize),
        ).value

    private fun camera(
        latitude: Double = 0.0,
        unwrappedLongitude: Double = 0.0,
        zoom: Double = 0.0,
        bearing: Double = 0.0,
        pitch: Double = 0.0,
    ): Camera = Camera(latitude, unwrappedLongitude, zoom, bearing, pitch)

    /** The 22.5 degrees `FOCAL_LENGTH_SCALE` encodes, derived here rather than written as a literal. */
    private val HALF_VERTICAL_FIELD_OF_VIEW_DEGREES: Double =
        atan(1.0 / FOCAL_LENGTH_SCALE) * 180.0 / PI

    private fun syntheticCamera(
        outputPixelSize: OutputPixelSize,
        cameraUp: DoubleVector3,
        cameraBack: DoubleVector3,
        cameraDistanceLogicalPixels: Double,
    ): ResolvedMercatorCamera = ResolvedMercatorCamera(
        outputPixelSize = outputPixelSize,
        mercatorAnchor = MercatorPosition(0.5, 0.5, 0.0),
        worldSizeLogicalPixels = 512.0,
        right = DoubleVector3(1.0, 0.0, 0.0),
        cameraUp = cameraUp,
        cameraBack = cameraBack,
        cameraDistanceLogicalPixels = cameraDistanceLogicalPixels,
        viewMatrix = DoubleMatrix4.identity,
        projectionMatrix = DoubleMatrix4.identity,
        geographicGroundAnchor = GeographicPosition(0.0, 0.0, 0.0),
    )

    private fun windowDepthAtViewDepth(projection: DoubleMatrix4, depth: Double): Double {
        val viewZ = -depth
        val clipZ = projection[2, 2] * viewZ + projection[2, 3]
        val clipW = projection[3, 2] * viewZ + projection[3, 3]
        return (clipZ / clipW + 1.0) / 2.0
    }

    private fun assertSpatialFailure(outcome: SpatialOutcome<*>, fieldName: String) {
        val failure = assertIs<SpatialOutcome.Failure>(outcome).failure
        assertEquals(RenGErrorCode.INVALID_VALUE, failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.stage)
        assertEquals(fieldName, requireNotNull(failure.diagnostic).fieldName)
    }

    private fun assertMatrixClose(expectedRows: List<List<Double>>, actual: DoubleMatrix4) {
        expectedRows.forEachIndexed { row, expectedRow ->
            expectedRow.forEachIndexed { column, expected ->
                assertClose(expected, actual[row, column])
            }
        }
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
}
