package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlStateSnapshotTest {
    @Test fun activeTextureIsReadFirstAndReinstatedAfterThePerUnitLoop() {
        val binding = populatedBinding()
        captureGlState(binding, esProfile(), textureUnitCount = 2)
        val activeReads = binding.log.indexOfFirst { it == "getIntegerv(0x84E0)" }
        val firstUnitSwitch = binding.log.indexOfFirst { it == "activeTexture(0x84C0)" }
        val firstBindingRead = binding.log.indexOfFirst { it == "getIntegerv(0x8069)" }
        assertEquals(0, activeReads)
        assertTrue(activeReads < firstUnitSwitch)
        assertTrue(firstUnitSwitch < firstBindingRead)
        assertEquals("activeTexture(0x84C3)", binding.log.last { it.startsWith("activeTexture") })
    }

    @Test fun restoreReinstatesTheActiveUnitLast() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 2)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertEquals("activeTexture(0x84C3)", binding.log.last())
    }

    @Test fun theElementArrayBufferBindingIsNeverQueried() {
        val binding = populatedBinding()
        captureGlState(binding, esProfile(), textureUnitCount = 1)
        assertTrue(binding.log.none { it == "getIntegerv(0x8895)" })
        assertTrue(binding.log.any { it == "getIntegerv(0x8894)" })
    }

    @Test fun desktopOnlyTokensAreQueriedOnlyOnADesktopContext() {
        val esBinding = populatedBinding()
        val esSnapshot = captureGlState(esBinding, esProfile(), textureUnitCount = 1)
        assertNull(esSnapshot.drawBuffer)
        assertNull(esSnapshot.lineSmoothEnabled)
        assertTrue(esBinding.log.none { it == "getIntegerv(0xC01)" })
        assertTrue(esBinding.log.none { it == "isEnabled(0xB20)" })

        val desktopBinding = populatedBinding()
        val desktopSnapshot = captureGlState(desktopBinding, desktopProfile(), textureUnitCount = 1)
        assertEquals(GL_BACK, desktopSnapshot.drawBuffer)
        assertEquals(false, desktopSnapshot.lineSmoothEnabled)
    }

    @Test fun theUnpackAlignmentDefaultIsFourNotOne() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        assertEquals(4, snapshot.unpackAlignment)
        assertEquals(4, snapshot.packAlignment)
        assertEquals(GL_UNPACK_ALIGNMENT_DEFAULT, snapshot.unpackAlignment)
    }

    @Test fun captureRestoreCaptureIsIdenticalOnTheFake() {
        val binding = populatedBinding()
        val first = captureGlState(binding, desktopProfile(), textureUnitCount = 3)
        restoreGlState(binding, first)
        val second = captureGlState(binding, desktopProfile(), textureUnitCount = 3)
        assertEquals(first, second)
    }

    @Test fun theSnapshotCoversEverySetMemberTheSpecificationNames() {
        val snapshot = captureGlState(populatedBinding(), desktopProfile(), textureUnitCount = 1)
        assertEquals(listOf(0f, 0f, 0f, 0f), snapshot.blendColour)
        assertEquals(listOf(0f, 1f), snapshot.depthRange)
        assertEquals(listOf(0, 0, 64, 64), snapshot.viewport)
        assertEquals(listOf(0, 0, 64, 64), snapshot.scissorBox)
        assertEquals(listOf(true, true, true, true), snapshot.colourWriteMask)
        assertEquals(listOf(0f, 0f, 0f, 0f), snapshot.colourClearValue)
        assertEquals(1f, snapshot.depthClearValue)
    }

    // The tests below assert directly against the fake's call log after restoreGlState runs,
    // rather than via a capture/restore/capture round trip: RecordingGlBinding's query maps are
    // static and are never mutated by its write methods, so a round trip on this fake proves
    // capture is deterministic but cannot prove any individual restore call actually happened
    // (verified by deleting the arrayBuffer restore line and observing every other test, including
    // captureRestoreCaptureIsIdenticalOnTheFake, stay green). Asserting on the log is the only way
    // this fake can prove a field saved during capture was actually written back during restore.

    @Test fun restoreWritesBackFramebufferAndBufferBindings() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("bindFramebuffer(0x8CA9,11)"))
        assertTrue(binding.log.contains("bindFramebuffer(0x8CA8,12)"))
        assertTrue(binding.log.contains("bindRenderbuffer(0x8D41,13)"))
        assertTrue(binding.log.contains("useProgram(21)"))
        assertTrue(binding.log.contains("bindVertexArray(31)"))
        assertTrue(binding.log.contains("bindBuffer(0x8892,41)"))
        assertTrue(binding.log.contains("bindBuffer(0x88EC,42)"))
        assertTrue(binding.log.contains("bindBuffer(0x8A11,43)"))
        assertTrue(binding.log.contains("bindBufferBase(0x8A11,0,44)"))
    }

    /**
     * `glBindBufferBase(GL_UNIFORM_BUFFER, n, b)` writes an indexed binding the generic
     * `GL_UNIFORM_BUFFER_BINDING` query never reaches. This test proves the corrected Restore Set
     * captures and restores that indexed binding: a caller's own UBO binding at
     * [RENG_JOINT_UNIFORM_BINDING_POINT] must survive a RenG frame that rebinds it mid-frame, the
     * same way every other member of the Restore Set survives.
     */
    @Test fun theIndexedUniformBufferBindingIsCapturedAndRestored() {
        val binding = RecordingGlBinding().apply { indexedUniformBuffer[0] = 77 }
        withCapturedGlState(binding, esProfile(), FRAME_TEXTURE_UNIT_COUNT) {
            binding.bindBufferBase(GL_UNIFORM_BUFFER, 0, 5)
        }
        assertEquals(77, binding.indexedUniformBuffer[0], "a caller's own UBO binding must survive a RenG frame")
    }

    @Test fun restoreWritesBackBlendState() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("enable(0xBE2)"))
        assertTrue(binding.log.contains("blendFuncSeparate(0x302,0x303,0x1,0x0)"))
        assertTrue(binding.log.contains("blendEquationSeparate(0x8006,0x8006)"))
        assertTrue(binding.log.contains("blendColor(0.0,0.0,0.0,0.0)"))
    }

    @Test fun restoreWritesBackDepthState() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("enable(0xB71)"))
        assertTrue(binding.log.contains("depthFunc(0x201)"))
        assertTrue(binding.log.contains("depthMask(true)"))
        assertTrue(binding.log.contains("depthRangef(0.0,1.0)"))
        assertTrue(binding.log.contains("clearDepthf(1.0)"))
    }

    @Test fun restoreWritesBackRasterizerAndScissorState() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("disable(0xB44)"))
        assertTrue(binding.log.contains("cullFace(0x405)"))
        assertTrue(binding.log.contains("frontFace(0x901)"))
        assertTrue(binding.log.contains("viewport(0,0,64,64)"))
        assertTrue(binding.log.contains("disable(0xC11)"))
        assertTrue(binding.log.contains("scissor(0,0,64,64)"))
    }

    @Test fun restoreWritesBackColourWriteMaskAndClearColour() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("colorMask(true,true,true,true)"))
        assertTrue(binding.log.contains("clearColor(0.0,0.0,0.0,0.0)"))
    }

    @Test fun restoreWritesBackPixelStoreState() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("pixelStorei(0xCF5,4)"))
        assertTrue(binding.log.contains("pixelStorei(0xCF2,0)"))
        assertTrue(binding.log.contains("pixelStorei(0xCF3,0)"))
        assertTrue(binding.log.contains("pixelStorei(0xCF4,0)"))
        assertTrue(binding.log.contains("pixelStorei(0xD05,4)"))
    }

    @Test fun restoreWritesBackDialectGatedState() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, desktopProfile(), textureUnitCount = 1)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("enable(0x8DB9)"))
        assertTrue(binding.log.contains("drawBuffers(1)"))
        assertEquals(GL_BACK, binding.lastDrawBuffers.single())
        assertTrue(binding.log.contains("disable(0xB20)"))
    }

    @Test fun restoreWritesBackEveryTextureUnitsBindings() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 2)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertTrue(binding.log.contains("activeTexture(0x84C0)"))
        assertTrue(binding.log.contains("activeTexture(0x84C1)"))
        assertTrue(binding.log.contains("bindTexture(0xDE1,7)"))
        assertTrue(binding.log.contains("bindSampler(0,2)"))
        assertTrue(binding.log.contains("bindSampler(1,2)"))
    }

    /**
     * [withCapturedGlState] wraps the block in a `finally`, so its restore guarantee must hold
     * even when the block throws. A round trip against [captureGlState] alone cannot prove this:
     * `RecordingGlBinding`'s query maps are static, so a second capture equals the first whether or
     * not restore ever ran. Instead this asserts on the log, using [populatedBinding]'s seeded
     * original draw-framebuffer handle (11) against a block-written handle (7) that differs from
     * it, so the restore write is textually distinguishable from the block's own write, and
     * ordered after it.
     */
    @Test fun theCapturedStateGuardRestoresEvenWhenTheBlockThrows() {
        val binding = populatedBinding()
        val profile = esProfile()
        binding.log.clear()

        val thrown = assertFailsWith<IllegalStateException> {
            withCapturedGlState(binding, profile, textureUnitCount = 1) {
                binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 7)
                throw IllegalStateException("frame content failed")
            }
        }
        assertEquals("frame content failed", thrown.message)

        val blockWrite = binding.log.indexOfFirst { it == "bindFramebuffer(0x8CA9,7)" }
        val restoreWrite = binding.log.indexOfLast { it == "bindFramebuffer(0x8CA9,11)" }
        assertTrue(blockWrite >= 0, "the block's own write must reach the log")
        assertTrue(
            restoreWrite >= 0,
            "the finally must restore the original draw framebuffer binding (11), distinct from the block's 7",
        )
        assertTrue(
            blockWrite < restoreWrite,
            "the restore write must appear after the block's write, proving it came from the finally",
        )
    }

    /**
     * Seeds `integers`, `floats`, `booleans`, and `enabled` for every token [captureGlState]
     * reads, across both [esProfile] and [desktopProfile]. The active texture unit is seeded at
     * `GL_TEXTURE0 + 3` rather than `GL_TEXTURE0` so the reinstatement assertions are not testing
     * zero against zero.
     */
    private fun populatedBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        integers[GL_ACTIVE_TEXTURE] = intArrayOf(GL_TEXTURE0 + 3)
        integers[GL_TEXTURE_BINDING_2D] = intArrayOf(7)
        integers[GL_SAMPLER_BINDING] = intArrayOf(2)
        integers[GL_DRAW_FRAMEBUFFER_BINDING] = intArrayOf(11)
        integers[GL_READ_FRAMEBUFFER_BINDING] = intArrayOf(12)
        integers[GL_RENDERBUFFER_BINDING] = intArrayOf(13)
        integers[GL_CURRENT_PROGRAM] = intArrayOf(21)
        integers[GL_VERTEX_ARRAY_BINDING] = intArrayOf(31)
        integers[GL_ARRAY_BUFFER_BINDING] = intArrayOf(41)
        integers[GL_PIXEL_UNPACK_BUFFER_BINDING] = intArrayOf(42)
        integers[GL_UNIFORM_BUFFER_BINDING] = intArrayOf(43)
        indexedUniformBuffer[RENG_JOINT_UNIFORM_BINDING_POINT] = 44
        integers[GL_BLEND_SRC_RGB] = intArrayOf(GL_SRC_ALPHA)
        integers[GL_BLEND_DST_RGB] = intArrayOf(GL_ONE_MINUS_SRC_ALPHA)
        integers[GL_BLEND_SRC_ALPHA] = intArrayOf(GL_ONE)
        integers[GL_BLEND_DST_ALPHA] = intArrayOf(GL_ZERO)
        integers[GL_BLEND_EQUATION_RGB] = intArrayOf(GL_FUNC_ADD)
        integers[GL_BLEND_EQUATION_ALPHA] = intArrayOf(GL_FUNC_ADD)
        integers[GL_DEPTH_FUNC] = intArrayOf(GL_LESS)
        integers[GL_CULL_FACE_MODE] = intArrayOf(GL_BACK)
        integers[GL_FRONT_FACE] = intArrayOf(GL_CCW)
        integers[GL_VIEWPORT] = intArrayOf(0, 0, 64, 64)
        integers[GL_SCISSOR_BOX] = intArrayOf(0, 0, 64, 64)
        integers[GL_UNPACK_ALIGNMENT] = intArrayOf(GL_UNPACK_ALIGNMENT_DEFAULT)
        integers[GL_UNPACK_ROW_LENGTH] = intArrayOf(0)
        integers[GL_UNPACK_SKIP_ROWS] = intArrayOf(0)
        integers[GL_UNPACK_SKIP_PIXELS] = intArrayOf(0)
        integers[GL_PACK_ALIGNMENT] = intArrayOf(GL_PACK_ALIGNMENT_DEFAULT)
        integers[GL_DRAW_BUFFER] = intArrayOf(GL_BACK)

        floats[GL_BLEND_COLOR] = floatArrayOf(0f, 0f, 0f, 0f)
        floats[GL_DEPTH_RANGE] = floatArrayOf(0f, 1f)
        floats[GL_DEPTH_CLEAR_VALUE] = floatArrayOf(1f)
        floats[GL_COLOR_CLEAR_VALUE] = floatArrayOf(0f, 0f, 0f, 0f)

        booleans[GL_DEPTH_WRITEMASK] = booleanArrayOf(true)
        booleans[GL_COLOR_WRITEMASK] = booleanArrayOf(true, true, true, true)

        enabled[GL_BLEND] = true
        enabled[GL_DEPTH_TEST] = true
        enabled[GL_CULL_FACE] = false
        enabled[GL_SCISSOR_TEST] = false
        enabled[GL_FRAMEBUFFER_SRGB] = true
        enabled[GL_LINE_SMOOTH] = false
    }

    private fun esProfile(): RenderContextProfile = RenderContextProfile(
        dialect = ShaderDialect.GLES,
        version = GlVersion(3, 2),
        vendorName = "Mesa",
        rendererName = "llvmpipe (LLVM 20.1.2, 256 bits)",
        shadingLanguageVersionText = "OpenGL ES GLSL ES 3.20",
        supportsEs3Compatibility = false,
        supportsSrgbWriteControl = false,
        maxTextureSize = 16384,
        maxColorAttachments = 8,
        maxCombinedTextureImageUnits = 192,
    )

    private fun desktopProfile(): RenderContextProfile = esProfile().copy(
        dialect = ShaderDialect.DESKTOP,
        supportsSrgbWriteControl = true,
    )
}
