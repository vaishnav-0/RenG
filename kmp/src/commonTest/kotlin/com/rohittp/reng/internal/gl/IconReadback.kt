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
 * E-labels task 22's real-driver half: three icon quads drawn through the production pipeline in
 * **one** `glDrawElements`, read back, and classified texel by texel.
 *
 * **The fixture's whole design is one question: is this pass sampling coverage or thresholding a
 * field?** A sprite atlas whose alpha is uniformly 255 cannot answer it -- coverage and an
 * iso-threshold both draw the whole cell, so such a fixture passes with the label program
 * substituted wholesale for this one. [SOFT_EDGE_ALPHA] and [FAINT_EDGE_ALPHA] are therefore
 * genuinely partial: `0.502` and `0.251` of the byte range, both **below** the `0.75` the glyph
 * generator puts an outline at by a margin no smoothing width can close. Under coverage they draw at
 * half and a quarter; under the label program's threshold they draw nothing at all, and the two
 * readings are separated by 128 and 64 units of every channel they touch.
 *
 * **The tint trap, closed in both directions.** `icon-color` applies to an `sdf` entry and to
 * nothing else. One fixture cannot show that: an `sdf`-only frame passes with the flag hardcoded
 * `true`, an artwork-only frame passes with it hardcoded `false`. So quads 0 and 1 sample the *same*
 * atlas texels and differ only in the flag, and the atlas's own artwork colour ([ARTWORK_FAR]) is
 * disjoint from the tint ([TINT]) in every channel -- a tinted artwork sprite and an untinted `sdf`
 * one each paint a colour the correct answer cannot produce.
 *
 * **And the opacity trap.** Quad 2 is quad 0 at half opacity. `icon-opacity` -- and therefore the
 * label fade, which multiplies the same field -- travels in the tint's alpha slot, which the artwork
 * path reads as a bare scalar; folding it into the tint's RGB instead would leave every non-`sdf`
 * sprite drawing at full strength and would be invisible to any fixture that never varies it.
 *
 * **What this does not claim.** Not legibility, not placement, not draw order, and not that an icon
 * lands where a style says -- `runLabelIntegrationReadbackSuite`'s icon case is what runs a real
 * style through the public API. One atlas, one batch, three quads, and the arithmetic between a
 * coverage channel and a colour.
 *
 * The driver is measured before it is trusted, exactly as `runLabelReadbackSuite` measures it: an
 * icon quad is the same shape and the same clip `w` as a glyph quad, so the same probe answers for
 * both.
 */
internal fun runIconReadbackSuite(binding: GlBinding) {
    val profile = (adoptRenderContext(binding) as? RenderContextAdoption.Adopted)?.profile
        ?: throw AssertionError("the fixture context must satisfy the ES 3.0 requirement")
    val cache = GlProgramCache()
    val pipeline = (
        createIconPipeline(binding, profile.dialect, cache, ResourceKeyDeriver())
            as? IconPipelineResult.Created
        )?.pipeline ?: throw AssertionError("the icon pipeline must link on this context")

    val names = IntArray(1)
    binding.genTextures(1, names)
    val targetTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, targetTexture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, ICON_READBACK_PIXELS, ICON_READBACK_PIXELS)
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

    val rasterisation = measureGlyphQuadRasterisation(
        binding,
        profile.dialect,
        targetFramebuffer,
        ICON_READBACK_PIXELS,
        iconReadbackFootprints(),
    )
    println(
        "RenG icon readback rasterisation probe: driver=${binding.getString(GL_RENDERER)} " +
            rasterisation.describe(),
    )

    val registry = GlObjectRegistry()
    val atlas = uploadSpriteAtlas(binding, registry, atlasKey(), spriteAtlas())

    binding.viewport(0, 0, ICON_READBACK_PIXELS, ICON_READBACK_PIXELS)
    binding.scissor(0, 0, ICON_READBACK_PIXELS, ICON_READBACK_PIXELS)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.clearColor(0.0f, 0.0f, 0.0f, 0.0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    // Culling and depth testing on, deliberately, and both left for the pass to turn off itself: the
    // icon pass runs straight after phase 4's stickers, which leave the depth test enabled, and a
    // y-down corner order is the back face by default. A pass that inherited this state draws nothing.
    binding.enable(GL_CULL_FACE)
    binding.cullFace(GL_BACK)
    binding.frontFace(GL_CCW)
    binding.enable(GL_DEPTH_TEST)
    binding.bindSampler(0, 0)

    drawIcons(
        binding,
        pipeline,
        IconWorld(
            outputPixelSize = OutputPixelSize(ICON_READBACK_PIXELS, ICON_READBACK_PIXELS),
            batches = listOf(IconBatch(atlasTexture = atlas.handle.name, quads = threeIconQuads())),
        ),
    )

    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    val row = readRow(binding, ICON_COVERED_GL_ROW)
    val uncovered = readRow(binding, ICON_UNCOVERED_GL_ROW)
    println("RenG icon readback covered row: " + describe(row))
    println("RenG icon readback uncovered row: " + describe(uncovered))

    if (rasterisation.isTrustworthy) {
        // ---- quad 0: artwork, full opacity. Coverage times the atlas's own colours. -------------
        assertPixel(row, ARTWORK_QUAD + 0, TRANSPARENT, "artwork texel 0 has no coverage")
        // **The load-bearing samples.** Both are below the glyph outline's iso-value by a wide
        // margin, so an implementation that thresholds instead of sampling paints neither.
        assertPixel(
            row,
            ARTWORK_QUAD + 1,
            intArrayOf(FAINT_EDGE_ALPHA, 0, 0, FAINT_EDGE_ALPHA),
            "artwork texel 1 draws at a quarter coverage rather than not at all",
        )
        assertPixel(
            row,
            ARTWORK_QUAD + 2,
            intArrayOf(SOFT_EDGE_ALPHA, 0, 0, SOFT_EDGE_ALPHA),
            "artwork texel 2 draws at half coverage rather than not at all",
        )
        assertPixel(row, ARTWORK_QUAD + 3, ARTWORK_NEAR, "artwork texel 3 is the atlas's near colour")
        // The atlas changes colour half way across, so this is what says the RGB is *read* rather
        // than synthesised from alpha -- which is what the glyph atlas's forced-white RGB would be.
        for (texel in 4 until ATLAS_TEXELS) {
            assertPixel(row, ARTWORK_QUAD + texel, ARTWORK_FAR, "artwork texel $texel is the far colour")
        }

        // ---- quad 1: the same texels, flagged `sdf`. The tint replaces the artwork. -------------
        assertPixel(row, SDF_QUAD + 0, TRANSPARENT, "an sdf texel with no coverage still draws nothing")
        assertPixel(
            row,
            SDF_QUAD + 2,
            intArrayOf(0, 0, SOFT_EDGE_ALPHA, SOFT_EDGE_ALPHA),
            "the tint is painted through the coverage rather than thresholded by it",
        )
        // The discriminating pair: one texel, two quads, two colours that cannot be confused. A
        // hardcoded `sdf = true` paints the artwork quad blue here; a hardcoded `false` paints this
        // one green.
        assertPixel(row, SDF_QUAD + 4, TINT_OPAQUE, "an sdf sprite takes icon-color, not its artwork")
        assertPixel(row, ARTWORK_QUAD + 4, ARTWORK_FAR, "and artwork beside it keeps its own")

        // ---- quad 2: quad 0 at half opacity. ----------------------------------------------------
        assertPixel(
            row,
            FADED_QUAD + 4,
            intArrayOf(0, HALF_OF_FULL, 0, HALF_OF_FULL),
            "icon-opacity attenuates artwork, which is what the label fade rides on",
        )
    } else {
        println(
            "RenG icon readback SKIPPED [the covered row's own pixels] " + rasterisation.describe() +
                ": this driver does not rasterise the icon pass's own quads, so a missing icon " +
                "pixel here would measure the driver rather than RenG. Every other check still ran.",
        )
    }

    // The vertical half. Screen space is y-down and the framebuffer is y-up, so a vertex shader that
    // forgot the flip puts every icon exactly as far from the wrong edge as it should have been from
    // the right one. The band the quads occupy and the band this row sits in are each other's mirror
    // image about the frame's centre, so an unflipped pass fails both rather than either alone.
    for (at in 0 until ICON_READBACK_PIXELS) {
        assertPixel(uncovered, at, TRANSPARENT, "no icon quad covers GL row $ICON_UNCOVERED_GL_ROW")
    }

    // Stated as its own claim rather than left implicit above: the three colours the classification
    // turns on are pairwise distinct, so no assertion can be satisfied by the wrong one of them.
    assertEquals(
        3,
        listOf(ARTWORK_NEAR.toList(), ARTWORK_FAR.toList(), TINT_OPAQUE.toList()).toSet().size,
        "the fixture's three classifying colours must be distinct or it proves nothing",
    )

    assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding), "the icon draw must provoke no GL error")

    registry.liveKeys().forEach { key -> deleteGlObjects(binding, registry.handles(key)) }
    deleteIconPipeline(binding, cache, pipeline)
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
 * Corner order is [ResolvedIconQuad]'s: top-left, top-right, bottom-right, bottom-left in y-down
 * screen space, paired corner for corner with the atlas rectangle. Vertically they occupy a band near
 * the top of the frame rather than the whole of it, which is what lets the frame say anything about
 * the y axis at all.
 */
private fun threeIconQuads(): List<ResolvedIconQuad> = listOf(
    iconQuad(quad = 0, colour = TINT_RGBA, opacity = 1.0f, tintable = false),
    iconQuad(quad = 1, colour = TINT_RGBA, opacity = 1.0f, tintable = true),
    iconQuad(quad = 2, colour = TINT_RGBA, opacity = 0.5f, tintable = false),
)

private fun iconQuad(quad: Int, colour: FloatArray, opacity: Float, tintable: Boolean): ResolvedIconQuad {
    val left = (quad * ATLAS_TEXELS).toFloat()
    val right = left + ATLAS_TEXELS.toFloat()
    return ResolvedIconQuad(
        cornersXy = floatArrayOf(
            left, QUAD_TOP_SCREEN_Y, right, QUAD_TOP_SCREEN_Y, right, QUAD_BOTTOM_SCREEN_Y,
            left, QUAD_BOTTOM_SCREEN_Y,
        ),
        cornersUv = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f),
        paint = ResolvedIconPaint(
            colour = colour,
            haloColour = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f),
            opacity = opacity,
            // Non-zero, and nothing may draw them: a sprite's coverage carries no outline to dilate,
            // and Rentile paints no icon halo either. A halo that had reached the shader would widen
            // every quad's ink past the analytic rectangle the probe above measures.
            haloWidthPixels = 3.0f,
            haloBlurPixels = 1.0f,
            tintable = tintable,
        ),
    )
}

/** The same three quads as analytic rectangles for task 18's probe, derived from the same constants. */
private fun iconReadbackFootprints(): List<GlyphQuadFootprint> = (0 until 3).map { quad ->
    GlyphQuadFootprint(
        name = "icon cell $quad",
        left = (quad * ATLAS_TEXELS).toFloat(),
        top = QUAD_TOP_SCREEN_Y,
        right = ((quad + 1) * ATLAS_TEXELS).toFloat(),
        bottom = QUAD_BOTTOM_SCREEN_Y,
    )
}

/**
 * A one-row sprite atlas: **coverage in alpha, artwork in RGB**, and the two varying independently.
 *
 * The alphas are deliberately asymmetric, so a horizontally mirrored atlas coordinate produces a
 * different classification per pixel rather than the same one read backwards; and the colour changes
 * half way across at a texel where the alpha does not, so "the RGB is read" and "the alpha is read"
 * are two separate observations rather than one.
 */
private fun spriteAtlas(): DecodedImage {
    val texels = listOf(
        intArrayOf(255, 255, 255, 0),
        intArrayOf(ARTWORK_NEAR[0], ARTWORK_NEAR[1], ARTWORK_NEAR[2], FAINT_EDGE_ALPHA),
        intArrayOf(ARTWORK_NEAR[0], ARTWORK_NEAR[1], ARTWORK_NEAR[2], SOFT_EDGE_ALPHA),
        intArrayOf(ARTWORK_NEAR[0], ARTWORK_NEAR[1], ARTWORK_NEAR[2], 255),
        intArrayOf(ARTWORK_FAR[0], ARTWORK_FAR[1], ARTWORK_FAR[2], 255),
        intArrayOf(ARTWORK_FAR[0], ARTWORK_FAR[1], ARTWORK_FAR[2], 255),
        intArrayOf(ARTWORK_FAR[0], ARTWORK_FAR[1], ARTWORK_FAR[2], 255),
        intArrayOf(ARTWORK_FAR[0], ARTWORK_FAR[1], ARTWORK_FAR[2], 255),
    )
    val rgba = ByteArray(texels.size * RGBA_CHANNELS)
    texels.forEachIndexed { texel, colour ->
        val at = texel * RGBA_CHANNELS
        for (channel in 0 until RGBA_CHANNELS) rgba[at + channel] = colour[channel].toByte()
    }
    return DecodedImage(width = texels.size, height = 1, rgba = rgba)
}

private fun assertPixel(row: ByteArray, at: Int, expected: IntArray, why: String) {
    val actual = pixel(row, at)
    val agrees = expected.indices.all { abs(actual[it] - expected[it]) <= CHANNEL_TOLERANCE }
    assertTrue(agrees, "$why: pixel $at read ${actual.toList()}, expected ${expected.toList()}")
}

private fun readRow(binding: GlBinding, glRow: Int): ByteArray {
    val row = ByteArray(ICON_READBACK_PIXELS * RGBA_CHANNELS)
    binding.readPixels(0, glRow, ICON_READBACK_PIXELS, 1, GL_RGBA, GL_UNSIGNED_BYTE, row)
    return row
}

private fun describe(row: ByteArray): String =
    (0 until ICON_READBACK_PIXELS).joinToString { pixel(row, it).toList().toString() }

private fun pixel(row: ByteArray, at: Int): IntArray =
    IntArray(RGBA_CHANNELS) { channel -> row[at * RGBA_CHANNELS + channel].toInt() and 0xFF }

/** [seed] must be a hex character: [ResourceKey] requires a lowercase SHA-256 digest. */
private fun atlasKey(seed: String = "c"): ResourceKey =
    ResourceKey(ResourceKind.EXTERNAL, seed.repeat(64), ResourceClass.BASEMAP_SPRITE_IMAGE)

internal const val ICON_READBACK_PIXELS: Int = 24

private const val ATLAS_TEXELS: Int = 8
private const val RGBA_CHANNELS: Int = 4

private const val ARTWORK_QUAD: Int = 0
private const val SDF_QUAD: Int = ATLAS_TEXELS
private const val FADED_QUAD: Int = 2 * ATLAS_TEXELS

/**
 * The quads' vertical band, in y-down screen pixels, and the two framebuffer rows read out of it.
 * Screen rows 2..10 are framebuffer rows 13..21 once the flip is applied, and rows 2..10 without it.
 */
private const val QUAD_TOP_SCREEN_Y: Float = 2.0f
private const val QUAD_BOTTOM_SCREEN_Y: Float = 10.0f
private const val ICON_COVERED_GL_ROW: Int = 18
private const val ICON_UNCOVERED_GL_ROW: Int = 4
private const val CHANNEL_TOLERANCE: Int = 2

/**
 * `0.502` of the byte range: a genuinely partial coverage, and a third of the field **below** the
 * `0.75` [LABEL_FILL_EDGE_DISTANCE] puts a glyph outline at. This one number is what separates
 * "samples coverage" from "thresholds a distance field": under the second reading it draws nothing.
 */
private const val SOFT_EDGE_ALPHA: Int = 128

/** `0.251`, the same statement at a second value, so one arithmetic slip cannot pass by coincidence. */
private const val FAINT_EDGE_ALPHA: Int = 64

/** 255 at half opacity, rounded as the blend rounds it. */
private const val HALF_OF_FULL: Int = 128

/** The atlas's own artwork, near half. Distinct from [ARTWORK_FAR] and from [TINT_OPAQUE]. */
private val ARTWORK_NEAR: IntArray = intArrayOf(255, 0, 0, 255)

/** And far half, so a constant-colour atlas cannot satisfy the classification. */
private val ARTWORK_FAR: IntArray = intArrayOf(0, 255, 0, 255)

/** `icon-color`, in no channel either artwork colour uses. */
private val TINT: IntArray = intArrayOf(0, 0, 255)

private val TINT_OPAQUE: IntArray = intArrayOf(TINT[0], TINT[1], TINT[2], 255)

private val TINT_RGBA: FloatArray =
    floatArrayOf(TINT[0] / 255.0f, TINT[1] / 255.0f, TINT[2] / 255.0f, 1.0f)

private val TRANSPARENT: IntArray = intArrayOf(0, 0, 0, 0)
