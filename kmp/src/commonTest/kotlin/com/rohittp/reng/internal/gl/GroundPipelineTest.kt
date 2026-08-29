package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import com.rohittp.reng.internal.terrain.DemEncoding
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RenG's own ground pipeline: one program compiled through [GlProgramCache] and keyed by an
 * [InternalPipelineRole], a [GroundGrid] per granularity built on first use and reused by every tile
 * of every later frame, and a per-instance model-view-projection uniform plus one texture.
 *
 * The geometry half moved with Cycle E-terrain. It was one unit quad allocated at setup and drawn
 * with `glDrawArrays`; it is now a subdivided grid drawn with `glDrawElements`, because four
 * vertices cannot be displaced by terrain. `GroundGridTest` owns the lattice itself and the claim
 * that a single cell reproduces the quad exactly; what is asserted here is the pipeline around it —
 * what setup allocates, what a draw issues, and what deletion frees.
 */
class GroundPipelineTest {
    @Test fun theRosterAddsGroundAtWireValueThreeWithoutRenumberingTheOthers() {
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
        assertEquals(3, InternalPipelineRole.GROUND.wireValue)
    }

    @Test fun theGroundSourcesAreAcceptedShaderProfileSources() {
        assertTrue(GROUND_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(GROUND_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(GROUND_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(GROUND_FRAGMENT_SOURCE) != null)
    }

    /**
     * The whole ADR 0018 identity chain's last mile, now stated in GLSL: `v = 0` must land at local
     * `+y`, because local `+y` is NORTH for a ground tile and row zero of a rendered basemap tile is
     * its north edge. A v-flip mirrors every tile about its own centre line — invisible on a
     * solid-coloured tile and catastrophic on a real map.
     *
     * **This asserts source text, and that is a real weakening worth stating plainly.** Before the
     * grid, the convention was four numbers in a `FloatArray` and a unit test could read them. It is
     * now one subtraction inside a shader no unit test can execute, so the honest unit-level
     * instrument is the text itself; the assertion that this text is also *correct* is
     * `runBasemapReadbackSuite`'s asymmetric fixture on a real driver, and a flip fails it in pixels.
     */
    @Test fun theVertexStagePutsTextureRowZeroAtTheNorthEdge() {
        assertTrue(
            GROUND_VERTEX_SOURCE.contains("vec2 position = vec2(rengGroundGrid.x - 0.5, 0.5 - rengGroundGrid.y);"),
            "u must run west-to-east and v north-to-south, row zero at the tile's north edge: " +
                GROUND_VERTEX_SOURCE,
        )
        assertTrue(
            GROUND_VERTEX_SOURCE.contains("rengGroundUv = rengGroundGrid;"),
            "the grid coordinate IS the texture coordinate: " + GROUND_VERTEX_SOURCE,
        )
    }

    /**
     * Setup builds a program and **no geometry at all**, which is the shape change Cycle E-terrain
     * makes here and the globe ground already made in Cycle G: which granularities a session reaches
     * follows the camera, so a grid is built on the draw path or not at all.
     */
    @Test fun creationBuildsAProgramAndNoGeometryAtAll() {
        val binding = newBinding()
        val created = createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as GroundPipelineResult.Created
        assertTrue(created.pipeline.program > 0)
        assertTrue(created.pipeline.grids.isEmpty(), "no granularity has been asked for yet")
        assertFalse(binding.log.any { it.startsWith("genVertexArrays") }, "setup allocates no grid")
        assertFalse(binding.log.any { it.startsWith("bufferData") }, "setup uploads nothing")
        assertEquals(MODEL_VIEW_PROJECTION_LOCATION, created.pipeline.modelViewProjectionUniformLocation)
        assertEquals(TEXTURE_LOCATION, created.pipeline.textureUniformLocation)
    }

    @Test fun theGroundProgramIsADistinctCacheEntryFromTheStickerProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val ground = (
            createGroundPipeline(binding, ShaderDialect.GLES, cache) as GroundPipelineResult.Created
            ).pipeline
        val sticker = (
            createStickerPipeline(binding, ShaderDialect.GLES, cache) as StickerPipelineResult.Created
            ).pipeline
        assertTrue(ground.key != sticker.key, "two internal pipelines must not share one program key")
        assertTrue(ground.program != sticker.program)
    }

    @Test fun deletionRemovesEveryCachedGridAndTheProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipeline = (
            createGroundPipeline(binding, ShaderDialect.GLES, cache) as GroundPipelineResult.Created
            ).pipeline
        drawGround(binding, pipeline, listOf(resolvedTile()), cellsPerTileSide = 4)
        drawGround(binding, pipeline, listOf(resolvedTile()), cellsPerTileSide = 32)
        binding.log.clear()

        deleteGroundPipeline(binding, cache, pipeline)

        assertEquals(2, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(2, binding.log.count { it.startsWith("deleteBuffers(2") }, "vertex and index")
        // Two, not one: Cycle E-terrain compiles the displacing program beside the flat one, and a
        // pipeline that deleted only the program it drew last frame would leak the other for the
        // renderer's whole life.
        assertEquals(2, binding.log.count { it.startsWith("deleteProgram") })
        assertTrue(pipeline.grids.isEmpty())
        assertNull(cache.program(pipeline.key))
        assertNull(cache.program(pipeline.terrain.key))
    }

    /**
     * One grid per granularity, shared by every tile of every frame — the whole economy the shared
     * lattice buys, and invisible to every pixel: a grid rebuilt per tile draws an identical frame
     * and leaks a vertex array and two buffers each time. Asserted on the call log for that reason.
     */
    @Test fun theGridIsBuiltOnceAndSharedByEveryTileAndEveryFrame() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawGround(binding, pipeline, listOf(resolvedTile(1), resolvedTile(2), resolvedTile(3)), cellsPerTileSide = 8)
        drawGround(binding, pipeline, listOf(resolvedTile(1), resolvedTile(2), resolvedTile(3)), cellsPerTileSide = 8)

        assertEquals(1, binding.log.count { it.startsWith("genVertexArrays") })
        assertEquals(2, binding.log.count { it.startsWith("genBuffers") }, "one vertex, one index")
        assertEquals(6, binding.log.count { it.startsWith("drawElements") }, "three tiles, twice")
        assertEquals(1, pipeline.grids.size)
        assertEquals(8, pipeline.grids.getValue(8).cellsPerSide)

        drawGround(binding, pipeline, listOf(resolvedTile()), cellsPerTileSide = 16)
        assertEquals(2, pipeline.grids.size, "a second granularity is a second cached grid")
        assertEquals(2, binding.log.count { it.startsWith("genVertexArrays") })
    }

    /**
     * The default granularity is one cell, and one cell is the two triangles the four-vertex quad
     * drew. Nothing in production passes a granularity yet — displacement is what will give a caller
     * a reason to — so this is the arm every Mercator frame currently takes, and it must not have
     * changed what it draws.
     */
    @Test fun theDefaultGranularityDrawsTheTwoTrianglesTheQuadDrew() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()))
        assertEquals(
            1,
            binding.log.count { it == "drawElements(${hex(GL_TRIANGLES)},6,${hex(GL_UNSIGNED_SHORT)},0)" },
            "the default ground draw is six indices, two triangles, one cell: " + binding.log,
        )
        assertEquals(setOf(1), pipeline.grids.keys)
    }

    /**
     * ADR 0027, superseding ADR 0025 on this point, **narrowed by ADR 0039 to the frame with no
     * terrain — which this fixture is.** This test used to assert the exact opposite — that
     * `depthMask(false)` never appears, "or terrain can never occlude anything" — and that reasoning
     * shipped two visible defects. Keeping the ground's depth writes bought exactly one thing, an
     * occluder for content below altitude 0, and cost a coplanar `Geometry` up to 100% of its pixels
     * frame to frame plus the lower half of every map-anchored billboard at any nonzero pitch.
     *
     * Terrain is the case where that trade inverts, and it is
     * [aDisplacedGroundWritesDepthOnceBeforeItDrawsAnything]'s. This case keeps the other 28 of the
     * corpus's 34 styles honest: a build that made the write unconditional fails **here**, on
     * `depthMask(true)` appearing in a frame with no DEM at all.
     */
    @Test fun theGroundDrawsDepthTestedAndWritesNoDepth() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()))
        assertTrue(binding.log.contains("enable(${hex(GL_DEPTH_TEST)})"), "the ground is depth-tested")
        val maskedOff = binding.log.indexOfFirst { it == "depthMask(false)" }
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawElements") }
        assertTrue(firstDraw >= 0, "the ground must actually draw")
        assertTrue(
            maskedOff in 0 until firstDraw,
            "the ground must turn depth writes off before it draws (ADR 0027): " +
                "a coplanar altitude-0 Geometry cannot near-tie with depth that was never written",
        )
        assertFalse(binding.log.any { it == "depthMask(true)" }, "the ground never re-enables depth writes")
    }

    @Test fun theBlendModeIsPremultipliedNotStraightAlpha() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()))
        assertTrue(binding.log.any { it == "blendFuncSeparate(0x1,0x303,0x1,0x303)" })
        assertFalse(binding.log.any { it.startsWith("blendFuncSeparate(0x302,") })
    }

    @Test fun tilesDrawInTheOrderTheyAreGiven() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(
            binding,
            pipeline,
            listOf(resolvedTile(texture = 11), resolvedTile(texture = 22)),
        )
        val first = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},11)" }
        val second = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},22)" }
        assertTrue(first in 0 until second)
        assertEquals(2, binding.log.count { it.startsWith("drawElements") })
    }

    @Test fun anEmptyGroundIssuesNoGlCallsAtAll() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, emptyList())
        assertContentEquals(emptyList(), binding.log)
    }

    @Test fun drawingBindsBothDeclaredUniformsAtTheirResolvedLocations() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()))
        assertTrue(binding.log.any { it == "uniformMatrix4fv($MODEL_VIEW_PROJECTION_LOCATION,1,false)" })
        assertTrue(binding.log.any { it == "uniform1i($TEXTURE_LOCATION,0)" })
    }

    @Test fun drawingSkipsBothUniformBindsWhenNeitherIsDeclared() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createdPipeline(binding)
        assertEquals(-1, pipeline.modelViewProjectionUniformLocation)
        assertEquals(-1, pipeline.textureUniformLocation)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()))
        assertFalse(binding.log.any { it.startsWith("uniformMatrix4fv") })
        assertFalse(binding.log.any { it.startsWith("uniform1i") })
    }

    /**
     * ADR 0038's ownership half, and the whole of it for this pass. Before Cycle G `drawGround` set
     * no cull state at all and inherited whatever the caller had left enabled — harmless only
     * because the ground's triangles happen to wind counter-clockwise. The disable is what makes a
     * mercator frame's pixels a function of this pass rather than of its caller.
     *
     * **The globe arm this pair used to have is gone**, with task 10: `drawGround` is the Mercator
     * ground and `drawGlobeGround` is the globe's, and the enable ADR 0038 asks for on a sphere is
     * unconditional in the latter (`GlobeGroundPipelineTest`). Two cases here — "the globe enables
     * culling before it draws" and "the two modes differ by exactly one GL call" — measured an arm
     * that no longer exists.
     */
    @Test fun theGroundDisablesCullingRatherThanInheritingIt() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()))
        val disabled = binding.log.indexOfFirst { it == "disable(${hex(GL_CULL_FACE)})" }
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawElements") }
        assertTrue(firstDraw >= 0, "the ground must actually draw")
        assertTrue(
            disabled in 0 until firstDraw,
            "under mercator the ground must disable culling before it draws, so no mercator pixel " +
                "depends on what the caller left enabled: ${binding.log}",
        )
        assertFalse(
            binding.log.any { it == "enable(${hex(GL_CULL_FACE)})" },
            "mercator must never enable culling",
        )
    }

    /**
     * A frame with no terrain must never reach the displacing program — which is the whole reason
     * there are two of them. Three shipped releases drew through [GroundPipeline.program], and this
     * is the assertion that keeps them drawing through it rather than through a displacing program
     * disabled by a zero uniform.
     */
    @Test fun aFrameWithNoTerrainDrawsEntirelyThroughTheFlatProgram() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawGround(binding, pipeline, listOf(resolvedTile(), resolvedTile(2)), cellsPerTileSide = 4)

        assertTrue(
            binding.log.contains("useProgram(${pipeline.program})"),
            "a frame with no terrain draws through the program 0.3.0 shipped: ${binding.log}",
        )
        assertFalse(
            binding.log.contains("useProgram(${pipeline.terrain.program})"),
            "nothing may reach the displacing program without a DEM: ${binding.log}",
        )
        assertFalse(
            binding.log.any { it.startsWith("uniform4f($DEM_WINDOW_LOCATION") },
            "no DEM window is uploaded by a frame with no terrain: ${binding.log}",
        )
        assertFalse(
            binding.log.any { it == "activeTexture(${hex(GL_TEXTURE0 + 1)})" },
            "the DEM texture unit is untouched by a frame with no terrain: ${binding.log}",
        )
    }

    /**
     * A tile with a DEM draws through the displacing program, and every uniform that program needs
     * arrives: the decode, the interior size and exaggeration, the metre scale, the tile's own window
     * and Mercator extent, and the DEM bound to its own texture unit.
     *
     * **Asserted on the values rather than on the calls**, because a uniform uploaded with the wrong
     * argument is exactly as invisible as one not uploaded at all — and because the exaggeration is
     * **2** here, the value at which an honoured multiplier and a dropped one stop agreeing.
     */
    @Test fun aTileWithADemDrawsThroughTheDisplacingProgramWithEveryUniformItNeeds() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawGround(
            binding = binding,
            pipeline = pipeline,
            tiles = listOf(demTile(texture = 11, demTexture = 22)),
            cellsPerTileSide = 8,
            elevation = elevationFrame(exaggeration = 2.0f),
        )

        assertTrue(
            binding.log.contains("useProgram(${pipeline.terrain.program})"),
            "a tile with a DEM draws through the displacing program: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform4f($DEM_DECODE_LOCATION,1671168.0,6528.0,25.5,-10000.0)"),
            "the Mapbox decode reaches the shader: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform2f($DEM_GRID_LOCATION,256.0,2.0)"),
            "the interior size and the exaggeration reach the shader: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform1f($ELEVATION_SCALE_LOCATION,0.25)"),
            "the equatorial metre scale reaches the shader: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform4f($DEM_WINDOW_LOCATION,0.25,0.5,0.5,0.75)"),
            "the tile's own window into its source DEM reaches the shader: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform2f($MERCATOR_Y_LOCATION,0.5,0.53125)"),
            "the tile's own mercator extent reaches the latitude term: ${binding.log}",
        )
        val demUnit = binding.log.indexOfFirst { it == "activeTexture(${hex(GL_TEXTURE0 + 1)})" }
        assertTrue(demUnit >= 0, "the DEM binds to its own texture unit: ${binding.log}")
        assertEquals(
            "bindTexture(${hex(GL_TEXTURE_2D)},22)",
            binding.log[demUnit + 1],
            "the DEM is what binds to that unit: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform1i($DEM_SAMPLER_LOCATION,1)"),
            "the sampler names the unit the DEM was bound to: ${binding.log}",
        )
    }

    /**
     * ADR 0041's coverage gap, in the draw: one tile without a DEM among tiles with one draws flat,
     * **in place**, without reordering the ground around it.
     *
     * Partitioning the tiles by program would have been cheaper in `useProgram` calls and would
     * change which of two overlapping alpha edges composites last. [drawGround]'s order is a
     * contract — it is the whole of the map regime's rule inside the ground pass — so the programs
     * interleave instead.
     */
    @Test fun aCoverageGapDrawsFlatInPlaceRatherThanReorderingTheGround() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawGround(
            binding = binding,
            pipeline = pipeline,
            tiles = listOf(
                demTile(texture = 11, demTexture = 22),
                resolvedTile(texture = 12),
                demTile(texture = 13, demTexture = 22),
            ),
            cellsPerTileSide = 8,
            elevation = elevationFrame(),
        )

        val programs = binding.log.filter { it.startsWith("useProgram") }
        assertEquals(
            listOf(
                "useProgram(${pipeline.terrain.program})",
                "useProgram(${pipeline.program})",
                "useProgram(${pipeline.terrain.program})",
            ),
            programs,
            "the two programs interleave in tile order rather than partitioning the ground",
        )
        val colourTextures = listOf(11, 12, 13).map { "bindTexture(${hex(GL_TEXTURE_2D)},$it)" }
        val binds = binding.log.filter { it in colourTextures }
        assertEquals(
            listOf(
                "bindTexture(${hex(GL_TEXTURE_2D)},11)",
                "bindTexture(${hex(GL_TEXTURE_2D)},12)",
                "bindTexture(${hex(GL_TEXTURE_2D)},13)",
            ),
            binds,
            "the frame's ground order is unchanged by the gap: ${binding.log}",
        )
        assertEquals(3, binding.log.count { it.startsWith("drawElements") }, "every tile still draws")
    }

    /**
     * **ADR 0039: the ground writes depth in a displaced frame, once, before it draws anything.**
     *
     * Once rather than per tile, and that is the ADR's own ruling rather than an economy: a per-tile
     * flip churns state inside the loop and would make a coplanar `Geometry` win over a flat coverage
     * gap while losing to the displaced tile beside it, which is a worse picture than either
     * consistent answer. The mask must also be set **before** the first draw, or the first tile of
     * every frame renders under whatever the caller left — and `drawFrame` leaves it on around its
     * own depth clear, so "whatever the caller left" is not a stable value either.
     */
    @Test fun aDisplacedGroundWritesDepthOnceBeforeItDrawsAnything() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(
            binding,
            pipeline,
            listOf(demTile(texture = 11, demTexture = 21), demTile(texture = 12, demTexture = 22)),
            cellsPerTileSide = 2,
            elevation = elevationFrame(exaggeration = 2.0f),
        )

        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawElements") }
        assertTrue(firstDraw >= 0, "the ground must actually draw")
        assertEquals(
            1,
            binding.log.count { it == "depthMask(true)" },
            "one mask for the whole pass, never one per tile (ADR 0039): " + binding.log,
        )
        assertTrue(
            binding.log.indexOf("depthMask(true)") in 0 until firstDraw,
            "the displaced ground must turn depth writes on before it draws: " + binding.log,
        )
        assertFalse(
            binding.log.any { it == "depthMask(false)" },
            "nothing in the ground pass turns the write back off; the geometry pass owns that: " +
                binding.log,
        )
    }

    /**
     * **The condition is the frame's, not the tile's.** A frame with terrain whose *first* tile lost
     * its DEM still writes: the pass is displaced if any of it is, so the flat tile among displaced
     * neighbours draws under the same mask rather than flipping it back.
     *
     * The gap is put first deliberately. The mask is set before the loop and the loop's own program
     * switch starts flat here, so a build that derived the frame's answer from the *first* tile — the
     * cheapest wrong reading of "is this frame displaced" — writes nothing at all and fails.
     */
    @Test fun aFrameWhoseFirstTileLostItsDemStillWritesDepthForTheWholePass() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(
            binding,
            pipeline,
            listOf(resolvedTile(texture = 11), demTile(texture = 12, demTexture = 22)),
            cellsPerTileSide = 2,
            elevation = elevationFrame(exaggeration = 2.0f),
        )

        assertEquals(
            1,
            binding.log.count { it == "depthMask(true)" },
            "one displaced tile makes the whole pass write (ADR 0039): " + binding.log,
        )
        assertEquals(2, binding.log.count { it.startsWith("drawElements") }, "both tiles still draw")
    }

    /**
     * The other half of the condition, and the one ADR 0041 creates: a frame that declares terrain
     * and whose every tile turned out to have no DEM has no relief anywhere, so it is ADR 0027's
     * frame and writes nothing. `drawGround` sees that as an elevation frame with no tile carrying
     * one — the shape `SceneContent` hands it when a whole frame's DEM acquisition came back empty.
     */
    @Test fun aTerrainFrameWithNoDemOnAnyTileWritesNoDepth() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(
            binding,
            pipeline,
            listOf(resolvedTile(texture = 11), resolvedTile(texture = 12)),
            cellsPerTileSide = 2,
            elevation = elevationFrame(exaggeration = 2.0f),
        )

        assertFalse(
            binding.log.any { it == "depthMask(true)" },
            "a declared terrain with no DEM anywhere is a flat ground (ADR 0041), and a flat ground " +
                "writes no depth (ADR 0039): " + binding.log,
        )
        assertEquals(2, binding.log.count { it.startsWith("drawElements") }, "both tiles still draw flat")
    }

    private fun demTile(texture: Int, demTexture: Int): ResolvedGroundTile = ResolvedGroundTile(
        modelViewProjection = FloatArray(16),
        texture = texture,
        elevation = MercatorGroundTileDem(
            dem = GroundTileDem(
                demTexture = demTexture,
                // A quarter of the source tile: not the whole of it, and not centred, so a window
                // dropped or transposed on the way to the shader is visible in the uploaded values.
                window = floatArrayOf(0.25f, 0.5f, 0.5f, 0.75f),
                tileSideMetres = 1_252_344.27f,
            ),
            mercatorY = mercatorTileYEdges(lod = 5, tileY = 16),
        ),
    )

    private fun elevationFrame(exaggeration: Float = 1.0f): MercatorGroundElevationFrame =
        MercatorGroundElevationFrame(
            dem = GroundDemUniforms(
                decode = demDecodeCoefficients(DemEncoding.MAPBOX),
                interiorSizePx = 256,
                exaggeration = exaggeration,
            ),
            equatorialLogicalPixelsPerMetre = 0.25f,
        )

    private fun createdPipeline(binding: RecordingGlBinding): GroundPipeline =
        (createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as GroundPipelineResult.Created)
            .pipeline

    private fun resolvedTile(texture: Int = 1): ResolvedGroundTile =
        ResolvedGroundTile(modelViewProjection = FloatArray(16), texture = texture)

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
        GROUND_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
        GROUND_MERCATOR_Y_UNIFORM_NAME to MERCATOR_Y_LOCATION,
        GROUND_ELEVATION_SCALE_UNIFORM_NAME to ELEVATION_SCALE_LOCATION,
        GROUND_DEM_SAMPLER_UNIFORM_NAME to DEM_SAMPLER_LOCATION,
        GROUND_DEM_WINDOW_UNIFORM_NAME to DEM_WINDOW_LOCATION,
        GROUND_DEM_DECODE_UNIFORM_NAME to DEM_DECODE_LOCATION,
        GROUND_DEM_GRID_UNIFORM_NAME to DEM_GRID_LOCATION,
    )

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

    private companion object {
        const val MODEL_VIEW_PROJECTION_LOCATION: Int = 3
        const val TEXTURE_LOCATION: Int = 7
        const val MERCATOR_Y_LOCATION: Int = 11
        const val ELEVATION_SCALE_LOCATION: Int = 12
        const val DEM_SAMPLER_LOCATION: Int = 13
        const val DEM_WINDOW_LOCATION: Int = 14
        const val DEM_DECODE_LOCATION: Int = 15
        const val DEM_GRID_LOCATION: Int = 16
    }
}
