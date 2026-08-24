package com.rohittp.reng.internal.math

import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rotation quaternion, spelled `[x, y, z, w]` to match glTF's node `rotation` field
 * (`internal/glb/GltfDocument.kt`'s `GltfNode.rotation`) component for component, so a caller can
 * build one directly from the parsed `Double` list without reordering.
 *
 * Every operation here assumes -- and [normalized] restores -- unit length; nothing checks it on
 * every call, in the same spirit as [DoubleMatrix3] and [DoubleMatrix4] not re-validating their own
 * invariants on every read.
 */
internal data class DoubleQuaternion(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun plus(other: DoubleQuaternion): DoubleQuaternion =
        DoubleQuaternion(x + other.x, y + other.y, z + other.z, w + other.w)

    operator fun times(scalar: Double): DoubleQuaternion =
        DoubleQuaternion(x * scalar, y * scalar, z * scalar, w * scalar)

    operator fun unaryMinus(): DoubleQuaternion = DoubleQuaternion(-x, -y, -z, -w)

    fun dot(other: DoubleQuaternion): Double = x * other.x + y * other.y + z * other.z + w * other.w

    /** Rescales to unit length. The caller is responsible for never calling this on the zero
     * quaternion, which has no direction to normalize toward. */
    fun normalized(): DoubleQuaternion {
        val length = sqrt(dot(this))
        return DoubleQuaternion(x / length, y / length, z / length, w / length)
    }

    /**
     * The rotation matrix this (assumed-unit) quaternion represents, in the same active,
     * column-vector convention as [DoubleMatrix3.rotationXDegrees] and its siblings -- a unit
     * quaternion built from an axis and half-angle produces the identical matrix a direct
     * axis-angle construction would.
     */
    fun toRotationMatrix(): DoubleMatrix3 {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return DoubleMatrix3.fromRows(
            listOf(
                listOf(1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy)),
                listOf(2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx)),
                listOf(2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy)),
            ),
        )
    }

    internal companion object {
        /** Above this dot product the two quaternions are close enough that spherical
         * interpolation's `sin(theta)` divisor is too small to trust; normalized linear
         * interpolation is visually indistinguishable at that separation and never divides by a
         * vanishing quantity. */
        private const val LERP_FALLBACK_DOT_THRESHOLD = 0.9995

        /**
         * Spherical linear interpolation between [from] and [to] at `t` in `[0, 1]`.
         *
         * A unit quaternion and its negation represent the identical rotation (the "double
         * cover"), so two keyframes authored independently can encode the same orientation with
         * opposite signs. Interpolating between them without correcting for that takes the long
         * way around the sphere -- 270 degrees instead of 90, say -- which renders as the model
         * spinning the wrong direction between two frames that individually look correct. This
         * negates [to] first whenever `from · to < 0`, which always chooses the shorter of the two
         * arcs joining the same pair of orientations.
         */
        fun slerp(from: DoubleQuaternion, to: DoubleQuaternion, t: Double): DoubleQuaternion {
            var cosineOfAngle = from.dot(to)
            var target = to
            if (cosineOfAngle < 0.0) {
                target = -to
                cosineOfAngle = -cosineOfAngle
            }

            if (cosineOfAngle > LERP_FALLBACK_DOT_THRESHOLD) {
                return (from * (1.0 - t) + target * t).normalized()
            }

            val angle = acos(cosineOfAngle.coerceIn(-1.0, 1.0))
            val sinOfAngle = sin(angle)
            val fromWeight = sin((1.0 - t) * angle) / sinOfAngle
            val targetWeight = sin(t * angle) / sinOfAngle
            return (from * fromWeight + target * targetWeight).normalized()
        }
    }
}
