package com.rohittp.reng.smoke.harness

import com.rohittp.reng.AltitudeMode
import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.Placement
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Sticker
import com.rohittp.reng.Vector3

/**
 * The corpus, authored once in Kotlin and emitted to JSON.
 *
 * **The JSON files are the source of truth; this file is how they were first written.** Eighteen
 * plans with nested placements and shader sources are not something to hand-write correctly, and
 * authoring them through RenG's own constructors means every one was validated before it reached a
 * file. After emission the files are ordinary data: hand-edit them, and `--emit-plans` on a file
 * re-encodes that file rather than regenerating it from here.
 *
 * **Every plan is named for what it proves, not for what it contains.** A reviewer looking at a
 * difference reads the name before the image, and "a model behind a ridge is occluded by it" tells
 * them what a changed pixel means where "model-3" does not.
 *
 * One frame each. A corpus is crossed with configs and rendered on both sides of a comparison, so
 * every extra frame is paid for four times over; the storyboard remains the one multi-frame entry,
 * because animation is the one thing a single frame cannot show.
 */

/**
 * **Why no camera here is pitched past 55 degrees.**
 *
 * The first corpus render failed three terrain plans outright with
 * `RESOURCE_LIMIT_EXCEEDED at FRAME_PLANNING` and a 100% undrawn frame, at pitch 70 and 75. That is
 * RenG working: an oblique camera at city zoom pulls the horizon into view, the visible ground runs
 * away to it, and the tile count passes `maximumBasemapTileInstances`, so the frame fails closed
 * with a typed error instead of drawing something partial. 55 is the storyboard's own maximum and
 * has rendered in every cycle since the basemap, so the corpus stays inside the envelope that is
 * known to work rather than inside the one `Camera` merely permits, which is anything under 90.
 *
 * A frame that cannot render is worthless as a baseline -- it compares equal to any other failure.
     *
     * **And no valley content sits at ABSOLUTE altitude 0, which is a corrected authoring error
     * rather than a style choice.** The first version of this corpus put a pin and a quad at 0 m in a
     * valley whose floor is about 1,200 m, then reported their absence as a renderer defect. They
     * were underground, and RenG was right to hide them: `ABSOLUTE` is ellipsoidal metres by
     * `CONTEXT.md`, so 0 m in Yosemite is roughly 1,200 m of rock overhead. Measured afterwards, the
     * same quad in `GROUND_RELATIVE` draws 22,231 pixels at pitch 0 and 10,641 at pitch 30. Valley
     * content is therefore either ground-relative, or absolute at an altitude that clears the floor
     * -- and the contrast between the two is what the pair of plans exists to show.
 */

/** Yosemite Valley: real relief, and the terrain corpus's anchor. */
private const val VALLEY_LATITUDE: Double = 37.7275
private const val VALLEY_LONGITUDE: Double = -119.5750

/** Downtown San Francisco: dense labels and tile seams at city zoom. */
private const val CITY_LATITUDE: Double = 37.7955
private const val CITY_LONGITUDE: Double = -122.4100

internal fun corpusPlans(): Map<String, List<FramePlan>> = mapOf(
    "storyboard" to storyboardPlans(),

    "a-plain-mercator-basemap-draws" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 12.0, 0.0, 0.0),
    ),

    "labels-draw-over-the-basemap" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 14.0, 0.0, 0.0),
    ),

    "labels-suppressed-leaves-the-basemap-intact" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 14.0, 0.0, 0.0),
        drawLabels = false,
    ),

    "a-pitched-camera-shows-tile-seams-in-depth" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 14.5, 0.0, 55.0),
    ),

    "a-rotated-camera-holds-label-orientation" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 14.0, 135.0, 45.0),
    ),

    "the-globes-limb-is-curved-against-the-void" to one(
        Camera(20.0, 0.0, 2.0, 0.0, 0.0),
        projectionMode = ProjectionMode.GLOBE,
    ),

    "the-north-polar-cap-closes-the-globe" to one(
        Camera(84.0, 0.0, 3.0, 0.0, 0.0),
        projectionMode = ProjectionMode.GLOBE,
    ),

    "the-south-polar-cap-closes-the-globe" to one(
        Camera(-84.0, 0.0, 3.0, 0.0, 0.0),
        projectionMode = ProjectionMode.GLOBE,
    ),

    "a-globe-at-city-zoom-matches-mercator" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 14.0, 0.0, 0.0),
        projectionMode = ProjectionMode.GLOBE,
    ),

    "terrain-relief-is-visible-from-the-valley-floor" to one(
        Camera(VALLEY_LATITUDE, VALLEY_LONGITUDE, 13.0, 45.0, 55.0),
    ),

    "terrain-flattens-when-the-camera-looks-straight-down" to one(
        Camera(VALLEY_LATITUDE, VALLEY_LONGITUDE, 13.0, 0.0, 0.0),
    ),

    "a-ground-relative-sticker-rides-the-terrain" to one(
        Camera(VALLEY_LATITUDE, VALLEY_LONGITUDE, 13.0, 45.0, 30.0),
        stickers = listOf(pin(VALLEY_LATITUDE, VALLEY_LONGITUDE, 0.0, AltitudeMode.GROUND_RELATIVE)),
    ),

    "an-absolute-sticker-holds-its-height-above-the-terrain" to one(
        Camera(VALLEY_LATITUDE, VALLEY_LONGITUDE, 13.0, 45.0, 30.0),
        stickers = listOf(pin(VALLEY_LATITUDE, VALLEY_LONGITUDE, 2_000.0, AltitudeMode.ABSOLUTE)),
    ),

    "ground-relative-and-absolute-stickers-separate-in-one-frame" to one(
        Camera(VALLEY_LATITUDE, VALLEY_LONGITUDE, 13.0, 45.0, 30.0),
        stickers = listOf(
            pin(VALLEY_LATITUDE, VALLEY_LONGITUDE, 0.0, AltitudeMode.GROUND_RELATIVE),
            pin(VALLEY_LATITUDE, VALLEY_LONGITUDE + 0.01, 2_000.0, AltitudeMode.ABSOLUTE),
        ),
    ),

    "a-screen-anchored-sticker-stays-nailed-to-the-corner" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 13.0, 30.0, 40.0),
        stickers = listOf(
            Sticker(
                placement = Placement(
                    positionMode = AnchoringMode.SCREEN,
                    position = Vector3(48.0, 48.0, 10.0),
                    rotationMode = AnchoringMode.SCREEN,
                    rotation = Vector3(0.0, 0.0, 0.0),
                    scaleMode = AnchoringMode.SCREEN,
                    scale = 1.5,
                ),
                image = ResourceLocator(STICKER_F_URL),
            ),
        ),
    ),

    "a-draped-geometry-follows-the-relief-under-it" to one(
        Camera(VALLEY_LATITUDE, VALLEY_LONGITUDE, 13.0, 45.0, 30.0),
        geometries = listOf(tintedQuad(VALLEY_LATITUDE, VALLEY_LONGITUDE, AltitudeMode.GROUND_RELATIVE)),
    ),

    // The contrast plan, and the altitude is the point rather than an oversight: 2,000 m is above
    // the valley floor and below the walls, so an absolute quad hangs in the air where the draped
    // one lies on the ground. At 0 m it would simply be buried, which is correct and shows nothing.
    "a-flat-geometry-hangs-above-the-relief-under-it" to one(
        Camera(VALLEY_LATITUDE, VALLEY_LONGITUDE, 13.0, 45.0, 30.0),
        geometries = listOf(
            tintedQuad(VALLEY_LATITUDE, VALLEY_LONGITUDE, AltitudeMode.ABSOLUTE, altitude = 2_000.0),
        ),
    ),

    "a-translucent-geometry-leaves-the-basemap-readable" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 13.5, 0.0, 0.0),
        geometries = listOf(tintedQuad(CITY_LATITUDE, CITY_LONGITUDE, AltitudeMode.ABSOLUTE)),
    ),

    "a-basemapless-frame-draws-only-what-the-caller-added" to one(
        Camera(CITY_LATITUDE, CITY_LONGITUDE, 13.5, 0.0, 0.0),
        drawBasemap = false,
        geometries = listOf(tintedQuad(CITY_LATITUDE, CITY_LONGITUDE, AltitudeMode.ABSOLUTE)),
        stickers = listOf(pin(CITY_LATITUDE, CITY_LONGITUDE, 0.0, AltitudeMode.ABSOLUTE)),
    ),
)

private fun one(
    camera: Camera,
    projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
    drawBasemap: Boolean = true,
    drawLabels: Boolean = true,
    stickers: List<Sticker> = emptyList(),
    geometries: List<Geometry> = emptyList(),
): List<FramePlan> = listOf(
    FramePlan(
        frameIndex = 0L,
        camera = camera,
        projectionMode = projectionMode,
        drawBasemap = drawBasemap,
        drawLabels = drawLabels,
        stickers = stickers,
        geometries = geometries,
    ),
)

private fun pin(
    latitude: Double,
    longitude: Double,
    altitude: Double,
    altitudeMode: AltitudeMode,
): Sticker = Sticker(
    placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(latitude, longitude, altitude),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
        altitudeMode = altitudeMode,
    ),
    image = ResourceLocator(STICKER_PIN_URL),
)

/**
 * A translucent lat/lon quad the consumer's own shader pair paints, centred on a coordinate.
 *
 * Translucent on purpose: an opaque geometry hides the basemap under it, which is exactly what a
 * draped-versus-flat comparison needs to see through.
 */
private fun tintedQuad(
    latitude: Double,
    longitude: Double,
    altitudeMode: AltitudeMode,
    altitude: Double = 0.0,
): Geometry = Geometry(
    topLeft = Vector3(latitude + 0.008, longitude - 0.015, altitude),
    bottomRight = Vector3(latitude - 0.008, longitude + 0.015, altitude),
    shaderPair = ShaderPair(CORPUS_VERTEX_SOURCE, CORPUS_FRAGMENT_SOURCE),
    uniforms = mapOf("uTint" to ShaderValue.Vec3(1.0f, 0.35f, 0.0f)),
    altitudeMode = altitudeMode,
)

private val CORPUS_VERTEX_SOURCE: String =
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

private val CORPUS_FRAGMENT_SOURCE: String =
    """
    #version 300 es
    precision mediump float;
    in vec2 vTexCoord;
    uniform vec3 uTint;
    out vec4 fragColor;
    void main() {
        float line = step(0.94, fract(vTexCoord.x * 8.0)) + step(0.94, fract(vTexCoord.y * 8.0));
        fragColor = vec4(uTint, 0.55) * clamp(line, 0.0, 1.0);
    }
    """.trimIndent() + "\n"
