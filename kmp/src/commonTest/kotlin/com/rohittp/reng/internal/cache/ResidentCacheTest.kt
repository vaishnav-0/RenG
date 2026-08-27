package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceFreeResult
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
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
