package com.rohittp.reng

/**
 * How urgently a preparation's basemap tiles should be rendered (ADR 0053).
 *
 * The lane this chooses is the **engine's**, not RenG's: the basemap engine maps this onto its own
 * internal resource gate, which is already ordering every fetch a preparation causes. RenG queues
 * nothing of its own — `prepare` still refuses a second concurrent caller rather than queueing it.
 *
 * The `RenG` prefix is here on the ground [RenGMetricName] sets out: the engine has a type of the
 * same bare name, both are in scope where the two are translated, and a reader must be able to tell
 * whose vocabulary they are holding.
 */
public enum class RenGRenderPriority {
    /**
     * Ahead of normal work in the engine's gate. For the one frame a consumer knows the user is
     * about to look at — the frame under a scrub head, not the batch queued behind it.
     */
    URGENT,

    /** The lane every RenG release before ADR 0053 used, and still the default. */
    NORMAL,
}

/**
 * RenG's own name for a counter the basemap engine keeps (ADR 0049).
 *
 * Deliberately **not** a typealias for the engine's own enum: the repository policy forbids an
 * engine type in RenG's ABI, and a typealias would make every constant the engine renames a
 * breaking change for RenG's consumers, decided by a release RenG does not cut. The `ENGINE_` prefix
 * is not redundant either -- it reserves this vocabulary's other half for counters RenG keeps
 * itself, and says at every call site which side of the firewall a number came from.
 *
 * Every constant is a running total since the renderer was created. The `_NANOS` ones are sums, not
 * means: divide by the matching count -- [ENGINE_TILES_RENDERED] for [ENGINE_TILE_DRAW_NANOS] -- if
 * a mean is what you want.
 */
public enum class RenGMetricName {
    /** Resources the engine asked RenG's firewall for, hit or miss. */
    ENGINE_RESOURCE_REQUESTS,

    /** Bytes the engine received over RenG's transport. */
    ENGINE_RESOURCE_WIRE_BYTES,

    /** Bytes the engine decoded from those it received. */
    ENGINE_RESOURCE_DECODED_BYTES,

    /** Requests the consumer's store answered. */
    ENGINE_STORE_HITS,

    /** Requests the consumer's store could not answer, so the transport had to. */
    ENGINE_STORE_MISSES,

    /** Requests that joined a fetch already in flight instead of starting a second one. */
    ENGINE_SINGLE_FLIGHT_JOINS,

    /** Warm requests that found their resource already being fetched. */
    ENGINE_WARM_ALREADY_IN_FLIGHT,

    /** Tiles drawn against a substituted resource rather than the exact one asked for. */
    ENGINE_TILE_RESOURCE_SUBSTITUTIONS,

    /** Tiles whose exact resource arrived after a substitution had already been drawn. */
    ENGINE_TILE_EXACT_RECOVERIES,

    /** Tiles the engine rasterised. */
    ENGINE_TILES_RENDERED,

    /**
     * Bytes of PNG the engine encoded.
     *
     * The direct measurement of ADR 0044: a frame taking the raw-pixel path encodes nothing, so this
     * staying at zero across a session is the evidence that path is being taken.
     */
    ENGINE_TILE_PNG_BYTES,

    /** Nanoseconds spent drawing tiles, summed. */
    ENGINE_TILE_DRAW_NANOS,

    /** Nanoseconds spent encoding tiles to PNG, summed. Zero wherever [ENGINE_TILE_PNG_BYTES] is. */
    ENGINE_TILE_PNG_ENCODE_NANOS,

    /** Background revalidations the engine began. */
    ENGINE_REVALIDATIONS_STARTED,

    /** Background revalidations that found the resource unchanged. */
    ENGINE_REVALIDATIONS_NOT_MODIFIED,

    /** Background revalidations that replaced the resource. */
    ENGINE_REVALIDATIONS_REPLACED,

    /** Background revalidations that failed. */
    ENGINE_REVALIDATIONS_FAILED,

    /**
     * Metrics the engine emitted that this version of RenG has no name for.
     *
     * Non-zero means the engine behind this renderer is newer than the RenG translating it. Nothing
     * was lost silently and nothing threw -- which is the whole reason this constant exists rather
     * than an exhaustive translation that would crash a consumer whose dependency resolution raised
     * the engine above the version RenG was built against (ADR 0049).
     *
     * **Declared last, and that is an ABI decision**, the same one `ResourceLimits` documents for
     * its trailing fields: appending a constant leaves every existing ordinal where it is.
     */
    ENGINE_METRICS_UNRECOGNISED,
}

/**
 * Every counter the basemap engine has reported to this renderer, as a running total (ADR 0049).
 *
 * Totals are cumulative since the renderer was created and never decrease, so a single reading says
 * only "this much has happened"; the useful signal is the difference between two readings, exactly
 * as it is for `ResourceReport.cpuResidency`'s eviction counters.
 */
public class MetricReport internal constructor(counters: Map<RenGMetricName, Long>) {
    private val snapshot: Map<RenGMetricName, Long> = LinkedHashMap(counters)

    /**
     * Every counter that has been reported at least once, in the order it was first seen. A name
     * absent here has never been reported, which [get] renders as the zero it means.
     */
    public val counters: Map<RenGMetricName, Long>
        get() = LinkedHashMap(snapshot)

    /** This counter's running total, or zero if the engine has never reported it. */
    public operator fun get(name: RenGMetricName): Long = snapshot[name] ?: 0L

    override fun equals(other: Any?): Boolean = other is MetricReport && snapshot == other.snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = "MetricReport(counters=$snapshot)"
}
