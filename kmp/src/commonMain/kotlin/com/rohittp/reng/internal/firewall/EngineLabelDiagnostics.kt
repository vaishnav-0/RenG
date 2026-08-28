package com.rohittp.reng.internal.firewall

import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.internal.labelContentExcludedDiagnostic
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.DiagnosticCode as EngineDiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity as EngineDiagnosticSeverity

/**
 * The one place RenG reads something the engine said about a frame that did **not** fail (ADR 0036).
 *
 * [classifyEngineFailure] is this file's opposite number and its whole discipline is subtraction: an
 * engine *failure* is a thing RenG can describe in its own vocabulary, so nothing of the engine's own
 * account survives the crossing. Labels are the first case where the engine knows something RenG
 * cannot find out any other way and the frame prepared anyway -- a style whose text is Hebrew, Arabic,
 * or any of the Brahmic and South-East Asian abugidas draws no text at all, because the engine's
 * `internal/glyph/ScriptSupport.kt` performs no bidirectional reordering and no contextual joining.
 * RenG cannot fix that downstream; the only thing available is to stop being silent about it.
 *
 * **Exactly two values cross, and one of them is not the engine's.** The code is RenG's own single
 * aggregate; the severity is the most severe of the exclusions this acquisition reported, mapped onto
 * RenG's own three. Nothing else: not the script, not the layer, not the font stack, not the counts,
 * and above all nothing from `details`, which is free-form and can carry a signed url exactly as an
 * injected adapter's message can -- the rule ADR 0016 already sets. [labelContentExcludedDiagnostic]
 * cannot express any of it, which is where that guarantee actually lives.
 *
 * **Why not mirror the engine's own codes.** A consumer told `COMPLEX_SCRIPT_LABEL_EXCLUDED` rather
 * than "something was excluded" learns more, and ADR 0036 gives that up deliberately: RenG's public
 * `DiagnosticCode` would otherwise track Rentile release by release, gaining an ABI event every time
 * the engine added, renamed, split or retired an exclusion code. Engine data may cross this boundary;
 * engine vocabulary never does.
 */
internal fun reportLabelContentExclusions(
    engineDiagnostics: List<RenderDiagnostic>,
    sink: DiagnosticSink,
) {
    val severity = labelContentExclusionSeverity(engineDiagnostics) ?: return
    sink.emit(labelContentExcludedDiagnostic(severity))
}

/**
 * The severity one aggregate carries for [engineDiagnostics], or `null` when nothing in them says a
 * label was lost -- which is the ordinary case and emits nothing at all.
 *
 * The most severe of the exclusions, because an aggregate reporting `INFO` over an engine `WARNING`
 * under-reports and one reporting `WARNING` over two `INFO`s cries wolf.
 *
 * **A whole batch is one reading.** `LabelCandidateBatch.diagnostics` is the prepared style's own
 * diagnostics followed by the acquisition's per-layer ones, so a style-time exclusion and a
 * feature-time one arrive in the same list and are aggregated by the same rule; there is no second
 * source to consult and no per-layer grouping to preserve, that grouping being the engine's own
 * choice about how to batch its reporting rather than anything about RenG's frame.
 */
internal fun labelContentExclusionSeverity(
    engineDiagnostics: List<RenderDiagnostic>,
): DiagnosticSeverity? =
    engineDiagnostics
        .filter { isLabelContentExclusion(it.code) }
        .maxOfOrNull { rengSeverityOf(it.severity) }

/**
 * Whether an engine diagnostic means this frame has less label content in it than the style asked for.
 *
 * A `when` over the engine's own enum with no `else`, exactly as [classifyEngineFailure] switches on
 * `RentileErrorCode`: a code a future Rentile adds fails this file's compilation rather than being
 * silently assumed harmless, which for a *diagnostic* is the failure mode that would leave RenG quiet
 * about a new class of missing text.
 *
 * **The false branch is where the judgement is.** Three of its entries name a text exclusion and are
 * still not one:
 *
 *  - `TEXT_ONLY_LAYER_EXCLUDED` and `TEXT_COMPONENT_REMOVED_ICON_RETAINED` describe the engine's own
 *    raster tile, where it draws no text and never has. The label pipeline admits a text-bearing
 *    symbol layer through `isAuxiliaryLabelLayer`, which is independent of the classification that
 *    raises those two, so such a layer still contributes label candidates and RenG still draws them.
 *    Counting them would fire on essentially every labelled style in the corpus.
 *  - `TEXT_COUPLED_ICON_LAYER_EXCLUDED` is the same thing for the icon half of such a layer: the
 *    raster tile loses the icon layer, while a label's own paired icon is reported by
 *    `ICON_FEATURE_SKIPPED`, which is in the true branch.
 *
 * `UNSUPPORTED_RETAINED_CONSTRUCT` is the other one worth naming: it is the engine's only `ERROR`
 * diagnostic, and it accompanies a thrown `StylePreparationException`, so no batch carrying it ever
 * reaches a consumer -- that path is [classifyEngineFailure]'s, and it fails the frame.
 */
private fun isLabelContentExclusion(code: EngineDiagnosticCode): Boolean = when (code) {
    // Label content the style asked for and this frame does not have.
    EngineDiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED,
    EngineDiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT,
    EngineDiagnosticCode.LABEL_SOURCE_UNAVAILABLE,
    EngineDiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED,
    EngineDiagnosticCode.LABEL_FEATURE_SKIPPED,
    EngineDiagnosticCode.GLYPH_RANGE_UNAVAILABLE,
    EngineDiagnosticCode.ICON_FEATURE_SKIPPED,
    -> true

    // The engine's raster tile, its resource bookkeeping, and its style-preparation failures. None of
    // them says a label RenG would have drawn is missing.
    EngineDiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED,
    EngineDiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED,
    EngineDiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED,
    EngineDiagnosticCode.EMPTY_ICON_IMAGE_NO_DRAW,
    EngineDiagnosticCode.HIDDEN_LAYER_NO_DRAW,
    EngineDiagnosticCode.EXTRUSION_FLATTENED,
    EngineDiagnosticCode.RESOURCE_CACHE_HIT,
    EngineDiagnosticCode.RESOURCE_CACHE_MISS,
    EngineDiagnosticCode.TILE_RESOURCE_SUBSTITUTED,
    EngineDiagnosticCode.TILE_EXACT_RECOVERY_FAILED,
    EngineDiagnosticCode.RESOURCE_REVALIDATED,
    EngineDiagnosticCode.RASTER_PASSTHROUGH_USED,
    EngineDiagnosticCode.ROOT_BEHAVIOR_EXCLUDED,
    EngineDiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT,
    EngineDiagnosticCode.ICON_LAYER_SKIPPED_SOURCE_UNAVAILABLE,
    -> false
}

/**
 * The engine's severity read as one of RenG's own, which is what makes a severity *data* rather than
 * vocabulary: the three constants happen to share their names, and the mapping is still RenG's.
 *
 * `ERROR` maps down to `WARNING`, and that is the one place this is not the identity. An exclusion is
 * never a failure -- the frame prepared, and it drew -- while RenG's `ERROR` is what a failure's own
 * context diagnostic carries, so an aggregate at `ERROR` would say the frame broke when it did not.
 * No exclusion code in the engine's enum is documented at `ERROR` today, so the branch is a ceiling
 * on a future Rentile rather than a live path; [com.rohittp.reng.Diagnostic]'s `init` refuses the
 * value outright, which would otherwise turn an informational report into a thrown frame.
 */
private fun rengSeverityOf(severity: EngineDiagnosticSeverity): DiagnosticSeverity = when (severity) {
    EngineDiagnosticSeverity.INFO -> DiagnosticSeverity.INFO
    EngineDiagnosticSeverity.WARNING -> DiagnosticSeverity.WARNING
    EngineDiagnosticSeverity.ERROR -> DiagnosticSeverity.WARNING
}
