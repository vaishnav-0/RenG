# One LOD per tile, chosen by equal screen area

ADR 0064 stopped a ground ray running away. It did nothing about what a steep frame costs, and the
cost is the actual defect. Measured on a 1080x1920 frame at zoom 20, counting the tile cells a
row-scan selection admits:

| pitch | tile instances, one LOD for the frame |
|---|---|
| 0 | 16 |
| 45 | 26 |
| 55 | 48 |
| 60 | 94 |
| 63 | 192 |
| 66.75 | **4114** |

`RendererConfiguration.maximumBasemapTileInstances` defaults to 512 and is capped at 4096, so the
last row is not slow -- it is past both, and `RESOURCE_LIMIT_EXCEEDED` is thrown out of `prepare()`
whatever the caller configured. The frame does not render at all.

The growth is not a constant factor to be absorbed. It is `sec^3(theta)` integrated across the
frame's angular span, and it diverges as the top of the frame approaches the horizon. No budget, no
cache and no faster rasteriser fixes a divergence; only choosing different tiles does.

## The rule, and where it comes from

MapLibre GL JS solves this in `covering_tiles.ts` by lowering each tile's zoom as a function of that
tile's own angle from the camera:

```
S = S_centre * cos^(b/2 + 1)(theta) / cos(theta_centre)
```

`b` is a tuning exponent, and their `developer-guides/covering-tiles.md` derives what it means: at
`b = 1` tiles are loaded with approximately equal *screen area*, and the total tile count becomes
independent of pitch, because the differential tile count `cos^(b-1)(theta) d(theta)` is flat.

Their shipped tuning is expressed as `maxZoomLevelsOnScreen = 9.314`, which looks arbitrary and is
not: solving their own formula for `b` at that value, with their 36.87 degree field of view and their
89.25 degree horizon angle, gives `b = 0.99965`. **9.314 is simply the number that makes `b = 1`.**
The indirection exists so a style author can express the rule in zoom levels; RenG has no style author
to serve here, so this ADR writes `b = 1` directly and skips it.

RenG's form, with the exponent substituted and expressed relative to the frame's own selected LOD:

```
lod(row) = selectedLod + floor(1.5 * log2(cos(theta) / cos(pitch)) + 0.5)
```

## Why this needs no quadtree, which is the whole reason it is small

MapLibre descends a quadtree from the root tile, testing each cell against the frustum, because it
must serve a globe and terrain where a tile's distance is genuinely two-dimensional. RenG's Mercator
ground is a plane and its horizon is level, and on a plane the angle falls out of the screen row
alone:

```
theta = pitch + atan(v)
```

exactly -- the tangent addition formula applied to `tan(theta) = (sin(pitch) + v * cos(pitch)) / q`,
which ADR 0064 already relies on. **The level-of-detail field is one-dimensional.** So the frame
decomposes into horizontal bands, each band is a row range, and each band's footprint is built by the
corner sampling `clippedPhysicalPixelFootprint` already does -- applied to a sub-range instead of the
whole admissible range. `selectBasemapTiles` is called once per band and is **not modified at all**.

The cosine needed is already in hand rather than computed: with `theta = pitch + atan(v)`,

```
cos(theta) = q / sqrt(1 + v * v)
```

so the rule reads `q` and `v`, both of which the ray function has, and evaluates one logarithm per
row.

## Anchored at the centre, which is a deliberate divergence from MapLibre

MapLibre's centre tile is itself coarsened by `0.5 * log2(cos(pitch))` -- at 60 degrees of pitch the
middle of their frame drops half a level. Written relative to `selectedLod` as above, RenG's centre
row gets offset exactly zero at every pitch, because `theta = pitch` there and the ratio is 1.

That is on purpose. `observeMercatorLod` chose `selectedLod` specifically to put
`screenPixelsPerTexel` near 1 at the camera, and spends its entire asymmetric hysteresis band
avoiding the coarser side because, in its own words, coarser "is the blurry direction". Re-coarsening
the centre here would undo that deliberately, and a viewer would read it as a pitched frame going
soft in the middle. The property this buys is worth stating plainly: **at any pitch, the centre of
the frame is exactly as sharp as it is today.**

The cost is that the tile count is pitch-independent only relative to the centre rather than
absolutely. The measurements below say what that is worth, and it is not much.

## A band must earn its own selection

A band costs a tile selection, and `selectBasemapTiles` admits every tile *intersecting* a footprint,
so two adjacent bands both admit the tiles straddling their shared edge. That overlap is real and it
showed up immediately: at 20 degrees of pitch the unrestricted rule splits off a 34-row band at the
top of the frame -- 1.8% of the height -- and the split **costs two tiles more than it saves**, 22
against today's 20.

So a band shorter than a thirty-second of the frame is merged into its neighbour. Because the offsets
decrease monotonically from the bottom of the frame to the top, the neighbour in row order is always
the finer one, and a merged band is therefore never blurrier than the rule asked for -- there is no
tie to break and no way for the merge to cost sharpness. The threshold was chosen by measurement, not
taste:

| pitch | today | no minimum | **1/32** | 1/16 | 1/8 |
|---|---|---|---|---|---|
| 20 | 18 | 20 | **18** | 18 | 18 |
| 45 | 26 | 24 | **24** | 24 | 20 |
| 55 | 48 | 34 | **34** | 34 | 38 |
| 66.75 | 4114 | 54 | **52** | 72 | 130 |
| 80 | 858 | 68 | **78** | 122 | 286 |

A thirty-second is the coarsest threshold that removes the shallow-pitch regression outright, and
past that every further merge costs more at the steep end than it saves at the shallow one -- an
eighth more than doubles the steep-pitch count, because merging a band upward pays for its whole row
range at the finer level. The threshold buys exactly one thing, the removal of a band too small to
pay for itself, and buying more of it is not better.

**These are the implementation's numbers, and they are not the ones this ADR was first drafted with.**
A simulation of the same rule put the best threshold at a sixteenth; running it put it at a
thirty-second, and inverted the ordering at the steep end. The simulated table was wrong because it
approximated a band's tile count by its bounding box rather than by what `selectBasemapTiles`
actually admits. Only the measured table is quoted here.

## What it is worth, measured

Same frame, same zoom, same counting method as the table at the top:

| pitch | one LOD (today) | per-tile LOD | bands |
|---|---|---|---|
| 0 | 16 | **16** | 1 |
| 20 | 18 | **18** | 1 |
| 38.82 | 22 | **18** | 2 |
| 50 | 32 | **30** | 3 |
| 60 | 94 | **40** | 5 |
| 66.75 | 4114 | **52** | 7 |
| 70 | 3124 | **54** | 7 |
| 80 | 858 | **78** | 6 |
| 89 | 20 | **164** | 1 |

Four things in that table matter more than the headline ratio:

- **Pitch 0 is identical, and so is every pitch through 20 degrees**, where the merge rule leaves
  exactly one band. Those frames select the same tiles as today, through the same call, and the test
  asserting it compares the two selections for equality rather than comparing counts.
- **Seven bands is the most the frame ever holds**, at any pitch this renderer accepts. The
  decomposition does not itself run away.
- **4114 to 52** is the change from a frame that throws to a frame that draws.
- **Pitch 89 costs more, not less, and that is the honest entry.** One LOD asks for 20 tiles there
  and the rule asks for 164. It is not waste: at 89 degrees the camera sits essentially on the
  ground, every remaining row is near ground, and the rule answers with LOD 22 where the frame LOD
  is 20 -- sixteen times the texel density over a patch that genuinely fills the frame. The count
  stays far inside the 512 default, and the alternative is a foreground rendered at a sixteenth of
  the detail it is being viewed at. The same effect gives a smaller rise from about 75 degrees
  upward, and it is the reason this ADR claims a bounded tile count rather than a smaller one.

There is one more regression worth naming because a reader will find it: at 23 degrees, the first
pitch that splits, the frame briefly costs 20 tiles against one LOD's 18. That is the band-boundary
overlap at its least useful, two tiles wide, in a narrow band of pitch just past the split. An eighth
of the frame as the minimum band avoids it and costs 130 tiles at 66.75 instead of 52, which is not a
trade worth making.

## What this does not do

It does not touch the globe or terrain, and ADR 0067 states why each is excluded rather than merely
deferred. It does not eliminate the band-boundary overlap above, only bound it -- bands are emitted
in row order, which is coarse to fine, so the finer tile is drawn last where they overlap. And it
leaves `clippedPhysicalPixelFootprint` sampling four corners of each band rather than the true ground
silhouette; shorter row ranges shrink that error substantially but do not remove it.
