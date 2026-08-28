package com.rohittp.reng.internal.firewall

import com.rohittp.rentile.internal.glyph.Glyphs
import com.rohittp.rentile.internal.mvt.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * The only independent check available that [LABEL_MVT_BYTES] and the three Glyph Ranges beside it are
 * the documents they claim to be.
 *
 * The fixture is hand-encoded, which buys no coupling to anything and costs a reviewer having to read
 * protobuf wire format -- and a wrong varint is a decode failure a long way from its cause. Rentile
 * generates both schemas with Square Wire, and Wire emits ordinary **public** Kotlin classes that
 * `explicitApi` does not reach, so `Tile`, `Glyphs` and their `ADAPTER`s are in Rentile's published
 * `kmp/api/kmp.klib.api` despite their `internal` package names. Decoding here therefore needs **no
 * build-file change and no added dependency**: Rentile's own `wire-runtime` is transitively visible to
 * this test compilation on every target.
 *
 * That reachability is an accident of code generation rather than a promise. Rentile could make these
 * types genuinely `internal` in any release and this file would stop compiling with no ABI-check warning
 * -- they are Rentile's ABI, not RenG's. That is exactly why the fixture itself is hand-encoded and this
 * is only the cross-check: if this file has to go, the suite keeps working and only loses its
 * independent reading of the bytes.
 */
class LabelFixtureWireCrossCheckTest {

    @Test
    fun theHandEncodedVectorTileIsTheDocumentTheFixtureClaims() {
        val decoded = Tile.ADAPTER.decode(LABEL_MVT_BYTES)

        assertEquals(listOf("place", "town_label"), decoded.layers.map { it.name })
        decoded.layers.forEach { layer ->
            assertEquals(2, layer.version, "MVT 2.1")
            assertEquals(4096, layer.extent)
            assertEquals(listOf("name"), layer.keys)
            val feature = layer.features.single()
            assertEquals(Tile.GeomType.POINT, feature.type)
            assertEquals(listOf(0, 0), feature.tags, "the single feature's `name` -> values[0]")
            // MoveTo with a repeat count of one, then the zig-zag encoded (2048, 2048) anchor: mid-tile
            // at a 4096 extent, which is what keeps the point inside the window the assembler tests.
            assertContentEquals(listOf(9, 4096, 4096), feature.geometry)
        }
        assertEquals("AĀ", decoded.layers[0].values.single().string_value)
        assertEquals("B", decoded.layers[1].values.single().string_value)

        // Re-encoding through Wire reproduces the same length, which is the check that catches a
        // hand-written varint that happens to decode to the right value from the wrong bytes.
        assertEquals(LABEL_MVT_BYTES.size, Tile.ADAPTER.encode(decoded).size)
    }

    @Test
    fun theHandEncodedGlyphRangesAreTheDocumentsTheFixtureClaims() {
        val cases = listOf(
            Triple(LABEL_SANS_RANGE_0_BYTES, LABEL_SANS_STACK to "0-255", 65),
            Triple(LABEL_SANS_RANGE_256_BYTES, LABEL_SANS_STACK to "256-511", 256),
            Triple(LABEL_SERIF_RANGE_0_BYTES, LABEL_SERIF_STACK to "0-255", 66),
        )
        cases.forEach { (bytes, identity, codepoint) ->
            val (stackName, range) = identity
            val stack = Glyphs.ADAPTER.decode(bytes).stacks.single()
            assertEquals(stackName, stack.name)
            assertEquals(range, stack.range, "the decoder checks this against the url's {range} token")
            val glyph = stack.glyphs.single()
            assertEquals(codepoint, glyph.id)
            assertEquals(8, glyph.width)
            assertEquals(10, glyph.height)
            assertEquals(1, glyph.left)
            assertEquals(-12, glyph.top, "a negative bearing, so the sint32 zig-zag is exercised")
            assertEquals(12, glyph.advance)
            val bitmap = assertNotNull(glyph.bitmap)
            assertEquals(
                (8 + 6) * (10 + 6),
                bitmap.size,
                "the three-pixel signed-distance-field buffer on every side, which the decoder enforces",
            )
            assertEquals(bytes.size, Glyphs.ADAPTER.encode(Glyphs.ADAPTER.decode(bytes)).size)
        }
    }
}
