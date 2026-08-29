package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [projectVisibleGeographicPosition] — the one function the label path gained for the globe.
 *
 * The whole content of these cases is the distinction the cycle's own spec and plan both got wrong
 * first: **being in front of the camera and being in front of the planet are different questions.**
 * The antipode answers "yes" to the first with the largest `w` on the globe and a pixel dead centre,
 * and "no" to the second.
 */
class VisibleProjectionTest {
    /**
     * **No mercator pixel moves.** The mercator arm of the new function is
     * [projectGeographicPosition] and nothing else, asserted by equality over three positions that
     * exercise all three of its outcomes — a point in front of the camera, one behind the near plane,
     * and one outside the Mercator domain — rather than over one that only exercises the first.
     */
    @Test
    fun underMercatorTheVisibleProjectionIsThePlainOneForEveryOutcome() {
        val camera = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(Camera(20.0, 10.0, 2.0, 15.0, 55.0), OUTPUT),
        ).value

        val positions = listOf(
            GeographicPosition(20.5, 10.5, 0.0),
            GeographicPosition(-80.0, 10.0, 0.0),
            GeographicPosition(-20.0, -170.0, 0.0),
            GeographicPosition(86.0, 10.0, 0.0),
        )
        val outcomes = positions.map { projectGeographicPosition(camera, it) }

        assertTrue(
            outcomes.any { it is ScreenProjection.Projected } &&
                outcomes.any { it is ScreenProjection.BehindNearPlane } &&
                outcomes.any { it is ScreenProjection.OutsideSupportedDomain },
            "the fixture must reach all three outcomes or it proves nothing about equality: $outcomes",
        )
        for ((index, position) in positions.withIndex()) {
            assertEquals(
                outcomes[index],
                projectVisibleGeographicPosition(camera, position),
                "mercator position $position",
            )
        }
    }

    /**
     * The globe drops what the planet hides, and the numbers show that nothing about the projection
     * itself could have told it so.
     *
     * The antipode's plain projection is asserted first, as a fact: `w` is `2 * radius +
     * cameraDistance`, the largest any point of the globe can carry, and the pixel is the exact
     * centre of the frame. A horizon test written as the sign of `w`, or as a viewport test, keeps
     * it. Both numbers are derived here from the sphere's own geometry rather than read off the
     * camera.
     */
    @Test
    fun onAGlobeTheAntipodeIsDroppedThoughItsDepthAndPixelAreImpeccable() {
        val latitude = 20.0
        val longitude = 10.0
        val zoom = 2.0
        val camera = globeCamera(latitude, longitude, zoom)
        val antipode = GeographicPosition(-latitude, longitude - 180.0, 0.0)

        val radius = 512.0 * 2.0.pow(zoom - log2(cos(latitude * PI / 180.0))) / (2.0 * PI)
        val cameraDistance = OUTPUT.height.toDouble() / (2.0 * tan(PI / 8.0))
        val plain = assertIs<ScreenProjection.Projected>(projectGeographicPosition(camera, antipode))

        assertClose(2.0 * radius + cameraDistance, plain.w, tolerance = 1e-6)
        assertClose(OUTPUT.width / 2.0, plain.pixelX, tolerance = 1e-6)
        assertClose(OUTPUT.height / 2.0, plain.pixelY, tolerance = 1e-6)

        assertEquals(
            ScreenProjection.OutsideSupportedDomain,
            projectVisibleGeographicPosition(camera, antipode),
        )
        assertTrue(isBeyondGlobeHorizon(camera, antipode), "and the limb plane is what says so")
    }

    /**
     * The other half of the same statement: inside the limb the two functions agree exactly, so the
     * globe arm drops what is hidden and nothing else.
     *
     * The fixture sits 56 degrees from the camera's own sub-point against a limb at 68.9, which is
     * far enough out that the sphere has visibly curved and still inside — a fixture under the camera
     * would be green against a cull that fired at any angle at all.
     */
    @Test
    fun onAGlobeAPositionInsideTheLimbProjectsIdentically() {
        val camera = globeCamera(20.0, 10.0, 2.0)
        val insideTheLimb = GeographicPosition(20.0, 70.0, 0.0)

        val plain = assertIs<ScreenProjection.Projected>(projectGeographicPosition(camera, insideTheLimb))

        assertTrue(!isBeyondGlobeHorizon(camera, insideTheLimb), "the fixture must be inside the limb")
        assertEquals(plain, projectVisibleGeographicPosition(camera, insideTheLimb))
        assertTrue(
            abs(plain.pixelX - OUTPUT.width / 2.0) > 100.0,
            "and far enough off centre that a cull at any angle would have shown",
        )
    }

    private fun globeCamera(
        latitude: Double,
        unwrappedLongitude: Double,
        zoom: Double,
    ): ResolvedGlobeCamera = assertIs<SpatialOutcome.Success<ResolvedGlobeCamera>>(
        resolveGlobeCamera(Camera(latitude, unwrappedLongitude, zoom, 0.0, 0.0), OUTPUT),
    ).value

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        val scale = maxOf(abs(expected), 1.0)
        assertTrue(abs(expected - actual) <= tolerance * scale, "expected $expected but was $actual")
    }

    private companion object {
        val OUTPUT: OutputPixelSize = OutputPixelSize(width = 853, height = 509)
    }
}
