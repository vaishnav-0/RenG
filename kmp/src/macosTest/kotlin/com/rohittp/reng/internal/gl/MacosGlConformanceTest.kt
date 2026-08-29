package com.rohittp.reng.internal.gl

import com.rohittp.reng.BASEMAP_READBACK_PIXELS
import com.rohittp.reng.GLOBE_GROUND_READBACK_PIXELS
import com.rohittp.reng.GROUND_CULL_READBACK_PIXELS
import com.rohittp.reng.LABEL_INTEGRATION_PIXELS
import com.rohittp.reng.LATITUDE_PROBE_PIXELS
import com.rohittp.reng.MODEL_READBACK_PIXELS
import com.rohittp.reng.runBasemapReadbackSuite
import com.rohittp.reng.GEOMETRY_SUBDIVISION_READBACK_PIXELS
import com.rohittp.reng.runGeometrySubdivisionReadbackSuite
import com.rohittp.reng.GLOBE_FRAME_READBACK_PIXELS
import com.rohittp.reng.runGlobeFrameReadbackSuite
import com.rohittp.reng.runGlobeGroundReadbackSuite
import com.rohittp.reng.runGroundCullReadbackSuite
import com.rohittp.reng.runLabelIntegrationReadbackSuite
import com.rohittp.reng.runLatitudePrecisionProbeSuite
import com.rohittp.reng.runVertexTextureFetchProbeSuite
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

    /**
     * Cycle G task 7's gate: the globe ground's own geometry, on both Apple rasterisers this machine
     * can offer.
     *
     * Both rather than one, and deliberately so. `measureLargeQuadRasterisation` records that
     * `Apple Software Renderer` drops quads reaching far outside the viewport — the shape every
     * Mercator ground tile has — and a globe ground patch is the opposite shape: one cell of a
     * subdivided grid, a few pixels across and entirely on-screen. Whether that driver's verdict
     * differs between the two modes is a measurement rather than a deduction, so this runs the suite
     * on both and the suite prints its silhouette and convergence numbers for each.
     */
    @Test fun theGlobeGroundReadbackSuitePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG globe ground readback: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                binding.viewport(0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS)
                binding.scissor(0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS)
                runGlobeGroundReadbackSuite(binding, ShaderDialect.DESKTOP)
            } finally {
                fixture.destroy()
            }
        }
    }

    /**
     * Cycle G task 12's gate: a `ProjectionMode.GLOBE` frame through the **public** API, on both
     * Apple rasterisers this machine can offer.
     *
     * Both rather than one, because this suite is the only globe case that draws a **Mercator**
     * frame too — the cross-mode comparison needs one — and a Mercator ground tile is exactly the
     * far-off-screen quad `Apple Software Renderer` drops. The suite measures that driver with
     * `measureLargeQuadRasterisation` and stands its Mercator ground-coverage assertion down out
     * loud when the probe distrusts it, so running here on both rasterisers is what exercises the
     * stand-down rather than only the trusted path.
     */
    @Test fun theGlobeFrameReadbackSuitePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG globe frame readback: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                binding.viewport(0, 0, GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS)
                binding.scissor(0, 0, GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS)
                runGlobeFrameReadbackSuite(binding, fixture.probe, ShaderDialect.DESKTOP)
            } finally {
                fixture.destroy()
            }
        }
    }

    /**
     * Cycle G task 11's probe, on both Apple rasterisers this machine can offer.
     *
     * Both rather than one, because the whole subject is what a *driver* does to a transcendental
     * function and these are two different implementations of them — the Metal path and Apple's CPU
     * rasteriser, which is also the only driver a hosted GitHub macOS runner has. Neither is a Mali,
     * which is the family MapLibre measured 200–300 metres of latitude error on and the family RenG
     * has never run on; ADR 0033 is why no real mobile GPU appears here at all. The probe prints its
     * measured numbers per driver rather than concluding anything about the ones it cannot reach.
     */
    @Test fun theLatitudePrecisionProbePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG latitude precision probe: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                binding.viewport(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
                binding.scissor(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
                runLatitudePrecisionProbeSuite(binding, ShaderDialect.DESKTOP)
            } finally {
                fixture.destroy()
            }
        }
    }

    /**
     * E-terrain preflight: can a vertex shader read a DEM, on both Apple rasterisers?
     *
     * Same two drivers and the same reason as the latitude probe above — the Metal path and the CPU
     * rasteriser a hosted runner is limited to. A vertex texture fetch is the difference between
     * uploading a DEM once per tile and re-baking a vertex buffer whenever the granularity moves,
     * so which of the two E-terrain can rely on is a property of the drivers rather than a
     * preference.
     */
    @Test fun theVertexTextureFetchProbePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println(
                    "RenG vertex texture fetch probe: skipped, $renderer is unavailable on this machine",
                )
                return@forEach
            }
            try {
                val binding = bindOrFail()
                runVertexTextureFetchProbeSuite(binding, ShaderDialect.DESKTOP)
            } finally {
                fixture.destroy()
            }
        }
    }

    /**
     * Cycle G task 9's gate, on both Apple rasterisers this machine can offer: a `Geometry` is a
     * subdivided, CPU-projected grid now, and subdividing one under Mercator must move no pixel.
     *
     * Both rasterisers rather than one, and the fixture is built so that neither has an excuse.
     * `measureLargeQuadRasterisation` records `Apple Software Renderer` dropping quads that reach far
     * outside the viewport; this suite's quad is smaller than its viewport for exactly that reason,
     * so what is compared is RenG's two tessellations rather than the driver's clip behaviour.
     */
    @Test fun theGeometrySubdivisionReadbackSuitePassesOnBothAppleRasterisers() {
        listOf(MacosGlRenderer.DEFAULT, MacosGlRenderer.SOFTWARE).forEach { renderer ->
            val fixture = CglCoreProfileContext.createOrNull(renderer)
            if (fixture == null) {
                println("RenG geometry subdivision readback: skipped, $renderer is unavailable on this machine")
                return@forEach
            }
            try {
                val binding = bindOrFail()
                binding.viewport(0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS)
                binding.scissor(0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS)
                runGeometrySubdivisionReadbackSuite(binding, ShaderDialect.DESKTOP)
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
