package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Cycle F-2's gate: draw a real GLB through the **public** API onto a real driver, read the whole
 * frame back, and assert relationships between the pixels.
 *
 * **Why this exists at all.** Every other model assertion in the tree is a call log against
 * `RecordingGlBinding`, and the basemap cycle is the reason that is not enough: it shipped a suite of
 * exactly that shape and **four real defects survived it** until someone rendered a frame and looked
 * at it — a blank basemap, a billboard bisected at pitch, a coplanar quad z-fighting itself, and a
 * soft image from LOD hysteresis. Not one of them issued a wrong GL call; every call log was
 * perfect. This file is the difference.
 *
 * **What it catches**, one case per trap this cycle can actually produce:
 * - a model that draws nothing at all, and a model that draws **black** — the mipmap-incompleteness
 *   trap directly, since honouring a `LINEAR_MIPMAP_LINEAR` minification filter without generating
 *   the chain leaves the texture incomplete and an incomplete texture samples black on a real driver;
 * - a base-colour texture that is never sampled — an interior point of each live triangle must carry
 *   that triangle's **own** texel, and the fixture's white base-colour factor means a dropped texture
 *   reads back white, 215 of 255 away in some channel;
 * - a rig that loads and does nothing — the same model at two animation times must differ, and must
 *   differ **in the band the moving joint governs** while leaving the static band bit-identical;
 * - animation bound to frame count rather than to time — two frames at one `timeSeconds` with
 *   different `frameIndex` values must be bit-identical;
 * - a model that does not occlude, and one that is not occluded — ADR 0030's own verification
 *   obligation, which Task 12 landed with call-log evidence only;
 * - a runaway transform or an exploded joint matrix — every non-clear pixel must fall inside bounds
 *   projected from the fixture's own local geometry, which also makes the index buffer load-bearing:
 *   the fixture's vertex 0 is a decoy no index reaches, and a draw that swept the vertex array in
 *   order would pull it into a triangle and miss the bounds by tens of pixels.
 *
 * **What it does NOT catch, stated so nobody reads more into a green run than is there.** A subtly
 * wrong skinning weight, an inverted normal, an off-by-a-little bind pose, a mis-ordered blend, and
 * any wrong picture that still lands inside the projected bounds with the right texels at the sampled
 * points — all pass. It stores no baseline and compares no image, so it says nothing about how a
 * model *looks*; golden images remain Cycle J's. ADR 0026's light is deliberately **not** exercised:
 * the fixture carries no `NORMAL`, which pins the shading term at exactly `1.0` and is what makes an
 * expected colour writable by hand at all. Alpha modes, `doubleSided` culling, vertex colour, and
 * every material property but base colour are untested here.
 *
 * **Two of the six cases cannot detect absence, and case 1 is why they do not have to.** Measured, by
 * making `drawModels` a no-op and running the suite: four cases failed and two passed. Case 4 compares
 * two frames for equality, and two blank frames are equal; case 6 asserts nothing falls *outside* a
 * bound, and nothing drawn falls nowhere. Both are upper-bound assertions by construction, and case 1
 * is the lower bound that covers them — which is exactly why "something drew at all" is asserted
 * separately rather than folded into a case that also checks colour.
 *
 * **Every tolerance below states the smallest defect it still detects**, because a budget with no
 * stated floor is a number someone tuned until it passed. And no assertion is an exact pixel count:
 * GitHub's hosted macOS runners have no GPU and report `Apple Software Renderer`, whose rasteriser
 * measurably drops primitives — `0.3.0`'s publication failed once on exactly that, with
 * `kotlin.AssertionError at null:-1` as its entire diagnostic. Where a case cannot survive a software
 * rasteriser it skips out loud rather than being tolerated into meaninglessness.
 */
internal fun runModelReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    @Suppress("UNUSED_PARAMETER") dialect: ShaderDialect,
) {
    val target = createModelReadbackTarget(binding)
    println("RenG model readback: driver=${binding.getString(GL_RENDERER)}")

    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS),
            transport = ModelReadbackTransport(),
            store = ModelReadbackStore(),
        ),
        binding,
        probe,
    )
    val failures = ModelCollectedFailures()
    try {
        val world = ModelReadbackWorld(renderer, binding, target)

        failures.run("drawsAndIsNotBlack") { assertTheModelDrawsAndIsNotBlack(world) }
        failures.run("textureIsApplied") { assertTheTextureIsApplied(world) }
        failures.run("skinningDeforms") { assertSkinningDeforms(world) }
        failures.run("timeNotFrameCount") { assertAnimationIsBoundToTime(world) }
        failures.run("occlusionIsReal") { assertOcclusionIsReal(world) }
        failures.run("coverageIsBounded") { assertCoverageIsBounded(world) }
    } finally {
        renderer.close()
    }
    failures.throwIfAny()
}

/** The readback target's edge, in pixels. Square, so a transposed index cannot pass by shape. */
internal const val MODEL_READBACK_PIXELS: Int = 160

private const val MODEL_READBACK_CASE_COUNT: Int = 6

/**
 * Collects each case's failure instead of throwing at the first, for the reason
 * `BasemapReadbackSuite` records: Gradle renders a Kotlin/Native failure as its exception class and
 * location and never its message, so a run that stops at case one tells a reader almost nothing. Each
 * case draws into a freshly cleared target and shares nothing but the renderer, so a later case is
 * still worth believing after an earlier one failed. Every message is printed as it happens too,
 * because the text below reaches a human through the test report rather than the build log.
 */
private class ModelCollectedFailures {
    private val messages: MutableList<String> = mutableListOf()

    fun run(name: String, case: () -> Unit) {
        try {
            case()
        } catch (error: AssertionError) {
            val message = error.message ?: error.toString()
            messages += "[$name] $message"
            println("RenG model readback FAILED [$name] $message")
        }
    }

    fun throwIfAny() {
        if (messages.isEmpty()) return
        throw AssertionError(
            "${messages.size} of $MODEL_READBACK_CASE_COUNT model readback cases failed:\n" +
                messages.joinToString("\n"),
        )
    }
}

// ---- the scene -------------------------------------------------------------------------------

private const val WEDGE_GLB_URL: String = "https://models.example/wedge.glb"
private const val BLOCKER_GLB_URL: String = "https://models.example/blocker.glb"

/**
 * An asymmetric camera: no two of latitude, unwrapped longitude, bearing and pitch share a value or a
 * sign pattern, so an x/y transposition anywhere in the transform chain moves the model rather than
 * leaving it where it was.
 *
 * Pitch is **zero**, and that is a deliberate narrowing rather than an oversight. Every placement in
 * this suite is `CONTEXT.md`'s billboard — `MAP` position with `SCREEN` rotation and scale — whose
 * model matrix is composed in camera space at the anchor's view-space position, so its projected size
 * and shape are identical at every pitch and only the depth behind it changes. A pitch sweep would
 * therefore add wall-clock and no coverage here; the pitch-sensitive case is the billboard-versus-
 * ground one, which is `BasemapReadbackSuite`'s and already swept there.
 */
private val MODEL_CAMERA: Camera = Camera(
    latitude = 37.7749,
    unwrappedLongitude = -122.4194,
    zoom = 16.0,
    bearing = 0.0,
    pitch = 0.0,
)

/**
 * Where the anchor lands, in readback pixels.
 *
 * Both models are `MAP`-positioned at exactly the camera's own centre at altitude zero, so the anchor
 * projects to the frame's centre by construction and every projected bound below is analytic rather
 * than measured. Readback rows run bottom-up (`glReadPixels`) and the billboard's local `+y` is
 * screen up, so local `y` and readback row increase together; local `+x` is screen right, so local
 * `x` and column increase together.
 */
private const val ANCHOR_PIXEL: Double = MODEL_READBACK_PIXELS / 2.0

private fun modelPlacement(): Placement = Placement(
    positionMode = AnchoringMode.MAP,
    position = Vector3(MODEL_CAMERA.latitude, MODEL_CAMERA.unwrappedLongitude, 0.0),
    rotationMode = AnchoringMode.SCREEN,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.SCREEN,
    scale = MODEL_SCREEN_SCALE,
)

private fun wedge(timeSeconds: Double?): Model = Model(
    placement = modelPlacement(),
    glb = ResourceLocator(WEDGE_GLB_URL),
    animationTracks = timeSeconds
        ?.let { listOf(AnimationTrack(AnimationSelector.Name(WEDGE_ANIMATION_NAME), it)) }
        .orEmpty(),
)

private fun blocker(): Model = Model(placement = modelPlacement(), glb = ResourceLocator(BLOCKER_GLB_URL))

private fun modelPlan(frameIndex: Long, models: List<Model>, geometries: List<Geometry> = emptyList()): FramePlan =
    FramePlan(
        frameIndex = frameIndex,
        camera = MODEL_CAMERA,
        drawBasemap = false,
        models = models,
        geometries = geometries,
    )

// ---- the cases -------------------------------------------------------------------------------

/**
 * Case 1. Something drew, and it is not black.
 *
 * Two claims in one, because separating them would let each pass for the other's reason. "Something
 * drew" is the blank-frame guard; "not black" is the mipmap-incompleteness guard, which is the one
 * failure mode this cycle could most easily have shipped — an incomplete texture samples black on a
 * real driver and reads exactly like a shader bug.
 *
 * The floor: [MINIMUM_DRAWN_PIXELS] is 200 against a bind-pose footprint of roughly 2,900 pixels, so
 * this still fails if fourteen fifteenths of the model vanishes. It is not a coverage assertion —
 * case 6 bounds coverage from above — only a "the draw happened" one, and it is deliberately far
 * below the true figure so a rasteriser that rounds a boundary differently cannot trip it.
 */
private fun assertTheModelDrawsAndIsNotBlack(world: ModelReadbackWorld) {
    val frame = world.render(modelPlan(frameIndex = 1L, models = listOf(wedge(timeSeconds = null))))
    val drawn = frame.pixelsDifferingFrom(MODEL_ABSENT)
    assertTrue(
        drawn.size >= MINIMUM_DRAWN_PIXELS,
        "the model drew ${drawn.size} pixels, below the floor of $MINIMUM_DRAWN_PIXELS: " +
            "a model that draws nothing issues a perfectly correct call log",
    )
    val black = drawn.count { (x, y) -> frame.isNear(x, y, BLACK, MODEL_CHANNEL_TOLERANCE) }
    assertEquals(
        0,
        black,
        "$black of ${drawn.size} drawn pixels are black: an incomplete texture — a mipmap " +
            "minification filter with no chain generated — samples black on a real driver",
    )
}

/**
 * Case 2. The base-colour texture is sampled, and each triangle carries its own texel.
 *
 * The sample point is each triangle's projected **centroid**, which is inside the triangle whatever
 * the projection did to its spacing. The fixture's base-colour factor is white, so a draw that never
 * binds the texture reads back white — 215 of 255 from the static triangle's texel and 159 from the
 * moving one's, against a tolerance of [MODEL_CHANNEL_TOLERANCE]. Two triangles rather than one
 * because a single sample cannot tell a correct texture from one whose coordinates are constant.
 */
private fun assertTheTextureIsApplied(world: ModelReadbackWorld) {
    val frame = world.render(modelPlan(frameIndex = 2L, models = listOf(wedge(timeSeconds = null))))
    listOf(
        "static" to (STATIC_TRIANGLE_CORNERS to STATIC_TRIANGLE_TEXEL),
        "moving" to (MOVING_TRIANGLE_CORNERS to MOVING_TRIANGLE_TEXEL),
    ).forEach { (name, expectation) ->
        val (corners, texel) = expectation
        val (x, y) = projectedCentroid(corners)
        assertTrue(
            frame.isNear(x, y, texel, MODEL_CHANNEL_TOLERANCE),
            "the $name triangle's centroid at ($x, $y) reads ${frame.describe(x, y)}, not its own " +
                "texel ${texel.toList()}; white would mean the texture was never sampled",
        )
    }
}

/**
 * Case 3. Skinning actually deforms the mesh.
 *
 * The only assertion in this file that distinguishes a working rig from one that loads, uploads a
 * palette, and changes nothing. Two claims, and both are needed: the frames must differ **inside the
 * band the moving joint governs**, and must be bit-identical **inside the band it cannot reach**. The
 * second is what stops a global transform error — a wrong bind pose, a palette applied to every
 * vertex — from passing as deformation.
 *
 * The two sampled times avoid every symmetry this cycle has already been caught by: neither is zero,
 * neither is the duration (which `timeSeconds % durationSeconds` maps back onto zero), and neither is
 * the midpoint, where spherical and normalized-linear interpolation agree exactly.
 *
 * The floor: [MINIMUM_DEFORMED_PIXELS] is 60 against a moving-triangle footprint of roughly 1,400
 * pixels. A rig that moves less than a twentieth of its own triangle is not one this suite is
 * pretending to certify — case 3 answers "does it deform at all", not "does it deform correctly".
 */
private fun assertSkinningDeforms(world: ModelReadbackWorld) {
    val early = world.render(modelPlan(frameIndex = 3L, models = listOf(wedge(timeSeconds = 0.35))))
    val late = world.render(modelPlan(frameIndex = 4L, models = listOf(wedge(timeSeconds = 1.55))))

    val staticColumnLimit = projectedColumn(STATIC_TRIANGLE_LOCAL_BOUNDS.maxX)
    val movingColumnFloor = projectedColumn(MOVING_TRIANGLE_SWEPT_LOCAL_BOUNDS.minX)
    assertTrue(
        staticColumnLimit < movingColumnFloor,
        "the fixture's two bands overlap in columns [$staticColumnLimit, $movingColumnFloor); this " +
            "case cannot separate what moved from what did not",
    )

    val movingDifferences = early.pixelsDifferingFrom(late) { x, _ -> x >= movingColumnFloor }
    assertTrue(
        movingDifferences >= MINIMUM_DEFORMED_PIXELS,
        "only $movingDifferences pixels changed in the moving band between t=0.35 and t=1.55, below " +
            "the floor of $MINIMUM_DEFORMED_PIXELS: a rig that loads and does nothing looks like this",
    )

    val staticDifferences = early.pixelsDifferingFrom(late) { x, _ -> x <= staticColumnLimit }
    assertEquals(
        0,
        staticDifferences,
        "$staticDifferences pixels changed in the band the animated joint cannot reach: the " +
            "deformation is global, which is a transform defect rather than skinning",
    )
}

/**
 * Case 4. Animation is a function of `timeSeconds`, not of how many frames have been drawn.
 *
 * The two frames differ in `frameIndex` and in nothing else, so they must be **bit-identical** — not
 * near, identical. There is no tolerance here on purpose: the same inputs through the same driver
 * produce the same bytes, and any difference at all is state leaking between frames.
 */
private fun assertAnimationIsBoundToTime(world: ModelReadbackWorld) {
    val first = world.render(modelPlan(frameIndex = 5L, models = listOf(wedge(timeSeconds = 0.7))))
    val second = world.render(modelPlan(frameIndex = 6L, models = listOf(wedge(timeSeconds = 0.7))))
    assertTrue(
        first.bytes.contentEquals(second.bytes),
        "two frames at the same timeSeconds and different frameIndex values differ in " +
            "${first.pixelsDifferingFrom(second) { _, _ -> true }} pixels: animation is being driven " +
            "by frame count, or state is leaking between frames",
    )
}

/**
 * Case 5. Occlusion is real — ADR 0030's own verification obligation.
 *
 * Two halves, because the ADR makes two separate claims and only one of them is about depth.
 *
 * **A model paints over the flat map-plane content beneath it.** That is ADR 0030's *order* clause:
 * ground and geometries draw first and write no depth, models draw after. A `Geometry` stands in for
 * the ground here, and legitimately — the two share a depth phase exactly, both testing and neither
 * writing — but it is a substitution and is named as one: literal Rentile ground is
 * `BasemapReadbackSuite`'s and is not re-staged here.
 *
 * **A model behind another is occluded by it.** That is the *depth* clause, and it is the
 * discriminating half. The blocker is declared **first** and sits at a constant local `z` in front of
 * every wedge vertex, so with depth writes the blocker wins on depth and without them the wedge wins
 * on declaration order. The two outcomes are opposite, which is what makes this a test rather than a
 * demonstration.
 */
private fun assertOcclusionIsReal(world: ModelReadbackWorld) {
    val overGround = world.render(
        modelPlan(
            frameIndex = 7L,
            models = listOf(wedge(timeSeconds = null)),
            geometries = listOf(mapPlaneGeometry()),
        ),
    )
    val (staticX, staticY) = projectedCentroid(STATIC_TRIANGLE_CORNERS)
    assertTrue(
        overGround.isNear(staticX, staticY, STATIC_TRIANGLE_TEXEL, MODEL_CHANNEL_TOLERANCE),
        "over map-plane content the model's centroid reads ${overGround.describe(staticX, staticY)}, " +
            "not its own texel: the map plane is painting over the model it stands on",
    )

    val behindBlocker = world.render(
        modelPlan(frameIndex = 8L, models = listOf(blocker(), wedge(timeSeconds = null))),
    )
    assertTrue(
        behindBlocker.isNear(staticX, staticY, BLOCKER_COLOUR, MODEL_CHANNEL_TOLERANCE),
        "behind a nearer model the wedge's centroid reads ${behindBlocker.describe(staticX, staticY)}, " +
            "not the blocker's ${BLOCKER_COLOUR.toList()}: the later-declared model won, which is " +
            "what happens when the model pass writes no depth (ADR 0030)",
    )
}

/**
 * Case 6. Every drawn pixel falls inside bounds projected from the fixture's own local geometry.
 *
 * This is the runaway-transform and exploded-joint-matrix guard, and it is also what makes the index
 * buffer load-bearing: the fixture's vertex 0 is a decoy no index reaches, sitting far outside these
 * bounds, so a draw that ignored the index buffer and swept the vertex array in order would pull it
 * into a triangle and miss by tens of pixels.
 *
 * The bounds are the **swept** ones — every angle the animation can reach — so this holds at any
 * sampled time rather than at the one drawn. The margin is [BOUNDS_MARGIN_PIXELS], one pixel of
 * rasteriser boundary rounding on each side; the smallest defect it still detects is a two-pixel
 * excursion, against a footprint whose own half-width is about 36 pixels.
 */
private fun assertCoverageIsBounded(world: ModelReadbackWorld) {
    val frame = world.render(modelPlan(frameIndex = 9L, models = listOf(wedge(timeSeconds = 1.55))))
    val minColumn = projectedColumn(WEDGE_SWEPT_LOCAL_BOUNDS.minX) - BOUNDS_MARGIN_PIXELS
    val maxColumn = projectedColumn(WEDGE_SWEPT_LOCAL_BOUNDS.maxX) + BOUNDS_MARGIN_PIXELS
    val minRow = projectedRow(WEDGE_SWEPT_LOCAL_BOUNDS.minY) - BOUNDS_MARGIN_PIXELS
    val maxRow = projectedRow(WEDGE_SWEPT_LOCAL_BOUNDS.maxY) + BOUNDS_MARGIN_PIXELS

    val outside = frame.pixelsDifferingFrom(MODEL_ABSENT)
        .filter { (x, y) -> x < minColumn || x > maxColumn || y < minRow || y > maxRow }
    assertEquals(
        emptyList(),
        outside.take(8),
        "${outside.size} drawn pixels fall outside the model's projected bounds " +
            "columns [$minColumn, $maxColumn] rows [$minRow, $maxRow] " +
            "(${WEDGE_SWEPT_LOCAL_BOUNDS.describe()} at scale $MODEL_SCREEN_SCALE): a runaway " +
            "transform, an exploded joint matrix, or a draw that ignored the index buffer",
    )
}

// ---- projection ------------------------------------------------------------------------------

private fun projectedColumn(localX: Double): Int = (ANCHOR_PIXEL + localX * MODEL_SCREEN_SCALE).toInt()

private fun projectedRow(localY: Double): Int = (ANCHOR_PIXEL + localY * MODEL_SCREEN_SCALE).toInt()

/** The centroid of three local corners, projected — inside the triangle they span, whatever the
 * projection did to their spacing. */
private fun projectedCentroid(corners: List<DoubleArray>): Pair<Int, Int> {
    val localX = corners.sumOf { it[0] } / corners.size
    val localY = corners.sumOf { it[1] } / corners.size
    return projectedColumn(localX) to projectedRow(localY)
}

private const val MINIMUM_DRAWN_PIXELS: Int = 200
private const val MINIMUM_DEFORMED_PIXELS: Int = 60
private const val BOUNDS_MARGIN_PIXELS: Int = 1

/** Black, for the mipmap-incompleteness guard. Alpha is not compared; only the colour channels are. */
private val BLACK: IntArray = intArrayOf(0, 0, 0, 255)

// ---- the map-plane stand-in ---------------------------------------------------------------------

/**
 * A flat opaque quad at altitude zero, spanning well past the frame, painted by the smallest shader
 * pair that is still a real consumer shader: `aPosition` and `uModelViewProjection` from the
 * documented interface and nothing else. Flat and opaque so a pixel either is the map plane or is
 * not — a gradient would turn case 5 into a threshold argument.
 */
private fun mapPlaneGeometry(): Geometry = Geometry(
    topLeft = Vector3(MODEL_CAMERA.latitude + 0.004, MODEL_CAMERA.unwrappedLongitude - 0.004, 0.0),
    bottomRight = Vector3(MODEL_CAMERA.latitude - 0.004, MODEL_CAMERA.unwrappedLongitude + 0.004, 0.0),
    shaderPair = ShaderPair(MAP_PLANE_VERTEX_SOURCE, MAP_PLANE_FRAGMENT_SOURCE),
)

private val MAP_PLANE_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n"

private val MAP_PLANE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "layout(location = 0) out vec4 mapPlaneColour;\n" +
        "void main() {\n" +
        "    mapPlaneColour = vec4(" +
        "${MODEL_GROUND_COLOUR[0]}.0 / 255.0, " +
        "${MODEL_GROUND_COLOUR[1]}.0 / 255.0, " +
        "${MODEL_GROUND_COLOUR[2]}.0 / 255.0, 1.0);\n" +
        "}\n"

// ---- the harness -----------------------------------------------------------------------------

private class ModelReadbackWorld(
    private val renderer: Renderer,
    private val binding: GlBinding,
    private val target: Int,
) {
    private val renderTarget: RenderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))

    fun render(plan: FramePlan): ReadFrame {
        clearTarget(binding, target)
        runBlocking { renderer.prepare(plan) }.use { prepared ->
            renderer.draw(prepared, renderTarget)
        }
        return readFrame(binding, target)
    }
}

/** One frame's RGBA bytes, bottom row first, exactly as `glReadPixels` delivers them. */
private class ReadFrame(val bytes: ByteArray) {
    fun channel(x: Int, y: Int, channel: Int): Int =
        bytes[(y * MODEL_READBACK_PIXELS + x) * 4 + channel].toInt() and 0xFF

    fun isNear(x: Int, y: Int, colour: IntArray, tolerance: Int): Boolean =
        (0 until 3).all { abs(channel(x, y, it) - colour[it]) <= tolerance }

    fun describe(x: Int, y: Int): String = (0 until 4).map { channel(x, y, it) }.toString()

    fun pixelsDifferingFrom(colour: IntArray): List<Pair<Int, Int>> {
        val differing = ArrayList<Pair<Int, Int>>()
        for (y in 0 until MODEL_READBACK_PIXELS) {
            for (x in 0 until MODEL_READBACK_PIXELS) {
                if (!isNear(x, y, colour, MODEL_CHANNEL_TOLERANCE)) differing += x to y
            }
        }
        return differing
    }

    fun pixelsDifferingFrom(other: ReadFrame, within: (Int, Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until MODEL_READBACK_PIXELS) {
            for (x in 0 until MODEL_READBACK_PIXELS) {
                if (!within(x, y)) continue
                val index = (y * MODEL_READBACK_PIXELS + x) * 4
                if ((0 until 4).any { bytes[index + it] != other.bytes[index + it] }) count++
            }
        }
        return count
    }
}

private fun createModelReadbackTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER,
        GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D,
        texture,
        0,
    )
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the model readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private fun clearTarget(binding: GlBinding, target: Int) {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.clearColor(
        MODEL_ABSENT[0] / 255.0f,
        MODEL_ABSENT[1] / 255.0f,
        MODEL_ABSENT[2] / 255.0f,
        MODEL_ABSENT[3] / 255.0f,
    )
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
}

private fun readFrame(binding: GlBinding, target: Int): ReadFrame {
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    val bytes = ByteArray(MODEL_READBACK_PIXELS * MODEL_READBACK_PIXELS * 4)
    binding.readPixels(0, 0, MODEL_READBACK_PIXELS, MODEL_READBACK_PIXELS, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return ReadFrame(bytes)
}

// ---- the adapters ----------------------------------------------------------------------------

private class ModelReadbackTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val body = when (request.locator.value) {
            WEDGE_GLB_URL -> texturedWedgeGlb()
            BLOCKER_GLB_URL -> tallBlockerGlb()
            else -> error("the model readback fixture serves no body for ${request.resourceClass}")
        }
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "model/gltf-binary"),
        )
    }
}

/**
 * An in-memory store. RenG is pure and owns no cache, so the suite supplies one; keeping it in a map
 * rather than refusing every read exercises the second-prepare path a real consumer would hit.
 */
private class ModelReadbackStore : Store {
    private val stored: MutableMap<String, StoredRawResource> = mutableMapOf()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = stored[key.stableId]

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        stored[key.stableId] = resource
    }
}
