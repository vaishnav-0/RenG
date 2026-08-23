package com.rohittp.reng.internal.math

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DoubleLinearAlgebraTest {
    @Test
    fun matricesSnapshotColumnMajorInputsAndHaveStructuralEquality() {
        val supplied = mutableListOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        val matrix = DoubleMatrix3(supplied)
        supplied[0] = 99.0

        assertEquals(1.0, matrix[0, 0])
        assertEquals(4.0, matrix[0, 1])
        assertEquals(
            matrix,
            DoubleMatrix3(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)),
        )
        assertEquals(
            matrix.hashCode(),
            DoubleMatrix3(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)).hashCode(),
        )
        assertNotEquals(matrix, DoubleMatrix3.identity)
        assertFailsWith<IllegalArgumentException> { DoubleMatrix3(List(8) { 0.0 }) }
        assertFailsWith<IllegalArgumentException> { DoubleMatrix4(List(15) { 0.0 }) }

        val suppliedFourByFour = MutableList(16) { it.toDouble() }
        val matrixFourByFour = DoubleMatrix4(suppliedFourByFour)
        suppliedFourByFour[0] = 99.0

        assertEquals(0.0, matrixFourByFour[0, 0])
        assertEquals(
            matrixFourByFour,
            DoubleMatrix4(List(16) { it.toDouble() }),
        )
        assertEquals(
            matrixFourByFour.hashCode(),
            DoubleMatrix4(List(16) { it.toDouble() }).hashCode(),
        )
    }

    @Test
    fun identityTransposeAndCompositionUseColumnVectorOrder() {
        val matrix = DoubleMatrix3(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0))
        val transpose = matrix.transpose()

        assertEquals(1.0, transpose[0, 0])
        assertEquals(2.0, transpose[0, 1])
        assertEquals(4.0, transpose[1, 0])
        assertEquals(matrix, transpose.transpose())
        assertEquals(matrix, DoubleMatrix3.identity * matrix)
        assertEquals(matrix, matrix * DoubleMatrix3.identity)
        assertEquals(DoubleMatrix4.identity, DoubleMatrix4.identity.transpose())
        assertEquals(DoubleMatrix4.identity, DoubleMatrix4.identity * DoubleMatrix4.identity)
    }

    @Test
    fun fourByFourOperationsPreserveColumnMajorOffDiagonalOrder() {
        val left = DoubleMatrix4(
            listOf(
                1.0, 5.0, 9.0, 13.0,
                2.0, 6.0, 10.0, 14.0,
                3.0, 7.0, 11.0, 15.0,
                4.0, 8.0, 12.0, 16.0,
            ),
        )
        val right = DoubleMatrix4(
            listOf(
                2.0, 0.0, 0.0, 0.0,
                0.0, 3.0, 0.0, 0.0,
                0.0, 0.0, 5.0, 0.0,
                0.0, 0.0, 0.0, 7.0,
            ),
        )

        assertEquals(2.0, left[0, 1])
        assertEquals(13.0, left[3, 0])
        assertEquals(7.0, left[1, 2])
        assertEquals(12.0, left[2, 3])
        assertEquals(
            DoubleMatrix4(
                listOf(
                    1.0, 2.0, 3.0, 4.0,
                    5.0, 6.0, 7.0, 8.0,
                    9.0, 10.0, 11.0, 12.0,
                    13.0, 14.0, 15.0, 16.0,
                ),
            ),
            left.transpose(),
        )
        assertEquals(
            DoubleMatrix4(
                listOf(
                    2.0, 10.0, 18.0, 26.0,
                    6.0, 18.0, 30.0, 42.0,
                    15.0, 35.0, 55.0, 75.0,
                    28.0, 56.0, 84.0, 112.0,
                ),
            ),
            left * right,
        )
        assertEquals(
            DoubleMatrix4(
                listOf(
                    2.0, 15.0, 45.0, 91.0,
                    4.0, 18.0, 50.0, 98.0,
                    6.0, 21.0, 55.0, 105.0,
                    8.0, 24.0, 60.0, 112.0,
                ),
            ),
            right * left,
        )
        assertNotEquals(left * right, right * left)
    }

    @Test
    fun vectorAlgebraIsRightHanded() {
        val x = DoubleVector3(1.0, 0.0, 0.0)
        val y = DoubleVector3(0.0, 1.0, 0.0)
        val z = DoubleVector3(0.0, 0.0, 1.0)

        assertEquals(z, x.cross(y))
        assertVectorClose(-z, y.cross(x))
        assertEquals(0.0, x.dot(y))
        assertEquals(1.0, x.dot(x))
        assertEquals(DoubleVector3(3.0, 3.0, 3.0), x + DoubleVector3(2.0, 3.0, 3.0))
        assertEquals(DoubleVector3(2.0, 4.0, 6.0), DoubleVector3(1.0, 2.0, 3.0) * 2.0)
    }

    @Test
    fun axisRotationsAndExtrinsicXyzCompositionAreExactInOrder() {
        assertVectorClose(
            DoubleVector3(0.0, 0.0, 1.0),
            DoubleMatrix3.rotationXDegrees(90.0) * DoubleVector3(0.0, 1.0, 0.0),
        )
        assertVectorClose(
            DoubleVector3(1.0, 0.0, 0.0),
            DoubleMatrix3.rotationYDegrees(90.0) * DoubleVector3(0.0, 0.0, 1.0),
        )
        assertVectorClose(
            DoubleVector3(0.0, 1.0, 0.0),
            DoubleMatrix3.rotationZDegrees(90.0) * DoubleVector3(1.0, 0.0, 0.0),
        )

        val composed = DoubleMatrix3.rotationXyzDegrees(90.0, 90.0, 0.0)
        val expected =
            DoubleMatrix3.rotationZDegrees(0.0) *
                DoubleMatrix3.rotationYDegrees(90.0) *
                DoubleMatrix3.rotationXDegrees(90.0)

        assertEquals(expected, composed)
        assertVectorClose(DoubleVector3(1.0, -1.0, 0.0), composed * DoubleVector3(0.0, 1.0, 1.0))
    }

    @Test
    fun aMatrix3InverseUndoesARotation() {
        val rotation = DoubleMatrix3.rotationYDegrees(37.0) * DoubleMatrix3.rotationXDegrees(52.0)
        val inverse = rotation.inverse()

        assertMatrixNear(DoubleMatrix3.identity, rotation * inverse!!, 1e-12)
        // A rotation's inverse is its transpose -- a cheap check the adjugate implementation is right.
        assertMatrixNear(rotation.transpose(), inverse, 1e-12)
    }

    @Test
    fun aSingularMatrix3HasNoInverse() {
        // The third row is the first row doubled: the rows are linearly dependent, so det is zero.
        val singular = DoubleMatrix3.fromRows(
            listOf(
                listOf(1.0, 2.0, 3.0),
                listOf(4.0, 5.0, 6.0),
                listOf(2.0, 4.0, 6.0),
            ),
        )

        assertNull(singular.inverse(), "a rank-deficient 3 by 3 matrix has no inverse and must say so")
    }

    @Test
    fun anAffineInverseUndoesATranslateRotateScale() {
        val m = translate(3.0, -4.0, 5.0) *
            DoubleMatrix4.fromRotation(DoubleMatrix3.rotationZDegrees(37.0)) *
            scale(2.0, 0.5, 3.0)

        assertMatrixNear(DoubleMatrix4.identity, m * m.inverseAffine()!!, 1e-10)
    }

    @Test
    fun anAffineInverseOfASingularTransformIsNull() {
        assertNull(scale(1.0, 0.0, 1.0).inverseAffine(), "a zero-scale node has no inverse and must say so")
    }

    private fun translate(x: Double, y: Double, z: Double): DoubleMatrix4 = DoubleMatrix4.fromRows(
        listOf(
            listOf(1.0, 0.0, 0.0, x),
            listOf(0.0, 1.0, 0.0, y),
            listOf(0.0, 0.0, 1.0, z),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )

    private fun scale(x: Double, y: Double, z: Double): DoubleMatrix4 = DoubleMatrix4.fromRows(
        listOf(
            listOf(x, 0.0, 0.0, 0.0),
            listOf(0.0, y, 0.0, 0.0),
            listOf(0.0, 0.0, z, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )

    private fun assertVectorClose(expected: DoubleVector3, actual: DoubleVector3, tolerance: Double = 1e-12) {
        assertClose(expected.x, actual.x, tolerance)
        assertClose(expected.y, actual.y, tolerance)
        assertClose(expected.z, actual.z, tolerance)
    }

    private fun assertMatrixNear(expected: DoubleMatrix3, actual: DoubleMatrix3, tolerance: Double) {
        for (row in 0 until 3) for (column in 0 until 3) {
            assertClose(expected[row, column], actual[row, column], tolerance)
        }
    }

    private fun assertMatrixNear(expected: DoubleMatrix4, actual: DoubleMatrix4, tolerance: Double) {
        for (row in 0 until 4) for (column in 0 until 4) {
            assertClose(expected[row, column], actual[row, column], tolerance)
        }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        check(abs(expected - actual) <= tolerance) {
            "Expected $expected but was $actual (tolerance $tolerance)"
        }
    }
}
