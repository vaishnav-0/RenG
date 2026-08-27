# Can a flat mercator camera outrun the 128 MiB GPU texture budget?

**Date:** 2026-08-28. **Branch read:** `main`, RenG HEAD `4d243ba`. Nothing in the repository was
changed by this document's author except the file you are reading. No Kotlin was edited, no branch
created, nothing committed.

## The answer

**Yes, and an unpitched camera does it on a 4K display.** Measured by running RenG's own
tile-selection code:

| viewport | max canonical tiles at **pitch 0** | vs the 128-tile budget | first pitch that exceeds 128 | max in any legal frame |
|---|---:|---|---:|---:|
| 960×540 (the harness's own) | **20** | 6.4× headroom | 62.5° | **512** |
| 1179×2556 (phone) | **76** | 1.68× headroom | 45° | **512** |
| 2560×1440 | **84** | 1.52× headroom | 40° | **512** |
| 3840×2160 | **167** | **1.30× over** | **0°** | **512** |

A canonical basemap tile is one GPU texture of exactly 1,048,576 bytes, so those counts are MiB.
167 tiles on a 4K display at pitch 0, bearing 44.9°, latitude 60.1699°, zoom 4.000001 with the
previous frame's LOD 5 still held — 167 MiB against a 128 MiB default, **39 MiB and 39 tiles into
the thrashing band with the camera perfectly level.** The camera that does it is an ordinary
zoom-out: the frame before it sat at zoom ~4.6, `observeMercatorLod` selected LOD 5, and the
hysteresis rule holds LOD 5 all the way down to zoom 4.0 (`MercatorLod.kt:46-70`, retained while
`zoom ∈ (lod − 1, lod + 0.5]`).

**Hysteresis is what does it, and that is measurable on its own.** With no LOD history the same 4K
viewport at pitch 0 peaks at **93** tiles — comfortably inside the budget. With one frame of history
it peaks at **167**. The 128-tile budget therefore sits *between* RenG's historyless worst case and
its with-history worst case on a 4K display, which is the least comfortable place for it to sit.

Every viewport reaches **512** canonical tiles — 512 MiB, **4× the default budget** — before
anything fails, because `maximumBasemapTileInstances` (512) binds first and every one of those 512
instances can be a distinct canonical tile. The 960×540 harness viewport gets there at pitch 65°;
the 4K viewport at pitch 57.5°.

---

## Method, and why it is trustworthy

**This is a run of RenG's real code, not a model of it.** Seven source files were copied *unmodified*
out of `kmp/src/commonMain` into a scratch Kotlin/JVM project outside the repository and compiled
there, which is what makes their `internal` declarations reachable from a driver. Byte-identity is
recorded so the claim is checkable:

```
0fc84aa5069b05591d1eac344f45d5a6b0631ee156609e841c24a141cacf6a96  internal/planning/BasemapTileSelector.kt
65ac8ca2f44ebee863604300901a27bb34d912fd7a137654fca8a52d4b533876  internal/planning/MercatorLod.kt
f1d08923e8c88bed99ed9be8db308c591591ac2d276d425c35c14e446d3094c6  internal/planning/SpatialPlanningResult.kt
c5d2376643522ba18c30b86298ba69a4349204a605c5b89ab57fe52ab4622ce8  internal/projection/CameraMatrices.kt
d3887e39a3b1f58c5b495227ec2d713cab1a11d8aef720524d06e4ea34205a78  internal/projection/MercatorGroundFootprint.kt
61f3ba22635d759bf1b7a0f01c7d163a2e0ed98b7ac8882770a8bcebfa2f2dc1  internal/projection/MercatorProjection.kt
0447a0995a414c3bc4e39fe7544325da14d0592e84e1650eacb93f6a3d0f1262  internal/math/DoubleLinearAlgebra.kt
```

The driver calls exactly what `planMercatorSpatial` calls, in the same order
(`MercatorSpatialPlanner.kt:138-157`): `resolveMercatorCamera` → `observeMercatorLod` →
`clippedPhysicalPixelFootprint` → `selectBasemapTiles`. Only the leaf types the copied files
reference on their **failure** paths were stubbed — `PipelineStage`, `RenGErrorCode`, `Diagnostic`,
`DiagnosticField`, `FailureDescriptor`, `failureContextDiagnostic` — plus `Camera` and
`OutputPixelSize`, whose `require` blocks were copied verbatim. None of those participates in tile
arithmetic, and no failure path is taken by any counted sample.

**Validated the same way the globe study was, against four asserted values rather than one.** All
four reproduce exactly:

```
FramePlanningCoreTest:285   lod=0 instances=3 canonical=1        (test asserts 3 instances)
BasemapTileSelectorTest     OverBudget(limit=4096, actual=32769)  (test asserts exactly this)
BasemapTileSelectorTest     OverBudget(limit=1, actual=576478344489467904)
BasemapTileSelectorTest     OverBudget(limit=1, actual=8)
```

The first exercises the whole camera→footprint→selection chain, which the other three do not; the
other three exercise the selector's counting arithmetic at scales the first does not reach.

**The sweep is asymmetric on every axis, deliberately.** A camera at latitude 0, longitude 0,
integer zoom, bearing 0, pitch 0 is symmetric in every axis and discriminates nothing. So: latitudes
`-41.2865, 0, 12.9716, 37.7749, 51.5074, 64.1466, 78.2232, 85.0511287798066` (both hemispheres, the
mercator limit included); longitudes `-122.4194, 13.4050, 174.7633`; bearings `0, 17.5, 44.9, 213.7`;
19 pitches; and 115 zoom cases built around the *fractional* boundaries `observeMercatorLod` cares
about — `k + 0.5` (coarsest ratio), `k + 0.500001` (the historyless boundary crossing), `k + 0.2`,
and `k + 0.000001` and `k + 0.3` with `previousSelectedLod = k + 1` (the finest ratio the hysteresis
rule permits). **200,640 cameras per viewport**, 802,560 in total. Bearing turned out to matter a
lot: the phone viewport's pitch-0 peak of 76 is at bearing 44.9°, against 66 at bearing 0 — a
bearing-0 sweep would have understated it by 15%.

**Pitch: the real bound is `>= 0.0 && < 90.0`** (`SpatialValues.kt:57-59`), not 60°. The sweep runs
to 89.999999°.

**One arithmetic cross-check, because a sweep that agrees with nothing is worth little.** At 960×540,
pitch 0, LOD offset −1, the ground footprint is exactly the viewport in logical pixels and a tile is
`512 · 2^(zoom − lod) = 256.0` logical pixels, so the covering is `ceil(960/256)+1 = 5` by
`ceil(540/256)+1 = 4` = 20. The sweep measures 20. The 4K case predicts 16–17 by 10 = 160–170; the
sweep measures 167.

---

## Sweep results

Peak canonical tiles by pitch, per viewport (all latitudes/zooms/bearings; `-` means the frame is
legal but no camera at that pitch got higher):

```
                p0   p10   p20   p30   p40   p45   p50   p55   p60   p62.5  p65   p70   p75   p85   p89.999999
960x540         20    22    23    24    29    33    41    55    98    168    483   510   512   457   0
1179x2556       76    75    77    89   111   132   168   242   469    511    512   508   512   507   0
2560x1440       83    89    92   104   130   154   196   289   507    512    512   490   496   484   0
3840x2160      166   175   185   209   263   319   410   507   512    498    496   504   500   511   0
```

(That table fixes longitude at 13.4050 and sweeps three bearings, so it is slightly below the
full-grid peaks quoted at the top — 166 against 167 on the 4K row.)

Two shapes in it are worth naming. **The count rises smoothly with pitch until about 60° and then
steps hard**: past 67.5° the top of the screen stops hitting the ground at all
(`q = cos P − v sin P ≤ 0` in `physicalPixelGroundRay`, with `v` bounded by `1/(1+√2)`), and the
footprint runs to the horizon, saturating the 512-instance ceiling instead of growing further. And
**at 89.999999° the footprint is empty and the basemap draws nothing at all**: the band of screen
rows that both clear the horizon and survive the near-clip at `t ≥ 1` is narrower than one pixel, so
no pixel centre lands in it. That is a separate curiosity, out of scope here, and not a residency
problem.

Largest instance counts measured, all far above the 512 ceiling and therefore clean failures:

| viewport | max instances | camera |
|---|---:|---|
| 960×540 | 13,190,772 | lat 0, lon −122.4194, zoom 13.000001, bearing 0, pitch 67.6, prevLod 14 |
| 1179×2556 | 689,939,751 | lat 51.5074, lon 13.4050, zoom 16.000001, bearing 44.9, pitch 67.6, prevLod 17 |
| 2560×1440 | 138,822,267 | lat 12.9716, lon −122.4194, zoom 19.000001, bearing 0, pitch 72, prevLod 20 |
| 3840×2160 | **2,347,648,537** | lat 51.5074, lon 13.4050, zoom 16.000001, bearing 44.9, pitch 67.6, prevLod 17 |

Roughly a third of the swept cameras (57,766 to 78,884 of 200,640 per viewport) exceed 512
instances.

---

## The four questions

**1. Can an ordinary mercator camera exceed 128 tiles today?** Yes. On a 3840×2160 display it
happens at **pitch 0** — 167 tiles, 30% over — and on every other viewport it happens by pitch 62.5°
at the latest, with the 2560×1440 and phone viewports crossing at 40° and 45°. RenG itself permits
pitch right up to 90°, so 40–45° is not near any edge of what a caller may ask for. The band is
entered easily and, at the 512 ceiling, exceeded by **4×**.

The one genuinely reassuring number is the harness's own 960×540 viewport at 20 tiles unpitched:
`0.3.0`'s visual harness never came near the budget, which is why nothing has been noticed.

**2. Can it exceed 512 and hit the clean failure instead?** Yes, trivially, and by enormous margins —
up to 2,347,648,537 instances. Anything past 512 is `TileSelectionOutcome.OverBudget` →
`RESOURCE_LIMIT_EXCEEDED` at `FRAME_PLANNING` with `DiagnosticField.BASEMAP_TILE_INSTANCES`, `limit`
and `actual` (`MercatorSpatialPlanner.kt:228-241`). That is the *loud* outcome, and it is the one
regime a consumer can actually see. The silent one sits entirely below it.

**3. Does anything else share `maximumResidentGpuTextureBytes`?** **No. Basemap ground tiles are its
only tenant**, so the effective tile headroom really is the full 128 and the answer above does not
shrink. `GlObjectRegistry.registerTexture` is the only budget-tracked path, and it has exactly one
call site: `RenGRenderer.kt:1455`, inside `resolveGroundTiles`. Everything else goes through the
unbudgeted `register`: sticker images (`:1240`), geometry consumer textures (`:1262`), model
base-colour and override textures (`:1362`, `:1380`) — all via `cachedTexture` at `:1479-1485` — and
model primitive VAOs and buffers (`:1350`). A glyph atlas would be new code and would have to opt in
deliberately; RenG never calls `acquireLabelCandidates` today.

The inverse is the thing worth carrying forward: **the budget bounds one class of texture and is
blind to every other**, so it is not a device-memory ceiling and must not be read as one. A frame
with 128 tiles and forty model textures is at budget and well past it in real GPU bytes.

**4. Is there any diagnostic, warning, counter or public query for the thrashing band?** **No, and
the field that looks like one reports a literal zero.**

- `evictOverBudget` (`GlObjectRegistry.kt:276-288`) emits nothing — no exception, no diagnostic sink
  call, no counter. It deletes and returns.
- `DiagnosticCode` has exactly three constants — `RESOURCE_RELOADED_AFTER_FREE`, `FAILURE_CONTEXT`,
  `BASEMAP_NOT_CONFIGURED` (`Diagnostics.kt:34-38`). None is about residency or eviction.
- `queryResources` routes to `ResidentCache.report` (`RenGRenderer.kt:1058-1065`), which is the CPU
  cache and knows nothing about GL. Every entry it builds hardcodes
  `knownGpuBytes = 0L, hasUnknownGpuBytes = false` (`ResidentCache.kt:234-235`), and the totals sum
  those (`:243-244`). So `ResourceReport.totals.knownGpuBytes` is **always 0**, and
  `hasUnknownGpuBytes` is **always false** — the report does not say "unknown", it affirmatively says
  "zero GPU bytes". `RendererFactoryTest.kt:813-815` already states this in a comment: *"GPU objects
  appear in no ResourceReport at all today."* `ResourceUsage`'s init even guards the honest
  alternative (`knownGpuBytes != null || hasUnknownGpuBytes`), so the shape for reporting this
  exists and is simply not used.

A consumer at 167 tiles on a 4K display sees: a frame that draws correctly, a `ResourceReport` saying
0 GPU bytes, no diagnostic, and a frame budget quietly spending 39 PNG decodes plus 39
`glTexImage2D` uploads — 39 MiB each way — every frame at a stationary camera
(`RenGRenderer.kt:1396-1400` prices a miss at "a megabyte of decode plus a megabyte of upload"). The
decode is not avoidable from cache either: `resolveGroundTiles` calls `decodePng` on the tile's PNG
bytes directly on every GPU miss (`:1445-1451`), with no decoded-image cache in front of it.

---

## What could not be measured

**Nothing here was drawn.** The sweep counts what frame planning selects; it does not run a GL
context, does not upload a texture, and does not observe an eviction. The link from "N canonical
tiles" to "N MiB resident" is arithmetic — `image.width * image.height * RGBA_BYTES_PER_PIXEL` at
`RenGRenderer.kt:1459`, with 512×512 fixed by `BasemapEngineHost` — plus the reading of
`evictOverBudget` in §4, not an observed eviction. **What would settle it:** a readback-suite test
that registers 200 fake 1 MiB textures under the default budget, releases their leases, and asserts
how many survive.

**The row guard.** Footprints spanning more than 50,000 tile rows were skipped rather than counted
(1,353 of 200,640 cameras on two viewports). Every one is a high-pitch, high-LOD case already far
past 512 instances, so none could have changed a maximum — but their exact instance counts are
unmeasured.

**Non-512-pixel tiles are not considered.** `BasemapEngineHost` fixes `outputSizePx` at 512 and RenG
never overrides it, so "1 tile = 1 MiB" holds today. At 256 the budget would be 512 tiles and this
document's answer would flip.

**Whether 39 MiB of re-decode per frame actually costs a frame** is not measured here. F-2's own
ledger measured ~3 ms per GLB re-parse per frame on an M3 Max; the equivalent number for a PNG
decode plus upload of a 512×512 tile is not recorded anywhere in this repository. **What would settle
it:** the visual harness, at 3840×2160, with a camera parked at the 167-tile configuration above.
