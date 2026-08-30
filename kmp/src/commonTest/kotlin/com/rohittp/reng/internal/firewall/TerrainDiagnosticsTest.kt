package com.rohittp.reng.internal.firewall

import com.rohittp.reng.Diagnostic
import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.DemNeighbourFill
import com.rohittp.reng.internal.terrain.DemTexels
import com.rohittp.reng.internal.terrain.DemTileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0041's two diagnostics: one per frame, never one per tile, and never a failed frame.
 *
 * **Every multiplicity case here loses more than one tile.** A frame missing exactly one tile cannot
 * tell "once per frame" from "once per tile" -- both emit once -- so the coverage cases below lose
 * forty, which is also the shape the ADR names when it says a frame missing forty tiles must produce
 * one diagnostic rather than forty.
 *
 * **The fixtures are hand-built outcomes rather than acquisitions.** [TerrainAcquisitionOutcome] is
 * ordinary data and [TerrainAcquisitionTest] already drives the engine seam that produces it; what is
 * under test here is what RenG *says* about one, which is arithmetic over a tile list and a set.
 *
 * **The count is over the tiles that will actually displace, not over the tiles the engine
 * returned**, which is why every case below states the two separately. A frame whose DEMs all arrive
 * and are all unusable is the shape Task 13's harness pass measured -- it printed `diagnostics: none`
 * -- and [anAcquiredTileThatCannotBeUsedIsStillACoverageGap] is that frame.
 */
class TerrainDiagnosticsTest {

    // ---- coverage: some tiles drew flat ----------------------------------------------------------

    @Test
    fun aFrameMissingFortyTilesEmitsOneDiagnosticNamingForty() {
        val ground = groundTiles(count = 60)
        val sink = RecordingSink()

        reportTerrainDegradation(acquiredWith(ground, acquired = ground.take(20)), ground, ground.take(20).toSet(), sink)

        val emitted = sink.diagnostics.single()
        assertEquals(DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE, emitted.code)
        assertEquals(40L, emitted.actual, "the count of tiles that drew flat, not the number of reports")
        assertEquals(0L, emitted.limit, "no tile is expected to draw flat when a style declares terrain")
    }

    @Test
    fun theCoverageReportIsTheWholeShapeAndNotJustTheCode() {
        val ground = groundTiles(count = 3)
        val sink = RecordingSink()

        reportTerrainDegradation(acquiredWith(ground, acquired = ground.take(1)), ground, ground.take(1).toSet(), sink)

        val emitted = sink.diagnostics.single()
        assertEquals(DiagnosticSeverity.WARNING, emitted.severity)
        assertEquals(PipelineStage.BASEMAP_RENDER, emitted.stage)
        assertNull(emitted.fieldName)
        assertNull(emitted.resourceClass)
        assertNull(emitted.resourceKey)
        assertNull(emitted.statusCode)
        assertEquals(
            Diagnostic(
                code = DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE,
                severity = DiagnosticSeverity.WARNING,
                stage = PipelineStage.BASEMAP_RENDER,
                limit = 0L,
                actual = 2L,
            ),
            emitted,
        )
    }

    @Test
    fun aFrameWithCompleteCoverageSaysNothingAtAll() {
        val ground = groundTiles(count = 40)
        val sink = RecordingSink()

        reportTerrainDegradation(acquiredWith(ground, acquired = ground), ground, ground.toSet(), sink)

        assertTrue(sink.diagnostics.isEmpty(), "complete coverage is the ordinary frame and is silent")
    }

    @Test
    fun aStyleThatDeclaresNoTerrainSaysNothingAtAll() {
        val sink = RecordingSink()

        reportTerrainDegradation(TerrainAcquisitionOutcome.NotDeclared, groundTiles(count = 40), emptySet(), sink)

        assertTrue(sink.diagnostics.isEmpty(), "flat ground is exactly what a terrain-free style asked for")
    }

    /**
     * The perimeter ring is acquired for borders and drawn by nothing, so a ring tile Rentile dropped
     * is a replicated border texel rather than a flat tile.
     *
     * This is the case that separates the count from
     * [TerrainAcquisitionOutcome.Acquired.absentTiles], which is every *requested* tile -- ring
     * included -- and would report eight losses for a frame whose one visible tile displaced
     * perfectly.
     */
    @Test
    fun anAbsentRingTileIsNotACoverageGap() {
        val visible = listOf(CanonicalBasemapTile(lod = 4, tileY = 8, canonicalX = 8))
        val requested = terrainTileRequest(visible)
        val sink = RecordingSink()
        val outcome = acquiredWith(requested, acquired = visible)

        assertEquals(9, requested.size, "one visible tile plus its eight neighbours")
        assertEquals(8, outcome.absentTiles.size, "every ring tile is genuinely absent from the request")
        reportTerrainDegradation(outcome, visible, visible.toSet(), sink)

        assertTrue(sink.diagnostics.isEmpty(), "a replicated border is a seam artifact, not a flat tile")
    }

    /**
     * ADR 0041 and [DemNeighbourFill.WORLD_EDGE]: the world ends at +/-85.0511 degrees, no tile exists
     * beyond it, and a frame whose ground reaches the top or bottom row must not report a permanent,
     * structural absence as a coverage gap every frame.
     */
    @Test
    fun aTileOutsideTheWorldIsAWorldEdgeRatherThanACoverageGap() {
        val real = CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 0)
        val northOfTheWorld = CanonicalBasemapTile(lod = 2, tileY = -1, canonicalX = 0)
        val southOfTheWorld = CanonicalBasemapTile(lod = 2, tileY = 4, canonicalX = 0)
        val ground = listOf(real, northOfTheWorld, southOfTheWorld)
        val elevated = emptySet<CanonicalBasemapTile>()

        assertEquals(DemNeighbourFill.ABSENT, terrainCoverageFillOf(real, elevated))
        assertEquals(DemNeighbourFill.WORLD_EDGE, terrainCoverageFillOf(northOfTheWorld, elevated))
        assertEquals(DemNeighbourFill.WORLD_EDGE, terrainCoverageFillOf(southOfTheWorld, elevated))
        assertEquals(
            1,
            terrainCoverageGapCount(ground, elevated),
            "only the tile that exists and is missing is a gap",
        )
    }

    /**
     * The third answer, and the one that stops the two above from passing vacuously: a tile that
     * has its DEM is no kind of fill at all. Without this case a classifier that answered
     * `ABSENT` for every tile would still satisfy the coverage-gap assertion.
     */
    @Test
    fun aTileWithItsOwnDemIsNotAFillOfEitherKind() {
        val tile = CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 1)

        assertNull(terrainCoverageFillOf(tile, setOf(tile)))
    }

    /**
     * One canonical tile shown in three world copies is one tile. A Mercator frame at low zoom spans
     * several copies of the world and draws the same canonical tile in each, and a consumer counting
     * losses is counting tiles rather than draws.
     */
    @Test
    fun worldCopiesOfOneMissingTileCountAsOneTile() {
        val tile = CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5)
        val copies = listOf(
            CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5),
            CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5),
            CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5),
        )

        assertEquals(1, terrainCoverageGapCount(copies, emptySet()))
        assertEquals(0, terrainCoverageGapCount(copies, setOf(tile)), "and none of them when it displaces")
    }

    // ---- the whole ground drew flat --------------------------------------------------------------

    @Test
    fun anAcquisitionFailureIsReportedAsUnavailableRatherThanAsACount() {
        val sink = RecordingSink()

        reportTerrainDegradation(degraded(TerrainDegradationReason.ACQUISITION_FAILED), groundTiles(40), emptySet(), sink)

        val emitted = sink.diagnostics.single()
        assertEquals(DiagnosticCode.TERRAIN_UNAVAILABLE, emitted.code)
        assertEquals(DiagnosticSeverity.WARNING, emitted.severity)
        assertEquals(PipelineStage.BASEMAP_RENDER, emitted.stage)
        assertNull(emitted.limit, "the whole ground is flat; a count would only restate the code")
        assertNull(emitted.actual)
        assertNull(emitted.fieldName)
        assertNull(emitted.resourceClass)
        assertNull(emitted.resourceKey)
        assertNull(emitted.statusCode)
    }

    @Test
    fun aSourceDisagreementIsTheSameReportAsAThrow() {
        val sink = RecordingSink()

        reportTerrainDegradation(degraded(TerrainDegradationReason.SOURCE_DISAGREEMENT), groundTiles(4), emptySet(), sink)

        assertEquals(DiagnosticCode.TERRAIN_UNAVAILABLE, sink.diagnostics.single().code)
    }

    /**
     * ADR 0041's whole point, and the one thing this file exists to hold: a degraded frame is a frame.
     * The carried [com.rohittp.reng.RenGException] is evidence to be reported, not a failure to be
     * rethrown -- rethrowing it would lose the frame, which is precisely the behaviour terrain
     * diverges from.
     */
    @Test
    fun degradationReportsAndDoesNotFailTheFrame() {
        val sink = RecordingSink()
        val outcome = TerrainAcquisitionOutcome.Degraded(
            reason = TerrainDegradationReason.ACQUISITION_FAILED,
            failure = renGFailure(RenGErrorCode.BASEMAP_RENDER_FAILED, PipelineStage.BASEMAP_RENDER),
        )

        reportTerrainDegradation(outcome, groundTiles(4), emptySet(), sink)

        assertEquals(1, sink.diagnostics.size, "the failure is reported rather than rethrown")
        assertEquals(DiagnosticCode.TERRAIN_UNAVAILABLE, sink.diagnostics.single().code)
    }

    /**
     * The negative that stops the two codes being interchangeable: an acquisition that *succeeded* and
     * merely lost tiles reports incomplete coverage, and never says terrain was unavailable.
     */
    @Test
    fun aSuccessfulAcquisitionNeverReportsTerrainUnavailable() {
        val ground = groundTiles(count = 40)
        val sink = RecordingSink()

        reportTerrainDegradation(acquiredWith(ground, acquired = emptyList()), ground, emptySet(), sink)

        val emitted = sink.diagnostics.single()
        assertEquals(DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE, emitted.code)
        assertEquals(40L, emitted.actual)
    }

    /**
     * **The defect Task 13's harness pass caught, as an assertion.** Style 57 fetched every DEM tile
     * its frame asked for, could use none of them, drew flat ground, and printed `diagnostics: none`
     * -- because the report was handed the *acquisition*, which had succeeded, while the tiles were
     * discarded later and silently.
     *
     * Here the acquisition returns a DEM for every one of the forty ground tiles and the frame can
     * displace none of them. The count must be forty, and it must be the same one diagnostic: a
     * report driven by `demTileFor` would emit nothing at all.
     */
    @Test
    fun anAcquiredTileThatCannotBeUsedIsStillACoverageGap() {
        val ground = groundTiles(count = 40)
        val outcome = acquiredWith(ground, acquired = ground)
        val sink = RecordingSink()

        assertEquals(40, outcome.acquiredTileCount, "every tile really did arrive, or this case is the old one")
        assertTrue(outcome.absentTiles.isEmpty(), "nothing is absent from the acquisition's own point of view")
        reportTerrainDegradation(outcome, ground, elevatedTiles = emptySet(), sink = sink)

        val emitted = sink.diagnostics.single()
        assertEquals(DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE, emitted.code)
        assertEquals(40L, emitted.actual, "a DEM RenG cannot use is a flat tile, whatever the engine returned")
    }

    /**
     * The half of the same frame that stops the case above from passing for a report that fires
     * whenever anything is unusable: twenty of the forty displace, so the count is the twenty that
     * did not rather than the whole ground or a bare code.
     */
    @Test
    fun aFrameThatUsesHalfOfWhatItAcquiredCountsOnlyTheHalfItLost() {
        val ground = groundTiles(count = 40)
        val sink = RecordingSink()

        reportTerrainDegradation(
            acquiredWith(ground, acquired = ground),
            ground,
            elevatedTiles = ground.take(20).toSet(),
            sink = sink,
        )

        assertEquals(20L, sink.diagnostics.single().actual)
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun groundTiles(count: Int): List<CanonicalBasemapTile> =
        (0 until count).map { CanonicalBasemapTile(lod = 8, tileY = 100, canonicalX = 100 + it) }

    private fun acquiredWith(
        requested: List<CanonicalBasemapTile>,
        acquired: List<CanonicalBasemapTile>,
    ): TerrainAcquisitionOutcome.Acquired = TerrainAcquisitionOutcome.Acquired(
        source = TerrainSource(
            styleSourceId = "terrain",
            encoding = DemEncoding.MAPBOX,
            tileSizePx = 512,
            minimumZoom = 0,
            maximumZoom = 14,
        ),
        requestedTiles = requested,
        demTiles = acquired.associateWith(::demTileFor),
    )

    private fun demTileFor(tile: CanonicalBasemapTile): AcquiredDemTile {
        val coordinate = DemTileCoordinate(z = tile.lod, x = tile.canonicalX, y = tile.tileY)
        return AcquiredDemTile(
            requestedTile = coordinate,
            sourceTile = coordinate,
            encoding = DemEncoding.MAPBOX,
            texels = DemTexels(DecodedImage(1, 1, ByteArray(4)), contentDigest = "digest"),
        )
    }

    private fun degraded(reason: TerrainDegradationReason): TerrainAcquisitionOutcome.Degraded =
        TerrainAcquisitionOutcome.Degraded(reason = reason, failure = null)

    private class RecordingSink : DiagnosticSink {
        private val recorded: MutableList<Diagnostic> = mutableListOf()

        val diagnostics: List<Diagnostic> get() = ArrayList(recorded)

        override fun emit(diagnostic: Diagnostic) {
            recorded += diagnostic
        }
    }
}
