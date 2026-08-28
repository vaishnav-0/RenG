package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The half of [LabelHandoverTest] that reads the [AcquiredLabelCandidates] itself, which is why it lives
 * here rather than in `commonTest`: `acquireLabelCandidates` ends in Rentile's `GlyphAtlasPacker`, which
 * encodes through Skia -- even for an empty glyph set, whose 1x1 placeholder atlas is still a Skia image
 * -- and this project's `androidHostTest` runtime resolves `org.jetbrains.skiko:skiko`'s API without its
 * native library. Kotlin/Native links Skia in, so this runs for real on `macosArm64Test`,
 * `iosSimulatorArm64Test` and `linuxX64Test`.
 *
 * Nothing here places, collides, uploads or draws anything: this task's scope ends at handing the batch
 * back intact, and these assertions are about what crossed the firewall rather than about what it means.
 */
class LabelHandoverBatchTest {

    /**
     * The handover's own product: two candidates from two label layers, two layer styles, and an atlas
     * whose bytes are a PNG.
     *
     * **This is where "nothing preregistered went unfetched" is measured from RenG's side.** The
     * `commonTest` case can only see what the consumer's `Transport` was asked for; here the route list
     * RenG actually declared is in hand, so the closure claim is checked in the direction that cannot be
     * inferred from the transport alone -- ordering included, since a set comparison cannot tell an
     * ordered list from a set and the fixture spans two font stacks precisely so that it could.
     */
    @Test
    fun handsBackTheBatchOverExactlyTheRoutesItDeclared() = runTest {
        val transport = LabelRecordingTransport()
        val host = basemapEngineHost(transport = transport, store = LabelRecordingStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val acquired = host.acquireLabelHandover(host.labelStyle())

                assertEquals(
                    labelGlyphUrls(),
                    acquired.glyphRoutes.map { it.locator.value },
                    "the second preregistration round declares the closure's three urls, in the engine's order",
                )
                assertEquals(
                    setOf(ResourceClass.BASEMAP_GLYPH_RANGE),
                    acquired.glyphRoutes.map { it.resourceClass }.toSet(),
                    "under RenG's own glyph class, which is what makes the firewall's indices find them",
                )
                assertEquals(
                    acquired.glyphRoutes.map { it.locator.value }.toSet(),
                    transport.requestedGlyphUrls().toSet(),
                    "every url RenG declared was fetched, and every url fetched was one RenG declared",
                )
                assertEquals(listOf(LABEL_TILE), acquired.tiles)

                assertEquals(2, acquired.batch.candidates.size, "one candidate per label layer")
                assertEquals(2, acquired.batch.layerStyles.size)
                assertTrue(
                    acquired.batch.candidates.all { it.layerStyleIndex in acquired.batch.layerStyles.indices },
                    "a candidate names its layer through an index into the batch's own list",
                )
                assertContentEquals(
                    PNG_SIGNATURE,
                    acquired.batch.atlas.pngBytes.take(PNG_SIGNATURE.size).toByteArray(),
                    "the atlas arrives as encoded PNG bytes",
                )
                assertTrue(acquired.batch.atlas.entries.isNotEmpty(), "and carries the glyphs it packed")
            }
        } finally {
            host.close()
        }
    }

    /**
     * The empty closure's batch. Kept beside the case above so the two outcomes are visibly different
     * rather than merely differently named: no candidates, no layer styles, and an atlas that still
     * exists because Skia will not encode a zero-dimension image.
     */
    @Test
    fun handsBackAnEmptyBatchForAStyleThatDeclaresNoGlyphs() = runTest {
        val host = basemapEngineHost(transport = LabelRecordingTransport(), store = LabelRecordingStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val style = host.labelStyle(LABEL_STYLE_JSON_WITHOUT_GLYPHS)
                val acquired = host.acquireLabelHandover(style, glyphTemplate = null)
                assertEquals(emptyList(), acquired.glyphRoutes, "nothing to preregister")
                assertEquals(0, acquired.batch.candidates.size)
                assertEquals(0, acquired.batch.layerStyles.size)
                assertEquals(0, acquired.batch.atlas.entries.size)
            }
        } finally {
            host.close()
        }
    }

    /**
     * The plan is reusable and acquiring from it repeatedly yields equal batches -- Rentile's own claim,
     * and the reason the handover may close it in a `finally` without losing anything. RenG never reuses
     * one, so what this actually pins is the property that makes the *second* handover in one operation
     * cost nothing new: the same routes, already preregistered, are idempotent on the way back in.
     */
    @Test
    fun acquiresTheSameBatchTwiceInOneOperationWithoutARouteCollision() = runTest {
        val transport = LabelRecordingTransport()
        val host = basemapEngineHost(transport = transport, store = LabelRecordingStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val first = host.acquireLabelHandover(host.labelStyle())
                val second = host.acquireLabelHandover(host.labelStyle())
                assertEquals(first.glyphRoutes.map { it.locator.value }, second.glyphRoutes.map { it.locator.value })
                assertEquals(first.batch.contentKey, second.batch.contentKey, "the same bytes yield the same batch")
                assertEquals(
                    labelGlyphUrls().size,
                    transport.requestedGlyphUrls().size,
                    "the second acquisition replays the invocation's latched outcomes, not the consumer",
                )
            }
        } finally {
            host.close()
        }
    }
}

/** The eight bytes the PNG format mandates, so "encoded pixels" is a claim rather than "non-empty". */
private val PNG_SIGNATURE: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
