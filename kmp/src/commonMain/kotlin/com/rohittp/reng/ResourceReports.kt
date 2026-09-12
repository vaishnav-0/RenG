package com.rohittp.reng

import com.rohittp.reng.internal.reportOrder

@ConsistentCopyVisibility
public data class ResourceKey internal constructor(
    public val kind: ResourceKind,
    public val stableId: String,
    public val resourceClass: ResourceClass?,
) {
    init {
        require(stableId.isLowercaseSha256()) { "stableId must be a lowercase SHA-256 digest" }
        require((kind == ResourceKind.EXTERNAL) == (resourceClass != null)) {
            "external keys require a resource class and other keys do not"
        }
    }

    override fun toString(): String = "ResourceKey(kind=$kind, resourceClass=$resourceClass)"
}

public sealed interface ResourceSelector {
    public data object All : ResourceSelector

    public data class ByKind(public val kind: ResourceKind) : ResourceSelector

    public data class ByClass(public val resourceClass: ResourceClass) : ResourceSelector

    public data class ByKey(public val key: ResourceKey) : ResourceSelector
}

@ConsistentCopyVisibility
public data class ResourceUsage internal constructor(
    public val rawBytes: Long,
    public val decodedCpuBytes: Long,
    public val knownGpuBytes: Long?,
    public val hasUnknownGpuBytes: Boolean,
) {
    init {
        require(rawBytes >= 0L) { "rawBytes must be non-negative" }
        require(decodedCpuBytes >= 0L) { "decodedCpuBytes must be non-negative" }
        require(knownGpuBytes == null || knownGpuBytes >= 0L) {
            "knownGpuBytes must be non-negative when present"
        }
        require(knownGpuBytes != null || hasUnknownGpuBytes) {
            "unknown GPU bytes must be declared when known GPU bytes are absent"
        }
    }
}

@ConsistentCopyVisibility
public data class ResourceReportEntry internal constructor(
    public val key: ResourceKey,
    public val residentGenerationCount: Int,
    public val retiredGenerationCount: Int,
    public val leaseCount: Int,
    public val reloadRequired: Boolean,
    public val usage: ResourceUsage,
) {
    init {
        require(residentGenerationCount >= 0) { "residentGenerationCount must be non-negative" }
        require(retiredGenerationCount >= 0) { "retiredGenerationCount must be non-negative" }
        require(leaseCount >= 0) { "leaseCount must be non-negative" }
    }
}

/**
 * What RenG's CPU-side resident cache is holding, what it is allowed to hold, and what its budget
 * has already cost (ADR 0047, ADR 0048).
 *
 * [evictedKeyCount] and [evictedBytes] are **cumulative since the renderer was created** and never
 * decrease. They cannot be per-key: an evicted key is removed from the cache outright, precisely so
 * that a later reload is not mistaken for a reload after an explicit `freeResources` -- see ADR
 * 0047. Read a difference between two reports to learn whether the budget is currently biting; read
 * a single non-zero value to learn that something has left without the consumer asking.
 *
 * Every figure here describes the CPU cache alone -- the compiled style, sticker and geometry
 * images, model GLBs. It is not a whole-renderer memory total: rendered basemap tiles are never
 * installed in that cache, GPU bytes are reported per entry as [ResourceUsage.knownGpuBytes], and
 * raw tile pixels in flight are governed by `ResourceLimits.maximumInFlightRawBasemapTileBytes`.
 */
@ConsistentCopyVisibility
public data class ResourceResidency internal constructor(
    /**
     * Bytes the cache holds right now, across every key it has -- **not** only those matching the
     * report's selector, because the budget those bytes are measured against governs all of them.
     * This is why the figure is here and not derivable by summing [ResourceReport.entries].
     */
    public val residentBytes: Long,
    /** `ResourceLimits.maximumResidentCpuResourceBytes`, repeated so [residentBytes] can be judged. */
    public val budgetBytes: Long,
    /** Keys evicted for the budget since this renderer was created. Monotonic. */
    public val evictedKeyCount: Long,
    /** Bytes reclaimed by those evictions. Monotonic. */
    public val evictedBytes: Long,
) {
    init {
        require(residentBytes >= 0L) { "residentBytes must be non-negative" }
        require(budgetBytes >= 0L) { "budgetBytes must be non-negative" }
        require(evictedKeyCount >= 0L) { "evictedKeyCount must be non-negative" }
        require(evictedBytes >= 0L) { "evictedBytes must be non-negative" }
    }
}

public class ResourceReport internal constructor(
    entries: List<ResourceReportEntry>,
    public val totals: ResourceUsage,
    /**
     * The CPU resident cache's budget and what it has already reclaimed. Named for its pool rather
     * than called `residency`, because it describes one of this renderer's memory pools and reading
     * it as the whole is the mistake ADR 0048 exists to prevent.
     */
    public val cpuResidency: ResourceResidency,
) {
    private val entrySnapshot: List<ResourceReportEntry> = entries.sortedWith(resourceReportEntryComparator)

    public val entries: List<ResourceReportEntry>
        get() = ArrayList(entrySnapshot)

    override fun equals(other: Any?): Boolean =
        other is ResourceReport &&
            entrySnapshot == other.entrySnapshot &&
            totals == other.totals &&
            cpuResidency == other.cpuResidency

    override fun hashCode(): Int =
        31 * (31 * entrySnapshot.hashCode() + totals.hashCode()) + cpuResidency.hashCode()

    override fun toString(): String =
        "ResourceReport(entries=$entrySnapshot, totals=$totals, cpuResidency=$cpuResidency)"
}

@ConsistentCopyVisibility
public data class ResourceFreeResult internal constructor(
    public val matchedKeys: Int,
    public val fullyFreedKeys: Int,
    public val deferredKeys: Int,
    public val alreadyFreeKeys: Int,
) {
    init {
        require(matchedKeys >= 0) { "matchedKeys must be non-negative" }
        require(fullyFreedKeys >= 0) { "fullyFreedKeys must be non-negative" }
        require(deferredKeys >= 0) { "deferredKeys must be non-negative" }
        require(alreadyFreeKeys >= 0) { "alreadyFreeKeys must be non-negative" }
        require(
            matchedKeys.toLong() ==
                fullyFreedKeys.toLong() + deferredKeys.toLong() + alreadyFreeKeys.toLong(),
        ) { "free result categories must sum to matchedKeys" }
    }
}

private val resourceReportEntryComparator: Comparator<ResourceReportEntry> =
    compareBy<ResourceReportEntry>(
        { it.key.kind.reportOrder },
        { it.key.resourceClass?.reportOrder ?: -1 },
        { it.key.stableId },
    )

private fun String.isLowercaseSha256(): Boolean =
    length == sha256HexLength && all { it in '0'..'9' || it in 'a'..'f' }

private const val sha256HexLength: Int = 64
