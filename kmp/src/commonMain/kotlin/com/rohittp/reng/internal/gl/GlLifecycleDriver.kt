package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.lifecycle.AdoptionContextFact
import com.rohittp.reng.internal.lifecycle.ExactContextFact
import com.rohittp.reng.internal.lifecycle.FramebufferFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleAction
import com.rohittp.reng.internal.lifecycle.RendererLifecycleObservation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererLifecycleStateMachine
import com.rohittp.reng.internal.lifecycle.RendererOwnerState

internal fun interface PermittedOperationExecutor {
    fun execute(operation: RendererLifecycleOperation): FailureDescriptor?
}

internal fun interface RenderCallBarrier {
    fun awaitRenderCallQuiescence()
}

internal fun interface PreparationController {
    fun requestCancellationAndAwaitTermination()
}

/**
 * Drives [RendererLifecycleStateMachine] with the real GL facts the machine's actions ask for, and
 * executes the single permitted operation it authorizes. This driver re-decides nothing: every
 * branch below either supplies a fact the machine requested or performs the one action the machine
 * named for the current step. No `when` here duplicates a decision the reducer already made, and
 * no GL result overrides a machine outcome.
 */
internal class GlLifecycleDriver(
    private val binding: GlBinding,
    private val probe: RenderContextProbe,
    private val registry: GlObjectRegistry,
    private val programs: GlProgramCache,
    private val operationGate: GlOperationGate = GlOperationGate(),
    private val barrier: RenderCallBarrier = RenderCallBarrier { },
    private val preparation: PreparationController = PreparationController { },
    initialSnapshot: RendererLifecycleSnapshot,
    initialContext: RenderContextIdentity? = null,
    initialProfile: RenderContextProfile? = null,
) {
    private var currentSnapshot: RendererLifecycleSnapshot = initialSnapshot
    internal val snapshot: RendererLifecycleSnapshot
        get() = operationGate.run { currentSnapshot }

    private var currentAdoptedContext: RenderContextIdentity? = initialContext
    internal val adoptedContext: RenderContextIdentity?
        get() = operationGate.run { currentAdoptedContext }

    private var currentProfile: RenderContextProfile? = initialProfile
    internal val profile: RenderContextProfile?
        get() = operationGate.run { currentProfile }

    private var pendingContext: RenderContextIdentity? = null
    private var pendingProfile: RenderContextProfile? = null

    /** Keeps renderer-owned state changes in the same critical section as their lifecycle facts. */
    internal fun <T> withOperationGate(block: () -> T): T = operationGate.run(block)

    /**
     * Records the core GL objects recreated immediately after a successful context adoption.
     *
     * Adoption and recreation are enclosed by the renderer's outer [withOperationGate] call, so no
     * public operation can observe the temporary LIVE-but-empty snapshot between them. Keeping this
     * update after successful recreation is important: a failed recreation is rolled back through
     * `NotifyGpuObjectsGone` and must remain context-free, while a successful one must make every
     * later free/close prove that this exact context is current before deleting the new objects.
     */
    internal fun recordCoreGpuObjectsCreated() = operationGate.run {
        check(currentSnapshot.ownerState == RendererOwnerState.LIVE) {
            "core GPU objects can be recorded only for a live renderer"
        }
        check(!currentSnapshot.gpuLedger.hasLiveGpuObjects) {
            "core GPU objects are already recorded as live"
        }
        currentSnapshot = currentSnapshot.copy(
            gpuLedger = com.rohittp.reng.internal.lifecycle.GpuLedger(
                hasLiveGpuObjects = true,
                deferredDeletions = currentSnapshot.gpuLedger.deferredDeletions,
            ),
        )
    }

    internal fun run(
        operation: RendererLifecycleOperation,
        executor: PermittedOperationExecutor,
    ): RendererLifecycleOutcome = operationGate.run {
        var transition = RendererLifecycleStateMachine.begin(currentSnapshot, operation)
        var terminal: RendererLifecycleOutcome? = null
        while (terminal == null) {
            currentSnapshot = transition.snapshot
            val outcome = transition.outcome
            if (outcome != null) {
                applyTerminal(operation, outcome)
                terminal = outcome
            } else {
                val cursor = requireNotNull(transition.cursor) { "a transition has a cursor or an outcome" }
                val observation = perform(transition.actions.single(), executor)
                transition = RendererLifecycleStateMachine.resume(cursor, observation)
            }
        }
        requireNotNull(terminal)
    }

    private fun perform(
        action: RendererLifecycleAction,
        executor: PermittedOperationExecutor,
    ): RendererLifecycleObservation = when (action) {
        RendererLifecycleAction.AwaitRenderCallQuiescence -> {
            barrier.awaitRenderCallQuiescence()
            RendererLifecycleObservation.RenderCallsQuiesced
        }

        RendererLifecycleAction.ObserveExactCurrentContext ->
            RendererLifecycleObservation.ExactContextObserved(
                currentAdoptedContext?.let { exactContextFact(it, probe) }
                    ?: ExactContextFact.NONE,
            )

        RendererLifecycleAction.ObserveAdoptableCurrentContext -> observeAdoption()

        is RendererLifecycleAction.DeleteDeferred -> {
            GlErrorQueue.drainOnEntry(binding)
            deleteGlObjects(binding, registry.takeQueued(action.deletion.id))
            if (GlErrorQueue.firstOwnError(binding) == GL_NO_ERROR) {
                RendererLifecycleObservation.DeferredDeletionAcknowledged(action.deletion.id)
            } else {
                RendererLifecycleObservation.DeferredDeletionFailed(
                    deletionId = action.deletion.id,
                    failure = glOperationFailure(
                        PipelineStage.RESOURCE_FREE,
                        action.deletion.resourceKey,
                    ),
                )
            }
        }

        is RendererLifecycleAction.ValidateFramebuffer ->
            RendererLifecycleObservation.FramebufferObserved(framebufferFact(action.framebufferName))

        RendererLifecycleAction.RequestPreparationCancellation -> {
            preparation.requestCancellationAndAwaitTermination()
            RendererLifecycleObservation.PreparationTerminated
        }

        is RendererLifecycleAction.ExecutePermittedOperation ->
            executor.execute(action.operation)
                ?.let { RendererLifecycleObservation.PermittedOperationFailed(it) }
                ?: RendererLifecycleObservation.PermittedOperationSucceeded
    }

    private fun observeAdoption(): RendererLifecycleObservation {
        val identity = probe.currentContextIdentity()
            ?: return RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.NONE)
        return when (val adoption = adoptRenderContext(binding)) {
            is RenderContextAdoption.Adopted -> {
                pendingContext = identity
                pendingProfile = adoption.profile
                RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.SUPPORTED)
            }

            is RenderContextAdoption.Rejected -> {
                pendingContext = null
                pendingProfile = null
                RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.UNSUPPORTED)
            }
        }
    }

    private fun framebufferFact(framebufferName: FramebufferName): FramebufferFact {
        val name = framebufferName.value.toInt()
        if (name != 0 && !binding.isFramebuffer(name)) return FramebufferFact.MISSING_OR_INCOMPLETE
        val previous = IntArray(1)
        binding.getIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, previous)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, name)
        val status = binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, previous[0])
        return if (status == GL_FRAMEBUFFER_COMPLETE) {
            FramebufferFact.COMPLETE
        } else {
            FramebufferFact.MISSING_OR_INCOMPLETE
        }
    }

    /**
     * Dispatches on the [RendererOwnerState] the machine actually reached, not on which
     * [RendererLifecycleOperation] reached it. That is deliberate: it used to be a `when` over
     * the operation with a trailing `if (snapshot.ownerState == AWAITING_CONTEXT_ADOPTION)
     * adoptedContext = null` safety net appended after it. That net was dead code given today's
     * single route into that state (`NotifyGpuObjectsGone`), but dead code of a dangerous shape
     * — a second future route into `AWAITING_CONTEXT_ADOPTION` that the operation `when` above
     * did not name would fall through to `else -> Unit`, then get "handled" by the net nulling
     * [adoptedContext] while silently skipping [forgetWithoutDeleting], leaving a stale program
     * cache and registry behind while looking handled.
     *
     * Keying on the reached state instead makes that impossible to reintroduce by accident:
     * `RendererOwnerState` is an enum, this `when` is in return position with no `else`, so the
     * compiler requires every state to be handled here. Adding a fourth `RendererOwnerState` — or
     * a change that stops routing some existing transition through one of these three branches —
     * is a build failure at this function, not a runtime path that quietly does the wrong thing.
     */
    private fun applyTerminal(
        operation: RendererLifecycleOperation,
        outcome: RendererLifecycleOutcome,
    ) {
        if (outcome !is RendererLifecycleOutcome.Succeeded) return

        return when (currentSnapshot.ownerState) {
            // Reached only by NotifyGpuObjectsGone today, but the guarantee is about the state,
            // not the operation: whatever operation leaves the machine here has just declared
            // every GL handle gone (ADR 0007/0015), so both the GL-object bookkeeping and the
            // context-derived fields must be forgotten together.
            RendererOwnerState.AWAITING_CONTEXT_ADOPTION -> {
                forgetWithoutDeleting()
                currentAdoptedContext = null
                currentProfile = null
            }

            // Reached by every operation that succeeds without changing ownership. Only a
            // successful AdoptCurrentRenderContext needs to record anything here; every other
            // LIVE-preserving operation is a no-op for this function.
            RendererOwnerState.LIVE ->
                if (operation is RendererLifecycleOperation.AdoptCurrentRenderContext) {
                    forgetWithoutDeleting()
                    currentAdoptedContext = pendingContext
                    currentProfile = pendingProfile
                } else {
                    Unit
                }

            // Reached only by a successful CloseRenderer today (RendererLifecycleStateMachine is
            // the only place that ever sets this state). Forgetting unconditionally here is
            // still correct if that ever changes: a closed renderer must never hold a GL handle.
            RendererOwnerState.CLOSED -> {
                forgetWithoutDeleting()
                currentAdoptedContext = null
                currentProfile = null
            }
        }
    }

    /**
     * Losing the context is not freeing (ADRs 0007, 0015): forgets every live and queued GL
     * object handle plus every cached compiled program, without issuing a single GL call, because
     * a replacement context cannot delete handles compiled or allocated against the lost one.
     *
     * This runs on every path that leaves [adoptedContext] behind: GPU object loss, a fresh
     * adoption (before recording the new identity and profile), and renderer close. The program
     * cache is included deliberately, not only the object registry: [GlProgramCache]'s key carries
     * no shader dialect, and the dialect is a runtime property of the context, read by
     * [adoptRenderContext] on every adoption. Two successive adoptions on the same machine can
     * resolve to different dialects, so a program compiled under the previous adoption must never
     * survive into the next one — leaving it cached would hand a program compiled for one dialect
     * (say GLES) to a context now resolved to the other (say DESKTOP), a defect that would surface
     * as a wrong-looking or non-compiling shader far from this call site.
     */
    private fun forgetWithoutDeleting() {
        registry.forgetEverything()
        programs.forgetAll()
    }
}
