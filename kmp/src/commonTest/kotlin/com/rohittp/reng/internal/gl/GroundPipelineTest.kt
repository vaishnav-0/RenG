package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RenG's own ground pipeline, built in the shape [StickerPipeline] and [GeometryPipeline] already
 * established: one program compiled through [GlProgramCache] and keyed by an
 * [InternalPipelineRole], one unit quad allocated once and reused by every tile in every frame, and
 * a per-instance model-view-projection uniform plus one texture.
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
     * The quad's texture coordinates are the whole ADR 0018 identity chain's last mile: `v = 0` must
     * sit at local `+y`, because local `+y` is NORTH for a ground tile and row zero of a rendered
     * basemap tile is its north edge. A v-flip here mirrors every tile about its own centre line, a
     * defect that is invisible on a solid-coloured tile and catastrophic on a real map.
     */
    @Test fun theUnitQuadPutsTextureRowZeroAtTheNorthEdge() {
        // x, y, u, v per vertex, in triangle-strip order.
        assertEquals(16, GROUND_QUAD.size)
        val vertices = (0 until 4).map { index ->
            listOf(
                GROUND_QUAD[index * 4],
                GROUND_QUAD[index * 4 + 1],
                GROUND_QUAD[index * 4 + 2],
                GROUND_QUAD[index * 4 + 3],
            )
        }
        vertices.forEach { (x, y, u, v) ->
            assertEquals(if (x < 0f) 0.0f else 1.0f, u, "u must run west-to-east")
            assertEquals(if (y > 0f) 0.0f else 1.0f, v, "v must run north-to-south, row zero at north")
        }
    }

    @Test fun creationBuildsAProgramAQuadAndTwoAttributes() {
        val binding = newBinding()
        val created = createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as GroundPipelineResult.Created
        assertTrue(created.pipeline.program > 0)
        assertTrue(created.pipeline.vertexArray > 0)
        assertTrue(created.pipeline.vertexBuffer > 0)
        assertEquals(2, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(2, binding.log.count { it.startsWith("vertexAttribPointer") })
        assertTrue(binding.log.any { it.startsWith("bufferData(0x8892,64") })
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

    @Test fun deletionRemovesTheQuadAndTheProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipeline = (
            createGroundPipeline(binding, ShaderDialect.GLES, cache) as GroundPipelineResult.Created
            ).pipeline
        binding.log.clear()
        deleteGroundPipeline(binding, cache, pipeline)
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertNull(cache.program(pipeline.key))
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
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
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
        assertEquals(2, binding.log.count { it.startsWith("drawArrays") })
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
     * because [GROUND_QUAD] happens to wind counter-clockwise. The disable is what makes a mercator
     * frame's pixels a function of this pass rather than of its caller.
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
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
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
