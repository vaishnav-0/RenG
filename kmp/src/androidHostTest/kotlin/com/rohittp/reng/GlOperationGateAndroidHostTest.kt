package com.rohittp.reng.internal.gl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlOperationGateAndroidHostTest {
    @Test
    fun aSecondThreadBlocksUntilTheCompleteOperationReturns() {
        val gate = GlOperationGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val order = mutableListOf<String>()

        val first = thread {
            gate.run {
                order += "first-enter"
                firstEntered.countDown()
                releaseFirst.await()
                order += "first-exit"
            }
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val second = thread {
            gate.run {
                order += "second-enter"
                secondEntered.countDown()
            }
        }

        assertTrue(!secondEntered.await(100, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        first.join()
        second.join()

        assertEquals(listOf("first-enter", "first-exit", "second-enter"), order)
    }
}
