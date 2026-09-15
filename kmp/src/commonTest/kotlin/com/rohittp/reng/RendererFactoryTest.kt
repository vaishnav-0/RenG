package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_MAX_COLOR_ATTACHMENTS
import com.rohittp.reng.internal.gl.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
import com.rohittp.reng.internal.gl.GL_MAX_TEXTURE_SIZE
import com.rohittp.reng.internal.gl.GL_MAX_UNIFORM_BLOCK_SIZE
import com.rohittp.reng.internal.gl.GL_NUM_EXTENSIONS
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_SHADING_LANGUAGE_VERSION
import com.rohittp.reng.internal.gl.GL_VENDOR
import com.rohittp.reng.internal.gl.GL_VERSION
import com.rohittp.reng.internal.gl.MODEL_ALPHA_CUTOFF_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_AMBIENT_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_FORCE_OPAQUE_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_JOINT_BLOCK_NAME
import com.rohittp.reng.internal.gl.MODEL_LIGHT_DIRECTION_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_NORMAL_MATRIX_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME
import com.rohittp.reng.internal.gl.MODEL_VIEW_PROJECTION_UNIFORM_NAME
import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME
import com.rohittp.reng.internal.gl.STICKER_TEXTURE_UNIFORM_NAME
import com.rohittp.reng.internal.gl.composeScreenModelViewProjection
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolvePlacement
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RendererFactoryTest {

    // ---- Setup failures -------------------------------------------------------------------------

    @Test
    fun setupWithoutACurrentContextIsATypedFailure() {
        val failure = assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), validGlesBinding(), RenderContextProbe { null })
        }
        assertEquals(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, failure.code)
    }

    @Test
    fun aSetupFailureWithNoCurrentContextCarriesNoDriverTextAtAll() {
        val binding = validGlesBinding()
        val failure = assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), binding, RenderContextProbe { null })
        }
        assertNoDriverText(failure)
    }

    /**
     * The stronger redaction proof: a failure reached AFTER a context was genuinely adopted, so
     * [com.rohittp.reng.internal.gl.RenderContextProfile] is populated with real vendor/renderer
     * strings and the shader compiler observed a real (fake, but driver-shaped) info log — exactly
     * the situation the design spec calls out: "a factory is where a developer most wants to echo
     * the driver string back for debugging." Setting [RecordingGlBinding.compileStatus] to failure
     * fails both of RenG's own internal pipelines' shader compilation, well after `GL_VENDOR` /
     * `GL_RENDERER` / `GL_SHADING_LANGUAGE_VERSION` were read into the adopted profile.
     */
    @Test
    fun aShaderCompileFailureDuringSetupCarriesNoDriverTextAtAll() {
        val binding = validGlesBinding().apply {
            compileStatus = 0
            shaderInfoLog = "ERROR: 0:1: Mesa/llvmpipe internal compiler diagnostic XK-9182"
        }
        val failure = assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), binding, fixedProbe())
        }
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertNoDriverText(failure)
    }

    private fun assertNoDriverText(failure: RenGException) {
        val rendered = failure.toString() + failure.message.orEmpty() +
            failure.diagnostics.joinToString { it.toString() }
        assertFalse(rendered.contains("Mesa", ignoreCase = true))
        assertFalse(rendered.contains("llvmpipe", ignoreCase = true))
        assertFalse(rendered.contains("Apple", ignoreCase = true))
        assertFalse(rendered.contains("XK-9182"))
        assertFalse(rendered.contains("GL_", ignoreCase = false))
    }

    // ---- Terrain shading reaches the compiler ------------------------------------------------------

    /**
     * **`RendererConfiguration.terrainShading` has to reach the program the ground is drawn with, and
     * the value of this test is that nothing else in the codebase would notice if it did not.**
     *
     * The option is spent in exactly one place — which of two displacing sources
     * `createGroundPipeline` compiles at setup — and everything downstream of that is a uniform
     * location that came back negative. So a flag that arrived at `RendererConfiguration` and was
     * never passed on would produce a renderer that accepted the option, reported it back, drew an
     * unshaded ground, and failed no unit test at all. That is the shape E-labels found twice: a
     * path fully built and wired to nothing.
     *
     * The instrument is the compiled shader text itself, because setup is the only moment the choice
     * is made and the recording binding is where it lands.
     */
    @Test
    fun theTerrainShadingOptionReachesTheProgramSetupCompiles() {
        listOf(false, true).forEach { shading ->
            val binding = validGlesBinding()
            createRenderer(testConfiguration(terrainShading = shading), binding, fixedProbe())
            assertEquals(
                shading,
                binding.shaderSources.values.any { it.contains("rengGroundEnuNormal") },
                "a renderer configured with terrainShading=$shading compiled the wrong ground " +
                    "program: ${binding.shaderSources.values.count()} shaders were compiled",
            )
        }
    }

    // ---- Purity: no consumer exchange at setup ---------------------------------------------------

    @Test
    fun setupPerformsNoConsumerExchangeAtAll() {
        val transport = CountingTransport()
        val configuration = testConfiguration(
            transport = transport,
            basemapStyle = ResourceLocator("https://example.invalid/style.json"),
        )
        createRenderer(configuration, validGlesBinding(), fixedProbe())
        assertEquals(0, transport.executeCalls, "the style locator is recorded at setup and acquired at first prepare()")
    }

    @Test
    fun aPrepareCallWithNoStickersAndNoConfiguredBasemapPerformsNoConsumerExchangeEither() = runTest {
        val transport = CountingTransport()
        val renderer = createRenderer(testConfiguration(transport = transport), validGlesBinding(), fixedProbe())
        renderer.prepare(FramePlan(frameIndex = 0L, camera = testCamera()))
        assertEquals(0, transport.executeCalls)
    }

    // ---- drawBasemap warn-and-degrade -------------------------------------------------------------

    @Test
    fun requestingABasemapWithNoConfiguredStyleWarnsOnceAndKeepsDrawing() = runTest {
        val sink = RecordingDiagnosticSink()
        val renderer = createRenderer(
            testConfiguration(basemapStyle = null, diagnosticSink = sink),
            validGlesBinding(),
            fixedProbe(),
        )
        val frame = renderer.prepare(FramePlan(frameIndex = 0L, camera = testCamera(), drawBasemap = true))
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        repeat(3) { renderer.draw(frame, target) }

        assertEquals(
            1,
            sink.diagnostics.count { it.code == DiagnosticCode.BASEMAP_NOT_CONFIGURED },
            "warn once per renderer, never once per frame",
        )
        assertTrue(sink.diagnostics.all { it.severity == DiagnosticSeverity.WARNING })
    }

    @Test
    fun drawBasemapFalseNeverWarnsEvenWithNoConfiguredStyle() = runTest {
        val sink = RecordingDiagnosticSink()
        val renderer = createRenderer(testConfiguration(diagnosticSink = sink), validGlesBinding(), fixedProbe())
        val frame = renderer.prepare(FramePlan(frameIndex = 0L, camera = testCamera(), drawBasemap = false))
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        renderer.draw(frame, target)
        assertTrue(sink.diagnostics.isEmpty())
    }

    // ---- Leak discipline on partial construction ---------------------------------------------------

    /**
     * Forces both internal pipelines' shader compilation to fail (see
     * [aShaderCompileFailureDuringSetupCarriesNoDriverTextAtAll]) while the offscreen surface, which
     * compiles no shader, succeeds — proving the succeeded allocation is deleted rather than leaked
     * when a later allocation in the same construction fails.
     */
    @Test
    fun aFailedSetupDeletesEveryAllocationThatDidSucceed() {
        val binding = validGlesBinding().apply { compileStatus = 0 }
        assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), binding, fixedProbe())
        }
        assertTrue(binding.log.any { it.startsWith("deleteFramebuffers") }, "the offscreen surface's framebuffer must be deleted")
        assertTrue(binding.log.any { it.startsWith("deleteRenderbuffers") }, "the offscreen surface's renderbuffer must be deleted")
        assertTrue(binding.log.any { it.startsWith("deleteTextures") }, "the offscreen surface's colour texture must be deleted")
    }

    @Test
    fun aSuccessfulSetupDeletesNothingExceptTheOrdinaryPostLinkShaderCleanup() {
        val binding = validGlesBinding()
        createRenderer(testConfiguration(), binding, fixedProbe())
        // deleteShader after a successful link is ordinary GL housekeeping (the linked program keeps
        // its own copy of the compiled stages) -- every OTHER delete call would mean a successful
        // setup destroyed one of its own live allocations.
        assertTrue(
            binding.log.none {
                it.startsWith("delete") && !it.startsWith("deleteShader")
            },
            "a successful setup must not delete any framebuffer, renderbuffer, texture, VAO, buffer, or program",
        )
    }

    // ---- CancelRoute reachability through the public API -------------------------------------------

    /**
     * Proves Task 1's `CancelRoute` fix is reachable through this factory's `prepare()`, not merely
     * through `PreparationDriver` directly: two distinct sticker locators (so `preRegister` cannot
     * merge them into one route) where one route's `Transport` call throws a bare
     * [CancellationException] of its own initiative while the other is still in flight. Before this
     * task, `Renderer` had no concrete implementation anywhere, so this path had no caller at all.
     */
    @Test
    fun cancelRouteDoesNotCrashAMultiStickerPrepareCall() = runTest {
        val cancellingLocator = ResourceLocator("https://example.invalid/first.png")
        val hangingLocator = ResourceLocator("https://example.invalid/second.png")
        val transport = Transport { request ->
            if (request.locator == cancellingLocator) {
                throw CancellationException("adapter cancelled itself")
            }
            TransportResponse(statusCode = 200, body = onePixelPng)
        }
        val renderer = createRenderer(testConfiguration(transport = transport), validGlesBinding(), fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(
                Sticker(testPlacement(), cancellingLocator),
                Sticker(testPlacement(), hangingLocator),
            ),
        )

        // The meaningful claim is that a typed/cancellation outcome is reached at all, rather than an
        // unhandled `error(...)` crash inside ResourceActionExecutor's CancelRoute branch.
        assertFailsWith<CancellationException> { renderer.prepare(plan) }
    }

    // ---- A real sticker round trips through prepare() and draw() -----------------------------------

    @Test
    fun aStickerRoundTripsThroughPrepareAndDraw() = runTest {
        val transport = CountingTransport()
        val binding = validGlesBinding()
        val renderer = createRenderer(testConfiguration(transport = transport), binding, fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
        )

        val frame = renderer.prepare(plan)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertEquals(1, transport.executeCalls)
        assertTrue(binding.log.any { it.startsWith("drawArrays") }, "a sticker must actually draw")
        assertTrue(binding.log.any { it.startsWith("genTextures") }, "the sticker image must upload a fresh texture")
    }

    // ---- Task 9b item 1: texture lifetime — reuse on repeat draws, delete on close ------------------

    @Test
    fun repeatedDrawsOfTheSamePreparedFrameReuseTheCachedStickerTexture() = runTest {
        val binding = validGlesBinding()
        val renderer = createRenderer(testConfiguration(transport = CountingTransport()), binding, fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
        )
        val frame = renderer.prepare(plan)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()

        renderer.draw(frame, target)
        renderer.draw(frame, target)

        assertEquals(
            1,
            binding.log.count { it.startsWith("genTextures") },
            "a repeated draw of the same prepared frame must reuse its cached texture, not re-upload it",
        )
        assertTrue(binding.log.count { it.startsWith("drawArrays") } >= 2, "both draws must still actually draw")
    }

    @Test
    fun closeDeletesEveryCachedSpriteTexture() = runTest {
        // A bare "some deleteTextures call happened" assertion would be vacuous here: close() also
        // deletes the offscreen surface's OWN colour texture regardless of this fix, so that alone
        // cannot distinguish "the cached sticker texture was deleted" from "nothing sticker-specific
        // was deleted at all." This test instead extracts the sticker's OWN assigned texture name
        // (from the bindTexture call its own draw issued) and asserts THAT specific name appears in
        // RecordingGlBinding.deletedNames, which every delete* call appends to regardless of type.
        val binding = validGlesBinding()
        val renderer = createRenderer(testConfiguration(transport = CountingTransport()), binding, fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
        )
        val frame = renderer.prepare(plan)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        // The sticker's own bind is the FIRST bindTexture call: SceneContent draws the sticker
        // regime before drawFrame's composite pass binds the offscreen surface's own colour texture.
        val stickerTextureName = binding.log
            .first { it.startsWith("bindTexture(0xDE1,") }
            .substringAfter("0xDE1,")
            .removeSuffix(")")
            .toInt()

        renderer.close()

        assertTrue(
            stickerTextureName in binding.deletedNames,
            "close() must delete the specific cached sticker texture (name=$stickerTextureName), " +
                "not merely something: ${binding.deletedNames}",
        )
    }

    /**
     * Every program the renderer compiles for itself is deleted when it closes, counted rather than
     * named.
     *
     * **A leaked program is invisible from every other angle**, which is why this is a count and not
     * an assertion about one pipeline: `close()` deletes the offscreen surface, the registry's
     * textures and three other programs whatever happens to the fourth, so "some `deleteProgram`
     * ran" distinguishes nothing. Creating and deleting exactly [INTERNAL_PIPELINE_PROGRAMS] of them
     * does: dropping any one pipeline's delete leaves the two counts unequal, and adding a fifth
     * internal pipeline without deleting it fails here rather than leaking silently on a consumer's
     * context.
     *
     * **The vertex-array count is two short of the program count, and both shortfalls are Cycle
     * E-terrain's doing.** Four of the six programs belong to pipelines that still allocate a vertex
     * array at setup. The ground's two allocate none, because the ground's geometry became a
     * `GroundGrid` per granularity built on the draw path — four vertices cannot be displaced by
     * terrain, and which granularity a frame needs follows the camera. This renderer draws no ground
     * tile before it closes, so no grid is ever built and none is deleted;
     * `GroundPipelineTest.deletionRemovesEveryCachedGridAndTheProgram` is where the ground's own
     * deletion is counted, over grids that exist.
     */
    @Test
    fun closeDeletesEveryProgramTheRendererCompiledForItself() = runTest {
        val binding = validGlesBinding()
        val renderer = createRenderer(testConfiguration(transport = CountingTransport()), binding, fixedProbe())

        val created = binding.log.count { it.startsWith("createProgram") }
        assertEquals(
            INTERNAL_PIPELINE_PROGRAMS,
            created,
            "setup compiles the composite, sticker, backdrop, ground, displaced ground, label " +
                "and icon programs: ${binding.log}",
        )

        renderer.close()

        assertEquals(
            created,
            binding.log.count { it.startsWith("deleteProgram") },
            "close() must delete every program it compiled, or one leaks on the consumer's context",
        )
        assertEquals(
            INTERNAL_PIPELINE_PROGRAMS - 2,
            binding.log.count { it.startsWith("deleteVertexArrays") },
            "every internal pipeline that allocated a vertex array must have it deleted by close()",
        )
    }

    @Test
    fun notifyGpuObjectsGoneForgetsWithoutDeletingAndTheNextDrawReUploadsFreshly() = runTest {
        // ADR 0007/0015's other half of item 1: losing the GL context is NOT freeing. The registry
        // must forget its cached texture without issuing a single GL delete (the handle is already
        // gone with the lost context, so a delete call would be meaningless or, worse, could collide
        // with an unrelated live object in the replacement context) -- and the NEXT draw, after the
        // caller re-adopts a context, must re-upload fresh rather than reuse a stale cache entry.
        val binding = validGlesBinding()
        val renderer = createRenderer(testConfiguration(transport = CountingTransport()), binding, fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
        )
        val frame = renderer.prepare(plan)
        val firstTarget = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, firstTarget)
        assertEquals(1, binding.log.count { it.startsWith("genTextures") }, "the first draw must upload")

        binding.log.clear()
        renderer.notifyGpuObjectsGone()
        assertTrue(
            binding.log.none { it.startsWith("delete") },
            "forgetting GPU objects must never issue a GL delete call: ${binding.log}",
        )

        // A render target minted before the context was lost is stale once a fresh one is adopted;
        // mint a new one, same as any real caller must after adoptCurrentRenderContext().
        renderer.adoptCurrentRenderContext()
        val secondTarget = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, secondTarget)

        assertEquals(
            1,
            binding.log.count { it.startsWith("genTextures") },
            "after forgetting, the next draw must re-upload fresh rather than reuse a stale " +
                "registry entry from before the context was lost: ${binding.log}",
        )
    }

    // ---- Task 9b item 2: a sticker's quad is sized from its own image's pixel dimensions ------------

    @Test
    fun aRealDecodedStickerImagesDimensionsScaleTheDrawnQuadEndToEnd() = runTest {
        // onePixelPng (this file's own long-standing fixture, despite its name) decodes to a 2x2
        // RGBA PNG -- see its own doc comment below. The sticker's MVP uniform location must be
        // declared on THIS test's own binding (never the shared validGlesBinding() default, which
        // declares no uniform names at all) or drawOneSticker's `>= 0` guard silently skips setting
        // it, and this test would pass while asserting nothing -- exactly the trap this cycle's own
        // task description warns about.
        val mvpLocation = 41
        val binding = validGlesBinding().apply {
            declaredNames = mapOf(
                STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to mvpLocation,
                STICKER_TEXTURE_UNIFORM_NAME to 42,
            )
        }
        val renderer = createRenderer(testConfiguration(transport = CountingTransport()), binding, fixedProbe())
        val placement = testPlacement()
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(Sticker(placement, ResourceLocator("https://example.invalid/a.png"))),
        )

        val frame = renderer.prepare(plan)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        val camera = (
            resolveMercatorCamera(testCamera(), OutputPixelSize(64, 64)) as SpatialOutcome.Success
            ).value
        val resolved = (resolvePlacement(placement, camera) as SpatialOutcome.Success).value
        // onePixelPng decodes to 2x2: the expected MVP bakes a (2, 2, 1) local pre-scale in, not the
        // (1, 1, 1) a fixed unit quad (this cycle's bug before Task 9b) would have used.
        val expected = composeScreenModelViewProjection(OutputPixelSize(64, 64), resolved, DoubleVector3(2.0, 2.0, 1.0))
        val unitQuad = composeScreenModelViewProjection(OutputPixelSize(64, 64), resolved)

        val actual = requireNotNull(binding.uniformMatrix4fvValues[mvpLocation]) {
            "the sticker's MVP must have been bound: ${binding.log}"
        }
        assertContentEquals(expected, actual)
        assertTrue(actual.toList() != unitQuad.toList(), "a real 2x2 image must not draw as a 1x1 unit quad")
    }

    // ---- Task 9b item 3: a live Geometry.uniforms mutation after prepare() must not reach draw() ----

    @Test
    fun mutatingAGeometrysUniformsMapAfterPrepareDoesNotChangeWhatDraws() = runTest {
        val uniformLocation = 55
        val binding = validGlesBinding().apply { declaredNames = mapOf("uTint" to uniformLocation) }
        val renderer = createRenderer(testConfiguration(), binding, fixedProbe())
        // A caller's own MutableMap, retained after construction -- Geometry stores this exact
        // reference (a forced consequence of keeping Geometry a data class; see its own KDoc).
        val liveUniforms = mutableMapOf<String, ShaderValue>("uTint" to ShaderValue.Scalar(0.25f))
        val geometry = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalGeometryShaderPair(),
            uniforms = liveUniforms,
        )
        val plan = FramePlan(frameIndex = 0L, camera = testCamera(), geometries = listOf(geometry))

        val frame = renderer.prepare(plan)
        // Mutate AFTER prepare() returns -- the exact window Task 9b's snapshot must close.
        liveUniforms["uTint"] = ShaderValue.Scalar(0.75f)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertTrue(
            binding.log.contains("uniform1f($uniformLocation,0.25)"),
            "the value prepare() snapshotted must be what draws: ${binding.log}",
        )
        assertFalse(
            binding.log.contains("uniform1f($uniformLocation,0.75)"),
            "a post-prepare() mutation of the caller's own map must never reach draw(): ${binding.log}",
        )
    }

    // ---- Task 9b item 4: a Geometry consumer texture is fetched, decoded, and bound ------------------

    @Test
    fun aGeometryConsumerTextureRoundTripsThroughPrepareAndDraw() = runTest {
        val samplerLocation = 66
        val binding = validGlesBinding().apply { declaredNames = mapOf("uMask" to samplerLocation) }
        val transport = CountingTransport()
        val renderer = createRenderer(testConfiguration(transport = transport), binding, fixedProbe())
        val geometry = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalGeometryShaderPair(),
            textures = mapOf("uMask" to ResourceLocator("https://example.invalid/mask.png")),
        )
        val plan = FramePlan(frameIndex = 0L, camera = testCamera(), geometries = listOf(geometry))

        val frame = renderer.prepare(plan)
        assertEquals(1, transport.executeCalls, "prepare() must fetch the geometry's own consumer texture")

        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertTrue(binding.log.any { it.startsWith("genTextures") }, "the consumer texture must be uploaded")
        assertTrue(
            binding.log.contains("uniform1i($samplerLocation,0)"),
            "the sampler must be bound to a texture unit: ${binding.log}",
        )
    }

    // ---- Task 16: a Model is acquired, decoded, posed, uploaded and drawn ---------------------------

    @Test
    fun preparingAFrameWithAModelAcquiresItsGlbAndItsOverrideTexture() = runTest {
        // MODEL_GLB and MODEL_TEXTURE were already traversed by staticResourceTraversal and had never
        // been acquired: prepare() fetched a plan's stickers and a geometry's consumer textures and
        // walked straight past every model. This is the test that gap closes against.
        val transport = ModelTransport()
        val renderer = createRenderer(testConfiguration(transport = transport), modelBinding(), fixedProbe())

        renderer.prepare(modelPlan(texture = ResourceLocator(TEXTURE_URL)))

        assertEquals(setOf(GLB_URL, TEXTURE_URL), transport.requestedUrls)
    }

    @Test
    fun aMissingAnimationSelectorFailsAtResourceParsingWithItsOwnField() = runTest {
        // The allowlist puts ANIMATION_SELECTOR at RESOURCE_PARSING and NOT at FRAME_PLANNING, which is
        // an ordering fact rather than a taste: a selector resolves against the GLB's own animation
        // catalogue, which does not exist until the bytes are in hand, so the check cannot live in
        // FramePlanningCore. The fixture's one animation is named "spin".
        val renderer = createRenderer(
            testConfiguration(transport = ModelTransport()),
            modelBinding(),
            fixedProbe(),
        )
        val plan = modelPlan(
            tracks = listOf(AnimationTrack(AnimationSelector.Name("walk"), 0.0)),
        )

        val failure = assertFailsWith<RenGException> { renderer.prepare(plan) }

        assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, failure.code)
        assertEquals(PipelineStage.RESOURCE_PARSING, failure.stage)
        assertEquals("animationSelector", failure.diagnostics.single().fieldName)
    }

    @Test
    fun anOutOfRangeAnimationIndexFailsTheSameWayAsAMissingName() = runTest {
        // A separate case from the name above, and deliberately not at the boundary: the fixture
        // declares exactly one animation, so index 0 resolves and index 1 is the first that does not.
        // Asserting only index 1 would leave "every index is refused" indistinguishable from the rule
        // actually implemented, so the passing half is asserted in the same test.
        val renderer = createRenderer(
            testConfiguration(transport = ModelTransport()),
            modelBinding(),
            fixedProbe(),
        )

        renderer.prepare(modelPlan(tracks = listOf(AnimationTrack(AnimationSelector.Index(0L), 0.25))))

        val failure = assertFailsWith<RenGException> {
            renderer.prepare(modelPlan(tracks = listOf(AnimationTrack(AnimationSelector.Index(1L), 0.25))))
        }
        assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, failure.code)
        assertEquals(PipelineStage.RESOURCE_PARSING, failure.stage)
        assertEquals("animationSelector", failure.diagnostics.single().fieldName)
    }

    @Test
    fun twoSelectorsResolvingToOneAnimationFailTheSameWay() = runTest {
        // CONTEXT.md states two separate rules under one code: a missing or out-of-range selector, OR
        // different selectors that resolve to the same animation. This is the second one -- the name
        // and the index both name the fixture's only animation.
        val renderer = createRenderer(
            testConfiguration(transport = ModelTransport()),
            modelBinding(),
            fixedProbe(),
        )
        val plan = modelPlan(
            tracks = listOf(
                AnimationTrack(AnimationSelector.Name("spin"), 0.0),
                AnimationTrack(AnimationSelector.Index(0L), 0.5),
            ),
        )

        val failure = assertFailsWith<RenGException> { renderer.prepare(plan) }

        assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, failure.code)
        assertEquals(PipelineStage.RESOURCE_PARSING, failure.stage)
        assertEquals("animationSelector", failure.diagnostics.single().fieldName)
    }

    @Test
    fun aModelRoundTripsThroughPrepareAndDraw() = runTest {
        val binding = modelBinding()
        val renderer = createRenderer(testConfiguration(transport = ModelTransport()), binding, fixedProbe())

        val frame = renderer.prepare(modelPlan())
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertTrue(binding.log.any { it.startsWith("genVertexArrays") }, "the primitive must upload")
        assertTrue(
            binding.log.contains("drawElements(0x4,3,0x1403,0)"),
            "the model's one three-index primitive must actually draw: ${binding.log}",
        )
        // SceneModel.imageTextures is what the draw path reads: decoding the GLB's embedded images and
        // then not uploading them renders every textured primitive untextured, with no error anywhere.
        // With no override in this plan, the authored image is the only thing that can be sampled -- so
        // a sampler bound at all is the whole claim, and it is one an empty imageTextures list fails.
        assertTrue(
            binding.log.contains("uniform1i($MODEL_TEXTURE_LOCATION,0)"),
            "the GLB's own embedded base-colour texture must be sampled: ${binding.log}",
        )
    }

    @Test
    fun aSkinnedModelUploadsAJointPaletteAndDrawsThroughTheSkinnedVariant() = runTest {
        // The whole skinned arm of prepareModel -- jointMatricesForSkin per skin a draw item names,
        // keyed into PreparedModel.jointMatricesBySkin -- is invisible to every other test here, whose
        // fixtures carry no rig at all. `modelShaderVariantFor` picks the skinned variant only when a
        // palette is actually present, and only a skinned pipeline owns a joint uniform buffer, so a
        // `bufferSubData` into GL_UNIFORM_BUFFER is a statement that the palette reached the draw.
        val binding = modelBinding()
        val renderer = createRenderer(
            testConfiguration(transport = ModelTransport(glb = skinnedTriangleGlb())),
            binding,
            fixedProbe(),
        )
        val frame = renderer.prepare(modelPlan())
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertTrue(
            binding.log.contains("drawElements(0x4,3,0x1403,0)"),
            "the skinned primitive must draw: ${binding.log}",
        )
        // Two joints, sixteen floats each, four bytes a float.
        assertTrue(
            binding.log.contains("bufferSubData(0x8A11,0,128)"),
            "the two-joint palette must be uploaded into the joint uniform block: ${binding.log}",
        )
    }

    @Test
    fun aModelsEmbeddedImageUploadsPremultiplied() = runTest {
        // TextureContent.IMAGE, never DATA, and this is a coupling rather than a preference: the
        // textured model fragment shader un-premultiplies the texel it samples, so an unpremultiplied
        // upload renders every partially transparent surface too dark. The fixture's second texel is
        // fully transparent with a non-zero colour, which premultiplication zeroes and DATA leaves
        // exactly as authored -- so the two are separated by whole bytes rather than by a rounding.
        val binding = modelBinding()
        val renderer = createRenderer(testConfiguration(transport = ModelTransport()), binding, fixedProbe())
        val frame = renderer.prepare(modelPlan())
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertEquals(
            listOf<Byte>(0x10, 0x20, 0x30, -1, 0, 0, 0, 0),
            binding.lastTexImageBytes(),
            "the GLB's embedded image must upload premultiplied: ${binding.log}",
        )
    }

    @Test
    fun movingATracksTimeMovesWhatDraws() = runTest {
        // Nothing else in this section would notice `sampleAnimationTracks` being replaced by an empty
        // override map: every other assertion is about uploads, binds and draw calls, none of which the
        // pose changes. The fixture's one animation translates node 0 from the origin to (4, 8, 16)
        // between t=0 and t=1, and the node transform is composed onto the placement, so two frames at
        // two times must bind two different model-view-projection matrices.
        //
        // The two times are 0.0 and 0.75, and neither is an accident. The midpoint of a two-keyframe
        // linear track is the symmetry point where holding the earlier keyframe and interpolating
        // correctly are hardest to tell apart, so 0.5 is avoided; and the far endpoint is unusable
        // because CONTEXT.md wraps a positive-duration animation by `timeSeconds % durationSeconds`,
        // which maps this track's t=1 back onto t=0 exactly.
        val binding = modelBinding()
        val renderer = createRenderer(testConfiguration(transport = ModelTransport()), binding, fixedProbe())
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val atRest = renderer.prepare(animatedPlan(frameIndex = 0L, timeSeconds = 0.0))
        renderer.draw(atRest, target)
        val restMatrix = requireNotNull(binding.uniformMatrix4fvValues[MODEL_VIEW_PROJECTION_LOCATION]) {
            "the model-view-projection must have been bound: ${binding.log}"
        }.copyOf()

        val moved = renderer.prepare(animatedPlan(frameIndex = 1L, timeSeconds = 0.75))
        renderer.draw(moved, target)
        val movedMatrix = requireNotNull(binding.uniformMatrix4fvValues[MODEL_VIEW_PROJECTION_LOCATION]).copyOf()

        assertFalse(
            restMatrix.contentEquals(movedMatrix),
            "a track sampled at a different time must move the model: ${restMatrix.toList()}",
        )
    }

    private fun animatedPlan(frameIndex: Long, timeSeconds: Double): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = modelCamera(),
        models = listOf(
            Model(
                placement = mapPlacement(),
                glb = ResourceLocator(GLB_URL),
                animationTracks = listOf(AnimationTrack(AnimationSelector.Name("spin"), timeSeconds)),
            ),
        ),
    )

    @Test
    fun theSamePreparedFrameDrawnTwiceUploadsItsPrimitivesOnce() = runTest {
        // Keyed by ResourceKeyDeriver.modelGeometry -- the same upload-once-by-key discipline a
        // sticker's image already follows. Counting genVertexArrays rather than genBuffers, because
        // creating the four skinned model pipelines generates a joint uniform buffer each and would
        // make a buffer count say nothing about the primitive.
        val binding = modelBinding()
        val renderer = createRenderer(testConfiguration(transport = ModelTransport()), binding, fixedProbe())
        val frame = renderer.prepare(modelPlan())
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()

        renderer.draw(frame, target)
        renderer.draw(frame, target)

        assertEquals(
            1,
            binding.log.count { it.startsWith("genVertexArrays") },
            "the second draw must reuse the uploaded primitive, not upload it again: ${binding.log}",
        )
        assertEquals(
            2,
            binding.log.count { it == "drawElements(0x4,3,0x1403,0)" },
            "both draws must still actually draw",
        )
    }

    @Test
    fun anOverrideReplacesEveryPrimitivesBaseColourTextureAndKeepsEverythingElse() = runTest {
        // CONTEXT.md: "an override replaces every rendered primitive's base-colour texture while
        // preserving other material properties." The fixture's material names its own embedded image
        // AND carries an asymmetric base colour factor, so the two halves of that sentence are
        // separable: the bound texture must be the override's, and the factor must still be authored.
        val binding = modelBinding()
        val renderer = createRenderer(testConfiguration(transport = ModelTransport()), binding, fixedProbe())
        val frame = renderer.prepare(modelPlan(texture = ResourceLocator(TEXTURE_URL)))
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        // Two textures upload before anything draws, in this order: the GLB's own embedded image (index
        // 0 of model.images), then the override. Each upload binds the name it was just given, so the
        // first two texture binds name them in that order, and `drawModels`' own base-colour bind is
        // the last one before the draw call.
        val drawIndex = binding.log.indexOfFirst { it == "drawElements(0x4,3,0x1403,0)" }
        assertTrue(drawIndex >= 0, "the model must draw: ${binding.log}")
        val binds = binding.log.take(drawIndex).filter { it.startsWith("bindTexture(0xDE1,") }
        assertTrue(binds.size >= 3, "both uploads plus the draw's own bind must appear: ${binding.log}")
        val embeddedTexture = binds[0]
        val overrideTexture = binds[1]
        assertNotEquals(embeddedTexture, overrideTexture, "the two uploads must be distinct GL textures")

        assertEquals(overrideTexture, binds.last(), "the override must win over the authored image")
        assertTrue(
            binding.log.contains("uniform4f($MODEL_FACTOR_LOCATION,0.125,0.25,0.5,0.75)"),
            "the authored base colour factor must survive the override: ${binding.log}",
        )
    }

    @Test
    fun anOverrideOnAPrimitiveWithNoTextureCoordinatesDrawsTheFactorAlone() = runTest {
        // "Replaces" cannot add a texture to a primitive that has no TEXCOORD_0 to sample it with:
        // binding a sampler with no coordinates reads an undefined varying, silently, with no GL error.
        val binding = modelBinding()
        val renderer = createRenderer(
            testConfiguration(transport = ModelTransport(glb = untexturedTriangleGlb())),
            binding,
            fixedProbe(),
        )
        val frame = renderer.prepare(modelPlan(texture = ResourceLocator(TEXTURE_URL)))
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertTrue(
            binding.log.contains("drawElements(0x4,3,0x1403,0)"),
            "the primitive must still draw: ${binding.log}",
        )
        assertTrue(
            binding.log.contains("uniform4f($MODEL_FACTOR_LOCATION,0.125,0.25,0.5,0.75)"),
            "the base colour factor must be bound: ${binding.log}",
        )
        assertFalse(
            binding.log.contains("uniform1i($MODEL_TEXTURE_LOCATION,0)"),
            "no sampler may be bound on a primitive with no texture coordinates: ${binding.log}",
        )
    }

    @Test
    fun closeDeletesTheModelsVertexArrayAndItsBuffers() = runTest {
        // The specific-name assertion `closeDeletesEveryCachedSpriteTexture` uses, for the same reason:
        // close() deletes RenG's own pipelines regardless, so "some deleteVertexArrays happened" cannot
        // distinguish the model's VAO from the sticker pipeline's.
        val binding = modelBinding()
        val renderer = createRenderer(testConfiguration(transport = ModelTransport()), binding, fixedProbe())
        val frame = renderer.prepare(modelPlan())
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        // RecordingGlBinding does not log the names it hands out, but uploadModelPrimitive binds the
        // vertex array it was just given, so the first bind after the first genVertexArrays names it.
        val generated = binding.log.indexOfFirst { it.startsWith("genVertexArrays") }
        val vertexArray = binding.log
            .drop(generated)
            .first { it.startsWith("bindVertexArray(") }
            .removePrefix("bindVertexArray(")
            .removeSuffix(")")
            .toInt()

        renderer.close()

        assertTrue(
            vertexArray in binding.deletedNames,
            "close() must delete the model's own vertex array (name=$vertexArray): ${binding.deletedNames}",
        )
    }

    @Test
    fun contextLossForgetsModelBuffersWithoutDeletingThem() = runTest {
        // ADR 0007/0015: losing the context is not freeing. The vertex arrays and buffers are already
        // gone with the context, so nothing valid remains to delete -- and the DecodedModel they were
        // uploaded from is CPU-side and survives, so the next draw re-uploads without re-fetching.
        val binding = modelBinding()
        val transport = ModelTransport()
        val renderer = createRenderer(testConfiguration(transport = transport), binding, fixedProbe())
        val frame = renderer.prepare(modelPlan())
        val firstTarget = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, firstTarget)
        assertEquals(1, binding.log.count { it.startsWith("genVertexArrays") }, "the first draw must upload")

        binding.log.clear()
        renderer.notifyGpuObjectsGone()
        assertTrue(
            binding.log.none { it.startsWith("delete") },
            "forgetting GPU objects must never issue a GL delete call: ${binding.log}",
        )

        renderer.adoptCurrentRenderContext()
        val secondTarget = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, secondTarget)

        assertEquals(
            1,
            binding.log.count { it.startsWith("genVertexArrays") },
            "the next draw must re-upload the primitive rather than reuse a forgotten handle: ${binding.log}",
        )
        assertEquals(1, transport.executeCalls, "re-uploading must not re-fetch the GLB")
    }

    @Test
    fun aStickersImageIsDecodedOncePerGenerationRatherThanOncePerFrame() = runTest {
        val binding = validGlesBinding()
        val transport = CountingTransport()
        val renderer = createRenderer(testConfiguration(transport = transport), binding, fixedProbe())
        fun plan(frameIndex: Long) = FramePlan(
            frameIndex = frameIndex,
            camera = testCamera(),
            stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
        )

        val first = renderer.prepare(plan(0L)) as RenGPreparedFrame
        val selector = ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)

        // ADR 0047 recorded decodedCpuBytes as "charged at zero throughout ... the field exists for a
        // decode path this cache does not yet have". ADR 0059 is that path, so this is the first
        // number this report has ever been able to give.
        val decodedBytes = renderer.queryResources(selector).totals.decodedCpuBytes
        assertTrue(decodedBytes > 0L, "the decode must be attached to its generation")

        val second = renderer.prepare(plan(1L)) as RenGPreparedFrame

        // The proof the second frame did not decode again: it is holding the first frame's pixels.
        // Before ADR 0059 every frame after the first decoded the PNG and then discarded the result,
        // because `cachedTexture` skips its upload lambda once a texture is registered -- measured at
        // 14.3 ms for one 512x512 image on a native build.
        assertSame(
            first.stickers.single().image,
            second.stickers.single().image,
            "a second frame must reuse the decode, not repeat it",
        )
        assertEquals(
            1,
            renderer.queryResources(selector).entries.single().residentGenerationCount,
            "and must re-lease the generation rather than install a byte-identical new one",
        )
        assertEquals(
            decodedBytes,
            renderer.queryResources(selector).totals.decodedCpuBytes,
            "and must not charge the budget twice for one image",
        )
    }

    @Test
    fun queryResourcesStopsClaimingZeroGpuBytesOnceATextureIsResident() = runTest {
        val binding = validGlesBinding()
        val renderer = createRenderer(testConfiguration(transport = CountingTransport()), binding, fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
        )
        val frame = renderer.prepare(plan)
        val selector = ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)

        // Prepared but never drawn: the image is decoded and resident on the CPU, and nothing of it
        // is on the GPU yet. Zero GPU bytes here is knowledge, and the report is right to say so.
        val beforeDraw = renderer.queryResources(selector).entries.single().usage
        assertEquals(0L, beforeDraw.knownGpuBytes, "nothing is uploaded before the first draw")
        assertFalse(beforeDraw.hasUnknownGpuBytes)

        renderer.draw(frame, renderer.mintRenderTarget(FramebufferName(0u)))

        // The same key, the same query, after `cachedTexture` uploaded and registered a texture under
        // it. That texture goes through the unbudgeted `register` path, so its bytes are genuinely
        // unknown to the layer that owns it -- and the report must now say "unknown" rather than
        // repeating the zero it was entitled to a moment ago. These are two different claims and the
        // difference is the whole defect: before this fix, both frames read 0L / false.
        val afterDraw = renderer.queryResources(selector).entries.single().usage
        assertNull(afterDraw.knownGpuBytes, "a resident texture of unrecorded size has no known byte count")
        assertTrue(afterDraw.hasUnknownGpuBytes, "and the report must declare that it does not know")
        assertEquals(beforeDraw.rawBytes, afterDraw.rawBytes, "nothing about the CPU-side account changed")
    }

    @Test
    fun aModelsGlbIsReportedAsAResidentResourceAndIsFreed() = runTest {
        // A GLB is bytes, never a GPU object of its own: its vertex and index buffers are registered
        // under `ResourceKeyDeriver.modelGeometry` keys, not under this one, so this entry's own GPU
        // account is a knowable zero and stays one. What a report can say about a model here is that
        // its GLB is resident, and by how many bytes.
        val binding = modelBinding()
        val renderer = createRenderer(testConfiguration(transport = ModelTransport()), binding, fixedProbe())
        renderer.prepare(modelPlan())

        val selector = ResourceSelector.ByClass(ResourceClass.MODEL_GLB)
        val report = renderer.queryResources(selector)
        assertEquals(1, report.entries.size, "the GLB must be resident under its own class")
        assertEquals(texturedTriangleGlb().size.toLong(), report.totals.rawBytes)
        assertEquals(0L, report.entries.single().usage.knownGpuBytes, "a GLB key holds no GPU object")
        assertFalse(report.entries.single().usage.hasUnknownGpuBytes)

        // Deferred rather than fully freed, and that is not a model-shaped fact: the preparation that
        // installed this generation still holds the lease `ResidentCache.installAndTakeLease` gave it,
        // and `free` retires a still-leased generation instead of dropping it. The observable contract
        // is the reload flag -- "freeing is never an error for the caller to recover from".
        val freed = renderer.freeResources(selector)
        assertEquals(1, freed.matchedKeys)
        assertEquals(0, freed.alreadyFreeKeys)
        assertEquals(1, freed.deferredKeys)
        assertTrue(renderer.queryResources(selector).entries.single().reloadRequired)
    }

    private fun modelPlan(
        texture: ResourceLocator? = null,
        tracks: List<AnimationTrack> = emptyList(),
    ): FramePlan = FramePlan(
        frameIndex = 0L,
        camera = modelCamera(),
        models = listOf(
            Model(
                placement = mapPlacement(),
                glb = ResourceLocator(GLB_URL),
                texture = texture,
                animationTracks = tracks,
            ),
        ),
    )

    /**
     * Zoom 18, not [testCamera]'s zoom 2, and that is load-bearing rather than cosmetic. A `MAP` scale
     * is metres per GLB unit, so at zoom 2 a scale of 1 resolves to about 2.5e-5 logical pixels, whose
     * cube is below `DoubleMatrix3.inverse`'s 1e-12 singularity threshold — `modelNormalMatrix` would
     * return `null`, `SceneContent.resolveModel` would correctly drop the primitive, and every draw
     * assertion in this section would pass while nothing at all was drawn.
     */
    private fun modelCamera(): Camera =
        Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 18.0, bearing = 0.0, pitch = 0.0)

    private fun mapPlacement(): Placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(0.0, 0.0, 0.0),
        rotationMode = AnchoringMode.MAP,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.MAP,
        scale = 1.0,
    )

    /**
     * [validGlesBinding] plus every model uniform name declared and the uniform-block size seeded.
     *
     * Both halves are traps this cycle has already been caught by. `getUniformLocation` answers `-1`
     * for a name the fake was never told about, and `drawModels` guards every `uniform*` call on a
     * non-negative location — so a test that forgets a name sees zero calls and can pass while
     * asserting nothing. And `createModelPipeline` reads `GL_MAX_UNIFORM_BLOCK_SIZE` and refuses
     * anything below 16384, which an unseeded `RecordingGlBinding` answers as `0`.
     */
    private fun modelBinding(): RecordingGlBinding = validGlesBinding().apply {
        declaredNames = mapOf(
            MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
            MODEL_NORMAL_MATRIX_UNIFORM_NAME to 71,
            MODEL_BASE_COLOUR_FACTOR_UNIFORM_NAME to MODEL_FACTOR_LOCATION,
            MODEL_BASE_COLOUR_TEXTURE_UNIFORM_NAME to MODEL_TEXTURE_LOCATION,
            MODEL_ALPHA_CUTOFF_UNIFORM_NAME to 74,
            MODEL_LIGHT_DIRECTION_UNIFORM_NAME to 75,
            MODEL_AMBIENT_UNIFORM_NAME to 76,
            MODEL_FORCE_OPAQUE_UNIFORM_NAME to 77,
            MODEL_VERTEX_COLOUR_PRESENT_UNIFORM_NAME to 78,
            MODEL_JOINT_BLOCK_NAME to 3,
        )
        integers[GL_MAX_UNIFORM_BLOCK_SIZE] = intArrayOf(16384)
    }

    private fun minimalGeometryShaderPair(): ShaderPair = ShaderPair(
        vertexSource = "#version 300 es\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n",
        fragmentSource = "#version 300 es\nprecision highp float;\nout vec4 rengOut;\nvoid main() {\n    rengOut = vec4(1.0);\n}\n",
    )

    // ---- Prepared-frame lease lifetime ---------------------------------------------------------------

    /**
     * `CONTEXT.md` defines a lease as held "by a **Prepared Frame** for every distinct resource its plan
     * needs", so closing the frame is what releases it. `closePreparedFrame` used to only mark the frame
     * closed and release nothing at all: every lease a preparation took stayed outstanding for the
     * renderer's whole life, `queryResources()` reported a permanently non-zero lease count, and
     * `freeResources()` could only ever report the key deferred -- an unbounded leak for a consumer that
     * prepares many frames.
     *
     * Two frames over one locator rather than one, because a single outstanding lease is indistinguishable
     * from a legitimately pinned generation: it is two closed frames still holding two leases that makes
     * the leak unbounded rather than merely present.
     */
    @Test
    fun closingAPreparedFrameReleasesTheLeasesItsPreparationTook() = runTest {
        val renderer = createRenderer(
            testConfiguration(transport = CountingTransport()),
            validGlesBinding(),
            fixedProbe(),
        )
        val first = renderer.prepare(oneStickerPlan(frameIndex = 0L))
        val second = renderer.prepare(oneStickerPlan(frameIndex = 1L))

        assertEquals(2, outstandingLeases(renderer), "each open prepared frame must hold its own lease")

        first.close()
        second.close()

        assertEquals(
            0,
            outstandingLeases(renderer),
            "closing a prepared frame must release every lease its own preparation took",
        )

        val freed = renderer.freeResources()
        assertEquals(1, freed.matchedKeys)
        assertEquals(1, freed.fullyFreedKeys, "an unleased key must be fully freed, never merely deferred")
        assertEquals(0, freed.deferredKeys)
    }

    /**
     * `close()` is idempotent, and a [com.rohittp.reng.internal.cache.Lease] rejects a double release
     * outright, so the two claims are one claim: the frame hands its leases over exactly once and a second
     * `close()` releases nothing rather than throwing.
     */
    @Test
    fun closingAPreparedFrameTwiceReleasesItsLeasesOnlyOnce() = runTest {
        val renderer = createRenderer(
            testConfiguration(transport = CountingTransport()),
            validGlesBinding(),
            fixedProbe(),
        )
        val frame = renderer.prepare(oneStickerPlan(frameIndex = 0L))

        frame.close()
        frame.close()

        assertEquals(0, outstandingLeases(renderer))
    }

    private fun oneStickerPlan(frameIndex: Long): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = testCamera(),
        stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
    )

    private fun outstandingLeases(renderer: Renderer): Int =
        renderer.queryResources().entries.sumOf { it.leaseCount }

    // ---- Test doubles and fixtures -----------------------------------------------------------------

    private fun fixedProbe(): RenderContextProbe = RenderContextProbe { RenderContextIdentity(1L) }

    private fun testCamera(): Camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 2.0, bearing = 0.0, pitch = 0.0)

    private fun testPlacement(): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(0.0, 0.0, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun testConfiguration(
        transport: Transport = Transport { error("test transport must not execute") },
        store: Store = NoOpStore(),
        basemapStyle: ResourceLocator? = null,
        diagnosticSink: DiagnosticSink = DiagnosticSink.None,
        terrainShading: Boolean = false,
    ): RendererConfiguration = RendererConfiguration(
        outputPixelSize = OutputPixelSize(64, 64),
        transport = transport,
        store = store,
        basemapStyle = basemapStyle,
        diagnosticSink = diagnosticSink,
        terrainShading = terrainShading,
    )

    /** A GLES 3.20 context report matching [com.rohittp.reng.internal.gl.GlLifecycleDriverTest]'s own default fixture. */
    private fun validGlesBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
        strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2"
        strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
        strings[GL_VENDOR] = "Mesa"
        indexedStrings += listOf("GL_EXT_sRGB_write_control", "GL_OES_texture_float")
        integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
    }
}

private class NoOpStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
}

/** Counts every [Transport.execute] call and answers a valid one-pixel PNG. */
private class CountingTransport : Transport {
    var executeCalls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        return TransportResponse(statusCode = 200, body = onePixelPng)
    }
}

private class RecordingDiagnosticSink : DiagnosticSink {
    private val recorded: MutableList<Diagnostic> = mutableListOf()

    val diagnostics: List<Diagnostic> get() = ArrayList(recorded)

    override fun emit(diagnostic: Diagnostic) {
        recorded += diagnostic
    }
}

/** A minimal, well-formed 2x2 RGBA PNG, base64-encoded (the same fixture used across this cycle's tests). */
private val onePixelPng: ByteArray = kotlin.io.encoding.Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

// ---- Task 16 fixtures: one model, over a real GLB assembled byte by byte -------------------------

private const val GLB_URL = "https://example.invalid/model.glb"
private const val TEXTURE_URL = "https://example.invalid/override.png"

/**
 * A real 2x1 RGBA PNG whose two texels are `(0x10, 0x20, 0x30, 0xFF)` and `(0x40, 0x50, 0x60, 0x00)`.
 *
 * The GLB's embedded image, and deliberately not [onePixelPng], which is colour type 2 — RGB, no alpha
 * at all — so every texel widens to alpha 255 and premultiplication is the identity over it. A texture
 * fixture that cannot tell a premultiplied upload from an unpremultiplied one is exactly the vacuous
 * check this cycle keeps finding, and the second texel is a fully transparent one specifically so the
 * difference is unmistakable rather than a rounding.
 *
 * Generated once by CPython, following `PngDecoderTest`'s anti-circularity rule that an expectation must
 * not be produced by the code under test. Regenerate with:
 *
 *     python3 - <<'PY'
 *     import zlib, struct
 *     def chunk(kind, payload):
 *         return (struct.pack(">I", len(payload)) + kind + payload +
 *                 struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))
 *     raw = b"\x00" + bytes([0x10, 0x20, 0x30, 0xFF, 0x40, 0x50, 0x60, 0x00])
 *     print(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 6, 0, 0, 0)) +
 *           chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
 *     PY
 */
private val transparentEdgePng: ByteArray = byteArrayOf(
    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72,
    68, 82, 0, 0, 0, 2, 0, 0, 0, 1, 8, 6, 0, 0,
    0, -12, 34, 127, -118, 0, 0, 0, 17, 73, 68, 65, 84, 120,
    -38, 99, 16, 80, 48, -8, -17, 16, -112, -64, 0, 0, 10, 52,
    2, 80, 20, 99, -11, -127, 0, 0, 0, 0, 73, 69, 78, 68,
    -82, 66, 96, -126,
)

/** The model uniform locations the assertions above name; see `modelBinding`'s KDoc for why. */
private const val MODEL_VIEW_PROJECTION_LOCATION = 70
private const val MODEL_FACTOR_LOCATION = 72
private const val MODEL_TEXTURE_LOCATION = 73

/**
 * Answers the GLB for [GLB_URL] and [onePixelPng] for anything else, recording every locator it was
 * asked for. Two answers rather than one because `preparingAFrameWithAModelAcquiresItsGlbAndItsOverrideTexture`
 * asserts the exact set of urls fetched, which a transport answering one body for both could satisfy
 * without either being routed correctly.
 */
private class ModelTransport(private val glb: ByteArray = texturedTriangleGlb()) : Transport {
    private val requested = mutableSetOf<String>()

    val requestedUrls: Set<String> get() = LinkedHashSet(requested)

    var executeCalls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        requested += request.locator.value
        val body = if (request.locator.value == GLB_URL) glb else onePixelPng
        return TransportResponse(statusCode = 200, body = body)
    }
}

/**
 * One indexed triangle with texture coordinates, an authored base colour factor and texture, and one
 * animation named `spin`.
 *
 * Asymmetric everywhere it could be symmetric, following `ModelDecodeTest`'s own rule: positions
 * ascend `1..9` rather than repeating, the index run is `0, 2, 1` rather than the identity permutation,
 * the sampler's four enums are four different values, and the base colour factor's four components
 * differ — so a transposed, reversed or mis-strided read fails instead of passing by coincidence.
 */
private fun texturedTriangleGlb(): ByteArray {
    // Derived rather than written out, because the one number a hand-assembled GLB gets wrong is a
    // length: the embedded PNG's own size decides both the image buffer view's extent and the buffer's
    // declared total, and a literal for either drifts silently into a Malformed decode the moment the
    // shared PNG fixture changes by a byte.
    val pngBytes = transparentEdgePng.size
    val bufferBytes = PNG_BUFFER_VIEW_OFFSET + pngBytes
    return glb(
        """
    {"asset": {"version": "2.0"},
     "buffers": [{"byteLength": $bufferBytes}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 24},
       {"buffer": 0, "byteOffset": 60, "byteLength": 6},
       {"buffer": 0, "byteOffset": 68, "byteLength": 8},
       {"buffer": 0, "byteOffset": 76, "byteLength": 24},
       {"buffer": 0, "byteOffset": $PNG_BUFFER_VIEW_OFFSET, "byteLength": $pngBytes}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC2"},
       {"bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR"},
       {"bufferView": 3, "componentType": 5126, "count": 2, "type": "SCALAR"},
       {"bufferView": 4, "componentType": 5126, "count": 2, "type": "VEC3"}],
     "images": [{"bufferView": 5, "mimeType": "image/png"}],
     "samplers": [{"magFilter": 9728, "minFilter": 9729, "wrapS": 33071, "wrapT": 33648}],
     "textures": [{"sampler": 0, "source": 0}],
     "materials": [{"doubleSided": true, "pbrMetallicRoughness": {
       "baseColorFactor": [0.125, 0.25, 0.5, 0.75], "baseColorTexture": {"index": 0}}}],
     "meshes": [{"primitives": [
       {"attributes": {"POSITION": 0, "TEXCOORD_0": 1}, "indices": 2, "material": 0}]}],
     "nodes": [{"mesh": 0}],
     "animations": [{"name": "spin",
       "samplers": [{"input": 3, "output": 4, "interpolation": "LINEAR"}],
       "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}}]}],
     "scenes": [{"nodes": [0]}],
     "scene": 0}
        """.trimIndent(),
        bin {
            f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
            f32(0.25f, 0.5f, 0.75f, 0.125f, 0.375f, 0.625f)
            u16(0, 2, 1)
            // Two pad bytes: the animation's keyframe accessor is 32-bit and must start four-byte
            // aligned, which the six bytes of unsigned-short indices before it would otherwise break.
            u8(0, 0)
            f32(0f, 1f)
            f32(0f, 0f, 0f, 4f, 8f, 16f)
            raw(transparentEdgePng)
        },
    )
}

/** Where the embedded PNG starts: 36 position + 24 texcoord + 6 index + 2 pad + 8 input + 24 output. */
private const val PNG_BUFFER_VIEW_OFFSET = 100

/**
 * The same triangle bound to a two-joint rig: `JOINTS_0`/`WEIGHTS_0` per vertex, a skin whose two joint
 * nodes the default scene reaches, and an inverse bind matrix per joint that is not the identity.
 *
 * The inverse bind matrices are `diag(2, 4, 8)` with a translation column rather than identities, and
 * the two joints differ from each other, so a palette built from the wrong node, in the wrong order, or
 * with the inverse bind matrix skipped produces different bytes rather than the same ones.
 */
private fun skinnedTriangleGlb(): ByteArray = glb(
    """
    {"asset": {"version": "2.0"},
     "buffers": [{"byteLength": 232}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 12},
       {"buffer": 0, "byteOffset": 48, "byteLength": 48},
       {"buffer": 0, "byteOffset": 96, "byteLength": 6},
       {"buffer": 0, "byteOffset": 104, "byteLength": 128}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5121, "count": 3, "type": "VEC4"},
       {"bufferView": 2, "componentType": 5126, "count": 3, "type": "VEC4"},
       {"bufferView": 3, "componentType": 5123, "count": 3, "type": "SCALAR"},
       {"bufferView": 4, "componentType": 5126, "count": 2, "type": "MAT4"}],
     "materials": [{"doubleSided": true, "pbrMetallicRoughness": {
       "baseColorFactor": [0.125, 0.25, 0.5, 0.75]}}],
     "meshes": [{"primitives": [
       {"attributes": {"POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2}, "indices": 3, "material": 0}]}],
     "skins": [{"joints": [1, 2], "inverseBindMatrices": 4}],
     "nodes": [
       {"mesh": 0, "skin": 0},
       {"translation": [1.0, 0.0, 0.0], "children": [2]},
       {"translation": [0.0, 2.0, 0.0]}],
     "scenes": [{"nodes": [0, 1]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        u8(0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0)
        f32(0.75f, 0.25f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 0.25f, 0.75f, 0f, 0f)
        u16(0, 2, 1)
        u8(0, 0)
        // Column-major, as glTF stores a mat4: diag(2, 4, 8) with a translation, then diag(1, 3, 5)
        // with a different one.
        f32(2f, 0f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 0f, 8f, 0f, 6f, 7f, 9f, 1f)
        f32(1f, 0f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 5f, 0f, 11f, 13f, 17f, 1f)
    },
)

/**
 * The same triangle with no `TEXCOORD_0`, no image and no texture — the primitive an override cannot
 * legitimately texture, because there are no coordinates to sample it with.
 */
private fun untexturedTriangleGlb(): ByteArray = glb(
    """
    {"asset": {"version": "2.0"},
     "buffers": [{"byteLength": 42}],
     "bufferViews": [
       {"buffer": 0, "byteOffset": 0, "byteLength": 36},
       {"buffer": 0, "byteOffset": 36, "byteLength": 6}],
     "accessors": [
       {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
       {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
     "materials": [{"doubleSided": true, "pbrMetallicRoughness": {
       "baseColorFactor": [0.125, 0.25, 0.5, 0.75]}}],
     "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "material": 0}]}],
     "nodes": [{"mesh": 0}],
     "scenes": [{"nodes": [0]}]}
    """.trimIndent(),
    bin {
        f32(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        u16(0, 2, 1)
    },
)

private const val GLB_MAGIC = 0x46546C67
private const val GLB_VERSION = 2
private const val JSON_CHUNK_TYPE = 0x4E4F534A
private const val BIN_CHUNK_TYPE = 0x004E4942
private const val JSON_PAD_BYTE = 0x20

/**
 * [json] and [binChunk] wrapped in a glTF 2.0 binary container: the 12-byte header, a JSON chunk padded
 * to a four-byte boundary with `0x20`, and a zero-padded BIN chunk. The declared total length is the
 * real one, since `scanGlb` requires exact equality. RenG owns a GLB decoder and no encoder, so there
 * is nothing else to build one of these with.
 */
private fun glb(json: String, binChunk: ByteArray): ByteArray {
    val jsonBytes = json.encodeToByteArray()
    val jsonPadding = (4 - jsonBytes.size % 4) % 4
    val binPadding = (4 - binChunk.size % 4) % 4
    return bin {
        u32(GLB_MAGIC, GLB_VERSION, 12 + 8 + jsonBytes.size + jsonPadding + 8 + binChunk.size + binPadding)
        u32(jsonBytes.size + jsonPadding, JSON_CHUNK_TYPE)
        raw(jsonBytes)
        repeat(jsonPadding) { u8(JSON_PAD_BYTE) }
        u32(binChunk.size + binPadding, BIN_CHUNK_TYPE)
        raw(binChunk)
        repeat(binPadding) { u8(0) }
    }
}

/** Little-endian, because glTF fixes the BIN chunk's byte order as little-endian. */
private class BinWriter {
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

    fun build(): ByteArray = written.toByteArray()
}

private fun bin(write: BinWriter.() -> Unit): ByteArray = BinWriter().apply(write).build()

/**
 * The programs `createInternalGlState` compiles at setup: composite, sticker, ground and — since
 * ADR 0034's phase 5 — label and icon. Phase 5 draws two textures and therefore needs two programs;
 * a sprite atlas carries coverage where a glyph atlas carries a distance field, so one program
 * cannot read both.
 */
/**
 * The composite, sticker, ground, label and icon programs — **plus the ground's displacing twin**,
 * which Cycle E-terrain compiles beside the flat one so that a frame with no terrain runs the exact
 * program three releases shipped rather than a displacing one disabled by a zero uniform.
 *
 * The globe ground's two are not here: that pipeline is compiled on the first globe frame that
 * carries ground, and this renderer draws none.
 */
private const val INTERNAL_PIPELINE_PROGRAMS: Int = 7
