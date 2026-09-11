package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The half of [BasemapEngineHostTest] that genuinely rasterizes, which is why it lives here rather than
 * in `commonTest`: Rentile rasterizes through Skia, and this project's `androidHostTest` runtime resolves
 * `org.jetbrains.skiko:skiko`'s API without its native library — Rentile adds `skiko-awt-runtime-<host>`
 * only to its own JVM/Android test source sets, never to what it publishes — so `Image.makeFromEncoded`
 * there fails with `LibraryLoadException` and every rasterizing assertion would be vacuous. Kotlin/Native
 * links Skia in, so this runs for real on `macosArm64Test` (Apple CI) and `linuxX64Test` (Ubuntu CI).
 */
class BasemapEngineRenderTest {

    @Test
    fun rendersOverAnAlreadyPreparedBatchWithoutAnyFurtherAdapterCall() = runTest {
        val transport = CountingHostTransport()
        val store = CountingHostStore()
        val host = basemapEngineHost(transport = transport, store = store)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val prepared = host.prepareTiles(style, listOf(HOST_RASTER_TILE))
                prepared.use {
                    val exchangesAfterPreparation =
                        transport.executeCalls + store.readCalls + store.writeCalls
                    assertTrue(exchangesAfterPreparation > 0, "preparation must genuinely acquire the tile")

                    val rendered = host.renderTiles(prepared, asRawPixels = false)

                    assertEquals(
                        exchangesAfterPreparation,
                        transport.executeCalls + store.readCalls + store.writeCalls,
                        "rendering an already-prepared batch is network-free and store-free",
                    )
                    val tile = rendered.single()
                    assertEquals(HOST_RASTER_TILE, tile.tile)
                    assertEquals(ResourceKind.BASEMAP_TILE, tile.key.kind)
                    assertEquals(
                        host.renderedTileKey(style, HOST_RASTER_TILE),
                        tile.key,
                        "a rendered tile carries RenG's own canonical identity",
                    )
                    // Not merely non-empty: these are RenG's first rendered basemap pixels, and the
                    // cheapest way to claim they are a PNG rather than any non-empty byte array is the
                    // 8-byte signature the format mandates.
                    val encoded = assertIs<BasemapTilePixels.Encoded>(tile.pixels, "asRawPixels = false encodes")
                    assertTrue(encoded.pngBytes.size > PNG_SIGNATURE.size, "the engine produced encoded ground pixels")
                    assertContentEquals(
                        PNG_SIGNATURE,
                        encoded.pngBytes.take(PNG_SIGNATURE.size).toByteArray(),
                        "the encoded bytes carry the PNG signature",
                    )
                    assertTrue(tile.contentKey.isNotEmpty(), "Rentile's own content key is stored beside them")
                    assertEquals(emptyList(), tile.substitutions, "tile substitution stays disabled")
                }
            }
        } finally {
            host.close()
        }
    }

    /**
     * The same batch rendered both ways: identical identity, identical provenance, different bytes.
     *
     * This is ADR 0044's central claim made checkable — `renderRaw` is "the same drawing with only
     * the encode skipped" — and the part that matters is `contentKey`. RenG derives its own
     * `ResourceKey` from the style digest and the canonical tile, so if the engine's content key
     * moved between the two forms, a tile would still resolve to one texture while meaning two
     * different pictures. Asserting the keys match is what makes the fallback safe to take at any
     * time, including mid-session when the budget crosses.
     */
    @Test
    fun rawAndEncodedRendersOfOneBatchAgreeOnIdentityAndDifferOnlyInTheirBytes() = runTest {
        val host = basemapEngineHost(transport = CountingHostTransport(), store = CountingHostStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                host.prepareTiles(style, listOf(HOST_RASTER_TILE)).use { prepared ->
                    val encodedTile = host.renderTiles(prepared, asRawPixels = false).single()
                    val rawTile = host.renderTiles(prepared, asRawPixels = true).single()

                    assertEquals(encodedTile.key, rawTile.key, "RenG's own identity does not depend on the form")
                    assertEquals(encodedTile.tile, rawTile.tile)
                    assertEquals(
                        encodedTile.contentKey,
                        rawTile.contentKey,
                        "the engine's content key is the same picture either way",
                    )

                    val raw = assertIs<BasemapTilePixels.Raw>(rawTile.pixels, "asRawPixels = true yields pixels")
                    assertEquals(
                        raw.widthPx * raw.heightPx * 4,
                        raw.rgba.size,
                        "raw pixels are tightly packed RGBA8 with no row padding",
                    )
                    // The whole point of the budget: raw is far larger than encoded for the same tile.
                    val encoded = assertIs<BasemapTilePixels.Encoded>(encodedTile.pixels)
                    assertTrue(
                        raw.rgba.size > encoded.pngBytes.size,
                        "raw pixels cost more than their encoding, which is why ADR 0044 bounds them",
                    )
                }
            }
        } finally {
            host.close()
        }
    }
}

/** The 8 bytes every PNG datastream begins with (RFC 2083 section 3.1). */
private val PNG_SIGNATURE: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
