# Cycle G preflight, supply side: what Rentile gives a globe, and what a globe does to tiles

**Date:** 2026-08-28. **Branch read:** `main`, RenG HEAD `f997e1a`.
**Question asked:** does Rentile know anything about a globe; do its tiles seam; how many tiles does a
globe want; and does the one public byte budget survive it?
**Answer, in one line:** Rentile knows nothing about a globe and does not need to — its tiles are
edge-exact, **measured** — but RenG's own LOD rule is calibrated on a Mercator identity that a sphere
breaks, and keeping it unchanged multiplies the frame's tile count by up to **33×**, straight through a
GPU budget that does not fail but silently thrashes.

This is a research document, not a decision record and not a plan. Nothing was changed in either
repository; every claim below is either **read** from source at a named commit, **measured** by a command
quoted in place, or explicitly flagged **not established**.

Two sibling investigations run alongside this one and are deliberately not duplicated here: RenG's
internal projection seam (`docs/research/2026-08-28-g-globe-projection-seam.md`) and external prior art.
The concurrent E-labels work on Rentile `0.6.0` is likewise not restated; §7 covers only what `0.6.0` does
to **tiles**.

---

## 0. Provenance

| Input | Value |
|---|---|
| RenG worktree | `/Users/rohittp/Data/Other/RenG`, branch `main`, HEAD `f997e1a` ("docs: withdraw Cycle I…"). Working tree carries only three sibling research files, untracked. Nothing modified by this document's author except the file you are reading. |
| RenG `VERSION_NAME` | `gradle.properties:20` → `0.4.0` |
| Pin under review | `gradle/libs.versions.toml:7` → `rentile = "0.5.0"` (read from the catalog, never from prose) |
| Rentile checkout | `/Users/rohittp/Data/Other/rentile`, branch `main`, HEAD `87ccba2` ("Release 0.6.0 label and icon fidelity"), `gradle.properties:10` → `VERSION_NAME=0.6.0` |
| Rentile `0.5.0` source commit | `d899cb2` — named as such by RenG's own `internal/firewall/RentileKeyDerivation.kt:25`, and consistent with `git log -S"VERSION_NAME=0.5.0" -- gradle.properties`, which shows the string introduced at `e423a78` and removed at `87ccba2` |
| Published Rentile versions (measured) | `curl https://maven.rohittp.com/com/rohittp/rentile/kmp/maven-metadata.xml` → `0.1.4, 0.1.5, 0.2.0, 0.3.0, 0.4.0, 0.5.0, 0.6.0`; `<latest>` and `<release>` both `0.6.0`, `lastUpdated 20260823134311` |
| `0.5.0 → 0.6.0` | exactly one commit, `87ccba2` |

Measured artefacts written to scratch, not to the repository:
`.../scratchpad/tiles.py` (a Python transcription of RenG's Mercator tile-selection arithmetic),
`.../scratchpad/globe.py`, `.../scratchpad/sweep.py`, `.../scratchpad/png.py`.

---

## The three findings most likely to decide the cycle's shape

**1. Rentile's independently rendered adjacent tiles already align exactly, and this is measured, not
argued.** Rentile's own checked-in nine-tile mosaic (`docs/examples/topographic-nine-tile-mosaic.png`,
1536×1536, nine separately returned 512×512 PNGs placed by the caller) has cross-seam column steps of
**16.44** and **15.32** mean channel-difference against an interior-column mean of **14.06** and an
interior **max of 18.84** — the seam is inside the ordinary noise of the map. Rows likewise. There is no
bleed, no padding and no overdraw anywhere in Rentile's output, and none is needed. A globe that seams
will therefore be seaming for a *RenG* reason — patch geometry, sampler state, or a mipmap chain — never
because the supply is short of pixels. §2.

**2. The tile-count multiplier is not a property of the sphere. It is a property of keeping
`observeMercatorLod` unchanged, and it is 33×.** `MercatorLod.kt:11-27` derives the LOD rule from one
identity — `screenPixelsPerTexel = 2^(zoom - selectedLod)` — that holds because a Mercator tile is always
`512 * 2^(zoom-lod)` output pixels wide *regardless of latitude*. On a sphere it is false: a Mercator tile
at latitude φ is a ground square `cos φ` the size of an equatorial one, so a globe showing the same
on-screen ground scale needs `1/cos²φ` more of them. Modelled against RenG's own camera arithmetic, a
960×540 viewport at latitude 82°, zoom 6, goes from **4 tiles flat to 132 on a globe (33×)**; a
1179×2556 phone viewport at latitude 82°, zoom 8, goes from **24 to 686 (28.6×)**. Adopt instead a
latitude-scale-matched convention (`z_eff = zoom − log₂ cos φ`) and both collapse back to **4 → 4** and
**24 → 24**, with a bounded curvature bump of about **4×** (24 → 96) around zoom 3–5. The cycle's whole
tile-supply cost is therefore decided by one choice about what "zoom" means on a sphere, and that choice
belongs in an ADR before any code. §3.

**3. At those counts the one public byte budget does not fail — it thrashes, silently, and the code says
so on purpose.** `ResourceLimits.maximumResidentGpuTextureBytes` defaults to 128 MiB
(`Resources.kt:90`); a tile is 512×512 RGBA8 = exactly 1,048,576 bytes, so the budget is **exactly 128
tiles**. A leased texture is structurally unreachable to eviction (`GlObjectRegistry.kt:276-288` iterates
`unleasedOrder` only), so a frame needing 232 tiles draws all 232 — and then `releaseLease` evicts back
to 128 as the leases drop, and the next frame re-decodes and re-uploads the missing 104 at
"a megabyte of decode plus a megabyte of upload every time it is missed"
(`RenGRenderer.kt:1399-1400`). Only above 512 *instances* does anything fail, and then loudly, at frame
planning. The band a globe most plausibly lands in — 129 to 512 tiles — is exactly the band where nothing
reports anything and the frame rate quietly halves. §4.

---

## 1. Does Rentile have any notion of a globe, a projection, or a non-Mercator output?

**No, in three independent places, and its own documentation says so.**

Searched properly rather than assumed:

```
$ grep -rniE "globe|spheric|projection|equirect" --include="*.kt" kmp/src | wc -l
6
```

Six hits across the whole Kotlin tree, and none is a capability:

- `internal/DefaultBasemapRasterizer.kt:2778,2784` — `projectionRadius(axis)`, a separating-axis
  collision helper. Unrelated.
- `internal/glyph/LabelCandidateAssembler.kt:182` — the word "globe" in a doc comment about where a label
  sits.
- `internal/style/StyleCompiler.kt:2253-2263` — `EXCLUDED_ROOT_KEYS`, which lists
  `bearing, center, fog, light, pitch, projection, sky, snow, terrain, zoom`. A style's own
  `"projection"` declaration is **deliberately discarded at compile time**. This is the load-bearing one:
  a MapLibre style that asks for a globe gets rasterized as Mercator with no diagnostic.
- `Api.kt:260` — "before any projection", in the label contract.

Rentile's `CONTEXT.md:120` puts the boundary in words — "the host … owns projection, collision,
occlusion, and drawing above the Output Tile texture" — and both published doc pages list the globe under
what Rentile is *not*: `docs/index.html:387` ("Globe, terrain, fog, sky, or animated layers" under **Use a
full map SDK when you need**) and `docs/kmp.html:391` ("Globe, fog, sky, and interactive camera state"
under **Not implemented yet**).

This is the good outcome. Rentile is a square-Mercator-tile rasterizer and a globe does not need it to be
anything else.

### The output contract, exactly

What RenG receives per tile, from `Api.kt` and `internal/DefaultBasemapRasterizer.kt` at `87ccba2` (all
identical at `d899cb2`, see §7):

| Property | Value | Source |
|---|---|---|
| Container | `RenderedTile(id: TileId, pngBytes: ByteArray, contentKey: String, diagnostics)` | `Api.kt:701-706` |
| Encoding | PNG, via `image.encodeToData(EncodedImageFormat.PNG)` | `DefaultBasemapRasterizer.kt:1512` |
| Pixel size | `RenderOptions.outputSizePx`, constrained to `setOf(256, 512)`, default **512** | `Api.kt:87-100` |
| Size RenG asks for | **512** — `BasemapEngineHost.kt:91` defaults `tileOutputSizePixels` to `RenderOptions.DEFAULT_OUTPUT_SIZE_PX` and `:98` builds `RenderOptions(outputSizePx = tileOutputSizePixels)`; nothing in RenG overrides it | |
| Surface | `Surface.makeRasterN32Premul(sizePx, sizePx)`, cleared to `Color.TRANSPARENT` | `DefaultBasemapRasterizer.kt:1462,1465` |
| Colour space | **untagged**. Measured on the three shipped example tiles: 512×512, bit depth 8, colour type 6 (RGBA), chunks `IHDR, sBIT, IDAT, IEND` — **no `sRGB`, no `gAMA`, no `iCCP`**. Skia is given no `ColorSpace` argument at surface creation. | measured, §2 |
| Alpha | present and meaningful. The surface is cleared transparent, so a style with no opaque background yields alpha < 255. In the shipped topographic mosaic, every sampled alpha is 255. | |

RenG treats those bytes as raw RGBA8 end to end: `decodePng` → `uploadTexture(..., TextureContent.IMAGE)`
→ `GL_RGBA8` / `GL_RGBA` / `GL_UNSIGNED_BYTE` (`GlTextureUpload.kt:130-140`) → a fragment shader that does
`texture(rengGroundTexture, rengGroundUv)` and nothing else (`GroundPipeline.kt:41-43`). Byte in, byte
out, no transfer function anywhere. A globe changes none of that.

**Nothing in Rentile is projection-aware, so the cycle does not change materially on this axis.**

---

## 2. Tile edge behaviour — the seam question

This was the question most likely to bite, so it got the most work. The answer splits cleanly in two: the
supply side is settled and good; the RenG side has one real hazard and it is *not* the obvious one.

### 2.1 Rentile adds no bleed, padding or overdraw, by construction

The output-pixel mapping is exact and edge-to-edge. `internal/mvt/VectorSource.kt:21-34`:

```kotlin
val localX = coordinate.x.toDouble() * childScale - childX.toDouble() * extent
...
x = localX * outputSizePx / extent,
```

Local coordinate `0` maps to output pixel `0.0`; local coordinate `extent` maps to `outputSizePx`. Two
adjacent tiles' shared boundary is coordinate `extent` in one and `0` in the other, and both land on the
tile's outer pixel boundary. There is no margin term anywhere. The raster path is the same:
`drawRaster` blits its source sub-rect onto `Rect.makeWH(sizePx, sizePx)` — the whole tile, no inset
(`DefaultBasemapRasterizer.kt:1603-1625`).

What makes a feature *continue* across the boundary is therefore not overdraw in the output but buffered
geometry in the input: MVT coordinates outside `[0, extent]` map outside `[0, sizePx]` and are clipped by
the Skia surface, so a road crossing the seam is drawn in both tiles from each tile's own buffered copy.

### 2.2 Measured: the tiles align

Rentile ships nine independently rendered tiles assembled into one image.
`docs/rendering.html:227-229` states the provenance in its own words: *"Rentile returns nine independent
512×512 PNGs for this request. The caller places them in XYZ order to form the 1536×1536 image below."*
That is exactly the experiment.

Decoded with a stdlib-only PNG reader (`scratchpad/png.py`; zlib plus the five filter types) and measured
as mean per-channel absolute difference between adjacent columns:

```
mean |col[508] - col[509]| =   18.98      mean |col[1020] - col[1021]| =   16.75
mean |col[509] - col[510]| =   18.67      mean |col[1021] - col[1022]| =   16.41
mean |col[510] - col[511]| =   18.20      mean |col[1022] - col[1023]| =   15.43
mean |col[511] - col[512]| =   16.44  <-- mean |col[1023] - col[1024]| =   15.32  <-- seam
mean |col[512] - col[513]| =   16.61      mean |col[1024] - col[1025]| =   15.88
interior columns 100..400: mean 14.06  median 14.06  max 18.84
```

and by rows:

```
mean |row[511] - row[512]| =   23.39  <-- seam    mean |row[1023] - row[1024]| =   10.04  <-- seam
interior rows 100..400: mean 20.24  max 27.69
```

Both seams sit **inside** the interior distribution. A one-pixel misalignment would roughly double the
step; a hairline gap would spike it. Neither happens. Spot-checked pixel values straddling `x=511/512` at
five rows are ordinary varied map content, confirming the grid overlay the docs page mentions is HTML
rather than baked into the PNG, so the measurement is of real content.

**Rentile's tiles are edge-exact. A globe seam will not come from the supply.**

### 2.3 RenG's sampler state, and what clamping does at a patch boundary

A ground tile is uploaded as `TextureContent.IMAGE` (`RenGRenderer.kt:1454`), which takes
`defaultSamplerStateFor(IMAGE)` — `GlTextureUpload.kt:47`:

```kotlin
TextureContent.IMAGE -> TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
```

and **no mipmap chain is generated**, because `isMipmapMinificationFilter(GL_LINEAR)` is false
(`GlTextureUpload.kt:74-77,147-149`). The ground quad's UVs are exactly `0..1` at the quad corners
(`GroundPipeline.kt:63-68`).

Three consequences, in order of how much they matter to a globe:

**(a) `CLAMP_TO_EDGE` does not crack, it flattens.** With linear filtering and UV reaching exactly 0 and
1, the outer half-texel band of each tile samples a constant — the edge texel — because there is nothing
beyond it to interpolate towards. Two neighbours' edge texels cover *geographically adjacent* bands, so
there is no gap and no wrong colour; the artefact is a one-texel constant stripe, invisible at
`screenPixelsPerTexel ≈ 1` and the reason the flat map does not seam today. `GL_REPEAT` would be the
crack, and `GlTextureUpload.kt:29-35` records that it was already found and fixed once on stickers.

**(b) The real seam risk is geometric, and it is new.** `GROUND_QUAD` is four vertices. A sphere patch is
not, so every tile becomes an N×N subdivided grid, and **adjacent patches must produce bit-identical
vertex positions along their shared edge**. Two tiles that compute the same shared corner through
different arithmetic — one as `(x+1)/2^lod`, its neighbour as `x'/2^lod` — differ by a float epsilon and
open a hairline crack that no sampler state can close. Note the precedent: `BasemapTileQuad.kt:32-36`
already documents choosing one of two algebraically identical forms because the other "loses about four
orders of magnitude" of precision. The same class of decision, one dimension up, and a T-junction
between patches at different subdivision levels is the same defect again.

**(c) Adding mipmaps to cure globe minification would *create* the seam.** On a globe, tiles near the
limb are foreshortened to a fraction of their flat projected area, so `screenPixelsPerTexel` falls far
below the `(0.5, 1.414]` band `MercatorLod.kt:43-44` guarantees on a plane, and an unmipmapped
`GL_LINEAR` minification filter aliases. The obvious fix is `GL_LINEAR_MIPMAP_LINEAR` — but each mip
level averages a 2^N block, and under `CLAMP_TO_EDGE` that averaging stops at the tile's own boundary, so
the constant band of (a) widens from one texel to 2^N texels at mip level N. Two neighbours then disagree
across the seam by a whole averaged block. The standard cure is a one-texel border, i.e. exactly the
bleed §2.1 establishes Rentile does not provide — so it would have to be synthesized on RenG's side at
upload, or avoided by keeping mipmaps off and controlling minification through LOD instead. **This is the
concrete coupling between §2 and §3: the LOD choice and the seam behaviour are the same decision.**

---

## 3. How many tiles a globe shows

### 3.1 What the code does today

`observeMercatorLod(zoom, previousSelectedLod)` (`MercatorLod.kt:46-70`) returns
`LodObservation(selectedLod: Int)` — one integer. Historyless it is `ceil(zoom - 0.5)`; with history it is
held inside `(selectedLod - 1, selectedLod + 0.5]`, deliberately asymmetric so hysteresis is never
*coarser* than round-to-nearest. `MINIMUM_LOD = 0`, `MAXIMUM_LOD = 22`.

`planMercatorSpatial` (`MercatorSpatialPlanner.kt:142-157`) then builds
`clippedPhysicalPixelFootprint(camera)` — a plane-ray footprint, clipped to the Mercator support box —
and hands it plus that single LOD to `selectBasemapTiles`, which walks candidate rows, projects the
footprint's x-interval into each row's horizontal strip, and emits one `BasemapTileInstance` per
intersecting cell (`BasemapTileSelector.kt:46-114`). Over budget is a hard failure:
`TileSelectionOutcome.OverBudget` → `RESOURCE_LIMIT_EXCEEDED` at `FRAME_PLANNING`
(`MercatorSpatialPlanner.kt:155, 228-241`). `maximumBasemapTileInstances` defaults to **512** and is
capped at 4096 (`Renderer.kt:24,40`).

Note the two counts, which differ and matter differently: `instances` are draws (one per visible world
copy) and `canonicalResources` are acquisitions and GPU textures, deduplicated across world copies
(`BasemapTileSelector.kt:99-112`). On a globe there are no world copies, so the two converge.

### 3.2 The measurement

RenG's tile-selection arithmetic was transcribed into Python (`scratchpad/tiles.py`) from
`CameraMatrices.kt:41-141`, `MercatorGroundFootprint.kt:30-110`, `MercatorLod.kt:46-70` and
`BasemapTileSelector.kt:46-321`. **It is a model of that code, not that code** — but it is a checked one:

```
$ python3 tiles.py
validate 1024x1024 z0: (3, 1, 0) (Kotlin asserts 3 instances)
```

which is exactly the count `FramePlanningCoreTest.kt:268-285` asserts for `Camera(0,0,0,0,0)` at
`OutputPixelSize(1024, 1024)` — three instances (world copies −1, 0, +1) resolving to one canonical tile.

The globe side (`scratchpad/globe.py`) needs a convention RenG does not have, so one is stated: sphere
radius `R = worldSizeLogicalPixels / 2π`, eye at `cameraDistanceLogicalPixels` above the sub-camera
surface point along the outward normal, the same perspective projection (focal `1+√2`, aspect `w/h`), the
same LOD from `observeMercatorLod`. A tile counts as visible when any of a 13×13 sample grid over its
lat/lon patch is both front-facing and inside NDC. This under-counts a tile that straddles the viewport
with no sample inside, and neither model applies the selector's conservative `CANDIDATE_NEIGHBOUR_PADDING`
to the globe, so both columns are lower bounds by a small constant.

**Column A** keeps the LOD rule as-is. **Column B** matches the on-screen ground scale at the camera
latitude, `z_eff = zoom − log₂ cos φ`, which is what a globe implementation has to do if "zoom 12" is to
mean the same thing on a sphere as on the plane.

Harness viewport, 960×540:

| lat | zoom | lod | flat inst | flat canon | globe, LOD rule unchanged | globe, scale-matched |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 2 | 2 | 4 | 4 | 8 | 8 |
| 0 | 4 | 4 | 4 | 4 | 8 | 8 |
| 0 | 12 | 12 | 4 | 4 | 4 | 4 |
| 45 | 3 | 3 | 4 | 4 | 16 | 8 |
| 45 | 12 | 12 | 4 | 4 | 12 | 4 |
| 60 | 4 | 4 | 4 | 4 | 22 | 6 |
| 75 | 5 | 5 | 4 | 4 | 48 | 6 |
| 75 | 12 | 12 | 4 | 4 | 40 | 4 |
| 82 | 6 | 6 | 4 | 4 | **132** | 4 |
| 82 | 12 | 12 | 4 | 4 | 126 | 4 |

Phone viewport, 1179×2556:

| lat | zoom | lod | flat inst | flat canon | globe, LOD rule unchanged | globe, scale-matched |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 3 | 3 | 24 | 24 | 28 | 28 |
| 0 | 4 | 4 | 24 | 24 | 52 | 52 |
| 0 | 12 | 12 | 24 | 24 | 24 | 24 |
| 45 | 4 | 4 | 24 | 24 | 108 | **96** |
| 45 | 12 | 12 | 24 | 24 | 32 | 24 |
| 60 | 6 | 6 | 24 | 24 | 90 | 24 |
| 75 | 6 | 6 | 24 | 24 | 232 | 22 |
| 82 | 8 | 8 | 24 | 24 | **686** | 24 |
| 82 | 12 | 12 | 24 | 24 | 666 | 24 |

Peak multipliers over the flat canonical count: **33.0×** on the harness viewport (4 → 132, lat 82 z6) and
**28.6×** on the phone (24 → 686, lat 82 z8), both under the unchanged LOD rule. Under the scale-matched
convention the peak is **4.0×** (24 → 96, lat 45 z4) and the multiplier is **1.0×** at every zoom ≥ 6.

For calibration, the flat map's own existing divergent case — a pitched camera — is an order of magnitude
smaller: at 960×540, latitude 45, zoom 12, pitch 60° gives 32 instances against 4 unpitched.

### 3.3 What the numbers actually say

**The naive intuition — "a globe shows a hemisphere, so it wants hundreds of tiles" — is not what the
model finds, and the true cause is more actionable.** At low zoom the visible cap really is most of a
hemisphere (limb half-angle `acos(R/(R+dist))` is 83.6° at zoom 0), but the LOD is 0 or 1, so a hemisphere
is one to four tiles. At high zoom the LOD is fine but the frustum's fixed 22.5° vertical half-angle
sees only a small cap. Neither end explodes.

What explodes is **latitude**, and it is `MercatorLod.kt`'s stated identity failing. `MercatorLod.kt:11-18`
grounds the whole rule on `screenPixelsPerTexel = 2^(zoom - selectedLod)`, which is exact on a plane
because a Mercator tile's *output pixel* width does not depend on latitude. On a sphere, ground size does:
a tile at 82° is a ground square `cos 82° = 0.139` the side of an equatorial one, so `1/cos² 82° ≈ 51`
times as many are needed to cover the same screen area. 51× is the same order as the measured 33× and
28.6×, and the residual is the frustum and limb bounds trimming it.

Which means the multiplier is a **convention** consequence, not a geometry one, and Cycle G buys it back
by making the LOD track the globe's own screen-pixels-per-texel rather than Mercator's. That is the same
derivation `MercatorLod.kt`'s doc comment already performs — the calibration measurement it cites
("roughly `0.89 + 2.27 * ratio` output pixels of contrast-weighted road edge") would need redoing on a
sphere, but the shape of the rule survives.

---

## 4. What that does to the one public byte budget

`ResourceLimits.maximumResidentGpuTextureBytes` defaults to `128L * 1024L * 1024L`
(`Resources.kt:90`) and is validated into `1 .. Int.MAX_VALUE` (`:125-127`). It reaches the registry at
`RendererFactory.kt:115` — and only since the ground-draw task; `:111` records that it was inert before
that, which is the "a public field with no wiring" failure this cycle should not repeat.

A tile is `image.width * image.height * RGBA_BYTES_PER_PIXEL` (`RenGRenderer.kt:1459`) = 512 × 512 × 4 =
**1,048,576 bytes exactly**. So the default budget is **exactly 128 tiles**, and the 512-instance tile
budget is **512 MiB**, four times over it.

### The eviction policy, and why it does not fail

`GlObjectRegistry.evictOverBudget` (`GlObjectRegistry.kt:276-288`):

```kotlin
var total = textureByteSizes.values.sum()
val iterator = unleasedOrder.keys.iterator()
while (total > residentTextureByteBudget && iterator.hasNext()) { ... }
```

`textureByteSizes` covers every budget-tracked key regardless of lease state, so `total` is the true
resident total — but the eviction candidates are `unleasedOrder` only, and `takeLease` removes a key from
it (`:135-139`). The field's own doc says the rule out loud (`Resources.kt:84-88`): *"A texture still
leased by a live Prepared Frame is never evicted, even past this budget: this field bounds what MAY stay
resident, never what MUST."* And `RenGRenderer.kt:1391-1394` states the intent: *"exceeding the budget
because a live frame still needs a tile is the correct outcome; breaking a drawable frame to honour a
cache limit is not."*

That is a deliberate, correct design. Its consequence for a globe is the problem.

### Three regimes, and today's answer is the middle one

Leases are taken during `resolveGroundTiles` and released together in `performDraw`'s `finally`
(`RenGRenderer.kt:1198,1216`); every `releaseLease` calls `evictOverBudget`
(`GlObjectRegistry.kt:167`). So for a frame needing N canonical tiles:

| N | What happens |
|---|---|
| N ≤ 128 | Fits. Every tile survives to the next frame; a pan back costs nothing. Today's flat map (4 to 32) is here with 4× headroom. |
| 129 ≤ N ≤ 512 | **Silent thrash.** All N draw correctly — nothing fails, nothing warns. Then as the leases release, `evictOverBudget` deletes the least-recently-used unleased entries until the total is back under 128 MiB, discarding N − 128 tiles the *next* frame needs. Each returns as one PNG decode plus one `glTexImage2D`, and `RenGRenderer.kt:1399-1400` prices that: *"a 512x512 tile costs a megabyte of decode plus a megabyte of upload every time it is missed."* At N = 232 (measured, lat 75 z6 phone) that is 104 MiB of decode and 104 MiB of upload **per frame, forever**, at a steady camera. |
| N > 512 | Hard, clean failure: `TileSelectionOutcome.OverBudget` → `RESOURCE_LIMIT_EXCEEDED` at `FRAME_PLANNING` with `DiagnosticField.BASEMAP_TILE_INSTANCES`, `limit` and `actual` (`MercatorSpatialPlanner.kt:228-241`). The measured 686-tile case is here. |

**So: today, a globe of the kind §3 column A produces thrashes rather than fails**, across most of the
range it would actually occupy. That is the worse of the two outcomes, and it is a public-API consequence
in the precise sense the brief means: the default is too small for a globe, and a consumer who does not
know to raise it gets no signal at all — no exception, no diagnostic, just a frame budget quietly eaten
by re-decodes.

Three things follow, all for the design phase rather than for this document:

1. Under the scale-matched convention the peak is 96 tiles, which **fits inside 128 MiB with headroom** and
   makes this a non-issue. That is a second, independent reason to prefer it over the naive one.
2. If the globe can exceed 128 tiles at all, the silent-thrash band needs a *diagnostic* — the frame
   should be able to say it is over budget without refusing to draw. There is no such surface today; the
   only reporting channel is a thrown `RenGException`.
3. `maximumResidentGpuTextureBytes` is derived from nothing and coupled to nothing on purpose
   (`Resources.kt:86-89` argues explicitly against deriving it from `maximumBasemapTileInstances`). That
   argument still holds. Raising the *default* is a separate question from wiring the two together, and
   only the default is in play.

---

## 5. Multi-zoom visibility

**RenG picks exactly one LOD per frame and cannot express more than one.** The evidence is a type:

```kotlin
internal data class LodObservation(val selectedLod: Int)          // MercatorLod.kt:5
```

One `Int`. It is produced once per frame (`MercatorSpatialPlanner.kt:142`), passed once to
`selectBasemapTiles(footprint, lod, maximumInstances)` (`:149-153`), and every emitted
`BasemapTileInstance` and `CanonicalBasemapTile` carries that same `lod` field
(`BasemapTileSelector.kt:86-92, 99-106`). It is carried across frames as a single nullable `Int`
(`RenGRenderer.kt:479, 642`) and reset to `null` on context loss (`:1045`). There is no per-tile, per-row
or per-region LOD anywhere in the path.

**There is, however, a partial precedent worth not confusing with it.** `basemapTileSampleFor`
(`BasemapStyleManifest.kt:596-609`) does resolve one *source* tile per output tile at a different zoom:

```kotlin
if (tile.lod < source.minZoom) return null
val sourceZ = minOf(tile.lod, source.maxZoom)
val zoomDelta = tile.lod - sourceZ
val childScale = 1 shl zoomDelta
```

That is overzoom — past a source's `maxzoom` RenG asks for the z-`maxZoom` ancestor and Rentile
upscales the sub-rect (`DefaultBasemapRasterizer.kt:1603-1625`) — and it is **downward only, per source,
driven by the source's declared zoom range rather than by anything on screen**. A globe wants the
opposite: *coarser* tiles near the limb, where a tile's projected screen area collapses. Nothing in this
mechanism can produce that, and the `if (tile.lod < source.minZoom) return null` branch is a second
globe-relevant fact — at a globe's low zooms, sources whose `minzoom` exceeds the frame LOD contribute no
tile at all, silently.

Whether a globe *needs* per-tile LOD is a design question this document does not answer. What it can say
is the cost of the alternative: a single LOD chosen for the sub-camera point makes limb tiles minified by
the factor §2.3(c) describes, which is the aliasing that pushes towards mipmaps, which is the seam. A
single LOD chosen for the limb makes the centre blurry, which `MercatorLod.kt:31-35` already identifies
as "the blurry direction" and spends its whole hysteresis band avoiding. **The three questions — LOD
count, mipmaps, and seams — are one question.** If per-tile LOD is adopted, `LodObservation`,
`BasemapTileInstance.lod`, `CanonicalBasemapTile.lod`, `previousSelectedLod` and the
`observeMercatorLod` contract all move together, and `previousSelectedLod`'s single-`Int` hysteresis
needs a replacement that does not thrash per tile.

---

## 6. The firewall

**No impact. It is purely per-URL, and here is why.**

- **Routes are per-URL and deduplicated.** `tileTimeRoutes` (`BasemapStyleManifest.kt:389-407`) emits one
  `ResourceRouteKey` per (canonical tile × source), expanded over the 3×3 neighbourhood for `raster-dem`
  sources, and returns `routes.distinct()`. A route is
  `(accessMode, locator, resourceClass, maximumResponseBytes)` — no count, no batch, no window.
- **Preregistration has no count limit.** `OperationRegistry.preregister(routes)`
  (`OperationRegistry.kt:244-290`) iterates the list, resolves a private key per route, and inserts into
  five maps, failing only on a *collision* (`requireNoCollision`) — never on cardinality. Grepping the
  whole `internal/firewall/` package for a size or count `require` returns nothing.
- **Key derivation is per-resource.** `ProductionRentilePrivateKeyResolver`
  (`RentileKeyDerivation.kt:54-…`) reproduces Rentile's `sha256Hex(url.withRedactedAuthenticationQuery())`
  paired with Rentile's `ResourceClass`. A URL in, a key out. Nothing observes how many.
- **The only count sensitivity in the file is a performance note, already accounted for.**
  `OperationRegistry.kt:267-269`: *"a pure-Kotlin SHA-256 per route adds up across a 512-instance tile
  plan."* So the authors already sized this for the existing 512-instance ceiling, and a globe that stays
  under that ceiling adds nothing new. A globe that *exceeds* it never reaches the firewall at all,
  because `selectBasemapTiles` refuses first (§4).
- **ADR 0016's own scaling constant is upstream of RenG.** The ADR describes "a 256-tile batch at
  concurrency eight" as Rentile's internal fan-out; that is `DefaultBasemapRasterizer`'s behaviour, not a
  RenG limit, and it bounds concurrency rather than count.

The one thing that *would* matter is variety rather than count: a globe frame reaching more distinct
**sources** (not more tiles of the same source) multiplies routes per tile. But that is already how a
multi-source style behaves on the flat map, and `distinct()` plus per-URL keying handle it identically.

A clean "no impact" — worth stating so the cycle does not spend a task re-establishing it.

---

## 7. Rentile `0.6.0`, tiles only

`0.5.0 → 0.6.0` is exactly one commit, `87ccba2`. Classifying every changed declaration in the KLIB ABI
dump by owning type:

```
$ git diff d899cb2 87ccba2 -- kmp/api/kmp.klib.api | grep -E "^[+-][^+-]" \
    | grep -oE "com\.rohittp\.rentile/[A-Za-z]+" | sort | uniq -c | sort -rn
  73 LabelCandidate    72 LabelIconRef      37 SymbolAlignment   23 LabelIconAnchor
  21 SymbolOverlap     18 LabelLinePoint    18 IconTextFit       17 SymbolZOrder
  17 LabelPlacement    12 LabelLayerStyle    8 TileId             6 LabelGlyphQuad
   6 LabelBox           1 DiagnosticCode
```

220 changed declaration lines, **every one of them label or icon**. The eight `TileId` occurrences are
`TileId` appearing inside `LabelCandidate`'s own constructor signature, not a change to `TileId`.
`RenderOptions`, `TileId`, `RenderedTile`, `RenderBatch`, `PreparedBatch`, `ResourceLimits`,
`TileSubstitutionPolicy`, `ResourceAccessMode`, `outputRequestKey`, `prepareBatch` and `render` are
untouched in the ABI.

Four things nonetheless reach tiles, and they are all in `docs/migrations/0.6.0.md`:

1. **`ContentIdentity.kt` is unchanged.** `git diff --stat d899cb2 87ccba2 -- '*ContentIdentity*'` is
   empty. This is the one that could have been expensive: RenG reproduces
   `sha256Hex(withRedactedAuthenticationQuery(url))` byte-for-byte in `RentileKeyDerivation.kt`, and its
   doc comment records that a wrong-but-well-formed key is *"a permanent, unannounced cache miss, never a
   thrown failure"*. It stays correct at `0.6.0`.
2. **The renderer semantic marker moves**, `rentile-renderer-3` → `rentile-renderer-4`
   (`StyleCompiler.kt:2213`), which changes `PreparedStyle.digest` for the same style document. RenG
   derives every basemap `ResourceKey` from it (`BasemapEngineHost.kt:484,494` → `basemapTileKey(styleDigest,
   …)`), so every RenG basemap key changes on upgrade. RenG owns no persistent cache, so this is a cold
   start, not a defect — but a *consumer* caching anything keyed by a RenG basemap `ResourceKey` would see
   a full invalidation. The consumer `Store`, keyed by redacted URL, is unaffected.
3. **Output tile pixels change for the same inputs**, by design: `rentile-output-request-1` → `-2`
   (`DefaultBasemapRasterizer.kt:248`) and `rentile-output-2` → `-3` (`:1330`). The migration doc names
   the causes — `line-round-limit` now honoured so round-join lines produce different edge pixels;
   per-feature evaluation of `symbol-placement`, `icon-anchor`, `icon-padding`, overlap, rotation/pitch
   alignment, translate alignment, upright, edge-avoidance and z-order for Output Tile icons; five new
   expression operators (`!=`, `<`, `>`, `slice`, `to-string`) plus stricter comparison typing. **None of
   this changes the tile contract** — still one 512×512 untagged RGBA8 PNG per `TileId`, still edge-exact
   by the §2.1 construction, which `87ccba2` does not touch.
4. **`maxGlyphRangesPerBatch` default rises 64 → 256** (`Api.kt:648`). Label-only; RenG never calls
   `acquireLabelCandidates` and the field is a default rather than an ABI change.

**For Cycle G's subject specifically: nothing in `0.6.0` touches tile selection, the rasterization output
contract, or the tile cache identity.** The upgrade is neutral here, and the decision belongs to the
concurrent E-labels investigation, not to this one.

---

## 8. What could not be established, and what would settle it

**The globe tile counts are a model, not RenG.** `scratchpad/globe.py` uses RenG's real camera arithmetic
but a stated globe convention RenG has not adopted, a 13×13 per-tile visibility sample rather than exact
patch–frustum intersection, and no `CANDIDATE_NEIGHBOUR_PADDING`. The flat column is validated against
`FramePlanningCoreTest.kt:285` exactly; the globe column is not validated against anything, because there
is nothing to validate against. **What would settle it:** a spike that adds a globe footprint to
`selectBasemapTiles` behind a flag and prints `tileSelection.canonicalResources.size` over the same
lat/zoom sweep. That is Kotlin, so it was out of scope here.

**Rentile's raster-source tile edges are unmeasured.** §2.2 measured a vector (topographic) style. A
`raster` source at `childScale == 1` goes through `drawImageRect` with `SamplingMode.LINEAR` over the
whole source image (`DefaultBasemapRasterizer.kt:1603-1625`), and Skia's linear filter clamps at the
image edge — which could introduce a half-texel effect that the vector path does not have. **What would
settle it:** the same column-difference measurement on a nine-tile mosaic from a raster or satellite
style. `docs/examples/satellite-z12-1205-1540.png` is a single tile, so the corpus does not contain one;
producing one needs a style URL, which carries the owner's key.

**The `sBIT`-but-untagged colour claim is from three shipped example PNGs, not from a live render.**
All three (`topo-`, `satellite-`, `glow-z12-1205-1540.png`) show `IHDR, sBIT, IDAT, IEND` and nothing
else, which is consistent with `Surface.makeRasterN32Premul` being given no `ColorSpace`. **What would
settle it definitively:** a `Skia` version check, or an `androidDeviceTest` run capturing a freshly
rendered tile's chunk list.

**The seam behaviour of a subdivided sphere patch is reasoned, not measured.** §2.3(b) and (c) argue from
the sampler state (`GlTextureUpload.kt:47`), the absence of a mipmap chain (`:147-149`) and the quad's
`0..1` UVs (`GroundPipeline.kt:63-68`). No sphere has been drawn. **What would settle it:** the readback
suite's own idiom — draw two adjacent patches at a known subdivision and count pixels disagreeing with
the analytic boundary, exactly as `measureLargeQuadRasterisation`
(`kmp/src/commonTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt:669`) already does for the flat
ground. That is the cheapest real answer available and it needs no style URL.

**The LOD rule's globe recalibration is not attempted here.** `MercatorLod.kt:20-27` cites a measured
affine relation between `screenPixelsPerTexel` and perceived road-edge width, taken on the visual harness
over a real style. The equivalent measurement on a sphere would need a globe to exist first. **What would
settle it:** the same harness procedure, after a first drawing globe, before the LOD constants are frozen.

**Nothing here was run against a device or an emulator**, and nothing here needed to be — every claim is
source, arithmetic, or a decode of a checked-in PNG.
