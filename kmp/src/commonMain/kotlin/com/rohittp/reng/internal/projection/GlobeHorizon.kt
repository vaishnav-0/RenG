package com.rohittp.reng.internal.projection

import com.rohittp.reng.internal.math.DoubleVector3
import kotlin.math.sqrt

/**
 * The plane through the globe's horizon circle, in [projectGlobe]'s own globe-fixed frame: the
 * sphere's centre at the origin, axes as [unitSphereDirection] produces them, distances in logical
 * pixels.
 *
 * ## What it is, derived rather than asserted
 *
 * A camera sits at `C`, a distance `d = |C|` from the centre of a sphere of radius `R`, with
 * `d > R`. A point `P` **on** that sphere has outward normal `P / R`, so the surface there faces the
 * camera exactly when the vector from the point to the camera has a positive component along it:
 *
 * ```
 * (C - P) . P > 0   <=>   C . P > R^2
 * ```
 *
 * Dividing by `d` turns that into a plane test with a unit normal — the boundary `C . P = R^2` is
 * the plane whose normal is the camera direction `C / d` and whose distance from the centre is
 * `R^2 / d`, which is precisely the plane containing the circle where the tangent lines from `C`
 * touch the sphere. So a point is visible exactly when
 *
 * ```
 * X . (C / d) >= R^2 / d
 * ```
 *
 * — [normal] is `C / d`, [offsetLogicalPixels] is `R^2 / d`, and
 * [signedDistanceLogicalPixels] is the left side minus the right, in logical pixels.
 *
 * ## What it is **not**: the sign of `ScreenProjection`'s `w`
 *
 * ADR 0038 records this, and both this cycle's specification and its plan said the wrong thing first
 * — so it is repeated here, next to the arithmetic. [ScreenProjection.Projected.w] is documented as
 * *"exactly `-z_view`: the distance in front of the camera plane, in logical pixels"*, and it is
 * *"always at least [NEAR_DISTANCE_LOGICAL_PIXELS] here"*. A camera outside a sphere has the **whole
 * sphere** in front of it, near hemisphere and far, so the antipodal point — the single most
 * thoroughly hidden point on the planet — carries a `w` of `d + R`: the **largest** positive `w` any
 * point of the globe can have, and it also projects to the exact centre of the frame, where a
 * viewport test is no help either. A horizon test written as `w < 0` culls nothing at all while
 * looking correct. `w` answers "is this behind the camera"; this plane answers "is this behind the
 * planet", and the two questions are different.
 *
 * ## Exact on the surface, deliberately conservative above it
 *
 * For `|X| = R` the test is exact: the plane is where the visible cap ends. For `|X| > R` — a
 * placement at altitude — it is **conservative in one direction only**. Occlusion (the segment from
 * the camera to the point crossing the sphere) implies being behind this plane, so nothing genuinely
 * hidden survives; but the converse fails, because the shadow region is the tangent *cone* rather
 * than the whole half-space, and a sufficiently high point behind the plane is really visible
 * peeking over the limb. Culling it is ADR 0038's accepted gap, stated there as "a tall model just
 * beyond the horizon is culled entirely rather than having its top drawn", and it is a consequence
 * of a **Placement** resolving to one anchor rather than of this arithmetic: every fix for it is one
 * of the two alternatives ADR 0038 rejected.
 */
internal class GlobeLimbPlane internal constructor(
    val normal: DoubleVector3,
    val offsetLogicalPixels: Double,
) {
    /**
     * How far [globeFixedPosition] lies on the camera's side of the limb plane, in logical pixels.
     * Positive is visible, negative is beyond the horizon, and zero is the limb itself.
     */
    fun signedDistanceLogicalPixels(globeFixedPosition: DoubleVector3): Double =
        normal.dot(globeFixedPosition) - offsetLogicalPixels

    /**
     * Whether [globeFixedPosition] is hidden by the planet.
     *
     * Written as `!(distance >= 0)` rather than `distance < 0` so a non-finite position is culled
     * instead of silently surviving: `NaN < 0` is false, and a `NaN` anchor that survived here would
     * go on to produce a `NaN` pixel, which compares false against every viewport bound and so
     * survives a viewport cull too. The limb is inclusive — a point exactly on it is visible — which
     * matches the sphere, where the limb is the last lit ring rather than the first dark one.
     */
    fun isBeyondHorizon(globeFixedPosition: DoubleVector3): Boolean =
        !(signedDistanceLogicalPixels(globeFixedPosition) >= 0.0)
}

/**
 * The limb plane for a camera at [cameraPositionFromGlobeCentre], or `null` when the globe hides
 * nothing from that camera.
 *
 * `null` is returned when the camera is not strictly outside the sphere, or when either input is not
 * a usable number. That case is not merely degenerate, it is **actively dangerous**: at `d <= R` the
 * offset `R^2 / d` is at least `R`, so a plane test would put the *entire* sphere on the far side
 * and cull every placement on the planet. Returning `null` — and, through
 * [isBeyondGlobeHorizon], culling nothing — keeps the failure mode "the far side is drawn" rather
 * than "the map is empty", which is the direction a renderer should fail in. RenG's camera never
 * reaches the surface (the eye stands `outputPixelSize.height * (1 + sqrt 2) / 2` logical pixels off
 * it at every zoom, because the globe's radius grows with the world size and the eye height does
 * not), so this is a guard rather than a supported mode.
 */
internal fun globeLimbPlane(
    cameraPositionFromGlobeCentre: DoubleVector3,
    radiusLogicalPixels: Double,
): GlobeLimbPlane? {
    // Three guards, each load-bearing and none of them a spare: the first rejects a radius that is
    // zero, negative or NaN; the second an infinite camera, which would otherwise pass the third and
    // build a plane with a NaN normal; the third a camera at or inside the surface. An infinite
    // *radius* needs no guard of its own -- no distance exceeds it, so the third catches it. Written
    // as negated comparisons so that NaN falls out on the null side rather than through.
    if (!(radiusLogicalPixels > 0.0)) return null
    val distanceSquared = cameraPositionFromGlobeCentre.dot(cameraPositionFromGlobeCentre)
    if (!distanceSquared.isFinite()) return null
    val distance = sqrt(distanceSquared)
    if (!(distance > radiusLogicalPixels)) return null
    return GlobeLimbPlane(
        normal = cameraPositionFromGlobeCentre * (1.0 / distance),
        offsetLogicalPixels = radiusLogicalPixels * radiusLogicalPixels / distance,
    )
}

/**
 * The whole horizon test for one position, for a caller that holds no plane.
 *
 * A frame with many placements should build the plane once with [globeLimbPlane] and ask it
 * directly — the plane is a property of the camera, not of the placement, and rebuilding it per
 * placement pays a square root each time. This exists so that the "no plane means cull nothing"
 * ruling lives in exactly one place rather than at every call site.
 */
internal fun isBeyondGlobeHorizon(
    cameraPositionFromGlobeCentre: DoubleVector3,
    globeFixedPosition: DoubleVector3,
    radiusLogicalPixels: Double,
): Boolean {
    val plane = globeLimbPlane(cameraPositionFromGlobeCentre, radiusLogicalPixels) ?: return false
    return plane.isBeyondHorizon(globeFixedPosition)
}

/**
 * Whether the globe hides [position] from [camera], for a caller that holds a geographic position
 * rather than a globe-fixed one.
 *
 * The plane comes from [ResolvedGlobeCamera.limbPlane], derived once when the camera was resolved,
 * so this costs one [projectGlobe] and a dot product per call and no square root at all.
 *
 * It re-projects rather than taking a position the caller has already projected, deliberately. The
 * two callers that need it — `internal.planning.resolveGlobePlacement`, which holds the globe-fixed
 * point already and therefore does **not** use this, and [projectVisibleGeographicPosition], which
 * holds only a [ScreenProjection] — have nothing in common to hand over, and a variant taking a
 * screen pixel could not answer the question at all: an antipodal position projects to a perfectly
 * good pixel in the middle of the frame.
 *
 * [position] is expected to have passed [validateMercatorMapPosition] already, exactly as
 * [projectGlobe]'s geographic overload requires.
 */
internal fun isBeyondGlobeHorizon(
    camera: ResolvedGlobeCamera,
    position: GeographicPosition,
): Boolean =
    camera.limbPlane?.isBeyondHorizon(projectGlobe(position, camera.radiusLogicalPixels)) ?: false
