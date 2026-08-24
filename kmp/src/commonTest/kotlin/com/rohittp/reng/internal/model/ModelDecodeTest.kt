package com.rohittp.reng.internal.model

import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.gl.GL_LINEAR
import com.rohittp.reng.internal.gl.GL_REPEAT
import com.rohittp.reng.internal.gl.GL_UNSIGNED_SHORT
import com.rohittp.reng.internal.gl.TextureSamplerState
import com.rohittp.reng.internal.gl.gltfSamplerState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every fixture here is a GLB assembled byte by byte -- header, JSON chunk, BIN chunk -- because
 * RenG owns a decoder and no encoder, so there is nothing to write one with. The geometry is
 * deliberately asymmetric everywhere it could be symmetric: positions ascend `1..9` rather than
 * repeating, the index run is `0, 2, 1` rather than `0, 1, 2`, the sampler's four enums are four
 * different values, and an inverse bind matrix holds `1..16` rather than an identity -- so a
 * transposed, reversed or mis-strided read fails instead of passing by coincidence.
 *
 * The one embedded PNG is a real 2x1 RGBA file generated once by CPython, following the same
 * anti-circularity rule `PngDecoderTest` states: the expectation must not be produced by the code
 * under test. Regenerate with:
 *
 *     python3 - <<'PY'
 *     import zlib, struct
 *     def chunk(kind, payload):
 *         return (struct.pack(">I", len(payload)) + kind + payload +
 *                 struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
 *     raw = b"\x00" + bytes([0x10, 0x20, 0x30, 0xFF, 0x40, 0x50, 0x60, 0x80])
 *     print(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 6, 0, 0, 0)) +
 *           chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
 *     PY
 */
class ModelDecodeTest {
    @Test
    fun everyMeshBearingNodeOfTheDefaultSceneBecomesADrawItem() {
        // Nodes 0 and 1 share mesh 0, node 2 is reached only as node 1's child, node 3 carries no
        // mesh, and node 4 carries mesh 1 but is outside the scene. Two nodes sharing one mesh are
        // two draw items over the same primitives, which is why a draw item names a node and a
        // primitive rather than a primitive alone.
        val model = decoded(sharedMeshGlb())

        assertEquals(
            listOf(Triple(0, 0, 0), Triple(1, 0, 0), Triple(2, 1, 0), Triple(2, 1, 1)),
            model.drawItems.map { Triple(it.nodeIndex, it.meshIndex, it.primitiveIndex) },
        )
        assertEquals(listOf(null, null, null, null), model.drawItems.map { it.skinIndex })
        // Three primitives, not four: a primitive is decoded once per (mesh, primitive), and the
        // two draw items over mesh 0 address the same one.
        assertEquals(3, model.primitives.size)
        assertEquals(model.primitives[0], model.primitiveFor(model.drawItems[0]))
        assertEquals(model.primitives[0], model.primitiveFor(model.drawItems[1]))
        assertEquals(model.primitives[2], model.primitiveFor(model.drawItems[3]))
    }

    @Test
    fun aPrimitiveWithNoMaterialTakesGltfsOwnDefaultMaterial() {
        // glTF defines it: white base colour, metallic 1, roughness 1, OPAQUE, single-sided.
        // RenG had no representation of it at all before this task.
        val model = decoded(sharedMeshGlb())
        val primitive = model.primitives[0]

        assertContentEquals(floatArrayOf(1f, 1f, 1f, 1f), primitive.material.baseColourFactor)
        assertEquals("OPAQUE", primitive.material.alphaMode)
        assertEquals(0.5f, primitive.material.alphaCutoff)
        assertFalse(primitive.material.doubleSided)
        assertNull(primitive.material.baseColourImageIndex)
    }

    @Test
    fun anAuthoredMaterialResolvesItsOwnFactorAlphaAndCullState() {
        // The other half of the test above: the default is a default, not the only answer.
        val material = decoded(sharedMeshGlb()).primitives[1].material

        assertContentEquals(floatArrayOf(0.125f, 0.25f, 0.5f, 0.75f), material.baseColourFactor)
        assertEquals("MASK", material.alphaMode)
        assertEquals(0.25f, material.alphaCutoff)
        assertTrue(material.doubleSided)
    }

    @Test
    fun aBaseColourTextureResolvesThroughMaterialTextureImageToADecodedImage() {
        // Four hops, all parsed and index-checked today and none of them executed:
        // material -> pbrMetallicRoughness.baseColorTexture -> textures[i].source -> images[j].bufferView.
        val model = decoded(texturedGlb(texture = """{"source": 0, "sampler": 0}"""))
        val primitive = model.primitives[0]

        assertEquals(0, primitive.material.baseColourImageIndex)
        assertEquals(1, model.images.size)
        assertEquals(2, model.images[0].width)
        assertEquals(1, model.images[0].height)
        assertContentEquals(
            byteArrayOf(0x10, 0x20, 0x30, -1, 0x40, 0x50, 0x60, -0x80),
            model.images[0].rgbaSnapshot(),
        )
        // Four different enums, so a transposed min/mag or S/T pair cannot pass.
        assertEquals(
            TextureSamplerState(minFilter = 9987, magFilter = 9728, wrapS = 33071, wrapT = 33648),
            primitive.material.baseColourSampler,
        )
    }

    @Test
    fun aTextureWithNoSamplerTakesTheSpecificationDefaults() {
        val model = decoded(texturedGlb(texture = """{"source": 0}""", samplers = ""))
        val sampler = model.primitives[0].material.baseColourSampler

        assertEquals(gltfSamplerState(null), sampler)
        assertEquals(TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT), sampler)
    }

    @Test
    fun aVec3ColourAttributeIsWidenedToVec4WithOpaqueAlpha() {
        val primitive = decoded(vec3ColourGlb()).primitives[0]

        assertContentEquals(
            floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 0.125f, 0.375f, 0.625f, 1f, 1f, 0.875f, 0.0625f, 1f),
            primitive.colours,
        )
    }

    @Test
    fun anUnindexedPrimitiveGetsAGeneratedIndexRun() {
        // One code path downstream, not two: every primitive draws with drawElements.
        val primitive = decoded(unindexedGlb()).primitives[0]

        assertContentEquals(shortArrayOf(0, 1, 2), primitive.indices.shorts)
        assertNull(primitive.indices.ints)
        assertEquals(GL_UNSIGNED_SHORT, primitive.indices.glComponentType)
        assertEquals(3, primitive.indices.count)
    }

    @Test
    fun anAuthoredIndexRunKeepsItsAuthoredOrder() {
        // The fixture's run is 0, 2, 1 precisely so a generated run cannot be mistaken for it.
        assertContentEquals(shortArrayOf(0, 2, 1), decoded(sharedMeshGlb()).primitives[0].indices.shorts)
    }

    @Test
    fun decodedCpuBytesCountsTheExpandedArraysRatherThanTheStoredOnes() {
        // De-interleaving and dequantizing costs up to four times the stored bytes, and
        // ResourceUsage.decodedCpuBytes is supposed to carry the expanded figure.
        val model = decoded(expandingGlb())

        // 36 stored position bytes stay 36; 6 normalized byte texture coordinates become 24;
        // 3 unsigned-byte indices become 6 widened shorts; 36 VEC3 colour bytes become 48 VEC4.
        val storedGeometryBytes = 36L + 6L + 3L + 36L
        assertEquals(36L + 24L + 6L + 48L, model.decodedCpuBytes)
        assertTrue(model.decodedCpuBytes > storedGeometryBytes)
    }

    @Test
    fun decodedCpuBytesCountsDecodedImagesAsWellAsGeometry() {
        val model = decoded(texturedGlb(texture = """{"source": 0, "sampler": 0}"""))

        // 36 position bytes, 6 index bytes, and one 2x1 RGBA raster of 8 bytes.
        assertEquals(36L + 6L + 8L, model.decodedCpuBytes)
    }

    @Test
    fun aModelWhoseExpandedFormExceedsTheDecodedImageBudgetIsTooLarge() {
        val bytes = expandingGlb()

        assertIs<ModelDecodeResult.TooLarge>(decodeModel(bytes, ResourceLimits(maximumDecodedImageBytes = 113L)))
        assertIs<ModelDecodeResult.Success>(decodeModel(bytes, ResourceLimits(maximumDecodedImageBytes = 114L)))
    }

    @Test
    fun aContainerFaultAParseFaultAndAFeatureFaultAreThreeDistinctResults() {
        // The driver's own gates map these to two different error codes, so a decode that collapses
        // them sends a consumer to the wrong fix.
        val draco = """"extensionsRequired": ["KHR_draco_mesh_compression"], """
        val container = sharedMeshGlb().also { it[0] = 'x'.code.toByte() }
        val parseFault = glb(minimalJson(accessorCount = 0), triangleBin())
        val featureFault = glb(minimalJson(extensionsRequired = draco), triangleBin())

        // The control first: without it, a fixture broken in some fourth way would read as whichever
        // of the three faults happens to be checked before the real one.
        assertIs<ModelDecodeResult.Success>(decodeModel(glb(minimalJson(), triangleBin()), ResourceLimits()))
        assertIs<ModelDecodeResult.Malformed>(decodeModel(container, ResourceLimits()))
        assertIs<ModelDecodeResult.Malformed>(decodeModel(parseFault, ResourceLimits()))
        assertIs<ModelDecodeResult.Unsupported>(decodeModel(featureFault, ResourceLimits()))
    }

    @Test
    fun aStridedSkinnedPrimitiveDeInterleavesIntoOnePackedArrayPerAttribute() {
        val model = decoded(skinnedGlb())
        val primitive = model.primitives[0]

        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f), primitive.positions)
        // Joint indices are counts, not fractions: joint 2 arrives as 2.0, never as 2/255.
        assertContentEquals(
            floatArrayOf(0f, 1f, 2f, 0f, 2f, 0f, 1f, 0f, 1f, 2f, 0f, 0f),
            primitive.joints,
        )
        assertContentEquals(
            floatArrayOf(0.5f, 0.25f, 0.25f, 0f, 0.125f, 0.5f, 0.375f, 0f, 1f, 0f, 0f, 0f),
            primitive.weights,
        )
        assertNull(primitive.normals)
        assertNull(primitive.texCoords)
        assertNull(primitive.colours)
        assertEquals(0, model.drawItems.single { it.nodeIndex == 0 }.skinIndex)
    }

    @Test
    fun aSkinCarriesItsJointNodesAndUntransposedInverseBindMatrices() {
        val skin = decoded(skinnedGlb()).skins.single()

        assertEquals(listOf(1, 2, 3), skin.jointNodes)
        assertEquals(1, skin.skeletonRoot)
        assertEquals(3, skin.inverseBindMatrices.size)
        // glTF stores a MAT4 column-major: values 1..16 put 2 at row 1 column 0 and 5 at row 0
        // column 1. Transposing the pair is the defect this asymmetry exists to catch.
        assertEquals(2.0, skin.inverseBindMatrices[0][1, 0])
        assertEquals(5.0, skin.inverseBindMatrices[0][0, 1])
        assertEquals(216.0, skin.inverseBindMatrices[2][3, 3])
    }

    @Test
    fun anUnreferencedSkinNeverFailsTheDecodeOverAnAccessorNoGateValidated() {
        // 18 of the consumer's 41 models carry a skins array no node references, and
        // validateGltfFeatures deliberately leaves those skins' accessors unchecked. Reading one
        // anyway would refuse a drawable model over exporter debris.
        val model = decoded(unreferencedSkinGlb())

        val skin = model.skins.single()
        assertEquals(listOf(1), skin.jointNodes)
        assertEquals(1, skin.inverseBindMatrices.size)
        assertEquals(1.0, skin.inverseBindMatrices[0][0, 0])
        assertEquals(0.0, skin.inverseBindMatrices[0][0, 1])
        assertNull(model.drawItems.first().skinIndex)
    }

    @Test
    fun theDeclaredSceneIsPreferredOverTheFirstOne() {
        val model = decoded(twoSceneGlb(declaredScene = 1))

        assertEquals(listOf(1), model.drawItems.map { it.nodeIndex })
    }

    @Test
    fun anIndexValueBeyondThePrimitivesVertexCountIsMalformed() {
        // GltfDocument's own documentation warns that a parsed document does not guarantee this:
        // parseGltf never sees the BIN chunk's bytes, so an undeclared max leaves it unproved.
        // Handing GL an out-of-range element index is undefined behaviour, so decode proves it.
        assertIs<ModelDecodeResult.Malformed>(decodeModel(outOfRangeIndexGlb(), ResourceLimits()))
    }

    @Test
    fun anAttributeWhoseCountDisagreesWithPositionIsMalformed() {
        // Separate packed arrays become separate vertex buffers, so a short NORMAL array would be
        // read past its end by the same vertex index that is legal for POSITION.
        assertIs<ModelDecodeResult.Malformed>(decodeModel(countMismatchGlb(), ResourceLimits()))
    }
}

// ---------------------------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------------------------

private fun decoded(bytes: ByteArray, limits: ResourceLimits = ResourceLimits()): DecodedModel =
    assertIs<ModelDecodeResult.Success>(decodeModel(bytes, limits)).model

private val ASSET = """"asset": {"version": "2.0"}"""

/** Three asymmetric vertices and an index run that is not the identity permutation. */
private fun triangleBin(): ByteArray = bin {
    f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
    u16(0, 2, 1)
}

private fun sharedMeshGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 42}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "materials": [{"alphaMode": "MASK", "alphaCutoff": 0.25, "doubleSided": true,
       "pbrMetallicRoughness": {"baseColorFactor": [0.125, 0.25, 0.5, 0.75]}}],
     "meshes": [
       {"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]},
       {"primitives": [
         {"attributes": {"POSITION": 0}, "indices": 1, "material": 0},
         {"attributes": {"POSITION": 0}, "indices": 1}]}],
     "nodes": [{"mesh": 0}, {"mesh": 0, "children": [2]}, {"mesh": 1}, {}, {"mesh": 1}],
     "scenes": [{"nodes": [0, 1, 3]}],
     "scene": 0}
    """.trimIndent(),
    triangleBin(),
)

/** A real 2x1 RGBA PNG; see this file's header for the script that produced it. */
private val PNG_2X1: ByteArray = byteArrayOf(
    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72,
    68, 82, 0, 0, 0, 2, 0, 0, 0, 1, 8, 6, 0, 0,
    0, -12, 34, 127, -118, 0, 0, 0, 17, 73, 68, 65, 84, 120,
    -38, 99, 16, 80, 48, -8, -17, 16, -112, -48, 0, 0, 10, -76,
    2, -48, 127, 48, -15, -17, 0, 0, 0, 0, 73, 69, 78, 68,
    -82, 66, 96, -126,
)

private fun texturedGlb(
    texture: String,
    samplers: String = """"samplers": [{"magFilter": 9728, "minFilter": 9987, "wrapS": 33071, "wrapT": 33648}],""",
): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 116}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6},
       {"buffer": 0, "byteOffset": 42, "byteLength": 74}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "images": [{"bufferView": 2, "mimeType": "image/png"}],
     $samplers
     "textures": [$texture],
     "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "material": 0}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        u16(0, 2, 1)
        raw(PNG_2X1)
    },
)

private fun vec3ColourGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 78}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 36},
       {"buffer": 0, "byteOffset": 72, "byteLength": 6}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "meshes": [{"primitives": [
       {"attributes": {"POSITION": 0, "COLOR_0": 1}, "indices": 2}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        f32(0.25f, 0.5f, 0.75f, 0.125f, 0.375f, 0.625f, 1f, 0.875f, 0.0625f)
        u16(0, 2, 1)
    },
)

private fun unindexedGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 36}],
     "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
     "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    bin { f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f) },
)

/** Every attribute this fixture carries expands on decode: a normalized byte texture coordinate to
 * a float, an unsigned-byte index to a widened short, and a VEC3 colour to a VEC4. */
private fun expandingGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 84}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6},
       {"buffer": 0, "byteOffset": 42, "byteLength": 3},
       {"buffer": 0, "byteOffset": 48, "byteLength": 36}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5121, "count": 3, "type": "VEC2", "normalized": true},
       {"bufferView": 2, "componentType": 5121, "count": 3, "type": "SCALAR"},
       {"bufferView": 3, "componentType": 5126, "count": 3, "type": "VEC3"}],
     "meshes": [{"primitives": [
       {"attributes": {"POSITION": 0, "TEXCOORD_0": 1, "COLOR_0": 3}, "indices": 2}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        u8(0, 255, 51, 204, 102, 153)
        u8(0, 2, 1)
        u8(0, 0, 0)
        f32(0.25f, 0.5f, 0.75f, 0.125f, 0.375f, 0.625f, 1f, 0.875f, 0.0625f)
    },
)

/** One interleaved buffer view at stride 32 -- position, four joint indices, four weights -- plus a
 * skin whose three inverse bind matrices are three different asymmetric matrices. */
private fun skinnedGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 296}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 96, "byteStride": 32},
       {"buffer": 0, "byteOffset": 96, "byteLength": 6},
       {"buffer": 0, "byteOffset": 104, "byteLength": 192}],
     "accessors": [
       {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 0, "byteOffset": 12, "componentType": 5121, "count": 3, "type": "VEC4"},
       {"bufferView": 0, "byteOffset": 16, "componentType": 5126, "count": 3, "type": "VEC4"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"},
       {"bufferView": 2, "componentType": 5126, "count": 3, "type": "MAT4"}],
     "skins": [{"joints": [1, 2, 3], "skeleton": 1, "inverseBindMatrices": 4}],
     "meshes": [{"primitives": [
       {"attributes": {"POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2}, "indices": 3}]}],
     "nodes": [{"mesh": 0, "skin": 0}, {}, {}, {}],
     "scenes": [{"nodes": [0, 1, 2, 3]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f)
        u8(0, 1, 2, 0)
        f32(0.5f, 0.25f, 0.25f, 0f)
        f32(4f, 5f, 6f)
        u8(2, 0, 1, 0)
        f32(0.125f, 0.5f, 0.375f, 0f)
        f32(7f, 8f, 9f)
        u8(1, 2, 0, 0)
        f32(1f, 0f, 0f, 0f)
        u16(0, 2, 1)
        u8(0, 0)
        for (matrix in 0..2) {
            for (element in 1..16) f32((matrix * 100 + element).toFloat())
        }
    },
)

/** A skins array no node references, whose inverse bind accessor is a `MAT2` no gate looked at --
 * `validateGltfFeatures` skips unreferenced skins by design. */
private fun unreferencedSkinGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 60}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6},
       {"buffer": 0, "byteOffset": 44, "byteLength": 16}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"},
       {"bufferView": 2, "componentType": 5126, "count": 1, "type": "MAT2"}],
     "skins": [{"joints": [1], "inverseBindMatrices": 2}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
     "nodes": [{"mesh": 0}, {}],
     "scenes": [{"nodes": [0, 1]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        u16(0, 2, 1)
        u8(0, 0)
        f32(9f, 9f, 9f, 9f)
    },
)

private fun twoSceneGlb(declaredScene: Int): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 42}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
     "nodes": [{"mesh": 0}, {"mesh": 0}],
     "scenes": [{"nodes": [0]}, {"nodes": [1]}],
     "scene": $declaredScene}
    """.trimIndent(),
    triangleBin(),
)

private fun outOfRangeIndexGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 42}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        // Vertex 5 does not exist, and no declared `max` gave parseGltf anything to catch it with.
        u16(0, 1, 5)
    },
)

private fun countMismatchGlb(): ByteArray = glb(
    """
    {$ASSET,
     "buffers": [{"byteLength": 66}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 24},
       {"buffer": 0, "byteOffset": 60, "byteLength": 6}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5126, "count": 2, "type": "VEC3"},
       {"bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "meshes": [{"primitives": [
       {"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 2}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        f32(0f, 0f, 1f, 0f, 1f, 0f)
        u16(0, 2, 1)
    },
)

/** The smallest document that draws, parameterised at the two points the three-distinct-results
 * test perturbs: an accessor `count` of zero is `PARSE_GLB` malformation, and a required extension
 * is a `VALIDATE_GLB_FEATURES` refusal. */
private fun minimalJson(accessorCount: Int = 3, extensionsRequired: String = ""): String =
    """
    {$ASSET, $extensionsRequired
     "buffers": [{"byteLength": 42}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": $accessorCount, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent()

// ---------------------------------------------------------------------------------------------
// A GLB assembler, because RenG owns a decoder and no encoder
// ---------------------------------------------------------------------------------------------

private const val GLB_MAGIC = 0x46546C67
private const val GLB_VERSION = 2
private const val JSON_CHUNK_TYPE = 0x4E4F534A
private const val BIN_CHUNK_TYPE = 0x004E4942
private const val JSON_PAD_BYTE = 0x20

/**
 * [json] and [binChunk] wrapped in a glTF 2.0 binary container: the 12-byte header, a JSON chunk
 * padded to a four-byte boundary with `0x20` (the only pad byte the specification permits there),
 * and -- when [binChunk] is non-empty -- a BIN chunk zero-padded the same way. The declared total
 * length is the real one, since `scanGlb` requires exact equality.
 */
private fun glb(json: String, binChunk: ByteArray = ByteArray(0)): ByteArray {
    val jsonBytes = json.encodeToByteArray()
    val jsonPadding = (4 - jsonBytes.size % 4) % 4
    val binPadding = (4 - binChunk.size % 4) % 4
    val binChunkBytes = if (binChunk.isEmpty()) 0 else 8 + binChunk.size + binPadding
    return bin {
        u32(GLB_MAGIC, GLB_VERSION, 12 + 8 + jsonBytes.size + jsonPadding + binChunkBytes)
        u32(jsonBytes.size + jsonPadding, JSON_CHUNK_TYPE)
        raw(jsonBytes)
        repeat(jsonPadding) { u8(JSON_PAD_BYTE) }
        if (binChunk.isNotEmpty()) {
            u32(binChunk.size + binPadding, BIN_CHUNK_TYPE)
            raw(binChunk)
            repeat(binPadding) { u8(0) }
        }
    }
}

/** Little-endian, because glTF fixes the BIN chunk's byte order as little-endian and every
 * published target is little-endian too (see `internal.gl.littleEndianBytes`). */
private class BinWriter {
    private val written = ArrayList<Byte>()

    fun u8(vararg values: Int) {
        values.forEach { written += (it and 0xFF).toByte() }
    }

    fun u16(vararg values: Int) {
        values.forEach { u8(it, it ushr 8) }
    }

    fun u32(vararg values: Int) {
        values.forEach { u8(it, it ushr 8, it ushr 16, it ushr 24) }
    }

    fun f32(vararg values: Float) {
        values.forEach { u32(it.toRawBits()) }
    }

    fun raw(bytes: ByteArray) {
        bytes.forEach { written += it }
    }

    fun build(): ByteArray = written.toByteArray()
}

private fun bin(write: BinWriter.() -> Unit): ByteArray = BinWriter().apply(write).build()
