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
is established, and after this ADR the calling thread is part of that identity. A consumer migrating
a context does on the RenG side exactly what they already do on the GL side: make it current on the
new thread, then tell RenG.

`createRenderer` records the first thread, because `createRenderer` already requires an
already-current context and therefore already fixes the thread that context is current on.

## What it applies to, and what it does not

Every entry point that issues GL or can delete a GL object: `draw`, `mintRenderTarget`,
`freeResources`, `notifyGpuObjectsGone`, `adoptCurrentRenderContext` (which sets the new thread rather
than checking the old), and `close`.

Deliberately **not** `prepare`, `prepareBatch`, `cancelPreparations`, `queryResources`,
`queryMetrics`, `clearFrameHistory` or `freeResources`' read-only sibling. `prepare` is `suspend` and
"may legally be resumed from any context" — `BasemapEngineHostTest` has a whole case built on that —
so requiring a thread of it would break the very property RenG went to trouble to preserve. Those
entry points issue no GL call.

`close` is the awkward one and is included anyway. ADR 0015 makes close context-free *after declared
loss*, and a consumer closing from a finaliser or a shutdown hook may well be on another thread — but
a close that still has live GL objects deletes them, which is precisely the operation ADR 0015 wrote
its rule for. A consumer in that position already has the escape hatch ADR 0015 gave them:
`notifyGpuObjectsGone()` first, then close from anywhere.

## A typed failure, not an assertion

`RENDER_CONTEXT_THREAD_CHANGED` is appended to `RenGErrorCode` — last, so no existing constant's
ordinal moves, the rule `ResourceClass.BASEMAP_GLYPH_RANGE` already follows.

It is a failure rather than a `check`, because every neighbouring precondition in this area is one:
ADR 0015 is explicit that "either failure leaves renderer state unchanged" and that the consumer keeps
"the option to retry under the correct context". A thread mismatch is the same shape — recoverable by
adopting on the right thread — and throwing `IllegalStateException` would make the one precondition a
consumer is most likely to trip the only one they cannot catch by code.

It reports `PipelineStage.CONTEXT_ADOPTION`, and names no thread: a thread identifier is not a
resource, carries no meaning across a process boundary, and `Diagnostic` has no field that would
honestly hold one.

## Cost

One identity comparison per GL-touching call, against frames that spend hundreds of milliseconds in
rasterisation. The comparison is `pthread_equal` on the five native targets and reference equality on
Android; neither allocates.
