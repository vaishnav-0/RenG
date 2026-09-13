package com.rohittp.reng.internal.metrics

import com.rohittp.reng.RenGMetricName
import com.rohittp.reng.RenGRenderPriority
import com.rohittp.rentile.MetricName
import com.rohittp.rentile.RenderPriority as EngineRenderPriority
import com.rohittp.rentile.RentileMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EngineMetricRecorderTest {
    @Test
    fun everyEngineMetricNameTranslatesIntoRenGsOwnVocabulary() {
        // The compile-time signal ADR 0049 trades away by keeping an `else` in the translation. An
        // engine that adds a constant fails here, at RenG's own gate and on the version bump that
        // introduced it, instead of silently landing in ENGINE_METRICS_UNRECOGNISED in the field.
        val untranslated = MetricName.entries.filter {
            it.toRenGName() == RenGMetricName.ENGINE_METRICS_UNRECOGNISED
        }
        assertEquals(emptyList(), untranslated, "engine metric names with no RenG name")

        // And distinctly: two engine names collapsing onto one RenG name would silently add
        // unrelated counters together.
        val translated = MetricName.entries.map { it.toRenGName() }
        assertEquals(translated.size, translated.toSet().size, "two engine names share a RenG name")
    }

    @Test
    fun repeatedRecordsOfOneNameAreSummed() {
        val recorder = EngineMetricRecorder()
        recorder.record(RentileMetric(MetricName.RAW_CACHE_HIT))
        recorder.record(RentileMetric(MetricName.RAW_CACHE_HIT))
        recorder.record(RentileMetric(MetricName.RESOURCE_WIRE_BYTES, value = 4_096L))
        recorder.record(RentileMetric(MetricName.RESOURCE_WIRE_BYTES, value = 2_048L))

        val report = recorder.snapshot()

        // Counts and totals are the same arithmetic: the engine sends 1 per occurrence for one and
        // bytes for the other, and summing is right for both.
        assertEquals(2L, report[RenGMetricName.ENGINE_STORE_HITS])
        assertEquals(6_144L, report[RenGMetricName.ENGINE_RESOURCE_WIRE_BYTES])
    }

    @Test
    fun aNameNeverRecordedReadsAsZeroWithoutAppearingInTheCounters() {
        val recorder = EngineMetricRecorder()
        recorder.record(RentileMetric(MetricName.TILE_RENDERED))

        val report = recorder.snapshot()

        assertEquals(0L, report[RenGMetricName.ENGINE_TILE_PNG_BYTES])
        assertEquals(setOf(RenGMetricName.ENGINE_TILES_RENDERED), report.counters.keys)
    }

    @Test
    fun aSnapshotIsDetachedFromTheRecorderThatProducedIt() {
        val recorder = EngineMetricRecorder()
        recorder.record(RentileMetric(MetricName.TILE_RENDERED))
        val taken = recorder.snapshot()

        recorder.record(RentileMetric(MetricName.TILE_RENDERED))

        // A running total read twice must give two answers, not one aliased map that changed under
        // the first reader -- the difference between two readings is the whole point (ADR 0049).
        assertEquals(1L, taken[RenGMetricName.ENGINE_TILES_RENDERED])
        assertEquals(2L, recorder.snapshot()[RenGMetricName.ENGINE_TILES_RENDERED])
        assertNotEquals(taken, recorder.snapshot())
    }

    @Test
    fun everyRenderPriorityTranslatesOntoADistinctEngineLane() {
        // ADR 0053. Unlike a metric name there is no "unrecognised" priority to answer with, so the
        // translation's `else` falls to the engine's normal lane -- which means a name silently
        // losing its mapping would degrade to NORMAL rather than fail. This is the case that notices.
        assertEquals(EngineRenderPriority.URGENT, RenGRenderPriority.URGENT.toEnginePriority())
        assertEquals(EngineRenderPriority.NORMAL, RenGRenderPriority.NORMAL.toEnginePriority())

        val lanes = RenGRenderPriority.entries.map { it.toEnginePriority() }
        assertEquals(lanes.size, lanes.toSet().size, "two RenG priorities must not share one lane")
        assertEquals(
            EngineRenderPriority.entries.size,
            RenGRenderPriority.entries.size,
            "RenG's priorities are the engine's, one for one",
        )
    }

    @Test
    fun theEngineSideOfTheFirewallIsTheOnlyPlaceThatNamesTheEnginesVocabulary() {
        // Not a tautology: this asserts the translation covers the full width of what the engine can
        // send, so no caller downstream ever has to ask what engine produced a number.
        assertTrue(MetricName.entries.size >= 17, "engine vocabulary shrank unexpectedly")
        assertEquals(
            MetricName.entries.size + 1,
            RenGMetricName.entries.size,
            "RenG's vocabulary is the engine's plus exactly ENGINE_METRICS_UNRECOGNISED",
        )
    }
}
