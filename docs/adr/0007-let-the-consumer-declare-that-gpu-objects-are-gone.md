# Let the consumer declare that GPU objects are gone

ADR 0015 supersedes this record's drop-and-warn fallback for free and close; GPU-object-loss declaration
remains context-free, while deletion operations now fail without state change unless the renderer's exact
context is current.

RenG exposes one operation meaning "the GL objects you hold no longer exist". It makes RenG forget
every GL handle without issuing a single delete, while keeping decoded images, parsed meshes, compiled
shader sources, and live **Prepared Frame**s exactly as they were; the next draw re-uploads what it
needs without re-fetching or re-decoding anything. Only the consumer can know this happened, because
only the consumer owns the **Render Context** — an EGL context dies when an Android app is backgrounded
or the GPU resets, and RenG has no reliable way to observe it.

This is deliberately not the existing free operation. Freeing means "you may reclaim these"; the
objects are still valid and RenG deletes them properly. After a context loss the objects are already
gone and their names may have been reused by an unrelated context, so issuing deletes would corrupt
someone else's state. The two operations differ in exactly that respect and are therefore separate,
and both are idempotent.

Because freeing and closing delete GL objects, both require the **Render Context** to be current on the
calling thread, which is stated as a precondition rather than discovered. Called without a current
context, RenG drops its GL handles and emits a warning instead of issuing deletes into whatever context
happens to be current — the objects belonged to a context that is being torn down anyway, and their
memory goes with it.

ADR 0015 supersedes that last fallback as stated above. The implementation also serializes the whole declared-loss
transition with renderer GL operations. Once any in-flight draw/free/close finishes, one critical section forgets
the object registry, compiled-program cache, fixed and consumer pipeline fields, and adopted context identity
together. A draw cannot resume midway through loss and republish a handle from the old context.
