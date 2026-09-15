package com.rohittp.reng.internal.firewall

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.ConcurrentRecorder
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.basemap.BasemapStyleManifestOutcome
import com.rohittp.reng.internal.basemap.BasemapStyleReject
import com.rohittp.reng.internal.basemap.BasemapSourceUnderivableReason
import com.rohittp.reng.internal.basemap.BasemapTileJsonOutcome
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.GpuByteAccount
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.ResourceRouteKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest

/**
 * The first tests in RenG that construct a real Rentile engine. Everything Skia-free lives here, so it
 * runs on every executable target; the rasterizing half — which needs Skia's native library, absent from
 * this project's `androidHostTest` runtime — lives in `nativeTest`'s `BasemapEngineRenderTest`.
 */
class BasemapEngineHostTest {

    // ---- setup purity ---------------------------------------------------------------------------

    @Test
    fun createsTheRasterizerAtSetupWithoutSuspendingOrPerformingIo() {
        val transport = CountingHostTransport()
        val store = CountingHostStore()
        // Deliberately NOT inside runTest: this constructor is not `suspend`, and a real engine must be
        // built by the time it returns without a single consumer exchange.
        val host = basemapEngineHost(transport, store)
        assertEquals(0, transport.executeCalls, "setup performs no Transport call")
        assertEquals(0, store.readCalls + store.writeCalls, "setup performs no Store call")
        assertFalse(host.isClosed)
        host.close()
    }

    @Test
    fun closesIdempotently() {
        val host = basemapEngineHost()
        host.close()
        host.close()
        assertTrue(host.isClosed)
    }

    @Test
    fun refusesFurtherWorkOnceClosed() = runTest {
        val host = basemapEngineHost()
        val style = host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
            host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
        }
        host.close()
        // Reported as the engine states BASEMAP_RENDER_FAILED already carries: "the ground did not draw",
        // reachable only if RenG mismanaged a handle it owns.
        val failure = assertFailsWith<RenGException> {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.prepareTiles(style, listOf(HOST_RASTER_TILE))
            }
        }
        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, failure.code)
        assertFailsWith<RenGException> {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
        }
    }

    // ---- rendered-tile identity ------------------------------------------------------------------

    @Test
    fun derivesTileIdentityFromRenGsOwnCanonicalRootNotTheEnginesKey() {
        val tile = CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 1)
        val baseline = basemapTileKey(HOST_STYLE_DIGEST_A, tile, HOST_TILE_OUTPUT_SIZE)

        assertEquals(ResourceKind.BASEMAP_TILE, baseline.kind)
        assertNull(baseline.resourceClass, "a rendered tile is not an external resource class")

        // Every component independently keys the tile: change exactly one and nothing else.
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_B, tile, HOST_TILE_OUTPUT_SIZE),
            "style digest",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile.copy(lod = 3), HOST_TILE_OUTPUT_SIZE),
            "lod",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile.copy(tileY = 2), HOST_TILE_OUTPUT_SIZE),
            "tileY",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile.copy(canonicalX = 0), HOST_TILE_OUTPUT_SIZE),
            "canonicalX",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile, OutputPixelSize(256, 512)),
            "output width",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile, OutputPixelSize(512, 256)),
            "output height",
        )
        assertEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile, HOST_TILE_OUTPUT_SIZE),
            "derivation is pure",
        )
    }

    @Test
    fun sharesOneRenderedTileAcrossEveryWorldCopyOfTheSameCanonicalTile() {
        // BasemapTileSelector emits instances (which carry unwrappedX and instanceCopy) separately from
        // canonicalResources, so a CanonicalBasemapTile is already post-world-copy-dedup. Two draw
        // instances of the same tile in different Mercator world copies must therefore resolve to one
        // rendered-tile resource and one engine render, never N.
        val first = BasemapTileInstance(lod = 2, tileY = 1, unwrappedX = 1L, instanceCopy = 0, canonicalX = 1)
        val second = BasemapTileInstance(lod = 2, tileY = 1, unwrappedX = 5L, instanceCopy = 1, canonicalX = 1)
        assertNotEquals(first, second, "the fixture must genuinely differ in its world copy")

        // Through the production instance overload, not a test-local projection: the world-copy dedup has
        // to live in shipped code for this to be a claim about the renderer rather than about the test.
        assertEquals(
            basemapTileKey(HOST_STYLE_DIGEST_A, first, HOST_TILE_OUTPUT_SIZE),
            basemapTileKey(HOST_STYLE_DIGEST_A, second, HOST_TILE_OUTPUT_SIZE),
        )
        assertEquals(
            basemapTileKey(HOST_STYLE_DIGEST_A, CanonicalBasemapTile(2, 1, 1), HOST_TILE_OUTPUT_SIZE),
            basemapTileKey(HOST_STYLE_DIGEST_A, first, HOST_TILE_OUTPUT_SIZE),
            "an instance keys the very same rendered tile its canonical tile does",
        )
    }

    // ---- style compilation -----------------------------------------------------------------------

    @Test
    fun compilesThePreparedStyleLazilyAndReusesItWhileResident() = runTest {
        val transport = CountingHostTransport()
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                assertEquals(0, transport.executeCalls, "opening an operation performs no exchange")
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertSame(first, second, "a resident style compiles once")
                assertEquals(
                    0,
                    transport.executeCalls,
                    "an inline-source style hands RenG's own bytes to the engine and fetches nothing",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * "Accessing a freed resource reloads it" is a claim about **residency**, not about compilation. A
     * freed style is genuinely reinstalled and re-leased here; its compilation is reused because the
     * bytes are identical, and recompiling identical bytes would re-run the engine's whole sprite,
     * TileJSON and GeoJSON acquisition for no result the caller could distinguish.
     */
    @Test
    fun reloadsAFreedStyleGenerationWithoutRecompilingIdenticalBytes() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertNotNullGeneration(cache)
                cache.free(ResourceSelector.ByKey(hostStyleKey))
                assertNull(cache.current(hostStyleKey), "the fixture must genuinely free the generation")

                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)

                assertSame(first, second, "identical bytes are never recompiled")
                assertNotNullGeneration(cache)
            }
        } finally {
            host.close()
        }
    }

    /**
     * The defect this binding exists to close, at its smallest reproducible size. Compilation runs
     * strictly before the style's own visibility install, and installing always retires and replaces the
     * current generation — so a compilation bound to a generation *object* misses on the very next
     * lookup and recompiles, once per frame, forever. Bound to content, it survives.
     */
    @Test
    fun keepsTheCompilationAcrossAFreshGenerationOfIdenticalBytes() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val compiledGeneration = cache.current(hostStyleKey)

                // Exactly what ResourceActionExecutor.installVisibility does after CompileBasemapStyle.
                cache.installAndTakeLease(hostStyleKey, hostStyleRecord(), decoded = null)
                assertNotSame(
                    compiledGeneration,
                    cache.current(hostStyleKey),
                    "the fixture must genuinely replace the generation the compilation observed",
                )

                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)

                assertSame(first, second, "a fresh generation of identical bytes reuses the compilation")
                assertSame(
                    host.currentPreparedStyle(hostStyleKey, hostStyleRecord().contentDigest),
                    second,
                    "the host retains exactly what it last compiled",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * The ordinary shape of an edit as the driver performs it end to end: a transport re-fetch, the
     * visibility install, and a compile of the same new bytes. What recompiles is the **content the
     * caller commits** — the install beside it here is the driver's, reproduced so this reads as a whole
     * frame rather than as an isolated call, and is deliberately *not* what the recompilation keys off.
     * The test twenty lines above compiles edited bytes with nothing installed at all, and recompiles
     * just the same; see [BasemapEngineHost.preparedStyle] for why the two cannot be swapped.
     */
    @Test
    fun recompilesWhenTheCommittedStyleContentItselfChanges() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val edited = HOST_STYLE_JSON.replace("reng-basemap-host-test", "reng-basemap-host-test-2")
                cache.installAndTakeLease(hostStyleKey, hostStyleRecord(edited), decoded = null)
                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(edited), HOST_STYLE_BASE_URI)
                assertNotSame(first, second, "different bytes are a different style")
            }
        } finally {
            host.close()
        }
    }

    /**
     * The compilation is of **the content the caller is committing**, not of whatever generation happens
     * to be resident when the call arrives — the two are the same document on most frames and are
     * genuinely different on exactly the frame a consumer edits their style.
     *
     * The ordering that makes them differ is the pure core's, and it is deliberate: `CompileBasemapStyle`
     * is emitted before `InstallBasemapStyleVisibility`, because a style that fails to compile must not
     * become visible. So at this point the resident generation still carries the *previous* document —
     * reproduced literally below, by leaving the first document installed and calling with the second.
     * Compiling the resident bytes there compiles a document the frame is not committing, while the
     * tile-time manifest (derived after the install) describes the one it is; the engine then asks for
     * the superseded style's urls and the firewall refuses every one of them.
     *
     * Keying on the caller's digest while still compiling the resident bytes would be worse than either:
     * see [BasemapEngineHost.preparedStyle]. The key and the compiled bytes move together.
     */
    @Test
    fun compilesTheContentTheCallerIsCommittingRatherThanTheGenerationResidentBeforeIt() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        val edited = hostStyleRecord(HOST_STYLE_JSON.replace(HOST_RASTER_TILE_TEMPLATE, HOST_EDITED_TILE_TEMPLATE))
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertEquals(
                    hostStyleRecord().contentDigest,
                    cache.current(hostStyleKey)?.stored?.contentDigest,
                    "the fixture must leave the first document resident, or it proves nothing",
                )
                assertNotEquals(hostStyleRecord().contentDigest, edited.contentDigest)

                val second = host.preparedStyle(hostStyleKey, edited, HOST_STYLE_BASE_URI)

                assertNotSame(first, second, "edited bytes are a different style")
                assertNotEquals(
                    first.digest,
                    second.digest,
                    "and what was compiled is the edited document, not the one still resident",
                )
                assertSame(
                    second,
                    host.currentPreparedStyle(hostStyleKey, edited.contentDigest),
                    "the host retains the compilation of the bytes being committed",
                )
                assertNull(
                    host.currentPreparedStyle(hostStyleKey, hostStyleRecord().contentDigest),
                    "and does not offer it for the document that is merely still resident",
                )
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun holdsNoPreparedStyleUntilOneIsCompiled() = runTest {
        val host = basemapEngineHost()
        try {
            assertNull(host.currentPreparedStyle(hostStyleKey, hostStyleRecord().contentDigest))
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
            // Retained across invocations on purpose: a RESIDENT-provenance frame emits no compile
            // action at all, so this readback is the renderer's only way to hold the frame's style.
            assertNotNull(host.currentPreparedStyle(hostStyleKey, hostStyleRecord().contentDigest))
            assertNull(
                host.currentPreparedStyle(hostStyleKey, contentDigest = null),
                "nothing resident under the key matches no compilation",
            )
        } finally {
            host.close()
        }
    }


    // ---- style manifest --------------------------------------------------------------------------

    /**
     * The other half of the per-frame style parse. `RenGRenderer.renderBasemapTiles` needs the very
     * manifest the driver's own `ValidateBasemapStyle` already derived from the very same bytes, and
     * re-deriving it re-runs `parseJson` over the *whole* document -- so a basemap frame parsed its style
     * twice (`internal.basemap.BasemapRouteDerivationCostTest` measures both halves). Bound to content
     * exactly as the compilation beside it is, so a second call over byte-identical content reads the
     * document once.
     */
    @Test
    fun derivesTheStyleManifestOnceAndReusesItWhileResident() = runTest {
        val host = basemapEngineHost()
        try {
            val first = hostManifest(host)
            val second = hostManifest(host)

            assertSame(first, second, "a resident style document is read exactly once")
            assertEquals(
                listOf(HOST_RASTER_TILE_TEMPLATE),
                first.sources.single().tileTemplates,
                "and the manifest is the one this style actually declares",
            )
        } finally {
            host.close()
        }
    }

    /**
     * The defect a single-frame test cannot see, at its smallest reproducible size -- and the exact shape
     * the compilation's own binding exists to close, since the two caches face the same hazard.
     *
     * A style the consumer's transport does not declare fresh is re-resolved on every frame, and the
     * driver's own visibility install retires the current generation and installs a fresh one carrying
     * identical bytes. A manifest bound to the resident *generation object* therefore misses on every
     * frame after the first and reparses the whole document forever, invisibly. Bound to content, it
     * survives the replacement.
     */
    @Test
    fun keepsTheManifestAcrossAFreshGenerationOfIdenticalBytes() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            val first = hostManifest(host)
            val observed = cache.current(hostStyleKey)

            // Exactly what ResourceActionExecutor.installVisibility does with a re-resolved style.
            cache.installAndTakeLease(hostStyleKey, hostStyleRecord(), decoded = null)
            assertNotSame(
                observed,
                cache.current(hostStyleKey),
                "the fixture must genuinely replace the generation the first derivation observed",
            )

            val second = hostManifest(host)

            assertSame(first, second, "a fresh generation of identical bytes reuses the manifest")
        } finally {
            host.close()
        }
    }

    /**
     * The resident generation is authoritative, so "the content changed" means a generation carrying
     * different bytes was installed. That, and only that, re-derives -- a manifest served for bytes it
     * was not derived from composes tile urls the engine never asks for, which the firewall reports as
     * `AMBIGUOUS_RESOURCE_ROUTE` on every tile at once rather than as a stale answer.
     */
    @Test
    fun rederivesTheManifestWhenTheInstalledStyleContentItselfChanges() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            val first = hostManifest(host)
            val edited = HOST_STYLE_JSON.replace(HOST_RASTER_TILE_TEMPLATE, HOST_EDITED_TILE_TEMPLATE)
            cache.installAndTakeLease(hostStyleKey, hostStyleRecord(edited), decoded = null)

            val second = hostManifest(host, hostStyleRecord(edited))

            assertNotSame(first, second, "different bytes are a different manifest")
            assertEquals(listOf(HOST_RASTER_TILE_TEMPLATE), first.sources.single().tileTemplates)
            assertEquals(
                listOf(HOST_EDITED_TILE_TEMPLATE),
                second.sources.single().tileTemplates,
                "the manifest describes the bytes that are resident now",
            )
        } finally {
            host.close()
        }
    }

    /**
     * One slot bound to a content digest, so two genuinely different style documents never share an
     * answer -- neither when they arrive under the same key (the configured style edited between frames)
     * nor when they arrive under different ones.
     */
    @Test
    fun neverServesOneStylesManifestForAnother() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            val raster = hostManifest(host)
            cache.installAndTakeLease(hostStyleKey, hostStyleRecord(PHASE_STYLE_JSON), decoded = null)
            val phase = hostManifest(host, hostStyleRecord(PHASE_STYLE_JSON))
            cache.installAndTakeLease(hostStyleKey, hostStyleRecord(), decoded = null)
            val rasterAgain = hostManifest(host)

            assertEquals(listOf("s"), raster.sources.map { it.sourceId })
            assertEquals(listOf("g", "r", "v", "d"), phase.sources.map { it.sourceId })
            assertEquals(PHASE_SPRITE_BASE_URL, phase.spriteBase, "the phase style is the one that has a sprite")
            assertNull(raster.spriteBase)
            assertEquals(
                listOf("s"),
                rasterAgain.sources.map { it.sourceId },
                "returning to the first document answers with the first document's manifest",
            )

            // The same holds across keys: this cache holds one style, and the other key's document is
            // read from that key's own resident bytes rather than served from the slot beside it.
            val phaseUnderItsOwnKey = hostManifest(
                host = host,
                stored = hostStyleRecord(PHASE_STYLE_JSON),
                styleKey = phaseStyleKey,
                baseUri = PHASE_STYLE_BASE_URI,
            )
            assertEquals(listOf("g", "r", "v", "d"), phaseUnderItsOwnKey.sources.map { it.sourceId })
            assertNotSame(rasterAgain, phaseUnderItsOwnKey)
        } finally {
            host.close()
        }
    }

    /**
     * A style RenG cannot read is not an answer worth remembering. The rejection is reported as it
     * stands and nothing is bound, so the cached success of *other* bytes is dropped rather than served
     * for a document it did not come from, and the next call reads the document again rather than
     * replaying a remembered refusal as though it had derived something.
     */
    @Test
    fun neverCachesARejectedStyleAsThoughItHadDerived() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            hostManifest(host)

            cache.installAndTakeLease(hostStyleKey, hostStyleRecord(HOST_UNSUPPORTED_STYLE_JSON), decoded = null)
            val rejected = hostStyleManifestOutcome(host, hostStyleRecord(HOST_UNSUPPORTED_STYLE_JSON))
            val rejectedAgain = hostStyleManifestOutcome(host, hostStyleRecord(HOST_UNSUPPORTED_STYLE_JSON))

            assertEquals(
                BasemapStyleReject.STYLE_VERSION_UNSUPPORTED,
                assertIs<BasemapStyleManifestOutcome.Rejected>(rejected).reason,
                "the rejection of the resident bytes, not the manifest of the bytes before them",
            )
            assertEquals(
                BasemapStyleReject.STYLE_VERSION_UNSUPPORTED,
                assertIs<BasemapStyleManifestOutcome.Rejected>(rejectedAgain).reason,
            )
            assertNotSame(rejected, rejectedAgain, "a rejection is derived again, never served from the cache")

            cache.installAndTakeLease(hostStyleKey, hostStyleRecord(), decoded = null)
            assertEquals(
                listOf(HOST_RASTER_TILE_TEMPLATE),
                hostManifest(host).sources.single().tileTemplates,
                "and a style that derives again after one that did not is derived normally",
            )
        } finally {
            host.close()
        }
    }

    /**
     * The manifest belongs to the host's own lifetime, exactly as the compilation beside it does: it
     * outlives every individual preparation invocation, and [BasemapEngineHost.close] releases the lease
     * keeping its bytes resident before closing the engine. Nothing GL-scoped is involved either way.
     */
    @Test
    fun retainsTheManifestAcrossInvocationsAndReleasesItsLeaseOnClose() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        // install() rather than installAndTakeLease(), so the only lease this key ever has is the host's.
        cache.install(hostStyleKey, hostStyleRecord(), decoded = null)

        val first = host.withOperation(ResourceAccessMode.NORMAL) { hostManifest(host) }
        val second = host.withOperation(ResourceAccessMode.NORMAL) { hostManifest(host) }
        assertSame(first, second, "one manifest spans preparation invocations")
        assertEquals(1, hostLeaseCount(cache, hostStyleKey), "which it does by keeping its content resident")

        host.close()

        assertEquals(0, hostLeaseCount(cache, hostStyleKey), "closing the host gives that lease back")
        assertFailsWith<RenGException> {
            host.styleManifest(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
        }
    }

    private fun hostManifest(
        host: BasemapEngineHost,
        stored: StoredRawResource = hostStyleRecord(),
        styleKey: ResourceKey = hostStyleKey,
        baseUri: String = HOST_STYLE_BASE_URI,
    ): BasemapStyleManifest = assertIs<BasemapStyleManifestOutcome.Derived>(
        host.styleManifest(styleKey, stored, baseUri),
    ).manifest

    private fun hostStyleManifestOutcome(
        host: BasemapEngineHost,
        stored: StoredRawResource = hostStyleRecord(),
        styleKey: ResourceKey = hostStyleKey,
        baseUri: String = HOST_STYLE_BASE_URI,
    ): BasemapStyleManifestOutcome = host.styleManifest(styleKey, stored, baseUri)

    private fun hostLeaseCount(cache: ResidentCache, key: ResourceKey): Int =
        cache.report(ResourceSelector.ByKey(key), noGpuObjects).entries.single().leaseCount

    /** No GL layer stands behind this cache: nothing it holds has a GPU object. */
    private val noGpuObjects: (ResourceKey) -> GpuByteAccount = { GpuByteAccount.NoGpuObjects }

    // ---- incremental route registration ----------------------------------------------------------

    /**
     * An invocation opened with no routes at all still admits exactly what is registered into it later —
     * which is the whole reason the registration is incremental: the routes a style's compilation needs
     * are not knowable until the style has been read, inside the invocation.
     *
     * The tile is served as a 404 so nothing rasterizes: this file runs on `androidHostTest` too, where
     * Skia's native library is absent (see [BasemapEngineRenderTest]). What is under test is which url
     * reaches the consumer, not what the engine does with it.
     */
    @Test
    fun admitsAnEngineExchangeOnlyThroughARouteRegisteredBeforeItHappens() = runTest {
        val transport = CountingHostTransport(statusCode = 404)
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                // Registering the very same route twice is one occurrence joining one route, not a
                // conflict.
                host.registerRoutes(listOf(hostRasterRoute))
                host.registerRoutes(listOf(hostRasterRoute))
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(listOf(HOST_RASTER_TILE_URL), transport.requestedUrls)
    }

    /**
     * Which of `CanonicalBasemapTile`'s two coordinates becomes `TileId.x` and which becomes `TileId.y`,
     * pinned against a tile whose `(x, y)` pair is **not** its own transpose.
     *
     * Every other tile fixture in this repo is symmetric -- `HOST_RASTER_TILE` is `(x = 0, y = 0)`, and the
     * renderer-level frame used to select `x in {1, 2}, y in {1, 2}` -- so a swap inside
     * `BasemapEngineHost.engineTileIdOf` composed a *different but equally acceptable* url and no test
     * moved. That is the exact silent-total-outage shape exact-url assertions exist to catch, so it gets a
     * test whose fixture cannot be transposed. Served as a 404, so it runs on `androidHostTest` too: what
     * is under test is which url the engine composed, not what it did with the bytes.
     */
    @Test
    fun mapsCanonicalXOntoTheEnginesTileXAndTileYOntoItsTileY() = runTest {
        val transport = CountingHostTransport(statusCode = 404)
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostAsymmetricTileRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_ASYMMETRIC_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(
            listOf(HOST_ASYMMETRIC_TILE_URL),
            transport.requestedUrls,
            "canonicalX is the engine's x and tileY is its y; transposing them composes /4/11/2.png, " +
                "which this invocation never routed, so the consumer would see no request at all",
        )
    }

    // ---- which phase fetches which class ---------------------------------------------------------

    /**
     * The companion [partitionsTheConsumerStoreIntoTwoDisjointKeyNamespaces] is missing: that test pins
     * *which* seven classes the engine keys, this one pins **when** it asks for them.
     *
     * That split is what the whole two-phase preregistration rests on -- `styleTimeRoutes` is declared
     * before `preparedStyle` and `tileTimeRoutes` before `prepareTiles`, and a class that moved between
     * the phases in a future Rentile would be requested with no route declared yet and fail closed on
     * every frame. It is also the thing that makes ADR 0016's one-registry-per-invocation rule
     * structural rather than load-bearing today: the two phases share no class, so they share no latch.
     * If this test ever fails, re-examine that conclusion before re-examining anything else.
     *
     * Tiles are served as 404 so nothing rasterizes and this runs on `androidHostTest` too. Rentile plans
     * and awaits *every* tile-time acquisition before it evaluates any failure
     * (`DefaultBasemapRasterizer.prepareBatch` -> `planRasterResources` / `planVectorResources`, then
     * `validateSubstitutionAllowance`), so a 404 loses no observation.
     */
    @Test
    fun fetchesEveryStyleTimeClassWhileCompilingAndEveryTileTimeClassWhileBatching() = runTest {
        val transport = PhaseRecordingHostTransport()
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, phaseStyleTimeRoutes + phaseTileTimeRoutes) {
                val style = host.preparedStyle(
                    styleKey = phaseStyleKey,
                    stored = hostStyleRecord(PHASE_STYLE_JSON),
                    baseUri = PHASE_STYLE_BASE_URI,
                )

                val whileCompiling = transport.requestedClasses().toSet()
                assertEquals(
                    setOf(
                        ResourceClass.BASEMAP_SPRITE_JSON,
                        ResourceClass.BASEMAP_SPRITE_IMAGE,
                        ResourceClass.BASEMAP_GEO_JSON,
                        ResourceClass.BASEMAP_TILE_JSON,
                    ),
                    whileCompiling,
                    "compiling a style fetches the sprite pair, every GeoJSON source, every TileJSON " +
                        "document -- and no tile",
                )

                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(PHASE_TILE)) }

                assertEquals(
                    setOf(
                        ResourceClass.BASEMAP_RASTER_TILE,
                        ResourceClass.BASEMAP_VECTOR_TILE,
                        ResourceClass.BASEMAP_DEM_TILE,
                    ),
                    transport.requestedClasses().toSet() - whileCompiling,
                    "preparing a batch fetches every tile class, and re-fetches no style-time one",
                )
            }
        } finally {
            host.close()
        }
        // The TileJSON document is a style-time class, and that is the whole premise of the `url` form:
        // the document RenG needs in order to name a tile is fetched strictly before any tile is named,
        // so there is a phase in which to read it. A Rentile that moved it into `prepareBatch` would
        // break the reference form outright rather than merely reorder two fetches.
        assertEquals(
            1,
            transport.requestedUrls().count { it == PHASE_TILE_JSON_URL },
            "the TileJSON document is fetched exactly once, while compiling",
        )
    }

    // ---- the TileJSON documents a compilation leaves behind ---------------------------------------

    /**
     * The documents a compilation fetched are retained **beside the compilation**, not beside the
     * invocation that saw them go past, and the reuse path carries them forward untouched.
     *
     * That is what a `RESIDENT`-provenance frame depends on: it emits no `CompileBasemapStyle`, so its
     * invocation calls `resolveTileJson` never and observes nothing. Retaining these with the compiled
     * style -- which still holds the templates Rentile derived from them -- is the only thing that keeps
     * such a frame able to name a tile at all.
     */
    @Test
    fun retainsTheParsedTileJsonDocumentsBesideTheCompilationTheyWereFetchedFor() = runTest {
        val transport = PhaseRecordingHostTransport()
        val host = basemapEngineHost(transport = transport)
        try {
            val stored = hostStyleRecord(PHASE_STYLE_JSON)
            host.withOperation(ResourceAccessMode.NORMAL, phaseStyleTimeRoutes) {
                host.preparedStyle(phaseStyleKey, stored, PHASE_STYLE_BASE_URI)
            }
            val parsed = assertIs<BasemapTileJsonOutcome.Parsed>(
                host.tileJsonDocuments(phaseStyleKey, stored.contentDigest)[PHASE_TILE_JSON_URL],
            )
            assertEquals(
                listOf("https://tiles.example/p/t/{z}/{x}/{y}.pbf?key=k"),
                parsed.document.tileTemplates,
            )

            // A second invocation over byte-identical content reuses the compilation and performs no
            // acquisition at all, so the documents must survive that path rather than be re-observed.
            host.withOperation(ResourceAccessMode.NORMAL, phaseStyleTimeRoutes) {
                host.preparedStyle(phaseStyleKey, stored, PHASE_STYLE_BASE_URI)
            }
            assertEquals(
                1,
                transport.requestedUrls().count { it == PHASE_TILE_JSON_URL },
                "the second invocation re-fetched nothing, so it observed nothing",
            )
            assertIs<BasemapTileJsonOutcome.Parsed>(
                host.tileJsonDocuments(phaseStyleKey, stored.contentDigest)[PHASE_TILE_JSON_URL],
                "and the retained document survives the reuse",
            )
        } finally {
            host.close()
        }
        assertEquals(
            emptyMap(),
            host.tileJsonDocuments(phaseStyleKey, "some-other-digest"),
            "and is declined for content this host holds no compilation of",
        )
    }

    /**
     * A TileJSON document only **RenG's** stricter reader refuses degrades the one source that named it
     * and leaves the compilation standing.
     *
     * Rejecting the style here would be wrong twice over. The engine compiled it, so the style genuinely
     * works; and RenG's reader diverges from kotlinx in four documented ways -- duplicate member names,
     * as here, plus invalid UTF-8, number spelling, and nesting depth -- none of which says anything
     * about whether the document is usable. What RenG loses is the ability to name that source's tiles,
     * which the firewall then reports precisely and attributably at the moment one is asked for.
     */
    @Test
    fun keepsTheCompilationWhenOnlyRenGsStricterReaderRefusesATileJsonDocument() = runTest {
        val host = basemapEngineHost(transport = StrictTileJsonHostTransport())
        try {
            val stored = hostStyleRecord(STRICT_TILE_JSON_STYLE_JSON)
            host.withOperation(ResourceAccessMode.NORMAL, listOf(strictTileJsonRoute)) {
                // Does not throw: Rentile's kotlinx reader keeps the last duplicate and compiles happily.
                host.preparedStyle(hostStyleKey, stored, HOST_STYLE_BASE_URI)
            }
            assertEquals(
                BasemapTileJsonOutcome.Rejected(
                    BasemapSourceUnderivableReason.TILE_JSON_DUPLICATE_MEMBER_NAME,
                ),
                host.tileJsonDocuments(hostStyleKey, stored.contentDigest)[STRICT_TILE_JSON_URL],
                "the reason is carried, not discarded, so a later diagnostic can name it",
            )
        } finally {
            host.close()
        }
    }

    /**
     * Rentile fetches the sprite metadata and the sprite image as two concurrent `async` children on
     * `Dispatchers.Default` (`SpriteResourceAcquirer.acquire`), and this cycle observed a real lost update
     * from that parallelism about one run in ten -- on the **JVM**, whose threading is what
     * `androidHostTest` exercises and Kotlin/Native does not. That path had no JVM test after the
     * renderer-level basemap tests moved to `nativeTest`, so it gets one here: sprite acquisition happens
     * entirely inside `engine.prepare`, which needs no rasterizer at all.
     *
     * [PhaseRecordingHostTransport] records through the same `ConcurrentRecorder` shape the renderer
     * fixtures use, for exactly the reason above -- a counting assertion is only as trustworthy as its
     * counter.
     */
    @Test
    fun acquiresTheSpritePairConcurrentlyThroughTheFirewallWhileCompiling() = runTest {
        val transport = PhaseRecordingHostTransport()
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, phaseStyleTimeRoutes) {
                host.preparedStyle(phaseStyleKey, hostStyleRecord(PHASE_STYLE_JSON), PHASE_STYLE_BASE_URI)
            }
        } finally {
            host.close()
        }
        val urls = transport.requestedUrls()
        assertEquals(1, urls.count { it == PHASE_SPRITE_JSON_URL }, "the atlas metadata is fetched once")
        assertEquals(1, urls.count { it == PHASE_SPRITE_IMAGE_URL }, "the atlas image is fetched once")
    }

    @Test
    fun refusesARouteRegisteredForADifferentAccessModeOrOutsideAnyInvocation() = runTest {
        val host = basemapEngineHost()
        try {
            assertFailsWith<RenGException> { host.registerRoutes(listOf(hostRasterRoute)) }
            host.withOperation(ResourceAccessMode.CACHE_ONLY) {
                assertFailsWith<IllegalArgumentException> { host.registerRoutes(listOf(hostRasterRoute)) }
            }
        } finally {
            host.close()
        }
    }

    private fun assertNotNullGeneration(cache: ResidentCache) {
        assertTrue(
            cache.current(hostStyleKey) != null,
            "compiling a style installs and leases its resident generation",
        )
    }

    // ---- consumer Store key namespaces -----------------------------------------------------------

    @Test
    fun partitionsTheConsumerStoreIntoTwoDisjointKeyNamespaces() {
        // Two different key spaces address one consumer Store. RenG's own driver reads and writes with
        // `ResourceKeyDeriver.external`'s canonical hash (`ResourceActionExecutor`'s `action.rawKey`); the
        // firewall passes RENTILE's `sha256Hex(redacted url)` straight through (`OperationRegistry`'s
        // `RenGRawResourceKey(stableId = key.stableId, ...)`). Two keys for one logical resource would be
        // two reads and two writes where RenG's contract permits one exchange, so the partition below is a
        // contract, not an implementation detail: the engine keys exactly the eight classes it fetches
        // itself, and RenG keys exactly the five it fetches itself.
        //
        // What this pins is the table. What makes the spaces genuinely disjoint *today* is that no
        // production path hands an engine-keyed class to the driver at all: `FramePlanningCore`'s static
        // traversal builds `StaticResourceReference.External` only for the four below, and the discovered
        // children that would carry the other eight are produced by the basemap-style commit actions, which
        // `ResourceActionExecutor` still leaves to its `else`.
        val rengKeyed = ResourceClass.entries.filter { engineKeyedResourceClassOf(it) == null }.toSet()
        val engineKeyed = ResourceClass.entries.filter { engineKeyedResourceClassOf(it) != null }.toSet()

        assertEquals(emptySet(), rengKeyed intersect engineKeyed)
        assertEquals(ResourceClass.entries.toSet(), rengKeyed + engineKeyed)
        assertEquals(
            setOf(
                ResourceClass.BASEMAP_STYLE,
                ResourceClass.STICKER_IMAGE,
                ResourceClass.BACKDROP_IMAGE,
                ResourceClass.MODEL_GLB,
                ResourceClass.MODEL_TEXTURE,
            ),
            rengKeyed,
            "moving a class between these namespaces makes one consumer resource answer to two keys",
        )
        assertEquals(8, engineKeyed.size, "Rentile 0.7.0 fetches and keys exactly eight basemap classes")
    }

    // ---- preregistration matches what the engine actually requests -------------------------------

    @Test
    fun requestsExactlyTheUrlItPreregistered() = runTest {
        val transport = CountingHostTransport(statusCode = 404)
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(1, transport.executeCalls, "the preregistered route is the one the engine asks for")
        assertEquals(
            HOST_RASTER_TILE_URL,
            transport.lastRequest?.locator?.value,
            "Rentile composes {z}/{x}/{y} itself; RenG must preregister the composed url, not the template",
        )
    }

    @Test
    fun failsClosedWhenTheRegisteredUrlIsNotTheOneTheEngineComposes() = runTest {
        val transport = CountingHostTransport()
        val host = basemapEngineHost(transport = transport)
        try {
            // The template, not the composed url -- the exact mistake that would present as a total
            // basemap outage rather than as a mismatch.
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostTemplateRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(0, transport.executeCalls, "an unpreregistered exchange never reaches the consumer")
    }

    @Test
    fun failsTheWholeBatchRatherThanSubstitutingForAnUnavailableTile() = runTest {
        // RenG "performs no repeated consumer exchanges, retries, repairs, or fallbacks", and Rentile's
        // tile substitution is exactly such a fallback -- so a 404 on a required tile must fail the batch,
        // never quietly become a stretched z0 ancestor. Both the exact route AND its ancestor are
        // preregistered here so the difference is observable at the consumer: with substitution disabled
        // Rentile never asks for the ancestor at all, and with it enabled it does.
        val transport = CountingHostTransport(
            statusForUrl = { url -> if (url == HOST_RASTER_TILE_URL) 404 else 200 },
        )
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute, hostAncestorRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(
            listOf(HOST_RASTER_TILE_URL),
            transport.requestedUrls,
            "an unavailable tile is a failure, not an invitation to fetch a substitute",
        )
    }

    // ---- failures -------------------------------------------------------------------------------

    @Test
    fun translatesTheEnginesOwnDigestBackToRenGsCanonicalResourceKey() = runTest {
        val host = basemapEngineHost(transport = CountingHostTransport(statusCode = 404))
        val failure = try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }

        assertEquals(RenGErrorCode.RESOURCE_UNAVAILABLE, failure.code)
        val reported = failure.diagnostics.single().resourceKey
        val rengKey = ResourceKeyDeriver(PureKotlinSha256)
            .external(ResourceClass.BASEMAP_RASTER_TILE, ResourceLocator(HOST_RASTER_TILE_URL))
            .key
        assertEquals(rengKey, reported, "a descriptor leaving this host names RenG's own key")
        assertNotEquals(
            hostEngineSanitizedIdOf(HOST_RASTER_TILE_URL),
            reported?.stableId,
            "Rentile's sha256(redacted url) is a foreign namespace and must never escape as an identity",
        )
        assertEquals(ResourceClass.BASEMAP_RASTER_TILE, reported?.resourceClass)
    }

    @Test
    fun neverLetsARentileExceptionEscape() = runTest {
        val signedUrl = "https://tiles.example/r/1/0/0.png?access_token=SECRET"
        val transport = CountingHostTransport(throwable = RuntimeException(signedUrl))
        val host = basemapEngineHost(transport = transport)
        val failure = try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        val rendered = failure.toString() + failure.diagnostics.joinToString { it.toString() }
        assertFalse(rendered.contains("SECRET"), "an adapter's credential must never surface")
        assertNull(failure.cause, "a RenG failure never carries an engine cause")
    }

    @Test
    fun keepsAdapterCancellationUnwrapped() = runTest {
        val cancellingTransport = object : Transport {
            override suspend fun execute(request: TransportRequest): TransportResponse =
                throw CancellationException("cancelled")
        }
        val host = basemapEngineHost(transport = cancellingTransport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                // Asserted on type, never on identity: Kotlin's stack recovery may hand back a copy
                // carrying the original as its immediate cause.
                assertFailsWith<CancellationException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun refusesAnEngineCallOutsideAnyPreparationInvocation() = runTest {
        // The registry's lifetime is exactly one preparation invocation (ADR 0016), so the engine's fixed
        // adapters must fail closed rather than answer from a registry that has already been discarded.
        val transport = CountingHostTransport()
        val store = CountingHostStore()
        val host = basemapEngineHost(transport = transport, store = store)
        try {
            val style = host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
            assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            assertEquals(0, transport.executeCalls + store.readCalls + store.writeCalls)
        } finally {
            host.close()
        }
    }

    @Test
    fun givesEachPreparationInvocationItsOwnOperationRegistry() = runTest {
        val transport = CountingHostTransport(throwable = RuntimeException("adapter down"))
        val host = basemapEngineHost(transport = transport)
        try {
            val style = host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
            repeat(2) {
                host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                    assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
                }
            }
            // A latch never evicts within one registry, so a registry that outlived its invocation would
            // replay the first failure without calling the consumer again and this count would read 1.
            assertEquals(2, transport.executeCalls, "each invocation gets its own operation-scoped registry")
        } finally {
            host.close()
        }
    }

    /**
     * An invocation carries a [Job] of its own even when the caller's context has none.
     *
     * This is not a hypothetical. Rentile's `DefaultBasemapRasterizer.operation` reads
     * `currentCoroutineContext().job` on every rasterizer entry point, so without a job every
     * `prepareTiles` and `renderTiles` throws `IllegalStateException("Current context doesn't contain Job
     * in it")`, which [classifyEngineFailure] can only report as the opaque `BASEMAP_RENDER_FAILED` --
     * a total basemap blackout with no usable diagnosis, for a consumer that did nothing wrong. A
     * `suspend` function may be resumed from any context, `EmptyCoroutineContext` included, so RenG's
     * public `prepare` cannot require the consumer to have launched it from a scope. (Style compilation
     * hid this for a long time because it runs inside `PreparationDriver.run`, which opens its own
     * `coroutineScope`; tile preparation runs in [BasemapEngineHost.withOperation]'s block and did not.)
     *
     * Deliberately driven by [startCoroutine] rather than `runTest` or `runBlocking`: both of those put a
     * [Job] in the context, which is precisely the condition this test must not have.
     */
    @Test
    fun givesTheInvocationItsOwnJobEvenWhenTheCallerContextHasNone() {
        val host = basemapEngineHost()
        var observedJob: Job? = null
        var failure: Throwable? = null
        var finished = false
        try {
            val invocation: suspend () -> Unit = {
                host.withOperation(ResourceAccessMode.NORMAL) {
                    observedJob = currentCoroutineContext()[Job]
                }
            }
            invocation.startCoroutine(
                Continuation(EmptyCoroutineContext) { result ->
                    failure = result.exceptionOrNull()
                    finished = true
                },
            )
            assertTrue(finished, "an invocation over an empty block completes without ever dispatching")
            assertNull(failure, "a caller without a Job is a legal caller")
            assertNotNull(observedJob, "the invocation must supply the Job Rentile's rasterizer reads")
        } finally {
            host.close()
        }
    }

    // ---- a shared invocation, which a batch's frames join (ADR 0051) ------------------------------

    @Test
    fun aSharedInvocationIsJoinedByTheFramesInsideItRatherThanReplaced() = runTest {
        val transport = CountingHostTransport(throwable = RuntimeException("adapter down"))
        val host = basemapEngineHost(transport = transport)
        try {
            host.withSharedOperation(ResourceAccessMode.NORMAL) {
                val style = host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                    host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                }
                repeat(2) {
                    host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                        assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
                    }
                }
            }
            // The mirror image of givesEachPreparationInvocationItsOwnOperationRegistry, which asserts
            // 2 for the same two invocations run as roots. Inside a shared root they join one registry,
            // so the second finds the first's latch and the consumer is never asked again.
            assertEquals(1, transport.executeCalls, "frames inside one shared invocation share its latches")
        } finally {
            host.close()
        }
    }

    @Test
    fun aPlainInvocationNestedInsideAnotherStillFails() = runTest {
        val host = basemapEngineHost()
        try {
            assertFailsWith<IllegalStateException> {
                host.withOperation(ResourceAccessMode.NORMAL) {
                    // Only a *shared* root may be joined. Concurrency is not nesting, and this check is
                    // the one that catches two preparations sharing a host -- ADR 0051 moves it to "one
                    // root at a time" rather than removing it.
                    host.withOperation(ResourceAccessMode.NORMAL) { }
                }
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun aSharedInvocationIsNeverJoinedUnderADifferentAccessMode() = runTest {
        val host = basemapEngineHost()
        try {
            assertFailsWith<IllegalStateException> {
                host.withSharedOperation(ResourceAccessMode.NORMAL) {
                    // One registry is never shared across modes: its routes carry the mode they were
                    // preregistered under, so a joiner asking under another would answer from routes
                    // that never agreed to it.
                    host.withOperation(ResourceAccessMode.RELOAD) { }
                }
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun aJoinedBlockStillCarriesTheJobRentilesRasterizerReads() {
        val host = basemapEngineHost()
        var observedJob: Job? = null
        var failure: Throwable? = null
        var finished = false
        try {
            // Same construction, and the same requirement, as
            // givesTheInvocationItsOwnJobEvenWhenTheCallerContextHasNone: every Rentile rasterizer
            // entry point reads `currentCoroutineContext().job`, so a joined frame with no Job would
            // fail every prepareTiles with an opaque BASEMAP_RENDER_FAILED.
            //
            // The Job here comes from the *root's* scope, not from the joined block's own -- removing
            // the inner `coroutineScope` leaves this passing, which is why that inner scope is
            // justified by child-bounding rather than by this property.
            val invocation: suspend () -> Unit = {
                host.withSharedOperation(ResourceAccessMode.NORMAL) {
                    host.withOperation(ResourceAccessMode.NORMAL) {
                        observedJob = currentCoroutineContext()[Job]
                    }
                }
            }
            invocation.startCoroutine(
                Continuation(EmptyCoroutineContext) { result ->
                    failure = result.exceptionOrNull()
                    finished = true
                },
            )
            assertTrue(finished, "an invocation over an empty block completes without ever dispatching")
            assertNull(failure, "a caller without a Job is a legal caller")
            assertNotNull(observedJob, "a joined block must supply the Job Rentile's rasterizer reads")
        } finally {
            host.close()
        }
    }
}

// ---- fixtures ------------------------------------------------------------------------------------

internal const val HOST_STYLE_BASE_URI: String = "https://styles.example/basic.json"
internal const val HOST_RASTER_TILE_URL: String = "https://tiles.example/r/1/0/0.png"
internal const val HOST_RASTER_TILE_TEMPLATE: String = "https://tiles.example/r/{z}/{x}/{y}.png"
internal const val HOST_ANCESTOR_TILE_URL: String = "https://tiles.example/r/0/0/0.png"

private const val HOST_STYLE_DIGEST_A = "digest-a"
private const val HOST_STYLE_DIGEST_B = "digest-b"
private val HOST_TILE_OUTPUT_SIZE = OutputPixelSize(512, 512)

/** `TileId(z = 1, x = 0, y = 0)` in RenG's own vocabulary. */
internal val HOST_RASTER_TILE: CanonicalBasemapTile = CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0)

/** [HOST_RASTER_TILE_TEMPLATE] edited, so a style carrying it derives a visibly different manifest. */
internal const val HOST_EDITED_TILE_TEMPLATE: String = "https://tiles.example/edited/{z}/{x}/{y}.png"

/** Reads cleanly and declares a style version outside RenG's subset: a document-level rejection. */
internal val HOST_UNSUPPORTED_STYLE_JSON: String = """{"version":7,"sources":{},"layers":[]}"""

internal val HOST_STYLE_JSON: String =
    """{"version":8,"name":"reng-basemap-host-test",""" +
        """"sources":{"s":{"type":"raster","tiles":["$HOST_RASTER_TILE_TEMPLATE"],"tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

internal val hostStyleKey: ResourceKey = ResourceKeyDeriver(PureKotlinSha256)
    .external(ResourceClass.BASEMAP_STYLE, ResourceLocator(HOST_STYLE_BASE_URI))
    .key

internal val hostRasterRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(HOST_RASTER_TILE_URL),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

/**
 * The z0 ancestor of [HOST_RASTER_TILE]. Preregistered only by the substitution test, which needs the
 * engine's substitute attempt to be able to reach the consumer at all: the firewall would otherwise refuse
 * it before `Transport.execute`, and a refused attempt is invisible to a consumer-side counter.
 */
internal val hostAncestorRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(HOST_ANCESTOR_TILE_URL),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

internal val hostTemplateRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(HOST_RASTER_TILE_TEMPLATE),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

/**
 * A tile whose `(x, y)` pair is disjoint from its own transpose -- unlike [HOST_RASTER_TILE], which is
 * `(0, 0)`. See [BasemapEngineHostTest.mapsCanonicalXOntoTheEnginesTileXAndTileYOntoItsTileY].
 */
internal val HOST_ASYMMETRIC_TILE: CanonicalBasemapTile = CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 2)

internal const val HOST_ASYMMETRIC_TILE_URL: String = "https://tiles.example/r/4/2/11.png"

internal val hostAsymmetricTileRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(HOST_ASYMMETRIC_TILE_URL),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

// ---- the phase fixture: every class RenG routes, in one style --------------------------------------

internal const val PHASE_STYLE_BASE_URI: String = "https://styles.example/phase.json"
internal const val PHASE_SPRITE_BASE_URL: String = "https://sprites.example/phase"
internal const val PHASE_SPRITE_JSON_URL: String = "https://sprites.example/phase.json"
internal const val PHASE_SPRITE_IMAGE_URL: String = "https://sprites.example/phase.png"
internal const val PHASE_GEO_JSON_URL: String = "https://data.example/points.geojson"
internal const val PHASE_TILE_JSON_URL: String = "https://tiles.example/p/t/tiles.json?key=k"
internal const val PHASE_RASTER_TILE_URL: String = "https://tiles.example/p/r/3/5/2.png"
internal const val PHASE_VECTOR_TILE_URL: String = "https://tiles.example/p/v/3/5/2.pbf"
internal const val PHASE_DEM_TILE_URL: String = "https://tiles.example/p/d/3/5/2.png"

/** The tile the TileJSON document below declares, composed for [PHASE_TILE]. */
internal const val PHASE_TILE_JSON_TILE_URL: String = "https://tiles.example/p/t/3/5/2.pbf?key=k"

/** Shaped like the 16 documents the verified corpus names: absolute template, explicit zoom range. */
internal val PHASE_TILE_JSON_BODY: String =
    """{"tilejson":"2.0.0","tiles":["https://tiles.example/p/t/{z}/{x}/{y}.pbf?key=k"],""" +
        """"minzoom":0,"maxzoom":14}"""

/** `TileId(z = 3, x = 5, y = 2)`, asymmetric for the same reason [HOST_ASYMMETRIC_TILE] is. */
internal val PHASE_TILE: CanonicalBasemapTile = CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5)

/**
 * One style that reaches every class RenG routes: a sprite the `background-pattern` layer makes
 * unconditional, a GeoJSON source, and a raster, vector and `raster-dem` source each drawn by the one
 * layer kind that samples it.
 */
internal val PHASE_STYLE_JSON: String =
    """{"version":8,"name":"reng-phase-test",""" +
        """"sprite":"$PHASE_SPRITE_BASE_URL",""" +
        """"sources":{""" +
        """"g":{"type":"geojson","data":"$PHASE_GEO_JSON_URL"},""" +
        """"r":{"type":"raster","tiles":["https://tiles.example/p/r/{z}/{x}/{y}.png"],"tileSize":256},""" +
        """"v":{"type":"vector","tiles":["https://tiles.example/p/v/{z}/{x}/{y}.pbf"]},""" +
        """"d":{"type":"raster-dem","tiles":["https://tiles.example/p/d/{z}/{x}/{y}.png"],"tileSize":256},""" +
        """"t":{"type":"vector","url":"$PHASE_TILE_JSON_URL"}""" +
        """},"layers":[""" +
        """{"id":"bg","type":"background","paint":{"background-pattern":"dot"}},""" +
        """{"id":"raster","type":"raster","source":"r"},""" +
        """{"id":"vector","type":"fill","source":"v","source-layer":"water"},""" +
        """{"id":"hillshade","type":"hillshade","source":"d"},""" +
        """{"id":"geo","type":"fill","source":"g"},""" +
        """{"id":"tilejson","type":"fill","source":"t","source-layer":"water"}""" +
        """]}"""

internal val phaseStyleKey: ResourceKey = ResourceKeyDeriver(PureKotlinSha256)
    .external(ResourceClass.BASEMAP_STYLE, ResourceLocator(PHASE_STYLE_BASE_URI))
    .key

private fun phaseRoute(url: String, resourceClass: ResourceClass): ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(url),
    resourceClass = resourceClass,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

internal val phaseStyleTimeRoutes: List<ResourceRouteKey> = listOf(
    phaseRoute(PHASE_SPRITE_JSON_URL, ResourceClass.BASEMAP_SPRITE_JSON),
    phaseRoute(PHASE_SPRITE_IMAGE_URL, ResourceClass.BASEMAP_SPRITE_IMAGE),
    phaseRoute(PHASE_GEO_JSON_URL, ResourceClass.BASEMAP_GEO_JSON),
    phaseRoute(PHASE_TILE_JSON_URL, ResourceClass.BASEMAP_TILE_JSON),
)

/**
 * The centre tile plus the DEM neighbourhood the hillshade layer samples -- derived here the way
 * `tileTimeRoutes` derives it, so this fixture stays a firewall fixture rather than a second
 * implementation of the composition under test.
 */
internal val phaseTileTimeRoutes: List<ResourceRouteKey> =
    listOf(
        phaseRoute(PHASE_RASTER_TILE_URL, ResourceClass.BASEMAP_RASTER_TILE),
        phaseRoute(PHASE_VECTOR_TILE_URL, ResourceClass.BASEMAP_VECTOR_TILE),
        phaseRoute(PHASE_TILE_JSON_TILE_URL, ResourceClass.BASEMAP_VECTOR_TILE),
    ) +
        (1..3).flatMap { y ->
            (4..6).map { x ->
                phaseRoute("https://tiles.example/p/d/3/$x/$y.png", ResourceClass.BASEMAP_DEM_TILE)
            }
        }

/**
 * Answers a valid document for every style-time class and a 404 for every tile, recording each request's
 * url and class through the concurrency-safe recorder the renderer fixtures use -- Rentile fetches the
 * sprite pair on two concurrent `Dispatchers.Default` children, so a plain list loses entries.
 */
internal class PhaseRecordingHostTransport : Transport {
    private val urls = ConcurrentRecorder<String>()
    private val classes = ConcurrentRecorder<ResourceClass>()

    suspend fun requestedUrls(): List<String> = urls.snapshot()

    suspend fun requestedClasses(): List<ResourceClass> = classes.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        urls.record(request.locator.value)
        classes.record(request.resourceClass)
        return when (request.locator.value) {
            PHASE_SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            PHASE_SPRITE_IMAGE_URL -> TransportResponse(
                statusCode = 200,
                body = VALID_TILE_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
            PHASE_GEO_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = """{"type":"FeatureCollection","features":[]}""".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/geo+json"),
            )
            PHASE_TILE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = PHASE_TILE_JSON_BODY.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(statusCode = 404, body = ByteArray(0))
        }
    }
}

internal fun hostStyleRecord(json: String = HOST_STYLE_JSON): StoredRawResource {
    val bytes = json.encodeToByteArray()
    return StoredRawResource(
        bytes = bytes,
        contentDigest = PureKotlinSha256.digest(CanonicalBytes(bytes)).lowercaseHex,
        metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
    )
}

internal fun hostEngineSanitizedIdOf(url: String): String =
    PureKotlinSha256.digest(CanonicalBytes(redactAuthenticationQuery(url).encodeToByteArray())).lowercaseHex

internal fun basemapEngineHost(
    transport: Transport = CountingHostTransport(),
    store: Store = CountingHostStore(),
    cache: ResidentCache = ResidentCache(),
): BasemapEngineHost = BasemapEngineHost(
    transport = transport,
    store = store,
    cache = cache,
    tileOutputSizePixels = 512,
)

/** Counts every [Transport.execute] call and records the last request. Single-threaded by design: every
 *  test here drives it from one `runTest` scheduler. */
internal class CountingHostTransport(
    private val statusCode: Int = 200,
    private val body: ByteArray = VALID_TILE_PNG,
    private val throwable: Throwable? = null,
    private val statusForUrl: (String) -> Int = { statusCode },
) : Transport {
    var executeCalls: Int = 0
        private set
    var lastRequest: TransportRequest? = null
        private set
    val requestedUrls: MutableList<String> = mutableListOf()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        lastRequest = request
        requestedUrls += request.locator.value
        throwable?.let { throw it }
        return TransportResponse(
            statusCode = statusForUrl(request.locator.value),
            body = body,
            metadata = TransportResponseMetadata(contentType = "image/png"),
        )
    }
}

/** Counts every [Store] call and answers [response] on read. */
internal class CountingHostStore(
    private val response: StoredRawResource? = null,
) : Store {
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        readCalls += 1
        return response
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        writeCalls += 1
    }
}

// A real, valid 2x2 truecolour PNG (colour type 2) -- the same fixture the driver and firewall suites
// use, so a rendering target genuinely decodes it rather than merely accepting bytes.
internal val VALID_TILE_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

// ---- a TileJSON only RenG's stricter reader refuses -----------------------------------------------

internal const val STRICT_TILE_JSON_URL: String = "https://tiles.example/strict/tiles.json"

/**
 * A duplicate `tilejson` member. kotlinx keeps the last occurrence and Rentile compiles the style; RenG's
 * own reader rejects the document under its own code. The divergence runs in the safe direction -- RenG
 * refuses a document Rentile read -- so it can only cost a route, never compose a wrong url.
 */
internal const val STRICT_TILE_JSON_BODY: String =
    """{"tilejson":"2.0.0","tilejson":"2.0.0",""" +
        """"tiles":["https://tiles.example/strict/{z}/{x}/{y}.png"],"minzoom":0,"maxzoom":14}"""

internal val STRICT_TILE_JSON_STYLE_JSON: String =
    """{"version":8,"name":"reng-strict-tilejson-test",""" +
        """"sources":{"s":{"type":"raster","url":"$STRICT_TILE_JSON_URL","tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

internal val strictTileJsonRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(STRICT_TILE_JSON_URL),
    resourceClass = ResourceClass.BASEMAP_TILE_JSON,
    maximumResponseBytes = 4L * 1024L * 1024L,
)

/** Answers [STRICT_TILE_JSON_BODY] for the document and a 404 for anything else. */
internal class StrictTileJsonHostTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse =
        if (request.locator.value == STRICT_TILE_JSON_URL) {
            TransportResponse(
                statusCode = 200,
                body = STRICT_TILE_JSON_BODY.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
        } else {
            TransportResponse(statusCode = 404, body = ByteArray(0))
        }
}
