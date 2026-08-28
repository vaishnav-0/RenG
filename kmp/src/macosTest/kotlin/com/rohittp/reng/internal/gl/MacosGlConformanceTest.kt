package com.rohittp.reng.internal.gl

import com.rohittp.reng.BASEMAP_READBACK_PIXELS
import com.rohittp.reng.MODEL_READBACK_PIXELS
import com.rohittp.reng.runBasemapReadbackSuite
import com.rohittp.reng.runModelReadbackSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacosGlConformanceTest {
    @Test fun theSuitePassesOnARealAppleCoreProfileContext() {
        val fixture = CglCoreProfileContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)

            val report = runGlConformanceSuite(binding, fixture.probe, ShaderDialect.DESKTOP)
            assertEquals(ShaderDialect.DESKTOP, report.dialect)
            assertEquals(8, report.checks.size)
            assertTrue(report.rendererName.isNotBlank())
            // A hosted runner reports "Apple Software Renderer"; a developer's machine reports
            // "4.1 Metal - 90.5". Cycle E must key golden baselines by this string and the dialect.
            println("RenG conformance renderer: ${report.rendererName} / ${report.versionText}")
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle E's gate, on a real Apple core-profile context — the desktop-dialect half of the pair
     * whose GLES half runs in `LinuxGlConformanceTest`. See `runBasemapReadbackSuite` for exactly
     * what this catches and what it does not.
     */
    @Test fun theBasemapReadbackSuitePassesOnARealAppleCoreProfileContext() {
        runReadbackOn(MacosGlRenderer.DEFAULT)
    }

    /**
     * Cycle F-2's gate, on the same real Apple core-profile context: a GLB drawn through the public
     * API, read back, and asserted in pixels. Every other model assertion in the tree is a call log
     * against a fake, and the basemap cycle is why that is not enough — see `runModelReadbackSuite`
     * for exactly what this catches and, more importantly, what it does not.
     */
    @Test fun theModelReadbackSuitePassesOnARealAppleCoreProfileContext() {
        val fixture = CglCoreProfileContext.createOrNull(MacosGlRenderer.DEFAULT)
            ?: throw AssertionError("the default Apple renderer must be available on a developer machine")
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            binding.scissor(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            runModelReadbackSuite(binding, fixture.probe, ShaderDialect.DESKTOP)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * The same gate on Apple's CPU rasteriser, which is the *only* driver a hosted GitHub macOS
     * runner has.
     *
     * Without this a developer's whole macOS signal comes from one GPU. That is how `0.3.0` failed
     * closed: the readback suite passed on an M3 Max and failed on the runner, and nothing on a
     * developer's machine could reproduce it. The software renderer is a genuinely different
     * rasteriser — it reports `GL_SUBPIXEL_BITS = 10` where the Metal path reports 4 — so it is the
     * cheapest honest answer to "does this assertion survive a driver we do not own".
     *
     * It skips rather than fails when the renderer is unavailable: this is a portability probe, not
     * a contract about which renderers a Mac must expose.
     */
    @Test fun theBasemapReadbackSuitePassesOnAppleSoftwareRenderer() {
        runReadbackOn(MacosGlRenderer.SOFTWARE)
    }

    /**
     * Cycle E-labels task 7's gate, on both Apple rasterisers this machine can offer.
     *
     * The glyph atlas is the one texture RenG uploads that neither `defaultSamplerStateFor` answer
     * fits, and the half of that choice with a visible consequence — linear filtering over the
     * `TextureContent.DATA` default's `GL_NEAREST` — is invisible to every assertion made against a
     * fake. See `runGlyphAtlasSamplerReadback` for what each of its three draws discriminates.
     *
     * Run twice deliberately: filtering is not rasterisation, so the software renderer is expected
     * to agree with the GPU here, and a disagreement is exactly the thing worth finding early. It
     * skips when a renderer is unavailable, like the basemap readback above.
     */
    @Test fun theGlyphAtlasSamplerReadbackPassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG glyph atlas readback: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                println("RenG glyph atlas readback driver: ${binding.getString(GL_RENDERER)}")
                binding.viewport(0, 0, GLYPH_ATLAS_READBACK_PIXELS, GLYPH_ATLAS_READBACK_PIXELS)
                binding.scissor(0, 0, GLYPH_ATLAS_READBACK_PIXELS, GLYPH_ATLAS_READBACK_PIXELS)
                runGlyphAtlasSamplerReadback(binding)
            } finally {
                fixture.destroy()
            }
        }
    }

    /**
     * Cycle E-labels task 8's gate, on both Apple rasterisers this machine can offer.
     *
     * Three glyph quads, six colours and one `glDrawElements`. The call-log suite pins that it *is*
     * one draw; nothing in a fake can pin that the shader links, that the five interleaved attribute
     * offsets line up with what the linked program reads, that a y-down screen pixel reaches clip
     * space the right way up, or that the halo band admits a field value the fill band does not.
     * See `runLabelReadbackSuite` for exactly what each pixel discriminates.
     *
     * Run on both rasterisers because the quads here are small -- eight pixels across -- and the
     * large-quad defect that cost `0.3.0` a publication was a rasteriser property rather than a RenG
     * one. Task 18 measures the label pass's own footprints against that probe; this pair is the
     * cheap early signal, and it skips rather than fails when a renderer is unavailable.
     */
    @Test fun theLabelReadbackSuitePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG label readback: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                println("RenG label readback driver: ${binding.getString(GL_RENDERER)}")
                binding.viewport(0, 0, LABEL_READBACK_PIXELS, LABEL_READBACK_PIXELS)
                binding.scissor(0, 0, LABEL_READBACK_PIXELS, LABEL_READBACK_PIXELS)
                runLabelReadbackSuite(binding)
            } finally {
                fixture.destroy()
            }
        }
    }

    private fun runReadbackOn(renderer: MacosGlRenderer) {
        val fixture = CglCoreProfileContext.createOrNull(renderer)
        if (fixture == null) {
            println("RenG basemap readback: skipped, $renderer is unavailable on this machine")
            return
        }
        try {
            val binding = bindOrFail()
            println(
                "RenG basemap readback driver: requested=$renderer " +
                    "GL_RENDERER=${binding.getString(GL_RENDERER)} " +
                    "GL_VERSION=${binding.getString(GL_VERSION)}",
            )
            binding.viewport(0, 0, BASEMAP_READBACK_PIXELS, BASEMAP_READBACK_PIXELS)
            binding.scissor(0, 0, BASEMAP_READBACK_PIXELS, BASEMAP_READBACK_PIXELS)
            runBasemapReadbackSuite(binding, fixture.probe, ShaderDialect.DESKTOP)
        } finally {
            fixture.destroy()
        }
    }

    private fun bindOrFail(): GlBinding = when (val result = openPlatformGlBinding()) {
        is GlBindingResult.Bound -> result.binding
        is GlBindingResult.Unsupported -> throw AssertionError("platform.OpenGL3 must bind")
    }
}
