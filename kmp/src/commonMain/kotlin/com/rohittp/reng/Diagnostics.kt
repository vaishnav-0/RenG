package com.rohittp.reng

import com.rohittp.reng.internal.isAllowedDiagnosticFieldName
import com.rohittp.reng.internal.isAllowedDiagnosticFieldStage

public enum class PipelineStage {
    CONFIGURATION,
    FRAME_PLANNING,
    FRAME_PREPARATION,
    RESOURCE_LOOKUP,
    STORE_READ,
    STORE_VALIDATION,
    TRANSPORT,
    TRANSPORT_VALIDATION,
    STORE_WRITE,
    RESOURCE_DECODING,
    RESOURCE_PARSING,
    SHADER_COMPILATION,
    GPU_RESOURCE,
    RENDER_TARGET,
    DRAW,
    RESOURCE_FREE,
    RENDERER_CLOSE,
    CONTEXT_ADOPTION,
    BASEMAP_RENDER,
    LABEL_PREPARATION,
}

public enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

public enum class DiagnosticCode {
    RESOURCE_RELOADED_AFTER_FREE,
    FAILURE_CONTEXT,
    BASEMAP_NOT_CONFIGURED,
    RESIDENT_GPU_TEXTURES_OVER_BUDGET,
    LABEL_CONTENT_EXCLUDED,
    TERRAIN_COVERAGE_INCOMPLETE,
    TERRAIN_UNAVAILABLE,

    /**
     * Some of this frame's ground was drawn from a resident ancestor tile rather than its own,
     * because the consumer's `maximumTilesRasterisedPerFrame` declined to rasterise it (ADR 0057).
     *
     * A frame that presented an ancestor is not the same artefact as one that did not, and a consumer
     * compositing a still or capturing a video frame needs to know which they have. Appended last, so
     * no existing constant's ordinal moves.
     */
    GROUND_PRESENTED_PROVISIONALLY,
}

@ConsistentCopyVisibility
public data class Diagnostic internal constructor(
    public val code: DiagnosticCode,
    public val severity: DiagnosticSeverity,
    public val stage: PipelineStage,
    public val fieldName: String? = null,
    public val resourceClass: ResourceClass? = null,
    public val resourceKey: ResourceKey? = null,
    public val statusCode: Int? = null,
    public val limit: Long? = null,
    public val actual: Long? = null,
) {
    init {
        require(fieldName == null || isAllowedDiagnosticFieldName(fieldName)) {
            "fieldName is not allowlisted"
        }
        require(fieldName == null || isAllowedDiagnosticFieldStage(fieldName, stage)) {
            "fieldName is not valid at this pipeline stage"
        }
        require(resourceClass == resourceKey?.resourceClass) {
            "resource class requires its established resource key"
        }
        require(statusCode == null || stage == PipelineStage.TRANSPORT_VALIDATION) {
            "statusCode is only valid during transport validation"
        }
        require((limit == null) == (actual == null)) {
            "limit and actual must be present together"
        }
        require(limit == null || (limit >= 0L && actual!! >= 0L)) {
            "limit and actual must be non-negative"
        }
        when (code) {
            DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE -> {
                require(severity == DiagnosticSeverity.WARNING) {
                    "reload diagnostics are warnings"
                }
                require(stage == PipelineStage.RESOURCE_LOOKUP) {
                    "reload diagnostics occur during resource lookup"
                }
                require(fieldName == null && resourceKey != null && statusCode == null && limit == null) {
                    "reload diagnostics contain only an established resource identity"
                }
            }

            DiagnosticCode.FAILURE_CONTEXT -> {
                require(severity == DiagnosticSeverity.ERROR) {
                    "failure context diagnostics are errors"
                }
            }

            DiagnosticCode.BASEMAP_NOT_CONFIGURED -> {
                require(severity == DiagnosticSeverity.WARNING) {
                    "basemap-not-configured diagnostics are warnings"
                }
                require(stage == PipelineStage.BASEMAP_RENDER) {
                    "basemap-not-configured diagnostics occur during basemap render"
                }
                require(
                    fieldName == null && resourceClass == null && resourceKey == null &&
                        statusCode == null && limit == null,
                ) { "basemap-not-configured diagnostics carry no further context" }
            }

            DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET -> {
                require(severity == DiagnosticSeverity.WARNING) {
                    "resident-GPU-texture-budget diagnostics are warnings"
                }
                require(stage == PipelineStage.DRAW) {
                    "resident-GPU-texture-budget diagnostics occur during draw"
                }
                require(fieldName == null && resourceClass == null && resourceKey == null && statusCode == null) {
                    "resident-GPU-texture-budget diagnostics name no single resource"
                }
                // The two numbers are the whole content, and the strict inequality is the claim: a
                // residency exactly at the budget is not over it, and a diagnostic saying otherwise
                // would be unconstructible rather than merely wrong.
                require(limit != null && actual!! > limit) {
                    "resident-GPU-texture-budget diagnostics carry resident bytes strictly above the budget"
                }
            }

            DiagnosticCode.LABEL_CONTENT_EXCLUDED -> {
                // ADR 0036, made structural rather than left to the emitting call site. Only a code
                // and a severity cross the engine boundary, so every other field is refused here:
                // the engine's `details` -- free-form, and able to carry a signed url exactly as an
                // injected adapter's message can -- has no field to arrive in, and neither has the
                // script, the layer, the font stack nor the feature count. A future call site that
                // decided to be more helpful is refused by this constructor rather than reviewed.
                require(severity != DiagnosticSeverity.ERROR) {
                    "label-content-excluded diagnostics are never errors"
                }
                require(stage == PipelineStage.LABEL_PREPARATION) {
                    "label-content-excluded diagnostics occur during label preparation"
                }
                require(
                    fieldName == null && resourceClass == null && resourceKey == null &&
                        statusCode == null && limit == null,
                ) { "label-content-excluded diagnostics carry a severity and nothing else" }
            }

            DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE -> {
                // ADR 0041. A warning rather than an error because the frame prepared and it drew:
                // some of its ground is flat where the style declared relief, which is a wrong
                // picture rather than a missing one, and that is the whole hazard the code exists to
                // announce.
                require(severity == DiagnosticSeverity.WARNING) {
                    "terrain-coverage diagnostics are warnings"
                }
                require(stage == PipelineStage.BASEMAP_RENDER) {
                    "terrain-coverage diagnostics occur during basemap render"
                }
                require(fieldName == null && resourceClass == null && resourceKey == null && statusCode == null) {
                    "terrain-coverage diagnostics name no single tile"
                }
                // The count is the whole content, and carrying it as the `actual` against a `limit`
                // of zero is what makes the two guarantees structural rather than left to the call
                // site: no tile is expected to draw flat when a style declares terrain, so a report
                // of a complete coverage is unconstructible rather than merely wrong.
                require(limit == 0L && actual!! > 0L) {
                    "terrain-coverage diagnostics carry a positive count of tiles that drew flat"
                }
            }

            DiagnosticCode.GROUND_PRESENTED_PROVISIONALLY -> {
                // ADR 0057. A warning, and for TERRAIN_COVERAGE_INCOMPLETE's reason: the frame
                // prepared and it drew, and some of its ground is coarser than the LOD it chose --
                // a wrong picture rather than a missing one, which is exactly what a consumer
                // capturing a still needs told.
                require(severity == DiagnosticSeverity.WARNING) {
                    "provisional-ground diagnostics are warnings"
                }
                require(stage == PipelineStage.DRAW) {
                    "provisional ground is decided during the draw that resolves it"
                }
                require(fieldName == null && resourceClass == null && resourceKey == null && statusCode == null) {
                    "provisional-ground diagnostics name no single tile"
                }
                // The same structural shape TERRAIN_COVERAGE_INCOMPLETE uses: a count against a
                // limit of zero, so a report that nothing was provisional is unconstructible rather
                // than merely pointless.
                require(limit == 0L && actual!! > 0L) {
                    "provisional-ground diagnostics carry a positive count of tiles drawn from an ancestor"
                }
            }

            DiagnosticCode.TERRAIN_UNAVAILABLE -> {
                require(severity == DiagnosticSeverity.WARNING) {
                    "terrain-unavailable diagnostics are warnings"
                }
                require(stage == PipelineStage.BASEMAP_RENDER) {
                    "terrain-unavailable diagnostics occur during basemap render"
                }
                // No count, and no identity. The whole ground drew flat, so a number would only
                // restate the code -- and the acquisition failure this follows names at most a
                // redacted resource RenG asked the engine for, which is not a resource the consumer
                // supplied and not one it can act on.
                require(
                    fieldName == null && resourceClass == null && resourceKey == null &&
                        statusCode == null && limit == null,
                ) { "terrain-unavailable diagnostics carry no further context" }
            }
        }
    }
}

public fun interface DiagnosticSink {
    public fun emit(diagnostic: Diagnostic)

    public companion object {
        public val None: DiagnosticSink = DiagnosticSink { }
    }
}
