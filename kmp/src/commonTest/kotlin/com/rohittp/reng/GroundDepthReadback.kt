package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_BACK
import com.rohittp.reng.internal.gl.GL_CCW
import com.rohittp.reng.internal.gl.GL_CLAMP_TO_EDGE
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_CULL_FACE
import com.rohittp.reng.internal.gl.GL_DEPTH_ATTACHMENT
import com.rohittp.reng.internal.gl.GL_DEPTH_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DEPTH_COMPONENT24
import com.rohittp.reng.internal.gl.GL_DEPTH_TEST
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_GEQUAL
import com.rohittp.reng.internal.gl.GL_NEAREST
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERBUFFER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_TEXTURE_MAG_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_MIN_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_WRAP_S
import com.rohittp.reng.internal.gl.GL_TEXTURE_WRAP_T
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GL_UNSIGNED_SHORT
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.GlobeGroundElevationFrame
import com.rohittp.reng.internal.gl.GlobeGroundPipeline
import com.rohittp.reng.internal.gl.GlobeGroundPipelineResult
import com.rohittp.reng.internal.gl.GroundDemUniforms
import com.rohittp.reng.internal.gl.GroundPipeline
import com.rohittp.reng.internal.gl.GroundPipelineResult
import com.rohittp.reng.internal.gl.GroundTileDem
import com.rohittp.reng.internal.gl.MercatorGroundElevationFrame
import com.rohittp.reng.internal.gl.MercatorGroundTileDem
import com.rohittp.reng.internal.gl.ModelPipeline
import com.rohittp.reng.internal.gl.ModelPipelineResult
import com.rohittp.reng.internal.gl.ModelShaderVariant
import com.rohittp.reng.internal.gl.REVERSE_Z_FAR_DEPTH
import com.rohittp.reng.internal.gl.ResolvedGlobeGroundTile
import com.rohittp.reng.internal.gl.ResolvedGroundTile
import com.rohittp.reng.internal.gl.ResolvedModel
import com.rohittp.reng.internal.gl.ResolvedModelPrimitive
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.TextureSamplerState
import com.rohittp.reng.internal.gl.UploadedPrimitive
import com.rohittp.reng.internal.gl.composeGlobeGroundUnitSphereToClip
import com.rohittp.reng.internal.gl.createGlobeGroundPipeline
import com.rohittp.reng.internal.gl.createGroundPipeline
import com.rohittp.reng.internal.gl.createModelPipeline
import com.rohittp.reng.internal.gl.deleteGlobeGroundPipeline
import com.rohittp.reng.internal.gl.deleteGroundPipeline
import com.rohittp.reng.internal.gl.deleteModelPipeline
import com.rohittp.reng.internal.gl.deleteUploadedPrimitive
import com.rohittp.reng.internal.gl.demDecodeCoefficients
import com.rohittp.reng.internal.gl.drawGlobeGround
import com.rohittp.reng.internal.gl.drawGround
import com.rohittp.reng.internal.gl.drawModels
import com.rohittp.reng.internal.gl.globeGroundCellsPerTileSide
import com.rohittp.reng.internal.gl.globeGroundTileEdges
import com.rohittp.reng.internal.gl.mercatorTileYEdges
import com.rohittp.reng.internal.gl.uploadModelPrimitive
import com.rohittp.reng.internal.model.DecodedPrimitive
import com.rohittp.reng.internal.model.ModelIndices
import com.rohittp.reng.internal.model.ResolvedMaterial
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.WORLD_CIRCUMFERENCE_METRES
import com.rohittp.reng.internal.projection.globeMetresToLogicalPixels
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.demElevationMetres
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * **Cycle E-terrain task 9's own gate: ADR 0039, in pixels, on a real driver.** The ground pass tests
 * depth and *writes* it in a frame whose ground is displaced, and writes none in a frame whose ground
 * is the flat plane RenG has drawn since `0.3.0`.
 *
 * **Why a whole fixture rather than a call-log assertion.** `depthMask(true)` reaching the driver is
 * `SceneContentTest`, `GroundPipelineTest` and `GlobeGroundPipelineTest`'s claim, and all three of
 * them would go on passing against a build whose depth *comparison*, clear value or attachment made
 * the write inert. Terrain that cannot occlude is not terrain, and "it occludes" is a statement about
 * pixels behind a surface, so it is measured here against a real depth buffer, `GL_GEQUAL` and
 * `drawFrame`'s own reverse-Z clear.
 *
 * ## The five cases and what each one alone would survive
 *
 * - [assertAModelBehindTheRaisedGroundIsOccludedByIt] — the point of the task. Its partner below is
 *   what stops it passing because the model never drew at all.
 * - [assertAModelInFrontOfTheRaisedGroundIsNot] — the other half of ADR 0039's own verification
 *   sentence, and the case that would fail if the write occluded *everything* rather than what is
 *   behind it. It is expected to survive the deletion of the write entirely, which is exactly why it
 *   is not the evidence for it.
 * - [assertAFrameWithNoTerrainWritesNoGroundDepth] — **the negative, and the one that makes the
 *   condition rather than the write the subject.** A flat ground with a model *below* it must leave
 *   that model whole, because ADR 0027 removed the ground's writes and ADR 0039 restores them only
 *   where there is relief. An unconditional write deletes the model here and revives, in miniature,
 *   the defect `BasemapReadbackSuite`'s coplanar sweep budgets against.
 * - [assertGroundOverGroundResolvesByDepthRatherThanByDeclarationOrder] — the ground occluding
 *   *itself*, which is what a mountain does to the valley behind it. Two tiles on one footprint at
 *   two elevations, the nearer declared first: without the write the later declaration wins, which is
 *   ADR 0027's rule and the wrong picture over terrain.
 * - [assertTheGlobesGroundOverGroundResolvesByDepthToo] — the same claim on the sphere, because ADR
 *   0039 changes both grounds together and a rule honoured in one projection is two pictures of one
 *   world.
 *
 * ## What this suite does not claim
 *
 * **No globe negative.** A flat ground has no relief, so the only instrument that can show a *flat*
 * globe ground writing depth is a probe at a known absolute window depth — and the globe's real
 * perspective projection makes that a measurement rather than arithmetic, which would put the fixture
 * in the position of measuring itself. The globe's "no terrain, no write" arm is
 * `GlobeGroundPipelineTest`'s call log; the Mercator one is measured here because its synthetic
 * matrix makes every depth exact.
 *
 * Nothing about fidelity, nothing about seams, nothing about how terrain *looks*: pixel verification
 * remains Cycle J's.
 *
 * ## The synthetic matrix, and why elevation moves depth and nothing else
 *
 * [GROUND_MATRIX] maps the ground tile's unit square onto the whole frame and its map-space `z` —
 * which is what displacement produces — onto clip `z` alone. Every factor in it is a power of two, so
 * the raised ground's window depth is exactly `0.75` and the flat ground's is exactly `0.5`, with no
 * appeal to a driver's rounding. It also means the raised ground has the **same silhouette** as the
 * flat one, so a model that disappears did so by losing the depth test rather than by being covered
 * by a mountain that grew over it — the two are indistinguishable under a real perspective camera and
 * only one of them is what ADR 0039 claims.
 *
 * A real camera would fold perspective, the Mercator latitude term and the frame's own tile
 * arrangement into the same measurement, which is the trade `runGroundDisplacementReadback` already
 * made for the displacement itself and makes again here.
 */
internal fun runGroundDepthReadback(binding: GlBinding, dialect: ShaderDialect) {
    val target = createDepthReadbackTarget(binding)
    val programs = GlProgramCache()
    val ground = when (val result = createGroundPipeline(binding, dialect, programs)) {
        is GroundPipelineResult.Created -> result.pipeline
        is GroundPipelineResult.Failed ->
            throw AssertionError("the ground pipeline's $dialect programs did not link on this driver")
    }
    val globe = when (val result = createGlobeGroundPipeline(binding, dialect, programs)) {
        is GlobeGroundPipelineResult.Created -> result.pipeline
        is GlobeGroundPipelineResult.Failed ->
            throw AssertionError("the globe ground pipeline's $dialect programs did not link on this driver")
    }
    val modelPipeline = when (val result = createModelPipeline(binding, dialect, programs, FLAT_MODEL_VARIANT)) {
        is ModelPipelineResult.Created -> result.pipeline
        is ModelPipelineResult.Failed ->
            throw AssertionError("the $FLAT_MODEL_VARIANT model program did not link on this driver")
    }

    val raisedDem = createDepthDemTexture(binding, RIDGE_METRES)
    val seaLevelDem = createDepthDemTexture(binding, 0.0)
    val groundTexel = createOpaqueTexel(binding, GROUND_COLOUR)
    val highTexel = createOpaqueTexel(binding, HIGH_GROUND_COLOUR)
    val lowTexel = createOpaqueTexel(binding, LOW_GROUND_COLOUR)
    val quad = uploadModelPrimitive(binding, screenQuadPrimitive())

    try {
        val fixture = DepthFixture(binding, target, ground, globe, modelPipeline, quad)
        println("RenG ground depth readback: dialect=$dialect")
        assertAModelBehindTheRaisedGroundIsOccludedByIt(fixture, raisedDem, groundTexel)
        assertAModelInFrontOfTheRaisedGroundIsNot(fixture, raisedDem, groundTexel)
        assertAFrameWithNoTerrainWritesNoGroundDepth(fixture, groundTexel)
        assertGroundOverGroundResolvesByDepthRatherThanByDeclarationOrder(
            fixture, raisedDem, seaLevelDem, highTexel, lowTexel,
        )
        assertTheGlobesGroundOverGroundResolvesByDepthToo(
            fixture, raisedDem, seaLevelDem, highTexel, lowTexel,
        )
    } finally {
        deleteUploadedPrimitive(binding, quad)
        intArrayOf(raisedDem, seaLevelDem, groundTexel, highTexel, lowTexel).forEach {
            binding.deleteTextures(1, intArrayOf(it))
        }
        deleteModelPipeline(binding, programs, modelPipeline)
        deleteGlobeGroundPipeline(binding, programs, globe)
        deleteGroundPipeline(binding, programs, ground)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        target.delete(binding)
    }
}

/**
 * **The task's own sentence, measured.** A model standing behind a displaced ground must be hidden by
 * it; before ADR 0039 it painted straight over the mountain.
 *
 * The model quad is drawn at window depth `0.5` and the raised ground writes `0.75`, so the model
 * loses `GL_GEQUAL` outright rather than by an epsilon — the occluder and the occludee are a quarter
 * of the depth range apart, which is the opposite of the same-depth symmetry point a test like this
 * naturally lands on.
 *
 * **The reference frames are what make it non-vacuous.** The model is drawn alone first and its own
 * pixels counted, so "the model is gone" is a claim about a model that demonstrably drew; and the
 * ground is drawn alone, so the pixels it is asked to have deleted are pixels it actually covers.
 * With neither, a build whose model pass did nothing at all would pass this.
 */
private fun assertAModelBehindTheRaisedGroundIsOccludedByIt(
    fixture: DepthFixture,
    raisedDem: Int,
    groundTexel: Int,
) {
    val modelAlone = fixture.render { fixture.drawModelAt(MODEL_BEHIND_CLIP_Z) }
    val groundAlone = fixture.render { fixture.drawMercatorGround(raisedDem, groundTexel) }
    val both = fixture.render {
        fixture.drawMercatorGround(raisedDem, groundTexel)
        fixture.drawModelAt(MODEL_BEHIND_CLIP_Z)
    }

    val contested = modelAlone.pixelsMatching(MODEL_COLOUR).filter { groundAlone.isCloseTo(it, GROUND_COLOUR) }
    assertTrue(
        contested.size >= MINIMUM_CONTESTED_PIXELS,
        "only ${contested.size} pixels carry both the model and the ground, so nothing here is " +
            "contested and the case proves nothing; expected at least $MINIMUM_CONTESTED_PIXELS",
    )
    val survivors = contested.count { both.isCloseTo(it, MODEL_COLOUR) }
    val budget = maxOf(2, contested.size / 100)
    println("RenG ground depth readback: model behind the raised ground kept $survivors of ${contested.size}")
    assertTrue(
        survivors <= budget,
        "a model at window depth 0.5 standing behind a ground raised to 0.75 kept $survivors of " +
            "${contested.size} contested pixels, over a budget of $budget: the displaced ground is " +
            "not writing depth, so terrain occludes nothing (ADR 0039)",
    )
}

/**
 * The other half of ADR 0039's verification sentence, and the reason case 1's disappearance reads as
 * occlusion rather than as a model that cannot be drawn over this ground at all.
 *
 * The same quad, the same ground, the same draw order — only the model's depth changes, from `0.5` to
 * `0.95`. It must survive whole. **This case is expected to stay green against a build with no ground
 * depth write at all**, and saying so is the point: it is a bound on over-occlusion, not evidence for
 * the write.
 */
private fun assertAModelInFrontOfTheRaisedGroundIsNot(
    fixture: DepthFixture,
    raisedDem: Int,
    groundTexel: Int,
) {
    val modelAlone = fixture.render { fixture.drawModelAt(MODEL_IN_FRONT_CLIP_Z) }
    val both = fixture.render {
        fixture.drawMercatorGround(raisedDem, groundTexel)
        fixture.drawModelAt(MODEL_IN_FRONT_CLIP_Z)
    }

    val painted = modelAlone.pixelsMatching(MODEL_COLOUR)
    assertTrue(
        painted.size >= MINIMUM_CONTESTED_PIXELS,
        "the model drew only ${painted.size} pixels on its own, so this case measures nothing",
    )
    val survivors = painted.count { both.isCloseTo(it, MODEL_COLOUR) }
    val budget = maxOf(2, painted.size / 100)
    println("RenG ground depth readback: model in front of the raised ground kept $survivors of ${painted.size}")
    assertTrue(
        painted.size - survivors <= budget,
        "a model at window depth 0.95 standing in front of a ground raised to 0.75 lost " +
            "${painted.size - survivors} of ${painted.size} pixels, over a budget of $budget: the " +
            "ground is occluding what is in front of it, which is a wrong depth rather than a write",
    )
}

/**
 * **ADR 0039's condition, which is the whole risk of the task.**
 *
 * A frame with no terrain draws the flat ground three published releases shipped, and ADR 0027
 * requires it to write no depth — so a model *below* it paints over it, exactly as that ADR records
 * for a `Geometry` below altitude 0. An unconditional write deletes this model, and it would delete a
 * coplanar altitude-0 `Geometry` in the 28 of the corpus's 34 styles that declare no terrain.
 *
 * The model sits at window depth `0.25` against a flat ground at `0.5`, so this is the same quarter of
 * the depth range as case 1 with the sign reversed. `BasemapReadbackSuite`'s coplanar sweep is the
 * full-camera version of this claim, through the public API and across five pitch-and-bearing pairs;
 * this is the arithmetic version, in one frame, so a build that revives the defect fails here first
 * and says why.
 */
private fun assertAFrameWithNoTerrainWritesNoGroundDepth(fixture: DepthFixture, groundTexel: Int) {
    val modelAlone = fixture.render { fixture.drawModelAt(MODEL_BELOW_FLAT_CLIP_Z) }
    val groundAlone = fixture.render { fixture.drawMercatorGround(dem = null, colour = groundTexel) }
    val both = fixture.render {
        fixture.drawMercatorGround(dem = null, colour = groundTexel)
        fixture.drawModelAt(MODEL_BELOW_FLAT_CLIP_Z)
    }

    val contested = modelAlone.pixelsMatching(MODEL_COLOUR).filter { groundAlone.isCloseTo(it, GROUND_COLOUR) }
    assertTrue(
        contested.size >= MINIMUM_CONTESTED_PIXELS,
        "only ${contested.size} pixels carry both the model and the flat ground, so this negative " +
            "proves nothing; expected at least $MINIMUM_CONTESTED_PIXELS",
    )
    val survivors = contested.count { both.isCloseTo(it, MODEL_COLOUR) }
    val budget = maxOf(2, contested.size / 100)
    println("RenG ground depth readback: model below the flat ground kept $survivors of ${contested.size}")
    assertTrue(
        contested.size - survivors <= budget,
        "a frame with no terrain deleted ${contested.size - survivors} of ${contested.size} pixels " +
            "of a model standing below its flat ground, over a budget of $budget: the ground is " +
            "writing depth in a frame with no relief, which is ADR 0027's coplanar defect revived in " +
            "28 of the corpus's 34 styles (ADR 0039: the write is conditional)",
    )
}

/**
 * The ground occluding **itself**, which is what a mountain does to the valley behind it and what no
 * assertion about a model can reach.
 *
 * Two tiles on one footprint: the first raised to window depth `0.75` and coloured
 * [HIGH_GROUND_COLOUR], the second at sea level and `0.5`, coloured [LOW_GROUND_COLOUR]. Depth must
 * decide, so the high tile survives. Under ADR 0027's rule — the one that holds in every frame with
 * no terrain — the *later* declaration wins instead and the frame reads entirely low.
 *
 * Both tiles carry a DEM, so this is unambiguously a displaced frame; the tile that happens to decode
 * to zero metres is still terrain, which is ADR 0041's own reading and the reason the condition is
 * about DEMs rather than about heights.
 */
private fun assertGroundOverGroundResolvesByDepthRatherThanByDeclarationOrder(
    fixture: DepthFixture,
    raisedDem: Int,
    seaLevelDem: Int,
    highTexel: Int,
    lowTexel: Int,
) {
    val frame = fixture.render {
        fixture.drawMercatorGroundPair(
            firstDem = raisedDem, firstColour = highTexel,
            secondDem = seaLevelDem, secondColour = lowTexel,
        )
    }
    val high = frame.pixelsMatching(HIGH_GROUND_COLOUR).size
    val low = frame.pixelsMatching(LOW_GROUND_COLOUR).size
    println("RenG ground depth readback: mercator ground over ground high=$high low=$low")
    assertTrue(
        high >= MINIMUM_CONTESTED_PIXELS,
        "the raised ground tile drew only $high pixels, so this case measures nothing",
    )
    assertTrue(
        low <= maxOf(2, high / 100),
        "the sea-level ground tile kept $low pixels against the raised tile's $high, so the ground " +
            "resolved against itself by declaration order rather than by depth: a displaced ground " +
            "that writes no depth cannot hide the valley behind the ridge (ADR 0039)",
    )
}

/**
 * [assertGroundOverGroundResolvesByDepthRatherThanByDeclarationOrder] on the sphere, because ADR 0039
 * moves both grounds together and terrain lands in both projections.
 *
 * The displacement is radial here, so the raised tile set is a **larger** sphere that contains the
 * sea-level one: every visible fragment of the second is behind a fragment of the first, with no
 * silhouette boundary between them to argue about. `GLOBE_EXAGGERATION` is 500 for
 * `runGroundDisplacementReadback`'s reason — a real summit against the WGS84 semi-major axis is 0.14
 * per cent, which is a fifth of a pixel and unmeasurable — and exaggeration is finite and unclamped by
 * decision.
 */
private fun assertTheGlobesGroundOverGroundResolvesByDepthToo(
    fixture: DepthFixture,
    raisedDem: Int,
    seaLevelDem: Int,
    highTexel: Int,
    lowTexel: Int,
) {
    val frame = fixture.render {
        fixture.drawGlobeGroundPair(
            firstDem = raisedDem, firstColour = highTexel,
            secondDem = seaLevelDem, secondColour = lowTexel,
        )
    }
    val high = frame.pixelsMatching(HIGH_GROUND_COLOUR).size
    val low = frame.pixelsMatching(LOW_GROUND_COLOUR).size
    println("RenG ground depth readback: globe ground over ground high=$high low=$low")
    assertTrue(
        high >= MINIMUM_GLOBE_DISC_PIXELS,
        "the raised globe drew only $high pixels, so this case measures nothing; expected at least " +
            "$MINIMUM_GLOBE_DISC_PIXELS",
    )
    assertTrue(
        low <= maxOf(2, high / 100),
        "the sea-level sphere kept $low pixels inside the raised sphere's $high, so the globe's " +
            "ground resolved against itself by declaration order rather than by depth (ADR 0039)",
    )
}

// ---- the fixture ------------------------------------------------------------------------------

private class DepthFixture(
    private val binding: GlBinding,
    private val target: DepthReadbackTarget,
    private val ground: GroundPipeline,
    private val globe: GlobeGroundPipeline,
    private val modelPipeline: ModelPipeline,
    private val quad: UploadedPrimitive,
) {
    private val globeCamera: ResolvedGlobeCamera = (
        resolveGlobeCamera(
            Camera(
                latitude = GLOBE_FIXTURE_LATITUDE,
                unwrappedLongitude = GLOBE_FIXTURE_LONGITUDE,
                zoom = GLOBE_FIXTURE_ZOOM,
                bearing = 0.0,
                pitch = 0.0,
            ),
            OutputPixelSize(DEPTH_READBACK_PIXELS, DEPTH_READBACK_PIXELS),
        ) as SpatialOutcome.Success<ResolvedGlobeCamera>
        ).value

    /** One Mercator ground tile, displaced by [dem] when it is given and flat when it is not. */
    fun drawMercatorGround(dem: Int?, colour: Int) {
        drawGround(
            binding = binding,
            pipeline = ground,
            tiles = listOf(mercatorTile(dem, colour)),
            cellsPerTileSide = FIXTURE_CELLS,
            elevation = dem?.let { mercatorElevationFrame() },
        )
    }

    /** Two Mercator ground tiles on one footprint, in the order given, both displaced. */
    fun drawMercatorGroundPair(firstDem: Int, firstColour: Int, secondDem: Int, secondColour: Int) {
        drawGround(
            binding = binding,
            pipeline = ground,
            tiles = listOf(mercatorTile(firstDem, firstColour), mercatorTile(secondDem, secondColour)),
            cellsPerTileSide = FIXTURE_CELLS,
            elevation = mercatorElevationFrame(),
        )
    }

    /** Two whole spheres, in the order given, at two radii. */
    fun drawGlobeGroundPair(firstDem: Int, firstColour: Int, secondDem: Int, secondColour: Int) {
        drawGlobeGround(
            binding = binding,
            pipeline = globe,
            tiles = globeTiles(firstDem, firstColour) + globeTiles(secondDem, secondColour),
            unitSphereToClip = composeGlobeGroundUnitSphereToClip(globeCamera),
            cellsPerTileSide = globeGroundCellsPerTileSide(globeCamera, GLOBE_FIXTURE_LOD),
            elevation = GlobeGroundElevationFrame(
                dem = demUniforms(GLOBE_EXAGGERATION),
                radialMultiplePerMetre = (
                    globeMetresToLogicalPixels(globeCamera.radiusLogicalPixels) /
                        globeCamera.radiusLogicalPixels
                    ).toFloat(),
            ),
        )
    }

    /**
     * The screen-parallel probe quad, drawn through the **production** model pass at a constant clip
     * `z`, so its whole surface carries one window depth and the comparison against the ground is the
     * one arithmetic states.
     *
     * Its model-view-projection is [MODEL_MATRIX] with `clipZ` in the translation column, which is
     * legal precisely because the quad's own vertices carry `z = 0`; nothing about the model pass is
     * mocked, including its own `depthMask(true)` for opaque primitives.
     */
    fun drawModelAt(clipZ: Float) {
        drawModels(
            binding = binding,
            pipelines = mapOf(FLAT_MODEL_VARIANT to modelPipeline),
            models = listOf(
                ResolvedModel(
                    listOf(
                        ResolvedModelPrimitive(
                            uploaded = quad,
                            modelViewProjection = modelMatrix(clipZ),
                            normalMatrix = IDENTITY_MATRIX,
                            material = PROBE_MATERIAL,
                            baseColourTexture = null,
                            jointMatrices = null,
                            reverseWinding = false,
                        ),
                    ),
                ),
            ),
            lightDirectionCameraSpace = floatArrayOf(0.0f, 0.0f, 1.0f),
        )
    }

    /**
     * Clears colour **and depth** exactly as `drawFrame` does — the mask on around the clear, the
     * clear value [REVERSE_Z_FAR_DEPTH], the comparison `GL_GEQUAL` — then runs [draw] and reads the
     * whole frame back.
     *
     * Restating `drawFrame`'s three depth decisions rather than calling it is what keeps this suite a
     * measurement of the ground pass: `drawFrame` renders into its own offscreen surface and
     * composites, which would put a second pass between the draw and the readback.
     */
    fun render(draw: () -> Unit): DepthFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer)
        binding.viewport(0, 0, DEPTH_READBACK_PIXELS, DEPTH_READBACK_PIXELS)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.depthMask(true)
        binding.clearColor(0f, 0f, 0f, 1f)
        binding.clearDepthf(REVERSE_Z_FAR_DEPTH)
        binding.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        binding.enable(GL_DEPTH_TEST)
        binding.depthFunc(GL_GEQUAL)
        binding.frontFace(GL_CCW)
        binding.cullFace(GL_BACK)
        binding.disable(GL_CULL_FACE)
        binding.bindSampler(0, 0)
        binding.bindSampler(1, 0)

        draw()

        val bytes = ByteArray(DEPTH_READBACK_PIXELS * DEPTH_READBACK_PIXELS * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target.framebuffer)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, DEPTH_READBACK_PIXELS, DEPTH_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, bytes,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        return DepthFrame(bytes)
    }

    private fun mercatorTile(dem: Int?, colour: Int): ResolvedGroundTile = ResolvedGroundTile(
        modelViewProjection = GROUND_MATRIX,
        texture = colour,
        elevation = dem?.let {
            MercatorGroundTileDem(
                dem = GroundTileDem(
                    demTexture = it,
                    window = WHOLE_TILE_WINDOW,
                    tileSideMetres = tileSideMetres(FIXTURE_LOD),
                ),
                mercatorY = mercatorTileYEdges(FIXTURE_LOD, FIXTURE_TILE_Y),
            )
        },
    )

    private fun globeTiles(dem: Int, colour: Int): List<ResolvedGlobeGroundTile> = (0 until 4).map { index ->
        ResolvedGlobeGroundTile(
            edges = globeGroundTileEdges(
                lod = GLOBE_FIXTURE_LOD,
                tileY = (index / 2).toLong(),
                unwrappedX = (index % 2).toLong(),
            ),
            texture = colour,
            elevation = GroundTileDem(
                demTexture = dem,
                window = WHOLE_TILE_WINDOW,
                tileSideMetres = tileSideMetres(GLOBE_FIXTURE_LOD),
            ),
        )
    }

    private fun mercatorElevationFrame(): MercatorGroundElevationFrame = MercatorGroundElevationFrame(
        dem = demUniforms(FIXTURE_EXAGGERATION),
        equatorialLogicalPixelsPerMetre = FIXTURE_LOGICAL_PIXELS_PER_METRE,
    )

    private fun demUniforms(exaggeration: Float): GroundDemUniforms = GroundDemUniforms(
        decode = demDecodeCoefficients(FIXTURE_ENCODING),
        interiorSizePx = DEM_INTERIOR_TEXELS,
        exaggeration = exaggeration,
    )
}

/** One read-back frame. Row 0 is the bottom, as `glReadPixels` returns it. */
private class DepthFrame(private val bytes: ByteArray) {
    fun isCloseTo(index: Int, colour: IntArray): Boolean {
        val offset = index * 4
        return (0..2).all {
            abs((bytes[offset + it].toInt() and 0xff) - colour[it]) <= DEPTH_CHANNEL_TOLERANCE
        }
    }

    fun pixelsMatching(colour: IntArray): List<Int> =
        (0 until DEPTH_READBACK_PIXELS * DEPTH_READBACK_PIXELS).filter { isCloseTo(it, colour) }
}

private class DepthReadbackTarget(
    val framebuffer: Int,
    private val colourTexture: Int,
    private val depthRenderbuffer: Int,
) {
    fun delete(binding: GlBinding) {
        binding.deleteFramebuffers(1, intArrayOf(framebuffer))
        binding.deleteRenderbuffers(1, intArrayOf(depthRenderbuffer))
        binding.deleteTextures(1, intArrayOf(colourTexture))
    }
}

/**
 * A colour texture plus a `GL_DEPTH_COMPONENT24` renderbuffer, which is `OffscreenSurface`'s own
 * arrangement. The depth attachment is the whole reason this suite has its own target:
 * `runGroundDisplacementReadback`'s has none, so every depth write in it is discarded by the
 * framebuffer before it can mean anything.
 */
/**
 * The tile's own equatorial side, read only by a **shaded** ground program. Every render here runs
 * the unshaded ones, so this is honest bookkeeping rather than a number under test; terrain shading
 * is `runGroundShadingReadback`'s.
 */
private fun tileSideMetres(lod: Int): Float =
    (WORLD_CIRCUMFERENCE_METRES / (1L shl lod).toDouble()).toFloat()

private fun createDepthReadbackTarget(binding: GlBinding): DepthReadbackTarget {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val colourTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, colourTexture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, DEPTH_READBACK_PIXELS, DEPTH_READBACK_PIXELS)

    binding.genRenderbuffers(1, names)
    val depthRenderbuffer = names[0]
    binding.bindRenderbuffer(GL_RENDERBUFFER, depthRenderbuffer)
    binding.renderbufferStorage(
        GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, DEPTH_READBACK_PIXELS, DEPTH_READBACK_PIXELS,
    )

    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colourTexture, 0)
    binding.framebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderbuffer)
    assertTrue(
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE,
        "the ground depth readback target must be a complete framebuffer with a depth attachment",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return DepthReadbackTarget(framebuffer, colourTexture, depthRenderbuffer)
}

private fun createOpaqueTexel(binding: GlBinding, colour: IntArray): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
        byteArrayOf(colour[0].toByte(), colour[1].toByte(), colour[2].toByte(), -1),
    )
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    return texture
}

/**
 * A padded `(N + 2)` square DEM whose every texel decodes to [metres], round-tripped forwards through
 * [demElevationMetres] so a fixture that encoded a different height than it names fails here rather
 * than producing a plausible wrong measurement.
 */
private fun createDepthDemTexture(binding: GlBinding, metres: Double): Int {
    val padded = DEM_INTERIOR_TEXELS + 2
    val packed = ((metres + 10_000.0) / 0.1).toLong()
    val red = ((packed shr 16) and 0xff).toInt()
    val green = ((packed shr 8) and 0xff).toInt()
    val blue = (packed and 0xff).toInt()
    val decoded = demElevationMetres(red, green, blue, FIXTURE_ENCODING)
    assertTrue(
        abs(decoded - metres) < 1e-6,
        "the fixture's own encoder must round-trip: asked for $metres m, encoded " +
            "($red, $green, $blue), which decodes to $decoded m",
    )
    val texels = ByteArray(padded * padded * 4)
    for (index in 0 until padded * padded) {
        texels[index * 4] = red.toByte()
        texels[index * 4 + 1] = green.toByte()
        texels[index * 4 + 2] = blue.toByte()
        texels[index * 4 + 3] = -1
    }
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, padded, padded, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    return texture
}

/**
 * The probe quad: two triangles in the `z = 0` plane, spanning half the frame in each axis, with no
 * `NORMAL` and no `TEXCOORD_0`.
 *
 * The absent normal is what pins the shading term at exactly `1.0` (`modelFragmentSource` reads a
 * zero-length normal as fully lit), so the drawn colour is [PROBE_BASE_COLOUR] itself and an expected
 * value can be written down. That is `ModelFixtureBuilder`'s own reasoning, restated here because
 * this fixture is built in-process rather than from a GLB.
 */
private fun screenQuadPrimitive(): DecodedPrimitive = DecodedPrimitive(
    positions = floatArrayOf(
        -MODEL_HALF_EXTENT, -MODEL_HALF_EXTENT, 0.0f,
        MODEL_HALF_EXTENT, -MODEL_HALF_EXTENT, 0.0f,
        MODEL_HALF_EXTENT, MODEL_HALF_EXTENT, 0.0f,
        -MODEL_HALF_EXTENT, MODEL_HALF_EXTENT, 0.0f,
    ),
    normals = null,
    texCoords = null,
    colours = null,
    joints = null,
    weights = null,
    indices = ModelIndices(
        shorts = shortArrayOf(0, 1, 2, 0, 2, 3),
        ints = null,
        glComponentType = GL_UNSIGNED_SHORT,
        count = 6,
    ),
    material = PROBE_MATERIAL,
)

/** [MODEL_MATRIX] with [clipZ] in its translation column, so every vertex lands at that clip `z`. */
private fun modelMatrix(clipZ: Float): FloatArray = MODEL_MATRIX.copyOf().also { it[14] = clipZ }

// ---- the numbers ------------------------------------------------------------------------------

/**
 * 256, and the number is the **globe** case's rather than the Mercator ones'.
 * `runGroundDisplacementReadback` records why: at 128 its sphere overflowed the frame in every
 * direction and its silhouette measurement saturated. The probe quad is 128 pixels a side here,
 * 16,384 pixels, far past any fill-rule boundary.
 */
internal const val DEPTH_READBACK_PIXELS: Int = 256

/** Eight interior texels, ten padded — `runGroundDisplacementReadback`'s size exactly. */
private const val DEM_INTERIOR_TEXELS: Int = 8

private val FIXTURE_ENCODING: DemEncoding = DemEncoding.MAPBOX

/**
 * 512 metres, so that `512 m * 2 exaggeration * 0.5 px/m * 2^-10 clip/px` is exactly `0.5` of clip
 * `z` and the raised ground's window depth is exactly `0.75`. Its Mapbox triple is `(1, 154, 96)`
 * with no rounding of its own.
 */
private const val RIDGE_METRES: Double = 512.0

/** `runGroundDisplacementReadback`'s scale, and **not 1**: at 1 a dropped metre scale is invisible. */
private const val FIXTURE_LOGICAL_PIXELS_PER_METRE: Float = 0.5f

/**
 * **2, never 1**, for the reason every exaggeration in this cycle is not 1: all six corpus styles
 * declare 1, and at 1 an honoured multiplier and a dropped one raise the ground by the same amount —
 * so a fixture at 1 would leave the raised ground at sea level's own depth, which is the same-depth
 * symmetry point this suite's whole subject is about not sitting on.
 */
private const val FIXTURE_EXAGGERATION: Float = 2.0f

private const val FIXTURE_LOD: Int = 10
private const val FIXTURE_TILE_Y: Int = 512
private const val FIXTURE_CELLS: Int = 4

private val WHOLE_TILE_WINDOW: FloatArray = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f)

/**
 * `clip.x = 2x`, `clip.y = 2y`, `clip.z = 2^-10 * z`, `clip.w = 1`, column-major.
 *
 * The tile's unit square therefore covers the whole frame and its map-space `z` becomes clip depth
 * and nothing else, so a raised ground has the same silhouette as a flat one. Every factor is a power
 * of two: sea level is window depth `0.5` exactly and [RIDGE_METRES] is `0.75` exactly.
 */
private val GROUND_MATRIX: FloatArray = floatArrayOf(
    2.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 2.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 0.0009765625f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f,
)

/** The identity, into whose translation column [modelMatrix] writes the probe's clip `z`. */
private val MODEL_MATRIX: FloatArray = floatArrayOf(
    1.0f, 0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f, 0.0f,
    0.0f, 0.0f, 1.0f, 0.0f,
    0.0f, 0.0f, 0.0f, 1.0f,
)

private val IDENTITY_MATRIX: FloatArray = MODEL_MATRIX.copyOf()

/**
 * Clip `+/-0.5`, which is half the frame in each axis: a 128-pixel square, 16,384 pixels, centred
 * inside the ground it contests with a whole quarter-frame margin on every side. Nothing here rests
 * on a boundary pixel.
 */
private const val MODEL_HALF_EXTENT: Float = 0.5f

/** Window depth `0.5`, a quarter of the range below the raised ground's `0.75`. */
private const val MODEL_BEHIND_CLIP_Z: Float = 0.0f

/** Window depth `0.95`, a fifth of the range above the raised ground's `0.75`. */
private const val MODEL_IN_FRONT_CLIP_Z: Float = 0.9f

/** Window depth `0.25`, a quarter of the range below the flat ground's `0.5`. */
private const val MODEL_BELOW_FLAT_CLIP_Z: Float = -0.5f

private val FLAT_MODEL_VARIANT: ModelShaderVariant =
    ModelShaderVariant(skinned = false, hasBaseColourTexture = false, masked = false)

/** Opaque and double-sided, so neither the alpha phase nor the winding decides anything here. */
private val PROBE_MATERIAL: ResolvedMaterial = ResolvedMaterial(
    baseColourFactor = floatArrayOf(1.0f, 0.5f, 0.0f, 1.0f),
    baseColourImageIndex = null,
    baseColourSampler = TextureSamplerState(
        minFilter = GL_NEAREST, magFilter = GL_NEAREST,
        wrapS = GL_CLAMP_TO_EDGE, wrapT = GL_CLAMP_TO_EDGE,
    ),
    alphaMode = "OPAQUE",
    alphaCutoff = 0.5f,
    doubleSided = true,
)

private val PROBE_BASE_COLOUR: FloatArray = PROBE_MATERIAL.baseColourFactor

/** `PROBE_BASE_COLOUR * 255`, which the shading term leaves untouched because there is no normal. */
private val MODEL_COLOUR: IntArray = intArrayOf(
    (PROBE_BASE_COLOUR[0] * 255f).toInt(),
    (PROBE_BASE_COLOUR[1] * 255f).toInt(),
    (PROBE_BASE_COLOUR[2] * 255f).toInt(),
)

/** Four colours no two of which are within [DEPTH_CHANNEL_TOLERANCE] of each other in any channel. */
private val GROUND_COLOUR: IntArray = intArrayOf(16, 96, 16)
private val HIGH_GROUND_COLOUR: IntArray = intArrayOf(208, 32, 32)
private val LOW_GROUND_COLOUR: IntArray = intArrayOf(32, 64, 208)

/** A `0.5` factor lands on 127 or 128 depending on the driver's rounding; nothing else is near. */
private const val DEPTH_CHANNEL_TOLERANCE: Int = 6

/** A quarter of the probe quad's own 16,384 pixels: far past a fill rule, far short of a defect. */
private const val MINIMUM_CONTESTED_PIXELS: Int = 4_000

/**
 * The globe fixture's disc measures about 184 pixels across at this zoom, so roughly 26,000 pixels
 * before the polar caps this lod leaves open. A floor of 10,000 is far below that and far above any
 * frame a broken draw could produce.
 */
private const val MINIMUM_GLOBE_DISC_PIXELS: Int = 10_000

private const val GLOBE_FIXTURE_LATITUDE: Double = 8.0
private const val GLOBE_FIXTURE_LONGITUDE: Double = 40.0
private const val GLOBE_FIXTURE_ZOOM: Double = 0.5
private const val GLOBE_FIXTURE_LOD: Int = 1

/**
 * 500, and the size is the measurement's rather than a taste: a summit's 8,848 m against the WGS84
 * semi-major axis is 0.14 per cent, a fifth of a pixel on this fixture's disc.
 */
private const val GLOBE_EXAGGERATION: Float = 500.0f
