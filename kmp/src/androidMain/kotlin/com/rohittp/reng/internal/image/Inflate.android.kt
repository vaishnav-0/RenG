package com.rohittp.reng.internal.image

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Streams zlib inflate over `java.util.zip.Inflater`: PNG's IDAT chunking means the compressed payload
 * must never be buffered whole.
 */
internal actual class InflateStream actual constructor() {
    private val inflater = Inflater()
    private var closed = false

    actual fun inflate(
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        output: ByteArray,
        outputOffset: Int,
        outputLength: Int,
    ): InflateStep {
        require(!closed) { "inflate stream is closed" }
        require(inputOffset >= 0 && inputLength >= 0 && inputOffset <= input.size - inputLength) {
            "input range out of bounds"
        }
        require(outputOffset >= 0 && outputLength >= 0 && outputOffset <= output.size - outputLength) {
            "output range out of bounds"
        }
        // Inflater.setInput() replaces the buffer it reads from, so only call it once the previous
        // buffer is fully drained (needsInput() true) — otherwise it would discard unconsumed bytes
        // from the prior call. When we skip it, none of *this* call's `input` bytes were touched, so
        // `consumed` for this step must be exactly 0, independent of `input`'s size.
        val suppliedNewInput = inputLength > 0 && inflater.needsInput()
        if (suppliedNewInput) {
            inflater.setInput(input, inputOffset, inputLength)
        }
        val availableInputBefore = inflater.remaining
        // Call inflate() even with availOut == 0: a stream whose remaining bytes decode to zero output
        // (the empty-payload vector) can only be detected as finished by letting the inflater consume
        // the trailing bytes, which needs no output space at all. Inflater.inflate(buf, off, 0) is a
        // legal, well-defined call that still advances internal state.
        val produced = try {
            inflater.inflate(output, outputOffset, outputLength)
        } catch (failure: DataFormatException) {
            throw InflateException("inflate failed: ${failure.message ?: failure::class.simpleName}")
        }
        val consumed = availableInputBefore - inflater.remaining
        return InflateStep(
            consumed = consumed,
            produced = produced,
            finished = inflater.finished(),
        )
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflater.end()
    }
}

internal actual fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "crc range out of bounds" }
    val digest = CRC32()
    if (seed != 0u) {
        // CRC32 has no seed setter; the PNG walk only ever seeds from zero — a chunk's type and payload
        // are a contiguous range of the same buffer, so the caller covers both in one call rather than
        // chaining. A non-zero seed here would mean a chained call this actual does not support.
        throw IllegalArgumentException("chained crc32 seeds are supplied by the caller's running value")
    }
    digest.update(bytes, offset, length)
    return digest.value.toUInt()
}
