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
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Cycle E-terrain task 8's own gate: does a DEM actually move the ground, by the right amount, in
 * both projections, and does it leave a ground with no relief alone.** The cycle's *suite* gate —
 * limb-and-relief, seams, cross-mode agreement — is task 12's and is deliberately not here; these
 * are the assertions task 8 needs to claim its own work is observable.
 *
 * **The whole suite exists because none of the six preceding tasks reached a pixel.** Elevation
 * decode, the overzoom window, the padded ring, the granularity rule, the acquisition and the
 * diagnostics are all unit-tested against pure functions and a recording binding, and every one of
 * them would go on passing if the vertex shader ignored the DEM entirely.
 *
 * ## The five Mercator cases and what each one alone would survive
 *
 * - [assertAFlatDemDrawsExactlyWhatTerrainOffDraws] — **the strongest negative available**, and the
 *   only case that catches displacement leaking where it should not. A DEM whose every texel decodes
 *   to exactly zero metres must render **byte-identical** to a frame drawn with no DEM at all. It is
 *   also the case that would pass vacuously if displacement were entirely dead, which is why
 *   [assertAKnownDemLiftsTheGroundByAKnownNumberOfPixels] sits immediately after it.
 * - [assertAKnownDemLiftsTheGroundByAKnownNumberOfPixels] — a uniform 768 m DEM lifts the drawn
 *   ground by exactly 12 pixels, which is arithmetic the fixture's own KDoc performs rather than a
 *   number read off a run. It fails if metres are used directly as logical pixels (the lift would be
 *   768 pixels and the ground would leave the frame), if the scale is dropped, or if the fetch never
 *   happens.
 * - [assertExaggerationTwoDoublesTheDisplacementExactly] — **never asserted at 1**, because all six
 *   corpus styles declare `1` and at 1 an honoured exaggeration and an ignored one are the same
 *   picture. This is the only case that can tell them apart.
 * - [assertOnlyThePaddedInteriorIsSampled] — the off-by-one. The DEM's interior is uniformly zero and
 *   its **ring**'s north row and west column carry 768 m, so a correct sampler renders the flat frame
 *   exactly and a sampler that forgot the padded `+1` lifts the tile's north and west edges. Half a
 *   texel of shift is what puts every closed seam back.
 *
 * - [assertMercatorScalesElevationByLatitudePerVertex] — the case that is **not** at a symmetry
 *   point. Every other Mercator case above sits on an equatorial tile, where Mercator's
 *   `1 / cos(latitude)` altitude scale is exactly 1 and a build that dropped it draws the identical
 *   frame. This one sits at the Mercator clip, where the same factor is `cosh(PI) = 11.59`.
 *
 * ## The globe case
 *
 * [assertTheGlobeDisplacesRadiallyByTheDeclaredFraction] measures the drawn sphere's *diameter* on
 * the frame's centre row and centre column. Displacement on a globe is radial, so a uniform DEM must
 * grow the silhouette by exactly `1 + metres / a` — and **not** by Mercator's
 * `1 / cos(latitude)`-distorted amount, which at this fixture's latitude would be a different number.
 * It fails if the radial scale is left per frame (the sphere would not grow at all).
 *
 * **The exaggeration there is 40 and 80 rather than 2, and that is a measurement decision.** A real
 * summit is about 8,848 m against a 6,378 km radius, which is 0.14 per cent — a fifth of a pixel on
 * this fixture's disc, and unmeasurable. Exaggeration is finite and unclamped by decision (design
 * section 10), so scaling the effect into the measurable range is legal rather than a workaround, and
 * asserting the *ratio* between 40 and 80 keeps the exaggeration itself under test.
 *
 * **What this suite does not claim.** Nothing about fidelity — that terrain looks like the terrain —
 * which is pixel verification and stays Cycle J's. Nothing about seams between adjacent tiles, which
 * is task 12's. And nothing about depth: this suite's target carries a colour attachment and no
 * depth one, so ADR 0039's conditional write -- which task 9 landed, and which every displaced frame
 * below now issues -- is discarded by the framebuffer before it can reach a pixel here.
 * `runGroundDepthReadback` is the suite that attaches a depth buffer and measures it.
 */
internal fun runGroundDisplacementReadback(binding: GlBinding, dialect: ShaderDialect) {
    val target = createDisplacementTarget(binding)
    val colour = createOpaqueWhiteTexel(binding)
    val programs = GlProgramCache()
    val ground = when (val result = createGroundPipeline(binding, dialect, programs)) {
        is GroundPipelineResult.Created -> result.pipeline
        is GroundPipelineResult.Failed ->
            throw AssertionError("the ground pipeline's $dialect programs did not link on this driver")
    }
    val globe = when (val result = createGlobeGroundPipeline(binding, dialect, programs)) {
        is GlobeGroundPipelineResult.Created -> result.pipeline
        is GlobeGroundPipelineResult.Failed ->
            throw AssertionError("the globe ground pipeline's $dialect programs did not link on this driver")
    }
    println("RenG ground displacement readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect")

    val flat = createDemTexture(binding) { _, _ -> 0.0 }
    val raised = createDemTexture(binding) { _, _ -> FIXTURE_ELEVATION_METRES }
    val ringOnly = createDemTexture(binding) { column, row ->
        if (column == 0 || row == 0) FIXTURE_ELEVATION_METRES else 0.0
    }
    val tall = createDemTexture(binding) { _, _ -> CLIP_FIXTURE_ELEVATION_METRES }
    try {
        val fixture = DisplacementFixture(binding, ground, globe, target, colour)
        assertAFlatDemDrawsExactlyWhatTerrainOffDraws(fixture, flat)
        assertAKnownDemLiftsTheGroundByAKnownNumberOfPixels(fixture, flat, raised)
        assertExaggerationTwoDoublesTheDisplacementExactly(fixture, flat, raised)
        assertOnlyThePaddedInteriorIsSampled(fixture, flat, ringOnly)
        assertMercatorScalesElevationByLatitudePerVertex(fixture, flat, tall)
        assertTheGlobeDisplacesRadiallyByTheDeclaredFraction(fixture, flat, raised)
    } finally {
        intArrayOf(flat, raised, ringOnly, tall, colour).forEach { binding.deleteTextures(1, intArrayOf(it)) }
        deleteGlobeGroundPipeline(binding, programs, globe)
        deleteGroundPipeline(binding, programs, ground)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * A DEM every texel of which decodes to exactly `0.0` metres must draw the frame RenG has drawn
 * since `0.3.0`, byte for byte.
 *
 * **Byte-identical rather than close, and the two frames run two different programs.** The
 * comparison is `drawGround` with an elevation frame against `drawGround` with none, at the same
 * granularity so that nothing but displacement differs. That is the point of compiling the
 * displacing ground as a second program rather than as a mode of the first: this assertion is a
 * comparison between two real draws instead of an argument about whether a driver evaluates
 * `0.0 * scale` to exactly zero.
 *
 * **Zero metres, not "some constant height".** A uniform *non-zero* DEM is a plane at that altitude,
 * which a perspective camera draws larger than the plane at sea level — that is displacement working,
 * not leaking, and [assertAKnownDemLiftsTheGroundByAKnownNumberOfPixels] measures exactly it. Only
 * zero is the terrain-off picture.
 *
 * The second half compares against granularity **1**, which is what a frame with no terrain actually
 * draws, so the claim covers the picture a consumer sees rather than only the isolated variable.
 */
private fun assertAFlatDemDrawsExactlyWhatTerrainOffDraws(fixture: DisplacementFixture, flatDem: Int) {
    val terrainOff = fixture.renderMercator(dem = null, cellsPerTileSide = FIXTURE_CELLS)
    val flat = fixture.renderMercator(
        dem = flatDem,
        cellsPerTileSide = FIXTURE_CELLS,
        exaggeration = FIXTURE_EXAGGERATION,
    )
    assertFramesIdentical(
        terrainOff,
        flat,
        "a DEM whose every texel decodes to zero metres must draw the flat ground byte-identically",
    )

    val undisplacedQuad = fixture.renderMercator(dem = null, cellsPerTileSide = 1)
    assertFramesIdentical(
        undisplacedQuad,
        flat,
        "a flat DEM must also match the single-cell quad a frame with no terrain draws",
    )
}

/**
 * The arithmetic, performed here rather than measured and then written down.
 *
 * The fixture's tile sits at LOD 10, row 512, whose northern edge is Mercator `y = 0.5` — the equator,
 * where `1 / cos(latitude)` is exactly `1` — and whose southern edge is `y = 0.5009765625`, where it
 * is `1.0000188`. Every vertex therefore scales its metres by
 * [FIXTURE_LOGICAL_PIXELS_PER_METRE] alone, to within two parts in a hundred thousand.
 *
 * The fixture's matrix is `clip.x = x`, `clip.y = y + 2^-12 * z`, which puts the tile's unit square
 * across the middle half of the frame and turns map-space `z` into vertical clip offset. A frame is
 * [DISPLACEMENT_READBACK_PIXELS] = 256 pixels, so one unit of clip `y` is 128 pixels, and:
 *
 * ```
 * lift = 768 m * 0.5 px/m * 2^-12 clip/px * 128 px/clip = 12 pixels, exactly
 * ```
 *
 * The flat tile's northern edge sits at clip `y = 0.5`, which is window row 192.0 — a boundary half
 * a pixel from both neighbouring pixel centres, so no rasteriser's fill rule is in question — and
 * the highest painted row is therefore 191. Lifted, the edge is at row 204.0 and the highest painted
 * row is 203. **The assertion is the difference, 12**, so the fixture's own edge convention cancels
 * and only the displacement is under test.
 *
 * Every one of those factors is a power of two or an exact integer in `Float`, so nothing here
 * depends on rounding. A build that used metres as logical pixels directly would lift by 768 pixels
 * and paint nothing at all; one that dropped the scale would lift by zero.
 */
private fun assertAKnownDemLiftsTheGroundByAKnownNumberOfPixels(
    fixture: DisplacementFixture,
    flatDem: Int,
    raisedDem: Int,
) {
    val flat = fixture.renderMercator(dem = flatDem, exaggeration = 1.0f)
    val raised = fixture.renderMercator(dem = raisedDem, exaggeration = 1.0f)
    val lift = raised.highestPaintedRow(FIXTURE_SAMPLE_COLUMN) - flat.highestPaintedRow(FIXTURE_SAMPLE_COLUMN)
    println("RenG ground displacement readback: ${FIXTURE_ELEVATION_METRES} m lifted the ground $lift px")
    assertEquals(
        EXPECTED_LIFT_PIXELS,
        lift,
        "a uniform ${FIXTURE_ELEVATION_METRES} m DEM must lift the ground by exactly " +
            "$EXPECTED_LIFT_PIXELS pixels; measured $lift " +
            "(flat top row ${flat.highestPaintedRow(FIXTURE_SAMPLE_COLUMN)}, " +
            "raised top row ${raised.highestPaintedRow(FIXTURE_SAMPLE_COLUMN)})",
    )
}

/**
 * **Exaggeration is asserted at 2 and never at 1**, because 1 is the value at which an honoured
 * multiplier and a dropped one draw the identical frame — and it is the value all six corpus styles
 * declare, so the corpus cannot tell the two apart and neither could a fixture copied from it.
 *
 * `2 * 12 = 24` pixels, the same exact arithmetic
 * [assertAKnownDemLiftsTheGroundByAKnownNumberOfPixels] performs, so this is an equality rather than
 * an inequality: a build that clamped exaggeration, or applied it to the wrong side of the metre
 * conversion, or applied it twice, fails here with a specific wrong number rather than merely
 * failing to be "greater".
 */
private fun assertExaggerationTwoDoublesTheDisplacementExactly(
    fixture: DisplacementFixture,
    flatDem: Int,
    raisedDem: Int,
) {
    val flat = fixture.renderMercator(dem = flatDem, exaggeration = 2.0f)
    val once = fixture.renderMercator(dem = raisedDem, exaggeration = 1.0f)
    val twice = fixture.renderMercator(dem = raisedDem, exaggeration = 2.0f)
    val base = flat.highestPaintedRow(FIXTURE_SAMPLE_COLUMN)
    val liftOnce = once.highestPaintedRow(FIXTURE_SAMPLE_COLUMN) - base
    val liftTwice = twice.highestPaintedRow(FIXTURE_SAMPLE_COLUMN) - base
    println("RenG ground displacement readback: exaggeration 1 lifted $liftOnce px, exaggeration 2 lifted $liftTwice px")
    assertEquals(
        EXPECTED_LIFT_PIXELS,
        liftOnce,
        "the exaggeration-1 lift must still be $EXPECTED_LIFT_PIXELS pixels; measured $liftOnce",
    )
    assertEquals(
        2 * EXPECTED_LIFT_PIXELS,
        liftTwice,
        "exaggeration 2 must double the displacement exactly: expected ${2 * EXPECTED_LIFT_PIXELS} " +
            "pixels, measured $liftTwice",
    )
    // The zero-metre DEM is drawn at exaggeration 2 as well, so a build that multiplied the *offset*
    // rather than the elevation -- which would move sea level -- cannot hide inside the difference.
    assertEquals(
        flat.highestPaintedRow(FIXTURE_SAMPLE_COLUMN),
        fixture.renderMercator(dem = flatDem, exaggeration = 1.0f)
            .highestPaintedRow(FIXTURE_SAMPLE_COLUMN),
        "exaggerating zero metres must still be zero metres",
    )
}

/**
 * The padded off-by-one, isolated so that it and nothing else decides the outcome.
 *
 * The DEM's whole interior decodes to zero and only its padded **north ring row and west ring
 * column** carry [FIXTURE_ELEVATION_METRES]. A correct sampler maps grid `u = 0` to padded texel 1
 * and `v = 0` to padded row 1 — the interior's own first column and row — so it reads zero
 * everywhere and draws the flat frame byte for byte. A sampler that dropped the `+1` reads the ring
 * at exactly those two edges and lifts the tile's north and west sides by 12 pixels.
 *
 * **The east and south ring texels are deliberately left at zero**, and that asymmetry is the DEM
 * grid's rather than the fixture's: the grid is edge-exclusive, so `u = 1` genuinely does read the
 * east ring — that is how two tiles either side of a boundary read the same source value and the
 * seam closes. Putting elevation there too would have made the correct and the off-by-one renders
 * differ only in *which* edges rose, which is a weaker signal than one being flat.
 */
private fun assertOnlyThePaddedInteriorIsSampled(
    fixture: DisplacementFixture,
    flatDem: Int,
    ringOnlyDem: Int,
) {
    val flat = fixture.renderMercator(dem = flatDem, exaggeration = 1.0f)
    val ringOnly = fixture.renderMercator(dem = ringOnlyDem, exaggeration = 1.0f)
    assertFramesIdentical(
        flat,
        ringOnly,
        "elevation living only in the padded ring's north row and west column must not reach the " +
            "ground: the grid vertex at u = 0 is padded texel 1, not texel 0",
    )
}

/**
 * On a sphere the displacement is radial, so a uniform DEM inflates the drawn globe and nothing else.
 *
 * **The measurement is the silhouette's diameter on the frame's centre row and centre column**, which
 * is an integer pixel count needing no fill-rule tolerance, taken through the sphere's centre because
 * a pitch-zero camera aimed at its own anchor projects that centre to the frame's centre.
 *
 * The predicted ratio is `1 + exaggeration * metres / a`, computed here from
 * `globeMetresToLogicalPixels(R) / R` — the same quotient the production path uses — rather than from
 * a literal, so a change to the globe's metre scale moves both together. **It is emphatically not
 * Mercator's number**: at this fixture's latitudes a `1 / cos(latitude)` term would inflate the
 * sphere by a different, larger factor, and copying Mercator's conversion is the exact mistake
 * `globeMetresToLogicalPixels`' own KDoc warns is 2x wrong at latitude 60 and invisible at the
 * equator.
 *
 * Both exaggerations are asserted, and the second is twice the first, so an ignored exaggeration
 * fails here as well as in the Mercator case.
 */
private fun assertTheGlobeDisplacesRadiallyByTheDeclaredFraction(
    fixture: DisplacementFixture,
    flatDem: Int,
    raisedDem: Int,
) {
    val terrainOff = fixture.renderGlobe(dem = null)
    val flat = fixture.renderGlobe(dem = flatDem, exaggeration = GLOBE_EXAGGERATION)
    assertFramesIdentical(
        terrainOff,
        flat,
        "a globe frame whose DEM is uniformly zero must draw the undisplaced sphere byte-identically",
    )

    val base = flat.silhouetteDiameters()
    // The measurement has to be able to move before anything computed over it means anything. A disc
    // larger than the frame reports the frame's own width whatever the elevation, which is exactly
    // how the first version of this case stayed green against a ground that did not displace at all.
    base.forEachIndexed { index, diameter ->
        assertTrue(
            diameter in MINIMUM_GLOBE_DIAMETER_PIXELS until DISPLACEMENT_READBACK_PIXELS,
            "the fixture's sphere must be wholly inside the frame with room to grow, or its " +
                "diameter saturates and this case cannot fail: axis $index measured $diameter of " +
                "$DISPLACEMENT_READBACK_PIXELS",
        )
    }

    val camera = fixture.globeCamera
    val radius = camera.radiusLogicalPixels
    val radialPerMetre = globeMetresToLogicalPixels(radius) / radius
    listOf(GLOBE_EXAGGERATION, 2.0f * GLOBE_EXAGGERATION).forEach { exaggeration ->
        val grown = fixture.renderGlobe(dem = raisedDem, exaggeration = exaggeration).silhouetteDiameters()
        val fraction = exaggeration * FIXTURE_ELEVATION_METRES * radialPerMetre
        val ratio = projectedLimbRadius(radius * (1.0 + fraction), camera) /
            projectedLimbRadius(radius, camera)
        listOf("row" to 0, "column" to 1).forEach { (axis, index) ->
            val expected = base[index] * ratio
            val measured = grown[index].toDouble()
            println(
                "RenG ground displacement readback: globe $axis diameter ${base[index]} -> " +
                    "${grown[index]} px at exaggeration $exaggeration, expected " +
                    "${(expected * 100.0).toInt() / 100.0} (radial fraction $fraction)",
            )
            assertTrue(
                abs(measured - expected) <= GLOBE_DIAMETER_BUDGET_PIXELS,
                "at exaggeration $exaggeration the sphere's $axis diameter must be $expected px; " +
                    "measured $measured (${base[index]} -> ${grown[index]}). An undisplaced ground " +
                    "measures ${base[index]}, which misses by ${abs(base[index] - expected)} px.",
            )
        }
    }
}

/**
 * Where a sphere of radius [sphereRadius] puts its limb on screen, in units of the camera's focal
 * length — `sin/cos` of the half-angle it subtends, which is `s / sqrt(1 - s^2)` for `s = r / d`.
 *
 * **The projected disc grows faster than the sphere does, and ignoring that is a systematic error
 * rather than noise.** A perspective camera at distance `d` sees a sphere's limb at angular radius
 * `asin(r / d)`, and `tan(asin(x))` is superlinear: at this fixture's `r / d` of about 0.15, a
 * 12 per cent radial displacement projects as about 12.4 per cent of disc. Predicting `1 + fraction`
 * instead left the measurement about a pixel and a half short on every reading — small enough to
 * hide inside a fill-rule budget, which is exactly how a tolerance stops being an instrument.
 *
 * The distance is `|eyeGlobeFixed|`, the eye's distance from the sphere's **centre**, rather than
 * `cameraDistanceLogicalPixels`, which is measured from the ground anchor.
 */
private fun projectedLimbRadius(sphereRadius: Double, camera: ResolvedGlobeCamera): Double {
    val eye = camera.eyeGlobeFixed
    val distance = sqrt(eye.x * eye.x + eye.y * eye.y + eye.z * eye.z)
    val sine = sphereRadius / distance
    return sine / sqrt(1.0 - sine * sine)
}

/**
 * **Mercator scales elevation by `1 / cos(latitude)`, evaluated per vertex, and this is the case that
 * measures it rather than reading it off the shader text.**
 *
 * Every other Mercator case here sits on a tile at the equator, where that factor is exactly `1` — so
 * every one of them would pass unchanged against a build that dropped the latitude term entirely.
 * That is the same symmetry-point trap F-2 found seven of, and this case is the fixture that is not
 * at the symmetry point: a tile at LOD 4, row 0, whose northern edge is the Mercator clip at
 * 85.0511 degrees, where `1 / cos(latitude) = cosh(PI) = 11.5919...`.
 *
 * The arithmetic, again performed rather than measured:
 *
 * ```
 * lift = 800 m * 0.5 px/m * 2^-16 clip/px * 128 px/clip * cosh(PI) = 9.056 pixels
 * ```
 *
 * against `0.781` pixels if the latitude term were dropped — a row difference of **9** against
 * **0 or 1**, which is why the assertion is a range rather than an equality: the edge lands at window
 * row 201.06 and a rasteriser owns the last 0.06, while nothing owns the gap between 9 and 1.
 *
 * The matrix is a sixteenth of the other cases' vertical scale, because at the fixture's own scale
 * this lift would be 139 pixels and leave a 128-pixel frame entirely.
 */
private fun assertMercatorScalesElevationByLatitudePerVertex(
    fixture: DisplacementFixture,
    flatDem: Int,
    tallDem: Int,
) {
    val flat = fixture.renderMercator(
        dem = flatDem,
        matrix = CLIP_FIXTURE_MATRIX,
        tileY = CLIP_FIXTURE_TILE_Y,
        lod = CLIP_FIXTURE_LOD,
    )
    val raised = fixture.renderMercator(
        dem = tallDem,
        matrix = CLIP_FIXTURE_MATRIX,
        tileY = CLIP_FIXTURE_TILE_Y,
        lod = CLIP_FIXTURE_LOD,
    )
    val lift = raised.highestPaintedRow(FIXTURE_SAMPLE_COLUMN) - flat.highestPaintedRow(FIXTURE_SAMPLE_COLUMN)
    println(
        "RenG ground displacement readback: at the mercator clip ${CLIP_FIXTURE_ELEVATION_METRES} m " +
            "lifted the ground $lift px, against 1 px at the equator on the same matrix",
    )
    assertTrue(
        lift in EXPECTED_CLIP_LIFT_PIXELS,
        "at the Mercator clip one metre of elevation is 11.59 logical pixels of map-space z, not one: " +
            "expected a lift in $EXPECTED_CLIP_LIFT_PIXELS pixels and measured $lift. A build with no " +
            "latitude term measures 0 or 1 here and is correct at the equator, where every other case " +
            "in this suite sits.",
    )
}

private class DisplacementFixture(
    private val binding: GlBinding,
    private val ground: GroundPipeline,
    private val globe: GlobeGroundPipeline,
    private val target: Int,
    private val colour: Int,
) {
    val globeCamera: ResolvedGlobeCamera = (
        resolveGlobeCamera(
            Camera(
                latitude = GLOBE_FIXTURE_LATITUDE,
                unwrappedLongitude = GLOBE_FIXTURE_LONGITUDE,
                zoom = GLOBE_FIXTURE_ZOOM,
                bearing = 0.0,
                pitch = 0.0,
            ),
            OutputPixelSize(DISPLACEMENT_READBACK_PIXELS, DISPLACEMENT_READBACK_PIXELS),
        ) as SpatialOutcome.Success<ResolvedGlobeCamera>
        ).value

    fun renderMercator(
        dem: Int?,
        cellsPerTileSide: Int = FIXTURE_CELLS,
        exaggeration: Float = FIXTURE_EXAGGERATION,
        matrix: FloatArray = FIXTURE_MATRIX,
        lod: Int = FIXTURE_LOD,
        tileY: Int = FIXTURE_TILE_Y,
    ): DisplacementFrame = render {
        drawGround(
            binding = binding,
            pipeline = ground,
            tiles = listOf(
                ResolvedGroundTile(
                    modelViewProjection = matrix,
                    texture = colour,
                    elevation = dem?.let {
                        MercatorGroundTileDem(
                            dem = GroundTileDem(
                                demTexture = it,
                                window = WHOLE_TILE_WINDOW,
                                tileSideMetres = tileSideMetres(lod),
                            ),
                            mercatorY = mercatorTileYEdges(lod, tileY),
                        )
                    },
                ),
            ),
            cellsPerTileSide = cellsPerTileSide,
            elevation = dem?.let {
                MercatorGroundElevationFrame(
                    dem = demUniforms(exaggeration),
                    equatorialLogicalPixelsPerMetre = FIXTURE_LOGICAL_PIXELS_PER_METRE,
                )
            },
        )
    }

    fun renderGlobe(dem: Int?, exaggeration: Float = GLOBE_EXAGGERATION): DisplacementFrame = render {
        drawGlobeGround(
            binding = binding,
            pipeline = globe,
            tiles = (0 until 4).map { index ->
                ResolvedGlobeGroundTile(
                    edges = globeGroundTileEdges(
                        lod = GLOBE_FIXTURE_LOD,
                        tileY = (index / 2).toLong(),
                        unwrappedX = (index % 2).toLong(),
                    ),
                    texture = colour,
                    elevation = dem?.let {
                        GroundTileDem(
                            demTexture = it,
                            window = WHOLE_TILE_WINDOW,
                            tileSideMetres = tileSideMetres(GLOBE_FIXTURE_LOD),
                        )
                    },
                )
            },
            unitSphereToClip = composeGlobeGroundUnitSphereToClip(globeCamera),
            cellsPerTileSide = globeGroundCellsPerTileSide(globeCamera, GLOBE_FIXTURE_LOD),
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
        decode = demDecodeCoefficients(FIXTURE_ENCODING),
        interiorSizePx = DEM_INTERIOR_TEXELS,
        exaggeration = exaggeration,
    )

    private inline fun render(draw: () -> Unit): DisplacementFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target)
        binding.viewport(0, 0, DISPLACEMENT_READBACK_PIXELS, DISPLACEMENT_READBACK_PIXELS)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.clearColor(0f, 0f, 0f, 1f)
        binding.clear(GL_COLOR_BUFFER_BIT)

        // Exactly what `GlFrameDrawer.drawFrame` establishes before `content.draw`, which is why
        // neither ground pass restates the winding or the cull mode (ADR 0038).
        binding.frontFace(GL_CCW)
        binding.cullFace(GL_BACK)
        binding.disable(GL_CULL_FACE)
        binding.bindSampler(0, 0)
        binding.bindSampler(1, 0)

        draw()

        val bytes = ByteArray(DISPLACEMENT_READBACK_PIXELS * DISPLACEMENT_READBACK_PIXELS * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, DISPLACEMENT_READBACK_PIXELS, DISPLACEMENT_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, bytes,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        return DisplacementFrame(bytes)
    }
}

/**
 * One read-back frame. **Row 0 is the bottom**, exactly as `glReadPixels` returns it and deliberately
 * un-flipped: displacement raises clip `y`, so a bigger row index is a higher point and no sign has
 * to be reasoned about twice.
 */
private class DisplacementFrame(val bytes: ByteArray) {
    fun isPainted(column: Int, row: Int): Boolean {
        val offset = (row * DISPLACEMENT_READBACK_PIXELS + column) * 4
        return (bytes[offset].toInt() and 0xff) > 128
    }

    /** The highest painted row in [column], or `-1` when the column is empty. */
    fun highestPaintedRow(column: Int): Int {
        for (row in DISPLACEMENT_READBACK_PIXELS - 1 downTo 0) {
            if (isPainted(column, row)) return row
        }
        return -1
    }

    /** `(painted pixels on the centre row, painted pixels on the centre column)`. */
    fun silhouetteDiameters(): IntArray {
        val centre = DISPLACEMENT_READBACK_PIXELS / 2
        var acrossRow = 0
        var downColumn = 0
        for (index in 0 until DISPLACEMENT_READBACK_PIXELS) {
            if (isPainted(index, centre)) acrossRow += 1
            if (isPainted(centre, index)) downColumn += 1
        }
        return intArrayOf(acrossRow, downColumn)
    }

    fun differenceFrom(other: DisplacementFrame): Int {
        var differing = 0
        for (index in 0 until DISPLACEMENT_READBACK_PIXELS * DISPLACEMENT_READBACK_PIXELS) {
            val offset = index * 4
            if ((0..2).any { bytes[offset + it] != other.bytes[offset + it] }) differing += 1
        }
        return differing
    }

    /** `(column, row)` of the first disagreeing pixel, or `null` when there is none. */
    fun firstDifferenceFrom(other: DisplacementFrame): Pair<Int, Int>? {
        for (index in 0 until DISPLACEMENT_READBACK_PIXELS * DISPLACEMENT_READBACK_PIXELS) {
            val offset = index * 4
            if ((0..2).any { bytes[offset + it] != other.bytes[offset + it] }) {
                return (index % DISPLACEMENT_READBACK_PIXELS) to (index / DISPLACEMENT_READBACK_PIXELS)
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
 * failure message. Measured during task 11's mutation pass: Gradle's Kotlin/Native test listener
 * refuses a service message over 1 MB, **loses the event, and fails the build with
 * `Cannot process output: too long teamcity service message` instead of the assertion** -- a broken
 * build that reports nothing about what broke, which is worse than a red assertion in exactly the
 * situation a red assertion is most needed. The count fires first with a message a human can read;
 * the array comparison stays underneath it as the exact claim, unreachable in practice and correct
 * in principle, because [DisplacementFrame.differenceFrom] compares three channels of every pixel.
 *
 * Alpha is the one channel it does not compare, and that is [DisplacementFrame.differenceFrom]'s
 * own long-standing choice rather than something introduced here: this suite's target is opaque
 * RGBA8 and nothing it draws writes a non-opaque alpha. The `assertContentEquals` beneath covers it
 * regardless.
 */
private fun assertFramesIdentical(
    expected: DisplacementFrame,
    actual: DisplacementFrame,
    message: String,
) {
    val differing = expected.differenceFrom(actual)
    assertEquals(
        0,
        differing,
        "$message; $differing of ${DISPLACEMENT_READBACK_PIXELS * DISPLACEMENT_READBACK_PIXELS} " +
            "pixels differ, the first at ${expected.firstDifferenceFrom(actual)}",
    )
    assertContentEquals(expected.bytes, actual.bytes, message)
}

private fun createDisplacementTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8,
        DISPLACEMENT_READBACK_PIXELS, DISPLACEMENT_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the ground displacement readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private fun createOpaqueWhiteTexel(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
        byteArrayOf(-1, -1, -1, -1),
    )
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    return texture
}

/**
 * A padded `(N + 2)` square DEM whose texel `(column, row)` decodes to `metresAt(column, row)`.
 *
 * **The encoding is inverted here and then checked forwards against [demElevationMetres]**, so a
 * fixture that encoded a different height from the one it names fails at construction rather than
 * producing a plausible wrong measurement — F-2 found seven vacuous checks, and a fixture derived
 * from the thing under test is the shape most of them had.
 *
 * `GL_NEAREST` and `GL_CLAMP_TO_EDGE` are `DEM_TEXTURE_SAMPLER`'s own values, restated rather than
 * imported because this texture is uploaded by hand: a filtered DEM blends across a Mapbox channel
 * carry and decodes hundreds of metres wrong.
 */
private fun createDemTexture(binding: GlBinding, metresAt: (Int, Int) -> Double): Int {
    val padded = DEM_INTERIOR_TEXELS + 2
    val texels = ByteArray(padded * padded * 4)
    for (row in 0 until padded) {
        for (column in 0 until padded) {
            val metres = metresAt(column, row)
            val packed = ((metres + 10_000.0) / 0.1).toLong()
            val red = ((packed shr 16) and 0xff).toInt()
            val green = ((packed shr 8) and 0xff).toInt()
            val blue = (packed and 0xff).toInt()
            val decoded = demElevationMetres(red, green, blue, FIXTURE_ENCODING)
            assertTrue(
                abs(decoded - metres) < 1e-6,
                "the fixture's own encoder must round-trip through demElevationMetres: " +
                    "asked for $metres m, encoded ($red, $green, $blue), which decodes to $decoded m",
            )
            val offset = (row * padded + column) * 4
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
    binding.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, padded, padded, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    return texture
}

/**
 * 256, and the number is set by the **globe** case rather than by the Mercator ones.
 *
 * At 128 the fixture's sphere overflowed the frame in every direction, so its measured silhouette
 * diameter was 128 whatever the elevation — a saturated measurement that could not move and an
 * assertion that could not fail. That is not a hypothetical: it is what this constant was, and a
 * deliberate break of the globe's displacement left the case green.
 * [assertTheGlobeDisplacesRadiallyByTheDeclaredFraction] now asserts the disc is inside the frame
 * before it compares anything, so a future change that clips it fails loudly instead of silently
 * going vacuous again.
 */
internal const val DISPLACEMENT_READBACK_PIXELS: Int = 256

/** Eight interior texels, ten padded: small enough to reason about, big enough to have an interior. */
private const val DEM_INTERIOR_TEXELS: Int = 8

/** Mapbox, because it is the specification default five of the corpus's six terrain styles take. */
private val FIXTURE_ENCODING: DemEncoding = DemEncoding.MAPBOX

/**
 * 768 metres, chosen so that every factor between it and the measured pixel lift is exact in `Float`
 * — see [assertAKnownDemLiftsTheGroundByAKnownNumberOfPixels] for the arithmetic — and so that its
 * Mapbox triple is `(1, 164, 160)` with no rounding of its own.
 */
private const val FIXTURE_ELEVATION_METRES: Double = 768.0

/**
 * The metre scale the fixture hands the shader, and it is **not 1**.
 *
 * One is a symmetry point: at that value `metres * scale` is `metres`, so a build that dropped the
 * scale entirely and used metres directly as logical pixels draws the identical frame, and every
 * assertion here would pass against it. The fixture ran at 1 until a mutation pass said so. A half
 * is a real Mercator scale — `worldSize / C` is 0.209 px/m at zoom 14 — and it keeps every factor
 * between the DEM and the measured pixel exact in `Float`.
 */
private const val FIXTURE_LOGICAL_PIXELS_PER_METRE: Float = 0.5f

/** `768 m * 0.5 px/m * 2^-12 clip/px * 128 px/clip`, exactly. */
private const val EXPECTED_LIFT_PIXELS: Int = 12

/**
 * The default for the cases that do not measure a lift.
 *
 * **Every case that does measure one passes its own value explicitly**, and 2 rather than 1, because
 * 1 is the value at which an honoured exaggeration and a dropped one draw the identical frame. It is
 * also the value all six corpus styles declare, which is why a fixture copied from the corpus could
 * not have caught a dropped multiplier at all.
 */
private const val FIXTURE_EXAGGERATION: Float = 1.0f

/**
 * LOD 10, row 512: the tile immediately south of the equator, where Mercator's `1 / cos(latitude)`
 * term is `1.0` at its north edge and `1.0000188` at its south. That is what lets the fixture predict
 * the lift from the metre scale alone.
 */
private const val FIXTURE_LOD: Int = 10
private const val FIXTURE_TILE_Y: Int = 512

/** Four cells a side: subdivided enough that a per-vertex fetch is genuinely per vertex. */
private const val FIXTURE_CELLS: Int = 4

/** The middle of the drawn tile, which spans the middle half of the frame's columns. */
private const val FIXTURE_SAMPLE_COLUMN: Int = DISPLACEMENT_READBACK_PIXELS / 2

/** The whole source tile: no overzoom, so the requested tile is the DEM tile. */
private val WHOLE_TILE_WINDOW: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)

/**
 * The tile's own equatorial side, which only a **shaded** ground reads — every render here runs the
 * unshaded programs, so this is honest bookkeeping rather than a number under test. Terrain shading
 * is `runGroundShadingReadback`'s.
 */
private fun tileSideMetres(lod: Int): Float =
    (WORLD_CIRCUMFERENCE_METRES / (1L shl lod).toDouble()).toFloat()

/**
 * `clip.x = x`, `clip.y = y + 2^-12 * z`, `clip.z = 0`, `clip.w = 1`, column-major.
 *
 * A deliberately synthetic matrix rather than a real camera's: it is linear, it is exact in `Float`,
 * and it turns map-space `z` — which is what displacement produces — into a vertical screen offset
 * that can be counted in whole pixels. A real pitched camera would fold perspective into the same
 * measurement and turn an equality into a tolerance.
 */
private val FIXTURE_MATRIX: FloatArray = floatArrayOf(
    1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 0.0f,
    0.0f, 0.000244140625f, 0.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f,
)

/**
 * The tile at LOD 4, row 0: its northern edge is Mercator `y = 0`, the clip at 85.0511 degrees, where
 * `1 / cos(latitude)` is `cosh(PI) = 11.5919532755`. The other Mercator cases sit at the equator,
 * where the same factor is `1` and cannot be observed at all.
 */
private const val CLIP_FIXTURE_LOD: Int = 4
private const val CLIP_FIXTURE_TILE_Y: Int = 0

/** `(800 + 10000) / 0.1 = 108000`, which is the Mapbox triple `(1, 165, 224)` with no rounding. */
private const val CLIP_FIXTURE_ELEVATION_METRES: Double = 800.0

/** `800 * 0.5 * 2^-16 * 128 * cosh(PI) = 9.06` pixels, against `0.78` with no latitude term. */
private val EXPECTED_CLIP_LIFT_PIXELS: IntRange = 8..10

/** [FIXTURE_MATRIX] with a sixteenth of its vertical scale, so a 9-pixel lift stays in frame. */
private val CLIP_FIXTURE_MATRIX: FloatArray = floatArrayOf(
    1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 0.0f,
    0.0f, 0.0000152587890625f, 0.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f,
)

/** Big enough that a per-pixel fill rule is noise against it, small enough to have room to grow. */
private const val MINIMUM_GLOBE_DIAMETER_PIXELS: Int = 120

private const val GLOBE_FIXTURE_LATITUDE: Double = 8.0
private const val GLOBE_FIXTURE_LONGITUDE: Double = 40.0
private const val GLOBE_FIXTURE_ZOOM: Double = 0.5
private const val GLOBE_FIXTURE_LOD: Int = 1

/**
 * 500, and 1000 for the doubling half — and the size is the measurement's, not a taste.
 *
 * A summit's 8,848 m against the WGS84 semi-major axis is 0.14 per cent, which on this fixture's
 * disc is a fifth of a pixel: real, correct, and **unmeasurable**. Exaggeration is finite and
 * unclamped by decision (design section 10), so scaling the effect into the measurable range is a
 * legal frame rather than a workaround, and the ratio between 500 and 1000 keeps the multiplier
 * itself under test.
 *
 * `500 * 768 / 6378137` is 6.02 per cent, which on a 184-pixel disc is 11 pixels against a tolerance
 * of two. The first version of this case used 40, which is 0.48 per cent — **below** its own
 * tolerance — and a deliberate break of the globe's displacement passed it. The rule that came out
 * of that is written into [GLOBE_RATIO_TOLERANCE]: a signal has to be stated against the tolerance
 * that will judge it, not merely be nonzero.
 */
private const val GLOBE_EXAGGERATION: Float = 500.0f

/**
 * Three pixels, and it is a **boundary budget rather than a defect budget**: the sphere's silhouette
 * is a circle, its boundary pixel belongs to whichever side a driver's fill rule puts it on, and the
 * two Apple rasterisers this suite runs on measured the same disc two pixels apart.
 *
 * **The signal is stated against this rather than merely being nonzero**, which is the lesson of the
 * version of this case that was vacuous. An undisplaced ground misses the predicted diameter by 10
 * pixels at exaggeration 500 and 22 at 1000 — 3.5 and 7 budgets — so the budget can absorb a
 * rasteriser without absorbing the defect.
 */
private const val GLOBE_DIAMETER_BUDGET_PIXELS: Double = 3.0
