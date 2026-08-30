# Let the displaced ground write depth

**The ground pass tests depth and writes it in a frame whose ground is displaced, and tests depth and
writes none in a frame whose ground is the flat plane RenG has drawn since `0.3.0`.** This supersedes ADR
0027 for the ground pass alone, exactly as ADR 0030 already supersedes it for the model pass, and for the
same shape of reason: ADR 0027's blanket rule is right about flat map-plane content and wrong about a
surface with real extent. Nothing else moves — the comparison is still `GL_GEQUAL`, the order is still
ground, geometries, models, map-anchored stickers, and each pass still sets its own mask because
`drawFrame` leaves the mask **on** around its per-frame depth clear (`internal/gl/GlFrameDrawer.kt:93`).

**ADR 0027 was not a preference and it must not be dismissed to get here.** It removed map-regime depth
writes because two defects shipped past 942 passing tests, both found by watching a video rather than by a
test. A coplanar altitude-0 `Geometry` z-fought the ground so badly that one quad's pixel count over
fifteen consecutive frames ran 3609, 7026, 9468, 1257, 25, 0, 8607, 0, 6501, 2475, 4502, 8764, 8064, 3310,
12163 — twice erased outright, never twice the same. And a map-anchored billboard carried its anchor's
single depth against a plane whose depth varies down the screen, so it was cut along an exactly horizontal
line through the anchor: a 41×40 pixel diamond rendered as 39×19 at every pitch except zero. Both defects
were invisible at pitch 0, the one camera where the two surfaces land on bit-identical window depth.

**Terrain inverts the trade, and ADR 0025 said so before ADR 0027 narrowed it.** ADR 0025 kept the ground's
depth writes on precisely because "terrain will need the ground to occlude what is buried in it"; ADR 0027
did not delete that argument, it deferred it — "terrain is a displaced surface with real elevation, drawn by
a pipeline that does not exist yet; when it lands it writes depth as its own decision and the map regime
keeps testing against it, because this ADR removes the writes and not the test." This is that decision.
**Terrain that cannot occlude is not terrain.** A model standing behind a ridge would paint straight over
it, a map-anchored marker in a valley would show through the mountain in front of it, and a `Geometry`
declared below the surface would be drawn on top of the surface burying it. A z-fight is a flicker on a
shared plane; a mountain that does not hide what is behind it is a different picture of the world.

**The write is conditional, and that is this ADR's one departure from the cycle design's §8, which states
it unqualified.** Taken literally, an unconditional write revives ADR 0027's first defect in the most
ordinary frame a consumer writes — a `Geometry` at altitude 0 over a flat ground — and that is not a
hypothetical. `BasemapReadbackSuite`'s
`assertACoplanarGeometryKeepsEveryGroundCoveredPixelAcrossACameraSweep` (`:351`) draws exactly that quad
across five pitch-and-bearing pairs against a budget of `max(2, covered/100)` deleted pixels (`:418`), and
the pre-ADR-0027 code failed it at 184 of 2099 ground-covered pixels deleted at pitch 15. Of the 34 styles
RenG is verified against, **9 declare a `raster-dem` source and only 6 declare a top-level `terrain` block**
— so the flat ground is not an edge case, it is 28 of 34 styles and every frame of three published
releases.

**The partition is exact rather than a hedge, which is the whole argument for it.** The write buys
occlusion only where the ground has relief; the defect it revives exists only where the ground has none.
There is no frame in which the condition costs something the unconditional rule would have bought. The
condition is a property of the **frame**, not of the tile: if any of the frame's ground is displaced, the
whole ground pass writes. A per-tile flip was rejected — it churns state inside the tile loop, and it would
make a coplanar `Geometry` win over a flat coverage gap while losing to the displaced tile beside it, which
is a worse picture than either consistent answer.

**So the map regime has four depth phases and one order.** The ground tests, and writes when displaced;
each `Geometry` tests and writes none; each model tests and writes (ADR 0030); each map-anchored sticker
tests and writes none. One consequence is worth naming because it changes what an existing line is load
bearing for. The geometry pass's own `depthMask(false)` (`internal/gl/SceneContent.kt:397`) has until now
been one of three identical calls, none of which changed a pixel — ADR 0030 measured that for the model
pass's trailing call. It stops being redundant here: the pass immediately before it now leaves the mask on,
so deleting it puts a consumer's shader pair into the depth buffer. The Mercator ground's own
`depthMask(false)` (`internal/gl/GroundPipeline.kt:207`) and the globe's (`GlobeGroundPipeline.kt:583`) are
the two lines this decision actually changes, and both grounds change together, because terrain lands in
both projections.

**The costs are accepted rather than discovered.** Content below the terrain surface is now hidden by it,
which is the point, and is still a behaviour change for anyone who was relying on ADR 0027's "a `Geometry`
declared below altitude 0 now paints over the ground". A billboard sharing space with terrain can be cut
along its anchor row, exactly the defect ADR 0030 accepted for models — the same geometry in a wider place,
since a mountain is a larger occluder than a car. That is accepted on ADR 0030's reasoning, and the
billboard-versus-volume policy stays owed; ADR 0027 already recorded that it is owed regardless, because "a
screen-parallel billboard intersects *any* ground surface it stands on, displaced or flat".

**One residual is not closed, and it is named precisely rather than waved at.** Over terrain, an
altitude-0 `Geometry` is coplanar with the ground wherever the ground is at sea level — over ocean, and
over a coverage gap drawn flat under ADR 0041 — so ADR 0027's first defect is confined rather than
eliminated. It now needs the terrain surface to coincide with the content's altitude, instead of being
guaranteed by a flat plane at zero.

**The deliberately coplanar case is quarantined into wave 2, and the quarantine is the reason the cycle is
split.** Wave 1 introduces the write while **no ground-relative content exists**, so nothing in it is
coplanar with terrain by construction. Wave 2 introduces `GROUND_RELATIVE` altitude (ADR 0040), whose
content sits on the surface on purpose, and it opens with a spike that measures the z-fight before anything
depends on it. The specific hazard is arithmetic rather than geometric: **ground-relative content's height
comes from a CPU `Double` lookup while the ground's comes from a GPU `Float` texture fetch**, two different
paths through two different precisions, which cannot be expected to agree to the bit. That is ADR 0027's
original condition restated in new material. The mitigation to measure first is a shared nearest-texel rule
against the same padded texture, so both paths read the same source value — which is available only because
the DEM sampler is `GL_NEAREST` by an unrelated decision about channel carries.

**Two alternatives are not reopened and one is rejected here.** `glPolygonOffset` and a shader depth bias
were both rejected by ADR 0027 against pixels, and both fail for the reasons measured there; nothing about
terrain changes them. The new one is **writing unconditionally and widening the coplanar sweep's budget
until it passes**. That is rejected because widening a budget until a known defect fits inside it is how a
gate stops being one, and because the defect in question is a quad erased twice in fifteen frames of a real
video.

**Verification is analytical readback plus one existing test whose meaning changes.**
`SceneContentTest`'s whole-scene assertion that "exactly one phase in the whole scene may enable depth
writes" (`:405`) stays exactly true of every frame without terrain, and becomes **two** for a terrain frame
— the ground first, the models second, with the mask off again before the geometries and before the
stickers. The coplanar sweep must stay green **unwidened**, which is the test of the condition rather than
of the write. And the write itself is gated on relief: a model behind a ridge is occluded by it, a model in
front of the same ridge is not. Pixel verification remains Cycle J's.

---

## Erratum, 2026-08-30 — the mitigation this ADR named is refuted by measurement

This ADR deferred the coplanar hazard to wave 2 and named the mitigation it expected to work: a **shared
nearest-texel rule** against the same padded texture, so the CPU lookup and the GPU fetch would read the
identical value and `GL_GEQUAL` would let the tie pass. Task 14's spike measured it
(`docs/research/2026-08-30-e-terrain-coplanar-depth-spike.md`) and **it does not work, because it fixes the
wrong term.**

A shared texel decides *where ground-relative content sits*. It does not make the content's depth agree
with the ground's, because the two are computed by different pipelines over different geometry. Survivors
of a 102,400-pixel probe on Apple M3 Max at zoom 13, pitch 0:

| approach | vertical error | survivors |
|---|---|---|
| drawn as a second **ground tile** — the ground's own vertex path | 0.000 m | **102,400 / 102,400** |
| shared nearest texel — *this ADR's proposal* | 3.049 m | 61,996 / 102,400 |
| exact ground-surface reconstruction | 0.000 m | 53,048 / 102,400 |
| …plus a matched lattice and the ground's own triangulation | 0.000 m | 50,263 / 102,400 |
| …plus a **1 m lift** | 1.000 m | **102,400 / 102,400** |
| drawn **before** the ground | 0.000 m | **0 / 102,400** |

Two of those rows overturn expectations this ADR encoded. **Reconstructing the ground's surface exactly is
not sufficient** — zero vertical error still loses half the probe, because the disagreement is in the
rasterised depth rather than in the height. And **drawing ground-relative content before the ground is
refuted outright**, at zero survivors under every camera, which was the obvious fallback and is not one.

What works is a **lift**: one metre with the lattice and triangulation matched, four without. `Apple
Software Renderer` through CGL and through EAGL agree with the GPU to within one per cent.

Three consequences the implementing tasks must carry:

- **A CPU lookup must reconstruct the ground's *drawn surface***, not the texel beneath the point — a ground
  cell spans four DEM texels, and interpolation must follow the ground's own NE–SW diagonal. The plan's
  original test, that CPU and GPU "agree on a shared texel", passes against a lookup still 3.049 m wrong.
- **The geometry grid triangulates on the opposite diagonal to the ground**, by accident rather than
  decision, and that alone costs survivors.
- **Labels carry no coplanar hazard at all**: `drawLabels` and `drawIcons` both `disable(GL_DEPTH_TEST)`.

The decision this ADR makes — that the displaced ground writes depth, conditionally — is unaffected. Only
its guess at the wave-2 mitigation was wrong, and it was wrong in the direction that a spike exists to
catch: it sounded principled, and it moved the number that was not the problem.
