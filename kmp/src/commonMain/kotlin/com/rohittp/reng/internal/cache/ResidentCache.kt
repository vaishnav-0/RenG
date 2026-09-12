package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceFreeResult
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceReport
import com.rohittp.reng.ResourceReportEntry
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.ResourceUsage
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.internal.GpuByteAccount
import com.rohittp.reng.internal.image.DecodedImage
import kotlinx.coroutines.sync.Mutex

/**
 * One resident copy of a [ResourceKey]'s raw bytes and, for image classes, its decoded pixels. A
 * generation is an identity, not a value: [ResidentCache.install] never interns by content, so two
 * installs of byte-identical bytes still produce two distinct, independently-leased generations — see
 * [ResidentCache] for why a retired generation is never resurrected. Raw bytes are retained for as long
 * as the generation itself is resident, never dropped after decode, because Cycle B's `NORMAL` rules use
 * a stale resident as a `304` baseline.
 */
internal class ResidentGeneration(
    val key: ResourceKey,
    val stored: StoredRawResource,
    val decoded: DecodedImage?,
) {
    var leaseCount: Int = 0
        private set

    /**
     * What this generation costs the byte budget (ADR 0047), read once at construction.
     *
     * `byteSnapshot` is the internal no-copy accessor, so `.size` is a field read rather than the
     * array copy `bytes` would make — this is computed for every install and must not be the
     * expensive one. The decoded half is always zero in production today, because every install
     * passes `decoded = null`; it is included so the figure stays right the day one does not.
     */
    val byteSize: Long =
        stored.byteSnapshot.size.toLong() + (decoded?.byteCount ?: 0).toLong()

    fun addLease() {
        leaseCount += 1
    }

    fun removeLease() {
        check(leaseCount > 0) { "cannot release a lease from a generation with no outstanding lease" }
        leaseCount -= 1
    }
}

/**
 * One outstanding claim on a [ResidentGeneration] that keeps it resident even after it is superseded by
 * a fresh install or its key is freed. Single-use: a lease token is consumed by its one matching
 * [ResidentCache.releaseLease] call, and releasing it again is a caller error rather than a silent no-op.
 */
internal class Lease(val generation: ResidentGeneration) {
    private var released: Boolean = false

    fun markReleased() {
        require(!released) { "a lease cannot be released more than once" }
        released = true
    }
}

/**
 * Every generation this cache holds for one [ResourceKey]: at most one [current] generation plus zero or
 * more [retired] generations kept alive only because a lease taken before they were superseded or freed
 * has not yet been released. [freed] is the reload marker: true exactly while the key has no current
 * generation because of an explicit [ResidentCache.free], and cleared by the next [ResidentCache.install].
 */
private class KeyEntry {
    var current: ResidentGeneration? = null
    val retired: MutableList<ResidentGeneration> = mutableListOf()
    var freed: Boolean = false
}

/**
 * The resident cache: one entry per [ResourceKey] holding generations, leases, and a reload marker.
 *
 * Exactly one generation per key is `current`; superseding it (a fresh [install]) or freeing its key
 * retires it. A retired generation with no outstanding lease is dropped immediately, and since ADR
 * 0047 an **unleased current** generation is evicted too, least-recently-used first, once
 * [residentByteBudget] is exceeded — a leased one never is, at any size, because a budget bounds what
 * may stay resident rather than what must go. An evicted key is removed outright and is never marked
 * `freed`: that marker means the consumer asked, and [wasFreed] turns it into a diagnostic that would
 * then blame them for this cache's own decision. [free] retires every
 * generation for a matched key, marks the key `freed` (the reload marker [wasFreed] answers), deletes
 * every unleased generation, and reports the rest deferred; a key with no current generation and no
 * retired generation counts as already free. Accessing a freed key never fails here: the next [install]
 * simply installs a fresh generation and clears the marker, because freeing is never an error the caller
 * must recover from.
 *
 * This class carries the renderer mutex, because its per-key state is exactly what that mutex exists to
 * guard: every public method locks for its own state transition only, and never across an adapter call, a
 * decode, or a parse — none of which this cache ever performs itself. [free] and [report] share the same
 * locked snapshot, which is what makes the free/release race well defined: whichever call locks first
 * decides the outcome, so a free that wins reports its generation deferred and a release that wins lets
 * the following free see nothing left to defer.
 */
internal class ResidentCache(
    /**
     * The byte budget unleased resources are evicted down to (ADR 0047).
     *
     * Defaults to `Long.MAX_VALUE`, a cache that never evicts — which is what every test constructing
     * this without an argument gets, and what every release before ADR 0047 behaved like.
     */
    private val residentByteBudget: Long = Long.MAX_VALUE,
) {
    private val mutex = Mutex()
    private val entries: MutableMap<ResourceKey, KeyEntry> = mutableMapOf()

    /**
     * Least-recently-used first, holding exactly the keys [entries] holds.
     *
     * A `LinkedHashMap` used as an ordered set, the shape `GlObjectRegistry.unleasedOrder` already
     * uses for GPU textures. Recency is bumped by removing and re-inserting — that is what moves a
     * key to the end, and it is why [entries] cannot carry this order itself: `getOrPut` on a plain
     * map does not reorder on a hit.
     */
    private val recency: LinkedHashMap<ResourceKey, Unit> = LinkedHashMap()

    /**
     * Bytes of every generation [entries] holds, maintained on install and eviction rather than
     * summed on demand.
     *
     * Summed would mean walking every generation of every key on every install, which is the cost
     * [report] already pays once per `queryResources` and must not pay once per resource per frame.
     */
    private var residentBytes: Long = 0L

    fun current(key: ResourceKey): ResidentGeneration? = locked {
        val generation = entries[key]?.current
        if (generation != null) touch(key)
        generation
    }

    fun install(
        key: ResourceKey,
        stored: StoredRawResource,
        decoded: DecodedImage?,
    ): ResidentGeneration = locked {
        val entry = entries.getOrPut(key) { KeyEntry() }
        retireCurrent(entry)
        val generation = ResidentGeneration(key = key, stored = stored, decoded = decoded)
        entry.current = generation
        entry.freed = false
        residentBytes += generation.byteSize
        touch(key)
        evictOverBudget()
        generation
    }

    fun takeLease(generation: ResidentGeneration): Lease = locked {
        generation.addLease()
        Lease(generation)
    }

    /**
     * Atomically installs a fresh generation for [key] and takes the caller's own lease on it in one
     * locked step. This exists because a visibility install is one conceptual action — "install the
     * generation and take the owner's lease" — that a separate [install] then [takeLease] pair cannot
     * deliver under concurrency: a freshly installed generation sits at `leaseCount == 0` in the gap
     * between the two calls, where a racing [free] can retire it there — dropping it from this cache's
     * own bookkeeping entirely, since a zero-lease generation is never kept in [KeyEntry.retired] — before
     * this caller's [takeLease] ever runs. [takeLease] itself never rejects a stale generation reference
     * (it mutates the object it is given directly, by design — see [ResidentGeneration]'s reference
     * identity), so the failure mode here is not a crash: it is a lease that is taken successfully but on
     * a generation [report] and a subsequent [free] can no longer see at all, because dropping already
     * erased every trace of it from [entries]. Locking both steps together closes that gap entirely: no
     * racing [free] can ever observe this generation with a zero lease count, so it is always retired
     * (deferred), never dropped.
     */
    fun installAndTakeLease(
        key: ResourceKey,
        stored: StoredRawResource,
        decoded: DecodedImage?,
    ): Lease = locked {
        val entry = entries.getOrPut(key) { KeyEntry() }
        retireCurrent(entry)
        val generation = ResidentGeneration(key = key, stored = stored, decoded = decoded)
        generation.addLease()
        entry.current = generation
        entry.freed = false
        residentBytes += generation.byteSize
        touch(key)
        // Safe to sweep here: this generation already holds the caller's lease, so it can never be
        // its own victim however far over budget the install pushed the cache.
        evictOverBudget()
        Lease(generation)
    }

    /**
     * Atomically observes the current generation for [key] and takes the caller's lease on it in one
     * locked step, or returns `null` if there is no current generation. Closes the same class of gap as
     * [installAndTakeLease], for a caller re-leasing an already-resident generation rather than installing
     * a new one: a separate [current] then [takeLease] pair could observe a generation that a racing
     * [free] has already dropped from [entries] by the time [takeLease] runs — which, unlike the
     * [installAndTakeLease] gap, is not a bug to route around but a genuine race outcome this method
     * reports honestly as "nothing was resident to lease" rather than leasing a generation this cache no
     * longer tracks.
     */
    fun observeAndTakeLease(key: ResourceKey): Lease? = locked {
        val generation = entries[key]?.current ?: return@locked null
        generation.addLease()
        touch(key)
        Lease(generation)
    }

    fun releaseLease(lease: Lease): Unit = locked {
        lease.markReleased()
        val generation = lease.generation
        generation.removeLease()
        if (generation.leaseCount == 0) {
            val removed = entries[generation.key]?.retired?.remove(generation) == true
            if (removed) residentBytes -= generation.byteSize
        }
    }

    fun free(selector: ResourceSelector): ResourceFreeResult = locked {
        var matched = 0
        var fullyFreed = 0
        var deferred = 0
        var alreadyFree = 0
        entries.forEach { (key, entry) ->
            if (!key.matches(selector)) return@forEach
            matched += 1
            if (entry.freed && entry.current == null && entry.retired.isEmpty()) {
                alreadyFree += 1
                return@forEach
            }
            entry.freed = true
            retireCurrent(entry)
            if (entry.retired.isEmpty()) fullyFreed += 1 else deferred += 1
        }
        ResourceFreeResult(
            matchedKeys = matched,
            fullyFreedKeys = fullyFreed,
            deferredKeys = deferred,
            alreadyFreeKeys = alreadyFree,
        )
    }

    /**
     * The point-in-time account of every key matching [selector], with each key's GPU bytes supplied
     * by [gpuBytes] rather than known here.
     *
     * A lookup rather than a registry, deliberately: this class owns raw bytes and decoded pixels and
     * has never known what a GL texture is, so the alternative — handing it the GL object registry —
     * would put the whole GPU layer inside the CPU cache to answer two fields. [GpuByteAccount] is
     * the seam, and it names no GL concept.
     *
     * [gpuBytes] is called under this cache's own lock, which is safe for exactly the reason the lock
     * holds at all: like everything else in a [locked] block it must be a synchronous handful of
     * reads, never an adapter call, a decode or a parse. The GL registry's answer is a map lookup.
     */
    fun report(selector: ResourceSelector, gpuBytes: (ResourceKey) -> GpuByteAccount): ResourceReport = locked {
        val reportEntries = entries
            .filterKeys { it.matches(selector) }
            .map { (key, entry) -> entry.toReportEntry(key, gpuBytes(key)) }
        ResourceReport(entries = reportEntries, totals = reportEntries.totalUsage())
    }

    fun wasFreed(key: ResourceKey): Boolean = locked {
        entries[key]?.freed ?: false
    }

    fun closeAll(): Unit = locked {
        entries.clear()
        recency.clear()
        residentBytes = 0L
    }

    /**
     * Moves the key's current generation, if any, out of the `current` slot: retained in [KeyEntry.retired]
     * if it still has an outstanding lease, dropped immediately otherwise. Shared by [install] (supersession)
     * and [free] (retirement) — the two ways a current generation stops being current.
     */
    private fun retireCurrent(entry: KeyEntry) {
        val superseded = entry.current ?: return
        entry.current = null
        if (superseded.leaseCount > 0) {
            entry.retired += superseded
        } else {
            // Dropped entirely, so its bytes leave with it. A retired-but-leased generation stays
            // counted, because it is still held.
            residentBytes -= superseded.byteSize
        }
    }

    /**
     * Moves [key] to the most-recently-used end of [recency], and holds the invariant that [recency]
     * never names a key [entries] has dropped: a key with no entry is not re-inserted.
     */
    private fun touch(key: ResourceKey) {
        if (!entries.containsKey(key)) return
        recency.remove(key)
        recency[key] = Unit
    }

    /**
     * Evicts least-recently-used keys until [residentBytes] is within [residentByteBudget].
     *
     * A key is a candidate only when its current generation holds no lease and it has no retired
     * generations — a retired generation exists only because something leases it, so such a key is in
     * use by definition. A leased key is skipped rather than stopping the sweep, so one live frame
     * cannot pin the whole cache above its budget.
     *
     * The evicted key is **removed from [entries] outright and never marked `freed`** (ADR 0047):
     * `freed` means the consumer asked, and `wasFreed` turns it into a diagnostic blaming them for a
     * decision this cache made on its own.
     */
    private fun evictOverBudget() {
        if (residentBytes <= residentByteBudget) return
        val candidates = recency.keys.toList()
        for (key in candidates) {
            if (residentBytes <= residentByteBudget) return
            val entry = entries[key] ?: continue
            val generation = entry.current
            if (generation == null || generation.leaseCount > 0 || entry.retired.isNotEmpty()) continue
            residentBytes -= generation.byteSize
            entries.remove(key)
            recency.remove(key)
        }
    }

    private fun ResourceKey.matches(selector: ResourceSelector): Boolean = when (selector) {
        is ResourceSelector.All -> true
        is ResourceSelector.ByKind -> kind == selector.kind
        is ResourceSelector.ByClass -> resourceClass == selector.resourceClass
        is ResourceSelector.ByKey -> this == selector.key
    }

    private fun KeyEntry.toReportEntry(key: ResourceKey, gpu: GpuByteAccount): ResourceReportEntry {
        val resident = listOfNotNull(current) + retired
        return ResourceReportEntry(
            key = key,
            residentGenerationCount = resident.size,
            retiredGenerationCount = retired.size,
            leaseCount = resident.sumOf { it.leaseCount },
            reloadRequired = freed,
            usage = ResourceUsage(
                rawBytes = resident.sumOf { it.stored.byteSnapshot.size.toLong() },
                decodedCpuBytes = resident.sumOf { (it.decoded?.byteCount ?: 0).toLong() },
                knownGpuBytes = gpu.knownBytes,
                hasUnknownGpuBytes = gpu.hasUnknownBytes,
            ),
        )
    }

    /**
     * Sums usage across matched entries. The two GPU fields aggregate differently on purpose: an
     * entry that knows nothing contributes nothing to the known sum but does set the flag, so a total
     * of "1 MiB known" beside `hasUnknownGpuBytes = true` reads as "at least this much, and there is
     * more nobody counted" — CONTEXT.md's rule that when known and unknown allocations coexist the
     * known sum stays present and the flag goes up. Dropping unknown entries from the sum silently
     * would be the same lie one level up.
     */
    private fun List<ResourceReportEntry>.totalUsage(): ResourceUsage = ResourceUsage(
        rawBytes = sumOf { it.usage.rawBytes },
        decodedCpuBytes = sumOf { it.usage.decodedCpuBytes },
        knownGpuBytes = sumOf { it.usage.knownGpuBytes ?: 0L },
        hasUnknownGpuBytes = any { it.usage.hasUnknownGpuBytes },
    )

    /**
     * Runs [block] as this cache's one state transition at a time. Held only across the synchronous
     * bookkeeping in [block] — never across an adapter call, a decode, or a parse, none of which any
     * [ResidentCache] method performs. [Mutex.tryLock] and [Mutex.unlock] are safe to call from ordinary,
     * non-suspending code and across real threads, which is what lets [free] and [releaseLease] race from
     * separate coroutines and still linearize at this exact boundary.
     */
    private inline fun <T> locked(block: () -> T): T {
        while (!mutex.tryLock()) {
            // Uncontended in practice: every critical section here is a few field reads/writes.
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
