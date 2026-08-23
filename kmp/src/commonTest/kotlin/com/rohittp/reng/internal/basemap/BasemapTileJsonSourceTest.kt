package com.rohittp.reng.internal.basemap

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `url` form of a style source: `"url": "https://.../tiles.json"` rather than inline `"tiles": [...]`.
 *
 * **Why this is the difference between a basemap and no basemap.** Across the 34 map styles Rentile is
 * verified for, 96 sources declare `url` and 2 declare inline `tiles` -- every one of the 34 needs at
 * least one `url`-form source. A source RenG cannot route contributes no tile route, so the engine's
 * request for that tile is refused by the firewall and the ground never draws.
 *
 * Every expectation here is a byte-exact string, for the same reason `BasemapStyleManifestTest` states:
 * the firewall matches by exact string equality, so a plausible-but-different url is a total outage
 * rather than a visible mismatch. The facts reproduced are `TileJsonResourceAcquirer.parseOrThrow` and
 * the `resolvedTileJson` half of `StyleCompiler.compileVectorSource` / `compileRasterSource`, both at
 * Rentile's pinned `0.5.0` release commit `d899cb2`.
 */
class BasemapTileJsonSourceTest {

    // ---- the document url is derivable from the style, so it preregisters like any other route ----

    @Test
    fun routesTheTileJsonDocumentItselfAsAStyleTimeResourceOfItsOwnClass() {
        val manifest = manifestOf(VECTOR_BY_URL_STYLE)

        assertEquals(emptyList(), manifest.underivableSources, "a url-form source is routable, not deferred")
        assertEquals(emptyList(), manifest.sources, "and contributes no tile source until its document arrives")
        val routes = styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS)
        assertEquals(listOf(TILE_JSON_URL), routes.map { it.locator.value })
        assertEquals(listOf(ResourceClass.BASEMAP_TILE_JSON), routes.map { it.resourceClass })
        assertEquals(
            listOf(LIMITS.maximumBasemapMetadataBytes),
            routes.map { it.maximumResponseBytes },
            "the TileJSON document is basemap metadata, ceiling included",
        )
    }

    @Test
    fun resolvesARelativeTileJsonReferenceAgainstTheStylesOwnLocator() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"v":{"type":"vector","url":"../data/v3.json?key=k"}},"layers":[]}""",
        )
        assertEquals(
            listOf("https://styles.example/data/v3.json?key=k"),
            styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
    }

    @Test
    fun defersASourceWhoseTileJsonReferenceCannotBeResolvedAtAll() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"v":{"type":"vector","url":"data/v3.json"}},"layers":[]}""",
            baseUri = "styles.example/basic.json",
        )
        assertEquals(
            listOf(UnderivableBasemapSource("v", BasemapSourceUnderivableReason.SOURCE_REFERENCE_UNRESOLVABLE)),
            manifest.underivableSources,
        )
        assertEquals(emptyList(), styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS))
    }

    @Test
    fun defersASourceThatDeclaresBothFormsBecauseRentileRefusesToCompileOne() {
        // `StyleCompiler.compileVectorSource` :1025 and `compileRasterSource` :1300 both failRetained on
        // "cannot declare both url and tiles", and the test is `"tiles" in source` -- key presence, not
        // type -- so an empty or non-array `tiles` beside a `url` conflicts just the same.
        val manifest = manifestOf(
            """{"version":8,"sources":{"v":{"type":"vector","url":"$TILE_JSON_URL","tiles":[]}},"layers":[]}""",
        )
        assertEquals(
            listOf(UnderivableBasemapSource("v", BasemapSourceUnderivableReason.SOURCE_DECLARES_URL_AND_TILES)),
            manifest.underivableSources,
        )
        assertEquals(emptyList(), styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS))
    }

    @Test
    fun leavesANonStringUrlMemberAbsentSoTheInlineFormStillRoutes() {
        // Rentile reads the reference through `asPrimitive()?.takeIf { it.isString }`, so `"url": 7` is
        // simply absent to it and there is no url/tiles conflict either.
        val manifest = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster","url":7,""" +
                """"tiles":["https://tiles.example/r/{z}/{x}/{y}.png"]}},"layers":[]}""",
        )
        assertEquals(emptyList(), manifest.underivableSources)
        assertEquals(
            listOf("https://tiles.example/r/2/1/1.png"),
            tileRoutesOf(manifest, emptyMap()),
        )
    }

    /**
     * The 17 attribution-only sources in the verified corpus: `{"type":"vector","attribution":"..."}`,
     * declaring neither form. Contributing no route and not rejecting the style is already correct for
     * them, and adding the `url` form must not change it.
     */
    @Test
    fun leavesAnAttributionOnlySourceContributingNoRouteAndFailingNothing() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"maptiler_attribution":{"type":"vector",""" +
                """"attribution":"&copy; MapTiler"}},"layers":[]}""",
        )
        assertEquals(
            listOf(
                UnderivableBasemapSource("maptiler_attribution", BasemapSourceUnderivableReason.SOURCE_TILES_EMPTY),
            ),
            manifest.underivableSources,
        )
        assertEquals(emptyList(), styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS))
        assertEquals(emptyList(), tileRoutesOf(manifest, emptyMap()))
    }

    // ---- the document's own facts ------------------------------------------------------------

    @Test
    fun composesTheExactTileUrlsTheTileJsonDocumentDeclares() {
        val manifest = manifestOf(VECTOR_BY_URL_STYLE)

        assertEquals(
            listOf("https://tiles.example/v/2/1/1.pbf?key=k"),
            tileRoutesOf(manifest, mapOf(TILE_JSON_URL to parsedTileJson(TILE_JSON_VECTOR_BODY, TILE_JSON_URL))),
        )
    }

    /**
     * A TileJSON tile template resolves against the **TileJSON document's** own url, not the style's --
     * `TileJsonResourceAcquirer.parseOrThrow` passes `baseUrl = url` (the document url) into
     * `resolveHttpReference`. The two bases differ in host here, so composing against the wrong one
     * produces a well-formed url the firewall has never heard of.
     */
    @Test
    fun resolvesARelativeTileTemplateAgainstTheTileJsonDocumentRatherThanTheStyle() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"v":{"type":"vector","url":"https://api.example/data/v3.json"}},""" +
                """"layers":[]}""",
        )
        val document = parsedTileJson(
            """{"tilejson":"2.0.0","tiles":["../t/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14}""",
            "https://api.example/data/v3.json",
        )

        assertEquals(
            listOf("https://api.example/t/2/1/1.pbf"),
            tileRoutesOf(manifest, mapOf("https://api.example/data/v3.json" to document)),
            "resolved against the document url; the style base would have composed styles.example",
        )
    }

    /**
     * `minZoom = maxOf(styleMinZoom ?: 0, tileJsonMinZoom)` and
     * `maxZoom = minOf(styleMaxZoom ?: default, tileJsonMaxZoom)` -- `StyleCompiler` :1055-1056 and
     * :1337-1338. Neither side simply wins; the tighter of the two does.
     *
     * That is a silent-failure trap rather than an optimisation: past a source's maxzoom the requested
     * url's z, x and y are all different (`min(z, maxZoom)` and the child-scale division), so keeping the
     * style's looser range composes a tile the engine never asks for and omits the one it does.
     */
    @Test
    fun takesTheTighterOfTheStyleAndTheDocumentAtEachEndOfTheZoomRange() {
        val cappedByDocument = manifestOf(VECTOR_BY_URL_STYLE)
        assertEquals(
            listOf("https://tiles.example/v/2/0/0.pbf?key=k"),
            tileRoutesOf(
                cappedByDocument,
                mapOf(TILE_JSON_URL to parsedTileJson(tileJsonVector(minZoom = 0, maxZoom = 2), TILE_JSON_URL)),
                tiles = listOf(TILE_4_2_3),
            ),
            "the document's maxzoom 2 clamps a LOD 4 tile to z2 and divides its x and y by four",
        )

        val cappedByStyle = manifestOf(
            """{"version":8,"sources":{"v":{"type":"vector","url":"$TILE_JSON_URL","maxzoom":2}},"layers":[]}""",
        )
        assertEquals(
            listOf("https://tiles.example/v/2/0/0.pbf?key=k"),
            tileRoutesOf(
                cappedByStyle,
                mapOf(TILE_JSON_URL to parsedTileJson(tileJsonVector(minZoom = 0, maxZoom = 14), TILE_JSON_URL)),
                tiles = listOf(TILE_4_2_3),
            ),
            "and the style's own maxzoom 2 clamps it identically when the document is looser",
        )

        val flooredByDocument = manifestOf(VECTOR_BY_URL_STYLE)
        assertEquals(
            emptyList(),
            tileRoutesOf(
                flooredByDocument,
                mapOf(TILE_JSON_URL to parsedTileJson(tileJsonVector(minZoom = 6, maxZoom = 14), TILE_JSON_URL)),
                tiles = listOf(TILE_4_2_3),
            ),
            "a document minzoom above the frame's LOD suppresses the source entirely",
        )
    }

    @Test
    fun prefersTheStylesSchemeOverTheDocumentsAndTheDocumentsOverXyz() {
        val documentIsTms = parsedTileJson(
            """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"minzoom":0,"maxzoom":14,"scheme":"tms"}""",
            TILE_JSON_URL,
        )
        assertEquals(
            listOf("https://tiles.example/v/2/1/2.pbf?key=k"),
            tileRoutesOf(manifestOf(VECTOR_BY_URL_STYLE), mapOf(TILE_JSON_URL to documentIsTms)),
            "with no style scheme the document's tms flips y from 1 to 2 at z2",
        )
        assertEquals(
            listOf("https://tiles.example/v/2/1/1.pbf?key=k"),
            tileRoutesOf(
                manifestOf(
                    """{"version":8,"sources":{"v":{"type":"vector","url":"$TILE_JSON_URL",""" +
                        """"scheme":"xyz"}},"layers":[]}""",
                ),
                mapOf(TILE_JSON_URL to documentIsTms),
            ),
            "and the style's own scheme wins outright when it declares one",
        )
    }

    @Test
    fun spreadsMultipleDocumentTemplatesThroughRentilesOwnHashRoundRobin() {
        val document = parsedTileJson(
            """{"tilejson":"2.0.0","tiles":["https://a.example/{z}/{x}/{y}.pbf",""" +
                """"https://b.example/{z}/{x}/{y}.pbf","https://c.example/{z}/{x}/{y}.pbf"],""" +
                """"minzoom":0,"maxzoom":14}""",
            TILE_JSON_URL,
        )
        // floorMod(z * 31 + x * 17 + y, 3): (2,1,1) -> 80 % 3 = 2, (4,2,3) -> 161 % 3 = 2, (2,0,0) -> 62 % 3 = 2.
        // (3,1,2) -> 111 % 3 = 0 and (2,1,2) -> 81 % 3 = 0 pick the first, (4,1,2) -> 143 % 3 = 2.
        assertEquals(
            listOf("https://c.example/2/1/1.pbf", "https://a.example/2/1/2.pbf"),
            tileRoutesOf(
                manifestOf(VECTOR_BY_URL_STYLE),
                mapOf(TILE_JSON_URL to document),
                tiles = listOf(TILE_2_1_1, CanonicalBasemapTile(lod = 2, tileY = 2, canonicalX = 1)),
            ),
        )
    }

    @Test
    fun expandsAUrlFormDemSourceOverTheSameThreeByThreeNeighbourhoodAsAnInlineOne() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"d":{"type":"raster-dem","url":"$TILE_JSON_URL"}},"layers":[]}""",
        )
        val document = parsedTileJson(
            """{"tilejson":"2.0.0","tiles":["https://dem.example/{z}/{x}/{y}.png"],"minzoom":0,"maxzoom":14}""",
            TILE_JSON_URL,
        )

        assertEquals(
            (0..2).flatMap { y -> (0..2).map { x -> "https://dem.example/2/$x/$y.png" } }.sorted(),
            tileRoutesOf(manifest, mapOf(TILE_JSON_URL to document)).sorted(),
        )
    }

    @Test
    fun readsTheTileSizeFromTheStyleThenTheDocumentThenFiveHundredAndTwelve() {
        val documentWithTileSize = parsedTileJson(
            """{"tilejson":"2.0.0","tiles":["$RASTER_TEMPLATE"],"minzoom":0,"maxzoom":14,"tileSize":256}""",
            TILE_JSON_URL,
        )
        val documentWithout = parsedTileJson(
            """{"tilejson":"2.0.0","tiles":["$RASTER_TEMPLATE"],"minzoom":0,"maxzoom":14}""",
            TILE_JSON_URL,
        )
        val styleDeclares = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster","url":"$TILE_JSON_URL","tileSize":64}},"layers":[]}""",
        )
        val styleSilent = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster","url":"$TILE_JSON_URL"}},"layers":[]}""",
        )

        assertEquals(64, sourceOf(styleDeclares, mapOf(TILE_JSON_URL to documentWithTileSize)).tileSizePixels)
        assertEquals(256, sourceOf(styleSilent, mapOf(TILE_JSON_URL to documentWithTileSize)).tileSizePixels)
        assertEquals(512, sourceOf(styleSilent, mapOf(TILE_JSON_URL to documentWithout)).tileSizePixels)
        assertNull(
            sourceOf(manifestOf(VECTOR_BY_URL_STYLE), mapOf(TILE_JSON_URL to documentWithTileSize)).tileSizePixels,
            "a vector source has no tile size at all -- Rentile does not even accept the member",
        )
    }

    // ---- degrading, never rejecting ------------------------------------------------------------

    /**
     * A TileJSON that never arrives, will not parse, or yields no usable template degrades **its own
     * source** and nothing else. Rejecting the style would break every style that has one bad source
     * among several working ones; the engine failing on a source it genuinely needs already surfaces
     * loudly through the firewall as `AMBIGUOUS_RESOURCE_ROUTE`.
     */
    @Test
    fun degradesOnlyTheSourceWhoseTileJsonIsUnusableAndKeepsEverySibling() {
        val manifest = manifestOf(MIXED_STYLE)

        val completed = completeBasemapStyleManifest(
            manifest,
            mapOf(BAD_TILE_JSON_URL to parseBasemapTileJson("{ not json".encodeToByteArray(), BAD_TILE_JSON_URL)),
        )

        assertEquals(
            listOf("inline"),
            completed.sources.map { it.sourceId },
            "the good url source is absent too -- its document never arrived -- so only the inline one routes",
        )
        assertEquals(
            listOf(
                UnderivableBasemapSource("good", BasemapSourceUnderivableReason.TILE_JSON_UNOBSERVED),
                UnderivableBasemapSource("bad", BasemapSourceUnderivableReason.TILE_JSON_MALFORMED),
            ),
            completed.underivableSources,
        )
        assertEquals(
            listOf("https://tiles.example/r/2/1/1.png"),
            tileTimeRoutes(completed, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS)
                .map { it.locator.value },
            "the inline sibling routes exactly as it did before",
        )
    }

    @Test
    fun namesEveryReasonADocumentCanBeUnusableForRatherThanDiscardingIt() {
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_MALFORMED,
            """{"tilejson":"2.0.0",""",
        )
        assertDocumentRejected(BasemapSourceUnderivableReason.TILE_JSON_ROOT_NOT_OBJECT, """[1,2,3]""")
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_DUPLICATE_MEMBER_NAME,
            """{"tilejson":"2.0.0","tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"]}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_VERSION_UNSUPPORTED,
            """{"tilejson":"1.0.0","tiles":["$VECTOR_TEMPLATE"]}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_VERSION_NOT_STRING,
            """{"tilejson":2,"tiles":["$VECTOR_TEMPLATE"]}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_TILES_NOT_STRINGS,
            """{"tilejson":"2.0.0","tiles":[7]}""",
        )
        assertDocumentRejected(BasemapSourceUnderivableReason.TILE_JSON_TILES_EMPTY, """{"tilejson":"2.0.0"}""")
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_TILES_EMPTY,
            """{"tilejson":"2.0.0","tiles":[]}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_REFERENCE_UNRESOLVABLE,
            """{"tilejson":"2.0.0","tiles":["t/{z}/{x}/{y}.pbf"]}""",
            documentUrl = "api.example/tiles.json",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_ZOOM_NOT_INTEGER,
            """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"minzoom":1.5}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_ZOOM_RANGE_INVALID,
            """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"minzoom":9,"maxzoom":4}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_SCHEME_NOT_STRING,
            """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"scheme":7}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_SCHEME_UNSUPPORTED,
            """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"scheme":"quad"}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_TILE_SIZE_NOT_INTEGER,
            """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"tileSize":"big"}""",
        )
        assertDocumentRejected(
            BasemapSourceUnderivableReason.TILE_JSON_TILE_SIZE_UNSUPPORTED,
            """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"tileSize":1024}""",
        )
    }

    @Test
    fun acceptsADocumentWithNoVersionMemberAtAllExactlyAsRentileDoes() {
        // `parseOrThrow` reads `tilejson` only if present; a document without it is accepted, and its
        // zoom range defaults to 0..22.
        val document = parsedTileJson("""{"tiles":["$VECTOR_TEMPLATE"]}""", TILE_JSON_URL)
        assertEquals(
            listOf("https://tiles.example/v/2/1/1.pbf?key=k"),
            tileRoutesOf(manifestOf(VECTOR_BY_URL_STYLE), mapOf(TILE_JSON_URL to document)),
        )
    }

    @Test
    fun defaultsTheDocumentsZoomRangeToZeroThroughTwentyTwoWhenItDeclaresNeither() {
        val document = parsedTileJson("""{"tilejson":"3.0.0","tiles":["$RASTER_TEMPLATE"]}""", TILE_JSON_URL)
        val raster = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster","url":"$TILE_JSON_URL"}},"layers":[]}""",
        )
        // A raster source alone defaults to maxzoom 30, but a TileJSON that declares none defaults to 22,
        // and the tighter of the two wins -- so a z25 tile clamps to z22 rather than staying at z25.
        assertEquals(22, sourceOf(raster, mapOf(TILE_JSON_URL to document)).maxZoom)
        assertEquals(0, sourceOf(raster, mapOf(TILE_JSON_URL to document)).minZoom)
    }

    @Test
    fun degradesTheSourceWhenTheCombinedZoomRangeInvertsRatherThanFailingTheStyle() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"v":{"type":"vector","url":"$TILE_JSON_URL","minzoom":9}},"layers":[]}""",
        )
        val completed = completeBasemapStyleManifest(
            manifest,
            mapOf(TILE_JSON_URL to parsedTileJson(tileJsonVector(minZoom = 0, maxZoom = 4), TILE_JSON_URL)),
        )
        assertEquals(emptyList(), completed.sources)
        assertEquals(
            listOf(UnderivableBasemapSource("v", BasemapSourceUnderivableReason.SOURCE_ZOOM_RANGE_INVALID)),
            completed.underivableSources,
        )
    }

    @Test
    fun completingATwiceCompletedManifestAddsNothingASecondTime() {
        val documents = mapOf(TILE_JSON_URL to parsedTileJson(TILE_JSON_VECTOR_BODY, TILE_JSON_URL))
        val once = completeBasemapStyleManifest(manifestOf(VECTOR_BY_URL_STYLE), documents)
        val twice = completeBasemapStyleManifest(once, documents)

        assertEquals(once.sources.map { it.sourceId }, twice.sources.map { it.sourceId })
        assertEquals(once.underivableSources, twice.underivableSources)
        assertEquals(emptyList(), twice.tileJsonSources)
        assertEquals(
            emptyList(),
            styleTimeRoutes(twice, ResourceAccessMode.NORMAL, LIMITS).filter {
                it.resourceClass == ResourceClass.BASEMAP_TILE_JSON
            },
            "a completed manifest has no outstanding document to fetch",
        )
    }

    @Test
    fun keepsTheStylesSpriteAndGeoJsonRoutesIntactThroughCompletion() {
        val manifest = manifestOf(MIXED_STYLE)
        val before = styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS)
        assertTrue(
            before.any { it.resourceClass == ResourceClass.BASEMAP_SPRITE_JSON },
            "the fixture must carry a sprite, or this proves nothing",
        )
        val completed = completeBasemapStyleManifest(manifest, emptyMap())

        assertEquals(
            before.filterNot { it.resourceClass == ResourceClass.BASEMAP_TILE_JSON },
            styleTimeRoutes(completed, ResourceAccessMode.NORMAL, LIMITS),
        )
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun manifestOf(styleJson: String, baseUri: String = STYLE_BASE_URI): BasemapStyleManifest =
        assertIs<BasemapStyleManifestOutcome.Derived>(
            deriveBasemapStyleManifest(styleJson.encodeToByteArray(), baseUri),
        ).manifest

    private fun parsedTileJson(body: String, documentUrl: String): BasemapTileJsonOutcome =
        parseBasemapTileJson(body.encodeToByteArray(), documentUrl)

    private fun tileRoutesOf(
        manifest: BasemapStyleManifest,
        documents: Map<String, BasemapTileJsonOutcome>,
        tiles: List<CanonicalBasemapTile> = listOf(TILE_2_1_1),
    ): List<String> = tileTimeRoutes(
        completeBasemapStyleManifest(manifest, documents),
        tiles,
        ResourceAccessMode.NORMAL,
        LIMITS,
    ).map { it.locator.value }

    private fun sourceOf(
        manifest: BasemapStyleManifest,
        documents: Map<String, BasemapTileJsonOutcome>,
    ): BasemapStyleSource = completeBasemapStyleManifest(manifest, documents).sources.single()

    private fun assertDocumentRejected(
        reason: BasemapSourceUnderivableReason,
        body: String,
        documentUrl: String = TILE_JSON_URL,
    ) {
        val outcome = parseBasemapTileJson(body.encodeToByteArray(), documentUrl)
        assertEquals(BasemapTileJsonOutcome.Rejected(reason), outcome)
        // And it degrades the source that named it rather than failing the style.
        val completed = completeBasemapStyleManifest(manifestOf(VECTOR_BY_URL_STYLE), mapOf(TILE_JSON_URL to outcome))
        assertEquals(emptyList(), completed.sources)
        assertEquals(listOf(UnderivableBasemapSource("v", reason)), completed.underivableSources)
    }
}

private const val STYLE_BASE_URI: String = "https://styles.example/maps/basic.json"

private val LIMITS: ResourceLimits = ResourceLimits()

private val TILE_2_1_1: CanonicalBasemapTile = CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 1)

/** LOD 4, and neither coordinate is the other's, so a clamp to z2 lands on `(0, 0)` unambiguously. */
private val TILE_4_2_3: CanonicalBasemapTile = CanonicalBasemapTile(lod = 4, tileY = 3, canonicalX = 2)

/**
 * Shaped like the 16 documents the verified corpus actually names: an absolute template carrying the
 * account key in its query, an explicit zoom range, and no `scheme` or `tileSize` at all.
 */
private const val TILE_JSON_URL: String = "https://tiles.example/v/tiles.json?key=k"

private const val VECTOR_TEMPLATE: String = "https://tiles.example/v/{z}/{x}/{y}.pbf?key=k"

private const val RASTER_TEMPLATE: String = "https://tiles.example/r/{z}/{x}/{y}.png?key=k"

private const val TILE_JSON_VECTOR_BODY: String =
    """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"minzoom":0,"maxzoom":14,""" +
        """"bounds":[-180,-85.0511,180,85.0511]}"""

private fun tileJsonVector(minZoom: Int, maxZoom: Int): String =
    """{"tilejson":"2.0.0","tiles":["$VECTOR_TEMPLATE"],"minzoom":$minZoom,"maxzoom":$maxZoom}"""

private const val VECTOR_BY_URL_STYLE: String =
    """{"version":8,"sources":{"v":{"type":"vector","url":"$TILE_JSON_URL"}},"layers":[]}"""

private const val BAD_TILE_JSON_URL: String = "https://tiles.example/bad/tiles.json"

/** One inline source, one `url` source whose document arrives, and one whose document does not. */
private const val MIXED_STYLE: String =
    """{"version":8,"sprite":"https://sprites.example/atlas","sources":{""" +
        """"inline":{"type":"raster","tiles":["https://tiles.example/r/{z}/{x}/{y}.png"]},""" +
        """"good":{"type":"vector","url":"$TILE_JSON_URL"},""" +
        """"bad":{"type":"vector","url":"$BAD_TILE_JSON_URL"}},"layers":[]}"""
