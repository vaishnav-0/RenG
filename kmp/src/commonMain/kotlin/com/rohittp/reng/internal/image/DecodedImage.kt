package com.rohittp.reng.internal.image

import com.rohittp.reng.internal.freshCopy

/**
 * One decoded image in RenG's single canonical form: tightly packed RGBA8, unpremultiplied, with no
 * row padding. Public-shaped reads return a fresh copy; narrow internal indexed/range operations let
 * terrain and upload code consume the immutable storage without first duplicating the whole raster.
 */
internal class DecodedImage private constructor(
    val width: Int,
    val height: Int,
    rgba: ByteArray,
    takeOwnership: Boolean,
) {
    constructor(width: Int, height: Int, rgba: ByteArray) : this(width, height, rgba, takeOwnership = false)

    private val bytes: ByteArray = if (takeOwnership) rgba else rgba.freshCopy()

    val byteCount: Int get() = bytes.size

    fun rgbaSnapshot(): ByteArray = bytes.freshCopy()

    fun rgbaByteAt(offset: Int): Byte = bytes[offset]

    fun copyRgbaRangeTo(
        destination: ByteArray,
        destinationOffset: Int,
        sourceOffset: Int,
        length: Int,
    ) {
        require(sourceOffset >= 0 && length >= 0 && sourceOffset <= bytes.size - length) {
            "source range is outside decoded pixels"
        }
        require(destinationOffset >= 0 && destinationOffset <= destination.size - length) {
            "destination range is outside the target"
        }
        bytes.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = sourceOffset,
            endIndex = sourceOffset + length,
        )
    }

    /**
     * Provides the backing bytes only for a synchronous operation that neither mutates nor retains
     * them. The callback shape makes that lifetime explicit at internal consumers such as a GL upload
     * whose native call completes before the callback returns.
     */
    fun <T> readRgbaBytes(block: (ByteArray) -> T): T = block(bytes)

    companion object {
        /** Adopts a freshly allocated array whose caller transfers all ownership to this image. */
        fun takeOwnership(width: Int, height: Int, rgba: ByteArray): DecodedImage =
            DecodedImage(width, height, rgba, takeOwnership = true)
    }
}
