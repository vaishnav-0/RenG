package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's glyph shader: a signed-distance-field sample thresholded twice, once for the glyph body
 * and once for its halo, composited in one fragment.
 *
 * **Why this pipeline exists rather than a [StickerPipeline] parameterisation.** A sticker's whole
 * fragment shader is `texture(...)`, and a sticker's whole per-instance variation travels through a
 * `uniformMatrix4fv` against one reused unit quad -- one uniform upload and one draw call per quad.
 * A dense labelled viewport is thousands of glyph quads, so that design does not scale, and it is
 * the precedent this file breaks from rather than follows. Everything per-glyph here is a **vertex
 * attribute** written into one array by [assembleLabelVertices], and one [LabelBatch] is one
 * `glDrawElements`, however many glyphs it carries.
 *
 * **Why the shape of the field is not RenG's invention.** The atlas is glyph-range bytes the engine
 * packed without re-encoding, so the field is the `glyphs.pbf` convention every renderer of that
 * format reads: alpha rises toward the glyph interior, the outline sits at
 * [LABEL_FILL_EDGE_DISTANCE], and one unit of alpha spans [LABEL_SDF_PIXELS_PER_UNIT] em pixels at
 * the 24-pixel em. The two thresholds and the two smoothing widths below are MapLibre's, named and
 * derived rather than copied as magic numbers, because a glyph rendered against a different
 * iso-value is not blurry -- it is a different, and wrong, letterform.
 *
 * **Legibility is not claimed here and is unverified until Cycle J.** What is verified is that the
 * halo band admits pixels the fill band does not, that per-candidate colour reaches the fragment,
 * and that a batch of many quads is one draw. How the result *reads* to a human eye is E8's, and
 * this cycle does not answer it.
 */
internal const val LABEL_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengLabelPosition;\n" +
        "layout(location = 1) in vec2 rengLabelTexCoord;\n" +
        "layout(location = 2) in vec4 rengLabelFillColour;\n" +
        "layout(location = 3) in vec4 rengLabelHaloColour;\n" +
        "layout(location = 4) in vec3 rengLabelSdfBands;\n" +
        "uniform vec2 rengLabelViewportSize;\n" +
        "out vec2 rengLabelUv;\n" +
        "out vec4 rengLabelFill;\n" +
        "out vec4 rengLabelHalo;\n" +
        "out vec3 rengLabelBands;\n" +
        "void main() {\n" +
        "    rengLabelUv = rengLabelTexCoord;\n" +
        "    rengLabelFill = rengLabelFillColour;\n" +
        "    rengLabelHalo = rengLabelHaloColour;\n" +
        "    rengLabelBands = rengLabelSdfBands;\n" +
        "    vec2 ndc = vec2(\n" +
        "        rengLabelPosition.x / rengLabelViewportSize.x * 2.0 - 1.0,\n" +
        "        1.0 - rengLabelPosition.y / rengLabelViewportSize.y * 2.0\n" +
        "    );\n" +
        "    gl_Position = vec4(ndc, 0.0, 1.0);\n" +
        "}\n"

/**
 * The halo is composited **under** the fill in one fragment rather than in a second pass.
 *
 * Drawing halo-then-fill as two passes over the same geometry produces exactly `fill over halo`
 * under the premultiplied blend function this pass establishes, so doing it locally is the same
 * picture at half the draw calls -- and a second pass would need a second copy of every vertex,
 * which is the cost a batch exists to avoid.
 *
 * Both colours arrive premultiplied ([assembleLabelVertices] does that on the CPU, once per quad
 * rather than once per fragment), so `colour * coverage` is the correct premultiplied attenuation
 * and `fill + halo * (1 - fill.a)` is source-over between them.
 */
internal const val LABEL_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengLabelAtlas;\n" +
        "in vec2 rengLabelUv;\n" +
        "in vec4 rengLabelFill;\n" +
        "in vec4 rengLabelHalo;\n" +
        "in vec3 rengLabelBands;\n" +
        "layout(location = 0) out vec4 rengLabelColour;\n" +
        "const float rengLabelFillEdge = 0.75;\n" +
        "void main() {\n" +
        "    float fieldDistance = texture(rengLabelAtlas, rengLabelUv).a;\n" +
        "    float fillGamma = rengLabelBands.x;\n" +
        "    float haloEdge = rengLabelBands.y;\n" +
        "    float haloGamma = rengLabelBands.z;\n" +
        "    float fillCoverage = smoothstep(\n" +
        "        rengLabelFillEdge - fillGamma, rengLabelFillEdge + fillGamma, fieldDistance);\n" +
        "    float haloCoverage = smoothstep(haloEdge - haloGamma, haloEdge + haloGamma, fieldDistance);\n" +
        "    vec4 fill = rengLabelFill * fillCoverage;\n" +
        "    vec4 halo = rengLabelHalo * haloCoverage;\n" +
        "    rengLabelColour = fill + halo * (1.0 - fill.a);\n" +
        "}\n"

internal val LABEL_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = LABEL_VERTEX_SOURCE, fragmentSource = LABEL_FRAGMENT_SOURCE)

internal const val LABEL_ATLAS_UNIFORM_NAME: String = "rengLabelAtlas"
internal const val LABEL_VIEWPORT_SIZE_UNIFORM_NAME: String = "rengLabelViewportSize"

/**
 * The alpha the glyph outline itself carries, `(256 - 64) / 256`.
 *
 * The `glyphs.pbf` generator encodes a signed distance with a quarter of its range reserved for the
 * interior, so the outline is three quarters of the way up the byte rather than half way. Reading
 * the field at `0.5` -- the value an SDF's name suggests -- fattens every glyph by a full em pixel
 * at 24 px and turns thin strokes into blobs, which is why this constant is named rather than
 * assumed.
 */
internal const val LABEL_FILL_EDGE_DISTANCE: Float = 0.75f

/**
 * How many em pixels of distance one unit of field alpha spans, at the 24-pixel SDF em the engine's
 * glyph entries are measured in. This is the generator's radius, and it is **not**
 * `GlyphRangeDecoder.BUFFER_PX`: the field ranges over 8 pixels, of which only the 3 nearest the
 * outline fit inside the packed cell. Halo widths are converted into field units by dividing by it.
 */
internal const val LABEL_SDF_PIXELS_PER_UNIT: Float = 8.0f

/**
 * Half the width, in field units at a scale of 1, of the smoothstep band that antialiases an edge.
 * MapLibre's `EDGE_GAMMA`, which is roughly `0.84 / 8` -- a little under one screen pixel of ramp.
 * Divided by the glyph's own scale at assembly, because a glyph drawn at twice the size crosses the
 * same field range over twice as many screen pixels and wants half the field-space ramp.
 */
internal const val LABEL_EDGE_GAMMA: Float = 0.105f

/** `GlyphRangeDecoder.BUFFER_PX`: how much of the field the engine's packed cell actually carries. */
private const val LABEL_SDF_GLYPH_BUFFER_PIXELS: Float = 3.0f

/**
 * The furthest the halo iso-line may be pushed from the outline, in field units.
 *
 * Beyond `GlyphRangeDecoder.BUFFER_PX` em pixels the packed cell simply contains no field: the
 * outermost texel of a cell is that far outside the glyph, so an iso-line below this value passes
 * *every* texel in the cell and the halo becomes the cell's rectangle. Clamping here turns an
 * over-wide `text-halo-width` into a halo that stops growing; not clamping turns it into a visible
 * box around every letter, which reads as a rendering bug rather than as a style asking for too
 * much.
 */
internal const val LABEL_HALO_EDGE_FLOOR: Float =
    LABEL_FILL_EDGE_DISTANCE - LABEL_SDF_GLYPH_BUFFER_PIXELS / LABEL_SDF_PIXELS_PER_UNIT

/**
 * A compiled label program, its vertex array, and the two buffers a batch streams through.
 *
 * [capacityQuads] is a **high-water mark, and the one piece of mutable state in this file.** The
 * batch's size is a property of the frame rather than of the pipeline -- E5 ships no public ceiling
 * on candidate count, and task 17 measures before one is proposed -- so the buffers grow to fit the
 * largest batch drawn so far and never shrink. A frame with fewer glyphs re-fills the front of a
 * larger allocation rather than reallocating a smaller one, which is what makes the steady state
 * allocation-free on the GL side.
 *
 * [indexBuffer]'s contents are a pure function of [capacityQuads] -- `0,1,2, 0,2,3` per quad,
 * against four corners -- so it is rewritten only when the capacity moves, never per frame.
 */
internal class LabelPipeline(
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

internal sealed interface LabelPipelineResult {
    data class Created(val pipeline: LabelPipeline) : LabelPipelineResult

    data class Failed(val failure: FailureDescriptor) : LabelPipelineResult
}

/**
 * One candidate's paint, in the vocabulary the engine hands it over in, before any field arithmetic
 * has been applied to it.
 *
 * **Per candidate, never per layer.** The engine moved `color` and `haloColor` onto the candidate
 * precisely because road, water and POI layers pick them from feature data, so a per-layer colour
 * cache would paint every road name in one road's colour. That is why these are vertex attributes
 * below rather than uniforms: a uniform is a per-draw value, and per-draw is exactly what a batch
 * abolishes.
 *
 * [textColour] and [haloColour] are **straight** (unpremultiplied) RGBA in `[0, 1]`, the shape an
 * unpacked `0xAARRGGBB` naturally takes; [assembleLabelVertices] applies [opacity] and premultiplies
 * both. [haloWidthPixels] and [haloBlurPixels] are screen pixels, and [scale] is the glyph quad's
 * own `text-size / 24`, which is what converts between the two.
 */
internal class ResolvedLabelPaint(
    val textColour: FloatArray,
    val haloColour: FloatArray,
    val opacity: Float = 1.0f,
    val haloWidthPixels: Float = 0.0f,
    val haloBlurPixels: Float = 0.0f,
    val scale: Float = 1.0f,
) {
    init {
        require(textColour.size == RGBA_COMPONENTS) { "textColour must be four components" }
        require(haloColour.size == RGBA_COMPONENTS) { "haloColour must be four components" }
        require(scale > 0.0f && scale.isFinite()) { "scale must be positive and finite" }
    }
}

/**
 * One glyph's quad, already placed.
 *
 * [cornersXy] is four `(x, y)` pairs in `CONTEXT.md`'s continuous output-pixel screen space --
 * origin top-left, positive y **downward** -- in the corner order top-left, top-right,
 * bottom-right, bottom-left. [cornersUv] is the same four corners in normalised atlas coordinates,
 * in the same order.
 *
 * **The corners are given rather than derived, and that is what makes line placement expressible.**
 * A quad here is any convex quadrilateral, not an axis-aligned rectangle plus a rotation, so a glyph
 * bent along a road is a different four numbers rather than a different code path. Nothing in this
 * file rotates, anchors or offsets anything: every transform a label undergoes is already baked into
 * these eight numbers by the time the pipeline sees them, which is the same reason the shader needs
 * no matrix at all.
 */
internal class ResolvedGlyphQuad(
    val cornersXy: FloatArray,
    val cornersUv: FloatArray,
    val paint: ResolvedLabelPaint,
) {
    init {
        require(cornersXy.size == QUAD_CORNERS * 2) { "cornersXy must be four (x, y) pairs" }
        require(cornersUv.size == QUAD_CORNERS * 2) { "cornersUv must be four (u, v) pairs" }
    }
}

/**
 * Every glyph quad sampling one atlas texture, drawn as one `glDrawElements`.
 *
 * A batch is per *texture* because a texture bind is the one thing a single draw cannot vary. Text
 * from one `prepare()` shares one glyph atlas, so the ordinary frame is one batch; a second texture
 * -- a sprite atlas, once icons land -- is a second batch and a second draw, and nothing else.
 */
internal class LabelBatch(
    val atlasTexture: Int,
    val quads: List<ResolvedGlyphQuad>,
)

/**
 * One frame's labels.
 *
 * [outputPixelSize] is the frame's, not a batch's: it is the only per-frame uniform this pass sets,
 * and it is a uniform rather than a per-vertex value because it is genuinely the same for every
 * glyph on the screen. Keeping the vertex positions in pixels rather than pre-dividing them into
 * clip space on the CPU means an assembled batch reads in the same space placement and collision
 * work in.
 */
internal class LabelWorld(
    val outputPixelSize: OutputPixelSize,
    val batches: List<LabelBatch> = emptyList(),
)

internal fun createLabelPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): LabelPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.LABEL, LABEL_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(LABEL_VERTEX_SOURCE)
        ?: return LabelPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(LABEL_FRAGMENT_SOURCE)
        ?: return LabelPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return LabelPipelineResult.Failed(result.failure)
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
    LABEL_VERTEX_ATTRIBUTES.forEach { attribute ->
        binding.enableVertexAttribArray(attribute.location)
        binding.vertexAttribPointer(
            attribute.location,
            attribute.components,
            GL_FLOAT,
            false,
            LABEL_VERTEX_STRIDE_BYTES,
            attribute.offsetBytes,
        )
    }
    // Bound while the vertex array is, so the vertex array captures it: an element-array binding is
    // vertex-array state, which is what lets `beginLabelPass` restore both buffers with one call.
    // `growLabelBuffers` binds it again below; this one stands on its own so the capture does not
    // depend on that call reaching its allocating branch.
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)

    val pipeline = LabelPipeline(
        key = key,
        program = program,
        vertexArray = vertexArray,
        vertexBuffer = vertexBuffer,
        indexBuffer = indexBuffer,
        atlasUniformLocation = binding.getUniformLocation(program, LABEL_ATLAS_UNIFORM_NAME),
        viewportSizeUniformLocation = binding.getUniformLocation(program, LABEL_VIEWPORT_SIZE_UNIFORM_NAME),
    )
    growLabelBuffers(binding, pipeline, LABEL_INITIAL_CAPACITY_QUADS)
    return LabelPipelineResult.Created(pipeline)
}

internal fun deleteLabelPipeline(binding: GlBinding, cache: GlProgramCache, pipeline: LabelPipeline) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    binding.deleteBuffers(1, intArrayOf(pipeline.indexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * Draws one frame's labels: **one `glDrawElements` per batch**, whatever the batch's glyph count.
 *
 * Every other pass in RenG issues one draw per logical object -- per sticker, per tile, per
 * geometry, per model primitive. This one does not, and that is the whole reason it exists as a
 * separate pipeline. A batch of one glyph and a batch of ten thousand differ in the size of one
 * `glBufferSubData` and in the count argument of one `glDrawElements`, and in nothing else.
 *
 * Order within a batch is exactly the order given; this function sorts nothing. Which labels
 * survived collision, and in what order they are handed over, is the placement pass's answer.
 */
internal fun drawLabels(binding: GlBinding, pipeline: LabelPipeline, world: LabelWorld) {
    val drawable = world.batches.filter { it.quads.isNotEmpty() }
    if (drawable.isEmpty()) return

    beginLabelPass(binding, pipeline, world.outputPixelSize)
    drawable.forEach { batch -> drawLabelBatch(binding, pipeline, batch) }
}

/**
 * Binds the label program, its vertex array and its sampler unit, and establishes the state this
 * pass depends on rather than inheriting whatever the previous pass left bound.
 *
 * **Depth testing off.** Labels are screen-space primitives: the pixel a glyph lands on is decided
 * entirely by the placement pass on the CPU, and a depth test would let map-regime content that
 * happens to be nearer erase text that collision had already found room for. Disabling the test also
 * disables depth *writes*, so no `depthMask` call is needed here -- unlike the map regime, where the
 * test stays on and the mask is the thing being turned off (ADR 0027).
 *
 * **Face culling off**, which no other RenG pass has to say. A glyph quad's winding follows the
 * screen-space corner order the placement pass produced, and under a y-down screen space that order
 * is clockwise in clip space -- the back face by default. The model pass leaves culling enabled for
 * a single-sided primitive, so inheriting it here would delete every glyph rather than some.
 *
 * The blend function is the premultiplied `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` the rest of RenG uses,
 * and the fragment shader emits premultiplied colour to match.
 */
internal fun beginLabelPass(binding: GlBinding, pipeline: LabelPipeline, outputPixelSize: OutputPixelSize) {
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

/**
 * Streams one batch's vertices into the pipeline's buffer and draws it.
 *
 * The upload is the shape F-2's joint palette established -- a `GL_DYNAMIC_DRAW` allocation made
 * once with a **null** payload and refilled with `glBufferSubData` -- rather than the shape
 * `uploadModelPrimitive` established, which is `GL_STATIC_DRAW` and cached by key. A batch's bytes
 * are different every frame and are never reused, so caching them by content would be a hash of
 * something that never repeats. Re-issuing `glBufferData` with a null payload before each fill is
 * orphaning: it tells the driver the old contents are dead, so a fill need not wait for the previous
 * frame's draw to finish reading them.
 */
private fun drawLabelBatch(binding: GlBinding, pipeline: LabelPipeline, batch: LabelBatch) {
    growLabelBuffers(binding, pipeline, batch.quads.size)

    val bytes = littleEndianBytes(assembleLabelVertices(batch.quads))
    binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
    binding.bufferData(GL_ARRAY_BUFFER, labelVertexCapacityBytes(pipeline.capacityQuads), null, GL_DYNAMIC_DRAW)
    binding.bufferSubData(GL_ARRAY_BUFFER, 0, bytes.size, bytes)

    binding.bindTexture(GL_TEXTURE_2D, batch.atlasTexture)
    binding.drawElements(GL_TRIANGLES, batch.quads.size * LABEL_INDICES_PER_QUAD, GL_UNSIGNED_INT, 0)
}

/**
 * Widens both buffers to hold at least [requiredQuads] and rewrites the index buffer to match, or
 * does nothing when they already fit.
 *
 * Capacity doubles rather than fitting exactly, so a frame that grows the batch by one glyph does
 * not reallocate; the index buffer is rewritten only here, because its contents depend on the
 * capacity and on nothing else about a frame.
 */
private fun growLabelBuffers(binding: GlBinding, pipeline: LabelPipeline, requiredQuads: Int) {
    if (requiredQuads <= pipeline.capacityQuads) return

    var capacity = maxOf(pipeline.capacityQuads, LABEL_INITIAL_CAPACITY_QUADS)
    while (capacity < requiredQuads) capacity *= 2
    pipeline.capacityQuads = capacity

    binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
    binding.bufferData(GL_ARRAY_BUFFER, labelVertexCapacityBytes(capacity), null, GL_DYNAMIC_DRAW)

    val indices = littleEndianIntBytes(labelQuadIndices(capacity))
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, pipeline.indexBuffer)
    binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size, indices, GL_STATIC_DRAW)
}

/**
 * The CPU-side vertex assembly: every glyph corner of every quad in [quads], interleaved into one
 * array in the layout [LABEL_VERTEX_ATTRIBUTES] describes.
 *
 * **This is the batch.** Nothing else in RenG builds geometry per frame except F-2's joint palette,
 * and nothing at all builds it out of a list of logical objects. Per corner it writes the screen
 * pixel, the atlas coordinate, both premultiplied colours and the three field-space numbers the
 * fragment shader thresholds with -- so a glyph's colour, its halo's colour, its halo's width and
 * its own size all travel as data rather than as a uniform upload between two draws.
 *
 * The three field numbers are derived here rather than in the shader because they depend only on the
 * candidate: computing them per fragment would recompute one division millions of times a frame for
 * an answer that is constant across a letter.
 *
 * **The colours are premultiplied here**, after [ResolvedLabelPaint.opacity] has been folded into
 * both alphas, because the blend function this pass establishes expects premultiplied source. Doing
 * it once per quad rather than once per fragment is free; doing it in the fragment shader would also
 * be correct and would cost a multiply per pixel per glyph.
 */
internal fun assembleLabelVertices(quads: List<ResolvedGlyphQuad>): FloatArray {
    val vertices = FloatArray(quads.size * QUAD_CORNERS * LABEL_VERTEX_FLOATS)
    var at = 0
    quads.forEach { quad ->
        val paint = quad.paint
        val fill = premultiplied(paint.textColour, paint.opacity)
        val halo = premultiplied(paint.haloColour, paint.opacity)
        val fillGamma = labelEdgeGamma(scale = paint.scale, blurPixels = 0.0f)
        val haloGamma = labelEdgeGamma(scale = paint.scale, blurPixels = paint.haloBlurPixels)
        val haloEdge = labelHaloEdgeDistance(scale = paint.scale, haloWidthPixels = paint.haloWidthPixels)

        for (corner in 0 until QUAD_CORNERS) {
            vertices[at] = quad.cornersXy[corner * 2]
            vertices[at + 1] = quad.cornersXy[corner * 2 + 1]
            vertices[at + 2] = quad.cornersUv[corner * 2]
            vertices[at + 3] = quad.cornersUv[corner * 2 + 1]
            fill.copyInto(vertices, at + 4)
            halo.copyInto(vertices, at + 8)
            vertices[at + 12] = fillGamma
            vertices[at + 13] = haloEdge
            vertices[at + 14] = haloGamma
            at += LABEL_VERTEX_FLOATS
        }
    }
    return vertices
}

/**
 * Half the smoothstep band, in field units, for an edge drawn at [scale] with [blurPixels] of
 * `text-halo-blur` on it. Floored so the two edges handed to `smoothstep` are never equal, which is
 * undefined rather than merely sharp.
 */
internal fun labelEdgeGamma(scale: Float, blurPixels: Float): Float {
    val blurTerm = blurPixels * LABEL_HALO_BLUR_FACTOR / LABEL_SDF_PIXELS_PER_UNIT
    return maxOf((blurTerm + LABEL_EDGE_GAMMA) / scale, LABEL_MINIMUM_GAMMA)
}

/**
 * The field value the halo's outer edge sits at: [haloWidthPixels] screen pixels outside the glyph
 * outline, converted into field units through the glyph's own [scale], and clamped at
 * [LABEL_HALO_EDGE_FLOOR] where the packed cell runs out of field.
 *
 * A width of zero returns [LABEL_FILL_EDGE_DISTANCE] exactly, which puts the halo band on top of the
 * fill band -- so an un-haloed label composites its halo colour underneath an identical fill and
 * shows nothing of it, without needing a second program or a branch.
 */
internal fun labelHaloEdgeDistance(scale: Float, haloWidthPixels: Float): Float {
    val offset = haloWidthPixels / scale / LABEL_SDF_PIXELS_PER_UNIT
    return maxOf(LABEL_FILL_EDGE_DISTANCE - offset, LABEL_HALO_EDGE_FLOOR)
}

/** [colour], scaled by [opacity] and premultiplied by the resulting alpha. */
private fun premultiplied(colour: FloatArray, opacity: Float): FloatArray {
    val alpha = colour[3] * opacity
    return floatArrayOf(colour[0] * alpha, colour[1] * alpha, colour[2] * alpha, alpha)
}

/**
 * `0,1,2, 0,2,3` per quad against its four corners in [ResolvedGlyphQuad]'s documented order, which
 * is two triangles covering the quad exactly once with a shared diagonal from top-left to
 * bottom-right.
 */
internal fun labelQuadIndices(quads: Int): IntArray {
    val indices = IntArray(quads * LABEL_INDICES_PER_QUAD)
    for (quad in 0 until quads) {
        val base = quad * QUAD_CORNERS
        val at = quad * LABEL_INDICES_PER_QUAD
        indices[at] = base
        indices[at + 1] = base + 1
        indices[at + 2] = base + 2
        indices[at + 3] = base
        indices[at + 4] = base + 2
        indices[at + 5] = base + 3
    }
    return indices
}

/**
 * [values] as little-endian 32-bit words, the integer counterpart of [littleEndianBytes] and correct
 * by construction for the same reason: every published target is little-endian and GL reads client
 * memory in host byte order.
 */
internal fun littleEndianIntBytes(values: IntArray): ByteArray {
    val bytes = ByteArray(values.size * Int.SIZE_BYTES)
    var offset = 0
    values.forEach { value ->
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
        offset += Int.SIZE_BYTES
    }
    return bytes
}

private fun labelVertexCapacityBytes(quads: Int): Int = quads * QUAD_CORNERS * LABEL_VERTEX_STRIDE_BYTES

/** One interleaved vertex attribute: where it binds, how wide it is, and where it starts. */
internal class LabelVertexAttribute(
    val location: Int,
    val components: Int,
    val offsetBytes: Int,
)

/**
 * The interleaved layout, in the order the floats are written.
 *
 * Five attributes rather than two is the price of the batch: a sticker carries its per-instance
 * state in one uniform because it pays a draw call per instance, and a glyph cannot.
 */
internal val LABEL_VERTEX_ATTRIBUTES: List<LabelVertexAttribute> = listOf(
    LabelVertexAttribute(location = 0, components = 2, offsetBytes = 0),
    LabelVertexAttribute(location = 1, components = 2, offsetBytes = 8),
    LabelVertexAttribute(location = 2, components = 4, offsetBytes = 16),
    LabelVertexAttribute(location = 3, components = 4, offsetBytes = 32),
    LabelVertexAttribute(location = 4, components = 3, offsetBytes = 48),
)

internal const val LABEL_VERTEX_FLOATS: Int = 15
internal const val LABEL_VERTEX_STRIDE_BYTES: Int = LABEL_VERTEX_FLOATS * 4
internal const val LABEL_INDICES_PER_QUAD: Int = 6
internal const val LABEL_INITIAL_CAPACITY_QUADS: Int = 256

private const val QUAD_CORNERS: Int = 4
private const val RGBA_COMPONENTS: Int = 4

/** MapLibre's empirical scaling from `text-halo-blur` pixels into extra smoothstep width. */
private const val LABEL_HALO_BLUR_FACTOR: Float = 1.19f

/** Small enough to be a hard edge on any rasteriser, large enough that `smoothstep` stays ordered. */
private const val LABEL_MINIMUM_GAMMA: Float = 1.0e-4f
