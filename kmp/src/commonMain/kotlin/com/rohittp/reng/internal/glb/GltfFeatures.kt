package com.rohittp.reng.internal.glb

/** The specification's `componentType` enumeration, named rather than spelled at each use.
 * [COMPONENT_TYPE_FLOAT] and [COMPONENT_TYPE_UNSIGNED_INT] are the two the specification forbids
 * [GltfAccessor.normalized] on (see [GltfUnsupported.NORMALIZED_NOT_PERMITTED]); the remaining four
 * appear in the per-role format tables below. */
private const val COMPONENT_TYPE_FLOAT = 5126
private const val COMPONENT_TYPE_UNSIGNED_INT = 5125
private const val COMPONENT_TYPE_BYTE = 5120
private const val COMPONENT_TYPE_UNSIGNED_BYTE = 5121
private const val COMPONENT_TYPE_SHORT = 5122
private const val COMPONENT_TYPE_UNSIGNED_SHORT = 5123

/** The attribute semantic every drawable primitive must carry: without vertex positions there is
 * nothing to place, and RenG never substitutes a default. */
private const val POSITION_SEMANTIC = "POSITION"

/** The one influence set RenG binds: four joint indices and their four weights per vertex. Both
 * are required on every primitive of a mesh a skinned node draws
 * ([GltfUnsupported.SKINNED_PRIMITIVE_ATTRIBUTES]). */
private const val JOINTS_SEMANTIC = "JOINTS_0"
private const val WEIGHTS_SEMANTIC = "WEIGHTS_0"

/** The sampler state the specification enumerates. `magFilter` has no mipmapped form -- there is
 * nothing to minify when magnifying -- which is why the two filter sets differ. */
private val SUPPORTED_MAGNIFICATION_FILTERS = setOf(9728, 9729)
private val SUPPORTED_MINIFICATION_FILTERS = setOf(9728, 9729, 9984, 9985, 9986, 9987)
private val SUPPORTED_WRAP_MODES = setOf(33071, 33648, 10497)

/** glTF's default primitive topology and the only one ADR 0021 admits. */
private const val SUPPORTED_PRIMITIVE_MODE = 4

/** The only embedded image media type RenG decodes. */
private const val SUPPORTED_IMAGE_MEDIA_TYPE = "image/png"

/** The most joints one skin may declare. GLES 3.0 and GL 3.3 both guarantee a
 * `GL_MAX_UNIFORM_BLOCK_SIZE` of at least 16384 bytes, which is exactly 256 `mat4`, so a rig at
 * this cap still fits the smallest uniform block either specification promises. The largest rig in
 * the consumer's corpus is 112 joints, so this is 2.3x headroom over the largest asset actually
 * shipped. A lower cap was rejected on the measurement that rejected a uniform *array* outright:
 * any cap a uniform array can hold is below what the consumer already ships. */
internal const val MAXIMUM_SKIN_JOINTS: Int = 256

/** The one texture coordinate set RenG binds. A material naming any other set is
 * [GltfUnsupported.TEXTURE_COORDINATE_SET]. */
private const val BOUND_TEXTURE_COORDINATE_SET = 0

/** Whether [name] is an attribute semantic this subset admits: `POSITION`, `NORMAL`, `TANGENT`, and
 * every `TEXCOORD_n` and `COLOR_n` set rather than set zero alone. RenG reads set zero and nothing
 * else, so a second set carries data it never binds -- and refusing it rejected 14 of the 41 models
 * the consumer ships, 10 of them for nothing else
 * (`docs/research/2026-08-22-consumer-model-corpus-check.md`). Widening a subset is a compatible
 * change under ADR 0021's own reasoning. Anything else -- `JOINTS_n`, `WEIGHTS_n`, an
 * application-specific `_CUSTOM` name, or a non-canonical index such as `TEXCOORD_01` -- is
 * [GltfUnsupported.ATTRIBUTE_SEMANTIC]. */
private fun isSupportedSemantic(name: String): Boolean = when {
    name == POSITION_SEMANTIC || name == "NORMAL" || name == "TANGENT" -> true
    name == JOINTS_SEMANTIC || name == WEIGHTS_SEMANTIC -> true
    name.startsWith("TEXCOORD_") -> isCanonicalSetIndex(name.removePrefix("TEXCOORD_"))
    name.startsWith("COLOR_") -> isCanonicalSetIndex(name.removePrefix("COLOR_"))
    else -> false
}

/** Whether [name] is an influence set above set zero -- `JOINTS_1`, `WEIGHTS_2` and so on. Separated
 * from [isSupportedSemantic]'s `false` because the two say different things: an extra influence set
 * is data RenG *would* have to read and cannot ([GltfUnsupported.MULTIPLE_SKIN_INFLUENCE_SETS]),
 * whereas an unknown semantic is data no renderer could act on. A non-canonical index such as
 * `JOINTS_01` is neither: it names nothing, and stays [GltfUnsupported.ATTRIBUTE_SEMANTIC]. */
private fun isAdditionalInfluenceSet(name: String): Boolean = when {
    name.startsWith("JOINTS_") -> isSetIndexAboveZero(name.removePrefix("JOINTS_"))
    name.startsWith("WEIGHTS_") -> isSetIndexAboveZero(name.removePrefix("WEIGHTS_"))
    else -> false
}

private fun isSetIndexAboveZero(text: String): Boolean = isCanonicalSetIndex(text) && text != "0"

/** A glTF set index is a canonical non-negative decimal integer: `0`, or digits with no leading
 * zero. `TEXCOORD_01` is not a semantic the specification defines, so admitting it would widen the
 * subset past anything an exporter writes, for a name no consumer can act on. */
private fun isCanonicalSetIndex(text: String): Boolean = when {
    text.isEmpty() -> false
    text.any { it !in '0'..'9' } -> false
    else -> text == "0" || text[0] != '0'
}

/** Animation channel target paths RenG plays. `weights` -- morph-target weight animation -- is
 * outside the subset because morph targets themselves are ([GltfUnsupported.MORPH_TARGET]). */
private val SUPPORTED_ANIMATION_TARGET_PATHS = setOf("translation", "rotation", "scale")

/** Animation sampler interpolations RenG plays. `CUBICSPLINE` is rejected rather than
 * approximated: substituting `LINEAR` would silently change motion (ADR 0021). */
private val SUPPORTED_INTERPOLATIONS = setOf("LINEAR", "STEP")

/** The four component types the specification permits for a normalized quaternion `rotation`
 * animation output, alongside plain float. */
private val NORMALIZED_ROTATION_TYPES = setOf(
    COMPONENT_TYPE_BYTE, COMPONENT_TYPE_UNSIGNED_BYTE, COMPONENT_TYPE_SHORT, COMPONENT_TYPE_UNSIGNED_SHORT,
)

/** Plain float: the specification forbids `normalized` on a float accessor outright (that is
 * [GltfUnsupported.NORMALIZED_NOT_PERMITTED]), so this reads as one condition wherever a slot
 * accepts float and nothing else. */
private fun isPlainFloat(accessor: GltfAccessor): Boolean =
    accessor.componentType == COMPONENT_TYPE_FLOAT && !accessor.normalized

/** The vertex-attribute quantization the specification allows without an extension: float, or an
 * unsigned byte or short carrying `normalized: true`. An unnormalized integer in one of those
 * slots would be read as a raw count rather than a fraction, which is not the same attribute. */
private fun isFloatOrNormalizedUnsigned(accessor: GltfAccessor): Boolean = when (accessor.componentType) {
    COMPONENT_TYPE_FLOAT -> !accessor.normalized
    COMPONENT_TYPE_UNSIGNED_BYTE, COMPONENT_TYPE_UNSIGNED_SHORT -> accessor.normalized
    else -> false
}

/** A joint index addresses the skin's joint list directly, so the specification types it as an
 * unnormalized unsigned byte or short. Read as a fraction -- which is what `normalized` means -- it
 * would address joint zero and nothing else. */
private fun isUnnormalizedUnsignedByteOrShort(accessor: GltfAccessor): Boolean = !accessor.normalized &&
    (accessor.componentType == COMPONENT_TYPE_UNSIGNED_BYTE || accessor.componentType == COMPONENT_TYPE_UNSIGNED_SHORT)

/**
 * The outcome of [validateGltfFeatures]: either [document] draws entirely within the subset ADR
 * 0021 admits, or the first feature outside it, reported as [Unsupported.reason].
 */
internal sealed interface GltfFeatureResult {
    data object Supported : GltfFeatureResult

    data class Unsupported(val reason: GltfUnsupported) : GltfFeatureResult
}

/**
 * Every reason [validateGltfFeatures] can refuse a document. Unlike [GltfReject], every one of
 * these documents is a perfectly valid glTF 2.0 asset -- ADR 0021 draws the line here, not at
 * malformation, so `PARSE_GLB` must never report any of these.
 *
 * - [EXTENSION_REQUIRED] covers any non-empty `extensionsRequired`, one blanket rule that covers
 *   Draco, meshopt, Basis and every future compression extension without going stale. Checked
 *   first and unconditionally: a Draco-shaped accessor with no `bufferView` is reported here, not
 *   as [ACCESSOR_WITHOUT_BUFFER_VIEW], because the true fault is the required extension, not the
 *   accessor shape that extension happens to produce.
 * - [ACCESSOR_WITHOUT_BUFFER_VIEW] covers an accessor with no `bufferView` when
 *   `extensionsRequired` is empty -- the specification's "all zeros" form, legal but drawing
 *   nothing RenG can distinguish from a genuine compression extension's absence.
 * - [SPARSE_ACCESSOR] covers any accessor carrying a `sparse` member. Checked before
 *   [ACCESSOR_WITHOUT_BUFFER_VIEW] on the same accessor, since a sparse accessor's `bufferView`
 *   being absent is the ordinary base-value form the specification itself permits for sparse
 *   accessors, not the Draco signature that code names.
 * - [PRIMITIVE_MODE] covers any primitive `mode` other than `4` (`TRIANGLES`) -- strips, fans,
 *   points and lines.
 * - [ATTRIBUTE_SEMANTIC] covers any primitive attribute semantic [isSupportedSemantic] does not
 *   admit: any application-specific `_`-prefixed name, and any `TEXCOORD_`/`COLOR_`/`JOINTS_`/
 *   `WEIGHTS_` name whose index is not a canonical non-negative integer, such as `TEXCOORD_01`.
 *   Two families of semantic are deliberately *not* covered. A second or later
 *   `TEXCOORD_n`/`COLOR_n` set is admitted and ignored -- RenG reads set zero only, so the rest is
 *   data it never binds, and refusing it rejected 14 of the consumer's 41 models. A second
 *   influence set is refused, but as [MULTIPLE_SKIN_INFLUENCE_SETS], which says which of the two
 *   possible faults it actually is.
 * - [MORPH_TARGET] covers any primitive whose `targets` array is non-empty. Re-decided rather than
 *   inherited when models were implemented: zero of the consumer's 41 models carries a single
 *   morph target, so deferring them is a measurement rather than an assumption.
 * - [ANIMATION_TARGET_PATH] covers any animation channel whose `target.path` is not one of
 *   `translation`, `rotation`, `scale` -- in practice, `weights`.
 * - [INTERPOLATION] covers any animation sampler whose `interpolation` is not `LINEAR` or `STEP`
 *   -- in practice, `CUBICSPLINE`.
 * - [IMAGE_MEDIA_TYPE] covers any image whose `mimeType` is not `image/png`, including an image
 *   with no declared `mimeType` at all. Checked only after [EXTERNAL_URI] on the same image, since
 *   a `uri`-sourced image legitimately omits `mimeType`. Re-decided rather than inherited when
 *   models were implemented: exactly one of the consumer's 41 models carries a JPEG texture, and a
 *   decoder RenG does not have is not worth building for a single asset. That model stays refused
 *   by a named code rather than silently, and the cheaper fix is upstream -- re-export it as PNG.
 *   It is the whole of why this cycle reaches 40 of 41 models rather than 41.
 * - [EXTERNAL_URI] covers any buffer or image carrying a `uri`, `data:` URIs included: a resource
 *   named inside a GLB has no Resource Locator, no Resource Class, and no place in the operation's
 *   route set, so RenG has no correct way to resolve it (ADR 0021).
 * - [MULTIPLE_BUFFERS] covers a document declaring more than one buffer. RenG's only supported
 *   buffer form is `buffers[0]` embedded in the GLB's BIN chunk; `PARSE_GLB` tolerates a second
 *   declared buffer structurally (nothing about it is malformed on its own), but RenG has no route
 *   to any buffer but the first.
 * - [SCENE_AMBIGUOUS] covers `scene` absent when `scenes` has zero, or two or more, entries --
 *   there is no single default scene to draw.
 * - [NORMALIZED_NOT_PERMITTED] covers an accessor with `normalized: true` whose `componentType` is
 *   `FLOAT` or `UNSIGNED_INT`, a combination the specification itself forbids.
 * - [PRIMITIVE_WITHOUT_POSITION] covers a primitive whose `attributes` names no `POSITION`. The
 *   specification permits the document -- it says a client "SHOULD skip" such a primitive, and an
 *   extension may supply positions by other means -- so this is a capability statement, not
 *   malformation, and `PARSE_GLB` must stay silent about it. RenG refuses rather than skipping
 *   because silently drawing part of a model is exactly the kind of quiet fallback ADR 0021
 *   rejects elsewhere. Checked after [EXTENSION_REQUIRED], so an extension-supplied geometry is
 *   reported by the extension it needs.
 * - [ATTRIBUTE_FORMAT] covers an accessor RenG *does* read whose `type`, `componentType` or
 *   `normalized` flag is one RenG does not bind: `POSITION` and `NORMAL` are `VEC3` float,
 *   `TANGENT` is `VEC4` float, `TEXCOORD_0` is `VEC2` float or normalized unsigned byte/short,
 *   `COLOR_0` is `VEC3`/`VEC4` float or normalized unsigned byte/short, `JOINTS_0` is an
 *   unnormalized unsigned byte/short `VEC4`, `WEIGHTS_0` is a `VEC4` float or normalized unsigned
 *   byte/short, and a referenced skin's `inverseBindMatrices` is `MAT4` float. An ignored
 *   `TEXCOORD_n`/`COLOR_n` set above zero is never reported here, because RenG never reads it.
 *   ADR 0021's accept list names the six component types as one flat set across every accessor,
 *   which is the right granularity for the *parser* and the wrong one for the renderer -- a
 *   `SCALAR`/`BYTE` `POSITION` has no draw behaviour at all. Reported here rather than as
 *   malformation because `KHR_mesh_quantization` makes several of these combinations
 *   specification-legal, and [EXTENSION_REQUIRED] is checked first, so a quantized asset is still
 *   diagnosed by the extension it declares rather than reported as a corrupt file.
 * - [ANIMATION_ACCESSOR_FORMAT] covers an animation sampler whose `input` is not a `SCALAR` float
 *   accessor, or whose `output` does not match the format the channel's own `target.path`
 *   requires: `VEC3` float for `translation` and `scale`, and `VEC4` float or normalized signed or
 *   unsigned byte/short for `rotation`. The same reasoning as [ATTRIBUTE_FORMAT] applies -- a
 *   sampler RenG cannot read is a sampling capability RenG lacks, and reporting it here keeps a
 *   document whose real problem is a required extension out of the malformation vocabulary. Only
 *   samplers a channel actually references are checked, since an unreferenced sampler is never
 *   sampled. Checked after [ANIMATION_TARGET_PATH] and [INTERPOLATION], both of which name a
 *   feature the consumer removes rather than a format they re-export.
 * - [TEXTURE_COORDINATE_SET] covers a material whose `pbrMetallicRoughness.baseColorTexture.texCoord`
 *   is not `0`. RenG binds `TEXCOORD_0` and only `TEXCOORD_0`, so a material asking for another set
 *   has no correct render, and sampling the set RenG does bind would be the silent fallback ADR 0021
 *   refuses everywhere else. Only the base-colour slot is checked: the four secondary slots are
 *   retained and never sampled, so the coordinate set they name is not one RenG binds either way.
 * - [MULTIPLE_SKIN_INFLUENCE_SETS] covers a primitive carrying `JOINTS_n`/`WEIGHTS_n` above set
 *   zero: more than four bone influences on one vertex. RenG binds one influence set, so a second
 *   would have to be dropped, and dropping influences deforms the mesh differently rather than
 *   not at all -- a silent wrong picture, which is what this gate exists to prevent.
 * - [SKIN_JOINT_COUNT] covers a skin some node references declaring more than
 *   [MAXIMUM_SKIN_JOINTS] joints. The cap is the uniform block the joint matrices are uploaded
 *   through; refusing here makes an oversized rig a typed refusal rather than a driver-level
 *   overflow later. An unreferenced skin is not checked -- it is exporter debris that deforms
 *   nothing.
 * - [SKINNED_PRIMITIVE_ATTRIBUTES] covers a primitive of a mesh drawn by a node with a `skin` that
 *   does not carry both `JOINTS_0` and `WEIGHTS_0`. The specification requires them; without them
 *   there is no per-vertex binding to the rig, so RenG would have to draw the bind pose and call
 *   it the animation.
 * - [SAMPLER_STATE] covers a sampler whose `magFilter`, `minFilter`, `wrapS` or `wrapT` is outside
 *   the GL enumeration the specification permits. Rejected rather than clamped: substituting a
 *   filter or a wrap mode silently changes the picture, the fallback ADR 0021 refuses everywhere
 *   else.
 */
internal enum class GltfUnsupported {
    EXTENSION_REQUIRED,
    ACCESSOR_WITHOUT_BUFFER_VIEW,
    SPARSE_ACCESSOR,
    PRIMITIVE_MODE,
    ATTRIBUTE_SEMANTIC,
    MORPH_TARGET,
    ANIMATION_TARGET_PATH,
    INTERPOLATION,
    IMAGE_MEDIA_TYPE,
    EXTERNAL_URI,
    MULTIPLE_BUFFERS,
    SCENE_AMBIGUOUS,
    NORMALIZED_NOT_PERMITTED,
    PRIMITIVE_WITHOUT_POSITION,
    ATTRIBUTE_FORMAT,
    ANIMATION_ACCESSOR_FORMAT,
    TEXTURE_COORDINATE_SET,
    MULTIPLE_SKIN_INFLUENCE_SETS,
    SKIN_JOINT_COUNT,
    SKINNED_PRIMITIVE_ATTRIBUTES,
    SAMPLER_STATE,
}

/**
 * `VALIDATE_GLB_FEATURES`: refuses a [document] that `PARSE_GLB` ([parseGltf]) already accepted as
 * a well-formed glTF 2.0 asset, but which draws outside the subset ADR 0021 admits. Every check
 * here answers "is this a feature RenG draws", never "is this bytes malformed" -- that split is
 * ADR 0021's, not this function's to blur.
 */
internal fun validateGltfFeatures(document: GltfDocument): GltfFeatureResult =
    try {
        GltfFeatureValidator(document).validate()
        GltfFeatureResult.Supported
    } catch (signal: GltfUnsupportedSignal) {
        GltfFeatureResult.Unsupported(signal.reason)
    }

/** Internal control-flow signal: unwound by [validateGltfFeatures] into a
 * [GltfFeatureResult.Unsupported], never seen outside this file. */
private class GltfUnsupportedSignal(val reason: GltfUnsupported) : RuntimeException()

private fun reject(reason: GltfUnsupported): Nothing = throw GltfUnsupportedSignal(reason)

private class GltfFeatureValidator(private val document: GltfDocument) {
    fun validate() {
        if (document.extensionsRequired.isNotEmpty()) reject(GltfUnsupported.EXTENSION_REQUIRED)

        validateBuffers()
        validateAccessors()
        validateSkins()
        validateImages()
        validateSamplers()
        validateMaterials()
        validateNodes()
        validateMeshes()
        validateAnimations()
        validateScene()
    }

    private fun validateBuffers() {
        for (buffer in document.buffers) {
            if (buffer.uri != null) reject(GltfUnsupported.EXTERNAL_URI)
        }
        if (document.buffers.size > 1) reject(GltfUnsupported.MULTIPLE_BUFFERS)
    }

    private fun validateAccessors() {
        for (accessor in document.accessors) {
            if (accessor.sparse) reject(GltfUnsupported.SPARSE_ACCESSOR)
            if (accessor.bufferView == null) reject(GltfUnsupported.ACCESSOR_WITHOUT_BUFFER_VIEW)
            val forbidsNormalized = accessor.componentType == COMPONENT_TYPE_FLOAT ||
                accessor.componentType == COMPONENT_TYPE_UNSIGNED_INT
            if (accessor.normalized && forbidsNormalized) reject(GltfUnsupported.NORMALIZED_NOT_PERMITTED)
        }
    }

    /** The one accessor a skin owns, checked for the same per-role reason every attribute
     * accessor is: RenG multiplies one `mat4` per joint, and nothing else has that meaning. Only a
     * skin some node references is checked -- an unreferenced `skins` array is exporter debris that
     * deforms nothing, which 18 of the consumer's 41 models carry -- and the joint cap is applied
     * on the same footing, for the same reason. */
    private fun validateSkins() {
        val referenced = document.nodes.mapNotNull { it.skin }.toSet()
        for (index in document.skins.indices) {
            if (index !in referenced) continue
            val skin = document.skins[index]
            if (skin.joints.size > MAXIMUM_SKIN_JOINTS) reject(GltfUnsupported.SKIN_JOINT_COUNT)
            val accessor = document.accessors[skin.inverseBindMatrices ?: continue]
            if (accessor.type != "MAT4" || !isPlainFloat(accessor)) reject(GltfUnsupported.ATTRIBUTE_FORMAT)
        }
    }

    /** Every filter and wrap mode the document declares, against the enumeration the specification
     * itself permits. Rejected rather than clamped: substituting a filter or a wrap mode silently
     * changes the picture, which is the fallback ADR 0021 refuses everywhere else. An absent filter
     * is not a fault -- the specification leaves it to the client. */
    private fun validateSamplers() {
        for (sampler in document.samplers) {
            val supported = (sampler.magFilter?.let { it in SUPPORTED_MAGNIFICATION_FILTERS } ?: true) &&
                (sampler.minFilter?.let { it in SUPPORTED_MINIFICATION_FILTERS } ?: true) &&
                sampler.wrapS in SUPPORTED_WRAP_MODES &&
                sampler.wrapT in SUPPORTED_WRAP_MODES
            if (!supported) reject(GltfUnsupported.SAMPLER_STATE)
        }
    }

    private fun validateImages() {
        for (image in document.images) {
            if (image.uri != null) reject(GltfUnsupported.EXTERNAL_URI)
            if (image.mimeType != SUPPORTED_IMAGE_MEDIA_TYPE) reject(GltfUnsupported.IMAGE_MEDIA_TYPE)
        }
    }

    /** RenG samples exactly one texture -- the base colour -- and binds
     * `TEXCOORD_`[BOUND_TEXTURE_COORDINATE_SET] to sample it with. A material asking for another
     * coordinate set therefore has no correct render, and sampling the set RenG does bind instead
     * would be a silent substitution. The four secondary slots are not checked: they are retained
     * and never sampled, so the set they name is one RenG never binds either way -- the same
     * reason an extra `TEXCOORD_n` attribute is admitted without a format check. */
    private fun validateMaterials() {
        for (material in document.materials) {
            val baseColorTexture = material.pbrMetallicRoughness?.baseColorTexture ?: continue
            if (baseColorTexture.texCoord != BOUND_TEXTURE_COORDINATE_SET) {
                reject(GltfUnsupported.TEXTURE_COORDINATE_SET)
            }
        }
    }

    private fun validateMeshes() {
        for (mesh in document.meshes) {
            for (primitive in mesh.primitives) {
                if (primitive.mode != SUPPORTED_PRIMITIVE_MODE) reject(GltfUnsupported.PRIMITIVE_MODE)
                for (semantic in primitive.attributes.keys) {
                    if (isAdditionalInfluenceSet(semantic)) {
                        reject(GltfUnsupported.MULTIPLE_SKIN_INFLUENCE_SETS)
                    }
                    if (!isSupportedSemantic(semantic)) reject(GltfUnsupported.ATTRIBUTE_SEMANTIC)
                }
                if (primitive.targetCount > 0) reject(GltfUnsupported.MORPH_TARGET)
                if (POSITION_SEMANTIC !in primitive.attributes) {
                    reject(GltfUnsupported.PRIMITIVE_WITHOUT_POSITION)
                }
                for ((semantic, accessorIndex) in primitive.attributes) {
                    if (!bindsAttribute(semantic, document.accessors[accessorIndex])) {
                        reject(GltfUnsupported.ATTRIBUTE_FORMAT)
                    }
                }
            }
        }
    }

    /** Whether RenG binds [accessor] for [semantic], per the specification's own per-semantic
     * accessor table. [semantic] has already passed [isSupportedSemantic] by the time this runs --
     * anything else is [GltfUnsupported.ATTRIBUTE_SEMANTIC] -- so the `else` branch covers exactly
     * the admitted sets RenG ignores: `TEXCOORD_n` and `COLOR_n` above zero. Those are admitted
     * *without checking*, deliberately. An attribute RenG never reads cannot have a format RenG
     * cannot bind, and format-checking one would reject a model over bytes no draw call touches --
     * which is the rejection this cycle just removed. */
    private fun bindsAttribute(semantic: String, accessor: GltfAccessor): Boolean = when (semantic) {
        "POSITION", "NORMAL" -> accessor.type == "VEC3" && isPlainFloat(accessor)
        "TANGENT" -> accessor.type == "VEC4" && isPlainFloat(accessor)
        "TEXCOORD_0" -> accessor.type == "VEC2" && isFloatOrNormalizedUnsigned(accessor)
        "COLOR_0" -> (accessor.type == "VEC3" || accessor.type == "VEC4") &&
            isFloatOrNormalizedUnsigned(accessor)
        JOINTS_SEMANTIC -> accessor.type == "VEC4" && isUnnormalizedUnsignedByteOrShort(accessor)
        WEIGHTS_SEMANTIC -> accessor.type == "VEC4" && isFloatOrNormalizedUnsigned(accessor)
        else -> true
    }

    /** A node's `skin` is what makes its mesh vertex-skinned, and the specification then requires
     * every primitive of that mesh to carry both halves of the influence set. Without them there is
     * no per-vertex binding to the rig at all, so RenG would have to draw the bind pose and report
     * the animation as played. A skinned node with no mesh deforms nothing and is left alone,
     * exactly as an unreferenced skin is: refusing either would refuse a drawable model over
     * exporter debris. */
    private fun validateNodes() {
        for (node in document.nodes) {
            if (node.skin == null) continue
            val mesh = document.meshes[node.mesh ?: continue]
            for (primitive in mesh.primitives) {
                if (JOINTS_SEMANTIC !in primitive.attributes || WEIGHTS_SEMANTIC !in primitive.attributes) {
                    reject(GltfUnsupported.SKINNED_PRIMITIVE_ATTRIBUTES)
                }
            }
        }
    }

    private fun validateAnimations() {
        for (animation in document.animations) {
            for (channel in animation.channels) {
                if (channel.targetPath !in SUPPORTED_ANIMATION_TARGET_PATHS) {
                    reject(GltfUnsupported.ANIMATION_TARGET_PATH)
                }
            }
            for (sampler in animation.samplers) {
                if (sampler.interpolation !in SUPPORTED_INTERPOLATIONS) reject(GltfUnsupported.INTERPOLATION)
            }
            for (channel in animation.channels) {
                validateSamplerFormat(animation.samplers[channel.sampler], channel.targetPath)
            }
        }
    }

    /** The specification's per-path animation accessor table: keyframe times are always `SCALAR`
     * float, `translation` and `scale` outputs are `VEC3` float, and a `rotation` output is a
     * `VEC4` quaternion either in float or normalized to a signed or unsigned byte or short. Read
     * per *channel* rather than per sampler because the output's required shape depends on the
     * path the channel drives, and one sampler may in principle be named by more than one. */
    private fun validateSamplerFormat(sampler: GltfAnimationSampler, targetPath: String) {
        val input = document.accessors[sampler.input]
        if (input.type != "SCALAR" || !isPlainFloat(input)) {
            reject(GltfUnsupported.ANIMATION_ACCESSOR_FORMAT)
        }
        val output = document.accessors[sampler.output]
        val binds = when (targetPath) {
            "translation", "scale" -> output.type == "VEC3" && isPlainFloat(output)
            "rotation" -> output.type == "VEC4" &&
                (isPlainFloat(output) || (output.normalized && output.componentType in NORMALIZED_ROTATION_TYPES))
            else -> true
        }
        if (!binds) reject(GltfUnsupported.ANIMATION_ACCESSOR_FORMAT)
    }

    private fun validateScene() {
        if (document.defaultScene == null && document.scenes.size != 1) {
            reject(GltfUnsupported.SCENE_AMBIGUOUS)
        }
    }
}
