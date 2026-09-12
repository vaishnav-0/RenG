package com.rohittp.reng.internal.lifecycle

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RendererLifecycleMatrixTest {
    @Test
    fun totalOperationStateMatrixMatchesTheApprovedContract() {
        val targetName = FramebufferName(7u)
        val cases = listOf(
            MatrixCase(
                operation = RendererLifecycleOperation.BeginPreparation,
                live = Expected.Execute(preparationActive = true),
                awaiting = Expected.Execute(preparationActive = true),
                closed = Expected.Failure(
                    RenGErrorCode.RENDERER_CLOSED,
                    PipelineStage.FRAME_PREPARATION,
                ),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.CancelPreparations,
                live = Expected.Outcome(RendererLifecycleOutcome.NoOp),
                awaiting = Expected.Outcome(RendererLifecycleOutcome.NoOp),
                closed = Expected.Outcome(RendererLifecycleOutcome.NoOp),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.ClearFrameHistory,
                live = Expected.Execute(),
                awaiting = Expected.Execute(),
                closed = Expected.Failure(
                    RenGErrorCode.RENDERER_CLOSED,
                    PipelineStage.FRAME_PLANNING,
                ),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.QueryResources(ResourceSelector.All),
                live = Expected.Execute(),
                awaiting = Expected.Execute(),
                closed = Expected.Outcome(RendererLifecycleOutcome.EmptyResourceResult),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.FreeResources(ResourceSelector.All),
                live = Expected.Execute(),
                awaiting = Expected.Execute(),
                closed = Expected.Outcome(RendererLifecycleOutcome.EmptyResourceResult),
            ),
            // Gated exactly as QueryResources is, which is the decision ADR 0049 takes rather than
            // reading the metric recorder directly: reading counters is safe everywhere, and a
            // single ungated public query would be a special case for whoever reads this next.
            MatrixCase(
                operation = RendererLifecycleOperation.QueryMetrics,
                live = Expected.Execute(),
                awaiting = Expected.Execute(),
                closed = Expected.Outcome(RendererLifecycleOutcome.EmptyResourceResult),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.NotifyGpuObjectsGone,
                live = Expected.Action(RendererLifecycleAction.AwaitRenderCallQuiescence),
                awaiting = Expected.Outcome(RendererLifecycleOutcome.NoOp),
                closed = Expected.Outcome(RendererLifecycleOutcome.NoOp),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.AdoptCurrentRenderContext,
                live = Expected.Failure(
                    RenGErrorCode.INVALID_VALUE,
                    PipelineStage.CONTEXT_ADOPTION,
                ),
                awaiting = Expected.Action(RendererLifecycleAction.ObserveAdoptableCurrentContext),
                closed = Expected.Failure(
                    RenGErrorCode.RENDERER_CLOSED,
                    PipelineStage.CONTEXT_ADOPTION,
                ),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.MintRenderTarget(targetName),
                live = Expected.Action(RendererLifecycleAction.ObserveExactCurrentContext),
                awaiting = Expected.Failure(
                    RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED,
                    PipelineStage.RENDER_TARGET,
                ),
                closed = Expected.Failure(
                    RenGErrorCode.RENDERER_CLOSED,
                    PipelineStage.RENDER_TARGET,
                ),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.Draw(
                    frame = PreparedFrameFact.OwnedOpen,
                    target = RenderTargetFact.OwnedCurrent(targetName),
                ),
                live = Expected.Action(RendererLifecycleAction.ObserveExactCurrentContext),
                awaiting = Expected.Failure(
                    RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED,
                    PipelineStage.DRAW,
                ),
                closed = Expected.Failure(
                    RenGErrorCode.RENDERER_CLOSED,
                    PipelineStage.DRAW,
                ),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.ClosePreparedFrame(PreparedFrameFact.OwnedOpen),
                live = Expected.Execute(),
                awaiting = Expected.Execute(),
                closed = Expected.Outcome(RendererLifecycleOutcome.NoOp),
            ),
            MatrixCase(
                operation = RendererLifecycleOperation.CloseRenderer,
                live = Expected.Execute(),
                awaiting = Expected.Execute(),
                closed = Expected.Outcome(RendererLifecycleOutcome.NoOp),
            ),
        )

        RendererOwnerState.entries.forEach { ownerState ->
            cases.forEach { case ->
                val transition = RendererLifecycleStateMachine.begin(
                    snapshot = snapshot(ownerState = ownerState),
                    operation = case.operation,
                )
                val expected = when (ownerState) {
                    RendererOwnerState.LIVE -> case.live
                    RendererOwnerState.AWAITING_CONTEXT_ADOPTION -> case.awaiting
                    RendererOwnerState.CLOSED -> case.closed
                }

                assertExpected(case.operation, expected, transition)
            }
        }
    }

    @Test
    fun adoptionUsesOnlyTheSuppliedObservationAndChangesOnlyAwaitingToLive() {
        val awaiting = snapshot(
            ownerState = RendererOwnerState.AWAITING_CONTEXT_ADOPTION,
            contextGeneration = 11L,
        )

        val noContext = RendererLifecycleStateMachine.resume(
            adoptionCursor(awaiting),
            RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.NONE),
        )
        assertFailure(
            noContext,
            RenGErrorCode.NO_CURRENT_RENDER_CONTEXT,
            PipelineStage.CONTEXT_ADOPTION,
        )
        assertEquals(awaiting.ownerState, noContext.snapshot.ownerState)
        assertEquals(11L, noContext.snapshot.contextGeneration)

        val unsupported = RendererLifecycleStateMachine.resume(
            adoptionCursor(awaiting),
            RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.UNSUPPORTED),
        )
        assertFailure(
            unsupported,
            RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            PipelineStage.CONTEXT_ADOPTION,
        )
        assertEquals(awaiting.ownerState, unsupported.snapshot.ownerState)

        val supported = RendererLifecycleStateMachine.resume(
            adoptionCursor(awaiting),
            RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.SUPPORTED),
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, supported.outcome)
        assertEquals(RendererOwnerState.LIVE, supported.snapshot.ownerState)
        assertEquals(11L, supported.snapshot.contextGeneration)
        assertFalse(supported.snapshot.gpuLedger.hasLiveGpuObjects)
        assertTrue(supported.snapshot.gpuLedger.deferredDeletions.isEmpty())

        val live = RendererLifecycleStateMachine.begin(
            snapshot(RendererOwnerState.LIVE),
            RendererLifecycleOperation.AdoptCurrentRenderContext,
        )
        assertFailure(live, RenGErrorCode.INVALID_VALUE, PipelineStage.CONTEXT_ADOPTION)
        assertTrue(live.actions.isEmpty())

        val closed = RendererLifecycleStateMachine.begin(
            snapshot(RendererOwnerState.CLOSED),
            RendererLifecycleOperation.AdoptCurrentRenderContext,
        )
        assertFailure(closed, RenGErrorCode.RENDERER_CLOSED, PipelineStage.CONTEXT_ADOPTION)
        assertTrue(closed.actions.isEmpty())
    }

    @Test
    fun gpuObjectLossWaitsForRenderQuiescenceThenForgetsHandlesAndIncrementsGeneration() {
        val deletion = DeferredDeletion(DeletionId(4L), null)
        val initial = snapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 19L,
            preparationActive = true,
            hasLiveGpuObjects = true,
            deferredDeletions = listOf(deletion),
        )

        val begun = RendererLifecycleStateMachine.begin(
            initial,
            RendererLifecycleOperation.NotifyGpuObjectsGone,
        )
        assertEquals(initial.ownerState, begun.snapshot.ownerState)
        assertEquals(19L, begun.snapshot.contextGeneration)
        assertTrue(begun.snapshot.gpuLedger.hasLiveGpuObjects)
        assertEquals(listOf(deletion), begun.snapshot.gpuLedger.deferredDeletions)
        assertEquals(
            listOf(RendererLifecycleAction.AwaitRenderCallQuiescence),
            begun.actions,
        )

        val completed = RendererLifecycleStateMachine.resume(
            requireNotNull(begun.cursor),
            RendererLifecycleObservation.RenderCallsQuiesced,
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, completed.outcome)
        assertEquals(RendererOwnerState.AWAITING_CONTEXT_ADOPTION, completed.snapshot.ownerState)
        assertEquals(20L, completed.snapshot.contextGeneration)
        assertTrue(completed.snapshot.preparationActive)
        assertFalse(completed.snapshot.gpuLedger.hasLiveGpuObjects)
        assertTrue(completed.snapshot.gpuLedger.deferredDeletions.isEmpty())
        assertTrue(completed.actions.isEmpty())
    }

    @Test
    fun preparedFrameCloseIsContextFreeAndIdempotentAcrossOwnerClosure() {
        val live = snapshot(RendererOwnerState.LIVE, hasLiveGpuObjects = true)
        val first = RendererLifecycleStateMachine.begin(
            live,
            RendererLifecycleOperation.ClosePreparedFrame(PreparedFrameFact.OwnedOpen),
        )
        assertEquals(
            listOf(
                RendererLifecycleAction.ExecutePermittedOperation(
                    RendererLifecycleOperation.ClosePreparedFrame(PreparedFrameFact.OwnedOpen),
                ),
            ),
            first.actions,
        )
        assertFalse(first.actions.contains(RendererLifecycleAction.ObserveExactCurrentContext))

        val released = RendererLifecycleStateMachine.resume(
            requireNotNull(first.cursor),
            RendererLifecycleObservation.PermittedOperationSucceeded,
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, released.outcome)

        val repeated = RendererLifecycleStateMachine.begin(
            released.snapshot,
            RendererLifecycleOperation.ClosePreparedFrame(PreparedFrameFact.OwnedClosed),
        )
        assertEquals(RendererLifecycleOutcome.NoOp, repeated.outcome)
        assertTrue(repeated.actions.isEmpty())

        val afterRendererClose = RendererLifecycleStateMachine.begin(
            snapshot(RendererOwnerState.CLOSED),
            RendererLifecycleOperation.ClosePreparedFrame(PreparedFrameFact.OwnedOpen),
        )
        assertEquals(RendererLifecycleOutcome.NoOp, afterRendererClose.outcome)
    }

    @Test
    fun rendererCloseSuccessMakesTheWholeOwnerTerminalAndClearsGpuState() {
        val initial = snapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 3L,
            hasLiveGpuObjects = true,
        )
        val begun = RendererLifecycleStateMachine.begin(
            initial,
            RendererLifecycleOperation.CloseRenderer,
        )
        assertEquals(
            listOf(RendererLifecycleAction.ObserveExactCurrentContext),
            begun.actions,
        )

        val contextAccepted = RendererLifecycleStateMachine.resume(
            requireNotNull(begun.cursor),
            RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
        )
        assertEquals(
            listOf(
                RendererLifecycleAction.ExecutePermittedOperation(
                    RendererLifecycleOperation.CloseRenderer,
                ),
            ),
            contextAccepted.actions,
        )

        val closed = RendererLifecycleStateMachine.resume(
            requireNotNull(contextAccepted.cursor),
            RendererLifecycleObservation.PermittedOperationSucceeded,
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, closed.outcome)
        assertEquals(RendererOwnerState.CLOSED, closed.snapshot.ownerState)
        assertFalse(closed.snapshot.preparationActive)
        assertFalse(closed.snapshot.gpuLedger.hasLiveGpuObjects)
        assertTrue(closed.snapshot.gpuLedger.deferredDeletions.isEmpty())
        assertEquals(3L, closed.snapshot.contextGeneration)

        val repeated = RendererLifecycleStateMachine.begin(
            closed.snapshot,
            RendererLifecycleOperation.CloseRenderer,
        )
        assertEquals(RendererLifecycleOutcome.NoOp, repeated.outcome)
    }

    @Test
    fun preparationIsMarkedActiveOnlyWhileItsPermittedOperationIsOutstanding() {
        val initial = snapshot(RendererOwnerState.LIVE)
        val begun = RendererLifecycleStateMachine.begin(
            initial,
            RendererLifecycleOperation.BeginPreparation,
        )
        assertTrue(begun.snapshot.preparationActive)

        val succeeded = RendererLifecycleStateMachine.resume(
            requireNotNull(begun.cursor),
            RendererLifecycleObservation.PermittedOperationSucceeded,
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, succeeded.outcome)
        assertFalse(succeeded.snapshot.preparationActive)

        val second = RendererLifecycleStateMachine.begin(
            succeeded.snapshot,
            RendererLifecycleOperation.BeginPreparation,
        )
        val suppliedFailure = FailureDescriptor(
            code = RenGErrorCode.GPU_OPERATION_FAILED,
            stage = PipelineStage.GPU_RESOURCE,
            diagnostic = failureContextDiagnostic(PipelineStage.GPU_RESOURCE),
        )
        val failed = RendererLifecycleStateMachine.resume(
            requireNotNull(second.cursor),
            RendererLifecycleObservation.PermittedOperationFailed(suppliedFailure),
        )
        assertSame(suppliedFailure, (failed.outcome as RendererLifecycleOutcome.Failed).failure)
        assertFalse(failed.snapshot.preparationActive)
    }

    @Test
    fun gpuLedgerAndTransitionListsAreInputSnapshotsAndFreshCopiesOnRead() {
        val deletion = DeferredDeletion(DeletionId(1L), null)
        val sourceDeletions = mutableListOf(deletion)
        val ledger = GpuLedger(
            hasLiveGpuObjects = true,
            deferredDeletions = sourceDeletions,
        )
        sourceDeletions.clear()
        assertEquals(listOf(deletion), ledger.deferredDeletions)

        val exposedDeletions = ledger.deferredDeletions as MutableList<DeferredDeletion>
        exposedDeletions.clear()
        assertEquals(listOf(deletion), ledger.deferredDeletions)

        val snapshot = RendererLifecycleSnapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 0L,
            preparationActive = false,
            gpuLedger = ledger,
        )
        val operation = RendererLifecycleOperation.QueryResources(ResourceSelector.All)
        val action = RendererLifecycleAction.ExecutePermittedOperation(operation)
        val sourceActions = mutableListOf<RendererLifecycleAction>(action)
        val cursor = RendererLifecycleCursor.AwaitingPermittedOperation(snapshot, operation)
        val transition = RendererLifecycleTransition(
            snapshot = snapshot,
            actions = sourceActions,
            cursor = cursor,
            outcome = null,
        )
        sourceActions.clear()
        assertEquals(listOf(action), transition.actions)

        val exposedActions = transition.actions as MutableList<RendererLifecycleAction>
        exposedActions.clear()
        assertEquals(listOf(action), transition.actions)
    }

    @Test
    fun transitionRequiresExactlyOneOfCursorAndOutcome() {
        val snapshot = snapshot(RendererOwnerState.LIVE)
        val operation = RendererLifecycleOperation.ClearFrameHistory
        val cursor = RendererLifecycleCursor.AwaitingPermittedOperation(snapshot, operation)

        assertFailsWith<IllegalArgumentException> {
            RendererLifecycleTransition(snapshot, emptyList(), null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            RendererLifecycleTransition(
                snapshot,
                emptyList(),
                cursor,
                RendererLifecycleOutcome.Succeeded,
            )
        }
    }

    private fun adoptionCursor(
        snapshot: RendererLifecycleSnapshot,
    ): RendererLifecycleCursor.AwaitingAdoptionContext {
        val transition = RendererLifecycleStateMachine.begin(
            snapshot,
            RendererLifecycleOperation.AdoptCurrentRenderContext,
        )
        return transition.cursor as RendererLifecycleCursor.AwaitingAdoptionContext
    }

    private fun assertExpected(
        operation: RendererLifecycleOperation,
        expected: Expected,
        transition: RendererLifecycleTransition,
    ) {
        when (expected) {
            is Expected.Action -> {
                assertNull(transition.outcome)
                assertNotNull(transition.cursor)
                assertEquals(listOf(expected.action), transition.actions)
                assertCursorMatchesAction(expected.action, transition.cursor)
            }

            is Expected.Execute -> {
                assertNull(transition.outcome)
                assertTrue(
                    transition.cursor is RendererLifecycleCursor.AwaitingPermittedOperation,
                    "expected permitted-operation cursor for $operation",
                )
                assertEquals(
                    listOf(RendererLifecycleAction.ExecutePermittedOperation(operation)),
                    transition.actions,
                )
                assertEquals(expected.preparationActive, transition.snapshot.preparationActive)
            }

            is Expected.Failure -> {
                assertFailure(transition, expected.code, expected.stage)
                assertTrue(transition.actions.isEmpty())
            }

            is Expected.Outcome -> {
                assertEquals(expected.outcome, transition.outcome)
                assertNull(transition.cursor)
                assertTrue(transition.actions.isEmpty())
            }
        }
    }

    private fun assertCursorMatchesAction(
        action: RendererLifecycleAction,
        cursor: RendererLifecycleCursor,
    ) {
        val matches = when (action) {
            RendererLifecycleAction.AwaitRenderCallQuiescence ->
                cursor is RendererLifecycleCursor.AwaitingRenderCallQuiescence

            RendererLifecycleAction.ObserveExactCurrentContext ->
                cursor is RendererLifecycleCursor.AwaitingExactContext

            RendererLifecycleAction.ObserveAdoptableCurrentContext ->
                cursor is RendererLifecycleCursor.AwaitingAdoptionContext

            is RendererLifecycleAction.DeleteDeferred ->
                cursor is RendererLifecycleCursor.AwaitingDeferredDeletion

            is RendererLifecycleAction.ValidateFramebuffer ->
                cursor is RendererLifecycleCursor.AwaitingFramebuffer

            RendererLifecycleAction.RequestPreparationCancellation ->
                cursor is RendererLifecycleCursor.AwaitingPreparationTermination

            is RendererLifecycleAction.ExecutePermittedOperation ->
                cursor is RendererLifecycleCursor.AwaitingPermittedOperation
        }
        assertTrue(matches, "cursor does not match $action")
    }

    private fun assertFailure(
        transition: RendererLifecycleTransition,
        code: RenGErrorCode,
        stage: PipelineStage,
    ) {
        val failed = transition.outcome as RendererLifecycleOutcome.Failed
        assertEquals(code, failed.failure.code)
        assertEquals(stage, failed.failure.stage)
        assertNull(failed.failure.diagnostic)
        assertNull(transition.cursor)
    }

    private fun snapshot(
        ownerState: RendererOwnerState,
        contextGeneration: Long = 5L,
        preparationActive: Boolean = false,
        hasLiveGpuObjects: Boolean = false,
        deferredDeletions: List<DeferredDeletion> = emptyList(),
    ): RendererLifecycleSnapshot = RendererLifecycleSnapshot(
        ownerState = ownerState,
        contextGeneration = contextGeneration,
        preparationActive = preparationActive,
        gpuLedger = GpuLedger(hasLiveGpuObjects, deferredDeletions),
    )

    private data class MatrixCase(
        val operation: RendererLifecycleOperation,
        val live: Expected,
        val awaiting: Expected,
        val closed: Expected,
    )

    private sealed interface Expected {
        data class Action(val action: RendererLifecycleAction) : Expected

        data class Execute(val preparationActive: Boolean = false) : Expected

        data class Failure(
            val code: RenGErrorCode,
            val stage: PipelineStage,
        ) : Expected

        data class Outcome(val outcome: RendererLifecycleOutcome) : Expected
    }
}
