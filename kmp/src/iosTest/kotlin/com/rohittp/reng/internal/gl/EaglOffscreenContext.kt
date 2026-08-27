@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.objcPtr
import platform.EAGL.EAGLContext
import platform.EAGL.kEAGLRenderingAPIOpenGLES3

/**
 * A headless OpenGL ES 3.0 context on iOS, for the tests that need a real driver.
 *
 * This is the iOS sibling of `CglCoreProfileContext` on macOS and `SurfacelessEglContext` on Linux,
 * and like both of them it is test-only by design: RenG never creates, makes current, or destroys a
 * context and names EAGL nowhere in `commonMain` or `iosMain` (ADR 0001), so the only context code
 * this repository owns at all is a fixture, once per platform.
 *
 * **`platform.EAGL`, not `platform.OpenGLES`.** The Kotlin/Native distribution splits these and the
 * obvious guess is wrong: the `OpenGLES` klib carries package `platform.gles` and is ES **1.x**.
 * `EAGLContext` and `kEAGLRenderingAPIOpenGLES3` live in the `EAGL` klib as `platform.EAGL`, while
 * the 91 ES 3.0 entry points `IosGlBinding` imports live in the `OpenGLES3` klib as `platform.gles3`.
 * None of this needs cinterop, a third-party binary, or a line of `kmp/build.gradle.kts`: the default
 * hierarchy template already compiles `iosTest` for both `iosArm64` and `iosSimulatorArm64`.
 *
 * **No layer, no window, no drawable.** An `EAGLContext` acquires a default framebuffer only through
 * `renderbufferStorage:fromDrawable:` against a `CAEAGLLayer`, which a test process has no reason to
 * own. Framebuffer zero is therefore incomplete under this fixture — exactly as it is under the macOS
 * and Linux ones — and every suite that runs against it renders into a framebuffer object it creates
 * itself, which is what those suites already do on the other two targets.
 *
 * **Apple deprecated OpenGL ES in iOS 12, in 2018, and it still functions.** Measured on iOS 26.0,
 * 26.2, 26.4.2 and 26.5, across three simulator runtimes and an iPhone 12
 * (`docs/research/2026-08-24-h-ios-gles-context-spike.md` and its device addendum). Nothing will warn
 * about the deprecation either: cinterop does not translate Apple's `API_DEPRECATED` into Kotlin's
 * `@Deprecated`, so the toolchain is silent and this comment is the only notice there is.
 *
 * **What this fixture says nothing about.** It supplies a context, not a GPU. The simulator's driver
 * is `Apple Software Renderer` and answers pixel questions differently from the hardware — see
 * [IosGlConformanceTest] for the numbers and for which case that costs. Presenting to a real
 * `CAEAGLLayer` is never exercised, because RenG draws onto a caller-supplied surface and owns no
 * window; whether that path still works on iOS 26 is untested here and is not RenG's concern.
 */
internal class EaglOffscreenContext private constructor(
    /**
     * Held only so the context outlives [create]. Kotlin/Native releases an Objective-C object when
     * its last Kotlin reference dies, which would tear down the very context the tests are still
     * drawing on. [probe] deliberately does *not* read this field: it reads `currentContext()`, so
     * it reports what is actually current rather than what this fixture once created — which is the
     * distinction [GlLifecycleDriver]'s context checks exist to make.
     */
    @Suppress("unused") private val context: EAGLContext,
) {
    internal val probe: RenderContextProbe = RenderContextProbe {
        EAGLContext.currentContext()?.let { RenderContextIdentity(it.objcPtr().toLong()) }
    }

    internal fun destroy() {
        EAGLContext.setCurrentContext(null)
    }

    internal companion object {
        /**
         * Creates an ES 3.0 context, makes it current, or throws.
         *
         * There is no `createOrNull` counterpart, and the asymmetry with `CglCoreProfileContext` is
         * deliberate. macOS needs one because its software renderer genuinely may be absent and
         * skipping is then the honest answer. Here there is one driver and every iOS runtime measured
         * supplies it, so a refusal is a finding about the platform and must fail the run loudly
         * rather than quietly reduce a suite to nothing.
         */
        internal fun create(): EaglOffscreenContext {
            val context = EAGLContext(kEAGLRenderingAPIOpenGLES3)
            check(EAGLContext.setCurrentContext(context)) {
                "EAGLContext(kEAGLRenderingAPIOpenGLES3) would not become current on this runtime"
            }
            checkNotNull(EAGLContext.currentContext()) {
                "setCurrentContext reported success but currentContext() is null"
            }
            return EaglOffscreenContext(context)
        }
    }
}
