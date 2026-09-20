package com.rohittp.reng.internal.preparation

import com.rohittp.reng.internal.cache.ResidentGeneration
import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * Hard aggregate admission for CPU payload retained by open prepared frames.
 *
 * A resident generation is charged once while any admitted frame leases it, irrespective of how many
 * frames or repeated occurrences name it. [frameOwnedBytes] is charged per frame: unlike a generation,
 * raw/encoded basemap pixels, terrain pixels, poses and label payloads have no renderer-wide identity
 * through which another frame can prove sharing.
 */
internal class PreparedFrameCpuBudget(private val maximumBytes: Long) {
    private val lock = PlatformLock()
    private val generations: MutableMap<ResidentGenerationId, GenerationAccount> = mutableMapOf()
    private var outstandingBytes: Long = 0L

    init {
        require(maximumBytes >= 0L) { "maximumBytes must not be negative" }
    }

    internal sealed interface Admission {
        class Admitted(val reservation: Reservation) : Admission
        class Rejected(val projectedBytes: Long) : Admission
    }

    internal sealed interface Update {
        data object Accepted : Update
        class Rejected(val projectedBytes: Long) : Update
    }

    internal class Reservation internal constructor(
        private val owner: PreparedFrameCpuBudget,
        internal val generationClaims: MutableList<GenerationClaim> = mutableListOf(),
        internal var frameOwnedBytes: Long = 0L,
    ) {
        private var released: Boolean = false

        fun tryAddGeneration(generation: ResidentGeneration): Update =
            owner.tryUpdate(
                this,
                mapOf(generation.id to GenerationProjection(generation, generation.byteSize)),
                frameOwnedBytes = 0L,
            )

        fun tryAddGenerations(generations: Collection<ResidentGeneration>): Update =
            owner.tryUpdate(this, owner.projectionsFor(generations), frameOwnedBytes = 0L)

        /**
         * Charges [projectedByteSize] before a known expansion is allocated. It may exceed the
         * generation's current [ResidentGeneration.byteSize]; attachment immediately after admission
         * reconciles the object to the already-reserved figure without a transient uncharged raster.
         */
        fun tryProjectGeneration(generation: ResidentGeneration, projectedByteSize: Long): Update {
            require(projectedByteSize >= generation.byteSize) {
                "projected generation bytes must cover the generation's current bytes"
            }
            return owner.tryUpdate(this, mapOf(generation.id to GenerationProjection(generation, projectedByteSize)), 0L)
        }

        fun tryAddFrameOwned(bytes: Long): Update = owner.tryUpdate(this, emptyMap(), bytes)

        fun release() {
            owner.release(this)
        }

        internal fun markReleased(): Boolean {
            if (released) return false
            released = true
            return true
        }

        internal fun requireOpen() {
            check(!released) { "cannot update a released prepared-frame reservation" }
        }
    }

    fun tryReserve(
        leasedGenerations: Collection<ResidentGeneration>,
        frameOwnedBytes: Long,
    ): Admission {
        val reservation = Reservation(owner = this)
        return when (
            val update = tryUpdate(
                reservation = reservation,
                projections = leasedGenerations.associateWithProjectedBytes(),
                frameOwnedBytes = frameOwnedBytes,
            )
        ) {
            Update.Accepted -> Admission.Admitted(reservation)
            is Update.Rejected -> Admission.Rejected(update.projectedBytes)
        }
    }

    internal fun outstandingBytes(): Long = lock.withLock { outstandingBytes }

    private fun tryUpdate(
        reservation: Reservation,
        projections: Map<ResidentGenerationId, GenerationProjection>,
        frameOwnedBytes: Long,
    ): Update {
        require(frameOwnedBytes >= 0L) { "frame-owned bytes must not be negative" }
        return lock.withLock {
            reservation.requireOpen()
            var additionalBytes = frameOwnedBytes
            var additionalOverflowed = false
            projections.forEach { (id, projection) ->
                val accounted = generations[id]?.bytes ?: 0L
                val additionalGenerationBytes = maxOf(0L, projection.bytes - accounted)
                if (additionalGenerationBytes > Long.MAX_VALUE - additionalBytes) {
                    additionalOverflowed = true
                    additionalBytes = Long.MAX_VALUE
                } else {
                    additionalBytes += additionalGenerationBytes
                }
            }
            val projectedOverflowed = additionalOverflowed || additionalBytes > Long.MAX_VALUE - outstandingBytes
            val projected = if (projectedOverflowed) Long.MAX_VALUE else outstandingBytes + additionalBytes
            if (projectedOverflowed || projected > maximumBytes) return@withLock Update.Rejected(projected)

            projections.forEach { (id, projection) ->
                val account = generations[id]
                val claim = reservation.claimFor(id)
                if (account == null) {
                    check(claim == null) { "a reserved generation must remain globally accounted" }
                    val added = GenerationClaim(id, projection.bytes)
                    reservation.generationClaims += added
                    generations[id] = GenerationAccount(
                        generation = projection.generation,
                        claims = mutableListOf(added),
                        bytes = projection.bytes,
                    )
                } else {
                    check(account.generation === projection.generation) {
                        "one resident generation identity must name one generation"
                    }
                    if (claim == null) {
                        val added = GenerationClaim(id, projection.bytes)
                        reservation.generationClaims += added
                        account.claims += added
                    } else {
                        claim.projectedBytes = maxOf(claim.projectedBytes, projection.bytes)
                    }
                    account.bytes = maxOf(account.bytes, projection.bytes)
                }
            }
            reservation.frameOwnedBytes += frameOwnedBytes
            outstandingBytes = projected
            Update.Accepted
        }
    }

    private fun release(reservation: Reservation) {
        lock.withLock {
            if (!reservation.markReleased()) return@withLock
            var releasedBytes = reservation.frameOwnedBytes
            var claimIndex = 0
            while (claimIndex < reservation.generationClaims.size) {
                val claim = reservation.generationClaims[claimIndex]
                val account = checkNotNull(generations[claim.id]) { "reserved generation must remain accounted" }
                val accountClaimIndex = account.claims.indexOf(claim)
                check(accountClaimIndex >= 0) { "reservation claim must remain globally accounted" }
                account.claims.removeAt(accountClaimIndex)
                if (account.claims.isEmpty()) {
                    generations.remove(claim.id)
                    releasedBytes = saturatedAdd(releasedBytes, account.bytes)
                } else {
                    var retainedBytes = account.generation.byteSize
                    var retainedClaimIndex = 0
                    while (retainedClaimIndex < account.claims.size) {
                        retainedBytes = maxOf(retainedBytes, account.claims[retainedClaimIndex].projectedBytes)
                        retainedClaimIndex += 1
                    }
                    check(retainedBytes <= account.bytes) {
                        "releasing a claim cannot grow prepared-frame accounting"
                    }
                    releasedBytes = saturatedAdd(releasedBytes, account.bytes - retainedBytes)
                    account.bytes = retainedBytes
                }
                claimIndex += 1
            }
            check(releasedBytes <= outstandingBytes) { "prepared-frame CPU budget cannot become negative" }
            outstandingBytes -= releasedBytes
        }
    }

    private class GenerationAccount(
        val generation: ResidentGeneration,
        val claims: MutableList<GenerationClaim>,
        var bytes: Long,
    )

    internal class GenerationClaim(
        val id: ResidentGenerationId,
        var projectedBytes: Long,
    )

    private class GenerationProjection(val generation: ResidentGeneration, val bytes: Long)

    private fun projectionsFor(
        generations: Collection<ResidentGeneration>,
    ): Map<ResidentGenerationId, GenerationProjection> = generations.associateWithProjectedBytes()

    private fun Reservation.claimFor(id: ResidentGenerationId): GenerationClaim? {
        var index = 0
        while (index < generationClaims.size) {
            val claim = generationClaims[index]
            if (claim.id == id) return claim
            index += 1
        }
        return null
    }

    private fun Collection<ResidentGeneration>.associateWithProjectedBytes(): Map<ResidentGenerationId, GenerationProjection> {
        val projections = LinkedHashMap<ResidentGenerationId, GenerationProjection>()
        forEach { generation ->
            val previous = projections.put(generation.id, GenerationProjection(generation, generation.byteSize))
            check(previous == null || previous.generation === generation) {
                "one resident generation identity must name one generation"
            }
        }
        return projections
    }
}

private fun saturatedAdd(left: Long, right: Long): Long {
    require(left >= 0L && right >= 0L) { "byte counts must not be negative" }
    return if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
