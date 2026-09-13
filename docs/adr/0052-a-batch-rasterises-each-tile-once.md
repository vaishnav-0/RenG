# A batch rasterises each tile once, and every frame in it carries the result

ADR 0046 stopped a frame asking the engine for a tile whose texture is already resident on the GPU.
That filter cannot help inside a `prepareBatch`, because nothing in a batch is drawn: no texture
becomes resident until the consumer draws, and the consumer draws after preparing. So every frame in
a batch re-rasterises every tile it can see, including the ones the frame before it just rendered.

Measured against the test fixture, three frames over one camera:

| | before | after |
|---|---|---|
| `ENGINE_TILES_RENDERED` | **12** | **4** |
| `ENGINE_TILE_DRAW_NANOS` | 15 046 291 | about a third of that |

Four distinct tiles, rendered twelve times. About 10 ms of that batch's 15 ms of engine draw is work
the first frame had already finished.

The fixture's tiles are 64 px. In production they are 512 px against a real style, where ADR 0046's
own commit recorded a single frame costing 863–2105 ms in this call on a Snapdragon 8 Gen 3. A batch
multiplies that by its frame count today, and this ADR divides it by the overlap.

## The memo is the same filter with a different lifetime

ADR 0046 asks "is this tile's texture resident?". This asks "has this batch already rendered it?".
Both answer the same question — *does the engine need to do this again* — against the two places an
answer can live before and after a draw. Keyed identically, on the `renderedTileKey` the host
derives, because a caller that guessed that identity would name a tile the engine never rendered.

It is deliberately **batch-scoped and nothing wider**. A renderer-lifetime memo of rendered tiles
would be an unbounded store of raw pixels sitting beside the two budgets that already govern them —
`GlObjectRegistry`'s texture budget after the upload, and `maximumInFlightRawBasemapTileBytes`
before it. Outside a batch there is a draw between one frame and the next, which is exactly what
makes ADR 0046's residency filter sufficient there.

## Every frame carries the tile, rather than pointing at the frame that rendered it

The tempting shape is for frames 2 and 3 to omit a tile frame 1 already has, and find it at draw
through `GlObjectRegistry` once frame 1 has uploaded it. That is wrong, and wrong in a way that
depends on the consumer.

A batch hands back N open frames and says nothing about the order they are drawn in, or whether they
are all drawn at all. A consumer that draws frame 2 first, or closes frame 1 without drawing it,
would find frames 2 and 3 naming a tile no texture exists for — `resolveGroundTiles` would answer
`RESOURCE_UNAVAILABLE` for a tile the batch definitely rendered. So each frame gets the same
`RenderedBasemapTile`, and each is self-sufficient: whichever frame is drawn first uploads it and
registers it, and the others then find it resident exactly as ADR 0046 intends.

Sharing the object is safe because it is immutable — a key, a canonical tile, its pixels, its content
key and its substitutions, none of them mutated after construction. `RenGPreparedFrame` snapshots the
*list* it is given, not the arrays inside it, so the shared pixels really are shared and the batch
holds one copy rather than N.

## The raw-byte charge stays per occurrence, which is the same number as today

`rawTileBytes` sums a frame's own tiles, so a tile shared by three frames is charged three times
against `maximumInFlightRawBasemapTileBytes` while one array is held. That over-counts the bytes and
it is left that way on purpose.

It is not a regression: three frames each holding their own copy of four tiles charged exactly the
same twelve tile-loads before this change. What changes is only that the charge is now conservative
rather than exact. Making it exact means counting distinct tile identities across open frames — the
same lease-counting shape `ResidentCache` and `GlObjectRegistry` already use — and it is worth doing,
but it is a second decision about a different budget and does not belong inside this one.

In practice the budget binds *less* often after this change, not more, because `rawTilesFit` is
consulted only for tiles actually being rasterised, and there are now far fewer of those.
