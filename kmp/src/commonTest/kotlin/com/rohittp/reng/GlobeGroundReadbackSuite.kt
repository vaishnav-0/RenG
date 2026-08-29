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
import com.rohittp.reng.internal.gl.GlobeGroundPipeline
import com.rohittp.reng.internal.gl.GlobeGroundPipelineResult
import com.rohittp.reng.internal.gl.ResolvedGlobeGroundTile
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.composeGlobeGroundUnitSphereToClip
import com.rohittp.reng.internal.gl.createGlobeGroundPipeline
import com.rohittp.reng.internal.gl.deleteGlobeGroundPipeline
import com.rohittp.reng.internal.gl.drawGlobeGround
import com.rohittp.reng.internal.gl.globeGroundCellsPerTileSide
import com.rohittp.reng.internal.gl.globeGroundTileEdges
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.GlobeRayResult
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.globeFixedViewProjection
import com.rohittp.reng.internal.projection.isWithinMercatorPlanningSupport
import com.rohittp.reng.internal.projection.physicalPixelGlobeRay
import com.rohittp.reng.internal.projection.projectGlobe
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle G task 7's gate: the globe ground, in pixels on a real driver.
 *
 * **Why this is not driven through `createRenderer`.** `ProjectionMode.GLOBE` is still refused at
 * frame planning, so no public call can put a globe frame in front of a driver yet. This suite
 * calls `drawGlobeGround` directly and stands in for the caller exactly as
 * [runGroundCullReadbackSuite] does, establishing what `GlFrameDrawer.drawFrame` establishes
 * scene-wide — `frontFace(GL_CCW)` and `cullFace(GL_BACK)` — before drawing.
 *
 * **The fixture is the whole planet at zoom 0.5, from four differently coloured tiles at LOD 1.**
 * Every choice in it is defending against a specific way a case could pass while saying nothing:
 *
 * - **Four tiles, not one.** A single-tile fixture cannot show a seam, and the discriminating sample
 *   is *across* a shared edge. It also cannot tell a near hemisphere from a far one: a sphere's
 *   silhouette is the same circle either way, so [assertTheNearHemisphereIsWhatIsDrawn] needs four
 *   different colours to notice that an inverted grid winding is drawing the antipodes.
 * - **A camera the whole globe fits inside**, because that is where a globe and a plane produce
 *   visibly different pictures. The spec's own sagitta numbers put a frame-sized quad 0.44 logical
 *   pixels off the sphere at zoom 10, so a fixture up there would pass with the projection replaced
 *   by a tangent plane.
 * - **Latitude 8 rather than latitude 45.** Mercator's +/-85.0511 degree clip leaves two polar caps
 *   with no tile at all, and [assertTheGroundCoversExactlyWhatTheGlobeSubtends] compares the drawn
 *   set against a ray cast per pixel. At this zoom the limb sits 74.15 degrees from the anchor and
 *   the near edge of the north cap sits 77.05, so both caps stay hidden and the comparison is
 *   against a full disc rather than a disc with two bites out of it. The north-south orientation
 *   that a near-equatorial camera could otherwise hide is pinned twice over, by
 *   [assertTheNearHemisphereIsWhatIsDrawn] and by
 *   [assertTheTileTextureKeepsRowZeroNorthAndColumnZeroWest].
 * - **Longitude 40 and bearing 23.** The anchor sits inside one tile rather than on the seam between
 *   two, and nothing in the frame is symmetric about either screen axis.
 *
 * **What it does not claim.** Curvature fidelity is Cycle J's, per the spec's own gate decision;
 * nothing here compares against a stored image. Every expected value is derived from the `Double`
 * projection path, which is a different implementation from the `Float` one the driver runs, rather
 * than from a second evaluation of the same expression.
 */
internal fun runGlobeGroundReadbackSuite(binding: GlBinding, dialect: ShaderDialect) {
    val target = createGlobeGroundTarget(binding)
    val programs = GlProgramCache()
    val pipeline = when (val result = createGlobeGroundPipeline(binding, dialect, programs)) {
        is GlobeGroundPipelineResult.Created -> result.pipeline
        is GlobeGroundPipelineResult.Failed ->
            throw AssertionError("the globe ground pipeline's $dialect program did not link on this driver")
    }
    val textures = QUADRANT_COLOURS.map { colour -> createSolidTexture(binding, colour) }
    val quadrantTexture = createQuadrantTexture(binding)
    println("RenG globe ground readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect")
    try {
        val fixture = GlobeGroundFixture(binding, pipeline, target, textures, quadrantTexture)
        assertTheGroundCoversExactlyWhatTheGlobeSubtends(fixture)
        assertTheNearHemisphereIsWhatIsDrawn(fixture)
        assertAdjacentTilesLeaveNoSeam(fixture)
        assertTheTileTextureKeepsRowZeroNorthAndColumnZeroWest(fixture)
        measureSubdivisionConvergence(fixture)
        assertDrawingNoTilesLeavesTheFrameCleared(fixture)
    } finally {
        deleteGlobeGroundPipeline(binding, programs, pipeline)
        binding.deleteTextures(1, intArrayOf(quadrantTexture))
        textures.forEach { binding.deleteTextures(1, intArrayOf(it)) }
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * Every pixel of the frame, against a ray cast through that pixel in `Double` by
 * [physicalPixelGlobeRay] — the inverse path, written for task 4 and tested there against the
 * forward one.
 *
 * This is the case a plane cannot pass. A tangent-plane ground covers a wedge running off the top of
 * the frame; a sphere covers a disc with empty corners, and the disc's radius is set by the camera's
 * distance rather than by anything the ground itself knows. It is also the case that catches a
 * granularity so coarse the limb turns polygonal, which is why the convergence measurement below
 * reports against it.
 *
 * Reported as a count rather than asserted as an equality because a rasteriser's fill rule owns the
 * boundary: the disc's circumference here is about 580 pixels and a driver may round any of them
 * either way. [MAXIMUM_SILHOUETTE_DISAGREEMENT] is that boundary, not a defect budget — the failure
 * it exists to catch is nothing like its size.
 */
private fun assertTheGroundCoversExactlyWhatTheGlobeSubtends(fixture: GlobeGroundFixture) {
    val frame = fixture.render()
    val expected = fixture.expectedGlobeMask()
    var disagreements = 0
    var expectedPainted = 0
    for (row in 0 until GLOBE_GROUND_READBACK_PIXELS) {
        for (column in 0 until GLOBE_GROUND_READBACK_PIXELS) {
            val index = row * GLOBE_GROUND_READBACK_PIXELS + column
            if (expected[index]) expectedPainted += 1
            if (frame.isPainted(column, row) != expected[index]) disagreements += 1
        }
    }
    println(
        "RenG globe ground silhouette: $disagreements pixels disagree with the ray-cast globe, " +
            "which subtends $expectedPainted of " +
            "${GLOBE_GROUND_READBACK_PIXELS * GLOBE_GROUND_READBACK_PIXELS}",
    )
    assertTrue(
        expectedPainted in 20000..30000,
        "the fixture must actually put a disc in the frame, or every count below is vacuous: " +
            "$expectedPainted",
    )
    assertEquals(
        0,
        frame.paintedAt(0, 0) + frame.paintedAt(GLOBE_GROUND_READBACK_PIXELS - 1, 0) +
            frame.paintedAt(0, GLOBE_GROUND_READBACK_PIXELS - 1) +
            frame.paintedAt(GLOBE_GROUND_READBACK_PIXELS - 1, GLOBE_GROUND_READBACK_PIXELS - 1),
        "a globe leaves the frame's corners empty where a plane would not",
    )
    assertTrue(
        disagreements <= MAXIMUM_SILHOUETTE_DISAGREEMENT,
        "the drawn ground must be the globe the camera subtends: $disagreements pixels disagree, " +
            "against a boundary budget of $MAXIMUM_SILHOUETTE_DISAGREEMENT",
    )
}

/**
 * Twelve positions spread over all four tiles and both hemispheres of the visible face, each
 * projected to its window pixel in `Double` and each required to be showing its own tile's colour.
 *
 * **This is the case that notices an inverted winding**, which the silhouette cannot: culling the
 * near hemisphere instead of the far one leaves the identical disc and fills it with the antipodal
 * tiles. It is also what pins east against west and north against south, since a mirrored globe
 * still covers the same pixels.
 */
private fun assertTheNearHemisphereIsWhatIsDrawn(fixture: GlobeGroundFixture) {
    val frame = fixture.render()
    var checked = 0
    SAMPLE_POINTS.forEach { (mercatorX, mercatorY) ->
        val visible = fixture.isOnTheNearFace(mercatorX, mercatorY)
        assertTrue(visible, "the fixture's sample at ($mercatorX, $mercatorY) must be on the near face")
        val window = fixture.windowPosition(mercatorX, mercatorY)
        val expected = QUADRANT_COLOURS[fixture.quadrantOf(mercatorX, mercatorY)]
        assertEquals(
            expected.toList(),
            frame.colourAt(window.first, window.second).toList(),
            "at Mercator ($mercatorX, $mercatorY), window ${window.first},${window.second}",
        )
        checked += 1
    }
    assertEquals(SAMPLE_POINTS.size, checked)
}

/**
 * The two seams the fixture contains — the prime meridian between the west and east tiles, and the
 * equator between the north and south ones — sampled **across** the shared edge rather than along
 * one side of it.
 *
 * Each sample takes three pixels: one a pixel and a half west of the edge, one on it, one east. The
 * outer two must show the two *different* tile colours, or the sample is not straddling anything and
 * the middle assertion is free; the middle one must be painted at all, which is what a crack would
 * break. Rentile's own adjacent tiles align exactly — measured, cross-seam differences inside
 * interior-column noise — so a seam here would be RenG's geometry, and the geometry that closes it
 * is `globeGroundTileEdges` handing both tiles the bitwise-identical edge.
 */
private fun assertAdjacentTilesLeaveNoSeam(fixture: GlobeGroundFixture) {
    val frame = fixture.render()
    var straddled = 0
    SEAM_SAMPLES.forEach { (mercatorX, mercatorY) ->
        val here = fixture.windowPosition(mercatorX, mercatorY)
        val offsets = listOf(-2.5, -1.5, -0.5, 0.5, 1.5, 2.5)
        val meridian = mercatorX == 0.5
        val colours = offsets.map { offset ->
            val sample = if (meridian) {
                fixture.windowPosition(mercatorX + offset * fixture.mercatorPerPixelX(mercatorY), mercatorY)
            } else {
                fixture.windowPosition(mercatorX, mercatorY + offset * fixture.mercatorPerPixelY(mercatorX))
            }
            frame.colourAt(sample.first, sample.second).toList()
        }
        colours.forEachIndexed { index, colour ->
            assertTrue(
                colour.any { (it.toInt() and 0xff) > 128 },
                "a seam at Mercator ($mercatorX, $mercatorY) left an unpainted pixel at offset " +
                    "${offsets[index]}: window ${here.first},${here.second}",
            )
        }
        if (colours.first() != colours.last()) straddled += 1
    }
    assertEquals(
        SEAM_SAMPLES.size,
        straddled,
        "every seam sample must actually cross from one tile's colour into the other's, or the " +
            "continuity assertion above is free",
    )
}

/**
 * The UV convention, on one tile carrying a texture whose four texels differ.
 *
 * Row zero of a rendered basemap tile is its **north** edge and column zero its west, and a flip in
 * either axis mirrors every tile about its own centre line — invisible on the solid colours every
 * other case here uses, and catastrophic on a real map. `GroundPipelineTest` pins the same
 * convention for the Mercator ground in its vertex stage, which is where it moved when Cycle
 * E-terrain replaced `GROUND_QUAD`'s four-row table with a subdivided grid; a grid has no such table
 * to read, so this reads pixels instead.
 */
private fun assertTheTileTextureKeepsRowZeroNorthAndColumnZeroWest(fixture: GlobeGroundFixture) {
    val frame = fixture.renderQuadrantTile()
    val tileCount = (1 shl QUADRANT_TILE_LOD).toDouble()
    listOf(
        Triple(0.25, 0.25, 0),
        Triple(0.75, 0.25, 1),
        Triple(0.25, 0.75, 2),
        Triple(0.75, 0.75, 3),
    ).forEach { (u, v, texel) ->
        val mercatorX = (QUADRANT_TILE_X + u) / tileCount
        val mercatorY = (QUADRANT_TILE_Y + v) / tileCount
        val window = fixture.windowPosition(mercatorX, mercatorY)
        assertEquals(
            QUADRANT_COLOURS[texel].toList(),
            frame.colourAt(window.first, window.second).toList(),
            "texel $texel belongs at (u=$u, v=$v) of the tile",
        )
    }
}

/**
 * **The measurement that turns half a logical pixel from a preference into a number.**
 *
 * The same frame is drawn at every granularity from one quad per tile to the 128-cell cap, and each
 * is compared against the 128-cell reference. What the granularity rule chooses for this camera must
 * be indistinguishable from the reference, and the coarse end must be very distinguishable from it —
 * otherwise the rule is choosing a density nobody needed and the whole shared-grid design is
 * unmotivated.
 */
private fun measureSubdivisionConvergence(fixture: GlobeGroundFixture) {
    val reference = fixture.render(cellsPerTileSide = 128)
    val chosen = fixture.cellsPerTileSide
    val report = StringBuilder("RenG globe ground subdivision, pixels differing from the 128 reference:")
    val differences = mutableMapOf<Int, Int>()
    listOf(1, 2, 4, 8, 16, 32, 64).forEach { cells ->
        val frame = fixture.render(cellsPerTileSide = cells)
        val difference = frame.differenceFrom(reference)
        differences[cells] = difference
        report.append(" $cells=$difference")
    }
    println("$report (the rule chose $chosen for this camera)")

    assertTrue(chosen > 1, "a grid of one quad is not a subdivided grid; the rule chose $chosen")
    assertTrue(
        differences.getValue(chosen) <= MAXIMUM_CONVERGED_DIFFERENCE,
        "the chosen granularity $chosen must be indistinguishable from the 128-cell reference: " +
            "${differences.getValue(chosen)} pixels differ",
    )
    assertTrue(
        differences.getValue(1) > 20 * MAXIMUM_CONVERGED_DIFFERENCE,
        "one quad per tile must be very distinguishable from the reference, or the subdivision " +
            "buys nothing here: ${differences.getValue(1)} pixels differ",
    )
    assertTrue(
        differences.getValue(chosen / 2) > differences.getValue(chosen),
        "the next coarser granularity must be measurably worse than the chosen one, or the rule " +
            "is one step past where it needed to stop",
    )
}

/** The negative that stops every count above being attributable to anything other than the ground. */
private fun assertDrawingNoTilesLeavesTheFrameCleared(fixture: GlobeGroundFixture) {
    val frame = fixture.render(tiles = emptyList())
    var painted = 0
    for (row in 0 until GLOBE_GROUND_READBACK_PIXELS) {
        for (column in 0 until GLOBE_GROUND_READBACK_PIXELS) {
            if (frame.isPainted(column, row)) painted += 1
        }
    }
    assertEquals(0, painted, "a ground with no tiles must leave the cleared frame alone")
}

private class GlobeGroundFixture(
    private val binding: GlBinding,
    private val pipeline: GlobeGroundPipeline,
    private val target: Int,
    private val textures: List<Int>,
    private val quadrantTexture: Int,
) {
    val camera: ResolvedGlobeCamera = (
        resolveGlobeCamera(
            Camera(
                latitude = FIXTURE_LATITUDE,
                unwrappedLongitude = FIXTURE_LONGITUDE,
                zoom = FIXTURE_ZOOM,
                bearing = FIXTURE_BEARING,
                pitch = 0.0,
            ),
            OutputPixelSize(GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS),
        ) as SpatialOutcome.Success<ResolvedGlobeCamera>
        ).value

    val cellsPerTileSide: Int = globeGroundCellsPerTileSide(camera, FIXTURE_LOD)

    private val unitSphereToClip: FloatArray = composeGlobeGroundUnitSphereToClip(camera)

    private val wholeGlobe: List<ResolvedGlobeGroundTile> = (0 until 4).map { index ->
        ResolvedGlobeGroundTile(
            edges = globeGroundTileEdges(
                lod = FIXTURE_LOD,
                tileY = (index / 2).toLong(),
                unwrappedX = (index % 2).toLong(),
            ),
            texture = textures[index],
        )
    }

    fun render(
        tiles: List<ResolvedGlobeGroundTile> = wholeGlobe,
        cellsPerTileSide: Int = this.cellsPerTileSide,
    ): GlobeGroundFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target)
        binding.viewport(0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.clearColor(0f, 0f, 0f, 1f)
        binding.clear(GL_COLOR_BUFFER_BIT)

        // Exactly what `GlFrameDrawer.drawFrame` establishes before `content.draw`, which is why the
        // ground pass restates neither the winding nor the cull mode (ADR 0038).
        binding.frontFace(GL_CCW)
        binding.cullFace(GL_BACK)
        binding.disable(GL_CULL_FACE)
        binding.bindSampler(0, 0)

        drawGlobeGround(binding, pipeline, tiles, unitSphereToClip, cellsPerTileSide)

        val bytes = ByteArray(GLOBE_GROUND_READBACK_PIXELS * GLOBE_GROUND_READBACK_PIXELS * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, bytes,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        return GlobeGroundFrame(bytes)
    }

    fun renderQuadrantTile(): GlobeGroundFrame = render(
        tiles = listOf(
            ResolvedGlobeGroundTile(
                edges = globeGroundTileEdges(
                    QUADRANT_TILE_LOD,
                    QUADRANT_TILE_Y.toLong(),
                    QUADRANT_TILE_X.toLong(),
                ),
                texture = quadrantTexture,
            ),
        ),
    )

    /** Which of the four LOD-1 tiles a Mercator position falls in, in the order [wholeGlobe] draws. */
    fun quadrantOf(mercatorX: Double, mercatorY: Double): Int =
        (if (mercatorY >= 0.5) 2 else 0) + (if (mercatorX >= 0.5) 1 else 0)

    /**
     * Where a Mercator ground position lands, in `glReadPixels` bottom-up window pixels, through the
     * `Double` projection path. The driver evaluates the same geometry in `Float` from a different
     * expression, so an agreement between the two is evidence rather than a tautology.
     */
    fun windowPosition(mercatorX: Double, mercatorY: Double): Pair<Int, Int> {
        val exact = exactWindowPosition(mercatorX, mercatorY)
        return Pair(exact.first.roundToInt(), exact.second.roundToInt())
    }

    /**
     * The same position without the rounding, so a derivative taken across a fraction of a pixel
     * does not divide by zero. Rounding first and differencing after is exactly that division, and
     * it produced a `NaN` longitude the first time this was written.
     */
    fun exactWindowPosition(mercatorX: Double, mercatorY: Double): Pair<Double, Double> {
        val position = projectGlobe(mercatorX, mercatorY, 0.0, camera.radiusLogicalPixels)
        val matrix = globeFixedViewProjection(camera)
        val w = matrix[3, 0] * position.x + matrix[3, 1] * position.y +
            matrix[3, 2] * position.z + matrix[3, 3]
        val x = (matrix[0, 0] * position.x + matrix[0, 1] * position.y +
            matrix[0, 2] * position.z + matrix[0, 3]) / w
        val y = (matrix[1, 0] * position.x + matrix[1, 1] * position.y +
            matrix[1, 2] * position.z + matrix[1, 3]) / w
        val half = GLOBE_GROUND_READBACK_PIXELS / 2.0
        return Pair(x * half + half - 0.5, y * half + half - 0.5)
    }

    /**
     * The limb-plane dot product ADR 0038 names: a surface point is on the visible face exactly when
     * its dot with the eye exceeds the radius squared.
     */
    fun isOnTheNearFace(mercatorX: Double, mercatorY: Double): Boolean {
        val position = projectGlobe(mercatorX, mercatorY, 0.0, camera.radiusLogicalPixels)
        return position.dot(camera.eyeGlobeFixed) >
            camera.radiusLogicalPixels * camera.radiusLogicalPixels
    }

    /** How much Mercator `x` one window pixel is worth near [mercatorY], measured rather than
     * derived, so a seam sample's offsets are in pixels whatever the projection is doing locally. */
    fun mercatorPerPixelX(mercatorY: Double): Double {
        val step = 1.0 / 4096.0
        val a = exactWindowPosition(0.5 - step, mercatorY)
        val b = exactWindowPosition(0.5 + step, mercatorY)
        return 2.0 * step / hypotenuse(b.first - a.first, b.second - a.second)
    }

    fun mercatorPerPixelY(mercatorX: Double): Double {
        val step = 1.0 / 4096.0
        val a = exactWindowPosition(mercatorX, 0.5 - step)
        val b = exactWindowPosition(mercatorX, 0.5 + step)
        return 2.0 * step / hypotenuse(b.first - a.first, b.second - a.second)
    }

    /**
     * Which pixels the globe subtends, one ray per pixel through [physicalPixelGlobeRay], excluding
     * the two polar caps Mercator does not tile. Bottom-up to match `glReadPixels`, where the ray
     * caster counts rows from the top.
     */
    fun expectedGlobeMask(): BooleanArray {
        val mask = BooleanArray(GLOBE_GROUND_READBACK_PIXELS * GLOBE_GROUND_READBACK_PIXELS)
        for (row in 0 until GLOBE_GROUND_READBACK_PIXELS) {
            for (column in 0 until GLOBE_GROUND_READBACK_PIXELS) {
                val hit = physicalPixelGlobeRay(
                    camera,
                    column,
                    GLOBE_GROUND_READBACK_PIXELS - 1 - row,
                )
                mask[row * GLOBE_GROUND_READBACK_PIXELS + column] =
                    hit is GlobeRayResult.Hit && isWithinMercatorPlanningSupport(hit.point.x, hit.point.y)
            }
        }
        return mask
    }

    private fun hypotenuse(x: Double, y: Double): Double = kotlin.math.sqrt(x * x + y * y)
}

private class GlobeGroundFrame(val bytes: ByteArray) {
    fun isPainted(column: Int, row: Int): Boolean {
        val offset = (row * GLOBE_GROUND_READBACK_PIXELS + column) * 4
        return (0..2).any { (bytes[offset + it].toInt() and 0xff) > 128 }
    }

    fun paintedAt(column: Int, row: Int): Int = if (isPainted(column, row)) 1 else 0

    fun colourAt(column: Int, row: Int): ByteArray {
        require(column in 0 until GLOBE_GROUND_READBACK_PIXELS) { "column $column is off the frame" }
        require(row in 0 until GLOBE_GROUND_READBACK_PIXELS) { "row $row is off the frame" }
        val offset = (row * GLOBE_GROUND_READBACK_PIXELS + column) * 4
        return byteArrayOf(bytes[offset], bytes[offset + 1], bytes[offset + 2])
    }

    fun differenceFrom(other: GlobeGroundFrame): Int {
        var differing = 0
        for (index in 0 until GLOBE_GROUND_READBACK_PIXELS * GLOBE_GROUND_READBACK_PIXELS) {
            val offset = index * 4
            if ((0..2).any { bytes[offset + it] != other.bytes[offset + it] }) differing += 1
        }
        return differing
    }
}

internal const val GLOBE_GROUND_READBACK_PIXELS: Int = 256

private const val FIXTURE_LATITUDE: Double = 8.0
private const val FIXTURE_LONGITUDE: Double = 40.0
private const val FIXTURE_ZOOM: Double = 0.5
private const val FIXTURE_BEARING: Double = 23.0
private const val FIXTURE_LOD: Int = 1

/**
 * The tile the UV case draws: LOD 4 rather than LOD 1, and the one holding the camera's own anchor.
 *
 * A LOD-1 tile's four sub-quadrant centres are 70 to 83 degrees from the anchor — one of them past
 * the limb entirely — so the case would have been sampling the far side of the planet. At LOD 4 the
 * whole tile spans 22.5 degrees of longitude beside the anchor and all four samples are safely on
 * the visible face. Neither index is zero, so a dropped tile origin cannot pass.
 */
private const val QUADRANT_TILE_LOD: Int = 4
private const val QUADRANT_TILE_X: Int = 9
private const val QUADRANT_TILE_Y: Int = 7

/**
 * Four opaque colours, one per LOD-1 tile, in `quadrantOf`'s order: north-west, north-east,
 * south-west, south-east. Each differs from the others in at least one channel by the full range, so
 * "which tile is this pixel" is an exact byte comparison rather than a tolerance.
 */
private val QUADRANT_COLOURS: List<ByteArray> = listOf(
    byteArrayOf(-1, 0, 0),
    byteArrayOf(0, -1, 0),
    byteArrayOf(0, 0, -1),
    byteArrayOf(-1, -1, -1),
)

/**
 * Twelve Mercator positions on the visible face, three per tile, spread between 15 and 52 degrees
 * of great-circle distance from the anchor — far enough apart to reach all four tiles, near enough
 * that none is inside the limb's foreshortened last few degrees where a one-pixel prediction error
 * would land on the wrong side of an edge. None sits on a tile edge, on the equator, on the prime
 * meridian or at the anchor.
 */
private val SAMPLE_POINTS: List<Pair<Double, Double>> = listOf(
    0.4861 to 0.4282, 0.4722 to 0.4433, 0.4944 to 0.4578,
    0.6528 to 0.4433, 0.5694 to 0.4861, 0.6667 to 0.4126,
    0.4861 to 0.5139, 0.4778 to 0.5336, 0.4917 to 0.5111,
    0.6528 to 0.5567, 0.5694 to 0.5279, 0.6250 to 0.5874,
)

/** Points on the two shared edges the fixture contains: the prime meridian and the equator. */
private val SEAM_SAMPLES: List<Pair<Double, Double>> = listOf(
    0.5 to 0.40, 0.5 to 0.46, 0.5 to 0.54, 0.5 to 0.60,
    0.56 to 0.5, 0.62 to 0.5, 0.68 to 0.5,
)

/**
 * The disc's own boundary: about 580 pixels of circumference, each of which a driver may round
 * either way. Anything larger is not a fill-rule difference — a polygonal limb or a hemisphere drawn
 * inside out is thousands.
 *
 * **Measured at 29 pixels on `Apple M3 Max` and 24 on `Apple Software Renderer`**, which is the
 * answer to a question this task was told not to assume either way. `measureLargeQuadRasterisation`
 * finds the software rasteriser dropping quads that reach far outside the viewport — 2,112 pixels of
 * disagreement, enough that `runBasemapReadbackSuite` stands its ground-coverage case down — and a
 * globe ground patch is the opposite shape: one cell of a subdivided grid, a few pixels across and
 * entirely on-screen. The two rasterisers agree here to within five pixels of each other, so no case
 * in this suite stands down on either.
 */
private const val MAXIMUM_SILHOUETTE_DISAGREEMENT: Int = 600

/**
 * Measured at 44 pixels on `Apple M3 Max` and 57 on `Apple Software Renderer`, of 24,264 the globe
 * covers. Set below the next coarser granularity's own figure — 173 and 186 for 16 cells a side —
 * so that the budget distinguishes the granularity the rule chose from the one it rejected, rather
 * than admitting both.
 */
private const val MAXIMUM_CONVERGED_DIFFERENCE: Int = 120

private fun createGlobeGroundTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8,
        GLOBE_GROUND_READBACK_PIXELS, GLOBE_GROUND_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the globe ground readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

/** One opaque texel, uploaded by hand: nothing here runs `decodePng`, so a decoder regression
 * cannot make this suite pass. */
private fun createSolidTexture(binding: GlBinding, colour: ByteArray): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
        byteArrayOf(colour[0], colour[1], colour[2], -1),
    )
    applyGroundSamplerState(binding)
    return texture
}

/** A two-by-two texture whose texels are [QUADRANT_COLOURS] in row-major order, so row zero is the
 * north edge and column zero the west. */
private fun createQuadrantTexture(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    val pixels = ByteArray(16)
    QUADRANT_COLOURS.forEachIndexed { index, colour ->
        pixels[index * 4] = colour[0]
        pixels[index * 4 + 1] = colour[1]
        pixels[index * 4 + 2] = colour[2]
        pixels[index * 4 + 3] = -1
    }
    binding.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
    applyGroundSamplerState(binding)
    return texture
}

/**
 * `GL_NEAREST` and `GL_CLAMP_TO_EDGE`, with **no mipmaps** — the state `GlTextureUpload` gives a
 * real basemap tile, minus the linear filter that would blur an exact byte comparison.
 *
 * The absent mipmap chain is deliberate and is the sharp trap this task was warned about: adding one
 * to soften the limb widens the clamped edge band from one texel to `2^N` and **creates** the seam
 * it was meant to avoid, which would then need a one-texel border Rentile does not supply.
 */
private fun applyGroundSamplerState(binding: GlBinding) {
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
}
