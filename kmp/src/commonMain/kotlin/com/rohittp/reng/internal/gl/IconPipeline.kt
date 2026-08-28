package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's sprite shader: **a sampled coverage, not a thresholded distance field.**
 *
 * **This is a second program rather than a [LabelPipeline] parameterisation, and the arithmetic is
 * the whole reason.** The label program reads its texture's alpha as a signed distance and
 * `smoothstep`s it about [LABEL_FILL_EDGE_DISTANCE], because that is where the `glyphs.pbf`
 * generator puts a glyph outline. A sprite atlas has no field: its alpha is *coverage* and its RGB
 * is *artwork*. Running an icon through the label program would hard-threshold a number that means
 * nothing at an iso-value that means nothing, and would throw the artwork away on the way past --
 * a wrong picture rather than a compile error, which is exactly why [ResolvedIconQuad] was made a
 * sibling of [ResolvedGlyphQuad] rather than the same type before any of this existed.
 *
 * Everything else it copies from [LabelPipeline] deliberately: one interleaved vertex array, one
 * `glDrawElements` per batch however many icons the batch carries, the same premultiplied blend
 * function, and the same y-down screen space resolved on the CPU so the shader needs no matrix. The
 * two files share [labelQuadIndices], [littleEndianBytes] and [littleEndianIntBytes] rather than
 * repeating them, because a quad's index pattern is not a property of what the quad draws.
 *
 * ## Tinting, and the one rule that decides it
 *
 * `icon-color` applies to an **`sdf` sprite only**. Rentile tints a sprite under `BlendMode.SRC_IN`
 * when the manifest entry says `sdf` and passes no colour filter otherwise, and RenG draws the same
 * icons Rentile's own rasteriser draws into the tile underneath, so departing from that rule would
 * make the two disagree about the same symbol. Tinting artwork a style never asked to recolour is
 * the failure this prevents: a two-tone transit roundel repainted flat in the label's text colour is
 * a defect no assertion about "an icon drew" would ever see.
 *
 * The flag travels as a **vertex attribute** rather than a uniform, for the batch's sake: one atlas
 * carries `sdf` entries and artwork entries alike, a batch is per texture, and a uniform is per draw.
 *
 * **`icon-halo-*` reaches no shader, and that is Rentile's behaviour rather than an omission.** A
 * halo is a dilation of an outline, and a coverage channel carries no outline to dilate -- the
 * distance that a glyph's halo width is converted into simply does not exist here. Rentile draws no
 * icon halo either, so honouring one would put a ring around symbols the engine's own rasteriser
 * paints bare. [ResolvedIconPaint] carries the two widths so that whoever closes this gap has the
 * evaluated numbers in hand; nothing in this file reads them.
 */
internal const val ICON_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengIconPosition;\n" +
        "layout(location = 1) in vec2 rengIconTexCoord;\n" +
        "layout(location = 2) in vec4 rengIconTint;\n" +
        "layout(location = 3) in float rengIconTintable;\n" +
        "uniform vec2 rengIconViewportSize;\n" +
        "out vec2 rengIconUv;\n" +
        "out vec4 rengIconTintColour;\n" +
        "out float rengIconTintSelector;\n" +
        "void main() {\n" +
        "    rengIconUv = rengIconTexCoord;\n" +
        "    rengIconTintColour = rengIconTint;\n" +
        "    rengIconTintSelector = rengIconTintable;\n" +
        "    vec2 ndc = vec2(\n" +
        "        rengIconPosition.x / rengIconViewportSize.x * 2.0 - 1.0,\n" +
        "        1.0 - rengIconPosition.y / rengIconViewportSize.y * 2.0\n" +
        "    );\n" +
        "    gl_Position = vec4(ndc, 0.0, 1.0);\n" +
        "}\n"

/**
 * Two regimes over one sampled texel, selected per vertex.
 *
 * The atlas is uploaded as [TextureContent.IMAGE], so the texel arrives **premultiplied** -- which
 * is both what the blend function this pass establishes wants and what makes the atlas's own linear
 * filtering correct at a sprite's edge, where an unpremultiplied transparent texel would bleed
 * meaningless RGB into the visible result.
 *
 * - **Artwork** (`rengIconTintSelector == 0`): the texel, attenuated by the icon's own opacity.
 *   Nothing about the style's colours enters, because for a non-`sdf` entry Rentile passes no colour
 *   filter at all.
 * - **`sdf`** (`rengIconTintSelector == 1`): `SRC_IN` against the tint. The source's alpha is the
 *   only channel that survives -- `alpha = coverage * tint.a * opacity` -- and the tint's RGB is
 *   painted through it, premultiplied to match.
 *
 * `mix` rather than a branch: the selector is uniform across every fragment of a quad, so a branch
 * would cost a divergence check for an answer no fragment in the quad disagrees about.
 *
 * **No `smoothstep`, no threshold, no field constant appears anywhere below**, and that absence is
 * the file's subject rather than an oversight. A coverage of 0.5 draws at half coverage here; under
 * the label program's [LABEL_FILL_EDGE_DISTANCE] it would draw nothing at all.
 */
internal const val ICON_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengIconAtlas;\n" +
        "in vec2 rengIconUv;\n" +
        "in vec4 rengIconTintColour;\n" +
        "in float rengIconTintSelector;\n" +
        "layout(location = 0) out vec4 rengIconColour;\n" +
        "void main() {\n" +
        "    vec4 texel = texture(rengIconAtlas, rengIconUv);\n" +
        "    float opacity = rengIconTintColour.a;\n" +
        "    vec4 artwork = texel * opacity;\n" +
        "    float tintAlpha = texel.a * opacity;\n" +
        "    vec4 tinted = vec4(rengIconTintColour.rgb * tintAlpha, tintAlpha);\n" +
        "    rengIconColour = mix(artwork, tinted, rengIconTintSelector);\n" +
        "}\n"

internal val ICON_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = ICON_VERTEX_SOURCE, fragmentSource = ICON_FRAGMENT_SOURCE)

internal const val ICON_ATLAS_UNIFORM_NAME: String = "rengIconAtlas"
internal const val ICON_VIEWPORT_SIZE_UNIFORM_NAME: String = "rengIconViewportSize"

/**
 * One icon's paint, in the vocabulary the engine hands it over in.
 *
 * [colour] and [haloColour] are **straight** (unpremultiplied) RGBA in `[0, 1]`, the shape an
 * unpacked `0xAARRGGBB` naturally takes. [opacity] is `icon-opacity` with any fade already folded in
 * by the caller.
 *
 * **[colour], [haloColour], [haloWidthPixels] and [haloBlurPixels] mean nothing when [tintable] is
 * `false`**: Rentile draws a non-SDF sprite with its own pixels and no colour filter, so tinting one
 * would repaint artwork the style never asked to recolour. [tintable] is the manifest entry's own
 * `sdf` member (`com.rohittp.reng.internal.firewall.SpriteAtlasEntry.sdf`), carried here so the
 * decision is made where the manifest is and not re-derived where it is not.
 *
 * [haloWidthPixels] and [haloBlurPixels] are screen pixels, and **nothing draws them** -- see
 * [ICON_VERTEX_SOURCE]'s KDoc for why a coverage channel has no outline to dilate and why Rentile
 * paints no icon halo either. They are evaluated and carried rather than dropped so that closing
 * that gap is a change to this pipeline alone.
 */
internal class ResolvedIconPaint(
    val colour: FloatArray,
    val haloColour: FloatArray,
    val opacity: Float,
    val haloWidthPixels: Float,
    val haloBlurPixels: Float,
    val tintable: Boolean,
) {
    init {
        require(colour.size == ICON_RGBA_COMPONENTS) { "colour must be four components" }
        require(haloColour.size == ICON_RGBA_COMPONENTS) { "haloColour must be four components" }
    }

    /** This paint with [factor] multiplied into [opacity] -- the label fade, applied to the icon half. */
    internal fun fadedBy(factor: Float): ResolvedIconPaint = ResolvedIconPaint(
        colour = colour,
        haloColour = haloColour,
        opacity = opacity * factor,
        haloWidthPixels = haloWidthPixels,
        haloBlurPixels = haloBlurPixels,
        tintable = tintable,
    )
}

/**
 * One icon's quad, already placed: four screen corners in `CONTEXT.md`'s continuous output-pixel
 * space (origin top-left, positive y downward) in the corner order top-left, top-right,
 * bottom-right, bottom-left, and the same four corners in normalised sprite-atlas coordinates.
 *
 * **A sibling of [ResolvedGlyphQuad] rather than the same type, and the reason is arithmetic rather
 * than taste.** The two carry identically shaped geometry -- and if geometry were all a quad
 * carried, reuse would be right. It is not: a [ResolvedGlyphQuad] carries a [ResolvedLabelPaint],
 * every field of which is measured in the glyph atlas's signed-distance field. `scale` is
 * `text-size / 24`; `haloWidthPixels` is converted through that scale into an iso-value by
 * [labelHaloEdgeDistance]; and [LABEL_FRAGMENT_SOURCE] thresholds the sampled alpha at `0.75`
 * because that is where the `glyphs.pbf` generator puts a glyph outline. A sprite has no field: its
 * alpha is coverage, its RGB is artwork, and `icon-halo-width` is a number of screen pixels rather
 * than a distance into a field that does not exist.
 *
 * So an icon quad appended to a [LabelBatch] would not be slightly wrong -- it would be
 * hard-thresholded at an iso-value that means nothing, tinted by a colour the style may never have
 * intended to apply, and drawn with its artwork discarded. Two types make that a compile error; one
 * type makes it a picture nobody reviews. The geometry duplication is eight floats and a corner-order
 * comment; the alternative is a silent wrong answer.
 */
internal class ResolvedIconQuad(
    val cornersXy: FloatArray,
    val cornersUv: FloatArray,
    val paint: ResolvedIconPaint,
) {
    init {
        require(cornersXy.size == ICON_QUAD_CORNERS * 2) { "cornersXy must be four (x, y) pairs" }
        require(cornersUv.size == ICON_QUAD_CORNERS * 2) { "cornersUv must be four (u, v) pairs" }
    }

    /** This quad drawn through [paint] faded by [factor], sharing both corner arrays. */
    internal fun fadedBy(factor: Float): ResolvedIconQuad =
        if (factor >= ICON_UNFADED) {
            this
        } else {
            ResolvedIconQuad(cornersXy = cornersXy, cornersUv = cornersUv, paint = paint.fadedBy(factor))
        }
}

/**
 * A compiled icon program, its vertex array, and the two buffers a batch streams through.
 *
 * [capacityQuads] is a high-water mark on exactly [LabelPipeline.capacityQuads]'s terms: the batch's
 * size is a property of the frame, so the buffers grow to fit the largest batch drawn so far and
 * never shrink.
 */
internal class IconPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val indexBuffer: Int,
    val atlasUniformLocation: Int,
    val viewportSizeUniformLocation: Int,
) {
    var capacityQuads: Int = 0
}

internal sealed interface IconPipelineResult {
    data class Created(val pipeline: IconPipeline) : IconPipelineResult

    data class Failed(val failure: FailureDescriptor) : IconPipelineResult
}

/**
 * Every icon quad sampling one sprite-atlas texture, drawn as one `glDrawElements`.
 *
 * A batch is per *texture* for [LabelBatch]'s reason: a texture bind is the one thing a single draw
 * cannot vary. A style declares at most one sprite pair, so an ordinary frame is one icon batch.
 */
internal class IconBatch(
    val atlasTexture: Int,
    val quads: List<ResolvedIconQuad>,
)

/**
 * One frame's icons. [outputPixelSize] is the frame's and is this pass's only per-frame uniform,
 * exactly as it is [LabelWorld]'s.
 */
internal class IconWorld(
    val outputPixelSize: OutputPixelSize,
    val batches: List<IconBatch> = emptyList(),
)

internal fun createIconPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): IconPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.ICON, ICON_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(ICON_VERTEX_SOURCE)
        ?: return IconPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(ICON_FRAGMENT_SOURCE)
        ?: return IconPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return IconPipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]
    binding.genBuffers(1, names)
    val indexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    ICON_VERTEX_ATTRIBUTES.forEach { attribute ->
        binding.enableVertexAttribArray(attribute.location)
        binding.vertexAttribPointer(
            attribute.location,
            attribute.components,
            GL_FLOAT,
            false,
            ICON_VERTEX_STRIDE_BYTES,
            attribute.offsetBytes,
        )
    }
    val pipeline = IconPipeline(
        key = key,
        program = program,
        vertexArray = vertexArray,
        vertexBuffer = vertexBuffer,
        indexBuffer = indexBuffer,
        atlasUniformLocation = binding.getUniformLocation(program, ICON_ATLAS_UNIFORM_NAME),
        viewportSizeUniformLocation = binding.getUniformLocation(program, ICON_VIEWPORT_SIZE_UNIFORM_NAME),
    )
    // Called with the vertex array still bound, so the vertex array captures the element-array binding
    // this makes -- see `createLabelPipeline` for why that is what lets `beginIconPass` restore both
    // buffers with one `bindVertexArray`.
    growIconBuffers(binding, pipeline, ICON_INITIAL_CAPACITY_QUADS)
    return IconPipelineResult.Created(pipeline)
}

internal fun deleteIconPipeline(binding: GlBinding, cache: GlProgramCache, pipeline: IconPipeline) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    binding.deleteBuffers(1, intArrayOf(pipeline.indexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * Draws one frame's icons: one `glDrawElements` per batch, whatever the batch's icon count.
 *
 * Order within a batch is exactly the order given; this function sorts nothing.
 */
internal fun drawIcons(binding: GlBinding, pipeline: IconPipeline, world: IconWorld) {
    val drawable = world.batches.filter { it.quads.isNotEmpty() }
    if (drawable.isEmpty()) return

    beginIconPass(binding, pipeline, world.outputPixelSize)
    drawable.forEach { batch -> drawIconBatch(binding, pipeline, batch) }
}

/**
 * Binds the icon program, its vertex array and its sampler unit, and establishes the state this pass
 * depends on rather than inheriting whatever the previous pass left bound.
 *
 * Identical in every particular to [beginLabelPass], and stated separately rather than shared
 * because the two passes reach it from different predecessors: the icon pass runs immediately after
 * the map regime's last sticker draw, which leaves the depth test **on** and face culling on, while
 * the label pass runs after this one. Depth testing off, because an icon's pixel is decided by the
 * placement pass on the CPU; face culling off, because a y-down screen-space corner order is
 * clockwise in clip space and therefore the back face by default; and the premultiplied blend
 * function the rest of RenG uses, which [ICON_FRAGMENT_SOURCE] emits to match.
 */
internal fun beginIconPass(binding: GlBinding, pipeline: IconPipeline, outputPixelSize: OutputPixelSize) {
    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.disable(GL_DEPTH_TEST)
    binding.disable(GL_CULL_FACE)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.activeTexture(GL_TEXTURE0)
    if (pipeline.atlasUniformLocation >= 0) {
        binding.uniform1i(pipeline.atlasUniformLocation, 0)
    }
    if (pipeline.viewportSizeUniformLocation >= 0) {
        binding.uniform2f(
            pipeline.viewportSizeUniformLocation,
            outputPixelSize.width.toFloat(),
            outputPixelSize.height.toFloat(),
        )
    }
}

/** Streams one batch's vertices into the pipeline's buffer and draws it, on [drawLabelBatch]'s terms. */
private fun drawIconBatch(binding: GlBinding, pipeline: IconPipeline, batch: IconBatch) {
    growIconBuffers(binding, pipeline, batch.quads.size)

    val bytes = littleEndianBytes(assembleIconVertices(batch.quads))
    binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
    binding.bufferData(GL_ARRAY_BUFFER, iconVertexCapacityBytes(pipeline.capacityQuads), null, GL_DYNAMIC_DRAW)
    binding.bufferSubData(GL_ARRAY_BUFFER, 0, bytes.size, bytes)

    binding.bindTexture(GL_TEXTURE_2D, batch.atlasTexture)
    binding.drawElements(GL_TRIANGLES, batch.quads.size * LABEL_INDICES_PER_QUAD, GL_UNSIGNED_INT, 0)
}

/** [growLabelBuffers] for the icon pass: doubles to fit, and rewrites the shared quad index pattern. */
private fun growIconBuffers(binding: GlBinding, pipeline: IconPipeline, requiredQuads: Int) {
    if (requiredQuads <= pipeline.capacityQuads) return

    var capacity = maxOf(pipeline.capacityQuads, ICON_INITIAL_CAPACITY_QUADS)
    while (capacity < requiredQuads) capacity *= 2
    pipeline.capacityQuads = capacity

    binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
    binding.bufferData(GL_ARRAY_BUFFER, iconVertexCapacityBytes(capacity), null, GL_DYNAMIC_DRAW)

    val indices = littleEndianIntBytes(labelQuadIndices(capacity))
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, pipeline.indexBuffer)
    binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size, indices, GL_STATIC_DRAW)
}

/**
 * The CPU-side vertex assembly: every corner of every quad in [quads], interleaved into one array in
 * the layout [ICON_VERTEX_ATTRIBUTES] describes.
 *
 * **The tint is written straight, with the icon's opacity in its alpha rather than multiplied into
 * its RGB.** That is the one place this deliberately differs from [assembleLabelVertices], which
 * premultiplies on the CPU: an icon's opacity has to reach the fragment as a *scalar* because it
 * attenuates the sampled artwork in the untinted case, where no tint colour is involved at all.
 * Premultiplying here would lose it for exactly the sprites that carry the artwork.
 *
 * [ResolvedIconPaint.tintable] is written as `1.0` or `0.0` per corner because a batch mixes `sdf`
 * entries and artwork entries out of one atlas, and a uniform is per draw.
 */
internal fun assembleIconVertices(quads: List<ResolvedIconQuad>): FloatArray {
    val vertices = FloatArray(quads.size * ICON_QUAD_CORNERS * ICON_VERTEX_FLOATS)
    var at = 0
    quads.forEach { quad ->
        val paint = quad.paint
        val tintable = if (paint.tintable) 1.0f else 0.0f
        for (corner in 0 until ICON_QUAD_CORNERS) {
            vertices[at] = quad.cornersXy[corner * 2]
            vertices[at + 1] = quad.cornersXy[corner * 2 + 1]
            vertices[at + 2] = quad.cornersUv[corner * 2]
            vertices[at + 3] = quad.cornersUv[corner * 2 + 1]
            vertices[at + 4] = paint.colour[0]
            vertices[at + 5] = paint.colour[1]
            vertices[at + 6] = paint.colour[2]
            // The tint's own alpha and `icon-opacity` are one number by the time a fragment reads them:
            // `SRC_IN` scales coverage by the filter's alpha, and the opacity scales the whole draw, so
            // multiplying them here saves an attribute without losing a distinction any fragment makes.
            vertices[at + 7] = paint.colour[3] * paint.opacity
            vertices[at + 8] = tintable
            at += ICON_VERTEX_FLOATS
        }
    }
    return vertices
}

/**
 * Puts [image] -- one decoded sprite atlas -- on the GPU under [key] and hands back the texture plus
 * the [TextureLease] the caller must release exactly once, or reuses the one already resident.
 *
 * **[uploadGlyphAtlas]'s residency shape, and deliberately not its sampler.** A glyph atlas takes
 * `DATA`'s bytes with `IMAGE`'s filters, because premultiplying a field whose RGB is forced white
 * would rewrite every colour channel into a copy of the alpha. A sprite atlas is the ordinary case
 * that rule is an exception to: its RGB is artwork, its alpha is coverage, and premultiplying
 * *before* GL's bilinear filter runs is exactly what stops a transparent texel's meaningless RGB
 * bleeding into a sprite's edge. So this takes [TextureContent.IMAGE] whole -- premultiplied bytes,
 * `GL_LINEAR` both ways, `GL_CLAMP_TO_EDGE` both axes, and no mipmap chain, since a mipmap level of
 * a packed atlas averages across the gap into a neighbouring sprite.
 *
 * The accepted cost of `GL_LINEAR` on a packed atlas is that a minified sprite can pick up a texel
 * of its neighbour at its own boundary. Real sprite sheets pad their entries for exactly this, and
 * `GL_NEAREST` would trade that for an icon that visibly stair-steps at every scale but 1.
 *
 * Enrolled in the byte budget through [GlObjectRegistry.registerTexture], so an atlas competes
 * honestly with ground tiles and the glyph atlas rather than being a texture the budget can never
 * reclaim. The live lease is what keeps it safe from eviction for the duration of the draw.
 */
internal fun uploadSpriteAtlas(
    binding: GlBinding,
    registry: GlObjectRegistry,
    key: ResourceKey,
    image: DecodedImage,
): LeasedTexture {
    registry.leaseResident(key)?.let { return it }
    val handle = GlObjectHandle(
        type = GlObjectType.TEXTURE,
        name = uploadTexture(binding, image, TextureContent.IMAGE),
    )
    return LeasedTexture(
        handle = handle,
        lease = registry.registerTexture(key = key, handle = handle, byteSize = image.byteCount.toLong()),
    )
}

private fun iconVertexCapacityBytes(quads: Int): Int = quads * ICON_QUAD_CORNERS * ICON_VERTEX_STRIDE_BYTES

/**
 * The interleaved layout, in the order the floats are written. Four attributes rather than the label
 * pass's five: an icon carries no halo band and no field scale, because it has no field.
 */
internal val ICON_VERTEX_ATTRIBUTES: List<LabelVertexAttribute> = listOf(
    LabelVertexAttribute(location = 0, components = 2, offsetBytes = 0),
    LabelVertexAttribute(location = 1, components = 2, offsetBytes = 8),
    LabelVertexAttribute(location = 2, components = 4, offsetBytes = 16),
    LabelVertexAttribute(location = 3, components = 1, offsetBytes = 32),
)

internal const val ICON_VERTEX_FLOATS: Int = 9
internal const val ICON_VERTEX_STRIDE_BYTES: Int = ICON_VERTEX_FLOATS * 4
internal const val ICON_INITIAL_CAPACITY_QUADS: Int = 64

internal const val ICON_QUAD_CORNERS: Int = 4

private const val ICON_RGBA_COMPONENTS: Int = 4
private const val ICON_UNFADED: Float = 1.0f
