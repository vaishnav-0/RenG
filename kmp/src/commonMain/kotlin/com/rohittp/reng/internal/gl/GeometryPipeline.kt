package com.rohittp.reng.internal.gl

import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * The documented shader interface (design spec "The shader interface"; ADR 0008; ADR 0024).
 *
 * **SILENT-RENAME HAZARD.** RenG binds a documented name only when the compiled program declares
 * it (ADR 0008): [GlBinding.getUniformLocation] and [GlBinding.getAttribLocation] both return a
 * negative location for a name the program never declared, and setting a uniform at a negative
 * location is a silent no-op in GL. [GeometryPipeline] and [drawGeometry] treat that negative
 * location as "do not bind," which is exactly the mechanism that lets a consumer shader naming
 * none of these compile and draw normally. It also means renaming one of the constants below is a
 * SILENT breaking change later: every consumer shader still using the old name keeps compiling and
 * keeps drawing, simply without that value ever being set again — no error, no warning, wrong
 * pixels. See ADR 0024 before touching any string here.
 */
internal const val ATTRIBUTE_POSITION: String = "aPosition"
internal const val ATTRIBUTE_TEXTURE_COORDINATE: String = "aTexCoord"
internal const val UNIFORM_MODEL_VIEW_PROJECTION: String = "uModelViewProjection"
internal const val UNIFORM_RESOLUTION: String = "uResolution"

/**
 * West, south, east, north in degrees. **INFORMATIONAL ONLY** — never derive a vertex position
 * from this uniform, and never let a future change to the vertex path read it. Vertex placement
 * stays camera-relative and exact through [ATTRIBUTE_POSITION] and
 * [UNIFORM_MODEL_VIEW_PROJECTION]: Cycle B's spikes measured camera-relative Float error below
 * 0.001 px, and this uniform's absolute degrees in a 32-bit float would discard that at the final
 * step. Mercator latitude is also not linear in screen space, so interpolating it across a quad
 * (which is what deriving `aPosition` from these bounds plus `aTexCoord` would do) is quietly
 * wrong — `CONTEXT.md` specifies that altitude interpolates north-to-south and deliberately says
 * nothing about latitude. A consumer wanting an approximate geographic position from inside a
 * shader may combine this uniform with `aTexCoord` themselves, visibly and by their own choice.
 */
internal const val UNIFORM_GEOMETRY_BOUNDS: String = "uGeometryBounds"
internal const val UNIFORM_FRAME_INDEX: String = "uFrameIndex"

/** Every documented name, for Task 7's reserved-name rejection at `Geometry` construction. */
internal val RESERVED_SHADER_NAMES: Set<String> = setOf(
    ATTRIBUTE_POSITION,
    ATTRIBUTE_TEXTURE_COORDINATE,
    UNIFORM_MODEL_VIEW_PROJECTION,
    UNIFORM_RESOLUTION,
    UNIFORM_GEOMETRY_BOUNDS,
    UNIFORM_FRAME_INDEX,
)

/**
 * The most consumer textures one `Geometry` may declare.
 *
 * GLES 3.0 guarantees only sixteen fragment texture image units (`GL_MAX_TEXTURE_IMAGE_UNITS`).
 * This budget stays strictly below that guaranteed minimum — rather than exactly saturating it —
 * so RenG's own draw of a geometry keeps headroom for a unit of its own, now or in a later cycle,
 * instead of the guarantee becoming a driver-dependent failure the moment RenG needs one. Exceeding
 * it is rejected at `Geometry` construction (loudly, at the point of the mistake) rather than
 * silently dropped or wrapped modulo sixteen at bind time.
 */
internal const val MAXIMUM_CONSUMER_TEXTURES: Int = 15

/**
 * One compiled consumer geometry program plus the quad it draws with. Every location field is
 * whatever [GlBinding.getAttribLocation] / [GlBinding.getUniformLocation] returned at creation —
 * negative when the program does not declare that name, per the hazard documented above.
 */
internal class GeometryPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    /**
     * The element array buffer every [drawGeometry] call refills with [GeometryGrid.triangleIndices].
     *
     * It exists because a `Geometry` is no longer four vertices: RenG subdivides it and projects the
     * grid's vertices on the CPU so the view-projection stays linear and a consumer's shader pair
     * survives a globe unchanged (ADR 0008's 2026-08-29 erratum). Its binding is recorded in
     * [vertexArray] at creation, which is why nothing rebinds it at draw time.
     */
    val indexBuffer: Int,
    val positionAttributeLocation: Int,
    val texCoordAttributeLocation: Int,
    val modelViewProjectionLocation: Int,
    val resolutionLocation: Int,
    val geometryBoundsLocation: Int,
    val frameIndexLocation: Int,
) {
    /**
     * Consumer uniform and sampler names resolved against [program], memoised on first use.
     *
     * The six documented names above are resolved once at creation, because they are known then. A
     * consumer's own names are not -- they arrive with each `Geometry` -- so [drawGeometry] asked the
     * driver for every one of them on every draw. A geometry declaring forty-six uniforms is
     * forty-six `glGetUniformLocation` calls per instance per frame, each a driver-side string
     * lookup, for an answer that cannot change: a location is a property of the linked program, and
     * this pipeline and that program are deleted together in [deleteGeometryPipeline], so the memo
     * can never outlive what it describes.
     *
     * **A negative location is cached too, and deliberately.** `getUniformLocation` returns a
     * negative for a name the program never declared, which is the documented "do not bind" signal
     * at the top of this file. That answer is as permanent as a positive one, so re-asking for it
     * every frame is the same waste with none of the benefit -- and a shader that legitimately
     * declares fewer names than a material offers is ordinary rather than an error.
     */
    private val consumerLocations: MutableMap<String, Int> = HashMap()

    /** [name]'s location in [program], from the memo above or from the driver exactly once. */
    fun consumerLocation(binding: GlBinding, name: String): Int =
        consumerLocations.getOrPut(name) { binding.getUniformLocation(program, name) }

    /**
     * The CPU byte buffers [drawGeometry] packs this pipeline's grid into before uploading it, reused
     * across draws and grown by doubling.
     *
     * Each draw used to allocate two fresh `ByteArray`s sized to that draw's grid. On a Mercator
     * geometry that is eighty bytes and beneath notice; on the globe and drape paths, where a grid
     * reaches [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE] cells a side, it is on the order of a
     * megabyte per geometry per frame, allocated and discarded immediately.
     *
     * **They are only ever handed to [GlBinding.bufferData] with an explicit byte count**, which
     * every platform actual passes straight to `glBufferData` while pinning the array from index
     * zero -- so a scratch larger than this draw needs uploads exactly the prefix just packed, never
     * the stale tail behind it. That is the whole safety argument for reusing them, and it is why
     * neither is passed anywhere that would take its length from the array instead.
     */
    private var vertexScratch: ByteArray = ByteArray(0)
    private var indexScratch: ByteArray = ByteArray(0)

    /** The scratch [packVertexBytes] filled. Valid until the next call on this pipeline. */
    val vertexBytes: ByteArray get() = vertexScratch

    /** The scratch [packIndexBytes] filled. Valid until the next call on this pipeline. */
    val indexBytes: ByteArray get() = indexScratch

    /** Packs [values] little-endian into [vertexBytes] and returns how many bytes that took. */
    fun packVertexBytes(values: FloatArray): Int {
        val byteCount = values.size * Float.SIZE_BYTES
        if (vertexScratch.size < byteCount) {
            vertexScratch = ByteArray(grownScratchSize(vertexScratch.size, byteCount))
        }
        writeLittleEndianFloats(values, vertexScratch)
        return byteCount
    }

    /** Packs [values] little-endian into [indexBytes] and returns how many bytes that took. */
    fun packIndexBytes(values: ShortArray): Int {
        val byteCount = values.size * Short.SIZE_BYTES
        if (indexScratch.size < byteCount) {
            indexScratch = ByteArray(grownScratchSize(indexScratch.size, byteCount))
        }
        writeLittleEndianShorts(values, indexScratch)
        return byteCount
    }
}

/**
 * Doubling growth that always clears [required], from a zero-length start.
 *
 * Doubling rather than exact, matching [growIconBuffers]: exact growth reallocates on a grid that
 * gained one cell, which is the allocation this exists to remove.
 */
private fun grownScratchSize(current: Int, required: Int): Int {
    var size = if (current == 0) required else current
    while (size < required) size *= 2
    return size
}

/** Writes [values] little-endian into the first `values.size * 4` bytes of [out]. */
private fun writeLittleEndianFloats(values: FloatArray, out: ByteArray) {
    var offset = 0
    values.forEach { value ->
        val bits = value.toRawBits()
        out[offset] = (bits and 0xff).toByte()
        out[offset + 1] = ((bits ushr 8) and 0xff).toByte()
        out[offset + 2] = ((bits ushr 16) and 0xff).toByte()
        out[offset + 3] = ((bits ushr 24) and 0xff).toByte()
        offset += Float.SIZE_BYTES
    }
}

/**
 * Writes [values] little-endian into the first `values.size * 2` bytes of [out], as
 * `GL_UNSIGNED_SHORT` expects.
 *
 * A 16-bit index is what caps [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE] at 128 for the globe ground
 * and for a geometry grid alike: 129 x 129 is 16,641 vertices where 257 x 257 would overflow.
 */
private fun writeLittleEndianShorts(values: ShortArray, out: ByteArray) {
    values.forEachIndexed { index, value ->
        val bits = value.toInt()
        out[index * 2] = (bits and 0xff).toByte()
        out[index * 2 + 1] = ((bits ushr 8) and 0xff).toByte()
    }
}

internal sealed interface GeometryPipelineResult {
    data class Created(val pipeline: GeometryPipeline) : GeometryPipelineResult

    data class Failed(val failure: FailureDescriptor) : GeometryPipelineResult
}

/**
 * Compiles (or reuses, via [cache]) the program for [shaderPair] and builds the one quad every
 * draw of this geometry reuses. Follows [createCompositePipeline]'s shape as the reference call
 * site for compiling and caching a program.
 */
internal fun createGeometryPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    shaderPair: ShaderPair,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): GeometryPipelineResult {
    val key = deriver.geometryProgram(shaderPair).key
    val vertexPlan = scanShaderProfile(shaderPair.vertexSource)
        ?: return GeometryPipelineResult.Failed(shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key))
    val fragmentPlan = scanShaderProfile(shaderPair.fragmentSource)
        ?: return GeometryPipelineResult.Failed(shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return GeometryPipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]
    binding.genBuffers(1, names)
    val indexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    // Bound while the vertex array is bound and never unbound: an element array buffer binding is
    // vertex-array state, so re-binding the VAO at draw time restores it. `globeGroundGrid` does the
    // same thing for the same reason.
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)

    // ADR 0008: bind only the attributes this program actually declares. Unlike an unset uniform,
    // enabling a negative vertex attrib index is a genuine GL error rather than a harmless no-op,
    // so the guard here is load-bearing for correctness, not only for the "bind only when
    // declared" contract.
    val positionLocation = binding.getAttribLocation(program, ATTRIBUTE_POSITION)
    if (positionLocation >= 0) {
        binding.enableVertexAttribArray(positionLocation)
        binding.vertexAttribPointer(
            positionLocation,
            GEOMETRY_POSITION_COMPONENT_COUNT,
            GL_FLOAT,
            false,
            GEOMETRY_STRIDE_BYTES,
            0,
        )
    }
    val texCoordLocation = binding.getAttribLocation(program, ATTRIBUTE_TEXTURE_COORDINATE)
    if (texCoordLocation >= 0) {
        binding.enableVertexAttribArray(texCoordLocation)
        binding.vertexAttribPointer(
            texCoordLocation,
            GEOMETRY_TEXCOORD_COMPONENT_COUNT,
            GL_FLOAT,
            false,
            GEOMETRY_STRIDE_BYTES,
            GEOMETRY_TEXCOORD_OFFSET_BYTES,
        )
    }

    return GeometryPipelineResult.Created(
        GeometryPipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            indexBuffer = indexBuffer,
            positionAttributeLocation = positionLocation,
            texCoordAttributeLocation = texCoordLocation,
            modelViewProjectionLocation = binding.getUniformLocation(program, UNIFORM_MODEL_VIEW_PROJECTION),
            resolutionLocation = binding.getUniformLocation(program, UNIFORM_RESOLUTION),
            geometryBoundsLocation = binding.getUniformLocation(program, UNIFORM_GEOMETRY_BOUNDS),
            frameIndexLocation = binding.getUniformLocation(program, UNIFORM_FRAME_INDEX),
        ),
    )
}

internal fun deleteGeometryPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GeometryPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    // Both buffers explicitly, for the reason `deleteUploadedPrimitive` already records: deleting a
    // VAO frees the VAO object alone, never the buffers whose bindings it recorded.
    binding.deleteBuffers(2, intArrayOf(pipeline.vertexBuffer, pipeline.indexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * Draws one geometry instance using an already-created [pipeline].
 *
 * [grid] must already be resolved and projected through the camera-relative path — see
 * [geometryGrid] — and carries `x, y, z, u, v` per vertex plus the triangle indices over them. This
 * function never reads a raw latitude/longitude/altitude and never computes a vertex position
 * itself, so it cannot be the place that discards Cycle B's camera-relative precision.
 *
 * **A grid rather than four corners, in both projection modes.** A Mercator-to-sphere map is
 * nonlinear and so cannot be a matrix, so RenG subdivides a `Geometry` and projects its vertices on
 * the CPU, leaving [modelViewProjection] linear and a consumer's shader pair working unchanged in
 * both modes (ADR 0008's 2026-08-29 erratum, and the design spec's section 6). Two consequences are
 * recorded there rather than repaired: a shader reading `gl_VertexID` no longer sees 0 through 3 —
 * it sees an index into a grid whose size and ordering RenG chooses and may change between versions,
 * and a consumer wanting corner identity should read [ATTRIBUTE_TEXTURE_COORDINATE], which
 * subdivision interpolates correctly by construction — and `aPosition.z` carries pure altitude under
 * Mercator but altitude *and* the sphere's curvature on a globe.
 *
 * [boundsWestSouthEastNorthDegrees] is the *separate*, informational `uGeometryBounds` payload
 * documented on [UNIFORM_GEOMETRY_BOUNDS]. It travels only to that one uniform and is never folded
 * into [cameraRelativeCornersXyz] or [modelViewProjection].
 *
 * Every uniform and attribute is set only when [pipeline] recorded a non-negative location for it
 * (ADR 0008): a shader declaring none of the six documented names still compiles and draws here,
 * and this function issues no `uniform*` call for a name [pipeline] did not resolve to a location
 * at creation.
 *
 * `frameIndex` is a `Long` narrowed to the `uint` `uFrameIndex` expects; the narrowing wraps at
 * 2^32 exactly as [GlBinding.uniform1ui] documents.
 *
 * [consumerUniforms] and [consumerTextures] are the [PreparedFrame][com.rohittp.reng.PreparedFrame]-time
 * SNAPSHOTS of `Geometry.uniforms` and `Geometry.textures` (Cycle F-1 Task 3), keyed by the
 * consumer's own sampler/uniform names rather than the six documented ones above.
 * [consumerTextures] carries each name's ALREADY-UPLOADED GL texture object name, not a
 * [com.rohittp.reng.internal.image.DecodedImage] — the caller assembling one frame's
 * [SceneGeometry]s uploads (and caches, by `ResourceKey`, through `GlObjectRegistry`) once, so this
 * function issues no [uploadTexture] call of its own and cannot be the site of Task 9b's
 * texture-lifetime leak. Each name is resolved against [pipeline]'s program through
 * [GeometryPipeline.consumerLocation] — the same "bind only when declared" rule [pipeline]'s own six
 * locations already follow, reached differently because a consumer name's location cannot be
 * resolved at pipeline-creation time the way the six documented ones are: the pipeline is shared and
 * content-keyed by [ShaderPair] alone, while the set of consumer names varies by `Geometry`
 * instance. So it is resolved on **first** draw that names it and memoised on the pipeline
 * thereafter, which is sound for the same reason creation-time resolution is: a location depends on
 * nothing but the linked program and the name, and the pipeline owns that program for its whole
 * life. Both maps are iterated in ascending key order (plain `String` comparison,
 * i.e. UTF-16 code-unit order — the same fixed, non-locale-sensitive ordering the canonical frame
 * encoding uses) purely so the same document always assigns the same texture units; the order
 * uniforms are set in has no observable effect since each lands at its own independent location.
 *
 * **`Geometry.uniforms`/`.textures` are never read here.** This function only ever sees the
 * `PreparedFrame`-time snapshot a caller already took (`com.rohittp.reng.RenGPreparedFrame`, Task
 * 9b) — never the live `Map` reference `Geometry` itself carries — so a consumer mutating either
 * map after `prepare()` cannot change what a later `draw()` on the same frame renders. This closes
 * the gap Task 7 could only document: the defensive `Map.toMap()` snapshot this function still
 * takes below guards only against the two arguments being structurally mutated mid-call, which is a
 * narrower, cheaper guarantee than the prepare-time snapshot that actually protects frame identity.
 *
 * Every consumer texture name in [consumerTextures] was uploaded through [TextureContent.DATA],
 * never [TextureContent.IMAGE], by the caller that resolved it: a consumer texture is a boundary
 * mask, a signed-distance field, or values packed across RGBA channels, and premultiplying any of
 * those corrupts it silently, with no error — see [uploadTexture]'s KDoc and `CONTEXT.md`'s
 * identical precedent for terrain samples.
 */
internal fun drawGeometry(
    binding: GlBinding,
    pipeline: GeometryPipeline,
    grid: GeometryGrid,
    modelViewProjection: FloatArray,
    resolutionWidthPixels: Float,
    resolutionHeightPixels: Float,
    boundsWestSouthEastNorthDegrees: FloatArray,
    frameIndex: Long,
    consumerUniforms: Map<String, ShaderValue> = emptyMap(),
    consumerTextures: Map<String, Int> = emptyMap(),
) {
    require(modelViewProjection.size == GEOMETRY_MVP_FLOAT_COUNT) {
        "a model-view-projection matrix requires exactly sixteen elements"
    }
    require(boundsWestSouthEastNorthDegrees.size == GEOMETRY_BOUNDS_FLOAT_COUNT) {
        "geometry bounds require exactly west, south, east and north degrees"
    }
    require(consumerTextures.size <= MAXIMUM_CONSUMER_TEXTURES) {
        "a geometry may bind at most $MAXIMUM_CONSUMER_TEXTURES consumer textures"
    }

    // Iterated directly, not copied. Both maps are already private to the frame being drawn:
    // `PreparedGeometry.uniformsSnapshot` is a `.toMap()` taken at prepare() time and never the
    // caller's own reference, and the texture map is built fresh per frame from it. The copies that
    // used to stand here guarded this call's iteration against a structural mutation that no
    // production caller can perform, at the cost of two map copies per geometry per frame.
    //
    // That makes it the caller's obligation rather than this function's: pass maps nothing else can
    // mutate while this call runs.
    val uniformsSnapshot = consumerUniforms
    val texturesSnapshot = consumerTextures

    // A grid the camera pruned to nothing draws nothing, and says so by touching no GL state at
    // all rather than by uploading two empty buffers and issuing a zero-count draw. A geometry
    // entirely behind the limb is the ordinary way this happens on a globe.
    if (grid.triangleIndices.isEmpty()) return

    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
    // Packed into the pipeline's reused scratch and uploaded by explicit byte count, never by array
    // length: the scratch is a high-water buffer and its tail is whatever the last, larger draw left.
    val vertexByteCount = pipeline.packVertexBytes(grid.interleavedVertices)
    binding.bufferData(GL_ARRAY_BUFFER, vertexByteCount, pipeline.vertexBytes, GL_DYNAMIC_DRAW)
    val indexByteCount = pipeline.packIndexBytes(grid.triangleIndices)
    binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indexByteCount, pipeline.indexBytes, GL_DYNAMIC_DRAW)

    if (pipeline.modelViewProjectionLocation >= 0) {
        binding.uniformMatrix4fv(pipeline.modelViewProjectionLocation, 1, false, modelViewProjection)
    }
    if (pipeline.resolutionLocation >= 0) {
        binding.uniform2f(pipeline.resolutionLocation, resolutionWidthPixels, resolutionHeightPixels)
    }
    if (pipeline.geometryBoundsLocation >= 0) {
        binding.uniform4f(
            pipeline.geometryBoundsLocation,
            boundsWestSouthEastNorthDegrees[0],
            boundsWestSouthEastNorthDegrees[1],
            boundsWestSouthEastNorthDegrees[2],
            boundsWestSouthEastNorthDegrees[3],
        )
    }
    if (pipeline.frameIndexLocation >= 0) {
        binding.uniform1ui(pipeline.frameIndexLocation, frameIndex.toInt())
    }

    uniformsSnapshot.entries.sortedBy { it.key }.forEach { (name, value) ->
        val location = pipeline.consumerLocation(binding, name)
        if (location >= 0) {
            bindConsumerUniform(binding, location, value)
        }
    }

    texturesSnapshot.entries.sortedBy { it.key }.forEachIndexed { unitIndex, (name, texture) ->
        val samplerLocation = pipeline.consumerLocation(binding, name)
        binding.activeTexture(GL_TEXTURE0 + unitIndex)
        binding.bindTexture(GL_TEXTURE_2D, texture)
        if (samplerLocation >= 0) {
            binding.uniform1i(samplerLocation, unitIndex)
        }
    }

    binding.drawElements(GL_TRIANGLES, grid.triangleIndices.size, GL_UNSIGNED_SHORT, 0)
}

/**
 * Dispatches [value] to the one [GlBinding] setter matching its variant, at [location].
 *
 * Written as an expression body over an exhaustive `when` with **no `else` branch**, so a future
 * [ShaderValue] variant is a compiler error here rather than a silently unset uniform — the same
 * discipline Cycle D's `applyTerminal` applies to `RendererOwnerState`, and for the same reason: a
 * missing branch must fail loudly at compile time, not sample as an untouched GL default at
 * runtime.
 */
private fun bindConsumerUniform(binding: GlBinding, location: Int, value: ShaderValue): Unit = when (value) {
    is ShaderValue.Scalar -> binding.uniform1f(location, value.value)
    is ShaderValue.Vec2 -> binding.uniform2f(location, value.x, value.y)
    is ShaderValue.Vec3 -> binding.uniform3f(location, value.x, value.y, value.z)
    is ShaderValue.Vec4 -> binding.uniform4f(location, value.x, value.y, value.z, value.w)
    is ShaderValue.Integer -> binding.uniform1i(location, value.value)
    is ShaderValue.Mat4 -> binding.uniformMatrix4fv(location, 1, false, value.elementsForCore())
}

private const val GEOMETRY_POSITION_COMPONENT_COUNT: Int = 3
private const val GEOMETRY_TEXCOORD_COMPONENT_COUNT: Int = 2
internal const val GEOMETRY_VERTEX_COMPONENT_COUNT: Int =
    GEOMETRY_POSITION_COMPONENT_COUNT + GEOMETRY_TEXCOORD_COMPONENT_COUNT
private const val GEOMETRY_STRIDE_BYTES: Int = GEOMETRY_VERTEX_COMPONENT_COUNT * Float.SIZE_BYTES
private const val GEOMETRY_TEXCOORD_OFFSET_BYTES: Int = GEOMETRY_POSITION_COMPONENT_COUNT * Float.SIZE_BYTES
private const val GEOMETRY_BOUNDS_FLOAT_COUNT: Int = 4
private const val GEOMETRY_MVP_FLOAT_COUNT: Int = 16
