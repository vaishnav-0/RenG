package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Vectors below are generated once via CPython's zlib module and pasted as literals so every
// target's test task asserts against the exact same bytes rather than recomputing an expectation
// with the same inflate code under test. Regenerate with:
//
// python3 - <<'PY'
// import zlib
// cases = {
//     "empty": (b"", 6),
//     "stored_level0": (b"stored block payload", 0),
//     "fixed_huffman_short": (b"aaaaaaaaaabbbbbbbbbb", 1),
//     "dynamic_text": ((b"the quick brown fox jumps over the lazy dog " * 40), 9),
//     "long_match_64k": (bytes(range(256)) * 256, 6),
//     "max_distance": (b"A" + bytes(32767) + b"A" * 8, 9),
// }
// for name, (raw, level) in cases.items():
//     print(name, list(zlib.compress(raw, level)), len(raw))
// PY
internal data class InflateVector(val name: String, val deflated: ByteArray, val expected: ByteArray)

internal val inflateVectors: List<InflateVector> = listOf(
    InflateVector(
        name = "empty",
        deflated = byteArrayOf(120, -100, 3, 0, 0, 0, 0, 1),
        expected = byteArrayOf(),
    ),
    InflateVector(
        name = "stored_level0",
        deflated = byteArrayOf(120, 1, 1, 20, 0, -21, -1, 115, 116, 111, 114, 101, 100, 32, 98, 108, 111, 99, 107, 32, 112, 97, 121, 108, 111, 97, 100, 82, 62, 7, -57),
        expected = byteArrayOf(115, 116, 111, 114, 101, 100, 32, 98, 108, 111, 99, 107, 32, 112, 97, 121, 108, 111, 97, 100),
    ),
    InflateVector(
        name = "fixed_huffman_short",
        deflated = byteArrayOf(120, 1, 75, 76, -124, -127, 36, 56, 0, 0, 79, -35, 7, -97),
        expected = byteArrayOf(97, 97, 97, 97, 97, 97, 97, 97, 97, 97, 98, 98, 98, 98, 98, 98, 98, 98, 98, 98),
    ),
    InflateVector(
        name = "dynamic_text",
        deflated = byteArrayOf(120, -38, 43, -55, 72, 85, 40, 44, -51, 76, -50, 86, 72, 42, -54, 47, -49, 83, 72, -53, -81, 80, -56, 42, -51, 45, 40, 86, -56, 47, 75, 45, 82, 40, 1, 74, -25, 36, 86, 85, 42, -92, -28, -89, -125, 57, -93, 106, 71, -43, -114, -86, 29, 85, 59, -86, 118, 84, -19, 80, 80, 11, 0, 76, -128, -124, 7),
        expected = byteArrayOf(116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32, 116, 104, 101, 32, 113, 117, 105, 99, 107, 32, 98, 114, 111, 119, 110, 32, 102, 111, 120, 32, 106, 117, 109, 112, 115, 32, 111, 118, 101, 114, 32, 116, 104, 101, 32, 108, 97, 122, 121, 32, 100, 111, 103, 32),
    ),
    InflateVector(
        name = "long_match_64k",
        deflated = byteArrayOf(120, -100, -19, -49, 3, 18, 24, 6, 0, 0, -80, -38, -74, 109, -37, -74, -51, -43, -74, 109, -37, -74, -71, -43, 90, 109, -37, -74, 109, -69, 119, 125, 71, -14, -125, 4, 8, 24, 40, 112, -112, -96, -63, -126, -121, 8, 25, 42, 116, -104, -80, -31, -62, 71, -120, 24, 41, 114, -108, -88, -47, -94, -57, -120, 25, 43, 118, -100, -72, -15, -30, 39, 72, -104, 40, 113, -110, -92, -55, -110, -89, 72, -103, 42, 117, -102, -76, -23, -46, 103, -56, -104, 41, 115, -106, -84, -39, -78, -25, -56, -103, 43, 119, -98, -68, -7, -14, 23, 40, 88, -88, 112, -111, -94, -59, -118, -105, 40, 89, -86, 116, -103, -78, -27, -54, 87, -88, 88, -87, 114, -107, -86, -43, -86, -41, -88, -7, 79, -83, -38, 117, -22, -42, -85, -33, -96, 97, -93, -58, 77, -102, 54, 107, -34, -94, 101, -85, -42, 109, -38, -74, 107, -33, -95, 99, -89, -50, 93, -70, 118, -21, -34, -93, 103, -81, -34, 125, -6, -10, -21, 63, 96, -32, -96, -63, 67, -122, 14, 27, 62, 98, -28, -88, -47, 99, -58, -114, 27, 63, 97, -30, -92, -55, 83, -90, 78, -101, 62, 99, -26, -84, -39, 115, -26, -50, -101, -65, 96, -31, -94, -59, 75, -106, 46, 91, -2, -17, 127, 43, 86, -82, 90, -67, 102, -19, -70, -11, 27, 54, 110, -6, 127, -13, -106, -83, -37, -74, -17, -40, -71, 107, -9, -98, -67, -5, -10, 31, 56, 120, -24, -16, -111, -93, -57, -114, -97, 56, 121, -22, -12, -103, -77, -25, -50, 95, -72, 120, -23, -14, -107, -85, -41, -82, -33, -72, 121, -21, -10, -99, -69, -9, -18, 63, 120, -8, -24, -15, -109, -89, -49, -98, -65, 120, -7, -22, -11, -101, -73, -17, -34, 127, -8, -8, -23, -13, -105, -81, -33, -66, -1, -8, -7, -21, 119, 0, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, -1, -65, -1, 63, -69, -70, -121, 114),
        expected = ByteArray(65536) { (it % 256).toByte() },
    ),
    InflateVector(
        name = "max_distance",
        deflated = byteArrayOf(120, -38, -19, -63, 33, 1, 0, 0, 0, 2, 32, -81, -7, -1, -108, -59, 25, 64, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -12, 6, 13, 35, 2, 74),
        expected = ByteArray(32776) { i -> if (i == 0 || i >= 32768) 'A'.code.toByte() else 0 },
    ),
)

class InflateTest {
    @Test
    fun inflatesStoredFixedAndDynamicBlocks() {
        for (vector in inflateVectors) {
            val output = ByteArray(vector.expected.size)
            val stream = InflateStream()
            var consumed = 0
            var produced = 0
            var finished = false
            while (!finished) {
                val step = stream.inflate(
                    vector.deflated,
                    consumed,
                    vector.deflated.size - consumed,
                    output,
                    produced,
                    output.size - produced,
                )
                consumed += step.consumed
                produced += step.produced
                finished = step.finished
                if (step.consumed == 0 && step.produced == 0 && !step.finished) break
            }
            stream.close()
            assertTrue(finished, "${vector.name}: stream did not finish")
            assertEquals(vector.expected.size, produced, "${vector.name}: wrong output length")
            assertContentEquals(vector.expected, output, "${vector.name}: wrong bytes")
        }
    }

    @Test
    fun splitInputAcrossChunkBoundariesProducesIdenticalOutput() {
        val vector = inflateVectors.first { it.name == "dynamic_text" }
        val output = ByteArray(vector.expected.size)
        val stream = InflateStream()
        var produced = 0
        var finished = false
        // Feed one byte at a time: the PNG case, where a zlib stream is cut at
        // arbitrary offsets by IDAT chunk boundaries.
        var index = 0
        while (!finished && index <= vector.deflated.size) {
            val inputLength = minOf(1, vector.deflated.size - index)
            val step = stream.inflate(
                vector.deflated,
                index,
                inputLength,
                output,
                produced,
                output.size - produced,
            )
            index += step.consumed
            produced += step.produced
            finished = step.finished
            if (step.consumed == 0 && step.produced == 0 && !step.finished) index += 1
        }
        stream.close()
        assertTrue(finished)
        assertContentEquals(vector.expected, output)
    }

    @Test
    fun undersizedOutputBufferAcrossSeveralCallsProducesIdenticalBytes() {
        // Both existing successful-decode tests give inflate() a full-size output array, so the
        // available window only shrinks as it nears the end — it is never genuinely constrained. This
        // reuses one small (and not evenly divisive) 13-byte window across every call, on the largest
        // vector (64 KiB, built from long LZ77 back-references), forcing many resumptions where each
        // call's avail_out runs out well before the stream itself does — exactly the pattern a decoder
        // filling one fixed-size row or tile buffer at a time will hit in Tasks 4 and 5.
        val vector = inflateVectors.first { it.name == "long_match_64k" }
        val result = ByteArray(vector.expected.size)
        val window = ByteArray(13)
        val stream = InflateStream()
        var consumed = 0
        var produced = 0
        var finished = false
        while (!finished) {
            val step = stream.inflate(
                vector.deflated,
                consumed,
                vector.deflated.size - consumed,
                window,
                0,
                window.size,
            )
            consumed += step.consumed
            window.copyInto(result, destinationOffset = produced, startIndex = 0, endIndex = step.produced)
            produced += step.produced
            finished = step.finished
            if (step.consumed == 0 && step.produced == 0 && !step.finished) break
        }
        stream.close()
        assertTrue(finished, "stream did not finish")
        assertEquals(vector.expected.size, produced, "wrong output length")
        assertContentEquals(vector.expected, result, "wrong bytes")
    }

    @Test
    fun corruptPayloadFailsRatherThanProducingPlausibleBytes() {
        val vector = inflateVectors.first { it.name == "dynamic_text" }
        val corrupted = vector.deflated.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 0x5A).toByte()
        val stream = InflateStream()
        assertFailsWith<InflateException> {
            var produced = 0
            var finished = false
            var index = 0
            while (!finished) {
                val output = ByteArray(4096)
                val step = stream.inflate(
                    corrupted,
                    index,
                    corrupted.size - index,
                    output,
                    produced.coerceAtMost(output.size),
                    (output.size - produced).coerceAtLeast(0),
                )
                index += step.consumed
                produced += step.produced
                finished = step.finished
                if (step.consumed == 0 && !step.finished) break
            }
            if (!finished) throw InflateException("stalled")
        }
        stream.close()
    }

    @Test
    fun crc32MatchesKnownAnswers() {
        assertEquals(0u, crc32(0u, ByteArray(0), 0, 0))
        val check = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926u, crc32(0u, check, 0, check.size))
        // A PNG chunk's CRC covers its 4-byte type immediately followed by its payload — a contiguous
        // range of the same buffer a container walk already holds — so the seed stays 0 and the whole
        // range is covered by one call, never by chaining across separate calls. Confirm that a
        // contiguous range computed in place (the type bytes sitting at an offset inside a larger
        // buffer, exactly as a chunk walk would see them) matches the same bytes computed on their own.
        val ihdr = byteArrayOf(0x49, 0x48, 0x44, 0x52)
        val embedded = byteArrayOf(0xAA.toByte(), 0xBB.toByte()) + ihdr + byteArrayOf(0xCC.toByte())
        assertEquals(crc32(0u, ihdr, 0, 4), crc32(0u, embedded, 2, 4))
    }
}
