package com.rohittp.reng.internal.gl

import com.rohittp.reng.BASEMAP_READBACK_PIXELS
import com.rohittp.reng.runBasemapReadbackSuite
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
