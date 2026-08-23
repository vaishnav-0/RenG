package com.rohittp.reng.internal.glb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GlbContainerTest {
    @Test
    fun classifiesEveryContainerFixtureAsIntended() {
        assertIs<GlbScan.Admitted>(scan("01-valid-json-and-bin"))
        assertIs<GlbScan.Admitted>(scan("02-valid-json-only"))
        assertEquals(GlbReject.BAD_MAGIC, reject("03-bad-magic"))
        assertEquals(GlbReject.UNSUPPORTED_CONTAINER_VERSION, reject("04-version-1"))
        assertEquals(GlbReject.UNSUPPORTED_CONTAINER_VERSION, reject("05-version-3"))
        // Five different authoring accidents collapse into one equality comparison.
        for (name in listOf(
            "06-truncated-chunk-data", "07-declared-length-too-large",
            "08-declared-length-not-multiple-of-4", "13-truncated-chunk-header",
            "31-trailing-garbage-length-unchanged",
        )) {
            assertEquals(GlbReject.DECLARED_LENGTH_MISMATCH, reject(name), name)
        }
        assertEquals(GlbReject.BIN_CHUNK_NOT_SECOND, reject("10-json-chunk-second"))
        assertEquals(GlbReject.JSON_CHUNK_NOT_FIRST, reject("15-two-json-chunks"))
        assertEquals(GlbReject.UNKNOWN_CHUNK_IN_BIN_POSITION, reject("18-bin-after-unknown-chunk"))
        assertEquals(GlbReject.EMPTY_JSON_CHUNK, reject("14-empty-json-chunk"))
        assertIs<GlbScan.Admitted>(scan("17-unknown-chunk-third"))
        assertEquals(GlbReject.HEADER_TOO_SHORT, reject("30-empty-file"))
    }

    @Test
    fun adoptsTheStrictSpacePaddingRule() {
        assertIs<GlbScan.Admitted>(scan("19-json-padded-with-spaces"))
        assertEquals(GlbReject.JSON_TRAILING_CONTENT, reject("20-json-padded-with-nulls"))
        // Tab is JSON whitespace, so only the strict rule catches it.
        assertEquals(GlbReject.JSON_PADDING_NOT_SPACE, reject("21-json-padded-with-tabs"))
    }

    @Test
    fun reportsTheBinChunkLengthWithoutWalkingIt() {
        // Both callers of parseGltf take this figure. Derived by subtraction rather than by
        // IntRange.count(), which walks the range one boxed Int at a time -- 7.2 ms over a real
        // 130 KB BIN chunk on Apple M3 Max, against 0.44 ms for the whole scan and parse it feeds.
        val withBin = assertIs<GlbScan.Admitted>(scan("01-valid-json-and-bin"))
        assertEquals(withBin.binChunk?.count()?.toLong(), withBin.binChunkLength)
        // No BIN chunk at all is zero, not an absent length parseGltf would have to interpret.
        assertEquals(0L, assertIs<GlbScan.Admitted>(scan("02-valid-json-only")).binChunkLength)
    }

    @Test
    fun boundsTheBinChunkByTheDeclaredBufferLength() {
        assertIs<GlbScan.Admitted>(scan("27-buffer-3-shorter-than-bin-chunk"))
        assertIs<GlbScan.Admitted>(scan("22-bin-padded-with-zeros"))
        // Padding bytes are unverifiable: chunkLength includes them and nothing records the
        // unpadded length, so 22 and 23 differ only in pad bytes and both are admitted.
        assertIs<GlbScan.Admitted>(scan("23-bin-padded-with-spaces"))
    }

    @Test
    fun boundsTheJsonChunkIndependentlyOfTheWholeGlbCeiling() {
        assertEquals(GlbReject.JSON_CHUNK_TOO_LARGE, rejectWithCeiling("02-valid-json-only", 8L))
    }

    @Test
    fun exercisesThePreviouslyUnreachedContainerRejectCodes() {
        // 32: the JSON chunk's own chunkLength (5) is not a multiple of four, but the file's
        // total byte count is deliberately kept a multiple of four, so the header-level length
        // check passes and the walk reaches the per-chunk misalignment check.
        assertEquals(GlbReject.CHUNK_LENGTH_MISALIGNED, reject("32-chunk-length-misaligned-total-aligned"))
        // 33: the file ends 4 bytes into the first chunk's 8-byte header, with the declared
        // length recomputed to match the truncated actual size (unlike fixture 13, which leaves
        // the original, too-large declared length and so is caught at the header gate instead).
        assertEquals(GlbReject.TRUNCATED_CHUNK_HEADER, reject("33-truncated-first-chunk-header"))
        // 34: a fully-present chunk header declares chunkLength = 0xFFFFFFF0 -- a multiple of
        // four, so it clears the misalignment check -- that overflows the actual file size.
        assertEquals(GlbReject.TRUNCATED_CHUNK_DATA, reject("34-chunk-length-overflow-aligned"))
    }

    @Test
    fun findsAnEarlierGateWinsFixture09() {
        // 09 is the research document's "misaligned-chunk-length" fixture, built the naive way:
        // the header's declared total length is set to the actual (undoctored) byte count, which
        // itself inherits the chunk's misalignment rather than being corrected to compensate for
        // it (that correction is what fixture 32 does instead). The header-level length check
        // fires on either half of its OR -- not equal to the actual size, or not a multiple of
        // four -- so it rejects here before the per-chunk chunkLength % 4 check is ever reached.
        // The document's own separate "DECLARED_LENGTH_MISALIGNED" label for this fixture names a
        // code this implementation does not have: Task 6/7 folded "not equal" and "not a multiple
        // of four" into the single DECLARED_LENGTH_MISMATCH check, so this fixture cannot newly
        // cover CHUNK_LENGTH_MISALIGNED -- fixture 32 above is what does that. Reported in full in
        // the task-7-fix-report.md finding.
        assertEquals(GlbReject.DECLARED_LENGTH_MISMATCH, reject("09-misaligned-chunk-length"))
    }

    @Test
    fun rejectsANonObjectJsonRootPrecisely() {
        // Every byte parsed cleanly in all three; the fault is the document's shape, not
        // trailing bytes or bad padding.
        assertEquals(GlbReject.JSON_ROOT_NOT_OBJECT, reject("root-not-object-array"))
        assertEquals(GlbReject.JSON_ROOT_NOT_OBJECT, reject("root-not-object-number"))
        assertEquals(GlbReject.JSON_ROOT_NOT_OBJECT, reject("root-not-object-string"))
    }

    @Test
    fun aByteLevelPaddingFaultWinsOverTheRootShapeCheck() {
        // [1,2,3] is a non-object root AND has a genuine non-space padding byte; the byte-level
        // fault, detected during the same walk, must be reported instead of JSON_ROOT_NOT_OBJECT.
        assertEquals(GlbReject.JSON_PADDING_NOT_SPACE, reject("root-not-object-array-bad-padding"))
    }

    // ---- fixture-name plumbing ----

    private val defaultCeiling = 1_048_576L

    private fun scan(name: String): GlbScan = scanGlb(fixture(name), defaultCeiling)

    private fun reject(name: String): GlbReject =
        assertIs<GlbScan.Malformed>(scan(name), "fixture $name").reason

    private fun rejectWithCeiling(name: String, ceiling: Long): GlbReject =
        assertIs<GlbScan.Malformed>(scanGlb(fixture(name), ceiling), "fixture $name").reason

    private fun fixture(name: String): ByteArray = when (name) {
        "01-valid-json-and-bin" -> validGlb()
        "02-valid-json-only" -> validJsonOnlyGlb()
        "03-bad-magic" -> badMagicGlb()
        "04-version-1" -> versionGlb(1L)
        "05-version-3" -> versionGlb(3L)
        "06-truncated-chunk-data" -> truncatedTailGlb()
        "07-declared-length-too-large" -> declaredLengthOffsetGlb(+100L)
        "08-declared-length-not-multiple-of-4" -> declaredLengthOffsetGlb(-1L)
        "13-truncated-chunk-header" -> truncatedIntoChunkHeaderGlb()
        "31-trailing-garbage-length-unchanged" -> trailingGarbageGlb()
        "10-json-chunk-second" -> binFirstJsonSecondGlb()
        "15-two-json-chunks" -> twoJsonChunksGlb()
        "18-bin-after-unknown-chunk" -> binAfterUnknownChunkGlb()
        "14-empty-json-chunk" -> emptyJsonChunkGlb()
        "17-unknown-chunk-third" -> unknownChunkThirdGlb()
        "30-empty-file" -> ByteArray(0)
        "19-json-padded-with-spaces" -> jsonPaddedGlb(0x20)
        "20-json-padded-with-nulls" -> jsonPaddedGlb(0x00)
        "21-json-padded-with-tabs" -> jsonPaddedGlb(0x09)
        // Task 7's container walk never inspects a buffer length declared inside the JSON chunk
        // against the BIN chunk's length — that cross-check needs the parsed glTF document and is
        // a later stage's job. All three of these fixtures are therefore just ordinary two-chunk
        // containers as far as scanGlb is concerned.
        "27-buffer-3-shorter-than-bin-chunk" -> binPaddedGlb(0x00)
        "22-bin-padded-with-zeros" -> binPaddedGlb(0x00)
        "23-bin-padded-with-spaces" -> binPaddedGlb(0x20)
        "09-misaligned-chunk-length" -> misalignedChunkLengthUndoctoredGlb()
        "32-chunk-length-misaligned-total-aligned" -> chunkLengthMisalignedTotalAlignedGlb()
        "33-truncated-first-chunk-header" -> truncatedFirstChunkHeaderGlb()
        "34-chunk-length-overflow-aligned" -> chunkLengthOverflowAlignedGlb()
        "root-not-object-array" -> nonObjectRootGlb("[1,2,3]")
        "root-not-object-number" -> nonObjectRootGlb("42")
        "root-not-object-string" -> nonObjectRootGlb("\"hello\"")
        "root-not-object-array-bad-padding" -> arrayRootWithBadPaddingGlb()
        else -> error("no fixture named $name")
    }

    // ---- fixture construction ----

    private fun u32le(value: Long): ByteArray = byteArrayOf(
        (value and 0xFFL).toByte(),
        ((value shr 8) and 0xFFL).toByte(),
        ((value shr 16) and 0xFFL).toByte(),
        ((value shr 24) and 0xFFL).toByte(),
    )

    private val jsonType = 0x4E4F534AL
    private val binType = 0x004E4942L

    // Neither JSON nor BIN: an arbitrary chunk type used to exercise the unknown-chunk rules.
    private val unknownType = 0x12345678L

    private fun chunk(type: Long, payload: ByteArray): ByteArray =
        u32le(payload.size.toLong()) + u32le(type) + payload

    /** Assembles a well-formed GLB: header (with a correct, matching declared length) plus the
     * given already-built chunks, concatenated in order. */
    private fun glbFrom(chunks: List<ByteArray>, version: Long = 2L): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, next -> acc + next }
        val total = 12L + body.size
        return u32le(0x46546C67L) + u32le(version) + u32le(total) + body
    }

    private fun spacePadded(text: String): ByteArray {
        val raw = text.encodeToByteArray()
        val padCount = (4 - raw.size % 4) % 4
        return raw + ByteArray(padCount) { 0x20 }
    }

    private fun simpleJsonChunk(): ByteArray = chunk(jsonType, spacePadded("{}"))

    private fun simpleBinChunk(): ByteArray = chunk(binType, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    private fun validGlb(): ByteArray = glbFrom(listOf(simpleJsonChunk(), simpleBinChunk()))

    private fun validJsonOnlyGlb(): ByteArray =
        // 9 bytes of text, padded to 12: comfortably above an 8-byte ceiling and comfortably
        // below the default one.
        glbFrom(listOf(chunk(jsonType, spacePadded("""{"abc":1}"""))))

    private fun badMagicGlb(): ByteArray {
        val bytes = validJsonOnlyGlb().copyOf()
        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        return bytes
    }

    private fun versionGlb(version: Long): ByteArray =
        glbFrom(listOf(simpleJsonChunk()), version = version)

    /** Truncates a valid file's tail without correcting the header's declared length, so the
     * declared length (still the original, larger value) no longer equals the actual byte count. */
    private fun truncatedTailGlb(): ByteArray {
        val full = validGlb()
        return full.copyOfRange(0, full.size - 4)
    }

    /** Overwrites only the header's declared-length field with `actualTotal + delta`, leaving the
     * rest of a valid file's bytes untouched. */
    private fun declaredLengthOffsetGlb(delta: Long): ByteArray {
        val chunks = listOf(simpleJsonChunk(), simpleBinChunk())
        val body = chunks.fold(ByteArray(0)) { acc, next -> acc + next }
        val actualTotal = 12L + body.size
        val header = u32le(0x46546C67L) + u32le(2L) + u32le(actualTotal + delta)
        return header + body
    }

    /** A valid two-chunk file cut off 4 bytes into the second chunk's 8-byte header, with the
     * declared length left at the original (now too large) value. */
    private fun truncatedIntoChunkHeaderGlb(): ByteArray {
        val full = validGlb()
        val cutAt = 12 + simpleJsonChunk().size + 4
        return full.copyOfRange(0, cutAt)
    }

    /** A valid file with garbage appended and the declared length left unchanged. */
    private fun trailingGarbageGlb(): ByteArray = validGlb() + byteArrayOf(0x01, 0x02, 0x03, 0x04)

    private fun binFirstJsonSecondGlb(): ByteArray =
        glbFrom(listOf(simpleBinChunk(), simpleJsonChunk()))

    private fun twoJsonChunksGlb(): ByteArray =
        glbFrom(listOf(simpleJsonChunk(), simpleJsonChunk()))

    private fun binAfterUnknownChunkGlb(): ByteArray =
        glbFrom(listOf(simpleJsonChunk(), chunk(unknownType, byteArrayOf(9, 9, 9, 9)), simpleBinChunk()))

    private fun emptyJsonChunkGlb(): ByteArray = glbFrom(listOf(chunk(jsonType, ByteArray(0))))

    private fun unknownChunkThirdGlb(): ByteArray =
        glbFrom(listOf(simpleJsonChunk(), simpleBinChunk(), chunk(unknownType, byteArrayOf(7, 7, 7, 7))))

    /** A JSON chunk whose payload is `{}` followed by six extra padding bytes, all [padByte]. */
    private fun jsonPaddedGlb(padByte: Int): ByteArray {
        val payload = "{}".encodeToByteArray() + ByteArray(6) { padByte.toByte() }
        return glbFrom(listOf(chunk(jsonType, payload)))
    }

    private fun binPaddedGlb(padByte: Int): ByteArray {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6) + ByteArray(2) { padByte.toByte() }
        return glbFrom(listOf(simpleJsonChunk(), chunk(binType, payload)))
    }

    /** Fixture 09: a single JSON chunk whose own `chunkLength` field (5) is not a multiple of
     * four, built the undoctored way -- the header's declared total length is simply set to the
     * actual byte count, which inherits the same misalignment rather than being corrected to
     * compensate for it. This lands on the header-level DECLARED_LENGTH_MISMATCH gate before the
     * per-chunk chunkLength % 4 check is ever reached; see [chunkLengthMisalignedTotalAlignedGlb]
     * for the fixture that does reach it. */
    private fun misalignedChunkLengthUndoctoredGlb(): ByteArray {
        val chunkHeader = u32le(5L) + u32le(jsonType)
        val payload = ByteArray(5) { 0x41 } // Arbitrary; never read at this rejection point.
        val body = chunkHeader + payload
        val actualTotal = 12L + body.size // 25, not a multiple of four.
        val header = u32le(0x46546C67L) + u32le(2L) + u32le(actualTotal)
        return header + body
    }

    /** Fixture 32: the JSON chunk's own `chunkLength` field (5) is not a multiple of four, but
     * the file's total byte count is deliberately kept a multiple of four (20: header plus an
     * 8-byte chunk header, with zero payload bytes actually present) so the header-level length
     * check passes and the walk reaches the per-chunk misalignment check. */
    private fun chunkLengthMisalignedTotalAlignedGlb(): ByteArray {
        val chunkHeader = u32le(5L) + u32le(jsonType)
        val actualTotal = 12L + chunkHeader.size // 20, a multiple of four.
        val header = u32le(0x46546C67L) + u32le(2L) + u32le(actualTotal)
        return header + chunkHeader
    }

    /** Fixture 33: the file ends 4 bytes into the first chunk's 8-byte header (only the
     * `chunkLength` field is present, not `chunkType`), with the declared length recomputed to
     * match the truncated actual size -- unlike fixture 13, which leaves the original, too-large
     * declared length and so is caught at the header gate instead. */
    private fun truncatedFirstChunkHeaderGlb(): ByteArray {
        val partialChunkHeader = u32le(8L) // Just the chunkLength field; chunkType never arrives.
        val actualTotal = 12L + partialChunkHeader.size // 16, a multiple of four.
        val header = u32le(0x46546C67L) + u32le(2L) + u32le(actualTotal)
        return header + partialChunkHeader
    }

    /** Fixture 34: a fully-present 8-byte chunk header declares `chunkLength = 0xFFFFFFF0` -- a
     * multiple of four, so it clears the misalignment check -- that overflows the actual file
     * size. */
    private fun chunkLengthOverflowAlignedGlb(): ByteArray {
        val chunkHeader = u32le(0xFFFFFFF0L) + u32le(jsonType)
        val actualTotal = 12L + chunkHeader.size // 20, a multiple of four.
        val header = u32le(0x46546C67L) + u32le(2L) + u32le(actualTotal)
        return header + chunkHeader
    }

    /** A JSON chunk whose content parses cleanly but whose root value is [content]'s JSON shape,
     * not an object -- e.g. an array, a bare number, or a bare string. */
    private fun nonObjectRootGlb(content: String): ByteArray =
        glbFrom(listOf(chunk(jsonType, spacePadded(content))))

    /** A non-object root (`[1,2,3]`) whose one padding byte is a tab, not the mandated space
     * byte. Tab is JSON whitespace, so the reader still returns `Parsed` (as with fixture 21) and
     * the strict-space check -- not parseJson's own trailing-content check -- is what catches it;
     * this is what lets the byte-level padding fault reach the same branch as the root-shape
     * check and prove it still wins. */
    private fun arrayRootWithBadPaddingGlb(): ByteArray {
        val payload = "[1,2,3]".encodeToByteArray() + byteArrayOf(0x09)
        return glbFrom(listOf(chunk(jsonType, payload)))
    }
}
