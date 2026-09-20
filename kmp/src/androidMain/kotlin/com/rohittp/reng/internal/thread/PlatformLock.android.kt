package com.rohittp.reng.internal.thread

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class PlatformLock {
    private val lock = ReentrantLock()

    internal actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
