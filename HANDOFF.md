# RenG handoff — 2026-08-22

The recovery point for whoever picks RenG up next. **RenG draws.** `createRenderer` is published API,
`VERSION_NAME` is `0.2.0`, and the basemap branch renders a real Rentile ground.

**Two releases are public and both are proven, not assumed.** `0.1.0` completed from
`af92901b2ef045078b855a6b47533bc95aca6886` (Cycle A). `0.2.0` completed from
`a2cbe6a965247f221f7e279a962b40306baac21b` and carries **Cycles B, C, D and F-1 together**: CI run
`32314778617` and publication run `32314778594` both succeeded on that exact commit, and
`com/rohittp/reng/kmp/0.2.0/reng-release-completion-v1.json` verifies anonymously, naming that commit and
manifest digest `e93bdfde7870056552961791d2d29fea43b6030e6b5e4493ff425049d0dcb1d4`. The first attempt, at
`3a6eec5`, failed both jobs and failed closed before any R2 write — a scheduler-benchmark wall-clock
ceiling calibrated on a developer M3 Max that a hosted runner could not meet. Nothing was published, so
`c67349a` replaced the ceiling with a machine-independent scaling-ratio assertion and the release retried
at the same version. That is exactly the recovery ADR 0013 describes, and it is worth remembering as the
one time the fail-closed design was actually exercised.

`main` and `origin/main` are the same commit, `a2cbe6a`. Everything below about Cycles B, C and D describes
**released** work; the sections are kept because they carry the measurements, defects and lessons that
produced it, not because their status is open.

**The basemap cycle is complete and gathered on `feat/cycle-e-basemap`, ahead of `main`, and is neither
merged nor released.** See "E-basemap, as implemented" below for what it ships, what it deliberately does
not, and what is owed before it merges. Its exact merged-commit CI and publication have not been observed,
because there is no merged commit yet.

**Merging the basemap branch to `main` publishes it.** `publish.yml` runs on every non-documentation push
to `main`, and since `0.2.0` has a valid public completion record the resolver advances to `0.2.1` and
writes it to R2 immutably. Nothing about that has happened. If a deliberate version is wanted instead,
change `VERSION_NAME` upward in the same commit.

## Read first

1. `CLAUDE.md` — repository constraints, purity contract, six targets, commands, publication rules.
2. `CONTEXT.md` — canonical vocabulary. Read it before naming anything.
3. `docs/adr/0001`–`0026` — newer ADRs override older prose. Two landed with the basemap cycle: **0025**
   supersedes ADR 0024's depth rule (`GL_GEQUAL`, and a fixed map-regime draw order of ground, then
   geometries, then map-anchored stickers, later declaration winning an exact tie), and **0026** invents
   the single directional world-anchored light every model is shaded by. ADR 0016's "eight basemap
   classes" now carries its own appended erratum for Rentile `0.3.0`'s ninth class; do not edit the
   decision text.
4. `docs/decomposition.md` — the cycle sequence and each cycle's gates.
5. `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md` and its plan
   `docs/superpowers/plans/2026-08-20-cycle-e-basemap.md` — the cycle in flight. The Cycle A, B, C, D and
   F-1 pairs in the same directories are historical decision records; Cycle C's plan in particular is
   **wider than what shipped** (tasks 1–13 and 15 shipped in `0.2.0`, five travelled to the basemap cycle
   and one to terrain), so read the plan for what a task number covers and this document for what shipped.
6. `.superpowers/sdd/2026-08-20-cycle-e-basemap/progress.md` — the basemap cycle's per-task ledger: every
   dispatch, review verdict, fix round and mid-cycle ruling, plus four spike summaries. It is the densest
   record of why the cycle looks the way it does.
7. `docs/research/` — the findings documents. Read the relevant one before touching the area it covers.
   Newest first: the F-2 draw-regime/occlusion and glTF capability spikes, the golden-image gate design,
   the Rentile upgrade-and-labels measurement, the consumer model corpus check, and the glyph-closure
   request to Rentile.

Approved specifications and plans are not reopened without repository-owner review.

## Cycle B, as released in `0.2.0`

Pure core only: public immutable values, protocols and sanitized failures, canonical identities and
SHA-256, spatial and diff planning, and pure lifecycle, resource and preparation reducers driven entirely
by supplied values. Every plan task is complete, including the cross-engine contract proof, and every task
was independently reviewed with its findings fixed.

There is no renderer factory, consumer adapter call, Rentile acquisition, decoder, parser, production
cache, GL call, shader compilation or pixel. The public ABI dump contains no Rentile type, platform binding
or renderer factory, and was frozen by Task 5 — nothing since has changed it.

Verified on Linux with forced task re-execution: `checkKotlinAbi` clean; `testAndroidHostTest` 432 tests
and `linuxX64Test` 428 tests, no failures; Linux Arm64 compilation and the Android archive gate pass; all
seven publications resolve at `0.1.0` into a local repository, and a fresh-Gradle-home consumer resolves
the three Linux-executable targets with no credentials; 73 Python tests and `Cycle B repository policy
passed`. The Apple gates cannot run on Linux and were observed in continuous integration instead: runs
`32055118061` and `32058579004` each passed both the Ubuntu job and the macOS job, the latter compiling
both iOS targets, running `macosArm64Test`, publishing all seven publications and resolving a clean
six-target consumer.

The two lines that used to close this section — that merged-commit CI and publication were unobserved, and
that `VERSION_NAME` remained `0.1.0` — are **superseded**. Cycle B merged to `main` and shipped inside
`0.2.0` from `a2cbe6a`, whose exact-commit CI and publication runs both succeeded. The 432/428 counts above
are Cycle B's own figures at the time and are not the current suite size; see the basemap section for that.
Still true and still not to be claimed: `linuxX64Test` has never run on macOS, and `macosArm64Test` has
never run on Linux.

### What review caught, and the lesson worth keeping

Six of eight independent reviews returned changes required — seven major and thirteen minor findings, all
fixed. Two were serious, and both lived in code whose own full suite passed.

A discovery parent never recorded installed visibility, so any plan carrying a discovery source produced no
outcome at all and a style waiting on that owner parked forever. Install and discovery readiness had been
mutually exclusive terminals, yet such a parent needs both. Two reviewers found it independently from
different symptoms. And arbitration closed a parked sprite member while its group owner still had work in
flight, after which the group wrote into a resolved route and the reducer threw; the existing trace missed
it only because it placed the unrelated route at a lower ordinal.

Most of the rest were the same shape: values whose constructors accepted self-contradictory combinations the
reducer never produced. **When a reducer's own state type is the boundary that makes an illegal state
impossible, write the invariant into the type, not only into the paths that build it.**

### The five open decisions, resolved

All five were worked on 2026-08-18. Four are closed in code or documentation; one is deliberately deferred.

1. **Transport response copy amplification — partly fixed.** The original note undercounted its own
   arithmetic: a successful 200 made **seven** copies, not four to five (1.75 GB ÷ 256 MiB is exactly 7),
   and two paths went unmentioned — the `304` merge and the Store read, the latter running on every warm
   frame. `TransportResponse` and `StoredRawResource` now expose internal non-copying snapshot accessors
   used where the array is only measured or fed straight into a copying constructor, and the redundant
   latch and `copyStored` re-copies are gone. Both classes are `final` over private snapshots with
   copying public getters, so no aliasing escapes and the public copy-on-read contract is untouched.
   **Still open, and deliberately:** letting RenG retain the consumer's own `TransportResponse` instead of
   its own copy would remove two further copies, but ADR 0016 says the outcome is latched as a
   *defensively copied* response, and retaining the consumer's object makes identity observable through
   `===`. That is an ADR 0016 amendment, not a cleanup.
2. **`ShaderProfilePlan` validates nothing — fixed.** The type now carries five `init` invariants relating
   its declared span to the actual source: ascending in-range span, span starts and ends a physical line,
   and the trimmed span is exactly the accepted directive. Bounds are checked before the trim comparison,
   which indexes the span. Seven contradictory constructions are now rejected with
   `IllegalArgumentException`, including the reported `#version 330 core#version 300 es` case and a
   descending span that previously duplicated text silently. Messages carry no source text.
3. **Scheduling cost — measured, not changed, by decision.** The original note named the wrong cause.
   `startNotYetStartedRoutes` sorting every route is real but nearly irrelevant. The actual shape is a
   **Θ(routes + occurrences) floor paid by every event** — the reducer rebuilds all derived indexes,
   re-copies every route record and re-validates the whole state per transition — times roughly nine
   events per route, giving Θ(events × (routes + occurrences)). Two further costs compound it: the
   style-owner barrier is O(owners × occurrences), which bites because a 256-frame batch binds all 256
   owners to one `StyleGroupId`; and `OwnerResourceSet` calls `toSet()` on every transition while
   `StoredRawResource.hashCode()` does a full `contentHashCode()` byte scan, so **every event re-hashes the
   complete payload of every already-installed resource** — on the order of 100 GB per frame at 512 tiles
   of 50 KB.

   **The per-event rebuild floor above is now measured directly; the style-owner barrier and full-payload
   re-hashing costs above remain extrapolations.** `ResourceOperationScaleBenchmarkTest` drives many
   *distinct* sticker routes (one occurrence each, no joining) through `ResourceOperationStateMachine.start`
   / `beginLookup` / `transition` to a real `ResourceOperationOutcome.Success`, on the always-succeeds path,
   and timed it on Apple Silicon macOS via
   `./gradlew --no-configuration-cache --rerun-tasks :kmp:macosArm64Test --tests
   "com.rohittp.reng.internal.resource.ResourceOperationScaleBenchmarkTest"`. Three independent runs:

   | routes | run 1 | run 2 | run 3 |
   |---|---|---|---|
   | 64  | 503 ms   | 498 ms   | 501 ms   |
   | 128 | 1838 ms  | 1724 ms  | 1705 ms  |
   | 256 | 7274 ms  | 6975 ms  | 6824 ms  |
   | 512 | 33622 ms | 29052 ms | 29610 ms |

   Successive-doubling ratios sit at roughly 3.5–3.7, 4.0–4.1, and 4.2–4.6 — at or above the quadratic
   signature (ratio 4), not the linear one (ratio 2, which the existing 4096-occurrence-on-one-route test
   already shows for the joined case).

   **This measures the base rebuild floor alone, not the other two costs named above.** Every route in this
   benchmark takes a unique `ResourceOwnerId`, so `OwnerResourceSet` never holds more than one element and
   the O(owners × occurrences) style-owner barrier above is never exercised; every occurrence uses
   `ResourceCommitBinding.Single`, so the shared style/sprite group path is never taken either; and the
   transport response body is four bytes, not the ~50 KB-per-tile payload the re-hashing cost above
   assumes. So the table measures only the reducer's per-event O(routes + occurrences) rebuild, at roughly
   nine events per route, compounding into effectively **O(routes²) or worse** for this scenario — not the
   owner barrier, not the re-hashing cost, and not their combination. A real mixed frame paying those two
   costs as well would cost more than the table shows, not less.

   **This is not acceptable at the shipped default 512-tile budget, and if anything the verdict is
   conservative** given it excludes two costs that only add to it: 29–34 seconds of scheduler CPU before a
   single byte is decoded, per frame, is not a viable production number on the rebuild floor alone, and the
   4096 maximum configuration is minutes. The benchmark's guard asserts the 512-route case stays under 50
   real seconds — anchored to the worst of the three observed runs with ~49% headroom for slower CI
   hardware, not for a further regression: extrapolating the 256-route worst run (7274ms) at a cubic rather
   than the observed near-quadratic doubling ratio (8x instead of ~4.2-4.6x) predicts ~58192ms for 512
   routes, which this ceiling still catches. A future change that makes this meaningfully worse fails a
   real test rather than shipping silently. None of this was observable in Cycle B, which has no factory
   and no public runtime API. **Cycle C's resource driver must fix this scheduling cost before or alongside
   connecting real adapters** — the benchmark above is the measurement any such fix must move, and the
   guard is what proves it worked. The cheapest real win for the re-hashing cost is caching
   `StoredRawResource`'s hash at construction; the honest fix for the floor measured here is the per-event
   rebuild itself — Cycle C needs to address both, since they are separate costs.

   **Update (2026-08-22): none of that happened, and the cost shipped.** Cycle C did not fix the
   scheduling cost, `0.2.0` carries it, and the basemap cycle did not fix it either. What changed is the
   guard: the 50-second wall-clock ceiling described above was calibrated on a developer M3 Max and a
   hosted runner could not meet it, which is what blocked the first `0.2.0` push. `c67349a` replaced it
   with an assertion on the machine-independent scaling ratio — 6.0× on the largest pair, between the
   quadratic signature and the cubic one — and dropped the top route count from 512 to 256 for roughly a
   quarter of the wall-clock cost per build. Every measurement is still printed, because those numbers are
   what the eventual optimisation has to beat. Both remedies named above are still unstarted.

   A 2026-08-19 harness review found the benchmark's own driver had contributed a second, avoidable
   O(routes²) cost stacked on top of the reducer's: `advanceAllPendingClassGates` re-scanned the full route
   list after every action rather than tracking which route needed advancing next. That scan is gone — the
   driver now names the ordinal directly off the `CallTransport` action that parks it, in O(1), which this
   benchmark's fixed always-succeeds event mapping makes exact rather than approximate. Re-measuring after
   the fix reproduced doubling ratios in the same at-or-above-quadratic range reported above, confirming
   the reducer's own per-event rebuild — not the benchmark's driving loop — produced the numbers in the
   table.
4. **Advancement events — fixed in the plan.** `AdvancePendingClassGates` appeared three times in the plan
   and `AdvancePendingSpriteCommit` and `AdvancePendingStyleCommit` zero times, though the reducer
   implements all three with identical preconditions. Tasks 14B and 14C now specify theirs, including the
   start-ceiling behaviour that makes a ceiling-prohibited sprite member stage no candidate.
5. **Two unkillable guards — kept and now documented in code.** The buffered-outcome guard in
   `successOutcome` carries a comment explaining that outcomes buffer only at or above
   `nextRetirementOrdinal`, so the preceding retirement check already implies it. Neither guard had any
   comment before, so a future reader would reasonably have deleted them as dead code.

## Cycle C — resource layer, as released in `0.2.0`

Acquisition through the consumer's adapters, proxying basemap resources to Rentile and fetching RenG's own;
PNG decode; GLB parse; the content-keyed cache with refcounted lifetime across live prepared frames; the
reload-on-access path; cancellation of everything in flight. Cycle B already decided every pure action this
cycle executes, so Cycle C connects real observations rather than inventing policy.

**Cycle C's plan has 21 tasks, and its scope shrank after the plan was approved.** The owner reordered the
cycles so a resource-layer MVP can ship for waiting consumers: six tasks — 14 (sprite pair and basemap
style commits), 16 (the production Rentile private-key resolver), 17 (the firewall transport/store
adapters), 18 (engine failure classification), 19 (the basemap rasterizer host, style compilation, and
rendered-tile identity), and 20 (terrain acquisition and encoding validation) — turned out to be basemap
work and now travel with Cycle E instead of Cycle C. **Shipped, and released in `0.2.0`:** tasks 1–13 and 15 — the coroutines dependency and Task 2's five-declaration public
surface growth; the inflate/CRC-32 seam; the PNG container walk, CRC validation, and unfiltering into
canonical unpremultiplied RGBA8; strict UTF-8 and a hand-written JSON reader; the GLB container scan, the
glTF document parser, and the `PARSE_GLB`/`VALIDATE_GLB_FEATURES` gates; the resident cache (generations,
leases, reload markers); the resource driver's class gates, Store writes, and visibility installs; and
cancellation propagated unwrapped through the driver.

**As released, Cycle C called no Rentile adapter, decoded no basemap tile, and drew no pixel.** The
basemap cycle closed all of that; what follows is the state `0.2.0` shipped, kept because the gate story
changed shape rather than merely advancing.

**The throwing gate/class combinations are gone, and the count was never six.** This document previously
said "the six Rentile-firewall-validated combinations … call `error(...)` rather than rubber-stamping
`Valid`". Two things were wrong with that even at the time: the enumerated list — `PARSE_TILEJSON`,
`DECODE_VECTOR_TILE`, `PARSE_GEOJSON`, and `DECODE_PNG` over `BASEMAP_RASTER_TILE` and `BASEMAP_DEM_TILE` —
is **five** combinations, not six, and it is now **zero**. The basemap cycle deleted them outright rather
than implementing them (commit `924f557`, "delete the class gates the Rentile engine owns"), because the
engine acquires and validates those classes itself through RenG's firewall and RenG's driver never routes
one. Today `ResourceClassGate` has exactly three constants — `DECODE_PNG`, `PARSE_GLB`,
`VALIDATE_GLB_FEATURES`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt:614-618`) —
`ordinaryResourceClassGates` returns `null` for the seven engine-acquired classes plus `BASEMAP_STYLE`
(same file, `:640-657`), and `RenGClassGateRunner`'s `when` is total over three gates with no `error(...)`
branch. The former `VALIDATE_DEM_TERRAIN_ENCODING` gate is gone too; its check survives as
`validatesDemTerrainEncoding` in `internal/driver/ClassGateRunner.kt`, kept `internal` and called from the
firewall's write path, where ADR 0016 puts the obligation. RenG's own `ResourceClass` is still eleven
constants.

RenG drawing no map text is not new to this cycle: Rentile
itself deliberately draws none baked into a tile (see
`docs/research/2026-08-18-rentile-label-primitives-request.md`), and RenG asked Rentile for a label
primitive so it could draw text as its own billboards later rather than accept it baked into the ground
texture. That request landed in Rentile 0.3.0 as `ResourceClass.GLYPH_RANGE` behind a new
`acquireLabelCandidates` entry point — see the erratum below — but Cycle C does not call it and consumes no
label data.

**Verified at the time, execution-verified on macOS with `--rerun-tasks`** (these are Cycle C's own
figures, superseded as suite totals by the basemap section below): `checkKotlinAbi` clean — the ABI diff
against `main` was exactly Task 2's five declarations
(`PipelineStage.BASEMAP_RENDER`, `RenGErrorCode.BASEMAP_RENDER_FAILED`, `ResourceKind.BASEMAP_TILE`,
`ResourceLimits.maximumDecodedImageBytes`, `ResourceLimits.maximumModelJsonChunkBytes`) plus their
mechanical `ResourceLimits` constructor/`copy`/`component9`/`component10` fallout — no Rentile type,
platform binding, or renderer factory anywhere in the dump; `testAndroidHostTest` 542 tests, 0 failures;
`macosArm64Test` 538 tests, 0 failures (matching the counts this task was asked to check against, so
nothing regressed); `compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`, `compileKotlinLinuxX64`,
`compileKotlinLinuxArm64`, and `bundleAndroidMainAar` all succeed; all seven publications resolve into a
local repository; a fresh-Gradle-home, `--refresh-dependencies` consumer-smoke build resolves and compiles
all six published targets with no credentials. 81/81 Python tests and the repository policy check both
pass. **Not run and not claimed:** `linuxX64Test` — Linux CI coverage, not a macOS-local gate.

### What five adversarial passes over the PNG path found, and the lesson worth keeping

PNG decode is RenG's primary hostile-input surface — Rentile hands back encoded tile bytes even for vector
basemaps, and stickers and model textures are consumer-supplied. It took five separate review/audit passes
to get this path clean, and every single pass found something:

1. The implementer self-reported an uncaught `ArrayIndexOutOfBoundsException` on an out-of-range palette
   index.
2. The reviewer found, unprompted, that a declared width/height at or past `2^31` sign-extends through
   signed 32-bit arithmetic, silently bypassing the size ceiling before an allocation throws
   `NegativeArraySizeException` — a crafted file of a few dozen bytes crashing the decoder.
3. A dedicated adversarial audit found the *ceiling check itself* overflows: `width * height * 4` as a
   `Long` product can wrap past `Long.MAX_VALUE` even after both dimensions are individually bounded,
   defeating the ceiling either loudly (a crash) or silently (an allocation roughly 3x the configured
   limit, with no rejection at all).
4. A second, independent audit pass found the fix for (3) was sound but two chunk-ordering rules were
   still missing: a duplicate `tRNS` chunk resolved by last-write-wins, and a `PLTE` chunk accepted after
   `IDAT` with no ordering check.
5. Building the fuzz harness (below) surfaced two more silent-wrong-result gaps that 300,000 random inputs
   could not find, because a "never throws" property is blind to bugs that decode without complaint but
   produce the wrong pixels.

Four crash classes and two silent-wrong-result gaps, six defects total, found by four different routes
(self-report, reviewer, dedicated audit, fuzz-adjacent inspection) — no single method found everything.
`PngDecoder.kt`/`PngContainer.kt` now carry `PngFuzzTest`, a deterministic 300,000-input property test (six
valid seeds × 50,000 mutations, a from-scratch fixed-seed xorshift32, twelve structured mutation kinds
including IHDR dimension corruption with the CRC recomputed so mutations reach deep validation rather than
dying at the checksum) asserting `decodePng` never throws for any byte sequence, on a realistic 1 MiB
ceiling. It runs in under half a second on both hosts that execute tests. The property is permanent
regression coverage for all four crash classes; it does not, by construction, cover the two silent
wrong-result gaps, which have their own dedicated fixtures instead. The base rate of one new defect per
pass did not visibly decline until the fifth pass — worth remembering before declaring any single hostile-
input surface "clean" after one review.

### GLB feature subset — corpus-checked, unchanged (see the 2026-08-22 erratum)

ADR 0021's supported/rejected subset was reasoned from the glTF 2.0 specification, not measured against a
corpus. `docs/research/2026-08-19-glb-feature-subset-corpus-check.md` records that check: 118 `.glb` files
from `KhronosGroup/glTF-Sample-Assets` run through the real, unmodified `scanGlb`/`parseGltf`/
`validateGltfFeatures` pipeline. 52 supported; every rejection (JPEG textures, required extensions, skin
attributes, a `KHR_animation_pointer` target, morph targets, `CUBICSPLINE` interpolation) traces to a
decision ADR 0021 already makes deliberately. No row moved from reject to accept, so the ADR is unchanged.

**Corrected by an erratum appended to that same document on 2026-08-22.** The upstream corpus is now
**119** files, not 118, and a re-run over all 119 reports **53** supported — one more accepted file,
no row moved. The re-run was performed twice, before and after the accessor-constraint tightening on
`feat/f2-glb-validation` (`24f36f0`, fifteen new guards across both gates), and **zero files changed
verdict**, so 53 holds for both the released pipeline and the tightened one. That branch is merged, and
its ADR was renumbered to `0028-constrain-glb-accessors-per-role.md` on the way in: it had claimed `0025`,
which the basemap cycle had already taken for the coplanar-content ruling.

### Erratum on Rentile's resource-class count — owed then, written now

Rentile `0.3.0` — now published — adds a ninth `ResourceClass`, `GLYPH_RANGE`, reachable only through a new
`acquireLabelCandidates` entry point that Rentile's `prepare`/`prepareBatch`/`render` never touch. ADR 0016
and `docs/superpowers/specs/2026-08-18-cycle-c-resource-layer-design.md` both say "eight basemap classes"
and now understate the real count by one. **Neither is edited here** — both are decision records, and the
undercount is a fact about the world changing after they were written, not an error in what they decided.
The measurement is at `docs/research/2026-08-19-rentile-030-counting-stub-respike.md`: style still has no
raw-store write; TileJSON/vector/raster/GeoJSON validate-then-write; DEM writes after only generic image
validation; sprite JSON and PNG write before joint validation and a digest-consistent-but-invalid sprite
record is terminal;
store reads are unbounded against a transport bounded at peak 6 of 8 — every ADR 0016 claim holds unchanged
at `0.3.0`. `GLYPH_RANGE` itself: accept is a third non-null value (`application/x-protobuf`), it writes
before decode validation (DEM-like), and it recovers from corruption via remove-then-refetch rather than
going terminal (unlike sprite). **The erratum is no longer owed: the basemap cycle's Task 0 appended it to
both documents**, so ADR 0016 and the Cycle C design spec each now carry an explicit note that the count is
nine and that `GLYPH_RANGE` stays outside the firewall because RenG does not call
`acquireLabelCandidates`. The tree still pins rentile `0.2.0` (bumped in commit `c9b6e0a`, moving the
three coupled version references documented below); bumping further to `0.3.0` is a separate decision that
needs this respike's evidence, not a reflex, and `GLYPH_RANGE` is deliberately out of Cycle C's scope
either way — RenG does not call `acquireLabelCandidates` and draws no label primitives yet. When ADR 0016
and the design spec are next revised, correcting "eight" to "nine" (RenG's own `ResourceClass` stays at
eleven; `CONTEXT.md` already says "eleven" and needs no change) is the owed fix.

**A version now lives in three coupled places**, discovered while bumping to `0.2.0`: `gradle/libs.versions.toml`,
the two `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` whole-file SHA-256 hashes, and a `base_versions` literal
inside `tools/check_repository_policy.py`'s `_dependency_name_policy_token`. All three must move together
in one commit or `check_repository_policy.py` fails closed.

### Two duplications from a decomposition error, resolved at merge

Splitting Cycle C across worktrees for parallelism put a dependency (Task 10's cache) on a different branch
than its consumer (Task 12's driver), and separately put the canonical GLB parser out of reach of Task 13's
class-gate wiring. Both produced a stand-in implementation that had to be discarded rather than merged
alongside the real one. At the `feat/cycle-c-glb` merge (commit `d5dd1ab`): the canonical, reference-identity
`ResidentCache` (full lease/generation/reload-marker contract, independently reviewed) won over the
stand-in built only to satisfy `PreparationDriver`'s constructor, with the atomicity fix
(`installAndTakeLease`/`observeAndTakeLease`) re-expressed onto the canonical model; and Task 13's narrowed
GLB/DEM class-gate checks were replaced by the canonical `scanGlb`→`parseGltf`→`validateGltfFeatures`
pipeline. The merge surfaced one real defect in the process — a `ClassGateRunnerTest` fixture asserting a
scene-less GLB as valid, which the canonical validator correctly rejects as `SCENE_AMBIGUOUS` — fixed by
correcting the fixture, not the assertion.

### The scheduler cost is measured, and it is worse than the Cycle B extrapolation

Carried forward from the Cycle B handoff's ~5-second extrapolation at the shipped default 512-tile budget:
Task 11 built `ResourceOperationScaleBenchmarkTest`, driving distinct sticker routes to real `Success`
outcomes, and measured (three clean runs, quiet host): **64 routes ≈ 500ms, 128 ≈ 1750ms, 256 ≈ 7000ms, 512
≈ 30000ms** — doubling ratios of 3.5–4.3x, at or above quadratic, not linear. A single frame at the shipped
default 512-tile budget costs roughly **thirty seconds** of pure CPU in the pure reducer alone, before any
byte is decoded or any pixel drawn — about 6x the prior extrapolation. This is a **floor-only** measurement:
every route used a unique owner and a single-occurrence binding, so the O(owners × occurrences) style-owner
barrier that Cycle B's handoff also names is never exercised, and the payload was 4 bytes against the ~50KB
content-rehashing estimate — the true worst case is likely higher, not equal. The optimisation itself is
**not** a Cycle C task and none was added; measuring before optimising is the gate this cycle's design spec
sets, and only the measurement half is done. A wall-clock guard of 50 seconds was chosen from this data
(~49% headroom over the worst clean run; a contention run under unrelated concurrent load hit 184 seconds,
which is a real CI-flakiness warning for a shared or busy runner, not part of the baseline).

**Resolved at integration:** `ResourceOperationScaleBenchmarkTest.kt` and this measurement were produced on
the separate `feat/cycle-c-bench` worktree/branch (commits `85e2660`, `4b7b1ba`, `8be3e8b`), which —
unlike `feat/cycle-c-glb` — was **not merged** into `feat/cycle-c-resource-layer` until the Cycle C/D
gathering merge that brought this branch and `feat/cycle-d-gl-foundation` together in one tree. The 542/538
counts recorded earlier in this document predate that merge and do not include this test; the combined
verification the gathering merge performed does, and reconciles the new total against these parts.

### Cancellation: one gap, correctly scoped to block the MVP rather than Cycle C

Task 15 wired cancellation through the driver, propagating an unsolicited `CancellationException` unwrapped
rather than letting structured concurrency silently absorb it (a real defect the implementer caught and
fixed mid-task). One gap remains: `ResourceActionExecutor` has no `CancelRoute` handler, so a multi-route
operation where one route observes an adapter cancellation while sibling routes are still active would
crash there. This does **not** block Cycle C — `Renderer` is a bare sealed interface with no concrete
implementation anywhere in the tree, so `Renderer.cancelPreparations()` is unreachable public ABI text, not
a path a consumer can hit. It **did** block the MVP, and it was fixed there: Cycle F-1's first task landed
`CancelRoute` handling ahead of the factory itself (`d7a96ff`), and `ResourceActionExecutor.kt:140` now maps
it to `CleanupCancellationObserved`. The gap is closed; the entry is kept for the shape of the reasoning —
unreachable public ABI text is not a licence to leave a crash in place once something can reach it.

### Pre-implementation spike findings, still accurate

The rest of this section is background from before Cycle C was implemented. Its two open questions were
settled, and a third emerged, and all three findings held up through implementation.

**PNG decode — `docs/research/2026-08-18-cycle-c-png-decode.md`.** Own the container in common Kotlin and
delegate only decompression and checksum to the platform: the bundled `zlib` binding on the five native
targets, `java.util.zip` on Android. A spike walking chunks, validating checksums, streaming across split
image data and unfiltering produced byte-exact output on a Linux host and as an emulated aarch64 binary, and
the same source compiles for the Apple targets. Skia is rejected on measured behaviour, not weight: it
silently truncates sixteen-bit grayscale to eight bits, silently accepts a stream truncated mid-image-data
with no end marker, collapses a checksum mismatch and a corrupt payload into one opaque message, and is not
faster. Repairing malformed input contradicts RenG's contract outright. Note also that Rentile returns
rendered tiles as encoded bytes, so RenG decodes PNG even for vector basemaps, and both of its terrain
encodings are eight-bit, so sixteen-bit support is a choice rather than a requirement.

**GLB parse — `docs/research/2026-08-18-cycle-c-glb-parse.md`.** The accept and reject table is written
down rather than left to discovery, and a pure-Kotlin container reader classified forty-one deliberately
malformed fixtures exactly as intended. Two consequences bind the design: padding is not verifiable at the
container layer, because a chunk's declared length includes its padding, so a padding policy is an explicit
choice; and parsing must tolerate an accessor without a buffer view so that the feature gate reports an
unsupported feature rather than the parse gate reporting malformed input — a compressed model is
unsupported, not corrupt, and Cycle B's two gates already model that distinction.

**Cycle C needs its own JSON reader, and this is the finding that was not anticipated.** The serialization
library is already resolved transitively but is compile-visible only on the native targets, and the
repository policy forbids declaring it directly. A reader is therefore required rather than chosen. The
spike's version is the feasibility proof, and measured allocation behaviour shows the JSON chunk needs its
own ceiling separate from the model byte limit, since a chunk at that limit would demand several gigabytes.

**Rentile's real surface — `docs/research/2026-08-18-cycle-c-rentile-surface.md`.** Read this before
writing the adapter; it was taken from the published artifact because the source tree is not on this
machine. The adapter must absorb a `remove` that RenG's own store does not have, because Rentile removes a
stored record on digest mismatch or parse failure. Rentile's raw key is a hash of the URL with
authentication query parameters redacted, and that derivation is what the real private-key resolver must
reproduce — Cycle B wired a deterministic fake. Rentile always sends null conditional headers, so every
conditional and accept decision is RenG's. Its response metadata carries nine fields against RenG's four.
Its retry is bounded to one extra call on specific statuses with a clamped delay, and it rethrows
cancellation unchanged. Credential and session providers are never invoked in `0.1.5`. And
`RawResourceKey.toString()` prints its identifier in the clear, so a Rentile key must never reach a RenG
diagnostic.

What the artifact cannot show is behaviour: threading, actual exchange counts, per-class store ordering,
revalidation, cancellation depth. The document prescribes a counting-stub spike against a real Rentile
call to close those before the specification is final.

## Cycle D — GL foundation, as released in `0.2.0`

**Released in `0.2.0`.** Every plan task is complete and independently reviewed with its
findings fixed, including the real-context conformance suite. It delivers the internal GL seam (eighty-four
entry points typed at Android's width when Cycle D closed; the seam is **87** entries at `main` and on the
basemap branch, having grown through F-1 and the ground draw — count `GlEntryPoint`, do not quote this
number) and four platform implementations — one each in `iosMain`,
`macosMain`, `linuxMain`, `androidMain`, per ADR 0022's measured source-set visibility rather than ADR
0009's original claim; runtime shading-language dialect detection off `GL_SHADING_LANGUAGE_VERSION` alone,
never the target platform; the offscreen colour-and-depth surface and its composite pass; the corrected GL
restore set (ADR 0023, superseding ADR 0006); shader compilation with version-directive substitution and a
program cache; the lifecycle driver that feeds real GL facts to Cycle B's pure reducer, unmodified; and the
GL conformance suite. As before, this adds no public ABI, no renderer factory, no resource acquisition, no
Rentile call, no decoder or parser, and no frame content.

Read `docs/research/2026-08-18-cycle-d-gl-foundation.md`, `docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md`,
and `docs/research/2026-08-19-mesa-bug-report-draft.md` — three things the plan got right stand as measured
(dialect keyed on the runtime query, the restore set's corrections, the eighty-four-entry binding
inventory), and two things were discovered only during implementation and are recorded below because they
were not anticipated by any of the cycle's research.

**A Mesa driver defect forced a Linux-wide skip of one deliberate negative check.** Mesa 25.2.8 SIGSEGVs
inside `libgallium` during `glLinkProgram` whenever a process holds two or more EGL contexts with at least
one GLES-profile context and performs a cross-`#version`-dialect link — order-independent, reproducible
from a RenG-free C program, and absent on Mesa 23.2.1. It is a driver defect, not RenG's: production code
never performs that link, only the conformance suite's deliberate "a mismatched `#version` must fail"
check does. The owner accepted skipping that one check on Linux, for every dialect and every driver,
through an internal `CrossDialectLinkPolicy` defaulting to `EXERCISE_LINK` with only
`LinuxGlConformanceTest` opting into the bypass — measured at 0/20 crashes, down from 15/15. A ready-to-file
upstream bug report exists at `docs/research/2026-08-19-mesa-bug-report-draft.md` but has **not** been
filed.

**The macOS CGL fixture is now the sole real proof that `#version` substitution is load-bearing.** Apple's
driver reports `4.1 Metal - 90.5` and does not advertise `GL_ARB_ES3_compatibility`, so on real Apple
silicon the cross-dialect negative check runs unskipped and passed. If that fixture ever stops exercising
the link — a refactor that widens its capability gate, for instance — the substitution claim goes back to
unproven everywhere, since Linux now skips its own copy of the same check. Whoever touches
`GlConformanceSuite.kt` or the macOS fixture should know this dependency exists before they change either.

**Two defects surfaced only by driving the lifecycle machine were found and fixed.** Restructuring
`applyTerminal` into a `when` over `RendererOwnerState` with no `else` branch — so a future added state is a
compile error rather than a silent fallthrough — surfaced a latent bug the old fallback had been hiding:
`profile` was not being nulled alongside `adoptedContext` on GPU-object loss, which would have let a stale
context's dialect leak into a decision made after that context was gone. Both the restructure and the fix
landed together. Separately, `GlProgramCache`'s cache key deliberately omits the shader dialect; that is
safe only because `forgetWithoutDeleting()` calls both `GlObjectRegistry.forgetEverything()` and
`GlProgramCache.forgetAll()` on every context adoption, every GPU-object-loss event, and renderer close. A
reviewer traced the state machine and confirmed those three call sites are exhaustive. Anyone adding a
fourth transition that can leave an adopted context behind must add the same invalidation or the cache will
serve a program compiled under one dialect to a context running another.

**What was verified where.** The conformance suite ran for real against llvmpipe (surfaceless EGL, both ES
3.2 and desktop 4.5 core) and against a real CGL core-profile context on Apple silicon (`Apple M3 Max`, `4.1
Metal - 90.5`) — both real-driver runs, both outside the ordinary `:kmp:linuxX64Test`/`:kmp:macosArm64Test`
Gradle invocation in the Linux case, since the Linux run that found the Mesa defect was an opportunistic
Docker verification rather than a hosted CI run. The one thing not verified anywhere yet is the hosted
runner's no-GPU fallback (`Apple Software Renderer` on macOS when acceleration is not requested) — every
local run so far has had a real GPU.

The seam's central design problem from the research phase — one interface implementable by pointer-based
Kotlin/Native sides and Android's JVM-array-based `GLES30` — is resolved as shipped: `Int` names and enums,
`Boolean`/`BooleanArray`, `IntArray`, `FloatArray`, `Int` buffer sizes, `ByteArray?` payloads, and one
`String` shader source, because Android exposes exactly `glShaderSource(int, String)` with no count or
length array.

## E-basemap, as implemented

**Complete and gathered on `feat/cycle-e-basemap`, ahead of `main`, not merged and not released.** Its
authority is `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md` and
`docs/superpowers/plans/2026-08-20-cycle-e-basemap.md`. The per-task ledger —
`.superpowers/sdd/2026-08-20-cycle-e-basemap/progress.md` — records every dispatch, review verdict, fix
round, mid-cycle ruling and spike summary, and is worth reading before touching anything this cycle built.
Each task ran on its own worktree and branch and was gathered by merge; `git worktree list` still shows them.

**What it ships.** The Rentile firewall and its production private-key resolver, in
`internal/firewall/` — `FirewallTransport`, `FirewallStore`, `OperationRegistry`, `RentileKeyDerivation`,
`EngineFailureClassification`, `BasemapEngineHost`. One basemap engine per renderer, with rendered-tile
identity derived by RenG's own `basemapTileKey` under ADR 0018 rather than by a structural triple match.
Sprite-pair and basemap-style commits, with a rendezvous on the firewall write path so a half-written
sprite pair cannot poison a record. Pure basemap route derivation from a real compiled style, with an
underivable source *deferred* rather than the whole style rejected. Style compilation bound to the exact
bytes the frame is committing, and a refusal when a compiled style's bytes are not the resident ones. The
frame's style manifest parsed once and cached by content digest instead of twice. Tiles rendered through
the engine the frame compiled, decoded and uploaded at draw rather than at prepare, so a tile whose GL
texture is still resident costs neither. Sources that declare their tiles **by reference** through a
TileJSON document, which turned out not to be an edge case at all: across the owner's 34-style corpus, 96
sources use the reference form against 2 inline, and all 34 need at least one — before that landed, the
ground drew for none of them. And the ground itself, in `internal/gl/GroundPipeline.kt`, with each
instance's unwrapped x read so N Mercator world copies land in N places while sharing one texture.

**Public API growth is exactly one field**: `ResourceLimits.maximumResidentGpuTextureBytes`, appended last
so no `componentN` shifted, defaulting to 128 MiB (a canonical 512×512×4 tile is exactly 1 MiB). It was
**inert for most of the cycle** — `RendererFactory` constructed `GlObjectRegistry()` with the class default,
so the public knob had no effect whatsoever — and the ground-draw task found it while wiring texture
leases. It is now threaded from the caller's configuration at `RendererFactory.kt:115`. A public field
whose only test constructs the registry directly is a field with no wiring; that is the shape to watch for.

**Two live defects surfaced on the way to the first drawn pixel**, both in code whose own suite passed.
`drawFrame` captured and restored only texture unit 0 while `drawGeometry` binds up to 15, leaving units
1..14 clobbered — an ADR 0006/0023 restore-set violation inside the very mechanism those ADRs exist to
specify; it now captures `FRAME_TEXTURE_UNIT_COUNT`. And the coplanar-ground problem behind ADR 0025: with
the shipped strict `GL_GREATER` depth comparison, every altitude-0 map-anchored thing became invisible the
moment a ground existed at altitude 0, with the draw call issued and the pixels simply never written.
`GL_GEQUAL` plus a fixed map-regime draw order fixes it, and `StickerPipeline`'s KDoc claim that
map-anchored things draw "in any order" was false from that point and has been replaced rather than left
standing.

**The gate is analytical readback, with no stored baselines** — 128×128 through the public API
(`createRenderer` → `prepare` → `draw`), the whole frame read back, and relationships asserted: no interior
pixel is the target's own colour, four named samples carry four fixture colours, the four quadrant means
stand in a strict order, and `drawBasemap = false` leaves the frame untouched. The camera is the asymmetric
`(-55, -135)` zoom-4 one already proved disjoint from its own transpose, and each tile's 2×2 source has
four distinct texels so a u-flip or v-flip shows a decoy. This catches the entire quiet-and-plausible class
— transposed tile index, wrong LOD, flipped texture, silently empty ground, dead `drawBasemap` flag — and
tells nobody whether the result *looks* right. That is Cycle J's job, and **all pixel verification is
deferred to Cycle J by owner decision**
(`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`).

**Verified where, and only where.** Last measured in this checkout: **903 Android host / 936 `macosArm64`,
no failures, none skipped**. Basemap rendering itself is exercised on `macosArm64Test` and `linuxX64Test`
only, and that is measured rather than scheduled: those are the only two test tasks that can hold a GL
context, and **Android's host tests cannot execute Rentile's Skia rasterization at all**, which was
established with error codes rather than assumed. All six targets still publish at every release; which of
them anyone has executed belongs in release notes.

**Not in this cycle, and not implemented:** terrain (Cycle C's task 20 travels to E-terrain), map labels,
models with textures and animation, the globe projection, and golden images.

**Owed before or at merge.**

- **Merging to `main` publishes.** `publish.yml` runs on every non-documentation push to `main` and the
  resolver will advance to `0.2.1`. Decide the version deliberately in the merge commit if `0.2.1` is not
  what is wanted.
- **The Rentile pin is `0.2.0` and the measured recommendation is to move to `0.4.0` now**, as its own
  commit, after integration review closes and before the next cycle opens. The upgrade spike SHA-compared
  all 21 Rentile files RenG reproduces or depends on plus six named private functions and found them
  byte-identical; the ABI diff is exactly two removed lines (Rentile's own `ResourceLimits` constructor and
  `copy` going 14 → 16 arguments, both fields appended, and RenG never constructs it); `RentileErrorCode` is
  unchanged, so `EngineFailureClassification`'s `else`-less `when` still compiles. Two real breaks: one
  test that asserts no engine class maps to `null`, which `GLYPH_RANGE` now does — and the test's own
  message prescribes the fix — and the three coupled version places below. The argument for doing it now is
  that the expensive part of an upgrade is re-proving the byte-for-byte reproduction, that proof is done
  and clean, and it is perishable.
- **A version lives in three coupled places** and all three must move in one commit or
  `check_repository_policy.py` fails closed: `gradle/libs.versions.toml`, the two
  `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` whole-file SHA-256 hashes, and a `base_versions` literal inside
  `tools/check_repository_policy.py`'s `_dependency_name_policy_token`.
- **An ADR number collided, and is resolved.** `feat/f2-glb-validation` claimed
  `docs/adr/0025-constrain-glb-accessors-per-role.md`, which the basemap cycle had already taken. It merged
  as `0028`. Worth knowing for next time: two branches numbering an ADR in parallel is the normal outcome
  of parallel work, and the number is cheap to move only until something cites it.
- **The served documentation site is actively wrong.** `docs/index.html:59` says RenG "still exposes no
  runtime API or" rendering, `docs/index.html:118` says "Cycle A renders nothing", and `docs/kmp.html:59`
  says the same. `0.2.0` is public, `createRenderer` is public API, and RenG draws. Those are HTML, not
  Markdown, and were left untouched by this pass; they should be corrected before anyone is pointed at
  `https://rohittp.com/reng/`.
- **The scheduler cost is still unfixed.** The measurement below stands; what changed is the guard.
  `c67349a` replaced the 50-second wall-clock ceiling — which blocked the first `0.2.0` push from a hosted
  runner — with an assertion on the machine-independent scaling ratio (6.0× on the largest pair, between
  quadratic and cubic), and dropped the top route count from 512 to 256. Every measurement is still
  printed, because those numbers are what the eventual optimisation has to beat.

## What remains: F-2, E-labels, E-terrain, H, G, I, J

**Order, as it stands after two reorders.** The 2026-08-19 reorder split Cycle F into **F-1** (stickers,
geometries, the renderer factory — the MVP, released in `0.2.0`) and **F-2** (models), and split Cycle E so
its basemap half could run right after the MVP. The 2026-08-22 changes split **E-labels** out of F-2 into
its own cycle and moved **H** ahead of **G**. Existing cycle letters stay bound to their existing content so
no prior reference breaks. The chain from here is:

```
E-basemap ──► release ──► F-2 models ──► release ──► E-labels ──► E-terrain ──► H platforms ──► G globe ──► I harness ──► J corpus
```

`docs/decomposition.md` carries the same order with each cycle's gates, and is the document to update if it
moves again.

**F-2 — models with textures and animation.** Four spikes have already been taken against it and all four
are in `docs/research/`. The scope is measured rather than argued: against the consumer's own 41-model
catalogue RenG accepts 23 today, 33 if extra UV and colour sets were ignored, and 40 if skinning were
supported — so ignoring `TEXCOORD_1`/`COLOR_1` is the largest win per unit of work in the cycle, skinning
needs joint matrices in a uniform buffer rather than a uniform array (joint counts reach 112, and GLSL ES
3.00 guarantees only 64 `mat4` of vertex uniforms), morph targets stay unsupported because the corpus has
zero, and one JPEG-textured model stays rejected loudly rather than motivating a JPEG decoder. **ADR 0026**
already settles the light: one directional, world-anchored light at azimuth 335° and elevation 45° with an
ambient term, chosen so model shading and future terrain hillshading agree by construction, with stickers,
geometries and the ground left unlit. A screen-positioned model is **rejected** rather than drawn wrong,
because `screenOrthographicProjection` has an all-zero z row and every vertex collapses to clip z = 0.
F-2's gate is analytical readback, not golden baselines.

**E-labels — map text as RenG's own screen-space primitives.** Rentile shipped more than RenG asked for:
`LabelCandidate` carries laid-out glyph quads, so text shaping and line breaking are Rentile's, and RenG
can reuse `decodePng` (the atlas is RGBA8, white RGB, alpha as SDF), `GlTextureUpload`, `ResidentCache`,
the screen-regime sticker path and `MercatorProjection`. **It is blocked on Rentile, architecturally.**
Glyph-range URLs are data-dependent — the range set comes from text inside decoded vector tiles, and
`text-font` can itself be a data-driven expression — so ADR 0016's exact-string preregistration
*structurally cannot* cover them. The request for a Rentile API that reports the glyph closure before
fetching it is at `docs/research/2026-08-22-rentile-glyph-closure-request.md`. New work beyond that: an SDF
shader with halo bands, viewport-wide collision and priority (ADR-worthy — policy and frame-to-frame
hysteresis are undesigned), batched quad drawing, occlusion against the 3D scene given that ADR 0024 grants
the screen regime no depth read, and resolving `LabelIconRef.imageName` without a public Rentile sprite
atlas. This is why labels are a cycle and not a task.

**E-terrain.** Displaces the mercator ground with the terrain Cycle C's deferred task 20 acquires; nothing
before it consumes elevation. Ground radiance, which Rentile evaluates from the style and hands over as a
literal, belongs with the same work. Behind models because terrain was already deferred once for having no
consumer, while models have consumers waiting.

**H — Android and iOS bring-up.** The one cycle no continuous integration can cover, and it is **now ahead
of the globe**: every cycle adds GL surface that two of the six published targets have never executed, and
that gap compounds. A draft pull request borrows macOS and Linux hardware, but not a device; Android GL
remains manual.

**G — globe projection.** The second projection mode, re-projecting mercator tiles and every placement, so
that it re-projects a complete scene rather than a partial one.

**I — macOS harness.** A consumer living in this repository under its own build, resolving the published
coordinate. It owns everything RenG refuses: creating the headless CGL context, driving a capture
framebuffer, reading back frames, and encoding MP4. Note that **a visual harness has been pulled forward**
ahead of this cycle, on the reasoning that RenG has now drawn a basemap no human has looked at and
analytical assertions prove relationships, not resemblance. It is placed in `consumer-smoke` under a
`macosArm64`-only source set — because `tools/check_repository_policy.py` permits Kotlin source in exactly
two places, `kmp/src` and `consumer-smoke` — and it writes a frame sequence, leaving assembly to `ffmpeg`;
a self-contained AVFoundation encoder stays with Cycle I. That is a placement decision, not a report:
check whether `consumer-smoke/src` has a `macosArm64` source set before assuming the harness exists.

**`FramePlan` serialization is still an unowned prerequisite for Cycle I.** The decomposition says the
harness consumes `FramePlan` JSON documents, "which means plan serialization is settled by then" — and
nothing has settled it. RenG has no serialization surface in its public ABI and no serialization dependency
or plugin anywhere in the build. Two candidate owners: a public serialization API in RenG, which adds
public surface and probably a dependency the repository policy forbids; or harness-side parsing through the
existing public constructors, which keeps RenG dependency-free but duplicates the schema. Decide before
Cycle I, and note that F-1 already fixed the shader uniform names a serialized plan would have to name.

**J — golden-image corpus.** The gate that proves RenG still draws what it drew, and the home of all pixel
verification deferred from E-basemap, F-2 and beyond. Its design has already been spiked
(`docs/research/2026-08-21-golden-image-gate-design.md`) and two findings shrink the work: **no new CI job
and no new publish step are needed**, because both workflows already run the only two tasks that can hold a
context and `publish.yml` runs `linuxX64Test` and `macosArm64Test` before the R2 preflight and upload; and
storage is *forced* rather than chosen — Base64 PNG constants in `commonTest`, with precedent at
`PngDecoderTest.kt:323-336`, because the policy checker fingerprints `kmp/build.gradle.kts` and forbids any
`.kt` outside `kmp/src`. The hard problem is attribution: **Rentile draws the basemap's content through
Skia and RenG only composites it**, so a frame baseline conflates "what RenG did with the tile" with "what
Skia produced as the tile", and a Rentile or Skia bump fails every basemap case at once, indistinguishably
from a RenG regression. The recommended answer is a separate decoded-tile gate at the firewall boundary.
Baselines also need a finer key than the platform — the reported renderer string, not the target, since a
hosted macOS runner renders through software while a developer's machine renders through Metal. Rentile's
two credential-bearing corpus gates have no RenG analogue and were deliberately not ported.

## Environment notes

Established on a fresh Linux container today, and each cost time to discover.

- **Android SDK Platform 37.0** is the package `platforms;android-37.0`, not `platforms;android-37`, and on
  older `cmdline-tools` it resolves only from the canary channel:
  `sdkmanager --sdk_root="$ANDROID_HOME" --channel=3 "platforms;android-37.0" "build-tools;37.0.0"`. Point
  untracked `local.properties` at it with `sdk.dir=…`. Without the SDK, AGP fails at configuration time and
  **every** Gradle task is blocked, including `linuxX64Test`.
- **All Kotlin/Native platform klibs, including the Apple ones, are present on a Linux host** under
  `~/.konan/kotlin-native-prebuilt-linux-x86_64-<version>/klib/platform/`. Apple bindings can therefore be
  inspected without a Mac: `klib dump-metadata <path>` (`klib contents` does not exist).
- **A real GL context is available on Linux.** Install `libegl1 libegl-mesa0 libgles2` after
  `apt-get update`, then create a surfaceless context via `EGL_PLATFORM_SURFACELESS_MESA` — no display
  server needed. Both an ES 3.2 and a 4.5 core profile context are reachable on llvmpipe.
- **Gradle reports `UP-TO-DATE` for unchanged test tasks.** A green build off cached test tasks is not
  evidence; pass `--rerun-tasks` when a run is meant to prove something.
- **The workflow parse command in `CLAUDE.md` uses the macOS Ruby 2.6 positional form**, which modern
  Psych rejects with `wrong number of arguments`. On Ruby 3 use
  `YAML.safe_load(File.read(path), aliases: true)`.
- **Borrowing Apple hardware:** push a temporary branch and open a **draft** pull request. `ci.yml` runs on
  every pull request and its `apple-publication` job covers `macosArm64Test`, both iOS targets, local
  publication and the clean six-target consumer. `publish.yml` triggers only on push to `main` or explicit
  dispatch, so a pull request cannot consume a version or reach R2. Such branches are disposable; say so in
  the pull request body.

Spike code is deliberately throwaway and lives outside the repository. The findings documents in
`docs/research/` are the durable record; if a spike needs re-running, they say what it did.

## Publication boundary

Pushing a development branch is a recovery checkpoint, not permission to merge, dispatch publication,
upload to R2, or claim a public release. Two releases are public and immutable: `0.1.0` from
`af92901b2ef045078b855a6b47533bc95aca6886` and `0.2.0` from `a2cbe6a965247f221f7e279a962b40306baac21b`.
Neither can be overwritten, deleted, reused or skipped; a partial release is recovered by an explicit
upward `VERSION_NAME` change and nothing else.

**The basemap cycle is not released.** It lives on `feat/cycle-e-basemap`, its exact merged-commit CI and
publication have not been observed because there is no merged commit, and merging it to `main` is what
starts a publication — the resolver will select `0.2.1` unless `VERSION_NAME` says otherwise in the same
commit. Do not infer that any outward gate passed without an observed workflow result.
