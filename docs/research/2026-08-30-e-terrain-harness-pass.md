# Cycle E-terrain — the harness pass, and the finding that stopped wave 1

Task 13's record. The visual harness was run against the corpus's own terrain styles for the first time,
and it found in one frame what eleven green tasks could not: **RenG's terrain does not work on five of the
six styles that ask for it.**

The two styles are the owner's and their urls carry an api key, so they are named by id and no url, tile
template or credential appears here.

## What the harness showed

Style **57** — a pure-vector terrain style — at zoom 11 over San Francisco, one frame, static camera:

```
frame 0000: mean rgb (153, 205, 222), undrawn 0.0%
transport: 55 requests
store: 33 entries, 55 reads (0 hits), 33 writes
diagnostics: none
```

Every DEM tile the frame asked for was fetched successfully:

```
fetch BASEMAP_TILE_JSON -> 200   .../terrain-rgb-v2/tiles.json
fetch BASEMAP_DEM_TILE  -> 200   .../terrain-rgb-v2/11/328/790.webp
fetch BASEMAP_DEM_TILE  -> 200   .../terrain-rgb-v2/11/326/790.webp
...
```

**`.webp`.** RenG owns a PNG decoder and nothing else, so every one of those tiles was decoded, rejected,
and dropped — and `diagnostics: none` says RenG did not mention it.

## The corpus, measured

Every terrain style's DEM source was followed to its TileJSON and the served format read off the tile
template:

| style | DEM format | encoding |
|---|---|---|
| 56 | **webp** | mapbox |
| 57 | **webp** | mapbox |
| 65 | **webp** | mapbox |
| 76 | **webp** | mapbox |
| 80 | png | terrarium |
| 84 | **webp** | mapbox |

**Five of six serve WebP. Terrain as built reaches one style.**

**The preflight predicted this and mis-sized it.** `docs/research/2026-08-29-e-terrain-preflight.md` §2
records that Rentile's validation is "**Not "a PNG"** — `Image.makeFromEncoded` is Skia's multi-format
decoder … A WebP or JPEG DEM passes Rentile's validation and would fail RenG's `decodePng`", and noted the
mitigation that RenG's transport sends `Accept: image/png`. That mitigation is advisory and MapTiler
ignores it, because the format is in the tile template rather than negotiated. The finding was filed as a
hazard; the corpus says it is the ordinary case.

## The second defect, which is RenG's own

**A DEM that acquires and then fails to decode is dropped silently.** `RenGRenderer.kt` reads

```kotlin
if (decoded is DemTexelDecodeResult.Success) texelsBySource[dem.sourceTile] = decoded.texels
```

with no `else`. The tile ends with no elevation, `padDemTexture` returns `null`, the ground draws flat —
and nothing is reported, because `reportTerrainDegradation` is handed the **acquisition** outcome, which
succeeded. ADR 0041 says absence draws flat *and diagnoses*; this path does the first and not the second.

That is why a frame that threw away every DEM it fetched printed `diagnostics: none`.

## The decision

**Rentile will expose decoded DEM pixels** (owner decision, 2026-08-30). Rentile already decodes these
exact bytes with Skia — `Image.makeFromEncoded` in `RasterResourceAcquirer`, for validation, discarding the
result — and already knows how to read them to RGBA8, which is what `drawHillshade` does. Keeping what it
has already computed removes the format question from RenG entirely.

Rejected: **adding a WebP decoder to RenG.** WebP means VP8L lossless and VP8 lossy; RenG's PNG decoder
alone took five adversarial review passes and a 300,000-input fuzz test, and this is the larger format on
the same untrusted-bytes boundary.

Rejected: **shipping PNG-only.** It reaches one corpus style.

**A consequence worth naming: it also deletes work RenG currently repeats.** Task 8 recorded that a frame
decodes every DEM in its request every frame, because the decode happens before the content key that would
answer residency exists. If the pixels arrive decoded, RenG's `decodeDemTexels` disappears and so does that
debt.

## What is verified and what is not

- **Verified**: the whole terrain path — acquisition, ring, window, padding, upload, displacement, depth,
  shading, and both diagnostics — against synthetic DEMs and against style 80's PNG source shape, on
  Apple M3 Max, `Apple Software Renderer` and the iOS simulator. Task 12's gate measures 4-of-4 against a
  displacement no-op and 1-of-4 against a dropped ring, the seam at **0 cracked pixels of 589,824**.
- **Not verified**: terrain against five of the six corpus styles, because their DEMs cannot be decoded.
  No claim is made about how they look, and none should be until Rentile's change lands.
