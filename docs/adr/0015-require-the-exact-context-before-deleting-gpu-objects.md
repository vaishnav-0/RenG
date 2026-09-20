# Require the exact context before deleting GPU objects

This decision supersedes ADR 0007's fallback that silently drops GL handles when free or close is called
without the expected current context. A renderer is affine to the exact **Render Context** identity it
captured, not merely to a share group. Operation-state checks take precedence: in particular, ADR 0014's
`PREPARATION_IN_PROGRESS` rejection occurs before context validation and inspects no context. After those
checks, any operation that can delete live GL objects — resource free, deferred-deletion draining, or
renderer close — proves that exact context is current on the calling thread before any state change. No
current context and a different current context are distinct typed failures, and either failure leaves
renderer state unchanged. RenG never turns an unproved deletion precondition into a warning and a silent
leak.

**GPU Object Loss** remains the separate context-free declaration that the renderer's handles no longer
name live objects. `notifyGpuObjectsGone()` is idempotent, issues no GL call, forgets live and queued handles,
retains CPU-side resources and **Prepared Frame** leases, and invalidates prior **Render Target**s. RenG does
not infer loss from a missing or different current context: only the consumer can know that the old context
and its objects are gone. After declared loss there is nothing to delete, so close is context-free.

Further GL work after loss requires the consumer to capture and explicitly adopt an already-current
replacement context. Adoption establishes a new exact identity and context generation; it cannot delete
handles from the lost context, and it does not make old Render Targets valid again. This explicit transition
was chosen over automatic adoption because a merely current context is not evidence that the previous
objects died, and object names may refer to unrelated live state there.

Failing without state change gives the consumer a recoverable precondition error and preserves the option
to retry under the correct context. The former drop-and-warn fallback was rejected because it made free and
close indistinguishable from declared loss, hid unreclaimed live objects, and violated the contract that
freeing valid objects deletes them properly.

The exact-context proof and the complete permitted GL operation execute under one re-entrant renderer gate. That
gate serializes draw, free, close, adoption, and declared loss across calling threads, so lifecycle facts cannot
change between validation and deletion. Active-preparation admission remains outside and earlier in the lifecycle
decision: free or close still reports `PREPARATION_IN_PROGRESS` before probing a context.

Reusable consumer GPU allocations are identified by logical Resource Key, monotonic CPU generation, subresource,
and texture upload variant. Resource free snapshots selected CPU generations, retires matching GPU generations
(including GPU-only owners selected by key/kind), and deletes unleased allocations immediately. A leased allocation
is deleted by its last draw release; if an old Prepared Frame uploads after the free, the generation high-water mark
makes that allocation born retired, so it is likewise deleted at the end of that draw instead of becoming resident
again. Texture and model-buffer residency have independent byte ceilings, both enforced immediately when a last
lease makes an entry evictable.
