package com.rohittp.reng.internal.terrain

import com.rohittp.reng.internal.image.DecodedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The CPU elevation path, over the texels Rentile hands across rather than over encoded bytes.
 *
 * **Every fixture below is a raw RGBA array, and that replaced a real PNG rather than simplifying
 * one.** RenG decoded a DEM itself until Rentile `0.7.0`, and five of the corpus's six terrain styles
 * serve **WebP**, so a PNG fixture here would assert the shape of a container RenG never sees. What
 * arrives is what [demTexels] builds: tightly packed RGBA8, four bytes a texel in R, G, B, A order,
 * rows top-down from the tile's north edge.
 *
 * The anti-circularity convention `PngDecoderTest.kt` documents still holds and is now stronger:
 * every expected elevation is computed **by hand** from the channel triples the fixture writes -- the
 * arithmetic is spelled out in each test's KDoc -- rather than by running the formula under test, and
 * no decoder sits anywhere in the path.
 */
class DemElevationTest {

    /**
     * The Mapbox fixture's four texels, in row order, with the packing done by hand:
     *
     * - `(0, 0)` = RGB(0, 0, 0) -> packed 0 -> `-10000.0 + 0 * 0.1` = **-10000.0 exactly**. This is
     *   the encoding's floor; design section 11 rules out no-data handling, so it is a real height
     *   rather than a hole.
     * - `(1, 0)` = RGB(1, 134, 160) -> `65536 + 134*256 + 160` = `65536 + 34304 + 160` = 100000 ->
     *   `-10000.0 + 10000.0` = **0.0** (sea level).
     * - `(0, 1)` = RGB(2, 66, 119) -> `131072 + 16896 + 119` = 148087 -> `-10000.0 + 14808.7` =
     *   **4808.7** (Mont Blanc, to the decimetre the encoding resolves).
     * - `(1, 1)` = RGB(0, 0, 1) -> packed 1 -> `-10000.0 + 0.1` = **-9999.9**, one quantum above the
     *   floor, which is what separates "the encoding's zero" from "no data" if anyone is tempted.
     *
     * Reading row 1 through `elevationAt(x, y)` is also what pins `y` as the row index: transposing
     * the two swaps 0.0 with 4808.7.
     */
    @Test
    fun decodesMapboxTexelsToHandComputedMetres() {
        val tile = assertNotNull(demElevationTile(mapboxTexels(), DemEncoding.MAPBOX))

        assertEquals(2, tile.sizePx)
        assertEquals(-10_000.0, tile.elevationAt(0, 0), 0.0, "a zero Mapbox triple is exactly the encoding's floor")
        assertEquals(0.0, tile.elevationAt(1, 0), 1e-9)
        assertEquals(4808.7, tile.elevationAt(0, 1), 1e-9)
        assertEquals(-9999.9, tile.elevationAt(1, 1), 1e-9)
    }

    /**
     * The Terrarium fixture, again by hand:
     *
     * - `(0, 0)` = RGB(128, 0, 0) -> `128*256 + 0 + 0/256 - 32768` = **0.0 exactly** (sea level).
     * - `(1, 0)` = RGB(128, 100, 128) -> `32768 + 100 + 0.5 - 32768` = **100.5**.
     * - `(0, 1)` = RGB(127, 255, 255) -> `32512 + 255 + 255/256 - 32768` = **-0.00390625**, just
     *   below sea level, which is the value a swapped red and green would move by kilometres.
     * - `(1, 1)` = RGB(146, 200, 64) -> `37376 + 200 + 0.25 - 32768` = **4808.25**.
     */
    @Test
    fun decodesTerrariumTexelsToHandComputedMetres() {
        val tile = assertNotNull(demElevationTile(terrariumTexels(), DemEncoding.TERRARIUM))

        assertEquals(0.0, tile.elevationAt(0, 0), 0.0, "a Terrarium (128, 0, 0) triple is exactly sea level")
        assertEquals(100.5, tile.elevationAt(1, 0), 1e-9)
        assertEquals(-0.00390625, tile.elevationAt(0, 1), 1e-12)
        assertEquals(4808.25, tile.elevationAt(1, 1), 1e-9)
    }

    /**
     * The encoding is the descriptor's word and nothing about the texels announces it, so the same
     * bytes read under the other formula are a different mountain rather than a failure. That is why
     * `demEncodingOf` translates Rentile's enum in a `when` with no `else`.
     *
     * `(0, 0)` = RGB(0, 0, 0) under Terrarium is `0*256 + 0 + 0/256 - 32768` = **-32768.0**, where
     * Mapbox reads -10000.0; `(1, 0)` = RGB(1, 134, 160) is `256 + 134 + 160/256 - 32768` =
     * **-32377.375**, where Mapbox reads 0.0.
     */
    @Test
    fun theSameTexelsUnderTheOtherEncodingAreADifferentMountain() {
        val tile = assertNotNull(demElevationTile(mapboxTexels(), DemEncoding.TERRARIUM))

        assertEquals(-32_768.0, tile.elevationAt(0, 0), 0.0, "0*256 + 0 + 0/256 - 32768")
        assertEquals(-32_377.375, tile.elevationAt(1, 0), 0.0, "1*256 + 134 + 160/256 - 32768")
    }

    /**
     * **The check this file used to make and no longer does, asserted as a decision.**
     *
     * A DEM with a translucent texel used to be refused outright, because Rentile decoded these
     * pixels into a **premultiplied** N32 bitmap: RGB(1, 134, 160) at alpha 128 came back as roughly
     * RGB(0, 67, 80) and decoded as a mountain range that is not there. Rentile `0.7.0` reads them as
     * `UNPREMUL` and documents that it preserves whatever alpha the image carried, so the channels
     * are the ones the DEM packed and the height is the one it encodes -- and refusing the tile would
     * now trade a correct height for flat ground.
     *
     * The assertion is that the elevations are **identical** to the opaque fixture's, texel for
     * texel, which is the property that makes admitting them safe rather than merely convenient.
     */
    @Test
    fun aTranslucentTexelDecodesToTheHeightItsChannelsPack() {
        val opaque = assertNotNull(demElevationTile(mapboxTexels(), DemEncoding.MAPBOX))
        val translucent = assertNotNull(demElevationTile(mapboxTexels(alpha = 128), DemEncoding.MAPBOX))

        assertEquals(0.0, translucent.elevationAt(1, 0), 1e-9, "RGB(1, 134, 160) is sea level at any alpha")
        assertEquals(
            opaque.elevationSnapshot().toList(),
            translucent.elevationSnapshot().toList(),
            "alpha changes no height, or the premultiplication guarantee is not being relied on",
        )
    }

    /**
     * Terrain degrades and never fails a frame (ADR 0041), so texels this arithmetic cannot describe
     * come back as `null` rather than as a thrown `require` out of [DemElevationTile]'s own `init`.
     *
     * Neither case is reachable through an acquisition -- [demTexelsOf] admits only a positive square
     * of exactly the declared size -- and both are reachable by any caller assembling a [DemTexels]
     * itself, which is what the CPU path is for.
     */
    @Test
    fun refusesRatherThanThrowsForTexelsThatAreNotAPositiveSquare() {
        assertNull(
            demElevationTile(DemTexels(DecodedImage(2, 1, ByteArray(2 * 4)), "d"), DemEncoding.MAPBOX),
            "2 wide and 1 tall is not a tile this arithmetic describes",
        )
        assertNull(
            demElevationTile(DemTexels(DecodedImage(0, 0, ByteArray(0)), "d"), DemEncoding.MAPBOX),
            "a zero-edge tile has no elevations at all",
        )
        assertNull(
            demElevationTile(DemTexels(DecodedImage(2, 2, ByteArray(2 * 2 * 4 - 1)), "d"), DemEncoding.MAPBOX),
            "an array short of one RGBA texel per pixel would index outside itself",
        )
    }

    /** A snapshot is a copy: mutating what a caller was handed cannot reach back into the tile. */
    @Test
    fun anElevationSnapshotCannotBeWrittenBackThrough() {
        val tile = assertNotNull(demElevationTile(mapboxTexels(), DemEncoding.MAPBOX))

        val snapshot = tile.elevationSnapshot()
        snapshot[0] = 42.0

        assertEquals(-10_000.0, tile.elevationAt(0, 0), 0.0)
        assertEquals(-10_000.0, tile.elevationSnapshot()[0], 0.0)
    }

    // 2x2. Row 0: RGB(0,0,0), RGB(1,134,160). Row 1: RGB(2,66,119), RGB(0,0,1).
    private fun mapboxTexels(alpha: Int = 255): DemTexels = demTexels(
        listOf(
            listOf(0, 0, 0), listOf(1, 134, 160),
            listOf(2, 66, 119), listOf(0, 0, 1),
        ),
        alpha,
    )

    // 2x2. Row 0: RGB(128,0,0), RGB(128,100,128). Row 1: RGB(127,255,255), RGB(146,200,64).
    private fun terrariumTexels(): DemTexels = demTexels(
        listOf(
            listOf(128, 0, 0), listOf(128, 100, 128),
            listOf(127, 255, 255), listOf(146, 200, 64),
        ),
        alpha = 255,
    )

    /** Four RGB triples in row order as the tightly packed RGBA8 an engine hands over. */
    private fun demTexels(triples: List<List<Int>>, alpha: Int): DemTexels {
        val rgba = ByteArray(triples.size * 4)
        triples.forEachIndexed { index, (red, green, blue) ->
            rgba[index * 4] = red.toByte()
            rgba[index * 4 + 1] = green.toByte()
            rgba[index * 4 + 2] = blue.toByte()
            rgba[index * 4 + 3] = alpha.toByte()
        }
        return DemTexels(DecodedImage(width = 2, height = 2, rgba = rgba), contentDigest = "digest")
    }
}
