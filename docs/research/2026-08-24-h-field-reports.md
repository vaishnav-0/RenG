# Cycle H field reports — what other people hit doing this

Compiled 2026-08-24. This document is **field evidence only**: bug trackers, issue threads, mailing
lists, published artifacts and public source. It deliberately does not run a spike and does not survey
vendor documentation — two sibling documents cover those. Where a vendor document is cited here it is
because a field report leans on it.

Its companion is `docs/research/2026-08-24-cycle-h-preflight.md`, which measured what this checkout can
already do. Several of the preflight's explicit "not checked" items are answered below from upstream
source rather than from a spike.

## How to read the source grades

Every claim carries a link and a grade. The grades are about *who is speaking*, not about how much I
believe the claim.

| Grade | Meaning |
|---|---|
| **A** | First-party and authoritative: an Apple DTS or Apple Frameworks engineer reply, a JetBrains YouTrack ticket, source or build files in the JetBrains / Google / Apple repositories, or a published artifact I opened myself. |
| **B** | Corroborated: a maintainer of a widely used project speaking about their own project, or the same symptom reported independently in more than one tracker. |
| **C** | Single uncorroborated report: one thread, one small repository, no replies. Concrete but weightless on its own. |
| **D** | Verified during this research on this machine or over the public network. Stated as such, with the command's result. |

A **C** claim that matches a **B** claim is worth more than either alone, and I say so when that happens.

---

## The three field facts most likely to change the cycle's shape

### 1. Apple's own DTS has acknowledged a widespread OpenGL ES crash that happens **only on Apple silicon iOS Simulators**

On [Apple Developer Forums thread 756598, "OpenGL ES support on Apple Silicon Simulators"](https://developer.apple.com/forums/thread/756598) **(A)**,
developers report `EXC_BAD_ACCESS` on *any* `glDrawElements` call, with stack frames in
`libCoreVMClient.dylib` — `cvmsServerElementBuild`, `cvms_element_build_from_source`,
`cvm_deferred_build_modular`. The same builds run correctly on real devices. An Apple DTS engineer
replied in June 2024:

> "This appears to be a widespread issue. Nonetheless, it's good that this is limited to the Simulator
> and doesn't affect actual device. Please take a moment to file a bug report in the Feedback Assistant
> and we'll investigate."

One participant filed **FB13850673**. The thread reports the symptom persisting from Xcode 15.4 through
Xcode 16.1 beta and into March 2025, with the split falling on the simulator *runtime* version rather
than Xcode (iOS 15.2 runtimes fine, 15.4+ crashing).

This is independently corroborated in a project structurally very close to RenG — a vector basemap
renderer drawing tiles through GLES:

- [maplibre-native #163](https://github.com/maplibre/maplibre-native/issues/163) **(B)** — segfault at
  `KERN_INVALID_ADDRESS 0x30` in `libCoreVMClient.dylib` / `cvmsServerElementBuild` on an M1 iOS
  Simulator. The reporter writes: *"I think this is something that could be an apple bug/known issue
  given that OpenGL is deprecated."*
- [maplibre-native #2351](https://github.com/maplibre/maplibre-native/issues/2351) **(B)** — May 2024,
  Sonoma + Xcode 15.3 + **iOS 17.4 simulator**, MapLibre 5.13.0. Crash off the main thread in `memmove`
  under `cvmsServerElementBuild`, reached from `glDrawElements_ES2Exec` ←
  `PaintParameters::renderTileClippingMasks()`. Device unaffected. Closed as not planned.
- [maplibre-native #268](https://github.com/maplibre/maplibre-native/issues/268) **(B)** — April 2022,
  `EXC_BAD_ACCESS` in `glDrawElements_IMM_ES2Exec` on a complex custom style during a programmatic
  camera move; simulator only; the default style is fine.
- [Apple Developer Forums 803483](https://developer.apple.com/forums/thread/803483) **(C)** — October
  2025, Mac mini M2 / Xcode 15.4. A SDL2 + OpenGL ES app is black-screen-or-immediate-exit on the
  **iOS 17.5** simulator, works on the 17.2 simulator after a clean rebuild, and works on real devices.
  Zero replies.

**D — verified locally.** The installed `iPhoneSimulator26.5.sdk` still ships
`System/Library/Frameworks/OpenGLES.framework` containing **`libCoreVMClient.tbd`** alongside
`libCVMSPluginSupport.tbd`, `libGFXShared.tbd` and `libGLProgrammability.tbd`. The component named in
every one of those crash reports is structurally still in the simulator's GLES path in the SDK this
machine builds against.

**Why this decides the cycle's shape.** The preflight's plan is an iOS GL context fixture running the
existing readback suites, and the only iOS test task that runs without a device is
`iosSimulatorArm64Test` on iOS 26.0 / 26.2 — which is exactly the configuration the field reports
implicate. The failure mode reported is a *crash on draw*, not the rounding disagreement that broke
`0.3.0`; that is easier to diagnose but it fails the whole task rather than one case. Two consequences
worth designing for before writing the fixture: (a) a device run (`iosArm64Test`) may be the only
trustworthy iOS gate, and no device is attached per the preflight; (b) if the simulator does work on
iOS 26, that is a *new* data point nobody has published and is worth recording explicitly, because
every public report stops at iOS 17.x.

**A conflict, stated as the owner asked.** On
[Apple Developer Forums 758684](https://developer.apple.com/forums/thread/758684) **(A)**, July 2024, a
developer reported the same `EXC_BAD_ACCESS` in `glDrawElements()` on the **visionOS 1.1** simulator on
an M1 Max, working normally on iOS/iPad simulators. An **Apple Graphics and Games engineer** replied:

> "It sounds like you have a memory management bug in your game. There's not much help anyone can
> provide without access to your source code."

So Apple has answered essentially the same symptom two ways: "widespread, simulator-only, file a bug"
(DTS, iOS simulators) and "your bug" (Graphics and Games, visionOS simulator). Both are grade **A**.
Do not treat either as settled.

### 2. Driving GLES **3** from Kotlin/Native on iOS with no cinterop is already proven in public code, including by a 3D globe renderer

The preflight lists as its one guess: *"Whether `platform.OpenGLES` exposes `EAGLContext` usably from
Kotlin/Native without cinterop was not verified."* Upstream source answers it without a spike.

**D — verified.** The Kotlin/Native platform-library definitions in `JetBrains/kotlin` declare these
klibs for iOS:

| `.def` | Kotlin package | headers |
|---|---|---|
| [`ios/EAGL.def`](https://raw.githubusercontent.com/JetBrains/kotlin/master/kotlin-native/platformLibs/src/platform/ios/EAGL.def) | `platform.EAGL` | `OpenGLES/EAGL.h`, `OpenGLES/EAGLDrawable.h` |
| [`ios/OpenGLES3.def`](https://raw.githubusercontent.com/JetBrains/kotlin/master/kotlin-native/platformLibs/src/platform/ios/OpenGLES3.def) | `platform.gles3` | `OpenGLES/ES3/gl.h`, `OpenGLES/ES3/glext.h` |
| [`ios/OpenGLESCommon.def`](https://raw.githubusercontent.com/JetBrains/kotlin/master/kotlin-native/platformLibs/src/platform/ios/OpenGLESCommon.def) | `platform.glescommon` | `OpenGLES/gltypes.h` |
| [`ios/GLKit.def`](https://raw.githubusercontent.com/JetBrains/kotlin/master/kotlin-native/platformLibs/src/platform/ios/GLKit.def) | `platform.GLKit` | framework `GLKit` |

Note that `OpenGLES.def` is **ES 1 only** (`package = platform.gles`, `headers = OpenGLES/ES1/**`) —
the name is misleading and the preflight's phrasing "`platform.OpenGLES`" would have led to the wrong
package. ES 3 is `platform.gles3`, which is what `kmp/src/iosMain/.../IosGlBinding.kt` already imports.
Also note `OpenGLES3.def` includes `glext.h`, so ES 3 extension entry points are in the klib.

`ls ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.0/klib/platform/{ios_arm64,ios_simulator_arm64}`
lists `…platform.EAGL`, `…platform.GLKit`, `…platform.OpenGLES`, `…platform.OpenGLES2`,
`…platform.OpenGLES3`, `…platform.OpenGLESCommon` for **both** targets. The klibs are present in this
checkout's toolchain.

Public code that uses them (found with `gh search code`, grade **B** unless noted):

- **[WorldWindEarth/WorldWindKotlin](https://github.com/WorldWindEarth/WorldWindKotlin)** — a KMP port
  of NASA WorldWind. `worldwind/src/iosMain/kotlin/earth/worldwind/WorldWindow.kt` imports
  `platform.EAGL.EAGLContext`, `kEAGLRenderingAPIOpenGLES3`, `kEAGLColorFormatRGBA8`,
  `kEAGLDrawablePropertyRetainedBacking`, `presentRenderbuffer`, `renderbufferStorage`,
  `platform.QuartzCore.CAEAGLLayer` and `CADisplayLink`, and creates the context with
  `EAGLContext(aPI = kEAGLRenderingAPIOpenGLES3)` followed by `EAGLContext.setCurrentContext(ctx)`.
  Its GL binding, `util/kgl/IosKgl.kt`, imports `platform.gles3.*` directly including
  `glBlitFramebuffer` and the ES 3 sync tokens. This is the closest published analogue to what Cycle H
  needs: a full 3D renderer, GLES 3, stock klibs, **no cinterop**.
  One interop wrinkle visible in that file: ES 3 sync objects arrive as
  `cnames.structs.__GLsync`, an opaque cinterop struct name rather than a Kotlin type. RenG does not
  use `fenceSync`/`clientWaitSync` anywhere (**D** — `grep` over `kmp/src` finds no match), so this
  costs nothing today, but it would if the seam ever grows fences.
- **[Kotlin/kotlinconf-spinner](https://github.com/Kotlin/kotlinconf-spinner)** **(A)** — JetBrains'
  own Kotlin/Native sample. `clients/ios/src/iosMain/kotlin/main.kt` uses `platform.EAGL`;
  `clients/shared/src/nativeMain/kotlin/gl.kt` and `rendering.kt` use `platform.gles3`. First-party
  evidence that JetBrains ships this combination as a demonstrable path.
- **[korlibs/korge](https://github.com/korlibs/korge)** —
  `korge-core/src/iosTvosMain/kotlin/korlibs/kgl/KmlGlContextAppleMobile.kt` implements the engine's
  iOS GL context in ~30 lines of `platform.EAGL`. **It uses ES 2**, and the ES 3 line is present but
  commented out:
  ```kotlin
  //val context = EAGLContext(kEAGLRenderingAPIOpenGLES3, shareGroup)
  val context: EAGLContext = if (shareGroup != null) EAGLContext(kEAGLRenderingAPIOpenGLES2, shareGroup)
                             else EAGLContext(kEAGLRenderingAPIOpenGLES2)
  ```
  The file gives no reason, so do not read one into it — but a shipping engine choosing ES 2 on iOS
  when the ES 3 call was written and then commented out is worth knowing before RenG commits to ES 3
  there.
- **[gergelydaniel/kgl](https://github.com/gergelydaniel/kgl)** `kgl-ios/.../KglIos.kt`,
  **[kamgurgul/cpu-info](https://github.com/kamgurgul/cpu-info)** `GpuDataProvider.ios.kt`,
  **[inoutch/kotchan](https://github.com/inoutch/kotchan)**,
  **[TaehoonLeee/camera-multiplatform](https://github.com/TaehoonLeee/camera-multiplatform)** — four
  more independent uses of `platform.gles3` / `platform.EAGL` from `iosMain`.
- **[PreOnZi/JustACalculator](https://github.com/PreOnZi/JustACalculator)** **(C)** — a small single
  repository, but the file `app/src/iosMain/.../gl/OffscreenGl.ios.kt` is precisely the fixture shape
  Cycle H needs and its own comment states the design constraint:
  > "iOS has no pbuffer: an EAGLContext draws into a framebuffer, so the offscreen target is a plain
  > FBO with colour and depth renderbuffers. `glReadPixels` reads from whatever framebuffer is bound."

  It creates `EAGLContext(kEAGLRenderingAPIOpenGLES2)`, `setCurrentContext`, generates an FBO with an
  `GL_RGBA8` colour renderbuffer and a `GL_DEPTH_COMPONENT16` depth renderbuffer, and checks
  `glCheckFramebufferStatus`. It also restores the previously current context on release. One repo, so
  grade C — but it is the concrete answer to "how does the iOS fixture differ from macOS CGL and Linux
  surfaceless EGL", and it agrees with what WorldWindKotlin does on the drawable path.

**So: the answer to "has anyone actually driven OpenGL ES from Kotlin/Native on iOS" is yes, several
people, including JetBrains and a 3D globe renderer, and RenG's own `IosGlBinding.kt` shows up in the
same code search.** The unknown was never the binding; it is the **offscreen context** and whether it
survives the simulator (fact 1).

### 3. Rentile's Android artifact depends on a Skiko artifact that contains **no native library at all**

`CLAUDE.md` records that Android host tests "cannot execute Rentile's Skia rasterization at all, which
was measured with error codes", and the preflight asks whether that holds on a device. The dependency
graph says the host/device axis is not the whole story.

**D — verified over the public network:**

- `https://maven.rohittp.com/com/rohittp/rentile/kmp-android/0.5.0/kmp-android-0.5.0.pom` declares
  exactly seven dependencies, one of which is:
  ```xml
  <dependency>
    <groupId>org.jetbrains.skiko</groupId>
    <artifactId>skiko-android</artifactId>
    <version>0.148.2</version>
    <scope>runtime</scope>
  </dependency>
  ```
- `skiko-android-0.148.2.aar` is **934,512 bytes and contains four entries**: `R.txt`,
  `AndroidManifest.xml`, `classes.jar`, `META-INF/…/aar-metadata.properties`. There is **no `jni/`
  directory and no `.so`**.
- Its Gradle module metadata lists three variants; the runtime variant's only file is that same AAR,
  and its dependencies are `kotlinx-coroutines-*` and `kotlin-stdlib`. **Nothing in the graph pulls a
  native library.**
- The native library lives in separate artifacts that nothing declares:
  `org.jetbrains.skiko:skiko-android-runtime-arm64` and `…-x64`, published as **plain JARs** (no AAR
  variant: the `.aar` URL 404s, the `.jar` URL 200s). `skiko-android-runtime-arm64-0.148.2.jar` is
  10,550,170 bytes and contains exactly `libskiko-android-arm64.so` (30,391,808 bytes) plus its
  `.sha256` — **at the JAR root**, not under `lib/<abi>/`.
- Neither `skiko-android` nor the runtime artifacts exist on Maven Central: a full listing of
  `https://repo1.maven.org/maven2/org/jetbrains/skiko/` returns 34 artifact directories and none of
  them is `skiko-android*`. They are on the JetBrains Compose dev repository — which
  `settings.gradle.kts` already declares, filtered to `includeGroup("org.jetbrains.skiko")`, so no new
  repository is needed.

**What this means for Cycle H's Android half.** Moving from `androidHostTest` to
`androidInstrumentedTest` puts the test on a real Android runtime where a `.so` *can* be loaded — but
it does not by itself put a `libskiko-android-*.so` anywhere, because no artifact in Rentile's Android
graph carries one. The experiment that decides whether the basemap readback suite can run on Android at
all is therefore small and specific: add
`org.jetbrains.skiko:skiko-android-runtime-<abi>:0.148.2` to the instrumented test source set and see
whether Skiko's loader finds it. Because the `.so` sits at the JAR root rather than under `lib/<abi>/`,
the Android Gradle Plugin's normal JNI-extraction convention does not apply and Skiko's own extraction
path has to do the work — which is exactly the mechanism the field reports below are about.

Corroborating field reports on that mechanism:

- [JetBrains/skiko #760, "Snapshot testing on Android"](https://github.com/JetBrains/skiko/issues/760)
  **(A/B)** — July 2023, snapshot tests **on an Android emulator** fail with
  `UnsatisfiedLinkError: dlopen failed: cannot locate symbol "_ZN4sksg4NodeD2Ev" referenced by
  "libskiko-android-x64.so"`, during static init of Skia's `ParagraphStyle`. Closed with no visible
  maintainer explanation.
- [JetBrains/skiko #970](https://github.com/JetBrains/skiko/issues/970) **(A/B)** —
  `dlopen failed: library "libskiko-android-arm64.so" not found` at app runtime in a KMP project
  targeting iOS and Android with Skiko 0.8.4. Closed with no visible resolution.
- [JetBrains/skiko #761](https://github.com/JetBrains/skiko/issues/761),
  [#876](https://github.com/JetBrains/skiko/issues/876),
  [#838](https://github.com/JetBrains/skiko/issues/838) **(B)** — more of the same family, including a
  SIGSEGV inside `libskiko-linux-arm64.so`.
- The generic host-vs-instrumented mechanism is well documented in
  [robolectric#1516](https://github.com/robolectric/robolectric/issues/1516) and
  [robolectric#9099](https://github.com/robolectric/robolectric/issues/9099) **(B)** and in
  [sureshjoshi.com, "Android JUnit Testing for Native Libraries"](https://sureshjoshi.com/mobile/android-junit-native-libraries)
  **(C)**: a host JVM unit test links against the host's libraries, not Android's, so an NDK-built
  `.so` cannot load; the standard advice is "run it as an instrumented test instead". That advice is
  correct in general and **insufficient here**, because the artifact carrying the `.so` is not in the
  graph on either side.

---

## Question by question

### Q1 — Has anyone driven OpenGL ES from Kotlin/Native on iOS?

Answered above (fact 2): yes, and by more people than expected. Summary of what worked and what did
not:

**Worked.** `platform.EAGL` (context creation, `setCurrentContext`, `sharegroup`, `presentRenderbuffer`,
`renderbufferStorage`, the `kEAGLDrawableProperty*` keys), `platform.gles3` (the full ES 3 entry point
set including `glBlitFramebuffer`), `platform.QuartzCore.CAEAGLLayer` and `CADisplayLink`, all from
`iosMain`, all through stock klibs. No project found used cinterop for GL on iOS.

**Did not, or needed care.**

- ES 3 sync objects surface as `cnames.structs.__GLsync` (WorldWindKotlin `IosKgl.kt`) — usable, but it
  is a raw cinterop struct name leaking into application code. Irrelevant to RenG today (**D**: no
  fence usage in `kmp/src`).
- KorGE ships ES **2** on iOS with the ES 3 constructor commented out. Unexplained in-source; flagged,
  not interpreted.
- `OpenGLES.def` is ES 1. Anyone reaching for "the OpenGLES klib" by that name gets the fixed-function
  pipeline. (**D**, from the `.def` file.)

**JetBrains-side defects in the GL platform libraries** — two open YouTrack tickets, both grade **A**,
both about the **Android-native** GL def files rather than iOS, and both **still unresolved six years
after filing**:

- [KT-38626](https://youtrack.jetbrains.com/issue/KT-38626) "[Kotlin Native] Missing
  `eglPresentationTimeANDROID` in `platform.egl`", created 2020-04-28, unresolved.
- [KT-38779](https://youtrack.jetbrains.com/issue/KT-38779) "[Platform Libs] Missing headers in
  Android: `GLES3/gl31.h` and `GLES3/gl32.h`", created 2020-05-06, unresolved. The `gles3.def` for
  Android includes `GLES3/gl3.h` only, so ES 3.1/3.2 entry points are absent from `platform.gles3` on
  `androidNative*`.

Read those as a calibration on JetBrains' responsiveness to GL platform-library gaps, not as an iOS
risk: iOS's `OpenGLES3.def` includes `glext.h`, and iOS never had ES 3.1 anyway. Relevant to RenG only
if a future target is `androidNative*`; RenG's Android binding is JVM (**D**:
`kmp/src/androidMain/.../AndroidGlBinding.kt` imports `android.opengl.GLES30`).

### Q2 — What breaks in practice on recent iOS?

**On device, OpenGL ES still works and the SDK still ships it.**

**D — verified locally.** The installed Xcode has `iPhoneOS26.5.sdk` and `iPhoneSimulator26.5.sdk`, and
**both** contain `System/Library/Frameworks/OpenGLES.framework` with `Headers/{EAGL.h, EAGLDrawable.h,
EAGLIOSurface.h, ES1/, ES2/, ES3/, gltypes.h}`; `ES3/` has both `gl.h` and `glext.h`. `EAGL.h` marks
its declarations `OPENGLES_DEPRECATED(ios(2.0, 12.0), tvos(9.0, 12.0))` — **deprecated, not
`API_UNAVAILABLE`**. The simulator stub library exports `_EAGLGetVersion`, `_glDrawElements`,
`_glReadPixels`. Nothing about the iOS 26.5 SDK removes the API.

**No Apple statement of removal exists that I could find.** On
[Apple Developer Forums 725247, "OpenGL on future iPhones and Macs?"](https://developer.apple.com/forums/thread/725247)
(February 2023) **(C — no Apple engineer replied; the whole thread is developers)**, the community
consensus is that OpenGL is frozen and emulated on Metal on Apple silicon, with no removal announced.
Treat "OpenGL ES will keep working on iOS 27" as unsourced either way.

**Device-side breakages found, all narrow.**

- [Apple Developer Forums 707644, "OpenGL crashes (iOS 16)"](https://developer.apple.com/forums/thread/707644)
  **(C)** — June 2022, `EXC_BAD_ACCESS` on `presentRenderBuffer` under iOS 16 beta. The original poster
  reported it **fixed in beta 3**; one participant said it was simulator-only and the device was fine;
  a fourth reported it still present in the **iOS 16.1 release**. The thread contradicts itself and no
  Apple engineer replied. Weak, and listed mainly because it is the thread most often surfaced by
  searches for this topic.
- [Apple Developer Forums 771450](https://developer.apple.com/forums/thread/771450) **(C)** — a single
  report of a 3D volume-rendering performance regression on iOS 18.2 relative to 18.1 in an app using
  WebGL/OpenGL. One report, no diagnosis. Insufficient to claim "OpenGL ES got slower on iOS 18".
- [Apple Developer Forums 778735, "WebGL is crashing in IOS 18.2 and 18.3"](https://developer.apple.com/forums/thread/778735)
  **(B — many reporters)** — CesiumJS loses its WebGL context on 3D view init across iPad and iPhone
  models after upgrading to 18.2/18.3. **This is not EAGL**: Safari's WebGL runs on ANGLE's Metal
  backend, so this belongs to Q5 as evidence about ANGLE-on-iOS in production, not to EAGL's record.

**Silently-failing entry points:** none found. Every recent report is a crash or a black frame, not a
no-op. If RenG hits a silent failure on iOS it will be the first published instance I can find, which
is itself a reason for the fixture to assert `glGetError()` and framebuffer completeness rather than
assume them.

### Q3 — The simulator specifically

The centre of gravity of this whole document. Covered as fact 1; the additional simulator-specific
material is:

**Simulator GLES is not the device's GLES, by Apple's own retired documentation.**
[OpenGL ES in iOS Simulator](https://developer.apple.com/library/archive/documentation/OpenGLES/Conceptual/OpenGLESHardwarePlatformGuide_iOS/OpenGLESiniOSSimulator/OpenGLESiniOSSimulator.html)
**(A, but retired)** states the simulator does not support the same extension set as the device GPUs
and does not use a tile-based deferred renderer. So `glGetString(GL_RENDERER)`, `GL_EXTENSIONS` /
`glGetStringi` and the tiling behaviour all differ between `iosSimulatorArm64Test` and
`iosArm64Test`. Any RenG assertion that reads the renderer string or an extension list will read
something different on the two iOS targets, exactly as it already does between macOS and llvmpipe.

**A second, RoboVM-flavoured simulator failure.**
[libgdx #6895, "[iOS] OpenGL backend not working on arm64 simulator (silicon)"](https://github.com/libgdx/libgdx/issues/6895)
**(B, with a caveat)** — opened by Berstanio (a libGDX maintainer) on 2022-06-11. The app crashes after
launch, preceded by repeated `'cyclone' is not a recognized processor for this target (ignoring
processor)`. He judged it an Apple-level limitation rather than a libGDX bug and listed two
workarounds: **use the MetalANGLE backend**, or **run the simulator under Rosetta**. Still open.
*Caveat:* libGDX's iOS target is RoboVM AOT-compiled, and the `'cyclone'` message is a
compiler/target-triple artefact of that toolchain, so this may be a narrower problem than the CoreVM
crash. It is included because the two named workarounds — ANGLE, or an x86_64 simulator under Rosetta —
are the two escape hatches the field actually uses, and because a Kotlin/Native build cannot use the
first without the whole of Q4/Q6.

**Rasteriser-difference risk, stated plainly.** `0.3.0` failed once because
`Apple Software Renderer` disagreed with an M3 Max about large-quad rasterisation, and the fix
(`measureLargeQuadRasterisation`, `BasemapReadbackSuite.kt:669`) was to **measure the driver rather
than name it**. Every source above says the iOS simulator's GLES is a different implementation from the
device's — routed through CoreVM shader compilation rather than a tile-based GPU. That is the same
class of difference that broke `0.3.0`, on a fourth rasteriser. The existing probe pattern is the
right answer and should be applied to the iOS fixture from the first commit rather than after the first
red build.

### Q4 — Who ships ANGLE on iOS, and how?

**The one unambiguous production deployment is WebKit, and it is a system framework, not an
integration anybody else can copy.** ANGLE is WebKit's WebGL backend and lives vendored in-tree at
[`Source/ThirdParty/ANGLE`](https://github.com/WebKit/webkit/tree/main/Source/ThirdParty/ANGLE) **(A)**.
On the ANGLE mailing list, Ken Russell (ANGLE/Chrome) wrote on 2020-08-18 **(A)**:

> "ANGLE's OpenGL ES 3.0 backend works on iOS" — and is used for Safari's WebGL 2.0.
> ([thread](https://groups.google.com/g/angleproject/c/DkD0rdMQCbM))

and on 2021-02-24 **(A)**, answering a direct question about an iOS ETA
([thread](https://groups.google.com/g/angleproject/c/11wLJvlYefQ)):

> "We're actively collaborating with Apple on ANGLE's Metal backend... This backend will support iOS."

followed on 2021-06-25 by: *"Upstreaming of Apple's direct-to-Metal backend is actively underway... We
do not have an ETA for when this will be complete."*

**Building ANGLE for iOS is not a supported standalone operation.** ANGLE's own
[`doc/DevSetup.md`](https://chromium.googlesource.com/angle/angle/+/HEAD/doc/DevSetup.md) **(A)** says
iOS building is *"currently possible only from Chromium checkout. Follow Chromium for iOS build
instructions."* The supported iOS targets named are `angle_end2end_tests` and `angle_white_box_tests`
— test binaries, not a shipping library configuration. ANGLE's `doc/` directory has 39 files and
**none of them is an iOS document** (`MetalBackendShaders.md` and `DevSetupAndroid.md` exist; there is
no `DevSetupIOS.md`).

**It is nonetheless done, from community-supplied GN args.** On
[angleproject "Is iOS device support available in ANGLE? Is there an official build guide?"](https://groups.google.com/g/angleproject/c/pLBQT6eQpok)
**(C — no maintainer replied)**, June 2025: Jian Chen hit a PartitionAlloc runtime error building
standalone; Guohao Zhu pointed at Flutter's ANGLE PRs, *"I built ios angle with these arguments. And it
worked fine."*; Honey Bee posted a full GN config with `target_os = "ios"`, `target_cpu = "arm64"`,
`angle_enable_metal = true` and notably `angle_enable_abseil = false`, saying *"This works for me on
device."*; Jian Chen confirmed *"thanks, that works for me."* Three independent people in one 2025
thread, no official blessing. Recent and specific, but grade C.

**The purpose-built iOS fork is unmaintained.**
[kakashidinho/MetalANGLE](https://github.com/kakashidinho/metalangle) **(B)** is the ANGLE fork that
added Metal and an iOS-friendly `MGLKit` (an analogue of Apple's deprecated GLKit: `MGLContext`,
`MGLLayer`, `MGLKView`, `MGLKViewController`), with Xcode projects under `ios/xcode`. Its own README
states it is **"currently not in active development"** because the author joined Google, that the
Metal backend was merged upstream from June 2021, and that MetalANGLE's *iOS support and MGLKit are
unlikely to be merged upstream*. It is also the backend libGDX's maintainer recommends for the
simulator problem in Q3 — so the field's named workaround points at an unmaintained fork.

**App Store acceptance: no evidence of rejections, and no policy statement either.** The strongest
thing found is uncertainty from a well-placed engineer — Ken Russell, same 2020 thread **(A)**:
*"I'm not 100% sure whether new iOS App Store submissions are allowed to use OpenGL ES."* I found no
report of an App Store rejection for using OpenGL ES, and no Apple statement permitting it. See "Where
I searched and found little".

**Cost of integration — build system, linking, size.** No published account of a third party
integrating ANGLE into a *shipping iOS app* was found with size or link-mode numbers. What can be said:
iOS builds require a Chromium checkout (**A**, above); ANGLE is statically linkable (the community GN
args build device libraries); and the size cost is unmeasured here. For a size *analogue* see Q6.

### Q5 — What breaks with ANGLE on iOS?

**The single most quotable field verdict is about macOS, not iOS, and must be labelled as such.**
Godot's platform maintainer bruvzg opened
[godotengine/godot#85785](https://github.com/godotengine/godot/pull/85785) **(A — verbatim from the PR
body via the GitHub API)**, merged 2023-12-05, whose entire rationale is:

> "I think it's pretty clear that using ANGLE over Metal on macOS was a total failure. Unlike ANGLE over
> DX11 on Windows which seems to be doing a good job for supporting older hardware, it's causing a lot
> more issues than fixing."

It switched macOS's ANGLE backend to ANGLE-over-OpenGL and the default compatibility renderer back to
native, closing [#85619](https://github.com/godotengine/godot/issues/85619) and
[#84591](https://github.com/godotengine/godot/issues/84591). Reported symptoms in the discussion
include GPU-particle crashes on Apple silicon, memory exhaustion, and TileMap performance degradation.
Godot subsequently went to a **native Metal** driver for macOS arm64 and iOS
([#88199](https://github.com/godotengine/godot/pull/88199)) rather than back to ANGLE.
**Caveat, stated because the owner asked for it:** this is macOS in 2023, not iOS in 2026, and the
Metal backend has had two and a half more years of Apple's contributions since. It is the strongest
negative field report about ANGLE-over-Metal in existence and it is also the one most likely to be out
of date.

**Concrete feature gaps, from MetalANGLE's own README (B):**

- **No `GL_TRIANGLE_FAN`, no `GL_LINE_LOOP`.** (**D** — irrelevant to RenG: a grep of `kmp/src` finds
  only `GL_TRIANGLES` (14 sites) and `GL_TRIANGLE_STRIP` (8 sites). No fan, no line loop, no points.)
- **Metal requires buffer offsets to be multiples of 4 bytes.** RenG would need to check its vertex
  layouts against this; not audited here.
- **"Only 90%+ of OpenGL ES 3.0 conformance tests pass."** That is MetalANGLE's own number for its own
  fork, not upstream ANGLE's, and it predates the upstreaming.

**Upstream ANGLE Metal backend gaps (B):** vertex-buffer data conversion is the recurring theme —
attribute strides and offsets Metal cannot consume natively (e.g. a `vec4` at stride 12) get converted
at draw time, and this shows up as a performance regression, tracked at
[Chromium issue 40286880, "Performance Regression with ANGLE Metal backend"](https://issues.chromium.org/issues/40286880).
Provoking-vertex behaviour differs and needs `WEBGL_provoking_vertex` where available.

**Production ANGLE-on-iOS instability, indirect (B):**
[Apple Developer Forums 778735](https://developer.apple.com/forums/thread/778735) — many reporters lose
the WebGL context on CesiumJS init across iPhone and iPad after upgrading to iOS 18.2/18.3. Safari's
WebGL is ANGLE-over-Metal on iOS, so this is field evidence that ANGLE's Metal backend on iOS is not
trouble-free in the configuration Apple itself ships and maintains. It is *indirect*: "WebGL context
lost" has many possible causes above ANGLE, and no root cause is established in the thread.

### Q6 — What would ANGLE mean for a Kotlin/Native consumer?

**Nobody has driven ANGLE from Kotlin/Native, anywhere, on any platform, as far as public code shows.**

**D — the search, so it is not repeated.** `gh search code` over Kotlin:

- `angle cinterop language:kotlin` — 20 hits, all irrelevant (`androidx/androidx` geometry `AngleNative`,
  Kotlin compiler internals matching "cinterop", RosettaCodeData).
- `libGLESv2 language:kotlin` — 20 hits. The ANGLE-related ones are **all JVM**:
  [`JetBrains/skiko` `skiko/src/jvmMain/kotlin/org/jetbrains/skiko/AngleSupport.jvm.kt`](https://github.com/JetBrains/skiko),
  `JetBrains/kotlin-desktop-toolkit` `buildSrc/.../DownloadAngleTask.kt`,
  [`maplibre/maplibre-compose` `lib/maplibre-compose/src/jvmMain/.../AngleSymbols.kt`](https://github.com/maplibre/maplibre-compose),
  `openrndr/openrndr` `openrndr-jvm/openrndr-gl3/src/commonJvmMain/.../angle/Angle.kt`. Skiko even
  **publishes** ANGLE binaries — `skiko-awt-runtime-angle-windows-x64` and
  `…-angle-windows-arm64` are real artifacts on Maven Central (**D**, from the repo1 directory
  listing) — but that is AWT on Windows.
- `headers=EGL/egl.h` — 2 hits, both copies of a Kotlin/Native distribution's own `android_arm{32,64}`
  `egl` platform-klib manifest.

No hit is Kotlin/Native, and none is iOS. **This is a well-documented absence, and it means Cycle H
would be inventing the pattern rather than following one.**

**The nearest analogue is strong, first-party, and measurable: Skiko.** JetBrains ships a large
third-party C++ native library to Kotlin/Native iOS consumers through a cinterop klib.

**D — verified by downloading and opening `skiko-iosarm64-0.148.2.klib`** (57,634,037 bytes) from the
JetBrains Compose repository. It contains, among 300 entries:

```
default/targets/ios_arm64/included/libskia.a              35,715,144
default/targets/ios_arm64/included/libskunicode_icu.a     28,099,464
default/targets/ios_arm64/included/libicu.a               27,941,912
default/targets/ios_arm64/included/libskshaper.a          12,305,648
default/targets/ios_arm64/included/libharfbuzz.a          12,095,136
… 7 more .a files …
```

~164 MB of static archives, uncompressed, inside one klib for one target. That is the cinterop
`staticLibrary` / `libraryPaths` mechanism working in production, and it is the mechanism RenG would
use for ANGLE.

**What that implies for RenG, stated as constraints rather than a recommendation:**

- **The C++ objection does not apply.** Kotlin/Native's cinterop is C and Objective-C, not C++
  ([Kotlin docs, C interop](https://kotlinlang.org/docs/native-lib-import-stability.html)) — but
  EGL and GLES are C ABIs. Binding `EGL/egl.h` + `GLES3/gl3.h` and linking `libEGL.a`/`libGLESv2.a`
  is a C interop, regardless of ANGLE being C++ inside.
- **The binaries must be built per target from a Chromium checkout** (Q4), for `ios_arm64` *and*
  `ios_simulator_arm64`, and rebuilt on every ANGLE bump. RenG's build has nothing like this today.
- **It ends two stated properties at once.** RenG has "no cinterop and no third-party native binary on
  any target", and `CLAUDE.md`'s platform-targets section records that "every published target is a
  permanent commitment". A cinterop klib carrying a vendored ANGLE would be published per iOS target
  and is not quietly reversible.
- **The bar the macOS harness set is the relevant comparison.** That harness reaches CGL "with no
  cinterop at all" through stock `platform.OpenGLCommon` / `platform.OpenGL3`. Fact 2 shows the iOS
  equivalent — `platform.EAGL` + `platform.gles3` — clears the same bar. ANGLE does not, and would be
  the first thing in the project that does not.

### Q7 — Headless GLES in Android instrumented tests

**The canonical pattern is EGL14 + a pbuffer, and Google wrote the reference implementation.**

- [google/grafika `gles/EglCore.java`](https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/gles/EglCore.java)
  and [`gles/OffscreenSurface.java`](https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/gles/OffscreenSurface.java)
  **(A — Google Inc., Apache 2.0)**. `OffscreenSurface` is documented as *"Off-screen EGL surface
  (pbuffer)"* and is a two-line subclass over `createOffscreenSurface(width, height)`.
  [`ReadPixelsActivity.java`](https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/ReadPixelsActivity.java)
  drives exactly the shape RenG needs: `EglCore` + `OffscreenSurface` + `glReadPixels`.
- **A caution that is easy to miss.** `EglCore` declares
  `FLAG_TRY_GLES3 = 0x02` with the comment *"ask for GLES3, fall back to GLES2 if not available.
  Without this flag, GLES2 is used."* Google's own reference does not assume ES 3 is obtainable.
  `AndroidGlBinding` is written against `android.opengl.GLES30` (**D**), so the fixture must *require*
  ES 3 and fail loudly if the config request cannot be satisfied, rather than silently falling back —
  the same discipline `CLAUDE.md` records for the `0.3.0` driver probe ("a probe that cannot run fails
  rather than skips").
- AOSP CTS uses the same approach; e.g.
  [`android.opengl.cts.FramebufferTest`](https://android.googlesource.com/platform/cts/+/8fabf13/tests/tests/opengl/src/android/opengl/cts/FramebufferTest.java)
  **(A)**. Andy McFadden's bigflake material, the origin of Grafika, describes its samples as
  *"headless CTS tests"* — self-contained and independent of the app lifecycle, which is precisely the
  property a RenG instrumented test wants.
- A supported modern alternative is androidx `graphics-core`'s `EGLManager` / `EGLSpec` /
  [`GLRenderer`](https://androidx.tech/artifacts/graphics/graphics-core/1.0.0-alpha02-source/androidx/graphics/opengl/GLRenderer.kt.html)
  **(A)**, which wraps the same EGL calls. Whether the extra dependency is worth it in a test source
  set is a design call, not a field finding.

**What fails, on emulators specifically.**

- `eglChooseConfig() returned no configs` is the characteristic emulator failure —
  [mapbox-gl-native #2144](https://github.com/mapbox/mapbox-gl-native/issues/2144) **(B)** and
  [google-ar/arcore-android-sdk #1220, "eglChooseConfig failed on Android Emulator using HelloAR"](https://github.com/google-ar/arcore-android-sdk/issues/1220)
  **(B)**. The mechanism is that an emulator without usable GPU support falls back to a software
  renderer that cannot satisfy the requested config. A pbuffer request is a *config* request, so this
  is directly on RenG's path.
- GLES level in the emulator is an AVD setting, not a given: **Advanced → OpenGL ES API level →
  "Renderer maximum"** (up to ES 3.1), and the default `auto` may select less. A fixture that asserts
  ES 3 should also log what it actually got.
- **No report was found of a fundamental blocker** — a pbuffer GLES 3 context in
  `androidInstrumentedTest` is ordinary practice on both emulators and devices, provided the config
  request is satisfiable. The risk is configuration, not capability.

### Q8 — ARM64 Android emulators on Apple silicon

**What the emulator can be told to use.** `-gpu` accepts `auto`, `host`, `swiftshader_indirect`,
`angle_indirect`, and `guest`. Google's
[emulator release notes](https://developer.android.com/studio/releases/emulator) **(A)** state that on
Apple silicon Macs the emulator uses **MoltenVK** for Vulkan when hardware rendering is selected, that
*"the library doesn't support all Vulkan features"*, and that the documented remedy for shader
compilation failures, graphical glitches or crashes is to **switch to software rendering** — AVD
settings or `-gpu swiftshader`. `gfxstream` is the guest↔host command serialisation layer underneath.

**What I could not establish.** No Google source I found states which implementation backs **GLES**
(as opposed to Vulkan) for an arm64 guest on an Apple silicon host — host GL via Metal emulation,
ANGLE (`angle_indirect` exists as a mode), or SwiftShader. Search results asserting "the Android
Emulator on Apple Silicon uses ANGLE with Metal/Vulkan backends through gfxstream" were aggregator
prose without a primary citation, and I am **not** carrying that claim. **This is the right thing for
the fixture to answer rather than the design document**: read `glGetString(GL_VENDOR)` and
`GL_RENDERER` in the instrumented test and record what comes back, exactly as RenG already does for the
driver probe.

**Why this matters for tolerances — the direct answer to the question behind the question.**
Google's own guidance says the fallback for graphics trouble on Apple silicon is a **software
renderer**. `0.3.0` failed closed because a software rasteriser (`Apple Software Renderer`) disagreed
with a GPU about large-quad coverage. So the Android emulator can silently land RenG on the exact
rasteriser class that has already broken it once, and it can do so as a *remedy* for an unrelated
problem — which means the AVD's graphics setting is a load-bearing part of the test environment and
belongs in the plan, not in someone's local `~/.android/avd`.

**Field evidence on device-vs-emulator pixel divergence.** The clearest engineering write-up is
[Android Testify, "Accounting for platform differences"](https://ndtp.github.io/android-testify/blog/platform-differences)
**(B)**:

> "The CPU (M1 or x86), the GPU (discrete or integrated), emulator image (arm64, x86 or x86_64) and
> emulated performance settings (number of CPU cores, emulated Graphics – Hardware or Software GLES 1.1
> or GLES 2.0) will all impact the mathematical calculations"

It names shadows/elevation, font smoothing and anti-aliasing, image decode, and alpha
blending/compositing as the most-affected areas, attributes the divergence to float→integer rounding at
capture (`0.619f × 255` landing on 157 or 158), and recommends a Delta-E tolerance between 0.7 and 0.9,
*"starting with 0.9f and reducing the value in increments of 0.05f"*. That is a screenshot-testing
recommendation, and RenG deliberately does **not** do golden images (Cycle J), so the number does not
transfer — but the causal list does, and it is a direct argument for keeping the analytical-readback
approach on Android rather than reaching for baselines.

There is also a long tail of emulator-specific instability on Apple silicon in Google's own release
notes **(A)** — an M1 hang (31.2.7, Feb 2022), high-rate crashes on cursor focus changes on M1
(32.1.13, May 2023), and a macOS 26.3 memory leak on close (36.6.11, Jun 2026). None is GLES-specific;
they are background noise a CI-less manual Android gate will encounter.

### Q9 — Skia in an Android instrumented test

The decisive finding is fact 3 above: **Rentile's Android graph carries no Skia native library at
all**, so the host-versus-device framing is not the whole explanation for `CLAUDE.md`'s "cannot execute
Skia at all". Beyond that:

- **The generic mechanism is real and well documented** — a host JVM test cannot `dlopen` an
  Android-ABI `.so`, and the standard remedy is to run the test as an instrumented test
  ([robolectric#1516](https://github.com/robolectric/robolectric/issues/1516),
  [robolectric#9099](https://github.com/robolectric/robolectric/issues/9099), **B**). It is a
  *necessary* step here, not a sufficient one.
- **Even with the `.so` present, Skiko on Android has a load-failure history.** skiko
  [#760](https://github.com/JetBrains/skiko/issues/760) reports `dlopen failed: cannot locate symbol
  "_ZN4sksg4NodeD2Ev" referenced by "libskiko-android-x64.so"` **on an emulator** during Skia
  `ParagraphStyle` static initialisation — a *symbol* resolution failure, not a missing file, which is
  the harder kind. [#970](https://github.com/JetBrains/skiko/issues/970) is the missing-file kind on
  arm64. Both closed without a visible maintainer explanation, which limits how much can be inferred.
- **ABI selection is a real decision, not a detail.** `skiko-android-runtime-arm64` and
  `skiko-android-runtime-x64` are separate artifacts (**D**). An arm64 AVD on Apple silicon needs the
  arm64 one; the preflight's two AVDs (`Pixel_10_Pro_XL`, `Pixel_4_XL_API_29`) are presumably arm64
  system images on this host, but that was not verified here and should be before the experiment.
- **Attribution stays split regardless.** `docs/decomposition.md:255` and `HANDOFF.md:643-645` already
  record that Rentile draws the basemap through Skia and RenG only composites it, so an Android
  basemap readback result conflates the two. If Skia does not load on Android, the honest outcome is
  that Android runs the GL conformance suite and the model/sticker/geometry readbacks but **not** the
  basemap readback — which matches what
  `docs/research/2026-08-21-golden-image-gate-design.md:123` already tabulates for the other targets.

---

## Where I searched and found little

Recording these so nobody repeats them.

- **A JetBrains YouTrack ticket about the iOS GL platform libraries.** Queried the YouTrack REST API
  with `project: KT` against `OpenGLES`, `EAGL`, `gles3`, and `OpenGL Native`. The only GL
  platform-library tickets returned are **Android-native**: KT-38626 and KT-38779, both open since
  2020. `project: KT EAGL` returns exactly two issues and **neither is about EAGL** (a `T::class`
  inlining bug and a compiler-cache investigation) — the term matched fuzzily. There is **no
  Kotlin/Native iOS OpenGL ES ticket**. Read that as "the klibs work well enough that nobody files",
  not as "nobody uses them" — Q1 shows people do.
- **Any use of ANGLE from Kotlin/Native.** Three separate `gh search code` queries (Q6). Zero hits on
  any platform. Every Kotlin↔ANGLE binding in public code is JVM desktop.
- **An App Store rejection for shipping OpenGL ES.** Searched for rejections in the 2024–2025 window
  and for an Apple policy statement. Found neither — only Ken Russell's 2020 uncertainty, and repeated
  community observation that OpenGL ES "has survived all along, with no announced removal date". Both
  "you can still ship it" and "you can't" are unsourced.
- **A published account of integrating ANGLE into a shipping third-party iOS app**, with binary size,
  static-versus-dynamic link mode, or review outcome. Not found. The only iOS build recipes are a 2025
  mailing-list thread of GN args (grade C) and an unmaintained fork's Xcode projects.
- **A Google statement on which GLES implementation backs an arm64 guest on an Apple silicon host.**
  Not found (Q8). The Vulkan/MoltenVK statement is documented; the GLES one is not.
- **Any report of RenG's specific risk — a mobile GPU disagreeing with a desktop GPU on
  tolerance-based GL readback.** The published mobile-GL material is about crashes and screenshot
  diffs, not analytical readback. There is no field precedent for what RenG is about to do on iOS and
  Android, which means the readback tolerances will be calibrated for the first time by this cycle and
  not validated against anyone else's numbers.
- **Anything at all about OpenGL ES on iOS 26.** Every simulator report found stops at iOS 17.x; the
  most recent is October 2025 against iOS 17.5. The two simulator runtimes this machine has (26.0,
  26.2) are **two major versions past the newest published report**, in both directions: the CoreVM
  crash may have been fixed, or the surface may have degraded further. Nobody has written it down.
