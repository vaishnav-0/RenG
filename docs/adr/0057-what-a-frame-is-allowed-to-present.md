# What a frame is allowed to present

Every release of RenG has answered one question the same way: a prepared frame's ground is drawn from
the tiles the frame asked for, at the LOD the frame chose, and `prepare()` does not return until every
one of them has been rasterised. `resolveGroundTiles` states it as an invariant — *"prepare() already
proved every ground instance names a rendered tile"* — and `renderBasemapTiles` awaits
`renderTiles` synchronously to make it true.

That is a good answer and it has a cost this stack has now measured from both ends. ADR 0046's own
commit recorded a single frame costing **863–2105 ms** inside that call on a Snapdragon 8 Gen 3.
ADR 0046 and ADR 0052 removed the *repeats* — a tile already on the GPU, a tile already rendered
earlier in the batch — and neither touches a cold frame, because a cold frame has nothing resident
and nothing memoised. **All of a first frame's latency is still there, and it is all in this call.**

## The change, stated as a permission rather than a behaviour

**When the consumer sets a per-frame rasterisation budget, a frame may draw a tile it has not
rasterised from a resident ancestor of that tile, and say so.**

Three things about that sentence are load-bearing.

**"When the consumer sets"** — `maximumTilesRasterisedPerFrame` defaults to no limit, and with no
limit nothing below changes at all. Every existing consumer keeps the guarantee they have today,
byte for byte, and this ADR costs them nothing. The trade it offers — a frame sooner, at a lower
resolution, for a while — is a product decision about a particular application's feel, and RenG is
not in a position to make it for anyone.

**"a resident ancestor"** — never a fetch, never a render, never a guess. If tile `(z, x, y)` has no
resident ancestor the frame rasterises it as it always has, budget or no budget: a frame that cannot
draw its ground is not an improvement on a frame that is slow. So the budget bounds the work a frame
*adds*, and can never make a frame draw nothing.

**"and say so"** — a frame that presented an ancestor is not the same artefact as one that did not,
and a consumer compositing a still, or capturing a video frame, needs to know which they have. It is
reported, not inferred.

## Why the ancestor is enough, and why nothing runs in the background

The obvious design is to return the frame immediately and rasterise the exact tiles on another
coroutine, replacing them as they land. It is also the design that makes this a project rather than a
change: it needs a notification path, a second writer into `GlObjectRegistry`, interaction with the
cancellation semantics ADR 0050 has just re-ruled, and an answer to what `close()` means while a
render is outstanding.

None of that is necessary, because **the consumer already has a loop**. A frame that skipped a tile
leaves it un-resident, so the next `prepare()` selects it again and rasterises it under that frame's
own budget. Refinement is driven by the animation the consumer is already running, one budget's worth
per frame, and arrives without a single background task: a pan into new territory shows coarse ground
immediately and sharpens over the next few frames, which is exactly what the budget is for.

A consumer who is *not* looping — one still, prepared once — sets no budget and waits, correctly.

## The mechanism already ships, for the other texture

`GROUND_ELEVATION_SOURCE` samples an **ancestor DEM** through `uv * scale + offset` today, and
`DemTileWindow` is already the rectangle that describes it: a power-of-two `childScale` with integer
offsets, exact in `Double`, with the world-wrap `floorMod` trap solved and the non-ancestor case
returning `null` rather than guessing. All of it is colour-agnostic.

So the colour path borrows the shape rather than inventing one. `CanonicalBasemapTile` is
`(lod, tileY, canonicalX)`, so the ancestor `k` levels up is `(lod - k, tileY shr k, canonicalX shr k)`
and the window is `(fraction + offset) / 2^k` with `offset` the low `k` bits — arithmetic that is
exact in binary floating point for every `k`, which is why no tolerance appears anywhere in it.

Sampling is `GL_LINEAR` with `GL_CLAMP_TO_EDGE` and no mipmaps, so magnifying an ancestor is plain
bilinear with no LOD selection to get wrong.

## What a consumer shader sees, and what it does not

The window reaches the ground shaders as one `vec4` uniform and one `mix`, replacing
`rengGroundUv = rengGroundGrid;`. A tile drawn exactly gets the identity window, so the instruction
executes on every tile and the shader has no branch and no second variant.

Consumer geometry shaders see nothing: ADR 0008 hands them `aPosition` and a matrix, and the ground's
own uv is not part of that contract.

## The invariant that goes, and what replaces it

`resolveGroundTiles`' `require` — "prepare() already proved every ground instance names a rendered
tile" — stops being true, and it must not be softened into silence. It becomes: **every ground
instance names either a rendered tile or a resident ancestor of one**, and the case that satisfies
neither is still the typed `RESOURCE_UNAVAILABLE` failure ADR 0046 made it, for the same reason —
a texture evicted between preparation and draw is a frame this renderer cannot honour, and crashing
a consumer for a cache decision they never made remains wrong.
