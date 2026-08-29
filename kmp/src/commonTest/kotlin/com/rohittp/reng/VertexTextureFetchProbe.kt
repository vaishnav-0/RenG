package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_ARRAY_BUFFER
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FLOAT
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_NEAREST
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_STATIC_DRAW
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_TEXTURE_MAG_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_MIN_FILTER
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlProgramResult
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.compileShaderProgram
import com.rohittp.reng.internal.gl.littleEndianBytes
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **E-terrain preflight.** Can a vertex shader read a DEM texture, and does the value it reads
 * actually move geometry, on every driver RenG runs on?
 *
 * The cycle's architecture turns on this. Displacing the ground means a per-vertex elevation, and
 * there are exactly two ways to supply it: sample the DEM texture in the vertex shader (a *vertex
 * texture fetch*), or decode the DEM on the CPU and bake elevations into the vertex buffer. The
 * first keeps one upload per tile and no CPU work per frame; the second costs a decode and a buffer
 * rewrite whenever the tile set or the granularity changes, but depends on nothing.
 *
 * **The advertised limit is not the measurement.** `GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS` is required
 * to be at least 16 by both GLSL ES 3.00 and desktop GL 3.30, so every driver here will claim
 * support. What this probe asserts instead is that a sampled value **reaches `gl_Position`**: each
 * point is placed at a row the vertex shader read out of the texture, so a driver that returns zero,
 * clamps to `lowp`, or silently ignores the fetch scatters the points onto one row instead of the
 * diagonal. That failure is unmistakable and cannot be produced by a correct implementation.
 *
 * **Two portability details are deliberately exercised rather than assumed.** A vertex shader has no
 * implicit derivatives, so the fetch must be `textureLod` with an explicit level — plain `texture()`
 * is undefined there. And GLSL ES 3.00 declares `precision lowp sampler2D` for the vertex language
 * (section 4.5.4), whose returned float carries about eight bits: exactly the resolution an 8-bit
 * DEM channel needs, with nothing spare. The sampler is therefore declared `highp` explicitly, and
 * the diagonal is what proves that mattered.
 *
 * **This probe is a spike, not a gate on terrain.** It measures the driver, so a failure here is a
 * finding about the platform rather than about RenG, and the cycle's answer to a failure is the CPU
 * path rather than a fix.
 */
internal fun runVertexTextureFetchProbeSuite(binding: GlBinding, dialect: ShaderDialect) {
    val target = createVertexFetchTarget(binding)
    val probe = VertexFetchProgram.create(binding, dialect, target)
    try {
        val advertisedUnits = IntArray(1)
        binding.getIntegerv(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, advertisedUnits)
        println(
            "RenG vertex texture fetch probe: driver=${binding.getString(GL_RENDERER)} " +
                "dialect=$dialect maxVertexTextureImageUnits=${advertisedUnits[0]}",
        )
        assertTrue(
            advertisedUnits[0] >= 1,
            "a driver advertising ${advertisedUnits[0]} vertex texture image units cannot displace a " +
                "ground in the vertex shader at all; both GLSL ES 3.00 and GL 3.30 require at least 16",
        )

        val lit = probe.drawAndReadLitPixels()

        // The diagonal is the whole assertion: point k was placed at row `texel(k).r`, which the
        // texture defines as k. Any driver that does not deliver the fetch puts every point on one
        // row, and `offDiagonal` counts exactly that.
        var missing = 0
        var offDiagonal = 0
        for (k in 0 until PROBE_TEXELS) {
            if (!lit[k * PROBE_PIXELS + k]) missing += 1
        }
        for (index in lit.indices) {
            val x = index % PROBE_PIXELS
            val y = index / PROBE_PIXELS
            if (lit[index] && x != y) offDiagonal += 1
        }
        assertEquals(
            0,
            missing,
            "$missing of $PROBE_TEXELS points did not land on the row the vertex shader read for " +
                "them, so a vertex texture fetch does not reach gl_Position on this driver",
        )
        assertEquals(
            0,
            offDiagonal,
            "$offDiagonal lit pixels are off the diagonal, so the vertex shader sampled the wrong " +
                "texel rather than none",
        )
        println(
            "RenG vertex texture fetch probe: all $PROBE_TEXELS displaced points landed on the row " +
                "their own texel named — a DEM can be read in the vertex shader on this driver",
        )
    } finally {
        probe.destroy()
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.deleteFramebuffers(1, intArrayOf(target.framebuffer))
        binding.deleteTextures(1, intArrayOf(target.texture))
    }
}

private class VertexFetchTarget(val framebuffer: Int, val texture: Int)

private fun createVertexFetchTarget(binding: GlBinding): VertexFetchTarget {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, PROBE_PIXELS, PROBE_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the vertex texture fetch probe's readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return VertexFetchTarget(framebuffer, texture)
}

private class VertexFetchProgram(
    private val binding: GlBinding,
    private val target: VertexFetchTarget,
    private val program: Int,
    private val vertexArray: Int,
    private val vertexBuffer: Int,
    private val demTexture: Int,
    private val samplerLocation: Int,
) {
    fun drawAndReadLitPixels(): BooleanArray {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer)
        binding.viewport(0, 0, PROBE_PIXELS, PROBE_PIXELS)
        binding.clearColor(0.0f, 0.0f, 0.0f, 1.0f)
        binding.clear(GL_COLOR_BUFFER_BIT)
        binding.useProgram(program)
        binding.bindVertexArray(vertexArray)
        binding.activeTexture(GL_TEXTURE0)
        binding.bindTexture(GL_TEXTURE_2D, demTexture)
        if (samplerLocation >= 0) binding.uniform1i(samplerLocation, 0)
        binding.drawArrays(GL_POINTS, 0, PROBE_TEXELS)

        val pixels = ByteArray(PROBE_PIXELS * PROBE_PIXELS * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target.framebuffer)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(0, 0, PROBE_PIXELS, PROBE_PIXELS, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        return BooleanArray(PROBE_PIXELS * PROBE_PIXELS) { index ->
            // Green is the probe's ink and nothing else writes it, so "lit" is unambiguous even if a
            // driver dithers the other channels.
            (pixels[index * 4 + 1].toInt() and 0xff) > 127
        }
    }

    fun destroy() {
        binding.deleteBuffers(1, intArrayOf(vertexBuffer))
        binding.deleteVertexArrays(1, intArrayOf(vertexArray))
        binding.deleteTextures(1, intArrayOf(demTexture))
        binding.deleteProgram(program)
    }

    companion object {
        fun create(binding: GlBinding, dialect: ShaderDialect, target: VertexFetchTarget): VertexFetchProgram {
            val vertexPlan = requireNotNull(scanShaderProfile(PROBE_VERTEX_SOURCE)) {
                "the vertex texture fetch probe's vertex source must scan"
            }
            val fragmentPlan = requireNotNull(scanShaderProfile(PROBE_FRAGMENT_SOURCE)) {
                "the vertex texture fetch probe's fragment source must scan"
            }
            val program = when (
                val result = compileShaderProgram(binding, dialect, PROBE_KEY, vertexPlan, fragmentPlan)
            ) {
                is GlProgramResult.Linked -> result.program
                is GlProgramResult.Failed ->
                    throw AssertionError("the vertex texture fetch probe must compile: ${result.failure}")
            }

            // Texel (i, j) carries its own linear index in red, so the elevation the shader reads is
            // the row it must land on. Nothing about the value is arbitrary: it is the identity, which
            // is the only encoding whose misreading is visible as a shape rather than as a number.
            val texels = ByteArray(PROBE_TEXELS * 4)
            for (k in 0 until PROBE_TEXELS) {
                texels[k * 4] = k.toByte()
                texels[k * 4 + 1] = 0
                texels[k * 4 + 2] = 0
                texels[k * 4 + 3] = -1
            }
            val names = IntArray(1)
            binding.genTextures(1, names)
            val demTexture = names[0]
            binding.bindTexture(GL_TEXTURE_2D, demTexture)
            binding.texImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8, PROBE_SIDE, PROBE_SIDE, 0, GL_RGBA, GL_UNSIGNED_BYTE, texels,
            )
            // Nearest in both directions: a filtered fetch would blend neighbouring indices and the
            // diagonal would blur into a band, which would be a measurement of the sampler rather
            // than of whether the fetch happened.
            binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)

            val indices = FloatArray(PROBE_TEXELS) { it.toFloat() }
            binding.genVertexArrays(1, names)
            val vertexArray = names[0]
            binding.bindVertexArray(vertexArray)
            binding.genBuffers(1, names)
            val vertexBuffer = names[0]
            binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
            val bytes = littleEndianBytes(indices)
            binding.bufferData(GL_ARRAY_BUFFER, bytes.size, bytes, GL_STATIC_DRAW)
            binding.enableVertexAttribArray(0)
            binding.vertexAttribPointer(0, 1, GL_FLOAT, false, 4, 0)

            return VertexFetchProgram(
                binding, target, program, vertexArray, vertexBuffer, demTexture,
                binding.getUniformLocation(program, "rengProbeDem"),
            )
        }
    }
}

/** Eight by eight, so 64 texels index 64 rows of a 64-pixel target exactly. */
private const val PROBE_SIDE: Int = 8
private const val PROBE_TEXELS: Int = PROBE_SIDE * PROBE_SIDE
private const val PROBE_PIXELS: Int = PROBE_TEXELS

/**
 * Point `k` samples texel `k` and lands at column `k`, row `texel(k).r * 255`.
 *
 * `textureLod` rather than `texture`, because a vertex shader has no implicit derivatives and the
 * plain form is undefined there. `highp sampler2D` rather than the vertex language's default `lowp`,
 * whose roughly eight bits of returned precision is exactly what an 8-bit channel needs and no more.
 */
private val PROBE_VERTEX_SOURCE: String =
    """
    #version 300 es
    precision highp float;
    layout(location = 0) in float rengProbeIndex;
    uniform highp sampler2D rengProbeDem;
    void main() {
        float column = mod(rengProbeIndex, 8.0);
        float row = floor(rengProbeIndex / 8.0);
        vec2 uv = vec2((column + 0.5) / 8.0, (row + 0.5) / 8.0);
        float elevation = textureLod(rengProbeDem, uv, 0.0).r * 255.0;
        float x = (rengProbeIndex + 0.5) / 64.0 * 2.0 - 1.0;
        float y = (elevation + 0.5) / 64.0 * 2.0 - 1.0;
        gl_Position = vec4(x, y, 0.0, 1.0);
        gl_PointSize = 1.0;
    }
    """.trimIndent() + "\n"

private val PROBE_FRAGMENT_SOURCE: String =
    """
    #version 300 es
    precision highp float;
    out vec4 rengProbeColour;
    void main() {
        rengProbeColour = vec4(0.0, 1.0, 0.0, 1.0);
    }
    """.trimIndent() + "\n"

private const val GL_POINTS: Int = 0x0000
private const val GL_TEXTURE0: Int = 0x84C0
private const val GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS: Int = 0x8B4C

private val PROBE_KEY: ResourceKey =
    ResourceKey(ResourceKind.INTERNAL_PIPELINE, "3".repeat(64), null)
