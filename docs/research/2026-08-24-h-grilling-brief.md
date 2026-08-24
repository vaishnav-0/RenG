# Cycle H grilling brief — the grounded position

The four preflight documents total 1,771 lines and disagree with each other in two places. This one
consolidates them, **resolves both disagreements by measurement**, and states what is still open. It is
the document the grilling runs on; the others are its evidence.

| Document | What it is | Lines |
|---|---|---|
| `2026-08-24-cycle-h-preflight.md` | first measurements, and one guess it flagged and got wrong | 103 |
| `2026-08-24-h-ios-gles-context-spike.md` | a live spike: real context, real suites, three iOS runtimes | 230 |
| `2026-08-24-h-official-platform-docs.md` | Apple, Google, JetBrains and Khronos primary sources | 646 |
| `2026-08-24-h-field-reports.md` | what other developers hit, graded A–D by source authority | 658 |
| `2026-08-24-h-android-measurements.md` | emulator and artifact measurements on this machine | 134 |

## The headline: the risk iOS was sequenced first to retire is retired

RenG obtains a real **`OpenGL ES 3.0`** context on iOS from the stock Kotlin/Native toolchain — no cinterop,
no third-party binary, no change to `kmp/build.gradle.kts` — through `EAGLContext(kEAGLRenderingAPIOpenGLES3)`
in the **`platform.EAGL`** klib. Verified live on iOS **26.0, 26.2 and 26.5**, each pinned by UDID. The GL
conformance suite passes unmodified at `ShaderDialect.GLES`, without the cross-dialect-link escape hatch
Linux needs. `runModelReadbackSuite` passes all six pixel relationships **with no tolerance change**.
`iosSimulatorArm64Test` stands at 1146 tests, 0 failures.

**No ANGLE pivot is required, and ANGLE is off Cycle H's critical path.** That was the question the owner
sequenced iOS first to answer early, and it is answered.

Corroboration rather than coincidence: `EAGL.def` and `OpenGLES3.def` exist upstream for both iOS targets,
and public projects already do this — WorldWindKotlin drives `kEAGLRenderingAPIOpenGLES3` with
`platform.gles3` from `iosMain`, as does JetBrains' own kotlinconf-spinner.

## Two disagreements between the strands, both resolved by measurement

### 1. The `glDrawElements` simulator crash — reported, and not reproduced here

**The report (source grade A).** Apple's own DTS acknowledged an `EXC_BAD_ACCESS` on *any* `glDrawElements`,
inside `libCoreVMClient.dylib` / `cvmsServerElementBuild`, occurring **only on Apple silicon iOS
Simulators** and not on devices (Apple Developer Forums 756598, FB13850673). Independently corroborated by
three maplibre-native issues, VLCKit, and an unanswered 2025 thread against the iOS 17.5 simulator.
`iPhoneSimulator26.5.sdk`'s `OpenGLES.framework` still ships `libCoreVMClient.tbd`. A different Apple
engineer told a visionOS-simulator reporter it was his own memory bug, so even the A-grade sources conflict.

**The measurement.** RenG's model draw path issues `binding.drawElements` (`internal/gl/ModelPipeline.kt:705`).
`runModelReadbackSuite` — which draws indexed geometry through exactly that call — **passed on iOS 26.0,
26.2 and 26.5**.

**Resolution.** For RenG's configuration on iOS 26, the crash does not occur. Either it is fixed by iOS 26 or
it is conditional on something RenG does not do. **Direct measurement of our own call path outranks the
report for our own configuration**, and the report is retained as a known hazard for anyone testing against
an older simulator runtime. This is worth writing down precisely because the next person to read those
threads will otherwise conclude the iOS gate is unusable.

### 2. Skia packaging — Rentile already solved it

**The report (correct, about upstream).** `org.jetbrains.skiko:skiko-android:0.148.2` contains **no native
library**: four entries, `classes.jar`, no `jni/`. The `.so` lives in `skiko-android-runtime-{arm64,x64}`,
plain JARs with the `.so` at the JAR root rather than under `lib/<abi>/`, which nothing in the graph
declares. On that basis the conclusion was that "Android cannot execute Skia" is a dependency-graph fact
that moving to a device test would not fix.

**The measurement.** RenG does not depend on `skiko-android`. It depends on Rentile, and **Rentile's own
AAR bundles the libraries**:

```
com.rohittp.rentile:kmp-android:0.5.0  →  jni/arm64-v8a/libskiko-android-arm64.so   30,391,808 bytes
                                          jni/x86_64/libskiko-android-x64.so        29,940,144 bytes
```

**Resolution.** Rentile has already solved the packaging problem for its consumers, and RenG inherits a
correctly-placed `.so` through ordinary AAR `jni/` merging. The proposed one-line experiment is
unnecessary. **The Android outlook is materially better than the field reading suggested** — though whether
the library *loads and rasterises* on a device is still unmeasured, and that remains the question deciding
whether `BasemapReadbackSuite` can run on Android at all.

Both of these would have entered the grilling as frightening unknowns. Neither is one.

## What is now settled, with its evidence

- **iOS context**: stock `platform.EAGL`, ES 3.0, three runtimes. *(spike)*
- **`platform.OpenGLES` is ES 1.x**, not what RenG needs; the preflight's guess was wrong and is corrected
  in place. *(spike + docs, independently)*
- **The deprecation is invisible to the build, permanently.** cinterop does not translate Apple's
  `API_DEPRECATED` into Kotlin `@Deprecated` — zero deprecation annotations across 3,099 lines of dumped
  `OpenGLES3` metadata. **If it is not in an ADR it is nowhere.** *(docs)*
- **The iOS simulator has no hardware GL driver.** Its runtime ships one renderer bundle,
  `GLRendererFloat.bundle`, containing `Apple Software Renderer`; the host macOS stack has
  `AppleMetalOpenGLRenderer.bundle` and vendor driver bundles. Confirmed live: `GL_SUBPIXEL_BITS=10`, the
  same rasteriser that failed `0.3.0`'s publication. **The simulator is a logic gate, never a GPU proxy.**
  *(docs + spike)*
- **`GL_MAX_TEXTURE_SIZE = 4096` on the simulator**, a quarter of the desktop targets. *(spike)*
- **Android's source set is `androidDeviceTest`**, via AGP 9.3.1's `withDeviceTest {}`, which brings Gradle
  Managed Devices — a route to running without a hand-booted emulator. *(docs)*
- **Android needs a pbuffer, not a surfaceless context**: `EGL_KHR_surfaceless_context` is on neither
  AOSP's mandatory nor its recommended list, so Linux's fixture cannot be transliterated. *(docs)*
- **The emulator offers two rasterisers by flag**: ANGLE-over-Metal by default, ANGLE-over-Vulkan-over-
  SwiftShader when forced; GLES 3.1 either way, against RenG's 3.0 requirement. *(measurement + docs)*
- **Nothing has ever executed `AndroidGlBinding.kt`** — not on a device, an emulator, or CI — while it has
  shipped in every release since `0.2.0`. *(measurement)*
- **RenG will meet ANGLE on Android regardless of choice**: Google intends OpenGL ES there to be delivered
  only through ANGLE. *(docs)*
- **Nobody has driven ANGLE from Kotlin/Native on any platform** — three code searches, zero hits. The
  nearest analogue is Skiko shipping `libskia.a` in a 57 MB iOS klib, which is both the mechanism ANGLE
  would need and the size class it would cost. *(field)*

## What is still open, and what would settle each

1. **No iOS device has run anything.** `iosArm64Test` compiles and has never executed; no device is
   attached. Every iOS fact above is simulator-only, on a software rasteriser. **Settled by**: attaching a
   device and running the suites.
2. **Whether the readback tolerances survive a real mobile GPU, or either ANGLE backend.** Every tolerance
   in both suites was derived against Apple M3 Max, Apple Software Renderer and llvmpipe.
   `measureLargeQuadRasterisation` already exists as the instrument and already fails on the simulator's
   rasteriser (3,040 mismatched pixels against a 512 budget) — which is the *expected* answer there, not a
   defect. **Settled by**: running the suites on each target rasteriser and recording the probe's number.
3. **Whether Skia loads and rasterises on an Android device or emulator.** Decides whether
   `BasemapReadbackSuite` can run on Android at all, or only the two self-contained suites. **Settled by**:
   one `androidDeviceTest` that calls into Rentile.
4. **Whether the 91-entry-point roster resolves on Android.** Unknown; iOS's does. **Settled by**: the
   conformance suite in an `androidDeviceTest`.
5. **Whether an application-level EGL context on the emulator reports what SurfaceFlinger reports.**
   **Settled by**: the same test.
6. **Rentile `0.6.0` exists and is unevaluated** while RenG pins `0.5.0`. Bumping moves five coupled places
   at once or the policy checker fails closed. **Settled by**: an explicit decision, not by discovery
   mid-cycle as happened with `0.5.0`.

## The questions the grilling has to answer

Each is now askable against facts rather than impressions.

1. **Does Cycle H gate on the simulator, on a device, or both?** The simulator is free, runs in CI's reach,
   and is a software rasteriser that cannot speak for a GPU. A device is the only real signal and cannot be
   automated here. What is the gate, and what does a release claim about targets it has only simulated?
2. **Does the iOS deprecation get an ADR?** The build will never warn. RenG has committed to six targets
   permanently, and one of them now rests on an API Apple deprecated in 2018 and has not removed in eight
   years. That is a real decision with a real risk, and it is currently recorded in no ADR.
3. **Which rasterisers must a tolerance survive, and what happens when one cannot?** `0.3.0` already
   answered this once by skipping a single case out loud rather than loosening every budget. Does that
   precedent extend to two more rasterisers, and where is the line past which a suite is tolerated into
   meaninglessness?
4. **Is `androidDeviceTest` in scope for this cycle, or does Android stop at "the binding compiles"?** The
   Android half is four unmeasured unknowns against iOS's zero.
5. **Does CI change?** Neither an iOS simulator nor an Android emulator runs in `ci.yml` today, and the
   publication gate claims nothing about either target. If a suite runs only on a developer's machine, what
   is the release actually asserting?
6. **What does the release note say about targets verified only by simulation?** `CLAUDE.md` already holds
   that "which of them anyone has actually executed belongs in release notes rather than being discovered by
   an Android consumer." Cycle H is where that promise comes due.
