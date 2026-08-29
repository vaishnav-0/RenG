package com.rohittp.reng.internal.gl

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The lattice both grounds draw, read in **Mercator's** frame.
 *
 * `GlobeGroundPipelineTest` already reads the same numbers as a sphere; nothing here restates that.
 * What is asserted here is the reading `GROUND_VERTEX_SOURCE` puts on them — a vertex is
 * `(u - 0.5, 0.5 - v)` in the tile's own map axes — and the one claim Cycle E-terrain owes before it
 * displaces anything: **an undisplaced grid at any granularity covers exactly the square the
 * four-vertex quad covered, with exactly the same texture coordinates on its boundary.**
 *
 * The Mercator reading is spelled out again in [positions] rather than read off the shader, because
 * a shader source is text and no unit test can execute it. That is the seam this file cannot close;
 * [GroundPipelineTest.theVertexStagePutsTextureRowZeroAtTheNorthEdge] pins the text against this
 * reading, and `runBasemapReadbackSuite`'s asymmetric fixture is what proves the pair agree in
 * pixels on a real driver.
 */
class GroundGridTest {
    /**
     * The exact four `(x, y, u, v)` rows `GROUND_QUAD` shipped with, in `0.3.0` and every release
     * before Cycle E-terrain, kept here as the reference rather than in production as a constant
     * nothing draws.
     *
     * A single cell must reproduce them as a set — the order differs, because the quad was a
     * triangle strip from the south-west and the grid is row-major from the north-west, and because
     * the two triangulations take opposite diagonals. Neither difference can move a pixel: the quad
     * is planar, so a projective map of it is exact under perspective-correct interpolation whichever
     * diagonal splits it.
     */
    private val quadBeforeTheGrid: Set<List<Float>> = setOf(
        listOf(-0.5f, -0.5f, 0.0f, 1.0f),
        listOf(0.5f, -0.5f, 1.0f, 1.0f),
        listOf(-0.5f, 0.5f, 0.0f, 0.0f),
        listOf(0.5f, 0.5f, 1.0f, 0.0f),
    )

    @Test fun aSingleCellIsExactlyTheFourVerticesTheQuadShipped() {
        val vertices = positions(1)
        assertEquals(4, vertices.size)
        assertEquals(quadBeforeTheGrid, vertices.toSet())
        assertEquals(6, groundGridIndices(1).size, "two triangles, as the strip drew two")
    }

    /**
     * The claim that matters, at every granularity a frame can ask for: the grid's triangles tile the
     * quad's square **exactly** — no gap, no overlap, no reversed cell.
     *
     * Signed area is the instrument because it fails in three independent ways at once. A gap or an
     * overlap moves the total off 1; a triangle wound the wrong way subtracts instead of adding; and
     * an outline that is not the unit square gets the wrong total outright. A per-vertex bounds check
     * would catch none of the three.
     */
    @Test fun theGridTilesTheQuadsSquareExactlyAtEveryGranularity() {
        granularities().forEach { cells ->
            val vertices = positions(cells)
            val indices = groundGridIndices(cells)
            var total = 0.0
            for (offset in indices.indices step 3) {
                total += signedArea(
                    vertices[indices[offset].toInt()],
                    vertices[indices[offset + 1].toInt()],
                    vertices[indices[offset + 2].toInt()],
                )
            }
            assertTrue(
                abs(total - 1.0) < 1e-9,
                "at $cells cells a side the grid covers $total of the quad's unit square",
            )
        }
    }

    /**
     * Every triangle faces the camera, which is `drawFrame`'s scene-wide `GL_CCW` front face.
     *
     * This is asserted on the numbers because no driver can catch it: `drawGround` **disables**
     * culling, so a Mercator frame draws a reversed grid and a correct one identically. The globe,
     * where the same index run is ADR 0038's far-hemisphere cull, would notice — which is precisely
     * why one shared triangulation needs an assertion on this side of the seam too.
     */
    @Test fun everyTriangleWindsCounterClockwiseInTheMercatorFrame() {
        granularities().forEach { cells ->
            val vertices = positions(cells)
            val indices = groundGridIndices(cells)
            for (offset in indices.indices step 3) {
                val area = signedArea(
                    vertices[indices[offset].toInt()],
                    vertices[indices[offset + 1].toInt()],
                    vertices[indices[offset + 2].toInt()],
                )
                assertTrue(
                    area > 0.0,
                    "triangle ${offset / 3} of a $cells-cell grid winds clockwise (area $area)",
                )
            }
        }
    }

    /**
     * `v` runs north to south: row zero carries `v = 0` exactly, and `v` grows strictly downward.
     *
     * Row zero of a rendered basemap tile is its north edge and `uploadTexture` uploads it first, so
     * a flip here mirrors every tile about its own centre line — invisible on a solid tile, ruinous
     * on a real map. The globe grid reads the identical numbers, so this convention is the one thing
     * the two grounds are least able to disagree about and the most expensive to get wrong.
     */
    @Test fun vRunsNorthToSouthWithRowZeroAtTheNorthEdge() {
        granularities().forEach { cells ->
            val perSide = cells + 1
            val vertices = groundGridVertices(cells)
            for (row in 0 until perSide) {
                val v = vertices[row * perSide * 2 + 1]
                assertEquals(row.toFloat() / cells.toFloat(), v, "row $row of a $cells-cell grid")
                if (row == 0) assertEquals(0.0f, v, "row zero must sample the tile's north edge")
                if (row == cells) assertEquals(1.0f, v, "the last row must sample the south edge")
                for (column in 0 until perSide) {
                    assertEquals(
                        v,
                        vertices[(row * perSide + column) * 2 + 1],
                        "a row must sit at one v: row $row, column $column",
                    )
                    assertEquals(
                        column.toFloat() / cells.toFloat(),
                        vertices[(row * perSide + column) * 2],
                        "u must run west to east: row $row, column $column",
                    )
                }
            }
        }
    }

    /**
     * The boundary is the quad's boundary, exactly — `0` and `1` and nothing near them.
     *
     * `mix(west, east, u)` returns its endpoints exactly at `u = 0` and `u = 1`, which is what closes
     * the seam between two neighbouring tiles by construction rather than by a skirt or a border
     * ring. That guarantee is worth nothing if the lattice's own endpoints are `0.9999999` at some
     * granularity, so the exactness is asserted rather than assumed.
     */
    @Test fun theOutlineCoincidesWithTheQuadsToTheBit() {
        granularities().forEach { cells ->
            positions(cells).forEach { (x, y, u, v) ->
                assertTrue(u in 0.0f..1.0f && v in 0.0f..1.0f, "a grid coordinate is a unit coordinate")
                assertTrue(x in -0.5f..0.5f && y in -0.5f..0.5f, "a grid vertex stays inside the tile")
                if (u == 0.0f) assertEquals(-0.5f, x, "the west edge is the quad's west edge")
                if (u == 1.0f) assertEquals(0.5f, x, "the east edge is the quad's east edge")
                if (v == 0.0f) assertEquals(0.5f, y, "the north edge is the quad's north edge")
                if (v == 1.0f) assertEquals(-0.5f, y, "the south edge is the quad's south edge")
            }
            val vertices = positions(cells)
            assertEquals(4, vertices.count { (x, y) -> abs(x) == 0.5f && abs(y) == 0.5f }, "four corners")
        }
    }

    @Test fun everyIndexAddressesAVertexThatExists() {
        granularities().forEach { cells ->
            val vertexCount = (cells + 1) * (cells + 1)
            val indices = groundGridIndices(cells)
            assertEquals(6 * cells * cells, indices.size, "two triangles a cell at $cells cells a side")
            indices.forEach { index ->
                assertTrue(index.toInt() in 0 until vertexCount, "index $index is outside $vertexCount vertices")
            }
        }
    }

    /**
     * 128 cells a side and not 129, because the index run is 16-bit: `129 * 129 = 16,641` vertices
     * fit and `257 * 257 = 66,049` do not. The cap is the index type's, so it is the same number in
     * both projections and is bound to the globe's rather than restated.
     */
    @Test fun theCapIsTheSixteenBitIndexCapAndBothGroundsShareIt() {
        assertEquals(128, MAXIMUM_GROUND_CELLS_PER_TILE_SIDE)
        assertEquals(MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE, MAXIMUM_GROUND_CELLS_PER_TILE_SIDE)
        assertTrue((MAXIMUM_GROUND_CELLS_PER_TILE_SIDE + 1) * (MAXIMUM_GROUND_CELLS_PER_TILE_SIDE + 1) <= 65536)
        val binding = RecordingGlBinding()
        assertFailsWith<IllegalArgumentException> { groundGrid(binding, mutableMapOf(), 0) }
        assertFailsWith<IllegalArgumentException> {
            groundGrid(binding, mutableMapOf(), MAXIMUM_GROUND_CELLS_PER_TILE_SIDE + 1)
        }
    }

    /**
     * One grid per granularity, built on first use and handed back by identity afterwards.
     *
     * A grid rebuilt per call would pass every geometric assertion above and every pixel in the
     * readback suites, and would leak a vertex array and two buffers per tile per frame, so it is
     * asserted on the call log and on object identity rather than on what it draws.
     */
    @Test fun aGranularityIsBuiltOnceAndHandedBackAfterwards() {
        val binding = RecordingGlBinding()
        val grids = mutableMapOf<Int, GroundGrid>()

        val first = groundGrid(binding, grids, 4)
        val again = groundGrid(binding, grids, 4)

        assertSame(first, again, "the second ask must reuse the built grid, not rebuild it")
        assertEquals(1, binding.log.count { it.startsWith("genVertexArrays") })
        assertEquals(2, binding.log.count { it.startsWith("genBuffers") }, "one vertex, one index")
        assertEquals(1, grids.size)

        val coarser = groundGrid(binding, grids, 8)
        assertEquals(2, grids.size, "a second granularity is a second cached grid")
        assertEquals(2, binding.log.count { it.startsWith("genVertexArrays") })
        assertTrue(coarser.vertexArray != first.vertexArray)
        assertEquals(8, coarser.cellsPerSide)
        assertEquals(6 * 8 * 8, coarser.indexCount)
    }

    /**
     * What actually reaches the driver, decoded back.
     *
     * The lattice assertions above all read the `FloatArray` the generator returns; a wrong stride, a
     * wrong byte order or a `bufferData` given the wrong array would leave every one of them green
     * and draw nothing recognisable. This decodes both payloads out of the recording binding and
     * compares them to the generators.
     */
    @Test fun bothBuffersReachTheDriverAsLittleEndianCopiesOfTheGenerators() {
        val binding = RecordingGlBinding()
        val grid = groundGrid(binding, mutableMapOf(), 2)

        val vertexBytes = requireNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER])
        val indexBytes = requireNotNull(binding.bufferDataPayloads[GL_ELEMENT_ARRAY_BUFFER])
        assertContentEquals(groundGridVertices(2).toList(), decodeFloats(vertexBytes))
        assertContentEquals(groundGridIndices(2).toList(), decodeShorts(indexBytes))
        assertEquals(9 * 2 * 4, vertexBytes.size, "nine vertices of two floats")
        assertEquals(grid.indexCount * 2, indexBytes.size, "twenty-four sixteen-bit indices")
        assertTrue(
            binding.log.contains("vertexAttribPointer(0,2,${hex(GL_FLOAT)},false,8,0)"),
            "a grid vertex is two tightly packed floats: ${binding.log}",
        )
        assertEquals(
            1,
            binding.log.count { it.startsWith("enableVertexAttribArray") },
            "the grid carries one attribute, not a position and a texture coordinate",
        )
    }

    @Test fun deletingTheGridsFreesEveryBufferAndEmptiesTheMap() {
        val binding = RecordingGlBinding()
        val grids = mutableMapOf<Int, GroundGrid>()
        groundGrid(binding, grids, 2)
        groundGrid(binding, grids, 16)
        binding.log.clear()

        deleteGroundGrids(binding, grids)

        assertEquals(2, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(
            2,
            binding.log.count { it.startsWith("deleteBuffers(2") },
            "a vertex array frees no buffer of its own: both must be named",
        )
        assertTrue(grids.isEmpty())
    }

    /** The Mercator reading of the lattice: `(x, y, u, v)` per vertex, as the vertex stage derives it. */
    private fun positions(cellsPerSide: Int): List<List<Float>> {
        val raw = groundGridVertices(cellsPerSide)
        return (0 until raw.size / 2).map { index ->
            val u = raw[index * 2]
            val v = raw[index * 2 + 1]
            listOf(u - 0.5f, 0.5f - v, u, v)
        }
    }

    private fun signedArea(a: List<Float>, b: List<Float>, c: List<Float>): Double {
        val abx = (b[0] - a[0]).toDouble()
        val aby = (b[1] - a[1]).toDouble()
        val acx = (c[0] - a[0]).toDouble()
        val acy = (c[1] - a[1]).toDouble()
        return (abx * acy - aby * acx) / 2.0
    }

    private fun decodeFloats(bytes: ByteArray): List<Float> = (0 until bytes.size / 4).map { index ->
        Float.fromBits(
            (bytes[index * 4].toInt() and 0xff) or
                ((bytes[index * 4 + 1].toInt() and 0xff) shl 8) or
                ((bytes[index * 4 + 2].toInt() and 0xff) shl 16) or
                ((bytes[index * 4 + 3].toInt() and 0xff) shl 24),
        )
    }

    private fun decodeShorts(bytes: ByteArray): List<Short> = (0 until bytes.size / 2).map { index ->
        (
            (bytes[index * 2].toInt() and 0xff) or
                ((bytes[index * 2 + 1].toInt() and 0xff) shl 8)
            ).toShort()
    }

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

    /** One cell, the smallest subdivision, two powers of two in between, and the cap. */
    private fun granularities(): List<Int> = listOf(1, 2, 4, 16, MAXIMUM_GROUND_CELLS_PER_TILE_SIDE)
}
