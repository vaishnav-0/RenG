# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

**RenG draws a basemap, and three releases are public.** `VERSION_NAME` is `0.3.0`. Anything in this file
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

**Cycle F-2 is complete, unreleased, and lives on `feat/f2-models`.** That branch is 50 commits ahead of
`origin/main` and 40 ahead of local `main`; `main` itself is `9a43b14`, ten ahead of the released
`49cc1d5`, carrying ADR 0028's per-role GLB accessor gates, the Rentile `0.5.0` bump, and F-2's spec and
plan. **Nothing of F-2 is published**, so `consumer-smoke` — which resolves the published coordinate on
purpose — still gets `0.3.0` and cannot draw a model yet.

`publish.yml` ignores only `docs/**`, `**/*.md` and `LICENSE`, so **pushing either branch as it stands cuts
a release**, and the resolver will select `0.3.1` because `0.3.0` has a valid completion record. F-2 grows
the public ABI by three enum entries, so `0.4.0` is the defensible number rather than `0.3.1`; either way,
decide it deliberately in the same push by declaring `VERSION_NAME`.

**`0.3.0` failed closed once before it published, and the cause was the runner's driver rather than
RenG.** The first attempt failed on the hosted macOS runner with `kotlin.AssertionError at null:-1` as its
entire diagnostic, and passed on every developer machine. A hosted GitHub macOS runner has no GPU: it runs
`Apple Software Renderer`, whose rasteriser drops quads reaching far outside the viewport — the shape every
ground tile has — losing the south-east tile outright and cutting the south-west one on a straight
diagonal, 3,005 of 15,876 interior pixels untouched. The suite now measures the driver instead of naming
it: `measureLargeQuadRasterisation`
(`kmp/src/nativeTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt:669`) draws the fixture's own four
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

**Basemap rendering is verified on `macosArm64Test` and `linuxX64Test` only, and that is a measured limit,
not a scheduling one.** Those are the only two test tasks that can hold a GL context. Android's host tests
are doubly excluded: no GL, and they cannot execute Rentile's Skia rasterization at all, which was measured
with error codes rather than assumed. All six targets still publish at every release; which of them anyone
has actually executed belongs in release notes rather than being discovered by an Android consumer.

**A visual harness exists, it found four defects the passing suite did not, and it is the reason `0.3.0`
draws.** It lives in `consumer-smoke/src/macosArm64Main/kotlin/com/rohittp/reng/smoke/harness/`, is invoked
as `./gradlew -p consumer-smoke runHarness -PstyleUrl=<url>`, and writes 960×540 binary PPM frames for
`ffmpeg` rather than PNG, because RenG owns a decoder and no encoder. Across all 34 styles the consumer
ships it rendered **1630 of 1632 frames**, the two losses being transport timeouts reported precisely
rather than swallowed. See "The macOS test harness" below for how it relates to Cycle I.

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

**Rentile is pinned in `gradle/libs.versions.toml`, at `0.5.0` since `0f385c7`.** Do not quote a Rentile
version from prose, including from this file; read the catalog. RenG adopts the pin and none of `0.5.0`'s
label feature: it still never calls `acquireLabelCandidates`, never enumerates `GLYPH_RANGE`, and still
passes `TileSubstitutionPolicy.Disabled`, so Rentile's new `CACHE_SUBSTITUTE_THEN_NETWORK` is unreachable.
`kmp/api/kmp.klib.api` did not move. A version lives in **five coupled places** that must move in one
commit or `check_repository_policy.py` fails closed: the catalog; *both*
`_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` entries for the catalog; `base_versions["rentile"]`; and two
fixtures in `tools/tests/test_check_repository_policy.py`. Those fingerprints are **not** whole-file
SHA-256 digests, whatever older prose and the checker's own comment say — `HANDOFF.md` gives the real
derivation and a recompute command, because recomputing the wrong thing is the trap here.

**What Cycle F-2 contains.** Its authority is `docs/superpowers/specs/2026-08-23-cycle-f2-models-design.md`,
its plan is `docs/superpowers/plans/2026-08-23-cycle-f2-models.md`, and the per-task ledger — every ruling,
every mutation observation, every measured number — is
`.superpowers/sdd/2026-08-23-cycle-f2-models/progress.md`. **Until this cycle no code in RenG had ever read
a byte of a GLB's BIN chunk**; `parseGltf` took `binChunkLength` and never the bytes. F-2 is the numeric
half: `internal/model/` (accessor decoding, node transforms, animation resolution and sampling, model
assembly), `internal/gl/ModelPipeline.kt` and `SceneLight.kt`, and the renderer arm that acquires,
decodes, uploads and draws. Test counts last measured in this checkout: **1123 Android host / 1159
`macosArm64`, no failures, none skipped**; 84 Python tests pass.

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

**Seven vacuous checks were caught during F-2, every one a fixture or an assertion sitting at a symmetry
point of the thing being tested** — a cap whose fixture was derived from the mutated constant; a slerp check
at `t = 0.5`, where slerp and nlerp agree exactly; a normal matrix on `diag(2, 4, 1)`, its own transpose; a
depth-mask check read at a sticker draw, where the sticker pass sets the mask itself; a screen-order check
at unequal z, which passes under either authority; a premultiplication check on a colour-type-2 PNG, where
premultiplying is the identity; and an animation check at `t = 1.0`, which `timeSeconds % duration` maps
back onto `t = 0`. **Assume the next one exists.**

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
ground, geometries, models, map-anchored stickers). Read both before proposing anything that touches the public API — where this
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
| `tools/` | Standard-library Python release resolver, publication verifier, repository-policy checker, and their unit tests. |

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
Android AAR gate on Ubuntu. `apple-publication` compiles both iOS targets, runs `macosArm64Test`,
publishes all seven publications to `build/local-maven`, then compiles the standalone consumer's six
targets with a fresh Gradle home and `--refresh-dependencies`.

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

## The macOS test harness

Cycle I's harness is a local development client that consumes a locally published RenG, feeds it a series
of `FramePlan` JSON documents, and encodes the output as an MP4. **Capture and MP4 encoding live in the
harness, not in RenG** — RenG only draws.

**Half of that already exists, pulled forward into the basemap cycle.** The visual harness lives in
`consumer-smoke/src/macosArm64Main/`, under the standalone consumer's own build, so it exercises the real
published coordinate rather than a project dependency. It owns context creation, as Cycle I's harness was
always meant to: a headless CGL core-profile context reached through the stock `platform.OpenGLCommon` and
`platform.OpenGL3` klibs with **no cinterop at all**, reporting `Apple M3 Max | 4.1 Metal - 90.5`. It
drives a fixed 48-frame storyboard (`Storyboard.kt:25`, with frames 30..32 as negative cases) rather than
`FramePlan` JSON, and it writes binary PPM and prints an `ffmpeg` line rather than encoding. The style URL
carries the owner's API key, so no style is checked in: pass `-PstyleUrl=` or `RENG_HARNESS_STYLE_URL`.

**What is still Cycle I's, therefore:** `FramePlan` JSON in, a self-contained AVFoundation encoder, and
MP4 out. Note that the current `ffmpeg`/libx264/`yuv420p` assembly step is **lossy**, and has already once
misled a judgement about image sharpness — compare at `crf 12` / `yuv444p`, or on the PPM frames directly.

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
  :kmp:macosArm64Test \
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

`linuxX64Test` is Linux CI coverage, not a macOS-local gate. `macosArm64Test` is the one Apple target
with a test task rather than a compile-only gate. On the current macOS system Ruby 2.6 toolchain, parse
both workflow files with Psych's aliases-enabled positional API:

```bash
ruby -e 'require "yaml"; YAML.safe_load(File.read(".github/workflows/ci.yml"), [], [], true); YAML.safe_load(File.read(".github/workflows/publish.yml"), [], [], true)'
```

Single test in any Gradle test source set: `--tests "com.rohittp.reng.SomeTest"` (works on Kotlin/Native
test tasks too). Every CI and publication Gradle invocation passes `--no-configuration-cache`.

`local.properties` is untracked and machine-specific; do not commit it.
