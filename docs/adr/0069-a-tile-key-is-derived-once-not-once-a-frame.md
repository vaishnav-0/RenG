# A tile key is derived once, not once a frame

`renderBasemapTiles` opens with this, and it runs on every frame that draws ground:

```kotlin
for (tile in canonicalTiles) {
    val key = basemapEngineHost.renderedTileKey(style, tile)
    if (glObjectRegistry.resident(key) != null) continue
    ...
}
```

The derivation is unconditional and it is not cheap. `renderedTileKey` reaches `basemapTileKey`, which
constructs a **fresh `ResourceKeyDeriver`**, builds a canonical binary, runs **SHA-256** over it and
formats a 64-character lowercase hex string — per tile, per frame. `groundInstances` then derives the
same keys a second time for the same tiles.

So a frame pays **two SHA-256 derivations per visible canonical tile**, before anything has been
decided. `CLAUDE.md` records the tile counts this happens at: 93 canonical tiles with no LOD history
and 167 with one frame of it, at 3840x2160 and pitch 0. A camera sitting perfectly still, with every
tile already resident and nothing to fetch, render or upload, still pays all of it on every frame
forever.

## It is a pure function of three things, two of which cannot change

```kotlin
internal fun basemapTileKey(
    styleDigest: String,
    tile: CanonicalBasemapTile,
    outputSize: OutputPixelSize,
    sha256: Sha256Function = AcceleratedSha256,
): ResourceKey
```

`outputSize` is the host's `tileOutputSize`, an engine render option fixed for the host's lifetime —
`renderedTileKey`'s own KDoc says it is asked of the host precisely so that value "stays owned in
exactly one place". `sha256` is the host's, also fixed. That leaves `(styleDigest, tile)`, and a map
on those two answers the whole question.

## The memo belongs to the host, and a caller's own would not do

`groundInstances` already carries one:

```kotlin
val keyByCanonicalTile = HashMap<CanonicalBasemapTile, ResourceKey>(renderedTiles.size)
```

and it is right about what it is for — several unwrapped world-copy instances share one canonical
tile, so it collapses those. But it is built and discarded inside one call, so it cannot see the
derivations `renderBasemapTiles` made moments earlier in the same frame, and it certainly cannot see
the previous frame's. Every caller writing its own gets the same partial answer.

`renderedTileKey` is the single funnel — three overloads, all landing on `basemapTileKey` — so one
memo behind it covers every caller, including the ancestor walk of ADR 0057, which asks for up to
`tile.lod` keys per walk. `groundInstances`' local map becomes redundant and goes.

## Bounded at 4096, and that number is not arbitrary

`RendererConfiguration.maximumBasemapTileInstances` is capped at 4096, and canonical tiles are never
more numerous than the instances that name them. So a memo of 4096 entries is exactly large enough
that **one frame can never evict a key it is still using** — the property that makes the eviction
policy uninteresting, in the same sense ADR 0060 means it: forgetting costs one re-derivation, which
is what every frame did for every tile before this ADR, so the worst case of the cache is the whole
behaviour of its absence.

Oldest-first, following `ClassGateRunner.remember`'s shape rather than inventing a second one.

## What this is not, stated because the first draft of it was wrong

**The ancestor walks are not the cost.** An earlier reading of this counted `residentAncestorOf`
running three times per tile and made that the headline. It does not. Its call in `groundInstances`
sits behind `||` after two cheaper tests and is reached only for a tile that is neither rendered this
frame nor resident; its call in `renderBasemapTiles` runs only on tiles past
`maximumTilesRasterisedPerFrame`; and the one in `resolveGroundTiles` is guarded the same way. All
three are provisional-tile paths, and on an ordinary frame none of them runs at all. The cost is the
unconditional two-per-tile derivation above, which is why the memo is the fix and removing a walk
would not have been.

**No key changes.** This derives exactly what it derived before, from the same inputs through the same
function, and returns it from a map on the second ask. Nothing about resource identity, cache
addressing or the ABI moves; a frame's pixels are unchanged by construction.
