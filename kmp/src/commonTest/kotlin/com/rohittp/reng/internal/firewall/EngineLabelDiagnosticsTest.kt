package com.rohittp.reng.internal.firewall

import com.rohittp.reng.Diagnostic
import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.PipelineStage
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.TileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.rohittp.rentile.DiagnosticCode as EngineDiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity as EngineDiagnosticSeverity
import com.rohittp.rentile.PipelineStage as EnginePipelineStage

/**
 * ADR 0036: one aggregate diagnostic per `prepare` that saw any label exclusion, carrying a code and a
 * severity and nothing else.
 *
 * **Every multiplicity case here reports several exclusions.** A fixture producing exactly one cannot
 * tell "once per prepare" from "once per exclusion" -- both emit once -- so the batches below carry
 * four exclusions across three codes and two layers, which is also the shape a real acquisition
 * produces: the engine reports once per layer per code, over every requested tile.
 */
class EngineLabelDiagnosticsTest {
    @Test
    fun severalEngineExclusionsProduceExactlyOneAggregate() {
        val sink = RecordingSink()

        reportLabelContentExclusions(complexScriptBatch(), sink)

        assertEquals(1, sink.diagnostics.size, "one aggregate per prepare, not one per exclusion")
    }

    @Test
    fun theAggregateIsTheWholeShapeAndNotJustTheCode() {
        val sink = RecordingSink()

        reportLabelContentExclusions(complexScriptBatch(), sink)

        val emitted = sink.diagnostics.single()
        assertEquals(DiagnosticCode.LABEL_CONTENT_EXCLUDED, emitted.code)
        assertEquals(DiagnosticSeverity.INFO, emitted.severity)
        assertEquals(PipelineStage.LABEL_PREPARATION, emitted.stage)
        assertNull(emitted.fieldName)
        assertNull(emitted.resourceClass)
        assertNull(emitted.resourceKey)
        assertNull(emitted.statusCode)
        assertNull(emitted.limit)
        assertNull(emitted.actual)
        assertEquals(
            Diagnostic(
                code = DiagnosticCode.LABEL_CONTENT_EXCLUDED,
                severity = DiagnosticSeverity.INFO,
                stage = PipelineStage.LABEL_PREPARATION,
            ),
            emitted,
        )
    }

    @Test
    fun nothingIsEmittedWhenNoLabelContentWasLost() {
        val quiet = RecordingSink()
        val empty = RecordingSink()

        // Every code the engine reports about its own raster tile and its own resource bookkeeping.
        // A style whose text-only symbol layers are excluded from the *tile* still contributes label
        // candidates -- `isAuxiliaryLabelLayer` admits it independently -- so reporting these would
        // fire on nearly every labelled style in the corpus.
        reportLabelContentExclusions(
            listOf(
                engineDiagnostic(EngineDiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED),
                engineDiagnostic(EngineDiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED, EngineDiagnosticSeverity.WARNING),
                engineDiagnostic(EngineDiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED),
                engineDiagnostic(EngineDiagnosticCode.RESOURCE_CACHE_HIT),
            ),
            quiet,
        )
        reportLabelContentExclusions(emptyList(), empty)

        assertEquals(emptyList(), quiet.diagnostics)
        assertEquals(emptyList(), empty.diagnostics)
    }

    @Test
    fun severityIsTheMostSevereOfTheExclusionsAloneAndNotOfTheBatch() {
        // A skipped label icon is the engine's one WARNING among label exclusions; the complex-script
        // and feature-skip entries beside it are INFO. The aggregate takes the WARNING.
        val mixed = RecordingSink()
        reportLabelContentExclusions(complexScriptBatch() + engineDiagnostic(EngineDiagnosticCode.ICON_FEATURE_SKIPPED, EngineDiagnosticSeverity.WARNING), mixed)

        // The same WARNING carried by a diagnostic that is *not* a label exclusion must not raise the
        // aggregate: a maximum taken before the filter would pass every other case in this file.
        val filtered = RecordingSink()
        reportLabelContentExclusions(
            complexScriptBatch() + engineDiagnostic(EngineDiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE, EngineDiagnosticSeverity.WARNING),
            filtered,
        )

        assertEquals(DiagnosticSeverity.WARNING, mixed.diagnostics.single().severity)
        assertEquals(DiagnosticSeverity.INFO, filtered.diagnostics.single().severity)
    }

    @Test
    fun anEngineErrorSeverityIsReportedAsAWarningRatherThanThrowing() {
        val sink = RecordingSink()

        // No exclusion code is documented at ERROR today, so this is a ceiling on a future Rentile.
        // `Diagnostic`'s init refuses ERROR outright, which is why the mapping caps rather than
        // forwards: an aggregate that threw here would turn an informational report into a failed frame.
        reportLabelContentExclusions(
            listOf(engineDiagnostic(EngineDiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED, EngineDiagnosticSeverity.ERROR)),
            sink,
        )

        assertEquals(DiagnosticSeverity.WARNING, sink.diagnostics.single().severity)
    }

    @Test
    fun theEnginesMessageDetailsAndTilesNeverCrossTheBoundary() {
        val signedUrl = "https://tiles.example/secret.pbf?signature=deadbeefcafe&key=live-key"
        val layerIdDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val sink = RecordingSink()

        // The vacuity trap this case exists for: an engine diagnostic with an *empty* details map
        // proves nothing about forwarding. Every entry Rentile documents is present here, plus a
        // signed url in both the free-form `message` and `details`, which ADR 0016 forbids carrying.
        reportLabelContentExclusions(
            listOf(
                RenderDiagnostic(
                    code = EngineDiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED,
                    severity = EngineDiagnosticSeverity.INFO,
                    stage = EnginePipelineStage.RESOURCE_ACQUISITION,
                    message = "Arabic labels were excluded while acquiring $signedUrl",
                    details = mapOf(
                        "layerIndex" to "7",
                        "layerIdDigest" to layerIdDigest,
                        "excludedFeatures" to "412",
                        "resourceId" to signedUrl,
                    ),
                    affectedTiles = listOf(TileId(1, 0, 0)),
                ),
            ),
            sink,
        )

        val rendered = sink.diagnostics.single().toString()
        listOf(signedUrl, "signature=deadbeefcafe", "live-key", layerIdDigest, "412", "Arabic", "7").forEach { secret ->
            assertFalse(rendered.contains(secret), "the aggregate leaked \"$secret\": $rendered")
        }
        assertEquals(
            "Diagnostic(code=LABEL_CONTENT_EXCLUDED, severity=INFO, stage=LABEL_PREPARATION, " +
                "fieldName=null, resourceClass=null, resourceKey=null, statusCode=null, limit=null, actual=null)",
            rendered,
        )
    }

    @Test
    fun everyEngineDiagnosticCodeIsClassifiedAndTheLabelOnesAreTheExclusions() {
        val expected = mapOf(
            EngineDiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED to true,
            EngineDiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT to true,
            EngineDiagnosticCode.LABEL_SOURCE_UNAVAILABLE to true,
            EngineDiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED to true,
            EngineDiagnosticCode.LABEL_FEATURE_SKIPPED to true,
            EngineDiagnosticCode.GLYPH_RANGE_UNAVAILABLE to true,
            EngineDiagnosticCode.ICON_FEATURE_SKIPPED to true,
            EngineDiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED to false,
            EngineDiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED to false,
            EngineDiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED to false,
            EngineDiagnosticCode.EMPTY_ICON_IMAGE_NO_DRAW to false,
            EngineDiagnosticCode.HIDDEN_LAYER_NO_DRAW to false,
            EngineDiagnosticCode.EXTRUSION_FLATTENED to false,
            EngineDiagnosticCode.RESOURCE_CACHE_HIT to false,
            EngineDiagnosticCode.RESOURCE_CACHE_MISS to false,
            EngineDiagnosticCode.TILE_RESOURCE_SUBSTITUTED to false,
            EngineDiagnosticCode.TILE_EXACT_RECOVERY_FAILED to false,
            EngineDiagnosticCode.RESOURCE_REVALIDATED to false,
            EngineDiagnosticCode.RASTER_PASSTHROUGH_USED to false,
            EngineDiagnosticCode.ROOT_BEHAVIOR_EXCLUDED to false,
            EngineDiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT to false,
            EngineDiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE to false,
        )

        assertEquals(
            EngineDiagnosticCode.entries.toSet(),
            expected.keys,
            "every engine diagnostic code must be classified; a code Rentile adds must fail here",
        )
        EngineDiagnosticCode.entries.forEach { code ->
            val isExclusion = labelContentExclusionSeverity(listOf(engineDiagnostic(code))) != null
            assertEquals(expected.getValue(code), isExclusion, "$code is classified the wrong way")
        }
        assertTrue(expected.values.any { it } && expected.values.any { !it })
    }

    /**
     * Four exclusions across three codes and two layers, all INFO: the shape one acquisition of an
     * Arabic- or Devanagari-labelled style produces, since the engine reports once per layer per code
     * over every requested tile.
     */
    private fun complexScriptBatch(): List<RenderDiagnostic> = listOf(
        engineDiagnostic(EngineDiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED, layerIndex = 3),
        engineDiagnostic(EngineDiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED, layerIndex = 9),
        engineDiagnostic(EngineDiagnosticCode.LABEL_FEATURE_SKIPPED, layerIndex = 3),
        engineDiagnostic(EngineDiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT, layerIndex = 11),
    )

    private fun engineDiagnostic(
        code: EngineDiagnosticCode,
        severity: EngineDiagnosticSeverity = EngineDiagnosticSeverity.INFO,
        layerIndex: Int = 0,
    ): RenderDiagnostic = RenderDiagnostic(
        code = code,
        severity = severity,
        stage = EnginePipelineStage.RESOURCE_ACQUISITION,
        message = "engine prose that never crosses the boundary",
        details = mapOf("layerIndex" to layerIndex.toString()),
    )

    private class RecordingSink : DiagnosticSink {
        private val recorded: MutableList<Diagnostic> = mutableListOf()

        val diagnostics: List<Diagnostic> get() = ArrayList(recorded)

        override fun emit(diagnostic: Diagnostic) {
            recorded += diagnostic
        }
    }
}
