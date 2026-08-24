# Cycle H against the vendors' own words — what Apple, Google and JetBrains actually state

Compiled 2026-08-24 on `docs/cycle-b-resource-contract` at `54839dc`, read-only, no code changed. Every
claim below carries a citation: a URL to primary documentation, or a path in the local toolchain, which
JetBrains explicitly designates as the place to look
([Kotlin/Native platform libraries](https://kotlinlang.org/docs/native-platform-libs.html) names both
`~/.konan/kotlin-native-prebuilt-[os]-[arch]-[version]/klib/platform/` and `konan/platformDef`). Claims I
could not source are gathered in "Unsourced" at the end rather than smuggled into the body.

Two things are deliberately **not** here: a live spike (a sibling agent is booting things) and a survey of
developer bug reports (another sibling agent). Where a forum thread is the only Apple-hosted statement on a
question, it is quoted and labelled as a forum thread, not as documentation.

## Summary answer — the three facts most likely to decide the cycle's shape

**1. The iOS GLES 3 context is reachable from stock klibs, exactly like macOS's CGL one, and the
deprecation is invisible to the build.** `platform.EAGL` ships in *both* `ios_arm64` and
`ios_simulator_arm64` in the pinned Kotlin/Native 2.3.21 distribution, exposing
`EAGLContext(aPI: ULong)`, `EAGLContext.Companion.setCurrentContext(...)` and the constant
`kEAGLRenderingAPIOpenGLES3 = 3uL`. The `OpenGLES3` klib metadata dumps for device and simulator are
**byte-identical**. And there is not one `@Deprecated` annotation carried over from Apple's
`API_DEPRECATED` in either klib — so `EAGLContext(kEAGLRenderingAPIOpenGLES3)` compiles silently, forever,
with no warning anyone will ever see. The preflight's one guess ("whether that klib exposes what is needed
was not verified") resolves in favour of the cheap answer, and the deprecation therefore has to be recorded
in an ADR because the compiler will never record it.

**2. The iOS 26.2 simulator runtime's only OpenGL ES renderer bundle is `GLRendererFloat.bundle`, whose
binary contains the literal string `Apple Software Renderer`** — the exact renderer that failed `0.3.0`'s
first publication attempt on the hosted macOS runner. The host Mac's GL stack has
`AppleMetalOpenGLRenderer.bundle` and vendor `*GLDriver.bundle`s alongside it; the simulator runtime has
neither. Static inspection cannot prove which renderer a *booted* simulator selects — that is the spike's
question — but the design should assume a software rasteriser on the simulator until measured otherwise,
and `measureLargeQuadRasterisation` is already the instrument for it rather than something new to build.

**3. Android's instrumented source set is `androidDeviceTest`, not `androidInstrumentedTest`, it is opt-in,
and the emulator's host GLES on Apple silicon is ANGLE-over-Metal.** AGP 9.3.1's
`KotlinMultiplatformAndroidLibraryExtension` declares `withDeviceTest {}` / `withDeviceTestBuilder {}`
beside the `withHostTest {}` the build already calls, and the device-test DSL carries Gradle Managed
Devices with it. Meanwhile the installed emulator ships `lib64/gles_angle/libGLESv2.dylib` — an arm64
Mach-O containing `ANGLE Metal Renderer`, `DisplayMtl`, `ContextMtl`. So Android bring-up introduces a
*fourth* rasteriser, and the headless path there must be a **pbuffer**, not the surfaceless context the
Linux fixture uses, because surfaceless is not on AOSP's mandatory extension list.

---

## iOS

### 1. OpenGL ES's official status on iOS today

**Deprecated since iOS 12.0. Never marked removed, obsoleted or unavailable, on any platform, in any
Apple document I could find.**

Apple's API reference for
[`EAGLContext`](https://developer.apple.com/documentation/opengles/eaglcontext) carries availability
`iOS 2.0 → deprecated 12.0`, `iPadOS 2.0 → deprecated 12.0`, `Mac Catalyst 2.0 → deprecated 12.0`,
`tvOS 9.0 → deprecated 12.0`, with `unavailable: false` on every one. The deprecation message is verbatim:

> OpenGLES API deprecated. (Define GLES_SILENCE_DEPRECATION to silence these warnings)

The [OpenGL ES framework page](https://developer.apple.com/documentation/opengles) repeats the same
availability and adds `visionOS 1.0 → deprecated 1.0` — deprecated in the same release it was introduced.
Every page of the (retired) programming guide carries the standing banner:

> **Important:** OpenGL ES was deprecated in iOS 12. To create high-performance code on GPUs, use the
> Metal framework instead.
> — [OpenGL ES Programming Guide](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/Introduction/Introduction.html)

**The local toolchain says the same thing and proves the framework is still shipped.** Xcode 26.6 (build
17F113) is installed. In `iPhoneSimulator26.5.sdk`, `OpenGLES.framework/Headers/EAGL.h:53` and `:69` annotate
`EAGLSharegroup` and `EAGLContext` with `OPENGLES_DEPRECATED(ios(2.0, 12.0), tvos(9.0, 12.0))`, and
`OpenGLESAvailability.h:10` expands that macro to
`API_DEPRECATED("OpenGLES API deprecated. (Define GLES_SILENCE_DEPRECATION to silence these warnings)", ...)`.
The ES3 entry points carry the same treatment — `ES3/gl.h:877`:

```c
GL_API void GL_APIENTRY glReadPixels (GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid* pixels) OPENGLES_DEPRECATED(ios(3.0, 12.0), tvos(9.0, 12.0));
```

Deprecated, not gone. `OpenGLES.framework` is present in **both** SDKs shipped with Xcode 26.6, and its
`.tbd` exports the whole GLES entry-point set:

- `iPhoneSimulator26.5.sdk/.../OpenGLES.framework/OpenGLES.tbd` — `targets: [ x86_64-ios-simulator, arm64-ios-simulator ]`,
  exporting `_EAGLGetVersion`, `_GLIContextFromEAGLContext`, `_glBindFramebuffer`, `_glBlitFramebuffer`,
  `_glBindVertexArray`, `_glBindSampler`, … 
- `iPhoneOS26.5.sdk/.../OpenGLES.tbd` — `targets: [ arm64e-ios ]`.

*Checked and dismissed:* the device `.tbd` naming only `arm64e-ios` looked at first like it might exclude
Kotlin/Native's plain-`arm64` `iosArm64` output. It does not distinguish OpenGLES — `Metal.tbd`,
`UIKit.tbd`, `Foundation.tbd` and `GLKit.tbd` in the same SDK all declare exactly `targets: [ arm64e-ios ]`.
It is how that whole SDK is stamped, not a signal about OpenGL ES.

**The runtimes ship it too.** `xcrun simctl list runtimes` shows iOS 26.0 (23A343), 26.2 (23C54) and 26.5
(23F77) installed. Inside the 26.2 runtime root
(`/Library/Developer/CoreSimulator/Volumes/iOS_23C54/…/iOS 26.2.simruntime/Contents/Resources/RuntimeRoot`)
sits `System/Library/Frameworks/OpenGLES.framework`, `CFBundleShortVersionString 23.0.2`, containing the
`OpenGLES` dispatch binary, `GLEngine.bundle`, `GLRendererFloat.bundle`, `libGLProgrammability.dylib`,
`libGLImage.dylib`, `libGLVMPlugin.dylib` and `libLLVMContainer.dylib`.

**On removal, Apple is silent, and the silence is the finding.** The
[iOS & iPadOS 26 release notes](https://developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-26-release-notes)
contain no occurrence of "OpenGL", "OpenGLES", "EAGL" or "GLKit" — the graphics entries there are all
Metal 4. Apple has published a deprecation and nothing since. Deprecated-and-still-shipped for fourteen
major releases is a fact; a commitment to keep shipping it is not something Apple has written down.

### 2. What Apple says a caller must do to get a GLES 3 context

Apple's normative text is archived and retired (last updated 2018-06-04) but is the only text there is.
[Configuring OpenGL ES Contexts](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/WorkingwithOpenGLESContexts/WorkingwithOpenGLESContexts.html)
states the three rules that matter:

> To set a thread's current context, call the `EAGLContext` class method `setCurrentContext:` when
> executing on that thread.

> Your app decides which version of OpenGL ES to support when it creates and initializes the `EAGLContext`
> object. If the device does not support the requested version of OpenGL ES, the `initWithAPI:` method
> returns `nil`.

> …all contexts associated with the same sharegroup must use the same version of the OpenGL ES API as the
> initial context.

So: construct with `initWithAPI:` and `kEAGLRenderingAPIOpenGLES3`, check for `nil` rather than assuming,
make current per thread. That maps one-to-one onto what the stock klib exposes (section 4).

**Is a headless, framebuffer-only context supported and documented?** Partly, and the distinction matters.
Apple documents *offscreen framebuffers* explicitly.
[Drawing to Other Rendering Destinations](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/WorkingwithEAGLContexts/WorkingwithEAGLContexts.html):

> A framebuffer intended for offscreen rendering allocates all of its attachments as OpenGL ES
> renderbuffers.

> After drawing to an offscreen renderbuffer, you can return its contents to the CPU for further
> processing using the `glReadPixels` function.

That is precisely RenG's readback shape: colour + depth renderbuffers, `glReadPixels`, no presentation. The
drawable path is the *other* case — a `CAEAGLLayer` bound with
`[myContext renderbufferStorage:GL_RENDERBUFFER fromDrawable:myEAGLLayer]` — and Apple treats the two as
alternatives, not as a prerequisite and an extra.

What Apple does **not** state anywhere I found is the stronger claim: that an `EAGLContext` with no
drawable ever attached is supported. What it documents is that a *framebuffer* need not be backed by a
drawable. Note also that `CAEAGLLayer` lives in QuartzCore, not in the EAGL framework — in Kotlin/Native it
is `platform.QuartzCore.CAEAGLLayer`, implementing `platform.EAGL.EAGLDrawableProtocol` — so an offscreen
fixture never touches QuartzCore or UIKit at all. Treat "context with no layer works" as very likely and
unproven-by-documentation; it is exactly the spike's first assertion.

### 3. What Apple recommends instead, and what Apple actually commits to

**Metal, and only Metal.** The recommendation is one sentence, repeated identically on every OpenGL ES
page: *"To create high-performance code on GPUs, use the Metal framework instead."*

The migration material Apple provides is
[Migrating OpenGL code to Metal](https://developer.apple.com/documentation/Metal/migrating-opengl-code-to-metal),
whose abstract is "Replace your app's deprecated OpenGL code with Metal", associated with
[WWDC 2019 session 611](https://developer.apple.com/videos/play/wwdc2019/611/). That is a sample project
and a video. There is no shim, no compatibility layer, no source translator, and no automated path.

**MetalANGLE is not Apple's and Apple names it nowhere.** It is a third-party fork
([kakashidinho/metalangle](https://github.com/kakashidinho/metalangle)) whose Metal ES 3.0 work was merged
into Google's ANGLE upstream. See section 9.

Stated precisely: **Apple commits to nothing in writing beyond the deprecation itself.** What Apple has
done — as opposed to promised — is keep the framework in every SDK and every simulator runtime through
iOS 26.5, and keep the API reference online without an obsoletion marker. That is evidence of continued
shipping, not a support statement, and a design document should say it that way.

### 4. Kotlin/Native's stock iOS klibs

JetBrains' documented model
([platform libraries](https://kotlinlang.org/docs/native-platform-libs.html)):

> The packages from platform libraries are available by default. You don't need to specify additional link
> options to use them. The Kotlin/Native compiler automatically detects which platform libraries are
> accessed and links the necessary ones.

> Platform libraries in the compiler distribution are merely wrappers and bindings to the native libraries.

> Kotlin/Native provides bindings for various popular native libraries that are commonly used on different
> platforms, such as OpenGL, zlib, and Foundation.

The docs point at the distribution as the enumeration, so here is the enumeration. Kotlin is pinned at
**2.3.21** (`gradle/libs.versions.toml:3`); the distribution is
`~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21`, whose `konan/konan.properties` records
`sdkVersion.ios = 26.0`, toolchain `xcode_26.0.1_17A400`, and `minVersion.ios = 14.0`.

GL-related klibs present per target:

| target | klibs |
|---|---|
| `ios_arm64` | `EAGL`, `GLKit`, `OpenGLES`, `OpenGLES2`, `OpenGLES3`, `OpenGLESCommon`, plus `Metal`, `MetalFX`, `MetalKit`, `MetalPerformanceShaders`, `MetalPerformanceShadersGraph`, `CoreVideo` |
| `ios_simulator_arm64` | identical set (no `CoreVideo` in the GL-name filter) |
| `macos_arm64` | `OpenGL`, `OpenGL3`, `OpenGLCommon`, `GLKit`, `GLUT`, Metal family — **no `EAGL`, no `OpenGLES*`** |

That last row is ADR 0022's measured asymmetry, confirmed from the shipped artifacts rather than from a
compile: the iOS and macOS GL bindings can never share a source set.

Package names come from the `.def` files in `konan/platformDef/ios_simulator_arm64/`:

```
EAGL.def         package = platform.EAGL
                 headers = OpenGLES/EAGL.h OpenGLES/EAGLDrawable.h
                 compilerOpts/linkerOpts = -framework OpenGLES

OpenGLES3.def    package = platform.gles3
                 depends = … EAGL … OpenGLESCommon …
                 headers = OpenGLES/ES3/gl.h OpenGLES/ES3/glext.h
```

`platform.gles3` is what `kmp/src/iosMain/kotlin/com/rohittp/reng/internal/gl/IosGlBinding.kt` already
imports, so the binding and the context fixture would sit on the same generated wrapper.

**The EAGL klib's exact usable surface** (`klib dump-metadata` on
`klib/platform/ios_simulator_arm64/org.jetbrains.kotlin.native.platform.EAGL`):

- `EAGLContext(aPI: ULong)` and `EAGLContext(aPI: ULong, sharegroup: EAGLSharegroup)` — secondary
  constructors over `initWithAPI:` / `initWithAPI:sharegroup:`
- `EAGLContext.Companion.setCurrentContext(context: EAGLContext?): Boolean` and `currentContext(): EAGLContext?`
- properties `API: ULong`, `sharegroup: EAGLSharegroup`, `debugLabel: String?`, `multiThreaded: Boolean`
- extensions `renderbufferStorage(target, fromDrawable:)`, three `presentRenderbuffer(...)` overloads
- constants `kEAGLRenderingAPIOpenGLES1 = 1uL`, `kEAGLRenderingAPIOpenGLES2 = 2uL`,
  **`kEAGLRenderingAPIOpenGLES3 = 3uL`**, `kEAGLColorFormatRGBA8`, `kEAGLColorFormatSRGBA8`,
  `kEAGLDrawablePropertyColorFormat`, `kEAGLDrawablePropertyRetainedBacking`, `EAGLGetVersion(...)`
- `typealias EAGLRenderingAPI = platform.darwin.NSUInteger` (i.e. `ULong`)

`EAGLContext` is a `platform.darwin.NSObject` subclass, so it needs no cinterop, no `.def`, and no linker
flag from RenG — the framework link comes from the klib.

The `OpenGLES3` metadata dumps for `ios_arm64` and `ios_simulator_arm64` are **byte-identical** (`diff`
returns nothing across 3,099 lines), and both include `glGenFramebuffers`, `glBindFramebuffer`,
`glCheckFramebufferStatus`, `glGenRenderbuffers`, `glRenderbufferStorage`, `glFramebufferRenderbuffer`,
`glBlitFramebuffer`, `glReadPixels`, `glTexImage2D`, `glGetString`, `glGetError`. Any device-versus-simulator
difference RenG hits therefore cannot originate in the bindings.

**One finding here is not in any document — it is in the artifacts.** Kotlin/Native's cinterop does **not**
translate Apple's `API_DEPRECATED` into Kotlin's `@Deprecated`. The `OpenGLES3` klib dump contains zero
occurrences of `Deprecated` across all 3,099 lines. The `EAGL` dump's only `@Deprecated` annotations are
Kotlin/Native's own `"Use constructor instead"` / `"Use constructor or factory method instead"` markers on
`init`/`alloc`. Consequence for the cycle: **nothing in RenG's build will ever warn that iOS GL is
deprecated.** `GLES_SILENCE_DEPRECATION` is irrelevant because there is no warning to silence. The
deprecation has to live in an ADR, in `CLAUDE.md`, and in release notes, or it lives nowhere. (Labelled as
measured-here in "Unsourced" below — JetBrains documents no policy either way.)

Finally, source-set placement is JetBrains-documented and needs no invention: the
[default hierarchy template](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
creates `iosMain` / `appleMain` / `nativeMain` for `iosArm64` + `iosSimulatorArm64`, and states plainly that
"everything is the same for `*Test` sources as well" — so `iosTest` is the mirror of the existing
`macosTest` and `linuxTest`, which is precisely where `CglCoreProfileContext.kt` and
`SurfacelessEglContext.kt` already live.

### 5. iOS simulator GPU support

**Apple publishes no current statement about OpenGL ES in the simulator.** The only page on the subject,
[OpenGL ES on iOS Simulator](https://developer.apple.com/library/archive/documentation/OpenGLES/Conceptual/OpenGLESHardwarePlatformGuide_iOS/OpenGLESiniOSSimulator/OpenGLESiniOSSimulator.html),
is marked **Retired Document, last updated 2018-06-04**, predates Apple silicon, and describes only ES 1.1
and 2.0:

> iOS Simulator includes complete and conformant implementations of both OpenGL ES 1.1 and OpenGL ES 2.0.

> Simulator provides an optimized software rasterizer that takes advantage of the vector-processing
> capabilities of your Macintosh computer.

> Rendering performance of OpenGL ES in Simulator has no relation to the performance of OpenGL ES on an
> actual device… Always profile and optimize your drawing code on a real device, and never assume that
> Simulator reflects real-world performance.

Apple's *current* simulator-GPU documentation is Metal-only and does not mention OpenGL ES at all.
[Developing Metal apps that run in Simulator](https://developer.apple.com/documentation/metal/developing-metal-apps-that-run-in-simulator):

> Simulator doesn't try to exactly simulate the GPU from the iOS, tvOS, or visionOS device you're
> simulating… Instead, Simulator translates any calls you make and directs them to the selected GPU on the
> host Mac.

> Simulator provides a device instance with capabilities similar to an Apple family 2 GPU…

That sentence is about `MTLDevice`. Extending it to OpenGL ES would be an inference, and the whole point of
this document is not to make those.

**So the evidence that GLES 3 exists on the simulator at all is the shipped SDK and runtime, not prose:**
`ES3/gl.h` and `ES3/glext.h` are in `iPhoneSimulator26.5.sdk`; `OpenGLES.tbd` exports the ES3 entry points
for `arm64-ios-simulator`; and the iOS 26.2 runtime root ships `OpenGLES.framework` with `GLEngine.bundle`,
whose binary carries the full `GL_APPLE_*` extension string table (`GL_APPLE_framebuffer_multisample`,
`GL_APPLE_clip_distance`, `GL_APPLE_texture_packed_float`, …).

**And the one renderer bundle inside that runtime is the software one.** The runtime's OpenGLES framework
contains exactly one renderer plug-in, `GLRendererFloat.bundle`, and `strings` on its binary yields:

```
Apple Software Renderer
GLRendererFloat
```

For contrast, the *host* macOS GL stack in `/System/Library/Extensions/` carries
`AppleMetalOpenGLRenderer.bundle`, `AMDRadeonX6000GLDriver.bundle`, `AppleIntelICLGraphicsGLDriver.bundle`
and friends. The simulator runtime carries none of those — its `System/Library/Extensions/` holds three
non-graphics kexts (`AppleHIDALSService.kext`, `IOHIDFamily.kext`, `EAP-RSA.ppp`).

Static inspection cannot prove renderer *selection* — a booted simulator is a native macOS process and may
resolve libraries from the host as well as from the runtime root, and no path string in the runtime's
`OpenGLES`, `libGLImage.dylib` or `GLEngine` binaries names a renderer bundle. What it establishes is: the
software renderer is present in the runtime, no hardware GL driver is, and `Apple Software Renderer` is a
plausible — arguably the default — answer.

**That matters more to RenG than to most projects.** `CLAUDE.md` records that `0.3.0`'s first publication
attempt failed on a hosted macOS runner running `Apple Software Renderer`, whose rasteriser drops
far-off-viewport quads — the shape of every ground tile — leaving 3,005 of 15,876 interior pixels
untouched, and that `measureLargeQuadRasterisation`
(`kmp/src/nativeTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt:669`) now measures 0 disagreeing
pixels on M3 Max against 2,112 on `Apple Software Renderer`, budget 512. If the simulator selects that
renderer, the guard already written is the guard needed, and the readback suites will skip one case loudly
rather than fail — which is a designed outcome, not a surprise.

**Apple's one acknowledgement of GLES trouble on Apple silicon simulators is a forum thread, not
documentation.** On [developer.apple.com/forums/thread/756598](https://developer.apple.com/forums/thread/756598),
an Apple DTS engineer replied in June 2024 to reports of `glDrawElements` crashing on Apple silicon
simulators:

> This appears to be a widespread issue. Nonetheless, it's good that this is limited to the Simulator and
> doesn't affect actual device. Please take a moment to file a bug report in the Feedback Assistant and
> we'll investigate.

Flagged, not analysed: it is a forum thread and the developer-report survey belongs to a sibling agent. But
its status is worth naming — Apple acknowledged a simulator-only GLES defect and did not declare GLES
unsupported in the simulator, and the thread shows no posted resolution as of March 2025.

---

## Android

### 6. EGL and GLES 3 on Android, and the headless path

**Version reach is not in question.** [OpenGL ES on Android](https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl)
maps versions to API levels: GLES 2.0 from API 8, **3.0 from API 18, 3.1 from API 21, 3.2 from API 24**,
exposed as `android.opengl.GLES20` / `GLES30` / `GLES31` / `GLES32`. RenG's `minSdk = 30`
(`kmp/build.gradle.kts`) clears all of them, and `AndroidGlBinding.kt` targets `GLES30` per ADR 0009.

**`EGL14` is the whole context path; `EGLExt` contributes two constants.** From the installed
`~/Library/Android/sdk/platforms/android-37.0/android.jar` (`compileSdk = 37`):

`android.opengl.EGL14` declares everything a headless context needs —
`eglGetDisplay(int)`, `eglInitialize`, `eglChooseConfig`, `eglCreateContext`,
**`eglCreatePbufferSurface(EGLDisplay, EGLConfig, int[], int)`**, `eglMakeCurrent`, plus
`EGL_DEFAULT_DISPLAY`, `EGL_SURFACE_TYPE`, **`EGL_PBUFFER_BIT`**, `EGL_RENDERABLE_TYPE`,
`EGL_OPENGL_ES2_BIT`, `EGL_CONTEXT_CLIENT_VERSION`, `EGL_WIDTH`, `EGL_HEIGHT`, `EGL_NO_SURFACE`.

`android.opengl.EGLExt` declares only nine constants and two methods in total:

```
EGL_CONTEXT_FLAGS_KHR, EGL_CONTEXT_MAJOR_VERSION_KHR, EGL_CONTEXT_MINOR_VERSION_KHR,
EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID,
EGL_SYNC_NATIVE_FENCE_ANDROID, EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_SYNC_NATIVE_FENCE_SIGNALED_ANDROID
eglPresentationTimeANDROID(...), eglDupNativeFenceFDANDROID(...)
```

So the split is sharp and worth writing down before anyone guesses: **`EGL14` for every call, `EGLExt`
only for `EGL_OPENGL_ES3_BIT_KHR` in the config attribute list** (and optionally
`EGL_CONTEXT_MAJOR_VERSION_KHR`/`MINOR` instead of `EGL_CONTEXT_CLIENT_VERSION`). `EGLExt` has no
`eglCreateContext` of its own.

**Pbuffer, not surfaceless.** A pbuffer is core EGL: `eglCreatePbufferSurface` and `EGL_PBUFFER_BIT` are
`EGL14` members, hence present on every device at RenG's `minSdk`. A *surfaceless* context —
`eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)`, which is what the Linux fixture does via
`EGL_PLATFORM_SURFACELESS_MESA` (`kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/SurfacelessEglContext.kt:29`)
— depends on `EGL_KHR_surfaceless_context`, and AOSP does **not** list it as required.
[Implement OpenGL ES and EGL](https://source.android.com/docs/core/graphics/implement-opengl-es) names the
mandatory set:

> Support the mandatory extensions `EGL_KHR_wait_sync`, `GL_OES_texture_external`,
> `EGL_ANDROID_image_native_buffer`, and `EGL_ANDROID_recordable`.

with `EGL_ANDROID_blob_cache`, `EGL_KHR_fence_sync` and `EGL_ANDROID_native_fence_sync` merely "highly
recommended". `EGL_KHR_surfaceless_context` appears in neither list. **The Android fixture therefore cannot
be a transliteration of the Linux one** — a 1×1 pbuffer is the documented-safe equivalent, and RenG draws
into its own offscreen surface anyway (ADR 0005), so the pbuffer is only there to make the context
current.

One more thing the same AOSP page says, which bears on how a device test should be written:

> …drivers for EGL, OpenGL ES 1.x, and OpenGL ES 2.0. **Support for OpenGL ES 3.x is optional.**

GLES 3 is a device capability, not a platform guarantee. A device test must query it (via
`EGL_OPENGL_ES3_BIT_KHR` config selection failing, or `<uses-feature android:glEsVersion="0x00030000">`
semantics described on the [OpenGL ES guide](https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl))
and fail or skip out loud — the same discipline `measureLargeQuadRasterisation` already applies to
rasteriser quality.

### 7. Android emulator GPU on Apple silicon

**Google's documentation does not state a GLES version for the emulator, and by design cannot.** The
[graphics acceleration page](https://developer.android.com/studio/run/emulator-acceleration) lists the
`-gpu` modes: `auto` (recommended), `host` ("Use the GPU on your computer for hardware acceleration…can
enable MoltenVK on macOS for accelerated Vulkan rendering"), `software`, `lavapipe` (Mesa), `swiftshader`,
`swangle` — with `mesa`, `angle`, `angle_indirect`, `guest`, `swiftshader_indirect` and `swangle_indirect`
now deprecated. It gives no GLES version numbers.

The reason is in the emulator's own shipped configuration. `~/Library/Android/sdk/emulator/lib/advancedFeatures.ini`
documents the mechanism verbatim:

```
# GLESDynamicVersion------------------------------------------------------------
# This feature attempts to detect the maximum supported GLES version depending on
# which OpenGL function pointers have been found on the GL libraries used
# on the host system. Different platforms / hardware + video driver setups can
# have different support.
# For example, OS X is not known to support GLES 3.1.
# If this feature is set to "off", the max supported GLES version is assumed to
# be <= 2 and also depend on the system image only (some images only support ES 1).
```

**The guest's maximum GLES version is a property of the host GL stack, discovered at boot.** The installed
ARM64 image turns it on: `system-images/android-37.0/google_apis_playstore_ps16k/arm64-v8a/advancedFeatures.ini`
sets `GLESDynamicVersion = on`, `GLDirectMem = on`, `GLAsyncSwap = on`, `GLDMA = on`, and its
`source.properties` records `SystemImage.Abi=arm64-v8a`, `AndroidVersion.ApiLevel=37.0` and
**`SystemImage.GpuSupport=true`**.

**And on Apple silicon that host GL stack is ANGLE over Metal.** Emulator 37.1.11 (build 15917651) ships,
in `~/Library/Android/sdk/emulator/lib64/gles_angle/`:

```
libEGL.dylib                     517 KB   (arm64)
libGLESv2.dylib                 14.2 MB   (arm64)
libswiftshader_libEGL.dylib
libswiftshader_libGLESv2.dylib
libvk_swiftshader.dylib
```

`lipo -info` reports `libGLESv2.dylib` as `arm64`, and `strings` on it yields `ANGLE Metal Renderer`,
`ANGLE (`, `DisplayMtl`, `ContextMtl` (and `RendererVk` for the Vulkan backend it also carries).
Alongside, `lib64/vulkan/` holds `libMoltenVK.dylib`, `libvulkan_lvp.dylib` (lavapipe) and
`libvk_swiftshader.dylib`.

So, grounded rather than assumed: an ARM64 emulator on this machine gets **hardware-backed GLES through
ANGLE's Metal backend** in the default/host path, and software-backed GLES through SwiftShader (or Vulkan
through lavapipe/SwiftShader) when forced. Google's release notes corroborate the software direction —
36.4.9 (Feb 10, 2026) "Enabled Lavapipe as the default graphics software renderer to improve Vulkan
compatibility across all platforms"
([emulator release notes](https://developer.android.com/studio/releases/emulator)).

**Cycle consequence:** Android bring-up puts RenG's readback tolerances in front of a rasteriser it has
never met — ANGLE-over-Metal — distinct from Apple's GL driver on M3 Max, from llvmpipe on Linux CI, and
from Apple Software Renderer. The preflight's last open risk ("a third and fourth rasteriser is precisely
where that risk lives") is confirmed to be exactly that, and the `-gpu` flag is the knob that decides which
one the gate measures.

### 8. Kotlin Multiplatform's Android instrumented-test story

**The source set is `androidDeviceTest`, not `androidInstrumentedTest`.**
[Set up the Android Gradle library plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin)
documents that the plugin creates `androidMain`, `androidHostTest` (replacing legacy `src/test`) and
`androidDeviceTest` (replacing legacy `src/androidTest`), that **tests are disabled by default** and must be
opted into with `withHostTestBuilder {}` / `withDeviceTestBuilder {}` (or the direct `withHostTest {}` /
`withDeviceTest {}` forms), and that the DSL block is `android { }` from AGP 8.12.0 (it was
`androidLibrary { }` below that). It also enumerates what the plugin does not support: build types and
product flavors, data/view binding, `externalNativeBuild`, and `BuildConfig`; Android resources are
opt-in; minimums are AGP 8.10.0 and KGP 2.0.0.

Confirmed against the local AGP the build actually resolves — `agp = "9.3.1"`
(`gradle/libs.versions.toml:2`), jar at
`~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle-api/9.3.1/…/gradle-api-9.3.1.jar`.
`com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension` declares exactly four test entry
points:

```
void withHostTest(Function1<KotlinMultiplatformAndroidHostTest, Unit>)
HasConfigurableValue<KotlinMultiplatformAndroidHostTest> withHostTestBuilder(Function1<KotlinMultiplatformAndroidCompilationBuilder, Unit>)
void withDeviceTest(Function1<KotlinMultiplatformAndroidDeviceTest, Unit>)
HasConfigurableValue<KotlinMultiplatformAndroidDeviceTest> withDeviceTestBuilder(Function1<KotlinMultiplatformAndroidCompilationBuilder, Unit>)
```

RenG calls the first and none of the others (`kmp/build.gradle.kts`, `withHostTest {}`), which is exactly
why `kmp/src/` has `androidHostTest` and nothing else — the preflight's "there is nothing to run" has a
one-line cause.

`KotlinMultiplatformAndroidDeviceTest` exposes `instrumentationRunner`, `instrumentationRunnerArguments`,
`applicationId`, `targetSdk { }`, `animationsDisabled`, `enableCoverage`, `execution`, `installation { }`,
`signing { }`, `multidex { }`, `emulatorControl { }` and **`managedDevices { }`**. That last one is the
officially supported way to put an emulator in the loop without a pre-booted AVD:
`ManagedDevices.getLocalDevices()` is a `NamedDomainObjectContainer<ManagedVirtualDevice>`, and
`ManagedVirtualDevice` carries `device`, `apiLevel`, `sdkVersion`, `systemImageSource`, `require64Bit`,
`testedAbi` and `pageAlignment`.

Two consequences for the cycle's shape:

- Adding device tests creates `kmp/src/androidDeviceTest/`, which is Kotlin under `kmp/src` — one of the
  two locations `tools/check_repository_policy.py` permits Kotlin in — so the policy checker is not an
  obstacle.
- Gradle Managed Devices give a *declared* emulator (image source, API level, ABI) rather than an ambient
  one. Given section 7, the declaration is where the rasteriser gets pinned, and it should be treated as
  part of the gate's definition rather than as machine setup.

---

## Cross-cutting

### 9. ANGLE, taken seriously

**What it officially is.** From [ANGLE's README](https://github.com/google/angle/blob/main/README.md):

> The goal of ANGLE is to allow users of multiple operating systems to seamlessly run WebGL and other
> OpenGL ES content by translating OpenGL ES API calls to one of the hardware-supported APIs available for
> that platform.

It implements OpenGL ES 2.0, 3.0 and 3.1 as complete across its backends, with ES 3.2 in progress on most
and certified on Vulkan ("OpenGL ES 3.2: ANGLE 2.1.2.21688.59f158c1695f (Sept, 2023)").

**What it supports on iOS.** The README's backend table gives **Metal: complete on iOS**, footnoted "Metal
supported on iOS 12+", and no other backend covers iOS — no Vulkan, no GL ES, no Desktop GL row for that
column. (On macOS both Metal, complete for 10.14+, and Desktop GL / GL ES are listed.)

**But ANGLE's own build documentation does not back that up as a shippable iOS product.**
[ANGLE Development](https://chromium.googlesource.com/angle/angle/+/main/doc/DevSetup.md) introduces the
project with:

> You can use these to build and run OpenGL ES applications on Windows, Linux, Mac and Android.

iOS is absent from that sentence. The iOS section then says:

> This is currently possible only from Chromium checkout. Follow Chromium for iOS build instructions.

with GN args `target_os = "ios"`, `target_environment = "simulator"`, `target_cpu = "x64"`,
`is_component_build = false`, and:

> Building `angle_end2end_tests` and `angle_white_box_tests` targets is supported.

Test targets, simulator, x64, from a full Chromium checkout. **Two official Google sources therefore
disagree about what "iOS: complete" means** — the README describes backend feature coverage, the build docs
describe what you can actually produce, and only the second one is a delivery path. That conflict is itself
the finding.

**Distribution.** No prebuilt binaries are offered by the project. Building requires Python 3, depot_tools
(which supplies GN and ninja), and Xcode; the output is `libEGL` / `libGLESv2`. There is no Maven,
CocoaPods, SwiftPM or XCFramework artifact published by ANGLE.
[MetalANGLE](https://github.com/kakashidinho/metalangle) is the third-party predecessor whose Metal ES 3.0
implementation was merged upstream; third-party prebuilt iOS binaries exist
(e.g. [nutiteq/angle-metal](https://github.com/nutiteq/angle-metal)) but are not Google artifacts and carry
no vendor support.

**What depending on it would mean for RenG specifically.** RenG today has no cinterop and no third-party
native binary on any target: ADR 0009 rejects hand-written cinterop against the Apple SDK as dangerous, and
ADR 0022 keeps all four GL implementations on stock platform klibs and `dlsym`. Adopting ANGLE on iOS would
require, at minimum:

1. a binding to ANGLE's `libEGL`/`libGLESv2` — a `.def` and a cinterop task, or a `dlsym` table like Linux's,
   except iOS has no `dlopen` of arbitrary paths in a sandboxed app;
2. a native binary shipped for `iosArm64` **and** `iosSimulatorArm64`, which a Maven-published KMP klib does
   not carry into a consumer's link step — the consumer would have to link it, which breaks "RenG is a
   single coordinate you resolve";
3. building that binary from a Chromium checkout on every ANGLE bump, for a target the build docs say is
   simulator-and-tests-only;
4. licence and provenance for a large third-party binary inside a published artifact, against a repository
   policy that currently forbids binary extensions outright
   (`tools/check_repository_policy.py:263` lists `.dylib`, `.jar`, `.klib` and friends).

Against that cost, the benefit — a non-deprecated GLES 3 path on iOS — buys nothing that is currently
blocked: `platform.EAGL` links today against the iOS 26.5 SDK and runs on iOS 26.x runtimes. **The
grounded reading is: ANGLE is the contingency for the day Apple removes OpenGLES, not the plan for Cycle H.**

**The more consequential ANGLE fact points the other way — Android.** ANGLE is arriving underneath RenG on
Android whether RenG adopts it or not.
[Use ANGLE for OpenGL ES](https://developer.android.com/preview/features/angle): Android 15 and up includes
ANGLE as an optional layer running OpenGL ES on top of Vulkan, testable via *Developer options →
Experimental: Enable ANGLE*; Google states that "Android will ship ANGLE as the GL system driver on more new
devices, with the expectation that ultimately OpenGL ES will be available only through ANGLE"; and in
Android 17 and higher an app can request it with

```xml
<meta-data android:name="com.android.graphics.driver.prefer_angle" android:value="true" />
```

described as a preference rather than a guarantee — "If ANGLE isn't able to be used, the gpu vendor's gles
driver will be used instead." Combined with section 7 (the emulator's host GLES on Apple silicon is already
ANGLE-over-Metal), **RenG will be running on ANGLE on Android before it ever chooses to.** The design
question that deserves attention is not "should we vendor ANGLE" but "does the Android gate declare which
GLES driver it measured", because the answer will change under it.

---

## Where the official sources are silent or conflict

1. **Removal of OpenGL ES on iOS.** Apple has published a deprecation (iOS 12, 2018) and nothing since. The
   iOS 26 release notes do not mention OpenGL, OpenGLES, EAGL or GLKit at all. The framework is in both
   iOS 26.5 SDKs and in the 26.0/26.2/26.5 simulator runtimes. There is no removal announcement, no
   sunset date, and equally **no statement of continued support**. Absence of a removal notice must not be
   written up as a commitment.
2. **GLES 3 in the simulator.** Apple's only page on the subject is retired, from 2018, and covers ES 1.1
   and 2.0. Apple's current simulator-GPU page is about Metal and never mentions OpenGL ES. The
   documentation is simply silent on whether GLES 3 works in the simulator; the SDK exports and the
   runtime's shipped `GLEngine.bundle` are the only evidence, and they show capability, not behaviour.
3. **Which renderer the simulator selects.** The runtime contains exactly one renderer bundle
   (`Apple Software Renderer`) and no hardware GL driver, but no shipped string names the selection
   mechanism and nothing static settles it. Spike territory.
4. **Emulator GLES version.** Google publishes no version number; the emulator's own config file documents
   that the answer is computed from the host at boot. "Which GLES version will the emulator give us" has no
   documented answer, only a runtime one.
5. **`EGL_KHR_surfaceless_context` on Android.** AOSP lists mandatory and recommended EGL extensions and
   this is on neither list. That is not a statement that it is absent — it is a statement that it is not
   guaranteed, which is enough to prefer pbuffer.
6. **ANGLE on iOS.** README says the Metal backend is complete on iOS 12+; DevSetup omits iOS from the
   supported-platform sentence and supports only simulator test targets from a Chromium checkout. Two
   official Google documents, two different answers to "is iOS supported".
7. **Apple's `EAGLContext` with no drawable.** Apple documents offscreen framebuffers with renderbuffer
   attachments and `glReadPixels`, and documents the layer-backed path separately. It never states that a
   context which never touches a drawable is supported. Very likely; undocumented.

## Unsourced — measured here, not stated by any vendor

- **Kotlin/Native cinterop does not propagate Apple's `API_DEPRECATED` into Kotlin `@Deprecated`.**
  Observed by dumping the 2.3.21 `EAGL` and `OpenGLES3` klibs for `ios_simulator_arm64`: zero `Deprecated`
  annotations in `OpenGLES3` (3,099 lines), and only Kotlin/Native's own constructor markers in `EAGL`.
  JetBrains documents no policy here either way. Reproduce with:
  `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21/bin/klib dump-metadata ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21/klib/platform/ios_simulator_arm64/org.jetbrains.kotlin.native.platform.EAGL`
- **The iOS simulator's GL renderer selection.** Everything in section 5 beyond "the software renderer bundle
  is present and no hardware one is" is inference and marked as such.
- **That ANGLE-over-Metal is what the emulator uses by default on this machine.** The binaries and their
  strings are facts; which one a booted emulator loads under `-gpu auto` was not observed.

## Sources

- [EAGLContext — Apple Developer Documentation](https://developer.apple.com/documentation/opengles/eaglcontext)
- [OpenGL ES framework — Apple Developer Documentation](https://developer.apple.com/documentation/opengles)
- [OpenGL ES Programming Guide (archived)](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/Introduction/Introduction.html)
- [Configuring OpenGL ES Contexts (archived)](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/WorkingwithOpenGLESContexts/WorkingwithOpenGLESContexts.html)
- [Drawing to Other Rendering Destinations (archived)](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/OpenGLES_ProgrammingGuide/WorkingwithEAGLContexts/WorkingwithEAGLContexts.html)
- [OpenGL ES on iOS Simulator (retired, 2018-06-04)](https://developer.apple.com/library/archive/documentation/OpenGLES/Conceptual/OpenGLESHardwarePlatformGuide_iOS/OpenGLESiniOSSimulator/OpenGLESiniOSSimulator.html)
- [Developing Metal apps that run in Simulator](https://developer.apple.com/documentation/metal/developing-metal-apps-that-run-in-simulator)
- [Migrating OpenGL code to Metal](https://developer.apple.com/documentation/Metal/migrating-opengl-code-to-metal) and [WWDC19 session 611](https://developer.apple.com/videos/play/wwdc2019/611/)
- [iOS & iPadOS 26 Release Notes](https://developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-26-release-notes)
- [Apple Developer Forums thread 756598 (forum, not documentation)](https://developer.apple.com/forums/thread/756598)
- [Kotlin/Native platform libraries](https://kotlinlang.org/docs/native-platform-libs.html)
- [Kotlin Multiplatform default hierarchy template](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
- [OpenGL ES — Android Developers](https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl)
- [Implement OpenGL ES and EGL — AOSP](https://source.android.com/docs/core/graphics/implement-opengl-es)
- [Configure hardware acceleration for the Android Emulator](https://developer.android.com/studio/run/emulator-acceleration)
- [Android Emulator release notes](https://developer.android.com/studio/releases/emulator)
- [Set up the Android Gradle library plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin)
- [Use ANGLE for OpenGL ES — Android Developers](https://developer.android.com/preview/features/angle)
- [ANGLE README](https://github.com/google/angle/blob/main/README.md)
- [ANGLE Development / DevSetup](https://chromium.googlesource.com/angle/angle/+/main/doc/DevSetup.md)
- [MetalANGLE](https://github.com/kakashidinho/metalangle)

Local toolchain paths cited above: `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21/`
(`klib/platform/`, `konan/platformDef/`, `konan/konan.properties`);
`/Applications/Xcode.app/.../iPhoneSimulator26.5.sdk` and `.../iPhoneOS26.5.sdk`;
`/Library/Developer/CoreSimulator/Volumes/iOS_23C54/.../iOS 26.2.simruntime/Contents/Resources/RuntimeRoot`;
`~/Library/Android/sdk/platforms/android-37.0/android.jar`;
`~/Library/Android/sdk/emulator/{lib/advancedFeatures.ini,lib64/gles_angle,lib64/vulkan}`;
`~/Library/Android/sdk/system-images/android-37.0/google_apis_playstore_ps16k/arm64-v8a/`;
`~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/gradle-api/9.3.1/`.
