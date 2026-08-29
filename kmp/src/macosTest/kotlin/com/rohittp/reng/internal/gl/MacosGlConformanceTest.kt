package com.rohittp.reng.internal.gl

import com.rohittp.reng.BASEMAP_READBACK_PIXELS
import com.rohittp.reng.GROUND_CULL_READBACK_PIXELS
import com.rohittp.reng.LABEL_INTEGRATION_PIXELS
import com.rohittp.reng.MODEL_READBACK_PIXELS
import com.rohittp.reng.runBasemapReadbackSuite
import com.rohittp.reng.runGroundCullReadbackSuite
import com.rohittp.reng.runLabelIntegrationReadbackSuite
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
     * one. Task 18's `measureGlyphQuadRasterisation` now opens the suite and measures exactly that:
     * **0** disagreeing pixels on each of these two drivers, and 0 again on the iOS simulator's copy
     * of the software rasteriser, where the ground's own footprints disagree over 3,040. It skips
     * rather than fails when a renderer is unavailable.
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

    /**
     * E-labels task 22's gate, on both Apple rasterisers this machine can offer.
     *
     * Three icon quads over one sprite atlas, and every assertion turns on the distinction the whole
     * pipeline exists for: an atlas texel at half coverage draws at half coverage rather than being
     * thresholded away at the glyph outline's iso-value, `icon-color` reaches an `sdf` sprite and
     * only an `sdf` sprite, and `icon-opacity` attenuates artwork the tint never touches. See
     * `runIconReadbackSuite` for what each texel discriminates and why a saturated fixture proves
     * none of it.
     *
     * Both rasterisers, as the label readback runs on both, and for the same reason: these quads are
     * eight pixels across and the defect that cost `0.3.0` a publication was a large-quad property
     * of the software rasteriser rather than a RenG one.
     */
    @Test fun theIconReadbackSuitePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG icon readback: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                println("RenG icon readback driver: ${binding.getString(GL_RENDERER)}")
                binding.viewport(0, 0, ICON_READBACK_PIXELS, ICON_READBACK_PIXELS)
                binding.scissor(0, 0, ICON_READBACK_PIXELS, ICON_READBACK_PIXELS)
                runIconReadbackSuite(binding)
            } finally {
                fixture.destroy()
            }
        }
    }

    /**
     * E-labels task 20's gate: a `FramePlan` in, drawn label pixels out, through the public API on a
     * real Apple core-profile context.
     *
     * Eleven merged tasks were green while the renderer drew no label at all -- every stage had its
     * own suite and nothing ran them in sequence -- so this is the first assertion in the tree that
     * the label path composes. See `runLabelIntegrationReadbackSuite` for what each case
     * discriminates, and for the two ways a "labels drew" assertion passes for the wrong reason.
     *
     * The default renderer only, unlike the readbacks above -- which is worth knowing precisely,
     * because on a hosted runner the default *is* `Apple Software Renderer`. Every quad here is about
     * ten pixels across, and task 18's `measureGlyphQuadRasterisation` opens the suite by measuring
     * them: 0 disagreeing pixels on `Apple M3 Max`. Where it distrusts the driver, the four cases
     * whose evidence is a drawn label pixel skip out loud and the two that assert an empty frame
     * still run.
     */
    @Test fun theLabelIntegrationReadbackSuitePassesOnARealAppleCoreProfileContext() {
        val fixture = CglCoreProfileContext.createOrNull(MacosGlRenderer.DEFAULT)
            ?: throw AssertionError("the default Apple renderer must be available on a developer machine")
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS)
            binding.scissor(0, 0, LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS)
            runLabelIntegrationReadbackSuite(binding, fixture.probe)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle G task 6's gate (ADR 0038), on both Apple rasterisers this machine can offer: a globe
     * ground pass removes a back-facing patch, a mercator one keeps it whatever the caller left
     * enabled, and no mercator pixel moves in either winding.
     *
     * Both rasterisers in one case rather than two, because the assertion is about RenG's own state
     * changes rather than about a fill rule — the patch is entirely on-screen, so the large-quad
     * defect that splits these two drivers apart in `runBasemapReadbackSuite` cannot reach it.
     */
    @Test fun theGroundCullReadbackSuitePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG ground-cull readback: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                binding.viewport(0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS)
                binding.scissor(0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS)
                runGroundCullReadbackSuite(binding, ShaderDialect.DESKTOP)
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
