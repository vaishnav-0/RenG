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

## One defect, and two findings that were my own test error

**Corrected on the same day, after deeper investigation.** The first pass of this review reported three
defects. Two of them were not defects at all, and the correction matters more than the original report
did: I had placed corpus content at **`ABSOLUTE` altitude 0 in a valley whose floor is about 1,200 m**,
found it invisible, and called that a renderer bug. It was underground. `CONTEXT.md` defines altitude as
ellipsoidal metres and ADR 0040 keeps `ABSOLUTE` meaning exactly that, so a pin at 0 m in Yosemite has
1,200 m of rock above it and RenG was right to hide it.

Measured after the correction, on the same style and camera:

| content | ABSOLUTE at 0 m | GROUND_RELATIVE at 0 m |
|---|---|---|
| geometry, pitch 0 | invisible | **22,231 px** |
| geometry, pitch 30 | invisible | **10,641 px** |
| sticker over the city, pitch 0 | invisible | **896 px**, rising with altitude |

**`GROUND_RELATIVE` works exactly as designed**, and the "8 pixels against 5,844" figure in the first
pass was an absolute quad buried in a mountain, seen edge-on. The lesson is the one this project keeps
relearning in its own tests: *a fixture at a degenerate point proves nothing about the code under it.*
Sea level is a degenerate altitude in mountainous terrain, and I picked it without thinking.

### The one that is real: the ground vanishes at high zoom over high terrain

Only styles that actually serve a DEM are affected — the correlation is exact, and `terrainShading` is
not the trigger (a style with the flag on and no terrain source behaves identically to the flag off).

| | zoom 13 | zoom 14 | zoom 15 |
|---|---|---|---|
| city, terrain ~100 m | draws | draws | draws |
| ocean, terrain ~0 m | draws | draws | draws |
| **valley, terrain 1,200–2,700 m** | draws | **blank at pitch 55** | **blank at every pitch** |

The frame does not fail. `prepare` and `draw` both succeed, the harness reports `0 of them failed`, and
no warning is emitted; the blank frame fetches *more* tiles than the working one (89 requests against
54, including 41 DEM tiles, all HTTP 200). Everything arrives and nothing is drawn.

**The threshold tracks terrain height against camera height, which points at the camera being inside the
terrain.** RenG derives camera altitude from zoom alone: the world is `512 * 2^zoom` logical pixels for
40,075,017 m, so at zoom 15 the eye sits on the order of 1,500 m above the *ellipsoid* — beneath
Yosemite's walls, and not far above its floor. The city at ~100 m and the ocean at ~0 m never approach
that, and neither ever fails.

**That is a consequence of a recorded design decision rather than an oversight**, which is why this
document stops at describing it. E-terrain rejected an `elevationAt(lat, lon)` query as circular —
elevation depends on resident tiles, residency on the camera, the camera on the plan being built — so
RenG has no terrain-aware camera by choice. What is *not* covered by that decision is the silence: ADR
0041 makes terrain the one basemap resource that degrades rather than failing a frame, and a blank frame
with no diagnostic is neither degrading nor failing. **Whether to raise the camera, clamp the
displacement, or announce the condition is an owner decision and an ADR, not a fix to slip into a
release.**

**It is not a regression, and this release does not introduce it.** Terrain shipped in `0.4.0`, which is
public; the behaviour above reproduces against that released coordinate. Cycle K adds serialization and
the corpus tooling and touches none of it.

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
