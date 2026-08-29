package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
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
 * changes; the basis does. [ResolvedFrameCamera.right], [ResolvedFrameCamera.cameraUp] and
 * [ResolvedFrameCamera.cameraBack] are expressed in the camera anchor's east/north/up frame — the
 * same three rows `internal.planning.resolvePlacement` assembles for a map-anchored rotation — so
 * multiplying a world-ENU direction by that basis converts it to camera space. As the camera orbits,
 * the basis rotates and the camera-space light rotates with it, which is exactly what keeps the lit
 * side of a model facing the same compass direction. A camera-anchored light would instead be a
 * constant here and would make a model's shading swim as the camera moves around it.
 *
 * **It takes a [ResolvedFrameCamera] rather than a Mercator one, and that is a statement rather than
 * a convenience.** Those three vectors are the mode-independent half of a resolved camera, so this
 * expression is unchanged on a globe — but what it *means* there is worth saying out loud, because
 * "world-anchored" reads differently on a sphere. The anchor frame is the camera's own ground
 * anchor's east/north/up, so the light is north-west **at whatever the camera is looking at**, not
 * fixed in globe-fixed space. That is the cartographic reading rather than the astronomical one, and
 * it is the right one here: [SCENE_LIGHT_AZIMUTH_DEGREES] is a *compass* bearing, compass bearings
 * are inherently local, and ADR 0026 took the number from `hillshade-illumination-direction` so that
 * model shading and terrain hillshading would agree — hillshading being computed per tile against
 * local north. A globe-fixed light would instead leave one limb of the planet unlit and turn RenG's
 * one light into a time of day nobody asked for.
 *
 * A model's own placement rotation is deliberately **not** applied: the light is anchored to the
 * world, not to the object, so turning an object turns which of its faces are lit.
 *
 * The basis is orthonormal, so the result is still a unit vector and the shader normalizes only its
 * interpolated normal.
 */
internal fun sceneLightDirectionCameraSpace(camera: ResolvedFrameCamera): FloatArray {
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


/**
 * [value] as a GLSL float literal at a fixed nine decimal places.
 *
 * **Deterministic across every published target, which `Double.toString` is not.** Kotlin's own
 * formatting is free to choose a different shortest round-tripping decimal on the JVM and on Native,
 * and a shader whose *text* differs between two targets derives a different `ResourceKey` under ADR
 * 0018 for the identical program. This builds the digits out of a rounded `Long` instead, so the
 * same constant produces the same characters everywhere.
 *
 * Nine places is far more than the roughly seven decimal digits a GLSL `highp float` can hold, so
 * nothing is lost to the truncation that is not lost to the narrowing anyway.
 */
internal fun glslFloatLiteral(value: Double): String {
    require(value.isFinite()) { "a GLSL float literal is finite" }
    val scaled = (value * 1_000_000_000.0).roundToLong()
    val magnitude = if (scaled < 0L) -scaled else scaled
    val sign = if (scaled < 0L) "-" else ""
    return "$sign${magnitude / 1_000_000_000L}." +
        (magnitude % 1_000_000_000L).toString().padStart(9, '0')
}

/**
 * [SCENE_LIGHT_DIRECTION_ENU] as a GLSL constructor, for a shader that lights a surface in the
 * surface's **own** east/north/up frame rather than in camera space.
 *
 * [sceneLightDirectionCameraSpace] is the other half of ADR 0026 and exists because a model is one
 * object at one place, so its whole shading is resolved against the camera anchor's frame once. The
 * ground is not one place: it is the map, and its local north differs across a globe frame. So the
 * ground lights per surface point against that point's own local frame, which is the *cartographic*
 * reading the ADR already commits to — [SCENE_LIGHT_AZIMUTH_DEGREES] is a compass bearing, compass
 * bearings are local, and the number was taken from `hillshade-illumination-direction`, which is
 * computed per tile against local north. Under Mercator every local frame is parallel and the two
 * readings coincide exactly; on a sphere they do not, and the local one is the one that keeps the
 * whole planet lit from the north-west instead of turning the light into a time of day.
 */
internal val SCENE_LIGHT_DIRECTION_ENU_GLSL: String =
    "vec3(" + glslFloatLiteral(SCENE_LIGHT_DIRECTION_ENU.x) + ", " +
        glslFloatLiteral(SCENE_LIGHT_DIRECTION_ENU.y) + ", " +
        glslFloatLiteral(SCENE_LIGHT_DIRECTION_ENU.z) + ")"

/**
 * The incidence a surface with **no relief** has under [SCENE_LIGHT_DIRECTION_ENU]: the light's own
 * up component, because a flat ground's normal is `(0, 0, 1)` exactly.
 *
 * Written as the same literal the direction's `z` is written as, deliberately: a shader that
 * subtracts this from a flat surface's own `dot(normal, light)` gets exactly `0.0` rather than an
 * ulp, which is what makes "terrain shading leaves ground with no relief byte-identical" a fact
 * about IEEE arithmetic instead of a tolerance.
 */
internal val SCENE_LIGHT_FLAT_INCIDENCE_GLSL: String =
    glslFloatLiteral(SCENE_LIGHT_DIRECTION_ENU.z)

/**
 * How much a surface's own colour moves per unit of incidence away from the flat datum:
 * `diffuse / (ambient + diffuse * flatIncidence)`.
 *
 * **This is ADR 0026's light divided by what it does to flat ground, and the division is the
 * decision worth reading twice.** Applied raw, ADR 0026's `ambient + diffuse * incidence` renders a
 * dead-flat map at 0.81 of its own colour, so switching terrain shading on would darken every pixel
 * of every style — including the 28 of 34 that declare no `terrain` at all — which is precisely the
 * "light the style never requested" this option's default exists to avoid. Normalising by the flat
 * value keeps the light, the azimuth, the elevation and the ambient term exactly as the ADR fixes
 * them and moves only the datum: flat ground is left alone and relief is what changes.
 *
 * It is also what hillshading does — a highlight and a shadow either side of the unshaded map — and
 * ADR 0026 took its azimuth from `hillshade-illumination-direction` so the two would agree.
 *
 * The resulting factor spans about `[0.43, 1.24]`, so it is never negative and needs no lower clamp;
 * the upper end is why the shaded fragment stage clamps against the texel's own alpha rather than
 * letting a highlight break the premultiplied invariant.
 */
internal val SCENE_LIGHT_RELIEF_GAIN_GLSL: String = glslFloatLiteral(
    SCENE_LIGHT_DIFFUSE.toDouble() /
        (SCENE_LIGHT_AMBIENT.toDouble() + SCENE_LIGHT_DIFFUSE.toDouble() * SCENE_LIGHT_DIRECTION_ENU.z),
)
