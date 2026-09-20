package com.rohittp.reng.internal.preparation

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.cache.ResidentGeneration
import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.DecodedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparationOwnershipTest {
    @Test
    fun cancellationJoinsThePublishedOuterWorker() = runTest {
        val coordinator = PreparationSessionCoordinator()
        val entered = CompletableDeferred<Unit>()
        var settled = false
        val invocation = launch {
            coordinator.run(
                onSettled = { settled = true },
                block = {
                    entered.complete(Unit)
                    awaitCancellation()
                },
            )
        }

        entered.await()
        coordinator.cancelSnapshotAndJoin()

        assertTrue(settled, "the cancellation barrier must include terminal session cleanup")
        invocation.join()
        assertTrue(invocation.isCancelled)
    }

    @Test
    fun completedValueCancelledBeforeAwaitDeliveryIsReclaimedBeforeSettlement() = runTest {
        val coordinator = PreparationSessionCoordinator()
        var reclaimed: String? = null
        var settledAfterReclaim = false

        assertFailsWith<CancellationException> {
            coordinator.run(
                onUndelivered = { reclaimed = it },
                onSettled = { settledAfterReclaim = reclaimed == "owned" },
                block = {
                    currentCoroutineContext().cancel()
                    "owned"
                },
            )
        }

        assertEquals("owned", reclaimed)
        assertTrue(settledAfterReclaim, "rollback must precede lifecycle settlement")
    }

    @Test
    fun settlementFailureReclaimsCompletedValueInsteadOfLosingOwnership() = runTest {
        val coordinator = PreparationSessionCoordinator()
        var reclaimed: String? = null

        val failure = assertFailsWith<IllegalStateException> {
            coordinator.run(
                onUndelivered = { reclaimed = it },
                onSettled = { error("settlement failed") },
                block = { "owned" },
            )
        }

        assertEquals("settlement failed", failure.message)
        assertEquals("owned", reclaimed)
    }

    @Test
    fun rawReservationsAreAtomicAndSingleUse() {
        val budget = RawTileBudget(10L)
        val first = requireNotNull(budget.tryReserve(7L))
        assertNull(budget.tryReserve(4L))
        assertEquals(7L, budget.outstandingBytes())

        first.release()
        first.release()

        assertEquals(0L, budget.outstandingBytes())
        val whole = requireNotNull(budget.tryReserve(10L))
        assertNull(budget.tryReserve(1L))
        whole.release()
        assertEquals(0L, budget.outstandingBytes())

        val maximum = RawTileBudget(Long.MAX_VALUE)
        val all = requireNotNull(maximum.tryReserve(Long.MAX_VALUE))
        assertNull(maximum.tryReserve(1L), "checked subtraction must refuse overflow")
        all.release()
    }

    @Test
    fun preparedFrameBudgetCountsSharedGenerationsOnceAndFramePayloadPerFrame() {
        val budget = PreparedFrameCpuBudget(13L)
        val shared = generation(id = 1L, name = "shared") // 4 raw + 4 decoded = 8 bytes.
        val unique = generation(id = 2L, name = "unique")

        val first = assertAdmitted(budget.tryReserve(listOf(shared, shared), frameOwnedBytes = 2L))
        assertEquals(10L, budget.outstandingBytes(), "duplicates within one frame must be charged once")

        val second = assertAdmitted(budget.tryReserve(listOf(shared), frameOwnedBytes = 3L))
        assertEquals(13L, budget.outstandingBytes(), "a shared generation is not charged again")

        val rejected = budget.tryReserve(listOf(unique), frameOwnedBytes = 0L)
        assertTrue(rejected is PreparedFrameCpuBudget.Admission.Rejected)
        assertEquals(21L, rejected.projectedBytes)
        assertEquals(13L, budget.outstandingBytes(), "rejection must not partially mutate accounting")

        first.release()
        first.release()
        assertEquals(11L, budget.outstandingBytes(), "single-use release removes only the first frame payload")
        second.release()
        assertEquals(0L, budget.outstandingBytes(), "the last sharing frame releases the generation")

        val replacement = assertAdmitted(budget.tryReserve(listOf(unique), frameOwnedBytes = 5L))
        assertEquals(13L, budget.outstandingBytes())
        replacement.release()
        assertEquals(0L, budget.outstandingBytes())
    }

    @Test
    fun preparedFrameBudgetRejectsOverflowInsteadOfWrapping() {
        val budget = PreparedFrameCpuBudget(Long.MAX_VALUE)
        val almostAll = assertAdmitted(budget.tryReserve(emptyList(), Long.MAX_VALUE))

        val rejected = budget.tryReserve(emptyList(), 1L)

        assertTrue(rejected is PreparedFrameCpuBudget.Admission.Rejected)
        assertEquals(Long.MAX_VALUE, rejected.projectedBytes)
        almostAll.release()
    }

    @Test
    fun preparedFrameBudgetCanAdmitExpansionBeforeTheGenerationAllocatesIt() {
        val budget = PreparedFrameCpuBudget(8L)
        val generation = generation(id = 3L, name = "projected", decoded = false)
        val first = assertAdmitted(budget.tryReserve(listOf(generation), frameOwnedBytes = 0L))
        assertEquals(4L, budget.outstandingBytes())

        assertEquals(
            PreparedFrameCpuBudget.Update.Accepted,
            first.tryProjectGeneration(generation, projectedByteSize = 8L),
        )
        assertEquals(8L, budget.outstandingBytes(), "the expansion is charged before attachment")
        generation.attachDecoded(DecodedImage(width = 1, height = 1, rgba = byteArrayOf(5, 6, 7, 8)))

        val second = assertAdmitted(budget.tryReserve(listOf(generation), frameOwnedBytes = 0L))
        assertEquals(8L, budget.outstandingBytes(), "the now-attached shared bytes are not charged twice")
        assertTrue(first.tryAddFrameOwned(1L) is PreparedFrameCpuBudget.Update.Rejected)

        first.release()
        assertEquals(8L, budget.outstandingBytes())
        second.release()
        assertEquals(0L, budget.outstandingBytes())
    }

    @Test
    fun abandoningAProjectedExpansionRestoresTheSharedGenerationsActualBytes() {
        val budget = PreparedFrameCpuBudget(8L)
        val generation = generation(id = 4L, name = "abandoned-projection", decoded = false)
        val retainedFrame = assertAdmitted(budget.tryReserve(listOf(generation), frameOwnedBytes = 0L))
        val projectingFrame = assertAdmitted(budget.tryReserve(listOf(generation), frameOwnedBytes = 0L))

        assertEquals(
            PreparedFrameCpuBudget.Update.Accepted,
            projectingFrame.tryProjectGeneration(generation, projectedByteSize = 8L),
        )
        assertEquals(8L, budget.outstandingBytes())

        projectingFrame.release()
        assertEquals(
            4L,
            budget.outstandingBytes(),
            "a failed decode must not leave its unapplied projection charged to another open frame",
        )
        retainedFrame.release()
        assertEquals(0L, budget.outstandingBytes())
    }

    private fun assertAdmitted(admission: PreparedFrameCpuBudget.Admission): PreparedFrameCpuBudget.Reservation {
        assertTrue(admission is PreparedFrameCpuBudget.Admission.Admitted)
        return admission.reservation
    }

    private fun generation(id: Long, name: String, decoded: Boolean = true): ResidentGeneration = ResidentGeneration(
        id = ResidentGenerationId(id),
        key = ResourceKeyDeriver().external(ResourceClass.STICKER_IMAGE, ResourceLocator(name)).key,
        stored = StoredRawResource(
            bytes = byteArrayOf(1, 2, 3, 4),
            contentDigest = id.toString().repeat(64),
            metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
        ),
        decoded = if (decoded) DecodedImage(width = 1, height = 1, rgba = byteArrayOf(5, 6, 7, 8)) else null,
    )
}
