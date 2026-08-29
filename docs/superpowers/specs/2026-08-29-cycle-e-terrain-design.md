# Cycle E-terrain — design

The ground stops being flat. RenG decodes the elevation Rentile acquires and displaces the basemap
ground with it, in **both** projections, plus Cycle C's deferred terrain-acquisition task.

Its preflight is `docs/research/2026-08-29-e-terrain-preflight.md`; every number quoted here comes from
there or from this document's own arithmetic. Nothing below is a preference where a measurement was
available.

---

## 1. What already exists, and what the decomposition overstates

`docs/decomposition.md` says Cycle C "takes Rentile's terrain descriptor and DEM tiles, decodes them, and
validates their declared encoding". Half of that is true.

Real: `ResourceClass.BASEMAP_DEM_TILE` is already a **public** constant with its byte ceiling, accept header
and report order mapped; the style reader already parses `terrain.source` into
`BasemapStyleManifest.terrainSourceId`; `BasemapSourceKind.RASTER_DEM` already routes to that class; the
firewall already runs `validatesDemTerrainEncoding` on its write path, where ADR 0016 puts the obligation;
and `tileTimeRoutes` already preregisters the **3×3 neighbourhood** for every `raster-dem` source.

Not real: Cycle C's **task 20** was never implemented — there is no `TerrainAcquisition.kt` — and **RenG has
never called one of Rentile's terrain entry points**. `acquireTerrainTiles`, `terrainSourceDescriptor`,
`groundRadianceDescriptor`, `ValidatedDemTile` and `TerrainDemEncoding` have zero references in `kmp/src`.

So the plumbing is real and the acquisition is not.

## 2. What the corpus asks for

All 34 style ids fetched. **9** declare a `raster-dem` source; **6** declare a top-level `terrain` block and
therefore need displacement; **3** carry a `hillshade` layer and no terrain, which Rentile already draws
itself. The two sets are **perfectly disjoint** — no corpus style combines terrain with hillshade.

Five of the six leave `encoding` unset (Mapbox by specification default) and **one declares `terrarium`**,
so both encodings ship or the corpus breaks. All six set `exaggeration: 1`. Only one inlines `tileSize` and
a zoom range, so `TerrainSourceDescriptor` is where those are read from, never the style JSON.

Of the six, **56, 65, 76 and 80 carry a raster layer** whose imagery already contains relief shading;
**57 and 84 are pure vector** (10 and 55 fill layers, no raster), whose flat fills read as flat however far
they move. That asymmetry is the whole reason §7 exists.

## 3. Elevation reaches the vertex shader by texture fetch

**Decision.** The DEM is uploaded as a texture and sampled in the vertex shader with `textureLod`. The
alternative — decoding on the CPU and baking heights into per-tile vertex buffers — was rejected because it
destroys an economy the globe ground already has: one **shared grid per granularity**, cached, with the
matrix uploaded once before the tile loop and one `vec4` per tile. CPU baking forces a vertex buffer per
tile per granularity and re-bakes whenever LOD moves, which X2 measured swinging the tile set 93 → 167.

**Measured, not assumed.** `GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS` is required to be ≥ 16 by both GLSL ES 3.00
and GL 3.30, so every driver claims support and the advertised number proves nothing. The probe
(`kmp/src/commonTest/kotlin/com/rohittp/reng/VertexTextureFetchProbe.kt`) instead places 64 points at rows
the vertex shader *read out of a texture*: Apple M3 Max, `Apple Software Renderer` through CGL, and the same
rasteriser through EAGL all place all 64. llvmpipe and Android are wired and unrun. Both deliberate breaks
— ignoring the fetch, transposing the texel — turn it red.

**Two portability facts are load-bearing.** A vertex shader has no implicit derivatives, so the fetch is
`textureLod` with an explicit level; and GLSL ES 3.00 declares `precision lowp sampler2D` for the vertex
language (§4.5.4), whose ~8 bits is exactly what an 8-bit DEM channel needs and no more, so the sampler is
declared `highp` explicitly.

**Filtering is `GL_NEAREST`, always.** Mapbox packs 24 bits across R/G/B, and bilinear blending is unsafe
across a channel carry: where green rolls 255 → 0 as red increments, a blended triple decodes hundreds of
metres wrong. Smoothness comes from **mesh interpolation between vertices placed on texel centres**, never
from the sampler. Any smoothing beyond that is explicit taps decoded individually and averaged as *heights*.

## 4. Seams are closed by a padded border, not hidden by skirts

**The DEM grid is edge-exclusive.** Texel `i` is centred at `(i + 0.5)/N`, so a tile boundary sits half a
texel *outside* the outermost texel centre and is represented by **no texel in either tile**. Rentile's own
`heightAt` treats pixel `-1` of one tile as pixel `W-1` of its neighbour. Clamping each ground tile to its
own data therefore puts the two sides of every edge at different heights.

**Decision.** Each DEM is uploaded as `(N+2)²` with a one-texel ring copied from its neighbours, so both
sides of an edge read **identical source values** and agree exactly. This is mapbox-gl-js's solution.

**The cost is a perimeter ring, not a ninefold increase** — a correction to the preflight's first reading.
For a contiguous visible set the neighbours are mostly already fetched as their own centre tiles; the extra
is `4√T + 4`, which is **+45% at 93 tiles and +33% at 167**. RenG's `tileTimeRoutes` already preregisters
the 3×3, so the firewall URLs exist and only the acquisition widens.

**LOD seams do not arise.** The ground already selects **one LOD per frame** — MapLibre's rule that two
meshes sharing an edge must be subdivided identically — so there are no LOD boundaries within a frame and
the padded border only ever solves the same-LOD case. One requested `z` against one DEM `maxzoom` also gives
one `childScale` for the whole frame.

## 5. The two projections need opposite work

- **Mercator's ground is a four-vertex quad per tile** (`GROUND_QUAD`) with no subdivision anywhere. A flat
  quad cannot displace, so Mercator gains a subdivided grid where none exists.
- **The globe's ground is already subdivided** (1–128 cells a side, chosen from the camera) but bakes
  **altitude zero into one per-frame matrix**: `composeGlobeGroundUnitSphereToClip` folds the radius in as a
  uniform radial scale precisely so the vertex shader can emit a unit direction. Displacement makes that
  scale **per vertex rather than per frame**.

The pipelines stay separate, for the reason `GlobeGroundPipeline`'s KDoc already gives, but the DEM
sampling and elevation decode are **one GLSL source fragment composed into both vertex shaders**, so the
formula exists once as text. `GROUND_FRAGMENT_SOURCE` is already shared between them; this is the same
argument applied to the vertex half.

**Granularity is derived from elevation error**, as the globe's is derived from curvature deviation, and the
two must yield **one granularity per frame**. One consequence is worth stating because it surprised this
cycle's own gate: since the rule is a work budget that never inspects the data, a **flat** DEM still asks
for a fine grid where a terrainless frame asks for a coarse one — and subdividing a plane is the identity
while subdividing a sphere is not. A flat DEM is therefore byte-identical to terrain-off only with
granularity held fixed, not at the API level. Sampling a DEM per texel is not an option: at X2's measured
167-tile worst case a 256-texel DEM sampled per texel is **11,030,183 vertices**.

## 6. Altitude gains a mode, and its default is the glossary's

**Decision.** `AltitudeMode = ABSOLUTE | GROUND_RELATIVE`, carried by `Placement` and — because
`CONTEXT.md` says a **Geometry** carries no **Placement** — by `Geometry` as well.

**`ABSOLUTE` is the default because the glossary already decided it**: `CONTEXT.md:265` defines the field as
"ellipsoidal altitude metres", so a caller who says zero over a plateau means sea level and gets it.
`GROUND_RELATIVE` is the opt-in that means the other thing. **The two are identical wherever the ground is
flat**, which is every style declaring no terrain, so nothing shipped changes behaviour.

**An `elevationAt(lat, lon)` query was rejected as circular**: elevation depends on resident DEM tiles,
residency depends on the camera, and the camera comes from the plan the consumer is trying to build. It
would answer from the previous frame's residency — wrong exactly when the camera moves — and it fights
`FramePlan` being a complete definition of on-screen state. A plan-declared mode has no such problem: the
plan says "on the ground" and RenG resolves it from the tiles that frame has.

**CPU elevation is sampled sparsely.** Placements and labels resolve on the CPU and cannot read the DEM
texture, and keeping a decoded DEM for every visible tile would be ~224 MiB against a
`maximumDecodedImageBytes` of 256 MiB already shared with every raster. So only tiles containing a
ground-relative placement or a label anchor are decoded CPU-side.

**Labels follow terrain.** Not a decision so much as a consequence: an anchor left at sea level detaches
from its feature the moment the ground under it rises.

## 7. Terrain shading is a renderer option, default off

**Decision.** `RendererConfiguration` gains a flag, **default off**. Off, the ground stays unlit exactly as
ADR 0026 says.

Off is the default because it is what the styles asked for. MapLibre does not shade terrain either — it
displaces, and shading comes from a `hillshade` layer. The corpus shows the authors making that choice
explicitly: the 3 styles wanting relief shading declared `hillshade`, and the 6 that displace declined it.
RenG inventing light the style never requested is the divergence its firewall posture exists to prevent.

It exists at all because **57 and 84 are pure vector**, and a flat fill reads as flat however far it moves.
When on, the normal comes from taps the padded border already pays for, lit by ADR 0026's existing
directional light rather than a new lighting concept. `RendererConfiguration` is a plain class, so the cost
is one constructor parameter and one property with no `copy`/`componentN` fallout.

`groundRadianceDescriptor` is **not** this: it is a flat colour multiplier compiled from the style's
top-level `lights`, independent of `terrain` entirely, whose range is the gamma-re-encoded and **unclamped**
`[0, 2^(1/2.2)] ≈ [0, 1.366]`. It tints; it cannot make relief visible.

## 8. The ground writes depth

**Decision, and it is conditional — this paragraph was wrong when first written and ADR 0039 is the
authority.** The ground writes depth **in a frame whose ground is displaced**, superseding ADR 0027 for the
ground pass, giving the map regime the phases: ground (test + write), geometries (test), models
(test + write, ADR 0030), map-anchored stickers (test). In a frame with no terrain the ground writes no
depth, exactly as ADR 0027 says.

**Why conditional, since this document first said otherwise.** An unconditional write revives ADR 0027's
first defect in the most ordinary frame a consumer writes — a coplanar altitude-0 `Geometry` over a *flat*
ground — and that is a live gate rather than a worry:
`BasemapReadbackSuite.assertACoplanarGeometryKeepsEveryGroundCoveredPixelAcrossACameraSweep` budgets
`max(2, covered/100)` deleted pixels, and ADR 0027 records the pre-fix code failing **184 of 2099** at pitch
15. **28 of the corpus's 34 styles declare no terrain**, so that is most frames. The partition costs
nothing: the write buys occlusion only where the ground has relief, and the defect exists only where it has
none. Widening the sweep's budget instead was considered and rejected.

ADR 0027 removed depth writes because a coplanar `Geometry` z-fought the flat ground and a map-anchored
sticker carried its anchor's single depth against a plane varying down-screen. Terrain makes the opposite
error worse: **terrain that cannot occlude is not terrain**, and a model behind a mountain painting over it
is more wrong than a z-fight.

**The remaining risk is real and is quarantined by the wave split.** Ground-relative content is
*deliberately* coplanar with terrain, and its height comes from a **CPU `Double`** lookup while the
ground's comes from a **GPU `Float`** fetch — different arithmetic paths that cannot be expected to agree to
the bit, which is exactly ADR 0027's original condition. Wave 1 introduces depth writes while **no
ground-relative content exists**, so no content is coplanar with *displaced* ground; the flat-ground case is
handled by the condition above rather than by the wave split. Wave 2 introduces the risk and opens with a spike that
measures it, with the mitigation being a shared nearest-texel rule against the same padded texture so both
paths sample the same value.

## 9. Terrain degrades; it never fails a frame

**Decision, and a deliberate divergence.** Every other basemap resource fails the frame when acquisition
fails — measured, not assumed: one ArcGIS tile timeout produced
`PREPARE FAILED RESOURCE_UNAVAILABLE at RESOURCE_LOOKUP` and lost frame 38 of a 48-frame render. Terrain
does not. When `acquireTerrainTiles` throws, the ground draws flat and a diagnostic says so.

Two failure shapes, two answers:

- **Absence** — tiles below `minimumZoom` or outside the source's `bounds` are dropped by Rentile with
  `mapNotNull`, no diagnostic and no exception, and `TerrainSourceDescriptor` exposes no `bounds` so RenG
  **cannot predict it**. Those tiles draw flat, and one diagnostic per frame names the count.
- **Error** — a throw draws the whole ground flat with a diagnostic, rather than failing the frame.

Terrain adds ~33% more tile requests, so making each one a new way to lose a frame would measurably raise
the frame-loss rate of exactly the video renders this project uses to verify itself.

Results are matched by `requestedTile`, **never** by list index or by assuming the returned list is as long
as the requested one.

## 10. Smaller rulings, each from a principle rather than a preference

- **Exaggeration is honoured, finite, unclamped.** Rentile ignores `terrain.exaggeration` entirely, so RenG
  parses and owns it. `CONTEXT.md:266`'s house rule is that out-of-domain values "fail rather than clamp or
  wrap", and ADR 0037 has just reaffirmed that no tuned constant ships. Any range would be such a constant.
  **All six corpus styles set `1`, which is exactly why the tests must not.**
- **The camera is never clamped and earns no diagnostic.** A camera in a valley below surrounding peaks is a
  legitimate shot, and a camera inside solid ground is *visibly* wrong, unlike a coverage gap that
  masquerades as real land. RenG draws the camera the plan specifies. (For scale: the camera sits a fixed
  651.8 logical pixels above the anchor, which at latitude 46.5 is 2,144 m at zoom 14 — below Mont Blanc.)
- **ADR 0038's cull survives displacement**, since displacement does not change winding and the ground now
  self-occludes through depth. One inaccuracy is recorded rather than fixed: `globeLimbPlane` culls
  placements against a horizon computed for a *smooth* sphere, so a placement atop a peak that genuinely
  pokes above the horizon is culled.
- **The firewall's DEM read gate is not this cycle's problem.** `passesClassSpecificReadValidation`'s
  `else -> true` carries a specific argument that holds at Rentile 0.6.0 — its raster acquirer re-decodes
  and re-verifies the digest on a store hit and refetches on mismatch. The narrower gap (non-PNG, alpha,
  dimension disagreement) lands on **RenG's own terrain decode**, which must validate what it decodes anyway.
- **GPU residency stays at 512 MiB.** Terrain roughly doubles texture residency — 391 MiB at 167 tiles,
  which fits; 1,123 MiB at the declared 512-tile ceiling, which does not. X2's invariant that a *legal*
  frame cannot thrash therefore no longer holds, and that is recorded rather than repaired: the realistic
  worst case fits, and `RESIDENT_GPU_TEXTURES_OVER_BUDGET` already exists to make the rest observable.

## 11. What this cycle does not do

- **No pixel verification.** Cycle J owns golden images by owner decision; terrain gates on analytical
  readback over a real context.
- **No hillshade.** RenG never draws a `hillshade` layer; Rentile does, into the tile image.
- **No terrain in the camera's own arithmetic** — no collision, no clamping, no elevation-aware LOD.
- **No mesh simplification, no error-driven refinement, no quadtree.** One granularity per frame.
- **No no-data handling.** A `0,0,0` Mapbox pixel decodes to a literal −10,000 m; that is the encoding's
  own answer and the style's problem.
- **No decoded-DEM residency across frames beyond what the resident cache already provides**, which is the
  same shape as F-2's per-frame GLB reparse debt and is left standing.
