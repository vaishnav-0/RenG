package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's own ground shader: one textured quad per basemap tile instance.
 *
 * Written as a GLES 3.00 source and compiled through
 * [com.rohittp.reng.internal.shader.ShaderProfilePlan.sourceFor] exactly as a consumer's
 * [ShaderPair] is — RenG is not exempt from its own dialect-substitution rule (ADR 0008).
 *
 * A separate program from [STICKER_SHADER_PAIR] rather than a reuse of it. The two are structurally
 * identical today, and that is precisely the trap: a sticker is consumer content whose shading is
 * free to grow (a tint, a fade, a premultiplied-alpha correction), while the ground is RenG's own
 * backdrop and must keep sampling its tile unchanged. Sharing one program would make every future
 * sticker change a silent ground change, and the cost of not sharing is one more cached program per
 * renderer.
 */
internal const val GROUND_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengGroundPosition;\n" +
        "layout(location = 1) in vec2 rengGroundTexCoord;\n" +
        "uniform mat4 rengGroundModelViewProjection;\n" +
        "out vec2 rengGroundUv;\n" +
        "void main() {\n" +
        "    rengGroundUv = rengGroundTexCoord;\n" +
        "    gl_Position = rengGroundModelViewProjection * vec4(rengGroundPosition, 0.0, 1.0);\n" +
        "}\n"

internal const val GROUND_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "in vec2 rengGroundUv;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        "void main() {\n" +
        "    rengGroundColour = texture(rengGroundTexture, rengGroundUv);\n" +
        "}\n"

internal val GROUND_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = GROUND_VERTEX_SOURCE, fragmentSource = GROUND_FRAGMENT_SOURCE)

internal const val GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME: String = "rengGroundModelViewProjection"
internal const val GROUND_TEXTURE_UNIFORM_NAME: String = "rengGroundTexture"

/**
 * A unit square centred on the origin, in the tile's own local map axes: `+x` east, `+y` **north**.
 *
 * `v = 0` sits at `+y` because row zero of a rendered basemap tile is its **north** edge, and
 * [uploadTexture] uploads row zero first. Flipping `v` here mirrors every tile about its own centre
 * line — a defect that is completely invisible on a solid-coloured tile and destroys a real map, so
 * it is pinned by `GroundPipelineTest.theUnitQuadPutsTextureRowZeroAtTheNorthEdge` and by the
 * readback suite's asymmetric fixture rather than left to inspection.
 *
 * Vertices are in triangle-strip order and wind counter-clockwise once projected, matching
 * `drawFrame`'s `GL_CCW` front face.
 */
internal val GROUND_QUAD: FloatArray = floatArrayOf(
    -0.5f, -0.5f, 0.0f, 1.0f,
    0.5f, -0.5f, 1.0f, 1.0f,
    -0.5f, 0.5f, 0.0f, 0.0f,
    0.5f, 0.5f, 1.0f, 0.0f,
)

internal class GroundPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val modelViewProjectionUniformLocation: Int,
    val textureUniformLocation: Int,
)

internal sealed interface GroundPipelineResult {
    data class Created(val pipeline: GroundPipeline) : GroundPipelineResult

    data class Failed(val failure: FailureDescriptor) : GroundPipelineResult
}

internal fun createGroundPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): GroundPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.GROUND, GROUND_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(GROUND_VERTEX_SOURCE)
        ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(GROUND_FRAGMENT_SOURCE)
        ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return GroundPipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(GROUND_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, GROUND_STRIDE_BYTES, 0)
    binding.enableVertexAttribArray(1)
    binding.vertexAttribPointer(1, 2, GL_FLOAT, false, GROUND_STRIDE_BYTES, GROUND_UV_OFFSET_BYTES)

    return GroundPipelineResult.Created(
        GroundPipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            modelViewProjectionUniformLocation = binding.getUniformLocation(
                program,
                GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
            ),
            textureUniformLocation = binding.getUniformLocation(program, GROUND_TEXTURE_UNIFORM_NAME),
        ),
    )
}

internal fun deleteGroundPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GroundPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * One ground tile instance ready to draw: its already-composed model-view-projection matrix
 * (column-major, matching [GlBinding.uniformMatrix4fv]) and its already-uploaded GL texture name.
 */
internal class ResolvedGroundTile(
    val modelViewProjection: FloatArray,
    val texture: Int,
)

/**
 * Draws [tiles] as the frame's ground, in the order given.
 *
 * **Depth testing on, depth writes off (ADR 0027, superseding ADR 0025 on this point).** ADR 0025
 * kept the ground's depth writes and bought coplanarity with `GL_GEQUAL` alone, on the reasoning
 * that terrain would later need the ground to occlude. That resolves an *exact* tie and nothing
 * else, and a moving camera does not produce exact ties: the ground's depth and a coplanar
 * altitude-0 `Geometry`'s depth are computed through different matrix products, so they differ by a
 * float epsilon whose sign changes from frame to frame and the quad tears itself apart. Measured on
 * a real style, a coplanar quad lost up to 100% of its pixels between consecutive frames. The
 * ground no longer writes depth at all, so there is nothing for coplanar map content to tie with,
 * near-tie or exact. [tiles] order and the ground's position first in [SceneContent.draw] stay
 * contracts — they are now the *whole* of the rule rather than only its tie-break.
 *
 * Blend state is established here rather than inherited, exactly as [drawStickers] does and for the
 * same reason: the premultiplied `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` function is what [uploadTexture]'s
 * [TextureContent.IMAGE] path requires, and a basemap style with a transparent background composites
 * correctly over the cleared offscreen surface only under it.
 *
 * **Face culling is established here too, and that is ADR 0038's whole new obligation for this
 * pass.** It is **explicitly disabled** rather than left alone. Until Cycle G this function set no
 * cull state at all and inherited whatever the caller had left, which was harmless only by luck:
 * [GROUND_QUAD] happens to wind counter-clockwise, so an inherited enable removed nothing.
 * Disabling makes a mercator frame's pixels a function of this pass rather than of its caller — the
 * same rule ADR 0027 already applies to `depthMask(false)` — and it is what lets a cycle that
 * changes the globe change no mercator pixel.
 *
 * **This function is the Mercator ground and takes no projection mode**, which is Cycle G task 10's
 * correction to task 6. Task 6 gave it a `projectionMode` parameter whose `GLOBE` arm enabled
 * culling and went on drawing the flat quad, because the subdivided sphere grid did not exist yet;
 * task 7 built [drawGlobeGround] and task 10 routed globe frames to it, which left that arm
 * unreachable from production and a trap for whoever wired the next thing — a flat quad drawn with
 * backface culling on is not obviously wrong on screen. ADR 0038's globe half is now
 * [drawGlobeGround]'s unconditional `enable(GL_CULL_FACE)`, on exactly the reasoning that function's
 * own KDoc already gave for taking no mode: a parameter whose only legal value is one constant is a
 * mode that can be got wrong rather than a decision.
 */
internal fun drawGround(
    binding: GlBinding,
    pipeline: GroundPipeline,
    tiles: List<ResolvedGroundTile>,
) {
    if (tiles.isEmpty()) return

    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.activeTexture(GL_TEXTURE0)
    if (pipeline.textureUniformLocation >= 0) {
        binding.uniform1i(pipeline.textureUniformLocation, 0)
    }
    binding.enable(GL_DEPTH_TEST)
    binding.depthMask(false)
    binding.disable(GL_CULL_FACE)

    tiles.forEach { tile ->
        if (pipeline.modelViewProjectionUniformLocation >= 0) {
            binding.uniformMatrix4fv(
                pipeline.modelViewProjectionUniformLocation,
                1,
                false,
                tile.modelViewProjection,
            )
        }
        binding.bindTexture(GL_TEXTURE_2D, tile.texture)
        binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }
}

private const val GROUND_STRIDE_BYTES: Int = 16
private const val GROUND_UV_OFFSET_BYTES: Int = 8
