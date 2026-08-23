package com.rohittp.reng.internal.glb

import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Every buffer/bufferView fixture below sizes its declared buffer at [FIXTURE_BIN_CHUNK_LENGTH]
 * or less, so a single generous [parse] default keeps buffer-length arithmetic (a `PARSE_GLB`
 * concern already covered by [GltfParseTest]) out of every fixture's way here. */
private const val FIXTURE_BIN_CHUNK_LENGTH = 4096L

class GltfFeaturesTest {
    @Test
    fun rejectsEveryUnsupportedFeatureWithItsOwnReason() {
        assertEquals(GltfUnsupported.EXTENSION_REQUIRED, unsupported(dracoShapedDocument))
        assertEquals(GltfUnsupported.ACCESSOR_WITHOUT_BUFFER_VIEW, unsupported(accessorWithoutBufferViewNoExtension))
        assertEquals(GltfUnsupported.SPARSE_ACCESSOR, unsupported(sparseAccessor))
        assertEquals(GltfUnsupported.PRIMITIVE_MODE, unsupported(triangleStrip))
        assertEquals(GltfUnsupported.PRIMITIVE_MODE, unsupported(pointsMode))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(customAttribute))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(nonCanonicalTexcoordIndex))
        assertEquals(GltfUnsupported.MORPH_TARGET, unsupported(documentWithMorphTargets))
        assertEquals(GltfUnsupported.ANIMATION_TARGET_PATH, unsupported(weightsChannel))
        assertEquals(GltfUnsupported.INTERPOLATION, unsupported(cubicSplineSampler))
        assertEquals(GltfUnsupported.IMAGE_MEDIA_TYPE, unsupported(jpegImage))
        assertEquals(GltfUnsupported.EXTERNAL_URI, unsupported(imageWithUri))
        assertEquals(GltfUnsupported.EXTERNAL_URI, unsupported(bufferWithDataUri))
        assertEquals(GltfUnsupported.SCENE_AMBIGUOUS, unsupported(twoScenesNoDefault))
        assertEquals(GltfUnsupported.NORMALIZED_NOT_PERMITTED, unsupported(normalizedFloatAccessor))
    }

    @Test
    fun acceptsEverythingTheSubsetAdmits() {
        for (fixture in listOf(
            triangleIndexed, triangleNonIndexed, nodeMatrixOnly, nodeTrsOnly,
            tangentAndColourZero, sceneAbsentWithExactlyOneScene, interleavedStride,
            linearAndStepAnimation, embeddedPngImage, fullMaterialBlock,
            cameraAndLightIgnored, extensionsUsedWithoutRequired,
        )) {
            assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(fixture.document))
        }
    }

    // ---- supplementary coverage: called out by the task brief but not in its literal table ----

    @Test
    fun rejectsSceneAmbiguityWhenNoSceneIsDeclaredAtAll() {
        // The same SCENE_AMBIGUOUS rule's other half: "scene absent with zero ... scenes", as
        // distinct from twoScenesNoDefault's "... or two-or-more scenes".
        assertEquals(GltfUnsupported.SCENE_AMBIGUOUS, unsupported(zeroScenesNoDefault))
    }

    @Test
    fun rejectsAnyBufferOtherThanBufferZero() {
        // PARSE_GLB tolerates a second declared buffer -- nothing about it is malformed on its
        // own -- but RenG has no route to any buffer but the GLB-embedded buffers[0].
        assertEquals(GltfUnsupported.MULTIPLE_BUFFERS, unsupported(secondBufferDeclared))
    }

    @Test
    fun neverLeaksAnAttackerControlledUriIntoTheRejectionItself() {
        // GltfUnsupported.EXTERNAL_URI carries no payload -- confirm the actual uri text used by
        // the fixture below cannot be recovered from the result's own string form.
        val secretUri = "https://attacker.example/leak?token=super-secret-value"
        val json = """
            {
              "asset": {"version": "2.0"},
              "images": [{"uri": "$secretUri"}]
            }
        """.trimIndent()
        val result = validateGltfFeatures(json.document)
        assertEquals(GltfUnsupported.EXTERNAL_URI, assertIs<GltfFeatureResult.Unsupported>(result).reason)
        assertFalse(result.toString().contains("attacker"), "uri text leaked into a diagnostic")
    }

    @Test
    fun rejectsAPrimitiveThatDeclaresNoPositionAttribute() {
        // PARSE_GLB accepts this document (the specification permits it and says a client SHOULD
        // skip the primitive); RenG refuses instead of skipping, because drawing part of a model
        // and reporting success is the quiet fallback ADR 0021 rejects everywhere else.
        assertEquals(GltfUnsupported.PRIMITIVE_WITHOUT_POSITION, unsupported(primitiveWithoutPosition))
    }

    @Test
    fun rejectsAnAdmittedAttributeSemanticInAFormatRenGDoesNotBind() {
        // ADR 0021's accept list names the six component types as one flat set across every
        // accessor. That is the parser's granularity, not the renderer's: a SCALAR/BYTE POSITION
        // or a MAT4 TEXCOORD_0 passed both gates as "supported" and has no draw behaviour.
        assertEquals(GltfUnsupported.ATTRIBUTE_FORMAT, unsupported(scalarBytePosition))
        assertEquals(GltfUnsupported.ATTRIBUTE_FORMAT, unsupported(mat4TexCoordZero))
        assertEquals(GltfUnsupported.ATTRIBUTE_FORMAT, unsupported(unnormalizedUnsignedByteTexCoordZero))
        assertEquals(GltfUnsupported.ATTRIBUTE_FORMAT, unsupported(vec3Tangent))
    }

    @Test
    fun keepsQuantizedGeometryDiagnosedByTheExtensionItRequires() {
        // The reason ATTRIBUTE_FORMAT lives on this gate rather than in PARSE_GLB:
        // KHR_mesh_quantization makes a short POSITION specification-legal, and
        // EXTENSION_REQUIRED is checked first, so a quantized asset is never reported as a
        // corrupt file for a shape its own declared extension permits.
        assertEquals(GltfUnsupported.EXTENSION_REQUIRED, unsupported(quantizedPositionWithExtension))
    }

    @Test
    fun rejectsAnAnimationSamplerInAFormatRenGCannotSample() {
        // A rotation output is a VEC4 quaternion and keyframe times are SCALAR floats. Neither
        // was constrained before, so an animation whose output is a VEC3 -- or whose input is
        // not even a number RenG can read as a time -- was reported as fully supported.
        assertEquals(GltfUnsupported.ANIMATION_ACCESSOR_FORMAT, unsupported(rotationOutputAsVec3))
        assertEquals(GltfUnsupported.ANIMATION_ACCESSOR_FORMAT, unsupported(translationOutputAsVec4))
        assertEquals(GltfUnsupported.ANIMATION_ACCESSOR_FORMAT, unsupported(samplerInputAsVec3))
        assertEquals(GltfUnsupported.ANIMATION_ACCESSOR_FORMAT, unsupported(samplerInputAsUnsignedShort))
    }

    @Test
    fun keepsAcceptingEveryQuantizedFormTheSpecificationAllowsWithoutAnExtension() {
        // The tightening must not shrink the subset: normalized unsigned byte and short
        // TEXCOORD_0/COLOR_0, a VEC3 COLOR_0, and a normalized short rotation output are all
        // core-specification forms and stay supported.
        for (fixture in listOf(
            normalizedUnsignedByteTexCoordZero, normalizedUnsignedShortColourZero,
            normalizedShortRotationOutput,
        )) {
            assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(fixture.document))
        }
    }

    // ---- F-2: the UV and colour sets RenG never reads ----

    @Test
    fun anExtraTextureCoordinateSetIsIgnoredRatherThanRejected() {
        // TEXCOORD_1 alone rejected 14 of the consumer's 41 models and was the sole reason for 10
        // of them, for carrying data RenG never reads: it samples one texture.
        val document = documentWithPrimitiveAttributes(
            "POSITION" to vec3Float(), "TEXCOORD_0" to vec2Float(), "TEXCOORD_1" to vec2Float(),
        )
        assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(document))
    }

    @Test
    fun anExtraColourSetIsIgnoredRatherThanRejected() {
        val document = documentWithPrimitiveAttributes(
            "POSITION" to vec3Float(), "COLOR_0" to vec4Float(), "COLOR_1" to vec4Float(),
        )
        assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(document))
    }

    @Test
    fun anIgnoredSetIsNotFormatCheckedBecauseRenGNeverBindsIt() {
        // TEXCOORD_1 as an unnormalized MAT4 of bytes is nonsense RenG will never read. "Ignored"
        // means *not format-checked*, not "format-checked and then discarded": a set RenG never
        // reads cannot have a format RenG cannot bind.
        val document = documentWithPrimitiveAttributes(
            "POSITION" to vec3Float(), "TEXCOORD_1" to accessor(type = "MAT4", componentType = 5120),
        )
        assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(document))
    }

    @Test
    fun anApplicationSpecificSemanticIsStillRejected() {
        // The widening admits TEXCOORD_n and COLOR_n and nothing else. A widening that widens to
        // everything is not the widening this cycle asked for.
        val document = documentWithPrimitiveAttributes("POSITION" to vec3Float(), "_BATCHID" to vec3Float())
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.ATTRIBUTE_SEMANTIC),
            validateGltfFeatures(document),
        )
    }

    @Test
    fun aBaseColourTextureNamingASecondCoordinateSetIsRejected() {
        // RenG binds TEXCOORD_0 and only TEXCOORD_0, so a material asking for another set has no
        // correct render. Ignoring an extra *set* is not the same as honouring a material that
        // names one -- baseColorTexture.texCoord is 0 in every material in the consumer's corpus.
        val document = documentWithBaseColourTexture(texCoord = 1)
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.TEXTURE_COORDINATE_SET),
            validateGltfFeatures(document),
        )
        // ... and the coordinate set RenG does bind stays supported.
        assertEquals(
            GltfFeatureResult.Supported,
            validateGltfFeatures(documentWithBaseColourTexture(texCoord = 0)),
        )
    }

    // ---- F-2: vertex skinning, and the sampler state a model is textured through ----

    @Test
    fun aSkinnedNodeIsNoLongerRejected() {
        // 7 of the consumer's 41 models are truly vertex-skinned -- two elephants at 112 joints,
        // three motorbikes at 44, two dinosaurs at 38.
        assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(skinnedDocument(joints = 38)))
    }

    @Test
    fun aSecondInfluenceSetIsRejected() {
        // JOINTS_1/WEIGHTS_1 means more than four influences per vertex; RenG binds one set, and
        // dropping the second deforms the mesh differently rather than not at all.
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.MULTIPLE_SKIN_INFLUENCE_SETS),
            validateGltfFeatures(skinnedDocument(influenceSets = 2)),
        )
    }

    @Test
    fun aRigBeyondTheUniformBlockCapacityIsRejected() {
        // The number itself is a portability claim, not a preference: GLES 3.0 and GL 3.3 both
        // guarantee a GL_MAX_UNIFORM_BLOCK_SIZE of at least 16384 bytes, which is exactly 256
        // mat4. Raising it would put the joint block past what either specification promises on
        // some driver, and every assertion below is stated relative to the constant, so nothing
        // else here would notice.
        assertEquals(256, MAXIMUM_SKIN_JOINTS)
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.SKIN_JOINT_COUNT),
            validateGltfFeatures(skinnedDocument(joints = MAXIMUM_SKIN_JOINTS + 1)),
        )
        // The cap itself is inside the block, not outside it.
        assertEquals(
            GltfFeatureResult.Supported,
            validateGltfFeatures(skinnedDocument(joints = MAXIMUM_SKIN_JOINTS)),
        )
    }

    @Test
    fun aSkinnedNodeWhoseMeshCarriesNoWeightsIsRejected() {
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.SKINNED_PRIMITIVE_ATTRIBUTES),
            validateGltfFeatures(skinnedDocument(attributes = listOf("POSITION", "JOINTS_0"))),
        )
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.SKINNED_PRIMITIVE_ATTRIBUTES),
            validateGltfFeatures(skinnedDocument(attributes = listOf("POSITION", "WEIGHTS_0"))),
        )
    }

    @Test
    fun theEighteenExporterLeftoverSkinsStillPass() {
        // A `skins` array no node references is exporter debris, not a skinned model: 18 of the
        // consumer's 41 models carry one. Rejecting on `document.skins.isNotEmpty()` rather than
        // on `node.skin != null` would refuse all 18 for dead data.
        assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(documentWithUnreferencedSkin()))
        // ... including one whose rig no uniform block could hold, since it deforms nothing.
        assertEquals(
            GltfFeatureResult.Supported,
            validateGltfFeatures(documentWithUnreferencedSkin(joints = MAXIMUM_SKIN_JOINTS + 1)),
        )
    }

    @Test
    fun aSkinnedInfluenceAttributeInAFormatRenGDoesNotBindIsRejected() {
        // The influence set is the one attribute pair RenG *does* read on a skinned mesh, so the
        // per-role table covers it: JOINTS_0 is an unnormalized unsigned VEC4 index, and WEIGHTS_0
        // is a VEC4 float or a normalized unsigned one. A float joint index addresses no joint.
        assertEquals(GltfUnsupported.ATTRIBUTE_FORMAT, unsupported(floatJointIndices))
        assertEquals(GltfUnsupported.ATTRIBUTE_FORMAT, unsupported(unnormalizedByteWeights))
    }

    @Test
    fun anInverseBindMatrixAccessorRenGCannotReadIsRejected() {
        // One MAT4 float per joint, or nothing at all -- an absent accessor is the specification's
        // own identity default and stays supported.
        assertEquals(GltfUnsupported.SKIN_ACCESSOR_FORMAT, unsupported(vec4InverseBindMatrices))
        assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(mat4InverseBindMatrices.document))
    }

    @Test
    fun aSamplerFilterOutsideTheGlEnumerationIsRejected() {
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.SAMPLER_STATE),
            validateGltfFeatures(documentWithSampler(minFilter = 1234)),
        )
        // The rule names four fields, so all four are pinned: a magFilter the enumeration has no
        // mipmapped form of, and a wrap mode outside the three the specification defines.
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.SAMPLER_STATE),
            validateGltfFeatures(documentWithSampler(magFilter = 9987)),
        )
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.SAMPLER_STATE),
            validateGltfFeatures(documentWithSampler(wrapS = 1234)),
        )
        assertEquals(
            GltfFeatureResult.Unsupported(GltfUnsupported.SAMPLER_STATE),
            validateGltfFeatures(documentWithSampler(wrapT = 1234)),
        )
        // What all 47 samplers in the consumer's corpus actually ask for, plus the sampler that
        // declares nothing at all: both stay supported.
        assertEquals(
            GltfFeatureResult.Supported,
            validateGltfFeatures(
                documentWithSampler(magFilter = 9729, minFilter = 9987, wrapS = 10497, wrapT = 10497),
            ),
        )
        assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(documentWithSampler()))
    }

    // ---- reject fixtures ----

    // Same shape as GltfParseTest's dracoShapedDocument: extensionsRequired names a compression
    // extension and the one accessor has no bufferView. PARSE_GLB accepts it; this gate must
    // report the required extension, not the accessor shape that extension happens to produce.
    private val dracoShapedDocument = """
        {
          "asset": {"version": "2.0"},
          "extensionsRequired": ["KHR_draco_mesh_compression"],
          "accessors": [
            {"componentType": 5126, "count": 24, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val accessorWithoutBufferViewNoExtension = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "accessors": [
            {"componentType": 5126, "count": 3, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val sparseAccessor = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [
            {
              "bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3",
              "sparse": {"count": 1, "indices": {}, "values": {}}
            }
          ]
        }
    """.trimIndent()

    private val triangleStrip = primitiveWithMode(5)
    private val pointsMode = primitiveWithMode(0)

    private fun primitiveWithMode(mode: Int) = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": $mode}]}]
        }
    """.trimIndent()

    private val customAttribute = primitiveWithAttribute("_CUSTOM")

    // TEXCOORD_01 is not a glTF semantic at all: the specification spells a set index as a
    // canonical non-negative integer, so a leading zero names nothing. It stays rejected even
    // though TEXCOORD_1 is now admitted and ignored.
    private val nonCanonicalTexcoordIndex = primitiveWithAttribute("TEXCOORD_01")

    private fun primitiveWithAttribute(semantic: String) = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "$semantic": 0}, "mode": 4}]}]
        }
    """.trimIndent()

    private val documentWithMorphTargets = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": 4, "targets": [{}]}]}]
        }
    """.trimIndent()

    private val weightsChannel = animationChannel(path = "weights", interpolation = "LINEAR")
    private val cubicSplineSampler = animationChannel(path = "translation", interpolation = "CUBICSPLINE")

    // CUBICSPLINE stores an in-tangent, a value and an out-tangent per keyframe, so its output
    // count is three times its input count. The fixture honours that rather than declaring one
    // value per keyframe: a sampler whose counts disagree is malformed (PARSE_GLB's
    // ANIMATION_SAMPLER_COUNTS), and this fixture must reach *this* gate to be refused for the
    // interpolation it uses -- which is the whole of ADR 0021's split.
    private fun animationChannel(path: String, interpolation: String) = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100}
          ],
          "nodes": [{}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "SCALAR"},
            {
              "bufferView": 1, "byteOffset": 0, "componentType": 5126, "type": "VEC3",
              "count": ${if (interpolation == "CUBICSPLINE") 3 else 1}
            }
          ],
          "animations": [
            {
              "channels": [{"sampler": 0, "target": {"node": 0, "path": "$path"}}],
              "samplers": [{"input": 0, "output": 1, "interpolation": "$interpolation"}]
            }
          ]
        }
    """.trimIndent()

    private val jpegImage = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "images": [{"mimeType": "image/jpeg"}]
        }
    """.trimIndent()

    private val imageWithUri = """
        {
          "asset": {"version": "2.0"},
          "images": [{"uri": "external-texture.png"}]
        }
    """.trimIndent()

    private val bufferWithDataUri = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 0, "uri": "data:application/octet-stream;base64,AAAA"}]
        }
    """.trimIndent()

    private val secondBufferDeclared = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 0}, {"byteLength": 0}]
        }
    """.trimIndent()

    private val twoScenesNoDefault = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}, {"nodes": []}]
        }
    """.trimIndent()

    private val zeroScenesNoDefault = """
        {"asset": {"version": "2.0"}}
    """.trimIndent()

    private val normalizedFloatAccessor = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [
            {
              "bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3",
              "normalized": true
            }
          ]
        }
    """.trimIndent()

    // ---- accept fixtures ----

    private val triangleIndexed = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR"}
          ],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "mode": 4}]}],
          "nodes": [{"mesh": 0}],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent()

    private val triangleNonIndexed = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": 4}]}],
          "nodes": [{"mesh": 0}],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent()

    private val nodeMatrixOnly = """
        {
          "asset": {"version": "2.0"},
          "nodes": [{"matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]}],
          "scenes": [{"nodes": []}]
        }
    """.trimIndent()

    private val nodeTrsOnly = """
        {
          "asset": {"version": "2.0"},
          "nodes": [{"translation": [1,2,3], "rotation": [0,0,0,1], "scale": [1,1,1]}],
          "scenes": [{"nodes": []}]
        }
    """.trimIndent()

    private val tangentAndColourZero = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100},
            {"buffer": 0, "byteOffset": 200, "byteLength": 100}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC4"},
            {"bufferView": 2, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC4"}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0, "TANGENT": 1, "COLOR_0": 2}, "mode": 4}]}
          ]
        }
    """.trimIndent()

    private val sceneAbsentWithExactlyOneScene = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}]
        }
    """.trimIndent()

    // Same interleaved shape as GltfParseTest's accessorInterleavedStride, at three vertices
    // rather than two: a non-indexed TRIANGLES primitive draws whole triangles, so its vertex
    // count must be a multiple of three (GltfReject.TRIANGLE_VERTEX_COUNT). Three vertices at
    // stride 24 span 12 + 2 * 24 + 12 = 72 bytes for the second accessor, so the view is 72.
    private val interleavedStride = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 72, "byteStride": 24}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 0, "byteOffset": 12, "componentType": 5126, "count": 3, "type": "VEC3"}
          ],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}, "mode": 4}]}]
        }
    """.trimIndent()

    // Accessor 2 exists because a `rotation` channel's output is a VEC4 quaternion: reusing the
    // VEC3 translation output for it is GltfUnsupported.ANIMATION_ACCESSOR_FORMAT.
    private val linearAndStepAnimation = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100},
            {"buffer": 0, "byteOffset": 200, "byteLength": 100}
          ],
          "nodes": [{}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "SCALAR"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"},
            {"bufferView": 2, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC4"}
          ],
          "animations": [
            {
              "channels": [
                {"sampler": 0, "target": {"node": 0, "path": "translation"}},
                {"sampler": 1, "target": {"node": 0, "path": "rotation"}}
              ],
              "samplers": [
                {"input": 0, "output": 1, "interpolation": "LINEAR"},
                {"input": 0, "output": 2, "interpolation": "STEP"}
              ]
            }
          ]
        }
    """.trimIndent()

    private val embeddedPngImage = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "images": [{"bufferView": 0, "mimeType": "image/png"}]
        }
    """.trimIndent()

    private val fullMaterialBlock = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "images": [{"bufferView": 0, "mimeType": "image/png"}],
          "samplers": [{}],
          "textures": [{"source": 0, "sampler": 0}],
          "materials": [
            {
              "pbrMetallicRoughness": {
                "baseColorFactor": [1, 1, 1, 1],
                "baseColorTexture": {"index": 0, "texCoord": 0},
                "metallicFactor": 1.0,
                "roughnessFactor": 1.0,
                "metallicRoughnessTexture": {"index": 0, "texCoord": 0}
              },
              "normalTexture": {"index": 0},
              "occlusionTexture": {"index": 0},
              "emissiveTexture": {"index": 0},
              "emissiveFactor": [0, 0, 0],
              "alphaMode": "BLEND",
              "alphaCutoff": 0.5,
              "doubleSided": true
            }
          ]
        }
    """.trimIndent()

    private val cameraAndLightIgnored = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "cameras": [{"type": "perspective"}],
          "nodes": [{"camera": 0}],
          "extensions": {"KHR_lights_punctual": {"lights": [{"type": "directional"}]}}
        }
    """.trimIndent()

    private val extensionsUsedWithoutRequired = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "extensionsUsed": ["KHR_materials_unlit"]
        }
    """.trimIndent()

    // ---- F-2 hardening fixtures ----
    //
    // All of these share one 4096-byte buffer split into four 1024-byte views, so every accessor
    // below fits regardless of its component type and the only thing under test in each fixture
    // is the format it declares.

    /** A single-scene document over the shared four-view buffer, wrapped around [accessors] and
     * a [tail] carrying whatever meshes, nodes or animations the fixture needs. */
    private fun overSharedBuffer(accessors: String, tail: String, extensionsRequired: String = "") = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}]$extensionsRequired,
          "buffers": [{"byteLength": 4096}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 1024},
            {"buffer": 0, "byteOffset": 1024, "byteLength": 1024},
            {"buffer": 0, "byteOffset": 2048, "byteLength": 1024},
            {"buffer": 0, "byteOffset": 3072, "byteLength": 1024}
          ],
          "accessors": [$accessors]$tail
        }
    """.trimIndent()

    /** One accessor declaration over view [view], at three elements -- a whole triangle, so
     * PARSE_GLB's own TRIANGLES vertex-count rule never fires in a fixture aimed at this gate. */
    private fun accessor(view: Int, componentType: Int, type: String, normalized: Boolean = false) =
        """{"bufferView": $view, "byteOffset": 0, "componentType": $componentType, "type": "$type",
            "count": 3, "normalized": $normalized}"""

    /** A single non-indexed TRIANGLES primitive naming [attributes] (semantic to accessor index). */
    private fun trianglesTail(attributes: String) =
        ""","meshes": [{"primitives": [{"attributes": {$attributes}, "mode": 4}]}]"""

    /** One accessor declaration minus its `bufferView` member, which
     * [documentWithPrimitiveAttributes] supplies per attribute so no two attributes share a view.
     * Three elements -- a whole triangle, so PARSE_GLB's own TRIANGLES vertex-count rule never
     * fires in a fixture aimed at this gate. */
    private fun accessor(type: String, componentType: Int, normalized: Boolean = false) =
        """"byteOffset": 0, "componentType": $componentType, "type": "$type",
            "count": 3, "normalized": $normalized"""

    private fun vec2Float() = accessor(type = "VEC2", componentType = 5126)

    private fun vec3Float() = accessor(type = "VEC3", componentType = 5126)

    private fun vec4Float() = accessor(type = "VEC4", componentType = 5126)

    /** A single non-indexed TRIANGLES primitive whose attributes are exactly [attributes], each
     * over its own view of the shared four. Returns the parsed document rather than its JSON,
     * because every caller passes it straight to [validateGltfFeatures]. */
    private fun documentWithPrimitiveAttributes(vararg attributes: Pair<String, String>): GltfDocument {
        require(attributes.size <= 4) { "overSharedBuffer declares exactly four buffer views" }
        val accessors = attributes.mapIndexed { view, (_, declaration) ->
            """{"bufferView": $view, $declaration}"""
        }
        val names = attributes.mapIndexed { view, (semantic, _) -> """"$semantic": $view""" }
        return overSharedBuffer(
            accessors = accessors.joinToString(",\n"),
            tail = trianglesTail(names.joinToString(", ")),
        ).document
    }

    /** One embedded PNG bound as a material's base colour, varying only the coordinate set that
     * material names. */
    private fun documentWithBaseColourTexture(texCoord: Int): GltfDocument = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "images": [{"bufferView": 0, "mimeType": "image/png"}],
          "samplers": [{}],
          "textures": [{"source": 0, "sampler": 0}],
          "materials": [
            {"pbrMetallicRoughness": {"baseColorTexture": {"index": 0, "texCoord": $texCoord}}}
          ]
        }
    """.trimIndent().document

    private val primitiveWithoutPosition = overSharedBuffer(
        accessors = accessor(view = 0, componentType = 5126, type = "VEC3"),
        tail = trianglesTail(""""NORMAL": 0"""),
    )

    private val scalarBytePosition = overSharedBuffer(
        accessors = accessor(view = 0, componentType = 5120, type = "SCALAR"),
        tail = trianglesTail(""""POSITION": 0"""),
    )

    private val mat4TexCoordZero = positionPlus(accessor(view = 1, componentType = 5126, type = "MAT4"), "TEXCOORD_0")

    private val unnormalizedUnsignedByteTexCoordZero =
        positionPlus(accessor(view = 1, componentType = 5121, type = "VEC2"), "TEXCOORD_0")

    private val vec3Tangent = positionPlus(accessor(view = 1, componentType = 5126, type = "VEC3"), "TANGENT")

    private val normalizedUnsignedByteTexCoordZero = positionPlus(
        accessor(view = 1, componentType = 5121, type = "VEC2", normalized = true),
        "TEXCOORD_0",
    )

    private val normalizedUnsignedShortColourZero = positionPlus(
        accessor(view = 1, componentType = 5123, type = "VEC3", normalized = true),
        "COLOR_0",
    )

    /** A well-formed VEC3-float POSITION plus one more attribute, so each fixture varies exactly
     * the secondary attribute's format. */
    private fun positionPlus(secondAccessor: String, semantic: String) = overSharedBuffer(
        accessors = accessor(view = 0, componentType = 5126, type = "VEC3") + ",\n" + secondAccessor,
        tail = trianglesTail(""""POSITION": 0, "$semantic": 1"""),
    )

    // A short POSITION is legal glTF *given* KHR_mesh_quantization -- which is exactly why this
    // document must report the extension rather than the attribute format.
    private val quantizedPositionWithExtension = overSharedBuffer(
        accessors = accessor(view = 0, componentType = 5122, type = "VEC3", normalized = true),
        tail = trianglesTail(""""POSITION": 0"""),
        extensionsRequired = ""","extensionsRequired": ["KHR_mesh_quantization"]""",
    )

    private val rotationOutputAsVec3 = animation(
        path = "rotation",
        input = accessor(view = 0, componentType = 5126, type = "SCALAR"),
        output = accessor(view = 1, componentType = 5126, type = "VEC3"),
    )

    private val translationOutputAsVec4 = animation(
        path = "translation",
        input = accessor(view = 0, componentType = 5126, type = "SCALAR"),
        output = accessor(view = 1, componentType = 5126, type = "VEC4"),
    )

    private val samplerInputAsVec3 = animation(
        path = "translation",
        input = accessor(view = 0, componentType = 5126, type = "VEC3"),
        output = accessor(view = 1, componentType = 5126, type = "VEC3"),
    )

    private val samplerInputAsUnsignedShort = animation(
        path = "translation",
        input = accessor(view = 0, componentType = 5123, type = "SCALAR"),
        output = accessor(view = 1, componentType = 5126, type = "VEC3"),
    )

    private val normalizedShortRotationOutput = animation(
        path = "rotation",
        input = accessor(view = 0, componentType = 5126, type = "SCALAR"),
        output = accessor(view = 1, componentType = 5122, type = "VEC4", normalized = true),
    )

    /** One node driven by one `LINEAR` channel on [path], varying only the sampler's [input] and
     * [output] accessor formats. */
    private fun animation(path: String, input: String, output: String) = overSharedBuffer(
        accessors = "$input,\n$output",
        tail = """
            ,"nodes": [{"translation": [0, 0, 0]}],
            "animations": [
              {
                "channels": [{"sampler": 0, "target": {"node": 0, "path": "$path"}}],
                "samplers": [{"input": 0, "output": 1, "interpolation": "LINEAR"}]
              }
            ]
        """.trimIndent(),
    )

    // ---- F-2 skin and sampler fixtures ----

    /** The accessor format the specification gives [semantic]'s role, so a fixture varying one
     * attribute list still declares every attribute in a form RenG binds. */
    private fun attributeFormat(semantic: String): String = when {
        semantic == "POSITION" || semantic == "NORMAL" -> """"componentType": 5126, "type": "VEC3""""
        semantic.startsWith("JOINTS_") -> """"componentType": 5121, "type": "VEC4""""
        semantic.startsWith("WEIGHTS_") -> """"componentType": 5126, "type": "VEC4""""
        else -> error("no format known for $semantic")
    }

    /** Node 0 draws mesh 0 and references skin 0, whose joints are the [joints] nodes after it.
     * The primitive carries [attributes] when given, and otherwise `POSITION` plus [influenceSets]
     * complete influence sets. Each attribute gets its own 256-byte view of one 4096-byte buffer,
     * which is [FIXTURE_BIN_CHUNK_LENGTH] exactly. */
    private fun skinnedDocument(
        joints: Int = 3,
        influenceSets: Int = 1,
        attributes: List<String>? = null,
    ): GltfDocument {
        val semantics = attributes
            ?: listOf("POSITION") + (0 until influenceSets).flatMap { listOf("JOINTS_$it", "WEIGHTS_$it") }
        val views = semantics.indices.joinToString(", ") {
            """{"buffer": 0, "byteOffset": ${it * 256}, "byteLength": 256}"""
        }
        val accessors = semantics.mapIndexed { view, semantic ->
            """{"bufferView": $view, "byteOffset": 0, "count": 3, ${attributeFormat(semantic)}}"""
        }
        val names = semantics.mapIndexed { view, semantic -> """"$semantic": $view""" }
        return """
            {
              "asset": {"version": "2.0"},
              "buffers": [{"byteLength": 4096}],
              "bufferViews": [$views],
              "accessors": [${accessors.joinToString(", ")}],
              "meshes": [{"primitives": [{"attributes": {${names.joinToString(", ")}}, "mode": 4}]}],
              "nodes": [{"mesh": 0, "skin": 0}${jointNodes(joints)}],
              "skins": [{"joints": [${(1..joints).joinToString(", ")}]}],
              "scene": 0,
              "scenes": [{"nodes": [0]}]
            }
        """.trimIndent().document
    }

    /** [joints] transform-only nodes, as the joint hierarchy of whichever skin names them. */
    private fun jointNodes(joints: Int) = (1..joints).joinToString("") { ", {}" }

    /** A drawable mesh under a node with no `skin`, plus a [joints]-joint skin nothing references
     * -- the exporter debris 18 of the consumer's 41 models carry. */
    private fun documentWithUnreferencedSkin(joints: Int = 1): GltfDocument = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 512}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": 4}]}],
          "nodes": [{"mesh": 0}${jointNodes(joints)}],
          "skins": [{"joints": [${(1..joints).joinToString(", ")}]}],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent().document

    private val floatJointIndices = skinnedPrimitive(
        """{"bufferView": 1, "byteOffset": 0, "count": 3, "componentType": 5126, "type": "VEC4"}""",
        """{"bufferView": 2, "byteOffset": 0, "count": 3, "componentType": 5126, "type": "VEC4"}""",
    )

    private val unnormalizedByteWeights = skinnedPrimitive(
        """{"bufferView": 1, "byteOffset": 0, "count": 3, "componentType": 5121, "type": "VEC4"}""",
        """{"bufferView": 2, "byteOffset": 0, "count": 3, "componentType": 5121, "type": "VEC4"}""",
    )

    /** One skinned primitive whose `POSITION` is well-formed, varying only the influence pair. */
    private fun skinnedPrimitive(jointsAccessor: String, weightsAccessor: String) = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 4096}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 256},
            {"buffer": 0, "byteOffset": 256, "byteLength": 256},
            {"buffer": 0, "byteOffset": 512, "byteLength": 256}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "count": 3, "componentType": 5126, "type": "VEC3"},
            $jointsAccessor,
            $weightsAccessor
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2}, "mode": 4}]}
          ],
          "nodes": [{"mesh": 0, "skin": 0}, {}],
          "skins": [{"joints": [1]}],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent()

    private val vec4InverseBindMatrices = inverseBindMatrices(""""componentType": 5126, "type": "VEC4"""")

    private val mat4InverseBindMatrices = inverseBindMatrices(""""componentType": 5126, "type": "MAT4"""")

    /** One skinned node whose skin names an inverse-bind-matrix accessor, varying only that
     * accessor's format. */
    private fun inverseBindMatrices(format: String) = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 4096}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 256},
            {"buffer": 0, "byteOffset": 256, "byteLength": 256},
            {"buffer": 0, "byteOffset": 512, "byteLength": 256},
            {"buffer": 0, "byteOffset": 768, "byteLength": 256}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "count": 3, "componentType": 5126, "type": "VEC3"},
            {"bufferView": 1, "byteOffset": 0, "count": 3, "componentType": 5121, "type": "VEC4"},
            {"bufferView": 2, "byteOffset": 0, "count": 3, "componentType": 5126, "type": "VEC4"},
            {"bufferView": 3, "byteOffset": 0, "count": 1, $format}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2}, "mode": 4}]}
          ],
          "nodes": [{"mesh": 0, "skin": 0}, {}],
          "skins": [{"joints": [1], "inverseBindMatrices": 3}],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent()

    /** One PNG behind one texture, varying only the sampler state that texture is read through.
     * An omitted member is genuinely absent, which the specification permits for both filters. */
    private fun documentWithSampler(
        magFilter: Int? = null,
        minFilter: Int? = null,
        wrapS: Int? = null,
        wrapT: Int? = null,
    ): GltfDocument {
        val members = listOfNotNull(
            magFilter?.let { """"magFilter": $it""" },
            minFilter?.let { """"minFilter": $it""" },
            wrapS?.let { """"wrapS": $it""" },
            wrapT?.let { """"wrapT": $it""" },
        ).joinToString(", ")
        return """
            {
              "asset": {"version": "2.0"},
              "scenes": [{"nodes": []}],
              "buffers": [{"byteLength": 1024}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
              "images": [{"bufferView": 0, "mimeType": "image/png"}],
              "samplers": [{$members}],
              "textures": [{"source": 0, "sampler": 0}]
            }
        """.trimIndent().document
    }

    // ---- fixture-name plumbing ----

    private fun parse(json: String): GltfParseResult =
        parseGltf(obj(json), binChunkLength = FIXTURE_BIN_CHUNK_LENGTH, maximumNodeDepth = 128)

    private val String.document: GltfDocument
        get() = assertIs<GltfParseResult.Parsed>(parse(this)).document

    private fun unsupported(json: String): GltfUnsupported =
        assertIs<GltfFeatureResult.Unsupported>(validateGltfFeatures(json.document)).reason

    private fun obj(text: String): JsonValue.Obj {
        val bytes = text.encodeToByteArray()
        val parsed = assertIs<JsonParse.Parsed>(parseJson(bytes, 0, bytes.size, 64))
        return parsed.value as JsonValue.Obj
    }
}
