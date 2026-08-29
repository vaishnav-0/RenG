package com.rohittp.reng.internal.terrain

import com.rohittp.reng.internal.image.DecodedImage
import kotlin.io.encoding.Base64
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cycle E-terrain task 5.
 *
 * **The synthetic fixture is built so that every mistake this file exists to catch changes a
 * different channel.** Every texel of tile `t` is `RGB(t, x, y)`, so:
 *
 * - a ring taken from the centre tile's own edge instead of the neighbour's changes **red**;
 * - a corner taken from the wrong diagonal changes **red**;
 * - the right tile's wrong column changes **green**, and its wrong row changes **blue**;
 * - an interior shifted by one changes green or blue;
 * - a transposed lookup swaps green with blue.
 *
 * That matters because a DEM is the one texture where a plausible wrong answer is the dangerous one:
 * every one of those mistakes compiles, renders, and produces terrain. F-2 found seven vacuous checks
 * sitting at a symmetry point of the thing under test, and `RGB(t, x, y)` has no symmetry left --
 * `t`, `x` and `y` are separately observable in every assertion below.
 *
 * [twoAdjacentTilesSampleBitIdenticalHeightsAtTheirSharedEdge] is the assertion the whole task exists
 * for, and it is the one case here driven by **real PNG bytes through the real decoder**, decoded to
 * real metres, rather than by the synthetic fixture.
 */
class PaddedDemTextureTest {

    private val ceiling = 64L * 1024L * 1024L

    // z = 2 puts four tiles on a side, so a centre at (1, 1) has all eight neighbours in the world
    // and a centre at (0, 1) has a western neighbour only across the antimeridian.
    private val centre = DemTileCoordinate(z = 2, x = 1, y = 1)

    /**
     * The 3x3 neighbourhood, ids running 1..9 in reading order from the north-west, so the centre is
     * 5 and each neighbour's id names its direction unambiguously in a failure message.
     */
    private fun neighbourhood(): MutableMap<DemTileCoordinate, DemTexels> {
        val tiles = LinkedHashMap<DemTileCoordinate, DemTexels>()
        var id = 1
        for (y in 0..2) {
            for (x in 0..2) {
                tiles[DemTileCoordinate(z = 2, x = x, y = y)] = syntheticTile(id)
                id++
            }
        }
        return tiles
    }

    @Test
    fun theInteriorIsTheCentreTilesOwnTexelsShiftedByExactlyOne() {
        val padded = requireNotNull(padDemTexture(centre, neighbourhood()))

        assertEquals(FIXTURE_SIZE, padded.interiorSizePx)
        assertEquals(FIXTURE_SIZE + 2, padded.image.width)
        assertEquals(FIXTURE_SIZE + 2, padded.image.height)
        for (y in 0 until FIXTURE_SIZE) {
            for (x in 0 until FIXTURE_SIZE) {
                assertEquals(
                    Triple(CENTRE_ID, x, y),
                    texelAt(padded.image, x + 1, y + 1),
                    "the centre tile's ($x, $y) belongs at padded (${x + 1}, ${y + 1})",
                )
            }
        }
    }

    /**
     * The mistake that looks right: filling the east ring from the centre's own column `N-1` rather
     * than from the eastern neighbour's column `0`. It renders, it is smooth, and it reopens the
     * crack the ring exists to close -- so each direction is asserted against the neighbour's facing
     * column *and* against the centre's own, which is what makes the second half of each pair a
     * statement rather than a decoration.
     */
    @Test
    fun eachEdgeRingCarriesTheNeighboursFacingColumnRatherThanACopyOfItsOwn() {
        val padded = requireNotNull(padDemTexture(centre, neighbourhood())).image
        val last = FIXTURE_SIZE - 1

        for (y in 0 until FIXTURE_SIZE) {
            assertEquals(Triple(WEST_ID, last, y), texelAt(padded, 0, y + 1), "west ring row $y")
            assertNotEquals(
                texelAt(padded, 1, y + 1),
                texelAt(padded, 0, y + 1),
                "the west ring must not be a copy of the centre's own western column",
            )
            assertEquals(Triple(EAST_ID, 0, y), texelAt(padded, FIXTURE_SIZE + 1, y + 1), "east ring row $y")
            assertNotEquals(
                texelAt(padded, FIXTURE_SIZE, y + 1),
                texelAt(padded, FIXTURE_SIZE + 1, y + 1),
                "the east ring must not be a copy of the centre's own eastern column",
            )
        }
        for (x in 0 until FIXTURE_SIZE) {
            assertEquals(Triple(NORTH_ID, x, last), texelAt(padded, x + 1, 0), "north ring column $x")
            assertNotEquals(
                texelAt(padded, x + 1, 1),
                texelAt(padded, x + 1, 0),
                "the north ring must not be a copy of the centre's own northern row",
            )
            assertEquals(Triple(SOUTH_ID, x, 0), texelAt(padded, x + 1, FIXTURE_SIZE + 1), "south ring column $x")
            assertNotEquals(
                texelAt(padded, x + 1, FIXTURE_SIZE),
                texelAt(padded, x + 1, FIXTURE_SIZE + 1),
                "the south ring must not be a copy of the centre's own southern row",
            )
        }
    }

    /**
     * A corner taken from the wrong diagonal is invisible to every axis-aligned assertion above: it
     * is one texel, it comes from a real neighbouring tile, and it sits where nothing else looks.
     * All four are named here with the exact opposite corner each must carry.
     */
    @Test
    fun eachCornerComesFromItsOwnDiagonalNeighboursOppositeCorner() {
        val padded = requireNotNull(padDemTexture(centre, neighbourhood())).image
        val last = FIXTURE_SIZE - 1
        val outer = FIXTURE_SIZE + 1

        assertEquals(Triple(NORTH_WEST_ID, last, last), texelAt(padded, 0, 0), "north-west corner")
        assertEquals(Triple(NORTH_EAST_ID, 0, last), texelAt(padded, outer, 0), "north-east corner")
        assertEquals(Triple(SOUTH_WEST_ID, last, 0), texelAt(padded, 0, outer), "south-west corner")
        assertEquals(Triple(SOUTH_EAST_ID, 0, 0), texelAt(padded, outer, outer), "south-east corner")
    }

    /**
     * **The assertion the task exists for**, and the only one here driven by real PNG bytes: two real
     * DEM tiles, decoded through [decodeDemTexels] and therefore through Cycle C's real PNG decoder,
     * padded independently, and read back as real metres through [demElevationMetres].
     *
     * Two claims, and the second is what makes the first non-vacuous:
     *
     * 1. The **ordered pair of texels straddling the shared edge is identical from both sides**, which
     *    is design section 4's "both sides of an edge read identical source values" stated in texels.
     *    Any sampling rule that depends only on position relative to the texel grid therefore returns
     *    the same value to both draws.
     * 2. Under GL's own `GL_NEAREST` rule, both tiles resolve the shared edge to the **eastern tile's
     *    column 0**, a height the western tile does not contain anywhere.
     *
     * The two fixtures are separated by a kilometre and a half of elevation on purpose: if the ring
     * were filled from each tile's own edge column the two sides would disagree by about 6,477 m, and
     * a fixture whose neighbours happened to be at similar heights would let that pass.
     */
    @Test
    fun twoAdjacentTilesSampleBitIdenticalHeightsAtTheirSharedEdge() {
        val west = DemTileCoordinate(z = 2, x = 1, y = 1)
        val east = DemTileCoordinate(z = 2, x = 2, y = 1)
        val tiles = mapOf(
            west to decodedTexels(westDemPng, "digest-west"),
            east to decodedTexels(eastDemPng, "digest-east"),
        )

        val paddedWest = requireNotNull(padDemTexture(west, tiles))
        val paddedEast = requireNotNull(padDemTexture(east, tiles))

        for (row in 0 until FIXTURE_SIZE) {
            val fromWest = listOf(
                metresAt(paddedWest.image, FIXTURE_SIZE, row + 1),
                metresAt(paddedWest.image, FIXTURE_SIZE + 1, row + 1),
            )
            val fromEast = listOf(
                metresAt(paddedEast.image, 0, row + 1),
                metresAt(paddedEast.image, 1, row + 1),
            )
            assertEquals(fromWest, fromEast, "the texels straddling the shared edge, read from either tile")

            val expected = 3107.2 + 0.1 * row
            assertEquals(
                expected,
                nearestSampledMetres(paddedWest, tileU = 1.0, tileRow = row),
                1e-9,
                "the western tile's own eastern boundary samples its neighbour's first column",
            )
            assertEquals(
                expected,
                nearestSampledMetres(paddedEast, tileU = 0.0, tileRow = row),
                1e-9,
                "and so does the eastern tile's own western boundary",
            )
        }

        // Without this the assertions above would also hold for a ring copied from each tile's own
        // edge, since both sides would then read "their own" and the pairs would still be equal.
        assertEquals(-3369.6, metresAt(paddedWest.image, FIXTURE_SIZE, 1), 1e-9)
        assertNotEquals(
            metresAt(paddedWest.image, FIXTURE_SIZE, 1),
            metresAt(paddedWest.image, FIXTURE_SIZE + 1, 1),
            "the fixtures must genuinely disagree across the edge or nothing above is tested",
        )
    }

    /**
     * A missing neighbour replicates the centre's own edge -- the only fill that invents no elevation
     * -- and the value says so, which is the whole of "it cannot be mistaken for real elevation".
     */
    @Test
    fun aMissingNeighbourReplicatesTheCentresOwnEdgeAndDeclaresTheFill() {
        val tiles = neighbourhood()
        tiles.remove(DemTileCoordinate(z = 2, x = 2, y = 1))
        tiles.remove(DemTileCoordinate(z = 2, x = 2, y = 2))

        val padded = requireNotNull(padDemTexture(centre, tiles))

        assertEquals(
            mapOf(
                DemNeighbour.EAST to DemNeighbourFill.ABSENT,
                DemNeighbour.SOUTH_EAST to DemNeighbourFill.ABSENT,
            ),
            padded.filledNeighbours,
            "a fill is invisible in the texels by design, so the value is where it must be visible",
        )
        for (y in 0 until FIXTURE_SIZE) {
            assertEquals(
                Triple(CENTRE_ID, FIXTURE_SIZE - 1, y),
                texelAt(padded.image, FIXTURE_SIZE + 1, y + 1),
                "the east ring replicates the centre's own eastern column",
            )
        }
        assertEquals(
            Triple(CENTRE_ID, FIXTURE_SIZE - 1, FIXTURE_SIZE - 1),
            texelAt(padded.image, FIXTURE_SIZE + 1, FIXTURE_SIZE + 1),
            "and the south-east corner replicates the centre's own",
        )
        assertEquals(
            Triple(SOUTH_ID, 0, 0),
            texelAt(padded.image, 1, FIXTURE_SIZE + 1),
            "a present neighbour is unaffected by an absent sibling",
        )
    }

    /**
     * The world ends in `y` and wraps in `x`, so the two reasons are structurally different: a tile on
     * the top row of the world has no northern neighbour in any frame, ever, and counting that as a
     * coverage gap would fire ADR 0041's `TERRAIN_COVERAGE_INCOMPLETE` on every such frame.
     */
    @Test
    fun theWorldEdgeIsADifferentFillFromACoverageGap() {
        val tiles = neighbourhood()
        tiles.remove(DemTileCoordinate(z = 2, x = 0, y = 0))

        val padded = requireNotNull(padDemTexture(DemTileCoordinate(z = 2, x = 1, y = 0), tiles))

        assertEquals(
            mapOf(
                DemNeighbour.NORTH_WEST to DemNeighbourFill.WORLD_EDGE,
                DemNeighbour.NORTH to DemNeighbourFill.WORLD_EDGE,
                DemNeighbour.NORTH_EAST to DemNeighbourFill.WORLD_EDGE,
                DemNeighbour.WEST to DemNeighbourFill.ABSENT,
            ),
            padded.filledNeighbours,
        )
    }

    /**
     * `-1 % 4` is `-1` in Kotlin, so a plain remainder either finds no western neighbour at all or
     * finds one three tiles away. The world is a cylinder and the tile at `x = 3` really is the
     * western neighbour of the tile at `x = 0`.
     */
    @Test
    fun aWesternNeighbourAcrossTheAntimeridianIsFoundByWrappingX() {
        val edge = DemTileCoordinate(z = 2, x = 0, y = 1)
        val tiles = mapOf(
            edge to syntheticTile(CENTRE_ID),
            DemTileCoordinate(z = 2, x = 3, y = 1) to syntheticTile(WEST_ID),
        )

        val padded = requireNotNull(padDemTexture(edge, tiles))

        assertTrue(
            DemNeighbour.WEST !in padded.filledNeighbours,
            "the western neighbour exists across the antimeridian and was found",
        )
        for (y in 0 until FIXTURE_SIZE) {
            assertEquals(
                Triple(WEST_ID, FIXTURE_SIZE - 1, y),
                texelAt(padded.image, 0, y + 1),
                "the tile at x = 3 is the western neighbour of the tile at x = 0",
            )
        }
    }

    /**
     * The staleness this key exists to prevent: the centre's bytes are identical in both frames, so a
     * key derived from them alone would hit a resident texture whose ring is a replication and draw a
     * crack in a frame that had everything it needed to close it.
     */
    @Test
    fun theContentKeyDistinguishesAFilledRingFromAnAcquiredOne() {
        val complete = neighbourhood()
        val missingEast = neighbourhood().also { it.remove(DemTileCoordinate(z = 2, x = 2, y = 1)) }

        val withNeighbour = requireNotNull(padDemTexture(centre, complete)).contentKey
        val withFill = requireNotNull(padDemTexture(centre, missingEast)).contentKey

        assertNotEquals(withNeighbour, withFill, "the centre's own bytes are identical in both")
        assertEquals(
            withNeighbour,
            requireNotNull(padDemTexture(centre, neighbourhood())).contentKey,
            "and identical inputs must key identically or nothing is ever reused",
        )
        assertTrue(
            withFill.contains("EAST=fill:ABSENT"),
            "a fill is named in the key rather than merely absent from it: $withFill",
        )
    }

    /**
     * No tile coordinate is in the key, deliberately: two centres with identical bytes and identical
     * neighbour bytes assemble byte-identical textures and share one GPU texture. Asserted rather than
     * left to the KDoc, because "content-addressed" is exactly the kind of claim that quietly stops
     * being true when a coordinate is added for debugging.
     */
    @Test
    fun theContentKeyIsAddressedByContentRatherThanByCoordinate() {
        val here = DemTileCoordinate(z = 2, x = 1, y = 1)
        val elsewhere = DemTileCoordinate(z = 2, x = 3, y = 2)
        val sameBytes = { origin: DemTileCoordinate ->
            mapOf(
                origin to syntheticTile(CENTRE_ID, digest = "d5"),
                DemTileCoordinate(z = 2, x = origin.x, y = origin.y - 1) to syntheticTile(NORTH_ID, digest = "d2"),
            )
        }

        assertEquals(
            requireNotNull(padDemTexture(here, sameBytes(here))).contentKey,
            requireNotNull(padDemTexture(elsewhere, sameBytes(elsewhere))).contentKey,
        )
    }

    @Test
    fun aCentreWithNoDemHasNoPaddedTextureRatherThanAThrow() {
        assertNull(
            padDemTexture(centre, mapOf(DemTileCoordinate(z = 2, x = 0, y = 0) to syntheticTile(1))),
            "a tile RenG has no DEM for draws flat (ADR 0041); it does not fail the frame",
        )
    }

    /**
     * The guard is load-bearing rather than defensive decoration: a zero-edge centre reaches
     * `copyCorner` with `interior - 1 == -1` and would index outside its own array, and ADR 0041 says
     * terrain degrades instead of taking the frame with it. Unreachable through [decodeDemTexels] --
     * `decodePng` admits only positive dimensions, so no decoded image can equal a non-positive
     * `tileSizePx` -- and reachable by any caller assembling a [DemTexels] itself.
     */
    @Test
    fun aDegenerateCentreImageHasNoPaddedTextureRatherThanAnIndexOutOfBounds() {
        val here = DemTileCoordinate(z = 2, x = 1, y = 1)

        assertNull(padDemTexture(here, mapOf(here to DemTexels(DecodedImage(0, 0, ByteArray(0)), "d0"))))
        assertNull(
            padDemTexture(here, mapOf(here to DemTexels(DecodedImage(4, 2, ByteArray(4 * 2 * 4)), "d1"))),
            "a DEM tile is square; a rectangular one has no interior this arithmetic describes",
        )
    }

    @Test
    fun aZoomBeyondTheShiftBoundHasNoPaddedTexture() {
        val absurd = DemTileCoordinate(z = 31, x = 0, y = 0)
        assertNull(padDemTexture(absurd, mapOf(absurd to syntheticTile(1))))
    }

    @Test
    fun demTexelsDecodeToCanonicalRgbaWithTheEnginesDigestCarriedThrough() {
        val decoded = decodeDemTexels(westDemPng, FIXTURE_SIZE, ceiling, contentDigest = "digest-west")

        val texels = assertIs<DemTexelDecodeResult.Success>(decoded).texels
        assertEquals("digest-west", texels.contentDigest)
        assertEquals(FIXTURE_SIZE, texels.image.width)
        // Row 0, column 3 of the western fixture: RGB(1, 3, 0) -> -10000 + (65536 + 768) * 0.1.
        assertEquals(-3369.6, metresAt(texels.image, 3, 0), 1e-9)
    }

    @Test
    fun demTexelsRefuseTheThreeThingsRentileNeverChecked() {
        assertEquals(
            DemTexelDecodeResult.Rejected(DemReject.UNDECODABLE),
            decodeDemTexels(byteArrayOf(1, 2, 3, 4), FIXTURE_SIZE, ceiling, "d"),
            "Rentile validates a DEM with a multi-format decoder; RenG owns exactly one, and it is PNG",
        )
        assertEquals(
            DemTexelDecodeResult.Rejected(DemReject.DIMENSIONS),
            decodeDemTexels(westDemPng, tileSizePx = 8, maximumDecodedBytes = ceiling, contentDigest = "d"),
            "Rentile never compares the image against the tileSizePx its own descriptor declares",
        )
        assertEquals(
            DemTexelDecodeResult.Rejected(DemReject.NON_OPAQUE),
            decodeDemTexels(translucentDemPng, tileSizePx = 2, maximumDecodedBytes = ceiling, contentDigest = "d"),
            "a translucent DEM texel is not a height RenG can state",
        )
    }

    private fun decodedTexels(png: ByteArray, digest: String): DemTexels =
        assertIs<DemTexelDecodeResult.Success>(decodeDemTexels(png, FIXTURE_SIZE, ceiling, digest)).texels

    /** Every texel of tile [id] is `RGB(id, x, y)`, opaque -- see this class's KDoc. */
    private fun syntheticTile(id: Int, digest: String = "digest-$id"): DemTexels {
        val bytes = ByteArray(FIXTURE_SIZE * FIXTURE_SIZE * 4)
        for (y in 0 until FIXTURE_SIZE) {
            for (x in 0 until FIXTURE_SIZE) {
                val at = (y * FIXTURE_SIZE + x) * 4
                bytes[at] = id.toByte()
                bytes[at + 1] = x.toByte()
                bytes[at + 2] = y.toByte()
                bytes[at + 3] = 255.toByte()
            }
        }
        return DemTexels(DecodedImage(FIXTURE_SIZE, FIXTURE_SIZE, bytes), digest)
    }

    private fun texelAt(image: DecodedImage, x: Int, y: Int): Triple<Int, Int, Int> {
        val rgba = image.rgbaSnapshot()
        val at = (y * image.width + x) * 4
        return Triple(rgba[at].toInt() and 0xFF, rgba[at + 1].toInt() and 0xFF, rgba[at + 2].toInt() and 0xFF)
    }

    private fun metresAt(image: DecodedImage, x: Int, y: Int): Double {
        val (red, green, blue) = texelAt(image, x, y)
        return demElevationMetres(red, green, blue, DemEncoding.MAPBOX)
    }

    /**
     * What a `GL_NEAREST` tap at tile-space [tileU] reads out of [padded], on row [tileRow] of the
     * tile's own grid.
     *
     * GL resolves a nearest sample as `i = floor(s)` over the *unnormalized* coordinate `s`. The
     * padding places the tile's own texel `i` at padded index `i + 1`, so `s = 1 + u * N` exactly --
     * written that way rather than as `((1 + u * N) / (N + 2)) * (N + 2)` so that this measures the
     * ring rather than a double rounding trip.
     */
    private fun nearestSampledMetres(padded: PaddedDemTexture, tileU: Double, tileRow: Int): Double {
        val coordinate = 1.0 + tileU * padded.interiorSizePx
        val texel = floor(coordinate).toInt().coerceIn(0, padded.image.width - 1)
        return metresAt(padded.image, texel, tileRow + 1)
    }
}

private const val FIXTURE_SIZE: Int = 4
private const val NORTH_WEST_ID: Int = 1
private const val NORTH_ID: Int = 2
private const val NORTH_EAST_ID: Int = 3
private const val WEST_ID: Int = 4
private const val CENTRE_ID: Int = 5
private const val EAST_ID: Int = 6
private const val SOUTH_WEST_ID: Int = 7
private const val SOUTH_ID: Int = 8
private const val SOUTH_EAST_ID: Int = 9

// Real PNGs, generated once by CPython's zlib/struct modules and pasted as base64, following the
// anti-circularity convention DemElevationTest.kt and PngDecoderTest.kt document: none of RenG's own
// encoder-less pipeline produced these bytes, and every expected elevation was computed by hand from
// the triples the generator was given. Regenerate with:
//
// python3 - <<'PY'
// import zlib, struct, base64
// SIG = b"\x89PNG\r\n\x1a\n"
// def chunk(kind, payload):
//     crc = struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
//     return struct.pack(">I", len(payload)) + kind + payload + crc
// def build(w, h, colour, rows):
//     raw = b"".join(b"\x00" + bytes(row) for row in rows)
//     return (SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, colour, 0, 0, 0))
//             + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
// def tile(tid, n=4): return [[c for x in range(n) for c in (tid, x, y)] for y in range(n)]
// print(base64.b64encode(build(4, 4, 2, tile(1))).decode())
// print(base64.b64encode(build(4, 4, 2, tile(2))).decode())
// print(base64.b64encode(build(2, 2, 6, [[0,0,0,255, 1,2,3,128],[4,5,6,255, 7,8,9,255]])).decode())
// PY

// 4x4 truecolour, every texel RGB(1, x, y): Mapbox heights -3446.4 m at (0, 0) rising to -3369.3 m.
private val westDemPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAJklEQVR42hXJwREAAAyCMJH9d671lZOQQGhwmreCY/pscTX+WdEDBWwAQSrge5QAAAAASUVORK5CYII=",
)

// 4x4 truecolour, every texel RGB(2, x, y): Mapbox heights 3107.2 m at (0, 0) rising to 3184.3 m --
// a little over 6.4 km above its western neighbour at every shared row.
private val eastDemPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAJUlEQVR42hXIwREAAAyCMMT9d67llQPBoNiJmGjsHo+vIzV9tgcHHABRfcQJqAAAAABJRU5ErkJggg==",
)

// 2x2 truecolour+alpha with texel (1, 0) at alpha 128 and the rest opaque.
private val translucentDemPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGklEQVR42mNgYGD4z8jE3MDAwsr2n52D8z8AGfEDq0ClLgIAAAAASUVORK5CYII=",
)
