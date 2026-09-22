package com.rohittp.reng

import com.rohittp.reng.internal.firewall.LABEL_GLYPH_TEMPLATE
import com.rohittp.reng.internal.firewall.LABEL_SANS_STACK
import com.rohittp.reng.internal.firewall.LABEL_TILE_TEMPLATE
import com.rohittp.reng.internal.firewall.labelGlyphRange
import com.rohittp.reng.internal.firewall.labelMvtBytes
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.label.FadedLabel
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectGeographicPosition
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * **E-terrain task 18's wiring gate: a real `prepare()`, a real DEM, and a label anchor that moved.**
 *
 * `LabelTerrainPlacementTest` proves the placement pass spends a height it is handed. It cannot prove
 * that anything ever hands it one — which is exactly the shape E-labels paid most for, "the label
 * path was fully built and wired to nothing", found only because an agent noticed its own work could
 * not be observed. So nothing here injects an elevation: the style declares a `raster-dem` source and
 * a `terrain` block, the transport serves a real Mapbox-encoded DEM, and the assertion is where the
 * frame's own label landed.
 *
 * **The expectation is arithmetic this file does itself.** The feature sits at the exact centre of its
 * vector tile — `labelMvtBytes` puts it at `(2048, 2048)` of a 4096 extent — so its geographic anchor
 * is the slippy-map tile centre, computed below from `z`, `x` and `y` by the standard formulas and not
 * by anything RenG derives. The predicted pixel is that position projected at
 * [RIDGE_CREST_METRES] × [TERRAIN_EXAGGERATION], and the case additionally requires the *unexaggerated*
 * prediction to miss, which is what stops a build that ignored `terrain.exaggeration` from passing.
 *
 * **Three ways this could pass for the wrong reason, and how each is closed.**
 *  - *No label at all.* Every arm asserts exactly one label with glyph quads before it asserts a pixel.
 *  - *The label moved because the frame changed.* The two arms differ in **nothing but the bytes the
 *    transport answers one DEM url with**, and share the style, the camera, the plan and the tiles.
 *  - *The prediction is the implementation.* The anchor's latitude and longitude come from the tile
 *    index, and the height from the style's own two numbers; only the projection is shared, and that
 *    is `ScreenProjectionTest`'s subject rather than this one's.
 *
 * **`nativeTest` for `RendererLabelResidencyTest`'s reason**: `acquireLabelCandidates` ends in
 * Rentile's Skia glyph packer, which this project's `androidHostTest` runtime resolves without its
 * native library, so no batch can be obtained there at all. No GL context is needed — every assertion
 * is about what `prepare()` decided, which is where ADR 0035 puts label placement.
 */
class RendererLabelTerrainTest {

    /**
     * The headline. One plan, two DEMs, and a label that lands where its own crest projects to.
     *
     * The sea-level arm is not decoration: it is what says the ridge arm's pixel is the terrain's
     * doing rather than the camera's, and it carries its own exact expectation at zero metres so that
     * a build which moved *every* label by a constant fails it.
     *
     * **The DEM has relief inside a single tile, and that is what makes the granularity visible.**
     * A uniform DEM is this cycle's own recorded symmetry point: a ground cell's four corners and the
     * texel under the point are the same number there, so a lookup taken at any granularity -- or at
     * a texel instead of a cell -- answers alike. [DEM_RIDGE_PNG] does not: at the tile centre the
     * frame's own ground grid reads its crest, while a one-cell grid would interpolate the tile's
     * *corner* texels and answer [RIDGE_EDGE_METRES]. Sixty-three times apart is not a tolerance
     * question.
     */
    @Test fun aLabelOverARidgeCrestLandsWhereItsOwnTerrainHeightProjects() = runTest {
        val overSeaLevel = prepareSingleLabel(DEM_SEA_LEVEL_PNG)
        val overTheRidge = prepareSingleLabel(DEM_RIDGE_PNG)

        val camera = resolvedTerrainLabelCamera()
        val expectedFlat = projectedLabelAnchor(camera, 0.0)
        val expectedRidden = projectedLabelAnchor(camera, RIDGE_CREST_METRES * TERRAIN_EXAGGERATION)

        assertEquals(
            expectedFlat.pixelX,
            overSeaLevel.label.anchorPixelX,
            ANCHOR_TOLERANCE_PIXELS,
            "over a sea-level DEM the label sits where the ellipsoid does, in x",
        )
        assertEquals(
            expectedFlat.pixelY,
            overSeaLevel.label.anchorPixelY,
            ANCHOR_TOLERANCE_PIXELS,
            "over a sea-level DEM the label sits where the ellipsoid does, in y",
        )
        assertEquals(
            expectedRidden.pixelX,
            overTheRidge.label.anchorPixelX,
            ANCHOR_TOLERANCE_PIXELS,
            "over a $RIDGE_CREST_METRES m crest exaggerated by $TERRAIN_EXAGGERATION the label rides it, in x",
        )
        assertEquals(
            expectedRidden.pixelY,
            overTheRidge.label.anchorPixelY,
            ANCHOR_TOLERANCE_PIXELS,
            "over a $RIDGE_CREST_METRES m crest exaggerated by $TERRAIN_EXAGGERATION the label rides it, in y",
        )

        val moved = distance(
            overSeaLevel.label.anchorPixelX,
            overSeaLevel.label.anchorPixelY,
            overTheRidge.label.anchorPixelX,
            overTheRidge.label.anchorPixelY,
        )
        assertTrue(
            moved >= MINIMUM_RIDGE_SHIFT_PIXELS,
            "the ridge must move the label further than $MINIMUM_RIDGE_SHIFT_PIXELS pixels, and it " +
                "moved $moved -- without this floor the four equalities above would all hold for a " +
                "renderer that handed the placement pass nothing",
        )

        // The exaggeration, pinned by refusal rather than by an equality: a build that read the DEM
        // and dropped `terrain.exaggeration` lands at the un-multiplied height, which must miss.
        val unexaggerated = projectedLabelAnchor(camera, RIDGE_CREST_METRES)
        val fromUnexaggerated = distance(
            unexaggerated.pixelX,
            unexaggerated.pixelY,
            overTheRidge.label.anchorPixelX,
            overTheRidge.label.anchorPixelY,
        )
        assertTrue(
            fromUnexaggerated >= MINIMUM_RIDGE_SHIFT_PIXELS,
            "an exaggeration of $TERRAIN_EXAGGERATION has to be visible: the label is only " +
                "$fromUnexaggerated pixels from where an unexaggerated $RIDGE_CREST_METRES m would put it",
        )
    }

    /**
     * **E-labels' decision E4, restated over the new input.** Two `prepare()`s of one plan against one
     * terrain place the label at the identical pixel and keep the identical collision box.
     *
     * The fade legitimately differs between the two — it advances on every successful preparation —
     * so nothing here compares opacity; what is compared is everything collision is decided from.
     */
    @Test fun twoPreparesOfOnePlanOverOneTerrainPlaceTheLabelIdentically() = runTest {
        val transport = TerrainLabelTransport(DEM_RIDGE_PNG)
        val renderer = terrainLabelRenderer(transport)
        val first: FadedLabel
        val second: FadedLabel
        try {
            first = singleLabel(renderer.prepare(terrainLabelPlan(frameIndex = 1L)) as RenGPreparedFrame)
            second = singleLabel(renderer.prepare(terrainLabelPlan(frameIndex = 2L)) as RenGPreparedFrame)
        } finally {
            renderer.close()
        }

        assertEquals(first.label.anchorPixelX, second.label.anchorPixelX, "the anchor is deterministic in x")
        assertEquals(first.label.anchorPixelY, second.label.anchorPixelY, "the anchor is deterministic in y")
        assertEquals(first.label.collisionBox, second.label.collisionBox, "and so is the space it claims")
        assertEquals(
            first.quads.map { it.cornersXy.toList() },
            second.quads.map { it.cornersXy.toList() },
            "and so is every glyph it draws",
        )
    }

    /**
     * The negative that keeps the two cases above honest about *which* terrain is being ridden: a
     * style with no `terrain` block draws the same label at the same tiles and does not move it at all.
     *
     * Without this, a renderer that added a constant to every label anchor -- or that read a height
     * from somewhere other than the frame's own DEM -- would satisfy the summit arm and be invisible
     * on the 28 corpus styles that declare no terrain.
     */
    @Test fun aStyleWithNoTerrainBlockPlacesTheLabelExactlyWhereTheEllipsoidDoes() = runTest {
        val terrainless = prepareSingleLabel(DEM_RIDGE_PNG, declareTerrain = false)
        val camera = resolvedTerrainLabelCamera()
        val expectedFlat = projectedLabelAnchor(camera, 0.0)

        assertEquals(expectedFlat.pixelX, terrainless.label.anchorPixelX, ANCHOR_TOLERANCE_PIXELS, "x")
        assertEquals(expectedFlat.pixelY, terrainless.label.anchorPixelY, ANCHOR_TOLERANCE_PIXELS, "y")
    }

    private suspend fun prepareSingleLabel(
        demBytes: ByteArray,
        declareTerrain: Boolean = true,
    ): FadedLabel {
        val renderer = terrainLabelRenderer(TerrainLabelTransport(demBytes, declareTerrain))
        return try {
            val frame = renderer.prepare(terrainLabelPlan(frameIndex = 1L)) as RenGPreparedFrame
            try {
                singleLabel(frame)
            } finally {
                frame.close()
            }
        } finally {
            renderer.close()
        }
    }
}

/**
 * The frame's one label, with the two ways this fixture could stop producing one named separately: a
 * frame with no labels at all, and a frame whose labels carry no glyph.
 */
private fun singleLabel(frame: RenGPreparedFrame): FadedLabel {
    val labels = assertNotNull(frame.labels, "the frame must carry labels").labels
    assertEquals(1, labels.size, "exactly one of the fixture's features is on screen")
    val only = labels.single()
    assertTrue(only.quads.isNotEmpty(), "and it draws glyphs rather than claiming empty space")
    return only
}

private fun distance(fromX: Double, fromY: Double, toX: Double, toY: Double): Double {
    val stepX = toX - fromX
    val stepY = toY - fromY
    return sqrt(stepX * stepX + stepY * stepY)
}

private fun resolvedTerrainLabelCamera(): ResolvedMercatorCamera =
    assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
        resolveMercatorCamera(
            camera = terrainLabelCamera(),
            outputPixelSize = OutputPixelSize(TERRAIN_LABEL_PIXELS, TERRAIN_LABEL_PIXELS),
        ),
    ).value

/** Where the fixture's one feature lands at [altitudeMetres], by the projection and nothing else. */
private fun projectedLabelAnchor(
    camera: ResolvedMercatorCamera,
    altitudeMetres: Double,
): ScreenProjection.Projected = assertIs<ScreenProjection.Projected>(
    projectGeographicPosition(
        camera,
        GeographicPosition(
            latitude = tileCentreLatitude(LABEL_TILE_Z, LABEL_TILE_Y),
            unwrappedLongitude = tileCentreLongitude(LABEL_TILE_Z, LABEL_TILE_X),
            altitudeMetres = altitudeMetres,
        ),
    ),
)

// ---- the fixture ---------------------------------------------------------------------------------

private const val TERRAIN_LABEL_STYLE_URL: String = "https://styles.example/terrain-labels.json"

private const val TERRAIN_DEM_PREFIX: String = "https://label-terrain-dem.example/"

private const val TERRAIN_DEM_TEMPLATE: String = TERRAIN_DEM_PREFIX + "{z}/{x}/{y}.png"

/** `https://tiles.example/l/`, the part of [LABEL_TILE_TEMPLATE] every vector tile url starts with. */
private val TILE_PREFIX: String = LABEL_TILE_TEMPLATE.substringBefore("{z}")

/** The sans `0-255` range's url, exactly as the firewall composes it for [LABEL_SANS_STACK]. */
private const val SANS_RANGE_URL: String =
    "https://glyphs.example/Label%20Sans%20Regular/0-255.pbf?key=reng-live-key"

/**
 * A saturated distance field, for `RendererLabelResidencyTest`'s reason: the shared fixture's default
 * ramps only to the fill edge, and a fixture that is honest about what it hands the pipeline costs
 * nothing even where no pixel is read.
 */
private val SATURATED: (Int) -> Byte = { 0xFF.toByte() }

private val SANS_RANGE: ByteArray = labelGlyphRange(LABEL_SANS_STACK, "0-255", listOf('A'.code), SATURATED)

/** Every tile carries the same one-letter `place` feature, at its own centre. */
private val TERRAIN_LABEL_MVT: ByteArray = labelMvtBytes("place" to "A")

/**
 * **1.5, and never 1.** Every one of the six corpus styles that declares terrain declares an
 * exaggeration of 1, at which an honoured multiplier and a dropped one are the same picture — the
 * cycle's own recorded trap. 1.5 against a 1,000 m DEM is 1,500 m, and the case refuses the 1,000 m
 * prediction explicitly.
 */
private const val TERRAIN_EXAGGERATION: Double = 1.5

/**
 * What [DEM_RIDGE_PNG] decodes to at the texel a tile's own centre lands in, restated here so the
 * expectation names a number rather than a blob.
 *
 * Derived rather than copied: that DEM's column `x` carries `1000 * (1 - |2 * (x + 0.5) / 64 - 1|)`
 * metres quantised to the Mapbox packing's tenth of a metre, and `GROUND_ELEVATION_SOURCE`'s rule
 * puts a grid node at `u = 0.5` on texel `floor(0.5 * 64) = 32`, which carries **984.4 m**. Columns
 * 31 and 32 carry the same height, so a point exactly on their boundary cannot be read two ways.
 */
private const val RIDGE_CREST_METRES: Double = 984.4

/**
 * What the same DEM carries at a tile's *corner* texels -- and therefore what a lookup taken at one
 * cell a side, rather than at the granularity the frame's ground is drawn with, would interpolate to
 * at that tile's centre.
 *
 * It is not asserted directly; it is what makes [RIDGE_CREST_METRES] a discriminating expectation
 * rather than a restatement of the fixture.
 */
private const val RIDGE_EDGE_METRES: Double = 15.6

/**
 * The tile the label comes out of. `x` and `y` are unequal and neither is the other's transpose, so a
 * swapped tile index names a different place rather than the same one.
 */
private const val LABEL_TILE_Z: Int = 13

private const val LABEL_TILE_X: Int = 4293

private const val LABEL_TILE_Y: Int = 2931

/**
 * 512 output pixels, which is one tile side at [LABEL_TILE_Z] — so with the camera off that tile's
 * centre by [CAMERA_OFFSET_PIXELS] every neighbouring tile's own feature sits a clear tile away and
 * off screen, and exactly one label survives.
 */
private const val TERRAIN_LABEL_PIXELS: Int = 512

/**
 * How far the camera sits from the label's tile centre, east and south, in logical pixels.
 *
 * **Not zero, and that is the whole of why this fixture can see anything.** An anchor at the
 * principal point is the symmetry point of a perspective camera: raising it scales its offset from
 * that point, and an offset of zero scales to zero, so a label at the screen centre does not move
 * however tall the terrain under it is. Sixty pixels puts it clearly off centre in both axes while
 * leaving the ridden anchor on a 512-pixel screen, which the case's own shift floor confirms.
 */
private const val CAMERA_OFFSET_PIXELS: Double = 60.0

/** One tile's side in logical pixels at the vector source's own tile size. */
private const val TILE_SIDE_LOGICAL_PIXELS: Double = 512.0

private const val MINIMUM_RIDGE_SHIFT_PIXELS: Double = 8.0

/**
 * A fifth of a pixel. The anchor is predicted from the tile index through the standard slippy-map
 * formulas while Rentile derives it from the same tile through its own, so the two agree to floating
 * point rather than exactly; every quantity the cases discriminate is tens of pixels apart.
 */
private const val ANCHOR_TOLERANCE_PIXELS: Double = 0.2

private fun tileCentreLongitude(z: Int, x: Int): Double =
    (x + 0.5) / (1 shl z).toDouble() * 360.0 - 180.0

private fun tileCentreLatitude(z: Int, y: Int): Double =
    atan(sinh(PI * (1.0 - 2.0 * (y + 0.5) / (1 shl z).toDouble()))) * DEGREES_PER_RADIAN

private const val DEGREES_PER_RADIAN: Double = 180.0 / PI

/** The label's tile centre, moved [CAMERA_OFFSET_PIXELS] east and south in normalised Mercator. */
private fun terrainLabelCamera(): Camera {
    val world = (1 shl LABEL_TILE_Z).toDouble() * TILE_SIDE_LOGICAL_PIXELS
    val offset = CAMERA_OFFSET_PIXELS / world
    val mercatorX = (LABEL_TILE_X + 0.5) / (1 shl LABEL_TILE_Z).toDouble() + offset
    val mercatorY = (LABEL_TILE_Y + 0.5) / (1 shl LABEL_TILE_Z).toDouble() + offset
    return Camera(
        latitude = atan(sinh(PI * (1.0 - 2.0 * mercatorY))) * DEGREES_PER_RADIAN,
        unwrappedLongitude = mercatorX * 360.0 - 180.0,
        zoom = LABEL_TILE_Z.toDouble(),
        bearing = 0.0,
        pitch = 0.0,
    )
}

private fun terrainLabelPlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = terrainLabelCamera(),
    drawBasemap = true,
    drawLabels = true,
)

private fun terrainLabelRenderer(transport: Transport): Renderer = createRenderer(
    RendererConfiguration(
        outputPixelSize = OutputPixelSize(TERRAIN_LABEL_PIXELS, TERRAIN_LABEL_PIXELS),
        transport = transport,
        store = RecordingStyleStore(),
        basemapStyle = ResourceLocator(TERRAIN_LABEL_STYLE_URL),
    ),
    styleGlBinding(),
    RenderContextProbe { RenderContextIdentity(1L) },
)

/**
 * One vector source serving both the ground and the labels, one `raster-dem` source, and a `terrain`
 * block that [declareTerrain] can remove without changing anything else about the style.
 *
 * A `background` layer is declared because the ground is rendered by the engine from this same style
 * and a style whose only layer is a symbol one rasterises to nothing — which would make the frame's
 * ground tiles, and therefore its terrain, disappear for a reason that has nothing to do with labels.
 */
private class TerrainLabelTransport(
    private val demBytes: ByteArray,
    private val declareTerrain: Boolean = true,
) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        val body = when {
            url == TERRAIN_LABEL_STYLE_URL -> styleJson().encodeToByteArray()
            url.startsWith(TERRAIN_DEM_PREFIX) -> demBytes
            url.startsWith(TILE_PREFIX) -> TERRAIN_LABEL_MVT
            url == SANS_RANGE_URL -> SANS_RANGE
            else -> null
        } ?: return TransportResponse(statusCode = 404, body = ByteArray(0))
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = "application/octet-stream"),
        )
    }

    private fun styleJson(): String {
        val terrain = if (declareTerrain) {
            ""","terrain":{"source":"dem","exaggeration":$TERRAIN_EXAGGERATION}"""
        } else {
            ""
        }
        return """{"version":8,"name":"reng-label-terrain",""" +
            """"glyphs":"$LABEL_GLYPH_TEMPLATE",""" +
            """"sources":{"v":{"type":"vector","tiles":["$LABEL_TILE_TEMPLATE"],"minzoom":0,"maxzoom":14},""" +
            """"dem":{"type":"raster-dem","tiles":["$TERRAIN_DEM_TEMPLATE"],""" +
            """"tileSize":64,"minzoom":0,"maxzoom":22}},""" +
            """"layers":[""" +
            """{"id":"bg","type":"background","paint":{"background-color":"#204060"}},""" +
            """{"id":"place","type":"symbol","source":"v","source-layer":"place",""" +
            """"layout":{"text-field":"{name}","text-font":["$LABEL_SANS_STACK"],"text-size":16},""" +
            """"paint":{"text-color":"#ff00ff"}}""" +
            """]$terrain}"""
    }
}
