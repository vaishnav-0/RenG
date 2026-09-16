# Label tiles are not the ground's tiles

One line decides what a frame asks the engine to plan labels over:

```kotlin
val labelCanonicalTiles = if (plan.drawLabels) {
    planned.spatialPlan.tileSelection?.canonicalResources.orEmpty()
} else { emptyList() }
```

Labels get the ground's exact per-frame selection. `labelHandover` retains one answer under
`BasemapEngineHost.labelCandidateRequestKey`, which is **rentile's** key over the sorted de-duplicated
tile set — so the retention answers only while that set is identical, and a single tile entering or
leaving costs the entire acquisition again: every label tile, every Glyph Range, and a re-packed
byte-identical atlas. `labelHandover`'s own KDoc measures the miss at **514–520 ms of a 605–610 ms
`prepare()`, about 85 % of it**.

A camera that moves changes the ground's selection. So the retention never answers.

## This is not a hypothetical

The consumer this renderer is built for **draws no basemap place names at all**, and says why in its
own source:

> labels on, the camera sweep never converges (**885–2237 ms a frame**, new tiles every frame, 50
> vector tiles fetched); labels off, it settles to **70.2 then 22.9 ms** with no new tiles and 12
> fetches. The label path keeps its own cache keyed on the tile *set*, so a moving camera misses it
> every frame — the ground path's residency filter cannot help it.
>
> Turn this on when RenG caches the label path the way it now caches ground tiles.

## Why the cache is not the thing to change

`HANDOFF.md` already recorded the obvious fix and rejected it:

> **`labelCandidateRequestKey` caches one entry.** Deliberate — a moving camera evicts any bounded
> cache every frame — but an A→B→A oscillation misses every time.

That reasoning is correct, and it is why this ADR grows no cache. A cache of any size loses to a set
that changes every frame. **So change the set.**

Nothing requires label tiles to be the ground's. They address *vector* sources where the ground
addresses raster; they share coordinates only because one line reuses the other's list. The ground
needs exactly the tiles under the frame, because a missing one is a hole. Labels do not: a set that
merely *covers* the frame answers the same question, and a set that covers the next few frames too
answers it for all of them.

## Two rules

- **A set that still covers what this frame needs is returned verbatim.** Identity, not equality —
  the test asserts `assertSame`, because a merely equal list is no better than a new one once
  rentile has hashed it.
- **A set that no longer covers it is rebuilt one tile wider**, so the next rebuild is many frames
  away rather than the next one.

## What that is worth, and what it costs

A 120-frame pan at a tenth of a tile a frame, which is a constant screen speed rather than a constant
angle — the second is not a camera anyone drives, and at zoom 18 it crosses one and a half tiles per
frame and makes the mechanism look useless:

| | acquisitions, today | acquisitions, observed |
|---|---|---|
| pitch 0 | 24 of 120 | **6** |
| pitch 45 | 96 of 120 | **13** |

The saving is the *fixed* cost of an acquisition — the Glyph Closure, the glyph round trip, the atlas
pack — and it falls by 4x and 7.4x.

**The set is wider, and the honest accounting says so.** At pitch 0 it goes from 16 tiles to 36, at
pitch 45 from 27 to 77. `CLAUDE.md` gives the shape as a perimeter, `4*sqrt(T) + 4` rather than a
ninefold, which is exactly right — 16 + 4*sqrt(16) + 4 is 36 — but the +33 % it quotes is at 167
tiles, and at these counts the same formula is **+125 % and +185 %**. Multiplying through:

| | tiles fetched, today | tiles fetched, observed |
|---|---|---|
| pitch 0 | 24 x 16 = 384 | 6 x 36 = **216** |
| pitch 45 | 96 x 27 = 2592 | 13 x 77 = **1001** |

So 1.8x and 2.6x less tile traffic, on top of 4x and 7.4x fewer of everything that does not scale with
the tile count.

**One tile of margin is measured, not assumed.** A wider margin buys fewer rebuilds and pays for each
quadratically, and the total turns around immediately: two tiles is 9 acquisitions of 151 tiles
(1359), three is 7 of 240 (1680). One is both the minimum and the best, so there is nothing to tune.

## What it does not do

**It does not turn labels on.** That is the consumer's `drawLabels`, and its own comment asks for the
sweep to be measured again before believing anything. This ADR removes the reason it was off; it
cannot claim the result.

**The level of detail is still the ground's.** A zoom that moves `selectedLod` rebuilds the set, which
is correct — tiles at a different level are different tiles, not the same ones shifted. ADR 0065's
bands also mean a pitch change that moves a band boundary rebuilds it. Both are genuine changes of
what the frame covers, and neither is what the 885–2237 ms figure was made of; that was pure
translation.

**A cold first frame pays more, not less.** The margin is fetched before it is needed, once. Every
frame after it is where this is paid back, which is the trade a margin always is.
