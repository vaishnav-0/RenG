package com.rohittp.reng.internal.thread

/** Reference identity: a `Thread` is unique while it is alive, which is the whole window that matters. */
internal actual class CallingThread(private val thread: Thread) {
    internal fun isSame(): Boolean = Thread.currentThread() === thread
}

internal actual fun currentCallingThread(): CallingThread = CallingThread(Thread.currentThread())

internal actual fun CallingThread.isCurrentThread(): Boolean = isSame()
