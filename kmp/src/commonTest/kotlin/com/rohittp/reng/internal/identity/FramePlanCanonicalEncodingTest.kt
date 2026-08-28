package com.rohittp.reng.internal.identity

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.Model
import com.rohittp.reng.Placement
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Sticker
import com.rohittp.reng.Vector3
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FramePlanCanonicalEncodingTest {
    private val encoder = FramePlanCanonicalEncoder()

    @Test
    fun minimalFrameHasExactCanonicalLengthAndIdentity() {
        val encoded = encoder.encode(canonicalV1MinimalFramePlan())

        assertEquals(148, encoded.identity.canonicalBytes.size)
        assertEquals(
            "524e474301010001000000080000000000000000000200000046" +
                "00010000000800000000000000000002000000080000000000000000" +
                "00030000000800000000000000000004000000080000000000000000" +
                "0005000000080000000000000000000300000002000100040000000101" +
                "000500000004000000000006000000040000000000070000000400000000" +
                // Tag 8, one-byte payload, boolean true: drawLabels defaults on.
                "00080000000101",
            encoded.identity.canonicalBytes.fixtureLowercaseHex(),
        )
        assertEquals(
            "reng-frame-v1:64af9745146dd73c1b25f4c4fea53fc7526188c3e3fec2871ca53d7dbf0f3f5b",
            encoded.frameIdentityText(),
        )
        assertEquals(allSegments.size, encoded.segmentPayloads.size)
    }

    @Test
    fun representativeFrameMatchesTrackedCanonicalBytesAndIdentityExactly() {
        val encoded = encoder.encode(canonicalV1RepresentativeFramePlan())
        val expectedBytes = CANONICAL_V1_REPRESENTATIVE_HEX.canonicalFixtureHexToByteArray()

        assertEquals(1_478, expectedBytes.size)
        assertEquals(1_478, encoded.identity.canonicalBytes.size)
        assertContentEquals(expectedBytes, encoded.identity.canonicalBytes.bytes)
        assertEquals(
            "reng-frame-v1:3bda735baccbc58063f88eb1853f14ea565d7b480d308910896b6610f0979ee5",
            encoded.frameIdentityText(),
        )
    }

    @Test
    fun eachTopLevelFieldChangesOnlyItsOwnCanonicalSegment() {
        val base = representativeFieldsPlan()
        val variants = listOf(
            FramePlanSegment.FRAME_INDEX to representativeFieldsPlan(frameIndex = 8),
            FramePlanSegment.CAMERA to representativeFieldsPlan(camera = Camera(1.0, 2.0, 3.5, 4.0, 5.0)),
            FramePlanSegment.PROJECTION_MODE to representativeFieldsPlan(projectionMode = ProjectionMode.GLOBE),
            FramePlanSegment.DRAW_BASEMAP to representativeFieldsPlan(drawBasemap = false),
            FramePlanSegment.STICKERS to representativeFieldsPlan(stickers = listOf(sticker("sticker-b"))),
            FramePlanSegment.MODELS to representativeFieldsPlan(models = listOf(model("model-b", null))),
            FramePlanSegment.GEOMETRIES to representativeFieldsPlan(geometries = listOf(geometry("vertex-b"))),
            FramePlanSegment.DRAW_LABELS to representativeFieldsPlan(drawLabels = false),
        )
        val baseEncoded = encoder.encode(base)

        variants.forEach { (expectedChanged, variant) ->
            val encoded = encoder.encode(variant)
            val changed = allSegments.filterIndexed { index, _ ->
                baseEncoded.segmentPayloads[index] != encoded.segmentPayloads[index]
            }
            assertEquals(listOf(expectedChanged), changed)
            assertNotEquals(baseEncoded.identity, encoded.identity)
        }
    }

    /**
     * The shape that actually discriminates. A plan carrying `drawLabels = false` round-trips through
     * this encoder whether or not the encoder reads the field at all, because the other seven segments
     * encode either way — so this asserts the **difference** instead: all four `drawBasemap` ×
     * `drawLabels` pairings must reach four distinct Frame Identities. An encoder that dropped
     * `drawLabels` would collapse them to two, and one that folded the two flags into a single byte
     * (`&&`, `||`, or either flag alone) would collide a pair no matter which fold it chose.
     */
    @Test
    fun allFourDrawBasemapAndDrawLabelsPairingsGetDistinctFrameIdentities() {
        val pairings = listOf(false to false, false to true, true to false, true to true)
        val digests = pairings.map { pairing ->
            frameIdentityOf(
                FramePlan(
                    frameIndex = 0,
                    camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
                    drawBasemap = pairing.first,
                    drawLabels = pairing.second,
                ),
            ).digest
        }

        assertEquals(4, digests.distinct().size)
    }

    @Test
    fun orderedListsPreserveOrderAndDuplicateOccurrences() {
        val first = sticker("a")
        val second = sticker("b")
        val orderedWithDuplicate = encoder.encode(simplePlan(stickers = listOf(first, second, first)))
        val reorderedWithDuplicate = encoder.encode(simplePlan(stickers = listOf(first, first, second)))
        val duplicateRemoved = encoder.encode(simplePlan(stickers = listOf(first, second)))

        assertNotEquals(orderedWithDuplicate.identity, reorderedWithDuplicate.identity)
        assertNotEquals(orderedWithDuplicate.identity, duplicateRemoved.identity)
        assertNotEquals(
            orderedWithDuplicate.segmentPayloads[FramePlanSegment.STICKERS.index],
            reorderedWithDuplicate.segmentPayloads[FramePlanSegment.STICKERS.index],
        )
        assertNotEquals(
            orderedWithDuplicate.segmentPayloads[FramePlanSegment.STICKERS.index],
            duplicateRemoved.segmentPayloads[FramePlanSegment.STICKERS.index],
        )
    }

    @Test
    fun selectorKindsAndOptionalLocatorUseExactNestedPayloadRules() {
        val tracks = listOf(
            AnimationTrack(AnimationSelector.Index(7), 1.0),
            AnimationTrack(AnimationSelector.Name("7"), 2.0),
        )
        val present = encoder.encode(
            simplePlan(models = listOf(model("model", ResourceLocator("é"), tracks))),
        ).segmentPayloads[FramePlanSegment.MODELS.index].fixtureLowercaseHex()
        val absent = encoder.encode(
            simplePlan(models = listOf(model("model", null))),
        ).segmentPayloads[FramePlanSegment.MODELS.index].fixtureLowercaseHex()

        assertTrue(present.contains("00030000000301c3a9"))
        assertFalse(present.contains("0003000000070100000002c3a9"))
        assertTrue(absent.contains("00030000000100"))
        assertTrue(present.contains("00010000000200010002000000080000000000000007"))
        assertTrue(present.contains("000100000002000200020000000137"))
    }

    @Test
    fun negativeZeroCanonicalizesWhileNfcAndNfdRemainDistinct() {
        val negativeZero = encoder.encode(
            simplePlan(
                camera = Camera(-0.0, -0.0, -0.0, -0.0, -0.0),
                stickers = listOf(sticker("é")),
            ),
        )
        val positiveZero = encoder.encode(
            simplePlan(
                camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
                stickers = listOf(sticker("é")),
            ),
        )
        val nfd = encoder.encode(
            simplePlan(stickers = listOf(sticker("é"))),
        )

        assertEquals(positiveZero, negativeZero)
        assertNotEquals(positiveZero.identity, nfd.identity)
        assertTrue(
            positiveZero.segmentPayloads[FramePlanSegment.STICKERS.index].fixtureLowercaseHex().contains("c3a9"),
        )
        assertTrue(nfd.segmentPayloads[FramePlanSegment.STICKERS.index].fixtureLowercaseHex().contains("65cc81"))
    }

    @Test
    fun publicGetterMutationCannotChangePlanIdentityOrEncodedSnapshots() {
        val plan = canonicalV1RepresentativeFramePlan()
        val before = encoder.encode(plan)
        val returnedSegments = before.segmentPayloads as MutableList<CanonicalBytes>

        (plan.stickers as MutableList<Sticker>).clear()
        val returnedModels = plan.models as MutableList<Model>
        val firstModel = returnedModels.first()
        (firstModel.animationTracks as MutableList<AnimationTrack>).clear()
        returnedModels.clear()
        (plan.geometries as MutableList<Geometry>).clear()
        returnedSegments.clear()
        val returnedBytes = before.identity.canonicalBytes.bytes
        returnedBytes.fill(0)

        val after = encoder.encode(plan)
        assertEquals(before, after)
        assertEquals(8, before.segmentPayloads.size)
        assertContentEquals(CANONICAL_V1_REPRESENTATIVE_HEX.canonicalFixtureHexToByteArray(), before.identity.canonicalBytes.bytes)
    }

    @Test
    fun geometriesDifferingOnlyByAUniformValueGetDifferentFrameIdentities() {
        val base = geometryWith(uniforms = mapOf("uTint" to ShaderValue.Scalar(0.25f)))
        val other = geometryWith(uniforms = mapOf("uTint" to ShaderValue.Scalar(0.75f)))
        assertNotEquals(frameIdentityOf(planWith(base)), frameIdentityOf(planWith(other)))
    }

    @Test
    fun uniformMapIterationOrderDoesNotChangeTheFrameIdentity() {
        val forward = geometryWith(
            uniforms = linkedMapOf("uA" to ShaderValue.Integer(1), "uB" to ShaderValue.Integer(2)),
        )
        val reversed = geometryWith(
            uniforms = linkedMapOf("uB" to ShaderValue.Integer(2), "uA" to ShaderValue.Integer(1)),
        )
        assertEquals(frameIdentityOf(planWith(forward)), frameIdentityOf(planWith(reversed)))
    }

    @Test
    fun texturesDifferingOnlyByLocatorGetDifferentFrameIdentities() {
        val base = geometryWith(textures = mapOf("uMask" to ResourceLocator("a.png")))
        val other = geometryWith(textures = mapOf("uMask" to ResourceLocator("b.png")))
        assertNotEquals(frameIdentityOf(planWith(base)), frameIdentityOf(planWith(other)))
    }

    @Test
    fun textureMapIterationOrderDoesNotChangeTheFrameIdentity() {
        val forward = geometryWith(
            textures = linkedMapOf("uA" to ResourceLocator("a.png"), "uB" to ResourceLocator("b.png")),
        )
        val reversed = geometryWith(
            textures = linkedMapOf("uB" to ResourceLocator("b.png"), "uA" to ResourceLocator("a.png")),
        )
        assertEquals(frameIdentityOf(planWith(forward)), frameIdentityOf(planWith(reversed)))
    }

    @Test
    fun aMat4UniformParticipatesInTheFrameIdentity() {
        val base = geometryWith(
            uniforms = mapOf("uModel" to ShaderValue.Mat4(FloatArray(16) { it.toFloat() })),
        )
        val other = geometryWith(
            uniforms = mapOf("uModel" to ShaderValue.Mat4(FloatArray(16) { -it.toFloat() })),
        )
        assertNotEquals(frameIdentityOf(planWith(base)), frameIdentityOf(planWith(other)))
    }

    @Test
    fun encodedFrameUsesStructuralEqualityHashingAndFreshSegmentLists() {
        val first = encoder.encode(canonicalV1RepresentativeFramePlan())
        val equal = encoder.encode(canonicalV1RepresentativeFramePlan())
        val different = encoder.encode(canonicalV1MinimalFramePlan())

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
        assertFalse(first.segmentPayloads === first.segmentPayloads)
    }

    private fun EncodedFramePlan.frameIdentityText(): String =
        "reng-frame-v1:${identity.digest.lowercaseHex}"

    private fun simplePlan(
        camera: Camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
        stickers: List<Sticker> = emptyList(),
        models: List<Model> = emptyList(),
    ): FramePlan = FramePlan(
        frameIndex = 0,
        camera = camera,
        stickers = stickers,
        models = models,
    )

    private fun representativeFieldsPlan(
        frameIndex: Long = 7,
        camera: Camera = Camera(1.0, 2.0, 3.0, 4.0, 5.0),
        projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
        drawBasemap: Boolean = true,
        drawLabels: Boolean = true,
        stickers: List<Sticker> = listOf(sticker("sticker-a")),
        models: List<Model> = listOf(model("model-a", null)),
        geometries: List<Geometry> = listOf(geometry("vertex-a")),
    ): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = camera,
        projectionMode = projectionMode,
        drawBasemap = drawBasemap,
        drawLabels = drawLabels,
        stickers = stickers,
        models = models,
        geometries = geometries,
    )

    private fun sticker(locator: String): Sticker = Sticker(
        placement = placement(),
        image = ResourceLocator(locator),
    )

    private fun model(
        locator: String,
        texture: ResourceLocator?,
        tracks: List<AnimationTrack> = emptyList(),
    ): Model = Model(
        placement = placement(),
        glb = ResourceLocator(locator),
        texture = texture,
        animationTracks = tracks,
    )

    private fun geometry(vertexSource: String): Geometry = Geometry(
        topLeft = Vector3(1.0, 0.0, 0.0),
        bottomRight = Vector3(0.0, 1.0, 0.0),
        shaderPair = ShaderPair(vertexSource, "fragment"),
    )

    private fun geometryWith(
        uniforms: Map<String, ShaderValue> = emptyMap(),
        textures: Map<String, ResourceLocator> = emptyMap(),
    ): Geometry = Geometry(
        topLeft = Vector3(1.0, 0.0, 0.0),
        bottomRight = Vector3(0.0, 1.0, 0.0),
        shaderPair = ShaderPair("vertex", "fragment"),
        uniforms = uniforms,
        textures = textures,
    )

    private fun planWith(geometry: Geometry): FramePlan = FramePlan(
        frameIndex = 0,
        camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
        geometries = listOf(geometry),
    )

    private fun frameIdentityOf(plan: FramePlan): HashedCanonicalBytes = encoder.encode(plan).identity

    private fun placement(): Placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(0.0, 0.0, 0.0),
        rotationMode = AnchoringMode.MAP,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.MAP,
        scale = 1.0,
    )

    private companion object {
        val allSegments: List<FramePlanSegment> = listOf(
            FramePlanSegment.FRAME_INDEX,
            FramePlanSegment.CAMERA,
            FramePlanSegment.PROJECTION_MODE,
            FramePlanSegment.DRAW_BASEMAP,
            FramePlanSegment.STICKERS,
            FramePlanSegment.MODELS,
            FramePlanSegment.GEOMETRIES,
            FramePlanSegment.DRAW_LABELS,
        )
    }
}

private val FramePlanSegment.index: Int
    get() = tag - 1
