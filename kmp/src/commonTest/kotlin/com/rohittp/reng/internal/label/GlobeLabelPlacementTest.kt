package com.rohittp.reng.internal.label

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * E-labels' placement pass, run against a globe camera.
 *
 * **The conclusion these cases exist to hold in place: `ScreenProjection`'s arithmetic needed no
 * globe arm, and the label path above it did.** The perspective divide and the viewport transform
 * are projection-mode-independent — the mode-specific step happens before them — and task 4 had
 * already given `projectGeographicPosition` its globe overload. What had not moved was
 * `placeLabels`, `layOutLineLabels` and their two private helpers, every one of them typed to
 * `ResolvedMercatorCamera`, so a globe camera could not be handed to any of them. Widening those
 * four signatures to `ResolvedFrameCamera` is the whole of the change, plus one function that adds
 * the question the globe brings with it: a label anchor on the far side of the planet projects to a
 * perfectly ordinary pixel.
 *
 * The camera here is at zoom 2 rather than E-labels' 13.7, because at zoom 13.7 a fixture a
 * kilometre from the anchor bows off the tangent plane by far less than a pixel and a globe and a
 * plane are the same picture. A case that cannot see the sphere is a case that cannot see the sphere
 * being wrong.
 */
class GlobeLabelPlacementTest {
    /**
     * A label anchor follows the sphere, measured against a pixel derived here from the sphere's own
     * geometry and the lens — not from the projection under test — and against the plane's answer for
     * the identical candidate, which is fourteen pixels away in y and eighteen in x.
     *
     * The candidate sits 30 degrees of longitude east of the camera, which at zoom 2 is a third of
     * the way to the limb: far enough that curvature is worth many pixels and near enough that both
     * projections still put the label on screen, so the case measures a difference rather than a
     * disappearance.
     */
    @Test
    fun aLabelAnchorFollowsTheSphereAndTheSameCandidateLandsElsewhereOnThePlane() {
        val globe = globeCamera()
        val mercator = mercatorCamera()
        val candidate = placementCandidate(position = THIRTY_DEGREES_EAST)

        val onTheGlobe = placeLabels(globe, placementBatch(candidate)).single()
        val onThePlane = placeLabels(mercator, placementBatch(candidate)).single()
        val expected = sphericalAnchorPixel(THIRTY_DEGREES_EAST)

        assertClose(expected.first, onTheGlobe.anchorPixelX, message = "globe anchor x")
        assertClose(expected.second, onTheGlobe.anchorPixelY, message = "globe anchor y")
        assertTrue(
            abs(onThePlane.anchorPixelX - onTheGlobe.anchorPixelX) > 10.0 &&
                abs(onThePlane.anchorPixelY - onTheGlobe.anchorPixelY) > 10.0,
            "the plane must disagree in both axes, or the fixture is too close to the anchor: " +
                "${onThePlane.anchorPixelX},${onThePlane.anchorPixelY} vs " +
                "${onTheGlobe.anchorPixelX},${onTheGlobe.anchorPixelY}",
        )
    }

    /**
     * **A label behind the planet is dropped, and the one in front of it is not.**
     *
     * Both candidates are in one batch so that the case fails in both directions: deleting the
     * horizon test places two labels, and culling indiscriminately places none. The hidden candidate
     * is the antipode, whose pixel is the exact centre of the frame and whose `w` is the largest on
     * the globe — the case asserts that its plain projection is `Projected` first, so that the drop is
     * demonstrably the limb plane's doing and not a viewport or near-plane rejection.
     *
     * Their boundaries do not touch, so neither is dropped by collision against the other.
     */
    @Test
    fun aLabelBehindThePlanetIsDroppedAndOneInFrontOfItIsNot() {
        val globe = globeCamera()
        val antipode = GeographicPosition(-CAMERA_LATITUDE, CAMERA_LONGITUDE - 180.0, 0.0)
        val visible = GeographicPosition(CAMERA_LATITUDE, CAMERA_LONGITUDE + 60.0, 0.0)

        val antipodalPixel = sphericalAnchorPixel(antipode)
        val visiblePixel = sphericalAnchorPixel(visible)
        assertClose(WIDTH / 2.0, antipodalPixel.first, message = "the antipode projects to the centre")
        assertClose(HEIGHT / 2.0, antipodalPixel.second, message = "the antipode projects to the centre")
        assertTrue(
            abs(visiblePixel.first - antipodalPixel.first) > 150.0,
            "the two boxes must not collide, or the wrong one could be the survivor",
        )

        val placed = placeLabels(
            globe,
            placementBatch(
                placementCandidate(position = antipode),
                placementCandidate(position = visible),
            ),
        )

        assertEquals(1, placed.size, "exactly one of the two is in front of the planet")
        assertClose(visiblePixel.first, placed.single().anchorPixelX, message = "the survivor's x")
        assertClose(visiblePixel.second, placed.single().anchorPixelY, message = "the survivor's y")
    }

    /**
     * The mercator path is untouched: the identical candidate and camera place the identical label
     * whether the batch goes through the point pass or the line pass.
     *
     * E-labels' own suites already assert what a mercator label looks like; what this adds is that
     * the widened signature and the new projection wrapper did not move it, over a camera with a
     * bearing and a pitch and a line candidate whose runs are rebuilt point by point.
     */
    @Test
    fun noMercatorLabelMovesUnderTheWidenedSignature() {
        val camera = resolvedPlacementCamera()

        val point = placeLabels(camera, placementBatch(placementCandidate())).single()
        val anchor = projectedAnchor(camera)
        assertClose(anchor.pixelX, point.anchorPixelX, message = "mercator point anchor x")
        assertClose(anchor.pixelY, point.anchorPixelY, message = "mercator point anchor y")

        val line = layOutLineLabels(camera, PLACEMENT_ATLAS, lineCandidate(), candidateIndex = 0).single()
        val run = screenRunOf(camera, LONG_LINE)
        val nearest = run.nearestOn(line.anchorPixelX, line.anchorPixelY)
        assertClose(0.0, nearest.offLine, message = "the mercator line anchor still sits on its line")
    }

    /**
     * Where a geographic position lands on screen under a globe camera at bearing and pitch zero,
     * written out from spherical trigonometry, the east/north/up basis, the view translation and the
     * 45 degree lens.
     *
     * Nothing here calls `internal.projection`: `sin` and `cos` of the angles, three dot products,
     * one subtraction of the radius, one perspective divide and one viewport transform.
     */
    private fun sphericalAnchorPixel(position: GeographicPosition): Pair<Double, Double> {
        val radius = 512.0 * 2.0.pow(ZOOM - log2(cos(CAMERA_LATITUDE.toRadians()))) / (2.0 * PI)
        val point = sphereDirection(position.latitude, position.unwrappedLongitude) * radius

        val cameraLatitude = CAMERA_LATITUDE.toRadians()
        val cameraLongitude = CAMERA_LONGITUDE.toRadians()
        val east = DoubleVector3(-sin(cameraLongitude), cos(cameraLongitude), 0.0)
        val north = DoubleVector3(
            -sin(cameraLatitude) * cos(cameraLongitude),
            -sin(cameraLatitude) * sin(cameraLongitude),
            cos(cameraLatitude),
        )
        val up = sphereDirection(CAMERA_LATITUDE, CAMERA_LONGITUDE)

        // Bearing and pitch are zero, so the camera basis is the identity and the view matrix is a
        // translation of `cameraDistance` along the anchor's up axis alone.
        val cameraDistance = HEIGHT.toDouble() / (2.0 * tan(PI / 8.0))
        val focalLength = 1.0 / tan(PI / 8.0)
        val viewX = east.dot(point)
        val viewY = north.dot(point)
        val depth = cameraDistance - (up.dot(point) - radius)

        return Pair(
            (focalLength / (WIDTH.toDouble() / HEIGHT.toDouble()) * viewX / depth + 1.0) * 0.5 * WIDTH,
            (1.0 - focalLength * viewY / depth) * 0.5 * HEIGHT,
        )
    }

    private fun sphereDirection(latitudeDegrees: Double, longitudeDegrees: Double): DoubleVector3 {
        val latitude = latitudeDegrees.toRadians()
        val longitude = longitudeDegrees.toRadians()
        return DoubleVector3(
            x = cos(latitude) * cos(longitude),
            y = cos(latitude) * sin(longitude),
            z = sin(latitude),
        )
    }

    private fun globeCamera(): ResolvedGlobeCamera =
        assertIs<SpatialOutcome.Success<ResolvedGlobeCamera>>(
            resolveGlobeCamera(globeFixtureCamera(), OUTPUT),
        ).value

    private fun mercatorCamera(): ResolvedMercatorCamera =
        assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(globeFixtureCamera(), OUTPUT),
        ).value

    private fun globeFixtureCamera(): Camera = Camera(
        latitude = CAMERA_LATITUDE,
        unwrappedLongitude = CAMERA_LONGITUDE,
        zoom = ZOOM,
        bearing = 0.0,
        pitch = 0.0,
    )

    private fun assertClose(
        expected: Double,
        actual: Double,
        tolerance: Double = 1e-6,
        message: String = "value",
    ) {
        assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected but was $actual")
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val WIDTH: Int = 853
        const val HEIGHT: Int = 509
        val OUTPUT: OutputPixelSize = OutputPixelSize(width = WIDTH, height = HEIGHT)

        const val CAMERA_LATITUDE: Double = 20.0
        const val CAMERA_LONGITUDE: Double = 10.0
        const val ZOOM: Double = 2.0

        val THIRTY_DEGREES_EAST: GeographicPosition =
            GeographicPosition(latitude = 20.0, unwrappedLongitude = 40.0, altitudeMetres = 0.0)
    }
}
