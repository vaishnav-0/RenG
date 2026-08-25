# Ask the engine only for basemap tiles that are not already resident

`renderBasemapTiles` sent every visible tile to the engine on every frame, rasterised each one, and
then threw the result away at draw for any tile whose GL texture was already on the GPU. **Panning
back over ground the renderer had already drawn cost a full re-rasterisation of it.**

Like ADR 0045, this finishes a sentence the codebase had already written twice.
`RenGPreparedFrame.basemapTiles` says the bytes are uploaded at draw "because a tile whose GL texture
is still resident from an earlier frame must cost neither"; `GlObjectRegistry` says an unleased
budget-tracked texture survives losing its lease "so a pan back over the same tile costs nothing".
Both were true of the decode and the upload. **Neither was true of the rasterisation**, which is the
expensive half.

## The measurement

On an Apple M2 release build, through a consumer harness, one camera prepared three times over a
one-source production style: **703, 714, 740 ms**, with eleven vector tiles re-fetched from the
consumer's transport on every prepare. On a Snapdragon 8 Gen 3 in an app, a frame cost **895–2256 ms,
of which 863–2105 ms was this one call**, against 17–151 ms to draw it and read it back — so a
four-and-a-half second timeline played about four frames.

## Three changes, and the second is what makes the first correct

**`renderBasemapTiles` filters to the tiles that are not resident** and returns early when none are
missing. It preregisters routes for the missing set only: the firewall admits exactly what was
preregistered, so widening that set for tiles the frame will not fetch buys nothing. The identity is
asked of `BasemapEngineHost.renderedTileKey` rather than derived at the call site, because
`tileOutputSize` is an engine render option and that function's own KDoc warns that a caller which
guessed it would name a tile the engine never rendered.

**`groundInstances` stops keying on the rendered list.** It returned empty whenever nothing was
rendered, and asserted that every instance names a tile *this frame rendered* — both of which stopped
being the right condition the moment a frame could legitimately render nothing and still have ground
to place. It now returns empty only when there is no basemap to place at all, and admits a tile that
is rendered **or** already resident. `MacosGlConformanceTest` caught the first version of this patch
exactly here: its coplanar-geometry case found a quad with no ground beneath it and failed itself as
vacuous rather than passing quietly.

**`resolveGroundTiles` stops using `requireNotNull` for an absent tile.** A tile can now be resident
at preparation and evicted before its draw — the byte budget can do that to an unleased entry — and
**refusing that frame is right where crashing the consumer for a cache decision it never made is
not.** It is reported as `RESOURCE_UNAVAILABLE` rather than `RESOURCE_DECODE_FAILED`, because nothing
failed to decode and a decode code sends the reader to look at the bytes. No new enum value, so the
published ABI is untouched.

That third change is a real behaviour change and is stated as one: a condition that used to be an
internal programmer-error assertion is now a typed failure a consumer can observe and handle.

## The filter narrows the rasterisation and never the route registration

**This is the constraint that decides whether the change is correct, and it was found by the readback
suites rather than by reasoning.**

`tileTimeRoutes` walks `tiles × manifest.sources`, and a `raster-dem` source expands each tile through
`demNeighbourhoodOrSelf` — so a frame's entire DEM neighbourhood is derived from the same tile list
the colour rasterisation uses. A resident colour texture says nothing about whether the DEM beneath it
is acquired: the two are tracked in different places, colour in `GlObjectRegistry` and terrain in
`PreparedTerrain`. **Narrowing the route registration to the missing tiles therefore starves terrain,
and on a camera whose colour tiles are all resident it registers no DEM routes at all.**

The first version of this change here did exactly that, and the cost was unmistakable: eight of nine
terrain frame readback cases and four of five ground anchor cases failed, every one of them reporting
the ground displacing by **exactly zero pixels** — "a build that displaces nothing measures zero
twice".

The upstream commit this derives from could not have caught it. It was written against a base that had
no terrain at all; ADR 0041 came afterwards. Its own reasoning — that registering routes for tiles a
frame will not fetch "would widen that surface for no reason" — was true then and is false now,
because those routes are exactly what a *different* consumer of the same list still asks for.

So routes are registered for every visible tile, and only the rasterisation is filtered. The
preregistration is cheap and the firewall still fetches only what is asked for; what it buys is that
the terrain path keeps finding the routes it has always depended on.

## How this composes with ADR 0044

ADR 0044 bounded raw tile pixels by refusing `renderRaw` once outstanding raw bytes would exceed
`maximumInFlightRawBasemapTileBytes`. That budget is charged against the batch handed to the engine —
and **after this ADR that batch is the missing tiles, not the visible ones.** The two compose in the
right direction: a frame that pans back over drawn ground now asks for nothing, so it charges nothing,
where before it would have charged the budget for tiles it was about to discard.

Anyone reading ADR 0044 alone should read it as governing the tiles a frame actually rasterises. It
no longer governs every tile a frame draws, and that is a strictly better place for the budget to sit.

## The assumption, stated because it is the first of its kind

This is **the first cross-phase read of `GlObjectRegistry`**: it runs during preparation, while the
registry is otherwise touched only under a draw. A consumer preparing one frame while drawing another
would be racing a `LinkedHashMap`.

Every consumer today prepares and draws in sequence on the thread that owns the GL context, which is
what "prepare() has no render context" already implies about how these phases interleave — and
`prepare()` refuses to run twice concurrently in any case. Making this safe for a genuinely concurrent
consumer is a synchronisation question this ADR does not answer, and it is the question any future
batched or pipelined preparation has to answer first.
