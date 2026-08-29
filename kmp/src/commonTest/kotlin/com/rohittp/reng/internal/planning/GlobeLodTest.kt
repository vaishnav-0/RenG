package com.rohittp.reng.internal.planning

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.internal.projection.MERCATOR_MAXIMUM_LATITUDE_DEGREES
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.clippedPhysicalPixelFootprint
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * G1, the latitude-matched convention, and the tile-count measurements that decided it.
 *
 * **Every fixture here is at a latitude where `cos(latitude)` is not 1.** At the equator `z_eff` is
 * `zoom`, the globe's world size is Mercator's, and every assertion in this file passes with the
 * convention deleted outright. Latitude 82 is the measured case from
 * `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.2 and the one that carries a 7.19x factor.
 */
class GlobeLodTest {
    @Test
    fun theGlobeIsScaledUpByTheSecantOfTheCameraLatitudeAndTheEquatorHidesIt() {
        // 2^(z_eff - zoom) is the scale factor the sphere is grown by, and G1 fixes it at 1/cos.
        for (latitude in listOf(17.3, -41.7, 60.0, -75.0, 82.0)) {
            val scale = 2.0.pow(latitudeMatchedGlobeZoom(6.0, latitude) - 6.0)
            assertEquals(1.0 / cos(latitude * PI / 180.0), scale, 1e-12, "latitude $latitude")
            assertTrue(scale > 1.0, "the globe is scaled up, never down, at latitude $latitude")
        }

        // The exact measured case: cos 82 = 0.139173, so the sphere is 7.185x Mercator's world size
        // and z_eff is 2.8455 levels above the camera's own zoom.
        assertEquals(8.845_047_7, latitudeMatchedGlobeZoom(6.0, 82.0), 1e-6)
        assertEquals(7.185_296_5, 2.0.pow(latitudeMatchedGlobeZoom(6.0, 82.0) - 6.0), 1e-6)

        // ...and at the equator it vanishes, which is why no fixture here sits there.
        assertEquals(6.0, latitudeMatchedGlobeZoom(6.0, 0.0))
        assertEquals(
            basemapTileSideLogicalPixels(ProjectionMode.MERCATOR, 6.0, 0.0, 6),
            basemapTileSideLogicalPixels(ProjectionMode.GLOBE, 6.0, 0.0, 6),
        )
    }

    @Test
    fun latitudeMatchedZoomIsSymmetricInLatitudeAndMonotonicAwayFromTheEquator() {
        assertEquals(latitudeMatchedGlobeZoom(11.5, 63.4), latitudeMatchedGlobeZoom(11.5, -63.4))

        var previous = latitudeMatchedGlobeZoom(11.5, 0.0)
        var latitude = 3.0
        while (latitude <= 84.0) {
            val current = latitudeMatchedGlobeZoom(11.5, latitude)
            assertTrue(current > previous, "z_eff must grow with |latitude|, broke at $latitude")
            previous = current
            latitude += 3.0
        }
    }

    /**
     * `z_eff` is a scale exponent, not a LOD. Clamping it into `[0, 22]` looks defensive and would
     * shrink the globe by 11.6x at the pole-most supported latitude; the LOD range bounds the LOD,
     * which is selected from the camera's own zoom and never from this number.
     */
    @Test
    fun latitudeMatchedZoomLeavesTheLodRangeRatherThanSaturatingAtIt() {
        val extreme = latitudeMatchedGlobeZoom(22.0, MERCATOR_MAXIMUM_LATITUDE_DEGREES)

        assertEquals(25.535_051_8, extreme, 1e-6)
        assertTrue(extreme > 22.0, "z_eff must be free to leave the LOD range")
        assertEquals(11.591_953_3, 2.0.pow(extreme - 22.0), 1e-6)

        // And the trap that makes the freedom matter: this number is not a zoom the LOD rule reads.
        assertFailsWith<IllegalArgumentException> { observeMercatorLod(extreme, null) }
    }

    @Test
    fun latitudeMatchedZoomRefusesLatitudesMercatorPlanningWouldHaveRefusedFirst() {
        assertFailsWith<IllegalArgumentException> {
            latitudeMatchedGlobeZoom(6.0, MERCATOR_MAXIMUM_LATITUDE_DEGREES + 1e-9)
        }
        assertFailsWith<IllegalArgumentException> { latitudeMatchedGlobeZoom(6.0, 90.0) }
        assertFailsWith<IllegalArgumentException> { latitudeMatchedGlobeZoom(6.0, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { latitudeMatchedGlobeZoom(Double.NaN, 82.0) }
        assertEquals(
            22.0,
            latitudeMatchedGlobeZoom(22.0, 0.0),
            "the supported band's own endpoints are accepted",
        )
    }

    /**
     * The Mercator arm is not a second opinion: it is the same number
     * [resolveBasemapTileQuad] puts on the ground, taken from a real resolved camera at a latitude
     * where the globe arm is 7.19x away from the naive reading.
     */
    @Test
    fun theMercatorTileSideIsTheOneTheTileQuadActuallyUses() {
        val camera = resolvedCamera(HARNESS_SIZE, latitude = 82.0, zoom = 6.0)
        val selection = selectBasemapTiles(
            footprint = clippedPhysicalPixelFootprint(camera),
            lod = 6,
            maximumInstances = 512,
        ) as TileSelectionOutcome.Success

        for (instance in selection.instances) {
            assertEquals(
                resolveBasemapTileQuad(instance, camera).sideLogicalPixels,
                basemapTileSideLogicalPixels(ProjectionMode.MERCATOR, 6.0, 82.0, instance.lod),
                1e-9,
            )
        }
        assertTrue(selection.instances.isNotEmpty(), "the fixture must actually select tiles")
    }

    /**
     * G1's whole content, stated as an equality rather than as prose: under the latitude-matched
     * scale a tile at the camera's own latitude covers exactly as many output pixels on the sphere
     * as it does on the plane, so `screenPixelsPerTexel = 2^(zoom - lod)` survives and
     * [observeMercatorLod] needs no globe arm.
     */
    @Test
    fun theGlobeTileSideEqualsMercatorsAtEveryLatitudeUnderTheConvention() {
        for (latitude in listOf(17.3, -41.7, 60.0, -75.0, 82.0, MERCATOR_MAXIMUM_LATITUDE_DEGREES)) {
            for (zoom in listOf(2.4, 6.0, 8.0, 13.7, 22.0)) {
                for (lod in listOf(0, 3, 6, 9, 22)) {
                    val mercator = basemapTileSideLogicalPixels(ProjectionMode.MERCATOR, zoom, latitude, lod)
                    val globe = basemapTileSideLogicalPixels(ProjectionMode.GLOBE, zoom, latitude, lod)
                    assertEquals(mercator, globe, mercator * 1e-12, "latitude $latitude zoom $zoom lod $lod")
                }
            }
        }
    }

    /**
     * And the same equality read as the cost of getting it wrong: a globe built at `512 * 2^zoom`
     * instead of `512 * 2^z_eff` draws a tile `cos(latitude)` of the size it should, so `1/cos^2`
     * times as many are needed to cover one screen. At latitude 82 that factor is 51.6.
     */
    @Test
    fun theNaiveGlobeScaleShrinksATileByTheCosineOfLatitude() {
        val latitude = 82.0
        val matched = basemapTileSideLogicalPixels(ProjectionMode.GLOBE, 6.0, latitude, 6)
        val naive = BASEMAP_TILE_TEXELS * 2.0.pow(6.0 - 6) * cos(latitude * PI / 180.0)

        assertEquals(BASEMAP_TILE_TEXELS, matched, 1e-9)
        assertEquals(71.256_628, naive, 1e-6)
        assertEquals(cos(latitude * PI / 180.0), naive / matched, 1e-12)
        assertEquals(51.628_486, (matched / naive).pow(2), 1e-6)
    }

    /**
     * The LOD rule with no history, at the measured camera. `ceil(zoom - 0.5)` selects 6, and the
     * tile it selects is 512 output pixels across in **both** modes — the flat count is therefore
     * the globe count. Selecting at `z_eff` instead would take LOD 9 and a 64-pixel tile.
     */
    @Test
    fun theHistorylessRuleSelectsTheSameLodInBothModesAndTheTrapSelectsThree() {
        val observed = observeMercatorLod(6.0, previousSelectedLod = null)

        assertEquals(LodObservation(6), observed)
        assertEquals(
            BASEMAP_TILE_TEXELS,
            basemapTileSideLogicalPixels(ProjectionMode.GLOBE, 6.0, 82.0, observed.selectedLod),
            1e-9,
        )

        val trapped = observeMercatorLod(latitudeMatchedGlobeZoom(6.0, 82.0), previousSelectedLod = null)
        assertEquals(LodObservation(9), trapped)
        assertEquals(
            64.0,
            basemapTileSideLogicalPixels(ProjectionMode.GLOBE, 6.0, 82.0, trapped.selectedLod),
            0.5,
        )
    }

    /**
     * The remembered path is a different branch of [observeMercatorLod] from the historyless one and
     * has to be exercised separately. Held at LOD 7 by hysteresis, a zoom of 6.4 keeps a
     * 337.8-pixel tile in both modes; the historyless rule would have taken 6, and `z_eff` would
     * have climbed the same hysteresis to 9.
     */
    @Test
    fun theRememberedRuleSelectsTheSameLodInBothModesAndTheTrapClimbsPastIt() {
        val remembered = observeMercatorLod(6.4, previousSelectedLod = 7)
        val historyless = observeMercatorLod(6.4, previousSelectedLod = null)

        assertEquals(LodObservation(7), remembered)
        assertEquals(LodObservation(6), historyless)
        assertEquals(
            basemapTileSideLogicalPixels(ProjectionMode.MERCATOR, 6.4, 82.0, remembered.selectedLod),
            basemapTileSideLogicalPixels(ProjectionMode.GLOBE, 6.4, 82.0, remembered.selectedLod),
            1e-9,
        )
        assertEquals(
            337.794_025,
            basemapTileSideLogicalPixels(ProjectionMode.GLOBE, 6.4, 82.0, remembered.selectedLod),
            1e-3,
        )

        val trapped = observeMercatorLod(latitudeMatchedGlobeZoom(6.4, 82.0), previousSelectedLod = 7)
        assertEquals(LodObservation(9), trapped)
    }

    /**
     * The measured stakes, reproduced. The flat column is RenG's own production path; the globe
     * columns come from [GlobeTileCountModel], a model of a globe RenG does not have yet — cycle G
     * task 5 owns the real one. The model is the Kotlin counterpart of the Python one in
     * `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.2 and lands on that document's own
     * numbers: 686 exactly for the phone case, 136 against its 132 for the harness case, the
     * four-tile difference being how a 13x13 sample grid treats a tile that only clips the viewport.
     */
    @Test
    fun theNaiveGlobeMultipliesTheTileCountAndTheConventionCollapsesItBackToMercators() {
        for (fixture in MEASURED_FIXTURES) {
            val flat = mercatorCanonicalTileCount(fixture)
            assertEquals(fixture.expectedFlatTiles, flat, "flat count at ${fixture.name}")

            val lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod
            val naive = GlobeTileCountModel.visibleTiles(fixture, scaleZoom = fixture.zoom, lod = lod)
            val matched = GlobeTileCountModel.visibleTiles(
                fixture = fixture,
                scaleZoom = latitudeMatchedGlobeZoom(fixture.zoom, fixture.latitude),
                lod = lod,
            )

            assertEquals(flat, matched, "the convention must return ${fixture.name} to its flat count")
            assertTrue(
                naive >= fixture.naiveLowerBound,
                "${fixture.name} naive count was $naive, under ${fixture.naiveLowerBound}",
            )
            assertTrue(
                naive > 25 * matched,
                "${fixture.name} naive count $naive must dwarf the matched $matched",
            )
        }
    }

    /**
     * The 686-tile fixture is the one that stops being slow and starts being broken: it is past
     * `maximumBasemapTileInstances`' default, so the naive reading fails the frame closed at
     * planning rather than rendering it. The convention's 24 sits well inside the same budget.
     */
    @Test
    fun theNaivePhoneCountIsPastTheDefaultTileBudgetAndTheMatchedOneIsNot() {
        val fixture = MEASURED_FIXTURES.single { it.name == "phone lat 82 zoom 8" }
        val lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod
        val naive = GlobeTileCountModel.visibleTiles(fixture, scaleZoom = fixture.zoom, lod = lod)
        val matched = GlobeTileCountModel.visibleTiles(
            fixture = fixture,
            scaleZoom = latitudeMatchedGlobeZoom(fixture.zoom, fixture.latitude),
            lod = lod,
        )

        assertTrue(naive > DEFAULT_TILE_BUDGET, "naive count $naive must exceed $DEFAULT_TILE_BUDGET")
        assertTrue(matched < DEFAULT_TILE_BUDGET / 4, "matched count $matched must sit well inside it")
    }

    /**
     * Selecting the LOD at `z_eff` — the reading the formula invites — is not a milder version of
     * the naive scale. It is worse than it: the globe is the right size and the tiles on it are
     * `cos(latitude)` too small anyway.
     */
    @Test
    fun observingTheLodAtTheScaleExponentIsWorseThanTheNaiveScaleItLooksLike() {
        val fixture = MEASURED_FIXTURES.single { it.name == "harness lat 82 zoom 6" }
        val effective = latitudeMatchedGlobeZoom(fixture.zoom, fixture.latitude)
        val naive = GlobeTileCountModel.visibleTiles(
            fixture = fixture,
            scaleZoom = fixture.zoom,
            lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod,
        )
        val trapped = GlobeTileCountModel.visibleTiles(
            fixture = fixture,
            scaleZoom = effective,
            lod = observeMercatorLod(effective, previousSelectedLod = null).selectedLod,
            tileWindow = 64,
        )

        assertEquals(9, observeMercatorLod(effective, previousSelectedLod = null).selectedLod)
        assertTrue(trapped > naive, "trapped count $trapped must exceed the naive $naive")
        assertTrue(trapped > 140, "trapped count $trapped must be the same order as the naive one")
    }

    private fun resolvedCamera(
        size: OutputPixelSize,
        latitude: Double,
        zoom: Double,
    ): ResolvedMercatorCamera {
        val outcome = resolveMercatorCamera(
            camera = Camera(
                latitude = latitude,
                unwrappedLongitude = 0.0,
                zoom = zoom,
                bearing = 0.0,
                pitch = 0.0,
            ),
            outputPixelSize = size,
        )
        return (outcome as SpatialOutcome.Success).value
    }

    private fun mercatorCanonicalTileCount(fixture: GlobeCameraFixture): Int {
        val camera = resolvedCamera(fixture.size, fixture.latitude, fixture.zoom)
        val selection = selectBasemapTiles(
            footprint = clippedPhysicalPixelFootprint(camera),
            lod = observeMercatorLod(fixture.zoom, previousSelectedLod = null).selectedLod,
            maximumInstances = DEFAULT_TILE_BUDGET,
        ) as TileSelectionOutcome.Success
        assertEquals(
            selection.canonicalResources.size,
            selection.instances.size,
            "${fixture.name} must not straddle a world copy, or the comparison is not like for like",
        )
        return selection.canonicalResources.size
    }

    private companion object {
        val HARNESS_SIZE = OutputPixelSize(960, 540)
        const val DEFAULT_TILE_BUDGET = 512

        val MEASURED_FIXTURES = listOf(
            GlobeCameraFixture(
                name = "harness lat 82 zoom 6",
                size = HARNESS_SIZE,
                latitude = 82.0,
                zoom = 6.0,
                expectedFlatTiles = 4,
                naiveLowerBound = 120,
            ),
            GlobeCameraFixture(
                name = "phone lat 82 zoom 8",
                size = OutputPixelSize(1179, 2556),
                latitude = 82.0,
                zoom = 8.0,
                expectedFlatTiles = 24,
                naiveLowerBound = 600,
            ),
        )
    }
}

private data class GlobeCameraFixture(
    val name: String,
    val size: OutputPixelSize,
    val latitude: Double,
    val zoom: Double,
    val expectedFlatTiles: Int,
    val naiveLowerBound: Int,
)

/**
 * A model of a globe RenG does not have yet, written only to count tiles.
 *
 * It is the Kotlin counterpart of `scratchpad/globe.py` in
 * `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.2 and shares its stated convention: sphere
 * radius `worldSize / 2*PI`, the eye one `cameraDistanceLogicalPixels` above the sub-camera surface
 * point along the outward normal, RenG's own perspective (focal `1 + sqrt(2)`, aspect `w / h`,
 * `CameraMatrices.kt:101-138`), a north-up unpitched camera, and a tile counted visible when any of
 * a 13x13 sample grid over its lat/lon patch is both front-facing and inside NDC.
 *
 * **It is a model of RenG's arithmetic, not RenG's arithmetic**, and nothing outside this file may
 * depend on it. Cycle G task 5 owns the real globe footprint and tile selection.
 */
private object GlobeTileCountModel {
    private val FOCAL_LENGTH_SCALE: Double = 1.0 + sqrt(2.0)
    private const val SAMPLES: Int = 13

    fun visibleTiles(
        fixture: GlobeCameraFixture,
        scaleZoom: Double,
        lod: Int,
        tileWindow: Int = 32,
    ): Int {
        val worldSize = 512.0 * 2.0.pow(scaleZoom)
        val radius = worldSize / (2.0 * PI)
        val eyeDistance = fixture.size.height.toDouble() * FOCAL_LENGTH_SCALE / 2.0
        val aspect = fixture.size.width.toDouble() / fixture.size.height.toDouble()
        val latitudeRadians = fixture.latitude * PI / 180.0
        val normal = doubleArrayOf(cos(latitudeRadians), 0.0, sin(latitudeRadians))
        val east = doubleArrayOf(0.0, 1.0, 0.0)
        val north = doubleArrayOf(-sin(latitudeRadians), 0.0, cos(latitudeRadians))
        val eye = DoubleArray(3) { (radius + eyeDistance) * normal[it] }

        val tileCount = 1 shl lod
        val cameraMercatorY = (1.0 - asinh(tan(latitudeRadians)) / PI) / 2.0
        val centreRow = (cameraMercatorY * tileCount).toInt()
        val firstRow = maxOf(0, centreRow - tileWindow)
        val lastRow = minOf(tileCount - 1, centreRow + tileWindow)
        val firstColumn = maxOf(0, tileCount / 2 - tileWindow)
        val lastColumn = minOf(tileCount - 1, tileCount / 2 + tileWindow)

        var visible = 0
        for (tileY in firstRow..lastRow) {
            for (tileX in firstColumn..lastColumn) {
                if (isVisible(tileX, tileY, tileCount, radius, eye, normal, east, north, aspect)) {
                    visible += 1
                }
            }
        }
        return visible
    }

    private fun isVisible(
        tileX: Int,
        tileY: Int,
        tileCount: Int,
        radius: Double,
        eye: DoubleArray,
        normal: DoubleArray,
        east: DoubleArray,
        north: DoubleArray,
        aspect: Double,
    ): Boolean {
        for (row in 0 until SAMPLES) {
            val mercatorY = (tileY + row.toDouble() / (SAMPLES - 1)) / tileCount
            val latitude = atan(sinh(PI * (1.0 - 2.0 * mercatorY)))
            for (column in 0 until SAMPLES) {
                val longitude =
                    ((tileX + column.toDouble() / (SAMPLES - 1)) / tileCount * 360.0 - 180.0) * PI / 180.0
                val surfaceNormal = doubleArrayOf(
                    cos(latitude) * cos(longitude),
                    cos(latitude) * sin(longitude),
                    sin(latitude),
                )
                val offset = DoubleArray(3) { radius * surfaceNormal[it] - eye[it] }
                if (dot(offset, surfaceNormal) >= 0.0) continue
                val forward = -dot(offset, normal)
                if (forward <= 0.0) continue
                val ndcX = FOCAL_LENGTH_SCALE * (dot(offset, east) / forward) / aspect
                val ndcY = FOCAL_LENGTH_SCALE * (dot(offset, north) / forward)
                if (abs(ndcX) <= 1.0 && abs(ndcY) <= 1.0) return true
            }
        }
        return false
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
