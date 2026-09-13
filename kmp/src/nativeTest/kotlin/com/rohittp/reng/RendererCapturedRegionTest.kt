package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_ACTIVE_TEXTURE
import com.rohittp.reng.internal.gl.GL_TEXTURE0
import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * ADR 0054: every GL write a draw makes must happen inside the captured region.
 *
 * Native-only for the reason the rest of this package is — a frame that uploads a ground tile needs
 * a real rasterised tile, and `androidHostTest` resolves skiko without its native library.
 *
 * These assert **order**, not state, and that is deliberate. `RecordingGlBinding` answers queries
 * from static maps its write methods never mutate, so a capture/restore round trip over the fake
 * passes whatever the writes did; the ordering it records is faithful.
 */
class RendererCapturedRegionTest {
    @Test
    fun noTextureIsBoundBeforeTheDrawHasCapturedTheStateItWillOverwrite() = runTest {
        val binding = styleGlBinding()
        val renderer = capturingRenderer(binding)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))
        binding.log.clear()

        renderer.draw(frame, target)

        // Before ADR 0054 this frame bound four textures -- one ground-tile upload per tile -- before
        // the capture began, so the capture recorded RenG's own texture as the host's and the
        // restore faithfully put RenG's texture back. The host's binding was lost on every frame
        // that uploaded anything.
        val firstBind = binding.log.indexOfFirst { it.startsWith("bindTexture") }
        val captureStart = binding.log.indexOfFirst { it.startsWith(CAPTURE_MARKER_PREFIX) }
        assertTrue(captureStart >= 0, "the draw must capture at all: ${binding.log.take(8)}")
        assertTrue(
            firstBind > captureStart,
            "a texture was bound at $firstBind, before the capture at $captureStart",
        )
        frame.close()
    }

    @Test
    fun theDrawCapturesOnceRatherThanOncePerNestedRegion() = runTest {
        val binding = styleGlBinding()
        val renderer = capturingRenderer(binding)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))
        binding.log.clear()

        renderer.draw(frame, target)

        // `drawFrame` opens a region of its own for its other callers and finds this one already
        // open. Capturing twice would be correct and paid twice, which is the whole reason
        // withCapturedGlState joins instead (ADR 0054).
        assertEquals(
            1,
            binding.log.count { it.startsWith(CAPTURE_MARKER_PREFIX) },
            "the draw must capture exactly once",
        )
        frame.close()
    }

    @Test
    fun aDrawLeavesTheHostsActiveTextureUnitAsItFoundIt() = runTest {
        val binding = styleGlBinding()
        // A distinctive unit the frame itself never selects, so "the frame put it back" and "the
        // frame happened to end there" are different observations.
        binding.integers[GL_ACTIVE_TEXTURE] = intArrayOf(GL_TEXTURE0 + 3)
        val renderer = capturingRenderer(binding)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))
        binding.log.clear()

        renderer.draw(frame, target)

        assertEquals(
            "activeTexture(0x84C3)",
            binding.log.last { it.startsWith("activeTexture") },
            "a draw must reinstate the unit the host had active",
        )
        frame.close()
    }

    @Test
    fun aDrawnFrameDoesNotPayAFixedTextureWalkOnTopOfItsOwnWork() = runTest {
        val binding = styleGlBinding()
        val renderer = capturingRenderer(binding)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))
        binding.log.clear()

        renderer.draw(frame, target)

        // Measured either side of ADR 0055 on this frame: 258 GL calls became 177, of which queries
        // fell from 68 to 42 -- and `glGetIntegerv` is the expensive half, since it can force driver
        // synchronisation where a bind is a pointer write.
        //
        // A ceiling rather than an exact count, because an unrelated pipeline change should be free
        // to move these by a few. The numbers it separates are not close: the fixed walk alone made
        // 32 activeTexture calls and 15 bindSampler calls on every frame whatever it drew.
        val activeTexture = binding.log.count { it.startsWith("activeTexture") }
        val bindSampler = binding.log.count { it.startsWith("bindSampler") }
        assertTrue(activeTexture <= 12, "a fixed texture walk is back: $activeTexture activeTexture calls")
        assertTrue(bindSampler <= 6, "a fixed texture walk is back: $bindSampler bindSampler calls")
        frame.close()
    }

    @Test
    fun aDrawnFrameNeverWalksTextureUnitsItDidNotWrite() = runTest {
        val binding = styleGlBinding()
        val renderer = capturingRenderer(binding)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))
        binding.log.clear()

        renderer.draw(frame, target)

        // ADR 0055. The fixed walk made unit 14 active on every frame; this frame uses units 0 and 1.
        assertEquals(
            0,
            binding.log.count { it == "activeTexture(0x84CE)" },
            "unit 14 was walked on a frame that never touched it",
        )
        frame.close()
    }
}

/**
 * The indexed uniform-buffer query, which `captureGlState` is the only caller of.
 *
 * `GL_DRAW_FRAMEBUFFER_BINDING` looks like the obvious marker — it is the first thing capture reads
 * — but the lifecycle driver queries it too when it checks the target is complete, so counting it
 * counts more than captures.
 */
private const val CAPTURE_MARKER_PREFIX: String = "getIntegeri_v"

private fun capturingRenderer(binding: RecordingGlBinding): Renderer = createRenderer(
    RendererConfiguration(
        outputPixelSize = OutputPixelSize(64, 64),
        transport = TileTransport(),
        store = RecordingStyleStore(),
        basemapStyle = ResourceLocator(STYLE_URL),
    ),
    binding,
    RenderContextProbe { RenderContextIdentity(1L) },
)
