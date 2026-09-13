package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceFreeResult
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceResidency
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.GpuByteAccount
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.DecodedImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResidentCacheTest {
    @Test
    fun onlyTheCurrentGenerationSatisfiesALookup() {
        val cache = ResidentCache()
        val first = cache.install(key, storedA, null)
        val lease = cache.takeLease(first)
        val second = cache.install(key, storedB, null)
        assertEquals(second, cache.current(key))
        // The superseded generation stays usable while leased.
        assertEquals(
            2,
            cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().residentGenerationCount,
        )
        cache.releaseLease(lease)
        assertEquals(
            1,
            cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().residentGenerationCount,
        )
    }

    @Test
    fun freeRetiresEveryGenerationAndDefersThoseStillLeased() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val lease = cache.takeLease(generation)
        val result = cache.free(ResourceSelector.ByKey(key))
        assertEquals(
            ResourceFreeResult(matchedKeys = 1, fullyFreedKeys = 0, deferredKeys = 1, alreadyFreeKeys = 0),
            result,
        )
        assertNull(cache.current(key))
        cache.releaseLease(lease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().retiredGenerationCount)
    }

    @Test
    fun freeWithNoLeaseReportsFullyFreedAndASecondFreeReportsAlreadyFree() {
        val cache = ResidentCache()
        cache.install(key, storedA, null)
        assertEquals(1, cache.free(ResourceSelector.ByKey(key)).fullyFreedKeys)
        assertEquals(1, cache.free(ResourceSelector.ByKey(key)).alreadyFreeKeys)
    }

    @Test
    fun aFreedKeyIsDistinguishableFromOneNeverLoaded() {
        val cache = ResidentCache()
        assertFalse(cache.wasFreed(key))
        cache.install(key, storedA, null)
        cache.free(ResourceSelector.ByKey(key))
        assertTrue(cache.wasFreed(key))
        assertTrue(cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().reloadRequired)
    }

    @Test
    fun aRetiredGenerationIsNeverResurrectedByIdenticalBytes() {
        val cache = ResidentCache()
        val first = cache.install(key, storedA, null)
        val lease = cache.takeLease(first)
        cache.free(ResourceSelector.ByKey(key))
        val reloaded = cache.install(key, storedA, null)
        assertNotSame(first, reloaded)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().retiredGenerationCount)
        cache.releaseLease(lease)
    }

    @Test
    fun manyLeasesShareOneGeneration() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val leases = List(8) { cache.takeLease(generation) }
        assertEquals(8, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().leaseCount)
        leases.forEach(cache::releaseLease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().leaseCount)
    }

    @Test
    fun reportAccountsRawAndDecodedBytesWithNoGpuAllocation() {
        val cache = ResidentCache()
        cache.install(key, storedA, decodedOf(64))
        val entry = cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single()
        assertEquals(storedA.bytes.size.toLong(), entry.usage.rawBytes)
        assertEquals(64L, entry.usage.decodedCpuBytes)
        assertEquals(0L, entry.usage.knownGpuBytes)
        assertFalse(entry.usage.hasUnknownGpuBytes)
    }

    @Test
    fun concurrentLeaseAndFreeLinearizeAtTheStateBoundary() = runTest {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        // Free racing the last lease release must report one or the other, never both and never
        // neither: deferred if free wins, fully freed if the release wins.
        val lease = cache.takeLease(generation)
        val results = listOf(
            async { cache.free(ResourceSelector.ByKey(key)) },
            async { cache.releaseLease(lease); null },
        ).awaitAll()
        val free = results.filterIsInstance<ResourceFreeResult>().single()
        assertEquals(1, free.deferredKeys + free.fullyFreedKeys)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().leaseCount)
    }

    // Task 13's atomic install-and-lease / observe-and-lease methods close a linearization gap a
    // separate install()/current() then takeLease() pair leaves open: a free() landing in the window
    // between the two calls can retire a zero-lease generation. In this class's reference-identity
    // model, takeLease() mutates the exact object it is handed and never consults `entries`, so it
    // never rejects a stale reference the way an id-keyed lookup could -- the gap instead manifests
    // as a lease taken successfully on a generation this cache has already dropped from its own
    // bookkeeping, invisible to report() and unreachable by any later free(). These tests are ported
    // from the stand-in ResidentCache that found the gap, adapted to this model.
    @Test
    fun installAndTakeLeaseClosesTheGapASeparateInstallThenLeaseSequenceLeavesOpen() {
        val cache = ResidentCache()

        // Reproduce the gap: a free() landing between install() and takeLease() drops the zero-lease
        // generation from this cache's bookkeeping entirely before the lease is taken.
        val orphaned = cache.install(key, storedA, null)
        cache.free(ResourceSelector.ByKey(key))
        val orphanedLease = cache.takeLease(orphaned)
        assertEquals(
            0,
            cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().leaseCount,
            "a lease taken after the drop is invisible to this cache's own accounting",
        )
        cache.releaseLease(orphanedLease) // must not throw even though untracked

        // installAndTakeLease closes the gap: the lease is taken atomically with the install, so a
        // free() run immediately afterward can only retire (defer) the generation, never drop it.
        val lease = cache.installAndTakeLease(key, storedB, null)
        val freeResult = cache.free(ResourceSelector.ByKey(key))
        assertEquals(1, freeResult.deferredKeys, "the leased generation must be deferred, never dropped")
        assertEquals(0, freeResult.fullyFreedKeys)
        cache.releaseLease(lease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().retiredGenerationCount)
    }

    @Test
    fun observeAndTakeLeaseReturnsNullWithNothingResidentAndLeasesWhatIsThere() {
        val cache = ResidentCache()
        assertNull(cache.observeAndTakeLease(key))

        val generation = cache.install(key, storedA, null)
        val lease = cache.observeAndTakeLease(key)
        assertSame(generation, requireNotNull(lease).generation)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().leaseCount)
        cache.releaseLease(lease)
    }

    @Test
    fun selectorsMatchAllByKindByClassAndByKey() {
        val cache = ResidentCache()
        cache.install(externalStickerKey, storedA, null)
        cache.install(externalModelKey, storedB, null)
        assertEquals(2, cache.report(ResourceSelector.All, noGpuObjects).entries.size)
        assertEquals(2, cache.report(ResourceSelector.ByKind(ResourceKind.EXTERNAL), noGpuObjects).entries.size)
        assertEquals(1, cache.report(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE), noGpuObjects).entries.size)
        assertEquals(1, cache.report(ResourceSelector.ByKey(externalStickerKey), noGpuObjects).entries.size)
    }

    // The tests below are additions beyond the brief's Step 1 list, closing gaps a mutation-test pass
    // over the Step 1 suite found: each guards a branch none of the tests above can distinguish from its
    // absence.

    @Test
    fun aRetiredGenerationWithMultipleLeasesSurvivesUntilTheLastIsReleased() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val firstLease = cache.takeLease(generation)
        val secondLease = cache.takeLease(generation)
        cache.install(key, storedB, null)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().retiredGenerationCount)

        cache.releaseLease(firstLease)
        // One outstanding lease remains: releasing a generation's lease count to a positive remainder
        // must not evict it early.
        assertEquals(1, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().retiredGenerationCount)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().leaseCount)

        cache.releaseLease(secondLease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().retiredGenerationCount)
    }

    @Test
    fun installAfterFreeClearsTheReloadMarker() {
        val cache = ResidentCache()
        cache.install(key, storedA, null)
        cache.free(ResourceSelector.ByKey(key))
        assertTrue(cache.wasFreed(key))

        cache.install(key, storedB, null)
        assertFalse(cache.wasFreed(key))
        assertFalse(cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().reloadRequired)
    }

    @Test
    fun releasingTheSameLeaseTwiceIsRejected() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val lease = cache.takeLease(generation)
        cache.releaseLease(lease)
        assertFailsWith<IllegalArgumentException> { cache.releaseLease(lease) }
    }

    @Test
    fun closeAllDropsEveryEntryRegardlessOfLeases() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        cache.takeLease(generation)

        cache.closeAll()

        assertNull(cache.current(key))
        assertEquals(0, cache.report(ResourceSelector.All, noGpuObjects).entries.size)
    }

    @Test
    fun byKindExcludesAKeyOfADifferentKind() {
        // selectorsMatchAllByKindByClassAndByKey only ever installs EXTERNAL keys, so it cannot tell a
        // real ByKind filter from one that always matches. This pins that branch with a non-EXTERNAL key.
        val cache = ResidentCache()
        val geometryKey = ResourceKey(
            kind = ResourceKind.GEOMETRY_PROGRAM,
            stableId = "c".repeat(64),
            resourceClass = null,
        )
        cache.install(externalStickerKey, storedA, null)
        cache.install(geometryKey, storedB, null)
        assertEquals(1, cache.report(ResourceSelector.ByKind(ResourceKind.EXTERNAL), noGpuObjects).entries.size)
        assertEquals(2, cache.report(ResourceSelector.All, noGpuObjects).entries.size)
    }

    @Test
    fun reportTotalsSumUsageAcrossMatchedEntries() {
        val cache = ResidentCache()
        cache.install(externalStickerKey, storedA, decodedOf(10))
        cache.install(externalModelKey, storedB, decodedOf(20))

        val totals = cache.report(ResourceSelector.All, noGpuObjects).totals

        assertEquals(storedA.bytes.size.toLong() + storedB.bytes.size.toLong(), totals.rawBytes)
        assertEquals(30L, totals.decodedCpuBytes)
        assertEquals(0L, totals.knownGpuBytes)
        assertFalse(totals.hasUnknownGpuBytes)
    }

    @Test
    fun anUnleasedResourceIsEvictedOnceTheBudgetIsExceeded() {
        // Every fixture resource is exactly 64 raw bytes, so a 64-byte budget holds one of them and
        // is breached by the second.
        val cache = ResidentCache(residentByteBudget = 64L)
        val first = keyNamed("evict-first")
        val second = keyNamed("evict-second")
        cache.install(first, storedA, null)

        cache.install(second, storedB, null)

        assertNull(cache.current(first))
        assertNotNull(cache.current(second))
        assertEquals(1, cache.report(ResourceSelector.All, noGpuObjects).entries.size)
    }

    @Test
    fun aLeasedResourceIsNeverEvictedHoweverFarOverBudget() {
        // A budget of one byte: every install breaches it, so nothing survives here except by the
        // lease rule itself.
        val cache = ResidentCache(residentByteBudget = 1L)
        val leased = keyNamed("leased-survivor")
        val unleased = keyNamed("unleased-victim")
        val lease = cache.installAndTakeLease(leased, storedA, null)

        cache.install(unleased, storedB, null)

        // Staying 127 bytes over budget is the correct outcome, not a leak: a live Prepared Frame
        // still needs this generation, and the budget bounds what may stay resident, never what must
        // go. The unleased neighbour is what the sweep is allowed to reclaim, and it has.
        assertNotNull(cache.current(leased))
        assertNull(cache.current(unleased))
        cache.releaseLease(lease)
    }

    @Test
    fun theLeastRecentlyUsedResourceIsTheOneEvicted() {
        val cache = ResidentCache(residentByteBudget = 128L)
        val oldest = keyNamed("lru-oldest")
        val middle = keyNamed("lru-middle")
        val newest = keyNamed("lru-newest")
        cache.install(oldest, storedA, null)
        cache.install(middle, storedB, null)
        // Observing the oldest makes it the most recent. Without that, install order alone would
        // decide the victim and this case would pass against a plain insertion-ordered queue.
        assertNotNull(cache.current(oldest))

        cache.install(newest, storedA, null)

        assertNull(cache.current(middle))
        assertNotNull(cache.current(oldest))
        assertNotNull(cache.current(newest))
    }

    @Test
    fun anEvictedKeyIsForgottenRatherThanMarkedFreed() {
        val cache = ResidentCache(residentByteBudget = 64L)
        val evicted = keyNamed("forgotten-key")
        cache.install(evicted, storedA, null)
        cache.install(keyNamed("forgotten-key-pressure"), storedB, null)

        assertNull(cache.current(evicted))
        // The decision ADR 0047 turns on. `freed` means the consumer asked, and `wasFreed` is what
        // raises RESOURCE_RELOADED_AFTER_FREE -- a diagnostic about the consumer's behaviour. An
        // eviction is this renderer's own decision about a resource nobody stopped wanting, so an
        // evicted key must read back exactly like one never seen.
        assertFalse(cache.wasFreed(evicted))
        assertEquals(
            ResourceFreeResult(matchedKeys = 0, fullyFreedKeys = 0, deferredKeys = 0, alreadyFreeKeys = 0),
            cache.free(ResourceSelector.ByKey(evicted)),
        )
    }

    @Test
    fun aKeyWhoseRetiredGenerationIsStillLeasedIsNotEvicted() {
        val cache = ResidentCache(residentByteBudget = 64L)
        val pinned = keyNamed("retired-pinned")
        val lease = cache.takeLease(cache.install(pinned, storedA, null))
        // Superseding moves the leased generation to `retired` and leaves an unleased current one.
        // Judged on its current generation alone the key looks evictable; it is not, because
        // something still holds the generation behind it.
        cache.install(pinned, storedB, null)

        cache.install(keyNamed("retired-pinned-pressure"), storedA, null)

        assertNotNull(cache.current(pinned))
        cache.releaseLease(lease)
    }

    @Test
    fun releasingTheLastLeaseMakesAKeyEvictableAtTheNextInstall() {
        val cache = ResidentCache(residentByteBudget = 64L)
        val held = keyNamed("held-then-released")
        val lease = cache.installAndTakeLease(held, storedA, null)
        cache.install(keyNamed("held-pressure-one"), storedB, null)
        assertNotNull(cache.current(held))

        cache.releaseLease(lease)
        cache.install(keyNamed("held-pressure-two"), storedA, null)

        // Releasing a lease does not itself evict -- the sweep runs where the total can grow, which
        // is an install. This is the pair that matters for a closing Prepared Frame (ADR 0045).
        assertNull(cache.current(held))
    }

    @Test
    fun decodedPixelsAreChargedToTheBudgetAlongsideRawBytes() {
        // 64 raw + 64 decoded against 64 raw alone. Were the decoded half charged at zero the two
        // installs would total exactly the budget and nothing would be evicted at all, which is the
        // mutation this case exists to catch.
        val cache = ResidentCache(residentByteBudget = 128L)
        val plain = keyNamed("plain-cost")
        val decoded = keyNamed("decoded-cost")
        cache.install(plain, storedA, null)

        cache.install(decoded, storedB, decodedOf(64))

        assertNull(cache.current(plain))
        assertNotNull(cache.current(decoded))
    }

    @Test
    fun supersedingAnUnleasedGenerationDoesNotLeaveItsBytesCharged() {
        // A budget for exactly two 64-byte generations, and exactly two are ever resident here. The
        // reinstall replaces rather than adds, so if the superseded generation's bytes stayed charged
        // the cache would believe itself full and evict a key nothing had finished with.
        val cache = ResidentCache(residentByteBudget = 128L)
        val reinstalled = keyNamed("superseded-unleased")
        val neighbour = keyNamed("superseded-neighbour")
        cache.install(reinstalled, storedA, null)
        cache.install(reinstalled, storedB, null)

        cache.install(neighbour, storedA, null)

        assertNotNull(cache.current(reinstalled))
        assertNotNull(cache.current(neighbour))
    }

    @Test
    fun aRetiredGenerationsBytesLeaveTheBudgetWhenItsLastLeaseIsReleased() {
        // The other half of the same accounting: a superseded generation that *was* leased stays
        // charged, correctly, until the lease goes -- and must stop being charged when it does.
        val cache = ResidentCache(residentByteBudget = 128L)
        val superseded = keyNamed("retired-bytes-superseded")
        val neighbour = keyNamed("retired-bytes-neighbour")
        val lease = cache.takeLease(cache.install(superseded, storedA, null))
        cache.install(superseded, storedB, null)
        cache.releaseLease(lease)

        cache.install(neighbour, storedA, null)

        assertNotNull(cache.current(superseded))
        assertNotNull(cache.current(neighbour))
    }

    @Test
    fun aDigestThatDoesNotMatchRefusesToReLeaseTheGenerationAlreadyHere() {
        val cache = ResidentCache()
        val key = keyNamed("digest-guard")
        cache.install(key, storedA, null)

        // The whole safety of ADR 0059's re-lease rests here. Same key and same digest is the same
        // content, so re-leasing is free; a *different* digest is a resource whose content changed,
        // and answering with the generation already here would serve stale bytes forever.
        assertNotNull(
            cache.observeAndTakeLease(key, storedA.contentDigest),
            "identical content must re-lease rather than install a twin",
        )
        assertNull(
            cache.observeAndTakeLease(key, storedB.contentDigest),
            "changed content must fall through to a fresh install",
        )
        assertNotNull(
            cache.observeAndTakeLease(key),
            "a caller with no digest to offer asks no digest question",
        )
    }

    @Test
    fun aDecodeIsAttachedOnceAndChargedOnce() {
        val cache = ResidentCache(residentByteBudget = 1024L)
        val key = keyNamed("attach-once")
        val generation = cache.install(key, storedA, null)

        cache.attachDecoded(generation, decodedOf(100))
        // A second attach is the racing caller ADR 0059 describes: equal pixels either way, so the
        // first is kept and -- the part that matters -- the bytes are charged once.
        cache.attachDecoded(generation, decodedOf(100))

        assertEquals(164L, cache.report(ResourceSelector.All, noGpuObjects).cpuResidency.residentBytes)
        assertEquals(100L, cache.report(ResourceSelector.All, noGpuObjects).totals.decodedCpuBytes)
    }

    @Test
    fun anAttachedDecodeIsReadBackFromTheGeneration() {
        val cache = ResidentCache()
        val key = keyNamed("attach-readback")
        val image = decodedOf(64)
        cache.install(key, storedA, null)

        assertNull(cache.current(key)?.decoded, "nothing has decoded it yet")
        cache.attachDecoded(cache.current(key)!!, image)

        // The whole point: the next frame finds the pixels instead of decoding the PNG again.
        assertSame(image, cache.current(key)?.decoded)
    }

    @Test
    fun attachingADecodeCanPushTheCacheOverItsBudgetAndEvict() {
        // 64 raw bytes fit; 64 + 100 decoded do not.
        val cache = ResidentCache(residentByteBudget = 100L)
        val key = keyNamed("attach-evicts")
        val generation = cache.install(key, storedA, null)

        cache.attachDecoded(generation, decodedOf(100))

        // Evicted by its own decode, and that is correct: the caller holds the image it just made and
        // draws this frame with it. What eviction decides is only whether the next frame decodes
        // again -- a budget that would not bind on the largest thing here would not be a budget.
        assertNull(cache.current(key))
        assertEquals(0L, cache.report(ResourceSelector.All, noGpuObjects).cpuResidency.residentBytes)
    }

    @Test
    fun aSupersededGenerationTakesItsDecodeWithIt() {
        val cache = ResidentCache()
        val key = keyNamed("attach-superseded")
        cache.attachDecoded(cache.install(key, storedA, null), decodedOf(64))

        cache.install(key, storedB, null)

        // Invalidation for free (ADR 0059): different content is a different locator and therefore a
        // different key, but a *re-install* of the same key must not hand back the old pixels.
        assertNull(cache.current(key)?.decoded)
    }

    @Test
    fun theReportCountsWhatTheBudgetHasEvicted() {
        val cache = ResidentCache(residentByteBudget = 64L)
        cache.install(keyNamed("counted-first"), storedA, null)
        assertEquals(
            ResourceResidency(residentBytes = 64L, budgetBytes = 64L, evictedKeyCount = 0L, evictedBytes = 0L),
            cache.report(ResourceSelector.All, noGpuObjects).cpuResidency,
        )

        cache.install(keyNamed("counted-second"), storedB, null)

        // One key left for the budget, taking its 64 bytes with it, and the resident total is back
        // inside the budget rather than merely reported as over it.
        assertEquals(
            ResourceResidency(residentBytes = 64L, budgetBytes = 64L, evictedKeyCount = 1L, evictedBytes = 64L),
            cache.report(ResourceSelector.All, noGpuObjects).cpuResidency,
        )
    }

    @Test
    fun residencyIgnoresTheSelectorThatNarrowsTheEntries() {
        val cache = ResidentCache()
        val selected = keyNamed("residency-selected")
        cache.install(selected, storedA, null)
        cache.install(keyNamed("residency-unselected"), storedB, null)

        val report = cache.report(ResourceSelector.ByKey(selected), noGpuObjects)

        assertEquals(1, report.entries.size)
        assertEquals(64L, report.totals.rawBytes)
        // ADR 0048: the budget governs every key, so the figure measured against it is every key's.
        // A selector-narrowed residency would invite a comparison that means nothing.
        assertEquals(128L, report.cpuResidency.residentBytes)
    }

    @Test
    fun closingTheCacheResetsItsCumulativeEvictionCounters() {
        val cache = ResidentCache(residentByteBudget = 64L)
        cache.install(keyNamed("closed-first"), storedA, null)
        cache.install(keyNamed("closed-second"), storedB, null)
        assertEquals(1L, cache.report(ResourceSelector.All, noGpuObjects).cpuResidency.evictedKeyCount)

        cache.closeAll()

        // "Since this renderer was created" ends when the renderer does, and a closed one answers
        // an empty report anyway -- a surviving history would only be state to get wrong.
        val residency = cache.report(ResourceSelector.All, noGpuObjects).cpuResidency
        assertEquals(0L, residency.residentBytes)
        assertEquals(0L, residency.evictedKeyCount)
        assertEquals(0L, residency.evictedBytes)
    }
}

/**
 * The GPU-byte lookup every case here passes: a [ResidentCache] with no GL layer behind it, whose
 * keys therefore hold no GPU object at all. Stated as a fixture rather than hardcoded inside
 * `report`, which is the whole of this task -- the cache does not know what is on the GPU, and what
 * it reports about that is now supplied by whoever does.
 *
 * The rows where the answer is *not* "no GPU objects" are exercised where the answer comes from, in
 * `GlObjectRegistryTest`, and end to end through `queryResources` in `RendererFactoryTest`.
 */
private val noGpuObjects: (ResourceKey) -> GpuByteAccount = { GpuByteAccount.NoGpuObjects }

private val key = ResourceKeyDeriver().external(
    ResourceClass.MODEL_TEXTURE,
    ResourceLocator("resident-cache-test-key"),
).key

private val externalStickerKey = ResourceKeyDeriver().external(
    ResourceClass.STICKER_IMAGE,
    ResourceLocator("resident-cache-test-sticker"),
).key

private val externalModelKey = ResourceKeyDeriver().external(
    ResourceClass.MODEL_GLB,
    ResourceLocator("resident-cache-test-model"),
).key

/**
 * A fresh key per eviction case, because eviction is about which keys a cache holds and the shared
 * fixtures above are reused across cases in one file.
 */
private fun keyNamed(name: String): ResourceKey = ResourceKeyDeriver().external(
    ResourceClass.MODEL_TEXTURE,
    ResourceLocator(name),
).key

private val storedA: StoredRawResource = storedResource("a".repeat(64))
private val storedB: StoredRawResource = storedResource("b".repeat(64))

private fun storedResource(digest: String): StoredRawResource = StoredRawResource(
    bytes = digest.encodeToByteArray(),
    contentDigest = digest,
    metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
)

private fun decodedOf(byteCount: Int): DecodedImage = DecodedImage(
    width = 1,
    height = 1,
    rgba = ByteArray(byteCount),
)
