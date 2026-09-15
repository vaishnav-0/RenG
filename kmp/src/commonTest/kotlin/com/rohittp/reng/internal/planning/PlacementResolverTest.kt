package com.rohittp.reng.internal.planning

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.Placement
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.WORLD_CIRCUMFERENCE_METRES
import com.rohittp.reng.internal.projection.projectMercator
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementResolverTest {
    @Test
    fun allEightAnchorCombinationsResolveEachPropertyIndependently() {
        val cameraLatitude = 5.0
        val cameraLongitude = 15.0
        val camera = resolvedCamera(
            latitude = cameraLatitude,
            unwrappedLongitude = cameraLongitude,
            zoom = 3.0,
            bearing = 20.0,
            pitch = 30.0,
        )
        val rotation = Vector3(10.0, -20.0, 30.0)
        val rotationMatrix = DoubleMatrix3.rotationXyzDegrees(rotation.x, rotation.y, rotation.z)
        val viewBasis = cameraViewBasis(camera)

        for (positionMode in AnchoringMode.entries) {
            for (rotationMode in AnchoringMode.entries) {
                for (scaleMode in AnchoringMode.entries) {
                    val position = when (positionMode) {
                        AnchoringMode.MAP -> Vector3(10.0, 25.0, 100.0)
                        AnchoringMode.SCREEN -> Vector3(123.25, 456.75, 7.5)
                    }
                    val placement = Placement(
                        positionMode = positionMode,
                        position = position,
                        rotationMode = rotationMode,
                        rotation = rotation,
                        scaleMode = scaleMode,
                        scale = 2.0,
                    )
                    val resolved = resolve(placement, camera)
                    val geographicAnchor = when (positionMode) {
                        AnchoringMode.MAP -> GeographicPosition(position.x, position.y, position.z)
                        AnchoringMode.SCREEN -> GeographicPosition(cameraLatitude, cameraLongitude, 0.0)
                    }

                    assertEquals(
                        if (positionMode == AnchoringMode.MAP) {
                            DrawRegime.MAP_OCCLUDED
                        } else {
                            DrawRegime.SCREEN_COMPOSITED
                        },
                        resolved.drawRegime,
                    )
                    if (positionMode == AnchoringMode.SCREEN) {
                        assertEquals(position.z, resolved.screenCompositeZ)
                        assertVectorClose(DoubleVector3(position.x, position.y, 0.0), resolved.logicalPosition)
                    } else {
                        assertNull(resolved.screenCompositeZ)
                        assertVectorClose(mapLogicalPosition(geographicAnchor, camera), resolved.logicalPosition)
                    }

                    val expectedDirection = when (rotationMode) {
                        AnchoringMode.SCREEN -> rotationMatrix
                        // Flat transport (ADR 0063): the two ENU bases this used to carry are the
                        // identity on a map whose meridians are parallel vertical lines.
                        AnchoringMode.MAP -> viewBasis * rotationMatrix
                    }
                    assertMatrixClose(expectedDirection, resolved.directionTransform)

                    val expectedScale = when (scaleMode) {
                        AnchoringMode.SCREEN -> placement.scale
                        AnchoringMode.MAP -> placement.scale * camera.worldSizeLogicalPixels /
                            (WORLD_CIRCUMFERENCE_METRES * cos(geographicAnchor.latitude * PI / 180.0))
                    }
                    assertClose(expectedScale, resolved.logicalScale)
                }
            }
        }
    }

    @Test
    fun mapPositionKeepsWorldCopyDisplacementWhileEquivalentBasesMatch() {
        val camera = resolvedCamera(
            latitude = 23.0,
            unwrappedLongitude = 45.0,
            zoom = 2.0,
            bearing = 35.0,
            pitch = 40.0,
        )
        val placement = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(23.0, 405.0, 0.0),
            rotationMode = AnchoringMode.MAP,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )
        val resolved = resolve(placement, camera)

        assertClose(camera.worldSizeLogicalPixels, resolved.logicalPosition.x)
        assertClose(0.0, resolved.logicalPosition.y)
        assertClose(0.0, resolved.logicalPosition.z)
        assertMatrixClose(cameraViewBasis(camera), resolved.directionTransform, tolerance = 1e-12)
    }

    @Test
    fun mapRotationUsesExactViewAndLocalBasisOrder() {
        val cameraLatitude = 31.0
        val cameraLongitude = -70.0
        val camera = resolvedCamera(
            latitude = cameraLatitude,
            unwrappedLongitude = cameraLongitude,
            zoom = 4.5,
            bearing = 67.0,
            pitch = 38.0,
        )
        val anchor = GeographicPosition(-12.0, 80.0, 900.0)
        val rotation = Vector3(17.0, -29.0, 43.0)
        val resolved = resolve(
            Placement(
                positionMode = AnchoringMode.MAP,
                position = Vector3(anchor.latitude, anchor.unwrappedLongitude, anchor.altitudeMetres),
                rotationMode = AnchoringMode.MAP,
                rotation = rotation,
                scaleMode = AnchoringMode.SCREEN,
                scale = 1.0,
            ),
            camera,
        )
        // **Two terms, not four** (ADR 0063). This case previously pinned
        // `viewBasis * cameraEnu^T * anchorEnu * localRotation`, which transports the rotation across
        // the sphere -- right on a globe, and wrong on a map whose meridians are parallel vertical
        // lines. The two ENU bases are gone because on a plane their product is the identity, and
        // carrying them rotated a thing by an angle the projection had already flattened away.
        //
        // The order of what remains is still pinned exactly, because it still matters: the view basis
        // is applied to the local rotation, not the other way round.
        val expected = cameraViewBasis(camera) *
            DoubleMatrix3.rotationXyzDegrees(rotation.x, rotation.y, rotation.z)

        assertMatrixBitsEqual(expected, resolved.directionTransform)
        assertTrue(
            matrixMaximumDifference(cameraViewBasis(camera), resolved.directionTransform) > 1e-3,
            "a geographically different anchor must change the direction basis",
        )
    }

    @Test
    fun screenPositionMapPropertiesFallBackToCameraGroundAnchor() {
        val cameraLatitude = 37.0
        val cameraLongitude = 405.0
        val camera = resolvedCamera(
            latitude = cameraLatitude,
            unwrappedLongitude = cameraLongitude,
            zoom = 5.0,
            bearing = 25.0,
            pitch = 50.0,
        )
        val rotation = Vector3(15.0, 25.0, -35.0)
        val scale = 12.0
        val resolved = resolve(
            Placement(
                positionMode = AnchoringMode.SCREEN,
                position = Vector3(200.0, 300.0, 4.0),
                rotationMode = AnchoringMode.MAP,
                rotation = rotation,
                scaleMode = AnchoringMode.MAP,
                scale = scale,
            ),
            camera,
        )
        val expectedDirection = cameraViewBasis(camera) *
            DoubleMatrix3.rotationXyzDegrees(rotation.x, rotation.y, rotation.z)
        val expectedScale = scale * camera.worldSizeLogicalPixels /
            (WORLD_CIRCUMFERENCE_METRES * cos(cameraLatitude * PI / 180.0))

        assertEquals(DrawRegime.SCREEN_COMPOSITED, resolved.drawRegime)
        assertEquals(4.0, resolved.screenCompositeZ)
        assertMatrixClose(expectedDirection, resolved.directionTransform, tolerance = 1e-12)
        assertClose(expectedScale, resolved.logicalScale)
    }

    @Test
    fun mapCameraFallbackUsesOriginalCoordinatesBitExactly() {
        val cameraLatitude = 31.0
        val cameraLongitude = 444363.4567890123
        val camera = resolvedCamera(
            latitude = cameraLatitude,
            unwrappedLongitude = cameraLongitude,
            zoom = 5.25,
            bearing = 25.0,
            pitch = 50.0,
        )
        val rotation = Vector3(15.0, 25.0, -35.0)
        val scale = 12.0
        val resolved = resolve(
            Placement(
                positionMode = AnchoringMode.SCREEN,
                position = Vector3(200.0, 300.0, 4.0),
                rotationMode = AnchoringMode.MAP,
                rotation = rotation,
                scaleMode = AnchoringMode.MAP,
                scale = scale,
            ),
            camera,
        )
        // This case places the thing at the camera's own anchor, so the two bases it used to carry
        // were the same basis: `B^T * B`, the identity in exact arithmetic but NOT bit-exactly, which
        // is why this assertion had to tolerate the rounding those two multiplications introduced.
        // Flat transport (ADR 0063) removes them, so the expectation is now exactly what the resolver
        // computes and the bit-equality below is a stronger claim than it was.
        val expectedDirection = cameraViewBasis(camera) *
            DoubleMatrix3.rotationXyzDegrees(rotation.x, rotation.y, rotation.z)
        val expectedScale = scale * camera.worldSizeLogicalPixels /
            (WORLD_CIRCUMFERENCE_METRES * cos(cameraLatitude * PI / 180.0))

        assertMatrixBitsEqual(expectedDirection, resolved.directionTransform)
        assertEquals(expectedScale.toBits(), resolved.logicalScale.toBits())
    }

    @Test
    fun zeroScaleAndCanonicalNegativeZeroRemainPositiveZero() {
        for (scaleMode in AnchoringMode.entries) {
            val resolved = resolve(
                Placement(
                    positionMode = AnchoringMode.SCREEN,
                    position = Vector3(-0.0, -0.0, -0.0),
                    rotationMode = AnchoringMode.SCREEN,
                    rotation = Vector3(-0.0, -0.0, -0.0),
                    scaleMode = scaleMode,
                    scale = -0.0,
                ),
                resolvedCamera(),
            )

            assertEquals(0.0.toBits(), resolved.logicalPosition.x.toBits())
            assertEquals(0.0.toBits(), resolved.logicalPosition.y.toBits())
            assertEquals(0.0.toBits(), resolved.logicalPosition.z.toBits())
            assertEquals(0.0.toBits(), requireNotNull(resolved.screenCompositeZ).toBits())
            assertEquals(0.0.toBits(), resolved.logicalScale.toBits())
        }
    }

    @Test
    fun floatMaximumIsAcceptedWithoutRoundingAndScreenZStaysCpuDouble() {
        val maximumFloatAsDouble = Float.MAX_VALUE.toDouble()
        val morePreciseThanFloat = 1.0000000000000002
        val resolved = resolve(
            Placement(
                positionMode = AnchoringMode.SCREEN,
                position = Vector3(maximumFloatAsDouble, morePreciseThanFloat, Double.MAX_VALUE),
                rotationMode = AnchoringMode.SCREEN,
                rotation = Vector3(0.0, 0.0, 0.0),
                scaleMode = AnchoringMode.SCREEN,
                scale = maximumFloatAsDouble,
            ),
            resolvedCamera(),
        )

        assertEquals(maximumFloatAsDouble, resolved.logicalPosition.x)
        assertEquals(morePreciseThanFloat, resolved.logicalPosition.y)
        assertEquals(0.0, resolved.logicalPosition.z)
        assertEquals(maximumFloatAsDouble, resolved.logicalScale)
        assertEquals(Double.MAX_VALUE, resolved.screenCompositeZ)
    }

    /**
     * ADR 0063. On a flat map north is straight up at every point, so a map-anchored rotation must
     * resolve to the same orientation wherever the thing carrying it stands.
     *
     * The spherical transport this replaced failed exactly here: it rotated a thing by the angle
     * between two ENU bases that Mercator has already flattened away, by an amount growing with
     * distance from the camera's anchor — 34 degrees at 40 degrees of separation.
     */
    @Test
    fun aMapAnchoredRotationDoesNotDependOnWhereOnTheMapItStands() {
        val camera = resolvedCamera(latitude = 20.0, unwrappedLongitude = 0.0, zoom = 3.0)
        val rotation = Vector3(0.0, 35.0, 0.0)

        fun transformAt(longitude: Double) = resolve(
            placement(
                positionMode = AnchoringMode.MAP,
                position = Vector3(20.0, longitude, 0.0),
                rotationMode = AnchoringMode.MAP,
                rotation = rotation,
            ),
            camera,
        ).directionTransform

        val atTheAnchor = transformAt(0.0)
        // 40 degrees away is the bottom row of ADR 0063's table, where the old form drifted 34
        // degrees and lost 37% of the drawn area.
        assertEquals(atTheAnchor, transformAt(40.0))
        assertEquals(atTheAnchor, transformAt(-40.0))
        assertEquals(atTheAnchor, transformAt(10.0))
    }

    @Test
    fun aMapAnchoredRotationStillFollowsTheCamera() {
        // Position-independent is not the same as camera-independent: bearing and pitch must still
        // reach it, or "flat" would have become "ignored".
        val rotation = Vector3(0.0, 35.0, 0.0)
        fun transformUnder(bearing: Double, pitch: Double) = resolve(
            placement(
                positionMode = AnchoringMode.MAP,
                position = Vector3(20.0, 0.0, 0.0),
                rotationMode = AnchoringMode.MAP,
                rotation = rotation,
            ),
            resolvedCamera(latitude = 20.0, zoom = 3.0, bearing = bearing, pitch = pitch),
        ).directionTransform

        val level = transformUnder(bearing = 0.0, pitch = 0.0)
        assertNotEquals(level, transformUnder(bearing = 30.0, pitch = 0.0))
        assertNotEquals(level, transformUnder(bearing = 0.0, pitch = 30.0))
    }

    @Test
    fun gpuFailuresUseExactPositionAltitudeAndScaleDiagnostics() {
        val overflow = Float.MAX_VALUE.toDouble() * 2.0
        val camera = resolvedCamera()

        assertFailure(
            resolvePlacement(
                placement(
                    positionMode = AnchoringMode.SCREEN,
                    position = Vector3(overflow, 0.0, 0.0),
                ),
                camera,
            ),
            "screenPosition.x",
        )
        assertFailure(
            resolvePlacement(
                placement(
                    positionMode = AnchoringMode.SCREEN,
                    position = Vector3(0.0, overflow, 0.0),
                ),
                camera,
            ),
            "screenPosition.y",
        )
        assertFailure(
            resolvePlacement(
                placement(
                    positionMode = AnchoringMode.MAP,
                    position = Vector3(0.0, 0.0, Double.MAX_VALUE),
                ),
                camera,
            ),
            "mapPosition.altitude",
        )
        assertFailure(
            resolvePlacement(
                placement(scaleMode = AnchoringMode.SCREEN, scale = Double.MAX_VALUE),
                camera,
            ),
            "placement.scale",
        )
        assertFailure(
            resolvePlacement(
                placement(scaleMode = AnchoringMode.MAP, scale = Double.MAX_VALUE),
                camera,
            ),
            "placement.scale",
        )
    }

    @Test
    fun resolvedPlacementRequiresScreenCompositeZExactlyForTheScreenCompositedRegime() {
        val identity = DoubleMatrix3.rotationXyzDegrees(0.0, 0.0, 0.0)
        val origin = DoubleVector3(0.0, 0.0, 0.0)

        assertFailsWith<IllegalArgumentException> {
            ResolvedPlacement(
                drawRegime = DrawRegime.SCREEN_COMPOSITED,
                logicalPosition = origin,
                directionTransform = identity,
                logicalScale = 1.0,
                screenCompositeZ = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResolvedPlacement(
                drawRegime = DrawRegime.MAP_OCCLUDED,
                logicalPosition = origin,
                directionTransform = identity,
                logicalScale = 1.0,
                screenCompositeZ = 3.0,
            )
        }

        val screenComposited = ResolvedPlacement(
            drawRegime = DrawRegime.SCREEN_COMPOSITED,
            logicalPosition = origin,
            directionTransform = identity,
            logicalScale = 1.0,
            screenCompositeZ = 0.0,
        )
        val mapOccluded = ResolvedPlacement(
            drawRegime = DrawRegime.MAP_OCCLUDED,
            logicalPosition = origin,
            directionTransform = identity,
            logicalScale = 1.0,
            screenCompositeZ = null,
        )

        assertEquals(0.0, screenComposited.screenCompositeZ)
        assertNull(mapOccluded.screenCompositeZ)
        assertFailsWith<IllegalArgumentException> { screenComposited.copy(screenCompositeZ = null) }
        assertFailsWith<IllegalArgumentException> { mapOccluded.copy(screenCompositeZ = 1.0) }
        assertFailsWith<IllegalArgumentException> {
            screenComposited.copy(drawRegime = DrawRegime.MAP_OCCLUDED)
        }
        assertFailsWith<IllegalArgumentException> {
            mapOccluded.copy(drawRegime = DrawRegime.SCREEN_COMPOSITED)
        }
    }

    private fun placement(
        positionMode: AnchoringMode = AnchoringMode.SCREEN,
        position: Vector3 = Vector3(0.0, 0.0, 0.0),
        rotationMode: AnchoringMode = AnchoringMode.SCREEN,
        rotation: Vector3 = Vector3(0.0, 0.0, 0.0),
        scaleMode: AnchoringMode = AnchoringMode.SCREEN,
        scale: Double = 1.0,
    ): Placement = Placement(
        positionMode = positionMode,
        position = position,
        rotationMode = rotationMode,
        rotation = rotation,
        scaleMode = scaleMode,
        scale = scale,
    )

    private fun resolve(placement: Placement, camera: ResolvedMercatorCamera): ResolvedPlacement =
        assertIs<SpatialOutcome.Success<ResolvedPlacement>>(resolvePlacement(placement, camera)).value

    private fun resolvedCamera(
        latitude: Double = 0.0,
        unwrappedLongitude: Double = 0.0,
        zoom: Double = 0.0,
        bearing: Double = 0.0,
        pitch: Double = 0.0,
    ): ResolvedMercatorCamera = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
        resolveMercatorCamera(
            Camera(latitude, unwrappedLongitude, zoom, bearing, pitch),
            OutputPixelSize(width = 1920, height = 1080),
        ),
    ).value

    private fun mapLogicalPosition(
        position: GeographicPosition,
        camera: ResolvedMercatorCamera,
    ): DoubleVector3 {
        val projected = projectMercator(position)
        return DoubleVector3(
            x = (projected.x - camera.mercatorAnchor.x) * camera.worldSizeLogicalPixels,
            y = (camera.mercatorAnchor.y - projected.y) * camera.worldSizeLogicalPixels,
            z = projected.z * camera.worldSizeLogicalPixels,
        )
    }

    private fun cameraViewBasis(camera: ResolvedMercatorCamera): DoubleMatrix3 =
        DoubleMatrix3.fromRows(
            listOf(
                listOf(camera.right.x, camera.right.y, camera.right.z),
                listOf(camera.cameraUp.x, camera.cameraUp.y, camera.cameraUp.z),
                listOf(camera.cameraBack.x, camera.cameraBack.y, camera.cameraBack.z),
            ),
        )

    private fun assertFailure(outcome: SpatialOutcome<*>, fieldName: String) {
        val failure = assertIs<SpatialOutcome.Failure>(outcome).failure
        assertEquals(RenGErrorCode.INVALID_VALUE, failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.stage)
        val diagnostic = requireNotNull(failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.FRAME_PLANNING, diagnostic.stage)
        assertEquals(fieldName, diagnostic.fieldName)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.statusCode)
        assertNull(diagnostic.limit)
        assertNull(diagnostic.actual)
    }

    private fun assertMatrixBitsEqual(expected: DoubleMatrix3, actual: DoubleMatrix3) {
        for (row in 0..2) {
            for (column in 0..2) {
                assertEquals(
                    expected[row, column].toBits(),
                    actual[row, column].toBits(),
                    "matrix value at [$row,$column] differs bit-exactly",
                )
            }
        }
    }

    private fun assertMatrixClose(
        expected: DoubleMatrix3,
        actual: DoubleMatrix3,
        tolerance: Double = 1e-11,
    ) {
        for (row in 0..2) {
            for (column in 0..2) {
                assertClose(expected[row, column], actual[row, column], tolerance)
            }
        }
    }

    private fun matrixMaximumDifference(first: DoubleMatrix3, second: DoubleMatrix3): Double {
        var maximum = 0.0
        for (row in 0..2) {
            for (column in 0..2) {
                maximum = maxOf(maximum, abs(first[row, column] - second[row, column]))
            }
        }
        return maximum
    }

    private fun assertVectorClose(
        expected: DoubleVector3,
        actual: DoubleVector3,
        tolerance: Double = 1e-11,
    ) {
        assertClose(expected.x, actual.x, tolerance)
        assertClose(expected.y, actual.y, tolerance)
        assertClose(expected.z, actual.z, tolerance)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-11) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")
    }
}
