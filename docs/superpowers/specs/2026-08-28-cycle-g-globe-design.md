# Cycle G — the globe projection

**Design specification.** Written 2026-08-28, ahead of its turn: G's research and grilling ran in parallel
with E-labels by owner decision, on the grounds that G is independent until its implementation.

**This spec is complete for everything except terrain, and deliberately so.** G re-projects whatever the
scene contains, and **E-terrain sits between E-labels and G and is undesigned**. Displacing a sphere's
surface is a different problem from displacing a plane, not the same problem with different math. §10 lists
what stays open; nothing else in this document should be read as covering it.

**No implementation plan accompanies this spec yet.** One should be written after E-terrain's design exists.

## Provenance

| Document | Establishes |
|---|---|
| `docs/research/2026-08-28-g-globe-projection-seam.md` | RenG's own seam and what a second mode costs |
| `docs/research/2026-08-28-g-globe-prior-art.md` | how shipping globes work, sourced and graded A–D |
| `docs/research/2026-08-28-g-globe-rentile-tiles.md` | the supply side: tiles, seams, counts, budgets |
| `docs/research/2026-08-28-owner-decisions.md` | the decision list this spec answers (G1–G5) |

---

## 1. What the cycle delivers

A second `projectionMode` that re-projects the mercator basemap, stickers, models, geometries and labels
onto a sphere. Sequenced after F and E so it re-projects a **complete** scene rather than being designed
around a partial one.

**Rentile has no notion of a globe at all** — it discards a style's `projection` root key outright
(`StyleCompiler.kt:2259`), and both its documentation pages list the globe as unsupported. The engine hands
over square mercator raster tiles and RenG re-projects them. The whole cycle is RenG's.

---

## 2. What `zoom` means on a sphere (G1)

**`z_eff = zoom − log₂ cos(latitude)`.** The globe is scaled by `1/cos(latitude)`, as MapLibre does.

The problem it solves: RenG's LOD rule rests on `screenPixelsPerTexel = 2^(zoom − lod)`
(`MercatorLod.kt:11-27`), an identity that holds **only** because a mercator tile's output-pixel width is
latitude-independent. On a sphere it fails by `1/cos²φ`. Modelled against RenG's own camera arithmetic — a
transcription validated by reproducing `FramePlanningCoreTest.kt:285`'s asserted count exactly:

| camera | mercator | naive globe | latitude-matched |
|---|---:|---:|---:|
| 960×540, lat 82, zoom 6 | 4 tiles | **132** (33×) | 4 |
| 1179×2556 phone, lat 82, zoom 8 | 24 tiles | **686** (28.6×) | 24 |

Peak cost under the chosen convention is about **4×**, around zoom 3–5 (24 → 96).

**The naive reading does not merely cost tiles — it fails frames.** 686 exceeds
`maximumBasemapTileInstances`' default of 512, so a high-latitude phone camera would fail closed with a
resource-limit error rather than render slowly.

**The accepted cost is that `Camera.zoom` becomes projection-dependent.** Zoom 6 shows a different scale in
the two modes, so a consumer toggling `projectionMode` sees the map jump. MapLibre ships exactly this and
its source carries apologetic comments about it; Mapbox instead matches mercator at a fixed 45°, which is
stable in the middle and wrong at both ends. Consistent tile cost was chosen over consistent zoom
semantics, and the reason is that the alternative makes frames fail rather than merely look different.

**Per-tile LOD was rejected as out of proportion.** It removes the dilemma rather than trading it off, but
`LodObservation(selectedLod: Int)` commits RenG to one LOD per frame in its type, so it is a change to what
the planner returns and to every test asserting a single LOD.

---

## 3. Units, precision and depth — settled by arithmetic

**RenG stays in logical pixels. It does not move to ECEF metres.** This is not a preference; the numbers
decide it.

Window depth under RenG's reverse-Z projection works out to exactly `near / distance`, with `near` fixed at
one logical pixel (`CameraMatrices.kt:141`) and a 24-bit **fixed-point** buffer (`OffscreenSurface.kt:19,58`).
The resolvable depth step at the camera's own distance is:

- **0.025 logical pixels** in RenG's existing unit;
- **2.4 × 10⁶ metres** in ECEF metres with a 1 m near plane, at earth radius.

A globe naturally invites a metric frame. Taking that invitation destroys the depth buffer.

This also removes the precision argument for a mercator/globe transition, which is the main reason the web
renderers have one.

---

## 4. Occluding the far hemisphere (G2)

**Backface culling for the ground, a horizon test for placements. No depth-write change: ADR 0027 stands.**

- The far hemisphere is **back-facing**. Ground patches have consistent winding on a sphere, so `glCullFace`
  removes it with no depth involvement.
- A placement is beyond the horizon exactly when a cheap CPU test says so — and the sign of `w` from
  `ScreenProjection` (built for E-labels, `internal/projection/ScreenProjection.kt`) already computes it.

**The web renderers' trick is unavailable to RenG.** MapLibre and Mapbox both clip the far side by
overwriting `gl_Position.z`, which works only because their depth buffer holds layer-ordering slices of
`glDepthRange` rather than perspective depth (`painter.ts:496-500`). It has broken twice in the field: on
Android drivers that order `glDepthRange` and clipping differently (MapLibre #6047, open, with a device
matrix), and against 3D content needing real Z (#6698, open). ADR 0030 gives RenG's model pass a real depth
test, so that route is closed here.

**And depth-writing was rejected rather than merely unavailable.** CesiumJS avoids both problems by having
its globe write depth — but ADR 0027 removed depth writing from the whole map regime because near-ties,
which a moving camera produces constantly, z-fight where exact ties do not, and a map-anchored billboard is
bisected at any nonzero pitch. A depth-writing globe ground resurrects that for every altitude-0 thing
standing on it.

**Known gap, accepted:** content that *straddles* the limb is handled wrongly. A tall model just beyond the
horizon is culled entirely rather than having its top drawn, and a geometry spanning the limb likewise.

**Cost:** enabling face culling adds an entry to the GL Restore Set that ADR 0023 governs. RenG does not
currently enable it.

---

## 5. One projection at all zooms, then measure (G3)

**Build globe-at-all-zooms. Measure the subdivision cost against a frame budget on real hardware. Add a
transition only if the numbers justify it.**

Both shipping renderers transition at a zoom threshold. Neither of their reasons applies cleanly here:
precision is settled by §3, and the visual case is measurable. The **sagitta** of a frame-sized quad — how
far the sphere bows from the tangent plane across one screen — is **27.9 logical pixels at zoom 4, 1.75 at
zoom 8, and 0.44 at zoom 10**. Above roughly zoom 10 a transition blends between two things that look the
same.

What remains is cost: at high zoom, ground tiles keep paying for subdivision nobody can see. Prior art says
a **raster**-tile globe needs a dense grid — MapLibre's whole-tile granularity is base 128 at zoom 0, halved
per zoom, **never below 32** (`vertical_perspective_projection.ts:20`), against a floor of 2 for fill, with
the in-source reason that raster otherwise shows "visible warping and deformations when changing zoom
levels". That is roughly 2,300 triangles per ground tile — but from a **shared, cached** grid buffer with
per-tile uniforms, not per-tile CPU work. Mapbox uses one 64×64 grid for the entire globe.

**This is deliberately the same discipline as E5 and G5: no tuned constant ships before a measurement.** The
X2 fix had to retrofit exactly that lesson, where two defaults chosen without measurement disagreed by 4×.

---

## 6. The consumer shader contract (G4)

**RenG subdivides a `Geometry` and projects its vertices on the CPU, leaving a linear view-projection
matrix. Consumer shaders run unchanged in both modes. ADR 0008 survives.**

The problem: a `Geometry` is a lat/lon/altitude-bounded quad painted by a **consumer-supplied** GLSL pair,
and under mercator the consumer writes `gl_Position = uMvp * aPosition`. **A mercator→sphere map is
nonlinear, so it cannot be expressed as a matrix.**

What makes the clean answer available: a `Geometry`'s four resolved corners are always **coplanar** — two
distinct mercator x, two distinct y, and z a function of y (`GeometryResolver.kt:41-46`,
`MercatorProjection.kt:45-51`). So an N×N grid lerped from those corners **changes no pixel under
mercator**, and RenG can subdivide in *both* modes and keep one contract rather than making the vertex
count mode-dependent.

**Two rejected alternatives, both worse than they look:**

- **A new uniform** carrying the globe transform sounds cheapest and is a **breaking change disguised as an
  addition**: `RESERVED_SHADER_NAMES` is enforced at `Geometry` construction (`DrawnThings.kt:275-280`), so
  growing that set turns a previously legal `Geometry` into an `IllegalArgumentException` for any consumer
  already using that name.
- **Prelude injection**, MapLibre's actual answer — prepend a `projectTile` function plus a variant cache
  key — directly contradicts ADR 0008's promise that a shader is self-contained but for its version
  directive, and would break every existing consumer shader *silently*, since ADR 0008 binds a documented
  name only when the shader declares it.

**ADR 0008 needs an erratum, not a supersession**, covering two narrow breaks: `gl_VertexID` stops meaning
what it did once RenG subdivides, and `aPosition.z` is pure altitude under mercator but
altitude-minus-curvature on a globe.

Subdivision should be **adaptive to the quad's angular extent** — a city-block geometry needs almost none, a
continent-sized one needs a lot.

---

## 7. Trigonometric precision (G5)

**Mitigate, probe, and require no new hardware.**

**GLSL ES grants the trigonometric functions undefined precision.** The spec is explicit (ES SL 3.00
§4.5.1, p. 52) while specifying `exp` at 3+2|x| ULP — and a mercator→sphere projection is nothing but
trigonometry. Three projects were bitten: MapLibre on **Mali-G610/G710** with 200–300 m latitude error
(#7419), CesiumJS on Intel HD 4600 with a `sin` discontinuity (#2110/#2111/#2258), and MapLibre's globe
atmosphere under 16-bit `mediump` on Android (#6916/#6939).

**RenG's only Android device evidence is Adreno — the family that was fine.** Mali is unmeasured.

Three mitigations, none needing a device:

1. **A trig-free latitude path** via tangent half-angle identities, removing `atan`/`sin`/`cos` from the
   GPU-side latitude computation entirely. This is the fix that survived in MapLibre.
2. **CPU-side transform construction** wherever it fits — per-tile transforms computed in `Double` and
   applied to a shared grid, which is the same shape §5 wants for cost reasons. G4 already removes trig from
   the consumer-geometry path.
3. **A runtime precision probe**, in the idiom RenG already established. `measureLargeQuadRasterisation`
   exists because `0.3.0` failed publication on a software rasteriser, and the suite now **measures the
   driver rather than naming it** — one case skips out loud when the probe distrusts the driver, and the
   others still run. MapLibre shipped (and later deleted) exactly such an `atan`-error probe.

**The probe reports and skips; it does not gate.** A hard budget would be a number chosen without ever
having measured a Mali device — the thing E5 and G3 both declined to do.

### Refined 2026-08-28 by `docs/research/2026-08-28-g-latitude-precision-spike.md`

The mitigation **holds, and is narrower than stated above.** Measured over 200,134 asymmetric float32
samples:

| formulation | latitude error per ULP of `atan`/`sin`/`cos` error |
|---|---:|
| naive `2·atan(exp ψ) − π/2`, then `sin`/`cos` | **1.661 m** worst case |
| tangent half-angle `(t²−1)/(t²+1), 2t/(t²+1)` | **0 m** — bit-for-bit unmoved to 1024 ULP |

At 1024 ULP the naive form is 1,700 m out. Inverting the measurement, MapLibre's field-observed 200–300 m
on Mali implies roughly **120–180 ULP** of `atan` error.

Three refinements the design above does not capture:

1. **What it buys is immunity to the *unbounded* built-ins, and only that.** With correctly rounded
   built-ins the two forms differ by 0.4 m across the whole domain. **The guarantee is only as good as
   `exp`** — at `exp`'s own specified `3 + 2|x|` ULP bound the half-angle path is 8.7 m out and inverts
   monotonicity slightly *more* often than the naive one. It converts an unbounded error into a specified
   one; it does not make the path exact. That `exp` assumption is precisely what only the runtime probe can
   check.
2. **MapLibre's stated reason for its own fix is wrong, and Cycle G must not repeat it.** Its source blames
   the `− π/2`; measurement shows the shared `PI - y*PI*2.0` prelude, one line *above* either identity, is
   what destroys precision near the equator. The two forms agree there to 2 micrometres.
3. **`precision highp float` is an independent non-negotiable.** `mediump` costs about **20 km** either
   way — 9.8 km of it before any arithmetic happens at all — so no choice of identity rescues a `mediump`
   latitude path.

One collateral result worth carrying into the probe's design: a single ULP of *non-smooth* `atan` error
already inverts **1,134 of 19,999** consecutive float32 y values under the naive form, and **zero** under
the half-angle form. That reproduces the reported "distinct latitudes overlap" symptom without needing a
badly broken driver, which means the probe should measure monotonicity rather than only magnitude.

---

## 8. What the cycle must not copy from mercator

Two hazards the seam research found that would ship silently.

**`PlacementResolver.kt:110-114` divides map scale by `cos(latitude)`.** That is precisely mercator's
distortion factor and **must be dropped on a globe**. Copying it makes every map-scaled object **2× too
large at latitude 60** while agreeing **exactly at the equator** — which is the natural place a fixture gets
written, so this is a symmetry-point trap with a defect already sitting on it. Any fixture for map scale
must be at a non-trivial latitude.

**Two algorithms are mercator-*shaped*, not mercator-parameterised** — substituting a projection function
does not fix them:

- `clippedPhysicalPixelFootprint` (`MercatorGroundFootprint.kt:33-63`) scans column 0 for its admissible
  row band, then casts four corner rays **unchecked**. That is sound only because a ray's hit/miss verdict
  on a horizontal plane depends on the screen *row* alone. On a sphere the limb is a curve in both axes, so
  the cast throws and the four-corner rectangle stops covering the visible ground.
- `selectBasemapTiles` and `observeMercatorLod` rest on a "rows × x-interval" topology and a single global
  `screenPixelsPerTexel`, both of which are mercator-plane facts.

By contrast the lat/lon→world conversion really is contained: `projectMercator` has exactly **one** caller,
and only two arithmetic sites exist outside `internal/projection/`.

---

## 9. Tiles, seams and residency

**Rentile's adjacent tiles already align exactly — measured, not argued.** Decoding its shipped nine-tile
mosaic, cross-seam column steps are 16.44 and 15.32 mean channel-difference against an interior-column mean
of 14.06 and a max of 18.84: the seams sit inside ordinary noise. There is no bleed, padding or overdraw,
and none is needed.

**So a globe seam would be RenG's defect** — patch geometry, sampler state, or a mipmap chain.

**The mipmap trap is the sharp one.** RenG's ground uses `GL_LINEAR` with `GL_CLAMP_TO_EDGE` and **no
mipmaps** (`GlTextureUpload.kt:47,147-149`). Adding mipmaps to cure limb aliasing widens the clamped edge
band from one texel to 2^N and **creates** the seam it was meant to avoid — which would then need a
one-texel border Rentile does not supply.

**Residency is no longer the constraint it was.** The X2 fix raised `maximumResidentGpuTextureBytes` to
512 MiB, matching `maximumBasemapTileInstances`' 512, so a globe's 4× peak (96 tiles) fits with wide margin
and the silent 129–512-tile thrash band no longer exists. `queryResources` now reports those bytes honestly
rather than claiming zero.

**The firewall is unaffected**, cleanly: it is purely per-URL with no count sensitivity beyond a performance
note already sized for 512 instances.

---

## 10. What stays open

1. **Terrain, entirely.** E-terrain is undesigned and precedes G. Every statement in this spec is about a
   scene whose ground is a smooth sphere. Displacement, ground radiance and the DEM path are not covered,
   and this spec must be revisited once E-terrain has a design.
2. **`Geometry`'s 360° longitude span — settled 2026-08-29, and the question was posed wrongly.** It was
   put as a degeneracy: a span whose east and west edges coincide. It is not one. **A `Geometry` is a
   lat/lon-bounded *region*, and a globe projects that region onto the sphere** — a geometry covering India
   covers India, and a 360° span covers the whole globe as a band with no seam. Edges meeting is what a
   closed band does, not a failure of one.

   So: **no rejection, no clamp on the declared span, and no constructor change.** The invariant at
   `DrawnThings.kt:274` stands unaltered in both modes, and nothing a consumer legally constructs today
   stops working.

   **What the camera clamps is the *work*, not the geometry.** G4 has RenG subdividing a `Geometry` and
   CPU-projecting its vertices, so a declared span of 360° would otherwise mean subdividing and projecting
   an entire planet's worth of grid every frame — most of it behind the limb, and all of it discarded by
   G2's backface and horizon culling *after* being paid for. Subdivision density and extent are therefore
   driven by **what the camera can currently see**, not by what the geometry declares. The drawn result is
   identical; the cost is bounded by the viewport rather than by the input.

   This is the same shape as the ground: a tile set is chosen from the camera, not from the world.
3. **`Camera.unwrappedLongitude` on a sphere — settled 2026-08-29.** The field keeps its meaning, its name
   and its value in both modes. A globe renders `longitude mod 360`, so the winding count is **inert** to the
   picture; it survives because it is what keeps a *consumer's* interpolation continuous. A camera animated
   from 170° to 190° pans smoothly east in either projection precisely because the number keeps counting,
   where normalising would turn that into 170° → −170° and leave a naive tween spinning the long way round
   the planet — in the one product this renderer exists for.

   Recorded in `CONTEXT.md`'s **Camera** entry rather than left to be re-derived, including the consequence
   that two globe frames differing only in winding render identically and still carry different frame
   identities. That is not new and not wrong: a Frame Plan's identity is a digest of the plan, never of the
   picture, and LOD hysteresis already made two identically-identified frames render differently.
4. **The gate.** `docs/decomposition.md` gives G "golden baselines at both projection modes", and Cycle J
   delivers baselines *after* G. E8 settled the analogous question for E-labels by keeping J last, so G
   inherits the same treatment and its gate row needs rewriting. A measured number bears on the replacement:
   because the sagitta is 0.44 logical pixels at zoom 10, **a globe that is secretly a tangent plane passes
   any cross-mode comparison above about zoom 12** — so a cross-mode agreement case must be bounded in
   *both* directions and taken at zoom ≤ 8. Alongside it: a limb case, an antipodal-invisibility case, an
   antimeridian-continuity case, and the `drawBasemap = false` negative that stops all four passing
   vacuously.
5. **No GPU vendor beyond Adreno and Apple has been measured**, on any target, and §7's mitigations are
   chosen precisely so that stays acceptable.
