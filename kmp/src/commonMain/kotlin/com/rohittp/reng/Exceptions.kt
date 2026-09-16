package com.rohittp.reng

import com.rohittp.reng.internal.requireAllowedFailureContext

public enum class RenGErrorCode {
    INVALID_VALUE,
    RESOURCE_LIMIT_EXCEEDED,
    UNSUPPORTED_PROJECTION_MODE,
    UNSUPPORTED_ANCHORING_MODE,
    PREPARATION_ORDER_VIOLATION,
    PREPARATION_IN_PROGRESS,
    RENDERER_CLOSED,
    RENDER_CONTEXT_ADOPTION_REQUIRED,
    NO_CURRENT_RENDER_CONTEXT,
    DIFFERENT_CURRENT_RENDER_CONTEXT,
    UNSUPPORTED_RENDER_CONTEXT,
    FOREIGN_PREPARED_FRAME,
    PREPARED_FRAME_CLOSED,
    FOREIGN_RENDER_TARGET,
    STALE_RENDER_TARGET,
    INVALID_RENDER_TARGET,
    AMBIGUOUS_RESOURCE_ROUTE,
    RESOURCE_UNAVAILABLE,
    TRANSPORT_EXECUTION_FAILED,
    INVALID_TRANSPORT_RESPONSE,
    STORE_READ_FAILED,
    STORE_WRITE_FAILED,
    STORE_INTEGRITY_FAILED,
    RESOURCE_DECODE_FAILED,
    RESOURCE_PARSE_FAILED,
    UNSUPPORTED_RESOURCE_FEATURE,
    SHADER_COMPILE_FAILED,
    SHADER_LINK_FAILED,
    GPU_OPERATION_FAILED,
    IDENTITY_COLLISION,
    BASEMAP_RENDER_FAILED,
    UNROUTABLE_LABEL_SOURCE,

    /**
     * This call arrived on a different thread than the one that created the renderer or last adopted
     * its Render Context, and nothing declared the move (ADR 0056).
     *
     * A context current on one thread is not current on another, so this is either a migration the
     * consumer performed without telling RenG -- `adoptCurrentRenderContext()` on the new thread
     * declares it -- or the ordinary mistake of calling a renderer from the wrong thread, which
     * otherwise surfaces as a black frame or a driver crash rather than as a failure.
     *
     * Like every precondition in ADR 0015's family, renderer state is unchanged and the call can be
     * retried from the right thread.
     *
     * **Declared last, and that is an ABI decision**: appending leaves every existing constant's
     * ordinal where it is, the rule `ResourceClass.BASEMAP_GLYPH_RANGE` already follows.
     */
    RENDER_CONTEXT_THREAD_CHANGED,
}

public class RenGException internal constructor(
    public val code: RenGErrorCode,
    public val stage: PipelineStage,
    diagnostics: List<Diagnostic> = emptyList(),
) : RuntimeException("RenG failure: $code at $stage", null) {
    private val diagnosticSnapshot: List<Diagnostic>

    init {
        require(diagnostics.size <= 1) { "a failure has at most one diagnostic" }
        requireAllowedFailureContext(code, stage, diagnostics.singleOrNull())
        diagnosticSnapshot = ArrayList(diagnostics)
    }

    public val diagnostics: List<Diagnostic>
        get() = ArrayList(diagnosticSnapshot)
}
