package com.rohittp.reng.internal.glb

/** One accessor: a typed, possibly strided view into a [GltfBufferView]. [bufferView] is `null`
 * when the specification's "all zeros" accessor form is used -- legal, and the signature of a
 * Draco-compressed primitive -- so [parseGltf] must never treat its absence as malformed. */
internal data class GltfAccessor(
    val bufferView: Int?,
    val byteOffset: Long,
    val componentType: Int,
    val count: Long,
    val type: String,
    val normalized: Boolean,
    val sparse: Boolean,
)

/** A byte range inside one [GltfDocument.buffers] entry, optionally strided for interleaved
 * attributes. [byteStride] is `null` when the view is tightly packed. */
internal data class GltfBufferView(
    val buffer: Int,
    val byteOffset: Long,
    val byteLength: Long,
    val byteStride: Long?,
)

/** One draw call's worth of geometry: an accessor per attribute semantic, an optional index
 * buffer, a topology [mode] (glTF default `4`, `TRIANGLES`), an optional material, and the count
 * of morph-target objects declared on this primitive (`0` when none are present). Whether [mode]
 * or a non-zero [targetCount] is something RenG actually draws is `VALIDATE_GLB_FEATURES`'
 * concern, not this parse gate's. */
internal data class GltfPrimitive(
    val attributes: Map<String, Int>,
    val indices: Int?,
    val mode: Int,
    val material: Int?,
    val targetCount: Int,
)

internal data class GltfMesh(val primitives: List<GltfPrimitive>)

/** One node in the scene graph. Exactly one of [matrix] or any of [translation]/[rotation]/
 * [scale] may be present -- [parseGltf] rejects both, per the specification. [skin] is parsed
 * only so a later gate can detect and reject it; [camera] is parsed only so that gate can bound-
 * check it and then ignore it -- ADR 0021 accepts cameras and draws nothing from them.
 * [GltfDocument] retains no top-level `skins` or `cameras` catalog because nothing else in RenG's
 * vocabulary refers to either. */
internal data class GltfNode(
    val children: List<Int>,
    val mesh: Int?,
    val skin: Int?,
    val camera: Int?,
    val matrix: List<Double>?,
    val translation: List<Double>?,
    val rotation: List<Double>?,
    val scale: List<Double>?,
)

internal data class GltfScene(val nodes: List<Int>)

internal data class GltfAnimationChannel(val sampler: Int, val targetNode: Int?, val targetPath: String)

/** [interpolation] defaults to the specification's own default, `LINEAR`. */
internal data class GltfAnimationSampler(val input: Int, val output: Int, val interpolation: String)

/** [name] is optional and, per `CONTEXT.md`, not required to be unique -- only a non-blank
 * duplicate is rejected. See [parseGltf]. */
internal data class GltfAnimation(
    val name: String?,
    val channels: List<GltfAnimationChannel>,
    val samplers: List<GltfAnimationSampler>,
)

/** A `{index, texCoord}` texture reference, used by every texture slot a [GltfMaterial] carries.
 * [texCoord] defaults to the specification's own default, `0`. */
internal data class GltfTextureRef(val index: Int, val texCoord: Int)

internal data class GltfPbrMetallicRoughness(
    val baseColorFactor: List<Double>?,
    val baseColorTexture: GltfTextureRef?,
    val metallicFactor: Double?,
    val roughnessFactor: Double?,
    val metallicRoughnessTexture: GltfTextureRef?,
)

/** Every material property ADR 0021 commits to retaining -- the base-colour slot a [GltfTextureRef]
 * override replaces, the four secondary texture slots, and the alpha/cull state -- parsed and kept
 * even though shading them is a later cycle's decision. */
internal data class GltfMaterial(
    val pbrMetallicRoughness: GltfPbrMetallicRoughness?,
    val normalTexture: GltfTextureRef?,
    val occlusionTexture: GltfTextureRef?,
    val emissiveTexture: GltfTextureRef?,
    val emissiveFactor: List<Double>?,
    val alphaMode: String,
    val alphaCutoff: Double,
    val doubleSided: Boolean,
)

/** [uri] is parsed only so `VALIDATE_GLB_FEATURES` can reject an external or `data:` reference;
 * RenG's only supported image form is [bufferView] plus an `image/png` [mimeType]. */
internal data class GltfImage(val bufferView: Int?, val mimeType: String?, val uri: String?)

internal data class GltfTexture(val source: Int?, val sampler: Int?)

/** [wrapS]/[wrapT] default to the specification's own default, `10497` (`REPEAT`). */
internal data class GltfSampler(val magFilter: Int?, val minFilter: Int?, val wrapS: Int, val wrapT: Int)

/** [uri] is parsed only so `VALIDATE_GLB_FEATURES` can reject it; RenG's only supported buffer
 * form is `buffers[0]` embedded in the GLB's BIN chunk. */
internal data class GltfBuffer(val byteLength: Long, val uri: String?)

/** A fully parsed, internally consistent glTF 2.0 document: every index *reference* resolves to
 * an element of the array it indexes into, every byte offset, length, stride and element count is
 * non-negative (and every accessor `count` at least one), every accessor's arithmetic fits its
 * backing storage without relying on a sum that could overflow, and the node hierarchy is a set of
 * disjoint strict trees. Those first two clauses are what makes a byte range derived from an
 * accessor safe to slice: a negative offset or count would otherwise shrink a computed span into
 * passing every fit test while addressing memory before the view. This is a structural
 * guarantee, not a content one: an indices accessor's actual index *values* are checked only
 * opportunistically, against a declared `max` when one is present -- see
 * [GltfReject.INDEX_VALUE_OUT_OF_RANGE] for why a buffer-level bounds check is not available here,
 * and why a `Parsed` result does not by itself guarantee every index value is in range. No
 * judgement is made yet about whether RenG can draw the document -- that is
 * `VALIDATE_GLB_FEATURES`'s job, over this same structure. */
internal data class GltfDocument(
    val accessors: List<GltfAccessor>,
    val bufferViews: List<GltfBufferView>,
    val meshes: List<GltfMesh>,
    val nodes: List<GltfNode>,
    val scenes: List<GltfScene>,
    val defaultScene: Int?,
    val animations: List<GltfAnimation>,
    val materials: List<GltfMaterial>,
    val images: List<GltfImage>,
    val textures: List<GltfTexture>,
    val samplers: List<GltfSampler>,
    val extensionsRequired: List<String>,
    val buffers: List<GltfBuffer>,
)

/**
 * Every reason [parseGltf] can reject a document. These are exactly the malformation checks ADR
 * 0021 assigns to `PARSE_GLB`: the bytes are not a well-formed, internally consistent glTF 2.0
 * document. A feature the specification permits but RenG does not draw is never reported here --
 * that is `VALIDATE_GLB_FEATURES`'s distinct vocabulary ([GltfUnsupported] et al., a later task),
 * over the [GltfDocument] this gate produces.
 *
 * Several codes are deliberately reused across related faults rather than given one code apiece,
 * the same design already established for `GlbReject.DECLARED_LENGTH_MISMATCH`: one code per kind
 * of true statement the consumer needs, not one code per JSON field.
 *
 * - [NON_INTEGER_FIELD] covers every integer-typed field read anywhere in the document -- not
 *   only a reference into another array, but any field the specification types as an integer
 *   (`count`, `componentType`, `byteOffset`, `byteLength`, `byteStride`, `mode`, an accessor's
 *   declared `max`) -- whenever the JSON number token is fractional or exponential. `1e2` is a
 *   JSON number but is not an integer spelling, so it is never accepted as one. Named for the
 *   field, not narrowed to "index", precisely because it also covers non-index integer fields
 *   such as `count` and `byteStride`.
 * - [INDEX_OUT_OF_RANGE] covers a syntactically valid integer reference that names no element of
 *   the array it indexes into, and also a structurally required index field that is absent
 *   entirely -- both mean the reference cannot be resolved.
 * - [COMPONENT_TYPE] covers only an accessor's `componentType`: unrecognised, or absent. An
 *   unrecognised or absent `type` string is the adjacent but distinct [ACCESSOR_TYPE] -- the two
 *   JSON fields are easy to confuse by name, so they are not allowed to share a code; conflating
 *   them would point a consumer debugging by code name at the wrong field.
 * - [ACCESSOR_TYPE] covers an accessor's `type` string being unrecognised or absent, leaving the
 *   number of components per element -- and so the element size -- undecidable.
 * - [ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW] covers only an accessor's byte span overflowing its
 *   buffer view. A `byteStride` that is below the accessor's element size or not a multiple of
 *   the component size is a distinct fault, incoherent addressing rather than an overrun, and is
 *   [BYTE_STRIDE] instead.
 * - [BYTE_STRIDE] covers a `bufferView.byteStride` that is below the referencing accessor's
 *   element size, or not a multiple of the component size -- addressing that cannot be resolved,
 *   as opposed to addressing that resolves past the end of the view.
 * - [BUFFER_VIEW_EXCEEDS_BUFFER] covers both a buffer view's span overflowing its buffer and
 *   `buffers[0]`'s declared `byteLength` overflowing the actual BIN chunk -- both mean a declared
 *   size exceeds what its backing store actually provides, one level apart in the same chain.
 * - [INDEX_VALUE_OUT_OF_RANGE] is weaker than its name alone suggests, and that gap is
 *   deliberate, not an oversight: `parseGltf` receives `binChunkLength` only, never the BIN
 *   chunk's bytes, so it cannot read an indices accessor's actual buffer values. This code fires
 *   only when an indices accessor declares its own `max` (a value the specification requires to
 *   match the buffer's true contents) and that declared value equals the reserved maximum for its
 *   `componentType`, or is at or above the referenced attribute count. When `max` is absent
 *   entirely, this check does not fire at all -- there is nothing to prove a violation against --
 *   so a [GltfParseResult.Parsed] result never guarantees every index value is in range; it only
 *   guarantees no declared `max` proves one is not. See [GltfDocument]'s own documentation for the
 *   same caveat stated against the document as a whole.
 * - [ASSET_VERSION_UNSUPPORTED] covers `asset.version` missing, malformed, or not major version
 *   `2`, and `asset.minVersion` malformed or above `2.0` -- ADR 0021 assigns `asset.version`
 *   checking to `PARSE_GLB` by name.
 * - [SIZE_FIELD_OUT_OF_RANGE] covers every size-typed integer field whose value falls outside the
 *   range the specification's own schema declares for it: a negative `buffer.byteLength`,
 *   `bufferView.byteOffset`, `bufferView.byteLength`, `bufferView.byteStride` or
 *   `accessor.byteOffset`, and an `accessor.count` below one. Named for the range rather than for
 *   the field, in the same spirit as [NON_INTEGER_FIELD], because the consumer's next action is
 *   identical in every case. This is the memory-safety code of the set: none of the span
 *   comparisons this gate performs is meaningful over a negative operand, since a negative offset
 *   or count makes a computed span *smaller* and so passes every "does it fit" test, and the
 *   accessor it admits addresses bytes before the start of its buffer view.
 * - [ACCESSOR_BOUNDS_LENGTH] covers an accessor's `min` or `max` that is present but is not an
 *   array of exactly one value per component of the accessor's `type`. Distinct from
 *   [INDEX_VALUE_OUT_OF_RANGE], which is about what a well-formed `max` *says*: this one is about
 *   `max` being unreadable in the first place, which would otherwise switch that check silently
 *   off rather than fail.
 * - [INDICES_ACCESSOR_FORMAT] covers a primitive's `indices` naming an accessor that is not
 *   `SCALAR`, or whose `componentType` is not one of the three unsigned types, or that is
 *   `normalized`. Unconditional in the specification -- no extension widens it -- so it is
 *   malformation, not an unsupported feature.
 * - [TRIANGLE_VERTEX_COUNT] covers a `TRIANGLES` primitive whose index count, or vertex count
 *   when it is not indexed, is not a multiple of three. Only `TRIANGLES` is checked: every other
 *   topology has its own arithmetic and is refused by `VALIDATE_GLB_FEATURES`, so applying a
 *   triangle rule to a strip would name the wrong fault.
 * - [ANIMATED_NODE_MATRIX] covers a node carrying `matrix` that some animation channel targets.
 *   Distinct from [NODE_MATRIX_AND_TRS], which is about one node contradicting itself: this is a
 *   contradiction between a node and an animation elsewhere in the document, and a consumer fixes
 *   it by decomposing the matrix into TRS rather than by deleting a member.
 */
internal enum class GltfReject {
    ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
    BUFFER_VIEW_EXCEEDS_BUFFER,
    NODE_MATRIX_AND_TRS,
    NODE_GRAPH_NOT_DISJOINT_TREES,
    NODE_DEPTH_EXCEEDED,
    INDEX_OUT_OF_RANGE,
    INDEX_VALUE_OUT_OF_RANGE,
    DUPLICATE_ANIMATION_NAME,
    NON_INTEGER_FIELD,
    COMPONENT_TYPE,
    ACCESSOR_TYPE,
    BYTE_STRIDE,
    ASSET_VERSION_UNSUPPORTED,
    SIZE_FIELD_OUT_OF_RANGE,
    ACCESSOR_BOUNDS_LENGTH,
    INDICES_ACCESSOR_FORMAT,
    TRIANGLE_VERTEX_COUNT,
    ANIMATED_NODE_MATRIX,
}

internal sealed interface GltfParseResult {
    data class Parsed(val document: GltfDocument) : GltfParseResult

    data class Malformed(val reason: GltfReject) : GltfParseResult
}
