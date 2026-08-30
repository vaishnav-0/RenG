package com.rohittp.reng

import com.rohittp.reng.internal.firewall.LABEL_GLYPH_TEMPLATE
import com.rohittp.reng.internal.firewall.LABEL_SANS_STACK
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
import com.rohittp.reng.internal.gl.GROUND_DRAPE_LIFT_METRES
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlyphQuadFootprint
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.measureGlyphQuadRasterisation
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectGeographicPosition
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **Cycle E-terrain task 19's gate: everything a frame can anchor to the ground, over real relief,
 * through the public API, counted in pixels.**
 *
 * Wave 2 gave three different kinds of content a height off the terrain — a `GROUND_RELATIVE`
 * placement (task 16), a draped `Geometry` (task 17) and a label anchor (task 18) — and each of the
 * three landed with its own tests. What no gate covered is the **set**: one fixture in which a
 * sticker, a quad and a name all ride the same drawn surface, and in which the negative that makes
 * the other four mean anything — a frame with no terrain at all — is drawn by the same renderer.
 * That is what this suite is, and its four positives are deliberately different *shapes* of anchor
 * rather than four readings of one.
 *
 * ## What it adds over `runTerrainFrameReadbackSuite`, which is not nothing and is not everything
 *
 * That suite's cases 5 to 9 already assert a ground-relative placement, a drape, the coplanar
 * survivor count and the no-terrain negative, and they do it in **both projections**, which nothing
 * here does. Three things are this suite's own:
 *
 * - **Its placement case stands on relief rather than on a plateau.** Case 5 there rides a *uniform*
 *   1,000 m DEM and says so in as many words — "a uniform DEM says nothing about the reconstruction
 *   rule and is not asked to" — which is this cycle's own recorded symmetry point: on a plateau a
 *   ground cell's four corners and the texel under the point are the same number. This suite's two
 *   stickers stand on [DEM_RIDGE_PNG], half a ground cell off the corner, where they are not: the
 *   reconstructed height there is 968.75 m against the 984.4 m of the texel underneath.
 * - **Its two anchors part inside one frame.** The `GROUND_RELATIVE` sticker and the `ABSOLUTE` one
 *   are the *same* latitude, longitude and altitude in the *same* `FramePlan`, differing in one enum
 *   value, so the contrast is one measurement rather than a comparison of two renders that could
 *   have differed for any other reason. The roles are then swapped between the two images, which is
 *   what stops the reading being "the second sticker never draws".
 * - **A label rides its feature in pixels, which nothing anywhere asserted.**
 *   `RendererLabelTerrainTest` proves `prepare()` puts the anchor at the right *pixel* and stops
 *   there; `LabelIntegrationReadbackSuite` draws labels and knows nothing about terrain. This is the
 *   only place where a name drawn over a ridge is measured against a name drawn over the sea.
 *
 * ## The five cases and what each one alone would survive
 *
 * - [assertGroundRelativeAndAbsoluteStickersPartInOneFrameOverRelief] — the placement half of ADR
 *   0040. Alone it would survive a build whose ground drew no relief at all, since a sticker at sea
 *   level under a *flat* ground is not buried and the mode swap would still swap nothing; the
 *   sea-level arm is what closes that.
 * - [assertADrapedGeometryFollowsTheRidgeRatherThanCuttingThroughIt] — the drape half. Alone it
 *   would survive a lift of any size, including none, because a quad half deleted along its creases
 *   still covers its footprint.
 * - [assertTheCoplanarDrapeSurvivesTheGroundItRides] — the one case that can see
 *   [GROUND_DRAPE_LIFT_METRES], and the only assertion in this file that is a *percentage* rather
 *   than a floor. Alone it would survive a build with no ground-relative resolution whatsoever, since
 *   a quad that never met the ground never loses to it; the sunk control is what closes that.
 * - [assertALabelRidesTheFeatureItNames] — the label half, and the only case here that reads a
 *   glyph. Alone it would survive a drape and a placement both wired to nothing, because the label
 *   path takes its height one layer earlier, in `placeLabels`.
 * - [assertAFrameWithNoTerrainIsUnchangedByEveryAnchorMode] — **the negative without which the other
 *   four can all pass vacuously.** A build that resolved every altitude as "wherever the terrain is"
 *   and a build that resolved none of them are told apart here and nowhere else in this file: over a
 *   style declaring no `terrain`, a `GROUND_RELATIVE` sticker and a `GROUND_RELATIVE` `Geometry` must
 *   read back **byte for byte** as `ABSOLUTE` ones, and the raised arm beside them is what proves the
 *   instrument can see an altitude at all.
 *
 * ## What it does not claim
 *
 * **Nothing about terrain fidelity, and nothing about curvature.** Whether the drawn surface is the
 * shape the DEM describes, whether a ridge looks like a ridge, and whether a globe's horizon bends
 * correctly are all Cycle J's pixel verification. Every assertion below is a relationship between
 * pixels of this fixture's own frames — a count, a centroid difference, a survivor percentage — and
 * none of them would notice a surface that was wrong in the same way twice.
 *
 * **One projection.** Mercator only. The globe's ground-relative arms are
 * `runTerrainFrameReadbackSuite`'s cases 5, 8 and 9; a globe drape is coplanar in a way this fixture's
 * camera cannot reach and the cycle records it as owed.
 *
 * **One camera per case.** Task 14's spike swept five pitch-and-bearing pairs and found the coplanar
 * residual varying by a factor of two across them. Case 3 asserts pitch 0, which is where the spike's
 * own lift ladder was measured and where its 102,370-of-102,400 figure comes from.
 */
internal fun runGroundAnchorReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createGroundAnchorTarget(binding)
    val transport = GroundAnchorTransport()
    val terrainRenderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(GROUND_ANCHOR_READBACK_PIXELS, GROUND_ANCHOR_READBACK_PIXELS),
            transport = transport,
            store = GroundAnchorStore(),
            basemapStyle = ResourceLocator(ANCHOR_STYLE_URL),
        ),
        binding,
        probe,
    )
    val plainRenderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(GROUND_ANCHOR_READBACK_PIXELS, GROUND_ANCHOR_READBACK_PIXELS),
            transport = transport,
            store = GroundAnchorStore(),
            basemapStyle = ResourceLocator(ANCHOR_PLAIN_STYLE_URL),
        ),
        binding,
        probe,
    )

    // The label case's own instrument, and the only thing in this file a driver can excuse itself
    // from. Its evidence is the centroid of a glyph cell about ten pixels across; a rasteriser that
    // does not place a quad that size would be measured here rather than RenG. The other four cases
    // count areas of hundreds of thousands of pixels and are not gated on it.
    val glyphRasterisation = measureGlyphQuadRasterisation(
        binding,
        dialect,
        target,
        GROUND_ANCHOR_READBACK_PIXELS,
        labelFootprints(),
    )
    println(
        "RenG ground anchor readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect " +
            glyphRasterisation.describe(),
    )

    val failures = CollectedGroundAnchorFailures()
    try {
        val fixture = GroundAnchorFixture(
            binding = binding,
            transport = transport,
            terrainRenderer = terrainRenderer,
            plainRenderer = plainRenderer,
            terrainTarget = terrainRenderer.mintRenderTarget(FramebufferName(target.toUInt())),
            plainTarget = plainRenderer.mintRenderTarget(FramebufferName(target.toUInt())),
            targetFramebuffer = target,
        )
        failures.run("ground-relative and absolute stickers part in one frame") {
            assertGroundRelativeAndAbsoluteStickersPartInOneFrameOverRelief(fixture)
        }
        failures.run("a draped geometry follows the ridge") {
            assertADrapedGeometryFollowsTheRidgeRatherThanCuttingThroughIt(fixture)
        }
        failures.run("the coplanar drape survives the ground it rides") {
            assertTheCoplanarDrapeSurvivesTheGroundItRides(fixture)
        }
        if (glyphRasterisation.isTrustworthy) {
            failures.run("a label rides the feature it names") {
                assertALabelRidesTheFeatureItNames(fixture)
            }
        } else {
            println(
                "RenG ground anchor readback SKIPPED [a label rides the feature it names] " +
                    glyphRasterisation.describe() +
                    ": this driver does not rasterise the label pass's own quads, so a glyph " +
                    "centroid would measure the driver rather than RenG. The other four cases ran.",
            )
        }
        failures.run("no terrain leaves every anchor mode alone") {
            assertAFrameWithNoTerrainIsUnchangedByEveryAnchorMode(fixture)
        }
    } finally {
        plainRenderer.close()
        terrainRenderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
    failures.throwIfAny()
}

/**
 * Runs every case even after one has failed and reports all of them together — `CollectedFailures` in
 * `BasemapReadbackSuite` and `CollectedTerrainFrameFailures` in `TerrainFrameReadbackSuite`, for the
 * same reason and with the same cost model: a suite that stops at its first assertion costs one CI
 * round trip per defect, and these run on drivers a developer cannot reach.
 */
private class CollectedGroundAnchorFailures {
    private val messages: MutableList<String> = mutableListOf()

    fun run(name: String, case: () -> Unit) {
        try {
            case()
        } catch (error: AssertionError) {
            val message = error.message ?: error.toString()
            messages += "[$name] $message"
            println("RenG ground anchor readback FAILED [$name] $message")
        }
    }

    fun throwIfAny() {
        if (messages.isEmpty()) return
        throw AssertionError(
            "${messages.size} of $GROUND_ANCHOR_CASE_COUNT ground anchor readback cases failed:\n" +
                messages.joinToString("\n"),
        )
    }
}

private const val GROUND_ANCHOR_CASE_COUNT: Int = 5

// ---- case 1: two anchors, one frame, one enum apart ----------------------------------------------

/**
 * **ADR 0040's whole promise, as a difference inside a single `FramePlan`.**
 *
 * Two stickers at the *identical* latitude, longitude and altitude, drawn in one frame, differing in
 * nothing but `altitudeMode`. The `GROUND_RELATIVE` one stands on the ridge; the `ABSOLUTE` one
 * stands at sea level, under about 1,477 metres of terrain that now writes depth (ADR 0039), and is
 * gone. Two frames later the two images swap roles, so "one of these draws and one does not" cannot
 * be a property of either image.
 *
 * ## The position is off the cell corner on purpose
 *
 * [DEM_RIDGE_PNG]'s crest sits at the tile's own centre, which at the frame's 64 ground cells a tile
 * is exactly a **cell corner** — and at a cell corner the reconstructed surface and the texel under
 * the point are the same number, which is the symmetry point task 17's own fixture sat on and read
 * 589,761 of 589,761 against a build 47 metres wrong. [STICKER_LONGITUDE_OFFSET_DEGREES] and
 * [STICKER_LATITUDE_OFFSET_DEGREES] move the pair half a ground cell east and a third of one south,
 * which is task 14's spike's own offset lattice, so the height they ride is an interpolation across
 * a cell rather than a texel read.
 *
 * ## The sea-level arm is the sensitivity control, and it is not optional
 *
 * A build that resolved `GROUND_RELATIVE` as `ABSOLUTE` — ADR 0040's own degradation and the most
 * likely way this can be wrong — would leave both stickers at sea level under a flat-enough ground
 * to be visible, and the swap assertion would then compare two identical frames and pass. So the
 * third render puts the same `GROUND_RELATIVE` sticker over a **sea-level DEM**, where it means
 * `ABSOLUTE`, and requires it to have moved: the ridden sticker's ink must sit at least
 * [MINIMUM_ANCHOR_RIDE_PIXELS] from where the terrainless one sits.
 *
 * **Pitch 45 is what turns the ride into a movement.** At pitch 0 a raised map anchor keeps its
 * screen position up to a perspective scale, and a sticker whose scale is fixed in *screen* pixels
 * would barely move — so the case would rest entirely on the burial half and would pass against a
 * build that resolved the altitude and then discarded it.
 */
private fun assertGroundRelativeAndAbsoluteStickersPartInOneFrameOverRelief(fixture: GroundAnchorFixture) {
    val ridingIsRelative = fixture.renderTerrain(
        PLACEMENT_CAMERA, AnchorRelief.RIDGE,
        stickers = listOf(
            anchorSticker(RIDING_STICKER_URL, AltitudeMode.GROUND_RELATIVE),
            anchorSticker(BURIED_STICKER_URL, AltitudeMode.ABSOLUTE),
        ),
    )
    val buriedIsRelative = fixture.renderTerrain(
        PLACEMENT_CAMERA, AnchorRelief.RIDGE,
        stickers = listOf(
            anchorSticker(RIDING_STICKER_URL, AltitudeMode.ABSOLUTE),
            anchorSticker(BURIED_STICKER_URL, AltitudeMode.GROUND_RELATIVE),
        ),
    )
    val withoutTerrain = fixture.renderTerrain(
        PLACEMENT_CAMERA, AnchorRelief.SEA_LEVEL,
        stickers = listOf(anchorSticker(RIDING_STICKER_URL, AltitudeMode.GROUND_RELATIVE)),
    )

    val ridingWhenRelative = ridingIsRelative.pixelsOf(AnchorInk.RIDING_STICKER)
    val buriedWhenAbsolute = ridingIsRelative.pixelsOf(AnchorInk.BURIED_STICKER)
    val ridingWhenAbsolute = buriedIsRelative.pixelsOf(AnchorInk.RIDING_STICKER)
    val buriedWhenRelative = buriedIsRelative.pixelsOf(AnchorInk.BURIED_STICKER)
    println(
        "RenG ground anchor readback: one frame, two modes — relative sticker $ridingWhenRelative " +
            "pixels, absolute sticker $buriedWhenAbsolute; with the roles swapped, " +
            "$ridingWhenAbsolute and $buriedWhenRelative",
    )

    assertTrue(
        ridingWhenRelative >= MINIMUM_STICKER_PIXELS,
        "the GROUND_RELATIVE sticker must ride the ridge and draw; it painted $ridingWhenRelative " +
            "pixels, under the $MINIMUM_STICKER_PIXELS this fixture's 32-pixel square needs",
    )
    assertTrue(
        buriedWhenAbsolute <= MAXIMUM_BURIED_STICKER_PIXELS,
        "the ABSOLUTE sticker beside it declares the same zero and stands under the whole ridge, so " +
            "the ground's depth writes must bury it; it painted $buriedWhenAbsolute pixels",
    )
    assertTrue(
        buriedWhenRelative >= MINIMUM_STICKER_PIXELS,
        "swapping the two modes must swap the two pictures: the second image drew " +
            "$buriedWhenRelative pixels when it was the GROUND_RELATIVE one",
    )
    assertTrue(
        ridingWhenAbsolute <= MAXIMUM_BURIED_STICKER_PIXELS,
        "and the first image must be the buried one after the swap; it painted $ridingWhenAbsolute " +
            "pixels, so what is being measured is the sticker rather than the mode",
    )

    val ridden = ridingIsRelative.centroidOf(AnchorInk.RIDING_STICKER)
    val flat = withoutTerrain.centroidOf(AnchorInk.RIDING_STICKER)
    val swapped = buriedIsRelative.centroidOf(AnchorInk.BURIED_STICKER)
    val rode = distance(ridden, flat)
    println(
        "RenG ground anchor readback: the ridden anchor sits ${format(rode)} pixels from where the " +
            "same sticker sits over a sea-level DEM, and ${format(distance(ridden, swapped))} from " +
            "the swapped frame's own ridden anchor",
    )
    assertTrue(
        rode >= MINIMUM_ANCHOR_RIDE_PIXELS,
        "over a sea-level DEM a GROUND_RELATIVE zero means ABSOLUTE zero, so the ridden sticker must " +
            "sit somewhere else: it is only ${format(rode)} pixels away, under " +
            "$MINIMUM_ANCHOR_RIDE_PIXELS — without this the burial half above is satisfied by a build " +
            "that resolves GROUND_RELATIVE as ABSOLUTE and lets the ridge hide everything",
    )
    assertTrue(
        distance(ridden, swapped) <= MAXIMUM_ANCHOR_AGREEMENT_PIXELS,
        "both frames' ridden stickers ride the same surface at the same point and must land together; " +
            "they are ${format(distance(ridden, swapped))} pixels apart",
    )
}

// ---- case 2: a draped geometry follows the relief -------------------------------------------------

/**
 * **A `GROUND_RELATIVE` `Geometry` drapes across every subdivided vertex, not only its two corners.**
 *
 * The fixture's ridge stands about 220 logical pixels at its summit, so a flat quad at the ridge's own
 * mean height ([MEAN_DRAWN_RIDGE_METRES]) is above the terrain on the flanks and below it across the
 * middle — which is "cutting through", and is what ADR 0039's depth writes make visible.
 *
 * Three altitudes, and each of the other two is why the first means anything: the **draped** quad must
 * cover essentially its whole footprint; the **flat at the mean** quad must lose a large part of it,
 * because a build that ignored `altitudeMode` would draw that frame for the draped one too; and the
 * **flat at zero** quad must be gone, because without it "the ridge buries things" is an assumption
 * rather than a measurement and a build whose ground wrote no depth at all would pass the first two.
 */
private fun assertADrapedGeometryFollowsTheRidgeRatherThanCuttingThroughIt(fixture: GroundAnchorFixture) {
    val draped = fixture.renderTerrain(
        DRAPE_CAMERA, AnchorRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, 0.0)),
    )
    val throughTheRidge = fixture.renderTerrain(
        DRAPE_CAMERA, AnchorRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.ABSOLUTE, MEAN_DRAWN_RIDGE_METRES)),
    )
    val underTheRidge = fixture.renderTerrain(
        DRAPE_CAMERA, AnchorRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.ABSOLUTE, 0.0)),
    )

    val drapedInk = draped.pixelsOf(AnchorInk.DRAPE)
    val cuttingInk = throughTheRidge.pixelsOf(AnchorInk.DRAPE)
    val buriedInk = underTheRidge.pixelsOf(AnchorInk.DRAPE)
    println(
        "RenG ground anchor readback: draped geometry paints $drapedInk pixels, the same quad flat at " +
            "${MEAN_DRAWN_RIDGE_METRES.toInt()} m paints $cuttingInk, and flat at sea level $buriedInk",
    )

    assertTrue(
        drapedInk >= MINIMUM_DRAPED_PIXELS,
        "a draped geometry must cover its footprint; it painted $drapedInk pixels",
    )
    assertTrue(
        cuttingInk <= drapedInk * MAXIMUM_CUT_QUAD_PERCENT / 100,
        "a flat quad at the ridge's mean height must be cut by the ridge: it painted $cuttingInk " +
            "against the drape's $drapedInk",
    )
    assertTrue(
        cuttingInk >= MINIMUM_CUT_QUAD_PIXELS,
        "the flat quad must still draw where the terrain is below it, or the comparison above is " +
            "satisfied by a quad that never drew; it painted $cuttingInk pixels",
    )
    assertTrue(
        buriedInk <= MAXIMUM_BURIED_QUAD_PIXELS,
        "a flat quad at sea level stands under the whole ridge and must be buried by it; it painted " +
            "$buriedInk pixels",
    )
}

// ---- case 3: the coplanar drape, counted the spike's way ------------------------------------------

/**
 * **The one genuinely coplanar case in RenG, and this suite's gate on [GROUND_DRAPE_LIFT_METRES].**
 *
 * A `GROUND_RELATIVE` `Geometry` at altitude zero is a second copy of the terrain surface drawn
 * through a different pipeline against a ground that now writes depth, which is ADR 0027's original
 * defect in new material. Task 14's spike measured every way out of it and ADR 0039's 2026-08-30
 * erratum records the verdict: a shared texel rule fixes the wrong term, drawing the drape first keeps
 * **zero** pixels at every camera, and a lift works.
 *
 * ## Counted the spike's way, because the spike's way is what caught its own fixture being wrong
 *
 * **Contested** is the set of pixels the content paints when it wins *and* the ground paints when the
 * content is absent; **survivors** are the contested pixels the coplanar drape still holds. Counting
 * "how much magenta is there" would be satisfied by a drape that had drifted off the ground
 * altogether, and counting "the drape covers the ground" by a drape that won because it was nowhere
 * near it. The raised frame supplies the contested set at [DRAPE_CONTROL_METRES] — high enough to
 * leave the fight outright, low enough that its footprint is the drape's to within a pixel of fringe —
 * and the **sunk** frame is what makes a survivor count mean anything at all: if the ground could not
 * delete this quad, every build would survive at 100 per cent, including one with no lift and no
 * lookup.
 *
 * ## The number, and why the quad runs off every edge
 *
 * The spike measured **102,370 of 102,400** for a four-metre lift on an offset lattice at zoom 13 and
 * pitch 0. [ridgeGeometry] runs off all four edges of the frame for the reason
 * `TerrainFrameReadbackSuite` records: a raised control quad projects about a pixel larger at every
 * edge, and a quad that ended *inside* the frame put that fringe into the contested set and read 97.99
 * per cent while measuring its own control's footprint.
 */
private fun assertTheCoplanarDrapeSurvivesTheGroundItRides(fixture: GroundAnchorFixture) {
    val groundOnly = fixture.renderTerrain(DRAPE_CAMERA, AnchorRelief.RIDGE)
    val raised = fixture.renderTerrain(
        DRAPE_CAMERA, AnchorRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, DRAPE_CONTROL_METRES)),
    )
    val coplanar = fixture.renderTerrain(
        DRAPE_CAMERA, AnchorRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, 0.0)),
    )
    val sunk = fixture.renderTerrain(
        DRAPE_CAMERA, AnchorRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, -DRAPE_CONTROL_METRES)),
    )

    var contested = 0
    var survivors = 0
    var sunkSurvivors = 0
    for (index in 0 until GROUND_ANCHOR_PIXEL_COUNT) {
        if (raised.inkAtIndex(index) != AnchorInk.DRAPE) continue
        if (groundOnly.inkAtIndex(index) != AnchorInk.GROUND) continue
        contested += 1
        if (coplanar.inkAtIndex(index) == AnchorInk.DRAPE) survivors += 1
        if (sunk.inkAtIndex(index) == AnchorInk.DRAPE) sunkSurvivors += 1
    }
    println(
        "RenG coplanar anchor readback: survivors $survivors / $contested contested " +
            "(sunk control $sunkSurvivors), lift ${GROUND_DRAPE_LIFT_METRES.toInt()} m",
    )

    assertTrue(
        contested >= MINIMUM_CONTESTED_PIXELS,
        "the fixture must put the drape and the ground over the same $MINIMUM_CONTESTED_PIXELS pixels " +
            "before a survivor count means anything; it contested $contested",
    )
    assertTrue(
        sunkSurvivors * 100 <= contested * MAXIMUM_SUNK_SURVIVOR_PERCENT,
        "the ground must be able to delete this quad, or every build survives: a quad sunk " +
            "${DRAPE_CONTROL_METRES.toInt()} m kept $sunkSurvivors of $contested",
    )
    assertTrue(
        survivors * 100 >= contested * MINIMUM_SURVIVOR_PERCENT,
        "the coplanar drape kept $survivors of $contested contested pixels, under the " +
            "$MINIMUM_SURVIVOR_PERCENT per cent the spike's four-metre lift measured",
    )
}

// ---- case 4: a label rides the feature it names ---------------------------------------------------

/**
 * **Task 18's subject, in pixels, which nothing had.** A label anchored at sea level detaches from the
 * feature it names the moment the ground under it rises; `placeLabels` hands every anchor the terrain
 * height beneath it and lets the projection spend it.
 *
 * `RendererLabelTerrainTest` asserts the anchor `prepare()` decided on and stops there — it never
 * calls `draw()`. `LabelIntegrationReadbackSuite` draws labels and knows nothing about terrain. This
 * is the only assertion in the tree that a *drawn* name moved with the ground.
 *
 * ## The expectation is a projection this file does itself, in both axes
 *
 * The feature sits at the exact centre of its vector tile — `labelMvtBytes` puts it at `(2048, 2048)`
 * of a 4096 extent — so its geographic anchor is the slippy-map tile centre, computed here from `z`,
 * `x` and `y` by the standard formulas rather than by anything RenG derives. The predicted movement is
 * the difference between that position projected at `984.4 m × 1.5` and projected at zero, and the
 * measured movement is the difference between the two frames' label-ink **centroids**. A centroid is
 * the anchor plus a constant offset — the glyph cell is the same size in both frames, both at pitch 0
 * — so the constant cancels and what is left is the anchor's own movement.
 *
 * **Both axes rather than a distance**, because `glReadPixels` returns rows bottom-up while
 * `ScreenProjection` is y-down: a build that dropped that flip would keep the distance and reverse the
 * sign, and a distance comparison would not notice.
 *
 * ## Three ways this could pass for the wrong reason, and how each is closed
 *
 * - *No label drew at all.* Both arms assert an ink floor before anything is compared, and a frame
 *   with no label has a centroid of nothing.
 * - *The label moved because the frame changed.* The two arms differ in **nothing but the bytes the
 *   transport answers a DEM url with** — same style, same camera, same plan, same tiles.
 * - *The exaggeration was dropped.* The unexaggerated prediction is 984.4 m rather than 1,476.6, which
 *   lands [MINIMUM_EXAGGERATION_SEPARATION_PIXELS] away from the exaggerated one, and the case refuses
 *   it explicitly.
 *
 * **The fade is ramped on both sides.** A label enters at a tenth of its opacity and saturates over ten
 * frames; a first frame's ink is the style's colour blended into the ground and is not the style's
 * colour. Both arms are driven the full ramp, so neither centroid is a measurement of a fade.
 *
 * **What this case cannot see, said rather than hidden.** The anchor is the tile centre, which at 64
 * ground cells a tile is a cell *corner*, and at a corner the reconstructed surface and the texel under
 * the point are the same number — so a lookup that read the texel instead of the cell passes here. It
 * is the *granularity* this fixture discriminates: a one-cell grid would interpolate the tile's own
 * corner texels and answer 15.6 m where the frame's grid reads 984.4. The cell rule is
 * `GroundSurfaceTest`'s and case 1's above.
 */
private fun assertALabelRidesTheFeatureItNames(fixture: GroundAnchorFixture) {
    val overTheRidge = fixture.renderLabelRamp(AnchorRelief.RIDGE)
    val overSeaLevel = fixture.renderLabelRamp(AnchorRelief.SEA_LEVEL)

    val riddenInk = overTheRidge.pixelsOf(AnchorInk.LABEL)
    val flatInk = overSeaLevel.pixelsOf(AnchorInk.LABEL)
    assertTrue(
        riddenInk >= MINIMUM_LABEL_PIXELS,
        "the label over the ridge must draw before its position means anything; it painted $riddenInk " +
            "pixels of ${AnchorInk.LABEL}, under $MINIMUM_LABEL_PIXELS",
    )
    assertTrue(
        flatInk >= MINIMUM_LABEL_PIXELS,
        "and so must the label over sea level; it painted $flatInk pixels",
    )

    val ridden = overTheRidge.centroidOf(AnchorInk.LABEL)
    val flat = overSeaLevel.centroidOf(AnchorInk.LABEL)
    // glReadPixels' rows run bottom-up and ScreenProjection is y-down, so a measured rise is a
    // negative predicted step. Keeping the two in the projection's own frame is what lets the y
    // comparison catch a dropped flip instead of hiding it inside a distance.
    val measuredX = ridden.x - flat.x
    val measuredY = -(ridden.y - flat.y)

    val camera = fixture.resolvedLabelCamera()
    val predictedFlat = projectedFeatureAnchor(camera, 0.0)
    val predictedRidden = projectedFeatureAnchor(camera, DRAWN_CREST_METRES)
    val predictedUnexaggerated = projectedFeatureAnchor(camera, RIDGE_CREST_METRES)
    val predictedX = predictedRidden.pixelX - predictedFlat.pixelX
    val predictedY = predictedRidden.pixelY - predictedFlat.pixelY
    val unexaggeratedX = predictedUnexaggerated.pixelX - predictedFlat.pixelX
    val unexaggeratedY = predictedUnexaggerated.pixelY - predictedFlat.pixelY
    println(
        "RenG ground anchor readback: the label paints $riddenInk pixels over the ridge and $flatInk " +
            "over sea level, and its ink moved (${format(measuredX)}, " +
            "${format(measuredY)}) between a sea-level DEM and a ridge, against a projected " +
            "(${format(predictedX)}, ${format(predictedY)}) and an unexaggerated " +
            "(${format(unexaggeratedX)}, ${format(unexaggeratedY)})",
    )

    val predictedStep = sqrt(predictedX * predictedX + predictedY * predictedY)
    assertTrue(
        predictedStep >= MINIMUM_LABEL_RIDE_PIXELS,
        "the fixture itself must predict a movement worth measuring, and it predicts only " +
            "${format(predictedStep)} pixels",
    )
    assertEquals(
        predictedX,
        measuredX,
        LABEL_CENTROID_TOLERANCE_PIXELS,
        "the drawn label must ride its feature's own terrain height, in x",
    )
    assertEquals(
        predictedY,
        measuredY,
        LABEL_CENTROID_TOLERANCE_PIXELS,
        "the drawn label must ride its feature's own terrain height, in y",
    )
    val fromUnexaggerated = sqrt(
        (measuredX - unexaggeratedX) * (measuredX - unexaggeratedX) +
            (measuredY - unexaggeratedY) * (measuredY - unexaggeratedY),
    )
    assertTrue(
        fromUnexaggerated >= MINIMUM_EXAGGERATION_SEPARATION_PIXELS,
        "a terrain exaggeration of $TERRAIN_EXAGGERATION has to be visible in the drawn frame: the " +
            "label's ink is only ${format(fromUnexaggerated)} pixels from where an unexaggerated " +
            "$RIDGE_CREST_METRES m would have put it",
    )
}

// ---- case 5: no terrain, no change ----------------------------------------------------------------

/**
 * **The negative, and without it the four cases above can all pass vacuously.**
 *
 * Against a style declaring no `terrain` block and no `raster-dem` source, a `GROUND_RELATIVE` sticker
 * and a `GROUND_RELATIVE` `Geometry` must read back **byte for byte** as `ABSOLUTE` ones. ADR 0040
 * promises exactly that — the two modes are identical wherever the ground is flat, which is every
 * frame of every published release and 28 of the 34 styles RenG is verified against — and it is what
 * separates a build that resolves altitudes from one that has simply moved everything onto whatever
 * surface it can find.
 *
 * It is also the case that fails if the drape's own triangulation ever escapes the drape: a geometry
 * cell folds on the ground's NE–SW diagonal only when there is a drape, and a leak would move pixels
 * here without moving a single altitude.
 *
 * The raised arm is the sensitivity control on the same terms as everywhere else in this file: two
 * frames agree trivially if the instrument cannot see an altitude at all.
 *
 * **`drawLabels` is off here, and that is arithmetic rather than an omission.** A label's opacity
 * advances one fade step per `prepare()`, so two consecutive frames of the same plan are *not*
 * byte-identical whatever the terrain does. Labels over a terrainless style are
 * `LabelIntegrationReadbackSuite`'s subject; the byte comparison is this one's.
 */
private fun assertAFrameWithNoTerrainIsUnchangedByEveryAnchorMode(fixture: GroundAnchorFixture) {
    val absolute = fixture.renderPlain(
        PLACEMENT_CAMERA,
        stickers = listOf(anchorSticker(RIDING_STICKER_URL, AltitudeMode.ABSOLUTE)),
        geometries = listOf(plainGeometry(AltitudeMode.ABSOLUTE, 0.0)),
    )
    val groundRelative = fixture.renderPlain(
        PLACEMENT_CAMERA,
        stickers = listOf(anchorSticker(RIDING_STICKER_URL, AltitudeMode.GROUND_RELATIVE)),
        geometries = listOf(plainGeometry(AltitudeMode.GROUND_RELATIVE, 0.0)),
    )
    val raised = fixture.renderPlain(
        PLACEMENT_CAMERA,
        stickers = listOf(
            anchorSticker(RIDING_STICKER_URL, AltitudeMode.ABSOLUTE, PLAIN_SENSITIVITY_METRES),
        ),
        geometries = listOf(plainGeometry(AltitudeMode.ABSOLUTE, PLAIN_SENSITIVITY_METRES)),
    )

    val painted = absolute.paintedPixels()
    val sensitivity = absolute.differenceFrom(raised)
    println(
        "RenG ground anchor readback: a terrainless frame paints $painted pixels, its two altitude " +
            "modes differ over ${absolute.differenceFrom(groundRelative)}, and raising both by " +
            "${PLAIN_SENSITIVITY_METRES.toInt()} m moves $sensitivity",
    )

    assertTrue(
        painted >= MINIMUM_PLAIN_PAINTED_PIXELS,
        "a terrainless frame carrying a sticker and a geometry must paint something before an " +
            "identity between two of them means anything; it painted $painted pixels",
    )
    assertTrue(
        sensitivity >= MINIMUM_PLAIN_SENSITIVITY_PIXELS,
        "and the instrument must be able to see an altitude at all: raising both by " +
            "${PLAIN_SENSITIVITY_METRES.toInt()} m moved only $sensitivity pixels",
    )
    assertEquals(
        0,
        absolute.differenceFrom(groundRelative),
        "with no terrain declared, a GROUND_RELATIVE altitude is an ABSOLUTE one (ADR 0040), so " +
            "these two frames must be byte-identical; they differ over " +
            "${absolute.differenceFrom(groundRelative)} pixels, first at " +
            "${absolute.firstDifferenceFrom(groundRelative)}",
    )
}

// ---- the content ----------------------------------------------------------------------------------

/**
 * A 16 x 16 sticker at [STICKER_LATITUDE] / [STICKER_LONGITUDE], map-anchored with a screen scale of
 * two so its drawn size is a fixed 32 x 32 logical pixels whatever the altitude does to its depth.
 *
 * **Screen scale rather than map scale**, so that a raised sticker moves and does not also grow: a
 * map-scaled billboard's size follows the camera distance, and a pixel count that changed with the
 * altitude would confuse "it moved" with "it got bigger". **Two rather than one**, because a scale of
 * exactly 1 is a symmetry point at which an applied scale and a dropped one are the same picture.
 */
private fun anchorSticker(
    imageUrl: String,
    altitudeMode: AltitudeMode,
    altitudeMetres: Double = 0.0,
): Sticker = Sticker(
    placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(STICKER_LATITUDE, STICKER_LONGITUDE, altitudeMetres),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 2.0,
        altitudeMode = altitudeMode,
    ),
    image = ResourceLocator(imageUrl),
)

/**
 * A quad two LOD-13 tiles a side, centred on [DRAPE_CAMERA] and running off every edge of a 768-pixel
 * frame — the overhang is what keeps a raised control quad's one-pixel fringe *outside* the contested
 * set case 3 counts.
 *
 * Its lattice is offset half a ground cell east and a third of one south, for
 * [STICKER_LONGITUDE_OFFSET_DEGREES]'s reason: on a snapped lattice every drape node coincides with a
 * ground node, where the surface and the texel under the point are the same number.
 */
private fun ridgeGeometry(altitudeMode: AltitudeMode, altitudeMetres: Double): Geometry = Geometry(
    topLeft = Vector3(
        STICKER_LATITUDE + RIDGE_QUAD_HALF_LATITUDE_DEGREES,
        STICKER_LONGITUDE - RIDGE_QUAD_HALF_LONGITUDE_DEGREES,
        altitudeMetres,
    ),
    bottomRight = Vector3(
        STICKER_LATITUDE - RIDGE_QUAD_HALF_LATITUDE_DEGREES,
        STICKER_LONGITUDE + RIDGE_QUAD_HALF_LONGITUDE_DEGREES,
        altitudeMetres,
    ),
    shaderPair = DRAPE_SHADER_PAIR,
    altitudeMode = altitudeMode,
)

/**
 * [ridgeGeometry] at a quarter of the span, so that a terrainless frame's quad has an edge inside the
 * frame for the raised arm of case 5 to move.
 */
private fun plainGeometry(altitudeMode: AltitudeMode, altitudeMetres: Double): Geometry = Geometry(
    topLeft = Vector3(
        STICKER_LATITUDE + RIDGE_QUAD_HALF_LATITUDE_DEGREES / 4.0,
        STICKER_LONGITUDE - RIDGE_QUAD_HALF_LONGITUDE_DEGREES / 4.0,
        altitudeMetres,
    ),
    bottomRight = Vector3(
        STICKER_LATITUDE - RIDGE_QUAD_HALF_LATITUDE_DEGREES / 4.0,
        STICKER_LONGITUDE + RIDGE_QUAD_HALF_LONGITUDE_DEGREES / 4.0,
        altitudeMetres,
    ),
    shaderPair = DRAPE_SHADER_PAIR,
    altitudeMode = altitudeMode,
)

/**
 * A consumer shader pair painting one flat colour, which is what makes a pixel count a pixel count. A
 * gradient would put half the quad into [AnchorInk.OTHER].
 */
private val DRAPE_SHADER_PAIR: ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\n" +
        "precision highp float;\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n",
    fragmentSource = "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengAnchorDrapeOut;\n" +
        "void main() {\n" +
        "    rengAnchorDrapeOut = vec4(1.0, 0.0, 1.0, 1.0);\n" +
        "}\n",
)

// ---- the fixture ------------------------------------------------------------------------------------

private class GroundAnchorFixture(
    private val binding: GlBinding,
    private val transport: GroundAnchorTransport,
    private val terrainRenderer: Renderer,
    private val plainRenderer: Renderer,
    private val terrainTarget: RenderTarget,
    private val plainTarget: RenderTarget,
    private val targetFramebuffer: Int,
) {
    private var frameIndex: Long = 0L

    fun renderTerrain(
        camera: Camera,
        relief: AnchorRelief,
        stickers: List<Sticker> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): AnchorFrame {
        transport.relief = relief
        return render(terrainRenderer, terrainTarget, plan(camera, stickers, geometries, drawLabels = false))
    }

    fun renderPlain(
        camera: Camera,
        stickers: List<Sticker> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): AnchorFrame {
        transport.relief = AnchorRelief.SEA_LEVEL
        return render(plainRenderer, plainTarget, plan(camera, stickers, geometries, drawLabels = false))
    }

    /**
     * Drives the label fade to saturation over [LABEL_FADE_RAMP] frames and returns the last one.
     *
     * A fresh ramp per arm rather than one ramp and a DEM swap: a label's fade is keyed on the label,
     * and this fixture does not need to know whether moving its anchor makes it a different one.
     */
    fun renderLabelRamp(relief: AnchorRelief): AnchorFrame {
        transport.relief = relief
        var frame: AnchorFrame? = null
        repeat(LABEL_FADE_RAMP) {
            frame = render(
                terrainRenderer,
                terrainTarget,
                plan(LABEL_CAMERA, emptyList(), emptyList(), drawLabels = true),
            )
        }
        return requireNotNull(frame) { "the label ramp must run at least one frame" }
    }

    fun resolvedLabelCamera(): ResolvedMercatorCamera {
        val outcome = resolveMercatorCamera(
            camera = LABEL_CAMERA,
            outputPixelSize = OutputPixelSize(GROUND_ANCHOR_READBACK_PIXELS, GROUND_ANCHOR_READBACK_PIXELS),
        )
        return (outcome as SpatialOutcome.Success<ResolvedMercatorCamera>).value
    }

    private fun plan(
        camera: Camera,
        stickers: List<Sticker>,
        geometries: List<Geometry>,
        drawLabels: Boolean,
    ): FramePlan = FramePlan(
        frameIndex = frameIndex++,
        camera = camera,
        projectionMode = ProjectionMode.MERCATOR,
        drawBasemap = true,
        drawLabels = drawLabels,
        stickers = stickers,
        geometries = geometries,
    )

    private fun render(renderer: Renderer, renderTarget: RenderTarget, plan: FramePlan): AnchorFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.viewport(0, 0, GROUND_ANCHOR_READBACK_PIXELS, GROUND_ANCHOR_READBACK_PIXELS)
        binding.clearColor(CLEARED[0] / 255f, CLEARED[1] / 255f, CLEARED[2] / 255f, CLEARED[3] / 255f)
        binding.clear(GL_COLOR_BUFFER_BIT)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

        // prepare() is suspending and reaches the Rentile engine on Dispatchers.Default; draw() is
        // synchronous GL work that must happen on the thread holding the context, so the two are split
        // rather than run inside one runBlocking body.
        val frame = runBlocking { renderer.prepare(plan) }
        try {
            renderer.draw(frame, renderTarget)
        } finally {
            frame.close()
        }

        val pixels = ByteArray(GROUND_ANCHOR_PIXEL_COUNT * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, GROUND_ANCHOR_READBACK_PIXELS, GROUND_ANCHOR_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, pixels,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        return AnchorFrame(pixels)
    }
}

/** What a pixel of one of this suite's frames can be. */
private enum class AnchorInk { CLEARED, GROUND, RIDING_STICKER, BURIED_STICKER, DRAPE, LABEL, OTHER }

/** A point in `glReadPixels` space: column, and row counted from the bottom. */
private class AnchorCentroid(val x: Double, val y: Double)

/** One read-back frame. Row 0 is the bottom, as `glReadPixels` returns it. */
private class AnchorFrame(private val bytes: ByteArray) {
    fun inkAtIndex(index: Int): AnchorInk {
        val offset = index * 4
        return when {
            matches(offset, CLEARED) -> AnchorInk.CLEARED
            matches(offset, GROUND) -> AnchorInk.GROUND
            matches(offset, RIDING_STICKER) -> AnchorInk.RIDING_STICKER
            matches(offset, BURIED_STICKER) -> AnchorInk.BURIED_STICKER
            matches(offset, DRAPE) -> AnchorInk.DRAPE
            matches(offset, LABEL) -> AnchorInk.LABEL
            else -> AnchorInk.OTHER
        }
    }

    fun pixelsOf(ink: AnchorInk): Int {
        var count = 0
        for (index in 0 until GROUND_ANCHOR_PIXEL_COUNT) {
            if (inkAtIndex(index) == ink) count += 1
        }
        return count
    }

    /**
     * The mean position of every pixel carrying [ink]. It is the anchor plus whatever constant offset
     * the drawn shape has from it, which is why every use of it below is a *difference* between two
     * frames rather than a position.
     */
    fun centroidOf(ink: AnchorInk): AnchorCentroid {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (index in 0 until GROUND_ANCHOR_PIXEL_COUNT) {
            if (inkAtIndex(index) != ink) continue
            sumX += (index % GROUND_ANCHOR_READBACK_PIXELS).toDouble()
            sumY += (index / GROUND_ANCHOR_READBACK_PIXELS).toDouble()
            count += 1
        }
        if (count == 0) throw AssertionError("no pixel of the frame carries $ink, so it has no centroid")
        return AnchorCentroid(sumX / count, sumY / count)
    }

    fun paintedPixels(): Int {
        var count = 0
        for (index in 0 until GROUND_ANCHOR_PIXEL_COUNT) {
            if (inkAtIndex(index) != AnchorInk.CLEARED) count += 1
        }
        return count
    }

    /**
     * How many pixels differ, exactly rather than within a tolerance.
     *
     * **A count first, and never `assertContentEquals` over the arrays.** Task 11 recorded the shape:
     * a whole-frame comparison's failure message is 4.6 MB here, which overflows Gradle's 1 MB
     * TeamCity service message and loses the assertion entirely.
     */
    fun differenceFrom(other: AnchorFrame): Int {
        var count = 0
        for (index in 0 until GROUND_ANCHOR_PIXEL_COUNT) {
            val offset = index * 4
            for (channel in 0 until 4) {
                if (bytes[offset + channel] != other.bytes[offset + channel]) {
                    count += 1
                    break
                }
            }
        }
        return count
    }

    fun firstDifferenceFrom(other: AnchorFrame): String {
        for (index in 0 until GROUND_ANCHOR_PIXEL_COUNT) {
            val offset = index * 4
            for (channel in 0 until 4) {
                if (bytes[offset + channel] != other.bytes[offset + channel]) {
                    return "(${index % GROUND_ANCHOR_READBACK_PIXELS}, " +
                        "${index / GROUND_ANCHOR_READBACK_PIXELS})"
                }
            }
        }
        return "nowhere"
    }

    private fun matches(offset: Int, colour: IntArray): Boolean = (0..3).all {
        abs((bytes[offset + it].toInt() and 0xff) - colour[it]) <= ANCHOR_CHANNEL_TOLERANCE
    }
}

private fun createGroundAnchorTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8,
        GROUND_ANCHOR_READBACK_PIXELS, GROUND_ANCHOR_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the ground anchor readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private fun distance(from: AnchorCentroid, to: AnchorCentroid): Double {
    val stepX = to.x - from.x
    val stepY = to.y - from.y
    return sqrt(stepX * stepX + stepY * stepY)
}

/**
 * Two decimal places, hand-rolled because Kotlin common has no format string.
 *
 * The sign is carried separately rather than falling out of the division, because `-50 / 100` is `0`
 * in Kotlin and a movement of minus half a pixel would otherwise print as a positive one.
 */
private fun format(value: Double): String {
    val hundredths = (value * 100.0).toLong()
    val magnitude = abs(hundredths)
    return "${if (hundredths < 0) "-" else ""}${magnitude / 100}." +
        (magnitude % 100).toString().padStart(2, '0')
}

/** Where the fixture's one feature lands at [altitudeMetres], by the projection and nothing else. */
private fun projectedFeatureAnchor(
    camera: ResolvedMercatorCamera,
    altitudeMetres: Double,
): ScreenProjection.Projected {
    val projection = projectGeographicPosition(
        camera,
        GeographicPosition(
            latitude = ANCHOR_TILE_LATITUDE,
            unwrappedLongitude = ANCHOR_TILE_LONGITUDE,
            altitudeMetres = altitudeMetres,
        ),
    )
    return projection as? ScreenProjection.Projected
        ?: throw AssertionError("the fixture's own feature must project at $altitudeMetres m")
}

/**
 * The glyph cell the fixture's one label is drawn as, for task 18's probe: one cell, box-centred on
 * the anchor the way the placement pass centres a label.
 *
 * Every number is derived from the fixture's own declarations rather than measured off a frame.
 * [labelGlyphRange] emits each glyph 8 by 10 pixels; Rentile's packer surrounds it with three pixels
 * of SDF buffer on all four sides, making the packed cell 14 by 16; and the style's `text-size` of 16
 * over the 24-pixel SDF em scales it by two thirds.
 */
private fun labelFootprints(): List<GlyphQuadFootprint> {
    val outcome = resolveMercatorCamera(
        camera = LABEL_CAMERA,
        outputPixelSize = OutputPixelSize(GROUND_ANCHOR_READBACK_PIXELS, GROUND_ANCHOR_READBACK_PIXELS),
    )
    val camera = (outcome as SpatialOutcome.Success<ResolvedMercatorCamera>).value
    val anchor = projectedFeatureAnchor(camera, DRAWN_CREST_METRES)
    val width = (8f + 2f * 3f) * (16f / 24f)
    val height = (10f + 2f * 3f) * (16f / 24f)
    return listOf(
        GlyphQuadFootprint(
            "the fixture's one glyph",
            anchor.pixelX.toFloat() - width / 2f,
            anchor.pixelY.toFloat() - height / 2f,
            anchor.pixelX.toFloat() + width / 2f,
            anchor.pixelY.toFloat() + height / 2f,
        ),
    )
}

// ---- the numbers -------------------------------------------------------------------------------------

/**
 * 768, which is `runTerrainFrameReadbackSuite`'s frame and deliberately the same one.
 *
 * The coplanar case's contested set is essentially the whole frame, so its survivor count is directly
 * comparable to that suite's own 579,754 of 589,824 — and a reading that differed would be a fact about
 * this fixture rather than a number that had to be re-derived. It also spans one and a half LOD-13
 * tiles, so the drape crosses a tile boundary rather than living inside one.
 */
internal const val GROUND_ANCHOR_READBACK_PIXELS: Int = 768

private const val GROUND_ANCHOR_PIXEL_COUNT: Int =
    GROUND_ANCHOR_READBACK_PIXELS * GROUND_ANCHOR_READBACK_PIXELS

/**
 * Eight, which is `TerrainFrameReadbackSuite`'s and `GlobeFrameReadbackSuite`'s tolerance for the same
 * job: ink arrives through Rentile's rasteriser, a texture upload and a composite pass, and every
 * classification here asks "which of six widely separated colours is this" rather than "is this exactly
 * that byte". Case 5 is where whole frames are compared, and it compares them exactly.
 */
private const val ANCHOR_CHANNEL_TOLERANCE: Int = 8

/**
 * What the target is cleared to before every render, and a colour **nothing in the fixture paints**.
 * RenG composites onto the caller's target rather than clearing it, so a pixel still carrying this
 * after a draw is one the renderer left alone.
 */
private val CLEARED: IntArray = intArrayOf(0, 96, 32, 255)

/** The style's `background` colour, which is the only thing its ground paints. */
private val GROUND: IntArray = intArrayOf(240, 40, 40, 255)

/** The first sticker image: opaque white. */
private val RIDING_STICKER: IntArray = intArrayOf(255, 255, 255, 255)

/** The second: opaque yellow. Case 1 swaps which of the two carries which altitude mode. */
private val BURIED_STICKER: IntArray = intArrayOf(255, 255, 0, 255)

/** What [DRAPE_SHADER_PAIR] paints. */
private val DRAPE: IntArray = intArrayOf(255, 0, 255, 255)

/** The symbol layer's `text-color`, at the full opacity the fade ramp reaches. */
private val LABEL: IntArray = intArrayOf(0, 160, 255, 255)

/**
 * The tile whose centre carries the fixture's one label feature, and whose centre column is
 * [DEM_RIDGE_PNG]'s own crest. `x` and `y` are unequal and neither is the other's transpose, so a
 * swapped tile index names a different place rather than the same one.
 */
private const val ANCHOR_TILE_Z: Int = 13
private const val ANCHOR_TILE_X: Int = 4293
private const val ANCHOR_TILE_Y: Int = 2931

private const val DEGREES_PER_RADIAN: Double = 180.0 / PI

private fun tileCentreLongitude(z: Int, x: Int): Double =
    (x + 0.5) / (1 shl z).toDouble() * 360.0 - 180.0

private fun tileCentreLatitude(z: Int, y: Int): Double =
    atan(sinh(PI * (1.0 - 2.0 * (y + 0.5) / (1 shl z).toDouble()))) * DEGREES_PER_RADIAN

/**
 * 45.4755 N and 8.6788 E, which is task 14's spike's own camera — so this suite's coplanar reading and
 * the spike's table describe the same regime. **Latitude is deliberately not 0**, where Mercator's
 * `1 / cos(latitude)` term is exactly 1 and a dropped latitude term is invisible.
 */
private val ANCHOR_TILE_LATITUDE: Double = tileCentreLatitude(ANCHOR_TILE_Z, ANCHOR_TILE_Y)
private val ANCHOR_TILE_LONGITUDE: Double = tileCentreLongitude(ANCHOR_TILE_Z, ANCHOR_TILE_X)

/**
 * **Half a ground cell east and a third of one south of the tile centre, and the offset is the
 * difference between the placement case measuring RenG and measuring itself.**
 *
 * At 64 ground cells a tile the tile's own centre is a cell *corner*, and at a corner the surface the
 * ground draws and the texel under the point are the same number — so a lookup that read the texel
 * would ride to exactly the right height there. Task 17's own drape fixture sat on that symmetry point
 * and read 589,761 of 589,761 against a build 47 metres wrong between nodes. It is also the realistic
 * condition: a consumer's placement carries a latitude and a longitude it wrote down and lands wherever
 * they put it.
 */
private const val STICKER_LONGITUDE_OFFSET_DEGREES: Double = 360.0 / 8192.0 / 64.0 / 2.0
private val STICKER_LATITUDE_OFFSET_DEGREES: Double =
    -360.0 / 8192.0 * cos(45.4755 / DEGREES_PER_RADIAN) / 64.0 / 3.0

private val STICKER_LATITUDE: Double = ANCHOR_TILE_LATITUDE + STICKER_LATITUDE_OFFSET_DEGREES
private val STICKER_LONGITUDE: Double = ANCHOR_TILE_LONGITUDE + STICKER_LONGITUDE_OFFSET_DEGREES

/** One whole LOD-13 tile of longitude either way, so the quad spans two tiles and 1,024 logical pixels
 * across a 768-pixel frame, with 128 pixels of overhang at each edge. */
private const val RIDGE_QUAD_HALF_LONGITUDE_DEGREES: Double = 360.0 / 8192.0

/** The same span in latitude, narrowed by `cos(latitude)` so the quad is roughly square on screen. */
private val RIDGE_QUAD_HALF_LATITUDE_DEGREES: Double =
    RIDGE_QUAD_HALF_LONGITUDE_DEGREES * cos(45.4755 / DEGREES_PER_RADIAN)

/** Zoom 13 at the tile's own centre, pitch 0 — the spike's camera, and where its lift ladder was run. */
private val DRAPE_CAMERA: Camera = Camera(
    latitude = ANCHOR_TILE_LATITUDE,
    unwrappedLongitude = ANCHOR_TILE_LONGITUDE,
    zoom = ANCHOR_TILE_Z.toDouble(),
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * [DRAPE_CAMERA] pitched 45 degrees, which is what turns a vertical rise into a screen movement.
 *
 * At pitch 0 a raised map anchor sits at the same screen position as an unraised one up to a
 * perspective scale, so a sticker whose size is fixed in screen pixels would barely move — case 1 would
 * then rest entirely on its burial half and would pass against a build that resolved the altitude and
 * discarded it.
 */
private val PLACEMENT_CAMERA: Camera = Camera(
    latitude = ANCHOR_TILE_LATITUDE,
    unwrappedLongitude = ANCHOR_TILE_LONGITUDE,
    zoom = ANCHOR_TILE_Z.toDouble(),
    bearing = 0.0,
    pitch = 45.0,
)

/**
 * How far the label case's camera sits from the feature's tile centre, east and south, in logical
 * pixels.
 *
 * **Not zero, and that is the whole of why the label case can see anything.** An anchor at the
 * principal point is the symmetry point of a perspective camera: raising it scales its offset from that
 * point, and an offset of zero scales to zero, so a label at the screen centre does not move however
 * tall the terrain under it is. 150 pixels puts the anchor at (534, 534) of a 768-pixel frame and its
 * ridden position near (581, 581), both clear of every edge.
 */
private const val LABEL_CAMERA_OFFSET_PIXELS: Double = 150.0

/** One tile's side in logical pixels at the vector source's own tile size. */
private const val TILE_SIDE_LOGICAL_PIXELS: Double = 512.0

/** The feature's tile centre, moved [LABEL_CAMERA_OFFSET_PIXELS] east and south in normalised Mercator. */
private val LABEL_CAMERA: Camera = run {
    val world = (1 shl ANCHOR_TILE_Z).toDouble() * TILE_SIDE_LOGICAL_PIXELS
    val offset = LABEL_CAMERA_OFFSET_PIXELS / world
    val mercatorX = (ANCHOR_TILE_X + 0.5) / (1 shl ANCHOR_TILE_Z).toDouble() + offset
    val mercatorY = (ANCHOR_TILE_Y + 0.5) / (1 shl ANCHOR_TILE_Z).toDouble() + offset
    Camera(
        latitude = atan(sinh(PI * (1.0 - 2.0 * mercatorY))) * DEGREES_PER_RADIAN,
        unwrappedLongitude = mercatorX * 360.0 - 180.0,
        zoom = ANCHOR_TILE_Z.toDouble(),
        bearing = 0.0,
        pitch = 0.0,
    )
}

/**
 * **1.5, and never 1.** All six corpus styles that declare terrain declare an exaggeration of 1, at
 * which an honoured multiplier and a dropped one are the same picture — this cycle's own recorded trap.
 * It is also task 14's spike's value, and the regime its lift ladder was measured in: at 1.5 and zoom
 * 13 this DEM stands about 220 logical pixels tall and a metre is 0.149 of one.
 */
private const val TERRAIN_EXAGGERATION: Double = 1.5

/**
 * What [DEM_RIDGE_PNG] decodes to at the texel a tile's own centre lands in.
 *
 * Derived rather than copied: that DEM's column `x` carries `1000 * (1 - |2 * (x + 0.5) / 64 - 1|)`
 * metres quantised to the Mapbox packing's tenth of a metre, and `GROUND_ELEVATION_SOURCE`'s rule puts a
 * grid node at `u = 0.5` on texel `floor(0.5 * 64) = 32`, which carries **984.4 m**.
 */
private const val RIDGE_CREST_METRES: Double = 984.4

/** The height the ground actually draws at the feature's own anchor. */
private const val DRAWN_CREST_METRES: Double = RIDGE_CREST_METRES * TERRAIN_EXAGGERATION

/**
 * The height the ridge draws on average across [ridgeGeometry]'s footprint: the profile is a triangle
 * spanning 15.6 m to 984.4 m, whose mean over whole periods is 500 m, times the exaggeration.
 *
 * The mean rather than the summit or the base, either of which would put the flat quad entirely on one
 * side of the terrain and measure nothing.
 */
private const val MEAN_DRAWN_RIDGE_METRES: Double = 500.0 * TERRAIN_EXAGGERATION

/**
 * 50 metres, which is task 14's spike's own control offset and about 7.5 logical pixels at zoom 13: far
 * enough above the ground to leave the coplanar fight outright, and near enough that the raised quad's
 * footprint is the drape's to within about a pixel of fringe.
 */
private const val DRAPE_CONTROL_METRES: Double = 50.0

/** 1,000 m, about 149 logical pixels at zoom 13: visible at pitch 45, absurd nowhere. */
private const val PLAIN_SENSITIVITY_METRES: Double = 1_000.0

/** A 32 x 32 sticker is 1,024 pixels; a quarter of that leaves room for a rasteriser and none for a
 * sticker that never drew. */
private const val MINIMUM_STICKER_PIXELS: Int = 256

/** 16 pixels of a 1,024-pixel sticker: a billboard under 1,477 m of terrain is gone, and the budget is
 * for a fill-rule sliver at the quad's own edge. */
private const val MAXIMUM_BURIED_STICKER_PIXELS: Int = 16

/**
 * How far the ridden sticker must sit from the same sticker over a sea-level DEM.
 *
 * 1,477 m at zoom 13 is about 220 logical pixels of rise, which pitch 45 turns into a measured
 * **181.91** pixels on all three of this project's drivers. 32 is far below that and far above any
 * sub-pixel wobble a centroid can carry.
 */
private const val MINIMUM_ANCHOR_RIDE_PIXELS: Double = 32.0

/** Two frames of the same anchor on the same surface must agree to within a pixel of centroid noise. */
private const val MAXIMUM_ANCHOR_AGREEMENT_PIXELS: Double = 2.0

/** [ridgeGeometry] runs off every edge of the frame, so a draped one covers essentially all of it. */
private const val MINIMUM_DRAPED_PIXELS: Int = 400_000

/** A flat quad at the ridge's mean height keeps its flanks and loses its middle: measured **366,336 of
 * the drape's 579,768**, which is 63 per cent. 75 is a ceiling a genuinely draped quad cannot slip
 * under and the cut quad clears by 12 points. */
private const val MAXIMUM_CUT_QUAD_PERCENT: Int = 75

/** And it must still draw its flanks, or the ceiling above is satisfied by a quad that never drew. */
private const val MINIMUM_CUT_QUAD_PIXELS: Int = 50_000

/** A quad at sea level under a 1,477 m ridge is gone; the budget is the frame's own edge fringe. */
private const val MAXIMUM_BURIED_QUAD_PIXELS: Int = 512

/** The drape and the ground share essentially the whole 589,824-pixel frame; two thirds is the floor. */
private const val MINIMUM_CONTESTED_PIXELS: Int = 400_000

/**
 * **95 per cent**, stated against task 14's spike rather than against this fixture's own reading.
 *
 * The spike measured **102,370 of 102,400** — 99.97 per cent — for a four-metre lift on the offset
 * lattice at zoom 13 and pitch 0, and **53,048 of 102,400** for no lift at all.
 * `TerrainFrameReadbackSuite`'s own coplanar case reads 579,754 of 589,824 (98.29 per cent) on the same
 * DEM at the same camera. **This fixture reads 579,768 of 589,824 (98.295 per cent) on Apple M3 Max and
 * 579,650 of 589,619 (98.31 per cent) on `Apple Software Renderer` through both CGL and EAGL** — the
 * two suites agree to fourteen pixels, which is what makes the two readings one number rather than two.
 * With [GROUND_DRAPE_LIFT_METRES] set to zero it reads **290,581 (49.27 per cent)**, reproducing the
 * spike's "no lift keeps about half" and that suite's 293,346.
 *
 * The 1.7-point shortfall against the spike's own whole is this DEM being steeper per ground cell
 * rather than slack — the residual the lift fights lives along the creases, and [DEM_RIDGE_PNG] steps
 * 46.9 m of drawn relief per cell where the spike's fixture stepped 28. So 95 sits three points below
 * the measurement, with room for a rasteriser, and 46 points above the lift's absence.
 */
private const val MINIMUM_SURVIVOR_PERCENT: Int = 95

/** And the sunk control must keep essentially none, or the survivor count is measuring a ground that
 * cannot delete anything. Two per cent rather than zero, for the fringe where the sunk quad's slightly
 * smaller footprint falls outside the contested set's edge. */
private const val MAXIMUM_SUNK_SURVIVOR_PERCENT: Int = 2

/** How many frames the label fade ramp runs for. `LABEL_FADE_STEPS` advances one step per frame, so ten
 * saturates it and the last frame's ink is the style's own colour rather than a tenth of it. */
private const val LABEL_FADE_RAMP: Int = 10

/** The fixture's one glyph cell is 9.33 by 10.67 logical pixels, so about a hundred; a third of that is
 * the floor, which leaves room for the anti-aliased rim the fill band does not saturate. */
private const val MINIMUM_LABEL_PIXELS: Int = 32

/**
 * The fixture must predict a movement worth measuring before the equalities below mean anything. It
 * predicts **66.16** pixels at this camera — `(-46.78, -46.78)` — and measures `(-47.00, -46.50)`.
 */
private const val MINIMUM_LABEL_RIDE_PIXELS: Double = 16.0

/**
 * Three pixels between the drawn centroid and the projected anchor.
 *
 * A centroid is the anchor plus a constant, and the constant cancels in a difference — but the two
 * frames' glyph cells land on different sub-pixel phases and their anti-aliased edges are not the same
 * set of pixels, which is worth a fraction of a pixel: measured **0.22** in x and **0.28** in y, on all
 * three drivers. Three is an order of magnitude above that and well inside the
 * [MINIMUM_EXAGGERATION_SEPARATION_PIXELS] the case has to discriminate.
 */
private const val LABEL_CENTROID_TOLERANCE_PIXELS: Double = 3.0

/**
 * How far the unexaggerated prediction sits from the exaggerated one at this camera, less a margin: the
 * two are `(-28.25, -28.25)` and `(-46.78, -46.78)`, so the drawn ink lands a measured **26.19** pixels
 * from where a build that dropped `terrain.exaggeration` would have put it.
 */
private const val MINIMUM_EXAGGERATION_SEPARATION_PIXELS: Double = 8.0

/** A terrainless frame carrying a ground, a sticker and a quad paints essentially all of itself. */
private const val MINIMUM_PLAIN_PAINTED_PIXELS: Int = 400_000

/** Raising both by 1,000 m at pitch 45 moves a quarter-tile quad and a sticker over a measured
 * **69,657** pixels; 4,096 is far below that and far above any rasteriser's noise. */
private const val MINIMUM_PLAIN_SENSITIVITY_PIXELS: Int = 4_096

// ---- the fixture's bytes ------------------------------------------------------------------------------

private const val ANCHOR_STYLE_URL: String = "https://styles.example/ground-anchor.json"
private const val ANCHOR_PLAIN_STYLE_URL: String = "https://styles.example/ground-anchor-plain.json"
private const val RIDING_STICKER_URL: String = "https://images.example/ground-anchor-riding.png"
private const val BURIED_STICKER_URL: String = "https://images.example/ground-anchor-buried.png"
private const val ANCHOR_DEM_TEMPLATE: String = "https://tiles.example/ga-dem/{z}/{x}/{y}.png"

/**
 * The ground is a **background layer** rather than a raster source, which is the one structural
 * difference between this fixture and `runTerrainFrameReadbackSuite`'s.
 *
 * That suite needs per-tile colours to see a seam; every claim here is "did this content hold this
 * pixel", so one uniform ground colour is the instrument and a second source would only add ways for
 * the fixture to be wrong. Measured by `LabelIntegrationReadbackSuite`: a style whose only other layers
 * are symbol ones rasterises to a fully transparent tile, because Rentile hands its text over as
 * candidates rather than drawing it — so the background layer is the whole of what the ground paints,
 * and a `drawLabels = false` frame carries no glyph.
 *
 * **`tileSize` is 64 on the DEM because RenG accepts 64, 256 and 512 and nothing else**, and 64 is the
 * size at which a whole DEM tile is a checked-in PNG. **`maxzoom` is 22**, so every requested tile has a
 * DEM of its own and the overzoom window is the identity; that arithmetic is `DemTileWindowTest`'s.
 */
private val ANCHOR_STYLE_JSON: String = anchorStyle(
    ""","dem":{"type":"raster-dem","tiles":["$ANCHOR_DEM_TEMPLATE"],"tileSize":64,""" +
        """"encoding":"mapbox","minzoom":0,"maxzoom":22}""",
    ""","terrain":{"source":"dem","exaggeration":$TERRAIN_EXAGGERATION}""",
)

/**
 * [ANCHOR_STYLE_JSON] with the `terrain` block and the `raster-dem` source removed and **nothing else
 * changed**, so case 5's byte comparison is a comparison of terrain against no terrain rather than of
 * two different maps.
 */
private val ANCHOR_PLAIN_STYLE_JSON: String = anchorStyle("", "")

private fun anchorStyle(demSource: String, terrain: String): String =
    """{"version":8,"name":"reng-ground-anchor",""" +
        """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" +
        """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14}""" +
        demSource +
        """},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f02828"}},""" +
        """{"id":"place","type":"symbol","source":"v","source-layer":"place",""" +
        """"layout":{"text-field":"{name}","text-font":["$LABEL_SANS_STACK"],"text-size":16},""" +
        """"paint":{"text-color":"#00a0ff"}}]$terrain}"""

/** Which DEM the transport is currently answering with. */
private enum class AnchorRelief { RIDGE, SEA_LEVEL }

/**
 * A **saturated** distance field, for `LabelIntegrationReadbackSuite`'s reason: the shared handover
 * fixture ramps its field across `64..191`, and 191 is the fill edge itself, so every texel of it lands
 * inside the smoothstep band and no drawn pixel ever reaches the style's own colour. Saturating the
 * cell draws each glyph as a solid block, which is legible to a centroid and not legible as a letter.
 * Legibility is Cycle J's.
 */
private val SATURATED: (Int) -> Byte = { 0xFF.toByte() }

private val ANCHOR_SANS_RANGE: ByteArray =
    labelGlyphRange(LABEL_SANS_STACK, "0-255", listOf('A'.code), SATURATED)

/** The one-letter `place` feature, at the centre of whichever tile serves it. */
private val ANCHOR_FEATURE_MVT: ByteArray = labelMvtBytes("place" to "A")

/**
 * A tile with no layers at all, which produces no candidate.
 *
 * Every tile url but [ANCHOR_TILE_X] / [ANCHOR_TILE_Y]'s gets this, so the frame carries **exactly one**
 * label — the shared fixture puts a copy of its feature at the centre of every tile it is asked for, and
 * a 768-pixel frame at zoom 13 selects four of them.
 */
private val ANCHOR_EMPTY_MVT: ByteArray = labelMvtBytes()

private val ANCHOR_TILE_URL_PREFIX: String = LABEL_TILE_TEMPLATE.substringBefore("{z}")
private val ANCHOR_DEM_URL_PREFIX: String = ANCHOR_DEM_TEMPLATE.substringBefore("{z}")
private val ANCHOR_FEATURE_TILE_URL: String =
    "$ANCHOR_TILE_URL_PREFIX$ANCHOR_TILE_Z/$ANCHOR_TILE_X/$ANCHOR_TILE_Y.pbf"

/**
 * Serves the two styles, the two sticker images, one Glyph Range, the feature's own vector tile and
 * whichever DEM [relief] currently names.
 *
 * **[relief] is mutable, and the store below never returns a hit**, so Rentile refetches every DEM tile
 * of every frame and a render therefore sees the policy set immediately before it — which is what makes
 * case 1's and case 4's two arms differ in nothing but those bytes.
 *
 * A url composed from the wrong template fails closed here rather than being handed a plausible body.
 */
private class GroundAnchorTransport : Transport {
    var relief: AnchorRelief = AnchorRelief.RIDGE

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        val style = when (url) {
            ANCHOR_STYLE_URL -> ANCHOR_STYLE_JSON
            ANCHOR_PLAIN_STYLE_URL -> ANCHOR_PLAIN_STYLE_JSON
            else -> null
        }
        if (style != null) {
            return TransportResponse(
                statusCode = 200,
                body = style.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
        }
        val body = when {
            url == RIDING_STICKER_URL -> RIDING_STICKER_PNG
            url == BURIED_STICKER_URL -> BURIED_STICKER_PNG
            url == labelGlyphUrls()[0] -> ANCHOR_SANS_RANGE
            url == ANCHOR_FEATURE_TILE_URL -> ANCHOR_FEATURE_MVT
            url.startsWith(ANCHOR_TILE_URL_PREFIX) -> ANCHOR_EMPTY_MVT
            url.startsWith(ANCHOR_DEM_URL_PREFIX) -> when (relief) {
                AnchorRelief.RIDGE -> DEM_RIDGE_PNG
                AnchorRelief.SEA_LEVEL -> DEM_SEA_LEVEL_PNG
            }
            else -> return TransportResponse(statusCode = 404, body = ByteArray(0))
        }
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "application/octet-stream"),
        )
    }
}

/**
 * Reads nothing and writes nothing, which is what forces every DEM through the transport on every frame
 * and lets [GroundAnchorTransport.relief] mean what it says.
 */
private class GroundAnchorStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource): Unit = Unit
}

// Both PNGs below are real, valid truecolour-with-alpha images (colour type 6) generated once via
// CPython's zlib/struct modules exactly as PngDecoderTest.kt documents:
//
//     import zlib, struct, base64
//     def chunk(kind, payload):
//         return (struct.pack(">I", len(payload)) + kind + payload +
//                 struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
//     raw = b"".join(b"\x00" + bytes(rgba) * 16 for _ in range(16))
//     png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) +
//            chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

/** 16 x 16, every texel [RIDING_STICKER]. */
private val RIDING_STICKER_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAFklEQVR42mP4TyFgGDVg1IBRA4aLAQBdePwurSGp" +
        "XgAAAABJRU5ErkJggg==",
)

/** 16 x 16, every texel [BURIED_STICKER]. */
private val BURIED_STICKER_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGklEQVR42mP4/5/hPyWYYdSAUQNGDRguBgAAxp79" +
        "H8morbIAAAAASUVORK5CYII=",
)
