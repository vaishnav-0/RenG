# A Prepared Frame owns its leases and releases them when it closes

`closePreparedFrame` marked the frame closed and released nothing. Every lease a preparation took
therefore stayed outstanding for the renderer's whole life, and **a consumer that prepares many
frames — a video editor is exactly that — leaked without bound.**

This is not a new rule. `CONTEXT.md` already says where the ownership belongs: a lease is held "by a
**Prepared Frame** for every distinct resource its plan needs". `ResourceActionExecutor`'s own KDoc
was equally explicit that it had not been implemented — "this driver has no owner-lease bookkeeping
yet (a future task's job), so today's install-and-lease is real but its lease is not yet retained for
a later release." **This ADR is that task.** The decision is only to finish a sentence the codebase
had already written.

**The leak is visible from outside, which is what makes it a defect rather than an internal untidiness.**
Two frames prepared over one sticker locator and then both closed leave `leases=2 matched=1
fullyFreed=0 deferred=1`, measured through the public `queryResources()` / `freeResources()` surface.
So `freeResources()` could only ever report a matched key *deferred*; a superseded generation was
never reclaimed; and the one reclamation RenG offers a consumer never actually reclaimed anything.
The two regression tests measure exactly that, through that same public surface rather than through
internal state, because a consumer's experience of the leak is the thing worth pinning.

## Where the release is anchored, and why not at the driver's outcome

The release for a preparation that never reaches a frame sits in `prepare()`'s own `finally`, not at
the preparation driver's `Failure` and `Cancelled` outcomes.

There are more ways to throw between the driver run and the constructed frame than that outcome check
can see — the PNG decode, the sticker and geometry-texture `requireNotNull`s, the ground-instance
`check`. **`prepare()` is the one place that knows whether a frame ended up owning the leases**, so it
is the one place that can decide whether to release them. Anchoring at the driver would leak on every
path that fails after a successful drive, which is most of them.

Idempotency is carried by handing the list over exactly once: `takeLeases()` drains the frame's
snapshot, so a second `close()` releases an empty list. That matters because `Lease` rejects a double
release by construction (`require(!released)`), and `PreparedFrame.close()` is documented idempotent.
Two rules that would otherwise collide are reconciled by making the second close have nothing to do.

## Two traps, recorded because neither is visible at the call site

**The install must not be written inside the safe call.** `leaseSink?.add(cache.installAndTakeLease(…))`
reads as "record the lease if there is a sink", and is not: a Kotlin safe call short-circuits its
whole argument list, so that form **skips the install entirely** whenever no sink is supplied, and the
resource is never made resident at all. `DriverCancellationTest`'s
`contentAcquiredBeforeCancellationMayRemainResident` is what catches it. The install is therefore
evaluated into a local and recorded on the next line, and `recordLease` exists partly to make the
collapsed form unwritable.

**The append needs a guard.** The driver launches one child coroutine per action and selects no
dispatcher, so two installs of one operation genuinely can append from two threads whenever the
consumer's `prepare()` sits on a multi-threaded dispatcher — and an unguarded `MutableList` can drop
exactly the lease this sink exists to keep. `recordLease` takes the same `tryLock`-from-non-suspending-code
shape `ResidentCache` already uses, for the same reason: the critical section is one list append and
never spans a suspension.

## What this deliberately does not carry

The upstream change this is derived from also added a `contentDigest` to `PreparedSticker` and
`AcquiredImage` so the GL texture cache keyed on content rather than on the resource key. **That half
is not carried, and the reason is a property of RenG's own design rather than an omission.** RenG names
every generated texture by the SHA-256 of its own bytes (ADR 0007), so a change of content is a change
of locator and therefore of resource key: the slot-keying defect that half describes cannot bite a
consumer that content-addresses. Carrying it would mean re-porting its plumbing on every release for a
defect this repository has designed around.

## What it unblocks, stated so the next reader does not have to find it

`ResidentCache`'s rule is that "a retired generation with no outstanding lease is dropped immediately
— there is no automatic eviction of a leased one, and no automatic eviction at all otherwise." While
every install's lease was dropped on the floor, **every generation was permanently leased**, so any
eviction policy added to that cache would have had nothing it was allowed to reclaim. This ADR is a
precondition for bounding that cache at all, not merely a tidy-up beside it.

The public ABI is unchanged. Every type touched is `internal`, and both new parameters are defaulted.
