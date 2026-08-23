package com.rohittp.reng.internal.gl

import com.rohittp.reng.Camera
import com.rohittp.reng.FramebufferName
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sentinel distinct from both the offscreen surface's own framebuffer and target (9). */
private const val ORIGINAL_DRAW_FRAMEBUFFER: Int = 777

/** What the fake answers every `GL_TEXTURE_BINDING_2D` query with, i.e. what a restore must write back. */
private const val INHERITED_TEXTURE_NAME: Int = 4242

/** Consumer texture names, chosen so none of them can be mistaken for [INHERITED_TEXTURE_NAME]. */
private const val CONSUMER_TEXTURE_BASE_NAME: Int = 500

private fun frameShaderPair(): ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n",
    fragmentSource = "#version 300 es\nprecision highp float;\nout vec4 rengOut;\nvoid main() {\n    rengOut = vec4(1.0);\n}\n",
)

private fun frameGeometry(): Geometry = Geometry(
    topLeft = Vector3(1.0, -1.0, 10.0),
    bottomRight = Vector3(-1.0, 1.0, 0.0),
    shaderPair = frameShaderPair(),
)

private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

class GlFrameDrawerTest {
    /**
     * DEVIATION FROM BRIEF (sanctioned, discovered while running this test red-then-green): the
     * brief's third assertion required the composite pass's rebind to the caller's target to be
     * strictly *after* [contentIndex]. With the reference `drawFrame` body, that rebind is the very
     * first call the composite pass issues once `content.draw` returns, so it lands at exactly
     * `contentIndex` (proven by an instrumented run: both indices were `96`), so this uses `>=`.
     *
     * The `>=` is safe rather than a loophole: `indexOfFirst` scans the whole log from position 0,
     * not forward from `contentIndex`, yet the framebuffer id it searches for
     * (`world.target.value.toInt()`, `9`) is structurally unreserved during setup.
     * [RecordingGlBinding]'s shared name counter is consumed 1-3 by `createOffscreenSurface`
     * (colour texture, depth renderbuffer, framebuffer) and 4-8 by `createCompositePipeline`
     * (vertex shader, fragment shader, program, vertex array, vertex buffer) — see
     * `drawWorld()` below — so `9` is never generated before `drawFrame` runs, and `drawFrame`
     * itself only ever binds it once, at the composite pass's rebind. The first (and only) match
     * `indexOfFirst` can find is therefore that rebind, which cannot occur before `content.draw`
     * returns.
     */
    @Test fun theOffscreenSurfaceIsClearedBeforeContentAndCompositedAfterIt() {
        val world = drawWorld()
        var contentIndex = -1
        world.draw { contentIndex = world.binding.log.size }
        val clearIndex = world.binding.log.indexOfFirst { it.startsWith("clear(") }
        val compositeIndex = world.binding.log.indexOfFirst { it.startsWith("drawArrays") }
        assertTrue(clearIndex in 0 until contentIndex)
        assertTrue(contentIndex < compositeIndex)
        assertTrue(
            world.binding.log.indexOfFirst { it == "bindFramebuffer(0x8CA9,${world.target.value.toInt()})" } >=
                contentIndex,
        )
    }

    /**
     * ADR 0025: the depth comparison is `GL_GEQUAL`, not `GL_GREATER`. A fragment at exactly the
     * ground's depth -- an altitude-0 `Geometry` or map-anchored sticker over the basemap -- must
     * pass and paint, with draw order deciding the tie. `GL_GREATER` discards it silently.
     */
    @Test fun reverseZClearsDepthToZeroAndTestsGreaterOrEqual() {
        val world = drawWorld()
        world.draw { }
        assertTrue(world.binding.log.any { it == "clearDepthf(0.0)" })
        assertTrue(
            world.binding.log.any { it == "depthFunc(0x206)" },
            "ADR 0025 requires GL_GEQUAL so coplanar map content is not silently deleted",
        )
        assertTrue(
            world.binding.log.none { it == "depthFunc(0x204)" },
            "GL_GREATER must not be set anywhere in a frame",
        )
    }

    /**
     * ADR 0023's Restore Set names "the bindings on the units RenG uses", and [drawGeometry] uses
     * up to [MAXIMUM_CONSUMER_TEXTURES] of them. Capturing only the composite pass's single unit
     * left units 1..14 clobbered and never restored -- a shipped violation of the very ADR the
     * restore machinery exists to satisfy, invisible to every test because the real-context round
     * trip drives [drawFrame] with a clear-only lambda that binds nothing.
     *
     * The fake answers every `GL_TEXTURE_BINDING_2D` query with [INHERITED_TEXTURE_NAME], so this
     * asserts the restore genuinely rebinds that value on every unit the frame could have touched,
     * with the geometry's own texture names deliberately distinct from it.
     */
    @Test fun everyTextureUnitAGeometryCanBindIsCapturedAndRestored() {
        val world = drawWorld()
        val geometryPipeline = (
            createGeometryPipeline(world.binding, ShaderDialect.GLES, GlProgramCache(), frameShaderPair())
                as GeometryPipelineResult.Created
            ).pipeline
        val consumerTextures = (0 until MAXIMUM_CONSUMER_TEXTURES)
            .associate { index -> "uTexture$index" to CONSUMER_TEXTURE_BASE_NAME + index }
        val scene = Scene(
            outputPixelSize = OutputPixelSize(width = 64, height = 64),
            frameIndex = 0L,
            geometries = listOf(
                SceneGeometry(
                    geometry = frameGeometry(),
                    pipeline = geometryPipeline,
                    consumerUniforms = emptyMap(),
                    consumerTextures = consumerTextures,
                ),
            ),
        )
        val camera = (
            resolveMercatorCamera(
                camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 10.0, bearing = 0.0, pitch = 0.0),
                outputPixelSize = OutputPixelSize(width = 64, height = 64),
            ) as SpatialOutcome.Success
            ).value
        val stickerPipeline = (
            createStickerPipeline(world.binding, ShaderDialect.GLES, GlProgramCache())
                as StickerPipelineResult.Created
            ).pipeline
        val groundPipeline = (
            createGroundPipeline(world.binding, ShaderDialect.GLES, GlProgramCache())
                as GroundPipelineResult.Created
            ).pipeline
        world.binding.log.clear()

        val failure = drawFrame(
            world.binding,
            world.profile,
            world.surface,
            world.pipeline,
            world.target,
            SceneContent(camera, scene, stickerPipeline, groundPipeline),
        )

        assertNull(failure)
        for (unitIndex in 0 until MAXIMUM_CONSUMER_TEXTURES) {
            val activate = world.binding.log.indexOfLast { it == "activeTexture(${hex(GL_TEXTURE0 + unitIndex)})" }
            assertTrue(activate >= 0, "unit $unitIndex must be made active to be captured and restored")
            assertEquals(
                "bindTexture(${hex(GL_TEXTURE_2D)},$INHERITED_TEXTURE_NAME)",
                world.binding.log[activate + 1],
                "unit $unitIndex must be restored to the binding the caller left on it",
            )
        }
    }

    @Test fun srgbIsSetExplicitlyAndRestoredRatherThanInherited() {
        val world = drawWorld(srgbSupported = true, srgbInitiallyEnabled = true)
        world.draw { }
        assertTrue(world.binding.log.any { it == "disable(0x8DB9)" })
        assertEquals("enable(0x8DB9)", world.binding.log.last { "0x8DB9" in it })
    }

    @Test fun anEsContextWithoutWriteControlNeverTouchesSrgb() {
        val world = drawWorld(srgbSupported = false)
        world.draw { }
        assertTrue(world.binding.log.none { "0x8DB9" in it })
    }

    /**
     * DEVIATION FROM BRIEF (sanctioned): the brief's version of this test captured
     * `captureGlState(...)` before and after `world.draw { }` and asserted the two snapshots equal.
     * Against [RecordingGlBinding] that assertion cannot fail: its query maps (`integers`/`floats`/
     * `booleans`/`enabled`) are static and are never mutated by its write methods, so any
     * capture/restore/capture round trip is trivially equal whether or not restoration actually ran
     * (see `GlStateSnapshotTest`, lines 77-83, and its note that deleting a real restore line left
     * every such round-trip test green). This is the identical defect that forced two fix rounds on
     * Task 12, so it is corrected here rather than reproduced.
     *
     * Instead, [drawWorld] seeds the draw-framebuffer binding to a sentinel ([ORIGINAL_DRAW_FRAMEBUFFER])
     * chosen to differ from both the offscreen surface's own framebuffer and the composite target
     * (9), and this test asserts the restore call for that sentinel appears in the log strictly
     * after the draw's own last framebuffer bind — proving `drawFrame` restores the caller's GL
     * state via `withCapturedGlState` rather than merely returning successfully.
     */
    @Test fun theFullStateSetIsIdenticalBeforeAndAfterADraw() {
        val world = drawWorld()
        world.binding.log.clear()
        assertNull(world.draw { })
        val lastWorkWrite = world.binding.log.indexOfLast {
            it == "bindFramebuffer(0x8CA9,${world.target.value.toInt()})"
        }
        val restoreWrite = world.binding.log.indexOfLast {
            it == "bindFramebuffer(0x8CA9,$ORIGINAL_DRAW_FRAMEBUFFER)"
        }
        assertTrue(lastWorkWrite >= 0, "the composite pass must bind the caller's target")
        assertTrue(restoreWrite >= 0, "the original draw framebuffer binding must be restored")
        assertTrue(lastWorkWrite < restoreWrite, "restore must come after the draw's own work")
    }

    /**
     * DEVIATION FROM BRIEF (sanctioned): same correction as
     * [theFullStateSetIsIdenticalBeforeAndAfterADraw] — the brief's snapshot-equality assertion is
     * vacuous against [RecordingGlBinding]. This instead asserts the sentinel framebuffer restore
     * write directly, proving the `finally` inside `withCapturedGlState` still restores state when
     * the draw pass reports a driver error.
     */
    @Test fun aDriverErrorDuringTheDrawBecomesATypedGpuFailureAndStillRestores() {
        val world = drawWorld()
        world.binding.errorQueue = mutableListOf(GL_NO_ERROR, GL_INVALID_OPERATION, GL_NO_ERROR)
        world.binding.log.clear()
        val failure = assertNotNull(world.draw { })
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        val lastWorkWrite = world.binding.log.indexOfLast {
            it == "bindFramebuffer(0x8CA9,${world.target.value.toInt()})"
        }
        val restoreWrite = world.binding.log.indexOfLast {
            it == "bindFramebuffer(0x8CA9,$ORIGINAL_DRAW_FRAMEBUFFER)"
        }
        assertTrue(lastWorkWrite >= 0, "the composite pass must bind the caller's target")
        assertTrue(restoreWrite >= 0, "the original draw framebuffer binding must be restored")
        assertTrue(lastWorkWrite < restoreWrite, "restore must come after the draw's own work")
    }

    @Test fun aConsumerErrorPresentOnEntryIsNotReportedAsRengFailure() {
        val world = drawWorld()
        world.binding.errorQueue = mutableListOf(GL_INVALID_ENUM, GL_NO_ERROR)
        assertNull(world.draw { })
    }

    /**
     * Every producer into the offscreen surface writes premultiplied colour ([uploadTexture]
     * premultiplies [TextureContent.IMAGE], and `drawStickers`/`drawGround` pair it with
     * `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`). Blending that premultiplied source into the caller's
     * framebuffer with `GL_SRC_ALPHA` multiplies alpha a second time -- invisible under an opaque
     * basemap, wrong the moment a consumer composites RenG over their own background.
     *
     * Uses `first`, not `last`: [withCapturedGlState] restores the caller's original blend
     * function afterward, which logs a second, later `blendFuncSeparate` call (the fake's
     * un-seeded default, `GL_ZERO` on every factor) that is not the one under test here.
     */
    @Test fun theCompositeBlendsPremultipliedSourceRatherThanMultiplyingAlphaTwice() {
        val world = drawWorld()
        world.binding.log.clear()
        assertNull(world.draw { })
        val call = world.binding.log.first { it.startsWith("blendFuncSeparate(") }
        assertEquals(
            "blendFuncSeparate(${hex(GL_ONE)},${hex(GL_ONE_MINUS_SRC_ALPHA)}," +
                "${hex(GL_ONE)},${hex(GL_ONE_MINUS_SRC_ALPHA)})",
            call,
            "the offscreen colour attachment is already premultiplied; GL_SRC_ALPHA multiplies it again",
        )
    }

    private class DrawWorld(
        val binding: RecordingGlBinding,
        val profile: RenderContextProfile,
        val surface: OffscreenSurface,
        val pipeline: CompositePipeline,
        val target: FramebufferName,
    ) {
        fun draw(content: (GlBinding) -> Unit): FailureDescriptor? =
            drawFrame(binding, profile, surface, pipeline, target, GlFrameContent { content(it) })
    }

    private fun drawWorld(
        srgbSupported: Boolean = false,
        srgbInitiallyEnabled: Boolean = false,
    ): DrawWorld {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        binding.integers[GL_DRAW_FRAMEBUFFER_BINDING] = intArrayOf(ORIGINAL_DRAW_FRAMEBUFFER)
        binding.integers[GL_TEXTURE_BINDING_2D] = intArrayOf(INHERITED_TEXTURE_NAME)
        binding.enabled[GL_FRAMEBUFFER_SRGB] = srgbInitiallyEnabled

        val profile = RenderContextProfile(
            dialect = ShaderDialect.GLES,
            version = GlVersion(3, 2),
            vendorName = "Mesa",
            rendererName = "llvmpipe (LLVM 20.1.2, 256 bits)",
            shadingLanguageVersionText = "OpenGL ES GLSL ES 3.20",
            supportsEs3Compatibility = false,
            supportsSrgbWriteControl = srgbSupported,
            maxTextureSize = 16384,
            maxColorAttachments = 8,
            maxCombinedTextureImageUnits = 192,
        )

        val deriver = ResourceKeyDeriver()
        val descriptor = offscreenSurfaceDescriptorFor(OutputPixelSize(width = 64, height = 64))
        val surface = (
            createOffscreenSurface(binding, profile, deriver.offscreenSurface(descriptor).key, descriptor)
                as OffscreenSurfaceResult.Created
            ).surface
        val pipeline = (
            createCompositePipeline(binding, profile.dialect, GlProgramCache(), deriver)
                as CompositePipelineResult.Created
            ).pipeline

        return DrawWorld(binding, profile, surface, pipeline, FramebufferName(9u))
    }
}
