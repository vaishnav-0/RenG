@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.*
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

private const val EGL_DISPATCH_LIBRARY: String = "libEGL.so.1"

/**
 * Resolves all ninety-one roster entries against the running system's EGL dispatch library.
 *
 * Resolution is eager and total: every name must resolve or the whole binding is
 * [GlBindingResult.Unsupported]. This turns a partially resolvable driver into a setup-time
 * typed error instead of a null-pointer call on the first frame. `libEGL.so.1` is loaded
 * rather than `libGLESv2.so.2` because on a glvnd-dispatched system `eglGetProcAddress` is the
 * only resolver guaranteed to return the entry points belonging to the current context's
 * vendor; `dlsym` against the dispatch library happens to work and is kept only as a fallback,
 * not as the contract. RenG resolves entry points and never creates a context (ADR 0001).
 */
internal fun openLinuxGlBinding(
    libraryName: String = EGL_DISPATCH_LIBRARY,
): GlBindingResult {
    val library = dlopen(libraryName, RTLD_NOW) ?: return unsupportedRenderContext()
    val getProcAddress = dlsym(library, "eglGetProcAddress")
        ?.reinterpret<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>()
        ?: return unsupportedRenderContext()

    val table = ArrayList<COpaquePointer>(GlEntryPoint.entries.size)
    for (entry in GlEntryPoint.entries) {
        val address = memScoped { getProcAddress(entry.cName.cstr.ptr) }
            ?: dlsym(library, entry.cName)
            ?: return unsupportedRenderContext()
        table += address
    }
    return GlBindingResult.Bound(LinuxGlBinding(table))
}

internal actual fun openPlatformGlBinding(): GlBindingResult = openLinuxGlBinding()

private fun Boolean.toGlBoolean(): UByte = if (this) 1u.toUByte() else 0u.toUByte()

private fun UByte.toKotlinBoolean(): Boolean = this.toInt() != 0

/**
 * The ninety-one-entry [GlBinding] over a fully resolved function-pointer table.
 *
 * [table] is indexed by [GlEntryPoint.ordinal]; each property below reinterprets exactly one
 * slot to the C signature Kotlin/Native needs to call it, using the standard width mapping:
 * `GLenum`/`GLuint` as [UInt], `GLint`/`GLsizei` as [Int], `GLboolean` as [UByte], `GLfloat` as
 * [Float], `GLsizeiptr`/`GLintptr` as [Long], and pointer parameters as the matching typed or
 * opaque `CPointer`.
 */
internal class LinuxGlBinding(table: List<COpaquePointer>) : GlBinding {

    // ----- Queries -----
    private val getErrorFn: CPointer<CFunction<() -> UInt>> =
        table[GlEntryPoint.GET_ERROR.ordinal].reinterpret()
    private val getStringFn: CPointer<CFunction<(UInt) -> CPointer<UByteVar>?>> =
        table[GlEntryPoint.GET_STRING.ordinal].reinterpret()
    private val getStringiFn: CPointer<CFunction<(UInt, UInt) -> CPointer<UByteVar>?>> =
        table[GlEntryPoint.GET_STRINGI.ordinal].reinterpret()
    private val getIntegervFn: CPointer<CFunction<(UInt, CPointer<IntVar>?) -> Unit>> =
        table[GlEntryPoint.GET_INTEGERV.ordinal].reinterpret()
    private val getFloatvFn: CPointer<CFunction<(UInt, CPointer<FloatVar>?) -> Unit>> =
        table[GlEntryPoint.GET_FLOATV.ordinal].reinterpret()
    private val getBooleanvFn: CPointer<CFunction<(UInt, CPointer<UByteVar>?) -> Unit>> =
        table[GlEntryPoint.GET_BOOLEANV.ordinal].reinterpret()
    private val isEnabledFn: CPointer<CFunction<(UInt) -> UByte>> =
        table[GlEntryPoint.IS_ENABLED.ordinal].reinterpret()
    private val getIntegeriVFn: CPointer<CFunction<(UInt, UInt, CPointer<IntVar>?) -> Unit>> =
        table[GlEntryPoint.GET_INTEGERI_V.ordinal].reinterpret()

    // ----- Framebuffers / renderbuffers -----
    private val genFramebuffersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.GEN_FRAMEBUFFERS.ordinal].reinterpret()
    private val deleteFramebuffersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.DELETE_FRAMEBUFFERS.ordinal].reinterpret()
    private val bindFramebufferFn: CPointer<CFunction<(UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BIND_FRAMEBUFFER.ordinal].reinterpret()
    private val framebufferTexture2DFn: CPointer<CFunction<(UInt, UInt, UInt, UInt, Int) -> Unit>> =
        table[GlEntryPoint.FRAMEBUFFER_TEXTURE_2D.ordinal].reinterpret()
    private val framebufferRenderbufferFn: CPointer<CFunction<(UInt, UInt, UInt, UInt) -> Unit>> =
        table[GlEntryPoint.FRAMEBUFFER_RENDERBUFFER.ordinal].reinterpret()
    private val checkFramebufferStatusFn: CPointer<CFunction<(UInt) -> UInt>> =
        table[GlEntryPoint.CHECK_FRAMEBUFFER_STATUS.ordinal].reinterpret()
    private val isFramebufferFn: CPointer<CFunction<(UInt) -> UByte>> =
        table[GlEntryPoint.IS_FRAMEBUFFER.ordinal].reinterpret()
    private val genRenderbuffersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.GEN_RENDERBUFFERS.ordinal].reinterpret()
    private val deleteRenderbuffersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.DELETE_RENDERBUFFERS.ordinal].reinterpret()
    private val bindRenderbufferFn: CPointer<CFunction<(UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BIND_RENDERBUFFER.ordinal].reinterpret()
    private val renderbufferStorageFn: CPointer<CFunction<(UInt, UInt, Int, Int) -> Unit>> =
        table[GlEntryPoint.RENDERBUFFER_STORAGE.ordinal].reinterpret()
    private val blitFramebufferFn: CPointer<CFunction<
        (Int, Int, Int, Int, Int, Int, Int, Int, UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BLIT_FRAMEBUFFER.ordinal].reinterpret()
    private val drawBuffersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.DRAW_BUFFERS.ordinal].reinterpret()
    private val readBufferFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.READ_BUFFER.ordinal].reinterpret()

    // ----- Textures / samplers -----
    private val genTexturesFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.GEN_TEXTURES.ordinal].reinterpret()
    private val deleteTexturesFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.DELETE_TEXTURES.ordinal].reinterpret()
    private val bindTextureFn: CPointer<CFunction<(UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BIND_TEXTURE.ordinal].reinterpret()
    private val activeTextureFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.ACTIVE_TEXTURE.ordinal].reinterpret()
    private val texImage2DFn: CPointer<CFunction<
        (UInt, Int, Int, Int, Int, Int, UInt, UInt, COpaquePointer?) -> Unit>> =
        table[GlEntryPoint.TEX_IMAGE_2D.ordinal].reinterpret()
    private val texStorage2DFn: CPointer<CFunction<(UInt, Int, UInt, Int, Int) -> Unit>> =
        table[GlEntryPoint.TEX_STORAGE_2D.ordinal].reinterpret()
    private val texParameteriFn: CPointer<CFunction<(UInt, UInt, Int) -> Unit>> =
        table[GlEntryPoint.TEX_PARAMETERI.ordinal].reinterpret()
    private val generateMipmapFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.GENERATE_MIPMAP.ordinal].reinterpret()
    private val genSamplersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.GEN_SAMPLERS.ordinal].reinterpret()
    private val deleteSamplersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.DELETE_SAMPLERS.ordinal].reinterpret()
    private val bindSamplerFn: CPointer<CFunction<(UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BIND_SAMPLER.ordinal].reinterpret()
    private val samplerParameteriFn: CPointer<CFunction<(UInt, UInt, Int) -> Unit>> =
        table[GlEntryPoint.SAMPLER_PARAMETERI.ordinal].reinterpret()
    private val pixelStoreiFn: CPointer<CFunction<(UInt, Int) -> Unit>> =
        table[GlEntryPoint.PIXEL_STOREI.ordinal].reinterpret()
    private val readPixelsFn: CPointer<CFunction<
        (Int, Int, Int, Int, UInt, UInt, COpaquePointer?) -> Unit>> =
        table[GlEntryPoint.READ_PIXELS.ordinal].reinterpret()

    // ----- Buffers / vertex arrays -----
    private val genBuffersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.GEN_BUFFERS.ordinal].reinterpret()
    private val deleteBuffersFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.DELETE_BUFFERS.ordinal].reinterpret()
    private val bindBufferFn: CPointer<CFunction<(UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BIND_BUFFER.ordinal].reinterpret()
    private val bindBufferBaseFn: CPointer<CFunction<(UInt, UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BIND_BUFFER_BASE.ordinal].reinterpret()
    private val bufferDataFn: CPointer<CFunction<(UInt, Long, COpaquePointer?, UInt) -> Unit>> =
        table[GlEntryPoint.BUFFER_DATA.ordinal].reinterpret()
    private val bufferSubDataFn: CPointer<CFunction<(UInt, Long, Long, COpaquePointer?) -> Unit>> =
        table[GlEntryPoint.BUFFER_SUB_DATA.ordinal].reinterpret()
    private val genVertexArraysFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.GEN_VERTEX_ARRAYS.ordinal].reinterpret()
    private val deleteVertexArraysFn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>> =
        table[GlEntryPoint.DELETE_VERTEX_ARRAYS.ordinal].reinterpret()
    private val bindVertexArrayFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.BIND_VERTEX_ARRAY.ordinal].reinterpret()
    private val enableVertexAttribArrayFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.ENABLE_VERTEX_ATTRIB_ARRAY.ordinal].reinterpret()
    private val disableVertexAttribArrayFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.DISABLE_VERTEX_ATTRIB_ARRAY.ordinal].reinterpret()
    private val vertexAttribPointerFn: CPointer<CFunction<
        (UInt, Int, UInt, UByte, Int, COpaquePointer?) -> Unit>> =
        table[GlEntryPoint.VERTEX_ATTRIB_POINTER.ordinal].reinterpret()

    // ----- Shaders / programs -----
    private val createShaderFn: CPointer<CFunction<(UInt) -> UInt>> =
        table[GlEntryPoint.CREATE_SHADER.ordinal].reinterpret()
    private val deleteShaderFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.DELETE_SHADER.ordinal].reinterpret()
    private val shaderSourceFn: CPointer<CFunction<
        (UInt, Int, CPointer<CPointerVar<ByteVar>>?, CPointer<IntVar>?) -> Unit>> =
        table[GlEntryPoint.SHADER_SOURCE.ordinal].reinterpret()
    private val compileShaderFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.COMPILE_SHADER.ordinal].reinterpret()
    private val getShaderivFn: CPointer<CFunction<(UInt, UInt, CPointer<IntVar>?) -> Unit>> =
        table[GlEntryPoint.GET_SHADERIV.ordinal].reinterpret()
    private val getShaderInfoLogFn: CPointer<CFunction<
        (UInt, Int, CPointer<IntVar>?, CPointer<ByteVar>?) -> Unit>> =
        table[GlEntryPoint.GET_SHADER_INFO_LOG.ordinal].reinterpret()
    private val createProgramFn: CPointer<CFunction<() -> UInt>> =
        table[GlEntryPoint.CREATE_PROGRAM.ordinal].reinterpret()
    private val deleteProgramFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.DELETE_PROGRAM.ordinal].reinterpret()
    private val attachShaderFn: CPointer<CFunction<(UInt, UInt) -> Unit>> =
        table[GlEntryPoint.ATTACH_SHADER.ordinal].reinterpret()
    private val linkProgramFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.LINK_PROGRAM.ordinal].reinterpret()
    private val getProgramivFn: CPointer<CFunction<(UInt, UInt, CPointer<IntVar>?) -> Unit>> =
        table[GlEntryPoint.GET_PROGRAMIV.ordinal].reinterpret()
    private val getProgramInfoLogFn: CPointer<CFunction<
        (UInt, Int, CPointer<IntVar>?, CPointer<ByteVar>?) -> Unit>> =
        table[GlEntryPoint.GET_PROGRAM_INFO_LOG.ordinal].reinterpret()
    private val useProgramFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.USE_PROGRAM.ordinal].reinterpret()
    private val getAttribLocationFn: CPointer<CFunction<(UInt, CPointer<ByteVar>?) -> Int>> =
        table[GlEntryPoint.GET_ATTRIB_LOCATION.ordinal].reinterpret()
    private val getUniformLocationFn: CPointer<CFunction<(UInt, CPointer<ByteVar>?) -> Int>> =
        table[GlEntryPoint.GET_UNIFORM_LOCATION.ordinal].reinterpret()
    private val uniform1iFn: CPointer<CFunction<(Int, Int) -> Unit>> =
        table[GlEntryPoint.UNIFORM_1I.ordinal].reinterpret()
    private val uniform1fFn: CPointer<CFunction<(Int, Float) -> Unit>> =
        table[GlEntryPoint.UNIFORM_1F.ordinal].reinterpret()
    private val uniform2fFn: CPointer<CFunction<(Int, Float, Float) -> Unit>> =
        table[GlEntryPoint.UNIFORM_2F.ordinal].reinterpret()
    private val uniform3fFn: CPointer<CFunction<(Int, Float, Float, Float) -> Unit>> =
        table[GlEntryPoint.UNIFORM_3F.ordinal].reinterpret()
    private val uniform4fFn: CPointer<CFunction<(Int, Float, Float, Float, Float) -> Unit>> =
        table[GlEntryPoint.UNIFORM_4F.ordinal].reinterpret()
    private val uniform1uiFn: CPointer<CFunction<(Int, UInt) -> Unit>> =
        table[GlEntryPoint.UNIFORM_1UI.ordinal].reinterpret()
    private val uniformMatrix4fvFn: CPointer<CFunction<
        (Int, Int, UByte, CPointer<FloatVar>?) -> Unit>> =
        table[GlEntryPoint.UNIFORM_MATRIX_4FV.ordinal].reinterpret()
    private val getUniformBlockIndexFn: CPointer<CFunction<(UInt, CPointer<ByteVar>?) -> UInt>> =
        table[GlEntryPoint.GET_UNIFORM_BLOCK_INDEX.ordinal].reinterpret()
    private val uniformBlockBindingFn: CPointer<CFunction<(UInt, UInt, UInt) -> Unit>> =
        table[GlEntryPoint.UNIFORM_BLOCK_BINDING.ordinal].reinterpret()

    // ----- Pipeline state / draw -----
    private val enableFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.ENABLE.ordinal].reinterpret()
    private val disableFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.DISABLE.ordinal].reinterpret()
    private val blendFuncSeparateFn: CPointer<CFunction<(UInt, UInt, UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BLEND_FUNC_SEPARATE.ordinal].reinterpret()
    private val blendEquationSeparateFn: CPointer<CFunction<(UInt, UInt) -> Unit>> =
        table[GlEntryPoint.BLEND_EQUATION_SEPARATE.ordinal].reinterpret()
    private val blendColorFn: CPointer<CFunction<(Float, Float, Float, Float) -> Unit>> =
        table[GlEntryPoint.BLEND_COLOR.ordinal].reinterpret()
    private val depthFuncFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.DEPTH_FUNC.ordinal].reinterpret()
    private val depthMaskFn: CPointer<CFunction<(UByte) -> Unit>> =
        table[GlEntryPoint.DEPTH_MASK.ordinal].reinterpret()
    private val depthRangefFn: CPointer<CFunction<(Float, Float) -> Unit>> =
        table[GlEntryPoint.DEPTH_RANGEF.ordinal].reinterpret()
    private val cullFaceFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.CULL_FACE.ordinal].reinterpret()
    private val frontFaceFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.FRONT_FACE.ordinal].reinterpret()
    private val viewportFn: CPointer<CFunction<(Int, Int, Int, Int) -> Unit>> =
        table[GlEntryPoint.VIEWPORT.ordinal].reinterpret()
    private val scissorFn: CPointer<CFunction<(Int, Int, Int, Int) -> Unit>> =
        table[GlEntryPoint.SCISSOR.ordinal].reinterpret()
    private val colorMaskFn: CPointer<CFunction<(UByte, UByte, UByte, UByte) -> Unit>> =
        table[GlEntryPoint.COLOR_MASK.ordinal].reinterpret()
    private val clearColorFn: CPointer<CFunction<(Float, Float, Float, Float) -> Unit>> =
        table[GlEntryPoint.CLEAR_COLOR.ordinal].reinterpret()
    private val clearDepthfFn: CPointer<CFunction<(Float) -> Unit>> =
        table[GlEntryPoint.CLEAR_DEPTHF.ordinal].reinterpret()
    private val clearFn: CPointer<CFunction<(UInt) -> Unit>> =
        table[GlEntryPoint.CLEAR.ordinal].reinterpret()
    private val drawArraysFn: CPointer<CFunction<(UInt, Int, Int) -> Unit>> =
        table[GlEntryPoint.DRAW_ARRAYS.ordinal].reinterpret()
    private val drawElementsFn: CPointer<CFunction<(UInt, Int, UInt, COpaquePointer?) -> Unit>> =
        table[GlEntryPoint.DRAW_ELEMENTS.ordinal].reinterpret()
    private val finishFn: CPointer<CFunction<() -> Unit>> =
        table[GlEntryPoint.FINISH.ordinal].reinterpret()

    // ===== Queries =====

    override fun getError(): Int = getErrorFn().toInt()

    override fun getString(name: Int): String? =
        getStringFn(name.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getStringi(name: Int, index: Int): String? =
        getStringiFn(name.toUInt(), index.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            getIntegervFn(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getFloatv(pname: Int, out: FloatArray) {
        require(out.isNotEmpty()) { "a float query needs a destination" }
        memScoped {
            val buffer = allocArray<FloatVar>(out.size)
            getFloatvFn(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        memScoped {
            val buffer = allocArray<UByteVar>(out.size)
            getBooleanvFn(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index].toKotlinBoolean()
        }
    }

    override fun isEnabled(cap: Int): Boolean = isEnabledFn(cap.toUInt()).toInt() != 0

    override fun getIntegeri_v(pname: Int, index: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an indexed integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            getIntegeriVFn(pname.toUInt(), index.toUInt(), buffer)
            for (i in out.indices) out[i] = buffer[i]
        }
    }

    // ===== Framebuffers / renderbuffers =====

    override fun genFramebuffers(count: Int, out: IntArray) = generateNames(count, out, genFramebuffersFn)

    override fun deleteFramebuffers(count: Int, names: IntArray) = deleteNames(count, names, deleteFramebuffersFn)

    override fun bindFramebuffer(target: Int, framebuffer: Int) =
        bindFramebufferFn(target.toUInt(), framebuffer.toUInt())

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) =
        framebufferTexture2DFn(
            target.toUInt(), attachment.toUInt(), textureTarget.toUInt(), texture.toUInt(), level,
        )

    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) =
        framebufferRenderbufferFn(
            target.toUInt(), attachment.toUInt(), renderbufferTarget.toUInt(), renderbuffer.toUInt(),
        )

    override fun checkFramebufferStatus(target: Int): Int = checkFramebufferStatusFn(target.toUInt()).toInt()

    override fun isFramebuffer(framebuffer: Int): Boolean = isFramebufferFn(framebuffer.toUInt()).toKotlinBoolean()

    override fun genRenderbuffers(count: Int, out: IntArray) = generateNames(count, out, genRenderbuffersFn)

    override fun deleteRenderbuffers(count: Int, names: IntArray) = deleteNames(count, names, deleteRenderbuffersFn)

    override fun bindRenderbuffer(target: Int, renderbuffer: Int) =
        bindRenderbufferFn(target.toUInt(), renderbuffer.toUInt())

    override fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int) =
        renderbufferStorageFn(target.toUInt(), internalFormat.toUInt(), width, height)

    override fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    ) = blitFramebufferFn(
        sourceX0, sourceY0, sourceX1, sourceY1,
        destinationX0, destinationY0, destinationX1, destinationY1,
        mask.toUInt(), filter.toUInt(),
    )

    override fun drawBuffers(count: Int, buffers: IntArray) {
        if (count == 0) return
        memScoped {
            val buffer = allocArray<UIntVar>(count)
            for (index in 0 until count) buffer[index] = buffers[index].toUInt()
            drawBuffersFn(count, buffer)
        }
    }

    override fun readBuffer(mode: Int) = readBufferFn(mode.toUInt())

    // ===== Textures / samplers =====

    override fun genTextures(count: Int, out: IntArray) = generateNames(count, out, genTexturesFn)

    override fun deleteTextures(count: Int, names: IntArray) = deleteNames(count, names, deleteTexturesFn)

    override fun bindTexture(target: Int, texture: Int) = bindTextureFn(target.toUInt(), texture.toUInt())

    override fun activeTexture(unit: Int) = activeTextureFn(unit.toUInt())

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        if (pixels == null || pixels.isEmpty()) {
            texImage2DFn(
                target.toUInt(), level, internalFormat, width, height,
                border, format.toUInt(), type.toUInt(), null,
            )
            return
        }
        pixels.usePinned { pinned ->
            texImage2DFn(
                target.toUInt(), level, internalFormat, width, height,
                border, format.toUInt(), type.toUInt(), pinned.addressOf(0),
            )
        }
    }

    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) =
        texStorage2DFn(target.toUInt(), levels, internalFormat.toUInt(), width, height)

    override fun texParameteri(target: Int, pname: Int, value: Int) =
        texParameteriFn(target.toUInt(), pname.toUInt(), value)

    override fun generateMipmap(target: Int) = generateMipmapFn(target.toUInt())

    override fun genSamplers(count: Int, out: IntArray) = generateNames(count, out, genSamplersFn)

    override fun deleteSamplers(count: Int, names: IntArray) = deleteNames(count, names, deleteSamplersFn)

    override fun bindSampler(unit: Int, sampler: Int) = bindSamplerFn(unit.toUInt(), sampler.toUInt())

    override fun samplerParameteri(sampler: Int, pname: Int, value: Int) =
        samplerParameteriFn(sampler.toUInt(), pname.toUInt(), value)

    override fun pixelStorei(pname: Int, value: Int) = pixelStoreiFn(pname.toUInt(), value)

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray) {
        require(out.isNotEmpty()) { "a pixel read needs a destination" }
        out.usePinned { pinned ->
            readPixelsFn(x, y, width, height, format.toUInt(), type.toUInt(), pinned.addressOf(0))
        }
    }

    // ===== Buffers / vertex arrays =====

    override fun genBuffers(count: Int, out: IntArray) = generateNames(count, out, genBuffersFn)

    override fun deleteBuffers(count: Int, names: IntArray) = deleteNames(count, names, deleteBuffersFn)

    override fun bindBuffer(target: Int, buffer: Int) = bindBufferFn(target.toUInt(), buffer.toUInt())

    override fun bindBufferBase(target: Int, index: Int, buffer: Int) =
        bindBufferBaseFn(target.toUInt(), index.toUInt(), buffer.toUInt())

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        if (data == null || data.isEmpty()) {
            bufferDataFn(target.toUInt(), size.toLong(), null, usage.toUInt())
            return
        }
        data.usePinned { pinned ->
            bufferDataFn(target.toUInt(), size.toLong(), pinned.addressOf(0), usage.toUInt())
        }
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray) {
        if (data.isEmpty()) return
        data.usePinned { pinned ->
            bufferSubDataFn(target.toUInt(), offset.toLong(), size.toLong(), pinned.addressOf(0))
        }
    }

    override fun genVertexArrays(count: Int, out: IntArray) = generateNames(count, out, genVertexArraysFn)

    override fun deleteVertexArrays(count: Int, names: IntArray) = deleteNames(count, names, deleteVertexArraysFn)

    override fun bindVertexArray(array: Int) = bindVertexArrayFn(array.toUInt())

    override fun enableVertexAttribArray(index: Int) = enableVertexAttribArrayFn(index.toUInt())

    override fun disableVertexAttribArray(index: Int) = disableVertexAttribArrayFn(index.toUInt())

    override fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    ) = vertexAttribPointerFn(
        index.toUInt(), size, type.toUInt(), normalized.toGlBoolean(), stride, offset.toLong().toCPointer<COpaque>(),
    )

    // ===== Shaders / programs =====

    override fun createShader(type: Int): Int = createShaderFn(type.toUInt()).toInt()

    override fun deleteShader(shader: Int) = deleteShaderFn(shader.toUInt())

    override fun shaderSource(shader: Int, source: String) {
        memScoped {
            val sources = allocArray<CPointerVar<ByteVar>>(1)
            sources[0] = source.cstr.ptr
            shaderSourceFn(shader.toUInt(), 1, sources, null)
        }
    }

    override fun compileShader(shader: Int) = compileShaderFn(shader.toUInt())

    override fun getShaderiv(shader: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a shader query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            getShaderivFn(shader.toUInt(), pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getShaderInfoLog(shader: Int): String = memScoped {
        val lengthVar = alloc<IntVar> { value = 0 }
        getShaderivFn(shader.toUInt(), GL_INFO_LOG_LENGTH.toUInt(), lengthVar.ptr)
        val length = lengthVar.value
        if (length <= 0) {
            ""
        } else {
            val buffer = allocArray<ByteVar>(length)
            getShaderInfoLogFn(shader.toUInt(), length, null, buffer)
            buffer.toKString()
        }
    }

    override fun createProgram(): Int = createProgramFn().toInt()

    override fun deleteProgram(program: Int) = deleteProgramFn(program.toUInt())

    override fun attachShader(program: Int, shader: Int) = attachShaderFn(program.toUInt(), shader.toUInt())

    override fun linkProgram(program: Int) = linkProgramFn(program.toUInt())

    override fun getProgramiv(program: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a program query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            getProgramivFn(program.toUInt(), pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getProgramInfoLog(program: Int): String = memScoped {
        val lengthVar = alloc<IntVar> { value = 0 }
        getProgramivFn(program.toUInt(), GL_INFO_LOG_LENGTH.toUInt(), lengthVar.ptr)
        val length = lengthVar.value
        if (length <= 0) {
            ""
        } else {
            val buffer = allocArray<ByteVar>(length)
            getProgramInfoLogFn(program.toUInt(), length, null, buffer)
            buffer.toKString()
        }
    }

    override fun useProgram(program: Int) = useProgramFn(program.toUInt())

    override fun getAttribLocation(program: Int, name: String): Int = memScoped {
        getAttribLocationFn(program.toUInt(), name.cstr.ptr)
    }

    override fun getUniformLocation(program: Int, name: String): Int = memScoped {
        getUniformLocationFn(program.toUInt(), name.cstr.ptr)
    }

    override fun uniform1i(location: Int, value: Int) = uniform1iFn(location, value)

    override fun uniform1f(location: Int, value: Float) = uniform1fFn(location, value)

    override fun uniform2f(location: Int, x: Float, y: Float) = uniform2fFn(location, x, y)

    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = uniform3fFn(location, x, y, z)

    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        uniform4fFn(location, x, y, z, w)

    override fun uniform1ui(location: Int, value: Int) = uniform1uiFn(location, value.toUInt())

    override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray) {
        if (value.isEmpty()) return
        value.usePinned { pinned ->
            uniformMatrix4fvFn(location, count, transpose.toGlBoolean(), pinned.addressOf(0))
        }
    }

    override fun getUniformBlockIndex(program: Int, name: String): Int = memScoped {
        getUniformBlockIndexFn(program.toUInt(), name.cstr.ptr).toInt()
    }

    override fun uniformBlockBinding(program: Int, blockIndex: Int, bindingPoint: Int) =
        uniformBlockBindingFn(program.toUInt(), blockIndex.toUInt(), bindingPoint.toUInt())

    // ===== Pipeline state / draw =====

    override fun enable(cap: Int) = enableFn(cap.toUInt())

    override fun disable(cap: Int) = disableFn(cap.toUInt())

    override fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int) =
        blendFuncSeparateFn(
            sourceRgb.toUInt(), destinationRgb.toUInt(), sourceAlpha.toUInt(), destinationAlpha.toUInt(),
        )

    override fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int) =
        blendEquationSeparateFn(modeRgb.toUInt(), modeAlpha.toUInt())

    override fun blendColor(red: Float, green: Float, blue: Float, alpha: Float) =
        blendColorFn(red, green, blue, alpha)

    override fun depthFunc(function: Int) = depthFuncFn(function.toUInt())

    override fun depthMask(enabled: Boolean) = depthMaskFn(enabled.toGlBoolean())

    override fun depthRangef(near: Float, far: Float) = depthRangefFn(near, far)

    override fun cullFace(mode: Int) = cullFaceFn(mode.toUInt())

    override fun frontFace(mode: Int) = frontFaceFn(mode.toUInt())

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = viewportFn(x, y, width, height)

    override fun scissor(x: Int, y: Int, width: Int, height: Int) = scissorFn(x, y, width, height)

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) =
        colorMaskFn(red.toGlBoolean(), green.toGlBoolean(), blue.toGlBoolean(), alpha.toGlBoolean())

    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) =
        clearColorFn(red, green, blue, alpha)

    override fun clearDepthf(depth: Float) = clearDepthfFn(depth)

    override fun clear(mask: Int) = clearFn(mask.toUInt())

    override fun drawArrays(mode: Int, first: Int, count: Int) = drawArraysFn(mode.toUInt(), first, count)

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) =
        drawElementsFn(mode.toUInt(), count, type.toUInt(), offset.toLong().toCPointer<COpaque>())

    override fun finish() = finishFn()

    // ===== Shared name-array helpers =====

    private fun generateNames(
        count: Int,
        out: IntArray,
        fn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>>,
    ) {
        require(out.size >= count) { "a name query needs room for $count names" }
        if (count == 0) return
        memScoped {
            val buffer = allocArray<UIntVar>(count)
            fn(count, buffer)
            for (index in 0 until count) out[index] = buffer[index].toInt()
        }
    }

    private fun deleteNames(
        count: Int,
        names: IntArray,
        fn: CPointer<CFunction<(Int, CPointer<UIntVar>?) -> Unit>>,
    ) {
        if (count == 0) return
        memScoped {
            val buffer = allocArray<UIntVar>(count)
            for (index in 0 until count) buffer[index] = names[index].toUInt()
            fn(count, buffer)
        }
    }
}
