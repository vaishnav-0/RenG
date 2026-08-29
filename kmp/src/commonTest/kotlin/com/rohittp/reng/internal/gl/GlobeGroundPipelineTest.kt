package com.rohittp.reng.internal.gl

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.globeFixedViewProjection
import com.rohittp.reng.internal.projection.globeRadiusLogicalPixels
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.unitSphereDirection
import com.rohittp.reng.internal.shader.scanShaderProfile
import com.rohittp.reng.internal.terrain.DemEncoding
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cycle G task 7: the globe ground's geometry, its granularity rule and its draw.
 *
 * The readback half of this task lives in `runGlobeGroundReadbackSuite`, which is where anything
 * that needs a rasteriser is asserted. What is here is everything a fake binding and `Double`
 * arithmetic can settle: the grid's shape and winding, the tile edges two neighbours have to agree
 * on bitwise, the granularity the camera implies, and the one number the design owes — how far the
 * `Float` narrowing moves the ground at each zoom.
 */
class GlobeGroundPipelineTest {
    @Test fun theRosterAddsGlobeGroundAtWireValueSevenWithoutRenumberingTheOthers() {
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
        assertEquals(3, InternalPipelineRole.GROUND.wireValue)
        assertEquals(4, InternalPipelineRole.MODEL.wireValue)
        assertEquals(5, InternalPipelineRole.LABEL.wireValue)
        assertEquals(6, InternalPipelineRole.ICON.wireValue)
        assertEquals(7, InternalPipelineRole.GLOBE_GROUND.wireValue)
    }

    @Test fun theGlobeGroundSourcesAreAcceptedShaderProfileSources() {
        assertTrue(GLOBE_GROUND_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(GLOBE_GROUND_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(GLOBE_GROUND_SHADER_PAIR.fragmentSource) != null)
    }

    /**
     * G5's two non-negotiables, asserted on the shipped source rather than trusted to review.
     *
     * `precision highp float` is worth about 20 km of latitude on its own and no choice of identity
     * rescues it, and the tangent half-angle path exists precisely so that `atan`, `sin` and `cos`
     * leave the **latitude** calculation entirely — GLSL ES 3.00 gives them no precision bound at
     * all while it bounds `exp` at `3 + 2|x|` ULP. A future edit that spells the latitude
     * `2.0 * atan(exp(psi)) - 1.5707963` would be mathematically identical, would pass every other
     * case in this file and in the readback suite, and would reintroduce MapLibre's 200-300 metre
     * Mali-G610 defect. This is the case that stops it.
     */
    @Test fun theVertexShaderKeepsHighPrecisionAndKeepsTrigonometryOutOfTheLatitudePath() {
        assertTrue(
            GLOBE_GROUND_VERTEX_SOURCE.contains("precision highp float;"),
            "mediump costs about 20 km of latitude, 9.8 km of it before any arithmetic happens",
        )
        assertTrue(GLOBE_GROUND_VERTEX_SOURCE.contains("exp(isometricLatitude)"))
        listOf("atan", "asin", "tanh", "sinh", "cosh").forEach { forbidden ->
            assertFalse(
                GLOBE_GROUND_VERTEX_SOURCE.contains(forbidden),
                "$forbidden has no specified precision in GLSL ES 3.00 and must not reach the " +
                    "latitude path",
            )
        }
        // Longitude still needs a rotation, and always did: `unitSphereDirection` calls cos and sin
        // on the longitude too. Only the latitude path is trigonometry-free.
        assertTrue(GLOBE_GROUND_VERTEX_SOURCE.contains("cos(longitude)"))
        assertTrue(GLOBE_GROUND_VERTEX_SOURCE.contains("sin(longitude)"))
    }

    /**
     * The two ground modes share one fragment stage by identity, not by copy, so a change to how a
     * basemap tile is sampled cannot reach one mode and miss the other.
     */
    @Test fun theGlobeGroundSamplesItsTileThroughTheMercatorGroundsOwnFragmentStage() {
        assertSame(GROUND_FRAGMENT_SOURCE, GLOBE_GROUND_SHADER_PAIR.fragmentSource)
        assertTrue(
            GLOBE_GROUND_VERTEX_SOURCE.contains("out vec2 rengGroundUv;"),
            "the varying name is the shared fragment stage's, or nothing links",
        )
    }

    @Test fun theGlobeGroundProgramIsADistinctCacheEntryFromTheMercatorGroundProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val mercator = (
            createGroundPipeline(binding, ShaderDialect.GLES, cache) as GroundPipelineResult.Created
            ).pipeline
        val globe = (
            createGlobeGroundPipeline(binding, ShaderDialect.GLES, cache)
                as GlobeGroundPipelineResult.Created
            ).pipeline
        assertTrue(mercator.key != globe.key, "two internal pipelines must not share one program key")
        assertTrue(mercator.program != globe.program)
    }

    /**
     * A grid of one quad is not a subdivided grid, so the counts are asserted rather than the
     * existence of a buffer. MapLibre's floor for a raster tile is 32 cells a side, which is 1,089
     * vertices and 2,048 triangles from one shared buffer.
     */
    @Test fun theGridHasTheVertexAndTriangleCountsItsGranularityImplies() {
        listOf(1, 2, 32, 128).forEach { cells ->
            val vertices = globeGroundGridVertices(cells)
            val indices = globeGroundGridIndices(cells)
            assertEquals((cells + 1) * (cells + 1) * 2, vertices.size, "$cells cells a side")
            assertEquals(cells * cells * 6, indices.size, "$cells cells a side")
        }
        assertEquals(1089 * 2, globeGroundGridVertices(32).size)
        assertEquals(2048, globeGroundGridIndices(32).size / 3)
        assertEquals(
            16641,
            globeGroundGridVertices(MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE).size / 2,
            "the cap is the widest grid a 16-bit index can address",
        )
    }

    /**
     * The four boundary coordinates must be exactly zero and one, because the shader reconstructs a
     * vertex's position as `mix(west, east, u)` and `mix` returns its endpoint exactly only at
     * exactly zero and one. This is the first half of the seam being closed by construction; the
     * second is [adjacentTilesAreHandedTheirSharedEdgeBitwiseIdentical].
     */
    @Test fun theGridsBoundaryCoordinatesAreExactlyZeroAndOne() {
        val cells = 32
        val vertices = globeGroundGridVertices(cells)
        val perSide = cells + 1
        for (row in 0 until perSide) {
            for (column in 0 until perSide) {
                val u = vertices[(row * perSide + column) * 2]
                val v = vertices[(row * perSide + column) * 2 + 1]
                if (column == 0) assertEquals(0.0f, u)
                if (column == cells) assertEquals(1.0f, u)
                if (row == 0) assertEquals(0.0f, v)
                if (row == cells) assertEquals(1.0f, v)
                assertTrue(u in 0.0f..1.0f && v in 0.0f..1.0f)
            }
        }
    }

    /**
     * Every triangle's normal points **away from the sphere's centre**, which is what makes the far
     * hemisphere the back-facing set under `drawFrame`'s `GL_CCW`/`GL_BACK` and ADR 0038's cull the
     * whole mechanism.
     *
     * Asserted on real sphere positions rather than on the `(u, v)` square, because the square says
     * nothing about which way `v` runs on the globe: `v` grows **south**, so the natural
     * `(i, j), (i + 1, j), (i, j + 1)` order is inward-facing and would draw the far hemisphere
     * while culling the near one — a mirrored planet that a single-tile fixture cannot tell from a
     * correct one.
     */
    @Test fun everyGridTriangleFacesAwayFromTheSpheresCentre() {
        val cells = 8
        val edges = globeGroundTileEdges(lod = 2, tileY = 1, unwrappedX = 3)
        val vertices = globeGroundGridVertices(cells)
        val indices = globeGroundGridIndices(cells)
        val positions = List(vertices.size / 2) { index ->
            directionAt(edges, vertices[index * 2], vertices[index * 2 + 1])
        }
        var inwardFacing = 0
        for (triangle in 0 until indices.size / 3) {
            val a = positions[indices[triangle * 3].toInt()]
            val b = positions[indices[triangle * 3 + 1].toInt()]
            val c = positions[indices[triangle * 3 + 2].toInt()]
            val normal = (b - a).cross(c - a)
            val outward = a + b + c
            if (normal.dot(outward) <= 0.0) inwardFacing += 1
        }
        assertEquals(0, inwardFacing, "every one of ${indices.size / 3} triangles must face outward")
    }

    /**
     * The edges two neighbours share are the **same `Float`**, in both axes, so their grids put
     * vertices in the same place rather than an ULP apart.
     *
     * Exact rather than within a tolerance, because a tolerance is what a crack hides in. What this
     * does *not* pin is the shader's spelling: measured, computing the east edge as
     * `west + span` in `Double` gives a different `Double` for 196 of 1,144 sampled tiles and the
     * identical `Float` for all of them, so this case is blind to that mutation and stayed green
     * under it. The spelling that matters is the one evaluated in `highp float` on the GPU, and
     * [theShaderReconstructsEachAxisFromItsOwnEndpoints] is what pins that.
     */
    @Test fun adjacentTilesAreHandedTheirSharedEdgeBitwiseIdentical() {
        val lod = 5
        for (tileY in 0L until 31L step 7L) {
            for (unwrappedX in 0L until 31L step 7L) {
                val here = globeGroundTileEdges(lod, tileY, unwrappedX)
                val east = globeGroundTileEdges(lod, tileY, unwrappedX + 1)
                val south = globeGroundTileEdges(lod, tileY + 1, unwrappedX)
                assertEquals(here[1], east[0], "the shared meridian at x=$unwrappedX")
                assertEquals(here[3], south[2], "the shared parallel at y=$tileY")
            }
        }
    }

    /**
     * The shader reconstructs each axis with `mix` between the tile's two edges, never as an origin
     * plus a span times the grid coordinate.
     *
     * **A source-inspection case, because no fixture can reach this one.** `mix(x, y, a)` is defined
     * as `x(1 - a) + y * a`, which returns `y` exactly at `a = 1`, so two tiles that were handed the
     * same edge put their shared vertices in the same place. `origin + span * u` performs the
     * multiply-add in `highp float` against a longitude near `PI` instead, and lands up to
     * `2.384 x 10^-7` radians from the neighbour's own west edge — 0.02 logical pixels of crack at
     * zoom 10, 5.09 at zoom 18. Every zoom where a readback fixture can put the whole globe in a
     * frame is at the harmless end of that, so a pixel gate would report the wrong spelling as
     * green right up to the zooms nobody can photograph in a test.
     */
    @Test fun theShaderReconstructsEachAxisFromItsOwnEndpoints() {
        assertTrue(
            GLOBE_GROUND_VERTEX_SOURCE.contains(
                "mix(rengGlobeGroundTileEdges.x, rengGlobeGroundTileEdges.y, rengGlobeGroundGrid.x)",
            ),
            "longitude must be mixed between the tile's west and east edges",
        )
        assertTrue(
            GLOBE_GROUND_VERTEX_SOURCE.contains(
                "mix(rengGlobeGroundTileEdges.z, rengGlobeGroundTileEdges.w, rengGlobeGroundGrid.y)",
            ),
            "isometric latitude must be mixed between the tile's north and south edges",
        )
    }

    /**
     * A sphere has no world copies, so a tile two copies east draws where copy zero's does —
     * `unitSphereDirection` wraps its Mercator `x` for exactly this reason and these edges must
     * agree with it.
     */
    @Test fun aTileInAnotherWorldCopyLandsOnTheFirstCopy() {
        val lod = 3
        val canonical = globeGroundTileEdges(lod, tileY = 2, unwrappedX = 5)
        listOf(-2L, -1L, 1L, 2L).forEach { copy ->
            val shifted = globeGroundTileEdges(lod, tileY = 2, unwrappedX = 5 + copy * 8)
            assertEquals(canonical.toList(), shifted.toList(), "world copy $copy")
        }
    }

    /**
     * The tile at the antimeridian keeps its own span rather than wrapping into the rest of the
     * planet. Its west edge is the last longitude before `+PI` and its east edge is `+PI` itself,
     * where the naive wrap would have folded the east edge back to `-PI` and made one tile cover
     * everything except itself.
     */
    @Test fun theAntimeridianTileKeepsItsOwnSpan() {
        val lod = 4
        val last = globeGroundTileEdges(lod, tileY = 7, unwrappedX = 15)
        assertTrue(last[1] > last[0], "east must stay east of west")
        assertEquals(
            (2.0 * PI / 16.0).toFloat(),
            last[1] - last[0],
            absoluteTolerance = 1e-6f,
        )
        assertEquals(PI.toFloat(), last[1], absoluteTolerance = 1e-6f)
        val first = globeGroundTileEdges(lod, tileY = 7, unwrappedX = 0)
        assertEquals((-PI).toFloat(), first[0], absoluteTolerance = 1e-6f)
    }

    /** Row zero of a rendered basemap tile is its north edge, so `v = 0` is the larger isometric
     * latitude. A flipped pair mirrors every tile about its own centre line. */
    @Test fun theNorthEdgeIsTheLargerIsometricLatitude() {
        val edges = globeGroundTileEdges(lod = 3, tileY = 5, unwrappedX = 2)
        assertTrue(edges[2] > edges[3], "north is above south")
        val whole = globeGroundTileEdges(lod = 0, tileY = 0, unwrappedX = 0)
        assertEquals(PI.toFloat(), whole[2], absoluteTolerance = 1e-6f)
        assertEquals((-PI).toFloat(), whole[3], absoluteTolerance = 1e-6f)
    }

    /**
     * The edges and the shader's `mix` reproduce [unitSphereDirection]'s own parameterisation to
     * `Double` precision, sampled asymmetrically across a tile that is at neither the equator nor a
     * pole and in neither the first world copy nor a symmetric position within it. A transposed
     * pair, a flipped axis or an off-by-one tile origin all fail here.
     */
    @Test fun theTileEdgesReproduceTheProjectionsOwnParameterisation() {
        val lod = 4
        val tileY = 3L
        val unwrappedX = 11L
        val edges = globeGroundTileEdges(lod, tileY, unwrappedX)
        val tileCount = (1L shl lod).toDouble()
        listOf(0.0, 0.125, 0.5, 0.875, 1.0).forEach { u ->
            listOf(0.0, 0.125, 0.5, 0.875, 1.0).forEach { v ->
                val expected = unitSphereDirection(
                    mercatorX = (unwrappedX.toDouble() + u) / tileCount,
                    mercatorY = (tileY.toDouble() + v) / tileCount,
                )
                val actual = directionAt(edges, u.toFloat(), v.toFloat())
                assertTrue(
                    abs(expected.x - actual.x) < 1e-6 &&
                        abs(expected.y - actual.y) < 1e-6 &&
                        abs(expected.z - actual.z) < 1e-6,
                    "at (u=$u, v=$v) expected $expected but was $actual",
                )
            }
        }
    }

    /**
     * The granularity follows the camera, not the world: 64 cells a side at zoom 0 falling to a
     * single quad above zoom 11, halving every second zoom level.
     *
     * The shape differs from MapLibre's — which halves every level from a base of 128 and floors at
     * 32 — because it is derived rather than tabulated: a cell's departure from the sphere goes as
     * the square of its angle, so a granularity that keeps it under half a pixel goes as the square
     * root of the tile's on-screen size. The floor at one quad is not a shortcut either: the spec's
     * own sagitta numbers put a frame-sized quad 0.44 logical pixels off the sphere at zoom 10, so a
     * tile at zoom 12 is flat to within a seventh of that.
     */
    @Test fun theGranularityFollowsTheCameraRatherThanTheWorld() {
        val expected = mapOf(
            0 to 64, 1 to 32, 2 to 32, 3 to 16, 4 to 16, 5 to 8, 6 to 8,
            7 to 4, 8 to 4, 9 to 2, 10 to 2, 11 to 1, 14 to 1, 22 to 1,
        )
        expected.forEach { (zoom, cells) ->
            val radius = globeRadiusLogicalPixels(512.0 * 2.0.pow(zoom))
            assertEquals(
                cells,
                globeGroundCellsPerTileSide(radius, 2.0 * PI / 2.0.pow(zoom)),
                "at zoom $zoom",
            )
        }
    }

    /**
     * A raster source whose maximum zoom is far below the camera's forces the frame to draw tiles
     * that are enormous on screen, and the rule answers with the cap rather than with a flat planet.
     * This is the arm that would be dead if the rule read the zoom instead of the tile.
     */
    @Test fun theGranularityRisesToTheCapWhenTheSourcesTilesAreCoarserThanTheCamera() {
        val radius = globeRadiusLogicalPixels(512.0 * 2.0.pow(14))
        assertEquals(128, globeGroundCellsPerTileSide(radius, 2.0 * PI / 32.0), "zoom 14 over lod 5")
        assertEquals(4, globeGroundCellsPerTileSide(radius, 2.0 * PI / 2048.0), "zoom 14 over lod 11")
    }

    /** The camera overload is the one a draw path calls, and it must agree with the primitive it
     * wraps rather than re-deriving the span. */
    @Test fun theCameraOverloadAgreesWithTheRadiusAndSpanItDerives() {
        val camera = resolve(zoom = 3.0)
        listOf(0, 3, 7, 12).forEach { lod ->
            assertEquals(
                globeGroundCellsPerTileSide(camera.radiusLogicalPixels, 2.0 * PI / (1L shl lod).toDouble()),
                globeGroundCellsPerTileSide(camera, lod),
                "at lod $lod",
            )
        }
    }

    /**
     * The grid is built once and shared by every tile of every frame at that granularity. A buffer
     * rebuilt per frame would pass every pixel assertion in the readback suite and is precisely what
     * the shared-grid design exists to avoid, so it is asserted on the call log.
     */
    @Test fun theGridIsBuiltOnceAndSharedByEveryTileAndEveryFrame() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawGlobeGround(binding, pipeline, tiles(3), FloatArray(16), cellsPerTileSide = 8)
        drawGlobeGround(binding, pipeline, tiles(3), FloatArray(16), cellsPerTileSide = 8)

        assertEquals(1, binding.log.count { it.startsWith("genVertexArrays") })
        assertEquals(2, binding.log.count { it.startsWith("genBuffers") }, "one vertex, one index")
        assertEquals(6, binding.log.count { it.startsWith("drawElements") }, "three tiles, twice")
        assertEquals(1, pipeline.grids.size)
        assertEquals(8, pipeline.grids.getValue(8).cellsPerSide)

        drawGlobeGround(binding, pipeline, tiles(1), FloatArray(16), cellsPerTileSide = 16)
        assertEquals(2, pipeline.grids.size, "a second granularity is a second cached grid")
        assertEquals(2, binding.log.count { it.startsWith("genVertexArrays") })
    }

    @Test fun deletionRemovesEveryCachedGridAndTheProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipeline = (
            createGlobeGroundPipeline(binding, ShaderDialect.GLES, cache)
                as GlobeGroundPipelineResult.Created
            ).pipeline
        drawGlobeGround(binding, pipeline, tiles(1), FloatArray(16), cellsPerTileSide = 4)
        drawGlobeGround(binding, pipeline, tiles(1), FloatArray(16), cellsPerTileSide = 32)
        binding.log.clear()

        deleteGlobeGroundPipeline(binding, cache, pipeline)

        assertEquals(2, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(2, binding.log.count { it.startsWith("deleteBuffers(2") }, "vertex and index")
        // Two, not one: the displacing program is compiled beside the flat one (Cycle E-terrain).
        assertEquals(2, binding.log.count { it.startsWith("deleteProgram") })
        assertTrue(pipeline.grids.isEmpty())
        assertNull(cache.program(pipeline.key))
        assertNull(cache.program(pipeline.terrain.key))
    }

    /**
     * ADR 0038's globe arm and ADR 0027's depth rule, in one pass: culling on before the first
     * draw, depth tested, depth writes off and never turned back on. The winding and the cull mode
     * are `drawFrame`'s, scene-wide, and are deliberately not restated here — a pass that set them
     * would be making a decision that belongs to the frame.
     */
    @Test fun theGlobeGroundCullsAndTestsDepthWithoutWritingItOrRestatingTheWinding() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGlobeGround(binding, pipeline, tiles(1), FloatArray(16), cellsPerTileSide = 4)

        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawElements") }
        assertTrue(firstDraw >= 0, "the globe ground must actually draw")
        assertTrue(binding.log.indexOfFirst { it == "enable(${hex(GL_CULL_FACE)})" } in 0 until firstDraw)
        assertTrue(binding.log.indexOfFirst { it == "enable(${hex(GL_DEPTH_TEST)})" } in 0 until firstDraw)
        assertTrue(binding.log.indexOfFirst { it == "depthMask(false)" } in 0 until firstDraw)
        assertFalse(binding.log.any { it == "depthMask(true)" })
        assertFalse(binding.log.any { it == "disable(${hex(GL_CULL_FACE)})" })
        assertFalse(binding.log.any { it.startsWith("frontFace") }, "the winding is drawFrame's")
        assertFalse(binding.log.any { it.startsWith("cullFace") }, "the cull mode is drawFrame's")
    }

    /**
     * One matrix for the frame, one `vec4` for each tile. That ratio is the whole economy of the
     * shared grid, and a per-tile matrix upload would mean the design had quietly reverted to the
     * Mercator ground's shape.
     */
    @Test fun theMatrixIsUploadedOncePerFrameAndTheEdgesOncePerTile() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGlobeGround(binding, pipeline, tiles(5), FloatArray(16), cellsPerTileSide = 4)

        assertEquals(1, binding.log.count { it.startsWith("uniformMatrix4fv") })
        assertEquals(5, binding.log.count { it.startsWith("uniform4f") })
        assertEquals(5, binding.log.count { it.startsWith("bindTexture") })
        assertEquals(5, binding.log.count { it.startsWith("drawElements") })
        assertTrue(binding.log.any { it == "drawElements(0x4,96,0x1403,0)" }, binding.log.toString())
    }

    @Test fun anEmptyTileListDrawsNothingAndBuildsNoGrid() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGlobeGround(binding, pipeline, emptyList(), FloatArray(16), cellsPerTileSide = 32)
        assertTrue(binding.log.isEmpty(), "an empty ground issues no GL call at all: ${binding.log}")
        assertTrue(pipeline.grids.isEmpty())
    }

    /**
     * **The number this design owes, measured rather than argued.**
     *
     * A globe-fixed vertex has the magnitude of the planet while the quantity being kept is a
     * screen-sized offset, and the whole of it is narrowed to `Float` before the GPU sees it. The
     * Mercator ground never meets this because `resolveBasemapTileQuad` rebases each tile against
     * the camera in `Double` first. This walks a tile's own grid at every zoom, evaluates the shader
     * exactly as written in `Float`, and compares the window position against the same expression in
     * `Double`.
     *
     * The measurement is printed in full because it is evidence for a decision the spec left open —
     * section 5 says build the globe at all zooms and add a Mercator transition only if the numbers
     * justify it — and these are some of those numbers.
     */
    @Test fun theFloatNarrowedGroundStaysSubPixelUntilTheMeasuredZoom() {
        val reported = StringBuilder("RenG globe ground Float narrowing, worst window displacement:")
        var firstZoomOverOnePixel = -1
        for (zoom in 0..22) {
            val camera = resolve(zoom = zoom.toDouble())
            val worst = worstNarrowingDisplacementPixels(camera, lod = zoom)
            reported.append(" z$zoom=${(worst * 1000.0).toInt() / 1000.0}px")
            if (firstZoomOverOnePixel < 0 && worst > 1.0) firstZoomOverOnePixel = zoom
        }
        println(reported)
        assertTrue(
            firstZoomOverOnePixel in 17..19,
            "measured at zoom 18 on this fixture -- 0.979 logical pixels at zoom 17 and 2.557 at " +
                "zoom 18. A path that narrowed twice, or that lost the Double composition of the " +
                "matrix, halves that and lands outside this range; a path that never broke at all " +
                "would report -1 and is not a globe. Measured: $firstZoomOverOnePixel",
        )
    }

    /**
     * The deviation function is the granularity rule's whole content, so its two terms are asserted
     * separately. The chord sagitta is exact at a full turn, where the small-angle form used
     * everywhere else is nonsense, and the Gudermannian warp is what makes a globe's grid denser
     * than a naive chord argument would ask for.
     */
    @Test fun theDeviationIsTheChordSagittaPlusTheGudermannianWarp() {
        val radius = 1000.0
        assertEquals(
            radius * (2.0 + (2.0 * PI) * (2.0 * PI) / 16.0),
            globeGroundCellDeviationLogicalPixels(radius, 2.0 * PI),
            absoluteTolerance = 1e-9,
        )
        // Halving the cell angle quarters the deviation, which is why the granularity goes as the
        // square root of a tile's on-screen size.
        val coarse = globeGroundCellDeviationLogicalPixels(radius, 0.01)
        val fine = globeGroundCellDeviationLogicalPixels(radius, 0.005)
        assertEquals(4.0, coarse / fine, absoluteTolerance = 1e-3)
    }

    /**
     * The shader's own expression, in `Float`, evaluated on the CPU: `mix` as the specification
     * defines it, then the tangent half-angle identities, then the rotation.
     *
     * Used by [theFloatNarrowedGroundStaysSubPixelUntilTheMeasuredZoom] to isolate the narrowing
     * from everything else. `exp` here is the platform's accurate one rather than a driver's, on
     * purpose: what is being measured is the cancellation, not `exp`, and the driver's own `exp` is
     * task 11's probe.
     */
    private fun narrowedDirection(edges: FloatArray, u: Float, v: Float): FloatArray {
        val longitude = edges[0] * (1.0f - u) + edges[1] * u
        val isometricLatitude = edges[2] * (1.0f - v) + edges[3] * v
        val tangentHalfAngle = exp(isometricLatitude)
        val tangentSquared = tangentHalfAngle * tangentHalfAngle
        val denominator = tangentSquared + 1.0f
        val sineLatitude = (tangentSquared - 1.0f) / denominator
        val cosineLatitude = 2.0f * tangentHalfAngle / denominator
        return floatArrayOf(
            cosineLatitude * kotlin.math.cos(longitude),
            cosineLatitude * kotlin.math.sin(longitude),
            sineLatitude,
        )
    }

    /** [narrowedDirection] in `Double`, which is [unitSphereDirection]'s own arithmetic. */
    private fun directionAt(edges: FloatArray, u: Float, v: Float): DoubleVector3 {
        val longitude = edges[0].toDouble() * (1.0 - u.toDouble()) + edges[1].toDouble() * u.toDouble()
        val isometricLatitude =
            edges[2].toDouble() * (1.0 - v.toDouble()) + edges[3].toDouble() * v.toDouble()
        val tangentHalfAngle = exp(isometricLatitude)
        val tangentSquared = tangentHalfAngle * tangentHalfAngle
        val denominator = tangentSquared + 1.0
        return DoubleVector3(
            x = 2.0 * tangentHalfAngle / denominator * kotlin.math.cos(longitude),
            y = 2.0 * tangentHalfAngle / denominator * kotlin.math.sin(longitude),
            z = (tangentSquared - 1.0) / denominator,
        )
    }

    private fun worstNarrowingDisplacementPixels(camera: ResolvedGlobeCamera, lod: Int): Double {
        val tileCount = (1L shl lod).toDouble()
        val tileY = (camera.mercatorAnchor.y * tileCount).toLong().coerceIn(0L, (1L shl lod) - 1L)
        val unwrappedX = (camera.mercatorAnchor.x * tileCount).toLong()
        val edges = globeGroundTileEdges(lod, tileY, unwrappedX)
        val narrowedMatrix = composeGlobeGroundUnitSphereToClip(camera)
        val exactMatrix = globeFixedViewProjection(camera)
        val radius = camera.radiusLogicalPixels
        val width = camera.outputPixelSize.width.toDouble()
        val height = camera.outputPixelSize.height.toDouble()

        var worst = 0.0
        listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f).forEach { u ->
            listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f).forEach { v ->
                val exactDirection = directionAt(edges, u, v)
                val exactW = exactMatrix[3, 0] * exactDirection.x * radius +
                    exactMatrix[3, 1] * exactDirection.y * radius +
                    exactMatrix[3, 2] * exactDirection.z * radius + exactMatrix[3, 3]
                if (exactW <= 0.0) return@forEach
                val exactX = (exactMatrix[0, 0] * exactDirection.x * radius +
                    exactMatrix[0, 1] * exactDirection.y * radius +
                    exactMatrix[0, 2] * exactDirection.z * radius + exactMatrix[0, 3]) / exactW
                val exactY = (exactMatrix[1, 0] * exactDirection.x * radius +
                    exactMatrix[1, 1] * exactDirection.y * radius +
                    exactMatrix[1, 2] * exactDirection.z * radius + exactMatrix[1, 3]) / exactW

                val d = narrowedDirection(edges, u, v)
                val w = narrowedMatrix[3] * d[0] + narrowedMatrix[7] * d[1] +
                    narrowedMatrix[11] * d[2] + narrowedMatrix[15]
                val x = (narrowedMatrix[0] * d[0] + narrowedMatrix[4] * d[1] +
                    narrowedMatrix[8] * d[2] + narrowedMatrix[12]) / w
                val y = (narrowedMatrix[1] * d[0] + narrowedMatrix[5] * d[1] +
                    narrowedMatrix[9] * d[2] + narrowedMatrix[13]) / w

                val displacement = max(
                    abs(x.toDouble() - exactX) * 0.5 * width,
                    abs(y.toDouble() - exactY) * 0.5 * height,
                )
                if (displacement.isFinite()) worst = max(worst, displacement)
            }
        }
        return worst
    }

    private fun resolve(zoom: Double): ResolvedGlobeCamera {
        val outcome = resolveGlobeCamera(
            Camera(
                latitude = TILTED_LATITUDE,
                unwrappedLongitude = TILTED_LONGITUDE,
                zoom = zoom,
                bearing = 37.0,
                pitch = 0.0,
            ),
            OUTPUT,
        )
        return (outcome as SpatialOutcome.Success<ResolvedGlobeCamera>).value
    }

    /**
     * The globe's half of Cycle E-terrain task 8: a tile with a DEM draws through the displacing
     * program, the frame's one matrix is re-uploaded for it, and the **radial** metre scale arrives.
     *
     * `radialMultiplePerMetre` is `globeMetresToLogicalPixels(R) / R` — no `1 / cos(latitude)`. The
     * value asserted below is deliberately not 1 and not the Mercator one, because a sphere that
     * copied Mercator's conversion agrees exactly at the equator and is 2x wrong at latitude 60,
     * which is the mistake `globeMetresToLogicalPixels`' own KDoc records.
     */
    @Test fun aGlobeTileWithADemDrawsThroughTheDisplacingProgramWithItsRadialScale() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val matrix = FloatArray(16) { it.toFloat() }
        binding.log.clear()

        drawGlobeGround(
            binding = binding,
            pipeline = pipeline,
            tiles = listOf(
                ResolvedGlobeGroundTile(
                    edges = globeGroundTileEdges(lod = 2, tileY = 1, unwrappedX = 1L),
                    texture = 4,
                    elevation = GroundTileDem(demTexture = 44, window = floatArrayOf(0f, 1f, 0f, 1f)),
                ),
                ResolvedGlobeGroundTile(
                    edges = globeGroundTileEdges(lod = 2, tileY = 1, unwrappedX = 2L),
                    texture = 5,
                ),
            ),
            unitSphereToClip = matrix,
            cellsPerTileSide = 8,
            elevation = GlobeGroundElevationFrame(
                dem = GroundDemUniforms(
                    decode = demDecodeCoefficients(DemEncoding.TERRARIUM),
                    interiorSizePx = 512,
                    exaggeration = 3.0f,
                ),
                radialMultiplePerMetre = 0.5f,
            ),
        )

        assertEquals(
            listOf("useProgram(${pipeline.terrain.program})", "useProgram(${pipeline.program})"),
            binding.log.filter { it.startsWith("useProgram") },
            "the displaced tile and the coverage gap beside it interleave in tile order",
        )
        assertEquals(
            2,
            binding.log.count { it.startsWith("uniformMatrix4fv(3,") },
            "the frame's one matrix is uploaded once per program, because a uniform belongs to the " +
                "program that was current when it was set: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform1f($RADIAL_PER_METRE_LOCATION,0.5)"),
            "the radial metre scale reaches the shader: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform2f($GLOBE_DEM_GRID_LOCATION,512.0,3.0)"),
            "the interior size and the exaggeration reach the shader: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform4f($GLOBE_DEM_DECODE_LOCATION,65280.0,255.0,0.99609375,-32768.0)"),
            "the Terrarium decode reaches the shader: ${binding.log}",
        )
        val demUnit = binding.log.indexOfFirst { it == "activeTexture(${hex(GL_TEXTURE0 + 1)})" }
        assertTrue(demUnit >= 0, "the DEM binds to its own texture unit: ${binding.log}")
        assertEquals("bindTexture(${hex(GL_TEXTURE_2D)},44)", binding.log[demUnit + 1])
    }

    /** A globe frame with no terrain never reaches the displacing program. */
    @Test fun aGlobeFrameWithNoTerrainDrawsEntirelyThroughTheFlatProgram() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawGlobeGround(binding, pipeline, tiles(2), FloatArray(16), cellsPerTileSide = 4)

        assertTrue(binding.log.contains("useProgram(${pipeline.program})"))
        assertFalse(
            binding.log.contains("useProgram(${pipeline.terrain.program})"),
            "nothing may reach the displacing program without a DEM: ${binding.log}",
        )
    }

    private fun tiles(count: Int): List<ResolvedGlobeGroundTile> = List(count) { index ->
        ResolvedGlobeGroundTile(
            edges = globeGroundTileEdges(lod = 2, tileY = 1, unwrappedX = index.toLong()),
            texture = index + 1,
        )
    }

    private fun createdPipeline(binding: RecordingGlBinding): GlobeGroundPipeline =
        (
            createGlobeGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache())
                as GlobeGroundPipelineResult.Created
            ).pipeline

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        GLOBE_GROUND_UNIT_SPHERE_TO_CLIP_UNIFORM_NAME to 3,
        GLOBE_GROUND_TILE_EDGES_UNIFORM_NAME to 5,
        GROUND_TEXTURE_UNIFORM_NAME to 7,
        GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME to 9,
        GLOBE_GROUND_RADIAL_PER_METRE_UNIFORM_NAME to RADIAL_PER_METRE_LOCATION,
        GROUND_DEM_SAMPLER_UNIFORM_NAME to 21,
        GROUND_DEM_WINDOW_UNIFORM_NAME to 22,
        GROUND_DEM_DECODE_UNIFORM_NAME to GLOBE_DEM_DECODE_LOCATION,
        GROUND_DEM_GRID_UNIFORM_NAME to GLOBE_DEM_GRID_LOCATION,
    )

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

    private companion object {
        const val TILTED_LATITUDE: Double = 48.8566
        const val TILTED_LONGITUDE: Double = 722.3522
        val OUTPUT: OutputPixelSize = OutputPixelSize(width = 960, height = 540)
        const val RADIAL_PER_METRE_LOCATION: Int = 20
        const val GLOBE_DEM_DECODE_LOCATION: Int = 23
        const val GLOBE_DEM_GRID_LOCATION: Int = 24
    }

    /**
     * The cap reuses its tile's longitude `Float`s **identically**, not equally.
     *
     * `globeGroundTileEdges`' own KDoc measures what a recomputed-but-equal longitude costs: up to
     * `2.384e-7` radians of disagreement at `u = 1`, which is 5.09 logical pixels of crack at zoom
     * 18. A cap sharing an edge with its tile has exactly that exposure along the whole seam, so
     * this asserts bit equality rather than approximate equality.
     */
    @Test
    fun polarCapsShareTheirTilesLongitudeBitsExactly() {
        val tile = globeGroundTileEdges(lod = 3, tileY = 0, unwrappedX = 5)
        val north = globeGroundPolarCapEdges(tile, north = true)
        val south = globeGroundPolarCapEdges(tile, north = false)
        assertEquals(tile[0], north[0])
        assertEquals(tile[1], north[1])
        assertEquals(tile[0], south[0])
        assertEquals(tile[1], south[1])
    }

    /**
     * North stays at the grid's `v = 0` end in both caps, which is what keeps a cap winding exactly
     * as a tile does so ADR 0038's cull removes the far one with no special case.
     *
     * The north cap therefore runs pole -> tile edge and the south runs tile edge -> pole, and each
     * meets its tile on the shared isometric latitude rather than near it.
     */
    @Test
    fun polarCapsRunNorthToSouthAndMeetTheirTileExactly() {
        val tile = globeGroundTileEdges(lod = 2, tileY = 0, unwrappedX = 1)
        val north = globeGroundPolarCapEdges(tile, north = true)
        assertEquals(POLAR_CAP_ISOMETRIC_LATITUDE, north[2])
        assertEquals(tile[2], north[3])
        assertTrue(north[2] > north[3], "a north cap descends from the pole to its tile")

        val bottom = globeGroundTileEdges(lod = 2, tileY = 3, unwrappedX = 1)
        val south = globeGroundPolarCapEdges(bottom, north = false)
        assertEquals(bottom[3], south[2])
        assertEquals(-POLAR_CAP_ISOMETRIC_LATITUDE, south[3])
        assertTrue(south[2] > south[3], "a south cap descends from its tile to the pole")
    }

    /**
     * The cap's isometric latitude is the pole *in the arithmetic the shader actually performs*.
     *
     * The shader forms `t = exp(psi)` and `sin(latitude) = (t^2 - 1) / (t^2 + 1)`. This reproduces
     * that in `Float`, because the claim is about rounding rather than about mathematics: `t^2` is
     * large enough that the `-1` and `+1` vanish and the quotient is exactly `1`. Asserting it in
     * `Double` would prove nothing about the shader.
     */
    @Test
    fun theCapsIsometricLatitudeRoundsToThePoleInFloat() {
        val tangentHalfAngle = exp(POLAR_CAP_ISOMETRIC_LATITUDE)
        val tangentSquared = tangentHalfAngle * tangentHalfAngle
        val sineLatitude = (tangentSquared - 1.0f) / (tangentSquared + 1.0f)
        val cosineLatitude = 2.0f * tangentHalfAngle / (tangentSquared + 1.0f)
        assertEquals(1.0f, sineLatitude, "the cap's north edge is the pole in float")
        assertTrue(
            cosineLatitude < 1.0e-8f,
            "the cap's residual off-axis term is $cosineLatitude, which must be a rounding artefact",
        )
        assertTrue(tangentSquared.isFinite(), "exp(psi)^2 must not overflow float")
    }

    /** An ordinary tile samples its whole texture; a cap pins both ends to the row it stretches. */
    @Test
    fun onlyACapPinsItsTextureRow() {
        assertEquals(listOf(0.0f, 1.0f), ORDINARY_TILE_UV_V.toList())
        assertEquals(listOf(0.0f, 0.0f), NORTH_POLAR_CAP_UV_V.toList())
        assertEquals(listOf(1.0f, 1.0f), SOUTH_POLAR_CAP_UV_V.toList())
        assertEquals(
            ORDINARY_TILE_UV_V.toList(),
            ResolvedGlobeGroundTile(edges = floatArrayOf(0f, 1f, 1f, 0f), texture = 1).uvV.toList(),
            "a tile built without a uv range must sample exactly what it did before caps existed",
        )
    }

}
