package com.rohittp.reng

import com.rohittp.reng.internal.canonicalDouble
import com.rohittp.reng.internal.canonicalFloat
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.gl.MAXIMUM_CONSUMER_TEXTURES
import com.rohittp.reng.internal.gl.RESERVED_SHADER_NAMES
import com.rohittp.reng.internal.requireFiniteFloat
import com.rohittp.reng.internal.requireUnicodeScalars

@kotlinx.serialization.Serializable(with = StickerSerializer::class)
public data class Sticker(
    public val placement: Placement,
    public val image: ResourceLocator,
)

@kotlinx.serialization.Serializable
public sealed interface AnimationSelector {
    @kotlinx.serialization.Serializable(with = AnimationSelectorNameSerializer::class)
    public data class Name(public val value: String) : AnimationSelector {
        init {
            requireUnicodeScalars(value, "animationName", nonBlank = true)
        }
    }

    @kotlinx.serialization.Serializable(with = AnimationSelectorIndexSerializer::class)
    public data class Index(public val value: Long) : AnimationSelector {
        init {
            require(value >= 0L) { "animation index must be non-negative" }
        }
    }
}

@kotlinx.serialization.Serializable(with = AnimationTrackSerializer::class)
public class AnimationTrack(animation: AnimationSelector, timeSeconds: Double) {
    public val animation: AnimationSelector
    public val timeSeconds: Double

    init {
        val validatedAnimation = animation
        val canonicalTimeSeconds = canonicalDouble(timeSeconds, "timeSeconds")
        require(canonicalTimeSeconds >= 0.0) { "timeSeconds must be non-negative" }

        this.animation = validatedAnimation
        this.timeSeconds = canonicalTimeSeconds
    }

    override fun equals(other: Any?): Boolean =
        other is AnimationTrack && animation == other.animation && timeSeconds == other.timeSeconds

    override fun hashCode(): Int = 31 * animation.hashCode() + timeSeconds.hashCode()
}

@kotlinx.serialization.Serializable(with = ModelSerializer::class)
public class Model(
    placement: Placement,
    glb: ResourceLocator,
    texture: ResourceLocator? = null,
    animationTracks: List<AnimationTrack> = emptyList(),
) {
    public val placement: Placement
    public val glb: ResourceLocator
    public val texture: ResourceLocator?
    private val animationTrackSnapshot: ArrayList<AnimationTrack>
    public val animationTracks: List<AnimationTrack>
        get() = freshListCopy(animationTrackSnapshot)

    init {
        val validatedPlacement = placement
        val validatedGlb = glb
        val validatedTexture = texture
        val snapshot = ArrayList(animationTracks)

        this.placement = validatedPlacement
        this.glb = validatedGlb
        this.texture = validatedTexture
        this.animationTrackSnapshot = snapshot
    }

    override fun equals(other: Any?): Boolean =
        other is Model &&
            placement == other.placement &&
            glb == other.glb &&
            texture == other.texture &&
            animationTrackSnapshot == other.animationTrackSnapshot

    override fun hashCode(): Int {
        var result = placement.hashCode()
        result = 31 * result + glb.hashCode()
        result = 31 * result + (texture?.hashCode() ?: 0)
        result = 31 * result + animationTrackSnapshot.hashCode()
        return result
    }
}

internal fun Model.animationTracksForCore(): List<AnimationTrack> = animationTracks

@kotlinx.serialization.Serializable(with = ShaderPairSerializer::class)
public data class ShaderPair(
    public val vertexSource: String,
    public val fragmentSource: String,
) {
    init {
        requireUnicodeScalars(vertexSource, "vertexSource", nonBlank = true)
        requireUnicodeScalars(fragmentSource, "fragmentSource", nonBlank = true)
    }

    override fun toString(): String = "ShaderPair(<redacted>)"
}

/**
 * A value a consumer shader pair may bind by documented uniform name (see [Geometry.uniforms]).
 *
 * Every finite-numeric variant follows the same canonicalization discipline `Vector3` establishes in
 * `CONTEXT.md`: non-finite components are rejected at construction, and `-0.0f`/`0.0f` compare and hash
 * identically. [Scalar], [Vec2], [Vec3], and [Vec4] stay `data class`es — so `copy` and `componentN` work
 * as a consumer expects — but hand-write `equals`/`hashCode` rather than accepting the generated ones:
 * Kotlin's data-class codegen compares `Float` properties with `Float.compare` (matching boxed
 * `Float.equals`, which treats `-0.0f` and `0.0f` as unequal so that hash codes stay consistent with a
 * naive implementation), not with the `==` operator's IEEE-754 semantics. Writing `x == other.x` by hand
 * over statically-typed `Float` uses the IEEE-754 comparison instead, so `-0.0f` and `0.0f` compare equal;
 * `hashCode` then has to normalize the sign of zero itself to stay consistent. [Integer] has no
 * floating-point component and needs neither. The canonical frame-identity encoder
 * (`FramePlanCanonicalEncoding.kt`) independently re-canonicalizes every component before hashing anyway,
 * exactly as it already does for `Vector3`'s `Double` components, so this is defense in depth rather than
 * the only place the guarantee is enforced. [Mat4] is a plain `class` rather than a `data class`
 * specifically so its backing array can be copied defensively and its elements canonicalized at
 * construction; a `data class` over a `FloatArray` would compare by reference.
 */
@kotlinx.serialization.Serializable
public sealed interface ShaderValue {
    @kotlinx.serialization.Serializable(with = ShaderValueScalarSerializer::class)
    public data class Scalar(public val value: Float) : ShaderValue {
        init {
            requireFiniteFloat(value, "value")
        }

        override fun equals(other: Any?): Boolean = other is Scalar && value == other.value

        override fun hashCode(): Int = zeroCanonicalizedHash(value)
    }

    @kotlinx.serialization.Serializable(with = ShaderValueVec2Serializer::class)
    public data class Vec2(public val x: Float, public val y: Float) : ShaderValue {
        init {
            requireFiniteFloat(x, "x")
            requireFiniteFloat(y, "y")
        }

        override fun equals(other: Any?): Boolean = other is Vec2 && x == other.x && y == other.y

        override fun hashCode(): Int = 31 * zeroCanonicalizedHash(x) + zeroCanonicalizedHash(y)
    }

    @kotlinx.serialization.Serializable(with = ShaderValueVec3Serializer::class)
    public data class Vec3(public val x: Float, public val y: Float, public val z: Float) : ShaderValue {
        init {
            requireFiniteFloat(x, "x")
            requireFiniteFloat(y, "y")
            requireFiniteFloat(z, "z")
        }

        override fun equals(other: Any?): Boolean =
            other is Vec3 && x == other.x && y == other.y && z == other.z

        override fun hashCode(): Int {
            var result = zeroCanonicalizedHash(x)
            result = 31 * result + zeroCanonicalizedHash(y)
            result = 31 * result + zeroCanonicalizedHash(z)
            return result
        }
    }

    @kotlinx.serialization.Serializable(with = ShaderValueVec4Serializer::class)
    public data class Vec4(
        public val x: Float,
        public val y: Float,
        public val z: Float,
        public val w: Float,
    ) : ShaderValue {
        init {
            requireFiniteFloat(x, "x")
            requireFiniteFloat(y, "y")
            requireFiniteFloat(z, "z")
            requireFiniteFloat(w, "w")
        }

        override fun equals(other: Any?): Boolean =
            other is Vec4 && x == other.x && y == other.y && z == other.z && w == other.w

        override fun hashCode(): Int {
            var result = zeroCanonicalizedHash(x)
            result = 31 * result + zeroCanonicalizedHash(y)
            result = 31 * result + zeroCanonicalizedHash(z)
            result = 31 * result + zeroCanonicalizedHash(w)
            return result
        }
    }

    @kotlinx.serialization.Serializable(with = ShaderValueIntegerSerializer::class)
    public data class Integer(public val value: Int) : ShaderValue

    @kotlinx.serialization.Serializable(with = ShaderValueMat4Serializer::class)
    public class Mat4(elements: FloatArray) : ShaderValue {
        private val elementSnapshot: FloatArray

        init {
            require(elements.size == MAT4_ELEMENT_COUNT) {
                "Mat4 requires exactly $MAT4_ELEMENT_COUNT elements"
            }
            elementSnapshot = FloatArray(MAT4_ELEMENT_COUNT) { index ->
                canonicalFloat(elements[index], "elements[$index]")
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Mat4 && elementSnapshot.contentEquals(other.elementSnapshot)

        override fun hashCode(): Int = elementSnapshot.contentHashCode()

        override fun toString(): String = "ShaderValue.Mat4(<redacted>)"

        /**
         * A fresh defensive copy of the canonicalized 16 elements, for internal use only (the canonical
         * frame-identity encoder and, later, the GL uniform binder). Not part of the public API: a
         * consumer has no supported way to read a [Mat4] back out.
         */
        internal fun elementsForCore(): FloatArray = elementSnapshot.copyOf()
    }
}

// Float.hashCode() (java.lang.Float.floatToIntBits under the hood on JVM, equivalently bit-based on
// every other target) gives -0.0f and 0.0f different results, even though the `==` operator above
// treats them as equal. Normalizing the sign of zero before hashing keeps equals()/hashCode() consistent
// without needing to canonicalize a ShaderValue's stored value itself.
private fun zeroCanonicalizedHash(value: Float): Int = (if (value == 0.0f) 0.0f else value).hashCode()

private const val MAT4_ELEMENT_COUNT = 16

@kotlinx.serialization.Serializable(with = GeometrySerializer::class)
public data class Geometry(
    public val topLeft: Vector3,
    public val bottomRight: Vector3,
    public val shaderPair: ShaderPair,
    /**
     * Consumer uniform values, bound by name at draw time to whichever of `shaderPair`'s programs
     * declares that name (ADR 0008: undeclared names are silently never bound, never an error).
     * A name matching one of RenG's six documented shader-interface names (see
     * `internal.gl.RESERVED_SHADER_NAMES`) is rejected at construction, loudly, rather than being
     * silently overwritten by RenG's own binding at draw time.
     *
     * **No-mutation-after-construction contract.** This property stores the caller's own `Map`
     * reference directly, with no defensive copy — a forced consequence of keeping [Geometry] a
     * `data class` so `copy()` and structural `equals` keep working the way every other RenG value
     * type's does. `FramePlanningCore.plan()` reads this map exactly once, synchronously, and
     * serializes it into the plan's immutable canonical bytes, so that reader can never observe a
     * mutation. `internal.gl.drawGeometry` (Cycle F-1 Task 7) is a second, later reader of the same
     * object — but by the time it runs, at draw time, `com.rohittp.reng.RenGPreparedFrame` (Task 9b)
     * has already taken its own `.toMap()` snapshot at `prepare()` time, so a caller mutating this
     * `Map` between `prepare()` and a later `draw()` on that same frame cannot change what draws or
     * diverge from what the frame's recorded identity already hashed. A caller should still treat a
     * `Map` passed here as immutable from the moment it is passed — RenG's own protection is a
     * defence for its internal correctness, not a licence to keep mutating a map handed to it.
     */
    public val uniforms: Map<String, ShaderValue> = emptyMap(),
    /**
     * Consumer texture bindings, by sampler name declared in `shaderPair`. Bulk data — a boundary
     * mask, a signed-distance field, values packed across RGBA channels — travels this way rather
     * than as a [ShaderValue], since GLSL has no array-valued uniform of usable size for it. Every
     * texture bound through this map uploads bit-exact, never premultiplied — see
     * `internal.gl.TextureContent.DATA` — because a consumer data payload is not decorative pixel
     * colour and a multiply would silently corrupt it, exactly as `CONTEXT.md` documents for
     * terrain samples.
     *
     * Capped at `internal.gl.MAXIMUM_CONSUMER_TEXTURES`: GLES 3.0 guarantees only sixteen fragment
     * texture image units, and RenG's own draw of a geometry keeps headroom in that budget rather
     * than exactly saturating it. A name colliding with a documented reserved shader name, or a map
     * larger than the budget, is rejected at construction rather than silently dropped or wrapped at
     * bind time. See [uniforms]'s KDoc for the identical no-mutation-after-construction contract,
     * which applies here too.
     */
    public val textures: Map<String, ResourceLocator> = emptyMap(),
    /**
     * How this geometry's corner altitudes are measured.
     *
     * **A [Geometry] carries no [Placement]** — `CONTEXT.md` says so, and it is why the mode has to
     * reach it on a field of its own rather than through one. **One mode governs both corners**: the
     * glossary already makes the northern edge [topLeft]'s `z`, the southern edge [bottomRight]'s,
     * and altitude interpolate north-to-south between them, so a rectangle with one corner pinned to
     * the ellipsoid and the other riding a ridge is a shape nobody asked for. Under
     * [AltitudeMode.GROUND_RELATIVE] that interpolated altitude becomes an offset above a surface
     * sampled at every subdivided vertex rather than only at the two corners — the difference
     * between a quad draped over a ridge and one cutting through it.
     *
     * **Declared last, after [textures], so no existing `componentN` moves.** [Geometry] is a
     * `data class`, so a field inserted between [bottomRight] and [shaderPair] — where it would read
     * best — would renumber `component3` through `component5` and break every consumer that
     * destructures one. It takes `component6` instead, and the canonical encoder takes the next free
     * tag for the same reason (ADR 0018).
     */
    public val altitudeMode: AltitudeMode = AltitudeMode.ABSOLUTE,
) {
    init {
        require(topLeft.x in -90.0..90.0) { "topLeft latitude must be within the supported range" }
        require(bottomRight.x in -90.0..90.0) { "bottomRight latitude must be within the supported range" }
        require(topLeft.x > bottomRight.x) { "topLeft latitude must be north of bottomRight latitude" }
        require(topLeft.y < bottomRight.y) { "topLeft longitude must be west of bottomRight longitude" }
        require(bottomRight.y - topLeft.y <= 360.0) { "geometry longitude span must not exceed 360 degrees" }
        require(uniforms.keys.none { it in RESERVED_SHADER_NAMES }) {
            "a geometry uniform must not use a name reserved for RenG's own documented shader interface"
        }
        require(textures.keys.none { it in RESERVED_SHADER_NAMES }) {
            "a geometry texture must not use a name reserved for RenG's own documented shader interface"
        }
        require(textures.size <= MAXIMUM_CONSUMER_TEXTURES) {
            "a geometry may declare at most $MAXIMUM_CONSUMER_TEXTURES consumer textures"
        }
    }

    override fun toString(): String =
        "Geometry(topLeft=$topLeft, bottomRight=$bottomRight, shaderPair=<redacted>, " +
            "uniforms=<redacted>, textures=<redacted>, altitudeMode=$altitudeMode)"
}
