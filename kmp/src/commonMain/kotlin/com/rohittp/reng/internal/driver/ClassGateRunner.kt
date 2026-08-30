package com.rohittp.reng.internal.driver

import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.glb.GlbScan
import com.rohittp.reng.internal.glb.GltfFeatureResult
import com.rohittp.reng.internal.glb.GltfParseResult
import com.rohittp.reng.internal.glb.MAXIMUM_GLB_NODE_DEPTH
import com.rohittp.reng.internal.glb.parseGltf
import com.rohittp.reng.internal.glb.scanGlb
import com.rohittp.reng.internal.glb.validateGltfFeatures
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceClassGate
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome

/**
 * Runs exactly one [ResourceClassGate] over already-resolved content and reports whether it is
 * [SuppliedValidationOutcome.Valid] or [SuppliedValidationOutcome.Failed]. The gate's identity alone
 * decides which check runs; [ResourceOperationStateMachine][com.rohittp.reng.internal.resource.ResourceOperationStateMachine]
 * decides the [com.rohittp.reng.RenGErrorCode] a `Failed` outcome maps to (decode gates report
 * `RESOURCE_DECODE_FAILED`, feature gates report `UNSUPPORTED_RESOURCE_FEATURE`, and STORE-provenance
 * content collapses either into `STORE_INTEGRITY_FAILED`) — this interface never carries a reason code,
 * only the two outcomes that decision needs.
 */
internal fun interface ClassGateRunner {
    suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome
}

/**
 * RenG's own class gates, and only those. `DECODE_PNG` genuinely decodes a sticker or model-texture PNG
 * through [decodePng], gated by [limits]'s decoded-byte ceiling; `PARSE_GLB`/`VALIDATE_GLB_FEATURES`
 * genuinely scan and parse a GLB container's JSON chunk into a
 * [com.rohittp.reng.internal.glb.GltfDocument] via the canonical
 * [scanGlb]/[parseGltf]/[validateGltfFeatures] (ADR 0021's complete supported subset).
 *
 * The gate's identity alone selects the check, with no second discrimination on the content's resource
 * class, because [com.rohittp.reng.internal.resource.ordinaryResourceClassGates] is what pairs the two:
 * it names `DECODE_PNG` for exactly `STICKER_IMAGE` and `MODEL_TEXTURE`, and
 * [com.rohittp.reng.internal.resource.AwaitingClassGate]'s own `init` refuses any cursor whose gate is
 * not that class's gate at that index. Nothing engine-keyed reaches here: the Rentile engine acquires and
 * validates its own seven classes through RenG's firewall, so RenG's driver never routes one.
 *
 * **GLB wiring:** `PARSE_GLB` and `VALIDATE_GLB_FEATURES` each independently re-derive the parsed
 * [com.rohittp.reng.internal.glb.GltfDocument] from [ResolvedResourceContent.stored]'s raw bytes via
 * [parseGlbDocument] — the same redundancy [runDecodePng] already accepts for PNG, since this interface
 * answers one gate at a time with no result cache between calls. A [GlbScan] container-framing rejection
 * or a [GltfParseResult.Malformed] structural rejection both fail `PARSE_GLB`; `VALIDATE_GLB_FEATURES`
 * additionally fails on either of those (a document `PARSE_GLB` would already have refused can never be
 * "supported"), or on a genuine [GltfFeatureResult.Unsupported] feature outside ADR 0021's subset.
 */
internal class RenGClassGateRunner(private val limits: ResourceLimits) : ClassGateRunner {
    override suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome =
        when (gate) {
            ResourceClassGate.DECODE_PNG -> runDecodePng(content)
            ResourceClassGate.PARSE_GLB -> runParseGlb(content)
            ResourceClassGate.VALIDATE_GLB_FEATURES -> runValidateGlbFeatures(content)
        }

    private fun runDecodePng(content: ResolvedResourceContent): SuppliedValidationOutcome =
        when (decodePng(content.stored.bytes, limits.maximumDecodedImageBytes)) {
            is PngDecodeResult.Success -> SuppliedValidationOutcome.Valid
            is PngDecodeResult.Malformed,
            is PngDecodeResult.Unsupported,
            PngDecodeResult.TooLarge,
            -> SuppliedValidationOutcome.Failed
        }

    private fun runParseGlb(content: ResolvedResourceContent): SuppliedValidationOutcome =
        when (parseGlbDocument(content.stored.bytes)) {
            is GltfParseResult.Parsed -> SuppliedValidationOutcome.Valid
            is GltfParseResult.Malformed, null -> SuppliedValidationOutcome.Failed
        }

    private fun runValidateGlbFeatures(content: ResolvedResourceContent): SuppliedValidationOutcome {
        val document = (parseGlbDocument(content.stored.bytes) as? GltfParseResult.Parsed)?.document
            ?: return SuppliedValidationOutcome.Failed
        return when (validateGltfFeatures(document)) {
            GltfFeatureResult.Supported -> SuppliedValidationOutcome.Valid
            is GltfFeatureResult.Unsupported -> SuppliedValidationOutcome.Failed
        }
    }

    /**
     * Runs the container scan and document parse `PARSE_GLB` performs — [scanGlb] bounded by
     * [ResourceLimits.maximumModelJsonChunkBytes], then [parseGltf] over the admitted JSON chunk and BIN
     * chunk length — sharing those exact steps with [runValidateGlbFeatures], which needs the same parsed
     * [com.rohittp.reng.internal.glb.GltfDocument] before it can inspect it. Returns `null` for a
     * container-framing fault ([scanGlb] rejected); a [GltfParseResult.Malformed] result still flows
     * through so callers see `PARSE_GLB`'s own malformation vocabulary rather than a second `null`.
     */
    private fun parseGlbDocument(bytes: ByteArray): GltfParseResult? {
        val admitted = scanGlb(bytes, limits.maximumModelJsonChunkBytes) as? GlbScan.Admitted ?: return null
        return parseGltf(admitted.json, admitted.binChunkLength, MAXIMUM_GLB_NODE_DEPTH)
    }
}

private const val OPAQUE_ALPHA: Byte = -1 // 0xFF unsigned

