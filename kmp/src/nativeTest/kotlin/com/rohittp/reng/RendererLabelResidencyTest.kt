package com.rohittp.reng

import com.rohittp.reng.internal.firewall.LABEL_GLYPH_TEMPLATE
import com.rohittp.reng.internal.firewall.LABEL_SANS_STACK
import com.rohittp.reng.internal.firewall.LABEL_SERIF_STACK
import com.rohittp.reng.internal.firewall.LABEL_TILE_TEMPLATE
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
 *   cheap".* Every frame below selects **nine** tiles, and the tile assertions are exact sorted lists
 *   of nine urls rather than counts or set memberships, so a cache that retained eight of them, or that
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
            "nine label tiles reach the consumer once across two frames, not eighteen times",
        )
        assertEquals(
            labelGlyphUrls().sorted(),
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
     * Two assertions, because a cache can be wrong in two directions. Fetching the new tiles is not
     * enough on its own -- a renderer that re-fetched and then handed back the *retained* batch anyway
     * would satisfy it -- so the frame's own content must differ too.
     *
     * **That content is the glyph quads' atlas coordinates, and it is deliberately not the atlas
     * identity.** The obvious assertion, that the two frames name two different atlases, is *false* and
     * measuring it is how that was found: Rentile packs every glyph of every Glyph Range it fetched
     * rather than only the ones its candidates use, both tile sets need the same three ranges, so both
     * atlases are byte-identical and share a `contentKey`. What does differ is *which cell of that atlas
     * each placed label samples* -- [cameraA]'s centre tile spells `V` and [cameraB]'s spells `Q` -- so
     * the `cornersUv` of the frame's quads is the thing that can only come from the right tile set.
     */
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
            "the moved frame fetches its own nine tiles, and the unmoved one fetched none",
        )
        assertEquals(
            (labelGlyphUrls() + labelGlyphUrls()).sorted(),
            transport.glyphUrls(),
            "two acquisitions across three frames, and therefore two rounds of Glyph Ranges",
        )

        val firstLabels = assertNotNull(first.labels)
        val movedLabels = assertNotNull(moved.labels)
        assertNotEquals(
            firstLabels.labels.map { sampledCells(it) },
            movedLabels.labels.map { sampledCells(it) },
            "the moved frame samples its own tile set's glyph cells rather than the retained batch's",
        )
        assertEquals(
            firstLabels.atlasKey,
            movedLabels.atlasKey,
            "while the atlas itself is the same one, because both tile sets need the same three ranges " +
                "and Rentile packs a whole range rather than the glyphs its candidates used",
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
        assertEquals(labelGlyphUrls().sorted(), transport.glyphUrls())
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
 * This is the frame's *content* in the only sense that discriminates two tile sets here: the two
 * atlases are byte-identical, so what a frame drawing tile set B's candidates has that one replaying
 * tile set A's does not is a different set of cells inside it.
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
 * second round of traffic. With no overlap, every one of the nine is either fetched or not.
 */
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

/** The nine tiles [cameraA] selects, as the exact urls the engine composes for them. */
private val TILE_URLS_A: List<String> =
    (2..4).flatMap { x -> (5..7).map { y -> "${TILE_PREFIX}4/$x/$y.pbf" } }.sorted()

/** The nine [cameraB] selects. Sharing no url with [TILE_URLS_A] is what the assertions rest on. */
private val TILE_URLS_B: List<String> =
    (11..13).flatMap { x -> (9..11).map { y -> "${TILE_PREFIX}4/$x/$y.pbf" } }.sorted()

/**
 * The letter tile `(x, y)` carries, which is what makes two tile sets produce two different atlases.
 *
 * `A + (x + 3y) mod 26` gives [TILE_URLS_A]'s nine tiles the letters `R..Z` and [TILE_URLS_B]'s `M..U`
 * -- overlapping ranges but different *sets*, so the glyphs Rentile packs for the two differ and its own
 * `LabelCandidateBatch.contentKey` differs with them. Every candidate in the batch contributes, not only
 * the on-screen one, because the closure is frozen over every tile the plan was handed.
 */
private fun tileLetter(x: Int, y: Int): Char = 'A' + ((x + 3 * y) % 26)

/**
 * One vector tile per url: a `place` feature carrying that tile's own letter plus `U+0100`, and a
 * `town_label` feature carrying a constant `B`.
 *
 * Those three codepoints are chosen so the Glyph Closure is exactly the shared fixture's three urls --
 * sans `0-255`, sans `256-511`, serif `0-255` -- which lets [labelGlyphUrls] stay the literal expected
 * list rather than a fourth string written out here. The varying half is deliberately in the sans
 * `0-255` range, the one range whose *packed contents* therefore differ between the two tile sets.
 */
private fun residencyTileBytes(url: String): ByteArray {
    val path = url.removePrefix(TILE_PREFIX).removeSuffix(".pbf").split("/")
    val x = path[1].toInt()
    val y = path[2].toInt()
    return labelMvtBytes("place" to "${tileLetter(x, y)}Ā", "town_label" to "B")
}

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

private val SERIF_RANGE_0: ByteArray =
    labelGlyphRange(LABEL_SERIF_STACK, "0-255", listOf(66), SATURATED)

/**
 * Answers the style, every label tile the frames select, and the three Glyph Ranges, recording every url
 * it was asked for.
 *
 * [ConcurrentRecorder] rather than a list: Rentile acquires tiles and ranges as concurrent
 * `Dispatchers.Default` children, and a lost update here would silently turn "fetched twice" into
 * "fetched once" -- which is the exact regression these tests exist to catch.
 */
private class ResidencyTransport : Transport {
    private val recorded = ConcurrentRecorder<String>()

    /** Every label tile url asked for, in sorted order, **with duplicates kept**. */
    suspend fun tileUrls(): List<String> = recorded.snapshot().filter { it.startsWith(TILE_PREFIX) }.sorted()

    /** Every Glyph Range url asked for, sorted, with duplicates kept. */
    suspend fun glyphUrls(): List<String> = recorded.snapshot().filter { it.startsWith(GLYPH_PREFIX) }.sorted()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        val body = when {
            url == RESIDENCY_STYLE_URL -> RESIDENCY_STYLE_JSON.encodeToByteArray()
            url.startsWith(TILE_PREFIX) -> residencyTileBytes(url)
            url == labelGlyphUrls()[0] -> SANS_RANGE_0
            url == labelGlyphUrls()[1] -> SANS_RANGE_256
            url == labelGlyphUrls()[2] -> SERIF_RANGE_0
            else -> null
        } ?: return TransportResponse(statusCode = 404, body = ByteArray(0))
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "application/octet-stream"),
        )
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
): Renderer = createRenderer(
    RendererConfiguration(
        outputPixelSize = OutputPixelSize(RESIDENCY_PIXELS, RESIDENCY_PIXELS),
        transport = transport,
        store = ResidencyStore(),
        basemapStyle = ResourceLocator(RESIDENCY_STYLE_URL),
    ),
    binding,
    RenderContextProbe { RenderContextIdentity(1L) },
)
