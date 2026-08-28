package com.rohittp.reng.internal.firewall

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.resource.isLowercaseSha256
import com.rohittp.rentile.BatchRenderException
import com.rohittp.rentile.RentileErrorCode
import com.rohittp.rentile.RentileException
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.TileSubstitutionException
import com.rohittp.rentile.TileSubstitutionLimitException
import kotlinx.coroutines.CancellationException
import com.rohittp.rentile.ResourceClass as RentileResourceClass

/**
 * How many wrapper failures this classifier will peel before it gives up and fails closed. Rentile's
 * deepest real chain is three -- a [BatchRenderException] around a [TileSubstitutionException] around the
 * [ResourceAcquisitionException] that started it -- so eight is ample headroom, while still being a hard
 * bound: nothing in Rentile's types forbids a chain longer than that three-deep real one, and this
 * classifier does not get to assume the engine's nesting stays shallow just because it does today.
 *
 * A *cyclic* chain is a different matter, and is structurally impossible rather than merely bounded:
 * every `primaryFailure` is an immutable constructor `val`, so an inner failure must already exist
 * before anything can wrap it. The loop is iterative regardless, so a deep chain terminates and fails
 * closed -- it never hangs. The cap is a plain depth bound, not a cycle guard.
 */
private const val MAXIMUM_UNWRAP_DEPTH: Int = 8

/**
 * The one seam where a Rentile engine failure becomes a RenG failure. Nothing else in RenG may read a
 * [RentileException]: everything downstream of here sees only a [FailureDescriptor], which
 * [com.rohittp.reng.internal.failure.toException] renders into the single public failure shape.
 *
 * **Discrimination.** The classification below switches on [RentileErrorCode] -- Rentile's own enum,
 * documented there as the machine-readable contract ("Human exception messages are not contracts") --
 * in a `when` with no `else`. A code a future Rentile adds therefore fails this file's compilation
 * rather than being silently swept into some default bucket, which is the exact failure mode ADR 0016's
 * firewall exists to prevent. The `as?` casts alongside it are belt-and-braces for the same reason:
 * Rentile 0.6.0 pairs each code with exactly one sealed subclass, so they cannot fail today, but a
 * future Rentile that reused a code across two classes would fail closed here instead of throwing a
 * `ClassCastException` out of a function whose whole purpose is to stop engine faults escaping.
 *
 * **Redaction.** Exactly three engine values are ever carried across: the [RentileErrorCode] itself,
 * `resourceClass` (an enum), and `sanitizedResourceId` (already `sha256Hex` of a credential-redacted
 * url). The engine's `message` is dropped whole, the `diagnostics` hanging off a *failure* are never
 * read (Rentile's `RenderDiagnostic` carries free-form `details`), and neither is `affectedTiles`.
 * The one place RenG does read an engine diagnostic is [reportLabelContentExclusions], on a batch
 * that succeeded, and it carries across a severity and nothing else (ADR 0036) -- never a `details`
 * entry, and never here.
 * [com.rohittp.reng.RenGException] has no `cause` parameter at all, so forwarding a cause is
 * structurally impossible. [ResourceAcquisitionException.statusCode] is available and still not carried:
 * RenG's allowlist leaves a status code `FORBIDDEN` at every stage an engine failure can reach.
 *
 * **No cause-walking.** A tempting design -- recovering RenG's own already-sanitized failure from the
 * cause chain when the engine merely relayed it -- does not work and is deliberately not attempted:
 * Rentile's `ResourceStoreException` throw sites pass no cause, and its
 * `DefaultBasemapRasterizer.asSubstitutionFailure` converts a non-Rentile throwable into a
 * [ResourceAcquisitionException] without one either. Classification reads structured fields only.
 *
 * **Cancellation** is rethrown unwrapped before any classification, exactly as
 * [com.rohittp.reng.internal.driver.ResourceActionExecutor] does at its own seam -- a cancelled frame is
 * not a basemap failure, and Kotlin's stack recovery may hand back a copy carrying the original as its
 * immediate cause, so the check is on type, never on identity. A cancellation cannot arrive *wrapped*
 * either: every `primaryFailure` is typed [RentileException], which extends `Exception` directly, while
 * `CancellationException` extends `IllegalStateException` -- no sealed subclass of [RentileException]
 * can be one, so the unwrap loop below needs no second rethrow. A [CancellationException] that merely
 * sits in an engine failure's `cause` is not a cancellation of RenG's frame and is classified normally.
 */
internal fun classifyEngineFailure(failure: Throwable): FailureDescriptor {
    if (failure is CancellationException) throw failure

    var current: RentileException = failure as? RentileException ?: return basemapRenderFailure()
    repeat(MAXIMUM_UNWRAP_DEPTH) {
        val wrapped: RentileException = when (current.code) {
            // Rentile has exactly one StylePreparationException with exactly one code, and every one of
            // its throw sites passes only a message -- no structured discriminator separates a malformed
            // style document from an unsupported style feature, and splitting on the message text is
            // precisely what the redaction rule forbids. So the failure names the stage and nothing else:
            // RESOURCE_PARSE_FAILED at RESOURCE_PARSING takes OPTIONAL_EXTERNAL identity, which makes the
            // *identity* optional but still requires the diagnostic itself (every FailureRule.Context does),
            // so this is the emptiest legal shape -- the `resource` field with no resource named, since
            // that rule admits only `resource` and `animationSelector` and neither an animation nor a
            // resource id exists to report.
            RentileErrorCode.STYLE_PREPARATION_FAILED -> return FailureDescriptor(
                code = RenGErrorCode.RESOURCE_PARSE_FAILED,
                stage = PipelineStage.RESOURCE_PARSING,
                diagnostic = failureContextDiagnostic(
                    stage = PipelineStage.RESOURCE_PARSING,
                    fieldName = DiagnosticField.RESOURCE,
                ),
            )

            RentileErrorCode.RESOURCE_ACQUISITION_FAILED -> {
                val acquisition = current as? ResourceAcquisitionException ?: return basemapRenderFailure()
                return externalResourceFailure(
                    code = RenGErrorCode.RESOURCE_UNAVAILABLE,
                    stage = PipelineStage.RESOURCE_LOOKUP,
                    engineResourceClass = acquisition.resourceClass,
                    sanitizedResourceId = acquisition.sanitizedResourceId,
                )
            }

            RentileErrorCode.RESOURCE_DECODE_FAILED -> {
                val decode = current as? ResourceDecodeException ?: return basemapRenderFailure()
                return externalResourceFailure(
                    code = RenGErrorCode.RESOURCE_DECODE_FAILED,
                    stage = PipelineStage.RESOURCE_DECODING,
                    engineResourceClass = decode.resourceClass,
                    sanitizedResourceId = decode.sanitizedResourceId,
                )
            }

            // Everything the engine can fail at that RenG has no constructible, non-fabricated failure
            // for. `RESOURCE_STORE_FAILED` carries no resource id whatsoever, so STORE_WRITE_FAILED's
            // REQUIRED_EXTERNAL identity cannot be satisfied; `SAFETY_LIMIT_EXCEEDED` is an engine-side
            // limit, and reporting it as RESOURCE_LIMIT_EXCEEDED would claim a RenG limit that was never
            // exceeded. The rest are rasterizer lifecycle and tile-identity faults with no RenG analogue
            // at all. BASEMAP_RENDER_FAILED is honest about exactly that: the basemap did not render.
            RentileErrorCode.RESOURCE_STORE_FAILED,
            RentileErrorCode.SAFETY_LIMIT_EXCEEDED,
            RentileErrorCode.RASTERIZATION_FAILED,
            RentileErrorCode.PNG_ENCODING_FAILED,
            RentileErrorCode.PREPARED_BATCH_CLOSED,
            RentileErrorCode.RASTERIZER_CLOSED,
            RentileErrorCode.FOREIGN_PREPARED_STYLE,
            RentileErrorCode.FOREIGN_PREPARED_BATCH,
            RentileErrorCode.INVALID_TILE_ID,
            RentileErrorCode.TILE_NOT_IN_PREPARED_BATCH,
            -> return basemapRenderFailure()

            // Rentile's three label-candidate codes, and this is the move the older comment here
            // promised: they are raised only from `acquireLabelCandidates` and the
            // `LabelCandidatePlan` it returns, an entry point RenG now calls, so the fail-closed
            // bucket they used to share with the ground's failures reported them at the wrong half
            // of the frame. Each of them means RenG's own defect -- a plan from another rasterizer,
            // a plan read after it was closed, a template disagreement `glyphUrls` did not catch --
            // so there is still nothing truthful for RenG to add beyond the code it already has.
            // What changed is that the stage now names the label plan: the ground drew, and the
            // labels did not. No RenG code of their own, because inventing one per engine code is
            // exactly the vocabulary mirroring ADR 0036 refuses.
            RentileErrorCode.FOREIGN_LABEL_CANDIDATE_PLAN,
            RentileErrorCode.LABEL_CANDIDATE_PLAN_CLOSED,
            RentileErrorCode.GLYPH_TEMPLATE_MISMATCH,
            -> return labelPlanFailure()

            // The three wrapping codes. Each reports its own aggregate shape, not the fault; the fault
            // that actually stopped the frame is the primary failure inside, so classification continues
            // there rather than collapsing a perfectly identifiable acquisition failure into an opaque
            // basemap failure.
            RentileErrorCode.TILE_SUBSTITUTION_LIMIT_EXCEEDED ->
                (current as? TileSubstitutionLimitException)?.primaryFailure ?: return basemapRenderFailure()

            RentileErrorCode.TILE_SUBSTITUTION_FAILED ->
                (current as? TileSubstitutionException)?.primaryFailure ?: return basemapRenderFailure()

            RentileErrorCode.BATCH_RENDER_FAILED ->
                (current as? BatchRenderException)?.primaryFailure ?: return basemapRenderFailure()
        }
        current = wrapped
    }
    return basemapRenderFailure()
}

/**
 * The failure RenG reports when the engine's own failure carries nothing RenG can honestly say more
 * about. [RenGErrorCode.BASEMAP_RENDER_FAILED] is allowlisted at [PipelineStage.BASEMAP_RENDER] and
 * nowhere else, and it permits **no** diagnostic -- [FailureDescriptor]'s `init` rejects one -- so this
 * is the whole of the fail-closed shape, deliberately with no parameters to get wrong.
 */
private fun basemapRenderFailure(): FailureDescriptor =
    FailureDescriptor(code = RenGErrorCode.BASEMAP_RENDER_FAILED, stage = PipelineStage.BASEMAP_RENDER)

/**
 * The same fail-closed shape, reported at the stage that names the label plan
 * ([PipelineStage.LABEL_PREPARATION]) rather than at the ground's. Same code, and deliberately: the
 * engine failing at its label work is not a different *kind* of thing from the engine failing at its
 * tile work, and a second opaque code would say nothing the stage does not already say.
 */
private fun labelPlanFailure(): FailureDescriptor =
    FailureDescriptor(code = RenGErrorCode.BASEMAP_RENDER_FAILED, stage = PipelineStage.LABEL_PREPARATION)

/**
 * Builds the one failure shape RenG's allowlist demands for the two engine classes that expose an
 * identity: `DiagnosticField.RESOURCE` plus an `EXTERNAL` [ResourceKey] carrying a resource class
 * (`IdentityRequirement.REQUIRED_EXTERNAL`). Both target rules leave `statusCode` and the numeric limit
 * `FORBIDDEN`, so [ResourceAcquisitionException.statusCode] is dropped even though the engine offers it
 * -- `Diagnostic`'s own `init` admits a status code only at `TRANSPORT_VALIDATION`, which is not a stage
 * an engine failure can reach.
 *
 * Two ways this can still not be satisfiable, and both fail closed rather than throwing out of a
 * classifier: the engine resource class has no RenG counterpart (a class a future Rentile adds), and the
 * engine's `sanitizedResourceId` is not the lowercase SHA-256 digest [ResourceKey] requires. The digest
 * check is not ceremony -- `ResourceKey`'s `init` would throw on a malformed one, turning a reported
 * engine failure into an unrelated `IllegalArgumentException` escaping the firewall.
 *
 * Note what this identity is and is not: it is Rentile's `sha256Hex(url.withRedactedAuthenticationQuery())`,
 * which is credential-free and stable, but it is **not** RenG's own canonical resource identity
 * (`ResourceKeyDeriver.external`, a canonical-binary digest over kind, class, and locator), so it will not
 * compare equal to the key for the same resource in a `ResourceReportEntry`. The alternative is worse:
 * the allowlist requires an identity here, and RenG does not hold the failing locator at this seam --
 * only the rasterizer host that handed the locators to the engine does.
 *
 * That host exists: [BasemapEngineHost] translates this identity back to RenG's own key on the way out,
 * for every class `OperationRegistry` preregistered a route for. So a descriptor that escapes through the
 * host carries a RenG key; one built here and read directly does not. Do not add a second translation
 * path here -- this seam still does not hold the locator.
 */
private fun externalResourceFailure(
    code: RenGErrorCode,
    stage: PipelineStage,
    engineResourceClass: RentileResourceClass,
    sanitizedResourceId: String,
): FailureDescriptor {
    val resourceClass = rengResourceClassOf(engineResourceClass) ?: return basemapRenderFailure()
    if (!isLowercaseSha256(sanitizedResourceId)) return basemapRenderFailure()

    return FailureDescriptor(
        code = code,
        stage = stage,
        diagnostic = failureContextDiagnostic(
            stage = stage,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = resourceClass,
            resourceKey = ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = sanitizedResourceId,
                resourceClass = resourceClass,
            ),
        ),
    )
}
