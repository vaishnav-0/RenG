package com.rohittp.reng.internal.model

import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.internal.glb.GltfAccessor
import com.rohittp.reng.internal.glb.GltfAnimation
import com.rohittp.reng.internal.glb.GltfAnimationChannel
import com.rohittp.reng.internal.glb.GltfAnimationSampler
import com.rohittp.reng.internal.glb.GltfBuffer
import com.rohittp.reng.internal.glb.GltfBufferView
import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.glb.GltfNode
import com.rohittp.reng.internal.glb.GltfParseResult
import com.rohittp.reng.internal.glb.GltfReject
import com.rohittp.reng.internal.glb.parseGltf
import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import com.rohittp.reng.internal.math.DoubleQuaternion
import com.rohittp.reng.internal.math.DoubleVector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FLOAT_COMPONENT_TYPE = 5126

/** The decomposed spelling of `é` -- `e` followed by U+0301 COMBINING ACUTE ACCENT. */
private const val DECOMPOSED_E_ACUTE = "e\u0301"

/** The precomposed spelling of the same grapheme, U+00E9. A normalizing matcher would treat the
 * two as one name; `CONTEXT.md` says RenG performs no normalization at all. */
private const val PRECOMPOSED_E_ACUTE = "\u00e9"

/** Twelve bytes of decoy every fixture places *before* its BIN chunk, so a read that ignored the
 * chunk's own range would produce visibly wrong keyframes rather than plausible ones. */
private val DECOY_PREFIX: ByteArray = floatBytes(listOf(-1f, -2f, -3f))

class ModelAnimationTest {
    @Test
    fun aNameSelectorResolvesByExactUnicodeScalarMatch() {
        // CONTEXT.md: "Names are exact Unicode scalar sequences: RenG performs no normalization."
        // No trimming and no case folding either, so " Walk", "Walk" and "walk" are three names.
        val fixture = Fixture()
        val time = fixture.times(0f)
        val translation = fixture.vec3s(0f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(
                oneChannelAnimation("Walk", time, translation),
                oneChannelAnimation(" Walk", time, translation),
                oneChannelAnimation("walk", time, translation),
                oneChannelAnimation(DECOMPOSED_E_ACUTE, time, translation),
            ),
        )

        assertEquals(resolvedTo(0), resolveAnimationSelectors(document, listOf(named("Walk"))))
        assertEquals(resolvedTo(1), resolveAnimationSelectors(document, listOf(named(" Walk"))))
        assertEquals(resolvedTo(2), resolveAnimationSelectors(document, listOf(named("walk"))))
        assertEquals(resolvedTo(3), resolveAnimationSelectors(document, listOf(named(DECOMPOSED_E_ACUTE))))

        assertEquals(AnimationResolution.Missing, resolveAnimationSelectors(document, listOf(named("Walk "))))
        assertEquals(AnimationResolution.Missing, resolveAnimationSelectors(document, listOf(named("WALK"))))
        assertEquals(
            AnimationResolution.Missing,
            resolveAnimationSelectors(document, listOf(named(PRECOMPOSED_E_ACUTE))),
            "no Unicode normalization: the precomposed spelling names no animation in this catalogue",
        )
    }

    @Test
    fun anIndexSelectorBeyondTheCatalogueIsMissing() {
        val fixture = Fixture()
        val time = fixture.times(0f)
        val translation = fixture.vec3s(0f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(
                oneChannelAnimation("A", time, translation),
                oneChannelAnimation("B", time, translation),
            ),
        )

        assertEquals(resolvedTo(1), resolveAnimationSelectors(document, listOf(indexed(1))))
        assertEquals(AnimationResolution.Missing, resolveAnimationSelectors(document, listOf(indexed(2))))
        assertEquals(
            AnimationResolution.Missing,
            resolveAnimationSelectors(document, listOf(indexed(Long.MAX_VALUE))),
            "an index far past Int range is missing rather than an arithmetic accident",
        )
    }

    @Test
    fun twoSelectorsNamingOneAnimationAreDuplicate() {
        val fixture = Fixture()
        val time = fixture.times(0f)
        val translation = fixture.vec3s(0f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(
                oneChannelAnimation("Walk", time, translation),
                oneChannelAnimation("Run", time, translation),
            ),
        )

        assertEquals(
            AnimationResolution.Duplicate,
            resolveAnimationSelectors(document, listOf(named("Walk"), indexed(0))),
            "a name and an index naming animation 0 are two selectors for one animation",
        )
        assertEquals(
            AnimationResolution.Duplicate,
            resolveAnimationSelectors(document, listOf(indexed(1), indexed(1))),
        )
        assertEquals(
            resolvedTo(1, 0),
            resolveAnimationSelectors(document, listOf(named("Run"), named("Walk"))),
            "distinct animations resolve in track order, not in catalogue order",
        )
        assertEquals(
            AnimationResolution.Resolved(emptyList()),
            resolveAnimationSelectors(document, emptyList()),
        )
    }

    @Test
    fun aPositiveDurationAnimationWrapsTimeByItsDuration() {
        // CONTEXT.md: positive-duration animations sample `timeSeconds % durationSeconds`.
        val fixture = Fixture()
        val times = fixture.times(0f, 1f, 2f)
        val translation = fixture.vec3s(0f, 0f, 0f, 10f, 0f, 0f, 20f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(oneChannelAnimation("Walk", times, translation)),
        )
        val bin = fixture.bin()

        assertEquals(2.0, animationDurationSeconds(document, bin, 0))
        val early = translationAt(document, bin, 0.5)

        assertEquals(DoubleVector3(5.0, 0.0, 0.0), early)
        assertEquals(early, translationAt(document, bin, 2.5), "duration 2.0: t=2.5 must equal t=0.5")
        assertEquals(early, translationAt(document, bin, 4.5), "and so must t=4.5, two whole cycles later")
    }

    @Test
    fun theDurationIsTheLargestInputAcrossEveryOneOfTheAnimationsSamplers() {
        // Not the first sampler's last keyframe and not the last sampler's: the largest of all of
        // them, which is why duration needs the BIN chunk rather than the document alone.
        val fixture = Fixture()
        val shortTimes = fixture.times(0f, 1f)
        val shortTranslation = fixture.vec3s(0f, 0f, 0f, 1f, 0f, 0f)
        val longTimes = fixture.times(0f, 3f)
        val longTranslation = fixture.vec3s(0f, 0f, 0f, 30f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode(), trsNode()),
            animations = listOf(
                GltfAnimation(
                    name = "Walk",
                    samplers = listOf(
                        GltfAnimationSampler(shortTimes, shortTranslation, "LINEAR"),
                        GltfAnimationSampler(longTimes, longTranslation, "LINEAR"),
                    ),
                    channels = listOf(
                        GltfAnimationChannel(sampler = 0, targetNode = 0, targetPath = "translation"),
                        GltfAnimationChannel(sampler = 1, targetNode = 1, targetPath = "translation"),
                    ),
                ),
            ),
        )
        val bin = fixture.bin()

        assertEquals(3.0, animationDurationSeconds(document, bin, 0))
        // t=4 wraps to 1.0 under the real duration and to 0.0 under the first sampler's, and the
        // long sampler's value at those two times differs, so the two answers are told apart.
        assertEquals(
            sampleTracks(document, bin, listOf(track(0, 1.0))),
            sampleTracks(document, bin, listOf(track(0, 4.0))),
            "wrapping uses 3.0, the largest input anywhere in the animation",
        )
        assertEquals(
            DoubleVector3(10.0, 0.0, 0.0),
            sampleTracks(document, bin, listOf(track(0, 4.0)))!!.getValue(1).translation,
        )
    }

    @Test
    fun aZeroDurationAnimationSamplesTimeZero() {
        // CONTEXT.md: "zero-duration animations sample time zero" -- the rule that keeps
        // `timeSeconds % durationSeconds` away from a division by zero.
        val single = Fixture()
        val singleTime = single.times(0f)
        val singleTranslation = single.vec3s(3f, 4f, 5f)
        val singleDocument = single.document(
            nodes = listOf(trsNode()),
            animations = listOf(oneChannelAnimation("Pose", singleTime, singleTranslation)),
        )

        assertEquals(0.0, animationDurationSeconds(singleDocument, single.bin(), 0))
        assertEquals(DoubleVector3(3.0, 4.0, 5.0), translationAt(singleDocument, single.bin(), 7.5))

        // Zero duration does not imply one keyframe: the largest input decides, so keyframes at
        // -1 and 0 are a zero-duration animation whose sample is the keyframe at exactly zero.
        val spanning = Fixture()
        val spanningTimes = spanning.times(-1f, 0f)
        val spanningTranslation = spanning.vec3s(1f, 0f, 0f, 2f, 0f, 0f)
        val spanningDocument = spanning.document(
            nodes = listOf(trsNode()),
            animations = listOf(oneChannelAnimation("Pose", spanningTimes, spanningTranslation)),
        )

        assertEquals(0.0, animationDurationSeconds(spanningDocument, spanning.bin(), 0))
        assertEquals(DoubleVector3(2.0, 0.0, 0.0), translationAt(spanningDocument, spanning.bin(), 5.0))
    }

    @Test
    fun eachSamplerClampsIndependentlyToItsOwnInputRange() {
        // An animation whose first keyframe is at t=10 holds that value from t=0, per the
        // specification, and a sibling sampler starting at t=0 does not. The same holds at the
        // other end: a sampler that finishes early holds its last value to the animation's end.
        val fixture = Fixture()
        val lateTimes = fixture.times(10f, 20f)
        val lateTranslation = fixture.vec3s(1f, 0f, 0f, 2f, 0f, 0f)
        val fullTimes = fixture.times(0f, 20f)
        val fullTranslation = fixture.vec3s(0f, 0f, 0f, 20f, 0f, 0f)
        val earlyTimes = fixture.times(0f, 4f)
        val earlyTranslation = fixture.vec3s(0f, 0f, 0f, 8f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode(), trsNode(), trsNode()),
            animations = listOf(
                GltfAnimation(
                    name = "Walk",
                    samplers = listOf(
                        GltfAnimationSampler(lateTimes, lateTranslation, "LINEAR"),
                        GltfAnimationSampler(fullTimes, fullTranslation, "LINEAR"),
                        GltfAnimationSampler(earlyTimes, earlyTranslation, "LINEAR"),
                    ),
                    channels = listOf(
                        GltfAnimationChannel(sampler = 0, targetNode = 0, targetPath = "translation"),
                        GltfAnimationChannel(sampler = 1, targetNode = 1, targetPath = "translation"),
                        GltfAnimationChannel(sampler = 2, targetNode = 2, targetPath = "translation"),
                    ),
                ),
            ),
        )
        val bin = fixture.bin()
        assertEquals(20.0, animationDurationSeconds(document, bin, 0))

        val sampled = sampleTracks(document, bin, listOf(track(0, 5.0)))!!

        assertEquals(
            DoubleVector3(1.0, 0.0, 0.0),
            sampled.getValue(0).translation,
            "t=5 is before this sampler's first keyframe at t=10, so it holds that keyframe",
        )
        assertEquals(
            DoubleVector3(5.0, 0.0, 0.0),
            sampled.getValue(1).translation,
            "the sibling sampler spans t=0..20 and interpolates a quarter of the way",
        )
        assertEquals(
            DoubleVector3(8.0, 0.0, 0.0),
            sampled.getValue(2).translation,
            "t=5 is past this sampler's last keyframe at t=4, so it holds that keyframe",
        )
    }

    @Test
    fun stepInterpolationTakesTheEarlierKeyframe() {
        val fixture = Fixture()
        val times = fixture.times(0f, 1f, 2f)
        val translation = fixture.vec3s(1f, 0f, 0f, 5f, 0f, 0f, 9f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(oneChannelAnimation("Blink", times, translation, interpolation = "STEP")),
        )
        val bin = fixture.bin()

        assertEquals(
            DoubleVector3(1.0, 0.0, 0.0),
            translationAt(document, bin, 0.5),
            "STEP holds the earlier keyframe; LINEAR would have blended to 3.0 here",
        )
        assertEquals(DoubleVector3(5.0, 0.0, 0.0), translationAt(document, bin, 1.0), "exactly on a keyframe")
        assertEquals(
            DoubleVector3(5.0, 0.0, 0.0),
            translationAt(document, bin, 1.9),
            "and holds it right up to the next keyframe",
        )
    }

    @Test
    fun linearRotationInterpolatesSphericallyRatherThanComponentwise() {
        // 0 and 180 degrees about z, a quarter of the way: slerp gives 45 degrees about z, while a
        // componentwise lerp gives a quaternion that normalizes to about 36.87 degrees instead.
        // A halfway sample would not tell the two apart -- they agree at t = 0.5 -- so this samples
        // off centre on purpose.
        val fixture = Fixture()
        val times = fixture.times(0f, 1f)
        val rotations = fixture.vec4s(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(oneChannelAnimation("Spin", times, rotations, targetPath = "rotation")),
        )
        val bin = fixture.bin()

        val rotation = sampleTracks(document, bin, listOf(track(0, 0.25)))!!.getValue(0).rotation

        assertEquals(0.0, rotation.x, 1e-9)
        assertEquals(0.0, rotation.y, 1e-9)
        assertEquals(sin(PI / 8.0), rotation.z, 1e-6, "sin(22.5 deg): a 45 degree rotation about z")
        assertEquals(cos(PI / 8.0), rotation.w, 1e-6)

        val componentwise = DoubleQuaternion(0.0, 0.0, 0.25, 0.75).normalized()
        assertTrue(
            abs(componentwise.z - rotation.z) > 1e-3,
            "the fixture must actually tell slerp and a componentwise lerp apart",
        )
    }

    @Test
    fun laterTracksWinWhenTwoTracksDriveTheSameNodeAndPath() {
        // CONTEXT.md: "A model applies its animation tracks in list order."
        val fixture = Fixture()
        val time = fixture.times(0f)
        val first = fixture.vec3s(1f, 0f, 0f)
        val second = fixture.vec3s(2f, 0f, 0f)
        val doubled = fixture.vec3s(2f, 2f, 2f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(
                oneChannelAnimation("First", time, first),
                oneChannelAnimation("Second", time, second),
                oneChannelAnimation("Bigger", time, doubled, targetPath = "scale"),
            ),
        )
        val bin = fixture.bin()

        assertEquals(
            DoubleVector3(2.0, 0.0, 0.0),
            sampleTracks(document, bin, listOf(track(0, 0.0), track(1, 0.0)))!!.getValue(0).translation,
        )
        assertEquals(
            DoubleVector3(1.0, 0.0, 0.0),
            sampleTracks(document, bin, listOf(track(1, 0.0), track(0, 0.0)))!!.getValue(0).translation,
            "reversing the list reverses which track wins",
        )

        // Two tracks driving different paths of one node both survive: the later one copies the
        // earlier one's override rather than replacing it wholesale.
        val merged = sampleTracks(document, bin, listOf(track(0, 0.0), track(2, 0.0)))!!.getValue(0)
        assertEquals(DoubleVector3(1.0, 0.0, 0.0), merged.translation)
        assertEquals(DoubleVector3(2.0, 2.0, 2.0), merged.scale)
    }

    @Test
    fun aTrackDrivingOneChannelLeavesTheOtherTwoComponentsAuthored() {
        // NodeTrs' own KDoc: a track driving one channel still produces a complete NodeTrs, by
        // copying the two components it does not drive from the node's authored TRS.
        val fixture = Fixture()
        val times = fixture.times(0f, 1f)
        val rotations = fixture.vec4s(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode(translation = listOf(7.0, 8.0, 9.0), scale = listOf(2.0, 3.0, 4.0))),
            animations = listOf(oneChannelAnimation("Spin", times, rotations, targetPath = "rotation")),
        )

        val sampled = sampleTracks(document, fixture.bin(), listOf(track(0, 0.0)))!!.getValue(0)

        assertEquals(DoubleVector3(7.0, 8.0, 9.0), sampled.translation)
        assertEquals(DoubleVector3(2.0, 3.0, 4.0), sampled.scale)
        assertEquals(DoubleQuaternion(0.0, 0.0, 0.0, 1.0), sampled.rotation)
    }

    @Test
    fun aChannelWithNoTargetNodeIsSkipped() {
        // Legal glTF, and the specification defines it as a no-op.
        val fixture = Fixture()
        val time = fixture.times(0f)
        val translation = fixture.vec3s(5f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode(), trsNode()),
            animations = listOf(
                GltfAnimation(
                    name = "Walk",
                    samplers = listOf(GltfAnimationSampler(time, translation, "LINEAR")),
                    channels = listOf(
                        GltfAnimationChannel(sampler = 0, targetNode = null, targetPath = "translation"),
                        GltfAnimationChannel(sampler = 0, targetNode = 1, targetPath = "translation"),
                    ),
                ),
            ),
        )

        val sampled = sampleTracks(document, fixture.bin(), listOf(track(0, 0.0)))!!

        assertEquals(setOf(1), sampled.keys, "the untargeted channel contributes no override at all")
        assertEquals(DoubleVector3(5.0, 0.0, 0.0), sampled.getValue(1).translation)
    }

    @Test
    fun aNonIncreasingKeyframeInputSequenceIsRejected() {
        // The last of ADR 0028's owed follow-ups, and it belongs here because it needs the bytes:
        // PARSE_GLB compares the two accessor counts and never sees the keyframe times themselves.
        for (badTimes in listOf(listOf(0f, 2f, 1f), listOf(0f, 1f, 1f), listOf(0f, Float.NaN, 2f))) {
            val fixture = Fixture()
            val times = fixture.times(*badTimes.toFloatArray())
            val translation = fixture.vec3s(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f)
            val document = fixture.document(
                nodes = listOf(trsNode()),
                animations = listOf(oneChannelAnimation("Walk", times, translation)),
            )
            val bin = fixture.bin()

            assertNull(animationDurationSeconds(document, bin, 0), "duration over $badTimes")
            assertNull(sampleTracks(document, bin, listOf(track(0, 0.5))), "sample over $badTimes")
        }
    }

    @Test
    fun aNonFiniteKeyframeTimeIsRejected() {
        // An infinity is strictly greater than its predecessor, so the increasing rule lets it
        // through; the fraction between two keyframes then evaluates to inf/inf and every sampled
        // transform downstream is NaN. Refused here, where the cause is still visible.
        val fixture = Fixture()
        val times = fixture.times(0f, Float.POSITIVE_INFINITY)
        val translation = fixture.vec3s(0f, 0f, 0f, 1f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(oneChannelAnimation("Walk", times, translation)),
        )
        val bin = fixture.bin()

        assertNull(animationDurationSeconds(document, bin, 0))
        assertNull(sampleTracks(document, bin, listOf(track(0, 0.5))))
    }

    @Test
    fun aReadOutsideTheDeliveredBinChunkIsRejected() {
        // The one fault no parse gate could have caught: the accessor spans fit the *declared*
        // buffer, and the chunk that actually arrived is shorter.
        val fixture = Fixture()
        val times = fixture.times(0f, 1f)
        val translation = fixture.vec3s(0f, 0f, 0f, 1f, 0f, 0f)
        val document = fixture.document(
            nodes = listOf(trsNode()),
            animations = listOf(oneChannelAnimation("Walk", times, translation)),
        )
        val truncated = BinChunk(ByteArray(4), 0 until 4)

        assertNull(animationDurationSeconds(document, truncated, 0))
        assertNull(sampleTracks(document, truncated, listOf(track(0, 0.5))))
        assertNull(animationDurationSeconds(document, fixture.bin(), 1), "no animation at index 1")
    }

    @Test
    fun aNodeTargetedByAChannelMustCarryTrsRatherThanAMatrix() {
        // The sampler copies from `localNodeTrs(node)!!`, which is `null` for a `matrix` node. That
        // `!!` is safe because PARSE_GLB refuses the combination outright; this pins the sampler's
        // assumption to the gate that guarantees it rather than leaving it implicit.
        assertEquals(GltfReject.ANIMATED_NODE_MATRIX, rejectionOf(animatedMatrixNodeDocument))
        assertIs<GltfParseResult.Parsed>(
            parseGltf(jsonObject(animatedTrsNodeDocument), 1024L, 128),
            "the same document with a TRS node parses, so the rejection is about the matrix",
        )
    }

    // ---- fixture-name plumbing ----

    private val animatedMatrixNodeDocument =
        animatedNodeDocument("""{"matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]}""")

    private val animatedTrsNodeDocument = animatedNodeDocument("""{"translation": [0, 0, 0]}""")

    private fun animatedNodeDocument(node: String) = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 512},
            {"buffer": 0, "byteOffset": 512, "byteLength": 512}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "SCALAR"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"}
          ],
          "nodes": [$node],
          "animations": [
            {
              "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}}],
              "samplers": [{"input": 0, "output": 1, "interpolation": "LINEAR"}]
            }
          ]
        }
    """.trimIndent()

    private fun rejectionOf(json: String): GltfReject =
        assertIs<GltfParseResult.Malformed>(parseGltf(jsonObject(json), 1024L, 128)).reason

    private fun jsonObject(text: String): JsonValue.Obj {
        val bytes = text.encodeToByteArray()
        return assertIs<JsonParse.Parsed>(parseJson(bytes, 0, bytes.size, 64)).value as JsonValue.Obj
    }

    private fun translationAt(document: GltfDocument, bin: BinChunk, timeSeconds: Double): DoubleVector3? =
        sampleTracks(document, bin, listOf(track(0, timeSeconds)))?.getValue(0)?.translation

    private fun sampleTracks(
        document: GltfDocument,
        bin: BinChunk,
        tracks: List<AnimationTrack>,
    ): Map<Int, NodeTrs>? {
        val resolved = assertIs<AnimationResolution.Resolved>(resolveAnimationSelectors(document, tracks))
        return sampleAnimationTracks(document, bin, tracks, resolved)
    }
}

private fun resolvedTo(vararg indices: Int): AnimationResolution = AnimationResolution.Resolved(indices.toList())

private fun named(value: String): AnimationTrack = AnimationTrack(AnimationSelector.Name(value), 0.0)

private fun indexed(value: Long): AnimationTrack = AnimationTrack(AnimationSelector.Index(value), 0.0)

private fun track(animationIndex: Long, timeSeconds: Double): AnimationTrack =
    AnimationTrack(AnimationSelector.Index(animationIndex), timeSeconds)

private fun trsNode(
    translation: List<Double>? = null,
    rotation: List<Double>? = null,
    scale: List<Double>? = null,
): GltfNode = GltfNode(
    children = emptyList(),
    mesh = null,
    skin = null,
    camera = null,
    matrix = null,
    translation = translation,
    rotation = rotation,
    scale = scale,
)

/** One animation, one sampler, one channel on node 0 -- the shape most of these tests need. */
private fun oneChannelAnimation(
    name: String,
    input: Int,
    output: Int,
    targetPath: String = "translation",
    interpolation: String = "LINEAR",
): GltfAnimation = GltfAnimation(
    name = name,
    samplers = listOf(GltfAnimationSampler(input = input, output = output, interpolation = interpolation)),
    channels = listOf(GltfAnimationChannel(sampler = 0, targetNode = 0, targetPath = targetPath)),
)

/**
 * A BIN chunk under construction, plus the accessors and buffer views addressing it. Each [times],
 * [vec3s] or [vec4s] call appends its floats and returns the index of the accessor that reads them
 * back, so a fixture reads as the keyframe data it is rather than as byte arithmetic.
 */
private class Fixture {
    private val payload = ArrayList<Float>()
    private val views = ArrayList<GltfBufferView>()
    private val accessors = ArrayList<GltfAccessor>()

    /** A `SCALAR` float keyframe-time accessor: the one input shape the specification permits. */
    fun times(vararg values: Float): Int = append(values.toList(), "SCALAR", 1)

    /** A `VEC3` output accessor -- a `translation` or `scale` channel's values. */
    fun vec3s(vararg values: Float): Int = append(values.toList(), "VEC3", 3)

    /** A `VEC4` output accessor -- a `rotation` channel's quaternions, in glTF's `[x, y, z, w]`. */
    fun vec4s(vararg values: Float): Int = append(values.toList(), "VEC4", 4)

    fun bin(): BinChunk {
        val bytes = DECOY_PREFIX + floatBytes(payload)
        return BinChunk(bytes, DECOY_PREFIX.size until bytes.size)
    }

    fun document(nodes: List<GltfNode>, animations: List<GltfAnimation>): GltfDocument = GltfDocument(
        accessors = accessors.toList(),
        bufferViews = views.toList(),
        meshes = emptyList(),
        nodes = nodes,
        skins = emptyList(),
        scenes = emptyList(),
        defaultScene = null,
        animations = animations,
        materials = emptyList(),
        images = emptyList(),
        textures = emptyList(),
        samplers = emptyList(),
        extensionsRequired = emptyList(),
        buffers = listOf(GltfBuffer(byteLength = 1L shl 20, uri = null)),
    )

    private fun append(values: List<Float>, type: String, components: Int): Int {
        val byteOffset = payload.size * Float.SIZE_BYTES
        payload += values
        views += GltfBufferView(
            buffer = 0,
            byteOffset = byteOffset.toLong(),
            byteLength = (values.size * Float.SIZE_BYTES).toLong(),
            byteStride = null,
        )
        accessors += GltfAccessor(
            bufferView = views.size - 1,
            byteOffset = 0L,
            componentType = FLOAT_COMPONENT_TYPE,
            count = (values.size / components).toLong(),
            type = type,
            normalized = false,
            sparse = false,
        )
        return accessors.size - 1
    }
}

/** Little-endian, because every published target is (see `internal.gl.littleEndianBytes`). */
private fun floatBytes(values: List<Float>): ByteArray {
    val bytes = ByteArray(values.size * Float.SIZE_BYTES)
    var target = 0
    for (value in values) {
        val bits = value.toRawBits()
        for (byteIndex in 0 until Float.SIZE_BYTES) bytes[target++] = ((bits shr (8 * byteIndex)) and 0xFF).toByte()
    }
    return bytes
}
