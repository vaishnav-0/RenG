package com.rohittp.reng.internal.terrain

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Every PNG fixture below is a real PNG generated once via CPython's zlib/struct/zlib.crc32 modules
// and pasted as a base64 literal, following the anti-circularity convention PngDecoderTest.kt
// documents in full: none of RenG's own decoder code produced these bytes, and every expected
// elevation below was computed by hand from the channel triples the generator was given (the
// arithmetic is written out in each test's KDoc) rather than by running the formula under test.
// Regenerate with:
//
// python3 - <<'PY'
// import zlib, struct, base64
// SIG = b"\x89PNG\r\n\x1a\n"
// def chunk(kind, payload):
//     crc = struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
//     return struct.pack(">I", len(payload)) + kind + payload + crc
// def build(w, h, colour, rows):  # colour 2 = RGB, 6 = RGBA; bit depth 8; filter 0 on every row
//     raw = b"".join(b"\x00" + bytes(row) for row in rows)
//     return (SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, colour, 0, 0, 0))
//             + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
// print(base64.b64encode(build(2, 2, 2, [[0,0,0, 1,134,160], [2,66,119, 0,0,1]])).decode())
// print(base64.b64encode(build(2, 2, 2, [[128,0,0, 128,100,128], [127,255,255, 146,200,64]])).decode())
// print(base64.b64encode(build(2, 1, 2, [[0,0,0, 1,134,160]])).decode())
// print(base64.b64encode(build(2, 2, 6, [[0,0,0,255, 1,134,160,128], [2,66,119,255, 0,0,1,255]])).decode())
// PY
class DemElevationTest {
    private val ceiling = 64L * 1024L * 1024L

    /**
     * The Mapbox fixture's four texels, in PNG row order, with the packing done by hand:
     *
     * - `(0, 0)` = RGB(0, 0, 0) -> packed 0 -> `-10000.0 + 0 * 0.1` = **-10000.0 exactly**. This is
     *   the encoding's floor and the boundary the task names; design section 11 rules out no-data
     *   handling, so it is a real height rather than a hole.
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
        val decoded =
            decodeDemElevation(mapboxDemPng, DemEncoding.MAPBOX, tileSizePx = 2, maximumDecodedBytes = ceiling)

        val tile = assertIs<DemDecodeResult.Success>(decoded).tile
        assertEquals(2, tile.sizePx)
        assertEquals(-10_000.0, tile.elevationAt(0, 0), 0.0, "a zero Mapbox triple is exactly the encoding's floor")
        assertEquals(0.0, tile.elevationAt(1, 0), 1e-9)
        assertEquals(4808.7, tile.elevationAt(0, 1), 1e-9)
        assertEquals(-9999.9, tile.elevationAt(1, 1), 1e-9)
    }

    /**
     * The Terrarium fixture's four texels, again by hand. Terrarium's blue channel is a fraction of a
     * metre (`blue / 256.0`), so three of these four are exact in `Double` rather than merely close:
     *
     * - `(0, 0)` = RGB(128, 0, 0) -> `128*256 + 0 + 0 - 32768` = `32768 - 32768` = **0.0 exactly**,
     *   the boundary the task names: Terrarium's offset is a whole red step.
     * - `(1, 0)` = RGB(128, 100, 128) -> `32768 + 100 + 128/256 - 32768` = **100.5 exactly**
     *   (`128/256` is `0.5`).
     * - `(0, 1)` = RGB(127, 255, 255) -> `32512 + 255 + 255/256 - 32768` = `32767.99609375 - 32768` =
     *   **-0.00390625 exactly** (`-1/256`), one quantum *below* sea level — Terrarium's whole point
     *   is that it can say that, and the Mapbox formula on the same triple cannot come near it.
     * - `(1, 1)` = RGB(146, 200, 64) -> `37376 + 200 + 0.25 - 32768` = **4808.25 exactly**.
     */
    @Test
    fun decodesTerrariumTexelsToHandComputedMetres() {
        val decoded =
            decodeDemElevation(terrariumDemPng, DemEncoding.TERRARIUM, tileSizePx = 2, maximumDecodedBytes = ceiling)

        val tile = assertIs<DemDecodeResult.Success>(decoded).tile
        assertEquals(0.0, tile.elevationAt(0, 0), 0.0, "a Terrarium 128/0/0 triple is exactly sea level")
        assertEquals(100.5, tile.elevationAt(1, 0), 0.0, "128/256 is exactly one half")
        assertEquals(-0.00390625, tile.elevationAt(0, 1), 0.0, "one 1/256 quantum below sea level")
        assertEquals(4808.25, tile.elevationAt(1, 1), 0.0, "200 + 64/256 metres above 4808")
    }

    /**
     * The encoding argument selects the formula, rather than one formula being applied always. Decoded
     * as Terrarium, the *Mapbox* fixture's own triples mean something entirely different, computed by
     * hand from the same channel values:
     *
     * - RGB(0, 0, 0) -> `0 + 0 + 0 - 32768` = **-32768.0**, not -10000.0.
     * - RGB(1, 134, 160) -> `256 + 134 + 160/256 - 32768` = `390.625 - 32768` = **-32377.375**, not 0.
     *
     * Without this, a decode that ignored its `encoding` parameter entirely would still pass one of
     * the two fixture tests above, and the pair of them could not say which formula ran on what.
     */
    @Test
    fun theSameTexelsMeanDifferentHeightsUnderTheTwoEncodings() {
        val asTerrarium =
            decodeDemElevation(mapboxDemPng, DemEncoding.TERRARIUM, tileSizePx = 2, maximumDecodedBytes = ceiling)

        val tile = assertIs<DemDecodeResult.Success>(asTerrarium).tile
        assertEquals(-32_768.0, tile.elevationAt(0, 0), 0.0, "Terrarium's floor, not Mapbox's")
        assertEquals(-32_377.375, tile.elevationAt(1, 0), 0.0, "1*256 + 134 + 160/256 - 32768")
    }

    /**
     * Rentile validates a DEM with `Image.makeFromEncoded`, which is multi-format, so
     * `ValidatedDemTile.bytes` is not guaranteed to be a PNG at all. RenG owns one decoder and it is a
     * PNG decoder, so anything else is refused rather than misread. The payload here is a JFIF header
     * — a plausible thing for a tile server to answer with, not random noise.
     */
    @Test
    fun refusesAPayloadThatIsNotAPng() {
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        )

        val decoded = decodeDemElevation(jpegHeader, DemEncoding.MAPBOX, tileSizePx = 2, maximumDecodedBytes = ceiling)

        assertEquals(DemDecodeResult.Rejected(DemReject.UNDECODABLE), decoded)
    }

    /**
     * Rentile checks a DEM's dimensions against its own policy limits and never against the
     * `tileSizePx` its `TerrainSourceDescriptor` declares, so a source can answer with an image of a
     * size the sampling arithmetic was never built for. Both halves are checked: a square image of the
     * wrong size, and an image whose width alone agrees — the second is what a check reading only
     * `image.width` would let through.
     */
    @Test
    fun refusesAnImageWhoseDimensionsDisagreeWithTheDeclaredTileSize() {
        assertEquals(
            DemDecodeResult.Rejected(DemReject.DIMENSIONS),
            decodeDemElevation(mapboxDemPng, DemEncoding.MAPBOX, tileSizePx = 4, maximumDecodedBytes = ceiling),
            "a 2x2 image is not the 4x4 the source declared",
        )
        assertEquals(
            DemDecodeResult.Rejected(DemReject.DIMENSIONS),
            decodeDemElevation(oblongDemPng, DemEncoding.MAPBOX, tileSizePx = 2, maximumDecodedBytes = ceiling),
            "2 wide and 1 tall agrees with tileSizePx on one axis only",
        )
    }

    /**
     * Rentile decodes DEM pixels into a **premultiplied** N32 bitmap
     * (`DefaultBasemapRasterizer.kt:1671-1672`), so a texel with alpha below 255 has its R/G/B scaled
     * before any elevation formula reads them — the fixture's second texel,
     * RGB(1, 134, 160) at alpha 128, would come back as roughly RGB(0, 67, 80) and decode as a
     * mountain range that is not there. RenG refuses the tile instead. This is also the one case that
     * distinguishes reusing `validatesDemTerrainEncoding` from merely decoding: the bytes here are a
     * perfectly valid PNG of exactly the declared size.
     */
    @Test
    fun refusesATileCarryingATranslucentTexel() {
        val decoded =
            decodeDemElevation(translucentDemPng, DemEncoding.MAPBOX, tileSizePx = 2, maximumDecodedBytes = ceiling)

        assertEquals(DemDecodeResult.Rejected(DemReject.NON_OPAQUE), decoded)
    }

    /**
     * Terrain degrades and never fails a frame (ADR 0041), so a descriptor declaring a
     * nonsensical `tileSizePx` must come back as a refusal rather than as a thrown `require`. Zero is
     * the case that matters: `decodePng` admits only positive dimensions, so a zero declaration can
     * never match a decoded image and falls out through the dimension check with everything else.
     */
    @Test
    fun refusesRatherThanThrowsForANonPositiveDeclaredTileSize() {
        assertEquals(
            DemDecodeResult.Rejected(DemReject.DIMENSIONS),
            decodeDemElevation(mapboxDemPng, DemEncoding.MAPBOX, tileSizePx = 0, maximumDecodedBytes = ceiling),
        )
    }

    /** A snapshot is a copy: mutating what a caller was handed cannot reach back into the tile. */
    @Test
    fun anElevationSnapshotCannotBeWrittenBackThrough() {
        val tile = assertIs<DemDecodeResult.Success>(
            decodeDemElevation(mapboxDemPng, DemEncoding.MAPBOX, tileSizePx = 2, maximumDecodedBytes = ceiling),
        ).tile

        val snapshot = tile.elevationSnapshot()
        snapshot[0] = 42.0

        assertEquals(-10_000.0, tile.elevationAt(0, 0), 0.0)
        assertEquals(-10_000.0, tile.elevationSnapshot()[0], 0.0)
    }
}

// 2x2 truecolour (colour type 2). Row 0: RGB(0,0,0), RGB(1,134,160). Row 1: RGB(2,66,119), RGB(0,0,1).
private val mapboxDemPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFUlEQVR42mNgYGBgbFvAwORUDmQAAA0BAeR5ASizAAAAAElFTkSuQmCC",
)

// 2x2 truecolour. Row 0: RGB(128,0,0), RGB(128,100,128). Row 1: RGB(127,255,255), RGB(146,200,64).
private val terrariumDemPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mNoYGBoSGlgqP//f9IJBwAiiQX8egYcHgAAAABJRU5ErkJggg==",
)

// 2 wide, 1 tall, truecolour -- square on one axis only.
private val oblongDemPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAD0lEQVR42mNgYGBgbFsAAAG2ASg0GGDWAAAAAElFTkSuQmCC",
)

// 2x2 truecolour+alpha (colour type 6) carrying the Mapbox fixture's pixels, with texel (1, 0) at
// alpha 128 and every other texel opaque.
private val translucentDemPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGklEQVR42mNgYGD4z9i2oIGByan8PwMD438AKt0FYT1GR1QAAAAASUVORK5CYII=",
)
