package com.rohittp.reng.internal.model

import com.rohittp.reng.internal.gl.GL_UNSIGNED_INT
import com.rohittp.reng.internal.gl.GL_UNSIGNED_SHORT
import com.rohittp.reng.internal.glb.GltfAccessor
import com.rohittp.reng.internal.glb.GltfBuffer
import com.rohittp.reng.internal.glb.GltfBufferView
import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.math.DoubleMatrix4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val BYTE = 5120
private const val UNSIGNED_BYTE = 5121
private const val SHORT = 5122
private const val UNSIGNED_SHORT = 5123
private const val UNSIGNED_INT = 5125
private const val FLOAT = 5126

/** Twelve bytes of decoy that every fixture places *before* its BIN chunk. A reader that ignored
 * the chunk's own range and started at index zero of the backing array would read these instead,
 * which is exactly what [readsStartAtTheChunksRangeRatherThanTheStartOfTheArray] asserts against. */
private val DECOY_PREFIX: ByteArray = bytes { floats(-1f, -2f, -3f) }

class AccessorReaderTest {
    @Test
    fun aTightlyPackedVec3FloatAccessorReadsItsComponentsInOrder() {
        val chunk = chunkOf(bytes { floats(1f, 2f, 3f, 4f, 5f, 6f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 24)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 2)),
        )

        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), chunk.readFloatElements(document, 0))
    }

    @Test
    fun readsStartAtTheChunksRangeRatherThanTheStartOfTheArray() {
        // The BIN chunk is never the whole file: its range begins after the GLB header and the JSON
        // chunk. Reading from index zero of the backing array would return DECOY_PREFIX's floats.
        val chunk = chunkOf(bytes { floats(7f, 8f, 9f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 12)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 1)),
        )

        assertContentEquals(floatArrayOf(7f, 8f, 9f), chunk.readFloatElements(document, 0))
    }

    @Test
    fun anAccessorEndingOnTheChunksLastByteIsRead() {
        // The off-by-one either way: a span that ends exactly at the final byte fits, and the test
        // below it proves a span one element longer does not.
        val chunk = chunkOf(bytes { floats(1f, 2f, 3f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 12)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 1)),
        )

        assertContentEquals(floatArrayOf(1f, 2f, 3f), chunk.readFloatElements(document, 0))
    }

    @Test
    fun anInterleavedAccessorRespectsItsBufferViewStride() {
        // One view, stride 20: POSITION at offset 0, TEXCOORD_0 at offset 12.
        val position = 0
        val texCoord = 1
        val chunk = chunkOf(
            bytes {
                floats(0f, 1f, 2f, 0.25f, 0.5f)
                floats(10f, 11f, 12f, 0.75f, 1.0f)
            },
        )
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 40, byteStride = 20)),
            accessors = listOf(
                accessor(componentType = FLOAT, type = "VEC3", count = 2),
                accessor(componentType = FLOAT, type = "VEC2", count = 2, byteOffset = 12),
            ),
        )

        assertContentEquals(floatArrayOf(0f, 1f, 2f, 10f, 11f, 12f), chunk.readFloatElements(document, position))
        assertContentEquals(floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f), chunk.readFloatElements(document, texCoord))
    }

    @Test
    fun aNormalizedUnsignedShortTexCoordDequantizesToTheUnitRange() {
        // 0 -> 0.0, 65535 -> 1.0, 32767 -> 32767/65535.
        val chunk = chunkOf(bytes { u16(0, 65535, 32767, 65535) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 8)),
            accessors = listOf(
                accessor(componentType = UNSIGNED_SHORT, type = "VEC2", count = 2, normalized = true),
            ),
        )

        val values = chunk.readFloatElements(document, 0)!!

        assertEquals(0.0f, values[0])
        assertEquals(1.0f, values[1], 1e-7f)
        assertEquals(32767.0f / 65535.0f, values[2], 1e-7f)
    }

    @Test
    fun aNormalizedSignedShortRotationDequantizesWithTheSpecificationsClamp() {
        // glTF: max(c / 32767, -1), so -32768 clamps to exactly -1.0 rather than -1.000031.
        val chunk = chunkOf(bytes { u16(-32768, 16384, 0, 32767) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 8)),
            accessors = listOf(accessor(componentType = SHORT, type = "VEC4", count = 1, normalized = true)),
        )

        val values = chunk.readFloatElements(document, 0)!!

        assertEquals(-1.0f, values[0], "the specification's clamp turns -32768 into exactly -1.0")
        assertEquals(16384.0f / 32767.0f, values[1], 1e-7f)
        assertEquals(0.0f, values[2])
        assertEquals(1.0f, values[3], 1e-7f)
    }

    @Test
    fun aNormalizedSignedByteDequantizesWithTheSameClamp() {
        // The one-byte half of the same rule: max(c / 127, -1), so -128 is exactly -1.0.
        val chunk = chunkOf(bytes { u8(128, 127, 0, 64) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 4)),
            accessors = listOf(accessor(componentType = BYTE, type = "VEC4", count = 1, normalized = true)),
        )

        val values = chunk.readFloatElements(document, 0)!!

        assertEquals(-1.0f, values[0], "the specification's clamp turns -128 into exactly -1.0")
        assertEquals(1.0f, values[1], 1e-7f)
        assertEquals(0.0f, values[2])
        assertEquals(64.0f / 127.0f, values[3], 1e-7f)
    }

    @Test
    fun anUnnormalizedUnsignedByteAccessorReadsRawCountsRatherThanFractions() {
        // JOINTS_0 is an unnormalized unsigned byte or short, and Task 9 binds it as GL_FLOAT: joint
        // 200 must arrive as 200.0, not as 200/255. `normalized` is the only thing that decides.
        val chunk = chunkOf(bytes { u8(200, 1, 0, 255) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 4)),
            accessors = listOf(accessor(componentType = UNSIGNED_BYTE, type = "VEC4", count = 1)),
        )

        assertContentEquals(floatArrayOf(200f, 1f, 0f, 255f), chunk.readFloatElements(document, 0))
    }

    @Test
    fun unsignedByteIndicesAreWidenedToUnsignedShort() {
        val chunk = chunkOf(bytes { u8(0, 1, 2) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 3)),
            accessors = listOf(accessor(componentType = UNSIGNED_BYTE, type = "SCALAR", count = 3)),
        )

        val indices = chunk.readIndices(document, 0)!!

        assertEquals(GL_UNSIGNED_SHORT, indices.glComponentType, "byte indices are not drawable on desktop core")
        assertContentEquals(shortArrayOf(0, 1, 2), indices.shorts)
        assertNull(indices.ints)
        assertEquals(3, indices.count)
    }

    @Test
    fun unsignedByteIndicesAboveOneHundredAndTwentySevenWidenWithoutSignExtension() {
        // 200 read as a signed Byte is -56, which addresses no vertex at all.
        val chunk = chunkOf(bytes { u8(200, 255, 0) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 3)),
            accessors = listOf(accessor(componentType = UNSIGNED_BYTE, type = "SCALAR", count = 3)),
        )

        assertContentEquals(shortArrayOf(200, 255, 0), chunk.readIndices(document, 0)!!.shorts)
    }

    @Test
    fun unsignedShortIndicesKeepTheirRawSixteenBitPattern() {
        // 40000 does not fit a signed Short; GL reads the same sixteen bits as unsigned, so the raw
        // pattern is what has to survive the widening.
        val chunk = chunkOf(bytes { u16(40000, 1, 2) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 6)),
            accessors = listOf(accessor(componentType = UNSIGNED_SHORT, type = "SCALAR", count = 3)),
        )

        val indices = chunk.readIndices(document, 0)!!

        assertEquals(GL_UNSIGNED_SHORT, indices.glComponentType)
        assertContentEquals(shortArrayOf(40000.toShort(), 1, 2), indices.shorts)
    }

    @Test
    fun unsignedIntIndicesStayThirtyTwoBit() {
        val chunk = chunkOf(bytes { u32(0, 1, 70000) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 12)),
            accessors = listOf(accessor(componentType = UNSIGNED_INT, type = "SCALAR", count = 3)),
        )

        val indices = chunk.readIndices(document, 0)!!

        assertEquals(GL_UNSIGNED_INT, indices.glComponentType, "70000 does not fit sixteen bits")
        assertContentEquals(intArrayOf(0, 1, 70000), indices.ints)
        assertNull(indices.shorts)
        assertEquals(3, indices.count)
    }

    @Test
    fun aFloatAccessorIsNotAnIndexBuffer() {
        // GltfReject.INDICES_ACCESSOR_FORMAT already refuses this at parse. Reinterpreting float bits
        // as vertex numbers is the one outcome worse than reporting the fault twice.
        val chunk = chunkOf(bytes { floats(0f, 1f, 2f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 12)),
            accessors = listOf(accessor(componentType = FLOAT, type = "SCALAR", count = 3)),
        )

        assertNull(chunk.readIndices(document, 0))
    }

    @Test
    fun anAccessorWhoseSpanFallsOutsideTheActualBinChunkReturnsNull() {
        // parseGltf proved the span fits the *declared* buffer length -- 24 bytes, which the view and
        // the accessor below both fit. This proves it fits the real bytes, of which there are 12.
        val shortChunk = chunkOf(bytes { floats(1f, 2f, 3f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 24)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 2)),
            bufferByteLength = 24,
        )

        assertNull(shortChunk.readFloatElements(document, 0))
    }

    @Test
    fun readIndicesAndReadMatricesBoundAgainstTheActualChunkToo() {
        // The same truncation, through the other two entry points: one bound, applied everywhere.
        val shortChunk = chunkOf(bytes { u16(0, 1, 2) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 128)),
            accessors = listOf(
                accessor(componentType = UNSIGNED_SHORT, type = "SCALAR", count = 12),
                accessor(componentType = FLOAT, type = "MAT4", count = 1),
            ),
            bufferByteLength = 128,
        )

        assertNull(shortChunk.readIndices(document, 0))
        assertNull(shortChunk.readMatrices(document, 1))
    }

    @Test
    fun aMatrixAccessorReadsGltfColumnMajorOrder() {
        // glTF stores MAT4 column-major; a row-major read transposes every inverse bind matrix
        // silently. 1..16 is asymmetric, so the transpose is a different matrix and the test can see
        // the difference at all.
        val columnMajor = (1..16).map { it.toDouble() }
        val chunk = chunkOf(bytes { floats(*FloatArray(16) { (it + 1).toFloat() }) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 64)),
            accessors = listOf(accessor(componentType = FLOAT, type = "MAT4", count = 1)),
        )

        val matrix = chunk.readMatrices(document, 0)!!.single()

        assertEquals(13.0, matrix[0, 3], "the thirteenth stored value is row 0 of column 3")
        assertEquals(4.0, matrix[3, 0], "and the fourth is row 3 of column 0")
        assertEquals(DoubleMatrix4(columnMajor), matrix)
        assertNotEquals(DoubleMatrix4(columnMajor).transpose(), matrix, "a transposed read must not pass")
    }

    @Test
    fun aMultiElementMatrixAccessorAdvancesOneMatrixPerElement() {
        val chunk = chunkOf(bytes { floats(*FloatArray(32) { (it + 1).toFloat() }) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 128)),
            accessors = listOf(accessor(componentType = FLOAT, type = "MAT4", count = 2)),
        )

        val matrices = chunk.readMatrices(document, 0)!!

        assertEquals(2, matrices.size)
        assertEquals(1.0, matrices[0][0, 0])
        assertEquals(17.0, matrices[1][0, 0], "the second matrix starts at the seventeenth value")
        assertEquals(29.0, matrices[1][0, 3])
    }

    @Test
    fun aVectorAccessorIsNotAMatrixAccessor() {
        // GltfUnsupported.SKIN_ACCESSOR_FORMAT already refuses a non-MAT4 inverse bind accessor.
        // Grouping a VEC3 run into fours would invent a rig rather than report one.
        val chunk = chunkOf(bytes { floats(*FloatArray(48) { it.toFloat() }) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 192)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 16)),
        )

        assertNull(chunk.readMatrices(document, 0))
    }

    @Test
    fun anAccessorWithNoBufferViewReturnsNull() {
        // Legal glTF -- it means all zeros -- and validateGltfFeatures refuses it as
        // ACCESSOR_WITHOUT_BUFFER_VIEW long before any read. Zero-filling here would collapse a mesh
        // to the origin and report success, which is the silent fallback ADR 0021 refuses.
        val chunk = chunkOf(bytes { floats(1f, 2f, 3f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 12)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 1, bufferView = null)),
        )

        assertNull(chunk.readFloatElements(document, 0))
    }

    @Test
    fun aSparseAccessorReturnsNull() {
        // validateGltfFeatures refuses SPARSE_ACCESSOR first. Reading only the base bytes would drop
        // the sparse override silently, which is worse than refusing twice.
        val chunk = chunkOf(bytes { floats(1f, 2f, 3f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 12)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 1, sparse = true)),
        )

        assertNull(chunk.readFloatElements(document, 0))
    }

    @Test
    fun anAccessorIndexNamingNoAccessorReturnsNull() {
        val chunk = chunkOf(bytes { floats(1f, 2f, 3f) })
        val document = documentWith(
            bufferViews = listOf(view(byteLength = 12)),
            accessors = listOf(accessor(componentType = FLOAT, type = "VEC3", count = 1)),
        )

        assertNull(chunk.readFloatElements(document, 1))
        assertNull(chunk.readFloatElements(document, -1))
    }
}

private fun chunkOf(payload: ByteArray, prefix: ByteArray = DECOY_PREFIX): BinChunk =
    BinChunk(prefix + payload, prefix.size until prefix.size + payload.size)

private fun view(byteLength: Long, byteOffset: Long = 0L, byteStride: Long? = null): GltfBufferView =
    GltfBufferView(buffer = 0, byteOffset = byteOffset, byteLength = byteLength, byteStride = byteStride)

private fun accessor(
    componentType: Int,
    type: String,
    count: Long,
    bufferView: Int? = 0,
    byteOffset: Long = 0L,
    normalized: Boolean = false,
    sparse: Boolean = false,
): GltfAccessor = GltfAccessor(
    bufferView = bufferView,
    byteOffset = byteOffset,
    componentType = componentType,
    count = count,
    type = type,
    normalized = normalized,
    sparse = sparse,
)

private fun documentWith(
    bufferViews: List<GltfBufferView>,
    accessors: List<GltfAccessor>,
    bufferByteLength: Long = 1L shl 20,
): GltfDocument = GltfDocument(
    accessors = accessors,
    bufferViews = bufferViews,
    meshes = emptyList(),
    nodes = emptyList(),
    skins = emptyList(),
    scenes = emptyList(),
    defaultScene = null,
    animations = emptyList(),
    materials = emptyList(),
    images = emptyList(),
    textures = emptyList(),
    samplers = emptyList(),
    extensionsRequired = emptyList(),
    buffers = listOf(GltfBuffer(byteLength = bufferByteLength, uri = null)),
)

/** Little-endian, because every published target is (see `internal.gl.littleEndianBytes`). */
private class ByteWriter {
    private val written = ArrayList<Byte>()

    fun u8(vararg values: Int) {
        values.forEach { written += (it and 0xFF).toByte() }
    }

    fun u16(vararg values: Int) {
        values.forEach {
            written += (it and 0xFF).toByte()
            written += ((it shr 8) and 0xFF).toByte()
        }
    }

    fun u32(vararg values: Long) {
        values.forEach {
            for (byteIndex in 0 until 4) written += ((it shr (8 * byteIndex)) and 0xFFL).toByte()
        }
    }

    fun floats(vararg values: Float) {
        values.forEach { u32(it.toRawBits().toLong() and 0xFFFFFFFFL) }
    }

    fun build(): ByteArray = written.toByteArray()
}

private fun bytes(write: ByteWriter.() -> Unit): ByteArray = ByteWriter().apply(write).build()
