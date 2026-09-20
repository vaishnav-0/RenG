package com.rohittp.reng.internal.lifecycle

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.failure.FailureDescriptor
import kotlin.jvm.JvmInline

internal enum class RendererOwnerState {
    LIVE,
    AWAITING_CONTEXT_ADOPTION,
    CLOSED,
}

@JvmInline
internal value class DeletionId(val value: Long)

internal data class DeferredDeletion(
    val id: DeletionId,
    val resourceKey: ResourceKey?,
)

internal class GpuLedger(
    val hasLiveGpuObjects: Boolean,
    deferredDeletions: List<DeferredDeletion>,
) {
    private val deferredDeletionSnapshot: List<DeferredDeletion> = ArrayList(deferredDeletions)

    val deferredDeletions: List<DeferredDeletion>
        get() = ArrayList(deferredDeletionSnapshot)
}

internal data class RendererLifecycleSnapshot(
    val ownerState: RendererOwnerState,
    val contextGeneration: Long,
    val preparationActive: Boolean,
    val gpuLedger: GpuLedger,
)

internal sealed interface PreparedFrameFact {
    data object OwnedOpen : PreparedFrameFact

    data object OwnedClosed : PreparedFrameFact

    data object Foreign : PreparedFrameFact
}

internal sealed interface RenderTargetFact {
    data class OwnedCurrent(val framebufferName: FramebufferName) : RenderTargetFact

    data object Foreign : RenderTargetFact

    data object Stale : RenderTargetFact
}

internal enum class ExactContextFact {
    EXACT,
    NONE,
    DIFFERENT,
}

internal enum class AdoptionContextFact {
    SUPPORTED,
    NONE,
    UNSUPPORTED,
}

internal enum class FramebufferFact {
    COMPLETE,
    MISSING_OR_INCOMPLETE,
}

internal sealed interface RendererLifecycleOperation {
    data object BeginPreparation : RendererLifecycleOperation

    /** Internal completion boundary for the suspend invocation begun by [BeginPreparation]. */
    data object EndPreparation : RendererLifecycleOperation

    data object CancelPreparations : RendererLifecycleOperation

    data object ClearFrameHistory : RendererLifecycleOperation

    data class QueryResources(val selector: ResourceSelector) : RendererLifecycleOperation

    /**
     * Reading the engine's accumulated counters (ADR 0049). Gated exactly where [QueryResources]
     * is, and for the same reason: it takes nothing a preparation needs, and a closed renderer
     * should answer an empty report rather than a stale one.
     */
    data object QueryMetrics : RendererLifecycleOperation

    data class FreeResources(val selector: ResourceSelector) : RendererLifecycleOperation

    data object NotifyGpuObjectsGone : RendererLifecycleOperation

    data object AdoptCurrentRenderContext : RendererLifecycleOperation

    data class MintRenderTarget(val framebufferName: FramebufferName) : RendererLifecycleOperation

    data class Draw(
        val frame: PreparedFrameFact,
        val target: RenderTargetFact,
    ) : RendererLifecycleOperation

    data class ClosePreparedFrame(val frame: PreparedFrameFact) : RendererLifecycleOperation

    data object CloseRenderer : RendererLifecycleOperation
}

internal sealed interface RendererLifecycleAction {
    data object AwaitRenderCallQuiescence : RendererLifecycleAction

    data object ObserveExactCurrentContext : RendererLifecycleAction

    data object ObserveAdoptableCurrentContext : RendererLifecycleAction

    data class DeleteDeferred(val deletion: DeferredDeletion) : RendererLifecycleAction

    data class ValidateFramebuffer(val framebufferName: FramebufferName) : RendererLifecycleAction

    data object RequestPreparationCancellation : RendererLifecycleAction

    data class ExecutePermittedOperation(
        val operation: RendererLifecycleOperation,
    ) : RendererLifecycleAction
}

internal sealed interface RendererLifecycleCursor {
    val snapshot: RendererLifecycleSnapshot
    val operation: RendererLifecycleOperation

    data class AwaitingRenderCallQuiescence(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor

    data class AwaitingExactContext(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor

    data class AwaitingAdoptionContext(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation.AdoptCurrentRenderContext,
    ) : RendererLifecycleCursor

    data class AwaitingDeferredDeletion(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
        val deletionId: DeletionId,
    ) : RendererLifecycleCursor

    data class AwaitingFramebuffer(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
        val framebufferName: FramebufferName,
    ) : RendererLifecycleCursor

    data class AwaitingPreparationTermination(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor

    data class AwaitingPermittedOperation(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor
}

internal sealed interface RendererLifecycleObservation {
    data object RenderCallsQuiesced : RendererLifecycleObservation

    data class ExactContextObserved(val fact: ExactContextFact) : RendererLifecycleObservation

    data class AdoptionContextObserved(val fact: AdoptionContextFact) : RendererLifecycleObservation

    data class DeferredDeletionAcknowledged(
        val deletionId: DeletionId,
    ) : RendererLifecycleObservation

    data class DeferredDeletionFailed(
        val deletionId: DeletionId,
        val failure: FailureDescriptor,
    ) : RendererLifecycleObservation

    data class FramebufferObserved(val fact: FramebufferFact) : RendererLifecycleObservation

    data object PreparationTerminated : RendererLifecycleObservation

    data object PermittedOperationSucceeded : RendererLifecycleObservation

    data class PermittedOperationFailed(
        val failure: FailureDescriptor,
    ) : RendererLifecycleObservation
}

internal sealed interface RendererLifecycleOutcome {
    data object Succeeded : RendererLifecycleOutcome

    data object NoOp : RendererLifecycleOutcome

    data object EmptyResourceResult : RendererLifecycleOutcome

    data class Failed(val failure: FailureDescriptor) : RendererLifecycleOutcome
}

internal class RendererLifecycleTransition(
    val snapshot: RendererLifecycleSnapshot,
    actions: List<RendererLifecycleAction>,
    val cursor: RendererLifecycleCursor?,
    val outcome: RendererLifecycleOutcome?,
) {
    private val actionSnapshot: List<RendererLifecycleAction> = ArrayList(actions)

    init {
        require((cursor == null) != (outcome == null)) {
            "exactly one of cursor and outcome must be present"
        }
    }

    val actions: List<RendererLifecycleAction>
        get() = ArrayList(actionSnapshot)
}
