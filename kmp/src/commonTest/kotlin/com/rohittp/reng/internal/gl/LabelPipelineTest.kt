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
 * The label pass's call log.
 *
 * **What this suite is actually for.** Every other pipeline's suite checks that a draw happened;
 * this one checks that *one* draw happened for many quads, because that is the only property
 * distinguishing this pipeline from the sticker pipeline it was written to replace. A batch test
 * built on a single quad cannot see the difference — one quad drawn once and one quad drawn
 * per-quad are the same call log — so every batching assertion below uses at least three, and
 * asserts the draw **count** rather than the presence of a draw.
 *
 * The same trap in the other dimension: a halo assertion where the halo colour equals the text
 * colour passes with the entire halo term deleted. Both colours differ from each other in every
 * fixture here, and the readback in `runLabelReadbackSuite` samples a field value only the halo
 * band admits.
 */
class LabelPipelineTest {
    @Test fun theRosterAppendsLabelAtWireValueFiveWithoutRenumberingAnything() {
        // Wire values are part of a canonical identity (ADR 0018), so an existing pipeline's
        // ResourceKey changes if any of these move. InternalResourceKeyTest's pinned hex vector for
        // COMPOSITE is the regression guard that bites; this states the same fact readably.
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
        assertEquals(3, InternalPipelineRole.GROUND.wireValue)
        assertEquals(4, InternalPipelineRole.MODEL.wireValue)
        assertEquals(5, InternalPipelineRole.LABEL.wireValue)
    }

    @Test fun theLabelSourcesAreAcceptedShaderProfileSources() {
        assertTrue(LABEL_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(LABEL_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(LABEL_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(LABEL_FRAGMENT_SOURCE) != null)
    }

    @Test fun creationBuildsAProgramTwoBuffersAndFiveInterleavedAttributes() {
        val binding = newBinding()
        val created = createLabelPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as LabelPipelineResult.Created
        val pipeline = created.pipeline

        assertTrue(pipeline.program > 0)
        assertTrue(pipeline.vertexArray > 0)
        assertTrue(pipeline.vertexBuffer > 0)
        assertTrue(pipeline.indexBuffer > 0)
        assertTrue(
            pipeline.vertexBuffer != pipeline.indexBuffer,
            "the vertex and index buffers must be two distinct GL names",
        )
        assertEquals(5, binding.log.count { it.startsWith("enableVertexAttribArray") })
        // Five attributes at the documented offsets, all GL_FLOAT(0x1406), all at one stride. A
        // wrong offset here reads a neighbouring attribute's floats, which is invisible in any
        // assertion about sizes alone.
        assertEquals(
            listOf(
                "vertexAttribPointer(0,2,0x1406,false,60,0)",
                "vertexAttribPointer(1,2,0x1406,false,60,8)",
                "vertexAttribPointer(2,4,0x1406,false,60,16)",
                "vertexAttribPointer(3,4,0x1406,false,60,32)",
                "vertexAttribPointer(4,3,0x1406,false,60,48)",
            ),
            binding.log.filter { it.startsWith("vertexAttribPointer") },
        )
        assertEquals(ATLAS_LOCATION, pipeline.atlasUniformLocation)
        assertEquals(VIEWPORT_LOCATION, pipeline.viewportSizeUniformLocation)
    }

    /**
     * The index buffer is bound while the vertex array is, so the vertex array captures it — an
     * element-array binding is vertex-array state. Without that capture `beginLabelPass`'s single
     * `bindVertexArray` would leave whatever element buffer the previous pass bound, and the draw
     * would read another pipeline's indices.
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
        assertFalse(
            binding.log.any { it == "bindVertexArray(0)" },
            "nothing may unbind the vertex array before the capture: ${binding.log}",
        )
    }

    /**
     * **The batching assertion.** Three quads, one `glDrawElements`, and the element count scales
     * with the quad count rather than the draw count doing so.
     *
     * Three rather than one deliberately: with a single quad, "batched" and "one draw per quad"
     * produce identical logs, so a one-quad fixture would pass against the very design this
     * pipeline exists to replace.
     */
    @Test fun threeQuadsAreOneDrawCallCarryingAllEighteenIndices() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        val draws = binding.log.filter { it.startsWith("drawElements") }
        assertEquals(1, draws.size, "three glyph quads must be one draw call, not three: ${binding.log}")
        // GL_TRIANGLES(0x4), 3 quads * 6 indices, GL_UNSIGNED_INT(0x1405), from offset 0.
        assertEquals("drawElements(0x4,18,0x1405,0)", draws.single())
        assertEquals(
            0,
            binding.log.count { it.startsWith("drawArrays") },
            "the batch draws indexed, never as separate arrays",
        )
    }

    /**
     * The sharper form of the same property: growing the batch changes the *count argument* of one
     * draw and nothing else. A pipeline that drew per quad would move the left column instead.
     */
    @Test fun theDrawCountStaysAtOneWhileTheGlyphCountGrows() {
        listOf(1, 3, 7, 40).forEach { glyphs ->
            val binding = newBinding()
            val pipeline = createdPipeline(binding)
            binding.log.clear()
            drawLabels(binding, pipeline, world(batch(quads = List(glyphs) { quad() })))

            val draws = binding.log.filter { it.startsWith("drawElements") }
            assertEquals(1, draws.size, "$glyphs glyph quads must still be one draw: ${binding.log}")
            assertEquals("drawElements(0x4,${glyphs * 6},0x1405,0)", draws.single())
            assertEquals(
                1,
                binding.log.count { it.startsWith("bufferSubData") },
                "$glyphs glyph quads must be one vertex upload",
            )
        }
    }

    /**
     * A batch is per **texture**, because a texture bind is the one thing a single draw cannot vary.
     * Two atlases are two draws and two binds, in the order given — and each bind must precede its
     * own draw, which is the part a count alone would not catch.
     */
    @Test fun twoAtlasesAreTwoDrawsEachPrecededByItsOwnTextureBind() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(
            binding,
            pipeline,
            world(
                batch(atlasTexture = 41, quads = threeDistinctQuads()),
                batch(atlasTexture = 17, quads = threeDistinctQuads()),
            ),
        )

        val interleaved = binding.log.filter { it.startsWith("bindTexture") || it.startsWith("drawElements") }
        assertEquals(
            listOf(
                "bindTexture(0xDE1,41)",
                "drawElements(0x4,18,0x1405,0)",
                "bindTexture(0xDE1,17)",
                "drawElements(0x4,18,0x1405,0)",
            ),
            interleaved,
            "each batch binds its own atlas and then draws it, in the order given",
        )
    }

    /**
     * Colour is per **candidate**, not per layer and not per draw: Rentile moved `color` and
     * `haloColor` onto the candidate at `0.6.0` because road, water and POI layers pick them from
     * feature data. A batch cannot express a per-object uniform at all, so the only way this can be
     * true is for all three quads' colours to be present in the one uploaded vertex array.
     *
     * All three text colours differ from each other **and** from all three halo colours, so a
     * pipeline that wrote one quad's colour for every quad, or that wrote the text colour into the
     * halo slot, fails rather than passing on a coincidence.
     */
    @Test fun everyQuadsOwnTextAndHaloColoursReachTheOneUploadedVertexArray() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        val vertices = uploadedVertices(binding)
        assertEquals(3 * 4 * LABEL_VERTEX_FLOATS, vertices.size, "three quads, four corners each")
        // Opaque colours with opacity 1, so premultiplying is the identity and these are the exact
        // numbers the fixture declared.
        assertEquals(listOf(1.0f, 0.0f, 0.0f, 1.0f), vertexSlice(vertices, corner = 0, at = 4))
        assertEquals(listOf(0.0f, 0.0f, 1.0f, 1.0f), vertexSlice(vertices, corner = 0, at = 8))
        assertEquals(listOf(0.0f, 1.0f, 0.0f, 1.0f), vertexSlice(vertices, corner = 4, at = 4))
        assertEquals(listOf(1.0f, 1.0f, 0.0f, 1.0f), vertexSlice(vertices, corner = 4, at = 8))
        assertEquals(listOf(0.0f, 1.0f, 1.0f, 1.0f), vertexSlice(vertices, corner = 8, at = 4))
        assertEquals(listOf(1.0f, 0.0f, 1.0f, 1.0f), vertexSlice(vertices, corner = 8, at = 8))
    }

    /**
     * Every corner of a quad carries that quad's own paint, not just its first: the fragment shader
     * reads interpolated attributes, so a paint written to one corner and left zero at the other
     * three produces a glyph that fades to nothing across itself.
     */
    @Test fun allFourCornersOfAQuadCarryThatQuadsPaint() {
        val vertices = assembleLabelVertices(listOf(quad(textColour = RED, haloColour = BLUE)))
        for (corner in 0 until 4) {
            assertEquals(listOf(1.0f, 0.0f, 0.0f, 1.0f), vertexSlice(vertices, corner, at = 4), "corner $corner fill")
            assertEquals(listOf(0.0f, 0.0f, 1.0f, 1.0f), vertexSlice(vertices, corner, at = 8), "corner $corner halo")
        }
    }

    /**
     * Screen pixels travel through unchanged, in the documented corner order. Every one of the eight
     * numbers below is distinct, so a transposed x/y, a reversed winding, or a corner order rotated
     * by one all fail — none of which a symmetric fixture such as a square centred on the origin
     * could see.
     */
    @Test fun theCornersReachTheVertexArrayAsScreenPixelsInTheDocumentedOrder() {
        val corners = floatArrayOf(11.0f, 23.0f, 47.0f, 29.0f, 53.0f, 71.0f, 13.0f, 67.0f)
        val uvs = floatArrayOf(0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 0.9375f)
        val vertices = assembleLabelVertices(listOf(quad(cornersXy = corners, cornersUv = uvs)))

        for (corner in 0 until 4) {
            assertEquals(
                listOf(corners[corner * 2], corners[corner * 2 + 1]),
                vertexSlice(vertices, corner, at = 0, width = 2),
                "corner $corner position",
            )
            assertEquals(
                listOf(uvs[corner * 2], uvs[corner * 2 + 1]),
                vertexSlice(vertices, corner, at = 2, width = 2),
                "corner $corner atlas coordinate",
            )
        }
    }

    /**
     * The halo iso-line sits `haloWidth` screen pixels outside the glyph outline, converted into
     * field units through the glyph's own scale. Both arguments are unequal to each other and to 1,
     * so a formula that forgot the division, inverted it, or dropped the scale entirely all give
     * different answers.
     */
    @Test fun theHaloEdgeSitsOutsideTheFillEdgeByTheHaloWidthInFieldUnits() {
        // 2 screen pixels at scale 4 is half an em pixel, which is 1/16 of the field's 8-pixel range.
        assertEquals(0.75f - 0.0625f, labelHaloEdgeDistance(scale = 4.0f, haloWidthPixels = 2.0f))
        // The same halo width on a smaller glyph reaches further through the field, not the same
        // distance: this is the pairing that fails when the scale division is dropped.
        assertEquals(0.75f - 0.125f, labelHaloEdgeDistance(scale = 2.0f, haloWidthPixels = 2.0f))
        assertTrue(
            labelHaloEdgeDistance(scale = 2.0f, haloWidthPixels = 2.0f) <
                labelHaloEdgeDistance(scale = 4.0f, haloWidthPixels = 2.0f),
            "a halo of fixed pixel width covers more of the field on a smaller glyph",
        )
    }

    /**
     * A width of zero puts the halo band exactly on the fill band, which is what makes an un-haloed
     * label need no second program and no branch: its halo composites underneath an identical fill
     * and contributes nothing.
     */
    @Test fun aZeroHaloWidthPutsTheHaloEdgeExactlyOnTheFillEdge() {
        assertEquals(LABEL_FILL_EDGE_DISTANCE, labelHaloEdgeDistance(scale = 3.0f, haloWidthPixels = 0.0f))
    }

    /**
     * Past the packed cell's three buffered pixels the field simply stops, so the iso-line clamps
     * rather than passing every texel in the cell and turning the halo into the cell's rectangle.
     */
    @Test fun theHaloEdgeClampsWhereThePackedCellRunsOutOfField() {
        assertEquals(0.375f, LABEL_HALO_EDGE_FLOOR)
        assertEquals(LABEL_HALO_EDGE_FLOOR, labelHaloEdgeDistance(scale = 1.0f, haloWidthPixels = 3.0f))
        assertEquals(
            LABEL_HALO_EDGE_FLOOR,
            labelHaloEdgeDistance(scale = 1.0f, haloWidthPixels = 30.0f),
            "an over-wide halo stops growing rather than becoming a box",
        )
    }

    /**
     * `text-halo-blur` widens the halo's ramp without moving its edge, and a bigger glyph gets a
     * narrower field-space ramp for the same screen-space softness. Asserted as an ordering plus one
     * exact value, because the ramp's exact width is a rendering choice while its monotonicity in
     * both arguments is the contract.
     */
    @Test fun blurWidensTheHaloRampAndScaleNarrowsIt() {
        val sharp = labelEdgeGamma(scale = 2.0f, blurPixels = 0.0f)
        val blurred = labelEdgeGamma(scale = 2.0f, blurPixels = 4.0f)
        val bigger = labelEdgeGamma(scale = 8.0f, blurPixels = 4.0f)

        assertEquals(LABEL_EDGE_GAMMA / 2.0f, sharp)
        assertTrue(blurred > sharp, "blur must widen the ramp: $blurred vs $sharp")
        assertTrue(bigger < blurred, "the same blur on a larger glyph is a narrower field ramp")
        assertTrue(
            labelEdgeGamma(scale = 1.0e6f, blurPixels = 0.0f) > 0.0f,
            "the ramp never collapses to zero, which would make smoothstep's two edges equal",
        )
    }

    /**
     * Opacity multiplies both alphas and the premultiplication follows it.
     *
     * The fixture is asymmetric on purpose: the three colour channels differ from each other, the
     * declared alpha is not 1, and the opacity is not 1 — so `rgb * a * opacity` is a different
     * number from `rgb`, from `rgb * a`, from `rgb * opacity`, and from leaving the colour straight.
     * A fixture at opacity 1 with an opaque colour passes with premultiplication deleted outright.
     */
    @Test fun opacityFoldsIntoBothAlphasAndBothColoursArePremultiplied() {
        val vertices = assembleLabelVertices(
            listOf(
                quad(
                    textColour = floatArrayOf(0.8f, 0.5f, 0.2f, 0.5f),
                    haloColour = floatArrayOf(0.4f, 0.6f, 1.0f, 0.25f),
                    opacity = 0.5f,
                ),
            ),
        )

        // text: alpha 0.5 * 0.5 = 0.25, so rgb * 0.25.
        assertEquals(listOf(0.2f, 0.125f, 0.05f, 0.25f), vertexSlice(vertices, corner = 0, at = 4))
        // halo: alpha 0.25 * 0.5 = 0.125, so rgb * 0.125.
        assertEquals(listOf(0.05f, 0.075f, 0.125f, 0.125f), vertexSlice(vertices, corner = 0, at = 8))
    }

    /**
     * Two triangles per quad against four corners, sharing the top-left-to-bottom-right diagonal.
     * Three quads rather than one, so an index generator that forgot to advance its base vertex per
     * quad — which draws the first glyph three times over — fails here.
     */
    @Test fun theIndicesAreTwoTrianglesPerQuadAgainstFourAdvancingCorners() {
        assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3), labelQuadIndices(1))
        assertContentEquals(
            intArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 8, 9, 10, 8, 10, 11),
            labelQuadIndices(3),
        )
    }

    @Test fun theIndexPayloadIsUploadedAsLittleEndianThirtyTwoBitWords() {
        assertContentEquals(
            byteArrayOf(1, 0, 0, 0, 0x2A, 0x01, 0, 0),
            littleEndianIntBytes(intArrayOf(1, 298)),
        )

        val binding = newBinding()
        createdPipeline(binding)
        val uploaded = binding.bufferDataPayloads[GL_ELEMENT_ARRAY_BUFFER]
        assertContentEquals(littleEndianIntBytes(labelQuadIndices(LABEL_INITIAL_CAPACITY_QUADS)), uploaded)
    }

    /**
     * The vertex buffer is allocated once with a **null** payload under `GL_DYNAMIC_DRAW` and
     * refilled through `glBufferSubData` — F-2's joint-palette shape, not `uploadModelPrimitive`'s
     * `GL_STATIC_DRAW`-and-cache-by-key shape, because a batch's bytes are different every frame and
     * never repeat. Re-issuing the null `glBufferData` before each fill is orphaning.
     */
    @Test fun theVertexBufferIsOrphanedWithANullPayloadAndRefilledThroughBufferSubData() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        val capacityBytes = LABEL_INITIAL_CAPACITY_QUADS * 4 * LABEL_VERTEX_STRIDE_BYTES
        // GL_ARRAY_BUFFER(0x8892), the whole capacity, GL_DYNAMIC_DRAW(0x88E8).
        assertTrue(
            binding.log.any { it == "bufferData(0x8892,$capacityBytes,0x88E8)" },
            "the batch must orphan its whole allocation before filling it: ${binding.log}",
        )
        assertNull(
            binding.bufferDataPayloads[GL_ARRAY_BUFFER],
            "the orphaning call must carry no payload at all",
        )
        assertFalse(
            binding.log.any { it.startsWith("bufferData(0x8892,") && it.endsWith("0x88E4)") },
            "a batch never uses GL_STATIC_DRAW: ${binding.log}",
        )
        assertEquals(
            "bufferSubData(0x8892,0,${3 * 4 * LABEL_VERTEX_STRIDE_BYTES})",
            binding.log.single { it.startsWith("bufferSubData") },
            "only the quads actually drawn are uploaded, at offset zero",
        )
    }

    /**
     * The buffers grow to a high-water mark and never shrink. Growth is what makes an unbounded
     * candidate count expressible at all — E5 ships no public ceiling and task 17 measures before one
     * is proposed — and never shrinking is what keeps the steady state free of GL allocation.
     */
    @Test fun theBuffersGrowPastTheirInitialCapacityAndNeverShrinkAgain() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        assertEquals(LABEL_INITIAL_CAPACITY_QUADS, pipeline.capacityQuads)

        val overflowing = LABEL_INITIAL_CAPACITY_QUADS + 1
        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = List(overflowing) { quad() })))
        assertEquals(LABEL_INITIAL_CAPACITY_QUADS * 2, pipeline.capacityQuads, "capacity doubles rather than fits")
        assertContentEquals(
            littleEndianIntBytes(labelQuadIndices(LABEL_INITIAL_CAPACITY_QUADS * 2)),
            binding.bufferDataPayloads[GL_ELEMENT_ARRAY_BUFFER],
            "the indices are rewritten to cover the new capacity",
        )
        assertEquals(1, binding.log.count { it.startsWith("drawElements") })

        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = listOf(quad()))))
        assertEquals(LABEL_INITIAL_CAPACITY_QUADS * 2, pipeline.capacityQuads, "a smaller frame never shrinks it")
        assertEquals(
            0,
            binding.log.count { it.startsWith("bufferData(0x8893") }, // GL_ELEMENT_ARRAY_BUFFER
            "a frame that fits rewrites no indices: ${binding.log}",
        )
    }

    /**
     * Labels are screen-space primitives whose pixel was decided on the CPU, so the pass turns depth
     * testing off. Disabling the test also disables depth *writes*, which is why — unlike the map
     * regime, where the test stays on and the mask is what gets turned off (ADR 0027) — there is no
     * `depthMask` call to make here.
     *
     * Face culling is the one this pass has to say out loud: a glyph quad's winding follows the
     * screen-space corner order under a y-down space, which is the back face by default, and the
     * model pass leaves culling enabled behind a single-sided primitive.
     */
    @Test fun theLabelPassTurnsDepthTestingAndFaceCullingOffBeforeItDraws() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawElements") }
        val depthOff = binding.log.indexOfFirst { it == "disable(0xB71)" } // GL_DEPTH_TEST
        val cullOff = binding.log.indexOfFirst { it == "disable(0xB44)" } // GL_CULL_FACE
        assertTrue(firstDraw >= 0, "the batch must actually draw")
        assertTrue(depthOff in 0 until firstDraw, "depth testing must be off before any glyph draws")
        assertTrue(cullOff in 0 until firstDraw, "face culling must be off before any glyph draws")
        assertFalse(binding.log.any { it == "enable(0xB71)" }, "nothing here turns depth testing back on")
    }

    @Test fun theBlendModeIsPremultipliedNotStraightAlpha() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        // GL_ONE(0x1), GL_ONE_MINUS_SRC_ALPHA(0x303) on both channels, matching the premultiplied
        // colour the fragment shader emits. GL_SRC_ALPHA(0x302) is the straight-alpha function a
        // caller might reach for instead, and it would double-multiply everything this pass draws.
        assertTrue(binding.log.any { it == "blendFuncSeparate(0x1,0x303,0x1,0x303)" })
        assertFalse(binding.log.any { it.startsWith("blendFuncSeparate(0x302,") })
        assertTrue(binding.log.any { it == "enable(0xBE2)" }) // GL_BLEND
    }

    /**
     * The one per-frame uniform. The fixture's viewport is deliberately **not** square: a square one
     * cannot tell the width apart from the height, so a transposed uniform upload would pass it.
     */
    @Test fun theViewportUniformCarriesTheFramesOwnWidthAndHeightInThatOrder() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(
            binding,
            pipeline,
            LabelWorld(
                outputPixelSize = OutputPixelSize(width = 960, height = 540),
                batches = listOf(batch(quads = threeDistinctQuads())),
            ),
        )

        assertTrue(
            binding.log.any { it == "uniform2f($VIEWPORT_LOCATION,960.0,540.0)" },
            "the viewport uniform must carry width then height: ${binding.log}",
        )
        assertTrue(binding.log.any { it == "uniform1i($ATLAS_LOCATION,0)" })
        assertEquals(
            1,
            binding.log.count { it.startsWith("uniform2f") },
            "the viewport is uploaded once per frame, not once per batch or once per quad",
        )
    }

    /**
     * The mirror of the test above: when the program declares neither name, both locations resolve to
     * `-1` and both uniform binds are skipped, exactly as they would for a real linked program that
     * optimised the uniforms away. Without this, every `>= 0` guard in the pass is untested.
     */
    @Test fun drawingSkipsBothUniformBindsWhenTheProgramDeclaresNeither() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createdPipeline(binding)
        assertEquals(-1, pipeline.atlasUniformLocation)
        assertEquals(-1, pipeline.viewportSizeUniformLocation)

        binding.log.clear()
        drawLabels(binding, pipeline, world(batch(quads = threeDistinctQuads())))

        assertFalse(binding.log.any { it.startsWith("uniform1i") })
        assertFalse(binding.log.any { it.startsWith("uniform2f") })
        assertEquals(1, binding.log.count { it.startsWith("drawElements") }, "and it still draws")
    }

    @Test fun anEmptyWorldIssuesNoGlCallsAtAll() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(binding, pipeline, LabelWorld(outputPixelSize = OutputPixelSize(64, 48)))
        assertContentEquals(emptyList(), binding.log)
    }

    /**
     * A batch that survived collision with nothing left in it is not a draw of zero glyphs — it is
     * no draw, no upload and no state change. `glDrawElements` with a count of zero is legal and
     * would hide the empty case behind a call that looks like work.
     */
    @Test fun batchesWithNoSurvivingQuadsIssueNoGlCallsEither() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(
            binding,
            pipeline,
            world(batch(atlasTexture = 3, quads = emptyList()), batch(atlasTexture = 9, quads = emptyList())),
        )
        assertContentEquals(emptyList(), binding.log)
    }

    /**
     * An empty batch beside a populated one is skipped rather than drawn, and does not consume the
     * populated one's texture bind.
     */
    @Test fun anEmptyBatchBesideAPopulatedOneIsSkippedEntirely() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawLabels(
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

    @Test fun deletionRemovesBothBuffersTheVertexArrayAndTheProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipeline = (
            createLabelPipeline(binding, ShaderDialect.GLES, cache) as LabelPipelineResult.Created
            ).pipeline
        binding.log.clear()
        deleteLabelPipeline(binding, cache, pipeline)

        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(2, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(
            listOf(pipeline.vertexBuffer, pipeline.indexBuffer),
            binding.deletedNames.filter { it == pipeline.vertexBuffer || it == pipeline.indexBuffer },
            "both the vertex and the index buffer are deleted, not one of them twice",
        )
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertNull(cache.program(pipeline.key))
    }

    private fun createdPipeline(binding: RecordingGlBinding): LabelPipeline =
        (createLabelPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as LabelPipelineResult.Created)
            .pipeline

    private fun world(vararg batches: LabelBatch): LabelWorld =
        LabelWorld(outputPixelSize = OutputPixelSize(width = 128, height = 96), batches = batches.toList())

    private fun batch(atlasTexture: Int = 7, quads: List<ResolvedGlyphQuad>): LabelBatch =
        LabelBatch(atlasTexture = atlasTexture, quads = quads)

    /** Three quads, no two of which share a text colour, a halo colour or a position. */
    private fun threeDistinctQuads(): List<ResolvedGlyphQuad> = listOf(
        quad(originX = 0.0f, textColour = RED, haloColour = BLUE),
        quad(originX = 20.0f, textColour = GREEN, haloColour = YELLOW),
        quad(originX = 40.0f, textColour = CYAN, haloColour = MAGENTA),
    )

    @Suppress("LongParameterList")
    private fun quad(
        originX: Float = 0.0f,
        cornersXy: FloatArray = floatArrayOf(
            originX, 0.0f, originX + 10.0f, 0.0f, originX + 10.0f, 12.0f, originX, 12.0f,
        ),
        cornersUv: FloatArray = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f),
        textColour: FloatArray = RED,
        haloColour: FloatArray = BLUE,
        opacity: Float = 1.0f,
        haloWidthPixels: Float = 1.0f,
    ): ResolvedGlyphQuad = ResolvedGlyphQuad(
        cornersXy = cornersXy,
        cornersUv = cornersUv,
        paint = ResolvedLabelPaint(
            textColour = textColour,
            haloColour = haloColour,
            opacity = opacity,
            haloWidthPixels = haloWidthPixels,
            scale = 2.0f,
        ),
    )

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        LABEL_ATLAS_UNIFORM_NAME to ATLAS_LOCATION,
        LABEL_VIEWPORT_SIZE_UNIFORM_NAME to VIEWPORT_LOCATION,
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
        val base = corner * LABEL_VERTEX_FLOATS + at
        return (0 until width).map { vertices[base + it] }
    }

    private companion object {
        const val ATLAS_LOCATION: Int = 4
        const val VIEWPORT_LOCATION: Int = 9

        val RED: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f)
        val GREEN: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)
        val BLUE: FloatArray = floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
        val CYAN: FloatArray = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f)
        val MAGENTA: FloatArray = floatArrayOf(1.0f, 0.0f, 1.0f, 1.0f)
        val YELLOW: FloatArray = floatArrayOf(1.0f, 1.0f, 0.0f, 1.0f)
    }
}
