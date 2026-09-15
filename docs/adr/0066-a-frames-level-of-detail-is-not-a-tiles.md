# A frame's level of detail is not a tile's

Five places asked a ground tile what level of detail the *frame* was drawn at:

```kotlin
groundInstances.firstOrNull()?.instance?.lod
```

That was exact while every tile in a frame shared one level, which is what it had always done. ADR
0065 ended it: the ground is banded now, bands are emitted coarse to fine, and `first()` is therefore
the **coarsest band at the top of the frame** rather than anything the frame as a whole is drawn at.

## How this was found, which is the part worth recording

Not by reading the code. Every unit test passed -- 1699 of them -- and so did the repository policy
gate and the ABI check. What failed was a GPU readback fixture on Apple's rasteriser:

```
[ground-relative and absolute stickers part in one frame] the GROUND_RELATIVE sticker must ride the
ridge and draw; it painted 0 pixels, under the 256 this fixture's 32-pixel square needs
```

Its camera is `PLACEMENT_CAMERA`, at a pitch of 45 degrees, which is exactly where ADR 0065's rule
first puts three bands on the frame. `PreparedTerrain.groundSurface(lod)` filters with
`if (tile.lod != lod) return@forEach`, so handing it the top band's level built a surface out of the
tiles furthest from the camera, the sticker's anchor found no ground beneath it, nothing lifted, and
the two stickers never parted. A frame-level number read from a per-tile field is invisible until the
two stop agreeing, and only a fixture that draws pixels noticed.

## A frame carries the level its own planner chose

`RenGPreparedFrame.groundSelectedLod` is `observeMercatorLod`'s answer, carried rather than
re-derived, and the terrain arms read it. The centre band always carries offset zero -- at the centre
row `theta` is the pitch itself, so ADR 0065's ratio is 1 and its logarithm is 0 -- so the frame's
selected level is genuinely present among the tiles rather than being a number no tile has.

## What needed nothing, and it is more than expected

**The DEM is already per tile.** `resolveDemTextures` returns
`Map<CanonicalBasemapTile, SceneTileDem>`, keyed on a type that carries `lod`, and
`demTileWindowFor(dem.requestedTile, dem.sourceTile)` computes each tile's window from that tile's own
coordinates. A tile at level 19 beside one at 21 each gets the right DEM window with no change at
all. That is why this ADR is a repoint rather than the terrain exclusion the plan for this work
expected to need.

**The grid granularity was never level-sensitive on this path.** `frameGroundCellsPerTileSide`
resolves `curvatureCells` to a literal `1` for a `ResolvedMercatorCamera`, so on flat Mercator the
number it returns does not depend on the level it is given at all. Only the globe's arm uses it, and
the globe never reaches the banded path: `planGlobeSpatial` calls `selectGlobeTiles`, which ADR 0065
did not touch. There is nothing to gate for the globe, so nothing is gated.

## The limitation this leaves, stated rather than discovered later

`GroundSurface` is single-level **by construction** -- it carries one `lod` field and its tile map is
filtered to it. Built at the frame's selected level it covers the centre band's tiles and no others.
So with terrain active:

- below about 23 degrees of pitch there is one band, the surface is the whole frame's, and nothing
  changes from today;
- above it, a `GROUND_RELATIVE` anchor that falls outside the centre band reads `0.0` metres rather
  than the height under it, exactly as `labelGroundElevation`'s own `?: 0.0` has always done where
  the frame drew no displaced ground.

That is a real narrowing of where ground-relative content rides terrain correctly, and it is the
price of ADR 0065 being a colour-tile change that terrain was not rebuilt for. The fix is to make
`GroundSurface` hold tiles at more than one level and choose per lookup; it is not attempted here,
because it is a terrain change with its own seams to argue about and this branch is a tile-selection
change. A consumer drawing terrain with ground-relative content above 23 degrees of pitch should know
that before adopting.
