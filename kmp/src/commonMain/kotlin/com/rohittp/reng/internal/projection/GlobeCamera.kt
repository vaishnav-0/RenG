package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * A camera that orbits the sphere [projectGlobe] places the world on, rather than hovering over a
 * plane.
 *
 * ## What is deliberately identical to [ResolvedMercatorCamera], and why
 *
 * The **lens** does not change: [cameraOrientation], [cameraDistanceLogicalPixels],
 * [cameraViewMatrix] and [cameraProjectionMatrix] are the same four expressions both modes call, so
 * the reverse-Z projection, the one-logical-pixel near plane, the 45 degree vertical field of view
 * and **logical pixels as the unit** survive verbatim. Section 3 of
 * `docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` settles the last of those by
 * arithmetic: the depth buffer resolves 0.025 logical pixels in RenG's unit and 2.4 x 10^6 metres in
 * ECEF metres at earth radius, so a globe's invitation to a metric frame is one that destroys the
 * depth buffer.
 *
 * What changes is **where a position is**, not how it is photographed. [Camera] keeps its five
 * fields and their meanings.
 *
 * ## The frame, and how it composes task 1's primitive
 *
 * [projectGlobe] produces a **globe-fixed** position -- sphere centre at the origin, `+z` through
 * the north pole, `+x` through `(0 N, 0 E)`, in logical pixels. This camera adds the one thing task
 * 1 deliberately left out: the rotation and translation that turn that into the **camera-relative
 * east/north/up logical pixels** [cameraViewMatrix] already consumes, which is exactly the frame
 * `resolveCameraRelativeMapPosition` produces under Mercator. [globeFixedToCameraRelative] is that
 * transform, and every drawn thing goes through it.
 *
 * The anchor frame is derived from [anchorUp] rather than from `cos`/`sin` of the camera's
 * latitude, so the camera and the ground it looks at come out of one formulation: [anchorUp] *is*
 * [unitSphereDirection] of the camera's own Mercator anchor, and east falls out of it as
 * `normalize(polar x up)` with no trigonometry at all. The pole is the one place that construction
 * degenerates, and Mercator's own +/-85.0511 degree clip -- already applied by
 * [validateMercatorCamera] before this runs -- keeps `cos(latitude)` at or above 0.0862.
 *
 * **The translation is written as a subtraction of [radiusLogicalPixels] from the up component
 * alone**, never as `point - anchorSurfacePoint` in globe-fixed coordinates. Rotating first and
 * subtracting after means the cancellation of two numbers the size of the planet happens on one
 * axis instead of three, which matters because at zoom 22 the radius is about 3.9 x 10^9 logical
 * pixels while the quantity being kept -- the curvature drop under a screen -- is 10^-4 of one.
 *
 * ## Zoom, and what the LOD selector owes
 *
 * [effectiveZoom] is G1's `z_eff = zoom - log2 cos(latitude)`: the globe is scaled by
 * `1 / cos(latitude)` so that the ground scale at the camera's own latitude matches Mercator's at
 * the same [Camera.zoom]. That is what makes the tile count on a sphere match the plane's -- 4
 * rather than 132 at latitude 82 and zoom 6 -- and the naive alternative does not merely cost
 * tiles, it pushes a high-latitude phone camera past `maximumBasemapTileInstances` and fails the
 * frame closed. The accepted cost is that [Camera.zoom] becomes projection-dependent, recorded in
 * `CONTEXT.md`'s **Camera** entry.
 *
 * [worldSizeLogicalPixels] is `512 * 2^effectiveZoom`, the same expression
 * [resolveMercatorCamera] uses with the plain zoom, so `screenPixelsPerTexel = 2^(effectiveZoom -
 * lod)` continues to hold exactly and a LOD selector needs the substitution and nothing else.
 * **[effectiveZoom] is deliberately not clamped**: it is the true scale exponent and reaches 25.5 at
 * the Mercator clip, while `observeMercatorLod` requires its argument within `0..22`. Coercing it is
 * the LOD selector's job, and coercing the *scale* instead would move the picture.
 *
 * ## Unwrapped longitude is inert here, and carried anyway
 *
 * A sphere has no seam, so a camera at 400 degrees sees exactly what one at 40 degrees sees:
 * [unitSphereDirection] wraps its Mercator `x`, so [anchorUp] and everything derived from it are
 * identical for the two. The winding count survives in [geographicGroundAnchor] because it is what
 * keeps a consumer's own interpolation continuous across the antimeridian -- `CONTEXT.md`'s
 * **Camera** entry, which this must not contradict.
 */
internal data class ResolvedGlobeCamera(
    val outputPixelSize: OutputPixelSize,
    val mercatorAnchor: MercatorPosition,
    val geographicGroundAnchor: GeographicPosition,
    val effectiveZoom: Double,
    val worldSizeLogicalPixels: Double,
    val radiusLogicalPixels: Double,
    val anchorEast: DoubleVector3,
    val anchorNorth: DoubleVector3,
    val anchorUp: DoubleVector3,
    val right: DoubleVector3,
    val cameraUp: DoubleVector3,
    val cameraBack: DoubleVector3,
    val cameraDistanceLogicalPixels: Double,
    val globeFixedToCameraRelative: DoubleMatrix4,
    val viewMatrix: DoubleMatrix4,
    val projectionMatrix: DoubleMatrix4,
    val eyeGlobeFixed: DoubleVector3,
) {
    /**
     * The plane through this camera's horizon circle, or `null` when the globe hides nothing from
     * it -- [globeLimbPlane] applied to [eyeGlobeFixed] and [radiusLogicalPixels].
     *
     * It is derived **once per frame**, in the class body rather than in the constructor, for two
     * reasons. [GlobeLimbPlane]'s own KDoc says a frame with many placements must build the plane
     * once and ask it repeatedly, because the plane is a property of the camera and rebuilding it
     * per placement pays a square root each time. And a body `val` leaves the generated `equals`,
     * `hashCode`, `copy` and `componentN` untouched, so no existing caller of this data class moves.
     *
     * Being `null` means "cull nothing", never "cull everything" -- see [globeLimbPlane] for why
     * that direction is the safe one.
     */
    val limbPlane: GlobeLimbPlane? = globeLimbPlane(eyeGlobeFixed, radiusLogicalPixels)
}

/**
 * What a pixel is looking at on the globe: the inverse direction, and [physicalPixelGroundRay]'s
 * counterpart.
 */
internal sealed interface GlobeRayResult {
    /**
     * The ray passes the limb and leaves the scene. Under Mercator the same case is
     * [GroundRayResult.HorizonOrSky] and the ground it misses is infinite; here the missed body is
     * finite, so this is also what a ray pointing away from the planet returns.
     */
    data object BeyondLimb : GlobeRayResult

    /** The globe is in front of the camera but its near surface is closer than
     * [NEAR_DISTANCE_LOGICAL_PIXELS], so no pixel may be attributed to it. */
    data object NearClipped : GlobeRayResult

    /**
     * The near-side hit, as a normalised Mercator coordinate.
     *
     * [point] is **unwrapped into the camera's own world copy** -- the copy nearest
     * [ResolvedGlobeCamera.mercatorAnchor], which is unambiguous because a camera outside the
     * sphere sees strictly less than a hemisphere. It is **not** clipped: a ray that lands nearer a
     * pole than Mercator's own +/-85.0511 degrees yields a `y` outside `[0, 1]`, and one that lands
     * on the pole yields a non-finite one, exactly as [clippedPhysicalPixelFootprint] already
     * expects of [GroundRayResult.Hit] -- the caller applies [isWithinMercatorPlanningSupport].
     *
     * [t] is the distance in front of the camera plane, in logical pixels: the same quantity
     * [GroundRayResult.Hit.t] reports and the same quantity [ScreenProjection.Projected.w] carries
     * back in the forward direction, which is what makes the round trip between them testable.
     */
    data class Hit(val point: MercatorGroundPoint, val t: Double) : GlobeRayResult
}

/**
 * The east/north/up basis of one point on the sphere, as the columns of a rotation from that point's
 * local frame into the globe-fixed one -- `+z` through the north pole, `+x` through `(0 N, 0 E)`.
 *
 * It exists as a function because **two** callers need it and neither may derive it twice:
 * [resolveGlobeCamera] builds the camera's own anchor frame from it, and
 * `internal.planning.resolveGlobePlacement` builds a map-anchored placement's frame from it. A
 * second spelling of the same three lines is the shape of a defect that shows up as content facing
 * the wrong way only when it is far from the camera.
 *
 * There is no trigonometry here: [unitSphereDirection] gives `up` directly from the Mercator
 * coordinate, east is `normalize(polar x up)` written out, and north is `up x east`. The one
 * degeneracy is the pole, where `cos(latitude)` is zero -- Mercator's own +/-85.0511 degree clip,
 * which [validateMercatorCamera] and [validateMercatorMapPosition] both apply before anything
 * reaches here, keeps it at or above 0.0862.
 *
 * The columns are `(east, north, up)`, matching [wgs84LocalFrame]'s [Wgs84LocalFrame.basisEastNorthUp]
 * exactly. That agreement is not a coincidence and is worth stating: a geodetic latitude is *defined*
 * by the ellipsoid normal, so WGS84's ENU **basis** is the sphere's ENU basis for the same
 * `(latitude, longitude)` -- the eccentricity appears only in [Wgs84LocalFrame.ecefPosition], which
 * nothing on the globe path reads. What differs between the two modes is where a point *is*, never
 * which way is north.
 */
internal fun globeEastNorthUpBasis(mercatorX: Double, mercatorY: Double): DoubleMatrix3 {
    val up = unitSphereDirection(mercatorX, mercatorY)
    val east = DoubleVector3(-up.y, up.x, 0.0) * (1.0 / hypot(up.x, up.y))
    return DoubleMatrix3.fromColumns(east, up.cross(east), up)
}

internal fun resolveGlobeCamera(
    camera: Camera,
    outputPixelSize: OutputPixelSize,
): SpatialOutcome<ResolvedGlobeCamera> {
    val geographicGroundAnchor = GeographicPosition(
        latitude = camera.latitude,
        unwrappedLongitude = camera.unwrappedLongitude,
        altitudeMetres = 0.0,
    )
    val anchorOutcome = validateMercatorCamera(geographicGroundAnchor)
    if (anchorOutcome is SpatialOutcome.Failure) return anchorOutcome
    val mercatorAnchor = (anchorOutcome as SpatialOutcome.Success).value

    val anchorBasis = globeEastNorthUpBasis(mercatorAnchor.x, mercatorAnchor.y)
    val anchorEast = anchorBasis.column(0)
    val anchorNorth = anchorBasis.column(1)
    val anchorUp = anchorBasis.column(2)
    val cosineLatitude = hypot(anchorUp.x, anchorUp.y)

    val effectiveZoom = camera.zoom - log2(cosineLatitude)
    val worldSizeLogicalPixels = 512.0 * 2.0.pow(effectiveZoom)
    val radiusLogicalPixels = globeRadiusLogicalPixels(worldSizeLogicalPixels)

    val orientation = cameraOrientation(camera.bearing, camera.pitch)
    val cameraDistance = cameraDistanceLogicalPixels(outputPixelSize)
    val eyeCameraRelative = orientation.cameraBack * cameraDistance

    return SpatialOutcome.Success(
        ResolvedGlobeCamera(
            outputPixelSize = outputPixelSize,
            mercatorAnchor = mercatorAnchor,
            geographicGroundAnchor = geographicGroundAnchor,
            effectiveZoom = effectiveZoom,
            worldSizeLogicalPixels = worldSizeLogicalPixels,
            radiusLogicalPixels = radiusLogicalPixels,
            anchorEast = anchorEast,
            anchorNorth = anchorNorth,
            anchorUp = anchorUp,
            right = orientation.right,
            cameraUp = orientation.cameraUp,
            cameraBack = orientation.cameraBack,
            cameraDistanceLogicalPixels = cameraDistance,
            globeFixedToCameraRelative = DoubleMatrix4.fromRows(
                listOf(
                    listOf(anchorEast.x, anchorEast.y, anchorEast.z, 0.0),
                    listOf(anchorNorth.x, anchorNorth.y, anchorNorth.z, 0.0),
                    listOf(anchorUp.x, anchorUp.y, anchorUp.z, -radiusLogicalPixels),
                    listOf(0.0, 0.0, 0.0, 1.0),
                ),
            ),
            viewMatrix = cameraViewMatrix(orientation, cameraDistance),
            projectionMatrix = cameraProjectionMatrix(outputPixelSize),
            eyeGlobeFixed = anchorEast * eyeCameraRelative.x +
                anchorNorth * eyeCameraRelative.y +
                anchorUp * (eyeCameraRelative.z + radiusLogicalPixels),
        ),
    )
}

/**
 * A globe-fixed position in the camera's own east/north/up logical pixels: exactly
 * [ResolvedGlobeCamera.globeFixedToCameraRelative] applied to [globeFixedPosition], spelled as
 * three dot products because that is the only form in which the radius subtraction stays on one
 * axis.
 *
 * The matrix and this function are asserted to agree rather than one being derived from the other,
 * because the matrix is what a draw path uploads and this is what the CPU paths use, and a
 * disagreement between them would show up as content sliding off the ground it stands on.
 */
internal fun cameraRelativeLogicalPosition(
    camera: ResolvedGlobeCamera,
    globeFixedPosition: DoubleVector3,
): DoubleVector3 = DoubleVector3(
    x = camera.anchorEast.dot(globeFixedPosition),
    y = camera.anchorNorth.dot(globeFixedPosition),
    z = camera.anchorUp.dot(globeFixedPosition) - camera.radiusLogicalPixels,
)

/**
 * A normalised Mercator coordinate and an altitude, in the camera's east/north/up logical pixels.
 *
 * The Mercator-coordinate entry point rather than the geographic one is the primary one for the
 * same reason [unitSphereDirection]'s is: a ground-tile corner has a `y` and no latitude.
 */
internal fun globeCameraRelativePosition(
    camera: ResolvedGlobeCamera,
    mercatorX: Double,
    mercatorY: Double,
    altitudeMetres: Double,
): DoubleVector3 = cameraRelativeLogicalPosition(
    camera,
    projectGlobe(mercatorX, mercatorY, altitudeMetres, camera.radiusLogicalPixels),
)

/** [globeCameraRelativePosition] from `(latitude, longitude, altitude)`, for a position that has
 * already passed [validateMercatorMapPosition] or a sibling. */
internal fun globeCameraRelativePosition(
    camera: ResolvedGlobeCamera,
    position: GeographicPosition,
): DoubleVector3 =
    cameraRelativeLogicalPosition(camera, projectGlobe(position, camera.radiusLogicalPixels))

/**
 * The one matrix a draw path uploads to take a **globe-fixed** vertex all the way to clip space.
 *
 * It exists so that the multiplication order is written once. A ground patch or a subdivided
 * geometry that already holds globe-fixed vertices needs no CPU frame conversion per vertex, and
 * the transform stays linear -- which is what keeps ADR 0008's consumer shader contract intact,
 * since the nonlinearity was spent in [projectGlobe] before any vertex was handed over.
 */
internal fun globeFixedViewProjection(camera: ResolvedGlobeCamera): DoubleMatrix4 =
    camera.projectionMatrix * camera.viewMatrix * camera.globeFixedToCameraRelative

/**
 * The globe's counterpart to [physicalPixelGroundRay]: what the centre of physical pixel
 * ([pixelX], [pixelY]) is looking at.
 *
 * Solved in the camera's own east/north/up frame, where the sphere's centre sits at
 * `(0, 0, -radius)`, so nothing here touches [ResolvedGlobeCamera.globeFixedToCameraRelative]'s
 * rotation and a shared error in that rotation cannot cancel itself out of the round trip against
 * [projectGeographicPosition].
 *
 * **The quadratic is written for its cancellations rather than from the textbook.** Its constant
 * term is `|eye - centre|^2 - radius^2`, which as written subtracts two numbers that agree to nine
 * significant figures at high zoom; expanded against a unit [ResolvedGlobeCamera.cameraBack] it is
 * exactly `d^2 + 2 * radius * d * cameraBack.z`, with no cancellation at all and no dependence on
 * the radius being large or small. It is also visibly positive for every `pitch` under 90 degrees,
 * which is the statement that the camera is outside the sphere. The roots then use the
 * sign-stable `q = -(b +/- sqrt(discriminant)) / 2` form, whose smaller root is `c / q`.
 */
internal fun physicalPixelGlobeRay(
    camera: ResolvedGlobeCamera,
    pixelX: Int,
    pixelY: Int,
): GlobeRayResult {
    val ray = pixelRayCoordinates(camera.outputPixelSize, pixelX, pixelY)
    val direction = camera.right * ray.u + camera.cameraUp * ray.v - camera.cameraBack
    val distance = camera.cameraDistanceLogicalPixels
    val eye = camera.cameraBack * distance
    val centreToEye = DoubleVector3(eye.x, eye.y, eye.z + camera.radiusLogicalPixels)

    val a = direction.dot(direction)
    val b = 2.0 * centreToEye.dot(direction)
    val c = distance * distance + 2.0 * camera.radiusLogicalPixels * distance * camera.cameraBack.z
    val discriminant = b * b - 4.0 * a * c
    if (discriminant < 0.0) return GlobeRayResult.BeyondLimb

    val rootTerm = sqrt(discriminant)
    val q = -0.5 * (if (b >= 0.0) b + rootTerm else b - rootTerm)
    val firstRoot = q / a
    val secondRoot = c / q
    val nearRoot = min(firstRoot, secondRoot)
    if (max(firstRoot, secondRoot) <= 0.0) return GlobeRayResult.BeyondLimb
    if (nearRoot < NEAR_DISTANCE_LOGICAL_PIXELS) return GlobeRayResult.NearClipped

    val hit = eye + direction * nearRoot
    val globeFixed = camera.anchorEast * hit.x +
        camera.anchorNorth * hit.y +
        camera.anchorUp * (hit.z + camera.radiusLogicalPixels)
    return GlobeRayResult.Hit(point = mercatorGroundPoint(camera, globeFixed), t = nearRoot)
}

/**
 * The inverse of [unitSphereDirection], plus the unwrap into the camera's world copy.
 *
 * `asinh(tan latitude)` and `atanh(sin latitude)` are the same function, so the isometric latitude
 * comes straight off the direction's `z` with no `asin` between them, and `y = (1 - psi / PI) / 2`
 * is [projectMercator]'s own expression rearranged rather than a second convention. Trigonometry is
 * acceptable here in a way it is not on the forward path: this runs in `Double` on the CPU and
 * feeds tile selection, whereas the forward path is what a `highp float` shader evaluates.
 */
private fun mercatorGroundPoint(
    camera: ResolvedGlobeCamera,
    globeFixedPosition: DoubleVector3,
): MercatorGroundPoint {
    val length = sqrt(globeFixedPosition.dot(globeFixedPosition))
    val isometricLatitude = atanh(globeFixedPosition.z / length)
    val longitudeRadians = atan2(globeFixedPosition.y, globeFixedPosition.x)
    val wrappedX = 0.5 + longitudeRadians / (2.0 * PI)
    return MercatorGroundPoint(
        x = wrappedX + round(camera.mercatorAnchor.x - wrappedX),
        y = 0.5 * (1.0 - isometricLatitude / PI),
    )
}
