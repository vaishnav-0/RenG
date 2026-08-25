package com.rohittp.reng.internal.driver

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.Store
import com.rohittp.reng.Transport
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.basemap.BasemapStyleManifestOutcome
import com.rohittp.reng.internal.basemap.deriveBasemapStyleManifest
import com.rohittp.reng.internal.basemap.styleTimeRoutes
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.resource.BasemapStyleCompilationCompleted
import com.rohittp.reng.internal.resource.BasemapStyleCompilationOutcome
import com.rohittp.reng.internal.resource.BasemapStyleValidationCompleted
import com.rohittp.reng.internal.resource.BasemapStyleValidationOutcome
import com.rohittp.reng.internal.resource.BasemapStyleVisibilityInstallCompleted
import com.rohittp.reng.internal.resource.BasemapStyleWriteCompleted
import com.rohittp.reng.internal.resource.CallTransport
import com.rohittp.reng.internal.resource.CancelRoute
import com.rohittp.reng.internal.resource.CleanupCancellationObserved
import com.rohittp.reng.internal.resource.ClockSampled
import com.rohittp.reng.internal.resource.CompileBasemapStyle
import com.rohittp.reng.internal.resource.ContentProvenance
import com.rohittp.reng.internal.resource.InstallBasemapStyleVisibility
import com.rohittp.reng.internal.resource.InstallVisibility
import com.rohittp.reng.internal.resource.LatchedTransportReplayCompleted
import com.rohittp.reng.internal.resource.ObserveResident
import com.rohittp.reng.internal.resource.ReadStore
import com.rohittp.reng.internal.resource.ReplayLatchedTransport
import com.rohittp.reng.internal.resource.ResidentObserved
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceClassValidationCompleted
import com.rohittp.reng.internal.resource.ResourceOperationAction
import com.rohittp.reng.internal.resource.ResourceOperationEvent
import com.rohittp.reng.internal.resource.SampleClock
import com.rohittp.reng.internal.resource.StoreReadCompleted
import com.rohittp.reng.internal.resource.StoreWriteCompleted
import com.rohittp.reng.internal.resource.SuppliedCallOutcome
import com.rohittp.reng.internal.resource.SuppliedInstallOutcome
import com.rohittp.reng.internal.resource.TransportCompleted
import com.rohittp.reng.internal.resource.ValidateBasemapStyle
import com.rohittp.reng.internal.resource.ValidateResourceClass
import com.rohittp.reng.internal.resource.VisibilityInstallCompleted
import com.rohittp.reng.internal.resource.WriteBasemapStyle
import com.rohittp.reng.internal.resource.WriteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * Executes exactly one [ResourceOperationAction] against the real [Transport], [Store], and
 * [ResidentCache] adapters and reports exactly one resulting [ResourceOperationEvent]. This is the ONE
 * seam where an injected consumer adapter's own [Throwable] is observed, so it is also the one seam that
 * must never let that throwable's message or cause escape: every non-cancellation failure collapses to a
 * bare [SuppliedCallOutcome.Failed] — a marker with no payload at all — and [ResourceOperationStateMachine]
 * (not this class) is what turns that marker into a stable, redacted [FailureDescriptor]. A
 * [CancellationException] is never caught here; it is left to propagate unwrapped, exactly as structured
 * concurrency and Kotlin's own stack-trace recovery expect.
 *
 * `ResourceOperationAction` is a sealed interface with 18 subtypes. This file's `when` handles the five
 * that need a real adapter call — [SampleClock], [ObserveResident], [ReadStore], [CallTransport], and
 * [ReplayLatchedTransport] — plus [WriteStore] (a real `Store.write` call, mapped the same way as every
 * other adapter call), [ValidateResourceClass] (a real [ClassGateRunner] call — see that class for what
 * "real" means for each gate; every gate it declares is one RenG performs itself, since the engine
 * acquires the basemap fetch classes through the firewall and they never reach a class gate),
 * [InstallVisibility] (a real [ResidentCache] install-and-lease — see [installVisibility]),
 * [CancelRoute] (pure internal bookkeeping — see the `when` branch below), and the four basemap-style
 * commit verbs: [ValidateBasemapStyle] (pure document reading), [CompileBasemapStyle] (route
 * preregistration plus a real engine compilation through [BasemapEngineHost]), [WriteBasemapStyle] (a
 * real `Store.write`) and [InstallBasemapStyleVisibility] (a real install-and-lease). Every action no
 * task has reached is an unreachable `else`.
 *
 * Unlike every other `Failed` outcome here, [SuppliedInstallOutcome.Failed] carries a [FailureDescriptor]
 * this class constructs directly rather than a bare marker [ResourceOperationStateMachine] classifies —
 * [ResourceOperationStateMachine.visibilityInstallCompleted] forwards it verbatim. That asymmetry is
 * deliberate: an install failure is inherently something only this executor observes at the cache layer
 * (unlike a class gate, whose failure category the gate's own identity already determines), and — unlike
 * [Transport]/[Store] — [ResidentCache] is RenG's own internal component, not a consumer-injected
 * adapter, so nothing about a cache-observed condition needs redacting.
 */
internal class ResourceActionExecutor(
    private val transport: Transport,
    private val store: Store,
    private val cache: ResidentCache,
    private val classGateRunner: ClassGateRunner,
    /**
     * The renderer's one long-lived Rentile engine (ADR 0016/0017), and the only thing [CompileBasemapStyle]
     * drives. Deliberately a **required** parameter with no default, because a default here is precisely
     * how a call site stays compiling while being disconnected from the thing it is supposed to drive.
     */
    private val basemapEngineHost: BasemapEngineHost,
    /**
     * The consumer's own byte ceilings, needed to derive the style-time route manifest: a preregistered
     * route carries the ceiling the firewall enforces on the engine's behalf, and the engine's own number
     * is never trusted for it (ADR 0016).
     */
    private val resourceLimits: ResourceLimits,
    private val clock: () -> Long,
    /**
     * Where every lease this executor takes is recorded, so the owner that requested the operation can
     * release it once it is finished with the content -- the "owner-lease bookkeeping" [installVisibility]
     * used to name as a future task's job. A [com.rohittp.reng.PreparedFrame] releasing its own leases on
     * `close()` is exactly that owner, and without this the lease of every generation a preparation ever
     * installed stayed outstanding for the renderer's whole life. Null keeps the older behaviour -- the
     * lease is still taken, which is what keeps the generation alive, and then simply dropped -- which is
     * what every test that drives this class directly still wants.
     */
    private val leaseSink: MutableList<Lease>? = null,
) {
    /**
     * Serializes [leaseSink] appends. [PreparationDriver] launches one child coroutine per action and
     * selects no dispatcher of its own, so two [InstallVisibility] actions of one operation genuinely can
     * reach [recordLease] from two threads whenever the consumer's own `prepare()` call sits on a
     * multi-threaded dispatcher -- and an unguarded [MutableList] can lose one of the two leases, which is
     * exactly the leak this sink exists to close. Held across a single list append and nothing else: the
     * same shape, and the same "[Mutex.tryLock] is safe from ordinary non-suspending code across real
     * threads" reasoning, that [ResidentCache] already uses for its own state transitions.
     */
    private val leaseSinkMutex: Mutex = Mutex()

    suspend fun execute(action: ResourceOperationAction): ResourceOperationEvent = when (action) {
        is SampleClock -> ClockSampled(action.actionId, clock())

        is ObserveResident -> ResidentObserved(
            action.actionId,
            cache.current(action.resourceKey)?.stored,
        )

        is ReadStore -> StoreReadCompleted(
            action.actionId,
            suppliedCall { store.read(action.rawKey) },
        )

        is CallTransport -> TransportCompleted(
            action.actionId,
            suppliedCall { transport.execute(action.request) },
        )

        is ReplayLatchedTransport -> LatchedTransportReplayCompleted(action.actionId)

        is ValidateResourceClass -> ResourceClassValidationCompleted(
            action.actionId,
            classGateRunner.run(action.gate, action.content),
        )

        is WriteStore -> StoreWriteCompleted(
            action.actionId,
            suppliedCall { store.write(action.rawKey, action.resource) },
        )

        is InstallVisibility -> VisibilityInstallCompleted(action.actionId, installVisibility(action.content))

        // Cancelling a route is internal bookkeeping, not a consumer exchange: it performs no Transport
        // or Store call and is never wrapped in suppliedCall. It reports completion unconditionally so
        // a sibling route that retireBufferedPrefix() is closing out -- because another route already
        // retired via Failure or Cancelled -- can be marked resolved even though nothing else will ever
        // produce an event for it.
        is CancelRoute -> CleanupCancellationObserved(action.ordinal)

        is ValidateBasemapStyle -> BasemapStyleValidationCompleted(
            action.actionId,
            validateBasemapStyle(action),
        )

        is CompileBasemapStyle -> BasemapStyleCompilationCompleted(
            action.actionId,
            compileBasemapStyle(action),
        )

        is WriteBasemapStyle -> BasemapStyleWriteCompleted(
            action.actionId,
            action.groupId,
            suppliedCall { store.write(action.rawKey, action.resource) },
        )

        is InstallBasemapStyleVisibility -> BasemapStyleVisibilityInstallCompleted(
            action.actionId,
            action.groupId,
            installVisibility(action.content),
        )

        else -> error("ResourceActionExecutor does not yet handle $action")
    }

    /**
     * Reads the style document RenG just acquired and answers what the engine will fetch while compiling
     * it. Entirely pure — no adapter call, no engine call, no clock — so it cannot be cancelled and
     * cannot fail for any reason outside the document itself.
     *
     * The manifest's base URI is the style's **own locator**, which is exactly what Rentile receives as
     * `StyleInput.Prefetched.baseUri` and resolves every relative reference against; deriving it from
     * anything else would compose different urls from the ones the engine composes, which the firewall
     * refuses.
     */
    private fun validateBasemapStyle(action: ValidateBasemapStyle): BasemapStyleValidationOutcome {
        val derivation = deriveBasemapStyleManifest(
            styleBytes = action.content.stored.bytes,
            baseUri = action.content.route.locator.value,
        )
        return when (derivation) {
            is BasemapStyleManifestOutcome.Rejected -> BasemapStyleValidationOutcome.Failed(derivation.kind)
            is BasemapStyleManifestOutcome.Derived -> BasemapStyleValidationOutcome.Valid(
                styleTimeRoutes(
                    manifest = derivation.manifest,
                    accessMode = action.content.route.accessMode,
                    limits = resourceLimits,
                ),
            )
        }
    }

    /**
     * Preregisters the manifest the validation of these exact bytes produced, then compiles them through
     * the engine. The order is load-bearing rather than tidy: the engine's sprite and GeoJSON acquisition
     * happens *inside* `preparedStyle`, so a route declared after it is declared too late.
     *
     * A [RenGException] here is the firewall's own, already sanitized and already carrying RenG's own
     * resource identity, so it is forwarded whole rather than collapsed into a
     * [com.rohittp.reng.internal.resource.StyleFailureKind] that would misreport it as a parse fault.
     * Any other throwable can only be a defect in RenG's own code — [BasemapEngineHost.engineCall]
     * converts every engine failure — so it is left to propagate rather than silently classified.
     * [CancellationException] is never caught, exactly as everywhere else in this class.
     */
    private suspend fun compileBasemapStyle(action: CompileBasemapStyle): BasemapStyleCompilationOutcome =
        try {
            basemapEngineHost.registerRoutes(action.routes)
            basemapEngineHost.preparedStyle(
                styleKey = action.content.resourceKey,
                stored = action.content.stored,
                baseUri = action.content.route.locator.value,
            )
            BasemapStyleCompilationOutcome.Succeeded
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (firewallFailure: RenGException) {
            BasemapStyleCompilationOutcome.EngineFailed(
                FailureDescriptor(
                    code = firewallFailure.code,
                    stage = firewallFailure.stage,
                    diagnostic = firewallFailure.diagnostics.firstOrNull(),
                ),
            )
        }

    /**
     * Installs [content] into visibility: one conceptual action, "install the generation and take the
     * owner's lease," performed as one atomic [ResidentCache] call so no observer can ever see a
     * freshly installed, zero-leased generation in a gap between two separate calls (see
     * [ResidentCache.installAndTakeLease]'s own KDoc for the concurrency hazard this closes).
     *
     * [ContentProvenance.RESIDENT] content is different in kind, not just in the cache call it needs:
     * it names a generation this route already observed resident earlier in its own lookup, not new
     * bytes to install, so it re-leases the CURRENT generation via [ResidentCache.observeAndTakeLease]
     * rather than installing a redundant new one. Everything else that reaches this point is content
     * this route freshly resolved (from the Store or Transport) and never installed anywhere, so it
     * installs a new generation.
     *
     * The returned [Lease] is recorded in [leaseSink] whenever the owner supplied one, so that owner can
     * release it once the content is no longer needed -- a [com.rohittp.reng.PreparedFrame] releasing its
     * own leases on `close()` is precisely that. With no sink the lease is still taken (it is what keeps
     * the generation alive) but not retained, which is the older behaviour every direct test of this class
     * relies on.
     *
     * A [ContentProvenance.RESIDENT] generation can genuinely vanish between when this route first
     * observed it and when visibility is installed here — a consumer's own concurrent
     * `free()`/`close()` call, or a competing route superseding the same key, are both real races this
     * architecture allows (ADR 0019's renderer mutex guards the cache, not the whole operation). That is
     * reported as a real, typed failure rather than a crash.
     */
    private fun installVisibility(content: ResolvedResourceContent): SuppliedInstallOutcome =
        when (content.provenance) {
            ContentProvenance.RESIDENT -> {
                val lease = cache.observeAndTakeLease(content.resourceKey)
                if (lease != null) {
                    recordLease(lease)
                    SuppliedInstallOutcome.Succeeded
                } else {
                    SuppliedInstallOutcome.Failed(residentGenerationVanishedFailure(content))
                }
            }

            ContentProvenance.STORE,
            ContentProvenance.TRANSPORT_200,
            ContentProvenance.TRANSPORT_304_MERGED,
            -> {
                // Two statements, and they cannot be collapsed into one. Writing the record as a safe call
                // on the sink -- `leaseSink?.add(cache.installAndTakeLease(...))` -- would take the install
                // with it: a Kotlin safe call short-circuits its whole argument list, so with no sink
                // supplied the install never runs at all and the resource is never made resident. The cache
                // call is therefore always evaluated into a local first, and the record is a plain call
                // that decides for itself whether there is a sink to write to.
                // DriverCancellationTest.contentAcquiredBeforeCancellationMayRemainResident is the test
                // that catches the collapsed form.
                val lease = cache.installAndTakeLease(content.resourceKey, content.stored, decoded = null)
                recordLease(lease)
                SuppliedInstallOutcome.Succeeded
            }
        }

    /**
     * Records one lease this executor just took into [leaseSink], or drops it when no owner asked for one.
     * See [leaseSinkMutex] for why the append is guarded, and [installVisibility] for why this is a plain
     * call rather than a safe call on the sink.
     */
    private fun recordLease(lease: Lease) {
        val sink = leaseSink ?: return
        while (!leaseSinkMutex.tryLock()) {
            // Uncontended in practice: one list append per resource per prepared frame.
        }
        try {
            sink.add(lease)
        } finally {
            leaseSinkMutex.unlock()
        }
    }

    /**
     * The resource this route selected as [ContentProvenance.RESIDENT] is no longer resident by the
     * time visibility is installed. Reuses [RenGErrorCode.RESOURCE_UNAVAILABLE] at
     * [PipelineStage.RESOURCE_LOOKUP] — the same (code, stage) pairing
     * [ResourceOperationStateMachine]'s own `resourceUnavailableFailure` uses for a `CACHE_ONLY` miss —
     * because both are the same fault: the resource this route needed is not available where it was
     * expected to be.
     */
    private fun residentGenerationVanishedFailure(content: ResolvedResourceContent): FailureDescriptor =
        FailureDescriptor(
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.RESOURCE_LOOKUP,
                fieldName = DiagnosticField.RESOURCE,
                resourceClass = content.route.resourceClass,
                resourceKey = content.resourceKey,
            ),
        )

    /**
     * Runs one suspending adapter call, collapsing any non-cancellation [Throwable] into a bare
     * [SuppliedCallOutcome.Failed] with no message, no cause, and no adapter-supplied context — the
     * adapter's own exception text can carry signed URLs or other consumer secrets, so nothing about it
     * may reach a diagnostic. [CancellationException] is rethrown unwrapped rather than converted, so
     * structured concurrency and Kotlin's own stack-trace recovery keep working.
     */
    private suspend fun <T> suppliedCall(block: suspend () -> T): SuppliedCallOutcome<T> = try {
        SuppliedCallOutcome.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        SuppliedCallOutcome.Failed
    }
}
