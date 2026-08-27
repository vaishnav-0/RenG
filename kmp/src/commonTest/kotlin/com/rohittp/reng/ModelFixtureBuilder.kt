package com.rohittp.reng

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The GLBs [runModelReadbackSuite] draws, assembled byte by byte because RenG owns a glTF decoder
 * and no encoder — there is nothing in the tree to write one of these with.
 *
 * **Every number here is asymmetric on purpose, and that is not decoration.** A transposed inverse
 * bind matrix deforms a model *plausibly* rather than obviously, and this project has already
 * shipped a suite of exact-url assertions blind to a transposition because every fixture in it
 * happened to be symmetric (`RendererBasemapStyleTest.styleCamera` records that episode). So: no two
 * vertex components are equal, no local bounding box is centred on the origin, both joints carry a
 * three-component translation rather than an identity, the wedge's two triangles sit in different
 * planes, and the index buffer is a non-identity permutation over a vertex array whose first entry
 * no index reaches.
 *
 * **The wedge's local frame is a screen frame, because the suite places it with `SCREEN` rotation
 * and `SCREEN` scale over a `MAP` position** — `CONTEXT.md`'s billboard anchoring, the same one
 * `BasemapReadbackSuite` uses for its map-anchored sticker. Local `+x` is screen right, `+y` is
 * screen up, `+z` is toward the viewer, and one local unit is [MODEL_SCREEN_SCALE] logical pixels at
 * the anchor's own depth. That is what makes every projected bound in the suite analytic rather than
 * measured.
 *
 * ### The two fixtures
 *
 * [texturedWedgeGlb] is the model under test: two triangles, six live vertices, a 4x4 embedded PNG
 * whose sixteen texels are all different, a two-joint skin, and one 90-degree rotation animation on
 * the second joint. Its two triangles are weighted to different joints — [STATIC_TRIANGLE] entirely
 * to the static joint, [MOVING_TRIANGLE] to a 0.2/0.8 blend that is mostly the moving one — so the
 * frame splits into a region the animation must not touch and a region it must.
 *
 * [tallBlockerGlb] is scenery for the occlusion case: one untextured, flat-coloured quad, taller
 * than it is wide, standing at a constant local `z` well in front of every wedge vertex and covering
 * the whole wedge on screen.
 *
 * ### What each fixture deliberately does not carry
 *
 * **No `NORMAL`.** `modelFragmentSource` shades by `max(dot(normal, light), 0)` and treats a
 * zero-length normal as "fully lit" — glTF requires unit normals, so a zero one cannot be authored
 * data. Omitting the attribute therefore pins the shading term at exactly `1.0`, which is what makes
 * an interior sample's expected colour the texel itself rather than the texel times an angle nobody
 * can write down. ADR 0026's light is consequently **not** exercised by this suite.
 *
 * **No `COLOR_0`.** The vertex tint is then `vec4(1.0)`, for the same reason.
 *
 * **A white base-colour factor on the wedge.** The product `factor * texel` is then the texel
 * exactly, so a defect that drops the texture reads back as white — 215 of 255 away from
 * [STATIC_TRIANGLE_TEXEL] and 159 away from [MOVING_TRIANGLE_TEXEL], against a tolerance of
 * [MODEL_CHANNEL_TOLERANCE]. The other half of that contract — that an *authored* factor is read at
 * all — is carried by [tallBlockerGlb], whose flat [BLOCKER_COLOUR] is nothing like white.
 */

// ---- the wedge's local geometry ---------------------------------------------------------------

/** Output pixels per local unit at the anchor's own depth; see this file's header. */
internal const val MODEL_SCREEN_SCALE: Double = 24.0

/**
 * A local axis-aligned box, in the wedge's own units. The suite projects one of these into window
 * pixels rather than tabulating a pixel rectangle, so editing a vertex above moves the assertion
 * with it instead of leaving a stale constant behind.
 */
internal class LocalBox(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val minZ: Double,
    val maxZ: Double,
) {
    fun describe(): String = "x=[$minX,$maxX] y=[$minY,$maxY] z=[$minZ,$maxZ]"
}

/**
 * The vertex no index reaches, sitting far outside every bound the suite asserts.
 *
 * It is what makes the index buffer load-bearing here: a draw that ignored [WEDGE_INDICES] and swept
 * the vertex array in order would pull this corner into a triangle and fail
 * `everyDrawnPixelFallsInsideTheModelsProjectedBounds` by tens of pixels. Without it, an
 * index-ignoring draw would produce exactly the same two triangles and nothing would notice.
 */
private val DECOY_VERTEX: DoubleArray = doubleArrayOf(4.7, -3.9, 2.6)

/** The static triangle: three vertices weighted wholly to the skin's first, unanimated joint. */
private val STATIC_TRIANGLE: List<DoubleArray> = listOf(
    doubleArrayOf(-1.18, -0.86, -0.24),
    doubleArrayOf(-0.09, -0.51, 0.17),
    doubleArrayOf(-0.72, 1.04, 0.41),
)

/** The moving triangle: three vertices weighted 0.2 to the static joint and 0.8 to the animated one. */
private val MOVING_TRIANGLE: List<DoubleArray> = listOf(
    doubleArrayOf(0.48, -1.02, 0.53),
    doubleArrayOf(1.51, -0.57, -0.13),
    doubleArrayOf(0.72, 0.91, 0.28),
)

/** Every position in buffer order: the decoy first, then the two triangles. */
private val WEDGE_POSITIONS: List<DoubleArray> = listOf(DECOY_VERTEX) + STATIC_TRIANGLE + MOVING_TRIANGLE

/**
 * A cyclic rotation of each triangle's own corner order, so the index buffer is a genuine
 * permutation rather than `0, 1, 2, ...`; winding is preserved, which matters only in principle
 * since both fixtures declare `doubleSided`.
 */
private val WEDGE_INDICES: IntArray = intArrayOf(3, 1, 2, 6, 4, 5)

/**
 * The skin's two joints, as world-space bind-pose translations.
 *
 * Both are three-component and none of the six numbers repeats, so the corresponding inverse bind
 * matrix is nowhere near its own transpose: transposing a translation matrix moves `(tx, ty, tz)`
 * out of the last column and into the last row, turning it into a projective matrix that would blow
 * the model apart rather than nudge it.
 */
private val STATIC_JOINT_BIND_POSITION: DoubleArray = doubleArrayOf(0.31, -0.42, 0.18)
private val MOVING_JOINT_BIND_POSITION: DoubleArray = doubleArrayOf(1.05, -0.10, 0.05)

/** The moving joint's own translation, relative to its parent, so that the two compose to the above. */
private val MOVING_JOINT_LOCAL_TRANSLATION: DoubleArray = doubleArrayOf(
    MOVING_JOINT_BIND_POSITION[0] - STATIC_JOINT_BIND_POSITION[0],
    MOVING_JOINT_BIND_POSITION[1] - STATIC_JOINT_BIND_POSITION[1],
    MOVING_JOINT_BIND_POSITION[2] - STATIC_JOINT_BIND_POSITION[2],
)

/** How much of the moving triangle's deformation the animated joint owns; the rest is the static one. */
private const val MOVING_JOINT_WEIGHT: Double = 0.8

/**
 * The animation's own last keyframe time. Every sampled time in the suite is chosen against this:
 * `CONTEXT.md` wraps by `timeSeconds % durationSeconds`, so this exact value maps back onto zero and
 * is never sampled, and the midpoint is avoided because slerp and nlerp agree there exactly.
 */
internal const val WEDGE_ANIMATION_DURATION_SECONDS: Double = 2.0

/** The animation's name, so the suite selects by [AnimationSelector.Name] rather than by index. */
internal const val WEDGE_ANIMATION_NAME: String = "swing"

/** The wedge's own bounding box in bind pose, where every joint matrix is the identity. */
internal val WEDGE_BIND_POSE_LOCAL_BOUNDS: LocalBox = boundsOf(STATIC_TRIANGLE + MOVING_TRIANGLE)

/**
 * The wedge's bounding box under **any** angle the animation can reach.
 *
 * Derived rather than sampled, and that is the point: the animated joint rotates about `z` through
 * [MOVING_JOINT_BIND_POSITION], so a moving vertex travels a circle in the local `xy` plane about
 * that pivot, and the 0.2/0.8 weight blend can only pull it onto the chord between its bind position
 * and that circle — inside the circle's own convex hull either way. Bounding the circle needs no
 * interpolation arithmetic at all, so this box cannot silently agree with a wrong slerp.
 */
internal val WEDGE_SWEPT_LOCAL_BOUNDS: LocalBox = sweptBounds()

/**
 * The greatest local `x` any static-triangle vertex reaches, and the least any moving-triangle
 * vertex reaches over the sampled part of the animation.
 *
 * These are what let the suite split the frame into a band the animation must leave bit-identical
 * and a band it must change. The moving figure is the *swept* minimum, so the split holds at every
 * angle rather than at the two the suite happens to draw.
 */
internal val STATIC_TRIANGLE_LOCAL_BOUNDS: LocalBox = boundsOf(STATIC_TRIANGLE)
internal val MOVING_TRIANGLE_SWEPT_LOCAL_BOUNDS: LocalBox = sweptMovingBounds()

/**
 * The two live triangles' own bind-pose corners, so the suite can name a point guaranteed to be
 * inside each one: the centroid of three projected corners is inside the triangle they span,
 * whatever the projection did to their spacing.
 */
internal val STATIC_TRIANGLE_CORNERS: List<DoubleArray> = STATIC_TRIANGLE
internal val MOVING_TRIANGLE_CORNERS: List<DoubleArray> = MOVING_TRIANGLE

// ---- the blocker's local geometry ---------------------------------------------------------------

/**
 * A flat quad at a constant local `z` far in front of every wedge vertex, and wide enough to cover
 * the whole bind-pose wedge on screen with margin on all four sides.
 *
 * Taller than it is wide, as the occlusion case's "second, taller mesh" — and flat in `z` on
 * purpose, so the depth comparison against the wedge is one number against another rather than a
 * gradient. At [MODEL_SCREEN_SCALE] and the fixture camera's 154.51-logical-pixel orbit its
 * fragments sit at reverse-Z window depth 0.00861 against the wedge's 0.00705 at worst — some
 * 26,000 units apart in a 24-bit buffer, which is a depth ordering no rounding rule can flip.
 */
private const val BLOCKER_LOCAL_Z: Double = 1.6

private val BLOCKER_CORNERS: List<DoubleArray> = listOf(
    doubleArrayOf(-1.32, -1.52, BLOCKER_LOCAL_Z),
    doubleArrayOf(1.44, -1.52, BLOCKER_LOCAL_Z),
    doubleArrayOf(-1.32, 1.58, BLOCKER_LOCAL_Z),
    doubleArrayOf(1.44, 1.58, BLOCKER_LOCAL_Z),
)

private val BLOCKER_INDICES: IntArray = intArrayOf(0, 1, 2, 2, 1, 3)

internal val BLOCKER_LOCAL_BOUNDS: LocalBox = boundsOf(BLOCKER_CORNERS)

// ---- the palette ---------------------------------------------------------------------------------

/**
 * Per-channel tolerance for every colour comparison in the suite.
 *
 * The palette below is separated by at least 47 in some channel across every pair the suite can
 * confuse — texel against texel, texel against [BLOCKER_COLOUR], texel against the clear colour,
 * texel against white — so this absorbs the shader's float arithmetic and an 8-bit rounding step
 * without ever admitting a neighbouring fixture colour.
 */
internal const val MODEL_CHANNEL_TOLERANCE: Int = 6

/**
 * The sixteen texels of the embedded PNG, in image order: index `row * 4 + column`, row 0 being the
 * image's **top** row.
 *
 * Written out by hand from the generator below rather than decoded, following `PngDecoderTest`'s
 * anti-circularity rule: nothing on the expected side of an assertion may run the code under test,
 * so a `decodePng` regression can only make this suite fail, never pass.
 *
 * Red is a function of the column and green of the row, so any two texels differ by at least 56 in
 * one of those two channels; blue varies on a third, coprime pattern so that the grid is not its own
 * transpose. Every one of the sixteen is far from black (the colour an incomplete texture samples),
 * far from white (the base-colour factor), and far from [MODEL_ABSENT] and [BLOCKER_COLOUR].
 *
 * Regenerate the PNG bytes with:
 *
 *     python3 - <<'PY'
 *     import zlib, struct
 *     def chunk(kind, payload):
 *         return (struct.pack(">I", len(payload)) + kind + payload +
 *                 struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
 *     raw = b""
 *     for row in range(4):
 *         raw += b"\x00"
 *         for col in range(4):
 *             raw += bytes((40 + 56 * col, 208 - 56 * row, 40 + 56 * ((row + 2 * col) % 4), 255))
 *     print(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 4, 4, 8, 6, 0, 0, 0)) +
 *           chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
 *     PY
 */
internal val MODEL_TEXELS: List<IntArray> = (0 until 4).flatMap { row ->
    (0 until 4).map { column ->
        intArrayOf(40 + 56 * column, 208 - 56 * row, 40 + 56 * ((row + 2 * column) % 4), 255)
    }
}

internal fun modelTexel(row: Int, column: Int): IntArray = MODEL_TEXELS[row * 4 + column]

/**
 * The one texel the static triangle can sample, and the one the moving triangle can.
 *
 * Both triangles' three texture coordinates lie **inside a single texel cell** — the static one in
 * image cell `(row 3, column 0)`, the moving one in `(row 1, column 2)` — and the sampler magnifies
 * with `GL_NEAREST`, so every fragment of a triangle samples that one texel whatever the projection
 * does. That is what makes an interior sample's expected colour a constant rather than a function of
 * where exactly the triangle landed.
 *
 * The two cells are `(3, 0)` and `(1, 2)`. Neither is on the grid's diagonal and neither maps onto
 * itself or onto the other under a `u`/`v` swap — a transposed texture coordinate read lands on
 * `(0, 3)` and `(2, 1)`, two different colours — so this pair also stands as the suite's
 * transposition check on the texture path.
 */
internal val STATIC_TRIANGLE_TEXEL: IntArray = modelTexel(row = 3, column = 0)
internal val MOVING_TRIANGLE_TEXEL: IntArray = modelTexel(row = 1, column = 2)

/** The blocker's authored base-colour factor, read back exactly because its material has no texture. */
internal val BLOCKER_COLOUR: IntArray = intArrayOf(32, 176, 240, 255)

/** What the ground fixture's one tile PNG carries, everywhere. */
internal val MODEL_GROUND_COLOUR: IntArray = intArrayOf(16, 96, 16, 255)

/**
 * What the readback target is cleared to before every draw: not a texel, not the blocker, not the
 * ground, and above all not black, so "still absent" and "drew black" are never the same reading.
 */
internal val MODEL_ABSENT: IntArray = intArrayOf(255, 0, 128, 255)

/** Every colour the suite can name, for a failure message that says which one a pixel is nearest. */
internal val MODEL_FIXTURE_COLOURS: List<Pair<String, IntArray>> =
    MODEL_TEXELS.mapIndexed { index, texel -> "texel(row ${index / 4}, column ${index % 4})" to texel } +
        listOf(
            "blocker" to BLOCKER_COLOUR,
            "ground" to MODEL_GROUND_COLOUR,
            "absent" to MODEL_ABSENT,
            "black" to intArrayOf(0, 0, 0, 255),
            "white" to intArrayOf(255, 255, 255, 255),
        )

// ---- the GLBs --------------------------------------------------------------------------------

/** The 4x4 RGBA PNG whose sixteen texels are [MODEL_TEXELS]; see that value for the generator. */
private val WEDGE_PNG: ByteArray = byteArrayOf(
    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13,
    73, 72, 68, 82, 0, 0, 0, 4, 0, 0, 0, 4,
    8, 6, 0, 0, 0, -87, -15, -98, 126, 0, 0, 0,
    46, 73, 68, 65, 84, 120, -38, 13, -59, 49, 1, 0,
    0, 12, -62, -80, -55, 65, 22, -78, 42, 11, 71, -116,
    60, 57, 69, 117, 40, 59, -5, -124, 107, 82, 118, -10,
    -55, -44, 86, -39, -39, 39, -91, -106, -53, -50, 126, 82,
    -89, 39, 49, 61, -37, -37, -5, 0, 0, 0, 0, 73,
    69, 78, 68, -82, 66, 96, -126,
)

private const val WEDGE_VERTEX_COUNT = 7
private const val WEDGE_INDEX_COUNT = 6
private const val JOINT_COUNT = 2

private const val POSITION_OFFSET = 0
private const val POSITION_BYTES = WEDGE_VERTEX_COUNT * 3 * 4
private const val TEX_COORD_OFFSET = POSITION_OFFSET + POSITION_BYTES
private const val TEX_COORD_BYTES = WEDGE_VERTEX_COUNT * 2 * 4
private const val JOINTS_OFFSET = TEX_COORD_OFFSET + TEX_COORD_BYTES
private const val JOINTS_BYTES = WEDGE_VERTEX_COUNT * 4
private const val WEIGHTS_OFFSET = JOINTS_OFFSET + JOINTS_BYTES
private const val WEIGHTS_BYTES = WEDGE_VERTEX_COUNT * 4 * 4
private const val INDEX_OFFSET = WEIGHTS_OFFSET + WEIGHTS_BYTES
private const val INDEX_BYTES = WEDGE_INDEX_COUNT * 2
private const val INVERSE_BIND_OFFSET = INDEX_OFFSET + INDEX_BYTES
private const val INVERSE_BIND_BYTES = JOINT_COUNT * 16 * 4
private const val KEYFRAME_TIME_OFFSET = INVERSE_BIND_OFFSET + INVERSE_BIND_BYTES
private const val KEYFRAME_TIME_BYTES = 2 * 4
private const val KEYFRAME_ROTATION_OFFSET = KEYFRAME_TIME_OFFSET + KEYFRAME_TIME_BYTES
private const val KEYFRAME_ROTATION_BYTES = 2 * 4 * 4
private const val IMAGE_OFFSET = KEYFRAME_ROTATION_OFFSET + KEYFRAME_ROTATION_BYTES

/** `GL_LINEAR_MIPMAP_LINEAR`: the whole point of the "a model is not black" case. Honouring this
 * without generating the chain it needs leaves the texture mipmap-incomplete, and an incomplete
 * texture samples `(0, 0, 0, 1)` on a real driver for every access, magnifying or not. */
private const val MIN_FILTER_LINEAR_MIPMAP_LINEAR = 9987

/** `GL_NEAREST` magnification, so a fragment samples one texel rather than a blend of four. */
private const val MAG_FILTER_NEAREST = 9728

/** `GL_CLAMP_TO_EDGE` on both axes. Every texture coordinate the fixture authors is strictly inside
 * `[0, 1]`, so wrapping is unreachable here and a transposed `wrapS`/`wrapT` is not this suite's to
 * catch; `ModelDecodeTest` already asserts the four enums arrive unswapped. */
private const val WRAP_CLAMP_TO_EDGE = 33071

/**
 * The model under test. See this file's header for what it carries and what it deliberately does not.
 */
internal fun texturedWedgeGlb(): ByteArray = glb(
    json = """
    {"asset": {"version": "2.0"},
     "buffers": [{"byteLength": ${IMAGE_OFFSET + WEDGE_PNG.size}}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": $POSITION_OFFSET, "byteLength": $POSITION_BYTES},
       {"buffer": 0, "byteOffset": $TEX_COORD_OFFSET, "byteLength": $TEX_COORD_BYTES},
       {"buffer": 0, "byteOffset": $JOINTS_OFFSET, "byteLength": $JOINTS_BYTES},
       {"buffer": 0, "byteOffset": $WEIGHTS_OFFSET, "byteLength": $WEIGHTS_BYTES},
       {"buffer": 0, "byteOffset": $INDEX_OFFSET, "byteLength": $INDEX_BYTES},
       {"buffer": 0, "byteOffset": $INVERSE_BIND_OFFSET, "byteLength": $INVERSE_BIND_BYTES},
       {"buffer": 0, "byteOffset": $KEYFRAME_TIME_OFFSET, "byteLength": $KEYFRAME_TIME_BYTES},
       {"buffer": 0, "byteOffset": $KEYFRAME_ROTATION_OFFSET, "byteLength": $KEYFRAME_ROTATION_BYTES},
       {"buffer": 0, "byteOffset": $IMAGE_OFFSET, "byteLength": ${WEDGE_PNG.size}}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": $WEDGE_VERTEX_COUNT, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5126, "count": $WEDGE_VERTEX_COUNT, "type": "VEC2"},
       {"bufferView": 2, "componentType": 5121, "count": $WEDGE_VERTEX_COUNT, "type": "VEC4"},
       {"bufferView": 3, "componentType": 5126, "count": $WEDGE_VERTEX_COUNT, "type": "VEC4"},
       {"bufferView": 4, "componentType": 5123, "count": $WEDGE_INDEX_COUNT, "type": "SCALAR"},
       {"bufferView": 5, "componentType": 5126, "count": $JOINT_COUNT, "type": "MAT4"},
       {"bufferView": 6, "componentType": 5126, "count": 2, "type": "SCALAR"},
       {"bufferView": 7, "componentType": 5126, "count": 2, "type": "VEC4"}],
     "images": [{"bufferView": 8, "mimeType": "image/png"}],
     "samplers": [{"minFilter": $MIN_FILTER_LINEAR_MIPMAP_LINEAR, "magFilter": $MAG_FILTER_NEAREST,
                   "wrapS": $WRAP_CLAMP_TO_EDGE, "wrapT": $WRAP_CLAMP_TO_EDGE}],
     "textures": [{"source": 0, "sampler": 0}],
     "materials": [{"doubleSided": true, "pbrMetallicRoughness": {
       "baseColorFactor": [1.0, 1.0, 1.0, 1.0], "baseColorTexture": {"index": 0}}}],
     "meshes": [{"primitives": [{"attributes": {
       "POSITION": 0, "TEXCOORD_0": 1, "JOINTS_0": 2, "WEIGHTS_0": 3},
       "indices": 4, "material": 0}]}],
     "skins": [{"joints": [1, 2], "inverseBindMatrices": 5}],
     "animations": [{"name": "$WEDGE_ANIMATION_NAME",
       "channels": [{"sampler": 0, "target": {"node": 2, "path": "rotation"}}],
       "samplers": [{"input": 6, "output": 7, "interpolation": "LINEAR"}]}],
     "nodes": [
       {"mesh": 0, "skin": 0},
       {"translation": [${STATIC_JOINT_BIND_POSITION.joinToString(", ")}], "children": [2]},
       {"translation": [${MOVING_JOINT_LOCAL_TRANSLATION.joinToString(", ")}]}],
     "scenes": [{"nodes": [0, 1]}]}
    """.trimIndent(),
    binChunk = modelBin {
        WEDGE_POSITIONS.forEach { f32(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
        WEDGE_TEX_COORDS.forEach { f32(it[0].toFloat(), it[1].toFloat()) }
        WEDGE_JOINT_INDICES.forEach { u8(it[0], it[1], it[2], it[3]) }
        WEDGE_JOINT_WEIGHTS.forEach { f32(it[0], it[1], it[2], it[3]) }
        u16(*WEDGE_INDICES)
        inverseBindTranslation(STATIC_JOINT_BIND_POSITION)
        inverseBindTranslation(MOVING_JOINT_BIND_POSITION)
        f32(0.0f, WEDGE_ANIMATION_DURATION_SECONDS.toFloat())
        // Two quaternions about z: the identity, then a quarter turn. Both endpoints lie on one
        // great circle through the identity, which is exactly why the suite can state the angle at
        // an intermediate time without reimplementing slerp -- and why it never samples the
        // midpoint, where slerp and nlerp agree to the bit.
        f32(0f, 0f, 0f, 1f)
        f32(0f, 0f, QUARTER_TURN_COMPONENT, QUARTER_TURN_COMPONENT)
        raw(WEDGE_PNG)
    },
)

/** `sin(45 degrees) == cos(45 degrees)`: the `z` and `w` components of a quarter turn about `z`. */
private val QUARTER_TURN_COMPONENT: Float = (sqrt(2.0) / 2.0).toFloat()

/**
 * Texture coordinates, in the same buffer order as [WEDGE_POSITIONS].
 *
 * Each live triangle's three coordinates sit inside one texel cell of the 4x4 image — see
 * [STATIC_TRIANGLE_TEXEL] — and the six are all different, so a shared or defaulted coordinate
 * cannot pass for a read one.
 */
private val WEDGE_TEX_COORDS: List<DoubleArray> = listOf(
    doubleArrayOf(0.55, 0.30), // the decoy; never drawn.
    doubleArrayOf(0.05, 0.95),
    doubleArrayOf(0.20, 0.83),
    doubleArrayOf(0.08, 0.79),
    doubleArrayOf(0.55, 0.30),
    doubleArrayOf(0.70, 0.44),
    doubleArrayOf(0.58, 0.35),
)

/**
 * Joint indices, in buffer order. The moving triangle names its joint in the **second** influence
 * slot rather than the first, so a vertex shader that read only `JOINTS_0.x` would deform nothing.
 */
private val WEDGE_JOINT_INDICES: List<IntArray> = listOf(
    intArrayOf(0, 0, 0, 0),
    intArrayOf(0, 0, 0, 0),
    intArrayOf(0, 0, 0, 0),
    intArrayOf(0, 0, 0, 0),
    intArrayOf(0, 1, 0, 0),
    intArrayOf(0, 1, 0, 0),
    intArrayOf(0, 1, 0, 0),
)

/** Weights, in buffer order; each row sums to one, as glTF requires. */
private val WEDGE_JOINT_WEIGHTS: List<FloatArray> = listOf(
    floatArrayOf(1f, 0f, 0f, 0f),
    floatArrayOf(1f, 0f, 0f, 0f),
    floatArrayOf(1f, 0f, 0f, 0f),
    floatArrayOf(1f, 0f, 0f, 0f),
    floatArrayOf((1.0 - MOVING_JOINT_WEIGHT).toFloat(), MOVING_JOINT_WEIGHT.toFloat(), 0f, 0f),
    floatArrayOf((1.0 - MOVING_JOINT_WEIGHT).toFloat(), MOVING_JOINT_WEIGHT.toFloat(), 0f, 0f),
    floatArrayOf((1.0 - MOVING_JOINT_WEIGHT).toFloat(), MOVING_JOINT_WEIGHT.toFloat(), 0f, 0f),
)

/**
 * The scenery for the occlusion case: one flat, untextured, taller-than-wide quad standing in front
 * of every wedge vertex. Its authored [BLOCKER_COLOUR] base factor is the suite's proof that a
 * material factor is read at all, which [texturedWedgeGlb]'s deliberately neutral white cannot be.
 */
internal fun tallBlockerGlb(): ByteArray = glb(
    json = """
    {"asset": {"version": "2.0"},
     "buffers": [{"byteLength": 60}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 48},
       {"buffer": 0, "byteOffset": 48, "byteLength": 12}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 6, "type": "SCALAR"}],
     "materials": [{"doubleSided": true, "pbrMetallicRoughness": {
       "baseColorFactor": [$BLOCKER_FACTOR_JSON]}}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "material": 0}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    binChunk = modelBin {
        BLOCKER_CORNERS.forEach { f32(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
        u16(*BLOCKER_INDICES)
    },
)

/**
 * [BLOCKER_COLOUR] as glTF's own 0..1 factor, at full `Double` precision rather than as a rounded
 * decimal, so the shader's multiply back up by 255 lands on the integer and the readback comparison
 * is not spending its tolerance on the fixture's own rounding.
 */
private val BLOCKER_FACTOR_JSON: String = BLOCKER_COLOUR.joinToString(", ") { (it / 255.0).toString() }

// ---- derived bounds ------------------------------------------------------------------------------

private fun boundsOf(points: List<DoubleArray>): LocalBox = LocalBox(
    minX = points.minOf { it[0] },
    maxX = points.maxOf { it[0] },
    minY = points.minOf { it[1] },
    maxY = points.maxOf { it[1] },
    minZ = points.minOf { it[2] },
    maxZ = points.maxOf { it[2] },
)

/**
 * The box the moving triangle can reach at any joint angle: for each vertex, the circle of radius
 * `MOVING_JOINT_WEIGHT * |v - pivot|` about `(1 - w) * v + w * pivot`, which is where the weight
 * blend of the identity and a rotation about `pivot` can put it. `z` never moves, because the
 * rotation is about `z`.
 */
private fun sweptMovingBounds(): LocalBox {
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    MOVING_TRIANGLE.forEach { vertex ->
        val offsetX = vertex[0] - MOVING_JOINT_BIND_POSITION[0]
        val offsetY = vertex[1] - MOVING_JOINT_BIND_POSITION[1]
        val radius = MOVING_JOINT_WEIGHT * sqrt(offsetX * offsetX + offsetY * offsetY)
        val centreX = (1.0 - MOVING_JOINT_WEIGHT) * vertex[0] + MOVING_JOINT_WEIGHT * MOVING_JOINT_BIND_POSITION[0]
        val centreY = (1.0 - MOVING_JOINT_WEIGHT) * vertex[1] + MOVING_JOINT_WEIGHT * MOVING_JOINT_BIND_POSITION[1]
        minX = min(minX, centreX - radius)
        maxX = max(maxX, centreX + radius)
        minY = min(minY, centreY - radius)
        maxY = max(maxY, centreY + radius)
    }
    return LocalBox(
        minX = minX,
        maxX = maxX,
        minY = minY,
        maxY = maxY,
        minZ = MOVING_TRIANGLE.minOf { it[2] },
        maxZ = MOVING_TRIANGLE.maxOf { it[2] },
    )
}

private fun sweptBounds(): LocalBox {
    val moving = sweptMovingBounds()
    val static = boundsOf(STATIC_TRIANGLE)
    return LocalBox(
        minX = min(static.minX, moving.minX),
        maxX = max(static.maxX, moving.maxX),
        minY = min(static.minY, moving.minY),
        maxY = max(static.maxY, moving.maxY),
        minZ = min(static.minZ, moving.minZ),
        maxZ = max(static.maxZ, moving.maxZ),
    )
}

// ---- a GLB assembler -----------------------------------------------------------------------------

private const val GLB_MAGIC = 0x46546C67
private const val GLB_VERSION = 2
private const val JSON_CHUNK_TYPE = 0x4E4F534A
private const val BIN_CHUNK_TYPE = 0x004E4942
private const val JSON_PAD_BYTE = 0x20

/**
 * [json] and [binChunk] wrapped in a glTF 2.0 binary container: the 12-byte header, a JSON chunk
 * padded to a four-byte boundary with `0x20` (the only pad byte the specification permits there),
 * and a zero-padded BIN chunk. The declared total length is the real one, because `scanGlb` requires
 * exact equality and folds five separate authoring accidents into that one comparison.
 */
private fun glb(json: String, binChunk: ByteArray): ByteArray {
    val jsonBytes = json.encodeToByteArray()
    val jsonPadding = (4 - jsonBytes.size % 4) % 4
    val binPadding = (4 - binChunk.size % 4) % 4
    return modelBin {
        u32(GLB_MAGIC, GLB_VERSION, 12 + 8 + jsonBytes.size + jsonPadding + 8 + binChunk.size + binPadding)
        u32(jsonBytes.size + jsonPadding, JSON_CHUNK_TYPE)
        raw(jsonBytes)
        repeat(jsonPadding) { u8(JSON_PAD_BYTE) }
        u32(binChunk.size + binPadding, BIN_CHUNK_TYPE)
        raw(binChunk)
        repeat(binPadding) { u8(0) }
    }
}

/** Little-endian, because glTF fixes the BIN chunk's byte order and every published target agrees. */
private class ModelBinWriter {
    private val written = ArrayList<Byte>()

    fun u8(vararg values: Int) {
        values.forEach { written += (it and 0xFF).toByte() }
    }

    fun u16(vararg values: Int) {
        values.forEach { u8(it, it ushr 8) }
    }

    fun u32(vararg values: Int) {
        values.forEach { u8(it, it ushr 8, it ushr 16, it ushr 24) }
    }

    fun f32(vararg values: Float) {
        values.forEach { u32(it.toRawBits()) }
    }

    fun raw(bytes: ByteArray) {
        bytes.forEach { written += it }
    }

    /**
     * The column-major inverse of `translate(position)`, which is `translate(-position)`.
     *
     * Written as a whole 16-element matrix rather than assembled from an identity, so the layout the
     * decoder has to read — translation in the **last column**, elements 12, 13 and 14 — is visible
     * here. Transposed, those three land in the last row and the matrix stops being affine at all.
     */
    fun inverseBindTranslation(position: DoubleArray) {
        f32(1f, 0f, 0f, 0f)
        f32(0f, 1f, 0f, 0f)
        f32(0f, 0f, 1f, 0f)
        f32((-position[0]).toFloat(), (-position[1]).toFloat(), (-position[2]).toFloat(), 1f)
    }

    fun build(): ByteArray = written.toByteArray()
}

private fun modelBin(write: ModelBinWriter.() -> Unit): ByteArray = ModelBinWriter().apply(write).build()
