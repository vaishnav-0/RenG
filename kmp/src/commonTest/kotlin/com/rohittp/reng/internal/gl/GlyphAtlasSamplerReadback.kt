package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.DecodedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle E-labels task 7's real-driver half: proof that the sampler [uploadGlyphAtlas] sets has the
 * consequence it is set for, measured on whatever rasteriser the calling fixture holds rather than
 * read back out of a fake's call log.
 *
 * **Why a readback at all**, when `GlyphAtlasUploadTest` already pins all four `glTexParameteri`
 * calls: a call log proves RenG asked for linear filtering, not that a texture uploaded this way is
 * sampled linearly. Nothing in a fake can tell a correct `glTexParameteri` from one issued against
 * the wrong bound texture, at the wrong time, or undone by a sampler object bound over it. This
 * samples between two atlas texels, where linear and nearest genuinely disagree, and reads the
 * answer off the framebuffer.
 *
 * **Three independent discriminations, one per sampler field that matters**, because the atlas
 * sampler sets `GL_LINEAR` for *both* filters and a single draw cannot say which one the driver
 * consulted:
 * - Magnification: a two-texel atlas across a 16-pixel viewport, so 8 pixels per texel. Only
 *   `GL_TEXTURE_MAG_FILTER` is consulted, and mutating it alone fails this draw.
 * - Minification: a four-texel atlas across a 2-pixel viewport, so 2 texels per pixel. Only
 *   `GL_TEXTURE_MIN_FILTER` is consulted, and mutating it alone fails this draw.
 * - Wrapping: the outermost pixels of the magnified draw sample beyond the outermost texel centres,
 *   where `GL_CLAMP_TO_EDGE` repeats the edge texel and `GL_REPEAT` blends in the opposite one --
 *   which in a packed atlas is an unrelated glyph. Mutating the wrap modes alone fails those.
 *
 * The atlas fixtures are shaped like the thing being uploaded: RGB forced opaque white, the field in
 * alpha. Every assertion below reads alpha for that reason, and every band excludes both texel
 * values by a wide margin rather than pinning an exact number, because subtexel precision is a
 * driver property (GL guarantees a minimum, not an exact one) while "it interpolated at all" is not.
 *
 * Legibility of real glyphs is not claimed here and is unverified until Cycle J; this is one
 * sampler, measured.
 */
internal fun runGlyphAtlasSamplerReadback(binding: GlBinding) {
    val profile = (adoptRenderContext(binding) as? RenderContextAdoption.Adopted)?.profile
        ?: throw AssertionError("the fixture context must satisfy the ES 3.0 requirement")
    val cache = GlProgramCache()
    val composite = (
        createCompositePipeline(binding, profile.dialect, cache, ResourceKeyDeriver())
            as? CompositePipelineResult.Created
        )?.pipeline ?: throw AssertionError("the composite pipeline must link on this context")

    val names = IntArray(1)
    binding.genTextures(1, names)
    val targetTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, targetTexture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8, GLYPH_ATLAS_READBACK_PIXELS, GLYPH_ATLAS_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val targetFramebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0,
    )
    binding.drawBuffers(1, intArrayOf(GL_COLOR_ATTACHMENT0))
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the readback target must be framebuffer-complete",
    )
    GlErrorQueue.drainOnEntry(binding)

    val registry = GlObjectRegistry()
    val magnified = sampleAtlasRow(
        binding = binding,
        registry = registry,
        composite = composite,
        targetFramebuffer = targetFramebuffer,
        key = atlasKey("c"),
        image = stepAtlas(TWO_TEXEL_STEP),
        viewportPixels = GLYPH_ATLAS_READBACK_PIXELS,
    )
    val minified = sampleAtlasRow(
        binding = binding,
        registry = registry,
        composite = composite,
        targetFramebuffer = targetFramebuffer,
        key = atlasKey("d"),
        image = stepAtlas(FOUR_TEXEL_ALTERNATION),
        viewportPixels = MINIFYING_VIEWPORT_PIXELS,
    )
    println(
        "RenG glyph atlas sampler readback: magnified=${magnified.toList()} minified=${minified.toList()}",
    )

    // Magnification. Texel centres sit at u = 0.25 and u = 0.75, so pixel 6 (u = 0.40625) is 31.25%
    // of the way from the transparent texel to the opaque one and pixel 9 (u = 0.59375) is 68.75%.
    // GL_NEAREST would answer 0 and 255 exactly; linear answers roughly 80 and 175.
    assertTrue(
        magnified[6] in LOW_BLEND_BAND,
        "pixel 6 read ${magnified[6]}: GL_NEAREST magnification answers 0 here, linear answers about 80",
    )
    assertTrue(
        magnified[9] in HIGH_BLEND_BAND,
        "pixel 9 read ${magnified[9]}: GL_NEAREST magnification answers 255 here, linear answers about 175",
    )
    assertTrue(magnified[6] < magnified[9], "and the blend runs from the transparent texel to the opaque one")

    // Wrapping. Pixels 0 and 15 sample outside both texel centres. GL_CLAMP_TO_EDGE repeats the
    // edge texel, so they read the texel values exactly; GL_REPEAT would blend in the far edge and
    // answer roughly 112 and 143 instead.
    assertTrue(
        magnified[0] <= EDGE_TOLERANCE,
        "pixel 0 read ${magnified[0]}: GL_REPEAT blends the opposite edge in here, GL_CLAMP_TO_EDGE does not",
    )
    assertTrue(
        magnified[GLYPH_ATLAS_READBACK_PIXELS - 1] >= OPAQUE - EDGE_TOLERANCE,
        "pixel 15 read ${magnified[GLYPH_ATLAS_READBACK_PIXELS - 1]}: the same, at the other edge",
    )

    // Minification. Two texels per pixel, and each pixel centre lands exactly on the boundary
    // between an empty texel and a full one, so linear answers the average of the two while
    // GL_NEAREST answers one of them outright.
    minified.forEachIndexed { pixel, alpha ->
        assertTrue(
            alpha in MIDPOINT_BAND,
            "minified pixel $pixel read $alpha: GL_NEAREST answers 0 or 255 here, linear answers about 128",
        )
    }

    assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding), "the readback must provoke no GL error")

    registry.liveKeys().forEach { key -> deleteGlObjects(binding, registry.handles(key)) }
    deleteCompositePipeline(binding, cache, composite)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    binding.deleteFramebuffers(1, intArrayOf(targetFramebuffer))
    binding.deleteTextures(1, intArrayOf(targetTexture))
    GlErrorQueue.drainOnEntry(binding)
}

/**
 * Uploads [image] through the production path and draws it over a [viewportPixels]-square region of
 * [targetFramebuffer] with the composite program, whose fragment shader is one `texture()` call and
 * nothing else -- so what comes back is the sampler's answer rather than any pipeline's arithmetic.
 * Returns the alpha channel of the bottom row.
 */
@Suppress("LongParameterList")
private fun sampleAtlasRow(
    binding: GlBinding,
    registry: GlObjectRegistry,
    composite: CompositePipeline,
    targetFramebuffer: Int,
    key: ResourceKey,
    image: DecodedImage,
    viewportPixels: Int,
): IntArray {
    val atlas = uploadGlyphAtlas(binding, registry, key, image)

    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.viewport(0, 0, viewportPixels, viewportPixels)
    binding.disable(GL_SCISSOR_TEST)
    binding.disable(GL_DEPTH_TEST)
    binding.disable(GL_CULL_FACE)
    binding.disable(GL_BLEND)
    binding.colorMask(true, true, true, true)
    binding.clearColor(0.0f, 0.0f, 0.0f, 0.0f)
    binding.clear(GL_COLOR_BUFFER_BIT)

    binding.activeTexture(GL_TEXTURE0)
    binding.bindTexture(GL_TEXTURE_2D, atlas.handle.name)
    // A sampler object bound over the texture would override every parameter this upload set, which
    // is precisely the failure a call-log assertion cannot see. Unbind one so the texture's own
    // sampler state is what the draw uses.
    binding.bindSampler(0, 0)
    binding.useProgram(composite.program)
    if (composite.sourceUniformLocation >= 0) binding.uniform1i(composite.sourceUniformLocation, 0)
    binding.bindVertexArray(composite.vertexArray)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)

    val row = ByteArray(viewportPixels * RGBA_CHANNELS)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.readPixels(0, 0, viewportPixels, 1, GL_RGBA, GL_UNSIGNED_BYTE, row)
    return IntArray(viewportPixels) { pixel -> row[pixel * RGBA_CHANNELS + 3].toInt() and 0xFF }
}

/**
 * A one-row atlas whose RGB is opaque white and whose alpha is [alphas], the shape of the atlas
 * Rentile hands over. One row, so vertical filtering is a no-op and every reading below is about
 * the horizontal axis alone.
 */
private fun stepAtlas(alphas: IntArray): DecodedImage {
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

/** [seed] must be a hex character: [ResourceKey] requires a lowercase SHA-256 digest. */
private fun atlasKey(seed: String): ResourceKey =
    ResourceKey(ResourceKind.EXTERNAL, seed.repeat(64), ResourceClass.BASEMAP_GLYPH_RANGE)

internal const val GLYPH_ATLAS_READBACK_PIXELS: Int = 16

private const val MINIFYING_VIEWPORT_PIXELS: Int = 2
private const val RGBA_CHANNELS: Int = 4
private const val OPAQUE: Int = 255
private const val EDGE_TOLERANCE: Int = 4

private val TWO_TEXEL_STEP: IntArray = intArrayOf(0, 255)
private val FOUR_TEXEL_ALTERNATION: IntArray = intArrayOf(0, 255, 0, 255)

/** Analytic answers are about 80, 175 and 128; the bands are wide enough to be about interpolation. */
private val LOW_BLEND_BAND: IntRange = 50..110
private val HIGH_BLEND_BAND: IntRange = 145..205
private val MIDPOINT_BAND: IntRange = 60..195
