package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ConcurrentRecorder
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.basemap.BasemapStyleManifestOutcome
import com.rohittp.reng.internal.basemap.deriveBasemapStyleManifest
import com.rohittp.reng.internal.basemap.tileTimeRoutes
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.DemTileCoordinate
import com.rohittp.reng.internal.terrain.DemTileWindow
import com.rohittp.reng.internal.terrain.demTileWindowFor
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.TerrainDemEncoding
import com.rohittp.rentile.TerrainSourceDescriptor
import com.rohittp.rentile.TileId
import com.rohittp.rentile.ValidatedDemTile
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * Cycle C's task 20 across ADR 0016's firewall: the terrain descriptor, the ground radiance, the DEM
 * request and its perimeter ring, and ADR 0041's degradation.
 *
 * **Everything here is Skia-free, which is why it lives in `commonTest`.** A DEM tile that genuinely
 * arrives has been through Rentile's `Image.makeFromEncoded`, and this project's `androidHostTest` runtime
 * resolves skiko's API without its native library -- so the cases that need a *successful* acquisition are
 * driven through [TerrainAcquisition.matchDemTiles] with hand-constructed [ValidatedDemTile]s rather than
 * through a rasteriser. That is not a compromise: matching a short result list back onto its request is
 * arithmetic, and a decoder in the way would only hide it.
 */
class TerrainAcquisitionTest {

    // ---- the descriptor, and the digest that hides in it ------------------------------------------

    /**
     * The style declares terrain, and every field of the descriptor is read from the *source* rather than
     * from the `terrain` block -- which is why the fixture sets all four away from Rentile's own defaults
     * (`tileSize` defaults to 512, `minzoom` to 0, `maxzoom` to 30, `encoding` to mapbox). A fixture at
     * the defaults cannot tell a descriptor that was read from one that was invented.
     */
    @Test
    fun exposesTheStylesTerrainDescriptor() = runTest {
        withTerrainHost { host, acquisition ->
            val style = host.terrainStyle(TERRAIN_STYLE_JSON)
            val declared = assertIs<TerrainSourceOutcome.Declared>(
                acquisition.terrainSource(style, terrainManifest(TERRAIN_STYLE_JSON)),
            )
            assertEquals(TERRAIN_SOURCE_ID, declared.source.styleSourceId)
            // RenG's own enum, never Rentile's: the translation is the firewall's job and this is where
            // it either happened or did not.
            assertEquals(DemEncoding.TERRARIUM, declared.source.encoding)
            assertEquals(256, declared.source.tileSizePx)
            assertEquals(2, declared.source.minimumZoom)
            assertEquals(14, declared.source.maximumZoom)
        }
    }

    /**
     * The trap, named on its own so nothing has to rediscover it: Rentile's
     * [TerrainSourceDescriptor.sourceId] is `sha256Hex` of the style's source id, not the source id.
     *
     * The digest is a literal rather than a derivation, so this case cannot agree with a hashing bug by
     * restating it.
     */
    @Test
    fun theEnginesTerrainSourceIdIsADigestRatherThanTheStylesOwn() = runTest {
        withTerrainHost { host, _ ->
            val descriptor = host.terrainSourceDescriptor(host.terrainStyle(TERRAIN_STYLE_JSON))
            assertNotEquals(TERRAIN_SOURCE_ID, descriptor?.sourceId)
            assertEquals(TERRAIN_SOURCE_ID_DIGEST, descriptor?.sourceId)
        }
    }

    /** No `terrain` block: flat ground is what the style asked for, and nothing is degraded. */
    @Test
    fun reportsNoTerrainForAStyleThatDeclaresNone() = runTest {
        withTerrainHost { host, acquisition ->
            val style = host.terrainStyle(LIT_STYLE_JSON)
            assertIs<TerrainSourceOutcome.NotDeclared>(
                acquisition.terrainSource(style, terrainManifest(LIT_STYLE_JSON)),
            )
        }
    }

    /**
     * RenG's own reading of `terrain.source` and the engine's compiled one must name the same source. A
     * frame where they do not degrades rather than displacing from a source RenG never routed.
     */
    @Test
    fun refusesAStyleAndAManifestThatNameDifferentTerrainSources() = runTest {
        withTerrainHost { host, acquisition ->
            val style = host.terrainStyle(TERRAIN_STYLE_JSON)
            assertIs<TerrainSourceOutcome.Disagreed>(
                acquisition.terrainSource(style, terrainManifest(OTHER_TERRAIN_STYLE_JSON)),
            )
        }
    }

    /**
     * The same disagreement in the other direction, which is a different branch and not a symmetry of the
     * case above: the engine compiled no terrain source while RenG's manifest names one. Whichever side is
     * wrong, the ring RenG preregistered is for a source the engine will never ask for.
     */
    @Test
    fun refusesAManifestThatNamesTerrainForAStyleTheEngineCompiledWithout() = runTest {
        withTerrainHost { host, acquisition ->
            val style = host.terrainStyle(LIT_STYLE_JSON)
            assertIs<TerrainSourceOutcome.Disagreed>(
                acquisition.terrainSource(style, terrainManifest(TERRAIN_STYLE_JSON)),
            )
        }
    }

    // ---- ground radiance, which is not about terrain at all ---------------------------------------

    /**
     * Half of the independence claim: a style with `lights` and no `terrain` still has a ground radiance.
     * The other half is [reportsNoGroundRadianceForAStyleWithTerrainAndNoLights], and neither direction is
     * worth anything without the other.
     */
    @Test
    fun exposesGroundRadianceForAStyleWithLightsAndNoTerrain() = runTest {
        withTerrainHost { host, acquisition ->
            val style = host.terrainStyle(LIT_STYLE_JSON)
            val radiance = assertNotNull(acquisition.groundRadiance(style))
            assertIs<TerrainSourceOutcome.NotDeclared>(
                acquisition.terrainSource(style, terrainManifest(LIT_STYLE_JSON)),
            )
            assertTrue(
                radiance.red > 0.0 && radiance.red < 1.0,
                "Rentile's own default lights land near-white rather than white: ${radiance.red}",
            )
        }
    }

    /** The other half: terrain without lights compiles no radiance. */
    @Test
    fun reportsNoGroundRadianceForAStyleWithTerrainAndNoLights() = runTest {
        withTerrainHost { host, acquisition ->
            val style = host.terrainStyle(TERRAIN_STYLE_JSON)
            assertNull(acquisition.groundRadiance(style))
            assertIs<TerrainSourceOutcome.Declared>(
                acquisition.terrainSource(style, terrainManifest(TERRAIN_STYLE_JSON)),
            )
        }
    }

    /**
     * The three components are gamma re-encoded over a *sum*, so full ambient plus full directional light
     * from directly overhead reaches `2^(1/2.2)`, roughly `1.370`. Above one is legal, and RenG does not
     * clamp it on the way past.
     *
     * The bound is arithmetic on `2` rather than a copy of Rentile's formula, and the `> 1.0` assertion is
     * the claim itself: a clamp at one would leave the equality below failing by 0.37, which no tolerance
     * hides.
     */
    @Test
    fun leavesGroundRadianceUnclampedAboveOne() = runTest {
        withTerrainHost { host, acquisition ->
            val radiance = assertNotNull(acquisition.groundRadiance(host.terrainStyle(SATURATED_LIGHT_STYLE_JSON)))
            val bound = 2.0.pow(1.0 / 2.2)
            listOf(radiance.red, radiance.green, radiance.blue).forEach { component ->
                assertTrue(component > 1.0, "a radiance component above one is legal, got $component")
                assertTrue(abs(component - bound) < 1e-9, "and it reaches the encoding's own ceiling $bound")
            }
        }
    }

    // ---- the request: the visible set plus a perimeter ring ---------------------------------------

    /**
     * The ring, asserted against the exact nine tiles one visible tile expands to.
     *
     * Nine rather than five, because a padded DEM border needs its corner texels and only the diagonal
     * neighbour carries them.
     */
    @Test
    fun requestsTheVisibleSetAndItsWholeEightNeighbourhood() {
        val requested = terrainTileRequest(listOf(TERRAIN_TILE))
        val expected = (1..3).flatMap { y ->
            (4..6).map { x -> CanonicalBasemapTile(lod = 3, tileY = y, canonicalX = x) }
        }
        assertEquals(expected.toSet(), requested.toSet())
        assertEquals(9, requested.size, "no tile is asked for twice")
        assertEquals(TERRAIN_TILE, requested.first(), "the visible set leads, so a caller can still find it")
    }

    /**
     * `y` is clipped and `x` wraps, exactly as Rentile's own `RasterSample.neighbor` does it. At zoom 1
     * the tile `(0, 0)` has no northern neighbour at all, and its western and eastern neighbours are the
     * *same* tile -- so the expansion is four tiles, not nine.
     *
     * The clip is load-bearing rather than tidy: Rentile's `validateTile` throws for a `y` outside the
     * world, and one throw fails the whole call.
     */
    @Test
    fun clipsTheRingAtThePoleAndWrapsItAcrossTheAntimeridian() {
        val requested = terrainTileRequest(listOf(CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0)))
        assertEquals(
            setOf(
                CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0),
                CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 1),
                CanonicalBasemapTile(lod = 1, tileY = 1, canonicalX = 0),
                CanonicalBasemapTile(lod = 1, tileY = 1, canonicalX = 1),
            ),
            requested.toSet(),
        )
        assertEquals(4, requested.size)
        assertTrue(requested.none { it.tileY < 0 }, "a neighbour off the top of the world does not exist")
    }

    /** The whole world in one tile has no neighbours, and each lod expands against its own dimension. */
    @Test
    fun expandsEachLodAgainstItsOwnWorldDimension() {
        val wholeWorld = CanonicalBasemapTile(lod = 0, tileY = 0, canonicalX = 0)
        assertEquals(listOf(wholeWorld), terrainTileRequest(listOf(wholeWorld)))

        val mixed = terrainTileRequest(listOf(wholeWorld, CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0)))
        assertEquals(1, mixed.count { it.lod == 0 }, "the zoom 0 tile still has no ring")
        assertEquals(4, mixed.count { it.lod == 1 }, "and the zoom 1 tile keeps its own")
    }

    /**
     * The ring costs no extra preregistration, which is the whole reason it is affordable -- and this case
     * proves that rather than asserting it.
     *
     * Only the **visible** tile is handed to `tileTimeRoutes`, so the routes below are the 3x3
     * neighbourhood it declares for a `raster-dem` source and nothing more. The firewall refuses an
     * unpreregistered url at the store index before the consumer's `Transport` is ever reached, so a
     * consumer that saw all nine urls saw nine routes that already existed. The urls are hand-written
     * literals, so this cannot pass by restating the composition under test.
     */
    @Test
    fun asksForEveryRingTileOverRoutesTheVisibleSetAlreadyPreregistered() = runTest {
        val transport = RecordingTerrainTransport()
        val host = basemapEngineHost(transport = transport, store = CountingHostStore())
        try {
            val manifest = terrainManifest(TERRAIN_STYLE_JSON)
            host.withOperation(
                ResourceAccessMode.NORMAL,
                tileTimeRoutes(manifest, listOf(TERRAIN_TILE), ResourceAccessMode.NORMAL, ResourceLimits()),
            ) {
                val style = host.terrainStyle(TERRAIN_STYLE_JSON)
                TerrainAcquisition(host).acquire(style, manifest, listOf(TERRAIN_TILE))
            }
            val requestedUrls = transport.requestedUrls()
            assertEquals(TERRAIN_RING_URLS.toSet(), requestedUrls.toSet())
            assertEquals(TERRAIN_RING_URLS.size, requestedUrls.size, "and each exactly once")
        } finally {
            host.close()
        }
    }

    /** A frame with no ground tiles asks the engine for nothing, and reports no missing coverage. */
    @Test
    fun asksForNothingWhenTheFrameHasNoVisibleTiles() = runTest {
        val transport = RecordingTerrainTransport()
        val host = basemapEngineHost(transport = transport, store = CountingHostStore())
        try {
            val outcome = host.withOperation(ResourceAccessMode.NORMAL) {
                val style = host.terrainStyle(TERRAIN_STYLE_JSON)
                TerrainAcquisition(host).acquire(style, terrainManifest(TERRAIN_STYLE_JSON), emptyList())
            }
            val acquired = assertIs<TerrainAcquisitionOutcome.Acquired>(outcome)
            assertTrue(acquired.requestedTiles.isEmpty())
            assertTrue(acquired.absentTiles.isEmpty())
            assertTrue(transport.requestedUrls().isEmpty())
        } finally {
            host.close()
        }
    }

    // ---- matching a short, reordered result list onto its request ---------------------------------

    /**
     * **The mechanism ADR 0041 says makes absence observable at all.**
     *
     * Rentile drops a tile below `minimumZoom` or outside the source's `bounds` through `mapNotNull` --
     * no diagnostic, no exception -- and exposes no `bounds` to predict it with, so a short list is
     * ordinary rather than exceptional. The fixture is short *and* out of order, which is what separates
     * "matched by `requestedTile`" from every cheaper rule: matching by position pairs the first requested
     * tile with a result for a different one. The assertions read the payload rather than the key, so a
     * map built by position cannot satisfy them by construction.
     */
    @Test
    fun matchesEachResultToTheTileItWasRequestedForRatherThanToItsPosition() {
        val host = basemapEngineHost()
        try {
            val requested = listOf(
                CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 4),
                CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5),
                CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 6),
                CanonicalBasemapTile(lod = 3, tileY = 3, canonicalX = 5),
            )
            // The engine answered for three of the four, and not in the order they were asked for.
            val results = listOf(
                demTile(requested[3], payload = 30),
                demTile(requested[0], payload = 10),
                demTile(requested[2], payload = 20),
            )

            val matched = TerrainAcquisition(host).matchDemTiles(requested, results)

            assertEquals(3, matched.size)
            assertContentEquals(byteArrayOf(10), matched[requested[0]]?.bytes)
            assertNull(matched[requested[1]], "the tile the engine dropped stays dropped")
            assertContentEquals(byteArrayOf(20), matched[requested[2]]?.bytes)
            assertContentEquals(byteArrayOf(30), matched[requested[3]]?.bytes)
        } finally {
            host.close()
        }
    }

    /**
     * The same short list read the way ADR 0041's coverage diagnostic reads it: the dropped tiles are
     * named, in request order, and everything else is acquired. A consumer acts differently on two missing
     * tiles and on a hundred, so this has to be the tiles that are genuinely missing rather than however
     * many the returned list happened to be short by.
     */
    @Test
    fun namesTheTilesTheEngineDroppedRatherThanCountingTheShortfall() {
        val host = basemapEngineHost()
        try {
            val requested = terrainTileRequest(listOf(TERRAIN_TILE))
            val dropped = setOf(requested[1], requested[4])
            val results = requested.filterNot { it in dropped }.reversed().map { demTile(it, payload = 1) }

            val acquired = TerrainAcquisitionOutcome.Acquired(
                source = TERRAIN_SOURCE,
                requestedTiles = requested,
                demTiles = TerrainAcquisition(host).matchDemTiles(requested, results),
            )

            assertEquals(listOf(requested[1], requested[4]), acquired.absentTiles)
            assertEquals(7, acquired.acquiredTileCount)
            assertNull(acquired.demTileFor(requested[1]))
            assertNotNull(acquired.demTileFor(requested[0]))
        } finally {
            host.close()
        }
    }

    /**
     * The translation seam, asserted at the one shape that can tell every axis apart: an overzoomed,
     * world-wrapped request whose source tile agrees with it in no coordinate at all.
     *
     * The pair is Rentile's own fixture (`RasterResourceTest.kt:19-51`) -- a `z = 4` request at `x = 13`,
     * `y = 10` answered from its `z = 2` ancestor -- so the window derived from the translated pair is
     * checked against a number Rentile itself publishes rather than against RenG's arithmetic restated.
     * Running it *through* `demTileWindowFor` is the point: a transposed or canonicalised translation
     * would still produce a `DemTileCoordinate`, and only the window says whether it produced the right
     * one.
     *
     * The bytes are asserted **identical rather than equal**: Task 5 uploads them as a texture, and a
     * defensive copy here would be a megabyte per tile and a hundred tiles per frame.
     */
    @Test
    fun translatesTheEnginesTileIdsAndEncodingIntoRenGsOwnVocabulary() {
        val host = basemapEngineHost()
        try {
            val requested = CanonicalBasemapTile(lod = 4, tileY = 10, canonicalX = 13)
            val payload = byteArrayOf(7, 8, 9)
            val result = ValidatedDemTile(
                requestedTile = TileId(z = 4, x = 13, y = 10),
                sourceTile = TileId(z = 2, x = 3, y = 2),
                sourceId = TERRAIN_SOURCE_ID_DIGEST,
                encoding = TerrainDemEncoding.MAPBOX,
                bytes = payload,
                contentDigest = TERRAIN_SOURCE_ID_DIGEST,
            )

            val dem = assertNotNull(
                TerrainAcquisition(host).matchDemTiles(listOf(requested), listOf(result))[requested],
            )

            assertEquals(DemTileCoordinate(z = 4, x = 13, y = 10), dem.requestedTile)
            assertEquals(DemTileCoordinate(z = 2, x = 3, y = 2), dem.sourceTile)
            assertEquals(DemEncoding.MAPBOX, dem.encoding)
            assertEquals(
                DemTileWindow(childScale = 4, childX = 1, childY = 2),
                demTileWindowFor(dem.requestedTile, dem.sourceTile),
                "the translated pair still resolves to Rentile's own published window",
            )
            assertSame(payload, dem.bytes, "the encoded bytes cross the firewall untouched")
        } finally {
            host.close()
        }
    }

    // ---- ADR 0041: terrain degrades, it never fails a frame ---------------------------------------

    /**
     * A DEM tile the consumer cannot serve fails Rentile's whole call, and RenG answers with a flat ground
     * rather than with a lost frame.
     *
     * The failure it carries is the firewall's own sanitized one -- a code, a stage, no engine text and no
     * cause -- and that is asserted here, because a degradation that forwarded an adapter's message would
     * breach ADR 0016 while still satisfying every type assertion above it.
     */
    @Test
    fun degradesToFlatGroundWhenTheEngineFailsTheWholeAcquisition() = runTest {
        val transport = RecordingTerrainTransport(statusCode = 404)
        val host = basemapEngineHost(transport = transport, store = CountingHostStore())
        try {
            val manifest = terrainManifest(TERRAIN_STYLE_JSON)
            val outcome = host.withOperation(
                ResourceAccessMode.NORMAL,
                tileTimeRoutes(manifest, listOf(TERRAIN_TILE), ResourceAccessMode.NORMAL, ResourceLimits()),
            ) {
                val style = host.terrainStyle(TERRAIN_STYLE_JSON)
                TerrainAcquisition(host).acquire(style, manifest, listOf(TERRAIN_TILE))
            }

            val degraded = assertIs<TerrainAcquisitionOutcome.Degraded>(outcome)
            assertEquals(TerrainDegradationReason.ACQUISITION_FAILED, degraded.reason)
            assertEquals(RenGErrorCode.RESOURCE_UNAVAILABLE, degraded.failure?.code)
            assertNull(degraded.failure?.cause, "a RenG failure never carries an engine cause")
            assertTrue(
                degraded.failure?.message?.contains("tiles.example") != true,
                "and never an adapter's locator",
            )
        } finally {
            host.close()
        }
    }

    /** A style and a manifest that disagree degrade too, and say which of the two reasons it was. */
    @Test
    fun degradesWithoutTouchingTheEngineWhenTheTwoReadingsDisagree() = runTest {
        val transport = RecordingTerrainTransport()
        val host = basemapEngineHost(transport = transport, store = CountingHostStore())
        try {
            val outcome = host.withOperation(ResourceAccessMode.NORMAL) {
                val style = host.terrainStyle(TERRAIN_STYLE_JSON)
                TerrainAcquisition(host).acquire(
                    style,
                    terrainManifest(OTHER_TERRAIN_STYLE_JSON),
                    listOf(TERRAIN_TILE),
                )
            }
            val degraded = assertIs<TerrainAcquisitionOutcome.Degraded>(outcome)
            assertEquals(TerrainDegradationReason.SOURCE_DISAGREEMENT, degraded.reason)
            assertNull(degraded.failure, "nothing failed: RenG disagreed with itself")
            assertTrue(transport.requestedUrls().isEmpty(), "and no DEM was fetched under the wrong source")
        } finally {
            host.close()
        }
    }

    /**
     * A cancelled frame is not a degraded frame. [TerrainAcquisition.acquire] catches
     * [com.rohittp.reng.RenGException] and nothing wider, so cancellation passes straight through -- and
     * it is asserted on type rather than on identity, because Kotlin's stack recovery may hand back a copy
     * carrying the original as its immediate cause.
     */
    @Test
    fun keepsCancellationUnwrappedRatherThanDegradingOnIt() = runTest {
        val cancellingTransport = object : Transport {
            override suspend fun execute(request: TransportRequest): TransportResponse =
                throw CancellationException("cancelled")
        }
        val host = basemapEngineHost(transport = cancellingTransport, store = CountingHostStore())
        try {
            val manifest = terrainManifest(TERRAIN_STYLE_JSON)
            host.withOperation(
                ResourceAccessMode.NORMAL,
                tileTimeRoutes(manifest, listOf(TERRAIN_TILE), ResourceAccessMode.NORMAL, ResourceLimits()),
            ) {
                val style = host.terrainStyle(TERRAIN_STYLE_JSON)
                assertFailsWith<CancellationException> {
                    TerrainAcquisition(host).acquire(style, manifest, listOf(TERRAIN_TILE))
                }
            }
        } finally {
            host.close()
        }
    }

    // ---- fixture plumbing --------------------------------------------------------------------------

    private suspend fun withTerrainHost(block: suspend (BasemapEngineHost, TerrainAcquisition) -> Unit) {
        val host = basemapEngineHost(transport = RecordingTerrainTransport(), store = CountingHostStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) { block(host, TerrainAcquisition(host)) }
        } finally {
            host.close()
        }
    }

    private suspend fun BasemapEngineHost.terrainStyle(json: String): PreparedStyle =
        preparedStyle(TERRAIN_STYLE_KEY, terrainStyleRecord(json), TERRAIN_STYLE_BASE_URI)
}

// ---- fixtures --------------------------------------------------------------------------------------

private const val TERRAIN_STYLE_BASE_URI: String = "https://styles.example/terrain.json"
private const val TERRAIN_DEM_TEMPLATE: String = "https://tiles.example/t/d/{z}/{x}/{y}.png"
private const val TERRAIN_SOURCE_ID: String = "dem"

/** `sha256Hex("dem")`, written out rather than derived -- see the case that reads it. */
private const val TERRAIN_SOURCE_ID_DIGEST: String =
    "5bd1269b11fc4c7c128e36854805a009bde2de968bbe5ddf287cab7bdb1bd6d8"

/**
 * Every descriptor field set away from Rentile's default: `tileSize` defaults to 512, `minzoom` to 0,
 * `maxzoom` to 30 and `encoding` to mapbox. It declares no `lights`, which is one half of the
 * independence claim between terrain and ground radiance.
 */
private val TERRAIN_STYLE_JSON: String =
    """{"version":8,"name":"reng-terrain-acquisition",""" +
        """"sources":{"$TERRAIN_SOURCE_ID":{"type":"raster-dem","tiles":["$TERRAIN_DEM_TEMPLATE"],""" +
        """"tileSize":256,"encoding":"terrarium","minzoom":2,"maxzoom":14}},""" +
        """"terrain":{"source":"$TERRAIN_SOURCE_ID"},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}}]}"""

/** The same document with `terrain` naming a second, equally real source. */
private val OTHER_TERRAIN_STYLE_JSON: String =
    """{"version":8,"name":"reng-terrain-acquisition-other",""" +
        """"sources":{"$TERRAIN_SOURCE_ID":{"type":"raster-dem","tiles":["$TERRAIN_DEM_TEMPLATE"],""" +
        """"tileSize":256,"encoding":"terrarium","minzoom":2,"maxzoom":14},""" +
        """"other":{"type":"raster-dem","tiles":["https://tiles.example/t/o/{z}/{x}/{y}.png"],""" +
        """"tileSize":256,"minzoom":2,"maxzoom":14}},""" +
        """"terrain":{"source":"other"},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}}]}"""

/** Lights and no terrain: the pairing Rentile compiles a ground radiance for and no DEM source from. */
private val LIT_STYLE_JSON: String =
    """{"version":8,"name":"reng-terrain-lit","sources":{},""" +
        """"lights":[{"id":"a","type":"ambient","properties":{"color":"#ffffff"}},""" +
        """{"id":"d","type":"directional","properties":{"color":"#ffffff"}}],""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}}]}"""

/** Both lights at full intensity, the directional one straight overhead: the encoding's own ceiling. */
private val SATURATED_LIGHT_STYLE_JSON: String =
    """{"version":8,"name":"reng-terrain-saturated","sources":{},""" +
        """"lights":[{"id":"a","type":"ambient","properties":{"color":"#ffffff","intensity":1}},""" +
        """{"id":"d","type":"directional","properties":{"color":"#ffffff","intensity":1,""" +
        """"direction":[210,0]}}],""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}}]}"""

/** `TileId(z = 3, x = 5, y = 2)`: asymmetric in both axes, so a transposed ring fails rather than passes. */
private val TERRAIN_TILE: CanonicalBasemapTile = CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5)

/**
 * The nine urls [TERRAIN_TILE]'s eight-neighbourhood composes, written out by hand. A derivation here
 * would be `basemapTileUrl` restated, and this fixture exists to check the request against something that
 * is not the code that built it.
 */
private val TERRAIN_RING_URLS: List<String> = listOf(
    "https://tiles.example/t/d/3/4/1.png",
    "https://tiles.example/t/d/3/5/1.png",
    "https://tiles.example/t/d/3/6/1.png",
    "https://tiles.example/t/d/3/4/2.png",
    "https://tiles.example/t/d/3/5/2.png",
    "https://tiles.example/t/d/3/6/2.png",
    "https://tiles.example/t/d/3/4/3.png",
    "https://tiles.example/t/d/3/5/3.png",
    "https://tiles.example/t/d/3/6/3.png",
)

private val TERRAIN_SOURCE: TerrainSource = TerrainSource(
    styleSourceId = TERRAIN_SOURCE_ID,
    encoding = DemEncoding.TERRARIUM,
    tileSizePx = 256,
    minimumZoom = 2,
    maximumZoom = 14,
)

private val TERRAIN_STYLE_KEY: ResourceKey = ResourceKeyDeriver(PureKotlinSha256)
    .external(ResourceClass.BASEMAP_STYLE, ResourceLocator(TERRAIN_STYLE_BASE_URI))
    .key

private fun terrainStyleRecord(json: String): StoredRawResource {
    val bytes = json.encodeToByteArray()
    return StoredRawResource(
        bytes = bytes,
        contentDigest = PureKotlinSha256.digest(CanonicalBytes(bytes)).lowercaseHex,
        metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
    )
}

private fun terrainManifest(json: String): BasemapStyleManifest {
    val outcome = deriveBasemapStyleManifest(json.encodeToByteArray(), TERRAIN_STYLE_BASE_URI)
    return (outcome as BasemapStyleManifestOutcome.Derived).manifest
}

/**
 * One [ValidatedDemTile] shaped exactly as `acquireTerrainTiles` shapes it, with a one-byte payload
 * identifying the tile it belongs to. No rasteriser is involved: matching a result onto its request never
 * reads the bytes, which is what lets the hardest case in this suite run on every target.
 */
private fun demTile(tile: CanonicalBasemapTile, payload: Int): ValidatedDemTile {
    val id = TileId(z = tile.lod, x = tile.canonicalX, y = tile.tileY)
    return ValidatedDemTile(
        requestedTile = id,
        sourceTile = id,
        sourceId = TERRAIN_SOURCE_ID_DIGEST,
        encoding = TerrainDemEncoding.TERRARIUM,
        bytes = byteArrayOf(payload.toByte()),
        contentDigest = TERRAIN_SOURCE_ID_DIGEST,
    )
}

/**
 * Records every url it is asked for and answers [statusCode]; nothing here needs a decodable body.
 *
 * [ConcurrentRecorder] rather than a plain list, for the reason the label fixture gives: Rentile acquires
 * a DEM neighbourhood as concurrent `Dispatchers.Default` children, so a `MutableList` genuinely loses
 * entries here.
 */
private class RecordingTerrainTransport(private val statusCode: Int = 404) : Transport {
    private val urls = ConcurrentRecorder<String>()

    suspend fun requestedUrls(): List<String> = urls.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        urls.record(request.locator.value)
        return TransportResponse(statusCode = statusCode, body = ByteArray(0))
    }
}
