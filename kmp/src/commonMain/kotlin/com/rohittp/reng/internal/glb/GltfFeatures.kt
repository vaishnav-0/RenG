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

/** glTF's default primitive topology and the only one ADR 0021 admits. */
private const val SUPPORTED_PRIMITIVE_MODE = 4

/** The only embedded image media type RenG decodes. */
private const val SUPPORTED_IMAGE_MEDIA_TYPE = "image/png"

/** Attribute semantics ADR 0021 admits. Anything else -- `TEXCOORD_n`/`COLOR_n` above zero,
 * `JOINTS_n`, `WEIGHTS_n`, or an application-specific `_CUSTOM` name -- is
 * [GltfUnsupported.ATTRIBUTE_SEMANTIC]. */
private val SUPPORTED_ATTRIBUTE_SEMANTICS = setOf("POSITION", "NORMAL", "TEXCOORD_0", "TANGENT", "COLOR_0")

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
 * - [ATTRIBUTE_SEMANTIC] covers any primitive attribute semantic outside
 *   [SUPPORTED_ATTRIBUTE_SEMANTICS]: `TEXCOORD_n`/`COLOR_n` above zero, `JOINTS_n`, `WEIGHTS_n`,
 *   and any application-specific `_`-prefixed name. Checked after [SKIN]: a skinned mesh carries
 *   both the flagged `JOINTS_0`/`WEIGHTS_0` attributes and a `node.skin` reference, and stripping
 *   just the attributes would not fix the file, since the skin reference remains and export fails
 *   again next round. [SKIN] names the feature the consumer must actually remove.
 * - [SKIN] covers any node carrying a `skin` index. Checked before [ATTRIBUTE_SEMANTIC] for exactly
 *   the reason given there.
 * - [MORPH_TARGET] covers any primitive whose `targets` array is non-empty.
 * - [ANIMATION_TARGET_PATH] covers any animation channel whose `target.path` is not one of
 *   `translation`, `rotation`, `scale` -- in practice, `weights`.
 * - [INTERPOLATION] covers any animation sampler whose `interpolation` is not `LINEAR` or `STEP`
 *   -- in practice, `CUBICSPLINE`.
 * - [IMAGE_MEDIA_TYPE] covers any image whose `mimeType` is not `image/png`, including an image
 *   with no declared `mimeType` at all. Checked only after [EXTERNAL_URI] on the same image, since
 *   a `uri`-sourced image legitimately omits `mimeType`.
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
 * - [ATTRIBUTE_FORMAT] covers an attribute semantic RenG *does* admit whose accessor `type`,
 *   `componentType` or `normalized` flag is one RenG does not bind: `POSITION` and `NORMAL` are
 *   `VEC3` float, `TANGENT` is `VEC4` float, `TEXCOORD_0` is `VEC2` float or normalized unsigned
 *   byte/short, and `COLOR_0` is `VEC3`/`VEC4` float or normalized unsigned byte/short. ADR 0021's
 *   accept list names the six component types as one flat set across every accessor, which is the
 *   right granularity for the *parser* and the wrong one for the renderer -- a `SCALAR`/`BYTE`
 *   `POSITION` has no draw behaviour at all. Reported here rather than as malformation because
 *   `KHR_mesh_quantization` makes several of these combinations specification-legal, and
 *   [EXTENSION_REQUIRED] is checked first, so a quantized asset is still diagnosed by the
 *   extension it declares rather than reported as a corrupt file.
 * - [ANIMATION_ACCESSOR_FORMAT] covers an animation sampler whose `input` is not a `SCALAR` float
 *   accessor, or whose `output` does not match the format the channel's own `target.path`
 *   requires: `VEC3` float for `translation` and `scale`, and `VEC4` float or normalized signed or
 *   unsigned byte/short for `rotation`. The same reasoning as [ATTRIBUTE_FORMAT] applies -- a
 *   sampler RenG cannot read is a sampling capability RenG lacks, and reporting it here keeps a
 *   document whose real problem is a required extension out of the malformation vocabulary. Only
 *   samplers a channel actually references are checked, since an unreferenced sampler is never
 *   sampled. Checked after [ANIMATION_TARGET_PATH] and [INTERPOLATION], both of which name a
 *   feature the consumer removes rather than a format they re-export.
 */
internal enum class GltfUnsupported {
    EXTENSION_REQUIRED,
    ACCESSOR_WITHOUT_BUFFER_VIEW,
    SPARSE_ACCESSOR,
    PRIMITIVE_MODE,
    ATTRIBUTE_SEMANTIC,
    SKIN,
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
        validateImages()
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

    private fun validateImages() {
        for (image in document.images) {
            if (image.uri != null) reject(GltfUnsupported.EXTERNAL_URI)
            if (image.mimeType != SUPPORTED_IMAGE_MEDIA_TYPE) reject(GltfUnsupported.IMAGE_MEDIA_TYPE)
        }
    }

    private fun validateMeshes() {
        for (mesh in document.meshes) {
            for (primitive in mesh.primitives) {
                if (primitive.mode != SUPPORTED_PRIMITIVE_MODE) reject(GltfUnsupported.PRIMITIVE_MODE)
                for (semantic in primitive.attributes.keys) {
                    if (semantic !in SUPPORTED_ATTRIBUTE_SEMANTICS) reject(GltfUnsupported.ATTRIBUTE_SEMANTIC)
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
     * accessor table. [semantic] is always one of [SUPPORTED_ATTRIBUTE_SEMANTICS] by the time this
     * runs -- anything else is already [GltfUnsupported.ATTRIBUTE_SEMANTIC] -- so the `else` branch
     * is unreachable and admits rather than rejects, keeping the two codes' territories disjoint. */
    private fun bindsAttribute(semantic: String, accessor: GltfAccessor): Boolean = when (semantic) {
        "POSITION", "NORMAL" -> accessor.type == "VEC3" && isPlainFloat(accessor)
        "TANGENT" -> accessor.type == "VEC4" && isPlainFloat(accessor)
        "TEXCOORD_0" -> accessor.type == "VEC2" && isFloatOrNormalizedUnsigned(accessor)
        "COLOR_0" -> (accessor.type == "VEC3" || accessor.type == "VEC4") &&
            isFloatOrNormalizedUnsigned(accessor)
        else -> true
    }

    private fun validateNodes() {
        for (node in document.nodes) {
            if (node.skin != null) reject(GltfUnsupported.SKIN)
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
