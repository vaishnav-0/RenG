# Cycle H — Android and iOS Bring-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the preflight's one-off demonstration into a permanent gate, so a regression on Android or iOS is caught by a test run rather than by a consumer.

**Architecture:** Two new test source sets carrying platform context fixtures, the existing readback suites relocated so one copy serves all six targets, an explicitly widened policy gate, and one Python tool so the iOS device run is a single command.

**Tech Stack:** Kotlin Multiplatform, six targets. `platform.EAGL` on iOS, `android.opengl.EGL14` on Android — both stock, no cinterop.

**Spec:** `docs/superpowers/specs/2026-08-28-cycle-h-mobile-bring-up-design.md`

## Global Constraints

- **The public ABI must not move.** `kmp/api/kmp.klib.api` is byte-identical at the end of this cycle. A diff there is a defect, not a decision — everything here is a test source set, a relocation within test source sets, a Python tool, CI configuration, or documentation.
- Keep exactly six targets. Keep `explicitApi()`.
- No cinterop, no new Gradle subproject, no new **production** dependency. The one new test dependency is `androidx.test:runner`, catalogued and allowlisted by Task 4 and by nothing else.
- Standard-library Python only in `tools/`, with unit tests in `tools/tests/`. The repository contains no shell scripts; do not add one.
- Every Gradle invocation passes `--no-configuration-cache`.
- **`RecordingGlBinding.getUniformLocation` returns `-1` for undeclared names**, so a test that forgets to declare a name sees zero calls and can pass while asserting nothing.
- Seven vacuous checks were caught during F-2, every one a fixture or assertion sitting at a **symmetry point** of the thing being tested. Assume the next one exists.
- The two spike branches `spike/h-ios-gles` and `spike/h-android-gles` contain working code. **Adopt from them; do not rewrite from scratch, and do not merge them wholesale** — they carry `SPIKE ONLY` markers and deliberate policy violations.

## Execution order

Wave 1, four tasks with disjoint files, parallelisable: **1, 3, 5, 6**.
Wave 2, after Task 1: **2, 4**.
Wave 3: **7**.

---

## Task 1: Move the readback suites to `commonTest`

The riskiest task in the cycle: it touches four working targets to serve two new ones. It goes first and alone so everything after it builds on a proven-unchanged base.

**Files:**
- Move: `kmp/src/nativeTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt` → `kmp/src/commonTest/kotlin/com/rohittp/reng/`
- Move: `kmp/src/nativeTest/kotlin/com/rohittp/reng/ModelReadbackSuite.kt` → same
- Move: `kmp/src/nativeTest/kotlin/com/rohittp/reng/ModelFixtureBuilder.kt` → same
- Verify: `kmp/src/macosTest/.../MacosGlConformanceTest.kt`, `kmp/src/linuxTest/.../LinuxGlConformanceTest.kt` still compile unchanged

**Interfaces:**
- Produces: `runBasemapReadbackSuite`, `runModelReadbackSuite`, `measureLargeQuadRasterisation` and the fixture builder's exports, all reachable from `commonTest` and therefore from every target including Android's JVM source sets.

- [ ] **Step 1: Record the baseline before touching anything**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test :kmp:iosSimulatorArm64Test
```

Expected, and these exact numbers are the contract this task must preserve: **1,123 Android host / 1,159 `macosArm64` / 1,141 `iosSimulatorArm64`, 0 failures, 0 skipped**. Write them down. A move that changes any of them has changed behaviour.

- [ ] **Step 2: Move the three files, changing nothing else**

`git mv` all three. Do not reformat, do not rename, do not widen visibility beyond what compilation forces. If something does not compile, the smallest change that fixes it is the right one — and note in the report exactly what it was, because "the move required no changes" and "the move required these three" are very different facts about how coupled `nativeTest` was.

`ModelFixtureBuilder.kt` declares `private class ModelBinWriter`, renamed during F-2 because Kotlin/Native reported a redeclaration against an identically-named file-private helper in `RendererFactoryTest.kt`. Both are now in `commonTest`. **Expect that collision to return** and resolve it the same way — rename, do not merge them, because `commonTest` cannot see `nativeTest`'s reason for the duplication any more than it could before.

- [ ] **Step 3: Prove behaviour is preserved**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test :kmp:iosSimulatorArm64Test :kmp:checkKotlinAbi
```

**The three counts must be identical to Step 1.** Not "similar" — identical. `androidHostTest` will now *compile* the suites and must not run them; if its count moved, something became a `@Test` that was an `internal fun`, and that is a defect.

- [ ] **Step 4: Prove the suites still actually run**

Compiling is not running. Confirm from the test report that `MacosGlConformanceTest` still invokes both readback suites and that they still pass on a real Apple context — the suites' own `println` lines are in the report's `system-out`.

- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(kmp): move the readback suites to commonTest so every target reaches one copy"
```

---

## Task 2: The `iosTest` source set

**Files:**
- Create: `kmp/src/iosTest/kotlin/com/rohittp/reng/internal/gl/EaglOffscreenContext.kt`
- Create: `kmp/src/iosTest/kotlin/com/rohittp/reng/internal/gl/IosGlConformanceTest.kt`

**Interfaces:**
- Consumes: Task 1's relocated suites.
- Produces: a GLES 3 context fixture on iOS and a conformance test that runs on both `iosSimulatorArm64Test` and, filtered, on `iosArm64`.

- [ ] **Step 1: Adopt the spike's fixture**

`spike/h-ios-gles` has a working `EaglOffscreenContext.kt` and `IosGlSpikeTest.kt`. Read both. The context is `EAGLContext(kEAGLRenderingAPIOpenGLES3)` from **`platform.EAGL`** — not `platform.OpenGLES`, which is ES 1.x — made current with `EAGLContext.Companion.setCurrentContext`, rendering to a framebuffer object with no layer and no window. **No change to `kmp/build.gradle.kts` is needed**: the default hierarchy template picks `iosTest` up for both iOS targets.

Bring it across as production-quality test code, not as the spike left it. The spike widened three declarations `private`→`internal` in `BasemapReadbackSuite.kt`; after Task 1 that file is in `commonTest`, so check whether the widening is still needed and remove it if not.

- [ ] **Step 2: Write the conformance test**

Model it on `MacosGlConformanceTest.kt` and `LinuxGlConformanceTest.kt`. It must, at minimum: assert every roster entry point resolves; run `runGlConformanceSuite(binding, probe, ShaderDialect.GLES)`; run `runModelReadbackSuite`; and print the rasterisation probe's verdict.

**iOS is a GLES context, so the dialect is `GLES`, not `DESKTOP`.**

- [ ] **Step 3: Run it, and expect the probe to fail on the simulator**

```bash
./gradlew --no-configuration-cache :kmp:iosSimulatorArm64Test
```

The simulator is `Apple Software Renderer`, and its large-quad probe **fails** — 3,040 mismatched pixels against a 512 budget. That is the correct answer for a software rasteriser and is exactly what the probe exists to detect. The suite must **skip the affected case out loud** and run the rest, following `BasemapReadbackSuite`'s own precedent. A run that passes everything on the simulator means the probe is not wired in.

Report the count. It was 1,141 before this task.

- [ ] **Step 4: Commit**

```bash
git commit -m "test(kmp): hold a real GLES 3 context on iOS"
```

---

## Task 3: CI runs the iOS simulator

**Files:**
- Modify: `.github/workflows/ci.yml`, the `apple-publication` job only

- [ ] **Step 1: Add the task to the existing Apple step**

`apple-publication` runs on `macos-latest`, which is arm64 and already runs `:kmp:macosArm64Test`. Add `:kmp:iosSimulatorArm64Test` to that same Gradle invocation. Do not add a job, do not add a step, do not touch `android-linux`.

- [ ] **Step 2: Verify the workflow still parses**

```bash
ruby -e 'require "yaml"; YAML.safe_load(File.read(".github/workflows/ci.yml"), [], [], true); YAML.safe_load(File.read(".github/workflows/publish.yml"), [], [], true)'
```

- [ ] **Step 3: Check the failure-reporting step still covers it**

`ci.yml` has an `if: failure()` step running `tools/print_test_failures.py` over the test-results directory, because Gradle renders a Kotlin/Native failure as its exception class and location and never its message. **Confirm the iOS simulator's results directory is within what that step reads**, and widen it if not. A new test task whose failures print nothing is worse than no new test task.

- [ ] **Step 4: Commit**

```bash
git commit -m "ci: run the iOS simulator suite alongside the macOS one"
```

---

## Task 4: `androidDeviceTest`, and the policy gate that admits it

The build-file change and the policy change are one task because neither is valid without the other: the fingerprint must be computed from the final file.

**Files:**
- Modify: `kmp/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `kmp/src/androidDeviceTest/kotlin/com/rohittp/reng/internal/gl/PbufferEglContext.kt`
- Create: `kmp/src/androidDeviceTest/kotlin/com/rohittp/reng/internal/gl/AndroidGlConformanceTest.kt`
- Modify: `tools/check_repository_policy.py`
- Modify: `tools/tests/test_check_repository_policy.py`

- [ ] **Step 1: Adopt the spike's source set and fixture**

`spike/h-android-gles` has a working `PbufferEglContext.kt` and the build wiring. Read its document first — `docs/research/2026-08-27-h-android-gles-device-spike.md` — because it records what fought back.

**The trap, stated so it is not rediscovered:** `dependsOn(commonTest.get())` on `androidDeviceTest` **silently disables the default hierarchy template project-wide**, un-wiring `iosMain`/`nativeMain`/`linuxMain`/`macosMain` and failing every Kotlin/Native compilation — **while the Android build stays green**. `applyDefaultHierarchyTemplate()` restores it. Strip the spike's `SPIKE ONLY` comments and keep the fix.

**A surfaceless context will not do**: `EGL_KHR_surfaceless_context` is on neither AOSP's mandatory nor its recommended list, so Linux's fixture cannot be transliterated. Use a pbuffer.

- [ ] **Step 2: Widen the policy gate, explicitly**

Three refusals, all measured. `check_dependencies` asserts `len(dependency_calls) == len(main_calls) + len(test_calls)`, so no `dependencies { }` may exist outside `commonMain`/`commonTest`; the coordinate is not catalogued; and `kmp/build.gradle.kts` is token-stream fingerprinted.

Add a third scope with its own exact allowlist — `kotlin("test")`, `libs.kotlinx.coroutines.test`, `libs.androidx.test.runner` — mirroring how `_PERMITTED_NEW_DEPENDENCIES` handles ADR 0019's coroutines. **Do not exempt test source sets wholesale**: the project polices `commonTest` deliberately, and removing that guard means nothing catches the next dependency.

Then recompute the `kmp/build.gradle.kts` fingerprint for **both** accepted forms. **These are not whole-file SHA-256 digests**, whatever older prose and the checker's own comment say — `HANDOFF.md` gives the real derivation and a recompute command, and recomputing the wrong thing is the known trap here.

- [ ] **Step 3: Prove the gate still fails closed**

Add checker unit tests, and mutation-check them: with the widening in place, confirm that a **fourth** dependency in `androidDeviceTest` is still refused, and that a dependency in some *other* source set is still refused. A widening that admits anything is not the widening this task asks for.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

- [ ] **Step 4: Run on the attached device**

```bash
adb devices          # expect ce29ab63, a OnePlus CPH2649, Adreno 830, Android 16
./gradlew --no-configuration-cache :kmp:connectedAndroidDeviceTest
```

Expected from the spike: the whole `commonTest` body runs on the phone at **1,125 tests, 0 failures**, the conformance suite passes at `ShaderDialect.GLES` with the real cross-dialect link, and the large-quad probe reports **0 pixels** of disagreement. Report the actual numbers, and say plainly if any differ.

- [ ] **Step 5: Confirm every other target still builds**

This is the task most able to break things silently.

```bash
./gradlew --no-configuration-cache :kmp:checkKotlinAbi :kmp:testAndroidHostTest \
  :kmp:macosArm64Test :kmp:iosSimulatorArm64Test \
  :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64 \
  :kmp:compileKotlinIosArm64 :kmp:bundleAndroidMainAar
```

- [ ] **Step 6: Commit**

```bash
git commit -m "test(kmp): hold a real GLES context on Android, and widen the gate that admits it"
```

---

## Task 5: `tools/run_ios_device_tests.py`

**Files:**
- Create: `tools/run_ios_device_tests.py`
- Create: `tools/tests/test_run_ios_device_tests.py`

- [ ] **Step 1: Write the tool**

Standard-library Python only, matching the four existing tools. `print_test_failures.py` is the closest precedent — a diagnostic helper rather than a gate — and its shape is the one to follow.

It takes `--device`, `--profile`, `--identity` and `--filter`, and performs: link check, wrap the `.kexe` as an `.app`'s `CFBundleExecutable` with a generated `Info.plist`, copy the profile to `embedded.mobileprovision`, extract entitlements **from the profile itself** so the signature cannot claim more than the profile grants, `codesign`, `devicectl device install app`, then `devicectl device process launch --console` with the filter appended.

**The filter is not optional and the tool must not let it be.** An unfiltered run executes ~1,146 tests and iOS **SIGKILLs the app part-way through** — it has no UI, never becomes responsive, and the watchdog takes it. From outside that looks like a white screen that closes, which reads as a crash in RenG. Default the filter to the GL tests and say why in the `--help`.

- [ ] **Step 2: Test what can be tested without hardware**

The unit tests cannot install anything. They can test argument handling, the generated `Info.plist`, the entitlements extraction, the assembled command lines, and that a missing `.kexe` or missing profile fails with a useful message rather than a traceback. Say plainly in the report which behaviours are untested because they need a phone.

- [ ] **Step 3: Run it against the attached iPhone**

An iPhone 12 (A14, iOS 26.4.2, UDID `00008101-0006492C3E51001E`) is attached. `-allowProvisioningUpdates` previously matched an existing **wildcard** development profile, so no App ID needed registering. Expect: 5 GL tests, 0 failures, `GL_RENDERER=Apple A14 GPU`, `GL_SUBPIXEL_BITS=4`, `GL_MAX_TEXTURE_SIZE=16384`, and the probe reporting **0 pixels**.

**Uninstall the app afterwards.** It is not this repository's business to leave software on someone's phone.

- [ ] **Step 4: Commit**

```bash
git commit -m "tools: run the iOS device suite in one command"
```

---

## Task 6: Three ADRs

**Files:**
- Create: `docs/adr/0031-stand-on-deprecated-opengl-es-for-ios.md`
- Create: `docs/adr/0032-take-androidx-test-runner-for-device-tests.md`
- Create: `docs/adr/0033-gate-mobile-targets-asymmetrically.md`

House form: `NNNN-imperative-title.md`, a few paragraphs of prose, no template headings. Read `docs/adr/0019-*.md` first — it is the precedent for a dependency decision — and `docs/adr/0010-*.md`, which is why the six published targets are a permanent commitment.

- [ ] **Step 1: ADR 0031 — standing on deprecated OpenGL ES**

The load-bearing fact is that **cinterop does not translate Apple's `API_DEPRECATED` into Kotlin `@Deprecated`** — zero deprecation annotations across 3,099 lines of dumped `OpenGLES3` metadata — so **no build will ever warn**. If this is not in an ADR it is recorded nowhere.

Record: it works, measured on iOS 26.0, 26.2, 26.4.2 and 26.5, simulator and device. That ANGLE means something different from what it sounds like — the device reports `OpenGL ES 3.0 **Metal** - 104.1`, so Apple already implements GLES over Metal, and adopting ANGLE would swap Apple's translation for Google's rather than add one. That nobody has driven ANGLE from Kotlin/Native on any platform, and the nearest analogue costs a 57 MB klib. And the consequence: if Apple removes the API, two published targets break with no warning and the fallback is a real cycle of work.

- [ ] **Step 2: ADR 0032 — taking `androidx.test:runner`**

RenG's second third-party dependency, after ADR 0019's coroutines. What it buys — a device gate at all, since AGP requires a runner on the classpath and there is no standard-library path. That it ships in nothing. And that the policy gate was widened explicitly rather than exempted, so the next dependency still meets a closed door.

- [ ] **Step 3: ADR 0033 — the asymmetric gate**

Why iOS is gated in CI and Android is not: `macos-latest` already runs the Apple suite so the simulator is nearly free, while an Android emulator needs KVM and picks its ANGLE backend from AVD configuration rather than a documented default — measured, `hw.gpu.mode=auto` chose Vulkan-on-SwiftShader, not Metal.

What the probe's skips mean, and the honest consequence: **the automated gate certifies logic and linkage, not rasterisation**, because both automated rasterisers are software. Only the manual device runs certify pixels. State what a release may therefore claim, and that `CLAUDE.md` already holds "which of them anyone has actually executed belongs in release notes rather than being discovered by an Android consumer."

- [ ] **Step 4: Commit**

```bash
git commit -m "docs: record the three decisions Cycle H rests on"
```

---

## Task 7: The records catch up

**Files:**
- Modify: `CLAUDE.md`, `CONTEXT.md`, `docs/decomposition.md`

- [ ] **Step 1: `CLAUDE.md`**

The basemap-verification paragraph now describes something else: the targets that hold a GL context, the gate each runs under, and which are automated. Two corrections already landed during the preflight — the Skia claim and the "only two test tasks that can hold a GL context" claim — so read what is there before writing.

Add the device-run recipe as one line pointing at `tools/run_ios_device_tests.py`, and record the test counts actually measured in this checkout.

- [ ] **Step 2: `CONTEXT.md`**

`Render Context` is deliberately abstract over CGL, EGL and EAGL and its `_Avoid_` list is still correct — **do not change it**. Check whether anything else claims a platform limit this cycle falsified. If nothing does, say so in the report rather than inventing an edit.

- [ ] **Step 3: `docs/decomposition.md`**

Mark H complete, record what it did and did not do, and correct its H row — "manual for Android GL" is no longer the whole story now that Android's device run is one Gradle task and iOS's is one Python command.

- [ ] **Step 4: Run every locally compilable gate**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi :kmp:testAndroidHostTest :kmp:macosArm64Test :kmp:iosSimulatorArm64Test \
  :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64 \
  :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64 :kmp:bundleAndroidMainAar
```

`linuxX64Test` is Linux CI coverage, not a macOS-local gate — do not claim it ran.

**Confirm `kmp/api/kmp.klib.api` is byte-identical to where this cycle started.** It is the cycle's headline constraint.

- [ ] **Step 5: Commit**

```bash
git commit -m "docs: record what Cycle H shipped, and what it still owes"
```
