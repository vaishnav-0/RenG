# Cycle H — Android facts, measured rather than reported

Measured 2026-08-24 on Apple M3 Max, against `main` at `63528ce` with Rentile pinned to `0.5.0`
(`gradle/libs.versions.toml`). Everything here is the output of a command, not a citation. A sibling
document, `2026-08-24-h-field-reports.md`, covers what other developers *report*; this one covers what this
machine actually does. Where the two disagree, this one is about this configuration only.

Android is sequenced **second** in Cycle H by owner decision, on the grounds that Android's EGL support is
not in doubt while iOS's is. These measurements are here so that the Android half is grounded when its turn
comes, and so the grilling does not have to guess.

## The emulator provides GLES 3.1, and it provides it through ANGLE

`Pixel_10_Pro_XL`, booted headless with **`-gpu swiftshader_indirect`** — and that flag is load-bearing for
everything below, see the correction after the table:

```
ro.product.cpu.abi      arm64-v8a
ro.build.version.sdk    37
ro.hardware.vulkan      ranchu
```

`dumpsys SurfaceFlinger`:

```
GLES: Google (Google Inc. (Google)), Android Emulator OpenGL ES Translator
      (ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (LLVM 10.0.0)), SwiftShader driver-5.0.0)),
      OpenGL ES 3.1 (OpenGL ES 3.1.0 (ANGLE 2.1.1 git hash: fbf66f49c7cc))
```

**Correction, from the official-documentation strand.** That reading is what SwiftShader gives *because this
run forced it*. The emulator's own default host backend on Apple silicon is **ANGLE-over-Metal**:
`~/Library/Android/sdk/emulator/lib64/gles_angle/libGLESv2.dylib` is an arm64 Mach-O containing
`ANGLE Metal Renderer`, `DisplayMtl` and `ContextMtl`, and the guest's maximum GLES version is computed from
the host at boot (`GLESDynamicVersion = on`). So there are **two** emulator rasterisers reachable by
configuration, not one, and a Cycle H test job has to pick one deliberately and record which, because the
tolerance question below has a different answer for each.

**Corrected 2026-08-27 by the device spike**: "by default" overstates it. Booting `Pixel_10_Pro_XL` with
`hw.gpu.mode=auto` and **no** `-gpu` flag selected ANGLE-over-Vulkan-over-SwiftShader on this machine, not
ANGLE-over-Metal. Which backend an emulator picks is a property of the AVD's own configuration, so the only
safe statement is that a job must pin it explicitly and print what it got.

Three things follow.

**RenG's requirement is met with room to spare.** RenG targets GLES 3.0; the emulator reports 3.1.
`GL_EXT_color_buffer_float`, `GL_OES_depth24`, `GL_OES_packed_depth_stencil` and `GL_OES_vertex_array_object`
are all present, which covers the offscreen surface and VAO paths RenG depends on. `/vendor/lib64/egl/`
carries `libEGL_emulation.so`, `libGLESv2_emulation.so` and `libGLESv1_CM_emulation.so`.

**This is a fourth distinct rasteriser, and that is the risk it carries.** RenG's readback suites assert
tolerances, not exact pixel counts, precisely because rasterisers round differently — `0.3.0` failed
publication once when a hosted runner's `Apple Software Renderer` dropped primitives an M3 Max did not. The
rasterisers now in play are Apple M3 Max (Metal), Apple Software Renderer, Mesa llvmpipe, and now **two**
more from the Android emulator alone — ANGLE-over-Metal by default, ANGLE-over-Vulkan-over-SwiftShader when
forced. The iOS spike has since added a sixth reading, though it turns out to be the second again: the iOS
simulator is also `Apple Software Renderer`. Every tolerance in `BasemapReadbackSuite` and
`ModelReadbackSuite` was derived against the first three. **Whether they survive either ANGLE backend is
unmeasured, and it is the single most likely source of a late surprise in the Android half.**

**It bears on the iOS pivot question, which has since been settled the other way.** The owner asked for
ANGLE to be evaluated early as the fallback if iOS's stock OpenGL ES proved unusable. Google ships ANGLE as
the default GLES implementation of its own Android emulator, which establishes it as production
infrastructure rather than an experiment. But the live iOS spike
(`2026-08-24-h-ios-gles-context-spike.md`) obtained a real `OpenGL ES 3.0` context from the stock
Kotlin/Native `platform.EAGL` klib on three iOS 26 runtimes, with the conformance and model readback suites
passing unmodified — **so no pivot is needed and ANGLE is not on Cycle H's critical path.** What remains
true is that RenG will meet ANGLE on Android whatever it chooses, because Google intends OpenGL ES on
Android to be delivered through ANGLE.

Caveat, stated because it would be easy to overclaim: this reading is SurfaceFlinger's own RenderEngine. An
application's EGL context on the emulator also goes through the emulator's translator, but that this
specific string describes what a RenG instrumented test would see **has not been verified** — verifying it
needs an instrumented test, and there is none. See below.

## Rentile ships arm64 Skia, so the host-test limitation does not transfer to a device

The pinned `com.rohittp.rentile:kmp-android:0.5.0` AAR contains:

```
jni/arm64-v8a/libskiko-android-arm64.so   30,391,808 bytes
jni/x86_64/libskiko-android-x64.so        29,940,144 bytes
```

`CLAUDE.md` records that RenG's Android **host** tests "cannot execute Rentile's Skia rasterization at all,
which was measured with error codes rather than assumed". That measurement stands, and the reason is now
visible: a JNI shared object packaged under `jni/` in an AAR is unpacked and loaded by an Android
*application*, not by a JVM host test, so the host-test failure is about packaging and not about Skia
being unavailable on the platform.

**So the widespread reading — "Rentile does not work on Android" — is not what was measured.** What was
measured is that it does not work in a JVM host test. On an arm64 device or emulator the binary is present.
Whether it *loads and rasterises* there is still unmeasured, and it is the question that decides whether
`BasemapReadbackSuite` can run on Android at all or whether only `GlConformanceSuite` and
`ModelReadbackSuite` can. Both of the latter are self-contained; the basemap suite needs the engine.

## Nothing can be run on a device today, and that is the shape of the Android half

`kmp/src/` contains `androidHostTest` and no device-test source set at all. **The name is
`androidDeviceTest`, not `androidInstrumentedTest`** — AGP 9.3.1's `KotlinMultiplatformAndroidLibraryExtension`
declares `withDeviceTest {}` beside the `withHostTest {}` this build already calls, and brings Gradle Managed
Devices with it (`ManagedVirtualDevice`, with `apiLevel`, `systemImageSource` and `testedAbi`), which is a
route to running these tests without a manually booted emulator. `androidConnectedCheck` exists as a Gradle
task and would execute zero tests. `AndroidGlBinding.kt` has therefore never been executed
anywhere — not on a device, not on an emulator, not in CI — while shipping in every release since `0.2.0`.

So the Android half is: create the source set, get an EGL context inside it, and find out (a) whether the
91-entry-point roster resolves, (b) whether the conformance suite passes on ANGLE, (c) whether the readback
tolerances survive it, and (d) whether Skia loads.

One constraint on (b) that rules out the obvious shortcut: **`EGL_KHR_surfaceless_context` is on neither
AOSP's mandatory nor its recommended extension list**, so Linux's `SurfacelessEglContext` fixture cannot be
transliterated. The Android context has to be a **pbuffer**.

Four unknowns, each measurable, none measured.

The tooling is present: Android SDK at `~/Library/Android/sdk`, `adb` on `PATH`, and two AVDs
(`Pixel_10_Pro_XL`, `Pixel_4_XL_API_29`). CI cannot do any of this — `ci.yml`'s two jobs are Ubuntu and
macOS runners with no attached device and no emulator.

## One thing noticed in passing

The Gradle cache holds `com.rohittp.rentile:kmp-android` at `0.6.0`, while RenG pins `0.5.0`. That is a
newer engine release nobody has evaluated. Bumping it moves five coupled places at once or
`check_repository_policy.py` fails closed (`HANDOFF.md` names them), so it is not a casual change — but it
is worth an explicit decision rather than being discovered mid-cycle, the way `0.5.0` was.

The cache also holds numerous `0.1.0-SNAPSHOT` entries. Those are residue from local cross-repo development,
not declared dependencies; RenG's catalog pins a stable version and the repository policy forbids a
`-SNAPSHOT` dependency being committed.

## What was not measured

- Whether an application-level EGL context on this emulator reports the same renderer string as
  SurfaceFlinger's.
- Whether `libskiko-android-arm64.so` loads and rasterises on a device or emulator.
- Anything on real Android hardware. Everything above is an emulator.
- `Pixel_4_XL_API_29` was not booted; API 29 against API 37 may differ in GLES backing.
