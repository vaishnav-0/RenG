package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerPipelineTest {
    @Test fun theRosterAddsStickerAtWireValueTwoWithoutRenumberingComposite() {
        // The regression guard that actually matters is InternalResourceKeyTest's pinned hex vector for
        // COMPOSITE; this is a direct, readable statement of the same fact.
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
    }

    @Test fun theStickerSourcesAreAcceptedShaderProfileSources() {
        assertTrue(STICKER_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(STICKER_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(STICKER_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(STICKER_FRAGMENT_SOURCE) != null)
    }

    @Test fun creationBuildsAProgramAQuadAndTwoAttributes() {
        val binding = newBinding()
        val pipeline = createStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as StickerPipelineResult.Created
        assertTrue(pipeline.pipeline.program > 0)
        assertTrue(pipeline.pipeline.vertexArray > 0)
        assertTrue(pipeline.pipeline.vertexBuffer > 0)
        assertEquals(2, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(2, binding.log.count { it.startsWith("vertexAttribPointer") })
        assertTrue(binding.log.any { it.startsWith("bufferData(0x8892,64") })
        // The fake mirrors a real driver: an undeclared name resolves to -1. This pins that the
        // pipeline actually queries both of its documented uniform names against the compiled program,
        // not merely that it builds something.
        assertEquals(
            MODEL_VIEW_PROJECTION_LOCATION,
            pipeline.pipeline.modelViewProjectionUniformLocation,
        )
        assertEquals(TEXTURE_LOCATION, pipeline.pipeline.textureUniformLocation)
    }

    @Test fun deletionRemovesTheQuadAndTheProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipeline =
            (createStickerPipeline(binding, ShaderDialect.GLES, cache) as StickerPipelineResult.Created).pipeline
        binding.log.clear()
        deleteStickerPipeline(binding, cache, pipeline)
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertNull(cache.program(pipeline.key))
    }

    /**
     * This pass owns the map regime and nothing else. It draws depth-tested, and — the half that used
     * to be here — it neither disables depth testing nor composites a screen stack afterwards, because
     * `SceneContent.drawScreenStack` owns that now. A `disable(GL_DEPTH_TEST)` left behind here would
     * turn the test off part-way through a map regime whose stickers need not be its last content.
     */
    @Test fun thisPassDrawsTheMapRegimeDepthTestedAndTurnsNothingOffAfterwards() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val mapTexture = 11
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker(texture = mapTexture))))

        val depthEnabled = binding.log.indexOfFirst { it == "enable(0xB71)" } // GL_DEPTH_TEST
        val mapBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$mapTexture)" } // GL_TEXTURE_2D
        val mapDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }

        assertTrue(depthEnabled in 0 until mapBind, "the map regime must be depth-tested")
        assertTrue(mapBind in 0 until mapDraw, "the map-anchored sticker's texture must bind before it draws")
        assertFalse(
            binding.log.any { it == "disable(0xB71)" },
            "turning depth testing off is the screen stack's job, not this pass's: ${binding.log}",
        )
    }

    /**
     * The second copy this task deleted. `MercatorSpatialPlanner` sorts the screen stack once, and
     * [drawStickers] used to sort its own screen half a second time by a `screenCompositeZ` threaded
     * down from a draw-time re-resolution. Nothing here may reorder what it is handed, and the three
     * textures below are in no sorted order under any key a re-derived sort could reach for.
     */
    @Test fun drawStickersNeitherSortsNorSplitsWhatItIsHanded() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val first = 91
        val second = 12
        val third = 53
        binding.log.clear()
        drawStickers(
            binding,
            pipeline,
            StickerWorld(
                mapAnchored = listOf(
                    resolvedSticker(texture = first),
                    resolvedSticker(texture = second),
                    resolvedSticker(texture = third),
                ),
            ),
        )

        assertEquals(
            listOf(first, second, third),
            boundTexturesInDrawOrder(binding),
            "the map half draws in exactly the order it is given",
        )
    }

    /**
     * ADR 0027. The map regime is depth-tested and writes nothing, so a map-anchored billboard —
     * a screen-parallel quad carrying its anchor's single depth — cannot be sliced in half by the
     * varying depth of the map plane it stands on, and cannot slice a later one either.
     */
    @Test fun theMapRegimeTurnsDepthWritesOffBeforeItDraws() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker(texture = 11))))

        val maskedOff = binding.log.indexOfFirst { it == "depthMask(false)" }
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
        assertTrue(firstDraw >= 0, "the map-anchored sticker must actually draw")
        assertTrue(
            maskedOff in 0 until firstDraw,
            "the map regime must turn depth writes off before it draws (ADR 0027)",
        )
        assertFalse(binding.log.any { it == "depthMask(true)" }, "nothing here re-enables depth writes")
    }

    // `equalZIndexCompositesInStablePlanOrder` and `greaterZIndexComposesOnTopOfLesserZIndex` used to
    // sit here, asserting the z-sort this function ran over its own screen half. Both the sort and the
    // half are gone: `MercatorSpatialPlanner`'s `screenCompositingOrder` is the one authority for the
    // screen stack's order, and `MercatorSpatialPlannerTest.screenEntriesSortByZThenSourceIndex` is
    // where those two properties are pinned now. `SceneContentTest` pins that the GL layer consumes
    // that order rather than deriving one of its own.

    @Test fun theBlendModeIsPremultipliedNotStraightAlpha() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker())))

        // GL_ONE(0x1), GL_ONE_MINUS_SRC_ALPHA(0x303) for both rgb and alpha: the premultiplied function
        // Task 4's premultiplied upload requires. GL_SRC_ALPHA(0x302) would be the straight-alpha
        // function a caller might reach for instead, and nothing else in this suite pins the distinction.
        assertTrue(binding.log.any { it == "blendFuncSeparate(0x1,0x303,0x1,0x303)" })
        assertFalse(binding.log.any { it.startsWith("blendFuncSeparate(0x302,") })
    }

    @Test fun anEntirelyEmptyWorldIssuesNoGlCallsAtAll() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld())
        assertContentEquals(emptyList(), binding.log)
    }

    // RecordingGlBinding's getUniformLocation/getAttribLocation return -1 for any name the binding was
    // not told to declare (matching a real driver for a name the linked program never declares). Every
    // other test in this file uses newBinding(), which declares both of this pipeline's uniform names,
    // so drawStickers's `>= 0` guards are exercised on their true (bind) branch throughout this suite --
    // not merely left untested because the fake happened to answer every query the same way. This test
    // pins that behaviour directly: with both names declared, drawing must actually issue the uniform
    // binds, at the exact locations createStickerPipeline resolved.
    @Test fun drawingBindsBothDeclaredUniformsAtTheirResolvedLocations() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        assertEquals(MODEL_VIEW_PROJECTION_LOCATION, pipeline.modelViewProjectionUniformLocation)
        assertEquals(TEXTURE_LOCATION, pipeline.textureUniformLocation)

        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker())))

        assertTrue(
            binding.log.any { it == "uniformMatrix4fv($MODEL_VIEW_PROJECTION_LOCATION,1,false)" },
            "the model-view-projection uniform must be bound when the shader declares it",
        )
        assertTrue(
            binding.log.any { it == "uniform1i($TEXTURE_LOCATION,0)" },
            "the texture uniform must be bound to texture unit 0 when the shader declares it",
        )
    }

    // The mirror image of the test above: when the shader declares neither name (RecordingGlBinding's
    // own default), createStickerPipeline resolves both locations to -1 and drawStickers's `>= 0` guards
    // must skip the binds entirely, exactly as they would for a real compiled program missing both
    // uniforms.
    @Test fun drawingSkipsBothUniformBindsWhenNeitherIsDeclared() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createdPipeline(binding)
        assertEquals(-1, pipeline.modelViewProjectionUniformLocation)
        assertEquals(-1, pipeline.textureUniformLocation)

        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker())))

        assertFalse(binding.log.any { it.startsWith("uniformMatrix4fv") })
        assertFalse(binding.log.any { it.startsWith("uniform1i") })
    }

    private fun createdPipeline(binding: RecordingGlBinding): StickerPipeline =
        (createStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as StickerPipelineResult.Created)
            .pipeline

    private fun resolvedSticker(texture: Int = 1): ResolvedSticker =
        ResolvedSticker(modelViewProjection = FloatArray(16), texture = texture)

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
        STICKER_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
    )

    private companion object {
        const val MODEL_VIEW_PROJECTION_LOCATION: Int = 3
        const val TEXTURE_LOCATION: Int = 7
    }
}
