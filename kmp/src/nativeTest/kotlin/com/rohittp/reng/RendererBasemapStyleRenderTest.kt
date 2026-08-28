package com.rohittp.reng

import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The half of [RendererBasemapStyleTest] whose `prepare()` **completes**, which since Cycle E-C3 means it
 * rasterizes: a frame that draws a basemap now renders its ground tiles through the engine before
 * `prepare()` returns.
 *
 * These tests lived in `commonTest` until then. They are here for exactly the reason
 * `internal.firewall.BasemapEngineRenderTest` is: Rentile rasterizes through Skia, and this project's
 * `androidHostTest` runtime resolves `org.jetbrains.skiko:skiko`'s API without its native library —
 * Rentile adds `skiko-awt-runtime-<host>` only to its own JVM/Android test source sets, never to what it
 * publishes. Measured on that runtime, `prepareBatch` fails with `RESOURCE_DECODE_FAILED` and `render`
 * with `BASEMAP_RENDER_FAILED`, for **any** style including a source-less one, so no successful basemap
 * preparation is expressible there at all. Kotlin/Native links Skia in, so every assertion below runs for
 * real on `macosArm64Test` (Apple CI) and `linuxX64Test` (Ubuntu CI). The style-commit tests that end in
 * a *failure* — an unparseable style, an unsupported one, a plan that draws no basemap — never reach the
 * rasterizer and stay in `commonTest`, where they still cover Android.
 */
class RendererBasemapStyleRenderTest {

    /**
     * The whole consumer-visible traffic of one basemap frame, as exact urls: RenG's own style document,
     * the sprite pair the engine fetches while compiling it, and the four ground tiles the frame's own
     * LOD selected. Every one of the last six reaches the consumer only because RenG preregistered the
     * exact string Rentile composed.
     */
    @Test
    fun acquiresTheConfiguredStyleAndEverythingCompilingAndDrawingItMakesTheEngineFetch() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(basemapPlan(frameIndex = 0L))

        assertEquals(
            listOf(
                STYLE_URL,
                SPRITE_JSON_URL,
                SPRITE_IMAGE_URL,
                "https://tiles.example/r/4/1/10.png",
                "https://tiles.example/r/4/1/11.png",
                "https://tiles.example/r/4/2/10.png",
                "https://tiles.example/r/4/2/11.png",
            ).sorted(),
            transport.requestedUrls().sorted(),
            "the style is RenG's own resource; the sprite pair and the ground tiles are the engine's, " +
                "reached only through preregistered routes",
        )
    }

    /**
     * The defect a single-frame test cannot see. Compilation is bound to the style's **content**, so a
     * second frame over identical bytes reuses it; binding it to a resident generation object instead
     * makes every frame recompile, which re-runs the whole sprite/TileJSON/GeoJSON acquisition through
     * the consumer's transport once per frame forever.
     *
     * The style itself is deliberately not fresh (the transport declares no expiry), so the driver
     * re-resolves it from the transport on the second frame and genuinely emits a second
     * `CompileBasemapStyle`. That is the case where recompilation is reachable at all — a fresh,
     * `RESIDENT`-provenance frame emits no compile action, so it could never observe the defect.
     */
    @Test
    fun compilesOneStyleOnceAcrossFramesRatherThanOncePerFrame() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(basemapPlan(frameIndex = 0L))
        val spriteRequestsAfterFirstFrame = transport.requestedUrls().count { it == SPRITE_JSON_URL }
        renderer.prepare(basemapPlan(frameIndex = 1L))

        assertEquals(1, spriteRequestsAfterFirstFrame, "one frame compiles the style exactly once")
        assertEquals(
            1,
            transport.requestedUrls().count { it == SPRITE_JSON_URL },
            "a second frame over identical style bytes must reuse the compilation, not redo its whole " +
                "resource acquisition",
        )
        assertEquals(
            1,
            transport.requestedUrls().count { it == SPRITE_IMAGE_URL },
            "the sprite image is acquired by the same compilation as its metadata",
        )
        assertTrue(
            transport.requestedUrls().count { it == STYLE_URL } >= 2,
            "the style document itself is re-resolved per frame; only its compilation is reused",
        )
    }

    /**
     * The renderer has to be able to hold the frame's compiled style on **every** frame, not only on the
     * one that compiled it: the pure core emits no `CompileBasemapStyle` on a `RESIDENT`-provenance
     * frame, so there is no compile action to take it from. Reading it back from the engine host gives
     * one uniform seam, and it is what `prepareTiles` is handed.
     */
    @Test
    fun holdsTheOneCompiledStyleAcrossEveryFrameThatDrawsIt() = runTest {
        val renderer = styleRenderer(StyleTransport()) as RenGRenderer
        assertNull(renderer.preparedBasemapStyle, "nothing is compiled before the first prepare()")

        renderer.prepare(basemapPlan(frameIndex = 0L))
        val first = renderer.preparedBasemapStyle
        renderer.prepare(basemapPlan(frameIndex = 1L))
        val second = renderer.preparedBasemapStyle

        assertNotNull(first, "a frame that draws a basemap holds its compiled style")
        assertSame(first, second, "the same style document is the same compilation, frame after frame")

        // E-labels task 8b: a frame that draws the labels and not the ground holds its style too --
        // the labels are in it. This is one of the two mixed pairings; the same claim with both
        // switches true is `first`/`second` above and would pass without the split.
        renderer.prepare(
            FramePlan(frameIndex = 2L, camera = styleCamera(), drawBasemap = false, drawLabels = true),
        )
        assertSame(
            first,
            renderer.preparedBasemapStyle,
            "a frame that draws only the labels still holds the style they come from",
        )

        // This names the frame in front of the reader, never "the last style compiled at some point".
        renderer.prepare(
            FramePlan(frameIndex = 3L, camera = styleCamera(), drawBasemap = false, drawLabels = false),
        )
        assertNull(
            renderer.preparedBasemapStyle,
            "a frame that draws neither the basemap nor its labels holds no style",
        )
    }

    /**
     * ADR 0016: the style is written only after "successful compilation and completion of all other work
     * for the referencing preparation items". Made observable, and deterministic, by giving the driver a
     * single concurrency slot — the two possible orders are then fully determined by whether the style's
     * owner is also the sticker's, which is precisely what the barrier reads.
     *
     * With one owner per plan item the sticker's Store write and visibility install both complete before
     * the style's write is even requested. With an owner per reference the style's owner has no other
     * work at all, the barrier is vacuously satisfied, and the style is written first.
     */
    @Test
    fun writesTheStyleOnlyAfterEveryOtherResourceItsOwnFrameAskedFor() = runTest {
        val transport = StyleTransport()
        val store = RecordingStyleStore()
        val renderer = createRenderer(
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(64, 64),
                transport = transport,
                store = store,
                basemapStyle = ResourceLocator(STYLE_URL),
                maximumConcurrentResourceOperations = 1,
            ),
            styleGlBinding(),
            RenderContextProbe { RenderContextIdentity(1L) },
        )

        renderer.prepare(
            basemapPlan(
                frameIndex = 0L,
                stickers = listOf(Sticker(placement = screenPlacement(), image = ResourceLocator(STICKER_URL))),
            ),
        )

        val styleStoreWrites = store.writes()

        // Filtered to the two classes RenG's own driver writes: the engine writes its sprite pair and its
        // ground tiles through the firewall too, on its own concurrent coroutines, and their order
        // relative to each other is the engine's business rather than this barrier's.
        assertEquals(
            listOf(ResourceClass.STICKER_IMAGE, ResourceClass.BASEMAP_STYLE),
            styleStoreWrites.map(RawResourceKey::resourceClass).filter {
                it == ResourceClass.STICKER_IMAGE || it == ResourceClass.BASEMAP_STYLE
            },
            "the style's write waits for every other resource of the item that referenced it",
        )
        assertTrue(
            styleStoreWrites.map(RawResourceKey::resourceClass).containsAll(
                listOf(ResourceClass.BASEMAP_SPRITE_JSON, ResourceClass.BASEMAP_SPRITE_IMAGE),
            ),
            "the engine's own sprite acquisition reached the consumer through the firewall",
        )
    }

    /**
     * Moved out of `RendererFactoryTest` for the same Skia reason as everything else here: the warning
     * under test is emitted at `draw()`, but observing that it is *absent* needs a basemap frame that
     * genuinely prepared, and preparing one now rasterizes.
     */
    @Test
    fun aConfiguredBasemapStyleNeverWarns() = runTest {
        val sink = CollectingDiagnosticSink()
        val renderer = createRenderer(
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(64, 64),
                transport = StyleTransport(),
                store = RecordingStyleStore(),
                basemapStyle = ResourceLocator(STYLE_URL),
                diagnosticSink = sink,
            ),
            styleGlBinding(),
            RenderContextProbe { RenderContextIdentity(1L) },
        )

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        renderer.draw(frame, target)

        assertTrue(sink.diagnostics.none { it.code == DiagnosticCode.BASEMAP_NOT_CONFIGURED })
    }
}

/** Collects every emitted diagnostic; the sink is called from `draw()`, which is not `suspend`. */
private class CollectingDiagnosticSink : DiagnosticSink {
    val diagnostics: MutableList<Diagnostic> = mutableListOf()

    override fun emit(diagnostic: Diagnostic) {
        diagnostics += diagnostic
    }
}
