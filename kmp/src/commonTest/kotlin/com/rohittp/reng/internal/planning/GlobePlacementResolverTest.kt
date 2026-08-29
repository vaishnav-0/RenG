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
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectCameraRelativeLogicalPosition
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cycle G task 8, the placement half.
 *
 * **Every expected value below is derived from spherical trigonometry written out in this file, not
 * from RenG's own projection run a second time.** The plan records that task 4's round-trip case was
 * blind to three separate mutations — an error the forward and inverse paths share, a self-consistent
 * wrong root, and anything downstream normalisation erases — so a round trip is used here only where
 * an independently derived value stands beside it. [sphereDirection], [eastNorthUpBasis] and
 * [viewBasis] take degrees straight to `sin`/`cos`, touching neither `projectMercator` nor
 * `unitSphereDirection`; agreement with the production path through the Mercator round trip is then a
 * real result rather than an identity.
 *
 * **No map-scale or map-altitude fixture sits at the equator**, because a `cos(latitude)` copied from
 * mercator is the identity there, so a fixture written at the natural place would be green against the
 * very defect this task exists to avoid. No fixture is a placement at the viewport centre, at zero
 * altitude and at the camera's own anchor at once either — that point is symmetric in every axis.
 */
class GlobePlacementResolverTest {
    /**
     * Position, radius, frame conversion, the Mercator round trip and the radial altitude, all in
     * one independently derived vector.
     *
     * The placement is 35 degrees of latitude and 60 degrees of longitude away from the camera and a
     * thousand kilometres up, so no component is zero and no axis can be swapped for another without
     * moving the answer.
     */
    @Test
    fun aMapAnchoredPositionSitsOnTheSphereRatherThanOnThePlane() {
        val cameraLatitude = 25.0
        val cameraLongitude = 40.0
        val zoom = 4.0
        val camera = globeCamera(latitude = cameraLatitude, unwrappedLongitude = cameraLongitude, zoom = zoom)

        val placementLatitude = 60.0
        val placementLongitude = 100.0
        val altitudeMetres = 1.0e6
        val resolved = resolveMap(
            camera,
            latitude = placementLatitude,
            unwrappedLongitude = placementLongitude,
            altitudeMetres = altitudeMetres,
        )

        val radius = globeRadiusLogicalPixels(cameraLatitude, zoom)
        val radial = radius + altitudeMetres * radius / EARTH_SEMI_MAJOR_AXIS_METRES
        val globeFixed = sphereDirection(placementLatitude, placementLongitude) * radial
        val cameraBasis = eastNorthUpBasis(cameraLatitude, cameraLongitude)

        assertRelativelyClose(cameraBasis.column(0).dot(globeFixed), resolved.placement.logicalPosition.x)
        assertRelativelyClose(cameraBasis.column(1).dot(globeFixed), resolved.placement.logicalPosition.y)
        assertRelativelyClose(
            cameraBasis.column(2).dot(globeFixed) - radius,
            resolved.placement.logicalPosition.z,
        )
        assertEquals(DrawRegime.MAP_OCCLUDED, resolved.placement.drawRegime)
        assertNull(resolved.placement.screenCompositeZ)
    }

    /**
     * The altitude trap, isolated: **a metre is the same number of logical pixels everywhere on a
     * sphere.**
     *
     * `projectMercator` divides altitude by `cos(latitude)`; carrying that division onto the globe
     * would make the same aircraft sit 2.9 times higher at latitude 70 than at the equator while
     * agreeing exactly at the equator. The height above the surface is read back as
     * `|globeFixed| - radius` — recovered from the resolved camera-relative vector by Pythagoras
     * rather than by re-running the projection — and asserted equal at three latitudes and equal to
     * the one independently derived number.
     */
    @Test
    fun theAltitudeTermIsRadialAndCarriesNoMercatorCosine() {
        val cameraLatitude = 25.0
        val zoom = 4.0
        val camera = globeCamera(latitude = cameraLatitude, unwrappedLongitude = 0.0, zoom = zoom)
        val radius = globeRadiusLogicalPixels(cameraLatitude, zoom)
        val altitudeMetres = 5.0e5
        val expectedHeightLogicalPixels = altitudeMetres * radius / EARTH_SEMI_MAJOR_AXIS_METRES

        for (latitude in listOf(0.0, 40.0, 70.0)) {
            val resolved = resolveMap(
                camera,
                latitude = latitude,
                unwrappedLongitude = 0.0,
                altitudeMetres = altitudeMetres,
            )
            val position = resolved.placement.logicalPosition
            val distanceFromCentre = sqrt(
                position.x * position.x +
                    position.y * position.y +
                    (position.z + radius) * (position.z + radius),
            )

            assertRelativelyClose(
                expectedHeightLogicalPixels,
                distanceFromCentre - radius,
                message = "altitude in logical pixels at latitude $latitude",
            )
        }
    }

    /**
     * The scale trap: **map scale on a sphere does not depend on the placement's latitude.**
     *
     * `PlacementResolver.kt` divides by `cos(latitude)` because Mercator's own metre shrinks with
     * latitude. Copying it would make a map-scaled model 2.9 times too large at latitude 70. The two
     * fixture latitudes straddle the camera's so that neither sits at ADR 0037's fixed point, where
     * the latitude-matched zoom cancels the bug exactly.
     */
    @Test
    fun mapScaleIsLatitudeIndependentAndCarriesNoMercatorCosine() {
        val cameraLatitude = 25.0
        val zoom = 4.0
        val scale = 3.0
        val camera = globeCamera(latitude = cameraLatitude, unwrappedLongitude = 40.0, zoom = zoom)
        val worldSize = 512.0 * 2.0.pow(zoom - log2(cos(cameraLatitude.toRadians())))
        val expected = scale * worldSize / (2.0 * PI * EARTH_SEMI_MAJOR_AXIS_METRES)

        val far = resolveMap(camera, latitude = 70.0, unwrappedLongitude = 40.0, scale = scale)
        val near = resolveMap(camera, latitude = 5.0, unwrappedLongitude = 40.0, scale = scale)

        assertRelativelyClose(expected, far.placement.logicalScale, message = "map scale at latitude 70")
        assertRelativelyClose(expected, near.placement.logicalScale, message = "map scale at latitude 5")
        assertEquals(
            far.placement.logicalScale.toBits(),
            near.placement.logicalScale.toBits(),
            "a sphere has no latitude distortion, so the two must be bit-identical",
        )
    }

    /**
     * ADR 0037's own promise, tested with content rather than argued: at the camera's **own**
     * latitude a map-scaled thing is the same size in both projection modes.
     *
     * That is what the latitude-matched zoom `z_eff = zoom - log2 cos(latitude)` buys. It was written
     * expecting to be blind to a copied `cos(latitude)`, on the argument that the two cosines cancel at
     * this fixture's own latitude; running that mutation showed they do not — the world size carries
     * `1 / cos(cameraLatitude)` and the copied distortion would carry `1 / cos(placementLatitude)`
     * **on top of** it — and this case turned red with the other two. The reasoning was wrong and the
     * measurement is what says so.
     */
    @Test
    fun mapScaleMatchesMercatorAtTheCameraOwnLatitude() {
        val latitude = 55.0
        val longitude = -12.0
        val zoom = 6.0
        val placement = placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(latitude, longitude, 0.0),
            scaleMode = AnchoringMode.MAP,
            scale = 7.5,
        )

        val globe = resolveGlobePlacementOrFail(placement, globeCamera(latitude, longitude, zoom))
        val mercator = assertIs<SpatialOutcome.Success<ResolvedPlacement>>(
            resolvePlacement(placement, mercatorCamera(latitude, longitude, zoom)),
        ).value

        assertRelativelyClose(mercator.logicalScale, globe.placement.logicalScale, tolerance = 1e-12)
    }

    /**
     * A map-anchored rotation turns with the sphere: at the same longitude, moving north by 8 degrees
     * tilts the anchor's frame by 8 degrees about its own east axis and nothing else.
     *
     * Bearing and pitch are zero here so that the view basis is the identity and the whole answer is
     * the closed form `rotationXDegrees(-8)`, derived on paper rather than from the code under test.
     * The general case follows in [aMapAnchoredRotationComposesTheViewAndBothLocalFrames].
     */
    @Test
    fun aMapAnchoredRotationTiltsByTheLatitudeDifferenceAlone() {
        val cameraLatitude = 30.0
        val longitude = 12.0
        val camera = globeCamera(latitude = cameraLatitude, unwrappedLongitude = longitude, zoom = 5.0)
        val latitudeStep = 8.0

        val moved = resolveMap(camera, latitude = cameraLatitude + latitudeStep, unwrappedLongitude = longitude)
        val atTheAnchor = resolveMap(camera, latitude = cameraLatitude, unwrappedLongitude = longitude)

        assertMatrixClose(DoubleMatrix3.rotationXDegrees(-latitudeStep), moved.placement.directionTransform)
        assertMatrixClose(DoubleMatrix3.identity, atTheAnchor.placement.directionTransform)
    }

    /**
     * The general map-anchored rotation, with a bearing, a pitch, a local rotation and an anchor
     * three quarters of the way around the planet from the camera.
     *
     * The expected value composes three independently written matrices — the view basis from bearing
     * and pitch, and both east/north/up bases from latitude and longitude — so nothing in it comes
     * from `internal.projection`.
     */
    @Test
    fun aMapAnchoredRotationComposesTheViewAndBothLocalFrames() {
        val cameraLatitude = 30.0
        val cameraLongitude = 12.0
        val bearing = 35.0
        val pitch = 40.0
        val camera = globeCamera(
            latitude = cameraLatitude,
            unwrappedLongitude = cameraLongitude,
            zoom = 5.0,
            bearing = bearing,
            pitch = pitch,
        )
        val rotation = Vector3(10.0, -20.0, 30.0)
        val placementLatitude = -15.0
        val placementLongitude = 95.0

        val resolved = resolveGlobePlacementOrFail(
            placement(
                positionMode = AnchoringMode.MAP,
                position = Vector3(placementLatitude, placementLongitude, 0.0),
                rotationMode = AnchoringMode.MAP,
                rotation = rotation,
            ),
            camera,
        )

        val expected = viewBasis(bearing, pitch) *
            eastNorthUpBasis(cameraLatitude, cameraLongitude).transpose() *
            eastNorthUpBasis(placementLatitude, placementLongitude) *
            DoubleMatrix3.rotationXyzDegrees(rotation.x, rotation.y, rotation.z)

        assertMatrixClose(expected, resolved.placement.directionTransform)
    }

    /**
     * Screen anchoring is projection-mode-independent, and this asserts it bit-for-bit rather than
     * closely: `CONTEXT.md`'s screen anchoring is resolution against output-pixel space and never
     * touches a view or projection matrix, so a globe must not move a single HUD pixel.
     *
     * A screen-composited thing is also never culled by the horizon — the planet is not between the
     * camera and a badge drawn in screen space.
     */
    @Test
    fun screenAnchoredPlacementsResolveExactlyAsUnderMercator() {
        val latitude = 47.0
        val longitude = 8.0
        val zoom = 9.0
        val bearing = 22.0
        val pitch = 33.0
        val placement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(123.25, 456.75, 7.5),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(11.0, -22.0, 33.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 2.5,
        )

        val globe = resolveGlobePlacementOrFail(
            placement,
            globeCamera(latitude, longitude, zoom, bearing, pitch),
        )
        val mercator = assertIs<SpatialOutcome.Success<ResolvedPlacement>>(
            resolvePlacement(placement, mercatorCamera(latitude, longitude, zoom, bearing, pitch)),
        ).value

        assertEquals(mercator, globe.placement)
        assertTrue(!globe.beyondHorizon, "a screen-composited placement is never behind the planet")
    }

    /**
     * A screen-positioned placement whose rotation and scale are map-anchored falls back to the
     * camera's own ground anchor, exactly as the Mercator arm does — so the relative frame is the
     * identity and only the view basis survives.
     */
    @Test
    fun screenPositionMapPropertiesFallBackToTheCameraGroundAnchor() {
        val latitude = 37.0
        val longitude = 405.0
        val bearing = 25.0
        val pitch = 50.0
        val zoom = 5.0
        val rotation = Vector3(15.0, 25.0, -35.0)
        val scale = 12.0
        val camera = globeCamera(latitude, longitude, zoom, bearing, pitch)

        val resolved = resolveGlobePlacementOrFail(
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

        val worldSize = 512.0 * 2.0.pow(zoom - log2(cos(latitude.toRadians())))
        val expectedDirection = viewBasis(bearing, pitch) *
            DoubleMatrix3.rotationXyzDegrees(rotation.x, rotation.y, rotation.z)

        assertEquals(DrawRegime.SCREEN_COMPOSITED, resolved.placement.drawRegime)
        assertEquals(4.0, resolved.placement.screenCompositeZ)
        assertMatrixClose(expectedDirection, resolved.placement.directionTransform)
        assertRelativelyClose(
            scale * worldSize / (2.0 * PI * EARTH_SEMI_MAJOR_AXIS_METRES),
            resolved.placement.logicalScale,
        )
    }

    /**
     * **The trap this task was warned about, encoded as a case.**
     *
     * The antipode is the single most thoroughly hidden point on the planet, and it carries the
     * *largest* positive `w` of any point on the globe — `2 * radius + cameraDistance` — while
     * projecting to the exact centre of the frame. A horizon test written as "the sign of `w`", or as
     * a viewport test, culls it not at all while looking correct. Both of those wrong answers are
     * asserted here as facts, beside the right one.
     */
    @Test
    fun theAntipodeIsBeyondTheHorizonThoughItsPixelAndItsDepthLookPerfect() {
        val cameraLatitude = 20.0
        val cameraLongitude = 45.0
        val zoom = 3.0
        val camera = globeCamera(cameraLatitude, cameraLongitude, zoom)
        val radius = globeRadiusLogicalPixels(cameraLatitude, zoom)
        val cameraDistance = cameraDistanceLogicalPixels(OUTPUT_HEIGHT)

        val antipode = resolveMap(camera, latitude = -cameraLatitude, unwrappedLongitude = cameraLongitude - 180.0)
        val beneathTheCamera = resolveMap(camera, latitude = cameraLatitude, unwrappedLongitude = cameraLongitude)

        assertTrue(antipode.beyondHorizon, "the antipode is behind the planet")
        assertTrue(!beneathTheCamera.beyondHorizon, "the point under the camera is not")

        val projected = assertIs<ScreenProjection.Projected>(
            projectCameraRelativeLogicalPosition(camera, antipode.placement.logicalPosition),
        )
        assertRelativelyClose(2.0 * radius + cameraDistance, projected.w, tolerance = 1e-6)
        assertTrue(projected.w > 0.0, "the whole planet is in front of a camera outside it")
        assertRelativelyClose(OUTPUT_WIDTH / 2.0, projected.pixelX, tolerance = 1e-6)
        assertRelativelyClose(OUTPUT_HEIGHT / 2.0, projected.pixelY, tolerance = 1e-6)
    }

    /**
     * The cull boundary is the tangent circle, derived independently: a camera at distance `d` from a
     * sphere of radius `R` sees exactly the cap within `acos(R / d)` of its own sub-point.
     *
     * The two fixtures sit a twentieth of a degree either side of that angle, which is far tighter
     * than any plausible wrong constant and far looser than `Double`'s own noise.
     */
    @Test
    fun theHorizonBoundaryIsTheTangentCircleAndNotAnApproximationOfIt() {
        val cameraLatitude = 10.0
        val zoom = 2.0
        val camera = globeCamera(cameraLatitude, 0.0, zoom)
        val radius = globeRadiusLogicalPixels(cameraLatitude, zoom)
        val eyeDistanceFromCentre = radius + cameraDistanceLogicalPixels(OUTPUT_HEIGHT)
        val limbAngleDegrees = acos(radius / eyeDistanceFromCentre) * 180.0 / PI

        val justInside = resolveMap(camera, latitude = cameraLatitude + limbAngleDegrees - 0.05, unwrappedLongitude = 0.0)
        val justOutside = resolveMap(camera, latitude = cameraLatitude + limbAngleDegrees + 0.05, unwrappedLongitude = 0.0)

        assertTrue(limbAngleDegrees > 60.0, "the fixture only exercises a cull if the limb is far from the anchor")
        assertTrue(!justInside.beyondHorizon, "a point inside the tangent circle is visible")
        assertTrue(justOutside.beyondHorizon, "a point outside it is not")
    }

    /**
     * ADR 0038's accepted gap, **measured rather than assumed**: content that straddles the limb is
     * culled entirely.
     *
     * The fixture is placed two degrees beyond the tangent circle and then raised until it is
     * genuinely outside the planet's shadow — the tangent ray grazing the sphere passes through
     * radius `R / cos(theta - limb)` at that angle, and the first assertion is that this placement is
     * above it, so a correct occlusion test would draw it. The second is that RenG culls it anyway,
     * because a `Placement` resolves to one anchor and the limb plane is a half-space rather than the
     * tangent cone.
     */
    @Test
    fun contentJustBeyondTheLimbIsCulledEvenWhenItReallyStandsAboveTheHorizon() {
        val cameraLatitude = 10.0
        val zoom = 2.0
        val camera = globeCamera(cameraLatitude, 0.0, zoom)
        val radius = globeRadiusLogicalPixels(cameraLatitude, zoom)
        val limbAngleDegrees = acos(radius / (radius + cameraDistanceLogicalPixels(OUTPUT_HEIGHT))) * 180.0 / PI
        val overshootDegrees = 2.0
        val altitudeMetres = 1.0e5

        val radial = radius + altitudeMetres * radius / EARTH_SEMI_MAJOR_AXIS_METRES
        val tangentRayRadiusAtThatAngle = radius / cos(overshootDegrees.toRadians())
        assertTrue(
            radial > tangentRayRadiusAtThatAngle,
            "the fixture must stand outside the tangent cone, or it measures nothing",
        )

        val raised = resolveMap(
            camera,
            latitude = cameraLatitude + limbAngleDegrees + overshootDegrees,
            unwrappedLongitude = 0.0,
            altitudeMetres = altitudeMetres,
        )

        assertTrue(raised.beyondHorizon, "ADR 0038 culls it, and that is the recorded gap")
    }

    /**
     * A camera the globe hides nothing from culls nothing.
     *
     * `globeLimbPlane` returns `null` rather than a plane when the camera is not strictly outside the
     * sphere, because at `d <= R` the offset `R^2 / d` puts the entire planet on the far side and a
     * plane test would empty the map. RenG's own camera never reaches that state, so the ruling is
     * reached here by handing the resolver the `null` plane directly.
     */
    @Test
    fun aCameraTheGlobeHidesNothingFromCullsNothing() {
        val camera = globeCamera(20.0, 45.0, 3.0)
        val antipode = placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(-20.0, -135.0, 0.0),
        )

        val withPlane = resolveGlobePlacementOrFail(antipode, camera)
        val withoutPlane = assertIs<SpatialOutcome.Success<GlobePlacement>>(
            resolveGlobePlacement(antipode, camera, limbPlane = null),
        ).value

        assertTrue(withPlane.beyondHorizon, "the camera's own plane culls the antipode")
        assertTrue(!withoutPlane.beyondHorizon, "no plane means cull nothing, never cull everything")
        assertEquals(withPlane.placement, withoutPlane.placement, "the plane decides nothing else")
    }

    /**
     * The guards, and which field each names.
     *
     * `ScreenProjection`'s globe arm records that a camera-relative position on a sphere needs no
     * GPU-representability guard because it is bounded by `2 * radius + cameraDistance`. That is true
     * of the sphere and false of a placement: altitude is bounded only by finiteness and enters as a
     * radial multiple, so the guard is live here — and it is the only input that can trip it, which
     * is why all three components report `mapPosition.altitude`.
     */
    @Test
    fun outOfDomainAndUnrepresentableInputsFailWithTheirOwnDiagnostics() {
        val camera = globeCamera(0.0, 0.0, 0.0)
        val overflow = Float.MAX_VALUE.toDouble() * 2.0

        assertFailure(
            resolveGlobePlacement(
                placement(positionMode = AnchoringMode.MAP, position = Vector3(86.0, 0.0, 0.0)),
                camera,
            ),
            "mapPosition.latitude",
        )
        assertFailure(
            resolveGlobePlacement(
                placement(positionMode = AnchoringMode.MAP, position = Vector3(0.0, 0.0, Double.MAX_VALUE)),
                camera,
            ),
            "mapPosition.altitude",
        )
        assertFailure(
            resolveGlobePlacement(
                placement(positionMode = AnchoringMode.SCREEN, position = Vector3(overflow, 0.0, 0.0)),
                camera,
            ),
            "screenPosition.x",
        )
        assertFailure(
            resolveGlobePlacement(
                placement(positionMode = AnchoringMode.SCREEN, position = Vector3(0.0, overflow, 0.0)),
                camera,
            ),
            "screenPosition.y",
        )
        assertFailure(
            resolveGlobePlacement(
                placement(scaleMode = AnchoringMode.MAP, scale = Double.MAX_VALUE),
                camera,
            ),
            "placement.scale",
        )
        assertFailure(
            resolveGlobePlacement(
                placement(scaleMode = AnchoringMode.SCREEN, scale = Double.MAX_VALUE),
                camera,
            ),
            "placement.scale",
        )
    }

    private fun placement(
        positionMode: AnchoringMode = AnchoringMode.SCREEN,
        position: Vector3 = Vector3(0.0, 0.0, 0.0),
        rotationMode: AnchoringMode = AnchoringMode.MAP,
        rotation: Vector3 = Vector3(0.0, 0.0, 0.0),
        scaleMode: AnchoringMode = AnchoringMode.MAP,
        scale: Double = 1.0,
    ): Placement = Placement(
        positionMode = positionMode,
        position = position,
        rotationMode = rotationMode,
        rotation = rotation,
        scaleMode = scaleMode,
        scale = scale,
    )

    private fun resolveMap(
        camera: ResolvedGlobeCamera,
        latitude: Double,
        unwrappedLongitude: Double,
        altitudeMetres: Double = 0.0,
        scale: Double = 1.0,
    ): GlobePlacement = resolveGlobePlacementOrFail(
        placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(latitude, unwrappedLongitude, altitudeMetres),
            scale = scale,
        ),
        camera,
    )

    private fun resolveGlobePlacementOrFail(
        placement: Placement,
        camera: ResolvedGlobeCamera,
    ): GlobePlacement = assertIs<SpatialOutcome.Success<GlobePlacement>>(
        resolveGlobePlacement(placement, camera),
    ).value

    private fun globeCamera(
        latitude: Double,
        unwrappedLongitude: Double,
        zoom: Double,
        bearing: Double = 0.0,
        pitch: Double = 0.0,
    ): ResolvedGlobeCamera = assertIs<SpatialOutcome.Success<ResolvedGlobeCamera>>(
        resolveGlobeCamera(
            Camera(latitude, unwrappedLongitude, zoom, bearing, pitch),
            OutputPixelSize(width = OUTPUT_WIDTH, height = OUTPUT_HEIGHT),
        ),
    ).value

    private fun mercatorCamera(
        latitude: Double,
        unwrappedLongitude: Double,
        zoom: Double,
        bearing: Double = 0.0,
        pitch: Double = 0.0,
    ): ResolvedMercatorCamera = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
        resolveMercatorCamera(
            Camera(latitude, unwrappedLongitude, zoom, bearing, pitch),
            OutputPixelSize(width = OUTPUT_WIDTH, height = OUTPUT_HEIGHT),
        ),
    ).value

    /**
     * The globe's radius in logical pixels for a camera at [cameraLatitudeDegrees], written out from
     * ADR 0037's `z_eff = zoom - log2 cos(latitude)` and `2 * PI * R = worldSize` rather than read off
     * [ResolvedGlobeCamera.radiusLogicalPixels].
     */
    private fun globeRadiusLogicalPixels(cameraLatitudeDegrees: Double, zoom: Double): Double =
        512.0 * 2.0.pow(zoom - log2(cos(cameraLatitudeDegrees.toRadians()))) / (2.0 * PI)

    /**
     * How far back the eye sits, from the 45 degree vertical field of view directly: the distance at
     * which half the frame height subtends 22.5 degrees. `1 / tan(PI / 8)` is `1 + sqrt(2)`, which is
     * what `FOCAL_LENGTH_SCALE` spells; deriving it from the angle keeps this file independent of that
     * constant.
     */
    private fun cameraDistanceLogicalPixels(heightPixels: Int): Double =
        heightPixels.toDouble() / (2.0 * tan(PI / 8.0))

    /** `(cos lat cos lon, cos lat sin lon, sin lat)`, straight from the angles. */
    private fun sphereDirection(latitudeDegrees: Double, longitudeDegrees: Double): DoubleVector3 {
        val latitude = latitudeDegrees.toRadians()
        val longitude = longitudeDegrees.toRadians()
        return DoubleVector3(
            x = cos(latitude) * cos(longitude),
            y = cos(latitude) * sin(longitude),
            z = sin(latitude),
        )
    }

    /** East, north and up as the three columns, each written out rather than crossed off the others. */
    private fun eastNorthUpBasis(latitudeDegrees: Double, longitudeDegrees: Double): DoubleMatrix3 {
        val latitude = latitudeDegrees.toRadians()
        val longitude = longitudeDegrees.toRadians()
        return DoubleMatrix3.fromColumns(
            DoubleVector3(-sin(longitude), cos(longitude), 0.0),
            DoubleVector3(
                -sin(latitude) * cos(longitude),
                -sin(latitude) * sin(longitude),
                cos(latitude),
            ),
            DoubleVector3(
                cos(latitude) * cos(longitude),
                cos(latitude) * sin(longitude),
                sin(latitude),
            ),
        )
    }

    /**
     * The camera's own basis in its anchor's east/north/up frame, as rows, from bearing and pitch:
     * right, up and back for a camera that faces the compass bearing and tilts by the pitch.
     */
    private fun viewBasis(bearingDegrees: Double, pitchDegrees: Double): DoubleMatrix3 {
        val bearing = bearingDegrees.toRadians()
        val pitch = pitchDegrees.toRadians()
        return DoubleMatrix3.fromRows(
            listOf(
                listOf(cos(bearing), -sin(bearing), 0.0),
                listOf(sin(bearing) * cos(pitch), cos(bearing) * cos(pitch), sin(pitch)),
                listOf(-sin(bearing) * sin(pitch), -cos(bearing) * sin(pitch), cos(pitch)),
            ),
        )
    }

    private fun assertFailure(outcome: SpatialOutcome<*>, fieldName: String) {
        val failure = assertIs<SpatialOutcome.Failure>(outcome).failure
        assertEquals(RenGErrorCode.INVALID_VALUE, failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.stage)
        val diagnostic = requireNotNull(failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.FRAME_PLANNING, diagnostic.stage)
        assertEquals(fieldName, diagnostic.fieldName)
    }

    private fun assertMatrixClose(
        expected: DoubleMatrix3,
        actual: DoubleMatrix3,
        tolerance: Double = 1e-12,
    ) {
        for (row in 0..2) {
            for (column in 0..2) {
                assertTrue(
                    abs(expected[row, column] - actual[row, column]) <= tolerance,
                    "matrix[$row,$column]: expected ${expected[row, column]} but was ${actual[row, column]}",
                )
            }
        }
    }

    private fun assertRelativelyClose(
        expected: Double,
        actual: Double,
        tolerance: Double = 1e-9,
        message: String = "value",
    ) {
        val scale = maxOf(abs(expected), 1.0)
        assertTrue(
            abs(expected - actual) <= tolerance * scale,
            "$message: expected $expected but was $actual",
        )
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val OUTPUT_WIDTH: Int = 960
        const val OUTPUT_HEIGHT: Int = 540

        /** WGS84's semi-major axis, the datum both projections are defined against. */
        const val EARTH_SEMI_MAJOR_AXIS_METRES: Double = 6378137.0
    }
}
