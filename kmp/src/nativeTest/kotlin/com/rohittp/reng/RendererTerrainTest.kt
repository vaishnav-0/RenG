package com.rohittp.reng

import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **ADR 0041's two diagnostics, fired by a real `prepare()` rather than by a hand-built outcome.**
 *
 * `TerrainDiagnosticsTest` already proves what `reportTerrainDegradation` emits for each outcome;
 * until Cycle E-terrain task 8 wired the acquisition into the renderer, **nothing called it**, so
 * every one of those assertions was true of a function no frame could reach. That is the exact defect
 * shape E-labels paid most for — "the label path was fully built and wired to nothing" — and these
 * three cases are what close it: a style, a transport, a camera and a `prepare()`, with the
 * diagnostic read off the consumer's own sink.
 *
 * The engine really is asked for DEM tiles here: the style declares a `raster-dem` source and a
 * `terrain` block, `tileTimeRoutes` preregisters that source's 3x3 neighbourhood, and
 * `TerrainAcquisition` widens the request to the visible set plus its perimeter ring.
 *
 * **`nativeTest` rather than `commonTest`, for `RendererBasemapTileTest`'s reason.** Every case here
 * renders the frame's basemap tiles through the engine, and the Android host JVM has no Skia to
 * render them with -- it looks for a `libskiko-macos-arm64.dylib` that was never going to be an
 * Android one, so a tile-rendering frame there fails with `RESOURCE_DECODE_FAILED` before terrain is
 * ever reached. This runs on macOS and on the iOS simulator, and on Linux in CI.
 */
class RendererTerrainTest {

    /**
     * **Absence.** The DEM source's `minzoom` sits far above the frame's zoom, so Rentile drops every
     * requested tile through `mapNotNull` — no exception, no engine diagnostic, a shorter list — and
     * RenG can only observe it afterwards. One diagnostic, once, carrying the count of *ground* tiles
     * that drew flat rather than the count of requests that vanished: the perimeter ring costs a
     * replicated border texel, not a flat tile, and counting it would inflate every ordinary frame.
     *
     * ADR 0041 names this as an accepted steady state rather than an event: a source whose `minzoom`
     * is above the camera's zoom drops everything, every frame.
     */
    @Test fun aFrameWhoseDemTilesAllVanishReportsIncompleteCoverageOnceAndStillPrepares() = runTest {
        val sink = TerrainDiagnosticCollector()
        val renderer = terrainRenderer(TerrainStyleTransport(minimumDemZoom = 12), sink)
        try {
            renderer.prepare(basemapPlan(frameIndex = 1L)).close()
        } finally {
            renderer.close()
        }

        val coverage = sink.diagnostics().filter { it.code == DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE }
        assertEquals(
            1,
            coverage.size,
            "one frame emits one coverage diagnostic, never one per tile: ${sink.codes()}",
        )
        // This camera selects four tiles (`x in {1, 2}, y in {10, 11}` at LOD 4), and all four drew
        // flat. The request was sixteen -- the visible set plus its perimeter ring -- so a count of
        // sixteen here would mean the ring was being counted as flat ground.
        assertEquals(
            4L,
            coverage.single().actual,
            "the count is the frame's flat *ground* tiles, not its vanished requests",
        )
        assertTrue(
            DiagnosticCode.TERRAIN_UNAVAILABLE !in sink.codes(),
            "a coverage gap is not an acquisition failure: ${sink.codes()}",
        )
    }

    /**
     * The other half of the negative, and the one that stops the coverage case above from passing
     * because terrain never reached the engine at all.
     *
     * Here the DEM source's zoom range covers the frame, the transport answers every DEM url, and the
     * acquisition returns a tile for every request — so the coverage count is zero and **nothing** is
     * emitted. A build whose terrain acquisition silently did nothing would report incomplete coverage
     * here, because every ground tile would still be missing its DEM.
     */
    @Test fun aFrameWhoseDemTilesAllArriveReportsNothing() = runTest {
        val sink = TerrainDiagnosticCollector()
        val renderer = terrainRenderer(TerrainStyleTransport(), sink)
        try {
            renderer.prepare(basemapPlan(frameIndex = 1L)).close()
        } finally {
            renderer.close()
        }

        assertTrue(
            sink.codes().none {
                it == DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE || it == DiagnosticCode.TERRAIN_UNAVAILABLE
            },
            "complete terrain coverage is silence: ${sink.codes()}",
        )
    }

    /**
     * **The defect Task 13's harness pass caught, end to end and through a real `prepare()`.**
     *
     * Every DEM tile is fetched, decoded by the engine and matched to its request; the source
     * declares 256 and the server answers 64, so not one of them is a DEM this frame's sampling
     * arithmetic can use. That is exactly style 57's shape -- fetched 200, unusable, ground drawn
     * flat -- and it printed `diagnostics: none`, because the report was handed the acquisition
     * outcome, which had succeeded.
     *
     * A build that reports coverage from `demTileFor` passes every other case in this file and fails
     * this one, which is the whole reason it is here. The count is the frame's four ground tiles, not
     * its sixteen requests.
     */
    @Test fun aFrameWhoseDemTilesArriveUnusableReportsIncompleteCoverageRatherThanNothing() = runTest {
        val sink = TerrainDiagnosticCollector()
        val renderer = terrainRenderer(TerrainStyleTransport(declaredDemTileSizePx = 256), sink)
        try {
            renderer.prepare(basemapPlan(frameIndex = 1L)).close()
        } finally {
            renderer.close()
        }

        val coverage = sink.diagnostics().filter { it.code == DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE }
        assertEquals(
            1,
            coverage.size,
            "a DEM that arrives and cannot be used is reported, once: ${sink.codes()}",
        )
        assertEquals(4L, coverage.single().actual, "the frame's four ground tiles all drew flat")
        assertTrue(
            DiagnosticCode.TERRAIN_UNAVAILABLE !in sink.codes(),
            "the acquisition succeeded; only the coverage is incomplete: ${sink.codes()}",
        )
    }

    /**
     * **Error.** One failing DEM tile fails the whole `acquireTerrainTiles` call — Rentile's
     * `throwAcquisitionFailures` offers no per-tile degradation and RenG's contract forbids a retry —
     * and the frame must still prepare. That is the whole of ADR 0041: terrain is the one basemap
     * resource whose absence has a picture RenG has already shipped, so it degrades where a failing
     * raster tile would cost the frame.
     *
     * The diagnostic carries no engine message and no cause; the failure it was built from is the
     * sanitized one the firewall already produced.
     */
    @Test fun aFrameWhoseDemAcquisitionFailsReportsTerrainUnavailableAndStillPrepares() = runTest {
        val sink = TerrainDiagnosticCollector()
        val renderer = terrainRenderer(TerrainStyleTransport(failDemTiles = true), sink)
        try {
            // The assertion is that this line does not throw. A raster tile failing here loses the
            // frame -- measured, not assumed: one ArcGIS timeout cost frame 38 of a 48-frame render.
            renderer.prepare(basemapPlan(frameIndex = 1L)).close()
        } finally {
            renderer.close()
        }

        assertEquals(
            1,
            sink.codes().count { it == DiagnosticCode.TERRAIN_UNAVAILABLE },
            "a failed terrain acquisition reports once and does not fail the frame: ${sink.codes()}",
        )
    }

    /**
     * The negative that stops the two above from passing for the wrong reason: a style with no
     * `terrain` block asked for flat ground and got it, so **neither** code appears.
     *
     * Without this a diagnostic emitted unconditionally from `prepare()` would satisfy both cases
     * above, and would then fire on the 28 of the corpus's 34 styles that declare no terrain at all.
     */
    @Test fun aStyleWithNoTerrainBlockReportsNeitherTerrainDiagnostic() = runTest {
        val sink = TerrainDiagnosticCollector()
        val renderer = terrainRenderer(TerrainStyleTransport(declareTerrain = false), sink)
        try {
            renderer.prepare(basemapPlan(frameIndex = 1L)).close()
        } finally {
            renderer.close()
        }

        assertTrue(
            sink.codes().none {
                it == DiagnosticCode.TERRAIN_COVERAGE_INCOMPLETE || it == DiagnosticCode.TERRAIN_UNAVAILABLE
            },
            "flat ground is exactly what a style with no terrain asked for: ${sink.codes()}",
        )
    }

    private fun terrainRenderer(transport: Transport, sink: DiagnosticSink): Renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(64, 64),
            transport = transport,
            store = RecordingStyleStore(),
            basemapStyle = ResourceLocator(STYLE_URL),
            diagnosticSink = sink,
        ),
        styleGlBinding(),
        RenderContextProbe { RenderContextIdentity(1L) },
    )
}

/**
 * [StyleTransport] with a `raster-dem` source, a `terrain` block, and two switches for the two
 * failure shapes ADR 0041 distinguishes.
 *
 * `exaggeration` is **3**, not 1. All six corpus styles declare 1, and at 1 an honoured multiplier
 * and a dropped one are indistinguishable — so a fixture copied from the corpus proves nothing about
 * the field being read at all.
 */
private class TerrainStyleTransport(
    private val minimumDemZoom: Int = 0,
    private val failDemTiles: Boolean = false,
    private val declareTerrain: Boolean = true,
    private val declaredDemTileSizePx: Int = 64,
) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        return when {
            url == STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = styleJson().encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            url.startsWith(TERRAIN_DEM_PREFIX) && failDemTiles -> TransportResponse(
                statusCode = 503,
                body = ByteArray(0),
                metadata = TransportResponseMetadata(contentType = "text/plain"),
            )
            // A real 64 x 64 Mapbox DEM. The basemap tiles keep the 2 x 2 fixture: only the DEM's
            // size has to agree with what the source declares.
            url.startsWith(TERRAIN_DEM_PREFIX) -> TransportResponse(
                statusCode = 200,
                body = DEM_SEA_LEVEL_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = STYLE_TEST_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }

    private fun styleJson(): String {
        val terrain = if (declareTerrain) ""","terrain":{"source":"dem","exaggeration":3}""" else ""
        return """{"version":8,"name":"reng-terrain-test",""" +
            """"sources":{"s":{"type":"raster","tiles":["$STYLE_TILE_TEMPLATE"],"tileSize":256},""" +
            """"dem":{"type":"raster-dem","tiles":["$TERRAIN_DEM_TEMPLATE"],""" +
            """"tileSize":$declaredDemTileSizePx,""" +
            """"minzoom":$minimumDemZoom,"maxzoom":22}},""" +
            """"layers":[{"id":"r","type":"raster","source":"s"}]$terrain}"""
    }
}

private const val TERRAIN_DEM_PREFIX: String = "https://terrain-dem.example/"
private const val TERRAIN_DEM_TEMPLATE: String = TERRAIN_DEM_PREFIX + "{z}/{x}/{y}.png"

private class TerrainDiagnosticCollector : DiagnosticSink {
    private val collected = mutableListOf<Diagnostic>()

    override fun emit(diagnostic: Diagnostic) {
        collected += diagnostic
    }

    fun diagnostics(): List<Diagnostic> = collected.toList()

    fun codes(): List<DiagnosticCode> = collected.map { it.code }
}
