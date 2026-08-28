package com.rohittp.reng

import com.rohittp.reng.internal.firewall.LABEL_GLYPH_KEY
import com.rohittp.reng.internal.firewall.LABEL_GLYPH_TEMPLATE
import com.rohittp.reng.internal.firewall.LABEL_MVT_BYTES
import com.rohittp.reng.internal.firewall.LABEL_SANS_STACK
import com.rohittp.reng.internal.firewall.LABEL_SERIF_STACK
import com.rohittp.reng.internal.firewall.LABEL_TILE_TEMPLATE
import com.rohittp.reng.internal.firewall.ProtoBuffer
import com.rohittp.reng.internal.firewall.VALID_TILE_PNG
import com.rohittp.reng.internal.firewall.labelGlyph
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
import kotlin.math.min
import kotlin.math.sqrt
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
 * **Task 16 added the three assertions section 8 lists that nothing else here made.**
 * [assertCollisionRejectsTheLowerPriorityLabelAndKeepsTheHigher] is the one section 8 calls "the case
 * with real discriminating power": two labels placed on top of each other draw fewer pixels than the
 * same two apart, and the survivor is the one `symbol-sort-key` names -- asserted in **both**
 * directions, because the higher-priority label being also the first-declared one is a second way to
 * pass under the wrong rule. [assertEveryDrawnPixelFallsInsideTheProjectedLabelBox] bounds the ink by
 * a box composed from the fixture's own declared glyph metrics through Rentile's layout formula,
 * which is what catches a runaway anchor projection: every other case here asks whether a colour is
 * *present* near a point, and a second copy of the label elsewhere satisfies all of them. And
 * [assertLineLabelGlyphsFollowTheProjectedPolyline] puts a label on a genuinely curved,
 * genuinely asymmetric arc -- a straight horizontal line is the symmetry point of the whole feature
 * and passes with the tangent computation deleted -- and requires every glyph pixel to sit on it.
 *
 * **The driver is measured before eight of these ten cases are believed.** [measureGlyphQuadRasterisation]
 * draws the two labels' own glyph cells at the label pass's constant clip `w` of 1 and counts the
 * pixels that disagree with the analytic rectangle -- 0 on `Apple M3 Max`. Where it distrusts a
 * driver, the eight cases whose evidence is a drawn label pixel skip out loud and the two that
 * require an empty frame still run. **The guard is why those two are not written behind it**: Cycle
 * H's most recent catch is that a guard which fires first masks every rule beneath it, so the cases
 * that need no rasterised glyph deliberately sit outside it. That matters here more than the printed
 * driver name suggests: this test
 * asks for `MacosGlRenderer.DEFAULT`, and on a hosted runner the default *is* `Apple Software
 * Renderer` -- the rasteriser that failed `0.3.0`'s publication on the ground's much larger quads.
 *
 * **What this gate does not cover, stated plainly because a gate that claims more than it verifies is
 * worse than none.** **Legibility is unverified until Cycle J**, and that is the whole of the
 * "how it looks" half of this cycle: nothing here measures antialiasing quality, halo contrast
 * against the ground beneath it, the sharpness of the signed-distance field's iso-line, kerning,
 * hinting, or whether a reader could tell one glyph from another. The fixture's glyphs are
 * **saturated** distance fields, so each draws as a solid block of its own cell rather than as a
 * letter, and every assertion here is a relationship between pixels and analytically derived
 * numbers -- present, absent, inside a box, near a line, fewer than before. Cycle J's pixel
 * verification is what can answer the other question, and E-labels' own harness pass is the only
 * thing in this cycle that *looks* at a frame.
 *
 * Not covered either: the ground beneath the labels, which every frame here draws
 * `drawBasemap = false` for except the one case that needs it; icons, which nothing in RenG draws at
 * all; complex scripts, which produce no glyph quads to assert about; occlusion of labels by 3D
 * scene content, which does not exist; and any real mobile GPU, since ADR 0033 runs the mobile
 * targets in simulation only and this suite's only home is `macosArm64Test`.
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
        // The eight cases whose evidence is a drawn label pixel. On a driver that will not rasterise
        // this pass's own quads they would measure the driver rather than RenG, so they say so and
        // stand down; see `measureGlyphQuadRasterisation` for why that is a measurement and not a
        // driver-name check, and `theLabelIntegrationReadbackSuitePassesOnARealAppleCoreProfileContext`
        // for why a hosted runner reaches this file on `Apple Software Renderer` whatever it asks for.
        if (rasterisation.isTrustworthy) {
            assertTwoLabelsDrawAtTheirOwnAnchorsInTheirOwnColours(binding, probe, target)
            assertDroppingALayerDropsExactlyThatLabel(binding, probe, target)
            assertTheTwoSwitchesAreIndependentOverAGroundThatPaints(binding, probe, target)
            assertOneAggregateDiagnosticWhateverTheEngineExcluded(binding, probe, target)
            assertAnIconClaimsTheScreenSpaceItsSymbolOccupies(binding, probe, target)
            assertCollisionRejectsTheLowerPriorityLabelAndKeepsTheHigher(binding, probe, target)
            assertEveryDrawnPixelFallsInsideTheProjectedLabelBox(binding, probe, target)
            assertLineLabelGlyphsFollowTheProjectedPolyline(binding, probe, target)
        } else {
            println(
                "RenG label integration readback SKIPPED [the eight cases that assert a drawn label " +
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

/**
 * E-labels task 12's own end-to-end gate: **an icon takes screen space, and the text that wanted that
 * space loses it.**
 *
 * Nothing here draws the icon -- RenG has no pipeline that samples a sprite atlas, and that gap is
 * recorded rather than hidden -- so the icon's *effect* is the only evidence available, and it is
 * exactly the evidence that matters. Placement, geometry and the coupling all have unit cases of their
 * own; what those cannot show is that the manifest the firewall parsed while the style compiled
 * actually reaches the pass. Six things have to be alive in sequence for this case to pass: the style
 * declaring a sprite, the firewall proxying and jointly gating the pair, the registry retaining what it
 * parsed, the host handing it out before the invocation is discarded, `prepare()` carrying it to
 * placement, and placement resolving `imageName` against it. Any one of them dead and the town label
 * survives.
 *
 * **The control is the same style with `icon-image` removed and nothing else changed** -- the sprite is
 * still declared, still fetched and still gated, and the place layer still carries the sort key that
 * puts it first. So the case is a statement about the icon rather than about the sprite, the priority
 * or the fixture: without `icon-image` both labels are in the frame, and the only edit that removes the
 * town one is the icon that claims its pixels.
 *
 * The icon is 120 screen pixels across a 128-pixel frame -- a two-pixel sprite entry at `icon-size: 60`
 * -- because the town label sits 40 pixels east of the place anchor and a 60-pixel icon would stop just
 * short of it. The place label must still be in the frame, which is what distinguishes "the icon
 * claimed space" from "the whole symbol collapsed": neither half is optional here, so a symbol that
 * lost its icon would take its own text with it and the assertion below would fail rather than pass
 * for the wrong reason.
 */
private fun assertAnIconClaimsTheScreenSpaceItsSymbolOccupies(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val withoutIcon = labelRenderer(binding, probe, SPRITE_WITHOUT_ICON_STYLE_JSON)
    try {
        val renderTarget = withoutIcon.mintRenderTarget(FramebufferName(target.toUInt()))
        var frame = clearAndDraw(binding, withoutIcon, renderTarget, target, labelPlan(0L))
        for (frameIndex in 1L until LABEL_FADE_RAMP.toLong()) {
            frame = clearAndDraw(binding, withoutIcon, renderTarget, target, labelPlan(frameIndex))
        }
        assertTrue(
            frame.nearest(PLACE_COLOUR) != null && frame.nearest(TOWN_COLOUR) != null,
            "the control must draw both labels, or the icon case proves nothing\n" + frame.asciiMap(),
        )
    } finally {
        withoutIcon.close()
    }

    val withIcon = labelRenderer(binding, probe, ICON_STYLE_JSON)
    try {
        val renderTarget = withIcon.mintRenderTarget(FramebufferName(target.toUInt()))
        var frame = clearAndDraw(binding, withIcon, renderTarget, target, labelPlan(0L))
        for (frameIndex in 1L until LABEL_FADE_RAMP.toLong()) {
            frame = clearAndDraw(binding, withIcon, renderTarget, target, labelPlan(frameIndex))
        }
        val placeSample = frame.nearest(PLACE_COLOUR)
            ?: throw AssertionError(
                "the symbol carrying the icon must still draw its own text -- neither half is " +
                    "optional, so a symbol that failed to resolve its icon would be absent entirely" +
                    "\n" + frame.asciiMap(),
            )
        assertNear(placeSample, PLACE_ANCHOR_X, ANCHOR_Y, "the icon-bearing place label")
        assertEquals(
            null,
            frame.nearest(TOWN_COLOUR)?.describe(),
            "the town label must lose its place to the icon's collision box, but a pixel carrying " +
                TOWN_COLOUR.describe() + " is still in the frame\n" + frame.asciiMap(),
        )
    } finally {
        withIcon.close()
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
    extraLayout: String = "",
): String =
    """{"id":"$id","type":"symbol","source":"v","source-layer":"$sourceLayer",""" +
        """"layout":{"text-field":"{name}","text-font":["$stack"],"text-size":16$extraLayout},""" +
        """"paint":{"text-color":"$colour","text-translate":[$translateX,0]}}"""

/**
 * Every fixture style shares one `background` layer, first, so that the ground has something to paint.
 * It is the *only* thing the ground paints: measured, a style whose only other layers are symbol ones
 * rasterises to a fully transparent tile, because Rentile hands its text over as candidates rather
 * than drawing it -- which is what makes a background layer the difference between a ground case that
 * discriminates and one that cannot.
 */
private fun integrationStyle(layers: List<String>, sprite: String = ""): String =
    """{"version":8,"name":"reng-label-integration",""" +
        """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" + sprite +
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

/** The sprite base Rentile appends `.json` and `.png` to, exactly as `appendSpriteExtension` does. */
private const val INTEGRATION_SPRITE_BASE: String = "https://sprites.example/integration"

private val SPRITE_MEMBER: String = """"sprite":"$INTEGRATION_SPRITE_BASE","""

/**
 * One entry filling the whole 2-by-2 atlas image, which is what lets `icon-size` alone decide the
 * icon's screen extent: a 2-pixel entry at `pixelRatio` 1 and `icon-size` 60 is 120 screen pixels
 * across a 128-pixel frame, wide enough to reach the town label 40 pixels east of it.
 */
private val INTEGRATION_SPRITE_JSON: ByteArray =
    """{"marker":{"x":0,"y":0,"width":2,"height":2}}""".encodeToByteArray()

/**
 * `symbol-sort-key` puts the place layer's symbol first, ahead of the town layer that is declared
 * after it, so the icon reaches the collision index before the label it is meant to displace does. It
 * is in the control as well as in the icon style, so the case turns on `icon-image` and nothing else.
 */
private const val PLACE_SORT_KEY_LAYOUT: String = ""","symbol-sort-key":10"""

private const val ICON_LAYOUT: String = ""","icon-image":"marker","icon-size":60"""

private val SPRITE_WITHOUT_ICON_STYLE_JSON: String = integrationStyle(
    listOf(symbolLayer("place", "place", LABEL_SANS_STACK, "#ff00ff", 0, PLACE_SORT_KEY_LAYOUT), TOWN_LAYER),
    SPRITE_MEMBER,
)

private val ICON_STYLE_JSON: String = integrationStyle(
    listOf(
        symbolLayer(
            "place",
            "place",
            LABEL_SANS_STACK,
            "#ff00ff",
            0,
            PLACE_SORT_KEY_LAYOUT + ICON_LAYOUT,
        ),
        TOWN_LAYER,
    ),
    SPRITE_MEMBER,
)

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
            url == LINE_GLYPH_URL -> LINE_GLYPH_RANGE_BYTES
            url == "$INTEGRATION_SPRITE_BASE.json" -> INTEGRATION_SPRITE_JSON
            url == "$INTEGRATION_SPRITE_BASE.png" -> VALID_TILE_PNG
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

    /**
     * Every pixel that is not the cleared background, as samples rather than as a count -- which is
     * what an assertion about *where* the ink landed needs, and what [drawnCount] deliberately
     * throws away.
     */
    fun drawn(): List<Sample> {
        val samples = ArrayList<Sample>()
        forEachPixel { x, y, pixel -> if (!pixel.isCloseTo(ABSENT)) samples += Sample(x, y, pixel) }
        return samples
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

    /**
     * One character per 4x4 block, keyed by nearest fixture colour: the message a human reads.
     *
     * [extra] adds colours a single case brings with it -- the line fixture's road colour is in no
     * other frame -- so that a failure there prints a map rather than a row of question marks.
     */
    fun asciiMap(vararg extra: Pair<IntArray, Char>): String {
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
                        else -> extra.firstOrNull { pixel.isCloseTo(it.first) }?.second ?: '?'
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

// ---- task 16: the three assertions section 8 lists that nothing else in the tree makes ------------

/**
 * **Collision genuinely rejects, and the survivor is the one priority names.** Section 8 calls this
 * "the case with real discriminating power" and until this task nothing anywhere asserted it through
 * pixels: `LabelPlacementTest` pins the rule against hand-built candidates, which is a statement
 * about a comparator rather than about a frame.
 *
 * **Three frames, and the pair at the end is what makes it a statement about priority.** Section 8's
 * own vacuity trap is that "two labels at equal priority pass under either ordering rule"; the trap
 * one step past it is that two labels at *different* priorities still pass under the wrong rule when
 * the higher-priority one is also the one declared first. `LabelLayerStyle.priority` is the layer's
 * position in the style, larger winning, so a pass that ignored `symbol-sort-key` entirely would let
 * the **town** layer -- declared second -- win every collision. So the two overlapping frames give
 * the sort keys to opposite layers and require the survivor to swap with them:
 *
 *  - `place` at `symbol-sort-key: 10`, `town` at `0` -- the place label must survive, which the
 *    layer-order rule alone cannot produce;
 *  - `town` at `10`, `place` at `0` -- the town label must survive, which a *reversed* comparator
 *    cannot produce.
 *
 * No single ordering rule other than "larger `symbol-sort-key` wins" satisfies both.
 *
 * **The control is the same style with the town label's `text-translate` back at
 * [TOWN_TRANSLATE_X].** Both sort keys are already in it, so the only thing that changes between the
 * control and either case is *whether the two boxes overlap* -- the case turns on collision and on
 * nothing about the fixture, the priority or the declaration order.
 *
 * **Fewer pixels, and the specific loser gone.** A count alone would be satisfied by a pass that
 * dropped both labels, and a colour check alone would be satisfied by a pass that never drew the
 * loser at any spacing; the pair is what says one label was rejected *by* the other.
 *
 * **The two labels overlap by [COLLISION_OVERLAP_TRANSLATE_X] rather than exactly, and that number
 * is a vacuity this case had to be broken to find.** Written first with both labels on the same
 * anchor, the place-wins direction **passed with collision deleted outright** -- measured, by making
 * `textPlaceable` ignore the index. The place label's cell is 17.33 pixels wide and the town's is
 * 9.33, both centred on the same point, so the survivor's ink is a strict superset of the loser's
 * and the winner simply paints over it: "no pixel carries the town colour" is then true whether the
 * town label was rejected or merely hidden, and the pixel count falls to the winner's own ink either
 * way. Only the town-wins direction caught the deleted collision. Ten pixels of `text-translate`
 * leaves each label six pixels of screen the other cannot reach, so a label that drew and lost is
 * visible as itself; both directions catch it now.
 */
private fun assertCollisionRejectsTheLowerPriorityLabelAndKeepsTheHigher(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val apart = drawSaturatedFrame(binding, probe, target, COLLISION_CONTROL_STYLE_JSON)
    if (apart.nearest(PLACE_COLOUR) == null || apart.nearest(TOWN_COLOUR) == null) {
        throw AssertionError(
            "the control must draw both labels side by side, or neither collision case proves " +
                "anything\n" + apart.asciiMap(),
        )
    }
    val apartDrawn = apart.drawnCount()

    val placeWins = drawSaturatedFrame(binding, probe, target, COLLISION_PLACE_WINS_STYLE_JSON)
    val townWins = drawSaturatedFrame(binding, probe, target, COLLISION_TOWN_WINS_STYLE_JSON)
    println(
        "RenG label integration readback collision: apart=" + apartDrawn +
            " placeWins=" + placeWins.drawnCount() + " townWins=" + townWins.drawnCount(),
    )

    assertTrue(
        placeWins.drawnCount() < apartDrawn,
        "two labels placed on top of each other must draw fewer pixels than the same two apart, " +
            "but the overlapping frame drew " + placeWins.drawnCount() + " against " + apartDrawn +
            "\n" + placeWins.asciiMap(),
    )
    assertTrue(
        townWins.drawnCount() < apartDrawn,
        "and the same with the sort keys exchanged: " + townWins.drawnCount() + " against " +
            apartDrawn + "\n" + townWins.asciiMap(),
    )

    val survivingPlace = placeWins.nearest(PLACE_COLOUR)
        ?: throw AssertionError(
            "the place label carries the higher symbol-sort-key, so it must be the one that keeps " +
                "its place; nothing in the frame carries " + PLACE_COLOUR.describe() + "\n" +
                placeWins.asciiMap(),
        )
    assertNear(survivingPlace, PLACE_ANCHOR_X, ANCHOR_Y, "the higher-priority place label")
    assertEquals(
        null,
        placeWins.nearest(TOWN_COLOUR)?.describe(),
        "and the town label must lose its place entirely, but a pixel carrying " +
            TOWN_COLOUR.describe() + " is still in the frame\n" + placeWins.asciiMap(),
    )

    val survivingTown = townWins.nearest(TOWN_COLOUR)
        ?: throw AssertionError(
            "with the sort keys exchanged the town label is the higher-priority one and must " +
                "survive instead; nothing in the frame carries " + TOWN_COLOUR.describe() + "\n" +
                townWins.asciiMap(),
        )
    assertNear(survivingTown, COLLIDING_TOWN_ANCHOR_X, ANCHOR_Y, "the higher-priority town label")
    assertEquals(
        null,
        townWins.nearest(PLACE_COLOUR)?.describe(),
        "and the place label must now be the one that loses, but a pixel carrying " +
            PLACE_COLOUR.describe() + " is still in the frame\n" + townWins.asciiMap(),
    )
}

/**
 * **Coverage is bounded: every drawn pixel falls inside a box projected analytically from the
 * label's own quad extents.**
 *
 * This is the case that catches a runaway anchor projection -- the failure the forward-projection
 * task called "invisible in a rendered frame until labels are already in the wrong place". Every
 * other pixel assertion in this suite asks whether a colour is *present* somewhere near a predicted
 * point, and a pass that also painted a second copy of the label somewhere else entirely satisfies
 * all of them.
 *
 * **The box is derived, not measured.** [projectedLabelBox] composes the fixture's own declared
 * numbers -- the glyph's width, height, bearings and advance as [labelGlyphRange] encodes them, the
 * three-pixel SDF buffer Rentile's packer adds, the style's `text-size` over the 24-pixel em, and
 * the default 1.2-em line height -- through Rentile's documented layout formula. Nothing in it comes
 * from a frame, so a frame cannot agree with it by construction. Its predictions were checked
 * against the ink a real `Apple M3 Max` draws: x 54.7..72 and 98.7..108 by y 60.4..71.1, against the
 * 55..71 / 99..107 by 60..70 recorded in [labelIntegrationFootprints].
 *
 * **The lower bound is in this case rather than beside it, and it has to be.** "Every drawn pixel is
 * inside the box" is an upper bound, and an empty frame satisfies it perfectly --
 * `ModelReadbackSuite` measured exactly that failure mode, where a no-op draw passed two of its six
 * cases. So the case asserts a floor on the ink first and bounds it second, and a no-op label draw
 * fails on the floor.
 *
 * **Two labels, not one, and the second one is load-bearing.** A single box centred on the frame's
 * own centre is symmetric in both axes: a transposed or mirrored viewport transform would move the
 * ink onto itself. The town label sits [TOWN_TRANSLATE_X] pixels east of the frame's centre and
 * nowhere near the vertical axis, so its box is disjoint from its own mirror in x, and the vertical
 * asymmetry of the glyph cell -- 3.6 pixels above the anchor against 7.1 below it -- is what closes
 * the same question in y.
 */
private fun assertEveryDrawnPixelFallsInsideTheProjectedLabelBox(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val frame = drawSaturatedFrame(binding, probe, target, TWO_LAYER_STYLE_JSON)
    val boxes = listOf(
        projectedLabelBox(PLACE_ANCHOR_X.toDouble(), PLACE_LABEL_GLYPHS),
        projectedLabelBox(TOWN_ANCHOR_X.toDouble(), TOWN_LABEL_GLYPHS),
    )
    val drawn = frame.drawn()
    println(
        "RenG label integration readback coverage: drawn=" + drawn.size + " boxes=" +
            boxes.joinToString { it.describe() },
    )
    assertTrue(
        drawn.size >= MINIMUM_LABEL_INK_PIXELS,
        "the two labels must actually cover the screen space their quads claim: only " + drawn.size +
            " pixels drew, against a floor of " + MINIMUM_LABEL_INK_PIXELS + "\n" + frame.asciiMap(),
    )

    val outside = drawn.filter { sample -> boxes.none { box -> box.containsPixel(sample) } }
    assertEquals(
        emptyList(),
        outside.map { it.describe() },
        "every drawn pixel must fall inside a label's own analytically projected box (" +
            boxes.joinToString { it.describe() } + ", each with a " + COVERAGE_MARGIN_PIXELS +
            "-pixel rasterisation margin), but " + outside.size + " of " + drawn.size +
            " did not\n" + frame.asciiMap(),
    )
}

/**
 * **Line placement puts the glyphs on the line**, in 31 of the 34 corpus styles' most common
 * placement mode, which until this task nothing verified through pixels at all.
 *
 * **The trap this fixture exists to avoid.** A straight horizontal line is the symmetry point of the
 * entire feature: every tangent along it is identical, so a tangent computation that had been
 * deleted, transposed or replaced by a constant produces exactly the correct frame. [LINE_MVT_POINTS]
 * is a circular arc turning 68 degrees over its 110 projected pixels, asymmetric in both axes and
 * never horizontal anywhere along it, sampled at 12 vertices so that no single vertex turns more
 * than 6.4 degrees.
 *
 * **The glyph is deliberately wide and short**, 26 by 8 packed texels against the rest of the
 * fixture's 14 by 16, and that shape is what gives the distance assertion its power. A glyph drawn
 * in the polyline's own frame lies *along* the line, so its cell reaches 8.7 pixels along the
 * tangent and only 2.9 across it; the same cell drawn unrotated reaches 8.7 pixels **across** a line
 * running at 50 to 70 degrees. Measured on `Apple M3 Max`, as the worst drawn pixel's own distance
 * from the polyline: **2.92** as this stands, **8.46** with the glyph frames taking a constant
 * horizontal axis instead of the sampled tangent, and **10.59** with the label drawn as a rigid row
 * along the tangent it has at its anchor -- the bug a straight fixture is definitionally blind to.
 * [MAXIMUM_LINE_OFFSET_PIXELS] sits between the first and the second with margin on both sides. A
 * near-square cell -- the shape every other label fixture here uses -- separates those same cases by
 * 5.3 against 7.0, which no tolerance can distinguish from rasterisation.
 *
 * **And the ink has to climb.** The distance bound alone is satisfied by a label whose glyphs all
 * collapsed onto one anchor, since that anchor is itself on the line. The arc rises 64 pixels over
 * the span the label occupies, against the 5.3-pixel height of one glyph cell, so requiring the ink
 * to span [MINIMUM_LINE_INK_HEIGHT_PIXELS] vertically is a statement that the glyphs were
 * distributed along the curve rather than stacked at its midpoint. Sampling every glyph at the
 * label's own anchor leaves 92 drawn pixels of the 433 this draws, so the floor is what reports that
 * one first; the climb is the assertion that stays true of a collapse which somehow kept its ink.
 *
 * **Every number the assertion compares against is derived from the fixture's own tile coordinates**
 * -- see [LINE_SCREEN_POINTS] -- rather than from `projectGeographicPosition`, which is the function
 * a wrong answer here would live in.
 */
private fun assertLineLabelGlyphsFollowTheProjectedPolyline(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
) {
    val frame = drawSaturatedFrame(binding, probe, target, LINE_STYLE_JSON, LINE_MVT_BYTES)
    val ink = frame.drawn()
    val worst = ink.maxByOrNull { distanceToProjectedLine(it) }
    println(
        "RenG label integration readback line placement: ink=" + ink.size +
            " worstOffset=" + (worst?.let { distanceToProjectedLine(it) } ?: -1.0) +
            " at " + (worst?.describe() ?: "nothing"),
    )
    assertTrue(
        ink.size >= MINIMUM_LINE_INK_PIXELS,
        "a line-placed label must reach the frame: only " + ink.size + " pixels drew, against a " +
            "floor of " + MINIMUM_LINE_INK_PIXELS + "\n" + frame.asciiMap(ROAD_COLOUR to 'R'),
    )
    assertEquals(
        ink.size,
        frame.countOf(ROAD_COLOUR),
        "and every one of them must carry the road layer's own text colour " +
            ROAD_COLOUR.describe() + "\n" + frame.asciiMap(ROAD_COLOUR to 'R'),
    )

    val worstOffset = distanceToProjectedLine(requireNotNull(worst) { "the floor above found ink" })
    assertTrue(
        worstOffset <= MAXIMUM_LINE_OFFSET_PIXELS,
        "every glyph pixel must sit on the projected polyline, but " + worst.describe() +
            " is " + worstOffset + " pixels from it, over a budget of " +
            MAXIMUM_LINE_OFFSET_PIXELS + "\n" + frame.asciiMap(ROAD_COLOUR to 'R'),
    )

    val climb = ink.maxOf { it.y } - ink.minOf { it.y }
    assertTrue(
        climb >= MINIMUM_LINE_INK_HEIGHT_PIXELS,
        "and the glyphs must be distributed along the curve rather than stacked at its anchor: the " +
            "ink spans " + climb + " rows against the " + MINIMUM_LINE_INK_HEIGHT_PIXELS +
            " the arc rises over the label's own extent\n" + frame.asciiMap(ROAD_COLOUR to 'R'),
    )
}

// ---- task 16's fixture ---------------------------------------------------------------------------

/**
 * One renderer, one style, and [LABEL_FADE_RAMP] prepares of the same plan, returning the last
 * frame: the shape every case here that reads a saturated colour needs, so that "the fade had not
 * finished" is never an explanation for a missing pixel.
 */
private fun drawSaturatedFrame(
    binding: GlBinding,
    probe: RenderContextProbe,
    target: Int,
    styleJson: String,
    tileBytes: ByteArray = LABEL_MVT_BYTES,
): LabelFrame {
    val renderer = labelRenderer(binding, probe, styleJson, tileBytes)
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        var frame = clearAndDraw(binding, renderer, renderTarget, target, labelPlan(0L))
        for (frameIndex in 1L until LABEL_FADE_RAMP.toLong()) {
            frame = clearAndDraw(binding, renderer, renderTarget, target, labelPlan(frameIndex))
        }
        return frame
    } finally {
        renderer.close()
    }
}

private fun sortKeyLayout(sortKey: Int): String = ""","symbol-sort-key":$sortKey"""

/**
 * The two ordinary layers with explicit and unequal `symbol-sort-key`s, and the town label's
 * `text-translate` as the only other free variable. Both keys are present in the control as well as
 * in the two overlapping cases, so nothing but the overlap changes between them.
 */
private fun collisionStyle(placeSortKey: Int, townSortKey: Int, townTranslateX: Int): String =
    integrationStyle(
        listOf(
            symbolLayer("place", "place", LABEL_SANS_STACK, "#ff00ff", 0, sortKeyLayout(placeSortKey)),
            symbolLayer(
                "town",
                "town_label",
                LABEL_SERIF_STACK,
                "#ffaa00",
                townTranslateX,
                sortKeyLayout(townSortKey),
            ),
        ),
    )

private val COLLISION_CONTROL_STYLE_JSON: String =
    collisionStyle(placeSortKey = 10, townSortKey = 0, townTranslateX = TOWN_TRANSLATE_X)

private val COLLISION_PLACE_WINS_STYLE_JSON: String =
    collisionStyle(placeSortKey = 10, townSortKey = 0, townTranslateX = COLLISION_OVERLAP_TRANSLATE_X)

private val COLLISION_TOWN_WINS_STYLE_JSON: String =
    collisionStyle(placeSortKey = 0, townSortKey = 10, townTranslateX = COLLISION_OVERLAP_TRANSLATE_X)

/**
 * How far apart the two colliding labels' anchors sit, in screen pixels.
 *
 * **Not zero, and the reason is measured rather than aesthetic** -- see this case's own KDoc. Ten
 * pixels puts the place label's ink at x 54.67..72 and the town label's at 68.67..78, so the two
 * overlap over the 7.3 pixels of collision geometry that decide the case while each label keeps six
 * pixels of screen the other cannot paint. Their collision boxes, which carry the default two-pixel
 * `text-padding` on every side, are 52.67..74 and 66.67..80 and intersect comfortably.
 */
private const val COLLISION_OVERLAP_TRANSLATE_X: Int = 10

/** Where the town label's ink sits in the two colliding frames. */
private const val COLLIDING_TOWN_ANCHOR_X: Int = LABEL_INTEGRATION_PIXELS / 2 + COLLISION_OVERLAP_TRANSLATE_X

/** `AĀ`: the place layer's feature text is two codepoints, one from each of the two sans ranges. */
private const val PLACE_LABEL_GLYPHS: Int = 2

/** `B`, in the serif stack. */
private const val TOWN_LABEL_GLYPHS: Int = 1

/**
 * How much ink the two labels together must put on screen. Their two cells are 17.3 by 10.7 and 9.3
 * by 10.7 output pixels, which is 285 whole pixels before any boundary is lost to the fill rule;
 * this floor is well under that and well over anything a partially wired pass produces. Measured at
 * 286 on `Apple M3 Max`.
 */
private const val MINIMUM_LABEL_INK_PIXELS: Int = 220

/**
 * How far outside its analytic box a drawn pixel may sit. A glyph quad is rasterised by the pixel-centre
 * rule with no multisampling anywhere in this fixture, so a covered pixel's centre is inside the quad
 * exactly; one pixel of slack absorbs the `Float` the quad's corners reach the GPU as and nothing more.
 */
private const val COVERAGE_MARGIN_PIXELS: Double = 1.0

/**
 * The box the fixture's own declarations say a label of [glyphCount] glyphs centred on
 * `(anchorX, ANCHOR_Y)` must fall inside, in `CONTEXT.md`'s continuous output-pixel screen space.
 *
 * Rentile's `LabelLayout` places glyph *i* of a single-line, centre-anchored label at
 * `x = (i * advance - blockWidth / 2 + left - buffer) * scale` and every glyph of it at
 * `y = (-lineHeight / 2 - top - buffer) * scale`, each cell then spanning the packed entry's own
 * buffered extent. Substituting this fixture's numbers -- [labelGlyph]'s advance of 12, left bearing
 * of 1 and top bearing of -12 over an 8-by-10 glyph, Rentile's 3-pixel SDF buffer, the style's
 * `text-size` of 16 over the 24-pixel em, and the specification's default 1.2-em line height --
 * gives a two-glyph label spanning x -9.33..8 and y -3.6..7.07 about its anchor.
 *
 * The vertical extent is deliberately **not** symmetric about the anchor, and that is the fixture's
 * own doing rather than an approximation: a glyph's cell hangs 7.07 pixels below the anchor row and
 * reaches only 3.6 above it, so a box that had been centred instead would be wrong in a way a
 * y-flipped projection could hide.
 */
private fun projectedLabelBox(anchorX: Double, glyphCount: Int): AnalyticBox {
    val advance = GLYPH_ADVANCE_UNITS
    val blockWidth = glyphCount * advance
    val firstX = (0 * advance - blockWidth / 2.0 + GLYPH_LEFT_UNITS - SDF_BUFFER_TEXELS) * GLYPH_SCALE
    val lastX =
        ((glyphCount - 1) * advance - blockWidth / 2.0 + GLYPH_LEFT_UNITS - SDF_BUFFER_TEXELS) * GLYPH_SCALE
    val cellWidth = (GLYPH_WIDTH_TEXELS + 2.0 * SDF_BUFFER_TEXELS) * GLYPH_SCALE
    val cellHeight = (GLYPH_HEIGHT_TEXELS + 2.0 * SDF_BUFFER_TEXELS) * GLYPH_SCALE
    val topY = (-LINE_HEIGHT_EM * EM_TEXELS / 2.0 - GLYPH_TOP_UNITS - SDF_BUFFER_TEXELS) * GLYPH_SCALE
    return AnalyticBox(
        left = anchorX + firstX,
        top = ANCHOR_Y + topY,
        right = anchorX + lastX + cellWidth,
        bottom = ANCHOR_Y + topY + cellHeight,
    )
}

/** A rectangle in continuous output-pixel screen space, with [COVERAGE_MARGIN_PIXELS] built into the test. */
private class AnalyticBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    /**
     * Whether [sample]'s own **centre** is inside this box. A pixel indexed `(x, y)` has its centre
     * at `(x + 0.5, y + 0.5)`, which is the point the rasteriser tested against the quad.
     */
    fun containsPixel(sample: Sample): Boolean {
        val centreX = sample.x + 0.5
        val centreY = sample.y + 0.5
        return centreX >= left - COVERAGE_MARGIN_PIXELS && centreX <= right + COVERAGE_MARGIN_PIXELS &&
            centreY >= top - COVERAGE_MARGIN_PIXELS && centreY <= bottom + COVERAGE_MARGIN_PIXELS
    }

    fun describe(): String = "[" + left + ", " + top + " .. " + right + ", " + bottom + "]"
}

/** Rentile's `GlyphRangeDecoder.EM_PX`: every glyph bearing and advance in a range is in these units. */
private const val EM_TEXELS: Double = 24.0

/** Rentile's `GlyphRangeDecoder.BUFFER_PX`, added by its packer on all four sides of every cell. */
private const val SDF_BUFFER_TEXELS: Double = 3.0

/** The style's `text-size` over [EM_TEXELS]. Every fixture style here declares 16. */
private const val GLYPH_SCALE: Double = 16.0 / EM_TEXELS

/** `text-line-height`'s specification default, which decides where a single line's top edge sits. */
private const val LINE_HEIGHT_EM: Double = 1.2

/** [labelGlyph]'s own declared glyph, and the three bearings it encodes. */
private const val GLYPH_WIDTH_TEXELS: Double = 8.0
private const val GLYPH_HEIGHT_TEXELS: Double = 10.0
private const val GLYPH_LEFT_UNITS: Double = 1.0
private const val GLYPH_TOP_UNITS: Double = -12.0
private const val GLYPH_ADVANCE_UNITS: Double = 12.0

// ---- the line-placement fixture ------------------------------------------------------------------

/** The road layer's `text-color`. Distinct in every channel from [ABSENT] and from both other labels. */
private val ROAD_COLOUR: IntArray = intArrayOf(0, 255, 0, 255)

private const val LINE_SOURCE_LAYER: String = "road"

private const val LINE_STACK: String = "Label Line Regular"

/** Five glyphs, `ABCDE`, all in the `0-255` block of [LINE_STACK]. */
private val LINE_CODEPOINTS: List<Int> = listOf(65, 66, 67, 68, 69)

private val LINE_TEXT: String = LINE_CODEPOINTS.map { it.toChar() }.joinToString("")

/**
 * `symbol-spacing`, chosen so the fixture's own arc has room for **exactly one** repeat.
 *
 * The walk anchors a repeat at half a spacing from the run's start and every spacing after it, so a
 * spacing of 112 puts the only anchor at 56 along a 109.95-pixel run: the label's own extent is
 * -41.33..40 about its anchor, which is 14.67..96 and comfortably inside the run, and the next
 * anchor would be at 168 and off the end of it. One repeat is what makes the ink a single readable
 * ribbon rather than several overlapping ones, and what lets the assertions below name a single
 * expected shape.
 */
private const val LINE_SYMBOL_SPACING: Int = 112

private val LINE_LAYER: String =
    """{"id":"road","type":"symbol","source":"v","source-layer":"$LINE_SOURCE_LAYER",""" +
        """"layout":{"text-field":"{name}","text-font":["$LINE_STACK"],"text-size":16,""" +
        """"symbol-placement":"line","symbol-spacing":$LINE_SYMBOL_SPACING},""" +
        """"paint":{"text-color":"#00ff00"}}"""

private val LINE_STYLE_JSON: String = integrationStyle(listOf(LINE_LAYER))

/**
 * A circular arc of radius 92.5 output pixels turning 68 degrees, in the tile coordinates the
 * fixture's vector tile declares it in.
 *
 * **Asymmetric in both axes, curved everywhere, and horizontal nowhere.** It runs from a heading of
 * -16.7 degrees to one of -84.8, so no two of its eleven segments share a tangent and none of them is
 * axis-aligned. A straight line -- the obvious fixture -- is the symmetry point of the whole feature
 * and would pass with the tangent computation deleted.
 *
 * Twelve vertices rather than five, so that no single vertex turns more than 6.4 degrees: a glyph
 * cell drawn in the frame of a sample near a sharp vertex overhangs the polyline by its own
 * half-length times the sine of that turn, and a coarse arc would spend the distance budget below on
 * the polyline's own corners rather than on the thing being measured.
 */
private val LINE_MVT_POINTS: List<Pair<Int, Int>> = listOf(
    1744 to 2368, 1819 to 2341, 1891 to 2306, 1959 to 2263, 2021 to 2214, 2078 to 2158,
    2129 to 2096, 2173 to 2029, 2209 to 1957, 2237 to 1882, 2257 to 1805, 2269 to 1726,
)

/**
 * The same arc in output pixels, derived from the tile coordinates above by arithmetic that involves
 * no camera code at all.
 *
 * At pitch 0 and bearing 0 the map-to-screen transform is a uniform scale: RenG's world is
 * `512 * 2^zoom` logical pixels across ([com.rohittp.reng.internal.projection] composes exactly
 * that), so one tile is 512 pixels at every zoom and one unit of a 4096-extent tile is
 * [MVT_UNITS_PER_PIXEL] of them. Web Mercator's y is linear in a tile's own y for the same reason it
 * is linear in the tile grid, so both axes take the same constant. [labelCamera] sits at the centre
 * of the tile these coordinates are in, which puts tile `(2048, 2048)` at the frame's centre.
 *
 * Deriving the expectation this way rather than through `projectGeographicPosition` is deliberate:
 * that function is where a wrong answer would live, and an expectation computed with it would agree
 * with any answer at all.
 */
private val LINE_SCREEN_POINTS: List<Pair<Double, Double>> = LINE_MVT_POINTS.map { (x, y) ->
    LABEL_INTEGRATION_PIXELS / 2.0 + (x - TILE_CENTRE_MVT) / MVT_UNITS_PER_PIXEL to
        LABEL_INTEGRATION_PIXELS / 2.0 + (y - TILE_CENTRE_MVT) / MVT_UNITS_PER_PIXEL
}

/** The mid-tile coordinate every fixture feature is placed relative to; see [labelCamera]. */
private const val TILE_CENTRE_MVT: Double = 2048.0

/** A 4096-unit tile extent over the 512 output pixels one tile covers at any zoom. */
private const val MVT_UNITS_PER_PIXEL: Double = 4096.0 / 512.0

/** The distance from one drawn pixel's centre to the nearest point of [LINE_SCREEN_POINTS]. */
private fun distanceToProjectedLine(sample: Sample): Double {
    val pointX = sample.x + 0.5
    val pointY = sample.y + 0.5
    var best = Double.MAX_VALUE
    for (index in 0 until LINE_SCREEN_POINTS.size - 1) {
        val (startX, startY) = LINE_SCREEN_POINTS[index]
        val (endX, endY) = LINE_SCREEN_POINTS[index + 1]
        val runX = endX - startX
        val runY = endY - startY
        val along = ((pointX - startX) * runX + (pointY - startY) * runY) / (runX * runX + runY * runY)
        val clamped = along.coerceIn(0.0, 1.0)
        val offsetX = pointX - (startX + clamped * runX)
        val offsetY = pointY - (startY + clamped * runY)
        best = min(best, sqrt(offsetX * offsetX + offsetY * offsetY))
    }
    return best
}

/**
 * How far a glyph pixel may sit from the projected polyline.
 *
 * Derived from the fixture's own cell, not chosen: a 26-by-8 packed cell at [GLYPH_SCALE] is 17.33
 * by 5.33 output pixels, so a cell centred on the line and rotated into its frame reaches 2.67
 * pixels across it, plus up to 0.94 more where the cell overhangs a vertex the polyline turns 6.4
 * degrees at. The worst corner of the fixture's five cells is 3.26 pixels out. The same five cells
 * drawn unrotated reach 9.15, and drawn as one horizontal row at the anchor reach 34.8.
 */
private const val MAXIMUM_LINE_OFFSET_PIXELS: Double = 5.5

/**
 * How much ink the line label must put on screen: five 17.33-by-5.33 cells, overlapping their
 * neighbours by 1.33 pixels of advance, is about 430 whole pixels. Measured at 435 on `Apple M3 Max`.
 */
private const val MINIMUM_LINE_INK_PIXELS: Int = 300

/**
 * How many rows the ink must span. The arc rises 64.5 output pixels over the extent the label
 * occupies; one glyph cell is 5.33 tall, so a label whose glyphs had collapsed onto a single anchor
 * -- which the distance bound alone permits, that anchor being on the line -- cannot reach half of
 * this.
 */
private const val MINIMUM_LINE_INK_HEIGHT_PIXELS: Int = 40

/**
 * The vector tile the line style reads: one `road` layer holding one LineString feature whose `name`
 * is [LINE_TEXT].
 *
 * The protobuf *encoder* is [ProtoBuffer], shared with the handover fixture; what is here is the
 * two constructs a LineString needs that a point feature does not -- geometry type 2, and a `MoveTo`
 * followed by a single `LineTo` run.
 */
private val LINE_MVT_BYTES: ByteArray = ProtoBuffer()
    .apply {
        messageField(3) {
            varintField(15, 2L)
            stringField(1, LINE_SOURCE_LAYER)
            messageField(2) {
                varintField(1, 1L)
                packedVarints(2, listOf(0L, 0L))
                varintField(3, LINE_GEOMETRY_TYPE)
                packedVarints(4, lineStringGeometry(LINE_MVT_POINTS))
            }
            stringField(3, "name")
            messageField(4) { stringField(1, LINE_TEXT) }
            varintField(5, MVT_EXTENT)
        }
    }
    .bytes()

/** MVT's `GeomType.LINESTRING`. */
private const val LINE_GEOMETRY_TYPE: Long = 2L

private const val MVT_EXTENT: Long = 4096L

/**
 * One `MoveTo` of a single point followed by one `LineTo` of every point after it, with every
 * coordinate a zig-zag delta from the cursor's previous position.
 */
private fun lineStringGeometry(points: List<Pair<Int, Int>>): List<Long> {
    val commands = ArrayList<Long>(2 * points.size + 2)
    commands += mvtCommand(MVT_MOVE_TO, 1)
    commands += mvtZigZag(points[0].first)
    commands += mvtZigZag(points[0].second)
    commands += mvtCommand(MVT_LINE_TO, points.size - 1)
    for (index in 1 until points.size) {
        commands += mvtZigZag(points[index].first - points[index - 1].first)
        commands += mvtZigZag(points[index].second - points[index - 1].second)
    }
    return commands
}

private fun mvtCommand(id: Int, count: Int): Long = ((count shl 3) or id).toLong()

private fun mvtZigZag(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong()

private const val MVT_MOVE_TO: Int = 1
private const val MVT_LINE_TO: Int = 2

/**
 * The line fixture's own Glyph Range: five saturated glyphs, each **wide and short** where the rest
 * of this file's are nearly square.
 *
 * A 20-by-2 glyph packs into a 26-by-8 cell, which is 17.33 by 5.33 output pixels at this fixture's
 * scale -- a cell more than three times as long as it is thick, so which way it is turned is
 * measurable in a frame. The top bearing of -13 is what centres that cell on the line rather than
 * hanging it below: Rentile places a single line's cell at `(-lineHeight / 2 - top - buffer) * scale`
 * downward from the anchor, which for a 1.2-em line height and this bearing is -2.93, half the cell's
 * own 5.33 height to within a third of a pixel. The rest of the fixture leaves that offset alone and
 * is 3.6 above the anchor against 7.07 below.
 *
 * The advance of 24 is one em, so consecutive cells overlap by 1.33 pixels and the label's ink is one
 * continuous ribbon along the arc.
 */
private val LINE_GLYPH_RANGE_BYTES: ByteArray = ProtoBuffer()
    .apply {
        messageField(1) {
            stringField(1, LINE_STACK)
            stringField(2, "0-255")
            LINE_CODEPOINTS.forEach { codepoint ->
                messageField(3) {
                    varintField(1, codepoint.toLong())
                    bytesField(
                        2,
                        ByteArray(
                            ((LINE_GLYPH_WIDTH + 2 * SDF_BUFFER_TEXELS.toInt()) *
                                (LINE_GLYPH_HEIGHT + 2 * SDF_BUFFER_TEXELS.toInt())),
                        ) { 0xFF.toByte() },
                    )
                    varintField(3, LINE_GLYPH_WIDTH.toLong())
                    varintField(4, LINE_GLYPH_HEIGHT.toLong())
                    varintField(5, mvtZigZag(LINE_GLYPH_LEFT))
                    varintField(6, mvtZigZag(LINE_GLYPH_TOP))
                    varintField(7, LINE_GLYPH_ADVANCE)
                }
            }
        }
    }
    .bytes()

private const val LINE_GLYPH_WIDTH: Int = 20
private const val LINE_GLYPH_HEIGHT: Int = 2
private const val LINE_GLYPH_LEFT: Int = 1
private const val LINE_GLYPH_TOP: Int = -13
private const val LINE_GLYPH_ADVANCE: Long = 24L

/** [LINE_STACK]'s own glyph url, composed the way [labelGlyphUrls] composes the other three. */
private val LINE_GLYPH_URL: String =
    "https://glyphs.example/Label%20Line%20Regular/0-255.pbf?key=$LABEL_GLYPH_KEY"
