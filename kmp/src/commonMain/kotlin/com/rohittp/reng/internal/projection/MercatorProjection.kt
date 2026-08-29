package com.rohittp.reng.internal.projection

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal const val WGS84_SEMI_MAJOR_AXIS_METRES: Double = 6378137.0
internal val WGS84_FLATTENING: Double = 1.0 / 298.257223563
internal val WGS84_ECCENTRICITY_SQUARED: Double = WGS84_FLATTENING * (2.0 - WGS84_FLATTENING)
internal const val WORLD_CIRCUMFERENCE_METRES: Double = 40075016.68557849
internal const val MERCATOR_MAXIMUM_LATITUDE_DEGREES: Double = 85.0511287798066

private const val MINIMUM_MERCATOR_COPY_INDEX: Double = -16384.0
private const val MAXIMUM_MERCATOR_COPY_INDEX: Double = 16384.0
private const val MINIMUM_MERCATOR_SUPPORT_X: Double = -16384.0
private const val MAXIMUM_MERCATOR_SUPPORT_X: Double = 16385.0

internal data class GeographicPosition(
    val latitude: Double,
    val unwrappedLongitude: Double,
    val altitudeMetres: Double,
)

internal data class MercatorPosition(val x: Double, val y: Double, val z: Double)

internal data class Wgs84LocalFrame(
    val ecefPosition: DoubleVector3,
    val basisEastNorthUp: DoubleMatrix3,
)

internal fun projectMercator(position: GeographicPosition): MercatorPosition {
    val latitudeRadians = position.latitude.degreesToRadians()
    val x = (position.unwrappedLongitude + 180.0) / 360.0
    val y = when (position.latitude) {
        MERCATOR_MAXIMUM_LATITUDE_DEGREES -> 0.0
        -MERCATOR_MAXIMUM_LATITUDE_DEGREES -> 1.0
        else -> (1.0 - asinh(tan(latitudeRadians)) / PI) / 2.0
    }
    val z = position.altitudeMetres / (WORLD_CIRCUMFERENCE_METRES * cos(latitudeRadians))
    return MercatorPosition(x, y, z)
}

internal fun mercatorCopyIndex(unwrappedLongitude: Double): Double =
    floor((unwrappedLongitude + 180.0) / 360.0)

internal fun isWithinMercatorPlanningSupport(x: Double, y: Double): Boolean =
    x.isFinite() &&
        y.isFinite() &&
        x >= MINIMUM_MERCATOR_SUPPORT_X &&
        x <= MAXIMUM_MERCATOR_SUPPORT_X &&
        y >= 0.0 &&
        y <= 1.0

internal fun validateMercatorCamera(position: GeographicPosition): SpatialOutcome<MercatorPosition> =
    validateMercatorPosition(
        position = position.copy(altitudeMetres = 0.0),
        fields = MercatorPositionFields.Camera,
    )

internal fun validateMercatorMapPosition(position: GeographicPosition): SpatialOutcome<MercatorPosition> =
    validateMercatorPosition(position, MercatorPositionFields.MapPosition)

internal fun validateMercatorGeometryPosition(position: GeographicPosition): SpatialOutcome<MercatorPosition> =
    validateMercatorPosition(position, MercatorPositionFields.Geometry)

internal fun wgs84LocalFrame(position: GeographicPosition): Wgs84LocalFrame {
    val latitudeRadians = position.latitude.degreesToRadians()
    val longitudeRadians = canonicalWgs84LongitudeDegrees(position.unwrappedLongitude).degreesToRadians()
    val sineLatitude = sin(latitudeRadians)
    val cosineLatitude = cos(latitudeRadians)
    val sineLongitude = sin(longitudeRadians)
    val cosineLongitude = cos(longitudeRadians)
    val primeVerticalRadius = WGS84_SEMI_MAJOR_AXIS_METRES /
        sqrt(1.0 - WGS84_ECCENTRICITY_SQUARED * sineLatitude * sineLatitude)
    val altitude = position.altitudeMetres

    val ecef = DoubleVector3(
        x = (primeVerticalRadius + altitude) * cosineLatitude * cosineLongitude,
        y = (primeVerticalRadius + altitude) * cosineLatitude * sineLongitude,
        z = ((1.0 - WGS84_ECCENTRICITY_SQUARED) * primeVerticalRadius + altitude) * sineLatitude,
    )
    val east = DoubleVector3(-sineLongitude, cosineLongitude, 0.0)
    val north = DoubleVector3(
        -sineLatitude * cosineLongitude,
        -sineLatitude * sineLongitude,
        cosineLatitude,
    )
    val up = DoubleVector3(
        cosineLatitude * cosineLongitude,
        cosineLatitude * sineLongitude,
        sineLatitude,
    )
    return Wgs84LocalFrame(ecef, DoubleMatrix3.fromColumns(east, north, up))
}

private fun validateMercatorPosition(
    position: GeographicPosition,
    fields: MercatorPositionFields,
): SpatialOutcome<MercatorPosition> {
    if (!position.latitude.isFinite() ||
        position.latitude < -MERCATOR_MAXIMUM_LATITUDE_DEGREES ||
        position.latitude > MERCATOR_MAXIMUM_LATITUDE_DEGREES
    ) {
        return invalidMercatorValue(fields.latitude)
    }
    if (!position.unwrappedLongitude.isFinite()) return invalidMercatorValue(fields.unwrappedLongitude)

    val copyIndex = mercatorCopyIndex(position.unwrappedLongitude)
    if (!copyIndex.isFinite() ||
        copyIndex < MINIMUM_MERCATOR_COPY_INDEX ||
        copyIndex > MAXIMUM_MERCATOR_COPY_INDEX
    ) {
        return invalidMercatorValue(fields.unwrappedLongitude)
    }
    if (!position.altitudeMetres.isFinite()) return invalidMercatorValue(requireNotNull(fields.altitude))

    val projected = projectMercator(position)
    if (!isWithinMercatorPlanningSupport(projected.x, projected.y)) {
        return invalidMercatorValue(fields.unwrappedLongitude)
    }
    if (!projected.z.isFinite()) return invalidMercatorValue(requireNotNull(fields.altitude))

    return SpatialOutcome.Success(projected)
}

private fun invalidMercatorValue(field: DiagnosticField): SpatialOutcome.Failure =
    SpatialOutcome.Failure(
        FailureDescriptor(
            code = RenGErrorCode.INVALID_VALUE,
            stage = PipelineStage.FRAME_PLANNING,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = field,
            ),
        ),
    )

private fun canonicalWgs84LongitudeDegrees(unwrappedLongitude: Double): Double =
    unwrappedLongitude - 360.0 * mercatorCopyIndex(unwrappedLongitude)

/** Degrees to radians, shared across `internal.projection` so the camera, the Mercator
 * projection and the globe cannot disagree about the conversion. */
internal fun Double.degreesToRadians(): Double = this * PI / 180.0

private enum class MercatorPositionFields(
    val latitude: DiagnosticField,
    val unwrappedLongitude: DiagnosticField,
    val altitude: DiagnosticField?,
) {
    Camera(
        latitude = DiagnosticField.CAMERA_LATITUDE,
        unwrappedLongitude = DiagnosticField.CAMERA_UNWRAPPED_LONGITUDE,
        altitude = null,
    ),
    MapPosition(
        latitude = DiagnosticField.MAP_POSITION_LATITUDE,
        unwrappedLongitude = DiagnosticField.MAP_POSITION_UNWRAPPED_LONGITUDE,
        altitude = DiagnosticField.MAP_POSITION_ALTITUDE,
    ),
    Geometry(
        latitude = DiagnosticField.GEOMETRY_LATITUDE,
        unwrappedLongitude = DiagnosticField.GEOMETRY_UNWRAPPED_LONGITUDE,
        altitude = DiagnosticField.GEOMETRY_ALTITUDE,
    ),
}
