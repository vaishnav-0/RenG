package com.rohittp.reng.internal.gl

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.Placement
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.GlobePlacement
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGlobePlacement
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The two `SceneContent` functions a sticker and a model reach on a globe, and the light that shades
 * the model.
 *
 * **None of the three needed a globe arm; all three needed their camera parameter widened, and that
 * is the finding these cases pin.** `composeMapCameraSpaceModel`, `composeMapModelViewProjection`
 * and [sceneLightDirectionCameraSpace] read only the mode-independent half of a resolved camera —
 * the view matrix, the projection matrix and the orientation — because every mode-specific step has
 * already been spent by the time a `ResolvedPlacement` exists. So the expected values below are the
 * lens applied to a placement the sphere produced, derived here from the field of view and the
 * radius rather than from the matrices under test.
 */
class GlobeSceneCompositionTest {
    /**
     * A map-anchored, map-scaled sticker standing at the camera's own ground anchor: its local origin
     * lands at the centre of the frame, and its local `+x` and `+y` unit axes land exactly one
     * `logicalScale`'s worth away, through the 45 degree lens.
     *
     * The anchor is the one place on the globe where the *position* contributes nothing, which is
     * deliberate here: it leaves the map **scale** as the only thing the two asserted corners can be
     * measuring, and map scale is where the mercator cosine would have been copied. At the fixture's
     * latitude 35 that cosine is 1.22, so a copied one moves the `+x` corner by 55 pixels.
     */
    @Test
    fun aMapScaledStickerMatrixCarriesTheSphereScaleThroughTheLens() {
        val latitude = 35.0
        val longitude = 20.0
        val zoom = 8.0
        val metresPerLocalUnit = 50_000.0
        val camera = globeCamera(latitude, longitude, zoom)

        val resolved = resolveGlobe(
            Placement(
                positionMode = AnchoringMode.MAP,
                position = Vector3(latitude, longitude, 0.0),
                rotationMode = AnchoringMode.SCREEN,
                rotation = Vector3(0.0, 0.0, 0.0),
                scaleMode = AnchoringMode.MAP,
                scale = metresPerLocalUnit,
            ),
            camera,
        )
        val modelViewProjection = composeMapModelViewProjection(camera, resolved.placement)

        val radius = 512.0 * 2.0.pow(zoom - log2(cos(latitude.toRadians()))) / (2.0 * PI)
        val logicalScale = metresPerLocalUnit * radius / EARTH_SEMI_MAJOR_AXIS_METRES
        val cameraDistance = HEIGHT.toDouble() / (2.0 * tan(PI / 8.0))
        val focalLength = 1.0 / tan(PI / 8.0)
        val aspect = WIDTH.toDouble() / HEIGHT.toDouble()

        val origin = pixelOf(modelViewProjection, DoubleVector3(0.0, 0.0, 0.0))
        assertClose(WIDTH / 2.0, origin.first, message = "the anchor is the centre of the frame")
        assertClose(HEIGHT / 2.0, origin.second, message = "the anchor is the centre of the frame")

        val alongX = pixelOf(modelViewProjection, DoubleVector3(1.0, 0.0, 0.0))
        val alongY = pixelOf(modelViewProjection, DoubleVector3(0.0, 1.0, 0.0))
        val normalisedX = focalLength / aspect * logicalScale / cameraDistance
        val normalisedY = focalLength * logicalScale / cameraDistance

        assertClose((normalisedX + 1.0) * 0.5 * WIDTH, alongX.first, message = "local +x")
        assertClose(HEIGHT / 2.0, alongX.second, message = "local +x does not move y")
        assertClose(WIDTH / 2.0, alongY.first, message = "local +y does not move x")
        assertClose((1.0 - normalisedY) * 0.5 * HEIGHT, alongY.second, message = "local +y")
    }

    /**
     * ADR 0026's one light on a globe: the same vector in the same frame, which is a statement about
     * what "world-anchored" means on a sphere rather than a null result.
     *
     * The expected value is `viewBasis * SCENE_LIGHT_DIRECTION_ENU` with both halves written out here
     * from the azimuth, the elevation, the bearing and the pitch. The second assertion — that a
     * different bearing gives a different answer — is what stops the first from passing against a
     * light that ignored the camera entirely, which is exactly the camera-anchored light ADR 0026
     * rejected.
     */
    @Test
    fun theWorldAnchoredLightIsOneExpressionInBothProjectionModes() {
        val bearing = 37.5
        val pitch = 52.0
        val globe = globeCamera(48.5, 2.3, 13.7, bearing, pitch)
        val mercator = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(Camera(48.5, 2.3, 13.7, bearing, pitch), OUTPUT),
        ).value

        val azimuth = 335.0.toRadians()
        val elevation = 45.0.toRadians()
        val lightEastNorthUp = DoubleVector3(
            x = sin(azimuth) * cos(elevation),
            y = cos(azimuth) * cos(elevation),
            z = sin(elevation),
        )
        val expected = viewBasis(bearing, pitch) * lightEastNorthUp
        val actual = sceneLightDirectionCameraSpace(globe)

        assertClose(expected.x, actual[0].toDouble(), tolerance = 1e-6, message = "light x")
        assertClose(expected.y, actual[1].toDouble(), tolerance = 1e-6, message = "light y")
        assertClose(expected.z, actual[2].toDouble(), tolerance = 1e-6, message = "light z")

        val underMercator = sceneLightDirectionCameraSpace(mercator)
        for (index in 0..2) {
            assertTrue(
                underMercator[index] == actual[index],
                "the lens is shared, component $index: ${underMercator[index]} vs ${actual[index]}",
            )
        }

        val turned = sceneLightDirectionCameraSpace(globeCamera(48.5, 2.3, 13.7, bearing + 90.0, pitch))
        assertTrue(
            (0..2).any { abs(turned[it] - actual[it]) > 0.1f },
            "a world-anchored light must move in camera space when the camera turns",
        )
    }

    private fun resolveGlobe(placement: Placement, camera: ResolvedGlobeCamera): GlobePlacement =
        assertIs<SpatialOutcome.Success<GlobePlacement>>(
            resolveGlobePlacement(placement, camera),
        ).value

    private fun globeCamera(
        latitude: Double,
        unwrappedLongitude: Double,
        zoom: Double,
        bearing: Double = 0.0,
        pitch: Double = 0.0,
    ): ResolvedGlobeCamera = assertIs<SpatialOutcome.Success<ResolvedGlobeCamera>>(
        resolveGlobeCamera(Camera(latitude, unwrappedLongitude, zoom, bearing, pitch), OUTPUT),
    ).value

    /**
     * Where a local-space point lands on screen, applying the column-major matrix, the perspective
     * divide and the viewport transform by hand — the three steps the GPU would take.
     */
    private fun pixelOf(columnMajor: FloatArray, point: DoubleVector3): Pair<Double, Double> {
        fun row(index: Int): Double =
            columnMajor[index].toDouble() * point.x +
                columnMajor[index + 4].toDouble() * point.y +
                columnMajor[index + 8].toDouble() * point.z +
                columnMajor[index + 12].toDouble()

        val w = row(3)
        return Pair(
            (row(0) / w + 1.0) * 0.5 * WIDTH,
            (1.0 - row(1) / w) * 0.5 * HEIGHT,
        )
    }

    /** Right, up and back as rows, in the anchor's east/north/up frame, from bearing and pitch. */
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

    private fun assertClose(
        expected: Double,
        actual: Double,
        tolerance: Double = 1e-2,
        message: String = "value",
    ) {
        assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected but was $actual")
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val WIDTH: Int = 960
        const val HEIGHT: Int = 540
        val OUTPUT: OutputPixelSize = OutputPixelSize(width = WIDTH, height = HEIGHT)

        /** WGS84's semi-major axis, the datum both projections are defined against. */
        const val EARTH_SEMI_MAJOR_AXIS_METRES: Double = 6378137.0
    }
}
