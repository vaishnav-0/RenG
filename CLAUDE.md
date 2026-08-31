# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

**RenG draws a basemap, and four releases are public.** The newest *published* version is **`0.4.0`**,
released on 2026-08-30 from `070a85d` — aggregate POM, all seven target publications and the completion
record all verify anonymously, and `maven-metadata.xml` lists `0.4.0` as both `<latest>` and `<release>`.
**`VERSION_NAME` in `gradle.properties` still reads `0.4.0`, which is now a released coordinate**, so it
must move up before anything is pushed to `main` — see the freeze section below, which this release ended.
Anything in this file
or in an older document that says RenG "renders nothing", "exposes no public runtime API", that the KLIB
ABI dump "contains no renderer factory", or that the basemap is an unmerged branch is obsolete.

**Cycle A — released as `0.1.0`, historical.** Exact source commit
`af92901b2ef045078b855a6b47533bc95aca6886` passed both CI jobs and the publication workflow, and its
completion record verified anonymously. It established the single publishable `:kmp` module (Android
Studio's placeholder `:app` is deleted), `consumer-smoke`, the dependency-free static site, and the
publication and repository-policy tooling.

**Cycles B, C, D and F-1 — released together as `0.2.0`.** Exact source commit
`a2cbe6a965247f221f7e279a962b40306baac21b`: CI run `32314778617` and publication run `32314778594` both
succeeded, and `com/rohittp/reng/kmp/0.2.0/reng-release-completion-v1.json` verifies anonymously, naming
that commit and manifest digest `e93bdfde7870056552961791d2d29fea43b6030e6b5e4493ff425049d0dcb1d4`. The
first attempt, at `3a6eec5`, failed closed before any R2 write — a scheduler-benchmark wall-clock ceiling
calibrated on developer hardware, replaced by a machine-independent scaling-ratio assertion in `c67349a` —
so nothing was published and the retry reused the same version, exactly as ADR 0013 intends.

**The basemap cycle — released as `0.3.0`, the current release.** Exact source commit
`49cc1d5843ba40ac866f96d00123ca07b662f6fc`:
`com/rohittp/reng/kmp/0.3.0/reng-release-completion-v1.json` verifies anonymously at
`https://maven.rohittp.com`, naming that commit and manifest digest
`49101f2d5ac66f96d60d6ea580b173bf051da8fa58b680d74fe50476cf4cdbf1`; aggregate metadata lists `0.1.0`,
`0.2.0` and `0.3.0` with `0.3.0` as both `<latest>` and `<release>`; and all seven publications resolve
over public HTTP with no credentials. The version was declared explicitly in `6e7c023` rather than left to
the resolver, which would have advanced to `0.2.1` and understated the release. **Unlike the first two
releases, its CI and publication run IDs are recorded nowhere in this repository — do not invent them.**

**Cycles F-2 and H are both complete, both merged into local `main`, and both unreleased.** Their branches
`feat/f2-models` and `feat/h-mobile-bring-up` survive only as history. `origin/main` is still at the
released `49cc1d5`, so **nothing of either cycle is published to R2** — and neither is the X2 residency fix
nor the Rentile `0.6.0` pin that followed them. Do not quote a commit or an ahead-of-origin count from this
file; read them from git, because both move every session.

**`VERSION_NAME` is `0.4.0`, declared deliberately rather than left to the resolver.** F-2
grows the public ABI by three enum entries, so a patch bump would understate it — and the resolver would
have chosen `0.3.1`, since `0.3.0` has a valid completion record. Declaring it explicitly is also what
keeps a *local* publish honest: `consumer-smoke` resolves `../build/local-maven` by default, so republishing
under `0.3.0` would have put F-2's bytes behind the released `0.3.0`'s coordinate on this machine — the same
same-version-different-bytes hazard ADR 0013 exists to prevent, one layer down.

**`publish.yml` ignores only `docs/**`, `**/*.md` and `LICENSE`, so pushing either branch as it stands cuts
a `0.4.0` release.** That is the intended number when the time comes; it is not intended yet. Cycle H adds
nothing to the public ABI, so it does not move that number.

**The freeze is over: E-labels, G and E-terrain shipped together as `0.4.0` on 2026-08-30**, which is
exactly what the freeze was for — one release at the end, not one per cycle.

**And that inverts the paragraph this one replaces, which is the thing to read carefully.** While `0.4.0`
was unpublished, republishing it into `build/local-maven` as cycles landed was harmless, because the
same-version-different-bytes hazard ADR 0013 exists to prevent had no public record to contradict. **There
is a public record now.** `VERSION_NAME` still reads `0.4.0`, so every
`publishAllPublicationsToLocalTestRepository` puts *post-release* bytes behind the *released* `0.4.0`
coordinate on this machine, and `consumer-smoke` — which defaults to `../build/local-maven` — resolves
them. Cycle K did exactly this for a whole session, with 97 lines of new ABI. Nothing public was harmed and
nothing can be; the local repository is a scratch directory and deleting it is always safe. But the
number must move up before the next push, and until it does, a local `0.4.0` is not the published `0.4.0`.

The push hazard is unchanged and still worth stating plainly: `publish.yml` cuts a release on any
non-documentation push to `main`, so an accidental push publishes whatever `VERSION_NAME` says,
**permanently** — ADR 0013 makes a coordinate immutable, and recovery is only ever an explicit upward
version change, never an overwrite. A push carrying a `VERSION_NAME` that is already published fails
closed instead, which is the resolver working, not a bug to route around. Nothing reaches `origin/main`
without the owner saying so.

**The visual harness runs against a local publish, with no repository edit and nothing to revert.**
`consumer-smoke/settings.gradle.kts` already defaults `rengRepositoryUrl` to `../build/local-maven` under an
`exclusiveContent` filter, so:

```bash
./gradlew --no-configuration-cache :kmp:publishAllPublicationsToLocalTestRepository
./gradlew -p consumer-smoke runHarness -Pconfig=corpus/configs/style-59.json \
                                       -Pplans=corpus/plans/storyboard.json
```

is the whole loop — verified end to end here, including a fresh Gradle home with `--refresh-dependencies`.
Add `-PrengRepositoryUrl=https://maven.rohittp.com` to run the same harness against the *published*
coordinate instead.

**Cycle K replaced `-PstyleUrl` with a style *id*.** A config names a style by id; the base url lives in
the untracked `local.properties` as `reng.harness.styleBaseUrl`, and a run composes `<base>/<id>`. So the
api key is set once per machine instead of being pasted into every command, and a corpus file can be
checked in naming "style 59" without carrying a key. **A missing base fails before anything renders and
names the property**, because composing against an empty base produces a transport error that reads as a
network problem. `-PmodelUrl` is gone with the rest of the flag pile: a model is a field in a plan file.

**`0.3.0` failed closed once before it published, and the cause was the runner's driver rather than
RenG.** The first attempt failed on the hosted macOS runner with `kotlin.AssertionError at null:-1` as its
entire diagnostic, and passed on every developer machine. A hosted GitHub macOS runner has no GPU: it runs
`Apple Software Renderer`, whose rasteriser drops quads reaching far outside the viewport — the shape every
ground tile has — losing the south-east tile outright and cutting the south-west one on a straight
diagonal, 3,005 of 15,876 interior pixels untouched. The suite now measures the driver instead of naming
it: `measureLargeQuadRasterisation`
(`kmp/src/commonTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt:669` — Cycle H moved the file
out of `nativeTest`; the line number did not change) draws the fixture's own four
ground-tile footprints at the fixture's real clip `w` and counts pixels disagreeing with the analytic
rectangle — 0 on Apple M3 Max, 2,112 on `Apple Software Renderer`, against a 512-pixel boundary budget.
When the probe distrusts the driver exactly one case skips out loud and the other four still run; Ubuntu's
llvmpipe job keeps that case's coverage. A probe that cannot run fails rather than skips. Nothing reached
R2, so the same version was retried, again exactly as ADR 0013 intends.

What `0.2.0` contains, by cycle. **B**: the public value types, protocols and sanitized failures, canonical
identities, spatial and diff planning, and the pure lifecycle, resource and preparation reducers, which
still call no adapter and touch no GL. **C**: the inflate and CRC-32 seam; PNG container parsing and
unfiltering to canonical RGBA8, hardened by five adversarial review passes into a 300,000-input property
fuzz test asserting `decodePng` never throws; strict UTF-8 and a hand-written JSON reader (no
serialization library is compile-visible outside native targets); GLB container scanning, glTF document
parsing and the `PARSE_GLB`/`VALIDATE_GLB_FEATURES` gates; the resident cache; the resource driver; and
cancellation propagated unwrapped. **D**: the internal GL seam and its four platform implementations,
runtime shading-language dialect detection, the offscreen colour-and-depth surface and composite pass, the
corrected restore set (ADR 0023), shader compilation with version-directive substitution and program
caching, the lifecycle driver, and the conformance suite against real llvmpipe and real Apple silicon.
**F-1**: `createRenderer` — the first API that makes RenG operable — stickers drawn in both draw regimes,
and geometries painted by consumer shader pairs with consumer-supplied uniforms and textures. Six Cycle C
tasks were reordered onto the basemap cycle so this could ship; the MVP release is internal, and a later
breaking interface change is accepted (never an overwritten coordinate).

**What `0.3.0` contains.** Its authority is `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md`
and `docs/superpowers/plans/2026-08-20-cycle-e-basemap.md`; the per-task ledger, including every review
verdict and the four spikes taken mid-cycle, is
`.superpowers/sdd/2026-08-20-cycle-e-basemap/progress.md` — which stops before the cycle's last three
fixes, whose record is their commit messages. It ships the Rentile firewall and its production key
resolver (`internal/firewall/`: `FirewallTransport`, `FirewallStore`, `OperationRegistry`,
`RentileKeyDerivation`, `EngineFailureClassification`, `BasemapEngineHost`), sprite-pair and style commits,
route derivation from a real style, style compilation against the bytes the frame actually commits, tile
rendering through the engine, sources that declare their tiles by reference through TileJSON, bounded GPU
texture residency, and the drawn ground (`internal/gl/GroundPipeline.kt`) gated by analytical readback
rather than by any stored image. Its test counts were **931 Android host / 966 `macosArm64`** at the time
it shipped; the figures for this checkout are F-2's, below.

**Exactly one public field is added by the basemap cycle**: `ResourceLimits.maximumResidentGpuTextureBytes`,
plus its mechanical constructor/`copy`/`component11` fallout. It was inert for most of the cycle —
`RendererFactory` constructed `GlObjectRegistry()` with the class default, so the knob had no effect at
all — until the ground-draw task threaded it from the caller's configuration at `RendererFactory.kt:115`.
A public field with no wiring is the failure mode worth remembering here.

**RenG's GL code reaches a real context five ways now; three are automated and two need hardware plugged
in.** Cycle H moved `BasemapReadbackSuite`, `ModelReadbackSuite`, `ModelFixtureBuilder` and the
rasterisation probe from `nativeTest` to `commonTest`, so one copy reaches every target, then added an
`iosTest` source set (EAGL) and an `androidDeviceTest` source set (EGL14 pbuffer). Basemap and model
readback are no longer macOS-and-Linux only.

| how RenG's GL runs | context | driver it has actually run on | gate |
|---|---|---|---|
| `:kmp:macosArm64Test` | CGL core profile | Apple M3 Max, and `Apple Software Renderer` through `MacosGlRenderer.SOFTWARE` | automated — `ci.yml` `apple-publication` |
| `:kmp:linuxX64Test` | surfaceless EGL | llvmpipe | automated — `ci.yml` `android-linux` |
| `:kmp:iosSimulatorArm64Test` | EAGL | `Apple Software Renderer` (simulator) | automated — `ci.yml` `apple-publication`, added by Cycle H |
| `:kmp:connectedAndroidDeviceTest` | EGL14 pbuffer | a `Pixel_10_Pro_XL` emulator on ANGLE over Vulkan over SwiftShader | manual — `./gradlew --no-configuration-cache :kmp:connectedAndroidDeviceTest`, against whatever `adb` offers |
| the `iosArm64` device run | EAGL | never, as the permanent suite — see below | manual — `python3 tools/run_ios_device_tests.py --device <udid> --profile <mobileprovision> --identity <sha1>`; `iosArm64` has no Gradle test task at all |

**Neither permanent device test has met a real GPU, and nothing here should be read as saying it has.** Both
phones were detached from this machine when their tasks ran. `androidDeviceTest`'s only execution is the
emulator run above, at 1,124 tests, 0 failures, 0 skips. The Adreno 830 and Apple A14 numbers — the
rasterisation probe reporting 0 disagreeing pixels on each — live in the preflight spikes
(`docs/research/2026-08-27-h-android-gles-device-spike.md`, where the spike's own build also ran the
whole `commonTest` suite on the Adreno phone at 1,125 tests and 0 failures, and
`docs/research/2026-08-24-h-ios-gles-context-spike.md`) — not in any gate, and not on this build. And
`tools/run_ios_device_tests.py` **has never been run against the class it defaults to**: it was built and
exercised on hardware against the spike's `IosGlSpikeTest`, and `IosGlConformanceTest`, which
`tools/run_ios_device_tests.py:50` names, arrived afterwards. The filter matches that class structurally —
verified — but the loop is unrun, and a filter matching nothing is the one case the tool turns into a loud
failure rather than a green run.

**The one case that would catch a ground regression skips on the only rasteriser CI will ever run.** On the
iOS simulator the probe measures 3,040 pixels of disagreement against a 512-pixel budget, so
`runBasemapReadbackSuite` skips its ground-coverage case out loud and runs the other four. That skip is
load-bearing rather than cosmetic: widening the budget until the probe trusts that driver makes **2 of 5
cases fail**, the ground one over 3,005 of 15,876 interior pixels — `0.3.0`'s exact signature, measured
again on a different rasteriser. Ubuntu's llvmpipe job is what keeps that case gated at all.

**Android's host tests remain doubly excluded, and the Skia half of that is about the host JVM rather than
about Android.** `testAndroidHostTest` compiles the moved suites and must never run them: that JVM has no
GL context, and it looks for `libskiko-macos-arm64.dylib` — a **host** library that was never going to be
an Android one — while Rentile's own AAR ships `jni/arm64-v8a/libskiko-android-arm64.so` at 30 MB, which
only an Android runtime unpacks. Measured on a real OnePlus (Adreno 830, Android 16), an `androidDeviceTest`
runs Rentile end to end, rendering a 3,605-byte PNG tile through the firewall.

All six targets still publish at every release; which of them anyone has actually executed belongs in
release notes rather than being discovered by an Android consumer, and **ADR 0033 is where that promise now
lives**.

**Measured on this checkout:** `testAndroidHostTest` **1,617**, `macosArm64Test` **1,690**,
`iosSimulatorArm64Test` **1,674** — 0 failures, 0 errors and 0 skips on each, summed from Gradle's own
JUnit XML rather than from scrollback. 138 Python tests pass, and `check_repository_policy.py` passes.
(Cycle H closed at 1,123 / 1,159 / 1,145; X2 took it to 1,134 / 1,174 / 1,160; E-labels took it to
1,298 / 1,351 / 1,335; Cycle G took it to 1,457 / 1,516 / 1,500; E-terrain added the rest.)

**Read a gate's verdict from `BUILD SUCCESSFUL` or an unpiped `$?`, never from an exit code through a
pipe.** `./gradlew … | tail` reports *tail's* status, so a failing build looks green. That cost this cycle
two merges reported passing that were not compiling, and the tell was mistaken twice for stale results: the
test counts did not move, and then no JUnit XML appeared at all, because the tests never ran. **An absent
measurement is evidence, not noise.**

**GPU residency is observable, and its two defaults no longer disagree — the X2 fix, landed between
Cycle H and E-labels.** Three things were wrong at once and none of them was about either cycle.
`RendererConfiguration.maximumBasemapTileInstances` defaulted to 512 while
`ResourceLimits.maximumResidentGpuTextureBytes` defaulted to 128 MiB, and a 512×512 RGBA8 tile is exactly
1,048,576 bytes — so residency was exactly 128 tiles against a ceiling declaring 512 legal. A **3840×2160
display at pitch 0** reaches **167 tiles**, measured, so the band was reachable with the camera level, and
LOD hysteresis is what put it there: the same viewport peaks at 93 tiles with no LOD history and 167 with
one frame of it, so the budget sat between RenG's own two worst cases. Nothing could observe it —
`evictOverBudget` emitted nothing, and `ResidentCache.toReportEntry` hardcoded
`knownGpuBytes = 0L, hasUnknownGpuBytes = false`, so `queryResources` **affirmatively reported zero GPU
bytes** while textures were resident. `ResourceUsage`'s own `init` had required the honest form all along
(`knownGpuBytes != null || hasUnknownGpuBytes`); the implementation asserted positive knowledge of zero,
which passes that check and is false.

Now: the default budget is **512 MiB**, sized to the default tile ceiling by owner decision — the fields
stay deliberately independent and that KDoc sentence stands, only the *default* was reconciled, and a
budget is an eviction threshold rather than a reservation, so it allocates nothing until frames need it.
`gpuByteAccount` answers a three-way rule (measured / unmeasurable / no GL objects), and
**`DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET`** — the one ABI line this fix adds, taking that enum
from three constants to four — warns once per draw when the leased working set cannot fit.

**Two limits of that fix, both deliberate.** `queryResources` still cannot see the 167 MiB: the report
iterates `ResidentCache` entries and a *rendered* basemap tile is never installed there, so the
measured-bytes row is unreachable in a production report today. What changed for consumers is that sticker,
geometry-texture and model-texture entries now say *unknown* instead of *zero* — the falsehood is gone,
the coverage gap is not, and closing it means reporting over the union of cache and registry keys. And the
diagnostic fires on the *leased* working set, so a frame exactly one tile over evicts that tile and stays
silent; a quiet log is not proof of no eviction.

**Cycle E-labels is complete and unreleased.** RenG draws map text: point and line placement with halo,
collision and priority, icons that claim space and draw, and fade across frames. Its authority is
`docs/superpowers/specs/2026-08-28-cycle-e-labels-design.md`, its plan is
`docs/superpowers/plans/2026-08-28-cycle-e-labels.md`, and eight preflight documents plus four measurement
spikes sit in `docs/research/2026-08-2[89]-e-labels-*.md`. **Rentile moved to `0.6.0` for it** — read the
catalog, never this paragraph. **ADRs 0034–0036** govern the label stack's position, fade as the only
cross-frame label state, and the engine-diagnostic boundary; ADR 0016 gained a second erratum and ADR 0018
gained one for the eighth Frame Plan field tag.

**The public ABI grew by four enum entries and one `FramePlan` field**, all under a `VERSION_NAME` frozen at
`0.4.0`: `ResourceClass.BASEMAP_GLYPH_RANGE`, `DiagnosticCode.LABEL_CONTENT_EXCLUDED`,
`PipelineStage.LABEL_PREPARATION`, `RenGErrorCode.UNROUTABLE_LABEL_SOURCE`, and `FramePlan.drawLabels`.
`ResourceLimits` did **not** grow — the chain that concluded otherwise was broken by measurement.

**What the cycle owes, all measured rather than suspected.** `icon-text-fit` is out of scope by decision, so
110 layers across 13 styles draw an unfitted icon — wrong size, not absent. `icon-pitch-alignment` and
`text-pitch-alignment` cannot be honoured by a screen-space quad and are recorded rather than faked.
Complex scripts still produce no glyphs — `ScriptSupport.kt` is byte-identical across the Rentile bump — and
RenG's only answer is one aggregate diagnostic. The handover cache holds **one** entry, so an A→B→A camera
oscillation misses every time. And `maximumDecodedImageBytes` was raised to 256 MiB to clear the engine's
largest atlas but is still **shared** with every raster, which is F-2's debt unchanged.

**The most expensive lesson of the cycle is about tests, not labels.** Twelve vacuous checks were found
inside E-labels' own new tests, every one by deliberately breaking working code rather than by review — a
`distinct()` assertion that passed against a broken `equals`, a probe that measured its own acceleration
structure, a premultiplication check unfalsifiable by construction, and a collision case where the winner
merely painted over the loser. **Two integration holes were also found this way**: the label path was fully
built and wired to nothing, and icons claimed collision space and drew no ink — both invisible to every unit
suite and both surfaced by an agent noticing its own work could not be observed.

**A visual harness exists, it found five defects the passing suite did not, and it is the reason `0.3.0`
draws.** It lives in `consumer-smoke/src/macosArm64Main/kotlin/com/rohittp/reng/smoke/harness/`, is invoked
as `./gradlew -p consumer-smoke runHarness -PstyleUrl=<url>`, and writes 960×540 binary PPM frames for
`ffmpeg` rather than PNG, because RenG owns a decoder and no encoder. Across all 34 styles the consumer
ships it rendered **1630 of 1632 frames**, the two losses being transport timeouts reported precisely
rather than swallowed. See "The visual harness" below.

**Out of scope in the basemap cycle, and not implemented:** terrain (Cycle C's task 20 travels to
E-terrain), map labels, models with textures and animation, the globe projection, and golden images. Map
labels were split into their own cycle, **E-labels**, on 2026-08-22, and **Android and iOS bring-up (H) now
precedes the globe (G)**. **Pixel verification is deferred to Cycle J by owner decision**
(`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`), so E-basemap and
F-2 gate on analytical readback — relationships asserted over a real context, no stored baselines.

**No gate/class combination throws.** `ResourceClassGate` has exactly three constants — `DECODE_PNG`,
`PARSE_GLB`, `VALIDATE_GLB_FEATURES`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt:614-618`) — and
`ordinaryResourceClassGates` returns `null` for all eight classes RenG's driver does not gate: the seven
the Rentile engine acquires and validates itself through the firewall, plus `BASEMAP_STYLE`, which commits
through its own verbs (same file, `:640-657`). `RenGClassGateRunner`'s `when` is total over three gates
with no `error(...)` branch. The DEM check survives as `validatesDemTerrainEncoding` in
`internal/driver/ClassGateRunner.kt`, called from the firewall's write path where ADR 0016 puts the
obligation. RenG's own `ResourceClass` has eleven constants; ADR 0016 carries its own appended erratum for
Rentile's ninth engine class, `GLYPH_RANGE`, which RenG deliberately does not route.

**Rentile is pinned in `gradle/libs.versions.toml`, at `0.6.0` since the E-labels preflight bump.** Do not
quote a Rentile version from prose, including from this file; read the catalog. RenG adopts the pin and
none of `0.6.0`'s label feature: it still never calls `acquireLabelCandidates` or `planLabelCandidates`,
never enumerates `GLYPH_RANGE`, and still passes `TileSubstitutionPolicy.Disabled`, so Rentile's
`CACHE_SUBSTITUTE_THEN_NETWORK` is unreachable. `kmp/api/kmp.klib.api` did not move — `0.6.0`'s 29 removed
ABI lines are all in `LabelCandidate`, `LabelIconRef` and `LabelLayerStyle`, which RenG has never imported,
and its transitive set is byte-for-byte the one `0.5.0` declared. A version lives in **five coupled places**
that must move in one commit or `check_repository_policy.py` fails closed: the catalog; *both*
`_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` entries for the catalog (**three** accepted forms in the
catalog's entry, all of which move); `base_versions["rentile"]`; and two fixtures in
`tools/tests/test_check_repository_policy.py`. Those fingerprints are **not** whole-file SHA-256 digests,
whatever older prose and the checker's own comment say — `HANDOFF.md` gives the real derivation and a
recompute command, because recomputing the wrong thing is the trap here. **A sixth place is not gated at
all**: roughly twenty lines of KDoc and comment across ten Kotlin files name the pinned Rentile version and
its release commit, and the fingerprint mechanism is deliberately comment-insensitive, so a bump passes
green with every one of them left false. Grep `kmp/src` for the old version before calling a bump done.
`0.6.0`'s bump was measured rather than argued —
`docs/research/2026-08-28-e-labels-060-bump-spike.md` records the gates, and closes the provenance question
for `0.5.0` and `0.6.0` both by diffing Rentile's **published sources jars** against its release commits,
which is the cheapest instrument for that and should be reused.

**What Cycle F-2 contains.** Its authority is `docs/superpowers/specs/2026-08-23-cycle-f2-models-design.md`,
its plan is `docs/superpowers/plans/2026-08-23-cycle-f2-models.md`, and the per-task ledger — every ruling,
every mutation observation, every measured number — is
`.superpowers/sdd/2026-08-23-cycle-f2-models/progress.md`. **Until this cycle no code in RenG had ever read
a byte of a GLB's BIN chunk**; `parseGltf` took `binChunkLength` and never the bytes. F-2 is the numeric
half: `internal/model/` (accessor decoding, node transforms, animation resolution and sampling, model
assembly), `internal/gl/ModelPipeline.kt` and `SceneLight.kt`, and the renderer arm that acquires,
decodes, uploads and draws. Test counts for this checkout are recorded once, with the GL-context table
above, so the two cannot drift apart.

It reaches **40 of the consumer's 41 models**, measured against
`docs/research/2026-08-22-consumer-model-corpus-check.md` rather than against glTF's breadth. Morph targets
and JPEG stay rejected by decision, not oversight — zero of 41 carry a morph target and exactly one carries
a JPEG. ADR 0021 and ADR 0028 each carry a 2026-08-2x erratum recording what widened; **read those errata,
because both ADR bodies still describe the narrower subset.**

**Three things F-2 leaves owed, all measured rather than suspected.** A model pays all three GLB parses on
**every frame** — a `RESIDENT`-provenance route still runs the class gates — about 3 ms per model per frame
on an M3 Max, roughly 18% of a 60 Hz budget for one model; that is the argument for a parsed-model
residency. `decodedCpuBytes` shares `maximumDecodedImageBytes` with rasters instead of taking its own
public limit. And all-zero skin weights collapse a vertex to the origin, which is the specification's
formula applied literally rather than a repair.

**What Cycle H contains.** Its authority is
`docs/superpowers/specs/2026-08-28-cycle-h-mobile-bring-up-design.md`, its plan is
`docs/superpowers/plans/2026-08-28-cycle-h-mobile-bring-up.md`, and the per-task ledger is
`.superpowers/sdd/2026-08-28-cycle-h-mobile-bring-up/progress.md` — which stops after waves 1 and 2, the
rest of the record being the task reports beside it (Task 3 has a brief but no report — its agent died
after making the correct one-line edit, and the controller finished it). **RenG's Android and iOS GL
bindings had shipped in every release since `0.2.0` and, until this cycle's preflight, had never executed
a single line anywhere** — not on a device, not on an emulator, not in CI. The preflight ran them and
nothing differed from macOS and Linux, so the cycle's subject is not the bindings but *permanence*: an
`iosTest` source set (EAGL) and an `androidDeviceTest` source set (EGL14 pbuffer), the readback suites and
probe relocated to `commonTest`, `:kmp:iosSimulatorArm64Test` appended to `ci.yml`'s existing
`apple-publication` invocation, `tools/run_ios_device_tests.py` with 45 unit tests, `androidx.test:runner`
catalogued, and ADRs 0031–0033.

**It grows the public ABI by nothing at all** — the first cycle since F-1 to do so. `kmp/api/kmp.klib.api`
is byte-identical to `main` at `sha256 fecb289f29ecfbb419287f5b6aa918085574afb5b6958dd7cc2b3ccb2065e5f6`,
and a diff there would have been a defect rather than a decision. **The cost is paid in the policy gate
instead**: `check_dependencies` grows a *third* dependency scope with its own closed allowlist rather than
exempting test source sets wholesale, and `kmp/build.gradle.kts` and `gradle/libs.versions.toml` each gain
a **third** accepted fingerprint form. Cataloguing the coordinate produced four refusals, not the three the
spec and ADR 0032 predicted, because the catalog is fingerprinted too. Every historical form stays accepted
forever, because an ADR-era test fixture depends on each one.

**What Cycle H did not do.** No Android emulator joined CI — an AVD picks its ANGLE backend from
configuration rather than from a documented default, and an unstable rasteriser makes an unstable probe
reading. `iosArm64` still has no Gradle test runner; the Python tool works around that rather than fixing
it. No GPU vendor beyond Adreno and Apple has been measured, on any target. And F-2's three debts — a
parsed-model residency, `maximumDecodedModelBytes`, and all-zero skin weights — are untouched.

**Seven vacuous checks were caught during F-2, every one a fixture or an assertion sitting at a symmetry
point of the thing being tested** — a cap whose fixture was derived from the mutated constant; a slerp check
at `t = 0.5`, where slerp and nlerp agree exactly; a normal matrix on `diag(2, 4, 1)`, its own transpose; a
depth-mask check read at a sticker draw, where the sticker pass sets the mask itself; a screen-order check
at unequal z, which passes under either authority; a premultiplication check on a colour-type-2 PNG, where
premultiplying is the identity; and an animation check at `t = 1.0`, which `timeSeconds % duration` maps
back onto `t = 0`. **Assume the next one exists.**

**E-terrain sharpened that rule into something you can act on: look for the symmetry point first.** The
cycle found more than fifteen vacuous checks, every one by deliberately breaking working code rather than
by review — and **three were the same shape, a fixture sitting where the thing under test is the
identity**. A *uniform* DEM makes a ground cell's four corners and the texel beneath a point the same
number, so a granularity bug survived a whole mutation sweep. A drape quad laid *on* a cell boundary makes
the interpolation vanish, and read 589,761 of 589,761 against a build 47 metres wrong. A metre-scale of
exactly *1.0* makes a missing multiply invisible, and made two different mutations report the same catching
test. The others rhyme: a globe probe **saturated** in a frame smaller than the sphere reported 0 differing
pixels against a *correct* build; an assertion satisfiable by **two frames of background**; and a test
asserting **its own helper's default** rather than production's, which is only visible when you flip the
production default and nothing goes red.

So before trusting a green fixture, ask what it is *not* varying — and move it off the symmetry point
rather than adding another assertion beside it.

**Cycle E-terrain is complete and unreleased. The ground is no longer flat.** Its authority is
`docs/superpowers/specs/2026-08-29-cycle-e-terrain-design.md`, its plan is
`docs/superpowers/plans/2026-08-29-cycle-e-terrain.md`, and three research documents carry what it
measured: the preflight (`2026-08-29-e-terrain-preflight.md`), the coplanar depth spike
(`2026-08-30-e-terrain-coplanar-depth-spike.md`) and the harness pass
(`2026-08-30-e-terrain-harness-pass.md`). **ADRs 0039, 0040 and 0041** govern the ground's conditional
depth write, the altitude mode, and terrain being the one basemap resource that degrades rather than
failing a frame. **ADR 0016 gained an erratum retiring its DEM write obligation**, and **ADR 0039 gained
one refuting its own coplanar mitigation**.

**It forced a Rentile release, and that is the first thing to know.** Five of the six corpus styles
declaring `terrain` serve **WebP** DEM tiles; RenG owns a PNG decoder and nothing else, so terrain reached
**one style in six** — every tile fetched HTTP 200, passed Rentile's Skia-based validation, and was thrown
away. Rentile **`0.7.0`** now exposes `ValidatedDemTile.texels`, the pixels it already decoded for
validation and used to discard, so **RenG decodes no DEM at all**. The harness re-run renders **six of
six**. Adding a WebP decoder to RenG was rejected: VP8L and VP8 on an untrusted-bytes boundary, where the
PNG decoder alone took five adversarial passes and a 300,000-input fuzz test.

**Two things that release taught, both worth carrying.** Rentile's version rule is the **inverse** of
RenG's — a stale `VERSION_NAME` there silently ships a patch instead of failing closed, and would have
shipped a binary-breaking ABI change as `0.6.1`. And `Image.makeFromEncoded` reports **PREMUL**: red 200
behind alpha 128 stores as 100 and recovers as 199, which is invisible in a photograph and is a wrong
elevation in a DEM. Rentile's retaining path uses `Codec` for exactly that reason.

**How elevation reaches a vertex.** The DEM is uploaded as a texture and sampled with `textureLod` in the
vertex shader — a vertex stage has no implicit derivatives, so the plain form is undefined there, and the
sampler is declared `highp` because GLSL ES 3.00 gives the vertex language `precision lowp sampler2D`,
whose ~8 bits is exactly what an 8-bit channel needs and no more. **Filtering is `GL_NEAREST`, always**:
Mapbox packs 24 bits across R/G/B and a bilinear blend across a channel carry decodes hundreds of metres
wrong. Smoothness comes from mesh interpolation between vertices, never from the sampler.

**Seams are closed rather than hidden.** The DEM grid is **edge-exclusive** — texel `i` is centred at
`(i + 0.5)/N`, so a tile boundary is represented by no texel in either tile — and each DEM is therefore
uploaded as `(N+2)²` with a one-texel ring from its neighbours, so both sides of an edge read identical
source values. The ring costs a perimeter, `4√T + 4`, **not** ninefold: +33% at 167 tiles. Measured at
**0 cracked pixels of 589,824** in both projections, and the gate's "ring dropped" mutation is caught by
that case **alone**, at 61,063 cleared pixels.

**The two projections needed opposite work.** Mercator's ground was a four-vertex quad with no subdivision
anywhere; the globe's was already subdivided but baked altitude zero into one per-frame radial scale. Both
now share one lattice, and the elevation formula exists once as a GLSL fragment composed into both vertex
stages — pinned to `demElevationMetres` by *evaluating* it at basis points rather than transcribing it
twice.

**Granularity is a budget, not an error bound, and that is a real difference.** Curvature's rule can
promise half a pixel from radius and span alone; terrain's deviation is a property of the DEM's contents,
and measuring it would mean decoding every visible tile on the CPU. So it bounds *work* from two ceilings —
the DEM's own texel count and the tile's screen size — rounds **down**, and caps at **64**, derived from
X2's measured 167 tiles: 64 cells is 705,575 vertices where 128 would be 2,779,047. The globe's cap of 128
is not reused, because curvature peaks at zoom 0 and terrain at city zooms.

**The ground writes depth only where it is displaced**, which ADR 0039 narrowed from this cycle's own
design. An unconditional write revives ADR 0027's coplanar-`Geometry` defect in the **28 of 34** corpus
styles with no terrain — and the mutation proving it reproduces ADR 0027's shipped numbers exactly:
1,459 of 1,894 pixels deleted at pitch 30.

**`AltitudeMode` is `ABSOLUTE` by default because the glossary already said so** — `CONTEXT.md` defines
altitude as ellipsoidal metres — and the two modes are identical wherever the ground is flat, so nothing
shipped changes. An `elevationAt(lat, lon)` query was rejected as **circular**: elevation depends on
resident tiles, residency on the camera, the camera on the plan being built.

**What the cycle leaves owed, measured rather than suspected.** The `(N+2)²` padded assembly still happens
per frame per source tile, and the repair its own KDoc names — composing the content key from the nine
digests before assembling anything — is available and untaken. `GROUND_DRAPE_LIFT_METRES` is under-sized
for a ridge steeper than its fixture, ~10,000 pixels of speckle along fold lines, recorded rather than
widened, because widening a constant to fit a fixture is how it stops being a measurement. **A coplanar
globe drape is owed**: at zoom 2 a four-metre lift is 0.0002 of a logical pixel, so the fight is
unmitigated there. And **the coplanar residual's mechanism is not understood** — the spike narrowed it to
the ground's program, lattice and shader-computed height without splitting that bundle, and the relief-free
residual is about ten times larger than the decode arithmetic accounts for.

**Measured on this checkout at the cycle's close:** `testAndroidHostTest` **1,617**, `macosArm64Test`
**1,690**, `iosSimulatorArm64Test` **1,674** — 0 failures, 0 errors, 0 skips on each, summed from Gradle's
own JUnit XML. Four `SKIPPED [...]` lines are printed and all four are pre-existing: `runBasemapReadbackSuite`'s
ground-coverage case and `runGlobeFrameReadbackSuite`'s Mercator-coverage case, on `Apple Software Renderer`
and the iOS simulator. **The public ABI moved by +24/−4**: `AltitudeMode`, two `DiagnosticCode` constants,
`altitudeMode` on `Placement` and `Geometry` (with `Geometry.component6`), and
`RendererConfiguration.terrainShading`. Every removal is a constructor or `copy` signature replaced by a
wider one with a default.

**One duplication is known and owed.** Tasks 16 and 17 added five anchor cases to `TerrainFrameReadbackSuite`
and Task 19 then built `GroundAnchorReadbackSuite` beside it, because the brief for 19 wrongly said no gate
covered anchors. Cases 2, 3 and 5 of the new suite overlap the older one substantially; what is genuinely
new is the label case, the ridge fixture in place of a plateau, and the two modes contrasted inside one
frame. Carrying both is deliberate for now — the new suite is what the conformance classes register — but
the honest consolidation is one suite, and it is not done.

**Cycle G is complete and unreleased. RenG draws a globe.** Its authority is
`docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md`, its plan is
`docs/superpowers/plans/2026-08-29-cycle-g-globe.md`, and the harness pass is
`docs/research/2026-08-29-g-harness-pass.md`. `ProjectionMode.GLOBE` stopped being an
`unsupportedProjectionModeFailure` and became a frame that plans, resolves and draws: the sphere projection
and its latitude-matched LOD, the globe camera, far-hemisphere culling, the globe ground, placements,
geometries and labels on a sphere. **ADRs 0037 and 0038** govern `Camera.zoom` being projection-dependent
and the far-hemisphere cull.

**It grows the public ABI by nothing at all.** `kmp/api/kmp.klib.api` is byte-identical to `main` —
`ProjectionMode.GLOBE` was already a public constant that failed closed, so making it work moved no
signature. A diff there would have been a defect rather than a decision.

**The globe's apparent scale is measured, not asserted by eye.** At zoom 0 the silhouette is **180 logical
pixels across horizontally and 180 vertically** against an analytic tangent-cone prediction of **180.2**,
and undrawn falls **94.3% → 53.8% → 0.0%** across zooms 0, 2 and 5.

**The trig precision decision was vindicated on a driver CI actually runs.** The probe measured
`Apple Software Renderer` at atan 1,687 ULP, sin 170,439 and cos 117,441, which puts the naive latitude
formulation **7,118 m** out against the half-angle form's **0.680 m**.

**Cycle G's one open decision is closed: the `Float` error is accepted and RenG does not transition.**
The globe-fixed formulation evaluates in `Float` on the GPU where Mercator rebases per tile in `Double`.
Subdivision *cost* turned out not to argue for a transition at all — granularity halves to a single quad
above zoom 11 — so the question §5 deferred was purely precision, and it was measured on screen against a
Mercator control rather than left as arithmetic
(`docs/research/2026-08-29-g-float-precision-measured.md`). The displacement is **zero at the frame centre
at every zoom**, because `globeFixedToCameraRelative` subtracts the radius on the up axis and the anchor
maps to the origin exactly; it reaches **+50 logical pixels at the frame edge at zoom 22** against a
predicted 45.8, and is invisible to a pixel-aligned search below about zoom 21.5. **The owner accepted it
on 2026-08-29**: no Mercator handover, no per-tile rebasing, one formulation at every zoom, and a smear at
the frame edge in the top zoom level or so. ADR 0037 carries the erratum. Reversal is additive and cheap;
what must not happen quietly is a tuned transition constant nobody measured.

**The poles are closed by stretching the edge texel row, and that is the one defect the videos found.**
Web Mercator tiles end at ±85.0511°, so the cap above them has no tile — a hole a flat map can never show,
because the region is not in frame at all. Measured at **783** pixels of the harness's clear colour enclosed
by the sphere at zoom 2, a notch widening from 14 to 56 px across 24 rows. A top- or bottom-row tile now
also paints the cap beyond it: the shader's own `sin(latitude) = (t² − 1)/(t² + 1)` reaches the pole with no
branch, because at `ψ = 20` the `t²` term is 2.35 × 10¹⁷ and both the −1 and the +1 round away in `highp
float`, leaving exactly 1.0 and a 4.1 × 10⁻⁹ radian residual — 2.6 cm. The cap reuses its tile's longitude
`Float`s **untouched** rather than recomputing equal ones, for the reason `globeGroundTileEdges` already
measures: an equal-but-different longitude is 5.09 logical pixels of crack at zoom 18. After: **19** pixels,
and all 19 are imagery — the region around them is dark forest green straddling the sentinel, a few landing
exactly on `(0, 96, 32)`. **The harness's clear colour is not as unique as its KDoc claims**; satellite
imagery produces it.

**Curvature fidelity is not claimed and stays Cycle J's.** The readback gate asserts relationships over a
real context; the sagitta of a frame-sized quad is 0.44 logical pixels at zoom 10, so a cross-mode
comparison taken above about zoom 12 cannot tell a globe from a tangent plane and this cycle's is bounded
in both directions at zoom ≤ 8.

**Cycle K is in progress on `feat/k-frame-plan-corpus`, and it replaced Cycle J rather than implementing
it.** Its authority is `docs/superpowers/specs/2026-08-31-cycle-k-frame-plan-corpus-design.md` and its plan
is `docs/superpowers/plans/2026-08-31-cycle-k-frame-plan-corpus.md`; `docs/decomposition.md` carries J's
withdrawal with the reasoning. J was to compare renders against **stored baselines with a tolerance**, and
both halves were wrong for this repository: a stored baseline is a claim about a driver, and the tolerance
that makes one survivable across machines is ~3,000x wider than the signal a real regression emits — the
`0.3.0` failure measured that exact number, 3,005 wrong pixels of 15,876 on `Apple Software Renderer`.
K compares **two commits on one machine**, where driver, libm and rasteriser are all constant and RenG's
source is the only variable, so the comparison is exact (**ADR 0043**).

**A `FramePlan` is now a document (ADR 0042).** Twenty-three public types gained `serializer()`;
`kmp/api/kmp.klib.api` grew by **97 lines and lost none**, every added line a `Companion` or a `serializer()`
overload. **Not one is a bare `@Serializable`**, for two independent reasons: six of these classes take
constructor parameters that are not properties, which the plugin refuses outright, and *every* one of them
validates in `init`. A plugin-generated deserializer assigning fields directly would mint a `Vector3`
holding NaN or a `Camera` at zoom 99 from a well-formed document. So each type carries
`@Serializable(with = …)` over a private surrogate whose `deserialize` calls the real public constructor —
**a decoded plan is exactly as validated as a constructed one**, and a bad document fails at decoding with
RenG's own `require` message. The cost is twelve places to forget a property, which no compiler catches;
the round-trip gate's fixture therefore uses a non-default value for every property, so a dropped one
round-trips to something the original was not.

**`kotlinx-serialization-core` is taken at `api` scope, and it is the only coordinate admitted there.**
`implementation` would put `KSerializer` on the runtime classpath and leave it off every consumer's compile
classpath — a public signature naming a type the consumer cannot see. Rentile `0.7.0` already declares
`kotlinx-serialization-json` at runtime scope, so **no consumer's resolution graph gains an artifact**;
RenG merely starts owning a version it already inherited. The policy cost was **six** refusal points, not
the three the plan budgeted: `_FORBIDDEN_DEPENDENCY` rejects the word `serialization` by name, and
`_EXPECTED_PLUGIN_BLOCKS` and `_PLUGIN_ACCESSORS` sit behind the allowlist and the two fingerprint sets.
`_EXPECTED_PLUGIN_BLOCKS` now keeps history as a tuple of accepted forms, exactly as the fingerprints do.
**Three separate places pin a dependency's call kind**, so a test for that rule only flips when all three
are broken at once — which is how it was verified rather than assumed.

**The baseline review happened on 2026-08-31.** Its record is
`docs/research/2026-08-31-k-baseline-review.md`: 77 runs, 124 frames, 0 failed, every frame looked at on
an M3 Max. What is right is listed there, including one thing worth having seen — on satellite imagery
the polar cap's closure is plainly a fan of radial streaks above the top tile row, which is what
"stretching the edge texel row" looks like when the texels are photographs.

**It first reported three defects; two of them were my own test error, and that correction is the more
useful half.** The corpus placed a pin and a quad at **`ABSOLUTE` altitude 0 in a valley whose floor is
about 1,200 m**, found them invisible, and called it a renderer bug. They were underground, and RenG was
right to hide them: `CONTEXT.md` makes altitude ellipsoidal metres and ADR 0040 keeps `ABSOLUTE` meaning
that. Measured after the correction, the same quad in `GROUND_RELATIVE` draws **22,231 pixels at pitch 0
and 10,641 at pitch 30**, and a ground-relative sticker over the city draws at altitude 0 and grows with
height. **`GROUND_RELATIVE` works as designed.** Sea level is a degenerate altitude in mountainous
terrain, and a fixture at a degenerate point proves nothing — the same lesson this project keeps
relearning inside its own suites, met here in a corpus instead.

**One defect is real: the ground vanishes at high zoom over high terrain.** Only styles that actually
serve a DEM are affected, and `terrainShading` is not the trigger. The city (~100 m) and the ocean (~0 m)
draw at every zoom and pitch tested; Yosemite (1,200–2,700 m) is blank at zoom 14 pitch 55 and blank at
**every** pitch by zoom 15. Nothing fails, nothing warns, and the blank frame fetches *more* tiles than
the working one (89 against 54, all HTTP 200).

**The threshold tracks terrain height against camera height.** RenG derives camera altitude from zoom
alone — `512 * 2^zoom` logical pixels for 40,075,017 m — so at zoom 15 the eye is on the order of 1,500 m
above the *ellipsoid*, beneath Yosemite's walls. **That is a recorded design decision rather than an
oversight**: E-terrain rejected an `elevationAt(lat, lon)` query as circular, so there is no terrain-aware
camera by choice. What that decision does not cover is the *silence* — ADR 0041 makes terrain the one
resource that degrades rather than failing a frame, and a blank frame with no diagnostic is neither.
**Raising the camera, clamping the displacement, or announcing the condition is an owner decision and an
ADR, not a fix to slip into a release.** It is also **not a regression**: terrain shipped in the public
`0.4.0` and the behaviour reproduces against that coordinate; Cycle K touches none of it.

**What K still owes.** The corpus carries no model, because a GLB url points at somebody's server and none
is checked in, so the model pipeline is unreviewed. A plan file repeats a geometry's shader source once per
frame, which is why the 48-frame storyboard is 124 KB. And `terrainShading = false` still fetches every DEM
tile, so that flag governs shading rather than acquisition — recorded, not yet judged.

Design decisions live in `CONTEXT.md` (vocabulary) and `docs/adr/` (ADRs 0001–0012 establish the
original graphics contract, ADR 0013 governs fail-closed publication, ADRs 0014–0015 supersede
preparation ordering and GL-deletion context behavior, ADRs 0016–0017 govern the Rentile firewall and
terminal renderer ownership, ADR 0018 governs canonical identities, ADR 0019 takes kotlinx-coroutines as a
first-party dependency, ADRs 0020–0021 fix PNG decode ownership and the supported GLB subset, ADR 0022
supersedes ADR 0009's source-set visibility claim, ADR 0023 supersedes ADR 0006's GL restore set and
declares the GL error-queue exception, ADR 0024 draws the map regime first and composites the screen
regime on top as one stack, ADR 0025 supersedes ADR 0024's depth rule — `GL_GEQUAL` instead of
`GL_GREATER`, plus a fixed map-regime draw order of ground, then geometries, then map-anchored stickers —
ADR 0026 invents the one directional, world-anchored light every model is shaded by, at azimuth 335° and
elevation 45° with an ambient term, leaving stickers, geometries and the ground unlit, ADR 0027 supersedes
ADR 0025's depth-*write* ruling so that no map-regime draw writes depth and declaration order becomes the
whole rule inside the regime rather than only its tie-break, ADR 0028 narrows ADR 0021's flat accessor
subset to a per-role one, and ADR 0029 rejects a `SCREEN`-positioned **Model** at frame planning because
the screen projection carries no z row at all, and ADR 0030 supersedes ADR 0027 **for the model pass alone**
so that a mesh writes depth and can occlude itself, giving the map regime three depth phases and the order
ground, geometries, models, map-anchored stickers, ADR 0031 stands on deprecated OpenGL ES for iOS and
records that cinterop drops `API_DEPRECATED`, so no build will ever warn, ADR 0032 takes
`androidx.test:runner` as the second third-party dependency after ADR 0019's, for instrumented tests only,
and ADR 0033 gates the mobile targets asymmetrically and says what a release may claim about a target
verified only in simulation, ADR 0034 draws labels as a fourth scene list orthogonal to `drawBasemap`,
ADR 0035 makes fade the only cross-frame label state, ADR 0036 reports engine label exclusions as one
aggregate diagnostic, ADR 0037 makes `Camera.zoom` projection-dependent so a globe's world size is
`512 * 2^(zoom - log2 cos latitude)`, and ADR 0038 culls the far hemisphere rather than depth-testing
it, the far side being exactly the back-facing set once the ground grid winds consistently). Read both before proposing anything that touches the public API — where this
file and an ADR disagree, the newer ADR wins.

## What RenG is

A Kotlin Multiplatform dependency that renders 3D worlds on top of basemap tiles from
[Rentile](https://rohittp.com/rentile/) (`com.rohittp.rentile:kmp`, source at
`/Users/rohittp/Data/Other/rentile`). RenG draws a frame onto a caller-supplied surface. It does not
own a window, a render loop, a capture path, or an encoder.

Two contracts drive every design decision:

**RenG is pure.** It makes no changes to the host system. It performs no network I/O and owns no
persistent cache of its own — the consumer injects transport and store adapters, which RenG proxies
down to Rentile (Rentile's `ResourceTransport` / `RawResourceStore` interfaces) and uses for its own
assets (sticker images, GLBs, textures). Persistent caches are the consumer's. RenG only follows the
supplied `FramePlan`.

**A `FramePlan` is a complete definition of on-screen state.** Callers do not issue incremental
mutations. Frame-to-frame reuse — of decoded images, uploaded textures, parsed GLBs, compiled
shaders, tiles — is entirely RenG's internal concern, invisible in the API. This means diffing
consecutive `FramePlan`s and keying cached GPU/CPU resources by plan content is core renderer work,
not an optimization to bolt on later.

### Lifecycle contract

- Setup takes the caller's already-current GL context and native resources (transport, store, basemap
  style, output pixel size); the render loop takes a **Prepared Frame**. Acquisition and drawing
  are separate operations — see ADRs 0001, 0002, 0004, 0012, and 0014.
- RenG exposes API to query and free the resources it holds; the consumer calls it when it needs to.
- `close()` frees everything. `close()` and `free()` are idempotent deletion operations; while live GL
  handles exist, both require the renderer's exact GL context to be current and otherwise fail without
  changing state (ADR 0015).
- Accessing a freed resource **reloads it and emits a warning** — freeing is never an error for the
  caller to recover from, so the resource layer needs a reload path on every access, not an assert.
- Losing the GL context is **not** freeing: a separate context-free operation makes RenG forget its GL
  handles without deleting them, keeping every CPU-side resource intact (ADRs 0007 and 0015).

## Domain model

```
FramePlan(frameIndex, camera, projectionMode = MERCATOR, drawBasemap = true,
          stickers = emptyList(), models = emptyList(), geometries = emptyList())

Camera(latitude, unwrappedLongitude, zoom, bearing, pitch)

Placement(positionMode: AnchoringMode, position: Vector3,
          rotationMode: AnchoringMode, rotation: Vector3,
          scaleMode:    AnchoringMode, scale: Double /* [0, inf) */)

AnchoringMode = SCREEN | MAP

Sticker(placement, image: ResourceLocator /* PNG */)

Model(placement, glb: ResourceLocator, texture: ResourceLocator? = null,
      animationTracks: List<AnimationTrack>)

AnimationSelector = Name(exactName) | Index(zeroBasedLongIndex)
AnimationTrack(animation: AnimationSelector, timeSeconds: Double)

Geometry(topLeft: Vector3(latitude, unwrappedLongitude, altitude),
         bottomRight: Vector3(latitude, unwrappedLongitude, altitude),
         shaderPair: ShaderPair)

ShaderPair(vertexSource: String, fragmentSource: String)
```

Non-obvious semantics:

- **Anchoring is per-property, not per-object.** One `Placement` can mix modes — e.g. `MAP` position
  with `SCREEN` rotation (a billboard pinned to a coordinate). The transform pipeline must resolve
  each of position, rotation, and scale independently.
- **`SCREEN` anchoring turns `position.z` into a z-index** — ordered compositing, no depth test.
  **`MAP` anchoring requires full occlusion testing** against the 3D scene. These are two distinct
  draw regimes in one frame; ordering between them is a design decision worth an ADR.
- **Geometry shaders are GLSL ES 3.00 sources, self-contained but for their version directive.** No
  RenG-injected includes or uniform preamble; RenG substitutes `#version 330 core` for
  `#version 300 es` on desktop GL contexts and changes nothing else, and binds documented uniform and
  attribute names only when the shader declares them (ADR 0008). A `Geometry` is a
  lat/lon/altitude-bounded quad the shader pair paints.

## Implemented structure (mirroring rentile)

Rentile remains the structural template. The implemented surface is:

| Path | Purpose |
|---|---|
| `kmp/` | The one published module. The public API sits at `com.rohittp.reng`; everything else is a deep package boundary under `com.rohittp.reng.internal` — `basemap`, `cache`, `diff`, `driver`, `failure`, `firewall`, `gl`, `glb`, `identity`, `image`, `json`, `lifecycle`, `math`, `planning`, `preparation`, `projection`, `resource`, `shader` — and **not** separate Gradle subprojects (rentile ADR 0002: KMP publication does not fold unpublished project dependencies into the aggregate artifact, so extra modules would break the single-coordinate guarantee). |
| `docs/adr/` | One short ADR per decision, `NNNN-imperative-title.md`, a few paragraphs of prose — no template headings. |
| `docs/` | Dependency-free static site published to GitHub Pages at `https://rohittp.com/reng/`. |
| `CONTEXT.md` | Domain vocabulary: each term with its definition and an explicit `_Avoid_:` list of rejected synonyms. Read it before naming anything. |
| `consumer-smoke/` | **Standalone** Gradle build (own `settings.gradle.kts`) that resolves the published coordinate from an isolated repository with `exclusiveContent`, proving a release resolves without credentials and without Central masking it. Reads `VERSION_NAME` out of `../gradle.properties` rather than pinning a literal. `commonMain` is that six-target proof; `macosArm64Main` is the visual harness, which lives here because the policy checker permits Kotlin in exactly two places, `kmp/src` and `consumer-smoke`. |
| `.github/workflows/` | `ci.yml` gates the branch on Ubuntu and macOS; `publish.yml` resolves one release candidate and verifies local, R2, public HTTP, and clean-consumer publication stages. See "CI/CD" below. |
| `tools/` | Standard-library Python release resolver, publication verifier, repository-policy checker, test-failure printer, iOS device-test runner, and their unit tests. The repository contains no shell scripts at all. |

Conventions carried over:

- `explicitApi()` plus Kotlin ABI validation (`checkKotlinAbi`) — public API changes are a reviewed diff.
- `VERSION_NAME` in the root `gradle.properties` is the **sole checked-in version input**. Never
  hardcode a RenG version in docs HTML, the smoke consumer, or the README. The release workflow may
  derive a later patch from the public version line under ADR 0013.
- `org.gradle.configuration-cache=true` is on, but publish/CI invocations pass
  `--no-configuration-cache` because remote Maven publishing is not CC-compatible.
- Typed exceptions with stable error codes, pipeline stage, and **redacted** diagnostics. Never
  forward messages or causes from injected transport/store adapters — they can carry signed URLs.
  Keep cancellation as an unwrapped `CancellationException`; Kotlin stack recovery may copy it with the
  original as its immediate cause. RenG performs no repeated consumer exchanges, retries, repairs, or
  fallbacks; Rentile's private retry calls replay the operation's latched outcome. The caller owns recovery.
- Never commit a `mavenLocal()` entry or a `-SNAPSHOT` dependency. Local cross-repo development uses
  `./gradlew publishToMavenLocal` in rentile plus a temporary repository entry, reverted before committing.

## Platform targets

RenG publishes exactly six targets:

```
android  iosArm64  iosSimulatorArm64  macosArm64  linuxX64  linuxArm64
```

**Apple Silicon only** — no `macosX64`, no `iosX64`. This matches rentile's release surface and its
reasoning (rentile ADR 0022): every published target is a permanent commitment, because removing one
later breaks resolution for anyone who adopted it. An Intel Mac gets a hard resolution failure, not a
degraded render. Adding `macosX64` later is a compatible change; adding it speculatively is not free.
This target decision is recorded in ADR 0010.

**No `jvm` target.** Rentile publishes one; RenG's spec enumerates Android, iOS, macOS, and Linux, so
the JVM is deliberately out of the published surface and absent from the ported workflows. Android
host tests still run on the JVM — that is a test source set, not a published target. If the macOS
harness or a future consumer needs it, adding `jvm` means touching both workflows and `consumer-smoke`.

**Rentile publishes every target RenG needs.** `kmp-android`, `kmp-iosarm64`, `kmp-iossimulatorarm64`,
`kmp-macosarm64`, `kmp-linuxx64`, and `kmp-linuxarm64` all resolve from `https://maven.rohittp.com`, so
RenG depends on `com.rohittp.rentile:kmp` with no `mavenLocal()` and no temporary repository entry. The
pinned version is in `gradle/libs.versions.toml` — do not quote a Rentile version from prose, including
from this file, since it moves. Bumping it moves five coupled places at once (`HANDOFF.md` names them and
gives the fingerprint recompute) or the repository policy fails closed.

## CI/CD

`.github/workflows/ci.yml` and `publish.yml` consume the implemented `:kmp`, `consumer-smoke`,
Python tools, and policy checks.

`ci.yml` has two jobs on push to `main` and every PR. `android-linux` runs the complete Python suite,
repository policy, ABI validation, Android host tests, `linuxX64Test`, Linux ARM64 compilation, and the
Android AAR gate on Ubuntu. `apple-publication` compiles both iOS targets, runs `macosArm64Test` and
`iosSimulatorArm64Test`, publishes all seven publications to `build/local-maven`, then compiles the
standalone consumer's six targets with a fresh Gradle home and `--refresh-dependencies`. Cycle H added
`iosSimulatorArm64Test` as one more task name in that existing invocation — no new job, no new step, and
`print_test_failures.py`'s `if: failure()` step already globbed the whole `kmp/build/test-results` tree,
so nothing had to widen to see it.

`publish.yml` runs for every non-documentation push to `main` and for an explicit dispatch from `main`.
It implements a **one-candidate rule**. If checked-in stable `VERSION_NAME` is newer than every public
stable version, that explicit declaration is the candidate and may recover from a partial release. Otherwise,
automatic next-patch advancement requires HTTP 200 plus a strict matching completion record for the newest
metadata-listed version at
`com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`. Schema version 1 has exactly integer
`schemaVersion` equal to 1, canonical stable `mavenVersion`, lowercase 40-character `sourceCommitSha`, and
lowercase 64-character `manifestSha256` over the exact serialized local manifest. Missing, malformed,
mismatched, redirected, or uncertain records; malformed or empty metadata; transport errors; unexpected
statuses; snapshots; and occupied candidates stop resolution. Explicit upward recovery bypasses the prior
record. The selected candidate still receives exactly one aggregate-POM availability probe, and the resolver
never skips it. Partial-release recovery is always an explicit upward `VERSION_NAME` change, never
overwrite, delete, reuse, or automatic skip.

The release gate chain is: Python tests and repository policy → Ubuntu ABI/Android/Linux gates → local
publication of `kmp` plus its six target artifacts → all seven POM checks → manifest-derived POM and
artifact validation → fresh-home six-target local smoke → authoritative exact-key R2 collision
checks → upload → anonymous HTTP verification of every manifest entry and aggregate metadata, with stale or
malformed HTTP 200 metadata retried within the configured budget → a copied standalone smoke project
resolving all six targets from the public repository with no credentials, a fresh Gradle home, and
`--refresh-dependencies` → canonical completion-record derivation → authoritative conditional R2 creation
with `If-None-Match: *` → credential-free anonymous record verification with retries. Of those three
completion-record stages, only the conditional write receives R2 credentials. The aggregate publication
still runs after all six target publications as defense in depth, but neither aggregate-POM nor metadata
availability proves completion. `publish-main` concurrency is serialized with `cancel-in-progress: false`.

The standard-library Python tools are:

- `tools/check_repository_policy.py --root .` — enforces the Cycle A target, dependency, ABI, version,
  docs, repository, and license constraints.
- `tools/resolve_release_version.py --properties-file gradle.properties --repository-url <url>` — prints
  the sole selected candidate or fails closed.
- `tools/verify_publication.py` has five exact CLI surfaces:
  - `local --repository <path> --version <version> --manifest <path>`
  - `r2-preflight --endpoint <url> --bucket <bucket> --version <version> --manifest <path>`
  - `public --repository-url <url> --version <version> --manifest <path> [--attempts <n>] [--retry-delay <seconds>]`
  - `completion-create --version <version> --manifest <path> --source-commit <sha> --output <path>`
  - `completion-public --repository-url <url> --version <version> --manifest <path> --source-commit <sha> [--attempts <n>] [--retry-delay <seconds>]`
  They derive and validate the immutable local manifest, reject exact R2 key collisions, verify anonymous
  public artifacts and metadata, derive the manifest-bound completion record, and verify that record
  anonymously. `tools/release_completion.py` is the shared record module behind the last two, not a CLI.
- `tools/print_test_failures.py <test-results-dir>` — **a diagnostic, not a gate.** Gradle renders a
  Kotlin/Native failure as its exception class and location and never its message, which is why `0.3.0`'s
  failed publication produced `kotlin.AssertionError at null:-1` and nothing else. This walks Gradle's own
  JUnit XML and prints what the console withheld. It runs as an `if: failure()` step on all four
  test-running jobs across both workflows, alongside an `upload-artifact` of the reports, and it never
  fails: a job that reaches it has already failed, and a reporting error must not change how.
- `tools/run_ios_device_tests.py --device <udid> --profile <mobileprovision> --identity <sha1>` — **also a
  developer tool, not a gate**, and nothing in CI runs it. `iosArm64` links a test binary that iOS will not
  execute as a bare Mach-O, so this links, wraps the `.kexe` as an `.app`, signs it against a profile
  covering the device, installs, launches with `--ktest_filter`, and uninstalls. It defaults the filter to
  the GL tests because an unfiltered run executes every test, never becomes responsive, and is SIGKILLed by
  the watchdog. It reads its verdict from Kotlin/Native's own GTEST summary rather than from the exit code,
  because a filter that matches nothing prints `[  PASSED  ] 0 tests.` and exits 0. It refuses an expired
  profile, a profile that does not list the device, and a profile whose bundle identifier belongs to a real
  app, all before the phone is touched — and it keeps `security cms -D` quiet, because a wildcard profile's
  device list is 41 other people's UDIDs and diagnostics never carry that class of thing.

Publishing needs repository **vars** `R2_ENDPOINT`, `R2_BUCKET`, `R2_PUBLIC_URL` and **secrets**
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`. A dedicated step fails fast if any is missing. Do not run AWS,
upload, push, dispatch, or otherwise infer that outward gates passed without explicit approval and an
observed workflow result. Three releases have completed this way: `0.1.0` from
`af92901b2ef045078b855a6b47533bc95aca6886` (CI run `31968682132`, publication run `31968682290`), `0.2.0`
from `a2cbe6a965247f221f7e279a962b40306baac21b` (CI run `32314778617`, publication run `32314778594`), and
`0.3.0` from `49cc1d5843ba40ac866f96d00123ca07b662f6fc` (**run IDs not recorded in this repository**). All
three completion records verify anonymously. ADR 0013 and the Cycle A design spec and implementation plan
remain historical decision records. Keep public version display metadata-driven and do not check a RenG
semantic version literal into README or served docs.

Two rentile gates were **not** ported because RenG has no analogue: the credential-free coverage
manifest check (`tools/check_coverage_manifest.py` over `compatibility/`) and the rolling
`map-catalog-corpus.yml` workflow, both of which exist to prove rentile renders every style in a
live public map catalog. RenG's equivalent — golden-image rendering over a corpus of `FramePlan`
documents — is undesigned; if it lands, it slots into the same two places (a CI job plus a gate step
in `publish.yml` before upload).

`org.gradle.configuration-cache=true` is set in `gradle.properties`, but every workflow and release-gate
Gradle invocation passes `--no-configuration-cache` because remote Maven publishing is not CC-compatible.

## The visual harness

A local development client that consumes a locally published RenG, drives it through a storyboard, and
writes frames for `ffmpeg` to assemble. **Capture and encoding live in the harness, not in RenG** — RenG
only draws. It was pulled forward into the basemap cycle from what was then Cycle I, and on **2026-08-28
Cycle I was withdrawn**, so this is the whole of the harness rather than half of it.

It lives in `consumer-smoke/src/macosArm64Main/`, under the standalone consumer's own build, so it
exercises the real published coordinate rather than a project dependency. It owns context creation: a
headless CGL core-profile context reached through the stock `platform.OpenGLCommon` and `platform.OpenGL3`
klibs with **no cinterop at all**, reporting `Apple M3 Max | 4.1 Metal - 90.5`. It drives a fixed 48-frame
storyboard (`Storyboard.kt:25`, with frames 30..32 as negative cases), and it writes binary PPM and prints
an `ffmpeg` line. Since Cycle K the storyboard is no longer a code path but one ordinary corpus file,
`consumer-smoke/corpus/plans/storyboard.json`, and the harness reads plans rather than generating them.

**`FramePlan` JSON came back in Cycle K; the AVFoundation encoder did not, and the distinction is the
point.** I withdrew both and said reversing the first would be "cheap and compatible" if anything ever
needed plan documents — Cycle K is that, and it cost one dependency and twelve serializers. The encoder
stays withdrawn on its original argument, unchanged and still correct. The paragraph below is the
reasoning as I recorded it, and only its serialization half is superseded.

**`FramePlan` JSON and a self-contained AVFoundation encoder were withdrawn with Cycle I, deliberately.**
The harness is verification code, so an encoder inside it is bug surface that can produce a misleading
video and cast doubt on the renderer; a PPM frame is a header and raw bytes, and `ffmpeg` is better
debugged than anything written here would be. RenG therefore gains no serialization surface and no
serialization dependency, and `FramePlan` serialization is no longer an unowned prerequisite of anything —
Cycle J's corpus is in-source Kotlin fixtures. The cost is kept rather than removed: the
`ffmpeg`/libx264/`yuv420p` assembly step is **lossy** and has already once misled a judgement about image
sharpness, so judge sharpness at `crf 12` / `yuv444p`, or on the PPM frames directly.

**The harness can lie about the renderer, and once did.** Cycle G's globe frames filled the viewport and
were byte-identical at zoom 0, 3 and 14 while Mercator responded normally — which reads exactly like a
projection ignoring its camera, and was diagnosed as one. The cause was `parseArguments` advancing
`index += 2` unconditionally, so it read only **even** positions: `--globe` is a valueless flag emitted
before `--zoom`, which pushed `--zoom` onto an odd index the loop never examined, silently, because an
unmatched argument is not an error there. Every globe run had been rendering at the storyboard's default
zoom of 11.5 — the one zoom where the sphere is larger than the viewport and a correct globe is
indistinguishable from a flat map. With the stride fixed the globe measures as a globe: a silhouette 180
logical pixels across both ways at zoom 0, against an analytic tangent-cone prediction of **180.2**, and
undrawn falling 94.3% → 53.8% → 0.0% across zooms 0, 2 and 5. **Before believing a harness frame accuses
the renderer, confirm the harness passed the renderer what you think it did** — and prefer the harness's
own `undrawn %`, which counts its `UNDRAWN` sentinel `(0, 96, 32)`, over any ad-hoc "how much is drawn"
statistic computed against a guessed background. An earlier metric here counted *pixels that were not
white*, which made the sentinel itself read as drawn and reported a 99.7%-full frame that was in fact
98.9% empty.

## Commands

Run Python and policy gates first:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

On macOS, run every locally compilable Ubuntu-equivalent gate (do **not** claim `linuxX64Test` ran):

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Run the Apple, cross-target compilation, local publication, and fresh dependency-cache smoke gates:

```bash
./gradlew --no-configuration-cache \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileTestKotlinIosArm64 \
  :kmp:macosArm64Test \
  :kmp:iosSimulatorArm64Test \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:publishAllPublicationsToLocalTestRepository

final_smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$final_smoke_home" \
  --refresh-dependencies \
  -p consumer-smoke \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

On Ubuntu CI, the host-executable command is:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

`linuxX64Test` is Linux CI coverage, not a macOS-local gate. `macosArm64Test` and `iosSimulatorArm64Test`
are the two Apple test tasks; `iosArm64` stays compile-only, and `compileKotlinIosArm64` compiles `iosMain`
alone, so `compileTestKotlinIosArm64` is what actually proves the `iosTest` sources build for the device
target. On the current macOS system Ruby 2.6 toolchain, parse both workflow files with Psych's
aliases-enabled positional API:

```bash
ruby -e 'require "yaml"; YAML.safe_load(File.read(".github/workflows/ci.yml"), [], [], true); YAML.safe_load(File.read(".github/workflows/publish.yml"), [], [], true)'
```

The two device runs are manual and need hardware attached — an Android phone or a booted emulator on `adb`
for `./gradlew --no-configuration-cache :kmp:connectedAndroidDeviceTest`, and a paired, provisioned iPhone
for `python3 tools/run_ios_device_tests.py --device <udid> --profile <mobileprovision> --identity <sha1>`,
which links, signs, installs, runs filtered and uninstalls in one command. Neither is in CI, by ADR 0033.

Single test in any Gradle test source set: `--tests "com.rohittp.reng.SomeTest"` (works on Kotlin/Native
test tasks too). Every CI and publication Gradle invocation passes `--no-configuration-cache`.

`local.properties` is untracked and machine-specific; do not commit it.
