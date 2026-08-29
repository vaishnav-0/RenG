# Cycle G — implementation plan

Authority: `docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md`. That spec carries the reasoning and
every decision; this plan carries the sequence, the per-task obligations, and what each task owes the next.
Where they disagree the spec wins.

Ledger: `.superpowers/sdd/2026-08-29-cycle-g-globe/progress.md`.

## The shape of the cycle

**`ProjectionMode.GLOBE` already exists** and is refused at frame planning with
`UNSUPPORTED_PROJECTION_MODE` (`FramePlanningCore.kt:169-170`). G makes it work. **The expected public ABI
delta is zero** — like Cycle H, and a diff in `kmp/api/kmp.klib.api` is a defect to investigate rather than
a decision to accept. `VERSION_NAME` stays `0.4.0`, frozen.

## Standing obligations for every task

- **Every task ends green.** Python suite, `check_repository_policy.py`, `checkKotlinAbi`,
  `:kmp:testAndroidHostTest`, `:kmp:macosArm64Test`, `:kmp:iosSimulatorArm64Test`.
- **Measure your own baseline by stashing.** Six E-labels tasks found a quoted baseline stale by the time
  they read it. Do not trust a number in this document.
- **Never read Gradle's verdict through a pipe.** `./gradlew … | tail` reports *tail's* exit status, so a
  failing build looks green. Capture to a file and grep `BUILD SUCCESSFUL` / `BUILD FAILED`, or run unpiped
  and check `$?`. This cost E-labels two merges reported green while not compiling.
- **An absent measurement is evidence.** Unmoved test counts and missing JUnit XML both mean the tests did
  not run, not that results are stale.
- **A readback suite's "skip" is not a JUnit skip.** When a probe distrusts a driver, the affected case
  prints `SKIPPED [...]` and the enclosing test still **passes**, so the XML's `skipped` attribute stays 0.
  Reading `skipped=0` as "nothing stood down" is wrong — grep the output for the printed line. On the iOS
  simulator, `[the ground covers the frame]` stands down on every run while the attribute reads zero.
- **Report which single case caught each mutation.** A mutation caught by every case is weaker evidence
  than one caught by exactly one; the latter is what proves a case is not inert.
- **A mutation that does not compile is not a caught mutation.** Task 5 scored one as caught when the build
  had failed and nothing ran; rewritten so it compiled, it was caught by ten cases — but it might not have
  been. Confirm the build succeeded *and* the suite ran before reading a red result as evidence.
- **A surviving mutation has three possible meanings, and they need different answers.** The test is weak
  (add a case), the code is dead (delete it — Task 5 removed a guard the exact filter already covered and
  Task 6 removed a redundant finiteness check), or the mutant is *equivalent* and cannot be caught by
  anything (say so and explain why, as Task 2 did for its algebraically identical arms). Guessing between
  them is how a vacuous check gets written.
- **RenG stays in logical pixels.** Not ECEF metres — the depth buffer's resolvable step is 0.025 logical
  pixels against 2.4 × 10⁶ metres at earth radius. This is settled by arithmetic, not preference.
- Typed failures carry redacted diagnostics; never forward an adapter's or the engine's message.
- **Assume the next vacuous check exists.** E-labels found thirteen inside its own new tests, every one by
  deliberately breaking working code rather than by review.
- **A round trip proves consistency, not correctness** — measured by Task 4, which found its own round-trip
  case blind to three separate mutations. It cannot see an error the forward and inverse paths **share** (a
  flipped basis axis cancels itself), a **self-consistent wrong root** (a ray taking the far sphere
  intersection round-trips perfectly), or anything downstream **normalisation** erases (a dropped world-copy
  unwrap, since everything wraps Mercator x afterwards). Pair every round trip with an assertion against an
  independently derived value.

## Two failures of E-labels' *planning* that this plan exists to avoid

**Tasks must own the seams between them.** Four E-labels tasks were added mid-cycle because every task
stopped cleanly at its own boundary and none owned the joins — the label path was fully built and wired to
nothing, and icons claimed space and drew no ink. **Task 10 below is a seam task by construction**, and any
task that finds its own output unreachable must say so rather than reporting done.

**A decision reasoned about is not a decision tested.** E-labels' fade restarted on every LOD change because
a sound argument went unmeasured. Where this plan states a rule, the task owes a case that fails without it.

---

## Wave 1 — pure math, fully parallel

### Task 1 — the sphere projection

`(latitude, longitude, altitude) → unit sphere → world`, in `internal/projection/`, `Double`, no GL.

**Use the trig-free latitude path**, and take its derivation from
`docs/research/2026-08-28-g-latitude-precision-spike.md` rather than re-deriving it. That spike measured the
tangent half-angle form at **0 metres of error per ULP** of `atan`/`sin`/`cos` error against the naive form's
**1.661 m**, and its proof-of-concept survives on branch `g/spike-latitude` as
`LatitudePrecisionSpikeTest.kt` — bring it in.

Three things the spike established that this task must honour:

- **The guarantee is only as good as `exp`.** At `exp`'s own specified `3 + 2|x|` ULP bound the path is
  8.7 m out. It converts an unbounded error into a specified one; it does not make the path exact.
- **MapLibre's stated reason for the fix is wrong** — its source blames the `− π/2`, and measurement shows
  the shared `PI - y*PI*2.0` prelude one line above either identity is what destroys equatorial precision.
  Do not inherit the wrong reason for the right fix.
- **`precision highp float` is independently non-negotiable.** `mediump` costs ~20 km either way.

**Vacuity warning:** the equator and the poles are both symmetry points of this entire calculation — at
`y = 0` every formulation agrees trivially, and near the ±85.05° clip the values saturate. Sample
asymmetrically across the whole domain including negative latitudes.

### Task 2 — the latitude-matched LOD

`z_eff = zoom − log₂ cos(latitude)` (G1). RenG's LOD rule rests on
`screenPixelsPerTexel = 2^(zoom − lod)` (`MercatorLod.kt:11-27`), an identity that holds only because a
mercator tile's output-pixel width is latitude-independent; on a sphere it fails by `1/cos²φ`.

The measured stakes: at latitude 82, zoom 6, the naive reading takes 4 tiles to **132**, and a phone at
zoom 8 takes 24 to **686** — past `maximumBasemapTileInstances`' 512, so the frame **fails closed** rather
than rendering slowly. Under the chosen convention both return to their mercator counts, peaking at ~4×
around zoom 3–5.

**Corrected during execution — `z_eff` sizes the sphere and does *not* feed LOD selection.** A Mercator tile
at latitude φ covers a ground square `cos φ` the side of an equatorial one, so its on-screen size is
`512·2^(z_eff − lod)·cos φ = 512·2^(zoom − lod)`: **the latitude factors cancel exactly** at the camera's own
latitude, which is where one per-frame LOD is chosen. `observeMercatorLod` therefore needs **no globe arm**.
Feeding it `z_eff` is the reading the formula invites and is *worse* than the naive convention — LOD 9
instead of 6, 154 modelled tiles against 136.

**The accepted cost is public-facing but narrower than first stated**: the on-screen ground scale at the
**view centre** is identical in both modes, so the map jumps away from the centre and at zooms where
curvature reads, not at the centre. `CONTEXT.md`'s **Camera** entry carries the precise form, and ADR 0037
gained an erratum.

`LodObservation(selectedLod: Int)` keeps one LOD per frame — per-tile LOD was rejected as out of proportion.

### Task 3 — the ADRs

Written before the code they govern.

1. **`Camera.zoom` is projection-dependent** (G1). It changes what a *public field means* without changing
   its type, which is the kind of thing a reader will otherwise take for a bug. Record the measured tile
   counts, that the alternative makes frames fail rather than merely look different, and that MapLibre ships
   this while Mapbox matches at a fixed 45°.
2. **The far hemisphere is culled, not depth-tested** (G2). ADR 0027 stands untouched: the web renderers'
   `gl_Position.z` trick needs a depth buffer holding layer-ordering slices and is unavailable under ADR
   0030, and depth-writing would resurrect exactly what ADR 0027 removed. Record the accepted gap — content
   *straddling* the limb is handled wrongly — and that face culling joins ADR 0023's Restore Set.
3. **An erratum on ADR 0008**, not a supersession. Consumer shaders run unchanged because RenG subdivides
   and CPU-projects; two narrow breaks need recording — `gl_VertexID` stops meaning what it did, and
   `aPosition.z` is pure altitude under mercator and altitude-minus-curvature on a globe. Record why a new
   uniform was rejected: `RESERVED_SHADER_NAMES` is enforced at `Geometry` construction, so growing it turns
   a previously legal `Geometry` into an `IllegalArgumentException` — a breaking change disguised as an
   addition.

---

## Wave 2 — camera and visibility

### Task 4 — globe camera matrices

Depends on task 1. The camera orbits a sphere rather than hovering over a plane. Keep the reverse-Z
projection, the one-logical-pixel near plane and the 45° vertical field of view; **stay in logical pixels**.

`ScreenProjection` (built for E-labels) must keep working — its `w` sign is the behind-camera test that
task 6 and every placement path depend on.

### Task 5 — globe ground footprint and tile selection

**Two algorithms are mercator-*shaped*, not mercator-parameterised**, and substituting a projection function
does not fix them:

- `clippedPhysicalPixelFootprint` (`MercatorGroundFootprint.kt:33-63`) scans column 0 for its admissible row
  band, then casts four corner rays **unchecked** — sound only because a ray's hit/miss verdict on a
  horizontal plane depends on the screen *row* alone. On a sphere the limb curves in both axes, so the cast
  throws and the four-corner rectangle stops covering the visible ground.
- `selectBasemapTiles` and `observeMercatorLod` rest on a "rows × x-interval" topology.

By contrast the lat/lon→world conversion really is contained: `projectMercator` has exactly one caller and
only two arithmetic sites exist outside `internal/projection/`.

### Task 6 — cull the far hemisphere

`glCullFace` for ground patches — consistent winding makes the far side back-facing, with no depth
involvement. A **horizon test** for placements: **a limb-plane dot product on the CPU.**

**Two corrections to this plan's first draft, both found by Task 3 and both worth knowing because the
original instructions were plausible and wrong.**

**The horizon test is not the sign of `w`.** `w` is `-z_view`, the distance in front of the *camera plane*,
and `ScreenProjection`'s KDoc says it "is always at least `NEAR_DISTANCE_LOGICAL_PIXELS` here". A camera
outside the sphere has the whole planet in front of it, so an antipodal placement carries a large *positive*
`w`. **Implementing the original sentence literally would have culled nothing** while looking correct.

**Face culling adds nothing to the Restore Set**, and RenG already enables it. Cull enable, mode and winding
have been captured and restored since ADR 0006, and `drawModels` enables `GL_CULL_FACE` per-primitive for
every non-`doubleSided` material. The real obligation is **ownership**: `drawGround` has never set a cull
state and inherits the caller's, harmless today only because the ground quad happens to wind CCW. Set it
explicitly in **both** modes — enabled on the globe, disabled under mercator — so no mercator pixel moves.

*Vacuity warning specific to this:* a test that only checks the globe path passes with the mercator arm
deleted, and vice versa. Assert both, and assert that a mercator frame's pixels are unchanged.

*Known gap, accepted and recorded rather than fixed:* content straddling the limb — a tall model just beyond
the horizon is culled entirely rather than having its top drawn.

---

## Wave 3 — drawing

### Task 7 — the globe ground

A **shared, cached** subdivided grid with per-tile uniforms — not per-tile CPU work. Prior art: MapLibre's
whole-tile granularity is base 128 at zoom 0, halved per zoom, **never below 32**, against a floor of 2 for
fill, with the in-source reason that raster otherwise shows "visible warping and deformations when changing
zoom levels". Roughly 2,300 triangles per ground tile from one buffer; Mapbox uses a single 64×64 grid for
the entire globe.

**Seams are RenG's to avoid.** Rentile's adjacent tiles already align exactly — measured, cross-seam
differences inside interior-column noise, no bleed and none needed. The sharp edge: the ground uses
`GL_LINEAR` + `GL_CLAMP_TO_EDGE` with **no mipmaps**, and adding mipmaps to cure limb aliasing widens the
clamped edge band from one texel to 2^N and **creates** the seam it was meant to avoid.

### Task 8 — placements on a globe

Stickers, models and labels re-projected. **`PlacementResolver.kt:110-114` divides map scale by
`cos(latitude)` — that is mercator's own distortion factor and must be dropped.** Copying it makes every
map-scaled object **2× too large at latitude 60** while agreeing **exactly at the equator**, which is where
a fixture naturally gets written. Any map-scale fixture must sit at a non-trivial latitude.

Labels already project through `ScreenProjection`; confirm rather than assume that path needs no globe arm.

### Task 9 — geometries on a globe

RenG subdivides a `Geometry` and **CPU-projects its vertices**, leaving a linear matrix, so consumer shaders
run **unchanged**. Available because a `Geometry`'s four corners are always coplanar, making subdivision
output-neutral under mercator — one code path, both modes.

**A `Geometry` is a region, and the globe projects it faithfully.** A geometry covering India covers India;
a 360° span covers the globe as a band with no seam. The constructor's invariant is unaltered.

**The camera bounds the work, not the geometry.** Subdivision density and extent follow what the camera can
currently see — otherwise a 360° span costs a planet's worth of grid every frame, most of it behind the limb
and all of it discarded by task 6 *after* being paid for. Same shape as the ground: a tile set is chosen from
the camera, not from the world.

---

## Wave 4 — the seam, then verification

### Task 10 — make a `GLOBE` frame render, end to end

**A seam task by construction, because E-labels proved this plan cannot rely on tasks meeting in the
middle.** Remove the `UNSUPPORTED_PROJECTION_MODE` refusal at `FramePlanningCore.kt:169-170` and wire the
whole path so a `FramePlan(projectionMode = GLOBE)` produces pixels.

**Assert the output, not the wiring.** Every stage has its own unit suite by now; what is unproven is that
they compose. The discriminating test is a `FramePlan` in and globe pixels out.

**The inventory, measured 2026-08-29 rather than left to be rediscovered.** Every piece below exists,
is tested, and has **zero production callers** — this is precisely the hole E-labels fell into, and this
task exists because it did:

| piece | production callers |
|---|---:|
| `selectGlobeTiles` (Task 5) | **0** |
| `GlobeHorizon` — the limb plane and `isBeyondGlobeHorizon` (Task 6) | **0** |
| `GlobeProjection` — `unitSphereDirection`, `projectGlobe` (Task 1) | **0** (reached only through `GlobeCamera`) |
| `globeGroundFootprint` (Task 5) | 1 |
| `GlobeCamera` (Task 4) | 3 |

**And there is an ambiguity Task 7 flagged that this task must settle.** There are now **two** ground entry
points: `drawGround` (`GroundPipeline.kt:191`), whose `GLOBE` arm from Task 6 enables culling but still
draws the **mercator quad**, and `drawGlobeGround` (`GlobeGroundPipeline.kt:498`), which draws the
subdivided sphere grid. A `GLOBE` frame must reach the second. Leaving the first's `GLOBE` arm reachable
would draw a flat quad with backface culling on — which is not obviously wrong on screen, and is exactly the
kind of thing that survives a look at the output.

Task 5's handover, in its own words: `globeGroundFootprint(camera)` then
`selectGlobeTiles(footprint, observeMercatorLod(plan.camera.zoom, previous).selectedLod, maximumBasemapTileInstances)`
— **the LOD argument is the camera's plain `zoom`, never `effectiveZoom`.**

*Vacuity warning:* a fixture whose camera sees no tiles draws nothing, and so does a broken wiring. Assert a
specific non-background pixel, and assert the result **changes** when the camera moves.

### Task 11 — the trig precision probe

G5's third mitigation, in the idiom `measureLargeQuadRasterisation` established: **measure the driver rather
than naming it**, report a number, and skip out loud where the driver is distrusted. A probe that cannot run
**fails rather than skips**.

**Measure monotonicity, not only magnitude.** The spike found that a single ULP of *non-smooth* `atan` error
inverts **1,134 of 19,999** consecutive float32 y values under the naive form and **zero** under the
half-angle one — reproducing the reported "distinct latitudes overlap" symptom without a badly broken
driver. Magnitude alone would miss it.

Report the measured number for every driver actually run on, and name the ones not: llvmpipe (no Linux host)
and every real mobile GPU (ADR 0033).

### Task 12 — the readback gate, at both modes

Per the spec: a limb case, an antipodal-invisibility case, an antimeridian-continuity case, a **cross-mode
agreement** case, and the `drawBasemap = false` negative that stops the others passing vacuously.

**The cross-mode case is a vacuity waiting to be written.** The sagitta of a frame-sized quad is 27.9
logical pixels at zoom 4, 1.75 at zoom 8 and **0.44 at zoom 10** — so **a globe that is secretly a tangent
plane passes any cross-mode comparison above about zoom 12.** Bound it in **both** directions and take it at
**zoom ≤ 8**, or it proves nothing while looking thorough.

**Measure the whole set against a broken build**: make the globe projection a no-op and report how many
cases fail. If most do not, they are upper-bound assertions and worthless — that is how
`ModelReadbackSuite` measured itself at 4-of-6 and how E-labels' label suite measured 8-of-10.

Curvature fidelity is **not** claimed; that stays Cycle J's, and the suite's own KDoc must say so.

### Task 13 — the harness pass, and the owner's videos

Run the visual harness and record what was seen, as E8 requires. Then produce **four videos**: styles 59
(raster) and 86 (vector), each in `MERCATOR` and `GLOBE`, **with labels on**.

The style URLs are the owner's and carry their API key — **never check one in, never paste one into a
document or commit message.** Refer to them as style 59 and style 86.

**Judge sharpness on the PPM frames or at `crf 12` / `yuv444p`**, never on a default encode: the assembly
step is lossy and has already once misled a judgement about image sharpness.

---

## Closing obligations

- `CLAUDE.md`, `HANDOFF.md`, `CONTEXT.md` (the **Camera** entry gains the projection-dependent zoom), and
  `docs/decomposition.md`.
- **The ABI dump must not move.** If it does, something public changed by accident.
- E-terrain now inherits displacing **both** projections — record that where E-terrain's author will find it.
