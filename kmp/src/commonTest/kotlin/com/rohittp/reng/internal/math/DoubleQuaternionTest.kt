package com.rohittp.reng.internal.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoubleQuaternionTest {
    @Test
    fun aQuaternionRotationMatrixMatchesTheEquivalentEulerRotation() {
        val ninetyAboutZ = DoubleQuaternion(0.0, 0.0, sin(PI / 4), cos(PI / 4))
        assertMatrixNear(DoubleMatrix3.rotationZDegrees(90.0), ninetyAboutZ.toRotationMatrix(), 1e-12)
    }

    @Test
    fun slerpTakesTheShortestArcWhenTheDotProductIsNegative() {
        val from = DoubleQuaternion(0.0, 0.0, 0.0, 1.0)
        // 90 degrees about -z, spelled the long way: negating a quaternion leaves the rotation it
        // represents unchanged, so this is the same rotation as +90 degrees about +z.
        val to = DoubleQuaternion(0.0, 0.0, -sin(PI / 4), -cos(PI / 4))
        val half = DoubleQuaternion.slerp(from, to, 0.5)
        // The shortest arc is 45 degrees about +z, not 135 the other way.
        assertMatrixNear(DoubleMatrix3.rotationZDegrees(45.0), half.toRotationMatrix(), 1e-9)
    }

    @Test
    fun slerpFallsBackToNormalizedLerpForNearlyParallelInputs() {
        val from = DoubleQuaternion(0.0, 0.0, 0.0, 1.0)
        val to = DoubleQuaternion(1e-9, 0.0, 0.0, 1.0).normalized()
        val half = DoubleQuaternion.slerp(from, to, 0.5)
        assertTrue(half.toRotationMatrix()[0, 0].isFinite(), "a vanishing sin(theta) must not divide by zero")
    }

    @Test
    fun slerpAtTheEndpointsReturnsTheEndpoints() {
        val from = DoubleQuaternion(0.0, 0.0, sin(PI / 8), cos(PI / 8))
        val to = DoubleQuaternion(0.0, 0.0, sin(PI / 3), cos(PI / 3))

        assertEquals(from, DoubleQuaternion.slerp(from, to, 0.0))
        assertEquals(to, DoubleQuaternion.slerp(from, to, 1.0))
    }

    private fun assertMatrixNear(expected: DoubleMatrix3, actual: DoubleMatrix3, tolerance: Double) {
        for (row in 0 until 3) for (column in 0 until 3) {
            val e = expected[row, column]
            val a = actual[row, column]
            check(abs(e - a) <= tolerance) {
                "Expected matrix element [$row, $column] to be $e but was $a (tolerance $tolerance)"
            }
        }
    }
}
