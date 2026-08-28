package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The icon pass's call log.
 *
 * **The one property this suite exists for is that the icon program is not the label program.** Two
 * quad pipelines with the same geometry, the same batching and the same blend function look
 * identical in every assertion about draws and buffers; what tells them apart is the arithmetic, and
 * the arithmetic is in the shader source and in the vertex layout. So the load-bearing cases below
 * are the ones that assert the *absence* of the distance-field constants and the presence of the
 * tint selector — a pipeline that had quietly reused `LABEL_FRAGMENT_SOURCE` would pass every other
 * test in this file.
 *
 * **The tint trap.** A tint assertion run only against an `sdf` fixture cannot tell a flag that is
 * read from one that is hardcoded to `true`; run only against artwork it cannot tell one hardcoded
 * to `false`. Both directions are asserted, in one batch, because a batch is per atlas and one atlas
 * carries both kinds.
 */
class IconPipelineTest {
    @Test fun theRosterAppendsIconAtWireValueSixWithoutRenumberingAnything() {
        // Wire values are part of a canonical identity (ADR 0018), so an existing pipeline's
        // ResourceKey changes if any of these move.
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
        assertEquals(3, InternalPipelineRole.GROUND.wireValue)
        assertEquals(4, InternalPipelineRole.MODEL.wireValue)
        assertEquals(5, InternalPipelineRole.LABEL.wireValue)
        assertEquals(6, InternalPipelineRole.ICON.wireValue)
    }

    @Test fun theIconSourcesAreAcceptedShaderProfileSources() {
        assertTrue(ICON_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(ICON_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(ICON_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(ICON_FRAGMENT_SOURCE) != null)
    }

    /**
     * **The whole reason this pipeline exists, stated as the cheapest thing that fails.**
     *
     * A sprite's alpha is coverage, so the fragment shader must consume it linearly. The label
     * program's answer is a `smoothstep` about `0.75`, and running an icon through it would draw an
     * artwork sprite as a hard-thresholded silhouette of itself — a wrong picture that still renders,
     * which is exactly the class of defect a call log cannot see and a reader stops noticing.
     *
     * Asserting the absence of a token is unusual and deliberate: what has to stay true is that the
     * icon fragment shader never acquires an iso-value, and no positive assertion about what it
     * *does* contain rules that out.
     */
    @Test fun theIconFragmentShaderSamplesCoverageAndThresholdsNothing() {
        assertFalse(
            ICON_FRAGMENT_SOURCE.contains("smoothstep"),
            "a sprite has no distance field to smoothstep across: $ICON_FRAGMENT_SOURCE",
        )
        assertFalse(
            ICON_FRAGMENT_SOURCE.contains("0.75"),
            "0.75 is where the glyph generator puts an outline, and means nothing in a sprite",
        )
        assertFalse(
            ICON_FRAGMENT_SOURCE.contains("rengLabel"),
            "the icon program must not have become the label program under another name",
        )
        assertTrue(
            ICON_FRAGMENT_SOURCE.contains("texture(rengIconAtlas, rengIconUv)"),
            "the sampled texel is the whole input",
        )
        // And the label program still thresholds, so the two really are different arithmetic rather
        // than both having been flattened into the same thing.
        assertTrue(LABEL_FRAGMENT_SOURCE.contains("smoothstep"))
    }

    @Test fun creationBuildsAProgramTwoBuffersAndFourInterleavedAttributes() {
        val binding = newBinding()
        val created = createIconPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as IconPipelineResult.Created
        val pipeline = created.pipeline

        assertTrue(pipeline.program > 0)
        assertTrue(pipeline.vertexArray > 0)
        assertTrue(pipeline.vertexBuffer > 0)
        assertTrue(pipeline.indexBuffer > 0)
        assertTrue(
            pipeline.vertexBuffer != pipeline.indexBuffer,
            "the vertex and index buffers must be two distinct GL names",
        )
        assertEquals(4, binding.log.count { it.startsWith("enableVertexAttribArray") })
        // Four attributes at the documented offsets, all GL_FLOAT(0x1406), all at one stride of 36.
        // A wrong offset reads a neighbouring attribute's floats, which no assertion about sizes sees.
        assertEquals(
            listOf(
                "vertexAttribPointer(0,2,0x1406,false,36,0)",
                "vertexAttribPointer(1,2,0x1406,false,36,8)",
                "vertexAttribPointer(2,4,0x1406,false,36,16)",
                "vertexAttribPointer(3,1,0x1406,false,36,32)",
            ),
            binding.log.filter { it.startsWith("vertexAttribPointer") },
        )
        assertEquals(ATLAS_LOCATION, pipeline.atlasUniformLocation)
        assertEquals(VIEWPORT_LOCATION, pipeline.viewportSizeUniformLocation)
    }

    /**
     * The element-array binding is vertex-array state, so it has to be made while the vertex array is
     * bound — otherwise [beginIconPass]'s single `bindVertexArray` leaves whatever element buffer the
     * previous pass bound and the draw reads another pipeline's indices.
     */
    @Test fun theIndexBufferIsBoundWhileTheVertexArrayIsSoTheVertexArrayCapturesIt() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)

        val vertexArrayBound = binding.log.indexOfFirst { it == "bindVertexArray(${pipeline.vertexArray})" }
        val elementBound = binding.log.indexOfFirst {
            it == "bindBuffer(0x8893,${pipeline.indexBuffer})" // GL_ELEMENT_ARRAY_BUFFER
        }
        assertTrue(vertexArrayBound >= 0, "creation must bind the vertex array")
        assertTrue(
            elementBound > vertexArrayBound,
            "the element array buffer must be bound after the vertex array, so the array captures it",
        )
    }

    /**
     * **The batching assertion.** Three quads, one `glDrawElements`, and the element count scales with
     * the quad count rather than the draw count doing so. Three rather than one, because with a single
     * quad "batched" and "one draw per quad" produce identical logs.
     */
    @Test fun threeIconsAreOneDrawCallCarryingAllEighteenIndices() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawIcons(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        assertEquals(1, binding.log.count { it.startsWith("drawElements") })
        assertTrue(
            binding.log.any { it == "drawElements(0x4,18,0x1405,0)" },
            "three quads are eighteen indices in one draw: ${binding.log}",
        )
    }

    /**
     * **Both directions of the tint rule, in one batch.**
     *
     * `icon-color` applies to an `sdf` sprite and to nothing else: Rentile tints under `SRC_IN` when
     * the manifest entry says so and passes no colour filter otherwise. The selector is a vertex
     * attribute rather than a uniform precisely so one atlas can carry both, so the discriminating
     * fixture is one batch carrying one of each — a per-draw uniform could not express it, and a
     * fixture with only one kind cannot tell a read flag from a hardcoded one.
     */
    @Test fun theSdfFlagReachesEveryCornerOfItsOwnQuadAndOnlyItsOwn() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawIcons(
            binding,
            pipeline,
            world(
                batch(
                    quads = listOf(
                        quad(originX = 0.0f, colour = RED, tintable = true),
                        quad(originX = 20.0f, colour = GREEN, tintable = false),
                    ),
                ),
            ),
        )

        val vertices = uploadedVertices(binding)
        val selectors = (0 until 8).map { corner -> vertices[corner * ICON_VERTEX_FLOATS + 8] }
        assertEquals(
            listOf(1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            selectors,
            "the sdf quad's four corners select the tint and the artwork quad's four do not",
        )
        // And the tint colour still travels for both, because it is the alpha slot that also carries
        // `icon-opacity`: an artwork quad with no colour at all would lose its opacity with it.
        assertContentEquals(listOf(1.0f, 0.0f, 0.0f, 1.0f), vertexSlice(vertices, corner = 0, at = 4))
        assertContentEquals(listOf(0.0f, 1.0f, 0.0f, 1.0f), vertexSlice(vertices, corner = 4, at = 4))
    }

    /**
     * `icon-opacity` is folded into the tint's **alpha** rather than premultiplied into its RGB, and
     * that is the one place this assembly deliberately differs from [assembleLabelVertices].
     *
     * The fragment shader reads that alpha as a scalar and attenuates the *sampled artwork* with it in
     * the untinted case, where no tint colour is involved at all. Premultiplying here would fold the
     * opacity into three channels the artwork path never reads, so every non-`sdf` sprite would ignore
     * `icon-opacity` — and would ignore the label fade with it, since the fade multiplies the same
     * field.
     */
    @Test fun opacityRidesTheTintsAlphaAndLeavesItsRgbAlone() {
        val vertices = assembleIconVertices(
            listOf(quad(colour = floatArrayOf(0.5f, 0.25f, 1.0f, 0.8f), opacity = 0.5f, tintable = true)),
        )

        assertContentEquals(
            listOf(0.5f, 0.25f, 1.0f, 0.4f),
            vertexSlice(vertices, corner = 0, at = 4),
            "the rgb is untouched and the alpha is colour alpha times opacity",
        )
    }

    /** The fade is one number per symbol, applied to the icon half without rebuilding its geometry. */
    @Test fun fadingReusesBothCornerArraysAndOnlyScalesTheOpacity() {
        val original = quad(colour = RED, opacity = 0.8f, tintable = true)
        val faded = original.fadedBy(0.5f)

        assertEquals(0.4f, faded.paint.opacity)
        assertTrue(faded.cornersXy === original.cornersXy, "a fade moves no corner")
        assertTrue(faded.cornersUv === original.cornersUv, "a fade moves no atlas coordinate")
        assertTrue(faded.paint.tintable, "a fade does not change what a sprite is")
        assertTrue(original.fadedBy(1.0f) === original, "a saturated fade is the identity")
    }

    /** An empty batch beside a populated one is skipped rather than drawn. */
    @Test fun anEmptyBatchBesideAPopulatedOneIsSkippedEntirely() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawIcons(
            binding,
            pipeline,
            world(
                batch(atlasTexture = 5, quads = emptyList()),
                batch(atlasTexture = 8, quads = threeDistinctQuads()),
            ),
        )

        assertEquals(1, binding.log.count { it.startsWith("drawElements") })
        assertEquals(listOf("bindTexture(0xDE1,8)"), binding.log.filter { it.startsWith("bindTexture") })
    }

    /**
     * The pass establishes its own depth and culling state rather than inheriting phase 4's.
     *
     * It runs immediately after the map regime's last sticker draw, which leaves the depth test on;
     * and a y-down screen-space corner order is clockwise in clip space, so inherited back-face
     * culling would delete every icon rather than some.
     */
    @Test fun theIconPassTurnsOffDepthAndCullingAndEstablishesPremultipliedBlending() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawIcons(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        assertTrue(binding.log.contains("disable(0xB71)"), "depth test off: ${binding.log}")
        assertTrue(binding.log.contains("disable(0xB44)"), "face culling off: ${binding.log}")
        assertTrue(binding.log.contains("enable(0xBE2)"), "blending on: ${binding.log}")
        assertTrue(
            binding.log.any { it.startsWith("blendFuncSeparate(0x1,0x303,0x1,0x303)") },
            "premultiplied source-over: ${binding.log}",
        )
        assertFalse(
            binding.log.any { it.startsWith("depthMask") },
            "a pass with the test disabled writes no depth, so it needs no mask call",
        )
    }

    @Test fun deletionRemovesBothBuffersTheVertexArrayAndTheProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipeline = (
            createIconPipeline(binding, ShaderDialect.GLES, cache) as IconPipelineResult.Created
            ).pipeline
        binding.log.clear()
        deleteIconPipeline(binding, cache, pipeline)

        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(2, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertNull(cache.program(pipeline.key))
    }

    private fun createdPipeline(binding: RecordingGlBinding): IconPipeline =
        (createIconPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as IconPipelineResult.Created)
            .pipeline

    private fun world(vararg batches: IconBatch): IconWorld =
        IconWorld(outputPixelSize = OutputPixelSize(width = 128, height = 96), batches = batches.toList())

    private fun batch(atlasTexture: Int = 7, quads: List<ResolvedIconQuad>): IconBatch =
        IconBatch(atlasTexture = atlasTexture, quads = quads)

    private fun threeDistinctQuads(): List<ResolvedIconQuad> = listOf(
        quad(originX = 0.0f, colour = RED, tintable = true),
        quad(originX = 20.0f, colour = GREEN, tintable = false),
        quad(originX = 40.0f, colour = BLUE, tintable = true),
    )

    private fun quad(
        originX: Float = 0.0f,
        colour: FloatArray = RED,
        opacity: Float = 1.0f,
        tintable: Boolean = false,
    ): ResolvedIconQuad = ResolvedIconQuad(
        cornersXy = floatArrayOf(
            originX, 0.0f, originX + 10.0f, 0.0f, originX + 10.0f, 12.0f, originX, 12.0f,
        ),
        cornersUv = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f),
        paint = ResolvedIconPaint(
            colour = colour,
            haloColour = BLUE,
            opacity = opacity,
            haloWidthPixels = 0.0f,
            haloBlurPixels = 0.0f,
            tintable = tintable,
        ),
    )

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        ICON_ATLAS_UNIFORM_NAME to ATLAS_LOCATION,
        ICON_VIEWPORT_SIZE_UNIFORM_NAME to VIEWPORT_LOCATION,
    )

    /** The floats the pass actually handed `glBufferSubData`, decoded back out of the fake. */
    private fun uploadedVertices(binding: RecordingGlBinding): FloatArray {
        val bytes = binding.bufferSubDataPayloads.getValue(GL_ARRAY_BUFFER)
        return FloatArray(bytes.size / 4) { index ->
            val at = index * 4
            Float.fromBits(
                (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24),
            )
        }
    }

    private fun vertexSlice(vertices: FloatArray, corner: Int, at: Int, width: Int = 4): List<Float> {
        val base = corner * ICON_VERTEX_FLOATS + at
        return (0 until width).map { vertices[base + it] }
    }

    private companion object {
        const val ATLAS_LOCATION: Int = 4
        const val VIEWPORT_LOCATION: Int = 9

        val RED: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f)
        val GREEN: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)
        val BLUE: FloatArray = floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
    }
}
