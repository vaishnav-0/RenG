package com.rohittp.reng.internal.identity

import com.rohittp.reng.AltitudeMode
import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.Backdrop
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

        // Unchanged by Cycle E-terrain, and that is the assertion rather than an accident: the
        // altitude mode lives inside a Placement and a Geometry, so a plan whose three drawn-thing
        // lists are all empty carries none of it and keeps the identity `0.3.0` published.
        assertEquals(155, encoded.identity.canonicalBytes.size)
        assertEquals(
            "524e474301010001000000080000000000000000000200000046" +
                "00010000000800000000000000000002000000080000000000000000" +
                "00030000000800000000000000000004000000080000000000000000" +
                "0005000000080000000000000000000300000002000100040000000101" +
                "000500000004000000000006000000040000000000070000000400000000" +
                // Tag 8, one-byte payload, boolean true: drawLabels defaults on.
                "00080000000101" +
                // Tag 9, one-byte payload, the absent marker: ADR 0068's backdrop, which a minimal
                // frame does not carry. Every frame encodes the segment; only its payload differs.
                "00090000000100",
            encoded.identity.canonicalBytes.fixtureLowercaseHex(),
        )
        assertEquals(
            "reng-frame-v1:af1da33e03587444276761bfe2c0b057e41376d8a67955ba8f799b6fca0c0360",
            encoded.frameIdentityText(),
        )
        assertEquals(allSegments.size, encoded.segmentPayloads.size)
    }

    @Test
    fun representativeFrameMatchesTrackedCanonicalBytesAndIdentityExactly() {
        val encoded = encoder.encode(canonicalV1RepresentativeFramePlan())
        val expectedBytes = CANONICAL_V1_REPRESENTATIVE_HEX.canonicalFixtureHexToByteArray()

        // 1,478 before Cycle E-terrain, plus 48: six objects carrying an altitude mode -- four
        // Placements (two stickers, two models) and two Geometries -- each paying one 8-byte field.
        // Plus 7 for ADR 0068's backdrop segment, which every frame encodes and this one leaves
        // absent: a two-byte tag, a four-byte length and the one-byte absent marker.
        assertEquals(1_533, expectedBytes.size)
        assertEquals(1_533, encoded.identity.canonicalBytes.size)
        assertContentEquals(expectedBytes, encoded.identity.canonicalBytes.bytes)
        assertEquals(
            "reng-frame-v1:39aaf36d3b3316faf0a3b45c95c66e77da358853ef1c3e1e44eb3e60ce7b64a0",
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
            FramePlanSegment.BACKDROP to representativeFieldsPlan(
                backdrop = Backdrop.Pattern(ResourceLocator("patterns/grid.png")),
            ),
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

    /**
     * Two plans differing only in an altitude mode must be two different frames, or a
     * `GROUND_RELATIVE` frame is served the cached `ABSOLUTE` one.
     *
     * **Both directions, deliberately.** Asserting only the inequality would pass against an encoder
     * that hashed something incidental; asserting only the equality would pass against an encoder
     * that ignored the field entirely.
     *
     * **And the last line's plan omits both arguments outright**, which is the only way it says
     * anything about ADR 0040's default. A helper that passes `ABSOLUTE` explicitly exercises the
     * *helper's* default; [defaultedAltitudeModePlan] writes neither argument, so flipping either
     * public default moves this identity.
     */
    @Test
    fun anAltitudeModeOnEitherTypeChangesTheFrameIdentityAndTheDefaultIsAbsolute() {
        val stickerAbsolute = altitudeModePlan(AltitudeMode.ABSOLUTE, AltitudeMode.ABSOLUTE)
        val stickerGround = altitudeModePlan(AltitudeMode.GROUND_RELATIVE, AltitudeMode.ABSOLUTE)
        val geometryGround = altitudeModePlan(AltitudeMode.ABSOLUTE, AltitudeMode.GROUND_RELATIVE)

        assertNotEquals(frameIdentityOf(stickerAbsolute), frameIdentityOf(stickerGround))
        assertNotEquals(frameIdentityOf(stickerAbsolute), frameIdentityOf(geometryGround))
        assertNotEquals(frameIdentityOf(stickerGround), frameIdentityOf(geometryGround))

        assertEquals(
            frameIdentityOf(stickerAbsolute),
            frameIdentityOf(altitudeModePlan(AltitudeMode.ABSOLUTE, AltitudeMode.ABSOLUTE)),
        )
        assertEquals(frameIdentityOf(stickerAbsolute), frameIdentityOf(defaultedAltitudeModePlan()))
        assertEquals(
            frameIdentityOf(stickerGround),
            frameIdentityOf(altitudeModePlan(AltitudeMode.GROUND_RELATIVE, AltitudeMode.ABSOLUTE)),
        )
    }

    /**
     * The case that separates the two fields, which "four distinct identities" cannot.
     *
     * An encoder that had swapped the two — writing the geometry's mode into the placement's field
     * and the placement's into the geometry's — still produces four distinct digests for the four
     * pairings, because a permutation of four values is still four values. What it *cannot* do is
     * keep the change inside the right segment: a sticker whose placement changed would move the
     * `GEOMETRIES` payload instead of the `STICKERS` one. So this asserts the segment, and then the
     * exact tag and wire value in bytes — tag 7 for a `Placement`, tag 6 for a `Geometry`, which are
     * the next free tags in two tables ADR 0018 calls permanent.
     */
    @Test
    fun eachTypesAltitudeModeMovesOnlyItsOwnSegmentAtItsOwnTag() {
        val base = encoder.encode(altitudeModePlan())
        val stickerGround = encoder.encode(
            altitudeModePlan(placementMode = AltitudeMode.GROUND_RELATIVE),
        )
        val geometryGround = encoder.encode(
            altitudeModePlan(geometryMode = AltitudeMode.GROUND_RELATIVE),
        )

        assertEquals(listOf(FramePlanSegment.STICKERS), changedSegments(base, stickerGround))
        assertEquals(listOf(FramePlanSegment.GEOMETRIES), changedSegments(base, geometryGround))

        val stickerHex = { plan: EncodedFramePlan ->
            plan.segmentPayloads[FramePlanSegment.STICKERS.index].fixtureLowercaseHex()
        }
        val geometryHex = { plan: EncodedFramePlan ->
            plan.segmentPayloads[FramePlanSegment.GEOMETRIES.index].fixtureLowercaseHex()
        }
        assertTrue(stickerHex(base).contains("0007000000020001"))
        assertFalse(stickerHex(base).contains("0007000000020002"))
        assertTrue(stickerHex(stickerGround).contains("0007000000020002"))
        assertTrue(geometryHex(base).contains("0006000000020001"))
        assertFalse(geometryHex(base).contains("0006000000020002"))
        assertTrue(geometryHex(geometryGround).contains("0006000000020002"))
    }

    /**
     * And all four pairings of the two modes are four distinct frames, the same shape the
     * `drawBasemap`/`drawLabels` case takes — an encoder that folded the two fields into one, or
     * dropped either, would collapse them to two.
     */
    @Test
    fun allFourAltitudeModePairingsGetDistinctFrameIdentities() {
        val digests = AltitudeMode.entries.flatMap { placementMode ->
            AltitudeMode.entries.map { geometryMode ->
                frameIdentityOf(altitudeModePlan(placementMode, geometryMode)).digest
            }
        }

        assertEquals(4, digests.size)
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
        assertEquals(9, before.segmentPayloads.size)
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
        backdrop: Backdrop? = null,
    ): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = camera,
        projectionMode = projectionMode,
        drawBasemap = drawBasemap,
        drawLabels = drawLabels,
        stickers = stickers,
        models = models,
        geometries = geometries,
        backdrop = backdrop,
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

    private fun changedSegments(
        base: EncodedFramePlan,
        other: EncodedFramePlan,
    ): List<FramePlanSegment> = allSegments.filterIndexed { index, _ ->
        base.segmentPayloads[index] != other.segmentPayloads[index]
    }

    /**
     * The same plan as [altitudeModePlan]'s default, with the two altitude-mode arguments **not
     * written at all** — so it is RenG's defaults that decide its bytes, not this file's.
     */
    private fun defaultedAltitudeModePlan(): FramePlan = FramePlan(
        frameIndex = 0,
        camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
        stickers = listOf(
            Sticker(
                placement = Placement(
                    AnchoringMode.MAP,
                    Vector3(0.0, 0.0, 0.0),
                    AnchoringMode.MAP,
                    Vector3(0.0, 0.0, 0.0),
                    AnchoringMode.MAP,
                    1.0,
                ),
                image = ResourceLocator("sticker"),
            ),
        ),
        geometries = listOf(
            Geometry(
                topLeft = Vector3(1.0, 0.0, 0.0),
                bottomRight = Vector3(0.0, 1.0, 0.0),
                shaderPair = ShaderPair("vertex", "fragment"),
            ),
        ),
    )

    /**
     * One sticker and one geometry, each carrying a mode chosen by the caller — and the sticker
     * rather than a model only because a `Placement` encodes identically wherever it sits. That the
     * models' placements carry the field too is what the representative fixture's 1,526 bytes says:
     * four placements and two geometries at eight bytes each, not two and two.
     */
    private fun altitudeModePlan(
        placementMode: AltitudeMode = AltitudeMode.ABSOLUTE,
        geometryMode: AltitudeMode = AltitudeMode.ABSOLUTE,
    ): FramePlan = FramePlan(
        frameIndex = 0,
        camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
        stickers = listOf(
            Sticker(
                placement = Placement(
                    AnchoringMode.MAP,
                    Vector3(0.0, 0.0, 0.0),
                    AnchoringMode.MAP,
                    Vector3(0.0, 0.0, 0.0),
                    AnchoringMode.MAP,
                    1.0,
                    placementMode,
                ),
                image = ResourceLocator("sticker"),
            ),
        ),
        geometries = listOf(
            Geometry(
                topLeft = Vector3(1.0, 0.0, 0.0),
                bottomRight = Vector3(0.0, 1.0, 0.0),
                shaderPair = ShaderPair("vertex", "fragment"),
                altitudeMode = geometryMode,
            ),
        ),
    )

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
            FramePlanSegment.BACKDROP,
        )
    }
}

/** ADR 0071: the two backdrop cases in the canonical encoding. */
class BackdropCanonicalEncodingTest {
    private val encoder = FramePlanCanonicalEncoder()

    /**
     * Tags 1 and 2 stay the pattern's and the shader takes 3 upward, so which tags are present is
     * the discriminator. The payoff, and the reason this is asserted rather than assumed: a plan
     * carrying a pattern encodes exactly as it did before the shader case existed -- tag `0001`
     * first, the same image and repeat behind it -- so no Frame Identity moved and no cache was
     * invalidated by the upgrade.
     */
    @Test
    fun aPatternKeepsTagsOneAndTwoAndAShaderTakesThreeUpward() {
        val pattern = encoder.encode(planWith(Backdrop.Pattern(ResourceLocator("p.png"), 128.0)))
            .segmentPayloads[FramePlanSegment.BACKDROP.index].fixtureLowercaseHex()
        val shader = encoder.encode(planWith(shaderBackdrop()))
            .segmentPayloads[FramePlanSegment.BACKDROP.index].fixtureLowercaseHex()

        // A present optional, then the first field header: tag 1 for a pattern, tag 3 for a shader.
        assertTrue(pattern.startsWith("010001"), pattern)
        assertTrue(shader.startsWith("010003"), shader)
        assertFalse(pattern.startsWith("010003"), pattern)
    }

    /** Two backdrops that differ at all are two frames, so neither can serve the other's cache. */
    @Test
    fun everyDistinctBackdropReachesADistinctFrameIdentity() {
        val identities = listOf(
            null,
            Backdrop.Pattern(ResourceLocator("p.png"), 128.0),
            Backdrop.Pattern(ResourceLocator("p.png"), 256.0),
            Backdrop.Pattern(ResourceLocator("q.png"), 128.0),
            shaderBackdrop(),
            shaderBackdrop(uniforms = mapOf("uTint" to ShaderValue.Scalar(2f))),
        ).map { encoder.encode(planWith(it)).identity.digest.lowercaseHex }

        assertEquals(identities.size, identities.toSet().size, identities.toString())
    }

    private fun shaderBackdrop(
        uniforms: Map<String, ShaderValue> = mapOf("uTint" to ShaderValue.Scalar(1f)),
    ): Backdrop.Shader = Backdrop.Shader(
        shaderPair = ShaderPair(
            vertexSource = "#version 300 es\nvoid main() {}\n",
            fragmentSource = "#version 300 es\nvoid main() {}\n",
        ),
        uniforms = uniforms,
    )

    private fun planWith(backdrop: Backdrop?): FramePlan =
        FramePlan(frameIndex = 1L, camera = Camera(1.0, 2.0, 3.0, 4.0, 5.0), backdrop = backdrop)
}

private val FramePlanSegment.index: Int
    get() = tag - 1
