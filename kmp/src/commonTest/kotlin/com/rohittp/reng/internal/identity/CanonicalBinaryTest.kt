package com.rohittp.reng.internal.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CanonicalBinaryTest {
    @Test
    fun fixedWidthPrimitivesUseExactUnsignedBigEndianBytes() {
        assertEquals("0102", CanonicalBinary.u16(0x0102).hex())
        assertEquals("ffff", CanonicalBinary.u16(0xffff).hex())
        assertEquals("0102030405060708", CanonicalBinary.u64(0x0102030405060708L).hex())
        assertEquals("00", CanonicalBinary.boolean(false).hex())
        assertEquals("01", CanonicalBinary.boolean(true).hex())
    }

    @Test
    fun unsignedIntegerWritersRejectOutOfRangeValues() {
        assertFailsWith<IllegalArgumentException> { CanonicalBinary.u16(-1) }
        assertFailsWith<IllegalArgumentException> { CanonicalBinary.u16(0x1_0000) }
        assertFailsWith<IllegalArgumentException> { CanonicalBinary.u64(-1L) }
    }

    @Test
    fun binary64UsesFiniteCanonicalIeee754Bytes() {
        assertEquals("3ff0000000000000", CanonicalBinary.binary64(1.0).hex())
        assertEquals("0000000000000000", CanonicalBinary.binary64(-0.0).hex())

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertFailsWith<IllegalArgumentException> { CanonicalBinary.binary64(value) }
        }
    }

    @Test
    fun exactUtf8PreservesUnicodeScalarsWithoutNormalization() {
        assertEquals("c3a9", CanonicalBinary.exactUtf8("é").hex())
        assertEquals("f09f9880", CanonicalBinary.exactUtf8("😀").hex())
        assertNotEquals(
            CanonicalBinary.exactUtf8("é"),
            CanonicalBinary.exactUtf8("é"),
        )
    }

    @Test
    fun exactUtf8RejectsEveryIsolatedSurrogateShape() {
        listOf("\uD800", "\uDC00", "before\uD800after", "\uD800\uD800").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { CanonicalBinary.exactUtf8(invalid) }
        }
    }

    @Test
    fun optionalAndListUseExactPresenceCountAndElementLengths() {
        val accent = CanonicalBinary.exactUtf8("é")

        assertEquals("00", CanonicalBinary.optional(null).hex())
        assertEquals("01c3a9", CanonicalBinary.optional(accent).hex())
        assertEquals(
            "0000000200000001aa00000002bbcc",
            CanonicalBinary.list(
                listOf(
                    CanonicalBytes(byteArrayOf(0xaa.toByte())),
                    CanonicalBytes(byteArrayOf(0xbb.toByte(), 0xcc.toByte())),
                ),
            ).hex(),
        )
    }

    @Test
    fun fieldsUseStrictTagsAndExactUnsignedLengths() {
        val encoded = CanonicalBinary.fields {
            field(0x0102, CanonicalBytes(byteArrayOf(0xaa.toByte(), 0xbb.toByte())))
        }

        assertEquals("010200000002aabb", encoded.hex())
        assertEquals("", CanonicalBinary.fields { }.hex())
    }

    @Test
    fun fieldWriterRejectsZeroOutOfRangeDuplicateAndDecreasingTags() {
        val empty = CanonicalBytes(ByteArray(0))

        assertFailsWith<IllegalArgumentException> {
            CanonicalBinary.fields { field(0, empty) }
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalBinary.fields { field(0x1_0000, empty) }
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalBinary.fields {
                field(1, empty)
                field(1, empty)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalBinary.fields {
                field(2, empty)
                field(1, empty)
            }
        }
    }

    /**
     * The domain byte is what keeps two identity namespaces apart, so this table has to cover every
     * [CanonicalRootKind] and the bytes have to be distinct — two kinds sharing one would let a
     * model-image key collide with a basemap-tile key of the same shape.
     *
     * It iterated its own hand-written list with no size guard until X2, and the gap was not
     * hypothetical: F-2 added `MODEL_GEOMETRY` and `MODEL_IMAGE`, and neither constant's permanent
     * domain byte was asserted anywhere in the tree. Both rows below, and the two assertions above
     * the loop, are what close that.
     */
    @Test
    fun rootsUseExactMagicSchemaAndPermanentDomainBytes() {
        val expected = listOf(
            CanonicalRootKind.FRAME to "524e47430101",
            CanonicalRootKind.EXTERNAL_RESOURCE to "524e47430102",
            CanonicalRootKind.GEOMETRY_PROGRAM to "524e47430103",
            CanonicalRootKind.INTERNAL_PIPELINE to "524e47430104",
            CanonicalRootKind.OFFSCREEN_SURFACE to "524e47430105",
            CanonicalRootKind.BASEMAP_TILE to "524e47430106",
            CanonicalRootKind.MODEL_GEOMETRY to "524e47430107",
            CanonicalRootKind.MODEL_IMAGE to "524e47430108",
            CanonicalRootKind.LABEL to "524e47430109",
            CanonicalRootKind.GLYPH_ATLAS to "524e4743010a",
            CanonicalRootKind.SPRITE_ATLAS to "524e4743010b",
            CanonicalRootKind.DEM_TEXTURE to "524e4743010c",
        )

        assertEquals(
            CanonicalRootKind.entries.size,
            expected.size,
            "a root kind absent from this table has its permanent domain byte asserted nowhere",
        )
        assertEquals(
            expected.size,
            expected.map { it.second }.toSet().size,
            "two root kinds sharing a domain byte would collide two identity namespaces",
        )
        expected.forEach { (kind, hex) ->
            assertEquals(hex, CanonicalBinary.root(kind) { }.hex())
        }
        assertEquals(
            "524e47430102010200000002aabb",
            CanonicalBinary.root(CanonicalRootKind.EXTERNAL_RESOURCE) {
                field(0x0102, CanonicalBytes(byteArrayOf(0xaa.toByte(), 0xbb.toByte())))
            }.hex(),
        )
    }

    @Test
    fun compositeWritersRejectCheckedSizeOverflowBeforeAllocation() {
        val payload = CanonicalBytes(ByteArray(32_768))
        val overflowingList = List(65_536) { payload }

        assertFailsWith<IllegalArgumentException> {
            CanonicalBinary.list(overflowingList)
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalBinary.fields {
                for (tag in 1..0xffff) {
                    field(tag, payload)
                }
            }
        }
    }

    @Test
    fun canonicalBytesDefensivelyCopyAndUseContentValueSemantics() {
        val input = byteArrayOf(0x01, 0x02, 0x03)
        val value = CanonicalBytes(input)
        val equal = CanonicalBytes(byteArrayOf(0x01, 0x02, 0x03))

        input.fill(0)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), value.bytes)

        val returned = value.bytes
        returned.fill(0)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), value.bytes)
        assertEquals(value, equal)
        assertEquals(value.hashCode(), equal.hashCode())
        assertNotEquals(value, CanonicalBytes(byteArrayOf(0x01, 0x02)))
        assertEquals("CanonicalBytes(<redacted>)", value.toString())
        assertFalse(value.toString().contains("010203"))
    }

    private fun CanonicalBytes.hex(): String = bytes.toLowercaseHex()
}

private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
    this@toLowercaseHex.forEach { byte ->
        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val HEX_DIGITS: String = "0123456789abcdef"
