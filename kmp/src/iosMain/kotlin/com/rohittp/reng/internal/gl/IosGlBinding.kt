@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.gles3.glActiveTexture
import platform.gles3.glAttachShader
import platform.gles3.glBindBuffer
import platform.gles3.glBindBufferBase
import platform.gles3.glBindFramebuffer
import platform.gles3.glBindRenderbuffer
import platform.gles3.glBindSampler
import platform.gles3.glBindTexture
import platform.gles3.glBindVertexArray
import platform.gles3.glBlendColor
import platform.gles3.glBlendEquationSeparate
import platform.gles3.glBlendFuncSeparate
import platform.gles3.glBlitFramebuffer
import platform.gles3.glBufferData
import platform.gles3.glBufferSubData
import platform.gles3.glCheckFramebufferStatus
import platform.gles3.glClear
import platform.gles3.glClearColor
import platform.gles3.glClearDepthf
import platform.gles3.glColorMask
import platform.gles3.glCompileShader
import platform.gles3.glCreateProgram
import platform.gles3.glCreateShader
import platform.gles3.glCullFace
import platform.gles3.glDeleteBuffers
import platform.gles3.glDeleteFramebuffers
import platform.gles3.glDeleteProgram
import platform.gles3.glDeleteRenderbuffers
import platform.gles3.glDeleteSamplers
import platform.gles3.glDeleteShader
import platform.gles3.glDeleteTextures
import platform.gles3.glDeleteVertexArrays
import platform.gles3.glDepthFunc
import platform.gles3.glDepthMask
import platform.gles3.glDepthRangef
import platform.gles3.glDisable
import platform.gles3.glDisableVertexAttribArray
import platform.gles3.glDrawArrays
import platform.gles3.glDrawBuffers
import platform.gles3.glDrawElements
import platform.gles3.glEnable
import platform.gles3.glEnableVertexAttribArray
import platform.gles3.glFinish
import platform.gles3.glFramebufferRenderbuffer
import platform.gles3.glFramebufferTexture2D
import platform.gles3.glFrontFace
import platform.gles3.glGenBuffers
import platform.gles3.glGenFramebuffers
import platform.gles3.glGenRenderbuffers
import platform.gles3.glGenSamplers
import platform.gles3.glGenVertexArrays
import platform.gles3.glGenerateMipmap
import platform.gles3.glGenTextures
import platform.gles3.glGetAttribLocation
import platform.gles3.glGetBooleanv
import platform.gles3.glGetError
import platform.gles3.glGetFloatv
import platform.gles3.glGetIntegerv
import platform.gles3.glGetIntegeri_v
import platform.gles3.glGetProgramInfoLog
import platform.gles3.glGetProgramiv
import platform.gles3.glGetShaderInfoLog
import platform.gles3.glGetShaderiv
import platform.gles3.glGetString
import platform.gles3.glGetStringi
import platform.gles3.glGetUniformBlockIndex
import platform.gles3.glGetUniformLocation
import platform.gles3.glIsEnabled
import platform.gles3.glIsFramebuffer
import platform.gles3.glLinkProgram
import platform.gles3.glPixelStorei
import platform.gles3.glReadBuffer
import platform.gles3.glReadPixels
import platform.gles3.glRenderbufferStorage
import platform.gles3.glSamplerParameteri
import platform.gles3.glScissor
import platform.gles3.glShaderSource
import platform.gles3.glTexImage2D
import platform.gles3.glTexParameteri
import platform.gles3.glTexStorage2D
import platform.gles3.glUniform1f
import platform.gles3.glUniform1i
import platform.gles3.glUniform1ui
import platform.gles3.glUniform2f
import platform.gles3.glUniform3f
import platform.gles3.glUniform4f
import platform.gles3.glUniformBlockBinding
import platform.gles3.glUniformMatrix4fv
import platform.gles3.glUseProgram
import platform.gles3.glVertexAttribPointer
import platform.gles3.glViewport

internal object IosGlBinding : GlBinding {
    override fun getError(): Int = glGetError().toInt()

    override fun getString(name: Int): String? =
        glGetString(name.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getStringi(name: Int, index: Int): String? =
        glGetStringi(name.toUInt(), index.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetIntegerv(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getFloatv(pname: Int, out: FloatArray) {
        require(out.isNotEmpty()) { "a float query needs a destination" }
        memScoped {
            val buffer = allocArray<FloatVar>(out.size)
            glGetFloatv(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        memScoped {
            val buffer = allocArray<UByteVar>(out.size)
            glGetBooleanv(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index].toInt() != 0
        }
    }

    override fun isEnabled(cap: Int): Boolean = glIsEnabled(cap.toUInt()).toInt() != 0

    override fun getIntegeri_v(pname: Int, index: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an indexed integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetIntegeri_v(pname.toUInt(), index.toUInt(), buffer)
            for (i in out.indices) out[i] = buffer[i]
        }
    }

    override fun genFramebuffers(count: Int, out: IntArray) = memScoped {
        generateNames(count, out, "glGenFramebuffers") { n, buf -> glGenFramebuffers(n, buf) }
    }

    override fun deleteFramebuffers(count: Int, names: IntArray) = memScoped {
        deleteNames(count, names, "glDeleteFramebuffers") { n, buf -> glDeleteFramebuffers(n, buf) }
    }

    override fun bindFramebuffer(target: Int, framebuffer: Int) {
        glBindFramebuffer(target.toUInt(), framebuffer.toUInt())
    }

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) {
        glFramebufferTexture2D(target.toUInt(), attachment.toUInt(), textureTarget.toUInt(), texture.toUInt(), level)
    }

    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) {
        glFramebufferRenderbuffer(target.toUInt(), attachment.toUInt(), renderbufferTarget.toUInt(), renderbuffer.toUInt())
    }

    override fun checkFramebufferStatus(target: Int): Int = glCheckFramebufferStatus(target.toUInt()).toInt()

    override fun isFramebuffer(framebuffer: Int): Boolean = glIsFramebuffer(framebuffer.toUInt()).toInt() != 0

    override fun genRenderbuffers(count: Int, out: IntArray) = memScoped {
        generateNames(count, out, "glGenRenderbuffers") { n, buf -> glGenRenderbuffers(n, buf) }
    }

    override fun deleteRenderbuffers(count: Int, names: IntArray) = memScoped {
        deleteNames(count, names, "glDeleteRenderbuffers") { n, buf -> glDeleteRenderbuffers(n, buf) }
    }

    override fun bindRenderbuffer(target: Int, renderbuffer: Int) {
        glBindRenderbuffer(target.toUInt(), renderbuffer.toUInt())
    }

    override fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int) {
        glRenderbufferStorage(target.toUInt(), internalFormat.toUInt(), width, height)
    }

    override fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    ) {
        glBlitFramebuffer(
            sourceX0, sourceY0, sourceX1, sourceY1,
            destinationX0, destinationY0, destinationX1, destinationY1,
            mask.toUInt(), filter.toUInt(),
        )
    }

    override fun drawBuffers(count: Int, buffers: IntArray) {
        require(buffers.size >= count) { "glDrawBuffers was given fewer buffers than $count" }
        if (count == 0) return
        memScoped { glDrawBuffers(count, unsignedNames(buffers, count)) }
    }

    override fun readBuffer(mode: Int) {
        glReadBuffer(mode.toUInt())
    }

    override fun genTextures(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenTextures needs room for $count names" }
        if (count == 0) return
        memScoped {
            val buffer = allocArray<UIntVar>(count)
            glGenTextures(count, buffer)
            for (index in 0 until count) out[index] = buffer[index].toInt()
        }
    }

    override fun deleteTextures(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteTextures was given fewer names than $count" }
        if (count == 0) return
        memScoped { glDeleteTextures(count, unsignedNames(names, count)) }
    }

    override fun bindTexture(target: Int, texture: Int) {
        glBindTexture(target.toUInt(), texture.toUInt())
    }

    override fun activeTexture(unit: Int) {
        glActiveTexture(unit.toUInt())
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        if (pixels == null || pixels.isEmpty()) {
            glTexImage2D(
                target.toUInt(), level, internalFormat, width, height,
                border, format.toUInt(), type.toUInt(), null,
            )
            return
        }
        pixels.usePinned { pinned ->
            glTexImage2D(
                target.toUInt(), level, internalFormat, width, height,
                border, format.toUInt(), type.toUInt(), pinned.addressOf(0),
            )
        }
    }

    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) {
        glTexStorage2D(target.toUInt(), levels, internalFormat.toUInt(), width, height)
    }

    override fun texParameteri(target: Int, pname: Int, value: Int) {
        glTexParameteri(target.toUInt(), pname.toUInt(), value)
    }

    override fun generateMipmap(target: Int) {
        glGenerateMipmap(target.toUInt())
    }

    override fun genSamplers(count: Int, out: IntArray) = memScoped {
        generateNames(count, out, "glGenSamplers") { n, buf -> glGenSamplers(n, buf) }
    }

    override fun deleteSamplers(count: Int, names: IntArray) = memScoped {
        deleteNames(count, names, "glDeleteSamplers") { n, buf -> glDeleteSamplers(n, buf) }
    }

    override fun bindSampler(unit: Int, sampler: Int) {
        glBindSampler(unit.toUInt(), sampler.toUInt())
    }

    override fun samplerParameteri(sampler: Int, pname: Int, value: Int) {
        glSamplerParameteri(sampler.toUInt(), pname.toUInt(), value)
    }

    override fun pixelStorei(pname: Int, value: Int) {
        glPixelStorei(pname.toUInt(), value)
    }

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray) {
        require(out.isNotEmpty()) { "a pixel read needs a destination" }
        out.usePinned { pinned ->
            glReadPixels(x, y, width, height, format.toUInt(), type.toUInt(), pinned.addressOf(0))
        }
    }

    override fun genBuffers(count: Int, out: IntArray) = memScoped {
        generateNames(count, out, "glGenBuffers") { n, buf -> glGenBuffers(n, buf) }
    }

    override fun deleteBuffers(count: Int, names: IntArray) = memScoped {
        deleteNames(count, names, "glDeleteBuffers") { n, buf -> glDeleteBuffers(n, buf) }
    }

    override fun bindBuffer(target: Int, buffer: Int) {
        glBindBuffer(target.toUInt(), buffer.toUInt())
    }

    override fun bindBufferBase(target: Int, index: Int, buffer: Int) {
        glBindBufferBase(target.toUInt(), index.toUInt(), buffer.toUInt())
    }

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        if (data == null || data.isEmpty()) {
            glBufferData(target.toUInt(), size.toLong(), null, usage.toUInt())
            return
        }
        data.usePinned { pinned ->
            glBufferData(target.toUInt(), size.toLong(), pinned.addressOf(0), usage.toUInt())
        }
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray) {
        if (data.isEmpty()) return
        data.usePinned { pinned ->
            glBufferSubData(target.toUInt(), offset.toLong(), size.toLong(), pinned.addressOf(0))
        }
    }

    override fun genVertexArrays(count: Int, out: IntArray) = memScoped {
        generateNames(count, out, "glGenVertexArrays") { n, buf -> glGenVertexArrays(n, buf) }
    }

    override fun deleteVertexArrays(count: Int, names: IntArray) = memScoped {
        deleteNames(count, names, "glDeleteVertexArrays") { n, buf -> glDeleteVertexArrays(n, buf) }
    }

    override fun bindVertexArray(array: Int) {
        glBindVertexArray(array.toUInt())
    }

    override fun enableVertexAttribArray(index: Int) {
        glEnableVertexAttribArray(index.toUInt())
    }

    override fun disableVertexAttribArray(index: Int) {
        glDisableVertexAttribArray(index.toUInt())
    }

    override fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    ) {
        glVertexAttribPointer(
            index.toUInt(), size, type.toUInt(), normalized.toGlBoolean(), stride,
            offset.toLong().toCPointer<ByteVar>(),
        )
    }

    override fun createShader(type: Int): Int = glCreateShader(type.toUInt()).toInt()

    override fun deleteShader(shader: Int) {
        glDeleteShader(shader.toUInt())
    }

    override fun shaderSource(shader: Int, source: String) {
        memScoped {
            val sources = allocArray<CPointerVar<ByteVar>>(1)
            sources[0] = source.cstr.ptr
            glShaderSource(shader.toUInt(), 1, sources, null)
        }
    }

    override fun compileShader(shader: Int) {
        glCompileShader(shader.toUInt())
    }

    override fun getShaderiv(shader: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "glGetShaderiv needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetShaderiv(shader.toUInt(), pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getShaderInfoLog(shader: Int): String = memScoped {
        val length = alloc<IntVar> { value = 0 }
        glGetShaderiv(shader.toUInt(), GL_INFO_LOG_LENGTH.toUInt(), length.ptr)
        val size = length.value
        if (size <= 0) return@memScoped ""
        val buffer = allocArray<ByteVar>(size)
        glGetShaderInfoLog(shader.toUInt(), size, null, buffer)
        buffer.toKString()
    }

    override fun createProgram(): Int = glCreateProgram().toInt()

    override fun deleteProgram(program: Int) {
        glDeleteProgram(program.toUInt())
    }

    override fun attachShader(program: Int, shader: Int) {
        glAttachShader(program.toUInt(), shader.toUInt())
    }

    override fun linkProgram(program: Int) {
        glLinkProgram(program.toUInt())
    }

    override fun getProgramiv(program: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "glGetProgramiv needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetProgramiv(program.toUInt(), pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getProgramInfoLog(program: Int): String = memScoped {
        val length = alloc<IntVar> { value = 0 }
        glGetProgramiv(program.toUInt(), GL_INFO_LOG_LENGTH.toUInt(), length.ptr)
        val size = length.value
        if (size <= 0) return@memScoped ""
        val buffer = allocArray<ByteVar>(size)
        glGetProgramInfoLog(program.toUInt(), size, null, buffer)
        buffer.toKString()
    }

    override fun useProgram(program: Int) {
        glUseProgram(program.toUInt())
    }

    override fun getAttribLocation(program: Int, name: String): Int =
        glGetAttribLocation(program.toUInt(), name)

    override fun getUniformLocation(program: Int, name: String): Int =
        glGetUniformLocation(program.toUInt(), name)

    override fun uniform1i(location: Int, value: Int) {
        glUniform1i(location, value)
    }

    override fun uniform1f(location: Int, value: Float) {
        glUniform1f(location, value)
    }

    override fun uniform2f(location: Int, x: Float, y: Float) {
        glUniform2f(location, x, y)
    }

    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) {
        glUniform3f(location, x, y, z)
    }

    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) {
        glUniform4f(location, x, y, z, w)
    }

    override fun uniform1ui(location: Int, value: Int) {
        glUniform1ui(location, value.toUInt())
    }

    override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray) {
        if (value.isEmpty()) return
        value.usePinned { pinned ->
            glUniformMatrix4fv(location, count, transpose.toGlBoolean(), pinned.addressOf(0))
        }
    }

    override fun getUniformBlockIndex(program: Int, name: String): Int =
        glGetUniformBlockIndex(program.toUInt(), name).toInt()

    override fun uniformBlockBinding(program: Int, blockIndex: Int, bindingPoint: Int) {
        glUniformBlockBinding(program.toUInt(), blockIndex.toUInt(), bindingPoint.toUInt())
    }

    override fun enable(cap: Int) {
        glEnable(cap.toUInt())
    }

    override fun disable(cap: Int) {
        glDisable(cap.toUInt())
    }

    override fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int) {
        glBlendFuncSeparate(sourceRgb.toUInt(), destinationRgb.toUInt(), sourceAlpha.toUInt(), destinationAlpha.toUInt())
    }

    override fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int) {
        glBlendEquationSeparate(modeRgb.toUInt(), modeAlpha.toUInt())
    }

    override fun blendColor(red: Float, green: Float, blue: Float, alpha: Float) {
        glBlendColor(red, green, blue, alpha)
    }

    override fun depthFunc(function: Int) {
        glDepthFunc(function.toUInt())
    }

    override fun depthMask(enabled: Boolean) {
        glDepthMask(enabled.toGlBoolean())
    }

    override fun depthRangef(near: Float, far: Float) {
        glDepthRangef(near, far)
    }

    override fun cullFace(mode: Int) {
        glCullFace(mode.toUInt())
    }

    override fun frontFace(mode: Int) {
        glFrontFace(mode.toUInt())
    }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) {
        glViewport(x, y, width, height)
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        glScissor(x, y, width, height)
    }

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        glColorMask(red.toGlBoolean(), green.toGlBoolean(), blue.toGlBoolean(), alpha.toGlBoolean())
    }

    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        glClearColor(red, green, blue, alpha)
    }

    override fun clearDepthf(depth: Float) {
        glClearDepthf(depth)
    }

    override fun clear(mask: Int) {
        glClear(mask.toUInt())
    }

    override fun drawArrays(mode: Int, first: Int, count: Int) {
        glDrawArrays(mode.toUInt(), first, count)
    }

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        glDrawElements(mode.toUInt(), count, type.toUInt(), offset.toLong().toCPointer<ByteVar>())
    }

    override fun finish() {
        glFinish()
    }
}

private fun Boolean.toGlBoolean(): UByte = if (this) 1u else 0u

private fun MemScope.unsignedNames(names: IntArray, count: Int): CPointer<UIntVar> {
    val buffer = allocArray<UIntVar>(count)
    for (index in 0 until count) buffer[index] = names[index].toUInt()
    return buffer
}

private fun MemScope.generateNames(
    count: Int,
    out: IntArray,
    label: String,
    write: (Int, CPointer<UIntVar>) -> Unit,
) {
    require(out.size >= count) { "$label needs room for $count names" }
    if (count == 0) return
    val buffer = allocArray<UIntVar>(count)
    write(count, buffer)
    for (index in 0 until count) out[index] = buffer[index].toInt()
}

private fun MemScope.deleteNames(
    count: Int,
    names: IntArray,
    label: String,
    write: (Int, CPointer<UIntVar>) -> Unit,
) {
    require(names.size >= count) { "$label was given fewer names than $count" }
    if (count == 0) return
    write(count, unsignedNames(names, count))
}

internal actual fun openPlatformGlBinding(): GlBindingResult = GlBindingResult.Bound(IosGlBinding)
