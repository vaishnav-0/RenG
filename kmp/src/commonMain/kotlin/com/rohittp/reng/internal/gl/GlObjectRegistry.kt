package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.GpuByteAccount
import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.lifecycle.DeferredDeletion
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.thread.PlatformLock

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

internal sealed interface GpuSubresource {
    data object Texture : GpuSubresource

    data class ModelImage(val imageIndex: Int) : GpuSubresource

    data class ModelPrimitive(val meshIndex: Int, val primitiveIndex: Int) : GpuSubresource
}

internal data class GpuUploadVariant(
    val content: TextureContent,
    val sampler: TextureSamplerState,
)

/** Exact identity of reusable GPU content; a locator-derived key alone is intentionally insufficient. */
internal data class GpuResourceIdentity(
    val ownerKey: ResourceKey,
    val generationId: ResidentGenerationId,
    val subresource: GpuSubresource,
    val uploadVariant: GpuUploadVariant? = null,
)

internal class GpuAllocationLease internal constructor(
    internal val identity: GpuResourceIdentity,
    internal val registryEpoch: Long,
) {
    private var released: Boolean = false

    internal fun markReleased() {
        require(!released) { "a GPU allocation lease cannot be released more than once" }
        released = true
    }
}

internal class LeasedGpuAllocation(
    internal val payload: Any,
    internal val lease: GpuAllocationLease,
)

internal data class GpuResourceResidency(
    val textureBytes: Long,
    val textureBudgetBytes: Long,
    val bufferBytes: Long,
    val bufferBudgetBytes: Long,
) {
    val overBudget: Boolean
        get() = textureBytes > textureBudgetBytes || bufferBytes > bufferBudgetBytes
}

internal data class GpuGenerationRetirement(
    val matchedOwnerKeys: Set<ResourceKey>,
    val deferredOwnerKeys: Set<ResourceKey>,
)

internal data class GpuOwnerSnapshot(
    val key: ResourceKey,
    val residentGenerationCount: Int,
    val retiredGenerationCount: Int,
    val leaseCount: Int,
    val knownBytes: Long,
)

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
internal class TextureLease internal constructor(
    internal val key: ResourceKey,
    internal val registryEpoch: Long,
) {
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
 * residency policy shared by [registerTexture]'s legacy key-only textures and the generation-aware
 * allocations registered through [registerAllocation]. Consumer textures and model buffers use the
 * latter path, so their measured bytes participate in the same texture/buffer eviction policy.
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
    private val residentBufferByteBudget: Long = 512L * 1024L * 1024L,
) {
    private val lock = PlatformLock()
    private val live: LinkedHashMap<ResourceKey, MutableList<GlObjectHandle>> = LinkedHashMap()
    private val queued: LinkedHashMap<DeletionId, List<GlObjectHandle>> = LinkedHashMap()

    // Budget-tracked texture residency (registerTexture/leaseResident/releaseLease only). textureByteSizes
    // covers every budget-tracked key regardless of lease state, so its sum is the true resident
    // total; unleasedOrder holds only the currently-unleased subset, oldest (least-recently-used)
    // first, and is exactly the eviction candidate list.
    private val textureByteSizes: MutableMap<ResourceKey, Long> = mutableMapOf()
    private val textureLeaseCounts: MutableMap<ResourceKey, Int> = mutableMapOf()
    private val unleasedOrder: LinkedHashMap<ResourceKey, Long> = LinkedHashMap()

    // Keys a [defer] call asked to delete while a lease was still open: kept whole in `live` (so
    // `liveKeys()`/`handles()` -- and therefore renderer close -- still see them) and deleted by the
    // release of their last lease. Every member is leased by construction, so no member is ever in
    // `unleasedOrder`, and eviction cannot reach one either.
    private val retiredPendingLastLease: MutableSet<ResourceKey> = mutableSetOf()

    private class AllocationEntry(
        val identity: GpuResourceIdentity,
        val handles: List<GlObjectHandle>,
        val textureBytes: Long,
        val bufferBytes: Long,
        val payload: Any,
        var leaseCount: Int,
        var retired: Boolean,
    )

    /** Generation-aware consumer allocations. The payload removes the old unsynchronised side map. */
    private val allocations: MutableMap<GpuResourceIdentity, AllocationEntry> = mutableMapOf()
    private val allocationUnleasedOrder: LinkedHashMap<GpuResourceIdentity, Long> = LinkedHashMap()
    // Free always selects every generation of a logical key, and generation IDs are monotonic. One
    // high-water mark per freed key therefore represents every retired generation without retaining
    // one marker per reload/free cycle for the renderer's lifetime.
    private val retiredThroughGeneration: MutableMap<ResourceKey, ResidentGenerationId> = mutableMapOf()
    private var registryEpoch: Long = 0L
    private var recencyClock: Long = 0L

    internal fun register(key: ResourceKey, handles: List<GlObjectHandle>) {
        lock.withLock { live.getOrPut(key) { mutableListOf() }.addAll(handles) }
    }

    internal fun registerAllocation(
        identity: GpuResourceIdentity,
        handles: List<GlObjectHandle>,
        textureBytes: Long,
        bufferBytes: Long,
        payload: Any,
    ): GpuAllocationLease {
        require(textureBytes >= 0L) { "textureBytes must be non-negative" }
        require(bufferBytes >= 0L) { "bufferBytes must be non-negative" }
        require(textureBytes > 0L || bufferBytes > 0L) { "a measured allocation must occupy bytes" }
        return lock.withLock {
            check(identity !in allocations) { "GPU allocation is already resident" }
            val retired = retiredThroughGeneration[identity.ownerKey]
                ?.let { identity.generationId.value <= it.value } == true
            allocations[identity] = AllocationEntry(
                identity = identity,
                handles = ArrayList(handles),
                textureBytes = textureBytes,
                bufferBytes = bufferBytes,
                payload = payload,
                leaseCount = 1,
                retired = retired,
            )
            GpuAllocationLease(identity, registryEpoch)
        }
    }

    internal fun leaseAllocation(identity: GpuResourceIdentity): LeasedGpuAllocation? = lock.withLock {
        val entry = allocations[identity] ?: return@withLock null
        // A free can precede the first upload of an old-but-still-open PreparedFrame. That upload is
        // born retired and must die at the end of this draw, but a second occurrence of the same
        // identity in this *same* serialized draw still has to share it. Every resident retired entry
        // necessarily has an active lease: retirement removes an unleased entry immediately and the
        // final release removes a leased one. The renderer's GL operation gate serializes draws, so
        // admitting another lease here cannot expose the retired allocation to a later draw.
        check(!entry.retired || entry.leaseCount > 0) {
            "an unleased retired GPU allocation must not remain resident"
        }
        entry.leaseCount += 1
        allocationUnleasedOrder.remove(identity)
        LeasedGpuAllocation(entry.payload, GpuAllocationLease(identity, registryEpoch))
    }

    internal fun releaseAllocation(
        lease: GpuAllocationLease,
        binding: GlBinding,
    ): GpuResourceResidency {
        val result = lock.withLock {
            lease.markReleased()
            if (lease.registryEpoch != registryEpoch) {
                return@withLock AllocationReleaseResult(allocationResidencyLocked(), emptyList())
            }
            val entry = checkNotNull(allocations[lease.identity]) {
                "leased GPU allocation must remain resident"
            }
            entry.leaseCount -= 1
            check(entry.leaseCount >= 0) { "GPU allocation lease count cannot be negative" }
            val directlyDeleted = if (entry.leaseCount == 0 && entry.retired) {
                allocations.remove(entry.identity)
                allocationUnleasedOrder.remove(entry.identity)
                entry.handles
            } else {
                if (entry.leaseCount == 0) {
                    allocationUnleasedOrder.remove(entry.identity)
                    allocationUnleasedOrder[entry.identity] = nextRecencyLocked()
                }
                emptyList()
            }
            val eviction = evictOverBudgetPoolsLocked()
            AllocationReleaseResult(
                residency = allocationResidencyLocked(),
                handlesToDelete = directlyDeleted + eviction,
            )
        }
        deleteGlObjects(binding, result.handlesToDelete)
        return result.residency
    }

    /**
     * Retires exact CPU generations selected by resource free. A later upload by an old Prepared
     * Frame is born retired and is deleted when its draw-scoped lease ends.
     */
    internal fun retireResources(
        selector: ResourceSelector,
        generations: Map<ResourceKey, Set<ResidentGenerationId>>,
        binding: GlBinding,
    ): GpuGenerationRetirement {
        val result = lock.withLock {
            generations.forEach { (owner, ids) ->
                val maximum = ids.maxByOrNull { it.value } ?: return@forEach
                val previous = retiredThroughGeneration[owner]
                if (previous == null || maximum.value > previous.value) {
                    retiredThroughGeneration[owner] = maximum
                }
            }
            val matched = linkedSetOf<ResourceKey>()
            val deferred = linkedSetOf<ResourceKey>()
            val delete = mutableListOf<GlObjectHandle>()
            allocations.values.toList().forEach { entry ->
                val selectedGeneration =
                    entry.identity.generationId in generations[entry.identity.ownerKey].orEmpty()
                if (!selectedGeneration && !entry.identity.ownerKey.matches(selector)) {
                    return@forEach
                }
                val previous = retiredThroughGeneration[entry.identity.ownerKey]
                if (previous == null || entry.identity.generationId.value > previous.value) {
                    retiredThroughGeneration[entry.identity.ownerKey] = entry.identity.generationId
                }
                matched += entry.identity.ownerKey
                entry.retired = true
                allocationUnleasedOrder.remove(entry.identity)
                if (entry.leaseCount == 0) {
                    allocations.remove(entry.identity)
                    delete += entry.handles
                } else {
                    deferred += entry.identity.ownerKey
                }
            }
            textureByteSizes.keys.toList().forEach { key ->
                if (!key.matches(selector)) return@forEach
                matched += key
                unleasedOrder.remove(key)
                if ((textureLeaseCounts[key] ?: 0) > 0) {
                    retiredPendingLastLease += key
                    deferred += key
                } else {
                    textureByteSizes.remove(key)
                    textureLeaseCounts.remove(key)
                    delete += live.remove(key).orEmpty()
                }
            }
            RetirementResult(
                publicResult = GpuGenerationRetirement(matched, deferred),
                handlesToDelete = delete,
            )
        }
        deleteGlObjects(binding, result.handlesToDelete)
        return result.publicResult
    }

    internal fun allocationOwnerKeys(): Set<ResourceKey> = lock.withLock {
        allocations.values.mapTo(linkedSetOf()) { it.identity.ownerKey }.apply {
            addAll(textureByteSizes.keys)
        }
    }

    internal fun allocationSnapshots(selector: ResourceSelector): List<GpuOwnerSnapshot> = lock.withLock {
        val allocationsByOwner = allocations.values
            .filter { it.identity.ownerKey.matches(selector) }
            .groupBy { it.identity.ownerKey }
        val keys = linkedSetOf<ResourceKey>().apply {
            addAll(allocationsByOwner.keys)
            textureByteSizes.keys.filterTo(this) { it.matches(selector) }
        }
        keys.map { key ->
                val owned = allocationsByOwner[key].orEmpty()
                GpuOwnerSnapshot(
                    key = key,
                    residentGenerationCount = owned.mapTo(linkedSetOf()) { it.identity.generationId }.size,
                    retiredGenerationCount = owned.filter { it.retired }
                        .mapTo(linkedSetOf()) { it.identity.generationId }.size,
                    leaseCount = owned.sumOf { it.leaseCount } + (textureLeaseCounts[key] ?: 0),
                    knownBytes = owned.sumOf { it.textureBytes + it.bufferBytes } +
                        (textureByteSizes[key] ?: 0L),
                )
            }
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
        return lock.withLock {
            retiredPendingLastLease.remove(key)
            live.getOrPut(key) { mutableListOf() }.add(handle)
            textureByteSizes[key] = byteSize
            takeLeaseLocked(key)
        }
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
    internal fun leaseResident(key: ResourceKey): LeasedTexture? = lock.withLock {
        if (key !in textureByteSizes) return@withLock null
        if (key in retiredPendingLastLease) return@withLock null
        val handle = live[key]?.firstOrNull { it.type == GlObjectType.TEXTURE }
            ?: return@withLock null
        LeasedTexture(handle = handle, lease = takeLeaseLocked(key))
    }

    /**
     * The one place a [TextureLease] is minted: counts one more claim on [key] and withdraws it from the
     * eviction order, since a leased entry is never a candidate there. Both [registerTexture] and
     * [leaseResident] route through here so neither can drift into counting a lease the other does not.
     */
    private fun takeLeaseLocked(key: ResourceKey): TextureLease {
        textureLeaseCounts[key] = (textureLeaseCounts[key] ?: 0) + 1
        unleasedOrder.remove(key)
        return TextureLease(key, registryEpoch)
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
     *
     * Returns the residency [evictOverBudget] left behind, which the caller needs because
     * [GpuTextureResidency.overBudget] is the thrash condition and this class deliberately cannot
     * report it: emitting a diagnostic needs the consumer's sink, and GL-side bookkeeping has no
     * business holding one. The caller that does hold it decides what a whole frame's worth of
     * these adds up to.
     */
    internal fun releaseLease(lease: TextureLease, binding: GlBinding): GpuTextureResidency {
        val result = lock.withLock {
            lease.markReleased()
            if (lease.registryEpoch != registryEpoch) {
                return@withLock ReleaseResult(residencyLocked(), emptyList())
            }
            val key = lease.key
            val remaining = (textureLeaseCounts[key] ?: 0) - 1
            check(remaining >= 0) { "cannot release a texture lease with no outstanding lease" }
            textureLeaseCounts[key] = remaining
            if (remaining > 0) {
                ReleaseResult(residencyLocked(), emptyList())
            } else {
                val retiredHandles = if (retiredPendingLastLease.remove(key)) {
                    textureByteSizes.remove(key)
                    textureLeaseCounts.remove(key)
                    live.remove(key).orEmpty()
                } else {
                    unleasedOrder.remove(key)
                    unleasedOrder[key] = nextRecencyLocked()
                    emptyList()
                }
                val eviction = evictOverBudgetPoolsLocked()
                ReleaseResult(
                    residency = residencyLocked(),
                    handlesToDelete = retiredHandles + eviction,
                )
            }
        }
        deleteGlObjects(binding, result.handlesToDelete)
        return result.residency
    }

    /** The resident [GlObjectHandle] for [key], whether registered via [register] or [registerTexture]. */
    internal fun resident(key: ResourceKey): GlObjectHandle? = lock.withLock {
        live[key]?.firstOrNull { it.type == GlObjectType.TEXTURE }
    }

    /**
     * What this registry knows about [key]'s GPU bytes — a read-only view, not the maps themselves.
     *
     * Generation-aware consumer/model allocations and [registerTexture] entries carry exact byte
     * sizes, so they contribute to [GpuByteAccount.knownBytes]. Only legacy handles inserted through
     * unmeasured [register] make [GpuByteAccount.hasUnknownBytes] true. A key with no live handle or
     * allocation at all is [GpuByteAccount.NoGpuObjects], where zero is genuine knowledge.
     *
     * The measured branch reports what the **budget** counts, deliberately: `textureByteSizes` is
     * both this answer and `evictOverBudget`'s input, so a consumer reading a report and a consumer
     * tuning [ResourceLimits.maximumResidentGpuTextureBytes] are looking at one number.
     */
    internal fun gpuByteAccount(key: ResourceKey): GpuByteAccount = lock.withLock {
        val trackedBytes = textureByteSizes[key]
        val measuredAllocationBytes = allocations.values
            .asSequence()
            .filter { it.identity.ownerKey == key }
            .sumOf { it.textureBytes + it.bufferBytes }
        val hasMeasured = trackedBytes != null || measuredAllocationBytes > 0L
        val knownBytes = (trackedBytes ?: 0L) + measuredAllocationBytes
        // Legacy registerTexture stores its handle in `live` as well as its measured byte map. That
        // handle is not an additional unknown allocation; only a live legacy key with no measured
        // registration is unknown.
        val hasUnknown = trackedBytes == null && live[key]?.isNotEmpty() == true
        when {
            hasUnknown && hasMeasured -> GpuByteAccount(knownBytes, hasUnknownBytes = true)
            hasUnknown -> GpuByteAccount.Unmeasurable
            hasMeasured -> GpuByteAccount.measured(knownBytes)
            else -> GpuByteAccount.NoGpuObjects
        }
    }

    internal fun handles(key: ResourceKey): List<GlObjectHandle> =
        lock.withLock { ArrayList(live[key].orEmpty()) }

    /**
     * Every live handle [key] holds of exactly [type], in registration order.
     *
     * Legacy callers of [register] may associate several object types with one logical key. Filtering
     * by type at the caller used to require each caller to write the same
     * `firstOrNull { it.type == }`; asking the registry directly keeps that filter from drifting.
     */
    internal fun handlesOfType(key: ResourceKey, type: GlObjectType): List<GlObjectHandle> =
        lock.withLock { live[key].orEmpty().filter { it.type == type } }

    internal fun liveKeys(): List<ResourceKey> = lock.withLock { ArrayList(live.keys) }

    internal fun hasLiveGpuObjects(): Boolean = lock.withLock {
        live.values.any { it.isNotEmpty() } || allocations.isNotEmpty()
    }

    /** Atomically detaches every handle for renderer close; no later snapshot can double-delete it. */
    internal fun takeAllHandlesForDeletion(): List<GlObjectHandle> = lock.withLock {
        val handles = buildList {
            live.values.forEach { addAll(it) }
            queued.values.forEach { addAll(it) }
            allocations.values.forEach { addAll(it.handles) }
        }
        live.clear()
        queued.clear()
        textureByteSizes.clear()
        textureLeaseCounts.clear()
        unleasedOrder.clear()
        retiredPendingLastLease.clear()
        allocations.clear()
        allocationUnleasedOrder.clear()
        retiredThroughGeneration.clear()
        recencyClock = 0L
        advanceRegistryEpochLocked()
        handles
    }

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
    internal fun defer(key: ResourceKey, id: DeletionId): DeferredDeletion? = lock.withLock {
        if ((textureLeaseCounts[key] ?: 0) > 0) {
            retiredPendingLastLease += key
            return@withLock null
        }
        val handles = live.remove(key) ?: return@withLock null
        queued[id] = ArrayList(handles)
        textureByteSizes.remove(key)
        textureLeaseCounts.remove(key)
        unleasedOrder.remove(key)
        DeferredDeletion(id = id, resourceKey = key)
    }

    internal fun takeQueued(id: DeletionId): List<GlObjectHandle> =
        lock.withLock { queued.remove(id).orEmpty() }

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
        lock.withLock {
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
            allocations.clear()
            allocationUnleasedOrder.clear()
            recencyClock = 0L
            advanceRegistryEpochLocked()
        }
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
     *
     * Returns where that left residency. The loop has two exits and they mean opposite things: under
     * budget is eviction having done its job, while out of candidates with the total still over
     * budget is the thrash condition -- every leased byte was needed by the frame that is drawing, so
     * the next frame will re-decode and re-upload whatever this one had to drop. This method reports
     * that and does nothing about it, deliberately: it takes no diagnostic sink, because it is called
     * from GL-side bookkeeping and a sink belongs to the operation the consumer invoked.
     */
    /** This registry's budget-tracked residency as it stands, evicting nothing. */
    private fun residencyLocked(): GpuTextureResidency = GpuTextureResidency(
        residentBytes = textureByteSizes.values.sum() + allocations.values.sumOf { it.textureBytes },
        budgetBytes = residentTextureByteBudget,
    )

    private data class ReleaseResult(
        val residency: GpuTextureResidency,
        val handlesToDelete: List<GlObjectHandle>,
    )

    private fun evictOverBudgetPoolsLocked(): List<GlObjectHandle> {
        var textureBytes = textureByteSizes.values.sum() + allocations.values.sumOf { it.textureBytes }
        var bufferBytes = allocations.values.sumOf { it.bufferBytes }
        val handlesToDelete = mutableListOf<GlObjectHandle>()
        while (textureBytes > residentTextureByteBudget || bufferBytes > residentBufferByteBudget) {
            val bufferPressure = bufferBytes > residentBufferByteBudget
            val allocationCandidate = allocationUnleasedOrder.entries.firstOrNull { (identity) ->
                val candidate = allocations[identity] ?: return@firstOrNull false
                if (bufferPressure) candidate.bufferBytes > 0L else candidate.textureBytes > 0L
            }
            val legacyCandidate = if (bufferPressure) null else unleasedOrder.entries.firstOrNull()
            if (
                legacyCandidate != null &&
                (allocationCandidate == null || legacyCandidate.value <= allocationCandidate.value)
            ) {
                val key = legacyCandidate.key
                unleasedOrder.remove(key)
                textureBytes -= textureByteSizes.remove(key) ?: 0L
                textureLeaseCounts.remove(key)
                handlesToDelete += live.remove(key).orEmpty()
            } else {
                val identity = allocationCandidate?.key ?: break
                allocationUnleasedOrder.remove(identity)
                val victim = allocations.remove(identity) ?: continue
                textureBytes -= victim.textureBytes
                bufferBytes -= victim.bufferBytes
                handlesToDelete += victim.handles
            }
        }
        return handlesToDelete
    }

    private fun allocationResidencyLocked(): GpuResourceResidency = GpuResourceResidency(
        textureBytes = textureByteSizes.values.sum() + allocations.values.sumOf { it.textureBytes },
        textureBudgetBytes = residentTextureByteBudget,
        bufferBytes = allocations.values.sumOf { it.bufferBytes },
        bufferBudgetBytes = residentBufferByteBudget,
    )

    private data class AllocationReleaseResult(
        val residency: GpuResourceResidency,
        val handlesToDelete: List<GlObjectHandle>,
    )

    private data class RetirementResult(
        val publicResult: GpuGenerationRetirement,
        val handlesToDelete: List<GlObjectHandle>,
    )

    private fun advanceRegistryEpochLocked() {
        check(registryEpoch != Long.MAX_VALUE) { "GPU registry epoch space exhausted" }
        registryEpoch += 1L
    }

    private fun nextRecencyLocked(): Long {
        check(recencyClock != Long.MAX_VALUE) { "GPU registry recency space exhausted" }
        recencyClock += 1L
        return recencyClock
    }

    private fun ResourceKey.matches(selector: ResourceSelector): Boolean = when (selector) {
        ResourceSelector.All -> true
        is ResourceSelector.ByKind -> kind == selector.kind
        is ResourceSelector.ByClass -> resourceClass == selector.resourceClass
        is ResourceSelector.ByKey -> this == selector.key
    }
}

/**
 * How many budget-tracked texture bytes [GlObjectRegistry] holds, against the budget it holds them
 * under. A point-in-time reading handed back by [GlObjectRegistry.releaseLease], never a live view.
 *
 * [overBudget] uses the same strict `>` as the eviction loop's own condition, from the same two
 * values, so the two can never disagree about whether a residency is over budget -- a residency
 * exactly at the budget is not over it, and that off-by-one is the whole of the distinction.
 */
internal data class GpuTextureResidency(val residentBytes: Long, val budgetBytes: Long) {
    val overBudget: Boolean get() = residentBytes > budgetBytes
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
