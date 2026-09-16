package com.rohittp.reng

import com.rohittp.reng.internal.firewall.LABEL_GLYPH_TEMPLATE
import com.rohittp.reng.internal.firewall.LABEL_SANS_STACK
import com.rohittp.reng.internal.firewall.LABEL_SERIF_STACK
import com.rohittp.reng.internal.firewall.LABEL_TILE_TEMPLATE
import com.rohittp.reng.internal.firewall.VALID_TILE_PNG
import com.rohittp.reng.internal.firewall.labelGlyphRange
import com.rohittp.reng.internal.firewall.labelGlyphUrls
import com.rohittp.reng.internal.firewall.labelMvtBytes
import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.label.FadedLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * E-labels task 23: the Label handover is retained across frames, keyed on Rentile's own
 * `labelCandidateRequestKey`, and invalidated when that key moves.
 *
 * **What was measured, and why it is the cycle's largest cost.** On a real CGL context with a
 * stationary camera, `prepare()` cost 0.05-0.15 ms drawing nothing, 93-102 ms drawing the ground alone,
 * and **514-520 ms drawing labels alone** over the same 17 tiles. Nothing was retained: three
 * acquisitions of an identical tile set asked the consumer's `Transport` for all 17 label tiles and all
 * 16 Glyph Ranges every time -- 17 -> 34 -> 51 and 16 -> 32 -> 48 -- and re-packed a **byte-identical**
 * atlas, the same `contentKey` all three times.
 *
 * **Every assertion here counts requests rather than measuring time**, which is the whole point of the
 * task's own vacuity warning: "the second acquisition is faster" measures a warm JIT as readily as a
 * cache, and would pass on a machine under load with the retention deleted. What a cache is *for* is
 * that the consumer's adapters are not asked again, and that is a count.
 *
 * **Native-only for the reason `RendererBasemapTileTest` and `LabelHandoverBatchTest` are**:
 * `acquireLabelCandidates` ends in Rentile's Skia glyph packer, and this project's `androidHostTest`
 * runtime resolves skiko's API without its native library, so no batch can be obtained there at all.
 * The GL side runs against [RecordingGlBinding] rather than a real context, deliberately -- what
 * [aSecondDrawOfTheSameAtlasUploadsNoTextureAgain] asserts is *how many times RenG uploads*, which is a
 * call-count property a fake states far more precisely than a driver does and which no pixel readback
 * can see at all.
 *
 * **The two ways a cache test passes for the wrong reason, and how each is closed here.**
 * - *A one-tile fixture cannot tell "cached the batch" from "cached nothing and the second call was
 *   cheap".* Every frame below selects **twenty-five** tiles -- the nine its camera covers plus ADR
 *   0070's ring of one -- and the tile assertions are exact sorted lists of twenty-five urls rather
 *   than counts or set memberships, so a cache that retained twenty-four of them, or that
 *   re-fetched one, fails.
 * - *Asserting a hit by object identity passes even if the cache serves a stale batch for a changed
 *   key.* So no assertion here compares object identity. The hit is asserted as request counts plus the
 *   frame's own placed labels; the miss as the *new* tile set being fetched plus the frame sampling
 *   **different glyph cells**, which it can only do if its candidates came from the new tiles. Each
 *   tile carries its own letter and the two tile sets are disjoint, which is what makes that hold.
 */
class RendererLabelResidencyTest {

    /**
     * The headline. Two frames, one camera, one round of consumer traffic.
     *
     * The glyph half is asserted beside the tile half rather than folded into it, because the two fail
     * separately: the tiles are fetched by `planLabelCandidates` and the ranges by
     * `acquireLabelCandidates`, over two different preregistration rounds, and a retention keyed
     * anywhere downstream of the plan would still pay the tiles.
     */
    @Test
    fun aSecondPrepareOverAnUnchangedCameraAsksTheConsumerForNothingAgain() = runTest {
        val transport = ResidencyTransport()
        val renderer = residencyRenderer(transport)

        val first = renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA())) as RenGPreparedFrame
        val second = renderer.prepare(residencyPlan(frameIndex = 1L, camera = cameraA())) as RenGPreparedFrame

        assertEquals(
            TILE_URLS_A,
            transport.tileUrls(),
            "twenty-five label tiles reach the consumer once across two frames, not fifty times",
        )
        assertEquals(
            GLYPH_URLS_A.sorted(),
            transport.glyphUrls(),
            "and the closure's three Glyph Ranges once, not six times",
        )

        val firstLabels = assertNotNull(first.labels, "the frame keeps at least one label")
        val secondLabels = assertNotNull(second.labels, "and so does the frame served from the retention")
        assertEquals(
            firstLabels.atlasKey,
            secondLabels.atlasKey,
            "the retained batch carries the same atlas, by content rather than by object identity",
        )
        assertEquals(
            firstLabels.labels.map { placedShape(it) },
            secondLabels.labels.map { placedShape(it) },
            "and the same labels, from the same candidates, at the same anchors, in the same order",
        )
    }

    /**
     * The half where caches actually fail. A camera that moves onto a **disjoint** tile set must
     * re-acquire, and this case is what a deleted invalidation fails: the case above passes with the
     * key comparison replaced by `true`, and this one cannot.
     *
     * Three assertions of content, at three depths, because a wrong cache can be wrong at any of them:
     * the quads' atlas coordinates ([cameraA]'s centre tile spells `V` and [cameraB]'s spells `Q`), the
     * atlas identity, and the decoded atlas pixels themselves. Fetching the new tiles is not enough on
     * its own -- a renderer that re-fetched and then handed back the *retained* batch anyway would
     * satisfy that alone -- and the last of the three is what gates the decode memo, which is keyed by
     * atlas content rather than by the handover key and would otherwise hand the moved frame the first
     * frame's pixels under the moved frame's own key.
     *
     * **That the two atlases differ at all is something the fixture had to be built for**, and the
     * naive assertion fails: Rentile packs every glyph of every Glyph Range it fetched rather than only
     * the ones its candidates used, so two tile sets needing the same ranges pack a byte-identical
     * atlas however different their letters are. That was measured here by asserting it and watching it
     * fail -- see [residencyTileBytes], which gives [cameraB]'s tiles a fourth range of their own.
     */
    /**
     * ADR 0070 end to end, and the two halves are what make it a claim rather than an observation.
     *
     * A camera one tile east selects a **different** ground tile set -- that is the condition under
     * which every release before this one re-acquired every label tile and every Glyph Range -- and
     * the margin already covers it, so the consumer is asked for nothing at all. Three tiles east is
     * outside the margin, and the traffic returns, which is what says the first half is the mechanism
     * working rather than a renderer that has stopped asking for anything.
     */
    @Test
    fun aCameraThatMovesInsideTheMarginAsksTheConsumerForNothing() = runTest {
        val transport = ResidencyTransport()
        val renderer = residencyRenderer(transport)

        renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA()))
        val afterFirst = transport.tileUrls()
        assertEquals(TILE_URLS_A, afterFirst, "the first frame fetches its own twenty-five")

        renderer.prepare(residencyPlan(frameIndex = 1L, camera = cameraEastOfA(tiles = 1)))
        assertEquals(
            afterFirst,
            transport.tileUrls(),
            "a camera one tile east selects different ground tiles and fetches no label tile at all",
        )

        renderer.prepare(residencyPlan(frameIndex = 2L, camera = cameraEastOfA(tiles = 3)))
        assertTrue(
            transport.tileUrls().size > afterFirst.size,
            "and three tiles east is past the margin, so the traffic returns",
        )
    }

    @Test
    fun aCameraMoveOntoADifferentTileSetReAcquiresRatherThanServingTheRetainedBatch() = runTest {
        val transport = ResidencyTransport()
        val renderer = residencyRenderer(transport)

        val first = renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA())) as RenGPreparedFrame
        renderer.prepare(residencyPlan(frameIndex = 1L, camera = cameraA()))
        val moved = renderer.prepare(residencyPlan(frameIndex = 2L, camera = cameraB())) as RenGPreparedFrame

        assertEquals(
            (TILE_URLS_A + TILE_URLS_B).sorted(),
            transport.tileUrls(),
            "the moved frame fetches its own twenty-five tiles, and the unmoved one fetched none",
        )
        assertEquals(
            (GLYPH_URLS_A + GLYPH_URLS_B).sorted(),
            transport.glyphUrls(),
            "two acquisitions across three frames, and the moved one needs a fourth range of its own",
        )

        val firstLabels = assertNotNull(first.labels)
        val movedLabels = assertNotNull(moved.labels)
        assertNotEquals(
            firstLabels.labels.map { sampledCells(it) },
            movedLabels.labels.map { sampledCells(it) },
            "the moved frame samples its own tile set's glyph cells rather than the retained batch's",
        )
        assertNotEquals(
            firstLabels.atlasKey,
            movedLabels.atlasKey,
            "and names its own atlas, which the fourth Glyph Range makes a different one",
        )
        assertNotEquals(
            firstLabels.atlas.rgbaSnapshot().toList(),
            movedLabels.atlas.rgbaSnapshot().toList(),
            "and carries that atlas's pixels rather than the ones already decoded for the first frame " +
                "-- the decode memo is keyed by atlas content, and this is what proves it reads the key",
        )
    }

    /**
     * The GPU half, which task 20 already got right and which this task must not be credited with.
     *
     * `uploadGlyphAtlas` leases a resident texture under `ResourceKeyDeriver.glyphAtlas`, a key derived
     * from Rentile's atlas digest, so a second draw of the same atlas uploads nothing -- and did so
     * before this task existed, because the engine repacked byte-identical bytes under an identical
     * digest. Pinned here so the claim is measured rather than assumed, and so that a later change to
     * the atlas key is caught by an assertion rather than by a frame rate.
     *
     * `drawBasemap = false`, so the atlas is the **only** texture in the frame and the count is exact
     * rather than a difference between two larger numbers.
     */
    @Test
    fun aSecondDrawOfTheSameAtlasUploadsNoTextureAgain() = runTest {
        val binding = residencyGlBinding()
        val renderer = residencyRenderer(ResidencyTransport(), binding)
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val first = renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA()))
        val second = renderer.prepare(residencyPlan(frameIndex = 1L, camera = cameraA()))

        binding.log.clear()
        renderer.draw(first, target)
        assertEquals(
            1,
            binding.log.count { it == "genTextures(1)" },
            "a label frame that draws no ground uploads exactly one texture: its glyph atlas",
        )

        binding.log.clear()
        renderer.draw(second, target)
        assertEquals(
            0,
            binding.log.count { it == "genTextures(1)" },
            "and the second draw over the same atlas uploads nothing at all",
        )
        assertEquals(
            0,
            binding.log.count { it.startsWith("deleteTextures") },
            "nor evicts the atlas it is still drawing with",
        )
    }

    /**
     * The frame served from the retention keeps its **icons**, which is the one way this cache could
     * have changed how the map looks rather than only how fast it is produced.
     *
     * `SpriteAtlasManifest` is readable only from inside the invocation that proxied the sprite pair,
     * and on a frame whose style was compiled earlier the Label acquisition is the last thing in that
     * invocation that makes the engine ask for it. So a retention that reused the batch and then read
     * the manifest fresh would find `null` on every cached frame, `resolveIcon` would drop every icon,
     * and the map would quietly lose its markers on frame two -- with no failure, no diagnostic, and
     * every request-count assertion in this file still green. Retaining the manifest beside the batch is
     * what prevents that, and this is the case that measures it.
     *
     * Asserted as the icons the *placed labels carry*, not as sprite traffic, because the defect is
     * about the frame rather than about the fetch.
     */
    @Test
    fun aFrameServedFromTheRetentionStillResolvesItsIcons() = runTest {
        val transport = ResidencyTransport(styleJson = ICON_STYLE_JSON)
        val renderer = residencyRenderer(transport)

        val first = renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA())) as RenGPreparedFrame
        val second = renderer.prepare(residencyPlan(frameIndex = 1L, camera = cameraA())) as RenGPreparedFrame

        val firstIcons = assertNotNull(first.labels).labels.count { it.label.icon != null }
        assertTrue(firstIcons > 0, "the fixture's sprite resolves at least one icon on the first frame")
        assertEquals(
            firstIcons,
            assertNotNull(second.labels).labels.count { it.label.icon != null },
            "and the frame served from the retention resolves exactly the same icons",
        )
        assertEquals(
            TILE_URLS_A,
            transport.tileUrls(),
            "while still asking the consumer for nothing a second time",
        )
    }

    /**
     * The aggregate exclusion diagnostic is emitted **once per prepare**, on the frame served from the
     * retention exactly as on the frame that acquired.
     *
     * ADR 0036 says "once per prepare", and before this task that was free -- the handover ran once per
     * prepare, so an aggregate beside it did too. A retention makes it a choice, and reporting only on
     * the acquiring frame would be the wrong one: the aggregate describes the label content **this
     * frame is drawing**, not the exchange that fetched it, so a consumer whose warning appeared on
     * frame 0 and vanished on frame 1 would be reading RenG's cache state rather than its own style.
     *
     * Arabic feature text is what makes the engine exclude anything at all -- the style is unchanged
     * and only the tile differs, which is `LabelIntegrationReadbackSuite`'s own arrangement.
     */
    @Test
    fun theExclusionAggregateIsReportedOnACachedFrameToo() = runTest {
        val sink = RecordingResidencySink()
        val transport = ResidencyTransport(tileBytes = { ARABIC_MVT_BYTES })
        val renderer = residencyRenderer(transport, diagnosticSink = sink)

        renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA()))
        renderer.prepare(residencyPlan(frameIndex = 1L, camera = cameraA()))

        assertEquals(
            List(2) {
                Diagnostic(
                    code = DiagnosticCode.LABEL_CONTENT_EXCLUDED,
                    severity = DiagnosticSeverity.INFO,
                    stage = PipelineStage.LABEL_PREPARATION,
                )
            },
            sink.diagnostics,
            "two prepares, two aggregates -- the cached frame reports what it is drawing",
        )
        assertEquals(
            TILE_URLS_A,
            transport.tileUrls(),
            "and the second of them was served from the retention rather than re-fetched",
        )
    }

    /**
     * `clearFrameHistory()` clears history and is not a cache invalidation, which is a decision rather
     * than an accident and is therefore pinned.
     *
     * ADR 0035's rule -- after a history clear the render is a pure function of the plan -- is untouched
     * by leaving this standing, because the retention cannot change what a frame looks like: it decides
     * only whether the consumer's `Transport` is asked for bytes RenG already holds. `CONTEXT.md` says
     * the same thing from the other side, that a history clear "neither frees resources nor invalidates
     * prepared frames", and lists "Cache" among the words Frame History is not.
     */
    @Test
    fun clearingFrameHistoryLeavesTheRetainedHandoverStanding() = runTest {
        val transport = ResidencyTransport()
        val renderer = residencyRenderer(transport)

        renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA()))
        renderer.clearFrameHistory()
        val after = renderer.prepare(residencyPlan(frameIndex = 0L, camera = cameraA())) as RenGPreparedFrame

        assertEquals(
            TILE_URLS_A,
            transport.tileUrls(),
            "a history clear does not send the frame back to the consumer for tiles RenG still holds",
        )
        assertEquals(GLYPH_URLS_A.sorted(), transport.glyphUrls())
        assertTrue(
            assertNotNull(after.labels).labels.isNotEmpty(),
            "and the frame after it still draws its labels",
        )
    }
}

/**
 * What a placed label is, for an equality assertion: which candidate of the batch it came from, where
 * on screen it landed, and how many glyph quads it produced.
 *
 * Deliberately not its opacity. The fade advances on every successful `prepare`, so two frames over one
 * retained batch legitimately paint the same label at two different opacities -- comparing the whole
 * [FadedLabel] would fail for a reason that has nothing to do with the cache, and comparing only what
 * the fade does not touch is what makes this an assertion about the batch.
 */
private fun placedShape(faded: FadedLabel): List<Double> = listOf(
    faded.label.candidateIndex.toDouble(),
    faded.label.anchorPixelX,
    faded.label.anchorPixelY,
    faded.quads.size.toDouble(),
)

/**
 * Which cells of the glyph atlas one placed label samples, as the quads' own texture coordinates.
 *
 * The frame's content as the *draw* sees it, and the half a stale batch served under a fresh key would
 * get wrong even while the atlas beside it was right.
 */
private fun sampledCells(faded: FadedLabel): List<List<Float>> =
    faded.quads.map { it.cornersUv.toList() }

// ---- the fixture ---------------------------------------------------------------------------------

private const val RESIDENCY_STYLE_URL: String = "https://styles.example/residency-labels.json"

/** `https://tiles.example/l/`, the part of [LABEL_TILE_TEMPLATE] every label tile url starts with. */
private val TILE_PREFIX: String = LABEL_TILE_TEMPLATE.substringBefore("{z}")

/** `https://glyphs.example/`, the same for a Glyph Range. */
private val GLYPH_PREFIX: String = LABEL_GLYPH_TEMPLATE.substringBefore("{fontstack}")

/**
 * 640 pixels at zoom 4, where one tile is 512 logical pixels, so a viewport centred on a tile's centre
 * reaches 64 pixels into each of its neighbours and the frame selects a **3x3** block.
 *
 * Nine rather than one is the point. A single-tile fixture cannot distinguish a retained batch from a
 * second call that was merely cheap, and cannot fail at all if a retention drops some of its tiles.
 */
private const val RESIDENCY_PIXELS: Int = 640

/**
 * The centre of tile `(z = 4, x = 3, y = 6)`.
 *
 * Centred on a tile rather than on a tile corner so that **one** feature -- the centre tile's -- is on
 * screen and the frame therefore keeps a label: every neighbour's copy sits 512 logical pixels away, off
 * a 640-pixel frame. `x = 3, y = 6` is disjoint from its own transpose, so a transposed tile index
 * cannot produce this selection.
 */
private fun cameraA(): Camera = Camera(
    latitude = 31.952162238024975,
    unwrappedLongitude = -101.25,
    zoom = 4.0,
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * The centre of tile `(z = 4, x = 12, y = 10)`, whose 3x3 block is **disjoint** from [cameraA]'s in both
 * axes -- `x in 11..13, y in 9..11` against `x in 2..4, y in 5..7`.
 *
 * Disjoint rather than adjacent on purpose: an overlapping move would let a partially correct
 * invalidation pass, because some of the second frame's tiles would legitimately be absent from the
 * second round of traffic. With no overlap, every one of the twenty-five is either fetched or not.
 */
/**
 * [cameraA] moved [tiles] whole tiles east at zoom 4, where a tile spans `360 / 16` degrees. One tile
 * moves the three-by-three ground selection by a column while staying inside ADR 0070's margin; three
 * moves it clear of the margin entirely.
 */
private fun cameraEastOfA(tiles: Int): Camera = Camera(
    latitude = cameraA().latitude,
    unwrappedLongitude = cameraA().unwrappedLongitude + tiles * (360.0 / 16.0),
    zoom = 4.0,
    bearing = 0.0,
    pitch = 0.0,
)

private fun cameraB(): Camera = Camera(
    latitude = -48.92249926375824,
    unwrappedLongitude = 101.25,
    zoom = 4.0,
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * `drawBasemap = false, drawLabels = true`: the label handover is the only thing in the frame, so the
 * tile urls the consumer is asked for are the label path's and nothing else. A frame that drew the
 * ground too would fetch the same tile set through the raster path and make every count ambiguous.
 */
private fun residencyPlan(frameIndex: Long, camera: Camera): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = camera,
    drawBasemap = false,
    drawLabels = true,
)

private val RESIDENCY_STYLE_JSON: String =
    """{"version":8,"name":"reng-label-residency",""" +
        """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" +
        """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14}},""" +
        """"layers":[""" +
        """{"id":"place","type":"symbol","source":"v","source-layer":"place",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SANS_STACK"],"text-size":16},""" +
        """"paint":{"text-color":"#ff00ff"}},""" +
        """{"id":"town","type":"symbol","source":"v","source-layer":"town_label",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SERIF_STACK"],"text-size":16},""" +
        """"paint":{"text-color":"#ffaa00","text-translate":[64,0]}}""" +
        """]}"""

/** The sprite base Rentile appends `.json` and `.png` to, exactly as `appendSpriteExtension` does. */
private const val RESIDENCY_SPRITE_BASE: String = "https://sprites.example/residency"

/** One entry filling the whole 2-by-2 atlas image, so `icon-size` alone decides its screen extent. */
private val RESIDENCY_SPRITE_JSON: ByteArray =
    """{"marker":{"x":0,"y":0,"width":2,"height":2}}""".encodeToByteArray()

/**
 * The same style with a sprite and an `icon-image` on its `place` layer, so that a placed label carries
 * an icon whose only possible source is a `SpriteAtlasManifest`.
 *
 * `symbol-sort-key` is load-bearing rather than decoration, and measuring it is how that was learnt:
 * without it the `town` layer's symbol reaches the collision index first, the icon-bearing `place`
 * symbol loses its place entirely, and the frame carries one label and **zero** icons -- which would
 * have made [aFrameServedFromTheRetentionStillResolvesItsIcons] pass its equality check on `0 == 0` and
 * gate nothing at all. The `assertTrue(firstIcons > 0)` guard beside that equality is what caught it.
 */
private val ICON_STYLE_JSON: String =
    """{"version":8,"name":"reng-label-residency-icons",""" +
        """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" +
        """"sprite":"$RESIDENCY_SPRITE_BASE",""" +
        """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14}},""" +
        """"layers":[""" +
        """{"id":"place","type":"symbol","source":"v","source-layer":"place",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SANS_STACK"],"text-size":16,""" +
        """"symbol-sort-key":10,"icon-image":"marker","icon-size":60},""" +
        """"paint":{"text-color":"#ff00ff"}},""" +
        """{"id":"town","type":"symbol","source":"v","source-layer":"town_label",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SERIF_STACK"],"text-size":16},""" +
        """"paint":{"text-color":"#ffaa00","text-translate":[64,0]}}""" +
        """]}"""

/**
 * The tiles [cameraA]'s label acquisition asks for: the three-by-three its camera covers, widened to
 * five-by-five by ADR 0070's ring of one. Written as the exact urls the engine composes for them.
 */
private val TILE_URLS_A: List<String> =
    (1..5).flatMap { x -> (4..8).map { y -> "${TILE_PREFIX}4/$x/$y.pbf" } }.sorted()

/**
 * The same for [cameraB]. **Sharing no url with [TILE_URLS_A] is what the assertions rest on**, and the
 * ring does not endanger it: the two cameras are eight tiles apart in `x` and one tile of margin each
 * leaves them six apart, so the widened sets are still disjoint.
 */
private val TILE_URLS_B: List<String> =
    (10..14).flatMap { x -> (8..12).map { y -> "${TILE_PREFIX}4/$x/$y.pbf" } }.sorted()

/**
 * The letter tile `(x, y)` carries, which is what makes two tile sets produce two different atlases.
 *
 * `A + (x + 3y) mod 26` gives [TILE_URLS_A]'s twenty-five tiles the letters `N..Z` and `A..D`, and
 * [TILE_URLS_B]'s `I..Y` -- overlapping ranges but different *sets*, so the glyphs Rentile packs differ
 * between them and its own
 * `LabelCandidateBatch.contentKey` differs with them. Every candidate in the batch contributes, not only
 * the on-screen one, because the closure is frozen over every tile the plan was handed.
 */
private fun tileLetter(x: Int, y: Int): Char = 'A' + ((x + 3 * y) % 26)

/**
 * One vector tile per url: a `place` feature carrying that tile's own letter plus `U+0100`, plus
 * `U+0200` in the eastern half of the world, and a `town_label` feature carrying a constant `B`.
 *
 * **`U+0200` is the whole reason the two tile sets need different Glyph Closures**, and without it this
 * fixture could not distinguish two atlases at all. Rentile packs every glyph of every Glyph Range it
 * fetched rather than only the ones its candidates used -- measured here, by asserting the opposite and
 * watching it fail -- so two tile sets needing the same three ranges pack a **byte-identical** atlas
 * under one `contentKey` however different their letters are. [TILE_URLS_B]'s tiles all sit at
 * `x >= 8`, so they alone need the sans `512-767` range, and that is what makes the moved frame's atlas
 * a genuinely different one rather than the same one sampled differently.
 */
private fun residencyTileBytes(url: String): ByteArray {
    val path = url.removePrefix(TILE_PREFIX).removeSuffix(".pbf").split("/")
    val x = path[1].toInt()
    val y = path[2].toInt()
    val eastern = if (x >= EASTERN_TILE_X) "Ȁ" else ""
    return labelMvtBytes("place" to "${tileLetter(x, y)}Ā$eastern", "town_label" to "B")
}

/** The tile column at and beyond which a tile's `place` feature also carries `U+0200`. */
private const val EASTERN_TILE_X: Int = 8

/**
 * A saturated distance field, for the reason `LabelIntegrationReadbackSuite` gives: the shared fixture's
 * default ramps to 191, which is the fill edge itself, so no drawn pixel ever reaches full coverage.
 * Nothing here reads a pixel, but [aSecondDrawOfTheSameAtlasUploadsNoTextureAgain] does issue a real
 * draw, and a fixture that is honest about what it hands the pipeline costs nothing.
 */
private val SATURATED: (Int) -> Byte = { 0xFF.toByte() }

/** `A` through `Z`, so every tile's letter is in the range whatever [tileLetter] answers. */
private val SANS_RANGE_0: ByteArray =
    labelGlyphRange(LABEL_SANS_STACK, "0-255", (65..90).toList(), SATURATED)

private val SANS_RANGE_256: ByteArray =
    labelGlyphRange(LABEL_SANS_STACK, "256-511", listOf(256), SATURATED)

/** The fourth range, which only [TILE_URLS_B]'s tiles ask for. */
private val SANS_RANGE_512: ByteArray =
    labelGlyphRange(LABEL_SANS_STACK, "512-767", listOf(512), SATURATED)

private val SERIF_RANGE_0: ByteArray =
    labelGlyphRange(LABEL_SERIF_STACK, "0-255", listOf(66), SATURATED)

/**
 * The fourth Glyph Range's url, a literal for the same reason [labelGlyphUrls] is one: composing it
 * here would restate the composition the firewall exists to reproduce.
 */
private const val SANS_512_URL: String =
    "https://glyphs.example/Label%20Sans%20Regular/512-767.pbf?key=reng-live-key"

/** The three ranges [cameraA]'s tiles need, and the four [cameraB]'s do. */
private val GLYPH_URLS_A: List<String> = labelGlyphUrls()

private val GLYPH_URLS_B: List<String> = labelGlyphUrls() + SANS_512_URL

/**
 * Answers the style, every label tile the frames select, and the three Glyph Ranges, recording every url
 * it was asked for.
 *
 * [ConcurrentRecorder] rather than a list: Rentile acquires tiles and ranges as concurrent
 * `Dispatchers.Default` children, and a lost update here would silently turn "fetched twice" into
 * "fetched once" -- which is the exact regression these tests exist to catch.
 */
private class ResidencyTransport(
    private val styleJson: String = RESIDENCY_STYLE_JSON,
    private val tileBytes: (String) -> ByteArray = ::residencyTileBytes,
) : Transport {
    private val recorded = ConcurrentRecorder<String>()

    /** Every label tile url asked for, in sorted order, **with duplicates kept**. */
    suspend fun tileUrls(): List<String> = recorded.snapshot().filter { it.startsWith(TILE_PREFIX) }.sorted()

    /** Every Glyph Range url asked for, sorted, with duplicates kept. */
    suspend fun glyphUrls(): List<String> = recorded.snapshot().filter { it.startsWith(GLYPH_PREFIX) }.sorted()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        val body = when {
            url == RESIDENCY_STYLE_URL -> styleJson.encodeToByteArray()
            url == "$RESIDENCY_SPRITE_BASE.json" -> RESIDENCY_SPRITE_JSON
            url == "$RESIDENCY_SPRITE_BASE.png" -> VALID_TILE_PNG
            url.startsWith(TILE_PREFIX) -> tileBytes(url)
            url == labelGlyphUrls()[0] -> SANS_RANGE_0
            url == labelGlyphUrls()[1] -> SANS_RANGE_256
            url == labelGlyphUrls()[2] -> SERIF_RANGE_0
            url == SANS_512_URL -> SANS_RANGE_512
            else -> null
        } ?: return TransportResponse(statusCode = 404, body = ByteArray(0))
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "application/octet-stream"),
        )
    }
}

/**
 * The same two source layers with Arabic feature text, which the engine cannot shape and therefore
 * excludes. The style is unchanged: only what the tile says differs.
 */
private val ARABIC_MVT_BYTES: ByteArray =
    labelMvtBytes("place" to "\u0645\u0631\u062D\u0628\u0627", "town_label" to "\u0634\u0627\u0631\u0639")

/** Every RenG diagnostic this renderer emitted, in order. */
private class RecordingResidencySink : DiagnosticSink {
    private val recorded: MutableList<Diagnostic> = mutableListOf()

    val diagnostics: List<Diagnostic> get() = ArrayList(recorded)

    override fun emit(diagnostic: Diagnostic) {
        recorded += diagnostic
    }
}

/** Every read a miss and every write accepted: nothing here is about persistence. */
private class ResidencyStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = Unit
}

private fun residencyGlBinding(): RecordingGlBinding = styleGlBinding()

private fun residencyRenderer(
    transport: Transport,
    binding: RecordingGlBinding = residencyGlBinding(),
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
): Renderer = createRenderer(
    RendererConfiguration(
        outputPixelSize = OutputPixelSize(RESIDENCY_PIXELS, RESIDENCY_PIXELS),
        transport = transport,
        store = ResidencyStore(),
        basemapStyle = ResourceLocator(RESIDENCY_STYLE_URL),
        diagnosticSink = diagnosticSink,
    ),
    binding,
    RenderContextProbe { RenderContextIdentity(1L) },
)
