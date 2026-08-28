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
        }
    }
}

public fun interface DiagnosticSink {
    public fun emit(diagnostic: Diagnostic)

    public companion object {
        public val None: DiagnosticSink = DiagnosticSink { }
    }
}
