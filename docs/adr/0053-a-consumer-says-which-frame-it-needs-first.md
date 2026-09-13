# A consumer says which frame it needs first, in RenG's own vocabulary

The engine takes a `RenderPriority` on every render entry point and RenG has never passed one, so
every tile RenG has ever asked for has gone in at the default. That is the right default and the
wrong only option: a consumer preparing eight frames ahead of a scrub knows which one the user is
about to look at, and RenG has no way to be told.

## The queue already exists, and it is not RenG's

The obvious objection is that priority needs a queue to order, and RenG has none — `prepare` takes
`preparationMutex.tryLock()` and *fails* a second concurrent caller rather than queueing it. That
objection is wrong, and checking it is what made this worth doing: rentile maps `RenderPriority` onto
an internal `GateLane` of `FIRST` and `DEFERRED`, so the queue being ordered is the **engine's
resource gate**, which already exists and is already ordering every fetch RenG causes.

So this is a thread-through, not a scheduler. Nothing in RenG queues anything; RenG stops discarding
a signal the engine is already asking for.

It composes with ADR 0052 in the way that matters: a batch is exactly where a consumer knows the
frame it needs first, and a batch is now also where the tiles are rendered once. The frame the user
is about to see can go in `URGENT` while the rest of the batch follows behind it.

## RenG's own enum, for ADR 0049's reasons

`tools/check_repository_policy.py` forbids a `com.rohittp.rentile` type in the ABI, and a typealias
would make an engine rename a breaking change for RenG's consumers, decided by a release RenG does
not cut. `RenGRenderPriority` carries the `RenG` prefix on exactly the ground ADR 0049 set out: the
engine has a type of the same bare name, both are in scope at the translation site, and a reader must
be able to tell whose vocabulary they are holding.

The translation is total and keeps its `else`, for the reason ADR 0049 argues at length — RenG ships
compiled klibs and a consumer's dependency resolution can raise the engine above the version RenG
compiled against, where an exhaustive `when` throws. Unlike a metric name there is no honest
"unrecognised" value to fall back to, so the `else` maps to the engine's own default lane: a priority
RenG cannot name is a priority RenG must not guess at, and rendering at normal priority is what every
release before this one did.

## A defaulted parameter, and that is an ABI decision

`prepare` and `prepareBatch` gain a trailing `priority: RenGRenderPriority = NORMAL`. That is
**source-compatible and binary-breaking**: `prepare(plan)` still compiles unchanged, and a consumer's
already-compiled klib referencing the two-parameter signature does not link against the three.

The alternative was an overload per method — `prepare(plan, accessMode, priority)` beside
`prepare(plan, accessMode = NORMAL)`, with no default on the new one to keep resolution
unambiguous. Four entry points across the two methods, all meaning the same thing, and a consumer
having to know that passing a priority means giving up the access-mode default. That is a worse API
bought with a compatibility guarantee this project does not owe at 0.x, where a klib consumer
recompiles for every Kotlin release in any case. `consumer-smoke` compiles six targets against the
published artifact on every pull request, so source compatibility — the half that is actually load-
bearing here — is proven rather than asserted.

The parameter goes last on both, for the same reason ADR 0044 gave `ResourceLimits` its trailing
fields: every parameter before it is positional in shipped consumer code.

## What is deliberately not here

No per-tile priority. The engine takes one priority per render call, and RenG makes one render call
per frame; splitting a frame's tiles into an urgent centre and a deferred edge would mean several
calls where there is now one, and the tile a user looks at first is not something RenG can know
better than the consumer who chose the camera.

No priority on the resource driver's own path. A style, a sticker and a model GLB are acquired
through RenG's driver rather than the engine's gate, so there is no lane to put them in; a priority
that silently applied to the basemap and not to the sticker beside it would be a worse promise than
not making one.
