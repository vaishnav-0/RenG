package com.rohittp.reng.internal.preparation

import com.rohittp.reng.internal.thread.PlatformLock

/** Atomic accounting for raw basemap pixels retained by open prepared frames. */
internal class RawTileBudget(private val maximumBytes: Long) {
    private val lock = PlatformLock()
    private var outstandingBytes: Long = 0L

    init {
        require(maximumBytes >= 0L) { "maximumBytes must not be negative" }
    }

    internal class Reservation internal constructor(
        private val owner: RawTileBudget,
        val bytes: Long,
    ) {
        private var released: Boolean = false

        fun release() {
            owner.release(this)
        }

        internal fun markReleased(): Boolean {
            if (released) return false
            released = true
            return true
        }
    }

    fun tryReserve(bytes: Long): Reservation? {
        require(bytes >= 0L) { "reservation bytes must not be negative" }
        return lock.withLock {
            if (bytes > maximumBytes - outstandingBytes) return@withLock null
            outstandingBytes += bytes
            Reservation(this, bytes)
        }
    }

    internal fun outstandingBytes(): Long = lock.withLock { outstandingBytes }

    private fun release(reservation: Reservation) {
        lock.withLock {
            if (!reservation.markReleased()) return@withLock
            check(reservation.bytes <= outstandingBytes) { "raw tile budget cannot become negative" }
            outstandingBytes -= reservation.bytes
        }
    }

}
