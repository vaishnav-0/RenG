package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.lifecycle.DeferredDeletion
import com.rohittp.reng.internal.lifecycle.DeletionId

internal enum class GlObjectType {
    TEXTURE,
    RENDERBUFFER,
    FRAMEBUFFER,
    BUFFER,
    VERTEX_ARRAY,
    SAMPLER,
    PROGRAM,
}

internal data class GlObjectHandle(val type: GlObjectType, val name: Int)

/**
 * A single-use claim that keeps a budget-tracked texture entry -- one [GlObjectRegistry.registerTexture]
 * created, whether this claim came from that call or from a later [GlObjectRegistry.leaseResident] on the
 * same key -- exempt from every deletion path in [GlObjectRegistry], byte-budgeted eviction and
 * [GlObjectRegistry.defer] alike, mirroring
 * [com.rohittp.reng.internal.cache.Lease]'s shape at the GPU layer: Cycle B's lease answers what MUST
 * stay resident, this one answers the same question for the GL texture behind it. Consumed by exactly
 * one matching [GlObjectRegistry.releaseLease] call; releasing it again is a caller error rather than a
 * silent no-op.
 *
 * The one deletion a lease cannot delay is renderer close, which deletes every handle
 * [GlObjectRegistry.liveKeys] still reports (ADR 0015): a lease postpones a deletion, it never outlives
 * the renderer that issued it.
 */
internal class TextureLease internal constructor(internal val key: ResourceKey) {
    private var released: Boolean = false

    internal fun markReleased() {
        require(!released) { "a GL texture lease cannot be released more than once" }
        released = true
    }
}

/**
 * A reusable GL texture and the [TextureLease] taken on it, handed back together by
 * [GlObjectRegistry.leaseResident] so that reusing a texture and uploading one leave the caller holding
 * the same thing: a GL name plus one claim to release when the draw is done.
 */
internal class LeasedTexture(internal val handle: GlObjectHandle, internal val lease: TextureLease)

/**
 * Owns every live GL object handle this renderer holds, plus a byte-budgeted, least-recently-used
 * residency policy over [registerTexture]'s budget-tracked textures specifically (a basemap tile
 * today; [register] remains the unbounded path Cycle F-1's sticker/geometry-consumer textures
 * already use, and this class does not change that).
 *
 * Cycle B's lease machinery already answers what MUST stay resident -- a texture a live Prepared
 * Frame still leases is never a candidate here. [residentTextureByteBudget] answers the separate
 * question of what MAY stay: an unleased, budget-tracked texture survives losing its lease so a
 * pan back over the same tile costs nothing, up to that byte budget, beyond which the
 * least-recently-used unleased texture is evicted first. Bytes rather than a texture count,
 * because memory is what actually runs out and a count means something different at every tile
 * size and on every device -- see [ResourceLimits.maximumResidentGpuTextureBytes].
 */
internal class GlObjectRegistry(
    private val residentTextureByteBudget: Long = ResourceLimits().maximumResidentGpuTextureBytes,
) {
    private val live: LinkedHashMap<ResourceKey, MutableList<GlObjectHandle>> = LinkedHashMap()
    private val queued: LinkedHashMap<DeletionId, List<GlObjectHandle>> = LinkedHashMap()

    // Budget-tracked texture residency (registerTexture/leaseResident/releaseLease only). textureByteSizes
    // covers every budget-tracked key regardless of lease state, so its sum is the true resident
    // total; unleasedOrder holds only the currently-unleased subset, oldest (least-recently-used)
    // first, and is exactly the eviction candidate list.
    private val textureByteSizes: MutableMap<ResourceKey, Long> = mutableMapOf()
    private val textureLeaseCounts: MutableMap<ResourceKey, Int> = mutableMapOf()
    private val unleasedOrder: LinkedHashMap<ResourceKey, Unit> = LinkedHashMap()

    // Keys a [defer] call asked to delete while a lease was still open: kept whole in `live` (so
    // `liveKeys()`/`handles()` -- and therefore renderer close -- still see them) and deleted by the
    // release of their last lease. Every member is leased by construction, so no member is ever in
    // `unleasedOrder`, and eviction cannot reach one either.
    private val retiredPendingLastLease: MutableSet<ResourceKey> = mutableSetOf()

    internal fun register(key: ResourceKey, handles: List<GlObjectHandle>) {
        live.getOrPut(key) { mutableListOf() }.addAll(handles)
    }

    /**
     * Registers a budget-tracked texture and returns the caller's [TextureLease] on it. The entry
     * starts leased -- registration itself is a use -- so it can never be evicted until that lease
     * (and every other outstanding one) is released; see [releaseLease].
     */
    internal fun registerTexture(key: ResourceKey, handle: GlObjectHandle, byteSize: Long): TextureLease {
        require(byteSize >= 0L) { "byteSize must be non-negative" }
        // Re-registering a key whose deletion is pending revives it, exactly as `ResidentCache.install`
        // clears `KeyEntry.freed`: the caller has just uploaded a fresh texture under this key, and
        // deleting it on the release of a lease taken before the free would be the same
        // use-after-delete this marker exists to prevent, only one generation later.
        retiredPendingLastLease.remove(key)
        live.getOrPut(key) { mutableListOf() }.add(handle)
        textureByteSizes[key] = byteSize
        return takeLease(key)
    }

    /**
     * Takes a lease on [key]'s already-resident budget-tracked texture and hands back both, or returns
     * `null` when there is nothing here to reuse.
     *
     * This is [registerTexture]'s other half, and deliberately its exact shape: the two ways a draw can
     * come by a ground texture -- upload it, or find last frame's still on the GPU -- both hand back a
     * [TextureLease] the caller must release exactly once, so a reused texture is protected for the draw
     * that reuses it by the same mechanism and to the same degree as a freshly uploaded one. Returning
     * the handle and the lease together is what makes that hard to get wrong: a caller cannot obtain the
     * GL name of a reusable texture from here without also taking the claim that keeps it alive.
     *
     * `null` means one of three things, and the caller's answer to all three is the same -- upload it and
     * register it. Either the key has no live texture at all; or it has one that [register] rather than
     * [registerTexture] put there, which is untracked by the byte budget and so must never enter the
     * eviction order this lease/release cycle feeds (a zero-byte candidate would be deleted without
     * moving the total it is being deleted to reduce); or its deletion is already pending on the release
     * of a lease taken before a [defer] retired it, and handing that texture to a new draw would extend
     * a life a free has already ended -- "a retired generation is never resurrected, even when identical
     * bytes return", so the reload is the fresh upload, exactly as [registerTexture] documents.
     */
    internal fun leaseResident(key: ResourceKey): LeasedTexture? {
        if (key !in textureByteSizes) return null
        if (key in retiredPendingLastLease) return null
        val handle = live[key]?.firstOrNull { it.type == GlObjectType.TEXTURE } ?: return null
        return LeasedTexture(handle = handle, lease = takeLease(key))
    }

    /**
     * The one place a [TextureLease] is minted: counts one more claim on [key] and withdraws it from the
     * eviction order, since a leased entry is never a candidate there. Both [registerTexture] and
     * [leaseResident] route through here so neither can drift into counting a lease the other does not.
     */
    private fun takeLease(key: ResourceKey): TextureLease {
        textureLeaseCounts[key] = (textureLeaseCounts[key] ?: 0) + 1
        unleasedOrder.remove(key)
        return TextureLease(key)
    }

    /**
     * Releases [lease]. If this was the key's last outstanding lease, one of two things happens: a key
     * [defer] retired while it was leased is deleted here and now, since the lease that was delaying its
     * deletion is gone; any other key simply becomes evictable and moves to the most-recently-used end of
     * the LRU order. [evictOverBudget] then runs either way.
     *
     * That re-insertion is the whole of this class's "recently used" signal, and it is why no separate
     * mark-as-used call exists: a draw that used a texture held a lease on it for its own duration, so
     * releasing that lease is precisely the moment the texture was last used.
     *
     * Both of those delete GL textures, and ADR 0015 requires the renderer's exact GL context to be
     * current for any GL delete call -- [binding] is threaded through for exactly that call, so
     * this method must only ever be invoked from an operation that has already confirmed the exact
     * context is current, the same discipline [deleteGlObjects] itself already assumes throughout
     * this file.
     */
    internal fun releaseLease(lease: TextureLease, binding: GlBinding) {
        lease.markReleased()
        val key = lease.key
        val remaining = (textureLeaseCounts[key] ?: 0) - 1
        check(remaining >= 0) { "cannot release a texture lease with no outstanding lease" }
        textureLeaseCounts[key] = remaining
        if (remaining > 0) return
        if (retiredPendingLastLease.remove(key)) {
            textureByteSizes.remove(key)
            textureLeaseCounts.remove(key)
            deleteGlObjects(binding, live.remove(key).orEmpty())
        } else {
            unleasedOrder.remove(key)
            unleasedOrder[key] = Unit
        }
        evictOverBudget(binding)
    }

    /** The resident [GlObjectHandle] for [key], whether registered via [register] or [registerTexture]. */
    internal fun resident(key: ResourceKey): GlObjectHandle? =
        live[key]?.firstOrNull { it.type == GlObjectType.TEXTURE }

    internal fun handles(key: ResourceKey): List<GlObjectHandle> = ArrayList(live[key].orEmpty())

    /**
     * Every live handle [key] holds of exactly [type], in registration order.
     *
     * A key registered by [register] holds one handle for a sticker or consumer texture, and several
     * of two different types for a model primitive -- one [GlObjectType.VERTEX_ARRAY] plus one
     * [GlObjectType.BUFFER] per attribute and one more for the index run. Filtering by type at the
     * caller is what that used to require, and every caller wrote the same `firstOrNull { it.type == }`
     * to do it; asking the registry the question directly is what stops a second copy of that filter
     * from drifting.
     */
    internal fun handlesOfType(key: ResourceKey, type: GlObjectType): List<GlObjectHandle> =
        live[key].orEmpty().filter { it.type == type }

    internal fun liveKeys(): List<ResourceKey> = ArrayList(live.keys)

    internal fun hasLiveGpuObjects(): Boolean = live.values.any { it.isNotEmpty() }

    /**
     * Queues [key]'s GL handles for deferred deletion under [id], or -- when the key still has an
     * outstanding [TextureLease] -- retires it instead and returns `null`, leaving every handle live
     * until the release of its last lease deletes them.
     *
     * A lease means "this texture is in use and must not go away". [evictOverBudget] honours that
     * structurally, by iterating only [unleasedOrder]; before this guard existed, this method did not
     * honour it at all, so the two deletion paths in this class disagreed about what a lease is worth.
     * That disagreement was never reachable: no production code calls this method (the deferred-deletion
     * ledger is designed but unwired -- `GpuLedger.deferredDeletions` is constructed empty in
     * `RendererFactory` and nothing ever appends to it), the only leases taken anywhere are
     * `RenGRenderer.performDraw`'s ground leases, which are taken and released inside one synchronous
     * `Draw` permitted operation, and every GL-bound operation must hold the renderer's exact context on
     * the calling thread (ADR 0015), so no free can interleave with a draw that holds a lease. **No
     * reaching sequence was constructible when this guard was written**; it is here so the two paths
     * agree before someone wires `freeResources()` onto budget-tracked tiles and makes it reachable.
     *
     * Retiring rather than refusing is what the contract asks for: "freeing is never an error for the
     * caller to recover from", and `ResidentCache.free` already retires a still-leased generation and
     * reports it in `ResourceFreeResult.deferredKeys` instead of rejecting the free. This is the same
     * rule one layer down.
     *
     * A retired key stays in [live], so `liveKeys()`/`handles()` still report it and renderer close still
     * deletes it -- a lease can delay a deletion but can never outlive the renderer. It deliberately does
     * NOT enter [queued]: nothing drains that map on close, so queueing here would trade a
     * use-after-delete for a leak. The last release therefore deletes directly, under the same
     * exact-context precondition [releaseLease] already documents for eviction.
     */
    internal fun defer(key: ResourceKey, id: DeletionId): DeferredDeletion? {
        if ((textureLeaseCounts[key] ?: 0) > 0) {
            retiredPendingLastLease += key
            return null
        }
        val handles = live.remove(key) ?: return null
        queued[id] = ArrayList(handles)
        textureByteSizes.remove(key)
        textureLeaseCounts.remove(key)
        unleasedOrder.remove(key)
        return DeferredDeletion(id = id, resourceKey = key)
    }

    internal fun takeQueued(id: DeletionId): List<GlObjectHandle> = queued.remove(id).orEmpty()

    /**
     * Declared GPU object loss: forget every live and queued handle without issuing a delete.
     *
     * A replacement context cannot delete handles from the lost one, and object names there may
     * refer to unrelated live state, so this method must never call the binding. This is the whole
     * reason residency lives here rather than on `ResidentCache`: forgetting the GL name costs the
     * next draw a re-upload, never a re-fetch, because the decoded image this texture came from is
     * untouched in `ResidentCache`, which survives context loss intact (ADRs 0007, 0015).
     */
    internal fun forgetEverything() {
        live.clear()
        queued.clear()
        textureByteSizes.clear()
        textureLeaseCounts.clear()
        unleasedOrder.clear()
        // Hygiene, deliberately not load-bearing and deliberately not covered by a test: no assertion can
        // observe this line, because [registerTexture] is the only way to obtain a lease and it clears the
        // key's marker first, so a marker that outlived a context loss could never reach [releaseLease].
        // It stays because "forget everything" that leaves one map populated is a claim this class would
        // then be making falsely.
        retiredPendingLastLease.clear()
    }

    /**
     * Evicts the least-recently-used unleased budget-tracked textures, oldest first, until resident
     * bytes fit [residentTextureByteBudget] or no unleased candidate remains. A texture with an
     * outstanding lease is never a candidate: it was never added to [unleasedOrder] (or was removed
     * from it by [registerTexture]'s re-lease guard), so it is structurally unreachable here
     * regardless of how far its bytes push the total over budget -- exceeding the budget because a
     * live Prepared Frame still needs a tile is the correct outcome; breaking a drawable frame to
     * honour a cache limit is not. A key [defer] retired while leased is leased by definition and is
     * therefore unreachable here for the same reason; [releaseLease] deletes it instead.
     */
    private fun evictOverBudget(binding: GlBinding) {
        var total = textureByteSizes.values.sum()
        val iterator = unleasedOrder.keys.iterator()
        while (total > residentTextureByteBudget && iterator.hasNext()) {
            val key = iterator.next()
            iterator.remove()
            val size = textureByteSizes.remove(key) ?: 0L
            textureLeaseCounts.remove(key)
            val handles = live.remove(key).orEmpty()
            deleteGlObjects(binding, handles)
            total -= size
        }
    }
}

internal fun deleteGlObjects(binding: GlBinding, handles: List<GlObjectHandle>) {
    if (handles.isEmpty()) return
    val byType = handles.groupBy { it.type }
    byType[GlObjectType.FRAMEBUFFER]?.let { binding.deleteFramebuffers(it.size, it.names()) }
    byType[GlObjectType.RENDERBUFFER]?.let { binding.deleteRenderbuffers(it.size, it.names()) }
    byType[GlObjectType.TEXTURE]?.let { binding.deleteTextures(it.size, it.names()) }
    byType[GlObjectType.SAMPLER]?.let { binding.deleteSamplers(it.size, it.names()) }
    byType[GlObjectType.VERTEX_ARRAY]?.let { binding.deleteVertexArrays(it.size, it.names()) }
    byType[GlObjectType.BUFFER]?.let { binding.deleteBuffers(it.size, it.names()) }
    byType[GlObjectType.PROGRAM]?.forEach { binding.deleteProgram(it.name) }
}

private fun List<GlObjectHandle>.names(): IntArray = IntArray(size) { this[it].name }
