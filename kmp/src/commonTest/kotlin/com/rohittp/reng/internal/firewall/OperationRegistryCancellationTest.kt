package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.rentile.ResourceClass as EngineResourceClass
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportRequestMetadata as EngineTransportRequestMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ADR 0050: the single-flight rendezvous latches what is true of a resource, and a cancellation is
 * not. Both cases here fail against the pre-0050 `SuspendJoin`, which latched a
 * `CancellationException` into an entry it never pruned and replayed it to every later caller.
 *
 * These are written now rather than alongside the batch widening they exist for, because the
 * widening is what makes an independently-cancelled caller ordinary — and a renderer where
 * cancelling one frame poisons every later frame touching the same tile must not exist even briefly.
 */
class OperationRegistryCancellationTest {
    @Test
    fun aCancelledRouteIsNotReplayedToTheNextCaller() = runTest {
        val transport = ScriptedTransport()
        val registry = registryFor(transport)

        // The owner is cancelled by the transport itself rather than by cancelling a job, so the
        // caller's own context stays active -- which is exactly the caller ADR 0050 protects.
        assertFailsWith<CancellationException> { registry.fetch() }

        val response = registry.fetch()

        assertEquals(200, response.statusCode)
        assertEquals(
            2,
            transport.calls,
            "the second caller must do the work itself, not inherit a cancellation it never earned",
        )
    }

    @Test
    fun aSiblingParkedAtTheRendezvousSurvivesTheOwnersCancellation() = runTest {
        val transport = GatedTransport()
        val registry = registryFor(transport)

        // The owner reaches the transport and parks there, holding the rendezvous.
        val owner = launch { registry.fetch() }
        yield()
        assertEquals(1, transport.calls, "the owner must be inside the transport before the joiner arrives")

        // The joiner parks on the owner's deferred: it is not the owner, so it never calls the
        // transport itself.
        val joiner = async { registry.fetch() }
        yield()
        assertEquals(1, transport.calls, "the joiner must be parked on the rendezvous, not fetching")

        owner.cancel()

        // The joiner's own job was never cancelled, so it must not inherit the owner's cancellation.
        // Before ADR 0050 this threw: `await()` replayed the latched CancellationException, and the
        // entry it came from was never pruned, so every later caller got it too.
        val response = joiner.await()
        assertEquals(200, response.statusCode)
        assertEquals(2, transport.calls, "the surviving joiner does the work the cancelled owner dropped")
    }

    @Test
    fun anOrdinaryFailureIsStillLatchedAndReplayedExactlyOnce() = runTest {
        val transport = FailingThenHealthyTransport()
        val registry = registryFor(transport)

        // A transport failure IS a verdict about the resource, so it stays latched: the second
        // caller gets the same refusal without a second exchange. Asserted here because ADR 0050
        // narrows the forgetting to cancellation alone, and a change that forgot every failure
        // would pass both cases above while silently turning one exchange into N.
        assertFailsWith<Throwable> { registry.fetch() }
        assertFailsWith<Throwable> { registry.fetch() }

        assertEquals(1, transport.calls, "a latched failure is replayed, never re-run")
    }
}

private const val ROUTE_URL: String = "https://example.invalid/cancellation-probe.pbf"

private fun registryFor(transport: Transport): OperationRegistry = OperationRegistry(
    transport = transport,
    store = NeverAnsweringStore(),
    privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
)

/** One preregistered route and the engine-side request that reaches it. */
private suspend fun OperationRegistry.fetch(): com.rohittp.rentile.TransportResponse {
    preregister(
        listOf(
            ResourceRouteKey(
                accessMode = ResourceAccessMode.NORMAL,
                locator = ResourceLocator(ROUTE_URL),
                resourceClass = ResourceClass.BASEMAP_VECTOR_TILE,
                maximumResponseBytes = 1L shl 20,
            ),
        ),
    )
    return executeTransport(
        EngineTransportRequest(
            url = ROUTE_URL,
            resourceClass = EngineResourceClass.VECTOR_TILE,
            maxResponseBytes = 1L shl 20,
            metadata = EngineTransportRequestMetadata(),
        ),
    )
}

private fun okResponse(): TransportResponse = TransportResponse(
    statusCode = 200,
    body = ByteArray(8) { it.toByte() },
    metadata = TransportResponseMetadata(contentType = "application/x-protobuf"),
)

/** Cancels its first caller and answers every one after it. */
private class ScriptedTransport : Transport {
    var calls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        calls += 1
        if (calls == 1) throw CancellationException("the first caller stopped waiting")
        return okResponse()
    }
}

/** Parks its first caller forever and answers every one after it. */
private class GatedTransport : Transport {
    private val gate = CompletableDeferred<Unit>()

    var calls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        calls += 1
        if (calls == 1) {
            gate.await()
            error("the gated caller is only ever released by cancellation")
        }
        return okResponse()
    }
}

/** Fails its first caller with an ordinary adapter fault, which is a verdict and stays latched. */
private class FailingThenHealthyTransport : Transport {
    var calls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        calls += 1
        if (calls == 1) throw IllegalStateException("the network is down")
        return okResponse()
    }
}

/** Answers no read and records no write: every case here is about the transport path alone. */
private class NeverAnsweringStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = Unit
}
