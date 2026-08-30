package com.rohittp.reng.internal.driver

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.resource.ContentProvenance
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceClassGate
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClassGateRunnerTest {
    private val defaultRunner: ClassGateRunner = RenGClassGateRunner(ResourceLimits())

    private suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome =
        defaultRunner.run(gate, content)

    @Test
    fun runsRenGsOwnGatesForItsOwnThreeClasses() = runTest {
        assertEquals(SuppliedValidationOutcome.Valid, run(ResourceClassGate.DECODE_PNG, stickerContent(validPng)))
        assertEquals(SuppliedValidationOutcome.Valid, run(ResourceClassGate.PARSE_GLB, modelContent(validGlb)))
        assertEquals(
            SuppliedValidationOutcome.Valid,
            run(ResourceClassGate.VALIDATE_GLB_FEATURES, modelContent(validGlb)),
        )
    }

    @Test
    fun separatesMalformedFromUnsupportedForFreshContent() = runTest {
        assertIs<SuppliedValidationOutcome.Failed>(run(ResourceClassGate.DECODE_PNG, stickerContent(corruptPng)))
        assertIs<SuppliedValidationOutcome.Failed>(run(ResourceClassGate.DECODE_PNG, stickerContent(interlacedPng)))
        // The distinction is in the code the failure carries, which Cycle B maps:
        // decode gates to RESOURCE_DECODE_FAILED, feature gates to UNSUPPORTED_RESOURCE_FEATURE.
    }

    @Test
    fun enforcesTheDecodedCeilingFromHeaderDimensions() = runTest {
        val runner = RenGClassGateRunner(ResourceLimits(maximumDecodedImageBytes = 1024L))
        assertIs<SuppliedValidationOutcome.Failed>(runner.run(ResourceClassGate.DECODE_PNG, stickerContent(large4096Png)))
    }


    @Test
    fun aFailedGateOnStoredContentReportsStoreIntegrityWhicheverGateFailed() = runTest {
        // Cycle B's rule: STORE provenance collapses every gate failure into one code, because
        // both a corrupt and an unsupported stored record mean the record cannot be trusted. That
        // collapsing happens downstream in ResourceOperationStateMachine.classGateFailure, keyed off
        // content.provenance; this runner itself stays provenance-agnostic and just reports whether
        // the gate passed.
        val outcome = run(ResourceClassGate.DECODE_PNG, storedProvenanceContent(corruptPng))
        assertIs<SuppliedValidationOutcome.Failed>(outcome)
    }

    // Not in the brief's literal test list. PARSE_GLB and VALIDATE_GLB_FEATURES are only exercised
    // above on a fixture engineered to pass both -- without a negative case, a mutation that made
    // either gate unconditionally Valid would slip through undetected. This pins a genuine rejection
    // for each: a container whose magic bytes are wrong (PARSE_GLB) and a well-formed container that
    // declares a required extension RenG cannot draw (VALIDATE_GLB_FEATURES).
    @Test
    fun rejectsAMalformedGlbContainerAndAModelDeclaringARequiredExtension() = runTest {
        assertIs<SuppliedValidationOutcome.Failed>(run(ResourceClassGate.PARSE_GLB, modelContent(badMagicGlb)))
        assertEquals(
            SuppliedValidationOutcome.Valid,
            run(ResourceClassGate.PARSE_GLB, modelContent(glbWithRequiredExtension)),
            "a required extension is a supported-feature fault, not a parse fault",
        )
        assertIs<SuppliedValidationOutcome.Failed>(
            run(ResourceClassGate.VALIDATE_GLB_FEATURES, modelContent(glbWithRequiredExtension)),
        )
    }

    // Every gate this runner can be handed is one RenG genuinely owns. There is no engine-validated
    // arm any more -- not a loud `error`, and (the anti-pattern that error existed to prevent) not a
    // silent `Valid` either -- because ResourceClassGate has no member for a check the Rentile engine
    // performs. ResourceOperationOrdinaryCommitTest's
    // ordinaryClassGatesMatchProductionForEveryClassAndNameNoEngineKeyedOne pins the other half: no
    // engine-keyed class produces a gate to hand over in the first place.
    @Test
    fun everyDeclaredGateIsOneRenGItselfPerforms() = runTest {
        assertEquals(
            setOf(
                ResourceClassGate.DECODE_PNG,
                ResourceClassGate.PARSE_GLB,
                ResourceClassGate.VALIDATE_GLB_FEATURES,
            ),
            ResourceClassGate.entries.toSet(),
        )
        ResourceClassGate.entries.forEach { gate ->
            val content = when (gate) {
                ResourceClassGate.DECODE_PNG -> stickerContent(validPng)
                ResourceClassGate.PARSE_GLB, ResourceClassGate.VALIDATE_GLB_FEATURES -> modelContent(validGlb)
            }
            assertEquals(SuppliedValidationOutcome.Valid, run(gate, content), gate.name)
        }
    }
}

// ---- content fixtures --------------------------------------------------------------------------

private fun content(
    resourceClass: ResourceClass,
    bytes: ByteArray,
    provenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
): ResolvedResourceContent {
    val route = ResourceRouteKey(
        accessMode = ResourceAccessMode.NORMAL,
        locator = ResourceLocator("locator-$resourceClass"),
        resourceClass = resourceClass,
        maximumResponseBytes = 128L * 1024L * 1024L,
    )
    val resourceKey = ResourceKey(ResourceKind.EXTERNAL, "a".repeat(64), resourceClass)
    val stored = StoredRawResource(
        bytes = bytes,
        contentDigest = "b".repeat(64),
        metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
    )
    return ResolvedResourceContent(route, resourceKey, stored, provenance)
}

private fun stickerContent(bytes: ByteArray) = content(ResourceClass.STICKER_IMAGE, bytes)

private fun modelContent(bytes: ByteArray) = content(ResourceClass.MODEL_GLB, bytes)

private fun storedProvenanceContent(bytes: ByteArray) =
    content(ResourceClass.STICKER_IMAGE, bytes, ContentProvenance.STORE)

// ---- GLB fixtures -------------------------------------------------------------------------------
//
// GLB chunk framing needs no compression or checksum (unlike PNG), so these are built directly here
// rather than pasted as pre-generated bytes.

private fun buildGlb(json: String): ByteArray {
    val jsonBytes = json.encodeToByteArray()
    val padding = (4 - jsonBytes.size % 4) % 4
    val paddedJson = jsonBytes + ByteArray(padding) { ' '.code.toByte() }
    val jsonChunk = leBytes(paddedJson.size) + leBytes(0x4E4F534A) + paddedJson // "JSON"
    val header = leBytes(0x46546C67) + leBytes(2) + leBytes(GLB_HEADER_BYTES + jsonChunk.size) // "glTF", version 2
    return header + jsonChunk
}

private const val GLB_HEADER_BYTES = 12

private fun leBytes(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 24) and 0xFF).toByte(),
)

// A resolvable single scene, not just a bare asset header: the canonical VALIDATE_GLB_FEATURES gate
// rejects a scene-less document as SCENE_AMBIGUOUS (there is no single default scene to draw), and
// this fixture's job is to exercise RenG's own gates passing, not to accidentally test that rejection.
private val validGlb: ByteArray = buildGlb("""{"asset":{"version":"2.0"},"scenes":[{"nodes":[]}]}""")

private val glbWithRequiredExtension: ByteArray = buildGlb(
    """{"asset":{"version":"2.0"},"extensionsRequired":["KHR_draco_mesh_compression"]}""",
)

private val badMagicGlb: ByteArray = validGlb.copyOfRange(0, GLB_HEADER_BYTES + 8).also { bytes ->
    bytes[0] = 0 // corrupt the "glTF" magic
}

// ---- PNG fixtures ---------------------------------------------------------------------------------
//
// Every fixture below is a real, valid (or deliberately corrupted) PNG generated once via CPython's
// zlib/struct/zlib.crc32 modules and pasted as a byte literal, following the same anti-circularity
// convention PngDecoderTest.kt documents in full: none of this decoder's own code produced these
// bytes. Base64-encoded, matching PngDecoderTest.declared4096Square's style.
//
// 2x2 truecolour (colour type 2) RGB image, a genuine valid PNG.
private val validPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

// Same container shape as validPng, but its IDAT payload is truncated to 4 bytes -- a well-formed
// chunk (correct length and CRC over the truncated payload) whose zlib stream can never inflate to
// the declared raster size, so decodePng must reach PngDecodeResult.Malformed.
private val corruptPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAABElEQVR42mPgKmwFjgAAAABJRU5ErkJggg==",
)

// Same pixels as validPng, but IHDR's interlace byte is 1 -- PngScan.Unsupported(INTERLACE).
private val interlacedPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAGK06rlAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

// IHDR declares 4096x4096 (colour type 2) with no IDAT at all -- decodePng's ceiling check fires
// from the header's declared dimensions alone, before any IDAT is ever read, so admission needs
// nothing past IHDR + IEND.
private val large4096Png: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAEAAAABAACAIAAAB9wbNAAAAAAElFTkSuQmCC",
)

// Two ordinary RGB (colour type 2) images. Mapbox Terrain-RGB and Terrarium are mathematically
// indistinguishable from decoded pixel bytes alone -- both are three eight-bit channels a caller-side
// formula interprets -- so any opaque RGB-sourced image satisfies either encoding.
private val mapboxDem: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mNgZGJmYWVjYOfg5OLmAQABjwBPBqjligAAAABJRU5ErkJggg==",
)

private val terrariumDem: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mNISU1Lz8hkyMrOyc3LBwAiCwTzZSbilwAAAABJRU5ErkJggg==",
)

// A genuine truecolour+alpha (colour type 6) image where one pixel's alpha is 128, not 255 -- alpha
// carrying real data, which neither admitted terrain encoding uses.
private val fourChannelDem: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGklEQVR42mNgZGL+z8LK1sDAzsH5n4ub5z8AGxUDzJcAohgAAAAASUVORK5CYII=",
)
