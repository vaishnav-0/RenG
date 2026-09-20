package com.rohittp.reng.internal.image

/** One incremental inflate step: input consumed, output produced, and whether the stream ended. */
internal data class InflateStep(
    val consumed: Int,
    val produced: Int,
    val finished: Boolean,
)

internal class InflateException(message: String) : Exception(message)

/**
 * A streaming zlib inflater. PNG splits one zlib stream across arbitrarily many IDAT chunks, so this
 * must accept input incrementally and must never require the whole compressed payload at once. It must
 * also tolerate an output buffer smaller than the remaining decompressed data, producing what fits and
 * reporting how much of each side it actually touched so the caller can resume with a fresh slice and/or
 * a fresh output window.
 */
internal expect class InflateStream() {
    fun inflate(
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        output: ByteArray,
        outputOffset: Int,
        outputLength: Int,
    ): InflateStep
    fun close()
}

/** Compatibility convenience for callers that genuinely own whole input/output tails. */
internal fun InflateStream.inflate(input: ByteArray, output: ByteArray, outputOffset: Int): InflateStep =
    inflate(input, 0, input.size, output, outputOffset, output.size - outputOffset)

internal expect fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt
