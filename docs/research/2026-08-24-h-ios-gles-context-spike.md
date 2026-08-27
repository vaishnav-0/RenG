# The iOS GLES context spike — can Cycle H build on stock EAGL?

Measured on 2026-08-24 on `spike/h-ios-gles`, branched from `main` at `63528ce` (the Cycle F-2 merge), on
Apple M3 Max, Xcode 26.6 (17F113), Kotlin 2.3.21. This answers the one question
`docs/research/2026-08-24-cycle-h-preflight.md` left open and explicitly flagged as a guess: *"Whether that
klib exposes what is needed **was not verified** — it is the first thing to check, and the one place this
document is guessing rather than measuring."*

## Verdict

**Yes. No pivot. RenG gets a real OpenGL ES 3.0 context on iOS from the stock Kotlin/Native toolchain with
no cinterop, no third-party binary, and no change to `kmp/build.gradle.kts`.** The GL conformance suite
passes on it unmodified. Cycle F-2's model readback suite passes on it unmodified. ANGLE is not needed and
should not be scoped.

The one negative finding is not about the context: the **iOS Simulator's GLES driver is
`Apple Software Renderer`**, the same rasteriser that failed `0.3.0`'s publication on a hosted macOS runner,
and `0.3.0`'s own probe says so in pixels here too. That is a statement about the simulator, not about iOS.

## What actually runs

```
:kmp:iosSimulatorArm64Test    1146 tests, 0 failures, 0 skipped   (1141 before this spike, + 5 new)
:kmp:macosArm64Test           1159 tests, 0 failures, 0 skipped   (unchanged)
:kmp:compileTestKotlinIosArm64  compiles clean (--rerun-tasks)
:kmp:checkKotlinAbi             passes; kmp/api/kmp.klib.api did not move
```

The five new tests are `com.rohittp.reng.internal.gl.IosGlSpikeTest`, and all five pass.

## Step 1 — what the toolchain exposes

The preflight document expected `EAGLContext` to live in `platform.OpenGLES`. **It does not.** The klibs
under `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21/klib/platform/ios_simulator_arm64/` split as:

| Symbol | klib directory | Kotlin package |
|---|---|---|
| `EAGLContext`, `EAGLSharegroup`, `EAGLDrawableProtocol` | `org.jetbrains.kotlin.native.platform.EAGL` | `platform.EAGL` |
| `kEAGLRenderingAPIOpenGLES3` (`= 3uL`), `…ES2`, `…ES1` | same | `platform.EAGL` |
| `glGetString`, `glGetIntegerv`, `glGetStringi`, `glReadPixels`, `glGetIntegeri_v`, … | `org.jetbrains.kotlin.native.platform.OpenGLES3` | **`platform.gles3`** |
| `CAEAGLLayer` | `org.jetbrains.kotlin.native.platform.QuartzCore` | `platform.QuartzCore` |

`platform.OpenGLES` carries package `platform.gles` (ES 1.x), `OpenGLES2` carries `platform.gles2`, and
`OpenGLESCommon` carries `platform.glescommon`. The one RenG already imports is `platform.gles3`, so the
package name in `IosGlBinding.kt` was right all along; only the *klib* the preflight named was wrong.

Everything the spike needed is in `platform.EAGL`, dumped with the distribution's own tool:

```
$ ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21/bin/klib dump-metadata \
    ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21/klib/platform/ios_simulator_arm64/\
org.jetbrains.kotlin.native.platform.EAGL
```

which yields, verbatim:

- `public open class platform/EAGL/EAGLContext : platform/darwin/NSObject`
- `@ObjCConstructor(initSelector = "initWithAPI:") public constructor(aPI: kotlin/ULong)`
- on the companion: `public open external fun setCurrentContext(context: EAGLContext?): kotlin/Boolean`
  and `public open external fun currentContext(): EAGLContext?`
- `public final const val kEAGLRenderingAPIOpenGLES3: kotlin/ULong /* = 3uL */`

The device target has the same klibs:
`~/.konan/…/klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.EAGL` and `…OpenGLES3` both exist,
and `OpenGLES.framework` is still present in the device SDK at
`/Applications/Xcode.app/…/SDKs/iPhoneOS26.5.sdk/System/Library/Frameworks/OpenGLES.framework` as well as
the simulator SDK.

Cross-checking the 91 `platform.gles3.gl*` imports in `IosGlBinding.kt` against the symbols in the
`OpenGLES3` klib's metadata leaves **zero** unresolved. (`glGetIntegeri_v` is the only one that needs a
second look, because its underscore falls outside a `gl[A-Z][A-Za-z0-9]*` pattern; it is present.)

## Step 2 — the smallest possible context, and the headline

`kmp/src/iosTest/kotlin/com/rohittp/reng/internal/gl/EaglOffscreenContext.kt` is 111 lines. It constructs
`EAGLContext(kEAGLRenderingAPIOpenGLES3)`, calls `EAGLContext.setCurrentContext`, and attaches a colour +
depth renderbuffer pair to a framebuffer object. There is no `CAEAGLLayer`, no window, no `UIApplication`,
and no cinterop. Like the macOS CGL fixture and the Linux surfaceless EGL fixture, it leaves default
framebuffer 0 incomplete — every suite that runs against it renders into an FBO it creates itself, which is
exactly what those suites already do on the other two platforms.

**`kmp/build.gradle.kts` did not need to change.** The default hierarchy template already declares an
`iosTest` source set that both `iosArm64` and `iosSimulatorArm64` compile, the same way `macosTest` and
`linuxTest` are already reached. Dropping files into `kmp/src/iosTest/` was the whole build change.

The headline, from `iosSimulatorArm64Test`'s own stdout, on the default device (iOS 26.5):

```
GL_VERSION=OpenGL ES 3.0 APPLE-23.1.1
GL_RENDERER=Apple Software Renderer
GL_VENDOR=Apple Inc.
GL_SHADING_LANGUAGE_VERSION=OpenGL ES GLSL ES 3.00
```

Pinning the simulator by UDID — through a throwaway init script setting `KotlinNativeSimulatorTest.device`
by reflection, since the KGP task type is not on an init script's classpath — gives the same answer on every
runtime on this machine:

| Runtime | Device | `GL_VERSION` | Result |
|---|---|---|---|
| iOS 26.0 | iPhone 17 `7ECFCC72…` | `OpenGL ES 3.0 APPLE-22.0.12` | 5/5 pass |
| iOS 26.2 | iPhone 17 `F64FB066…` | `OpenGL ES 3.0 APPLE-23.0.2` | 5/5 pass |
| iOS 26.5 | iPhone 17 Pro `424DF6C6…` | `OpenGL ES 3.0 APPLE-23.1.1` | 5/5 pass |

So "Apple deprecated OpenGL ES in iOS 12" is true and irrelevant to whether it functions: it functions on
every iOS 26 runtime present, eight years after the deprecation.

## Step 3 — RenG's own binding

`openPlatformGlBinding()` returns `GlBindingResult.Bound(IosGlBinding)` unconditionally
(`IosGlBinding.kt:599`), so only running the roster proves anything. Three things establish it, at three
different levels:

- **Link time.** `:kmp:linkDebugTestIosSimulatorArm64` succeeds. `IosGlBinding` imports 91 `platform.gles3`
  functions and Kotlin/Native resolves each against `OpenGLES.framework` when it links the test binary; a
  symbol absent from ES 3.0 on iOS would fail there. **None does.**
- **Adoption.** `everyRosterEntryPointResolvesOnIos` asserts `GlEntryPoint.entries.size == 91` and drives
  `adoptRenderContext(binding)`, which reads version, renderer, extension set and three `GL_MAX_*` limits
  through the binding, then requires a clean error flag. It returns `Adopted`: `dialect=GLES renderer=Apple
  Software Renderer`.
- **Execution.** The conformance suite's `entry-point-inventory` check (Step 4) actually calls VAOs,
  samplers, `texStorage2D`, two framebuffers, `drawBuffers`, `readBuffer`, `blitFramebuffer` and the
  matching deletes, and requires no unexpected error flag. It passes.

RenG's roster was already an ES-3.0-shaped set, so there is no desktop-only call in it to fail here — which
was the specific risk this step existed to find.

The context reports 18 extensions:

```
GL_APPLE_clip_distance GL_APPLE_color_buffer_packed_float GL_APPLE_copy_texture_levels
GL_APPLE_rgb_422 GL_APPLE_texture_format_BGRA8888 GL_EXT_color_buffer_half_float
GL_EXT_debug_label GL_EXT_debug_marker GL_EXT_pvrtc_sRGB GL_EXT_read_format_bgra
GL_EXT_separate_shader_objects GL_EXT_shader_framebuffer_fetch GL_EXT_shader_texture_lod
GL_EXT_shadow_samplers GL_EXT_texture_filter_anisotropic GL_IMG_read_format
GL_IMG_texture_compression_pvrtc GL_OES_standard_derivatives
```

and `GL_MAX_TEXTURE_SIZE = 4096` — a quarter of what a desktop core profile typically reports, and the
number Cycle H should size any atlas or residency decision against.

## Step 4 — the GL conformance suite

`runGlConformanceSuite(binding, fixture.probe, ShaderDialect.GLES)` passes, unmodified, with the full
eight checks and no relaxed policy — notably **not** the `CrossDialectLinkPolicy.SKIP_ON_LINUX_MESA_LINK_SEGFAULT`
escape hatch Linux needs, so iOS exercises the real cross-dialect link:

```
conformance renderer=Apple Software Renderer / OpenGL ES 3.0 APPLE-23.1.1
report.dialect == GLES, report.checks.size == 8
report.shadingLanguageVersionText startsWith "OpenGL ES GLSL ES"
```

iOS is therefore the **second** GLES target under this suite after Linux's ES context, exactly as the
preflight predicted, and the dialect machinery needed no new work.

## Step 5 — the model readback suite

`runModelReadbackSuite(binding, fixture.probe, ShaderDialect.GLES)` **passes on iOS**. Six pixel
relationships over a real GLB drawn through the public API, on a rasteriser nothing in this repository had
ever run before, with no tolerance change. That is the strongest single result in this document, because it
is the one that says F-2's numbers are portable rather than tuned to two drivers.

The basemap readback suite was not run: it needs a style URL carrying an API key, which this spike does not
have.

## The one negative: the simulator's rasteriser is `Apple Software Renderer`

`CLAUDE.md` records that `0.3.0` failed publication once because a hosted GitHub macOS runner has no GPU
and runs `Apple Software Renderer`, whose rasteriser drops quads reaching far outside the viewport — the
shape every ground tile has. `measureLargeQuadRasterisation`
(`kmp/src/nativeTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt:669`) exists to measure that instead of
naming the driver: 0 mismatched pixels on Apple M3 Max, 2,112 on `Apple Software Renderer`, against a
512-pixel budget.

Run against the iOS Simulator, on all three runtimes:

```
driver=Apple Software Renderer GL_SUBPIXEL_BITS=10 GL_MAX_TEXTURE_SIZE=4096
this driver mis-rasterises large off-screen quads: the north-east tile footprint disagrees
with its analytic rectangle over 3040 pixels, against a boundary budget of 512
isTrustworthy=false
```

`GL_SUBPIXEL_BITS=10` is the same number the macOS software rasteriser reports (the Metal path reports 4),
and the driver string is identical. **The iOS Simulator's GL implementation is that same CPU rasteriser.**
The mismatch is even larger here — 3,040 pixels on the north-east footprint, versus 2,112 on macOS — and it
picks a different tile, which is consistent with a different build of the same rasteriser rather than with
anything RenG does differently.

Three consequences for Cycle H, none of them blockers:

1. **If the basemap readback suite is ever wired onto `iosSimulatorArm64Test`, its ground-coverage case will
   skip out loud**, exactly as it does on a hosted macOS runner. The probe already handles this correctly;
   nothing needs inventing. But nobody should read a green iOS basemap run as proof the ground drew.
2. **The simulator does not tell you what a device's GPU does.** Every pixel result in this document is from
   a CPU rasteriser. A real-device run is the only thing that measures the mobile GPU's fill rule.
3. **`GL_MAX_TEXTURE_SIZE = 4096`** is a real constraint the desktop targets never exposed, and it is a
   simulator number — a device may differ in either direction.

## What was not checked, and what would settle it

- **A real iOS device.** `xcrun xctrace list devices` lists only this Mac; nothing is attached. So
  `:kmp:iosArm64Test` was never executed — it compiles (`:kmp:compileTestKotlinIosArm64`, forced with
  `--rerun-tasks`, succeeds) but has no host to run on. **Attaching a provisioned iPhone and running
  `:kmp:iosArm64Test` is what would settle it**, and it is the only way to learn whether the device GPU's
  rasteriser passes the large-quad probe and what its real `GL_MAX_TEXTURE_SIZE` is.
- **The basemap readback suite on iOS.** Needs a style URL with an API key. Given the rasteriser probe
  above, the expected outcome on the simulator is known — one case skips — but it was not run.
- **`EAGLContext` under a `CAEAGLLayer`.** The spike renders only to an FBO. Whether presenting to a real
  drawable still works on iOS 26 is untested and is not something RenG needs: RenG draws onto a
  caller-supplied surface and owns no window.
- **Whether Apple removes `EAGLContext` in a future release.** Unknowable; the mitigation is that the seam
  is `GlBinding`, and a Metal or ANGLE backend would replace one file behind it. Nothing in this spike
  couples RenG further to EAGL — the context fixture is *test* code, and iOS context creation stays the
  consumer's responsibility in production, the same as on every other target.

## What the spike changed in the tree

Three commits on `spike/h-ios-gles`, all test-source-only:

- `ff1f0f3` — adds `kmp/src/iosTest/` with `EaglOffscreenContext.kt` (111 lines) and `IosGlSpikeTest.kt`.
- `6646507` — widens three declarations in `BasemapReadbackSuite.kt` from `private` to `internal`
  (`measureLargeQuadRasterisation`, `LargeQuadRasterisation`, `createReadbackTarget`) so the rasteriser
  probe is callable from `iosTest`. No production file moves.
- `411bc7b` — the extension enumeration.

`kmp/api/kmp.klib.api` is byte-identical and `:kmp:checkKotlinAbi` passes. `kmp/build.gradle.kts` is
untouched. This is spike code: it should be reshaped into a proper `IosGlConformanceTest` matching
`MacosGlConformanceTest`'s structure when Cycle H is scoped, not merged as-is.

---

## Addendum, 2026-08-27: the same suites on a real device

An **iPhone 12 (iPhone13,2), iOS 26.4.2, Apple A14 GPU** was attached, which closes the largest gap this
document left open. Everything below is that device's own stdout.

### Getting a Kotlin/Native test binary onto a device at all

There is **no `iosArm64Test` Gradle task** — Kotlin/Native links a device test binary (`iosArm64TestBinaries`
→ `kmp/build/bin/iosArm64/debugTest/test.kexe`, a plain arm64 Mach-O with `minos 14.0`) and provides no
runner, because iOS will not execute a bare executable. The route that worked, and that a Cycle H task would
have to automate:

1. `./gradlew :kmp:linkDebugTestIosArm64`
2. wrap the `.kexe` as the `CFBundleExecutable` of a minimal signed `.app`
3. `xcrun devicectl device install app`
4. `xcrun devicectl device process launch --console`, which streams the binary's stdout back

Signing needed a provisioning profile covering the device. Every profile on this machine that did so targeted
a **shipping** bundle id (`com.travelanimator.routemap`, `com.lascade.marinetracker`), so installing under one
would have replaced a real app; the owner chose a throwaway id instead. In the event `xcodebuild
-allowProvisioningUpdates` matched an existing **wildcard** development profile
(`iOS Team Provisioning Profile: *`), so **no new App ID was registered** and nothing on the device was
disturbed. The app was uninstalled afterwards.

**One trap worth recording.** Launched with no filter, the binary runs all 1146 tests and iOS **SIGKILLs it
part-way through** — the app has no UI, never becomes responsive, and the watchdog takes it. From the
outside that looks like a white screen that closes, and it is easy to mistake for a crash in RenG. Passing
`--ktest_filter=` to scope the run finishes inside the watchdog window and exits 0. **Any Cycle H device job
must either filter or solve the responsiveness problem**; a full-suite device run is not simply slower, it
is killed.

### The result: 5 of 5, unmodified, in 310 ms

```
GL_VERSION=OpenGL ES 3.0 Metal - 104.1
GL_RENDERER=Apple A14 GPU
GL_VENDOR=Apple Inc.
GL_SHADING_LANGUAGE_VERSION=OpenGL ES GLSL ES 3.00
19 extensions
```

Every roster entry point resolves. `runGlConformanceSuite(..., ShaderDialect.GLES)` passes. **The model
readback suite passes on real hardware, unmodified and with no tolerance change** — all six pixel
relationships, including the indexed `drawElements` draws that the Apple-silicon-simulator bug reports say
crash.

### The device is the trustworthy rasteriser; the simulator was the outlier

| | Simulator | **Device (A14)** | macOS (M3 Max) |
|---|---|---|---|
| `GL_RENDERER` | `Apple Software Renderer` | `Apple A14 GPU` | `Apple M3 Max` |
| `GL_VERSION` | `OpenGL ES 3.0 APPLE-23.1.1` | `OpenGL ES 3.0 Metal - 104.1` | `4.1 Metal - 90.5` |
| `GL_SUBPIXEL_BITS` | 10 | **4** | 4 |
| `GL_MAX_TEXTURE_SIZE` | 4096 | **16384** | 16384 |
| large off-screen quad probe | fails, 3,040 px | **0 px disagreement** | 0 px |

Three corrections to this document's own body follow.

**`GL_MAX_TEXTURE_SIZE = 4096` is a simulator limit, not an iOS limit.** The body flagged it as a constraint
a quarter of the desktop targets'. On device it is 16384, the same as macOS. Nothing needs to be designed
around it.

**`GL_SUBPIXEL_BITS = 10` is likewise the software rasteriser's**, matching the macOS software path; the
device reports 4, matching the macOS Metal path. The device behaves like the desktop GPU, not like the
simulator.

**The rasterisation probe passes on device with zero disagreement.** The body records it failing on the
simulator at 3,040 pixels against a 512 budget and treats that as the expected answer for a software
rasteriser — which it is. What is now measured is that **no tolerance needs widening for iOS at all**: the
real hardware agrees with the analytic rectangle exactly.

### And a fact that reframes the ANGLE question entirely

The device reports `OpenGL ES 3.0 **Metal** - 104.1`. **Apple's own OpenGL ES on iOS is already implemented
over Metal.** The pivot that was on the table — adopting ANGLE to translate GLES to Metal — would replace
Apple's Metal translation with Google's, for an API Apple is already translating. That is a materially
different proposition from "GLES is unsupported and needs an emulation layer", which is how the option was
framed before any of this was measured.
