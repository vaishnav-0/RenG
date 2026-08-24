# Decomposition

RenG is too large for one specification. It is built as a sequence of cycles, each with its own spec,
its own implementation plan, and its own gates. A cycle is finished when its outcome gates pass, not when
its code exists. Cycle A additionally requires both CI jobs on the exact merged commit and anonymous
verification of that commit's first public completion record.

Cycle 0 is complete: the original graphics contract is recorded in ADRs 0001–0012 and `CONTEXT.md`;
ADRs 0014–0015 supersede its preparation-ordering and exact-context deletion details, ADRs 0016–0017
add the strict Rentile firewall and terminal renderer ownership rules, ADR 0018 fixes canonical content
identities, ADRs 0019–0022 record the coroutines dependency, PNG decode ownership, the GLB subset, and the
corrected GL source-set visibility, ADR 0023 corrects the GL restore set, ADRs 0024–0026 fix the
draw-regime order, the map-regime depth rule and draw order for coplanar content, and the single
world-anchored light models are shaded by, ADR 0027 supersedes 0025's depth-*write* ruling so that no
map-regime draw writes depth, and ADR 0028 narrows ADR 0021's flat GLB accessor subset to a per-role one.
Everything below inherits the current decisions rather than revisiting them without new evidence.

## Order

**Reordered 2026-08-19 so an MVP can ship for waiting consumers.** Existing cycle letters stay bound to
their existing content so no prior reference breaks: Cycle F splits into **F-1** (stickers, geometries, and
the renderer factory) and **F-2** (models with textures and animation), and Cycle E splits across its
basemap, label and terrain thirds. Six Cycle C tasks travel to Cycle E in this reorder — see "C — Resource layer"
below for exactly which.

```
A skeleton ──► B core ──┬──► C resources ──┐
                        └──► D gl foundation┘──► F-1 (MVP) ──► release ──► E-basemap ──► release
                                                            ──► F-2 models ──► release ──► E-labels ──► E-terrain
                                                            ──► H platforms ──► G globe ──► I harness ──► J corpus
```

C and D are genuinely independent — one is I/O and CPU, the other is GPU — and are the natural place to
work in parallel. Everything from F-1 onward is a chain; the MVP release sits between F-1 and E-basemap.

| Cycle | Delivers | Gates |
|---|---|---|
| A | Publishable `:kmp`, six targets, `:app` gone | Both CI jobs on exact merged commit; public six-target smoke; immutable completion record verifies anonymously |
| B | Public API surface and the pure core behind it | `checkKotlinAbi`, host + `linuxX64` + `macosArm64` tests |
| C | Resource acquisition, decode, parse, caching | Host tests against fake transport/store |
| D | The GL seam and its three implementations | Real-context conformance on macOS and llvmpipe |
| F-1 | Stickers, geometries, and the renderer factory — the MVP | Call-log draw-path assertions; ADR 0024's draw-regime order honoured |
| *(internal MVP release)* | All six targets published; only macOS and Linux verified | — |
| E-basemap | Basemap drawn from Rentile tiles, plus deferred Cycle C tasks 14/16/17/18/19 | First frame with pixels; analytical readback over a real GL context |
| F-2 | Models with textures and animation | Analytical readback over a real GL context |
| E-labels | Map text drawn as screen-space primitives from Rentile label candidates | Labels legible and collision-free over a moving camera |
| E-terrain | Terrain displacing the mercator ground, plus deferred Cycle C task 20 | Golden baselines with terrain |
| H | Android and iOS bring-up | Device/simulator runs, manual for Android GL |
| G | Globe projection | Golden baselines at both projection modes |
| I | macOS harness: plans in, video out | A rendered sequence encodes and plays |
| J | Golden-image corpus gate | Corpus job wired into `ci.yml` and `publish.yml` |

**Where the sequence stands.** A, B, C, D, F-1 and E-basemap are released: A as `0.1.0`, B/C/D/F-1 together
as `0.2.0`, and E-basemap as `0.3.0`. **F-2 is complete and unreleased** — all eighteen tasks landed on
`feat/f2-models`, gated by a model readback suite that draws a real GLB on a real driver. Everything from
E-labels onward is unstarted, though E-labels has been spiked; see `HANDOFF.md` for what that spike settled.

**Pixel verification is deferred to Cycle J** by owner decision, recorded at
`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`. The gate rows for
E-basemap and F-2 therefore read "analytical readback", not golden baselines: relationships asserted over
a real GL context, with no stored images. Analytical readback catches the failures that are quiet and
plausible — a transposed tile index, a v-flipped texture, a silently empty ground, a model that renders
black because its mipmap filter has no mipmaps — and it cannot tell anyone whether the result *looks*
right. That remains Cycle J's job, and these cycles should not imply otherwise.

**A visual harness runs earlier than Cycle I, and it now exists.** Pulled forward on the reasoning that
RenG had drawn a basemap no human had looked at: analytical assertions prove relationships, not
resemblance. It lives in `consumer-smoke/src/macosArm64Main/` rather than in its own directory, because
`tools/check_repository_policy.py` permits Kotlin source in exactly two places — `kmp/src` and
`consumer-smoke` — and because the six-target resolution proof is what protects every release and should
not share a source set with rendering machinery. It writes a PPM frame sequence and leaves assembly to
`ffmpeg`; a self-contained encoder through AVFoundation cinterop stays with Cycle I. It earned the
reordering immediately: four of the five defects the basemap cycle fixed after its suite went green were
found by looking at its output.

The MVP release is **internal**: breaking the public interface in a later cycle is accepted. Publication
itself stays immutable regardless — a later breaking change means a new version, never overwriting a
published coordinate.

## A — Build and publication skeleton

The `:kmp` module with `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and
`linuxArm64`; `explicitApi()` and Kotlin ABI validation; `com.rohittp.rentile:kmp` resolving from
`https://maven.rohittp.com` with no `mavenLocal()`, at whatever version `gradle/libs.versions.toml` pins; the standalone `consumer-smoke` build; the
dependency-free `docs/` site; `VERSION_NAME` as the sole checked-in version input. `:app` is deleted —
it is Android Studio's skeleton and nothing in it is RenG.

Nothing here renders. The point is that `ci.yml` and `publish.yml`, which already reference `:kmp` and
`consumer-smoke`, stop failing, so every later cycle lands against a working gate. Publication fails
closed on remote uncertainty or an occupied artifact. Cycle A ends only when both CI jobs pass the exact
merged commit and its public workflow anonymously verifies every manifest artifact, valid aggregate
metadata, credential-free resolution for all six targets, and the final immutable record at
`com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`; POM and metadata availability alone are
not completion proof. See ADR 0013.

That outcome is satisfied. Cycle B is complete, merged, and **released inside `0.2.0`**: every plan task
including the cross-engine contract proof was finished and independently reviewed with its findings fixed,
`checkKotlinAbi` reported no public ABI change, and the Android host, `linuxX64` and `macosArm64` suites all
passed.

Cycle A is publicly complete from exact source commit
`af92901b2ef045078b855a6b47533bc95aca6886`: CI run `31968682132` and publication run `31968682290`
succeeded, public six-target resolution passed, and the immutable `0.1.0` completion record verified
anonymously. The required documentation follow-up keeps README and served-doc version display
metadata-driven. ADR 0013 and the Cycle A design spec and implementation plan remain historical decision
records.

The second release, `0.2.0`, completed the same way from exact source commit
`a2cbe6a965247f221f7e279a962b40306baac21b`, carrying Cycles B, C, D and F-1 together: both CI jobs and the
publication workflow passed on that commit and its immutable completion record verifies anonymously. The
first attempt failed closed before any R2 write, on a test ceiling calibrated for developer hardware;
nothing was published, so the same version was retried after the ceiling was replaced.

The third, `0.3.0`, carries E-basemap, from exact source commit
`49cc1d5843ba40ac866f96d00123ca07b662f6fc`; its completion record verifies anonymously and all seven
publications resolve over public HTTP with no credentials. It too failed closed once first — on a readback
case that a hosted macOS runner's software rasteriser cannot draw — and it too was retried at the same
version once the suite learned to measure the driver rather than name it. Recovery by explicit retry, never
by overwrite, is exactly ADR 0013's intent, and it has now been exercised twice.

## B — Public API surface and pure core

Every type a consumer touches, embodying ADRs 0001–0012 as refined by ADRs 0014–0018: the frame
vocabulary (`FramePlan`,
`Placement`, `Sticker`, `Model`, `Geometry`, `AnimationTrack`, camera), the renderer boundary
(`prepare`, `draw`, cancellation, resource query and free, the GPU-objects-are-gone operation, `close`),
RenG's own transport and store interfaces with RenG's resource classes, and typed exceptions carrying
stable codes, pipeline stage, and redacted diagnostics.

Behind it, the parts that need no GPU and no network: resolving a camera to matrices, resolving each
placement property independently under its own anchoring mode, selecting basemap tiles for a camera at
the configured output size, diffing consecutive plans, and deriving content-keyed identity for every
cacheable resource. Cycle B also lands the production pure decision engines for ordered preparation, resource
route/frontier/lookup/response/write actions, and renderer lifecycle/error precedence. Those engines consume
supplied observations and emit immutable actions; they call no adapter, Rentile, decoder, parser, context API,
or GL function. Cycles C and D connect their respective real observations and execute their actions. All Cycle B
behavior is host-testable, which is why it comes before both C and D.

Coordinate precision is resolved here: geographic and camera-relative transform math remains `Double` through
clipping and rebasing, and only small camera-relative values cross the GPU boundary as `Float`. Mercator
preparation rejects coordinates outside its latitude and proved world-copy bounds rather than clamping or
wrapping them.

## C — Resource layer

Acquisition through the consumer's transport and store by driving Cycle B's pure resource-operation decisions,
proxying basemap resources down to Rentile and fetching RenG's own; PNG decode; GLB parse; the content-keyed
cache with refcounted lifetime across concurrently live prepared frames; the reload-on-access path that makes
freeing safe; cancellation of everything in flight.

Two open technical decisions belong here and both deserve a spike before the spec is written. PNG
decoding across six targets has no free answer — Skiko is proven on these targets but heavy, and a pure
Kotlin decoder needs an inflate implementation. GLB parsing is glTF 2.0 binary: a JSON chunk plus a
binary chunk, tractable in pure Kotlin, but the supported feature subset must be written down rather
than discovered.

Both spikes landed, the design specification and implementation plan were approved, and Cycle C **released
inside `0.2.0`**. It ships the plan's tasks 1 through 13 and 15 — the coroutines dependency and public-surface growth, the inflate/CRC-32 seam,
PNG container parsing and decode (own-authored, no Skiko, hardened by a 300,000-input fuzz test after five
adversarial review passes found six distinct defects), strict UTF-8 and a hand-rolled JSON reader, GLB
container scanning and glTF parsing with the `PARSE_GLB`/`VALIDATE_GLB_FEATURES` gates, the resident cache,
the resource driver's class gates and Store writes, and cancellation through the driver. The owner
reordered the remaining six tasks onto Cycle E instead, so an MVP can ship before basemap work is ready:
sprite/style commits, the production Rentile private-key resolver, the firewall transport/store adapters,
engine failure classification, and the basemap rasterizer host travel to E's basemap half, and terrain
acquisition travels to E's terrain half. As released, this cycle therefore proxied nothing to Rentile,
decoded no basemap tile, and drew no pixel — all of which the basemap cycle then closed.

An earlier version of this paragraph said "six Rentile-firewall-validated gate/class combinations fail
loudly rather than fake success". That was wrong twice: the enumerated set was five, and it is now **zero**.
The basemap cycle deleted those gates rather than implementing them, because the Rentile engine acquires
and validates those classes itself through RenG's firewall and RenG's driver never routes one;
`ResourceClassGate` is down to `DECODE_PNG`, `PARSE_GLB` and `VALIDATE_GLB_FEATURES`. `HANDOFF.md` carries
the scheduler-cost measurement taken here, which shipped unfixed. The erratum once owed against ADR 0016's
basemap-class count has been written and is appended to the ADR itself.

## D — GL foundation

The internal GL seam and its three implementations — `platform.OpenGL3`/`platform.OpenGLCommon`,
`platform.gles3`, `dlsym`, and Android's `GLES30` — with signatures both pointer-based and JVM-array-based
sides can implement. It supplies real context, target, and handle observations to Cycle B's lifecycle decisions,
then executes their GL actions. Context and dialect detection at setup; the offscreen colour+depth surface and
the composite pass; the documented save-and-restore state set; shader compilation with version-directive
substitution and program caching.

The conformance suite lands here and is the reason ADR 0006 and ADR 0008 are claims rather than hopes:
state identical before and after a draw, and a GLSL ES 3.00 source compiling under both a substituted
and an unsubstituted directive. It runs against real contexts on `macosArm64` and llvmpipe.

Cycle D **released inside `0.2.0`**. Every plan task including the real-context conformance suite is
complete and each was independently reviewed with its findings fixed.
`checkKotlinAbi` reports no public ABI change across the whole cycle. Its own gates pass locally: the
conformance suite ran for real
against llvmpipe (surfaceless EGL, ES 3.2 and desktop 4.5 core) and against a real CGL core-profile context
on Apple silicon; ADR 0006's restore-set claim is corrected and superseded by ADR 0023 and verified
byte-exact on both. A Mesa 25.2.8 driver defect made one deliberate negative check — a cross-dialect
`glLinkProgram` — SIGSEGV in-process on Linux; the owner-approved fix skips that one check on Linux for
every dialect, leaving the macOS fixture as the only remaining real proof that `#version` substitution is
load-bearing. That Linux verification happened opportunistically under Docker against real Mesa, not
through a hosted CI run of `:kmp:linuxX64Test`; the hosted runner's no-GPU software-renderer fallback on
macOS remains untested on any machine with a real GPU. The seam has grown since Cycle D closed — count
`GlEntryPoint` rather than quoting a number from prose.

## E — Basemap, labels and terrain

**Split across three execution slots.** The 2026-08-19 reorder split basemap from terrain so an MVP could
ship first: the basemap slot runs immediately after F-1's MVP release, and terrain runs last, moved behind
models because terrain was already deferred once for having no consumer while models have consumers
waiting. Labels were split out on 2026-08-22, after research showed they share almost nothing with the
model work they were briefly bundled into — a signed-distance-field shader, viewport-wide collision and
priority, and a Rentile-side change that has not shipped — and that letting an external dependency sit
inside the model cycle would hold a shippable release hostage. All three slots belong to one letter and
one subject — what the map itself draws — and are described together here.

**E-labels.** Map text drawn by RenG as screen-space primitives from Rentile's `LabelCandidate`s, which
already carry laid-out glyph quads, so text shaping and line breaking are Rentile's. It **was** blocked on
a Rentile API that reports the glyph closure before fetching it — ADR 0016's firewall preregisters exact
URLs, and glyph-range URLs are the one resource class RenG cannot derive in advance, because the range set
comes from text inside decoded vector tiles. **Rentile `0.5.0` shipped it**: `planLabelCandidates` freezes
the closure without acquiring any of it, and `acquireLabelCandidates(plan)` consumes that same frozen plan,
so the closure cannot under-approximate the acquisition. The cycle is unblocked; `HANDOFF.md` carries the
API detail and the work that remains. Placed before terrain on consumer value: a map without text serves
fewer consumers than a flat one does.

**E-basemap — released as `0.3.0`.** Rentile PNG
tiles decoded, uploaded, and drawn as the mercator ground under a camera, with texture residency and
eviction bounded by an explicit GPU byte budget and driven by the prepared frames that are alive. It picked
up five of Cycle C's deferred tasks — sprite/style commits, the production Rentile private-key resolver,
the firewall transport/store adapters, engine failure classification, and the basemap rasterizer host — and
added a sixth thing nobody planned for: sources that declare their tiles by reference through a TileJSON
document, which 96 of the sources across the owner's 34-style corpus use, against 2 inline. Without it the
ground drew for none of the 34.

This is the first cycle that produces pixels, and its gate is **analytical readback with no stored
baselines**: a 128×128 frame through the public API, read back whole, with relationships asserted — no
interior pixel is the clear colour, four named samples carry four fixture colours, quadrant means stand in
a fixed order, and `drawBasemap = false` leaves the frame untouched. Golden baselines were deferred to
Cycle J with all other pixel verification.

Three decisions came out of drawing the ground rather than out of planning it. **ADR 0025** supersedes ADR
0024's depth rule: with strict `GL_GREATER`, every altitude-0 map-anchored thing vanished the instant a
ground existed at altitude 0 — the draw call issued, the pixels never written — so the comparison becomes
`GL_GEQUAL` and the map regime's draw order becomes a contract (ground, then geometries, then map-anchored
stickers). **ADR 0027** then supersedes 0025's depth-*write* ruling: near ties, which a moving camera
produces constantly, z-fight where exact ties do not, and a map-anchored billboard is bisected at any
nonzero pitch — so no map-regime draw writes depth at all, and declaration order becomes the whole rule
rather than only the tie-break. Its stated cost, that map-regime content can no longer occlude anything, is
correct for flat quads and billboards and is what F-2 must reopen for models. **ADR 0026** invents the
single directional world-anchored light models will be shaded by, before the cycle that needs it, so the
constant is argued in the open rather than appearing unexplained in a shader.

When golden baselines do arrive they need a finer key than the platform. A hosted macOS runner renders
through a software renderer while a developer's machine renders through Metal, so the reported renderer
string — not the target — keys a baseline, or the first run on new hardware fails on a difference that is
not a regression. And attribution has to be split: Rentile draws the basemap's content through Skia and
RenG only composites it, so a whole-frame baseline cannot tell a RenG regression from a Skia bump.

**E-terrain.** Draws the terrain Cycle C acquires, plus Cycle C's deferred terrain-acquisition task. Cycle
C takes Rentile's terrain descriptor and DEM tiles, decodes them, and validates their declared encoding,
but nothing consumes elevation until here: this half displaces the mercator ground with it. That keeps the
ground one subject rather than splitting acquisition from the only thing that reads it. Ground radiance,
which Rentile evaluates from the style and hands over as a literal, belongs with the same work.

## F — Drawn things

**Split into F-1 and F-2 by the 2026-08-19 reorder** so an MVP can ship for waiting consumers before models
are ready. Both halves belong to one letter and one subject — things a `FramePlan` draws — described
together here.

**F-1 — stickers, geometries, and the renderer factory (the MVP), released in `0.2.0`.** `createRenderer`, the first API making
RenG operable; stickers drawn in both draw regimes; geometries painted by consumer shader pairs with
consumer-supplied uniforms and textures. This cycle owns the decision CLAUDE.md flagged as ADR-worthy: how
the two draw regimes order against each other within one frame, given screen-anchored things composite by
z-index with no depth test while map-anchored things are occlusion-tested against the scene. **ADR 0024**
answers it: the map regime draws first, depth-tested, and the screen regime composites on top as a single
stack. It also fixes the documented uniform and attribute names a shader pair may declare. Ships no
basemap, terrain, models, or globe, and defers all pixel verification to Cycle J in favour of call-log
draw-path assertions. This cycle's release is the internal MVP.

**F-2 — models with textures and animation. Complete, unreleased.** Its authority is
`docs/superpowers/specs/2026-08-23-cycle-f2-models-design.md` and its plan is
`docs/superpowers/plans/2026-08-23-cycle-f2-models.md`.

It shipped accessor and BIN-chunk decoding — until this cycle **no code in RenG had ever read a byte of a
BIN chunk** — node transform composition, animation resolution and sampling with quaternion slerp, vertex
skinning with joint matrices in a uniform buffer, embedded texture decode with correct sampler state, and a
model draw pass with real occlusion. It widened ADR 0021's subset against the consumer's own 41 models
rather than against glTF's breadth: extra UV and colour sets are ignored rather than rejected (which alone
unblocked 14 of the 41, and was the sole reason for 10), and skins are admitted (7 of the 41 are genuinely
vertex-skinned; the other 18 declaring one carry exporter debris no node references).

**It reaches 40 of the 41 models, and the exception is a decision.** Morph targets stay rejected because
zero of the 41 carry one, and JPEG stays rejected because exactly one does and a decoder RenG does not have
is not worth building for a single asset.

Both defects folded in by owner decision are closed: the composite's double alpha multiply, and ADR 0027's
no-intra-regime-occlusion ruling, reopened for models alone by **ADR 0030** — a mesh that writes no depth
cannot occlude itself, and 109 of the corpus's 111 materials are `doubleSided`, so culling cannot cover for
it. ADR 0029's refusal of a `SCREEN`-positioned model, which had been a decision with no code behind it,
is implemented.

**The public ABI grew by exactly three enum entries**: `RenGErrorCode.UNSUPPORTED_ANCHORING_MODE`,
`ResourceKind.MODEL_GEOMETRY` and `ResourceKind.MODEL_IMAGE`.

**Not done, and owed.** A model pays all three GLB parses on **every frame**, not once per acquisition —
a `RESIDENT`-provenance route still runs the class gates — measured at about 3 ms per model per frame on an
M3 Max, roughly 18% of a 60 Hz budget for one model. That is the standing argument for a parsed-model
residency. `decodedCpuBytes` shares `maximumDecodedImageBytes` with rasters rather than taking its own
public limit. All-zero skin weights collapse a vertex to the origin, which is the specification's formula
applied literally. And no model has been seen in the harness, because `consumer-smoke` resolves the
published coordinate and F-2 is unreleased.

## H — Android and iOS bring-up

The two targets CI cannot exercise against a real context. Android's `GLES30` path and iOS's
`platform.gles3` path get run on real devices, and whatever differs from the macOS and Linux behaviour
gets fixed or documented.

## G — Globe projection

The second projection mode, re-projecting mercator basemap tiles and every placement onto a globe.
Deliberately after F and E so it re-projects a complete scene rather than being designed around a
partial one. Moved behind H on 2026-08-22: every cycle adds GL surface that two of the six published
targets have never executed, and that gap compounds, so bring-up should not keep waiting behind new
rendering work.

## I — macOS harness

A consumer that happens to live in this repo, under its own build like `consumer-smoke`, resolving the
published coordinate rather than a project dependency. It owns everything RenG refuses to: creating the
headless CGL context, driving a capture framebuffer, reading back frames, and encoding MP4. **Half of it
already exists** — the visual harness pulled forward into the basemap cycle owns the context, the capture
and the readback. What is still this cycle's: consuming a sequence of `FramePlan` JSON documents, which
means plan serialization is settled by then and is still unowned, and a self-contained AVFoundation
encoder in place of the current print-an-`ffmpeg`-line step.

## J — Golden-image corpus

The gate that proves RenG still draws what it drew: a corpus of frame plans rendered per platform and
compared against baselines with a tolerance. It slots into the same two places Rentile's corpus does — a
job in `ci.yml` and a step in `publish.yml` before upload. Rentile's two credential-bearing corpus gates
have no RenG analogue and were deliberately not ported.
