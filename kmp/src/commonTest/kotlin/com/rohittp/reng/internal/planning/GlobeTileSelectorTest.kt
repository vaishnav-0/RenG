package com.rohittp.reng.internal.planning

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.GlobeGroundFootprint
import com.rohittp.reng.internal.projection.GlobeGroundHalfSpace
import com.rohittp.reng.internal.projection.GlobeRayResult
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.clippedPhysicalPixelFootprint
import com.rohittp.reng.internal.projection.globeGroundFootprint
import com.rohittp.reng.internal.projection.physicalPixelGlobeRay
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Basemap tile selection on a globe: the counts, the coverage, and the two topologies the Mercator
 * selector cannot express.
 *
 * **Latitude 82 is where every count fixture sits, and the equator is where none of them do.** The
 * latitude-matched convention that makes these numbers what they are is exactly the identity at
 * `cos(latitude) = 1`, so an equatorial fixture passes with G1 deleted, with the sphere sized from
 * the wrong exponent, and with the LOD selected from the wrong zoom.
 */
class GlobeTileSelectorTest {
    /**
     * The measurement Cycle G was designed around, taken on the production path rather than on a
     * model of it.
     *
     * The flat column is RenG's own Mercator selector run on the same camera, not a literal — a
     * quoted number would go stale and would not notice if the Mercator path moved. The naive globe
     * is the same production selector fed a sphere built from `512 * 2^zoom` instead of
     * `512 * 2^z_eff`, which is reached without a second camera implementation: [resolveGlobeCamera]
     * derives `z_eff` from the camera's own latitude, so asking it for
     * `zoom + log2(cos latitude)` asks it for exactly the naive sphere at the same orientation,
     * distance and viewport. Only the radius differs, which is the whole claim.
     */
    @Test
    fun theLatitudeMatchedGlobeSelectsTheFlatMapsTileCountAndTheNaiveSphereExplodes() {
        for (fixture in MEASURED_FIXTURES) {
            val flat = mercatorCanonicalTileCount(fixture)
            val lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod
            val matched = globeTileCount(fixture, fixture.zoom, lod)
            val naive = globeTileCount(fixture, fixture.naiveZoom, lod)

            assertEquals(fixture.expectedFlatTiles, flat, "${fixture.name}: the flat count moved")
            assertEquals(flat, matched, "${fixture.name}: the convention must return the flat count")
            assertTrue(
                naive > 25 * matched,
                "${fixture.name}: naive $naive must dwarf the matched $matched",
            )
        }
    }

    /**
     * The 686-tile case is the one that stops being slow and starts being broken. It is past
     * `maximumBasemapTileInstances`' default, so the naive sphere does not render a heavy frame —
     * it refuses the frame at planning, before a single tile is acquired.
     */
    @Test
    fun theNaivePhoneSphereIsPastTheDefaultTileBudgetAndFailsTheFrameClosed() {
        val fixture = MEASURED_FIXTURES.single { it.name == "phone lat 82 zoom 8" }
        val lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod

        assertTrue(
            globeTileCount(fixture, fixture.naiveZoom, lod) > DEFAULT_TILE_BUDGET,
            "the naive count must exceed $DEFAULT_TILE_BUDGET",
        )
        val refused = selectGlobeTiles(
            footprint = globeGroundFootprint(fixture.resolve(fixture.naiveZoom)),
            lod = lod,
            maximumInstances = DEFAULT_TILE_BUDGET,
        ) as TileSelectionOutcome.OverBudget
        assertEquals(DEFAULT_TILE_BUDGET, refused.limit)
        assertTrue(refused.actual > DEFAULT_TILE_BUDGET.toLong())

        val accepted = selectGlobeTiles(
            footprint = globeGroundFootprint(fixture.resolve(fixture.zoom)),
            lod = lod,
            maximumInstances = DEFAULT_TILE_BUDGET,
        ) as TileSelectionOutcome.Success
        assertEquals(fixture.expectedFlatTiles, accepted.instances.size)
    }

    /**
     * The scale-matched column of `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.2, whose
     * globe half was a Python model of a renderer that did not exist. It does now, and the
     * production path lands on every entry — including the table's own calibration line, a pitched
     * flat camera at 32 instances against 4 unpitched, which the globe reproduces exactly.
     *
     * The flat column is measured rather than quoted, for the same reason as above.
     */
    @Test
    fun theWholeMeasuredTableIsReproducedByTheProductionPath() {
        for (row in MEASURED_TABLE) {
            val fixture = GlobeTileFixture(row.name, row.size, row.latitude, row.zoom, 0, row.pitch)
            val lod = observeMercatorLod(row.zoom, previousSelectedLod = null).selectedLod
            assertEquals(row.lod, lod, "${row.name}: LOD")
            assertEquals(row.flatTiles, mercatorCanonicalTileCount(fixture), "${row.name}: flat count")
            assertEquals(row.globeTiles, globeTileCount(fixture, row.zoom, lod), "${row.name}: globe count")
        }
    }

    /**
     * Coverage, against [physicalPixelGlobeRay] — a quadratic solved in the camera's own frame,
     * sharing no arithmetic with the half-space footprint or with the quadtree descent. Every pixel
     * centre that lands on the ground must land in a tile that was selected; a selector that drops
     * a tile leaves a visible hole, and this is what would see it.
     */
    @Test
    fun everyGroundPixelLandsInASelectedTile() {
        for (fixture in COVERAGE_FIXTURES) {
            val camera = fixture.resolve(fixture.zoom)
            val lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod
            val selection = selectGlobeTiles(globeGroundFootprint(camera), lod, 4096)
                as TileSelectionOutcome.Success
            val selected = selection.instances.map { it.tileY to it.canonicalX }.toHashSet()
            val tileCount = 1L shl lod
            var covered = 0

            for (pixelY in 0 until camera.outputPixelSize.height step 5) {
                for (pixelX in 0 until camera.outputPixelSize.width step 5) {
                    val ray = physicalPixelGlobeRay(camera, pixelX, pixelY)
                    if (ray !is GlobeRayResult.Hit) continue
                    if (ray.point.y < 0.0 || ray.point.y > 1.0) continue
                    val tileY = floor(ray.point.y * tileCount).toLong().coerceIn(0L, tileCount - 1L)
                    val unwrapped = floor(ray.point.x * tileCount).toLong()
                    val canonicalX = unwrapped - floorDivision(unwrapped, tileCount) * tileCount
                    covered += 1
                    assertTrue(
                        (tileY.toInt() to canonicalX.toInt()) in selected,
                        "${fixture.name}: pixel ($pixelX, $pixelY) is ground in tile " +
                            "($canonicalX, $tileY) at LOD $lod, which was not selected",
                    )
                }
            }
            assertTrue(covered > 200, "${fixture.name} covered only $covered pixels")
        }
    }

    /**
     * Tightness, against an exhaustive scan of every cell at the selected LOD. The descent visits a
     * few dozen cells and the scan visits every one of `4^lod`; they must agree exactly, which is
     * what says the quadtree pruning loses nothing and adds nothing.
     */
    @Test
    fun theQuadtreeDescentAgreesWithAnExhaustiveScanOfEveryCell() {
        for (fixture in EXHAUSTIVE_FIXTURES) {
            val camera = fixture.resolve(fixture.zoom)
            val footprint = globeGroundFootprint(camera)
            val lod = fixture.scanLod
            val tileCount = 1 shl lod
            val descended = (selectGlobeTiles(footprint, lod, 1 shl 20) as TileSelectionOutcome.Success)
                .instances
                .map { it.tileY to it.canonicalX }
                .toHashSet()

            val scanned = HashSet<Pair<Int, Int>>()
            for (tileY in 0 until tileCount) {
                for (tileX in 0 until tileCount) {
                    val admitted = footprint.admitsMercatorCell(
                        minimumX = tileX.toDouble() / tileCount,
                        maximumX = (tileX + 1).toDouble() / tileCount,
                        minimumY = tileY.toDouble() / tileCount,
                        maximumY = (tileY + 1).toDouble() / tileCount,
                    )
                    if (admitted) scanned += tileY to tileX
                }
            }

            assertEquals(scanned, descended, "${fixture.name}: descent and exhaustive scan disagree")
            assertTrue(scanned.isNotEmpty(), "${fixture.name} selected nothing, so it compared nothing")
            assertTrue(
                scanned.size < tileCount * tileCount,
                "${fixture.name} selected every cell, so the comparison is vacuous",
            )
        }
    }

    /**
     * A sphere has no world copies, so the instance list and the canonical list are the same list.
     * Emitting a second copy of a tile would draw one patch of sphere twice, and keying acquisitions
     * on an unwrapped index would acquire it twice.
     */
    @Test
    fun everyGlobeInstanceIsItsOwnCanonicalTile() {
        val fixture = MEASURED_FIXTURES.single { it.name == "phone lat 82 zoom 8" }
        val selection = selectGlobeTiles(globeGroundFootprint(fixture.resolve(fixture.zoom)), 8, 512)
            as TileSelectionOutcome.Success

        assertEquals(24, selection.instances.size)
        assertEquals(selection.instances.size, selection.canonicalResources.size)
        for (instance in selection.instances) {
            assertEquals(0, instance.instanceCopy, "a globe has no world copies")
            assertEquals(instance.canonicalX.toLong(), instance.unwrappedX)
            assertEquals(8, instance.lod)
            assertTrue(instance.canonicalX in 0 until 256 && instance.tileY in 0 until 256)
        }
        assertEquals(
            selection.instances.map { CanonicalBasemapTile(it.lod, it.tileY, it.canonicalX) },
            selection.canonicalResources,
        )
        assertEquals(
            selection.instances.sortedWith(compareBy({ it.tileY }, { it.unwrappedX })),
            selection.instances,
            "instances must be emitted in a deterministic order",
        )
    }

    /**
     * The antimeridian, which on the plane is a world-copy boundary and on the sphere is nothing at
     * all. A camera looking at longitude 180 selects tiles at both ends of the tile grid and every
     * one of them is canonical; the same camera one whole turn of longitude away selects the
     * identical set, because [unitSphereDirection] wraps its Mercator `x`.
     */
    @Test
    fun theSelectionWrapsTheAntimeridianAndIsInvariantUnderAWholeTurnOfLongitude() {
        val atSeam = globeSelection(HARNESS_SIZE, latitude = 71.0, zoom = 6.0, longitude = 180.0, lod = 6)
        val columns = atSeam.map { it.canonicalX }.toHashSet()

        assertTrue(0 in columns, "the seam must select the first column")
        assertTrue(63 in columns, "and the last one")
        assertTrue(atSeam.all { it.instanceCopy == 0 }, "neither of them is a world copy")

        for (longitude in listOf(-180.0, 540.0, -540.0)) {
            assertEquals(
                atSeam,
                globeSelection(HARNESS_SIZE, 71.0, 6.0, longitude, 6),
                "longitude $longitude must see exactly what longitude 180 sees",
            )
        }
        val eastern = globeSelection(HARNESS_SIZE, latitude = 71.0, zoom = 6.0, longitude = 43.0, lod = 6)
        assertEquals(
            eastern,
            globeSelection(HARNESS_SIZE, 71.0, 6.0, 43.0 + 720.0, 6),
            "two whole turns must change nothing",
        )
        assertTrue(
            eastern.map { it.canonicalX }.toHashSet() != columns,
            "and a different longitude must select different columns, or the invariance is vacuous",
        )
    }

    /**
     * A camera whose visible cap reaches over the pole, which is the shape the Mercator selector
     * cannot hold at all: its footprint is one x-interval per row, and here the northernmost row's
     * interval is **every longitude** while a row four levels south is two tiles wide. No
     * quadrilateral in Mercator space has that outline.
     *
     * The LOD is the one [observeMercatorLod] itself selects for this zoom, so the fixture is a
     * frame RenG can actually be asked to draw rather than an arbitrary pairing.
     */
    @Test
    fun aCameraOverThePoleSelectsEveryColumnOfItsNorthernmostRowAndTwoOfItsSouthernmost() {
        val zoom = 3.6
        val lod = observeMercatorLod(zoom, previousSelectedLod = null).selectedLod
        val tileCount = 1 shl lod
        val selection = globeSelection(PHONE_SIZE, latitude = 78.0, zoom = zoom, longitude = 0.0, lod = lod)
        val columnsByRow = selection.groupBy({ it.tileY }, { it.canonicalX })

        assertEquals(4, lod)
        val northernmost = columnsByRow.keys.min()
        val southernmost = columnsByRow.keys.max()
        assertEquals(
            (0 until tileCount).toSet(),
            columnsByRow.getValue(northernmost).toSet(),
            "the row nearest the pole must span every longitude",
        )
        assertTrue(southernmost - northernmost >= 3, "the fixture must span several rows")
        assertTrue(
            columnsByRow.getValue(southernmost).size * 4 <= tileCount,
            "a southern row must be far narrower, or the case does not discriminate",
        )
        assertTrue(selection.all { it.instanceCopy == 0 }, "a wrapped row is still not a world copy")
    }

    /** Over budget refuses the frame; it never silently drops a tile. */
    @Test
    fun overBudgetReportsTheExactCountAndSelectsNothing() {
        val fixture = MEASURED_FIXTURES.single { it.name == "phone lat 82 zoom 8" }
        val footprint = globeGroundFootprint(fixture.resolve(fixture.zoom))

        assertEquals(
            24,
            (selectGlobeTiles(footprint, 8, 24) as TileSelectionOutcome.Success).instances.size,
            "a budget of exactly the count is not over budget",
        )
        val refused = selectGlobeTiles(footprint, 8, 23) as TileSelectionOutcome.OverBudget
        assertEquals(23, refused.limit)
        assertEquals(24L, refused.actual, "the reported count is the exact one, not a candidate count")
    }

    /**
     * A footprint that admits no direction at all selects nothing rather than throwing or selecting
     * the whole world. No resolvable camera produces one — the guard is against a future one.
     */
    @Test
    fun anEmptyFootprintSelectsNoTiles() {
        val impossible = GlobeGroundFootprint(
            listOf(GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, 1.0), 2.0)),
        )
        val selection = selectGlobeTiles(impossible, 6, 512) as TileSelectionOutcome.Success

        assertTrue(selection.instances.isEmpty())
        assertTrue(selection.canonicalResources.isEmpty())
    }

    @Test
    fun theLodAndTheBudgetAreValidatedBeforeAnyDescent() {
        val footprint = globeGroundFootprint(
            MEASURED_FIXTURES.first().resolve(MEASURED_FIXTURES.first().zoom),
        )

        assertFailsWith<IllegalArgumentException> { selectGlobeTiles(footprint, -1, 512) }
        assertFailsWith<IllegalArgumentException> { selectGlobeTiles(footprint, 23, 512) }
        assertFailsWith<IllegalArgumentException> { selectGlobeTiles(footprint, 6, 0) }
        assertFailsWith<IllegalArgumentException> { selectGlobeTiles(footprint, 6, -1) }
        // LOD 22 and LOD 0 are both accepted arguments. At LOD 22 over a zoom-6 camera the answer
        // is legitimately past any budget, so the outcome that matters is that it is an outcome.
        assertTrue(selectGlobeTiles(footprint, 22, 512) is TileSelectionOutcome.OverBudget)
        assertEquals(
            1,
            (selectGlobeTiles(footprint, 0, 512) as TileSelectionOutcome.Success).instances.size,
        )
        // ...and at the radius LOD 22 is meant for -- about 2.4 x 10^9 logical pixels -- the same
        // descent still resolves the four tiles a 960x540 frame shows.
        val deepest = GlobeTileFixture("harness lat 82 zoom 22", HARNESS_SIZE, 82.0, 22.0, 4)
        assertEquals(
            4,
            (
                selectGlobeTiles(globeGroundFootprint(deepest.resolve(22.0)), 22, 512)
                    as TileSelectionOutcome.Success
                ).instances.size,
        )
    }

    private fun globeSelection(
        size: OutputPixelSize,
        latitude: Double,
        zoom: Double,
        longitude: Double,
        lod: Int,
    ): List<BasemapTileInstance> {
        val camera = (
            resolveGlobeCamera(
                Camera(latitude, longitude, zoom, 0.0, 0.0),
                size,
            ) as SpatialOutcome.Success
            ).value
        return (selectGlobeTiles(globeGroundFootprint(camera), lod, 4096) as TileSelectionOutcome.Success)
            .instances
    }

    private fun globeTileCount(fixture: GlobeTileFixture, zoom: Double, lod: Int): Int =
        (
            selectGlobeTiles(
                footprint = globeGroundFootprint(fixture.resolve(zoom)),
                lod = lod,
                maximumInstances = MEASUREMENT_BUDGET,
            ) as TileSelectionOutcome.Success
            ).instances.size

    private fun mercatorCanonicalTileCount(fixture: GlobeTileFixture): Int {
        val camera = fixture.resolveFlat()
        val selection = selectBasemapTiles(
            footprint = clippedPhysicalPixelFootprint(camera),
            lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod,
            maximumInstances = MEASUREMENT_BUDGET,
        ) as TileSelectionOutcome.Success
        assertEquals(
            selection.canonicalResources.size,
            selection.instances.size,
            "${fixture.name} straddles a Mercator world copy, so the comparison is not like for like",
        )
        return selection.instances.size
    }

    private fun floorDivision(value: Long, positiveDivisor: Long): Long {
        val quotient = value / positiveDivisor
        return if (value % positiveDivisor < 0L) quotient - 1L else quotient
    }

    private companion object {
        val HARNESS_SIZE = OutputPixelSize(960, 540)
        val PHONE_SIZE = OutputPixelSize(1179, 2556)
        const val DEFAULT_TILE_BUDGET = 512
        const val MEASUREMENT_BUDGET = 4096

        val MEASURED_FIXTURES = listOf(
            GlobeTileFixture("harness lat 82 zoom 6", HARNESS_SIZE, 82.0, 6.0, expectedFlatTiles = 4),
            GlobeTileFixture("phone lat 82 zoom 8", PHONE_SIZE, 82.0, 8.0, expectedFlatTiles = 24),
        )

        val COVERAGE_FIXTURES = listOf(
            GlobeTileFixture("harness lat 82 zoom 6", HARNESS_SIZE, 82.0, 6.0, 4),
            GlobeTileFixture("harness lat -47 zoom 3 pitched", HARNESS_SIZE, -47.0, 3.0, 0, pitch = 51.0),
            GlobeTileFixture("phone lat 45 zoom 4", PHONE_SIZE, 45.0, 4.0, 0),
            GlobeTileFixture("harness lat 71 zoom 1, whole limb in frame", HARNESS_SIZE, 71.0, 1.0, 0),
        )

        val EXHAUSTIVE_FIXTURES = listOf(
            ExhaustiveFixture("harness lat 82 zoom 6", HARNESS_SIZE, 82.0, 6.0, scanLod = 6),
            ExhaustiveFixture("harness lat -47 zoom 3 pitched", HARNESS_SIZE, -47.0, 3.0, 5, pitch = 51.0),
            ExhaustiveFixture("polar cap, latitude 84.6", HARNESS_SIZE, 84.6, 2.0, 5),
            ExhaustiveFixture("across the antimeridian", HARNESS_SIZE, 71.0, 6.0, 6, longitude = 179.7),
        )

        /**
         * `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.2, scale-matched column, plus its
         * own pitched-Mercator calibration line.
         */
        val MEASURED_TABLE = listOf(
            MeasuredRow("960x540 lat 0 zoom 2", HARNESS_SIZE, 0.0, 2.0, 2, 4, 8),
            MeasuredRow("960x540 lat 45 zoom 3", HARNESS_SIZE, 45.0, 3.0, 3, 4, 8),
            MeasuredRow("960x540 lat 45 zoom 12", HARNESS_SIZE, 45.0, 12.0, 12, 4, 4),
            MeasuredRow("960x540 lat 60 zoom 4", HARNESS_SIZE, 60.0, 4.0, 4, 4, 6),
            MeasuredRow("960x540 lat 75 zoom 5", HARNESS_SIZE, 75.0, 5.0, 5, 4, 6),
            MeasuredRow("960x540 lat 82 zoom 6", HARNESS_SIZE, 82.0, 6.0, 6, 4, 4),
            MeasuredRow("960x540 lat 82 zoom 12", HARNESS_SIZE, 82.0, 12.0, 12, 4, 4),
            MeasuredRow("1179x2556 lat 0 zoom 3", PHONE_SIZE, 0.0, 3.0, 3, 24, 28),
            MeasuredRow("1179x2556 lat 0 zoom 4", PHONE_SIZE, 0.0, 4.0, 4, 24, 52),
            MeasuredRow("1179x2556 lat 45 zoom 4", PHONE_SIZE, 45.0, 4.0, 4, 24, 96),
            MeasuredRow("1179x2556 lat 60 zoom 6", PHONE_SIZE, 60.0, 6.0, 6, 24, 24),
            MeasuredRow("1179x2556 lat 82 zoom 8", PHONE_SIZE, 82.0, 8.0, 8, 24, 24),
            MeasuredRow("1179x2556 lat 82 zoom 12", PHONE_SIZE, 82.0, 12.0, 12, 24, 24),
            MeasuredRow("960x540 lat 45 zoom 12 pitch 60", HARNESS_SIZE, 45.0, 12.0, 12, 32, 32, pitch = 60.0),
        )
    }
}

private class GlobeTileFixture(
    val name: String,
    val size: OutputPixelSize,
    val latitude: Double,
    val zoom: Double,
    val expectedFlatTiles: Int,
    val pitch: Double = 0.0,
) {
    /**
     * `z_eff = zoom - log2(cos latitude)`, so a camera declared at `zoom + log2(cos latitude)`
     * resolves to a sphere of exactly `512 * 2^zoom` — the naive one — with every other property of
     * the camera unchanged.
     */
    val naiveZoom: Double get() = zoom + log2(cos(latitude * PI / 180.0))

    fun resolve(atZoom: Double): ResolvedGlobeCamera {
        val outcome = resolveGlobeCamera(Camera(latitude, 0.0, atZoom, 0.0, pitch), size)
        return (outcome as SpatialOutcome.Success).value
    }

    fun resolveFlat(): ResolvedMercatorCamera {
        val outcome = resolveMercatorCamera(Camera(latitude, 0.0, zoom, 0.0, pitch), size)
        return (outcome as SpatialOutcome.Success).value
    }
}

private class ExhaustiveFixture(
    val name: String,
    val size: OutputPixelSize,
    val latitude: Double,
    val zoom: Double,
    val scanLod: Int,
    val pitch: Double = 0.0,
    val longitude: Double = 0.0,
) {
    fun resolve(atZoom: Double): ResolvedGlobeCamera {
        val outcome = resolveGlobeCamera(Camera(latitude, longitude, atZoom, 0.0, pitch), size)
        return (outcome as SpatialOutcome.Success).value
    }
}

private class MeasuredRow(
    val name: String,
    val size: OutputPixelSize,
    val latitude: Double,
    val zoom: Double,
    val lod: Int,
    val flatTiles: Int,
    val globeTiles: Int,
    val pitch: Double = 0.0,
)
