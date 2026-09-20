@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.OpenGL3.glActiveTexture
import platform.OpenGL3.glAttachShader
import platform.OpenGL3.glBindBuffer
import platform.OpenGL3.glBindBufferBase
import platform.OpenGL3.glBindFramebuffer
import platform.OpenGL3.glBindRenderbuffer
import platform.OpenGL3.glBindSampler
import platform.OpenGL3.glBindTexture
import platform.OpenGL3.glBindVertexArray
import platform.OpenGL3.glBlendColor
import platform.OpenGL3.glBlendEquationSeparate
import platform.OpenGL3.glBlendFuncSeparate
import platform.OpenGL3.glBlitFramebuffer
import platform.OpenGL3.glBufferData
import platform.OpenGL3.glBufferSubData
import platform.OpenGL3.glCheckFramebufferStatus
import platform.OpenGL3.glClear
import platform.OpenGL3.glClearColor
import platform.OpenGL3.glClearDepthf
import platform.OpenGL3.glColorMask
import platform.OpenGL3.glCompileShader
import platform.OpenGL3.glCreateProgram
import platform.OpenGL3.glCreateShader
import platform.OpenGL3.glCullFace
import platform.OpenGL3.glDeleteBuffers
import platform.OpenGL3.glDeleteFramebuffers
import platform.OpenGL3.glDeleteProgram
import platform.OpenGL3.glDeleteRenderbuffers
import platform.OpenGL3.glDeleteSamplers
import platform.OpenGL3.glDeleteShader
import platform.OpenGL3.glDeleteTextures
import platform.OpenGL3.glDeleteVertexArrays
import platform.OpenGL3.glDepthFunc
import platform.OpenGL3.glDepthMask
import platform.OpenGL3.glDepthRangef
import platform.OpenGL3.glDisable
import platform.OpenGL3.glDisableVertexAttribArray
import platform.OpenGL3.glDrawArrays
import platform.OpenGL3.glDrawBuffers
import platform.OpenGL3.glDrawElements
import platform.OpenGL3.glEnable
import platform.OpenGL3.glEnableVertexAttribArray
import platform.OpenGL3.glFinish
import platform.OpenGL3.glFramebufferRenderbuffer
import platform.OpenGL3.glFramebufferTexture2D
import platform.OpenGL3.glFrontFace
import platform.OpenGL3.glGenBuffers
import platform.OpenGL3.glGenFramebuffers
import platform.OpenGL3.glGenRenderbuffers
import platform.OpenGL3.glGenSamplers
import platform.OpenGL3.glGenTextures
import platform.OpenGL3.glGenVertexArrays
import platform.OpenGL3.glGenerateMipmap
import platform.OpenGL3.glGetAttribLocation
import platform.OpenGL3.glGetBooleanv
import platform.OpenGL3.glGetError
import platform.OpenGL3.glGetFloatv
import platform.OpenGL3.glGetIntegerv
import platform.OpenGL3.glGetIntegeri_v
import platform.OpenGL3.glGetProgramInfoLog
import platform.OpenGL3.glGetProgramiv
import platform.OpenGL3.glGetShaderInfoLog
import platform.OpenGL3.glGetShaderiv
import platform.OpenGL3.glGetString
import platform.OpenGL3.glGetStringi
import platform.OpenGL3.glGetUniformBlockIndex
import platform.OpenGL3.glGetUniformLocation
import platform.OpenGL3.glIsEnabled
import platform.OpenGL3.glIsFramebuffer
import platform.OpenGL3.glLinkProgram
import platform.OpenGL3.glPixelStorei
import platform.OpenGL3.glReadBuffer
import platform.OpenGL3.glReadPixels
import platform.OpenGL3.glRenderbufferStorage
import platform.OpenGL3.glSamplerParameteri
import platform.OpenGL3.glScissor
import platform.OpenGL3.glShaderSource
import platform.OpenGL3.glTexImage2D
import platform.OpenGL3.glTexSubImage2D
import platform.OpenGL3.glTexParameteri
import platform.OpenGL3.glTexStorage2D
import platform.OpenGL3.glUniform1f
import platform.OpenGL3.glUniform1i
import platform.OpenGL3.glUniform1ui
import platform.OpenGL3.glUniform2f
import platform.OpenGL3.glUniform3f
import platform.OpenGL3.glUniform4f
import platform.OpenGL3.glUniformBlockBinding
import platform.OpenGL3.glUniformMatrix4fv
import platform.OpenGL3.glUseProgram
import platform.OpenGL3.glVertexAttribPointer
import platform.OpenGL3.glViewport

private fun Boolean.asGlBoolean(): UByte = if (this) 1u else 0u

private fun UByte.asBoolean(): Boolean = this != 0.toUByte()

/**
 * The macOS binding of the internal GL seam, over desktop `platform.OpenGL3`.
 *
 * `platform.OpenGL3` and `platform.gles3` declare byte-identical Kotlin signatures for every
 * entry point this seam names, so the marshalling below mirrors the iOS implementation exactly
 * except for the import package. This near-duplication is irreducible: Kotlin has no conditional
 * import, so a single shared file cannot host both packages.
 */
internal object MacosGlBinding : GlBinding {
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
            for (index in out.indices) out[index] = buffer[index].asBoolean()
        }
    }

    override fun isEnabled(cap: Int): Boolean = glIsEnabled(cap.toUInt()).asBoolean()

    override fun getIntegeri_v(pname: Int, index: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an indexed integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetIntegeri_v(pname.toUInt(), index.toUInt(), buffer)
            for (i in out.indices) out[i] = buffer[i]
        }
    }

    override fun genFramebuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenFramebuffers needs room for $count names" }
        if (count == 0) return
        out.usePinned { pinned -> glGenFramebuffers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
    }

    override fun deleteFramebuffers(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteFramebuffers needs room for $count names" }
        if (count == 0) return
        names.usePinned { pinned -> glDeleteFramebuffers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
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

    override fun isFramebuffer(framebuffer: Int): Boolean = glIsFramebuffer(framebuffer.toUInt()).asBoolean()

    override fun genRenderbuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenRenderbuffers needs room for $count names" }
        if (count == 0) return
        out.usePinned { pinned -> glGenRenderbuffers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
    }

    override fun deleteRenderbuffers(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteRenderbuffers needs room for $count names" }
        if (count == 0) return
        names.usePinned { pinned -> glDeleteRenderbuffers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
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
        require(buffers.size >= count) { "glDrawBuffers needs room for $count buffers" }
        if (count == 0) return
        buffers.usePinned { pinned -> glDrawBuffers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
    }

    override fun readBuffer(mode: Int) {
        glReadBuffer(mode.toUInt())
    }

    override fun genTextures(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenTextures needs room for $count names" }
        if (count == 0) return
        out.usePinned { pinned -> glGenTextures(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
    }

    override fun deleteTextures(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteTextures needs room for $count names" }
        if (count == 0) return
        names.usePinned { pinned -> glDeleteTextures(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
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
            glTexImage2D(target.toUInt(), level, internalFormat, width, height, border, format.toUInt(), type.toUInt(), null)
            return
        }
        pixels.usePinned { pinned ->
            glTexImage2D(
                target.toUInt(), level, internalFormat, width, height, border,
                format.toUInt(), type.toUInt(), pinned.addressOf(0),
            )
        }
    }

    override fun texSubImage2D(
        target: Int, level: Int, xOffset: Int, yOffset: Int, width: Int, height: Int,
        format: Int, type: Int, pixels: ByteArray,
    ) {
        require(pixels.isNotEmpty()) { "a texture sub-image needs pixels" }
        pixels.usePinned { pinned ->
            glTexSubImage2D(
                target.toUInt(), level, xOffset, yOffset, width, height,
                format.toUInt(), type.toUInt(), pinned.addressOf(0),
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

    override fun genSamplers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenSamplers needs room for $count names" }
        if (count == 0) return
        out.usePinned { pinned -> glGenSamplers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
    }

    override fun deleteSamplers(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteSamplers needs room for $count names" }
        if (count == 0) return
        names.usePinned { pinned -> glDeleteSamplers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
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
        out.usePinned { pinned -> glReadPixels(x, y, width, height, format.toUInt(), type.toUInt(), pinned.addressOf(0)) }
    }

    override fun genBuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenBuffers needs room for $count names" }
        if (count == 0) return
        out.usePinned { pinned -> glGenBuffers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
    }

    override fun deleteBuffers(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteBuffers needs room for $count names" }
        if (count == 0) return
        names.usePinned { pinned -> glDeleteBuffers(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
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
        data.usePinned { pinned -> glBufferData(target.toUInt(), size.toLong(), pinned.addressOf(0), usage.toUInt()) }
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray) {
        if (data.isEmpty()) return
        data.usePinned { pinned -> glBufferSubData(target.toUInt(), offset.toLong(), size.toLong(), pinned.addressOf(0)) }
    }

    override fun genVertexArrays(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenVertexArrays needs room for $count names" }
        if (count == 0) return
        out.usePinned { pinned -> glGenVertexArrays(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
    }

    override fun deleteVertexArrays(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteVertexArrays needs room for $count names" }
        if (count == 0) return
        names.usePinned { pinned -> glDeleteVertexArrays(count, pinned.addressOf(0).reinterpret<UIntVar>()) }
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
            index.toUInt(), size, type.toUInt(), normalized.asGlBoolean(), stride,
            offset.toLong().toCPointer<ByteVar>(),
        )
    }

    override fun createShader(type: Int): Int = glCreateShader(type.toUInt()).toInt()

    override fun deleteShader(shader: Int) {
        glDeleteShader(shader.toUInt())
    }

    override fun shaderSource(shader: Int, source: String) {
        memScoped {
            val sourcePointer = source.cstr.getPointer(this)
            val sourcePointers = allocArray<CPointerVar<ByteVar>>(1)
            sourcePointers[0] = sourcePointer
            glShaderSource(shader.toUInt(), 1, sourcePointers, null)
        }
    }

    override fun compileShader(shader: Int) {
        glCompileShader(shader.toUInt())
    }

    override fun getShaderiv(shader: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a shader query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetShaderiv(shader.toUInt(), pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getShaderInfoLog(shader: Int): String {
        val lengthHolder = IntArray(1)
        getShaderiv(shader, GL_INFO_LOG_LENGTH, lengthHolder)
        val logLength = lengthHolder[0]
        if (logLength <= 0) return ""
        return memScoped {
            val buffer = allocArray<ByteVar>(logLength)
            glGetShaderInfoLog(shader.toUInt(), logLength, null, buffer)
            buffer.toKString()
        }
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
        require(out.isNotEmpty()) { "a program query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetProgramiv(program.toUInt(), pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getProgramInfoLog(program: Int): String {
        val lengthHolder = IntArray(1)
        getProgramiv(program, GL_INFO_LOG_LENGTH, lengthHolder)
        val logLength = lengthHolder[0]
        if (logLength <= 0) return ""
        return memScoped {
            val buffer = allocArray<ByteVar>(logLength)
            glGetProgramInfoLog(program.toUInt(), logLength, null, buffer)
            buffer.toKString()
        }
    }

    override fun useProgram(program: Int) {
        glUseProgram(program.toUInt())
    }

    override fun getAttribLocation(program: Int, name: String): Int = glGetAttribLocation(program.toUInt(), name)

    override fun getUniformLocation(program: Int, name: String): Int = glGetUniformLocation(program.toUInt(), name)

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
        value.usePinned { pinned -> glUniformMatrix4fv(location, count, transpose.asGlBoolean(), pinned.addressOf(0)) }
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
        glDepthMask(enabled.asGlBoolean())
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
        glColorMask(red.asGlBoolean(), green.asGlBoolean(), blue.asGlBoolean(), alpha.asGlBoolean())
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

internal actual fun openPlatformGlBinding(): GlBindingResult = GlBindingResult.Bound(MacosGlBinding)
