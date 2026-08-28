package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * Every pipeline RenG compiles for its own use. The wire value is part of a canonical identity
 * (ADR 0018), so entries are only ever appended -- renumbering one would change the [ResourceKey] of
 * an existing pipeline.
 */
internal enum class InternalPipelineRole(internal val wireValue: Int) {
    COMPOSITE(1),
    STICKER(2),
    GROUND(3),
    MODEL(4),
    LABEL(5),
    ICON(6),
}

/**
 * Compositing is a blended draw rather than a framebuffer blit, because a blit does not blend and
 * a consumer compositing RenG's output over existing content needs it to (ADR 0005). The composite
 * sources are written in the same Shader Profile the public API accepts, so they travel the
 * identical scan-and-substitute path a consumer's [ShaderPair] does: the substitution machinery is
 * exercised on every context RenG ever runs on, not only on frames that happen to carry a
 * `Geometry`.
 */
internal const val COMPOSITE_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengCompositePosition;\n" +
        "layout(location = 1) in vec2 rengCompositeTexCoord;\n" +
        "out vec2 rengCompositeUv;\n" +
        "void main() {\n" +
        "    rengCompositeUv = rengCompositeTexCoord;\n" +
        "    gl_Position = vec4(rengCompositePosition, 0.0, 1.0);\n" +
        "}\n"

internal const val COMPOSITE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengCompositeSource;\n" +
        "in vec2 rengCompositeUv;\n" +
        "layout(location = 0) out vec4 rengCompositeColour;\n" +
        "void main() {\n" +
        "    rengCompositeColour = texture(rengCompositeSource, rengCompositeUv);\n" +
        "}\n"

internal val COMPOSITE_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = COMPOSITE_VERTEX_SOURCE, fragmentSource = COMPOSITE_FRAGMENT_SOURCE)

internal const val COMPOSITE_SOURCE_UNIFORM_NAME: String = "rengCompositeSource"
internal const val COMPOSITE_TEXTURE_UNIT_COUNT: Int = 1

internal val COMPOSITE_QUAD: FloatArray = floatArrayOf(
    -1.0f, -1.0f, 0.0f, 0.0f,
    1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f, 1.0f, 0.0f, 1.0f,
    1.0f, 1.0f, 1.0f, 1.0f,
)

/**
 * All six published targets are little-endian and GL reads client memory in host byte order, so
 * this encoding is correct by construction on every one of them.
 */
internal fun littleEndianBytes(values: FloatArray): ByteArray {
    val bytes = ByteArray(values.size * Float.SIZE_BYTES)
    var offset = 0
    values.forEach { value ->
        val bits = value.toRawBits()
        bytes[offset] = (bits and 0xff).toByte()
        bytes[offset + 1] = ((bits ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((bits ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((bits ushr 24) and 0xff).toByte()
        offset += Float.SIZE_BYTES
    }
    return bytes
}

internal class CompositePipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val sourceUniformLocation: Int,
)

internal sealed interface CompositePipelineResult {
    data class Created(val pipeline: CompositePipeline) : CompositePipelineResult

    data class Failed(val failure: FailureDescriptor) : CompositePipelineResult
}

internal fun createCompositePipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): CompositePipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.COMPOSITE, COMPOSITE_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(COMPOSITE_VERTEX_SOURCE)
        ?: return CompositePipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(COMPOSITE_FRAGMENT_SOURCE)
        ?: return CompositePipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return CompositePipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(COMPOSITE_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, COMPOSITE_STRIDE_BYTES, 0)
    binding.enableVertexAttribArray(1)
    binding.vertexAttribPointer(1, 2, GL_FLOAT, false, COMPOSITE_STRIDE_BYTES, COMPOSITE_UV_OFFSET_BYTES)

    return CompositePipelineResult.Created(
        CompositePipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            sourceUniformLocation = binding.getUniformLocation(program, COMPOSITE_SOURCE_UNIFORM_NAME),
        ),
    )
}

internal fun deleteCompositePipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: CompositePipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

private const val COMPOSITE_STRIDE_BYTES: Int = 16
private const val COMPOSITE_UV_OFFSET_BYTES: Int = 8
