package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.demElevationMetres

/**
 * The one text that reads a DEM texel and turns it into metres, composed into **both** ground vertex
 * shaders so the formula exists once.
 *
 * This is [GROUND_FRAGMENT_SOURCE]'s argument applied to the vertex half, and
 * [GroundGrid]'s applied to shader text rather than to a lattice: the globe ground and the Mercator
 * ground are required to displace by the identical height at the identical place, the cycle's
 * cross-mode agreement gate asserts exactly that, and one source makes a drift impossible rather
 * than merely visible. What each projection then *does* with the metres is genuinely different — a
 * normal offset on a plane, a radial one on a sphere — so that half stays in each vertex shader,
 * where the difference is legible.
 *
 * ## Three portability facts, each load-bearing
 *
 * **`textureLod` with an explicit level.** A vertex shader has no implicit derivatives, so plain
 * `texture()` is undefined there — not slow, not approximate, undefined. Level `0.0` is the only
 * level a DEM has: [uploadDemTexture] generates no mipmap chain, because a mipmap level of a Mapbox
 * DEM is a channel-wise average of packed integers and is the elevation of nothing.
 *
 * **`highp sampler2D`, declared.** GLSL ES 3.00 §4.5.4 gives the *vertex* language
 * `precision lowp sampler2D`, whose roughly eight bits of returned precision is exactly what an
 * 8-bit DEM channel needs and no more — so the default would work for the low byte and quietly
 * destroy the high one, which for Mapbox is 65,536 metres per unit.
 * `VertexTextureFetchProbe` measured this on Apple silicon, `Apple Software Renderer` through both
 * CGL and EAGL, and the qualifier is what its diagonal proves mattered.
 *
 * **The texture is padded, so a grid vertex at `u = 0` reads padded texel 1.** [padDemTexture] wraps
 * an `N`-square DEM in a one-texel ring copied from its neighbours, so the interior occupies
 * `1 .. N` of an `N + 2` square. Dropping that `+1` shifts every tile half a texel north-west
 * against the ground it displaces and reopens every seam the ring exists to close.
 *
 * ## The sampling rule, and why it closes the seam rather than merely narrowing it
 *
 * The grid coordinate is mapped into the **source** tile through [rengGroundDemWindow] with `mix`,
 * which returns its endpoints exactly (the same property [globeGroundTileEdges] depends on), then
 * snapped to the padded texel *containing* it and sampled at that texel's centre. Two tiles sharing
 * an edge therefore read the same source value from either side:
 *
 * - **Inside one source tile** (overzoom, `childScale > 1`) the two neighbours are handed the
 *   identical `Double`-exact window bound, so they snap to the identical texel of the identical
 *   texture.
 * - **Across two source tiles** the western tile's `u = 1` snaps to padded texel `N + 1`, which is
 *   its **east ring** — a copy of the eastern neighbour's column 0 — while the eastern tile's
 *   `u = 0` snaps to padded texel 1, which is that same column 0. The same ordered pair from both
 *   sides, which is the property [PaddedDemTexture]'s own KDoc states.
 *
 * Sampling the texel **centre** rather than its edge is what makes that robust: `GL_NEAREST` at a
 * texel boundary is a coin toss over the last bit, and the whole seam argument would then rest on a
 * rounding mode. Half a texel of margin costs nothing and removes the question.
 *
 * `floor` is deliberate rather than a rounding choice: design section 3 rules that smoothness comes
 * from mesh interpolation between vertices placed on texel centres, never from the sampler, because
 * a bilinear tap across a Mapbox channel carry decodes hundreds of metres wrong.
 */
internal const val GROUND_ELEVATION_SOURCE: String =
    "uniform highp sampler2D rengGroundDem;\n" +
        "uniform highp vec4 rengGroundDemWindow;\n" +
        "uniform highp vec4 rengGroundDemDecode;\n" +
        "uniform highp vec2 rengGroundDemGrid;\n" +
        "float rengGroundElevationMetres(vec2 grid) {\n" +
        "    float interior = rengGroundDemGrid.x;\n" +
        "    vec2 source = vec2(\n" +
        "        mix(rengGroundDemWindow.x, rengGroundDemWindow.y, grid.x),\n" +
        "        mix(rengGroundDemWindow.z, rengGroundDemWindow.w, grid.y));\n" +
        "    vec2 centre = (floor(source * interior) + 1.5) / (interior + 2.0);\n" +
        "    vec3 channels = textureLod(rengGroundDem, centre, 0.0).rgb;\n" +
        "    return (dot(channels, rengGroundDemDecode.xyz) + rengGroundDemDecode.w) *\n" +
        "        rengGroundDemGrid.y;\n" +
        "}\n"

internal const val GROUND_DEM_SAMPLER_UNIFORM_NAME: String = "rengGroundDem"
internal const val GROUND_DEM_WINDOW_UNIFORM_NAME: String = "rengGroundDemWindow"
internal const val GROUND_DEM_DECODE_UNIFORM_NAME: String = "rengGroundDemDecode"
internal const val GROUND_DEM_GRID_UNIFORM_NAME: String = "rengGroundDemGrid"

/**
 * The texture unit a DEM is sampled from, which is **not** unit 0.
 *
 * Unit 0 is the rendered basemap tile in both ground passes and in every other pass RenG has, so a
 * displaced ground is the first thing in the tree that samples two textures at once. Unit 1 is
 * inside [FRAME_TEXTURE_UNIT_COUNT] already — that constant captures and restores sixteen units
 * precisely so a pass that starts using a new one does not silently escape ADR 0023's Restore Set —
 * so this widening costs no change there and no new obligation.
 */
internal const val GROUND_DEM_TEXTURE_UNIT: Int = 1

/**
 * The `(kRed, kGreen, kBlue, offsetMetres)` the shader's `dot` needs, **derived from**
 * [demElevationMetres] rather than transcribed beside it.
 *
 * **A second transcription is the failure this function exists to make impossible.** Design section
 * 5 requires the GPU and the CPU to agree on what a DEM texel means, and the ordinary way to satisfy
 * that is to write the formula twice — once in Kotlin, once in GLSL — and pin the pair with a test.
 * A test can only catch a drift that has already been written. This instead *evaluates* the one
 * Kotlin definition at four points and reads the coefficients off it, so there is nothing to drift:
 * changing [demElevationMetres] changes what the shader computes, in the same commit, with no
 * second edit to forget.
 *
 * **It works because both encodings are affine in `(red, green, blue)`**, which is a property of the
 * packings rather than a coincidence — Mapbox packs a 24-bit big-endian integer and Terrarium packs
 * a fixed-point one, and a positional numeral is a weighted sum by construction:
 *
 * ```
 * MAPBOX    : (1671168.0,  6528.0, 25.5      , -10000.0)
 * TERRARIUM : (  65280.0,   255.0,  0.99609375, -32768.0)
 * ```
 *
 * Every one of those eight numbers is exact in `Float` — the weights are `255` or `255/256` times a
 * power of two, and the offsets are integers well inside the significand — so the narrowing below
 * loses nothing. `GroundElevationTest` asserts the affinity itself over the whole channel domain, so
 * an encoding added later that is *not* affine fails loudly here instead of being silently
 * linearised.
 *
 * The shader's input is `texture(...).rgb`, which is the 8-bit channel divided by 255, so each
 * coefficient is the metres a **full** channel contributes rather than the metres one count does.
 */
internal fun demDecodeCoefficients(encoding: DemEncoding): FloatArray {
    val offsetMetres = demElevationMetres(red = 0, green = 0, blue = 0, encoding = encoding)
    return floatArrayOf(
        (demElevationMetres(255, 0, 0, encoding) - offsetMetres).toFloat(),
        (demElevationMetres(0, 255, 0, encoding) - offsetMetres).toFloat(),
        (demElevationMetres(0, 0, 255, encoding) - offsetMetres).toFloat(),
        offsetMetres.toFloat(),
    )
}

/**
 * What the shared elevation fetch needs that is the same for every tile in a frame: how to decode a
 * texel, how big the DEM's interior is, and how far to exaggerate what it says.
 *
 * [exaggeration] is the style's own `terrain.exaggeration`, honoured, finite and **unclamped**
 * (design section 10): Rentile ignores the property entirely, so RenG parses and owns it, and
 * `CONTEXT.md`'s house rule is that an out-of-domain value fails rather than being clamped or
 * wrapped. All six corpus styles declare `1`, which is exactly why no test may assert at 1 — the
 * value is a symmetry point at which an honoured exaggeration and an ignored one are the same
 * picture.
 */
internal class GroundDemUniforms(
    decode: FloatArray,
    val interiorSizePx: Int,
    val exaggeration: Float,
) {
    val decode: FloatArray = decode.copyOf()

    init {
        require(decode.size == 4) { "a DEM decode carries three weights and an offset" }
        require(interiorSizePx > 0) { "a DEM tile has a positive interior" }
        require(exaggeration.isFinite()) { "an exaggeration is finite" }
    }
}

/**
 * One ground tile's own elevation: the padded DEM texture it samples, and the sub-rectangle of that
 * texture it occupies.
 *
 * **[window] is a window into the *source* tile, not into the requested one.** Under overzoom
 * several requested tiles share one DEM image and each reads its own quarter, sixteenth or
 * millionth of it; `demTileWindowFor` is what derives the rectangle, and it answers `null` for a
 * source that is not the request's ancestor, in which case the tile draws flat rather than
 * displacing to somebody else's mountains.
 *
 * The four components are `(uWest, uEast, vNorth, vSouth)`. `v` increases **southward**, matching
 * `DemTileWindow`'s own convention, the XYZ scheme's row order and [GroundGrid]'s `v`; all four
 * conventions agree, and a flip in any one of them mirrors every tile about its own centre line.
 */
internal class GroundTileDem(val demTexture: Int, window: FloatArray) {
    val window: FloatArray = window.copyOf()

    init {
        require(window.size == 4) { "a DEM window carries a west, an east, a north and a south bound" }
    }
}

/**
 * Mercator's per-frame elevation state: the shared decode plus the one scalar that turns metres into
 * the map-space logical pixels the ground's model matrix passes through untouched.
 *
 * **[equatorialLogicalPixelsPerMetre] is `worldSize / C` and carries no `1 / cos(latitude)`**,
 * because the vertex shader applies that per vertex. Folding it in per tile would be cheaper and
 * would reopen the seam this cycle spent a whole task closing: two tiles sharing a line of latitude
 * would scale one DEM height by two different constants, and the ground would step at every
 * horizontal tile boundary. See [TERRAIN_GROUND_VERTEX_SOURCE] for the identity that replaces it.
 */
internal class MercatorGroundElevationFrame(
    val dem: GroundDemUniforms,
    val equatorialLogicalPixelsPerMetre: Float,
) {
    init {
        require(equatorialLogicalPixelsPerMetre.isFinite()) { "a metre scale is finite" }
    }
}

/**
 * The globe's per-frame elevation state: the shared decode plus the fraction of the sphere's radius
 * one metre of elevation is worth.
 *
 * **[radialMultiplePerMetre] rather than a radius, and that is the whole of "the radial scale
 * becomes per vertex".** [composeGlobeGroundUnitSphereToClip] folds the radius into the frame's one
 * matrix so the vertex shader can emit a unit direction; displacement makes the emitted direction
 * `direction * (1 + metres * this)` instead, which is per vertex while the radius stays per frame
 * and stays in `Double` until that matrix's single narrowing. Pulling the radius out into a `Float`
 * uniform would have been the literal reading of "per vertex" and would have thrown away the
 * measurement `GlobeGroundPipelineTest.theFloatNarrowedGroundStaysSubPixelUntilTheMeasuredZoom`
 * exists to defend.
 *
 * Derived as `globeMetresToLogicalPixels(radius) / radius`, which is `1 / a` for the WGS84
 * semi-major axis and therefore independent of zoom — written as the quotient anyway, so that it
 * follows the one function that owns the globe's metre scale rather than restating its value. That
 * function's own KDoc carries the warning this must not violate: **a sphere has no Mercator
 * distortion**, so there is no `1 / cos(latitude)` here, and copying Mercator's would be exactly 2x
 * wrong at latitude 60 while agreeing perfectly at the equator, where a fixture naturally gets
 * written.
 */
internal class GlobeGroundElevationFrame(
    val dem: GroundDemUniforms,
    val radialMultiplePerMetre: Float,
) {
    init {
        require(radialMultiplePerMetre.isFinite()) { "a radial metre scale is finite" }
    }
}

/** Where [GROUND_ELEVATION_SOURCE]'s four uniforms landed in one linked program. */
internal class GroundElevationUniformLocations(
    val demSampler: Int,
    val window: Int,
    val decode: Int,
    val grid: Int,
)

internal fun resolveGroundElevationUniforms(
    binding: GlBinding,
    program: Int,
): GroundElevationUniformLocations = GroundElevationUniformLocations(
    demSampler = binding.getUniformLocation(program, GROUND_DEM_SAMPLER_UNIFORM_NAME),
    window = binding.getUniformLocation(program, GROUND_DEM_WINDOW_UNIFORM_NAME),
    decode = binding.getUniformLocation(program, GROUND_DEM_DECODE_UNIFORM_NAME),
    grid = binding.getUniformLocation(program, GROUND_DEM_GRID_UNIFORM_NAME),
)

/**
 * Uploads the three uniforms that are constant across a frame's ground, plus the sampler's unit.
 *
 * Called on every switch into a displacing program rather than once per frame, because a frame whose
 * coverage is incomplete alternates between the two programs inside the tile loop (ADR 0041: a tile
 * with no DEM draws flat) and a uniform set before a `useProgram` belongs to whichever program was
 * current then.
 */
internal fun bindGroundElevationFrame(
    binding: GlBinding,
    locations: GroundElevationUniformLocations,
    dem: GroundDemUniforms,
) {
    if (locations.demSampler >= 0) binding.uniform1i(locations.demSampler, GROUND_DEM_TEXTURE_UNIT)
    if (locations.decode >= 0) {
        binding.uniform4f(locations.decode, dem.decode[0], dem.decode[1], dem.decode[2], dem.decode[3])
    }
    if (locations.grid >= 0) {
        binding.uniform2f(locations.grid, dem.interiorSizePx.toFloat(), dem.exaggeration)
    }
}

/** Uploads one tile's window and binds its padded DEM to [GROUND_DEM_TEXTURE_UNIT]. */
internal fun bindGroundElevationTile(
    binding: GlBinding,
    locations: GroundElevationUniformLocations,
    tile: GroundTileDem,
) {
    if (locations.window >= 0) {
        binding.uniform4f(
            locations.window,
            tile.window[0],
            tile.window[1],
            tile.window[2],
            tile.window[3],
        )
    }
    binding.activeTexture(GL_TEXTURE0 + GROUND_DEM_TEXTURE_UNIT)
    binding.bindTexture(GL_TEXTURE_2D, tile.demTexture)
}
