package com.rohittp.reng.smoke.harness

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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The sequence the harness renders.
 *
 * A static frame cannot show that tile selection and level of detail track the camera, so every
 * property the basemap cycle reads moves across the sequence: latitude, unwrapped longitude, zoom,
 * bearing and pitch. Three frames near the middle carry `drawBasemap = false`, which is both the
 * negative case and the thing that stops "the ground is there" being a vacuous observation.
 */
internal const val FRAME_COUNT: Int = 48

/** The frames that ask for no ground at all. Three of them, so they are visible at twelve frames a second. */
internal val NEGATIVE_FRAMES: IntRange = 30..32

/** San Francisco: a coastline, a bay, a bridge and a street grid, so a wrong render is not a plausible one. */
private const val ANCHOR_LATITUDE: Double = 37.7955
private const val ANCHOR_LONGITUDE: Double = -122.4100

internal const val STICKER_F_URL: String = "reng-harness:sticker-f.png"
internal const val STICKER_PIN_URL: String = "reng-harness:sticker-pin.png"

/**
 * [groundless] forces `drawBasemap = false` on every frame, and [labelless] forces
 * `drawLabels = false` on every frame. Neither is part of the storyboard: the first lets the
 * overlay content be watched across the whole camera path when the ground itself will not draw,
 * and the second exists so a labelled run and an unlabelled run of the same camera path can be
 * subtracted, which is the only way to count a frame's label ink rather than squint at it.
 */
internal fun framePlans(
    groundless: Boolean = false,
    modelUrl: String? = null,
    labelless: Boolean = false,
    globe: Boolean = false,
    baseZoom: Double = DEFAULT_BASE_ZOOM,
): List<FramePlan> =
    (0 until FRAME_COUNT).map { framePlan(it, groundless, modelUrl, labelless, globe, baseZoom) }

/**
 * The storyboard's own zoom, unchanged from the sweep every previous cycle used: two and a half levels
 * crossing three integer boundaries, at a scale where a city's labels are legible.
 *
 * A globe wants a different one. At zoom 11.5 the sphere is far larger than the viewport, so its
 * curvature is off-screen and a correct globe is indistinguishable from a flat map -- which is the
 * measured sagitta result stated the other way round: 0.44 logical pixels of bow at zoom 10. `--zoom`
 * exists so the globe runs can sit where the planet is actually visible.
 */
internal const val DEFAULT_BASE_ZOOM: Double = 11.5
private const val ZOOM_SPAN: Double = 2.5

private fun framePlan(
    index: Int,
    groundless: Boolean,
    modelUrl: String?,
    labelless: Boolean,
    globe: Boolean,
    baseZoom: Double,
): FramePlan {
    val t = index.toDouble() / (FRAME_COUNT - 1).toDouble()
    return FramePlan(
        frameIndex = index.toLong(),
        camera = Camera(
            // A short north-easterly drift, so consecutive frames need overlapping but not
            // identical tile sets.
            latitude = ANCHOR_LATITUDE + 0.010 * t,
            unwrappedLongitude = ANCHOR_LONGITUDE + 0.016 * t,
            // Two and a half levels of detail, crossing three integer zoom boundaries.
            zoom = baseZoom + ZOOM_SPAN * t,
            // Three quarters of a turn, so a frame that ignores bearing is obvious.
            bearing = 270.0 * t,
            // Flat, then tilted: the pitched half is where the ground's horizon behaviour shows.
            pitch = 55.0 * smoothStep(t),
        ),
        projectionMode = if (globe) ProjectionMode.GLOBE else ProjectionMode.MERCATOR,
        drawBasemap = !groundless && index !in NEGATIVE_FRAMES,
        drawLabels = !labelless,
        stickers = listOf(mapAnchoredPin(), screenAnchoredF()),
        models = modelUrl?.let { listOf(animatedModel(it, t)) }.orEmpty(),
        geometries = listOf(groundGrid()),
    )
}

/**
 * The Cycle F-2 model, when the harness was given one to fetch.
 *
 * **`MAP` rotation with `SCREEN` scale**, which is the pairing that makes a moving camera worth
 * watching. Map-anchored rotation resolves against the anchor's own east/north/up basis, so the model
 * turns with the world as the storyboard's three-quarter bearing sweep goes past — and that is also
 * the only way to see ADR 0026's light doing its job, since a world-anchored light keeps one side of
 * the model bright while the camera orbits. Screen-anchored scale keeps it a constant number of
 * pixels across two and a half levels of detail; map-anchored scale is metres per local unit, and a
 * car-sized asset at zoom 11.5 is comfortably smaller than one pixel.
 *
 * **No `SCREEN`-positioned negative frame.** ADR 0029 refuses one at frame planning, so a frame
 * carrying it would fail, and this harness exits non-zero when any frame fails. A storyboard with a
 * guaranteed failure in it teaches whoever runs it to ignore the exit code, which costs more than the
 * case is worth here — the refusal is pinned by `FramePlanningCoreTest` and `MercatorSpatialPlannerTest`
 * instead, where a deliberate failure is the assertion rather than noise.
 *
 * The animation time advances with the sequence rather than with the frame index, so a rig that
 * ignores `timeSeconds` and counts frames looks identical for the first cycle and then drifts.
 * `AnimationSelector.Index(0)` rather than a name, because the harness cannot know what any
 * particular consumer asset calls its animations.
 */
private fun animatedModel(modelUrl: String, t: Double): Model = Model(
    placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(ANCHOR_LATITUDE + 0.0045, ANCHOR_LONGITUDE + 0.0070, 0.0),
        rotationMode = AnchoringMode.MAP,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = MODEL_SCREEN_PIXELS_PER_UNIT,
    ),
    glb = ResourceLocator(modelUrl),
    animationTracks = listOf(AnimationTrack(AnimationSelector.Index(0L), t * MODEL_ANIMATION_SECONDS)),
)

/** Output pixels per model-local unit; a few-unit asset then covers a useful part of a 960x540 frame. */
private const val MODEL_SCREEN_PIXELS_PER_UNIT: Double = 60.0

/** How many seconds of animation the whole sequence plays through. */
private const val MODEL_ANIMATION_SECONDS: Double = 4.0

private fun smoothStep(t: Double): Double = t * t * (3.0 - 2.0 * t)

/**
 * Map-anchored at a fixed coordinate, so it must slide across the frame as the camera moves and
 * must be occluded exactly as the ground is. Screen rotation and screen scale keep it upright and
 * a constant size in output pixels -- the billboard case named in `CONTEXT.md`.
 */
private fun mapAnchoredPin(): Sticker = Sticker(
    placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(ANCHOR_LATITUDE, ANCHOR_LONGITUDE, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    ),
    image = ResourceLocator(STICKER_PIN_URL),
)

/**
 * Screen-anchored at a fixed output-pixel position with a high z-index, so it must stay nailed to
 * the top-left corner while everything under it moves. The image is an `F`, asymmetric in both
 * axes: a flip or a rotation anywhere in the sticker path is unmistakable in the picture.
 */
private fun screenAnchoredF(): Sticker = Sticker(
    placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(48.0, 48.0, 10.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.5,
    ),
    image = ResourceLocator(STICKER_F_URL),
)

/**
 * A lat/lon-bounded quad the consumer's own shader pair paints, sitting on the ground over
 * downtown San Francisco.
 *
 * The shader declares `aTexCoord`, `uFrameIndex` and one consumer uniform, so the frame shows the
 * documented shader interface actually carrying values (ADR 0008): `v` runs 0 at the north edge to
 * 1 at the south, which is the one convention a mis-oriented ground quad would betray. The grid is
 * translucent, so the basemap under it must remain readable -- an opaque geometry would hide
 * exactly the thing this harness exists to look at.
 */
private fun groundGrid(): Geometry = Geometry(
    topLeft = Vector3(37.8010, -122.4250, 0.0),
    bottomRight = Vector3(37.7850, -122.3950, 0.0),
    shaderPair = ShaderPair(GRID_VERTEX_SOURCE, GRID_FRAGMENT_SOURCE),
    uniforms = mapOf("uTint" to ShaderValue.Vec3(1.0f, 0.35f, 0.0f)),
)

private val GRID_VERTEX_SOURCE: String =
    """
    #version 300 es
    in vec3 aPosition;
    in vec2 aTexCoord;
    uniform mat4 uModelViewProjection;
    out vec2 vTexCoord;
    void main() {
        vTexCoord = aTexCoord;
        gl_Position = uModelViewProjection * vec4(aPosition, 1.0);
    }
    """.trimIndent() + "\n"

private val GRID_FRAGMENT_SOURCE: String =
    """
    #version 300 es
    precision highp float;
    in vec2 vTexCoord;
    uniform uint uFrameIndex;
    uniform vec3 uTint;
    out vec4 rengOut;
    void main() {
        vec2 cell = fract(vTexCoord * 8.0);
        float line = step(0.92, max(cell.x, cell.y));
        // A scan band that walks north to south with the frame index, so a static geometry is
        // distinguishable from an animated one at a glance.
        float band = step(abs(vTexCoord.y - fract(float(uFrameIndex) / 24.0)), 0.03);
        float alpha = max(line * 0.75, band * 0.55);
        // The north edge is darker than the south edge, so a v-flip shows as an inverted ramp.
        vec3 colour = mix(uTint * 0.25, uTint, vTexCoord.y);
        rengOut = vec4(colour * alpha, alpha);
    }
    """.trimIndent() + "\n"

/**
 * The two sticker images, embedded so the harness needs no asset directory and commits no artwork
 * that could be mistaken for a rendered frame. Both are 64x64 RGBA PNGs generated once by hand.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun embeddedImages(): Map<String, Pair<ByteArray, String>> = mapOf(
    STICKER_F_URL to (Base64.decode(STICKER_F_PNG_BASE64) to "image/png"),
    STICKER_PIN_URL to (Base64.decode(STICKER_PIN_PNG_BASE64) to "image/png"),
)

/** A white-bordered magenta `F` on transparent black. */
private const val STICKER_F_PNG_BASE64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAfElEQVR42u3bMQoAIAgFUO9/sm5" +
        "lje1FGL4PDq5vUQQjmycAANiaJgEAAAAAAAAOATJGVioAAAAAAAAAAAAA1QDar8IAAAAAAMBB5O" +
        "2cBwAAAAAAAAAAsAkCAAAAAAAAAAAAAAAAAAAAAAAAuAjwcwAAAAAAAACvswAArExP8bxxAgs6kw" +
        "AAAABJRU5ErkJggg=="

/** A cyan diamond with a dark ring and a stalk pointing up. */
private const val STICKER_PIN_PNG_BASE64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAA4UlEQVR42u3a2Q2EMAwAUReR/iuj" +
        "l/ANQuFKfI6lLWCetLuQWER5Wmt99JHsAwAAAAAAAAAAAFAS4C4+NcLT+JQIl4FbP3zSIjyJT4vw" +
        "Jj4dwpf4NAh/4sMjzIgPizAzPhzCivgwCCvj3SNoxLtF0Ix3h2AR7wbBMt4cwUO8GYKneHUEj/Fq" +
        "CJ7jlyNEiF+GECl+OkLE+KkI5QHKfwX4EeRvkAchHoV5GeJ1mAMRjsQ4FOVYnIsRrsa4HOV6nAUJ" +
        "VmQ0EEpvipXeFWRblH3hQtviIwSpNKXjzwjCMAzDMCazAw614MP6tWD5AAAAAElFTkSuQmCC"
