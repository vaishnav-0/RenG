package com.rohittp.reng.internal.terrain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Cycle E-terrain task 16: the CPU elevation lookup, asserted against the surface the ground
 * *draws* rather than against the texel underneath the point.**
 *
 * The plan's original test for this task was "the CPU lookup and the GPU fetch agree on a shared
 * texel". Task 14's spike measured that rule at **3.049 m** of vertical error where it is used, and
 * ADR 0039's 2026-08-30 erratum records it as refuted: a shared texel decides *where content sits*,
 * and the drawn surface inside a ground cell is the interpolation between the cell's four **corner**
 * samples. So every case below is written so that a lookup passing it cannot be reading the texel the
 * point lands on — [theLookupReconstructsTheCellSurfaceRatherThanTheTexelUnderThePoint] is that claim
 * directly, and [theLookupInterpolatesAcrossTheGroundsOwnNorthEastToSouthWestDiagonal] is the second
 * half of it, because interpolating the right four corners across the *wrong* diagonal measured 1.800
 * m in the same spike.
 *
 * ## Every expected number here is arithmetic on this file's own integers
 *
 * A fixture texel is declared as its Mapbox packed integer, and the metres a case expects are
 * `-10000 + n * 0.1` written out here. Nothing on the expected side calls [demElevationMetres],
 * [GroundSurface], or any helper that shares an implementation with them, so an encoding regression
 * can only make these fail.
 *
 * ## Two symmetry points deliberately avoided
 *
 * **Exaggeration is never 1**, which is this cycle's standing trap: at 1 an honoured exaggeration and
 * a dropped one are the same number. **No sample point sits on a cell's centre or on either
 * diagonal**, because the cell centre is exactly where the two triangulations agree and reading a
 * saddle there would pass against both.
 */
class GroundSurfaceTest {

    /**
     * **The task's own sentence.** A ground cell spanning two DEM texels has a *middle* texel that is
     * a corner of nothing, and the drawn surface never takes its value.
     *
     * The fixture is a comb: even texel columns at 400 m, odd ones at 900 m, with a grid of 4 cells
     * over an 8-texel interior so every cell's two corners land on even columns and its interior on an
     * odd one. The sample sits inside cell 0 at a point whose own texel is column 1 — the 900 m one —
     * so a lookup reading the texel underneath answers 2,250 m after exaggeration where the drawn
     * surface answers 1,000 m.
     *
     * The comb is flat in `v`, so this case says nothing about the diagonal and cannot pass or fail
     * for the diagonal's reasons; that is the next case's job.
     */
    @Test
    fun theLookupReconstructsTheCellSurfaceRatherThanTheTexelUnderThePoint() {
        val low = 104_000 // -10000 + 104000 * 0.1 = 400.0 m
        val high = 109_000 // -10000 + 109000 * 0.1 = 900.0 m
        val surface = groundSurface(
            interior = 8,
            exaggeration = 2.5,
            packed = { x, _ -> if (x % 2 == 0) low else high },
        )

        // u = 0.15 is inside cell 0 (which spans [0, 0.25]) and inside texel column 1, whose height is
        // the one the drawn surface never uses.
        val metres = surface.elevationMetresAt(mercatorX = 0.15, mercatorY = 0.05, cellsPerTileSide = 4)

        assertEquals(400.0 * 2.5, metres, "the cell's corners are both 400 m, so its surface is 400 m")
        assertNotEquals(900.0 * 2.5, metres, "reading the texel under the point would answer 900 m")
    }

    /**
     * **The diagonal, and it is not a detail.** `groundGridIndices` folds a cell on `SW`-`NE`; the
     * geometry grid folded the other way until this cycle, and the spike measured a CPU lookup
     * interpolating the ground's own four corners across the geometry's diagonal at 1.800 m of error.
     *
     * The cell is a saddle with four distinct corner heights, so the two triangulations disagree
     * everywhere except on the shared edge. Both sample points sit strictly inside one triangle and
     * strictly off both diagonals, and their expected values are worked out here from the four corner
     * metres:
     *
     * - `(s, t) = (0.25, 0.5)` is in the north-west triangle `(NW, SW, NE)`:
     *   `100 + 0.25 * (900 - 100) + 0.5 * (500 - 100) = 500`. Across the other diagonal the same point
     *   falls in `(NW, SW, SE)` and reads `100 + 0.5 * 400 + 0.25 * (300 - 500) = 250`.
     * - `(s, t) = (0.9, 0.6)` is in the south-east triangle `(NE, SW, SE)`:
     *   `300 + 0.1 * (500 - 300) + 0.4 * (900 - 300) = 560`. Across the other diagonal it falls in
     *   `(NW, SE, NE)` and reads `100 + 0.9 * 800 + 0.6 * (300 - 900) = 460`.
     *
     * Two points rather than one, one in each triangle, because a lookup that used the north-west
     * triangle's formula everywhere would still be right about the first.
     */
    @Test
    fun theLookupInterpolatesAcrossTheGroundsOwnNorthEastToSouthWestDiagonal() {
        // Cell (0, 0) of a 4-cell grid over an 8-texel interior has its corners at texels (0,0),
        // (2,0), (0,2) and (2,2). Every other texel is a distinctive 4,321 m so that a lookup reading
        // one lands nowhere near an expected value.
        val corners = mapOf(
            (0 to 0) to 101_000, // 100 m, north-west
            (2 to 0) to 109_000, // 900 m, north-east
            (0 to 2) to 105_000, // 500 m, south-west
            (2 to 2) to 103_000, // 300 m, south-east
        )
        val surface = groundSurface(
            interior = 8,
            exaggeration = 2.0,
            packed = { x, y -> corners[x to y] ?: 143_210 },
        )

        val northWestTriangle = surface.elevationMetresAt(
            mercatorX = 0.25 * 0.25,
            mercatorY = 0.5 * 0.25,
            cellsPerTileSide = 4,
        )
        assertEquals(500.0 * 2.0, northWestTriangle, "(NW, SW, NE) reads 500 m at (0.25, 0.5)")
        assertNotEquals(250.0 * 2.0, northWestTriangle, "the other diagonal would read 250 m there")

        val southEastTriangle = surface.elevationMetresAt(
            mercatorX = 0.9 * 0.25,
            mercatorY = 0.6 * 0.25,
            cellsPerTileSide = 4,
        )
        assertEquals(560.0 * 2.0, southEastTriangle, "(NE, SW, SE) reads 560 m at (0.9, 0.6)")
        assertNotEquals(460.0 * 2.0, southEastTriangle, "the other diagonal would read 460 m there")
    }

    /**
     * **The tile's far edge reads its neighbour, exactly as the padded texture does.**
     *
     * `GROUND_ELEVATION_SOURCE` snaps a grid coordinate to `floor(source * interior)`, which at the
     * eastern edge is `interior` itself — one past the last interior texel — and the shader's `+ 1.5`
     * lands that on the padded texture's east ring, which [padDemTexture] fills from the eastern
     * neighbour's first column. A lookup that clamped to its own last column instead would put a drape
     * a full texel of relief away from the ground along every tile boundary in the frame, which is the
     * seam the ring exists to close, reopened for one kind of content.
     *
     * The fixture is two tiles at lod 1: the western one flat at 100 m, the eastern one flat at 900 m.
     * The sample is inside the western tile's last cell, so its north-east and south-east corners are
     * the ring. The second half removes the eastern tile and requires the replicated answer, because
     * "reads the neighbour" and "has a neighbour to read" are two different claims and only the first
     * is what the ring rule says.
     */
    @Test
    fun aCornerAtTheTilesFarEdgeReadsThePaddedRingRatherThanItsOwnLastTexel() {
        val west = DemTileCoordinate(z = 1, x = 0, y = 0)
        val east = DemTileCoordinate(z = 1, x = 1, y = 0)
        val texels = mapOf(
            west to texels(interior = 4, packed = { _, _ -> 101_000 }, digest = "west"), // 100 m
            east to texels(interior = 4, packed = { _, _ -> 109_000 }, digest = "east"), // 900 m
        )
        val tiles = mapOf(west to GroundSurfaceTile(west, DemTileWindow(1, 0, 0)))

        // Cell 3 of a 4-cell grid spans u in [0.75, 1]; (s, t) = (0.5, 0.3) puts the sample in the
        // north-west triangle, whose north-east corner is the ring.
        val sampleX = 0.5 * (0.75 + 0.25 * 0.5)
        val sampleY = 0.5 * (0.25 * 0.3)

        val withNeighbour = GroundSurface(
            lod = 1,
            interiorSizePx = 4,
            exaggeration = 3.0,
            encoding = DemEncoding.MAPBOX,
            tiles = tiles,
            texelsBySource = texels,
        ).elevationMetresAt(sampleX, sampleY, cellsPerTileSide = 4)
        // 100 + 0.5 * (900 - 100) + 0.3 * (100 - 100) = 500
        assertEquals(500.0 * 3.0, withNeighbour, "the east corners read the neighbour's first column")

        val alone = GroundSurface(
            lod = 1,
            interiorSizePx = 4,
            exaggeration = 3.0,
            encoding = DemEncoding.MAPBOX,
            tiles = tiles,
            texelsBySource = mapOf(west to texels.getValue(west)),
        ).elevationMetresAt(sampleX, sampleY, cellsPerTileSide = 4)
        assertEquals(100.0 * 3.0, alone, "an absent neighbour replicates this tile's own edge")
    }

    /**
     * **The window is spent, so an overzoomed ground tile reads its own quarter of a coarser DEM.**
     *
     * A ground tile at lod 2 whose DEM came from lod 1 carries `childScale = 2`, and its `u = 0` is
     * the source's `u = 0.5`. The fixture's source is a west-to-east staircase, so ignoring the window
     * and reading the source's own first column would answer the western quarter's height instead.
     */
    @Test
    fun anOverzoomedTileReadsItsOwnQuarterOfTheSourceDem() {
        val source = DemTileCoordinate(z = 1, x = 0, y = 0)
        // 100 m at column 0 rising by 100 m a column: `-10000 + (101000 + x * 1000) * 0.1`.
        val texels = mapOf(source to texels(interior = 8, packed = { x, _ -> 101_000 + x * 1_000 }, digest = "s"))
        val requested = DemTileCoordinate(z = 2, x = 1, y = 0)
        val surface = GroundSurface(
            lod = 2,
            interiorSizePx = 8,
            exaggeration = 4.0,
            encoding = DemEncoding.MAPBOX,
            tiles = mapOf(
                requested to GroundSurfaceTile(
                    source,
                    DemTileWindow(childScale = 2, childX = 1, childY = 0),
                ),
            ),
            texelsBySource = texels,
        )

        // The ground tile occupies mercator x in [0.25, 0.5) at lod 2. A one-cell grid makes the whole
        // tile one cell, whose corners are the source's columns 4 (u = 0.5) and 8 (the east ring,
        // replicated from column 7 because this source has no eastern neighbour here).
        val metres = surface.elevationMetresAt(mercatorX = 0.25, mercatorY = 0.0, cellsPerTileSide = 1)
        assertEquals(500.0 * 4.0, metres, "the tile's western edge is the source's column 4, at 500 m")
        assertNotEquals(100.0 * 4.0, metres, "ignoring the window would read the source's column 0, at 100 m")
    }

    /**
     * **The granularity is an input, and getting it from the wrong place changes the answer.**
     *
     * `SceneContent` reconciles terrain's granularity claim with the globe's curvature claim and draws
     * the ground at the result; a lookup handed terrain's unreconciled claim would find the wrong cell
     * on every globe frame the curvature refines. Two granularities over one staircase DEM give two
     * different heights at one point, which is what makes the parameter observable at all.
     */
    @Test
    fun theGranularityChoosesTheCellAndTwoGranularitiesDisagree() {
        val surface = groundSurface(
            interior = 8,
            exaggeration = 1.5,
            packed = { x, _ -> 100_000 + x * 1_000 }, // 100 m + 100 m a column
        )
        val coarse = surface.elevationMetresAt(mercatorX = 0.3, mercatorY = 0.0, cellsPerTileSide = 1)
        val fine = surface.elevationMetresAt(mercatorX = 0.3, mercatorY = 0.0, cellsPerTileSide = 8)
        assertNotNullAnd(coarse) { assertNotEquals(it, fine, "granularity chooses the containing cell") }
    }

    /** Exaggeration multiplies the reconstructed surface, and is never assumed to be 1. */
    @Test
    fun exaggerationScalesTheReconstructedSurface() {
        val flat = { _: Int, _: Int -> 106_000 } // 600 m
        val plain = groundSurface(interior = 4, exaggeration = 1.0, packed = flat)
            .elevationMetresAt(0.4, 0.4, cellsPerTileSide = 2)
        val exaggerated = groundSurface(interior = 4, exaggeration = 2.5, packed = flat)
            .elevationMetresAt(0.4, 0.4, cellsPerTileSide = 2)
        assertEquals(600.0, plain)
        assertEquals(1_500.0, exaggerated)
    }

    /**
     * **ADR 0040's silent degradation, in the one place that can be silent.** A tile with no DEM, and
     * a position off the world's latitude band, both answer `null` — which the draw spends as "add
     * nothing", so a `GROUND_RELATIVE` altitude means `ABSOLUTE` exactly where terrain is absent.
     */
    @Test
    fun aPositionWithNoDisplacedGroundUnderItAnswersNull() {
        // Two tiles exist at lod 1 and this frame displaced only the western one, which is exactly
        // the coverage gap ADR 0041 draws flat and ADR 0040 then resolves as `ABSOLUTE`.
        val west = DemTileCoordinate(z = 1, x = 0, y = 0)
        val surface = GroundSurface(
            lod = 1,
            interiorSizePx = 4,
            exaggeration = 2.0,
            encoding = DemEncoding.MAPBOX,
            tiles = mapOf(west to GroundSurfaceTile(west, DemTileWindow(1, 0, 0))),
            texelsBySource = mapOf(west to texels(4, { _, _ -> 106_000 }, "west")),
        )
        assertNull(surface.elevationMetresAt(mercatorX = 0.9, mercatorY = 0.1, cellsPerTileSide = 2))
        assertNull(surface.elevationMetresAt(mercatorX = 0.1, mercatorY = 1.5, cellsPerTileSide = 2))
        assertNull(surface.elevationMetresAt(mercatorX = 0.1, mercatorY = -0.1, cellsPerTileSide = 2))
        assertNull(surface.elevationMetresAt(mercatorX = Double.NaN, mercatorY = 0.1, cellsPerTileSide = 2))
        assertEquals(
            1_200.0,
            surface.elevationMetresAt(0.1, 0.1, cellsPerTileSide = 2),
            "the tile this frame did displace still answers, so the nulls are about coverage",
        )
    }

    /** A world copy is a real position: `x = 1.25` at lod 1 is the same ground as `x = 0.25`. */
    @Test
    fun aWorldCopyWrapsOntoTheSameCanonicalTile() {
        val surface = groundSurface(interior = 8, exaggeration = 2.0, packed = { x, _ -> 100_000 + x * 1_000 })
        val home = surface.elevationMetresAt(0.25, 0.1, cellsPerTileSide = 4)
        assertEquals(home, surface.elevationMetresAt(1.25, 0.1, cellsPerTileSide = 4))
        assertEquals(home, surface.elevationMetresAt(-0.75, 0.1, cellsPerTileSide = 4))
    }

    /**
     * The geographic entry point projects and then asks the same question, so the two agree at the
     * one place both can describe: latitude 0 is Mercator `y = 0.5`, and longitude 0 is `x = 0.5`.
     */
    @Test
    fun theGeographicEntryPointProjectsOntoTheMercatorOne() {
        val surface =
            groundSurface(interior = 8, exaggeration = 2.0, packed = { x, y -> 100_000 + (x + y) * 1_000 })
        assertEquals(
            surface.elevationMetresAt(mercatorX = 0.5, mercatorY = 0.5, cellsPerTileSide = 4),
            surface.elevationMetresBeneath(latitude = 0.0, unwrappedLongitude = 0.0, cellsPerTileSide = 4),
        )
    }

    /** `isEmpty` is what the draw reads to decide a frame has no surface at all. */
    @Test
    fun aSurfaceWithNoTilesIsEmpty() {
        assertTrue(
            GroundSurface(
                lod = 0,
                interiorSizePx = 4,
                exaggeration = 2.0,
                encoding = DemEncoding.MAPBOX,
                tiles = emptyMap(),
                texelsBySource = emptyMap(),
            ).isEmpty,
        )
        assertTrue(!groundSurface(interior = 4, exaggeration = 2.0, packed = { _, _ -> 106_000 }).isEmpty)
    }

    // ---- fixture -----------------------------------------------------------------------------------

    /** One lod-0 tile whose DEM is its own source, which is the identity window. */
    private fun groundSurface(
        interior: Int,
        exaggeration: Double,
        packed: (Int, Int) -> Int,
    ): GroundSurface {
        val tile = DemTileCoordinate(z = 0, x = 0, y = 0)
        return GroundSurface(
            lod = 0,
            interiorSizePx = interior,
            exaggeration = exaggeration,
            encoding = DemEncoding.MAPBOX,
            tiles = mapOf(tile to GroundSurfaceTile(tile, DemTileWindow(1, 0, 0))),
            texelsBySource = mapOf(tile to texels(interior, packed, "only")),
        )
    }

    /**
     * A square of RGBA texels carrying [packed] as a big-endian 24-bit Mapbox value, built here rather
     * than through any RenG encoder so that the metres a case expects and the bytes it feeds in are
     * two independent statements of one number.
     */
    private fun texels(interior: Int, packed: (Int, Int) -> Int, digest: String): DemTexels {
        val rgba = ByteArray(interior * interior * 4)
        for (y in 0 until interior) {
            for (x in 0 until interior) {
                val value = packed(x, y)
                val offset = (y * interior + x) * 4
                rgba[offset] = ((value shr 16) and 0xFF).toByte()
                rgba[offset + 1] = ((value shr 8) and 0xFF).toByte()
                rgba[offset + 2] = (value and 0xFF).toByte()
                rgba[offset + 3] = 0xFF.toByte()
            }
        }
        return requireNotNull(
            demTexelsOf(
                width = interior,
                height = interior,
                rgba = rgba,
                tileSizePx = interior,
                contentDigest = digest,
            ),
        )
    }

    private fun assertNotNullAnd(value: Double?, block: (Double) -> Unit) {
        assertTrue(value != null, "the fixture's own tile must answer")
        block(value)
    }
}
