package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.model.DecodedPrimitive
import com.rohittp.reng.internal.model.DecodedSkin
import com.rohittp.reng.internal.model.ModelIndices
import com.rohittp.reng.internal.model.ResolvedMaterial
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RenG's own model pipeline: eight compiled variants, one uploaded primitive per decoded one, and a
 * two-phase draw.
 *
 * **Every name a test expects bound is declared on the fake.** [RecordingGlBinding.getUniformLocation]
 * returns `-1` for a name the fake was never told about, and every `uniform*` call in this file is
 * guarded on a non-negative location (ADR 0008), so a test that forgot to declare a name would see
 * zero calls and pass while asserting nothing at all. [newBinding] declares the complete roster.
 */
class ModelPipelineTest {
    @Test fun theRosterAddsModelAtWireValueFourWithoutRenumberingTheOthers() {
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
        assertEquals(3, InternalPipelineRole.GROUND.wireValue)
        assertEquals(4, InternalPipelineRole.MODEL.wireValue)
    }

    @Test fun theVariantCountIsBoundedAtEight() {
        assertEquals(8, allModelShaderVariants().size, "skinned x textured x masked, and nothing else")
        assertEquals(8, allModelShaderVariants().toSet().size, "no variant is listed twice")
    }

    @Test fun everyModelShaderNameIsRengPrefixedAndNoneIsReserved() {
        modelShaderNames().forEach {
            assertTrue(it.startsWith("reng"), "$it is not reng-prefixed")
            assertFalse(it in RESERVED_SHADER_NAMES, "a model shader must not grow the public shader contract")
        }
    }

    /**
     * The joint block's name is the one identifier that cannot be `reng`-prefixed in the lowercase
     * sense: a GLSL interface block name is a type name, conventionally capitalised, and RenG binds
     * it through [GlBinding.getUniformBlockIndex] rather than through [GlBinding.getUniformLocation].
     * It still must not collide with the documented contract, so it is checked here instead of being
     * quietly left out of the roster.
     */
    @Test fun theJointBlockNameIsRengPrefixedAndNotReserved() {
        assertEquals("RengModelJoints", MODEL_JOINT_BLOCK_NAME)
        assertTrue(MODEL_JOINT_BLOCK_NAME.startsWith("Reng"))
        assertFalse(MODEL_JOINT_BLOCK_NAME in RESERVED_SHADER_NAMES)
    }

    @Test fun everyVariantIsAnAcceptedShaderProfileSourcePair() {
        allModelShaderVariants().forEach { variant ->
            val pair = modelShaderPair(variant)
            assertTrue(pair.vertexSource.startsWith("#version 300 es\n"), "$variant vertex")
            assertTrue(pair.fragmentSource.startsWith("#version 300 es\n"), "$variant fragment")
            assertNotNull(scanShaderProfile(pair.vertexSource), "$variant vertex is not scannable")
            assertNotNull(scanShaderProfile(pair.fragmentSource), "$variant fragment is not scannable")
        }
    }

    @Test fun everyVariantIsItsOwnProgramAndItsOwnCacheEntry() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipelines = allModelShaderVariants().map { createdPipeline(binding, it, cache) }
        assertEquals(8, pipelines.map { it.key }.toSet().size, "each variant needs its own ResourceKey")
        assertEquals(8, pipelines.map { it.program }.toSet().size, "each variant needs its own program")
    }

    @Test fun aSkinnedVariantBindsItsJointBlockToRengsOwnBindingPoint() {
        val binding = newBinding()
        createdPipeline(binding, ModelShaderVariant(skinned = true, hasBaseColourTexture = false, masked = false))
        assertTrue(
            binding.log.any { it.startsWith("getUniformBlockIndex(") && it.endsWith(",$MODEL_JOINT_BLOCK_NAME)") },
        )
        assertTrue(
            binding.log.any {
                it.startsWith("uniformBlockBinding(") &&
                    it.endsWith(",$JOINT_BLOCK_INDEX,$RENG_JOINT_UNIFORM_BINDING_POINT)")
            },
        )
        val allocation = "bufferData(${hex(GL_UNIFORM_BUFFER)},$MODEL_JOINT_BLOCK_BYTES,${hex(GL_DYNAMIC_DRAW)})"
        assertTrue(binding.log.contains(allocation), "the skinned variant allocates the block it declares")
    }

    /** A 16 KB uniform block on every static model is a cost with no purpose. */
    @Test fun anUnskinnedVariantDeclaresNoJointBlockAtAll() {
        val unskinned = ModelShaderVariant(skinned = false, hasBaseColourTexture = false, masked = false)
        val pair = modelShaderPair(unskinned)
        assertFalse(pair.vertexSource.contains(MODEL_JOINT_BLOCK_NAME), "no block declaration")
        assertFalse(pair.vertexSource.contains(MODEL_JOINT_MATRICES_UNIFORM_NAME), "no joint array")
        assertFalse(pair.vertexSource.contains(MODEL_JOINTS_ATTRIBUTE_NAME), "no joint attribute")
        assertFalse(pair.vertexSource.contains(MODEL_WEIGHTS_ATTRIBUTE_NAME), "no weight attribute")

        val binding = newBinding()
        val pipeline = createdPipeline(binding, unskinned)
        assertEquals(-1, pipeline.jointBlockIndex)
        assertEquals(0, pipeline.jointBuffer, "an unskinned variant allocates no 16 KB uniform buffer")
        assertFalse(binding.log.any { it.startsWith("getUniformBlockIndex") })
        assertFalse(binding.log.any { it.startsWith("uniformBlockBinding") })
    }

    /**
     * A typed refusal rather than a link error whose message RenG must not forward. GLES 3.0 and GL
     * 3.3 both guarantee 16384, so a context reporting less is broken rather than merely small — but
     * discovering that as an unexplained failed link, with a driver string RenG is forbidden to
     * relay, would leave a consumer nothing to act on.
     */
    @Test fun aContextBelowTheGuaranteedUniformBlockSizeIsRefusedWithATypedFailure() {
        val binding = newBinding()
        binding.integers[GL_MAX_UNIFORM_BLOCK_SIZE] = intArrayOf(16383)
        val result = createModelPipeline(
            binding = binding,
            dialect = ShaderDialect.GLES,
            cache = GlProgramCache(),
            variant = ModelShaderVariant(skinned = true, hasBaseColourTexture = false, masked = false),
        )
        val failure = (result as ModelPipelineResult.Failed).failure
        assertEquals(com.rohittp.reng.RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(com.rohittp.reng.PipelineStage.GPU_RESOURCE, failure.stage)
        assertFalse(binding.log.any { it.startsWith("linkProgram") }, "refused before anything was linked")
    }

    @Test fun aPrimitiveUploadsOneBufferPerPresentAttributeAndNoneForAbsentOnes() {
        val binding = RecordingGlBinding()
        val uploaded = uploadModelPrimitive(binding, decodedPrimitive(withNormals = true, withTexCoords = true))

        // position + normal + texcoord, plus the index buffer: four in total, and no colour, joint
        // or weight buffer for the three attributes this primitive does not carry.
        assertEquals(3, uploaded.buffers.size)
        assertEquals(
            setOf(ModelVertexAttribute.POSITION, ModelVertexAttribute.NORMAL, ModelVertexAttribute.TEX_COORD),
            uploaded.attributes,
        )
        assertEquals(4, binding.log.count { it.startsWith("genBuffers") })
        assertEquals(3, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertTrue(binding.log.contains("enableVertexAttribArray(${ModelVertexAttribute.POSITION.location})"))
        assertTrue(binding.log.contains("enableVertexAttribArray(${ModelVertexAttribute.NORMAL.location})"))
        assertTrue(binding.log.contains("enableVertexAttribArray(${ModelVertexAttribute.TEX_COORD.location})"))
        assertFalse(binding.log.contains("enableVertexAttribArray(${ModelVertexAttribute.COLOUR.location})"))
        assertFalse(binding.log.contains("enableVertexAttribArray(${ModelVertexAttribute.JOINTS.location})"))
        assertFalse(binding.log.contains("enableVertexAttribArray(${ModelVertexAttribute.WEIGHTS.location})"))
    }

    @Test fun aPositionOnlyPrimitiveUploadsExactlyOneAttributeBuffer() {
        val binding = RecordingGlBinding()
        val uploaded = uploadModelPrimitive(binding, decodedPrimitive())
        assertEquals(1, uploaded.buffers.size)
        assertEquals(setOf(ModelVertexAttribute.POSITION), uploaded.attributes)
        assertEquals(1, binding.log.count { it.startsWith("enableVertexAttribArray") })
    }

    /**
     * The index run travels as its own `GL_ELEMENT_ARRAY_BUFFER`, recorded into the VAO, with the
     * width and count [ModelIndices] already resolved. Getting the width wrong reads the run at half
     * or double stride and produces geometry that is scrambled rather than absent.
     */
    @Test fun theIndexRunKeepsItsDecodedWidthAndCount() {
        val binding = RecordingGlBinding()
        val uploaded = uploadModelPrimitive(binding, decodedPrimitive())
        assertEquals(GL_UNSIGNED_SHORT, uploaded.indexType)
        assertEquals(3, uploaded.indexCount)
        assertTrue(binding.log.contains("bindBuffer(${hex(GL_ELEMENT_ARRAY_BUFFER)},${uploaded.indexBuffer})"))
        assertEquals(6, binding.bufferDataPayloads[GL_ELEMENT_ARRAY_BUFFER]?.size, "three little-endian shorts")
    }

    @Test fun deletingAnUploadedPrimitiveFreesEveryBufferAndTheVertexArray() {
        val binding = RecordingGlBinding()
        val uploaded = uploadModelPrimitive(binding, decodedPrimitive(withNormals = true))
        binding.log.clear()
        binding.deletedNames.clear()
        deleteUploadedPrimitive(binding, uploaded)
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(
            (uploaded.buffers + uploaded.indexBuffer).sorted(),
            binding.deletedNames.filter { it != uploaded.vertexArray }.sorted(),
        )
    }

    @Test fun anEmptyModelListIssuesNoGlCallsAtAll() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(binding, pipelines, emptyList(), LIGHT)
        assertContentEquals(emptyList(), binding.log)
    }

    @Test fun aModelWithNoPrimitivesIssuesNoGlCallsEither() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(binding, pipelines, listOf(ResolvedModel(emptyList())), LIGHT)
        assertContentEquals(emptyList(), binding.log)
    }

    /**
     * `doubleSided` is true on 109 of the corpus's 111 materials, which happens to match the ambient
     * state `drawFrame` leaves behind — it sets a cull mode and never enables `GL_CULL_FACE` — so
     * only the two single-sided materials need anything done at all. Turning culling on and never
     * off again would hollow out every double-sided model that followed in the same frame.
     */
    @Test fun aSingleSidedMaterialEnablesCullingAndADoubleSidedOneDisablesIt() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(
                    listOf(
                        resolved(indexCount = 11, material = material(doubleSided = false)),
                        resolved(indexCount = 22, material = material(doubleSided = true)),
                    ),
                ),
            ),
            LIGHT,
        )
        val enable = binding.log.indexOfFirst { it == "enable(${hex(GL_CULL_FACE)})" }
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},11") }
        val disable = binding.log.indexOfFirst { it == "disable(${hex(GL_CULL_FACE)})" && it.isNotEmpty() }
        val secondDraw = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},22") }
        assertTrue(enable in 0 until firstDraw, "a single-sided material culls its back faces")
        assertTrue(firstDraw < secondDraw)
        assertTrue(
            binding.log.subList(firstDraw, secondDraw).contains("disable(${hex(GL_CULL_FACE)})"),
            "a double-sided material must turn culling back off before it draws",
        )
        assertTrue(disable >= 0)
    }

    /**
     * glTF requires the reversal whenever the node's global transform has a negative determinant.
     * Missing it produces inside-out models with no error at all — which reads as a broken asset
     * rather than a broken renderer, and is exactly why this one gets a mutation check.
     */
    @Test fun aMirroredNodeTransformReversesTheWindingOrder() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(ResolvedModel(listOf(resolved(indexCount = 33, reverseWinding = true)))),
            LIGHT,
        )
        val reversed = binding.log.indexOfFirst { it == "frontFace(${hex(GL_CW)})" }
        val draw = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},33") }
        assertTrue(reversed in 0 until draw, "a mirrored primitive must wind clockwise before it draws")
    }

    @Test fun anUnmirroredPrimitiveAfterAMirroredOneWindsCounterClockwiseAgain() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(
                    listOf(
                        resolved(indexCount = 33, reverseWinding = true),
                        resolved(indexCount = 44, reverseWinding = false),
                    ),
                ),
            ),
            LIGHT,
        )
        val mirrored = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},33") }
        val ordinary = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},44") }
        assertTrue(
            binding.log.subList(mirrored, ordinary).contains("frontFace(${hex(GL_CCW)})"),
            "the reversal must not leak onto the next primitive",
        )
    }

    /**
     * Two passes: every opaque and masked primitive first, with depth writes on, then every blended
     * one, sorted back to front with writes off. The sort key is each primitive's own clip-space
     * `w` — its distance from the camera — so a fixture whose blended primitives are declared
     * near-first proves the sort ran rather than merely that declaration order happened to be right.
     */
    @Test fun blendedPrimitivesDrawAfterOpaqueOnesAndSortedBackToFront() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(
                    listOf(
                        resolved(indexCount = 11, material = material(alphaMode = "BLEND"), cameraDistance = 2.0f),
                        resolved(indexCount = 22, material = material(alphaMode = "BLEND"), cameraDistance = 9.0f),
                        resolved(indexCount = 33, material = material(alphaMode = "OPAQUE")),
                    ),
                ),
            ),
            LIGHT,
        )
        assertEquals(listOf(33, 22, 11), drawnIndexCounts(binding))
    }

    @Test fun theBlendedPassSortsAcrossEveryModelInTheFrame() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(listOf(resolved(indexCount = 11, material = blend(), cameraDistance = 3.0f))),
                ResolvedModel(listOf(resolved(indexCount = 22, material = blend(), cameraDistance = 7.0f))),
                ResolvedModel(listOf(resolved(indexCount = 33, material = material()))),
            ),
            LIGHT,
        )
        assertEquals(listOf(33, 22, 11), drawnIndexCounts(binding))
    }

    @Test fun theOpaquePassWritesDepthAndTheBlendedPassDoesNot() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(ResolvedModel(listOf(resolved(indexCount = 11), resolved(indexCount = 22, material = blend())))),
            LIGHT,
        )
        val writesOn = binding.log.indexOfFirst { it == "depthMask(true)" }
        val opaqueDraw = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},11") }
        val writesOff = binding.log.indexOfFirst { it == "depthMask(false)" }
        val blendedDraw = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},22") }
        assertTrue(binding.log.contains("enable(${hex(GL_DEPTH_TEST)})"), "models are depth-tested")
        assertTrue(writesOn in 0 until opaqueDraw, "the opaque pass writes depth (ADR 0030)")
        assertTrue(opaqueDraw < writesOff, "writes stay on for the whole opaque pass")
        assertTrue(writesOff in 0 until blendedDraw, "the blended pass writes no depth")
        assertEquals(1, binding.log.count { it == "depthMask(true)" }, "one phase change, not one per primitive")
    }

    /**
     * A frame whose models are all opaque still has to leave depth writes off behind it: ADR 0027's
     * billboard fix is what a map-anchored sticker drawn afterwards depends on, and it silently
     * stops working the moment a model in the same frame leaves the mask on.
     */
    @Test fun theDrawLeavesDepthWritesOffEvenWhenNothingBlends() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(binding, pipelines, listOf(ResolvedModel(listOf(resolved()))), LIGHT)
        assertEquals("depthMask(false)", binding.log.last { it.startsWith("depthMask") })
    }

    @Test fun theBlendedPassUsesThePremultipliedBlendFunctionRatherThanStraightAlpha() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(binding, pipelines, listOf(ResolvedModel(listOf(resolved(material = blend())))), LIGHT)
        assertTrue(binding.log.contains("blendFuncSeparate(0x1,0x303,0x1,0x303)"))
        assertFalse(binding.log.any { it.startsWith("blendFuncSeparate(0x302,") })
    }

    @Test fun theOpaquePassDisablesBlendingRatherThanInheritingIt() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(binding, pipelines, listOf(ResolvedModel(listOf(resolved(indexCount = 11)))), LIGHT)
        val disabled = binding.log.indexOfFirst { it == "disable(${hex(GL_BLEND)})" }
        val draw = binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},11") }
        assertTrue(disabled in 0 until draw)
    }

    /**
     * The offscreen surface is RGBA and the composite blends by alpha, so an `OPAQUE` primitive
     * whose base colour factor or texture carries alpha below one would be faded into the consumer's
     * own background — invisible against an opaque basemap and wrong the moment anyone composites
     * RenG over something. glTF is explicit that `OPAQUE` ignores alpha entirely; `MASK` does too,
     * once its cutoff test has passed. Only `BLEND` writes the material's own alpha.
     */
    @Test fun anOpaquePrimitiveWritesAlphaOneSoTheCompositeDoesNotFadeIt() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(
                    listOf(
                        resolved(indexCount = 11, material = material(alphaMode = "OPAQUE")),
                        resolved(indexCount = 22, material = material(alphaMode = "MASK")),
                        resolved(indexCount = 33, material = blend(), cameraDistance = 4.0f),
                    ),
                ),
            ),
            LIGHT,
        )
        assertEquals(
            listOf("1.0", "1.0", "0.0"),
            binding.log.filter { it.startsWith("uniform1f($FORCE_OPAQUE_LOCATION,") }
                .map { it.removePrefix("uniform1f($FORCE_OPAQUE_LOCATION,").removeSuffix(")") },
        )
    }

    @Test fun aMaskedPrimitiveCarriesItsOwnCutoffAndAnUnmaskedOneCarriesNone() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(ResolvedModel(listOf(resolved(material = material(alphaMode = "MASK", cutoff = 0.25f))))),
            LIGHT,
        )
        assertTrue(binding.log.contains("uniform1f($ALPHA_CUTOFF_LOCATION,0.25)"))

        val plain = newBinding()
        val plainPipelines = pipelines(plain)
        plain.log.clear()
        drawModels(plain, plainPipelines, listOf(ResolvedModel(listOf(resolved()))), LIGHT)
        assertFalse(plain.log.any { it.startsWith("uniform1f($ALPHA_CUTOFF_LOCATION,") })
    }

    @Test fun aTexturedPrimitiveBindsItsBaseColourTextureOnUnitZero() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(
                    listOf(
                        resolved(
                            baseColourTexture = 77,
                            attributes = setOf(
                                ModelVertexAttribute.POSITION,
                                ModelVertexAttribute.NORMAL,
                                ModelVertexAttribute.TEX_COORD,
                            ),
                        ),
                    ),
                ),
            ),
            LIGHT,
        )
        assertTrue(binding.log.contains("activeTexture(${hex(GL_TEXTURE0)})"))
        assertTrue(binding.log.contains("bindTexture(${hex(GL_TEXTURE_2D)},77)"))
        assertTrue(binding.log.contains("uniform1i($BASE_COLOUR_TEXTURE_LOCATION,0)"))
    }

    /**
     * Task 9 reported both halves of this: a material may name a `baseColorTexture` while its
     * primitive carries no `TEXCOORD_0`, and `GltfTexture.source` is nullable so the texture may
     * resolve to no image at all. Neither is a decode fault, and neither may bind a sampler with no
     * coordinates to sample it with — a bound sampler read through an undefined varying is the
     * garbage this refuses to draw. The untextured variant paints the base colour factor alone.
     */
    @Test fun aTextureOnAPrimitiveWithNoTexCoordsFallsBackToTheFactorAlone() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        val primitive = resolved(
            baseColourTexture = 77,
            attributes = setOf(ModelVertexAttribute.POSITION, ModelVertexAttribute.NORMAL),
        )
        assertFalse(modelShaderVariantFor(primitive).hasBaseColourTexture)
        drawModels(binding, pipelines, listOf(ResolvedModel(listOf(primitive))), LIGHT)
        assertFalse(binding.log.any { it == "bindTexture(${hex(GL_TEXTURE_2D)},77)" })
        assertFalse(binding.log.any { it.startsWith("uniform1i($BASE_COLOUR_TEXTURE_LOCATION,") })
        assertTrue(binding.log.any { it.startsWith("uniform4f($BASE_COLOUR_FACTOR_LOCATION,") })
    }

    @Test fun aPrimitiveWithTexCoordsButNoResolvedTextureAlsoFallsBackToTheFactorAlone() {
        val primitive = resolved(
            baseColourTexture = null,
            attributes = setOf(
                ModelVertexAttribute.POSITION,
                ModelVertexAttribute.NORMAL,
                ModelVertexAttribute.TEX_COORD,
            ),
        )
        assertFalse(modelShaderVariantFor(primitive).hasBaseColourTexture)
    }

    @Test fun aSkinnedPrimitiveUploadsItsPaletteAndBindsItAtRengsBindingPoint() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        val primitive = resolved(
            jointMatrices = FloatArray(32) { it.toFloat() },
            attributes = setOf(
                ModelVertexAttribute.POSITION,
                ModelVertexAttribute.NORMAL,
                ModelVertexAttribute.JOINTS,
                ModelVertexAttribute.WEIGHTS,
            ),
        )
        assertTrue(modelShaderVariantFor(primitive).skinned)
        drawModels(binding, pipelines, listOf(ResolvedModel(listOf(primitive))), LIGHT)
        assertTrue(binding.log.any { it.startsWith("bufferSubData(${hex(GL_UNIFORM_BUFFER)},0,128)") })
        assertTrue(
            binding.log.any {
                it.startsWith("bindBufferBase(${hex(GL_UNIFORM_BUFFER)},$RENG_JOINT_UNIFORM_BINDING_POINT,")
            },
        )
    }

    /**
     * Joints and weights on the primitive but no palette from the caller means the draw item carried
     * no skin — an unskinned draw of a skinnable mesh, which glTF permits. It must take the static
     * variant rather than sample an uninitialised joint buffer.
     */
    @Test fun aPrimitiveWithJointsButNoPaletteDrawsStatically() {
        val primitive = resolved(
            jointMatrices = null,
            attributes = setOf(
                ModelVertexAttribute.POSITION,
                ModelVertexAttribute.JOINTS,
                ModelVertexAttribute.WEIGHTS,
            ),
        )
        assertFalse(modelShaderVariantFor(primitive).skinned)
    }

    @Test fun theLightAndItsAmbientTermReachEveryProgramTheDrawUses() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(
                    listOf(
                        resolved(indexCount = 11),
                        resolved(indexCount = 22, material = material(alphaMode = "MASK")),
                    ),
                ),
            ),
            LIGHT,
        )
        val programs = binding.log.filter { it.startsWith("useProgram") }.toSet()
        assertEquals(2, programs.size, "an unmasked and a masked primitive are two different programs")
        assertEquals(
            2,
            binding.log.count { it == "uniform3f($LIGHT_DIRECTION_LOCATION,${LIGHT[0]},${LIGHT[1]},${LIGHT[2]})" },
            "the light must be set once per program, since a uniform belongs to its program",
        )
        assertEquals(2, binding.log.count { it == "uniform1f($AMBIENT_LOCATION,$SCENE_LIGHT_AMBIENT)" })
    }

    @Test fun aRepeatedVariantSwitchesProgramOnlyWhenTheVariantChanges() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(ResolvedModel(listOf(resolved(indexCount = 11), resolved(indexCount = 22)))),
            LIGHT,
        )
        assertEquals(1, binding.log.count { it.startsWith("useProgram") })
    }

    @Test fun theVertexColourFlagIsSetOnlyWhenThePrimitiveCarriesColours() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(
            binding,
            pipelines,
            listOf(
                ResolvedModel(
                    listOf(
                        resolved(
                            indexCount = 11,
                            attributes = setOf(ModelVertexAttribute.POSITION, ModelVertexAttribute.COLOUR),
                        ),
                        resolved(indexCount = 22, attributes = setOf(ModelVertexAttribute.POSITION)),
                    ),
                ),
            ),
            LIGHT,
        )
        assertEquals(
            listOf("uniform1f($VERTEX_COLOUR_LOCATION,1.0)", "uniform1f($VERTEX_COLOUR_LOCATION,0.0)"),
            binding.log.filter { it.startsWith("uniform1f($VERTEX_COLOUR_LOCATION,") },
        )
    }

    @Test fun bothMatricesTravelToTheirOwnUniformsPerPrimitive() {
        val binding = newBinding()
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(binding, pipelines, listOf(ResolvedModel(listOf(resolved()))), LIGHT)
        assertTrue(binding.log.contains("uniformMatrix4fv($MODEL_VIEW_PROJECTION_LOCATION,1,false)"))
        assertTrue(binding.log.contains("uniformMatrix4fv($NORMAL_MATRIX_LOCATION,1,false)"))
    }

    @Test fun drawingSkipsEveryUniformBindWhenTheProgramDeclaresNone() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        binding.integers[GL_MAX_UNIFORM_BLOCK_SIZE] = intArrayOf(16384)
        val pipelines = pipelines(binding)
        binding.log.clear()
        drawModels(binding, pipelines, listOf(ResolvedModel(listOf(resolved(baseColourTexture = 5)))), LIGHT)
        assertFalse(binding.log.any { it.startsWith("uniformMatrix4fv") })
        assertFalse(binding.log.any { it.startsWith("uniform4f") })
        assertFalse(binding.log.any { it.startsWith("uniform3f") })
        assertFalse(binding.log.any { it.startsWith("uniform1f") })
        assertTrue(binding.log.any { it.startsWith("drawElements") }, "it still draws")
    }

    /**
     * The inverse transpose of the linear block, in the same camera space the positions use, shipped
     * as a `mat4` the shader narrows with `mat3(...)` — which is why the GL seam needs no
     * `uniformMatrix3fv`.
     *
     * **The fixture shears as well as scaling, and the shear is the load-bearing part.** A rotation is
     * its own inverse transpose, and a diagonal matrix is its own transpose, so either would pass
     * unchanged against an implementation that forgot to transpose — this test was written with a
     * `diag(2, 4, 1)` fixture first and a mutation deleting `.transpose()` left it green. With the
     * shear, all four of inverse-transpose, plain inverse, plain transpose and the untouched matrix
     * give different answers at elements 0, 1 and 4.
     *
     * `linear` is `[[2, 1, 0], [0, 4, 0], [0, 0, 1]]`, whose inverse is
     * `[[0.5, -0.125, 0], [0, 0.25, 0], [0, 0, 1]]`, so the inverse transpose puts `-0.125` at row 1
     * column 0 — element 1 of a column-major array — and `0` at row 0 column 1, element 4. A dropped
     * transpose swaps exactly those two.
     */
    @Test fun theNormalMatrixIsTheInverseTransposeOfTheModelMatrixLinearBlock() {
        val model = DoubleMatrix4.fromRows(
            listOf(
                listOf(2.0, 1.0, 0.0, 100.0),
                listOf(0.0, 4.0, 0.0, 200.0),
                listOf(0.0, 0.0, 1.0, 300.0),
                listOf(0.0, 0.0, 0.0, 1.0),
            ),
        )
        val normal = assertNotNull(modelNormalMatrix(model))
        assertEquals(16, normal.size)
        assertEquals(0.5f, normal[0], 1e-6f)
        assertEquals(-0.125f, normal[1], 1e-6f, "the transpose puts the shear term below the diagonal")
        assertEquals(0.0f, normal[4], 1e-6f, "and leaves nothing above it")
        assertEquals(0.25f, normal[5], 1e-6f)
        assertEquals(1.0f, normal[10], 1e-6f)
        assertEquals(0.0f, normal[12], 1e-6f, "a direction transform carries no translation")
        assertEquals(0.0f, normal[13], 1e-6f)
        assertEquals(0.0f, normal[14], 1e-6f)
        assertEquals(1.0f, normal[15], 1e-6f)
    }

    @Test fun aSingularModelMatrixYieldsNoNormalMatrixRatherThanAnIdentityOne() {
        val flattened = DoubleMatrix4.fromRows(
            listOf(
                listOf(1.0, 0.0, 0.0, 0.0),
                listOf(0.0, 1.0, 0.0, 0.0),
                listOf(0.0, 0.0, 0.0, 0.0),
                listOf(0.0, 0.0, 0.0, 1.0),
            ),
        )
        assertNull(modelNormalMatrix(flattened))
    }

    /**
     * `inverseAffine(global[n]) * global[jointNodes[i]] * inverseBindMatrices[i]`, with the fixture
     * chosen so that every one of the three terms is distinguishable: the skinned node translates by
     * 10 along x, the joint node by 3, and the inverse bind matrix by -1, so the palette entry
     * translates by `-10 + 3 + -1 = -8` and dropping any term changes the answer.
     */
    @Test fun aJointMatrixUndoesTheSkinnedNodesOwnTransform() {
        val skinnedNode = translation(10.0)
        val jointNode = translation(3.0)
        val skin = DecodedSkin(
            jointNodes = listOf(1),
            inverseBindMatrices = listOf(translation(-1.0)),
            skeletonRoot = null,
        )
        val matrices = assertNotNull(jointMatricesForSkin(skin, listOf(skinnedNode, jointNode), 0))
        assertEquals(1, matrices.size)
        assertEquals(-8.0, matrices[0][0, 3], 1e-9)
    }

    @Test fun aSingularSkinnedNodeTransformYieldsNoJointMatricesRatherThanIdentity() {
        val collapsed = DoubleMatrix4.fromRows(
            listOf(
                listOf(0.0, 0.0, 0.0, 0.0),
                listOf(0.0, 0.0, 0.0, 0.0),
                listOf(0.0, 0.0, 0.0, 0.0),
                listOf(0.0, 0.0, 0.0, 1.0),
            ),
        )
        val skin = DecodedSkin(listOf(1), listOf(DoubleMatrix4.identity), null)
        assertNull(jointMatricesForSkin(skin, listOf(collapsed, DoubleMatrix4.identity), 0))
    }

    @Test fun aJointNodeTheSceneNeverReachesYieldsNoJointMatrices() {
        val skin = DecodedSkin(listOf(1), listOf(DoubleMatrix4.identity), null)
        assertNull(jointMatricesForSkin(skin, listOf(DoubleMatrix4.identity, null), 0))
    }

    /**
     * The block holds 256 `mat4`, which is exactly the 16384 bytes GLES 3.0 guarantees. The bound is
     * written here as the literal 256 and 257 rather than derived from [MAXIMUM_MODEL_JOINTS]: a
     * fixture built from the constant under test moves with it and passes whatever the constant
     * becomes.
     */
    @Test fun theJointPaletteRefusesMoreJointsThanTheBlockHolds() {
        assertNotNull(packJointMatrices(List(256) { DoubleMatrix4.identity }))
        assertNull(packJointMatrices(List(257) { DoubleMatrix4.identity }))
    }

    @Test fun theJointPaletteIsColumnMajorAndSixteenFloatsPerJoint() {
        val packed = assertNotNull(packJointMatrices(listOf(translation(7.0), DoubleMatrix4.identity)))
        assertEquals(32, packed.size)
        assertEquals(7.0f, packed[12], 1e-6f, "column-major puts the translation in elements 12..14")
        assertEquals(1.0f, packed[15], 1e-6f)
        assertEquals(0.0f, packed[28], 1e-6f, "the second joint is the identity")
    }

    private fun drawnIndexCounts(binding: RecordingGlBinding): List<Int> = binding.log
        .filter { it.startsWith("drawElements(") }
        .map { it.removePrefix("drawElements(${hex(GL_TRIANGLES)},").substringBefore(',').toInt() }

    private fun pipelines(binding: RecordingGlBinding): Map<ModelShaderVariant, ModelPipeline> {
        val cache = GlProgramCache()
        return allModelShaderVariants().associateWith { createdPipeline(binding, it, cache) }
    }

    private fun createdPipeline(
        binding: RecordingGlBinding,
        variant: ModelShaderVariant,
        cache: GlProgramCache = GlProgramCache(),
    ): ModelPipeline = (
        createModelPipeline(binding, ShaderDialect.GLES, cache, variant) as ModelPipelineResult.Created
        ).pipeline

    private fun material(
        alphaMode: String = "OPAQUE",
        cutoff: Float = 0.5f,
        doubleSided: Boolean = true,
    ): ResolvedMaterial = ResolvedMaterial(
        baseColourFactor = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
        baseColourImageIndex = null,
        baseColourSampler = TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT),
        alphaMode = alphaMode,
        alphaCutoff = cutoff,
        doubleSided = doubleSided,
    )

    private fun blend(): ResolvedMaterial = material(alphaMode = "BLEND")

    private fun resolved(
        indexCount: Int = 3,
        material: ResolvedMaterial = material(),
        baseColourTexture: Int? = null,
        jointMatrices: FloatArray? = null,
        reverseWinding: Boolean = false,
        cameraDistance: Float = 1.0f,
        attributes: Set<ModelVertexAttribute> = setOf(
            ModelVertexAttribute.POSITION,
            ModelVertexAttribute.NORMAL,
        ),
    ): ResolvedModelPrimitive = ResolvedModelPrimitive(
        uploaded = UploadedPrimitive(
            vertexArray = 1,
            buffers = listOf(2),
            indexBuffer = 3,
            indexCount = indexCount,
            indexType = GL_UNSIGNED_SHORT,
            attributes = attributes,
        ),
        modelViewProjection = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = cameraDistance },
        normalMatrix = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f },
        material = material,
        baseColourTexture = baseColourTexture,
        jointMatrices = jointMatrices,
        reverseWinding = reverseWinding,
    )

    private fun decodedPrimitive(
        withNormals: Boolean = false,
        withTexCoords: Boolean = false,
    ): DecodedPrimitive = DecodedPrimitive(
        positions = FloatArray(9) { it.toFloat() },
        normals = if (withNormals) FloatArray(9) { 0.0f } else null,
        texCoords = if (withTexCoords) FloatArray(6) { 0.5f } else null,
        colours = null,
        joints = null,
        weights = null,
        indices = ModelIndices(shortArrayOf(0, 1, 2), null, GL_UNSIGNED_SHORT, 3),
        material = material(),
    )

    private fun translation(x: Double): DoubleMatrix4 = DoubleMatrix4.fromRows(
        listOf(
            listOf(1.0, 0.0, 0.0, x),
            listOf(0.0, 1.0, 0.0, 0.0),
            listOf(0.0, 0.0, 1.0, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding()
        .withDeclaredNames(
            MODEL_POSITION_ATTRIBUTE_NAME to ModelVertexAttribute.POSITION.location,
            MODEL_NORMAL_ATTRIBUTE_NAME to ModelVertexAttribute.NORMAL.location,
            MODEL_TEX_COORD_ATTRIBUTE_NAME to ModelVertexAttribute.TEX_COORD.location,
            MODEL_COLOUR_ATTRIBUTE_NAME to ModelVertexAttribute.COLOUR.location,
            MODEL_JOINTS_ATTRIBUTE_NAME to ModelVertexAttribute.JOINTS.location,
            MODEL_WEIGHTS_ATTRIBUTE_NAME to ModelVertexAttribute.WEIGHTS.location,
            MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
            MODEL_NORMAL_MATRIX_UNIFORM_NAME to NORMAL_MATRIX_LOCATION,
            MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME to BASE_COLOUR_FACTOR_LOCATION,
            MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME to BASE_COLOUR_TEXTURE_LOCATION,
            MODEL_ALPHA_CUTOFF_UNIFORM_NAME to ALPHA_CUTOFF_LOCATION,
            MODEL_LIGHT_DIRECTION_UNIFORM_NAME to LIGHT_DIRECTION_LOCATION,
            MODEL_AMBIENT_UNIFORM_NAME to AMBIENT_LOCATION,
            MODEL_FORCE_OPAQUE_UNIFORM_NAME to FORCE_OPAQUE_LOCATION,
            MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME to VERTEX_COLOUR_LOCATION,
            MODEL_JOINT_BLOCK_NAME to JOINT_BLOCK_INDEX,
        )
        .also { it.integers[GL_MAX_UNIFORM_BLOCK_SIZE] = intArrayOf(16384) }

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

    private companion object {
        const val MODEL_VIEW_PROJECTION_LOCATION: Int = 10
        const val NORMAL_MATRIX_LOCATION: Int = 11
        const val BASE_COLOUR_FACTOR_LOCATION: Int = 12
        const val BASE_COLOUR_TEXTURE_LOCATION: Int = 13
        const val ALPHA_CUTOFF_LOCATION: Int = 14
        const val LIGHT_DIRECTION_LOCATION: Int = 15
        const val AMBIENT_LOCATION: Int = 16
        const val FORCE_OPAQUE_LOCATION: Int = 17
        const val VERTEX_COLOUR_LOCATION: Int = 18
        const val JOINT_BLOCK_INDEX: Int = 19
        val LIGHT: FloatArray = floatArrayOf(-0.25f, 0.5f, 0.75f)
    }
}
