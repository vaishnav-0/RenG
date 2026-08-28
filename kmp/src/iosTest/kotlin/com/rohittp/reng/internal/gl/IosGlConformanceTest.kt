package com.rohittp.reng.internal.gl

import com.rohittp.reng.BASEMAP_READBACK_PIXELS
import com.rohittp.reng.MODEL_READBACK_PIXELS
import com.rohittp.reng.runBasemapReadbackSuite
import com.rohittp.reng.runModelReadbackSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS half of RenG's platform GL gate: `IosGlBinding` executed against a real EAGL context.
 *
 * `iosSimulatorArm64Test` runs this every time, unattended. `iosArm64` has no Gradle test task at all —
 * Kotlin/Native links a device test binary and supplies no runner — so on a device these five cases run
 * only under an explicit `--ktest_filter`, and unfiltered they are killed by the watchdog along with the
 * other ~1,100 tests in the binary. Which of the two actually ran belongs in a release note.
 *
 * **Why this class exists.** The iOS binding has shipped in every release since `0.2.0` and, until
 * Cycle H, had never executed a single line anywhere — not on a device, not on a simulator, not in CI.
 * A binding proven once on a developer's desk is proven until the next commit; this file is the
 * difference between a demonstration and a gate.
 *
 * **What the driver actually is, and why one case is expected to skip.** The simulator reports
 * `Apple Software Renderer` — the same CPU rasteriser a hosted GitHub macOS runner uses, and the one
 * that failed `0.3.0`'s publication by dropping quads that reach far outside the viewport. It fails
 * the large-quad probe here too, and by more than macOS's copy does: 3,040 mismatched pixels against
 * a 512-pixel boundary budget, versus 2,112 on macOS. `runBasemapReadbackSuite` therefore skips its
 * ground-coverage case out loud on this target and runs the other four — the precedent `0.3.0` set
 * rather than anything invented here. **A run in which everything passes on the simulator means the
 * probe stopped being wired in, and the budget must not be widened to produce one.** On real hardware
 * the same probe reports zero disagreement (Apple A14; the device addendum to
 * `docs/research/2026-08-24-h-ios-gles-context-spike.md`), so nothing about iOS needs a wider
 * tolerance — the simulator does.
 *
 * **What a green run here does not say.** Every pixel this class asserts on `iosSimulatorArm64` comes
 * off a CPU rasteriser, so it says nothing about a mobile GPU's fill rule; `GL_SUBPIXEL_BITS` is 10
 * here and 4 on device, and `GL_MAX_TEXTURE_SIZE` is 4096 here and 16384 there. Three numbers this
 * project first recorded as "iOS constraints" were simulator artefacts. The simulator is a logic gate
 * and can never be a GPU proxy.
 */
class IosGlConformanceTest {
    /**
     * The roster, and the one platform-specific hazard iOS carries.
     *
     * On Linux `openPlatformGlBinding()` resolves all 91 entry points by `dlsym` and returns
     * `Unsupported` if any is missing, so the binding result is itself the proof. **On iOS it returns
     * `Bound` unconditionally** (`IosGlBinding.kt:599`), because Kotlin/Native resolves
     * `platform.gles3` symbols against `OpenGLES.framework` when it links the test binary. So the
     * three things that actually establish resolution here are: the link succeeding at all; this
     * test driving [adoptRenderContext], which reads version, renderer, extensions and three
     * `GL_MAX_*` limits through the binding and requires a clean error flag afterwards; and the
     * conformance suite's `entry-point-inventory` check, which calls the VAO, sampler,
     * `texStorage2D`, two-framebuffer, `drawBuffers`, `readBuffer` and `blitFramebuffer` family and
     * their deletes. Asserting `Bound` alone would assert nothing at all on this target.
     *
     * Two honest qualifications. The roster-size assertion here is a **restatement** — `runGlConformanceSuite`
     * asserts the same 91 in the same process, and so does `GlEntryPointRosterTest` on a fake — kept only
     * so this test states its own claim instead of borrowing one from a suite it also calls. And no test
     * on any target asserts that all 91 entry points *execute*; the inventory check drives roughly a
     * quarter of them and the rest are covered by whichever suite happens to need them. Widening that is
     * the cheapest next increment here.
     */
    @Test fun everyRosterEntryPointResolvesOnARealEaglContext() {
        val fixture = EaglOffscreenContext.create()
        try {
            val binding = bindOrFail()
            assertEquals(91, GlEntryPoint.entries.size)

            val profile = when (val adoption = adoptRenderContext(binding)) {
                is RenderContextAdoption.Adopted -> adoption.profile
                is RenderContextAdoption.Rejected ->
                    throw AssertionError("adoption rejected the EAGL context: ${adoption.failure}")
            }
            println(
                "RenG iOS conformance driver: GL_RENDERER=${profile.rendererName} " +
                    "GL_VENDOR=${profile.vendorName} " +
                    "GL_VERSION=${binding.getString(GL_VERSION)} " +
                    "GL_SHADING_LANGUAGE_VERSION=${profile.shadingLanguageVersionText} " +
                    "GL_MAX_TEXTURE_SIZE=${profile.maxTextureSize} " +
                    "extensions=${readExtensionNames(binding).size}",
            )

            assertEquals(ShaderDialect.GLES, profile.dialect, "an EAGL context is a GLES context")
            assertTrue(
                binding.getString(GL_VERSION)?.startsWith("OpenGL ES 3") == true,
                "expected an ES 3 context, got ${binding.getString(GL_VERSION)}",
            )
            assertTrue(
                profile.shadingLanguageVersionText.startsWith("OpenGL ES GLSL ES"),
                "expected an ES shading language, got ${profile.shadingLanguageVersionText}",
            )
            assertNotNull(
                fixture.probe.currentContextIdentity(),
                "the fixture's context must be current for the lifecycle check to mean anything",
            )
        } finally {
            fixture.destroy()
        }

        // Asserted after destroy on purpose. A probe that cached the identity it was built with
        // would satisfy the assertion above and still be worthless to GlLifecycleDriver, whose
        // whole job is noticing that the caller's context went away (ADRs 0007 and 0015).
        assertNull(
            fixture.probe.currentContextIdentity(),
            "the probe must read the live current context, not the one the fixture created",
        )
    }

    /**
     * Cycle D's gate on a real EAGL context — the second GLES-dialect target after Linux's ES
     * context, and the only one that runs the check on Apple's shader compiler.
     *
     * Note the absent argument: this passes no [CrossDialectLinkPolicy], so it takes
     * [CrossDialectLinkPolicy.EXERCISE_LINK] and performs the real cross-`#version` `glLinkProgram`
     * that proves ADR 0008's substitution is load-bearing. Linux is the only fixture that needs the
     * escape hatch, for a Mesa defect; iOS does not, and giving it one would quietly delete the
     * assertion on the second GLES driver this project has.
     */
    @Test fun theSuitePassesOnARealEaglContext() {
        val fixture = EaglOffscreenContext.create()
        try {
            val binding = bindOrFail()
            // Like the macOS and Linux fixtures, this context has no drawable, so viewport and
            // scissor start at 0,0,0,0 and nothing would rasterise until they are set.
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)

            val report = runGlConformanceSuite(binding, fixture.probe, ShaderDialect.GLES)
            println("RenG iOS conformance renderer: ${report.rendererName} / ${report.versionText}")
            assertEquals(ShaderDialect.GLES, report.dialect)
            assertEquals(8, report.checks.size)
            assertTrue(report.shadingLanguageVersionText.startsWith("OpenGL ES GLSL ES"))
            assertTrue(report.versionText.startsWith("OpenGL ES 3"))
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle F-2's gate on iOS: a GLB acquired, decoded, uploaded and drawn through the public API,
     * read back, and asserted in pixels. See `runModelReadbackSuite` for what it catches and what it
     * does not.
     *
     * This one passes on the software rasteriser unmodified and with no tolerance change, which is
     * the result that says F-2's numbers are portable rather than tuned to the two drivers they were
     * written on.
     */
    @Test fun theModelReadbackSuitePassesOnARealEaglContext() {
        val fixture = EaglOffscreenContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            binding.scissor(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            runModelReadbackSuite(binding, fixture.probe, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle E's gate on iOS, and **the case this target is expected to run short of.**
     *
     * `runBasemapReadbackSuite` opens by measuring how this driver rasterises the fixture's own
     * ground-tile footprints against their analytic rectangles, prints that verdict, and — when the
     * driver disagrees by more than a boundary budget — skips its ground-coverage case out loud and
     * runs the other four. On the simulator it skips. That is the probe working, not the suite
     * failing: a missing ground pixel on `Apple Software Renderer` would measure the driver rather
     * than RenG, which is exactly the confusion that cost `0.3.0` a publication.
     *
     * The suite is entirely self-contained — a fake `Transport` serving a fixture style and
     * hand-assembled 2x2 PNGs — so it needs no network and no api key. The iOS spike recorded that it
     * did, and that was wrong.
     */
    @Test fun theBasemapReadbackSuitePassesOnARealEaglContext() {
        val fixture = EaglOffscreenContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, BASEMAP_READBACK_PIXELS, BASEMAP_READBACK_PIXELS)
            binding.scissor(0, 0, BASEMAP_READBACK_PIXELS, BASEMAP_READBACK_PIXELS)
            runBasemapReadbackSuite(binding, fixture.probe, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * E-labels task 8's gate on iOS, and the third rasteriser the label pass has been measured on.
     *
     * **This is the case that turns E-labels task 18's prediction into a measurement.** The label
     * suites rest on the claim that a glyph quad is small enough to survive a driver that drops the
     * ground's 512-pixel ones -- and this target is the sharpest available test of it, because the
     * simulator's `Apple Software Renderer` is exactly the driver that fails the large-quad probe
     * here by 3,040 pixels and skips the basemap suite's ground case. `runLabelReadbackSuite` opens
     * with [measureGlyphQuadRasterisation] over its own three 8-by-8 footprints at the label pass's
     * constant clip `w` of 1, and on this driver that measures **0** pixels of disagreement against
     * a 32-pixel boundary budget -- so the covered-row classification runs rather than skipping, and
     * every one of its pixels is asserted.
     *
     * Unlike the basemap suite above, therefore, a fully green run here is the expected outcome and
     * not a sign the probe stopped being wired in. The two claims live side by side on one driver:
     * the ground's quads are dropped and the label pass's are not.
     */
    @Test fun theLabelReadbackSuitePassesOnARealEaglContext() {
        val fixture = EaglOffscreenContext.create()
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

    private fun bindOrFail(): GlBinding = when (val result = openPlatformGlBinding()) {
        is GlBindingResult.Bound -> result.binding
        is GlBindingResult.Unsupported -> throw AssertionError("platform.gles3 must bind on iOS")
    }
}
