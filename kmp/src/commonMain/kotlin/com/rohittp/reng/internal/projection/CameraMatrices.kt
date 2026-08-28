package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class MercatorGroundPoint(val x: Double, val y: Double)

internal data class ResolvedMercatorCamera(
    val outputPixelSize: OutputPixelSize,
    val mercatorAnchor: MercatorPosition,
    val worldSizeLogicalPixels: Double,
    val right: DoubleVector3,
    val cameraUp: DoubleVector3,
    val cameraBack: DoubleVector3,
    val cameraDistanceLogicalPixels: Double,
    val viewMatrix: DoubleMatrix4,
    val projectionMatrix: DoubleMatrix4,
    val geographicGroundAnchor: GeographicPosition,
)

internal sealed interface GroundRayResult {
    data object HorizonOrSky : GroundRayResult

    data object NearClipped : GroundRayResult

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

    val bearingRadians = camera.bearing.degreesToRadians()
    val pitchRadians = camera.pitch.degreesToRadians()
    val sineBearing = sin(bearingRadians)
    val cosineBearing = cos(bearingRadians)
    val sinePitch = sin(pitchRadians)
    val cosinePitch = cos(pitchRadians)
    val mapForward = DoubleVector3(sineBearing, cosineBearing, 0.0)
    val right = DoubleVector3(cosineBearing, -sineBearing, 0.0)
    val cameraForward = mapForward * sinePitch - UP * cosinePitch
    val cameraUp = mapForward * cosinePitch + UP * sinePitch
    val cameraBack = -cameraForward
    val cameraDistance = outputPixelSize.height.toDouble() * FOCAL_LENGTH_SCALE / 2.0
    val aspect = outputPixelSize.width.toDouble() / outputPixelSize.height.toDouble()

    val viewMatrix = DoubleMatrix4.fromRows(
        listOf(
            listOf(right.x, right.y, right.z, 0.0),
            listOf(cameraUp.x, cameraUp.y, cameraUp.z, 0.0),
            listOf(cameraBack.x, cameraBack.y, cameraBack.z, -cameraDistance),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )
    val projectionMatrix = DoubleMatrix4.fromRows(
        listOf(
            listOf(FOCAL_LENGTH_SCALE / aspect, 0.0, 0.0, 0.0),
            listOf(0.0, FOCAL_LENGTH_SCALE, 0.0, 0.0),
            listOf(0.0, 0.0, 1.0, 2.0 * NEAR_DISTANCE_LOGICAL_PIXELS),
            listOf(0.0, 0.0, -1.0, 0.0),
        ),
    )

    return SpatialOutcome.Success(
        ResolvedMercatorCamera(
            outputPixelSize = outputPixelSize,
            mercatorAnchor = mercatorAnchor,
            worldSizeLogicalPixels = 512.0 * 2.0.pow(camera.zoom),
            right = right,
            cameraUp = cameraUp,
            cameraBack = cameraBack,
            cameraDistanceLogicalPixels = cameraDistance,
            viewMatrix = viewMatrix,
            projectionMatrix = projectionMatrix,
            geographicGroundAnchor = geographicGroundAnchor,
        ),
    )
}

internal fun physicalPixelGroundRay(
    camera: ResolvedMercatorCamera,
    pixelX: Int,
    pixelY: Int,
): GroundRayResult {
    require(pixelX in 0 until camera.outputPixelSize.width) { "pixelX is outside the output" }
    require(pixelY in 0 until camera.outputPixelSize.height) { "pixelY is outside the output" }

    val width = camera.outputPixelSize.width.toDouble()
    val height = camera.outputPixelSize.height.toDouble()
    val aspect = width / height
    val screenX = pixelX.toDouble() + 0.5
    val screenY = pixelY.toDouble() + 0.5
    val xi = 2.0 * screenX / width - 1.0
    val eta = 1.0 - 2.0 * screenY / height
    val u = aspect * xi / FOCAL_LENGTH_SCALE
    val v = eta / FOCAL_LENGTH_SCALE
    val cosinePitch = camera.cameraBack.z
    val sinePitch = camera.cameraUp.z
    val q = cosinePitch - v * sinePitch

    if (q <= 0.0) return GroundRayResult.HorizonOrSky

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

private val FOCAL_LENGTH_SCALE: Double = 1.0 + sqrt(2.0)
/** The one-logical-pixel near plane (`CONTEXT.md`), shared by both directions: the inverse
 * [physicalPixelGroundRay] near-clips a ground ray at it and the forward
 * [projectCameraRelativeLogicalPosition] rejects a position behind it. Two copies of this
 * number would be exactly the kind of silent disagreement the round trip exists to catch. */
internal const val NEAR_DISTANCE_LOGICAL_PIXELS: Double = 1.0
private val UP: DoubleVector3 = DoubleVector3(0.0, 0.0, 1.0)

private fun Double.degreesToRadians(): Double = this * PI / 180.0
