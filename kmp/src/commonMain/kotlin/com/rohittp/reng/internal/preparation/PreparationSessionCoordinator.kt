package com.rohittp.reng.internal.preparation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * Publishes the one outer preparation worker owned by a renderer.
 *
 * The lifecycle state machine remains the authority for whether a preparation is active. This class
 * owns only the cancellable [Job] which spans planning, acquisition, frame construction, commit and
 * rollback. The worker is published before it is started, so a concurrent cancellation can never miss
 * a preparation which has begun touching renderer state.
 */
internal class PreparationSessionCoordinator {
    private val lock = PlatformLock()
    private var activeSession: ActiveSession? = null

    /**
     * Runs one published preparation session.
     *
     * [onUndelivered] owns a value which the worker produced but `await` could not deliver because its
     * caller was cancelled. For an undelivered result, [onSettled] runs after that rollback; for a
     * successful result it must itself succeed before ownership is handed off. In both cases it runs
     * before the session disappears from cancellation's view, and the renderer uses it to end the
     * lifecycle admission window.
     */
    suspend fun <T : Any> run(
        onUndelivered: (T) -> Unit = {},
        onSettled: () -> Unit = {},
        block: suspend () -> T,
    ): T {
        var stagedValue: T? = null
        var stagedReady = false
        var handedOff = false
        var settlementAttempted = false
        val completion = CompletableDeferred<Unit>()
        var session: ActiveSession? = null
        try {
            val value = supervisorScope {
                val worker = async(start = CoroutineStart.LAZY) {
                    val produced = block()
                    // Publication is deliberately allocation-free. Even if cancellation wins while
                    // the async machinery is completing, the coordinator can reclaim the ownership.
                    lock.withLock {
                        stagedValue = produced
                        stagedReady = true
                    }
                    produced
                }
                ActiveSession(worker, completion).also { published ->
                    session = published
                    lock.withLock {
                        check(activeSession == null) { "only one preparation worker may be active" }
                        activeSession = published
                    }
                    worker.start()
                }
                worker.await()
            }
            // Settlement is part of successful publication. If it throws, the value is still staged
            // and the finally below reclaims it rather than losing ownership between worker and caller.
            settlementAttempted = true
            onSettled()
            // There is no cancellable suspension between this linearization point and returning.
            handedOff = true
            return value
        } finally {
            val published = session
            val undelivered = lock.withLock {
                if (!handedOff && stagedReady) stagedValue else null
            }
            try {
                if (undelivered != null) onUndelivered(undelivered)
            } finally {
                try {
                    if (!settlementAttempted) {
                        settlementAttempted = true
                        onSettled()
                    }
                } finally {
                    lock.withLock {
                        stagedValue = null
                        stagedReady = false
                        if (activeSession === published) activeSession = null
                    }
                    completion.complete(Unit)
                }
            }
        }
    }

    suspend fun cancelSnapshotAndJoin() {
        val session = lock.withLock { activeSession } ?: return
        session.worker.cancel()
        session.completion.await()
    }

    private class ActiveSession(
        val worker: Job,
        val completion: CompletableDeferred<Unit>,
    )
}
