package com.rohittp.reng.internal.gl

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.Placement
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.glb.GltfMesh
import com.rohittp.reng.internal.glb.GltfNode
import com.rohittp.reng.internal.glb.GltfPrimitive
import com.rohittp.reng.internal.glb.GltfScene
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.model.BinChunk
import com.rohittp.reng.internal.model.DecodedModel
import com.rohittp.reng.internal.model.DecodedPrimitive
import com.rohittp.reng.internal.model.ModelDrawItem
import com.rohittp.reng.internal.model.ModelIndices
import com.rohittp.reng.internal.model.ResolvedMaterial
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.DrawnThingReference.ModelAt
import com.rohittp.reng.internal.planning.DrawnThingReference.StickerAt
import com.rohittp.reng.internal.planning.resolveBasemapTileQuad
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.planning.resolvePlacement
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.WORLD_CIRCUMFERENCE_METRES
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.DemTileWindow
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneContentTest {

    // --- the empty-scene case: the first thing a consumer hits while wiring an integration -----

    @Test
    fun anEmptySceneIssuesNoGlCallsAtAll() {
        val binding = RecordingGlBinding()
        SceneContent(
            topDownCamera(),
            Scene(outputPixelSize = OUTPUT_SIZE, frameIndex = 0L),
            newStickerPipeline(),
            newGroundPipeline(),
        )
            .draw(binding)
        assertContentEquals(emptyList(), binding.log)
    }

    // --- Task 14: the planner owns the regime split and the screen order, and this layer reads it ---

    /**
     * **The load-bearing provenance test, and the only shape of it that can fail.** With one authority
     * the planner's answer and a second draw-time resolution of the same [Placement] are the same value,
     * so a scene built from consistent inputs proves nothing about which of the two was consulted — it
     * would pass equally well against the code this task deleted. The inputs here are therefore
     * deliberately inconsistent: the planner's split contradicts what re-resolving the placement says,
     * and each half of the test can only take the branch it asserts if the planner's answer is the one
     * that decided the regime.
     *
     * The contradiction surfaces as a loud `IllegalArgumentException` rather than as a composited
     * sticker, and that is the design rather than a limitation of the test.
     * [composeScreenModelViewProjection] and [composeMapCameraSpaceModel] each `require` the placement
     * they were handed to be of their own regime, because the two resolutions are expressed in
     * different spaces (camera-relative logical pixels versus output pixels) and composing one through
     * the other's projection is geometric nonsense, not a lesser rendering. Those two `require`s are now
     * exactly the cross-check that the planner and the placement agree; a `SceneContent` that re-derived
     * the regime from the resolution could never trip either, because it would always call the composer
     * that matches. The message is asserted so that neither failure can be satisfied by some other
     * `IllegalArgumentException` — [Scene]'s own bijection check, for one — thrown along the way.
     */
    @Test
    fun theRegimeSplitComesFromThePlannerRatherThanFromASecondResolution() {
        val binding = RecordingGlBinding()
        val stickerPipeline = newStickerPipeline(binding)

        // Control: a screen-positioned sticker the planner puts in the screen stack composites with
        // depth testing off and never turns it on, which is what the two failures below forgo.
        val agreeing = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(SceneSticker(screenPlacement(z = 5.0), texture = 101)),
            screenOrder = listOf(StickerAt(0)),
        )
        binding.log.clear()
        SceneContent(topDownCamera(), agreeing, stickerPipeline, newGroundPipeline()).draw(binding)
        assertEquals(listOf(101), boundTexturesInDrawOrder(binding), "the control frame must draw")
        assertTrue(binding.log.contains("disable(${hex(GL_DEPTH_TEST)})"), "the screen stack turns depth off")
        assertFalse(binding.log.contains("enable(${hex(GL_DEPTH_TEST)})"), "and never turns it back on")

        // A screen-positioned sticker the planner reports as map-anchored. A second resolution would
        // put it in the screen stack and composite it happily; taking the planner's word calls the map
        // composer, which refuses the placement it is handed.
        val plannerSaysMap = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(SceneSticker(screenPlacement(z = 5.0), texture = 101)),
            mapOrder = listOf(StickerAt(0)),
        )
        val mapFailure = assertFailsWith<IllegalArgumentException> {
            SceneContent(topDownCamera(), plannerSaysMap, stickerPipeline, newGroundPipeline()).draw(binding)
        }
        assertEquals("composeMapCameraSpaceModel requires a map-occluded placement", mapFailure.message)

        // The mirror image, so that neither direction of the disagreement is the one that happens to
        // be checked: a map-positioned sticker the planner reports as screen-composited.
        val plannerSaysScreen = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(SceneSticker(mapPlacement(), texture = 101)),
            screenOrder = listOf(StickerAt(0)),
        )
        val screenFailure = assertFailsWith<IllegalArgumentException> {
            SceneContent(topDownCamera(), plannerSaysScreen, stickerPipeline, newGroundPipeline()).draw(binding)
        }
        assertEquals(
            "composeScreenModelViewProjection requires a screen-composited placement",
            screenFailure.message,
        )
    }

    /**
     * `MercatorSpatialPlanner` sorts the screen stack by z, then sticker-before-model, then source
     * index, and `SceneContent` walks the result. The two stickers below carry the **same** z on
     * purpose: at equal z a re-derived stable sort keeps declaration order, so a planner order of
     * `[1, 0]` is the one input on which the deleted sort and the planner disagree while both remain
     * perfectly legal. Two different z values would not do — the deleted sort would agree with the
     * planner and the test would pass against the code it exists to forbid.
     */
    @Test
    fun theScreenStackFollowsThePlannersOrderRatherThanAnyOrderDerivedHere() {
        val binding = RecordingGlBinding()
        val stickerPipeline = newStickerPipeline(binding)
        val firstDeclared = 101
        val secondDeclared = 202
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(screenPlacement(z = 1.0), texture = firstDeclared),
                SceneSticker(screenPlacement(z = 1.0), texture = secondDeclared),
            ),
            screenOrder = listOf(StickerAt(1), StickerAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, stickerPipeline, newGroundPipeline()).draw(binding)

        assertEquals(
            listOf(secondDeclared, firstDeclared),
            boundTexturesInDrawOrder(binding),
            "the screen stack draws the planner's order, not the sticker list's: ${binding.log}",
        )
    }

    /**
     * The same claim for the map half. `drawStickers` preserves what it is handed (ADR 0027 makes
     * painter's order the whole rule there), and what it is handed is the planner's `mapEntries` order
     * for stickers rather than `Scene.stickers` walked front to back.
     */
    @Test
    fun perTypeDeclarationOrderInsideTheMapRegimeIsStillThePlannersOwn() {
        val binding = RecordingGlBinding()
        val stickerPipeline = newStickerPipeline(binding)
        val firstDeclared = 101
        val secondDeclared = 202
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = firstDeclared),
                SceneSticker(mapPlacement(), texture = secondDeclared),
            ),
            mapOrder = listOf(StickerAt(1), StickerAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, stickerPipeline, newGroundPipeline()).draw(binding)

        assertEquals(
            listOf(secondDeclared, firstDeclared),
            boundTexturesInDrawOrder(binding),
            "the map half draws the planner's order, not the sticker list's: ${binding.log}",
        )
    }

    /**
     * ADR 0029 refuses a `SCREEN`-positioned [com.rohittp.reng.Model] at frame planning, so a
     * `ModelAt` in the screen order is a contract violation — and [SceneContent] reports it as one
     * rather than quietly dropping it or drawing it in the map regime instead. The stack is built by
     * walking the merged order precisely so this case has a place to be refused *in*: building it out
     * of `Scene.stickers` would encode "no models here" in the loop's shape, and the merge would have
     * to be reinvented the day ADR 0029 is revisited.
     */
    @Test
    fun aModelInTheScreenOrderIsRefusedRatherThanSilentlyRedirectedOrDropped() {
        val binding = modelCapableBinding()
        val modelPipelines = newModelPipelines(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(sceneModel()),
            screenOrder = listOf(ModelAt(0)),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            SceneContent(
                topDownCamera(),
                scene,
                newStickerPipeline(binding),
                newGroundPipeline(binding),
                modelPipelines,
            ).draw(binding)
        }
        assertTrue(failure.message.orEmpty().contains("ADR 0029"), "the refusal must name its reason")
    }

    /**
     * A [Scene] whose orders do not name every drawn thing it carries is a caller that built the two
     * halves inconsistently — most plausibly by forgetting the orders altogether, which is the exact
     * mistake that would otherwise render a frame's stickers and models silently missing. The bijection
     * is checked at construction so the omission is reported before any GL call is issued.
     */
    @Test
    fun aSceneWhoseOrdersDoNotNameEveryDrawnThingIsRefusedAtConstruction() {
        val stickers = listOf(SceneSticker(mapPlacement(), texture = 101))
        assertFailsWith<IllegalArgumentException> {
            Scene(outputPixelSize = OUTPUT_SIZE, frameIndex = 0L, stickers = stickers)
        }
        assertFailsWith<IllegalArgumentException> {
            Scene(
                outputPixelSize = OUTPUT_SIZE,
                frameIndex = 0L,
                stickers = stickers,
                mapOrder = listOf(StickerAt(0)),
                screenOrder = listOf(StickerAt(0)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Scene(outputPixelSize = OUTPUT_SIZE, frameIndex = 0L, mapOrder = listOf(StickerAt(0)))
        }
    }

    // --- ADR 0024: both regimes compose in one frame, not merely in isolation ------------------

    @Test
    fun theMapRegimeDrawsGeometriesAndMapAnchoredStickersBeforeTheScreenRegimeComposites() {
        val binding = RecordingGlBinding()
        val camera = topDownCamera()
        val geometryPipeline = newGeometryPipeline(binding)
        val stickerPipeline = newStickerPipeline(binding)
        val mapTexture = 101
        val screenTexture = 202

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = mapTexture),
                SceneSticker(screenPlacement(z = 5.0), texture = screenTexture),
            ),
            geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline, consumerUniforms = emptyMap())),
            mapOrder = listOf(StickerAt(0)),
            screenOrder = listOf(StickerAt(1)),
        )
        binding.log.clear()

        SceneContent(camera, scene, stickerPipeline, newGroundPipeline()).draw(binding)

        // A geometry is a subdivided, CPU-projected grid as of Cycle G task 9, so it is the only
        // `drawElements` in this fixture; the two stickers are the `drawArrays` pair.
        val geometryDraw = binding.log.indexOfFirst { it.startsWith("drawElements") }
        val depthDisabled = binding.log.indexOfFirst { it == "disable(${hex(GL_DEPTH_TEST)})" }
        val mapStickerBind = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$mapTexture)" }
        val screenStickerBind = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$screenTexture)" }
        val screenDraw = binding.log.indexOfLast { it.startsWith("drawArrays") }

        assertTrue(geometryDraw in 0 until depthDisabled, "the geometry must draw before depth testing is turned off")
        assertTrue(mapStickerBind in geometryDraw until depthDisabled, "the map-anchored sticker draws depth-tested too")
        assertTrue(depthDisabled < screenStickerBind, "the screen-anchored sticker composites after depth testing is off")
        assertTrue(depthDisabled in 0 until screenDraw, "every depth-tested thing draws before the screen regime composites")
    }

    // --- ADRs 0027 and 0030: the map regime's three depth phases ---------------------------------

    /**
     * ADR 0027 as an invariant over the whole scene rather than as per-pipeline assertions, because
     * the defect it closes was a *pass* that forgot — now carrying ADR 0030's amendment.
     *
     * `drawFrame` hands [SceneContent] a context with `glDepthMask(GL_TRUE)` — it has to, or the
     * per-frame depth clear would not take — so every pass owes its own depth state. This walks the
     * call log with the mask's real starting value and makes three claims at once:
     *
     * - every flat map-plane draw (the ground, each `Geometry`) and every sticker draws with writes
     *   **off**, exactly as before;
     * - **exactly one** phase turns writes on, and it is the model pass;
     * - the mask is off again **on the way out of the model pass**, before the sticker pass runs.
     *
     * **This fixture's ground carries no DEM, and after ADR 0039 that is the load-bearing half of
     * it.** The count of one is now a statement about a frame with *no terrain* -- which is 28 of the
     * corpus's 34 styles and every frame of three published releases -- rather than about every
     * frame, and a build that made the ground's new depth write unconditional takes it to two and
     * fails here. Its terrain twin is
     * [aDisplacedGroundIsTheSecondPhaseThatWritesAndTheMaskIsOffAgainBeforeTheGeometries], which
     * asserts **two** rather than widening this one: a widened count would also accept a frame that
     * wrote depth in the wrong pass.
     *
     * **The fixture carries a label batch, and ADR 0034 is what put it there.** Labels are not a
     * member of the map regime, so ADRs 0027 and 0030 do not describe them — but this invariant is
     * over the *whole scene*, so it does: a label pass that enabled a depth write would take the
     * count of `depthMask(true)` calls to two and fail here, which is the intended binding.
     *
     * **Three passes now issue `glDrawElements`, and telling them apart is load-bearing.** Cycle G
     * task 9 made a `Geometry` a subdivided grid, so its draw is indexed like the model pass's and
     * carries the identical `GL_UNSIGNED_SHORT` index type — a fixture that read them as one kind of
     * draw would assert ADR 0030's "must write depth" over a pass ADR 0027 says must not. So the
     * geometry pass is identified by its own program, which is the only thing that distinguishes it,
     * and the label pass by its `GL_UNSIGNED_INT` indices.
     *
     * That last claim is deliberately checked at the model pass's own exit rather than at the first
     * sticker draw. [drawStickers] sets `depthMask(false)` for itself, so a check taken at the
     * sticker's draw call sits at a symmetry point and would stay green with the model pass's exit
     * mask deleted outright — asserting nothing about the very line ADR 0030 says is the easiest one
     * to forget. Measuring the state at the boundary between the two passes is what makes deleting
     * `drawModels`' trailing `depthMask(false)` fail here.
     */
    @Test
    fun exactlyOnePhaseWritesDepthItIsTheModelPassAndTheMaskIsOffAgainOnTheWayOut() {
        val binding = modelCapableBinding()
        val camera = topDownCamera()
        val geometryPipeline = newGeometryPipeline(binding)
        val stickerPipeline = newStickerPipeline(binding)
        val groundPipeline = newGroundPipeline(binding)
        val modelPipelines = newModelPipelines(binding)
        val labelPipeline = newLabelPipeline(binding)

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = 101),
                SceneSticker(screenPlacement(z = 5.0), texture = 202),
            ),
            geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline, consumerUniforms = emptyMap())),
            groundTiles = listOf(groundTile(canonicalX = 0, tileY = 0, texture = 303)),
            models = listOf(sceneModel(indexCounts = listOf(OPAQUE_INDEX_COUNT))),
            labels = listOf(labelBatch()),
            mapOrder = listOf(StickerAt(0), ModelAt(0)),
            screenOrder = listOf(StickerAt(1)),
        )
        binding.log.clear()

        SceneContent(camera, scene, stickerPipeline, groundPipeline, modelPipelines, labelPipeline).draw(binding)

        // `drawFrame` leaves the mask on for its depth clear, so that is the state a scene inherits.
        var depthWrites = true
        var currentProgram = -1
        var flatDraws = 0
        var modelDraws = 0
        var labelDraws = 0
        var firstModelDraw = -1
        // Every draw is classified by the program bound before it, never by its shape. The ground
        // joined the geometry pass in issuing `drawElements(GL_TRIANGLES, ..., GL_UNSIGNED_SHORT, 0)`
        // when Cycle E-terrain subdivided it, which is byte-identical to an opaque model's draw; a
        // classifier keyed on the call alone read the ground as a model and demanded it write depth.
        val flatPrograms = setOf(geometryPipeline.program, groundPipeline.program)
        binding.log.forEachIndexed { index, call ->
            when {
                call == "depthMask(true)" -> depthWrites = true
                call == "depthMask(false)" -> depthWrites = false
                call.startsWith("useProgram(") ->
                    currentProgram = call.removePrefix("useProgram(").removeSuffix(")").toInt()
                call.startsWith("drawArrays") ||
                    (call.startsWith("drawElements") && currentProgram in flatPrograms) -> {
                    flatDraws += 1
                    assertFalse(
                        depthWrites,
                        "call $index ($call) draws with depth writes still on; ADR 0027 requires " +
                            "the ground, every Geometry and every sticker to turn them off",
                    )
                }
                call.startsWith("drawElements(${hex(GL_TRIANGLES)},") &&
                    call.endsWith(",${hex(GL_UNSIGNED_INT)},0)") -> {
                    labelDraws += 1
                    assertFalse(
                        depthWrites,
                        "call $index ($call) is the label batch and ADR 0034 gives phase 5 no depth " +
                            "write at all, so the model pass stays the one phase that has one",
                    )
                }
                call.startsWith("drawElements") -> {
                    modelDraws += 1
                    if (firstModelDraw < 0) firstModelDraw = index
                    assertTrue(
                        depthWrites,
                        "call $index ($call) is an opaque model draw and ADR 0030 requires it to " +
                            "write depth, or a mesh cannot occlude itself",
                    )
                }
            }
        }
        assertEquals(4, flatDraws, "the scene must issue one ground, one geometry and two sticker draws")
        assertEquals(1, modelDraws, "the scene must issue exactly one model draw")
        assertEquals(1, labelDraws, "the scene must issue exactly one label draw")

        assertEquals(
            1,
            binding.log.count { it == "depthMask(true)" },
            "exactly one phase in the whole scene may enable depth writes: ${binding.log}",
        )
        // The enable belongs to the model pass, pinned by position rather than by name: `drawModels`
        // sets the mask before it binds any program, so the ground and the geometry -- and only
        // those two -- have already drawn by the time it fires.
        val writesOn = binding.log.indexOf("depthMask(true)")
        // The model's own draw, located by the program bound before it rather than by its shape: the
        // ground, the geometry and the model all issue an indexed triangle draw now, so "the second
        // drawElements in the log" names the geometry pass and not the model pass.
        val modelDraw = firstModelDraw
        assertTrue(modelDraw > 0, "the scene must contain a model draw to locate")
        assertTrue(writesOn in 0 until modelDraw, "the one depth-write enable precedes the model's own draw")
        assertEquals(
            2,
            binding.log.subList(0, writesOn)
                .count { it.startsWith("drawArrays") || it.startsWith("drawElements") },
            "the enable falls after the ground and the geometry and before every sticker: ${binding.log}",
        )

        // The model pass ends where the sticker pass begins; the last mask call before that boundary
        // is the model pass's exit mask, and it must be off.
        val stickerPassStart = binding.log.indexOf("useProgram(${stickerPipeline.program})")
        assertTrue(modelDraw < stickerPassStart, "the sticker pass begins after the model pass draws")
        val exitMask = binding.log.subList(modelDraw, stickerPassStart).lastOrNull { it.startsWith("depthMask") }
        assertEquals(
            "depthMask(false)",
            exitMask,
            "ADR 0030: the model pass owes a depthMask(false) on the way out, or ADR 0027's " +
                "billboard fix silently stops working in every frame with a model and a billboard",
        )
    }

    /**
     * **ADR 0039's terrain arm, asserting two writes rather than widening the count above to two.**
     *
     * A frame whose ground is displaced has *two* phases that enable depth writes -- the ground
     * first, the models second -- and the difference between "exactly two" and "at most two" is the
     * whole value of this test: a widened count on the no-terrain fixture would pass just as happily
     * against a build that wrote depth in the geometry pass and left the ground alone, which is the
     * opposite of ADR 0039.
     *
     * So the claim is made per *pass* rather than per call. Every draw is classified by the program
     * bound before it, and the ground's displacing program is what identifies phase 1 -- the ground,
     * a `Geometry` and an opaque model all issue `drawElements(GL_TRIANGLES, ..., GL_UNSIGNED_SHORT,
     * 0)` since Cycle E-terrain subdivided the ground, so a classifier keyed on the call alone cannot
     * tell them apart. The ground must draw with writes **on**; the geometry, the stickers and the
     * label batch must all draw with them **off**, which is what pins the mask coming back down
     * between phase 1 and phase 2 rather than staying on for the rest of the map regime.
     */
    @Test
    fun aDisplacedGroundIsTheSecondPhaseThatWritesAndTheMaskIsOffAgainBeforeTheGeometries() {
        val binding = modelCapableBinding()
        val camera = topDownCamera()
        val geometryPipeline = newGeometryPipeline(binding)
        val stickerPipeline = newStickerPipeline(binding)
        val groundPipeline = newGroundPipeline(binding)
        val modelPipelines = newModelPipelines(binding)
        val labelPipeline = newLabelPipeline(binding)

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = 101),
                SceneSticker(screenPlacement(z = 5.0), texture = 202),
            ),
            geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline, consumerUniforms = emptyMap())),
            groundTiles = listOf(displacedGroundTile(canonicalX = 0, tileY = 0, texture = 303, demTexture = 77)),
            terrain = fixtureTerrain(),
            models = listOf(sceneModel(indexCounts = listOf(OPAQUE_INDEX_COUNT))),
            labels = listOf(labelBatch()),
            mapOrder = listOf(StickerAt(0), ModelAt(0)),
            screenOrder = listOf(StickerAt(1)),
        )
        binding.log.clear()

        SceneContent(camera, scene, stickerPipeline, groundPipeline, modelPipelines, labelPipeline).draw(binding)

        var depthWrites = true
        var currentProgram = -1
        var groundDraws = 0
        var flatDraws = 0
        var labelDraws = 0
        var modelDraws = 0
        binding.log.forEachIndexed { index, call ->
            when {
                call == "depthMask(true)" -> depthWrites = true
                call == "depthMask(false)" -> depthWrites = false
                call.startsWith("useProgram(") ->
                    currentProgram = call.removePrefix("useProgram(").removeSuffix(")").toInt()
                call.startsWith("drawElements") && currentProgram == groundPipeline.terrain.program -> {
                    groundDraws += 1
                    assertTrue(
                        depthWrites,
                        "call $index ($call) is the displaced ground and ADR 0039 requires it to " +
                            "write depth, or terrain occludes nothing",
                    )
                }
                call.startsWith("drawArrays") ||
                    (call.startsWith("drawElements") && currentProgram == geometryPipeline.program) -> {
                    flatDraws += 1
                    assertFalse(
                        depthWrites,
                        "call $index ($call) draws with depth writes still on; ADR 0027 keeps every " +
                            "Geometry and every sticker out of the depth buffer even over terrain",
                    )
                }
                call.startsWith("drawElements(${hex(GL_TRIANGLES)},") &&
                    call.endsWith(",${hex(GL_UNSIGNED_INT)},0)") -> {
                    labelDraws += 1
                    assertFalse(depthWrites, "call $index ($call) is the label batch, and ADR 0034 gives it no write")
                }
                call.startsWith("drawElements") -> {
                    modelDraws += 1
                    assertTrue(depthWrites, "call $index ($call) is an opaque model draw (ADR 0030)")
                }
            }
        }
        assertEquals(1, groundDraws, "the scene must issue exactly one displaced ground draw: ${binding.log}")
        assertEquals(3, flatDraws, "one geometry and two stickers")
        assertEquals(1, labelDraws, "one label batch")
        assertEquals(1, modelDraws, "one model draw")

        assertEquals(
            2,
            binding.log.count { it == "depthMask(true)" },
            "a terrain frame has exactly two phases that write depth, the ground and the models, " +
                "and no third: ${binding.log}",
        )
    }

    /**
     * **The condition is the frame's, and a frame is displaced by carrying a DEM rather than by
     * declaring terrain.**
     *
     * [Scene.terrain] is non-null whenever the style declared a `terrain` block RenG agreed with,
     * *including* when every one of the frame's tiles turned out to have no DEM -- ADR 0041 draws
     * those tiles flat rather than failing the frame. Such a frame has no relief anywhere, so it is
     * ADR 0027's frame in every respect that matters and must write no depth. Keying the write on
     * `terrain != null` instead of on the DEMs would revive the coplanar defect in exactly the frames
     * ADR 0041 exists to keep renderable.
     */
    @Test
    fun aTerrainFrameWhoseEveryTileLostItsDemWritesNoGroundDepth() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = newGroundPipeline(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            groundTiles = listOf(groundTile(canonicalX = 0, tileY = 0, texture = 303)),
            terrain = fixtureTerrain(),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(), pipeline).draw(binding)

        assertTrue(
            binding.log.any { it.startsWith("drawElements") },
            "the frame must still draw its ground flat (ADR 0041): ${binding.log}",
        )
        assertFalse(
            binding.log.any { it == "depthMask(true)" },
            "a declared terrain none of whose tiles has a DEM is a flat ground, and a flat ground " +
                "writes no depth (ADR 0039): ${binding.log}",
        )
    }

    // --- ADR 0030: ground, geometries, models, then map-anchored stickers -------------------------

    /**
     * ADR 0025's map-regime order with ADR 0030's models inserted before the stickers, because a
     * map-anchored sticker is a marker and a marker paints over the scene it marks. Each pass is
     * located by its own `useProgram`, not by its draw call: before Cycle E-terrain the ground and a
     * sticker both issued a bit-identical `drawArrays(GL_TRIANGLE_STRIP, 0, 4)`, and now the ground
     * is an indexed grid whose draw is bit-identical in shape to a `Geometry`'s (Cycle G task 9) and
     * to the model pass's. Every pairing has collided at some point, which is why locating a pass by
     * its program is the only rule that has ever worked for all four.
     *
     * `mapOrder` here is the planner's own `[StickerAt(0), ModelAt(0)]` — stickers-then-models by
     * declaration, which is what `MercatorSpatialPlan.mapEntries` actually produces. That is the
     * point: the list is a regime-membership answer, not a draw order, and a `SceneContent` that
     * consumed it verbatim would draw the sticker first and fail here. ADR 0030's phase order has to
     * be applied *over* the planner's answer rather than taken from it.
     *
     * The ground and a sticker still issue a bit-identical `drawArrays(GL_TRIANGLE_STRIP, 0, 4)`; a
     * `Geometry` no longer does, because Cycle G task 9 made it an indexed grid. Locating each pass
     * by its own `useProgram` was already the rule here and stays the rule, since a draw call still
     * cannot tell the ground from a sticker.
     */
    @Test
    fun theMapRegimeDrawsGroundThenGeometriesThenModelsThenMapAnchoredStickers() {
        val binding = modelCapableBinding()
        val camera = topDownCamera()
        val geometryPipeline = newGeometryPipeline(binding)
        val stickerPipeline = newStickerPipeline(binding)
        val groundPipeline = newGroundPipeline(binding)
        val modelPipelines = newModelPipelines(binding)
        val mapTexture = 101
        val screenTexture = 202

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = mapTexture),
                SceneSticker(screenPlacement(z = 5.0), texture = screenTexture),
            ),
            geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline, consumerUniforms = emptyMap())),
            groundTiles = listOf(groundTile(canonicalX = 8, tileY = 8, texture = 303)),
            models = listOf(sceneModel()),
            mapOrder = listOf(StickerAt(0), ModelAt(0)),
            screenOrder = listOf(StickerAt(1)),
        )
        binding.log.clear()

        SceneContent(camera, scene, stickerPipeline, groundPipeline, modelPipelines).draw(binding)

        val ground = binding.log.indexOf("useProgram(${groundPipeline.program})")
        val geometry = binding.log.indexOf("useProgram(${geometryPipeline.program})")
        val model = binding.log.indexOf("useProgram(${modelPipelines.values.first().program})")
        val stickers = binding.log.indexOf("useProgram(${stickerPipeline.program})")
        val mapSticker = binding.log.indexOf("bindTexture(${hex(GL_TEXTURE_2D)},$mapTexture)")
        val depthDisabled = binding.log.indexOf("disable(${hex(GL_DEPTH_TEST)})")
        val screenSticker = binding.log.indexOf("bindTexture(${hex(GL_TEXTURE_2D)},$screenTexture)")

        assertTrue(ground >= 0 && geometry >= 0 && model >= 0 && stickers >= 0, "all four passes must run")
        assertTrue(ground < geometry, "the ground is the backdrop everything else paints onto")
        assertTrue(geometry < model, "geometries draw before models")
        assertTrue(model < stickers, "models draw before map-anchored stickers, which mark them")
        assertTrue(stickers < mapSticker, "the map-anchored sticker draws inside the sticker pass")
        assertTrue(mapSticker < depthDisabled, "the whole map regime is depth-tested")
        assertTrue(depthDisabled < screenSticker, "the screen regime still composites last")
    }

    /**
     * The blended half of the model pass draws inside the model pass, and still before the stickers.
     * A model with one opaque and one blended primitive is the case where the model pass's own two
     * phases could be mistaken for the whole map regime's — the opaque draw writes depth, the blended
     * one does not, and both precede every map-anchored sticker.
     */
    @Test
    fun aBlendedModelPrimitiveStillDrawsInsideTheModelPassAndBeforeTheStickers() {
        val binding = modelCapableBinding()
        val stickerPipeline = newStickerPipeline(binding)
        val modelPipelines = newModelPipelines(binding)

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(SceneSticker(mapPlacement(), texture = 101)),
            models = listOf(
                sceneModel(
                    indexCounts = listOf(OPAQUE_INDEX_COUNT, BLENDED_INDEX_COUNT),
                    alphaModes = listOf("OPAQUE", "BLEND"),
                ),
            ),
            mapOrder = listOf(StickerAt(0), ModelAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, stickerPipeline, newGroundPipeline(binding), modelPipelines)
            .draw(binding)

        val opaque =
            binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},$OPAQUE_INDEX_COUNT,") }
        val blended =
            binding.log.indexOfFirst { it.startsWith("drawElements(${hex(GL_TRIANGLES)},$BLENDED_INDEX_COUNT,") }
        val stickers = binding.log.indexOf("useProgram(${stickerPipeline.program})")
        assertTrue(opaque in 0 until blended, "the opaque primitive draws before the blended one")
        assertTrue(blended in 0 until stickers, "both halves of the model pass precede the stickers")
    }

    // --- ADR 0034: labels are phase 5, between the two regimes --------------------------------

    /**
     * ADR 0034's whole contract in one call log: the label draw lands after **every** map-regime
     * draw and before the screen regime's first one.
     *
     * **The fixture carries both a map-anchored and a screen-anchored sticker, and that is what
     * makes this test say anything.** A scene with labels and no stickers cannot tell "labels last
     * in the map regime" from "labels first in the screen regime" — the two orderings produce an
     * identical log — and neither can a scene whose only sticker is on one side. With one of each,
     * phase 5 has to land strictly between them, so moving the call to either neighbouring phase
     * fails here.
     *
     * **Each pass is located by its own draw, never by a program bind.** `beginLabelPass` binds the
     * label program before it uploads anything, so a `useProgram` assertion would stay green with
     * `drawLabelBatch`'s `glDrawElements` deleted outright — the pass would set itself up in the
     * right place and paint nothing. The label draw is identified by its exact call, whose index
     * count ([LABEL_QUADS] quads x [LABEL_INDICES_PER_QUAD]) and `GL_UNSIGNED_INT` index type
     * distinguish it from the model pass's `GL_UNSIGNED_SHORT` draw.
     */
    @Test
    fun labelsDrawAfterEveryMapRegimeDrawAndBeforeTheScreenRegimesFirstDraw() {
        val frame = labelledFrame()
        frame.content.draw(frame.binding)
        val log = frame.binding.log

        val groundDraw = log.firstDrawAfter(log.indexOf("useProgram(${frame.ground.program})"))
        val geometryDraw = log.firstDrawAfter(log.indexOf("useProgram(${frame.geometry.program})"))
        val modelDraw = log.indexOfFirst {
            it == "drawElements(${hex(GL_TRIANGLES)},$OPAQUE_INDEX_COUNT,${hex(GL_UNSIGNED_SHORT)},0)"
        }
        val mapStickerDraw = log.firstDrawAfter(log.indexOf("bindTexture(${hex(GL_TEXTURE_2D)},$MAP_STICKER_TEXTURE)"))
        val labelDraw = log.indexOfFirst { it == LABEL_DRAW_CALL }
        val screenStickerDraw =
            log.firstDrawAfter(log.indexOf("bindTexture(${hex(GL_TEXTURE_2D)},$SCREEN_STICKER_TEXTURE)"))

        assertTrue(
            groundDraw >= 0 && geometryDraw >= 0 && modelDraw >= 0 && mapStickerDraw >= 0 &&
                labelDraw >= 0 && screenStickerDraw >= 0,
            "all six phases must have drawn: $log",
        )
        assertTrue(groundDraw < labelDraw, "phase 1's ground draws before the labels")
        assertTrue(geometryDraw < labelDraw, "phase 2's geometry draws before the labels")
        assertTrue(modelDraw < labelDraw, "phase 3's model draws before the labels")
        assertTrue(
            mapStickerDraw < labelDraw,
            "ADR 0034: phase 5 draws after phase 4's map-anchored sticker, so a label covers it",
        )
        assertTrue(
            labelDraw < screenStickerDraw,
            "ADR 0034: phase 5 draws before the screen regime, so a consumer's screen sticker covers a label",
        )
        assertEquals(
            1,
            log.count { it == LABEL_DRAW_CALL },
            "$LABEL_QUADS glyph quads are one batch and therefore exactly one draw: $log",
        )
    }

    /**
     * ADR 0034's depth ruling, read off the same log as a state machine rather than as the presence
     * of a `disable` call.
     *
     * The disable is genuinely load-bearing rather than a restatement: `drawStickers` sets
     * `enable(GL_DEPTH_TEST)` for itself, so phase 4 hands phase 5 a context with the test **on**.
     * Walking the log with the state `drawFrame` establishes before the scene runs is what proves
     * that — every map-regime draw is depth-tested, the label draw is not, and deleting
     * `beginLabelPass`'s `disable` flips exactly the one reading that matters.
     *
     * The counterpart claim — that no depth *write* is enabled in the pass — is checked by
     * [exactlyOnePhaseWritesDepthItIsTheModelPassAndTheMaskIsOffAgainOnTheWayOut], whose fixture
     * carries labels for exactly that reason. Keeping it there rather than duplicating it here is
     * what ADR 0034 means by binding labels to the amended form of that invariant.
     */
    @Test
    fun theLabelPassDrawsWithDepthTestingOffWhileEveryMapRegimeDrawKeepsItOn() {
        val frame = labelledFrame()
        frame.content.draw(frame.binding)
        val log = frame.binding.log

        // `drawFrame` enables GL_DEPTH_TEST before it hands the scene the context, so that -- not
        // `false` -- is the state a scene inherits, and a pass that never touches it stays tested.
        var depthTest = true
        val drawsWithDepthTest = ArrayList<Pair<Int, Boolean>>()
        log.forEachIndexed { index, call ->
            when {
                call == "enable(${hex(GL_DEPTH_TEST)})" -> depthTest = true
                call == "disable(${hex(GL_DEPTH_TEST)})" -> depthTest = false
                call.startsWith("drawArrays") || call.startsWith("drawElements") ->
                    drawsWithDepthTest += index to depthTest
            }
        }

        val labelDraw = log.indexOfFirst { it == LABEL_DRAW_CALL }
        assertTrue(labelDraw >= 0, "the label pass must have drawn: $log")

        val mapRegimeDraws = drawsWithDepthTest.filter { it.first < labelDraw }
        assertEquals(
            4,
            mapRegimeDraws.size,
            "the ground, the geometry, the model and one map-anchored sticker precede the labels: $log",
        )
        assertTrue(
            mapRegimeDraws.all { it.second },
            "ADR 0027: every map-regime pass draws with GL_DEPTH_TEST enabled: $log",
        )
        assertFalse(
            drawsWithDepthTest.single { it.first == labelDraw }.second,
            "ADR 0034: the label pass disables the depth test, and phase 4 leaves it enabled behind it",
        )

        val afterTheLabels = drawsWithDepthTest.filter { it.first > labelDraw }
        assertEquals(1, afterTheLabels.size, "only the screen-anchored sticker draws after the labels: $log")
        assertTrue(
            afterTheLabels.none { it.second },
            "the screen regime still composites with the depth test off after phase 5: $log",
        )
    }

    /**
     * The phase order is a *relative* order and not a claim that any earlier phase produced pixels.
     * `FramePlan.drawLabels` is orthogonal to `drawBasemap`, so `drawBasemap = false,
     * drawLabels = true` is a legal frame and phase 5 has to run on it with no ground, no geometry,
     * no model and no sticker in front of it.
     *
     * This is the case the nothing-to-draw guard at the top of [SceneContent.draw] gets wrong by
     * omission: a guard listing only the four `FramePlan`-derived lists returns before phase 5 and
     * silently renders an empty frame for every labels-only plan.
     */
    @Test
    fun aSceneCarryingOnlyLabelsStillDrawsThem() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val stickerPipeline = newStickerPipeline(binding)
        val groundPipeline = newGroundPipeline(binding)
        val labelPipeline = newLabelPipeline(binding)
        val scene = Scene(outputPixelSize = OUTPUT_SIZE, frameIndex = 0L, labels = listOf(labelBatch()))
        binding.log.clear()

        SceneContent(topDownCamera(), scene, stickerPipeline, groundPipeline, emptyMap(), labelPipeline)
            .draw(binding)

        assertEquals(1, binding.log.count { it == LABEL_DRAW_CALL }, "a labels-only scene still draws: ${binding.log}")
    }

    /**
     * The same rule [aSceneWithModelsAndNoPipelinesFailsLoudlyRatherThanDrawingNothing] states for
     * models: omitting the pipeline for content the scene actually carries must not be silent. A
     * map drawn with every label dropped looks finished, which is the failure mode worth being loud
     * about.
     */
    @Test
    fun aSceneWithLabelsAndNoLabelPipelineFailsLoudlyRatherThanDrawingATextlessMap() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val scene = Scene(outputPixelSize = OUTPUT_SIZE, frameIndex = 0L, labels = listOf(labelBatch()))

        assertFailsWith<IllegalArgumentException> {
            SceneContent(topDownCamera(), scene, newStickerPipeline(binding), newGroundPipeline(binding))
                .draw(binding)
        }
    }

    /**
     * ADR 0034: `Scene`'s construction-time bijection counts stickers and models, and labels are
     * outside it. A label is engine-derived — no entry in any of the caller's three `FramePlan`
     * lists corresponds to one — so there is no reference that could name it and nothing for the
     * bijection to check. Two batches beside one sticker is the shape that catches a bijection
     * widened to `stickers.size + models.size + labels.size`, which would refuse every label-bearing
     * scene at construction.
     */
    @Test
    fun labelsSitOutsideTheBijectionThatCountsStickersAndModelsOnly() {
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(SceneSticker(mapPlacement(), texture = MAP_STICKER_TEXTURE)),
            labels = listOf(labelBatch(), labelBatch(atlasTexture = SECOND_LABEL_ATLAS_TEXTURE)),
            mapOrder = listOf(StickerAt(0)),
        )

        assertEquals(2, scene.labels.size, "both batches survive construction unreferenced by either order list")
        assertEquals(listOf(StickerAt(0)), scene.mapOrder, "the order lists still name stickers and models only")
        assertTrue(scene.screenOrder.isEmpty())
    }

    // --- what SceneContent derives per model, on top of what Task 16 hands it --------------------

    /**
     * A model's model-view-projection is the placement's own camera-space model matrix with the
     * node's global transform inserted on its right, projected once — never the placement matrix
     * narrowed to `Float` and then multiplied by the node transform, which would throw away the
     * precision the rest of this file exists to keep. The node transform here is a translation on
     * every axis with distinct components and a non-uniform scale, so a dropped or transposed node
     * transform cannot coincide with the right answer.
     */
    @Test
    fun aModelsMvpIsTheProjectionOfThePlacementMatrixTimesItsNodeTransform() {
        val binding = modelCapableBinding().withDeclaredNames(
            MODEL_VIEW_PROJECTION_UNIFORM_NAME to MVP_LOCATION,
            MODEL_NORMAL_MATRIX_UNIFORM_NAME to NORMAL_LOCATION,
        )
        binding.integers[GL_MAX_UNIFORM_BLOCK_SIZE] = intArrayOf(16384)
        val camera = topDownCamera()
        val modelPipelines = newModelPipelines(binding)
        val placement = mapPlacementAt(Vector3(0.5, -0.25, 0.0))
        val node = asymmetricNodeTransform()
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(sceneModel(placement = placement, nodeTransforms = listOf(node))),
            mapOrder = listOf(ModelAt(0)),
        )
        binding.log.clear()

        SceneContent(camera, scene, newStickerPipeline(binding), newGroundPipeline(binding), modelPipelines)
            .draw(binding)

        val resolved = (resolvePlacement(placement, camera) as SpatialOutcome.Success).value
        val expected = columnMajor(camera.projectionMatrix * composeMapCameraSpaceModel(camera, resolved) * node)
        val withoutNode = columnMajor(camera.projectionMatrix * composeMapCameraSpaceModel(camera, resolved))

        val actual = requireNotNull(binding.uniformMatrix4fvValues[MVP_LOCATION]) { "the MVP must be bound" }
        assertContentEquals(expected, actual)
        assertTrue(actual.toList() != withoutNode.toList(), "the node transform must actually be applied")
    }

    /**
     * The normal matrix is derived from the same camera-space model matrix the position travels
     * through, node transform included. The fixture's node scale is `diag(2, 3, 5)` rather than
     * anything symmetric: a normal matrix on `diag(2, 2, 2)` or on any symmetric block equals its own
     * transpose, and a check taken there stays green with the transpose deleted.
     */
    @Test
    fun aModelsNormalMatrixIsTheInverseTransposeOfTheSameCameraSpaceModel() {
        val binding = modelCapableBinding().withDeclaredNames(
            MODEL_VIEW_PROJECTION_UNIFORM_NAME to MVP_LOCATION,
            MODEL_NORMAL_MATRIX_UNIFORM_NAME to NORMAL_LOCATION,
        )
        binding.integers[GL_MAX_UNIFORM_BLOCK_SIZE] = intArrayOf(16384)
        val camera = topDownCamera()
        val modelPipelines = newModelPipelines(binding)
        val placement = mapPlacementAt(Vector3(0.5, -0.25, 0.0))
        val node = asymmetricNodeTransform()
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(sceneModel(placement = placement, nodeTransforms = listOf(node))),
            mapOrder = listOf(ModelAt(0)),
        )
        binding.log.clear()

        SceneContent(camera, scene, newStickerPipeline(binding), newGroundPipeline(binding), modelPipelines)
            .draw(binding)

        val resolved = (resolvePlacement(placement, camera) as SpatialOutcome.Success).value
        val expected = requireNotNull(modelNormalMatrix(composeMapCameraSpaceModel(camera, resolved) * node))
        val actual = requireNotNull(binding.uniformMatrix4fvValues[NORMAL_LOCATION]) { "the normal matrix is bound" }
        assertContentEquals(expected, actual)
    }

    /**
     * With no override, a primitive's material resolves its base-colour image index through
     * [SceneModel.imageTextures] to the GL name Task 16 uploaded it to. Without this the next test's
     * "the authored image is not bound" half would be vacuous — a path that never binds anything
     * satisfies it too.
     */
    @Test
    fun theAuthoredBaseColourImageIsBoundWhenThereIsNoOverride() {
        val binding = modelCapableBinding()
        val modelPipelines = newModelPipelines(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(
                sceneModel(
                    baseColourImageIndex = 0,
                    imageTextures = listOf(AUTHORED_TEXTURE),
                    attributes = TEXTURED_ATTRIBUTES,
                ),
            ),
            mapOrder = listOf(ModelAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(binding), newGroundPipeline(binding), modelPipelines)
            .draw(binding)

        assertTrue(
            binding.log.contains("bindTexture(${hex(GL_TEXTURE_2D)},$AUTHORED_TEXTURE)"),
            "the authored image's uploaded texture must be bound: ${binding.log}",
        )
    }

    /**
     * `CONTEXT.md`: a `Model.texture` override "replaces every rendered primitive's base-colour
     * texture while preserving other material properties".
     */
    @Test
    fun aModelTextureOverrideWinsOverTheAuthoredBaseColourImage() {
        val binding = modelCapableBinding()
        val modelPipelines = newModelPipelines(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(
                sceneModel(
                    baseColourImageIndex = 0,
                    imageTextures = listOf(AUTHORED_TEXTURE),
                    overrideTexture = OVERRIDE_TEXTURE,
                    attributes = TEXTURED_ATTRIBUTES,
                ),
            ),
            mapOrder = listOf(ModelAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(binding), newGroundPipeline(binding), modelPipelines)
            .draw(binding)

        assertTrue(
            binding.log.contains("bindTexture(${hex(GL_TEXTURE_2D)},$OVERRIDE_TEXTURE)"),
            "the override must be the texture bound: ${binding.log}",
        )
        assertFalse(
            binding.log.contains("bindTexture(${hex(GL_TEXTURE_2D)},$AUTHORED_TEXTURE)"),
            "the authored image must not also be bound: ${binding.log}",
        )
    }

    /**
     * glTF's own rule: a node whose global transform has a negative determinant winds its triangles
     * the other way, and [drawModels] answers that with `glFrontFace(GL_CW)`. The mirrored fixture
     * negates one axis only — a fixture that negated all three would have a negative determinant and
     * be a rotation composed with a point reflection, which is a different thing to get right.
     */
    @Test
    fun aMirroredNodeTransformReversesTheWindingAndAnUnmirroredOneDoesNot() {
        val mirrored = modelCapableBinding()
        SceneContent(
            topDownCamera(),
            Scene(
                OUTPUT_SIZE,
                0L,
                models = listOf(sceneModel(nodeTransforms = listOf(mirroredNodeTransform()))),
                mapOrder = listOf(ModelAt(0)),
            ),
            newStickerPipeline(mirrored),
            newGroundPipeline(mirrored),
            newModelPipelines(mirrored),
        ).also { mirrored.log.clear() }.draw(mirrored)

        val upright = modelCapableBinding()
        SceneContent(
            topDownCamera(),
            Scene(
                OUTPUT_SIZE,
                0L,
                models = listOf(sceneModel(nodeTransforms = listOf(asymmetricNodeTransform()))),
                mapOrder = listOf(ModelAt(0)),
            ),
            newStickerPipeline(upright),
            newGroundPipeline(upright),
            newModelPipelines(upright),
        ).also { upright.log.clear() }.draw(upright)

        assertTrue(mirrored.log.contains("frontFace(${hex(GL_CW)})"), "a mirrored node winds clockwise")
        assertFalse(upright.log.contains("frontFace(${hex(GL_CW)})"), "an unmirrored node does not")
    }

    /**
     * A draw item at a node the default scene never reaches has no global transform, and drawing it
     * at the origin would put a detached mesh in the middle of the map. It is dropped instead — and
     * dropping it must not drop the model's other primitives with it.
     */
    @Test
    fun aDrawItemAtAnUnreachedNodeIsDroppedRatherThanDrawnAtTheOrigin() {
        val binding = modelCapableBinding()
        val modelPipelines = newModelPipelines(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(
                sceneModel(
                    indexCounts = listOf(OPAQUE_INDEX_COUNT, BLENDED_INDEX_COUNT),
                    nodeIndices = listOf(0, 1),
                    nodeTransforms = listOf(DoubleMatrix4.identity, null),
                ),
            ),
            mapOrder = listOf(ModelAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(binding), newGroundPipeline(binding), modelPipelines)
            .draw(binding)

        val drawn = binding.log.filter { it.startsWith("drawElements") }
        assertEquals(1, drawn.size, "only the reachable node's primitive draws: $drawn")
        assertTrue(drawn.single().startsWith("drawElements(${hex(GL_TRIANGLES)},$OPAQUE_INDEX_COUNT,"))
    }

    /**
     * `CONTEXT.md` calls a zero **Scale** valid, and a zero-scaled model has no volume to draw.
     * [modelNormalMatrix] returns `null` for the singular camera-space matrix that produces, and this
     * pins that the answer is "paint nothing" rather than an identity normal matrix — which would
     * light the model as though it were neither scaled nor rotated, a wrong picture reported as a
     * correct one.
     */
    @Test
    fun aZeroScaledModelDrawsNothingRatherThanTakingAnIdentityNormalMatrix() {
        val binding = modelCapableBinding()
        val modelPipelines = newModelPipelines(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(sceneModel(placement = mapPlacementScaled(0.0))),
            mapOrder = listOf(ModelAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(binding), newGroundPipeline(binding), modelPipelines)
            .draw(binding)

        assertTrue(binding.log.none { it.startsWith("drawElements") }, "nothing may draw: ${binding.log}")
        assertTrue(binding.log.none { it == "depthMask(true)" }, "an empty model pass touches no depth state")
    }

    /**
     * ADR 0029 refuses a `SCREEN`-positioned **Model** at frame planning, so one reaching a draw is a
     * caller contract violation rather than a case to handle. Asserted rather than degraded, because
     * the screen projection carries no z row at all and a mesh drawn through it would show its own
     * back faces through its front ones — a silently wrong picture.
     */
    @Test
    fun aScreenPositionedModelIsRefusedAtDrawTimeRatherThanCompositedFlat() {
        val binding = modelCapableBinding()
        val modelPipelines = newModelPipelines(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(sceneModel(placement = screenPlacement(z = 1.0))),
            mapOrder = listOf(ModelAt(0)),
        )

        assertFailsWith<IllegalArgumentException> {
            SceneContent(
                topDownCamera(),
                scene,
                newStickerPipeline(binding),
                newGroundPipeline(binding),
                modelPipelines,
            ).draw(binding)
        }
    }

    /**
     * The one hazard [SceneContent]'s `modelPipelines` default carries: a caller that supplies models
     * and forgets the pipelines. It fails loudly at the first primitive rather than drawing a frame
     * with the models silently missing, which is what makes the default safe where
     * [SceneGeometry.consumerUniforms]'s absent default would not have been.
     */
    @Test
    fun aSceneWithModelsAndNoPipelinesFailsLoudlyRatherThanDrawingNothing() {
        val binding = modelCapableBinding()
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            models = listOf(sceneModel()),
            mapOrder = listOf(ModelAt(0)),
        )

        assertFailsWith<IllegalArgumentException> {
            SceneContent(topDownCamera(), scene, newStickerPipeline(binding), newGroundPipeline(binding))
                .draw(binding)
        }
    }

    // --- ADR 0025: the ground is first, and map-regime draw order is a contract ------------------

    /**
     * The ground is the backdrop consumer content paints onto, so it draws before every other
     * map-regime thing. Under ADR 0025's `GL_GEQUAL` this is no longer cosmetic: a `Geometry` or a
     * map-anchored sticker at altitude 0 ties with the ground on depth, and the tie is broken by
     * draw order, so drawing the ground second would erase them.
     */
    @Test
    fun theGroundDrawsBeforeEveryOtherMapRegimeThing() {
        val binding = RecordingGlBinding()
        val camera = topDownCamera()
        val geometryPipeline = newGeometryPipeline(binding)
        val stickerPipeline = newStickerPipeline(binding)
        val groundPipeline = newGroundPipeline(binding)
        val groundTexture = 303
        val mapTexture = 101
        val screenTexture = 202

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = mapTexture),
                SceneSticker(screenPlacement(z = 5.0), texture = screenTexture),
            ),
            geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline, consumerUniforms = emptyMap())),
            groundTiles = listOf(groundTile(canonicalX = 8, tileY = 8, texture = groundTexture)),
            mapOrder = listOf(StickerAt(0)),
            screenOrder = listOf(StickerAt(1)),
        )
        binding.log.clear()

        SceneContent(camera, scene, stickerPipeline, groundPipeline).draw(binding)

        val groundBind = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$groundTexture)" }
        val geometryDraw = binding.log.indexOfFirst { it.startsWith("drawElements") }
        val mapStickerBind = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$mapTexture)" }
        val depthDisabled = binding.log.indexOfFirst { it == "disable(${hex(GL_DEPTH_TEST)})" }
        val screenStickerBind = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$screenTexture)" }

        assertTrue(groundBind >= 0, "the ground must actually draw")
        assertTrue(groundBind < geometryDraw, "the ground draws before every geometry")
        assertTrue(groundBind < mapStickerBind, "the ground draws before every map-anchored sticker")
        assertTrue(geometryDraw < mapStickerBind, "geometries keep drawing before map-anchored stickers")
        assertTrue(mapStickerBind < depthDisabled, "the whole map regime is depth-tested")
        assertTrue(depthDisabled < screenStickerBind, "the screen regime still composites last")
    }

    /**
     * ADR 0025's contract, at the only level a call log can state it: within the map regime, later
     * declaration draws later, so under `GL_GEQUAL` the later-declared of two coplanar things wins.
     * `StickerPipeline`'s KDoc used to say map-anchored things draw "in any order"; that sentence is
     * false from ADR 0025 onward, and this test is what stops it becoming true again.
     */
    @Test
    fun coplanarMapAnchoredThingsDrawInDeclarationOrderSoTheLaterOneWins() {
        val binding = RecordingGlBinding()
        val stickerPipeline = newStickerPipeline(binding)
        val firstTexture = 401
        val secondTexture = 402
        val firstGroundTexture = 501
        val secondGroundTexture = 502

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = firstTexture),
                SceneSticker(mapPlacement(), texture = secondTexture),
            ),
            groundTiles = listOf(
                groundTile(canonicalX = 8, tileY = 8, texture = firstGroundTexture),
                groundTile(canonicalX = 9, tileY = 8, texture = secondGroundTexture),
            ),
            mapOrder = listOf(StickerAt(0), StickerAt(1)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, stickerPipeline, newGroundPipeline(binding)).draw(binding)

        val firstGround = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$firstGroundTexture)" }
        val secondGround = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$secondGroundTexture)" }
        val first = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$firstTexture)" }
        val second = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$secondTexture)" }
        assertTrue(firstGround in 0 until secondGround, "ground tiles draw in the order the frame lists them")
        assertTrue(first in 0 until second, "the later-declared map-anchored sticker draws last, so it wins a tie")
    }

    /**
     * The ground's own placement, end to end through [SceneContent]: the uniform it uploads must be
     * exactly the camera's view-projection composed with the tile's own quad, so a placement bug
     * cannot hide behind a matrix this layer recomputes differently.
     */
    @Test
    fun theGroundUniformIsTheCameraViewProjectionComposedWithTheTilesOwnQuad() {
        val binding = RecordingGlBinding().withDeclaredNames(
            GROUND_MODEL_VIEW_PROJECTION_UNIFORM_NAME to 3,
            GROUND_TEXTURE_UNIFORM_NAME to 7,
        )
        val camera = topDownCamera()
        val pipeline = (
            createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as GroundPipelineResult.Created
            ).pipeline
        val tile = groundTile(canonicalX = 9, tileY = 7, texture = 55)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            groundTiles = listOf(tile),
        )
        binding.log.clear()

        SceneContent(camera, scene, newStickerPipeline(binding), pipeline).draw(binding)

        val expected = composeGroundModelViewProjection(camera, resolveBasemapTileQuad(tile.instance, camera))
        assertContentEquals(expected, requireNotNull(binding.uniformMatrix4fvValues[3]))
    }

    /**
     * **One granularity for the whole frame, and this is where the two claims on it meet.**
     *
     * Terrain wants the ground fine enough to follow the DEM; the globe wants it fine enough not to
     * facet the limb. Each is a *floor* on what its own subject needs, so they reconcile by `max` —
     * a `min` would satisfy neither, and preferring one would make its correctness depend on the
     * other's presence. Two tiles subdivided differently leave a sliver of background between them,
     * which is MapLibre's most expensive globe defect and the reason the rule is per frame.
     *
     * The mercator arm asserts terrain's claim reaches the grid at all: before this task
     * `SceneContent` passed nothing and `drawGround` defaulted to a single cell, so a granularity
     * rule that ran perfectly would still have drawn a four-vertex quad.
     */
    @Test
    fun theFrameDrawsItsWholeGroundAtOneGranularityReconcilingCurvatureAndTerrain() {
        val terrain = SceneTerrain(
            decode = demDecodeCoefficients(DemEncoding.MAPBOX),
            interiorSizePx = 256,
            exaggeration = 2.0,
            cellsPerTileSide = 8,
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            groundTiles = listOf(groundTile(canonicalX = 8, tileY = 8, texture = 303)),
            terrain = terrain,
        )

        val mercatorBinding = RecordingGlBinding().withNoDeclaredNames()
        val mercatorPipeline = newGroundPipeline(mercatorBinding)
        SceneContent(topDownCamera(), scene, newStickerPipeline(), mercatorPipeline).draw(mercatorBinding)
        assertEquals(
            setOf(8),
            mercatorPipeline.grids.keys,
            "mercator has no curvature claim, so terrain's is the frame's granularity",
        )

        val globeBinding = RecordingGlBinding().withNoDeclaredNames()
        val globePipeline = newGlobeGroundPipeline(globeBinding)
        val camera = globeCamera()
        SceneContent(
            camera = camera,
            scene = scene,
            stickerPipeline = newStickerPipeline(),
            groundPipeline = newGroundPipeline(),
            globeGroundPipeline = globePipeline,
        ).draw(globeBinding)
        val curvature = globeGroundCellsPerTileSide(camera, scene.groundTiles.first().instance.lod)
        assertEquals(
            setOf(maxOf(curvature, 8)),
            globePipeline.grids.keys,
            "the globe takes the larger of its curvature claim ($curvature) and terrain's (8)",
        )
    }

    /**
     * A terrain frame's per-tile DEM reaches the ground pass, and a frame without one leaves the DEM
     * uniforms alone.
     *
     * The narrowing from `DemTileWindow`'s exact `Double` bounds to the shader's four `Float`s
     * happens in this layer and nowhere else, which is why the *values* are asserted rather than the
     * fact of an upload: a transposed window — `u` and `v` swapped — reads a real DEM at a plausible
     * wrong place, and no assertion about a call having happened can see it.
     *
     * **The tile's own equatorial side is derived in this layer too, and it is the one number a
     * shaded ground turns an elevation difference into a slope with.** It is asserted here because
     * `runGroundShadingReadback` builds its own tiles and would not see a wrong derivation: a
     * mutation halving this survived that whole suite. The fixture's DEM is a *quarter of a quarter*
     * of a source tile two zoom levels coarser, so a build that read the **source** DEM's side
     * instead of the requested tile's — the obvious way to get this wrong — is four times too large
     * and flattens every slope by the same factor.
     */
    @Test
    fun aTerrainFramesPerTileWindowReachesTheGroundPassNarrowedOnce() {
        val binding = RecordingGlBinding().withDeclaredNames(
            GROUND_DEM_WINDOW_UNIFORM_NAME to 30,
            GROUND_DEM_GRID_UNIFORM_NAME to 31,
            GROUND_TILE_SIDE_METRES_UNIFORM_NAME to 32,
        )
        val pipeline = newGroundPipeline(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            groundTiles = listOf(
                SceneGroundTile(
                    instance = BasemapTileInstance(
                        lod = 4, tileY = 8, unwrappedX = 8L, instanceCopy = 0, canonicalX = 8,
                    ),
                    texture = 303,
                    // The south-west quarter of a source tile two zoom levels coarser: no bound is 0
                    // or 1, and no two are equal, so a dropped, doubled or transposed component shows.
                    elevation = SceneTileDem(
                        demTexture = 77,
                        window = DemTileWindow(childScale = 4, childX = 1, childY = 2),
                    ),
                ),
            ),
            terrain = SceneTerrain(
                decode = demDecodeCoefficients(DemEncoding.MAPBOX),
                interiorSizePx = 256,
                exaggeration = 2.0,
                cellsPerTileSide = 4,
            ),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(), pipeline).draw(binding)

        assertTrue(
            binding.log.contains("uniform4f(30,0.25,0.5,0.5,0.75)"),
            "(uWest, uEast, vNorth, vSouth) of the tile's own quarter of its source DEM: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform2f(31,256.0,2.0)"),
            "the source's interior size and the style's exaggeration: ${binding.log}",
        )
        // 2^4 written out rather than derived from the tile's own field, so the exponent is a
        // statement this test makes and not one it copies from the code under test.
        val requestedTileSide = (WORLD_CIRCUMFERENCE_METRES / 16.0).toFloat()
        val sourceTileSide = (WORLD_CIRCUMFERENCE_METRES / 4.0).toFloat()
        assertTrue(
            binding.log.contains("uniform1f(32,$requestedTileSide)"),
            "one grid unit of the **requested** LOD 4 tile is $requestedTileSide equatorial metres, " +
                "not the source DEM's $sourceTileSide: ${binding.log}",
        )
    }

    // --- the precision path: SceneContent must not be the layer that discards it ---------------

    @Test
    fun geometryVertexPositionsSurviveSceneContentBitExactly() {
        // Mirrors GeometryPipelineTest's vertexPositionsMatchTheCameraRelativeResolutionBitExactly,
        // one layer up: SceneContent must convert Geometry plus the resolved camera into the same
        // camera-relative bytes that calling resolveGeometry directly produces.
        val camera = (
            resolveMercatorCamera(
                camera = Camera(
                    latitude = 37.7749,
                    unwrappedLongitude = -122.4194,
                    zoom = 15.0,
                    bearing = 30.0,
                    pitch = 45.0,
                ),
                outputPixelSize = OutputPixelSize(width = 1024, height = 768),
            ) as SpatialOutcome.Success
            ).value
        val geometry = Geometry(
            topLeft = Vector3(37.7752, -122.4198, 12.0),
            bottomRight = Vector3(37.7746, -122.4190, 3.0),
            shaderPair = minimalShaderPair(),
        )
        val expectedTopLeft = (resolveGeometry(geometry, camera) as SpatialOutcome.Success).value
            .cornersClockwiseFromTopLeft[0]

        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0)
        val pipeline = newGeometryPipeline(binding)
        val scene = Scene(
            outputPixelSize = OutputPixelSize(width = 1024, height = 768),
            frameIndex = 0L,
            geometries = listOf(SceneGeometry(geometry, pipeline, consumerUniforms = emptyMap())),
        )

        SceneContent(camera, scene, newStickerPipeline(RecordingGlBinding()), newGroundPipeline()).draw(binding)

        val uploaded = decodeLittleEndianFloats(requireNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER]))
        // The grid's first vertex is its north-west node; stride 5 floats (xyz + uv).
        assertEquals(expectedTopLeft.x.toFloat(), uploaded[0])
        assertEquals(expectedTopLeft.y.toFloat(), uploaded[1])
        assertEquals(expectedTopLeft.z.toFloat(), uploaded[2])
    }

    // --- the MVP composer: the largest piece of unwritten work this task supplies --------------

    @Test
    fun aGeometrysModelViewProjectionIsExactlyTheCamerasViewProjection() {
        // CONTEXT.md: "A Geometry carries no Placement" -- so uModelViewProjection for a geometry
        // has no per-object model term at all, only the camera's own projection * view.
        val camera = topDownCamera()
        val expected = expectedProjectionTimesView(camera)

        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_MODEL_VIEW_PROJECTION to 2)
        val pipeline = newGeometryPipeline(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(SceneGeometry(testGeometry(), pipeline, consumerUniforms = emptyMap())),
        )
        binding.log.clear()

        SceneContent(camera, scene, newStickerPipeline(RecordingGlBinding()), newGroundPipeline()).draw(binding)

        assertContentEquals(expected, binding.uniformMatrix4fvValues.getValue(2))
    }

    // --- the Task 7 / Task 8 seam: a SceneGeometry's consumer data must reach drawGeometry ------
    //
    // Merging Task 8's SceneContent (written against drawGeometry's pre-Task-7 signature) against
    // Task 7's added consumerUniforms/consumerTextures parameters compiles cleanly either way,
    // because both new parameters carry defaults -- so a merge that leaves SceneContent's call
    // site unchanged would build green and pass every pre-existing test while silently dropping
    // every consumer uniform and texture a Geometry declares. These two tests pin the actual wire:
    // a value present on the Geometry/SceneGeometry a caller hands SceneContent must show up in
    // the GL calls SceneContent.draw() issues, not merely compile.

    @Test
    fun aGeometrysConsumerUniformIsBoundWhenSceneContentDraws() {
        val binding = RecordingGlBinding().withDeclaredNames("uTint" to 20)
        val pipeline = newGeometryPipeline(binding)
        val geometryWithUniform = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalShaderPair(),
            uniforms = mapOf("uTint" to ShaderValue.Scalar(0.5f)),
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(SceneGeometry(geometryWithUniform, pipeline, consumerUniforms = geometryWithUniform.uniforms)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(RecordingGlBinding()), newGroundPipeline())
            .draw(binding)

        assertTrue(
            binding.log.contains("uniform1f(20,0.5)"),
            "a Geometry.uniforms entry must reach drawGeometry's consumerUniforms and get bound: " +
                "${binding.log}",
        )
    }

    @Test
    fun aSceneGeometrysConsumerTextureIsBoundWhenSceneContentDraws() {
        // As of Task 9b, SceneGeometry.consumerTextures carries each name's ALREADY-UPLOADED GL
        // texture name (an Int) -- uploading and caching it by ResourceKey through GlObjectRegistry
        // is the job of whoever assembles the SceneGeometry (RenGRenderer), one layer above
        // SceneContent, so this test asserts binding, not upload.
        val binding = RecordingGlBinding().withDeclaredNames("uMask" to 21)
        val pipeline = newGeometryPipeline(binding)
        val geometry = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalShaderPair(),
            textures = mapOf("uMask" to ResourceLocator("https://example.invalid/mask.png")),
        )
        val sceneGeometry = SceneGeometry(
            geometry = geometry,
            pipeline = pipeline,
            consumerUniforms = emptyMap(),
            consumerTextures = mapOf("uMask" to 909),
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(sceneGeometry),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(RecordingGlBinding()), newGroundPipeline())
            .draw(binding)

        assertTrue(
            binding.log.contains("uniform1i(21,0)"),
            "a SceneGeometry.consumerTextures entry must reach drawGeometry's consumerTextures, " +
                "take a texture unit, and bind its sampler: ${binding.log}",
        )
        assertTrue(
            binding.log.any { it == "bindTexture(0xDE1,909)" },
            "the already-uploaded texture name must be the one bound: ${binding.log}",
        )
        assertTrue(binding.log.none { it.startsWith("genTextures") }, "SceneContent must never upload a texture itself")
    }

    @Test
    fun aGeometrysConsumerUniformSnapshotIsUsedNotItsLiveMap() {
        // Pins the item-3 fix: SceneGeometry.consumerUniforms is a separate, explicit field from
        // Geometry.uniforms. A caller (RenGRenderer) that passes a DIFFERENT snapshot than the
        // Geometry's own live map must see ITS snapshot drawn, not the Geometry's.
        val binding = RecordingGlBinding().withDeclaredNames("uTint" to 20)
        val pipeline = newGeometryPipeline(binding)
        val geometry = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalShaderPair(),
            uniforms = mapOf("uTint" to ShaderValue.Scalar(0.5f)),
        )
        val sceneGeometry = SceneGeometry(
            geometry = geometry,
            pipeline = pipeline,
            consumerUniforms = mapOf("uTint" to ShaderValue.Scalar(0.9f)),
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(sceneGeometry),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(RecordingGlBinding()), newGroundPipeline())
            .draw(binding)

        assertTrue(binding.log.contains("uniform1f(20,0.9)"), "the explicit snapshot must be drawn: ${binding.log}")
        assertTrue(!binding.log.contains("uniform1f(20,0.5)"), "the Geometry's own live map must not be read: ${binding.log}")
    }

    @Test
    fun aScreenAnchoredStickerAtTheFramebufferCentreProjectsToTheOrigin() {
        // 2*x/width - 1 and 1 - 2*y/height both land exactly on 0 when the placement sits at the
        // framebuffer's centre pixel with no rotation and unit scale -- an exact, independently
        // derivable check of the orthographic screen composition, not a re-statement of its code.
        val placement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(OUTPUT_SIZE.width / 2.0, OUTPUT_SIZE.height / 2.0, 7.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )
        val camera = topDownCamera()
        val resolved = (resolvePlacement(placement, camera) as SpatialOutcome.Success).value

        val mvp = composeScreenModelViewProjection(OUTPUT_SIZE, resolved)

        // Column 3 (indices 12..15) is M * (0,0,0,1)^T: the local quad's own origin.
        assertEquals(0.0f, mvp[12])
        assertEquals(0.0f, mvp[13])
        assertEquals(0.0f, mvp[14])
        assertEquals(1.0f, mvp[15])
    }

    @Test
    fun mapAnchoredStickersAtDifferentPositionsShareTheSameRotationScaleBlock() {
        // With SCREEN rotation/scale anchoring, directionTransform and logicalScale never depend on
        // position, so only the translation column of the composed MVP may differ between two
        // otherwise-identical map-anchored stickers placed a world apart.
        val camera = topDownCamera()
        val near = mapPlacementAt(Vector3(0.0, 0.0, 0.0))
        val far = mapPlacementAt(Vector3(10.0, 10.0, 0.0))
        val resolvedNear = (resolvePlacement(near, camera) as SpatialOutcome.Success).value
        val resolvedFar = (resolvePlacement(far, camera) as SpatialOutcome.Success).value

        val mvpNear = composeMapModelViewProjection(camera, resolvedNear)
        val mvpFar = composeMapModelViewProjection(camera, resolvedFar)

        // Columns 0 and 1 (indices 0..7) are the rotation-and-scale block; column 3 (12..15) is
        // translation and is expected to differ.
        assertContentEquals(mvpNear.copyOfRange(0, 8), mvpFar.copyOfRange(0, 8))
        assertTrue(
            mvpNear.copyOfRange(12, 16).toList() != mvpFar.copyOfRange(12, 16).toList(),
            "different world positions must produce different translations",
        )
    }

    // --- Task 9b item 2: a sticker's quad is sized from its image's own pixel dimensions -------

    @Test
    fun aStickersImageDimensionsScaleItsRotationAndScaleBlockRelativeToAUnitQuad() {
        // CONTEXT.md: a Sticker draws "as a centred local XY quad whose width and height are the
        // image's pixel dimensions." Before this fix STICKER_QUAD was a fixed unit square with
        // nothing scaling it by the image's own size, so at scale 1.0 a sticker rendered one pixel
        // across instead of its image's size. This pins that SceneContent now threads
        // SceneSticker.imageWidthPixels/imageHeightPixels into the composed MVP as a local pre-scale.
        //
        // The MVP uniform location must be declared -- RecordingGlBinding.getUniformLocation
        // returns -1 for anything undeclared, and drawOneSticker's `>= 0` guard would then skip the
        // uniformMatrix4fv call entirely, letting this test pass while asserting nothing.
        val mvpLocation = 3
        val binding = RecordingGlBinding().withDeclaredNames(
            STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to mvpLocation,
            STICKER_TEXTURE_UNIFORM_NAME to 7,
        )
        val stickerPipeline = newStickerPipeline(binding)
        val placement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(0.0, 0.0, 0.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(SceneSticker(placement, texture = 1, imageWidthPixels = 4, imageHeightPixels = 2)),
            screenOrder = listOf(StickerAt(0)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, stickerPipeline, newGroundPipeline()).draw(binding)

        val resolved = (resolvePlacement(placement, topDownCamera()) as SpatialOutcome.Success).value
        val expected = composeScreenModelViewProjection(OUTPUT_SIZE, resolved, DoubleVector3(4.0, 2.0, 1.0))
        val unitQuad = composeScreenModelViewProjection(OUTPUT_SIZE, resolved)

        val actual = requireNotNull(binding.uniformMatrix4fvValues[mvpLocation]) {
            "the sticker's MVP must have been bound"
        }
        assertContentEquals(expected, actual)
        assertTrue(actual.toList() != unitQuad.toList(), "a non-1x1 image must not draw as a unit quad")
    }

    // --- Task 9b item 5: draw-time resolution failure is a typed RenGException, not error(...) --

    @Test
    fun requireResolvedAtDrawTimeConvertsAFailureToATypedRedactedRenGException() {
        // The wrapped failure deliberately carries a DIFFERENT code and stage (GPU_RESOURCE /
        // GPU_OPERATION_FAILED, not DRAW / INVALID_VALUE) than the exception this must throw,
        // proving requireResolvedAtDrawTime reports its own fixed, redacted draw-time failure
        // rather than merely rethrowing whatever it was handed. INVALID_VALUE, not
        // GPU_OPERATION_FAILED, is the correct code: resolvePlacement/resolveGeometry/
        // resolveMercatorCamera all report their OWN internal failures as INVALID_VALUE at
        // FRAME_PLANNING, and GPU_OPERATION_FAILED is GlErrorQueue's wrapper for a genuine
        // glGetError() result, which would misdirect a consumer at their GL state when the actual
        // fault is in their FramePlan.
        val wrapped = SpatialOutcome.Failure(glOperationFailure(PipelineStage.GPU_RESOURCE, resourceKey = null))

        val failure = assertFailsWith<RenGException> { wrapped.requireResolvedAtDrawTime() }

        assertEquals(RenGErrorCode.INVALID_VALUE, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        val rendered = failure.toString() + failure.message.orEmpty()
        assertTrue(!rendered.contains("Mesa", ignoreCase = true))
        assertTrue(!rendered.contains("GL_", ignoreCase = false))
    }


    /**
     * One whole frame with content in every phase ADR 0034 orders: a ground tile, a `Geometry`, a
     * model, a **map-anchored** sticker, a label batch and a **screen-anchored** sticker. Both
     * stickers are mandatory rather than incidental — see
     * [labelsDrawAfterEveryMapRegimeDrawAndBeforeTheScreenRegimesFirstDraw] for why one of each is
     * the minimum that can tell phase 5 from phase 4 or phase 6.
     */
    private fun labelledFrame(): LabelledFrame {
        val binding = modelCapableBinding()
        val geometryPipeline = newGeometryPipeline(binding)
        val stickerPipeline = newStickerPipeline(binding)
        val groundPipeline = newGroundPipeline(binding)
        val modelPipelines = newModelPipelines(binding)
        val labelPipeline = newLabelPipeline(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = MAP_STICKER_TEXTURE),
                SceneSticker(screenPlacement(z = 5.0), texture = SCREEN_STICKER_TEXTURE),
            ),
            geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline, consumerUniforms = emptyMap())),
            groundTiles = listOf(groundTile(canonicalX = 8, tileY = 8, texture = GROUND_TEXTURE)),
            models = listOf(sceneModel()),
            labels = listOf(labelBatch()),
            mapOrder = listOf(StickerAt(0), ModelAt(0)),
            screenOrder = listOf(StickerAt(1)),
        )
        binding.log.clear()
        return LabelledFrame(
            binding = binding,
            ground = groundPipeline,
            geometry = geometryPipeline,
            content = SceneContent(
                topDownCamera(),
                scene,
                stickerPipeline,
                groundPipeline,
                modelPipelines,
                labelPipeline,
            ),
        )
    }

    private fun newLabelPipeline(binding: RecordingGlBinding): LabelPipeline =
        (createLabelPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as LabelPipelineResult.Created).pipeline

    /**
     * [LABEL_QUADS] hand-built glyph quads — placement is task 9's and nothing here computes one
     * from a candidate. More than one quad on purpose: a single-quad batch cannot tell one draw for
     * many glyphs from one draw per glyph, which is the property the label pipeline exists for.
     */
    private fun labelBatch(atlasTexture: Int = LABEL_ATLAS_TEXTURE): LabelBatch = LabelBatch(
        atlasTexture = atlasTexture,
        quads = (0 until LABEL_QUADS).map { index ->
            val left = 10.0f * index
            ResolvedGlyphQuad(
                cornersXy = floatArrayOf(left, 0.0f, left + 8.0f, 0.0f, left + 8.0f, 12.0f, left, 12.0f),
                cornersUv = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f),
                paint = ResolvedLabelPaint(
                    textColour = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f),
                    haloColour = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
                    haloWidthPixels = 1.0f,
                ),
            )
        },
    )

    private fun mapPlacementAt(position: Vector3): Placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = position,
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun mapPlacement(): Placement = mapPlacementAt(Vector3(0.0, 0.0, 0.0))

    private fun mapPlacementScaled(scale: Double): Placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(0.0, 0.0, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = scale,
    )

    private fun screenPlacement(z: Double): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(400.0, 300.0, z),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    // --- ADR 0038: the ground owns its cull state, and the geometry pass never inherits it -------

    /**
     * **The seam, at the one place it can be got wrong silently.** There are two ground entry points
     * — `drawGround` and `drawGlobeGround` — and the frame's camera is what chooses between them.
     * Routing a globe frame to the first would draw a tangent plane, which at anything above about
     * zoom 12 looks exactly like a globe.
     *
     * **The draw call stopped discriminating with Cycle E-terrain and the program now carries the
     * whole assertion.** Until this cycle the mercator ground drew a flat quad with `glDrawArrays`
     * and only the globe drew an indexed grid, so the two entry points were distinguishable by their
     * draw call alone. Both are subdivided grids now and both issue a byte-identical
     * `drawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0)` at one cell a side, so what is left is
     * the **program** bound before it — which the fixture asserts the two pipelines do not share, or
     * neither could be located. The draw call is still required to appear, because a pass that drew
     * nothing would otherwise satisfy a program-only check.
     */
    @Test
    fun theFramesCameraChoosesWhichGroundEntryPointDrawsIt() {
        val binding = RecordingGlBinding()
        val groundPipeline = newGroundPipeline(binding)
        val globePipeline = newGlobeGroundPipeline(binding)
        assertTrue(
            groundPipeline.program != globePipeline.program,
            "the fixture must give the two ground pipelines distinct programs or neither can be located",
        )
        listOf(
            Triple(topDownCamera(), groundPipeline.program, "drawElements"),
            Triple(globeCamera(), globePipeline.program, "drawElements"),
        ).forEach { (camera, expectedProgram, expectedDraw) ->
            val scene = Scene(
                outputPixelSize = OUTPUT_SIZE,
                frameIndex = 0L,
                groundTiles = listOf(groundTile(canonicalX = 8, tileY = 8, texture = 303)),
            )
            binding.log.clear()
            SceneContent(
                camera = camera,
                scene = scene,
                stickerPipeline = newStickerPipeline(),
                groundPipeline = groundPipeline,
                globeGroundPipeline = globePipeline,
            ).draw(binding)
            val draw = binding.log.indexOfFirst { it.startsWith(expectedDraw) }
            assertTrue(draw >= 0, "${camera::class.simpleName} must draw the ground with $expectedDraw: ${binding.log}")
            val program = binding.log.subList(0, draw).indexOfLast { it == "useProgram($expectedProgram)" }
            assertTrue(
                program >= 0,
                "${camera::class.simpleName} must bind program $expectedProgram before drawing: ${binding.log}",
            )
        }
    }

    /**
     * ADR 0038's cull state, at whichever entry point the camera named. The mercator ground disables
     * and the globe ground enables, and asserting only one of the two would pass with the other's
     * line deleted.
     */
    @Test
    fun theGroundPassEstablishesTheCullStateItsProjectionRequires() {
        val binding = RecordingGlBinding()
        val groundPipeline = newGroundPipeline(binding)
        val globePipeline = newGlobeGroundPipeline(binding)
        listOf(
            Triple(topDownCamera(), "disable(${hex(GL_CULL_FACE)})", "drawElements"),
            Triple(globeCamera(), "enable(${hex(GL_CULL_FACE)})", "drawElements"),
        ).forEach { (camera, expected, drawCall) ->
            val scene = Scene(
                outputPixelSize = OUTPUT_SIZE,
                frameIndex = 0L,
                groundTiles = listOf(groundTile(canonicalX = 8, tileY = 8, texture = 303)),
            )
            binding.log.clear()
            SceneContent(
                camera = camera,
                scene = scene,
                stickerPipeline = newStickerPipeline(),
                groundPipeline = groundPipeline,
                globeGroundPipeline = globePipeline,
            ).draw(binding)
            val call = binding.log.indexOfFirst { it == expected }
            val firstDraw = binding.log.indexOfFirst { it.startsWith(drawCall) }
            assertTrue(firstDraw >= 0, "${camera::class.simpleName} must draw the ground")
            assertTrue(
                call in 0 until firstDraw,
                "${camera::class.simpleName} must issue $expected before drawing: ${binding.log}",
            )
        }
    }

    /**
     * ADR 0038's one stated boundary. The globe ground leaves `GL_CULL_FACE` enabled behind it and the
     * geometry pass runs a consumer's own shader pair, whose triangle winding is the consumer's to
     * decide — a shader that negates a coordinate inverts the front face, and an inherited enable
     * would then delete a legal `Geometry` silently and in one projection mode only.
     *
     * Asserted in **both** modes on purpose: the reason is a property of consumer shaders rather than
     * of the globe, so the disable is unconditional, and under mercator this case is what stops the
     * geometry pass from acquiring a mode-dependent state it never had.
     */
    @Test
    fun theGeometryPassDisablesCullingRatherThanInheritingTheGlobeGroundsEnable() {
        listOf(topDownCamera(), globeCamera()).forEach { camera ->
            val binding = RecordingGlBinding()
            val groundPipeline = newGroundPipeline(binding)
            val globePipeline = newGlobeGroundPipeline(binding)
            val geometryPipeline = newGeometryPipeline(binding)
            assertTrue(
                groundPipeline.program != geometryPipeline.program,
                "the fixture must give the two passes distinct programs or this case cannot locate either",
            )
            val scene = Scene(
                outputPixelSize = OUTPUT_SIZE,
                frameIndex = 0L,
                groundTiles = listOf(groundTile(canonicalX = 8, tileY = 8, texture = 303)),
                geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline, consumerUniforms = emptyMap())),
            )
            binding.log.clear()
            SceneContent(
                camera = camera,
                scene = scene,
                stickerPipeline = newStickerPipeline(),
                groundPipeline = groundPipeline,
                globeGroundPipeline = globePipeline,
            ).draw(binding)

            val label = camera::class.simpleName
            val geometryProgram = binding.log.indexOfFirst { it == "useProgram(${geometryPipeline.program})" }
            assertTrue(geometryProgram >= 0, "$label must bind the geometry program: ${binding.log}")
            val disabled = binding.log.subList(0, geometryProgram).indexOfLast { it == "disable(${hex(GL_CULL_FACE)})" }
            assertTrue(
                disabled >= 0,
                "$label must disable culling before the geometry pass binds its program: ${binding.log}",
            )
            if (camera is ResolvedGlobeCamera) {
                val groundEnable = binding.log.indexOfFirst { it == "enable(${hex(GL_CULL_FACE)})" }
                assertTrue(groundEnable >= 0, "the globe ground must have enabled culling first")
                assertTrue(
                    disabled > groundEnable,
                    "the geometry pass's disable must come after the ground's enable, or it undoes nothing",
                )
            }
        }
    }

    private fun testGeometry(): Geometry = Geometry(
        topLeft = Vector3(1.0, -1.0, 10.0),
        bottomRight = Vector3(-1.0, 1.0, 0.0),
        shaderPair = minimalShaderPair(),
    )

    private fun topDownCamera(): ResolvedMercatorCamera = (
        resolveMercatorCamera(
            camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 10.0, bearing = 0.0, pitch = 0.0),
            outputPixelSize = OUTPUT_SIZE,
        ) as SpatialOutcome.Success
        ).value

    /** [topDownCamera]'s globe counterpart, at the same five [Camera] fields. */
    private fun globeCamera(): ResolvedGlobeCamera = (
        resolveGlobeCamera(
            camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 10.0, bearing = 0.0, pitch = 0.0),
            outputPixelSize = OUTPUT_SIZE,
        ) as SpatialOutcome.Success
        ).value

    private fun newGeometryPipeline(binding: RecordingGlBinding = RecordingGlBinding().withNoDeclaredNames()): GeometryPipeline =
        (
            createGeometryPipeline(binding, ShaderDialect.GLES, GlProgramCache(), minimalShaderPair())
                as GeometryPipelineResult.Created
            ).pipeline

    private fun newStickerPipeline(binding: RecordingGlBinding = RecordingGlBinding().withNoDeclaredNames()): StickerPipeline =
        (createStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as StickerPipelineResult.Created).pipeline

    private fun newGroundPipeline(binding: RecordingGlBinding = RecordingGlBinding().withNoDeclaredNames()): GroundPipeline =
        (createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as GroundPipelineResult.Created).pipeline

    private fun newGlobeGroundPipeline(
        binding: RecordingGlBinding = RecordingGlBinding().withNoDeclaredNames(),
    ): GlobeGroundPipeline = (
        createGlobeGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as GlobeGroundPipelineResult.Created
        ).pipeline

    /**
     * A [RecordingGlBinding] a model pipeline can actually be built on. [createModelPipeline] reads
     * `GL_MAX_UNIFORM_BLOCK_SIZE` first and refuses anything below 16384, and the fake answers an
     * unseeded integer query with `0` — so without this seed every model pipeline fails, and the
     * failure looks like a pipeline bug rather than a fixture one.
     */
    private fun modelCapableBinding(): RecordingGlBinding = RecordingGlBinding()
        .withDeclaredNames(
            MODEL_VIEW_PROJECTION_UNIFORM_NAME to MVP_LOCATION,
            MODEL_NORMAL_MATRIX_UNIFORM_NAME to NORMAL_LOCATION,
            MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME to 32,
            MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME to 33,
            MODEL_LIGHT_DIRECTION_UNIFORM_NAME to 34,
            MODEL_AMBIENT_UNIFORM_NAME to 35,
            MODEL_FORCE_OPAQUE_UNIFORM_NAME to 36,
            MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME to 37,
        )
        .also { it.integers[GL_MAX_UNIFORM_BLOCK_SIZE] = intArrayOf(16384) }

    private fun newModelPipelines(binding: RecordingGlBinding): Map<ModelShaderVariant, ModelPipeline> {
        val cache = GlProgramCache()
        return allModelShaderVariants().associateWith { variant ->
            (createModelPipeline(binding, ShaderDialect.GLES, cache, variant) as ModelPipelineResult.Created).pipeline
        }
    }

    /**
     * One [SceneModel] over a single mesh whose primitives are identified in the call log by their
     * own index counts. Everything a real [SceneModel] arrives with already derived — node
     * transforms, joint palettes, uploaded geometry, texture names — is a parameter here, because
     * that is exactly the shape [SceneContent] consumes it in.
     */
    private fun sceneModel(
        placement: Placement = mapPlacement(),
        indexCounts: List<Int> = listOf(OPAQUE_INDEX_COUNT),
        alphaModes: List<String> = indexCounts.map { "OPAQUE" },
        nodeIndices: List<Int> = indexCounts.map { 0 },
        nodeTransforms: List<DoubleMatrix4?> = listOf(DoubleMatrix4.identity),
        baseColourImageIndex: Int? = null,
        imageTextures: List<Int> = emptyList(),
        overrideTexture: Int? = null,
        attributes: Set<ModelVertexAttribute> = UNTEXTURED_ATTRIBUTES,
    ): SceneModel {
        val decoded = DecodedModel(
            primitives = indexCounts.indices.map { index ->
                DecodedPrimitive(
                    positions = FloatArray(9) { it.toFloat() },
                    normals = FloatArray(9) { 0.0f },
                    texCoords = null,
                    colours = null,
                    joints = null,
                    weights = null,
                    indices = ModelIndices(shortArrayOf(0, 1, 2), null, GL_UNSIGNED_SHORT, 3),
                    material = modelMaterial(alphaModes[index], baseColourImageIndex),
                )
            },
            drawItems = indexCounts.indices.map { ModelDrawItem(nodeIndices[it], 0, it, null) },
            images = emptyList(),
            skins = emptyList(),
            document = GltfDocument(
                accessors = emptyList(),
                bufferViews = emptyList(),
                meshes = listOf(GltfMesh(indexCounts.map { gltfPrimitive() })),
                nodes = nodeTransforms.map { gltfNode() },
                skins = emptyList(),
                scenes = listOf(GltfScene(listOf(0))),
                defaultScene = 0,
                animations = emptyList(),
                materials = emptyList(),
                images = emptyList(),
                textures = emptyList(),
                samplers = emptyList(),
                extensionsRequired = emptyList(),
                buffers = emptyList(),
            ),
            bin = BinChunk(ByteArray(0), IntRange.EMPTY),
            decodedCpuBytes = 0L,
        )
        return SceneModel(
            placement = placement,
            model = decoded,
            nodeTransforms = nodeTransforms,
            jointMatricesBySkin = emptyMap(),
            uploaded = indexCounts.indices.associate { index ->
                (0 to index) to UploadedPrimitive(
                    vertexArray = 900 + index,
                    buffers = listOf(910 + index),
                    indexBuffer = 920 + index,
                    indexCount = indexCounts[index],
                    indexType = GL_UNSIGNED_SHORT,
                    attributes = attributes,
                )
            },
            imageTextures = imageTextures,
            overrideTexture = overrideTexture,
        )
    }

    private fun modelMaterial(alphaMode: String, baseColourImageIndex: Int?): ResolvedMaterial = ResolvedMaterial(
        baseColourFactor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f),
        baseColourImageIndex = baseColourImageIndex,
        baseColourSampler = TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT),
        alphaMode = alphaMode,
        alphaCutoff = 0.5f,
        doubleSided = true,
    )

    private fun gltfPrimitive(): GltfPrimitive =
        GltfPrimitive(attributes = emptyMap(), indices = null, mode = 4, material = null, targetCount = 0)

    private fun gltfNode(): GltfNode = GltfNode(
        children = emptyList(),
        mesh = 0,
        skin = null,
        camera = null,
        matrix = null,
        translation = null,
        rotation = null,
        scale = null,
    )

    /**
     * A node transform that sits at no symmetry point of anything being checked through it: the
     * linear block is non-symmetric (so a stray transpose shows), non-uniformly scaled by three
     * distinct factors (so a dropped inverse-transpose shows), and its translation components are
     * three distinct non-zero values (so a dropped or reordered column shows). Its determinant is
     * `+30.125`, comfortably non-singular and positive.
     */
    private fun asymmetricNodeTransform(): DoubleMatrix4 = DoubleMatrix4.fromRows(
        listOf(
            listOf(2.0, 0.7, -0.3, 4.0),
            listOf(0.0, 3.0, 0.5, -6.0),
            listOf(0.1, 0.0, 5.0, 9.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )

    /** [asymmetricNodeTransform] with its x axis alone negated: determinant `-30.125`. */
    private fun mirroredNodeTransform(): DoubleMatrix4 = DoubleMatrix4.fromRows(
        listOf(
            listOf(-2.0, 0.7, -0.3, 4.0),
            listOf(0.0, 3.0, 0.5, -6.0),
            listOf(-0.1, 0.0, 5.0, 9.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )

    private fun columnMajor(matrix: DoubleMatrix4): FloatArray =
        FloatArray(16) { index -> matrix[index % 4, index / 4].toFloat() }

    /** [groundTile] with a DEM, which is what makes its frame a displaced one (ADR 0039). */
    private fun displacedGroundTile(
        canonicalX: Int,
        tileY: Int,
        texture: Int,
        demTexture: Int,
    ): SceneGroundTile = SceneGroundTile(
        instance = BasemapTileInstance(
            lod = 4,
            tileY = tileY,
            unwrappedX = canonicalX.toLong(),
            instanceCopy = 0,
            canonicalX = canonicalX,
        ),
        texture = texture,
        elevation = SceneTileDem(
            demTexture = demTexture,
            window = DemTileWindow(childScale = 1, childX = 0, childY = 0),
        ),
    )

    /**
     * A frame's terrain, at an exaggeration that is **not 1**: at 1 an honoured multiplier and a
     * dropped one are the same picture, and all six corpus styles declare 1.
     */
    private fun fixtureTerrain(): SceneTerrain = SceneTerrain(
        decode = demDecodeCoefficients(DemEncoding.MAPBOX),
        interiorSizePx = 256,
        exaggeration = 2.0,
        cellsPerTileSide = 4,
    )

    private fun groundTile(canonicalX: Int, tileY: Int, texture: Int): SceneGroundTile = SceneGroundTile(
        instance = BasemapTileInstance(
            lod = 4,
            tileY = tileY,
            unwrappedX = canonicalX.toLong(),
            instanceCopy = 0,
            canonicalX = canonicalX,
        ),
        texture = texture,
    )

    private fun expectedProjectionTimesView(camera: ResolvedMercatorCamera): FloatArray {
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += camera.projectionMatrix[row, k] * camera.viewMatrix[k, column]
                }
                result[column * 4 + row] = sum.toFloat()
            }
        }
        return result
    }

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

    private fun decodeLittleEndianFloats(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0) { "byte payload must hold whole floats" }
        return FloatArray(bytes.size / Float.SIZE_BYTES) { index ->
            val offset = index * Float.SIZE_BYTES
            val bits = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
            Float.fromBits(bits)
        }
    }
}

private val OUTPUT_SIZE = OutputPixelSize(width = 800, height = 600)

/** Index counts, used as per-primitive identities in `drawElements` log lines. */
private const val OPAQUE_INDEX_COUNT: Int = 11
private const val BLENDED_INDEX_COUNT: Int = 22

private const val MVP_LOCATION: Int = 30
private const val NORMAL_LOCATION: Int = 31

private const val AUTHORED_TEXTURE: Int = 707
private const val OVERRIDE_TEXTURE: Int = 808

private val UNTEXTURED_ATTRIBUTES: Set<ModelVertexAttribute> =
    setOf(ModelVertexAttribute.POSITION, ModelVertexAttribute.NORMAL)

/**
 * `modelShaderVariantFor` picks a textured variant only when the primitive carries a `TEXCOORD_0` to
 * sample with, so a fixture asserting on a bound base-colour texture has to declare one — without it
 * the untextured variant draws, binds nothing, and the assertion has nothing to find.
 */
private val TEXTURED_ATTRIBUTES: Set<ModelVertexAttribute> = UNTEXTURED_ATTRIBUTES + ModelVertexAttribute.TEX_COORD

/**
 * The pipelines and the assembled content of one [SceneContentTest.labelledFrame], kept together so
 * both ADR 0034 tests read the same log against the same pass identities.
 */
private class LabelledFrame(
    val binding: RecordingGlBinding,
    val ground: GroundPipeline,
    val geometry: GeometryPipeline,
    val content: SceneContent,
)

/** The index of the first draw call at or after [index], or `-1` when there is none. */
private fun List<String>.firstDrawAfter(index: Int): Int {
    if (index < 0) return -1
    val at = subList(index, size).indexOfFirst { it.startsWith("drawArrays") || it.startsWith("drawElements") }
    return if (at < 0) -1 else index + at
}

private const val MAP_STICKER_TEXTURE: Int = 101
private const val SCREEN_STICKER_TEXTURE: Int = 202
private const val GROUND_TEXTURE: Int = 303
private const val LABEL_ATLAS_TEXTURE: Int = 909
private const val SECOND_LABEL_ATLAS_TEXTURE: Int = 910
private const val LABEL_QUADS: Int = 3

/**
 * The exact `glDrawElements` the label pass issues for [LABEL_QUADS] quads. Its `GL_UNSIGNED_INT`
 * index type is what distinguishes it from the model pass's draw, which the fixtures here index
 * with `GL_UNSIGNED_SHORT`.
 */
private val LABEL_DRAW_CALL: String = "drawElements(" +
    "0x${GL_TRIANGLES.toString(16).uppercase()}," +
    "${LABEL_QUADS * LABEL_INDICES_PER_QUAD}," +
    "0x${GL_UNSIGNED_INT.toString(16).uppercase()},0)"

private fun minimalShaderPair(): ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n",
    fragmentSource = "#version 300 es\nprecision highp float;\nout vec4 rengOut;\nvoid main() {\n    rengOut = vec4(1.0);\n}\n",
)
