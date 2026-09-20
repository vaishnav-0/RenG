@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package com.rohittp.reng.internal.thread

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlin.native.ref.createCleaner
import platform.posix.PTHREAD_MUTEX_RECURSIVE
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t

internal actual class PlatformLock {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        val attributes = nativeHeap.alloc<pthread_mutexattr_t>()
        check(pthread_mutexattr_init(attributes.ptr) == 0) { "failed to initialise mutex attributes" }
        try {
            check(pthread_mutexattr_settype(attributes.ptr, PTHREAD_MUTEX_RECURSIVE.toInt()) == 0) {
                "failed to configure a recursive mutex"
            }
            check(pthread_mutex_init(mutex.ptr, attributes.ptr) == 0) { "failed to initialise mutex" }
        } finally {
            pthread_mutexattr_destroy(attributes.ptr)
            nativeHeap.free(attributes.ptr.rawValue)
        }
    }

    @Suppress("unused")
    private val cleaner = createCleaner(mutex.ptr) { pointer ->
        pthread_mutex_destroy(pointer)
        nativeHeap.free(pointer.rawValue)
    }

    internal actual fun <T> withLock(block: () -> T): T {
        check(pthread_mutex_lock(mutex.ptr) == 0) { "failed to acquire mutex" }
        try {
            return block()
        } finally {
            check(pthread_mutex_unlock(mutex.ptr) == 0) { "failed to release mutex" }
        }
    }
}
