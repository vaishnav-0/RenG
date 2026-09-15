package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackdropPipelineTest {
    @Test
    fun theRosterAddsBackdropAtWireValueTenWithoutRenumberingAnything() {
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
        assertEquals(9, InternalPipelineRole.TERRAIN_GLOBE_GROUND.wireValue)
        assertEquals(10, InternalPipelineRole.BACKDROP.wireValue)
    }

    @Test
    fun theBackdropSourcesAreAcceptedShaderProfileSources() {
        assertTrue(BACKDROP_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(BACKDROP_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(BACKDROP_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(BACKDROP_FRAGMENT_SOURCE) != null)
    }

    /**
     * One attribute, not two: the backdrop's texture coordinates are derived in the vertex stage from
     * the clip-space position, because a quad that is always the whole frame has nothing to vary.
     * Thirty-two bytes is four vertices of two floats.
     */
    @Test
    fun creationBuildsAProgramAFullFrameQuadAndExactlyOneAttribute() {
        val binding = newBinding()
        val created = createBackdropPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as BackdropPipelineResult.Created

        assertTrue(created.pipeline.program > 0)
        assertTrue(created.pipeline.vertexArray > 0)
        assertEquals(1, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(1, binding.log.count { it.startsWith("vertexAttribPointer") })
        assertTrue(binding.log.any { it.startsWith("bufferData(0x8892,32") })
        assertEquals(REPEAT_LOCATION, created.pipeline.repeatUniformLocation)
        assertEquals(TEXTURE_LOCATION, created.pipeline.textureUniformLocation)
    }

    /**
     * ADR 0068. This is the only texture RenG uploads that wraps, and it is the reason the backdrop
     * has a sampler of its own rather than borrowing the sticker pass's -- every other consumer image
     * clamps. The minification filter names no mipmap level on purpose: under `GL_REPEAT` a mipmap of
     * a tiling pattern blends its own opposite edge across the seam.
     */
    @Test
    fun theSamplerRepeatsOnBothAxesAndAsksForNoMipmapChain() {
        assertEquals(GL_REPEAT, BACKDROP_SAMPLER_STATE.wrapS)
        assertEquals(GL_REPEAT, BACKDROP_SAMPLER_STATE.wrapT)
        assertEquals(GL_LINEAR, BACKDROP_SAMPLER_STATE.minFilter)
        assertEquals(GL_LINEAR, BACKDROP_SAMPLER_STATE.magFilter)
    }

    /**
     * The pass turns depth testing **and** depth writing off, which is what makes "behind everything"
     * true without the backdrop having a depth of its own for later passes to compare against. It also
     * establishes its own blend state rather than inheriting whatever was bound, on the same terms
     * `beginStickerPass` does.
     */
    @Test
    fun drawingDisablesDepthAndUploadsItsRepeatAndTexture() {
        val binding = newBinding()
        val pipeline = (createBackdropPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as BackdropPipelineResult.Created).pipeline
        binding.log.clear()

        drawBackdrop(binding, pipeline, ResolvedBackdrop(texture = 77, repeatAcross = 4.5f, repeatDown = 8.0f))

        assertTrue(binding.log.any { it == "disable(0xB71)" }, binding.log.toString())
        assertTrue(binding.log.any { it == "depthMask(false)" }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("uniform2f($REPEAT_LOCATION,4.5,8.0") }, binding.log.toString())
        assertTrue(binding.log.any { it == "bindTexture(0xDE1,77)" }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("drawArrays(0x5,0,4)") }, binding.log.toString())
    }

    /**
     * ADR 0068's `tileSizeLogicalPixels` is a repeat *distance*, so the repeat count divides by it.
     * Dividing and multiplying both produce a perfectly valid draw whose only difference is that the
     * pattern is three orders of magnitude too small, which no GL call log and no shader test can
     * see -- so this states the arithmetic directly.
     */
    @Test
    fun theBackdropRepeatsOnceEveryTileSizeOfLogicalPixels() {
        val resolved = resolvedBackdropFor(
            texture = 3,
            outputPixelSize = OutputPixelSize(width = 1080, height = 1920),
            tileSizeLogicalPixels = 256.0,
        )

        assertEquals(3, resolved.texture)
        assertEquals(1080f / 256f, resolved.repeatAcross)
        assertEquals(1920f / 256f, resolved.repeatDown)

        // A repeat as wide as the frame is exactly one tile across, which is the identity that says
        // the units are the frame's own rather than the image's.
        val once = resolvedBackdropFor(3, OutputPixelSize(800, 600), 800.0)
        assertEquals(1.0f, once.repeatAcross)
    }

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        BACKDROP_REPEAT_UNIFORM_NAME to REPEAT_LOCATION,
        BACKDROP_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
    )

    private companion object {
        const val REPEAT_LOCATION: Int = 11
        const val TEXTURE_LOCATION: Int = 12
    }
}
