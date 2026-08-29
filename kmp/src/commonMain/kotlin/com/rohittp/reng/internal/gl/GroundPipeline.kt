package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's own ground shader: one shared, cached [GroundGrid] drawn once per basemap tile instance,
 * with the tile's own model-view-projection as the only per-tile uniform.
 *
 * **The vertex stage takes a grid coordinate and derives the position**, where before Cycle
 * E-terrain the buffer carried a position and a texture coordinate per vertex. That is not tidying:
 * it makes the buffer bit-identical to the globe ground's, which is what lets one lattice serve both
 * grounds (see [GroundGrid]), and it halves what a 129 x 129 grid uploads. The derivation is
 * `(u - 0.5, 0.5 - v)` and its sign convention is [GroundPipeline]'s KDoc.
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
        "layout(location = 0) in vec2 rengGroundGrid;\n" +
        "uniform mat4 rengGroundModelViewProjection;\n" +
        "out vec2 rengGroundUv;\n" +
        "void main() {\n" +
        "    vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);\n" +
        "    rengGroundUv = rengGroundGrid;\n" +
        "    gl_Position = rengGroundModelViewProjection * vec4(position, 0.0, 1.0);\n" +
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
 * RenG's Mercator ground pipeline: one program and one [GroundGrid] per granularity, drawn once per
 * basemap tile with the tile's own model-view-projection.
 *
 * **The reading this pipeline puts on a grid vertex is documented here because the shader states it
 * in GLSL and nothing else can.** A grid vertex is a unit `(u, v)` cell coordinate and nothing more;
 * the vertex stage turns it into a position in the tile's own local map axes — `+x` east and `+y`
 * **north** — as `(u - 0.5, 0.5 - v)`, so the grid spans the same unit square centred on the origin
 * that `GROUND_QUAD`'s four vertices did before Cycle E-terrain subdivided it, and the tile's own
 * model-view-projection places it exactly as before.
 *
 * `v = 0` therefore lands at local `+y` because row zero of a rendered basemap tile is its **north**
 * edge and [uploadTexture] uploads row zero first. Flipping the sign of that subtraction mirrors
 * every tile about its own centre line — invisible on a solid-coloured tile and catastrophic on a
 * real map — so it is pinned by `GroundPipelineTest.theVertexStagePutsTextureRowZeroAtTheNorthEdge`
 * on the source text and, in pixels, by `runBasemapReadbackSuite`'s asymmetric fixture.
 *
 * The grid's triangles wind counter-clockwise in that frame, matching `drawFrame`'s `GL_CCW` front
 * face — see [groundGridIndices]. [drawGround] disables culling, so nothing on a real driver would
 * catch a flip; `GroundGridTest` asserts it on the signed areas instead.
 */
internal class GroundPipeline(
    val key: ResourceKey,
    val program: Int,
    val modelViewProjectionUniformLocation: Int,
    val textureUniformLocation: Int,
    /**
     * Every grid this pipeline has been asked for, keyed by [GroundGrid.cellsPerSide].
     *
     * Mutable, lazily filled and owned here for [GlobeGroundPipeline.grids]' reason exactly: the
     * granularity a frame needs is a function of the camera and of the elevation it has to follow,
     * so it is unknown at creation time. One grid per granularity is built on first use and every
     * later tile of every later frame at that granularity reuses it, so a frame allocates nothing.
     */
    val grids: MutableMap<Int, GroundGrid> = mutableMapOf(),
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

    // No vertex array and no buffer here, which is the one shape change Cycle E-terrain makes to
    // setup: the pipeline's geometry is now a [GroundGrid] per granularity, and which granularities
    // a session reaches is a camera question that setup cannot answer. [groundGrid] builds them on
    // the draw path, as the globe's has since Cycle G.
    return GroundPipelineResult.Created(
        GroundPipeline(
            key = key,
            program = program,
            modelViewProjectionUniformLocation = binding.getUniformLocation(
                program,
                GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
            ),
            textureUniformLocation = binding.getUniformLocation(program, GROUND_TEXTURE_UNIFORM_NAME),
        ),
    )
}

/** Deletes the program and **every** cached grid, on [deleteGroundGrids]' terms. */
internal fun deleteGroundPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GroundPipeline,
) {
    deleteGroundGrids(binding, pipeline.grids)
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
 * the ground's triangles happen to wind counter-clockwise, so an inherited enable removed nothing.
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
 *
 * **[cellsPerTileSide] defaults to 1, and at 1 this draws exactly what the four-vertex quad drew.**
 * The default is not a placeholder for a missing decision, it is the honest state of the seam: this
 * task subdivides the ground and displaces nothing, so no caller yet has a reason to ask for more
 * than one cell, and `SceneContent` passes nothing. A granularity derived from elevation error
 * arrives with displacement, alongside the globe's own curvature-derived
 * [globeGroundCellsPerTileSide], and both must then yield **one granularity for the whole frame** —
 * two tiles sharing an edge at different granularities is the crack the globe's KDoc already
 * records. Until then the equivalence is the contract: same outline, same UVs, same pixels.
 */
internal fun drawGround(
    binding: GlBinding,
    pipeline: GroundPipeline,
    tiles: List<ResolvedGroundTile>,
    cellsPerTileSide: Int = 1,
) {
    if (tiles.isEmpty()) return

    val grid = groundGrid(binding, pipeline.grids, cellsPerTileSide)
    binding.useProgram(pipeline.program)
    binding.bindVertexArray(grid.vertexArray)
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
        binding.drawElements(GL_TRIANGLES, grid.indexCount, GL_UNSIGNED_SHORT, 0)
    }
}
