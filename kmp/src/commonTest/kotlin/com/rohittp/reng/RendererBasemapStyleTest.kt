package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_MAX_COLOR_ATTACHMENTS
import com.rohittp.reng.internal.gl.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
import com.rohittp.reng.internal.gl.GL_MAX_TEXTURE_SIZE
import com.rohittp.reng.internal.gl.GL_NUM_EXTENSIONS
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_SHADING_LANGUAGE_VERSION
import com.rohittp.reng.internal.gl.GL_VENDOR
import com.rohittp.reng.internal.gl.GL_VERSION
import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

/**
 * The renderer end of the basemap style commit: `prepare()` acquires the configured style through its
 * own driver, derives the routes the Rentile engine will fetch while compiling it, preregisters them on
 * the firewall, and compiles the style through the real engine inside one preparation invocation.
 *
 * Every assertion here is stated in terms of what the **consumer's own adapters** observed, because that
 * is the only surface a caller has: an engine exchange RenG failed to preregister never reaches the
 * consumer at all (it fails closed as `AMBIGUOUS_RESOURCE_ROUTE`), and a style RenG recompiles per frame
 * shows up as the same sprite being fetched again and again.
 *
 * **What is here and what is not.** Since Cycle E-C3 a basemap frame that prepares *successfully* also
 * rasterizes its ground, which needs Skia's native library -- absent from this project's `androidHostTest`
 * runtime. Those cases therefore live in `nativeTest`'s `RendererBasemapStyleRenderTest` and
 * `RendererBasemapTileTest`; what stays here is every case that ends before the rasterizer, which is
 * still the whole style-failure surface and still covers Android. The fixtures below are shared by all
 * three files.
 */
class RendererBasemapStyleTest {

    @Test
    fun aStyleThatWillNotParseIsATypedRenGFailureRatherThanAnEngineOne() = runTest {
        val transport = StyleTransport(styleJson = "{ not a style }")
        val renderer = styleRenderer(transport)

        val failure = kotlin.test.assertFailsWith<RenGException> { renderer.prepare(basemapPlan(frameIndex = 0L)) }

        assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, failure.code)
        assertEquals(PipelineStage.RESOURCE_PARSING, failure.stage)
        assertEquals(
            listOf(STYLE_URL),
            transport.requestedUrls(),
            "a style RenG cannot read is never handed to the engine",
        )
    }

    @Test
    fun aStyleOutsideRenGsSupportedSubsetIsReportedAsUnsupportedRatherThanMalformed() = runTest {
        val transport = StyleTransport(styleJson = """{"version":7,"sources":{},"layers":[]}""")
        val renderer = styleRenderer(transport)

        val failure = kotlin.test.assertFailsWith<RenGException> { renderer.prepare(basemapPlan(frameIndex = 0L)) }

        assertEquals(RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE, failure.code)
    }

    @Test
    fun aPlanThatDrawsNeitherBasemapNorLabelsAcquiresNoStyleAtAll() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(
            FramePlan(
                frameIndex = 0L,
                camera = styleCamera(),
                drawBasemap = false,
                drawLabels = false,
            ),
        )

        assertEquals(
            emptyList(),
            transport.requestedUrls(),
            "a frame that asks for neither the basemap nor its labels acquires nothing",
        )
    }

    /**
     * E-labels task 8b, and the pairing that was broken: the labels are *in* the style, so a frame that
     * draws them without the ground still has to fetch it.
     *
     * **Read the fixture before reading the assertion.** The style is deliberately unparseable, so the
     * frame fails immediately after acquisition — which is what keeps this case in `commonTest` and
     * therefore covering Android, whose host runtime cannot complete any successful basemap preparation
     * (see the class KDoc). The failure is the *evidence* that the acquisition happened at all: before
     * the split this exact plan requested no url and prepared successfully.
     *
     * **The unmixed pairings would prove nothing.** `drawBasemap = true` acquires the style today, and
     * both switches false acquires nothing today; only `false/true` separates the two guards. Its
     * companion `true/false` — the basemap still acquiring its style with labels switched off — is
     * `nativeTest`'s `RendererBasemapTileTest`, where a successful preparation is expressible.
     */
    @Test
    fun aPlanThatDrawsLabelsWithoutTheBasemapStillAcquiresTheStyle() = runTest {
        val transport = StyleTransport(styleJson = "{ not a style }")
        val renderer = styleRenderer(transport)

        val failure = kotlin.test.assertFailsWith<RenGException> {
            renderer.prepare(
                FramePlan(
                    frameIndex = 0L,
                    camera = styleCamera(),
                    drawBasemap = false,
                    drawLabels = true,
                ),
            )
        }

        assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, failure.code)
        assertEquals(
            listOf(STYLE_URL),
            transport.requestedUrls(),
            "labels without a basemap must still acquire the style the labels come from",
        )
    }
}

// ---- fixtures ------------------------------------------------------------------------------------

internal const val STYLE_URL: String = "https://styles.example/basic.json"
internal const val SPRITE_BASE_URL: String = "https://sprites.example/atlas"
internal const val SPRITE_JSON_URL: String = "https://sprites.example/atlas.json"
internal const val SPRITE_IMAGE_URL: String = "https://sprites.example/atlas.png"
internal const val STYLE_TILE_TEMPLATE: String = "https://tiles.example/r/{z}/{x}/{y}.png"
internal const val STICKER_URL: String = "https://images.example/sticker.png"

/**
 * A style whose compilation genuinely fetches something. `background-pattern` is what makes Rentile's
 * `layerRequiresSpriteUnconditionally` true, so the sprite pair is acquired inside `engine.prepare`
 * rather than at tile time — which is exactly the traffic this task's preregistration has to cover.
 */
internal val STYLE_WITH_SPRITE_JSON: String =
    """{"version":8,"name":"reng-style-commit-test",""" +
        """"sprite":"$SPRITE_BASE_URL",""" +
        """"sources":{"s":{"type":"raster","tiles":["$STYLE_TILE_TEMPLATE"],"tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-pattern":"dot"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/** An empty sprite atlas: a valid document with no entries, so nothing has to line up with the image. */
private const val SPRITE_ATLAS_JSON: String = "{}"

/** A real, valid 2x2 truecolour PNG — Rentile reads its IHDR dimensions while compiling the atlas. */
internal val STYLE_TEST_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

internal fun screenPlacement(): Placement = Placement(
    positionMode = AnchoringMode.SCREEN,
    position = Vector3(0.0, 0.0, 0.0),
    rotationMode = AnchoringMode.SCREEN,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.SCREEN,
    scale = 1.0,
)

/**
 * Deliberately **off the origin and off any diagonal**: at 64x64 output this selects the four tiles
 * `x in {1, 2}, y in {10, 11}` at LOD 4, whose `(x, y)` pair set is **disjoint** from its own transpose,
 * as is the 16-tile DEM neighbourhood around it (`x in 0..3, y in 9..12`).
 *
 * That is load-bearing, not decoration. The obvious camera -- `(0, 0)` at zoom 2 -- selects
 * `x in {1, 2}, y in {1, 2}`, a set invariant under swapping x and y, so every url assertion built on it
 * would pass unchanged if `BasemapEngineHost.engineTileIdOf` mapped `canonicalX` onto `TileId.y` and
 * `tileY` onto `TileId.x`. A transposition there composes a plausible-but-wrong request that a symmetric
 * preregistered set happens to accept -- exactly the silent total outage exact-url assertions exist to
 * catch. With this camera a transposition fails closed on every tile instead.
 */
internal fun styleCamera(): Camera =
    Camera(latitude = -55.0, unwrappedLongitude = -135.0, zoom = 4.0, bearing = 0.0, pitch = 0.0)

internal fun basemapPlan(frameIndex: Long, stickers: List<Sticker> = emptyList()): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = styleCamera(),
    drawBasemap = true,
    stickers = stickers,
)

internal fun styleRenderer(
    transport: Transport,
    store: Store = RecordingStyleStore(),
    basemapStyle: ResourceLocator? = ResourceLocator(STYLE_URL),
    resourceLimits: ResourceLimits = ResourceLimits(),
    diagnosticSink: DiagnosticSink = DiagnosticSink { },
): Renderer = createRenderer(
    RendererConfiguration(
        outputPixelSize = OutputPixelSize(64, 64),
        diagnosticSink = diagnosticSink,
        transport = transport,
        store = store,
        basemapStyle = basemapStyle,
        resourceLimits = resourceLimits,
    ),
    styleGlBinding(),
    RenderContextProbe { RenderContextIdentity(1L) },
)

internal fun styleGlBinding(): RecordingGlBinding = RecordingGlBinding().apply {
    strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
    strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2"
    strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
    strings[GL_VENDOR] = "Mesa"
    indexedStrings += listOf("GL_EXT_sRGB_write_control", "GL_OES_texture_float")
    integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
    integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
    integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
    integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
}

/**
 * Answers the style document, the sprite pair, and a PNG for anything else, recording every url in call
 * order. Declares no expiry at all, so nothing it serves is ever fresh — every frame re-resolves its
 * documents from here, which is what makes a per-frame recompilation visible as repeated sprite traffic.
 */
internal class StyleTransport(
    private val styleJson: String = STYLE_WITH_SPRITE_JSON,
) : Transport {
    private val recorded = ConcurrentRecorder<String>()

    /**
     * Every url this transport was asked for, in the order the appends were serialized. Suspending, and
     * a copy, because acquiring the same lock the appends take is what gives the reading coroutine a
     * happens-before edge on all of them; reading the backing list directly would be a race even if the
     * appends themselves were safe.
     */
    suspend fun requestedUrls(): List<String> = recorded.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = styleJson.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = SPRITE_ATLAS_JSON.encodeToByteArray(),
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

/** Records every Store exchange in call order and never answers a read, so nothing is served twice. */
internal class RecordingStyleStore : Store {
    private val recordedReads = ConcurrentRecorder<RawResourceKey>()
    private val recordedWrites = ConcurrentRecorder<RawResourceKey>()

    suspend fun reads(): List<RawResourceKey> = recordedReads.snapshot()

    suspend fun writes(): List<RawResourceKey> = recordedWrites.snapshot()

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        recordedReads.record(key)
        return null
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        recordedWrites.record(key)
    }
}

/**
 * An append-only recorder that is safe under **real** parallelism, not merely under a single test
 * dispatcher.
 *
 * The adapters these fixtures implement are called from inside the Rentile engine, whose own scope is
 * `rootJob + Dispatchers.Default` (`DefaultBasemapRasterizer.operation`), and whose sprite acquirer
 * fetches the atlas metadata and the atlas image as two concurrent `async` children
 * (`SpriteResourceAcquirer.acquire`). On the JVM those genuinely run on different threads, so an
 * unguarded `ArrayList.add` from both can lose an entry.
 *
 * That is not a cosmetic flake. `compilesOneStyleOnceAcrossFramesRatherThanOncePerFrame` detects a
 * per-frame recompilation by *counting* sprite urls in this list: a regression writes two entries and a
 * lost update reduces them to one, so the very assertion that exists to catch the defect a single-frame
 * test cannot see would silently pass. A recorder a test draws conclusions from has to be at least as
 * trustworthy as the conclusion.
 *
 * A [Mutex] rather than an atomic or a concurrent collection because every recorded call site is already
 * `suspend`, so it composes with no new dependency and no platform-specific type, and because it gives
 * the suspending reader the same happens-before edge it gives the writers.
 */
internal class ConcurrentRecorder<T> {
    private val mutex = Mutex()
    private val entries = mutableListOf<T>()

    suspend fun record(entry: T) {
        mutex.withLock { entries += entry }
    }

    suspend fun snapshot(): List<T> = mutex.withLock { ArrayList(entries) }
}
