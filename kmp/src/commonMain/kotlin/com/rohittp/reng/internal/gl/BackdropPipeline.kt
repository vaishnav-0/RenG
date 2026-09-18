package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's own backdrop shader (ADR 0068), written as GLES 3.00 and travelling through
 * [com.rohittp.reng.internal.shader.ShaderProfilePlan.sourceFor] as a consumer's [ShaderPair] does.
 *
 * **The quad is the frame**, in clip space, so there is no model-view-projection: a backdrop is
 * screen-space by definition. `rengBackdropRepeat` carries how many times the pattern fits across
 * the output, and the sampler's `GL_REPEAT` does the tiling.
 *
 * The `v` axis is flipped in the vertex stage so a pattern reads the same way up here as the same
 * image does on a sticker, whose quad already maps `v = 0` to its top edge.
 */
internal const val BACKDROP_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengBackdropPosition;\n" +
        "uniform vec2 rengBackdropRepeat;\n" +
        "out vec2 rengBackdropUv;\n" +
        "void main() {\n" +
        "    vec2 rengBackdropUnit = vec2(\n" +
        "        rengBackdropPosition.x * 0.5 + 0.5,\n" +
        "        0.5 - rengBackdropPosition.y * 0.5\n" +
        "    );\n" +
        "    rengBackdropUv = rengBackdropUnit * rengBackdropRepeat;\n" +
        "    gl_Position = vec4(rengBackdropPosition, 0.0, 1.0);\n" +
        "}\n"

internal const val BACKDROP_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengBackdropTexture;\n" +
        "in vec2 rengBackdropUv;\n" +
        "layout(location = 0) out vec4 rengBackdropColour;\n" +
        "void main() {\n" +
        "    rengBackdropColour = texture(rengBackdropTexture, rengBackdropUv);\n" +
        "}\n"

internal val BACKDROP_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = BACKDROP_VERTEX_SOURCE, fragmentSource = BACKDROP_FRAGMENT_SOURCE)

internal const val BACKDROP_REPEAT_UNIFORM_NAME: String = "rengBackdropRepeat"
internal const val BACKDROP_TEXTURE_UNIFORM_NAME: String = "rengBackdropTexture"

/**
 * `inverse(projectionMatrix * viewMatrix)` for the frame, so a backdrop shader can find the ground
 * under a pixel and draw a pattern fixed to the map rather than to the screen (ADR 0072).
 *
 * Under the prefix, so it was already refused as a consumer name from the day ADR 0071 reserved
 * one -- which is why adding it costs no ABI and breaks nobody.
 */
internal const val BACKDROP_INVERSE_VIEW_PROJECTION_UNIFORM_NAME: String =
    "rengBackdropInverseViewProjection"

/**
 * The prefix the names above share, reserved against consumer uniform and texture names on
 * `Backdrop.Shader` (ADR 0071).
 *
 * Reserved as a prefix rather than as the two literals, because ADR 0008 makes adding a documented
 * name later a breaking change: it joins the reserved set, and a consumer already using it stops
 * being able to construct the object. Holding the prefix is what leaves room for a horizon uniform
 * in a later cycle, and it can only be claimed before the first consumer.
 */
internal const val BACKDROP_RESERVED_NAME_PREFIX: String = "rengBackdrop"

/** The whole clip volume in `x` and `y`, as a triangle strip. No texture coordinates: see the shader. */
internal val BACKDROP_QUAD: FloatArray = floatArrayOf(
    -1.0f, -1.0f,
    1.0f, -1.0f,
    -1.0f, 1.0f,
    1.0f, 1.0f,
)

/**
 * `GL_REPEAT` on both axes, and the only texture RenG uploads that wraps -- which is exactly why the
 * backdrop cannot borrow the sticker pipeline's sampler. `GL_LINEAR` both ways with no mipmap chain,
 * matching every other consumer image: a pattern is magnified as often as it is minified, and a
 * mipmap level of a tiling pattern would bleed its own opposite edge across the seam under
 * `GL_REPEAT` rather than the adjacent copy a viewer expects.
 */
internal val BACKDROP_SAMPLER_STATE: TextureSamplerState = TextureSamplerState(
    minFilter = GL_LINEAR,
    magFilter = GL_LINEAR,
    wrapS = GL_REPEAT,
    wrapT = GL_REPEAT,
)

internal class BackdropPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val repeatUniformLocation: Int,
    val textureUniformLocation: Int,
)

internal sealed interface BackdropPipelineResult {
    data class Created(val pipeline: BackdropPipeline) : BackdropPipelineResult

    data class Failed(val failure: FailureDescriptor) : BackdropPipelineResult
}

internal fun createBackdropPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): BackdropPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.BACKDROP, BACKDROP_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(BACKDROP_VERTEX_SOURCE)
        ?: return BackdropPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(BACKDROP_FRAGMENT_SOURCE)
        ?: return BackdropPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return BackdropPipelineResult.Failed(result.failure)
    }

    val quad = backdropQuad(binding)

    return BackdropPipelineResult.Created(
        BackdropPipeline(
            key = key,
            program = program,
            vertexArray = quad.vertexArray,
            vertexBuffer = quad.vertexBuffer,
            repeatUniformLocation = binding.getUniformLocation(program, BACKDROP_REPEAT_UNIFORM_NAME),
            textureUniformLocation = binding.getUniformLocation(program, BACKDROP_TEXTURE_UNIFORM_NAME),
        ),
    )
}

/** The full-frame clip-space quad, as a vertex array both backdrop pipelines build identically. */
private class BackdropQuad(val vertexArray: Int, val vertexBuffer: Int)

private fun backdropQuad(binding: GlBinding): BackdropQuad {
    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(BACKDROP_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, BACKDROP_STRIDE_BYTES, 0)
    return BackdropQuad(vertexArray, vertexBuffer)
}

/**
 * A backdrop drawn from a shader pair the consumer wrote (ADR 0071).
 *
 * Keyed under [InternalPipelineRole.BACKDROP] like RenG's own, because the role is RenG's even
 * though the text is not, and the key already hashes both sources -- so two consumer shaders get
 * two keys and RenG's own gets a third. [consumerLocations] memoises exactly as
 * [GeometryPipeline]'s does, and for the same reason: a location is a property of the linked
 * program, and the two die together.
 */
internal class ConsumerBackdropPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val resolutionLocation: Int,
    val frameIndexLocation: Int,
    val inverseViewProjectionLocation: Int,
) {
    private val consumerLocations: MutableMap<String, Int> = HashMap()

    fun consumerLocation(binding: GlBinding, name: String): Int =
        consumerLocations.getOrPut(name) { binding.getUniformLocation(program, name) }
}

internal sealed interface ConsumerBackdropPipelineResult {
    data class Created(val pipeline: ConsumerBackdropPipeline) : ConsumerBackdropPipelineResult

    data class Failed(val failure: FailureDescriptor) : ConsumerBackdropPipelineResult
}

/**
 * Compiles [shaderPair] into a backdrop pipeline, reporting a failure as the consumer's.
 *
 * `consumerAuthored = true` is the whole difference from [createBackdropPipeline]: the key is an
 * internal-pipeline key either way, so without it a consumer's syntax error reads as RenG's GPU
 * failing.
 */
internal fun createConsumerBackdropPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    shaderPair: ShaderPair,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): ConsumerBackdropPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.BACKDROP, shaderPair).key
    val vertexPlan = scanShaderProfile(shaderPair.vertexSource)
        ?: return ConsumerBackdropPipelineResult.Failed(
            shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key, consumerAuthored = true),
        )
    val fragmentPlan = scanShaderProfile(shaderPair.fragmentSource)
        ?: return ConsumerBackdropPipelineResult.Failed(
            shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key, consumerAuthored = true),
        )

    val program = when (
        val result = cache.getOrCompile(
            binding, dialect, key, vertexPlan, fragmentPlan, consumerAuthored = true,
        )
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return ConsumerBackdropPipelineResult.Failed(result.failure)
    }

    val quad = backdropQuad(binding)

    return ConsumerBackdropPipelineResult.Created(
        ConsumerBackdropPipeline(
            key = key,
            program = program,
            vertexArray = quad.vertexArray,
            vertexBuffer = quad.vertexBuffer,
            resolutionLocation = binding.getUniformLocation(program, UNIFORM_RESOLUTION),
            frameIndexLocation = binding.getUniformLocation(program, UNIFORM_FRAME_INDEX),
            inverseViewProjectionLocation =
                binding.getUniformLocation(program, BACKDROP_INVERSE_VIEW_PROJECTION_UNIFORM_NAME),
        ),
    )
}

internal fun deleteConsumerBackdropPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: ConsumerBackdropPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

internal fun deleteBackdropPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: BackdropPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/** How many times a pattern repeats across and down the output. */
internal class BackdropRepeat(val across: Float, val down: Float)

/** One frame's backdrop, carrying the pipeline that draws it (ADR 0068, ADR 0071). */
internal sealed interface ResolvedBackdrop {
    /** Both forms bind the same way, so the draw reads these rather than branching to find them. */
    val program: Int
    val vertexArray: Int

    data class Pattern(
        val pipeline: BackdropPipeline,
        val texture: Int,
        val repeat: BackdropRepeat,
    ) : ResolvedBackdrop {
        override val program: Int get() = pipeline.program
        override val vertexArray: Int get() = pipeline.vertexArray
    }

    data class Shader(
        val pipeline: ConsumerBackdropPipeline,
        val uniforms: Map<String, ShaderValue>,
        val textures: Map<String, Int>,
        /**
         * Column-major `inverse(projection * view)`, or `null` when the frame's camera does not
         * invert -- in which case the name is simply never bound, exactly as ADR 0008 says happens
         * to a name RenG does not set (ADR 0072).
         */
        val inverseViewProjection: FloatArray? = null,
    ) : ResolvedBackdrop {
        override val program: Int get() = pipeline.program
        override val vertexArray: Int get() = pipeline.vertexArray
    }
}

/**
 * How many times the pattern repeats across and down [outputPixelSize], given the repeat distance the
 * plan asked for (ADR 0068).
 *
 * Its own function rather than three lines at the call site because it is the whole of what
 * `tileSizeLogicalPixels` *means*, and dividing where one should multiply is the difference between
 * a pattern at the right size and one at 1080 times it -- which no shader test and no GL call log
 * can see.
 *
 * RenG's output size is its logical pixel size, so no scale factor applies between the two.
 */
internal fun resolvedBackdropFor(
    outputPixelSize: OutputPixelSize,
    tileSizeLogicalPixels: Double,
): BackdropRepeat = BackdropRepeat(
    across = (outputPixelSize.width / tileSizeLogicalPixels).toFloat(),
    down = (outputPixelSize.height / tileSizeLogicalPixels).toFloat(),
)

/**
 * Paints [backdrop] across the whole frame, before anything else in it (ADR 0068, ADR 0071).
 *
 * **Depth testing and depth writing are both off**, which is what makes "behind everything" true
 * without the backdrop having a depth of its own to defend: every later pass draws over it, and none
 * of them has to compare against it. Blending is the same premultiplied `GL_ONE,
 * GL_ONE_MINUS_SRC_ALPHA` every other image pass establishes, so a pattern with transparency
 * composites over the cleared surface rather than replacing it.
 *
 * Both forms take that identical state, which is why it is established here once rather than in
 * each branch: a shader backdrop that composited differently from a pattern one would be a
 * difference nobody asked for.
 */
internal fun drawBackdrop(
    binding: GlBinding,
    backdrop: ResolvedBackdrop,
    resolutionWidthPixels: Float,
    resolutionHeightPixels: Float,
    frameIndex: Long,
) {
    binding.useProgram(backdrop.program)
    binding.bindVertexArray(backdrop.vertexArray)
    binding.disable(GL_DEPTH_TEST)
    binding.depthMask(false)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)

    when (backdrop) {
        is ResolvedBackdrop.Pattern -> {
            val pipeline = backdrop.pipeline
            binding.activeTexture(GL_TEXTURE0)
            if (pipeline.textureUniformLocation >= 0) {
                binding.uniform1i(pipeline.textureUniformLocation, 0)
            }
            if (pipeline.repeatUniformLocation >= 0) {
                binding.uniform2f(
                    pipeline.repeatUniformLocation, backdrop.repeat.across, backdrop.repeat.down,
                )
            }
            binding.bindTexture(GL_TEXTURE_2D, backdrop.texture)
        }

        is ResolvedBackdrop.Shader -> {
            val pipeline = backdrop.pipeline
            if (pipeline.resolutionLocation >= 0) {
                binding.uniform2f(pipeline.resolutionLocation, resolutionWidthPixels, resolutionHeightPixels)
            }
            if (pipeline.frameIndexLocation >= 0) {
                binding.uniform1ui(pipeline.frameIndexLocation, frameIndex.toInt())
            }
            val inverseViewProjection = backdrop.inverseViewProjection
            if (pipeline.inverseViewProjectionLocation >= 0 && inverseViewProjection != null) {
                binding.uniformMatrix4fv(
                    pipeline.inverseViewProjectionLocation, 1, false, inverseViewProjection,
                )
            }
            bindConsumerValues(
                binding = binding,
                locate = { name -> pipeline.consumerLocation(binding, name) },
                uniforms = backdrop.uniforms,
                textures = backdrop.textures,
            )
        }
    }

    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

private const val BACKDROP_STRIDE_BYTES: Int = 8
