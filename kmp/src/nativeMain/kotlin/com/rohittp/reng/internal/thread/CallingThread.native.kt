package com.rohittp.reng.internal.thread

import kotlin.native.concurrent.ThreadLocal

/**
 * A token unique to the thread that first reads it, and stable for that thread's life.
 *
 * `@ThreadLocal` gives every thread its own copy of this object, so [token] is a distinct instance
 * per thread and reference identity answers "same thread?" exactly.
 *
 * Chosen over `pthread_self`, which is the obvious answer and does not survive being written once for
 * all five native targets: `pthread_t` is a `ULong` on Linux and a nullable `CPointer` on Darwin, so
 * a shared `nativeMain` actual cannot even name its type, and it drags in `ExperimentalForeignApi`
 * for a question that needs no foreign call at all.
 */
@ThreadLocal
private object ThreadToken {
    val token: Any = Any()
}

internal actual class CallingThread(private val token: Any) {
    internal fun isSame(): Boolean = ThreadToken.token === token
}

internal actual fun currentCallingThread(): CallingThread = CallingThread(ThreadToken.token)

internal actual fun CallingThread.isCurrentThread(): Boolean = isSame()
