package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's own backdrop shader (ADR 0068), written as GLES 3.00 and travelling through
 * [com.rohittp.reng.internal.shader.ShaderProfilePlan.sourceFor] exactly as a consumer's [ShaderPair]
 * does, on the same terms as the sticker pair beside it.
 *
 * **The quad is the frame**, in clip space, so there is no model-view-projection here at all -- the
 * backdrop is screen-space by definition and a matrix would only be an opportunity to place it
 * somewhere it must never be. `rengBackdropRepeat` carries how many times the pattern fits across the
 * output, and the sampler's `GL_REPEAT` does the tiling.
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

    return BackdropPipelineResult.Created(
        BackdropPipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            repeatUniformLocation = binding.getUniformLocation(program, BACKDROP_REPEAT_UNIFORM_NAME),
            textureUniformLocation = binding.getUniformLocation(program, BACKDROP_TEXTURE_UNIFORM_NAME),
        ),
    )
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

/** One frame's backdrop: its uploaded texture and how many times it repeats across the output. */
internal class ResolvedBackdrop(
    val texture: Int,
    val repeatAcross: Float,
    val repeatDown: Float,
)

/**
 * How many times the pattern repeats across and down [outputPixelSize], given the repeat distance the
 * plan asked for (ADR 0068).
 *
 * A separate function rather than three lines at the call site because it is the whole of what
 * `tileSizeLogicalPixels` *means*, and the difference between dividing and multiplying here is a
 * pattern at the right size and one at 1080 times it -- a distinction no shader test can see and no
 * GL call log records.
 *
 * RenG's output size is its logical pixel size: `cameraDistanceLogicalPixels` derives the camera
 * distance straight from `outputPixelSize.height`, so there is no scale factor to apply between the
 * two and none is applied.
 */
internal fun resolvedBackdropFor(
    texture: Int,
    outputPixelSize: OutputPixelSize,
    tileSizeLogicalPixels: Double,
): ResolvedBackdrop = ResolvedBackdrop(
    texture = texture,
    repeatAcross = (outputPixelSize.width / tileSizeLogicalPixels).toFloat(),
    repeatDown = (outputPixelSize.height / tileSizeLogicalPixels).toFloat(),
)

/**
 * Paints [backdrop] across the whole frame, before anything else in it (ADR 0068).
 *
 * **Depth testing and depth writing are both off**, which is what makes "behind everything" true
 * without the backdrop having a depth of its own to defend: every later pass draws over it, and none
 * of them has to compare against it. Blending is the same premultiplied `GL_ONE,
 * GL_ONE_MINUS_SRC_ALPHA` every other image pass establishes, so a pattern with transparency
 * composites over the cleared surface rather than replacing it.
 */
internal fun drawBackdrop(
    binding: GlBinding,
    pipeline: BackdropPipeline,
    backdrop: ResolvedBackdrop,
) {
    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.disable(GL_DEPTH_TEST)
    binding.depthMask(false)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.activeTexture(GL_TEXTURE0)
    if (pipeline.textureUniformLocation >= 0) {
        binding.uniform1i(pipeline.textureUniformLocation, 0)
    }
    if (pipeline.repeatUniformLocation >= 0) {
        binding.uniform2f(pipeline.repeatUniformLocation, backdrop.repeatAcross, backdrop.repeatDown)
    }
    binding.bindTexture(GL_TEXTURE_2D, backdrop.texture)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

private const val BACKDROP_STRIDE_BYTES: Int = 8
