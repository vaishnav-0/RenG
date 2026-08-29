# E-terrain preflight — what the code and the corpus already decide

Written before any grilling or planning, which is this project's order: read the code, measure, and only
then argue. Everything below is either a citation or a number this session produced. Where something could
not be established, it says so.

---

## 1. Far more of this cycle already exists than the decomposition implies

`docs/decomposition.md` says Cycle C "takes Rentile's terrain descriptor and DEM tiles, decodes them, and
validates their declared encoding, but nothing consumes elevation until here". The first half is only
partly true, and the difference matters for scope.

**What exists:**

- `ResourceClass.BASEMAP_DEM_TILE` is a **public** enum constant already
  (`kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt:44`), with its byte ceiling, accept header and
  report order already mapped (`internal/ValueSupport.kt:82,101,116`).
- The style reader already parses `terrain.source` into `BasemapStyleManifest.terrainSourceId`
  (`internal/basemap/BasemapStyleManifest.kt:297-298,1059-1060`), and `BasemapSourceKind.RASTER_DEM`
  routes to `BASEMAP_DEM_TILE` (same file, `:1069`).
- `validatesDemTerrainEncoding` exists and **is wired into the firewall's write path**
  (`internal/driver/ClassGateRunner.kt:111`, called from `internal/firewall/OperationRegistry.kt:775`),
  exactly where ADR 0016 puts the obligation.
- The firewall maps the class to Rentile's own (`internal/firewall/RentileKeyDerivation.kt:103`).

**What does not exist:** Cycle C's **task 20** was never implemented. Its plan
(`docs/superpowers/plans/2026-08-18-cycle-c-resource-layer.md:2316-2326`) specifies
`internal/firewall/TerrainAcquisition.kt` "exposing the style's terrain descriptor, ground radiance, and
decoded DEM samples". No such file exists. And **RenG has never called a single one of Rentile's terrain
entry points** — `acquireTerrainTiles`, `terrainSourceDescriptor`, `groundRadianceDescriptor`,
`ValidatedDemTile` and `TerrainDemEncoding` have **zero references** anywhere in `kmp/src`.

So the acquisition *plumbing* is real; the acquisition *itself* is not.

## 2. What Rentile `0.6.0` provides

From `rentile/kmp/api/kmp.klib.api` at the `0.6.0` release commit `87ccba2`:

- `BasemapRasterizer.terrainSourceDescriptor(PreparedStyle): TerrainSourceDescriptor?`
- `BasemapRasterizer.acquireTerrainTiles(PreparedStyle, List<TileId>, ResourceAccessMode): List<ValidatedDemTile>`
- `BasemapRasterizer.groundRadianceDescriptor(PreparedStyle): GroundRadianceDescriptor?` → `(red, green, blue)` as `Double`
- `TerrainSourceDescriptor(sourceId, encoding, tileSizePx, minimumZoom, maximumZoom)`
- `TerrainDemEncoding` = `MAPBOX | TERRARIUM`
- `ValidatedDemTile(requestedTile, sourceTile, sourceId, encoding, bytes, contentDigest)`

**Two consequences are already visible in those signatures.** `bytes` is a `ByteArray`, so **RenG decodes
the DEM itself** — it already owns `decodePng`, and the elevation formula is RenG's to implement and to
get right. And `requestedTile` and `sourceTile` are **separate fields**, which can only mean overzoom or
substitution: when the DEM source's `maximumZoom` is below the requested zoom, the returned tile is an
ancestor and the caller must sample a sub-rectangle of it. That is a real piece of arithmetic RenG does not
have today.

### What Rentile's implementation actually does, read from its source at `87ccba2`

**`requestedTile` vs `sourceTile` is overzoom, and nothing is lost.** They diverge through
`CompiledRasterSource.sampleFor` (`rentile/.../internal/raster/RasterResource.kt:34-54`), in exactly two
cases: a request above the source's `maximumZoom`, and a non-canonical `x` (a world-wrapped tile). It is
**not** substitution — `acquireTerrainTiles` calls the acquirer directly
(`.../internal/DefaultBasemapRasterizer.kt:668`) and the ancestor-substitution machinery is reachable only
from a different path. Rentile computes `childScale`, `childX` and `childY` and then **throws them away**:
`RasterSample` carries them, `ValidatedDemTile` does not. RenG can recompute them exactly —
`childScale = 1 shl (requested.z - source.z)`, `childX = floorMod(requested.x, 1 shl requested.z) %
childScale`, `childY = requested.y % childScale` — checked against Rentile's own fixture
(`RasterResourceTest.kt:19-51`). The requested tile occupies `u ∈ [childX/childScale,
(childX+1)/childScale]` of the source, `v` **increasing southward**. **Overzoom depth is unbounded** below
the profile's `maximumOutputZoom = 22`, so a `maxzoom: 12` DEM reaches `childScale = 1024` at zoom 22 —
the entire ground tile inside one DEM texel, with no diagnostic.

**"Validated" is weaker than the type name suggests.** Rentile checks HTTP status, encoded size, that Skia
decodes it, dimensions against limits, and a SHA-256 digest
(`.../internal/raster/RasterResourceAcquirer.kt:128-258`). It does **not** check that the bytes are a PNG
(`Image.makeFromEncoded` is multi-format, and Rentile's own `isPng` helper is used only on an unrelated
path), does **not** compare the image's dimensions to the descriptor's `tileSizePx`, and does **not** check
terrain encoding. **So `ValidatedDemTile.bytes` is not guaranteed PNG, not guaranteed `tileSizePx`, and not
guaranteed opaque.**

**And RenG's own check has a hole.** `validatesDemTerrainEncoding` runs on the firewall's **write** path
only; `passesClassSpecificReadValidation` returns `true` for `BASEMAP_DEM_TILE` through its `else` branch
(`internal/firewall/OperationRegistry.kt:748-764`), so **bytes served from the consumer's Store reach
Rentile having passed nothing of RenG's**. Opacity is not pedantic here: Rentile decodes DEM pixels into a
**premultiplied** N32 bitmap (`DefaultBasemapRasterizer.kt:1671`), so any alpha below 255 silently scales
R/G/B before any elevation formula reads them.

**The elevation formulas, quoted rather than remembered** (`DefaultBasemapRasterizer.kt:1706-1709`), in
metres:

```
MAPBOX    : -10000.0 + (red * 65536 + green * 256 + blue) * 0.1
TERRARIUM : red * 256.0 + green + blue / 256.0 - 32768.0
```

Rentile decodes elevation in that one private place and **exposes it nowhere** — no public API converts DEM
bytes to heights. RenG's decode is RenG's to write, and these two lines are what to check it against.

**The DEM grid is edge-*exclusive*, and this is the cycle's hardest problem.** Two adjacent DEM tiles do
**not** duplicate a row or column; pixel `-1` of tile B *is* pixel `W-1` of tile A
(`DefaultBasemapRasterizer.kt:1693-1700`), and the world is one continuous grid of area samples with pixel
`i` centred at `(i + 0.5)/N` (`:1720-1722`). So two adjacent ground tiles displaced from their own DEM
tiles **will disagree at the shared edge** unless RenG samples the same world position from both sides —
which means reading one texel of the *neighbouring* DEM tile. **`acquireTerrainTiles` provides no border
and does not fetch neighbours**, while Rentile's own hillshade planner explicitly expands to the 3×3
neighbourhood because it needs exactly that (`:851-855`). RenG's `tileTimeRoutes` already preregisters the
3×3 for `raster-dem` sources (`BasemapStyleManifest.kt:1073-1087`), so the routes exist but the acquisition
does not.

**Four smaller traps, each confirmed in source rather than inferred.**

- **`TerrainSourceDescriptor.sourceId` is a digest**, `sha256Hex` of the style's source id
  (`StyleCompiler.kt:1770`). Comparing it to `BasemapStyleManifest.terrainSourceId` requires hashing first.
- **Tiles below `minimumZoom` or outside the source's `bounds` vanish silently** — `mapNotNull`, no
  diagnostic, no exception — and `TerrainSourceDescriptor` exposes no `bounds` with which to predict it.
  Results must be matched by `requestedTile`, never by index or count.
- **One failing DEM tile fails the whole call** (`throwAcquisitionFailures`, `:1370-1384`). There is no
  per-tile degradation, and RenG's own contract forbids retries.
- **`ValidatedDemTile` is a `data class` whose `equals` compares `bytes` by reference** (`Api.kt:583-590`),
  unlike `LabelGlyphAtlas`, which overrides for exactly this reason. `distinct()`, a `Set` or a `Map` key
  will not behave as content equality.

**`terrain.exaggeration` is read by nothing in Rentile.** `compileTerrainSource` reads `terrain.source` and
no other key (`StyleCompiler.kt:564-585`); the only exaggeration in the codebase is a *hillshade layer*
paint property. RenG must parse it from the style itself — and the corpus above says every style that
declares it sets `1`, so a fixture at `1` proves nothing.

**`groundRadianceDescriptor` is not about terrain at all.** It is compiled from the style's top-level
`lights` array (`internal/style/GroundLight.kt:23-52`) and is entirely independent of `terrain`: a style
with lights and no terrain yields one, and a style with terrain and no lights yields `null`. Its range is
neither 0..1 nor 0..255 — the value is gamma re-encoded over a **sum**, giving `[0, 2^(1/2.2)] ≈ [0,
1.366]`, **unclamped**, so multiplying a ground colour by it can exceed 1. With Rentile's defaults it is
≈ **0.969**, near-white but not white, which is worth knowing before someone reads a faint tint as a bug.

## 3. The corpus: 6 of 34 styles need displacement

Measured this session by fetching all 34 map ids of the owner's corpus — 17, 49, 51–65, 67–73, 76–81,
83–86 — and counting. **No style document, URL, credential or tile template appears in this file**; the
documents were fetched to the session scratchpad and only aggregates brought back, the same rule the
E-labels corpus document follows.

| | count |
|---|---|
| fetched successfully | **34 / 34** |
| declare a `raster-dem` source | **9** |
| declare a top-level `terrain` block — **these need displacement** | **6** |
| declare a `hillshade` layer and no `terrain` — **Rentile already draws these** | **3** |
| declare `fog` | **34** |

The six are ids **56, 57, 65, 76, 80, 84**. Five leave `encoding` unset, which means Mapbox by
specification default; **one (id 80) declares `terrarium`**, so both encodings are exercised by the corpus
and neither can be skipped. Every one of the six sets `exaggeration: 1`, so the multiplier is needed for
correctness but is never exercised at a non-default value here — a fixture that only ever tests
`exaggeration = 1` would be a vacuous check of exactly the kind this project keeps finding.

Only id 80 inlines `tileSize` (256) and a zoom range (0–12); the rest leave both to TileJSON. That settles
where RenG reads them from: **`TerrainSourceDescriptor`, which Rentile resolves, not the style JSON.**

**`fog` is declared by all 34 styles and is out of scope** — worth stating because a reader meeting "34/34"
will otherwise wonder whether it was missed.

## 4. The two projections need opposite work, and this is the cycle's real shape

- **Mercator ground is a four-vertex quad per tile** (`internal/gl/GroundPipeline.kt:63-68`, `GROUND_QUAD`)
  with **no subdivision anywhere in the file**. A flat quad cannot displace. Mercator needs a subdivided
  grid introduced where none exists.
- **Globe ground is already subdivided**, 1 to 128 cells a side, chosen from the camera by
  `globeGroundCellsPerTileSide` — but it bakes **altitude zero into one per-frame matrix**:
  `composeGlobeGroundUnitSphereToClip` folds the radius in as a uniform radial scale precisely so the
  vertex shader can emit a unit direction. Displacing it makes that scale **per vertex rather than per
  frame** (`internal/gl/GlobeGroundPipeline.kt`, and the KDoc there already says so).

So the globe has the geometry and the wrong transform; Mercator has the transform and no geometry.

## 5. Vertex texture fetch works on every driver reachable from this machine — measured

The architecture turns on this. Per-vertex elevation can come from a **vertex texture fetch** (sample the
DEM in the vertex shader: one upload per tile, no CPU work per frame) or from **CPU-baked vertex buffers**
(decode and rewrite the buffer whenever the tile set or granularity moves, but depends on nothing).

A probe was written for this preflight —
`kmp/src/commonTest/kotlin/com/rohittp/reng/VertexTextureFetchProbe.kt` — and wired into all four platform
conformance suites. **The advertised limit is deliberately not the measurement**: both GLSL ES 3.00 and GL
3.30 require at least 16 vertex texture image units, so every driver claims support. The probe instead
places 64 points at rows the vertex shader *read out of a texture*, so a driver that returns zero, clamps
to `lowp`, or ignores the fetch collapses the diagonal onto one row.

| context | driver | units | result |
|---|---|---|---|
| `macosArm64Test`, CGL | Apple M3 Max | 16 | all 64 landed |
| `macosArm64Test`, CGL software | `Apple Software Renderer` | 16 | all 64 landed |
| `iosSimulatorArm64Test`, EAGL | `Apple Software Renderer` | 16 | all 64 landed |
| `linuxX64Test`, surfaceless EGL | llvmpipe | **not run here** — compiled and wired, CI only |
| `connectedAndroidDeviceTest`, EGL14 | — | **not run here** — no device attached (ADR 0033) |

**The probe was measured against deliberate breaks** and both turn it red: making the shader ignore the
fetch (`* 0.0`), and sampling the transposed texel. Gradle's Kotlin/Native output withheld the assertion
text in both cases — the documented behaviour `print_test_failures.py` exists for — so the discrimination
was confirmed by exit status rather than by message.

**Two portability details are exercised rather than assumed**, and both are recorded in the probe's KDoc: a
vertex shader has no implicit derivatives, so the fetch must be `textureLod` with an explicit level; and
GLSL ES 3.00 declares `precision lowp sampler2D` for the vertex language (§4.5.4), whose ~8 bits of
returned precision is exactly what an 8-bit DEM channel needs and no more, so the sampler is declared
`highp` explicitly.

**This does not clear the approach on hardware nobody here has measured.** Two of the five contexts are
unrun, and no Mali, Adreno or Intel part has been measured at all.

## 6. The vertex budget, which decides the granularity rule

Vertices per frame for a displaced ground, against X2's measured tile counts for a 3840×2160 viewport
(93 with no LOD history, 167 with one frame of hysteresis) and against the declared ceiling of 512:

| cells/side | verts/tile | 93 tiles | 167 tiles | 512 tiles |
|---:|---:|---:|---:|---:|
| 8 | 81 | 7,533 | 13,527 | 41,472 |
| 16 | 289 | 26,877 | 48,263 | 147,968 |
| 32 | 1,089 | 101,277 | 181,863 | 557,568 |
| 64 | 4,225 | 392,925 | 705,575 | 2,163,200 |
| 128 | 16,641 | 1,547,613 | 2,779,047 | 8,520,192 |
| 256 | 66,049 | 6,142,557 | 11,030,183 | 33,817,088 |

**Sampling a 256-texel DEM tile per texel is the last row and is out of the question**, which is worth
stating because it is the naive reading of "displace the ground by the DEM". The globe's existing rule
derives granularity from a **half-pixel curvature deviation**; terrain needs one derived from **elevation
error**, and the two must produce a single granularity per frame, because MapLibre's own rule — recorded in
`GlobeGroundPipeline.kt` — is that two meshes sharing an edge must be subdivided identically or a sliver of
background shows through.

## 7. What this preflight has NOT established

Stated plainly, because the next step is grilling and these are what it has to resolve.

- **How to close the edge-exclusive seam.** The grid convention is now known; the *answer* is not. Fetching
  the 3×3 neighbourhood multiplies DEM acquisition ninefold; a one-texel border cannot be asked for; and
  clamping instead produces a terrace at every tile edge. This is the cycle's largest open design question.
- **Whether the two ground pipelines converge or stay separate.** The globe and Mercator grounds are
  separate pipelines today for reasons `GlobeGroundPipeline.kt` argues; terrain is the first feature both
  need identically.
- **Precision.** ADR 0037's erratum accepted a `Float` positional error reaching 50 px at the frame edge at
  zoom 22. Elevation adds a third axis to that, and nothing here measures it.
- **Whether terrain changes the lit/unlit rule.** ADR 0026 leaves the ground unlit, and a displaced ground
  with no shading reads as flat regardless of its geometry. `groundRadianceDescriptor` is now understood —
  a `lights`-derived, unclamped `[0, 1.366]` triple — but whether RenG shades terrain at all, and with what,
  is undecided. Note that it says nothing about terrain, so it does not answer this by itself.
- **What terrain does to labels and placements.** E-labels places on a flat ground; a displaced ground
  moves every anchor. Not investigated.
