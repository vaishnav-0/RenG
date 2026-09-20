package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.thread.PlatformLock

/** Serializes complete synchronous GL/lifecycle transitions across real calling threads. */
internal class GlOperationGate {
    private val lock = PlatformLock()

    internal fun <T> run(block: () -> T): T = lock.withLock(block)
}
