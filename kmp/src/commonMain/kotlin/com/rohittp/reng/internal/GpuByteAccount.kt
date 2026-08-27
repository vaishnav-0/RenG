package com.rohittp.reng.internal

/**
 * What the layer that owns GL handles knows about one [com.rohittp.reng.ResourceKey]'s GPU bytes,
 * in exactly the two fields [com.rohittp.reng.ResourceUsage] reports them in — a known byte count,
 * an "and there is more I cannot measure" flag, or both.
 *
 * It exists so that `ResidentCache.report` can be told those bytes without being handed anything that
 * knows what a GL texture is: the cache takes a `(ResourceKey) -> GpuByteAccount` lookup, and the GL
 * registry on the other side of it answers. Neither of the two fields is a GL concept, which is the
 * whole point — this type is the seam.
 *
 * The three states are distinct claims and must stay distinguishable. [NoGpuObjects] says *zero bytes,
 * and I know that exactly*; [Unmeasurable] says *there are bytes and I cannot count them*; [measured]
 * says *this many bytes, exactly*. Collapsing the first two is what
 * `ResidentCache.toReportEntry` used to do by hardcoding `knownGpuBytes = 0L`, which stated positive
 * knowledge of zero while 167 MiB of basemap tiles were resident.
 */
internal data class GpuByteAccount(
    val knownBytes: Long?,
    val hasUnknownBytes: Boolean,
) {
    init {
        require(knownBytes == null || knownBytes >= 0L) { "knownBytes must be non-negative when present" }
        require(knownBytes != null || hasUnknownBytes) {
            "unknown GPU bytes must be declared when known GPU bytes are absent"
        }
    }

    internal companion object {
        /** No live GPU object under this key at all: zero bytes, and that zero is knowledge. */
        val NoGpuObjects: GpuByteAccount = GpuByteAccount(knownBytes = 0L, hasUnknownBytes = false)

        /** Live GPU objects whose bytes this layer does not track, so it declines to name a number. */
        val Unmeasurable: GpuByteAccount = GpuByteAccount(knownBytes = null, hasUnknownBytes = true)

        /** Exactly [bytes] GPU bytes, tracked. */
        fun measured(bytes: Long): GpuByteAccount =
            GpuByteAccount(knownBytes = bytes, hasUnknownBytes = false)
    }
}
