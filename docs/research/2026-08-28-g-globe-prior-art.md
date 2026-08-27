# Cycle G prior art — how the field actually builds a globe

Compiled 2026-08-28. This document is **external evidence only**: the source of shipping renderers, their
maintainers' own writing, their issue trackers, and the relevant Khronos specifications. It deliberately
does not read RenG's own source or Rentile's — two sibling documents cover those — and it runs no spike.
Where a number about RenG appears it is quoted from `CLAUDE.md` or an ADR and labelled as such.

Its subject is the globe projection: a second projection mode that re-projects mercator basemap tiles and
every placement onto a sphere. Assume for reading this that RenG is a Kotlin Multiplatform OpenGL ES 3.0
renderer that draws a mercator basemap from **raster** tiles, plus stickers, models and consumer-shaded
quads.

## How to read the source grades

Every claim carries a link and a grade. The grades are about *who is speaking*, not about how much I
believe the claim.

| Grade | Meaning |
|---|---|
| **A** | First-party and authoritative: a Khronos specification, a renderer's own source or developer guide, or a maintainer of that renderer speaking about their own project. |
| **B** | Corroborated: the same symptom or the same mechanism reported independently in more than one project or tracker. |
| **C** | Single uncorroborated report: one thread, one reporter, no maintainer reply. Concrete but weightless on its own. |
| **D** | Verified during this research by fetching and reading the artifact myself, at a named commit or tag. Stated as such. |

Two checkouts back the grade-**D** source claims:

- **MapLibre GL JS** — `github.com/maplibre/maplibre-gl-js`, sparse clone of `src/` and `developer-guides/`
  at commit **`c6e182ede4caadca1cd4c1ca83c7811eb39b90c4`** (2026-08-27). Line numbers below are that
  commit's.
- **Mapbox GL JS** — files fetched from the **`v2.15.0`** tag (the last tag whose `src/` is public in this
  form) and, where noted, from `main`. Mapbox GL JS v3 is not source-available in the same way; every
  Mapbox claim here is either from v2.15.0 source, from a file still present on `main`, from Mapbox's own
  documentation, or from a Mapbox employee in the tracker.
- **CesiumJS** — files fetched from `github.com/CesiumGS/cesium`, `main`, on 2026-08-28.

---

## The three findings most likely to decide the cycle's shape

### 1. Nobody re-tessellates per tile at runtime — the globe is one cached, subdivided grid mesh plus a per-tile uniform, and for *raster* tiles the grid is the densest of any layer type

Both web renderers project **per vertex in the vertex shader**. Neither computes sphere positions on the
CPU per frame. But both also had to add vertices, because a two-triangle quad cannot bend:

> "If we were to draw mercator tiles with globe shaders directly, we would end up with a deformed sphere.
> … if we were to project the vertices of such large triangles to globe directly, we would not get curved
> horizons, lines, etc. For this reason, before a tile is finished loading, its geometry (both polygons and
> lines) is further subdivided."
> — [`developer-guides/globe.md`](https://github.com/maplibre/maplibre-gl-js/blob/main/developer-guides/globe.md), MapLibre GL JS **(A)**

The distinction that matters for RenG is *where* the extra vertices come from. For MapLibre's **vector**
layers they come from re-subdividing earcut output per tile, on the tile-load worker. For anything that
covers a whole tile — **raster included** — they come from a **shared, cached grid mesh**, and the tile's
identity arrives as uniforms:

- MapLibre builds the mesh in `createTileMesh` and caches it by `(granularity, borders, northPole,
  southPole)` — `src/geo/projection/vertical_perspective_projection.ts:75-99` **(D)**. A whole map uses a
  handful of distinct meshes.
- Mapbox goes further: **one** grid vertex buffer for the entire globe, built once in
  `GlobeSharedBuffers._createGrid` (`src/geo/projection/globe_util.js:869-875`, v2.15.0) and handed to every
  tile by `sharedBuffers.getGridBuffers(latitudinalLod, …)`
  (`src/terrain/draw_terrain_raster.js:224-230`) **(D)**. The vertices carry only grid indices; the tile's
  lat/lon rectangle arrives as the `u_grid_matrix` uniform and the shader turns grid coordinates into
  lat/lng and then into ECEF (`src/shaders/globe_raster.vertex.glsl:50-68`) **(D)**.

The densities are explicit and they are not small:

| renderer | mesh for a whole-tile (raster) surface | source |
|---|---|---|
| MapLibre GL JS | base **128** at zoom 0, halved per zoom level, **never below 32** → a 32×32 quad grid from zoom 2 onward, plus a one-quad border ring | `vertical_perspective_projection.ts:20`, `subdivision_granularity_settings.ts:36-39` **(D)** |
| MapLibre GL JS, fill layers | base 128, **minimum 2** | `vertical_perspective_projection.ts:14` **(D)** |
| MapLibre GL JS, line layers | base 512, minimum 0 | `vertical_perspective_projection.ts:15` **(D)** |
| Mapbox GL JS | fixed **64** longitudinal cells; latitudinal cells 64/32/16 chosen by latitude | `globe_constants.ts:25-26`, `globe_util.js:607-614` **(D)** |
| CesiumJS, no terrain | **16×16 vertices** per tile (15×15 quads) from `EllipsoidTerrainProvider` | `packages/engine/Source/Core/EllipsoidTerrainProvider.js:147-153` **(D)** |

MapLibre's minimum is not an accident and the in-source comment names RenG's exact case:

```ts
// Always keep at least some subdivision on raster tiles, etc,
// otherwise they will be visibly warped at high zooms (before mercator transition).
// This si not needed on fill, because fill geometry tends to already be
// highly tessellated and granular at high zooms.
tile: new SubdivisionGranularityExpression(128, 32),
```
— `src/geo/projection/vertical_perspective_projection.ts:16-20` **(D)**, typo in the original

**Why this decides the cycle's shape.** `CLAUDE.md` describes RenG's ground as tiles whose footprints are
quads — `measureLargeQuadRasterisation` "draws the fixture's own four ground-tile footprints" — so if a
ground tile is two triangles today, the field's floor for a raster globe is 32×32×2 = **2,048 triangles
per tile**, or ~1,000×. That is still cheap in absolute terms (a shared 35×35 = 1,225-vertex `Pos` buffer,
reused by every tile), but it changes the shape of `GroundPipeline`: the ground stops being a quad
emitter and becomes a grid emitter with per-tile uniforms. It also lands squarely on the rasteriser the
`0.3.0` failure was about — *more, smaller* triangles is the direction that makes a software rasteriser
happier, not less, so this may cut the other way for `Apple Software Renderer` and llvmpipe. Nobody in the
field has measured that; see "What I could not source".

### 2. Both web renderers clip the far side of the planet by **overwriting `gl_Position.z`** — and that trick is unavailable to any pass that needs a real depth test, which RenG's model pass is

MapLibre states the mechanism and the reason for it in one paragraph:

> "When drawing a planet, we need to somehow clip the geometry that is on its backfacing side. Since
> MapLibre uses the Z-buffer for optimizing transparency drawing, filling it with custom values, we cannot
> use it for this purpose. Instead, we compute a plane that intersects the horizons, and for each vertex we
> compute the distance from this plane and store it in `gl_Position.z`. This forces the GPU's clipping
> hardware to clip geometry beyond the planet's horizon."
> — `developer-guides/globe.md` **(A)**

In shader source, `globeComputeClippingZ` and `interpolateProjection` with the comment
*"Z is overwritten by glDepthRange anyway - use a custom z value to clip geometry on the invisible side of
the sphere"* — `src/shaders/glsl/_projection_globe.vertex.glsl:99-108` **(D)**. The reason MapLibre can
afford this is that its depth values are **not** perspective depth at all: `getDepthModeForSublayer`
allocates each layer/sublayer a constant `glDepthRange` slice,
`depth = 1 - ((1 + currentLayer) * numSublayers + n) * depthEpsilon`
(`src/render/painter.ts:496-500` **(D)**).

The consequence is visible in the API. MapLibre had to ship *two* projection entry points for consumer
shaders: `projectTile`, which replaces Z, and `projectTileFor3D`, described as
*"this variant of the function preserves the Z value of the final vector"*
(`_projection_globe.vertex.glsl:132-145`, `168-171` **(D)**). And a user found the seam between them the
hard way in [maplibre-gl-js#6698](https://github.com/maplibre/maplibre-gl-js/issues/6698) **(B)**, still
open, where a 3D custom layer vanishes around zoom 11 on a globe. The reporter's own table:

| vertex shader | triangle status |
|---|---|
| `gl_Position = projectTile(a_pos);` | disappears at zoom ~10.8+ |
| `projectTileWithElevation(a_pos, 1000.0)` | disappears at zoom ~10.9+ |
| `projectTileWithElevation(a_pos, 2000.0)` | disappears at zoom ~11+ |
| `projectTileFor3D(a_pos, 1000.0)` | always visible |

and their diagnosis: *"the `vertical-perspective` projection will make the clipping plane very close to the
earth's surface, which makes everything on the earth's surface projected to the clipping space's
`gl_Position.z/gl_Position.w` very close to 1 (something like 0.998)."*

The same user reached `renderingMode: '3d'` in the first place to escape
[maplibre-gl-js#6047](https://github.com/maplibre/maplibre-gl-js/issues/6047) **(B, open)**, where the
clipping-plane trick simply **does not work on some Android devices** — the far hemisphere bleeds through.
MapLibre's guide names the cause and its own workaround:

> "However this approach does not work on some phones due to what is likely a driver bug, which applies
> `glDepthRange` and clipping in the wrong order. So additionally, face culling is used for fill and raster
> layers … and line layers … discard beyond-horizon pixels in the fragment shader."
> — `developer-guides/globe.md` **(A)**

#6047 carries a BrowserStack device matrix from the reporter **(C, one reporter, but tabulated)**: Galaxy
S24 and S22 correct, Galaxy S21, Galaxy S20 and **Pixel 9** incorrect, in both Chrome and Firefox, and
identical whether WebGL 1 or WebGL 2 is forced.

**The contrast that matters.** CesiumJS does not have this problem because its globe **writes depth**:
`GlobeSurfaceTileProvider`'s render state is `cull: {enabled: true}`,
`depthTest: {enabled: true, func: LESS}`, with colour and depth written
(`packages/engine/Source/Scene/GlobeSurfaceTileProvider.js:470-492` **(D)**, the comment on the block is
literally `// Write color and depth`). A Cesium billboard on the far side is occluded by the planet the
ordinary way.

**Why this decides the cycle's shape.** `CLAUDE.md` records that ADR 0027 supersedes ADR 0025 so that *no*
map-regime draw writes depth, and ADR 0030 then supersedes ADR 0027 **for the model pass alone** so a mesh
writes depth and can occlude itself. A globe forces that question open again: if the ground does not write
depth, a model on the far side of the planet draws straight through it, and the `gl_Position.z` hijack that
MapLibre uses to fix that is exactly what ADR 0030's model pass cannot do. The three candidate answers —
(a) make the ground write depth, Cesium-style; (b) hijack Z for everything except models and give models a
separate CPU-side or fragment-side horizon test; (c) discard beyond-horizon fragments in the fragment
shader, which MapLibre already does for its lines — are an ADR-level fork, not an implementation detail.

### 3. GLSL ES gives the trigonometric functions **undefined precision**, and the mercator→sphere conversion is nothing but trigonometry. Three separate projects have been bitten; the fix that survived uses only `exp` and rational arithmetic

The specification is unambiguous:

> "Built-in functions defined in the specification with an equation built from the above operations inherit
> the above errors. These include, for example, the geometric functions, the common functions, and many of
> the matrix functions. **Built-in functions not listed above and not defined as equations of the above have
> undefined precision. These include, for example, the trigonometric functions** and determinant."
> — [OpenGL ES Shading Language 3.00 Specification](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf),
> §4.5.1 "Range and Precision", p. 52 **(A, D — fetched and read)**

The same table *does* specify `exp(x)` and `exp2(x)` at `(3 + 2*|x|)` ULP, `log`/`log2` at 3 ULP outside
`[0.5, 2.0]`, division at 2.5 ULP, and `a+b`, `a-b`, `a*b` correctly rounded. So `exp` has a contract and
`sin`, `cos`, `tan`, `atan` have none.

Field consequences, three independent projects:

- **MapLibre / Mali.** [maplibre-gl-js#7419](https://github.com/maplibre/maplibre-gl-js/issues/7419)
  **(A — maintainers in-thread; closed)**, April 2026: *"The y-direction projection has a precision issue at
  zoom around 11 under the globe projection. Some distinct latitude values will overlap."* Reproduced on
  Huawei Nova 13 (Mali-G610) and Oppo Find N2 Flip (Mali-G710); **not** reproduced on Xiaomi 13 (Adreno 740)
  or iPhone 16e (Apple A18). The reporter measured it: *"for Mali-G710 and Mali-G610, the error can vary by
  up to 200m"*, later *"the precision error can be as large as approximately 300 meters"*, with a public
  per-GPU dashboard at <https://webgl-test.lcblue.net/dashboard>.
- **CesiumJS / Intel HD 4600.** The comment block in `ImageryLayer.js` is a maintainer's own five-stage
  post-mortem **(A, D)**:
  > "Over a year later, we noticed (<https://github.com/CesiumGS/cesium/issues/2110>) that our reprojection
  > code was creating a rare but severe artifact on some GPUs (Intel HD 4600 for one). The problem was that
  > the GLSL sin function on these GPUs had a discontinuity at fine scales in a few places. We solved this by
  > implementing a more reliable sin function based on the CORDIC algorithm
  > (<https://github.com/CesiumGS/cesium/pull/2111>). … Unfortunately, on some GPUs, the performance was
  > absolutely terrible (<https://github.com/CesiumGS/cesium/issues/2258>)."
  > — `packages/engine/Source/Scene/ImageryLayer.js:1490-1524`
- **MapLibre / mediump.** [maplibre-gl-js#6916](https://github.com/maplibre/maplibre-gl-js/issues/6916)
  **(A/B)** — the globe atmosphere renders nowhere on Android. Root cause found by the reporter:
  *"The calculation within the atmosphere's fragment shader requires highp floats, but due to the prelude
  … the float precision is mediump by default. The iOS does not have the problem because mediump on iOS is
  already 32 bits."* Fixed by
  [#6939](https://github.com/maplibre/maplibre-gl-js/pull/6939), which is one `precision highp float;` line
  and whose PR body says *"on an Android device with **16bit mediump float**"*. The spec's mediump floor is
  relative precision `2^-10` over range `(−2^14, 2^14)` (GLSL ES 3.00 §4.5.1, p. 53 **(A, D)**), so a
  conforming implementation may legally give you ten bits.

The fix MapLibre landed removes the trig entirely. `projectToSphere` now computes `sin(spherical_y)` and
`cos(spherical_y)` from `t = exp(PI - mercator_y*2*PI)` by the tangent half-angle identities:

```glsl
float t = exp(PI - (mercator_pos.y * PI * 2.0));
float t2 = t * t;
float denom = t2 + 1.0;
float sin_sy = (t2 - 1.0) / denom;
float cos_sy = (2.0 * t) / denom;
```
— `src/shaders/glsl/_projection_globe.vertex.glsl:71-75` **(D)**

with the in-source rationale that the direct form *"subtracts PI/2 from a value near PI/2, destroying most
float32 mantissa bits near the equator, and some GPUs (e.g. Mali) have imprecise atan/sin/cos besides"*
(same file, `:61-70`). The change also **deleted** MapLibre's previous runtime correction — a mechanism
described in the guide as rendering *"a 1x1 pixel framebuffer once a second, comparing it against a
CPU-computed reference"* and shifting the projection matrix to compensate — because *"keeping a correction
calibrated for an error class that no longer exists would just add a spurious offset"*
([#7419 comment](https://github.com/maplibre/maplibre-gl-js/issues/7419), `clement-igonet`, 2026-07-12
**(A)**).

Note that `spherical_x` still goes through `sin`/`cos` (`:77-81`), and the maintainers know it. `kubapelc`,
who wrote the original: *"The atan error is also not monotonic and can also be in the range of hundreds of
meters, but it changes very gradually… a single correction factor is good enough. Maybe there is still hope
that this approach could work for `sin` as well?"* **(A)**.

**Why this decides the cycle's shape.** RenG's only real-device GL evidence on Android is an Adreno 830
(`docs/research/2026-08-27-h-android-gles-device-spike.md`, per `CLAUDE.md`) — the *same vendor family that
was fine* in #7419. Mali is unmeasured on this project, on any target. A globe is the first RenG feature
whose correctness depends on a shader function the specification refuses to bound, on the platform family
RenG has least coverage of. That argues for two things at plan time: keep the latitude conversion on the
`exp`-only path from the first commit rather than after the first bug report, and extend the existing
"measure the driver rather than name it" discipline — the one that produced `measureLargeQuadRasterisation`
— to a transcendental-accuracy probe. MapLibre's deleted `ProjectionErrorMeasurement` is a working
precedent for exactly that probe, and RenG already has the analytical-readback machinery it would need.

---

## Question by question

### Q1 — How MapLibre GL JS and Mapbox GL JS implement globe

**Both project per-vertex in the vertex shader. Neither re-tessellates positions on the CPU per frame.
Both add vertices, and where those vertices come from differs by layer type.**

**MapLibre.** The projection is three steps — *"compute angular spherical coordinates from source web
mercator tile data; convert spherical coordinates to a 3D vector - a point on the surface of a unit sphere;
project the 3D vector using a common perspective projection matrix"* — and *"Geometry is projected to the
sphere in the vertex shader"* (`developer-guides/globe.md` **(A)**). Concretely, `projectToSphere` takes a
position in tile-local `0..EXTENT` (EXTENT = 8192), converts it to mercator `0..1` with the per-tile
uniform `u_projection_tile_mercator_coords`, and returns a unit-sphere vector
(`_projection_globe.vertex.glsl:54-93` **(D)**). Every shader that draws map content calls `projectTile`,
whose implementation is *"automatically injected into the shader source code"* depending on the active
projection — so most shaders are byte-identical between mercator and globe.

The globe is a **unit sphere** in the shader; scale lives entirely in the matrix. Elevation enters as
`spherePos * (1.0 + elevation / GLOBE_RADIUS)` with `GLOBE_RADIUS 6371008.8` metres
(`_projection_globe.vertex.glsl:1`, `:105` **(D)**).

CPU subdivision is separate and happens once per tile at load, for vector geometry only:
*"Currently the fastest approach seems to be taking the output geometry from `earcut` and subdividing that
further"*, producing *"a square grid"*, with the warning *"beware that it is very prone to subtle errors,
resulting in single-pixel seams"* (`developer-guides/globe.md` **(A)**). Granularity is
`max(floor(base / 2^zoom), min, 1)` (`subdivision_granularity_settings.ts:36-39` **(D)**), and the ceiling
is chosen against a hardware limit: *"The maximal subdivision granularity of 128 for fill layers is enough
to get nicely curved horizons, while also not generating too much new geometry and not overflowing the 16
bit vertex indices used throughout MapLibre."* `createTileMesh` enforces that literally — it throws
`'Granularity is too large and meshes would not fit inside 16 bit vertex indices.'`
(`src/util/create_tile_mesh.ts:135-137` **(D)**).

**Mapbox.** Same per-vertex principle, different plumbing. The grid vertex buffer holds only grid indices;
`u_grid_matrix` is a `mat3` carrying the tile's lat/lng rectangle, and the shader does
grid → lat/lng → mercator UV → ECEF in one pass
(`src/shaders/globe_raster.vertex.glsl:50-68`, v2.15.0 **(D)**). Three of that matrix's nine slots are
stolen for scalars, with the comment *"The 3rd row of `u_grid_matrix` is only used as a spare space to pass
the following 3 uniforms to avoid explicitly introducing new ones."* Positions are then transformed by
`u_globe_matrix`, which is built per tile on the CPU in **`Float64Array`** and downcast to `Float32Array`
exactly once (`globe_util.js:499-514`, `:545-548` **(D)**).

Neither renderer does CPU-side re-tessellation of *positions* per frame. The only per-frame CPU geometry
work either does is choosing which cached mesh / which LOD to bind.

### Q2 — The transition problem: why neither stays on a globe at all zooms

**The two projects give two different reasons, and both are load-bearing. RenG should hear both before
deciding it needs a transition at all.**

**MapLibre's reason is float32 precision, stated with the arithmetic (A):**

> "Shaders work with 32 bit floating point numbers (64 bit are possible on some platforms, but very slow).
> The 23 bits of mantissa and 1 sign bit can represent at most around 16 million values, but the
> circumference of the earth is roughly 40 000 km, which works out to about one float32 value per 2.5
> meters, which is insufficient for a map. Thus if we were to use globe projection at all zoom levels, we
> would unsurprisingly encounter precision issues. To combat this, globe projection automatically switches
> to mercator projection around zoom level 12."
> — `developer-guides/globe.md`

**Mapbox's reason, from a Mapbox engineer, is cartographic (A):**

> "For larger values of zoom, it intentionally transits to Mercator projection because, for larger zoom
> levels you should have a projection that keeps angles, which is important for navigation."
> — `underoot`, [mapbox-gl-js#13395](https://github.com/mapbox/mapbox-gl-js/issues/13395), 2025

**A third, independent confirmation of the same numeric ceiling.** deck.gl's `GlobeView` documentation lists
among its limitations: *"No high-precision rendering at high zoom levels (> 12). Features at the city-block
scale may not be rendered accurately."* — [deck.gl GlobeView docs](https://deck.gl/docs/api-reference/core/globe-view)
**(A)**. Three projects, one number, arrived at separately.

**The thresholds are hard-coded and differ by a factor of ~60 in scale.**

- Mapbox: `GLOBE_ZOOM_THRESHOLD_MIN = 5`, `GLOBE_ZOOM_THRESHOLD_MAX = 6`, blended with
  `smoothstep(min, max, zoom)` — `src/geo/projection/globe_constants.ts:5-6` and
  `globe_util.js:541-542` **(D)**. Still those values on `main` as of this research.
- MapLibre: the transition is a **style property**, not a constant. The `"globe"` preset expands to
  `["interpolate", ["linear"], ["zoom"], 10, "vertical-perspective", 12, "mercator"]` —
  [maplibre-style-spec `docs/types.md`](https://github.com/maplibre/maplibre-style-spec/blob/main/docs/types.md)
  **(A, D via code search)**. `GlobeProjection.transitionState` reads it and returns a scalar 0..1
  (`src/geo/projection/globe_projection.ts:33-52` **(D)**), which becomes the `_globeness` the shader sees.

**How the blend is done.** Both blend in the shader, and both blend *world position*, not the projection
matrix:

- MapLibre computes both a globe clip position and a mercator fallback clip position and mixes:
  `result.xyw = mix(flatPosition.xyw, globePosition.xyw, u_projection_transition);`
  — `_projection_globe.vertex.glsl:116-122` **(D)**. Z is treated specially: the horizon-clip Z is faded in
  only over the last 80% (`z_globeness_threshold = 0.2`), and the pole vertices are hidden until the last
  2% (`poles_hidden_anim_percentage = 0.02`, with an eighth-power ramp) — same file, `:119-128`.
- Mapbox mixes ECEF and camera-relative mercator world positions directly:
  `vec4 interpolated_pos = vec4(mix(globe_world_pos.xyz, merc_world_pos.xyz, u_zoom_transition), 1.0);`
  — `src/shaders/globe_raster.vertex.glsl:100-109` **(D)**.

**The transition band is where the bugs live, and they are long-lived.** All of these are Mapbox issues
whose reproduction is *specifically* "between zoom 5 and 6":

- [#12645](https://github.com/mapbox/mapbox-gl-js/issues/12645) **(B, open since 2023)** — line labels,
  point labels with variable anchors, and symbols with `text-variable-anchor` + `icon-text-fit` are not
  rendered between `GLOBE_ZOOM_THRESHOLD_MIN` and `GLOBE_ZOOM_THRESHOLD_MAX`.
- [#13065](https://github.com/mapbox/mapbox-gl-js/issues/13065) **(B, closed but with years of "any
  update?" comments)** — marker icon and its click box diverge between zoom 5 and 6, *"more pronounced the
  bigger the screen is"*, making markers unclickable. One commenter reports that paid Mapbox support
  redirected them to the issue.
- [#12100](https://github.com/mapbox/mapbox-gl-js/issues/12100) — symbols disappear for ~200 ms on repaint
  when zooming out from the threshold.

**What this means for RenG, stated as a question rather than an answer.** The field's reason for a
transition is a *cartographic* argument (Mapbox) and a *float32-in-the-shader* argument (MapLibre). The
float32 argument is about representing a whole-planet coordinate in a shader; it is not automatically
RenG's argument, because RenG's tile-local coordinates and per-tile matrices are the same on either
projection, and because RenG could keep the mercator path for the ground and only curve it. But the
cartographic argument does apply to any consumer drawing a north-up navigation view. And the evidence above
says that whatever else a transition buys, it also buys a permanent class of "wrong between zoom X and Y"
bugs in symbol placement and hit-testing — a class RenG would own for stickers and models.

### Q3 — The pole problem

**Web mercator's tiles stop at ±85.051129°, and every implementation surveyed draws the polar cap by
extending the top and bottom row of tiles to the pole and stretching that tile's edge texels over it.
Nobody fetches or synthesises polar data.**

**Mapbox.** `POLE_RAD = degToRad(85.0)` (`globe_util.js:693` **(D)**). `GlobeSharedBuffers._createPoles`
builds, for every zoom level `0..GLOBE_ZOOM_THRESHOLD_MIN`, a triangle fan of `GLOBE_VERTEX_GRID_SIZE`
(64) primitives: a tip vertex at the pole with UV `(0.5, 0)` for north and `(0.5, 1)` for south, and 65 rim
vertices at latitude ±85° with UV `(i/64, 0)` or `(i/64, 1)` (`globe_util.js:877-909` **(D)**). The draw
loop's own comment says what the colour is:

```js
// Fill poles by extrapolating adjacent border tiles
const {x, y, z} = coord.canonical;
const topCap = y === 0;
const bottomCap = y === (1 << z) - 1;
```
— `src/terrain/draw_terrain_raster.js:241-244` **(D)**

The cap is drawn with the border tile's own texture bound and `CullFaceMode.disabled` (same file, `:259`),
rotated into place by `globePoleMatrixForTile`, which is *"Rotate the pole triangle fan to the correct
location"* by `(x / numTiles - 0.5) * 2π` (`globe_util.js:550-558` **(D)**). The south cap reuses the north
matrix scaled by `[1, -1, 1]`.

**MapLibre.** Same idea, implemented through sentinel vertex coordinates rather than a separate buffer.
`NORTH_POLE_Y = -32768` and `SOUTH_POLE_Y = 32767` (`src/render/subdivision.ts:24-25` **(D)**) — the
extremes of the `Int16` vertex format, outside the legal `0..EXTENT` range, so they cannot collide with real
geometry. The shader recognises them:

```glsl
// North pole
if (rawPos.y < -32767.5) { pos = vec3(0.0, 1.0, 0.0); }
// South pole
if (rawPos.y > 32766.5)  { pos = vec3(0.0, -1.0, 0.0); }
```
— `_projection_globe.vertex.glsl:83-90` **(D)**

and the raster shader gives them the edge texel:

```glsl
#ifdef GLOBE
if (a_pos.y < -32767.5) { texturePos.y = 0.0; }
if (a_pos.y > 32766.5)  { texturePos.y = 1.0; }
#endif
```
— `src/shaders/glsl/raster.vertex.glsl:42-52` **(D)**

For whole-tile meshes the extra row is generated in `createTileMesh` when
`extendToNorthPole: tileID.y === 0` / `extendToSouthPole: tileID.y === (1 << tileID.z) - 1`
(`create_tile_mesh.ts:104-109`, `:154-160` **(D)**). For vector geometry, `Subdivider._fillPoles` walks the
subdivided index buffer, finds every edge whose two vertices sit exactly on the tile's north or south edge,
and emits a quad from that edge to the pole with the correct winding
(`src/render/subdivision.ts:523-563` **(D)**). Before that runs, `_ensureNoPoleVertices` shifts any real
vertex that happens to land on the sentinel Y by one unit, *"for removing unintended pole vertices that
might have been created during subdivision"* (same file, `:465-479`).

**Two consequences the field ran into and recorded.**

1. **Culling volumes must be widened to include the pole**, or the cap gets frustum-culled with its tile:
   *"Special handling of poles - we need to extend the tile AABB to include the pole for tiles that border
   mercator north/south edge"* — `src/geo/projection/globe_covering_tiles_details_provider.ts:163-170` **(D)**.
2. **The pole is a discontinuity for camera control, not only for geometry.** MapLibre's guide: *"there are
   other edge cases, such as when looking at the planet's poles and trying to zoom in to a location that is
   on the other hemisphere ('behind the pole'). MapLibre does not support moving the camera across poles, so
   instead we need to rotate around."* **(A)**. The tracker corroborates: `#8003` *"Globe: pan/drag
   direction feels inverted and unstable near the poles"*, `#8154` *"pan with a versor so dragging stays
   consistent near the poles"*, `#8182` *"do not zoom in when panning the globe off a pole at min zoom"*,
   `#8217` *"solve the drag in closed form on the reachable band"*, and the still-open `#5868` — four merged
   fixes and one open PR, all about poles and camera control **(B)**. RenG does not own a camera controller
   (the consumer supplies a `Camera` in the `FramePlan`), so this cost falls on RenG's *consumers* rather
   than on RenG — which is worth saying out loud in the design, because it is a real cost that RenG would be
   exporting.

Also worth noting for scoping: the visible-pole problem only exists below the transition zoom. Mapbox
builds pole fans for zoom `0..4` only (`globe_util.js:890`), because above `GLOBE_ZOOM_THRESHOLD_MIN` the
projection is on its way to mercator anyway.

### Q4 — The antimeridian

**On a sphere the antimeridian problem mostly disappears, because the geometry is periodic by construction.
What is left is one specific artefact at zoom 0, and it took MapLibre four attempts to fix.**

**Duplicated world copies vanish.** MapLibre's mercator transform returns one `UnwrappedTileID` per visible
world copy, walking `w0-1 .. w1+1` (`src/geo/projection/mercator_transform.ts:300-321` **(D)**). The globe
transform returns exactly one, with a one-line comment:

```ts
getVisibleUnwrappedCoordinates(tileID: CanonicalTileID): UnwrappedTileID[] {
    // Globe has no wrap.
    return [new UnwrappedTileID(0, tileID)];
}
```
— `src/geo/projection/vertical_perspective_transform.ts:507-509` **(D)**

and `GlobeCoveringTilesDetailsProvider.allowWorldCopies()` returns `false`
(`globe_covering_tiles_details_provider.ts:104-106` **(D)**).

**What replaces it is wrap-aware distance.** Tile-to-camera distance is computed with X wrapping and, at the
poles, with Y mirrored and X shifted by half a world:
*"Handles distances on a sphere correctly: X is wrapped when crossing the antimeridian, when crossing the
poles Y is mirrored and X is shifted by half world size"*
(`globe_covering_tiles_details_provider.ts:56-78` **(D)**), plus a `getWrap` that picks the tile copy nearest
the camera so *"tiles will remain loaded when crossing the antimeridian"* (same file, `:80-98`).

**The one real artefact, and its history.** Tile sources pad geometry into a buffer beyond the tile edge.
Under globe that buffer wraps around the planet. From the merged fix's own root-cause section **(A)**:

> "Under globe projection the mapping of in-tile coordinates to the sphere is periodic in X, so buffer
> geometry past +/-180 degrees wraps around the planet: At zoom 1+ it lands inside a *different* tile's
> stencil clipping mask and is discarded… The zoom 0 tile, however, covers the whole world. Its own buffer
> wraps around and lands back on the tile itself, inside its own stencil mask. The original geometry and its
> +/-360 degrees copy occupy the same pixels within the same tile, so screen-space stencil clipping cannot
> distinguish them, both are blended, and features render twice."
> — [maplibre-gl-js#7896](https://github.com/maplibre/maplibre-gl-js/pull/7896), merged 2026-07

The fix is a fragment-shader discard, enabled only for `canonical.z === 0`:

```glsl
void clipAntimeridian() {
    if (u_projection_clip_antimeridian && (v_projection_tile_x < 0.0 || v_projection_tile_x >= 8192.0)) {
        discard;
    }
}
```
— `src/shaders/glsl/_projection_globe.fragment.glsl:8-11` **(D)**; enabled at
`vertical_perspective_transform.ts:303` **(D)**

That PR *"replaces the earlier stencil and data-level attempts (#6970, #7528, maplibre/geojson-vt#112)"*
and closes [#6248](https://github.com/maplibre/maplibre-gl-js/issues/6248) **(A)** — so this is the fourth
attempt at one bug, and the first three were at the wrong layer.

**Related, still open:** [#7672](https://github.com/maplibre/maplibre-gl-js/issues/7672) **(C)** — a severe
FPS collapse under globe with pitch, zoom > 5, and a bearing pointing across the antimeridian, with the
camera parked. And [#5379](https://github.com/maplibre/maplibre-gl-js/issues/5379) **(C)** — a tile blinks
when the camera approaches the north pole from a particular longitude, i.e. `getWrap` churn.

**What this means for RenG's unwrapped longitude.** RenG's camera carries a deliberately *unwrapped*
longitude. On a sphere that value cannot address geometry — `sin`/`cos` are periodic, so lng and lng+360
name the same point, and no world-copy machinery exists to disambiguate them. Two concrete implications:

1. **Unwrapped longitude becomes purely a camera-animation quantity** on the globe: it says which way and
   how far to rotate, and must be reduced mod 360 before it reaches any geometry. That is a *simplification*
   relative to mercator, not a complication — MapLibre deleted an entire code path here.
2. **It must be reduced before it reaches a shader.** Mapbox keeps `lng` in a `Float64Array` matrix
   rotation, `mat4.rotateY(m, m, degToRad(-lng))` (`globe_util.js:507` **(D)**), so a large unwrapped value
   is fine on the CPU in double. Pushing a large angle into a GLSL `sin`/`cos` is a different matter: the
   spec grants those functions no accuracy at all (see finding 3 above), and argument reduction is exactly
   where implementations cut corners. Reduce on the CPU, in double, before it ever becomes a `float`.

### Q5 — Depth and floating-point precision at planetary scale

**Three separate problems get conflated under this heading. RenG's reverse-Z addresses one of them, and
only partially on the APIs RenG uses.**

**(a) Position precision — coordinates too large for float32.** The canonical numbers: MapLibre's guide
computes *"about one float32 value per 2.5 meters"* across the earth's circumference **(A)**. Nathan Reed
makes the same point about depth. The field's answers, all present in shipping source:

- **Per-tile origin rebasing.** Mapbox normalises each tile's ECEF AABB into a **15-bit** range —
  `GLOBE_NORMALIZATION_BIT_RANGE = 15`, `GLOBE_NORMALIZATION_MASK = (1 << 14) - 1` — with
  `globeNormalizeECEF` / `globeDenormalizeECEF` built in `Float64Array` and composed into the tile matrix
  before a single `Float32Array.from` (`globe_util.js:466-484`, `:545-548` **(D)**). Vertices are int16
  grid indices; nothing large ever reaches the GPU.
- **Camera-relative world space.** Mapbox's mercator half of the blend is explicitly camera-relative:
  `merc_world_pos.xy -= u_merc_center; merc_world_pos.x = wrap(merc_world_pos.x, -0.5, 0.5);`
  (`globe_raster.vertex.glsl:103-105` **(D)**). MapLibre's globe matrix rotates the planet under a fixed
  camera rather than moving the camera (`vertical_perspective_transform.ts:306-370` **(D)**).
- **Split-double emulation on the GPU (GPU RTE).** CesiumJS encodes every position as a `high`/`low` float
  pair and differences them against the camera's encoded position in the vertex shader:
  `czm_translateRelativeToEye(high, low)`
  ([`Shaders/Builtin/Functions/translateRelativeToEye.glsl`](https://github.com/CesiumGS/cesium/blob/main/packages/engine/Source/Shaders/Builtin/Functions/translateRelativeToEye.glsl) **(A, D)**),
  backed by `EncodedCartesian3` **(A, D)**, splitting at 65536. The technique traces to AGI's *"Precisions,
  Precisions"*, which both files cite by name. That shader also carries a defensive note worth reading
  twice: *"This check handles the case when NaN values have gotten into `highDifference`. Such a thing
  could happen on devices running iOS."*

**Neither web renderer uses GPU RTE**, because per-tile normalisation already keeps every coordinate small.
That is the cheaper answer and it is the one that fits a tiled renderer.

**(b) Depth precision — z-fighting across a planetary depth range.** This is where reverse-Z lives.

- **Reverse-Z's benefit is mostly a *float depth buffer* benefit.** Nathan Reed: *"the quasi-logarithmic
  distribution of floating-point somewhat cancels the 1/z nonlinearity, giving us similar precision at the
  near plane to an integer depth buffer, and vastly improved precision everywhere else"*, and his
  recommendation is *"just use a floating-point depth buffer with reversed-Z! And if you can't use a
  floating-point depth buffer, you should still use reversed-Z."*
  — [Depth Precision Visualized](https://www.reedbeta.com/blog/depth-precision-visualized/), also published
  as [Visualizing Depth Precision](https://developer.nvidia.com/blog/visualizing-depth-precision/) on
  NVIDIA's blog **(A)**.
- **And OpenGL's default `[-1, 1]` NDC depth range destroys most of it.** Same source, verbatim:
  *"with floating-point, all the precision is stuck uselessly in the middle. (The value gets mapped into
  [0, 1] for storage in the depth buffer later, but that doesn't help, since the initial mapping to [−1, 1]
  has already destroyed all the precision in the far half of the range.)"* and *"in desktop OpenGL you can
  fix this with the widely-supported ARB_clip_control extension (now also core in OpenGL 4.5 as
  glClipControl). **Unfortunately, in GL ES you're out of luck.**"* **(A)**
- **The clip-control situation, checked against the registry (D).** `ARB_clip_control` is an OpenGL 4.5-era
  extension (its revision history reads *"Prepare spec for OpenGL 4.5"*,
  [OpenGL-Registry `ARB_clip_control.txt`](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/ARB/ARB_clip_control.txt)).
  `EXT_clip_control` does exist for ES — *"OpenGL ES Extension #290"*, dated 2017, *"Requires OpenGL ES
  2.0"*, *"a port of GL_ARB_clip_control to OpenGL ES"*
  ([`EXT_clip_control.txt`](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/EXT/EXT_clip_control.txt))
  — so Reed's 2015 "out of luck" is now "an extension, not core in ES 3.0". It is **not** in ES 3.0, so a
  renderer that targets ES 3.0 baseline cannot assume it.

  **What that means for RenG, stated carefully.** `CLAUDE.md` and ADR 0025 record that RenG already uses
  reverse-Z: depth cleared to 0 and `GL_GEQUAL`. Against the sources above, that buys the ordering
  robustness and the near-plane behaviour of reverse-Z, but it does **not** buy the headline precision win
  unless *both* (i) the depth attachment is a float format (`GL_DEPTH_COMPONENT32F` is core in both GL 3.0
  and GLES 3.0, so it is available on every RenG target) **and** (ii) clip space is `[0, 1]` rather than
  `[-1, 1]`. macOS caps at OpenGL 4.1 — `CLAUDE.md` records the harness reporting
  `Apple M3 Max | 4.1 Metal - 90.5` — which is below `glClipControl`'s 4.5. Whether Apple exposes
  `GL_ARB_clip_control` as an extension at 4.1, and whether any RenG GLES target exposes
  `GL_EXT_clip_control`, are one `glGetString(GL_EXTENSIONS)` read each and are **not** answered here. I
  could not find a primary source either way; see "What I could not source".
- **The alternative the planetary-rendering field actually shipped is a logarithmic depth buffer.** Brano
  Kemen, Outerra, 2009: the OpenGL form is `z = (2*log(C*w + 1) / log(C*Far + 1) - 1) * w`, and
  *"Along with the better utilization of z-value space it also (almost) gets us rid of the near clip plane"*
  — [Logarithmic Depth Buffer](https://outerra.blogspot.com/2009/08/logarithmic-z-buffer.html) **(A)**. Its
  two documented failure modes are both about interpolation:
  *"the depth is interpolated linearly and not logarithmically"* (2009), fixed by writing depth per fragment,
  and, from the 2013 follow-up
  ([Logarithmic depth buffer optimizations & fixes](https://outerra.blogspot.com/2013/07/logarithmic-depth-buffer-optimizations.html)
  **(A)**), *"for values smaller than or equal to 0 the log function is undefined. In cases when one vertex
  of the triangle lies further behind the camera (≤ -1), this causes a rejection of the whole triangle even
  before the triangle is clipped"*, fixed by `max(1e-6, 1.0 + gl_Position.w)`. The cost is stated plainly:
  *"Writing fragment depth disables several depth buffer optimizations."*
- **Cesium's numbers for the same trade.** Cesium moved from three frustums (1–1,000 m, 1,000–1,000,000,
  1,000,000–1,000,000,000) to a single logarithmic frustum with near 0.1 and far 1.0e8; on their Mount
  Everest scene, 137 draw calls including 26 duplicates became 111 with no duplicates. The catch is the
  same one Outerra names: large triangles must write `gl_FragDepth`, which *"disables the early depth test
  optimization in GPUs"*, so browsers without `EXT_frag_depth` fall back to the multi-frustum —
  [Hybrid Multi-Frustum Logarithmic Depth Buffer](https://cesium.com/blog/2018/05/24/logarithmic-depth/)
  **(A)**.

**(c) The web renderers dodge (b) entirely, and RenG cannot dodge it the same way.** MapLibre does not have
a planetary depth-precision problem because it never uses perspective depth for the map: each layer and
sublayer gets a constant `glDepthRange` slice (`painter.ts:496-500` **(D)**), and 3D layers get whatever
range is left over (`painter.ts:594`). That is precisely why the `gl_Position.z` horizon hack in finding 2
is available to them at all. RenG's map regime has three depth phases and a model pass that writes real
depth (ADR 0030, per `CLAUDE.md`), which puts RenG on Cesium's side of this line rather than MapLibre's.

**Reverse-Z, restated as what it already solves and what it does not.** It solves the *distribution* problem
— with a float depth attachment and `[0,1]` clip, near-plane precision is no longer catastrophically
over-served. It does not solve: coordinate precision in the vertex shader (that is (a), and the answer is
per-tile rebasing); the `sin`/`cos`/`atan` accuracy problem (finding 3, which is upstream of depth
entirely); or the ordering-versus-occlusion policy question that a globe forces open (finding 2).

### Q6 — Backface and horizon culling, and what it does to non-tile content

**Three mechanisms are in use, and they are complementary rather than alternative.**

1. **Backface culling of the tile mesh.** Cheapest, and everyone does it. MapLibre draws raster with
   `CullFaceMode.backCCW` (`src/webgl/draw/draw_raster.ts:148` **(D)**), and its guide notes the
   prerequisite: *"earcut does not result in consistent winding order, this is ensured during subdivision"*
   **(A)**. Mapbox uses `CullFaceMode.backCCW` for the grid but `CullFaceMode.disabled` for the pole fans
   (`draw_terrain_raster.js:228`, `:259` **(D)**). Cesium sets `cull: {enabled: true}` on the globe
   (`GlobeSurfaceTileProvider.js:474-476` **(D)**) with a `backFaceCulling` flag that is disabled when the
   camera is underground or the globe is translucent (same file, `:128`, `:1460-1470`).
2. **A horizon plane test.** MapLibre's `_computeClippingPlane` derives the plane through the tangent points
   from the camera to the sphere, in a coordinate space where the globe radius is 1, and rotates it by
   bearing, latitude and longitude (`vertical_perspective_transform.ts:306-370` **(D)**, with an ASCII
   diagram of the construction in-source). The same plane is then used three ways: as `gl_Position.z` in the
   shader; as `isSurfacePointVisible(p) = dot(plane.xyz, p) + plane.w >= 0` on the CPU
   (same file, `:869-874`); and per projected symbol as an `isOccluded` flag (`:419-437`).
3. **Fragment discard**, for geometry whose winding cannot be trusted (MapLibre's line layers) and as the
   fallback where the `gl_Position.z` trick fails on mobile drivers — `developer-guides/globe.md` **(A)**.

**What happens to non-tile content on the far side.** This is where the answers diverge sharply, and the
divergence is architectural rather than aesthetic.

- **CesiumJS: the depth buffer does it.** The globe writes depth, so a billboard or a model behind the
  planet is occluded like anything else (`GlobeSurfaceTileProvider.js:470-492` **(D)**).
- **MapLibre: an explicit CPU test with a *soft* result.** DOM markers and popups are not GL content at all,
  so they get `transform.isLocationOccluded(lngLat)` and an opacity swap:
  `const targetOpacity = occluded ? this._opacityWhenCovered : this._opacity;`
  (`src/ui/marker.ts:706-714`; popup at `src/ui/popup.ts:285` **(D)**). Note the design choice: a covered
  marker is *dimmed*, not hidden, and `opacityWhenCovered` is a public option.
- **MapLibre: GL symbols get the same plane test per anchor**, folded into projection —
  `projectTileCoordinates` returns `{point, signedDistanceFromCamera, isOccluded}`
  (`vertical_perspective_transform.ts:419-437` **(D)**).

**Two traps recorded in the field.**

- **The horizon-Z hack is not portable.** [#6047](https://github.com/maplibre/maplibre-gl-js/issues/6047)
  **(B, open)**, with a device matrix showing Galaxy S21, S20 and Pixel 9 failing while S24 and S22 pass,
  identical under WebGL 1 and 2. The maintainer's reply — *"This sounds more like a chrome bug than maplibre
  bug as the same code runs on both browsers"* — is honest and is also the end of the thread's progress.
- **Escaping it costs you the other end.** Switching to `renderingMode: '3d'` to get real depth is exactly
  what produced [#6698](https://github.com/maplibre/maplibre-gl-js/issues/6698) in finding 2.

**RenG-specific note, flagged as mine rather than the field's.** MapLibre's soft answer (dim the covered
marker) exists because its markers are DOM elements outside the GL pipeline. RenG's stickers are GL
content in a `SCREEN`/`MAP` regime split, so the equivalent decision is sharper: a `MAP`-anchored sticker
whose anchor is beyond the horizon must be *culled*, and the natural place for the test is wherever RenG
already resolves `Placement` — a CPU-side `dot(plane, spherePos) >= 0`, which is three multiplies and two
adds per sticker and needs no shader change at all.

### Q7 — Raster tiles specifically: what changes when the input is an already-rasterised square image

**This is the question with the least prior art and the most concrete answers, because two of the three
renderers surveyed do exactly this and their code says why each line exists.**

**A raster tile becomes a subdivided, textured patch, and the UVs stay linear.** This is the crucial
simplification and it is easy to miss. Because a web mercator raster tile's *texture* is parameterised by
mercator x/y, and the subdivision grid is also uniform in mercator x/y, the mapping from grid cell to texel
is **linear** — all the nonlinearity is in the vertex *position*. MapLibre's raster vertex shader is
therefore almost unchanged between projections: `texturePos` is just `fractionalPos`
(`raster.vertex.glsl:40`, with `u_buffer_scale` a constant 1 — `src/webgl/program/raster_program.ts:67-71`
**(D)**), and only `gl_Position = projectTile(position, position)` differs.

That is *not* Cesium's situation, and the difference is instructive. Cesium's globe surface tiles use a
geographic tiling scheme, so web mercator imagery does not line up, and Cesium either (i) carries a
per-vertex `webMercatorT` attribute when the tile is inside `WebMercatorProjection.MaximumLatitude`
(`ImageryLayer.js:724-727`, `:1068` **(D)**), or (ii) reprojects the texture on the GPU. **RenG is in
Cesium's fast case by construction**, because its tiles and its sphere parameterisation share mercator. Any
prior-art conclusion that starts "you will need to reproject the imagery" is transplanted from an assumption
RenG does not share.

**What breaks is the tile *edges*, and there are four distinct mechanisms in use.**

1. **A border ring of quads, drawn in a second pass gated by stencil (MapLibre).** The reasoning is in the
   source, verbatim **(D)**:

   > "When rendering globe (or any other subdivided projection), two passes are needed. Subdivided tiles
   > with different granularities might have tiny gaps between them. To combat this, tile meshes for globe
   > have a slight border region. However tiles borders will overlap, and a part of a tile often gets hidden
   > by its neighbour's border, which displays an ugly stretched texture. To both hide the border stretch and
   > avoid tiny gaps, tiles are first drawn without borders (with gaps), and then any missing pixels (gaps,
   > not marked in stencil) get overdrawn with tile borders. This approach also avoids pixel shader overdraw,
   > as any pixel is drawn at most once."
   > — `src/webgl/draw/draw_raster.ts:55-62`

   Mechanically: `stencilConfigForOverlapTwoPass` returns two stencil-mode maps, high values for the
   borderless pass and low for the bordered pass, both `func: gl.GREATER` with `gl.REPLACE`
   (`src/render/painter.ts:447-480` **(D)**); pass one draws `useBorder=false`, pass two `useBorder=true`
   (`draw_raster.ts:70-72`). The border extends `EXTENT_STENCIL_BORDER = EXTENT / 128` = 64 tile units, i.e.
   1/128 of a tile, ≈ 2 px of a 256-px tile (`create_tile_mesh.ts:13`, `:148-160` **(D)**). Those border
   vertices produce texture coordinates outside `[0,1]`, and the texture is bound
   `gl.CLAMP_TO_EDGE` (`draw_raster.ts:121`), which is exactly the *"ugly stretched texture"* the comment
   names. The guide's summary: *"Tile are drawn as subdivided meshes instead of simple quads, and the
   curvature near the edges can cause seams, especially in cases when two tiles of different zoom levels are
   next to each other."* **(A)**
2. **Half-a-pixel of geometric padding (Mapbox).** `getGridMatrix` multiplies in a `padMatrix` for every
   tile above zoom 0, with the comment *"Add slight padding to patch seams between tiles. This is done by
   extruding vertices by a fixed amount. Pixel padding is first converted to degrees and then to tile units"*
   and `const pixelPadding = 0.5;` (`globe_util.js:585-596` **(D)**).
3. **Skirts (Mapbox).** The shared grid embeds a skirt ring — `EMBED_SKIRTS = true` — whose vertices are
   pushed inward along the surface normal by `u_skirt_height`
   (`globe_util.js:698`, `globe_raster.vertex.glsl:92-95` **(D)**). Skirts hide gaps between tiles at
   different LODs by giving the crack a wall to be filled by.
4. **Latitude-dependent LOD (Mapbox).** `getLatitudinalLod` reduces latitudinal grid rows toward the poles
   with `t = |sin(lat)|^3`, picking from `[64, 32, 16]` (`globe_util.js:607-614` **(D)**) — because a
   mercator tile near the pole covers very little latitude on the sphere and does not need 64 rows.

**The trap that cost MapLibre the most, and that generalises.** From
[maplibre-gl-js#4868](https://github.com/maplibre/maplibre-gl-js/pull/4868), the maintainer's own account
**(A)**:

> "What is happening is that the ocean geometry is a simple quad, but the stencil mask is a curved shape
> that extended above the ocean quad (because it's min granularity was set to 4), and the ocean's further
> edge lies below the stencil mask edge, leaving some pixels above it still marked in stencil as belonging
> to this tile, but not filling them with pixels. These seams are then simply fixed by tweaking the stencil
> mask granularity so that it is never higher than fill granularity. This however introduces other, more
> subtle artifacts… I tried fixing this by generating extra geometry at tile edges that extends to the
> planet's center, but this is also problematic."

The rule that came out of it is still a comment in the settings:
`// Stencil granularity must never be higher than fill granularity, otherwise we would get seams in the
oceans` (`vertical_perspective_projection.ts:21-23` **(D)**), plus an assertion in code
(`vertical_perspective_projection.ts:76-77`: *"Stencil granularity must match fill granularity"*). **The
general law: any two meshes that share an edge on the sphere must be subdivided identically, or a
sub-pixel sliver of background shows through.** For RenG that means the ground grid and any clip/mask mesh
must come from the same generator with the same granularity — and if RenG ever draws a `Geometry` quad
flush against a tile edge, that quad needs the tile's subdivision too.

**Filtering.** MapLibre binds raster tiles `LINEAR` / `CLAMP_TO_EDGE` / `LINEAR_MIPMAP_NEAREST` and enables
anisotropic filtering only above a pitch threshold (`draw_raster.ts:121`, `:134-139` **(D)**, default from
`anisotropicFilterPitch`, 20° per the option's documentation). On a globe the limb is a permanent extreme
foreshortening — every frame has a band of tiles at grazing incidence regardless of pitch — so the
"pitch > 20°" heuristic is a mercator heuristic that does not obviously carry over. That is an observation,
not a citation; I found no source discussing anisotropy specifically on a globe limb.

**Arithmetic, for scoping.** With MapLibre's floor (granularity 32, borders on, no pole row) a raster tile
is `34 × 34 = 1,156` quads = **2,312 triangles**, from a `35 × 35 = 1,225`-vertex shared buffer. At zoom 0
(granularity 128) it is `130 × 130` quads = **33,800 triangles** from 17,161 vertices — still inside 16-bit
indices, which is why 128 is the ceiling. Mapbox's fixed grid is `64 × 64` plus skirts. Cesium's untextured
ellipsoid tile is `15 × 15` quads = 450 triangles. The whole spread is 450–33,800 triangles per tile; the
number RenG would live at for most of a session is the ~2,300 figure.

### Q7b — Consumer-supplied shaders on a globe, which ADR 0008 makes unavoidable

RenG's `Geometry` takes a consumer `ShaderPair`, and `CLAUDE.md` records ADR 0008's rule precisely: *"No
RenG-injected includes or uniform preamble; RenG substitutes `#version 330 core` for `#version 300 es` on
desktop GL contexts and changes nothing else."* **A globe cannot honour that rule**, because the
mercator→sphere transform lives in the vertex shader and the consumer's shader is the vertex shader.

MapLibre hit exactly this and its answer is the opposite of ADR 0008. A custom layer receives, per frame,
a `shaderData` object containing:

- `vertexShaderPrelude: string` — *"The prelude code to add to the vertex shader to access MapLibre's
  `projectTile` projection function. Depends on current projection."*
- `define: string` — *"Defines to add to the shader code. Depends on current projection."* (this is how
  `#ifdef GLOBE` becomes available)
- `variantName: string` — *"Name of the shader variant that should be used. Depends on current projection.
  Whenever the other shader properties change, this string changes as well, and can be used as a key with
  which to cache compiled shaders."*

— `src/style/style_layer/custom_style_layer.ts:87-127` **(D)**, and the documented usage is textual
concatenation by the consumer:

```
const vertexSource = `#version 300 es
${shaderData.vertexShaderPrelude}
${shaderData.define}
in vec2 a_pos;
void main() { gl_Position = projectTile(a_pos); }`;
```

Three consequences worth naming before the grilling.

1. **The prelude is injected, but by the *consumer*, not the renderer.** MapLibre hands over a string and
   lets the consumer paste it. That preserves "the renderer does not rewrite your source" in letter while
   abandoning it in spirit — and it makes the consumer responsible for recompiling when `variantName`
   changes. It is, notably, still compatible with ADR 0008's actual prohibition, which is on RenG *editing*
   the source.
2. **`variantName` exists because the shader must be recompiled when the projection changes**, and under a
   *transition* the projection changes continuously in one axis but only twice in shader-source terms
   (mercator variant, globe variant). Any RenG design that keeps ADR 0008's "compile the consumer's source
   verbatim" needs an equivalent cache key, or every `Geometry` recompiles on the frame the projection
   flips.
3. **The two entry points are visible in the public contract**, and the documentation says which is which:
   *"For projection 3D features, use `projectTileFor3D` in the shader"*, and *"If you just need a projection
   matrix, use `defaultProjectionData.mainMatrix`. A projection matrix is sufficient for simple custom
   layers that only support mercator projection."* (same file, `:129-140` **(D)**). In other words MapLibre
   tells consumers plainly that a matrix-only shader is a mercator-only shader. Every `Geometry` shader
   written against RenG today is a matrix-only shader.

I found **no** renderer that projects consumer-authored shaders onto a globe without either injecting a
prelude or requiring the consumer to adopt one. The only alternative visible in the field is to not let the
consumer's shader do the projection at all — draw the consumer's geometry into an offscreen texture in its
own flat space and then drape that texture on the sphere, which is structurally what Mapbox does for
terrain-draped layers (`drawTerrainRaster`'s render-to-texture path) but which I could not find anyone
doing specifically to preserve a consumer shader contract.

### Q8 — What the field considers a known trap

Ordered by how likely a first implementation is to hit it.

1. **Assuming `sin`/`cos`/`atan` are accurate.** Finding 3. Spec says undefined; Mali is off by 200–300 m;
   Intel HD 4600 had a discontinuity; the surviving fix uses only `exp`. **(A/B)**
2. **Leaving fragment precision at the default `mediump`.** [#6916](https://github.com/maplibre/maplibre-gl-js/issues/6916)
   / [#6939](https://github.com/maplibre/maplibre-gl-js/pull/6939) **(A)**. A one-line omission that renders
   correctly on every desktop and on iOS (where mediump is 32-bit) and produces a blank feature on Android
   (where it may be 16-bit). The spec floor is `2^-10` relative. Note the maintainer's closing remark on
   that PR: *"Feel free to investigate ways to test it, but I'll be surprised if you would be able to find
   one."* — MapLibre has **no** GLES test coverage for this class of bug. RenG does have GLES device
   coverage, which is an unusual advantage here.
3. **Under-subdividing the tile.** MapLibre ships a figure captioned *"how globe would look without
   subdivision. Note the deformed oceans, and the USA-Canada border that is not properly curved"*
   (`developer-guides/globe.md`, `assets/no_subdivision.png` **(A)**), and a minimum of 32 specifically for
   raster because otherwise *"they would exhibit visible warping and deformations when changing zoom
   levels"*.
4. **Mismatched subdivision between meshes that share an edge.** #4868, above **(A)**. Seams in the ocean.
5. **The horizon-clip-via-`gl_Position.z` hack.** Breaks on some Android drivers (#6047), and is
   mutually exclusive with a real depth test (#6698). **(B)**
6. **Forgetting to widen culling volumes to include the pole.**
   `globe_covering_tiles_details_provider.ts:163-170` **(D)** — the code exists because the naive AABB
   culls the polar cap.
7. **Forgetting that the transition band is a distinct correctness regime.** Mapbox #12645, #13065, #12100
   are all "only between zoom 5 and 6", and two are years old **(B)**.
8. **Assuming zoom still means what it meant.** See the next section.
9. **Subdivision that is not *consistent*.** MapLibre's guide, unprompted: *"When modifying subdivision,
   beware that it is very prone to subtle errors, resulting in single-pixel seams. Subdivision should also
   split the geometry in consistent places, so that polygons and lines match up correctly when projected."*
   **(A)**
10. **Assuming the pole sentinel cannot collide with real data.** `_ensureNoPoleVertices` exists precisely
    because subdivision can land a real vertex on `y = -32768` **(D)**.

---

## The question the brief did not ask but the field forces: what does `zoom` mean on a sphere?

Both renderers had to invent a convention, they chose **differently**, and both conventions leak into user
code. This is decision-forcing for RenG because `Camera(latitude, unwrappedLongitude, zoom, bearing, pitch)`
is public API and its meaning is currently mercator's.

- **MapLibre scales the globe by `1/cos(latitude)`.**
  `getGlobeRadiusPixels(worldSize, lat) = worldSize / (2π) / cos(lat)`, with the comment *"We want zoom
  levels to be consistent between globe and flat views. This means that the pixel size of features at the
  map center point should be the same for both globe and flat view. For this reason we scale the globe up
  when map center is nearer to the poles."* (`src/geo/projection/globe_utils.ts:83-89` **(D)**). The guide
  adds that panning then has to *compensate* by changing zoom with latitude, and that this is invisible
  except when a `flyTo` sets a zoom derived from the current zoom **(A)**.
- **Mapbox matches scale at a fixed latitude of 45°.** `GLOBE_SCALE_MATCH_LATITUDE = 45`, with an unusually
  candid comment: *"The choice of latitude is a bit arbitrary. Different choices will match mercator more
  closely in different views. 45 is a good enough choice because: it's half way from the pole to the
  equator; matches most middle latitudes reasonably well; biases towards increasing size rather than
  decreasing… Changing this value will change how large a globe is rendered and could affect end users."*
  (`src/geo/projection/globe_constants.ts:8-21`, on `main` **(D)**).

MapLibre's choice has a visible consequence users filed a bug about:
[#5471](https://github.com/maplibre/maplibre-gl-js/issues/5471) **(C, open)** — country labels disappear as
you pan toward the poles, because the effective zoom changes with latitude and zoom-gated style rules turn
off. The reporter's expectation — *"the map style should remain consistent across latitude when the globe is
the same size in the window"* — is reasonable and is not what the convention delivers.

**For RenG this is not cosmetic.** `zoom` selects which tile zoom level the ground loads, and it is a
consumer-visible field of `Camera`. A globe convention that makes the effective ground resolution depend on
latitude changes tile selection as the camera pans north at constant `zoom`. Whatever RenG picks, it should
be written down as a decision with a name, because both renderers that shipped one have a comment
apologising for it.

---

## Where the field's answer rests on an assumption RenG may not share

Stated explicitly, as asked.

| The field's answer | The assumption behind it | Does RenG share it? |
|---|---|---|
| "Subdivide the tile's geometry after earcut, on the tile worker" | **Vector** tiles, re-tessellated client-side | **No.** RenG's ground is raster. The relevant path is the *other* one both renderers have — a cached, shared grid mesh plus per-tile uniforms — which is simpler, not harder. |
| "Reproject the imagery from mercator to geographic" (Cesium) | The globe's surface tiles are in a **geographic** tiling scheme | **No.** RenG's tiles and its sphere would share a mercator parameterisation, so UVs stay linear and no reprojection pass exists. Cesium's five-stage reprojection saga is a cautionary tale RenG gets to skip. |
| "Transition to mercator around zoom 10–12" | The renderer draws whole-planet coordinates in a float32 shader without per-tile rebasing | **Partly.** The float32 argument weakens if positions are tile-local; the *cartographic* argument (Mapbox: conformality for navigation) is independent of implementation and does apply. |
| "Clip the far side by writing distance-to-horizon into `gl_Position.z`" | The depth buffer is used for **layer ordering**, not occlusion (MapLibre: constant `glDepthRange` per sublayer) | **No.** ADR 0030 gives RenG's model pass a real depth test. RenG is on Cesium's side of this line. |
| "Dim the marker when it is behind the planet" | Markers are DOM elements outside the GL pipeline | **No.** RenG's stickers are GL content; culling, not opacity, is the natural verb. |
| "The camera cannot cross the pole; rotate around instead" | The renderer owns the camera controller | **No.** RenG takes a `Camera` in the `FramePlan`. This cost transfers to RenG's consumers, and RenG should say so rather than absorb it. |
| "Use a logarithmic depth buffer" (Outerra, Cesium) | A single frustum spanning metres to 10⁸ metres with real occlusion everywhere | **Unclear.** Depends entirely on whether RenG's globe keeps a near plane close to the surface while the far plane reaches the limb. Not answerable from prior art; it is a measurement. |
| "Hand the consumer a shader prelude containing `projectTile`" | The renderer is allowed to shape the consumer's shader source | **In tension.** ADR 0008 forbids RenG-injected includes or a uniform preamble. MapLibre's form — hand over a string and let the consumer paste it — keeps the letter of that rule; nothing in the field keeps its spirit. |
| "Grid granularity 32 / 64 is enough" | A GPU rasteriser | **Unverified.** RenG's CI includes `Apple Software Renderer` and llvmpipe, and `0.3.0` failed once on large-quad rasterisation. More, smaller triangles plausibly helps there — but nobody has measured a subdivided globe patch on a software rasteriser. |

---

## What I could not source

Recorded so nobody repeats the search.

- **Whether Apple's macOS OpenGL 4.1 exposes `GL_ARB_clip_control`, or whether any Apple or Android GLES
  3.0 driver exposes `GL_EXT_clip_control`.** The extension exists for ES (registry, #290, 2017) and is core
  in desktop GL only at 4.5. I found no vendor extension list stating support or absence for the drivers
  RenG runs on. This is one `glGetString(GL_EXTENSIONS)` / `glGetStringi` read per target and belongs in a
  probe rather than in a design document — the same discipline `CLAUDE.md` already records for the driver
  probe. Until then, treat "reverse-Z gives RenG the precision win Nathan Reed describes" as **unproven on
  RenG's targets**, in either direction.
- **Any measurement of a subdivided globe patch on a software rasteriser** (`Apple Software Renderer`,
  llvmpipe, SwiftShader). Every performance and correctness datum found is from a GPU or a mobile GPU. Given
  that `0.3.0` failed closed on `Apple Software Renderer`'s treatment of large quads, and that subdivision
  is the opposite of a large quad, the sign of the effect is a guess. Nobody has published it.
- **Any globe implementation on OpenGL ES *natively* — as opposed to WebGL.** MapLibre GL JS, Mapbox GL JS
  and CesiumJS are all WebGL; deck.gl is WebGL. MapLibre Native's globe status was not established here.
  Every mobile data point above is WebGL-on-GLES through a browser, which adds ANGLE or the browser's own
  translation layer between the shader and the driver. The Mali precision numbers in #7419 are therefore
  *WebGL* numbers; whether the same GLSL compiled directly by the Mali driver behaves identically is
  unestablished.
- **A globe built on *raster* tiles as the primary basemap, in an open-source renderer, with the tile scheme
  RenG uses.** MapLibre supports raster sources on globe and I quoted its raster path in detail, but its
  design centre is vector. Cesium is raster imagery on a globe but with a different tiling scheme and a
  reprojection step RenG does not need. There is no project that is exactly RenG's shape, so the raster
  answers above are assembled from two half-matches rather than read off one.
- **Anything about golden-image or analytical verification of a globe.** MapLibre has render tests
  (`projection/globe` fixtures are mentioned in #7419's PR description) but they are pixel snapshots — the
  approach `CLAUDE.md` records RenG as deliberately deferring to Cycle J. I found no published account of
  gating a globe projection by analytical readback, which means RenG would be first and the tolerances would
  be calibrated against nobody else's numbers. The same sentence appears in the Cycle H field report about
  mobile readback, and it is true again here.
- **How many tiles a globe view holds resident, and what that does to a bounded GPU texture budget.**
  `ResourceLimits.maximumResidentGpuTextureBytes` exists (per `CLAUDE.md`, the one public field the basemap
  cycle added). A globe at low zoom shows an entire hemisphere, which is strictly more tiles than a mercator
  viewport at the same zoom, and MapLibre only allows variable zoom above coverage zoom 4
  (`globe_covering_tiles_details_provider.ts:100-102` **(D)**) — meaning below that it loads a *uniform*
  level across the whole visible hemisphere. I found no published tile-count or byte figure for a globe view
  in any of the three renderers.
- **Why KorGE-style engines or any Kotlin/Native project have done this.** Not searched — out of remit; the
  Cycle H field report already establishes what exists in Kotlin/Native GL land, and none of it is a globe
  except WorldWindKotlin, whose projection code was not examined here.
