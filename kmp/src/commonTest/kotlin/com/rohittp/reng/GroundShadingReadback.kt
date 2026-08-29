package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_BACK
import com.rohittp.reng.internal.gl.GL_CCW
import com.rohittp.reng.internal.gl.GL_CLAMP_TO_EDGE
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_CULL_FACE
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_NEAREST
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_TEXTURE_MAG_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_MIN_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_WRAP_S
import com.rohittp.reng.internal.gl.GL_TEXTURE_WRAP_T
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.GlobeGroundElevationFrame
import com.rohittp.reng.internal.gl.GlobeGroundPipeline
import com.rohittp.reng.internal.gl.GlobeGroundPipelineResult
import com.rohittp.reng.internal.gl.GroundDemUniforms
import com.rohittp.reng.internal.gl.GroundPipeline
import com.rohittp.reng.internal.gl.GroundPipelineResult
import com.rohittp.reng.internal.gl.GroundTileDem
import com.rohittp.reng.internal.gl.MercatorGroundElevationFrame
import com.rohittp.reng.internal.gl.MercatorGroundTileDem
import com.rohittp.reng.internal.gl.ResolvedGlobeGroundTile
import com.rohittp.reng.internal.gl.ResolvedGroundTile
import com.rohittp.reng.internal.gl.SCENE_LIGHT_AMBIENT
import com.rohittp.reng.internal.gl.SCENE_LIGHT_DIFFUSE
import com.rohittp.reng.internal.gl.SCENE_LIGHT_DIRECTION_ENU
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.composeGlobeGroundUnitSphereToClip
import com.rohittp.reng.internal.gl.createGlobeGroundPipeline
import com.rohittp.reng.internal.gl.createGroundPipeline
import com.rohittp.reng.internal.gl.deleteGlobeGroundPipeline
import com.rohittp.reng.internal.gl.deleteGroundPipeline
import com.rohittp.reng.internal.gl.demDecodeCoefficients
import com.rohittp.reng.internal.gl.drawGlobeGround
import com.rohittp.reng.internal.gl.drawGround
import com.rohittp.reng.internal.gl.globeGroundCellsPerTileSide
import com.rohittp.reng.internal.gl.globeGroundTileEdges
import com.rohittp.reng.internal.gl.mercatorTileYEdges
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.WORLD_CIRCUMFERENCE_METRES
import com.rohittp.reng.internal.projection.globeMetresToLogicalPixels
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.demElevationMetres
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Cycle E-terrain task 11's own gate: does `RendererConfiguration.terrainShading` change the ink
 * of a slope, leave ground with no relief exactly alone, and change nothing whatever when it is
 * off.**
 *
 * ## Why the fixture's matrix throws `z` away
 *
 * Every Mercator render here goes through a matrix whose third column is **zero**, so the
 * displacement this cycle spent eight tasks building moves no pixel at all. That is the point:
 * shading and displacement are two consequences of one DEM, and a frame in which both are visible
 * cannot say which of them moved a pixel. `runGroundDisplacementReadback` owns the geometry and
 * measures it with a matrix that turns map-space `z` into rows; this one owns the colour and holds
 * the geometry still. Every difference measured below is a difference in shading.
 *
 * ## Why the tile is mid-grey rather than white
 *
 * The shade factor spans about `[0.43, 1.24]`, so it both darkens and brightens. Against the white
 * texel `runGroundDisplacementReadback` uses, every highlight would clip to 255 and a whole half of
 * the instrument would be **saturated** — the same failure that once left the globe's displacement
 * case green against a ground that did not displace, recorded in [DISPLACEMENT_READBACK_PIXELS]'
 * own KDoc. [SHADING_TILE_GREY] is 128, which is exactly `0.5019...` and puts the whole range
 * inside `(0, 255)` with room at both ends;
 * [assertEachFacingIsLitByItsOwnAngle] asserts that room is still there before it compares anything.
 *
 * ## The seven cases and what each one alone would survive
 *
 * - [assertShadingOffDrawsTheUnshadedGroundByteForByte] — the negative the option's default rests
 *   on. A ground with real relief, drawn through a renderer configured **off**, must be
 *   byte-identical to the same ground drawn with no DEM at all. A build that compiled the shaded
 *   program regardless of the flag fails here and nowhere else.
 * - [assertShadingLeavesGroundWithNoReliefAlone] — the other exact one, and the case that stops the
 *   rest from passing vacuously. A DEM whose every texel is the same **non-zero** height must render
 *   byte-identically with shading on and off. Non-zero deliberately: a flat DEM at zero would let a
 *   normal computed from absolute height rather than from differences pass.
 * - [assertEachFacingIsLitByItsOwnAngle] — four slopes of equal magnitude facing north, south, east
 *   and west, each measured against the shade its own geometry predicts. A build that dropped the
 *   normal's horizontal component paints all four and the flat one the same; one that dropped only
 *   the east/west component collapses two of the four; one that reversed the light swaps two pairs
 *   and fails the flat case above as well.
 * - [assertTheLatitudeTermReachesTheHorizontalRun] — the case that is **not** at a symmetry point.
 *   Every case above sits on an equatorial tile where `cos(latitude)` is 1 and a build that ignored
 *   it entirely draws the identical frame. This one puts the same DEM on a tile at the Mercator
 *   clip, where the horizontal run is 11.6x shorter and the slope correspondingly steeper, and on
 *   two tiles four LOD levels apart, where it is 64x longer.
 * - [assertTheNormalReadsThePaddedRing] — the off-by-one, in the direction opposite to
 *   `assertOnlyThePaddedInteriorIsSampled`'s. A tap at the tile's west edge must read padded texel
 *   0 — the **ring**, the western neighbour's own last column — because a central difference at an
 *   edge has nowhere else to come from. A build whose taps stayed inside the interior shades that
 *   edge exactly like the flat middle and fails here alone.
 * - [assertExaggerationReachesTheNormal] — the drawn relief is exaggerated, so the shading has to
 *   be. Asserted at 1 against 2 and never at 1 alone, for the reason all six corpus styles make
 *   unavoidable.
 * - [assertTheGlobeGroundIsShadedToo] — the shared normal source reaches the sphere's program as
 *   well, with the sphere's own `cos(latitude)`, and leaves a sphere with no relief alone.
 *
 * **What this suite does not claim.** Nothing about fidelity — that a shaded mountain looks like a
 * mountain — which is pixel verification and stays Cycle J's. Nothing about seams: the normal at a
 * tile's far edge is a one-vertex-line approximation a one-texel ring cannot do better than, which
 * `GROUND_NORMAL_SOURCE` records rather than hides.
 */
internal fun runGroundShadingReadback(binding: GlBinding, dialect: ShaderDialect) {
    val target = createShadingTarget(binding)
    val colour = createOpaqueGreyTexel(binding)
    // Two caches rather than one, so the unshaded and shaded pipelines cannot share a GL program:
    // their flat programs derive the same ResourceKey, and one cache would hand the second pipeline
    // the first's program and then delete it twice.
    val plainPrograms = GlProgramCache()
    val shadedPrograms = GlProgramCache()
    val plainGround = groundOrFail(binding, dialect, plainPrograms, shading = false)
    val shadedGround = groundOrFail(binding, dialect, shadedPrograms, shading = true)
    val plainGlobe = globeOrFail(binding, dialect, plainPrograms, shading = false)
    val shadedGlobe = globeOrFail(binding, dialect, shadedPrograms, shading = true)
    println("RenG ground shading readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect")

    val level = createDemTexture(binding) { _, _ -> SHADING_LEVEL_METRES }
    val northUp = createDemTexture(binding) { _, row ->
        (SHADING_PADDED - 1 - row).toDouble() * SHADING_STEP_METRES
    }
    val southUp = createDemTexture(binding) { _, row -> row.toDouble() * SHADING_STEP_METRES }
    val eastUp = createDemTexture(binding) { column, _ -> column.toDouble() * SHADING_STEP_METRES }
    val westUp = createDemTexture(binding) { column, _ ->
        (SHADING_PADDED - 1 - column).toDouble() * SHADING_STEP_METRES
    }
    val ringOnly = createDemTexture(binding) { column, row ->
        if (column == 0 || row == 0) SHADING_LEVEL_METRES + SHADING_RING_STEP_METRES else SHADING_LEVEL_METRES
    }
    val coarseRamp = createDemTexture(binding) { _, row ->
        (SHADING_PADDED - 1 - row).toDouble() * SHADING_COARSE_STEP_METRES
    }
    val globeRamp = createDemTexture(binding) { _, row ->
        (SHADING_PADDED - 1 - row).toDouble() * SHADING_GLOBE_STEP_METRES
    }
    val ramps = listOf(northUp, southUp, eastUp, westUp)
    try {
        val fixture = ShadingFixture(binding, plainGround, shadedGround, plainGlobe, shadedGlobe, target, colour)
        assertShadingOffDrawsTheUnshadedGroundByteForByte(fixture, southUp)
        assertShadingLeavesGroundWithNoReliefAlone(fixture, level)
        assertEachFacingIsLitByItsOwnAngle(fixture, level, northUp, southUp, eastUp, westUp)
        assertTheLatitudeTermReachesTheHorizontalRun(fixture, coarseRamp)
        assertTheNormalReadsThePaddedRing(fixture, level, ringOnly)
        assertExaggerationReachesTheNormal(fixture, northUp)
        assertTheGlobeGroundIsShadedToo(fixture, level, globeRamp)
    } finally {
        (ramps + listOf(level, ringOnly, coarseRamp, globeRamp, colour)).forEach {
            binding.deleteTextures(1, intArrayOf(it))
        }
        deleteGlobeGroundPipeline(binding, shadedPrograms, shadedGlobe)
        deleteGlobeGroundPipeline(binding, plainPrograms, plainGlobe)
        deleteGroundPipeline(binding, shadedPrograms, shadedGround)
        deleteGroundPipeline(binding, plainPrograms, plainGround)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * A renderer whose configuration left [RendererConfiguration.terrainShading] at its default must
 * draw a ground with real relief exactly as it draws one with no elevation at all.
 *
 * **This is the assertion the default rests on, and it is a comparison between two real draws.** The
 * ramp is a genuine 1,200 metre-per-texel slope, so a shaded build has something to shade; the
 * fixture's matrix discards `z`, so the displacement it also causes moves nothing; and the reference
 * is the frame drawn through the program that has no DEM in it at all — the one three published
 * releases shipped. A build that compiled the shaded pair regardless of the flag, or that shaded
 * inside the unshaded program under a uniform, differs here in every painted pixel.
 */
private fun assertShadingOffDrawsTheUnshadedGroundByteForByte(fixture: ShadingFixture, rampDem: Int) {
    val noTerrain = fixture.renderMercator(dem = null, shaded = false)
    val unshadedRelief = fixture.renderMercator(dem = rampDem, shaded = false)
    assertFramesIdentical(
        noTerrain,
        unshadedRelief,
        "terrain shading off must draw the ground with no shading at all: a sloped DEM through the " +
            "unshaded program must match a frame with no DEM",
    )
    assertEquals(
        SHADING_TILE_GREY,
        unshadedRelief.redAt(SHADING_CENTRE, SHADING_CENTRE),
        "the unshaded ground is the tile's own colour, unmultiplied",
    )
}

/**
 * A DEM whose every texel is the same height must render byte-identically with terrain shading on
 * and off.
 *
 * **This is what stops [assertEachFacingIsLitByItsOwnAngle] from passing vacuously.** A build that
 * lit everything by some constant — a normal hard-coded to `(0, 0, 1)` and then shaded anyway, an
 * ambient term applied unconditionally, a light whose direction was reversed so that flat ground
 * falls to its ambient floor — changes a slope's ink perfectly well and fails here.
 *
 * **The height is [SHADING_LEVEL_METRES] and not zero, deliberately.** A normal derived from
 * absolute height rather than from differences between taps is exactly zero at sea level and wrong
 * everywhere else, and a fixture at zero cannot tell the two apart. It is the same symmetry point
 * `runGroundDisplacementReadback`'s own metre scale was found sitting on.
 *
 * The exactness is not a coincidence and not a tolerance: a flat DEM makes both taps equal, so the
 * normal is `(0, 0, 1)` to the bit, so the shaded fragment stage's `incidence - flat` is exactly
 * `0.0` and its factor exactly `1.0`. See `GROUND_SHADED_FRAGMENT_SOURCE`.
 */
private fun assertShadingLeavesGroundWithNoReliefAlone(fixture: ShadingFixture, levelDem: Int) {
    val unshaded = fixture.renderMercator(dem = levelDem, shaded = false)
    val shaded = fixture.renderMercator(dem = levelDem, shaded = true)
    assertFramesIdentical(
        unshaded,
        shaded,
        "terrain shading must leave ground with no relief byte-identical",
    )
    assertEquals(
        SHADING_TILE_GREY,
        shaded.redAt(SHADING_CENTRE, SHADING_CENTRE),
        "a shaded but level ground is still the tile's own colour",
    )
}

/**
 * Four slopes of equal magnitude, facing the four cardinal directions, each measured against the
 * shade its own geometry predicts.
 *
 * **The prediction is re-derived rather than transcribed.** [predictedShade] computes
 * `(ambient + diffuse * incidence) / (ambient + diffuse * flatIncidence)` from ADR 0026's own Kotlin
 * constants; the shader computes the algebraically identical `1 + (incidence - flat) * gain`. The
 * two arrangements agree only if the light, the normal and the normalisation are all right.
 *
 * **The slope is 0.49 and that number is chosen, not convenient.** `dot(normal, light)` for a
 * north-facing slope is *stationary* where the normal points straight at the light — at slope
 * `L.y / L.z = 0.906` — so a fixture there would be insensitive to the slope being wrong by a long
 * way, which is precisely the symmetry-point shape F-2 found seven of and E-labels twelve. 0.49 sits
 * on the steep part of that curve and well away from 0.
 *
 * **The measurement is a single pixel because a linear ramp shades uniformly.** Every vertex of the
 * grid sees the same one-texel gradient — including the edge ones, which read the ring the ramp also
 * fills — so the whole tile is one colour and the centre pixel is the whole picture. The uniformity
 * itself is asserted, so a build whose edge vertices disagreed with its interior ones fails rather
 * than being averaged away.
 */
private fun assertEachFacingIsLitByItsOwnAngle(
    fixture: ShadingFixture,
    levelDem: Int,
    northUpDem: Int,
    southUpDem: Int,
    eastUpDem: Int,
    westUpDem: Int,
) {
    val slope = fixture.equatorialSlope()
    val cases = listOf(
        // A ground rising toward the north **faces south**, which is the sign this fixture exists to
        // pin: the normal leans away from the rise.
        Triple("north-rising (south-facing)", northUpDem, predictedShade(eastSlope = 0.0, northSlope = slope)),
        Triple("south-rising (north-facing)", southUpDem, predictedShade(eastSlope = 0.0, northSlope = -slope)),
        Triple("east-rising (west-facing)", eastUpDem, predictedShade(eastSlope = slope, northSlope = 0.0)),
        Triple("west-rising (east-facing)", westUpDem, predictedShade(eastSlope = -slope, northSlope = 0.0)),
    )

    val measured = cases.map { (name, dem, expected) ->
        val frame = fixture.renderMercator(dem = dem, shaded = true)
        val ink = frame.redAt(SHADING_CENTRE, SHADING_CENTRE)
        val predicted = (SHADING_TILE_GREY * expected).roundToInt()
        println(
            "RenG ground shading readback: $name at slope ${(slope * 1000).roundToInt() / 1000.0} " +
                "measured $ink, predicted $predicted",
        )
        // The instrument has to be able to move in both directions before anything read off it
        // means anything: a factor above 1 brightens, and against a white tile every one of these
        // would read 255 whatever the normal was.
        assertTrue(
            ink in 1..254,
            "$name saturated at $ink: the fixture's tile colour must leave room to darken and to " +
                "brighten, or this case cannot fail",
        )
        assertTrue(
            abs(ink - predicted) <= SHADING_INK_BUDGET,
            "$name must be lit at $predicted of 255 and measured $ink; the normal, the light or " +
                "the normalisation is wrong",
        )
        assertEquals(
            ink,
            frame.redAt(SHADING_WEST_SAMPLE, SHADING_CENTRE),
            "$name is a linear ramp, so every vertex sees the same gradient and the tile shades " +
                "uniformly; the west edge disagrees with the centre",
        )
        ink
    }

    val level = fixture.renderMercator(dem = levelDem, shaded = true).redAt(SHADING_CENTRE, SHADING_CENTRE)
    assertTrue(
        abs(measured[0] - measured[1]) >= SHADING_FACING_MARGIN,
        "a north-facing and a south-facing slope of equal magnitude must differ under a light with " +
            "an azimuth: measured ${measured[1]} and ${measured[0]}. If they match, the normal's " +
            "horizontal component is being dropped.",
    )
    assertTrue(
        abs(measured[2] - measured[3]) >= SHADING_FACING_MARGIN,
        "an east-facing and a west-facing slope must differ too: measured ${measured[3]} and " +
            "${measured[2]}. If they match, the light's own east component is being dropped.",
    )
    assertEquals(
        4,
        measured.toSet().size,
        "four slopes at four bearings under one directional light are four different inks; " +
            "measured $measured against level ground at $level",
    )
    assertTrue(
        measured[1] > level && measured[0] < level,
        "the light comes from the north-west, so a north-facing slope must be brighter than level " +
            "ground and a south-facing one darker: measured ${measured[1]} and ${measured[0]} " +
            "against $level",
    )
}

/**
 * **The case that is not at a symmetry point.** Every other Mercator case here sits on an equatorial
 * tile, where `1 / cos(latitude)` is exactly 1 and a build that never applied it draws the identical
 * frame — the same trap `assertMercatorScalesElevationByLatitudePerVertex` is the answer to on the
 * displacement side.
 *
 * Two independent halves, because the horizontal run is a product of two things and either could be
 * dropped alone:
 *
 * - **Latitude.** The same DEM on a tile at the Mercator clip against one at the equator, both at
 *   the same LOD. A tile at the clip is the same number of *equatorial* metres across and 11.6x
 *   fewer metres of real ground, so the slope is 11.6x steeper and the shading correspondingly
 *   stronger. Dropped, both frames are the equatorial one.
 * - **LOD.** The same DEM on an equatorial tile at LOD 4 against one at LOD 7, which is 8x
 *   narrower. The gentler tile must shade closer to level ground. A run that ignored the tile's own
 *   side — a fixed metres-per-texel, say — draws these two identically. 8x rather than the 64x a LOD
 *   10 tile would give, because at 64x the slope is steep enough to put the surface entirely in
 *   shadow, and an incidence clamped at zero is a **saturated** measurement that stops discriminating.
 *
 * The assertions are inequalities with margins rather than predicted inks, deliberately: the tile at
 * the clip spans latitudes from 85.05 degrees to 82.7, so `cos(latitude)` varies by a third down its
 * own height and the shade with it. What is under test is that the term is applied at all and in the
 * right direction, which no arrangement of the fixture's own arithmetic can accidentally supply.
 */
private fun assertTheLatitudeTermReachesTheHorizontalRun(fixture: ShadingFixture, rampDem: Int) {
    val atClip = fixture.renderMercator(
        dem = rampDem,
        shaded = true,
        lod = SHADING_CLIP_LOD,
        tileY = SHADING_CLIP_TILE_Y,
    ).redAt(SHADING_CENTRE, SHADING_CENTRE)
    val atEquator = fixture.renderMercator(
        dem = rampDem,
        shaded = true,
        lod = SHADING_CLIP_LOD,
        tileY = SHADING_CLIP_EQUATOR_TILE_Y,
    ).redAt(SHADING_CENTRE, SHADING_CENTRE)
    val narrowTile = fixture.renderMercator(
        dem = rampDem,
        shaded = true,
        lod = SHADING_NARROW_LOD,
        tileY = SHADING_NARROW_TILE_Y,
    ).redAt(SHADING_CENTRE, SHADING_CENTRE)
    println(
        "RenG ground shading readback: one north-rising ramp shades $atEquator at the equator, " +
            "$atClip at the mercator clip, and $narrowTile on a tile 8x narrower",
    )

    assertTrue(
        abs(atClip - atEquator) >= SHADING_LATITUDE_MARGIN,
        "at the Mercator clip a tile is 11.6x fewer ground metres across than at the equator, so " +
            "the same DEM is a much steeper slope: expected the two inks to differ by at least " +
            "$SHADING_LATITUDE_MARGIN and measured $atEquator against $atClip. A build with no " +
            "latitude term measures the same number twice and is right at the equator, where every " +
            "other case in this suite sits.",
    )
    assertTrue(
        abs(narrowTile - atEquator) >= SHADING_LATITUDE_MARGIN,
        "a LOD 7 tile is 8x narrower than a LOD 4 one, so the same DEM is a far steeper slope on " +
            "it: expected the two inks to differ by at least $SHADING_LATITUDE_MARGIN and measured " +
            "$atEquator against $narrowTile. A run that ignored the tile's own side measures the " +
            "same number twice.",
    )
}

/**
 * The off-by-one, in the direction opposite to `assertOnlyThePaddedInteriorIsSampled`'s — and the
 * pair is the whole point.
 *
 * The DEM's interior is uniformly [SHADING_LEVEL_METRES] and only its padded **north ring row and
 * west ring column** stand higher. That elevation must reach **no vertex's position**, which is what
 * the displacement suite asserts; it must reach the **normal** at exactly the vertices on the tile's
 * west and north edges, which is this. A central difference at `u = 0` has one tap at padded texel
 * 0, the western neighbour's own last column, because there is nowhere else for it to come from —
 * that ring is the second thing task 5 bought and this is where the second thing is spent.
 *
 * A build whose taps were clamped into the interior reads the tile's own first column twice, gets a
 * difference of zero, and shades the west edge exactly like the flat middle: it fails the first
 * assertion and passes the second, which is why both are here.
 */
private fun assertTheNormalReadsThePaddedRing(fixture: ShadingFixture, levelDem: Int, ringOnlyDem: Int) {
    val level = fixture.renderMercator(dem = levelDem, shaded = true)
    val ringOnly = fixture.renderMercator(dem = ringOnlyDem, shaded = true)
    val westEdge = ringOnly.redAt(SHADING_WEST_SAMPLE, SHADING_CENTRE)
    println(
        "RenG ground shading readback: a raised ring shades the west edge $westEdge against " +
            "${level.redAt(SHADING_WEST_SAMPLE, SHADING_CENTRE)} for a level DEM",
    )
    assertTrue(
        abs(westEdge - level.redAt(SHADING_WEST_SAMPLE, SHADING_CENTRE)) >= SHADING_FACING_MARGIN,
        "the tap at the tile's west edge reads padded texel 0 — the ring — so elevation living only " +
            "there must still tilt that edge's normal: measured $westEdge against " +
            "${level.redAt(SHADING_WEST_SAMPLE, SHADING_CENTRE)}. A tap clamped into the interior " +
            "measures the same number twice.",
    )
    assertEquals(
        level.redAt(SHADING_CENTRE, SHADING_CENTRE),
        ringOnly.redAt(SHADING_CENTRE, SHADING_CENTRE),
        "the middle of the tile sees only interior texels, all of them at the same height, so it " +
            "must shade exactly as level ground does",
    )
}

/**
 * The drawn relief is exaggerated, so the shading has to be: a slope shaded from unexaggerated
 * metres would be lit as a gentler hill than the one it is drawn as.
 *
 * The ramp is the **south-facing** one, deliberately: `dot(normal, light)` falls away from the flat
 * datum faster than it climbs toward it, so a south-facing pair of slopes separates by about 18
 * counts where the north-facing pair separates by 8. A margin with two counts of slack is a margin
 * that measures the driver.
 *
 * **Asserted between 1 and 2 and never at 1 alone.** All six corpus styles declare `exaggeration: 1`
 * — the one value at which an honoured multiplier and a dropped one are the same picture — so a
 * fixture copied from the corpus could not catch this at all, and every other case in this suite
 * runs at [SHADING_EXAGGERATION] for the same reason.
 */
private fun assertExaggerationReachesTheNormal(fixture: ShadingFixture, rampDem: Int) {
    val once = fixture.renderMercator(dem = rampDem, shaded = true, exaggeration = 1.0f)
        .redAt(SHADING_CENTRE, SHADING_CENTRE)
    val twice = fixture.renderMercator(dem = rampDem, shaded = true, exaggeration = SHADING_EXAGGERATION)
        .redAt(SHADING_CENTRE, SHADING_CENTRE)
    val predictedOnce = (SHADING_TILE_GREY * predictedShade(0.0, fixture.equatorialSlope() / 2.0)).roundToInt()
    println(
        "RenG ground shading readback: exaggeration 1 shades $once (predicted $predictedOnce), " +
            "exaggeration $SHADING_EXAGGERATION shades $twice",
    )
    assertTrue(
        abs(once - predictedOnce) <= SHADING_INK_BUDGET,
        "at exaggeration 1 the slope is half the exaggerated one, so the ink must be " +
            "$predictedOnce; measured $once",
    )
    assertTrue(
        abs(twice - once) >= SHADING_FACING_MARGIN,
        "exaggerating the ground by $SHADING_EXAGGERATION must exaggerate the relief it is shaded " +
            "by: measured $once against $twice",
    )
}

/**
 * The shared normal source reaches the sphere's program too, with the sphere's own
 * `cos(latitude)` — which is a local it already computes rather than Mercator's `cosh` term, whose
 * KDoc records it would be exactly 2x wrong at latitude 60 and perfectly right at the equator.
 *
 * Two halves for the same reason the Mercator cases have two: a globe with no relief must be
 * byte-identical shaded and unshaded, and one with relief must not be. The comparison is always
 * shaded-against-unshaded on the **same** DEM, so the radial displacement — which does move pixels
 * on a sphere — is identical in both frames and cancels.
 */
private fun assertTheGlobeGroundIsShadedToo(fixture: ShadingFixture, levelDem: Int, rampDem: Int) {
    val levelPlain = fixture.renderGlobe(dem = levelDem, shaded = false)
    val levelShaded = fixture.renderGlobe(dem = levelDem, shaded = true)
    // A sphere that drew nothing would satisfy every comparison below, which is the shape of a
    // vacuous check rather than a hypothetical: two frames of background are byte-identical whatever
    // the shader does.
    val painted = levelPlain.paintedPixels()
    println("RenG ground shading readback: the globe painted $painted pixels")
    assertTrue(
        painted >= SHADING_GLOBE_PIXEL_FLOOR,
        "the fixture's sphere must actually be drawn before anything measured over it means " +
            "anything: only $painted pixels are painted, against a floor of $SHADING_GLOBE_PIXEL_FLOOR",
    )
    assertFramesIdentical(
        levelPlain,
        levelShaded,
        "a sphere whose DEM has no relief must be byte-identical shaded and unshaded",
    )

    val reliefPlain = fixture.renderGlobe(dem = rampDem, shaded = false)
    val reliefShaded = fixture.renderGlobe(dem = rampDem, shaded = true)
    val differing = reliefPlain.differenceFrom(reliefShaded)
    println("RenG ground shading readback: the globe's relief changed $differing pixels")
    assertTrue(
        differing >= SHADING_GLOBE_PIXEL_FLOOR,
        "a sphere with relief must be shaded by it: only $differing pixels differ between the " +
            "shaded and unshaded globe programs, against a floor of $SHADING_GLOBE_PIXEL_FLOOR",
    )
}

/**
 * The shade a surface of these slopes gets, **re-derived** from ADR 0026's constants rather than
 * transcribed from the shader.
 *
 * `(ambient + diffuse * incidence) / (ambient + diffuse * flatIncidence)` is the ADR's own light
 * divided by what it does to a flat surface; the shader evaluates the algebraically identical
 * `1 + (incidence - flat) * gain`. Two arrangements of one expression, so agreement is evidence.
 *
 * The normal is `(-eastSlope, -northSlope, 1)` normalised: a surface rising toward the east leans
 * away from it.
 */
private fun predictedShade(eastSlope: Double, northSlope: Double): Double {
    val length = sqrt(eastSlope * eastSlope + northSlope * northSlope + 1.0)
    val light = SCENE_LIGHT_DIRECTION_ENU
    val incidence = (-eastSlope * light.x - northSlope * light.y + light.z) / length
    val ambient = SCENE_LIGHT_AMBIENT.toDouble()
    val diffuse = SCENE_LIGHT_DIFFUSE.toDouble()
    val lit = ambient + diffuse * maxOf(incidence, 0.0)
    return lit / (ambient + diffuse * light.z)
}

private class ShadingFixture(
    private val binding: GlBinding,
    private val plainGround: GroundPipeline,
    private val shadedGround: GroundPipeline,
    private val plainGlobe: GlobeGroundPipeline,
    private val shadedGlobe: GlobeGroundPipeline,
    private val target: Int,
    private val colour: Int,
) {
    val globeCamera: ResolvedGlobeCamera = (
        resolveGlobeCamera(
            Camera(
                latitude = SHADING_GLOBE_LATITUDE,
                unwrappedLongitude = SHADING_GLOBE_LONGITUDE,
                zoom = SHADING_GLOBE_ZOOM,
                bearing = 0.0,
                pitch = 0.0,
            ),
            OutputPixelSize(SHADING_READBACK_PIXELS, SHADING_READBACK_PIXELS),
        ) as SpatialOutcome.Success<ResolvedGlobeCamera>
        ).value

    /**
     * The slope [assertEachFacingIsLitByItsOwnAngle] predicts against: one exaggerated ramp step
     * across the two texels a central difference spans, over the ground metres those two texels
     * cover at the equator, where `cos(latitude)` is 1.
     */
    fun equatorialSlope(): Double {
        val rise = 2.0 * SHADING_STEP_METRES * SHADING_EXAGGERATION
        val run = 2.0 * (WORLD_CIRCUMFERENCE_METRES / (1L shl SHADING_LOD).toDouble()) / SHADING_INTERIOR
        return rise / run
    }

    fun renderMercator(
        dem: Int?,
        shaded: Boolean,
        exaggeration: Float = SHADING_EXAGGERATION,
        lod: Int = SHADING_LOD,
        tileY: Int = SHADING_TILE_Y,
    ): ShadingFrame = render {
        drawGround(
            binding = binding,
            pipeline = if (shaded) shadedGround else plainGround,
            tiles = listOf(
                ResolvedGroundTile(
                    modelViewProjection = SHADING_MATRIX,
                    texture = colour,
                    elevation = dem?.let {
                        MercatorGroundTileDem(
                            dem = GroundTileDem(
                                demTexture = it,
                                window = WHOLE_SOURCE_TILE,
                                tileSideMetres = tileSideMetres(lod),
                            ),
                            mercatorY = mercatorTileYEdges(lod, tileY),
                        )
                    },
                ),
            ),
            cellsPerTileSide = SHADING_CELLS,
            elevation = dem?.let {
                MercatorGroundElevationFrame(
                    dem = demUniforms(exaggeration),
                    equatorialLogicalPixelsPerMetre = SHADING_LOGICAL_PIXELS_PER_METRE,
                )
            },
        )
    }

    fun renderGlobe(
        dem: Int?,
        shaded: Boolean,
        exaggeration: Float = SHADING_GLOBE_EXAGGERATION,
    ): ShadingFrame = render {
        drawGlobeGround(
            binding = binding,
            pipeline = if (shaded) shadedGlobe else plainGlobe,
            tiles = (0 until 4).map { index ->
                ResolvedGlobeGroundTile(
                    edges = globeGroundTileEdges(
                        lod = SHADING_GLOBE_LOD,
                        tileY = (index / 2).toLong(),
                        unwrappedX = (index % 2).toLong(),
                    ),
                    texture = colour,
                    elevation = dem?.let {
                        GroundTileDem(
                            demTexture = it,
                            window = WHOLE_SOURCE_TILE,
                            tileSideMetres = tileSideMetres(SHADING_GLOBE_LOD),
                        )
                    },
                )
            },
            unitSphereToClip = composeGlobeGroundUnitSphereToClip(globeCamera),
            cellsPerTileSide = globeGroundCellsPerTileSide(globeCamera, SHADING_GLOBE_LOD),
            elevation = dem?.let {
                GlobeGroundElevationFrame(
                    dem = demUniforms(exaggeration),
                    radialMultiplePerMetre = (
                        globeMetresToLogicalPixels(globeCamera.radiusLogicalPixels) /
                            globeCamera.radiusLogicalPixels
                        ).toFloat(),
                )
            },
        )
    }

    private fun demUniforms(exaggeration: Float): GroundDemUniforms = GroundDemUniforms(
        decode = demDecodeCoefficients(SHADING_ENCODING),
        interiorSizePx = SHADING_INTERIOR,
        exaggeration = exaggeration,
    )

    private inline fun render(draw: () -> Unit): ShadingFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target)
        binding.viewport(0, 0, SHADING_READBACK_PIXELS, SHADING_READBACK_PIXELS)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.clearColor(0f, 0f, 0f, 1f)
        binding.clear(GL_COLOR_BUFFER_BIT)

        // Exactly what `GlFrameDrawer.drawFrame` establishes before `content.draw`.
        binding.frontFace(GL_CCW)
        binding.cullFace(GL_BACK)
        binding.disable(GL_CULL_FACE)
        binding.bindSampler(0, 0)
        binding.bindSampler(1, 0)

        draw()

        val bytes = ByteArray(SHADING_READBACK_PIXELS * SHADING_READBACK_PIXELS * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, SHADING_READBACK_PIXELS, SHADING_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, bytes,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        return ShadingFrame(bytes)
    }
}

/** One read-back frame. Row 0 is the bottom, exactly as `glReadPixels` returns it. */
private class ShadingFrame(val bytes: ByteArray) {
    /**
     * The red channel at [column], [row], as `0..255`.
     *
     * Red alone because the fixture's tile is a neutral grey and the shade is a scalar, so all three
     * channels carry the same number and reading one is reading all of them.
     */
    fun redAt(column: Int, row: Int): Int =
        bytes[(row * SHADING_READBACK_PIXELS + column) * 4].toInt() and 0xff

    /** How many pixels carry ink at all, the fixture's clear colour being black. */
    fun paintedPixels(): Int {
        var painted = 0
        for (index in 0 until SHADING_READBACK_PIXELS * SHADING_READBACK_PIXELS) {
            if ((bytes[index * 4].toInt() and 0xff) > 0) painted += 1
        }
        return painted
    }

    /** How many pixels disagree with [other] in **any** of their four channels. */
    fun differenceFrom(other: ShadingFrame): Int {
        var differing = 0
        for (index in 0 until SHADING_READBACK_PIXELS * SHADING_READBACK_PIXELS) {
            val offset = index * 4
            if ((0..3).any { bytes[offset + it] != other.bytes[offset + it] }) differing += 1
        }
        return differing
    }

    /** `(column, row)` of the first disagreeing pixel, or `null` when there is none. */
    fun firstDifferenceFrom(other: ShadingFrame): Pair<Int, Int>? {
        for (index in 0 until SHADING_READBACK_PIXELS * SHADING_READBACK_PIXELS) {
            val offset = index * 4
            if ((0..3).any { bytes[offset + it] != other.bytes[offset + it] }) {
                return (index % SHADING_READBACK_PIXELS) to (index / SHADING_READBACK_PIXELS)
            }
        }
        return null
    }
}

/**
 * Byte-for-byte, and the **count is asserted before the arrays are**, which is a diagnostic decision
 * bought with a real failure rather than a preference.
 *
 * `assertContentEquals` over a 256 x 256 RGBA frame renders both 262,144-byte arrays into its
 * failure message. Measured here during a mutation pass: Gradle's Kotlin/Native test listener
 * refuses a service message over 1 MB, **loses the event, and fails the build with
 * `Cannot process output: too long teamcity service message` instead of the assertion** — a broken
 * build that reports nothing about what broke. The count fires first with a message a human can
 * read; the array comparison stays underneath it as the exact claim, unreachable in practice and
 * correct in principle, because [ShadingFrame.differenceFrom] compares all four channels and is
 * therefore equivalent to it.
 */
private fun assertFramesIdentical(expected: ShadingFrame, actual: ShadingFrame, message: String) {
    val differing = expected.differenceFrom(actual)
    assertEquals(
        0,
        differing,
        "$message; $differing of ${SHADING_READBACK_PIXELS * SHADING_READBACK_PIXELS} pixels " +
            "differ, the first at ${expected.firstDifferenceFrom(actual)}",
    )
    assertContentEquals(expected.bytes, actual.bytes, message)
}

private fun groundOrFail(
    binding: GlBinding,
    dialect: ShaderDialect,
    programs: GlProgramCache,
    shading: Boolean,
): GroundPipeline = when (
    val result = createGroundPipeline(binding, dialect, programs, terrainShading = shading)
) {
    is GroundPipelineResult.Created -> result.pipeline
    is GroundPipelineResult.Failed ->
        throw AssertionError("the ground pipeline's $dialect programs did not link with shading=$shading")
}

private fun globeOrFail(
    binding: GlBinding,
    dialect: ShaderDialect,
    programs: GlProgramCache,
    shading: Boolean,
): GlobeGroundPipeline = when (
    val result = createGlobeGroundPipeline(binding, dialect, programs, terrainShading = shading)
) {
    is GlobeGroundPipelineResult.Created -> result.pipeline
    is GlobeGroundPipelineResult.Failed ->
        throw AssertionError("the globe ground pipeline's $dialect programs did not link with shading=$shading")
}

private fun createShadingTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, SHADING_READBACK_PIXELS, SHADING_READBACK_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the ground shading readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

/**
 * An opaque [SHADING_TILE_GREY] texel: mid-grey rather than white, so a shade factor above 1 has
 * somewhere to go. See this file's own KDoc — a saturated readback is a readback that cannot fail.
 *
 * Opaque, so the texel is its own premultiplied form and the shaded stage's clamp against alpha is
 * the framebuffer's own clamp rather than a second rule under test here.
 */
private fun createOpaqueGreyTexel(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    val grey = SHADING_TILE_GREY.toByte()
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
        byteArrayOf(grey, grey, grey, -1),
    )
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    return texture
}

/**
 * A padded `(N + 2)` square DEM whose texel `(column, row)` decodes to `metresAt(column, row)`.
 *
 * The encoding is inverted here and then checked **forwards** against [demElevationMetres], so a
 * fixture that encoded a different height from the one it names fails at construction rather than
 * producing a plausible wrong measurement.
 */
private fun createDemTexture(binding: GlBinding, metresAt: (Int, Int) -> Double): Int {
    val texels = ByteArray(SHADING_PADDED * SHADING_PADDED * 4)
    for (row in 0 until SHADING_PADDED) {
        for (column in 0 until SHADING_PADDED) {
            val metres = metresAt(column, row)
            val packed = ((metres + 10_000.0) / 0.1).toLong()
            val red = ((packed shr 16) and 0xff).toInt()
            val green = ((packed shr 8) and 0xff).toInt()
            val blue = (packed and 0xff).toInt()
            val decoded = demElevationMetres(red, green, blue, SHADING_ENCODING)
            assertTrue(
                abs(decoded - metres) < 1e-6,
                "the fixture's own encoder must round-trip through demElevationMetres: asked for " +
                    "$metres m, encoded ($red, $green, $blue), which decodes to $decoded m",
            )
            val offset = (row * SHADING_PADDED + column) * 4
            texels[offset] = red.toByte()
            texels[offset + 1] = green.toByte()
            texels[offset + 2] = blue.toByte()
            texels[offset + 3] = -1
        }
    }
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, SHADING_PADDED, SHADING_PADDED, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels,
    )
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    return texture
}

private fun tileSideMetres(lod: Int): Float =
    (WORLD_CIRCUMFERENCE_METRES / (1L shl lod).toDouble()).toFloat()

/** 256, matching `runGroundDisplacementReadback` so the globe fits inside the frame with room. */
internal const val SHADING_READBACK_PIXELS: Int = 256

private const val SHADING_CENTRE: Int = SHADING_READBACK_PIXELS / 2

/**
 * A column two pixels inside the drawn tile's west edge, which the fixture's matrix puts at column
 * 64: well inside the grid's first cell, where a tap on the padded ring is the only thing that can
 * tilt a normal.
 */
private const val SHADING_WEST_SAMPLE: Int = 66

private const val SHADING_INTERIOR: Int = 8
private const val SHADING_PADDED: Int = SHADING_INTERIOR + 2

/** Mapbox, the specification default five of the corpus's six terrain styles take. */
private val SHADING_ENCODING: DemEncoding = DemEncoding.MAPBOX

/**
 * The tile's own colour, and the number is chosen so the instrument can move both ways.
 *
 * 128 of 255 is `0.5019...`, so the shade factor's whole `[0.43, 1.24]` range lands strictly inside
 * `(0, 255)` — about 55 at the darkest and 158 at the brightest. Against white every highlight would
 * clip and half the range would be unmeasurable; against black nothing would be.
 */
private const val SHADING_TILE_GREY: Int = 128

/**
 * 1,200 metres per texel of ramp, which at [SHADING_EXAGGERATION] on a LOD 10 tile is a slope of
 * **0.49** — and that number is the fixture's one real choice.
 *
 * `dot(normal, light)` is stationary where the normal points at the light, which for a north-facing
 * slope is `L.y / L.z = 0.906`: a fixture there would barely move if the slope were wrong by half.
 * Zero is the other stationary point. 0.49 is on the steep part of the curve, away from both.
 */
private const val SHADING_STEP_METRES: Double = 1_200.0

/** The uniform height [assertShadingLeavesGroundWithNoReliefAlone] uses, and it is **not** zero. */
private const val SHADING_LEVEL_METRES: Double = 768.0

/** How far the padded ring stands above the interior in the ring-only fixture. */
private const val SHADING_RING_STEP_METRES: Double = 4_000.0

/**
 * The ramp [assertTheLatitudeTermReachesTheHorizontalRun] uses, six and a half times the ordinary
 * one because a LOD 4 tile is 64x wider and the same ramp would be a slope of 0.008 on it.
 */
private const val SHADING_COARSE_STEP_METRES: Double = 8_000.0

/**
 * 2, and never 1, because all six corpus styles declare 1 and at 1 an honoured exaggeration and a
 * dropped one draw the identical frame.
 */
private const val SHADING_EXAGGERATION: Float = 2.0f

/** Four cells a side, so a per-vertex normal is genuinely computed more than once across the tile. */
private const val SHADING_CELLS: Int = 4

/** LOD 10, row 512: the tile immediately south of the equator, where `1 / cos(latitude)` is 1. */
private const val SHADING_LOD: Int = 10
private const val SHADING_TILE_Y: Int = 512

/** LOD 4, row 0: the tile whose north edge is the Mercator clip, where the same factor is 11.59. */
private const val SHADING_CLIP_LOD: Int = 4
private const val SHADING_CLIP_TILE_Y: Int = 0

/** LOD 4, row 8: the same-sized tile at the equator, so only the latitude differs. */
private const val SHADING_CLIP_EQUATOR_TILE_Y: Int = 8

/**
 * LOD 7, row 64: an equatorial tile 8x narrower than the LOD 4 one, so only the tile's own side
 * differs. Not LOD 10, whose 64x would drive the same ramp past the light's own horizon and clamp
 * the incidence to zero — a saturated reading discriminates nothing.
 */
private const val SHADING_NARROW_LOD: Int = 7
private const val SHADING_NARROW_TILE_Y: Int = 64

/**
 * Eight counts of 255. The differences this case actually produces are about 32 (latitude) and 26
 * (LOD), and a build missing either term produces exactly 0.
 */
private const val SHADING_LATITUDE_MARGIN: Int = 8

/**
 * A real Mercator metre scale rather than 1, on the displacement suite's own reasoning: at 1 a build
 * that used metres directly as logical pixels draws the identical frame. Nothing here measures a
 * displacement, but the value travels through the same uniform and a symmetry point is a symmetry
 * point.
 */
private const val SHADING_LOGICAL_PIXELS_PER_METRE: Float = 0.5f

/** Two counts of 255, which is a rasteriser's rounding of the last bit and nothing else. */
private const val SHADING_INK_BUDGET: Int = 2

/**
 * Six counts of 255, comfortably above [SHADING_INK_BUDGET] and far below every difference this
 * suite's fixtures actually produce — the closest pair measures about 15 apart.
 */
private const val SHADING_FACING_MARGIN: Int = 6

/** The whole source tile: no overzoom, so the requested tile is the DEM tile. */
private val WHOLE_SOURCE_TILE: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)

/**
 * `clip.x = x`, `clip.y = y`, **`clip.z = 0`**, `clip.w = 1`, column-major — and the zero is the
 * fixture's whole design.
 *
 * Map-space `z` is what displacement produces, and this matrix discards it, so the ground draws in
 * exactly the same place whatever the DEM says. Every difference this suite measures is therefore a
 * difference in shading and cannot be a difference in geometry.
 * `runGroundDisplacementReadback`'s matrix does the opposite, folding `z` into rows so displacement
 * can be counted in whole pixels; between them the two consequences of one DEM are measured one at
 * a time.
 *
 * The tile's unit square lands on clip `[-0.5, 0.5]`, which is the middle half of the frame:
 * columns and rows 64 through 191, with both boundaries half a pixel from the nearest pixel centre
 * so no rasteriser's fill rule is in question.
 */
private val SHADING_MATRIX: FloatArray = floatArrayOf(
    1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f,
)

/**
 * 120 kilometres per texel of ramp, and 4x exaggeration on top, because a LOD 1 tile is **twenty
 * thousand kilometres** across.
 *
 * The Mercator fixture's 1.2 km per texel is a slope of 0.49 on a LOD 10 tile and a slope of
 * **0.001** here, which shades no pixel at all — measured, and it is what the first version of this
 * case reported: 0 differing pixels against a build that was shading the globe perfectly well.
 * Scaling a real effect into the measurable range is exactly what
 * `assertTheGlobeDisplacesRadiallyByTheDeclaredFraction`'s exaggeration of 40 does, and for the same
 * reason: a sphere is large and a mountain is not.
 *
 * The pair keeps the ramp inside Mapbox's own ceiling of about 1,667 km, and the displacement it
 * also causes is identical in the shaded and unshaded frames, so it cancels out of the comparison.
 */
private const val SHADING_GLOBE_STEP_METRES: Double = 120_000.0
private const val SHADING_GLOBE_EXAGGERATION: Float = 4.0f

private const val SHADING_GLOBE_LATITUDE: Double = 8.0
private const val SHADING_GLOBE_LONGITUDE: Double = 40.0
private const val SHADING_GLOBE_ZOOM: Double = 0.5
private const val SHADING_GLOBE_LOD: Int = 1

/**
 * A thousand pixels of a 65,536-pixel frame, which is under a sixth of the drawn sphere: low enough
 * that a rasteriser's edge cannot reach it and high enough that a single stray pixel cannot.
 */
private const val SHADING_GLOBE_PIXEL_FLOOR: Int = 1_000
