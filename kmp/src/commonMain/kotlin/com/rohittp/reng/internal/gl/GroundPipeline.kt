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
        "uniform vec3 rengGroundColourWindow;\n" +
        "void main() {\n" +
        "    vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);\n" +
        "    rengGroundUv = rengGroundGrid * rengGroundColourWindow.x + rengGroundColourWindow.yz;\n" +
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

/**
 * The ground's fragment stage **with terrain shading on**: the same texel, multiplied by ADR 0026's
 * one light applied to the normal the vertex stage derived from the DEM.
 *
 * Shared by both projections for [GROUND_FRAGMENT_SOURCE]'s own reason — the globe and the Mercator
 * ground must sample the identical texture identically, and the cycle's cross-mode agreement gate
 * asserts it — and a second copy would make a drift a silent edit rather than a visible one.
 *
 * ## Three properties of the expression, each deliberate
 *
 * **The light's numbers are derived, never transcribed.** [SCENE_LIGHT_DIRECTION_ENU_GLSL],
 * [SCENE_LIGHT_FLAT_INCIDENCE_GLSL] and [SCENE_LIGHT_RELIEF_GAIN_GLSL] are built from ADR 0026's
 * Kotlin constants, on `demDecodeCoefficients`' argument exactly: a formula written twice is a
 * formula that can drift, and a test can only catch a drift already written.
 *
 * **`1.0 + (incidence - flat) * gain` rather than `ambient + diffuse * incidence`.** The two are
 * algebraically the same light divided by what it does to flat ground, and this arrangement is what
 * makes ground with no relief come out **exactly** unchanged: a flat normal is `(0, 0, 1)` exactly,
 * so `incidence` is the light's own `z` to the bit, so the subtraction is `0.0` and the factor is
 * `1.0` with no rounding anywhere. Written the other way it would be one ulp from one, and "terrain
 * shading changes only the pixels that have relief" would be a tolerance rather than a fact.
 *
 * **The result is clamped against the texel's own alpha**, because a slope facing the light reaches
 * about 1.24 and the ground is composited premultiplied (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`), where a
 * colour above its own alpha is not a valid premultiplied triple. On an opaque tile — every rendered
 * basemap tile with an opaque style background — the clamp is what the framebuffer would do anyway,
 * so it costs nothing and removes the question. There is no lower clamp because the factor's minimum
 * is about 0.43 and cannot reach zero.
 */
internal val GROUND_SHADED_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengGroundTexture;\n" +
        "in vec2 rengGroundUv;\n" +
        "in vec3 rengGroundNormalEnu;\n" +
        "layout(location = 0) out vec4 rengGroundColour;\n" +
        "void main() {\n" +
        "    vec4 tile = texture(rengGroundTexture, rengGroundUv);\n" +
        "    float incidence = max(dot(normalize(rengGroundNormalEnu), " +
        SCENE_LIGHT_DIRECTION_ENU_GLSL + "), 0.0);\n" +
        "    float shade = 1.0 + (incidence - " + SCENE_LIGHT_FLAT_INCIDENCE_GLSL + ") * " +
        SCENE_LIGHT_RELIEF_GAIN_GLSL + ";\n" +
        "    rengGroundColour = vec4(min(tile.rgb * shade, vec3(tile.a)), tile.a);\n" +
        "}\n"

internal val GROUND_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = GROUND_VERTEX_SOURCE, fragmentSource = GROUND_FRAGMENT_SOURCE)

internal const val GROUND_COLOUR_WINDOW_UNIFORM_NAME: String = "rengGroundColourWindow"

internal const val GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME: String = "rengGroundModelViewProjection"
internal const val GROUND_TEXTURE_UNIFORM_NAME: String = "rengGroundTexture"
internal const val GROUND_MERCATOR_Y_UNIFORM_NAME: String = "rengGroundMercatorY"
internal const val GROUND_ELEVATION_SCALE_UNIFORM_NAME: String = "rengGroundElevationScale"

/**
 * The Mercator ground's displacing vertex stage: [GROUND_VERTEX_SOURCE] with
 * [GROUND_ELEVATION_SOURCE] composed in and one term added to the position.
 *
 * **Mercator displaces along the surface normal, which in the tile's own map axes is `+z`.** The
 * tile's model matrix is `Translate(centre) * Scale(side, side, 1)` ([composeGroundModelViewProjection]),
 * so `x` and `y` are scaled from the unit square into logical pixels and **`z` passes through with a
 * scale of exactly one**. The displacement therefore has to arrive already in logical pixels, and
 * everything below exists to convert metres into them.
 *
 * ## Metres to logical pixels, and why the latitude term is per vertex
 *
 * RenG stays in logical pixels, never metres, and Mercator's own conversion is already written down
 * twice: `projectMercator` computes `z = altitudeMetres / (C * cos(latitude))` in world units and
 * `PlacementResolver` multiplies by `worldSizeLogicalPixels`. This is that same expression, split so
 * that the part which varies across a tile is evaluated per vertex:
 *
 * ```
 * logicalPixels = metres * (worldSize / C) * (1 / cos(latitude))
 *               = metres * rengGroundElevationScale * cosh(PI * (1 - 2 * mercatorY))
 * ```
 *
 * The identity is exact rather than an approximation. Web Mercator defines
 * `asinh(tan(latitude)) = PI * (1 - 2y)`, so `tan(latitude) = sinh(PI * (1 - 2y))` and
 * `1 / cos(latitude) = sqrt(1 + tan^2) = cosh(PI * (1 - 2y))`. At the Mercator clip that is
 * `cosh(PI) = 11.592`, which is the distortion at 85.0511 degrees to six figures.
 *
 * **Per vertex rather than per tile, and that is a seam decision rather than an accuracy one.** A
 * single `1 / cos(latitude)` per tile — the tile's centre, say — would be cheaper and would scale
 * one DEM height by two different constants on the two sides of every east-west tile boundary. The
 * heights already agree there, because the padded ring makes both sides read the same texel; the
 * *drawn* ground would still step, in exactly the places the ring exists to make continuous. Two
 * tiles sharing a line of latitude are handed the identical `mercatorY` endpoint and `mix` returns
 * its endpoints exactly, so the two evaluate `cosh` on the identical argument instead.
 *
 * ## What [shading] adds, and what stays out either way
 *
 * With [shading] false this emits the source three published releases' flat ground is compared
 * against, character for character; with it true it emits that source plus [GROUND_NORMAL_SOURCE],
 * one `out vec3`, and one statement. Terrain shading is a **renderer** option
 * (`RendererConfiguration.terrainShading`), fixed for a renderer's whole life, so which of the two
 * is compiled is decided once at setup and no frame can vary it. ADR 0026 leaves the ground unlit
 * and that stays the default.
 *
 * `1 / cos(latitude)` reaches the normal as `1 / cosh(PI * (1 - 2y))`, the reciprocal of the same
 * per-vertex term the displacement uses, because the run of a tap is real ground metres and Mercator
 * measures its tile in equatorial ones. It is the same latitude factor, applied to the horizontal
 * where the displacement applies it to the vertical.
 *
 * No depth write here either way: ADR 0039's write is conditional on the *frame* being displaced, so
 * it belongs to [drawGround] -- which is the only place that knows which of a frame's tiles carry a
 * DEM -- and not to a shader compiled once per renderer. The UV, the winding and the position of an
 * undisplaced vertex are [GROUND_VERTEX_SOURCE]'s, character for character.
 */
private fun terrainGroundVertexSource(shading: Boolean): String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "layout(location = 0) in vec2 rengGroundGrid;\n" +
        "uniform mat4 rengGroundModelViewProjection;\n" +
        "uniform vec2 rengGroundMercatorY;\n" +
        "uniform float rengGroundElevationScale;\n" +
        GROUND_ELEVATION_SOURCE +
        (if (shading) GROUND_NORMAL_SOURCE else "") +
        "out vec2 rengGroundUv;\n" +
        "uniform vec3 rengGroundColourWindow;\n" +
        (if (shading) "out vec3 rengGroundNormalEnu;\n" else "") +
        "void main() {\n" +
        "    float mercatorY = mix(rengGroundMercatorY.x, rengGroundMercatorY.y, rengGroundGrid.y);\n" +
        "    float up = rengGroundElevationMetres(rengGroundGrid) * rengGroundElevationScale *\n" +
        "        cosh(3.141592653589793 * (1.0 - 2.0 * mercatorY));\n" +
        "    vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);\n" +
        "    rengGroundUv = rengGroundGrid * rengGroundColourWindow.x + rengGroundColourWindow.yz;\n" +
        (
            if (shading) {
                "    rengGroundNormalEnu = rengGroundEnuNormal(rengGroundGrid, 1.0 /\n" +
                    "        cosh(3.141592653589793 * (1.0 - 2.0 * mercatorY)));\n"
            } else {
                ""
            }
            ) +
        "    gl_Position = rengGroundModelViewProjection * vec4(position, up, 1.0);\n" +
        "}\n"

internal val TERRAIN_GROUND_VERTEX_SOURCE: String = terrainGroundVertexSource(shading = false)

/**
 * [TERRAIN_GROUND_VERTEX_SOURCE] with [GROUND_NORMAL_SOURCE] composed in and one varying added, for
 * a renderer whose configuration asked for terrain shading.
 *
 * **The unshaded text is not merely equivalent, it is the same characters**, which is what keeps
 * "off draws what three releases drew" a statement about which program ran. Both come out of one
 * builder that *adds* to the unshaded body rather than restructuring it, so there is no arrangement
 * of this file in which the two drift.
 *
 * `cosh` is evaluated twice, once for the displacement and once for `1 / cos(latitude)`, rather than
 * hoisted into a local. Hoisting would have changed the unshaded text, and every GLSL compiler in
 * the world eliminates the common subexpression.
 */
internal val TERRAIN_SHADED_GROUND_VERTEX_SOURCE: String = terrainGroundVertexSource(shading = true)

internal val TERRAIN_GROUND_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = TERRAIN_GROUND_VERTEX_SOURCE, fragmentSource = GROUND_FRAGMENT_SOURCE)

internal val TERRAIN_SHADED_GROUND_SHADER_PAIR: ShaderPair = ShaderPair(
    vertexSource = TERRAIN_SHADED_GROUND_VERTEX_SOURCE,
    fragmentSource = GROUND_SHADED_FRAGMENT_SOURCE,
)

/**
 * The Mercator ground's displacing program and every uniform location it needs.
 *
 * Compiled beside the flat program rather than on the first terrain frame, unlike
 * [GlobeGroundPipeline], and the asymmetry is deliberate: a globe pipeline is a whole second
 * pipeline object a Mercator renderer must never pay for, while this is one more program inside a
 * pipeline that already exists. Lazily compiling it would put a `var` on the draw path and a
 * `Failed` arm in a function that currently cannot fail, to save one link on the 28 of 34 corpus
 * styles that declare no terrain.
 */
internal class TerrainGroundProgram(
    val key: ResourceKey,
    val program: Int,
    val modelViewProjectionUniformLocation: Int,
    /**
     * Which part of the bound texture a tile samples (ADR 0057): scale, then u and v offsets.
     *
     * The identity window is what an exact tile gets, so this is written for every tile and the
     * shader carries no branch and no second variant.
     */
    val colourWindowUniformLocation: Int,
    val textureUniformLocation: Int,
    val mercatorYUniformLocation: Int,
    val elevationScaleUniformLocation: Int,
    val elevation: GroundElevationUniformLocations,
)

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
    /**
     * Which part of the bound texture a tile samples (ADR 0057): scale, then u and v offsets.
     *
     * The identity window is what an exact tile gets, so this is written for every tile and the
     * shader carries no branch and no second variant.
     */
    val colourWindowUniformLocation: Int,
    val textureUniformLocation: Int,
    /**
     * The displacing program this pipeline draws a tile with a DEM through — see
     * [TerrainGroundProgram] for why it is compiled here rather than on first use.
     */
    val terrain: TerrainGroundProgram,
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

/**
 * [terrainShading] picks which of the two displacing sources is compiled, and picking it **here** is
 * the whole of how the option is spent.
 *
 * `RendererConfiguration.terrainShading` is a property of the renderer rather than of a `FramePlan`,
 * so the choice is made once, at setup, and `drawGround` needs no branch, no uniform and no way to
 * get it wrong per tile. Off — the default — the flat program and the displacing program are the two
 * this file has compiled since task 6, byte for byte, so a frame drawn with shading off is not
 * merely expected to match the unshaded picture, it runs the identical program.
 */
internal fun createGroundPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
    terrainShading: Boolean = false,
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

    val terrainPair = if (terrainShading) TERRAIN_SHADED_GROUND_SHADER_PAIR else TERRAIN_GROUND_SHADER_PAIR
    val terrainKey = deriver.internalPipeline(InternalPipelineRole.TERRAIN_GROUND, terrainPair).key
    val terrainVertexPlan = scanShaderProfile(terrainPair.vertexSource)
        ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, terrainKey))
    val terrainFragmentPlan = if (terrainShading) {
        scanShaderProfile(terrainPair.fragmentSource)
            ?: return GroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, terrainKey))
    } else {
        fragmentPlan
    }
    val terrainProgram = when (
        val result = cache.getOrCompile(binding, dialect, terrainKey, terrainVertexPlan, terrainFragmentPlan)
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
            colourWindowUniformLocation = binding.getUniformLocation(
                program,
                GROUND_COLOUR_WINDOW_UNIFORM_NAME,
            ),
            textureUniformLocation = binding.getUniformLocation(program, GROUND_TEXTURE_UNIFORM_NAME),
            terrain = TerrainGroundProgram(
                key = terrainKey,
                program = terrainProgram,
                modelViewProjectionUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
                ),
                colourWindowUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_COLOUR_WINDOW_UNIFORM_NAME,
                ),
                textureUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_TEXTURE_UNIFORM_NAME,
                ),
                mercatorYUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_MERCATOR_Y_UNIFORM_NAME,
                ),
                elevationScaleUniformLocation = binding.getUniformLocation(
                    terrainProgram,
                    GROUND_ELEVATION_SCALE_UNIFORM_NAME,
                ),
                elevation = resolveGroundElevationUniforms(binding, terrainProgram),
            ),
        ),
    )
}

/** Deletes **both** programs and **every** cached grid, on [deleteGroundGrids]' terms. */
internal fun deleteGroundPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GroundPipeline,
) {
    deleteGroundGrids(binding, pipeline.grids)
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
    cache.remove(pipeline.terrain.key)?.let { binding.deleteProgram(it) }
}

/**
 * One ground tile instance ready to draw: its already-composed model-view-projection matrix
 * (column-major, matching [GlBinding.uniformMatrix4fv]), its already-uploaded GL texture name, and
 * — when this frame has terrain and this tile has a DEM — what to displace it by.
 *
 * [elevation] is `null` for every tile of every frame whose style declares no `terrain` block, and
 * also for a tile inside a terrain frame that Rentile returned no DEM for: ADR 0041 makes that tile
 * draw flat rather than failing the frame, so the two cases produce the same drawn tile and are
 * distinguished only by the diagnostic.
 */
internal class ResolvedGroundTile(
    val modelViewProjection: FloatArray,
    val texture: Int,
    val elevation: MercatorGroundTileDem? = null,
    /** Which part of [texture] to sample; the whole of it unless this tile is provisional (ADR 0057). */
    val colourWindow: GroundTileWindow = GroundTileWindow.WHOLE,
)

/**
 * One Mercator ground tile's DEM: the padded texture and window every projection needs, plus the
 * tile's own Mercator `y` extent, which only Mercator does.
 *
 * The two travel together rather than as two fields with a default, because
 * [TERRAIN_GROUND_VERTEX_SOURCE]'s latitude term is meaningless without the extent and a plausible
 * default for it — the whole world, say — would draw a tile's terrain at up to 11.6x the right
 * height with nothing to notice it.
 */
/**
 * One basemap tile's Mercator `y` extent as `(north, south)`, which is what
 * [TERRAIN_GROUND_VERTEX_SOURCE]'s latitude term interpolates between.
 *
 * **Two vertically adjacent tiles are handed the identical `Float` for the edge they share**, by
 * exactly the mechanism [globeGroundTileEdges] documents: `(tileY + 1) / 2^lod` of one and
 * `tileY / 2^lod` of the next are the same `Double`, so they narrow to the same `Float`, and `mix`
 * returns its endpoints exactly. Both sides therefore evaluate `cosh` on the same argument and scale
 * the same DEM height by the same number, which is what keeps the seam closed in the drawn ground as
 * well as in the sampled one.
 */
internal fun mercatorTileYEdges(lod: Int, tileY: Int): FloatArray {
    require(lod >= 0) { "a basemap tile's lod is never negative" }
    val dimension = (1L shl lod).toDouble()
    return floatArrayOf(
        (tileY.toDouble() / dimension).toFloat(),
        ((tileY.toDouble() + 1.0) / dimension).toFloat(),
    )
}

internal class MercatorGroundTileDem(val dem: GroundTileDem, mercatorY: FloatArray) {
    /** `(mercator y at the tile's north edge, mercator y at its south edge)`, both in `[0, 1]`. */
    val mercatorY: FloatArray = mercatorY.copyOf()

    init {
        require(mercatorY.size == 2) { "a ground tile spans two mercator y bounds" }
    }
}

/**
 * Draws [tiles] as the frame's ground, in the order given.
 *
 * **Depth testing on; depth writes on exactly when this frame's ground is displaced (ADR 0039,
 * superseding ADR 0027 for this pass alone).** ADR 0027 removed the ground's depth writes because
 * flat map-plane content acting as an occluder cost a coplanar altitude-0 `Geometry` up to 100% of
 * its pixels between consecutive frames -- the ground's depth and the quad's are different
 * floating-point products of the same plane, so they differ by an epsilon whose sign changes as the
 * camera moves. Terrain inverts that trade: a mountain that does not hide what is behind it is a
 * different picture of the world, not a flicker on a shared plane.
 *
 * So the write is **conditional, and the condition is the frame's rather than the tile's**. If any
 * tile of this ground is displaced the whole pass writes; if none is -- which is 28 of the corpus's
 * 34 styles and every frame of three published releases -- the pass writes nothing and ADR 0027's
 * rule stands untouched. There is no frame in which that costs something an unconditional write
 * would have bought, because the write buys occlusion only where the ground has relief and the
 * defect it revives exists only where it has none. A per-tile flip was rejected by ADR 0039: it
 * churns state inside the loop, and it would let a coplanar `Geometry` win over a flat coverage gap
 * while losing to the displaced tile beside it.
 *
 * [tiles] order and the ground's position first in [SceneContent.draw] stay contracts. Inside a
 * frame with no terrain they remain the *whole* of the rule rather than only its tie-break; inside a
 * displaced one, depth decides between the ground and what is drawn after it, and order decides the
 * rest.
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
 * **[cellsPerTileSide] defaults to 1, and at 1 an undisplaced tile draws exactly what the
 * four-vertex quad drew.** The frame's one granularity is now the caller's answer rather than a
 * placeholder: `SceneContent` reconciles the globe's curvature claim with terrain's own through
 * `groundCellsPerTileSide`, because **two tiles sharing an edge at different granularities is the
 * crack the globe's KDoc already records**. The default survives for the frames that need nothing
 * else — no terrain and no sphere — where the equivalence remains the contract: same outline, same
 * UVs, same pixels.
 *
 * **[elevation] is the frame's, and each tile decides for itself whether it uses it.** A frame with
 * no terrain passes `null` and every tile draws through [GroundPipeline.program], which is the
 * program three releases shipped; a terrain frame passes the decode and the metre scale, and each
 * tile with a [ResolvedGroundTile.elevation] draws through [GroundPipeline.terrain] instead. **The
 * two are interleaved in [tiles] order rather than partitioned**, because ADR 0041 lets a single
 * absent DEM make one tile flat among displaced neighbours, and reordering the ground to group the
 * programs would change which of two overlapping alpha edges is composited last — a pixel
 * difference bought for a `useProgram` this pass issues at most a few dozen times.
 */
internal fun drawGround(
    binding: GlBinding,
    pipeline: GroundPipeline,
    tiles: List<ResolvedGroundTile>,
    cellsPerTileSide: Int = 1,
    elevation: MercatorGroundElevationFrame? = null,
) {
    if (tiles.isEmpty()) return

    val grid = groundGrid(binding, pipeline.grids, cellsPerTileSide)
    binding.bindVertexArray(grid.vertexArray)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.enable(GL_DEPTH_TEST)
    // Each tile's own answer to "am I displaced", resolved once. The frame's answer is `any` over
    // it rather than a second reading of [elevation], so the mask below and the program switch
    // inside the loop cannot disagree about what this frame's terrain is.
    val tileElevations = tiles.map { if (elevation == null) null else it.elevation }
    binding.depthMask(tileElevations.any { it != null })
    binding.disable(GL_CULL_FACE)

    // Which program was made current last, so a run of tiles on one side of the terrain/flat split
    // costs one `useProgram` rather than one per tile. `null` until the first tile, because either
    // program may be the first: a frame whose only coverage gap is its first tile starts flat.
    var displacing: Boolean? = null
    tiles.forEachIndexed { index, tile ->
        val tileElevation = tileElevations[index]
        val wantsDisplacement = tileElevation != null
        if (displacing != wantsDisplacement) {
            displacing = wantsDisplacement
            if (wantsDisplacement) {
                binding.useProgram(pipeline.terrain.program)
                if (pipeline.terrain.textureUniformLocation >= 0) {
                    binding.uniform1i(pipeline.terrain.textureUniformLocation, 0)
                }
                if (pipeline.terrain.elevationScaleUniformLocation >= 0) {
                    binding.uniform1f(
                        pipeline.terrain.elevationScaleUniformLocation,
                        requireNotNull(elevation).equatorialLogicalPixelsPerMetre,
                    )
                }
                bindGroundElevationFrame(
                    binding,
                    pipeline.terrain.elevation,
                    requireNotNull(elevation).dem,
                )
            } else {
                binding.useProgram(pipeline.program)
                if (pipeline.textureUniformLocation >= 0) {
                    binding.uniform1i(pipeline.textureUniformLocation, 0)
                }
            }
        }

        val matrixLocation = if (wantsDisplacement) {
            pipeline.terrain.modelViewProjectionUniformLocation
        } else {
            pipeline.modelViewProjectionUniformLocation
        }
        if (matrixLocation >= 0) {
            binding.uniformMatrix4fv(matrixLocation, 1, false, tile.modelViewProjection)
        }
        val windowLocation = if (wantsDisplacement) {
            pipeline.terrain.colourWindowUniformLocation
        } else {
            pipeline.colourWindowUniformLocation
        }
        if (windowLocation >= 0) {
            // Written for every tile, identity included (ADR 0057): a uniform left over from the
            // previous tile is the one way this can draw the wrong part of the right texture.
            binding.uniform3f(
                windowLocation,
                tile.colourWindow.scale,
                tile.colourWindow.offsetU,
                tile.colourWindow.offsetV,
            )
        }
        if (tileElevation != null) {
            if (pipeline.terrain.mercatorYUniformLocation >= 0) {
                binding.uniform2f(
                    pipeline.terrain.mercatorYUniformLocation,
                    tileElevation.mercatorY[0],
                    tileElevation.mercatorY[1],
                )
            }
            bindGroundElevationTile(binding, pipeline.terrain.elevation, tileElevation.dem)
        }
        binding.activeTexture(GL_TEXTURE0)
        binding.bindTexture(GL_TEXTURE_2D, tile.texture)
        binding.drawElements(GL_TRIANGLES, grid.indexCount, GL_UNSIGNED_SHORT, 0)
    }
}
