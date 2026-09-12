package com.rohittp.reng.internal.metrics

import com.rohittp.reng.MetricReport
import com.rohittp.reng.RenGMetricName
import com.rohittp.rentile.MetricName
import com.rohittp.rentile.MetricsSink
import com.rohittp.rentile.RentileMetric
import kotlinx.coroutines.sync.Mutex

/**
 * The sink RenG installs in the engine, accumulating every metric it emits into RenG's own
 * vocabulary (ADR 0049).
 *
 * This type is the firewall for metrics, in the same sense `BasemapEngineHost` is the firewall for
 * resources: it is the only place in RenG that names `com.rohittp.rentile.MetricName`, and nothing
 * downstream of [snapshot] can tell which engine produced a number.
 *
 * Locked rather than lock-free because the engine records from whatever thread it happens to be
 * working on -- a preparation may resume anywhere -- and a `Map` mutated from two of them at once is
 * undefined on every target RenG ships. The critical section is one map read and one write, which is
 * why the same spin-on-`tryLock` shape `ResidentCache` uses is appropriate here too.
 */
internal class EngineMetricRecorder : MetricsSink {
    private val mutex = Mutex()
    private val totals: MutableMap<RenGMetricName, Long> = LinkedHashMap()

    override fun record(metric: RentileMetric) {
        val name = metric.name.toRenGName()
        locked {
            // Summed, which is right for both shapes the engine uses: the counters send 1 per
            // occurrence, the totals send bytes or nanoseconds. `resourceClass` and `tags` are
            // dropped here and never reach RenG's own types -- ADR 0049 argues why.
            totals[name] = (totals[name] ?: 0L) + metric.value
        }
    }

    /** Every total as it stands, as the public report. */
    fun snapshot(): MetricReport = locked { MetricReport(totals) }

    private inline fun <T> locked(block: () -> T): T {
        while (!mutex.tryLock()) {
            // Uncontended in practice: the critical section is one map read and one write.
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * The one translation from the engine's vocabulary into RenG's.
 *
 * The `else` is load-bearing and not laziness: an exhaustive `when` over another module's enum
 * throws `NoWhenBranchMatchedException` if a consumer's dependency resolution raises the engine
 * above the version RenG compiled against, which is a crash in a diagnostic path. Counting the
 * unrecognised name instead loses nothing silently. `engineMetricNamesAllTranslate` keeps the
 * compile-time signal at RenG's own gate: it walks `MetricName.entries` and fails on the next engine
 * bump that adds one.
 */
// Redundant against the engine RenG compiles against, and precisely not redundant against the one
// a consumer may resolve at runtime. Suppressed rather than removed: see the KDoc above.
@Suppress("REDUNDANT_ELSE_IN_WHEN")
internal fun MetricName.toRenGName(): RenGMetricName = when (this) {
    MetricName.RESOURCE_REQUEST -> RenGMetricName.ENGINE_RESOURCE_REQUESTS
    MetricName.RESOURCE_WIRE_BYTES -> RenGMetricName.ENGINE_RESOURCE_WIRE_BYTES
    MetricName.RESOURCE_DECODED_BYTES -> RenGMetricName.ENGINE_RESOURCE_DECODED_BYTES
    MetricName.RAW_CACHE_HIT -> RenGMetricName.ENGINE_STORE_HITS
    MetricName.RAW_CACHE_MISS -> RenGMetricName.ENGINE_STORE_MISSES
    MetricName.SINGLE_FLIGHT_JOIN -> RenGMetricName.ENGINE_SINGLE_FLIGHT_JOINS
    MetricName.WARM_ALREADY_IN_FLIGHT -> RenGMetricName.ENGINE_WARM_ALREADY_IN_FLIGHT
    MetricName.TILE_RESOURCE_SUBSTITUTED -> RenGMetricName.ENGINE_TILE_RESOURCE_SUBSTITUTIONS
    MetricName.TILE_EXACT_RECOVERED -> RenGMetricName.ENGINE_TILE_EXACT_RECOVERIES
    MetricName.TILE_RENDERED -> RenGMetricName.ENGINE_TILES_RENDERED
    MetricName.PNG_ENCODED_BYTES -> RenGMetricName.ENGINE_TILE_PNG_BYTES
    MetricName.TILE_DRAW_NANOS -> RenGMetricName.ENGINE_TILE_DRAW_NANOS
    MetricName.TILE_PNG_ENCODE_NANOS -> RenGMetricName.ENGINE_TILE_PNG_ENCODE_NANOS
    MetricName.BACKGROUND_REVALIDATION_STARTED -> RenGMetricName.ENGINE_REVALIDATIONS_STARTED
    MetricName.BACKGROUND_REVALIDATION_NOT_MODIFIED -> RenGMetricName.ENGINE_REVALIDATIONS_NOT_MODIFIED
    MetricName.BACKGROUND_REVALIDATION_REPLACED -> RenGMetricName.ENGINE_REVALIDATIONS_REPLACED
    MetricName.BACKGROUND_REVALIDATION_FAILED -> RenGMetricName.ENGINE_REVALIDATIONS_FAILED
    else -> RenGMetricName.ENGINE_METRICS_UNRECOGNISED
}
