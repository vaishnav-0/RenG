package com.rohittp.reng.internal.glb

import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson

/**
 * The outcome of [scanGlb]: either the container was well-formed and its JSON chunk parsed as an
 * object, or the first structural rule that failed.
 *
 * [Admitted.binChunk] is the BIN chunk's raw byte range in the scanned array, or `null` when no
 * BIN chunk is present. It is not cross-checked against any `buffers[0].byteLength` declared
 * inside [Admitted.json] — that relationship needs the parsed glTF document and belongs to a
 * later, JSON-content-aware stage.
 */
internal sealed interface GlbScan {
    data class Admitted(val json: JsonValue.Obj, val binChunk: IntRange?) : GlbScan {
        /**
         * [binChunk]'s length in bytes, `0` when there is no BIN chunk -- the figure [parseGltf]
         * takes as its `binChunkLength`.
         *
         * Derived by subtraction rather than by `binChunk.count()`, which is `Iterable.count()` and
         * therefore walks the range one boxed `Int` at a time: measured at **7.2 ms** over a real
         * 130 KB BIN chunk on Apple M3 Max, against **0.44 ms** for the entire container scan and
         * document parse it was feeding. Every caller wants one subtraction, and none wants a loop
         * whose cost grows with the model.
         */
        val binChunkLength: Long
            get() {
                val range = binChunk ?: return 0L
                return if (range.isEmpty()) 0L else range.last.toLong() - range.first.toLong() + 1L
            }
    }

    data class Malformed(val reason: GlbReject) : GlbScan
}

/**
 * Every reason [scanGlb] can reject a file. Distinct from `JsonReject`: these are container-
 * framing and padding violations, never JSON grammar violations (a JSON grammar violation inside
 * the JSON chunk that is not the strict-space padding case reported here as
 * [JSON_TRAILING_CONTENT], since the container layer has no finer-grained code to report it with).
 *
 * A JSON chunk that parses cleanly but whose root value is not an object is a distinct fault from
 * both of those: every byte parsed, so nothing is "trailing", and the document obeys JSON grammar,
 * so nothing is malformed JSON — it is simply the wrong shape for glTF, whose root **MUST** be an
 * object. That case is [JSON_ROOT_NOT_OBJECT], and the two ways a byte-level fault can beat it are
 * not the same mechanism. [JSON_PADDING_NOT_SPACE] genuinely wins by ordering: it is checked, in
 * the same [JsonParse.Parsed] branch, before the root's shape is inspected, so a non-object root
 * with bad padding still reports the padding fault. [JSON_TRAILING_CONTENT] wins by construction,
 * not ordering: it can only arise from the separate, mutually exclusive [JsonParse.Failed] branch,
 * which returns immediately and never reaches the root-shape check at all — there is no race
 * between the two to order, because a single parse can never produce both outcomes.
 */
internal enum class GlbReject {
    HEADER_TOO_SHORT,
    BAD_MAGIC,
    UNSUPPORTED_CONTAINER_VERSION,
    DECLARED_LENGTH_MISMATCH,
    CHUNK_LENGTH_MISALIGNED,
    TRUNCATED_CHUNK_HEADER,
    TRUNCATED_CHUNK_DATA,
    JSON_CHUNK_NOT_FIRST,
    EMPTY_JSON_CHUNK,
    BIN_CHUNK_NOT_SECOND,
    UNKNOWN_CHUNK_IN_BIN_POSITION,
    JSON_TRAILING_CONTENT,
    JSON_PADDING_NOT_SPACE,
    JSON_CHUNK_TOO_LARGE,
    JSON_ROOT_NOT_OBJECT,
}

private const val HEADER_BYTES = 12
private const val CHUNK_HEADER_BYTES = 8
private const val MAGIC = 0x46546C67L
private const val SUPPORTED_VERSION = 2L
private const val JSON_CHUNK_TYPE = 0x4E4F534AL
private const val BIN_CHUNK_TYPE = 0x004E4942L
private const val SPACE_BYTE = 0x20

/** JSON nesting bound for the GLB JSON chunk. A glTF document is a handful of levels deep; this
 * is generous for anything RenG reads and finite for arbitrary consumer `extras`. */
private const val MAXIMUM_JSON_DEPTH = 64

/**
 * Scans [bytes] as a GLB (glTF 2.0 binary) container: a 12-byte header followed by a walk of
 * 4-aligned chunks.
 *
 * The header's declared total length must **equal** the actual byte count exactly, and be a
 * multiple of four. This is stricter than the specification's wording, and deliberately so: that
 * one equality comparison alone rejects truncation, an inflated declared length, a misaligned
 * length, a file ending inside a chunk header, and appended garbage — five different authoring
 * accidents collapsed into a single check.
 *
 * The first chunk must be JSON (`0x4E4F534A`) and non-empty. A BIN chunk (`0x004E4942`) is
 * permitted only as the second chunk. An unknown chunk found in the second position is rejected
 * rather than scanned past, because the specification permits extension chunks only following the
 * first two. Chunks from the third position on with any other type are ignored.
 *
 * The JSON chunk is bounded against [maximumJsonChunkBytes] **before** it is parsed: a boxed JSON
 * value tree costs many times its source text, so an unbounded chunk is a denial of service from a
 * file the transport layer already accepted. Once parsed, every byte from the document's own
 * closing token (`JsonParse.Parsed.endOffset`) to the end of the chunk must be exactly `0x20` —
 * the specification's mandated JSON pad byte. This is stricter than plain JSON whitespace: a
 * tab-padded chunk parses cleanly under an ordinary reader (tab is JSON whitespace) but is
 * rejected here, because `0x20` is the only byte the specification permits.
 *
 * The BIN chunk's own trailing bytes are never inspected here: `chunkLength` includes any padding
 * and nothing records the buffer's unpadded length, so a container-level reader cannot tell
 * payload from padding by length alone. The BIN chunk's byte range is simply returned as-is.
 */
internal fun scanGlb(bytes: ByteArray, maximumJsonChunkBytes: Long): GlbScan {
    if (bytes.size < HEADER_BYTES) return GlbScan.Malformed(GlbReject.HEADER_TOO_SHORT)

    val magic = readUInt32LE(bytes, 0)
    if (magic != MAGIC) return GlbScan.Malformed(GlbReject.BAD_MAGIC)

    val version = readUInt32LE(bytes, 4)
    if (version != SUPPORTED_VERSION) return GlbScan.Malformed(GlbReject.UNSUPPORTED_CONTAINER_VERSION)

    val declaredLength = readUInt32LE(bytes, 8)
    if (declaredLength != bytes.size.toLong() || declaredLength % 4L != 0L) {
        return GlbScan.Malformed(GlbReject.DECLARED_LENGTH_MISMATCH)
    }

    var position = HEADER_BYTES
    var chunkIndex = 0
    var json: JsonValue.Obj? = null
    var binChunk: IntRange? = null

    while (position < bytes.size) {
        chunkIndex++

        if (position + CHUNK_HEADER_BYTES > bytes.size) {
            return GlbScan.Malformed(GlbReject.TRUNCATED_CHUNK_HEADER)
        }

        val chunkLength = readUInt32LE(bytes, position)
        val chunkType = readUInt32LE(bytes, position + 4)
        if (chunkLength % 4L != 0L) return GlbScan.Malformed(GlbReject.CHUNK_LENGTH_MISALIGNED)

        val dataStart = position + CHUNK_HEADER_BYTES
        val dataEndLong = dataStart.toLong() + chunkLength
        if (dataEndLong > bytes.size.toLong()) return GlbScan.Malformed(GlbReject.TRUNCATED_CHUNK_DATA)
        val dataEnd = dataEndLong.toInt()

        when (chunkType) {
            JSON_CHUNK_TYPE -> {
                if (chunkIndex != 1) return GlbScan.Malformed(GlbReject.JSON_CHUNK_NOT_FIRST)
                if (chunkLength == 0L) return GlbScan.Malformed(GlbReject.EMPTY_JSON_CHUNK)
                if (chunkLength > maximumJsonChunkBytes) return GlbScan.Malformed(GlbReject.JSON_CHUNK_TOO_LARGE)

                when (val parsed = parseJson(bytes, dataStart, dataEnd, MAXIMUM_JSON_DEPTH)) {
                    is JsonParse.Failed -> return GlbScan.Malformed(GlbReject.JSON_TRAILING_CONTENT)
                    is JsonParse.Parsed -> {
                        // The byte-level padding fault is checked first and wins: it is detected
                        // during this same walk and is the more specific, earlier-discovered
                        // problem. Only once padding is confirmed clean is the root's shape
                        // checked, so a non-object root with bad padding still reports the
                        // padding fault, never JSON_ROOT_NOT_OBJECT.
                        for (index in parsed.endOffset until dataEnd) {
                            if ((bytes[index].toInt() and 0xFF) != SPACE_BYTE) {
                                return GlbScan.Malformed(GlbReject.JSON_PADDING_NOT_SPACE)
                            }
                        }
                        json = parsed.value as? JsonValue.Obj
                            ?: return GlbScan.Malformed(GlbReject.JSON_ROOT_NOT_OBJECT)
                    }
                }
            }

            BIN_CHUNK_TYPE -> {
                if (chunkIndex != 2) return GlbScan.Malformed(GlbReject.BIN_CHUNK_NOT_SECOND)
                binChunk = dataStart until dataEnd
            }

            else -> when (chunkIndex) {
                1 -> return GlbScan.Malformed(GlbReject.JSON_CHUNK_NOT_FIRST)
                2 -> return GlbScan.Malformed(GlbReject.UNKNOWN_CHUNK_IN_BIN_POSITION)
                else -> Unit // Extension chunks are ignored following the first two.
            }
        }

        position = dataEnd
    }

    val admittedJson = json ?: return GlbScan.Malformed(GlbReject.EMPTY_JSON_CHUNK)
    return GlbScan.Admitted(admittedJson, binChunk)
}

private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
