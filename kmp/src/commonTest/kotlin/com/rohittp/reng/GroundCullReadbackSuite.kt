package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_BACK
import com.rohittp.reng.internal.gl.GL_CCW
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_CULL_FACE
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_NEAREST
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_TEXTURE_MAG_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_MIN_FILTER
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.GroundPipeline
import com.rohittp.reng.internal.gl.GroundPipelineResult
import com.rohittp.reng.internal.gl.ResolvedGroundTile
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.createGroundPipeline
import com.rohittp.reng.internal.gl.deleteGroundPipeline
import com.rohittp.reng.internal.gl.drawGround
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0038's ground half, in pixels on a real driver: the globe removes back-facing ground and
 * mercator does not, and a mercator frame's pixels do not depend on what its caller left enabled.
 *
 * **Why this is not driven through `createRenderer` like [runBasemapReadbackSuite] is.**
 * `ProjectionMode.GLOBE` is still refused at frame planning, so no public call can put a globe frame
 * in front of a driver yet. This suite therefore calls [drawGround] directly, standing in for the
 * caller: it establishes exactly what `GlFrameDrawer.drawFrame` establishes scene-wide before
 * `content.draw` — `frontFace(GL_CCW)` and `cullFace(GL_BACK)` — and then varies the one thing this
 * cycle changes.
 *
 * **The fixture is one ground quad drawn twice over, once mirrored.** Mirroring the model-view-
 * projection about `x` reverses the winding of both triangles of `GROUND_QUAD`'s strip without
 * changing which pixels they cover, so the mirrored draw is a stand-in for a ground patch on the far
 * hemisphere: same area, opposite facing. A patch is the only thing a cull can act on, and pairing
 * the two windings is what makes each assertion below say something the other cannot.
 *
 * **What each case would survive, and therefore why there are four.**
 * - [assertMercatorDrawsABackFacingPatchEvenFromAHostileCullState] fails if the mercator arm's
 *   `disable` is deleted, and passes with the globe arm deleted.
 * - [assertTheGlobeRemovesABackFacingPatchFromACleanCullState] fails if the globe arm's `enable` is
 *   deleted, and passes with the mercator arm deleted. It starts from culling *disabled* on purpose:
 *   an inherited enable would otherwise do the globe's work for it.
 * - [assertTheGlobeLeavesTheNearSideByteIdentical] fails if the globe arm removes anything a
 *   mercator frame keeps — an over-broad cull that deleted front faces too would pass both of the
 *   above.
 * - [assertNoMercatorPixelMoves] fails if a mercator frame's pixels depend on the caller's cull
 *   state in either winding. That is the claim this cycle makes about the projection it is *not*
 *   changing, and it is the one that protects three shipped releases.
 *
 * **What it does not claim.** Nothing here says the far hemisphere of a real globe winds
 * consistently — task 7 owns the ground geometry, and this suite has no sphere in it. It says that
 * *given* a back-facing patch, the globe arm removes it and the mercator arm does not, which is the
 * whole of what ADR 0038 asks the ground pass to do.
 */
internal fun runGroundCullReadbackSuite(binding: GlBinding, dialect: ShaderDialect) {
    val target = createGroundCullTarget(binding)
    val texture = createOpaqueWhiteTexture(binding)
    val programs = GlProgramCache()
    val pipeline = when (val result = createGroundPipeline(binding, dialect, programs)) {
        is GroundPipelineResult.Created -> result.pipeline
        is GroundPipelineResult.Failed ->
            throw AssertionError("the ground pipeline's $dialect program did not link on this driver")
    }
    println("RenG ground-cull readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect")
    try {
        val fixture = GroundCullFixture(binding, pipeline, target, texture)
        assertMercatorDrawsABackFacingPatchEvenFromAHostileCullState(fixture)
        assertTheGlobeRemovesABackFacingPatchFromACleanCullState(fixture)
        assertTheGlobeLeavesTheNearSideByteIdentical(fixture)
        assertNoMercatorPixelMoves(fixture)
    } finally {
        deleteGroundPipeline(binding, programs, pipeline)
        binding.deleteTextures(1, intArrayOf(texture))
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * The mercator arm is load-bearing rather than decorative. The caller here leaves `GL_CULL_FACE`
 * **enabled** — which is exactly what the globe ground pass now leaves behind it, and what
 * `drawModels`' last non-`doubleSided` primitive has always left behind it — and the mercator ground
 * must draw its back-facing patch anyway, because under mercator no ground patch faces away.
 */
private fun assertMercatorDrawsABackFacingPatchEvenFromAHostileCullState(fixture: GroundCullFixture) {
    val frame = fixture.render(
        winding = GroundPatchWinding.BACK_FACING,
        projectionMode = ProjectionMode.MERCATOR,
        callerLeftCullingEnabled = true,
    )
    assertTrue(
        frame.paintedPixels >= MINIMUM_PATCH_PIXELS,
        "under mercator a back-facing ground patch must still draw, whatever the caller left " +
            "enabled: ${frame.paintedPixels} painted pixels against a floor of $MINIMUM_PATCH_PIXELS",
    )
}

/**
 * The globe arm, from a caller that left culling **disabled**, so the enable can only come from
 * [drawGround] itself.
 */
private fun assertTheGlobeRemovesABackFacingPatchFromACleanCullState(fixture: GroundCullFixture) {
    val frame = fixture.render(
        winding = GroundPatchWinding.BACK_FACING,
        projectionMode = ProjectionMode.GLOBE,
        callerLeftCullingEnabled = false,
    )
    assertEquals(
        0,
        frame.paintedPixels,
        "on a globe a back-facing ground patch must leave no pixel at all",
    )
}

/** The near side is untouched: enabling culling must remove back faces and nothing else. */
private fun assertTheGlobeLeavesTheNearSideByteIdentical(fixture: GroundCullFixture) {
    val mercator = fixture.render(
        winding = GroundPatchWinding.FRONT_FACING,
        projectionMode = ProjectionMode.MERCATOR,
        callerLeftCullingEnabled = false,
    )
    val globe = fixture.render(
        winding = GroundPatchWinding.FRONT_FACING,
        projectionMode = ProjectionMode.GLOBE,
        callerLeftCullingEnabled = false,
    )
    assertTrue(
        mercator.paintedPixels >= MINIMUM_PATCH_PIXELS,
        "the front-facing fixture must actually paint, or this case compares two empty frames: " +
            "${mercator.paintedPixels}",
    )
    assertContentEquals(
        mercator.bytes,
        globe.bytes,
        "a globe must keep every pixel of a front-facing ground patch that mercator keeps",
    )
}

/**
 * "No mercator pixel moves", asserted rather than argued: a mercator frame drawn from a caller that
 * left culling enabled must be byte-for-byte the frame drawn from one that left it disabled, in
 * **both** windings. The back-facing half is the one that fails without the explicit disable; the
 * front-facing half is the one that would still pass, which is why both are here — a case that only
 * checked real-winding ground would call the inherited state harmless and be right by luck, which is
 * exactly what ADR 0038 says this pass has been relying on.
 */
private fun assertNoMercatorPixelMoves(fixture: GroundCullFixture) {
    GroundPatchWinding.entries.forEach { winding ->
        val clean = fixture.render(winding, ProjectionMode.MERCATOR, callerLeftCullingEnabled = false)
        val hostile = fixture.render(winding, ProjectionMode.MERCATOR, callerLeftCullingEnabled = true)
        assertTrue(
            clean.paintedPixels >= MINIMUM_PATCH_PIXELS,
            "the $winding mercator frame must paint, or this case compares two empty frames",
        )
        assertContentEquals(
            clean.bytes,
            hostile.bytes,
            "a $winding mercator frame's pixels must not depend on the cull state its caller left",
        )
    }
}

/** Which way the fixture's one ground patch faces once projected. */
private enum class GroundPatchWinding { FRONT_FACING, BACK_FACING }

private class GroundCullFixture(
    private val binding: GlBinding,
    private val pipeline: GroundPipeline,
    private val target: Int,
    private val texture: Int,
) {
    /**
     * Clears to opaque black, establishes what `drawFrame` establishes scene-wide plus the caller's
     * cull enable, draws one ground patch, and reads the whole frame back.
     */
    fun render(
        winding: GroundPatchWinding,
        projectionMode: ProjectionMode,
        callerLeftCullingEnabled: Boolean,
    ): GroundCullFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target)
        binding.viewport(0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.clearColor(0f, 0f, 0f, 1f)
        binding.clear(GL_COLOR_BUFFER_BIT)

        // Exactly what `GlFrameDrawer.drawFrame` establishes before `content.draw`, which is why the
        // ground pass sets neither the mode nor the winding for itself (ADR 0038).
        binding.frontFace(GL_CCW)
        binding.cullFace(GL_BACK)
        if (callerLeftCullingEnabled) binding.enable(GL_CULL_FACE) else binding.disable(GL_CULL_FACE)
        binding.bindSampler(0, 0)

        drawGround(
            binding = binding,
            pipeline = pipeline,
            tiles = listOf(ResolvedGroundTile(modelViewProjection = matrixFor(winding), texture = texture)),
            projectionMode = projectionMode,
        )

        val bytes = ByteArray(GROUND_CULL_READBACK_PIXELS * GROUND_CULL_READBACK_PIXELS * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, bytes,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        return GroundCullFrame(bytes)
    }

    /**
     * The column-major matrix that puts `GROUND_QUAD`'s unit square on the middle of the frame.
     *
     * [GroundPatchWinding.BACK_FACING] negates the `x` scale, which reflects both triangles of the
     * strip and so reverses their winding while covering the identical set of pixels. That equality
     * of coverage is what lets every assertion above compare a count or a byte array rather than a
     * shape.
     */
    private fun matrixFor(winding: GroundPatchWinding): FloatArray {
        val scaleX = if (winding == GroundPatchWinding.BACK_FACING) -PATCH_SCALE else PATCH_SCALE
        return floatArrayOf(
            scaleX, 0f, 0f, 0f,
            0f, PATCH_SCALE, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

private class GroundCullFrame(val bytes: ByteArray) {
    /** How many pixels the ground reached, counted off the red channel of an opaque white patch. */
    val paintedPixels: Int
        get() = (0 until GROUND_CULL_READBACK_PIXELS * GROUND_CULL_READBACK_PIXELS)
            .count { (bytes[it * 4].toInt() and 0xff) > 128 }
}

internal const val GROUND_CULL_READBACK_PIXELS: Int = 64

/**
 * The unit quad is scaled to `+/-0.75` of normalised device coordinates, so it covers `0.75^2` of the
 * frame: 2,304 of 4,096 pixels. The floor below is well under that and well over zero, because the
 * only thing any case needs from it is "the patch really drew" — an exact count would be a claim
 * about a driver's fill rule rather than about RenG.
 */
private const val PATCH_SCALE: Float = 1.5f

private const val MINIMUM_PATCH_PIXELS: Int = 2000

private fun createGroundCullTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8,
        GROUND_CULL_READBACK_PIXELS, GROUND_CULL_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the ground-cull readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

/**
 * One opaque white texel, uploaded by hand rather than decoded.
 *
 * White against a black clear is the widest separation a single channel offers, and premultiplied
 * opaque white composites to white under the `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` function [drawGround]
 * establishes — so "painted" and "not painted" are 255 and 0 rather than two shades a tolerance has
 * to separate. Nothing here runs `decodePng`, so a decoder regression cannot make this suite pass.
 */
private fun createOpaqueWhiteTexture(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
        byteArrayOf(-1, -1, -1, -1),
    )
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    return texture
}
