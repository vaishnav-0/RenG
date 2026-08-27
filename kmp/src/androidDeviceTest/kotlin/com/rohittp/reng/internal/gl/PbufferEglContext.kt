package com.rohittp.reng.internal.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/**
 * A headless GLES 3 context for Android instrumented tests, built from stock `android.opengl.EGL14`.
 * No cinterop, no NDK, no third-party anything — the platform ships every entry point this needs.
 *
 * **It is a pbuffer rather than a surfaceless context, and that is not a stylistic choice.** Linux's
 * `SurfacelessEglContext` cannot be transliterated here: `EGL_KHR_surfaceless_context` appears on
 * neither AOSP's mandatory nor its recommended extension list, so a portable Android context needs a
 * real drawable and a pbuffer is the smallest one. A given driver may advertise the extension —
 * Adreno 830 advertises `GL_OES_surfaceless_context` — but "this phone has it" is not "Android has
 * it", and the pbuffer costs nothing.
 *
 * Like every other context fixture in this repository it leaves the default framebuffer alone. The
 * suites render into framebuffer objects they create themselves; the pbuffer exists only so
 * `eglMakeCurrent` has something to bind.
 */
internal class PbufferEglContext private constructor(
    private val display: EGLDisplay,
    private val surface: EGLSurface,
    private val context: EGLContext,
) {
    /**
     * Identifies the context RenG's lifecycle layer must see current before it deletes a GL object
     * (ADR 0015). `EGLContext.nativeHandle` is the address EGL itself hands back, so two distinct
     * contexts in one process cannot collide.
     */
    internal val probe: RenderContextProbe = RenderContextProbe {
        val current = EGL14.eglGetCurrentContext()
        if (current == null || current == EGL14.EGL_NO_CONTEXT) null
        else RenderContextIdentity(current.nativeHandle)
    }

    internal fun destroy() {
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        EGL14.eglDestroyContext(display, context)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglTerminate(display)
    }

    internal companion object {
        /**
         * `EGL_OPENGL_ES3_BIT_KHR`. `EGL14` stops at `EGL_OPENGL_ES2_BIT`, and the ES3 bit lives in
         * `EGL_KHR_create_context`; the numeric value is the one the Khronos registry assigns.
         */
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

        internal fun create(widthPixels: Int = 512, heightPixels: Int = 512): PbufferEglContext {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay returned EGL_NO_DISPLAY" }

            val major = IntArray(1)
            val minor = IntArray(1)
            check(EGL14.eglInitialize(display, major, 0, minor, 0)) {
                "eglInitialize failed: ${EGL14.eglGetError()}"
            }
            check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                "eglBindAPI(EGL_OPENGL_ES_API) failed: ${EGL14.eglGetError()}"
            }

            val configAttributes = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display, configAttributes, 0, configs, 0, 1, configCount, 0,
                ) && configCount[0] > 0,
            ) { "no ES3 pbuffer EGLConfig: ${EGL14.eglGetError()}" }
            val config = requireNotNull(configs[0])

            val surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(
                    EGL14.EGL_WIDTH, widthPixels,
                    EGL14.EGL_HEIGHT, heightPixels,
                    EGL14.EGL_NONE,
                ),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) {
                "eglCreatePbufferSurface failed: ${EGL14.eglGetError()}"
            }

            val context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) {
                "eglCreateContext(ES 3) failed: ${EGL14.eglGetError()}"
            }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed: ${EGL14.eglGetError()}"
            }
            println("EGL ${major[0]}.${minor[0]} pbuffer ${widthPixels}x$heightPixels is current")
            return PbufferEglContext(display, surface, context)
        }
    }
}
