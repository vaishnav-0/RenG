package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.model.DecodedPrimitive
import com.rohittp.reng.internal.model.DecodedSkin
import com.rohittp.reng.internal.model.ModelIndices
import com.rohittp.reng.internal.model.ResolvedMaterial
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * The six vertex attributes RenG binds for a glTF primitive, and the `layout(location = ...)` each
 * one occupies in every model shader variant.
 *
 * The locations are fixed rather than looked up because one uploaded primitive is drawn by whichever
 * of the eight variants its material selects, and a VAO records attribute indices, not names: a
 * per-variant location would need a per-variant VAO of the same buffers. Every variant therefore
 * declares the same locations, and a variant that does not use one simply leaves it inactive, which
 * costs an enabled-but-unread attribute array and nothing else.
 *
 * [ModelVertexAttribute.JOINTS] is bound as `GL_FLOAT` and read in the shader with `int(...)`, not as
 * an integer attribute — `internal.model.DecodedPrimitive.joints` already produced joint indices as a
 * `FloatArray` because every index a 256-joint rig can name is exact in `Float`. That is why the GL
 * seam carries no `glVertexAttribIPointer`.
 */
internal enum class ModelVertexAttribute(val location: Int, val componentCount: Int, val glslName: String) {
    POSITION(0, 3, MODEL_POSITION_ATTRIBUTE_NAME),
    NORMAL(1, 3, MODEL_NORMAL_ATTRIBUTE_NAME),
    TEX_COORD(2, 2, MODEL_TEX_COORD_ATTRIBUTE_NAME),
    COLOUR(3, 4, MODEL_COLOUR_ATTRIBUTE_NAME),
    JOINTS(4, 4, MODEL_JOINTS_ATTRIBUTE_NAME),
    WEIGHTS(5, 4, MODEL_WEIGHTS_ATTRIBUTE_NAME),
}

/**
 * The model shaders' own names.
 *
 * **None of these may ever appear in [RESERVED_SHADER_NAMES].** That set is the *public* shader
 * contract ADR 0008 fixes for a consumer's `Geometry` pair, and ADR 0024 closes with the warning that
 * renaming a documented name there is a **silent** breaking change. A model shader name colliding
 * with one would quietly grow that contract by making a consumer-facing name look like it had two
 * meanings; every name below is `reng`-prefixed precisely so the two sets cannot meet.
 */
internal const val MODEL_POSITION_ATTRIBUTE_NAME: String = "rengModelPosition"
internal const val MODEL_NORMAL_ATTRIBUTE_NAME: String = "rengModelNormal"
internal const val MODEL_TEX_COORD_ATTRIBUTE_NAME: String = "rengModelTexCoord"
internal const val MODEL_COLOUR_ATTRIBUTE_NAME: String = "rengModelColour"
internal const val MODEL_JOINTS_ATTRIBUTE_NAME: String = "rengModelJoints"
internal const val MODEL_WEIGHTS_ATTRIBUTE_NAME: String = "rengModelWeights"

internal const val MODEL_VIEW_PROJECTION_UNIFORM_NAME: String = "rengModelViewProjection"
internal const val MODEL_NORMAL_MATRIX_UNIFORM_NAME: String = "rengModelNormalMatrix"
internal const val MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME: String = "rengModelBaseColourFactor"
internal const val MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME: String = "rengModelBaseColourTexture"
internal const val MODEL_ALPHA_CUTOFF_UNIFORM_NAME: String = "rengModelAlphaCutoff"
internal const val MODEL_LIGHT_DIRECTION_UNIFORM_NAME: String = "rengModelLightDirection"
internal const val MODEL_AMBIENT_UNIFORM_NAME: String = "rengModelAmbient"

/**
 * Whether the written alpha is forced to one (`1.0`) or is the material's own (`0.0`).
 *
 * **Not in the plan's uniform roster, and needed.** glTF states that `OPAQUE` ignores alpha entirely
 * and that `MASK` is fully opaque once its cutoff test passes, while `BLEND` writes the real value.
 * The offscreen surface is RGBA and the composite blends by alpha, so an `OPAQUE` material whose
 * factor or texture carries alpha below one would otherwise fade into whatever a consumer composites
 * RenG over — invisible against an opaque basemap, wrong everywhere else. The plan fixes the variant
 * set at skinned x textured x masked and states that `OPAQUE` and `BLEND` differ in *state* rather
 * than in source, and a uniform's value is exactly that: program state, set per draw, costing no
 * extra program. Making it a ninth variant dimension would double the compiled set to serve a
 * one-instruction difference.
 */
internal const val MODEL_FORCE_OPAQUE_UNIFORM_NAME: String = "rengModelForceOpaque"

/**
 * Whether the primitive actually carries a `COLOR_0` attribute (`1.0`) or not (`0.0`).
 *
 * **Not in the plan's uniform roster, and needed for the same structural reason.** A disabled vertex
 * attribute array reads GL's current generic attribute value, which is `(0, 0, 0, 1)` — black. Since
 * `COLOR_0` multiplies the base colour, a primitive without one would render **black** rather than
 * unmodified, and the great majority of glTF primitives have no `COLOR_0` at all. The three repairs
 * available without this flag are all worse: uploading a synthetic white colour buffer per primitive
 * contradicts "one buffer per present attribute", doubling the variant set spends eight programs on a
 * multiply, and inferring absence from the value itself misreads a legitimately black vertex colour.
 * The GL seam has no `glVertexAttrib4f` and this task adds no entry point.
 *
 * A degenerate *normal* needs no equivalent flag, and that asymmetry is deliberate: glTF requires
 * `NORMAL` to be unit length, so a zero-length normal cannot be authored data and the fragment shader
 * can safely read it as "this primitive has no normals" and shade the fragment fully lit. A zero
 * vertex *colour* is perfectly legal authored data and can never be self-identifying.
 */
internal const val MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME: String = "rengModelVertexColourPresent"

/**
 * The joint-matrix uniform block, bound at [RENG_JOINT_UNIFORM_BINDING_POINT].
 *
 * Capitalised because a GLSL interface block name is a type name, which is also why it is the one
 * model shader name not reachable through [GlBinding.getUniformLocation]: it is looked up with
 * [GlBinding.getUniformBlockIndex] instead, and so is excluded from [modelShaderNames].
 */
internal const val MODEL_JOINT_BLOCK_NAME: String = "RengModelJoints"
internal const val MODEL_JOINT_MATRICES_UNIFORM_NAME: String = "rengModelJointMatrices"

/** The varyings and the fragment output, listed so [modelShaderNames] can prove the whole set. */
internal const val MODEL_UV_VARYING_NAME: String = "rengModelUv"
internal const val MODEL_TINT_VARYING_NAME: String = "rengModelTint"
internal const val MODEL_SHADING_NORMAL_VARYING_NAME: String = "rengModelShadingNormal"
internal const val MODEL_FRAGMENT_COLOUR_OUTPUT_NAME: String = "rengModelFragmentColour"

/**
 * The most joints one skin may deform with.
 *
 * `256 mat4` is exactly the 16384 bytes GLES 3.0 and GL 3.3 both guarantee for a single uniform
 * block, and the consumer's own corpus tops out at 112 joints — so the block covers every model
 * anyone ships with headroom. A uniform *array* was rejected on the same measurement: GLSL ES 3.00
 * guarantees 256 `vec4` of vertex uniforms, 64 `mat4` before anything else RenG binds, which would
 * refuse the corpus's two 112-joint models outright.
 */
internal const val MAXIMUM_MODEL_JOINTS: Int = 256

/** `MAXIMUM_MODEL_JOINTS` `mat4` of four-byte floats: the std140 size of [MODEL_JOINT_BLOCK_NAME]. */
internal const val MODEL_JOINT_BLOCK_BYTES: Int = MAXIMUM_MODEL_JOINTS * 16 * Float.SIZE_BYTES

/** glTF's `alphaMode` values RenG treats specially; anything else takes the specification's default. */
internal const val MODEL_ALPHA_MODE_MASK: String = "MASK"
internal const val MODEL_ALPHA_MODE_BLEND: String = "BLEND"

/**
 * One compiled model program's identity.
 *
 * Eight variants exist, and only eight: [skinned] decides whether the vertex shader deforms by a
 * joint palette, [hasBaseColourTexture] whether the fragment shader samples a texture at all, and
 * [masked] whether it carries glTF's `alphaMode: MASK` `discard`. Every other per-primitive
 * difference — the base colour factor, the cutoff value, `doubleSided`, `OPAQUE` against `BLEND`,
 * whether a `COLOR_0` is present — is a uniform value or a piece of GL state, because each of those
 * would otherwise double the compiled set to buy a single instruction.
 */
internal data class ModelShaderVariant(
    val skinned: Boolean,
    val hasBaseColourTexture: Boolean,
    val masked: Boolean,
)

/** All eight variants, in a fixed order so a caller can build the pipeline map deterministically. */
internal fun allModelShaderVariants(): List<ModelShaderVariant> = BOOLEANS.flatMap { skinned ->
    BOOLEANS.flatMap { textured ->
        BOOLEANS.map { masked -> ModelShaderVariant(skinned, textured, masked) }
    }
}

/**
 * Every identifier RenG declares in a model shader and binds a value to, for the discipline check
 * that none of them collides with [RESERVED_SHADER_NAMES]. [MODEL_JOINT_BLOCK_NAME] is checked
 * separately; see its own documentation.
 */
internal fun modelShaderNames(): List<String> = listOf(
    MODEL_POSITION_ATTRIBUTE_NAME,
    MODEL_NORMAL_ATTRIBUTE_NAME,
    MODEL_TEX_COORD_ATTRIBUTE_NAME,
    MODEL_COLOUR_ATTRIBUTE_NAME,
    MODEL_JOINTS_ATTRIBUTE_NAME,
    MODEL_WEIGHTS_ATTRIBUTE_NAME,
    MODEL_VIEW_PROJECTION_UNIFORM_NAME,
    MODEL_NORMAL_MATRIX_UNIFORM_NAME,
    MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME,
    MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME,
    MODEL_ALPHA_CUTOFF_UNIFORM_NAME,
    MODEL_LIGHT_DIRECTION_UNIFORM_NAME,
    MODEL_AMBIENT_UNIFORM_NAME,
    MODEL_FORCE_OPAQUE_UNIFORM_NAME,
    MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME,
    MODEL_JOINT_MATRICES_UNIFORM_NAME,
    MODEL_UV_VARYING_NAME,
    MODEL_TINT_VARYING_NAME,
    MODEL_SHADING_NORMAL_VARYING_NAME,
    MODEL_FRAGMENT_COLOUR_OUTPUT_NAME,
)

/**
 * [variant]'s vertex source, authored as a GLES 3.00 source exactly as the sticker, ground and
 * composite pairs are, so it travels the same
 * [com.rohittp.reng.internal.shader.ShaderProfilePlan.sourceFor] dialect substitution a consumer's
 * own [ShaderPair] does (ADR 0008) — RenG is not exempt from its own rule.
 *
 * A skinned variant folds the joint palette into the position *and* into the normal, because a joint
 * matrix rotates and scales as well as translating; skinning the position while leaving the normal
 * in bind pose lights a deformed mesh as though it were still standing still.
 */
internal fun modelVertexSource(variant: ModelShaderVariant): String = buildString {
    append("#version 300 es\n")
    ModelVertexAttribute.entries.forEach { attribute ->
        if (attribute.isSkinningOnly && !variant.skinned) return@forEach
        append("layout(location = ${attribute.location}) in ${attribute.glslType} ${attribute.glslName};\n")
    }
    append("uniform mat4 $MODEL_VIEW_PROJECTION_UNIFORM_NAME;\n")
    append("uniform mat4 $MODEL_NORMAL_MATRIX_UNIFORM_NAME;\n")
    append("uniform float $MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME;\n")
    if (variant.skinned) {
        append("layout(std140) uniform $MODEL_JOINT_BLOCK_NAME {\n")
        append("    mat4 $MODEL_JOINT_MATRICES_UNIFORM_NAME[$MAXIMUM_MODEL_JOINTS];\n")
        append("};\n")
    }
    append("out vec2 $MODEL_UV_VARYING_NAME;\n")
    append("out vec4 $MODEL_TINT_VARYING_NAME;\n")
    append("out vec3 $MODEL_SHADING_NORMAL_VARYING_NAME;\n")
    append("void main() {\n")
    if (variant.skinned) {
        append("    mat4 rengModelSkin =\n")
        listOf("x", "y", "z", "w").forEachIndexed { index, component ->
            val terminator = if (index == 3) ";" else " +"
            append(
                "        $MODEL_WEIGHTS_ATTRIBUTE_NAME.$component * " +
                    "$MODEL_JOINT_MATRICES_UNIFORM_NAME[int($MODEL_JOINTS_ATTRIBUTE_NAME.$component)]" +
                    "$terminator\n",
            )
        }
        append("    vec4 rengModelSkinnedPosition = rengModelSkin * vec4($MODEL_POSITION_ATTRIBUTE_NAME, 1.0);\n")
        append("    vec3 rengModelSkinnedNormal = mat3(rengModelSkin) * $MODEL_NORMAL_ATTRIBUTE_NAME;\n")
    } else {
        append("    vec4 rengModelSkinnedPosition = vec4($MODEL_POSITION_ATTRIBUTE_NAME, 1.0);\n")
        append("    vec3 rengModelSkinnedNormal = $MODEL_NORMAL_ATTRIBUTE_NAME;\n")
    }
    append("    $MODEL_UV_VARYING_NAME = $MODEL_TEX_COORD_ATTRIBUTE_NAME;\n")
    append(
        "    $MODEL_TINT_VARYING_NAME = mix(vec4(1.0), $MODEL_COLOUR_ATTRIBUTE_NAME, " +
            "$MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME);\n",
    )
    append(
        "    $MODEL_SHADING_NORMAL_VARYING_NAME = mat3($MODEL_NORMAL_MATRIX_UNIFORM_NAME) * " +
            "rengModelSkinnedNormal;\n",
    )
    append("    gl_Position = $MODEL_VIEW_PROJECTION_UNIFORM_NAME * rengModelSkinnedPosition;\n")
    append("}\n")
}

/**
 * [variant]'s fragment source: glTF's base colour product, ADR 0026's one light, and the alpha rule
 * the variant's `alphaMode` asks for.
 *
 * **The sampled texel is premultiplied and this source unpremultiplies it.** `uploadTexture`
 * premultiplies every [TextureContent.IMAGE] before upload so that GL's bilinear filter — which runs
 * on the stored bytes before any shader sees them — cannot bleed a transparent texel's meaningless
 * RGB into a visible edge. Recovering the straight colour after filtering is the correct other half
 * of that: glTF's own base colour arithmetic multiplies unpremultiplied colours, and the cutoff test
 * compares an unpremultiplied alpha. The guard against dividing by a zero alpha yields black, which
 * is what a fully transparent texel means once its alpha has been applied.
 *
 * **The written colour is premultiplied again at the very end**, because every producer into RenG's
 * offscreen surface writes premultiplied colour and the composite pass blends with
 * `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` (see [drawFrame]).
 */
internal fun modelFragmentSource(variant: ModelShaderVariant): String = buildString {
    append("#version 300 es\n")
    append("precision highp float;\n")
    append("uniform vec4 $MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME;\n")
    if (variant.hasBaseColourTexture) {
        append("uniform sampler2D $MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME;\n")
    }
    if (variant.masked) {
        append("uniform float $MODEL_ALPHA_CUTOFF_UNIFORM_NAME;\n")
    }
    append("uniform vec3 $MODEL_LIGHT_DIRECTION_UNIFORM_NAME;\n")
    append("uniform float $MODEL_AMBIENT_UNIFORM_NAME;\n")
    append("uniform float $MODEL_FORCE_OPAQUE_UNIFORM_NAME;\n")
    append("in vec2 $MODEL_UV_VARYING_NAME;\n")
    append("in vec4 $MODEL_TINT_VARYING_NAME;\n")
    append("in vec3 $MODEL_SHADING_NORMAL_VARYING_NAME;\n")
    append("layout(location = 0) out vec4 $MODEL_FRAGMENT_COLOUR_OUTPUT_NAME;\n")
    append("void main() {\n")
    append("    vec4 rengModelBase = $MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME * $MODEL_TINT_VARYING_NAME;\n")
    if (variant.hasBaseColourTexture) {
        append(
            "    vec4 rengModelTexel = texture($MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME, " +
                "$MODEL_UV_VARYING_NAME);\n",
        )
        append(
            "    vec3 rengModelTexelRgb = rengModelTexel.a > 0.0 ? " +
                "rengModelTexel.rgb / rengModelTexel.a : vec3(0.0);\n",
        )
        append(
            "    rengModelBase = vec4(rengModelBase.rgb * rengModelTexelRgb, " +
                "rengModelBase.a * rengModelTexel.a);\n",
        )
    }
    if (variant.masked) {
        append("    if (rengModelBase.a < $MODEL_ALPHA_CUTOFF_UNIFORM_NAME) discard;\n")
        append("    rengModelBase.a = 1.0;\n")
    }
    append("    float rengModelNormalLength = length($MODEL_SHADING_NORMAL_VARYING_NAME);\n")
    append("    float rengModelLambert = rengModelNormalLength > 0.0\n")
    append(
        "        ? max(dot($MODEL_SHADING_NORMAL_VARYING_NAME / rengModelNormalLength, " +
            "$MODEL_LIGHT_DIRECTION_UNIFORM_NAME), 0.0)\n",
    )
    append("        : 1.0;\n")
    append(
        "    float rengModelShade = $MODEL_AMBIENT_UNIFORM_NAME + " +
            "(1.0 - $MODEL_AMBIENT_UNIFORM_NAME) * rengModelLambert;\n",
    )
    append(
        "    float rengModelAlpha = mix(rengModelBase.a, 1.0, $MODEL_FORCE_OPAQUE_UNIFORM_NAME);\n",
    )
    append(
        "    $MODEL_FRAGMENT_COLOUR_OUTPUT_NAME = vec4(rengModelBase.rgb * rengModelShade * " +
            "rengModelAlpha, rengModelAlpha);\n",
    )
    append("}\n")
}

/** [variant]'s complete source pair, in the same [ShaderPair] shape a consumer's own geometry uses. */
internal fun modelShaderPair(variant: ModelShaderVariant): ShaderPair =
    ShaderPair(vertexSource = modelVertexSource(variant), fragmentSource = modelFragmentSource(variant))

/**
 * One compiled model program plus the joint buffer a skinned variant deforms through.
 *
 * Every location field is whatever [GlBinding.getUniformLocation] returned at creation — negative
 * when this variant's source does not declare that name, which is the same "bind only when declared"
 * rule [GeometryPipeline] follows and the reason [drawModels] guards every `uniform*` call. Names
 * this variant is known not to declare are not even queried, so a driver that resolved them anyway
 * could not make [drawModels] set a uniform that does not exist.
 *
 * [jointBuffer] is `0` for an unskinned variant, which allocates no uniform buffer at all: a 16 KB
 * block on every static model is a cost with no purpose.
 */
internal class ModelPipeline(
    val key: ResourceKey,
    val variant: ModelShaderVariant,
    val program: Int,
    val jointBuffer: Int,
    val jointBlockIndex: Int,
    val modelViewProjectionLocation: Int,
    val normalMatrixLocation: Int,
    val baseColourFactorLocation: Int,
    val baseColourTextureLocation: Int,
    val alphaCutoffLocation: Int,
    val lightDirectionLocation: Int,
    val ambientLocation: Int,
    val forceOpaqueLocation: Int,
    val vertexColourPresentLocation: Int,
)

internal sealed interface ModelPipelineResult {
    data class Created(val pipeline: ModelPipeline) : ModelPipelineResult

    data class Failed(val failure: FailureDescriptor) : ModelPipelineResult
}

/**
 * Compiles (or reuses, via [cache]) [variant]'s program, keyed by
 * [ResourceKeyDeriver.internalPipeline] under [InternalPipelineRole.MODEL], and allocates the joint
 * uniform buffer when [variant] is skinned.
 *
 * **The context's `GL_MAX_UNIFORM_BLOCK_SIZE` is read once, before anything is compiled, and a value
 * below [MODEL_JOINT_BLOCK_BYTES] is a typed refusal.** Both GLES 3.0 and GL 3.3 guarantee that
 * figure, so a context reporting less is broken rather than merely small — but the way that would
 * otherwise surface is a failed link whose only diagnostic is an implementation-defined info log,
 * and RenG must never forward a driver's message to a consumer. The query runs for every variant,
 * skinned or not, because a renderer that can draw static models and silently cannot draw skinned
 * ones is a worse answer than refusing model support outright on a context that cannot carry it.
 *
 * A caller driving this with [RecordingGlBinding] must seed `GL_MAX_UNIFORM_BLOCK_SIZE`: the fake
 * answers an unseeded integer query with zero, which this correctly refuses.
 */
internal fun createModelPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    variant: ModelShaderVariant,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): ModelPipelineResult {
    val pair = modelShaderPair(variant)
    val key = deriver.internalPipeline(InternalPipelineRole.MODEL, pair).key

    val limits = IntArray(1)
    binding.getIntegerv(GL_MAX_UNIFORM_BLOCK_SIZE, limits)
    if (limits[0] < MODEL_JOINT_BLOCK_BYTES) {
        return ModelPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    }

    val vertexPlan = scanShaderProfile(pair.vertexSource)
        ?: return ModelPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(pair.fragmentSource)
        ?: return ModelPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return ModelPipelineResult.Failed(result.failure)
    }

    var jointBlockIndex = -1
    var jointBuffer = 0
    if (variant.skinned) {
        jointBlockIndex = binding.getUniformBlockIndex(program, MODEL_JOINT_BLOCK_NAME)
        if (jointBlockIndex >= 0) {
            binding.uniformBlockBinding(program, jointBlockIndex, RENG_JOINT_UNIFORM_BINDING_POINT)
            val names = IntArray(1)
            binding.genBuffers(1, names)
            jointBuffer = names[0]
            binding.bindBuffer(GL_UNIFORM_BUFFER, jointBuffer)
            binding.bufferData(GL_UNIFORM_BUFFER, MODEL_JOINT_BLOCK_BYTES, null, GL_DYNAMIC_DRAW)
        }
    }

    return ModelPipelineResult.Created(
        ModelPipeline(
            key = key,
            variant = variant,
            program = program,
            jointBuffer = jointBuffer,
            jointBlockIndex = jointBlockIndex,
            modelViewProjectionLocation = binding.getUniformLocation(program, MODEL_VIEW_PROJECTION_UNIFORM_NAME),
            normalMatrixLocation = binding.getUniformLocation(program, MODEL_NORMAL_MATRIX_UNIFORM_NAME),
            baseColourFactorLocation = binding.getUniformLocation(program, MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME),
            baseColourTextureLocation = if (variant.hasBaseColourTexture) {
                binding.getUniformLocation(program, MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME)
            } else {
                -1
            },
            alphaCutoffLocation = if (variant.masked) {
                binding.getUniformLocation(program, MODEL_ALPHA_CUTOFF_UNIFORM_NAME)
            } else {
                -1
            },
            lightDirectionLocation = binding.getUniformLocation(program, MODEL_LIGHT_DIRECTION_UNIFORM_NAME),
            ambientLocation = binding.getUniformLocation(program, MODEL_AMBIENT_UNIFORM_NAME),
            forceOpaqueLocation = binding.getUniformLocation(program, MODEL_FORCE_OPAQUE_UNIFORM_NAME),
            vertexColourPresentLocation = binding.getUniformLocation(
                program,
                MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME,
            ),
        ),
    )
}

internal fun deleteModelPipeline(binding: GlBinding, cache: GlProgramCache, pipeline: ModelPipeline) {
    if (pipeline.jointBuffer != 0) binding.deleteBuffers(1, intArrayOf(pipeline.jointBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * One decoded primitive's geometry, resident on the GPU: a vertex array recording one buffer per
 * attribute the primitive actually carries, plus its index run.
 *
 * [attributes] is what the primitive *has*, not what the shader declares, and it decides two things
 * [drawModels] cannot otherwise know: which of the eight variants may draw it (a base-colour texture
 * with no `TEXCOORD_0` to sample it with must fall back to the untextured variant rather than read an
 * undefined varying), and whether `rengModelVertexColourPresent` is on.
 */
internal class UploadedPrimitive(
    val vertexArray: Int,
    val buffers: List<Int>,
    val indexBuffer: Int,
    val indexCount: Int,
    val indexType: Int,
    val attributes: Set<ModelVertexAttribute>,
)

/**
 * Uploads [primitive]'s already-de-interleaved attribute arrays as one tightly packed vertex buffer
 * each, plus its index run, into a fresh vertex array.
 *
 * One buffer per **present** attribute and none for an absent one. `internal.model.decodeModel`
 * already de-interleaved every attribute into its own packed `FloatArray`, so no stride or offset
 * bookkeeping survives to this layer; an absent attribute simply leaves its location disabled, and
 * `rengModelVertexColourPresent` is what stops the one attribute whose GL default is harmful — the
 * vertex colour — from painting the primitive black.
 *
 * `indices` is never absent: `decodeModel` gives a primitive authored without an index accessor a
 * generated `0..n-1` run, so exactly one draw shape exists here, `glDrawElements`.
 */
internal fun uploadModelPrimitive(binding: GlBinding, primitive: DecodedPrimitive): UploadedPrimitive {
    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.bindVertexArray(vertexArray)

    val buffers = ArrayList<Int>(ModelVertexAttribute.entries.size)
    val attributes = LinkedHashSet<ModelVertexAttribute>()
    ModelVertexAttribute.entries.forEach { attribute ->
        val values = attribute.valuesOf(primitive) ?: return@forEach
        binding.genBuffers(1, names)
        val buffer = names[0]
        buffers += buffer
        attributes += attribute
        binding.bindBuffer(GL_ARRAY_BUFFER, buffer)
        val bytes = littleEndianBytes(values)
        binding.bufferData(GL_ARRAY_BUFFER, bytes.size, bytes, GL_STATIC_DRAW)
        binding.enableVertexAttribArray(attribute.location)
        binding.vertexAttribPointer(attribute.location, attribute.componentCount, GL_FLOAT, false, 0, 0)
    }

    binding.genBuffers(1, names)
    val indexBuffer = names[0]
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
    val indexBytes = littleEndianIndexBytes(primitive.indices)
    binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indexBytes.size, indexBytes, GL_STATIC_DRAW)

    return UploadedPrimitive(
        vertexArray = vertexArray,
        buffers = buffers,
        indexBuffer = indexBuffer,
        indexCount = primitive.indices.count,
        indexType = primitive.indices.glComponentType,
        attributes = attributes,
    )
}

/**
 * Deletes everything [uploaded] holds. The element array buffer is deleted explicitly rather than
 * left to the vertex array: deleting a VAO frees the VAO object alone, never the buffers it recorded
 * bindings to.
 */
internal fun deleteUploadedPrimitive(binding: GlBinding, uploaded: UploadedPrimitive) {
    binding.deleteVertexArrays(1, intArrayOf(uploaded.vertexArray))
    val buffers = (uploaded.buffers + uploaded.indexBuffer).toIntArray()
    binding.deleteBuffers(buffers.size, buffers)
}

/**
 * One primitive instance ready to draw, the model-side twin of [ResolvedSticker].
 *
 * [modelViewProjection] and [normalMatrix] are both column-major sixteen-element arrays matching
 * [GlBinding.uniformMatrix4fv]. [normalMatrix] is the inverse transpose of the **camera-space** model
 * matrix's linear block, shipped as a `mat4` the shader narrows with `mat3(...)` — which is why the
 * GL seam needs no `uniformMatrix3fv`. Camera space specifically, because
 * [sceneLightDirectionCameraSpace] delivers the light there and a diffuse term computed between two
 * different spaces is wrong in a way no assertion about either one alone would notice. Build it with
 * [modelNormalMatrix].
 *
 * [jointMatrices] is the packed palette from [packJointMatrices], or `null` for a primitive drawn
 * without a skin. Carrying joints and weights while [jointMatrices] is `null` is legal glTF — a mesh
 * that can be skinned, instanced at a node that names no skin — and draws statically.
 *
 * [reverseWinding] is glTF's own rule: when the node's global transform has a negative determinant
 * the primitive's triangles wind the other way. Its caller computes the determinant; getting it wrong
 * produces an inside-out model with no GL error at all.
 */
internal class ResolvedModelPrimitive(
    val uploaded: UploadedPrimitive,
    val modelViewProjection: FloatArray,
    val normalMatrix: FloatArray,
    val material: ResolvedMaterial,
    val baseColourTexture: Int?,
    val jointMatrices: FloatArray?,
    val reverseWinding: Boolean,
)

/** One model's primitives, in the order its draw items were walked. */
internal class ResolvedModel(val primitives: List<ResolvedModelPrimitive>)

/**
 * Which of the eight variants draws [primitive].
 *
 * Two of the three answers are deliberate refusals rather than lookups, both carried forward from
 * Task 9's decode report:
 *
 * - A material may name a `baseColorTexture` while the primitive carries no `TEXCOORD_0`, and
 *   `GltfTexture.source` is nullable so a texture may resolve to no image at all. Neither is a decode
 *   fault. Binding a sampler with no coordinates to sample it with would read an undefined varying —
 *   garbage, silently, with no GL error — so both fall back to the untextured variant and the base
 *   colour factor alone.
 * - Joints and weights present with no palette means the draw item named no skin. That draws
 *   statically rather than sampling an uninitialised joint buffer.
 */
internal fun modelShaderVariantFor(primitive: ResolvedModelPrimitive): ModelShaderVariant {
    val attributes = primitive.uploaded.attributes
    val skinnable = ModelVertexAttribute.JOINTS in attributes && ModelVertexAttribute.WEIGHTS in attributes
    return ModelShaderVariant(
        skinned = skinnable && primitive.jointMatrices != null,
        hasBaseColourTexture = primitive.baseColourTexture != null && ModelVertexAttribute.TEX_COORD in attributes,
        masked = primitive.material.alphaMode == MODEL_ALPHA_MODE_MASK,
    )
}

/**
 * Draws every primitive of [models] in two phases, lit by [lightDirectionCameraSpace].
 *
 * **Opaque first with depth writes on, then blended sorted back to front with writes off.** A mesh
 * has to occlude itself, which is why the model pass is the one map-regime pass that writes depth
 * (ADR 0030, superseding ADR 0027 for models alone) — without writes a model's own triangles all pass
 * the test against whatever is behind them and paint in submission order, so back faces show through
 * front ones and back-face culling only hides it for a closed convex mesh. Blended primitives cannot
 * write depth without occluding the translucent surfaces behind them, so they run second, in
 * descending distance from the camera.
 *
 * The blended sort spans **every** model in the frame rather than each model separately: two models'
 * translucent surfaces interleave in space, and sorting them apart would order each correctly against
 * itself and arbitrarily against the other. It costs one sort of a list that is 6 materials across 6
 * models in the whole consumer corpus.
 *
 * The sort key is each primitive's clip-space `w` at its own local origin —
 * `modelViewProjection[15]`, which the projection matrix's `(0, 0, -1, 0)` bottom row makes the
 * distance from the camera. That is a per-primitive proxy, not a per-triangle sort: two interpenetrating
 * translucent surfaces inside one primitive still resolve by submission order. Per-triangle depth
 * sorting is not something a renderer should invent in the cycle that first draws a model.
 *
 * This function leaves `depthMask(false)` behind unconditionally, even when nothing blended, because
 * ADR 0027's billboard fix is what a map-anchored sticker drawn after it depends on.
 *
 * Every GL state this draw depends on is established here rather than inherited — the same rule
 * [drawStickers] and [drawGround] follow, and for the same reason: `drawFrame` restores state around
 * a frame and cannot be relied on to leave any pass's own dependencies set up.
 */
internal fun drawModels(
    binding: GlBinding,
    pipelines: Map<ModelShaderVariant, ModelPipeline>,
    models: List<ResolvedModel>,
    lightDirectionCameraSpace: FloatArray,
) {
    require(lightDirectionCameraSpace.size == 3) { "a light direction requires exactly three components" }
    val primitives = models.flatMap { it.primitives }
    if (primitives.isEmpty()) return

    val blended = primitives.filter { it.material.alphaMode == MODEL_ALPHA_MODE_BLEND }
    val opaque = primitives.filter { it.material.alphaMode != MODEL_ALPHA_MODE_BLEND }

    val state = ModelDrawState(binding, pipelines, lightDirectionCameraSpace)
    binding.enable(GL_DEPTH_TEST)
    binding.frontFace(GL_CCW)
    binding.disable(GL_CULL_FACE)

    binding.disable(GL_BLEND)
    binding.depthMask(true)
    opaque.forEach { state.draw(it) }

    binding.depthMask(false)
    if (blended.isNotEmpty()) {
        binding.enable(GL_BLEND)
        binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
        binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        blended.sortedByDescending { it.modelViewProjection[CAMERA_DISTANCE_ELEMENT] }.forEach { state.draw(it) }
    }
}

/**
 * The per-primitive draw, plus the four pieces of GL state that persist between primitives.
 *
 * Tracking the current program, front face and cull enable — rather than restating each of them for
 * every primitive — is not a micro-optimisation: a redundant `useProgram` would force
 * `rengModelLightDirection` and `rengModelAmbient` to be re-set on every primitive, since a uniform
 * belongs to its program and RenG re-establishes both whenever it switches. The tracked values start
 * from what [drawModels] explicitly wrote, never from what the caller happened to leave bound.
 */
private class ModelDrawState(
    private val binding: GlBinding,
    private val pipelines: Map<ModelShaderVariant, ModelPipeline>,
    private val lightDirectionCameraSpace: FloatArray,
) {
    private var currentProgram: Int = 0
    private var currentFrontFace: Int = GL_CCW
    private var cullEnabled: Boolean = false

    fun draw(primitive: ResolvedModelPrimitive) {
        val variant = modelShaderVariantFor(primitive)
        val pipeline = requireNotNull(pipelines[variant]) { "no compiled model pipeline for $variant" }
        useProgram(pipeline)

        val frontFace = if (primitive.reverseWinding) GL_CW else GL_CCW
        if (frontFace != currentFrontFace) {
            binding.frontFace(frontFace)
            currentFrontFace = frontFace
        }
        val cull = !primitive.material.doubleSided
        if (cull != cullEnabled) {
            if (cull) binding.enable(GL_CULL_FACE) else binding.disable(GL_CULL_FACE)
            cullEnabled = cull
        }

        binding.bindVertexArray(primitive.uploaded.vertexArray)
        if (pipeline.modelViewProjectionLocation >= 0) {
            binding.uniformMatrix4fv(pipeline.modelViewProjectionLocation, 1, false, primitive.modelViewProjection)
        }
        if (pipeline.normalMatrixLocation >= 0) {
            binding.uniformMatrix4fv(pipeline.normalMatrixLocation, 1, false, primitive.normalMatrix)
        }
        if (pipeline.baseColourFactorLocation >= 0) {
            val factor = primitive.material.baseColourFactor
            binding.uniform4f(pipeline.baseColourFactorLocation, factor[0], factor[1], factor[2], factor[3])
        }
        if (pipeline.vertexColourPresentLocation >= 0) {
            val present = ModelVertexAttribute.COLOUR in primitive.uploaded.attributes
            binding.uniform1f(pipeline.vertexColourPresentLocation, if (present) 1.0f else 0.0f)
        }
        if (pipeline.forceOpaqueLocation >= 0) {
            val blended = primitive.material.alphaMode == MODEL_ALPHA_MODE_BLEND
            binding.uniform1f(pipeline.forceOpaqueLocation, if (blended) 0.0f else 1.0f)
        }
        if (variant.masked && pipeline.alphaCutoffLocation >= 0) {
            binding.uniform1f(pipeline.alphaCutoffLocation, primitive.material.alphaCutoff)
        }
        if (variant.hasBaseColourTexture) {
            binding.activeTexture(GL_TEXTURE0)
            binding.bindTexture(GL_TEXTURE_2D, primitive.baseColourTexture ?: 0)
            if (pipeline.baseColourTextureLocation >= 0) {
                binding.uniform1i(pipeline.baseColourTextureLocation, 0)
            }
        }
        bindJointPalette(pipeline, primitive.jointMatrices)

        binding.drawElements(
            GL_TRIANGLES,
            primitive.uploaded.indexCount,
            primitive.uploaded.indexType,
            0,
        )
    }

    private fun useProgram(pipeline: ModelPipeline) {
        if (pipeline.program == currentProgram) return
        binding.useProgram(pipeline.program)
        currentProgram = pipeline.program
        if (pipeline.lightDirectionLocation >= 0) {
            binding.uniform3f(
                pipeline.lightDirectionLocation,
                lightDirectionCameraSpace[0],
                lightDirectionCameraSpace[1],
                lightDirectionCameraSpace[2],
            )
        }
        if (pipeline.ambientLocation >= 0) {
            binding.uniform1f(pipeline.ambientLocation, SCENE_LIGHT_AMBIENT)
        }
    }

    private fun bindJointPalette(pipeline: ModelPipeline, palette: FloatArray?) {
        if (palette == null || pipeline.jointBuffer == 0) return
        val bytes = littleEndianBytes(palette)
        binding.bindBuffer(GL_UNIFORM_BUFFER, pipeline.jointBuffer)
        binding.bufferSubData(GL_UNIFORM_BUFFER, 0, bytes.size, bytes)
        binding.bindBufferBase(GL_UNIFORM_BUFFER, RENG_JOINT_UNIFORM_BINDING_POINT, pipeline.jointBuffer)
    }
}

/**
 * The inverse transpose of [cameraSpaceModel]'s linear block, widened to a column-major `mat4` for
 * `rengModelNormalMatrix`, or `null` when that block is singular.
 *
 * A normal is a covector: under a non-uniform scale it does not transform by the model matrix but by
 * the inverse transpose of it, and skipping that tilts every normal on a squashed mesh, which shows
 * up as lighting that is subtly wrong rather than as anything obviously broken. The translation
 * column is dropped, because a direction has no position.
 *
 * `null` is a typed failure at the caller, never an identity substitution: an identity normal matrix
 * on a degenerate transform renders a model lit as though it were unscaled and unrotated, which is a
 * wrong picture reported as a correct one.
 */
internal fun modelNormalMatrix(cameraSpaceModel: DoubleMatrix4): FloatArray? {
    val linear = DoubleMatrix3.fromRows(
        listOf(
            listOf(cameraSpaceModel[0, 0], cameraSpaceModel[0, 1], cameraSpaceModel[0, 2]),
            listOf(cameraSpaceModel[1, 0], cameraSpaceModel[1, 1], cameraSpaceModel[1, 2]),
            listOf(cameraSpaceModel[2, 0], cameraSpaceModel[2, 1], cameraSpaceModel[2, 2]),
        ),
    )
    val inverseTranspose = (linear.inverse() ?: return null).transpose()
    val elements = FloatArray(16)
    for (column in 0 until 3) {
        for (row in 0 until 3) {
            elements[column * 4 + row] = inverseTranspose[row, column].toFloat()
        }
    }
    elements[15] = 1.0f
    return elements
}

/**
 * [skin]'s joint matrices for a mesh drawn at node [skinnedNodeIndex], or `null` when they cannot be
 * derived.
 *
 * Each entry is `inverse(global[skinnedNodeIndex]) * global[jointNodes[i]] * inverseBindMatrices[i]`:
 * the inverse bind matrix carries a vertex from mesh space into the joint's own bind-pose space, the
 * joint's global transform carries it to where the joint is now, and the inverse of the skinned node's
 * own transform undoes the transform the draw is about to apply again — glTF specifies that a skinned
 * mesh's node transform must not be applied twice.
 *
 * Three things yield `null`, and each is a typed failure at the caller rather than a substitution:
 * a singular skinned-node transform (an identity there would silently double-apply that node's real
 * transform), a joint node the default scene never reaches (its global transform does not exist, and
 * inventing one poses the rig arbitrarily), and a skin naming more joints than
 * [MAXIMUM_MODEL_JOINTS] (the palette would not fit the uniform block, and truncating it would
 * collapse every vertex bound to a dropped joint onto the origin).
 */
internal fun jointMatricesForSkin(
    skin: DecodedSkin,
    nodeGlobalTransforms: List<DoubleMatrix4?>,
    skinnedNodeIndex: Int,
): List<DoubleMatrix4>? {
    if (skin.jointNodes.size > MAXIMUM_MODEL_JOINTS) return null
    val skinnedNode = nodeGlobalTransforms.getOrNull(skinnedNodeIndex) ?: return null
    val inverseSkinnedNode = skinnedNode.inverseAffine() ?: return null
    return skin.jointNodes.mapIndexed { index, jointNode ->
        val jointGlobal = nodeGlobalTransforms.getOrNull(jointNode) ?: return null
        inverseSkinnedNode * jointGlobal * skin.inverseBindMatrices[index]
    }
}

/**
 * [matrices] packed into the column-major float run `RengModelJoints` expects, or `null` when there
 * are more of them than [MAXIMUM_MODEL_JOINTS].
 *
 * std140 lays a `mat4` out as four tightly packed `vec4` columns, so sixteen consecutive floats per
 * joint in column-major order is exactly the block's layout with no padding to insert. Only the
 * joints actually present are written; the rest of the buffer keeps whatever the previous draw left
 * there, which no vertex can index because no vertex references a joint the skin does not declare.
 */
internal fun packJointMatrices(matrices: List<DoubleMatrix4>): FloatArray? {
    if (matrices.size > MAXIMUM_MODEL_JOINTS) return null
    val packed = FloatArray(matrices.size * 16)
    matrices.forEachIndexed { joint, matrix ->
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                packed[joint * 16 + column * 4 + row] = matrix[row, column].toFloat()
            }
        }
    }
    return packed
}

/**
 * The index run's bytes in the little-endian host order every published target uses, at the width
 * `internal.model.ModelIndices` already resolved. Both widths are written from their unsigned bit
 * patterns: an index of 40000 lives in a `Short` as -25536, and it is the sixteen bits that travel.
 */
private fun littleEndianIndexBytes(indices: ModelIndices): ByteArray {
    val shorts = indices.shorts
    if (shorts != null) {
        val bytes = ByteArray(shorts.size * Short.SIZE_BYTES)
        shorts.forEachIndexed { index, value ->
            val bits = value.toInt()
            bytes[index * 2] = (bits and 0xff).toByte()
            bytes[index * 2 + 1] = ((bits ushr 8) and 0xff).toByte()
        }
        return bytes
    }
    val ints = indices.ints ?: IntArray(0)
    val bytes = ByteArray(ints.size * Int.SIZE_BYTES)
    ints.forEachIndexed { index, value ->
        bytes[index * 4] = (value and 0xff).toByte()
        bytes[index * 4 + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[index * 4 + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[index * 4 + 3] = ((value ushr 24) and 0xff).toByte()
    }
    return bytes
}

private val ModelVertexAttribute.glslType: String
    get() = when (componentCount) {
        2 -> "vec2"
        3 -> "vec3"
        else -> "vec4"
    }

/** Whether this attribute exists only to feed the skinning path, and so only in a skinned variant. */
private val ModelVertexAttribute.isSkinningOnly: Boolean
    get() = this == ModelVertexAttribute.JOINTS || this == ModelVertexAttribute.WEIGHTS

private fun ModelVertexAttribute.valuesOf(primitive: DecodedPrimitive): FloatArray? = when (this) {
    ModelVertexAttribute.POSITION -> primitive.positions
    ModelVertexAttribute.NORMAL -> primitive.normals
    ModelVertexAttribute.TEX_COORD -> primitive.texCoords
    ModelVertexAttribute.COLOUR -> primitive.colours
    ModelVertexAttribute.JOINTS -> primitive.joints
    ModelVertexAttribute.WEIGHTS -> primitive.weights
}

/** Element `[3][3]` of a column-major model-view-projection: the local origin's clip-space `w`. */
private const val CAMERA_DISTANCE_ELEMENT: Int = 15

private val BOOLEANS: List<Boolean> = listOf(false, true)
