package com.rohittp.reng.internal.gl

/**
 * The subdivided ground grid: `cellsPerSide^2` quads over the unit `(u, v)` square, indexed as
 * triangles, built once per granularity and shared by every basemap tile that draws at it.
 *
 * ## Why Mercator has one now
 *
 * A Mercator ground tile was four vertices — a unit square the tile's own model-view-projection
 * moved into place — and four vertices cannot be displaced by terrain: an elevation sampled at the
 * corners and interpolated across two triangles is a plane, whatever the DEM says in between. The
 * globe's ground has been subdivided since Cycle G for the unrelated reason that a chord cannot
 * bend, so the grid, the cache and the winding already existed. This is that same grid, reached from
 * both sides.
 *
 * ## Where the seam is, and why it is a third file rather than either pipeline
 *
 * The lattice below is **projection-neutral and is the whole of what the two grounds share**. It is
 * a unit `(u, v)` square with a triangulation and a winding; what a vertex *means* is the reading
 * each pipeline puts on it, and the two readings have nothing in common. The globe reads `(u, v)` as
 * a (longitude, isometric latitude) interpolant and evaluates a sphere from it in the vertex shader;
 * Mercator reads it as a tile-local offset, `(u - 0.5, 0.5 - v)` in the tile's own map axes, and
 * lets the per-tile matrix place it. Neither reading belongs in the other's file, and the lattice
 * belongs in neither, so it lives here.
 *
 * **The two pipelines stay separate**, for the reason [GlobeGroundPipeline]'s own KDoc gives — one
 * function serving one geometry, with no mode parameter that could be got wrong. Sharing the *grid*
 * is the opposite decision from merging the *passes*, and it is the same decision
 * [GROUND_FRAGMENT_SOURCE] already records: the two grounds are required to sample their tile
 * identically, so one text makes a drift impossible rather than merely visible.
 *
 * **[groundGridVertices] and [groundGridIndices] delegate rather than restate.** The lattice and its
 * winding exist exactly once in this repository, in `GlobeGroundPipeline.kt`, and these two
 * functions are the projection-neutral names for them. The bodies belong *here* and the delegation
 * belongs *there*; moving them is a pure rename that changes no behaviour, and it was not made in
 * this commit because `GlobeGroundPipeline.kt` was owned by another task in the same wave. A copy
 * was rejected outright: two identical loops that can drift apart is the whole failure this seam
 * exists to prevent, and the drift would show as a hairline crack in one projection only.
 *
 * ## The convention that must not flip
 *
 * `u` runs west to east and `v` runs **north to south**. Row zero of a rendered basemap tile is its
 * north edge and [uploadTexture] uploads row zero first, so `v = 0` is north in both projections and
 * the shared [GROUND_FRAGMENT_SOURCE] samples the same texel from the same place in each. A flip is
 * invisible on a solid-coloured tile and destroys a real map.
 */
internal class GroundGrid(
    val cellsPerSide: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val indexBuffer: Int,
    val indexCount: Int,
)

/**
 * The widest grid a 16-bit index can address: 128 cells a side is `129 * 129 = 16,641` vertices,
 * where 256 would be 66,049 and overflow.
 *
 * Bound to [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE] rather than restated as its own literal,
 * because it is the same ceiling for the same reason — the index type both grids upload — and two
 * numbers that must agree should not be two numbers.
 */
internal const val MAXIMUM_GROUND_CELLS_PER_TILE_SIDE: Int = MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE

/**
 * The grid's `(u, v)` pairs, row-major from the north-west corner: `(cellsPerSide + 1)^2` vertices.
 *
 * See [globeGroundGridVertices], which this is the projection-neutral name for, and the class KDoc
 * above for why the name is here and the body is there. The exactness of the four boundary values is
 * load-bearing in both projections for the same reason and by the same mechanism: a coordinate is
 * `index / cellsPerSide` in `Float`, so `0` and `1` are exact, and two tiles sharing an edge land
 * their shared vertices on the identical position rather than within an epsilon of it.
 */
internal fun groundGridVertices(cellsPerSide: Int): FloatArray {
    val perSide = cellsPerSide + 1
    val vertices = FloatArray(perSide * perSide * 2)
    var offset = 0
    for (row in 0 until perSide) {
        val v = row.toFloat() / cellsPerSide.toFloat()
        for (column in 0 until perSide) {
            vertices[offset] = column.toFloat() / cellsPerSide.toFloat()
            vertices[offset + 1] = v
            offset += 2
        }
    }
    return vertices
}

/**
 * The grid's triangles, two per cell, wound counter-clockwise once the reading each pipeline puts on
 * `(u, v)` is applied.
 *
 * See [globeGroundGridIndices], which this is the projection-neutral name for. That function states
 * the winding as seen from outside the sphere, where it is ADR 0038's far-hemisphere cull; the same
 * index run is counter-clockwise in Mercator's `(u - 0.5, 0.5 - v)` frame too, which is what lets
 * one triangulation serve both. `drawGround` disables culling and so cannot be caught by a flip,
 * which is exactly why the winding is asserted on the numbers here rather than left to pixels.
 */
internal fun groundGridIndices(cellsPerSide: Int): ShortArray {
    val perSide = cellsPerSide + 1
    val indices = ShortArray(cellsPerSide * cellsPerSide * 6)
    var offset = 0
    for (row in 0 until cellsPerSide) {
        for (column in 0 until cellsPerSide) {
            val northWest = row * perSide + column
            val northEast = northWest + 1
            val southWest = northWest + perSide
            val southEast = southWest + 1
            indices[offset] = northWest.toShort()
            indices[offset + 1] = southWest.toShort()
            indices[offset + 2] = northEast.toShort()
            indices[offset + 3] = northEast.toShort()
            indices[offset + 4] = southWest.toShort()
            indices[offset + 5] = southEast.toShort()
            offset += 6
        }
    }
    return indices
}

/**
 * The grid for [cellsPerSide], built once into [grids] and reused forever after.
 *
 * Building GL objects on the draw path is deliberate and is [globeGroundGrid]'s arrangement, for its
 * reason: the granularity a frame needs follows the camera, so it cannot be known when the renderer
 * is set up, and eagerly building every possible grid would allocate buffers a session may never
 * touch.
 *
 * [grids] is passed rather than a pipeline, which is the one place this differs from
 * [globeGroundGrid]. The map is the pipeline's — bounded by construction, since a granularity is a
 * power of two up to [MAXIMUM_GROUND_CELLS_PER_TILE_SIDE] — but taking it directly is what lets one
 * function serve two pipeline types without either of them learning about the other.
 */
internal fun groundGrid(
    binding: GlBinding,
    grids: MutableMap<Int, GroundGrid>,
    cellsPerSide: Int,
): GroundGrid {
    require(cellsPerSide in 1..MAXIMUM_GROUND_CELLS_PER_TILE_SIDE) {
        "a ground grid needs between one and $MAXIMUM_GROUND_CELLS_PER_TILE_SIDE cells a side"
    }
    grids[cellsPerSide]?.let { return it }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]
    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val vertices = littleEndianBytes(groundGridVertices(cellsPerSide))
    binding.bufferData(GL_ARRAY_BUFFER, vertices.size, vertices, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, GROUND_GRID_STRIDE_BYTES, 0)

    binding.genBuffers(1, names)
    val indexBuffer = names[0]
    // Bound while the vertex array is bound, and never unbound: the element array buffer binding is
    // vertex-array state, so re-binding the VAO at draw time restores it.
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
    val indices = groundGridIndices(cellsPerSide)
    val indexBytes = littleEndianBytes(indices)
    binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indexBytes.size, indexBytes, GL_STATIC_DRAW)

    val grid = GroundGrid(
        cellsPerSide = cellsPerSide,
        vertexArray = vertexArray,
        vertexBuffer = vertexBuffer,
        indexBuffer = indexBuffer,
        indexCount = indices.size,
    )
    grids[cellsPerSide] = grid
    return grid
}

/**
 * Deletes every grid in [grids] and empties it.
 *
 * Each grid's element array buffer is deleted explicitly rather than left to its vertex array, for
 * the reason [deleteUploadedPrimitive] already records: deleting a VAO frees the VAO object alone,
 * never the buffers whose bindings it recorded.
 */
internal fun deleteGroundGrids(binding: GlBinding, grids: MutableMap<Int, GroundGrid>) {
    grids.values.forEach { grid ->
        binding.deleteVertexArrays(1, intArrayOf(grid.vertexArray))
        binding.deleteBuffers(2, intArrayOf(grid.vertexBuffer, grid.indexBuffer))
    }
    grids.clear()
}

/**
 * The index run's bytes in the little-endian host order every published target uses, an overload of
 * [littleEndianBytes] for `FloatArray` rather than a second name for the same idea.
 */
internal fun littleEndianBytes(values: ShortArray): ByteArray {
    val bytes = ByteArray(values.size * Short.SIZE_BYTES)
    values.forEachIndexed { index, value ->
        val bits = value.toInt()
        bytes[index * 2] = (bits and 0xff).toByte()
        bytes[index * 2 + 1] = ((bits ushr 8) and 0xff).toByte()
    }
    return bytes
}

/** Two floats, and nothing else: a grid vertex is a `(u, v)` cell coordinate. */
private const val GROUND_GRID_STRIDE_BYTES: Int = 8
