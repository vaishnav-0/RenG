package com.rohittp.reng.internal.model

import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.glb.GltfNode
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleQuaternion
import com.rohittp.reng.internal.math.DoubleVector3

private val IDENTITY_TRANSLATION = DoubleVector3(0.0, 0.0, 0.0)
private val IDENTITY_ROTATION = DoubleQuaternion(0.0, 0.0, 0.0, 1.0)
private val IDENTITY_SCALE = DoubleVector3(1.0, 1.0, 1.0)

/**
 * One node's local translate/rotate/scale, either read off [GltfNode]'s own authored fields
 * ([localNodeTrs]) or supplied whole by an animation track sampled for the current frame. `copy`
 * is how a track that drives only one of the three components -- say `rotation` -- produces an
 * override that leaves the other two at their authored value: it starts from
 * `localNodeTrs(node)!!` and copies just the driven field, rather than this file offering any
 * partial-override representation of its own.
 */
internal data class NodeTrs(val translation: DoubleVector3, val rotation: DoubleQuaternion, val scale: DoubleVector3)

/**
 * [node]'s own authored TRS, or `null` when [node] instead carries a baked [GltfNode.matrix].
 * `parseGltf` already rejects a node declaring both `matrix` and any of translation/rotation/scale,
 * so a `matrix` node is never missing exactly the fields this reads. Each of the three components
 * defaults independently to the specification's own default -- zero translation, identity
 * rotation, unit scale -- exactly as an absent field means in glTF itself.
 */
internal fun localNodeTrs(node: GltfNode): NodeTrs? {
    if (node.matrix != null) return null
    return NodeTrs(
        translation = node.translation?.toVector3() ?: IDENTITY_TRANSLATION,
        rotation = node.rotation?.toQuaternion() ?: IDENTITY_ROTATION,
        scale = node.scale?.toVector3() ?: IDENTITY_SCALE,
    )
}

/**
 * [node]'s local transform. A [GltfNode.matrix] node is read straight off the document: glTF's
 * `matrix` is already 16 `Double`s in the same column-major order [DoubleMatrix4]'s primary
 * constructor expects, so no transpose or reinterpretation happens here.
 *
 * Otherwise composes as translate * rotate * scale -- glTF's own order -- from [override] when the
 * caller supplies one (an animation track sampled for this frame; see [NodeTrs] for how a partial
 * track still produces a complete one), else from [localNodeTrs]. [override] is never combined
 * with [node]'s authored fields here: whoever builds it is responsible for carrying forward the
 * channels no track drives, exactly as [NodeTrs]'s own KDoc describes.
 */
internal fun localNodeTransform(node: GltfNode, override: NodeTrs?): DoubleMatrix4 {
    val matrix = node.matrix
    if (matrix != null) return DoubleMatrix4(matrix)

    val trs = override ?: localNodeTrs(node)!!
    return translationMatrix(trs.translation) *
        DoubleMatrix4.fromRotation(trs.rotation.toRotationMatrix()) *
        scaleMatrix(trs.scale)
}

/**
 * The global (model-space) transform of every node in [document]'s node array, `null` for a node
 * the default scene's forest does not reach -- legal glTF, and a node that draws nothing rather
 * than a fault.
 *
 * The default scene is `document.defaultScene ?: document.scenes.single()`: `validateGltfFeatures`
 * already refuses an ambiguous scene (`SCENE_AMBIGUOUS`) before a document reaches this function,
 * so a document with no explicit `scene` is guaranteed to have exactly one, and `single()` cannot
 * throw here. This is a pre-order walk from each of that scene's root node indices;
 * `validateNodeGraph` (`internal/glb/GltfParse.kt`) already proved the whole node array is a set
 * of disjoint strict trees with bounded depth before a [GltfDocument] exists at all, so the walk
 * needs no cycle guard of its own.
 *
 * [overrides] supplies a complete, animation-sampled [NodeTrs] for the node indices it contains;
 * every other node composes from its own authored [localNodeTrs] (or [GltfNode.matrix]).
 *
 * Every term stays `Double` through this composition and narrows to `Float` only once, at the GL
 * uniform -- the same rule `internal.gl.SceneContent`'s KDoc already states for camera-relative
 * values ("`Double` matrices and vectors throughout, narrowing to `Float` only in the column-major
 * array"). A model's node transforms are local and small, so `Float` would have been a defensible
 * choice on its own -- but composing in `Double` costs nothing measurable per model, and one
 * narrowing rule for the whole renderer is worth more than the allocation saved by a second one.
 */
internal fun composeGlobalTransforms(document: GltfDocument, overrides: Map<Int, NodeTrs>): List<DoubleMatrix4?> {
    val scene = document.defaultScene?.let { document.scenes[it] } ?: document.scenes.single()
    val globalTransforms = arrayOfNulls<DoubleMatrix4>(document.nodes.size)

    fun visit(nodeIndex: Int, parentTransform: DoubleMatrix4) {
        val node = document.nodes[nodeIndex]
        val global = parentTransform * localNodeTransform(node, overrides[nodeIndex])
        globalTransforms[nodeIndex] = global
        for (child in node.children) visit(child, global)
    }

    for (rootIndex in scene.nodes) visit(rootIndex, DoubleMatrix4.identity)

    return globalTransforms.toList()
}

private fun List<Double>.toVector3(): DoubleVector3 = DoubleVector3(this[0], this[1], this[2])

private fun List<Double>.toQuaternion(): DoubleQuaternion = DoubleQuaternion(this[0], this[1], this[2], this[3])

private fun translationMatrix(translation: DoubleVector3): DoubleMatrix4 = DoubleMatrix4.fromRows(
    listOf(
        listOf(1.0, 0.0, 0.0, translation.x),
        listOf(0.0, 1.0, 0.0, translation.y),
        listOf(0.0, 0.0, 1.0, translation.z),
        listOf(0.0, 0.0, 0.0, 1.0),
    ),
)

private fun scaleMatrix(scale: DoubleVector3): DoubleMatrix4 = DoubleMatrix4.fromRows(
    listOf(
        listOf(scale.x, 0.0, 0.0, 0.0),
        listOf(0.0, scale.y, 0.0, 0.0),
        listOf(0.0, 0.0, scale.z, 0.0),
        listOf(0.0, 0.0, 0.0, 1.0),
    ),
)
