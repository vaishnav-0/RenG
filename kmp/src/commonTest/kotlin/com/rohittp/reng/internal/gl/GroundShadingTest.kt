package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `RendererConfiguration.terrainShading` at the level a unit test can reach: which source is
 * composed, which program is compiled, and which uniform is uploaded. **That the shading is
 * *correct* is `runGroundShadingReadback`'s**, on a real driver, in pixels — nothing here executes a
 * line of GLSL.
 *
 * The division matters because the option's whole promise is a negative one: off, the ground must
 * draw what three published releases drew. A unit test can prove the unshaded program's source is
 * the same characters; only a readback can prove the pixels are the same bytes; and the source claim
 * is worth making anyway, because it is the one that says *why* the pixels match.
 */
class GroundShadingTest {
    @Test fun theUnshadedTerrainSourcesCarryNoNormalAtAll() {
        listOf(
            "mercator" to TERRAIN_GROUND_VERTEX_SOURCE,
            "globe" to TERRAIN_GLOBE_GROUND_VERTEX_SOURCE,
        ).forEach { (projection, source) ->
            assertFalse(
                source.contains("rengGroundEnuNormal"),
                "the $projection ground's unshaded displacing stage must compute no normal: $source",
            )
            assertFalse(
                source.contains("rengGroundNormalEnu"),
                "the $projection ground's unshaded displacing stage must emit no normal varying: $source",
            )
            assertFalse(
                source.contains(GROUND_TILE_SIDE_METRES_UNIFORM_NAME),
                "the $projection ground's unshaded displacing stage needs no horizontal run: $source",
            )
        }
    }

    /**
     * The shaded source is the unshaded one **plus** the normal fragment, one varying and one
     * statement — never a restructuring of it.
     *
     * This is what makes "off draws the bytes three releases drew" a claim about which program ran
     * rather than an argument about two shaders being equivalent. Both sources come out of one
     * builder, and the assertion below is that the builder adds rather than rewrites.
     */
    @Test fun theShadedTerrainSourcesAreTheUnshadedOnesPlusTheSharedNormal() {
        listOf(
            Triple("mercator", TERRAIN_GROUND_VERTEX_SOURCE, TERRAIN_SHADED_GROUND_VERTEX_SOURCE),
            Triple("globe", TERRAIN_GLOBE_GROUND_VERTEX_SOURCE, TERRAIN_SHADED_GLOBE_GROUND_VERTEX_SOURCE),
        ).forEach { (projection, unshaded, shaded) ->
            assertTrue(
                shaded.contains(GROUND_ELEVATION_SOURCE),
                "the $projection ground's shaded stage still reads elevation from the one shared text",
            )
            assertTrue(
                shaded.contains(GROUND_NORMAL_SOURCE),
                "the $projection ground's shaded stage derives its normal from the one shared text",
            )
            assertTrue(
                shaded.contains("out vec3 rengGroundNormalEnu;"),
                "the $projection ground's shaded stage hands the normal to the fragment stage",
            )
            assertTrue(
                shaded.length > unshaded.length,
                "the $projection ground's shaded stage adds to the unshaded one",
            )
            unshaded.lines().filter { it.isNotBlank() }.forEach { line ->
                assertTrue(
                    shaded.contains(line),
                    "the $projection ground's shaded stage keeps every line of the unshaded one; " +
                        "`$line` is missing",
                )
            }
        }
    }

    /**
     * The elevation source is composed **before** the normal source, which is not a style choice:
     * `rengGroundEnuNormal` calls `rengGroundElevationMetres` four times, and GLSL requires a
     * declaration before its use.
     */
    @Test fun theNormalSourceIsComposedAfterTheElevationItCalls() {
        listOf(TERRAIN_SHADED_GROUND_VERTEX_SOURCE, TERRAIN_SHADED_GLOBE_GROUND_VERTEX_SOURCE).forEach { source ->
            assertTrue(
                source.indexOf(GROUND_ELEVATION_SOURCE) < source.indexOf(GROUND_NORMAL_SOURCE),
                "GLSL has no forward declarations: $source",
            )
        }
    }

    @Test fun theShadedSourcesAreAcceptedShaderProfileSources() {
        listOf(
            TERRAIN_SHADED_GROUND_VERTEX_SOURCE,
            TERRAIN_SHADED_GLOBE_GROUND_VERTEX_SOURCE,
            GROUND_SHADED_FRAGMENT_SOURCE,
        ).forEach { source ->
            assertTrue(source.startsWith("#version 300 es\n"), source)
            assertTrue(scanShaderProfile(source) != null, source)
        }
    }

    /**
     * One shaded fragment stage for both projections, the identical object rather than an equal one
     * — [GROUND_FRAGMENT_SOURCE]'s own argument, which `GlobeGroundPipelineTest` already makes for
     * the unshaded pair: the globe and the Mercator ground must sample and light the identical
     * texture identically, and a second copy would make a drift a silent edit.
     */
    @Test fun bothProjectionsShareOneShadedFragmentStage() {
        assertSame(GROUND_SHADED_FRAGMENT_SOURCE, TERRAIN_SHADED_GROUND_SHADER_PAIR.fragmentSource)
        assertSame(GROUND_SHADED_FRAGMENT_SOURCE, TERRAIN_SHADED_GLOBE_GROUND_SHADER_PAIR.fragmentSource)
        assertSame(GROUND_FRAGMENT_SOURCE, TERRAIN_GROUND_SHADER_PAIR.fragmentSource)
        assertSame(GROUND_FRAGMENT_SOURCE, TERRAIN_GLOBE_GROUND_SHADER_PAIR.fragmentSource)
    }

    /**
     * The light in the shader is ADR 0026's, **evaluated** rather than typed out a second time —
     * `demDecodeCoefficients`' argument applied to a lighting constant.
     *
     * The second assertion is the one with teeth. The flat datum the shader subtracts must be the
     * *same characters* as the light's own `z`, or a level ground's `incidence - flat` is an ulp
     * instead of exactly zero and "terrain shading leaves ground with no relief byte-identical"
     * becomes a tolerance.
     */
    @Test fun theShadedFragmentStageCarriesAdr0026sOwnNumbers() {
        listOf(
            SCENE_LIGHT_DIRECTION_ENU.x,
            SCENE_LIGHT_DIRECTION_ENU.y,
            SCENE_LIGHT_DIRECTION_ENU.z,
        ).forEach { component ->
            assertTrue(
                GROUND_SHADED_FRAGMENT_SOURCE.contains(glslFloatLiteral(component)),
                "the ground's light is ADR 0026's own vector: $GROUND_SHADED_FRAGMENT_SOURCE",
            )
        }
        assertEquals(
            glslFloatLiteral(SCENE_LIGHT_DIRECTION_ENU.z),
            SCENE_LIGHT_FLAT_INCIDENCE_GLSL,
            "the flat datum must be the light's own up component to the character, or subtracting " +
                "it from a level ground's incidence is not exactly zero",
        )
        assertTrue(
            GROUND_SHADED_FRAGMENT_SOURCE.contains("1.0 + (incidence - $SCENE_LIGHT_FLAT_INCIDENCE_GLSL)"),
            "level ground must reach a factor of exactly 1.0: $GROUND_SHADED_FRAGMENT_SOURCE",
        )
    }

    /**
     * The gain is `diffuse / (ambient + diffuse * flatIncidence)`, which is ADR 0026's light divided
     * by what it does to level ground — the normalisation that keeps switching the option on from
     * darkening every flat map by a fifth.
     */
    @Test fun theReliefGainIsTheLightNormalisedByItsOwnFlatValue() {
        val ambient = SCENE_LIGHT_AMBIENT.toDouble()
        val diffuse = SCENE_LIGHT_DIFFUSE.toDouble()
        assertEquals(
            glslFloatLiteral(diffuse / (ambient + diffuse * SCENE_LIGHT_DIRECTION_ENU.z)),
            SCENE_LIGHT_RELIEF_GAIN_GLSL,
        )
        // The ADR's own light, applied raw, would put level ground here -- which is what the
        // normalisation exists to avoid, and the number worth having written down.
        assertTrue(
            ambient + diffuse * SCENE_LIGHT_DIRECTION_ENU.z < 0.82,
            "ADR 0026's light leaves a level surface below four fifths of its own colour, which is " +
                "why the ground's shading is normalised by it rather than applied raw",
        )
    }

    /**
     * A GLSL literal must be the same characters on every published target, because the shader's
     * text is a field of its ADR 0018 identity. `Double.toString` is not: the JVM and Kotlin/Native
     * are each free to pick a different shortest round-tripping decimal.
     */
    @Test fun aGlslFloatLiteralIsNineFixedDecimalsWithItsOwnSign() {
        assertEquals("0.500000000", glslFloatLiteral(0.5))
        assertEquals("-0.500000000", glslFloatLiteral(-0.5))
        assertEquals("0.000000000", glslFloatLiteral(0.0))
        assertEquals("1.000000000", glslFloatLiteral(1.0))
        assertEquals("-2.250000000", glslFloatLiteral(-2.25))
        assertEquals("0.000000001", glslFloatLiteral(1e-9))
        assertEquals("3.141592654", glslFloatLiteral(3.14159265358979))
    }

    /**
     * The flag picks which of the two displacing programs is compiled, and the *only* thing the draw
     * path learns from it is whether one uniform location came back negative.
     *
     * That is the design: `RendererConfiguration.terrainShading` cannot vary frame to frame, so
     * `drawGround` carries no branch for it and no frame can get it wrong per tile.
     */
    @Test fun theFlagPicksWhichDisplacingProgramTheMercatorGroundCompiles() {
        listOf(false, true).forEach { shading ->
            val binding = shadingBinding()
            val pipeline = (
                createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache(), terrainShading = shading)
                    as GroundPipelineResult.Created
                ).pipeline
            assertEquals(
                shading,
                binding.shaderSources.values.any { it.contains("rengGroundEnuNormal") },
                "a pipeline with terrainShading=$shading compiled the wrong displacing stage",
            )
            assertEquals(
                shading,
                binding.shaderSources.values.any { it.contains(GROUND_SHADED_FRAGMENT_SOURCE) },
                "a pipeline with terrainShading=$shading compiled the wrong fragment stage",
            )
            assertTrue(pipeline.terrain.program > 0)
        }
    }

    @Test fun theFlagPicksWhichDisplacingProgramTheGlobeGroundCompiles() {
        listOf(false, true).forEach { shading ->
            val binding = shadingBinding()
            val pipeline = (
                createGlobeGroundPipeline(
                    binding,
                    ShaderDialect.GLES,
                    GlProgramCache(),
                    terrainShading = shading,
                ) as GlobeGroundPipelineResult.Created
                ).pipeline
            assertEquals(
                shading,
                binding.shaderSources.values.any { it.contains("rengGroundEnuNormal") },
                "a globe pipeline with terrainShading=$shading compiled the wrong displacing stage",
            )
            assertEquals(
                shading,
                binding.shaderSources.values.any { it.contains(GROUND_SHADED_FRAGMENT_SOURCE) },
                "a globe pipeline with terrainShading=$shading compiled the wrong fragment stage",
            )
            assertTrue(pipeline.terrain.program > 0)
        }
    }

    /**
     * The two displacing programs are two cache entries, so a renderer configured one way can never
     * be handed the other's program by [GlProgramCache].
     */
    @Test fun theShadedAndUnshadedDisplacingProgramsAreDistinctIdentities() {
        val binding = shadingBinding()
        val cache = GlProgramCache()
        val plain = (
            createGroundPipeline(binding, ShaderDialect.GLES, cache, terrainShading = false)
                as GroundPipelineResult.Created
            ).pipeline
        val shaded = (
            createGroundPipeline(binding, ShaderDialect.GLES, cache, terrainShading = true)
                as GroundPipelineResult.Created
            ).pipeline
        assertTrue(
            plain.terrain.key != shaded.terrain.key,
            "two different shader pairs are two different canonical identities (ADR 0018)",
        )
        assertTrue(plain.terrain.program != shaded.terrain.program)
        assertEquals(
            plain.key,
            shaded.key,
            "the flat program is untouched by the option, so its identity must not move",
        )
    }

    /**
     * **A negative uniform location is the whole of how an unshaded program says it has no
     * horizontal run**, and this is the case that pins the guard rather than the driver.
     *
     * `glGetUniformLocation` answers `-1` for a name the linked program never declared — ADR 0008's
     * own mechanism, and what [RecordingGlBinding] models when a name is not in its declared set.
     * The unshaded displacing source declares no `rengGroundTileSideMetres`
     * ([theUnshadedTerrainSourcesCarryNoNormalAtAll] asserts exactly that on the text), so a real
     * driver answers `-1` and this upload is skipped. The two halves are asserted separately because
     * a fake binding cannot compile GLSL and would otherwise be asked to invent the answer.
     */
    @Test fun aNegativeLocationIsHowAnUnshadedProgramSkipsTheHorizontalRun() {
        listOf(false, true).forEach { declares ->
            val binding = if (declares) shadingBinding() else RecordingGlBinding().withNoDeclaredNames()
            val pipeline = (
                createGroundPipeline(binding, ShaderDialect.GLES, GlProgramCache(), terrainShading = declares)
                    as GroundPipelineResult.Created
                ).pipeline
            assertEquals(
                if (declares) TILE_SIDE_LOCATION else -1,
                pipeline.terrain.elevation.tileSideMetres,
            )
            binding.log.clear()
            drawGround(
                binding = binding,
                pipeline = pipeline,
                tiles = listOf(
                    ResolvedGroundTile(
                        modelViewProjection = FloatArray(16),
                        texture = 1,
                        elevation = MercatorGroundTileDem(
                            dem = GroundTileDem(
                                demTexture = 2,
                                window = floatArrayOf(0f, 1f, 0f, 1f),
                                tileSideMetres = 39_135.758f,
                            ),
                            mercatorY = mercatorTileYEdges(lod = 10, tileY = 512),
                        ),
                    ),
                ),
                cellsPerTileSide = 2,
                elevation = MercatorGroundElevationFrame(
                    dem = GroundDemUniforms(
                        decode = floatArrayOf(1f, 2f, 3f, 4f),
                        interiorSizePx = 8,
                        exaggeration = 2f,
                    ),
                    equatorialLogicalPixelsPerMetre = 0.5f,
                ),
            )
            assertEquals(
                declares,
                binding.log.any { it.startsWith("uniform1f($TILE_SIDE_LOCATION,") },
                "a program declaring=$declares uploaded the wrong set of uniforms: ${binding.log}",
            )
        }
    }

    private fun shadingBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        GROUND_TILE_SIDE_METRES_UNIFORM_NAME to TILE_SIDE_LOCATION,
    )

    private companion object {
        const val TILE_SIDE_LOCATION: Int = 21
    }
}
