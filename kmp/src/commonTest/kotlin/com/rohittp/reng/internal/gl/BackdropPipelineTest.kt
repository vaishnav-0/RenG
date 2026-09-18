package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackdropPipelineTest {
    @Test
    fun theRosterAddsBackdropAtWireValueTenWithoutRenumberingAnything() {
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
        assertEquals(9, InternalPipelineRole.TERRAIN_GLOBE_GROUND.wireValue)
        assertEquals(10, InternalPipelineRole.BACKDROP.wireValue)
    }

    @Test
    fun theBackdropSourcesAreAcceptedShaderProfileSources() {
        assertTrue(BACKDROP_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(BACKDROP_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(BACKDROP_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(BACKDROP_FRAGMENT_SOURCE) != null)
    }

    /**
     * One attribute, not two: the backdrop's texture coordinates are derived in the vertex stage from
     * the clip-space position, because a quad that is always the whole frame has nothing to vary.
     * Thirty-two bytes is four vertices of two floats.
     */
    @Test
    fun creationBuildsAProgramAFullFrameQuadAndExactlyOneAttribute() {
        val binding = newBinding()
        val created = createBackdropPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as BackdropPipelineResult.Created

        assertTrue(created.pipeline.program > 0)
        assertTrue(created.pipeline.vertexArray > 0)
        assertEquals(1, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(1, binding.log.count { it.startsWith("vertexAttribPointer") })
        assertTrue(binding.log.any { it.startsWith("bufferData(0x8892,32") })
        assertEquals(REPEAT_LOCATION, created.pipeline.repeatUniformLocation)
        assertEquals(TEXTURE_LOCATION, created.pipeline.textureUniformLocation)
    }

    /**
     * ADR 0068. This is the only texture RenG uploads that wraps, and it is the reason the backdrop
     * has a sampler of its own rather than borrowing the sticker pass's -- every other consumer image
     * clamps. The minification filter names no mipmap level on purpose: under `GL_REPEAT` a mipmap of
     * a tiling pattern blends its own opposite edge across the seam.
     */
    @Test
    fun theSamplerRepeatsOnBothAxesAndAsksForNoMipmapChain() {
        assertEquals(GL_REPEAT, BACKDROP_SAMPLER_STATE.wrapS)
        assertEquals(GL_REPEAT, BACKDROP_SAMPLER_STATE.wrapT)
        assertEquals(GL_LINEAR, BACKDROP_SAMPLER_STATE.minFilter)
        assertEquals(GL_LINEAR, BACKDROP_SAMPLER_STATE.magFilter)
    }

    /**
     * The pass turns depth testing **and** depth writing off, which is what makes "behind everything"
     * true without the backdrop having a depth of its own for later passes to compare against. It also
     * establishes its own blend state rather than inheriting whatever was bound, on the same terms
     * `beginStickerPass` does.
     */
    @Test
    fun drawingDisablesDepthAndUploadsItsRepeatAndTexture() {
        val binding = newBinding()
        val pipeline = (createBackdropPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as BackdropPipelineResult.Created).pipeline
        binding.log.clear()

        drawBackdrop(
            binding = binding,
            backdrop = ResolvedBackdrop.Pattern(
                pipeline = pipeline,
                texture = 77,
                repeat = BackdropRepeat(across = 4.5f, down = 8.0f),
            ),
            resolutionWidthPixels = 1080f,
            resolutionHeightPixels = 1920f,
            frameIndex = 0L,
        )

        assertTrue(binding.log.any { it == "disable(0xB71)" }, binding.log.toString())
        assertTrue(binding.log.any { it == "depthMask(false)" }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("uniform2f($REPEAT_LOCATION,4.5,8.0") }, binding.log.toString())
        assertTrue(binding.log.any { it == "bindTexture(0xDE1,77)" }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("drawArrays(0x5,0,4)") }, binding.log.toString())
    }

    /**
     * ADR 0068's `tileSizeLogicalPixels` is a repeat *distance*, so the repeat count divides by it.
     * Dividing and multiplying both produce a perfectly valid draw whose only difference is that the
     * pattern is three orders of magnitude too small, which no GL call log and no shader test can
     * see -- so this states the arithmetic directly.
     */
    @Test
    fun theBackdropRepeatsOnceEveryTileSizeOfLogicalPixels() {
        val resolved = resolvedBackdropFor(
            outputPixelSize = OutputPixelSize(width = 1080, height = 1920),
            tileSizeLogicalPixels = 256.0,
        )

        assertEquals(1080f / 256f, resolved.across)
        assertEquals(1920f / 256f, resolved.down)

        // A repeat as wide as the frame is exactly one tile across, which is the identity that says
        // the units are the frame's own rather than the image's.
        val once = resolvedBackdropFor(OutputPixelSize(800, 600), 800.0)
        assertEquals(1.0f, once.across)
    }

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        BACKDROP_REPEAT_UNIFORM_NAME to REPEAT_LOCATION,
        BACKDROP_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
    )

    private companion object {
        const val REPEAT_LOCATION: Int = 11
        const val TEXTURE_LOCATION: Int = 12
    }
}

/** ADR 0071: a backdrop drawn from a shader pair the consumer wrote. */
class ConsumerBackdropPipelineTest {

    /**
     * The whole point of the case: a consumer's own program reaches the same full-frame quad draw
     * the pattern uses, under the same depth-off, premultiplied-blend state.
     */
    @Test
    fun aShaderBackdropDrawsTheFullFrameQuadUnderTheSameState() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawBackdrop(binding, shaderBackdrop(pipeline), 1080f, 1920f, frameIndex = 7L)

        assertTrue(binding.log.any { it == "disable(0xB71)" }, binding.log.toString())
        assertTrue(binding.log.any { it == "depthMask(false)" }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("drawArrays(0x5,0,4)") }, binding.log.toString())
    }

    /**
     * The two built-ins ADR 0071 commits to, and nothing else. `uFrameIndex` is asserted at a value
     * the frame actually carries rather than zero, so a binding that passed a constant would fail.
     */
    @Test
    fun aShaderDeclaringTheTwoBuiltInsReceivesBoth() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawBackdrop(binding, shaderBackdrop(pipeline), 1080f, 1920f, frameIndex = 7L)

        assertTrue(
            binding.log.any { it.startsWith("uniform2f($RESOLUTION_LOCATION,1080.0,1920.0") },
            binding.log.toString(),
        )
        assertTrue(binding.log.any { it == "uniform1ui($FRAME_INDEX_LOCATION,7)" }, binding.log.toString())
    }

    /**
     * ADR 0008's negative-location rule, which is what lets the built-ins be a contract a shader
     * opts into rather than a preamble RenG injects: a shader declaring neither name still draws,
     * and RenG writes no uniform at all.
     */
    @Test
    fun aShaderDeclaringNeitherBuiltInStillDraws() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawBackdrop(binding, shaderBackdrop(pipeline), 1080f, 1920f, frameIndex = 7L)

        assertTrue(binding.log.none { it.startsWith("uniform") }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("drawArrays(0x5,0,4)") }, binding.log.toString())
    }

    /** Consumer uniforms and textures bind by name, in name order, as a geometry's do. */
    @Test
    fun consumerUniformsAndTexturesBindByNameInNameOrder() {
        val binding = RecordingGlBinding().withDeclaredNames(
            "uAlpha" to 20,
            "uBeta" to 21,
            "uSamplerA" to 22,
            "uSamplerB" to 23,
        )
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawBackdrop(
            binding = binding,
            backdrop = ResolvedBackdrop.Shader(
                pipeline = pipeline,
                uniforms = mapOf("uBeta" to ShaderValue.Scalar(2f), "uAlpha" to ShaderValue.Scalar(1f)),
                textures = mapOf("uSamplerB" to 91, "uSamplerA" to 90),
            ),
            resolutionWidthPixels = 1f,
            resolutionHeightPixels = 1f,
            frameIndex = 0L,
        )

        assertTrue(binding.log.any { it.startsWith("uniform1f(20,1.0") }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("uniform1f(21,2.0") }, binding.log.toString())
        // Unit 0 goes to the alphabetically first sampler, not to the first map entry.
        assertEquals(
            listOf("bindTexture(0xDE1,90)", "bindTexture(0xDE1,91)"),
            binding.log.filter { it.startsWith("bindTexture") },
        )
        assertTrue(binding.log.any { it == "uniform1i(22,0)" }, binding.log.toString())
        assertTrue(binding.log.any { it == "uniform1i(23,1)" }, binding.log.toString())
    }

    /**
     * A consumer's shader that will not compile must be reported as the consumer's, at
     * `SHADER_COMPILATION` -- not as `glOperationFailure` at `GPU_RESOURCE`, which is what a
     * backdrop key got before ADR 0071 and which sends the reader to look at their driver.
     */
    @Test
    fun aShaderThatWillNotCompileBlamesTheConsumerAndNotTheGpu() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        binding.compileStatus = 0

        val result = createConsumerBackdropPipeline(
            binding, ShaderDialect.GLES, GlProgramCache(), CONSUMER_SHADER_PAIR,
        )

        val failure = (result as ConsumerBackdropPipelineResult.Failed).failure
        assertEquals(RenGErrorCode.SHADER_COMPILE_FAILED, failure.code)
        assertEquals(PipelineStage.SHADER_COMPILATION, failure.stage)
    }

    /** A source that is not a Shader Profile fails the same way, before any GL call is made. */
    @Test
    fun aSourceThatIsNotAShaderProfileFailsAsTheConsumersToo() {
        val binding = RecordingGlBinding().withNoDeclaredNames()

        val result = createConsumerBackdropPipeline(
            binding,
            ShaderDialect.GLES,
            GlProgramCache(),
            ShaderPair(vertexSource = "void main() {}", fragmentSource = "void main() {}"),
        )

        val failure = (result as ConsumerBackdropPipelineResult.Failed).failure
        assertEquals(RenGErrorCode.SHADER_COMPILE_FAILED, failure.code)
        assertEquals(PipelineStage.SHADER_COMPILATION, failure.stage)
        assertTrue(binding.log.none { it.startsWith("createShader") }, binding.log.toString())
    }

    /**
     * Two distinct sources are two programs and one source is one, which is what makes the map a
     * memo rather than a leak. Keyed on the source pair alone, so RenG's own backdrop program and a
     * consumer's cannot collide either.
     */
    @Test
    fun aKeyIsTheShaderSourceSoTwoShadersAreTwoProgramsAndOneIsOne() {
        val deriver = ResourceKeyDeriver()
        val first = deriver.internalPipeline(InternalPipelineRole.BACKDROP, CONSUMER_SHADER_PAIR).key
        val again = deriver.internalPipeline(InternalPipelineRole.BACKDROP, CONSUMER_SHADER_PAIR).key
        val other = deriver.internalPipeline(
            InternalPipelineRole.BACKDROP,
            ShaderPair(CONSUMER_SHADER_PAIR.vertexSource, OTHER_FRAGMENT_SOURCE),
        ).key
        val rengsOwn = deriver.internalPipeline(InternalPipelineRole.BACKDROP, BACKDROP_SHADER_PAIR).key

        assertEquals(first, again)
        assertTrue(first != other)
        assertTrue(first != rengsOwn)
    }

    /**
     * ADR 0072's uniform, bound on a shader that declares it. Sixteen floats, column-major, and
     * `transpose = false` -- GL is told to read them as they are, so a transposed upload would be
     * a silently mirrored grid rather than an error.
     */
    @Test
    fun aShaderDeclaringTheInverseViewProjectionReceivesIt() {
        val binding = RecordingGlBinding().withDeclaredNames(
            BACKDROP_INVERSE_VIEW_PROJECTION_UNIFORM_NAME to INVERSE_LOCATION,
        )
        val pipeline = createdPipeline(binding)
        val matrix = FloatArray(16) { it.toFloat() }
        binding.log.clear()

        drawBackdrop(
            binding = binding,
            backdrop = ResolvedBackdrop.Shader(pipeline, emptyMap(), emptyMap(), matrix),
            resolutionWidthPixels = 1f,
            resolutionHeightPixels = 1f,
            frameIndex = 0L,
        )

        assertTrue(
            binding.log.any { it.startsWith("uniformMatrix4fv($INVERSE_LOCATION,1,false") },
            binding.log.toString(),
        )
        // The exact sixteen floats, in the order handed over: a transposing upload would still
        // log a call and still draw, and only the values tell the two apart.
        assertEquals(matrix.toList(), binding.uniformMatrix4fvValues[INVERSE_LOCATION]?.toList())
    }

    /**
     * A camera that does not invert binds nothing, rather than binding an identity a shader would
     * read as a real answer and draw a grid from.
     */
    @Test
    fun anUninvertibleCameraBindsNoMatrixAtAll() {
        val binding = RecordingGlBinding().withDeclaredNames(
            BACKDROP_INVERSE_VIEW_PROJECTION_UNIFORM_NAME to INVERSE_LOCATION,
        )
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawBackdrop(
            binding = binding,
            backdrop = ResolvedBackdrop.Shader(pipeline, emptyMap(), emptyMap(), inverseViewProjection = null),
            resolutionWidthPixels = 1f,
            resolutionHeightPixels = 1f,
            frameIndex = 0L,
        )

        assertTrue(binding.log.none { it.startsWith("uniformMatrix4fv") }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("drawArrays(0x5,0,4)") }, binding.log.toString())
    }

    /** A shader that never names it still draws, which is ADR 0008 holding for this uniform too. */
    @Test
    fun aShaderNotDeclaringTheInverseViewProjectionStillDraws() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createdPipeline(binding)
        binding.log.clear()

        drawBackdrop(
            binding = binding,
            backdrop = ResolvedBackdrop.Shader(
                pipeline, emptyMap(), emptyMap(), FloatArray(16) { it.toFloat() },
            ),
            resolutionWidthPixels = 1f,
            resolutionHeightPixels = 1f,
            frameIndex = 0L,
        )

        assertTrue(binding.log.none { it.startsWith("uniformMatrix4fv") }, binding.log.toString())
        assertTrue(binding.log.any { it.startsWith("drawArrays(0x5,0,4)") }, binding.log.toString())
    }

    private fun createdPipeline(binding: RecordingGlBinding): ConsumerBackdropPipeline =
        (
            createConsumerBackdropPipeline(
                binding, ShaderDialect.GLES, GlProgramCache(), CONSUMER_SHADER_PAIR,
            ) as ConsumerBackdropPipelineResult.Created
            ).pipeline

    private fun shaderBackdrop(pipeline: ConsumerBackdropPipeline): ResolvedBackdrop.Shader =
        ResolvedBackdrop.Shader(pipeline = pipeline, uniforms = emptyMap(), textures = emptyMap())

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        UNIFORM_RESOLUTION to RESOLUTION_LOCATION,
        UNIFORM_FRAME_INDEX to FRAME_INDEX_LOCATION,
    )

    private companion object {
        const val RESOLUTION_LOCATION: Int = 31
        const val FRAME_INDEX_LOCATION: Int = 32
        const val INVERSE_LOCATION: Int = 33

        val CONSUMER_SHADER_PAIR: ShaderPair = ShaderPair(
            vertexSource = "#version 300 es\n" +
                "layout(location = 0) in vec2 aQuad;\n" +
                "void main() { gl_Position = vec4(aQuad, 0.0, 1.0); }\n",
            fragmentSource = "#version 300 es\n" +
                "precision highp float;\n" +
                "uniform vec2 uResolution;\n" +
                "out vec4 colour;\n" +
                "void main() { colour = vec4(gl_FragCoord.xy / uResolution, 0.0, 1.0); }\n",
        )

        const val OTHER_FRAGMENT_SOURCE: String = "#version 300 es\n" +
            "precision highp float;\n" +
            "out vec4 colour;\n" +
            "void main() { colour = vec4(1.0); }\n"
    }
}
