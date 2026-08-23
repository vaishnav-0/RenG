package com.rohittp.reng.internal.gl

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR 0026's one light, and the two numbers that ADR deliberately left to implementation.
 *
 * The ADR fixes the direction — directional, azimuth 335 degrees, elevation 45 degrees,
 * world-anchored — and commits to "an ambient term" without a value. [SCENE_LIGHT_AMBIENT] and
 * [SCENE_LIGHT_DIFFUSE] are that value, recorded here rather than discovered as a constant inside a
 * shader, because changing either changes every consumer's pixels.
 */
class SceneLightTest {
    @Test fun theLightPointsFromTheNorthWestAtFortyFiveDegrees() {
        // Azimuth 335 deg clockwise from north, elevation 45 deg, as a unit direction *toward* the
        // light in east/north/up: (sin(az)cos(el), cos(az)cos(el), sin(el)).
        //
        // `x` is `sin(335 deg) * cos(45 deg)` = -0.2988362387..., not the -0.29886 the plan quoted;
        // the other two components the plan quoted round correctly. The arithmetic wins over the
        // transcription, and the discrepancy is 2.4e-5 — larger than the tolerance, so it had to be
        // resolved rather than absorbed.
        val d = SCENE_LIGHT_DIRECTION_ENU
        assertEquals(-0.298836, d.x, 1e-5)
        assertEquals(0.64086, d.y, 1e-5)
        assertEquals(0.70711, d.z, 1e-5)
        assertEquals(1.0, sqrt(d.dot(d)), 1e-12)
    }

    /**
     * North-west, not north-east. A sign flip on `x` is the single most likely transcription error
     * here and produces a picture that is merely lit from the wrong side — legible only against
     * this assertion, since 335 degrees and 25 degrees have identical elevation and identical
     * northward component.
     */
    @Test fun theLightComesFromTheWestSideRatherThanTheEastSide() {
        assertTrue(SCENE_LIGHT_DIRECTION_ENU.x < 0.0, "azimuth 335 deg is west of north")
        assertTrue(SCENE_LIGHT_DIRECTION_ENU.y > 0.0, "azimuth 335 deg is north of the camera anchor")
        assertTrue(SCENE_LIGHT_DIRECTION_ENU.z > 0.0, "a light below the horizon lights nothing")
    }

    @Test fun theAmbientAndDiffuseTermsSumToOneSoAFullyLitSurfaceReachesItsOwnColour() {
        assertEquals(1.0f, SCENE_LIGHT_AMBIENT + SCENE_LIGHT_DIFFUSE, 1e-6f)
    }

    /**
     * The shader is handed [SCENE_LIGHT_AMBIENT] alone and derives the diffuse weight as
     * `1 - ambient`, which is only the recorded [SCENE_LIGHT_DIFFUSE] because the two sum to one.
     * Asserting the individual values as well as the sum stops a future edit from moving both by
     * the same amount and leaving the sum test green while every model changes brightness.
     */
    @Test fun theAmbientAndDiffuseTermsAreTheRecordedValuesAndNotMerelyComplementary() {
        assertEquals(0.35f, SCENE_LIGHT_AMBIENT, 1e-6f)
        assertEquals(0.65f, SCENE_LIGHT_DIFFUSE, 1e-6f)
    }

    @Test fun theLightStaysPutInTheWorldAsTheCameraOrbits() {
        // World-anchored (ADR 0026): the camera-space direction must rotate with bearing, which is
        // exactly what keeps the lit side of a model facing the same compass direction.
        val north = sceneLightDirectionCameraSpace(cameraAt(bearing = 0.0))
        val east = sceneLightDirectionCameraSpace(cameraAt(bearing = 90.0))
        assertFalse(north.contentEquals(east), "a camera-anchored light would give the same vector at both")
    }

    /**
     * The mechanism, not merely the symptom. At bearing zero and pitch zero the view basis is
     * `right = +east`, `up = +north`, `back = +up`, so the camera-space light is the ENU vector
     * component for component. Pinning that identity is what makes the bearing test above evidence
     * of a rotating *basis* rather than of any arbitrary bearing-dependent scramble.
     */
    @Test fun aNorthFacingUnpitchedCameraSeesTheLightInItsOwnEastNorthUpComponents() {
        val cameraSpace = sceneLightDirectionCameraSpace(cameraAt(bearing = 0.0, pitch = 0.0))
        assertEquals(SCENE_LIGHT_DIRECTION_ENU.x.toFloat(), cameraSpace[0], 1e-6f)
        assertEquals(SCENE_LIGHT_DIRECTION_ENU.y.toFloat(), cameraSpace[1], 1e-6f)
        assertEquals(SCENE_LIGHT_DIRECTION_ENU.z.toFloat(), cameraSpace[2], 1e-6f)
    }

    /**
     * A rotation preserves length, so the camera-space light stays a unit vector at every bearing
     * and pitch. The fragment shader normalizes its interpolated normal and not this vector, so a
     * basis that quietly scaled would scale every model's diffuse term with it.
     */
    @Test fun theCameraSpaceLightIsStillAUnitVectorAtAnyBearingAndPitch() {
        listOf(0.0 to 0.0, 90.0 to 30.0, 217.0 to 59.0, 335.0 to 45.0).forEach { (bearing, pitch) ->
            val d = sceneLightDirectionCameraSpace(cameraAt(bearing = bearing, pitch = pitch))
            val length = sqrt((d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).toDouble())
            assertEquals(1.0, length, 1e-6, "bearing $bearing pitch $pitch")
        }
    }

    private fun cameraAt(bearing: Double, pitch: Double = 45.0): ResolvedMercatorCamera {
        val outcome = resolveMercatorCamera(
            camera = Camera(
                latitude = 12.0,
                unwrappedLongitude = 77.0,
                zoom = 14.0,
                bearing = bearing,
                pitch = pitch,
            ),
            outputPixelSize = OutputPixelSize(width = 128, height = 64),
        )
        return (outcome as SpatialOutcome.Success).value
    }
}
