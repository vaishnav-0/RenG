package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.DecodedImage
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle E-labels task 8's real-driver half: three glyph quads drawn through the production pipeline
 * in **one** `glDrawElements`, read back, and classified pixel by pixel.
 *
 * **Why a readback when `LabelPipelineTest` already pins every call.** A call log proves RenG asked
 * for the right thing; it cannot prove the shader compiles on a real driver, that the interleaved
 * attribute offsets line up with what the linked program actually reads, that the y-down screen
 * space reaches clip space the right way up, or that the two smoothstep bands are ordered such that
 * a halo pixel is a halo pixel. Every one of those is invisible to a fake and visible here.
 *
 * **The halo trap, closed.** A halo assertion whose halo colour equals its text colour passes with
 * the entire halo term deleted from the fragment shader. Here the two are never equal, and the
 * discriminating sample is a field value **only the halo band admits**: [HALO_ONLY_ALPHA] sits above
 * the halo edge and below the fill edge by a wide margin in both directions, so a pixel reading the
 * halo colour there cannot be explained by the fill, by the background, or by the two bands
 * collapsing onto each other.
 *
 * **The batch trap, closed the only way a readback can close it.** A readback cannot count draw
 * calls -- that is `LabelPipelineTest.threeQuadsAreOneDrawCallCarryingAllEighteenIndices`'s job. What
 * it can do is give each of the three quads its **own** text and halo colour, six values no two of
 * which are equal, and require all six to appear in the frame. A batch that wrote one quad's paint
 * across the whole vertex array, or that dropped every quad after the first, paints a frame this
 * cannot read as correct.
 *
 * **What this does not claim.** Not legibility -- no real glyph is drawn here, and legibility is
 * unverified until Cycle J. Not placement, not collision, not draw order. One atlas, one batch,
 * three quads, and the arithmetic between a distance field and a colour.
 */
internal fun runLabelReadbackSuite(binding: GlBinding) {
    val profile = (adoptRenderContext(binding) as? RenderContextAdoption.Adopted)?.profile
        ?: throw AssertionError("the fixture context must satisfy the ES 3.0 requirement")
    val cache = GlProgramCache()
    val pipeline = (
        createLabelPipeline(binding, profile.dialect, cache, ResourceKeyDeriver())
            as? LabelPipelineResult.Created
        )?.pipeline ?: throw AssertionError("the label pipeline must link on this context")

    val names = IntArray(1)
    binding.genTextures(1, names)
    val targetTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, targetTexture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, LABEL_READBACK_PIXELS, LABEL_READBACK_PIXELS)
    binding.genFramebuffers(1, names)
    val targetFramebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0)
    binding.drawBuffers(1, intArrayOf(GL_COLOR_ATTACHMENT0))
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the readback target must be framebuffer-complete",
    )
    GlErrorQueue.drainOnEntry(binding)

    val registry = GlObjectRegistry()
    val atlas = uploadGlyphAtlas(binding, registry, atlasKey(), fieldAtlas())

    binding.viewport(0, 0, LABEL_READBACK_PIXELS, LABEL_READBACK_PIXELS)
    binding.scissor(0, 0, LABEL_READBACK_PIXELS, LABEL_READBACK_PIXELS)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.clearColor(0.0f, 0.0f, 0.0f, 0.0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    // Face culling on and depth testing on, deliberately, and both left for the pass to turn off
    // itself: a glyph quad's winding under a y-down screen space is the back face by default, and the
    // model pass really does leave culling enabled behind a single-sided primitive. If `beginLabelPass`
    // inherited this state rather than establishing its own, the frame below would be empty.
    binding.enable(GL_CULL_FACE)
    binding.cullFace(GL_BACK)
    binding.frontFace(GL_CCW)
    binding.enable(GL_DEPTH_TEST)
    binding.bindSampler(0, 0)

    drawLabels(
        binding,
        pipeline,
        LabelWorld(
            outputPixelSize = OutputPixelSize(LABEL_READBACK_PIXELS, LABEL_READBACK_PIXELS),
            batches = listOf(LabelBatch(atlasTexture = atlas.handle.name, quads = threeColouredQuads())),
        ),
    )

    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    val row = readRow(binding, COVERED_GL_ROW)
    val uncovered = readRow(binding, UNCOVERED_GL_ROW)
    println("RenG label readback covered row: " + describe(row))
    println("RenG label readback uncovered row: " + describe(uncovered))

    QUAD_PAINTS.forEachIndexed { quad, paint ->
        val left = quad * ATLAS_TEXELS
        // Texels 0 and 1 are far outside the glyph: below the halo edge as well as the fill edge, so
        // nothing draws and the cleared framebuffer shows through. A halo band that had swallowed the
        // whole cell -- the failure the edge clamp exists to prevent -- shows up here first.
        assertPixel(row, left + 0, TRANSPARENT, "quad $quad texel 0 is outside both bands")
        assertPixel(row, left + 1, TRANSPARENT, "quad $quad texel 1 is outside both bands")
        // The load-bearing sample. Only the halo band admits this field value.
        assertPixel(row, left + 2, paint.halo, "quad $quad texel 2 is inside the halo band and outside the fill band")
        // Deep inside the glyph: the fill is opaque, so it covers its own halo exactly.
        for (texel in 3 until ATLAS_TEXELS) {
            assertPixel(row, left + texel, paint.fill, "quad $quad texel $texel is inside the fill band")
        }
    }

    // The vertical half, and the reason the quads do not span the frame. Screen space is y-down and
    // GL's framebuffer is y-up, so a vertex shader that forgot the flip puts every glyph exactly as
    // far from the wrong edge as it should have been from the right one. The band the quads occupy
    // and the band this row sits in are each other's mirror image about the frame's centre, so an
    // unflipped pass fails both of these rather than either alone -- and a frame-filling quad, which
    // is the obvious fixture to reach for, would have caught neither.
    for (at in 0 until LABEL_READBACK_PIXELS) {
        assertPixel(uncovered, at, TRANSPARENT, "no glyph quad covers GL row $UNCOVERED_GL_ROW")
    }

    // Stated as its own claim rather than left implicit in the loop: six colours, no two equal, all
    // six present. This is what a batch that collapsed onto one quad's paint cannot produce.
    val seen = QUAD_PAINTS.flatMap { listOf(it.halo.toList(), it.fill.toList()) }
    assertEquals(6, seen.toSet().size, "the fixture's six colours must be distinct or it proves nothing")

    assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding), "the label draw must provoke no GL error")

    registry.liveKeys().forEach { key -> deleteGlObjects(binding, registry.handles(key)) }
    deleteLabelPipeline(binding, cache, pipeline)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    binding.deleteFramebuffers(1, intArrayOf(targetFramebuffer))
    binding.deleteTextures(1, intArrayOf(targetTexture))
    binding.disable(GL_CULL_FACE)
    binding.disable(GL_DEPTH_TEST)
    GlErrorQueue.drainOnEntry(binding)
}

/**
 * Three quads side by side, each eight pixels wide -- one screen pixel per atlas texel, so a pixel
 * centre lands exactly on a texel centre and linear filtering contributes nothing of the neighbour.
 *
 * Corner order is [ResolvedGlyphQuad]'s: top-left, top-right, bottom-right, bottom-left in the y-down
 * screen space, paired corner for corner with the atlas rectangle.
 *
 * Vertically the quads occupy a **band near the top of the screen** rather than the whole frame,
 * which is what lets the frame say anything about the y axis at all: screen space is y-down and the
 * framebuffer is y-up, so a pass that dropped the flip would put a full-height quad in exactly the
 * same place and a banded one in the mirrored place.
 */
private fun threeColouredQuads(): List<ResolvedGlyphQuad> = QUAD_PAINTS.mapIndexed { quad, paint ->
    val left = (quad * ATLAS_TEXELS).toFloat()
    val right = left + ATLAS_TEXELS.toFloat()
    val top = QUAD_TOP_SCREEN_Y
    val bottom = QUAD_BOTTOM_SCREEN_Y
    ResolvedGlyphQuad(
        cornersXy = floatArrayOf(left, top, right, top, right, bottom, left, bottom),
        cornersUv = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f),
        paint = ResolvedLabelPaint(
            textColour = paint.fill.map { it / 255.0f }.toFloatArray(),
            haloColour = paint.halo.map { it / 255.0f }.toFloatArray(),
            opacity = 1.0f,
            haloWidthPixels = HALO_WIDTH_PIXELS,
            haloBlurPixels = 0.0f,
            scale = GLYPH_SCALE,
        ),
    )
}

/**
 * A one-row atlas shaped like the thing the engine hands over: RGB forced opaque white, the field in
 * alpha. The eight alphas are deliberately **asymmetric**, so a horizontally mirrored atlas
 * coordinate produces a different classification per pixel rather than the same one read backwards.
 *
 * The three values are chosen for margin rather than for realism. [OUTSIDE_ALPHA] and
 * [HALO_ONLY_ALPHA] sit roughly a quarter of the field apart from the nearest band edge on either
 * side, which is what makes the classification robust to a rasteriser that lands a pixel centre a
 * little off a texel centre.
 */
private fun fieldAtlas(): DecodedImage {
    val alphas = intArrayOf(
        0, OUTSIDE_ALPHA, HALO_ONLY_ALPHA, INSIDE_ALPHA, INSIDE_ALPHA, INSIDE_ALPHA, INSIDE_ALPHA, INSIDE_ALPHA,
    )
    val rgba = ByteArray(alphas.size * RGBA_CHANNELS)
    alphas.forEachIndexed { texel, alpha ->
        val at = texel * RGBA_CHANNELS
        rgba[at] = -1
        rgba[at + 1] = -1
        rgba[at + 2] = -1
        rgba[at + 3] = alpha.toByte()
    }
    return DecodedImage(width = alphas.size, height = 1, rgba = rgba)
}

private fun assertPixel(row: ByteArray, at: Int, expected: IntArray, why: String) {
    val actual = pixel(row, at)
    val agrees = expected.indices.all { abs(actual[it] - expected[it]) <= CHANNEL_TOLERANCE }
    assertTrue(
        agrees,
        "$why: pixel $at read ${actual.toList()}, expected ${expected.toList()}",
    )
}

private fun readRow(binding: GlBinding, glRow: Int): ByteArray {
    val row = ByteArray(LABEL_READBACK_PIXELS * RGBA_CHANNELS)
    binding.readPixels(0, glRow, LABEL_READBACK_PIXELS, 1, GL_RGBA, GL_UNSIGNED_BYTE, row)
    return row
}

private fun describe(row: ByteArray): String =
    (0 until LABEL_READBACK_PIXELS).joinToString { pixel(row, it).toList().toString() }

private fun pixel(row: ByteArray, at: Int): IntArray =
    IntArray(RGBA_CHANNELS) { channel -> row[at * RGBA_CHANNELS + channel].toInt() and 0xFF }

/** [seed] must be a hex character: [ResourceKey] requires a lowercase SHA-256 digest. */
private fun atlasKey(seed: String = "e"): ResourceKey =
    ResourceKey(ResourceKind.EXTERNAL, seed.repeat(64), ResourceClass.BASEMAP_GLYPH_RANGE)

private class QuadPaint(val fill: IntArray, val halo: IntArray)

/**
 * Three quads, six colours, no two of them equal -- asserted rather than assumed, in the suite
 * itself. A fixture that gave two quads the same halo, or gave one quad the same colour for fill and
 * halo, would pass with half the pipeline deleted.
 */
private val QUAD_PAINTS: List<QuadPaint> = listOf(
    QuadPaint(fill = intArrayOf(255, 0, 0, 255), halo = intArrayOf(0, 0, 255, 255)),
    QuadPaint(fill = intArrayOf(255, 0, 255, 255), halo = intArrayOf(0, 255, 0, 255)),
    QuadPaint(fill = intArrayOf(0, 255, 255, 255), halo = intArrayOf(255, 255, 0, 255)),
)

private val TRANSPARENT: IntArray = intArrayOf(0, 0, 0, 0)

internal const val LABEL_READBACK_PIXELS: Int = 24

private const val ATLAS_TEXELS: Int = 8
private const val RGBA_CHANNELS: Int = 4

/**
 * The quads' vertical band, in y-down screen pixels, and the two framebuffer rows read out of it.
 * Screen rows 2..10 are framebuffer rows 14..22 once the flip is applied, and rows 2..10 without it,
 * so [COVERED_GL_ROW] falls inside the band under the correct convention and [UNCOVERED_GL_ROW]
 * falls inside it under the wrong one.
 */
private const val QUAD_TOP_SCREEN_Y: Float = 2.0f
private const val QUAD_BOTTOM_SCREEN_Y: Float = 10.0f
private const val COVERED_GL_ROW: Int = 18
private const val UNCOVERED_GL_ROW: Int = 4
private const val CHANNEL_TOLERANCE: Int = 2

/**
 * A scale of 4 and a halo 8 screen pixels wide put the halo edge at `0.75 - (8 / 4) / 8 = 0.5`,
 * a quarter of the field below the fill edge -- deliberately wide, so the two smoothstep bands
 * (`0.474..0.526` and `0.724..0.776`) are separated by a gap no rasteriser's sub-texel precision can
 * close.
 */
private const val GLYPH_SCALE: Float = 4.0f
private const val HALO_WIDTH_PIXELS: Float = 8.0f

/** `0.2` of the field: below the halo band by a quarter of the field's range. */
private const val OUTSIDE_ALPHA: Int = 51

/** `0.624`: above the halo band and below the fill band, by about a tenth of the field either way. */
private const val HALO_ONLY_ALPHA: Int = 159

/** Saturated, so the fill covers completely and its own opaque colour is the whole answer. */
private const val INSIDE_ALPHA: Int = 255
