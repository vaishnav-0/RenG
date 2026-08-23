package com.rohittp.reng.internal.glb

import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GltfParseTest {
    @Test
    fun toleratesAnAccessorWithNoBufferView() {
        // Fixture 41's shape: extensionsRequired names a compression extension and the
        // accessor has no bufferView. PARSE_GLB must accept; the feature gate rejects it.
        val parsed = assertIs<GltfParseResult.Parsed>(parse(dracoShapedDocument))
        assertNull(parsed.document.accessors[0].bufferView)
        assertEquals(listOf("KHR_draco_mesh_compression"), parsed.document.extensionsRequired)
    }

    @Test
    fun checksAccessorArithmeticInLongBeforeAllocating() {
        assertEquals(
            GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
            reject(accessorCountTwoPowForty, binChunkLength = 36L),
        )
        assertEquals(
            GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
            reject(accessorOffsetPastView, binChunkLength = 36L),
        )
        assertEquals(GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER, reject(bufferViewPastBuffer, binChunkLength = 36L))
        assertIs<GltfParseResult.Parsed>(parse(accessorFitsExactly, binChunkLength = 36L))
        assertIs<GltfParseResult.Parsed>(parse(accessorInterleavedStride, binChunkLength = 48L))
    }

    @Test
    fun rejectsAnAlphaModeOutsideTheSpecificationsOwnEnumeration() {
        // The specification types alphaMode as an enumeration of exactly three values, so a fourth
        // is a schema violation rather than an unsupported feature. It is checked at all because
        // the alternative is a silent repair: an unrecognised value used to fall through to the
        // OPAQUE default and render a transparent material solid, saying nothing.
        assertEquals(GltfReject.ALPHA_MODE, reject(materialJson(""""alphaMode": "TRANSPARENT", """)))
        assertEquals(GltfReject.ALPHA_MODE, reject(materialJson(""""alphaMode": "blend", """)))
        assertEquals(GltfReject.ALPHA_MODE, reject(materialJson(""""alphaMode": 2, """)))

        // Absent is not wrong: the specification's own default is OPAQUE.
        assertIs<GltfParseResult.Parsed>(parse(materialJson("")))
        for (mode in listOf("OPAQUE", "MASK", "BLEND")) {
            assertIs<GltfParseResult.Parsed>(parse(materialJson(""""alphaMode": "$mode", """)))
        }
    }

    @Test
    fun rejectsAContradictoryOrCyclicNodeGraph() {
        assertEquals(GltfReject.NODE_MATRIX_AND_TRS, reject(nodeWithMatrixAndTrs))
        assertEquals(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES, reject(nodeCycle))
        assertEquals(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES, reject(nodeWithTwoParents))
        assertEquals(
            GltfReject.NODE_DEPTH_EXCEEDED,
            reject(nodeChainJson(200), maximumNodeDepth = 128),
        )
    }

    @Test
    fun rejectsIndexReferencesOutOfRangeAndReservedIndexValues() {
        assertEquals(GltfReject.INDEX_OUT_OF_RANGE, reject(meshNamingMissingAccessor))
        assertEquals(
            GltfReject.INDEX_VALUE_OUT_OF_RANGE,
            reject(indexValueAboveVertexCount, binChunkLength = 42L),
        )
    }

    @Test
    fun rejectsDuplicateNonBlankAnimationNames() {
        assertEquals(GltfReject.DUPLICATE_ANIMATION_NAME, reject(twoAnimationsNamedWalk))
        // Absent and blank names are legal and addressable only by index.
        assertIs<GltfParseResult.Parsed>(parse(animationsWithBlankAndAbsentNames))
    }

    @Test
    fun readsIntegerFieldsOnlyFromIntegerSpelling() {
        // 1e2 is a JSON number but is not an index.
        assertEquals(
            GltfReject.NON_INTEGER_FIELD,
            reject(bufferViewIndexWrittenAsExponent, binChunkLength = 36L),
        )
    }

    @Test
    fun rejectsAnUnknownComponentTypeAsMalformedNotUnsupported() {
        // An unknown componentType has no known size, so accessor arithmetic is undecidable.
        assertEquals(GltfReject.COMPONENT_TYPE, reject(componentType9999))
    }

    @Test
    fun rejectsAnUnrecognizedAccessorTypeDistinctlyFromComponentType() {
        // "VEC5" is not one of the specification's type strings, so the component count -- and
        // so the element size -- is undecidable. This is ACCESSOR_TYPE, not COMPONENT_TYPE: the
        // two adjacent JSON fields (`type` and `componentType`) must not share a code, or a
        // consumer debugging by name inspects the wrong field.
        assertEquals(GltfReject.ACCESSOR_TYPE, reject(accessorTypeUnrecognized))
    }

    @Test
    fun rejectsAnIncoherentByteStrideDistinctlyFromASpanOverrun() {
        assertEquals(GltfReject.BYTE_STRIDE, reject(byteStrideBelowElementSize, binChunkLength = 36L))
        assertEquals(GltfReject.BYTE_STRIDE, reject(byteStrideNotMultipleOfComponentSize, binChunkLength = 40L))
    }

    @Test
    fun rejectsAnUnsupportedAssetVersion() {
        // Two separate rules -- major version and minVersion -- so one fixture each.
        assertEquals(GltfReject.ASSET_VERSION_UNSUPPORTED, reject(assetVersionWrongMajor))
        assertEquals(GltfReject.ASSET_VERSION_UNSUPPORTED, reject(assetMinVersionAboveTwoZero))
    }

    @Test
    fun rejectsAWrongTypedMinVersionRatherThanTreatingItAsAbsent() {
        // minVersion's safe cast previously failed silently for a non-string JSON value, falling
        // through to the same `return` an absent minVersion takes -- the sibling `version` field
        // already rejects the identical wrong-type case. A present-but-numeric minVersion must be
        // rejected, not treated as though the field were never there.
        assertEquals(GltfReject.ASSET_VERSION_UNSUPPORTED, reject(assetMinVersionWrongType))
    }

    @Test
    fun rejectsANodeCameraIndexOutOfRange() {
        // Regression: node.camera must be bounds-checked the same way node.skin already is.
        assertEquals(GltfReject.INDEX_OUT_OF_RANGE, reject(nodeWithCameraIndexOutOfRange))
    }

    @Test
    fun rejectsANegativeByteOffsetLengthOrStrideRatherThanShrinkingTheSpan() {
        // A JSON integer is signed, and every span check in this gate is a "does it fit"
        // comparison -- so a negative offset or length makes the computed span *smaller* and
        // sails through. Each of these parsed clean before the guard existed.
        assertEquals(
            GltfReject.SIZE_FIELD_OUT_OF_RANGE,
            reject(accessorNegativeByteOffset, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.SIZE_FIELD_OUT_OF_RANGE,
            reject(bufferViewNegativeByteOffset, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.SIZE_FIELD_OUT_OF_RANGE,
            reject(bufferViewNegativeByteLength, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.SIZE_FIELD_OUT_OF_RANGE,
            reject(bufferViewNegativeByteStride, binChunkLength = 1024L),
        )
        assertEquals(GltfReject.SIZE_FIELD_OUT_OF_RANGE, reject(bufferNegativeByteLength))
    }

    @Test
    fun rejectsAnAccessorCountBelowOne() {
        // The specification's own `count` schema carries `minimum: 1`. Zero also drives the
        // span check's `count - 1` negative, which shrinks the span into passing.
        assertEquals(GltfReject.SIZE_FIELD_OUT_OF_RANGE, reject(accessorZeroCount, binChunkLength = 1024L))
        assertEquals(
            GltfReject.SIZE_FIELD_OUT_OF_RANGE,
            reject(accessorNegativeCount, binChunkLength = 1024L),
        )
    }

    @Test
    fun refusesASpanWhoseArithmeticWouldOverflowRatherThanWrappingIntoAcceptance() {
        // Non-negativity alone is not enough: `byteOffset + (count - 1) * stride + elementSize`
        // overflows Long for large non-negative operands, and an overflowed sum is *negative*,
        // which passes the comparison it was supposed to fail. Both fixtures below are positive
        // in every field and were accepted before the span check stopped forming that sum.
        assertEquals(
            GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
            reject(accessorByteOffsetOverflowsTheSpanSum, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
            reject(accessorCountOverflowsTheStrideProduct, binChunkLength = 1024L),
        )
        // The same wrap one link up the chain: `byteOffset + byteLength` over a buffer view.
        assertEquals(
            GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER,
            reject(bufferViewOffsetAndLengthOverflowTheirSum, binChunkLength = 1024L),
        )
    }

    @Test
    fun rejectsAnIndicesAccessorThatIsNotScalarUnsignedAndUnnormalized() {
        // The specification types `indices` as SCALAR, one of the three unsigned component
        // types, and never normalized. No extension relaxes it, so this is malformation.
        assertEquals(
            GltfReject.INDICES_ACCESSOR_FORMAT,
            reject(indicesAccessorVec3, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.INDICES_ACCESSOR_FORMAT,
            reject(indicesAccessorFloat, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.INDICES_ACCESSOR_FORMAT,
            reject(indicesAccessorNormalized, binChunkLength = 1024L),
        )
    }

    @Test
    fun rejectsATriangleVertexCountThatIsNotAMultipleOfThree() {
        assertEquals(
            GltfReject.TRIANGLE_VERTEX_COUNT,
            reject(indexedTriangleCountOfFour, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.TRIANGLE_VERTEX_COUNT,
            reject(nonIndexedTriangleVertexCountOfFour, binChunkLength = 1024L),
        )
        // Only TRIANGLES is checked. A strip of four vertices is perfectly well-formed; that it
        // is outside RenG's subset is VALIDATE_GLB_FEATURES' statement, not this gate's.
        assertIs<GltfParseResult.Parsed>(parse(stripVertexCountOfFour, binChunkLength = 1024L))
    }

    @Test
    fun rejectsAMatrixTransformOnAnAnimatedNode() {
        // The specification forbids `matrix` on a node an animation channel targets: the channel
        // writes translation/rotation/scale and a baked matrix has none of them to write into.
        assertEquals(
            GltfReject.ANIMATED_NODE_MATRIX,
            reject(animatedNodeWithMatrix, binChunkLength = 1024L),
        )
        // An unanimated matrix node, and a channel with no target node at all (legal, and
        // specified as a no-op), both stay accepted.
        assertIs<GltfParseResult.Parsed>(parse(unanimatedNodeWithMatrix, binChunkLength = 1024L))
        assertIs<GltfParseResult.Parsed>(parse(channelWithNoTargetNode, binChunkLength = 1024L))
    }

    @Test
    fun rejectsAnAccessorBoundArrayThatDoesNotMatchTheComponentCount() {
        // `max`/`min` carry one value per component. An empty `max` is the dangerous shape: it
        // makes validateIndexValues' `firstOrNull()` return null, switching that content check
        // silently off rather than failing.
        assertEquals(
            GltfReject.ACCESSOR_BOUNDS_LENGTH,
            reject(indicesAccessorWithEmptyMax, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.ACCESSOR_BOUNDS_LENGTH,
            reject(positionAccessorWithTwoElementMin, binChunkLength = 1024L),
        )
        assertEquals(
            GltfReject.ACCESSOR_BOUNDS_LENGTH,
            reject(accessorWithScalarMax, binChunkLength = 1024L),
        )
    }

    @Test
    fun staysPermissiveAboutAPrimitiveWithNoPositionAttribute() {
        // The specification permits it -- a client "SHOULD skip" such a primitive, and an
        // extension may supply positions -- so PARSE_GLB must not call it malformed. The refusal
        // is GltfUnsupported.PRIMITIVE_WITHOUT_POSITION, one gate later.
        assertIs<GltfParseResult.Parsed>(parse(primitiveWithoutPosition, binChunkLength = 1024L))
    }

    // ---- F-2: skins, and the two structural rules owed alongside them ----

    @Test
    fun aSkinIsRetainedWithItsJointsAndInverseBindMatrices() {
        val parsed = assertIs<GltfParseResult.Parsed>(parse(skinnedDocument, binChunkLength = 4096L))
        val skin = parsed.document.skins.single()
        assertEquals(listOf(1, 2, 3), skin.joints)
        assertEquals(0, skin.inverseBindMatrices)
        assertEquals(1, skin.skeleton)
    }

    @Test
    fun aSkinJointNamingNoNodeIsMalformed() {
        // A joint is a node reference like any other, and an unresolvable reference is
        // malformation rather than a feature RenG declines.
        assertEquals(GltfReject.INDEX_OUT_OF_RANGE, reject(skinJointNamingNoNode, binChunkLength = 4096L))
        assertEquals(
            GltfReject.INDEX_OUT_OF_RANGE,
            reject(skinInverseBindMatricesNamingNoAccessor, binChunkLength = 4096L),
        )
    }

    @Test
    fun aSkinWithNoJointsIsMalformed() {
        // The specification's own `joints` schema carries `minItems: 1`, and a skin with no joint
        // deforms nothing -- the same class of fault SIZE_FIELD_OUT_OF_RANGE already names.
        assertEquals(GltfReject.SIZE_FIELD_OUT_OF_RANGE, reject(skinWithEmptyJoints, binChunkLength = 4096L))
        assertEquals(GltfReject.SIZE_FIELD_OUT_OF_RANGE, reject(skinWithNoJointsMember, binChunkLength = 4096L))
    }

    @Test
    fun anAnimationSamplerWhoseOutputCountDisagreesWithItsInputIsMalformed() {
        // input.count = 4 keyframe times, output.count = 3 values: undecidable. There is no
        // keyframe the surplus value belongs to and no value the missing keyframe reads.
        assertEquals(
            GltfReject.ANIMATION_SAMPLER_COUNTS,
            reject(samplerCounts(inputCount = 4, outputCount = 3), binChunkLength = 4096L),
        )
        assertIs<GltfParseResult.Parsed>(
            parse(samplerCounts(inputCount = 4, outputCount = 4), binChunkLength = 4096L),
        )
    }

    @Test
    fun readsACubicSplineSamplerAgainstItsOwnThreeValuesPerKeyframe() {
        // CUBICSPLINE stores an in-tangent, a value and an out-tangent per keyframe. RenG refuses
        // that interpolation, but PARSE_GLB must not report a legal cubic-spline asset as corrupt:
        // ADR 0021 gives the refusal to VALIDATE_GLB_FEATURES, which names the interpolation.
        assertIs<GltfParseResult.Parsed>(
            parse(
                samplerCounts(inputCount = 4, outputCount = 12, interpolation = "CUBICSPLINE"),
                binChunkLength = 4096L,
            ),
        )
        assertEquals(
            GltfReject.ANIMATION_SAMPLER_COUNTS,
            reject(
                samplerCounts(inputCount = 4, outputCount = 4, interpolation = "CUBICSPLINE"),
                binChunkLength = 4096L,
            ),
        )
    }

    @Test
    fun twoChannelsInOneAnimationDrivingTheSameNodeAndPathAreMalformed() {
        assertEquals(
            GltfReject.DUPLICATE_ANIMATION_CHANNEL_TARGET,
            reject(
                twoChannels("""{"node": 0, "path": "translation"}""", """{"node": 0, "path": "translation"}"""),
                binChunkLength = 4096L,
            ),
        )
        // One node driven on two different paths, two nodes driven on the same path, and two
        // channels naming no node at all are all ordinary documents: the rule is one target, not
        // one node, and a channel with no target node is specified as a no-op.
        for (pair in listOf(
            """{"node": 0, "path": "translation"}""" to """{"node": 0, "path": "scale"}""",
            """{"node": 0, "path": "translation"}""" to """{"node": 1, "path": "translation"}""",
            """{"path": "translation"}""" to """{"path": "translation"}""",
        )) {
            assertIs<GltfParseResult.Parsed>(parse(twoChannels(pair.first, pair.second), binChunkLength = 4096L))
        }
    }

    // ---- fixtures ----

    private val dracoShapedDocument = """
        {
          "asset": {"version": "2.0"},
          "extensionsRequired": ["KHR_draco_mesh_compression"],
          "accessors": [
            {"componentType": 5126, "count": 24, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    // A 36-byte bufferView backed by a 36-byte buffer -- Task 7's fixtures 36-40 use the same
    // "buffer=36" shape, kept here for the same reason: a single VEC3-float accessor (3 * 4 = 12
    // bytes per element) spans exactly 36 bytes across 3 elements.
    private val accessorCountTwoPowForty = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1099511627776, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val accessorOffsetPastView = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 1099511627776, "componentType": 5126, "count": 1, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val bufferViewPastBuffer = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 30, "byteLength": 10}]
        }
    """.trimIndent()

    private val accessorFitsExactly = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    // Two VEC3-float accessors (12 bytes each) interleaved at stride 24 across 2 vertices: the
    // first spans bytes [0, 36), the second [12, 48), exactly filling the 48-byte view.
    private val accessorInterleavedStride = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 48}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48, "byteStride": 24}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 2, "type": "VEC3"},
            {"bufferView": 0, "byteOffset": 12, "componentType": 5126, "count": 2, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val nodeWithMatrixAndTrs = """
        {
          "asset": {"version": "2.0"},
          "nodes": [
            {
              "matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1],
              "translation": [1,2,3]
            }
          ]
        }
    """.trimIndent()

    // Node 0's only child is node 1, and node 1's only child is node 0: a two-node cycle with no
    // node left over to serve as a root, so the walk from roots visits neither.
    private val nodeCycle = """
        {
          "asset": {"version": "2.0"},
          "nodes": [
            {"children": [1]},
            {"children": [0]}
          ]
        }
    """.trimIndent()

    // Nodes 0 and 1 both name node 2 as their child: node 2 has two parents.
    private val nodeWithTwoParents = """
        {
          "asset": {"version": "2.0"},
          "nodes": [
            {"children": [2]},
            {"children": [2]},
            {}
          ]
        }
    """.trimIndent()

    private val meshNamingMissingAccessor = """
        {
          "asset": {"version": "2.0"},
          "accessors": [
            {"componentType": 5126, "count": 3, "type": "VEC3"}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 5}}]}
          ]
        }
    """.trimIndent()

    // A 3-vertex POSITION accessor (accessor 0) and a 3-index SCALAR/unsigned-short indices
    // accessor (accessor 1) that declares max=[5] -- 5 is at or above the 3-vertex attribute
    // count, so the declared index value is out of range even though every reference and every
    // accessor/bufferView/buffer arithmetic check fits exactly.
    private val indexValueAboveVertexCount = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 42}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 36},
            {"buffer": 0, "byteOffset": 36, "byteLength": 6}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR", "max": [5]}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}
          ]
        }
    """.trimIndent()

    private val twoAnimationsNamedWalk = """
        {
          "asset": {"version": "2.0"},
          "animations": [
            {"name": "Walk", "channels": [], "samplers": []},
            {"name": "Walk", "channels": [], "samplers": []}
          ]
        }
    """.trimIndent()

    private val animationsWithBlankAndAbsentNames = """
        {
          "asset": {"version": "2.0"},
          "animations": [
            {"channels": [], "samplers": []},
            {"name": "   ", "channels": [], "samplers": []},
            {"name": "", "channels": [], "samplers": []}
          ]
        }
    """.trimIndent()

    private val bufferViewIndexWrittenAsExponent = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 1e2, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val componentType9999 = """
        {
          "asset": {"version": "2.0"},
          "accessors": [
            {"componentType": 9999, "count": 1, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val accessorTypeUnrecognized = """
        {
          "asset": {"version": "2.0"},
          "accessors": [
            {"componentType": 5126, "count": 1, "type": "VEC5"}
          ]
        }
    """.trimIndent()

    // byteStride (8) is below the VEC3-float element size (12).
    private val byteStrideBelowElementSize = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36, "byteStride": 8}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    // byteStride (14) is at or above the VEC3-float element size (12) but not a multiple of the
    // 4-byte component size.
    private val byteStrideNotMultipleOfComponentSize = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 40}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 40, "byteStride": 14}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 2, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val assetVersionWrongMajor = """
        {"asset": {"version": "3.0"}}
    """.trimIndent()

    private val assetMinVersionAboveTwoZero = """
        {"asset": {"version": "2.0", "minVersion": "2.1"}}
    """.trimIndent()

    // minVersion is present but spelled as a JSON number, not the required MAJOR.MINOR string.
    private val assetMinVersionWrongType = """
        {"asset": {"version": "2.0", "minVersion": 2.0}}
    """.trimIndent()

    // No "cameras" array is declared at all, so any camera index -- even 0 -- is out of range.
    private val nodeWithCameraIndexOutOfRange = """
        {
          "asset": {"version": "2.0"},
          "nodes": [{"camera": 3}]
        }
    """.trimIndent()

    /** A linear chain of [length] nodes, each the sole child of its predecessor: node 0 is the
     * only root, and its tree is exactly [length] nodes deep. */
    private fun nodeChainJson(length: Int): String {
        val nodes = (0 until length).joinToString(",\n") { index ->
            val children = if (index < length - 1) "[${index + 1}]" else "[]"
            """{"children": $children}"""
        }
        return """{"asset": {"version": "2.0"}, "nodes": [$nodes]}"""
    }

    // ---- F-2 hardening fixtures ----
    //
    // Every one of these sits on the same backing store -- a 1024-byte buffer with one 1024-byte
    // view (and, where indices are involved, a second) -- so that the only thing under test in
    // each fixture is the one field it perturbs.

    /** A document body wrapped around [accessors] and an optional [meshes]/[nodes]/[animations]
     * tail, over the shared 1024-byte buffer and its two views. */
    private fun overSharedBuffer(accessors: String, tail: String = "") = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 512},
            {"buffer": 0, "byteOffset": 512, "byteLength": 512}
          ],
          "accessors": [$accessors]$tail
        }
    """.trimIndent()

    // Reads 1000 bytes *before* the start of its buffer view. Before the guard: the span
    // evaluates to -1000 + 0 + 12 = -988, which is comfortably "within" the 512-byte view.
    private val accessorNegativeByteOffset =
        overSharedBuffer("""{"bufferView": 0, "byteOffset": -1000, "componentType": 5126, "count": 1, "type": "VEC3"}""")

    // The same fault one link down the chain, and the one an *image* walks into: parseImages
    // bounds-checks only the bufferView index, never its span, so before the guard a PNG sourced
    // from this view would be sliced from 1000 bytes before the BIN chunk begins.
    private val bufferViewNegativeByteOffset = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": -1000, "byteLength": 100}],
          "images": [{"bufferView": 0, "mimeType": "image/png"}]
        }
    """.trimIndent()

    private val bufferViewNegativeByteLength = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 30, "byteLength": -100}]
        }
    """.trimIndent()

    private val bufferViewNegativeByteStride = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 512, "byteStride": -12}]
        }
    """.trimIndent()

    // binChunkLength defaults to 0 here on purpose: a negative declared byteLength must be
    // refused on its own terms, not because it happens to exceed the BIN chunk.
    private val bufferNegativeByteLength = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": -1}]
        }
    """.trimIndent()

    private val accessorZeroCount =
        overSharedBuffer("""{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 0, "type": "VEC3"}""")

    private val accessorNegativeCount =
        overSharedBuffer("""{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": -1, "type": "VEC3"}""")

    // byteOffset is 2^63 - 8: positive, so non-negativity passes it, but `byteOffset + 12`
    // overflows to a large negative number that is trivially "within" a 512-byte view.
    private val accessorByteOffsetOverflowsTheSpanSum = overSharedBuffer(
        """{"bufferView": 0, "byteOffset": 9223372036854775800, "componentType": 5126, "count": 1, "type": "VEC3"}""",
    )

    // count is Long.MAX_VALUE and the tight stride is 12, so `(count - 1) * 12` wraps to exactly
    // -24 and the whole span evaluates to -12 -- accepted, before the check stopped forming it.
    private val accessorCountOverflowsTheStrideProduct = overSharedBuffer(
        """{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 9223372036854775807, "type": "VEC3"}""",
    )

    // Two positive halves of 2^62 each: their sum is exactly Long.MIN_VALUE, so the direct
    // comparison saw a hugely negative span sitting comfortably inside a 1024-byte buffer.
    private val bufferViewOffsetAndLengthOverflowTheirSum = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 4611686018427387904, "byteLength": 4611686018427387904}
          ]
        }
    """.trimIndent()

    private val indicesAccessorVec3 = indexedPrimitive(
        """{"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "VEC3"}""",
    )

    private val indicesAccessorFloat = indexedPrimitive(
        """{"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "SCALAR"}""",
    )

    private val indicesAccessorNormalized = indexedPrimitive(
        """
        {
          "bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR",
          "normalized": true
        }
        """.trimIndent(),
    )

    private val indexedTriangleCountOfFour = indexedPrimitive(
        """{"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 4, "type": "SCALAR"}""",
    )

    /** A three-vertex POSITION accessor plus [indicesAccessor], drawn as one indexed TRIANGLES
     * primitive. Only the indices accessor varies between the fixtures that use this. */
    private fun indexedPrimitive(indicesAccessor: String) = overSharedBuffer(
        accessors = """
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            $indicesAccessor
        """.trimIndent(),
        tail = ""","meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "mode": 4}]}]""",
    )

    private val nonIndexedTriangleVertexCountOfFour = nonIndexedPrimitiveOfFourVertices(mode = 4)
    private val stripVertexCountOfFour = nonIndexedPrimitiveOfFourVertices(mode = 5)

    private fun nonIndexedPrimitiveOfFourVertices(mode: Int) = overSharedBuffer(
        accessors = """{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 4, "type": "VEC3"}""",
        tail = ""","meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": $mode}]}]""",
    )

    private val animatedNodeWithMatrix = animatedDocument(
        node = """{"matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]}""",
        target = """{"node": 0, "path": "translation"}""",
    )

    private val unanimatedNodeWithMatrix = animatedDocument(
        node = """{"matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]}""",
        target = """{"path": "translation"}""",
    )

    private val channelWithNoTargetNode = animatedDocument(
        node = """{"translation": [0, 0, 0]}""",
        target = """{"path": "translation"}""",
    )

    /** One node and one animation channel over the shared buffer, varying only the node's
     * transform and the channel's target. */
    private fun animatedDocument(node: String, target: String) = overSharedBuffer(
        accessors = """
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "SCALAR"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"}
        """.trimIndent(),
        tail = """
            ,"nodes": [$node],
            "animations": [
              {
                "channels": [{"sampler": 0, "target": $target}],
                "samplers": [{"input": 0, "output": 1, "interpolation": "LINEAR"}]
              }
            ]
        """.trimIndent(),
    )

    private val indicesAccessorWithEmptyMax = indexedPrimitive(
        """{"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR", "max": []}""",
    )

    private val positionAccessorWithTwoElementMin = overSharedBuffer(
        """
        {
          "bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3",
          "min": [0, 0]
        }
        """.trimIndent(),
    )

    private val accessorWithScalarMax = overSharedBuffer(
        """{"bufferView": 0, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR", "max": 5}""",
    )

    private val primitiveWithoutPosition = overSharedBuffer(
        accessors = """{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}""",
        tail = ""","meshes": [{"primitives": [{"attributes": {"NORMAL": 0}, "mode": 4}]}]""",
    )

    // ---- F-2 fixtures ----

    /** Four nodes over one buffer: node 0 draws nothing and nodes 1..3 are available as joints.
     * Only the skin object itself varies between the fixtures below. */
    private fun documentWithSkin(skin: String) = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 4096}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 512}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "MAT4"}
          ],
          "nodes": [{}, {}, {}, {}],
          "skins": [$skin],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent()

    private val skinnedDocument =
        documentWithSkin("""{"inverseBindMatrices": 0, "joints": [1, 2, 3], "skeleton": 1}""")

    private val skinJointNamingNoNode = documentWithSkin("""{"joints": [1, 9]}""")

    private val skinInverseBindMatricesNamingNoAccessor =
        documentWithSkin("""{"inverseBindMatrices": 7, "joints": [1]}""")

    private val skinWithEmptyJoints = documentWithSkin("""{"joints": []}""")

    private val skinWithNoJointsMember = documentWithSkin("{}")

    /** One node driven by one channel, varying only the two accessor counts the sampler reads and
     * the interpolation that fixes the ratio between them. */
    private fun samplerCounts(inputCount: Int, outputCount: Int, interpolation: String = "LINEAR") = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 4096}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 512},
            {"buffer": 0, "byteOffset": 512, "byteLength": 512}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": $inputCount, "type": "SCALAR"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": $outputCount, "type": "VEC3"}
          ],
          "nodes": [{"translation": [0, 0, 0]}],
          "scene": 0,
          "scenes": [{"nodes": [0]}],
          "animations": [
            {
              "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}}],
              "samplers": [{"input": 0, "output": 1, "interpolation": "$interpolation"}]
            }
          ]
        }
    """.trimIndent()

    /** Two channels of one animation over two animatable nodes, varying only what each targets. */
    private fun twoChannels(firstTarget: String, secondTarget: String) = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 4096}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 512},
            {"buffer": 0, "byteOffset": 512, "byteLength": 512}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "SCALAR"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"}
          ],
          "nodes": [{"translation": [0, 0, 0]}, {"translation": [0, 0, 0]}],
          "scene": 0,
          "scenes": [{"nodes": [0, 1]}],
          "animations": [
            {
              "channels": [
                {"sampler": 0, "target": $firstTarget},
                {"sampler": 0, "target": $secondTarget}
              ],
              "samplers": [{"input": 0, "output": 1, "interpolation": "LINEAR"}]
            }
          ]
        }
    """.trimIndent()

    // ---- fixture-name plumbing ----

    private fun parse(json: String, binChunkLength: Long = 0L, maximumNodeDepth: Int = 128): GltfParseResult =
        parseGltf(obj(json), binChunkLength, maximumNodeDepth)

    /** A minimal document carrying exactly one material, with [alphaModeMember] spliced into it. */
    private fun materialJson(alphaModeMember: String): String = """
        {
          "asset": {"version": "2.0"},
          "materials": [{$alphaModeMember"doubleSided": true}],
          "scenes": [{"nodes": []}],
          "scene": 0
        }
    """.trimIndent()

    private fun reject(json: String, binChunkLength: Long = 0L, maximumNodeDepth: Int = 128): GltfReject =
        assertIs<GltfParseResult.Malformed>(parse(json, binChunkLength, maximumNodeDepth)).reason

    private fun obj(text: String): JsonValue.Obj {
        val bytes = text.encodeToByteArray()
        val parsed = assertIs<JsonParse.Parsed>(parseJson(bytes, 0, bytes.size, 64))
        return parsed.value as JsonValue.Obj
    }
}
