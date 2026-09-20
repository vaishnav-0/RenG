package com.rohittp.reng.internal.gl

import com.rohittp.reng.ShaderPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsumerPipelineCacheTest {
    @Test
    fun capPlusOneEvictsTheLeastRecentlyUsedUnleasedPipeline() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val programs = GlProgramCache()
        val first = geometry(binding, programs, 1)
        val second = geometry(binding, programs, 2)
        val third = geometry(binding, programs, 3)
        val cache = ConsumerPipelineCache(maximumEntries = 2)

        cache.release(binding, programs, cache.registerGeometry(first))
        cache.release(binding, programs, cache.registerGeometry(second))
        binding.log.clear()
        cache.release(binding, programs, cache.registerGeometry(third))

        assertEquals(2, cache.size())
        assertNull(cache.leaseGeometry(first.key))
        cache.release(binding, programs, assertNotNull(cache.leaseGeometry(second.key)))
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
    }

    @Test
    fun aHitUpdatesRecencyAcrossTheSharedGeometryAndBackdropCache() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val programs = GlProgramCache()
        val geometry = geometry(binding, programs, 1)
        val backdrop = backdrop(binding, programs, 2)
        val newcomer = geometry(binding, programs, 3)
        val cache = ConsumerPipelineCache(maximumEntries = 2)
        cache.release(binding, programs, cache.registerGeometry(geometry))
        cache.release(binding, programs, cache.registerBackdrop(backdrop))

        cache.release(binding, programs, assertNotNull(cache.leaseGeometry(geometry.key)))
        cache.release(binding, programs, cache.registerGeometry(newcomer))

        assertNull(cache.leaseBackdrop(backdrop.key), "the older backdrop is the shared LRU victim")
        cache.release(binding, programs, assertNotNull(cache.leaseGeometry(geometry.key)))
        cache.release(binding, programs, assertNotNull(cache.leaseGeometry(newcomer.key)))
    }

    @Test
    fun aLeasedPipelineIsNeverEvictedAndTheCacheTrimsAfterRelease() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val programs = GlProgramCache()
        val first = geometry(binding, programs, 1)
        val second = geometry(binding, programs, 2)
        val cache = ConsumerPipelineCache(maximumEntries = 1)
        val firstLease = cache.registerGeometry(first)
        val secondLease = cache.registerGeometry(second)

        cache.release(binding, programs, secondLease)

        assertNotNull(cache.leaseGeometry(first.key), "the still-leased oldest entry must survive")
        assertNull(cache.leaseGeometry(second.key), "the only unleased entry is evicted")
        cache.release(binding, programs, firstLease)
    }

    @Test
    fun contextLossForgetsWithoutDeleting() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val programs = GlProgramCache()
        val cache = ConsumerPipelineCache(maximumEntries = 1)
        cache.registerGeometry(geometry(binding, programs, 1))
        binding.log.clear()

        cache.forgetAll()

        assertEquals(0, cache.size())
        assertTrue(binding.log.none { it.startsWith("delete") })
    }

    private fun geometry(
        binding: RecordingGlBinding,
        programs: GlProgramCache,
        suffix: Int,
    ): GeometryPipeline {
        val pair = ShaderPair(
            vertexSource = "#version 300 es\n// $suffix\nvoid main(){gl_Position=vec4(0.0);}\n",
            fragmentSource = "#version 300 es\nprecision highp float;\n// $suffix\nout vec4 c;void main(){c=vec4(1.0);}\n",
        )
        return (createGeometryPipeline(binding, ShaderDialect.GLES, programs, pair) as GeometryPipelineResult.Created)
            .pipeline
    }

    private fun backdrop(
        binding: RecordingGlBinding,
        programs: GlProgramCache,
        suffix: Int,
    ): ConsumerBackdropPipeline {
        val pair = ShaderPair(
            vertexSource = "#version 300 es\n// $suffix\nvoid main(){gl_Position=vec4(0.0);}\n",
            fragmentSource = "#version 300 es\nprecision highp float;\n// $suffix\nout vec4 c;void main(){c=vec4(1.0);}\n",
        )
        return (
            createConsumerBackdropPipeline(binding, ShaderDialect.GLES, programs, pair)
                as ConsumerBackdropPipelineResult.Created
            ).pipeline
    }
}
