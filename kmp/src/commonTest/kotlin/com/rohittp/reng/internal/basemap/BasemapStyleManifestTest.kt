package com.rohittp.reng.internal.basemap

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.StyleFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every expectation here is a byte-exact string lifted from Rentile's own composition at the pinned
 * `0.6.0` release commit `87ccba2`, because RenG's firewall matches an engine request by exact string
 * equality: a plausible-but-different URL is refused, not repaired.
 */
class BasemapStyleManifestTest {

    // ---- resolveHttpReference: the bespoke, non-RFC-3986 resolver ------------------------------

    @Test
    fun returnsAnAbsoluteHttpReferenceVerbatimWithoutNormalisingIt() {
        assertEquals(
            "https://other.example/a/../b.png",
            resolveHttpReference(STYLE_BASE_URI, "https://other.example/a/../b.png"),
        )
        assertEquals(
            "http://other.example/x.png",
            resolveHttpReference(STYLE_BASE_URI, "http://other.example/x.png"),
        )
    }

    @Test
    fun resolvesARelativeReferenceAgainstTheBaseDirectory() {
        assertEquals(
            "https://styles.example/maps/tiles/{z}/{x}/{y}.pbf",
            resolveHttpReference(STYLE_BASE_URI, "tiles/{z}/{x}/{y}.pbf"),
        )
    }

    @Test
    fun resolvesAnAbsolutePathReferenceAgainstTheOrigin() {
        assertEquals(
            "https://styles.example/t/{z}.png",
            resolveHttpReference(STYLE_BASE_URI, "/t/{z}.png"),
        )
    }

    @Test
    fun splicesOnlyTheSchemeForAProtocolRelativeReference() {
        assertEquals(
            "https://cdn.example/t/{z}.png",
            resolveHttpReference(STYLE_BASE_URI, "//cdn.example/t/{z}.png"),
        )
    }

    @Test
    fun givesAProtocolRelativeReferenceWithNoPathATrailingSlash() {
        assertEquals("https://cdn.example/", resolveHttpReference(STYLE_BASE_URI, "//cdn.example"))
    }

    @Test
    fun dropsTheBaseQueryBeforeJoiningButKeepsTheReferenceSuffix() {
        assertEquals(
            "https://styles.example/maps/sprite",
            resolveHttpReference("https://styles.example/maps/basic.json?key=abc", "sprite"),
        )
        assertEquals(
            "https://styles.example/maps/t.png?k=1",
            resolveHttpReference(STYLE_BASE_URI, "t.png?k=1"),
        )
        assertEquals(
            "https://styles.example/maps/t.png#f",
            resolveHttpReference(STYLE_BASE_URI, "t.png#f"),
        )
        assertEquals(
            "https://styles.example/maps/t.png?k=1#f",
            resolveHttpReference(STYLE_BASE_URI, "t.png?k=1#f"),
        )
        // The path is cut at '?' first and then at '#', so a fragment carrying a '?' keeps the whole
        // fragment as the suffix.
        assertEquals(
            "https://styles.example/maps/t.png#f?k=1",
            resolveHttpReference(STYLE_BASE_URI, "t.png#f?k=1"),
        )
    }

    @Test
    fun dropsTheBaseFragmentBeforeJoining() {
        assertEquals(
            "https://styles.example/maps/t.png",
            resolveHttpReference("https://styles.example/maps/basic.json#frag", "t.png"),
        )
    }

    @Test
    fun dropsEmptyPathSegmentsSoATrailingSlashDisappears() {
        assertEquals(
            "https://styles.example/maps/t.json",
            resolveHttpReference("https://styles.example/maps/", "t.json"),
        )
        assertEquals(
            "https://styles.example/maps/sub",
            resolveHttpReference("https://styles.example/maps/", "sub/"),
        )
        assertEquals(
            "https://styles.example/maps",
            resolveHttpReference(STYLE_BASE_URI, ""),
        )
    }

    @Test
    fun normalisesDotAndDotDotSegmentsAndClampsAtTheOrigin() {
        assertEquals(
            "https://styles.example/a/c/t.png",
            resolveHttpReference("https://styles.example/a/b/style.json", "../c/t.png"),
        )
        assertEquals(
            "https://styles.example/x.png",
            resolveHttpReference("https://styles.example/a/b/style.json", "../../../x.png"),
        )
        assertEquals(
            "https://styles.example/a/b/t.png",
            resolveHttpReference("https://styles.example/a/b/style.json", "./t.png"),
        )
    }

    @Test
    fun resolvesAgainstABaseThatCarriesNoPath() {
        assertEquals("https://styles.example/t.json", resolveHttpReference("https://styles.example", "t.json"))
        assertEquals(
            "https://styles.example/t.json",
            resolveHttpReference("https://styles.example?key=abc", "t.json"),
        )
    }

    @Test
    fun refusesToResolveAgainstABaseWithNoScheme() {
        assertNull(resolveHttpReference("styles.example/basic.json", "t.json"))
        assertNull(resolveHttpReference("://styles.example/basic.json", "t.json"))
    }

    // ---- appendSpriteExtension -----------------------------------------------------------------

    @Test
    fun appendsTheSpriteExtensionBeforeTheQueryAndFragment() {
        assertEquals("https://h/sprite.json", appendSpriteExtension("https://h/sprite", ".json"))
        assertEquals("https://h/sprite.png", appendSpriteExtension("https://h/sprite", ".png"))
        assertEquals("https://h/sprite@2x.json", appendSpriteExtension("https://h/sprite@2x", ".json"))
        assertEquals("https://h/sprite.png?key=abc", appendSpriteExtension("https://h/sprite?key=abc", ".png"))
        assertEquals("https://h/sprite.json#f", appendSpriteExtension("https://h/sprite#f", ".json"))
        assertEquals(
            "https://h/sprite.png?key=abc#f",
            appendSpriteExtension("https://h/sprite?key=abc#f", ".png"),
        )
        // '#' is located before '?', so a fragment carrying a '?' keeps the whole fragment.
        assertEquals("https://h/sprite.json#f?key=abc", appendSpriteExtension("https://h/sprite#f?key=abc", ".json"))
    }

    @Test
    fun neverDropsAnExistingExtensionFromTheSpriteBase() {
        // Rentile appends unconditionally at `2d0a5bf`; an already-suffixed base doubles up, and RenG
        // must preregister the doubled url because that is the one the engine will request.
        assertEquals("https://h/sprite.json.json", appendSpriteExtension("https://h/sprite.json", ".json"))
        assertEquals("https://h/sprite.json.png", appendSpriteExtension("https://h/sprite.json", ".png"))
    }

    // ---- manifest parsing ----------------------------------------------------------------------

    @Test
    fun readsSourcesSpriteAndTerrainOutOfAStyleDocument() {
        val manifest = manifestOf(
            """{"version":8,"sprite":"sprites/basic","terrain":{"source":"dem","exaggeration":1.2},""" +
                """"sources":{""" +
                """"v":{"type":"vector","tiles":["v/{z}/{x}/{y}.pbf"],"maxzoom":25},""" +
                """"r":{"type":"raster","tiles":["r/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":25},""" +
                """"dem":{"type":"raster-dem","tiles":["d/{z}/{x}/{y}.png"],"tile-size":64},""" +
                """"g":{"type":"geojson","data":"data/points.geojson"}},""" +
                """"layers":[]}""",
        )

        assertEquals("https://styles.example/maps/sprites/basic", manifest.spriteBase)
        assertEquals("dem", manifest.terrainSourceId)
        assertEquals(emptyList(), manifest.underivableSources)
        assertEquals(listOf("v", "r", "dem", "g"), manifest.sources.map { it.sourceId })

        val vector = manifest.sources.single { it.sourceId == "v" }
        assertEquals(BasemapSourceKind.VECTOR, vector.kind)
        assertEquals(listOf("https://styles.example/maps/v/{z}/{x}/{y}.pbf"), vector.tileTemplates)
        assertEquals(BasemapTileScheme.XYZ, vector.scheme)
        assertEquals(0, vector.minZoom)
        // A vector source's declared maxzoom is clamped by the *vector* default of 22, not 30.
        assertEquals(22, vector.maxZoom)
        assertNull(vector.tileSizePixels)

        val raster = manifest.sources.single { it.sourceId == "r" }
        assertEquals(BasemapSourceKind.RASTER, raster.kind)
        assertEquals(25, raster.maxZoom)
        assertEquals(256, raster.tileSizePixels)

        val dem = manifest.sources.single { it.sourceId == "dem" }
        assertEquals(BasemapSourceKind.RASTER_DEM, dem.kind)
        assertEquals(30, dem.maxZoom)
        assertEquals(64, dem.tileSizePixels)

        val geoJson = manifest.sources.single { it.sourceId == "g" }
        assertEquals(BasemapSourceKind.GEO_JSON, geoJson.kind)
        assertEquals("https://styles.example/maps/data/points.geojson", geoJson.geoJsonReference)
        assertEquals(emptyList(), geoJson.tileTemplates)
    }

    @Test
    fun defaultsSchemeToXyzZoomsToTheirSourceKindAndRasterTileSizeTo512() {
        val manifest = manifestOf(
            """{"version":8,"sources":{""" +
                """"v":{"type":"vector","tiles":["v/{z}/{x}/{y}.pbf"]},""" +
                """"r":{"type":"raster","tiles":["r/{z}/{x}/{y}.png"],"scheme":"tms"}},""" +
                """"layers":[]}""",
        )
        val vector = manifest.sources.single { it.sourceId == "v" }
        assertEquals(BasemapTileScheme.XYZ, vector.scheme)
        assertEquals(0, vector.minZoom)
        assertEquals(22, vector.maxZoom)

        val raster = manifest.sources.single { it.sourceId == "r" }
        assertEquals(BasemapTileScheme.TMS, raster.scheme)
        assertEquals(30, raster.maxZoom)
        assertEquals(512, raster.tileSizePixels)
    }

    @Test
    fun ignoresSourceKindsRengNeverRoutes() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"i":{"type":"image","url":"https://img.example/a.png"}},"layers":[]}""",
        )
        assertEquals(emptyList(), manifest.sources)
        // An unrouted source type is not a defect: Rentile never fetches one either, so nothing is
        // recorded against it.
        assertEquals(emptyList(), manifest.underivableSources)
        assertEquals(emptyList(), tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS))
        assertEquals(emptyList(), styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS))
    }

    @Test
    fun acceptsAStringSpelledStyleVersionExactlyAsRentileDoes() {
        // Rentile reads `version` through kotlinx `intOrNull`, which parses a *string* primitive's
        // content. Reproducing that -- rather than requiring a JSON integer -- is the point.
        val manifest = manifestOf("""{"version":"8","sources":{},"layers":[]}""")
        assertEquals(emptyList(), manifest.sources)
    }

    @Test
    fun rejectsAStyleVersionOtherThanEight() {
        assertRejected(BasemapStyleReject.STYLE_VERSION_UNSUPPORTED, """{"version":7,"sources":{},"layers":[]}""")
        assertRejected(BasemapStyleReject.STYLE_VERSION_UNSUPPORTED, """{"version":8.0,"sources":{},"layers":[]}""")
        assertRejected(BasemapStyleReject.STYLE_VERSION_UNSUPPORTED, """{"sources":{},"layers":[]}""")
    }

    @Test
    fun rejectsMalformedStyleJsonAndANonObjectRoot() {
        assertRejected(BasemapStyleReject.STYLE_JSON_MALFORMED, """{"version":8,""")
        assertRejected(BasemapStyleReject.STYLE_ROOT_NOT_OBJECT, """[1,2,3]""")
    }

    @Test
    fun rejectsADuplicateMemberNameWithItsOwnCodeEvenThoughRentileWouldAcceptIt() {
        val outcome = assertRejected(
            BasemapStyleReject.STYLE_JSON_DUPLICATE_MEMBER_NAME,
            """{"version":8,"version":8,"sources":{},"layers":[]}""",
        )
        assertEquals(StyleFailureKind.PARSE, outcome.kind)
    }

    @Test
    fun routesTheTileJsonDocumentForEveryTileSourceKindThatNamesOne() {
        // The `url` form contributes a style-time document route and no tile route until that document
        // arrives; `BasemapTileJsonSourceTest` owns the whole of what happens once it does.
        for (type in listOf("vector", "raster", "raster-dem")) {
            val manifest = manifestOf(
                """{"version":8,"sources":{"s":{"type":"$type","url":"https://tiles.example/s.json"}},"layers":[]}""",
            )
            assertEquals(emptyList(), manifest.underivableSources)
            assertEquals(emptyList(), manifest.sources)
            assertEquals(listOf("s"), manifest.tileJsonSources.map { it.sourceId })
            assertEquals(emptyList(), tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS))
            assertEquals(
                listOf("https://tiles.example/s.json"),
                styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
            )
        }
    }

    @Test
    fun recordsASourceThatDeclaresBothUrlAndTilesAsUnderivable() {
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_DECLARES_URL_AND_TILES,
            """{"version":8,"sources":{"s":{"type":"raster","url":"https://t.example/s.json",""" +
                """"tiles":["https://t.example/{z}/{x}/{y}.png"]}},"layers":[]}""",
        )
    }

    @Test
    fun leavesANonStringUrlMemberAbsentExactlyAsRentileDoes() {
        // Rentile reads the reference as a *string* primitive, so `"url": null` is simply absent to it
        // and the inline templates are used.
        val manifest = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster","url":null,""" +
                """"tiles":["https://tiles.example/r/{z}/{x}/{y}.png"]}},"layers":[]}""",
        )
        assertEquals(emptyList(), manifest.underivableSources)
        assertEquals(
            listOf("https://tiles.example/r/2/1/1.png"),
            tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
    }

    @Test
    fun recordsEverySourceItCannotComposeAnExactUrlForWithoutFailingTheStyle() {
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_TILES_EMPTY,
            """{"version":8,"sources":{"s":{"type":"raster","tiles":[]}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_TILES_EMPTY,
            """{"version":8,"sources":{"s":{"type":"raster"}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_TILES_NOT_STRINGS,
            """{"version":8,"sources":{"s":{"type":"raster","tiles":[7]}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_SCHEME_UNSUPPORTED,
            """{"version":8,"sources":{"s":{"type":"raster","tiles":["a/{z}.png"],"scheme":"quad"}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_ZOOM_NOT_INTEGER,
            """{"version":8,"sources":{"s":{"type":"raster","tiles":["a/{z}.png"],"minzoom":1.5}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_ZOOM_RANGE_INVALID,
            """{"version":8,"sources":{"s":{"type":"vector","tiles":["a/{z}.pbf"],"minzoom":25}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_TILE_SIZE_NOT_INTEGER,
            """{"version":8,"sources":{"s":{"type":"raster","tiles":["a/{z}.png"],"tileSize":"big"}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_TILE_SIZE_UNSUPPORTED,
            """{"version":8,"sources":{"s":{"type":"raster","tiles":["a/{z}.png"],"tileSize":1024}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.GEO_JSON_DATA_NOT_STRING,
            """{"version":8,"sources":{"s":{"type":"geojson","data":{"type":"FeatureCollection"}}},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_NOT_OBJECT,
            """{"version":8,"sources":{"s":7},"layers":[]}""",
        )
        assertUnderivable(
            BasemapSourceUnderivableReason.SOURCE_REFERENCE_UNRESOLVABLE,
            """{"version":8,"sources":{"s":{"type":"raster","tiles":["a/{z}.png"]}},"layers":[]}""",
            baseUri = "styles.example/basic.json",
        )
    }

    @Test
    fun keepsRoutingEverySiblingSourceWhenOneIsUnderivable() {
        val manifest = manifestOf(
            """{"version":8,"sprite":"sprites/basic","sources":{""" +
                """"bad":{"type":"vector","tiles":[7]},""" +
                """"r":{"type":"raster","tiles":["https://tiles.example/r/{z}/{x}/{y}.png"]},""" +
                """"g":{"type":"geojson","data":"data/points.geojson"}},"layers":[]}""",
        )
        assertEquals(
            listOf(UnderivableBasemapSource("bad", BasemapSourceUnderivableReason.SOURCE_TILES_NOT_STRINGS)),
            manifest.underivableSources,
        )
        assertEquals(listOf("r", "g"), manifest.sources.map { it.sourceId })
        assertEquals(
            listOf("https://tiles.example/r/2/1/1.png"),
            tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
        assertEquals(
            listOf(
                "https://styles.example/maps/sprites/basic.json",
                "https://styles.example/maps/sprites/basic.png",
                "https://styles.example/maps/data/points.geojson",
            ),
            styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
    }

    @Test
    fun rejectsOnlyTheDocumentLevelFaultsThatLeaveNoRouteDerivableAtAll() {
        assertRejected(BasemapStyleReject.SOURCES_NOT_OBJECT, """{"version":8,"sources":[],"layers":[]}""")
        assertRejected(
            BasemapStyleReject.TERRAIN_NOT_OBJECT,
            """{"version":8,"terrain":7,"sources":{},"layers":[]}""",
        )
        // Not a divergence: StyleCompiler.compileTerrainSource throws on both of these unconditionally.
        assertRejected(
            BasemapStyleReject.TERRAIN_SOURCE_NOT_STRING,
            """{"version":8,"terrain":{"exaggeration":1.0},"sources":{},"layers":[]}""",
        )
    }

    @Test
    fun leavesAnArrayFormOrUnresolvableSpriteWithNoRoutesRatherThanFailing() {
        val arrayForm = manifestOf("""{"version":8,"sprite":[{"id":"a","url":"https://s.example/a"}],"sources":{}}""")
        assertNull(arrayForm.spriteBase)
        assertEquals(emptyList(), styleTimeRoutes(arrayForm, ResourceAccessMode.NORMAL, LIMITS))

        val unresolvable = manifestOf(
            """{"version":8,"sprite":"sprites/basic","sources":{}}""",
            baseUri = "styles.example/basic.json",
        )
        assertNull(unresolvable.spriteBase)
        assertEquals(emptyList(), styleTimeRoutes(unresolvable, ResourceAccessMode.NORMAL, LIMITS))
    }

    /**
     * The style's `glyphs` template, which RenG needs its own resolved copy of: `glyphUrls` substitutes
     * the template *the caller hands it*, so the credential that reaches RenG's own transport is the one
     * in this string rather than the one Rentile compiled privately.
     *
     * The relative case is the one that discriminates -- a raw pass-through of `root["glyphs"]` would
     * satisfy every absolute and every `null` assertion here and fail only this one. The absolute case
     * uses a different origin from the style's for the mirror-image reason: re-resolving it against the
     * base would rewrite the host.
     *
     * The placeholders stay unsubstituted on purpose. RenG never composes a glyph url itself; Rentile's
     * `glyphUrls` does, from this template, which is what makes RenG's preregistered strings match the
     * engine's requests exactly.
     */
    @Test
    fun readsAndResolvesTheGlyphTemplateWithoutSubstitutingItsPlaceholders() {
        val relative = manifestOf(
            """{"version":8,"glyphs":"fonts/{fontstack}/{range}.pbf","sources":{},"layers":[]}""",
        )
        assertEquals(
            "https://styles.example/maps/fonts/{fontstack}/{range}.pbf",
            relative.glyphTemplate,
        )
        // Glyph routes are not style-time routes: they are preregistered from the label handover, after
        // `planLabelCandidates` names the exact urls. A style-time glyph route here would be RenG
        // guessing at a font stack.
        assertEquals(emptyList(), styleTimeRoutes(relative, ResourceAccessMode.NORMAL, LIMITS))

        val absolute = manifestOf(
            """{"version":8,"glyphs":"https://fonts.example/f/{fontstack}/{range}.pbf?key=K",""" +
                """"sources":{},"layers":[]}""",
        )
        assertEquals(
            "https://fonts.example/f/{fontstack}/{range}.pbf?key=K",
            absolute.glyphTemplate,
        )

        assertNull(manifestOf("""{"version":8,"sources":{},"layers":[]}""").glyphTemplate)
        assertNull(
            manifestOf("""{"version":8,"glyphs":["a"],"sources":{},"layers":[]}""").glyphTemplate,
            "only the string form resolves, exactly as for `sprite`",
        )
        assertNull(
            manifestOf(
                """{"version":8,"glyphs":"fonts/{fontstack}/{range}.pbf","sources":{}}""",
                baseUri = "styles.example/basic.json",
            ).glyphTemplate,
            "an unresolvable relative reference leaves no template rather than composing a url",
        )
    }

    @Test
    fun carriesTheGlyphTemplateThroughTileJsonCompletion() {
        // completeBasemapStyleManifest rebuilds the manifest field by field, so a field it forgets to
        // carry silently becomes null the moment any source is TileJSON-backed -- which is most styles.
        // The fixture must therefore declare a `url`-form source: with none pending, completion returns
        // the same manifest by identity and this test would pass with the carried field deleted.
        val manifest = manifestOf(
            """{"version":8,"glyphs":"fonts/{fontstack}/{range}.pbf",""" +
                """"sources":{"s":{"type":"vector","url":"https://tiles.example/s.json"}},"layers":[]}""",
        )
        assertTrue(
            manifest.tileJsonSources.isNotEmpty(),
            "the fixture must have a document still pending, or completion short-circuits and this " +
                "proves nothing",
        )

        val completed = completeBasemapStyleManifest(manifest, emptyMap())

        assertNotNull(completed.glyphTemplate)
        assertEquals(manifest.glyphTemplate, completed.glyphTemplate)
    }

    // ---- style-time routes ---------------------------------------------------------------------

    @Test
    fun composesTheSpritePairAndEveryGeoJsonSourceAtStyleTime() {
        val manifest = manifestOf(
            """{"version":8,"sprite":"sprites/basic","sources":{""" +
                """"g":{"type":"geojson","data":"data/points.geojson"},""" +
                """"h":{"type":"geojson","data":"https://geo.example/lines.geojson"},""" +
                """"r":{"type":"raster","tiles":["r/{z}/{x}/{y}.png"]}},"layers":[]}""",
        )
        val routes = styleTimeRoutes(manifest, ResourceAccessMode.RELOAD, LIMITS)

        assertEquals(
            listOf(
                "https://styles.example/maps/sprites/basic.json",
                "https://styles.example/maps/sprites/basic.png",
                "https://styles.example/maps/data/points.geojson",
                "https://geo.example/lines.geojson",
            ),
            routes.map { it.locator.value },
        )
        assertEquals(
            listOf(
                ResourceClass.BASEMAP_SPRITE_JSON,
                ResourceClass.BASEMAP_SPRITE_IMAGE,
                ResourceClass.BASEMAP_GEO_JSON,
                ResourceClass.BASEMAP_GEO_JSON,
            ),
            routes.map { it.resourceClass },
        )
        assertTrue(routes.all { it.accessMode == ResourceAccessMode.RELOAD })
        assertEquals(LIMITS.maximumBasemapMetadataBytes, routes[0].maximumResponseBytes)
        assertEquals(LIMITS.maximumBasemapSpriteImageBytes, routes[1].maximumResponseBytes)
        assertEquals(LIMITS.maximumBasemapGeoJsonBytes, routes[2].maximumResponseBytes)
    }

    @Test
    fun composesNoTileJsonRouteBecauseTheUrlFormIsOutOfScope() {
        val manifest = manifestOf(SINGLE_RASTER_STYLE)
        assertTrue(
            styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS).none {
                it.resourceClass == ResourceClass.BASEMAP_TILE_JSON
            },
        )
    }

    // ---- tile-time routes ----------------------------------------------------------------------

    @Test
    fun composesOneRasterTileUrlPerTile() {
        val manifest = manifestOf(SINGLE_RASTER_STYLE)
        val routes = tileTimeRoutes(
            manifest,
            listOf(CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5), TILE_2_1_1),
            ResourceAccessMode.NORMAL,
            LIMITS,
        )
        assertEquals(
            listOf("https://tiles.example/r/3/5/2.png", "https://tiles.example/r/2/1/1.png"),
            routes.map { it.locator.value },
        )
        assertTrue(routes.all { it.resourceClass == ResourceClass.BASEMAP_RASTER_TILE })
        assertTrue(routes.all { it.maximumResponseBytes == LIMITS.maximumBasemapTileBytes })
    }

    @Test
    fun composesVectorTileUrlsWithTheirOwnResourceClass() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"v":{"type":"vector",""" +
                """"tiles":["https://tiles.example/v/{z}/{x}/{y}.pbf"]}},"layers":[]}""",
        )
        val routes = tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS)
        assertEquals(listOf("https://tiles.example/v/2/1/1.pbf"), routes.map { it.locator.value })
        assertEquals(listOf(ResourceClass.BASEMAP_VECTOR_TILE), routes.map { it.resourceClass })
    }

    @Test
    fun clampsTheUrlZoomToTheSourceMaxzoomRatherThanTheSelectedLod() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster",""" +
                """"tiles":["https://tiles.example/r/{z}/{x}/{y}.png"],"maxzoom":2}},"layers":[]}""",
        )
        val routes = tileTimeRoutes(
            manifest,
            listOf(CanonicalBasemapTile(lod = 5, tileY = 7, canonicalX = 13)),
            ResourceAccessMode.NORMAL,
            LIMITS,
        )
        // sourceZ = min(5, 2) = 2, childScale = 8, so x = 13 / 8 = 1 and y = 7 / 8 = 0.
        assertEquals(listOf("https://tiles.example/r/2/1/0.png"), routes.map { it.locator.value })
    }

    @Test
    fun composesNoRouteBelowTheSourceMinzoom() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster",""" +
                """"tiles":["https://tiles.example/r/{z}/{x}/{y}.png"],"minzoom":4}},"layers":[]}""",
        )
        assertEquals(
            emptyList(),
            tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
        assertEquals(
            listOf("https://tiles.example/r/4/1/1.png"),
            tileTimeRoutes(
                manifest,
                listOf(CanonicalBasemapTile(lod = 4, tileY = 1, canonicalX = 1)),
                ResourceAccessMode.NORMAL,
                LIMITS,
            ).map { it.locator.value },
        )
    }

    @Test
    fun picksTheTemplateWithRentilesOwnHashRoundRobin() {
        val manifest = manifestOf(MULTI_TEMPLATE_STYLE)
        val tiles = listOf(
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 1),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 2),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 3),
            CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 0),
            CanonicalBasemapTile(lod = 2, tileY = 2, canonicalX = 0),
            CanonicalBasemapTile(lod = 3, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0),
        )
        // index = floorMod(z * 31 + x * 17 + y, 5)
        assertEquals(
            listOf(
                "https://c.example/2/0/0.png",
                "https://e.example/2/1/0.png",
                "https://b.example/2/2/0.png",
                "https://d.example/2/3/0.png",
                "https://d.example/2/0/1.png",
                "https://e.example/2/0/2.png",
                "https://d.example/3/0/0.png",
                "https://b.example/1/0/0.png",
            ),
            tileTimeRoutes(manifest, tiles, ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
    }

    @Test
    fun picksTheTemplateWithTheSameHashOverAThreeTemplateSource() {
        // The five-template case above pins the two constants only modulo 5. A coprime template count
        // raises that to 15 -- but a single-constant change of any multiple of 15 still escapes both,
        // so a seven-template source below raises it again to 105.
        val manifest = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster","tiles":[""" +
                """"https://a.example/{z}/{x}/{y}.png",""" +
                """"https://b.example/{z}/{x}/{y}.png",""" +
                """"https://c.example/{z}/{x}/{y}.png"]}},"layers":[]}""",
        )
        val tiles = listOf(
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 1),
            CanonicalBasemapTile(lod = 3, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 4, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 2),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 3),
        )
        // index = floorMod(z * 31 + x * 17 + y, 3)
        assertEquals(
            listOf(
                "https://c.example/2/0/0.png",
                "https://b.example/2/1/0.png",
                "https://a.example/3/0/0.png",
                "https://b.example/4/0/0.png",
                "https://a.example/2/2/0.png",
                "https://c.example/2/3/0.png",
            ),
            tileTimeRoutes(manifest, tiles, ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
    }

    @Test
    fun picksTheTemplateWithTheSameHashOverASevenTemplateSource() {
        // Three sources with pairwise-coprime template counts (5, 3, 7) pin both constants modulo 105:
        // every one of z = 1, 2, 3, 4 and x = 1 appears, so a change to the 31 term or the 17 term
        // survives only if it is a multiple of 3, 5 and 7 at once. A single-constant change of 15 --
        // which the five- and three-template cases alone would have missed -- is caught here.
        val manifest = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster","tiles":[""" +
                """"https://a.example/{z}/{x}/{y}.png",""" +
                """"https://b.example/{z}/{x}/{y}.png",""" +
                """"https://c.example/{z}/{x}/{y}.png",""" +
                """"https://d.example/{z}/{x}/{y}.png",""" +
                """"https://e.example/{z}/{x}/{y}.png",""" +
                """"https://f.example/{z}/{x}/{y}.png",""" +
                """"https://g.example/{z}/{x}/{y}.png"]}},"layers":[]}""",
        )
        val tiles = listOf(
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 1),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 2),
            CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 3),
            CanonicalBasemapTile(lod = 3, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 4, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0),
            CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 0),
        )
        // index = floorMod(z * 31 + x * 17 + y, 7)
        assertEquals(
            listOf(
                "https://g.example/2/0/0.png",
                "https://c.example/2/1/0.png",
                "https://f.example/2/2/0.png",
                "https://b.example/2/3/0.png",
                "https://c.example/3/0/0.png",
                "https://f.example/4/0/0.png",
                "https://d.example/1/0/0.png",
                "https://a.example/2/0/1.png",
            ),
            tileTimeRoutes(manifest, tiles, ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
    }

    @Test
    fun substitutesTheNegativeYPlaceholderIndependentlyOfTheTmsFlip() {
        val xyz = manifestOf(FLIP_STYLE_XYZ)
        assertEquals(
            listOf("https://tiles.example/2/1/1-2.png"),
            tileTimeRoutes(xyz, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )

        val tms = manifestOf(FLIP_STYLE_TMS)
        assertEquals(
            listOf("https://tiles.example/2/1/2-2.png"),
            tileTimeRoutes(tms, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS).map { it.locator.value },
        )
    }

    @Test
    fun expandsEveryDemSourceOverItsEightNeighbours() {
        val manifest = manifestOf(SINGLE_DEM_STYLE)
        val routes = tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS)
        assertEquals(
            listOf(
                "https://tiles.example/d/2/0/0.png",
                "https://tiles.example/d/2/1/0.png",
                "https://tiles.example/d/2/2/0.png",
                "https://tiles.example/d/2/0/1.png",
                "https://tiles.example/d/2/1/1.png",
                "https://tiles.example/d/2/2/1.png",
                "https://tiles.example/d/2/0/2.png",
                "https://tiles.example/d/2/1/2.png",
                "https://tiles.example/d/2/2/2.png",
            ),
            routes.map { it.locator.value },
        )
        assertTrue(routes.all { it.resourceClass == ResourceClass.BASEMAP_DEM_TILE })
    }

    @Test
    fun wrapsDemNeighboursInXAndClipsThemInY() {
        val manifest = manifestOf(SINGLE_DEM_STYLE)
        val routes = tileTimeRoutes(
            manifest,
            listOf(CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 0)),
            ResourceAccessMode.NORMAL,
            LIMITS,
        )
        // The row above y = 0 does not exist, and x = -1 wraps to x = 3 at z = 2.
        assertEquals(
            listOf(
                "https://tiles.example/d/2/3/0.png",
                "https://tiles.example/d/2/0/0.png",
                "https://tiles.example/d/2/1/0.png",
                "https://tiles.example/d/2/3/1.png",
                "https://tiles.example/d/2/0/1.png",
                "https://tiles.example/d/2/1/1.png",
            ),
            routes.map { it.locator.value },
        )
    }

    @Test
    fun composesNoTileRouteForAGeoJsonSource() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"g":{"type":"geojson","data":"data/points.geojson"}},"layers":[]}""",
        )
        assertEquals(emptyList(), tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS))
    }

    @Test
    fun collapsesTilesThatClampOntoTheSameSourceTileIntoOneRoute() {
        val manifest = manifestOf(
            """{"version":8,"sources":{"r":{"type":"raster",""" +
                """"tiles":["https://tiles.example/r/{z}/{x}/{y}.png"],"maxzoom":1}},"layers":[]}""",
        )
        val routes = tileTimeRoutes(
            manifest,
            listOf(
                CanonicalBasemapTile(lod = 3, tileY = 0, canonicalX = 0),
                CanonicalBasemapTile(lod = 3, tileY = 1, canonicalX = 1),
                CanonicalBasemapTile(lod = 3, tileY = 0, canonicalX = 7),
            ),
            ResourceAccessMode.NORMAL,
            LIMITS,
        )
        assertEquals(
            listOf("https://tiles.example/r/1/0/0.png", "https://tiles.example/r/1/1/0.png"),
            routes.map { it.locator.value },
        )
    }

    @Test
    fun carriesTheRequestedAccessModeOntoEveryTileRoute() {
        val manifest = manifestOf(SINGLE_RASTER_STYLE)
        val routes = tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.CACHE_ONLY, LIMITS)
        assertTrue(routes.isNotEmpty())
        assertTrue(routes.all { it.accessMode == ResourceAccessMode.CACHE_ONLY })
    }

    // ---- sampling primitives -------------------------------------------------------------------

    @Test
    fun samplesAndComposesThroughTheSameDerivationTheRouteBuilderUses() {
        val source = manifestOf(SINGLE_RASTER_STYLE).sources.single()
        val sample = assertNotNull(basemapTileSampleFor(source, TILE_2_1_1))
        assertEquals(BasemapTileSample(sourceZ = 2, sourceX = 1, sourceY = 1), sample)
        assertEquals("https://tiles.example/r/2/1/1.png", basemapTileUrl(source, sample))
        assertEquals(
            BasemapTileSample(sourceZ = 2, sourceX = 0, sourceY = 2),
            basemapTileSampleNeighbour(sample, deltaX = -1, deltaY = 1),
        )
        assertNull(basemapTileSampleNeighbour(BasemapTileSample(2, 1, 3), deltaX = 0, deltaY = 1))
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun manifestOf(styleJson: String, baseUri: String = STYLE_BASE_URI): BasemapStyleManifest {
        val outcome = deriveBasemapStyleManifest(styleJson.encodeToByteArray(), baseUri)
        return assertIs<BasemapStyleManifestOutcome.Derived>(outcome).manifest
    }

    private fun assertUnderivable(
        reason: BasemapSourceUnderivableReason,
        styleJson: String,
        baseUri: String = STYLE_BASE_URI,
        sourceId: String = "s",
    ) {
        val manifest = manifestOf(styleJson, baseUri)
        assertEquals(listOf(UnderivableBasemapSource(sourceId, reason)), manifest.underivableSources)
        assertEquals(emptyList(), manifest.sources)
        assertEquals(emptyList(), tileTimeRoutes(manifest, listOf(TILE_2_1_1), ResourceAccessMode.NORMAL, LIMITS))
        assertEquals(emptyList(), styleTimeRoutes(manifest, ResourceAccessMode.NORMAL, LIMITS))
    }

    private fun assertRejected(
        reason: BasemapStyleReject,
        styleJson: String,
        baseUri: String = STYLE_BASE_URI,
    ): BasemapStyleManifestOutcome.Rejected {
        val rejected = assertIs<BasemapStyleManifestOutcome.Rejected>(
            deriveBasemapStyleManifest(styleJson.encodeToByteArray(), baseUri),
        )
        assertEquals(reason, rejected.reason)
        return rejected
    }
}

private const val STYLE_BASE_URI: String = "https://styles.example/maps/basic.json"

private val LIMITS: ResourceLimits = ResourceLimits()

private val TILE_2_1_1: CanonicalBasemapTile = CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 1)

private const val SINGLE_RASTER_STYLE: String =
    """{"version":8,"sources":{"r":{"type":"raster","tiles":["https://tiles.example/r/{z}/{x}/{y}.png"]}},""" +
        """"layers":[]}"""

private const val SINGLE_DEM_STYLE: String =
    """{"version":8,"sources":{"d":{"type":"raster-dem","tiles":["https://tiles.example/d/{z}/{x}/{y}.png"]}},""" +
        """"layers":[]}"""

private const val MULTI_TEMPLATE_STYLE: String =
    """{"version":8,"sources":{"r":{"type":"raster","tiles":[""" +
        """"https://a.example/{z}/{x}/{y}.png",""" +
        """"https://b.example/{z}/{x}/{y}.png",""" +
        """"https://c.example/{z}/{x}/{y}.png",""" +
        """"https://d.example/{z}/{x}/{y}.png",""" +
        """"https://e.example/{z}/{x}/{y}.png"]}},"layers":[]}"""

private const val FLIP_STYLE_XYZ: String =
    """{"version":8,"sources":{"r":{"type":"raster",""" +
        """"tiles":["https://tiles.example/{z}/{x}/{y}-{-y}.png"]}},"layers":[]}"""

private const val FLIP_STYLE_TMS: String =
    """{"version":8,"sources":{"r":{"type":"raster","scheme":"tms",""" +
        """"tiles":["https://tiles.example/{z}/{x}/{y}-{-y}.png"]}},"layers":[]}"""
