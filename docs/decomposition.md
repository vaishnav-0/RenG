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
ADR 0029 rejects a `SCREEN`-positioned model and ADR 0030 reopens ADR 0027's depth-write ruling for the
model pass alone; ADRs 0031–0033 are Cycle H's and touch the platforms and the gates rather than the
contract — standing on deprecated OpenGL ES for iOS, taking `androidx.test:runner` for instrumented tests,
and gating the mobile targets asymmetrically.
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
                                                            ──► F-2 models ──► release ──► E-labels
                                                            ──► H platforms ──► G globe ──► E-terrain ──► J corpus
```

**G moved ahead of E-terrain on 2026-08-29**, by owner decision, once E-labels closed. G's spec was already
written and its research done, while E-terrain had no preflight at all. The obligation moves rather than
disappearing: G is designed against a scene whose ground is a smooth sphere, so **E-terrain now inherits
displacing both projections** instead of G re-projecting a ground that already displaces. Terrain is
designed once, against a globe that exists.

**Where that inheritance is concrete, now that G has shipped.** The globe ground bakes altitude zero into
one per-frame matrix: `composeGlobeGroundUnitSphereToClip` folds the radius in as a uniform radial scale so
the vertex shader can emit a unit direction and nothing larger. Displacing the ground means the radial
scale becomes **per vertex rather than per frame**, which is a different shape of change from Mercator's --
there the ground is a quad whose vertices already carry position. Both are E-terrain's, and
`GlobeGroundPipeline.kt` says so at the point where the assumption is made rather than only here.

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
| E-labels | Map text drawn as screen-space primitives from Rentile label candidates | Analytical readback over a real GL context, plus a recorded harness pass. **Not legibility** — see below |
| E-terrain | Terrain displacing the ground in **both** projections, plus deferred Cycle C task 20 | Golden baselines with terrain |
| H | Android and iOS bring-up | `iosSimulatorArm64Test` in CI; two one-command device runs, neither automated |
| G | Globe projection | Analytical readback at both projection modes, plus a recorded harness pass. **Not curvature fidelity** |
| J | Golden-image corpus gate | Corpus job wired into `ci.yml` and `publish.yml` |

**Where the sequence stands.** A, B, C, D, F-1 and E-basemap are released: A as `0.1.0`, B/C/D/F-1 together
as `0.2.0`, and E-basemap as `0.3.0`. **F-2 and H are both complete and both unreleased.** F-2's eighteen
tasks are merged into `main`, gated by a model readback suite that draws a real GLB on a real driver; H's
seven merged into `main` on 2026-08-28. E-labels and E-terrain remain
unstarted, though E-labels has been spiked; see `HANDOFF.md` for what that spike settled. **H ran out of order deliberately**, ahead of
G, on the 2026-08-22 reasoning recorded under "G — Globe projection" below.

**Pixel verification is deferred to Cycle J** by owner decision, recorded at
`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`. The gate rows for
E-basemap and F-2 therefore read "analytical readback", not golden baselines: relationships asserted over
a real GL context, with no stored images. Analytical readback catches the failures that are quiet and
plausible — a transposed tile index, a v-flipped texture, a silently empty ground, a model that renders
black because its mipmap filter has no mipmaps — and it cannot tell anyone whether the result *looks*
right. That remains Cycle J's job, and these cycles should not imply otherwise.

**A visual harness runs earlier than the cycle that was to own it, and it now exists.** Pulled forward on
the reasoning that RenG had drawn a basemap no human had looked at: analytical assertions prove
relationships, not resemblance. It lives in `consumer-smoke/src/macosArm64Main/` rather than in its own
directory, because `tools/check_repository_policy.py` permits Kotlin source in exactly two places —
`kmp/src` and `consumer-smoke` — and because the six-target resolution proof is what protects every release
and should not share a source set with rendering machinery. It writes a PPM frame sequence and leaves
assembly to `ffmpeg`. It earned the reordering immediately: four of the five defects the basemap cycle
fixed after its suite went green were found by looking at its output. **Cycle I, which was to finish it,
was withdrawn on 2026-08-28** — the harness as pulled forward is the whole of it; see below.

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

**E-labels' gate does not include legibility, and its row above no longer claims it.** The original wording
was "labels legible and collision-free over a moving camera". Analytical readback proves collision-free; it
cannot prove legible — antialiasing quality, halo contrast, the sharpness of the SDF iso-line and whether a
reader can tell one glyph from another are all "how it looks" questions, and pixel verification is deferred
to Cycle J. So the gate became the analytical suite **plus a recorded harness pass**, with the limitation
stated in the suite's own KDoc rather than left for a reader to discover.

The harness half earned its place immediately: it found that every LOD change renamed every label and
restarted its fade, blinking the whole text layer out for ten frames — a defect no analytical assertion in
the cycle could see, because every one of them is a single frame or a pair at fixed zoom. That is the fifth
defect the visual harness has caught that a passing suite did not.

**E-terrain and G inherit the same problem and it is not yet solved for them.** Both rows below still say
"golden baselines", and Cycle J still runs after both.

**E-terrain.** Draws the terrain Cycle C acquires, plus Cycle C's deferred
terrain-acquisition task. Cycle
C takes Rentile's terrain descriptor and DEM tiles, decodes them, and validates their declared encoding,
but nothing consumes elevation until here: this half displaces the mercator ground with it. That keeps the
ground one subject rather than splitting acquisition from the only thing that reads it. Ground radiance,
which Rentile evaluates from the style and hands over as a literal, belongs with the same work.

**What the cycle actually cost, recorded because it is not what the paragraph above predicts.** Cycle C
never implemented task 20 at all, so acquisition was built here from nothing. The ground is displaced in
both projections, which needed opposite work in each: Mercator's ground was a four-vertex quad with no
subdivision, and the globe's was already subdivided but baked altitude zero into a per-frame radial scale.
And the cycle turned on a dependency change nobody planned for — **five of the six corpus styles that
declare `terrain` serve WebP DEMs**, which RenG could not decode, so terrain reached one style in six until
Rentile `0.7.0` was cut to hand decoded texels over. That bump also deleted a per-frame decode and, once
RenG stopped decoding DEMs at all, retired ADR 0016's DEM write obligation, whose premise had been that
RenG would read those bytes itself.

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
applied literally. The harness can draw one against a
local publish — `consumer-smoke` defaults `rengRepositoryUrl` to `../build/local-maven`, so no repository
edit is needed and nothing has to be reverted — but no model has been *watched* yet, and the basemap
cycle's record is that watching is what finds the defects a green suite does not.

## H — Android and iOS bring-up

**Complete and unreleased**, on `feat/h-mobile-bring-up`. Its authority is
`docs/superpowers/specs/2026-08-28-cycle-h-mobile-bring-up-design.md` and its plan is
`docs/superpowers/plans/2026-08-28-cycle-h-mobile-bring-up.md`.

The deliverable as written was "Android's `GLES30` path and iOS's `platform.gles3` path get run on real
devices, and whatever differs from the macOS and Linux behaviour gets fixed or documented." **The preflight
did exactly that before the cycle started, and nothing differed** — all 91 roster entry points resolve on
both platforms, and the GL conformance suite passes unmodified at `ShaderDialect.GLES` on an Apple A14 and
on a Qualcomm Adreno 830, the latter with the real cross-dialect link that Linux has to skip for a Mesa
defect. The one thing that looked like a difference, Skia on Android, was a host-JVM packaging fact.

So the cycle's real subject is **permanence**, not bring-up: a binding proven once on a developer's desk is
proven until the next commit, and these two had shipped unexercised through three releases. What it built is
an `iosTest` source set holding an EAGL context and an `androidDeviceTest` source set holding an EGL14
pbuffer context; `BasemapReadbackSuite`, `ModelReadbackSuite`, `ModelFixtureBuilder` and the large-quad
rasterisation probe relocated from `nativeTest` to `commonTest`, so one copy reaches every target instead of
the Android spike's second copy; `:kmp:iosSimulatorArm64Test` appended to `ci.yml`'s existing
`apple-publication` invocation; `tools/run_ios_device_tests.py`, which makes the iOS device run one command
where it had been five manual steps; an explicit third dependency scope in `tools/check_repository_policy.py`
so `androidx.test:runner` can be declared without weakening the gate; and ADRs 0031, 0032 and 0033.

**It grew the public ABI by nothing** — the first cycle since F-1 to do so, and the constraint it was
gated on. `kmp/api/kmp.klib.api` is byte-identical to `main`.

**What it did not do, stated plainly because the gate is weaker than the headline.** Neither permanent
device test has met a real GPU: both phones were detached when their tasks ran, so `androidDeviceTest`'s
only execution is against a `Pixel_10_Pro_XL` emulator on ANGLE over Vulkan over SwiftShader, and the
Adreno 830 and Apple A14 results live in the preflight spikes rather than in any gate.
`tools/run_ios_device_tests.py` has never been run against the class it defaults to — it was exercised on
hardware against the spike's test class, and `IosGlConformanceTest` arrived afterwards; the filter matches
structurally, but the loop is unrun. And the one readback case that would catch a ground regression skips on
the iOS simulator's `Apple Software Renderer`, the only rasteriser CI will ever run for this target — a skip
proved load-bearing by mutation, since trusting that driver makes 2 of 5 cases fail, the ground one over
3,005 of 15,876 interior pixels, which is `0.3.0`'s exact publication-failure signature. Ubuntu's llvmpipe
job keeps that case gated. **ADR 0033 is the record of that asymmetry and of what a release may claim about
a target verified only in simulation.**

No Android emulator joined CI: an AVD selects its ANGLE backend from configuration rather than from a
documented default — measured, `hw.gpu.mode=auto` chose Vulkan-on-SwiftShader — and an unstable rasteriser
makes an unstable probe reading. `iosArm64` still has no Gradle test runner; the Python tool works around
that rather than fixing it. No GPU vendor beyond Adreno and Apple has been measured on any target.

## G — Globe projection

The second projection mode, re-projecting mercator basemap tiles and every placement onto a globe.
Deliberately after F and E so it re-projects a complete scene rather than being designed around a
partial one. Moved behind H on 2026-08-22: every cycle adds GL surface that two of the six published
targets have never executed, and that gap compounds, so bring-up should not keep waiting behind new
rendering work.

## I — macOS harness — withdrawn 2026-08-28

**Withdrawn by owner decision. The letter stays bound to its content so no prior reference breaks**, the
same convention the 2026-08-19 reorder used. The harness pulled forward into the basemap cycle already owns
everything RenG refuses to — the headless CGL context, the capture framebuffer, the readback — and it has
found defects a passing suite did not, which was the cycle's whole purpose. What remained was consuming
`FramePlan` JSON documents and replacing the print-an-`ffmpeg`-line step with a self-contained AVFoundation
encoder. Both were withdrawn on one argument: **the harness is verification code, so every line of encoder
inside it is a line that can produce a misleading video and cast doubt on the renderer.** A PPM frame is a
header and raw bytes, with essentially nothing to get wrong, and `ffmpeg` has been debugged by more people
than this project ever will. Shelling out keeps the bug surface outside the thing being trusted.

Two consequences worth recording. **`FramePlan` serialization stops being an unowned prerequisite**: it had
exactly one consumer, this cycle, and Cycle J's corpus is in-source Kotlin fixtures rather than documents
(`docs/research/2026-08-21-golden-image-gate-design.md:392`), so nothing else needs it and RenG adds neither
a public serialization surface nor a serialization dependency the repository policy would refuse. And the
assembly step's **lossiness is now a hazard to live with rather than one to engineer away**:
`ffmpeg`/libx264/`yuv420p` has already once misled a judgement about image sharpness, so sharpness is judged
at `crf 12` / `yuv444p` or on the PPM frames directly. That caveat's permanent home is `CLAUDE.md` under
"The visual harness".

Reversing this is cheap and compatible if a consumer ever needs RenG to hand over plan documents: the
harness gains a parser and the decomposition gains a cycle. Nothing here forecloses that.

## J — Golden-image corpus

The gate that proves RenG still draws what it drew: a corpus of frame plans rendered per platform and
compared against baselines with a tolerance. It slots into the same two places Rentile's corpus does — a
job in `ci.yml` and a step in `publish.yml` before upload. Rentile's two credential-bearing corpus gates
have no RenG analogue and were deliberately not ported.
