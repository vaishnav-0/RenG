# One firewall invocation may span a batch, and the measurement that re-priced this

`prepareBatch` is `plans.map { prepare(it) }`. `HANDOFF.md` records the consequence as parked work:
*"`prepareBatch` opens one registry per plan with no latch sharing, because `withOperation` lives
inside `prepare`. An N-frame batch over one style gets N registries."* This ADR gives a batch one.

## What the batch actually costs, measured rather than assumed

ADR 0049 made this observable for the first time. One camera, prepared three times in a single
`prepareBatch` against the test fixture, against the same camera prepared once:

| counter | one frame | a batch of three |
|---|---|---|
| `ENGINE_TILES_RENDERED` | 4 | **12** |
| `ENGINE_RESOURCE_REQUESTS` | 6 | **14** |
| `ENGINE_STORE_MISSES` | 6 | **14** |
| `ENGINE_RESOURCE_WIRE_BYTES` | 397 | **1029** |
| `ENGINE_TILE_DRAW_NANOS` | 4 833 709 | **15 046 291** |

Two things fall out of that table, and the second one is not what this ADR was planned to be.

**The expensive cross-frame work really is already shared.** Three frames cost 14 requests, not 18:
the style compilation and its manifest derivation are bound to the style's content digest on the
host, whose lifetime already spans frames, so the six-request first frame becomes four per frame
after it. Sharing a registry cannot save what is already saved.

**What is not shared is per-tile, and it is most of the cost.** Every frame re-rasterises every tile
it can see — twelve renders for four distinct tiles — and re-asks for the resources behind them. Of
the 15 ms of engine draw time in that batch, about 10 ms is work the first frame had already done.

## So this ADR is the smaller half, deliberately, and says so

A rendered-tile memo across the batch is the larger half and is the next ADR's subject. It also
subsumes part of this one: a frame that never rasterises a tile never asks for that tile's
resources, so the eight duplicate requests above mostly disappear with the renders.

This ADR is still worth taking on its own, for what the memo cannot reach. A memo matches whole
tiles; a registry matches *resources*. Two frames over a moving camera share few whole tiles and
many underlying resources — a `raster-dem` source expands each tile through its neighbourhood, so
adjacent frames' DEM sets overlap heavily even where no colour tile repeats, and glyph ranges for a
label are shared across every frame showing that label. Those are joins only a shared registry makes.

It is also the change ADR 0050 was written for, and the ordering matters: the latch semantics had to
be re-ruled before a registry outlived a single cancellable caller.

## The invariant moves, it does not weaken

`withOperation` opens with `check(activeOperation == null) { "a basemap engine host drives one
preparation invocation at a time" }`. That check earns its place: two *concurrent* preparations
sharing one host would interleave route preregistration and answer paths, which is the failure ADR
0016's whole invocation model exists to prevent.

Concurrency is not nesting, and the rule becomes **one root invocation at a time**. A
`withSharedOperation` opens the root; a `withOperation` finding one already open joins it instead of
opening its own, and only the root clears `activeOperation`. Nothing else can produce a nested call:
`preparationMutex` already serialises `prepare`, so an `activeOperation` observed from inside a
preparation can only be that preparation's own batch root.

Two conditions are checked on the join rather than assumed. The joiner's access mode must equal the
root's — one registry is never shared across modes, which is the rule `withOperation` already states
for its routes — and the root must actually be a shared one, so a plain `withOperation` nested inside
another plain `withOperation` still fails exactly as loudly as it does today.

`givesEachPreparationInvocationItsOwnOperationRegistry` keeps passing unchanged, and that is the
point: it opens two invocations in sequence, not nested, and sequential roots still get a registry
each.

## What grows, and why it does not matter at this size

The shared route index accumulates every frame's routes, and `preregister` copies its four maps on
each call, so a batch pays O(N · R) map-entry copies where one registry per frame paid O(R) each.
`maximumPreparationBatchSize` defaults to 256, which sounds alarming until it is compared against
what the same batch is doing anyway: at the measured four tiles a frame, the copies are thousands of
map entries against tens of milliseconds of rasterisation. `BasemapRouteDerivationCostTest`'s
shape guard is the one to watch if that ever stops being true; it measures the derivation, and the
ratio it asserts is unaffected by where the routes are stored.

The one-exchange contract tightens rather than loosens: a resource two frames in a batch both want
is now fetched once for the batch instead of once per frame. That is an observable change for a
consumer counting `Store` reads, and it is the saving, not a side effect.
