package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.cache.ResidentGenerationId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class GlObjectRegistryConcurrencyAndroidHostTest {
    @Test
    fun snapshotsAndContextLossCanRaceRegistryTransitionsWithoutCorruption() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 64L, residentBufferByteBudget = 64L)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()

        val writer = thread {
            runCatching {
                start.await()
                repeat(500) { index ->
                    val key = key(index)
                    val identity = GpuResourceIdentity(
                        ownerKey = key,
                        generationId = ResidentGenerationId(index.toLong()),
                        subresource = GpuSubresource.Texture,
                        uploadVariant = GpuUploadVariant(
                            TextureContent.IMAGE,
                            defaultSamplerStateFor(TextureContent.IMAGE),
                        ),
                    )
                    registry.releaseAllocation(
                        registry.registerAllocation(
                            identity,
                            listOf(GlObjectHandle(GlObjectType.TEXTURE, index + 1)),
                            textureBytes = 4L,
                            bufferBytes = 0L,
                            payload = index + 1,
                        ),
                        binding,
                    )
                }
            }.exceptionOrNull()?.let(failures::add)
        }
        val reader = thread {
            runCatching {
                start.await()
                repeat(1_000) { index ->
                    val key = key(index % 500)
                    registry.resident(key)
                    registry.gpuByteAccount(key)
                    registry.allocationSnapshots(ResourceSelector.All)
                    registry.hasLiveGpuObjects()
                }
            }.exceptionOrNull()?.let(failures::add)
        }
        val loss = thread {
            runCatching {
                start.await()
                repeat(100) { registry.forgetEverything() }
            }.exceptionOrNull()?.let(failures::add)
        }

        start.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(10))
        reader.join(TimeUnit.SECONDS.toMillis(10))
        loss.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue(!writer.isAlive && !reader.isAlive && !loss.isAlive, "registry stress threads must terminate")
        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
    }

    private fun key(index: Int): ResourceKey = ResourceKey(
        kind = ResourceKind.BASEMAP_TILE,
        stableId = index.toString(16).padStart(64, '0'),
        resourceClass = null,
    )
}
