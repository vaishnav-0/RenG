package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.gl.composeBackdropInverseViewProjection
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR 0072's uniform, checked as the thing a consumer actually does with it.
 *
 * The claim is not "a matrix was uploaded" -- it is that unprojecting a pixel through this matrix
 * and intersecting `z = 0` lands on the ground point RenG itself would name. So every case below
 * compares against [physicalPixelGroundRay], which reaches the same answer by trigonometry on the
 * near-plane ray and shares no arithmetic with the matrix path. Both being wrong the same way is
 * the only way this passes falsely.
 */
class BackdropInverseViewProjectionTest {

    @Test
    fun unprojectingAPixelLandsOnTheGroundPointRengWouldName() {
        var compared = 0
        for (pitch in listOf(0.0, 20.0, 45.0, 60.0, 66.0)) {
            for (bearing in listOf(0.0, 35.0, 210.0)) {
                val camera = resolvedCamera(pitch = pitch, bearing = bearing)
                // Through the production function, not a local product: the factor order is the
                // thing most able to be wrong and least able to be seen, so the test must observe
                // the renderer's choice rather than restate it.
                val inverse = assertNotNull(
                    composeBackdropInverseViewProjection(camera),
                    "pitch=$pitch bearing=$bearing",
                )

                for (pixelX in listOf(1, WIDTH / 2, WIDTH - 2)) {
                    for (pixelY in listOf(HEIGHT / 2, HEIGHT - 2)) {
                        val expected = physicalPixelGroundRay(camera, pixelX, pixelY)
                        if (expected !is GroundRayResult.Hit) continue

                        val ground = groundUnderPixel(inverse, pixelX, pixelY)
                        assertNotNull(ground, "pitch=$pitch bearing=$bearing pixel=$pixelX,$pixelY")

                        // Back to normalised Mercator the way physicalPixelGroundRay does, so the
                        // two are compared in one space: east is +x, north is -y.
                        val mercatorX = camera.mercatorAnchor.x + ground.first / camera.worldSizeLogicalPixels
                        val mercatorY = camera.mercatorAnchor.y - ground.second / camera.worldSizeLogicalPixels

                        val label = "pitch=$pitch bearing=$bearing pixel=$pixelX,$pixelY"
                        assertTrue(abs(mercatorX - expected.point.x) < TOLERANCE, "$label x")
                        assertTrue(abs(mercatorY - expected.point.y) < TOLERANCE, "$label y")
                        compared++
                    }
                }
            }
        }

        // The loop skips every ray that misses the ground, so without this the whole case passes
        // by comparing nothing -- which is how a fixture stops measuring its subject silently.
        assertEquals(EXPECTED_COMPARISONS, compared, "the cross-check must actually compare")
    }

    /**
     * The trap this replaced. A view matrix is affine and a projection is not, so `inverseAffine`
     * would have silently inverted a matrix whose bottom row it assumes -- and `[0, 0, -1, 0]` is
     * exactly the row it assumes away.
     */
    @Test
    fun theViewProjectionIsNotAffineSoTheAffineInverseWouldHaveBeenWrong() {
        val camera = resolvedCamera(pitch = 45.0, bearing = 0.0)
        val viewProjection = camera.projectionMatrix * camera.viewMatrix

        assertEquals(0.0, camera.viewMatrix[3, 0])
        assertEquals(0.0, camera.viewMatrix[3, 1])
        assertEquals(0.0, camera.viewMatrix[3, 2])
        assertEquals(1.0, camera.viewMatrix[3, 3])

        val bottomRowIsAffine = viewProjection[3, 0] == 0.0 &&
            viewProjection[3, 1] == 0.0 &&
            viewProjection[3, 2] == 0.0 &&
            viewProjection[3, 3] == 1.0
        assertTrue(!bottomRowIsAffine, "a projection's bottom row must not look affine")
    }

    /** The general inverse is an inverse: the product with its source is the identity. */
    @Test
    fun theInverseTimesItsSourceIsTheIdentity() {
        for (pitch in listOf(0.0, 30.0, 66.0)) {
            val camera = resolvedCamera(pitch = pitch, bearing = 17.0)
            val viewProjection = camera.projectionMatrix * camera.viewMatrix
            val product = viewProjection * assertNotNull(viewProjection.inverse())

            for (row in 0 until 4) {
                for (column in 0 until 4) {
                    val expected = if (row == column) 1.0 else 0.0
                    assertTrue(
                        abs(product[row, column] - expected) < 1e-9,
                        "pitch=$pitch [$row,$column] was ${product[row, column]}",
                    )
                }
            }
        }
    }

    /**
     * A leading zero on the diagonal, which elimination without partial pivoting divides by. The
     * matrix is perfectly invertible; only the pivot search makes it so.
     */
    @Test
    fun aMatrixWhoseFirstPivotIsZeroStillInverts() {
        val needsPivoting = DoubleMatrix4.fromRows(
            listOf(
                listOf(0.0, 1.0, 0.0, 0.0),
                listOf(1.0, 0.0, 0.0, 0.0),
                listOf(0.0, 0.0, 0.0, 1.0),
                listOf(0.0, 0.0, 1.0, 0.0),
            ),
        )

        val product = needsPivoting * assertNotNull(needsPivoting.inverse())
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                val expected = if (row == column) 1.0 else 0.0
                assertTrue(abs(product[row, column] - expected) < 1e-12, "[$row,$column]")
            }
        }
    }

    /** A singular matrix has no inverse and says so, rather than returning one built from zero. */
    @Test
    fun aSingularMatrixInvertsToNull() {
        val singular = DoubleMatrix4.fromRows(
            listOf(
                listOf(1.0, 2.0, 3.0, 4.0),
                listOf(2.0, 4.0, 6.0, 8.0),
                listOf(0.0, 1.0, 0.0, 1.0),
                listOf(1.0, 0.0, 1.0, 0.0),
            ),
        )

        assertEquals(null, singular.inverse())
    }

    /**
     * Unprojects the centre of pixel ([pixelX], [pixelY]) through [inverse] and intersects `z = 0`,
     * which is the recipe ADR 0072 documents. Two finite depths rather than one: under reverse-Z
     * with no far plane the far depth is a direction, not a point.
     */
    private fun groundUnderPixel(inverse: FloatArray, pixelX: Int, pixelY: Int): Pair<Double, Double>? {
        val ndcX = 2.0 * (pixelX + 0.5) / WIDTH - 1.0
        val ndcY = 1.0 - 2.0 * (pixelY + 0.5) / HEIGHT
        val near = unproject(inverse, ndcX, ndcY, 1.0) ?: return null
        val further = unproject(inverse, ndcX, ndcY, 0.0) ?: return null

        val dz = further[2] - near[2]
        if (abs(dz) < 1e-12) return null
        val t = -near[2] / dz
        return (near[0] + t * (further[0] - near[0])) to (near[1] + t * (further[1] - near[1]))
    }

    /** Column-major, so element `(row, column)` is at `column * 4 + row` -- as GL reads it. */
    private fun unproject(inverse: FloatArray, x: Double, y: Double, z: Double): DoubleArray? {
        val out = DoubleArray(4)
        for (row in 0 until 4) {
            out[row] = inverse[row].toDouble() * x +
                inverse[4 + row].toDouble() * y +
                inverse[8 + row].toDouble() * z +
                inverse[12 + row].toDouble()
        }
        if (abs(out[3]) < 1e-12) return null
        return doubleArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3], 1.0)
    }

    private fun resolvedCamera(pitch: Double, bearing: Double): ResolvedMercatorCamera {
        val outcome = resolveMercatorCamera(
            Camera(latitude = 12.5, unwrappedLongitude = 77.25, zoom = 11.0, bearing = bearing, pitch = pitch),
            OutputPixelSize(WIDTH, HEIGHT),
        )
        return (outcome as SpatialOutcome.Success).value
    }

    private companion object {
        const val WIDTH: Int = 1080
        const val HEIGHT: Int = 1920

        /**
         * Normalised Mercator, where the whole world is 1.0 and this camera's frame spans about
         * 5e-4 of it, so this is roughly a thousandth of a frame -- far tighter than the Float the
         * uniform is narrowed to could hide a real disagreement behind.
         */
        const val TOLERANCE: Double = 5e-7

        /** Five pitches x three bearings x three columns x two rows, every one of which hits. */
        const val EXPECTED_COMPARISONS: Int = 90
    }
}
