package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals

class GlOperationGateTest {
    @Test
    fun theGateIsReentrant() {
        val gate = GlOperationGate()
        var calls = 0

        gate.run {
            calls += 1
            gate.run { calls += 1 }
        }

        assertEquals(2, calls)
    }
}
