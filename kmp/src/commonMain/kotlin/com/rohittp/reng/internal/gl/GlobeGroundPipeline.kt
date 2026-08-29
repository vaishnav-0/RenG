package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.globeFixedViewProjection
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * The ground on a sphere: one **shared, cached** subdivided grid, drawn once per basemap tile with
 * the tile's own longitude and isometric-latitude edges as uniforms.
 *
 * ## Why the grid is shared rather than built per tile
 *
 * A two-triangle quad cannot bend, so a globe ground tile needs vertices that a Mercator one does
 * not. The field's answer is unanimous and it is not "tessellate per tile": MapLibre caches meshes
 * by granularity in `createTileMesh` and Mapbox builds **one** grid buffer for the entire globe,
 * both handing the tile's identity to the shader as uniforms
 * (`docs/research/2026-08-28-g-globe-prior-art.md`, finding 1). This file does the same — a grid is
 * built the first time a granularity is asked for and lives in [GlobeGroundPipeline.grids] until the
 * pipeline is deleted, so a frame allocates nothing and uploads one `vec4` per tile.
 *
 * ## Why the vertex shader projects, where a `Geometry` is projected on the CPU
 *
 * ADR 0008's consumer contract makes RenG subdivide a `Geometry` and project its vertices on the CPU
 * so a consumer's shader stays linear. The ground's shader is RenG's own, so the same reasoning does
 * not apply and the opposite constraint does: CPU projection would mean per-tile, per-frame vertex
 * work over a buffer that changes every frame, which is exactly what the shared grid exists to
 * avoid. The grid therefore carries a **unit `(u, v)` cell coordinate** and nothing else, and the
 * sphere is evaluated per vertex from two uniforms.
 *
 * ## The one number this design owes, measured rather than assumed
 *
 * A globe-fixed position has the magnitude of the planet — `radiusLogicalPixels` reaches 3.9 x 10^9
 * at zoom 22 — while the quantity being kept is a screen-sized offset from the camera. The Mercator
 * ground never meets this because `resolveBasemapTileQuad` rebases each tile against the camera
 * **in `Double` on the CPU**, so nothing large is ever narrowed. Here the narrowing to `Float`
 * happens with the radius still in the matrix, and `Float`'s 24-bit significand puts the resulting
 * ground displacement at roughly `4 x 10^-6 * 2^zoom` logical pixels. Measured on a 960 x 540 frame:
 * **0.008 logical pixels at zoom 10**, 0.165 at zoom 14, **0.979 at zoom 17**, 2.557 at zoom 18 and
 * 45.8 at zoom 22.
 * `GlobeGroundPipelineTest.theFloatNarrowedGroundStaysSubPixelUntilTheMeasuredZoom` measures it
 * rather than restating it.
 *
 * That bound is deliberately not fixed here, and the reason is that the spec's own sagitta numbers
 * put the interesting zooms on the other side of it: a frame-sized quad bows 27.9 logical pixels
 * from its chord at zoom 4, 1.75 at zoom 8 and 0.44 at zoom 10, so above roughly zoom 12 a globe and
 * a tangent plane are the same picture. Fixing this would mean an analytically rebased, tile-local
 * formulation of the sphere — the field's per-tile origin rebasing — and both shipping web renderers
 * dodge it instead by transitioning to Mercator around zoom 12. Section 5 of
 * `docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` leaves that transition open pending a
 * measurement; this is one of the measurements it was waiting for.
 */
internal const val GLOBE_GROUND_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "layout(location = 0) in vec2 rengGlobeGroundGrid;\n" +
        "uniform mat4 rengGlobeGroundUnitSphereToClip;\n" +
        "uniform vec4 rengGlobeGroundTileEdges;\n" +
        "uniform vec2 rengGlobeGroundTileUvV;\n" +
        "out vec2 rengGroundUv;\n" +
        "void main() {\n" +
        "    float longitude = mix(rengGlobeGroundTileEdges.x, rengGlobeGroundTileEdges.y, " +
        "rengGlobeGroundGrid.x);\n" +
        "    float isometricLatitude = mix(rengGlobeGroundTileEdges.z, rengGlobeGroundTileEdges.w, " +
        "rengGlobeGroundGrid.y);\n" +
        "    float tangentHalfAngle = exp(isometricLatitude);\n" +
        "    float tangentSquared = tangentHalfAngle * tangentHalfAngle;\n" +
        "    float denominator = tangentSquared + 1.0;\n" +
        "    float sineLatitude = (tangentSquared - 1.0) / denominator;\n" +
        "    float cosineLatitude = 2.0 * tangentHalfAngle / denominator;\n" +
        "    vec3 direction = vec3(cosineLatitude * cos(longitude), cosineLatitude * sin(longitude), " +
        "sineLatitude);\n" +
        "    rengGroundUv = vec2(rengGlobeGroundGrid.x, mix(rengGlobeGroundTileUvV.x, " +
        "rengGlobeGroundTileUvV.y, rengGlobeGroundGrid.y));\n" +
        "    gl_Position = rengGlobeGroundUnitSphereToClip * vec4(direction, 1.0);\n" +
        "}\n"

/**
 * The globe ground's fragment stage is [GROUND_FRAGMENT_SOURCE] itself, not a copy of it.
 *
 * [GroundPipeline]'s own KDoc argues the ground must **not** share a program with stickers, because
 * a sticker's shading is consumer content free to grow while the ground's must keep sampling its
 * tile unchanged. Between the two ground modes that argument runs the other way: the globe and the
 * Mercator ground are required to sample the identical texture identically, and the cycle's
 * cross-mode agreement gate asserts exactly that. Sharing the source makes the two impossible to
 * drift apart; a second copy would make a drift a silent edit rather than a visible one.
 *
 * The vertex stage therefore writes the varying under the shared name `rengGroundUv` and the
 * pipeline resolves the sampler under [GROUND_TEXTURE_UNIFORM_NAME]. Only the vertex source differs,
 * which is enough for [ResourceKeyDeriver.internalPipeline] to derive a distinct key and
 * [GlProgramCache] to hold a distinct program.
 */
internal val GLOBE_GROUND_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = GLOBE_GROUND_VERTEX_SOURCE, fragmentSource = GROUND_FRAGMENT_SOURCE)

internal const val GLOBE_GROUND_UNIT_SPHERE_TO_CLIP_UNIFORM_NAME: String =
    "rengGlobeGroundUnitSphereToClip"
internal const val GLOBE_GROUND_TILE_EDGES_UNIFORM_NAME: String = "rengGlobeGroundTileEdges"
internal const val GLOBE_GROUND_TILE_UV_V_UNIFORM_NAME: String = "rengGlobeGroundTileUvV"

/**
 * One subdivided grid: `cellsPerSide^2` quads over the unit `(u, v)` square, indexed as triangles.
 *
 * `u` runs west to east and `v` runs **north to south**, matching [GROUND_QUAD]'s convention exactly
 * so that the shared fragment stage samples row zero of a rendered basemap tile at the tile's north
 * edge in both modes. The grid coordinate is the texture coordinate: a Mercator raster tile's
 * texture is parameterised by Mercator `x`/`y` and so is this grid, which is what keeps the UVs
 * linear while the position is not.
 */
internal class GlobeGroundGrid(
    val cellsPerSide: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val indexBuffer: Int,
    val indexCount: Int,
)

internal class GlobeGroundPipeline(
    val key: ResourceKey,
    val program: Int,
    val unitSphereToClipUniformLocation: Int,
    val tileEdgesUniformLocation: Int,
    val tileUvVUniformLocation: Int,
    val textureUniformLocation: Int,
    /**
     * Every grid this pipeline has been asked for, keyed by [GlobeGroundGrid.cellsPerSide].
     *
     * Mutable and owned here because the granularity a frame needs is a function of the camera and
     * is therefore unknown at creation time, and because "shared and cached" is the whole point:
     * [globeGroundGrid] builds one on first use and every later frame at that granularity reuses it.
     * The map is bounded by construction — [globeGroundCellsPerTileSide] returns powers of two up to
     * [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE], so at most eight entries can ever exist.
     */
    val grids: MutableMap<Int, GlobeGroundGrid> = mutableMapOf(),
)

internal sealed interface GlobeGroundPipelineResult {
    data class Created(val pipeline: GlobeGroundPipeline) : GlobeGroundPipelineResult

    data class Failed(val failure: FailureDescriptor) : GlobeGroundPipelineResult
}

internal fun createGlobeGroundPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): GlobeGroundPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.GLOBE_GROUND, GLOBE_GROUND_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(GLOBE_GROUND_VERTEX_SOURCE)
        ?: return GlobeGroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(GROUND_FRAGMENT_SOURCE)
        ?: return GlobeGroundPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return GlobeGroundPipelineResult.Failed(result.failure)
    }

    return GlobeGroundPipelineResult.Created(
        GlobeGroundPipeline(
            key = key,
            program = program,
            unitSphereToClipUniformLocation = binding.getUniformLocation(
                program,
                GLOBE_GROUND_UNIT_SPHERE_TO_CLIP_UNIFORM_NAME,
            ),
            tileEdgesUniformLocation = binding.getUniformLocation(
                program,
                GLOBE_GROUND_TILE_EDGES_UNIFORM_NAME,
            ),
            tileUvVUniformLocation = binding.getUniformLocation(
                program,
                GLOBE_GROUND_TILE_UV_V_UNIFORM_NAME,
            ),
            textureUniformLocation = binding.getUniformLocation(program, GROUND_TEXTURE_UNIFORM_NAME),
        ),
    )
}

/**
 * Deletes the program and **every** cached grid.
 *
 * Each grid's element array buffer is deleted explicitly rather than left to its vertex array, for
 * the reason [deleteUploadedPrimitive] already records: deleting a VAO frees the VAO object alone,
 * never the buffers whose bindings it recorded.
 */
internal fun deleteGlobeGroundPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GlobeGroundPipeline,
) {
    pipeline.grids.values.forEach { grid ->
        binding.deleteVertexArrays(1, intArrayOf(grid.vertexArray))
        binding.deleteBuffers(2, intArrayOf(grid.vertexBuffer, grid.indexBuffer))
    }
    pipeline.grids.clear()
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * The grid for [cellsPerSide], built once and reused forever after.
 *
 * Building GL objects on the draw path is unusual in this codebase and is deliberate here: the
 * granularity a frame needs follows the camera, so it cannot be known when the renderer is set up,
 * and eagerly building all eight possible grids would allocate about 350 KB of buffers a session may
 * never touch. This is `createTileMesh`'s own arrangement.
 */
internal fun globeGroundGrid(
    binding: GlBinding,
    pipeline: GlobeGroundPipeline,
    cellsPerSide: Int,
): GlobeGroundGrid {
    require(cellsPerSide in 1..MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE) {
        "a globe ground grid needs between one and $MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE cells a side"
    }
    pipeline.grids[cellsPerSide]?.let { return it }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]
    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val vertices = littleEndianBytes(globeGroundGridVertices(cellsPerSide))
    binding.bufferData(GL_ARRAY_BUFFER, vertices.size, vertices, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, GLOBE_GROUND_STRIDE_BYTES, 0)

    binding.genBuffers(1, names)
    val indexBuffer = names[0]
    // Bound while the vertex array is bound, and never unbound: the element array buffer binding is
    // vertex-array state, so re-binding the VAO at draw time restores it.
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
    val indices = globeGroundGridIndices(cellsPerSide)
    val indexBytes = littleEndianShortBytes(indices)
    binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indexBytes.size, indexBytes, GL_STATIC_DRAW)

    val grid = GlobeGroundGrid(
        cellsPerSide = cellsPerSide,
        vertexArray = vertexArray,
        vertexBuffer = vertexBuffer,
        indexBuffer = indexBuffer,
        indexCount = indices.size,
    )
    pipeline.grids[cellsPerSide] = grid
    return grid
}

/**
 * The grid's `(u, v)` pairs, row-major from the north-west corner: `(cellsPerSide + 1)^2` vertices.
 *
 * Each coordinate is `index / cellsPerSide` evaluated in `Float`, so the four boundary values are
 * **exactly** `0` and `1`. That exactness is load-bearing rather than tidy: the vertex shader
 * reconstructs a vertex's longitude as `mix(west, east, u)`, and `mix` returns its endpoint exactly
 * at `u = 0` and `u = 1`. Two tiles sharing an edge are handed the identical `Float` for it by
 * [globeGroundTileEdges], so their shared vertices land on the identical position and the seam is
 * closed by construction rather than by a border ring, a skirt or half a pixel of padding — the
 * three mechanisms the field uses instead (`docs/research/2026-08-28-g-globe-prior-art.md`, Q7).
 */
internal fun globeGroundGridVertices(cellsPerSide: Int): FloatArray {
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
 * The grid's triangles, two per cell, wound **counter-clockwise as seen from outside the sphere**.
 *
 * That winding is the whole of ADR 0038's globe mechanism: the far hemisphere is exactly the
 * back-facing set, and `drawFrame` has already established `GL_CCW` and `GL_BACK` scene-wide. Seen
 * from outside with north up, east is to the right and `v` grows downward, so the naive
 * `(i, j), (i + 1, j), (i, j + 1)` order is clockwise and would cull the near hemisphere while
 * drawing the far one — a mirror-image globe that looks plausible in a single-tile fixture.
 */
internal fun globeGroundGridIndices(cellsPerSide: Int): ShortArray {
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
 * One basemap tile's edges in the shader's own coordinates: `(west, east, north, south)` as
 * `(longitude, longitude, isometric latitude, isometric latitude)` in radians.
 *
 * This is the CPU half of the expression [com.rohittp.reng.internal.projection.unitSphereDirection]
 * evaluates: `psi = PI * (1 - 2y)` and `lambda = PI * (2x - 1)`, in `Double`, from the tile's own
 * exact rational coordinates. Two neighbours are handed the identical `Float` for the edge they
 * share, because `(unwrappedX + 1) / 2^lod` and the neighbour's `unwrappedX / 2^lod` are the same
 * `Double`.
 *
 * **Two endpoints per axis rather than an origin and a span, and the reason is the shader rather
 * than this function.** Spelling the same thing `origin + span * u` on the CPU would be harmless:
 * over 1,144 sampled tiles across every LOD the two `Double` results differ 196 times and the
 * narrowed `Float` results differ **never**. Spelling it that way in the *shader* is not harmless,
 * because there the multiply-add happens in `highp float` against a longitude near `PI`: at `u = 1`
 * it lands up to `2.384 x 10^-7` radians away from the neighbour's own west edge, which is 0.02
 * logical pixels of crack at zoom 10 and **5.09 at zoom 18**. `mix(west, east, u)` returns its
 * endpoints exactly, so the shared vertices coincide instead — no border ring, no skirt and no half
 * pixel of padding, which are the three mechanisms the field uses instead.
 *
 * **[unwrappedX] is wrapped here, exactly as [unitSphereDirection] wraps its Mercator `x`**: a
 * sphere has no world copies, so a tile in copy 3 draws where copy 0's does. The east edge is then
 * carried a full turn past the west one when the wrap would otherwise fold it back, so a tile
 * spanning the antimeridian stays a tile rather than becoming the rest of the planet.
 */
internal fun globeGroundTileEdges(lod: Int, tileY: Long, unwrappedX: Long): FloatArray {
    require(lod >= 0) { "a basemap tile's lod is never negative" }
    val tileCount = (1L shl lod).toDouble()
    val westMercatorX = unwrappedX.toDouble() / tileCount
    val eastMercatorX = (unwrappedX.toDouble() + 1.0) / tileCount
    val westLongitude = mercatorXLongitudeRadians(westMercatorX)
    val unwrappedEastLongitude = mercatorXLongitudeRadians(eastMercatorX)
    val eastLongitude =
        if (unwrappedEastLongitude > westLongitude) unwrappedEastLongitude
        else unwrappedEastLongitude + 2.0 * PI
    return floatArrayOf(
        westLongitude.toFloat(),
        eastLongitude.toFloat(),
        (PI * (1.0 - 2.0 * (tileY.toDouble() / tileCount))).toFloat(),
        (PI * (1.0 - 2.0 * ((tileY.toDouble() + 1.0) / tileCount))).toFloat(),
    )
}

/**
 * The isometric latitude standing in for a pole, and why a finite number is the exact answer.
 *
 * A pole is `psi = infinity`, which no uniform can carry. But the shader's own identity closes the
 * gap: it forms `t = exp(psi)` and then `sin(latitude) = (t^2 - 1) / (t^2 + 1)`. At `psi = 20`,
 * `t^2` is `2.35 x 10^17`, so in `highp float` the `-1` and `+1` are both lost to rounding and the
 * quotient is **exactly** `1.0` -- the pole, bit for bit, with no branch and no second formulation.
 * The residual is `cos(latitude) = 2t / t^2 = 4.1 x 10^-9` radians, which is 2.6 centimetres on
 * Earth. `exp(20)` is `4.9 x 10^8` and its square is nowhere near `float`'s ceiling, so nothing
 * overflows; the southern cap evaluates `exp(-20)` instead and loses nothing at all.
 */
internal const val POLAR_CAP_ISOMETRIC_LATITUDE: Float = 20.0f

/**
 * The wedge that closes a pole, for a top- or bottom-row tile whose [tileEdges] these reuse.
 *
 * **Web Mercator stops at 85.0511 degrees**, so the cap above it has no tile and, on a globe, no
 * pixels either -- a hole a flat map can never show because the region is not in frame at all.
 * Measured before this existed: 783 pixels of the harness's clear colour enclosed by the sphere at
 * zoom 2, a notch widening from 14 to 56 pixels across 24 rows.
 *
 * **The longitudes are the tile's own `Float`s, passed through untouched.** That is the whole reason
 * this takes [tileEdges] rather than recomputing from `lod` and `unwrappedX`: a cap sharing an edge
 * with its tile must share the *same* number, not an equal one, or the crack that
 * [globeGroundTileEdges] documents at 5.09 logical pixels reappears along every cap seam. Adjacent
 * caps meet for the same reason, since neighbouring tiles already agree on the longitude between
 * them.
 *
 * North stays at the grid's `v = 0` end in both directions, so a cap winds exactly as a tile does
 * and ADR 0038's far-hemisphere cull removes the far one with no special case.
 */
internal fun globeGroundPolarCapEdges(tileEdges: FloatArray, north: Boolean): FloatArray {
    require(tileEdges.size == 4) { "a globe ground tile carries four edges" }
    return if (north) {
        floatArrayOf(tileEdges[0], tileEdges[1], POLAR_CAP_ISOMETRIC_LATITUDE, tileEdges[2])
    } else {
        floatArrayOf(tileEdges[0], tileEdges[1], tileEdges[3], -POLAR_CAP_ISOMETRIC_LATITUDE)
    }
}

private fun mercatorXLongitudeRadians(mercatorX: Double): Double =
    PI * (2.0 * (mercatorX - floor(mercatorX)) - 1.0)

/**
 * One globe ground tile instance ready to draw: its four edges from [globeGroundTileEdges] and its
 * already-uploaded GL texture name.
 *
 * There is no per-tile matrix, which is the point. A Mercator ground tile carries a whole
 * model-view-projection because its geometry is a unit square that has to be moved and scaled into
 * place; a globe ground tile's geometry is fixed by where it is on the sphere, so its identity fits
 * in one `vec4` and every tile in the frame shares one uploaded matrix.
 */
internal class ResolvedGlobeGroundTile(
    val edges: FloatArray,
    val texture: Int,
    /**
     * Which rows of [texture] this quad samples, as `(v at the north edge, v at the south edge)`.
     *
     * `(0, 1)` for an ordinary tile, which is the whole texture and the only value that existed
     * before polar caps. A cap wedge pins both to the same row -- `(0, 0)` at the north pole and
     * `(1, 1)` at the south -- so the tile's own edge texels stretch over the cap. That is why the
     * ground sampler's `GL_CLAMP_TO_EDGE` is load-bearing here rather than incidental: `v = 0` under
     * linear filtering samples half a texel outside the texture, and `GL_REPEAT` would fetch the
     * opposite pole's row.
     */
    val uvV: FloatArray = ORDINARY_TILE_UV_V,
) {
    init {
        require(edges.size == 4) { "a globe ground tile carries four edges" }
        require(uvV.size == 2) { "a globe ground tile carries two texture v coordinates" }
    }
}

internal val ORDINARY_TILE_UV_V: FloatArray = floatArrayOf(0.0f, 1.0f)
internal val NORTH_POLAR_CAP_UV_V: FloatArray = floatArrayOf(0.0f, 0.0f)
internal val SOUTH_POLAR_CAP_UV_V: FloatArray = floatArrayOf(1.0f, 1.0f)

/**
 * The one matrix the globe ground uploads: a **unit sphere direction** to clip space.
 *
 * [globeFixedViewProjection] already writes the composition order once and takes a globe-fixed
 * position; folding the radius in as a scale here is what lets the vertex shader emit a unit
 * direction and nothing larger, and keeps the radius in `Double` until the single narrowing every
 * other compose function in this package performs.
 *
 * Altitude zero is baked in, because a ground tile is the sphere's surface. Terrain displacing the
 * ground would need the radial scale per vertex rather than per frame, which is E-terrain's problem
 * in both projections.
 */
internal fun composeGlobeGroundUnitSphereToClip(camera: ResolvedGlobeCamera): FloatArray {
    val radius = camera.radiusLogicalPixels
    val radialScale = DoubleMatrix4.fromRows(
        listOf(
            listOf(radius, 0.0, 0.0, 0.0),
            listOf(0.0, radius, 0.0, 0.0),
            listOf(0.0, 0.0, radius, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )
    return (globeFixedViewProjection(camera) * radialScale).toColumnMajorFloatArray()
}

/**
 * How far a chord spanning one grid cell departs from the sphere it is standing in for, in logical
 * pixels, for a cell of [cellAngleRadians] on a globe of [radiusLogicalPixels].
 *
 * Two errors, and the second is the one a reader will not expect.
 *
 * - The **chord sagitta**, `R * (1 - cos(theta / 2))`, written exactly rather than as `R theta^2 /
 *   8` because at zoom 0 a single-cell tile spans the whole planet and the small-angle form is
 *   nonsense there.
 * - The **Gudermannian warp**. A cell's corners sit at the right latitudes, but a point halfway down
 *   it in Mercator `y` is drawn halfway along the chord, and `phi(psi)` is not linear: the error is
 *   `R * cos phi * sin phi * dpsi^2 / 8`, at most `R * dpsi^2 / 16` over all latitudes. This one has
 *   no counterpart in a flat renderer at all, and near the poles it is the **larger** of the two —
 *   its ratio to the north-south sagitta is `tan phi`, which is 11 at the Mercator clip.
 *
 * Their sum is what a granularity has to beat. Both are world-space displacements at unit
 * perspective scale, which is the right frame for the criterion: a tile near the camera projects
 * about one-to-one, and a tile out at the horizon has the same world error scaled *down* by
 * perspective.
 */
internal fun globeGroundCellDeviationLogicalPixels(
    radiusLogicalPixels: Double,
    cellAngleRadians: Double,
): Double = radiusLogicalPixels *
    ((1.0 - cos(cellAngleRadians / 2.0)) + cellAngleRadians * cellAngleRadians / 16.0)

/**
 * How many cells a side the ground grid needs so that no cell departs from the sphere by more than
 * [toleranceLogicalPixels], for tiles spanning [tileSpanRadians] on a globe of
 * [radiusLogicalPixels].
 *
 * **The camera bounds the work, not the world** — the spec's rule for the ground and for a
 * `Geometry` alike. Both arguments are camera-derived: the radius is
 * [ResolvedGlobeCamera.radiusLogicalPixels], which carries the zoom, and the span is the frame's
 * one selected LOD. The result falls from 64 at zoom 0 to 1 above zoom 11, halving every second
 * zoom level, and rises to the [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE] cap when a source's
 * maximum zoom forces the frame to draw tiles far coarser than the camera.
 *
 * **One granularity for the whole frame, never one per tile.** MapLibre's most expensive globe bug
 * was two meshes sharing an edge at different granularities, and the rule that came out of it is
 * still a comment in its source: any two meshes that share an edge on the sphere must be subdivided
 * identically or a sliver of background shows through. RenG selects one LOD per frame, so one
 * granularity per frame is the whole of that obligation.
 *
 * **Why half a logical pixel.** A deviation under half a pixel cannot move a sample point into a
 * different pixel, and rounding up to a power of two means the granularity actually chosen beats it
 * by 2x to 4x. It is not a tuned constant: the readback suite renders the fixture at every
 * granularity from 1 to 128 and reports how many pixels each disagrees with the 128 reference over,
 * which is what turns "half a pixel" from a preference into a measurement.
 */
internal fun globeGroundCellsPerTileSide(
    radiusLogicalPixels: Double,
    tileSpanRadians: Double,
    toleranceLogicalPixels: Double = GLOBE_GROUND_DEVIATION_TOLERANCE_LOGICAL_PIXELS,
): Int {
    require(radiusLogicalPixels > 0.0) { "a globe has a positive radius" }
    require(tileSpanRadians > 0.0) { "a basemap tile spans a positive angle" }
    require(toleranceLogicalPixels > 0.0) { "a deviation tolerance is positive" }
    var cells = 1
    while (cells < MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE &&
        globeGroundCellDeviationLogicalPixels(
            radiusLogicalPixels,
            tileSpanRadians / cells.toDouble(),
        ) > toleranceLogicalPixels
    ) {
        cells *= 2
    }
    return cells
}

/** [globeGroundCellsPerTileSide] for the frame [camera] is looking at, whose tiles are at [lod]. */
internal fun globeGroundCellsPerTileSide(camera: ResolvedGlobeCamera, lod: Int): Int {
    require(lod >= 0) { "a basemap tile's lod is never negative" }
    return globeGroundCellsPerTileSide(
        radiusLogicalPixels = camera.radiusLogicalPixels,
        tileSpanRadians = 2.0 * PI / (1L shl lod).toDouble(),
    )
}

/**
 * Draws [tiles] as the frame's ground on a sphere, in the order given, from the one grid at
 * [cellsPerTileSide].
 *
 * **The pass state is [drawGround]'s, arm for arm.** Blend, depth test and depth mask are
 * established here for the reasons ADR 0027 and [drawGround] already record, and the cull enable is
 * ADR 0038's globe arm: the far hemisphere is exactly the back-facing set once the grid winds
 * consistently, so `GL_CULL_FACE` removes it with no depth involvement. `drawFrame` has established
 * `frontFace(GL_CCW)` and `cullFace(GL_BACK)` scene-wide and the ground is the first pass inside it,
 * so neither the mode nor the winding is restated.
 *
 * **What is not here is a `ProjectionMode`.** [drawGround]'s exhaustive `when` exists because one
 * function serves both modes with one geometry; this function is only ever the globe's, and a
 * parameter whose only legal value is `GLOBE` would be a mode that could be got wrong rather than a
 * decision. The seam between the two entry points — which one a `GLOBE` frame reaches — is task 10's
 * to close, and until it does, nothing in production calls this.
 *
 * The matrix is uploaded **once**, before the loop: every tile shares it, and the per-tile upload is
 * one `vec4`. That is the whole economy the shared grid buys.
 */
internal fun drawGlobeGround(
    binding: GlBinding,
    pipeline: GlobeGroundPipeline,
    tiles: List<ResolvedGlobeGroundTile>,
    unitSphereToClip: FloatArray,
    cellsPerTileSide: Int,
) {
    require(unitSphereToClip.size == 16) { "a model-view-projection matrix carries sixteen values" }
    if (tiles.isEmpty()) return

    val grid = globeGroundGrid(binding, pipeline, cellsPerTileSide)
    binding.useProgram(pipeline.program)
    binding.bindVertexArray(grid.vertexArray)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.activeTexture(GL_TEXTURE0)
    if (pipeline.textureUniformLocation >= 0) {
        binding.uniform1i(pipeline.textureUniformLocation, 0)
    }
    binding.enable(GL_DEPTH_TEST)
    binding.depthMask(false)
    binding.enable(GL_CULL_FACE)
    if (pipeline.unitSphereToClipUniformLocation >= 0) {
        binding.uniformMatrix4fv(pipeline.unitSphereToClipUniformLocation, 1, false, unitSphereToClip)
    }

    tiles.forEach { tile ->
        if (pipeline.tileEdgesUniformLocation >= 0) {
            binding.uniform4f(
                pipeline.tileEdgesUniformLocation,
                tile.edges[0],
                tile.edges[1],
                tile.edges[2],
                tile.edges[3],
            )
        }
        if (pipeline.tileUvVUniformLocation >= 0) {
            binding.uniform2f(pipeline.tileUvVUniformLocation, tile.uvV[0], tile.uvV[1])
        }
        binding.bindTexture(GL_TEXTURE_2D, tile.texture)
        binding.drawElements(GL_TRIANGLES, grid.indexCount, GL_UNSIGNED_SHORT, 0)
    }
}

/**
 * MapLibre's own ceiling, and the widest grid a 16-bit index can address: 128 cells a side is
 * `129 * 129 = 16,641` vertices, where 256 would be 66,049 and overflow.
 */
internal const val MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE: Int = 128

internal const val GLOBE_GROUND_DEVIATION_TOLERANCE_LOGICAL_PIXELS: Double = 0.5

/** The index run's bytes in the little-endian host order every published target uses. */
private fun littleEndianShortBytes(values: ShortArray): ByteArray {
    val bytes = ByteArray(values.size * Short.SIZE_BYTES)
    values.forEachIndexed { index, value ->
        val bits = value.toInt()
        bytes[index * 2] = (bits and 0xff).toByte()
        bytes[index * 2 + 1] = ((bits ushr 8) and 0xff).toByte()
    }
    return bytes
}

private const val GLOBE_GROUND_STRIDE_BYTES: Int = 8
