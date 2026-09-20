package com.rohittp.reng.internal.model

import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.gl.GL_UNSIGNED_INT
import com.rohittp.reng.internal.gl.GL_UNSIGNED_SHORT
import com.rohittp.reng.internal.gl.TextureSamplerState
import com.rohittp.reng.internal.gl.gltfSamplerState
import com.rohittp.reng.internal.glb.GlbScan
import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.glb.GltfFeatureResult
import com.rohittp.reng.internal.glb.GltfParseResult
import com.rohittp.reng.internal.glb.GltfPrimitive
import com.rohittp.reng.internal.glb.MAXIMUM_GLB_NODE_DEPTH
import com.rohittp.reng.internal.glb.parseGltf
import com.rohittp.reng.internal.glb.scanGlb
import com.rohittp.reng.internal.glb.validateGltfFeatures
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.image.projectedPngRgbaBytes
import com.rohittp.reng.internal.math.DoubleMatrix4

/** The attribute semantics RenG binds. Every other semantic `validateGltfFeatures` admits --
 * `TANGENT`, and `TEXCOORD_n`/`COLOR_n` above set zero -- carries data no draw call reads, so this
 * file never decodes one: the whole point of admitting them was to stop paying for bytes RenG
 * ignores. */
private const val POSITION_SEMANTIC = "POSITION"
private const val NORMAL_SEMANTIC = "NORMAL"
private const val TEX_COORD_SEMANTIC = "TEXCOORD_0"
private const val COLOUR_SEMANTIC = "COLOR_0"
private const val JOINTS_SEMANTIC = "JOINTS_0"
private const val WEIGHTS_SEMANTIC = "WEIGHTS_0"

/** Components per bound attribute, after this file's own widening: a `COLOR_0` is four whether it
 * was authored `VEC3` or `VEC4`. These restate the per-role table ADR 0028 put into
 * `validateGltfFeatures`, which is what makes them safe to assume rather than re-derive from each
 * accessor's `type`. */
private const val POSITION_COMPONENTS = 3
private const val NORMAL_COMPONENTS = 3
private const val TEX_COORD_COMPONENTS = 2
private const val COLOUR_COMPONENTS = 4
private const val INFLUENCE_COMPONENTS = 4

private const val VEC3_TYPE = "VEC3"

private const val FLOAT_BYTES = 4L
private const val SHORT_BYTES = 2L
private const val INT_BYTES = 4L

/** A `DoubleMatrix4`'s sixteen `Double`s. Boxing and object header are not counted -- this figure is
 * the payload, on the same footing as the arrays around it. */
private const val MATRIX_BYTES = 16L * 8L

/** The largest vertex count a generated `GL_UNSIGNED_SHORT` index run can address: indices `0` to
 * `65535` inclusive. One more vertex than that needs `GL_UNSIGNED_INT`. */
private const val MAXIMUM_SHORT_ADDRESSABLE_VERTICES = 65536

/** glTF's own default material, which the specification states as a complete material rather than
 * as "no material": opaque white, fully rough, fully metallic, single-sided. */
private const val DEFAULT_ALPHA_MODE = "OPAQUE"
private const val DEFAULT_ALPHA_CUTOFF = 0.5f
private const val BASE_COLOUR_COMPONENTS = 4

/**
 * One primitive's geometry, de-interleaved into one tightly packed array per attribute and paired
 * with the material it draws under.
 *
 * **Every attribute is its own array, never a stride into a shared one.** The GL seam supports
 * strided uploads and glTF's own buffer views are frequently interleaved, so the alternative -- one
 * vertex buffer per buffer view, with per-attribute offset and stride bookkeeping -- was available
 * and was rejected. One packed array per attribute removes a whole class of stride bugs at a cost
 * that is bounded: external URIs are refused, so all of a model's geometry is inside its GLB and
 * therefore inside `ResourceLimits.maximumModelGlbBytes`. The expansion is up to fourfold -- a
 * normalized byte texture coordinate becomes a float, an unsigned-byte index becomes a short -- and
 * that expansion is exactly what [DecodedModel.decodedCpuBytes] reports rather than hides.
 *
 * [joints] is a `FloatArray` of joint *indices*, not an integer array, and that is deliberate: every
 * index a 256-joint rig can name is exact in `Float`, `glVertexAttribPointer` converts integer
 * client data to float at no cost, and the vertex shader indexes with `int(...)`. This is why the GL
 * seam carries no `glVertexAttribIPointer` -- adding one to four platform bindings would buy
 * nothing.
 *
 * [indices] is never absent. A primitive glTF authored without an index accessor is given a
 * generated `0..n-1` run here, so exactly one draw shape exists downstream: `glDrawElements`.
 */
internal class DecodedPrimitive(
    val positions: FloatArray,
    val normals: FloatArray?,
    val texCoords: FloatArray?,
    val colours: FloatArray?,
    val joints: FloatArray?,
    val weights: FloatArray?,
    val indices: ModelIndices,
    val material: ResolvedMaterial,
)

/**
 * One `glDrawElements` the default scene asks for: primitive [primitiveIndex] of mesh [meshIndex],
 * drawn at node [nodeIndex]'s world transform, deformed by [skinIndex] when the node carries a skin.
 *
 * A draw item names a node *and* a primitive because two nodes may share one mesh -- the ordinary
 * way glTF instances geometry. Those are two draw items over one [DecodedPrimitive], not two copies
 * of it, which is why the primitive list is keyed by `(meshIndex, primitiveIndex)` and this list is
 * keyed by the scene walk.
 */
internal class ModelDrawItem(
    val nodeIndex: Int,
    val meshIndex: Int,
    val primitiveIndex: Int,
    val skinIndex: Int?,
)

/**
 * A primitive's material with every reference already followed: the base-colour factor as four
 * floats, the *image* index its base-colour texture resolves to (through
 * `material -> baseColorTexture -> textures[i].source -> images[j]`), the sampler that texture
 * declares, and the alpha and cull state.
 *
 * [baseColourImageIndex] names an entry of [DecodedModel.images], and the renderer is what decides
 * whether to bind it: a `Model.texture` override "replaces every rendered primitive's base-colour
 * texture while preserving other material properties" (`CONTEXT.md`), which is a substitution at
 * bind time over faithfully decoded authored materials, not a rewrite of them here.
 *
 * No metallic or roughness factor is carried. ADR 0026's one directional light with an ambient term
 * consumes neither, so resolving them would put two values on this type that nothing reads -- the
 * failure mode `ResourceLimits.maximumResidentGpuTextureBytes` already demonstrated once.
 */
internal class ResolvedMaterial(
    val baseColourFactor: FloatArray,
    val baseColourImageIndex: Int?,
    val baseColourSampler: TextureSamplerState,
    val alphaMode: String,
    val alphaCutoff: Float,
    val doubleSided: Boolean,
)

/**
 * One skin: the nodes whose animated transforms deform a mesh, one inverse bind matrix per joint,
 * and the skeleton root the specification defines as a hint.
 *
 * [inverseBindMatrices] always holds exactly one matrix per entry of [jointNodes]. A skin that
 * declares no `inverseBindMatrices` accessor takes the specification's own meaning for its absence
 * -- identity per joint -- and so does a skin no node references, whose accessor
 * `validateGltfFeatures` deliberately never looked at (18 of the consumer's 41 models carry such a
 * skins array). Reading an unvalidated accessor would refuse a drawable model over exporter debris;
 * inventing a rig for it is not a risk, because nothing can draw with a skin no node names.
 */
internal class DecodedSkin(
    val jointNodes: List<Int>,
    val inverseBindMatrices: List<DoubleMatrix4>,
    val skeletonRoot: Int?,
)

/**
 * A GLB decoded into everything a draw needs and nothing it does not: [primitives] keyed by
 * `(meshIndex, primitiveIndex)` -- use [primitiveFor] rather than recomputing the offset --
 * [drawItems] as the default scene's walk, [images] index-parallel with `document.images`, [skins]
 * index-parallel with `document.skins`, and the [document] and [bin] every later stage still reads
 * (node transforms, animation sampling).
 *
 * [decodedCpuBytes] is the expanded figure: the packed attribute arrays, the widened index runs, the
 * decoded RGBA8 rasters and the inverse bind matrices, never the bytes they were stored as. It is
 * bounded against `ResourceLimits.maximumDecodedImageBytes`, which is what keeps the de-interleaving
 * decision honest rather than hidden -- the cost shows up in the number the caller can see.
 */
internal class DecodedModel(
    val primitives: List<DecodedPrimitive>,
    val drawItems: List<ModelDrawItem>,
    val images: List<DecodedImage>,
    val skins: List<DecodedSkin>,
    val document: GltfDocument,
    val bin: BinChunk,
    val decodedCpuBytes: Long,
) {
    /** Where each mesh's primitives begin inside [primitives], as a running total with a trailing
     * entry. Derived rather than passed, so the flat list and its key cannot be given to this class
     * out of step with each other. */
    private val firstPrimitiveOfMesh: IntArray = IntArray(document.meshes.size + 1).also { offsets ->
        for (mesh in document.meshes.indices) {
            offsets[mesh + 1] = offsets[mesh] + document.meshes[mesh].primitives.size
        }
    }

    /** The [DecodedPrimitive] [item] draws. Two draw items over one mesh return the same instance. */
    fun primitiveFor(item: ModelDrawItem): DecodedPrimitive =
        primitives[firstPrimitiveOfMesh[item.meshIndex] + item.primitiveIndex]
}

/**
 * The outcome of [decodeModel]. The three failures are kept apart because the driver's own gates map
 * them to two different `RenGErrorCode`s, and a decode that reported the wrong one would send a
 * consumer to the wrong fix: [Malformed] means these bytes are not a well-formed glTF 2.0 binary,
 * [Unsupported] means they are and RenG does not draw what they ask for, and [TooLarge] means they
 * are drawable but their expanded form does not fit the caller's own budget.
 */
internal sealed interface ModelDecodeResult {
    class Success(val model: DecodedModel) : ModelDecodeResult

    data object Malformed : ModelDecodeResult

    data object Unsupported : ModelDecodeResult

    data object TooLarge : ModelDecodeResult
}

/**
 * Decodes [bytes] -- one GLB, whole -- into a draw-ready [DecodedModel], running the same
 * [scanGlb]/[parseGltf]/[validateGltfFeatures] sequence
 * [com.rohittp.reng.internal.driver.RenGClassGateRunner] runs, then reading the BIN chunk the two
 * gates never touch.
 *
 * **This is the third parse of the same bytes in a prepared frame.** `PARSE_GLB` and
 * `VALIDATE_GLB_FEATURES` each independently re-derive the document, because that interface answers
 * one gate at a time with no result cache between calls, and this function derives it a third time
 * for the same reason. The redundancy is accepted for this cycle and deliberately not fixed here,
 * because it was measured rather than waved away: over a real 133 KB model with a 3 KB JSON chunk
 * (2,629 vertices, 11,502 indices) on Apple M3 Max `macosArm64`, one `scanGlb` plus `parseGltf` is
 * **0.42 ms**, `validateGltfFeatures` over the parsed document is **0.007 ms**, and this whole
 * function -- its own parse included -- is **2.74 ms**. The two redundant parses are therefore
 * **0.85 ms of a 3.6 ms total, 24%**, and the fix for them is a cache with an eviction policy
 * attached, which is a design decision and not something a decode function should invent.
 *
 * The three failure results are not interchangeable: a container-framing fault is
 * [ModelDecodeResult.Malformed], a structural fault is [ModelDecodeResult.Malformed], and a feature
 * outside ADR 0021's subset is [ModelDecodeResult.Unsupported]. Faults the two gates could not have
 * seen -- an accessor whose span runs past the BIN chunk actually delivered, an index value naming
 * no vertex, an attribute whose element count disagrees with `POSITION`'s -- are
 * [ModelDecodeResult.Malformed] too, and are exactly the gap [GltfDocument]'s own documentation
 * warns about: `parseGltf` receives the BIN chunk's *length* and never its bytes.
 */
internal fun decodeModel(
    bytes: ByteArray,
    limits: ResourceLimits,
    reserveDecodedCpuBytes: (Long) -> Unit = {},
): ModelDecodeResult {
    val admitted = scanGlb(bytes, limits.maximumModelJsonChunkBytes) as? GlbScan.Admitted
        ?: return ModelDecodeResult.Malformed
    val binRange = admitted.binChunk ?: IntRange.EMPTY
    val parsed = parseGltf(admitted.json, admitted.binChunkLength, MAXIMUM_GLB_NODE_DEPTH)
    val document = (parsed as? GltfParseResult.Parsed)?.document ?: return ModelDecodeResult.Malformed
    when (validateGltfFeatures(document)) {
        GltfFeatureResult.Supported -> Unit
        is GltfFeatureResult.Unsupported -> return ModelDecodeResult.Unsupported
    }

    val decoder = ModelDecoder(
        document,
        BinChunk(bytes, binRange),
        limits.maximumDecodedImageBytes,
        limits.maximumImageDecodeWorkingBytes,
        reserveDecodedCpuBytes,
    )
    return try {
        ModelDecodeResult.Success(decoder.decode())
    } catch (signal: ModelDecodeSignal) {
        signal.result
    }
}

/** Internal control-flow signal: unwound by [decodeModel] into the [result] it carries, never seen
 * outside this file. The same shape `validateGltfFeatures` uses, and for the same reason -- the
 * alternative is threading a nullable through every read in the file and losing which of three
 * failures it was. */
private class ModelDecodeSignal(val result: ModelDecodeResult) : RuntimeException()

private fun fail(result: ModelDecodeResult): Nothing = throw ModelDecodeSignal(result)

private class ModelDecoder(
    private val document: GltfDocument,
    private val bin: BinChunk,
    private val budgetBytes: Long,
    private val imageWorkingBudgetBytes: Long,
    private val reserveDecodedCpuBytes: (Long) -> Unit,
) {
    private var decodedBytes: Long = 0L

    fun decode(): DecodedModel {
        // Projected before anything is allocated, so a model whose expanded geometry cannot fit the
        // budget is refused without first building the arrays that would not fit. The projection is
        // exact rather than an upper bound -- it applies the same widening rules the decode below
        // does -- so it can never refuse a model the accumulated figure would have admitted.
        val geometryBytes = projectedGeometryBytes()
        if (geometryBytes > budgetBytes) fail(ModelDecodeResult.TooLarge)
        reserveDecodedCpuBytes(geometryBytes)

        val primitives = document.meshes.flatMap { mesh -> mesh.primitives.map { decodePrimitive(it) } }
        val images = decodeImages()
        val skins = decodeSkins()
        if (decodedBytes > budgetBytes) fail(ModelDecodeResult.TooLarge)

        return DecodedModel(
            primitives = primitives,
            drawItems = drawItems(),
            images = images,
            skins = skins,
            document = document,
            bin = bin,
            decodedCpuBytes = decodedBytes,
        )
    }

    private fun projectedGeometryBytes(): Long {
        var total = 0L
        for (mesh in document.meshes) {
            for (primitive in mesh.primitives) {
                for ((semantic, accessorIndex) in primitive.attributes) {
                    val components = boundComponentCount(semantic) ?: continue
                    total += document.accessors[accessorIndex].count * components * FLOAT_BYTES
                }
                total += projectedIndexBytes(primitive)
            }
        }
        return total
    }

    private fun projectedIndexBytes(primitive: GltfPrimitive): Long {
        val vertexCount = document.accessors[primitive.attributes.getValue(POSITION_SEMANTIC)].count
        val accessorIndex = primitive.indices ?: return vertexCount * generatedIndexWidth(vertexCount)
        val accessor = document.accessors[accessorIndex]
        val width = if (accessor.componentType == UNSIGNED_INT_COMPONENT_TYPE) INT_BYTES else SHORT_BYTES
        return accessor.count * width
    }

    /**
     * One primitive, de-interleaved. Every optional attribute's element count is checked against
     * `POSITION`'s: separate packed arrays become separate vertex buffers, so a `NORMAL` array
     * shorter than `POSITION` would be read past its end by a vertex index that is perfectly legal
     * for the positions. The specification requires the counts to agree; nothing before this point
     * could check it, because `parseGltf` validates each accessor against its own buffer view and
     * never against a sibling attribute.
     */
    private fun decodePrimitive(primitive: GltfPrimitive): DecodedPrimitive {
        // GltfUnsupported.PRIMITIVE_WITHOUT_POSITION already refused a primitive without one.
        val positions = readAttribute(primitive, POSITION_SEMANTIC, POSITION_COMPONENTS, null)
            ?: fail(ModelDecodeResult.Malformed)
        val vertexCount = positions.size / POSITION_COMPONENTS

        val decoded = DecodedPrimitive(
            positions = positions,
            normals = readAttribute(primitive, NORMAL_SEMANTIC, NORMAL_COMPONENTS, vertexCount),
            texCoords = readAttribute(primitive, TEX_COORD_SEMANTIC, TEX_COORD_COMPONENTS, vertexCount),
            colours = readColours(primitive, vertexCount),
            joints = readAttribute(primitive, JOINTS_SEMANTIC, INFLUENCE_COMPONENTS, vertexCount),
            weights = readAttribute(primitive, WEIGHTS_SEMANTIC, INFLUENCE_COMPONENTS, vertexCount),
            indices = resolveIndices(primitive, vertexCount),
            material = resolveMaterial(primitive.material),
        )
        account(decoded)
        return decoded
    }

    /**
     * `primitive`'s [semantic] attribute as one packed `FloatArray`, or `null` when the primitive
     * does not carry it. A present attribute that cannot be read is a fault, never a silent absence:
     * substituting `null` there would draw the model with the attribute missing and report success.
     */
    private fun readAttribute(
        primitive: GltfPrimitive,
        semantic: String,
        components: Int,
        expectedVertexCount: Int?,
    ): FloatArray? {
        val accessorIndex = primitive.attributes[semantic] ?: return null
        val values = bin.readFloatElements(document, accessorIndex) ?: fail(ModelDecodeResult.Malformed)
        if (expectedVertexCount != null && values.size != expectedVertexCount * components) {
            fail(ModelDecodeResult.Malformed)
        }
        return values
    }

    /** `COLOR_0`, widened to `VEC4` with opaque alpha when it was authored `VEC3`. The renderer then
     * has one vertex colour shape rather than two, exactly as it has one index shape. */
    private fun readColours(primitive: GltfPrimitive, vertexCount: Int): FloatArray? {
        val accessorIndex = primitive.attributes[COLOUR_SEMANTIC] ?: return null
        val authored = bin.readFloatElements(document, accessorIndex) ?: fail(ModelDecodeResult.Malformed)
        val values = if (document.accessors[accessorIndex].type == VEC3_TYPE) widenToOpaqueVec4(authored) else authored
        if (values.size != vertexCount * COLOUR_COMPONENTS) fail(ModelDecodeResult.Malformed)
        return values
    }

    /**
     * The primitive's authored index run, or a generated `0..n-1` one when it has none.
     *
     * Every authored index value is proved to name a vertex that exists. [GltfDocument]'s own
     * documentation states that a parsed document does not guarantee this -- `parseGltf` never sees
     * the BIN chunk's bytes, so an accessor with no declared `max` leaves it unproved -- and this is
     * where the bytes finally exist to prove it against. Handing `glDrawElements` an index past the
     * end of a vertex buffer is undefined behaviour, from bytes a consumer supplied.
     */
    private fun resolveIndices(primitive: GltfPrimitive, vertexCount: Int): ModelIndices {
        val accessorIndex = primitive.indices ?: return generatedIndexRun(vertexCount)
        val indices = bin.readIndices(document, accessorIndex) ?: fail(ModelDecodeResult.Malformed)
        if (!addressesOnlyExistingVertices(indices, vertexCount)) fail(ModelDecodeResult.Malformed)
        return indices
    }

    /**
     * The material [materialIndex] names, or glTF's own default material when a primitive declares
     * none. The specification states that default as a real material rather than as an absence, and
     * RenG had no representation of it before this file: opaque white base colour, `alphaCutoff`
     * `0.5`, single-sided.
     *
     * A `baseColorFactor` that is present but is not four numbers is a fault. `parseGltf` reads it as
     * an unconstrained number list, so nothing before this point checked its length, and the two
     * available repairs -- pad with ones, or fall back to white -- both change the authored colour
     * silently.
     */
    private fun resolveMaterial(materialIndex: Int?): ResolvedMaterial {
        val material = materialIndex?.let { document.materials[it] }
            ?: return ResolvedMaterial(
                baseColourFactor = opaqueWhite(),
                baseColourImageIndex = null,
                baseColourSampler = gltfSamplerState(null),
                alphaMode = DEFAULT_ALPHA_MODE,
                alphaCutoff = DEFAULT_ALPHA_CUTOFF,
                doubleSided = false,
            )

        val pbr = material.pbrMetallicRoughness
        val texture = pbr?.baseColorTexture?.let { document.textures[it.index] }
        return ResolvedMaterial(
            baseColourFactor = baseColourFactor(pbr?.baseColorFactor),
            baseColourImageIndex = texture?.source,
            baseColourSampler = gltfSamplerState(texture?.sampler?.let { document.samplers[it] }),
            alphaMode = material.alphaMode,
            alphaCutoff = material.alphaCutoff.toFloat(),
            doubleSided = material.doubleSided,
        )
    }

    private fun baseColourFactor(authored: List<Double>?): FloatArray {
        if (authored == null) return opaqueWhite()
        if (authored.size != BASE_COLOUR_COMPONENTS) fail(ModelDecodeResult.Malformed)
        return FloatArray(BASE_COLOUR_COMPONENTS) { authored[it].toFloat() }
    }

    /**
     * Every embedded image, decoded to canonical RGBA8 and kept index-parallel with
     * `document.images`, which is what lets [ResolvedMaterial.baseColourImageIndex] be a glTF image
     * index rather than a remapped one. An image no material references is still decoded, and still
     * fails the decode if its bytes are not a PNG: `validateGltfFeatures` has already vouched for
     * every image's media type and embedding, so a payload that does not decode is a corrupt file
     * rather than the unvalidated debris an unreferenced *skin* is.
     *
     * A PNG's three failures map straight through to this file's three, so a JPEG mislabelled
     * `image/png` and a raster larger than the budget stay distinguishable at the caller.
     */
    private fun decodeImages(): List<DecodedImage> = document.images.map { image ->
        val view = document.bufferViews[image.bufferView ?: fail(ModelDecodeResult.Malformed)]
        val payload = bin.copyOfRange(view.byteOffset, view.byteLength) ?: fail(ModelDecodeResult.Malformed)
        projectedPngRgbaBytes(payload)?.let { projectedBytes ->
            if (projectedBytes > budgetBytes - decodedBytes) fail(ModelDecodeResult.TooLarge)
            reserveDecodedCpuBytes(projectedBytes)
        }
        val decoded = when (val result = decodePng(payload, budgetBytes, imageWorkingBudgetBytes)) {
            is PngDecodeResult.Success -> result.image
            is PngDecodeResult.Malformed -> fail(ModelDecodeResult.Malformed)
            is PngDecodeResult.Unsupported -> fail(ModelDecodeResult.Unsupported)
            PngDecodeResult.TooLarge -> fail(ModelDecodeResult.TooLarge)
        }
        decodedBytes += decoded.byteCount.toLong()
        if (decodedBytes > budgetBytes) fail(ModelDecodeResult.TooLarge)
        decoded
    }

    /**
     * Every skin, index-parallel with `document.skins` so [ModelDrawItem.skinIndex] -- which is a
     * node's own `skin` -- addresses it directly.
     *
     * A skin no node references has its `inverseBindMatrices` accessor left unread. That is not an
     * optimisation: `validateGltfFeatures` deliberately validates only referenced skins, on the
     * stated grounds that an unreferenced `skins` array is exporter debris that deforms nothing (18
     * of the consumer's 41 models carry one), so that accessor may legally be any shape at all.
     * Reading it would refuse a drawable model over data no draw call touches. It takes the
     * specification's own meaning for an absent accessor instead -- identity per joint -- and
     * nothing can draw with it, because no node names it.
     */
    private fun decodeSkins(): List<DecodedSkin> {
        val referenced = document.nodes.mapNotNull { it.skin }.toSet()
        return document.skins.mapIndexed { index, skin ->
            val accessorIndex = skin.inverseBindMatrices.takeIf { index in referenced }
            val projectedBytes = skin.joints.size * MATRIX_BYTES
            if (projectedBytes > budgetBytes - decodedBytes) fail(ModelDecodeResult.TooLarge)
            reserveDecodedCpuBytes(projectedBytes)
            val matrices = accessorIndex
                ?.let { bin.readMatrices(document, it) ?: fail(ModelDecodeResult.Malformed) }
                ?: List(skin.joints.size) { DoubleMatrix4.identity }
            // The specification requires one matrix per joint. A short accessor would leave a joint
            // with no bind pose, which the vertex shader would read as an out-of-range lookup.
            if (matrices.size != skin.joints.size) fail(ModelDecodeResult.Malformed)
            decodedBytes += projectedBytes
            DecodedSkin(jointNodes = skin.joints, inverseBindMatrices = matrices, skeletonRoot = skin.skeleton)
        }
    }

    /**
     * The default scene's mesh-bearing nodes, depth first, each node's own primitives before its
     * children's. `validateGltfFeatures` already refuses a document with no `scene` and anything
     * other than exactly one scene ([com.rohittp.reng.internal.glb.GltfUnsupported.SCENE_AMBIGUOUS]),
     * so `single()` here cannot throw. The recursion is bounded by [MAXIMUM_GLB_NODE_DEPTH], which
     * `parseGltf` already enforced over a hierarchy it also proved to be disjoint strict trees.
     */
    private fun drawItems(): List<ModelDrawItem> {
        val scene = document.defaultScene?.let { document.scenes[it] } ?: document.scenes.single()
        val items = mutableListOf<ModelDrawItem>()
        for (root in scene.nodes) appendDrawItems(root, items)
        return items
    }

    private fun appendDrawItems(nodeIndex: Int, into: MutableList<ModelDrawItem>) {
        val node = document.nodes[nodeIndex]
        val meshIndex = node.mesh
        if (meshIndex != null) {
            for (primitiveIndex in document.meshes[meshIndex].primitives.indices) {
                into += ModelDrawItem(nodeIndex, meshIndex, primitiveIndex, node.skin)
            }
        }
        for (child in node.children) appendDrawItems(child, into)
    }

    private fun account(primitive: DecodedPrimitive) {
        decodedBytes += floatBytes(primitive.positions) +
            floatBytes(primitive.normals) +
            floatBytes(primitive.texCoords) +
            floatBytes(primitive.colours) +
            floatBytes(primitive.joints) +
            floatBytes(primitive.weights) +
            (primitive.indices.shorts?.size ?: 0) * SHORT_BYTES +
            (primitive.indices.ints?.size ?: 0) * INT_BYTES
    }
}

/** The specification's `componentType` for a 32-bit unsigned index, the one index width that is not
 * two bytes wide after [readIndices]'s unsigned-byte widening. */
private const val UNSIGNED_INT_COMPONENT_TYPE = 5125

/** Components RenG reads for [semantic], or `null` for the semantics `validateGltfFeatures` admits
 * and this file never decodes. `COLOR_0` reports four whatever it was authored as, because it is
 * widened. */
private fun boundComponentCount(semantic: String): Int? = when (semantic) {
    POSITION_SEMANTIC -> POSITION_COMPONENTS
    NORMAL_SEMANTIC -> NORMAL_COMPONENTS
    TEX_COORD_SEMANTIC -> TEX_COORD_COMPONENTS
    COLOUR_SEMANTIC -> COLOUR_COMPONENTS
    JOINTS_SEMANTIC, WEIGHTS_SEMANTIC -> INFLUENCE_COMPONENTS
    else -> null
}

private fun generatedIndexWidth(vertexCount: Long): Long =
    if (vertexCount <= MAXIMUM_SHORT_ADDRESSABLE_VERTICES) SHORT_BYTES else INT_BYTES

private fun generatedIndexRun(vertexCount: Int): ModelIndices =
    if (vertexCount <= MAXIMUM_SHORT_ADDRESSABLE_VERTICES) {
        ModelIndices(ShortArray(vertexCount) { it.toShort() }, null, GL_UNSIGNED_SHORT, vertexCount)
    } else {
        ModelIndices(null, IntArray(vertexCount) { it }, GL_UNSIGNED_INT, vertexCount)
    }

/**
 * Whether every index in [indices] names one of [vertexCount] vertices. Both widths are read as the
 * unsigned patterns [ModelIndices] documents them to be, not as Kotlin's signed interpretation: an
 * index of `40000` is stored as the `Short` `-25536`, and reading it signed would report it as below
 * zero and therefore in range for the wrong reason.
 */
private fun addressesOnlyExistingVertices(indices: ModelIndices, vertexCount: Int): Boolean {
    val shorts = indices.shorts
    if (shorts != null) return shorts.all { (it.toInt() and 0xFFFF) < vertexCount }
    val ints = indices.ints ?: return false
    return ints.all { (it.toLong() and 0xFFFFFFFFL) < vertexCount.toLong() }
}

private fun widenToOpaqueVec4(values: FloatArray): FloatArray {
    val vertexCount = values.size / VEC3_COMPONENTS
    val widened = FloatArray(vertexCount * COLOUR_COMPONENTS)
    for (vertex in 0 until vertexCount) {
        widened[vertex * COLOUR_COMPONENTS] = values[vertex * VEC3_COMPONENTS]
        widened[vertex * COLOUR_COMPONENTS + 1] = values[vertex * VEC3_COMPONENTS + 1]
        widened[vertex * COLOUR_COMPONENTS + 2] = values[vertex * VEC3_COMPONENTS + 2]
        widened[vertex * COLOUR_COMPONENTS + 3] = 1.0f
    }
    return widened
}

private const val VEC3_COMPONENTS = 3

/** A fresh array per call: [ResolvedMaterial.baseColourFactor] is a `FloatArray`, and one shared
 * instance handed to every default-material primitive would let a write through any of them change
 * all the others. */
private fun opaqueWhite(): FloatArray = FloatArray(BASE_COLOUR_COMPONENTS) { 1.0f }

private fun floatBytes(values: FloatArray?): Long = (values?.size ?: 0) * FLOAT_BYTES
