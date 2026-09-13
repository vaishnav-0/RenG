package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceAdaptersTest {
    @Test
    fun responseAdmitsMalformedConsumerValuesButCopiesBytes() {
        val source = byteArrayOf(1, 2, 3)
        val response = TransportResponse(
            statusCode = -7,
            body = source,
            metadata = TransportResponseMetadata(
                contentType = "\rcontent-type-secret",
                etag = "\nvalidator-secret",
                lastModified = "\uD800",
                freshUntilEpochMillis = -1,
            ),
        )
        source[0] = 9
        val returned = response.body
        returned[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), response.body)
        assertEquals(-7, response.statusCode)
        assertEquals("\rcontent-type-secret", response.metadata.contentType)
        assertEquals("\nvalidator-secret", response.metadata.etag)
        assertEquals("\uD800", response.metadata.lastModified)
        assertEquals(-1, response.metadata.freshUntilEpochMillis)
        val equal = TransportResponse(-7, byteArrayOf(1, 2, 3), response.metadata)
        assertEquals(response, equal)
        assertEquals(response.hashCode(), equal.hashCode())
        assertNotEquals(response, TransportResponse(-7, byteArrayOf(1, 2, 4), response.metadata))
        assertRedacted(
            response.toString(),
            "content-type-secret",
            "validator-secret",
            "\uD800",
        )
    }

    @Test
    fun storedResourceAdmitsMalformedConsumerValuesCopiesBytesAndUsesThemForEqualityAndHashing() {
        val source = byteArrayOf(1, 2, 3)
        val metadata = StoredRawResourceMetadata(
            contentType = "\rcontent-type-secret",
            etag = "\nvalidator-secret",
            lastModified = "\uD800",
            freshUntilEpochMillis = -1,
            storedAtEpochMillis = -1,
        )
        val first = StoredRawResource(source, "not-a-digest-secret", metadata)
        val second = StoredRawResource(byteArrayOf(1, 2, 3), "not-a-digest-secret", metadata)
        source[0] = 9
        val returned = first.bytes
        returned[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), first.bytes)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, StoredRawResource(byteArrayOf(1, 2, 4), "not-a-digest-secret", metadata))
        assertEquals(-1, first.metadata.storedAtEpochMillis)
        assertRedacted(
            first.toString(),
            "not-a-digest-secret",
            "content-type-secret",
            "validator-secret",
            "\uD800",
        )
    }

    @Test
    fun equalityKeepsItsByteWalkWhileHashingTrustsTheDigest() {
        // ADR 0061's asymmetry, which is the whole of that decision. These two carry the same digest
        // and the same metadata but different bytes -- a lying digest, which only a consumer can
        // produce, since RenG derives one from the bytes.
        val metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L)
        val first = StoredRawResource(byteArrayOf(1, 2, 3), "d".repeat(64), metadata)
        val second = StoredRawResource(byteArrayOf(9, 9, 9), "d".repeat(64), metadata)

        // `equals` must still walk the bytes: trusting the digest alone would make equality exactly
        // as sound as its least careful supplier, and these are not the same resource.
        assertNotEquals(first, second)

        // `hashCode` may trust what `equals` proves. Equal resources still hash equally because
        // `equals` requires matching digests; all a lying digest can do is collide two UNEQUAL
        // resources, which costs a bucket comparison that then calls `equals` and gets it right.
        // A collision is a performance event, never a correctness one -- and this equality is what
        // shows the hash no longer walks the array.
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun adapterDtosUseStructuralEqualityAndShapeOnlyText() {
        val requestMetadata = TransportRequestMetadata(
            ifNoneMatch = "validator-secret",
            ifModifiedSince = "timestamp-secret",
            accept = "accept-secret",
        )
        val request = TransportRequest(
            locator = ResourceLocator("https://example.invalid/signed-url-secret"),
            resourceClass = ResourceClass.MODEL_GLB,
            maximumResponseBytes = -1,
            metadata = requestMetadata,
        )
        val responseMetadata = TransportResponseMetadata(
            contentType = "content-type-secret",
            etag = "validator-secret",
            lastModified = "timestamp-secret",
            freshUntilEpochMillis = -1,
        )
        val storedMetadata = StoredRawResourceMetadata(
            contentType = "content-type-secret",
            etag = "validator-secret",
            lastModified = "timestamp-secret",
            freshUntilEpochMillis = -1,
            storedAtEpochMillis = -1,
        )
        val rawKey = RawResourceKey("stable-id-secret", ResourceClass.MODEL_GLB)

        assertEquals(requestMetadata, TransportRequestMetadata("validator-secret", "timestamp-secret", "accept-secret"))
        assertEquals(requestMetadata.hashCode(), TransportRequestMetadata("validator-secret", "timestamp-secret", "accept-secret").hashCode())
        assertEquals(request, TransportRequest(ResourceLocator("https://example.invalid/signed-url-secret"), ResourceClass.MODEL_GLB, -1, requestMetadata))
        assertEquals(request.hashCode(), TransportRequest(ResourceLocator("https://example.invalid/signed-url-secret"), ResourceClass.MODEL_GLB, -1, requestMetadata).hashCode())
        assertEquals(responseMetadata, TransportResponseMetadata("content-type-secret", "validator-secret", "timestamp-secret", -1))
        assertEquals(responseMetadata.hashCode(), TransportResponseMetadata("content-type-secret", "validator-secret", "timestamp-secret", -1).hashCode())
        assertEquals(storedMetadata, StoredRawResourceMetadata("content-type-secret", "validator-secret", "timestamp-secret", -1, -1))
        assertEquals(storedMetadata.hashCode(), StoredRawResourceMetadata("content-type-secret", "validator-secret", "timestamp-secret", -1, -1).hashCode())
        assertEquals(rawKey, RawResourceKey("stable-id-secret", ResourceClass.MODEL_GLB))
        assertEquals(rawKey.hashCode(), RawResourceKey("stable-id-secret", ResourceClass.MODEL_GLB).hashCode())

        val requestText = request.toString()
        assertTrue(requestText.contains("MODEL_GLB"))
        assertTrue(requestText.contains("-1"))
        assertTrue(requestText.contains("ifNoneMatchPresent=true"))
        assertTrue(requestText.contains("ifModifiedSincePresent=true"))
        assertTrue(requestText.contains("acceptPresent=true"))
        assertRedacted(
            requestText,
            "signed-url-secret",
            "validator-secret",
            "timestamp-secret",
            "accept-secret",
            "locator",
        )
        assertRedacted(
            responseMetadata.toString(),
            "content-type-secret",
            "validator-secret",
            "timestamp-secret",
            "-1",
        )
        assertRedacted(
            storedMetadata.toString(),
            "content-type-secret",
            "validator-secret",
            "timestamp-secret",
            "-1",
        )
        assertRedacted(rawKey.toString(), "stable-id-secret")
    }

    @Test
    fun byteArrayGettersReturnFreshCopiesEvenWhenEmpty() {
        val response = TransportResponse(200, byteArrayOf())
        val stored = StoredRawResource(byteArrayOf(), "bad", StoredRawResourceMetadata(storedAtEpochMillis = -1))

        assertFalse(response.body === response.body)
        assertFalse(stored.bytes === stored.bytes)
        assertContentEquals(byteArrayOf(), response.body)
        assertContentEquals(byteArrayOf(), stored.bytes)
    }

    private fun assertRedacted(text: String, vararg sensitiveValues: String) {
        sensitiveValues.forEach { sensitiveValue ->
            assertFalse(text.contains(sensitiveValue), "text leaked $sensitiveValue: $text")
        }
    }
}
