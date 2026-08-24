# Cycle H preflight — what Android and iOS bring-up actually costs

Measured on 2026-08-24 against `feat/f2-models` at `3692d73`, on Apple M3 Max, immediately after Cycle F-2
landed. Every number below came from running something, not from reading it.

## Summary answer

**iOS is nearly done and nobody knew.** `:kmp:iosSimulatorArm64Test` is an existing Gradle task that had
never been run in this project's history. It runs, first try: **1141 tests, 0 failures, 0 skipped**, against
`macosArm64Test`'s 1159. The two targets share **97 test classes** and iOS passes every one. The entire
difference is **two classes**, both macOS-only, both holding a real GL context:

| macOS-only class | what it does |
|---|---|
| `internal.gl.MacosGlConformanceTest` | creates a CGL core-profile context and runs the GL conformance suite, the basemap readback suite and the model readback suite on it |
| `internal.gl.MacosGlBindingCapacityGuardTest` | the platform binding's own capacity guard |

iOS contributes **zero** unique classes. So the iOS half of Cycle H is one well-shaped task: an `iosTest`
source set carrying a GLES context fixture, so the suites that already exist run there too. Not a port —
the code under test is already proven to compile, link and pass on the target.

**Android is the opposite: there is nothing to run.** `kmp/src/` contains `androidHostTest` and no
`androidInstrumentedTest`. `androidConnectedCheck` exists as a task and would execute zero tests.
`AndroidGlBinding.kt` has therefore **never been executed anywhere** — not on a device, not on an emulator,
not on CI — while shipping in every release since `0.2.0`.

## What was measured

```
:kmp:iosSimulatorArm64Test   1141 tests, 0 failures, 0 skipped   (never run before today)
:kmp:macosArm64Test          1159 tests, 0 failures, 0 skipped
shared classes                 97, all passing on both
macOS-only classes              2, both GL-context holders
iOS-only classes                0
```

The 18-test gap is those two classes' contents, not a coverage hole in the shared code.

## Toolchain available on this machine

- **iOS simulators**: iOS 26.0 and 26.2, iPhone 17 Pro / Pro Max, currently shut down. Kotlin/Native drives
  these itself for `iosSimulatorArm64Test`; no manual boot was needed.
- **Android SDK** at `~/Library/Android/sdk`, `adb` on `PATH`, **no device connected**, two AVDs defined:
  `Pixel_10_Pro_XL` and `Pixel_4_XL_API_29`.

So both halves of Cycle H are executable here. That is not a given and is worth recording, because CI cannot
do either: `ci.yml`'s two jobs are Ubuntu and macOS runners with no simulator boot and no attached device.

## The iOS half, precisely

`kmp/src/iosMain/.../IosGlBinding.kt` provides `openPlatformGlBinding()` and nothing else — there is no
context creation in the tree for iOS, because context creation belongs to the *test* source set. macOS puts
it in `macosTest/.../CglCoreProfileContext.kt`, which reaches CGL through the stock `platform.OpenGLCommon`
klib with no cinterop. Linux puts it in `linuxTest/.../SurfacelessEglContext.kt`.

iOS needs the same shape and cannot use either: CGL is macOS-only and iOS has no EGL. The equivalent is
`EAGLContext` with `kEAGLRenderingAPIOpenGLES3`, reached through the stock `platform.OpenGLES` klib. Whether
that klib exposes what is needed **was not verified** — it is the first thing to check, and the one place
this document is guessing rather than measuring.

Two consequences worth stating before design starts:

- **iOS is a GLES context, so the shader dialect is `ShaderDialect.GLES`, not `DESKTOP`.** That makes iOS the
  second GLES target after Linux's ES context, and `LinuxGlConformanceTest` already proves the same binary
  detects both dialects. So the dialect machinery is not new work; the context is.
- **`EAGLContext` is deprecated by Apple in favour of Metal.** It still functions and still ships, but a
  cycle that builds on it should say so in writing rather than discover it in a release note.

## The Android half, precisely

There is no instrumented test source set, so the work is to create one and get a GL context in it. That is
strictly more than iOS needs, and it carries a constraint the other targets do not:
`CLAUDE.md` records that Android host tests "cannot execute Rentile's Skia rasterization at all, which was
measured with error codes rather than assumed". Whether that holds on a *device* — where the Skia native
library is actually present — is unknown and is the measurement that decides whether the basemap readback
suite can run on Android at all, or only the GL conformance suite.

## What this changes about the cycle's shape

The decomposition row reads "Android and iOS bring-up — device/simulator runs, manual for Android GL". That
is still right, but it understates how asymmetric the two halves are. iOS is a context fixture away from
running the whole existing gate. Android is a source set, a context fixture, an emulator or device in the
loop, and an open question about whether the engine works there at all.

Sequencing them iOS-first would put a second GLES context under the conformance suite quickly and cheaply,
and would tell us whether the suites are portable before Android's larger unknowns are opened.

## What was not checked

- Whether `platform.OpenGLES` exposes `EAGLContext` usably from Kotlin/Native without cinterop.
- Whether a headless GLES context is obtainable on the simulator at all, or whether a real device is needed.
- Whether Rentile's Skia rasterization works on an Android device or an ARM64 emulator.
- `iosArm64Test` — a real-device task — was not run, and there is no device attached.
- Whether the readback suites' tolerances survive a mobile GPU's rasteriser. `0.3.0` failed once on exactly
  this class of difference between an M3 Max and a software rasteriser; a third and fourth rasteriser is
  precisely where that risk lives.
