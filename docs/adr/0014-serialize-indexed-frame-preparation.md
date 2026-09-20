# Serialize indexed frame preparation and keep batches internally parallel

This decision supersedes ADR 0002's promise that preparation may run concurrently and out of order. Every
**Frame Plan** carries a required non-negative `frameIndex`, and one renderer accepts only one active
preparation invocation. `prepare` has the semantics of a one-item batch. `prepareBatch` snapshots one
nonempty list under one **Resource Access Mode**; its indices must be strictly increasing and every index
must be above the renderer's last successfully prepared index. Planning, structural diffing, and basemap
LOD selection proceed in input order. Independent resource operations may run concurrently within the
configured bound, and successful **Prepared Frame**s are returned in input order only after the entire
batch succeeds.

The renderer commits the batch's last plan, frame index, and selected LOD as **Frame History** atomically.
Failure or cancellation commits none of it, although valid content already acquired remains cached. The
first item diffs against committed history and each later item against its immediately preceding input.
Every resulting **Prepared Frame** still leases its own complete dependency set and remains drawable in
any order, repeatedly or not at all. `clearFrameHistory()` clears only the ordering, diff, and LOD baseline;
it neither frees resources nor invalidates prepared frames.

This serialization makes history-dependent hysteresis and structural reuse deterministic. Letting
concurrent calls race would make successful coroutine scheduling part of frame content, while silently
ignoring a lower index would give callers a prepared frame whose LOD and reuse baseline could not be
explained by its sequence. A draw-history workaround was rejected because preparation must be complete
before drawing and prepared frames may be drawn out of order. Bounded parallel work inside an atomic batch
retains the useful acquisition pipeline without exposing a history race.

`cancelPreparations()` is an idempotent suspending snapshot barrier for the invocation active when it is
called. It returns after that invocation terminates, commits no history, and is a no-op when none is active;
it is not a latch. A second prepare, history clearing, resource freeing, or renderer close cannot interfere
with active preparation and fails with the operation's typed `PREPARATION_IN_PROGRESS` error. Cancelling
the calling coroutine remains the way to cancel one specific prepare, and every `CancellationException`
propagates unchanged.

The active invocation is one published outer worker, not only its resource-acquisition children. Its lifetime
starts before planning touches renderer state and spans ordered planning, the batch-wide firewall operation,
acquisition, Prepared Frame construction, history/style commit, and rollback. `cancelPreparations()` snapshots
that worker and `cancelAndJoin`s it, so returning from the barrier means none of those phases can still mutate the
renderer.

Prepared Frames remain provisional until every batch item succeeds. A later failure or cancellation closes them
in reverse construction order, atomically taking each frame's leases and raw-pixel reservation exactly once.
Only then does the invocation end; neither the provisional history nor a newly compiled prepared style becomes
visible. Valid cache content acquired before the terminal outcome may remain, as above, but no caller-owned frame
or ownership token leaks out of the failed transaction.
