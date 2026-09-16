package com.rohittp.reng

import com.rohittp.reng.internal.canonicalDouble
import com.rohittp.reng.internal.projection.FOCAL_LENGTH_SCALE
import com.rohittp.reng.internal.projection.MAXIMUM_GROUND_ANGLE_DEGREES
import kotlin.math.PI
import kotlin.math.atan

/**
 * Whether a map position's altitude is measured from the ellipsoid or from the terrain beneath it.
 *
 * **[ABSOLUTE] is the default, and the glossary decided that rather than caution.** `CONTEXT.md`
 * already defines a map position's components as `(latitude degrees, unwrapped longitude degrees,
 * WGS84 ellipsoidal altitude metres)`, so a caller who writes zero over a plateau means sea level
 * and gets sea level. Defaulting the other way would have made that sentence false for every
 * consumer who had not opted out. [GROUND_RELATIVE] is the opt-in that means the other thing.
 *
 * **The two are identical wherever the ground is flat** — every published release, and 28 of the 34
 * styles RenG is verified against, which declare no terrain at all. Where terrain is absent or
 * degraded [GROUND_RELATIVE] therefore resolves against the flat ground and *means* [ABSOLUTE]; the
 * frame's coverage diagnostic is the only thing that says so, which ADR 0041 accepts deliberately
 * rather than failing a frame over a coverage gap.
 *
 * **Not a third [AnchoringMode].** Anchoring chooses the space a property resolves in and is legal
 * on all three of position, rotation and scale, so a `GROUND` constant there would be writable as
 * `rotationMode` and `scaleMode`, where it means nothing. Altitude mode modifies one component of a
 * map position and only that. ADR 0040 has the whole argument, including why an
 * `elevationAt(latitude, longitude)` query was rejected as circular.
 */
@kotlinx.serialization.Serializable
public enum class AltitudeMode {
    @kotlinx.serialization.SerialName("ABSOLUTE")
    ABSOLUTE,
    @kotlinx.serialization.SerialName("GROUND_RELATIVE")
    GROUND_RELATIVE,
}

@kotlinx.serialization.Serializable(with = Vector3Serializer::class)
public class Vector3(x: Double, y: Double, z: Double) {
    public val x: Double
    public val y: Double
    public val z: Double

    init {
        val canonicalX = canonicalDouble(x, "x")
        val canonicalY = canonicalDouble(y, "y")
        val canonicalZ = canonicalDouble(z, "z")
        this.x = canonicalX
        this.y = canonicalY
        this.z = canonicalZ
    }

    override fun equals(other: Any?): Boolean =
        other is Vector3 && x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    override fun toString(): String = "Vector3(x=$x, y=$y, z=$z)"
}

@kotlinx.serialization.Serializable(with = CameraSerializer::class)
public class Camera(
    latitude: Double,
    unwrappedLongitude: Double,
    zoom: Double,
    bearing: Double,
    pitch: Double,
) {
    public val latitude: Double
    public val unwrappedLongitude: Double
    public val zoom: Double
    public val bearing: Double
    public val pitch: Double

    init {
        val canonicalLatitude = canonicalDouble(latitude, "latitude")
        val canonicalLongitude = canonicalDouble(unwrappedLongitude, "unwrappedLongitude")
        val canonicalZoom = canonicalDouble(zoom, "zoom")
        val canonicalBearing = canonicalDouble(bearing, "bearing")
        val canonicalPitch = canonicalDouble(pitch, "pitch")

        require(canonicalLatitude in -90.0..90.0) { "latitude must be within the supported range" }
        require(canonicalZoom in 0.0..22.0) { "zoom must be within the supported range" }
        require(canonicalBearing >= 0.0 && canonicalBearing < 360.0) {
            "bearing must be within the supported range"
        }
        require(canonicalPitch >= 0.0 && canonicalPitch < 90.0) {
            "pitch must be within the supported range"
        }

        this.latitude = canonicalLatitude
        this.unwrappedLongitude = canonicalLongitude
        this.zoom = canonicalZoom
        this.bearing = canonicalBearing
        this.pitch = canonicalPitch
    }

    override fun equals(other: Any?): Boolean =
        other is Camera &&
            latitude == other.latitude &&
            unwrappedLongitude == other.unwrappedLongitude &&
            zoom == other.zoom &&
            bearing == other.bearing &&
            pitch == other.pitch

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + unwrappedLongitude.hashCode()
        result = 31 * result + zoom.hashCode()
        result = 31 * result + bearing.hashCode()
        result = 31 * result + pitch.hashCode()
        return result
    }

    override fun toString(): String =
        "Camera(latitude=$latitude, unwrappedLongitude=$unwrappedLongitude, zoom=$zoom, " +
            "bearing=$bearing, pitch=$pitch)"

    public companion object {
        /**
         * The largest [pitch] at which the ground still reaches every row of the output frame -- a
         * quality ceiling, not a domain limit (ADR 0067).
         *
         * [pitch] keeps the full `[0, 90)` range this class has always validated: a steeper camera
         * is legal and is drawn exactly as asked. This exists for a caller -- typically wherever a
         * *person* drags a tilt gesture -- that wants to stop **offering** a pitch before anyone
         * reaches one, which is a different question from which pitches are admissible.
         *
         * The vertical half field of view is exactly 22.5 degrees (`FOCAL_LENGTH_SCALE = 1 + sqrt(2)`
         * being `1 / tan(22.5)`) and a ray's angle is `pitch + atan(v)` exactly, so the top row sits
         * at `pitch + 22.5` and is always the first to run out of ground. This is therefore
         * `MAXIMUM_GROUND_ANGLE_DEGREES` (ADR 0064) less that half angle -- **66.75 degrees** at the
         * shipped constants -- computed from both rather than written as a literal, so a change to
         * either moves it.
         *
         * **Above it the renderer keeps drawing and the horizon enters the frame.** The rows above
         * carry whatever the consumer's surface was cleared to: a horizon, not a clipped edge.
         */
        public val MAXIMUM_GROUND_FILLING_PITCH_DEGREES: Double =
            MAXIMUM_GROUND_ANGLE_DEGREES - atan(1.0 / FOCAL_LENGTH_SCALE) * DEGREES_PER_RADIAN
    }
}

/** Radians to degrees, for the one derived constant on [Camera] that needs it. */
private const val DEGREES_PER_RADIAN: Double = 180.0 / PI

@kotlinx.serialization.Serializable(with = PlacementSerializer::class)
public class Placement(
    positionMode: AnchoringMode,
    position: Vector3,
    rotationMode: AnchoringMode,
    rotation: Vector3,
    scaleMode: AnchoringMode,
    scale: Double,
    altitudeMode: AltitudeMode = AltitudeMode.ABSOLUTE,
) {
    public val positionMode: AnchoringMode
    public val position: Vector3
    public val rotationMode: AnchoringMode
    public val rotation: Vector3
    public val scaleMode: AnchoringMode
    public val scale: Double

    /**
     * How this placement's `position.z` is measured, when `position` resolves in map space.
     *
     * **Declared last rather than beside [position], and that is an ABI decision.** Every argument
     * before it is positional in three shipped releases' worth of consumer code; a seventh parameter
     * with a default leaves all of them compiling, where inserting one in the middle would silently
     * re-bind every positional call. The canonical encoder makes the same choice for the same reason
     * (ADR 0018: tags are permanent, declaration order is not part of the contract).
     *
     * **[AltitudeMode.GROUND_RELATIVE] requires [positionMode] to be [AnchoringMode.MAP], and that is
     * rejected at construction rather than at planning.** A screen-anchored `position.z` is a
     * compositing z-index rather than an altitude — there is no ground under it to be relative to —
     * so the pair is not a frame RenG can draw badly, it is a sentence with no meaning. ADR 0040
     * rejects a fourth `AnchoringMode` precisely because that type "would accept nonsense that RenG
     * would then have to reject at planning"; a separate field that accepted the same nonsense would
     * have bought nothing.
     */
    public val altitudeMode: AltitudeMode

    init {
        val canonicalScale = canonicalDouble(scale, "scale")
        require(rotation.x >= -180.0 && rotation.x < 180.0) {
            "rotation.x must be within the supported range"
        }
        require(rotation.y >= -180.0 && rotation.y < 180.0) {
            "rotation.y must be within the supported range"
        }
        require(rotation.z >= -180.0 && rotation.z < 180.0) {
            "rotation.z must be within the supported range"
        }
        require(canonicalScale >= 0.0) { "scale must be non-negative" }
        require(altitudeMode == AltitudeMode.ABSOLUTE || positionMode == AnchoringMode.MAP) {
            "a ground-relative altitude requires a map-anchored position"
        }

        this.positionMode = positionMode
        this.position = position
        this.rotationMode = rotationMode
        this.rotation = rotation
        this.scaleMode = scaleMode
        this.scale = canonicalScale
        this.altitudeMode = altitudeMode
    }

    override fun equals(other: Any?): Boolean =
        other is Placement &&
            positionMode == other.positionMode &&
            position == other.position &&
            rotationMode == other.rotationMode &&
            rotation == other.rotation &&
            scaleMode == other.scaleMode &&
            scale == other.scale &&
            altitudeMode == other.altitudeMode

    override fun hashCode(): Int {
        var result = positionMode.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + rotationMode.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + scaleMode.hashCode()
        result = 31 * result + scale.hashCode()
        result = 31 * result + altitudeMode.hashCode()
        return result
    }

    override fun toString(): String =
        "Placement(positionMode=$positionMode, position=$position, rotationMode=$rotationMode, " +
            "rotation=$rotation, scaleMode=$scaleMode, scale=$scale, altitudeMode=$altitudeMode)"
}
