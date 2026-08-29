package com.rohittp.reng

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
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.GlobeRayResult
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.isWithinMercatorPlanningSupport
import com.rohittp.reng.internal.projection.physicalPixelGlobeRay
import com.rohittp.reng.internal.projection.projectVisibleGeographicPosition
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Cycle G task 12's gate: a `ProjectionMode.GLOBE` frame drawn through the **public** API, read back
 * off a real driver, and asserted against arithmetic rather than against a stored picture.
 *
 * **Why a sibling of [runGlobeGroundReadbackSuite] rather than five more cases inside it.** That
 * suite calls `drawGlobeGround` directly and stands in for the caller, because it was written while
 * `FramePlanningCore` still refused `GLOBE` and no public call could put a globe frame in front of a
 * driver at all. Task 10 removed that refusal, so what is unproven now is not the ground pass but
 * the **composition**: camera resolution, the globe footprint, `selectGlobeTiles`, the firewall and
 * the engine, texture residency, the horizon cull that only the draw path applies, the offscreen
 * surface and the composite. None of that is reachable from `drawGlobeGround`, and its fixture — a
 * hand-built tile list and a hand-composed matrix — cannot express a `FramePlan` at all. Every case
 * below therefore goes in as a `FramePlan` and comes out as pixels, which is exactly the shape
 * `runBasemapReadbackSuite` established for Mercator.
 *
 * **What it claims.**
 * - **The limb is where the sphere puts it.** The painted set is compared, pixel by pixel, against
 *   a ray cast per pixel in `Double` through [physicalPixelGlobeRay]. A tangent plane covers the
 *   whole frame; a sphere covers a disc whose radius is set by the camera's distance and leaves the
 *   corners empty.
 * - **Nothing on the far hemisphere draws.** ADR 0038 culls it, and a placement there projects to a
 *   perfectly ordinary pixel with a large positive `w` — the antipodal one lands dead centre — so
 *   this is the one claim no viewport or near-plane test can make. The fixture brackets the limb
 *   between two placements 6 degrees apart that project **0.16 pixels** from each other.
 * - **Tiles meet across the antimeridian.** The camera sits on it, so tile `x = 0`'s west edge and
 *   tile `x = 2^lod - 1`'s east edge — the same meridian, reached from opposite ends of the Mercator
 *   world — are drawn side by side down the middle of the frame.
 * - **The two projection modes agree at the view centre and part where curvature says they must**,
 *   bounded in **both** directions. This is the case the spec calls a vacuity waiting to be written.
 * - **`drawBasemap = false` draws nothing**, without which the four above can all pass vacuously.
 *
 * **What it does NOT claim, stated so nobody reads more into a green run than is there.**
 * **Curvature fidelity is not claimed here and stays Cycle J's**, by the owner's own gate decision
 * (`docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` section 10.4): nothing below compares
 * against a stored image, so this suite says nothing about how a globe frame *looks* — only about
 * relationships between pixels that hold or do not. It also says nothing about sub-pixel tile
 * placement, filtering quality, blend correctness at tile edges, terrain, models, or any regression
 * that preserves both the silhouette and the four fiducial centroids.
 *
 * **Why the cross-mode case is taken at zoom 2 and bounded from below.** The sagitta of a
 * frame-sized quad is 27.9 logical pixels at zoom 4, 1.75 at zoom 8 and **0.44 at zoom 10**, so a
 * globe that is secretly a tangent plane passes any cross-mode comparison above about zoom 12 — and
 * passes a one-sided "the two modes are close" comparison at **every** zoom. The case therefore
 * asserts a **floor** as well as a ceiling, and takes its measurement at a zoom where the floor is
 * an order of magnitude above the noise.
 *
 * **Nothing on the expected side runs `decodePng`.** Every fixture image is a real PNG generated
 * once by CPython's `zlib`/`struct` modules and pasted as base64, exactly as `PngDecoderTest`
 * documents; the expected colours are hand-written constants. A decoder regression can only make
 * this suite fail, never pass.
 *
 * **Measured against three deliberately broken builds rather than reviewed**, which is E-labels'
 * hardest-won lesson and this cycle's standing obligation. Each mutation was reverted afterwards and
 * the revert confirmed against `git diff`:
 *
 * **The globe projection made a no-op** — `FramePlanningCore` dispatching `GLOBE` to
 * `planMercatorSpatial` and `RenGRenderer.resolveFrameCamera` to `resolveMercatorCamera`, so a
 * `GLOBE` frame plans, resolves and draws as a Mercator one. **3 of 5 failed**: the limb, with the
 * frame's four corners painted and 24,356 pixels disagreeing with the ray cast; the far hemisphere,
 * whose 40-degree sticker was off screen entirely; and the cross-mode case, whose far east fiducial
 * measured **0.000** pixels of displacement against a 4.0 floor and a 9.81 prediction.
 *
 * **The antimeridian carry dropped** from `globeGroundTileEdges`, so a tile whose east edge wraps
 * spans the rest of the planet backwards instead. **3 of 5 failed**: the antimeridian, with the
 * cleared target showing through 2.5 pixels west of the seam; the limb, 12,341 pixels disagreeing
 * and 28,839 drawn against 41,180; and the far hemisphere.
 *
 * **`drawGlobeGround`'s `enable(GL_CULL_FACE)` turned into `disable`**, so ADR 0038's far hemisphere
 * draws. **1 of 5 failed** — the far-hemisphere case **alone**, by exactly the assertion written for
 * it: the frame's centre carried the antipodal tile's `(40, 40, 200)` instead of the anchor tile's
 * `(200, 200, 40)`.
 *
 * The third is the useful one: a mutation caught by every case is weaker evidence than one caught by
 * exactly one. The `drawBasemap = false` negative failed under none of the three, which is what a
 * negative is for — a mutation that made it fail would be a mutation that drew something.
 *
 * **What no mutation here reached**: the antimeridian case is blind to the first, because Mercator
 * joins tiles across a world-copy boundary correctly and has since `0.3.0` — a globe frame rendered
 * as a Mercator one has a perfectly continuous antimeridian. That is why the second exists.
 */
internal fun runGlobeFrameReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createGlobeFrameTarget(binding)
    // Measured against this suite's own 256-pixel target rather than the basemap suite's 128-pixel
    // one, so the number printed here is this suite's: 0 disagreeing pixels on `Apple M3 Max` and
    // 2,112 on `Apple Software Renderer` through both CGL and the iOS simulator's EAGL. Only the
    // verdict is used, and it agrees with the basemap suite's on every driver measured.
    val rasterisation = measureLargeQuadRasterisation(binding, dialect, target)
    println(
        "RenG globe frame readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect " +
            rasterisation.describe(),
    )
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS),
            transport = GlobeFrameTransport(),
            store = RecordingStyleStore(),
            basemapStyle = ResourceLocator(GLOBE_FRAME_STYLE_URL),
        ),
        binding,
        probe,
    )
    val failures = CollectedGlobeFrameFailures()
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        val fixture = GlobeFrameFixture(binding, renderer, renderTarget, target)
        failures.run("the ground stops at the limb") {
            assertTheGroundStopsExactlyAtTheLimb(fixture)
        }
        failures.run("nothing on the far hemisphere draws") {
            assertNothingOnTheFarHemisphereDraws(fixture)
        }
        failures.run("tiles meet across the antimeridian") {
            assertTilesMeetAcrossTheAntimeridian(fixture)
        }
        failures.run("the two modes agree at the centre and part at the edge") {
            assertTheTwoModesAgreeAtTheCentreAndPartAtTheEdge(fixture, rasterisation.isTrustworthy)
        }
        failures.run("drawBasemap = false draws nothing") {
            assertDrawBasemapFalseLeavesAGlobeFrameUntouched(fixture)
        }
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
    failures.throwIfAny()
}

/**
 * Runs every case even after one has failed, and reports all of them together — `CollectedFailures`
 * in `BasemapReadbackSuite`, for the same reason and with the same cost model.
 *
 * A suite that stops at its first assertion costs one CI round trip per defect, and these run on
 * drivers a developer cannot reach: `0.3.0`'s publication failed on a hosted macOS runner whose
 * entire diagnostic was `kotlin.AssertionError at null:-1`. Every case draws its own frames into a
 * freshly cleared target and shares nothing but the renderer, so a later case is still worth
 * believing after an earlier one failed. Each message is printed as it happens, because Gradle's
 * console renders a Kotlin/Native failure's class and location and never its message.
 */
private class CollectedGlobeFrameFailures {
    private val messages: MutableList<String> = mutableListOf()

    fun run(name: String, case: () -> Unit) {
        try {
            case()
        } catch (error: AssertionError) {
            val message = error.message ?: error.toString()
            messages += "[$name] $message"
            println("RenG globe frame readback FAILED [$name] $message")
        }
    }

    fun throwIfAny() {
        if (messages.isEmpty()) return
        throw AssertionError(
            "${messages.size} of $GLOBE_FRAME_CASE_COUNT globe frame readback cases failed:\n" +
                messages.joinToString("\n"),
        )
    }
}

private const val GLOBE_FRAME_CASE_COUNT: Int = 5

// ---- case 1: the limb ------------------------------------------------------------------------

/**
 * **The limb case.** Every pixel of a whole-globe frame, against a ray cast through that pixel in
 * `Double` by [physicalPixelGlobeRay] — the inverse path, written for task 4 and tested there
 * against the forward one.
 *
 * This is the case a plane cannot pass, and it is the *end-to-end* version of the claim
 * `runGlobeGroundReadbackSuite` makes about `drawGlobeGround` alone: here the tile set comes from
 * `globeGroundFootprint` and `selectGlobeTiles`, the textures come out of the Rentile engine through
 * the firewall, and the frame is composited off an offscreen surface. A footprint that admitted the
 * far hemisphere, a selector that dropped a rim tile, a matrix composed with the wrong radius or a
 * ground pass that never ran all move this count and nothing else in the suite would notice.
 *
 * **The fixture is the whole planet at zoom 1 from latitude 8, longitude 40, bearing 23**, in a
 * 256-pixel frame. Every part of that is defending against a way the case could pass while saying
 * nothing:
 *
 * - **zoom 1 rather than the lowest legal zoom.** At zoom 1 the arithmetic puts the globe's radius
 *   at 164.58 logical pixels against a camera 309.02 above the surface, so the limb sits
 *   `acos(R / (R + d))` = **69.665 degrees** from the anchor and subtends a disc of radius
 *   **114.5 pixels** in a frame whose half-width is 128 — inscribed, with the corners left empty.
 *   Half a zoom level lower the LOD selector drops to 0 and the whole planet becomes one tile, which
 *   would make the antimeridian case below impossible and every tile-identity assertion vacuous.
 * - **latitude 8, not the equator and not 45.** Mercator's +/-85.0511 degree clip leaves two polar
 *   caps with no tile at all; the north cap's near edge is 77.05 degrees from this anchor and the
 *   south cap's 93.05, both beyond the limb, so the drawn set is a full disc rather than a disc with
 *   two bites out of it and the ray-cast comparison is over the whole circle.
 * - **longitude 40 and bearing 23.** The anchor sits inside tile `(x = 1, y = 0)` rather than on a
 *   seam, and nothing in the frame is symmetric about either screen axis.
 *
 * Reported as a count rather than asserted as an equality because a rasteriser's fill rule owns the
 * boundary: the disc's circumference here is **720 pixels** and a driver may round any of them
 * either way. [MAXIMUM_LIMB_DISAGREEMENT] is that boundary, not a defect budget — the failure it
 * exists to catch is nothing like its size, since a ground drawn flat covers the corners too and a
 * ground that never drew leaves all 65,536 pixels empty.
 */
private fun assertTheGroundStopsExactlyAtTheLimb(fixture: GlobeFrameFixture) {
    val frame = fixture.render(
        FramePlan(
            frameIndex = fixture.nextFrameIndex(),
            camera = globeFrameCamera(),
            projectionMode = ProjectionMode.GLOBE,
            drawBasemap = true,
        ),
    )
    val expected = fixture.rayCastGlobeMask(globeFrameCamera())
    var disagreements = 0
    var expectedPainted = 0
    var painted = 0
    for (row in 0 until GLOBE_FRAME_READBACK_PIXELS) {
        for (column in 0 until GLOBE_FRAME_READBACK_PIXELS) {
            val index = row * GLOBE_FRAME_READBACK_PIXELS + column
            if (expected[index]) expectedPainted += 1
            val drawn = frame.isPainted(column, row)
            if (drawn) painted += 1
            if (drawn != expected[index]) disagreements += 1
        }
    }
    println(
        "RenG globe frame limb: $disagreements of " +
            "${GLOBE_FRAME_READBACK_PIXELS * GLOBE_FRAME_READBACK_PIXELS} pixels disagree with the " +
            "ray-cast globe; drawn=$painted expected=$expectedPainted",
    )

    assertTrue(
        expectedPainted in MINIMUM_EXPECTED_DISC_PIXELS..MAXIMUM_EXPECTED_DISC_PIXELS,
        "the fixture must actually inscribe a disc in the frame, or every count here is vacuous: " +
            "the ray cast finds $expectedPainted painted pixels, outside " +
            "$MINIMUM_EXPECTED_DISC_PIXELS..$MAXIMUM_EXPECTED_DISC_PIXELS",
    )
    assertEquals(
        0,
        frame.paintedAt(0, 0) + frame.paintedAt(GLOBE_FRAME_READBACK_PIXELS - 1, 0) +
            frame.paintedAt(0, GLOBE_FRAME_READBACK_PIXELS - 1) +
            frame.paintedAt(GLOBE_FRAME_READBACK_PIXELS - 1, GLOBE_FRAME_READBACK_PIXELS - 1),
        "a globe leaves the frame's corners empty where a plane would not. Frame:\n" +
            frame.asciiMap(),
    )
    assertTrue(
        disagreements <= MAXIMUM_LIMB_DISAGREEMENT,
        "the drawn ground must be the globe the camera subtends: $disagreements pixels disagree, " +
            "against a boundary budget of $MAXIMUM_LIMB_DISAGREEMENT (the disc's own 720-pixel " +
            "circumference, one pixel of fill-rule rounding each). Frame:\n" + frame.asciiMap(),
    )
}

// ---- case 2: the far hemisphere --------------------------------------------------------------

/**
 * **The antipodal-invisibility case**, and the sharpest claim in the suite: ADR 0038 culls the far
 * hemisphere, and nothing about a far-side position's *projection* betrays it.
 *
 * Four map-anchored stickers at the same camera as the limb case, at great-circle distances of
 * **40, 66, 72 and 180 degrees** from the anchor, where arithmetic puts the limb at **69.665**. The
 * pair either side of it is what makes this a bound on the limb's *position* rather than a check
 * that something somewhere is culled: at 66 degrees the anchor projects to window `(88.4, 20.1)`
 * and at 72 degrees to `(88.4, 20.0)` — **0.16 pixels apart**, the same colour region of the frame,
 * one drawn and one not. No viewport test, no near-plane test and no `w` sign can separate those
 * two, which is exactly ADR 0038's argument stated in pixels.
 *
 * The antipode is the headline of the same claim: it carries the **largest** positive `w` any point
 * of the planet can have (`d + R`, 638 logical pixels here against the anchor's 309) and it projects
 * to the exact centre of the frame. A horizon test written as "is `w` negative" — which both this
 * cycle's specification and its plan proposed in their first drafts — would cull nothing at all
 * while looking correct, and this case is what says so.
 *
 * **The two hidden stickers are declared after the two visible ones**, deliberately. They project on
 * top of them, so a cull that failed would paint [FAR_STICKER] over [NEAR_STICKER] rather than
 * hiding beneath it; declared first they would be invisible either way and the case would pass on a
 * broken cull.
 *
 * The ground carries the same claim independently: the frame's centre pixel looks straight down at
 * the anchor, which lies in tile `(x = 1, y = 0)`, while the **antipode** lies in tile
 * `(x = 0, y = 1)`. A near hemisphere culled instead of a far one leaves the identical disc and
 * fills its centre with the other colour.
 */
private fun assertNothingOnTheFarHemisphereDraws(fixture: GlobeFrameFixture) {
    val camera = globeFrameCamera()
    val frame = fixture.render(
        FramePlan(
            frameIndex = fixture.nextFrameIndex(),
            camera = camera,
            projectionMode = ProjectionMode.GLOBE,
            drawBasemap = true,
            stickers = listOf(
                mapSticker(INSIDE_LIMB_AT_40_DEGREES, NEAR_STICKER_URL),
                mapSticker(INSIDE_LIMB_AT_66_DEGREES, NEAR_STICKER_URL),
                mapSticker(BEYOND_LIMB_AT_72_DEGREES, FAR_STICKER_URL),
                mapSticker(ANTIPODE, FAR_STICKER_URL),
            ),
        ),
    )
    val resolved = fixture.globeCamera(camera)

    listOf(
        "40 degrees from the anchor" to INSIDE_LIMB_AT_40_DEGREES,
        "66 degrees from the anchor" to INSIDE_LIMB_AT_66_DEGREES,
    ).forEach { (name, position) ->
        val window = fixture.windowPixel(resolved, position)
        val radius = hypot(
            window.first - (GLOBE_FRAME_READBACK_PIXELS / 2.0 - 0.5),
            window.second - (GLOBE_FRAME_READBACK_PIXELS / 2.0 - 0.5),
        )
        assertTrue(
            radius <= MAXIMUM_EXPECTED_DISC_RADIUS,
            "the $name sticker must project inside the limb's own 114.5-pixel disc for this case " +
                "to be about culling rather than about the viewport: it lands $radius pixels out",
        )
        val observed = frame.colourAt(window.first.roundToInt(), window.second.roundToInt())
        assertTrue(
            observed.isCloseTo(NEAR_STICKER),
            "a sticker $name is on the near face and must draw: at window " +
                "(${window.first.roundToInt()}, ${window.second.roundToInt()}) the pixel is " +
                observed.describe() + ", expected " + NEAR_STICKER.describe(),
        )
    }

    val hidden = frame.count { it.isCloseTo(FAR_STICKER) }
    assertEquals(
        0,
        hidden,
        "a sticker 72 degrees from the anchor and one at the antipode are both behind the planet " +
            "and neither may draw a pixel, but $hidden pixels carry their colour. The limb is at " +
            "69.665 degrees by arithmetic, and the 72-degree anchor projects 0.16 pixels from the " +
            "66-degree one that must draw. Frame:\n" + frame.asciiMap(),
    )

    val centre = GLOBE_FRAME_READBACK_PIXELS / 2
    val centrePixel = frame.colourAt(centre, centre)
    assertTrue(
        centrePixel.isCloseTo(NORTH_EAST_TILE),
        "the frame's centre looks straight down at the anchor, which is in the north-east tile, so " +
            "it must carry ${NORTH_EAST_TILE.describe()}; it carries ${centrePixel.describe()}. " +
            "The antipode is in the south-west tile ${SOUTH_WEST_TILE.describe()}, which is what a " +
            "near hemisphere culled in place of the far one would put here.",
    )
}

// ---- case 3: the antimeridian ----------------------------------------------------------------

/**
 * **The antimeridian-continuity case.** The camera stands on the antimeridian, so tile `x = 0`'s
 * west edge and tile `x = 1`'s east edge — the same meridian, reached from opposite ends of the
 * Mercator world — are drawn against each other down the middle of the frame.
 *
 * **This is the one seam that is not merely a shared edge.** Two tiles inside the world share a
 * Mercator `x`, and `globeGroundTileEdges` hands them the bitwise-identical longitude. Across the
 * antimeridian they do not: one tile's east edge is `mercatorX = 1`, which
 * `mercatorXLongitudeRadians` wraps to `-PI` and then carries a full turn to `+PI`, while its
 * neighbour's west edge is `mercatorX = 0`, which is `-PI` directly. Those are the same point on the
 * sphere and different numbers, and everything between them — the wrap, the turn, `cos` and `sin` of
 * plus and minus PI in `float` — has to agree to within a pixel or a crack of background shows
 * through. A world-copy index that survived onto the globe would put the two tiles a **whole turn**
 * apart rather than a fraction of a pixel.
 *
 * Each sample takes six pixels **across** the seam at -2.5, -1.5, -0.5, +0.5, +1.5 and +2.5 output
 * pixels, converted from pixels to Mercator `x` by measuring the local scale rather than assuming
 * it. All six must be painted, which is what a crack would break; and the outermost two must carry
 * two *different* tile colours, or the sample is not straddling anything and the continuity
 * assertion is free. Rentile's own adjacent tiles align exactly — measured, cross-seam differences
 * inside interior-column noise — so a seam here would be RenG's geometry.
 *
 * The camera is at latitude 5 with **bearing 31**, so the seam runs diagonally rather than down the
 * frame's own centre column: a fixture whose seam lies on a screen axis cannot tell a continuous
 * meridian from a renderer that happens to be symmetric about that axis.
 */
private fun assertTilesMeetAcrossTheAntimeridian(fixture: GlobeFrameFixture) {
    val camera = antimeridianCamera()
    val frame = fixture.render(
        FramePlan(
            frameIndex = fixture.nextFrameIndex(),
            camera = camera,
            projectionMode = ProjectionMode.GLOBE,
            drawBasemap = true,
        ),
    )
    val resolved = fixture.globeCamera(camera)
    var straddled = 0
    ANTIMERIDIAN_SAMPLE_ROWS.forEach { mercatorY ->
        val perPixel = fixture.mercatorXPerPixel(resolved, ANTIMERIDIAN_X, mercatorY)
        val colours = SEAM_OFFSETS_PIXELS.map { offset ->
            val mercatorX = ANTIMERIDIAN_X + offset * perPixel
            val window = fixture.windowPixelOfMercator(resolved, mercatorX, mercatorY)
            frame.colourAt(window.first.roundToInt(), window.second.roundToInt())
        }
        colours.forEachIndexed { index, colour ->
            assertTrue(
                !colour.isCloseTo(ABSENT),
                "the antimeridian left an unpainted pixel at Mercator y=$mercatorY, " +
                    "${SEAM_OFFSETS_PIXELS[index]} pixels across the seam: the pixel is " +
                    colour.describe() + ", which is the cleared target showing through. Frame:\n" +
                    frame.asciiMap(),
            )
        }
        if (!colours.first().isCloseTo(colours.last())) straddled += 1
    }
    assertEquals(
        ANTIMERIDIAN_SAMPLE_ROWS.size,
        straddled,
        "every antimeridian sample must cross from one tile's colour into the other's, or the " +
            "continuity assertion above is free. Frame:\n" + frame.asciiMap(),
    )
}

// ---- case 4: the two modes -------------------------------------------------------------------

/**
 * **The cross-mode agreement case**, which the specification names as a vacuity waiting to be
 * written and which is therefore bounded in **both** directions.
 *
 * **Why a floor is the whole point.** The sagitta of a frame-sized quad is 27.9 logical pixels at
 * zoom 4, 1.75 at zoom 8 and **0.44 at zoom 10**, so a globe that is secretly a tangent plane passes
 * any "the two modes are close" comparison above about zoom 12 — and passes it at *every* zoom if
 * the comparison has only an upper bound. Everything here is measured at **zoom 2**, where the
 * departure is tens of pixels rather than fractions of one.
 *
 * **The instrument is four map-anchored stickers with `SCREEN` scale and `SCREEN` rotation**, which
 * is `CONTEXT.md`'s billboard: a screen-parallel quad of a fixed output-pixel size pinned to a
 * coordinate. Its size is identical in both modes by construction — `resolveGlobePlacement`'s
 * `SCREEN` arms are the *same expression* as `resolvePlacement`'s — so its centroid is the
 * projection of one geographic point and nothing else, measured to about a twentieth of a pixel from
 * a 16 x 16 block of a colour nothing else in the frame carries. Tile seams would have been the
 * obvious instrument and are useless here: one basemap tile is 512 logical pixels a side at this
 * zoom, so a 256-pixel frame contains at most one seam and cannot place it where the measurement
 * wants it.
 *
 * **The four anchors are one Mercator plane offset apart**, at 0, 20, 100 and 100 output pixels from
 * the camera's own anchor, and what they measure is:
 *
 * | fiducial | Mercator offset | predicted in `Double` | measured off the driver |
 * |---|---:|---:|---:|
 * | centre | 0 px | **0.000** — the anchor is the camera-relative origin in both modes | **0.000** |
 * | near east | 20 px | **0.345** | **0.000** — a third of a pixel does not move a 16 x 16 block |
 * | far east | 100 px | **9.812** | **9.708** |
 * | far north | 100 px | **12.742** | **12.500** |
 *
 * That table is the two-sided claim: at the view centre the modes agree exactly, twenty pixels out
 * they still agree to a third of a pixel — which is G1's whole promise, that
 * `Camera.zoom` means the same on-screen ground scale in both modes — and a hundred pixels out they
 * part by ten, because the sphere has dropped `R * (1 - cos alpha)` = 12.6 logical pixels away from
 * its own tangent plane and the small circle through the anchor's latitude has drifted 8.5 pixels
 * north of the straight Mercator row. A tangent-plane globe puts **zero** in all four rows.
 *
 * **Both a north and an east fiducial**, because a fix that happens to work along one screen axis
 * must not read as a fix — `runBasemapReadbackSuite`'s own reason for sweeping bearing alongside
 * pitch. Latitude **34** rather than the equator, because at the equator every cosine in this
 * cycle's arithmetic is 1 and the spec records a defect already sitting on that symmetry point.
 *
 * The ground half of the comparison — that both modes fill the frame at a camera where neither has a
 * limb in view — is asserted only when [groundCoverageIsTrustworthy]. The Mercator ground at this
 * zoom is a quad reaching far outside the viewport, which is the exact shape
 * [measureLargeQuadRasterisation] exists to measure, and on a driver that drops it the missing
 * pixels would be the driver's rather than RenG's.
 */
private fun assertTheTwoModesAgreeAtTheCentreAndPartAtTheEdge(
    fixture: GlobeFrameFixture,
    groundCoverageIsTrustworthy: Boolean,
) {
    val camera = crossModeCamera()
    val stickers = crossModeFiducials().map { mapSticker(it.position, it.url) }
    val globeFrame = fixture.render(
        FramePlan(
            frameIndex = fixture.nextFrameIndex(),
            camera = camera,
            projectionMode = ProjectionMode.GLOBE,
            drawBasemap = true,
            stickers = stickers,
        ),
    )
    val mercatorFrame = fixture.render(
        FramePlan(
            frameIndex = fixture.nextFrameIndex(),
            camera = camera,
            projectionMode = ProjectionMode.MERCATOR,
            drawBasemap = true,
            stickers = stickers,
        ),
    )

    val globeCamera = fixture.globeCamera(camera)
    val mercatorCamera = fixture.mercatorCamera(camera)
    val report = StringBuilder("RenG globe frame cross-mode fiducials:")
    crossModeFiducials().forEach { fiducial ->
        val globeBlock = globeFrame.centroidOf(fiducial.colour)
        val mercatorBlock = mercatorFrame.centroidOf(fiducial.colour)
        assertTrue(
            globeBlock != null && mercatorBlock != null,
            "the ${fiducial.name} fiducial must draw in both modes, or its displacement is not a " +
                "measurement: globe=${globeBlock != null} mercator=${mercatorBlock != null}",
        )
        val globeCentroid = globeBlock!!
        val mercatorCentroid = mercatorBlock!!
        assertTrue(
            globeCentroid.pixels >= MINIMUM_FIDUCIAL_PIXELS &&
                mercatorCentroid.pixels >= MINIMUM_FIDUCIAL_PIXELS,
            "the ${fiducial.name} fiducial must be a whole 16x16 billboard in both modes, or its " +
                "centroid is biased by a clipped edge: globe=${globeCentroid.pixels} " +
                "mercator=${mercatorCentroid.pixels} against a floor of $MINIMUM_FIDUCIAL_PIXELS",
        )
        assertTrue(
            abs(globeCentroid.pixels - mercatorCentroid.pixels) <= MAXIMUM_FIDUCIAL_PIXEL_DIFFERENCE,
            "a `SCREEN`-scaled billboard is the same quad in both modes, so the ${fiducial.name} " +
                "fiducial must cover the same number of pixels: globe=${globeCentroid.pixels} " +
                "mercator=${mercatorCentroid.pixels}",
        )

        val measured = hypot(
            globeCentroid.x - mercatorCentroid.x,
            globeCentroid.y - mercatorCentroid.y,
        )
        val predicted = fixture.predictedCrossModeDisplacement(globeCamera, mercatorCamera, fiducial.position)
        report.append(" ${fiducial.name}=$measured(predicted $predicted)")
        assertTrue(
            abs(measured - predicted) <= MAXIMUM_PREDICTION_ERROR_PIXELS,
            "the ${fiducial.name} fiducial's measured cross-mode displacement $measured must match " +
                "the displacement the two `Double` camera paths predict, $predicted, within " +
                "$MAXIMUM_PREDICTION_ERROR_PIXELS pixels",
        )
        when (fiducial.bound) {
            CrossModeBound.AGREES -> assertTrue(
                measured <= fiducial.limitPixels,
                "the ${fiducial.name} fiducial is where the two modes must agree — G1 matches the " +
                    "on-screen ground scale at the camera's own latitude — but they differ by " +
                    "$measured pixels, over ${fiducial.limitPixels}",
            )
            CrossModeBound.PARTS -> {
                assertTrue(
                    measured >= fiducial.limitPixels,
                    "the ${fiducial.name} fiducial is where curvature must show: the two modes " +
                        "differ by only $measured pixels, under a floor of ${fiducial.limitPixels}. " +
                        "A globe that is secretly a tangent plane measures zero here, and passes " +
                        "any comparison that has only an upper bound.",
                )
                assertTrue(
                    measured <= MAXIMUM_PARTING_DISPLACEMENT_PIXELS,
                    "the ${fiducial.name} fiducial's $measured pixels of cross-mode displacement is " +
                        "far more than curvature accounts for at zoom 2, over a ceiling of " +
                        "$MAXIMUM_PARTING_DISPLACEMENT_PIXELS: the globe is a different size or a " +
                        "different place from the plane rather than a bent version of it",
                )
            }
        }
    }
    println(report.toString())

    val globeAbsent = globeFrame.count { it.isCloseTo(ABSENT) }
    assertEquals(
        0,
        globeAbsent,
        "at zoom 2 the globe subtends a 209-pixel disc in a frame whose half-diagonal is 181, so " +
            "its ground must cover every pixel: $globeAbsent are still the cleared target. Frame:\n" +
            globeFrame.asciiMap(),
    )
    if (groundCoverageIsTrustworthy) {
        val mercatorAbsent = mercatorFrame.count { it.isCloseTo(ABSENT) }
        assertEquals(
            0,
            mercatorAbsent,
            "the Mercator ground at the same camera must cover every pixel too, or the two modes " +
                "are not being compared over the same frame: $mercatorAbsent are still the cleared " +
                "target. Frame:\n" + mercatorFrame.asciiMap(),
        )
    } else {
        println(
            "RenG globe frame readback SKIPPED [the two modes agree at the centre and part at the " +
                "edge: Mercator ground coverage]: this driver does not rasterise quads that reach " +
                "far outside the viewport, which is the shape every Mercator ground tile has, so a " +
                "missing Mercator pixel would measure the driver rather than RenG. The globe half " +
                "of the same assertion and all four fiducials still ran.",
        )
    }
}

// ---- case 5: the negative --------------------------------------------------------------------

/**
 * **The negative, and the reason the four cases above are not vacuous**: with `drawBasemap = false`
 * a globe frame must come back exactly as the target was left, because RenG's own offscreen surface
 * clears to a fully transparent black that composites to nothing.
 *
 * The camera is the limb case's, which that case has already shown paints roughly 41,000 pixels — so
 * "nothing changed" here is a statement about the flag rather than about a camera that sees no
 * ground. **`drawLabels` is left at its default `true` deliberately**, exactly as
 * `runBasemapReadbackSuite`'s negative does: since E-labels task 8b split the two flags this plan
 * still acquires the style and selects the frame's tiles — `planGlobeSpatial` gates its selection on
 * `drawBasemap || drawLabels` — so everything a ground draw needs is planned and available, and the
 * ground must still not appear. Setting `drawLabels` false would restore the old
 * both-switches-off frame and quietly stop testing the split at all.
 */
private fun assertDrawBasemapFalseLeavesAGlobeFrameUntouched(fixture: GlobeFrameFixture) {
    val frame = fixture.render(
        FramePlan(
            frameIndex = fixture.nextFrameIndex(),
            camera = globeFrameCamera(),
            projectionMode = ProjectionMode.GLOBE,
            drawBasemap = false,
            drawLabels = true,
        ),
    )
    val drawn = frame.count { !it.isCloseTo(ABSENT) }
    assertEquals(
        0,
        drawn,
        "drawBasemap = false must draw no globe ground at all, but $drawn pixels changed. Frame:\n" +
            frame.asciiMap(),
    )
}

// ---- the harness -----------------------------------------------------------------------------

/**
 * The gate's frame size: 256 rather than `runBasemapReadbackSuite`'s 128, and the reason is
 * arithmetic rather than taste.
 *
 * The camera stands `height * (1 + sqrt 2) / 2` logical pixels off its anchor at every zoom, while
 * the globe's radius is `512 * 2^zoom / (2 * PI)`, so the smallest frame in which the whole planet
 * fits at a **legal** zoom is set by `Camera.zoom`'s own `0..22` floor. At 128 pixels the camera
 * stands 154.5 pixels off and the globe would have to be smaller than about zoom -0.04 to leave the
 * frame's corners empty; at 256 it stands 309.02 off and zoom 1 leaves a 114.5-pixel disc in a
 * 128-pixel half-frame — with the LOD selector at 1, which is what gives the antimeridian case two
 * tiles to join. Zoom 0 in a 128-pixel frame would have fitted the disc and put the whole planet in
 * a single LOD-0 tile, with no seam anywhere and every tile-identity assertion vacuous.
 */
internal const val GLOBE_FRAME_READBACK_PIXELS: Int = 256

/**
 * Per-channel tolerance. Every fixture colour below is separated from every other by at least 32 in
 * some channel and each region sampled is flat colour well inside a clamped texel, so this absorbs
 * driver rounding without ever admitting a neighbouring fixture colour.
 */
private const val CHANNEL_TOLERANCE: Int = 8

private class GlobeFrameFixture(
    private val binding: GlBinding,
    private val renderer: Renderer,
    private val renderTarget: RenderTarget,
    private val targetFramebuffer: Int,
) {
    private var frameIndex: Long = 0L

    fun nextFrameIndex(): Long = frameIndex++

    /** Draws [plan] into a freshly [ABSENT]-cleared target and reads the whole frame back. */
    fun render(plan: FramePlan): GlobeFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.viewport(0, 0, GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS)
        binding.clearColor(ABSENT[0] / 255f, ABSENT[1] / 255f, ABSENT[2] / 255f, ABSENT[3] / 255f)
        binding.clear(GL_COLOR_BUFFER_BIT)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

        // prepare() is suspending and reaches the Rentile engine on Dispatchers.Default; draw() is
        // synchronous GL work that must happen on the thread holding the context, so the two are
        // split rather than run inside one runBlocking body.
        val frame = runBlocking { renderer.prepare(plan) }
        try {
            renderer.draw(frame, renderTarget)
        } finally {
            frame.close()
        }

        val pixels = ByteArray(GLOBE_FRAME_READBACK_PIXELS * GLOBE_FRAME_READBACK_PIXELS * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, pixels,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        return GlobeFrame(pixels)
    }

    fun globeCamera(camera: Camera): ResolvedGlobeCamera =
        (
            resolveGlobeCamera(
                camera,
                OutputPixelSize(GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS),
            ) as SpatialOutcome.Success<ResolvedGlobeCamera>
            ).value

    fun mercatorCamera(camera: Camera): ResolvedFrameCamera =
        (
            resolveMercatorCamera(
                camera,
                OutputPixelSize(GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS),
            ) as SpatialOutcome.Success<ResolvedMercatorCamera>
            ).value

    /**
     * Where a geographic position lands, in `glReadPixels` **bottom-up** window pixels.
     *
     * [projectVisibleGeographicPosition] answers in `CONTEXT.md`'s continuous output-pixel screen
     * space, whose origin is top-left and whose `y` runs downward, so the row is flipped once here
     * rather than at every call site. It is the mode-independent entry point deliberately -- there is
     * no plain `projectGeographicPosition` overload taking a [ResolvedFrameCamera], so a caller that
     * wanted one without the horizon question would have to write it, which is the point. The driver
     * evaluates the same geometry from `composeMapModelViewProjection`'s `Float` matrix product, so
     * an agreement between the two is evidence rather than a tautology.
     */
    fun windowPixel(camera: ResolvedFrameCamera, position: GeographicPosition): Pair<Double, Double> {
        val projected = projectVisibleGeographicPosition(camera, position)
        assertTrue(
            projected is ScreenProjection.Projected,
            "a fixture position must project: $position gave $projected",
        )
        val pixel = projected as ScreenProjection.Projected
        return Pair(pixel.pixelX - 0.5, GLOBE_FRAME_READBACK_PIXELS - pixel.pixelY - 0.5)
    }

    /** [windowPixel] for a caller holding a normalised Mercator coordinate rather than a latitude --
     * the form a ground-tile edge comes in, which has no latitude at all. */
    fun windowPixelOfMercator(
        camera: ResolvedGlobeCamera,
        mercatorX: Double,
        mercatorY: Double,
    ): Pair<Double, Double> = windowPixel(camera, geographicOfMercator(mercatorX, mercatorY))

    /**
     * How much Mercator `x` one output pixel is worth near ([mercatorX], [mercatorY]), **measured**
     * rather than derived, so a seam sample's offsets are in pixels whatever the projection is doing
     * locally and whatever the camera's bearing has done to the screen axes.
     *
     * The two probe points are taken on either side of the seam and the difference is divided by the
     * on-screen distance between them, so this is a secant rather than a tangent and stays finite
     * across the wrap where a one-sided difference would not.
     */
    fun mercatorXPerPixel(camera: ResolvedGlobeCamera, mercatorX: Double, mercatorY: Double): Double {
        val step = 1.0 / 4096.0
        val before = windowPixelOfMercator(camera, mercatorX - step, mercatorY)
        val after = windowPixelOfMercator(camera, mercatorX + step, mercatorY)
        val distance = hypot(after.first - before.first, after.second - before.second)
        assertTrue(distance > 0.0, "a seam probe must move on screen at Mercator y=$mercatorY")
        return 2.0 * step / distance
    }

    /**
     * How far apart the two modes put one geographic position, in output pixels, derived in `Double`
     * from the two resolved cameras.
     *
     * This is the independently derived value the cycle's standing obligations require beside every
     * measurement: the readback measures where the **GPU** put a billboard, and this says where the
     * CPU's own two projections put its anchor. It is not what makes the case non-vacuous — the
     * floor and the ceiling are, since a tangent-plane globe would agree with a prediction of zero —
     * but a measurement that matches an independent derivation to a pixel is a much narrower claim
     * than one that merely falls inside a band.
     */
    fun predictedCrossModeDisplacement(
        globe: ResolvedGlobeCamera,
        mercator: ResolvedFrameCamera,
        position: GeographicPosition,
    ): Double {
        val onGlobe = windowPixel(globe, position)
        val onPlane = windowPixel(mercator, position)
        return hypot(onGlobe.first - onPlane.first, onGlobe.second - onPlane.second)
    }

    /**
     * Which pixels the globe subtends, one ray per pixel through [physicalPixelGlobeRay], excluding
     * anything outside Mercator's own support. Bottom-up to match `glReadPixels`, where the ray
     * caster counts rows from the top.
     */
    fun rayCastGlobeMask(camera: Camera): BooleanArray {
        val resolved = globeCamera(camera)
        val mask = BooleanArray(GLOBE_FRAME_READBACK_PIXELS * GLOBE_FRAME_READBACK_PIXELS)
        for (row in 0 until GLOBE_FRAME_READBACK_PIXELS) {
            for (column in 0 until GLOBE_FRAME_READBACK_PIXELS) {
                val hit = physicalPixelGlobeRay(
                    resolved,
                    column,
                    GLOBE_FRAME_READBACK_PIXELS - 1 - row,
                )
                mask[row * GLOBE_FRAME_READBACK_PIXELS + column] =
                    hit is GlobeRayResult.Hit && isWithinMercatorPlanningSupport(hit.point.x, hit.point.y)
            }
        }
        return mask
    }
}

/** One block of a fiducial colour: how many pixels carry it and where their centre of mass is. */
private class Centroid(val x: Double, val y: Double, val pixels: Int)

private class GlobeFrame(val bytes: ByteArray) {
    /** [column] rightward and [row] **upward** from the bottom-left, which is `glReadPixels`' own
     * order and the order [GlobeFrameFixture.windowPixel] flips its answer into. */
    fun colourAt(column: Int, row: Int): IntArray {
        require(column in 0 until GLOBE_FRAME_READBACK_PIXELS) { "column $column is off the frame" }
        require(row in 0 until GLOBE_FRAME_READBACK_PIXELS) { "row $row is off the frame" }
        val offset = (row * GLOBE_FRAME_READBACK_PIXELS + column) * 4
        return IntArray(4) { bytes[offset + it].toInt() and 0xff }
    }

    fun isPainted(column: Int, row: Int): Boolean = !colourAt(column, row).isCloseTo(ABSENT)

    fun paintedAt(column: Int, row: Int): Int = if (isPainted(column, row)) 1 else 0

    fun count(predicate: (IntArray) -> Boolean): Int {
        var total = 0
        for (row in 0 until GLOBE_FRAME_READBACK_PIXELS) {
            for (column in 0 until GLOBE_FRAME_READBACK_PIXELS) {
                if (predicate(colourAt(column, row))) total += 1
            }
        }
        return total
    }

    /** The centre of mass of every pixel carrying [colour], or `null` when none does. */
    fun centroidOf(colour: IntArray): Centroid? {
        var sumX = 0.0
        var sumY = 0.0
        var pixels = 0
        for (row in 0 until GLOBE_FRAME_READBACK_PIXELS) {
            for (column in 0 until GLOBE_FRAME_READBACK_PIXELS) {
                if (!colourAt(column, row).isCloseTo(colour)) continue
                sumX += column.toDouble()
                sumY += row.toDouble()
                pixels += 1
            }
        }
        if (pixels == 0) return null
        return Centroid(sumX / pixels, sumY / pixels, pixels)
    }

    /**
     * A coarse picture of the frame, one character per 8x8 block, keyed by nearest fixture colour.
     *
     * This is the failure message a human actually reads. "3005 pixels disagree with the ray-cast
     * globe" does not distinguish a ground drawn flat from a ground missing one tile from a
     * hemisphere drawn inside out; the picture does, at a glance, from a CI log.
     */
    fun asciiMap(): String {
        val builder = StringBuilder()
        for (block in 0 until GLOBE_FRAME_READBACK_PIXELS / MAP_GLYPH_PIXELS) {
            val row = GLOBE_FRAME_READBACK_PIXELS - 1 - (block * MAP_GLYPH_PIXELS + MAP_GLYPH_PIXELS / 2)
            for (column in 0 until GLOBE_FRAME_READBACK_PIXELS / MAP_GLYPH_PIXELS) {
                val pixel = colourAt(column * MAP_GLYPH_PIXELS + MAP_GLYPH_PIXELS / 2, row)
                builder.append(FIXTURE_GLYPHS.firstOrNull { pixel.isCloseTo(it.second) }?.first ?: '?')
            }
            builder.append('\n')
        }
        return builder.toString()
    }
}

private const val MAP_GLYPH_PIXELS: Int = 8

private fun IntArray.isCloseTo(other: IntArray): Boolean =
    indices.all { abs(this[it] - other[it]) <= CHANNEL_TOLERANCE }

private fun IntArray.describe(): String = "(${this[0]},${this[1]},${this[2]},${this[3]})"

private fun createGlobeFrameTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8,
        GLOBE_FRAME_READBACK_PIXELS, GLOBE_FRAME_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the globe frame readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

// ---- the fixture -----------------------------------------------------------------------------

/** The limb, antipodal and negative cases' camera; see [assertTheGroundStopsExactlyAtTheLimb] for
 * why every one of its five numbers is what it is. */
private fun globeFrameCamera(): Camera =
    Camera(latitude = 8.0, unwrappedLongitude = 40.0, zoom = 1.0, bearing = 23.0, pitch = 0.0)

/** The antimeridian case's camera: standing on the seam, off the equator, and turned 31 degrees so
 * the seam is not a screen axis. */
private fun antimeridianCamera(): Camera =
    Camera(latitude = 5.0, unwrappedLongitude = 180.0, zoom = 1.0, bearing = 31.0, pitch = 0.0)

/** The cross-mode case's camera. Zoom 2 because the sagitta at zoom 8 is 1.75 logical pixels over a
 * frame-sized quad and 0.44 at zoom 10; latitude 34 because the equator is where every cosine in
 * this cycle's arithmetic is 1. */
private fun crossModeCamera(): Camera =
    Camera(latitude = 34.0, unwrappedLongitude = -18.0, zoom = 2.0, bearing = 0.0, pitch = 0.0)

/**
 * A billboard: map-anchored at [position] with `SCREEN` rotation and `SCREEN` scale, so the quad is
 * screen-parallel, axis-aligned and **the same 16 x 16 output pixels in both projection modes** —
 * the 2 x 2 source image scaled by 8. `resolveGlobePlacement`'s `SCREEN` arms are the identical
 * expression to `resolvePlacement`'s, deliberately, which is what makes a centroid difference
 * between the two modes a statement about the anchor's projection and nothing else.
 */
private fun mapSticker(position: GeographicPosition, url: String): Sticker = Sticker(
    placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(position.latitude, position.unwrappedLongitude, position.altitudeMetres),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 8.0,
    ),
    image = ResourceLocator(url),
)

/**
 * The inverse of [com.rohittp.reng.internal.projection.projectMercator], written out here rather
 * than imported, so that the fixture's Mercator-to-geographic step is not the same expression the
 * renderer runs in the other direction.
 */
private fun geographicOfMercator(mercatorX: Double, mercatorY: Double): GeographicPosition {
    val isometricLatitude = PI * (1.0 - 2.0 * mercatorY)
    val latitude = (2.0 * atan(exp(isometricLatitude)) - PI / 2.0) * 180.0 / PI
    return GeographicPosition(
        latitude = latitude,
        unwrappedLongitude = (mercatorX - floor(mercatorX)) * 360.0 - 180.0,
        altitudeMetres = 0.0,
    )
}

/**
 * The four placements of [assertNothingOnTheFarHemisphereDraws], at great-circle distances of 40,
 * 66, 72 and 180 degrees from `globeFrameCamera()`'s anchor, where the limb is at 69.665.
 *
 * They are written as latitude and longitude rather than derived from the camera's own basis on
 * purpose: deriving them through `globeEastNorthUpBasis` would make the fixture a second evaluation
 * of the code under test, and a basis error would then move both the expectation and the pixel.
 */
private val INSIDE_LIMB_AT_40_DEGREES: GeographicPosition =
    GeographicPosition(latitude = 28.1455, unwrappedLongitude = 76.6662, altitudeMetres = 0.0)

private val INSIDE_LIMB_AT_66_DEGREES: GeographicPosition =
    GeographicPosition(latitude = -52.5129, unwrappedLongitude = 9.1089, altitudeMetres = 0.0)

private val BEYOND_LIMB_AT_72_DEGREES: GeographicPosition =
    GeographicPosition(latitude = -57.3515, unwrappedLongitude = 2.9186, altitudeMetres = 0.0)

/** Exactly opposite `globeFrameCamera()`'s anchor of (8, 40): the single most thoroughly hidden
 * point on the planet, and the one that projects to the frame's exact centre. */
private val ANTIPODE: GeographicPosition =
    GeographicPosition(latitude = -8.0, unwrappedLongitude = -140.0, altitudeMetres = 0.0)

/** Which side of a cross-mode fiducial's bound is the load-bearing one. */
private enum class CrossModeBound { AGREES, PARTS }

private class CrossModeFiducial(
    val name: String,
    val position: GeographicPosition,
    val url: String,
    val colour: IntArray,
    val bound: CrossModeBound,
    val limitPixels: Double,
)

/**
 * The four cross-mode fiducials, at 0, 20, 100 and 100 output pixels of Mercator plane offset from
 * `crossModeCamera()`'s anchor.
 *
 * **Every limit here is derived from the geometry rather than tuned to a run.** At this camera the
 * globe's radius is 393.17 logical pixels and the eye stands 309.02 off the surface, so a point 100
 * output pixels east has dropped `R * (1 - cos alpha)` = 12.62 logical pixels below the tangent
 * plane and drifted 8.52 pixels north of the Mercator row; the perspective divide then puts it 9.81
 * pixels from where Mercator puts it. Twenty pixels out the same arithmetic gives 0.345, and at the
 * anchor itself both modes resolve the camera-relative origin and the answer is exactly zero.
 *
 * So the **agreeing** limits are one and a half pixels — four times the arithmetic and still an
 * order of magnitude under the parting fiducials — and the **parting** floor is 4.0, which is above
 * the noise, below every term the arithmetic contains, and unreachable by a globe that is secretly a
 * tangent plane. That last is a measurement rather than a claim: dispatching `GLOBE` to
 * `planMercatorSpatial` takes both parting fiducials to **0.000** and this floor is what fails.
 *
 * Note what the near east row costs and what it buys. It measures 0.000 because a 0.345-pixel shift
 * does not move a 16 x 16 block's centroid at all, so a tangent-plane globe measures the same thing
 * there — which is exactly why it is an `AGREES` bound and why the floor lives on the far pair. A
 * fixture that put its only fiducials twenty pixels out would be the vacuity the spec warns about.
 */
private fun crossModeFiducials(): List<CrossModeFiducial> = listOf(
    CrossModeFiducial(
        name = "centre",
        position = GeographicPosition(34.0, -18.0, 0.0),
        url = NEAR_STICKER_URL,
        colour = NEAR_STICKER,
        bound = CrossModeBound.AGREES,
        limitPixels = 0.75,
    ),
    CrossModeFiducial(
        name = "near east",
        position = GeographicPosition(34.0, -14.484375, 0.0),
        url = NEAR_EAST_FIDUCIAL_URL,
        colour = NEAR_EAST_FIDUCIAL,
        bound = CrossModeBound.AGREES,
        limitPixels = 1.5,
    ),
    CrossModeFiducial(
        name = "far east",
        position = GeographicPosition(34.0, -0.421875, 0.0),
        url = FAR_EAST_FIDUCIAL_URL,
        colour = FAR_EAST_FIDUCIAL,
        bound = CrossModeBound.PARTS,
        limitPixels = 4.0,
    ),
    CrossModeFiducial(
        name = "far north",
        position = GeographicPosition(47.265935, -18.0, 0.0),
        url = FAR_NORTH_FIDUCIAL_URL,
        colour = FAR_NORTH_FIDUCIAL,
        bound = CrossModeBound.PARTS,
        limitPixels = 4.0,
    ),
)

/** A globe that is a bent plane parts by ten pixels here; one that is a different size or in a
 * different place parts by far more, and this is what says which of the two happened. */
private const val MAXIMUM_PARTING_DISPLACEMENT_PIXELS: Double = 25.0

/** How far a measured centroid displacement may sit from the one the two `Double` camera paths
 * predict. A 16 x 16 block's centroid is good to about a twentieth of a pixel, so this is two orders
 * of magnitude above the measurement's own precision and two below the quantity measured. */
private const val MAXIMUM_PREDICTION_ERROR_PIXELS: Double = 1.5

/** A 2 x 2 image at scale 8 is a 16 x 16 output-pixel quad, so a fiducial that is not at least this
 * many pixels has been clipped by a frame edge and its centroid is biased. */
private const val MINIMUM_FIDUCIAL_PIXELS: Int = 200

/** Two rasterisations of the identical `SCREEN`-scaled quad may resolve a boundary pixel
 * differently; a quad of a different *size* is the defect, and 16 pixels is one row of one. */
private const val MAXIMUM_FIDUCIAL_PIXEL_DIFFERENCE: Int = 16

/** Mercator `x` of the antimeridian, reached from the east. `unitSphereDirection` wraps it to
 * longitude `-PI`, which is where tile `x = 0`'s west edge is; tile `x = 1`'s east edge arrives at
 * the same point as `+PI`. */
private const val ANTIMERIDIAN_X: Double = 1.0

/** Four Mercator rows down the seam, two north of the equator and two south, none within ten output
 * pixels of the horizontal tile seam at `y = 0.5` and all inside the limb's disc. */
private val ANTIMERIDIAN_SAMPLE_ROWS: List<Double> = listOf(0.42, 0.45, 0.55, 0.58)

/** Output-pixel offsets across the seam. Sampling **across** rather than along it is the whole
 * point: a crack is a column of background between two tiles, and a sample on one side of it proves
 * nothing about the other. */
private val SEAM_OFFSETS_PIXELS: List<Double> = listOf(-2.5, -1.5, -0.5, 0.5, 1.5, 2.5)

/**
 * The disc's continuous area is `PI * 114.52^2` = 41,203 pixels and the ray cast finds **41,180** of
 * them. This band is +/-15% of that, wide enough that a driver's fill rule cannot reach either edge
 * and narrow enough that a fixture edited into covering the whole frame — 65,536 — or nothing fails
 * here rather than silently making every count below meaningless.
 */
private const val MINIMUM_EXPECTED_DISC_PIXELS: Int = 35_000

private const val MAXIMUM_EXPECTED_DISC_PIXELS: Int = 48_000

/** The limb's own disc radius at the fixture camera, `d * R * sin(alpha) / (d + R * (1 - cos
 * alpha))` = 114.52 pixels, rounded up by half a pixel of fill rule. */
private const val MAXIMUM_EXPECTED_DISC_RADIUS: Double = 115.0

/**
 * The disc's own boundary: 720 pixels of circumference, each of which a driver may round either way.
 *
 * **Measured at 66 pixels on `Apple M3 Max` and 55 on `Apple Software Renderer`** — the same five
 * hundredths of a percent `runGlobeGroundReadbackSuite` measures against `drawGlobeGround` alone, so
 * nothing the renderer adds between a `FramePlan` and a pixel moves the silhouette.
 *
 * Anything larger is not a fill-rule difference, and the mutations measured above say so in numbers:
 * a globe drawn as a plane covers the frame's 24,356 empty pixels as well as the disc, and a tile
 * whose antimeridian carry was dropped moves 12,341. A ground that never drew leaves all 41,180.
 */
private const val MAXIMUM_LIMB_DISAGREEMENT: Int = 800

/**
 * What the readback target is cleared to before every draw. Not a fixture tile colour and not a
 * sticker colour, so "still absent" is unambiguous. RenG's own offscreen surface clears to
 * `(0, 0, 0, 0)` and composites with source alpha, so an undrawn frame leaves this standing
 * untouched.
 */
private val ABSENT: IntArray = intArrayOf(0, 96, 32, 255)

/**
 * One opaque colour per LOD-1 tile, in `(tileY, tileX)` order — north-west, north-east, south-west,
 * south-east. Each differs from every other fixture colour by at least 32 in some channel, so "which
 * tile is this pixel" is a comparison rather than an argument.
 *
 * Every tile is a **solid** colour rather than the four-texel decoy arrangement
 * `runBasemapReadbackSuite` uses, and deliberately: the claims here are about *where* ground is
 * drawn, so a sample two pixels from a tile edge must not also be a statement about how Rentile
 * filtered that edge. The u/v convention those decoys exist to pin is already pinned by
 * `runGlobeGroundReadbackSuite`'s own quadrant-texture case.
 */
private val NORTH_WEST_TILE: IntArray = intArrayOf(240, 40, 40, 255)
private val NORTH_EAST_TILE: IntArray = intArrayOf(200, 200, 40, 255)
private val SOUTH_WEST_TILE: IntArray = intArrayOf(40, 40, 200, 255)
private val SOUTH_EAST_TILE: IntArray = intArrayOf(0, 160, 160, 255)

/** Served for every tile outside LOD 1 — the cross-mode case draws at LOD 2. Deliberately not any
 * expected sample value, so a wrong-LOD or wrong-template regression shows up as this colour at a
 * named sample rather than as a plausible one. */
private val FILLER_TILE: IntArray = intArrayOf(96, 32, 160, 255)

private val NEAR_STICKER: IntArray = intArrayOf(255, 0, 255, 255)
private val FAR_STICKER: IntArray = intArrayOf(255, 128, 0, 255)
private val NEAR_EAST_FIDUCIAL: IntArray = intArrayOf(255, 255, 255, 255)
private val FAR_EAST_FIDUCIAL: IntArray = intArrayOf(128, 255, 0, 255)
private val FAR_NORTH_FIDUCIAL: IntArray = intArrayOf(0, 128, 255, 255)

/** The same palette, one character each, for [GlobeFrame.asciiMap]. No glyph means "no fixture
 * colour", which prints as `?` and is itself a finding. */
private val FIXTURE_GLYPHS: List<Pair<Char, IntArray>> = listOf(
    'R' to NORTH_WEST_TILE,
    'Y' to NORTH_EAST_TILE,
    'B' to SOUTH_WEST_TILE,
    'T' to SOUTH_EAST_TILE,
    'P' to FILLER_TILE,
    'M' to NEAR_STICKER,
    'O' to FAR_STICKER,
    'W' to NEAR_EAST_FIDUCIAL,
    'L' to FAR_EAST_FIDUCIAL,
    'A' to FAR_NORTH_FIDUCIAL,
    '.' to ABSENT,
)

private const val GLOBE_FRAME_STYLE_URL: String = "https://styles.example/globe-frame.json"

private const val GLOBE_FRAME_TILE_TEMPLATE: String = "https://tiles.example/g/{z}/{x}/{y}.png"

private const val NEAR_STICKER_URL: String = "https://images.example/globe-near.png"
private const val FAR_STICKER_URL: String = "https://images.example/globe-far.png"
private const val NEAR_EAST_FIDUCIAL_URL: String = "https://images.example/globe-near-east.png"
private const val FAR_EAST_FIDUCIAL_URL: String = "https://images.example/globe-far-east.png"
private const val FAR_NORTH_FIDUCIAL_URL: String = "https://images.example/globe-far-north.png"

/**
 * A style with an opaque background and one raster source, so a rendered tile is exactly its source
 * image and nothing else. No sprite and no text layer: this fixture is about where ground pixels
 * land, and both of those paths are covered elsewhere.
 */
private val GLOBE_FRAME_STYLE_JSON: String =
    """{"version":8,"name":"reng-globe-frame",""" +
        """"sources":{"s":{"type":"raster","tiles":["$GLOBE_FRAME_TILE_TEMPLATE"],"tileSize":512}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#000000"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/**
 * Serves the style, one solid PNG per LOD-1 tile, [FILLER_TILE_PNG] for every other tile inside the
 * fixture's own url template, and the five sticker images.
 *
 * The fallback is scoped to the template's own prefix, so a url RenG composed from the wrong
 * template — a different source, a stale style — still fails closed here rather than being handed a
 * plausible body.
 */
private class GlobeFrameTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        if (url == GLOBE_FRAME_STYLE_URL) {
            return TransportResponse(
                statusCode = 200,
                body = GLOBE_FRAME_STYLE_JSON.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
        }
        val body = STICKER_PNGS[url]
            ?: url.takeIf { it.startsWith(GLOBE_FRAME_TILE_URL_PREFIX) }?.let { tilePng(it) }
            ?: error("the globe frame fixture serves no body for $url")
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "image/png"),
        )
    }
}

/** Everything before `{z}` in [GLOBE_FRAME_TILE_TEMPLATE]; see [GlobeFrameTransport]. */
private val GLOBE_FRAME_TILE_URL_PREFIX: String = GLOBE_FRAME_TILE_TEMPLATE.substringBefore("{z}")

/**
 * The LOD-1 quadrant colours by url, and [FILLER_TILE_PNG] everywhere else. Parsed off the url's own
 * trailing `.../{z}/{x}/{y}.png` rather than from a table, because the cross-mode camera draws at
 * LOD 2 and a table would have to enumerate a set the LOD selector chooses.
 */
private fun tilePng(url: String): ByteArray {
    val segments = url.removeSuffix(".png").split('/')
    val lod = segments[segments.size - 3].toIntOrNull()
    val tileX = segments[segments.size - 2].toIntOrNull()
    val tileY = segments[segments.size - 1].toIntOrNull()
    if (lod != 1 || tileX == null || tileY == null) return FILLER_TILE_PNG
    return QUADRANT_PNGS[tileY * 2 + tileX]
}

// Every PNG below is a real, valid 2x2 truecolour-with-alpha image (colour type 6) whose four texels
// are one opaque colour, generated once via CPython's zlib/struct/zlib.crc32 modules exactly as
// PngDecoderTest.kt documents:
//
//     import zlib, struct, base64
//     def chunk(kind, payload):
//         return (struct.pack(">I", len(payload)) + kind + payload +
//                 struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
//     row = b"\x00" + bytes(rgb) + b"\xff" + bytes(rgb) + b"\xff"
//     png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0)) +
//            chunk(b"IDAT", zlib.compress(row + row, 9)) + chunk(b"IEND", b""))
//
// Nothing on the expected side of any assertion runs decodePng, so a decoder regression can only
// make this suite fail, never pass.

/** [NORTH_WEST_TILE], [NORTH_EAST_TILE], [SOUTH_WEST_TILE], [SOUTH_EAST_TILE], in the order
 * `tileY * 2 + tileX` indexes them. */
private val QUADRANT_PNGS: List<ByteArray> = listOf(
    Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP4oKHxH4QZYAwAUJQI/WpVtS8AAAAASUVORK5CYII="),
    Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mM4cULjPwgzwBgAYcQK3UBtJAUAAAAASUVORK5CYII="),
    Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mPQ0DjxH4QZYAwARQQIXZPoq30AAAAASUVORK5CYII="),
    Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR42mNgWLDgPxjDGABK9Aj9rBMHhgAAAABJRU5ErkJggg=="),
)

private val FILLER_TILE_PNG: ByteArray =
    Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mNIUFjwH4QZYAwAR7QIfVj/5kkAAAAASUVORK5CYII=")

private val STICKER_PNGS: Map<String, ByteArray> = mapOf(
    NEAR_STICKER_URL to
        Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP4z/D/PwgzwBgAaagL9Uu86vkAAAAASUVORK5CYII="),
    FAR_STICKER_URL to
        Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP438DwH4QZYAwAWsoJ+ZJB9b8AAAAASUVORK5CYII="),
    NEAR_EAST_FIDUCIAL_URL to
        Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAADklEQVR42mP4DwUMMAYAj4IP8SHNGCcAAAAASUVORK5CYII="),
    FAR_EAST_FIDUCIAL_URL to
        Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mNo+M/wH4QZYAwAWM4J+WYZlLoAAAAASUVORK5CYII="),
    FAR_NORTH_FIDUCIAL_URL to
        Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR42mNgaPj/H4xhDABS0gn51EcZVQAAAABJRU5ErkJggg=="),
)
