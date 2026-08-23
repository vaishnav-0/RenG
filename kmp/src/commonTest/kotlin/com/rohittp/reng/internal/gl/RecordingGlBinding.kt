package com.rohittp.reng.internal.gl

/**
 * A programmable, non-native fake for [GlBinding] driven entirely from Kotlin state.
 *
 * Every call is appended to [log] as `"<methodName>(<args>)"`, with enum-typed arguments
 * rendered as uppercase hex; array/bulk-payload arguments (names, pixel data, vertex data,
 * matrices, shader source) are omitted from the log line itself since they carry no stable
 * string form, but every one of them is still recoverable from a dedicated backing field
 * ([deletedNames], [lastDrawBuffers], [bufferDataPayloads], [bufferSubDataPayloads],
 * [uniformMatrix4fvValues], [shaderSources], [pixels], [lastTexImageBytes]) so a test can assert on
 * exactly what was passed, not merely that something of a given size was passed. Query results are driven
 * by the public mutable fields below, which tests set up before exercising a binding
 * consumer. This class lives only in `commonTest` and is never part of production source.
 *
 * [indexedUniformBuffer] models exactly the one indexed target RenG's Restore Set captures —
 * `GL_UNIFORM_BUFFER_BINDING` at `RENG_JOINT_UNIFORM_BINDING_POINT` — keyed by binding index alone
 * rather than by `(pname, index)`, since that is the only indexed query this fake is ever asked to
 * answer. [getIntegeri_v] reads it and [bindBufferBase] writes it, the same way [integers] backs
 * [getIntegerv] and the various bind calls above it.
 */
internal class RecordingGlBinding : GlBinding {
    val log: MutableList<String> = mutableListOf()
    val integers: MutableMap<Int, IntArray> = mutableMapOf()
    val floats: MutableMap<Int, FloatArray> = mutableMapOf()
    val booleans: MutableMap<Int, BooleanArray> = mutableMapOf()
    val enabled: MutableMap<Int, Boolean> = mutableMapOf()
    val strings: MutableMap<Int, String?> = mutableMapOf()
    val indexedStrings: MutableList<String> = mutableListOf()
    var errorQueue: MutableList<Int> = mutableListOf()
    var compileStatus: Int = 1
    var linkStatus: Int = 1
    var framebufferStatus: Int = GL_FRAMEBUFFER_COMPLETE
    var shaderInfoLog: String = ""
    var programInfoLog: String = ""

    /**
     * Maps a declared shader name to its GL location. Empty by default, which matches a shader
     * declaring none of RenG's documented names: with nothing in this map, [getUniformLocation]
     * and [getAttribLocation] both return `-1` for every name queried, exactly like a real driver
     * returns for a name the compiled program never declared (ADR 0008's actual mechanism — a
     * negative location, not an exception). Set it with [withDeclaredNames]; [withNoDeclaredNames]
     * exists only so a test can say the default explicitly.
     */
    var declaredNames: Map<String, Int> = emptyMap()

    fun withNoDeclaredNames(): RecordingGlBinding = apply { declaredNames = emptyMap() }

    fun withDeclaredNames(vararg names: Pair<String, Int>): RecordingGlBinding = apply {
        declaredNames = names.toMap()
    }

    val shaderSources: MutableMap<Int, String> = mutableMapOf()
    private var nextName: Int = 1
    val deletedNames: MutableList<Int> = mutableListOf()
    var lastDrawBuffers: IntArray = IntArray(0)
    val bufferDataPayloads: MutableMap<Int, ByteArray?> = mutableMapOf()
    val bufferSubDataPayloads: MutableMap<Int, ByteArray> = mutableMapOf()
    val uniformMatrix4fvValues: MutableMap<Int, FloatArray> = mutableMapOf()
    val pixels: MutableMap<Int, ByteArray> = mutableMapOf()
    val indexedUniformBuffer: MutableMap<Int, Int> = mutableMapOf()
    private var lastTexImage2DPixels: ByteArray? = null

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

    override fun getError(): Int = if (errorQueue.isEmpty()) GL_NO_ERROR else errorQueue.removeAt(0)

    override fun getString(name: Int): String? {
        log += "getString(${hex(name)})"
        return strings[name]
    }

    override fun getStringi(name: Int, index: Int): String? {
        log += "getStringi(${hex(name)},$index)"
        return indexedStrings.getOrNull(index)
    }

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        log += "getIntegerv(${hex(pname)})"
        integers[pname]?.copyInto(out, endIndex = minOf(out.size, integers.getValue(pname).size))
    }

    override fun getFloatv(pname: Int, out: FloatArray) {
        require(out.isNotEmpty()) { "a float query needs a destination" }
        log += "getFloatv(${hex(pname)})"
        floats[pname]?.copyInto(out, endIndex = minOf(out.size, floats.getValue(pname).size))
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        log += "getBooleanv(${hex(pname)})"
        booleans[pname]?.copyInto(out, endIndex = minOf(out.size, booleans.getValue(pname).size))
    }

    override fun isEnabled(cap: Int): Boolean {
        log += "isEnabled(${hex(cap)})"
        return enabled[cap] ?: false
    }

    override fun getIntegeri_v(pname: Int, index: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an indexed integer query needs a destination" }
        log += "getIntegeri_v(${hex(pname)},$index)"
        out[0] = indexedUniformBuffer[index] ?: 0
    }

    override fun genFramebuffers(count: Int, out: IntArray) = generate("genFramebuffers", count, out)

    override fun deleteFramebuffers(count: Int, names: IntArray) {
        log += "deleteFramebuffers($count)"
        deletedNames += names.take(count)
    }

    override fun bindFramebuffer(target: Int, framebuffer: Int) {
        log += "bindFramebuffer(${hex(target)},$framebuffer)"
    }

    override fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int) {
        log += "framebufferTexture2D(${hex(target)},${hex(attachment)},${hex(textureTarget)},$texture,$level)"
    }

    override fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int) {
        log += "framebufferRenderbuffer(${hex(target)},${hex(attachment)},${hex(renderbufferTarget)},$renderbuffer)"
    }

    override fun checkFramebufferStatus(target: Int): Int {
        log += "checkFramebufferStatus(${hex(target)})"
        return framebufferStatus
    }

    override fun isFramebuffer(framebuffer: Int): Boolean {
        log += "isFramebuffer($framebuffer)"
        return framebuffer != 0
    }

    override fun genRenderbuffers(count: Int, out: IntArray) = generate("genRenderbuffers", count, out)

    override fun deleteRenderbuffers(count: Int, names: IntArray) {
        log += "deleteRenderbuffers($count)"
        deletedNames += names.take(count)
    }

    override fun bindRenderbuffer(target: Int, renderbuffer: Int) {
        log += "bindRenderbuffer(${hex(target)},$renderbuffer)"
    }

    override fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int) {
        log += "renderbufferStorage(${hex(target)},${hex(internalFormat)},$width,$height)"
    }

    override fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    ) {
        log += "blitFramebuffer($sourceX0,$sourceY0,$sourceX1,$sourceY1," +
            "$destinationX0,$destinationY0,$destinationX1,$destinationY1,${hex(mask)},${hex(filter)})"
    }

    override fun drawBuffers(count: Int, buffers: IntArray) {
        log += "drawBuffers($count)"
        lastDrawBuffers = buffers.copyOfRange(0, count)
    }

    override fun readBuffer(mode: Int) {
        log += "readBuffer(${hex(mode)})"
    }

    override fun genTextures(count: Int, out: IntArray) = generate("genTextures", count, out)

    override fun deleteTextures(count: Int, names: IntArray) {
        log += "deleteTextures($count)"
        deletedNames += names.take(count)
    }

    override fun bindTexture(target: Int, texture: Int) {
        log += "bindTexture(${hex(target)},$texture)"
    }

    override fun activeTexture(unit: Int) {
        log += "activeTexture(${hex(unit)})"
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        log += "texImage2D(${hex(target)},$level,${hex(internalFormat)},$width,$height,$border,${hex(format)},${hex(type)})"
        lastTexImage2DPixels = pixels
    }

    /** The exact bytes passed to the most recent [texImage2D] call, or empty if none was passed or made. */
    fun lastTexImageBytes(): List<Byte> = lastTexImage2DPixels?.toList().orEmpty()

    override fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int) {
        log += "texStorage2D(${hex(target)},$levels,${hex(internalFormat)},$width,$height)"
    }

    override fun texParameteri(target: Int, pname: Int, value: Int) {
        log += "texParameteri(${hex(target)},${hex(pname)},${hex(value)})"
    }

    override fun generateMipmap(target: Int) {
        log += "generateMipmap(${hex(target)})"
    }

    override fun genSamplers(count: Int, out: IntArray) = generate("genSamplers", count, out)

    override fun deleteSamplers(count: Int, names: IntArray) {
        log += "deleteSamplers($count)"
        deletedNames += names.take(count)
    }

    override fun bindSampler(unit: Int, sampler: Int) {
        log += "bindSampler($unit,$sampler)"
    }

    override fun samplerParameteri(sampler: Int, pname: Int, value: Int) {
        log += "samplerParameteri($sampler,${hex(pname)},${hex(value)})"
    }

    override fun pixelStorei(pname: Int, value: Int) {
        log += "pixelStorei(${hex(pname)},$value)"
    }

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray) {
        require(out.isNotEmpty()) { "a pixel read needs a destination" }
        log += "readPixels($x,$y,$width,$height,${hex(format)},${hex(type)})"
        pixels[format]?.copyInto(out, endIndex = minOf(out.size, pixels.getValue(format).size))
    }

    override fun genBuffers(count: Int, out: IntArray) = generate("genBuffers", count, out)

    override fun deleteBuffers(count: Int, names: IntArray) {
        log += "deleteBuffers($count)"
        deletedNames += names.take(count)
    }

    override fun bindBuffer(target: Int, buffer: Int) {
        log += "bindBuffer(${hex(target)},$buffer)"
    }

    override fun bindBufferBase(target: Int, index: Int, buffer: Int) {
        log += "bindBufferBase(${hex(target)},$index,$buffer)"
        indexedUniformBuffer[index] = buffer
    }

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        log += "bufferData(${hex(target)},$size,${hex(usage)})"
        bufferDataPayloads[target] = data
    }

    override fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray) {
        log += "bufferSubData(${hex(target)},$offset,$size)"
        bufferSubDataPayloads[target] = data
    }

    override fun genVertexArrays(count: Int, out: IntArray) = generate("genVertexArrays", count, out)

    override fun deleteVertexArrays(count: Int, names: IntArray) {
        log += "deleteVertexArrays($count)"
        deletedNames += names.take(count)
    }

    override fun bindVertexArray(array: Int) {
        log += "bindVertexArray($array)"
    }

    override fun enableVertexAttribArray(index: Int) {
        log += "enableVertexAttribArray($index)"
    }

    override fun disableVertexAttribArray(index: Int) {
        log += "disableVertexAttribArray($index)"
    }

    override fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    ) {
        log += "vertexAttribPointer($index,$size,${hex(type)},$normalized,$stride,$offset)"
    }

    override fun createShader(type: Int): Int {
        log += "createShader(${hex(type)})"
        val name = nextName
        nextName += 1
        return name
    }

    override fun deleteShader(shader: Int) {
        log += "deleteShader($shader)"
    }

    override fun shaderSource(shader: Int, source: String) {
        log += "shaderSource($shader)"
        shaderSources[shader] = source
    }

    override fun compileShader(shader: Int) {
        log += "compileShader($shader)"
    }

    override fun getShaderiv(shader: Int, pname: Int, out: IntArray) {
        log += "getShaderiv($shader,${hex(pname)})"
        out[0] = compileStatus
    }

    override fun getShaderInfoLog(shader: Int): String {
        log += "getShaderInfoLog($shader)"
        return shaderInfoLog
    }

    override fun createProgram(): Int {
        log += "createProgram()"
        val name = nextName
        nextName += 1
        return name
    }

    override fun deleteProgram(program: Int) {
        log += "deleteProgram($program)"
    }

    override fun attachShader(program: Int, shader: Int) {
        log += "attachShader($program,$shader)"
    }

    override fun linkProgram(program: Int) {
        log += "linkProgram($program)"
    }

    override fun getProgramiv(program: Int, pname: Int, out: IntArray) {
        log += "getProgramiv($program,${hex(pname)})"
        out[0] = linkStatus
    }

    override fun getProgramInfoLog(program: Int): String {
        log += "getProgramInfoLog($program)"
        return programInfoLog
    }

    override fun useProgram(program: Int) {
        log += "useProgram($program)"
    }

    override fun getAttribLocation(program: Int, name: String): Int {
        log += "getAttribLocation($program,$name)"
        return declaredNames[name] ?: -1
    }

    override fun getUniformLocation(program: Int, name: String): Int {
        log += "getUniformLocation($program,$name)"
        return declaredNames[name] ?: -1
    }

    override fun uniform1i(location: Int, value: Int) {
        log += "uniform1i($location,$value)"
    }

    override fun uniform1f(location: Int, value: Float) {
        log += "uniform1f($location,$value)"
    }

    override fun uniform2f(location: Int, x: Float, y: Float) {
        log += "uniform2f($location,$x,$y)"
    }

    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) {
        log += "uniform3f($location,$x,$y,$z)"
    }

    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) {
        log += "uniform4f($location,$x,$y,$z,$w)"
    }

    override fun uniform1ui(location: Int, value: Int) {
        log += "uniform1ui($location,$value)"
    }

    override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray) {
        log += "uniformMatrix4fv($location,$count,$transpose)"
        uniformMatrix4fvValues[location] = value
    }

    override fun getUniformBlockIndex(program: Int, name: String): Int {
        log += "getUniformBlockIndex($program,$name)"
        return declaredNames[name] ?: -1
    }

    override fun uniformBlockBinding(program: Int, blockIndex: Int, bindingPoint: Int) {
        log += "uniformBlockBinding($program,$blockIndex,$bindingPoint)"
    }

    override fun enable(cap: Int) {
        log += "enable(${hex(cap)})"
    }

    override fun disable(cap: Int) {
        log += "disable(${hex(cap)})"
    }

    override fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int) {
        log += "blendFuncSeparate(${hex(sourceRgb)},${hex(destinationRgb)},${hex(sourceAlpha)},${hex(destinationAlpha)})"
    }

    override fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int) {
        log += "blendEquationSeparate(${hex(modeRgb)},${hex(modeAlpha)})"
    }

    override fun blendColor(red: Float, green: Float, blue: Float, alpha: Float) {
        log += "blendColor($red,$green,$blue,$alpha)"
    }

    override fun depthFunc(function: Int) {
        log += "depthFunc(${hex(function)})"
    }

    override fun depthMask(enabled: Boolean) {
        log += "depthMask($enabled)"
    }

    override fun depthRangef(near: Float, far: Float) {
        log += "depthRangef($near,$far)"
    }

    override fun cullFace(mode: Int) {
        log += "cullFace(${hex(mode)})"
    }

    override fun frontFace(mode: Int) {
        log += "frontFace(${hex(mode)})"
    }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) {
        log += "viewport($x,$y,$width,$height)"
    }

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        log += "scissor($x,$y,$width,$height)"
    }

    override fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        log += "colorMask($red,$green,$blue,$alpha)"
    }

    override fun clearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        log += "clearColor($red,$green,$blue,$alpha)"
    }

    override fun clearDepthf(depth: Float) {
        log += "clearDepthf($depth)"
    }

    override fun clear(mask: Int) {
        log += "clear(${hex(mask)})"
    }

    override fun drawArrays(mode: Int, first: Int, count: Int) {
        log += "drawArrays(${hex(mode)},$first,$count)"
    }

    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        log += "drawElements(${hex(mode)},$count,${hex(type)},$offset)"
    }

    override fun finish() {
        log += "finish()"
    }

    private fun generate(call: String, count: Int, out: IntArray) {
        require(out.size >= count) { "$call needs room for $count names" }
        log += "$call($count)"
        repeat(count) { index ->
            out[index] = nextName
            nextName += 1
        }
    }
}
