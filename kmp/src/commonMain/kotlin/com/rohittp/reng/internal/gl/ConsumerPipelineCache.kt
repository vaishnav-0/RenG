package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey

/** A draw-scoped claim that keeps one consumer-authored pipeline out of LRU eviction. */
internal class ConsumerPipelineLease<T : Any> internal constructor(
    internal val key: ResourceKey,
    internal val value: T,
) {
    private var released: Boolean = false

    internal fun markReleased() {
        require(!released) { "a consumer-pipeline lease cannot be released more than once" }
        released = true
    }
}

/**
 * One count-bounded LRU shared by Geometry and consumer Backdrop pipelines.
 *
 * Entries are leased for the complete draw that uses them. The cache may temporarily exceed its
 * steady limit when a single draw needs more entries, but releases trim it before the draw returns.
 * Removing an entry also releases its reusable CPU grid scratch through ordinary reachability.
 */
internal class ConsumerPipelineCache(private val maximumEntries: Int) {
    private sealed interface Value {
        val key: ResourceKey

        class Geometry(val pipeline: GeometryPipeline) : Value {
            override val key: ResourceKey get() = pipeline.key
        }

        class Backdrop(val pipeline: ConsumerBackdropPipeline) : Value {
            override val key: ResourceKey get() = pipeline.key
        }
    }

    private class Entry(val value: Value, var leaseCount: Int)

    private val entries: MutableMap<ResourceKey, Entry> = mutableMapOf()
    private val unleasedOrder: LinkedHashMap<ResourceKey, Unit> = LinkedHashMap()

    init {
        require(maximumEntries >= 1) { "maximumEntries must be positive" }
    }

    internal fun leaseGeometry(key: ResourceKey): ConsumerPipelineLease<GeometryPipeline>? {
        val entry = entries[key] ?: return null
        val pipeline = (entry.value as? Value.Geometry)?.pipeline ?: return null
        takeLease(key, entry)
        return ConsumerPipelineLease(key, pipeline)
    }

    internal fun registerGeometry(pipeline: GeometryPipeline): ConsumerPipelineLease<GeometryPipeline> {
        register(Value.Geometry(pipeline))
        return ConsumerPipelineLease(pipeline.key, pipeline)
    }

    internal fun leaseBackdrop(key: ResourceKey): ConsumerPipelineLease<ConsumerBackdropPipeline>? {
        val entry = entries[key] ?: return null
        val pipeline = (entry.value as? Value.Backdrop)?.pipeline ?: return null
        takeLease(key, entry)
        return ConsumerPipelineLease(key, pipeline)
    }

    internal fun registerBackdrop(
        pipeline: ConsumerBackdropPipeline,
    ): ConsumerPipelineLease<ConsumerBackdropPipeline> {
        register(Value.Backdrop(pipeline))
        return ConsumerPipelineLease(pipeline.key, pipeline)
    }

    internal fun release(
        binding: GlBinding,
        programs: GlProgramCache,
        lease: ConsumerPipelineLease<*>,
    ) {
        lease.markReleased()
        val entry = checkNotNull(entries[lease.key]) { "leased consumer pipeline must remain cached" }
        check(entry.value.valueIdentity() === lease.value) { "consumer-pipeline lease has stale value" }
        entry.leaseCount -= 1
        check(entry.leaseCount >= 0) { "consumer-pipeline lease count cannot be negative" }
        if (entry.leaseCount == 0) {
            unleasedOrder.remove(lease.key)
            unleasedOrder[lease.key] = Unit
            trim(binding, programs)
        }
    }

    /** Declared context loss: names are already gone, so drop metadata without GL deletes. */
    internal fun forgetAll() {
        entries.clear()
        unleasedOrder.clear()
    }

    /** Renderer close: delete every still-known pipeline exactly once, regardless of leases. */
    internal fun deleteAll(binding: GlBinding, programs: GlProgramCache) {
        val values = entries.values.map { it.value }
        entries.clear()
        unleasedOrder.clear()
        values.forEach { delete(binding, programs, it) }
    }

    internal fun size(): Int = entries.size

    private fun register(value: Value) {
        check(value.key !in entries) { "consumer pipeline is already cached" }
        entries[value.key] = Entry(value, leaseCount = 1)
        unleasedOrder.remove(value.key)
    }

    private fun takeLease(key: ResourceKey, entry: Entry) {
        entry.leaseCount += 1
        unleasedOrder.remove(key)
    }

    private fun trim(binding: GlBinding, programs: GlProgramCache) {
        while (entries.size > maximumEntries) {
            val key = unleasedOrder.keys.firstOrNull() ?: return
            unleasedOrder.remove(key)
            val value = entries.remove(key)?.value ?: continue
            delete(binding, programs, value)
        }
    }

    private fun Value.valueIdentity(): Any = when (this) {
        is Value.Geometry -> pipeline
        is Value.Backdrop -> pipeline
    }

    private fun delete(binding: GlBinding, programs: GlProgramCache, value: Value) {
        when (value) {
            is Value.Geometry -> deleteGeometryPipeline(binding, programs, value.pipeline)
            is Value.Backdrop -> deleteConsumerBackdropPipeline(binding, programs, value.pipeline)
        }
    }
}
