package com.rohittp.reng.internal.driver

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.firewall.basemapEngineHost
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.resource.CanonicalIdentityRecord
import com.rohittp.reng.internal.resource.ReadStore
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceActionId
import com.rohittp.reng.internal.resource.ResourceClassGate
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrence
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOwnerId
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.ResourceRouteRegistration
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cancellation through [PreparationDriver]: an external caller cancelling its own coroutine must stop a
 * `run()` in flight rather than complete it with a typed outcome (already true for free -- `run` opens
 * exactly one [kotlinx.coroutines.coroutineScope], so structured concurrency already reaches every
 * launched action), and an adapter's own [CancellationException] -- one that does NOT originate from this
 * driver's own coroutine being cancelled -- must be fed back to the state machine as an observation
 * ([com.rohittp.reng.internal.resource.SuppliedCallOutcome.Cancelled] and its
 * [com.rohittp.reng.internal.resource.SuppliedValidationOutcome]/
 * [com.rohittp.reng.internal.resource.SuppliedInstallOutcome] siblings) rather than left to vanish: a
 * `launch`ed child that completes via a `CancellationException` its own `Job` was never asked for is
 * silently absorbed by `coroutineScope`, which -- left unhandled -- means this driver's event loop waits
 * forever for an event that will never arrive. Cross-coroutine cancellation is intentionally owned by
 * the renderer's outer preparation-session coordinator; this driver only needs structured cancellation
 * from its caller to reach every action launched here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriverCancellationTest {
    @Test
    fun callerCancellationPropagatesUnwrappedAndStopsFurtherAdapterCalls() = runTest {
        val transport = CancellationCountingTransport(delayMillis = 1_000)
        val job = launch { driver(transport).run(manyRoutes()) }
        advanceTimeBy(10)
        job.cancel()
        job.join()
        val before = transport.executeCalls
        advanceTimeBy(5_000)
        assertEquals(before, transport.executeCalls, "no adapter call may start after cancellation")
    }

    @Test
    fun anAdapterCancellationIsNeverTranslatedIntoARenGFailure() = runTest {
        val transport = CancellationThrowingTransport(CancellationException("adapter cancelled"))
        val outcome = driver(transport).run(oneStickerRoute())
        assertIs<ResourceOperationOutcome.Cancelled>(outcome)
    }

    // Cycle C Task 15 kept its adapter-cancellation tests single-route specifically to avoid tripping
    // the CancelRoute crash carried into Cycle F-1: retireBufferedPrefix() calls cancelActiveRoutes(...)
    // on every OTHER active ordinal once one route retires via ResourceRouteOutcome.Cancelled, and
    // ResourceActionExecutor.execute had no branch for the resulting CancelRoute action at all -- an
    // unhandled `else -> error(...)`. Two DISTINCT locators are essential here: a same-key fixture would
    // be merged into one RouteRecord at preRegister and could never exercise the multi-route path (see
    // replaysALatchedOutcomeWithoutASecondExchange's own comment on that exact trap).
    @Test
    fun aMultiRouteOperationSurvivesOneRouteObservingAdapterCancellation() = runTest {
        val cancellingRegistration = registration("cancelling")
        val survivingRegistration = registration("surviving")
        val transport = FirstThrowsAdapterCancellationTransport(cancellingRegistration.route.locator)
        val definition = definitionOf(
            occurrence(1L, cancellingRegistration),
            occurrence(2L, survivingRegistration),
            maximumConcurrentRoutes = 2,
        )

        val outcome = driver(transport).run(definition)

        // The crash was an error(...) fallthrough in the executor, not an assertion failure, so the
        // meaningful claim is that we reach a typed outcome at all.
        assertIs<ResourceOperationOutcome.Cancelled>(outcome)
    }

    // The brief this task was written from names this `driver(engine = ClosingEngine())`, modelling a
    // rasterizer whose close() cancels its own internal job and surfaces that to in-flight work as a
    // plain CancellationException (see rentile's DefaultBasemapRasterizer.close(), which does exactly
    // this: `rootJob.cancel(CancellationException("Rentile rasterizer closed"))`). No rasterizer/engine
    // type exists anywhere in this tree yet -- that is Task 19's territory -- and ClassGateRunner's own
    // KDoc already names itself as the seam a later task will route the Rentile firewall's outcome
    // through. So this fakes that one seam directly instead of inventing rasterizer plumbing this task
    // does not own; the assertion this test exists to pin -- a bare adapter-level CancellationException
    // from ANY call site, not just Transport, must surface as Cancelled and never as a typed Failure --
    // is identical either way.
    @Test
    fun aClosedRasterizerCancellationIsCancellationNotAFailure() = runTest {
        val outcome = driver(classGateRunner = ClosingClassGateRunner()).run(oneStickerRoute())
        assertIs<ResourceOperationOutcome.Cancelled>(outcome)
    }

    // The brief calls `driver(cache = cache)` with no transport, relying on some default. A default fast
    // enough to resolve both routes before the caller ever gets a chance to cancel would prove nothing
    // about content "acquired before cancellation" -- the whole run would already be done. This supplies
    // the one transport shape the assertion actually needs: the "first" route's request resolves
    // immediately (so it can install into the cache and retire before anything is cancelled), and the
    // "second" route's request never resolves at all (so the run is still genuinely in flight when the
    // caller cancels) -- while keeping the cache, the fixture, and the assertion exactly as given.
    @Test
    fun contentAcquiredBeforeCancellationMayRemainResident() = runTest {
        val cache = ResidentCache()
        val transport = FirstCompletesSecondHangsTransport(registration("first").route.locator)
        val job = launch { driver(transport, cache = cache).run(twoRoutesWhereFirstCompletes()) }
        advanceTimeBy(50)
        job.cancel()
        job.join()
        // CONTEXT.md permits this explicitly: cancellation exposes no partial history, but valid
        // acquired content may remain cached.
        assertNotNull(cache.current(firstRouteKey))
    }

    // A sharper, unit-level companion to the first test above, in the same spirit as
    // PreparationDriverTest's own `theExecutorPropagatesAnAdapterCancellationRatherThanConvertingItToAnEvent`.
    // Driving cancellation through a whole `run()` cannot tell "the isActive guard is present" apart from
    // "it was deleted": once a Job is genuinely cancelled, Kotlin forces that coroutine to complete as
    // Cancelled regardless of what user code catches internally, so job.isCancelled and "no further
    // adapter calls" hold either way -- confirmed by mutation testing this exact guard. Calling
    // executeObservingAdapterCancellation directly, against a coroutine whose own Job this test cancels
    // itself, makes the one difference the guard exists for externally observable: with the guard, the
    // rethrown CancellationException means the line after the call never runs; without it, the adapter's
    // CancellationException would be swallowed and converted to an event, and that line WOULD run.
    @Test
    fun aGenuinelyCancelledJobRethrowsRatherThanConvertingAdapterCancellationToAnEvent() = runTest {
        val executor = ResourceActionExecutor(
            transport = CancellationCountingTransport(),
            store = HangingStore(),
            cache = ResidentCache(),
            classGateRunner = RenGClassGateRunner(ResourceLimits()),
            resourceLimits = ResourceLimits(),
            basemapEngineHost = basemapEngineHost(),
            clock = CancellationFixedClock,
        )
        val action = ReadStore(ResourceActionId(1L), 0L, RawResourceKey("raw", ResourceClass.STICKER_IMAGE))
        var convertedToEvent = false
        val job = launch {
            try {
                executeObservingAdapterCancellation(executor, action)
                convertedToEvent = true
            } catch (_: CancellationException) {
                // Expected: this coroutine's own Job is genuinely cancelled below, so the
                // CancellationException the hanging Store.read() surfaces must propagate unwrapped.
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertFalse(convertedToEvent, "a genuinely cancelled Job must rethrow, never convert to an event")
    }
}

// ---- driver + fixture wiring -------------------------------------------------------------------

private fun driver(
    transport: Transport = CancellationCountingTransport(),
    store: Store = CancellationCountingStore(),
    cache: ResidentCache = ResidentCache(),
    classGateRunner: ClassGateRunner = RenGClassGateRunner(ResourceLimits()),
    maximumConcurrentOperations: Int = 8,
    clock: () -> Long = CancellationFixedClock,
): PreparationDriver = PreparationDriver(
    transport = transport,
    store = store,
    cache = cache,
    classGateRunner = classGateRunner,
    resourceLimits = ResourceLimits(),
    basemapEngineHost = basemapEngineHost(cache = cache),
    maximumConcurrentOperations = maximumConcurrentOperations,
    clock = clock,
)

private object CancellationFixedClock : () -> Long {
    override fun invoke(): Long = 1_700_000_000_000L
}

/** Counts every [Store.read] and [Store.write] call. Reads always miss so a route must go on to
 *  Transport; writes always succeed. */
private class CancellationCountingStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = Unit
}

/** Counts every [Transport.execute] call and answers every request with a 200 and a valid sticker PNG,
 *  after [delayMillis] of (cancellable) [delay] -- long enough that a test driving cancellation mid-flight
 *  observes the call still pending. */
private class CancellationCountingTransport(private val delayMillis: Long = 0L) : Transport {
    var executeCalls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        if (delayMillis > 0L) delay(delayMillis)
        return response(200)
    }
}

/** Always throws [throwable] from [execute]. */
private class CancellationThrowingTransport(private val throwable: Throwable) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse = throw throwable
}

/** [cancellingLocator]'s own request throws a bare [CancellationException], exactly as an adapter would
 *  if it cancelled itself; every other request resolves normally with a 200 and a valid PNG, so that
 *  sibling route keeps progressing through its own remaining pipeline steps (class validation, store
 *  write, install visibility) rather than retiring in the same step -- it is still active, not yet
 *  retired, at the moment the cancelling route's own outcome retires first and reaches
 *  cancelActiveRoutes. */
private class FirstThrowsAdapterCancellationTransport(private val cancellingLocator: ResourceLocator) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse =
        if (request.locator == cancellingLocator) throw CancellationException("adapter cancelled itself")
        else response(200)
}

/** Never returns from [read] on its own; it only ever ends by the calling coroutine's own Job being
 *  cancelled -- exactly the shape needed to observe a genuinely cancelled Job's own cancellation, rather
 *  than an adapter's unsolicited one, reach [executeObservingAdapterCancellation]'s catch. */
private class HangingStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = awaitCancellation()

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        error("not used by this fixture")
    }
}

/** Resolves [fastLocator]'s own request immediately; every other request never resolves at all. */
private class FirstCompletesSecondHangsTransport(private val fastLocator: ResourceLocator) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse =
        if (request.locator == fastLocator) response(200) else awaitCancellation()
}

/** Simulates a Rentile rasterizer's close() surfacing to in-flight work as a plain CancellationException
 *  (see this file's own KDoc on [DriverCancellationTest.aClosedRasterizerCancellationIsCancellationNotAFailure]) --
 *  the general rule this class-gate seam must honour just as much as a Transport/Store call does. */
private class ClosingClassGateRunner : ClassGateRunner {
    override suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome =
        throw CancellationException("Rentile rasterizer closed")
}

private fun response(status: Int): TransportResponse = TransportResponse(
    statusCode = status,
    body = validStickerPng,
    metadata = TransportResponseMetadata(freshUntilEpochMillis = 1_700_000_100_000L),
)

// A real, valid 2x2 truecolour PNG (colour type 2), generated once via CPython's zlib/struct/
// zlib.crc32 modules exactly as PngDecoderTest.kt documents, so every fixture's own STICKER_IMAGE
// content genuinely passes DECODE_PNG rather than merely resembling bytes.
private val validStickerPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

// ---- ResourceOperationDefinition fixtures -------------------------------------------------------

private fun oneStickerRoute(): ResourceOperationDefinition = definitionOf(occurrence(1L, registration("a")))

private fun manyRoutes(): ResourceOperationDefinition {
    val occurrences = (1..4).map { index -> occurrence(index.toLong(), registration("many-$index")) }
    return definitionOf(*occurrences.toTypedArray(), maximumConcurrentRoutes = 4)
}

private fun twoRoutesWhereFirstCompletes(): ResourceOperationDefinition = definitionOf(
    occurrence(1L, registration("first")),
    occurrence(2L, registration("second")),
    maximumConcurrentRoutes = 2,
)

private val firstRouteKey: ResourceKey = registration("first").resourceKey

private fun definitionOf(
    vararg occurrences: ResourceOccurrence,
    maximumConcurrentRoutes: Int = 8,
): ResourceOperationDefinition = ResourceOperationDefinition(
    maximumConcurrentRoutes = maximumConcurrentRoutes,
    staticOccurrences = occurrences.toList(),
    resourceIdentities = occurrences.map {
        CanonicalIdentityRecord(it.registration.resourceKey, it.registration.canonicalBytes)
    },
)

private fun occurrence(
    id: Long,
    registration: ResourceRouteRegistration,
    ownerId: Long = id + 1000L,
): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(id),
    ownerId = ResourceOwnerId(ownerId),
    registration = registration,
    discoveryRequired = false,
    commitBinding = ResourceCommitBinding.Single,
)

private fun registration(marker: String): ResourceRouteRegistration {
    val stableId = marker.hashCode().toUInt().toString(16).padStart(64, '0').takeLast(64)
    return ResourceRouteRegistration(
        route = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = ResourceLocator("locator-$marker"),
            resourceClass = ResourceClass.STICKER_IMAGE,
            maximumResponseBytes = 4096L,
        ),
        resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.STICKER_IMAGE),
        rawKey = RawResourceKey("raw-$marker", ResourceClass.STICKER_IMAGE),
        privateRentileKey = RentilePrivateKey("private-$marker"),
        canonicalBytes = CanonicalBytes("canonical-$marker".encodeToByteArray()),
    )
}
