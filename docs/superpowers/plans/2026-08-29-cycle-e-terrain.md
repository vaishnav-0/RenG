# Cycle E-terrain — implementation plan

Authority is `docs/superpowers/specs/2026-08-29-cycle-e-terrain-design.md`; preflight is
`docs/research/2026-08-29-e-terrain-preflight.md`. Read both before starting any task. Where this plan and
the design disagree, the design wins; where either and an ADR disagree, the newer ADR wins.

## The shape of the cycle

**Two waves, and the split is load-bearing rather than cosmetic.** Wave 1 makes the ground move and adds no
public API beyond two diagnostics and one configuration flag. Wave 2 makes things sit on it, which is where
the public API grows and where the cycle's one genuinely risky interaction lives.

The split quarantines that risk. Wave 1 gives the ground depth writes while **no ground-relative content
exists**, so there is nothing coplanar with the terrain and no z-fighting is possible. Wave 2 creates the
coplanar case and opens by measuring it. If wave 2 slips, wave 1 still delivers terrain.

---

## Standing obligations for every task

These are the same obligations Cycle G ran under. They are repeated because every one of them was earned by
a specific failure.

- **Every task ends green.** Python suite, `check_repository_policy.py`, `checkKotlinAbi`,
  `:kmp:testAndroidHostTest`, `:kmp:macosArm64Test`, `:kmp:iosSimulatorArm64Test`.
- **Measure your own baseline by stashing.** Do not trust a number in this document. Cycle G's Task 12
  found the baselines it had been handed were stale by one, and caught it only because its count was
  impossible.
- **Never read Gradle's verdict through a pipe.** `./gradlew … | tail` reports *tail's* status, so a
  failing build looks green. Capture to a file and grep `BUILD SUCCESSFUL`, or run unpiped and check `$?`.
- **An absent measurement is evidence.** Unmoved test counts and missing JUnit XML both mean the tests did
  not run.
- **A readback suite's "skip" is not a JUnit skip.** When a probe distrusts a driver the case prints
  `SKIPPED [...]` and the enclosing test still passes, so `skipped` stays 0. Grep the output.
- **Report which single case caught each mutation.** A mutation caught by every case is weaker evidence
  than one caught by exactly one.
- **A mutation that does not compile is not a caught mutation.** Confirm the build succeeded *and* the
  suite ran before reading red as evidence.
- **A surviving mutation has three meanings** — weak test, dead code, or an equivalent mutant that cannot
  be caught. Say which; guessing is how a vacuous check gets written.
- **Assume the next vacuous check exists.** E-labels found thirteen inside its own new tests and F-2 seven,
  every one by deliberately breaking working code rather than by review.
- **RenG stays in logical pixels**, not metres. Elevation arrives in metres and is converted once.
- **Never forward an adapter's or the engine's message.** Typed failures carry redacted diagnostics.
- **The style URL carries the owner's API key.** Never check one in, never paste one into a document or a
  commit message.

## Two traps specific to this cycle

- **`exaggeration` is `1` in all six corpus styles.** A fixture at `1` cannot tell an honoured
  exaggeration from an ignored one — it is a symmetry point of exactly the kind F-2 found seven of. Every
  exaggeration assertion uses a value that is not 1.
- **A flat DEM is the strongest negative available.** A DEM whose every texel decodes to the same height
  must render **byte-identical** to terrain being off. If it does not, displacement is leaking somewhere it
  should not, and no positive test will tell you.

---

## Wave 1 — the ground moves

### Task 1 — the ADRs

Three, written before the code they govern, as Cycle G's Task 3 was.

- **The ground writes depth**, superseding ADR 0027 for the ground pass alone. Must state ADR 0027's
  original defects, why terrain inverts the trade, and that the coplanar risk is quarantined into wave 2.
- **Altitude mode**, recording that `ABSOLUTE` is the default because `CONTEXT.md:265` already defines
  altitude as ellipsoidal, and that an `elevationAt` query was rejected as circular.
- **Terrain degrades rather than failing a frame**, recording the deliberate divergence from every other
  basemap resource and the measured frame-loss argument behind it.

### Task 2 — terrain acquisition (Cycle C's task 20, finally)

`internal/firewall/TerrainAcquisition.kt`: the terrain descriptor, ground radiance, and DEM tiles for the
visible set **plus a perimeter ring**.

- `TerrainSourceDescriptor.sourceId` is a **digest** — `sha256Hex` of the style's source id — so matching it
  against `BasemapStyleManifest.terrainSourceId` requires hashing first. This is the easiest trap here.
- Match results by `requestedTile`, **never** by index or count: below-minzoom and out-of-bounds tiles vanish
  through `mapNotNull` with no diagnostic, and no `bounds` is exposed to predict it.
- A throw draws flat and diagnoses; it does not fail the frame.

**Tests:** a ring is requested and not just the visible set; a short result list is matched correctly rather
than shifting every tile; a throw produces the flat-plus-diagnostic path and not a frame failure.

### Task 3 — DEM decode, both encodings

Decode `ValidatedDemTile.bytes` to elevation. The formulas, from Rentile's own source rather than memory:

```
MAPBOX    : -10000.0 + (red * 65536 + green * 256 + blue) * 0.1
TERRARIUM : red * 256.0 + green + blue / 256.0 - 32768.0
```

**Validate what you decode.** Rentile's "Validated" covers status, size, that Skia decoded it, dimensions
and a digest — **not** PNG-ness, **not** the declared `tileSizePx`, **not** opacity. RenG's decode owns all
three.

**Tests:** both encodings against hand-computed heights; a non-PNG payload; a dimension disagreeing with
`tileSizePx`; a pixel with alpha < 255.

### Task 4 — the overzoom sub-rectangle

`childScale = 1 shl (requested.z - source.z)`, `childX = floorMod(requested.x, 1 shl requested.z) %
childScale`, `childY = requested.y % childScale`; the requested tile occupies
`u ∈ [childX/childScale, (childX+1)/childScale]` with **`v` increasing southward**.

Verified against Rentile's own fixture (`RasterResourceTest:19-51`): `(4,13,10)` against a z=2 ancestor
gives `childScale=4, childX=1, childY=2`.

**Depth is unbounded** below the profile's `maximumOutputZoom = 22`: a `maxzoom: 12` source reaches
`childScale = 1024`, the whole ground tile inside one texel.

**Tests:** the fixture above; a wrapped negative `x`; `childScale = 1024`.

### Task 5 — the padded border texture

Upload each DEM as `(N+2)²` with a one-texel ring from its neighbours, so both sides of an edge read
identical source values. `GL_NEAREST` both directions — bilinear is unsafe across a Mapbox channel carry.

Missing neighbours (coverage edge, world edge at ±85.0511°) need a defined fill; say which and why.

**Tests:** the ring carries the neighbour's edge column, not a copy of the tile's own; **two adjacent tiles
sample bit-identical heights at their shared edge** — the assertion the whole task exists for.

### Task 6 — the elevation-error granularity rule

One granularity per frame, derived from elevation error as the globe's is derived from curvature deviation,
and reconciled with `globeGroundCellsPerTileSide` so a globe frame gets one number rather than two.

**Sampling per texel is not an option**: 11,030,183 vertices at X2's measured 167-tile worst case.

**Tests:** the rule falls with zoom as the globe's does; flat terrain does not demand a fine grid; the cap
holds; the globe's two rules agree on one number.

### Task 7 — Mercator's ground gains a grid

`GROUND_QUAD` is four vertices with no subdivision. Introduce a subdivided grid built and cached exactly as
`GlobeGroundGrid` is, so both pipelines share the idiom even though they stay separate pipelines.

**Tests:** winding matches the globe grid's; the shared grid is reused rather than rebuilt per tile; an
undisplaced grid renders identically to the old quad.

### Task 8 — displacement in both vertex shaders

One GLSL source fragment — sampling and elevation decode — composed into both vertex shaders, so the formula
exists once as text, exactly as `GROUND_FRAGMENT_SOURCE` is already shared. `textureLod` with an explicit
level; `highp sampler2D` declared explicitly.

Mercator displaces along the surface normal. **The globe's radial scale becomes per vertex rather than per
frame** — `composeGlobeGroundUnitSphereToClip` folds the radius in as a uniform scale today precisely so the
vertex shader can emit a unit direction, and that is what changes.

**Tests, on a real context:** a flat DEM renders byte-identical to terrain off; a known DEM produces known
heights; **exaggeration 2 doubles displacement exactly** (never test at 1).

### Task 9 — the ground writes depth

Add the write, per Task 1's ADR. No ground-relative content exists yet, so there is nothing coplanar.

**Tests:** a model behind a ridge is occluded by it; a model in front is not; ground-over-ground still
resolves; and the existing map-regime order is unchanged for everything else.

### Task 10 — diagnostics

Two new `DiagnosticCode` constants — incomplete coverage, and terrain unavailable — each **once per frame**,
not once per tile. **This is a public ABI addition**; expect `kmp/api/kmp.klib.api` to move by exactly two
lines and treat anything else as a defect.

**Tests:** fires once for a frame missing forty tiles; does not fire when coverage is complete; the
unavailable path fires on a throw and still renders.

### Task 11 — optional terrain shading

`RendererConfiguration` gains the flag, **default off**. On, the normal comes from taps the padded border
already pays for, lit by ADR 0026's existing light. Off, the ground is unlit and byte-identical to today.

`RendererConfiguration` is a plain class, so this is one parameter and one property with no `copy` fallout.

**Tests:** off renders byte-identical to no shading; on changes a slope's ink and leaves a flat area alone —
the second half is what stops the first from passing vacuously.

### Task 12 — the readback gate, both projections

A limb-and-relief case, a seam case, a flat-DEM negative, and a cross-mode case.

**Measure the whole set against a broken build** — make displacement a no-op — and report how many fail. If
most do not, they are upper-bound assertions and worthless. `ModelReadbackSuite` measured itself at 4-of-6,
E-labels' at 8-of-10, Cycle G's at 3-of-5.

Curvature and terrain *fidelity* are not claimed; that stays Cycle J's, and the suite's KDoc must say so.

### Task 13 — the harness pass

Render the six terrain styles in both projections, with `--static-camera` where a comparison needs it.
Report what is **observed**, not what is hoped: do seams close, does the coverage boundary read as an
artifact, do 57 and 84 look flat with shading off and right with it on.

Cycle G's harness pass found three of its own defects before it found anything in the renderer. Budget for
that.

---

## Wave 2 — things sit on the ground

### Task 14 — the coplanar depth spike, before anything depends on it

Ground-relative content is coplanar with terrain, and its height comes from a **CPU `Double`** lookup while
the ground's comes from a **GPU `Float`** fetch. Measure whether `GL_GEQUAL` plus a shared nearest-texel
rule against the same padded texture keeps it stable, or whether z-fighting returns.

**This blocks tasks 15–18.** If it fails, the mitigation is decided here rather than discovered later.

### Task 15 — `AltitudeMode`, on `Placement` and on `Geometry`

The enum plus a field on each, `ABSOLUTE` by default. **`Geometry` needs its own because it carries no
`Placement`** — `CONTEXT.md` says so.

**Public ABI addition**: a new enum, two fields, and the mechanical `copy`/`componentN` fallout on two data
classes. Every existing plan must render byte-identically, because the two modes are identical wherever the
ground is flat.

### Task 16 — sparse CPU elevation

Only tiles containing a ground-relative placement or a label anchor are decoded CPU-side. Decoding every
visible tile would be ~224 MiB against a `maximumDecodedImageBytes` of 256 MiB already shared with every
raster.

**Tests:** a plan with one ground-relative sticker decodes one tile, not the visible set; the CPU lookup and
the GPU fetch agree on a shared texel.

### Task 17 — placements and geometries ride the ground

Resolve `GROUND_RELATIVE` at draw time in both projections. A `Geometry` drapes across every subdivided
vertex, not just its corners.

**Tests:** a sticker at ground-relative 0 sits on the surface at a known height; the same sticker at
`ABSOLUTE` 0 is buried; a draped geometry follows a ridge rather than cutting through it.

### Task 18 — labels follow terrain

An anchor left at sea level detaches from its feature. Collision still resolves in `prepare()` and never in
`draw()`, per E-labels' decision E4, so terrain height must be available at prepare time.

**Tests:** a label over a displaced feature moves with it; collision outcomes stay deterministic across two
prepares of the same plan.

### Task 19 — the readback gate for anchors, and the harness pass

Ground-relative content over real terrain, including the coplanar case Task 14 measured. Then re-render the
harness and report what is observed.

---

## Closing obligations

- `CLAUDE.md`, `HANDOFF.md`, `CONTEXT.md` and `docs/decomposition.md`.
- **The ABI dump moves by a known amount**: two diagnostics in wave 1, and in wave 2 one enum plus two
  fields with their data-class fallout. Anything else is a defect rather than a decision.
- Record what the cycle leaves owed, measured rather than suspected — as F-2 and E-labels both did.
