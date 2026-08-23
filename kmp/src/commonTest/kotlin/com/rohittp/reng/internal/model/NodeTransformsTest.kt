package com.rohittp.reng.internal.model

import com.rohittp.reng.internal.glb.GltfAnimation
import com.rohittp.reng.internal.glb.GltfBuffer
import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.glb.GltfImage
import com.rohittp.reng.internal.glb.GltfMaterial
import com.rohittp.reng.internal.glb.GltfMesh
import com.rohittp.reng.internal.glb.GltfNode
import com.rohittp.reng.internal.glb.GltfSampler
import com.rohittp.reng.internal.glb.GltfScene
import com.rohittp.reng.internal.glb.GltfTexture
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleQuaternion
import com.rohittp.reng.internal.math.DoubleVector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeTransformsTest {
    @Test
    fun aTrsNodeComposesTranslateTimesRotateTimesScale() {
        // Order matters: T*R*S scales in local axes, S*R*T does not. A non-uniform scale plus a
        // non-zero rotation makes the two orders disagree.
        val node = trsNode(
            translation = listOf(1.0, 2.0, 3.0),
            rotation = listOf(0.0, 0.0, sin(PI / 4), cos(PI / 4)),
            scale = listOf(2.0, 1.0, 1.0),
        )

        val transform = localNodeTransform(node, override = null)

        val expected = translationMatrix(DoubleVector3(1.0, 2.0, 3.0)) *
            DoubleMatrix4.fromRotation(DoubleMatrix3.rotationZDegrees(90.0)) *
            scaleMatrix(DoubleVector3(2.0, 1.0, 1.0))
        assertMatrixNear(expected, transform, 1e-12)

        // Confirm the two orders really do disagree for this fixture, so the test above is
        // actually exercising the composition order rather than passing by coincidence.
        val wrongOrder = scaleMatrix(DoubleVector3(2.0, 1.0, 1.0)) *
            DoubleMatrix4.fromRotation(DoubleMatrix3.rotationZDegrees(90.0)) *
            translationMatrix(DoubleVector3(1.0, 2.0, 3.0))
        assertMatrixNotNear(wrongOrder, transform, 1e-6)
    }

    @Test
    fun aMatrixNodeIsReadColumnMajorStraightFromTheDocument() {
        val columnMajor = (1..16).map { it.toDouble() }
        val node = matrixNode(columnMajor)

        val transform = localNodeTransform(node, override = null)

        assertEquals(DoubleMatrix4(columnMajor), transform)
    }

    @Test
    fun localNodeTrsIsNullForAMatrixNode() {
        assertNull(localNodeTrs(matrixNode((1..16).map { it.toDouble() })))
    }

    @Test
    fun aChildTransformIsTheParentTimesItsOwnLocal() {
        val parent = trsNode(translation = listOf(5.0, 0.0, 0.0), children = listOf(1))
        val child = trsNode(translation = listOf(0.0, 3.0, 0.0))
        val document = documentWith(
            nodes = listOf(parent, child),
            scenes = listOf(GltfScene(nodes = listOf(0))),
            defaultScene = 0,
        )

        val transforms = composeGlobalTransforms(document, emptyMap())

        assertEquals(localNodeTransform(parent, null), transforms[0])
        val expectedChild = localNodeTransform(parent, null) * localNodeTransform(child, null)
        assertEquals(expectedChild, transforms[1])
    }

    @Test
    fun aNodeOutsideTheDefaultSceneHasNoGlobalTransform() {
        val reachable = trsNode()
        val unreachable = trsNode()
        val document = documentWith(
            nodes = listOf(reachable, unreachable),
            scenes = listOf(GltfScene(nodes = listOf(0))),
            defaultScene = 0,
        )

        val transforms = composeGlobalTransforms(document, emptyMap())

        assertEquals(DoubleMatrix4.identity, transforms[0])
        assertNull(transforms[1], "a node no scene reaches draws nothing and has no global transform")
    }

    @Test
    fun anAnimationOverrideReplacesOnlyTheChannelsItDrives() {
        val node = trsNode(
            translation = listOf(1.0, 2.0, 3.0),
            rotation = listOf(0.0, 0.0, 0.0, 1.0),
            scale = listOf(2.0, 2.0, 2.0),
        )
        // A track driving only `rotation` copies the authored TRS forward and replaces just that
        // field -- exactly the reason NodeTrs is a data class with a `copy`.
        val rotationOnlyOverride = localNodeTrs(node)!!.copy(
            rotation = DoubleQuaternion(0.0, 0.0, sin(PI / 4), cos(PI / 4)),
        )

        val transform = localNodeTransform(node, rotationOnlyOverride)

        val expected = translationMatrix(DoubleVector3(1.0, 2.0, 3.0)) *
            DoubleMatrix4.fromRotation(DoubleMatrix3.rotationZDegrees(90.0)) *
            scaleMatrix(DoubleVector3(2.0, 2.0, 2.0))
        assertMatrixNear(expected, transform, 1e-12)
    }

    private fun trsNode(
        children: List<Int> = emptyList(),
        translation: List<Double>? = null,
        rotation: List<Double>? = null,
        scale: List<Double>? = null,
    ): GltfNode = GltfNode(
        children = children,
        mesh = null,
        skin = null,
        camera = null,
        matrix = null,
        translation = translation,
        rotation = rotation,
        scale = scale,
    )

    private fun matrixNode(matrix: List<Double>, children: List<Int> = emptyList()): GltfNode = GltfNode(
        children = children,
        mesh = null,
        skin = null,
        camera = null,
        matrix = matrix,
        translation = null,
        rotation = null,
        scale = null,
    )

    private fun documentWith(
        nodes: List<GltfNode>,
        scenes: List<GltfScene>,
        defaultScene: Int?,
    ): GltfDocument = GltfDocument(
        accessors = emptyList(),
        bufferViews = emptyList(),
        meshes = emptyList<GltfMesh>(),
        nodes = nodes,
        scenes = scenes,
        defaultScene = defaultScene,
        animations = emptyList<GltfAnimation>(),
        materials = emptyList<GltfMaterial>(),
        images = emptyList<GltfImage>(),
        textures = emptyList<GltfTexture>(),
        samplers = emptyList<GltfSampler>(),
        extensionsRequired = emptyList(),
        buffers = emptyList<GltfBuffer>(),
    )

    private fun translationMatrix(v: DoubleVector3): DoubleMatrix4 = DoubleMatrix4.fromRows(
        listOf(
            listOf(1.0, 0.0, 0.0, v.x),
            listOf(0.0, 1.0, 0.0, v.y),
            listOf(0.0, 0.0, 1.0, v.z),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )

    private fun scaleMatrix(v: DoubleVector3): DoubleMatrix4 = DoubleMatrix4.fromRows(
        listOf(
            listOf(v.x, 0.0, 0.0, 0.0),
            listOf(0.0, v.y, 0.0, 0.0),
            listOf(0.0, 0.0, v.z, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )

    private fun assertMatrixNear(expected: DoubleMatrix4, actual: DoubleMatrix4, tolerance: Double) {
        for (row in 0 until 4) for (column in 0 until 4) {
            val e = expected[row, column]
            val a = actual[row, column]
            check(abs(e - a) <= tolerance) {
                "Expected matrix element [$row, $column] to be $e but was $a (tolerance $tolerance)"
            }
        }
    }

    private fun assertMatrixNotNear(expected: DoubleMatrix4, actual: DoubleMatrix4, tolerance: Double) {
        var anyDiffers = false
        for (row in 0 until 4) for (column in 0 until 4) {
            if (abs(expected[row, column] - actual[row, column]) > tolerance) anyDiffers = true
        }
        check(anyDiffers) { "Expected the two matrices to differ by more than $tolerance somewhere, but they matched" }
    }
}
