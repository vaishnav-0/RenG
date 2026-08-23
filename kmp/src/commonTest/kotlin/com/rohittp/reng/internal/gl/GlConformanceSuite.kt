package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.shader.ShaderProfilePlan
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared conformance suite body: one set of assertions run against a REAL GL context on both
 * measured dialects (Linux surfaceless EGL / GLES and macOS CGL / desktop GL), and against both
 * concrete [GlBinding] implementations that carry them. It lives in `commonTest` rather than a
 * shared-but-unpublished source set so every target still compiles it, while only the `linuxTest`
 * and `macosTest` fixtures (Tasks 17 and 18) ever call [runGlConformanceSuite] -- `androidHostTest`
 * and the iOS test compilations have no real context to run it against.
 *
 * Every assertion below runs against a genuine driver, where a query genuinely reflects previously
 * written state. That is a deliberate departure from the fake-oriented rule documented at
 * `GlStateSnapshotTest.kt` lines 77-83: a capture/restore/capture round trip against
 * `RecordingGlBinding` cannot fail because the fake's query maps are static and never mutated by
 * its write methods, but the same round trip here is a true and valuable proof, because a real
 * driver's state genuinely changes when written and genuinely persists when restored.
 */
internal const val CONFORMANCE_SURFACE_PIXELS: Int = 64
internal const val CONFORMANCE_TEXTURE_UNITS: Int = 2

internal data class GlConformanceReport(
    val dialect: ShaderDialect,
    val rendererName: String,
    val versionText: String,
    val shadingLanguageVersionText: String,
    val checks: List<String>,
)

/**
 * How [assertShaderDialectMatrix] handles its deliberate cross-`#version`-dialect negative link.
 *
 * [EXERCISE_LINK] performs the real check everywhere it is safe: compile a shader pair in the
 * dialect opposite the adopted context and call `glLinkProgram` on it, asserting the driver
 * rejects it (or, where [RenderContextProfile.supportsEs3Compatibility] entitles the driver to
 * accept it instead, asserting the compile-only capability probe -- see
 * [assertGles300CompilesWithoutLinking]). This is the assertion proving RenG's `#version`
 * substitution (`ShaderProfilePlan`, ADR 0008) is necessary rather than decorative, so every
 * fixture keeps it unless it has a documented reason not to. This is the default, and the only
 * value any caller needs to pass explicitly today; from Task 18, the macOS CGL fixture is
 * expected to rely on this default too.
 *
 * [SKIP_ON_LINUX_MESA_LINK_SEGFAULT] never calls `glLinkProgram` on a cross-dialect pair, on
 * either dialect. Mesa 25.2.8's `libgallium` SIGSEGVs inside that exact call whenever a process
 * holds two or more EGL contexts where at least one is GLES-profile and at least one of them
 * performs this link -- a genuine, order-independent driver defect reproduced from a RenG-free C
 * program, not a RenG bug, and not fixable by gating on a capability extension alone (a
 * GLES-profile context never advertises `GL_ARB_ES3_compatibility`, so that gate leaves the link
 * still running on the GLES side). See
 * `docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md` for the full crash-rate matrix,
 * the minimal C reproducer, and the version bisection. Only [LinuxGlConformanceTest] passes this
 * value -- its fixture is the one that repeatedly creates and destroys GLES-profile EGL contexts
 * in one process, which is exactly the shape that triggers the defect.
 */
internal enum class CrossDialectLinkPolicy {
    EXERCISE_LINK,
    SKIP_ON_LINUX_MESA_LINK_SEGFAULT,
}

internal fun runGlConformanceSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    expectedDialect: ShaderDialect,
    crossDialectLinkPolicy: CrossDialectLinkPolicy = CrossDialectLinkPolicy.EXERCISE_LINK,
): GlConformanceReport {
    val checks = mutableListOf<String>()

    val profile = assertContextAdoption(binding, expectedDialect)
    checks += "context-adoption"
    assertEntryPointInventory(binding)
    checks += "entry-point-inventory"
    assertErrorQueueIsDestructive(binding)
    checks += "error-queue"
    assertStateRoundTripIsExact(binding, profile)
    checks += "state-round-trip"
    assertShaderDialectMatrix(binding, profile, crossDialectLinkPolicy)
    checks += "shader-dialect-matrix"
    assertOffscreenCompositeAndRestore(binding, profile)
    checks += "offscreen-composite"
    assertLifecycleUnderARealContext(binding, probe, profile)
    checks += "lifecycle"

    return GlConformanceReport(
        dialect = profile.dialect,
        rendererName = profile.rendererName,
        versionText = binding.getString(GL_VERSION).orEmpty(),
        shadingLanguageVersionText = profile.shadingLanguageVersionText,
        checks = checks,
    )
}

private fun assertContextAdoption(
    binding: GlBinding,
    expectedDialect: ShaderDialect,
): RenderContextProfile {
    val adoption = adoptRenderContext(binding)
    val adopted = adoption as? RenderContextAdoption.Adopted
        ?: throw AssertionError("the fixture context must satisfy the ES 3.0 requirement")
    val profile = adopted.profile
    assertEquals(expectedDialect, profile.dialect, "the fixture created a ${expectedDialect} context")
    assertTrue(profile.rendererName.isNotBlank(), "GL_RENDERER must identify the driver")
    assertTrue(profile.maxTextureSize >= CONFORMANCE_SURFACE_PIXELS)
    assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding), "adoption must leave no error flag")
    return profile
}

private fun assertEntryPointInventory(binding: GlBinding) {
    assertEquals(91, GlEntryPoint.entries.size)
    assertTrue(binding.getString(GL_VENDOR)?.isNotBlank() == true)

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.bindVertexArray(vertexArray)

    binding.genSamplers(1, names)
    val sampler = names[0]
    binding.bindSampler(0, sampler)
    binding.samplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.bindSampler(0, 0)

    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4)

    val framebuffers = IntArray(2)
    binding.genFramebuffers(2, framebuffers)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffers[0])
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    binding.drawBuffers(1, intArrayOf(GL_COLOR_ATTACHMENT0))
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, framebuffers[0])
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffers[1])
    binding.blitFramebuffer(0, 0, 4, 4, 0, 0, 4, 4, GL_COLOR_BUFFER_BIT, GL_NEAREST)

    assertTrue(binding.isFramebuffer(framebuffers[0]))
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    binding.bindVertexArray(0)
    binding.deleteFramebuffers(2, framebuffers)
    binding.deleteTextures(1, intArrayOf(texture))
    binding.deleteSamplers(1, intArrayOf(sampler))
    binding.deleteVertexArrays(1, intArrayOf(vertexArray))

    // The second blit target is intentionally incomplete on some drivers, so tolerate that one
    // flag and require the rest of the sequence to be clean.
    val flag = GlErrorQueue.firstOwnError(binding)
    assertTrue(
        flag == GL_NO_ERROR || flag == GL_INVALID_FRAMEBUFFER_OPERATION,
        "the ES-3 entry points must execute without an unexpected error flag",
    )
}

private fun assertErrorQueueIsDestructive(binding: GlBinding) {
    GlErrorQueue.drainOnEntry(binding)
    val out = IntArray(1)
    binding.getIntegerv(UNDEFINED_GL_TOKEN, out)
    assertEquals(GL_INVALID_ENUM, binding.getError(), "a provoked flag reads once")
    assertEquals(GL_NO_ERROR, binding.getError(), "and is gone thereafter")

    binding.getIntegerv(UNDEFINED_GL_TOKEN, out)
    assertEquals(
        GL_INVALID_ENUM,
        GlErrorQueue.drainOnEntry(binding),
        "the drain reports the consumer's pre-existing flag",
    )
    assertEquals(GL_NO_ERROR, GlErrorQueue.drainOnEntry(binding), "and consumes it")
}

private const val UNDEFINED_GL_TOKEN: Int = 0x7FFF

private fun assertStateRoundTripIsExact(binding: GlBinding, profile: RenderContextProfile) {
    val scratch = createScratchState(binding)
    GlErrorQueue.drainOnEntry(binding)

    perturbRestoredState(binding, profile, scratch, variant = 0)
    val captured = captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS)
    assertEquals(
        GL_NO_ERROR,
        GlErrorQueue.firstOwnError(binding),
        "capture must query only tokens valid on this dialect",
    )

    perturbRestoredState(binding, profile, scratch, variant = 1)
    val different = captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS)
    assertTrue(captured != different, "the perturbation must actually change every captured item")

    restoreGlState(binding, captured)
    assertEquals(
        captured,
        captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS),
        "save, perturb, and restore must be byte-exact",
    )
    assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding))

    if (profile.dialect == ShaderDialect.GLES) {
        assertNull(captured.drawBuffer)
        assertNull(captured.lineSmoothEnabled)
    } else {
        assertNotNull(captured.drawBuffer)
        assertNotNull(captured.lineSmoothEnabled)
    }

    deleteScratchState(binding, scratch)
}

private class ScratchGlState(
    val texture: Int,
    val secondTexture: Int,
    val sampler: Int,
    val buffer: Int,
    val vertexArray: Int,
    val framebuffer: Int,
    val renderbuffer: Int,
)

/**
 * Allocates the seven scratch objects [perturbRestoredState] binds during the round trip: two
 * immutably-storaged `GL_RGBA8` textures, a sampler, a buffer, a vertex array, a
 * `GL_DEPTH_COMPONENT24` renderbuffer, and a framebuffer with a colour attachment so it is
 * complete. Every object is left unbound on return; [assertStateRoundTripIsExact] captures the
 * context's original bindings before touching any of this.
 */
private fun createScratchState(binding: GlBinding): ScratchGlState {
    val names = IntArray(1)

    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4)

    binding.genTextures(1, names)
    val secondTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, secondTexture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4)
    binding.bindTexture(GL_TEXTURE_2D, 0)

    binding.genSamplers(1, names)
    val sampler = names[0]

    binding.genBuffers(1, names)
    val buffer = names[0]

    binding.genVertexArrays(1, names)
    val vertexArray = names[0]

    binding.genRenderbuffers(1, names)
    val renderbuffer = names[0]
    binding.bindRenderbuffer(GL_RENDERBUFFER, renderbuffer)
    binding.renderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, 4, 4)
    binding.bindRenderbuffer(GL_RENDERBUFFER, 0)

    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    return ScratchGlState(
        texture = texture,
        secondTexture = secondTexture,
        sampler = sampler,
        buffer = buffer,
        vertexArray = vertexArray,
        framebuffer = framebuffer,
        renderbuffer = renderbuffer,
    )
}

private fun deleteScratchState(binding: GlBinding, scratch: ScratchGlState) {
    binding.deleteFramebuffers(1, intArrayOf(scratch.framebuffer))
    binding.deleteRenderbuffers(1, intArrayOf(scratch.renderbuffer))
    binding.deleteVertexArrays(1, intArrayOf(scratch.vertexArray))
    binding.deleteBuffers(1, intArrayOf(scratch.buffer))
    binding.deleteSamplers(1, intArrayOf(scratch.sampler))
    binding.deleteTextures(2, intArrayOf(scratch.texture, scratch.secondTexture))
}

private fun perturbRestoredState(
    binding: GlBinding,
    profile: RenderContextProfile,
    scratch: ScratchGlState,
    variant: Int,
) {
    val bias = variant.toFloat()
    binding.activeTexture(GL_TEXTURE0 + 1)
    binding.bindTexture(GL_TEXTURE_2D, if (variant == 0) scratch.texture else scratch.secondTexture)
    binding.bindSampler(1, if (variant == 0) scratch.sampler else 0)
    binding.activeTexture(GL_TEXTURE0 + variant)

    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, if (variant == 0) scratch.framebuffer else 0)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, if (variant == 0) scratch.framebuffer else 0)
    binding.bindRenderbuffer(GL_RENDERBUFFER, if (variant == 0) scratch.renderbuffer else 0)
    binding.bindVertexArray(if (variant == 0) scratch.vertexArray else 0)
    binding.bindBuffer(GL_ARRAY_BUFFER, if (variant == 0) scratch.buffer else 0)
    binding.bindBuffer(GL_PIXEL_UNPACK_BUFFER, if (variant == 0) scratch.buffer else 0)
    binding.bindBuffer(GL_UNIFORM_BUFFER, if (variant == 0) scratch.buffer else 0)

    if (variant == 0) binding.enable(GL_BLEND) else binding.disable(GL_BLEND)
    binding.blendFuncSeparate(
        if (variant == 0) GL_SRC_ALPHA else GL_ONE,
        GL_ONE_MINUS_SRC_ALPHA,
        GL_ONE,
        if (variant == 0) GL_ZERO else GL_ONE,
    )
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendColor(0.25f + bias * 0.5f, 0.5f, 0.75f, 1.0f)

    if (variant == 0) binding.enable(GL_DEPTH_TEST) else binding.disable(GL_DEPTH_TEST)
    binding.depthFunc(if (variant == 0) GL_GREATER else GL_LESS)
    binding.depthMask(variant == 0)
    binding.depthRangef(0.25f * (variant + 1), 0.75f)
    binding.clearDepthf(0.125f * (variant + 1))

    if (variant == 0) binding.enable(GL_CULL_FACE) else binding.disable(GL_CULL_FACE)
    binding.cullFace(GL_BACK)
    binding.frontFace(if (variant == 0) GL_CCW else 0x0900)

    binding.viewport(variant, variant, 16 + variant, 24 + variant)
    if (variant == 0) binding.enable(GL_SCISSOR_TEST) else binding.disable(GL_SCISSOR_TEST)
    binding.scissor(variant, variant, 8 + variant, 12 + variant)
    binding.colorMask(true, variant == 0, true, variant == 0)
    binding.clearColor(0.1f * (variant + 1), 0.2f, 0.3f, 0.4f)

    binding.pixelStorei(GL_UNPACK_ALIGNMENT, if (variant == 0) 1 else 8)
    binding.pixelStorei(GL_UNPACK_ROW_LENGTH, 3 + variant)
    binding.pixelStorei(GL_UNPACK_SKIP_ROWS, variant)
    binding.pixelStorei(GL_UNPACK_SKIP_PIXELS, 1 + variant)
    binding.pixelStorei(GL_PACK_ALIGNMENT, if (variant == 0) 2 else 8)

    if (profile.supportsSrgbWriteControl) {
        if (variant == 0) binding.enable(GL_FRAMEBUFFER_SRGB) else binding.disable(GL_FRAMEBUFFER_SRGB)
    }
    if (profile.dialect == ShaderDialect.DESKTOP) {
        if (variant == 0) binding.enable(GL_LINE_SMOOTH) else binding.disable(GL_LINE_SMOOTH)
    }
    GlErrorQueue.drainOnEntry(binding)
}

private const val CONFORMANCE_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec3 rengConformancePosition;\n" +
        "uniform mat4 rengConformanceMatrix;\n" +
        "out vec2 rengConformanceUv;\n" +
        "void main() {\n" +
        "    rengConformanceUv = rengConformancePosition.xy;\n" +
        "    gl_Position = rengConformanceMatrix * vec4(rengConformancePosition, 1.0);\n" +
        "}\n"

private const val CONFORMANCE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D rengConformanceTexture;\n" +
        "uniform int rengConformanceLevel;\n" +
        "in vec2 rengConformanceUv;\n" +
        "layout(location = 0) out vec4 rengConformanceColour;\n" +
        "void main() {\n" +
        "    vec2 size = vec2(textureSize(rengConformanceTexture, rengConformanceLevel));\n" +
        "    rengConformanceColour = texture(rengConformanceTexture, rengConformanceUv / max(size, vec2(1.0)));\n" +
        "}\n"

private fun assertShaderDialectMatrix(
    binding: GlBinding,
    profile: RenderContextProfile,
    crossDialectLinkPolicy: CrossDialectLinkPolicy,
) {
    val pair = ShaderPair(CONFORMANCE_VERTEX_SOURCE, CONFORMANCE_FRAGMENT_SOURCE)
    val key = ResourceKeyDeriver().geometryProgram(pair).key
    val vertexPlan = requireNotNull(scanShaderProfile(CONFORMANCE_VERTEX_SOURCE))
    val fragmentPlan = requireNotNull(scanShaderProfile(CONFORMANCE_FRAGMENT_SOURCE))

    val matching = compileShaderProgram(binding, profile.dialect, key, vertexPlan, fragmentPlan)
    val linked = matching as? GlProgramResult.Linked
        ?: throw AssertionError("the ${profile.dialect} source must compile and link on this context")
    binding.deleteProgram(linked.program)

    val opposite = when (profile.dialect) {
        ShaderDialect.GLES -> ShaderDialect.DESKTOP
        ShaderDialect.DESKTOP -> ShaderDialect.GLES
    }

    // Mesa 25.2.8's libgallium SIGSEGVs inside glLinkProgram whenever a process holds 2+ EGL
    // contexts -- at least one GLES-profile -- and at least one of them performs exactly this
    // deliberate cross-#version-dialect link. See
    // docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md for the full crash-rate
    // matrix, the minimal C reproducer, and the version bisection (29/30 crashes on Mesa
    // 25.2.8, 0/15 on Mesa 23.2.1). RenG's production code never performs this operation --
    // `ShaderProfilePlan` always substitutes to the dialect the runtime context reports, so
    // only this suite's deliberate negative check ever links a knowingly-mismatched shader.
    //
    // The negative expectation this check exists to prove -- "the wrong directive must fail to
    // link" -- is also already unsound on exactly the drivers that crash: a driver advertising
    // GL_ARB_ES3_compatibility (Mesa's desktop core profile) is entitled to accept
    // `#version 300 es` unchanged, which is precisely why the `oppositeShouldLink` branch below
    // already exists as a non-failure case. So on such a driver there is nothing left to prove
    // by forcing the link, and this suite does not perform it -- it instead proves the
    // capability probe itself is correct, by asserting `#version 300 es` genuinely compiles
    // (never linking) on this context, so the skip is not indistinguishable from an assertion
    // that silently checks nothing. Where the driver does NOT advertise
    // GL_ARB_ES3_compatibility, the negative check below is unchanged: the assertion is valid
    // there, and the Mesa crash trigger needs a GLES-profile context to be entitled to accept
    // the cross-dialect input in the first place, which a non-ES3-compatible driver never is.
    val oppositeShouldLink =
        profile.dialect == ShaderDialect.DESKTOP && profile.supportsEs3Compatibility

    when {
        oppositeShouldLink -> assertGles300CompilesWithoutLinking(binding, vertexPlan, fragmentPlan)

        crossDialectLinkPolicy == CrossDialectLinkPolicy.SKIP_ON_LINUX_MESA_LINK_SEGFAULT -> {
            // Gating only the ES3-compatible-DESKTOP branch above (the previous fix, commit
            // 598cc44) did not fully avoid the Mesa defect: a GLES-profile context never
            // advertises GL_ARB_ES3_compatibility, so `oppositeShouldLink` is always false here,
            // meaning the unmodified negative link below still ran on the GLES side. The
            // investigation's own crash-rate matrix already contains this exact case -- two
            // GLES-profile contexts, each running this unmodified negative link, crash 15/15
            // with zero DESKTOP contexts involved at all -- which is exactly what
            // `LinuxGlConformanceTest.theSameBinaryDetectsTwoDialectsOnOneTarget`'s second GLES
            // context does after the first test method's GLES context already ran. Measured safe
            // at 20/20 trials
            // (docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md): skip the
            // cross-dialect glLinkProgram call entirely on Linux, regardless of dialect or
            // capability -- there is no capability-based gate that avoids this, short of never
            // performing the link.
            //
            // This intentionally drops the "the wrong directive fails to link" assertion on
            // Linux. Task 18's macOS CGL fixture is where that assertion must keep running --
            // Apple's GL is 4.1 core and does not advertise GL_ARB_ES3_compatibility, so the
            // check is both valid (the driver has no entitlement to accept the mismatch) and
            // safe (macOS is not the driver with this defect) there. Do not delete this branch as
            // a "pointless" no-op without reading the research document above first: it is a
            // driver workaround, not a design choice, and removing it re-exposes the Mesa
            // SIGSEGV on every Linux CI run.
        }

        else -> {
            var observedLog = ""
            val other = compileShaderProgram(binding, opposite, key, vertexPlan, fragmentPlan) { _, log ->
                observedLog = log
            }
            val failed = other as? GlProgramResult.Failed
                ?: throw AssertionError("the wrong directive must fail on this context")
            assertTrue(observedLog.isNotEmpty(), "the driver must explain its rejection to the observer")
            assertTrue(
                observedLog !in failed.failure.toString(),
                "the driver log must never cross the failure boundary",
            )
            assertEquals("shaderPair", assertNotNull(failed.failure.diagnostic).fieldName)
        }
    }
    GlErrorQueue.drainOnEntry(binding)
}

/**
 * Proves `supportsEs3Compatibility` correctly predicts that `#version 300 es` is accepted on
 * this (desktop) context, without ever calling `glLinkProgram` on a cross-dialect pair -- that
 * exact call is the one Mesa 25.2.8 crashes inside (see the gate comment above and
 * `docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md`). Compiling, never linking, is
 * unaffected: the reported crash is isolated to the link stage.
 */
private fun assertGles300CompilesWithoutLinking(
    binding: GlBinding,
    vertexPlan: ShaderProfilePlan,
    fragmentPlan: ShaderProfilePlan,
) {
    assertShaderStageCompiles(binding, GL_VERTEX_SHADER, vertexPlan.sourceFor(ShaderDialect.GLES))
    assertShaderStageCompiles(binding, GL_FRAGMENT_SHADER, fragmentPlan.sourceFor(ShaderDialect.GLES))
}

private fun assertShaderStageCompiles(binding: GlBinding, type: Int, source: String) {
    val shader = binding.createShader(type)
    binding.shaderSource(shader, source)
    binding.compileShader(shader)
    val status = IntArray(1)
    binding.getShaderiv(shader, GL_COMPILE_STATUS, status)
    val log = binding.getShaderInfoLog(shader)
    binding.deleteShader(shader)
    assertTrue(
        status[0] != 0,
        "a driver advertising $ES3_COMPATIBILITY_EXTENSION must accept #version 300 es at the " +
            "compile stage even though this suite skips the crash-triggering link: $log",
    )
}

private fun assertOffscreenCompositeAndRestore(binding: GlBinding, profile: RenderContextProfile) {
    val deriver = ResourceKeyDeriver()
    val descriptor = OffscreenSurfaceDescriptor(
        widthPixels = CONFORMANCE_SURFACE_PIXELS,
        heightPixels = CONFORMANCE_SURFACE_PIXELS,
        colourFormat = OffscreenColourFormat.RGBA8,
        depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
    )
    val surface = (
        createOffscreenSurface(binding, profile, deriver.offscreenSurface(descriptor).key, descriptor)
            as? OffscreenSurfaceResult.Created
        )?.surface ?: throw AssertionError("the offscreen surface must be framebuffer-complete")

    val cache = GlProgramCache()
    val composite = (
        createCompositePipeline(binding, profile.dialect, cache, deriver)
            as? CompositePipelineResult.Created
        )?.pipeline ?: throw AssertionError("the composite pipeline must link on this context")

    // A surfaceless context has no default framebuffer, and framebuffer zero then reports
    // GL_FRAMEBUFFER_UNDEFINED, so the target is an FBO this suite owns.
    val names = IntArray(1)
    binding.genTextures(1, names)
    val targetTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, targetTexture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val targetFramebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0,
    )
    assertEquals(GL_FRAMEBUFFER_COMPLETE, binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER))
    binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.clearColor(1.0f, 0.0f, 0.0f, 1.0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    GlErrorQueue.drainOnEntry(binding)

    val before = captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS)
    val failure = drawFrame(
        binding = binding,
        profile = profile,
        surface = surface,
        composite = composite,
        targetFramebuffer = FramebufferName(targetFramebuffer.toUInt()),
    ) { inner ->
        inner.clearColor(0.0f, 0.0f, 1.0f, 1.0f)
        inner.clear(GL_COLOR_BUFFER_BIT)
    }
    assertNull(failure, "a Cycle D frame must draw without provoking a GL error")
    assertEquals(
        before,
        captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS),
        "the documented state must be identical before and after a draw",
    )

    val pixel = ByteArray(4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.readPixels(
        CONFORMANCE_SURFACE_PIXELS / 2, CONFORMANCE_SURFACE_PIXELS / 2, 1, 1,
        GL_RGBA, GL_UNSIGNED_BYTE, pixel,
    )
    assertEquals(0, pixel[0].toInt() and 0xff, "red channel")
    assertEquals(0, pixel[1].toInt() and 0xff, "green channel")
    assertEquals(255, pixel[2].toInt() and 0xff, "blue channel")
    assertEquals(255, pixel[3].toInt() and 0xff, "alpha channel")

    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    deleteCompositePipeline(binding, cache, composite)
    deleteOffscreenSurface(binding, surface)
    binding.deleteFramebuffers(1, intArrayOf(targetFramebuffer))
    binding.deleteTextures(1, intArrayOf(targetTexture))
    GlErrorQueue.drainOnEntry(binding)
}

private fun assertLifecycleUnderARealContext(
    binding: GlBinding,
    probe: RenderContextProbe,
    profile: RenderContextProfile,
) {
    val identity = assertNotNull(probe.currentContextIdentity(), "the fixture context must be current")
    val registry = GlObjectRegistry()
    val programs = GlProgramCache()
    val key = ResourceKeyDeriver().offscreenSurface(
        OffscreenSurfaceDescriptor(4, 4, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
    ).key

    val names = IntArray(1)
    binding.genFramebuffers(1, names)
    val deferredFramebuffer = names[0]
    registry.register(key, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, deferredFramebuffer)))
    val deletion = assertNotNull(registry.defer(key, DeletionId(1L)))

    val driver = GlLifecycleDriver(
        binding = binding,
        probe = probe,
        registry = registry,
        programs = programs,
        initialSnapshot = RendererLifecycleSnapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 0L,
            preparationActive = false,
            gpuLedger = GpuLedger(hasLiveGpuObjects = false, deferredDeletions = listOf(deletion)),
        ),
        initialContext = identity,
        initialProfile = profile,
    )

    val freed = driver.run(RendererLifecycleOperation.FreeResources(ResourceSelector.All)) { null }
    assertEquals(RendererLifecycleOutcome.Succeeded, freed)
    assertTrue(!binding.isFramebuffer(deferredFramebuffer), "a drained deletion really deletes")

    binding.genFramebuffers(1, names)
    val survivor = names[0]
    // Per the GL 4.1 spec section 9.2 and the GLES 3.0 spec section 4.4.4 (both "Framebuffer
    // Objects"): "A name returned by GenFramebuffers, but not yet associated with a framebuffer
    // object by calling BindFramebuffer, is not the name of a framebuffer object" -- so
    // isFramebuffer is specified to return GL_FALSE for a generated-but-never-bound name
    // regardless of what happens to it afterward. Binding it once, here, is what makes the name
    // "the name of a framebuffer object" at all, so the query below actually distinguishes a
    // survivor from a deleted object instead of reporting GL_FALSE unconditionally.
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, survivor)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    registry.register(key, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, survivor)))
    assertEquals(
        RendererLifecycleOutcome.Succeeded,
        driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null },
    )
    assertTrue(
        binding.isFramebuffer(survivor),
        "declared GPU object loss forgets handles without deleting them",
    )
    binding.deleteFramebuffers(1, intArrayOf(survivor))
    GlErrorQueue.drainOnEntry(binding)
}
