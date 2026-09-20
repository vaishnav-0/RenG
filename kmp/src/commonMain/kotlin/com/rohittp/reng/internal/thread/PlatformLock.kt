package com.rohittp.reng.internal.thread

/**
 * A small blocking, reentrant lock for non-suspending renderer state.
 *
 * GL-bound calls are synchronous and can legitimately take hundreds of milliseconds, so their
 * serialization cannot be implemented by spinning on a coroutine mutex. Registry critical
 * sections use the same primitive and release it before invoking a GL binding.
 */
internal expect class PlatformLock() {
    internal fun <T> withLock(block: () -> T): T
}
