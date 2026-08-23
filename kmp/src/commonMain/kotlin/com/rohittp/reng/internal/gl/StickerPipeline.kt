package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's own sticker shader. It is written as a GLES 3.00 source and travels through
 * [com.rohittp.reng.internal.shader.ShaderProfilePlan.sourceFor] exactly as a consumer's [ShaderPair]
 * does — RenG is not exempt from its own dialect-substitution rule, so compiling this pair exercises
 * that path on every platform RenG ever runs on, not only on frames carrying a consumer `Geometry`.
 *
 * The quad is a unit square centred on the origin; [ResolvedSticker.modelViewProjection] carries the
 * per-sticker position, rotation and scale, so [createStickerPipeline] allocates exactly one vertex
 * buffer, reused for every sticker in every frame.
 */
internal const val STICKER_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengStickerPosition;\n" +
        "layout(location = 1) in vec2 rengStickerTexCoord;\n" +
        "uniform mat4 rengStickerModelViewProjection;\n" +
        "out vec2 rengStickerUv;\n" +
        "void main() {\n" +
        "    rengStickerUv = rengStickerTexCoord;\n" +
        "    gl_Position = rengStickerModelViewProjection * vec4(rengStickerPosition, 0.0, 1.0);\n" +
        "}\n"

internal const val STICKER_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengStickerTexture;\n" +
        "in vec2 rengStickerUv;\n" +
        "layout(location = 0) out vec4 rengStickerColour;\n" +
        "void main() {\n" +
        "    rengStickerColour = texture(rengStickerTexture, rengStickerUv);\n" +
        "}\n"

internal val STICKER_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = STICKER_VERTEX_SOURCE, fragmentSource = STICKER_FRAGMENT_SOURCE)

internal const val STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME: String = "rengStickerModelViewProjection"
internal const val STICKER_TEXTURE_UNIFORM_NAME: String = "rengStickerTexture"

/** A unit quad (side length 1, centred on the origin) with texture coordinates spanning `[0, 1]`. */
internal val STICKER_QUAD: FloatArray = floatArrayOf(
    -0.5f, -0.5f, 0.0f, 1.0f,
    0.5f, -0.5f, 1.0f, 1.0f,
    -0.5f, 0.5f, 0.0f, 0.0f,
    0.5f, 0.5f, 1.0f, 0.0f,
)

internal class StickerPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val modelViewProjectionUniformLocation: Int,
    val textureUniformLocation: Int,
)

internal sealed interface StickerPipelineResult {
    data class Created(val pipeline: StickerPipeline) : StickerPipelineResult

    data class Failed(val failure: FailureDescriptor) : StickerPipelineResult
}

internal fun createStickerPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): StickerPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.STICKER, STICKER_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(STICKER_VERTEX_SOURCE)
        ?: return StickerPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(STICKER_FRAGMENT_SOURCE)
        ?: return StickerPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return StickerPipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(STICKER_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, STICKER_STRIDE_BYTES, 0)
    binding.enableVertexAttribArray(1)
    binding.vertexAttribPointer(1, 2, GL_FLOAT, false, STICKER_STRIDE_BYTES, STICKER_UV_OFFSET_BYTES)

    return StickerPipelineResult.Created(
        StickerPipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            modelViewProjectionUniformLocation = binding.getUniformLocation(
                program,
                STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
            ),
            textureUniformLocation = binding.getUniformLocation(program, STICKER_TEXTURE_UNIFORM_NAME),
        ),
    )
}

internal fun deleteStickerPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: StickerPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * One sticker instance ready to draw: its already-resolved per-instance model-view-projection matrix
 * (column-major, matching [GlBinding.uniformMatrix4fv]) and its already-uploaded GL texture name.
 *
 * This used to carry a `screenCompositeZ` as well, for [drawStickers] to sort the screen stack by.
 * It no longer does: `MercatorSpatialPlanner` sorts that stack once, at `FRAME_PLANNING`, and
 * `SceneContent.drawScreenStack` walks the order it produced. A z carried down here would only be
 * an invitation to sort by it a second time, which is the duplicate that field's removal deletes.
 */
internal class ResolvedSticker(
    val modelViewProjection: FloatArray,
    val texture: Int,
)

/**
 * One frame's **map-anchored** stickers, in the order they are to be drawn.
 *
 * [mapAnchored] draws depth-tested but **depth-write-free**, **in the order it is given** — a
 * contract since ADR 0025 and, since ADR 0027, the whole of the rule rather than only its tie-break.
 * This KDoc once said the opposite ("in any order — the GPU depth buffer decides visibility between
 * map-anchored things, not draw order"); ADR 0025 replaced that with a tie-break, and ADR 0027
 * replaced the tie-break with an unconditional painter's order among map-regime content, because a
 * tie-break only ever resolved *exact* ties and the two defects that shipped were both near-ties.
 * The map regime still tests depth, so anything that genuinely wrote nearer depth still occludes
 * it; it just no longer writes depth itself. [drawStickers] therefore preserves the order it is
 * given and derives none of its own.
 *
 * **The screen regime is no longer this class's.** It used to carry a `screenAnchored` list that
 * [drawStickers] sorted and composited after the map half. Owning both regimes inside one
 * drawn-thing type is exactly what made that second sort possible, and the screen stack is one
 * ordered stack across *every* drawn-thing type (ADR 0024) rather than any one pipeline's. It now
 * lives in `SceneContent.drawScreenStack`, which walks the order `MercatorSpatialPlanner` already
 * produced.
 */
internal class StickerWorld(
    val mapAnchored: List<ResolvedSticker> = emptyList(),
)

/**
 * Draws the map-anchored half of one frame's stickers through [pipeline], depth-tested and
 * depth-write-free, **in exactly the order given** — this function sorts nothing and splits nothing.
 * Which stickers are map-anchored, and in what order, is `MercatorSpatialPlanner`'s answer, threaded
 * through `Scene.mapOrder`; re-deriving either here is the duplicate this pass no longer contains.
 *
 * **Why the map regime writes no depth (ADR 0027).** A map-anchored, screen-rotated sticker is a
 * billboard: its quad is screen-parallel, so every fragment carries the anchor's single depth while
 * the ground plane it stands on has depth varying down the screen. Under a pitched camera the half
 * of the quad below the anchor row is geometrically *under* the map plane and the depth test
 * deleted it — a cyan pin sliced exactly in half along a horizontal line through its own anchor,
 * visible at every pitch but zero. No constant or slope-scaled offset closes that, because the
 * deficit grows with the quad's own screen height. Removing the writes from every map-regime
 * surface removes the occluder instead, which is the only thing that makes the billboard whole.
 * The [GlBinding.depthMask] call below is **not** redundant with the model pass's exit mask
 * (ADR 0030 says so in as many words): it is the call actually holding that billboard fix up, and
 * every other map-regime pass sets the mask for itself for the same reason.
 *
 * The screen regime is drawn by `SceneContent.drawScreenStack`, not here, so this function neither
 * disables depth testing nor composites anything.
 */
internal fun drawStickers(binding: GlBinding, pipeline: StickerPipeline, world: StickerWorld) {
    if (world.mapAnchored.isEmpty()) return

    beginStickerPass(binding, pipeline)
    binding.enable(GL_DEPTH_TEST)
    binding.depthMask(false)
    world.mapAnchored.forEach { drawOneSticker(binding, pipeline, it) }
}

/**
 * Binds [pipeline]'s program, quad and sampler unit, and establishes the premultiplied
 * `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` blend function that Task 4's premultiplied image upload requires,
 * rather than inheriting whatever the caller left bound — `drawFrame` restores GL state around a
 * frame, so this pipeline cannot rely on it to leave its own dependencies set up.
 *
 * Depth state is deliberately **not** set here: the two regimes that draw stickers want opposite
 * depth state ([drawStickers] tests depth, `SceneContent.drawScreenStack` turns testing off), and
 * folding it in would put one regime's rule inside the other's setup.
 */
internal fun beginStickerPass(binding: GlBinding, pipeline: StickerPipeline) {
    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.activeTexture(GL_TEXTURE0)
    if (pipeline.textureUniformLocation >= 0) {
        binding.uniform1i(pipeline.textureUniformLocation, 0)
    }
}

/**
 * Draws one already-resolved sticker with [pipeline] and its state already established by
 * [beginStickerPass]. Shared by both regimes, so a screen-composited sticker and a map-anchored one
 * differ only in the matrix they were composed with and the depth state around them.
 */
internal fun drawOneSticker(binding: GlBinding, pipeline: StickerPipeline, sticker: ResolvedSticker) {
    if (pipeline.modelViewProjectionUniformLocation >= 0) {
        binding.uniformMatrix4fv(
            pipeline.modelViewProjectionUniformLocation,
            1,
            false,
            sticker.modelViewProjection,
        )
    }
    binding.bindTexture(GL_TEXTURE_2D, sticker.texture)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

private const val STICKER_STRIDE_BYTES: Int = 16
private const val STICKER_UV_OFFSET_BYTES: Int = 8
