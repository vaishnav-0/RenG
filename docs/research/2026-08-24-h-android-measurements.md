# Cycle H — Android facts, measured rather than reported

Measured 2026-08-24 on Apple M3 Max, against `main` at `63528ce` with Rentile pinned to `0.5.0`
(`gradle/libs.versions.toml`). Everything here is the output of a command, not a citation. A sibling
document, `2026-08-24-h-field-reports.md`, covers what other developers *report*; this one covers what this
machine actually does. Where the two disagree, this one is about this configuration only.

Android is sequenced **second** in Cycle H by owner decision, on the grounds that Android's EGL support is
not in doubt while iOS's is. These measurements are here so that the Android half is grounded when its turn
comes, and so the grilling does not have to guess.

## The emulator provides GLES 3.1, and it provides it through ANGLE

`Pixel_10_Pro_XL`, booted headless with `-gpu swiftshader_indirect`:

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

Three things follow.

**RenG's requirement is met with room to spare.** RenG targets GLES 3.0; the emulator reports 3.1.
`GL_EXT_color_buffer_float`, `GL_OES_depth24`, `GL_OES_packed_depth_stencil` and `GL_OES_vertex_array_object`
are all present, which covers the offscreen surface and VAO paths RenG depends on. `/vendor/lib64/egl/`
carries `libEGL_emulation.so`, `libGLESv2_emulation.so` and `libGLESv1_CM_emulation.so`.

**This is a fourth distinct rasteriser, and that is the risk it carries.** RenG's readback suites assert
tolerances, not exact pixel counts, precisely because rasterisers round differently — `0.3.0` failed
publication once when a hosted runner's `Apple Software Renderer` dropped primitives an M3 Max did not. The
rasterisers now in play are Apple M3 Max (Metal), Apple Software Renderer, Mesa llvmpipe, and now
ANGLE-over-Vulkan-over-SwiftShader. Every tolerance in `BasemapReadbackSuite` and `ModelReadbackSuite` was
derived against the first three. **Whether they survive the fourth is unmeasured and is the single most
likely source of a late surprise in the Android half.**

**It is also direct evidence for the iOS pivot question.** The owner asked for ANGLE to be evaluated early
as the likely fallback if iOS's stock OpenGL ES proves unusable. Google ships ANGLE as the *default* GLES
implementation of its own Android emulator, translating to Vulkan. That is not proof it is right for iOS —
the iOS backend is Metal, not Vulkan, and is a different code path — but it establishes that ANGLE is
production infrastructure rather than an experiment, which is worth knowing before the option is weighed.

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

`kmp/src/` contains `androidHostTest` and **no `androidInstrumentedTest`**. `androidConnectedCheck` exists
as a Gradle task and would execute zero tests. `AndroidGlBinding.kt` has therefore never been executed
anywhere — not on a device, not on an emulator, not in CI — while shipping in every release since `0.2.0`.

So the Android half is: create the source set, get an EGL context inside it, and find out (a) whether the
91-entry-point roster resolves, (b) whether the conformance suite passes on ANGLE/SwiftShader, (c) whether
the readback tolerances survive that rasteriser, and (d) whether Skia loads. Four unknowns, each
measurable, none measured.

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
