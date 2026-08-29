package com.rohittp.reng.internal.gl

import com.rohittp.reng.ProjectionMode
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
        drawGround(binding, pipeline, listOf(resolvedTile()), ProjectionMode.MERCATOR)
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
        drawGround(binding, pipeline, listOf(resolvedTile()), ProjectionMode.MERCATOR)
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
            ProjectionMode.MERCATOR,
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
        drawGround(binding, pipeline, emptyList(), ProjectionMode.MERCATOR)
        assertContentEquals(emptyList(), binding.log)
    }

    @Test fun drawingBindsBothDeclaredUniformsAtTheirResolvedLocations() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()), ProjectionMode.MERCATOR)
        assertTrue(binding.log.any { it == "uniformMatrix4fv($MODEL_VIEW_PROJECTION_LOCATION,1,false)" })
        assertTrue(binding.log.any { it == "uniform1i($TEXTURE_LOCATION,0)" })
    }

    @Test fun drawingSkipsBothUniformBindsWhenNeitherIsDeclared() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createdPipeline(binding)
        assertEquals(-1, pipeline.modelViewProjectionUniformLocation)
        assertEquals(-1, pipeline.textureUniformLocation)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()), ProjectionMode.MERCATOR)
        assertFalse(binding.log.any { it.startsWith("uniformMatrix4fv") })
        assertFalse(binding.log.any { it.startsWith("uniform1i") })
    }

    /**
     * ADR 0038's ownership half, mercator arm. Before this cycle `drawGround` set no cull state at
     * all and inherited whatever the caller had left enabled — harmless only because [GROUND_QUAD]
     * happens to wind counter-clockwise. The disable is what makes a mercator frame's pixels a
     * function of this pass rather than of its caller.
     */
    @Test fun underMercatorTheGroundDisablesCullingRatherThanInheritingIt() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()), ProjectionMode.MERCATOR)
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

    /**
     * ADR 0038's globe arm: on a sphere the far hemisphere is exactly the back-facing set, so the
     * enable is the whole mechanism. `drawFrame` has already established `GL_CCW`/`GL_BACK` for the
     * scene, which is why neither is restated here.
     */
    @Test fun onAGlobeTheGroundEnablesCullingBeforeItDraws() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawGround(binding, pipeline, listOf(resolvedTile()), ProjectionMode.GLOBE)
        val enabled = binding.log.indexOfFirst { it == "enable(${hex(GL_CULL_FACE)})" }
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
        assertTrue(firstDraw >= 0, "the ground must actually draw")
        assertTrue(
            enabled in 0 until firstDraw,
            "on a globe the ground must enable culling before it draws: ${binding.log}",
        )
        assertFalse(
            binding.log.any { it == "disable(${hex(GL_CULL_FACE)})" },
            "the globe must never disable culling",
        )
    }

    /**
     * The two arms must differ in **exactly** the cull enable and in nothing else. This is the call-log
     * half of "no mercator pixel moves": a globe arm that also changed the blend function, the depth
     * state, the winding or the draw count would pass both tests above and still move pixels under
     * mercator once anything threaded the mode through. Stripping one call from each log and comparing
     * the remainder is what makes that claim rather than merely implying it.
     */
    @Test fun theTwoProjectionModesDifferByExactlyOneGlCall() {
        val mercatorBinding = newBinding()
        val mercator = createdPipeline(mercatorBinding)
        mercatorBinding.log.clear()
        drawGround(mercatorBinding, mercator, listOf(resolvedTile()), ProjectionMode.MERCATOR)

        val globeBinding = newBinding()
        val globe = createdPipeline(globeBinding)
        globeBinding.log.clear()
        drawGround(globeBinding, globe, listOf(resolvedTile()), ProjectionMode.GLOBE)

        assertEquals(
            mercatorBinding.log.size,
            globeBinding.log.size,
            "the two arms must issue the same number of calls",
        )
        assertContentEquals(
            mercatorBinding.log.filter { it != "disable(${hex(GL_CULL_FACE)})" },
            globeBinding.log.filter { it != "enable(${hex(GL_CULL_FACE)})" },
            "the globe arm must change the cull enable and nothing else",
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
