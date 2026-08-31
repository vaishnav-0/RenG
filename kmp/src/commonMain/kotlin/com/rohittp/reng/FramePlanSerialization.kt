package com.rohittp.reng

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Frame Plan serialization (ADR 0042), in one file, by way of private surrogates.
 *
 * **Every serializer here decodes by calling the real public constructor.** That is the whole
 * design, and it is not a stylistic preference: RenG's public value types validate in `init` --
 * [Vector3] canonicalises and rejects NaN, [Camera] bounds zoom, bearing and pitch, [AnimationTrack]
 * refuses negative time, [Placement] refuses a ground-relative altitude on a screen-anchored
 * position, [Geometry] refuses a uniform named after one of RenG's own shader-interface names. A
 * deserializer that assigned fields directly would mint values the constructor forbids, and those
 * values would then flow into planning and GL having never been checked. Routing through the
 * constructor makes a decoded Frame Plan **exactly as validated as a constructed one**: a malformed
 * document fails with RenG's own `require` message, at the point of decoding, rather than becoming a
 * quietly illegal object.
 *
 * The shape is forced as well as chosen. Six of these classes take primary-constructor *parameters*
 * that are not properties -- [FramePlan] does it to keep private `ArrayList` snapshots and hand out
 * fresh copies on every read -- and the serialization plugin serializes constructor *properties*, so
 * a bare `@Serializable` on them does not compile in the first place.
 *
 * **Every surrogate property carries an explicit [SerialName].** The wire name is then a decision
 * recorded here rather than a consequence of a Kotlin identifier, so renaming a property is not
 * silently a format break. The corresponding hazard is that a *forgotten* property is silently a
 * dropped field, which no compiler catches; `FramePlanSerializationTest`'s round trip over a corpus
 * exercising every property of every type is what stands in for that compiler.
 *
 * **On redaction.** [ResourceLocator] and [ShaderPair] print `<redacted>` from `toString`, and this
 * file deliberately writes their real contents. Those two things are not in tension: redaction
 * exists so a URL bearing a signed token cannot leak into a diagnostic, a log line or an exception
 * message that RenG emits on its own initiative, whereas serializing is the caller explicitly asking
 * for the value back. A document produced here therefore carries resource URLs verbatim and should
 * be treated as carrying whatever those URLs carry.
 */

// ---------------------------------------------------------------------------------------------
// Vector3
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("Vector3")
private class Vector3Surrogate(
    @SerialName("x") val x: Double,
    @SerialName("y") val y: Double,
    @SerialName("z") val z: Double,
)

internal object Vector3Serializer : KSerializer<Vector3> {
    private val delegate = Vector3Surrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Vector3) {
        encoder.encodeSerializableValue(delegate, Vector3Surrogate(value.x, value.y, value.z))
    }
    override fun deserialize(decoder: Decoder): Vector3 {
        val surrogate = decoder.decodeSerializableValue(delegate)
        return Vector3(surrogate.x, surrogate.y, surrogate.z)
    }
}

// ---------------------------------------------------------------------------------------------
// ResourceLocator -- a single string, so it serializes *as* a string rather than as an object with
// one field. A locator is the commonest leaf in a corpus document and the flat form is what makes a
// hand-edited plan readable.
// ---------------------------------------------------------------------------------------------

internal object ResourceLocatorSerializer : KSerializer<ResourceLocator> {
    private val delegate = String.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ResourceLocator) {
        encoder.encodeSerializableValue(delegate, value.value)
    }
    override fun deserialize(decoder: Decoder): ResourceLocator =
        ResourceLocator(decoder.decodeSerializableValue(delegate))
}

// ---------------------------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("Camera")
private class CameraSurrogate(
    @SerialName("latitude") val latitude: Double,
    @SerialName("unwrappedLongitude") val unwrappedLongitude: Double,
    @SerialName("zoom") val zoom: Double,
    @SerialName("bearing") val bearing: Double,
    @SerialName("pitch") val pitch: Double,
)

internal object CameraSerializer : KSerializer<Camera> {
    private val delegate = CameraSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Camera) {
        encoder.encodeSerializableValue(
            delegate,
            CameraSurrogate(
                value.latitude, value.unwrappedLongitude, value.zoom, value.bearing, value.pitch,
            ),
        )
    }
    override fun deserialize(decoder: Decoder): Camera {
        val it = decoder.decodeSerializableValue(delegate)
        return Camera(it.latitude, it.unwrappedLongitude, it.zoom, it.bearing, it.pitch)
    }
}

// ---------------------------------------------------------------------------------------------
// Placement
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("Placement")
private class PlacementSurrogate(
    @SerialName("positionMode") val positionMode: AnchoringMode,
    @SerialName("position") val position: Vector3,
    @SerialName("rotationMode") val rotationMode: AnchoringMode,
    @SerialName("rotation") val rotation: Vector3,
    @SerialName("scaleMode") val scaleMode: AnchoringMode,
    @SerialName("scale") val scale: Double,
    @SerialName("altitudeMode") val altitudeMode: AltitudeMode = AltitudeMode.ABSOLUTE,
)

internal object PlacementSerializer : KSerializer<Placement> {
    private val delegate = PlacementSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Placement) {
        encoder.encodeSerializableValue(
            delegate,
            PlacementSurrogate(
                value.positionMode, value.position, value.rotationMode, value.rotation,
                value.scaleMode, value.scale, value.altitudeMode,
            ),
        )
    }
    override fun deserialize(decoder: Decoder): Placement {
        val it = decoder.decodeSerializableValue(delegate)
        return Placement(
            it.positionMode, it.position, it.rotationMode, it.rotation,
            it.scaleMode, it.scale, it.altitudeMode,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sticker
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("Sticker")
private class StickerSurrogate(
    @SerialName("placement") val placement: Placement,
    @SerialName("image") val image: ResourceLocator,
)

internal object StickerSerializer : KSerializer<Sticker> {
    private val delegate = StickerSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Sticker) {
        encoder.encodeSerializableValue(delegate, StickerSurrogate(value.placement, value.image))
    }
    override fun deserialize(decoder: Decoder): Sticker {
        val it = decoder.decodeSerializableValue(delegate)
        return Sticker(it.placement, it.image)
    }
}

// ---------------------------------------------------------------------------------------------
// AnimationSelector -- a sealed hierarchy, so the format writes a discriminated union. The two
// arms are `Name` and `Index` rather than one nullable-of-each pair, because a selector is exactly
// one of the two and a shape that can express "neither" or "both" would need a runtime check the
// type system already makes unnecessary.
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("AnimationSelector.Name")
private class AnimationSelectorNameSurrogate(@SerialName("value") val value: String)

internal object AnimationSelectorNameSerializer : KSerializer<AnimationSelector.Name> {
    private val delegate = AnimationSelectorNameSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: AnimationSelector.Name) {
        encoder.encodeSerializableValue(delegate, AnimationSelectorNameSurrogate(value.value))
    }
    override fun deserialize(decoder: Decoder): AnimationSelector.Name =
        AnimationSelector.Name(decoder.decodeSerializableValue(delegate).value)
}

@Serializable
@SerialName("AnimationSelector.Index")
private class AnimationSelectorIndexSurrogate(@SerialName("value") val value: Long)

internal object AnimationSelectorIndexSerializer : KSerializer<AnimationSelector.Index> {
    private val delegate = AnimationSelectorIndexSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: AnimationSelector.Index) {
        encoder.encodeSerializableValue(delegate, AnimationSelectorIndexSurrogate(value.value))
    }
    override fun deserialize(decoder: Decoder): AnimationSelector.Index =
        AnimationSelector.Index(decoder.decodeSerializableValue(delegate).value)
}

// ---------------------------------------------------------------------------------------------
// AnimationTrack
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("AnimationTrack")
private class AnimationTrackSurrogate(
    @SerialName("animation") val animation: AnimationSelector,
    @SerialName("timeSeconds") val timeSeconds: Double,
)

internal object AnimationTrackSerializer : KSerializer<AnimationTrack> {
    private val delegate = AnimationTrackSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: AnimationTrack) {
        encoder.encodeSerializableValue(
            delegate, AnimationTrackSurrogate(value.animation, value.timeSeconds),
        )
    }
    override fun deserialize(decoder: Decoder): AnimationTrack {
        val it = decoder.decodeSerializableValue(delegate)
        return AnimationTrack(it.animation, it.timeSeconds)
    }
}

// ---------------------------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("Model")
private class ModelSurrogate(
    @SerialName("placement") val placement: Placement,
    @SerialName("glb") val glb: ResourceLocator,
    @SerialName("texture") val texture: ResourceLocator? = null,
    @SerialName("animationTracks") val animationTracks: List<AnimationTrack> = emptyList(),
)

internal object ModelSerializer : KSerializer<Model> {
    private val delegate = ModelSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Model) {
        encoder.encodeSerializableValue(
            delegate,
            ModelSurrogate(value.placement, value.glb, value.texture, value.animationTracks),
        )
    }
    override fun deserialize(decoder: Decoder): Model {
        val it = decoder.decodeSerializableValue(delegate)
        return Model(it.placement, it.glb, it.texture, it.animationTracks)
    }
}

// ---------------------------------------------------------------------------------------------
// ShaderPair. Written in full, not redacted -- see this file's header on why serializing and
// `toString` differ deliberately.
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("ShaderPair")
private class ShaderPairSurrogate(
    @SerialName("vertexSource") val vertexSource: String,
    @SerialName("fragmentSource") val fragmentSource: String,
)

internal object ShaderPairSerializer : KSerializer<ShaderPair> {
    private val delegate = ShaderPairSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ShaderPair) {
        encoder.encodeSerializableValue(
            delegate, ShaderPairSurrogate(value.vertexSource, value.fragmentSource),
        )
    }
    override fun deserialize(decoder: Decoder): ShaderPair {
        val it = decoder.decodeSerializableValue(delegate)
        return ShaderPair(it.vertexSource, it.fragmentSource)
    }
}

// ---------------------------------------------------------------------------------------------
// ShaderValue -- the second sealed hierarchy. `Mat4` is the one arm that cannot be reached through
// its own properties at all: its elements live in a private `FloatArray` snapshot, readable only
// through the module-internal `elementsForCore()`, which hands back a copy.
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("ShaderValue.Scalar")
private class ShaderValueScalarSurrogate(@SerialName("value") val value: Float)

internal object ShaderValueScalarSerializer : KSerializer<ShaderValue.Scalar> {
    private val delegate = ShaderValueScalarSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ShaderValue.Scalar) {
        encoder.encodeSerializableValue(delegate, ShaderValueScalarSurrogate(value.value))
    }
    override fun deserialize(decoder: Decoder): ShaderValue.Scalar =
        ShaderValue.Scalar(decoder.decodeSerializableValue(delegate).value)
}

@Serializable
@SerialName("ShaderValue.Vec2")
private class ShaderValueVec2Surrogate(
    @SerialName("x") val x: Float,
    @SerialName("y") val y: Float,
)

internal object ShaderValueVec2Serializer : KSerializer<ShaderValue.Vec2> {
    private val delegate = ShaderValueVec2Surrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ShaderValue.Vec2) {
        encoder.encodeSerializableValue(delegate, ShaderValueVec2Surrogate(value.x, value.y))
    }
    override fun deserialize(decoder: Decoder): ShaderValue.Vec2 {
        val it = decoder.decodeSerializableValue(delegate)
        return ShaderValue.Vec2(it.x, it.y)
    }
}

@Serializable
@SerialName("ShaderValue.Vec3")
private class ShaderValueVec3Surrogate(
    @SerialName("x") val x: Float,
    @SerialName("y") val y: Float,
    @SerialName("z") val z: Float,
)

internal object ShaderValueVec3Serializer : KSerializer<ShaderValue.Vec3> {
    private val delegate = ShaderValueVec3Surrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ShaderValue.Vec3) {
        encoder.encodeSerializableValue(delegate, ShaderValueVec3Surrogate(value.x, value.y, value.z))
    }
    override fun deserialize(decoder: Decoder): ShaderValue.Vec3 {
        val it = decoder.decodeSerializableValue(delegate)
        return ShaderValue.Vec3(it.x, it.y, it.z)
    }
}

@Serializable
@SerialName("ShaderValue.Vec4")
private class ShaderValueVec4Surrogate(
    @SerialName("x") val x: Float,
    @SerialName("y") val y: Float,
    @SerialName("z") val z: Float,
    @SerialName("w") val w: Float,
)

internal object ShaderValueVec4Serializer : KSerializer<ShaderValue.Vec4> {
    private val delegate = ShaderValueVec4Surrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ShaderValue.Vec4) {
        encoder.encodeSerializableValue(
            delegate, ShaderValueVec4Surrogate(value.x, value.y, value.z, value.w),
        )
    }
    override fun deserialize(decoder: Decoder): ShaderValue.Vec4 {
        val it = decoder.decodeSerializableValue(delegate)
        return ShaderValue.Vec4(it.x, it.y, it.z, it.w)
    }
}

@Serializable
@SerialName("ShaderValue.Integer")
private class ShaderValueIntegerSurrogate(@SerialName("value") val value: Int)

internal object ShaderValueIntegerSerializer : KSerializer<ShaderValue.Integer> {
    private val delegate = ShaderValueIntegerSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ShaderValue.Integer) {
        encoder.encodeSerializableValue(delegate, ShaderValueIntegerSurrogate(value.value))
    }
    override fun deserialize(decoder: Decoder): ShaderValue.Integer =
        ShaderValue.Integer(decoder.decodeSerializableValue(delegate).value)
}

@Serializable
@SerialName("ShaderValue.Mat4")
private class ShaderValueMat4Surrogate(@SerialName("elements") val elements: List<Float>)

internal object ShaderValueMat4Serializer : KSerializer<ShaderValue.Mat4> {
    private val delegate = ShaderValueMat4Surrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ShaderValue.Mat4) {
        encoder.encodeSerializableValue(
            delegate, ShaderValueMat4Surrogate(value.elementsForCore().toList()),
        )
    }
    override fun deserialize(decoder: Decoder): ShaderValue.Mat4 =
        ShaderValue.Mat4(decoder.decodeSerializableValue(delegate).elements.toFloatArray())
}

// ---------------------------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("Geometry")
private class GeometrySurrogate(
    @SerialName("topLeft") val topLeft: Vector3,
    @SerialName("bottomRight") val bottomRight: Vector3,
    @SerialName("shaderPair") val shaderPair: ShaderPair,
    @SerialName("uniforms") val uniforms: Map<String, ShaderValue> = emptyMap(),
    @SerialName("textures") val textures: Map<String, ResourceLocator> = emptyMap(),
    @SerialName("altitudeMode") val altitudeMode: AltitudeMode = AltitudeMode.ABSOLUTE,
)

internal object GeometrySerializer : KSerializer<Geometry> {
    private val delegate = GeometrySurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Geometry) {
        encoder.encodeSerializableValue(
            delegate,
            GeometrySurrogate(
                value.topLeft, value.bottomRight, value.shaderPair,
                value.uniforms, value.textures, value.altitudeMode,
            ),
        )
    }
    override fun deserialize(decoder: Decoder): Geometry {
        val it = decoder.decodeSerializableValue(delegate)
        return Geometry(
            it.topLeft, it.bottomRight, it.shaderPair, it.uniforms, it.textures, it.altitudeMode,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// FramePlan
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("FramePlan")
private class FramePlanSurrogate(
    @SerialName("frameIndex") val frameIndex: Long,
    @SerialName("camera") val camera: Camera,
    @SerialName("projectionMode") val projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
    @SerialName("drawBasemap") val drawBasemap: Boolean = true,
    @SerialName("drawLabels") val drawLabels: Boolean = true,
    @SerialName("stickers") val stickers: List<Sticker> = emptyList(),
    @SerialName("models") val models: List<Model> = emptyList(),
    @SerialName("geometries") val geometries: List<Geometry> = emptyList(),
)

internal object FramePlanSerializer : KSerializer<FramePlan> {
    private val delegate = FramePlanSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: FramePlan) {
        encoder.encodeSerializableValue(
            delegate,
            FramePlanSurrogate(
                value.frameIndex, value.camera, value.projectionMode, value.drawBasemap,
                value.drawLabels, value.stickers, value.models, value.geometries,
            ),
        )
    }
    override fun deserialize(decoder: Decoder): FramePlan {
        val it = decoder.decodeSerializableValue(delegate)
        return FramePlan(
            it.frameIndex, it.camera, it.projectionMode, it.drawBasemap, it.drawLabels,
            it.stickers, it.models, it.geometries,
        )
    }
}
