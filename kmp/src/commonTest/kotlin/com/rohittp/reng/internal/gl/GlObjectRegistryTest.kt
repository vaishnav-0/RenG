package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.ExactContextFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlObjectRegistryTest {
    private val surfaceKey = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, "a".repeat(64), null)
    private val pipelineKey = ResourceKey(ResourceKind.INTERNAL_PIPELINE, "b".repeat(64), null)

    @Test fun liveHandlesAreReportedUntilTheyAreDeferred() {
        val registry = GlObjectRegistry()
        assertFalse(registry.hasLiveGpuObjects())
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)))
        assertTrue(registry.hasLiveGpuObjects())
        val deferred = registry.defer(surfaceKey, DeletionId(1L))
        assertEquals(surfaceKey, deferred?.resourceKey)
        assertFalse(registry.hasLiveGpuObjects())
        assertEquals(listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)), registry.takeQueued(DeletionId(1L)))
    }

    @Test fun gpuObjectLossForgetsEveryHandleAndIssuesNoDelete() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, 3)))
        registry.register(pipelineKey, listOf(GlObjectHandle(GlObjectType.PROGRAM, 9)))
        registry.defer(pipelineKey, DeletionId(2L))
        registry.forgetEverything()
        assertFalse(registry.hasLiveGpuObjects())
        assertTrue(registry.liveKeys().isEmpty())
        assertTrue(registry.takeQueued(DeletionId(2L)).isEmpty())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theDeleterGroupsByTypeAndSkipsEmptyGroups() {
        val binding = RecordingGlBinding()
        deleteGlObjects(
            binding,
            listOf(
                GlObjectHandle(GlObjectType.TEXTURE, 1),
                GlObjectHandle(GlObjectType.TEXTURE, 2),
                GlObjectHandle(GlObjectType.PROGRAM, 5),
            ),
        )
        assertEquals(1, binding.log.count { it.startsWith("deleteTextures(2") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram(5") })
        assertTrue(binding.log.none { it.startsWith("deleteBuffers") })
        assertTrue(binding.log.none { it.startsWith("deleteRenderbuffers") })
    }

    @Test fun deletingNothingIssuesNothing() {
        val binding = RecordingGlBinding()
        deleteGlObjects(binding, emptyList())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theProbeDistinguishesExactMissingAndForeignContexts() {
        val adopted = RenderContextIdentity(0x1000L)
        assertEquals(
            ExactContextFact.EXACT,
            exactContextFact(adopted) { RenderContextIdentity(0x1000L) },
        )
        assertEquals(ExactContextFact.NONE, exactContextFact(adopted) { null })
        assertEquals(
            ExactContextFact.DIFFERENT,
            exactContextFact(adopted) { RenderContextIdentity(0x2000L) },
        )
    }

    // ---- Task 1: the resident GPU texture byte budget -----------------------------------------

    @Test fun anUnleasedTextureStaysResidentWhileTheBudgetAllows() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val lease = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)

        registry.releaseLease(lease, binding)

        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            registry.resident(tileKey(0)),
            "a tile must survive losing its lease, or panning re-uploads",
        )
        assertTrue(binding.deletedNames.isEmpty())
    }

    @Test fun theLeastRecentlyUsedUnleasedTextureIsEvictedFirstWhenTheBudgetIsExceeded() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 2 * ONE_TILE_BYTES)
        val lease0 = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.releaseLease(lease0, binding)
        val lease1 = registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)
        registry.releaseLease(lease1, binding)

        // A draw reuses tile 0: it leases the resident texture and releases the lease when it is done,
        // which is the only thing that makes a budget-tracked texture most-recently-used. Tile 1 is now
        // the least recently used.
        val reuse0 = assertNotNull(registry.leaseResident(tileKey(0)))
        registry.releaseLease(reuse0.lease, binding)

        val lease2 = registry.registerTexture(tileKey(2), GlObjectHandle(GlObjectType.TEXTURE, 102), ONE_TILE_BYTES)
        registry.releaseLease(lease2, binding)

        assertEquals(GlObjectHandle(GlObjectType.TEXTURE, 100), registry.resident(tileKey(0)))
        assertNull(registry.resident(tileKey(1)), "the least recently used unleased tile is evicted first")
        assertEquals(GlObjectHandle(GlObjectType.TEXTURE, 102), registry.resident(tileKey(2)))
        assertEquals(
            listOf(101),
            binding.deletedNames,
            "eviction must delete exactly the evicted tile's own GL name, no more and no less",
        )
    }

    @Test fun aLeasedTextureIsNeverEvictedEvenWhenThatExceedsTheBudget() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = ONE_TILE_BYTES)
        // Held leased, never released.
        registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        val lease1 = registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)

        registry.releaseLease(lease1, binding)

        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            registry.resident(tileKey(0)),
            "a live PreparedFrame's tile must outrank the budget",
        )
        assertFalse(100 in binding.deletedNames, "the leased tile's GL name must never be deleted")
    }

    // ---- Task E-H: defer() must honour a lease the same way eviction does ----------------------

    @Test fun deferringALeasedTextureRetiresItInsteadOfQueuingItForDeletion() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        val lease = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)

        // The sequence a consumer-triggered free would take against a tile an in-flight draw is
        // sampling. Nothing wires it today (see GlObjectRegistry.defer's KDoc), but the lease must
        // outrank this deletion path exactly as it outranks eviction.
        assertNull(
            registry.defer(key, DeletionId(1L)),
            "a leased texture must not produce a ledger entry the lifecycle machine would delete",
        )

        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            registry.resident(key),
            "the draw still sampling this texture must still find it",
        )
        assertTrue(
            registry.takeQueued(DeletionId(1L)).isEmpty(),
            "no handle of a leased texture may be queued for deferred deletion",
        )
        assertTrue(binding.deletedNames.isEmpty(), "nothing may be deleted while the lease is open")

        // The draw ends, the lease goes, and only now does the retired texture die.
        registry.releaseLease(lease, binding)

        assertEquals(listOf(100), binding.deletedNames, "the last release deletes the retired texture")
        assertNull(registry.resident(key))
        assertTrue(registry.liveKeys().isEmpty())
    }

    @Test fun aRetiredTextureSurvivesEveryLeaseButTheLast() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        val first = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        val second = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)

        registry.defer(key, DeletionId(1L))
        registry.releaseLease(first, binding)

        assertTrue(binding.deletedNames.isEmpty(), "one released lease of two is not the last one")
        assertNotNull(registry.resident(key))

        registry.releaseLease(second, binding)
        assertEquals(
            listOf(100, 101),
            binding.deletedNames,
            "the last release deletes every handle the retired key still holds",
        )
    }

    @Test fun renderCloseDeletesATextureWhoseDeletionALeaseIsStillDelaying() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.defer(key, DeletionId(1L))

        // Exactly RenGRenderer.close()'s own sweep: every key liveKeys() still reports, deleted once.
        registry.liveKeys().forEach { live -> deleteGlObjects(binding, registry.handles(live)) }

        assertEquals(
            listOf(100),
            binding.deletedNames,
            "a lease may delay a deletion, never survive the renderer that issued it",
        )
    }

    @Test fun reRegisteringARetiredKeyRevivesIt() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        val stale = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.defer(key, DeletionId(1L))

        // The reload half of "accessing a freed resource reloads it": a fresh upload under the same key.
        val reloaded = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)
        registry.releaseLease(stale, binding)
        registry.releaseLease(reloaded, binding)

        assertTrue(binding.deletedNames.isEmpty(), "the reload cancels the pending deletion")
        assertNotNull(registry.resident(key))
    }

    // ---- Task E-J: reusing a resident texture leases it exactly as uploading one does ----------

    @Test fun aTextureLeasedForReuseIsAsExemptFromEvictionAsAFreshlyUploadedOne() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 2 * ONE_TILE_BYTES)
        val upload = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.releaseLease(upload, binding)

        // A later draw finds tile 0 still on the GPU and reuses it, then uploads two more tiles, taking
        // the resident total to three over a two-tile budget.
        val reuse = assertNotNull(registry.leaseResident(tileKey(0)))
        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            reuse.handle,
            "reuse must hand back the resident texture",
        )
        val lease1 = registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)
        val lease2 = registry.registerTexture(tileKey(2), GlObjectHandle(GlObjectType.TEXTURE, 102), ONE_TILE_BYTES)
        registry.releaseLease(lease1, binding)
        registry.releaseLease(lease2, binding)

        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            registry.resident(tileKey(0)),
            "the tile the draw is reusing must outrank the budget exactly as the tiles it uploaded do",
        )
        assertFalse(100 in binding.deletedNames, "a reused tile's GL name must not be deleted under its own draw")

        // And it is only exempt for as long as the draw holds it: the release puts it back in the order,
        // where two further tiles' worth of pressure works down to it like any other unleased entry.
        registry.releaseLease(reuse.lease, binding)
        val lease3 = registry.registerTexture(tileKey(3), GlObjectHandle(GlObjectType.TEXTURE, 103), ONE_TILE_BYTES)
        registry.releaseLease(lease3, binding)
        val lease4 = registry.registerTexture(tileKey(4), GlObjectHandle(GlObjectType.TEXTURE, 104), ONE_TILE_BYTES)
        registry.releaseLease(lease4, binding)
        assertTrue(
            100 in binding.deletedNames,
            "once the draw is done, a reused tile is an eviction candidate again",
        )
    }

    @Test fun reuseCountsOneMoreClaimOnOneTextureRatherThanOneMoreTexture() {
        val binding = RecordingGlBinding()
        // A budget below one tile: anything unleased dies at the next release.
        val registry = GlObjectRegistry(residentTextureByteBudget = 1L)
        val key = tileKey(0)
        val upload = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        val reuse = assertNotNull(registry.leaseResident(key))

        registry.releaseLease(upload, binding)
        assertTrue(binding.deletedNames.isEmpty(), "one released claim of two is not the last one")
        assertNotNull(registry.resident(key))

        registry.releaseLease(reuse.lease, binding)
        assertEquals(
            listOf(100),
            binding.deletedNames,
            "two claims and two releases delete the one texture once -- an unbalanced count leaks or " +
                "double-deletes it",
        )
    }

    @Test fun onlyABudgetTrackedTextureCanBeLeasedForReuse() {
        val registry = GlObjectRegistry()
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)))

        assertNull(
            registry.leaseResident(surfaceKey),
            "a register() texture carries no byte size, so leasing it would put a zero-byte candidate " +
                "in the eviction order",
        )
        assertNull(registry.leaseResident(tileKey(0)), "a key with no live texture has nothing to reuse")
    }

    @Test fun aTextureRetiredByAFreeIsNotHandedToTheNextDrawForReuse() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        val lease = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.defer(key, DeletionId(1L))

        assertNull(
            registry.leaseResident(key),
            "a retired generation is never resurrected: the next draw uploads a fresh texture instead",
        )

        registry.releaseLease(lease, binding)
        assertEquals(listOf(100), binding.deletedNames, "and the refused reuse did not delay the retired deletion")
    }

    // ---- X2 Task 1: what this registry may claim about a key's GPU bytes ----------------------

    @Test fun aBudgetTrackedTextureIsReportedByItsExactByteSize() {
        val cache = ResidentCache()
        val registry = GlObjectRegistry()
        cache.install(tileKey(0), storedTileBytes, null)
        registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), THREE_TILES_BYTES)

        val usage = cache.report(ResourceSelector.ByKey(tileKey(0)), registry::gpuByteAccount)
            .entries.single().usage

        assertEquals(THREE_TILES_BYTES, usage.knownGpuBytes, "a tracked texture's bytes are known exactly")
        assertFalse(usage.hasUnknownGpuBytes, "and nothing about it is unaccounted for")
    }

    @Test fun aTextureOutsideTheByteBudgetIsReportedAsUnknownRatherThanAsZero() {
        val cache = ResidentCache()
        val registry = GlObjectRegistry()
        cache.install(stickerKey, storedTileBytes, null)
        // The path every sticker image, geometry consumer texture, model texture and model buffer
        // takes: a live GL object with no byte size recorded anywhere in the registry.
        registry.register(stickerKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 101)))

        val usage = cache.report(ResourceSelector.ByKey(stickerKey), registry::gpuByteAccount)
            .entries.single().usage

        assertNull(
            usage.knownGpuBytes,
            "a texture whose size this layer never recorded has no known byte count, and 0 would be a claim",
        )
        assertTrue(usage.hasUnknownGpuBytes, "the bytes exist and go undeclared, which is what the flag is for")
    }

    @Test fun aKeyWithNoLiveGlObjectIsReportedAsAKnownZero() {
        val cache = ResidentCache()
        val registry = GlObjectRegistry()
        cache.install(glbKey, storedTileBytes, null)

        val usage = cache.report(ResourceSelector.ByKey(glbKey), registry::gpuByteAccount)
            .entries.single().usage

        assertEquals(0L, usage.knownGpuBytes, "nothing on the GPU is a knowable zero, not an unknown")
        assertFalse(usage.hasUnknownGpuBytes)
    }

    @Test fun reportTotalsSumWhatIsKnownAndStillDeclareWhatIsNot() {
        val cache = ResidentCache()
        val registry = GlObjectRegistry()
        // Two tracked textures of deliberately different sizes, so the total is neither entry's own
        // figure and a sum that quietly returned the first or the largest would show; one untracked
        // texture; and one key with no GL object at all. All three rows of the rule in one report.
        cache.install(tileKey(0), storedTileBytes, null)
        cache.install(tileKey(1), storedTileBytes, null)
        cache.install(stickerKey, storedTileBytes, null)
        cache.install(glbKey, storedTileBytes, null)
        registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), THREE_TILES_BYTES)
        registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), FIVE_TILES_BYTES)
        registry.register(stickerKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 102)))

        val report = cache.report(ResourceSelector.All, registry::gpuByteAccount)

        assertEquals(4, report.entries.size)
        assertEquals(
            THREE_TILES_BYTES + FIVE_TILES_BYTES,
            report.totals.knownGpuBytes,
            "the known sum must survive an unknown entry standing beside it rather than collapsing to null",
        )
        assertTrue(
            report.totals.hasUnknownGpuBytes,
            "and the unknown entry must still be declared, or the total silently drops it",
        )
    }

    // ---- X2 Task 2: eviction reports whether it got under budget ------------------------------

    @Test fun aReleaseThatCannotGetUnderBudgetSaysSo() {
        val binding = RecordingGlBinding()
        // One byte below one tile. The frame holds two tiles; releasing the first leaves it the only
        // eviction candidate, and taking it still leaves the second tile's megabyte resident against
        // a budget a byte smaller -- with nothing left that may be evicted, because the frame is
        // still holding it.
        val registry = GlObjectRegistry(residentTextureByteBudget = ONE_TILE_BYTES - 1L)
        val leased = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)

        val residency = registry.releaseLease(leased, binding)

        assertEquals(ONE_TILE_BYTES, residency.residentBytes, "the tile the frame still holds is what remains")
        assertEquals(ONE_TILE_BYTES - 1L, residency.budgetBytes)
        assertTrue(residency.overBudget, "one byte over is over")
    }

    @Test fun aResidencyExactlyAtTheBudgetIsNotOverIt() {
        val binding = RecordingGlBinding()
        // The same fixture as the case above, one byte of budget larger. That byte is the whole
        // difference between a thrash report and silence.
        val registry = GlObjectRegistry(residentTextureByteBudget = ONE_TILE_BYTES)
        val leased = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)

        val residency = registry.releaseLease(leased, binding)

        assertEquals(ONE_TILE_BYTES, residency.residentBytes)
        assertEquals(ONE_TILE_BYTES, residency.budgetBytes)
        assertFalse(residency.overBudget, "at the budget is not over the budget")
    }

    @Test fun aReleaseWithRoomToSpareEvictsNothingAndReportsItsResidency() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val leased = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)

        val residency = registry.releaseLease(leased, binding)

        assertEquals(2 * ONE_TILE_BYTES, residency.residentBytes, "both tiles are still resident")
        assertFalse(residency.overBudget)
        assertTrue(binding.deletedNames.isEmpty(), "and nothing was evicted to make that true")
    }

    @Test fun contextLossForgetsTexturesWhileTheDecodedImageStaysLeased() {
        val registry = GlObjectRegistry()
        val residentCache = ResidentCache()
        val key = tileKey(0)
        registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        val stored = StoredRawResource(
            bytes = "tile-bytes".encodeToByteArray(),
            contentDigest = "d".repeat(64),
            metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
        )
        val decoded = DecodedImage(width = 1, height = 1, rgba = ByteArray(4))
        val generation = residentCache.install(key, stored, decoded)
        residentCache.takeLease(generation)

        registry.forgetEverything()

        assertNull(registry.resident(key), "the GL name is meaningless after context loss")
        assertNotNull(residentCache.current(key), "the decoded pixels are still valid and leased")
    }

    @Test fun generationAndUploadVariantArePartOfConsumerTextureIdentity() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 16L)
        val image = defaultSamplerStateFor(TextureContent.IMAGE)
        val data = defaultSamplerStateFor(TextureContent.DATA)
        val generationOne = gpuIdentity(stickerKey, 1L, GpuUploadVariant(TextureContent.IMAGE, image))
        val generationTwo = gpuIdentity(stickerKey, 2L, GpuUploadVariant(TextureContent.IMAGE, image))
        val dataVariant = gpuIdentity(stickerKey, 2L, GpuUploadVariant(TextureContent.DATA, data))

        val first = registry.registerAllocation(
            generationOne, listOf(GlObjectHandle(GlObjectType.TEXTURE, 100)), 4L, 0L, 100,
        )
        val second = registry.registerAllocation(
            generationTwo, listOf(GlObjectHandle(GlObjectType.TEXTURE, 101)), 4L, 0L, 101,
        )
        val third = registry.registerAllocation(
            dataVariant, listOf(GlObjectHandle(GlObjectType.TEXTURE, 102)), 4L, 0L, 102,
        )

        assertEquals(100, assertNotNull(registry.leaseAllocation(generationOne)).payload)
        assertEquals(101, assertNotNull(registry.leaseAllocation(generationTwo)).payload)
        assertEquals(102, assertNotNull(registry.leaseAllocation(dataVariant)).payload)
        registry.releaseAllocation(first, binding)
        registry.releaseAllocation(second, binding)
        registry.releaseAllocation(third, binding)
    }

    @Test fun consumerTextureBudgetEvictsOldestUnleasedExactAllocation() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4L)
        val firstIdentity = gpuIdentity(stickerKey, 1L)
        val secondIdentity = gpuIdentity(stickerKey, 2L)
        registry.releaseAllocation(
            registry.registerAllocation(
                firstIdentity, listOf(GlObjectHandle(GlObjectType.TEXTURE, 100)), 4L, 0L, 100,
            ),
            binding,
        )

        registry.releaseAllocation(
            registry.registerAllocation(
                secondIdentity, listOf(GlObjectHandle(GlObjectType.TEXTURE, 101)), 4L, 0L, 101,
            ),
            binding,
        )

        assertNull(registry.leaseAllocation(firstIdentity))
        assertNotNull(registry.leaseAllocation(secondIdentity))
        assertTrue(100 in binding.deletedNames)
    }

    @Test fun modelBufferBudgetDeletesAWholePrimitiveGroupExactlyOnce() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentBufferByteBudget = 8L)
        val firstIdentity = gpuPrimitiveIdentity(glbKey, generation = 1L, primitive = 0)
        val secondIdentity = gpuPrimitiveIdentity(glbKey, generation = 1L, primitive = 1)
        val firstHandles = listOf(
            GlObjectHandle(GlObjectType.VERTEX_ARRAY, 100),
            GlObjectHandle(GlObjectType.BUFFER, 101),
            GlObjectHandle(GlObjectType.BUFFER, 102),
        )
        registry.releaseAllocation(
            registry.registerAllocation(firstIdentity, firstHandles, 0L, 8L, "first"), binding,
        )
        registry.releaseAllocation(
            registry.registerAllocation(
                secondIdentity,
                listOf(GlObjectHandle(GlObjectType.VERTEX_ARRAY, 103), GlObjectHandle(GlObjectType.BUFFER, 104)),
                0L,
                8L,
                "second",
            ),
            binding,
        )

        assertNull(registry.leaseAllocation(firstIdentity))
        assertTrue(100 in binding.deletedNames)
        assertTrue(101 in binding.deletedNames)
        assertTrue(102 in binding.deletedNames)
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
    }

    @Test fun freeingALeasedGenerationAllowsSameDrawSharingThenMakesLaterUploadsEphemeral() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        val identity = gpuIdentity(stickerKey, 7L)
        val lease = registry.registerAllocation(
            identity, listOf(GlObjectHandle(GlObjectType.TEXTURE, 100)), 4L, 0L, 100,
        )

        val retirement = registry.retireResources(
            ResourceSelector.ByKey(stickerKey),
            mapOf(stickerKey to setOf(ResidentGenerationId(7L))),
            binding,
        )
        assertEquals(setOf(stickerKey), retirement.deferredOwnerKeys)
        val sameDrawDuplicate = assertNotNull(
            registry.leaseAllocation(identity),
            "a duplicate occurrence in the serialized draw must share its active born-retired upload",
        )
        assertEquals(100, sameDrawDuplicate.payload)
        registry.releaseAllocation(lease, binding)
        assertTrue(binding.deletedNames.isEmpty(), "the first of two draw leases cannot delete the texture")
        registry.releaseAllocation(sameDrawDuplicate.lease, binding)
        assertEquals(listOf(100), binding.deletedNames, "the last draw lease deletes the retired allocation")

        val reupload = registry.registerAllocation(
            identity, listOf(GlObjectHandle(GlObjectType.TEXTURE, 101)), 4L, 0L, 101,
        )
        registry.releaseAllocation(reupload, binding)
        assertTrue(101 in binding.deletedNames, "an old frame cannot undo a completed free")
        assertNull(registry.leaseAllocation(identity))
    }

    @Test fun releasingAClaimFromALostContextCannotTouchAReplacementAllocation() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        val identity = gpuIdentity(stickerKey, 1L)
        val lost = registry.registerAllocation(
            identity, listOf(GlObjectHandle(GlObjectType.TEXTURE, 100)), 4L, 0L, 100,
        )
        val lostLegacy = registry.registerTexture(
            tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 200), ONE_TILE_BYTES,
        )
        registry.forgetEverything()
        val replacement = registry.registerAllocation(
            identity, listOf(GlObjectHandle(GlObjectType.TEXTURE, 101)), 4L, 0L, 101,
        )
        val replacementLegacy = registry.registerTexture(
            tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 201), ONE_TILE_BYTES,
        )

        registry.releaseAllocation(lost, binding)
        registry.releaseLease(lostLegacy, binding)

        assertEquals(101, assertNotNull(registry.leaseAllocation(identity)).payload)
        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 201),
            assertNotNull(registry.leaseResident(tileKey(0))).handle,
        )
        registry.releaseAllocation(replacement, binding)
        registry.releaseLease(replacementLegacy, binding)
    }

    @Test fun generalizedAndLegacyTexturesShareOneTextureBudget() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 10L)
        val legacyKey = tileKey(0)
        registry.releaseLease(
            registry.registerTexture(legacyKey, GlObjectHandle(GlObjectType.TEXTURE, 200), 6L),
            binding,
        )
        val identity = gpuIdentity(stickerKey, 1L)

        registry.releaseAllocation(
            registry.registerAllocation(
                identity,
                listOf(GlObjectHandle(GlObjectType.TEXTURE, 201)),
                textureBytes = 6L,
                bufferBytes = 0L,
                payload = 201,
            ),
            binding,
        )

        assertNull(registry.leaseResident(legacyKey), "the oldest texture in the shared pool is evicted")
        assertNotNull(registry.leaseAllocation(identity))
        assertTrue(200 in binding.deletedNames)
    }

    @Test fun freeAndReportIncludeLegacyBudgetTrackedGpuOnlyOwners() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        val key = tileKey(3)
        registry.releaseLease(
            registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 203), 12L),
            binding,
        )

        val snapshot = registry.allocationSnapshots(ResourceSelector.ByKey(key)).single()
        assertEquals(12L, snapshot.knownBytes)
        val retirement = registry.retireResources(ResourceSelector.ByKey(key), emptyMap(), binding)

        assertEquals(setOf(key), retirement.matchedOwnerKeys)
        assertTrue(203 in binding.deletedNames)
        assertTrue(registry.allocationSnapshots(ResourceSelector.ByKey(key)).isEmpty())
    }
}

private const val ONE_TILE_BYTES: Long = 512L * 512L * 4L

/** Two budget-tracked sizes that are neither equal to each other nor to their own sum. */
private const val THREE_TILES_BYTES: Long = 3L * ONE_TILE_BYTES
private const val FIVE_TILES_BYTES: Long = 5L * ONE_TILE_BYTES

private val stickerKey = ResourceKey(ResourceKind.EXTERNAL, "c".repeat(64), ResourceClass.STICKER_IMAGE)
private val glbKey = ResourceKey(ResourceKind.EXTERNAL, "d".repeat(64), ResourceClass.MODEL_GLB)

private val storedTileBytes = StoredRawResource(
    bytes = "tile-bytes".encodeToByteArray(),
    contentDigest = "e".repeat(64),
    metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
)

private fun tileKey(index: Int): ResourceKey =
    ResourceKey(ResourceKind.BASEMAP_TILE, index.toString().repeat(64).take(64), null)

private fun gpuIdentity(
    owner: ResourceKey,
    generation: Long,
    variant: GpuUploadVariant = GpuUploadVariant(
        TextureContent.IMAGE,
        defaultSamplerStateFor(TextureContent.IMAGE),
    ),
): GpuResourceIdentity = GpuResourceIdentity(
    ownerKey = owner,
    generationId = ResidentGenerationId(generation),
    subresource = GpuSubresource.Texture,
    uploadVariant = variant,
)

private fun gpuPrimitiveIdentity(owner: ResourceKey, generation: Long, primitive: Int): GpuResourceIdentity =
    GpuResourceIdentity(
        ownerKey = owner,
        generationId = ResidentGenerationId(generation),
        subresource = GpuSubresource.ModelPrimitive(meshIndex = 0, primitiveIndex = primitive),
    )
