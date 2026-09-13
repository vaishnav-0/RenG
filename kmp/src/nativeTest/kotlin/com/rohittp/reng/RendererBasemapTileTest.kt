package com.rohittp.reng

import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.basemapTileKey
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

/**
 * The renderer end of basemap **tiles**: one `prepare()` acquires the style, compiles it through the
 * real engine, preregisters the exact urls that style's tiles compose, and hands back rendered ground
 * bytes carrying RenG's own identity.
 *
 * **Why this suite is native-only.** Every test here drives `BasemapRasterizer.render`, and Rentile
 * rasterizes through Skia. This project's `androidHostTest` runtime resolves `org.jetbrains.skiko:skiko`'s
 * API without its native library — Rentile adds `skiko-awt-runtime-<host>` only to its own JVM/Android
 * test source sets, never to what it publishes — so on that target `prepareTiles` fails with
 * `RESOURCE_DECODE_FAILED` and `renderTiles` with `BASEMAP_RENDER_FAILED`, both measured, for **any**
 * style including a source-less one. A renderer-level basemap test therefore cannot pass there at all;
 * it is the environment that is incapable, not RenG. Kotlin/Native links Skia in, so this runs for real
 * on `macosArm64Test` (Apple CI) and `linuxX64Test` (Ubuntu CI) — the same reasoning, and the same two
 * targets, as `internal.firewall.BasemapEngineRenderTest`.
 *
 * Urls are asserted as **exact composed strings**, never as shapes. RenG reproduces Rentile's private
 * url composition from a pinned version; a plausible-but-wrong url is not a mismatch a consumer can see,
 * it is `AMBIGUOUS_RESOURCE_ROUTE` on every tile at once — a total outage.
 */
class RendererBasemapTileTest {

    /**
     * The whole path in one assertion set: the frame's four selected ground tiles come back as raw
     * premultiplied pixels, each named by [basemapTileKey] over the style digest, the canonical tile,
     * and the engine's output size — RenG's own derivation, deliberately not Rentile's
     * `outputRequestKey`.
     *
     * Raw rather than encoded because four tiles are 4 MiB against ADR 0044's 64 MiB default, so this
     * is the path the default budget admits. The fallback has its own test below.
     */
    @Test
    fun rendersEveryGroundTileTheFrameSelectedAndNamesItWithRenGsOwnIdentity() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame

        val style = assertNotNull(renderer.preparedBasemapStyle, "a basemap frame holds its compiled style")
        assertEquals(
            listOf(
                CanonicalBasemapTile(lod = 4, tileY = 10, canonicalX = 1),
                CanonicalBasemapTile(lod = 4, tileY = 10, canonicalX = 2),
                CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 1),
                CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 2),
            ),
            frame.basemapTiles.map { it.tile }.sortedWith(compareBy({ it.tileY }, { it.canonicalX })),
            "every canonical tile the spatial plan selected is rendered exactly once",
        )
        frame.basemapTiles.forEach { rendered ->
            assertEquals(
                basemapTileKey(style.digest, rendered.tile, TILE_OUTPUT_SIZE),
                rendered.key,
                "a rendered tile carries RenG's own canonical identity, not the engine's request key",
            )
            assertEquals(ResourceKind.BASEMAP_TILE, rendered.key.kind)
            // Not merely non-empty: raw pixels are claimed by their own arithmetic rather than by a
            // signature, because they have none. Tight packing at the engine's output size is the
            // whole contract `uploadPremultipliedTexture` relies on, so it is what is asserted.
            val pixels = assertIs<BasemapTilePixels.Raw>(
                rendered.pixels,
                "the default budget admits raw pixels for a four-tile frame (ADR 0044)",
            )
            assertEquals(TILE_OUTPUT_SIZE.width, pixels.widthPx, "a tile is square at the engine's output size")
            assertEquals(TILE_OUTPUT_SIZE.height, pixels.heightPx, "a tile is square at the engine's output size")
            assertEquals(
                pixels.widthPx * pixels.heightPx * 4,
                pixels.rgba.size,
                "raw pixels are tightly packed RGBA8 with no row padding",
            )
            assertTrue(rendered.contentKey.isNotEmpty(), "Rentile's own content key travels beside them")
            assertEquals(emptyList(), rendered.substitutions, "tile substitution stays disabled")
        }
    }

    /**
     * A budget too small for one tile sends the whole frame down the encoded path (ADR 0044).
     *
     * One byte, not zero, and that is the point of the number: zero would also be satisfied by an
     * implementation that never takes raw pixels at all, so it cannot tell a working budget from an
     * unwired one. One byte is under a tile and over nothing, so only an implementation that actually
     * compares outstanding-plus-requested against the limit produces this result. The companion test
     * above, on the same fixture with the default limit, is the other half: together they show the
     * decision moving with the budget rather than being fixed either way.
     *
     * The frame still draws. Falling back is the behaviour of every release before ADR 0044, so the
     * assertion is that the tiles arrive encoded, not that anything degraded.
     */
    @Test
    fun aBudgetBelowOneTileFallsBackToEncodedPixelsAndStillRendersEveryTile() = runTest {
        val renderer = styleRenderer(
            TileTransport(),
            resourceLimits = ResourceLimits(maximumInFlightRawBasemapTileBytes = 1L),
        ) as RenGRenderer

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame

        assertEquals(4, frame.basemapTiles.size, "the budget changes the form, never which tiles are drawn")
        frame.basemapTiles.forEach { rendered ->
            assertIs<BasemapTilePixels.Encoded>(
                rendered.pixels,
                "a budget under one tile cannot admit raw pixels",
            )
        }
        assertEquals(0L, frame.rawTileBytes, "an encoded frame holds no raw bytes against the budget")
    }

    /**
     * A camera that has not moved since its tiles were drawn rasterises nothing (ADR 0046).
     *
     * The draw between the two preparations is the whole point and not scaffolding: a tile becomes
     * resident when its texture is uploaded, which is a draw-time act, so a second `prepare()` with
     * no draw in between would legitimately still rasterise. This is what makes the assertion about
     * residency rather than about repeating a call.
     *
     * It asserts the ground is still placed as well as that nothing was rendered, because those two
     * used to be the same condition — `groundInstances` returned empty whenever nothing was rendered.
     * Separating them is what lets a frame draw ground it did not rasterise, and a test that checked
     * only the first half would pass on a build that drew no ground at all.
     */
    @Test
    fun aPreparationsPriorityReachesTheEngineRatherThanStoppingAtTheParameter() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer
        assertNull(renderer.lastForwardedRenderPriority, "nothing rendered yet")

        renderer.prepare(basemapPlan(frameIndex = 0L), priority = RenGRenderPriority.URGENT).close()

        // ADR 0053. The engine consumes a priority into its own gate and reports it nowhere, so this
        // is the only point where "the consumer's choice reached the engine call" is observable at
        // all -- and an argument quietly dropped anywhere along the thread-through would default to
        // NORMAL for every frame, forever, with every other test still green.
        assertEquals(RenGRenderPriority.URGENT, renderer.lastForwardedRenderPriority)

        // And the default is still the lane every release before this one used.
        renderer.prepare(basemapPlan(frameIndex = 1L)).close()
        assertEquals(RenGRenderPriority.NORMAL, renderer.lastForwardedRenderPriority)
    }

    @Test
    fun aBatchsPriorityAppliesToEveryFrameInIt() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer

        renderer.prepareBatch(
            listOf(basemapPlan(frameIndex = 0L), shiftedBasemapPlan(frameIndex = 1L)),
            priority = RenGRenderPriority.URGENT,
        ).forEach { it.close() }

        // The second frame rasterises tiles of its own (ADR 0052 leaves it two), so this reads the
        // priority of a render the batch's *last* frame caused, not a leftover from its first.
        assertEquals(RenGRenderPriority.URGENT, renderer.lastForwardedRenderPriority)
    }

    @Test
    fun aBudgetedFrameDrawsWhatItDidNotRasteriseFromAnAncestorAndSaysSo() = runTest {
        val sink = RecordingProvisionalSink()
        // Zoom out first so a coarse tile is resident and can serve as an ancestor, then zoom in
        // under a budget of zero: every tile at the new LOD has somewhere to come from, and none of
        // them is rasterised.
        val renderer = styleRenderer(
            transport = TileTransport(),
            resourceLimits = ResourceLimits(maximumTilesRasterisedPerFrame = 0),
            diagnosticSink = sink,
        ) as RenGRenderer
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val coarse = renderer.prepare(coarseBasemapPlan(frameIndex = 0L))
        renderer.draw(coarse, target)
        coarse.close()
        val rasterisedForTheCoarseFrame = renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED]

        val fine = renderer.prepare(basemapPlan(frameIndex = 1L))
        renderer.draw(fine, target)

        // ADR 0057: the budget bounds the work a frame ADDS. Nothing new was rasterised, and the
        // frame still drew -- a frame that cannot draw its ground is not an improvement on a slow one.
        assertEquals(
            rasterisedForTheCoarseFrame,
            renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED],
            "a budget of zero must rasterise nothing that has an ancestor",
        )
        val reported = sink.provisional.singleOrNull()
        assertNotNull(reported, "a frame that presented an ancestor must say so: ${sink.provisional}")
        assertTrue(reported.actual!! > 0L, "and must name how many tiles it did that for")
        fine.close()
    }

    @Test
    fun anUnbudgetedFrameSaysNothingAndRasterisesEverything() = runTest {
        val sink = RecordingProvisionalSink()
        // The default is no limit, and with no limit nothing about ADR 0057 happens at all -- which
        // is the promise that lets it ship without changing anyone's frames.
        val renderer = styleRenderer(TileTransport(), diagnosticSink = sink) as RenGRenderer
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))
        renderer.draw(frame, target)

        assertEquals(4L, renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED])
        assertEquals(emptyList(), sink.provisional, "an unbudgeted frame presents nothing provisional")
        frame.close()
    }

    @Test
    fun aBatchRasterisesEachDistinctTileOnceHoweverManyFramesShowIt() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer

        val frames = renderer.prepareBatch(
            listOf(basemapPlan(frameIndex = 0L), basemapPlan(frameIndex = 1L), basemapPlan(frameIndex = 2L)),
        )

        // ADR 0052. Twelve renders for four distinct tiles before: ADR 0046's residency filter cannot
        // see inside a batch, because nothing in a batch has been drawn.
        assertEquals(
            4L,
            renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED],
            "three frames over one camera must rasterise four tiles, not twelve",
        )

        // And every frame still carries all four, rather than pointing at the frame that rendered
        // them -- which is what makes the draw order below free.
        frames.forEach { frame ->
            assertEquals(4, (frame as RenGPreparedFrame).basemapTiles.size, "every frame carries its own ground")
        }
        // The same pixels, not copies: the batch holds one tile, three frames reference it.
        val first = (frames[0] as RenGPreparedFrame).basemapTiles.associateBy { it.key }
        (frames[2] as RenGPreparedFrame).basemapTiles.forEach { tile ->
            assertSame(first.getValue(tile.key), tile, "a memo hit must share the tile, not copy it")
        }
        frames.forEach { it.close() }
    }

    @Test
    fun aBatchsFramesDrawInAnyOrderAndTheFirstDrawnUploadsTheSharedTile() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val frames = renderer.prepareBatch(
            listOf(basemapPlan(frameIndex = 0L), basemapPlan(frameIndex = 1L)),
        )

        // Deliberately out of order, and the first frame is closed without ever being drawn. A design
        // where a later frame pointed at the tile an earlier one rendered would answer
        // RESOURCE_UNAVAILABLE here for a tile the batch definitely rendered (ADR 0052).
        frames[0].close()
        renderer.draw(frames[1], target)
        frames[1].close()

        // Still four: drawing must not have sent anything back to the engine.
        assertEquals(4L, renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED])
    }

    @Test
    fun aFrameThatPartlyOverlapsTheOneBeforeItRendersOnlyItsNewTiles() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer

        // Two cameras one tile apart at LOD 4, so the second frame shares half its tiles with the
        // first and brings half of its own. This is the realistic batch -- a moving camera -- and it
        // is the only shape that reaches the mixed return, where a frame carries both remembered and
        // freshly rendered tiles.
        val frames = renderer.prepareBatch(
            listOf(basemapPlan(frameIndex = 0L), shiftedBasemapPlan(frameIndex = 1L)),
        )

        assertEquals(
            6L,
            renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED],
            "four tiles for the first frame and only the two new ones for the second",
        )
        assertEquals(
            4,
            (frames[1] as RenGPreparedFrame).basemapTiles.size,
            "the overlapping frame must carry all four of its tiles, remembered ones included",
        )
        frames.forEach { it.close() }
    }

    @Test
    fun aBatchsMemoDoesNotSurviveIntoTheNextPreparation() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer

        renderer.prepareBatch(
            listOf(basemapPlan(frameIndex = 0L), basemapPlan(frameIndex = 1L)),
        ).forEach { it.close() }
        assertEquals(4L, renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED])

        // Nothing was drawn, so no texture is resident and ADR 0046's filter has nothing to say. A
        // memo left standing past its batch would answer instead -- and would be an unbounded store
        // of raw pixels beside the two budgets that are supposed to govern them (ADR 0052).
        renderer.prepare(basemapPlan(frameIndex = 2L)).close()

        assertEquals(
            8L,
            renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED],
            "a preparation after the batch must not reuse the batch's rendered pixels",
        )
    }

    @Test
    fun aSinglePreparationKeepsNoMemoBetweenFrames() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer

        // Outside a batch there is a draw between one frame and the next, which is what makes ADR
        // 0046's residency filter sufficient -- so a prepare that is never drawn rasterises again,
        // and the memo must not be quietly doing renderer-lifetime work it is not budgeted for.
        renderer.prepare(basemapPlan(frameIndex = 0L)).close()
        renderer.prepare(basemapPlan(frameIndex = 1L)).close()

        assertEquals(
            8L,
            renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED],
            "two undrawn preparations outside a batch each rasterise their own tiles",
        )
    }

    @Test
    fun aBatchAsksTheConsumerForEachResourceOnceRatherThanOncePerFrame() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport)

        renderer.prepareBatch(
            listOf(basemapPlan(frameIndex = 0L), basemapPlan(frameIndex = 1L), basemapPlan(frameIndex = 2L)),
        )

        val requested = transport.requestedUrls()
        // ADR 0051. One registry per frame cost 17 exchanges for these same seven resources -- about
        // five more for every frame added. One registry for the batch costs nine, about one more per
        // frame, and the seven distinct urls are unchanged: nothing new is fetched, the repeats are
        // gone.
        assertEquals(7, requested.toSet().size, "the batch must fetch no resource it did not need")
        assertEquals(9, requested.size, "each extra frame must not re-fetch what the batch already has")

        // The nine is four tiles and two sprite members exactly once each, plus the style three
        // times. A style is acquired on the resource driver's own path rather than through the
        // firewall registry, and this fixture's style declares no freshUntilEpochMillis, so every
        // frame revalidates it -- a property of the fixture, asserted here so that a change to it is
        // read as a change and not as this ADR regressing.
        assertEquals(
            3,
            requested.count { it == STYLE_URL },
            "a style with no declared freshness is revalidated once per frame",
        )
    }

    @Test
    fun aRasterisingPreparationReportsTheEnginesCountersAndACloseForgetsThem() = runTest {
        val renderer = styleRenderer(TileTransport())
        assertEquals(emptyMap(), renderer.queryMetrics().counters, "nothing has happened yet")

        renderer.prepare(basemapPlan(frameIndex = 0L))

        val metrics = renderer.queryMetrics()
        // Before ADR 0049 this was unconditionally empty: RenG installed MetricsSink.None and threw
        // every counter the engine produced away. This case lives here rather than beside the other
        // metric tests for the reason this whole suite is native-only -- androidHostTest resolves
        // skiko without its native library, so no renderer-level basemap preparation completes there.
        assertEquals(4L, metrics[RenGMetricName.ENGINE_TILES_RENDERED], "four tiles: ${metrics.counters}")
        assertTrue(metrics[RenGMetricName.ENGINE_TILE_DRAW_NANOS] > 0L, "drawing took no time at all")
        assertEquals(
            0L,
            metrics[RenGMetricName.ENGINE_METRICS_UNRECOGNISED],
            "this engine emitted a metric RenG has no name for: ${metrics.counters}",
        )
        // The outside view of ADR 0044: a frame taking the raw-pixel path encodes no PNG, so these
        // two stay at zero while four tiles are rasterised. Nothing else in the tree can observe it.
        assertEquals(0L, metrics[RenGMetricName.ENGINE_TILE_PNG_BYTES])
        assertEquals(0L, metrics[RenGMetricName.ENGINE_TILE_PNG_ENCODE_NANOS])

        renderer.close()

        // Empty rather than the final totals: a monotonic series that simply stopped moving reads as
        // a quiet renderer, which a closed one is not.
        assertEquals(emptyMap(), renderer.queryMetrics().counters)
    }

    @Test
    fun aCameraWhoseTilesAreAlreadyDrawnRasterisesNothingAndStillPlacesItsGround() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport) as RenGRenderer
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val first = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame
        assertEquals(4, first.basemapTiles.size, "the first frame rasterises every tile it selected")
        renderer.draw(first, target)
        first.close()

        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame

        assertEquals(
            emptyList(),
            second.basemapTiles,
            "every tile's texture is resident, so the engine is asked for none of them",
        )
        assertEquals(
            first.groundInstances.size,
            second.groundInstances.size,
            "the same ground is still placed from the textures already on the GPU",
        )
        second.close()
    }

    /**
     * The exact four urls Rentile composes for `{z}/{x}/{y}` at the frame's own LOD, pinned as strings.
     * `min(z, maxZoom)`, the template hash, and the `{y}`/`{-y}` distinction are all silent-failure
     * traps: get one wrong and the firewall refuses every tile, which reads as a dead basemap rather
     * than as a mismatch.
     */
    @Test
    fun preregistersTheExactTileUrlsRentileComposesRatherThanTheirShape() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(basemapPlan(frameIndex = 0L))

        assertEquals(
            listOf(
                "https://tiles.example/r/4/1/10.png",
                "https://tiles.example/r/4/1/11.png",
                "https://tiles.example/r/4/2/10.png",
                "https://tiles.example/r/4/2/11.png",
            ),
            transport.requestedUrls().filter { it.startsWith("https://tiles.example/") }.sorted(),
            "the engine reaches the consumer only through routes RenG composed identically",
        )
    }

    /**
     * A hillshade layer samples its DEM source over a 3x3 neighbourhood, so one output tile needs nine
     * source tiles. RenG does not model whether a style has a hillshade layer, so it expands **every**
     * `raster-dem` source that way; dropping the expansion leaves the engine asking for eight urls per
     * tile that no route covers, and the whole frame fails closed.
     */
    @Test
    fun expandsEveryDemSourceOverItsNeighbourhoodSoAHillshadeLayerNeverFailsClosed() = runTest {
        val transport = TileTransport(styleJson = DEM_HILLSHADE_STYLE)
        val renderer = styleRenderer(transport) as RenGRenderer

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame

        assertEquals(4, frame.basemapTiles.size, "a hillshade frame renders its ground")
        // The union of the 3x3 neighbourhoods of x in {1, 2}, y in {10, 11} at z4 is x in 0..3,
        // y in 9..12: sixteen distinct source tiles for four output tiles. That block is disjoint from
        // its own transpose too, so it catches an x/y swap on the DEM path as well as on the raster one.
        assertEquals(
            (9..12).flatMap { y -> (0..3).map { x -> "https://dem.example/4/$x/$y.png" } }.sorted(),
            transport.requestedUrls().filter { it.startsWith("https://dem.example/") }.distinct().sorted(),
            "one output tile needs its DEM tile plus its eight neighbours",
        )
    }

    /**
     * The frame that proves the style is read back from the host rather than taken from the driver's
     * compile action. With a style the transport declares fresh, frame two resolves it from residency
     * and the pure core emits **no** `CompileBasemapStyle` at all — so a renderer reading the action
     * would hold no style and render no ground from frame two onward. A single-frame test cannot see
     * this, because frame one has both.
     */
    @Test
    fun rendersGroundOnAFrameThatCompilesNoStyleBecauseTheStyleIsAlreadyResident() = runTest {
        val transport = TileTransport(styleFreshUntilEpochMillis = Long.MAX_VALUE)
        val renderer = styleRenderer(transport) as RenGRenderer

        val first = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame
        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame

        assertEquals(
            1,
            transport.requestedUrls().count { it == STYLE_URL },
            "the fixture must genuinely make frame two resident-provenance, or it proves nothing",
        )
        assertEquals(4, first.basemapTiles.size)
        assertEquals(
            first.basemapTiles.map { it.key }.sortedBy { it.stableId },
            second.basemapTiles.map { it.key }.sortedBy { it.stableId },
            "a frame that compiles no style still renders exactly the same identified ground",
        )
    }

    /**
     * The whole tile phase, twice, over a style the consumer's transport never declares fresh — which is
     * the condition under which a per-frame style *manifest* is reachable at all, and the one
     * `BasemapEngineHost`'s digest-bound manifest cache has to survive. Frame two resolves the document
     * from the transport again and the driver installs a fresh generation carrying identical bytes, so a
     * cache bound to that generation would silently reparse the entire style on every frame forever.
     *
     * That reuse is pure, so no consumer adapter can see it and no assertion here can: the reuse itself
     * is asserted at its own level, by
     * `internal.firewall.BasemapEngineHostTest.keepsTheManifestAcrossAFreshGenerationOfIdenticalBytes`.
     * What this test does own is the end of the same path a consumer actually observes — that frame two's
     * ground is still composed from a manifest describing *this* style, rather than from a stale or empty
     * one, which would reach the consumer as `AMBIGUOUS_RESOURCE_ROUTE` on every tile at once.
     *
     * The transport declares **no** `freshUntilEpochMillis`, checked here as an assertion rather than
     * assumed: a fresh style would make frame two `RESIDENT`-provenance, install no new generation, and
     * leave this test passing whether the cache worked or not.
     */
    @Test
    fun rendersTheSameGroundOnEveryFrameThatReResolvesTheSameStyleDocument() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport) as RenGRenderer

        val first = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame
        val urlsAfterFirstFrame = transport.requestedUrls()
        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame

        assertEquals(
            2,
            transport.requestedUrls().count { it == STYLE_URL },
            "the fixture must genuinely re-resolve the style on frame two, or it proves nothing",
        )
        assertEquals(
            first.basemapTiles.map { it.key }.sortedBy { it.stableId },
            second.basemapTiles.map { it.key }.sortedBy { it.stableId },
            "a re-resolved style of identical bytes renders exactly the same identified ground",
        )
        assertEquals(
            urlsAfterFirstFrame.filter { it.startsWith("https://tiles.example/") }.sorted(),
            transport.requestedUrls()
                .filter { it.startsWith("https://tiles.example/") }
                .distinct()
                .sorted(),
            "and asks for it through exactly the urls the first frame's manifest composed",
        )
    }

    /**
     * The frame on which the consumer's own style document **changes** — the thing a downstream
     * integrator does constantly while iterating on a style, and the one case where the compilation and
     * the route derivation can disagree about which document the frame is on.
     *
     * `CompileBasemapStyle` runs strictly before `InstallBasemapStyleVisibility`, so at compile time the
     * resident generation still carries the *previous* frame's bytes; the tile-time manifest is derived
     * after that install, from this frame's. A compilation taken from the resident generation therefore
     * makes the engine ask for the superseded style's tile urls while the firewall holds only the edited
     * style's, and every tile at once fails closed as `AMBIGUOUS_RESOURCE_ROUTE` — surfacing to the
     * caller as `BASEMAP_RENDER_FAILED` on frame two.
     *
     * **It has to be frame two.** The defect self-heals: by frame three the edited bytes are resident, so
     * a test that prepares two frames and only asks whether it recovers passes without the fix. What is
     * asserted here is that the *second* frame — the one that fails today — succeeds and renders from the
     * edited document.
     *
     * The transport declares **no** `freshUntilEpochMillis`, asserted rather than assumed: a fresh style
     * would make frame two `RESIDENT`-provenance, re-resolve nothing, and never deliver the edit at all.
     */
    @Test
    fun rendersTheEditedStyleOnTheVeryFrameItsBytesChange() = runTest {
        val transport = EditedStyleTileTransport()
        val renderer = styleRenderer(transport) as RenGRenderer

        renderer.prepare(basemapPlan(frameIndex = 0L))
        val firstDigest = assertNotNull(renderer.preparedBasemapStyle, "frame one holds its style").digest
        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame
        val secondDigest = assertNotNull(renderer.preparedBasemapStyle, "frame two holds its style").digest

        assertEquals(
            2,
            transport.requestedUrls().count { it == STYLE_URL },
            "the fixture declares no freshness, so frame two must genuinely re-resolve the style",
        )
        assertNotEquals(
            firstDigest,
            secondDigest,
            "frame two compiled the document it is committing, not the one resident before it",
        )
        assertEquals(4, second.basemapTiles.size, "and rendered the ground that document describes")
        assertEquals(
            listOf(
                "https://tiles.example/edited/4/1/10.png",
                "https://tiles.example/edited/4/1/11.png",
                "https://tiles.example/edited/4/2/10.png",
                "https://tiles.example/edited/4/2/11.png",
            ),
            transport.requestedUrls()
                .filter { it.startsWith("https://tiles.example/edited/") }
                .distinct()
                .sorted(),
            "through the exact urls the edited style's own tile source composes",
        )
        second.basemapTiles.forEach { rendered ->
            assertEquals(
                basemapTileKey(secondDigest, rendered.tile, TILE_OUTPUT_SIZE),
                rendered.key,
                "and every rendered tile is identified by the edited style's digest",
            )
        }
        assertEquals(
            4,
            transport.requestedUrls().count { it.startsWith("https://tiles.example/r/") },
            "the superseded style's ground is asked for on its own frame only",
        )
    }

    /**
     * The residual left by the compile-side fix, closed at the readback.
     *
     * `CompileBasemapStyle` runs before `InstallBasemapStyleVisibility`, and the steps between them can
     * fail — the style's own Store write is enough, and is what this drives. The compilation of the
     * edited document then succeeds and is retained by the host, while the *previous* document stays
     * resident because nothing installed. A later `RESIDENT`-provenance frame emits no compile action at
     * all, so the renderer reads the retained compilation back; matching on the style key alone hands it
     * the edited style's program while `renderBasemapTiles` derives routes from the resident older bytes,
     * and every tile fails closed at once — the same mismatch this task fixed, arriving by a different
     * door.
     *
     * `RESIDENT` provenance is reached here through `CACHE_ONLY`, which `residentObserved` treats as
     * resident unconditionally whenever anything is resident, and which is a public per-frame argument to
     * `prepare`. The frame is asserted to *prepare* rather than to render ground: the host honestly holds
     * no compilation of the bytes that are resident, and says so. It heals on the next frame that
     * compiles.
     */
    @Test
    fun neverPairsARetainedCompilationWithRoutesFromDifferentResidentBytes() = runTest {
        val transport = EditedStyleTileTransport()
        val store = FailingStyleWriteStore()
        val renderer = styleRenderer(transport, store) as RenGRenderer

        val first = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame
        assertEquals(4, first.basemapTiles.size, "the first document renders its ground")

        // The edited document compiles, and then its commit fails before anything installs.
        store.failStyleWrites = true
        val writeFailure = assertFailsWith<RenGException> { renderer.prepare(basemapPlan(frameIndex = 1L)) }
        assertEquals(RenGErrorCode.STORE_WRITE_FAILED, writeFailure.code)
        assertEquals(PipelineStage.STORE_WRITE, writeFailure.stage)
        store.failStyleWrites = false

        val resident = renderer.prepare(
            basemapPlan(frameIndex = 2L),
            ResourceAccessMode.CACHE_ONLY,
        ) as RenGPreparedFrame

        assertEquals(
            2,
            transport.requestedUrls().count { it == STYLE_URL },
            "a CACHE_ONLY frame must genuinely resolve the style from residency, or it proves nothing",
        )
        assertEquals(
            emptyList(),
            resident.basemapTiles,
            "a compilation of bytes that are not resident is declined rather than drawn",
        )
        assertEquals(
            emptyList(),
            transport.requestedUrls().filter { it.startsWith("https://tiles.example/edited/") },
            "so the frame never asks for the uninstalled document's ground",
        )

        val healed = renderer.prepare(basemapPlan(frameIndex = 3L)) as RenGPreparedFrame
        assertEquals(4, healed.basemapTiles.size, "and the next compiling frame renders normally again")
        assertEquals(
            listOf(
                "https://tiles.example/edited/4/1/10.png",
                "https://tiles.example/edited/4/1/11.png",
                "https://tiles.example/edited/4/2/10.png",
                "https://tiles.example/edited/4/2/11.png",
            ),
            transport.requestedUrls()
                .filter { it.startsWith("https://tiles.example/edited/") }
                .distinct()
                .sorted(),
            "from the edited document, through its own exact urls",
        )
    }

    // ---- sources that declare their tiles by reference -------------------------------------------

    /**
     * The `url` form, end to end: the style names a TileJSON document, the engine fetches it while
     * compiling, and the frame's ground is then requested through the exact urls **that document**
     * declares.
     *
     * This is not one more source shape. Across the 34 map styles Rentile is verified for, 96 sources use
     * this form and 2 use the inline one, and every one of the 34 needs at least one -- so before this
     * worked, none of them drew any ground at all. The urls are pinned as exact strings for the reason
     * the class header gives: a plausible-but-different url is refused on every tile at once.
     */
    @Test
    fun rendersGroundFromASourceThatDeclaresItsTilesByReference() = runTest {
        val transport = TileJsonTileTransport()
        val renderer = styleRenderer(transport) as RenGRenderer

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame

        assertEquals(
            1,
            transport.requestedUrls().count { it == REFERENCED_TILE_JSON_URL },
            "the TileJSON document is acquired once, through a route RenG preregistered for it",
        )
        assertEquals(4, frame.basemapTiles.size, "and the frame's ground is rendered from it")
        assertEquals(
            listOf(
                "https://tiles.example/t/4/1/10.png?key=k",
                "https://tiles.example/t/4/1/11.png?key=k",
                "https://tiles.example/t/4/2/10.png?key=k",
                "https://tiles.example/t/4/2/11.png?key=k",
            ),
            transport.requestedUrls().filter { it.startsWith("https://tiles.example/t/") }.distinct().sorted(),
            "through the exact urls the TileJSON's own template composes, credential query included",
        )
    }

    /**
     * The retention question the `url` form raises and the inline form does not.
     *
     * A `RESIDENT`-provenance frame emits no `CompileBasemapStyle` at all, so its preparation invocation
     * calls Rentile's `resolveTileJson` never and observes no TileJSON bytes whatsoever. The document's
     * facts must therefore live with the *compilation* -- which is what still holds the templates Rentile
     * derived from them -- and not with the invocation that happened to see them go past. Without that,
     * frame two derives no tile route for the source and every tile fails closed at once, on a style
     * whose only sin was being cached.
     *
     * The style is declared fresh so frame two is genuinely resident-provenance, asserted rather than
     * assumed.
     */
    @Test
    fun rendersReferencedGroundOnAFrameThatCompilesNoStyleAndSoObservesNoTileJson() = runTest {
        val transport = TileJsonTileTransport(styleFreshUntilEpochMillis = Long.MAX_VALUE)
        val renderer = styleRenderer(transport) as RenGRenderer

        val first = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame
        val urlsAfterFirstFrame = transport.requestedUrls()
        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame

        assertEquals(
            1,
            transport.requestedUrls().count { it == STYLE_URL },
            "the fixture must genuinely make frame two resident-provenance, or it proves nothing",
        )
        assertEquals(
            1,
            urlsAfterFirstFrame.count { it == REFERENCED_TILE_JSON_URL },
            "and frame one is the only frame that observes the document",
        )
        assertEquals(4, first.basemapTiles.size)
        assertEquals(
            first.basemapTiles.map { it.key }.sortedBy { it.stableId },
            second.basemapTiles.map { it.key }.sortedBy { it.stableId },
            "a frame that compiles no style still renders exactly the same referenced ground",
        )
    }

    /**
     * A TileJSON's `maxzoom` is not advisory. Past it the requested url's z, x and y are all different --
     * `min(z, maxZoom)` plus the child-scale division -- so a derivation that kept the style's looser
     * range would compose four urls the engine never asks for and omit the four it does.
     */
    @Test
    fun clampsToTheZoomRangeTheTileJsonDeclaresRatherThanTheStylesDefault() = runTest {
        val transport = TileJsonTileTransport(tileJsonMaximumZoom = 2)
        val renderer = styleRenderer(transport) as RenGRenderer

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame

        assertEquals(4, frame.basemapTiles.size, "the frame still draws its four output tiles")
        assertEquals(
            listOf("https://tiles.example/t/2/0/2.png?key=k"),
            transport.requestedUrls().filter { it.startsWith("https://tiles.example/t/") }.distinct().sorted(),
            "all four LOD 4 tiles resolve to the one z2 source tile the document's maxzoom allows",
        )
    }

    @Test
    fun aFrameThatDrawsNeitherBasemapNorLabelsRendersNoGroundAndAcquiresNothing() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport)

        val frame = renderer.prepare(
            FramePlan(
                frameIndex = 0L,
                camera = styleCamera(),
                drawBasemap = false,
                drawLabels = false,
            ),
        ) as RenGPreparedFrame

        assertEquals(emptyList(), frame.basemapTiles, "neither switch renders no ground")
        assertEquals(emptyList(), transport.requestedUrls(), "and acquires nothing at all")
    }

    /**
     * E-labels task 8b's whole point, in one frame: the *ground draw* stays gated on `drawBasemap`
     * alone while the style and the tile selection follow either switch.
     *
     * The three assertions are one claim each, and each fails for its own reason. The style url proves
     * the acquisition was widened (before the split this frame fetched nothing at all). The absent tile
     * urls and the empty `basemapTiles` prove the widening stopped short of the ground: nothing was
     * rasterized, so there is no ground texture and `groundInstances` is empty, which is exactly what
     * "labels over a caller-drawn background" has to mean.
     *
     * **`drawBasemap = true, drawLabels = true` is the symmetry point and is deliberately not the
     * subject here** — it renders the four tiles today and would keep doing so under either version of
     * the guard. `true/false` is asserted alongside, because a fix that read `drawLabels` where it
     * should have read `drawBasemap` would leave the labels working and take the ground away.
     */
    @Test
    fun aFrameThatDrawsLabelsWithoutTheBasemapAcquiresTheStyleAndStillRendersNoGround() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport)

        val labelsOnly = renderer.prepare(
            FramePlan(
                frameIndex = 0L,
                camera = styleCamera(),
                drawBasemap = false,
                drawLabels = true,
            ),
        ) as RenGPreparedFrame

        assertTrue(
            STYLE_URL in transport.requestedUrls(),
            "labels without a basemap still acquire the style they come from",
        )
        assertEquals(
            emptyList(),
            transport.requestedUrls().filter { it.startsWith("https://tiles.example/") },
            "but no ground tile is rendered, so the engine fetches none",
        )
        assertEquals(
            emptyList(),
            labelsOnly.basemapTiles,
            "the ground draw follows drawBasemap alone",
        )
        assertEquals(emptyList(), labelsOnly.groundInstances, "and it has nothing to draw")

        val basemapOnly = renderer.prepare(
            FramePlan(
                frameIndex = 1L,
                camera = styleCamera(),
                drawBasemap = true,
                drawLabels = false,
            ),
        ) as RenGPreparedFrame

        assertEquals(
            4,
            basemapOnly.basemapTiles.size,
            "and switching the labels off takes nothing away from the ground",
        )
    }
}

// ---- fixtures ------------------------------------------------------------------------------------

/** `RenderOptions.DEFAULT_OUTPUT_SIZE_PX` square — what [BasemapEngineHost] renders a tile at. */
internal val TILE_OUTPUT_SIZE: OutputPixelSize = OutputPixelSize(512, 512)

/** The 8 bytes every PNG datastream begins with (RFC 2083 section 3.1). */
internal val PNG_SIGNATURE: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal const val DEM_TILE_TEMPLATE: String = "https://dem.example/{z}/{x}/{y}.png"

/** One `raster-dem` source drawn by a hillshade layer — the only layer kind that samples a 3x3. */
internal val DEM_HILLSHADE_STYLE: String =
    """{"version":8,"name":"reng-dem-tile-test",""" +
        """"sources":{"d":{"type":"raster-dem","tiles":["$DEM_TILE_TEMPLATE"],"tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
        """{"id":"h","type":"hillshade","source":"d"}]}"""

/**
 * Serves a style document, the sprite pair, and a valid PNG for everything else, recording every url in
 * call order through the same concurrency-safe recorder `RendererBasemapStyleTest` uses — Rentile
 * fetches concurrently on `Dispatchers.Default`, so an unguarded list loses entries.
 *
 * [styleFreshUntilEpochMillis] is declared on the **style response only**: it is what makes a later
 * frame resolve the style from residency instead of re-fetching it, which is the one condition under
 * which the pure core emits no `CompileBasemapStyle`.
 */
/**
 * [styleCamera] moved one LOD-4 tile east: `unwrappedLongitude` -135 places the camera on the x = 2.0
 * tile boundary, and -112.5 places it on x = 3.0, so the two frames share their y range and half
 * their x range.
 */
private fun shiftedBasemapPlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(
        latitude = -55.0,
        unwrappedLongitude = -112.5,
        zoom = 4.0,
        bearing = 0.0,
        pitch = 0.0,
    ),
    drawBasemap = true,
)

/** A camera two LODs out from [styleCamera], so its tiles are ancestors of that camera's. */
private fun coarseBasemapPlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(
        latitude = -55.0,
        unwrappedLongitude = -135.0,
        zoom = 2.0,
        bearing = 0.0,
        pitch = 0.0,
    ),
    drawBasemap = true,
)

/** Keeps only the one code ADR 0057 adds, so an unrelated warning cannot satisfy these cases. */
private class RecordingProvisionalSink : DiagnosticSink {
    private val recorded = mutableListOf<Diagnostic>()

    val provisional: List<Diagnostic>
        get() = recorded.filter { it.code == DiagnosticCode.GROUND_PRESENTED_PROVISIONALLY }

    override fun emit(diagnostic: Diagnostic) {
        recorded += diagnostic
    }
}

internal class TileTransport(
    private val styleJson: String = STYLE_WITH_SPRITE_JSON,
    private val styleFreshUntilEpochMillis: Long? = null,
) : Transport {
    private val recorded = ConcurrentRecorder<String>()

    suspend fun requestedUrls(): List<String> = recorded.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = styleJson.encodeToByteArray(),
                metadata = TransportResponseMetadata(
                    contentType = "application/json",
                    freshUntilEpochMillis = styleFreshUntilEpochMillis,
                ),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = STYLE_TEST_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }
}

/** [STYLE_TILE_TEMPLATE] edited, so the same style url serves a genuinely different ground source. */
internal const val EDITED_STYLE_TILE_TEMPLATE: String = "https://tiles.example/edited/{z}/{x}/{y}.png"

/**
 * [STYLE_WITH_SPRITE_JSON] with its raster source repointed. Only the tile template moves, which is
 * enough to change both the compiled style's digest and every tile url the engine composes.
 */
internal val EDITED_STYLE_WITH_SPRITE_JSON: String =
    STYLE_WITH_SPRITE_JSON.replace(STYLE_TILE_TEMPLATE, EDITED_STYLE_TILE_TEMPLATE)

/**
 * A [TileTransport] whose style document is **edited between frames**: the first request for
 * [STYLE_URL] answers [STYLE_WITH_SPRITE_JSON] and every later one answers
 * [EDITED_STYLE_WITH_SPRITE_JSON], modelling a consumer saving a change to their own style.
 *
 * Which body to serve is decided from the recorder's own snapshot, taken under the same lock the append
 * took, so the decision is ordered with respect to every other recorded call rather than read off an
 * unguarded counter.
 *
 * Declares no `freshUntilEpochMillis` at all: a fresh style would be resolved from residency on frame
 * two and the edit would never be delivered.
 */
internal class EditedStyleTileTransport : Transport {
    private val recorded = ConcurrentRecorder<String>()

    suspend fun requestedUrls(): List<String> = recorded.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = if (recorded.snapshot().count { it == STYLE_URL } <= 1) {
                    STYLE_WITH_SPRITE_JSON.encodeToByteArray()
                } else {
                    EDITED_STYLE_WITH_SPRITE_JSON.encodeToByteArray()
                },
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = STYLE_TEST_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }
}

/**
 * Persists every write and answers reads from them, so a `CACHE_ONLY` frame is genuinely served rather
 * than failing for want of content — without which a test cannot tell a route mismatch from an empty
 * store. [failStyleWrites] fails the style's own Store write only, which is the smallest deterministic
 * way to make a frame compile its style and then abandon the commit before anything installs.
 *
 * A [Mutex] rather than a plain map because the engine writes its sprite pair and its ground tiles from
 * concurrent coroutines on `Dispatchers.Default`, exactly as [ConcurrentRecorder] documents.
 */
internal class FailingStyleWriteStore : Store {
    private val mutex = Mutex()
    private val entries = mutableMapOf<RawResourceKey, StoredRawResource>()

    var failStyleWrites: Boolean = false

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock { entries[key] }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        if (failStyleWrites && key.resourceClass == ResourceClass.BASEMAP_STYLE) {
            throw IllegalStateException("consumer store is out of space")
        }
        mutex.withLock { entries[key] = resource }
    }
}

// ---- the `url`-form fixture ----------------------------------------------------------------------

internal const val REFERENCED_TILE_JSON_URL: String = "https://tiles.example/ref/tiles.json?key=k"

/**
 * A style whose only tile source declares its tiles by reference, plus the sprite that makes the engine
 * fetch something else while compiling -- so the TileJSON is one of several style-time acquisitions
 * rather than the only one.
 */
internal val REFERENCED_TILE_STYLE: String =
    """{"version":8,"name":"reng-tilejson-tile-test",""" +
        """"sprite":"$SPRITE_BASE_URL",""" +
        """"sources":{"s":{"type":"raster","url":"$REFERENCED_TILE_JSON_URL","tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-pattern":"dot"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/**
 * Serves [REFERENCED_TILE_STYLE], the TileJSON document it names, the sprite pair, and a valid PNG for
 * every tile. Shaped like the 16 documents the verified corpus actually names: an absolute template
 * carrying the account key in its query, and an explicit zoom range.
 */
internal class TileJsonTileTransport(
    private val styleFreshUntilEpochMillis: Long? = null,
    private val tileJsonMaximumZoom: Int = 14,
) : Transport {
    private val recorded = ConcurrentRecorder<String>()

    suspend fun requestedUrls(): List<String> = recorded.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = REFERENCED_TILE_STYLE.encodeToByteArray(),
                metadata = TransportResponseMetadata(
                    contentType = "application/json",
                    freshUntilEpochMillis = styleFreshUntilEpochMillis,
                ),
            )
            REFERENCED_TILE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = (
                    """{"tilejson":"2.0.0",""" +
                        """"tiles":["https://tiles.example/t/{z}/{x}/{y}.png?key=k"],""" +
                        """"minzoom":0,"maxzoom":$tileJsonMaximumZoom}"""
                    ).encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = STYLE_TEST_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }
}
