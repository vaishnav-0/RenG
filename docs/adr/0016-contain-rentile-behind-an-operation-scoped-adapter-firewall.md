# Contain Rentile behind an operation-scoped adapter firewall

Rentile 0.1.5 is not a transparent pass-through resource boundary. Its tile paths may call Transport twice
for one thrown or transient outcome, and invalid stored content causes a private remove-then-fetch repair.
RenG's public contract instead permits at most one consumer exchange for one logical resource operation and
makes stored corruption terminal without consumer mutation. Actual proofs showed that a stateless adapter
violates those guarantees and that one long-lived Rentile instance with an operation-scoped firewall can
preserve them across all eight basemap resource classes.

A renderer owns one long-lived Rentile instance whose fixed adapters multiplex through the one active
preparation invocation. Each invocation preregisters static prelookup routes by access mode, exact locator,
exhaustive resource class, and response-byte limit. Preregistration establishes joins and detects static private
Rentile key collisions but assigns no execution order. Dynamic occurrences register at depth-first discovery frontiers;
the first logically eligible occurrence assigns the shared route's traversal ordinal, so an earlier discovered
child can activate a route preregistered from a later static segment. Equal routes join one freshness sample,
resident decision, and Store read. After that lookup determines the allowlisted request metadata, the final
Transport latch key is the route plus all three exact metadata values. The first consumer Transport result is
latched as a defensively copied response, a sanitized RenG failure, or an unwrapped `CancellationException`;
Kotlin stack recovery may copy that exception while preserving the original as its immediate cause. Concurrent
joins and Rentile's later adapter call replay that outcome. The registry is discarded when the invocation
terminates; it is neither a renderer-lifetime response cache nor permission to share work across access modes.

Rentile does not carry `ResourceAccessMode` through its `ResourceTransport` or `RawResourceStore` callbacks.
The firewall therefore binds mode from the explicit outer preparation invocation, includes it in RenG's
operation identity, and never reconstructs it from a Rentile key. It validates every consumer record's
shape, digest, metadata, freshness, byte limit, and class-specific encoded format before exposing it to
Rentile. A Rentile `remove` request is private and terminal: it performs no consumer removal, no repair, and
no follow-on exchange.

Rentile's write ordering is class-specific. TileJSON, vector-tile, raster-tile, and GeoJSON paths reach
Rentile's raw-store write only after their bounded parser or decoder validation. DEM reaches it after generic
bounded image validation, not terrain-encoding semantic validation. Style has no Rentile raw-store write.
Sprite JSON and image bytes are written before their joint atlas validation, and complete sprite-image
decoding may otherwise be deferred until rendering. RenG therefore never treats Rentile's callback as a
universal post-format-validation boundary.

RenG never rewrites a record selected directly from resident state or the consumer Store. Store-sourced style
bytes compile privately and may become preparation-visible only after compilation and whole-batch success,
without another consumer write. Fetched or `304`-metadata-refreshed style bytes stage privately for Rentile
compilation and write only after successful compilation and completion of all other work for the referencing
preparation items; staged bytes are not preparation-visible. RenG jointly prevalidates the complete sprite
JSON-and-PNG pair before writing either fetched member; the consumer writes are sequential, but no atlas becomes
visible unless both succeed. A fetched DEM write additionally requires RenG's terrain encoding validation. For
the remaining classes, a Rentile write callback may perform the consumer write only after RenG verifies that it
matches the latched response and the stricter RenG record rules. A valid `304` is first merged with its validated
stale baseline and presented to Rentile as a bounded full response; every other redirect or disallowed status
fails.

Tile substitution remains disabled, and RenG never invokes Rentile's exact-tile retry helper. Internal
Rentile adapter counts are not public retries: only consumer Transport and Store calls define RenG's
observable boundary. Adapter text, locators, validators, and causes never cross that boundary; cancellation
remains an unwrapped coroutine cancellation rather than a RenG failure.

A direct pass-through was rejected because it observably duplicates exchanges and mutates corrupt entries.
Creating a new Rentile instance for every resource operation was sufficient for the throwaway proof but
would discard renderer-lifetime planning state and make ownership needlessly expensive. Forking Rentile was
also rejected while the adapter firewall can enforce the contract against the published coordinate. The
operation-aware multiplexer is more stateful than a simple adapter, but its lifetime is bounded by the
already-serialized preparation contract. One long-lived Rentile instance passed sequential mode isolation,
actual raster/vector/DEM/TileJSON/GeoJSON/style/sprite paths, a 256-tile batch at concurrency eight,
cancellation/redaction checks, six-target compilation, and identical Android/macOS executable suites.

## Erratum (2026-08-20, Cycle E basemap preflight)

This ADR's opening states the firewall's guarantees were proved "across all eight basemap resource classes,"
and its closing paragraph names the paths measured — raster, vector, DEM, TileJSON, GeoJSON, style, and
sprite — the same eight classes under seven descriptors (sprite covers two). Rentile `0.3.0` added a ninth,
`GLYPH_RANGE`, measured in `docs/research/2026-08-19-rentile-030-counting-stub-respike.md`. "Eight" was an
accurate count of the `0.1.5` artifact this ADR's own spike actually drove; only Rentile's later surface
growth made the count stale.

`GLYPH_RANGE` carries `accept = application/x-protobuf`. Like `BASEMAP_DEM_TILE` — and unlike
`BASEMAP_SPRITE_JSON`/`BASEMAP_SPRITE_IMAGE` — it reaches Rentile's raw-store write before its own decode
validation runs. And unlike the sprite pair's terminal poisoning, a digest mismatch on a stored `GLYPH_RANGE`
record recovers by remove-then-refetch, the same non-terminal shape this ADR already describes for the
TileJSON/vector/raster/DEM/GeoJSON majority.

It is reachable only through a new entry point, `acquireLabelCandidates(style, tiles, resourceAccess)`, never
through `prepare`, `prepareBatch`, or `render` — the three methods this ADR's firewall is built against. RenG
does not call `acquireLabelCandidates`, so `GLYPH_RANGE` stays deliberately outside this firewall: this ADR's
guarantees hold for the classes reachable from the entry points RenG actually drives, and adding a row for a
class RenG never routes would be exactly the failure the respike was run to rule out — enumerating a class
without adopting the entry point that reaches it. The firewall's existing posture toward a class it does not
enumerate is unchanged by this erratum: it fails closed on an unrecognised URL or resource class rather than
special-casing one it cannot handle, which is what keeps `GLYPH_RANGE` safely absent rather than silently
mishandled.

## Second erratum (2026-08-28, Cycle E-labels)

The first erratum reasons entirely about why enumerating `GLYPH_RANGE` *without* adopting the entry point
that reaches it would be wrong. That reasoning was correct for the situation it described and is kept as the
record of the posture RenG held while it did not call that entry point. It has nothing to say about the
situation that now exists, which is RenG adopting it: E-labels adds `ResourceClass.BASEMAP_GLYPH_RANGE`,
drives the label handover, and brings the class **inside** this firewall rather than leaving it deliberately
outside.

**One clause of the first erratum is already false and is corrected here rather than in its body.** It says
the class "is reachable only through a new entry point, `acquireLabelCandidates(style, tiles,
resourceAccess)`". Since Rentile `0.5.0` there have been **two** overloads of `acquireLabelCandidates` — a
plan-taking one and the one-shot one that clause names (`Api.kt:789` and `:798` at `0.6.0`) — and the
plan-taking overload is the one RenG calls. The other half of that clause, "never through `prepare`,
`prepareBatch`, or `render`", remains true and is the load-bearing half: it is why everything this ADR's
body guarantees about those three entry points is untouched by anything below. RenG must **never** call the
one-shot overload, and now for a stronger reason than tidiness — its three-line body plans and acquires
inside one uninterruptible call, which closes the exact window preregistration needs.

**The handover spans two resource classes inside one operation.** `planLabelCandidates` acquires the label
layers' `VECTOR_TILE`s under this same firewall and freezes the glyph closure without fetching a byte;
`acquireLabelCandidates(plan)` then acquires `GLYPH_RANGE` and nothing else. The sequence is: open one
operation, preregister the draw routes and the label-layer tile routes, call `planLabelCandidates`, read
`plan.glyphUrls(template)`, call `registerRoutes` again with those URLs, call `acquireLabelCandidates(plan)`.
`glyphUrls` dies with the plan's `close()`, so the URLs are read before it closes.

**Exact-string preregistration survives, with a second mid-invocation `registerRoutes` round and nothing
else — and this was measured rather than inferred.** A spike drove one real handover end to end
(`docs/research/2026-08-28-e-labels-handover-spike.md`): `glyphUrls` returned **3** URLs across two font
stacks and two codepoint blocks, the consumer's Transport was then asked for exactly those 3, each exactly
once, all under `BASEMAP_GLYPH_RANGE` — no fourth URL, nothing unpreregistered fetched, nothing
preregistered left unfetched — and planning fetched **zero** glyph bytes. The closure is exact by
construction rather than by promise: `glyphClosure` and the acquisition read the same frozen
`assembly.requiredRanges` on the same plan object. The second `registerRoutes` round is the same bend this
ADR already took for style-derived routes and not a new one; `BasemapEngineHost.registerRoutes` exists for
that reason and its KDoc already says why preregistration cannot all happen when the invocation opens.

**Three things the same spike found that this erratum records so they are not rediscovered.** An
unpreregistered glyph URL is refused by the **store** index rather than the transport index, because
Rentile's glyph acquirer reads its raw store first — so it does not surface as `AMBIGUOUS_RESOURCE_ROUTE`,
as two preflight documents predicted, but as an opaque `BASEMAP_RENDER_FAILED`. The *refusal* is complete,
which is what this ADR guarantees; what was wrong was the diagnosis, and E-labels gives it a name. Second,
`glyphUrls` compares only the **redacted** template forms, so it cannot catch a stale credential: a caller
passing one gets back a plausible, non-empty list whose every URL is wrong, which under this firewall
presents as every glyph route preregistered and none matched. Third, `GlyphTemplateMismatchException` and
`LabelCandidatePlanClosedException` escape RenG **unwrapped**, because `glyphUrls` is a plan method rather
than a call through `engineCall` — an engine exception type crossing the public boundary breaches this ADR's
own sanitized-failure rule, and E-labels closes that gap.

Everything else in the first erratum stands: the `accept = application/x-protobuf` value, the DEM-like
ordering that reaches Rentile's raw-store write before decode validation, the non-terminal
remove-then-refetch on a stored digest mismatch, and this ADR's fail-closed posture toward a URL or resource
class it does not recognise. That posture is what kept `GLYPH_RANGE` safely absent while it was unenumerated;
what changes now is only that it is enumerated, routed, and measured.
