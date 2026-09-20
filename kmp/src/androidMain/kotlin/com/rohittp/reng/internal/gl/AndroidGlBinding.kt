package com.rohittp.reng.internal.gl

import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * The Android binding of the internal GL seam, over `android.opengl.GLES30`.
 *
 * `GLES30 extends GLES20`, so one import reaches every entry point this seam names, including
 * the ES 3.0-only tokens ([GL_VERTEX_ARRAY_BINDING], [GL_SAMPLER_BINDING],
 * [GL_DRAW_FRAMEBUFFER_BINDING], [GL_READ_FRAMEBUFFER_BINDING], [GL_UNPACK_ROW_LENGTH],
 * [GL_NUM_EXTENSIONS], [GL_MAX_COLOR_ATTACHMENTS]) that state restore needs. The seam is typed
 * at Android's width, so this side is the most direct of the four platform bindings: every
 * array-taking overload gets an explicit `0` offset, and the four payload-carrying calls
 * ([bufferData], [bufferSubData], [texImage2D], [readPixels]) wrap their [ByteArray] in a
 * [java.nio.ByteBuffer]. No numeric conversion is needed anywhere else, because the seam and
 * `GLES30` already agree on `Int`, `Float`, and `Boolean`.
 */
internal object AndroidGlBinding : GlBinding {

    // ===== Queries =====

    override fun getError(): Int = GLES30.glGetError()

    override fun getString(name: Int): String? = GLES30.glGetString(name)

    override fun getStringi(name: Int, index: Int): String? = GLES30.glGetStringi(name, index)

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        GLES30.glGetIntegerv(pname, out, 0)
    }

    override fun getFloatv(pname: Int, out: FloatArray) {
        require(out.isNotEmpty()) { "a float query needs a destination" }
        GLES30.glGetFloatv(pname, out, 0)
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        GLES30.glGetBooleanv(pname, out, 0)
    }

    override fun isEnabled(cap: Int): Boolean = GLES30.glIsEnabled(cap)

    override fun getIntegeri_v(pname: Int, index: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an indexed integer query needs a destination" }
        GLES30.glGetIntegeri_v(pname, index, out, 0)
    }

    // ===== Framebuffers / renderbuffers =====

    override fun genFramebuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenFramebuffers needs room for $count names" }
        if (count == 0) return
        GLES30.glGenFramebuffers(count, out, 0)
    }

    override fun deleteFramebuffers(count: Int, names: IntArray) {
        if (count == 0) return
        GLES30.glDeleteFramebuffers(count, names, 0)
    }

    override fun bindFramebuffer(target: Int, framebuffer: Int) {
        GLES30.glBindFramebuffer(target, framebuffer)
    }

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) {
        GLES30.glFramebufferTexture2D(target, attachment, textureTarget, texture, level)
    }

    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) {
        GLES30.glFramebufferRenderbuffer(target, attachment, renderbufferTarget, renderbuffer)
    }

    override fun checkFramebufferStatus(target: Int): Int = GLES30.glCheckFramebufferStatus(target)

    override fun isFramebuffer(framebuffer: Int): Boolean = GLES30.glIsFramebuffer(framebuffer)

    override fun genRenderbuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenRenderbuffers needs room for $count names" }
        if (count == 0) return
        GLES30.glGenRenderbuffers(count, out, 0)
    }

    override fun deleteRenderbuffers(count: Int, names: IntArray) {
        if (count == 0) return
        GLES30.glDeleteRenderbuffers(count, names, 0)
    }

    override fun bindRenderbuffer(target: Int, renderbuffer: Int) {
        GLES30.glBindRenderbuffer(target, renderbuffer)
    }

    override fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int) {
        GLES30.glRenderbufferStorage(target, internalFormat, width, height)
    }

    override fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    ) {
        GLES30.glBlitFramebuffer(
            sourceX0, sourceY0, sourceX1, sourceY1,
            destinationX0, destinationY0, destinationX1, destinationY1,
            mask, filter,
        )
    }

    override fun drawBuffers(count: Int, buffers: IntArray) {
        if (count == 0) return
        GLES30.glDrawBuffers(count, buffers, 0)
    }

    override fun readBuffer(mode: Int) {
        GLES30.glReadBuffer(mode)
    }

    // ===== Textures / samplers =====

    override fun genTextures(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenTextures needs room for $count names" }
        if (count == 0) return
        GLES30.glGenTextures(count, out, 0)
    }

    override fun deleteTextures(count: Int, names: IntArray) {
        if (count == 0) return
        GLES30.glDeleteTextures(count, names, 0)
    }

    override fun bindTexture(target: Int, texture: Int) {
        GLES30.glBindTexture(target, texture)
    }

    override fun activeTexture(unit: Int) {
        GLES30.glActiveTexture(unit)
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        GLES30.glTexImage2D(
            target, level, internalFormat, width, height, border, format, type,
            pixels?.takeIf { it.isNotEmpty() }?.let(ByteBuffer::wrap),
        )
    }

    override fun texSubImage2D(
        target: Int, level: Int, xOffset: Int, yOffset: Int, width: Int, height: Int,
        format: Int, type: Int, pixels: ByteArray,
    ) {
        GLES30.glTexSubImage2D(
            target, level, xOffset, yOffset, width, height, format, type, ByteBuffer.wrap(pixels),
        )
    }

    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) {
        GLES30.glTexStorage2D(target, levels, internalFormat, width, height)
    }

    override fun texParameteri(target: Int, pname: Int, value: Int) {
        GLES30.glTexParameteri(target, pname, value)
    }

    override fun generateMipmap(target: Int) {
        GLES30.glGenerateMipmap(target)
    }

    override fun genSamplers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenSamplers needs room for $count names" }
        if (count == 0) return
        GLES30.glGenSamplers(count, out, 0)
    }

    override fun deleteSamplers(count: Int, names: IntArray) {
        if (count == 0) return
        GLES30.glDeleteSamplers(count, names, 0)
    }

    override fun bindSampler(unit: Int, sampler: Int) {
        GLES30.glBindSampler(unit, sampler)
    }

    override fun samplerParameteri(sampler: Int, pname: Int, value: Int) {
        GLES30.glSamplerParameteri(sampler, pname, value)
    }

    override fun pixelStorei(pname: Int, value: Int) {
        GLES30.glPixelStorei(pname, value)
    }

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray,
    ) {
        require(out.isNotEmpty()) { "a pixel read needs a destination" }
        GLES30.glReadPixels(x, y, width, height, format, type, ByteBuffer.wrap(out))
    }

    // ===== Buffers / vertex arrays =====

    override fun genBuffers(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenBuffers needs room for $count names" }
        if (count == 0) return
        GLES30.glGenBuffers(count, out, 0)
    }

    override fun deleteBuffers(count: Int, names: IntArray) {
        if (count == 0) return
        GLES30.glDeleteBuffers(count, names, 0)
    }

    override fun bindBuffer(target: Int, buffer: Int) {
        GLES30.glBindBuffer(target, buffer)
    }

    override fun bindBufferBase(target: Int, index: Int, buffer: Int) {
        GLES30.glBindBufferBase(target, index, buffer)
    }

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        GLES30.glBufferData(target, size, data?.takeIf { it.isNotEmpty() }?.let(ByteBuffer::wrap), usage)
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray) {
        if (data.isEmpty()) return
        GLES30.glBufferSubData(target, offset, size, ByteBuffer.wrap(data))
    }

    override fun genVertexArrays(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenVertexArrays needs room for $count names" }
        if (count == 0) return
        GLES30.glGenVertexArrays(count, out, 0)
    }

    override fun deleteVertexArrays(count: Int, names: IntArray) {
        if (count == 0) return
        GLES30.glDeleteVertexArrays(count, names, 0)
    }

    override fun bindVertexArray(array: Int) {
        GLES30.glBindVertexArray(array)
    }

    override fun enableVertexAttribArray(index: Int) {
        GLES30.glEnableVertexAttribArray(index)
    }

    override fun disableVertexAttribArray(index: Int) {
        GLES30.glDisableVertexAttribArray(index)
    }

    override fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    ) {
        GLES30.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    }

    // ===== Shaders / programs =====

    override fun createShader(type: Int): Int = GLES30.glCreateShader(type)

    override fun deleteShader(shader: Int) {
        GLES30.glDeleteShader(shader)
    }

    override fun shaderSource(shader: Int, source: String) {
        GLES30.glShaderSource(shader, source)
    }

    override fun compileShader(shader: Int) {
        GLES30.glCompileShader(shader)
    }

    override fun getShaderiv(shader: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a shader query needs a destination" }
        GLES30.glGetShaderiv(shader, pname, out, 0)
    }

    override fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)

    override fun createProgram(): Int = GLES30.glCreateProgram()

    override fun deleteProgram(program: Int) {
        GLES30.glDeleteProgram(program)
    }

    override fun attachShader(program: Int, shader: Int) {
        GLES30.glAttachShader(program, shader)
    }

    override fun linkProgram(program: Int) {
        GLES30.glLinkProgram(program)
    }

    override fun getProgramiv(program: Int, pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "a program query needs a destination" }
        GLES30.glGetProgramiv(program, pname, out, 0)
    }

    override fun getProgramInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program)

    override fun useProgram(program: Int) {
        GLES30.glUseProgram(program)
    }

    override fun getAttribLocation(program: Int, name: String): Int = GLES30.glGetAttribLocation(program, name)

    override fun getUniformLocation(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)

    override fun uniform1i(location: Int, value: Int) {
        GLES30.glUniform1i(location, value)
    }

    override fun uniform1f(location: Int, value: Float) {
        GLES30.glUniform1f(location, value)
    }

    override fun uniform2f(location: Int, x: Float, y: Float) {
        GLES30.glUniform2f(location, x, y)
    }

    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(location, x, y, z)
    }

    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) {
        GLES30.glUniform4f(location, x, y, z, w)
    }

    override fun uniform1ui(location: Int, value: Int) {
        GLES30.glUniform1ui(location, value)
    }

    override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray) {
        if (value.isEmpty()) return
        GLES30.glUniformMatrix4fv(location, count, transpose, value, 0)
    }

    override fun getUniformBlockIndex(program: Int, name: String): Int =
        GLES30.glGetUniformBlockIndex(program, name)

    override fun uniformBlockBinding(program: Int, blockIndex: Int, bindingPoint: Int) {
        GLES30.glUniformBlockBinding(program, blockIndex, bindingPoint)
    }

    // ===== Pipeline state / draw =====

    override fun enable(cap: Int) {
        GLES30.glEnable(cap)
    }

    override fun disable(cap: Int) {
        GLES30.glDisable(cap)
    }

    override fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int) {
        GLES30.glBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha)
    }

    override fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int) {
        GLES30.glBlendEquationSeparate(modeRgb, modeAlpha)
    }

    override fun blendColor(red: Float, green: Float, blue: Float, alpha: Float) {
        GLES30.glBlendColor(red, green, blue, alpha)
    }

    override fun depthFunc(function: Int) {
        GLES30.glDepthFunc(function)
    }

    override fun depthMask(enabled: Boolean) {
        GLES30.glDepthMask(enabled)
    }

    override fun depthRangef(near: Float, far: Float) {
        GLES30.glDepthRangef(near, far)
    }

    override fun cullFace(mode: Int) {
        GLES30.glCullFace(mode)
    }

    override fun frontFace(mode: Int) {
        GLES30.glFrontFace(mode)
    }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) {
        GLES30.glViewport(x, y, width, height)
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        GLES30.glScissor(x, y, width, height)
    }

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        GLES30.glColorMask(red, green, blue, alpha)
    }

    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        GLES30.glClearColor(red, green, blue, alpha)
    }

    override fun clearDepthf(depth: Float) {
        GLES30.glClearDepthf(depth)
    }

    override fun clear(mask: Int) {
        GLES30.glClear(mask)
    }

    override fun drawArrays(mode: Int, first: Int, count: Int) {
        GLES30.glDrawArrays(mode, first, count)
    }

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        GLES30.glDrawElements(mode, count, type, offset)
    }

    override fun finish() {
        GLES30.glFinish()
    }
}

internal actual fun openPlatformGlBinding(): GlBindingResult = GlBindingResult.Bound(AndroidGlBinding)
