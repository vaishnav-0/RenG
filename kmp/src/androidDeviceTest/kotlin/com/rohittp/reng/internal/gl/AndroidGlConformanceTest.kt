package com.rohittp.reng.internal.gl

import com.rohittp.reng.BASEMAP_READBACK_PIXELS
import com.rohittp.reng.GROUND_CULL_READBACK_PIXELS
import com.rohittp.reng.GLOBE_GROUND_READBACK_PIXELS
import com.rohittp.reng.LATITUDE_PROBE_PIXELS
import com.rohittp.reng.MODEL_READBACK_PIXELS
import com.rohittp.reng.runBasemapReadbackSuite
import com.rohittp.reng.runGroundCullReadbackSuite
import com.rohittp.reng.GEOMETRY_SUBDIVISION_READBACK_PIXELS
import com.rohittp.reng.runGeometrySubdivisionReadbackSuite
import com.rohittp.reng.GLOBE_FRAME_READBACK_PIXELS
import com.rohittp.reng.runGlobeFrameReadbackSuite
import com.rohittp.reng.runGlobeGroundReadbackSuite
import com.rohittp.reng.runLatitudePrecisionProbeSuite
import com.rohittp.reng.runVertexTextureFetchProbeSuite
import com.rohittp.reng.runModelReadbackSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android's half of the GL gate, and the only place `AndroidGlBinding` is executed at all. It shipped
 * in every release from `0.2.0` without a single one of its entry points ever running — not on a
 * device, not on an emulator, not in CI — because `kmp/src` had no device test source set. It has one
 * now (ADR 0032), and this is what runs in it.
 *
 * The shape is `LinuxGlConformanceTest`'s, with two differences that are facts about the platform
 * rather than choices. There is only one dialect: an Android EGL context is GLES, so there is no
 * `DESKTOP` half to pair with and no same-binary two-dialect case. And the cross-dialect link runs
 * for real — `CrossDialectLinkPolicy.SKIP_ON_LINUX_MESA_LINK_SEGFAULT` exists for one Mesa
 * `libgallium` bug and must not travel to a driver that does not have it.
 *
 * Every suite this calls lives in `commonTest`, in one copy, reached through the explicit
 * `dependsOn(commonTest)` edge `kmp/build.gradle.kts` declares.
 */
class AndroidGlConformanceTest {

    /**
     * Reports the driver rather than asserting a particular one. Which GPU an instrumented run lands
     * on is a property of the attached device, and a suite that names one would be a suite that only
     * one phone can pass; the emulator's ANGLE-over-SwiftShader stack and Adreno both have to be
     * legible here. What *is* asserted is the contract RenG's dialect detection depends on: this is
     * an ES 3 context.
     */
    @Test fun theDriverIdentifiesItselfAsAnEs3Context() {
        withCurrentContext { binding ->
            val subpixelBits = IntArray(1)
            binding.getIntegerv(GL_SUBPIXEL_BITS, subpixelBits)
            val maxTextureSize = IntArray(1)
            binding.getIntegerv(GL_MAX_TEXTURE_SIZE, maxTextureSize)
            val extensions = readExtensionNames(binding)
            println(
                "RenG Android GL driver: GL_RENDERER=${binding.getString(GL_RENDERER)} " +
                    "GL_VENDOR=${binding.getString(GL_VENDOR)} " +
                    "GL_VERSION=${binding.getString(GL_VERSION)} " +
                    "GL_SHADING_LANGUAGE_VERSION=${binding.getString(GL_SHADING_LANGUAGE_VERSION)} " +
                    "GL_SUBPIXEL_BITS=${subpixelBits[0]} GL_MAX_TEXTURE_SIZE=${maxTextureSize[0]} " +
                    "extensions=${extensions.size}",
            )
            assertTrue(
                binding.getString(GL_VERSION).orEmpty().startsWith("OpenGL ES 3"),
                "an EGL_CONTEXT_CLIENT_VERSION=3 context must report OpenGL ES 3.x",
            )
        }
    }

    /**
     * `openPlatformGlBinding()` returns `Bound(AndroidGlBinding)` unconditionally, exactly as the iOS
     * binding does, so a green `Bound` proves nothing on its own. `adoptRenderContext` is what drives
     * the binding — version, renderer, extension set, three `GL_MAX_*` limits — and requires a clean
     * error flag afterwards, so a mis-declared entry point surfaces here as a rejection rather than
     * as a silent wrong answer somewhere later.
     */
    @Test fun everyRosterEntryPointResolvesOnThisDriver() {
        assertEquals(91, GlEntryPoint.entries.size)
        withCurrentContext { binding ->
            val adoption = adoptRenderContext(binding)
            val adopted = adoption as? RenderContextAdoption.Adopted
                ?: throw AssertionError("adoption rejected: $adoption")
            println(
                "RenG Android adopted: dialect=${adopted.profile.dialect} " +
                    "renderer=${adopted.profile.rendererName} version=${adopted.profile.version} " +
                    "maxTextureSize=${adopted.profile.maxTextureSize} " +
                    "maxColorAttachments=${adopted.profile.maxColorAttachments} " +
                    "maxCombinedTextureImageUnits=${adopted.profile.maxCombinedTextureImageUnits}",
            )
            assertEquals(ShaderDialect.GLES, adopted.profile.dialect)
        }
    }

    @Test fun theSuitePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            val report = runGlConformanceSuite(binding, fixture.probe, ShaderDialect.GLES)
            println(
                "RenG conformance renderer: ${report.rendererName} / ${report.versionText} " +
                    "sl=${report.shadingLanguageVersionText} checks=${report.checks}",
            )
            assertEquals(ShaderDialect.GLES, report.dialect)
            assertEquals(8, report.checks.size)
            assertTrue(report.shadingLanguageVersionText.startsWith("OpenGL ES GLSL ES"))
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle E's gate on Android's driver. This is where the large-off-screen-quad probe runs and
     * prints its verdict: the suite measures the rasteriser and skips exactly the case that cannot
     * survive a driver dropping whole primitives, rather than tolerating a budget into meaning
     * nothing. Reading the printed pixel count is the point — it is how a new GPU vendor gets
     * assessed without anybody editing a tolerance.
     *
     * It also exercises Rentile's Skia on the device, which is the half `androidHostTest` provably
     * cannot do: that JVM looks for `libskiko-macos-arm64.dylib`, a host library the Android AAR was
     * never going to carry.
     */
    @Test fun theBasemapReadbackSuitePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
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
     * Cycle G task 6's gate (ADR 0038) on Android's driver: a globe ground pass removes a back-facing
     * patch, a mercator one keeps it whatever the caller left enabled, and no mercator pixel moves in
     * either winding. See `runGroundCullReadbackSuite` for what each case would survive.
     */
    @Test fun theGroundCullReadbackSuitePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS)
            binding.scissor(0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS)
            runGroundCullReadbackSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle G task 9's gate on Android's driver: a `Geometry` is a subdivided, CPU-projected grid now, and
     * subdividing one under Mercator must move no pixel. The suite prints, for whichever rasteriser
     * it lands on, how far each granularity moves the frame the four-corner strip of `0.3.0` drew.
     */
    @Test fun theGeometrySubdivisionReadbackSuitePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS)
            binding.scissor(0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS)
            runGeometrySubdivisionReadbackSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle G task 7's gate on Android's driver: the globe ground's own geometry, its seams and the
     * granularity the camera implies. Manual, like everything in this source set (ADR 0033).
     */
    @Test fun theGlobeGroundReadbackSuitePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS)
            binding.scissor(0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS)
            runGlobeGroundReadbackSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }

    /**
     * Cycle G task 12's gate on Android's driver: a `ProjectionMode.GLOBE` frame through the
     * **public** API. Manual, like everything in this source set (ADR 0033).
     *
     * Whether the cross-mode case's Mercator ground-coverage assertion runs or stands down here is a
     * measurement rather than a prediction: the suite opens with `measureLargeQuadRasterisation` and
     * prints what this driver does with a quad that reaches far outside the viewport. On the only
     * two Android rasterisers RenG has ever met — an Adreno 830 and an ANGLE-over-Vulkan-over-
     * SwiftShader emulator — the equivalent probe reported zero disagreeing pixels, so the
     * expectation is that nothing stands down; a run that does is worth reading rather than working
     * around.
     */
    @Test fun theGlobeFrameReadbackSuitePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS)
            binding.scissor(0, 0, GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS)
            runGlobeFrameReadbackSuite(binding, fixture.probe, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }
    }

    /**
     * Cycle G task 11's probe on Android's driver — the one target where it could answer the
     * question that started the cycle, and where nothing has ever run it.
     *
     * MapLibre's 200-300 metre latitude error was measured on **Mali-G610/G710**. RenG's only Android
     * device evidence is an Adreno 830, the family that was fine, and this source set is manual
     * (ADR 0033), so no Mali number exists anywhere in this project. Whoever first runs this against
     * a Mali should record the printed line: it is the measurement the whole latitude spike could not
     * make.
     */
    @Test fun theLatitudePrecisionProbePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
            binding.scissor(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
            runLatitudePrecisionProbeSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * E-terrain preflight: can a vertex shader read a DEM on EGL14 pbuffer?
     *
     * The cycle's architecture turns on the answer, because it decides between uploading a DEM once
     * per tile and re-baking a vertex buffer whenever the granularity moves. See the probe's own
     * KDoc for why the advertised unit count is not the measurement.
     */
    @Test fun theVertexTextureFetchProbePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
            binding.scissor(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
            runVertexTextureFetchProbeSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /** Cycle F-2's gate on Android's driver: a GLB drawn through the public API and read back. */
    @Test fun theModelReadbackSuitePassesOnARealEsContext() {
        val fixture = PbufferEglContext.create()
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            binding.scissor(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            runModelReadbackSuite(binding, fixture.probe, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    private fun bindOrFail(): GlBinding = when (val result = openPlatformGlBinding()) {
        is GlBindingResult.Bound -> result.binding
        is GlBindingResult.Unsupported ->
            throw AssertionError("every roster entry point must resolve on this driver: ${result.failure}")
    }

    private fun withCurrentContext(body: (GlBinding) -> Unit) {
        val fixture = PbufferEglContext.create()
        try {
            body(bindOrFail())
        } finally {
            fixture.destroy()
        }
    }
}

/** `GL_SUBPIXEL_BITS`, which separates a hardware rasteriser (4) from a software one (10). */
private const val GL_SUBPIXEL_BITS: Int = 0x0D50
