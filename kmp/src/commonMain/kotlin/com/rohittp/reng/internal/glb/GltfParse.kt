package com.rohittp.reng.internal.glb

import com.rohittp.reng.internal.json.JsonValue

/** Byte size of one component, keyed by the six `componentType` values the specification defines
 * and ADR 0021 accepts. Any other value has no known size, so accessor arithmetic over it is
 * undecidable -- that is [GltfReject.COMPONENT_TYPE]. */
private val COMPONENT_SIZE_BYTES: Map<Int, Int> = mapOf(
    5120 to 1, 5121 to 1, 5122 to 2, 5123 to 2, 5125 to 4, 5126 to 4,
)

/** Component count per accessor `type` string. An unrecognised or missing string has no known
 * count -- that is [GltfReject.ACCESSOR_TYPE], distinct from [GltfReject.COMPONENT_TYPE]. */
private val COMPONENT_COUNT_BY_TYPE: Map<String, Int> = mapOf(
    "SCALAR" to 1, "VEC2" to 2, "VEC3" to 3, "VEC4" to 4, "MAT2" to 4, "MAT3" to 9, "MAT4" to 16,
)

/** The specification's reserved "primitive restart" maximum value per unsigned `componentType`,
 * which an index value MUST NOT equal. Component types never legally used for indices are absent
 * on purpose: the reserved-value rule has nothing to say about them. */
private val RESERVED_MAX_INDEX_VALUE: Map<Int, Long> = mapOf(
    5121 to 0xFFL, 5123 to 0xFFFFL, 5125 to 0xFFFFFFFFL,
)

/** The three unsigned component types the specification permits on a primitive's `indices`
 * accessor. Deliberately the same three keys [RESERVED_MAX_INDEX_VALUE] carries, spelled out
 * separately because the two rules are independent: one bounds an index's *value*, this one
 * constrains the accessor's declared *format*. No extension widens this set, so
 * [GltfReject.INDICES_ACCESSOR_FORMAT] is an unconditional malformation. */
private val INDEX_COMPONENT_TYPES: Set<Int> = setOf(5121, 5123, 5125)

/** glTF's `TRIANGLES` topology, and the only `mode` whose vertex-count rule this gate can state.
 * Strips, fans, points and lines each have their own arithmetic and are refused by
 * `VALIDATE_GLB_FEATURES` anyway, so applying a triangle rule to them here would report the wrong
 * fault for a document whose real problem is its topology. */
private const val TRIANGLES_MODE = 4

/** Vertices per triangle: an indexed `TRIANGLES` primitive's index count, and a non-indexed one's
 * vertex count, MUST both be a multiple of this. */
private const val VERTICES_PER_TRIANGLE = 3L

/** The two accessor members the specification types as a per-component bound array. */
private val ACCESSOR_BOUND_FIELDS = listOf("min", "max")

/** The one interpolation whose sampler stores three output values per keyframe -- an in-tangent, a
 * value and an out-tangent. `VALIDATE_GLB_FEATURES` refuses it, but this gate must still read a
 * legal cubic-spline asset by its own arithmetic rather than report it as corrupt (ADR 0021). */
private const val CUBIC_SPLINE_INTERPOLATION = "CUBICSPLINE"

/**
 * Parses [json] -- already scanned as a well-formed GLB JSON chunk by [scanGlb] -- into a fully
 * parsed, internally consistent [GltfDocument], or reports the first structural fault found.
 *
 * This is `PARSE_GLB`: it is permissive about anything the specification permits even when
 * `VALIDATE_GLB_FEATURES` (a later gate, over the [GltfDocument] this function produces) will
 * refuse it. An accessor with no `bufferView` is legal, means all zeros, and is the signature of a
 * Draco-compressed primitive; this function accepts it rather than reporting corruption for a
 * file whose real problem is an unsupported extension.
 *
 * [binChunkLength] is the BIN chunk's byte length (`0` when [GlbScan.Admitted.binChunk] was
 * `null`), used only to bound `buffers[0].byteLength`. [maximumNodeDepth] bounds the node
 * hierarchy walk so a cyclic graph -- which the specification forbids -- terminates instead of
 * recursing forever.
 *
 * `asset.version` is checked first, before any other field: ADR 0021 assigns it to `PARSE_GLB` by
 * name, requiring major version `2` and, when present, `asset.minVersion` at most `2.0`. Neither
 * value is retained on [GltfDocument] -- nothing downstream needs it once this gate has passed.
 */
internal fun parseGltf(json: JsonValue.Obj, binChunkLength: Long, maximumNodeDepth: Int): GltfParseResult =
    try {
        GltfParseResult.Parsed(GltfParser(json, binChunkLength, maximumNodeDepth).parse())
    } catch (signal: GltfRejectSignal) {
        GltfParseResult.Malformed(signal.reason)
    }

/** Internal control-flow signal: unwound by [parseGltf] into a [GltfParseResult.Malformed], never
 * seen outside this file. */
private class GltfRejectSignal(val reason: GltfReject) : RuntimeException()

private fun reject(reason: GltfReject): Nothing = throw GltfRejectSignal(reason)

private fun arrOf(value: JsonValue?): List<JsonValue> = (value as? JsonValue.Arr)?.elements ?: emptyList()

private fun membersOf(value: JsonValue?): Map<String, JsonValue> = (value as? JsonValue.Obj)?.members ?: emptyMap()

/** Splits a `MAJOR.MINOR` version string (glTF's own `asset.version`/`asset.minVersion` form) into
 * its two integer components, or `null` when either half is not an integer spelling. A string with
 * no `.` is treated as `MAJOR.0`. */
private fun majorMinor(text: String): Pair<Int, Int>? {
    val dotIndex = text.indexOf('.')
    val majorText = if (dotIndex >= 0) text.substring(0, dotIndex) else text
    val minorText = if (dotIndex >= 0) text.substring(dotIndex + 1).substringBefore('.') else "0"
    val major = majorText.toIntOrNull() ?: return null
    val minor = minorText.toIntOrNull() ?: return null
    return major to minor
}

private fun numberValue(value: JsonValue?): Double? = when (value) {
    is JsonValue.Integer -> value.value.toDouble()
    is JsonValue.Real -> value.value
    else -> null
}

/** Reads a JSON array of numbers as a [List] of [Double], or `null` when [value] is absent or not
 * an array. An element that is neither an integer nor a real number aborts the whole read
 * (via the non-local `return` inside [kotlin.collections.map], which is `inline`), yielding `null`
 * rather than a partial list -- this field is descriptive only (`matrix`/`translation`/`rotation`/
 * `scale`/factor arrays), with no [GltfReject] code assigned to a malformed element. */
private fun numberList(value: JsonValue?): List<Double>? {
    val array = value as? JsonValue.Arr ?: return null
    return array.elements.map { element -> numberValue(element) ?: return null }
}

/** Reads [field] from [members] as a `Long`, requiring the JSON token to be integer-spelled
 * (rejecting [GltfReject.NON_INTEGER_FIELD] otherwise -- see that code's documentation for why an
 * ordinary integer field, not only an index, uses it). Returns [default] when [field] is absent. */
private fun readLong(members: Map<String, JsonValue>, field: String, default: Long): Long {
    val value = members[field] ?: return default
    return (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
}

private fun readLongOrNull(members: Map<String, JsonValue>, field: String): Long? {
    val value = members[field] ?: return null
    return (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
}

/** Returns [value] when it is at or above zero, and rejects otherwise. Every byte offset, byte
 * length and byte stride the specification declares carries `minimum: 0` in its own schema, and
 * JSON integers are signed ([JsonValue.Integer] holds a `Long`), so nothing but this stops a
 * consumer-supplied `-1000` from reaching the span arithmetic -- where it makes a span *smaller*
 * and so passes every "does it fit" comparison. */
private fun nonNegative(value: Long): Long =
    if (value < 0L) reject(GltfReject.SIZE_FIELD_OUT_OF_RANGE) else value

/** Reads [field] as an index into an array of size [bound]: integer-spelled (else
 * [GltfReject.NON_INTEGER_FIELD]) and in `[0, bound)` (else [GltfReject.INDEX_OUT_OF_RANGE]).
 * Returns `null` when [field] is genuinely optional and absent. */
private fun optionalIndex(members: Map<String, JsonValue>, field: String, bound: Int): Int? {
    val value = members[field] ?: return null
    val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
    if (index < 0 || index >= bound) reject(GltfReject.INDEX_OUT_OF_RANGE)
    return index.toInt()
}

/** As [optionalIndex], but a missing [field] is itself unresolvable -- reused as
 * [GltfReject.INDEX_OUT_OF_RANGE], the same code an out-of-bounds value gets, since neither can be
 * resolved to an element. */
private fun requiredIndex(members: Map<String, JsonValue>, field: String, bound: Int): Int =
    optionalIndex(members, field, bound) ?: reject(GltfReject.INDEX_OUT_OF_RANGE)

private class GltfParser(
    private val json: JsonValue.Obj,
    private val binChunkLength: Long,
    private val maximumNodeDepth: Int,
) {
    /** Set by [parseAccessors]; kept only so [validateIndexValues] can read an indices accessor's
     * declared `max`, a field [GltfAccessor] itself does not retain. */
    private var accessorsJsonMembers: List<Map<String, JsonValue>> = emptyList()

    fun parse(): GltfDocument {
        validateAssetVersion()

        val extensionsRequired = arrOf(json.members["extensionsRequired"])
            .mapNotNull { (it as? JsonValue.Text)?.value }

        val buffers = parseBuffers()
        val bufferViews = parseBufferViews(buffers)
        val accessors = parseAccessors(bufferViews)
        val skinsCount = arrOf(json.members["skins"]).size
        val images = parseImages(bufferViews.size)
        val samplers = parseSamplers()
        val textures = parseTextures(images.size, samplers.size)
        val materials = parseMaterials(textures.size)
        val meshes = parseMeshes(accessors, materials.size)
        val camerasCount = arrOf(json.members["cameras"]).size
        val nodes = parseNodes(meshes.size, skinsCount, camerasCount)
        validateNodeGraph(nodes)
        val skins = parseSkins(nodes.size, accessors.size)
        val scenes = parseScenes(nodes.size)
        val defaultScene = optionalIndex(json.members, "scene", scenes.size)
        val animations = parseAnimations(nodes.size, accessors)
        validateAnimatedNodeTransforms(nodes, animations)

        return GltfDocument(
            accessors = accessors,
            bufferViews = bufferViews,
            meshes = meshes,
            nodes = nodes,
            skins = skins,
            scenes = scenes,
            defaultScene = defaultScene,
            animations = animations,
            materials = materials,
            images = images,
            textures = textures,
            samplers = samplers,
            extensionsRequired = extensionsRequired,
            buffers = buffers,
        )
    }

    /** ADR 0021 assigns `asset.version` checking to `PARSE_GLB` by name. Requires `asset.version`
     * to be present and major version `2`; when `asset.minVersion` is also present, requires it be
     * at most `2.0`. Both fields are `MAJOR.MINOR` text per the specification's own `asset`
     * schema, read here without relying on JSON number spelling since they are strings, not
     * numbers. */
    private fun validateAssetVersion() {
        val assetMembers = membersOf(json.members["asset"])
        val version = (assetMembers["version"] as? JsonValue.Text)?.value
            ?: reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
        val (major, _) = majorMinor(version) ?: reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
        if (major != 2) reject(GltfReject.ASSET_VERSION_UNSUPPORTED)

        val minVersionMember = assetMembers["minVersion"] ?: return
        val minVersion = (minVersionMember as? JsonValue.Text)?.value
            ?: reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
        val (minMajor, minMinor) = majorMinor(minVersion) ?: reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
        if (minMajor > 2 || (minMajor == 2 && minMinor > 0)) reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
    }

    private fun parseBuffers(): List<GltfBuffer> {
        val buffers = arrOf(json.members["buffers"]).map { element ->
            val members = membersOf(element)
            GltfBuffer(
                byteLength = nonNegative(readLong(members, "byteLength", default = 0L)),
                uri = (members["uri"] as? JsonValue.Text)?.value,
            )
        }
        // Only buffers[0] can be GLB-embedded. Reusing BUFFER_VIEW_EXCEEDS_BUFFER: both mean a
        // declared size exceeds what its actual backing store provides, one link further down
        // the buffer/bufferView/accessor chain than the code's name alone suggests.
        if (buffers.isNotEmpty() && buffers[0].byteLength > binChunkLength) {
            reject(GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER)
        }
        return buffers
    }

    private fun parseBufferViews(buffers: List<GltfBuffer>): List<GltfBufferView> =
        arrOf(json.members["bufferViews"]).map { element ->
            val members = membersOf(element)
            val bufferIndex = requiredIndex(members, "buffer", buffers.size)
            val byteOffset = nonNegative(readLong(members, "byteOffset", default = 0L))
            val byteLength = nonNegative(readLong(members, "byteLength", default = 0L))
            val byteStride = readLongOrNull(members, "byteStride")?.let { nonNegative(it) }
            // Stated as a subtraction rather than `byteOffset + byteLength > buffer.byteLength`:
            // both operands are already non-negative, so the difference cannot overflow,
            // whereas the sum can -- and an overflowed sum is negative, which passes.
            if (byteOffset > buffers[bufferIndex].byteLength - byteLength) {
                reject(GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER)
            }
            GltfBufferView(bufferIndex, byteOffset, byteLength, byteStride)
        }

    private fun parseAccessors(bufferViews: List<GltfBufferView>): List<GltfAccessor> {
        accessorsJsonMembers = arrOf(json.members["accessors"]).map { membersOf(it) }
        return accessorsJsonMembers.map { members ->
            val componentType = readLong(members, "componentType", default = -1L).toInt()
            val componentSize = COMPONENT_SIZE_BYTES[componentType] ?: reject(GltfReject.COMPONENT_TYPE)
            val typeName = (members["type"] as? JsonValue.Text)?.value ?: reject(GltfReject.ACCESSOR_TYPE)
            val numComponents = COMPONENT_COUNT_BY_TYPE[typeName] ?: reject(GltfReject.ACCESSOR_TYPE)
            val elementSize = componentSize.toLong() * numComponents

            validateAccessorBounds(members, numComponents)

            val bufferView = optionalIndex(members, "bufferView", bufferViews.size)
            val byteOffset = nonNegative(readLong(members, "byteOffset", default = 0L))
            // The specification's own `count` schema carries `minimum: 1`. Zero is not a smaller
            // accessor, it is one with no defined element -- and it also makes the `count - 1`
            // below negative, which would silently shrink the span check into passing anything.
            val count = readLong(members, "count", default = 0L)
            if (count < 1L) reject(GltfReject.SIZE_FIELD_OUT_OF_RANGE)
            val normalized = (members["normalized"] as? JsonValue.Bool)?.value ?: false
            val sparse = members["sparse"] != null

            if (bufferView != null) {
                val view = bufferViews[bufferView]
                if (view.byteStride != null &&
                    (view.byteStride < elementSize || view.byteStride % componentSize != 0L)
                ) {
                    // Below the element size or not a multiple of the component size: incoherent
                    // addressing, distinct from a span overrunning the view.
                    reject(GltfReject.BYTE_STRIDE)
                }
                val effectiveStride = view.byteStride ?: elementSize
                validateAccessorSpan(byteOffset, count, effectiveStride, elementSize, view.byteLength)
            }

            GltfAccessor(bufferView, byteOffset, componentType, count, typeName, normalized, sparse)
        }
    }

    /**
     * The specification requires an accessor's `min` and `max`, when present, to hold exactly one
     * value per component of its `type`. Checked here for its own sake and because
     * [validateIndexValues] reads `max[0]` as a stand-in for the buffer bytes this gate never
     * sees: a `max` of the wrong length -- `[]` above all -- would otherwise silently switch that
     * content check off rather than fail, which is the worst of the three outcomes.
     */
    private fun validateAccessorBounds(members: Map<String, JsonValue>, numComponents: Int) {
        for (field in ACCESSOR_BOUND_FIELDS) {
            val bound = members[field] ?: continue
            val elements = (bound as? JsonValue.Arr)?.elements ?: reject(GltfReject.ACCESSOR_BOUNDS_LENGTH)
            if (elements.size != numComponents) reject(GltfReject.ACCESSOR_BOUNDS_LENGTH)
        }
    }

    /**
     * Proves `byteOffset + (count - 1) * stride + elementSize <= byteLength` without ever forming
     * that sum, because every term is a caller-supplied `Long` and the sum overflows for large
     * ones -- an overflowed sum is negative, so the direct comparison admits exactly the documents
     * this check exists to refuse. Every operand here is already non-negative and [count] is at
     * least one, so each subtraction below stays in range and the division is exact enough:
     * `(count - 1) > floor(remaining / stride)` holds precisely when `(count - 1) * stride`
     * exceeds `remaining`.
     */
    private fun validateAccessorSpan(
        byteOffset: Long,
        count: Long,
        effectiveStride: Long,
        elementSize: Long,
        byteLength: Long,
    ) {
        val available = byteLength - byteOffset
        if (available < elementSize) reject(GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW)
        val remaining = available - elementSize
        if (count - 1L > remaining / effectiveStride) {
            reject(GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW)
        }
    }

    private fun parseImages(bufferViewsCount: Int): List<GltfImage> =
        arrOf(json.members["images"]).map { element ->
            val members = membersOf(element)
            GltfImage(
                bufferView = optionalIndex(members, "bufferView", bufferViewsCount),
                mimeType = (members["mimeType"] as? JsonValue.Text)?.value,
                uri = (members["uri"] as? JsonValue.Text)?.value,
            )
        }

    private fun parseSamplers(): List<GltfSampler> =
        arrOf(json.members["samplers"]).map { element ->
            val members = membersOf(element)
            GltfSampler(
                magFilter = readLongOrNull(members, "magFilter")?.toInt(),
                minFilter = readLongOrNull(members, "minFilter")?.toInt(),
                wrapS = readLong(members, "wrapS", default = 10497L).toInt(),
                wrapT = readLong(members, "wrapT", default = 10497L).toInt(),
            )
        }

    private fun parseTextures(imagesCount: Int, samplersCount: Int): List<GltfTexture> =
        arrOf(json.members["textures"]).map { element ->
            val members = membersOf(element)
            GltfTexture(
                source = optionalIndex(members, "source", imagesCount),
                sampler = optionalIndex(members, "sampler", samplersCount),
            )
        }

    private fun textureRef(members: Map<String, JsonValue>, field: String, texturesCount: Int): GltfTextureRef? {
        val refMembers = (members[field] as? JsonValue.Obj)?.members ?: return null
        return GltfTextureRef(
            index = requiredIndex(refMembers, "index", texturesCount),
            texCoord = readLong(refMembers, "texCoord", default = 0L).toInt(),
        )
    }

    private fun parseMaterials(texturesCount: Int): List<GltfMaterial> =
        arrOf(json.members["materials"]).map { element ->
            val members = membersOf(element)
            val pbrMembers = (members["pbrMetallicRoughness"] as? JsonValue.Obj)?.members
            val pbr = pbrMembers?.let {
                GltfPbrMetallicRoughness(
                    baseColorFactor = numberList(it["baseColorFactor"]),
                    baseColorTexture = textureRef(it, "baseColorTexture", texturesCount),
                    metallicFactor = numberValue(it["metallicFactor"]),
                    roughnessFactor = numberValue(it["roughnessFactor"]),
                    metallicRoughnessTexture = textureRef(it, "metallicRoughnessTexture", texturesCount),
                )
            }
            GltfMaterial(
                pbrMetallicRoughness = pbr,
                normalTexture = textureRef(members, "normalTexture", texturesCount),
                occlusionTexture = textureRef(members, "occlusionTexture", texturesCount),
                emissiveTexture = textureRef(members, "emissiveTexture", texturesCount),
                emissiveFactor = numberList(members["emissiveFactor"]),
                alphaMode = (members["alphaMode"] as? JsonValue.Text)?.value ?: "OPAQUE",
                alphaCutoff = numberValue(members["alphaCutoff"]) ?: 0.5,
                doubleSided = (members["doubleSided"] as? JsonValue.Bool)?.value ?: false,
            )
        }

    private fun parseMeshes(accessors: List<GltfAccessor>, materialsCount: Int): List<GltfMesh> =
        arrOf(json.members["meshes"]).map { meshElement ->
            val primitives = arrOf(membersOf(meshElement)["primitives"]).map { primitiveElement ->
                val members = membersOf(primitiveElement)
                val attributes = membersOf(members["attributes"]).mapValues { (_, value) ->
                    val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
                    if (index < 0 || index >= accessors.size) reject(GltfReject.INDEX_OUT_OF_RANGE)
                    index.toInt()
                }
                val indices = optionalIndex(members, "indices", accessors.size)
                val mode = readLong(members, "mode", default = 4L).toInt()
                val material = optionalIndex(members, "material", materialsCount)
                val targetCount = arrOf(members["targets"]).size

                if (indices != null) {
                    validateIndicesAccessorFormat(accessors[indices])
                    validateIndexValues(indices, attributes, accessors)
                }
                if (mode == TRIANGLES_MODE) validateTriangleVertexCount(indices, attributes, accessors)

                GltfPrimitive(attributes, indices, mode, material, targetCount)
            }
            GltfMesh(primitives)
        }

    /** The specification types a primitive's `indices` accessor as `SCALAR`, one of the three
     * unsigned component types, and never `normalized` -- an unconditional rule no extension
     * relaxes, so a violation is malformation rather than an unsupported feature. Without it a
     * `VEC3`/`FLOAT` accessor could be named as an index buffer, and the element count a decoder
     * derives from it would describe neither the bytes present nor the draw call issued. */
    private fun validateIndicesAccessorFormat(accessor: GltfAccessor) {
        if (accessor.type != "SCALAR") reject(GltfReject.INDICES_ACCESSOR_FORMAT)
        if (accessor.componentType !in INDEX_COMPONENT_TYPES) reject(GltfReject.INDICES_ACCESSOR_FORMAT)
        if (accessor.normalized) reject(GltfReject.INDICES_ACCESSOR_FORMAT)
    }

    /** A `TRIANGLES` primitive draws whole triangles: its index count when indexed, and its
     * vertex count when not, MUST be a multiple of three. A primitive with no `POSITION` has no
     * vertex count to check here at all -- that absence is
     * [GltfUnsupported.PRIMITIVE_WITHOUT_POSITION], a later gate's statement, so this one stays
     * silent about it rather than reporting the wrong fault. */
    private fun validateTriangleVertexCount(
        indices: Int?,
        attributes: Map<String, Int>,
        accessors: List<GltfAccessor>,
    ) {
        val count = if (indices != null) {
            accessors[indices].count
        } else {
            accessors[attributes["POSITION"] ?: return].count
        }
        if (count % VERTICES_PER_TRIANGLE != 0L) reject(GltfReject.TRIANGLE_VERTEX_COUNT)
    }

    /** The specification's own index-value rules: an index MUST NOT equal the reserved maximum
     * for its component type, and MUST NOT be at or above the vertex count. Both are read from
     * the indices accessor's own declared `max`, per the specification's own promise that a
     * declared `min`/`max` matches the buffer's true contents -- this parser has no buffer bytes
     * to check independently, only [binChunkLength]. An absent `max` is not proof of a violation,
     * so [GltfReject.INDEX_VALUE_OUT_OF_RANGE] is never raised without one: `PARSE_GLB` stays
     * permissive rather than guessing. */
    private fun validateIndexValues(
        indicesAccessorIndex: Int,
        attributes: Map<String, Int>,
        accessors: List<GltfAccessor>,
    ) {
        val positionAccessorIndex = attributes["POSITION"] ?: return
        val attributeCount = accessors[positionAccessorIndex].count
        val indicesAccessor = accessors[indicesAccessorIndex]
        val maxElement = (accessorsJsonMembers[indicesAccessorIndex]["max"] as? JsonValue.Arr)
            ?.elements?.firstOrNull() ?: return
        val declaredMax = (maxElement as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
        val reservedMax = RESERVED_MAX_INDEX_VALUE[indicesAccessor.componentType]
        if (reservedMax != null && declaredMax == reservedMax) reject(GltfReject.INDEX_VALUE_OUT_OF_RANGE)
        if (declaredMax >= attributeCount) reject(GltfReject.INDEX_VALUE_OUT_OF_RANGE)
    }

    private fun parseNodes(meshesCount: Int, skinsCount: Int, camerasCount: Int): List<GltfNode> {
        val nodesArray = arrOf(json.members["nodes"])
        return nodesArray.map { element ->
            val members = membersOf(element)
            val children = arrOf(members["children"]).map { value ->
                val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
                if (index < 0 || index >= nodesArray.size) reject(GltfReject.INDEX_OUT_OF_RANGE)
                index.toInt()
            }
            val mesh = optionalIndex(members, "mesh", meshesCount)
            val skin = optionalIndex(members, "skin", skinsCount)
            val camera = optionalIndex(members, "camera", camerasCount)
            val matrix = numberList(members["matrix"])
            val translation = numberList(members["translation"])
            val rotation = numberList(members["rotation"])
            val scale = numberList(members["scale"])
            if (matrix != null && (translation != null || rotation != null || scale != null)) {
                reject(GltfReject.NODE_MATRIX_AND_TRS)
            }
            GltfNode(children, mesh, skin, camera, matrix, translation, rotation, scale)
        }
    }

    /** Every skin, read after [parseNodes] so a joint can be bound-checked against the real node
     * count. A joint is a node reference like any other, so an unresolvable one is
     * [GltfReject.INDEX_OUT_OF_RANGE]; an empty or absent `joints` is
     * [GltfReject.SIZE_FIELD_OUT_OF_RANGE], since the specification's own schema gives that array
     * `minItems: 1` and a skin with no joint deforms nothing. Whether the skin is *drawn* is not
     * decided here at all -- a `skins` array no node references is exporter debris, which 18 of the
     * consumer's 41 models carry, and nothing about it is malformed. */
    private fun parseSkins(nodesCount: Int, accessorsCount: Int): List<GltfSkin> =
        arrOf(json.members["skins"]).map { element ->
            val members = membersOf(element)
            val joints = arrOf(members["joints"]).map { value ->
                val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
                if (index < 0 || index >= nodesCount) reject(GltfReject.INDEX_OUT_OF_RANGE)
                index.toInt()
            }
            if (joints.isEmpty()) reject(GltfReject.SIZE_FIELD_OUT_OF_RANGE)
            GltfSkin(
                inverseBindMatrices = optionalIndex(members, "inverseBindMatrices", accessorsCount),
                joints = joints,
                skeleton = optionalIndex(members, "skeleton", nodesCount),
            )
        }

    /** The specification requires the node hierarchy to be a set of disjoint strict trees: every
     * node has at most one parent, and no cycle exists. A node referenced as a child more than
     * once -- by one parent twice, or by two different parents -- is rejected immediately. The
     * remaining walk is iterative and bounded by [maximumNodeDepth] so a cycle terminates instead
     * of recursing forever: since every surviving node has at most one parent, a node can only be
     * reached once from any root, so a cyclic component with no root simply never gets visited --
     * which is exactly how it is detected, without a separate visited-set check mid-walk. */
    private fun validateNodeGraph(nodes: List<GltfNode>) {
        val parentCount = IntArray(nodes.size)
        for (node in nodes) for (child in node.children) parentCount[child]++
        if (parentCount.any { it > 1 }) reject(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES)

        val visited = BooleanArray(nodes.size)
        val stack = ArrayDeque<Pair<Int, Int>>()
        for (index in nodes.indices) if (parentCount[index] == 0) stack.addLast(index to 1)
        while (stack.isNotEmpty()) {
            val (index, depth) = stack.removeLast()
            if (depth > maximumNodeDepth) reject(GltfReject.NODE_DEPTH_EXCEEDED)
            visited[index] = true
            for (child in nodes[index].children) stack.addLast(child to depth + 1)
        }
        if (visited.any { !it }) reject(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES)
    }

    /** The specification forbids `matrix` on a node any animation channel targets: the channel
     * animates one of `translation`, `rotation` or `scale`, and a node whose transform is a
     * single baked matrix has none of the three to write into. Sampling such a node has no
     * defined meaning at all -- there is no "repair" that preserves the author's intent, so this
     * is malformation rather than a feature RenG happens not to draw. A channel with no
     * `target.node` is legal and specified as a no-op, so it targets nothing to check. */
    private fun validateAnimatedNodeTransforms(nodes: List<GltfNode>, animations: List<GltfAnimation>) {
        for (animation in animations) {
            for (channel in animation.channels) {
                val target = channel.targetNode ?: continue
                if (nodes[target].matrix != null) reject(GltfReject.ANIMATED_NODE_MATRIX)
            }
        }
    }

    private fun parseScenes(nodesCount: Int): List<GltfScene> =
        arrOf(json.members["scenes"]).map { element ->
            val members = membersOf(element)
            val nodeIndices = arrOf(members["nodes"]).map { value ->
                val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
                if (index < 0 || index >= nodesCount) reject(GltfReject.INDEX_OUT_OF_RANGE)
                index.toInt()
            }
            GltfScene(nodeIndices)
        }

    private fun parseAnimations(nodesCount: Int, accessors: List<GltfAccessor>): List<GltfAnimation> {
        val seenNames = mutableSetOf<String>()
        return arrOf(json.members["animations"]).map { element ->
            val members = membersOf(element)
            val name = (members["name"] as? JsonValue.Text)?.value
            if (!name.isNullOrBlank() && !seenNames.add(name)) reject(GltfReject.DUPLICATE_ANIMATION_NAME)

            val samplers = arrOf(members["samplers"]).map { samplerElement ->
                val samplerMembers = membersOf(samplerElement)
                GltfAnimationSampler(
                    input = requiredIndex(samplerMembers, "input", accessors.size),
                    output = requiredIndex(samplerMembers, "output", accessors.size),
                    interpolation = (samplerMembers["interpolation"] as? JsonValue.Text)?.value ?: "LINEAR",
                )
            }
            val channels = arrOf(members["channels"]).map { channelElement ->
                val channelMembers = membersOf(channelElement)
                val targetMembers = membersOf(channelMembers["target"])
                GltfAnimationChannel(
                    sampler = requiredIndex(channelMembers, "sampler", samplers.size),
                    targetNode = optionalIndex(targetMembers, "node", nodesCount),
                    targetPath = (targetMembers["path"] as? JsonValue.Text)?.value ?: "",
                )
            }
            validateSamplerCounts(samplers, accessors)
            validateDistinctChannelTargets(channels)

            GltfAnimation(name, channels, samplers)
        }
    }

    /** The specification fixes the ratio between a sampler's two accessor counts: one output value
     * per keyframe time, or three for [CUBIC_SPLINE_INTERPOLATION]. A sampler that breaks it is
     * undecidable rather than unsupported -- there is no keyframe the surplus value belongs to and
     * no value the missing keyframe reads -- so it belongs to this gate. Stated as a division and
     * a remainder rather than as `keyframes * valuesPerKeyframe`, which overflows `Long` for a
     * large declared count and wraps negative, passing the comparison it exists to fail. Every
     * sampler is checked, referenced or not, exactly as every accessor's span already is. */
    private fun validateSamplerCounts(samplers: List<GltfAnimationSampler>, accessors: List<GltfAccessor>) {
        for (sampler in samplers) {
            val keyframes = accessors[sampler.input].count
            val values = accessors[sampler.output].count
            val valuesPerKeyframe = if (sampler.interpolation == CUBIC_SPLINE_INTERPOLATION) 3L else 1L
            if (values % valuesPerKeyframe != 0L || values / valuesPerKeyframe != keyframes) {
                reject(GltfReject.ANIMATION_SAMPLER_COUNTS)
            }
        }
    }

    /** The specification forbids two channels of one animation writing the same node and the same
     * `target.path`: the two disagree about one value at one time, with no rule for which wins. A
     * channel naming no node is specified as a no-op, so it targets nothing to collide with. Scoped
     * to one animation because two *animations* driving the same node is the ordinary case -- the
     * caller chooses which to play. */
    private fun validateDistinctChannelTargets(channels: List<GltfAnimationChannel>) {
        val targets = mutableSetOf<Pair<Int, String>>()
        for (channel in channels) {
            val node = channel.targetNode ?: continue
            if (!targets.add(node to channel.targetPath)) {
                reject(GltfReject.DUPLICATE_ANIMATION_CHANNEL_TARGET)
            }
        }
    }
}
