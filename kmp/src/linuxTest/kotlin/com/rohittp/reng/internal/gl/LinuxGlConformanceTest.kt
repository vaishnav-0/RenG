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
import com.rohittp.reng.runGlobeGroundReadbackSuite
import com.rohittp.reng.runLatitudePrecisionProbeSuite
import com.rohittp.reng.runModelReadbackSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxGlConformanceTest {
    @Test fun theSuitePassesOnARealEsContext() {
        runOn(ShaderDialect.GLES) { report ->
            assertEquals(ShaderDialect.GLES, report.dialect)
            assertTrue(report.shadingLanguageVersionText.startsWith("OpenGL ES GLSL ES"))
            assertTrue(report.versionText.startsWith("OpenGL ES 3"))
        }
    }

    @Test fun theSuitePassesOnARealDesktopCoreContext() {
        runOn(ShaderDialect.DESKTOP) { report ->
            assertEquals(ShaderDialect.DESKTOP, report.dialect)
            assertTrue(!report.shadingLanguageVersionText.startsWith("OpenGL ES"))
        }
    }

    @Test fun theSameBinaryDetectsTwoDialectsOnOneTarget() {
        val esRenderer = runOn(ShaderDialect.GLES) { it }
        val desktopRenderer = runOn(ShaderDialect.DESKTOP) { it }
        assertEquals(esRenderer.rendererName, desktopRenderer.rendererName)
        assertTrue(esRenderer.dialect != desktopRenderer.dialect)
    }

    /**
     * Cycle E's gate, on a real ES context: the ground genuinely reaches the framebuffer, in the
     * arrangement the fixture camera implies. See `runBasemapReadbackSuite` for exactly what this
     * catches and what it does not.
     *
     * Run on the GLES context only. The desktop dialect's half of the same suite runs on macOS
     * (`MacosGlConformanceTest`), so both shading-language dialects are covered once each; running
     * both here would double a Skia rasterization the suite already performs and prove nothing the
     * pair does not.
     */
    /**
     * Cycle F-2's gate on a real ES context — the GLES half of the pair whose desktop half runs on
     * macOS. See `runModelReadbackSuite` for what this catches and what it does not.
     */
    @Test fun theModelReadbackSuitePassesOnARealEsContext() {
        val fixture = SurfacelessEglContext.create(ShaderDialect.GLES)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            binding.viewport(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            binding.scissor(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
            runModelReadbackSuite(binding, fixture.probe, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    @Test fun theBasemapReadbackSuitePassesOnARealEsContext() {
        val fixture = SurfacelessEglContext.create(ShaderDialect.GLES)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            binding.viewport(0, 0, BASEMAP_READBACK_PIXELS, BASEMAP_READBACK_PIXELS)
            binding.scissor(0, 0, BASEMAP_READBACK_PIXELS, BASEMAP_READBACK_PIXELS)
            runBasemapReadbackSuite(binding, fixture.probe, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle G task 6's gate (ADR 0038) on llvmpipe: a globe ground pass removes a back-facing patch,
     * a mercator one keeps it whatever the caller left enabled, and no mercator pixel moves in either
     * winding. `runGroundCullReadbackSuite` says what each of its four cases would survive.
     */
    @Test fun theGroundCullReadbackSuitePassesOnARealEsContext() {
        val fixture = SurfacelessEglContext.create(ShaderDialect.GLES)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            binding.viewport(0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS)
            binding.scissor(0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS)
            runGroundCullReadbackSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle G task 9's gate on llvmpipe: a `Geometry` is a subdivided, CPU-projected grid now, and
     * subdividing one under Mercator must move no pixel. The suite prints, for whichever rasteriser
     * it lands on, how far each granularity moves the frame the four-corner strip of `0.3.0` drew.
     */
    @Test fun theGeometrySubdivisionReadbackSuitePassesOnARealEsContext() {
        val fixture = SurfacelessEglContext.create(ShaderDialect.GLES)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            binding.viewport(0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS)
            binding.scissor(0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS)
            runGeometrySubdivisionReadbackSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle G task 7's gate on llvmpipe: the globe ground's own geometry, its seams and the
     * granularity the camera implies. The suite prints its silhouette and convergence numbers for
     * whichever rasteriser it lands on, and the same case on macOS runs against both Apple ones.
     */
    @Test fun theGlobeGroundReadbackSuitePassesOnARealEsContext() {
        val fixture = SurfacelessEglContext.create(ShaderDialect.GLES)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            binding.viewport(0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS)
            binding.scissor(0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS)
            runGlobeGroundReadbackSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    /**
     * Cycle G task 11's probe on llvmpipe, which is the only driver in CI that is neither Apple's nor
     * a simulator's.
     *
     * It prints what it measures rather than asserting a budget on it, and nothing here is expected
     * to stand down: the two cases that can stand down do so only on a driver whose `exp` is outside
     * its own specified bound or is not monotone, and both Apple rasterisers measured `exp` at 4 and
     * 1 ULP with no inversions. If llvmpipe differs, the printed line is the finding.
     */
    @Test fun theLatitudePrecisionProbePassesOnARealEsContext() {
        val fixture = SurfacelessEglContext.create(ShaderDialect.GLES)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            binding.viewport(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
            binding.scissor(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
            runLatitudePrecisionProbeSuite(binding, ShaderDialect.GLES)
        } finally {
            fixture.destroy()
        }
    }

    private fun <T> runOn(dialect: ShaderDialect, assertions: (GlConformanceReport) -> T): T {
        val fixture = SurfacelessEglContext.create(dialect)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            // A surfaceless context starts with viewport and scissor box 0,0,0,0.
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            // This fixture repeatedly creates and destroys GLES-profile EGL contexts in one
            // process -- exactly the shape that triggers Mesa 25.2.8's libgallium SIGSEGV inside
            // a cross-dialect glLinkProgram (docs/research/2026-08-19-mesa-cross-dialect-link-
            // segfault.md). See CrossDialectLinkPolicy's doc comment in GlConformanceSuite.kt for
            // why this is scoped to Linux only and why macOS (Task 18) must keep the real check.
            val report = runGlConformanceSuite(
                binding,
                fixture.probe,
                dialect,
                crossDialectLinkPolicy = CrossDialectLinkPolicy.SKIP_ON_LINUX_MESA_LINK_SEGFAULT,
            )
            assertEquals(8, report.checks.size)
            return assertions(report)
        } finally {
            fixture.destroy()
        }
    }
}
