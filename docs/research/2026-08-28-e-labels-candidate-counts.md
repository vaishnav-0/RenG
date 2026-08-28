# E-labels: how many label candidates a frame really produces, and what they cost

Decision **E5** was *no public ceiling on labels, measure first*. This is that measurement. It produces
numbers and nothing else: no `RendererConfiguration` field, no `ResourceLimits` field, no constant that
bounds anything, and no code checked in.

Measured 2026-08-28 on `el/t17-candidatecounts` at `ab2f05e`, Apple M3 Max, Rentile `0.6.0` (the pin in
`gradle/libs.versions.toml`; do not quote it from here). Two harnesses were written, run and deleted —
see **Method** for exactly what they were and what they are not.

This is the spike `2026-08-28-e-labels-corpus-statistics.md` §7 asked for: that document counted what 34
real styles *declare* and said plainly that "how many candidates a real viewport produces, how many glyph
ranges it needs, and how large the resulting atlas is are all still unmeasured — they need a running
handover, which is a separate spike."

**Every candidate figure below comes from a synthetic fixture, not from a real style over real tiles.**
The corpus styles carry the owner's API key and none is in the repository, so nothing here saw a real
viewport. Read **What a synthetic fixture cannot tell anyone** before quoting any number as a corpus
fact.

---

## Headline

**1. A corpus-shaped frame produces a few thousand candidates and keeps a few hundred.** On a
1179×2556 phone viewport at zoom 12.3 the frame selects **17 canonical tiles**; 36 text layers at 4
features per layer per tile give **2,448 candidates**, of which **442 are placed** and **5,304 glyph
quads** reach the batch. At a dense-POI density — 24 features per layer per tile — the same frame
produces **14,688 candidates**, places **605**, and uploads **7,260 quads**. The *placed* count barely
moves, because it is bounded by viewport area rather than by candidate count.

**2. Placement and collision are not the problem.** They are linear in the candidate count and cost
about **1 µs per candidate on a JIT-warm JVM** (the Android runtime's shape) — 2.4 ms for 2,448
candidates, 15 ms for 14,688, 197 ms for 200,000. On the debug Kotlin/Native test binary the same code
is 17–31× slower. Nothing here argues for a ceiling on labels.

**3. The glyph atlas does not threaten the 512 MiB GPU residency budget. It fails the frame long
before it gets near it.** `ResourceLimits.maximumDecodedImageBytes` is **64 MiB**, which
`decodePng` enforces as a hard **16,777,216-pixel** ceiling on the atlas. Measured: a plan needing
**144 glyph ranges** packs an **8190×2250** atlas — 73,710,000 decoded bytes — and RenG's own decoder
answers **`TooLarge`**, so the whole frame fails with `RESOURCE_DECODE_FAILED`. **Rentile's own recorded
worst case of 159 ranges / 8192×4357 / 136 MiB is 2.1× past that ceiling and would fail every frame it
appeared on.**

**4. The unbudgeted cost is that the whole label path runs again on every single frame.** Measured
through the public API on a real CGL context, over the corpus-shaped fixture and a stationary camera:

| frame | `prepare()` |
|---|---:|
| `drawBasemap=false, drawLabels=false` | **0.05–0.15 ms** |
| `drawBasemap=true, drawLabels=false` | **93–102 ms** |
| `drawBasemap=false, drawLabels=true` | **514–520 ms** |
| both | **605–610 ms** |

The label path is **~85% of `prepare()`** on a frame that draws both, and **5.5× the ground path** over
the same 17 tiles. Frames 1 and 2 cost what frame 0 cost, to within 1%: nothing is retained. A separate
probe shows what is repeated — three acquisitions of the identical tile set, in three separate
invocations of one live host, asked the consumer's `Transport` for **all 17 label tiles and all 16 glyph
ranges every time** (17→34→51 and 16→32→48 requests) and re-packed a **byte-identical** atlas, same
`contentKey` on all three. Rentile publishes `labelCandidateRequestKey` for exactly this cache and RenG
calls it nowhere.

These are debug-binary figures (`linkDebugTestMacosArm64`); see **Method** for why the ratios survive
that and the absolutes do not.

---

## 1. Candidates per frame

### 1a. Tiles per frame — real RenG code, no fixture involved

The label handover is handed `tileSelection.canonicalResources`, the *same* list the ground reads
(`RenGRenderer.kt:712-716`), so the candidate count scales with it. Peak canonical tiles over a sweep of
latitudes, bearings, zooms and LOD histories, run through `planMercatorSpatial`:

| viewport | pitch 0 | 30° | 45° | 60° |
|---|---:|---:|---:|---:|
| 960×540 (harness) | 20 | 23 | 33 | 97 |
| 1179×2556 (phone) | 76 | 88 | 132 | 469 |
| 2560×1440 | 83 | 102 | 154 | 507 |
| 3840×2160 | 166 | 209 | 318 | 511 |

These reproduce `2026-08-28-tile-residency-headroom.md` exactly on the rows the two sweeps share — 20 at
960×540 and 166 at 3840×2160, pitch 0, that document's own reduced-grid figures (it reports 167 for the
4K row on its full grid and says so). That agreement is the cross-check that this sweep is running the
real selector rather than a model of it.

The census camera — phone viewport, zoom 12.3, bearing 17.5°, Paris — selects **17 tiles at pitch 0** and
**30 at pitch 45°**. Those are ordinary frames, not the peaks.

### 1b. Candidates, quads and placement

Fixture density is `symbol layers × features per layer per tile × tiles`, and it is exactly linear in all
three. Measured (see **Method** for what each fixture is):

| fixture | tiles | candidates | glyph quads produced | placed labels | quads uploaded |
|---|---:|---:|---:|---:|---:|
| the cycle's own `LabelHandoverFixture` shape | 1 | 2 | 24 | 2 | 24 |
| corpus-shaped, 1 tile | 1 | 144 | 1,728 | 55 | 660 |
| corpus-shaped, 5 tiles | 5 | 720 | 8,640 | 206 | 2,472 |
| **corpus-shaped, whole frame** | **17** | **2,448** | **29,376** | **442** | **5,304** |
| corpus-shaped, 30 tiles (pitch 45°) | 30 | 4,320 | 51,840 | 436 | 5,232 |
| **dense POI, whole frame** | **17** | **14,688** | **176,256** | **605** | **7,260** |

**The placed count saturates and the candidate count does not.** 17 tiles place 442 and 30 tiles place
436 — the extra 13 tiles add 1,872 candidates and *no* labels, because the viewport was already full.
Six times the candidate density (dense POI) buys 37% more placed labels. A 1179×2556 viewport is
3.01 Mpx and one of these labels claims 188.3×21.5 = 4,049 px² — `LabelBox` already carries
`text-padding`, and `placeLabels` deliberately does not add it a second time
(`LabelPlacement.kt:326-329`) — so the geometric ceiling on non-overlapping placements is ~744.
442–605 measured is the right order.

**What the batch uploads is small.** `LABEL_VERTEX_FLOATS` is 15, so a quad is 4 × 15 × 4 = 240 bytes of
vertex data plus 6 × 4 = 24 bytes of index: **264 bytes per quad**. The whole-frame corpus-shaped batch
is **1.40 MB**, the dense-POI one **1.92 MB**, in one `glDrawElements`. That is not a budget problem.

Extrapolating the two densities onto the tile sweep — arithmetic, not measurement — a 3840×2160 frame at
pitch 45° (318 tiles) would produce **45,792** candidates corpus-shaped and **274,752** dense. See §4 for
why the first is survivable and neither is what actually hurts.

---

## 2. Glyph ranges, atlas dimensions, and the ceiling that bites

### 2a. The packing law, derived from five measured points

Rentile shelf-packs at a fixed `SHELF_WIDTH_PX = 8192` (`GlyphAtlasPacker.kt:49`). For a uniform cell of
`c` pixels — a provider glyph of `g` pixels plus the mandatory 3-pixel SDF buffer on each side, so
`c = g + 6` — the atlas is exactly:

```
perRow = floor(8192 / c)
width  = min(N, perRow) * c
height = ceil(N / perRow) * c
```

Every measured atlas obeys it, at `g = 24`, `c = 30`, `perRow = 273`:

| ranges | glyphs packed (N) | atlas | decoded bytes | `decodePng` |
|---:|---:|---|---:|---|
| 4 | 128 | 3840×30 | 460,800 | ok |
| 8 | 752 | 8190×90 | 2,948,400 | ok |
| 16 | 1,776 | 8190×210 | 6,879,600 | ok |
| 72 | 7,992 | 8190×900 | 29,484,000 | ok |
| **144** | **20,376** | **8190×2250** | **73,710,000** | **`TooLarge`** |

Real ranges carry glyphs of differing heights, so a real atlas's shelves vary and the law is the
uniform-cell case. It nevertheless reproduces Rentile's own recorded worst case closely: 159 ranges of
~256 glyphs is ~40,700 cells, which at a 28–30 pixel cell is 8192 by 4,200–4,500 — the migration
document's **8192×4357**.

### 2b. Where RenG refuses

`decodePng` compares `pixelCount > maximumDecodedBytes / 4` from the PNG header alone
(`internal/image/PngDecoder.kt:63-64`), and `maximumDecodedImageBytes` defaults to 64 MiB
(`Resources.kt:87`). So the atlas ceiling is:

> **16,777,216 pixels — an atlas of 4096×4096, or ~18,640 glyph cells at a 24-pixel em, or about
> 73 full 256-glyph ranges.**

`RenGRenderer.decodeGlyphAtlas` (`:841`) turns anything larger into `RESOURCE_DECODE_FAILED` at
`RESOURCE_DECODING`, naming `BASEMAP_GLYPH_RANGE`. That is the *whole frame*, not the text: there is no
"draw the map without its labels" path.

**RenG's ceiling is a quarter of what Rentile may legally hand it.** Rentile's own `ResourceLimits` allow
`maxRasterDimensionPx = 8192` and `maxDecodedRasterBytes = 256 MiB`, and `maxGlyphRangesPerBatch` is
**256**. The shelf width was widened to 8192 *specifically* so those 256 ranges reach the packer rather
than dying in it — the packer's own KDoc works the case and puts "256 dense CJK ranges" at "65536 cells"
and **197 MiB**, in contract. An engine sized for a 197 MiB atlas is paired with a consumer whose decoder
refuses at 64 MiB. No test in RenG's tree builds an atlas within two orders of magnitude of either
number — every label suite's atlas is a handful of glyphs — so nothing in the repository would notice.

### 2c. Against the GPU residency budget

`uploadGlyphAtlas` enrols the atlas through `GlObjectRegistry.registerTexture`
(`internal/gl/GlyphAtlasUpload.kt`), so its bytes count against
`maximumResidentGpuTextureBytes` — 512 MiB, whose only other tenant is basemap ground tiles.

Because §2b caps the atlas at 64 MiB, an atlas is at most **12.5% of that budget**. A frame at the
512-instance tile ceiling is 512 MiB of tiles exactly (the two defaults were sized to agree), so tiles
plus a maximal atlas is 576 MiB — 64 MiB, 12.5%, over. Both are leased for the duration of the frame and
a leased texture is never evicted, so that frame simply runs 64 MiB over budget rather than thrashing.
Nothing measured here shows the atlas threatening residency.

**The atlas's real per-frame cost is the decode, not the residency.** The whole-frame corpus-shaped
atlas — 6.88 MB decoded, a modest 16 ranges — takes **120–130 ms to decode**, and it is decoded on
**every** `prepare` that keeps a label (`RenGRenderer.kt:796`). The decode happens before anything
consults GL residency, and plausibly has to: `prepare` is a suspend function that may resume off the
thread holding the context, and `GlObjectRegistry` belongs to the draw. The 72-range atlas takes
**500–517 ms**. That is
24% of the label path's own time at 16 ranges, and it buys a `DecodedImage` that `uploadGlyphAtlas`
throws away on every frame after the first.

---

## 3. `prepare()`, decomposed

The four-pairing table in the headline is the measurement; this is what is inside the 515 ms label frame,
measured part by part on the same fixture:

| stage | ms | share |
|---|---:|---:|
| `BasemapEngineHost.acquireLabelCandidates` (plan + fetch + decode + pack) | 300 | 58% |
| `decodeGlyphAtlas` | 122 | 24% |
| `placeLabels` | 67 | 13% |
| `advanceLabelFade` | 8 | 2% |
| *sum of parts* | *497* | *97%* |
| *measured whole* | *515* | *100%* |

The parts summing to the whole is the cross-check that nothing large is unaccounted for.

Acquisition against tile count, corpus-shaped, 16 ranges: 1 tile 148 ms, 5 tiles 184 ms, 17 tiles 300 ms,
30 tiles 432 ms — about **137 ms fixed plus 9.8 ms per tile**. Against range count at one tile: 8 ranges
75 ms, 16 ranges 148 ms, 72 ranges 559 ms, 144 ranges 1,381 ms. Against candidate count at one tile: 144
candidates 147 ms, 864 candidates 206 ms.

**None of that includes network latency.** The census transport answers from memory. A real frame adds
17 tile fetches and 16 glyph fetches on top, every frame, unless the consumer's `Store` serves them.

---

## 4. The two questions this was for

### Does collision cost scale in a way that needs bounding? — **No, on the evidence available.**

`placeLabels` over the engine-measured candidate shape (12 glyph quads, a 188.3×21.5 label-local box,
`text-padding` 2, `text-overlap: never`), re-anchored onto a geographic grid:

| n | JVM (`testAndroidHostTest`, JIT warm) | Kotlin/Native debug (`macosArm64Test`) |
|---:|---:|---:|
| 1,000 | 4.0 ms (cold) | 25–30 ms |
| 10,000 | 16.8 ms | 280–320 ms |
| 50,000 | 61.0 ms | 1,452–1,571 ms |
| 200,000 | 197.3 ms | 6,142–6,665 ms |

**Linear.** 20× the candidates costs 11.7× the time on the JVM (JIT still warming at the small end) and
21–24× on native. Per candidate: **~1.0 µs JVM**, ~30 µs native-debug — the same direction as this
repo's only other same-code both-runtimes measurement, `BasemapRouteDerivationCostTest`'s 9–14× on route
derivation, and larger, which this loop being far more float-heavy would account for.

Two structural reasons it stays linear, both visible in the numbers:

- **The index cannot grow.** Only boxes intersecting the viewport are inserted, and a label claims
  ~4,050 px², so a phone viewport holds at most ~740 of them. Measured, the index saturates at 240–545
  entries however many candidates are offered. `LABEL_COLLISION_CELL_PIXELS` is 64, so one label spans at
  most 4×2 cells and a query touches at most 8 short lists.
- **A rejected candidate is cheap and a projected one is most of the cost.** Candidates placed entirely
  off-screen cost 8.5–15 µs each on native against 25–33 µs on-screen, so roughly 40% of the work is the
  projection every candidate pays and 60% is the layout and collision only on-screen ones pay.

At the densities §1 measures — 2,448 and 14,688 candidates — placement is **2.4 ms and 15 ms** on the
JVM. The 4K-at-45° extrapolation of 45,792 corpus-shaped candidates is ~46 ms on the JVM, which is a real
problem for a 60 Hz frame. But on the one binary where both were measured, that same frame's
*acquisition* extrapolates to ~3.3 s against its placement's ~1.4 s — and the acquisition figure excludes
318 tile fetches and every glyph fetch, because the census transport answers from memory. **The constant
factor at realistic n is ~1 µs, the growth is linear, and the thing that would need bounding first is not
this.**

### Does the glyph atlas threaten the GPU residency budget? — **No. It threatens the frame.**

It is capped at 64 MiB — 12.5% of the 512 MiB budget — by `maximumDecodedImageBytes`, so it cannot
meaningfully compete with tiles for residency. What it can do, measured, is make the frame fail: **144
ranges is already `TooLarge`**, and Rentile's own worst case of 159 ranges is 2.1× past the ceiling.
Between the two limits is a gap nothing in either project tests: Rentile sizes its packer for 256 ranges
and 197 MiB, and RenG's decoder refuses at 64 MiB.

Two things follow, both stated as findings rather than as changes this task is authorised to make:

- The relevant number for a future `maximumDecodedGlyphAtlasBytes` — if one is ever wanted — is **not a
  candidate count**. It is a *pixel* count, and the honest one is measured against a real style's font
  stacks, which this task could not reach.
- A frame that loses its labels to an oversized atlas currently loses the whole map with it. Whether that
  is right is a design question this document does not settle; it is recorded because it is not obvious
  and nothing else names it.

---

## 5. What a synthetic fixture cannot tell anyone

**A number from a 36-layer synthetic fixture is a lower bound on a 37-symbol-layer real style, and it is
not the same kind of number.** Specifically:

- **Features per layer per tile is a guess, and it is the one input every candidate count is linear in.**
  4 and 24 were chosen to bracket plausibility, not measured. A real `poi` layer in a downtown tile can
  carry hundreds of features; a real `country_label` layer carries none at that zoom. Nothing here models
  that spread, and the corpus statistics document explicitly could not either — its counts are what a
  style *declares*, "the right measure for scope and the wrong one for performance".
- **No `filter`, no zoom range, no data-driven `text-field` was evaluated against real data.** Every
  fixture layer produces a candidate for every feature. Real styles reject most of both.
- **Every tile in the fixture carries the same bytes.** A real 17-tile viewport has 17 different feature
  sets; a water tile and a city-centre tile differ by orders of magnitude.
- **All placement is `POINT`.** The corpus has 300 `line` and 26 `line-center` layers across 31 of 34
  styles, and a line candidate produces *several* placed labels — one per `symbol-spacing` repeat along
  the line. Line placement can only raise the placed count and the quad count, and nothing here measures
  it.
- **No icons.** 681 corpus layers carry one, 611 of those alongside their text. Icons add their own
  collision boxes to the same index.
- **The synthetic glyph is square and wide.** A 24×24 declared glyph makes a 12-character label 188 px
  wide, where a real proportional face would be nearer 90–110. Wider labels collide more, so the *placed*
  counts here are, if anything, pessimistic and the *candidate* counts unaffected.
- **The fixture glyph ranges are uniform.** Real ranges mix heights, so §2a's packing law is the
  uniform-cell case rather than a prediction of a specific real atlas.
- **Absolute times on `macosArm64Test` are from an unoptimised debug binary** (`linkDebugTestMacosArm64`
  compiles RenG *and* Rentile without optimisation). They overstate release cost, by an unmeasured factor
  this repo has previously measured at 9–14× on route derivation, and §4 here measures at 17–31× on
  placement. The *ratios* within one binary — label path vs ground path, frame
  1 vs frame 2, 8 ranges vs 144 — are not affected by that and are the parts worth quoting. The JVM
  column in §4 is the closest thing here to a shipping runtime, since RenG's `android` target is JVM
  bytecode.
- **Nothing was drawn.** No pixel was read back and no legibility claim is made or implied.

### What a real-corpus run would settle

One run of the same harness against the 34 corpus styles and their real tiles would settle, in one pass:
the true features-per-layer-per-tile distribution and therefore the real candidate count; the real glyph
range count per viewport, and whether any real style approaches 73 ranges; the real atlas dimensions and
whether `TooLarge` is reachable in practice or only in principle; and the line-placement and icon
contributions that are entirely absent here. It needs a credentialled style URL and live tiles, which is
why it is not in this document.

---

## Method

Two temporary Kotlin test files, written, run and **deleted before commit** — the same shape as
`2026-08-28-e-labels-corpus-statistics.md` and `2026-08-28-tile-residency-headroom.md`, which also check
in no code. Nothing in `kmp/api/kmp.klib.api`, `internal/label/`, `internal/gl/` or `docs/adr/` was
touched, and the test counts on every target are unchanged: `macosArm64Test` is **1,319 tests, 0
failures, 0 errors, 0 skips** at `ab2f05e` both before and after this document, summed from Gradle's own
JUnit XML.

**`kmp/src/macosTest/.../LabelCandidateCensusTest.kt`** — the census. It drives the real path:
`deriveBasemapStyleManifest` → `tileTimeRoutes` → `BasemapEngineHost.acquireLabelCandidates` (which is
the two-round preregistration, `planLabelCandidates`, `glyphUrls` and `acquireLabelCandidates`) →
`placeLabels` → `advanceLabelFade` → `decodePng`. It also runs `planMercatorSpatial` for the tile sweep,
and `createRenderer` + `prepare` on a real `CglCoreProfileContext` for §3's four pairings. `macosTest`
rather than `commonTest` because the atlas packer is Skia and the Android host JVM cannot load it.

**`kmp/src/commonTest/.../label/LabelPlacementScalingCensus.kt`** — §4's scaling table, in `commonTest`
so the identical code runs on both `macosArm64Test` and `testAndroidHostTest`. It reuses the cycle's own
`placementCandidate` / `PLACEMENT_ATLAS` fixtures, with the candidate shape set to what the engine
actually produced in the census — that shape was printed by the census first and copied, not invented.

**The fixture.** A generated style with `symbolLayers` symbol layers over `symbolLayers` source layers,
each with its own `text-font` drawn from `fontStacks` distinct stacks, `text-size` 14, `text-padding` 2,
a halo, and `text-field: {name}`. A hand-encoded MVT with `featuresPerLayer` point features per source
layer, spread on a grid inside the 4096 extent and displaced per layer so two layers do not stack on the
identical anchor — without that displacement every layer after the first loses its whole grid to
collision and the placed count is understated by the layer count. Feature text is 12 codepoints drawn
round-robin from the enabled 256-wide blocks. Glyph ranges are generated per `(stack, block)` with
`glyphsPerRange` glyphs at 24×24 declared pixels, which is what makes the packed cell 30×30. The style
base URI, tile template and glyphs template are the cycle's own `LabelHandoverFixture` constants, so the
route derivation and the exact-string firewall are exercised as they are in production. The transport
answers from memory and the store misses everything.

The four block alphabets are ASCII printable (94), Latin Extended-A (128), Greek (120) and Cyrillic
(256), all simple scripts — a complex script produces no glyph quads at all in Rentile `0.6.0` and would
have made the whole census vacuous.

**Sources.** Both files were preserved outside the repository at
`<session scratchpad>/t17-harness/`, at
`sha256 26ca1cdccddb3d7a5130efca31e05817c7afb20a41a47b13f5db270f1fddd7da` (census, 675 lines) and
`0b3f3b49aa005fdb1f409e8168991fbb6f413abcc6d06e26400fe32b6591c95e` (scaling, 93 lines). That location is
session-local; the description above is what survives.

**Runs.** `./gradlew --no-configuration-cache :kmp:macosArm64Test --tests
"com.rohittp.reng.LabelCandidateCensusTest"` and the same with
`:kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.label.LabelPlacementScalingCensus"`. Every
timing is one untimed warm-up pass followed by the measured pass, except the acquisition and the
`prepare` figures, which are single measured passes reported per frame — precisely so that frame 0 and
frame 2 can be compared, which is how §4's "nothing is retained" is established.

### The vacuity this measurement nearly shipped

The first census reported `placed = 0` for five of its eight fixtures and `placed = 35` where it should
have been 442, and both readings looked plausible. Two independent causes:

1. **A one-tile fixture was given the wrong tile.** `selection.take(1)` picks the first tile of the
   selection, which is a corner of the viewport, not the tile the camera is centred on — so every label
   in it was off screen. Fixed by taking the tile nearest the selection's centroid.
2. **Every layer's features sat on the identical anchors.** All 36 layers used the same grid, so 35 of
   every 36 labels lost the collision to the first and the placed count read as 1/36 of the truth. Fixed
   by displacing each layer's grid by its own offset.

Both produce *small, believable* numbers rather than obviously wrong ones, and neither would have been
caught by any assertion — which is the same shape as the twelve vacuous checks this cycle's plan warns
about, in a measurement rather than in a test.
