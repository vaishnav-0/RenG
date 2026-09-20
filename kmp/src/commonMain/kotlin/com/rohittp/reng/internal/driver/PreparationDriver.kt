package com.rohittp.reng.internal.driver

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.Store
import com.rohittp.reng.Transport
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.resource.AdvancePendingClassGates
import com.rohittp.reng.internal.resource.AdvancePendingStyleCommit
import com.rohittp.reng.internal.resource.BasemapStyleCompilationCompleted
import com.rohittp.reng.internal.resource.BasemapStyleCompilationOutcome
import com.rohittp.reng.internal.resource.BasemapStyleVisibilityInstallCompleted
import com.rohittp.reng.internal.resource.BasemapStyleWriteCompleted
import com.rohittp.reng.internal.resource.CallTransport
import com.rohittp.reng.internal.resource.CancellationCause
import com.rohittp.reng.internal.resource.CancellationId
import com.rohittp.reng.internal.resource.CancellationSelection
import com.rohittp.reng.internal.resource.CompileBasemapStyle
import com.rohittp.reng.internal.resource.InstallBasemapStyleVisibility
import com.rohittp.reng.internal.resource.InstallVisibility
import com.rohittp.reng.internal.resource.PendingClassGates
import com.rohittp.reng.internal.resource.ReadStore
import com.rohittp.reng.internal.resource.ResourceActionId
import com.rohittp.reng.internal.resource.ResourceClassValidationCompleted
import com.rohittp.reng.internal.resource.ResourceOperationAction
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationEvent
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOperationState
import com.rohittp.reng.internal.resource.ResourceOperationStateMachine
import com.rohittp.reng.internal.resource.ResourceOperationTransition
import com.rohittp.reng.internal.resource.ResourceRouteStatus
import com.rohittp.reng.internal.resource.StartRoute
import com.rohittp.reng.internal.resource.StoreReadCompleted
import com.rohittp.reng.internal.resource.StoreWriteCompleted
import com.rohittp.reng.internal.resource.SuppliedCallOutcome
import com.rohittp.reng.internal.resource.SuppliedInstallOutcome
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome
import com.rohittp.reng.internal.resource.TransportCompleted
import com.rohittp.reng.internal.resource.ValidateResourceClass
import com.rohittp.reng.internal.resource.VisibilityInstallCompleted
import com.rohittp.reng.internal.resource.WriteBasemapStyle
import com.rohittp.reng.internal.resource.WriteStore
import com.rohittp.reng.internal.resource.ordinaryResourceClassGates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/**
 * Drives one [ResourceOperationDefinition] to its [ResourceOperationOutcome], sequencing clock sampling,
 * a resident-cache lookup, a Store read, a Transport fetch, and latch replay against the real adapters
 * [ResourceOperationStateMachine] asks for.
 *
 * `run` opens exactly one [coroutineScope]. Every action this driver executes is launched as a structured
 * child of that scope, so cancelling the caller's own coroutine cancels every in-flight adapter call and
 * no child ever outlives this one invocation. No dispatcher is selected anywhere in this class: every
 * coroutine it launches keeps running on the caller's own context, because RenG owns no thread pool of
 * its own. A [Semaphore] sized to [maximumConcurrentOperations] bounds how many actions run at once,
 * regardless of how many the state machine hands back in one batch.
 *
 * The loop is: ask the state machine for actions (via [ResourceOperationStateMachine.start], then via
 * [ResourceOperationStateMachine.transition] for every event this class feeds back), launch each action
 * under a permit, and feed exactly one event back per completed action, until a transition yields an
 * outcome. [StartRoute] is the one action that performs no adapter call and feeds back no event through
 * `transition`: it is how a route begins at all, so this driver executes it by calling
 * [ResourceOperationStateMachine.beginLookup] directly and folding the resulting transition into the same
 * loop as every other action.
 *
 * A route that finishes resolving its content sits at a [PendingClassGates] cursor until something asks
 * the state machine to advance it; this driver does that immediately, itself, via
 * [AdvancePendingClassGates] for every ordinary resource class ([ordinaryResourceClassGates] non-null)
 * and via [AdvancePendingStyleCommit] for a basemap style, whose gates are null because a style is
 * validated by its own commit verbs rather than by a class gate. Both are the same synchronous,
 * no-adapter-call shape as [StartRoute], and both are pumped to a fixpoint after every event: a style
 * route added without its pump would simply sit at its cursor with no pending action, and this class's
 * own `check(pendingActionCount > 0)` would report the operation as stalled. A sprite-classed route is
 * still left untouched; no task has reached its commit verbs.
 *
 * **Cancellation.** Two distinct cancellations reach this class, and only one of them is this driver's to
 * observe rather than merely suffer: a caller cancelling the coroutine that is running [run] is already
 * handled for free by [coroutineScope] — every launched child is a structured child of that same scope, so
 * it is cancelled too, and this loop simply never gets to send or receive another event. But an adapter
 * that throws a bare [CancellationException] of its **own** initiative — Rentile's rasterizer cancelling
 * its own internal job on `close()` and surfacing that to in-flight work, say — completes its `launch`ed
 * child the exact same way a genuine external cancellation would, except that child's own [Job] was never
 * asked to cancel anything. [kotlinx.coroutines.coroutineScope] treats a child completing via
 * [CancellationException] as ordinary, silent completion (that is precisely what lets a coroutine
 * cooperatively bail out without crashing its siblings) — which means, left alone, this loop's `events`
 * channel would simply never receive the corresponding event and [run] would hang forever instead of
 * finishing. [executeObservingAdapterCancellation] is how this driver tells the two apart: the ambient
 * [Job] is still active exactly when nothing has cancelled this child's own `Job` — an adapter-initiated
 * cancellation, folded into an event via [adapterCancellationEventFor] — and inactive exactly when the
 * ambient scope is already winding down for a real external reason, in which case the
 * [CancellationException] is rethrown unwrapped, exactly as [ResourceActionExecutor.suppliedCall] already
 * does at its own seam. [ResourceOperationStateMachine]'s own arbitration decides what an observed adapter
 * cancellation means for the whole operation; this driver only supplies the observation.
 *
 * Cross-coroutine cancellation belongs to the renderer's outer preparation-session coordinator. This
 * driver deliberately has no independently published active job: structured cancellation of [run]'s
 * caller reaches every action child in this scope.
 */
internal class PreparationDriver(
    private val transport: Transport,
    private val store: Store,
    private val cache: ResidentCache,
    private val classGateRunner: ClassGateRunner,
    private val basemapEngineHost: BasemapEngineHost,
    private val resourceLimits: ResourceLimits,
    private val maximumConcurrentOperations: Int,
    private val clock: () -> Long,
) {

    /**
     * [leaseSink], when supplied, collects every resident-cache lease this run takes, so the owner that
     * asked for the operation can release them once it is done with the content -- which is what
     * [com.rohittp.reng.PreparedFrame.close] does with the ones its own preparation took. Omitting it
     * leaves the leases outstanding for the cache's lifetime, which is what every caller did before
     * owner-lease bookkeeping existed and what every test driving this class directly still expects.
     * [leaseObserver], when supplied, runs immediately after that lease has been recorded for cleanup;
     * throwing from it aborts the operation while leaving the lease reachable by the caller's rollback.
     */
    suspend fun run(
        definition: ResourceOperationDefinition,
        leaseSink: MutableList<Lease>? = null,
        leaseObserver: ((Lease) -> Unit)? = null,
    ): ResourceOperationOutcome = coroutineScope {
            val executor = ResourceActionExecutor(
                transport = transport,
                store = store,
                cache = cache,
                classGateRunner = classGateRunner,
                basemapEngineHost = basemapEngineHost,
                resourceLimits = resourceLimits,
                clock = clock,
                leaseSink = leaseSink,
                leaseObserver = leaseObserver,
            )
            val semaphore = Semaphore(maximumConcurrentOperations)
            val events = Channel<ResourceOperationEvent>(Channel.UNLIMITED)

            var state: ResourceOperationState.Running? = null
            var outcome: ResourceOperationOutcome? = null
            var pendingActionCount = 0

            fun apply(transition: ResourceOperationTransition) {
                transition.state?.let { state = it }
                transition.outcome?.let { outcome = it }
                transition.actions.forEach { action ->
                    if (action is StartRoute) {
                        // StartRoute performs no adapter call and feeds back no event through
                        // `transition`: it is folded into the same loop as every other action by calling
                        // beginLookup directly and recursively applying whatever it yields.
                        apply(ResourceOperationStateMachine.beginLookup(requireNotNull(state), action.ordinal))
                    } else {
                        pendingActionCount += 1
                        launch {
                            semaphore.withPermit {
                                events.send(executeObservingAdapterCancellation(executor, action))
                            }
                        }
                    }
                }
            }

            fun advancePendingClassGates(): Boolean {
                var advanced = false
                while (true) {
                    val running = state ?: return advanced
                    val record = running.routeRecords.firstOrNull { record ->
                        val cursor = record.cursor
                        cursor is PendingClassGates &&
                            ordinaryResourceClassGates(cursor.content.route.resourceClass) != null
                    } ?: return advanced
                    val ordinal = (record.cursor as PendingClassGates).ordinal
                    apply(ResourceOperationStateMachine.transition(running, AdvancePendingClassGates(ordinal)))
                    advanced = true
                }
            }

            // A basemap style route reaches its commit verbs only through this, because
            // `ordinaryResourceClassGates(BASEMAP_STYLE)` is null and nothing else ever sends the event.
            // It advances exactly the style routes that are still active and have not yet staged their
            // commit -- a validated style sits back at a PendingClassGates cursor while parked, and
            // advancing one twice is refused by the state machine rather than tolerated.
            fun advancePendingStyleCommits(): Boolean {
                var advanced = false
                while (true) {
                    val running = state ?: return advanced
                    val staged = running.styleCommitStates.map { it.ordinal }.toSet()
                    val active = running.activeRouteOrdinals.toSet()
                    val record = running.routeRecords.firstOrNull { record ->
                        val cursor = record.cursor
                        cursor is PendingClassGates &&
                            cursor.content.route.resourceClass == ResourceClass.BASEMAP_STYLE &&
                            record.status == ResourceRouteStatus.RUNNING &&
                            cursor.ordinal in active &&
                            cursor.ordinal !in staged
                    } ?: return advanced
                    val ordinal = (record.cursor as PendingClassGates).ordinal
                    apply(ResourceOperationStateMachine.transition(running, AdvancePendingStyleCommit(ordinal)))
                    advanced = true
                }
            }

            // Either pump can make the other's work reachable, so both run to a joint fixpoint.
            fun advancePendingCursors() {
                var advanced = true
                while (advanced) {
                    advanced = advancePendingClassGates() || advancePendingStyleCommits()
                }
            }

            apply(ResourceOperationStateMachine.start(definition))
            advancePendingCursors()

            while (outcome == null) {
                check(pendingActionCount > 0) {
                    "the resource operation state machine stalled with no pending action and no outcome"
                }
                val event = events.receive()
                pendingActionCount -= 1
                apply(ResourceOperationStateMachine.transition(requireNotNull(state), event))
                advancePendingCursors()
            }

        requireNotNull(outcome)
    }
}

/**
 * Executes one action through [executor] and, if that throws a bare [CancellationException] rather than
 * completing, decides whether this driver may safely fold it into a [ResourceOperationEvent] or must let
 * it propagate unwrapped. `coroutineContext[Job]?.isActive` — checked only after the exception is already
 * caught — is the discriminator: still `true` means nothing has cancelled *this* coroutine's own `Job`, so
 * the exception can only be the adapter's own initiative (Rentile's rasterizer cancelling its own internal
 * job on `close()` and surfacing that to in-flight work, say) and [adapterCancellationEventFor] reports it
 * as a [com.rohittp.reng.internal.resource.CancellationCause.ADAPTER] observation; `false` means this
 * child's own `Job` genuinely has been cancelled — a caller cancelling [PreparationDriver.run], or
 * its outer preparation session — in which case rethrowing unwrapped is what lets
 * [kotlinx.coroutines.coroutineScope]'s ordinary structured-concurrency cancellation proceed, exactly as
 * [ResourceActionExecutor.suppliedCall] already does at its own seam. Left unhandled entirely — the shape
 * before this function existed — an adapter's own unsolicited [CancellationException] would complete its
 * `launch`ed child the same way a genuine external cancellation would, which [kotlinx.coroutines.coroutineScope]
 * treats as ordinary, silent completion; the caller of [PreparationDriver.run] would then wait on an event
 * that never arrives.
 *
 * A free function taking an explicit [executor] and [action], rather than a driver method reading its own
 * fields, exactly so a test can drive the discriminator directly against a coroutine whose `Job` state it
 * controls, without threading a full [PreparationDriver.run] invocation through it.
 */
internal suspend fun executeObservingAdapterCancellation(
    executor: ResourceActionExecutor,
    action: ResourceOperationAction,
): ResourceOperationEvent = try {
    executor.execute(action)
} catch (adapterCancellation: CancellationException) {
    if (coroutineContext[Job]?.isActive ?: true) {
        adapterCancellationEventFor(action, adapterCancellation)
    } else {
        throw adapterCancellation
    }
}

/**
 * Maps an action this driver just tried to execute, and the [CancellationException] an adapter threw of
 * its own initiative while executing it, onto the one [ResourceOperationEvent] that reports it as a
 * [CancellationCause.ADAPTER] observation rather than losing it. [CancellationId] reuses the action's own
 * [ResourceActionId] — already unique per action within one operation, so no separate counter is needed to
 * keep every [CancellationSelection] this driver ever produces distinguishable. Every action with a real
 * suspending adapter call is covered; anything else (no task has reached it, or it has no adapter call to
 * cancel in the first place) rethrows the original cancellation unwrapped rather than inventing an event
 * shape for it.
 */
private fun adapterCancellationEventFor(
    action: ResourceOperationAction,
    cancellation: CancellationException,
): ResourceOperationEvent {
    fun selectionFor(actionId: ResourceActionId) =
        CancellationSelection(CancellationCause.ADAPTER, CancellationId(actionId.value))

    return when (action) {
        is ReadStore -> StoreReadCompleted(
            action.actionId,
            SuppliedCallOutcome.Cancelled(selectionFor(action.actionId)),
        )

        is CallTransport -> TransportCompleted(
            action.actionId,
            SuppliedCallOutcome.Cancelled(selectionFor(action.actionId)),
        )

        is ValidateResourceClass -> ResourceClassValidationCompleted(
            action.actionId,
            SuppliedValidationOutcome.Cancelled(selectionFor(action.actionId)),
        )

        is WriteStore -> StoreWriteCompleted(
            action.actionId,
            SuppliedCallOutcome.Cancelled(selectionFor(action.actionId)),
        )

        is InstallVisibility -> VisibilityInstallCompleted(
            action.actionId,
            SuppliedInstallOutcome.Cancelled(selectionFor(action.actionId)),
        )

        is CompileBasemapStyle -> BasemapStyleCompilationCompleted(
            action.actionId,
            BasemapStyleCompilationOutcome.Cancelled(selectionFor(action.actionId)),
        )

        is WriteBasemapStyle -> BasemapStyleWriteCompleted(
            action.actionId,
            action.groupId,
            SuppliedCallOutcome.Cancelled(selectionFor(action.actionId)),
        )

        is InstallBasemapStyleVisibility -> BasemapStyleVisibilityInstallCompleted(
            action.actionId,
            action.groupId,
            SuppliedInstallOutcome.Cancelled(selectionFor(action.actionId)),
        )

        // ValidateBasemapStyle is deliberately absent: it reads bytes RenG already holds, reaches no
        // adapter and no engine, so it has nothing that could cancel it of its own initiative.
        else -> throw cancellation
    }
}
