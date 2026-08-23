package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ADR 0026's one light: directional, world-anchored, at azimuth 335 degrees and elevation 45
 * degrees, with an ambient term. Every model RenG draws is shaded by it; stickers, geometries and
 * the ground stay unlit.
 *
 * **Azimuth 335 degrees is not a taste decision.** It is the cartographic convention for relief
 * shading and MapLibre's own `hillshade-illumination-direction` default, taken so that model shading
 * and terrain hillshading agree by construction when terrain lands rather than RenG carrying two
 * lights pointing different ways. Light from the north-west also avoids the inversion illusion in
 * which hills read as valleys.
 */
internal const val SCENE_LIGHT_AZIMUTH_DEGREES: Double = 335.0

/** Elevation above the horizon, in degrees. See [SCENE_LIGHT_AZIMUTH_DEGREES]. */
internal const val SCENE_LIGHT_ELEVATION_DEGREES: Double = 45.0

/**
 * The unit direction **toward** the light, in the east/north/up frame of the camera's own ground
 * anchor: `(sin(azimuth)cos(elevation), cos(azimuth)cos(elevation), sin(elevation))`, with azimuth
 * measured clockwise from north as compass bearings are.
 *
 * Toward the light rather than along its travel, so the diffuse term is a plain `dot(normal, light)`
 * with no sign to get wrong at the one place it would silently invert every model's shading.
 */
internal val SCENE_LIGHT_DIRECTION_ENU: DoubleVector3 = run {
    val azimuth = SCENE_LIGHT_AZIMUTH_DEGREES * PI / 180.0
    val elevation = SCENE_LIGHT_ELEVATION_DEGREES * PI / 180.0
    DoubleVector3(
        x = sin(azimuth) * cos(elevation),
        y = cos(azimuth) * cos(elevation),
        z = sin(elevation),
    )
}

/**
 * The fraction of a surface's own colour that reaches the frame with no light on it at all.
 *
 * ADR 0026 committed to "an ambient term so that a surface facing away from it stays readable
 * instead of going black against a bright basemap" and left the number to implementation. This is
 * that number, recorded once here rather than discovered later as an unexplained constant inside a
 * shader — changing it changes every consumer's pixels, which is precisely why the ADR exists.
 */
internal const val SCENE_LIGHT_AMBIENT: Float = 0.35f

/**
 * The fraction the directional term contributes at full incidence.
 *
 * [SCENE_LIGHT_AMBIENT] and this sum to one, so a surface facing the light square-on reaches its own
 * colour and nothing brighter — no model is ever blown out by RenG's own light. The model fragment
 * shader is handed the ambient term alone and derives this as `1 - ambient`, which is only correct
 * *because* of that sum.
 *
 * Written as its own literal rather than as `1 - SCENE_LIGHT_AMBIENT`, deliberately. Defining it
 * from the other constant would make `SceneLightTest`'s sum assertion true by construction — green
 * against every possible edit to either number, and therefore evidence of nothing.
 */
internal const val SCENE_LIGHT_DIFFUSE: Float = 0.65f

/**
 * [SCENE_LIGHT_DIRECTION_ENU] expressed in [camera]'s own camera space, as three floats ready for
 * `rengModelLightDirection`.
 *
 * **This is the whole of the world-anchoring mechanism (ADR 0026).** The light vector itself never
 * changes; the basis does. [ResolvedMercatorCamera.right], [ResolvedMercatorCamera.cameraUp] and
 * [ResolvedMercatorCamera.cameraBack] are expressed in the camera anchor's east/north/up frame — the
 * same three rows `internal.planning.resolvePlacement` assembles for a map-anchored rotation — so
 * multiplying a world-ENU direction by that basis converts it to camera space. As the camera orbits,
 * the basis rotates and the camera-space light rotates with it, which is exactly what keeps the lit
 * side of a model facing the same compass direction. A camera-anchored light would instead be a
 * constant here and would make a model's shading swim as the camera moves around it.
 *
 * A model's own placement rotation is deliberately **not** applied: the light is anchored to the
 * world, not to the object, so turning an object turns which of its faces are lit.
 *
 * The basis is orthonormal, so the result is still a unit vector and the shader normalizes only its
 * interpolated normal.
 */
internal fun sceneLightDirectionCameraSpace(camera: ResolvedMercatorCamera): FloatArray {
    val viewBasis = DoubleMatrix3.fromRows(
        listOf(
            listOf(camera.right.x, camera.right.y, camera.right.z),
            listOf(camera.cameraUp.x, camera.cameraUp.y, camera.cameraUp.z),
            listOf(camera.cameraBack.x, camera.cameraBack.y, camera.cameraBack.z),
        ),
    )
    val cameraSpace = viewBasis * SCENE_LIGHT_DIRECTION_ENU
    return floatArrayOf(cameraSpace.x.toFloat(), cameraSpace.y.toFloat(), cameraSpace.z.toFloat())
}
