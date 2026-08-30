package com.rohittp.reng

import com.rohittp.reng.internal.gl.GEOMETRY_VERTEX_COMPONENT_COUNT
import com.rohittp.reng.internal.gl.GL_ARRAY_BUFFER
import com.rohittp.reng.internal.gl.GL_BLEND
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_CULL_FACE
import com.rohittp.reng.internal.gl.GL_DEPTH_TEST
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_DYNAMIC_DRAW
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_TRIANGLE_STRIP
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GeometryGrid
import com.rohittp.reng.internal.gl.GeometryPipeline
import com.rohittp.reng.internal.gl.GeometryPipelineResult
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.composeGeometryViewProjection
import com.rohittp.reng.internal.gl.createGeometryPipeline
import com.rohittp.reng.internal.gl.deleteGeometryPipeline
import com.rohittp.reng.internal.gl.drawGeometry
import com.rohittp.reng.internal.gl.geometryCellsPerSide
import com.rohittp.reng.internal.gl.geometryGrid
import com.rohittp.reng.internal.gl.littleEndianBytes
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle G task 9's gate: a `Geometry` subdivided and CPU-projected, in pixels on a real driver.
 *
 * **The load-bearing case is the boring-sounding one.** ADR 0008's erratum lets RenG subdivide a
 * `Geometry` in *both* projection modes on the strength of one claim — that a lerped grid over four
 * coplanar corners changes no pixel under Mercator. Three shipped releases draw geometries through
 * the four-corner triangle strip that claim is about, so
 * [assertSubdividingAMercatorGeometryMovesNoPixel] draws the fixture **the way `0.3.0` drew it** —
 * the same four corners, the same interleaved layout, the same `GL_TRIANGLE_STRIP` — and then
 * through the new grid at six granularities, and compares the read-back frames byte for byte. A
 * reference built by asking the new code for one cell would prove something much weaker.
 *
 * **The fragment stage is deliberately the most sensitive probe available.** It paints the
 * interpolated texture coordinate straight into the red and green channels, so a subdivision that
 * changed *where* an attribute lands — not merely which pixels are covered — shows up as a colour
 * difference rather than being averaged away by a texture fetch. The vertex stage is a consumer's:
 * it declares `aPosition`, `aTexCoord` and `uModelViewProjection` and nothing else, which is exactly
 * the shader ADR 0008 promises keeps working.
 *
 * **The camera is pitched.** A top-down camera makes the map-to-screen map affine, where any
 * triangulation of a planar quad agrees trivially; at 45 degrees of pitch it is genuinely
 * projective, and the two triangulations agree only because perspective-correct interpolation of an
 * attribute affine in the quad's own plane is exact. That is the property being measured.
 *
 * **What it does not claim.** Curvature fidelity is Cycle J's, per the cycle's own gate decision;
 * nothing here compares against a stored image, and the globe cases below assert only that the
 * projection bends the quad and that the camera bounds it — the cross-mode *agreement* gate is task
 * 12's.
 */
internal fun runGeometrySubdivisionReadbackSuite(binding: GlBinding, dialect: ShaderDialect) {
    val target = createGeometrySubdivisionTarget(binding)
    val programs = GlProgramCache()
    val pipeline = when (
        val result = createGeometryPipeline(binding, dialect, programs, GEOMETRY_PROBE_SHADER_PAIR)
    ) {
        is GeometryPipelineResult.Created -> result.pipeline
        is GeometryPipelineResult.Failed ->
            throw AssertionError("the geometry probe's $dialect program did not link on this driver")
    }
    println("RenG geometry subdivision readback: driver=${binding.getString(GL_RENDERER)} dialect=$dialect")
    try {
        val fixture = GeometrySubdivisionFixture(binding, pipeline, target)
        assertSubdividingAMercatorGeometryMovesNoPixel(fixture)
        assertAnAltitudeModeMovesNoPixelOverFlatGround(fixture)
        assertAGlobeGeometryBendsWithItsGranularityAndThenConverges(fixture)
        assertAGeometryBehindTheLimbPaintsNothing(fixture)
    } finally {
        deleteGeometryPipeline(binding, programs, pipeline)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * The claim that protects three shipped releases: **byte-identical**, not "close enough".
 *
 * Six granularities rather than one, because the ways this could go wrong scale differently. One
 * cell catches a changed vertex order or a changed diagonal; two and four catch an interior edge
 * that leaks background through it; sixteen and thirty-two catch a boundary node that has drifted
 * off the quad's own edge by a rounding, since the number of chances to drift grows with the node
 * count while the size of each drift does not.
 *
 * The painted-pixel check is the vacuity guard, and it is not decoration: two blank frames are
 * byte-identical too, and so are two frames whose quad fell entirely outside the viewport.
 */
private fun assertSubdividingAMercatorGeometryMovesNoPixel(fixture: GeometrySubdivisionFixture) {
    val reference = fixture.renderTheShippedFourCornerStrip()
    val painted = reference.paintedPixels()
    val total = GEOMETRY_SUBDIVISION_READBACK_PIXELS * GEOMETRY_SUBDIVISION_READBACK_PIXELS
    println("RenG geometry subdivision readback: the shipped strip paints $painted of $total pixels")
    assertTrue(
        painted > total / 20 && painted < total * 4 / 5,
        "the fixture must paint a real quad with background around it, painted $painted of $total",
    )

    // The production Mercator granularity, asserted rather than assumed: a plane does not depart
    // from itself, so nothing below this line is what a `0.4.0` frame actually draws -- it is the
    // subdivision the erratum permits, forced, so that "it would have changed nothing" is measured.
    assertEquals(1, geometryCellsPerSide(fixture.mercatorGeometry, fixture.mercatorCamera))

    assertEquals(
        0,
        reference.differingBytes(fixture.renderMercatorGrid(1)),
        "the granularity a Mercator frame actually chooses must reproduce the shipped strip byte " +
            "for byte -- measured at 0 on both Apple M3 Max and Apple Software Renderer",
    )

    listOf(2, 4, 8, 16, 32).forEach { cellsPerSide ->
        val subdivided = fixture.renderMercatorGrid(cellsPerSide)
        val pixels = reference.differingPixels(subdivided)
        val coverage = reference.coverageDifferences(subdivided)
        println(
            "RenG geometry subdivision readback: $cellsPerSide cells a side differs over " +
                "${reference.differingBytes(subdivided)} bytes, $pixels pixels, " +
                "$coverage of them coverage, " +
                "worst channel delta ${reference.worstChannelDelta(subdivided)}",
        )
        assertTrue(
            pixels <= MAXIMUM_SUBDIVISION_ROUNDING_PIXELS,
            "subdividing a Mercator Geometry into $cellsPerSide cells a side moved $pixels pixels, " +
                "which is more than last-bit rounding can account for",
        )
        assertTrue(
            coverage <= MAXIMUM_SUBDIVISION_COVERAGE_PIXELS,
            "subdividing into $cellsPerSide cells a side changed the coverage of $coverage pixels; " +
                "an interior edge leaking background would be counted in the hundreds",
        )
    }
}

/**
 * ADR 0040's promise that nothing already shipped changes: **the two altitude modes are identical
 * wherever the ground is flat**, measured in pixels rather than argued from a diff that looks
 * additive.
 *
 * Flat is what this fixture is — the terrainless path every published release draws, and 28 of the
 * 34 corpus styles — so a `GROUND_RELATIVE` quad and an `ABSOLUTE` one must read back byte for byte.
 * ADR 0040 keeps that true past the cycle that resolves the mode: where terrain is absent or
 * degraded, `GROUND_RELATIVE` resolves against the flat ground and *means* `ABSOLUTE`.
 *
 * **The third frame is why the first two agreeing means anything.** Two frames are byte-identical
 * whenever the instrument cannot see the thing being changed, so this raises both corners by 400
 * metres and requires that to move a substantial part of the quad — under a camera pitched 45
 * degrees it does. Without it, a probe blind to altitude entirely would report the same zero and
 * look like proof.
 */
private fun assertAnAltitudeModeMovesNoPixelOverFlatGround(fixture: GeometrySubdivisionFixture) {
    val absolute = fixture.renderMercatorGrid(1)
    val groundRelative = fixture.renderMercatorGrid(1, fixture.groundRelativeMercatorGeometry)
    val raised = fixture.renderMercatorGrid(1, fixture.raisedMercatorGeometry)
    val total = GEOMETRY_SUBDIVISION_READBACK_PIXELS * GEOMETRY_SUBDIVISION_READBACK_PIXELS
    val sensitivity = absolute.differingPixels(raised)
    println(
        "RenG geometry subdivision readback: over flat ground the two altitude modes differ over " +
            "${absolute.differingBytes(groundRelative)} bytes, while 400 metres of altitude moves " +
            "$sensitivity of $total pixels",
    )

    assertTrue(
        absolute.paintedPixels() > total / 20,
        "the altitude-mode comparison must be made on a frame that paints a real quad",
    )
    assertTrue(
        sensitivity > total / 20,
        "this readback must be able to see an altitude change at all, or agreeing about one proves " +
            "nothing; 400 metres moved $sensitivity pixels of $total",
    )
    assertEquals(
        0,
        absolute.differingBytes(groundRelative),
        "over flat ground a GROUND_RELATIVE Geometry must read back byte for byte identically to an " +
            "ABSOLUTE one",
    )
}

/**
 * The globe arm, drawn through the identical consumer shader, the identical `drawGeometry` and the
 * identical camera — with only the **granularity** changed.
 *
 * That is what makes this case discriminating where the obvious one is not. Comparing a globe frame
 * against a Mercator frame proves nothing: the two use different cameras and different projections,
 * so they would differ under any implementation, including one that had replaced the sphere with a
 * tangent plane. Comparing one cell against eight isolates the only thing that can possibly make the
 * difference — that the map from a `(u, v)` to a position is **nonlinear**. Under Mercator the same
 * comparison is the loop above, which measures tens of pixels of last-bit rounding; here it must be
 * a different picture outright.
 *
 * The fixture is at zoom 2.5 because the spec's own sagitta numbers put a frame-sized quad 0.44
 * logical pixels off the sphere by zoom 10, so a fixture up there would report a globe and a plane
 * as the same picture while looking thorough.
 *
 * The second half is the granularity rule answering for itself: the density it derives from the
 * camera must already be converged, so refining it four times over changes almost nothing. A rule
 * that returned a number far too small would show up here and nowhere else.
 */
private fun assertAGlobeGeometryBendsWithItsGranularityAndThenConverges(
    fixture: GeometrySubdivisionFixture,
) {
    val total = GEOMETRY_SUBDIVISION_READBACK_PIXELS * GEOMETRY_SUBDIVISION_READBACK_PIXELS
    val oneCell = fixture.renderCurvedGlobe(1)
    val eightCells = fixture.renderCurvedGlobe(8)
    val bend = oneCell.differingPixels(eightCells)
    println(
        "RenG geometry subdivision readback: on a globe at zoom $CURVED_ZOOM one cell and eight " +
            "differ over $bend pixels of $total, painting ${oneCell.paintedPixels()} and " +
            "${eightCells.paintedPixels()}",
    )
    assertTrue(oneCell.paintedPixels() > total / 20, "the globe fixture must paint something")
    assertTrue(
        bend > total / 20,
        "a globe whose projection was secretly linear would agree here; measured $bend of $total",
    )

    val chosen = fixture.curvedGlobeCellsPerSide
    val refined = fixture.renderCurvedGlobe(minOf(chosen * 4, 128))
    assertTrue(chosen > 1, "a 30-degree quad at zoom 2.5 is not one cell on a sphere")
    // Pixels differing by a single unit are not the measurement here: the probe shader paints the
    // interpolated texture coordinate, so half a logical pixel of geometric refinement moves a
    // smooth gradient across a byte boundary almost everywhere. What converges is the silhouette.
    val ladder = listOf(2, 4, 8, chosen).associateWith { cells ->
        fixture.renderCurvedGlobe(cells).coverageDifferences(refined)
    }
    println(
        "RenG geometry subdivision readback: globe silhouette against ${minOf(chosen * 4, 128)} " +
            "cells a side, by granularity: $ladder",
    )
    assertTrue(
        ladder.getValue(chosen) <= MAXIMUM_CONVERGED_SILHOUETTE_PIXELS,
        "the granularity the camera implies must already be converged; at $chosen cells a side the " +
            "silhouette still moves by ${ladder.getValue(chosen)} pixels",
    )
}

/**
 * The negative that stops the case above passing vacuously, and the extent bound in pixels: a
 * geometry on the far side of the planet is pruned before a vertex is projected, so the frame stays
 * exactly as the clear left it.
 *
 * A cleared frame proves nothing on its own — a broken pipeline clears just as well — which is why
 * it is asserted only alongside two cases that do paint through the same pipeline in the same run.
 */
private fun assertAGeometryBehindTheLimbPaintsNothing(fixture: GeometrySubdivisionFixture) {
    val frame = fixture.renderAntipodalGlobe()
    assertEquals(
        0,
        frame.paintedPixels(),
        "a Geometry behind the limb must cost no triangles and paint no pixels",
    )
}

private class GeometrySubdivisionFixture(
    private val binding: GlBinding,
    private val pipeline: GeometryPipeline,
    private val target: Int,
) {
    /**
     * A quad about 120 logical pixels a side at zoom 12, with **two different non-zero altitudes**,
     * seen from a camera pitched 45 degrees and turned 23.
     *
     * Every number in that sentence is defending against a way the comparison could agree while
     * saying nothing. Different altitudes tilt the quad out of the map plane, so the four corners
     * span a genuinely oblique plane rather than a horizontal one. The pitch makes the map-to-screen
     * map projective rather than affine. The bearing keeps the quad off both screen axes, so a
     * subdivision that leaked along one axis only cannot hide. And the quad is smaller than the
     * viewport, deliberately: a quad reaching far outside it is the exact shape
     * `measureLargeQuadRasterisation` finds `Apple Software Renderer` dropping, and comparing two
     * differently tessellated versions of that shape would measure the driver rather than RenG.
     */
    val mercatorGeometry: Geometry = Geometry(
        topLeft = Vector3(55.0060, 11.9900, 240.0),
        bottomRight = Vector3(54.9940, 12.0100, 60.0),
        shaderPair = GEOMETRY_PROBE_SHADER_PAIR,
    )

    val mercatorCamera: ResolvedMercatorCamera =
        resolvedMercatorCamera(zoom = 12.0, bearing = 23.0, pitch = 45.0)

    private val curvedGeometry: Geometry = Geometry(
        topLeft = Vector3(65.0, -3.0, 0.0),
        bottomRight = Vector3(45.0, 27.0, 0.0),
        shaderPair = GEOMETRY_PROBE_SHADER_PAIR,
    )

    private val curvedGlobeCamera: ResolvedGlobeCamera = (
        resolveGlobeCamera(
            Camera(latitude = 55.0, unwrappedLongitude = 12.0, zoom = CURVED_ZOOM, bearing = 0.0, pitch = 0.0),
            OutputPixelSize(GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS),
        ) as SpatialOutcome.Success<ResolvedGlobeCamera>
        ).value

    /**
     * The draw `0.3.0` issues, reproduced here rather than kept alive in production code.
     *
     * Corner order and texture coordinates are `buildInterleavedVertexBytes`'s exactly — bottom-left,
     * bottom-right, top-left, top-right, with the north edge at `v = 0` — and the draw is the same
     * `drawArrays(GL_TRIANGLE_STRIP, 0, 4)`. The vertex format is unchanged by this cycle, so the
     * pipeline's own vertex array serves both this and [renderMercatorGrid] without an edit.
     */
    fun renderTheShippedFourCornerStrip(): GeometryFrame {
        val corners = (resolveGeometry(mercatorGeometry, mercatorCamera) as SpatialOutcome.Success)
            .value.cornersClockwiseFromTopLeft
        fun corner(index: Int) = corners[index]
        val strip = floatArrayOf(
            corner(3).x.toFloat(), corner(3).y.toFloat(), corner(3).z.toFloat(), 0.0f, 1.0f,
            corner(2).x.toFloat(), corner(2).y.toFloat(), corner(2).z.toFloat(), 1.0f, 1.0f,
            corner(0).x.toFloat(), corner(0).y.toFloat(), corner(0).z.toFloat(), 0.0f, 0.0f,
            corner(1).x.toFloat(), corner(1).y.toFloat(), corner(1).z.toFloat(), 1.0f, 0.0f,
        )
        return render {
            val bytes = littleEndianBytes(strip)
            binding.useProgram(pipeline.program)
            binding.bindVertexArray(pipeline.vertexArray)
            binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
            binding.bufferData(GL_ARRAY_BUFFER, bytes.size, bytes, GL_DYNAMIC_DRAW)
            binding.uniformMatrix4fv(
                pipeline.modelViewProjectionLocation,
                1,
                false,
                composeGeometryViewProjection(mercatorCamera),
            )
            binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /** The same quad, asked for as an offset above the ground instead of above the ellipsoid. */
    val groundRelativeMercatorGeometry: Geometry =
        mercatorGeometry.copy(altitudeMode = AltitudeMode.GROUND_RELATIVE)

    /** The same quad 400 metres higher, which exists only to prove the readback can see altitude. */
    val raisedMercatorGeometry: Geometry = mercatorGeometry.copy(
        topLeft = Vector3(55.0060, 11.9900, 640.0),
        bottomRight = Vector3(54.9940, 12.0100, 460.0),
    )

    fun renderMercatorGrid(
        cellsPerSide: Int,
        geometry: Geometry = mercatorGeometry,
    ): GeometryFrame {
        val grid = (
            geometryGrid(geometry, mercatorCamera, cellsPerSide) as SpatialOutcome.Success
            ).value
        assertEquals(
            (cellsPerSide + 1) * (cellsPerSide + 1),
            grid.interleavedVertices.size / GEOMETRY_VERTEX_COMPONENT_COUNT,
            "a Mercator grid prunes nothing, so it carries every node",
        )
        return render { drawGrid(grid, composeGeometryViewProjection(mercatorCamera)) }
    }

    val curvedGlobeCellsPerSide: Int get() = geometryCellsPerSide(curvedGeometry, curvedGlobeCamera)

    fun renderCurvedGlobe(cellsPerSide: Int): GeometryFrame {
        val grid = (
            geometryGrid(curvedGeometry, curvedGlobeCamera, cellsPerSide) as SpatialOutcome.Success
            ).value
        return render { drawGrid(grid, composeGeometryViewProjection(curvedGlobeCamera)) }
    }

    fun renderAntipodalGlobe(): GeometryFrame {
        val antipodal = Geometry(
            topLeft = Vector3(20.0, -178.0, 0.0),
            bottomRight = Vector3(-20.0, -158.0, 0.0),
            shaderPair = GEOMETRY_PROBE_SHADER_PAIR,
        )
        val grid = (geometryGrid(antipodal, curvedGlobeCamera) as SpatialOutcome.Success).value
        assertEquals(0, grid.triangleCount, "the far side of the planet is pruned before it is projected")
        return render { drawGrid(grid, composeGeometryViewProjection(curvedGlobeCamera)) }
    }

    private fun drawGrid(
        grid: GeometryGrid,
        modelViewProjection: FloatArray,
    ) {
        drawGeometry(
            binding = binding,
            pipeline = pipeline,
            grid = grid,
            modelViewProjection = modelViewProjection,
            resolutionWidthPixels = GEOMETRY_SUBDIVISION_READBACK_PIXELS.toFloat(),
            resolutionHeightPixels = GEOMETRY_SUBDIVISION_READBACK_PIXELS.toFloat(),
            boundsWestSouthEastNorthDegrees = floatArrayOf(0f, 0f, 0f, 0f),
            frameIndex = 0L,
        )
    }

    private fun resolvedMercatorCamera(
        zoom: Double,
        bearing: Double,
        pitch: Double,
    ): ResolvedMercatorCamera = (
        resolveMercatorCamera(
            Camera(
                latitude = 55.0,
                unwrappedLongitude = 12.0,
                zoom = zoom,
                bearing = bearing,
                pitch = pitch,
            ),
            OutputPixelSize(GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS),
        ) as SpatialOutcome.Success<ResolvedMercatorCamera>
        ).value

    private fun render(draw: () -> Unit): GeometryFrame {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target)
        binding.viewport(0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS)
        binding.disable(GL_SCISSOR_TEST)
        binding.disable(GL_DEPTH_TEST)
        binding.disable(GL_CULL_FACE)
        binding.disable(GL_BLEND)
        binding.colorMask(true, true, true, true)
        binding.clearColor(0f, 0f, 0f, 1f)
        binding.clear(GL_COLOR_BUFFER_BIT)
        binding.bindSampler(0, 0)

        draw()

        val bytes = ByteArray(
            GEOMETRY_SUBDIVISION_READBACK_PIXELS * GEOMETRY_SUBDIVISION_READBACK_PIXELS * 4,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, bytes,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        return GeometryFrame(bytes)
    }
}

private class GeometryFrame(val bytes: ByteArray) {
    /** Anything the clear did not leave behind. The probe shader's blue channel is a constant 0.25,
     * so every painted pixel differs from the cleared one whatever its texture coordinate — a quad
     * corner at `(0, 0)` included. */
    fun paintedPixels(): Int = (0 until bytes.size / 4).count { pixel ->
        bytes[pixel * 4] != ZERO || bytes[pixel * 4 + 1] != ZERO || bytes[pixel * 4 + 2] != ZERO
    }

    fun differingBytes(other: GeometryFrame): Int = bytes.indices.count { bytes[it] != other.bytes[it] }

    fun differingPixels(other: GeometryFrame): Int = (0 until bytes.size / 4).count { pixel ->
        (0 until 4).any { bytes[pixel * 4 + it] != other.bytes[pixel * 4 + it] }
    }

    fun coverageDifferences(other: GeometryFrame): Int = (0 until bytes.size / 4).count { pixel ->
        painted(pixel) != other.painted(pixel)
    }

    fun worstChannelDelta(other: GeometryFrame): Int = bytes.indices.maxOf { index ->
        val mine = bytes[index].toInt() and 0xff
        val theirs = other.bytes[index].toInt() and 0xff
        if (mine > theirs) mine - theirs else theirs - mine
    }

    private fun painted(pixel: Int): Boolean =
        bytes[pixel * 4] != ZERO || bytes[pixel * 4 + 1] != ZERO || bytes[pixel * 4 + 2] != ZERO

    private companion object {
        const val ZERO: Byte = 0
    }
}

/**
 * A consumer's shader pair, written the way ADR 0008 says one is written: `#version 300 es`, the
 * documented names it wants and no others, and no RenG preamble of any kind. RenG substitutes the
 * version directive on a desktop context and changes nothing else — which is the claim this suite
 * exercises on both dialects.
 *
 * The fragment stage paints the interpolated texture coordinate rather than sampling anything,
 * because the comparison this suite makes is about *where an attribute lands*, and a texture fetch
 * would quantise that onto texels and hide it.
 */
private val GEOMETRY_PROBE_SHADER_PAIR: ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\n" +
        "precision highp float;\n" +
        "in vec3 aPosition;\n" +
        "in vec2 aTexCoord;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "out vec2 vProbeTexCoord;\n" +
        "void main() {\n" +
        "    vProbeTexCoord = aTexCoord;\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n",
    fragmentSource = "#version 300 es\n" +
        "precision highp float;\n" +
        "in vec2 vProbeTexCoord;\n" +
        "out vec4 rengProbeOut;\n" +
        "void main() {\n" +
        "    rengProbeOut = vec4(vProbeTexCoord.x, vProbeTexCoord.y, 0.25, 1.0);\n" +
        "}\n",
)

private fun createGeometrySubdivisionTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8,
        GEOMETRY_SUBDIVISION_READBACK_PIXELS, GEOMETRY_SUBDIVISION_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the geometry subdivision readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

/**
 * The most pixels last-bit rounding may move when a Mercator geometry is subdivided past the one
 * cell its camera asks for.
 *
 * Measured at 58 on `Apple M3 Max` and 6 on `Apple Software Renderer`, over granularities from 2 to
 * 32, out of 12,103 the quad paints. The budget is four times the worse of those and still two
 * orders of magnitude below what any real defect produces: a dropped interior edge leaks background
 * along a whole seam, and a mis-set texture coordinate changes every pixel of the quad.
 */
private const val MAXIMUM_SUBDIVISION_ROUNDING_PIXELS: Int = 256

/**
 * And how many of those may change from painted to background or back.
 *
 * Measured at **one** pixel, at 16 and 32 cells a side on `Apple M3 Max` and none at all on the
 * software rasteriser: a boundary node lerped in `Double` and narrowed to `Float` does not land
 * exactly on the chord the four-corner quad rasterised, and once in a frame that ULP falls across a
 * subpixel boundary. This is the "up to floating-point rounding in the last bits" ADR 0008's erratum
 * writes down, measured rather than assumed — and it is why the production granularity is the one
 * asserted at zero.
 */
private const val MAXIMUM_SUBDIVISION_COVERAGE_PIXELS: Int = 4

/**
 * How far the drawn silhouette may still move when the granularity the camera chose is refined four
 * times over.
 *
 * Measured across the whole ladder, against 64 cells a side: **932** pixels at 2 cells, 178 at 4, 38
 * at 8 and **12** at the 16 the rule actually picks — on both Apple rasterisers, within a pixel. The
 * budget sits between the rule's own figure and the next coarser one it rejected, so it
 * distinguishes them rather than admitting both, which is the same shape the globe ground suite's
 * convergence budget takes.
 */
private const val MAXIMUM_CONVERGED_SILHOUETTE_PIXELS: Int = 24

/** The zoom at which a 30-degree quad still fits in the frame **and** bows 21 logical pixels off its
 * own corner plane. Above about zoom 12 a globe and a tangent plane are the same picture, so a
 * fixture up there would pass with the sphere replaced by a plane. */
private const val CURVED_ZOOM: Double = 2.5

internal const val GEOMETRY_SUBDIVISION_READBACK_PIXELS: Int = 256
