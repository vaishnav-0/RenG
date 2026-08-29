package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
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
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertTrue(pipeline.grids.isEmpty())
        assertNull(cache.program(pipeline.key))
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
     * ADR 0027, superseding ADR 0025 on this point. This test used to assert the exact opposite —
     * that `depthMask(false)` never appears, "or terrain can never occlude anything" — and that
     * reasoning shipped two visible defects. Keeping the ground's depth writes bought exactly one
     * thing, an occluder for content below altitude 0, and cost a coplanar `Geometry` up to 100% of
     * its pixels frame to frame plus the lower half of every map-anchored billboard at any nonzero
     * pitch. The ground still *tests* depth, so terrain and models can occlude it once they exist
     * and write depth of their own.
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

    private fun createdPipeline(binding: RecordingGlBinding): GroundPipeline =
        (createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as GroundPipelineResult.Created)
            .pipeline

    private fun resolvedTile(texture: Int = 1): ResolvedGroundTile =
        ResolvedGroundTile(modelViewProjection = FloatArray(16), texture = texture)

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
        GROUND_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
    )

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

    private companion object {
        const val MODEL_VIEW_PROJECTION_LOCATION: Int = 3
        const val TEXTURE_LOCATION: Int = 7
    }
}
