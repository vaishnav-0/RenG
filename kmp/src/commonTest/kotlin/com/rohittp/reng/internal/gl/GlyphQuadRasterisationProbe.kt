package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * E-labels task 18: measures whether this context rasterises the **label pass's own** quad
 * footprints, and says so in pixels.
 *
 * **Why the label suites need their own driver measurement.** `0.3.0` failed publication because a
 * hosted macOS runner's `Apple Software Renderer` drops quads that reach far outside the viewport --
 * the shape every basemap ground tile has -- and `measureLargeQuadRasterisation` exists to measure
 * that rather than to name the driver. The label suites then arrived resting on a *prediction*: that
 * glyph quads are small enough not to be affected. That is precisely the class of prediction that
 * cost `0.3.0` a publication, so it is measured here before any label assertion leans on it.
 *
 * **What is genuinely different about a glyph quad, stated as the four things this probe reproduces.**
 * - Its clip `w` is the constant `1.0`. `LABEL_VERTEX_SOURCE` ends in `vec4(ndc, 0.0, 1.0)` and has no
 *   matrix at all, so unlike the ground -- whose defect was erratic in `w`, wrong at `154.5` and right
 *   at `247` -- there is exactly one `w` a glyph quad can ever have, and this probe draws at it.
 * - It is small: single figures to low tens of pixels on a side, against the ground's 512.
 * - It lies wholly inside the viewport. The ground quad reaches 448 pixels outside a 128-pixel frame.
 * - It is **two indexed triangles sharing a diagonal**, not a triangle strip. That seam is its own
 *   rasterisation hazard -- a driver that fills the shared edge inconsistently leaves a hairline of
 *   unpainted pixels straight through every letter -- and it is invisible to the ground probe, which
 *   draws a strip.
 *
 * **This probe compiles its own program and writes its own vertices, and that is deliberate.** It
 * restates the label pass's clip-space arithmetic and its `0,1,2, 0,2,3` topology rather than calling
 * [createLabelPipeline] or importing [labelQuadIndices], because a probe built on the code it guards
 * is a guard that fires on RenG's own regressions -- and a guard that fires first masks every rule
 * beneath it. If a defect in RenG's label pass could make this probe distrust the driver, the suites
 * below would skip their way to green having tested nothing. Drawing an independent shape at the same
 * clip `w`, at the same size, with the same topology keeps the two failures distinguishable: this
 * probe can only ever accuse the driver.
 *
 * **A probe that cannot run fails rather than skips**, exactly as [measureLargeQuadRasterisation]
 * does. Silently declaring a driver untrustworthy would take the label pixel assertions off CI with
 * nobody noticing, which is the whole failure mode this exists to remove.
 *
 * **Measured, and the prediction held.** The label readback suite's three 8-by-8 footprints -- the
 * smallest any fixture in the tree draws -- disagree with their analytic rectangles over **0** pixels
 * on `Apple M3 Max`, **0** on `Apple Software Renderer` through `MacosGlRenderer.SOFTWARE`, and **0**
 * on the iOS simulator's `Apple Software Renderer`. The label integration suite's three 9.33-by-10.67
 * footprints measure **0** on `Apple M3 Max`; that suite runs on the default renderer alone, so its
 * larger footprint has not been put to the software rasteriser.
 *
 * **The sharp result is that two of those numbers come from one process.** On the iOS simulator
 * `measureLargeQuadRasterisation` reports 3,040 disagreeing pixels for the ground's footprints and
 * this probe reports 0 for the label pass's, on the same driver, in the same run -- so "glyph quads
 * survive where ground quads do not" is now a measurement on the exact rasteriser that cost `0.3.0`
 * a publication, rather than an argument from their size.
 *
 * **What stays unmeasured:** Linux `llvmpipe`, which has no host here, and every real mobile GPU,
 * because ADR 0033 runs the mobile targets in simulation only. That is why the number is printed on
 * every run rather than asserted once, and why the budget below is derived from the quad's own
 * geometry rather than fitted to the drivers that have been seen.
 */
internal fun measureGlyphQuadRasterisation(
    binding: GlBinding,
    dialect: ShaderDialect,
    targetFramebuffer: Int,
    framePixels: Int,
    footprints: List<GlyphQuadFootprint>,
): GlyphQuadRasterisation {
    require(footprints.isNotEmpty()) { "the glyph-quad probe must be given at least one footprint" }
    val vertexPlan = requireNotNull(scanShaderProfile(GLYPH_PROBE_VERTEX_SOURCE)) {
        "the glyph-quad probe's vertex source must scan"
    }
    val fragmentPlan = requireNotNull(scanShaderProfile(GLYPH_PROBE_FRAGMENT_SOURCE)) {
        "the glyph-quad probe's fragment source must scan"
    }
    val program = when (
        val result = compileShaderProgram(binding, dialect, GLYPH_PROBE_KEY, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed ->
            throw AssertionError("the glyph-quad probe's $dialect program did not link on this driver")
    }

    // Restored rather than zeroed on the way out. This probe runs inside suites that bind their own
    // target once and never rebind it, so leaving framebuffer 0 current would send every subsequent
    // clear and draw to the default framebuffer while the readback still read the target -- measured,
    // and it looks exactly like a label pass that drew nothing.
    val names = IntArray(1)
    binding.getIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, names)
    val previousDrawFramebuffer = names[0]
    binding.getIntegerv(GL_READ_FRAMEBUFFER_BINDING, names)
    val previousReadFramebuffer = names[0]
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]
    binding.genBuffers(1, names)
    val indexBuffer = names[0]
    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, GLYPH_PROBE_STRIDE_BYTES, 0)
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
    binding.bufferData(
        GL_ELEMENT_ARRAY_BUFFER,
        GLYPH_PROBE_INDEX_BYTES.size,
        GLYPH_PROBE_INDEX_BYTES,
        GL_STATIC_DRAW,
    )

    val pixels = ByteArray(framePixels * framePixels * 4)
    val readings = mutableListOf<GlyphQuadReading>()
    try {
        binding.useProgram(program)
        val viewportLocation = binding.getUniformLocation(program, GLYPH_PROBE_VIEWPORT_UNIFORM_NAME)
        footprints.forEach { footprint ->
            binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
            binding.viewport(0, 0, framePixels, framePixels)
            binding.disable(GL_SCISSOR_TEST)
            binding.disable(GL_DEPTH_TEST)
            binding.disable(GL_CULL_FACE)
            binding.disable(GL_BLEND)
            binding.colorMask(true, true, true, true)
            binding.clearColor(0f, 0f, 0f, 1f)
            binding.clear(GL_COLOR_BUFFER_BIT)
            if (viewportLocation >= 0) {
                binding.uniform2f(viewportLocation, framePixels.toFloat(), framePixels.toFloat())
            }
            val corners = littleEndianBytes(footprint.cornersXy())
            binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
            binding.bufferData(GL_ARRAY_BUFFER, corners.size, corners, GL_STATIC_DRAW)
            binding.drawElements(GL_TRIANGLES, GLYPH_PROBE_INDICES, GL_UNSIGNED_INT, 0)

            binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
            binding.readBuffer(GL_COLOR_ATTACHMENT0)
            binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
            binding.readPixels(0, 0, framePixels, framePixels, GL_RGBA, GL_UNSIGNED_BYTE, pixels)

            var disagreeing = 0
            for (row in 0 until framePixels) {
                for (column in 0 until framePixels) {
                    val painted = pixels[(row * framePixels + column) * 4].toInt() and 0xff > 128
                    if (painted != footprint.covers(column, row, framePixels)) disagreeing += 1
                }
            }
            readings += GlyphQuadReading(footprint, disagreeing)
        }
    } finally {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer)
        binding.useProgram(0)
        binding.bindVertexArray(0)
        binding.bindBuffer(GL_ARRAY_BUFFER, 0)
        binding.deleteVertexArrays(1, intArrayOf(vertexArray))
        binding.deleteBuffers(1, intArrayOf(vertexBuffer))
        binding.deleteBuffers(1, intArrayOf(indexBuffer))
        binding.deleteProgram(program)
    }
    return GlyphQuadRasterisation(readings)
}

/**
 * One glyph quad's screen footprint, in `CONTEXT.md`'s continuous output-pixel screen space --
 * origin top-left, positive y **downward** -- which is the space [ResolvedGlyphQuad.cornersXy] is
 * already written in, so a suite's footprint reads the same way its fixture does.
 *
 * **Place these so no pixel centre lands on an edge, or near one.** A centre exactly on a boundary is
 * decided by the rasteriser's fill rule rather than by arithmetic, and a whole column or row of such
 * pixels would be a legitimate disagreement that says nothing about the defect this probe hunts. The
 * two footprint sets in the tree keep every edge at least a sixth of a pixel from the nearest centre,
 * which is more than the 1/16 the Metal path's 4 `GL_SUBPIXEL_BITS` can snap an edge by, so [covers]
 * is the only correct answer for both and the honest expectation is **0** disagreeing pixels.
 */
internal class GlyphQuadFootprint(
    val name: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val widthPixels: Float get() = right - left
    val heightPixels: Float get() = bottom - top

    /**
     * The four corners in [ResolvedGlyphQuad]'s own order -- top-left, top-right, bottom-right,
     * bottom-left -- which with [GLYPH_PROBE_INDEX_BYTES] is the label pass's exact topology.
     */
    fun cornersXy(): FloatArray = floatArrayOf(left, top, right, top, right, bottom, left, bottom)

    /**
     * Whether the analytic rectangle covers the pixel at [column] of bottom-up framebuffer row
     * [glRow], in a [framePixels]-square frame.
     *
     * `glReadPixels` hands back row 0 as the **bottom** of the frame while this footprint is written
     * y-down, so the flip is applied here rather than in the fixture -- and applying it here is also
     * what makes a probe that forgot the flip disagree over the whole quad twice over instead of
     * cancelling out.
     */
    fun covers(column: Int, glRow: Int, framePixels: Int): Boolean {
        val centreX = column + 0.5f
        val centreY = framePixels - (glRow + 0.5f)
        return centreX > left && centreX < right && centreY > top && centreY < bottom
    }

    /**
     * One pixel of boundary on each of the quad's four sides. Derived from the quad's own perimeter
     * rather than tuned, and slack rather than expected: every driver measured so far disagrees over
     * **0** pixels, because the edges are placed off the pixel centres. What it has to stay under is
     * the quad's own area, which is what a dropped primitive costs -- for the smallest footprint any
     * label fixture draws, 8 by 8, that is 32 against 64.
     */
    val boundaryBudget: Int get() = (2f * (widthPixels + heightPixels)).toInt()

    fun describeSize(): String = "$name ${widthPixels}x${heightPixels} at ($left, $top)"
}

internal class GlyphQuadReading(val footprint: GlyphQuadFootprint, val disagreeingPixels: Int) {
    val overBudget: Int get() = disagreeingPixels - footprint.boundaryBudget
}

/**
 * What a context did to the label pass's own footprints, **in pixels**.
 *
 * A number rather than a verdict, and printed on every run rather than only on failure, so that a
 * future reader of a CI log can see what some driver nobody here owns actually did -- which is the
 * one thing `0.3.0`'s `kotlin.AssertionError at null:-1` could not tell anybody.
 */
internal class GlyphQuadRasterisation internal constructor(
    private val readings: List<GlyphQuadReading>,
) {
    private val worst: GlyphQuadReading = readings.maxBy { it.overBudget }

    val isTrustworthy: Boolean get() = worst.overBudget <= 0

    fun describe(): String {
        val perFootprint = readings.joinToString(", ") {
            "${it.footprint.describeSize()}=${it.disagreeingPixels}/${it.footprint.boundaryBudget}"
        }
        return if (isTrustworthy) {
            "glyph quads rasterise correctly at clip w = 1 (disagreement/budget: $perFootprint)"
        } else {
            "this driver mis-rasterises glyph quads: ${worst.footprint.describeSize()} disagrees " +
                "with its analytic rectangle over ${worst.disagreeingPixels} pixels, against a " +
                "boundary budget of ${worst.footprint.boundaryBudget} " +
                "(disagreement/budget: $perFootprint)"
        }
    }
}

/**
 * The label pass's own clip-space arithmetic, restated: a position in y-down output pixels, a
 * viewport size, and `w` left at the constant `1.0`.
 *
 * Copied in shape from `LABEL_VERTEX_SOURCE` rather than imported from it, for the reason the
 * function's KDoc gives -- the probe must not be able to fail because RenG's label pass is broken.
 */
private val GLYPH_PROBE_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengGlyphProbePosition;\n" +
        "uniform vec2 $GLYPH_PROBE_VIEWPORT_UNIFORM_NAME;\n" +
        "void main() {\n" +
        "    vec2 ndc = vec2(\n" +
        "        rengGlyphProbePosition.x / $GLYPH_PROBE_VIEWPORT_UNIFORM_NAME.x * 2.0 - 1.0,\n" +
        "        1.0 - rengGlyphProbePosition.y / $GLYPH_PROBE_VIEWPORT_UNIFORM_NAME.y * 2.0\n" +
        "    );\n" +
        "    gl_Position = vec4(ndc, 0.0, 1.0);\n" +
        "}\n"

private val GLYPH_PROBE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "layout(location = 0) out vec4 rengGlyphProbeColour;\n" +
        "void main() {\n" +
        "    rengGlyphProbeColour = vec4(1.0, 1.0, 1.0, 1.0);\n" +
        "}\n"

/**
 * `0,1,2, 0,2,3` as little-endian `GL_UNSIGNED_INT`, which is `labelQuadIndices(1)` serialised --
 * written out rather than called, so that a regression in the label pass's own index generation
 * cannot make this probe distrust the driver and skip the case that would have caught it.
 */
private val GLYPH_PROBE_INDEX_BYTES: ByteArray = byteArrayOf(
    0, 0, 0, 0,
    1, 0, 0, 0,
    2, 0, 0, 0,
    0, 0, 0, 0,
    2, 0, 0, 0,
    3, 0, 0, 0,
)

private const val GLYPH_PROBE_INDICES: Int = 6

private const val GLYPH_PROBE_STRIDE_BYTES: Int = 8

private const val GLYPH_PROBE_VIEWPORT_UNIFORM_NAME: String = "rengGlyphProbeViewportSize"

/** Only ever used to name a compile failure; the probe program is never cached. */
private val GLYPH_PROBE_KEY: ResourceKey =
    ResourceKey(ResourceKind.INTERNAL_PIPELINE, "1".repeat(64), null)
