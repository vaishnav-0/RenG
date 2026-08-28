package com.rohittp.reng.internal.identity

import com.rohittp.reng.internal.canonicalDouble
import com.rohittp.reng.internal.requireUnicodeScalars

internal enum class CanonicalRootKind(internal val wireByte: Int) {
    FRAME(1),
    EXTERNAL_RESOURCE(2),
    GEOMETRY_PROGRAM(3),
    INTERNAL_PIPELINE(4),
    OFFSCREEN_SURFACE(5),
    BASEMAP_TILE(6),
    MODEL_GEOMETRY(7),
    MODEL_IMAGE(8),

    /**
     * One engine-derived label, across frames (ADR 0035). The only root here that names no resource
     * at all: nothing is fetched, decoded, uploaded or cached under it, and its digest is never taken
     * -- see `deriveLabelIdentity`, which keys on these exact bytes.
     */
    LABEL(9),

    /**
     * One packed glyph atlas, identified by the content it packs. Named here rather than derived
     * from a locator because an atlas is not fetched: it is assembled by the engine out of the
     * Glyph Ranges the firewall did fetch, so it has no url of its own to be the identity of.
     */
    GLYPH_ATLAS(10),
}

internal class CanonicalFieldWriter internal constructor() {
    private val fields: MutableList<CanonicalField> = mutableListOf()
    private var lastTag: Int = 0
    private var encodedSize: Long = 0L

    internal fun field(tag: Int, payload: CanonicalBytes) {
        require(tag in 1..MAX_U16) { "Canonical field tag must be an unsigned nonzero 16-bit value" }
        require(tag > lastTag) { "Canonical field tags must be strictly increasing" }

        val nextSize = encodedSize + FIELD_HEADER_BYTES.toLong() + payload.size.toLong()
        requireCanonicalSize(nextSize)

        fields += CanonicalField(tag, payload)
        lastTag = tag
        encodedSize = nextSize
    }

    internal fun encode(prefix: ByteArray): CanonicalBytes {
        val outputSize = requireCanonicalSize(prefix.size.toLong() + encodedSize)
        val output = ByteArray(outputSize)
        prefix.copyInto(output)

        var offset = prefix.size
        fields.forEach { field ->
            writeU16(field.tag, output, offset)
            writeU32(field.payload.size, output, offset + U16_BYTES)
            field.payload.copyInto(output, offset + FIELD_HEADER_BYTES)
            offset += FIELD_HEADER_BYTES + field.payload.size
        }
        return CanonicalBytes(output)
    }

    private data class CanonicalField(
        val tag: Int,
        val payload: CanonicalBytes,
    )
}

internal object CanonicalBinary {
    internal fun root(
        kind: CanonicalRootKind,
        block: CanonicalFieldWriter.() -> Unit,
    ): CanonicalBytes {
        val writer = CanonicalFieldWriter().apply(block)
        return writer.encode(
            byteArrayOf(
                'R'.code.toByte(),
                'N'.code.toByte(),
                'G'.code.toByte(),
                'C'.code.toByte(),
                CANONICAL_SCHEMA_VERSION.toByte(),
                kind.wireByte.toByte(),
            ),
        )
    }

    internal fun fields(block: CanonicalFieldWriter.() -> Unit): CanonicalBytes =
        CanonicalFieldWriter().apply(block).encode(ByteArray(0))

    internal fun u16(value: Int): CanonicalBytes {
        require(value in 0..MAX_U16) { "Value must fit an unsigned 16-bit integer" }
        return CanonicalBytes(ByteArray(U16_BYTES).also { writeU16(value, it, 0) })
    }

    internal fun u64(value: Long): CanonicalBytes {
        require(value >= 0L) { "Value must be a non-negative unsigned 64-bit integer" }
        return CanonicalBytes(encodeLongBits(value))
    }

    /**
     * A **signed** 64-bit integer, two's complement and big-endian, for the values RenG reads out of
     * the engine rather than derives itself.
     *
     * [u64] is the right encoding for a value RenG knows to be non-negative -- a LOD, a pixel extent,
     * a document index -- and its `require` is a real check there. It is the wrong one for a
     * `TileId`'s three coordinates or a `LabelGlyphEntry.codepoint`: those are plain `Int`s on
     * Rentile's side with no validation behind them, and an identity derivation that throws on one is
     * an identity derivation that can fail a frame over a number nobody promised. This encoding is
     * total over every `Long`, so the caller needs no guard and no fallback.
     */
    internal fun i64(value: Long): CanonicalBytes = CanonicalBytes(encodeLongBits(value))

    internal fun boolean(value: Boolean): CanonicalBytes =
        CanonicalBytes(byteArrayOf(if (value) 1 else 0))

    internal fun binary64(value: Double): CanonicalBytes {
        val canonical = canonicalDouble(value, "canonical binary64 value")
        return CanonicalBytes(encodeLongBits(canonical.toBits()))
    }

    internal fun exactUtf8(value: String): CanonicalBytes {
        val scalars = requireUnicodeScalars(value, "canonical UTF-8 value", nonBlank = false)
        var encodedSize = 0L
        var index = 0
        while (index < scalars.length) {
            val codeUnit = scalars[index].code
            when {
                codeUnit <= 0x7f -> encodedSize += 1L
                codeUnit <= 0x7ff -> encodedSize += 2L
                codeUnit in HIGH_SURROGATE_START..HIGH_SURROGATE_END -> {
                    encodedSize += 4L
                    index += 1
                }
                else -> encodedSize += 3L
            }
            requireCanonicalSize(encodedSize)
            index += 1
        }

        val output = ByteArray(encodedSize.toInt())
        index = 0
        var offset = 0
        while (index < scalars.length) {
            val codeUnit = scalars[index].code
            when {
                codeUnit <= 0x7f -> {
                    output[offset] = codeUnit.toByte()
                    offset += 1
                }
                codeUnit <= 0x7ff -> {
                    output[offset] = (0xc0 or (codeUnit ushr 6)).toByte()
                    output[offset + 1] = (0x80 or (codeUnit and 0x3f)).toByte()
                    offset += 2
                }
                codeUnit in HIGH_SURROGATE_START..HIGH_SURROGATE_END -> {
                    val low = scalars[index + 1].code
                    val scalar = 0x10000 +
                        ((codeUnit - HIGH_SURROGATE_START) shl 10) +
                        (low - LOW_SURROGATE_START)
                    output[offset] = (0xf0 or (scalar ushr 18)).toByte()
                    output[offset + 1] = (0x80 or ((scalar ushr 12) and 0x3f)).toByte()
                    output[offset + 2] = (0x80 or ((scalar ushr 6) and 0x3f)).toByte()
                    output[offset + 3] = (0x80 or (scalar and 0x3f)).toByte()
                    offset += 4
                    index += 1
                }
                else -> {
                    output[offset] = (0xe0 or (codeUnit ushr 12)).toByte()
                    output[offset + 1] = (0x80 or ((codeUnit ushr 6) and 0x3f)).toByte()
                    output[offset + 2] = (0x80 or (codeUnit and 0x3f)).toByte()
                    offset += 3
                }
            }
            index += 1
        }
        return CanonicalBytes(output)
    }

    internal fun optional(value: CanonicalBytes?): CanonicalBytes {
        if (value == null) {
            return CanonicalBytes(byteArrayOf(OPTIONAL_ABSENT))
        }

        val output = ByteArray(requireCanonicalSize(1L + value.size.toLong()))
        output[0] = OPTIONAL_PRESENT
        value.copyInto(output, 1)
        return CanonicalBytes(output)
    }

    internal fun list(elements: List<CanonicalBytes>): CanonicalBytes {
        val snapshot = elements.toList()
        var encodedSize = U32_BYTES.toLong()
        snapshot.forEach { element ->
            encodedSize += U32_BYTES.toLong() + element.size.toLong()
            requireCanonicalSize(encodedSize)
        }

        val output = ByteArray(encodedSize.toInt())
        writeU32(snapshot.size, output, 0)
        var offset = U32_BYTES
        snapshot.forEach { element ->
            writeU32(element.size, output, offset)
            element.copyInto(output, offset + U32_BYTES)
            offset += U32_BYTES + element.size
        }
        return CanonicalBytes(output)
    }

    private fun encodeLongBits(value: Long): ByteArray = ByteArray(U64_BYTES) { index ->
        (value ushr ((U64_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
    }
}

private fun requireCanonicalSize(size: Long): Int {
    require(size in 0L..Int.MAX_VALUE.toLong()) { "Canonical encoding exceeds the supported size" }
    return size.toInt()
}

private fun writeU16(value: Int, destination: ByteArray, offset: Int) {
    destination[offset] = (value ushr Byte.SIZE_BITS).toByte()
    destination[offset + 1] = value.toByte()
}

private fun writeU32(value: Int, destination: ByteArray, offset: Int) {
    destination[offset] = (value ushr 24).toByte()
    destination[offset + 1] = (value ushr 16).toByte()
    destination[offset + 2] = (value ushr 8).toByte()
    destination[offset + 3] = value.toByte()
}

private const val CANONICAL_SCHEMA_VERSION: Int = 1
private const val MAX_U16: Int = 0xffff
private const val U16_BYTES: Int = 2
private const val U32_BYTES: Int = 4
private const val U64_BYTES: Int = 8
private const val FIELD_HEADER_BYTES: Int = U16_BYTES + U32_BYTES
private const val OPTIONAL_ABSENT: Byte = 0
private const val OPTIONAL_PRESENT: Byte = 1
private const val HIGH_SURROGATE_START: Int = 0xd800
private const val HIGH_SURROGATE_END: Int = 0xdbff
private const val LOW_SURROGATE_START: Int = 0xdc00
