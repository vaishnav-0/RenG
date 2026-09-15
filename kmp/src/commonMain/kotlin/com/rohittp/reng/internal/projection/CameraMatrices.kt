package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class MercatorGroundPoint(val x: Double, val y: Double)

internal data class ResolvedMercatorCamera(
    override val outputPixelSize: OutputPixelSize,
    val mercatorAnchor: MercatorPosition,
    val worldSizeLogicalPixels: Double,
    override val right: DoubleVector3,
    override val cameraUp: DoubleVector3,
    override val cameraBack: DoubleVector3,
    val cameraDistanceLogicalPixels: Double,
    override val viewMatrix: DoubleMatrix4,
    override val projectionMatrix: DoubleMatrix4,
    val geographicGroundAnchor: GeographicPosition,
) : ResolvedFrameCamera

/**
 * A camera's orientation in the local east/north/up frame of the thing it is anchored to: `x` east,
 * `y` north, `z` up, all three unit and right-handed as `right x cameraUp == cameraBack`.
 *
 * It exists as a type because **both** projection modes need exactly this and neither may derive it
 * twice. Under Mercator that frame is the whole world, since the plane's east/north/up is the same
 * everywhere; on a globe it is the frame at the camera's own ground anchor and the globe-fixed axes
 * are a rotation away ([ResolvedGlobeCamera.anchorEast] and its siblings). Bearing and pitch mean
 * the same thing in both, which is what lets [cameraViewMatrix] and [cameraProjectionMatrix] be one
 * expression rather than two that can silently disagree.
 */
internal data class CameraOrientation(
    val right: DoubleVector3,
    val cameraUp: DoubleVector3,
    val cameraBack: DoubleVector3,
)

/**
 * A pixel's ray coordinates on the camera's own near-plane axes: [u] rightward and [v] upward, both
 * in units where the view axis contributes exactly 1, so a ray is `right * u + cameraUp * v -
 * cameraBack` and its parameter is `w` — the distance in front of the camera plane — rather than a
 * Euclidean length. Shared by [physicalPixelGroundRay] and [physicalPixelGlobeRay] so the two
 * inverse directions cannot disagree about where a pixel centre is.
 */
internal data class PixelRayCoordinates(val u: Double, val v: Double)

internal sealed interface GroundRayResult {
    data object HorizonOrSky : GroundRayResult

    data object NearClipped : GroundRayResult

    /**
     * The ray's ground hit exists and is finite, but it lies nearer the horizon than
     * [MAXIMUM_GROUND_ANGLE_DEGREES] allows, so the footprint built from it is not worth trusting
     * (ADR 0064). Every caller treats this exactly as it treats [HorizonOrSky] -- both mean "this
     * row contributes no ground" -- and the two are kept apart only so a test can tell which bound
     * rejected a row.
     */
    data object BeyondHorizonAngle : GroundRayResult

    data class Hit(
        val point: MercatorGroundPoint,
        val q: Double,
        val t: Double,
    ) : GroundRayResult
}

internal fun resolveMercatorCamera(
    camera: Camera,
    outputPixelSize: OutputPixelSize,
): SpatialOutcome<ResolvedMercatorCamera> {
    val geographicGroundAnchor = GeographicPosition(
        latitude = camera.latitude,
        unwrappedLongitude = camera.unwrappedLongitude,
        altitudeMetres = 0.0,
    )
    val anchorOutcome = validateMercatorCamera(geographicGroundAnchor)
    if (anchorOutcome is SpatialOutcome.Failure) return anchorOutcome
    val mercatorAnchor = (anchorOutcome as SpatialOutcome.Success).value

    val orientation = cameraOrientation(camera.bearing, camera.pitch)
    val cameraDistance = cameraDistanceLogicalPixels(outputPixelSize)

    return SpatialOutcome.Success(
        ResolvedMercatorCamera(
            outputPixelSize = outputPixelSize,
            mercatorAnchor = mercatorAnchor,
            worldSizeLogicalPixels = 512.0 * 2.0.pow(camera.zoom),
            right = orientation.right,
            cameraUp = orientation.cameraUp,
            cameraBack = orientation.cameraBack,
            cameraDistanceLogicalPixels = cameraDistance,
            viewMatrix = cameraViewMatrix(orientation, cameraDistance),
            projectionMatrix = cameraProjectionMatrix(outputPixelSize),
            geographicGroundAnchor = geographicGroundAnchor,
        ),
    )
}

/**
 * Bearing and pitch as an orthonormal right-handed basis, in the anchor's east/north/up frame.
 *
 * `mapForward` is the compass direction the camera faces along the ground, so a bearing of 90
 * degrees puts east at the top of the screen and south to the [CameraOrientation.right] -- the
 * screen rotates under a fixed world rather than the world rotating under a fixed screen, which is
 * the sign convention every placement and every ray in RenG already carries.
 */
internal fun cameraOrientation(bearingDegrees: Double, pitchDegrees: Double): CameraOrientation {
    val bearingRadians = bearingDegrees.degreesToRadians()
    val pitchRadians = pitchDegrees.degreesToRadians()
    val sineBearing = sin(bearingRadians)
    val cosineBearing = cos(bearingRadians)
    val sinePitch = sin(pitchRadians)
    val cosinePitch = cos(pitchRadians)
    val mapForward = DoubleVector3(sineBearing, cosineBearing, 0.0)
    val right = DoubleVector3(cosineBearing, -sineBearing, 0.0)
    val cameraForward = mapForward * sinePitch - UP * cosinePitch
    val cameraUp = mapForward * cosinePitch + UP * sinePitch
    return CameraOrientation(right = right, cameraUp = cameraUp, cameraBack = -cameraForward)
}

/**
 * How far back from its anchor the camera sits, in logical pixels: the distance at which the 45
 * degree vertical field of view spans exactly [OutputPixelSize.height] logical pixels, which is
 * what makes one logical pixel at the anchor one output pixel on the screen.
 *
 * It is deliberately independent of the projection mode. On a globe the camera orbits rather than
 * hovers, but it orbits at this same height above its anchor, so the anchor's on-screen scale is
 * the one thing both modes share -- and it is what
 * [docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md] section 2's latitude-matched zoom
 * convention is defined against.
 */
internal fun cameraDistanceLogicalPixels(outputPixelSize: OutputPixelSize): Double =
    outputPixelSize.height.toDouble() * FOCAL_LENGTH_SCALE / 2.0

/**
 * The view matrix: the orientation's basis in mathematical rows, plus a translation that pulls the
 * camera [cameraDistanceLogicalPixels] back along [CameraOrientation.cameraBack]. Its input is a
 * position in the anchor's east/north/up logical pixels, in both projection modes -- on a globe
 * [ResolvedGlobeCamera.globeFixedToCameraRelative] produces that input from a globe-fixed one.
 */
internal fun cameraViewMatrix(
    orientation: CameraOrientation,
    cameraDistanceLogicalPixels: Double,
): DoubleMatrix4 = DoubleMatrix4.fromRows(
    listOf(
        listOf(orientation.right.x, orientation.right.y, orientation.right.z, 0.0),
        listOf(orientation.cameraUp.x, orientation.cameraUp.y, orientation.cameraUp.z, 0.0),
        listOf(
            orientation.cameraBack.x,
            orientation.cameraBack.y,
            orientation.cameraBack.z,
            -cameraDistanceLogicalPixels,
        ),
        listOf(0.0, 0.0, 0.0, 1.0),
    ),
)

/**
 * The reverse-Z, infinite-far perspective projection with a one-logical-pixel near plane and a 45
 * degree vertical field of view, so window depth is exactly `near / distance`.
 *
 * **It does not change on a globe, and the reason is arithmetic rather than taste.** Against the
 * 24-bit fixed-point depth buffer this makes the resolvable step at the camera's own distance 0.025
 * logical pixels; the same projection in ECEF metres with a one-metre near plane resolves 2.4 x
 * 10^6 metres at earth radius. A sphere invites a metric frame and taking the invitation destroys
 * the depth buffer, so [ResolvedGlobeCamera] stays in logical pixels and shares this matrix
 * unchanged (`docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` section 3).
 */
internal fun cameraProjectionMatrix(outputPixelSize: OutputPixelSize): DoubleMatrix4 {
    val aspect = outputPixelSize.width.toDouble() / outputPixelSize.height.toDouble()
    return DoubleMatrix4.fromRows(
        listOf(
            listOf(FOCAL_LENGTH_SCALE / aspect, 0.0, 0.0, 0.0),
            listOf(0.0, FOCAL_LENGTH_SCALE, 0.0, 0.0),
            listOf(0.0, 0.0, 1.0, 2.0 * NEAR_DISTANCE_LOGICAL_PIXELS),
            listOf(0.0, 0.0, -1.0, 0.0),
        ),
    )
}

/** Where the centre of physical pixel ([pixelX], [pixelY]) sits on the camera's near-plane axes.
 * The half-pixel is what makes the round trip through [projectCameraRelativeLogicalPosition] --
 * whose viewport transform puts pixel centres at half-integers -- land back on `index + 0.5`. */
internal fun pixelRayCoordinates(
    outputPixelSize: OutputPixelSize,
    pixelX: Int,
    pixelY: Int,
): PixelRayCoordinates {
    require(pixelX in 0 until outputPixelSize.width) { "pixelX is outside the output" }
    require(pixelY in 0 until outputPixelSize.height) { "pixelY is outside the output" }

    val width = outputPixelSize.width.toDouble()
    val height = outputPixelSize.height.toDouble()
    val aspect = width / height
    val screenX = pixelX.toDouble() + 0.5
    val screenY = pixelY.toDouble() + 0.5
    val xi = 2.0 * screenX / width - 1.0
    val eta = 1.0 - 2.0 * screenY / height
    return PixelRayCoordinates(u = aspect * xi / FOCAL_LENGTH_SCALE, v = eta / FOCAL_LENGTH_SCALE)
}

internal fun physicalPixelGroundRay(
    camera: ResolvedMercatorCamera,
    pixelX: Int,
    pixelY: Int,
): GroundRayResult {
    val ray = pixelRayCoordinates(camera.outputPixelSize, pixelX, pixelY)
    val u = ray.u
    val v = ray.v
    val cosinePitch = camera.cameraBack.z
    val sinePitch = camera.cameraUp.z
    val q = cosinePitch - v * sinePitch

    if (q <= 0.0) return GroundRayResult.HorizonOrSky

    // ADR 0064, and it decides the angle without ever computing it: `tan(theta)` is
    // `(sinePitch + v * cosinePitch) / q`, and `q` is strictly positive by the branch above, so
    // multiplying through preserves the sense. The comparison is closed, matching the horizon and
    // near-plane boundaries either side of it -- at a pitch of 66.75 degrees the top row's angle is
    // the maximum to within one ULP, and admitting that row is what lets the ceiling
    // `Camera.MAXIMUM_GROUND_FILLING_PITCH_DEGREES` publishes be reached with the frame still whole.
    if (sinePitch + v * cosinePitch > q * TANGENT_OF_MAXIMUM_GROUND_ANGLE) {
        return GroundRayResult.BeyondHorizonAngle
    }

    val t = camera.cameraDistanceLogicalPixels * cosinePitch / q
    if (t < NEAR_DISTANCE_LOGICAL_PIXELS) return GroundRayResult.NearClipped

    val rightGround = u * t
    val forwardGround = camera.cameraDistanceLogicalPixels * v / q
    val cosineBearing = camera.right.x
    val sineBearing = -camera.right.y
    val eastGround = cosineBearing * rightGround + sineBearing * forwardGround
    val northGround = -sineBearing * rightGround + cosineBearing * forwardGround
    val point = MercatorGroundPoint(
        x = camera.mercatorAnchor.x + eastGround / camera.worldSizeLogicalPixels,
        y = camera.mercatorAnchor.y - northGround / camera.worldSizeLogicalPixels,
    )
    return GroundRayResult.Hit(point = point, q = q, t = t)
}

/** `1 + sqrt(2)`, which is `1 / tan(22.5 degrees)`: the 45 degree vertical field of view, written
 * as the one number the projection matrix, the camera distance and both inverse rays all divide
 * by. */
internal val FOCAL_LENGTH_SCALE: Double = 1.0 + sqrt(2.0)
/** The one-logical-pixel near plane (`CONTEXT.md`), shared by both directions: the inverse
 * [physicalPixelGroundRay] near-clips a ground ray at it and the forward
 * [projectCameraRelativeLogicalPosition] rejects a position behind it. Two copies of this
 * number would be exactly the kind of silent disagreement the round trip exists to catch. */
internal const val NEAR_DISTANCE_LOGICAL_PIXELS: Double = 1.0

/**
 * How near the horizon a ground ray is still trusted, as an angle from the downward axis, in
 * degrees (ADR 0064).
 *
 * **This is a numerical guard, not a work bound, and the distinction is the whole reason it sits
 * this close to 90.** A ray immediately below the horizon has a vanishing `q` and therefore an
 * arbitrarily large `t`, which used to reach tile selection as a footprint spanning thousands of
 * world copies -- an `OverBudget` frame failure, or a non-finite vertex collapsing to an empty map.
 * Bounding the *work* a steep frame costs is `basemapLodForGroundAngle`'s job, in tile selection
 * where the cost actually is, so this constant is free to be set purely on where the arithmetic
 * stops being trustworthy rather than on what a frame can afford.
 *
 * `89.25` is `maxMercatorHorizonAngle` from MapLibre GL JS
 * (`src/geo/projection/mercator_utils.ts`), adopted unchanged and with attribution. Their own
 * comment gives both bounds on it -- it "must be less than 90 to prevent errors", and "shouldn't be
 * too close to 90, or the distance to the horizon will become very large, unnecessarily increasing
 * the number of tiles needed to render the map." Only the first half is this constant's problem
 * here, so there is no evidence on which to retune it and it is inherited rather than rechosen.
 *
 * Because the vertical half field of view is exactly 22.5 degrees and `theta = pitch + atan(v)`
 * exactly, the top row's angle is `pitch + 22.5`, so this bound first bites at `89.25 - 22.5 =
 * 66.75` degrees of pitch and not one row earlier. Measured across the sweep it removes 0.00% of
 * frame height at 66.75 degrees, 0.62% at 67.0 and 1.60% at 67.4.
 */
internal const val MAXIMUM_GROUND_ANGLE_DEGREES: Double = 89.25

/**
 * [MAXIMUM_GROUND_ANGLE_DEGREES] as the tangent the ray test compares against, evaluated once here
 * rather than per pixel. A ground ray is cast for every row of every frame, so the per-row cost of
 * this bound is one multiply and one compare.
 */
private val TANGENT_OF_MAXIMUM_GROUND_ANGLE: Double =
    tan(MAXIMUM_GROUND_ANGLE_DEGREES.degreesToRadians())
private val UP: DoubleVector3 = DoubleVector3(0.0, 0.0, 1.0)

