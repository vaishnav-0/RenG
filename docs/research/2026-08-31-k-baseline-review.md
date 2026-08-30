# Cycle K — the baseline review

**2026-08-31.** The corpus rendered on this machine (Apple M3 Max, CGL core profile, `4.1 Metal - 90.5`)
and every frame looked at. This is the step every later comparison inherits its authority from:
`compare_rendered_frames.py` can only ever say *this changed*, and what makes a change meaningful is a
human having established, once, that the starting point was right. Until this document existed, Cycle K
proved change and not correctness.

77 runs — 4 configs × 19 single-frame plans, plus the 48-frame storyboard on one config — 124 frames,
0 failed. Configs are style **59** (raster/satellite), style **86** (vector), style **86** with
`terrainShading` on, and style **57** (vector, and the only one of the four whose style actually serves a
DEM). No style url or key appears here or in any corpus file; a config names a style by id.

## What is correct

Checked by eye on contact sheets and, where the eye is a bad instrument, by counting pixels.

- **The basemap draws** on all four configs, in both projections, at every camera in the corpus.
- **`drawBasemap = false` leaves exactly the caller's own content** — the orange grid geometry and the
  cyan pin on the harness's sentinel, and nothing else.
- **`drawLabels` genuinely suppresses labels.** The contact sheet could not show it, so it was measured:
  turning labels off changes **9.37%** of the frame on the vector style, **1.76%** on style 57 and
  **0.46%** on the satellite style, which has few labels to begin with. The ordering is what it should be.
- **A screen-anchored sticker stays nailed to the corner** under a pitched, rotated camera, and the `F` is
  the right way up — it is asymmetric in both axes precisely so a flip would be unmistakable.
- **Map-anchored stickers draw** on every style without a DEM, and the ground-relative and absolute pair
  separate visibly in one frame.
- **A translucent geometry leaves the basemap readable** underneath, which is what makes it useful.
- **The globe's limb is curved against the void**, 64.2% undrawn at zoom 2 — close to Cycle G's measured
  63.8%, and the sphere is unmistakably a sphere.
- **The poles are closed.** On satellite imagery the closure is plainly the documented mechanism: a fan of
  radial streaks above the top tile row, which is what "stretching the edge texel row" looks like when the
  texels are photographs. Greenland and Ellesmere sit where they belong. Not a defect — the accepted
  implementation, seen clearly for the first time.
- **Terrain relief is visible** on style 57 from the valley floor, with hillshading, ridges and drainages
  where Yosemite has them.

## Three defects, all in the same place

Every one is specific to a style that **actually serves a DEM**. The correlation is exact: of the four
configs, only style 57 fetches DEM tiles (41 on the failing frame), and only style 57 shows any of this.
`style-86-terrain` sets `terrainShading = true` on a style with no terrain source, fetches 0 DEM tiles, and
behaves identically to plain style 86 — so the trigger is the DEM, not the flag.

### 1. Above a zoom/pitch threshold the entire basemap disappears, silently

At zoom 14 over Yosemite, style 57 draws normally at pitch 10 and renders a **100% empty frame** at pitch
15. Holding pitch at 55 instead, it draws at zoom 13.2 and is 94.5% empty by zoom 13.3.

| | pitch 0 | pitch 10 | pitch 15 | pitch 55 |
|---|---|---|---|---|
| zoom 13 | draws | draws | draws | draws |
| zoom 14 | draws | draws | **100% undrawn** | **100% undrawn** |

The frame does not fail. `prepare` and `draw` both succeed, the harness reports `0 of them failed`, and the
only diagnostic emitted is an unrelated `INFO LABEL_CONTENT_EXCLUDED`. More tiles are fetched at the
failing pitch than at the working one — 89 requests against 54, including 41 DEM tiles, all HTTP 200 — so
this is not an acquisition failure. Everything arrives and nothing is drawn.

**This is the one to fix first**, and not only because a blank map is severe: ADR 0041 makes terrain the
one basemap resource that *degrades* rather than failing a frame, and what happens here is neither. It does
not degrade to flat ground and it does not fail; it silently takes the whole basemap with it.

Turning `terrainShading` off does not help — and notably does not stop the DEM being fetched either
(42 tiles with shading off), so that flag governs shading rather than acquisition.

### 2. A map-anchored sticker draws nothing at all

On style 57 the frame is **byte-identical with and without the sticker**. The same sticker in the same plan
changes 1,464 pixels on styles 59 and 86.

It is not occlusion by the terrain. The sticker was raised through 0, 5, 50, 500 and 2,000 metres, in both
`GROUND_RELATIVE` and `ABSOLUTE`, and drew zero pixels at every one — 2,000 m ABSOLUTE is roughly 800 m
clear of the valley floor and well above anything that could be in front of it.

The `GROUND_RELATIVE` case is the one the corpus was built to look at, and it is the more serious half: a
pin at altitude 0 in ground-relative mode is exactly the "sticker sitting on the terrain" a caller would
write, and it is invisible.

### 3. Geometries very nearly so

The same comparison on the draped geometry: **8 pixels** changed on style 57 against **5,844** on styles 59
and 86. Over the city camera the translucent grid contributes **2** orange pixels where the other styles
show thousands.

Eight and two rather than zero is itself a clue — a few pixels survive, which reads like a depth
interaction rather than a draw that never happens. ADR 0039 narrowed the ground's depth write specifically
to avoid reviving ADR 0027's coplanar-`Geometry` defect in the 28 corpus styles with no terrain. In the six
that do have terrain, something in that neighbourhood is unresolved and considerably worse than coplanar
fighting.

## What this changed in the corpus

Two authoring errors of mine, both caught by rendering:

**Pitch 70 and 75 were unrenderable.** Three plans failed outright with `RESOURCE_LIMIT_EXCEEDED at
FRAME_PLANNING` and a 100% undrawn frame. That is RenG working — an oblique camera at city zoom pulls the
horizon in, the visible ground runs away to it, and the tile count passes `maximumBasemapTileInstances`, so
the frame fails closed with a typed error instead of drawing something partial. Every corpus camera is now
at or below **pitch 55**, the storyboard's own maximum, which has rendered in every cycle since the
basemap. The envelope `Camera` *permits* is anything under 90; the envelope that is known to work is
narrower, and a corpus should live inside the second one.

**The valley cameras moved from zoom 13.5 to 13.0**, to stay clear of defect 1. A frame that cannot render
is worthless as a baseline: it compares equal to every other failure, so freezing one in would create a
test that passes for the wrong reason forever.

## What this review does not cover

The storyboard's 48 frames were rendered on one config and checked in the aggregate — undrawn percentage
and mean colour per frame — rather than looked at individually; previous cycles reviewed that sequence
frame by frame and it is unchanged here. No model appears anywhere in the corpus, because a GLB url points
at somebody's server and none is checked in, so the model pipeline is entirely unreviewed by this pass.
Only one machine and one driver: nothing here says what any other GPU does.
