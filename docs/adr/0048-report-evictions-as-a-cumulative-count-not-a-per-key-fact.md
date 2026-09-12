# Report evictions as a cumulative count, because the keys themselves are gone

ADR 0047 gave `ResidentCache` a byte budget, and with it the first way a resource can leave this
renderer without the consumer asking. That creates an obligation this report does not yet meet: a
consumer whose resource vanished has no way to learn that it was evicted, how often that is
happening, or how close to the budget they are running. `queryResources` answers "what is resident",
and eviction is precisely the thing that is *not* resident any more.

## Why this cannot be a per-key field

The obvious shape — an `evictionCount` on `ResourceReportEntry`, beside `residentGenerationCount`
and `leaseCount` — is impossible, and impossible for the reason ADR 0047 argued for deliberately.
An evicted key is **removed from `entries` outright**: it has no entry left to carry a count, and
giving it one would mean keeping a tombstone per evicted key, which is both a leak in the shape of a
diagnostic and the very "the cache remembers you were evicted" state that ADR 0047 rejected when it
refused to set `freed`.

So the counters are **cache-wide and cumulative since the renderer was created**, and they are
monotonic: `evictedKeyCount` and `evictedBytes` only ever rise. That is the honest reading of a
forgetful cache. What a consumer does with it is a rate — two reports a few seconds apart, and the
difference says whether the budget is biting. A single reading says only "this has happened at all",
which is still the answer to the question that matters most: *did something leave that I did not
free?*

## Resident and budget bytes travel with them

Three numbers are useless apart. `evictedBytes` without `budgetBytes` cannot be judged, and
`residentBytes` without either is the number the per-entry rows already sum to. Together they answer
the only question worth asking of a budget: how close am I, and has it already cost me anything.
`residentBytes` is also the one figure here that is *not* derivable from the entry rows, because the
entries are filtered by the caller's `ResourceSelector` and the budget is not — a `ByKey` report
sums one key and the budget governs all of them.

## Deliberately the CPU cache only

`ResourceResidency` describes `ResidentCache` and nothing else, and the field is named `cpuResidency`
rather than `residency` so that no one reads it as the whole picture. The GPU has its own budget,
its own eviction loop and its own `GpuTextureResidency` inside `GlObjectRegistry`, and it is already
reported at the only granularity that is meaningful for it: `ResourceUsage.knownGpuBytes` per entry,
with `hasUnknownGpuBytes` where nobody counted.

Merging the two into one residency figure would produce a number that is the sum of two budgets
nothing enforces jointly — the kind of total that reads as authoritative and governs nothing.
Surfacing the GPU pool's own budget publicly is a defensible thing to do later; it is a separate
decision about a separate pool, and folding it in here to make the type look symmetrical would be
deciding it by accident.

## What an empty report says

`emptyResourceReport()` — the answer when the renderer is closed or the driver short-circuits — now
reports zeroes for all four fields. That is not a claim that nothing was ever evicted; it is the
same "this renderer is not answering questions" that its empty entry list already means. A consumer
reading a monotonic counter that dropped to zero has learned the renderer closed, which is true.
