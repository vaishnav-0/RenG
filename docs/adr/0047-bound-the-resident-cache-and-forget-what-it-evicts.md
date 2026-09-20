# Bound the resident cache, and forget what it evicts rather than freeing it

`ResidentCache` had no eviction of any kind. Its own KDoc said so: "a retired generation with no
outstanding lease is dropped immediately — **there is no automatic eviction of a leased one, and no
automatic eviction at all otherwise**." Nothing shrank it except a consumer calling `freeResources()`,
which most never do. This ADR gives it a byte budget and a least-recently-used order, and decides the
one question that budget raises: **what an evicted key becomes.**

## Why this is only possible now

ADR 0045 is the precondition, not a neighbour. While `ResourceActionExecutor` dropped the lease of
every install on the floor, **every generation in this cache was permanently leased**, and a lease is
exactly what makes a generation ineligible for eviction. A budget added before that would have been
inert: it would have walked the cache, found nothing it was allowed to reclaim, and returned.

Now that a Prepared Frame releases its leases when it closes, a generation whose frame is gone is
genuinely unleased, and there is something for a budget to act on.

## What is actually in here, stated because it is easy to get wrong in both directions

The cache holds the resources named by `acquireFrameResources`'s `references` — the compiled style
document, **every sticker and geometry-consumer image**, and **every model GLB**. It does *not* hold
rendered basemap tiles: `CLAUDE.md` already records that "the report iterates `ResidentCache` entries
and a rendered basemap tile is never installed there." Tiles are bounded elsewhere, on the GPU by
`GlObjectRegistry.residentTextureByteBudget` and, since ADR 0044, in flight by
`maximumInFlightRawBasemapTileBytes`.

So this is **not** the basemap memory fix, and selling it as one would be wrong. What it bounds is the
consumer-content half, and that half is not small for the consumer that matters. An editing consumer
re-bakes a signed-distance atlas as its user works; every bake is different bytes, therefore a
different content-addressed locator (ADR 0007), therefore a different `ResourceKey` and a fresh
generation. A long editing session installs one per bake and, until now, released none of them. That
is the shape a consumer's own investigation recorded as `resident=6 retired=6 leases=6` on a single
locator whose content had changed six times.

Production installs still begin with `decoded = null`, but decoded images (ADR 0059) and immutable decoded
model expansions are now attached to their exact generation afterward. Their retained byte counts join the raw
bytes in this same budget and appear as `decodedCpuBytes`; attaching either is therefore another point at which
the cache may evict.

## An evicted key is forgotten, never marked freed

This is the decision, and both alternatives are wrong in a way that shows up as a diagnostic.

`KeyEntry.freed` is set by exactly one thing today — `free()`, the consumer asking for a key to go —
and it is read by `wasFreed(key)`, which is what raises `DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE`.
That diagnostic means "you freed this and then asked for it again", which is a statement about the
**consumer's** behaviour.

An eviction is not that. It is the cache's own decision, taken for its own reasons, about a resource
the consumer never stopped wanting. Marking an evicted key `freed` would make the next perfectly
ordinary use of it raise a warning blaming the consumer for a choice this renderer made silently —
the worst kind of diagnostic, because it is both alarming and false.

So an evicted key is **removed from `entries` entirely**. It then behaves exactly like a key never
seen: `wasFreed` is false, the next install creates a fresh entry, and nothing is reported. A reload
after eviction is not an event worth telling anyone about, because it is the budget working.

## What is evictable

Least-recently-used first, and only a key whose `current` generation holds no lease and whose
`retired` list is empty. Both halves matter:

- A leased generation is never a candidate, mirroring the rule `GlObjectRegistry` already states for
  textures — "exceeding the budget because a live Prepared Frame still needs a resource is the correct
  outcome". A budget bounds what *may* stay, never what *must* go.
- A key with retired generations is skipped rather than partially collapsed. A retired generation is
  only still present because something holds a lease on it, so the key is in use by definition.

Recency is touched where a generation is observed — `current` and `observeAndTakeLease` — and where
one is installed. Eviction runs after an install or decoded attachment, the points where the total can grow,
and immediately after a last lease release, the point where an over-budget pinned generation first becomes
evictable. It does not wait for an unrelated future install.

`maximumResidentCpuResourceBytes` defaults to 128 MiB, which is deliberately smaller than
`maximumDecodedImageBytes` (256 MiB, a per-image ceiling rather than a total) and smaller than the
512 MiB GPU texture budget: this bounds encoded bytes, which are the cheaper representation, and a
consumer holding more than that in undrawn resources is holding something it is not using.

## What this does not attempt

The sweep runs inside the cache's own `locked { }` block, which means it is a handful of map
operations and never an adapter call, a decode or a parse — the same discipline every other method
here follows. It frees CPU bytes only; a GL texture uploaded from an evicted resource stays resident
under its own budget until its own eviction, because the two are tracked separately and neither knows
the other's rules.
