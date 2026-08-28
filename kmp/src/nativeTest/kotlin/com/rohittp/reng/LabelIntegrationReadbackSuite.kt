package com.rohittp.reng

import com.rohittp.reng.internal.firewall.LABEL_GLYPH_TEMPLATE
import com.rohittp.reng.internal.firewall.LABEL_MVT_BYTES
import com.rohittp.reng.internal.firewall.LABEL_SANS_STACK
import com.rohittp.reng.internal.firewall.LABEL_SERIF_STACK
import com.rohittp.reng.internal.firewall.LABEL_TILE_TEMPLATE
import com.rohittp.reng.internal.firewall.labelGlyphRange
import com.rohittp.reng.internal.firewall.labelGlyphUrls
import com.rohittp.reng.internal.firewall.labelMvtBytes
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
import com.rohittp.reng.internal.gl.GlyphQuadFootprint
import com.rohittp.reng.internal.gl.RenderContextAdoption
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.adoptRenderContext
import com.rohittp.reng.internal.gl.measureGlyphQuadRasterisation
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
 * **`drawLabels` itself is gated over a ground that paints**, and that took a second attempt worth
 * recording. The both-switches-off frame does not gate the flag at all: with `drawBasemap` off too,
 * no style is traversed and no tile selected, so the frame is empty before `drawLabels` is read.
 * [assertTheTwoSwitchesAreIndependentOverAGroundThatPaints] is the case that does, and its assertion
 * has to be "nothing but ground reached the frame" rather than "no pure label colour is present" --
 * a first frame carries a tenth of the fade, so the weaker form passes with the switch dead.
 *
 * **The driver is measured before four of these six cases are believed.** [measureGlyphQuadRasterisation]
 * draws the two labels' own glyph cells at the label pass's constant clip `w` of 1 and counts the
 * pixels that disagree with the analytic rectangle -- 0 on `Apple M3 Max`. Where it distrusts a
 * driver, the four cases whose evidence is a drawn label pixel skip out loud and the two that require
 * an empty frame still run. That matters here more than the printed driver name suggests: this test
 * asks for `MacosGlRenderer.DEFAULT`, and on a hosted runner the default *is* `Apple Software
 * Renderer` -- the rasteriser that failed `0.3.0`'s publication on the ground's much larger quads.
 *
 * **What this does not claim.** Not legibility: the fixture's glyphs are saturated distance fields,
 * so each draws as a solid block of its cell rather than as a letter, and legibility stays unverified
 * until Cycle J. Not line placement, not icons, not the ground -- every frame here draws
 * `drawBasemap = false` precisely so that the only thing in it is text.
 */
internal fun runLabelIntegrationReadbackSuite(binding: GlBinding, probe: RenderContextProbe) {
    val target = createLabelIntegrationTarget(binding)
    val dialect = (adoptRenderContext(binding) as? RenderContextAdoption.Adopted)?.profile?.dialect
        ?: throw AssertionError("the fixture context must satisfy the ES 3.0 requirement")
    val rasterisation = measureGlyphQuadRasterisation(
        binding,
        dialect,
        target,
        LABEL_INTEGRATION_PIXELS,
        labelIntegrationFootprints(),
    )
    println(
        "RenG label integration readback driver: " + binding.getString(GL_RENDERER) + " " +
            rasterisation.describe(),
    )
    try {
        // The four cases whose evidence is a drawn label pixel. On a driver that will not rasterise
        // this pass's own quads they would measure the driver rather than RenG, so they say so and
        // stand down; see `measureGlyphQuadRasterisation` for why that is a measurement and not a
        // driver-name check, and `theLabelIntegrationReadbackSuitePassesOnARealAppleCoreProfileContext`
        // for why a hosted runner reaches this file on `Apple Software Renderer` whatever it asks for.
        if (rasterisation.isTrustworthy) {
            assertTwoLabelsDrawAtTheirOwnAnchorsInTheirOwnColours(binding, probe, target)
            assertDroppingALayerDropsExactlyThatLabel(binding, probe, target)
            assertTheTwoSwitchesAreIndependentOverAGroundThatPaints(binding, probe, target)
            assertOneAggregateDiagnosticWhateverTheEngineExcluded(binding, probe, target)
        } else {
            println(
                "RenG label integration readback SKIPPED [the four cases that assert a drawn label " +
                    "pixel] " + rasterisation.describe() +
                    ": this driver does not rasterise the label pass's own quads. The two cases that " +
                    "assert an empty frame still ran.",
            )
        }
        // Neither of these asserts that anything drew, so neither can be answered by the driver's
        // rasterisation of a glyph quad: both require the frame to come back exactly as it was left.
        assertAStyleWithNoSymbolLayersDrawsNothing(binding, probe, target)
        assertDrawLabelsFalseDrawsNothing(binding, probe, target)
    } finally {
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * The glyph cells this suite's own two labels are drawn as, for E-labels task 18's probe: two side by
 * side at the place anchor, one at the town anchor, each box-centred on it the way the placement pass
 * centres a label.
 *
 * **Every number is derived from the fixture's own declarations rather than measured off a frame.**
 * [labelGlyphRange] emits each glyph 8 by 10 pixels; Rentile's packer surrounds it with
 * `GlyphRangeDecoder.BUFFER_PX` -- the 3 pixels
 * [com.rohittp.reng.internal.gl.LABEL_SDF_PIXELS_PER_UNIT]'s KDoc names -- on all four sides, making
 * the packed cell 14 by 16; and the style's `text-size` of 16 over the 24-pixel SDF em scales it by
 * two thirds. The place label carries two glyphs (codepoints 65 and 256, one from each sans range)
 * and the town label one (codepoint 66).
 *
 * Confirmed against a real frame at the time it was written: on `Apple M3 Max` the place label's ink
 * spans x 55..71 by y 60..70 and the town label's x 99..107 by y 60..70, which is two 9.33-pixel cells
 * and one, each 10.67 tall, centred on their own anchors -- the ink being inset from the cell by the
 * SDF buffer the fill band does not paint.
 *
 * No cell edge lands on a pixel centre, which is what makes the probe's analytic rectangle the only
 * correct answer rather than a fill-rule opinion.
 *
 * **One thing this footprint set cannot discriminate, said rather than hidden.** The fixture centres
 * its labels on the frame's own centre row, so these three rectangles are their own mirror image
 * about the horizontal axis: a probe that had dropped the y-flip between screen space and
 * `glReadPixels`' bottom-up rows would measure 0 here just the same. What rules that out is the label
 * readback suite's own footprint set, which sits in a band near the top of the frame and is disjoint
 * from its mirror -- and which measures 0 on all three rasterisers this project can reach. The town
 * cell, off to one side, is what closes the same question on the x axis here.
 */
private fun labelIntegrationFootprints(): List<GlyphQuadFootprint> {
    val width = FIXTURE_GLYPH_CELL_TEXELS_WIDE * FIXTURE_GLYPH_SCALE
    val height = FIXTURE_GLYPH_CELL_TEXELS_TALL * FIXTURE_GLYPH_SCALE
    val top = ANCHOR_Y - height / 2f
    val bottom = ANCHOR_Y + height / 2f
    return listOf(
        GlyphQuadFootprint("place glyph 0", PLACE_ANCHOR_X - width, top, PLACE_ANCHOR_X.toFloat(), bottom),
        GlyphQuadFootprint("place glyph 1", PLACE_ANCHOR_X.toFloat(), top, PLACE_ANCHOR_X + width, bottom),
        GlyphQuadFootprint("town glyph 0", TOWN_ANCHOR_X - width / 2f, top, TOWN_ANCHOR_X + width / 2f, bottom),
    )
}

/** [labelGlyphRange]'s own glyph width, plus Rentile's 3-pixel SDF buffer on each side. */
private const val FIXTURE_GLYPH_CELL_TEXELS_WIDE: Float = 8f + 2f * 3f

/** [labelGlyphRange]'s own glyph height, plus the same buffer. */
private const val FIXTURE_GLYPH_CELL_TEXELS_TALL: Float = 10f + 2f * 3f

/** The style's `text-size` of 16 over the 24-pixel em every SDF glyph entry is measured in. */
private const val FIXTURE_GLYPH_SCALE: Float = 16f / 24f

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
            frame.nearest(TOWN_COLOUR)?.describe(),
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
 * Both switches off over the style that does draw labels: the frame must come back exactly as the
 * target was left.
 *
 * **What this gates is narrower than its name suggests, and saying so is the point.** With
 * `drawBasemap` off too, no style is traversed and no tile is selected at all (task 8b's rule reads
 * *either* flag), so this frame is empty before `drawLabels` is ever consulted -- measured, by
 * removing the `drawLabels` guard on the label tile list and watching this case still pass. The flag
 * itself is gated by [assertTheTwoSwitchesAreIndependentOverAGroundThatPaints]; this case is the
 * both-off pairing, which is worth its own line and is not the same claim.
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

/**
 * E7's orthogonality in pixels: the same style and the same camera, with `drawBasemap` on throughout
 * and `drawLabels` moving.
 *
 * **This is the case that gates the `drawLabels` flag, and it needs a ground that paints.** The
 * fixture's style carries a `background` layer for exactly that reason: without one, a frame drawing
 * the basemap of a symbol-only style is empty -- measured, 0 drawn pixels -- and "no label drew" would
 * then be indistinguishable from "nothing drew", which is the vacuity this whole suite is built to
 * avoid. With it, `drawBasemap = true, drawLabels = false` must be a frame **full** of ground and
 * carrying neither text colour, and turning only the flag back on must add them over that same ground.
 *
 * Order matters: the labelless frame runs first, on a renderer whose fade has never advanced, so its
 * emptiness of text cannot be a fade artefact. The ramp then follows on the same renderer.
 */
private fun assertTheTwoSwitchesAreIndependentOverAGroundThatPaints(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val renderer = labelRenderer(binding, probe, TWO_LAYER_STYLE_JSON)
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        val groundOnly = clearAndDraw(
            binding,
            renderer,
            renderTarget,
            target,
            FramePlan(frameIndex = 0L, camera = labelCamera(), drawBasemap = true, drawLabels = false),
        )
        // Exactly every pixel, not merely most of them, and the exactness is the assertion's whole
        // power. A `nearest(PLACE_COLOUR) == null` check here would be satisfied by a label drawn at
        // the tenth of its opacity a first frame carries -- measured: removing the `drawLabels` guard
        // on the label tile list leaves 286 pixels of dim magenta in this frame and no pure one, so
        // that weaker check passes with the switch dead. "Nothing but ground reached the frame" does
        // not, at any opacity.
        assertEquals(
            0,
            groundOnly.countOfNot(GROUND_COLOUR),
            "drawLabels = false must leave a frame of nothing but ground, but " +
                groundOnly.countOfNot(GROUND_COLOUR) + " of " +
                (LABEL_INTEGRATION_PIXELS * LABEL_INTEGRATION_PIXELS) +
                " pixels are not " + GROUND_COLOUR.describe() + "\n" + groundOnly.asciiMap(),
        )

        var withLabels = groundOnly
        for (frameIndex in 1L..LABEL_FADE_RAMP.toLong()) {
            withLabels = clearAndDraw(
                binding,
                renderer,
                renderTarget,
                target,
                FramePlan(
                    frameIndex = frameIndex,
                    camera = labelCamera(),
                    drawBasemap = true,
                    drawLabels = true,
                ),
            )
        }
        val place = withLabels.nearest(PLACE_COLOUR)
            ?: throw AssertionError(
                "turning drawLabels back on over the same ground must add the place label, but no " +
                    "pixel carries " + PLACE_COLOUR.describe() + "\n" + withLabels.asciiMap(),
            )
        assertNear(place, PLACE_ANCHOR_X, ANCHOR_Y, "the place label over the ground")
        assertTrue(
            withLabels.countOf(GROUND_COLOUR) > MINIMUM_GROUND_PIXELS,
            "and must not cost the ground its own pixels",
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
 * The style's `background` colour, which is the only thing its ground paints. Distinct from [ABSENT]
 * and from both text colours in every channel that matters, so a frame can be read as ground, text, or
 * neither with no ambiguity.
 */
private val GROUND_COLOUR: IntArray = intArrayOf(16, 64, 192, 255)

/**
 * How much ground has to survive a frame that also draws labels. The camera sits at a tile's centre at
 * zoom 4, so one tile covers the whole frame with no seam in it -- which is why the labelless frame
 * above can demand *every* pixel rather than a budget -- and the labels themselves cover a few hundred
 * pixels of it. This is the floor that says the ground is still there underneath them.
 */
private const val MINIMUM_GROUND_PIXELS: Int = LABEL_INTEGRATION_PIXELS * LABEL_INTEGRATION_PIXELS / 2

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
 * ADR 0036 end to end, and the only case in the tree that runs the *emission* rather than the shape:
 * a style whose labels the engine excludes prepares successfully, draws no text at all, and leaves
 * exactly one aggregate diagnostic behind.
 *
 * **The fixture is the ADR's own motivating fact.** Rentile's `internal/glyph/ScriptSupport.kt` is
 * byte-identical between `0.5.0` and `0.6.0`, so Arabic still produces no glyph quads whatever the
 * style says. The two style layers are unchanged from every other case here -- only the *feature*
 * text differs -- so a consumer's experience is a map with its background and no text whatsoever,
 * which until this task nothing anywhere reported.
 *
 * **Two layers, and that is what makes the assertion mean "once per prepare".** The engine reports
 * complex-script exclusion once per layer per acquisition, so this frame excludes twice; a fixture
 * with one layer would emit one diagnostic under "once per prepare" and one under "once per
 * exclusion" alike, and could not tell them apart. Measured against a per-exclusion emitter, this
 * case sees two.
 *
 * The glyph closure is empty here rather than merely unused: the assembler counts the exclusion and
 * skips the feature before collecting its ranges, so no glyph url is composed and the fixture needs
 * no Arabic range to answer with.
 *
 * The control is the same camera, the same plan and the same two layers over the ordinary Latin
 * tile -- a frame that *does* draw its labels and must emit nothing at all, which is what stops this
 * case passing against an emitter that fires on every prepare.
 */
private fun assertOneAggregateDiagnosticWhateverTheEngineExcluded(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val excluded = RecordingSink()
    val excludedRenderer = labelRenderer(binding, probe, TWO_LAYER_STYLE_JSON, ARABIC_MVT_BYTES, excluded)
    val frame = try {
        val renderTarget = excludedRenderer.mintRenderTarget(FramebufferName(target.toUInt()))
        clearAndDraw(binding, excludedRenderer, renderTarget, target, labelPlan(0L))
    } finally {
        excludedRenderer.close()
    }

    assertEquals(
        0,
        frame.drawnCount(),
        "an unshapeable script must draw no text at all\n" + frame.asciiMap(),
    )
    assertEquals(
        listOf(
            Diagnostic(
                code = DiagnosticCode.LABEL_CONTENT_EXCLUDED,
                severity = DiagnosticSeverity.INFO,
                stage = PipelineStage.LABEL_PREPARATION,
            ),
        ),
        excluded.diagnostics,
        "one aggregate per prepare, whole and with nothing of the engine's in it",
    )

    val drawn = RecordingSink()
    val drawnRenderer = labelRenderer(binding, probe, TWO_LAYER_STYLE_JSON, LABEL_MVT_BYTES, drawn)
    val latin = try {
        val renderTarget = drawnRenderer.mintRenderTarget(FramebufferName(target.toUInt()))
        clearAndDraw(binding, drawnRenderer, renderTarget, target, labelPlan(0L))
    } finally {
        drawnRenderer.close()
    }

    assertTrue(latin.drawnCount() > 0, "the control frame must draw the labels this one lost")
    assertEquals(
        emptyList(),
        drawn.diagnostics,
        "a frame that lost no label content reports none: " + drawn.diagnostics,
    )
}

/**
 * The same two source layers with Arabic feature text. The style is unchanged, so nothing about the
 * *declaration* differs from the drawing cases -- only what the tile says.
 */
private val ARABIC_MVT_BYTES: ByteArray =
    labelMvtBytes("place" to "\u0645\u0631\u062D\u0628\u0627", "town_label" to "\u0634\u0627\u0631\u0639")

/** Every RenG diagnostic this renderer emitted, in order. */
private class RecordingSink : DiagnosticSink {
    private val recorded: MutableList<Diagnostic> = mutableListOf()

    val diagnostics: List<Diagnostic> get() = ArrayList(recorded)

    override fun emit(diagnostic: Diagnostic) {
        recorded += diagnostic
    }
}

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

/**
 * Every fixture style shares one `background` layer, first, so that the ground has something to paint.
 * It is the *only* thing the ground paints: measured, a style whose only other layers are symbol ones
 * rasterises to a fully transparent tile, because Rentile hands its text over as candidates rather
 * than drawing it -- which is what makes a background layer the difference between a ground case that
 * discriminates and one that cannot.
 */
private fun integrationStyle(layers: List<String>): String =
    """{"version":8,"name":"reng-label-integration",""" +
        """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" +
        """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#1040c0"}}""" +
        layers.joinToString("") { ",$it" } + """]}"""

private val PLACE_LAYER: String =
    symbolLayer("place", "place", LABEL_SANS_STACK, "#ff00ff", 0)

private val TOWN_LAYER: String =
    symbolLayer("town", "town_label", LABEL_SERIF_STACK, "#ffaa00", TOWN_TRANSLATE_X)

private val TWO_LAYER_STYLE_JSON: String = integrationStyle(listOf(PLACE_LAYER, TOWN_LAYER))

private val ONE_LAYER_STYLE_JSON: String = integrationStyle(listOf(PLACE_LAYER))

/**
 * The same document with both symbol layers gone, so the style is still legal and still declares its
 * source, its glyphs and the shared background -- only the thing that produces candidates is missing.
 */
private val NO_SYMBOL_STYLE_JSON: String = integrationStyle(emptyList())

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
private class IntegrationTransport(
    private val styleJson: String,
    private val tileBytes: ByteArray = LABEL_MVT_BYTES,
) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        val body = when {
            url == INTEGRATION_STYLE_URL -> styleJson.encodeToByteArray()
            url.startsWith(LABEL_TILE_URL_PREFIX) -> tileBytes
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

private fun labelRenderer(
    binding: GlBinding,
    probe: RenderContextProbe,
    styleJson: String,
    tileBytes: ByteArray = LABEL_MVT_BYTES,
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
): Renderer =
    createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS),
            transport = IntegrationTransport(styleJson, tileBytes),
            store = IntegrationStore(),
            basemapStyle = ResourceLocator(INTEGRATION_STYLE_URL),
            diagnosticSink = diagnosticSink,
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

    /** How many pixels do **not** carry [colour] within [CHANNEL_TOLERANCE]. */
    fun countOfNot(colour: IntArray): Int =
        LABEL_INTEGRATION_PIXELS * LABEL_INTEGRATION_PIXELS - countOf(colour)

    /** How many pixels carry [colour] within [CHANNEL_TOLERANCE]. */
    fun countOf(colour: IntArray): Int {
        var total = 0
        forEachPixel { _, _, pixel -> if (pixel.isCloseTo(colour)) total += 1 }
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
                        pixel.isCloseTo(GROUND_COLOUR) -> 'g'
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
