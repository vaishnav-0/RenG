package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.demElevationMetres
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one text that decodes a DEM texel into metres, and the one function that supplies it its
 * coefficients.
 *
 * **The agreement between the GPU's formula and the CPU's is not tested here — it is made
 * unwriteable.** Design section 5 requires `demElevationMetres` and the shader to mean the same
 * thing by a texel, and the usual way to satisfy that is two transcriptions and a test comparing
 * them. A test can only catch a drift somebody has already written. [demDecodeCoefficients] instead
 * *evaluates* the single Kotlin definition at four points and hands the result to the shader, so
 * there is no second transcription to drift; what these cases pin is the property that derivation
 * depends on — that both encodings really are affine in `(red, green, blue)` — and the composition
 * that puts one GLSL text into two vertex stages.
 */
class GroundElevationTest {

    /**
     * The derivation's premise, checked over the **whole** channel domain rather than at a sample.
     *
     * `demElevationMetres` is affine in its three channels, so
     * `f(r, g, b) = f(0,0,0) + r*cr + g*cg + b*cb` for coefficients read off three axis probes. If
     * that ever stops being true — a future encoding with a gamma, a clamp, or a sentinel — the
     * shader would go on evaluating a plane through four of its points and be silently wrong
     * everywhere else. This is the assertion that turns that into a failure.
     *
     * 768 triples per encoding on the axes plus a deterministic sweep of mixed ones, compared in
     * `Double` at the shader's own normalised inputs (`channel / 255`), because the coefficients are
     * "metres per full channel" rather than "metres per count".
     */
    @Test fun theDecodedCoefficientsReproduceDemElevationMetresOverTheWholeChannelDomain() {
        DemEncoding.entries.forEach { encoding ->
            val coefficients = demDecodeCoefficients(encoding)
            var worst = 0.0
            var worstTriple = ""
            fun check(red: Int, green: Int, blue: Int) {
                val expected = demElevationMetres(red, green, blue, encoding)
                val actual = coefficients[0] * (red / 255.0) +
                    coefficients[1] * (green / 255.0) +
                    coefficients[2] * (blue / 255.0) +
                    coefficients[3]
                val error = abs(actual - expected)
                if (error > worst) {
                    worst = error
                    worstTriple = "($red, $green, $blue): expected $expected, derived $actual"
                }
            }
            for (value in 0..255) {
                check(value, 0, 0)
                check(0, value, 0)
                check(0, 0, value)
            }
            // Mixed triples, stepped by primes so the three channels never repeat a pattern
            // together: a coefficient swap survives every all-equal triple.
            for (index in 0..255) {
                check(index, (index * 7 + 3) % 256, (index * 13 + 11) % 256)
            }
            assertTrue(
                worst < 1e-6,
                "$encoding is not affine in its channels, so the shader's dot product cannot stand " +
                    "in for demElevationMetres: worst disagreement $worst m at $worstTriple",
            )
        }
    }

    /**
     * The two encodings' coefficients written out, so that a change to either formula is a visible
     * diff here rather than a silent change of what every terrain frame draws.
     *
     * Every one of these eight numbers is exact in `Float` — the weights are 255 or 255/256 times a
     * power of two and the offsets are small integers — which is what lets the narrowing in
     * [demDecodeCoefficients] lose nothing. The equality below is exact for that reason, and would
     * not be if any of them needed rounding.
     */
    @Test fun theTwoEncodingsDecodeToTheirDocumentedCoefficients() {
        assertEquals(
            listOf(1671168.0f, 6528.0f, 25.5f, -10000.0f),
            demDecodeCoefficients(DemEncoding.MAPBOX).toList(),
            "Mapbox packs a 24-bit big-endian integer at 0.1 m per count, offset -10000 m",
        )
        assertEquals(
            listOf(65280.0f, 255.0f, 0.99609375f, -32768.0f),
            demDecodeCoefficients(DemEncoding.TERRARIUM).toList(),
            "Terrarium packs metres as red*256 + green + blue/256, offset -32768 m",
        )
    }

    /**
     * One GLSL text, in both vertex stages — the vertex-side half of the argument
     * [GROUND_FRAGMENT_SOURCE] already makes for the fragment side.
     *
     * The two grounds are required to displace by the identical height at the identical place, and
     * a second copy of this text would make a drift between them a silent edit rather than a
     * conflict. The identity check is `contains` on the constant itself rather than on any excerpt,
     * so a paraphrase fails.
     */
    @Test fun theSharedElevationFragmentIsComposedIntoBothGroundVertexStages() {
        assertTrue(
            TERRAIN_GROUND_VERTEX_SOURCE.contains(GROUND_ELEVATION_SOURCE),
            "the mercator ground must compose the shared elevation source, not restate it",
        )
        assertTrue(
            TERRAIN_GLOBE_GROUND_VERTEX_SOURCE.contains(GROUND_ELEVATION_SOURCE),
            "the globe ground must compose the shared elevation source, not restate it",
        )
        assertEquals(
            GROUND_FRAGMENT_SOURCE,
            TERRAIN_GROUND_SHADER_PAIR.fragmentSource,
            "displacement is a vertex-stage change; the fragment stage stays the shared one",
        )
        assertEquals(
            GROUND_FRAGMENT_SOURCE,
            TERRAIN_GLOBE_GROUND_SHADER_PAIR.fragmentSource,
            "displacement is a vertex-stage change; the fragment stage stays the shared one",
        )
    }

    /**
     * The three portability facts the E-terrain preflight measured, asserted on the text because
     * GLSL is not executable from a unit test.
     *
     * - `textureLod` with an explicit level, because a vertex shader has no implicit derivatives and
     *   plain `texture()` is **undefined** there rather than merely slower.
     * - `highp sampler2D`, declared, because GLSL ES 3.00 section 4.5.4 gives the vertex language
     *   `precision lowp sampler2D` — about eight bits, exactly what one DEM channel needs and
     *   nothing for the other two.
     * - the padded `+ 1`, because [com.rohittp.reng.internal.terrain.padDemTexture] wraps an
     *   `N`-square DEM in a one-texel ring and the interior therefore starts at texel 1.
     *
     * The *correctness* of all three is `runGroundDisplacementReadback`'s, on a real driver; these
     * are the cheap unit-level tripwires for an edit that deletes one of them.
     */
    @Test fun theElevationFetchIsAnExplicitLevelOnAnExplicitlyHighpSampler() {
        assertTrue(
            GROUND_ELEVATION_SOURCE.contains("uniform highp sampler2D rengGroundDem;"),
            "the DEM sampler must be declared highp: $GROUND_ELEVATION_SOURCE",
        )
        assertTrue(
            GROUND_ELEVATION_SOURCE.contains("textureLod(rengGroundDem, centre, 0.0)"),
            "a vertex shader has no derivatives, so the fetch must name its level: $GROUND_ELEVATION_SOURCE",
        )
        assertTrue(
            GROUND_ELEVATION_SOURCE.contains("(floor(source * interior) + 1.5) / (interior + 2.0)"),
            "the interior of a padded DEM starts at texel 1 and the texture is (N + 2) square: " +
                GROUND_ELEVATION_SOURCE,
        )
        assertTrue(
            GROUND_ELEVATION_SOURCE.contains("mix(rengGroundDemWindow.x, rengGroundDemWindow.y, grid.x)"),
            "the window must be interpolated with mix, which returns its endpoints exactly: " +
                GROUND_ELEVATION_SOURCE,
        )
    }

    /** Both displacing stages are ordinary Shader Profile sources and go through the same scan. */
    @Test fun bothDisplacingVertexSourcesAreAcceptedShaderProfileSources() {
        assertTrue(TERRAIN_GROUND_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(TERRAIN_GLOBE_GROUND_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(TERRAIN_GROUND_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(TERRAIN_GLOBE_GROUND_VERTEX_SOURCE) != null)
    }

    /**
     * Mercator scales elevation by `1 / cos(latitude)` per vertex, and the globe by nothing of the
     * sort. Asserted as an absence as well as a presence, because copying Mercator's conversion onto
     * the sphere is the specific mistake `globeMetresToLogicalPixels`' KDoc records as **2x wrong at
     * latitude 60 and exactly right at the equator** — the one place a fixture naturally gets
     * written.
     */
    @Test fun onlyTheMercatorStageCarriesTheLatitudeDistortion() {
        assertTrue(
            TERRAIN_GROUND_VERTEX_SOURCE.contains("cosh(3.141592653589793 * (1.0 - 2.0 * mercatorY))"),
            "mercator's altitude scale is 1 / cos(latitude) = cosh(PI * (1 - 2y)): " +
                TERRAIN_GROUND_VERTEX_SOURCE,
        )
        assertTrue(
            !TERRAIN_GLOBE_GROUND_VERTEX_SOURCE.contains("cosh"),
            "a sphere has no Mercator area distortion; a cosh here would be 2x wrong at latitude 60",
        )
        assertTrue(
            TERRAIN_GLOBE_GROUND_VERTEX_SOURCE.contains("direction * radial"),
            "the globe's emitted direction is no longer a unit one: $TERRAIN_GLOBE_GROUND_VERTEX_SOURCE",
        )
    }

    /**
     * Two vertically adjacent tiles must be handed the **identical** `Float` for the parallel they
     * share, or the latitude term evaluates two different scales either side of it and the drawn
     * ground steps at every horizontal tile boundary — in a frame whose *sampled* heights already
     * agree, because the padded ring made them.
     *
     * This is `globeGroundTileEdges`' own exactness argument in the other projection: both sides
     * compute the same rational in `Double` before narrowing, so the two `Float`s are equal bit for
     * bit rather than close.
     */
    @Test fun twoVerticallyAdjacentTilesShareTheExactSameMercatorEdge() {
        for (lod in 0..14) {
            val rows = 1 shl lod
            val row = rows / 2
            if (row + 1 >= rows) continue
            val north = mercatorTileYEdges(lod, row)
            val south = mercatorTileYEdges(lod, row + 1)
            assertEquals(
                north[1],
                south[0],
                "at lod $lod the shared parallel between rows $row and ${row + 1} must be one Float",
            )
        }
        assertEquals(listOf(0.0f, 1.0f), mercatorTileYEdges(0, 0).toList(), "lod 0 is the whole world")
    }
}
