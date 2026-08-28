package com.rohittp.reng

import com.rohittp.reng.internal.firewall.LABEL_GLYPH_TEMPLATE
import com.rohittp.reng.internal.firewall.LABEL_MVT_BYTES
import com.rohittp.reng.internal.firewall.LABEL_SANS_STACK
import com.rohittp.reng.internal.firewall.LABEL_SERIF_STACK
import com.rohittp.reng.internal.firewall.LABEL_TILE_TEMPLATE
import com.rohittp.reng.internal.firewall.labelGlyphRange
import com.rohittp.reng.internal.firewall.labelGlyphUrls
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * E-labels task 20's gate: a [FramePlan] goes in through the public API and **drawn label pixels
 * come out**.
 *
 * **Why this suite and not four call-log assertions.** Every stage of the label path already has its
 * own unit suite -- the handover's routes, the placement's collision, the fade's arithmetic, the
 * pipeline's one draw call -- and all eleven of them were green while the renderer drew no label at
 * all, because nothing ran them in sequence. What was unproven is composition, and the only evidence
 * for composition that a fake cannot fabricate is a pixel. So this asserts the frame, never the
 * calls.
 *
 * **Native-only, for the same reason `LabelHandoverBatchTest` is.** `acquireLabelCandidates` ends in
 * Rentile's Skia glyph packer, and this project's `androidHostTest` runtime resolves skiko's API
 * without its native library, so even a no-glyph batch cannot be read there. Kotlin/Native links Skia
 * in. It also needs a real GL context, which is what makes `macosArm64Test` its home.
 *
 * **The two ways a "labels drew" assertion passes for the wrong reason, and how each is closed.**
 * - *Everything collides, so nothing draws, and "nothing drawn" is also what broken wiring
 *   produces.* Closed by asserting **presence**, at a **named colour**, in a **named place**: the two
 *   labels carry two different `text-color`s that no other pixel in the frame can produce, and each
 *   must appear near its own predicted anchor. A frame with nothing in it fails every one of those.
 * - *The fade starts every label at zero, so frame one is empty whatever the wiring does.* Closed by
 *   drawing the whole ramp and asserting the ends **differ**: frame 1 carries a tenth of the fade and
 *   frame [LABEL_FADE_RAMP] carries all of it, so the same pixel must be strictly more opaque at the
 *   end than at the start. That is also the only assertion in the tree that shows the fade committing
 *   across `prepare()` calls rather than merely computing.
 *
 * **And the change-the-candidates control.** Presence alone cannot tell "the labels drew" from "the
 * label pass paints something regardless": three styles are drawn through the identical camera and
 * plan -- two symbol layers, one, and none -- and the frame must lose exactly the labels the style
 * stopped declaring. The one-layer style must keep [PLACE_COLOUR] and lose [TOWN_COLOUR] entirely,
 * which no constant-output pass can satisfy.
 *
 * **What this does not claim.** Not legibility: the fixture's glyphs are saturated distance fields,
 * so each draws as a solid block of its cell rather than as a letter, and legibility stays unverified
 * until Cycle J. Not line placement, not icons, not the ground -- every frame here draws
 * `drawBasemap = false` precisely so that the only thing in it is text.
 */
internal fun runLabelIntegrationReadbackSuite(binding: GlBinding, probe: RenderContextProbe) {
    val target = createLabelIntegrationTarget(binding)
    println("RenG label integration readback driver: " + binding.getString(GL_RENDERER))
    try {
        assertTwoLabelsDrawAtTheirOwnAnchorsInTheirOwnColours(binding, probe, target)
        assertDroppingALayerDropsExactlyThatLabel(binding, probe, target)
        assertAStyleWithNoSymbolLayersDrawsNothing(binding, probe, target)
        assertDrawLabelsFalseDrawsNothing(binding, probe, target)
    } finally {
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * The positive case, and the only one in the tree that runs the whole path.
 *
 * Both labels sit on the fixture's one point feature, at the centre of the tile the camera is centred
 * on; `text-translate` moves the town label 40 screen pixels east of it, which is what keeps the two
 * from colliding and what makes their two anchors independently checkable. The fade is driven to
 * saturation over [LABEL_FADE_RAMP] frames and the first frame is kept, so the ramp's own ends are
 * the fade assertion.
 */
private fun assertTwoLabelsDrawAtTheirOwnAnchorsInTheirOwnColours(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val renderer = labelRenderer(binding, probe, TWO_LAYER_STYLE_JSON)
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        val first = clearAndDraw(binding, renderer, renderTarget, target, labelPlan(0L))
        var last = first
        for (frameIndex in 1L until LABEL_FADE_RAMP.toLong()) {
            last = clearAndDraw(binding, renderer, renderTarget, target, labelPlan(frameIndex))
        }

        val placeSample = last.nearest(PLACE_COLOUR)
            ?: throw AssertionError(
                "no pixel in the frame carries the place layer's own text colour " +
                    PLACE_COLOUR.describe() + "; the frame drew " + last.drawnCount() +
                    " non-background pixels in total.\n" + last.asciiMap(),
            )
        val townSample = last.nearest(TOWN_COLOUR)
            ?: throw AssertionError(
                "the place label drew but the town label did not: no pixel carries " +
                    TOWN_COLOUR.describe() + ", so only one of the batch's two labels reached the " +
                    "frame.\n" + last.asciiMap(),
            )
        println(
            "RenG label integration readback: place=" + placeSample.describe() +
                " town=" + townSample.describe() + " drawn=" + last.drawnCount(),
        )

        // The specific pixel. Both labels are box-centred on their own anchor, so each colour's ink
        // must sit within half a label of where the camera puts that anchor -- the place label at the
        // frame's own centre, the town label its `text-translate` east of it. A pass that painted the
        // right colours in the wrong place, or that ignored `text-translate` and stacked the two,
        // fails here even though both colours are present.
        assertNear(placeSample, PLACE_ANCHOR_X, ANCHOR_Y, "the place label")
        assertNear(townSample, TOWN_ANCHOR_X, ANCHOR_Y, "the town label")

        // The fade, end to end. Ten prepares carry one label from a tenth of its opacity to all of
        // it, so the same pixel must be strictly nearer the pure text colour at the end than at the
        // start. A fade that never commits leaves the two frames identical; a fade that starts at
        // zero leaves the first frame empty, which the strict inequality also catches.
        val firstDistance = first.at(placeSample.x, placeSample.y).distanceTo(PLACE_COLOUR)
        val lastDistance = last.at(placeSample.x, placeSample.y).distanceTo(PLACE_COLOUR)
        assertTrue(
            lastDistance < firstDistance,
            "the fade must advance across prepares: at (${placeSample.x}, ${placeSample.y}) the " +
                "first frame is $firstDistance from ${PLACE_COLOUR.describe()} and frame " +
                "${LABEL_FADE_RAMP - 1} is $lastDistance -- " +
                first.at(placeSample.x, placeSample.y).describe() + " then " +
                last.at(placeSample.x, placeSample.y).describe(),
        )
        assertTrue(
            lastDistance <= CHANNEL_TOLERANCE,
            "a saturated fade must reach the style's own text colour, but the pixel is " +
                last.at(placeSample.x, placeSample.y).describe() +
                " against ${PLACE_COLOUR.describe()}",
        )
    } finally {
        renderer.close()
    }
}

/**
 * The control that turns presence into evidence. The same camera, the same plan and the same glyphs,
 * over a style that declares one symbol layer instead of two: the place label must survive untouched
 * and the town label must vanish completely.
 *
 * A "labels drew" assertion cannot distinguish a live path from a pass that paints something on every
 * frame; this can, because no constant output changes when a style stops declaring a layer.
 */
private fun assertDroppingALayerDropsExactlyThatLabel(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val renderer = labelRenderer(binding, probe, ONE_LAYER_STYLE_JSON)
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        var frame = clearAndDraw(binding, renderer, renderTarget, target, labelPlan(0L))
        for (frameIndex in 1L until LABEL_FADE_RAMP.toLong()) {
            frame = clearAndDraw(binding, renderer, renderTarget, target, labelPlan(frameIndex))
        }

        val placeSample = frame.nearest(PLACE_COLOUR)
            ?: throw AssertionError(
                "dropping the town layer must not disturb the place label, but nothing in the " +
                    "frame carries " + PLACE_COLOUR.describe() + "\n" + frame.asciiMap(),
            )
        assertNear(placeSample, PLACE_ANCHOR_X, ANCHOR_Y, "the surviving place label")
        assertEquals(
            null,
            frame.nearest(TOWN_COLOUR),
            "a style that declares no town layer must draw no town label, but a pixel carrying " +
                TOWN_COLOUR.describe() + " is still in the frame",
        )
    } finally {
        renderer.close()
    }
}

/**
 * The floor. A style whose symbol layers are gone entirely plans no candidate at all, so a frame that
 * still asks for labels must come back exactly as the target was left.
 *
 * This is what stops every assertion above from passing against a pass that draws its own fixture:
 * the camera, the plan, the tiles and the glyph fixture are unchanged, and only the style's layers
 * differ.
 */
private fun assertAStyleWithNoSymbolLayersDrawsNothing(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val renderer = labelRenderer(binding, probe, NO_SYMBOL_STYLE_JSON)
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        val frame = clearAndDraw(binding, renderer, renderTarget, target, labelPlan(0L))
        assertEquals(
            0,
            frame.drawnCount(),
            "a style with no symbol layer draws no label, but ${frame.drawnCount()} pixels " +
                "changed\n" + frame.asciiMap(),
        )
    } finally {
        renderer.close()
    }
}

/**
 * `drawLabels = false` over the style that does draw them: the switch, in pixels.
 *
 * Deliberately the last case, and deliberately over [TWO_LAYER_STYLE_JSON] rather than a style that
 * would have drawn nothing anyway -- this is the one case whose fixture is identical to the positive
 * case's in every respect but the flag.
 */
private fun assertDrawLabelsFalseDrawsNothing(binding: GlBinding, probe: RenderContextProbe, target: Int) {
    val renderer = labelRenderer(binding, probe, TWO_LAYER_STYLE_JSON)
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        val frame = clearAndDraw(
            binding,
            renderer,
            renderTarget,
            target,
            FramePlan(
                frameIndex = 0L,
                camera = labelCamera(),
                drawBasemap = false,
                drawLabels = false,
            ),
        )
        assertEquals(
            0,
            frame.drawnCount(),
            "drawLabels = false must draw no label, but ${frame.drawnCount()} pixels changed\n" +
                frame.asciiMap(),
        )
    } finally {
        renderer.close()
    }
}

// ---- the fixture ---------------------------------------------------------------------------------

internal const val LABEL_INTEGRATION_PIXELS: Int = 128

/**
 * How many frames the ramp runs for. [com.rohittp.reng.internal.label.LABEL_FADE_STEPS] advances one
 * step per successful `prepare`, so this is exactly the number that saturates a label held in place
 * for every frame of it -- one fewer would leave the last frame short of the style's own colour and
 * make the saturation assertion a tolerance argument rather than a statement.
 */
private const val LABEL_FADE_RAMP: Int = 10

/** Per-channel tolerance, the basemap readback suite's, for the same driver-rounding reason. */
private const val CHANNEL_TOLERANCE: Int = 8

/**
 * What the readback target is cleared to. Not a colour any label in this fixture can paint, and
 * opaque, so "still absent" is unambiguous: RenG's own offscreen surface clears to transparent black
 * and composites with source alpha, leaving this standing wherever nothing drew.
 */
private val ABSENT: IntArray = intArrayOf(0, 96, 32, 255)

/** The place layer's `text-color`. Distinct from [TOWN_COLOUR] in two channels, and from [ABSENT] in three. */
private val PLACE_COLOUR: IntArray = intArrayOf(255, 0, 255, 255)

/** The town layer's. */
private val TOWN_COLOUR: IntArray = intArrayOf(255, 170, 0, 255)

/**
 * `text-translate` for the town layer, in screen pixels. Large enough that the two labels cannot
 * collide -- each is about 19 pixels wide -- and small enough that both stay inside the frame.
 */
private const val TOWN_TRANSLATE_X: Int = 40

private const val PLACE_ANCHOR_X: Int = LABEL_INTEGRATION_PIXELS / 2
private const val TOWN_ANCHOR_X: Int = LABEL_INTEGRATION_PIXELS / 2 + TOWN_TRANSLATE_X
private const val ANCHOR_Y: Int = LABEL_INTEGRATION_PIXELS / 2

/**
 * How far a label's ink may sit from its own anchor. A label is box-centred on its anchor and the
 * fixture's two glyph cells are about 19 by 11 screen pixels, so its own ink never leaves this
 * radius; the two anchors are [TOWN_TRANSLATE_X] apart, so neither label's ink can satisfy the
 * other's assertion.
 */
private const val MAXIMUM_ANCHOR_OFFSET: Int = 16

/**
 * The camera sits exactly at the centre of tile `(z = 4, x = 3, y = 6)`, which is where the fixture's
 * point feature lands: the MVT feature is at `(2048, 2048)` of a 4096 extent, so it is attributed to
 * the middle of whichever tile the engine was asked for, and centring the camera on that tile puts
 * that anchor at the middle of the frame. Every other selected tile's copy of the feature is a whole
 * tile away -- 512 logical pixels at this zoom -- and therefore off a 128-pixel frame entirely.
 *
 * Asymmetric in both axes on purpose, as `styleCamera` is: `x = 3, y = 6` is disjoint from its own
 * transpose, so a transposed tile index cannot produce this frame.
 */
private fun labelCamera(): Camera = Camera(
    latitude = 31.952162238024968,
    unwrappedLongitude = -101.25,
    zoom = 4.0,
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * `drawBasemap = false, drawLabels = true` -- task 8b's mixed pairing, and the one that makes this
 * suite readable: nothing but text reaches the frame, so every non-background pixel is a label pixel
 * and no ground colour can stand in for one.
 */
private fun labelPlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = labelCamera(),
    drawBasemap = false,
    drawLabels = true,
)

private const val INTEGRATION_STYLE_URL: String = "https://styles.example/integration-labels.json"

private fun symbolLayer(
    id: String,
    sourceLayer: String,
    stack: String,
    colour: String,
    translateX: Int,
): String =
    """{"id":"$id","type":"symbol","source":"v","source-layer":"$sourceLayer",""" +
        """"layout":{"text-field":"{name}","text-font":["$stack"],"text-size":16},""" +
        """"paint":{"text-color":"$colour","text-translate":[$translateX,0]}}"""

private fun integrationStyle(layers: List<String>): String =
    """{"version":8,"name":"reng-label-integration",""" +
        """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" +
        """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14}},""" +
        """"layers":[""" + layers.joinToString(",") + """]}"""

private val PLACE_LAYER: String =
    symbolLayer("place", "place", LABEL_SANS_STACK, "#ff00ff", 0)

private val TOWN_LAYER: String =
    symbolLayer("town", "town_label", LABEL_SERIF_STACK, "#ffaa00", TOWN_TRANSLATE_X)

private val TWO_LAYER_STYLE_JSON: String = integrationStyle(listOf(PLACE_LAYER, TOWN_LAYER))

private val ONE_LAYER_STYLE_JSON: String = integrationStyle(listOf(PLACE_LAYER))

/**
 * The same document with both symbol layers replaced by a background one, so the style is still legal
 * and still declares its source and its glyphs -- only the thing that produces candidates is gone.
 */
private val NO_SYMBOL_STYLE_JSON: String =
    integrationStyle(listOf("""{"id":"bg","type":"background"}"""))

/**
 * The fixture's three Glyph Ranges with a **saturated** distance field.
 *
 * The shared handover fixture ramps its field across `64..191`, and 191 is the fill edge itself
 * (`LABEL_FILL_EDGE_DISTANCE` is `0.75` of the byte range): every texel of it lands inside the
 * smoothstep band, so no pixel ever reaches full coverage and no drawn pixel is ever exactly the
 * style's own colour. That is invisible to a routing test and fatal to a pixel one, so this suite
 * saturates the cell instead -- each glyph then draws as a solid block of its own cell, which is
 * legible to an assertion and not legible as a letter. Legibility is Cycle J's.
 */
private val SATURATED: (Int) -> Byte = { 0xFF.toByte() }

private val SANS_RANGE_0: ByteArray = labelGlyphRange(LABEL_SANS_STACK, "0-255", listOf(65), SATURATED)
private val SANS_RANGE_256: ByteArray = labelGlyphRange(LABEL_SANS_STACK, "256-511", listOf(256), SATURATED)
private val SERIF_RANGE_0: ByteArray = labelGlyphRange(LABEL_SERIF_STACK, "0-255", listOf(66), SATURATED)

private val LABEL_TILE_URL_PREFIX: String = LABEL_TILE_TEMPLATE.substringBefore("{z}")

/**
 * Answers the style, every vector tile the frame selects, and the three Glyph Ranges.
 *
 * Every tile url gets the same bytes, so each selected tile carries its own copy of the feature at
 * its own centre. Only the camera's own tile's copy is on screen; see [labelCamera].
 */
private class IntegrationTransport(private val styleJson: String) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        val body = when {
            url == INTEGRATION_STYLE_URL -> styleJson.encodeToByteArray()
            url.startsWith(LABEL_TILE_URL_PREFIX) -> LABEL_MVT_BYTES
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

/** Every read a miss, every write accepted: nothing here is about persistence. */
private class IntegrationStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = Unit
}

private fun labelRenderer(binding: GlBinding, probe: RenderContextProbe, styleJson: String): Renderer =
    createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS),
            transport = IntegrationTransport(styleJson),
            store = IntegrationStore(),
            basemapStyle = ResourceLocator(INTEGRATION_STYLE_URL),
        ),
        binding,
        probe,
    )

// ---- readback ------------------------------------------------------------------------------------

private fun createLabelIntegrationTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private fun clearAndDraw(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
    plan: FramePlan,
): LabelFrame {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.viewport(0, 0, LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS)
    binding.clearColor(ABSENT[0] / 255f, ABSENT[1] / 255f, ABSENT[2] / 255f, ABSENT[3] / 255f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    // prepare() suspends and reaches the engine on Dispatchers.Default; draw() is synchronous GL work
    // that must run on the thread holding the context, so the two are split rather than nested.
    val frame = runBlocking { renderer.prepare(plan) }
    try {
        renderer.draw(frame, renderTarget)
    } finally {
        frame.close()
    }

    val pixels = ByteArray(LABEL_INTEGRATION_PIXELS * LABEL_INTEGRATION_PIXELS * 4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(
        0,
        0,
        LABEL_INTEGRATION_PIXELS,
        LABEL_INTEGRATION_PIXELS,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        pixels,
    )
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return LabelFrame(pixels)
}

private class Sample(val x: Int, val y: Int, val pixel: IntArray) {
    fun describe(): String = "(" + x + ", " + y + ") " + pixel.describe()
}

private class LabelFrame(private val bytes: ByteArray) {
    /** [x] rightward and [y] **downward** from the top-left, converting `glReadPixels`' bottom-up rows. */
    fun at(x: Int, y: Int): IntArray {
        val row = LABEL_INTEGRATION_PIXELS - 1 - y
        val offset = (row * LABEL_INTEGRATION_PIXELS + x) * 4
        return IntArray(4) { bytes[offset + it].toInt() and 0xff }
    }

    fun drawnCount(): Int {
        var total = 0
        forEachPixel { _, _, pixel -> if (!pixel.isCloseTo(ABSENT)) total += 1 }
        return total
    }

    /** The pixel matching [colour] within [CHANNEL_TOLERANCE] and nearest it, or `null` if none does. */
    fun nearest(colour: IntArray): Sample? {
        var best: Sample? = null
        var bestDistance = CHANNEL_TOLERANCE + 1
        forEachPixel { x, y, pixel ->
            val distance = pixel.distanceTo(colour)
            if (distance <= CHANNEL_TOLERANCE && distance < bestDistance) {
                bestDistance = distance
                best = Sample(x, y, pixel)
            }
        }
        return best
    }

    /** One character per 4x4 block, keyed by nearest fixture colour: the message a human reads. */
    fun asciiMap(): String {
        val builder = StringBuilder()
        for (row in 0 until LABEL_INTEGRATION_PIXELS / MAP_GLYPH_PIXELS) {
            for (column in 0 until LABEL_INTEGRATION_PIXELS / MAP_GLYPH_PIXELS) {
                val pixel = at(
                    column * MAP_GLYPH_PIXELS + MAP_GLYPH_PIXELS / 2,
                    row * MAP_GLYPH_PIXELS + MAP_GLYPH_PIXELS / 2,
                )
                builder.append(
                    when {
                        pixel.isCloseTo(ABSENT) -> '.'
                        pixel.isCloseTo(PLACE_COLOUR) -> 'P'
                        pixel.isCloseTo(TOWN_COLOUR) -> 'T'
                        else -> '?'
                    },
                )
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private inline fun forEachPixel(action: (Int, Int, IntArray) -> Unit) {
        for (y in 0 until LABEL_INTEGRATION_PIXELS) {
            for (x in 0 until LABEL_INTEGRATION_PIXELS) action(x, y, at(x, y))
        }
    }
}

private const val MAP_GLYPH_PIXELS: Int = 4

private fun assertNear(sample: Sample, anchorX: Int, anchorY: Int, name: String) {
    val offsetX = abs(sample.x - anchorX)
    val offsetY = abs(sample.y - anchorY)
    assertTrue(
        offsetX <= MAXIMUM_ANCHOR_OFFSET && offsetY <= MAXIMUM_ANCHOR_OFFSET,
        "$name must land on its own projected anchor ($anchorX, $anchorY), but its ink is at " +
            sample.describe() + " -- ($offsetX, $offsetY) away, over a budget of " +
            MAXIMUM_ANCHOR_OFFSET,
    )
}

private fun IntArray.isCloseTo(other: IntArray): Boolean = distanceTo(other) <= CHANNEL_TOLERANCE

private fun IntArray.distanceTo(other: IntArray): Int = indices.maxOf { abs(this[it] - other[it]) }

private fun IntArray.describe(): String = "(${this[0]},${this[1]},${this[2]},${this[3]})"
