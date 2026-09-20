package com.rohittp.reng.internal.thread

import platform.Foundation.NSRecursiveLock

internal actual class PlatformLock {
    private val lock = NSRecursiveLock()

    internal actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
