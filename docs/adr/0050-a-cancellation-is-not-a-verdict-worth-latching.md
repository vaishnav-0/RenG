# A cancellation is not a verdict, so the join must not latch one

`SuspendJoin` is the firewall's single-flight rendezvous: one coroutine owns a route's work, every
other caller for the same key parks on the owner's `CompletableDeferred`. `inFlight` is never pruned
— that is deliberate, and `OperationRegistry`'s own KDoc says why: a latched outcome is what makes
the join "never-evicting" and therefore safe. The outcome is the route's answer, and the route's
answer does not change inside one invocation.

Except one of them is not an answer. `run`'s `catch (t: Throwable)` latches a `CancellationException`
exactly like a transport failure or an integrity refusal, and `inFlight` keeps that entry for the
registry's lifetime. Every later caller for that key then gets `await()`'s replay: a
`CancellationException` raised in a coroutine nobody cancelled.

`HANDOFF.md` records this as parked: *"A latched cancellation is permanent per route for the
registry's lifetime, so a later healthy joiner replays a `CancellationException` it never earned. The
lifetime was bounded to one preparation invocation; the semantics inside that invocation were never
re-ruled."* This ADR re-rules them, **before** the change that makes the bound stop holding.

## Why now, and not as a tidy-up

Today a registry lives for exactly one `prepare()`, so the blast radius is one frame, and the
existing comment in `executeTransport` is a fair reading of that world:

> a cancelled route is a cancelled invocation, and a sibling parked at the rendezvous must observe
> that as its own cancellation rather than as a silent decline.

The next ADR widens an invocation to span a whole `prepareBatch`. The frames in a batch are
independently cancellable — that is the point of preparing several — so "a cancelled route is a
cancelled invocation" becomes false the moment the widening lands. Fixing the latch afterwards would
mean shipping, however briefly, a renderer where cancelling one frame poisons every later frame that
touches the same tile. So the rule moves first.

## The rule

**A latch records what is true of the resource. A cancellation is true of a caller.**

Success, a transport failure, an integrity refusal, a declined write: all of these are statements
about the route, they will be just as true for the next caller, and they stay latched exactly as they
are today. A cancellation says only that one coroutine stopped waiting, which tells the next caller
nothing at all about the resource it wants.

So on a cancellation the owner **forgets the key** before completing its deferred, and a later caller
finds no entry and does the work itself.

## The parked sibling is the harder half, and it is the half that matters

Forgetting the key fixes callers that arrive *after* the cancellation. A sibling already suspended in
`await()` still holds the deferred and still wakes with the owner's `CancellationException` — the
exact caller the widening creates, since in a batch frames 0 and 1 routinely want the same tile at
the same moment.

That sibling must ask a question it has never had to ask: *am I cancelled too?* `ensureActive()` on
its own context answers it. If the sibling's job is cancelled, it rethrows its own cancellation and
nothing has changed. If it is healthy, it loops: the entry is gone, so the next pass either owns the
work or joins whoever now does.

`ensureActive()` is deliberately explicit rather than load-bearing: the loop's next `Mutex.withLock`
is itself a cancellation point, so a cancelled sibling would leave through it anyway, and a mutation
that removes the call survives every test here. It stays because the correctness of this loop should
not rest on a lock happening to be cancellable, and because a reader must be able to see the question
being asked.

This is also why **today's behaviour is unchanged**. Under the current one-invocation registry a
cancellation almost always arrives from outside, cancelling the whole preparation and therefore every
sibling in it — so every sibling's `ensureActive()` rethrows and the observable outcome is identical
to the latch. The new path only comes alive where callers can be cancelled independently, which is
precisely the world the next ADR builds. A rule that changes nothing until it is needed is the
cheapest possible prerequisite.

## Forget before completing, and forget only your own entry

Two orderings matter enough to state.

The owner removes the entry *before* `completeExceptionally`, so no woken sibling can re-enter, find
the stale entry, and park on the same already-cancelled deferred.

It also removes the entry only if it is still the one it installed (`inFlight[key] === deferred`),
and that guard is **unreachable today** — stated plainly, because a comment implying a live race
where there is none is worse than no comment. Given the ordering above, `forget` runs to completion
before any sibling can wake, and no other path removes an entry, so nobody can have installed a
successor by then. The check is what keeps that reasoning from silently becoming wrong: moving
`forget` after `completeExceptionally` looks like a harmless simplification and would make the race
real, and the guard means the result would be a retry rather than a deferred nothing ever completes.
A mutation that drops it survives every test here, which is the honest measurement of a guard that
protects an ordering rather than a behaviour.

The removal is non-suspending — a `tryLock` spin, the shape `ResidentCache` already uses — for a
reason worth naming, because the obvious code is wrong here: the owner is running this inside a catch
for its own cancellation, and `Mutex.withLock` is a cancellable suspension point that would throw
`CancellationException` instead of taking the lock. The critical section is one map read and one
removal, and the lock is never held across a suspension anywhere in this class, so spinning is both
safe and shorter than wrapping it in `NonCancellable`.
