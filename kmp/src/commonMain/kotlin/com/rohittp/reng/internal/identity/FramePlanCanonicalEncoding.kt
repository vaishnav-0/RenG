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
import com.rohittp.reng.animationTracksForCore
import com.rohittp.reng.geometriesForCore
import com.rohittp.reng.modelsForCore
import com.rohittp.reng.stickersForCore

/**
 * The Frame Plan field-tag table. ADR 0018 calls this table **permanent**, and a root is "a strictly
 * increasing sequence of unique fields", so a field added later takes the next free tag and encodes
 * last — it never takes a tag in the middle and renumbers the fields after it. `DRAW_LABELS` reads
 * beside `DRAW_BASEMAP` in [FramePlan] and encodes after `GEOMETRIES` for exactly that reason; the
 * two orders are allowed to differ, and the tag is what identity depends on.
 */
internal enum class FramePlanSegment(internal val tag: Int) {
    FRAME_INDEX(1),
    CAMERA(2),
    PROJECTION_MODE(3),
    DRAW_BASEMAP(4),
    STICKERS(5),
    MODELS(6),
    GEOMETRIES(7),
    DRAW_LABELS(8),
}

internal class EncodedFramePlan(
    internal val identity: HashedCanonicalBytes,
    segmentPayloads: List<CanonicalBytes>,
) {
    private val segmentPayloadSnapshot: List<CanonicalBytes> = ArrayList(segmentPayloads)

    internal val segmentPayloads: List<CanonicalBytes>
        get() = ArrayList(segmentPayloadSnapshot)

    override fun equals(other: Any?): Boolean =
        other is EncodedFramePlan &&
            identity == other.identity &&
            segmentPayloadSnapshot == other.segmentPayloadSnapshot

    override fun hashCode(): Int = 31 * identity.hashCode() + segmentPayloadSnapshot.hashCode()
}

internal class FramePlanCanonicalEncoder(
    private val sha256: Sha256Function = PureKotlinSha256,
) {
    internal fun encode(plan: FramePlan): EncodedFramePlan {
        val stickers = plan.stickersForCore()
        val models = plan.modelsForCore()
        val geometries = plan.geometriesForCore()
        val segmentPayloads = listOf(
            CanonicalBinary.u64(plan.frameIndex),
            encodeCamera(plan.camera),
            CanonicalBinary.u16(plan.projectionMode.wireValue),
            CanonicalBinary.boolean(plan.drawBasemap),
            CanonicalBinary.list(stickers.map(::encodeSticker)),
            CanonicalBinary.list(models.map(::encodeModel)),
            CanonicalBinary.list(geometries.map(::encodeGeometry)),
            // Encoded as its own segment rather than folded into DRAW_BASEMAP's byte: the two flags
            // are orthogonal, so all four pairings must reach four distinct Frame Identities.
            CanonicalBinary.boolean(plan.drawLabels),
        )
        val root = CanonicalBinary.root(CanonicalRootKind.FRAME) {
            FramePlanSegment.entries.forEach { segment ->
                field(segment.tag, segmentPayloads[segment.tag - 1])
            }
        }
        return EncodedFramePlan(
            identity = HashedCanonicalBytes(
                digest = sha256.digest(root),
                canonicalBytes = root,
            ),
            segmentPayloads = segmentPayloads,
        )
    }

    private fun encodeCamera(camera: Camera): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.binary64(camera.latitude))
        field(2, CanonicalBinary.binary64(camera.unwrappedLongitude))
        field(3, CanonicalBinary.binary64(camera.zoom))
        field(4, CanonicalBinary.binary64(camera.bearing))
        field(5, CanonicalBinary.binary64(camera.pitch))
    }

    private fun encodeVector(vector: Vector3): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.binary64(vector.x))
        field(2, CanonicalBinary.binary64(vector.y))
        field(3, CanonicalBinary.binary64(vector.z))
    }

    private fun encodePlacement(placement: Placement): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.u16(placement.positionMode.wireValue))
        field(2, encodeVector(placement.position))
        field(3, CanonicalBinary.u16(placement.rotationMode.wireValue))
        field(4, encodeVector(placement.rotation))
        field(5, CanonicalBinary.u16(placement.scaleMode.wireValue))
        field(6, CanonicalBinary.binary64(placement.scale))
    }

    private fun encodeSticker(sticker: Sticker): CanonicalBytes = CanonicalBinary.fields {
        field(1, encodePlacement(sticker.placement))
        field(2, CanonicalBinary.exactUtf8(sticker.image.value))
    }

    private fun encodeModel(model: Model): CanonicalBytes {
        val animationTracks = model.animationTracksForCore()
        return CanonicalBinary.fields {
            field(1, encodePlacement(model.placement))
            field(2, CanonicalBinary.exactUtf8(model.glb.value))
            field(
                3,
                CanonicalBinary.optional(model.texture?.let { CanonicalBinary.exactUtf8(it.value) }),
            )
            field(4, CanonicalBinary.list(animationTracks.map(::encodeAnimationTrack)))
        }
    }

    private fun encodeAnimationTrack(track: AnimationTrack): CanonicalBytes = CanonicalBinary.fields {
        field(1, encodeAnimationSelector(track.animation))
        field(2, CanonicalBinary.binary64(track.timeSeconds))
    }

    private fun encodeAnimationSelector(selector: AnimationSelector): CanonicalBytes = CanonicalBinary.fields {
        when (selector) {
            is AnimationSelector.Index -> {
                field(1, CanonicalBinary.u16(SELECTOR_INDEX_WIRE_VALUE))
                field(2, CanonicalBinary.u64(selector.value))
            }
            is AnimationSelector.Name -> {
                field(1, CanonicalBinary.u16(SELECTOR_NAME_WIRE_VALUE))
                field(2, CanonicalBinary.exactUtf8(selector.value))
            }
        }
    }

    private fun encodeGeometry(geometry: Geometry): CanonicalBytes = CanonicalBinary.fields {
        field(1, encodeVector(geometry.topLeft))
        field(2, encodeVector(geometry.bottomRight))
        field(3, encodeShaderPair(geometry.shaderPair))
        field(4, encodeUniforms(geometry.uniforms))
        field(5, encodeTextures(geometry.textures))
    }

    private fun encodeShaderPair(shaderPair: ShaderPair): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.exactUtf8(shaderPair.vertexSource))
        field(2, CanonicalBinary.exactUtf8(shaderPair.fragmentSource))
    }

    // A Map has no inherent iteration order, and the canonical encoding must be deterministic
    // regardless of how a consumer happened to build the map. Sorting by Kotlin's default String
    // ordering (a fixed UTF-16 code-unit comparison, never locale-sensitive) makes the encoded bytes
    // depend only on the map's content, not on insertion or hash-bucket order.
    private fun encodeUniforms(uniforms: Map<String, ShaderValue>): CanonicalBytes =
        CanonicalBinary.list(
            uniforms.entries.sortedBy { it.key }.map { (name, value) ->
                CanonicalBinary.fields {
                    field(1, CanonicalBinary.exactUtf8(name))
                    field(2, encodeShaderValue(value))
                }
            },
        )

    // Same fixed code-unit key ordering as encodeUniforms, and for the same reason.
    private fun encodeTextures(textures: Map<String, ResourceLocator>): CanonicalBytes =
        CanonicalBinary.list(
            textures.entries.sortedBy { it.key }.map { (name, locator) ->
                CanonicalBinary.fields {
                    field(1, CanonicalBinary.exactUtf8(name))
                    field(2, CanonicalBinary.exactUtf8(locator.value))
                }
            },
        )

    private fun encodeShaderValue(value: ShaderValue): CanonicalBytes = CanonicalBinary.fields {
        when (value) {
            is ShaderValue.Scalar -> {
                field(1, CanonicalBinary.u16(SHADER_VALUE_SCALAR_WIRE_VALUE))
                field(2, CanonicalBinary.binary64(value.value.toDouble()))
            }
            is ShaderValue.Vec2 -> {
                field(1, CanonicalBinary.u16(SHADER_VALUE_VEC2_WIRE_VALUE))
                field(
                    2,
                    CanonicalBinary.list(
                        listOf(
                            CanonicalBinary.binary64(value.x.toDouble()),
                            CanonicalBinary.binary64(value.y.toDouble()),
                        ),
                    ),
                )
            }
            is ShaderValue.Vec3 -> {
                field(1, CanonicalBinary.u16(SHADER_VALUE_VEC3_WIRE_VALUE))
                field(
                    2,
                    CanonicalBinary.list(
                        listOf(
                            CanonicalBinary.binary64(value.x.toDouble()),
                            CanonicalBinary.binary64(value.y.toDouble()),
                            CanonicalBinary.binary64(value.z.toDouble()),
                        ),
                    ),
                )
            }
            is ShaderValue.Vec4 -> {
                field(1, CanonicalBinary.u16(SHADER_VALUE_VEC4_WIRE_VALUE))
                field(
                    2,
                    CanonicalBinary.list(
                        listOf(
                            CanonicalBinary.binary64(value.x.toDouble()),
                            CanonicalBinary.binary64(value.y.toDouble()),
                            CanonicalBinary.binary64(value.z.toDouble()),
                            CanonicalBinary.binary64(value.w.toDouble()),
                        ),
                    ),
                )
            }
            is ShaderValue.Integer -> {
                field(1, CanonicalBinary.u16(SHADER_VALUE_INTEGER_WIRE_VALUE))
                // Widen the Int's exact 32-bit two's-complement pattern into a nonnegative Long payload;
                // sign-extension by toLong() is masked off, so the mapping is exact and injective.
                field(2, CanonicalBinary.u64(value.value.toLong() and 0xFFFFFFFFL))
            }
            is ShaderValue.Mat4 -> {
                field(1, CanonicalBinary.u16(SHADER_VALUE_MAT4_WIRE_VALUE))
                field(
                    2,
                    CanonicalBinary.list(
                        value.elementsForCore().map { CanonicalBinary.binary64(it.toDouble()) },
                    ),
                )
            }
        }
    }
}

private val ProjectionMode.wireValue: Int
    get() = when (this) {
        ProjectionMode.MERCATOR -> 1
        ProjectionMode.GLOBE -> 2
    }

private val AnchoringMode.wireValue: Int
    get() = when (this) {
        AnchoringMode.MAP -> 1
        AnchoringMode.SCREEN -> 2
    }

private const val SELECTOR_INDEX_WIRE_VALUE: Int = 1
private const val SELECTOR_NAME_WIRE_VALUE: Int = 2

private const val SHADER_VALUE_SCALAR_WIRE_VALUE: Int = 1
private const val SHADER_VALUE_VEC2_WIRE_VALUE: Int = 2
private const val SHADER_VALUE_VEC3_WIRE_VALUE: Int = 3
private const val SHADER_VALUE_VEC4_WIRE_VALUE: Int = 4
private const val SHADER_VALUE_INTEGER_WIRE_VALUE: Int = 5
private const val SHADER_VALUE_MAT4_WIRE_VALUE: Int = 6
