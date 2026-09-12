package com.rohittp.reng.internal.gl

import com.rohittp.reng.Camera
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeometryPipelineTest {

    @Test
    fun theReservedNamesAreExactlyTheSixDocumentedShaderNames() {
        assertEquals(
            setOf(
                "aPosition", "aTexCoord",
                "uModelViewProjection", "uResolution", "uGeometryBounds", "uFrameIndex",
            ),
            RESERVED_SHADER_NAMES,
        )
    }

    // --- ADR 0008: bind only when the compiled program declares the name ---------------------

    @Test
    fun aShaderDeclaringNoDocumentedNameStillCompilesAndDraws() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)

        drawGeometry(binding, pipeline, testGrid(), IDENTITY_4X4, 800f, 600f, testBounds(), frameIndex = 7L)

        assertTrue(binding.log.any { it.startsWith("drawElements") }, "it must still draw")
        assertTrue(binding.log.none { it.startsWith("uniform") }, "and set nothing it cannot set")
    }

    @Test
    fun creationSkipsAttributesTheProgramDoesNotDeclare() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)

        assertEquals(-1, pipeline.positionAttributeLocation)
        assertEquals(-1, pipeline.texCoordAttributeLocation)
        assertEquals(0, binding.log.count { it.startsWith("enableVertexAttribArray") })
    }

    @Test
    fun aShaderDeclaringOnlyFrameIndexGetsOnlyThatOneSet() {
        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
        val pipeline = createPipeline(binding)

        drawGeometry(binding, pipeline, testGrid(), IDENTITY_4X4, 800f, 600f, testBounds(), frameIndex = 7L)

        assertEquals(listOf("uniform1ui(4,7)"), binding.log.filter { it.startsWith("uniform") })
    }

    @Test
    fun allSixDocumentedNamesAreBoundWhenAllAreDeclared() {
        val binding = RecordingGlBinding().withDeclaredNames(
            ATTRIBUTE_POSITION to 0,
            ATTRIBUTE_TEXTURE_COORDINATE to 1,
            UNIFORM_MODEL_VIEW_PROJECTION to 2,
            UNIFORM_RESOLUTION to 3,
            UNIFORM_GEOMETRY_BOUNDS to 4,
            UNIFORM_FRAME_INDEX to 5,
        )
        val pipeline = createPipeline(binding)
        assertEquals(0, pipeline.positionAttributeLocation)
        assertEquals(1, pipeline.texCoordAttributeLocation)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testGrid(), IDENTITY_4X4,
            resolutionWidthPixels = 800f, resolutionHeightPixels = 600f,
            boundsWestSouthEastNorthDegrees = floatArrayOf(1f, 2f, 3f, 4f),
            frameIndex = 7L,
        )

        assertEquals(
            listOf(
                "uniformMatrix4fv(2,1,false)",
                "uniform2f(3,800.0,600.0)",
                "uniform4f(4,1.0,2.0,3.0,4.0)",
                "uniform1ui(5,7)",
            ),
            binding.log.filter { it.startsWith("uniform") },
        )
    }

    // --- frame index narrowing: FramePlan.frameIndex is a Long, uFrameIndex is a uint --------

    @Test
    fun aFrameIndexBeyondThirtyTwoBitsWrapsAsDocumented() {
        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
        val pipeline = createPipeline(binding)

        drawGeometry(
            binding, pipeline, testGrid(), IDENTITY_4X4, 800f, 600f, testBounds(),
            frameIndex = 0x1_0000_0007L,
        )

        assertEquals(listOf("uniform1ui(4,7)"), binding.log.filter { it.startsWith("uniform") })
    }

    @Test
    fun aNegativeFrameIndexBitPatternIsForwardedUnchanged() {
        // -1L's low 32 bits are all set, i.e. the uint bit pattern 0xFFFFFFFF. This pins that the
        // narrowing is a raw bit-pattern truncation, not a clamp.
        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
        val pipeline = createPipeline(binding)

        drawGeometry(binding, pipeline, testGrid(), IDENTITY_4X4, 800f, 600f, testBounds(), frameIndex = -1L)

        assertEquals(listOf("uniform1ui(4,-1)"), binding.log.filter { it.startsWith("uniform") })
    }

    // --- uGeometryBounds is informational only: it must never leak into aPosition ------------

    @Test
    fun theVertexBufferCarriesTheGridsVerticesAndUvsNeverTheBounds() {
        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0, UNIFORM_GEOMETRY_BOUNDS to 4)
        val pipeline = createPipeline(binding)
        val grid = GeometryGrid(
            cellsPerSide = 1,
            interleavedVertices = floatArrayOf(
                1f, 2f, 3f, 0f, 0f, // north-west
                4f, 5f, 6f, 1f, 0f, // north-east
                10f, 11f, 12f, 0f, 1f, // south-west
                7f, 8f, 9f, 1f, 1f, // south-east
            ),
            triangleIndices = shortArrayOf(0, 2, 3, 0, 3, 1),
        )
        val bounds = floatArrayOf(100f, 200f, 300f, 400f)

        drawGeometry(binding, pipeline, grid, IDENTITY_4X4, 1f, 1f, bounds, frameIndex = 0L)

        assertEquals(listOf("uniform4f(4,100.0,200.0,300.0,400.0)"), binding.log.filter { it.startsWith("uniform") })

        val uploaded = decodeLittleEndianFloats(requireNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER]))
        assertContentEquals(grid.interleavedVertices, uploaded)
        assertTrue(bounds.none { boundsValue -> uploaded.any { it == boundsValue } }, "bounds must never appear in vertex data")

        val indexPayload = requireNotNull(binding.bufferDataPayloads[GL_ELEMENT_ARRAY_BUFFER])
        assertContentEquals(
            byteArrayOf(0, 0, 2, 0, 3, 0, 0, 0, 3, 0, 1, 0),
            indexPayload,
            "the triangle indices reach GL as little-endian unsigned shorts",
        )
        assertTrue(
            binding.log.contains("drawElements(0x4,6,0x1403,0)"),
            "a grid draws its own index run as triangles: ${binding.log}",
        )
    }

    @Test
    fun vertexPositionsMatchTheCameraRelativeResolutionBitExactly() {
        // Proves the claim in GeometryPipeline's KDoc: drawGeometry never recomputes a vertex
        // position, so whatever internal.planning.resolveGeometry / resolveMercatorCamera resolve
        // through the camera-relative path survives into the uploaded buffer untouched (aside from
        // the intentional, already-measured Double-to-Float narrowing).
        val camera = (
            resolveMercatorCamera(
                camera = Camera(
                    latitude = 37.7749,
                    unwrappedLongitude = -122.4194,
                    zoom = 15.0,
                    bearing = 30.0,
                    pitch = 45.0,
                ),
                outputPixelSize = OutputPixelSize(width = 1024, height = 768),
            ) as SpatialOutcome.Success
            ).value
        val geometry = Geometry(
            topLeft = Vector3(37.7752, -122.4198, 12.0),
            bottomRight = Vector3(37.7746, -122.4190, 3.0),
            shaderPair = minimalShaderPair(),
        )
        val resolved = (resolveGeometry(geometry, camera) as SpatialOutcome.Success).value
        val grid = (geometryGrid(geometry, camera) as SpatialOutcome.Success).value

        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0)
        val pipeline = createPipeline(binding)

        drawGeometry(
            binding, pipeline, grid, IDENTITY_4X4,
            resolutionWidthPixels = 1024f, resolutionHeightPixels = 768f,
            boundsWestSouthEastNorthDegrees = floatArrayOf(0f, 0f, 0f, 0f),
            frameIndex = 0L,
        )

        val uploaded = decodeLittleEndianFloats(requireNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER]))
        // The grid's first vertex is its north-west node, which under Mercator is the resolved
        // top-left corner and nothing recomputed from it; stride 5 floats (xyz + uv).
        val topLeftResolved = resolved.cornersClockwiseFromTopLeft[0]
        assertEquals(topLeftResolved.x.toFloat(), uploaded[0])
        assertEquals(topLeftResolved.y.toFloat(), uploaded[1])
        assertEquals(topLeftResolved.z.toFloat(), uploaded[2])
    }

    // --- Task 7: consumer uniforms dispatch by type, bound only when declared -----------------

    @Test
    fun consumerUniformsAreDispatchedByTypeToTheMatchingUniformSetter() {
        val binding = RecordingGlBinding().withDeclaredNames(
            "uScalar" to 10, "uVec2" to 11, "uVec3" to 12, "uVec4" to 13, "uInt" to 14, "uMat" to 15,
        )
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerUniforms = mapOf(
                "uScalar" to ShaderValue.Scalar(1f),
                "uVec2" to ShaderValue.Vec2(1f, 2f),
                "uVec3" to ShaderValue.Vec3(1f, 2f, 3f),
                "uVec4" to ShaderValue.Vec4(1f, 2f, 3f, 4f),
                "uInt" to ShaderValue.Integer(7),
                "uMat" to ShaderValue.Mat4(FloatArray(16) { it.toFloat() }),
            ),
        )

        // Sorted by name: uInt, uMat, uScalar, uVec2, uVec3, uVec4.
        assertEquals(
            listOf(
                "uniform1i(14,7)",
                "uniformMatrix4fv(15,1,false)",
                "uniform1f(10,1.0)",
                "uniform2f(11,1.0,2.0)",
                "uniform3f(12,1.0,2.0,3.0)",
                "uniform4f(13,1.0,2.0,3.0,4.0)",
            ),
            binding.log.filter { it.startsWith("uniform") },
        )
    }

    @Test
    fun aConsumerUniformNotDeclaredByTheProgramIsNeverSet() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerUniforms = mapOf("uUnused" to ShaderValue.Scalar(1f)),
        )

        assertTrue(binding.log.none { it.startsWith("uniform") }, "an undeclared consumer name binds nothing")
    }

    /**
     * A consumer name costs one `glGetUniformLocation` for the life of the pipeline, not one per draw.
     *
     * Three draws over one pipeline with two names: six lookups would be one per name per draw, and
     * the assertion is two, one per distinct name. A location depends on nothing but the linked
     * program and the name, and the pipeline owns that program until [deleteGeometryPipeline] takes
     * both, so a second ask can only repeat the first answer. On a consumer program declaring
     * forty-six uniforms that is forty-six driver string lookups saved per instance per frame.
     */
    @Test
    fun aConsumerNamesLocationIsAskedOfTheDriverOncePerPipelineRatherThanOncePerDraw() {
        val binding = RecordingGlBinding().withDeclaredNames("uAlpha" to 4, "uBeta" to 5)
        val pipeline = createPipeline(binding)
        binding.log.clear()

        repeat(3) {
            drawGeometry(
                binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
                consumerUniforms = mapOf(
                    "uAlpha" to ShaderValue.Scalar(1f),
                    "uBeta" to ShaderValue.Scalar(2f),
                ),
            )
        }

        assertEquals(
            listOf(
                "getUniformLocation(${pipeline.program},uAlpha)",
                "getUniformLocation(${pipeline.program},uBeta)",
            ),
            binding.log.filter { it.startsWith("getUniformLocation(") },
            "each consumer name is resolved once and memoised on the pipeline",
        )
        // The uniforms are still set on every draw: the memo removes the lookup, never the bind, or
        // the second frame would draw with the first frame's values.
        assertEquals(
            6,
            binding.log.count { it.startsWith("uniform1f(") },
            "three draws still bind both uniforms",
        )
    }

    /**
     * An undeclared name is memoised too, so a material offering more than its shader declares stops
     * costing a lookup per draw as well.
     *
     * Worth its own test because the negative answer is the one an implementation is likeliest to
     * leave uncached: a memo that only stores successes would quietly re-ask forever for exactly the
     * names that never resolve.
     */
    @Test
    fun anUndeclaredConsumerNameIsMemoisedRatherThanReAskedOnEveryDraw() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)
        binding.log.clear()

        repeat(3) {
            drawGeometry(
                binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
                consumerUniforms = mapOf("uUnused" to ShaderValue.Scalar(1f)),
            )
        }

        assertEquals(
            1,
            binding.log.count { it.startsWith("getUniformLocation(") },
            "a name the program does not declare is asked once, and the negative answer is kept",
        )
        assertTrue(binding.log.none { it.startsWith("uniform") }, "and it still binds nothing")
    }

    /**
     * A smaller draw after a larger one uploads its own bytes and none of the tail behind them.
     *
     * The vertex and index bytes are packed into scratch the pipeline keeps and grows by doubling, so
     * after a big grid that scratch is longer than the next small grid needs and still holds the big
     * grid's bytes past the prefix. Nothing reads the array's length: [GlBinding.bufferData] takes an
     * explicit byte count and every platform actual forwards exactly that to `glBufferData`. This
     * asserts both halves — the count shrinks to the small grid, and the bytes under it are the small
     * grid's — because an implementation that passed `array.size` would upload the stale tail and
     * draw the previous geometry's vertices, which is the failure this scratch could introduce.
     */
    @Test
    fun aSmallerGridAfterALargerOneUploadsItsOwnByteCountAndNotTheScratchTail() {
        val binding = RecordingGlBinding()
        val pipeline = createPipeline(binding)

        drawGeometry(binding, pipeline, largeTestGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L)
        binding.log.clear()
        drawGeometry(binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L)

        val small = testGrid()
        val expectedVertexBytes = small.interleavedVertices.size * Float.SIZE_BYTES
        val expectedIndexBytes = small.triangleIndices.size * Short.SIZE_BYTES
        // 0x8892 is GL_ARRAY_BUFFER and 0x8893 GL_ELEMENT_ARRAY_BUFFER, as CompositePipelineTest
        // already matches them.
        assertTrue(
            binding.log.any { it.startsWith("bufferData(0x8892,$expectedVertexBytes,") },
            "the second draw uploads its own vertex byte count, not the scratch's length: ${binding.log}",
        )
        assertTrue(
            binding.log.any { it.startsWith("bufferData(0x8893,$expectedIndexBytes,") },
            "and its own index byte count: ${binding.log}",
        )

        val uploaded = assertNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER])
        assertTrue(
            uploaded.size > expectedVertexBytes,
            "the scratch is genuinely longer than this draw, or the test proves nothing",
        )
        assertContentEquals(
            littleEndianBytes(small.interleavedVertices),
            uploaded.copyOf(expectedVertexBytes),
            "the prefix GL is told to read is the small grid's own bytes",
        )
    }

    // --- Task 7/9b: consumer textures take deterministic, name-sorted units -------------------
    //
    // As of Task 9b, drawGeometry's consumerTextures parameter carries each name's ALREADY-UPLOADED
    // GL texture object name (an Int), never a DecodedImage: uploading (and caching it by
    // ResourceKey, through GlObjectRegistry) is the job of whoever assembles a frame's
    // SceneGeometrys, not drawGeometry itself -- see its KDoc. These tests therefore assert binding
    // (activeTexture/bindTexture/uniform1i), never texImage2D upload bytes; the DATA-vs-IMAGE
    // premultiply distinction is covered where the upload actually happens, in GlTextureUploadTest.

    @Test
    fun consumerTexturesTakeStableUnitsSortedByNameAndTheirSamplersReceiveTheUnitIndex() {
        val binding = RecordingGlBinding().withDeclaredNames("uMaskB" to 9, "uMaskA" to 8)
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerTextures = linkedMapOf("uMaskB" to 202, "uMaskA" to 101),
        )

        // Sorted by name regardless of map insertion order: uMaskA takes unit 0, uMaskB takes unit 1.
        assertEquals(listOf("uniform1i(8,0)", "uniform1i(9,1)"), binding.log.filter { it.startsWith("uniform1i") })
        assertEquals(
            listOf("bindTexture(0xDE1,101)", "bindTexture(0xDE1,202)"),
            binding.log.filter { it.startsWith("bindTexture") },
        )
    }

    @Test
    fun aConsumerTextureSamplerNotDeclaredByTheProgramIsNeverSet() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerTextures = mapOf("uUnused" to 101),
        )

        assertTrue(binding.log.none { it.startsWith("uniform1i") })
        // Even undeclared, the texture unit is still bound -- ADR 0008 only skips the SAMPLER
        // uniform when undeclared, never the underlying GL bind.
        assertTrue(binding.log.any { it == "bindTexture(0xDE1,101)" })
    }

    @Test
    fun drawGeometryRejectsMoreConsumerTexturesThanTheBudgetAllows() {
        val binding = RecordingGlBinding()
        val pipeline = createPipeline(binding)
        val tooMany = (0 until MAXIMUM_CONSUMER_TEXTURES + 1).associate { "uMask$it" to it }

        assertFailsWithIllegalArgument {
            drawGeometry(
                binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
                consumerTextures = tooMany,
            )
        }
    }

    // --- Task 9b: no upload happens here at all -------------------------------------------------

    @Test
    fun drawGeometryNeverCallsGenTexturesEvenWithConsumerTexturesBound() {
        // The single most important regression this refactor protects: if drawGeometry ever went
        // back to calling uploadTexture itself, every draw of an unchanged Geometry would leak a
        // fresh GPU texture again, exactly the bug Task 9b's caching fix (one layer up, in
        // RenGRenderer) depends on this function never doing.
        val binding = RecordingGlBinding().withDeclaredNames("uMask" to 8)
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerTextures = mapOf("uMask" to 101),
        )

        assertTrue(binding.log.none { it.startsWith("genTextures") })
        assertTrue(binding.log.none { it.startsWith("texImage2D") })
    }

    // --- creation / deletion, mirroring CompositePipelineTest's shape ------------------------

    @Test
    fun creationBuildsAProgramAndAQuad() {
        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0, ATTRIBUTE_TEXTURE_COORDINATE to 1)
        val pipeline = createPipeline(binding)

        assertTrue(pipeline.program > 0)
        assertTrue(pipeline.vertexArray > 0)
        assertTrue(pipeline.vertexBuffer > 0)
        assertEquals(2, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(2, binding.log.count { it.startsWith("vertexAttribPointer") })
    }

    @Test
    fun deletionRemovesTheQuadAndTheProgram() {
        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0)
        val cache = GlProgramCache()
        val pipeline =
            (createGeometryPipeline(binding, ShaderDialect.GLES, cache, minimalShaderPair()) as GeometryPipelineResult.Created).pipeline
        binding.log.clear()

        deleteGeometryPipeline(binding, cache, pipeline)

        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertEquals(null, cache.program(pipeline.key))
    }

    @Test
    fun theProgramIsCachedByShaderPairAcrossCreations() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val cache = GlProgramCache()
        val shaderPair = minimalShaderPair()

        val first =
            (createGeometryPipeline(binding, ShaderDialect.GLES, cache, shaderPair) as GeometryPipelineResult.Created).pipeline
        val compileCallsAfterFirst = binding.log.count { it.startsWith("compileShader") }
        val second =
            (createGeometryPipeline(binding, ShaderDialect.GLES, cache, shaderPair) as GeometryPipelineResult.Created).pipeline

        assertEquals(compileCallsAfterFirst, binding.log.count { it.startsWith("compileShader") })
        assertEquals(first.program, second.program)
    }

    @Test
    fun aShaderMissingTheVersionDirectiveIsATypedShaderCompileFailure() {
        val binding = RecordingGlBinding()
        val badPair = ShaderPair(vertexSource = "void main() {}", fragmentSource = minimalShaderPair().fragmentSource)

        val result = createGeometryPipeline(binding, ShaderDialect.GLES, GlProgramCache(), badPair)

        val failure = (result as GeometryPipelineResult.Failed).failure
        assertEquals(RenGErrorCode.SHADER_COMPILE_FAILED, failure.code)
    }

    // --- argument validation ------------------------------------------------------------------

    @Test
    fun aGridRejectsAPartialVertexOrAPartialTriangle() {
        assertFailsWithIllegalArgument {
            GeometryGrid(cellsPerSide = 1, interleavedVertices = FloatArray(11), triangleIndices = shortArrayOf(0, 1, 2))
        }
        assertFailsWithIllegalArgument {
            GeometryGrid(cellsPerSide = 1, interleavedVertices = FloatArray(20), triangleIndices = shortArrayOf(0, 1))
        }
    }

    @Test
    fun drawGeometryRejectsAWrongBoundsCount() {
        val binding = RecordingGlBinding()
        val pipeline = createPipeline(binding)
        assertFailsWithIllegalArgument {
            drawGeometry(binding, pipeline, testGrid(), IDENTITY_4X4, 1f, 1f, FloatArray(3), 0L)
        }
    }

    private fun createPipeline(binding: RecordingGlBinding): GeometryPipeline =
        (createGeometryPipeline(binding, ShaderDialect.GLES, GlProgramCache(), minimalShaderPair()) as GeometryPipelineResult.Created).pipeline
}

private fun assertFailsWithIllegalArgument(block: () -> Unit) {
    var threw = false
    try {
        block()
    } catch (expected: IllegalArgumentException) {
        threw = true
    }
    assertTrue(threw, "expected an IllegalArgumentException")
}

private fun minimalShaderPair(): ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n",
    fragmentSource = "#version 300 es\nprecision highp float;\nout vec4 rengOut;\nvoid main() {\n    rengOut = vec4(1.0);\n}\n",
)

private fun testGrid(): GeometryGrid = GeometryGrid(
    cellsPerSide = 1,
    interleavedVertices = floatArrayOf(
        0f, 1f, 0f, 0f, 0f,
        1f, 1f, 0f, 1f, 0f,
        0f, 0f, 0f, 0f, 1f,
        1f, 0f, 0f, 1f, 1f,
    ),
    triangleIndices = shortArrayOf(0, 2, 3, 0, 3, 1),
)

/**
 * A grid strictly larger than [testGrid] in both buffers, so drawing it first leaves the pipeline's
 * scratch longer than the small grid needs and still holding these bytes past that prefix.
 *
 * The values are distinct from [testGrid]'s on purpose: a stale-tail bug that uploaded this grid's
 * bytes under the small grid's count has to be visible as a content mismatch, not merely as a length.
 */
private fun largeTestGrid(): GeometryGrid = GeometryGrid(
    cellsPerSide = 2,
    interleavedVertices = FloatArray(9 * GEOMETRY_VERTEX_COMPONENT_COUNT) { index -> 100f + index },
    triangleIndices = ShortArray(24) { index -> (index % 9).toShort() },
)

private fun testBounds(): FloatArray = floatArrayOf(-1.0f, -1.0f, 1.0f, 1.0f)

private val IDENTITY_4X4: FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

private fun decodeLittleEndianFloats(bytes: ByteArray): FloatArray {
    require(bytes.size % Float.SIZE_BYTES == 0) { "byte payload must hold whole floats" }
    return FloatArray(bytes.size / Float.SIZE_BYTES) { index ->
        val offset = index * Float.SIZE_BYTES
        val bits = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
        Float.fromBits(bits)
    }
}
