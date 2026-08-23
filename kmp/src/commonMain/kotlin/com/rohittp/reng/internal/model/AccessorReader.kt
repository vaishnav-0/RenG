package com.rohittp.reng.internal.model

import com.rohittp.reng.internal.gl.GL_UNSIGNED_INT
import com.rohittp.reng.internal.gl.GL_UNSIGNED_SHORT
import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.math.DoubleMatrix4

/** Component count per accessor `type` string, the same table `parseGltf` keys its element-size
 * arithmetic off. Restated here rather than shared because the two files answer different
 * questions with it -- one decides whether a span is decidable, this one decides how many numbers
 * to produce -- and `internal/glb` already keeps its own copy private per file. */
private val COMPONENT_COUNT_BY_TYPE: Map<String, Int> = mapOf(
    "SCALAR" to 1, "VEC2" to 2, "VEC3" to 3, "VEC4" to 4, "MAT2" to 4, "MAT3" to 9, "MAT4" to 16,
)

/** The two accessor `type` strings whose columns the specification pads to a four-byte boundary
 * when the component is smaller than four bytes. No role in RenG's subset reaches this file with
 * one -- attributes are `SCALAR`/`VEC2`/`VEC3`/`VEC4`, an inverse bind accessor is `MAT4`, and
 * `MAT4` never pads whatever its component size -- so the padded layout is refused rather than
 * read as if it were tight, which would return silently wrong numbers. */
private val COLUMN_PADDED_TYPES: Set<String> = setOf("MAT2", "MAT3")

/** The only accessor `type` [readMatrices] reads. */
private const val MATRIX_TYPE = "MAT4"

private const val MATRIX_COMPONENTS = 16

/**
 * The six `componentType` values the specification defines, carrying the byte width that decides
 * every offset in this file. A resolved [ComponentFormat] is what lets the decode `when`s below be
 * total with no `else` branch inventing a value for a number no table knows.
 */
private enum class ComponentFormat(val byteCount: Int) {
    BYTE(1),
    UNSIGNED_BYTE(1),
    SHORT(2),
    UNSIGNED_SHORT(2),
    UNSIGNED_INT(4),
    FLOAT(4),
}

private fun componentFormat(componentType: Int): ComponentFormat? = when (componentType) {
    5120 -> ComponentFormat.BYTE
    5121 -> ComponentFormat.UNSIGNED_BYTE
    5122 -> ComponentFormat.SHORT
    5123 -> ComponentFormat.UNSIGNED_SHORT
    5125 -> ComponentFormat.UNSIGNED_INT
    5126 -> ComponentFormat.FLOAT
    else -> null
}

/**
 * A GLB's BIN chunk: the [bytes] a resource operation delivered, and the [range] `scanGlb` reported
 * the chunk to occupy inside them ([com.rohittp.reng.internal.glb.GlbScan.Admitted.binChunk]). Every
 * offset the read functions in this file compute is relative to the start of that range, never to
 * the start of the array, because the chunk begins after the GLB header and the JSON chunk.
 *
 * A document with no BIN chunk at all is an empty range (`IntRange.EMPTY`), against which every read
 * bounds-checks to `null` -- an accessor cannot address bytes that were never delivered. A range
 * reaching outside [bytes] is narrowed to the part that exists, for the same reason: this class
 * never reads a byte it was not handed, whatever it was told about the ones it was.
 *
 * The array is held rather than copied. It is up to the caller's `maximumModelGlbBytes` budget in
 * size, and every read here is bounded by [byteCount], so a copy would buy nothing but the peak.
 */
internal class BinChunk(private val bytes: ByteArray, private val range: IntRange) {
    private val start: Int = range.first.coerceIn(0, bytes.size)

    private val endExclusive: Int = when {
        range.isEmpty() -> start
        // Stated as `>=` rather than by adding one, so a `last` of `Int.MAX_VALUE` cannot overflow
        // into a negative end that then narrows the chunk instead of clamping it.
        range.last >= bytes.size -> bytes.size
        else -> (range.last + 1).coerceAtLeast(start)
    }

    /** How many bytes of the chunk actually exist. Every bound in this file is against this and
     * never against `buffers[0].byteLength`: `parseGltf` already proved the declaration consistent,
     * and a BIN chunk truncated below its declaration is the distinct fault it could not see. */
    internal val byteCount: Int = endExclusive - start

    /** Whether [length] bytes starting at [offset] lie inside the chunk. Both operands are `Long`
     * and the comparison is a subtraction, so an offset or length large enough to overflow a sum
     * fails the check rather than wrapping past it. */
    internal fun covers(offset: Long, length: Long): Boolean =
        offset >= 0L && length >= 0L && offset <= byteCount.toLong() - length

    /**
     * The [componentByteCount] bytes at [offset] read as an unsigned little-endian integer. All six
     * published targets are little-endian and glTF fixes the BIN chunk's byte order as little-endian
     * regardless, so this is the format on the wire, not a host assumption (the same reasoning
     * `internal.gl.littleEndianBytes` states for the outgoing direction).
     *
     * The caller has already proved the span through [covers]; this performs no bound check of its
     * own, which is why it is not part of the file's public shape.
     */
    internal fun littleEndianUnsigned(offset: Int, componentByteCount: Int): Long {
        var value = 0L
        for (byteIndex in 0 until componentByteCount) {
            value = value or ((bytes[start + offset + byteIndex].toLong() and 0xFFL) shl (8 * byteIndex))
        }
        return value
    }
}

/**
 * One primitive's index buffer, in the width GL will draw it at. Exactly one of [shorts] and [ints]
 * is present, and [glComponentType] says which -- `GL_UNSIGNED_SHORT` or `GL_UNSIGNED_INT`, ready to
 * hand to `glDrawElements` without a second mapping.
 *
 * Both arrays hold raw bit patterns rather than Kotlin's signed interpretation of them: an index of
 * `40000` is stored as the `Short` `-25536`, and GL reads back the same sixteen unsigned bits.
 */
internal class ModelIndices(
    val shorts: ShortArray?,
    val ints: IntArray?,
    val glComponentType: Int,
    val count: Int,
) {
    init {
        require((shorts == null) != (ints == null)) { "exactly one index width is populated" }
    }
}

/**
 * Every component of `document.accessors[accessorIndex]`, de-interleaved into one tightly packed
 * `FloatArray` of `count * componentsPerElement` values, or `null` when the accessor cannot be read.
 *
 * A `normalized` integer accessor is dequantized by the specification's own table, including its
 * clamp: `max(c / 127, -1)` and `max(c / 32767, -1)` for the signed forms -- so `-128` and `-32768`
 * yield exactly `-1.0` rather than `-1.0079` and `-1.000031` -- and `c / 255` and `c / 65535` for the
 * unsigned ones. An *unnormalized* integer accessor is read as the count it is: `JOINTS_0` is an
 * unnormalized unsigned byte or short, and joint 200 must arrive as `200.0`. `normalized` is
 * therefore the only thing that decides between the two, which is why `validateGltfFeatures` refuses
 * the flag on the two component types the specification forbids it on.
 *
 * `null` means the caller reports `RESOURCE_PARSE_FAILED`; this file never throws and never repairs.
 * The faults are: an accessor index naming no accessor, an unknown `componentType` or `type`, a
 * `bufferView` that is absent or names no view, a `sparse` accessor, a column-padded matrix type, a
 * `byteStride` below the element size, and -- the one no earlier gate could have caught -- a span
 * that runs past the end of the BIN chunk actually delivered.
 */
internal fun BinChunk.readFloatElements(document: GltfDocument, accessorIndex: Int): FloatArray? {
    val layout = layoutFor(document, accessorIndex) ?: return null
    val values = FloatArray(layout.count * layout.componentsPerElement)
    var target = 0

    for (element in 0 until layout.count) {
        val elementOffset = layout.offsetOf(element)
        for (component in 0 until layout.componentsPerElement) {
            val raw = littleEndianUnsigned(elementOffset + component * layout.format.byteCount, layout.format.byteCount)
            values[target++] = decodeFloat(raw, layout.format, layout.normalized)
        }
    }

    return values
}

/**
 * `document.accessors[accessorIndex]` read as a primitive's index buffer, or `null` when it cannot
 * be read as one. Every fault [readFloatElements] reports applies, plus the accessor not being an
 * unnormalized unsigned `SCALAR` -- which `parseGltf` already refuses as
 * `GltfReject.INDICES_ACCESSOR_FORMAT`, and which is re-stated here because reinterpreting float
 * bits as vertex numbers is worse than reporting one fault twice.
 *
 * `UNSIGNED_BYTE` indices widen to `ShortArray` and report `GL_UNSIGNED_SHORT`. They are legal glTF,
 * and no desktop GL core profile draws them, so the widening happens once here rather than at every
 * draw site. The widening is unsigned: `200` widens to `200`, not to the `-56` a sign-extending
 * `Byte.toShort()` would produce.
 */
internal fun BinChunk.readIndices(document: GltfDocument, accessorIndex: Int): ModelIndices? {
    val layout = layoutFor(document, accessorIndex) ?: return null
    if (layout.componentsPerElement != 1 || layout.normalized) return null

    return when (layout.format) {
        ComponentFormat.UNSIGNED_BYTE, ComponentFormat.UNSIGNED_SHORT -> {
            val shorts = ShortArray(layout.count) { element ->
                littleEndianUnsigned(layout.offsetOf(element), layout.format.byteCount).toShort()
            }
            ModelIndices(shorts, null, GL_UNSIGNED_SHORT, layout.count)
        }

        ComponentFormat.UNSIGNED_INT -> {
            val ints = IntArray(layout.count) { element ->
                littleEndianUnsigned(layout.offsetOf(element), layout.format.byteCount).toInt()
            }
            ModelIndices(null, ints, GL_UNSIGNED_INT, layout.count)
        }

        ComponentFormat.BYTE, ComponentFormat.SHORT, ComponentFormat.FLOAT -> null
    }
}

/**
 * `document.accessors[accessorIndex]` read as one `DoubleMatrix4` per element -- a skin's inverse
 * bind matrices, and nothing else in RenG's subset -- or `null` when it cannot be read as one.
 *
 * glTF stores a `MAT4` in column-major order, which is the order [DoubleMatrix4]'s primary
 * constructor takes, so the sixteen values pass straight through with no transpose. A transposed
 * inverse bind matrix deforms a rig plausibly rather than obviously, so this is asserted against an
 * asymmetric fixture rather than left to inspection.
 *
 * A non-`MAT4` accessor is `null` rather than sixteen-value groups cut out of a longer run:
 * `validateGltfFeatures` already refuses one as `GltfUnsupported.SKIN_ACCESSOR_FORMAT`, and
 * regrouping would invent a rig rather than report the fault.
 */
internal fun BinChunk.readMatrices(document: GltfDocument, accessorIndex: Int): List<DoubleMatrix4>? {
    if (accessorIndex !in document.accessors.indices) return null
    if (document.accessors[accessorIndex].type != MATRIX_TYPE) return null

    val values = readFloatElements(document, accessorIndex) ?: return null
    return List(values.size / MATRIX_COMPONENTS) { matrix ->
        val first = matrix * MATRIX_COMPONENTS
        DoubleMatrix4(List(MATRIX_COMPONENTS) { values[first + it].toDouble() })
    }
}

/**
 * Everything an accessor's bytes are addressed by, resolved once and narrowed to `Int` only after
 * the whole span has been proved to lie inside the chunk. Offsets stay `Int` from there on: the
 * final byte of the final element is inside a chunk whose length is an `Int`, so no address this
 * describes can overflow one.
 */
private class AccessorLayout(
    val format: ComponentFormat,
    val componentsPerElement: Int,
    val count: Int,
    val firstElementOffset: Int,
    val strideBytes: Int,
    val normalized: Boolean,
) {
    fun offsetOf(element: Int): Int = firstElementOffset + element * strideBytes
}

private fun BinChunk.layoutFor(document: GltfDocument, accessorIndex: Int): AccessorLayout? {
    if (accessorIndex !in document.accessors.indices) return null
    val accessor = document.accessors[accessorIndex]

    // Both of these are already `GltfUnsupported` refusals -- SPARSE_ACCESSOR and
    // ACCESSOR_WITHOUT_BUFFER_VIEW -- so neither is reachable behind the feature gate. They are
    // refused rather than approximated because the two approximations available (drop the sparse
    // override, substitute zeros) are silent: they produce a model that draws and is wrong.
    if (accessor.sparse) return null
    val viewIndex = accessor.bufferView ?: return null
    if (viewIndex !in document.bufferViews.indices) return null
    val view = document.bufferViews[viewIndex]

    val format = componentFormat(accessor.componentType) ?: return null
    val componentsPerElement = COMPONENT_COUNT_BY_TYPE[accessor.type] ?: return null
    if (accessor.type in COLUMN_PADDED_TYPES && format.byteCount < Int.SIZE_BYTES) return null

    val elementBytes = format.byteCount.toLong() * componentsPerElement
    val strideBytes = view.byteStride ?: elementBytes
    if (strideBytes < elementBytes) return null
    if (accessor.count < 1L || accessor.byteOffset < 0L || view.byteOffset < 0L) return null

    // Written as a subtraction against a non-negative operand so the sum cannot overflow into a
    // negative offset that would then pass every fit test below.
    if (view.byteOffset > byteCount.toLong() - accessor.byteOffset) return null
    val firstElementOffset = view.byteOffset + accessor.byteOffset

    // The same overflow-free shape `parseGltf.validateAccessorSpan` uses, applied to the chunk that
    // actually arrived rather than to the length the document declared for it.
    if (!covers(firstElementOffset, elementBytes)) return null
    val remaining = byteCount.toLong() - firstElementOffset - elementBytes
    if (accessor.count - 1L > remaining / strideBytes) return null

    // A single-element accessor never advances, so its declared stride -- unbounded by anything
    // `parseGltf` checks -- is not narrowed. Past one element the check above has already bounded
    // the stride by the chunk length.
    val narrowedStride = if (accessor.count > 1L) strideBytes else elementBytes

    return AccessorLayout(
        format = format,
        componentsPerElement = componentsPerElement,
        count = accessor.count.toInt(),
        firstElementOffset = firstElementOffset.toInt(),
        strideBytes = narrowedStride.toInt(),
        normalized = accessor.normalized,
    )
}

/**
 * One component, dequantized per the specification's own table. The clamp on the two signed forms is
 * the specification's, not a safety margin: the negative half of a signed quantization has one more
 * step than the positive half, so `-32768 / 32767` is `-1.000031` and the table says to floor it at
 * `-1`.
 *
 * `normalized` is ignored for the two component types the specification forbids it on --
 * [ComponentFormat.FLOAT], which is already a real number, and [ComponentFormat.UNSIGNED_INT], which
 * `validateGltfFeatures` refuses the flag on ([com.rohittp.reng.internal.glb.GltfUnsupported]
 * `NORMALIZED_NOT_PERMITTED`).
 */
private fun decodeFloat(raw: Long, format: ComponentFormat, normalized: Boolean): Float = when (format) {
    ComponentFormat.FLOAT -> Float.fromBits(raw.toInt())
    ComponentFormat.BYTE -> raw.toByte().toInt().let { if (normalized) maxOf(it / 127.0f, -1.0f) else it.toFloat() }
    ComponentFormat.UNSIGNED_BYTE -> raw.toInt().let { if (normalized) it / 255.0f else it.toFloat() }
    ComponentFormat.SHORT -> raw.toShort().toInt().let { if (normalized) maxOf(it / 32767.0f, -1.0f) else it.toFloat() }
    ComponentFormat.UNSIGNED_SHORT -> raw.toInt().let { if (normalized) it / 65535.0f else it.toFloat() }
    ComponentFormat.UNSIGNED_INT -> raw.toFloat()
}
