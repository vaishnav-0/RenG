package com.rohittp.reng.internal.projection

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3

/**
 * The part of a resolved camera that is the **same expression** in both projection modes, so that
 * code depending on nothing else can be written once instead of twice.
 *
 * [ResolvedGlobeCamera]'s own KDoc records why this set exists: "the **lens** does not change".
 * `cameraOrientation`, `cameraDistanceLogicalPixels`, `cameraViewMatrix` and
 * `cameraProjectionMatrix` are literally the same four calls in [resolveMercatorCamera] and
 * [resolveGlobeCamera], so the reverse-Z projection, the one-logical-pixel near plane, the 45 degree
 * vertical field of view and logical pixels as the unit are shared rather than duplicated. What
 * differs between the modes is **where a position is** — and that is deliberately *not* on this
 * interface, because every function that needs it has to choose a mode consciously.
 *
 * So the members here are exactly the ones whose meaning is mode-independent:
 *
 * - [outputPixelSize], which is the frame rather than the world.
 * - [right], [cameraUp] and [cameraBack], the orientation in **the anchor's** east/north/up frame.
 *   Under Mercator that frame is the whole plane; on a globe it is the frame at the camera's own
 *   ground anchor. Bearing and pitch mean the same thing in both, which is the premise
 *   `CameraOrientation` already states.
 * - [viewMatrix] and [projectionMatrix], which consume a position already expressed in that
 *   east/north/up frame in logical pixels — the frame `resolveCameraRelativeMapPosition` produces
 *   under Mercator and [globeCameraRelativePosition] produces on the globe.
 *
 * It is `sealed` so that a `when` over the two modes is exhaustive without an `else` branch: a third
 * projection would be a compile error at every site that has to decide, rather than a silently taken
 * default. Both implementations are `internal` data classes, so nothing here reaches the public ABI.
 *
 * **What this is not.** It is not a way to avoid choosing a projection. `resolvePlacement` and
 * `resolveGlobePlacement` deliberately keep their concrete camera types, because the whole content
 * of Cycle G is that those two arms differ.
 */
internal sealed interface ResolvedFrameCamera {
    val outputPixelSize: OutputPixelSize
    val right: DoubleVector3
    val cameraUp: DoubleVector3
    val cameraBack: DoubleVector3
    val viewMatrix: DoubleMatrix4
    val projectionMatrix: DoubleMatrix4
}
