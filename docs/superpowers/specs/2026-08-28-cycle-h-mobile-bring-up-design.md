# Cycle H — Android and iOS bring-up

**Status:** drafted 2026-08-28 from a grilling session; awaiting owner approval.

This cycle turns a demonstration into a gate. RenG's Android and iOS GL bindings have shipped in every
release since `0.2.0` and, until this cycle's preflight, **had never executed a single line anywhere** — not
on a device, not on an emulator, not in CI. The preflight ran them on real hardware and they work. What
remains is making that permanent, so a regression is caught by a test run rather than by a consumer.

Every number below was measured, not read. The evidence is seven documents totalling 2,796 lines under
`docs/research/2026-08-2*-h-*.md` and `2026-08-24-cycle-h-preflight.md`, plus two working spike branches,
`spike/h-ios-gles` and `spike/h-android-gles`.

## What ships

An `iosTest` source set with an EAGL context fixture and an `androidDeviceTest` source set with an EGL
pbuffer fixture; both readback suites, their fixture builder and the rasterisation probe relocated from
`nativeTest` to `commonTest` so every target reaches one copy; `iosSimulatorArm64Test` wired into CI;
`tools/run_ios_device_tests.py` so the iOS device run is one command; an explicit widening of the repository
policy gate; and three ADRs.

## The gap this cycle actually closes

`docs/decomposition.md` states the deliverable as "Android's `GLES30` path and iOS's `platform.gles3` path
get run on real devices, and whatever differs from the macOS and Linux behaviour gets fixed or documented."

**The preflight did exactly that, and nothing differs.** All 91 roster entry points resolve on both
platforms. The GL conformance suite passes unmodified at `ShaderDialect.GLES` on an Apple A14 and on a
Qualcomm Adreno 830 — on Adreno with the real cross-dialect link and no escape hatch, which Linux needs. The
large off-screen quad probe reports **zero pixels** of disagreement on both, the same answer an M3 Max gives.
The one thing that looked like it differed — Skia on Android — turned out to be a host-JVM packaging fact,
now corrected in `CLAUDE.md`.

So the cycle as written is complete before it starts. The work that remains is a different thing wearing its
name: **permanence**. A binding proven once on a developer's desk is proven until the next commit.

## Public API additions

**None.** This is the first cycle since F-1 to grow the ABI by nothing at all. `kmp/api/kmp.klib.api` must
not move, and a diff there is a defect rather than a decision. Everything this cycle adds is a test source
set, a relocation within test source sets, a Python tool, CI configuration, or documentation.

## Decisions

### The gate is permanent, and that is the whole cycle

Recording the preflight and closing H was the alternative. It was rejected because a demonstration catches
nothing: the next regression on either mobile target would be found by whoever next happened to run a spike,
which in this project's history has been "never" — the bindings shipped unexercised through three releases.

### Simulator and emulator run automatically; the device runs manually; the probe decides what each may claim

The automatable rasterisers are the untrustworthy ones, and the trustworthy ones need hardware plugged in:

| | automatable | large-quad probe |
|---|---|---|
| iOS simulator | yes, Gradle drives it | **fails** — 3,040 px against a 512 budget |
| iOS device (A14) | no | **0 px** |
| Android emulator | yes, Gradle Managed Devices | **0 px** — ANGLE/SwiftShader; backend varies by AVD |
| Android device (Adreno 830) | no | **0 px** |

Rather than choose, both run. `measureLargeQuadRasterisation` already exists for precisely this: `0.3.0`
failed publication on a hosted runner's software rasteriser, and `BasemapReadbackSuite`'s answer was not to
loosen every budget but to let the probe distrust the driver, skip one case **out loud**, and still run the
other four. That precedent extends to two more rasterisers unchanged.

**Corrected 2026-08-28.** This table first recorded the Android emulator's probe as unmeasured. It was
measured, by the 2026-08-27 device spike: ANGLE-over-SwiftShader reports **0 px**, `GL_SUBPIXEL_BITS = 4`
and `GL_MAX_TEXTURE_SIZE = 8192`. So "both automated rasterisers are software" stays true, but it does not
follow that both fail the probe — **only the iOS simulator's does.** The distinction matters to anyone
reading the gate's promise: the automated pair is weak because it is software, not because it is measurably
wrong, and one half of it agrees with the analytic rectangle exactly.

A consequence worth stating rather than discovering: the iOS simulator reports `GL_SUBPIXEL_BITS = 10` and
`GL_MAX_TEXTURE_SIZE = 4096` where the device reports 4 and 16384 — matching macOS's Metal path, not its
software one. **The simulator is a logic gate and can never be a GPU proxy.** Three numbers this project's
own research first recorded as "iOS constraints" were simulator artefacts.

### The device gate runs the GL tests only

Not a preference — the platform forces it. An unfiltered device run executes all ~1,146 tests, and iOS
**SIGKILLs it part-way through**: the app has no UI, never becomes responsive, and the watchdog takes it.
From outside that looks like a white screen that closes, which is easy to mistake for a crash in RenG.
Scoped with `--ktest_filter` the GL tests exit 0 (measured at 310 ms on the attached A14; see the iOS
spike's device addendum).

The other ~1,100 tests are pure logic already covered on four targets. Running them on a phone buys nothing
and is exactly what trips the watchdog.

### The readback suites move to `commonTest`

`ModelReadbackSuite`, `BasemapReadbackSuite`, `ModelFixtureBuilder` and the probe live in `nativeTest`,
which an Android JVM test cannot see at any visibility — the `private`→`internal` widening that let the iOS
spike reuse them does not help. The Android spike's answer was to **copy** the probe, which is a second copy
of a rule and exactly what F-2's Task 14 existed to delete.

Moving them is mechanically trivial and that was verified before choosing it: their only non-RenG imports
are `kotlin.io.encoding.Base64` and `kotlinx.coroutines.runBlocking`, **both already used in `commonTest`**,
which already compiles for Android host tests on the JVM. No intermediate source set, no new dependency.

They are `internal fun run…Suite(binding, probe, dialect)`, not `@Test` classes — platform conformance tests
invoke them. So moving the functions creates no tests anywhere; it only makes them reachable. In particular
`androidHostTest` will compile them and must never run them, because that JVM has neither a GL context nor
Skia.

The half-measure — move only the probe — was rejected as incoherent: if Android runs only the conformance
suite, the probe decides nothing there, because conformance does not depend on rasterisation tolerance. The
probe earns its keep only where readback runs.

### The policy gate widens explicitly and keeps failing closed

An `androidDeviceTest` source set needs `kotlin("test")`, `libs.kotlinx.coroutines.test`, and
**`androidx.test:runner`** — RenG's second third-party dependency ever, after `kotlinx-coroutines` took ADR
0019. AGP requires a runner on the classpath for instrumented tests; there is no standard-library path.

`tools/check_repository_policy.py` refuses this three ways, all measured: `kmp/build.gradle.kts` is
token-stream fingerprinted with exactly two accepted forms; `check_dependencies` asserts
`len(dependency_calls) == len(main_calls) + len(test_calls)`, so no `dependencies { }` may exist outside
`commonMain` and `commonTest`; and the coordinate is not in the version catalog.

The gate is widened, not weakened: a third scope with its own exact allowlist, the coordinate catalogued,
the fingerprint recomputed for both accepted forms. Exempting test source sets wholesale was rejected — the
project deliberately polices `commonTest` today, and removing that guard means nothing catches the next
dependency. **Note that the fingerprints are not whole-file SHA-256 digests**, whatever older prose says;
`HANDOFF.md` gives the real derivation, and recomputing the wrong thing is the known trap here.

### CI gains the iOS simulator and nothing else

`macos-latest` is arm64 and already runs `macosArm64Test`, so `iosSimulatorArm64Test` is nearly free and
adds a second GLES dialect under real linkage. An Android emulator on `ubuntu-latest` needs KVM, costs
minutes, and picks its ANGLE backend from AVD configuration rather than a documented default — measured:
`hw.gpu.mode=auto` with no `-gpu` flag chose Vulkan-on-SwiftShader, not the Metal backend the host libraries
suggested. An unstable rasteriser makes an unstable probe reading.

This is deliberately asymmetric: iOS is gated in CI, Android on attached hardware. The asymmetry reflects
real cost rather than real importance, and the release notes must say which ran.

Hosted macOS runners have no GPU, so this puts the readback suites on `Apple Software Renderer` — the exact
driver that failed `0.3.0`. That is not an oversight: the probe is the mitigation, and it did not exist when
`0.3.0` failed.

### The iOS device run becomes one command

Android's device run is already one Gradle task, `:kmp:connectedAndroidDeviceTest`. iOS has none:
Kotlin/Native links a device binary and provides no runner, because iOS will not execute a bare Mach-O. The
working recipe is link, wrap the `.kexe` as an `.app`'s `CFBundleExecutable`, sign it against a profile
covering the device, `devicectl install`, then `devicectl launch --console` with a filter.

A gate that takes five manual steps is one nobody runs. `tools/run_ios_device_tests.py` — standard-library
Python, unit-tested, taking device, profile, identity and filter as arguments — matches the repository's only
tooling convention: all five existing tools are standard-library Python in `tools/`, and
`print_test_failures.py` is the precedent for a helper that is a diagnostic rather than a gate. The repository
contains no shell scripts at all.

A Gradle task was rejected because signing identity, profile and device differ per developer, so it needs
configuration that cannot be checked in — and it would move the fingerprint again.

### Three ADRs

- **Standing on deprecated OpenGL ES for iOS.** Apple deprecated it in iOS 12, in 2018. RenG has committed
  to six published targets permanently (ADR 0010), and two now rest on it. It works — measured on iOS 26.0,
  26.2, 26.4.2 and 26.5, simulator and device. The load-bearing fact is that **cinterop does not translate
  Apple's `API_DEPRECATED` into Kotlin `@Deprecated`**: zero deprecation annotations across 3,099 lines of
  dumped `OpenGLES3` metadata, so **no build will ever warn**. If this is not in an ADR it is recorded
  nowhere. The ADR must also record what ANGLE actually means here, because the device answers it: iOS
  reports `OpenGL ES 3.0 **Metal** - 104.1`, so Apple already implements GLES over Metal and adopting ANGLE
  would swap Apple's translation for Google's — not add one where none exists. Nobody has driven ANGLE from
  Kotlin/Native on any platform, and the nearest analogue costs a 57 MB klib.
- **Taking `androidx.test:runner` as a dependency.** ADR 0019 recorded the first third-party dependency;
  symmetry records the second. What it buys, why no standard-library path exists, and that it ships in
  nothing.
- **The gate's asymmetry and what a release may claim.** Why iOS is gated in CI and Android is not, what the
  probe's skips mean, and what a release may assert about a target verified only in simulation. `CLAUDE.md`
  already holds that "which of them anyone has actually executed belongs in release notes rather than being
  discovered by an Android consumer"; this cycle is where that promise comes due.

## Two risks the plan must carry

**The hierarchy-template trap.** The `dependsOn(commonTest)` edge that makes the moved suites reachable from
Android **silently disables the default hierarchy template project-wide**, un-wiring `iosMain`, `nativeMain`,
`linuxMain` and `macosMain` and failing every Kotlin/Native compilation with "Expected … has no actual
declaration" — **while the Android build stays green**. `applyDefaultHierarchyTemplate()` restores it. CI
compiles the native targets so CI would catch it; a developer running only Android tests would not.

**The move touches four working targets to serve two new ones.** macOS and Linux pass those suites today.
Relocating them must be provably behaviour-preserving for both, or this cycle breaks what works in order to
extend it. The test counts before the move are the check: 1,123 Android host, 1,159 `macosArm64`.

## Out of scope, decided rather than omitted

- **Rentile `0.6.0`.** It is a label-fidelity release — E-labels' business — and its AAR ships byte-identical
  Skia binaries, so nothing measured here changes. Recorded so it is not discovered mid-cycle, the way
  `0.5.0` was.
- **An Android emulator in CI**, for the reasons above.
- **`linuxArm64` and `macosX64`.** Neither has a test task and neither is in this cycle's remit.
- **Golden images.** Pixel verification remains Cycle J's.

## Carried in, and owed

The findings of record are `2026-08-24-cycle-h-preflight.md`, `…-h-ios-gles-context-spike.md` (with its
device addendum), `…-h-official-platform-docs.md`, `…-h-field-reports.md`, `…-h-android-measurements.md`,
`2026-08-27-h-android-gles-device-spike.md`, and `…-h-grilling-brief.md`, which resolves the two places the
strands disagreed.

Known and not addressed here: no second GPU vendor beyond Adreno and Apple has been measured; `iosArm64Test`
has no Gradle runner and this cycle works around that rather than fixing it; and the three debts F-2 left
open — a parsed-model residency, `maximumDecodedModelBytes`, and all-zero skin weights — remain owed and are
untouched by this cycle.
