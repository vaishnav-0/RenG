package com.rohittp.reng.internal.lifecycle

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

internal object RendererLifecycleStateMachine {
    internal fun begin(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition {
        activePreparationFailure(snapshot, operation)?.let { return it }

        return when (snapshot.ownerState) {
            RendererOwnerState.LIVE -> beginLive(snapshot, operation)
            RendererOwnerState.AWAITING_CONTEXT_ADOPTION -> beginAwaitingAdoption(snapshot, operation)
            RendererOwnerState.CLOSED -> beginClosed(snapshot, operation)
        }
    }

    internal fun resume(
        cursor: RendererLifecycleCursor,
        observation: RendererLifecycleObservation,
    ): RendererLifecycleTransition = when (cursor) {
        is RendererLifecycleCursor.AwaitingRenderCallQuiescence -> {
            require(observation is RendererLifecycleObservation.RenderCallsQuiesced) {
                "render-call quiescence observation required"
            }
            finishGpuObjectLoss(cursor)
        }

        is RendererLifecycleCursor.AwaitingExactContext -> {
            require(observation is RendererLifecycleObservation.ExactContextObserved) {
                "exact-context observation required"
            }
            resumeExactContext(cursor, observation.fact)
        }

        is RendererLifecycleCursor.AwaitingAdoptionContext -> {
            require(observation is RendererLifecycleObservation.AdoptionContextObserved) {
                "adoption-context observation required"
            }
            resumeAdoption(cursor, observation.fact)
        }

        is RendererLifecycleCursor.AwaitingDeferredDeletion ->
            resumeDeferredDeletion(cursor, observation)

        is RendererLifecycleCursor.AwaitingFramebuffer -> {
            require(observation is RendererLifecycleObservation.FramebufferObserved) {
                "framebuffer observation required"
            }
            resumeFramebuffer(cursor, observation.fact)
        }

        is RendererLifecycleCursor.AwaitingPreparationTermination -> {
            require(observation is RendererLifecycleObservation.PreparationTerminated) {
                "preparation-termination observation required"
            }
            succeeded(cursor.snapshot.copy(preparationActive = false))
        }

        is RendererLifecycleCursor.AwaitingPermittedOperation ->
            resumePermittedOperation(cursor, observation)
    }

    private fun activePreparationFailure(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition? {
        if (!snapshot.preparationActive) return null

        val stage = when (operation) {
            RendererLifecycleOperation.BeginPreparation -> PipelineStage.FRAME_PREPARATION
            RendererLifecycleOperation.EndPreparation -> return null
            RendererLifecycleOperation.ClearFrameHistory -> PipelineStage.FRAME_PLANNING
            is RendererLifecycleOperation.FreeResources -> PipelineStage.RESOURCE_FREE
            RendererLifecycleOperation.CloseRenderer -> PipelineStage.RENDERER_CLOSE
            else -> return null
        }
        return failed(snapshot, RenGErrorCode.PREPARATION_IN_PROGRESS, stage)
    }

    private fun beginLive(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition = when (operation) {
        RendererLifecycleOperation.BeginPreparation -> executePermitted(
            snapshot.copy(preparationActive = true),
            operation,
        )

        RendererLifecycleOperation.EndPreparation -> endPreparation(snapshot)

        RendererLifecycleOperation.CancelPreparations -> beginCancellation(
            snapshot,
            RendererLifecycleOperation.CancelPreparations,
        )
        RendererLifecycleOperation.ClearFrameHistory,
        is RendererLifecycleOperation.QueryResources,
        RendererLifecycleOperation.QueryMetrics,
        -> executePermitted(snapshot, operation)

        is RendererLifecycleOperation.FreeResources ->
            beginContextDependentIfGpuObjectsExist(snapshot, operation)

        RendererLifecycleOperation.NotifyGpuObjectsGone -> awaitRenderCallQuiescence(
            snapshot,
            RendererLifecycleOperation.NotifyGpuObjectsGone,
        )
        RendererLifecycleOperation.AdoptCurrentRenderContext -> failed(
            snapshot,
            RenGErrorCode.INVALID_VALUE,
            PipelineStage.CONTEXT_ADOPTION,
        )

        is RendererLifecycleOperation.MintRenderTarget -> awaitExactContext(snapshot, operation)
        is RendererLifecycleOperation.Draw -> beginLiveDraw(snapshot, operation)
        is RendererLifecycleOperation.ClosePreparedFrame -> beginPreparedFrameClose(snapshot, operation)
        RendererLifecycleOperation.CloseRenderer ->
            beginContextDependentIfGpuObjectsExist(snapshot, operation)
    }

    private fun beginAwaitingAdoption(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition = when (operation) {
        RendererLifecycleOperation.BeginPreparation -> executePermitted(
            snapshot.copy(preparationActive = true),
            operation,
        )

        RendererLifecycleOperation.EndPreparation -> endPreparation(snapshot)

        RendererLifecycleOperation.CancelPreparations -> beginCancellation(
            snapshot,
            RendererLifecycleOperation.CancelPreparations,
        )
        RendererLifecycleOperation.ClearFrameHistory,
        is RendererLifecycleOperation.QueryResources,
        is RendererLifecycleOperation.FreeResources,
        RendererLifecycleOperation.QueryMetrics,
        -> executePermitted(snapshot, operation)

        RendererLifecycleOperation.NotifyGpuObjectsGone -> noOp(snapshot)
        RendererLifecycleOperation.AdoptCurrentRenderContext -> awaitAdoptionContext(
            snapshot,
            RendererLifecycleOperation.AdoptCurrentRenderContext,
        )
        is RendererLifecycleOperation.MintRenderTarget -> failed(
            snapshot,
            RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED,
            PipelineStage.RENDER_TARGET,
        )

        is RendererLifecycleOperation.Draw -> failed(
            snapshot,
            RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED,
            PipelineStage.DRAW,
        )

        is RendererLifecycleOperation.ClosePreparedFrame -> beginPreparedFrameClose(snapshot, operation)
        RendererLifecycleOperation.CloseRenderer -> executePermitted(snapshot, operation)
    }

    private fun beginClosed(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition = when (operation) {
        RendererLifecycleOperation.BeginPreparation -> failed(
            snapshot,
            RenGErrorCode.RENDERER_CLOSED,
            PipelineStage.FRAME_PREPARATION,
        )

        RendererLifecycleOperation.EndPreparation -> noOp(snapshot)

        RendererLifecycleOperation.CancelPreparations,
        RendererLifecycleOperation.NotifyGpuObjectsGone,
        is RendererLifecycleOperation.ClosePreparedFrame,
        RendererLifecycleOperation.CloseRenderer,
        -> noOp(snapshot)

        RendererLifecycleOperation.ClearFrameHistory -> failed(
            snapshot,
            RenGErrorCode.RENDERER_CLOSED,
            PipelineStage.FRAME_PLANNING,
        )

        is RendererLifecycleOperation.QueryResources,
        is RendererLifecycleOperation.FreeResources,
        RendererLifecycleOperation.QueryMetrics,
        -> emptyResourceResult(snapshot)

        RendererLifecycleOperation.AdoptCurrentRenderContext -> failed(
            snapshot,
            RenGErrorCode.RENDERER_CLOSED,
            PipelineStage.CONTEXT_ADOPTION,
        )

        is RendererLifecycleOperation.MintRenderTarget -> failed(
            snapshot,
            RenGErrorCode.RENDERER_CLOSED,
            PipelineStage.RENDER_TARGET,
        )

        is RendererLifecycleOperation.Draw -> failed(
            snapshot,
            RenGErrorCode.RENDERER_CLOSED,
            PipelineStage.DRAW,
        )
    }

    private fun beginCancellation(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation.CancelPreparations,
    ): RendererLifecycleTransition = if (snapshot.preparationActive) {
        transition(
            snapshot = snapshot,
            action = RendererLifecycleAction.RequestPreparationCancellation,
            cursor = RendererLifecycleCursor.AwaitingPreparationTermination(snapshot, operation),
        )
    } else {
        noOp(snapshot)
    }

    private fun endPreparation(
        snapshot: RendererLifecycleSnapshot,
    ): RendererLifecycleTransition = if (snapshot.preparationActive) {
        executePermitted(snapshot, RendererLifecycleOperation.EndPreparation)
    } else {
        noOp(snapshot)
    }

    private fun beginPreparedFrameClose(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation.ClosePreparedFrame,
    ): RendererLifecycleTransition = when (operation.frame) {
        PreparedFrameFact.OwnedOpen -> executePermitted(snapshot, operation)
        PreparedFrameFact.OwnedClosed,
        PreparedFrameFact.Foreign,
        -> noOp(snapshot)
    }

    private fun beginLiveDraw(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation.Draw,
    ): RendererLifecycleTransition {
        when (operation.frame) {
            PreparedFrameFact.Foreign -> return failed(
                snapshot,
                RenGErrorCode.FOREIGN_PREPARED_FRAME,
                PipelineStage.DRAW,
            )

            PreparedFrameFact.OwnedClosed -> return failed(
                snapshot,
                RenGErrorCode.PREPARED_FRAME_CLOSED,
                PipelineStage.DRAW,
            )

            PreparedFrameFact.OwnedOpen -> Unit
        }

        when (operation.target) {
            RenderTargetFact.Foreign -> return failed(
                snapshot,
                RenGErrorCode.FOREIGN_RENDER_TARGET,
                PipelineStage.RENDER_TARGET,
            )

            RenderTargetFact.Stale -> return failed(
                snapshot,
                RenGErrorCode.STALE_RENDER_TARGET,
                PipelineStage.RENDER_TARGET,
            )

            is RenderTargetFact.OwnedCurrent -> Unit
        }

        return awaitExactContext(snapshot, operation)
    }

    private fun beginContextDependentIfGpuObjectsExist(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition = if (
        snapshot.gpuLedger.hasLiveGpuObjects || snapshot.gpuLedger.deferredDeletions.isNotEmpty()
    ) {
        awaitExactContext(snapshot, operation)
    } else {
        executePermitted(snapshot, operation)
    }

    private fun finishGpuObjectLoss(
        cursor: RendererLifecycleCursor.AwaitingRenderCallQuiescence,
    ): RendererLifecycleTransition {
        require(cursor.operation is RendererLifecycleOperation.NotifyGpuObjectsGone) {
            "render-call quiescence is valid only for GPU object loss"
        }
        return executePermitted(
            cursor.snapshot.copy(
                ownerState = RendererOwnerState.AWAITING_CONTEXT_ADOPTION,
                contextGeneration = cursor.snapshot.contextGeneration + 1L,
                gpuLedger = GpuLedger(
                    hasLiveGpuObjects = false,
                    deferredDeletions = emptyList(),
                ),
            ),
            cursor.operation,
        )
    }

    private fun resumeExactContext(
        cursor: RendererLifecycleCursor.AwaitingExactContext,
        fact: ExactContextFact,
    ): RendererLifecycleTransition = when (fact) {
        ExactContextFact.EXACT -> afterExactContext(cursor.snapshot, cursor.operation)
        ExactContextFact.NONE -> failed(
            cursor.snapshot,
            RenGErrorCode.NO_CURRENT_RENDER_CONTEXT,
            exactContextStage(cursor.operation),
        )

        ExactContextFact.DIFFERENT -> failed(
            cursor.snapshot,
            RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT,
            exactContextStage(cursor.operation),
        )
    }

    private fun resumeAdoption(
        cursor: RendererLifecycleCursor.AwaitingAdoptionContext,
        fact: AdoptionContextFact,
    ): RendererLifecycleTransition = when (fact) {
        AdoptionContextFact.SUPPORTED -> succeeded(
            cursor.snapshot.copy(
                ownerState = RendererOwnerState.LIVE,
                gpuLedger = GpuLedger(
                    hasLiveGpuObjects = false,
                    deferredDeletions = emptyList(),
                ),
            ),
        )

        AdoptionContextFact.NONE -> failed(
            cursor.snapshot,
            RenGErrorCode.NO_CURRENT_RENDER_CONTEXT,
            PipelineStage.CONTEXT_ADOPTION,
        )

        AdoptionContextFact.UNSUPPORTED -> failed(
            cursor.snapshot,
            RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            PipelineStage.CONTEXT_ADOPTION,
        )
    }

    private fun resumeDeferredDeletion(
        cursor: RendererLifecycleCursor.AwaitingDeferredDeletion,
        observation: RendererLifecycleObservation,
    ): RendererLifecycleTransition {
        val queue = cursor.snapshot.gpuLedger.deferredDeletions
        require(queue.isNotEmpty() && queue.first().id == cursor.deletionId) {
            "deferred-deletion cursor must name the current queue entry"
        }

        return when (observation) {
            is RendererLifecycleObservation.DeferredDeletionAcknowledged -> {
                require(observation.deletionId == cursor.deletionId) {
                    "deferred-deletion acknowledgement id does not match"
                }
                val remaining = queue.drop(1)
                val advancedSnapshot = cursor.snapshot.copy(
                    gpuLedger = GpuLedger(
                        hasLiveGpuObjects = cursor.snapshot.gpuLedger.hasLiveGpuObjects,
                        deferredDeletions = remaining,
                    ),
                )
                if (remaining.isEmpty()) {
                    afterDeferredDeletions(advancedSnapshot, cursor.operation)
                } else {
                    awaitDeferredDeletion(advancedSnapshot, cursor.operation, remaining.first())
                }
            }

            is RendererLifecycleObservation.DeferredDeletionFailed -> {
                require(observation.deletionId == cursor.deletionId) {
                    "deferred-deletion failure id does not match"
                }
                failed(cursor.snapshot, observation.failure)
            }

            else -> throw IllegalArgumentException("deferred-deletion observation required")
        }
    }

    private fun resumeFramebuffer(
        cursor: RendererLifecycleCursor.AwaitingFramebuffer,
        fact: FramebufferFact,
    ): RendererLifecycleTransition = when (fact) {
        FramebufferFact.COMPLETE -> executePermitted(cursor.snapshot, cursor.operation)
        FramebufferFact.MISSING_OR_INCOMPLETE -> failed(
            snapshot = cursor.snapshot,
            failure = FailureDescriptor(
                code = RenGErrorCode.INVALID_RENDER_TARGET,
                stage = PipelineStage.RENDER_TARGET,
                diagnostic = failureContextDiagnostic(
                    stage = PipelineStage.RENDER_TARGET,
                    fieldName = DiagnosticField.RENDER_TARGET,
                ),
            ),
        )
    }

    private fun resumePermittedOperation(
        cursor: RendererLifecycleCursor.AwaitingPermittedOperation,
        observation: RendererLifecycleObservation,
    ): RendererLifecycleTransition = when (observation) {
        RendererLifecycleObservation.PermittedOperationSucceeded -> {
            val completedSnapshot = when (cursor.operation) {
                RendererLifecycleOperation.EndPreparation ->
                    cursor.snapshot.copy(preparationActive = false)

                RendererLifecycleOperation.CloseRenderer -> cursor.snapshot.copy(
                    ownerState = RendererOwnerState.CLOSED,
                    preparationActive = false,
                    gpuLedger = GpuLedger(
                        hasLiveGpuObjects = false,
                        deferredDeletions = emptyList(),
                    ),
                )

                else -> cursor.snapshot
            }
            succeeded(completedSnapshot)
        }

        is RendererLifecycleObservation.PermittedOperationFailed -> {
            val failedSnapshot = if (
                cursor.operation is RendererLifecycleOperation.BeginPreparation
            ) {
                cursor.snapshot.copy(preparationActive = false)
            } else {
                cursor.snapshot
            }
            failed(failedSnapshot, observation.failure)
        }

        else -> throw IllegalArgumentException("permitted-operation observation required")
    }

    private fun afterExactContext(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition {
        val firstDeletion = snapshot.gpuLedger.deferredDeletions.firstOrNull()
        return if (firstDeletion == null) {
            afterDeferredDeletions(snapshot, operation)
        } else {
            awaitDeferredDeletion(snapshot, operation, firstDeletion)
        }
    }

    private fun afterDeferredDeletions(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition = when (operation) {
        is RendererLifecycleOperation.MintRenderTarget ->
            awaitFramebuffer(snapshot, operation, operation.framebufferName)

        is RendererLifecycleOperation.Draw -> {
            val target = operation.target as RenderTargetFact.OwnedCurrent
            awaitFramebuffer(snapshot, operation, target.framebufferName)
        }

        else -> executePermitted(snapshot, operation)
    }

    private fun exactContextStage(operation: RendererLifecycleOperation): PipelineStage = when (operation) {
        is RendererLifecycleOperation.MintRenderTarget -> PipelineStage.RENDER_TARGET
        is RendererLifecycleOperation.Draw -> PipelineStage.DRAW
        is RendererLifecycleOperation.FreeResources -> PipelineStage.RESOURCE_FREE
        RendererLifecycleOperation.CloseRenderer -> PipelineStage.RENDERER_CLOSE
        else -> throw IllegalArgumentException("operation does not require an exact-context observation")
    }

    private fun awaitRenderCallQuiescence(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation.NotifyGpuObjectsGone,
    ): RendererLifecycleTransition = transition(
        snapshot = snapshot,
        action = RendererLifecycleAction.AwaitRenderCallQuiescence,
        cursor = RendererLifecycleCursor.AwaitingRenderCallQuiescence(snapshot, operation),
    )

    private fun awaitExactContext(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition = transition(
        snapshot = snapshot,
        action = RendererLifecycleAction.ObserveExactCurrentContext,
        cursor = RendererLifecycleCursor.AwaitingExactContext(snapshot, operation),
    )

    private fun awaitAdoptionContext(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation.AdoptCurrentRenderContext,
    ): RendererLifecycleTransition = transition(
        snapshot = snapshot,
        action = RendererLifecycleAction.ObserveAdoptableCurrentContext,
        cursor = RendererLifecycleCursor.AwaitingAdoptionContext(snapshot, operation),
    )

    private fun awaitDeferredDeletion(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
        deletion: DeferredDeletion,
    ): RendererLifecycleTransition = transition(
        snapshot = snapshot,
        action = RendererLifecycleAction.DeleteDeferred(deletion),
        cursor = RendererLifecycleCursor.AwaitingDeferredDeletion(
            snapshot,
            operation,
            deletion.id,
        ),
    )

    private fun awaitFramebuffer(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
        framebufferName: com.rohittp.reng.FramebufferName,
    ): RendererLifecycleTransition = transition(
        snapshot = snapshot,
        action = RendererLifecycleAction.ValidateFramebuffer(framebufferName),
        cursor = RendererLifecycleCursor.AwaitingFramebuffer(
            snapshot,
            operation,
            framebufferName,
        ),
    )

    private fun executePermitted(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition = transition(
        snapshot = snapshot,
        action = RendererLifecycleAction.ExecutePermittedOperation(operation),
        cursor = RendererLifecycleCursor.AwaitingPermittedOperation(snapshot, operation),
    )

    private fun transition(
        snapshot: RendererLifecycleSnapshot,
        action: RendererLifecycleAction,
        cursor: RendererLifecycleCursor,
    ): RendererLifecycleTransition = RendererLifecycleTransition(
        snapshot = snapshot,
        actions = listOf(action),
        cursor = cursor,
        outcome = null,
    )

    private fun succeeded(snapshot: RendererLifecycleSnapshot): RendererLifecycleTransition =
        completed(snapshot, RendererLifecycleOutcome.Succeeded)

    private fun noOp(snapshot: RendererLifecycleSnapshot): RendererLifecycleTransition =
        completed(snapshot, RendererLifecycleOutcome.NoOp)

    private fun emptyResourceResult(snapshot: RendererLifecycleSnapshot): RendererLifecycleTransition =
        completed(snapshot, RendererLifecycleOutcome.EmptyResourceResult)

    private fun failed(
        snapshot: RendererLifecycleSnapshot,
        code: RenGErrorCode,
        stage: PipelineStage,
    ): RendererLifecycleTransition = failed(
        snapshot,
        FailureDescriptor(code = code, stage = stage),
    )

    private fun failed(
        snapshot: RendererLifecycleSnapshot,
        failure: FailureDescriptor,
    ): RendererLifecycleTransition = completed(
        snapshot,
        RendererLifecycleOutcome.Failed(failure),
    )

    private fun completed(
        snapshot: RendererLifecycleSnapshot,
        outcome: RendererLifecycleOutcome,
    ): RendererLifecycleTransition = RendererLifecycleTransition(
        snapshot = snapshot,
        actions = emptyList(),
        cursor = null,
        outcome = outcome,
    )
}
