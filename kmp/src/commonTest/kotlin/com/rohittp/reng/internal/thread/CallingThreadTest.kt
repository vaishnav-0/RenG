package com.rohittp.reng.internal.thread

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CallingThreadTest {
    @Test
    fun theThreadThatTookTheMarkerRecognisesItself() {
        val here = currentCallingThread()
        assertTrue(here.isCurrentThread())
        // Taken twice on one thread, both must still answer for that thread -- the marker identifies
        // a thread, not a call.
        assertTrue(currentCallingThread().isCurrentThread())
        assertTrue(here.isCurrentThread())
    }

    @Test
    fun anotherThreadDoesNotRecogniseIt() = runTest {
        val here = currentCallingThread()

        // Dispatchers.Default is a real thread pool on every target RenG ships, so this genuinely
        // leaves the test's thread rather than merely suspending on it.
        val recognisedElsewhere = withContext(Dispatchers.Default) { here.isCurrentThread() }

        assertFalse(recognisedElsewhere, "a marker must not answer for a thread that did not take it")
        assertTrue(here.isCurrentThread(), "and must still answer for the one that did")
    }

    @Test
    fun eachThreadGetsAMarkerOfItsOwn() = runTest {
        val here = currentCallingThread()
        val there = withContext(Dispatchers.Default) { currentCallingThread() }

        assertTrue(here.isCurrentThread())
        assertFalse(there.isCurrentThread(), "the other thread's marker must not answer here")
    }
}
