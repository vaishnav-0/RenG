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
import com.rohittp.reng.internal.gl.GROUND_DRAPE_LIFT_METRES
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **Cycle E-terrain task 12's gate: a terrain frame drawn through the *public* API, read back off a
 * real driver, and asserted against relationships between pixels rather than against a stored
 * picture.**
 *
 * **Why a fifth suite rather than more cases inside tasks 8, 9 and 11's.** Those three call
 * `drawGround` and `drawGlobeGround` directly with a hand-built tile list, a hand-composed matrix and
 * a DEM texture the test uploaded itself. Every one of them would go on passing against a build in
 * which no `FramePlan` ever reaches a DEM at all. `RendererTerrainTest` closes half of that already
 * -- it puts a `terrain` style through `createRenderer` and reads ADR 0041's diagnostics off a real
 * `prepare()` -- but it never calls `draw()`, so until this suite existed **no test had put a
 * `FramePlan` over a terrain style in one end and read a displaced ground pixel out of the other**.
 * Acquisition, the `sha256Hex` source-id comparison, the perimeter ring, the overzoom window,
 * decode, the padded upload, the granularity reconciliation and the two ground passes were each
 * proven in isolation, and the sequence stopped one call short of a pixel. That is a milder version
 * of the shape E-labels found twice -- a stage fully built and wired to nothing -- and it is what a
 * gate is for.
 *
 * ## The four cases and what each one alone would survive
 *
 * - [assertAdjacentDisplacedTilesLeaveNoCrackInEitherProjection] -- **the case this cycle most needs
 *   and the only one that reaches the padded border end to end.** Task 5 proved two adjacent tiles
 *   sample bit-identical *heights* at a shared edge, over decoded arrays. Nothing had drawn them.
 *   The fixture's DEM alternates by tile `x` between sea level and a summit, so **every** vertical
 *   tile boundary in the frame is a step the ring has to close, and a ring that fell back to edge
 *   replication opens a band of cleared target between the two tiles wide enough to see from across
 *   the room. Its second half is what stops it passing on a build that displaces nothing at all.
 * - [assertTheGlobeLimbFollowsTheReliefBeneathIt] -- the limb-and-relief case. Relief raises the
 *   silhouette **where the relief is** and nowhere else, which is the claim a per-frame radial scale
 *   cannot make. Its third render is the control that keeps the "nowhere else" half from being
 *   satisfied by a frame in which nothing moves.
 * - [assertAFlatDemDrawsExactlyWhatANoTerrainStyleDraws] -- **the strongest negative available**, at
 *   the level a consumer sees it: a style declaring `terrain` whose every DEM texel decodes to zero
 *   metres must draw what a style declaring no terrain at all draws, in both projections. Task 8
 *   makes that claim about one `drawGround` call against another; this makes it about two
 *   `FramePlan`s, which additionally covers acquisition, the granularity rule and the choice of
 *   ground program -- and the granularity rule is precisely what turned the byte-exact version of
 *   this claim into a **measured 30-pixel exception** under the globe. The case's own KDoc has the
 *   arithmetic, and its second half is what keeps that exception from being a hiding place.
 * - [assertTheTwoProjectionsDisplaceTheSameGroundAndPartByCurvature] -- the cross-mode case,
 *   **bounded in both directions and taken at zoom 4**, for the reason Cycle G's task 12 recorded:
 *   the sagitta of a frame-sized quad is 27.9 logical pixels at zoom 4, 1.75 at zoom 8 and 0.44 at
 *   zoom 10, so above about zoom 12 a globe that is secretly a tangent plane passes any cross-mode
 *   comparison, and passes a one-sided one at every zoom.
 *
 * ## What this suite does NOT claim, stated so nobody reads more into a green run than is there
 *
 * **Neither curvature nor terrain fidelity is claimed here, and both stay Cycle J's** by the owner's
 * gate decision (`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md`
 * lines 204-205): nothing below compares against a stored image, so this suite says nothing about
 * how a terrain frame *looks* -- only about relationships between pixels that hold or do not. It says
 * nothing about whether a mountain is in the right place, whether its slopes have the right
 * gradient, whether the DEM's own resolution is honoured between vertices, or about any regression
 * that preserves both the seam and the two measured silhouettes.
 *
 * **The fixture's exaggeration is 500 and its summit is 1,000 metres, which is 500 kilometres of
 * relief, and that is a measurement decision rather than a plausible landscape.** A real summit is
 * about 8,848 metres against a 6,378-kilometre semi-major axis, which is 0.14 per cent -- a fifth of
 * a pixel on a frame this size, and unmeasurable. Exaggeration is finite and unclamped by decision
 * (design section 10), so scaling the effect into the measurable range is a legal frame rather than a
 * workaround, and `runGroundDisplacementReadback` set the precedent for exactly this reason. It is
 * emphatically **not 1**: all six corpus styles declare `1`, and at `1` an honoured exaggeration and
 * an ignored one draw the identical frame.
 *
 * **No assertion here hands two whole frames to `assertContentEquals`, deliberately.** Over a
 * 768 x 768 RGBA frame that renders both 2,359,296-byte arrays into the failure message; measured
 * during task 11's mutation pass at a quarter of this size, Gradle's Kotlin/Native test listener
 * refuses a service message over 1 MB, **loses the event, and fails the build with
 * `Cannot process output: too long teamcity service message` instead of the assertion** -- a broken
 * build that reports nothing about what broke, in exactly the situation a readable assertion is most
 * needed. Every whole-frame comparison below is an exact per-pixel count over all four channels
 * ([TerrainFrame.differenceFrom]) with a message a human can read, and
 * `runGroundDisplacementReadback`'s four array comparisons now assert that count first for the same
 * reason.
 *
 * **Nothing on the expected side of any assertion runs `decodePng` or `demElevationMetres`.** Every
 * fixture image is a real PNG generated once by CPython's `zlib`/`struct` modules exactly as
 * `PngDecoderTest` documents, and the two DEM triples were computed there too -- `(1, 134, 160)` is
 * Mapbox for 0 m and `(1, 173, 176)` is Mapbox for 1,000 m. A decoder or an encoding regression can
 * only make this suite fail, never pass.
 *
 * ## Measured against three deliberately broken builds rather than reviewed
 *
 * Three deliberately broken builds, each reverted afterwards and the revert confirmed against
 * `git diff`. Every one of them compiled and ran -- a mutation that does not compile is not a caught
 * mutation, and the first attempt at the second of these did not, which is why that sentence is a
 * standing obligation rather than advice.
 *
 * **1. Displacement made a no-op** -- `GROUND_ELEVATION_SOURCE` returning `0.0 * (...)`, so every
 * vertex reads its DEM texel, decodes it and then ignores it. **4 of 4 failed**: the seam case on its
 * widening half (512 pixels against 512, a widening of 0 against a floor of 16), the limb case on its
 * eastern floor (0 pixels of rise), the flat-DEM negative on its *raised* half (0 pixels of
 * difference against a floor of 40,000) and the cross-mode case on both its floors (`+0` in each
 * projection, and a ratio of `NaN`). That the negative fails too is deliberate: its second half exists
 * precisely so that "a flat DEM changes nothing" cannot be satisfied by a build in which nothing
 * changes anything.
 *
 * **2. The padded ring dropped** -- `padDemTexture` recording every neighbour as
 * `DemNeighbourFill.ABSENT`, so each DEM's border is edge replication of its own outermost texel,
 * which is exactly what `GL_CLAMP_TO_EDGE` would have given with no ring at all. **1 of 4 failed, and
 * it is the seam case alone**: 61,063 of 589,824 pixels showed the cleared target, as two bands 39
 * pixels wide running the full height of the frame -- the centre row read
 * `[ODD:89, CLEARED:39, EVEN:512, CLEARED:39, ODD:89]` where a correct build reads
 * `[ODD:128, EVEN:551, ODD:89]`. **This is the useful one**: a mutation caught by every case is weaker
 * evidence than one caught by exactly one, and cases 2, 3 and 4 survive it because their DEMs are
 * uniform over the tiles they measure, so a ring copied from a neighbour and a ring replicated from
 * the tile's own edge carry the identical value.
 *
 * **3. The globe's radial scale made per frame rather than per vertex** -- `drawGlobeGround` handing
 * every tile the *same* `GroundTileDem` instead of its own, which is the shape design section 5 says
 * this cycle changed. **2 of 4 failed**, and which two depended on which tile's DEM the frame
 * collapsed onto, which is itself worth recording: taking the **first** available DEM (sea level
 * here) failed the seam case's globe arm and the limb case's eastern *floor*; taking the **last**
 * (a summit) failed the seam case's globe arm and the limb case's western *ceiling*, by exactly the
 * sentence that assertion is written in -- "moved the western limb by 25 pixels, over a budget of 2".
 * It was run because the western ceiling is the one assertion here that neither of the first two
 * mutations could falsify, and an assertion no mutation can falsify is the definition of the vacuous
 * check this cycle has now found nine of.
 */
internal fun runTerrainFrameReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createTerrainFrameTarget(binding)
    // Printed rather than acted on. Every claim below is either a difference between two frames of
    // the same fixture or a colour-boundary column, and the fixture's ground quads are 512 logical
    // pixels inside a 768-pixel frame -- not the far-outside-the-viewport shape this probe measures.
    // It is here so that a driver which *does* disagree is on the record beside the numbers rather
    // than discovered afterwards.
    val rasterisation = measureLargeQuadRasterisation(binding, dialect, target)
    println(
        "RenG terrain frame readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect " +
            rasterisation.describe(),
    )

    val transport = TerrainFrameTransport()
    val terrainRenderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(TERRAIN_FRAME_READBACK_PIXELS, TERRAIN_FRAME_READBACK_PIXELS),
            transport = transport,
            store = TerrainFrameStore(),
            basemapStyle = ResourceLocator(TERRAIN_STYLE_URL),
        ),
        binding,
        probe,
    )
    val plainRenderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(TERRAIN_FRAME_READBACK_PIXELS, TERRAIN_FRAME_READBACK_PIXELS),
            transport = transport,
            store = TerrainFrameStore(),
            basemapStyle = ResourceLocator(PLAIN_STYLE_URL),
        ),
        binding,
        probe,
    )
    // A third renderer because a style's exaggeration is a property of the style rather than of a
    // frame, and tasks 16 and 17's cases need a *plausible* one: the coplanar fight is a fight about
    // metres against a depth budget that scales with zoom, and 500x relief at zoom 4 puts a drape's
    // four-metre lift eight ten-thousandths of a logical pixel above the ground, which measures
    // nothing at all. See [FINE_TERRAIN_STYLE_JSON].
    val fineRenderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(TERRAIN_FRAME_READBACK_PIXELS, TERRAIN_FRAME_READBACK_PIXELS),
            transport = transport,
            store = TerrainFrameStore(),
            basemapStyle = ResourceLocator(FINE_TERRAIN_STYLE_URL),
        ),
        binding,
        probe,
    )
    val failures = CollectedTerrainFrameFailures()
    try {
        val fixture = TerrainFrameFixture(
            binding = binding,
            transport = transport,
            terrainRenderer = terrainRenderer,
            plainRenderer = plainRenderer,
            fineRenderer = fineRenderer,
            terrainTarget = terrainRenderer.mintRenderTarget(FramebufferName(target.toUInt())),
            plainTarget = plainRenderer.mintRenderTarget(FramebufferName(target.toUInt())),
            fineTarget = fineRenderer.mintRenderTarget(FramebufferName(target.toUInt())),
            targetFramebuffer = target,
        )
        failures.run("adjacent displaced tiles leave no crack") {
            assertAdjacentDisplacedTilesLeaveNoCrackInEitherProjection(fixture)
        }
        failures.run("the globe limb follows the relief beneath it") {
            assertTheGlobeLimbFollowsTheReliefBeneathIt(fixture)
        }
        failures.run("a flat DEM draws what a no-terrain style draws") {
            assertAFlatDemDrawsExactlyWhatANoTerrainStyleDraws(fixture)
        }
        failures.run("the two projections displace the same ground") {
            assertTheTwoProjectionsDisplaceTheSameGroundAndPartByCurvature(fixture)
        }
        failures.run("a ground-relative placement rides the surface") {
            assertAGroundRelativePlacementRidesTheSurfaceInBothProjections(fixture)
        }
        failures.run("a draped geometry follows the ridge") {
            assertADrapedGeometryFollowsTheRidgeRatherThanCuttingThroughIt(fixture)
        }
        failures.run("the coplanar drape survives the ground it rides") {
            assertTheCoplanarDrapeSurvivesTheGroundItRides(fixture)
        }
        failures.run("a globe geometry drapes too") {
            assertAGlobeGeometryDrapesToo(fixture)
        }
        failures.run("no terrain leaves ground-relative content alone") {
            assertWithoutTerrainAGroundRelativeAltitudeIsAbsolute(fixture)
        }
    } finally {
        fineRenderer.close()
        plainRenderer.close()
        terrainRenderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
    failures.throwIfAny()
}

/**
 * Runs every case even after one has failed and reports all of them together -- `CollectedFailures`
 * in `BasemapReadbackSuite` and `CollectedGlobeFrameFailures` in `GlobeFrameReadbackSuite`, for the
 * same reason and with the same cost model.
 *
 * A suite that stops at its first assertion costs one CI round trip per defect, and these run on
 * drivers a developer cannot reach. Each message is printed as it happens, because Gradle's console
 * renders a Kotlin/Native failure's class and location and never its message.
 */
private class CollectedTerrainFrameFailures {
    private val messages: MutableList<String> = mutableListOf()

    fun run(name: String, case: () -> Unit) {
        try {
            case()
        } catch (error: AssertionError) {
            val message = error.message ?: error.toString()
            messages += "[$name] $message"
            println("RenG terrain frame readback FAILED [$name] $message")
        }
    }

    fun throwIfAny() {
        if (messages.isEmpty()) return
        throw AssertionError(
            "${messages.size} of $TERRAIN_FRAME_CASE_COUNT terrain frame readback cases failed:\n" +
                messages.joinToString("\n"),
        )
    }
}

private const val TERRAIN_FRAME_CASE_COUNT: Int = 9

// ---- case 1: the seam ---------------------------------------------------------------------------

/**
 * **The seam case, and the padded border is the only thing standing between it and a hole in the
 * ground.**
 *
 * A DEM grid is edge-*exclusive*: texel `i` is centred at `(i + 0.5) / N`, so a tile boundary sits
 * half a texel outside the outermost texel centre and is described by no texel in either tile. Each
 * DEM is therefore uploaded as `(N + 2)^2` with a one-texel ring copied from its neighbours, so both
 * sides of an edge read **identical source values**. Task 5 asserted that over decoded arrays. This
 * asserts it in pixels, which is the form in which a consumer would meet the defect.
 *
 * ## The fixture is a square wave, and the arithmetic of the crack is worked here rather than read
 * off a run
 *
 * The DEM alternates by tile `x`: even columns of tiles sit at sea level, odd ones at
 * [SUMMIT_METRES] -- 500 kilometres after the style's exaggeration. At [SEAM_ZOOM] the camera sits at
 * the exact centre of tile `x = 8`, so a 768-pixel frame spans one and a half tiles and carries
 * **two** tile boundaries, at 256 output pixels either side of the frame centre.
 *
 * With the ring in place the drawn surface is a continuous zigzag, and the sampling rule is what makes
 * it one: `GROUND_ELEVATION_SOURCE` maps a grid coordinate into the source tile and snaps it to the
 * padded texel *containing* it, so a tile's `u = 0` vertex reads padded texel 1 -- its own first
 * interior column -- while its `u = 1` vertex reads padded texel `N + 1`, its **east ring**, which is
 * a copy of its eastern neighbour's first interior column. The two sides of every boundary therefore
 * read the same source value and the two projected edges land on the same pixel. Measured on
 * `Apple M3 Max`: the centre row of the relief frame reads `odd:134 even:560 odd:74` under the globe
 * and `odd:128 even:551 odd:89` under Mercator, three colour runs and **zero** cleared pixels.
 *
 * Without the ring each tile clamps to its own data, the eastern tile's west edge stands 500
 * kilometres above the western tile's east edge, and the perspective camera projects the two to
 * columns hundreds of pixels apart with the cleared target showing between them.
 *
 * ## Why the second half is not optional
 *
 * "No cleared pixel inside the frame" is satisfied perfectly by a build that displaces **nothing** --
 * a flat ground has no seam to open. That is the "assertion satisfied by an empty frame" shape, one
 * pane over, and it is why the case also asserts that the relief genuinely moved the ground: the
 * middle colour run must be wider than the 512 pixels the flat frame draws, by a margin no fill rule
 * can supply.
 *
 * Both projections, because ADR 0039 and the shared `GROUND_ELEVATION_SOURCE` move the two grounds
 * together and a seam closed in one projection is half a claim.
 */
private fun assertAdjacentDisplacedTilesLeaveNoCrackInEitherProjection(fixture: TerrainFrameFixture) {
    ProjectionMode.entries.forEach { mode ->
        val flat = fixture.renderTerrain(SEAM_CAMERA, mode, DemRelief.SEA_LEVEL)
        val relief = fixture.renderTerrain(SEAM_CAMERA, mode, DemRelief.ALTERNATING_BY_TILE_COLUMN)

        // The flat frame is the control for the crack count twice over: if this camera does not fill
        // its frame with ground even when the ground is a plane, a small crack count below means
        // nothing -- and whatever a driver's fill rule leaves along a tile edge on a plane, it leaves
        // on a displaced one too, so the crack is measured as the *increase* rather than as an
        // absolute. `Apple Software Renderer` leaves 34 pixels of the globe's flat frame cleared;
        // `Apple M3 Max` leaves none.
        val flatCleared = flat.clearedPixels()
        assertTrue(
            flatCleared <= MAXIMUM_FLAT_CLEARED_PIXELS,
            "the $mode fixture must fill its frame with flat ground before a crack count means " +
                "anything; $flatCleared of $TERRAIN_FRAME_PIXEL_COUNT pixels were left cleared, over " +
                "a budget of $MAXIMUM_FLAT_CLEARED_PIXELS",
        )
        val flatRuns = flat.centreRowRuns()
        assertEquals(
            listOf(TerrainInk.ODD_TILE, TerrainInk.EVEN_TILE, TerrainInk.ODD_TILE),
            flatRuns.map { it.ink },
            "the $mode flat frame must show one whole tile between two neighbours, or the fixture is " +
                "not straddling the boundaries this case exists to measure: $flatRuns",
        )

        val crack = relief.clearedPixels() - flatCleared
        println(
            "RenG terrain frame readback: $mode seam flat=$flatRuns relief=${relief.centreRowRuns()} " +
                "cleared ${relief.clearedPixels()} against the flat frame's $flatCleared, crack=$crack",
        )
        assertTrue(
            crack <= MAXIMUM_CRACK_PIXELS,
            "a displaced $mode ground cracked open at a tile boundary: ${relief.clearedPixels()} of " +
                "$TERRAIN_FRAME_PIXEL_COUNT pixels show the cleared target against the flat frame's " +
                "$flatCleared, an increase of $crack over a budget of $MAXIMUM_CRACK_PIXELS. The " +
                "padded ring is what makes two adjacent tiles read the same source value at their " +
                "shared edge; without it the fixture's square-wave DEM puts $SUMMIT_METRES m of " +
                "exaggerated relief between them. Runs: ${relief.centreRowRuns()}",
        )

        val flatCentreRun = flatRuns.single { it.ink == TerrainInk.EVEN_TILE }.length
        val reliefCentreRun = relief.centreRowRuns().filter { it.ink == TerrainInk.EVEN_TILE }
            .maxOfOrNull { it.length } ?: 0
        assertTrue(
            reliefCentreRun - flatCentreRun >= MINIMUM_SEAM_WIDENING_PIXELS,
            "the $mode relief frame's centre tile spans $reliefCentreRun pixels against the flat " +
                "frame's $flatCentreRun, a widening of ${reliefCentreRun - flatCentreRun} against a " +
                "floor of $MINIMUM_SEAM_WIDENING_PIXELS. Without this half the crack budget above is " +
                "satisfied by a ground that never displaced at all.",
        )
    }
}

// ---- case 2: the limb ---------------------------------------------------------------------------

/**
 * **The limb-and-relief case: a globe's silhouette rises where the relief is, and only there.**
 *
 * At [LIMB_ZOOM] the whole sphere is inside the frame, so its limb is a measurable curve rather than
 * an argument, and the fixture raises **one band of longitude** -- the eastern half of the world --
 * to [SUMMIT_METRES]. Displacement on a globe is radial, so that band's surface stands 500 kilometres
 * further out and its silhouette does too, while the western limb, over sea-level tiles, must not
 * move at all.
 *
 * Measured on `Apple M3 Max`, as columns of cleared target at the ends of the frame's centre row:
 *
 * | DEM | west limb | east limb |
 * |---|---:|---:|
 * | sea level everywhere | 95 | 95 |
 * | eastern half raised | **95** | **70** |
 * | raised everywhere | 70 | 70 |
 *
 * **The third row is the whole reason the second row's west column is worth asserting.** "The west
 * limb did not move" is satisfied exactly by a build in which nothing ever moves, which is the
 * vacuous shape this cycle has now found nine times; the uniform render is the control that shows the
 * same measurement moving by 25 pixels when the tiles beneath it *are* raised. Without it this case
 * would be a floor and a tautology rather than a floor and a ceiling.
 *
 * **A per-frame radial scale is what the east-versus-west pair discriminates.** Before this cycle the
 * globe's radius was folded into `composeGlobeGroundUnitSphereToClip` as one uniform scale precisely
 * so the vertex shader could emit a unit direction; displacement is that scale becoming per vertex.
 * A build that kept it per frame -- taking, say, the mean elevation -- would inflate the whole sphere
 * and move **both** limbs, failing the west ceiling while sailing through the east floor. That is not
 * a hypothetical: mutation 3 in the suite KDoc is exactly it, and it measured **25** pixels of west
 * limb movement against this case's budget of 2.
 *
 * The corners are asserted cleared as well, because every number above is a distance from a frame
 * edge and would be meaningless if the disc had grown to fill the frame.
 */
private fun assertTheGlobeLimbFollowsTheReliefBeneathIt(fixture: TerrainFrameFixture) {
    val flat = fixture.renderTerrain(LIMB_CAMERA, ProjectionMode.GLOBE, DemRelief.SEA_LEVEL)
    val eastern = fixture.renderTerrain(LIMB_CAMERA, ProjectionMode.GLOBE, DemRelief.EASTERN_HALF)
    val everywhere = fixture.renderTerrain(LIMB_CAMERA, ProjectionMode.GLOBE, DemRelief.SUMMIT)

    listOf("flat" to flat, "eastern" to eastern, "everywhere" to everywhere).forEach { (name, frame) ->
        assertTrue(
            frame.cornersAreCleared(),
            "the $name globe frame must leave its four corners cleared, or its limb is off screen " +
                "and every column measured below is the frame's edge rather than the sphere's",
        )
    }

    val flatLimbs = flat.centreRowLimbColumns()
    val easternLimbs = eastern.centreRowLimbColumns()
    val everywhereLimbs = everywhere.centreRowLimbColumns()
    println(
        "RenG terrain frame readback: globe limb flat=$flatLimbs eastern=$easternLimbs " +
            "everywhere=$everywhereLimbs",
    )

    val easternRose = flatLimbs.second - easternLimbs.second
    val westernRose = flatLimbs.first - easternLimbs.first
    val controlRose = flatLimbs.first - everywhereLimbs.first
    assertTrue(
        easternRose >= MINIMUM_LIMB_RISE_PIXELS,
        "raising the eastern half of the world moved the globe's eastern limb by only " +
            "$easternRose pixels, against a floor of $MINIMUM_LIMB_RISE_PIXELS: the ground is not " +
            "displacing radially at the limb (flat=${flatLimbs.second} eastern=${easternLimbs.second})",
    )
    assertTrue(
        controlRose >= MINIMUM_LIMB_RISE_PIXELS,
        "the control render must move the *western* limb by at least $MINIMUM_LIMB_RISE_PIXELS " +
            "pixels when every tile is raised, or the western ceiling below is satisfied by a " +
            "measurement that cannot move; measured $controlRose",
    )
    assertTrue(
        abs(westernRose) <= MAXIMUM_UNRAISED_LIMB_MOVEMENT_PIXELS,
        "raising only the eastern half of the world moved the *western* limb by $westernRose " +
            "pixels, over a budget of $MAXIMUM_UNRAISED_LIMB_MOVEMENT_PIXELS: the radial scale is " +
            "being applied per frame rather than per vertex, so relief on one side of the sphere " +
            "inflates the other. The same measurement moves $controlRose pixels when the western " +
            "tiles genuinely are raised.",
    )
}

// ---- case 3: the flat-DEM negative ---------------------------------------------------------------

/**
 * **The strongest negative available, at the level a consumer meets it -- and the case that turned
 * out to have a measured exception under one of the two projections.**
 *
 * A style declaring `terrain` whose every DEM texel decodes to exactly zero metres must draw what a
 * style declaring no terrain at all draws. If it does not, displacement is leaking somewhere it
 * should not, and no positive test in this cycle would say so: every one of them measures a
 * *difference* between two frames and would report the same difference against a uniformly wrong
 * baseline.
 *
 * **Zero metres, not "some constant height".** A uniform non-zero DEM is a plane at that altitude,
 * which a perspective camera draws larger than the plane at sea level -- that is displacement
 * working, not leaking, and case 4 measures exactly it.
 *
 * **Under Mercator it is byte-for-byte on a GPU -- 0 of 589,824 pixels -- and it is still asserted as
 * the same budget the globe arm uses, because a CPU rasteriser has already been measured breaking the
 * equality by one pixel.** Declaring terrain subdivides a ground tile into 64 cells a side where a
 * frame with none draws a single quad; `Apple Software Renderer` measured **0** through CGL and
 * **1** through the iOS simulator's EAGL, both times at a tile boundary. Subdividing a plane is the
 * identity in arithmetic and a fill rule's business in practice, and an equality a driver can break
 * by one pixel is a flake rather than a stronger claim. [MINIMUM_RAISED_DIFFERENCE_PIXELS] is what
 * keeps the budget honest.
 *
 * ## Under the globe the difference is real, 30 pixels of it, and it is the granularity rule rather
 * than a defect
 *
 * `terrainCellsPerTileSide` is deliberately a **work budget rather than an error bound**: RenG does
 * not decode DEM texels on the path that chooses a granularity, so a *flat* DEM asks for the same
 * subdivision a mountain range would -- 64 cells a side here, from the fixture's 64-texel DEM. A
 * frame with no terrain at all asks for 1 under Mercator and, at this camera, **8** from the globe's
 * curvature rule. `groundCellsPerTileSide` reconciles the two claims with `max`, so declaring terrain
 * takes this globe frame from 8 cells a side to 64.
 *
 * Subdividing a **plane** more finely is exactly the identity, which is why Mercator measures zero
 * and why `GeometrySubdivisionReadbackSuite` can assert that at all. Subdividing a **sphere** more
 * finely is not: 8 cells already sits inside `GLOBE_GROUND_DEVIATION_TOLERANCE_LOGICAL_PIXELS`, so
 * the two silhouettes agree to well under a pixel, and 30 pixels of 589,824 -- 0.005 per cent, all of
 * them on the frame's outer edge where the sphere bends most -- is where that sub-pixel disagreement
 * lands on an integer grid.
 *
 * **So both arms assert a budget, and state the signal against it rather than merely being
 * nonzero.** The same comparison against a DEM at [SUMMIT_METRES] differs by 61,063 pixels under
 * Mercator and 92,032 under the globe, and the case requires that floor explicitly: a budget that
 * could absorb the defect it is meant to catch is not a budget, and this cycle has now written that
 * sentence three times.
 *
 * **Why this is not `assertAFlatDemDrawsExactlyWhatTerrainOffDraws` again.** That case compares one
 * `drawGround` call against another with the same tile list, the same matrix and **the same
 * granularity**, so it isolates the vertex shader -- and the granularity it holds fixed is precisely
 * the thing this case just found moving. This compares two `FramePlan`s through two renderers over
 * two styles, so it additionally covers acquisition running at all, the granularity rule, and the
 * frame picking the ground program it picks.
 */
private fun assertAFlatDemDrawsExactlyWhatANoTerrainStyleDraws(fixture: TerrainFrameFixture) {
    val mercatorPlain = fixture.renderPlain(SEAM_CAMERA, ProjectionMode.MERCATOR)
    val mercatorFlat = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.MERCATOR, DemRelief.SEA_LEVEL)
    assertTrue(
        mercatorPlain.paintedPixels() >= MINIMUM_PAINTED_PIXELS,
        "the no-terrain MERCATOR frame drew only ${mercatorPlain.paintedPixels()} pixels, so this " +
            "case is comparing two empty frames; expected at least $MINIMUM_PAINTED_PIXELS",
    )
    val mercatorRaised = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.MERCATOR, DemRelief.SUMMIT)
    assertFlatDemLeavesTheFrameAlone(
        mode = ProjectionMode.MERCATOR,
        plain = mercatorPlain,
        flat = mercatorFlat,
        raised = mercatorRaised,
    )

    val globePlain = fixture.renderPlain(SEAM_CAMERA, ProjectionMode.GLOBE)
    val globeFlat = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.GLOBE, DemRelief.SEA_LEVEL)
    val globeRaised = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.GLOBE, DemRelief.SUMMIT)
    assertTrue(
        globePlain.paintedPixels() >= MINIMUM_PAINTED_PIXELS,
        "the no-terrain GLOBE frame drew only ${globePlain.paintedPixels()} pixels, so this case is " +
            "comparing two empty frames; expected at least $MINIMUM_PAINTED_PIXELS",
    )
    assertFlatDemLeavesTheFrameAlone(
        mode = ProjectionMode.GLOBE,
        plain = globePlain,
        flat = globeFlat,
        raised = globeRaised,
    )
}

/**
 * A flat DEM must leave the frame where a no-terrain style left it, to within the pixels a **finer
 * subdivision of the same surface** can legitimately move -- and the raised DEM in the same
 * projection is what states that budget against the signal it must not absorb.
 *
 * Measured, as `plain` against `flat` and `plain` against `raised`:
 *
 * | driver | Mercator flat | Mercator raised | globe flat | globe raised |
 * |---|---:|---:|---:|---:|
 * | `Apple M3 Max` | **0** | 61,063 | **30** | 92,032 |
 * | `Apple Software Renderer` (macOS, CGL) | **0** | 61,750 | **242** | 92,846 |
 * | `Apple Software Renderer` (iOS simulator, EAGL) | **1** | 61,750 | **242** | 92,846 |
 *
 * Three drivers agree that a flat DEM changes at most one Mercator pixel and a couple of hundred on a
 * sphere, against a signal three orders of magnitude larger. The gap between 30 and 242 is the
 * rasteriser's rather than RenG's: it is the same two silhouettes, an eighth of a pixel apart,
 * landing on integer pixels differently. The single Mercator pixel is the reason this arm is a budget
 * rather than an equality.
 */
private fun assertFlatDemLeavesTheFrameAlone(
    mode: ProjectionMode,
    plain: TerrainFrame,
    flat: TerrainFrame,
    raised: TerrainFrame,
) {
    val flatDifference = plain.differenceFrom(flat)
    val raisedDifference = plain.differenceFrom(raised)
    println(
        "RenG terrain frame readback: $mode flat-DEM difference $flatDifference px, raised-DEM " +
            "difference $raisedDifference px of $TERRAIN_FRAME_PIXEL_COUNT",
    )
    assertTrue(
        flatDifference <= MAXIMUM_SUBDIVISION_DIFFERENCE_PIXELS,
        "a $mode style declaring terrain whose every DEM texel decodes to zero metres differed from " +
            "a no-terrain style in $flatDifference of $TERRAIN_FRAME_PIXEL_COUNT pixels, over a " +
            "budget of $MAXIMUM_SUBDIVISION_DIFFERENCE_PIXELS. Declaring terrain subdivides this " +
            "frame's ground into 64 cells a side where a frame with none draws 1 under Mercator and " +
            "8 from the globe's curvature rule; anything above this budget is displacement leaking " +
            "into a frame with no relief in it. First difference at ${plain.firstDifferenceFrom(flat)}",
    )
    assertTrue(
        raisedDifference >= MINIMUM_RAISED_DIFFERENCE_PIXELS,
        "the budget above has to be stated against the signal it must not absorb: a $mode DEM at " +
            "$SUMMIT_METRES m differed from the no-terrain frame in only $raisedDifference pixels, " +
            "under a floor of $MINIMUM_RAISED_DIFFERENCE_PIXELS -- which is " +
            "${MINIMUM_RAISED_DIFFERENCE_PIXELS / MAXIMUM_SUBDIVISION_DIFFERENCE_PIXELS} times the " +
            "flat budget, so no build can satisfy both by accident",
    )
}

// ---- case 4: the two projections -----------------------------------------------------------------

/**
 * **The cross-mode case, bounded in both directions and taken at zoom 4.**
 *
 * Cycle G's task 12 recorded the number that makes a one-sided cross-mode comparison worthless: the
 * sagitta of a frame-sized quad is 27.9 logical pixels at zoom 4, 1.75 at zoom 8 and **0.44 at zoom
 * 10**, so a globe that is secretly a tangent plane passes any "the two modes are close" comparison
 * above about zoom 12 -- and passes it at *every* zoom when only an upper bound is asserted.
 * Everything below is measured at [SEAM_ZOOM], where the two modes are tens of pixels apart.
 *
 * **The instrument is the tile boundary's distance from the frame centre**, measured on the centre
 * row, with a uniform DEM. It is an integer column count needing no fill-rule tolerance, and the
 * fixture's camera sits at the exact centre of a tile so that the boundary is the same geographic
 * meridian in both modes.
 *
 * Measured on `Apple M3 Max`:
 *
 * | | flat | raised 500 km | displacement |
 * |---|---:|---:|---:|
 * | Mercator | 256 | 295 | **+39** |
 * | globe | 250 | 310 | **+60** |
 *
 * Four bounds come out of that table, and each one kills a different build:
 *
 * 1. **A floor on each mode's displacement.** A build that displaces nothing measures `+0` twice.
 * 2. **A floor on the two flat radii parting.** 256 against 250 is the zoom-4 curvature signature;
 *    a tangent-plane globe puts the same meridian in the same column and measures `0`, which is what
 *    makes bound 3 attributable to curvature at all.
 * 3. **A floor on the globe displacing *more* than Mercator.** This is not a defect: on a plane the
 *    lift is entirely toward the camera, while on a sphere the radial lift at screen radius `r` also
 *    carries a component *across* the view axis, and to first order the ratio is
 *    `1 + cameraDistance / globeRadius` -- 1.59 from RenG's own resolved camera at this zoom against
 *    a measured 1.54, and exactly **1** for a tangent plane. Both operands are read off
 *    [ResolvedGlobeCamera] rather than written down, so a change to either moves the prediction.
 * 4. **A ceiling on the same ratio**, because a globe that applied Mercator's `1 / cos(latitude)` to
 *    its metres -- 1.206 at this fixture's latitude 34, and exactly 1 at the equator where a fixture
 *    naturally gets written -- measures about 2.7 here.
 *
 * **What it deliberately does not assert.** That the two modes displace the ground by the *same
 * number of screen pixels*. They must not: rows 1 and 2 of the table differ by design, and a suite
 * asserting otherwise would be asserting the globe is a plane. The claim that the two projections
 * take the same *height* in metres from the same DEM belongs to the arithmetic, and
 * `runGroundDisplacementReadback`'s globe case is where it is measured -- it predicts the radial
 * fraction from `globeMetresToLogicalPixels` and would fail if the globe borrowed Mercator's
 * latitude term.
 */
private fun assertTheTwoProjectionsDisplaceTheSameGroundAndPartByCurvature(fixture: TerrainFrameFixture) {
    val mercatorFlat = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.MERCATOR, DemRelief.SEA_LEVEL)
    val mercatorRaised = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.MERCATOR, DemRelief.SUMMIT)
    val globeFlat = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.GLOBE, DemRelief.SEA_LEVEL)
    val globeRaised = fixture.renderTerrain(SEAM_CAMERA, ProjectionMode.GLOBE, DemRelief.SUMMIT)

    val mercatorFlatRadius = mercatorFlat.centreTileBoundaryRadius()
    val mercatorRaisedRadius = mercatorRaised.centreTileBoundaryRadius()
    val globeFlatRadius = globeFlat.centreTileBoundaryRadius()
    val globeRaisedRadius = globeRaised.centreTileBoundaryRadius()
    val mercatorDisplacement = mercatorRaisedRadius - mercatorFlatRadius
    val globeDisplacement = globeRaisedRadius - globeFlatRadius

    val camera = fixture.globeCamera(SEAM_CAMERA)
    val predictedRatio = 1.0 + camera.cameraDistanceLogicalPixels / camera.radiusLogicalPixels
    println(
        "RenG terrain frame readback: cross-mode mercator $mercatorFlatRadius -> " +
            "$mercatorRaisedRadius (+$mercatorDisplacement) globe $globeFlatRadius -> " +
            "$globeRaisedRadius (+$globeDisplacement), ratio " +
            "${globeDisplacement.toDouble() / mercatorDisplacement} against a first-order " +
            "prediction of $predictedRatio",
    )

    assertTrue(
        mercatorDisplacement >= MINIMUM_CROSS_MODE_DISPLACEMENT_PIXELS &&
            globeDisplacement >= MINIMUM_CROSS_MODE_DISPLACEMENT_PIXELS,
        "a uniform ${SUMMIT_METRES} m DEM must push the tile boundary outward in both projections: " +
            "mercator moved $mercatorDisplacement and globe $globeDisplacement against a floor of " +
            "$MINIMUM_CROSS_MODE_DISPLACEMENT_PIXELS. A build that displaces nothing measures zero twice.",
    )
    val parting = mercatorFlatRadius - globeFlatRadius
    assertTrue(
        parting in MINIMUM_FLAT_PARTING_PIXELS..MAXIMUM_FLAT_PARTING_PIXELS,
        "at zoom ${SEAM_ZOOM} the same meridian must land $MINIMUM_FLAT_PARTING_PIXELS to " +
            "$MAXIMUM_FLAT_PARTING_PIXELS pixels further out under Mercator than under the globe; " +
            "measured $parting (mercator $mercatorFlatRadius, globe $globeFlatRadius). A globe that " +
            "is secretly a tangent plane measures 0 here and passes every one-sided cross-mode " +
            "comparison ever written.",
    )
    assertTrue(
        globeDisplacement - mercatorDisplacement >= MINIMUM_CURVATURE_MARGIN_PIXELS,
        "the globe must displace the boundary further than Mercator does, because a radial lift at " +
            "screen radius r carries a component across the view axis that a plane's lift does not: " +
            "measured $globeDisplacement against $mercatorDisplacement, a margin of " +
            "${globeDisplacement - mercatorDisplacement} against a floor of " +
            "$MINIMUM_CURVATURE_MARGIN_PIXELS. A tangent-plane globe measures a margin of zero.",
    )
    val ratio = globeDisplacement.toDouble() / mercatorDisplacement
    assertTrue(
        ratio <= predictedRatio * MAXIMUM_RATIO_OVERSHOOT,
        "the globe displaced the boundary $ratio times as far as Mercator did, over a ceiling of " +
            "${predictedRatio * MAXIMUM_RATIO_OVERSHOOT} -- the first-order prediction " +
            "1 + cameraDistance/globeRadius = $predictedRatio, allowing " +
            "${MAXIMUM_RATIO_OVERSHOOT}x. A globe that took Mercator's 1/cos(latitude) into its " +
            "metres measures about 2.7 here at latitude ${SEAM_CAMERA.latitude}.",
    )
}

// ---- the fixture ---------------------------------------------------------------------------------

/** Which DEM the transport serves for a given tile, for the frame about to be drawn. */
private enum class DemRelief {
    /** Every tile at sea level: the negative, and every case's control. */
    SEA_LEVEL,

    /** Every tile at [SUMMIT_METRES]: a plane lifted, with no seam anywhere. */
    SUMMIT,

    /**
     * A north-south triangular ridge **inside** every tile: [DEM_RIDGE_PNG]'s
     * `1000 * (1 - |2 * (x + 0.5) / 64 - 1|)` metres, peaking at the tile's own centre column and
     * falling to 15.6 m at both edges.
     *
     * **Relief within a tile rather than between tiles**, which is what tasks 16 and 17 need and what
     * no relief here had: a ground cell's four corners are four *different* heights, so the surface a
     * CPU lookup has to reconstruct is genuinely a surface, and a flat quad laid across it is
     * genuinely cutting through a ridge. [SUMMIT] and [ALTERNATING_BY_TILE_COLUMN] are uniform inside
     * a tile, which is exactly the symmetry point at which reading a cell's corners and reading the
     * texel under the point are the same answer.
     *
     * It is periodic with one tile and its two edge columns agree, so the padded ring joins one tile
     * to the next at one height and the ridge repeats seamlessly across the frame.
     */
    RIDGE,

    /**
     * Sea level on even tile columns, [SUMMIT_METRES] on odd ones, so that **every** vertical tile
     * boundary in the frame is a step the padded ring has to close.
     */
    ALTERNATING_BY_TILE_COLUMN,

    /**
     * [SUMMIT_METRES] on the eastern **half** of the world and sea level on the western half -- one
     * band of longitude, which is what makes the globe's limb rise on one side and not the other.
     *
     * A half rather than a quarter, and that is a robustness decision taken from a measurement. With
     * the eastern *quarter* raised the boundary at longitude 90 sits within ten degrees of the
     * eastern limb, and the elevation that reached the limb turned out to arrive through the padded
     * **ring** -- the visible tile's own last column being a copy of its raised neighbour's first.
     * The case still worked and mutation 2 caught it, but a claim about a limb that rests on one ring
     * texel is a claim one granularity change away from measuring something else. The boundary is at
     * longitude 0 now, which is 78 degrees from the western limb and 100 from the eastern.
     */
    EASTERN_HALF,
    ;

    fun demFor(lod: Int, tileX: Int): ByteArray = when (this) {
        SEA_LEVEL -> DEM_SEA_LEVEL_PNG
        SUMMIT -> DEM_SUMMIT_PNG
        RIDGE -> DEM_RIDGE_PNG
        ALTERNATING_BY_TILE_COLUMN -> if (tileX % 2 == 0) DEM_SEA_LEVEL_PNG else DEM_SUMMIT_PNG
        EASTERN_HALF -> if (tileX * 2 >= (1 shl lod)) DEM_SUMMIT_PNG else DEM_SEA_LEVEL_PNG
    }
}

// ---- case 5: a ground-relative placement rides the drawn surface ---------------------------------

/**
 * **ADR 0040's `GROUND_RELATIVE`, in pixels, through the public API, in both projections.** A sticker
 * declaring altitude zero over a plateau stands *on* the plateau; the same sticker declaring
 * `ABSOLUTE` zero stands at sea level and the terrain buries it.
 *
 * ## The known height is an identity rather than a measurement
 *
 * The fixture's DEM is uniform 1,000 m and the style exaggerates by 500, so the surface the ground
 * draws is **exactly** [DRAWN_SUMMIT_METRES] everywhere -- a terminating value both the shader and the
 * CPU lookup reach without rounding. So "sits on the surface at a known height" is asserted as
 * `GROUND_RELATIVE 0` reading back **byte for byte** identical to `ABSOLUTE 500000`, which is a far
 * stronger statement than a pixel-position measurement and needs no budget.
 *
 * ## What each of the three frames stops the others being satisfied by
 *
 * The sea-level frame is the sensitivity control: without it, a build that resolved `GROUND_RELATIVE`
 * as `ABSOLUTE` -- ADR 0040's own degradation, and the single most likely way this can be wrong --
 * would satisfy the identity by making all three frames the same. The painted-pixel floor is the
 * vacuity control: three frames in which the sticker never drew are also byte-identical.
 *
 * **A uniform DEM says nothing about the reconstruction rule and is not asked to.** Whether the lookup
 * reads a cell's corners or the texel underneath is invisible on a plateau -- that is
 * `GroundSurfaceTest`'s subject, and the relief cases below are where it reaches a pixel.
 */
private fun assertAGroundRelativePlacementRidesTheSurfaceInBothProjections(fixture: TerrainFrameFixture) {
    ProjectionMode.entries.forEach { mode ->
        val camera = if (mode == ProjectionMode.MERCATOR) ANCHOR_CAMERA else LIMB_CAMERA
        val atSeaLevel = fixture.renderTerrain(
            camera, mode, DemRelief.SUMMIT,
            stickers = listOf(anchorSticker(camera, AltitudeMode.ABSOLUTE, 0.0)),
        )
        val onTheGround = fixture.renderTerrain(
            camera, mode, DemRelief.SUMMIT,
            stickers = listOf(anchorSticker(camera, AltitudeMode.GROUND_RELATIVE, 0.0)),
        )
        val atTheKnownHeight = fixture.renderTerrain(
            camera, mode, DemRelief.SUMMIT,
            stickers = listOf(anchorSticker(camera, AltitudeMode.ABSOLUTE, DRAWN_SUMMIT_METRES)),
        )

        val riding = onTheGround.pixelsOf(TerrainInk.STICKER)
        val buried = atSeaLevel.pixelsOf(TerrainInk.STICKER)
        println(
            "RenG terrain frame readback: $mode ground-relative sticker paints $riding pixels, " +
                "the same sticker at absolute zero paints $buried, and it differs from the " +
                "sea-level frame over ${onTheGround.differenceFrom(atSeaLevel)} pixels",
        )

        assertTrue(
            riding >= MINIMUM_STICKER_PIXELS,
            "a $mode sticker riding the terrain must actually draw; it painted $riding pixels",
        )
        assertTrue(
            buried <= MAXIMUM_BURIED_STICKER_PIXELS,
            "a $mode sticker at absolute zero stands under half a million metres of terrain and " +
                "must be occluded by it; it painted $buried pixels",
        )
        assertEquals(
            0,
            onTheGround.differenceFrom(atTheKnownHeight),
            "$mode: a ground-relative zero over a $DRAWN_SUMMIT_METRES m surface must read back " +
                "byte for byte as an absolute $DRAWN_SUMMIT_METRES",
        )
        assertTrue(
            onTheGround.differenceFrom(atSeaLevel) >= MINIMUM_STICKER_PIXELS,
            "$mode: resolving GROUND_RELATIVE as ABSOLUTE would make these two frames one",
        )
    }
}

// ---- case 6: a draped geometry follows the relief ------------------------------------------------

/**
 * **A `GROUND_RELATIVE` `Geometry` drapes across every subdivided vertex, not just its corners.**
 *
 * ADR 0040 says that in as many words, and the difference between draping and not is the difference
 * between a quad over a ridge and a quad through one. The fixture's ridge stands 220 logical pixels
 * at its summit and 115 at the quad's edges, so a flat quad at the ridge's own mean height
 * ([MEAN_DRAWN_RIDGE_METRES]) is above the terrain on the flanks and below it across the middle --
 * which is exactly "cutting through", and is what the ground's depth writes (ADR 0039) make visible.
 *
 * Three frames, and each of the other two is why the first means something:
 *
 * - **draped** must paint essentially its whole footprint, because it is *on* the surface everywhere.
 * - **flat at the mean** must paint substantially less, because the ridge buries its middle. A build
 *   that ignored the altitude mode entirely would draw this frame for the draped one too.
 * - **flat at zero** must paint almost nothing, because the whole ridge stands over it. Without it,
 *   "the ridge buries things" would be an assumption rather than a measurement, and a build whose
 *   ground wrote no depth at all would pass the first two.
 */
private fun assertADrapedGeometryFollowsTheRidgeRatherThanCuttingThroughIt(fixture: TerrainFrameFixture) {
    val draped = fixture.renderFine(
        RIDGE_CAMERA, ProjectionMode.MERCATOR, DemRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, 0.0)),
    )
    val throughTheRidge = fixture.renderFine(
        RIDGE_CAMERA, ProjectionMode.MERCATOR, DemRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.ABSOLUTE, MEAN_DRAWN_RIDGE_METRES)),
    )
    val underTheRidge = fixture.renderFine(
        RIDGE_CAMERA, ProjectionMode.MERCATOR, DemRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.ABSOLUTE, 0.0)),
    )

    val drapedInk = draped.pixelsOf(TerrainInk.DRAPE)
    val cuttingInk = throughTheRidge.pixelsOf(TerrainInk.DRAPE)
    val buriedInk = underTheRidge.pixelsOf(TerrainInk.DRAPE)
    println(
        "RenG terrain frame readback: draped geometry paints $drapedInk pixels, the same quad flat " +
            "at ${MEAN_DRAWN_RIDGE_METRES.toInt()} m paints $cuttingInk, and flat at sea level $buriedInk",
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
        "a flat quad at sea level stands under the whole ridge and must be buried by it; it " +
            "painted $buriedInk pixels",
    )
}

// ---- case 7: the coplanar drape, counted the way the spike counted it ----------------------------

/**
 * **The one genuinely coplanar case in RenG, and the gate on `GROUND_DRAPE_LIFT_METRES`.**
 *
 * A `GROUND_RELATIVE` `Geometry` at altitude zero is a second copy of the terrain surface drawn
 * through a different pipeline against a ground that now writes depth (ADR 0039), which is ADR 0027's
 * original defect in new material: half the quad deleted, and redealt every frame as the camera moves.
 * Task 14's spike measured every way out of it and ADR 0039's 2026-08-30 erratum records the verdict
 * -- a shared texel rule fixes the wrong term, drawing the drape first keeps **zero** pixels, and a
 * lift works.
 *
 * ## It is counted the spike's way, because the spike's way is what caught its own fixture being wrong
 *
 * **Contested** is the set of pixels the content paints when it wins *and* the ground paints when the
 * content is absent; **survivors** are the contested pixels the coplanar drape still holds. Counting
 * only "how much magenta is there" would be satisfied by a drape that had drifted off the ground
 * altogether, and counting only "the drape covers the ground" would be satisfied by a drape that won
 * because it was nowhere near it.
 *
 * The raised frame supplies the contested set at [DRAPE_CONTROL_METRES], which is the spike's own
 * positive control at this fixture's scale: high enough to leave the fight outright, low enough that
 * its footprint is the drape's to within a pixel of fringe. The sunk frame is the negative control and
 * the reason a survivor count means anything at all -- if the ground could not delete this quad, the
 * survivor count would be 100 per cent against every build, including one with no lift and no lookup.
 *
 * ## The number
 *
 * The spike measured **102,370 of 102,400** for a four-metre lift on the offset lattice at zoom 13,
 * pitch 0, on `Apple M3 Max`. [MINIMUM_SURVIVOR_PERCENT] is stated against that rather than against
 * this fixture's own reading, and the measured reading is printed beside it every run.
 *
 * ## What this case cannot see, measured rather than assumed
 *
 * **The drape's triangulation.** [DEM_RIDGE_PNG]'s relief varies with longitude alone, so inside any
 * ground cell the north-west and south-west corners carry one height and the north-east and south-east
 * corners carry another -- and a cell whose corners satisfy `NW + SE = NE + SW` describes the *same*
 * surface under either diagonal. Reverting the drape to the geometry grid's historical fold measured
 * **579,744 against 579,754** here, which is nothing. `GeometryGridTest` is where that claim lives, as
 * an agreement with `groundGridIndices` rather than as a pixel count.
 *
 * **One camera.** The spike swept five pitch-and-bearing pairs and found the residual varying by a
 * factor of two across them; this asserts pitch 0, which is where its own lift ladder was measured.
 */
private fun assertTheCoplanarDrapeSurvivesTheGroundItRides(fixture: TerrainFrameFixture) {
    val groundOnly = fixture.renderFine(RIDGE_CAMERA, ProjectionMode.MERCATOR, DemRelief.RIDGE)
    val raised = fixture.renderFine(
        RIDGE_CAMERA, ProjectionMode.MERCATOR, DemRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, DRAPE_CONTROL_METRES)),
    )
    val coplanar = fixture.renderFine(
        RIDGE_CAMERA, ProjectionMode.MERCATOR, DemRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, 0.0)),
    )
    val sunk = fixture.renderFine(
        RIDGE_CAMERA, ProjectionMode.MERCATOR, DemRelief.RIDGE,
        geometries = listOf(ridgeGeometry(AltitudeMode.GROUND_RELATIVE, -DRAPE_CONTROL_METRES)),
    )

    val content = raised.maskOf(TerrainInk.DRAPE)
    val ground = groundOnly.groundMask()
    var contested = 0
    var survivors = 0
    var sunkSurvivors = 0
    for (index in content.indices) {
        if (!content[index] || !ground[index]) continue
        contested += 1
        if (coplanar.inkAtIndex(index) == TerrainInk.DRAPE) survivors += 1
        if (sunk.inkAtIndex(index) == TerrainInk.DRAPE) sunkSurvivors += 1
    }
    println(
        "RenG coplanar drape readback: survivors $survivors / $contested contested " +
            "(sunk control $sunkSurvivors), lift ${GROUND_DRAPE_LIFT_METRES.toInt()} m",
    )

    assertTrue(
        contested >= MINIMUM_CONTESTED_PIXELS,
        "the fixture must put the drape and the ground over the same $MINIMUM_CONTESTED_PIXELS " +
            "pixels before a survivor count means anything; it contested $contested",
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

// ---- case 8: no terrain, no change ---------------------------------------------------------------

/**
 * **ADR 0040's promise that nothing already shipped moves**, at the level a consumer meets it and in
 * both projections: against a style declaring no terrain, a `GROUND_RELATIVE` sticker and a
 * `GROUND_RELATIVE` `Geometry` must read back byte for byte as `ABSOLUTE` ones.
 *
 * `runGeometrySubdivisionReadbackSuite` already makes this claim for a `Geometry` against a hand-built
 * grid; this makes it for a whole frame through `createRenderer`, and adds the placement half, which
 * nothing else covers. It is also the case that fails if the drape's own triangulation ever escapes
 * the drape: the geometry grid folds a cell on the ground's diagonal only when there is a drape, and
 * a leak would move pixels here without moving a single altitude.
 *
 * The raised frame is the sensitivity control on the same terms as everywhere else in this file: two
 * frames agree trivially if the instrument cannot see altitude at all.
 */
private fun assertWithoutTerrainAGroundRelativeAltitudeIsAbsolute(fixture: TerrainFrameFixture) {
    ProjectionMode.entries.forEach { mode ->
        val camera = if (mode == ProjectionMode.MERCATOR) ANCHOR_CAMERA else LIMB_CAMERA
        val absolute = fixture.renderPlain(
            camera, mode,
            stickers = listOf(anchorSticker(camera, AltitudeMode.ABSOLUTE, 0.0)),
            geometries = listOf(plainGeometry(camera, AltitudeMode.ABSOLUTE, 0.0)),
        )
        val groundRelative = fixture.renderPlain(
            camera, mode,
            stickers = listOf(anchorSticker(camera, AltitudeMode.GROUND_RELATIVE, 0.0)),
            geometries = listOf(plainGeometry(camera, AltitudeMode.GROUND_RELATIVE, 0.0)),
        )
        val raised = fixture.renderPlain(
            camera, mode,
            stickers = listOf(anchorSticker(camera, AltitudeMode.ABSOLUTE, PLAIN_SENSITIVITY_METRES)),
            geometries = listOf(plainGeometry(camera, AltitudeMode.ABSOLUTE, PLAIN_SENSITIVITY_METRES)),
        )

        val stickerInk = absolute.pixelsOf(TerrainInk.STICKER)
        val drapeInk = absolute.pixelsOf(TerrainInk.DRAPE)
        println(
            "RenG terrain frame readback: $mode terrainless frame paints $stickerInk sticker and " +
                "$drapeInk geometry pixels; raising both moves " +
                "${absolute.differenceFrom(raised)} pixels",
        )
        assertTrue(
            stickerInk >= MINIMUM_STICKER_PIXELS && drapeInk >= MINIMUM_PLAIN_GEOMETRY_PIXELS,
            "$mode: the terrainless frame must draw both things, or byte identity is vacuous; it " +
                "painted $stickerInk sticker and $drapeInk geometry pixels",
        )
        assertEquals(
            0,
            absolute.differenceFrom(groundRelative),
            "$mode: with no terrain a ground-relative altitude means an absolute one (ADR 0040), so " +
                "these two frames must be byte-identical",
        )
        assertTrue(
            absolute.differenceFrom(raised) >= MINIMUM_PLAIN_SENSITIVITY_PIXELS,
            "$mode: this camera must be able to see an altitude at all, or the identity above is " +
                "satisfied by an instrument that is blind to it",
        )
    }
}

// ---- case 9: the globe drapes too ----------------------------------------------------------------

/**
 * **Task 17's "in both projections", end to end.** Every other drape claim in this suite is measured
 * under Mercator, because that is the only projection task 14's spike ran and its own closing section
 * says so in as many words: "the globe ground displaces radially through `GlobeGroundPipeline`, and a
 * globe `Geometry` is already a CPU-projected grid with a third transform factor of its own, so
 * nothing here transfers to `GLOBE` without being run again."
 *
 * So this case claims the two things a Mercator-only suite would otherwise leave to a code reading:
 * that a globe frame reaches the drape at all, and that what it reaches follows the relief rather than
 * a frame-wide constant. It is the globe's [assertADrapedGeometryFollowsTheRidgeRatherThanCuttingThroughIt].
 *
 * ## It deliberately does not measure the coplanar case, and that is a finding rather than an omission
 *
 * A first version drew the globe drape at altitude zero and measured **2,295 surviving pixels on
 * `Apple M3 Max` against 5,348 on `Apple Software Renderer`** out of the same footprint -- a z-fight
 * resolved differently by two rasterisers, which is ADR 0027's defect exactly. The arithmetic is not
 * mysterious: [GROUND_DRAPE_LIFT_METRES] is four **metres**, and at zoom 2 four metres is 0.0002 of a
 * logical pixel, so the lift buys nothing there. A metre lift is a fixed height against a depth budget
 * that scales with zoom, which the spike states as the constant's own known weakness; at zoom 2 that
 * weakness is total. This case therefore rides the surface at [GLOBE_DRAPE_CLEARANCE_METRES] -- a real
 * offset a consumer might declare -- and the coplanar globe drape is recorded as owed rather than
 * half-measured.
 *
 * The third frame declares **the same number** as `ABSOLUTE`, which is the sharpest form the contrast
 * takes: one quad 100 km above the terrain and one 100 km above the ellipsoid, from two plans that
 * differ by an enum. The terrain under this footprint runs 36 to 214 km, so the absolute one is buried
 * over most of it and the drape over none.
 */
private fun assertAGlobeGeometryDrapesToo(fixture: TerrainFrameFixture) {
    val draped = fixture.renderTerrain(
        LIMB_CAMERA, ProjectionMode.GLOBE, DemRelief.RIDGE,
        geometries = listOf(
            plainGeometry(LIMB_CAMERA, AltitudeMode.GROUND_RELATIVE, GLOBE_DRAPE_CLEARANCE_METRES),
        ),
    )
    val atSeaLevel = fixture.renderTerrain(
        LIMB_CAMERA, ProjectionMode.GLOBE, DemRelief.RIDGE,
        geometries = listOf(plainGeometry(LIMB_CAMERA, AltitudeMode.ABSOLUTE, 0.0)),
    )
    val atTheSameNumber = fixture.renderTerrain(
        LIMB_CAMERA, ProjectionMode.GLOBE, DemRelief.RIDGE,
        geometries = listOf(
            plainGeometry(LIMB_CAMERA, AltitudeMode.ABSOLUTE, GLOBE_DRAPE_CLEARANCE_METRES),
        ),
    )

    val drapedInk = draped.pixelsOf(TerrainInk.DRAPE)
    val buriedInk = atSeaLevel.pixelsOf(TerrainInk.DRAPE)
    val flatInk = atTheSameNumber.pixelsOf(TerrainInk.DRAPE)
    println(
        "RenG terrain frame readback: GLOBE draped geometry paints $drapedInk pixels, at sea level " +
            "$buriedInk, and at the same number absolute $flatInk",
    )

    assertTrue(
        drapedInk >= MINIMUM_GLOBE_DRAPED_PIXELS,
        "a globe drape must ride out of the terrain and draw; it painted $drapedInk pixels",
    )
    assertTrue(
        buriedInk <= MAXIMUM_BURIED_QUAD_PIXELS,
        "a globe quad at sea level stands under the whole ridge; it painted $buriedInk pixels",
    )
    assertTrue(
        flatInk * 100 <= drapedInk * MAXIMUM_CUT_QUAD_PERCENT,
        "the same number declared ABSOLUTE is a fixed height that the ridge stands over across most " +
            "of this quad: it painted $flatInk against the drape's $drapedInk",
    )
}

// ---- the content cases' own fixtures ------------------------------------------------------------

/**
 * A 16 x 16 white sticker at [camera]'s own anchor, map-anchored with a screen scale of two so its
 * drawn size is a fixed 32 x 32 logical pixels whatever the altitude does to its depth.
 *
 * **Screen scale rather than map scale**, so that a raised sticker moves and does not also grow: a
 * map-scaled billboard's size follows the camera distance, and a pixel count that changed with the
 * altitude would confuse "it moved" with "it got bigger". **Two rather than one** because a scale of
 * exactly 1 is a symmetry point at which an applied scale and a dropped one are the same picture.
 */
private fun anchorSticker(camera: Camera, altitudeMode: AltitudeMode, altitudeMetres: Double): Sticker =
    Sticker(
        placement = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(camera.latitude, camera.unwrappedLongitude, altitudeMetres),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 2.0,
            altitudeMode = altitudeMode,
        ),
        image = ResourceLocator(TERRAIN_STICKER_URL),
    )

/**
 * A quad two LOD-13 tiles a side, centred on [RIDGE_CAMERA] and running off every edge of a 768-pixel
 * frame -- see [RIDGE_QUAD_HALF_LONGITUDE_DEGREES] for why the overhang is load bearing.
 *
 * Its latitude span is the longitude span times `cos(45.4755)`, so it is square on screen. It covers
 * two whole ridge periods, which is what makes a flat quad at the mean height half above the terrain
 * and half below it.
 */
private fun ridgeGeometry(altitudeMode: AltitudeMode, altitudeMetres: Double): Geometry {
    val latitude = RIDGE_CAMERA.latitude + RIDGE_QUAD_LATITUDE_OFFSET_DEGREES
    val longitude = RIDGE_CAMERA.unwrappedLongitude + RIDGE_QUAD_LONGITUDE_OFFSET_DEGREES
    return Geometry(
        topLeft = Vector3(
            latitude + RIDGE_QUAD_HALF_LATITUDE_DEGREES,
            longitude - RIDGE_QUAD_HALF_LONGITUDE_DEGREES,
            altitudeMetres,
        ),
        bottomRight = Vector3(
            latitude - RIDGE_QUAD_HALF_LATITUDE_DEGREES,
            longitude + RIDGE_QUAD_HALF_LONGITUDE_DEGREES,
            altitudeMetres,
        ),
        shaderPair = DRAPE_SHADER_PAIR,
        altitudeMode = altitudeMode,
    )
}

/** [ridgeGeometry]'s shape at the low-zoom cameras, where a tile-sized quad would be a few pixels. */
private fun plainGeometry(camera: Camera, altitudeMode: AltitudeMode, altitudeMetres: Double): Geometry =
    Geometry(
        topLeft = Vector3(camera.latitude + 5.0, camera.unwrappedLongitude - 8.0, altitudeMetres),
        bottomRight = Vector3(camera.latitude - 5.0, camera.unwrappedLongitude + 8.0, altitudeMetres),
        shaderPair = DRAPE_SHADER_PAIR,
        altitudeMode = altitudeMode,
    )

/**
 * A consumer shader pair painting one flat colour, which is what makes a pixel count a pixel count.
 *
 * `runGeometrySubdivisionReadbackSuite`'s probe paints a gradient because it is measuring *where* the
 * interpolation lands; every claim here is "did this quad hold this pixel", so a single ink that no
 * tile carries is the instrument, and a gradient would put half the quad into `OTHER`.
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
        "out vec4 rengDrapeOut;\n" +
        "void main() {\n" +
        "    rengDrapeOut = vec4(1.0, 0.0, 1.0, 1.0);\n" +
        "}\n",
)

private class TerrainFrameFixture(
    private val binding: GlBinding,
    private val transport: TerrainFrameTransport,
    private val terrainRenderer: Renderer,
    private val plainRenderer: Renderer,
    private val fineRenderer: Renderer,
    private val terrainTarget: RenderTarget,
    private val plainTarget: RenderTarget,
    private val fineTarget: RenderTarget,
    private val targetFramebuffer: Int,
) {
    private var frameIndex: Long = 0L

    fun renderTerrain(
        camera: Camera,
        mode: ProjectionMode,
        relief: DemRelief,
        stickers: List<Sticker> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): TerrainFrame {
        transport.relief = relief
        return render(terrainRenderer, terrainTarget, camera, mode, stickers, geometries)
    }

    /** [renderTerrain] against the `exaggeration: 1.5` style — see [FINE_TERRAIN_STYLE_JSON]. */
    fun renderFine(
        camera: Camera,
        mode: ProjectionMode,
        relief: DemRelief,
        stickers: List<Sticker> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): TerrainFrame {
        transport.relief = relief
        return render(fineRenderer, fineTarget, camera, mode, stickers, geometries)
    }

    fun renderPlain(
        camera: Camera,
        mode: ProjectionMode,
        stickers: List<Sticker> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): TerrainFrame {
        transport.relief = DemRelief.SEA_LEVEL
        return render(plainRenderer, plainTarget, camera, mode, stickers, geometries)
    }

    fun globeCamera(camera: Camera): ResolvedGlobeCamera =
        (
            resolveGlobeCamera(
                camera,
                OutputPixelSize(TERRAIN_FRAME_READBACK_PIXELS, TERRAIN_FRAME_READBACK_PIXELS),
            ) as SpatialOutcome.Success<ResolvedGlobeCamera>
            ).value

    private fun render(
        renderer: Renderer,
        renderTarget: RenderTarget,
        camera: Camera,
        mode: ProjectionMode,
        stickers: List<Sticker> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): TerrainFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.viewport(0, 0, TERRAIN_FRAME_READBACK_PIXELS, TERRAIN_FRAME_READBACK_PIXELS)
        binding.clearColor(CLEARED[0] / 255f, CLEARED[1] / 255f, CLEARED[2] / 255f, CLEARED[3] / 255f)
        binding.clear(GL_COLOR_BUFFER_BIT)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

        // prepare() is suspending and reaches the Rentile engine on Dispatchers.Default; draw() is
        // synchronous GL work that must happen on the thread holding the context, so the two are
        // split rather than run inside one runBlocking body.
        val plan = FramePlan(
            frameIndex = frameIndex++,
            camera = camera,
            projectionMode = mode,
            drawBasemap = true,
            stickers = stickers,
            geometries = geometries,
        )
        val frame = runBlocking { renderer.prepare(plan) }
        try {
            renderer.draw(frame, renderTarget)
        } finally {
            frame.close()
        }

        val pixels = ByteArray(TERRAIN_FRAME_PIXEL_COUNT * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, TERRAIN_FRAME_READBACK_PIXELS, TERRAIN_FRAME_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, pixels,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        return TerrainFrame(pixels)
    }
}

/** What a pixel of one of this suite's frames can be. */
private enum class TerrainInk { CLEARED, EVEN_TILE, ODD_TILE, STICKER, DRAPE, OTHER }

/** One maximal run of one ink along a row. */
private class InkRun(val ink: TerrainInk, val start: Int, val length: Int) {
    override fun toString(): String = "$ink:$length"
}

/**
 * One read-back frame. **Row 0 is the bottom**, exactly as `glReadPixels` returns it and deliberately
 * un-flipped: nothing here is measured against a top-down convention, and flipping once is one more
 * sign to get wrong.
 */
private class TerrainFrame(val bytes: ByteArray) {
    fun inkAt(column: Int, row: Int): TerrainInk = inkAtIndex(row * TERRAIN_FRAME_READBACK_PIXELS + column)

    fun inkAtIndex(index: Int): TerrainInk {
        val offset = index * 4
        return when {
            isCloseTo(offset, CLEARED) -> TerrainInk.CLEARED
            isCloseTo(offset, EVEN_TILE) -> TerrainInk.EVEN_TILE
            isCloseTo(offset, ODD_TILE) -> TerrainInk.ODD_TILE
            isCloseTo(offset, STICKER_INK) -> TerrainInk.STICKER
            isCloseTo(offset, DRAPE_INK) -> TerrainInk.DRAPE
            else -> TerrainInk.OTHER
        }
    }

    /** How many pixels of the whole frame carry [ink]. */
    fun pixelsOf(ink: TerrainInk): Int {
        var count = 0
        for (index in 0 until TERRAIN_FRAME_PIXEL_COUNT) {
            if (inkAtIndex(index) == ink) count += 1
        }
        return count
    }

    /** Whether each pixel carries [ink], as a flat mask the survivor count intersects. */
    fun maskOf(ink: TerrainInk): BooleanArray =
        BooleanArray(TERRAIN_FRAME_PIXEL_COUNT) { inkAtIndex(it) == ink }

    /**
     * Whether each pixel carries a basemap tile, either colour.
     *
     * Two inks rather than one because the fixture's tiles alternate by column and the coplanar case's
     * camera straddles a boundary; asking for one of them would silently halve the contested set.
     */
    fun groundMask(): BooleanArray = BooleanArray(TERRAIN_FRAME_PIXEL_COUNT) {
        val ink = inkAtIndex(it)
        ink == TerrainInk.EVEN_TILE || ink == TerrainInk.ODD_TILE
    }

    private fun isCloseTo(offset: Int, colour: IntArray): Boolean = (0..2).all {
        abs((bytes[offset + it].toInt() and 0xff) - colour[it]) <= TERRAIN_CHANNEL_TOLERANCE
    }

    /** How many pixels of the whole frame still show the target this suite cleared it to. */
    fun clearedPixels(): Int {
        var cleared = 0
        for (index in 0 until TERRAIN_FRAME_PIXEL_COUNT) {
            if (isCloseTo(index * 4, CLEARED)) cleared += 1
        }
        return cleared
    }

    /** How many pixels carry anything but the cleared target. */
    fun paintedPixels(): Int = TERRAIN_FRAME_PIXEL_COUNT - clearedPixels()

    /** Every maximal run of one ink along the frame's centre row, west to east. */
    fun centreRowRuns(): List<InkRun> {
        val row = TERRAIN_FRAME_READBACK_PIXELS / 2
        val runs = mutableListOf<InkRun>()
        var start = 0
        var current = inkAt(0, row)
        for (column in 1 until TERRAIN_FRAME_READBACK_PIXELS) {
            val ink = inkAt(column, row)
            if (ink != current) {
                runs += InkRun(current, start, column - start)
                current = ink
                start = column
            }
        }
        runs += InkRun(current, start, TERRAIN_FRAME_READBACK_PIXELS - start)
        return runs
    }

    /**
     * `(western limb column, eastern limb column)` as counts of cleared pixels at each end of the
     * centre row -- how far the sphere's silhouette is from each edge of the frame.
     */
    fun centreRowLimbColumns(): Pair<Int, Int> {
        val row = TERRAIN_FRAME_READBACK_PIXELS / 2
        var west = 0
        while (west < TERRAIN_FRAME_READBACK_PIXELS && inkAt(west, row) == TerrainInk.CLEARED) west += 1
        var east = 0
        while (
            east < TERRAIN_FRAME_READBACK_PIXELS &&
            inkAt(TERRAIN_FRAME_READBACK_PIXELS - 1 - east, row) == TerrainInk.CLEARED
        ) {
            east += 1
        }
        return west to east
    }

    /**
     * How far the frame's centre tile reaches from the frame centre, as the mean of its western and
     * eastern boundary radii on the centre row.
     *
     * The mean rather than one side, because a bearing of zero and a camera at a tile's own centre
     * make the two symmetric by construction, and averaging them halves whatever a fill rule does to
     * either.
     */
    fun centreTileBoundaryRadius(): Int {
        val centre = TERRAIN_FRAME_READBACK_PIXELS / 2
        val runs = centreRowRuns()
        val middle = runs.first { it.start <= centre && centre < it.start + it.length }
        return ((centre - middle.start) + (middle.start + middle.length - centre)) / 2
    }

    fun cornersAreCleared(): Boolean {
        val last = TERRAIN_FRAME_READBACK_PIXELS - 1
        return listOf(0 to 0, last to 0, 0 to last, last to last)
            .all { (column, row) -> inkAt(column, row) == TerrainInk.CLEARED }
    }

    /** How many pixels disagree with [other] in **any** of their four channels. */
    fun differenceFrom(other: TerrainFrame): Int {
        var differing = 0
        for (index in 0 until TERRAIN_FRAME_PIXEL_COUNT) {
            val offset = index * 4
            if ((0..3).any { bytes[offset + it] != other.bytes[offset + it] }) differing += 1
        }
        return differing
    }

    /** `(column, row)` of the first disagreeing pixel, or `null` when there is none. */
    fun firstDifferenceFrom(other: TerrainFrame): Pair<Int, Int>? {
        for (index in 0 until TERRAIN_FRAME_PIXEL_COUNT) {
            val offset = index * 4
            if ((0..3).any { bytes[offset + it] != other.bytes[offset + it] }) {
                return (index % TERRAIN_FRAME_READBACK_PIXELS) to (index / TERRAIN_FRAME_READBACK_PIXELS)
            }
        }
        return null
    }
}

private fun createTerrainFrameTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8,
        TERRAIN_FRAME_READBACK_PIXELS, TERRAIN_FRAME_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the terrain frame readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

// ---- the fixture's numbers ------------------------------------------------------------------------

/**
 * 768, and the number is set by the **seam** case rather than by the globe one.
 *
 * A basemap tile is 512 logical pixels a side at every zoom, so a 512-pixel frame anchored at a
 * tile's own centre contains **no** tile boundary at all and the seam case would have nothing to
 * measure. 768 spans one and a half tiles: one whole tile in the middle and 128 pixels of each
 * neighbour, with a boundary 256 pixels either side of the frame centre. It is also comfortably more
 * than the globe case needs -- at [LIMB_ZOOM] the whole sphere lands inside it with 95 pixels of
 * clearance at each end of the centre row.
 */
internal const val TERRAIN_FRAME_READBACK_PIXELS: Int = 768

private const val TERRAIN_FRAME_PIXEL_COUNT: Int =
    TERRAIN_FRAME_READBACK_PIXELS * TERRAIN_FRAME_READBACK_PIXELS

/**
 * Eight, which is `GlobeFrameReadbackSuite`'s tolerance for the same job: a tile's ink arrives
 * through Rentile's rasteriser, RenG's PNG decode, a texture upload and a composite pass, and the
 * question every classification here asks is "which of three widely separated colours is this",
 * never "is this exactly that byte". Case 3 is where whole frames are compared, and it compares them
 * with an exact per-pixel [TerrainFrame.differenceFrom] rather than with this tolerance.
 */
private const val TERRAIN_CHANNEL_TOLERANCE: Int = 8

/**
 * What the target is cleared to before every render, and it is a colour **no fixture tile carries**.
 *
 * RenG composites its frame onto the caller's target rather than clearing it, so a pixel still
 * showing this after a draw is a pixel the renderer left alone -- which is precisely what a crack
 * between two tiles is, and what the space beyond a globe's limb is.
 */
private val CLEARED: IntArray = intArrayOf(0, 96, 32, 255)

/**
 * The ink of a `GROUND_RELATIVE` sticker: opaque white, which no tile, no background and no drape
 * carries, so counting it is counting the sticker.
 */
private val STICKER_INK: IntArray = intArrayOf(255, 255, 255, 255)

/** The ink a draped `Geometry`'s consumer shader pair paints, for the same reason. */
private val DRAPE_INK: IntArray = intArrayOf(255, 0, 255, 255)

/** The ink of a tile at an even `x`, which is where the fixture's sea level sits. */
private val EVEN_TILE: IntArray = intArrayOf(240, 40, 40, 255)

/** The ink of a tile at an odd `x`, which is where the fixture's summit sits. */
private val ODD_TILE: IntArray = intArrayOf(40, 160, 200, 255)

/**
 * Zoom 4 for the seam and the cross-mode cases, and **not higher**, because Cycle G's task 12
 * measured the sagitta of a frame-sized quad at 27.9 logical pixels at zoom 4, 1.75 at zoom 8 and
 * 0.44 at zoom 10: above about zoom 12 a globe and a plane are the same picture and a cross-mode
 * comparison proves nothing while looking thorough.
 */
private const val SEAM_ZOOM: Double = 4.0

/**
 * The exact centre of tile `x = 8` at LOD 4 -- `360 * 8.5 / 16 - 180`, which is a terminating binary
 * fraction -- so that the two tile boundaries the seam case measures sit symmetrically at 256 output
 * pixels either side of the frame centre.
 *
 * **Latitude 34 rather than the equator**, and that is not decoration: Mercator's altitude scale
 * carries a `1 / cos(latitude)` term that is exactly `1` at the equator, so a fixture written there
 * cannot tell an honoured latitude term from a dropped one. `runGroundDisplacementReadback` keeps the
 * case that isolates that term; this one merely refuses to sit on its symmetry point.
 */
private val SEAM_CAMERA: Camera = Camera(
    latitude = 34.0,
    unwrappedLongitude = 11.25,
    zoom = SEAM_ZOOM,
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * Zoom 2 for the limb case: high enough that the sphere is 578 pixels across on the centre row and a
 * 25-pixel rise is a fifth of its radius rather than noise, low enough that the whole limb is inside
 * the frame with clearance at every corner.
 */
private const val LIMB_ZOOM: Double = 2.0

private val LIMB_CAMERA: Camera = Camera(
    latitude = SEAM_CAMERA.latitude,
    unwrappedLongitude = SEAM_CAMERA.unwrappedLongitude,
    zoom = LIMB_ZOOM,
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * 1,000 metres in the DEM against the style's `"exaggeration": 500`, so 500 kilometres of drawn
 * relief. See the suite KDoc for why a fixture at a plausible summit height would be unmeasurable,
 * and `runGroundDisplacementReadback`'s `GLOBE_EXAGGERATION` for the precedent.
 *
 * Its Mapbox triple is `(1, 173, 176)` with no rounding of its own, and it was computed by the same
 * Python that wrote the PNG rather than by RenG's own decoder.
 */
private const val SUMMIT_METRES: Int = 1_000

/**
 * 64 pixels of 589,824, which is a **fill-rule budget rather than a defect budget**.
 *
 * Two adjacent ground tiles reach their shared edge through two different per-tile model matrices, so
 * their edge vertices agree to the last bit only if two `Float` products do; a pixel on that line
 * belongs to whichever side a driver's fill rule puts it on. Measured: **0** cleared pixels on
 * `Apple M3 Max` in both projections, so the budget absorbs a rasteriser without absorbing the
 * defect -- a ring that fell back to edge replication opens two bands about 200 pixels wide and 768
 * tall, four orders of magnitude above this.
 */
private const val MAXIMUM_CRACK_PIXELS: Int = 64

/**
 * 256 pixels of 589,824: how much of a *flat* frame this suite's cameras may leave uncovered before
 * the crack measurement they control stops meaning anything.
 *
 * Measured: **0** on `Apple M3 Max` in both projections, and **18 to 34** on `Apple Software
 * Renderer` for the globe -- a rasteriser difference at the outer edge of a sphere that fills the
 * frame, not a coverage gap. The crack itself is asserted as the *increase* over this number rather than as an
 * absolute, so whatever a driver leaves along a tile edge on a plane it is allowed to leave on a
 * displaced one too.
 */
private const val MAXIMUM_FLAT_CLEARED_PIXELS: Int = 256

/**
 * 16 output pixels, and it is the half of the seam case that a build displacing nothing fails.
 *
 * Measured: the centre tile's colour run widens from 512 pixels to 551 under Mercator and to 560
 * under the globe, so the floor sits at about two fifths of the smaller signal.
 */
private const val MINIMUM_SEAM_WIDENING_PIXELS: Int = 16

/** Three quarters of the frame, so "the no-terrain style drew a map" is a claim rather than a hope. */
private const val MINIMUM_PAINTED_PIXELS: Int = TERRAIN_FRAME_PIXEL_COUNT * 3 / 4

/**
 * 400 pixels of 589,824, against a measured **0** under Mercator and **30** under the globe on
 * `Apple M3 Max`, and **242** under the globe and **1** under Mercator on `Apple Software Renderer`.
 *
 * It is a *subdivision* budget rather than a defect budget: declaring terrain subdivides a ground
 * tile into 64 cells a side where a frame with no terrain draws 1 under Mercator and 8 from the
 * globe's curvature rule, and the outer edge of a sphere drawn at two granularities that both sit
 * inside half a logical pixel of deviation lands on a handful of different integer pixels. Thirteen
 * times the largest measured value leaves room for a rasteriser RenG has not met;
 * [MINIMUM_RAISED_DIFFERENCE_PIXELS] is what stops it absorbing a real defect.
 */
private const val MAXIMUM_SUBDIVISION_DIFFERENCE_PIXELS: Int = 400

/**
 * The floor a raised DEM's difference must clear, so that [MAXIMUM_SUBDIVISION_DIFFERENCE_PIXELS] is
 * stated against the signal it must not absorb. Measured at 92,032 pixels under the globe, so 40,000
 * is under half the measurement and a hundred times the flat budget.
 */
private const val MINIMUM_RAISED_DIFFERENCE_PIXELS: Int = 40_000

/** Measured 24 pixels for the raised limb and 25 for the uniform control; half of it is the floor. */
private const val MINIMUM_LIMB_RISE_PIXELS: Int = 12

/**
 * Two pixels, against a measured **0**: the western limb sits over sea-level tiles and must not move
 * when the eastern half of the world rises. Two rather than zero because the limb column is the
 * first non-cleared pixel of a row crossing a circle, which is a fill-rule question, and because a
 * deliberately broken build measured **25** here -- twelve budgets away -- so the budget absorbs a
 * rasteriser without absorbing the defect.
 */
private const val MAXIMUM_UNRAISED_LIMB_MOVEMENT_PIXELS: Int = 2

/** Measured +39 under Mercator and +60 under the globe; the floor is under a third of the smaller. */
private const val MINIMUM_CROSS_MODE_DISPLACEMENT_PIXELS: Int = 12

/** Measured 6 pixels of parting at zoom 4. A tangent-plane globe measures **0**. */
private const val MINIMUM_FLAT_PARTING_PIXELS: Int = 3
private const val MAXIMUM_FLAT_PARTING_PIXELS: Int = 20

/** Measured 21 pixels of margin (60 against 39). A tangent-plane globe measures **0**. */
private const val MINIMUM_CURVATURE_MARGIN_PIXELS: Int = 8

/**
 * How far above the first-order prediction `1 + cameraDistance / globeRadius` the measured ratio may
 * sit. The prediction is 1.59 at this camera and the measurement 1.54, so the model is about three
 * per cent optimistic; 1.4x leaves room for that and for a fill rule while still refusing the 2.7 a
 * globe borrowing Mercator's `1 / cos(34)` would produce.
 */
private const val MAXIMUM_RATIO_OVERSHOOT: Double = 1.4

/**
 * [SEAM_CAMERA] pitched 45 degrees, which is what turns a vertical rise into a screen movement.
 *
 * At pitch 0 a raised map anchor sits at the same screen position as an unraised one and differs only
 * by perspective scale, so a sticker whose size is fixed in screen pixels would barely move -- the
 * case would then rest entirely on the burial half and would pass against a build that resolved the
 * altitude and then discarded it. Pitch 45 moves it about a hundred pixels up the frame.
 */
private val ANCHOR_CAMERA: Camera = Camera(
    latitude = SEAM_CAMERA.latitude,
    unwrappedLongitude = SEAM_CAMERA.unwrappedLongitude,
    zoom = SEAM_ZOOM,
    bearing = 0.0,
    pitch = 45.0,
)

/**
 * [SUMMIT_METRES] against the terrain style's `exaggeration` of 500: the height the ground actually
 * draws, and therefore the `ABSOLUTE` altitude a `GROUND_RELATIVE` zero must be identical to.
 *
 * Written as the product rather than as `500000.0` so that changing either factor changes this, and
 * exact in binary at every step -- 1,000 m is the Mapbox triple `(1, 173, 176)` on the nose and 500 is
 * a whole number, so the shader and the CPU lookup reach the same `Double` with nothing to round.
 */
private const val DRAWN_SUMMIT_METRES: Double = SUMMIT_METRES * 500.0

/**
 * Zoom 13 at the centre of tile z13/4293/2931 -- 45.4755 N, 8.6792 E -- which is task 14's spike's own
 * camera, so this suite's coplanar reading and the spike's table describe the same regime.
 *
 * **Latitude is deliberately not 0**, where Mercator's `1 / cos(latitude)` term is exactly 1 and a
 * dropped latitude term is invisible; and the longitude puts the camera on [DEM_RIDGE_PNG]'s own
 * summit column, so the quad below straddles the peak rather than a flank.
 */
private val RIDGE_CAMERA: Camera = Camera(
    latitude = 45.4755,
    unwrappedLongitude = 8.6792,
    zoom = 13.0,
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * One whole LOD-13 tile of longitude, `360 / 8192`, so the quad spans **two** tiles -- 1,024 logical
 * pixels across a 768-pixel frame, with 128 pixels of margin at each edge.
 *
 * **The margin is the measurement rather than decoration**, and it is what the first version of this
 * fixture got wrong. The coplanar case's contested set comes from a *raised* control quad, and a quad
 * 50 m nearer the camera projects about one pixel larger at every edge; with a quad that ended inside
 * the frame, that fringe put 1,875 pixels into the contested set that the coplanar drape never covered
 * at all, and the survivor count read 97.99 per cent while measuring the control's own footprint.
 * Running the quad off every edge of the frame puts the fringe outside it, which is exactly why the
 * spike's own content filled its frame.
 *
 * At this span `drapeCellsPerSide` asks for 128 cells and gets them, so a drape node sits every 8
 * logical pixels -- the ground's own spacing at 64 cells over a 512-pixel tile.
 */
private const val RIDGE_QUAD_HALF_LONGITUDE_DEGREES: Double = 360.0 / 8192.0

/** The same span in latitude, narrowed by `cos(45.4755)` so the quad is roughly square on screen. */
private const val RIDGE_QUAD_HALF_LATITUDE_DEGREES: Double = RIDGE_QUAD_HALF_LONGITUDE_DEGREES * 0.7009

/**
 * **Half a ground cell east and a third of one south, and this offset is the difference between the
 * case measuring RenG and the case measuring itself.**
 *
 * Without it the quad's western edge lands on `x = 4292.5` at LOD 13, which is exactly a ground cell
 * boundary at 64 cells a tile -- and with 128 cells over two tiles the drape's node spacing is the
 * ground's, so **every drape node coincides with a ground node**. At a coincident node the ground's own
 * surface and the texel underneath the point are the same number, so a lookup reading the texel
 * instead of reconstructing the cell measured **589,761 of 589,761 survivors** and the case reported a
 * clean pass against a build 47 metres wrong between nodes. That is this cycle's recurring shape -- a
 * fixture at a symmetry point -- and it was found by mutation rather than by reading.
 *
 * It is also the *realistic* condition, which is why the fix is an offset rather than a different
 * granularity: a `Geometry` carries a consumer's own two latitudes and two longitudes and lands
 * wherever they put it. Task 14's spike calls this the offset lattice and offsets its own content by
 * exactly half a cell east and a third of one south; [GROUND_DRAPE_LIFT_METRES] is sized against that
 * arm's lift ladder and would be over-generous against a snapped one.
 */
private const val RIDGE_QUAD_LONGITUDE_OFFSET_DEGREES: Double = 360.0 / 8192.0 / 64.0 / 2.0
private const val RIDGE_QUAD_LATITUDE_OFFSET_DEGREES: Double =
    -360.0 / 8192.0 * 0.7009 / 64.0 / 3.0

/**
 * The height the ridge draws on average across [ridgeGeometry]'s footprint: the profile is a triangle
 * spanning 15.6 m to 984.4 m, whose mean over whole periods is 500 m, times the fine style's
 * exaggeration of 1.5.
 *
 * A flat quad here is above the terrain on both flanks and below it across the middle, which is the
 * "cutting through a ridge" picture ADR 0040 replaces -- and is why the mean rather than the summit or
 * the base, either of which would put the flat quad entirely on one side and measure nothing.
 */
private const val MEAN_DRAWN_RIDGE_METRES: Double = 500.0 * 1.5

/**
 * 50 metres, which is the spike's own control offset, and about 7.5 logical pixels at zoom 13.
 *
 * Far enough above the ground to leave the coplanar fight outright -- the spike's quantum probe puts
 * the depth buffer's resolution at well under a millimetre of terrain height here -- and near enough
 * that the raised quad's footprint is the drape's to within about a pixel of fringe, which is what
 * lets it stand in for "the pixels the content covers".
 */
private const val DRAPE_CONTROL_METRES: Double = 50.0

/** 200 km, which is 49 logical pixels at zoom 4 and 10 at zoom 2: visible in both, absurd in neither. */
private const val PLAIN_SENSITIVITY_METRES: Double = 200_000.0

/**
 * A 32 x 32 sticker is 1,024 pixels; a quarter of that is the floor, which leaves room for a rasteriser
 * and for the frame edge without leaving room for a sticker that never drew.
 */
private const val MINIMUM_STICKER_PIXELS: Int = 256

/**
 * 16 pixels of a 1,024-pixel sticker: a billboard buried under half a million metres of terrain is
 * gone, and the budget is for a fill-rule sliver at the quad's own edge rather than for a fraction of
 * it showing.
 */
private const val MAXIMUM_BURIED_STICKER_PIXELS: Int = 16

/** [ridgeGeometry] runs off every edge of the frame, so a draped one covers essentially all of it. */
private const val MINIMUM_DRAPED_PIXELS: Int = 400_000

/**
 * A flat quad at the ridge's mean height keeps its two flanks and loses its middle, which is about
 * half of it. 75 per cent is a ceiling that a genuinely draped quad -- which keeps essentially all of
 * it -- cannot slip under, stated with room for a rasteriser at the boundary between the two.
 */
private const val MAXIMUM_CUT_QUAD_PERCENT: Int = 75

/** And it must still draw its flanks, or the ceiling above is satisfied by a quad that never drew. */
private const val MINIMUM_CUT_QUAD_PIXELS: Int = 50_000

/** A quad at sea level under a 1,477 m ridge is gone; the budget is the frame's own edge fringe. */
private const val MAXIMUM_BURIED_QUAD_PIXELS: Int = 512

/**
 * The coplanar case needs the drape and the ground over a real area before a percentage of it means
 * anything. [ridgeGeometry] and the ground share essentially the whole 589,824-pixel frame; two thirds
 * of it is the floor.
 */
private const val MINIMUM_CONTESTED_PIXELS: Int = 400_000

/**
 * **95 per cent**, and the gap between that and 100 is a measurement rather than slack.
 *
 * Task 14's spike measured **102,370 of 102,400** -- 99.97 per cent -- for a four-metre lift on the
 * offset lattice at zoom 13 and pitch 0, and **53,048 of 102,400** for no lift at all. This fixture
 * measures **579,754 of 589,824** (98.29 per cent) and **293,346** (49.73 per cent) with the lift
 * deleted, so it reproduces the spike's "no lift keeps about half" almost exactly and falls 1.7 points
 * short of its whole.
 *
 * **That shortfall is the fixture being harsher, and the spike predicted it in as many words**: "the
 * vertical errors ... scale with the relief per cell and the numbers above should be read as that
 * profile's, not as constants". [DEM_RIDGE_PNG] steps **46.9 m** of drawn relief per ground cell where
 * the spike's fixture stepped 28, and the residual the lift is fighting lives along the creases, whose
 * height is exactly what that step is. So `GROUND_DRAPE_LIFT_METRES` is under-sized for a ridge this
 * steep, by about 10,000 pixels of speckle along fold lines -- which is a different thing from ADR
 * 0027's half-erased quad, and is the cost recorded rather than repaired.
 *
 * 95 sits below the measurement with room for a rasteriser (the spike's two software runs agree with
 * the GPU to within one per cent) and 45 points above the lift's absence.
 */
private const val MINIMUM_SURVIVOR_PERCENT: Int = 95

/**
 * And the sunk control must keep essentially none, or the survivor count above is measuring a ground
 * that cannot delete anything. Two per cent rather than zero for the fringe where the sunk quad's
 * slightly smaller footprint falls outside the contested set's own edge.
 */
private const val MAXIMUM_SUNK_SURVIVOR_PERCENT: Int = 2

/** [plainGeometry] covers a 16 x 10 degree box; a few thousand pixels of it is a real quad. */
private const val MINIMUM_PLAIN_GEOMETRY_PIXELS: Int = 2_048

/** The same quad on a globe over relief, which the ridge's own dips take a bite out of. */
private const val MINIMUM_GLOBE_DRAPED_PIXELS: Int = 1_024

/**
 * 100 km, which is about five logical pixels at [LIMB_ZOOM] against a ridge 25 pixels tall: enough for
 * a globe drape to leave the coplanar fight outright, and a clearance a consumer might really declare.
 */
private const val GLOBE_DRAPE_CLEARANCE_METRES: Double = 100_000.0


/**
 * Raising both by [PLAIN_SENSITIVITY_METRES] moves 33,964 pixels under Mercator and **574** on the
 * globe, where 200 km is three per cent of the earth's radius and the quad is a small one; 256 is
 * under half the smaller measurement.
 */
private const val MINIMUM_PLAIN_SENSITIVITY_PIXELS: Int = 256

// ---- the fixture's bytes --------------------------------------------------------------------------

private const val TERRAIN_STYLE_URL: String = "https://styles.example/terrain-frame.json"
private const val PLAIN_STYLE_URL: String = "https://styles.example/terrain-frame-plain.json"
private const val FINE_TERRAIN_STYLE_URL: String = "https://styles.example/terrain-frame-fine.json"
private const val TERRAIN_STICKER_URL: String = "https://images.example/terrain-frame-sticker.png"
private const val TERRAIN_TILE_TEMPLATE: String = "https://tiles.example/tf/{z}/{x}/{y}.png"
private const val TERRAIN_DEM_TEMPLATE: String = "https://tiles.example/tfd/{z}/{x}/{y}.png"

/**
 * A style with an opaque background, one raster source and one `raster-dem` source the top-level
 * `terrain` block names, so a rendered tile is exactly its source image and the ground is displaced
 * by exactly the DEM the transport chose.
 *
 * **`tileSize` is 64 because RenG accepts 64, 256 and 512 and nothing else**
 * (`BasemapStyleManifest.SUPPORTED_TILE_SIZES`), and 64 is the size at which a whole DEM tile is a
 * 157-byte PNG that can be checked in and read. **`maxzoom` is 22** -- the profile's own maximum
 * output zoom -- so every requested tile has a DEM of its own and the overzoom window is the identity;
 * the overzoom arithmetic is `DemTileWindowTest`'s subject and deliberately not confounded with this
 * suite's.
 *
 * `"exaggeration": 500` for the reason the suite KDoc gives, and never `1`.
 */
private val TERRAIN_STYLE_JSON: String =
    """{"version":8,"name":"reng-terrain-frame",""" +
        """"sources":{"s":{"type":"raster","tiles":["$TERRAIN_TILE_TEMPLATE"],"tileSize":512},""" +
        """"dem":{"type":"raster-dem","tiles":["$TERRAIN_DEM_TEMPLATE"],"tileSize":64,""" +
        """"encoding":"mapbox","minzoom":0,"maxzoom":22}},""" +
        """"terrain":{"source":"dem","exaggeration":500},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#000000"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/**
 * [TERRAIN_STYLE_JSON] at **`"exaggeration": 1.5`**, which is the whole difference and the reason a
 * third renderer exists.
 *
 * The other two styles exaggerate by 500 so that a 1,000 m DEM is half a million metres of relief and
 * a displacement is unmissable at zoom 4. Tasks 16 and 17 need the opposite: the coplanar fight ADR
 * 0039's erratum measures is a fight in *metres* against a depth budget that scales with zoom, and
 * `GROUND_DRAPE_LIFT_METRES` is four of them. At zoom 4 a metre is 0.0002 logical pixels, so a
 * four-metre lift would be eight ten-thousandths of a pixel and a survivor count there would measure
 * a driver's rounding. At `1.5` and zoom 13 the same DEM stands 1,477 m tall -- about 220 logical
 * pixels -- and a metre is 0.149 of one, which is the regime the spike ran in.
 *
 * **`1.5` rather than `1`** for this cycle's standing reason: at 1 an honoured exaggeration and a
 * dropped one draw the same picture. It is also the spike's own value.
 */
private val FINE_TERRAIN_STYLE_JSON: String =
    """{"version":8,"name":"reng-terrain-frame-fine",""" +
        """"sources":{"s":{"type":"raster","tiles":["$TERRAIN_TILE_TEMPLATE"],"tileSize":512},""" +
        """"dem":{"type":"raster-dem","tiles":["$TERRAIN_DEM_TEMPLATE"],"tileSize":64,""" +
        """"encoding":"mapbox","minzoom":0,"maxzoom":22}},""" +
        """"terrain":{"source":"dem","exaggeration":1.5},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#000000"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/**
 * [TERRAIN_STYLE_JSON] with the `terrain` block and the `raster-dem` source removed and **nothing
 * else changed**, so that case 3's byte comparison is a comparison of terrain against no terrain
 * rather than of two different maps.
 */
private val PLAIN_STYLE_JSON: String =
    """{"version":8,"name":"reng-terrain-frame-plain",""" +
        """"sources":{"s":{"type":"raster","tiles":["$TERRAIN_TILE_TEMPLATE"],"tileSize":512}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#000000"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/**
 * Serves the two styles, one solid PNG per basemap tile keyed on the parity of its `x`, and whichever
 * DEM [relief] currently names.
 *
 * **[relief] is mutable, and that it works at all is a measured property of this fixture rather than
 * an assumption.** The store below never returns a hit, so Rentile refetches every DEM tile of every
 * frame -- 25 requests per frame at the seam camera -- and a render therefore sees the policy set
 * immediately before it. Two renders of the same camera with the same policy were confirmed
 * byte-identical during the spike that sized this suite, which is the property that makes case 3's
 * comparison a comparison of styles rather than of cache states.
 *
 * The fallbacks are scoped to each template's own prefix, so a url RenG composed from the wrong
 * template -- a different source, a stale style -- fails closed here rather than being handed a
 * plausible body.
 */
private class TerrainFrameTransport : Transport {
    var relief: DemRelief = DemRelief.SEA_LEVEL

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        val style = when (url) {
            TERRAIN_STYLE_URL -> TERRAIN_STYLE_JSON
            PLAIN_STYLE_URL -> PLAIN_STYLE_JSON
            FINE_TERRAIN_STYLE_URL -> FINE_TERRAIN_STYLE_JSON
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
            url == TERRAIN_STICKER_URL -> STICKER_PNG
            url.startsWith(TERRAIN_DEM_URL_PREFIX) -> demPng(url)
            url.startsWith(TERRAIN_TILE_URL_PREFIX) -> tilePng(url)
            else -> error("the terrain frame fixture serves no body for $url")
        }
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "image/png"),
        )
    }

    private fun demPng(url: String): ByteArray {
        val (lod, tileX, _) = tileOf(url)
        return relief.demFor(lod, tileX)
    }
}

private val TERRAIN_TILE_URL_PREFIX: String = TERRAIN_TILE_TEMPLATE.substringBefore("{z}")
private val TERRAIN_DEM_URL_PREFIX: String = TERRAIN_DEM_TEMPLATE.substringBefore("{z}")

/**
 * `(lod, x, y)` off a url's own trailing `.../{z}/{x}/{y}.png`, rather than from a table, because
 * which tiles a frame asks for follows the LOD selector and the perimeter ring and a table would have
 * to enumerate a set neither this file nor its reader chooses.
 */
private fun tileOf(url: String): Triple<Int, Int, Int> {
    val segments = url.removeSuffix(".png").split('/')
    return Triple(
        segments[segments.size - 3].toInt(),
        segments[segments.size - 2].toInt(),
        segments[segments.size - 1].toInt(),
    )
}

/** [EVEN_TILE] on an even tile column and [ODD_TILE] on an odd one, which is what makes a boundary
 * between two tiles visible at all. */
private fun tilePng(url: String): ByteArray =
    if (tileOf(url).second % 2 == 0) EVEN_TILE_PNG else ODD_TILE_PNG

/**
 * Reads nothing and writes nothing, which is what forces every DEM through the transport on every
 * frame and lets [TerrainFrameTransport.relief] mean what it says.
 */
private class TerrainFrameStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource): Unit = Unit
}

// Every PNG below is a real, valid truecolour-with-alpha image (colour type 6) generated once via
// CPython's zlib/struct/zlib.crc32 modules exactly as PngDecoderTest.kt documents:
//
//     import zlib, struct, base64
//     def chunk(kind, payload):
//         return (struct.pack(">I", len(payload)) + kind + payload +
//                 struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
//     raw = b"".join(b"\x00" + bytes(rgba) * width for _ in range(height))
//     png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) +
//            chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
//
// The two DEM triples were computed there too, from the Mapbox packing
// `(metres + 10000) / 0.1` split big-endian across red, green and blue: 0 m is (1, 134, 160) and
// 1,000 m is (1, 173, 176). Nothing on the expected side of any assertion runs decodePng or
// demElevationMetres, so a decoder or an encoding regression can only make this suite fail.

/**
 * 64 x 64, every texel Mapbox `(1, 134, 160)`, which decodes to exactly 0 m.
 *
 * `internal` because `RendererTerrainTest` needs a DEM body of a size a `raster-dem` source can
 * legally declare, and a second copy of a base64 blob is a second thing to regenerate.
 */
internal val DEM_SEA_LEVEL_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAZElEQVR42u3QQREAAAQAMMrLoSk5nD1WYJHV81kIECBA" +
        "gAABAgQIECBAgAABAgQIECBAgAABAgQIECBAgAABAgQIECBAgAABAgQIECBAgAABAgQIECBAgAABAgQIECBAgAAB" +
        "9y1XDGH/EBLYbAAAAABJRU5ErkJggg==",
)

/**
 * 64 x 64, every texel Mapbox `(1, 173, 176)`, which decodes to exactly 1,000 m.
 *
 * `internal` for [DEM_SEA_LEVEL_PNG]'s reason: `RendererLabelTerrainTest` needs a DEM that actually
 * displaces, and a second copy of a base64 blob is a second thing to regenerate.
 */
internal val DEM_SUMMIT_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAZUlEQVR42u3QQREAAAQAMPpncachOZw9VmCR1fNZCBAg" +
        "QIAAAQIECBAgQIAAAQIECBAgQIAAAQIECBAgQIAAAQIECBAgQIAAAQIECBAgQIAAAQIECBAgQIAAAQIECBAgQIAA" +
        "Afcth+fSLKgN2FYAAAAASUVORK5CYII=",
)

/**
 * 64 x 64, a north-south triangular ridge: column `x` carries
 * `1000 * (1 - |2 * (x + 0.5) / 64 - 1|)` metres, quantised to the Mapbox packing's own tenth of a
 * metre, and every row is identical.
 *
 * So it runs **15.6 m at either edge to 984.4 m at columns 31 and 32**, and the two edge columns carry
 * the same height, which is what lets the padded ring join one tile to the next without a step and
 * makes the ridge repeat seamlessly across a frame.
 *
 * **Its point is relief *inside* a tile.** Every other DEM here is uniform within a tile, which is the
 * exact symmetry point at which a ground cell's four corners and the texel under a point are the same
 * number -- so a lookup that read the texel instead of the cell would pass every case this suite had
 * before task 16.
 *
 * Generated by the same CPython recipe as the other DEMs, with
 * `n = round((metres + 10000) / 0.1)` split big-endian across red, green and blue.
 *
 * `internal` for [DEM_SEA_LEVEL_PNG]'s reason, and for a second one: `RendererLabelTerrainTest`
 * needs a DEM whose value under a point is not also the value at its ground cell's corners, which
 * is what lets a label fixture see the granularity its lookup is taken at.
 */
internal val DEM_RIDGE_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAABHElEQVR42u3QQUoCcRzF8Xl3CFwEHSJoEXSIoIV3" +
        "cBG06AxqTImmaEUZakMZHkJw0R1cBC26Qwt5+vAUwXcxMMz8/7/fe59CzTOrdW21F9bNr1UeWbd1665jdb6sbmH1" +
        "Tq37K6s/twY/1vDQGl1YD6X1uLKeNtbzifVyaY0r6/XbmtSs6bk1a1lvS6v6s96PrY+GNZ9an2trcbB/8p5v+Zcz" +
        "OZs7uZsZmZWZmZ0d2ZWd2Z0MyZJMyZaMyZrMyZ4O6ZJO6ZaO6ZrOu+4FAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD8f4At0IWccCbFZPgA" +
        "AAAASUVORK5CYII=",
)

/** 16 x 16, every texel opaque white, which is [STICKER_INK]. */
private val STICKER_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAFklEQVR42mP4TyFgGDVg1IBRA4aLAQBdePwurSGp" +
        "XgAAAABJRU5ErkJggg==",
)

/** 2 x 2, every texel [EVEN_TILE]. */
private val EVEN_TILE_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP4oKHxH4QZYAwAUJQI/WpVtS8AAAAASUVORK5CYII=",
)

/** 2 x 2, every texel [ODD_TILE]. */
private val ODD_TILE_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mPQWHDiPwgzwBgAVtQKPaA97a4AAAAASUVORK5CYII=",
)
