package com.rohittp.reng.internal.image

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.crc32 as zlibCrc32
import platform.zlib.inflate as zlibInflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream

/**
 * Streams zlib inflate over `platform.zlib`'s full streaming interface, not the one-shot helper: PNG's
 * IDAT chunking means the compressed payload must never be buffered whole.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class InflateStream actual constructor() {
    private val stream = nativeHeap.alloc<z_stream>()

    // zlib's inflate() rejects a null next_out unconditionally — `strm->next_out == Z_NULL` is a
    // Z_STREAM_ERROR by itself, even when avail_out is 0. But an empty output ByteArray (the
    // zero-payload vector) or an outputOffset sitting exactly at the end of a full one can never
    // produce a valid pinned address via addressOf(). This single reusable byte gives zlib a
    // permanently valid, never-written destination for exactly those zero-avail_out calls.
    private val dummyOut = nativeHeap.allocArray<ByteVar>(1)
    private var closed = false

    init {
        // zlib's own contract (zlib.h) requires the caller to initialize zalloc, zfree, and opaque
        // before calling the init function; nativeHeap.alloc() does not zero the memory it returns,
        // so skipping this leaves those fields as garbage. zlib then reads a nonzero zalloc/zfree as a
        // caller-supplied allocator and calls through it, corrupting the stream state before the first
        // inflate() call ever runs.
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        val status = inflateInit_(stream.ptr, ZLIB_VERSION, sizeOf<z_stream>().convert())
        if (status != Z_OK) {
            nativeHeap.free(stream.rawPtr)
            nativeHeap.free(dummyOut.rawValue)
            throw InflateException("inflateInit failed with $status")
        }
    }

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
        return input.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                // addressOf(0) throws at runtime on an empty array, so only take an address when there
                // is at least one byte behind it. next_in may be null here: zlib only rejects a null
                // next_in when avail_in is nonzero, and avail_in is 0 exactly when input is empty.
                stream.next_in = if (inputLength == 0) null else pinnedInput.addressOf(inputOffset).reinterpret()
                stream.avail_in = inputLength.convert()
                // next_out must NEVER be null, unlike next_in — zlib's own guard rejects a null
                // next_out unconditionally, regardless of avail_out. addressOf(outputOffset) is invalid
                // whenever availOut == 0 (an empty array, or an offset sitting exactly at the end of a
                // full one), so fall back to the reusable dummy byte in exactly that case; avail_out
                // staying 0 guarantees zlib never writes through it.
                stream.next_out = if (outputLength == 0) dummyOut.reinterpret() else pinnedOutput.addressOf(outputOffset).reinterpret()
                stream.avail_out = outputLength.convert()
                // Always call inflate, even when avail_out is 0: a stream whose remaining bytes decode
                // to zero output (the empty-payload vector) can only ever be detected as finished by
                // letting zlib consume the trailing bytes, which needs no output space at all.
                val status = zlibInflate(stream.ptr, Z_NO_FLUSH)
                if (status != Z_OK && status != Z_STREAM_END && status != Z_BUF_ERROR) {
                    throw InflateException("inflate failed with status $status")
                }
                InflateStep(
                    consumed = inputLength - stream.avail_in.convert<Int>(),
                    produced = outputLength - stream.avail_out.convert<Int>(),
                    finished = status == Z_STREAM_END,
                )
            }
        }
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflateEnd(stream.ptr)
        nativeHeap.free(stream.rawPtr)
        nativeHeap.free(dummyOut.rawValue)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "crc range out of bounds" }
    // addressOf(offset) throws when there is nothing behind it (a zero-length range, possibly over an
    // empty array); zlib's crc32(seed, buf, 0) is the identity on seed, so return it directly instead.
    if (length == 0) return seed
    return bytes.usePinned { pinned ->
        zlibCrc32(seed.convert(), pinned.addressOf(offset).reinterpret(), length.convert()).convert()
    }
}
