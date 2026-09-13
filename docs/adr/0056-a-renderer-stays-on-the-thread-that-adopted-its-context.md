# A renderer stays on the thread that adopted its context

ADR 0015 requires that any operation which can delete GL objects "proves that exact context is
current **on the calling thread** before any state change". RenG proves the first half and not the
second. `RenderContextProbe` answers with an identity, and `RendererFactory`'s own KDoc explains why
that answer is not really an observation:

> RenG never creates, makes current, or destroys a Render Context and never references CGL, EAGL,
> EGL, `NSOpenGLContext`, or `ANativeWindow` (ADR 0001) [...] There is therefore no production-safe
> way to ask any of the six published platforms "is a context current, and which one".
> Production therefore trusts the documented precondition instead.

So a consumer who calls `draw` from a thread where their context is not current gets no failure from
RenG at all. They get GL calls issued against no context or the wrong one: a black frame, a driver
error queue full of `GL_INVALID_OPERATION`, or a crash inside the driver — the three worst
diagnostics in the library, for the most ordinary mistake a consumer can make with OpenGL.

**The thread, unlike the context, RenG can observe.** Not as a substitute for the context check — a
thread identity says nothing about which context is current on it — but as a cheap, sound *necessary*
condition. A context current on thread A is not current on thread B unless something explicitly moved
it, so a call arriving on a different thread than the one that adopted the context is either a
migration the consumer performed and has not declared, or the bug above.

## This narrows the contract, and the narrowing is the decision

A blanket "same thread as construction" rule would be wrong. GL contexts may legally be released from
one thread and made current on another, and ADR 0015's phrasing — current *on the calling thread* —
admits exactly that. Outlawing it would break a consumer doing something permitted and correct.

So the rule is tied to adoption instead: **a renderer may be used from one thread at a time, and the
thread may change only across `adoptCurrentRenderContext()`.** That is not a new concept bolted on;
it is the one ADR 0015 already has. Adoption is where "a new exact identity and context generation"
is established, and after this ADR the calling thread is part of that identity.

**What that costs a consumer who really does migrate a context, stated because it is not free.**
Adoption is not available while a renderer is live — the lifecycle refuses it, because ADR 0015
reaches adoption only through declared loss: *"Further GL work after loss requires the consumer to
capture and explicitly adopt an already-current replacement context."* So a consumer moving a context
to another thread must call `notifyGpuObjectsGone()` and then adopt, which throws away every GPU
object RenG holds, even though moving a context loses none of them.

That is a real cost and this ADR does not pretend otherwise. It is accepted because the alternative
is worse in both directions: leaving the thread unchecked keeps the common bug undiagnosable, and
making adoption legal while live would rewrite the lifecycle's central invariant to serve a rare
case. A consumer who migrates contexts routinely should say so, and that would be a different ADR
about the lifecycle rather than a loosening of this one.

`createRenderer` records the first thread, because `createRenderer` already requires an
already-current context and therefore already fixes the thread that context is current on.

## What it applies to, and what it does not

Every entry point that issues GL or can delete a GL object: `draw`, `mintRenderTarget`,
`freeResources` and `close`. `adoptCurrentRenderContext` sets the new thread rather than checking the
old one — that is what makes it the declaration.

`notifyGpuObjectsGone` is **not** checked, which ADR 0015 settles rather than this one: it "is
idempotent, issues no GL call, forgets live and queued handles". A call that touches no context
cannot be on the wrong thread for one, and refusing it would take away the escape hatch the next
paragraph relies on.

Deliberately **not** `prepare`, `prepareBatch`, `cancelPreparations`, `queryResources`,
`queryMetrics`, `clearFrameHistory` or `freeResources`' read-only sibling. `prepare` is `suspend` and
"may legally be resumed from any context" — `BasemapEngineHostTest` has a whole case built on that —
so requiring a thread of it would break the very property RenG went to trouble to preserve. Those
entry points issue no GL call.

`close` is guarded **only while GPU objects are live**. ADR 0015 says "after declared loss there is
nothing to delete, so close is context-free", and a close that is context-free is thread-free for
exactly the same reason: the thread was only ever standing in for the context. A close that still has
live objects deletes them, which is the operation that rule was written for, so that one is refused
from the wrong thread — and a consumer closing from a finaliser or a shutdown hook has the escape
hatch ADR 0015 already gave them, `notifyGpuObjectsGone()` first.

## A typed failure, not an assertion

`RENDER_CONTEXT_THREAD_CHANGED` is appended to `RenGErrorCode` — last, so no existing constant's
ordinal moves, the rule `ResourceClass.BASEMAP_GLYPH_RANGE` already follows.

It is a failure rather than a `check`, because every neighbouring precondition in this area is one:
ADR 0015 is explicit that "either failure leaves renderer state unchanged" and that the consumer keeps
"the option to retry under the correct context". A thread mismatch is the same shape — recoverable by
adopting on the right thread — and throwing `IllegalStateException` would make the one precondition a
consumer is most likely to trip the only one they cannot catch by code.

It is admissible at exactly the stages `DIFFERENT_CURRENT_RENDER_CONTEXT` is —
`RENDER_TARGET`, `DRAW`, `RESOURCE_FREE`, `RENDERER_CLOSE` — which is not a coincidence but the same
family of failure seen one step earlier: both say "the context you promised is not the one here", one
by identity and one by the thread that identity was fixed on. `CONTEXT_ADOPTION` is absent because
adoption is where the thread is *set*, never where it is refused.

It names no thread. A thread identifier is not a resource, carries no meaning across a process
boundary, and `Diagnostic` has no field that would honestly hold one.

## Cost

One identity comparison per GL-touching call, against frames that spend hundreds of milliseconds in
rasterisation. The comparison is `pthread_equal` on the five native targets and reference equality on
Android; neither allocates.
