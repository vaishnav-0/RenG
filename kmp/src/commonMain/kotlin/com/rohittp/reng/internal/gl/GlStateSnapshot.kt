package com.rohittp.reng.internal.gl

/**
 * Every array-valued member is a [List], so [GlStateSnapshot] gets structural equality for free,
 * which is exactly what byte-exact comparisons of captured state need: `Float` list equality is
 * bit-based, stricter than `==` on raw floats.
 *
 * Caveat: `GlStateSnapshotTest.captureRestoreCaptureIsIdenticalOnTheFake` performs a
 * capture/restore/capture round trip, but it only proves [captureGlState] is deterministic and
 * that this structural equality holds — it does **not** prove [restoreGlState] wrote every field
 * back. The `RecordingGlBinding` test fake's query responses come from static maps seeded once per
 * test and are never mutated by its write methods, so that round trip still passes even with a
 * restore call deleted. Restore completeness is instead proven by asserting the exact write call
 * appears in the fake's call log after `restoreGlState` runs, field by field — see the
 * `restoreWritesBack*` tests in `GlStateSnapshotTest`.
 *
 * The three nullable members are the dialect-gated ones: [framebufferSrgbEnabled] is `null` when
 * the context is ES without `GL_EXT_sRGB_write_control`, and [drawBuffer]/[lineSmoothEnabled] are
 * `null` on every ES context. `null` means "never queried and never restored", not "queried and
 * found unset".
 */
internal data class GlTextureUnitState(
    val unit: Int,
    val texture2d: Int,
    val sampler: Int,
)

/**
 * The uniform-buffer binding point RenG reserves for the joint-matrix uniform buffer skinning
 * needs. RenG binds exactly one uniform buffer, so exactly one indexed binding point is ever in
 * use, and the Restore Set below only ever needs to save and restore this one index.
 */
internal const val RENG_JOINT_UNIFORM_BINDING_POINT: Int = 0

internal data class GlStateSnapshot(
    val drawFramebuffer: Int,
    val readFramebuffer: Int,
    val renderbuffer: Int,
    val program: Int,
    val vertexArray: Int,
    val arrayBuffer: Int,
    val pixelUnpackBuffer: Int,
    val uniformBuffer: Int,
    val indexedUniformBuffer: Int,
    val blendEnabled: Boolean,
    val blendSourceRgb: Int,
    val blendDestinationRgb: Int,
    val blendSourceAlpha: Int,
    val blendDestinationAlpha: Int,
    val blendEquationRgb: Int,
    val blendEquationAlpha: Int,
    val blendColour: List<Float>,
    val depthTestEnabled: Boolean,
    val depthFunction: Int,
    val depthWriteMask: Boolean,
    val depthRange: List<Float>,
    val depthClearValue: Float,
    val cullEnabled: Boolean,
    val cullMode: Int,
    val frontFace: Int,
    val viewport: List<Int>,
    val scissorEnabled: Boolean,
    val scissorBox: List<Int>,
    val colourWriteMask: List<Boolean>,
    val colourClearValue: List<Float>,
    val unpackAlignment: Int,
    val unpackRowLength: Int,
    val unpackSkipRows: Int,
    val unpackSkipPixels: Int,
    val packAlignment: Int,
    val framebufferSrgbEnabled: Boolean?,
    val drawBuffer: Int?,
    val lineSmoothEnabled: Boolean?,
)

/**
 * Captures the corrected save-and-restore set from [binding].
 *
 * Reading a texture binding requires making its unit active, so `GL_ACTIVE_TEXTURE` is captured
 * first and reinstated immediately after the per-unit loop, with every per-unit read nested
 * inside. Capture is itself non-mutating: the per-unit loop changes the active unit and the line
 * after the loop puts it back, so a capture that is never followed by a restore still leaves the
 * context as it was found.
 *
 * `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are queryable on a desktop core profile but raise
 * `GL_INVALID_ENUM` on ES, so they are only queried when [profile] reports a desktop dialect.
 * `GL_FRAMEBUFFER_SRGB` is gated on [RenderContextProfile.supportsSrgbWriteControl] instead, since
 * an ES context without `GL_EXT_sRGB_write_control` cannot query it either.
 *
 * The **array** buffer binding is captured explicitly because the VAO does not capture it, while
 * the **element** array buffer binding is deliberately never queried here: it is per-VAO state
 * restored implicitly by restoring the VAO binding.
 *
 * The indexed uniform-buffer binding at [RENG_JOINT_UNIFORM_BINDING_POINT] is captured and restored
 * alongside the generic `GL_UNIFORM_BUFFER_BINDING` above for the same reason every other member of
 * this set exists: `glBindBufferBase(GL_UNIFORM_BUFFER, n, b)`, which skinning's joint-matrix buffer
 * needs, writes **both** the generic binding — already captured — and an indexed binding at point
 * `n` that the generic query never reaches. This is **applying** ADR 0023, not amending it: the
 * Restore Set is defined as the state RenG writes, and this indexed binding is state RenG has only
 * now started writing. `glGetIntegeri_v` is the query [GL_UNIFORM_BUFFER_BINDING]'s indexed form
 * needs, the same way [getIntegerv] answers the plain form.
 */
internal fun captureGlState(
    binding: GlBinding,
    profile: RenderContextProfile,
): GlStateSnapshot {
    val desktop = profile.dialect == ShaderDialect.DESKTOP
    return GlStateSnapshot(
        drawFramebuffer = binding.integer(GL_DRAW_FRAMEBUFFER_BINDING),
        readFramebuffer = binding.integer(GL_READ_FRAMEBUFFER_BINDING),
        renderbuffer = binding.integer(GL_RENDERBUFFER_BINDING),
        program = binding.integer(GL_CURRENT_PROGRAM),
        vertexArray = binding.integer(GL_VERTEX_ARRAY_BINDING),
        arrayBuffer = binding.integer(GL_ARRAY_BUFFER_BINDING),
        pixelUnpackBuffer = binding.integer(GL_PIXEL_UNPACK_BUFFER_BINDING),
        uniformBuffer = binding.integer(GL_UNIFORM_BUFFER_BINDING),
        indexedUniformBuffer = binding.integeri(GL_UNIFORM_BUFFER_BINDING, RENG_JOINT_UNIFORM_BINDING_POINT),
        blendEnabled = binding.isEnabled(GL_BLEND),
        blendSourceRgb = binding.integer(GL_BLEND_SRC_RGB),
        blendDestinationRgb = binding.integer(GL_BLEND_DST_RGB),
        blendSourceAlpha = binding.integer(GL_BLEND_SRC_ALPHA),
        blendDestinationAlpha = binding.integer(GL_BLEND_DST_ALPHA),
        blendEquationRgb = binding.integer(GL_BLEND_EQUATION_RGB),
        blendEquationAlpha = binding.integer(GL_BLEND_EQUATION_ALPHA),
        blendColour = binding.floats(GL_BLEND_COLOR, 4),
        depthTestEnabled = binding.isEnabled(GL_DEPTH_TEST),
        depthFunction = binding.integer(GL_DEPTH_FUNC),
        depthWriteMask = binding.booleans(GL_DEPTH_WRITEMASK, 1).single(),
        depthRange = binding.floats(GL_DEPTH_RANGE, 2),
        depthClearValue = binding.floats(GL_DEPTH_CLEAR_VALUE, 1).single(),
        cullEnabled = binding.isEnabled(GL_CULL_FACE),
        cullMode = binding.integer(GL_CULL_FACE_MODE),
        frontFace = binding.integer(GL_FRONT_FACE),
        viewport = binding.integers(GL_VIEWPORT, 4),
        scissorEnabled = binding.isEnabled(GL_SCISSOR_TEST),
        scissorBox = binding.integers(GL_SCISSOR_BOX, 4),
        colourWriteMask = binding.booleans(GL_COLOR_WRITEMASK, 4),
        colourClearValue = binding.floats(GL_COLOR_CLEAR_VALUE, 4),
        unpackAlignment = binding.integer(GL_UNPACK_ALIGNMENT),
        unpackRowLength = binding.integer(GL_UNPACK_ROW_LENGTH),
        unpackSkipRows = binding.integer(GL_UNPACK_SKIP_ROWS),
        unpackSkipPixels = binding.integer(GL_UNPACK_SKIP_PIXELS),
        packAlignment = binding.integer(GL_PACK_ALIGNMENT),
        framebufferSrgbEnabled =
            if (profile.supportsSrgbWriteControl) binding.isEnabled(GL_FRAMEBUFFER_SRGB) else null,
        drawBuffer = if (desktop) binding.integer(GL_DRAW_BUFFER) else null,
        lineSmoothEnabled = if (desktop) binding.isEnabled(GL_LINE_SMOOTH) else null,
    )
}

/**
 * Reads [textureUnitCount] units' 2D bindings and samplers.
 *
 * **Not used by capture any more** (ADR 0055 saves a unit when a draw first writes it). This exists
 * for verification: a conformance check should look at more units than the implementation tracks,
 * precisely so that a unit RenG touched and failed to restore is visible. Production never walks a
 * fixed set; the thing checking production does.
 */
internal fun captureTextureUnits(binding: GlBinding, textureUnitCount: Int): List<GlTextureUnitState> {
    require(textureUnitCount > 0) { "at least one texture unit is captured" }
    val activeTextureUnit = binding.integer(GL_ACTIVE_TEXTURE)
    val units = ArrayList<GlTextureUnitState>(textureUnitCount)
    for (index in 0 until textureUnitCount) {
        binding.activeTexture(GL_TEXTURE0 + index)
        units += GlTextureUnitState(
            unit = GL_TEXTURE0 + index,
            texture2d = binding.integer(GL_TEXTURE_BINDING_2D),
            sampler = binding.integer(GL_SAMPLER_BINDING),
        )
    }
    binding.activeTexture(activeTextureUnit)
    return units
}

private fun GlBinding.integer(pname: Int): Int = integers(pname, 1).single()

private fun GlBinding.integeri(pname: Int, index: Int): Int {
    val out = IntArray(1)
    getIntegeri_v(pname, index, out)
    return out.single()
}

private fun GlBinding.integers(pname: Int, count: Int): List<Int> {
    val out = IntArray(count)
    getIntegerv(pname, out)
    return out.toList()
}

private fun GlBinding.floats(pname: Int, count: Int): List<Float> {
    val out = FloatArray(count)
    getFloatv(pname, out)
    return out.toList()
}

private fun GlBinding.booleans(pname: Int, count: Int): List<Boolean> {
    val out = BooleanArray(count)
    getBooleanv(pname, out)
    return out.toList()
}

/**
 * Restores the corrected save-and-restore set into [binding].
 *
 * `glClearColor` and `glClearDepthf` are restored because they are global state rather than
 * parameters of `glClear`, and RenG clears its offscreen surface every frame. `glBindSampler`
 * takes a texture unit **index**, not the `GL_TEXTUREi` token, which is why the restore subtracts
 * `GL_TEXTURE0`. As in capture, `GL_ACTIVE_TEXTURE` is reinstated last, after every per-unit bind.
 *
 * `glBindBufferBase` restores the indexed uniform-buffer binding **before** the plain `glBindBuffer`
 * restores the generic one, not after: per the GL specification, `glBindBufferBase(target, index,
 * buffer)` also sets `target`'s generic binding to `buffer`, exactly as if `glBindBuffer` had been
 * called with it. Restoring `glBindBufferBase` second would silently overwrite a correctly restored
 * generic `GL_UNIFORM_BUFFER_BINDING` with whatever the indexed snapshot happened to hold — a real
 * bug the recording fake cannot see (it does not model this side effect) but the real-hardware
 * conformance suite caught immediately as a byte-inexact round trip. Restoring `glBindBufferBase`
 * first, with the plain `glBindBuffer` last, makes the plain call authoritative for the generic
 * binding while leaving the indexed binding — which only `glBindBufferBase`/`glBindBufferRange`
 * ever write — exactly as `glBindBufferBase` left it.
 */
internal fun restoreGlState(binding: GlBinding, snapshot: GlStateSnapshot) {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, snapshot.drawFramebuffer)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, snapshot.readFramebuffer)
    binding.bindRenderbuffer(GL_RENDERBUFFER, snapshot.renderbuffer)
    binding.useProgram(snapshot.program)
    binding.bindVertexArray(snapshot.vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, snapshot.arrayBuffer)
    binding.bindBuffer(GL_PIXEL_UNPACK_BUFFER, snapshot.pixelUnpackBuffer)
    binding.bindBufferBase(GL_UNIFORM_BUFFER, RENG_JOINT_UNIFORM_BINDING_POINT, snapshot.indexedUniformBuffer)
    binding.bindBuffer(GL_UNIFORM_BUFFER, snapshot.uniformBuffer)

    binding.setEnabled(GL_BLEND, snapshot.blendEnabled)
    binding.blendFuncSeparate(
        snapshot.blendSourceRgb,
        snapshot.blendDestinationRgb,
        snapshot.blendSourceAlpha,
        snapshot.blendDestinationAlpha,
    )
    binding.blendEquationSeparate(snapshot.blendEquationRgb, snapshot.blendEquationAlpha)
    binding.blendColor(
        snapshot.blendColour[0],
        snapshot.blendColour[1],
        snapshot.blendColour[2],
        snapshot.blendColour[3],
    )

    binding.setEnabled(GL_DEPTH_TEST, snapshot.depthTestEnabled)
    binding.depthFunc(snapshot.depthFunction)
    binding.depthMask(snapshot.depthWriteMask)
    binding.depthRangef(snapshot.depthRange[0], snapshot.depthRange[1])
    binding.clearDepthf(snapshot.depthClearValue)

    binding.setEnabled(GL_CULL_FACE, snapshot.cullEnabled)
    binding.cullFace(snapshot.cullMode)
    binding.frontFace(snapshot.frontFace)

    binding.viewport(
        snapshot.viewport[0], snapshot.viewport[1], snapshot.viewport[2], snapshot.viewport[3],
    )
    binding.setEnabled(GL_SCISSOR_TEST, snapshot.scissorEnabled)
    binding.scissor(
        snapshot.scissorBox[0], snapshot.scissorBox[1], snapshot.scissorBox[2], snapshot.scissorBox[3],
    )
    binding.colorMask(
        snapshot.colourWriteMask[0],
        snapshot.colourWriteMask[1],
        snapshot.colourWriteMask[2],
        snapshot.colourWriteMask[3],
    )
    binding.clearColor(
        snapshot.colourClearValue[0],
        snapshot.colourClearValue[1],
        snapshot.colourClearValue[2],
        snapshot.colourClearValue[3],
    )

    binding.pixelStorei(GL_UNPACK_ALIGNMENT, snapshot.unpackAlignment)
    binding.pixelStorei(GL_UNPACK_ROW_LENGTH, snapshot.unpackRowLength)
    binding.pixelStorei(GL_UNPACK_SKIP_ROWS, snapshot.unpackSkipRows)
    binding.pixelStorei(GL_UNPACK_SKIP_PIXELS, snapshot.unpackSkipPixels)
    binding.pixelStorei(GL_PACK_ALIGNMENT, snapshot.packAlignment)

    snapshot.framebufferSrgbEnabled?.let { binding.setEnabled(GL_FRAMEBUFFER_SRGB, it) }
    snapshot.drawBuffer?.let { binding.drawBuffers(1, intArrayOf(it)) }
    snapshot.lineSmoothEnabled?.let { binding.setEnabled(GL_LINE_SMOOTH, it) }

}

private fun GlBinding.setEnabled(cap: Int, enabled: Boolean) {
    if (enabled) enable(cap) else disable(cap)
}

/**
 * Runs [block] with [binding]'s corrected save-and-restore set captured beforehand and restored
 * afterward unconditionally, including when [block] throws.
 *
 * Restoring in a `finally` is what turns ADR 0006 from a happy-path promise into a guarantee: a
 * frame that fails halfway through still hands the context back exactly as it was found.
 */
internal inline fun <T> withCapturedGlState(
    binding: GlBinding,
    profile: RenderContextProfile,
    block: (GlBinding) -> T,
): T {
    // Already capturing: run inside the region that is open and leave both the capture and the
    // restore to whoever opened it (ADR 0054). The same root/join shape ADR 0051 gave `withOperation`,
    // and for the same reason -- an invariant that held for one caller has to survive being called
    // from inside another. A second capture here would not be wrong, only paid twice.
    if (binding is TextureUnitCapturingBinding) return block(binding)

    // The texture units are captured by the binding below, one at a time and only when the draw
    // actually overwrites one (ADR 0055); everything else is captured here and now, because it is
    // written from too many places to intercept and is a fixed, small set.
    val tracked = TextureUnitCapturingBinding(binding)
    val snapshot = captureGlState(binding, profile)
    try {
        return block(tracked)
    } finally {
        restoreGlState(binding, snapshot)
        // After the rest of the state, so that a restore which rebinds a framebuffer or a program
        // cannot be undone by a texture unit switch, and because the active unit must be the last
        // thing written either way.
        tracked.restoreTouchedUnits()
    }
}
