package com.rohittp.reng

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cycle K task 2: the gate that stands in for a compiler.
 *
 * Twelve hand-written serializers is twelve places a property can be forgotten, and a forgotten
 * property is not a compile error -- it is a field that silently decodes to its default. **That is
 * the failure mode this file exists to catch, and it drives the fixture's shape**: every value below
 * differs from the default the constructor would supply, so a dropped property round-trips to
 * something the original was not and equality fails. A fixture built from defaults would round-trip
 * perfectly through a serializer that dropped half the type.
 *
 * The key lists are written out by hand from the public API rather than derived from the surrogates'
 * descriptors. Deriving them would be exactly the circular check the project keeps finding: a
 * surrogate missing a property also has a descriptor missing it, and the assertion would pass.
 */
class FramePlanSerializationTest {
    private val json = Json

    private val maximalPlan: FramePlan
        get() {
            val mapPlacement = Placement(
                positionMode = AnchoringMode.MAP,
                position = Vector3(12.5, -37.25, 480.0),
                rotationMode = AnchoringMode.SCREEN,
                rotation = Vector3(15.0, -42.5, 91.25),
                scaleMode = AnchoringMode.MAP,
                scale = 2.75,
                altitudeMode = AltitudeMode.GROUND_RELATIVE,
            )
            return FramePlan(
                frameIndex = 4_294_967_296L,
                camera = Camera(
                    latitude = 46.5,
                    unwrappedLongitude = -1234.75,
                    zoom = 13.25,
                    bearing = 271.5,
                    pitch = 62.25,
                ),
                projectionMode = ProjectionMode.GLOBE,
                drawBasemap = false,
                drawLabels = false,
                stickers = listOf(
                    Sticker(placement = mapPlacement, image = ResourceLocator("sticker://one.png")),
                ),
                models = listOf(
                    Model(
                        placement = mapPlacement,
                        glb = ResourceLocator("model://one.glb"),
                        texture = ResourceLocator("texture://one.png"),
                        animationTracks = listOf(
                            AnimationTrack(AnimationSelector.Name("walk"), 1.5),
                            AnimationTrack(AnimationSelector.Index(7L), 2.25),
                        ),
                    ),
                ),
                geometries = listOf(
                    Geometry(
                        // north-west corner to south-east corner: latitude descends and
                        // longitude ascends, which `Geometry`'s own `init` requires.
                        topLeft = Vector3(2.5, -1.5, 3.5),
                        bottomRight = Vector3(-2.5, 1.5, -3.5),
                        shaderPair = ShaderPair("#version 300 es\nvoid main() {}", "void main() {}"),
                        uniforms = mapOf(
                            "uScalar" to ShaderValue.Scalar(1.5f),
                            "uVec2" to ShaderValue.Vec2(1.5f, 2.5f),
                            "uVec3" to ShaderValue.Vec3(1.5f, 2.5f, 3.5f),
                            "uVec4" to ShaderValue.Vec4(1.5f, 2.5f, 3.5f, 4.5f),
                            "uInteger" to ShaderValue.Integer(11),
                            "uMat4" to ShaderValue.Mat4(FloatArray(16) { it + 0.5f }),
                        ),
                        textures = mapOf("uTexture" to ResourceLocator("geometry://one.png")),
                        altitudeMode = AltitudeMode.GROUND_RELATIVE,
                    ),
                ),
            )
        }

    /**
     * The round trip, over a plan whose every property differs from its default.
     *
     * `FramePlan`, `Model` and `Geometry` all compare by content -- `FramePlan` over its private
     * list snapshots -- so this one assertion reaches every leaf of the tree.
     */
    @Test
    fun aMaximalPlanSurvivesEncodingAndDecodingUnchanged() {
        val original = maximalPlan
        assertEquals(original, json.decodeFromString(FramePlan.serializer(), json.encodeToString(FramePlan.serializer(), original)))
    }

    /**
     * Every property of every type appears in the document, by name.
     *
     * The round trip above is the primary gate, but it can only observe a dropped property through
     * a value that changed. This observes the *absence of the key itself*, which is the more direct
     * statement and the one that fails with a legible message. `Json` omits a property equal to its
     * default, so this doubles as a check that the fixture really is maximal: a key missing here
     * means either the surrogate dropped it or the fixture used a default for it.
     */
    @Test
    fun everyPublicPropertyOfEveryReachableTypeIsWrittenByName() {
        val document = json.encodeToString(FramePlan.serializer(), maximalPlan)
        val expectedKeys = listOf(
            // FramePlan
            "frameIndex", "camera", "projectionMode", "drawBasemap", "drawLabels",
            "stickers", "models", "geometries",
            // Camera
            "latitude", "unwrappedLongitude", "zoom", "bearing", "pitch",
            // Placement
            "positionMode", "position", "rotationMode", "rotation", "scaleMode", "scale",
            "altitudeMode",
            // Vector3
            "x", "y", "z",
            // Sticker
            "placement", "image",
            // Model
            "glb", "texture", "animationTracks",
            // AnimationTrack + AnimationSelector
            "animation", "timeSeconds", "value",
            // Geometry
            "topLeft", "bottomRight", "shaderPair", "uniforms", "textures",
            // ShaderPair
            "vertexSource", "fragmentSource",
            // ShaderValue
            "w", "elements",
        )
        for (key in expectedKeys) {
            assertTrue("\"$key\"" in document, "no `$key` in the encoded plan")
        }
    }

    /**
     * Decoding runs the real constructor, so a document the constructor would refuse is refused.
     *
     * **This is the property the whole surrogate design exists for.** Each case below is a value
     * some `init` block rejects, and each would be perfectly representable in the format: a
     * deserializer that assigned fields directly would produce a `Camera` at zoom 99, a
     * `AnimationTrack` at negative time, or a `Placement` claiming a ground-relative altitude on a
     * screen-anchored position -- objects that would then reach planning and GL unchecked.
     */
    @Test
    fun aDocumentTheConstructorWouldRefuseIsRefusedAtDecoding() {
        val valid = json.encodeToString(FramePlan.serializer(), maximalPlan)
        val refusals = mapOf(
            "zoom beyond the supported range" to valid.replace("\"zoom\":13.25", "\"zoom\":99.0"),
            "negative animation time" to valid.replace("\"timeSeconds\":1.5", "\"timeSeconds\":-1.5"),
            "bearing at a full turn" to valid.replace("\"bearing\":271.5", "\"bearing\":360.0"),
            "negative frame index" to valid.replace("\"frameIndex\":4294967296", "\"frameIndex\":-1"),
            "ground-relative altitude on a screen-anchored position" to
                valid.replace("\"positionMode\":\"MAP\"", "\"positionMode\":\"SCREEN\""),
        )
        for ((name, document) in refusals) {
            assertTrue(document != valid, "the fixture for `$name` did not change the document")
            assertFailsWith<IllegalArgumentException>("`$name` decoded without complaint") {
                json.decodeFromString(FramePlan.serializer(), document)
            }
        }
    }

    /** A plan of nothing but its required fields survives, and writes only what it must. */
    @Test
    fun aMinimalPlanOmitsEveryDefaultedProperty() {
        val minimal = FramePlan(frameIndex = 0L, camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0))
        val document = json.encodeToString(FramePlan.serializer(), minimal)
        assertEquals(minimal, json.decodeFromString(FramePlan.serializer(), document))
        val keys = (json.parseToJsonElement(document) as JsonObject).keys
        assertEquals(setOf("frameIndex", "camera"), keys, "a minimal plan wrote more than it had to")
    }
}

/** ADR 0071: both backdrop cases over the wire, and what an older document still means. */
class BackdropSerializationTest {
    private val json = Json

    @Test
    fun bothCasesRoundTrip() {
        val pattern: Backdrop = Backdrop.Pattern(ResourceLocator("patterns/grid.png"), 128.0)
        val shader: Backdrop = Backdrop.Shader(
            shaderPair = ShaderPair(
                vertexSource = "#version 300 es\nvoid main() {}\n",
                fragmentSource = "#version 300 es\nvoid main() {}\n",
            ),
            uniforms = mapOf("uTint" to ShaderValue.Vec3(0.1f, 0.2f, 0.3f)),
            textures = mapOf("uMask" to ResourceLocator("masks/a.png")),
        )

        assertEquals(pattern, json.decodeFromString<Backdrop>(json.encodeToString(pattern)))
        assertEquals(shader, json.decodeFromString<Backdrop>(json.encodeToString(shader)))
    }

    /**
     * A document written before `Backdrop.Shader` existed carries `image` and nothing else. It must
     * still mean what it meant -- a pattern, at the default repeat -- rather than failing to parse
     * or arriving with a shader half nobody wrote.
     */
    @Test
    fun aDocumentWrittenBeforeTheShaderCaseStillReadsAsAPattern() {
        val decoded = json.decodeFromString<Backdrop>("""{"image":"patterns/grid.png"}""")

        assertEquals(Backdrop.Pattern(ResourceLocator("patterns/grid.png")), decoded)
    }

    /** Neither half, or both, is a document nobody could have written: rejected, not guessed at. */
    @Test
    fun aDocumentCarryingBothHalvesOrNeitherIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<Backdrop>("""{"tileSizeLogicalPixels":64.0}""")
        }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<Backdrop>(
                """{"image":"p.png","shaderPair":{"vertexSource":"v","fragmentSource":"f"}}""",
            )
        }
    }
}
