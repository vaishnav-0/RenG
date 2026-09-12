# RenG's own metric vocabulary, fed by the engine's sink

`BasemapEngineHost` has passed `MetricsSink.None` since ADR 0016. Rentile emits seventeen counters
across every preparation — store hits and misses, single-flight joins, wire bytes, tiles rendered,
PNG encode nanoseconds — and RenG throws all of them away. That was the right default while nothing
could read them; it is the wrong one now, because this PR stack is a performance stack and none of
its claims can be checked from outside the renderer. `PNG_ENCODED_BYTES` falling to zero is the
direct evidence that ADR 0044's raw path is taken; `RAW_CACHE_MISS` falling is the direct evidence
that ADR 0046's residency filter is working. Both are already being emitted into a black hole.

## Why the vocabulary is RenG's and not the engine's

`tools/check_repository_policy.py` forbids any `com.rohittp.rentile` type in `kmp/api/kmp.klib.api`,
so re-exporting `MetricName` or `RentileMetric` is not available, and that prohibition is right
rather than merely binding. A typealias would make every constant rentile renames a breaking change
for RenG's consumers, decided by a release RenG does not cut. `RenGMetricName` is RenG's promise;
the translation is where a rentile rename becomes RenG's problem instead of the consumer's.

The enum is named with the `RenG` prefix — unlike `ResourceReport` or `ResourceClass` — for the one
reason the prefix ever earns its keep here: the engine has a type of the same bare name, both are in
scope at the translation site, and a reader who cannot tell whose vocabulary they are holding is
exactly the reader this ADR exists to serve.

## An unrecognised name is counted, not dropped and not thrown

A `when` over rentile's enum with no `else` is exhaustive today and would fail RenG's own build the
moment rentile adds a constant, which is the signal worth having. It is also a runtime hazard:
RenG ships compiled klibs, a consumer's dependency resolution can raise rentile above the version
RenG compiled against, and an exhaustive `when` then throws `NoWhenBranchMatchedException` — a crash
in a diagnostic path, the worst place for one.

So the translation has an `else`, and it maps to `ENGINE_METRICS_UNRECOGNISED`, a RenG-owned
constant counting how many metrics this RenG had no name for. Nothing is silently lost, nothing
throws, and a consumer on a newer engine sees a non-zero counter saying so.

The compile-time signal is kept a different way: a test walks `MetricName.entries` and asserts each
one translates to something other than `ENGINE_METRICS_UNRECOGNISED`. That fails at RenG's gate on
the next rentile bump, which is when someone should be looking, and it costs no runtime safety.

## Three dimensions are deliberately dropped

`RentileMetric` carries `value`, `resourceClass` and `tags`. Only `value` survives, summed.

`resourceClass` is a **rentile** `ResourceClass`, which RenG cannot re-export and would have to
translate into its own — multiplying seventeen counters by eleven classes to answer a question
`queryResources` already answers per key, and answers better, because it knows about resources
rentile never sees. `tags` is free-form engine vocabulary whose keys rentile may rename without
telling anyone; putting it in a public type invites a consumer to depend on those strings.

Summing `value` is correct for both shapes rentile uses: the counters send `1` per occurrence and
the totals send bytes or nanoseconds. A sum of nanoseconds is a total, not a mean, and the report
says so — a consumer wanting a mean divides by the matching count, which is why `TILE_RENDERED` and
`ENGINE_TILE_DRAW_NANOS` are both present.

## Pulled, not pushed

The alternative was a consumer-supplied sink on `RendererConfiguration`. Rentile calls `record` from
wherever it happens to be working, so a push design hands consumer code a callback on a thread RenG
does not choose and cannot describe, in the middle of a preparation — the exact shape of the
re-entrancy hazards ADR 0015 spends its length on for GL. It also makes RenG's metrics a thing the
consumer must configure before they exist.

So RenG accumulates into its own sink and `queryMetrics()` returns a snapshot, beside
`queryResources()`. Counters are cumulative since the renderer was created and monotonic, for the
reason ADR 0048 already gives: a difference between two readings is the useful signal, and a
resettable counter is a second piece of state to get wrong.

`queryMetrics` goes through the lifecycle driver as its own `QueryMetrics` operation rather than
reading the sink directly, even though reading a counter map touches neither GL nor the cache and
would be safe. Uniformity is the argument: every other public query on `Renderer` is gated, a closed
renderer answers an empty report rather than a stale one, and a single ungated method is a special
case whoever next reads this file has to rediscover. It is permitted wherever `QueryResources` is,
including during an active preparation, for the same reason — it takes nothing the preparation needs.
