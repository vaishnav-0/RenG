package com.rohittp.reng.internal

import com.rohittp.reng.Diagnostic
import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind

internal enum class DiagnosticField(internal val wireName: String) {
    PLANS("plans"),
    FRAME_INDEX("frameIndex"),
    PROJECTION_MODE("projectionMode"),
    CAMERA_LATITUDE("camera.latitude"),
    CAMERA_UNWRAPPED_LONGITUDE("camera.unwrappedLongitude"),
    MAP_POSITION_LATITUDE("mapPosition.latitude"),
    MAP_POSITION_UNWRAPPED_LONGITUDE("mapPosition.unwrappedLongitude"),
    MAP_POSITION_ALTITUDE("mapPosition.altitude"),
    SCREEN_POSITION_X("screenPosition.x"),
    SCREEN_POSITION_Y("screenPosition.y"),
    PLACEMENT_POSITION_MODE("placement.positionMode"),
    PLACEMENT_SCALE("placement.scale"),
    GEOMETRY_LATITUDE("geometry.latitude"),
    GEOMETRY_UNWRAPPED_LONGITUDE("geometry.unwrappedLongitude"),
    GEOMETRY_ALTITUDE("geometry.altitude"),
    BASEMAP_TILE_INSTANCES("basemapTileInstances"),
    RESPONSE_BODY_BYTES("responseBodyBytes"),
    RESOURCE("resource"),
    FRAME_IDENTITY("frameIdentity"),
    ANIMATION_SELECTOR("animationSelector"),
    SHADER_PAIR("shaderPair"),
    RENDER_TARGET("renderTarget"),
}

internal fun failureContextDiagnostic(
    stage: PipelineStage,
    fieldName: DiagnosticField? = null,
    resourceClass: ResourceClass? = null,
    resourceKey: ResourceKey? = null,
    statusCode: Int? = null,
    limit: Long? = null,
    actual: Long? = null,
): Diagnostic =
    Diagnostic(
        code = DiagnosticCode.FAILURE_CONTEXT,
        severity = DiagnosticSeverity.ERROR,
        stage = stage,
        fieldName = fieldName?.wireName,
        resourceClass = resourceClass,
        resourceKey = resourceKey,
        statusCode = statusCode,
        limit = limit,
        actual = actual,
    )

internal fun resourceReloadedAfterFreeDiagnostic(key: ResourceKey): Diagnostic =
    Diagnostic(
        code = DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE,
        severity = DiagnosticSeverity.WARNING,
        stage = PipelineStage.RESOURCE_LOOKUP,
        resourceClass = key.resourceClass,
        resourceKey = key,
    )

/**
 * `drawBasemap` was requested with no configured `basemapStyle`. Warns and degrades rather than
 * failing: running with no map is the whole MVP use case, and stays legitimate once the basemap
 * ships since `basemapStyle` remains nullable then too. Carries no further context — there is
 * nothing more specific to report than "no style was configured."
 */
internal fun basemapNotConfiguredDiagnostic(): Diagnostic =
    Diagnostic(
        code = DiagnosticCode.BASEMAP_NOT_CONFIGURED,
        severity = DiagnosticSeverity.WARNING,
        stage = PipelineStage.BASEMAP_RENDER,
    )

/**
 * A draw ended with more budget-tracked texture bytes resident than
 * [com.rohittp.reng.ResourceLimits.maximumResidentGpuTextureBytes] allows, and eviction could do
 * nothing about it: every remaining byte belonged to a texture the frame itself was holding. The
 * next frame over the same camera therefore re-decodes and re-uploads whatever this one dropped —
 * a megabyte each way per 512x512 tile, every frame, at a stationary camera.
 *
 * A warning rather than a failure, because the frame drew correctly and RenG repairs nothing on the
 * caller's behalf: the two numbers are what a consumer needs to decide whether to raise the limit or
 * lower [com.rohittp.reng.RendererConfiguration.maximumBasemapTileInstances], and that decision is
 * theirs. It names no resource key: the condition is a property of the whole working set, and
 * blaming the last tile released would be arbitrary.
 */
internal fun residentGpuTexturesOverBudgetDiagnostic(residentBytes: Long, budgetBytes: Long): Diagnostic =
    Diagnostic(
        code = DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET,
        severity = DiagnosticSeverity.WARNING,
        stage = PipelineStage.DRAW,
        limit = budgetBytes,
        actual = residentBytes,
    )

/**
 * The engine reported that some label content is missing from this frame, and this is the whole of
 * what RenG says about it (ADR 0036). One aggregate per successful `prepare` that saw any exclusion
 * at all -- never one per excluded label, per layer or per engine diagnostic, because the grouping
 * itself is engine vocabulary -- and it names neither the script, the layer, the font stack nor the
 * feature: [severity] is the only field the engine moves, and [Diagnostic]'s own `init` is what makes
 * every other field unconstructible here.
 *
 * A warning at most. An exclusion is not a failure: the frame prepared and it drew, with less text on
 * it than the style asked for, so the severity says how much was lost rather than whether anything
 * broke. RenG's own [DiagnosticSeverity.ERROR] means the frame failed, which this one did not.
 */
internal fun labelContentExcludedDiagnostic(severity: DiagnosticSeverity): Diagnostic =
    Diagnostic(
        code = DiagnosticCode.LABEL_CONTENT_EXCLUDED,
        severity = severity,
        stage = PipelineStage.LABEL_PREPARATION,
    )

internal fun renGFailure(
    code: RenGErrorCode,
    stage: PipelineStage,
    failureContext: Diagnostic? = null,
): RenGException =
    RenGException(
        code = code,
        stage = stage,
        diagnostics = failureContext?.let(::listOf) ?: emptyList(),
    )

/**
 * A label layer reads a source RenG derived no route for, so this frame's labels cannot be asked for
 * at all (E-labels task 15). Raised **before** the engine is called: Rentile's `planLabelCandidates`
 * reports acquisition failures over the whole batch, so one unroutable source throws the entire plan
 * and per-layer granularity is not expressible after the fact -- and the failure that came back
 * instead was the opaque [RenGErrorCode.BASEMAP_RENDER_FAILED], which sends a consumer to look at
 * their tiles for a fault that is in their style's sources.
 *
 * It names no source. There is no [ResourceKey] to name one with: a route was never derived, which is
 * the fault itself, and a style's own source id is not an identity RenG has ever put in a diagnostic.
 * The `resource` field with no identity behind it is the same shape [RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE]
 * already uses for a route fault RenG cannot attribute, and the allowlist admits nothing more here.
 *
 * Offered as a factory rather than as a `renGFailure` call spelled out at the throw site, because the
 * allowlist requires a diagnostic for this pair and a call site that omitted it would raise an
 * `IllegalArgumentException` out of a frame that was merely being refused.
 */
internal fun unroutableLabelSourceFailure(): RenGException =
    renGFailure(
        code = RenGErrorCode.UNROUTABLE_LABEL_SOURCE,
        stage = PipelineStage.LABEL_PREPARATION,
        failureContext = failureContextDiagnostic(
            stage = PipelineStage.LABEL_PREPARATION,
            fieldName = DiagnosticField.RESOURCE,
        ),
    )

internal fun isAllowedDiagnosticFieldName(fieldName: String): Boolean =
    DiagnosticField.entries.any { it.wireName == fieldName }

internal fun isAllowedDiagnosticFieldStage(fieldName: String, stage: PipelineStage): Boolean =
    diagnosticFieldsByStage[stage]?.any { it.wireName == fieldName } == true

internal fun requireAllowedFailureContext(
    code: RenGErrorCode,
    stage: PipelineStage,
    diagnostic: Diagnostic?,
) {
    when (val rule = failureRule(code, stage)) {
        null -> throw IllegalArgumentException("error code is not valid at this pipeline stage")
        FailureRule.NoDiagnostic -> require(diagnostic == null) {
            "this failure does not carry a diagnostic"
        }

        is FailureRule.Context -> {
            requireNotNull(diagnostic) { "this failure requires a diagnostic" }
            require(diagnostic.code == DiagnosticCode.FAILURE_CONTEXT) {
                "failure diagnostics must be failure context"
            }
            require(diagnostic.severity == DiagnosticSeverity.ERROR) {
                "failure diagnostics must be errors"
            }
            require(diagnostic.stage == stage) {
                "failure diagnostic stage must match the exception stage"
            }
            require(rule.matches(diagnostic)) {
                "diagnostic fields are not allowlisted for this failure"
            }
        }
    }
}

private val diagnosticFieldsByStage: Map<PipelineStage, Set<DiagnosticField>> = mapOf(
    PipelineStage.FRAME_PLANNING to setOf(
        DiagnosticField.PLANS,
        DiagnosticField.FRAME_INDEX,
        DiagnosticField.PROJECTION_MODE,
        DiagnosticField.CAMERA_LATITUDE,
        DiagnosticField.CAMERA_UNWRAPPED_LONGITUDE,
        DiagnosticField.MAP_POSITION_LATITUDE,
        DiagnosticField.MAP_POSITION_UNWRAPPED_LONGITUDE,
        DiagnosticField.MAP_POSITION_ALTITUDE,
        DiagnosticField.SCREEN_POSITION_X,
        DiagnosticField.SCREEN_POSITION_Y,
        DiagnosticField.PLACEMENT_POSITION_MODE,
        DiagnosticField.PLACEMENT_SCALE,
        DiagnosticField.GEOMETRY_LATITUDE,
        DiagnosticField.GEOMETRY_UNWRAPPED_LONGITUDE,
        DiagnosticField.GEOMETRY_ALTITUDE,
        DiagnosticField.BASEMAP_TILE_INSTANCES,
        DiagnosticField.FRAME_IDENTITY,
        DiagnosticField.SHADER_PAIR,
    ),
    PipelineStage.RESOURCE_LOOKUP to setOf(DiagnosticField.RESOURCE),
    PipelineStage.STORE_VALIDATION to setOf(DiagnosticField.RESOURCE),
    PipelineStage.TRANSPORT_VALIDATION to setOf(DiagnosticField.RESPONSE_BODY_BYTES),
    PipelineStage.RESOURCE_DECODING to setOf(DiagnosticField.RESOURCE),
    PipelineStage.RESOURCE_PARSING to setOf(
        DiagnosticField.RESOURCE,
        DiagnosticField.ANIMATION_SELECTOR,
    ),
    PipelineStage.SHADER_COMPILATION to setOf(DiagnosticField.SHADER_PAIR),
    PipelineStage.RENDER_TARGET to setOf(DiagnosticField.RENDER_TARGET),
    // A rendered basemap tile is the one resource RenG decodes at DRAW rather than during
    // preparation, because decoding it is skipped entirely whenever its GL texture is still
    // resident -- see `RenGRenderer.performDraw`. Naming the tile is the whole value of the
    // failure; without this entry the only reportable shape would be a bare GPU_OPERATION_FAILED,
    // which sends a consumer to inspect GL state for a fault that is in their resource limits.
    PipelineStage.DRAW to setOf(DiagnosticField.RESOURCE),
    // The label stage carries the same bare `resource` field the route failures do, and for the same
    // reason: the only failure allowlisted here is one about a source RenG could not route, and it
    // has no identity to name. Nothing about a label -- its text, its layer, its script -- is a
    // diagnostic field at all, here or anywhere (ADR 0036).
    PipelineStage.LABEL_PREPARATION to setOf(DiagnosticField.RESOURCE),
)

private sealed interface FailureRule {
    data object NoDiagnostic : FailureRule

    data class Context(
        val fields: Set<DiagnosticField?>,
        val identity: IdentityRequirement = IdentityRequirement.FORBIDDEN,
        val status: PresenceRequirement = PresenceRequirement.FORBIDDEN,
        val numericLimit: PresenceRequirement = PresenceRequirement.FORBIDDEN,
    ) : FailureRule {
        fun matches(diagnostic: Diagnostic): Boolean =
            fields.any { it?.wireName == diagnostic.fieldName } &&
                identity.matches(diagnostic.resourceKey) &&
                status.matches(diagnostic.statusCode != null) &&
                numericLimit.matches(diagnostic.limit != null) &&
                (numericLimit != PresenceRequirement.REQUIRED || diagnostic.actual!! > diagnostic.limit!!)
    }
}

private enum class IdentityRequirement {
    FORBIDDEN {
        override fun matches(key: ResourceKey?): Boolean = key == null
    },
    REQUIRED_EXTERNAL {
        override fun matches(key: ResourceKey?): Boolean =
            key?.kind == ResourceKind.EXTERNAL && key.resourceClass != null
    },
    REQUIRED_ANY {
        override fun matches(key: ResourceKey?): Boolean = key != null
    },
    OPTIONAL_EXTERNAL {
        override fun matches(key: ResourceKey?): Boolean =
            key == null || (key.kind == ResourceKind.EXTERNAL && key.resourceClass != null)
    },
    REQUIRED_GEOMETRY_PROGRAM {
        override fun matches(key: ResourceKey?): Boolean =
            key?.kind == ResourceKind.GEOMETRY_PROGRAM && key.resourceClass == null
    },
    OPTIONAL_ANY {
        override fun matches(key: ResourceKey?): Boolean = true
    };

    abstract fun matches(key: ResourceKey?): Boolean
}

private enum class PresenceRequirement {
    FORBIDDEN {
        override fun matches(present: Boolean): Boolean = !present
    },
    REQUIRED {
        override fun matches(present: Boolean): Boolean = present
    };

    abstract fun matches(present: Boolean): Boolean
}

private fun failureRule(code: RenGErrorCode, stage: PipelineStage): FailureRule? =
    when (code) {
        RenGErrorCode.INVALID_VALUE -> when (stage) {
            PipelineStage.CONTEXT_ADOPTION -> FailureRule.NoDiagnostic
            PipelineStage.FRAME_PLANNING -> FailureRule.Context(
                fields = setOf(
                    DiagnosticField.PLANS,
                    DiagnosticField.CAMERA_LATITUDE,
                    DiagnosticField.CAMERA_UNWRAPPED_LONGITUDE,
                    DiagnosticField.MAP_POSITION_LATITUDE,
                    DiagnosticField.MAP_POSITION_UNWRAPPED_LONGITUDE,
                    DiagnosticField.MAP_POSITION_ALTITUDE,
                    DiagnosticField.SCREEN_POSITION_X,
                    DiagnosticField.SCREEN_POSITION_Y,
                    DiagnosticField.PLACEMENT_SCALE,
                    DiagnosticField.GEOMETRY_LATITUDE,
                    DiagnosticField.GEOMETRY_UNWRAPPED_LONGITUDE,
                    DiagnosticField.GEOMETRY_ALTITUDE,
                    DiagnosticField.SHADER_PAIR,
                ),
            )

            // Cycle F-1 Task 9b fix round 1: a draw-time Placement/Geometry/Camera re-resolution
            // failure is, semantically, an invalid-value fault -- resolveMercatorCamera,
            // resolvePlacement, and resolveGeometry all report their OWN internal failures as
            // INVALID_VALUE at FRAME_PLANNING, so the re-resolution `internal.gl.requireResolvedAtDrawTime`
            // performs at draw time reuses the same code, only relocated to the stage it actually
            // fires from. No fieldName is carried (this allowlist is private and invisible to
            // checkKotlinAbi, so extending it costs nothing toward the public-ABI-freeze
            // constraint) -- unlike GPU_OPERATION_FAILED, which is GlErrorQueue's wrapper for a
            // genuine glGetError() result and would misdirect a consumer at their GL state when the
            // actual fault is in their own FramePlan.
            PipelineStage.DRAW -> FailureRule.Context(fields = setOf(null))

            else -> null
        }

        RenGErrorCode.RESOURCE_LIMIT_EXCEEDED -> when (stage) {
            PipelineStage.FRAME_PLANNING -> FailureRule.Context(
                fields = setOf(DiagnosticField.PLANS, DiagnosticField.BASEMAP_TILE_INSTANCES),
                numericLimit = PresenceRequirement.REQUIRED,
            )

            PipelineStage.TRANSPORT_VALIDATION -> FailureRule.Context(
                fields = setOf(DiagnosticField.RESPONSE_BODY_BYTES),
                identity = IdentityRequirement.REQUIRED_EXTERNAL,
                status = PresenceRequirement.REQUIRED,
                numericLimit = PresenceRequirement.REQUIRED,
            )

            else -> null
        }

        RenGErrorCode.UNSUPPORTED_PROJECTION_MODE -> ruleAt(
            stage,
            PipelineStage.FRAME_PLANNING,
            FailureRule.Context(setOf(DiagnosticField.PROJECTION_MODE)),
        )

        // ADR 0029: a SCREEN-positioned Model is refused at frame planning for the same reason
        // UNSUPPORTED_PROJECTION_MODE refuses ProjectionMode.GLOBE -- RenG never substitutes a
        // mode it does not implement. One level down from a projection: the screen projection has
        // no z row at all, so a volumetric mesh drawn there would show its back faces through its
        // front ones.
        RenGErrorCode.UNSUPPORTED_ANCHORING_MODE -> ruleAt(
            stage,
            PipelineStage.FRAME_PLANNING,
            FailureRule.Context(setOf(DiagnosticField.PLACEMENT_POSITION_MODE)),
        )

        RenGErrorCode.PREPARATION_ORDER_VIOLATION -> ruleAt(
            stage,
            PipelineStage.FRAME_PLANNING,
            FailureRule.Context(setOf(DiagnosticField.FRAME_INDEX)),
        )

        RenGErrorCode.PREPARATION_IN_PROGRESS -> noDiagnosticAt(
            stage,
            PipelineStage.FRAME_PREPARATION,
            PipelineStage.FRAME_PLANNING,
            PipelineStage.RESOURCE_FREE,
            PipelineStage.RENDERER_CLOSE,
        )

        RenGErrorCode.RENDERER_CLOSED -> noDiagnosticAt(
            stage,
            PipelineStage.FRAME_PREPARATION,
            PipelineStage.FRAME_PLANNING,
            PipelineStage.CONTEXT_ADOPTION,
            PipelineStage.RENDER_TARGET,
            PipelineStage.DRAW,
        )

        RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED -> noDiagnosticAt(
            stage,
            PipelineStage.RENDER_TARGET,
            PipelineStage.DRAW,
        )

        RenGErrorCode.NO_CURRENT_RENDER_CONTEXT -> noDiagnosticAt(
            stage,
            PipelineStage.CONTEXT_ADOPTION,
            PipelineStage.RENDER_TARGET,
            PipelineStage.DRAW,
            PipelineStage.RESOURCE_FREE,
            PipelineStage.RENDERER_CLOSE,
        )

        RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT -> noDiagnosticAt(
            stage,
            PipelineStage.RENDER_TARGET,
            PipelineStage.DRAW,
            PipelineStage.RESOURCE_FREE,
            PipelineStage.RENDERER_CLOSE,
        )

        RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT -> noDiagnosticAt(
            stage,
            PipelineStage.CONTEXT_ADOPTION,
            PipelineStage.CONFIGURATION,
        )

        RenGErrorCode.FOREIGN_PREPARED_FRAME,
        RenGErrorCode.PREPARED_FRAME_CLOSED,
        -> noDiagnosticAt(stage, PipelineStage.DRAW)

        RenGErrorCode.FOREIGN_RENDER_TARGET,
        RenGErrorCode.STALE_RENDER_TARGET,
        -> noDiagnosticAt(stage, PipelineStage.RENDER_TARGET)

        RenGErrorCode.INVALID_RENDER_TARGET -> ruleAt(
            stage,
            PipelineStage.RENDER_TARGET,
            FailureRule.Context(setOf(DiagnosticField.RENDER_TARGET)),
        )

        RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE -> ruleAt(
            stage,
            PipelineStage.RESOURCE_LOOKUP,
            FailureRule.Context(setOf(DiagnosticField.RESOURCE)),
        )

        RenGErrorCode.RESOURCE_UNAVAILABLE -> ruleAt(
            stage,
            PipelineStage.RESOURCE_LOOKUP,
            externalResourceRule,
        )

        RenGErrorCode.TRANSPORT_EXECUTION_FAILED -> ruleAt(
            stage,
            PipelineStage.TRANSPORT,
            externalIdentityRule,
        )

        RenGErrorCode.INVALID_TRANSPORT_RESPONSE -> ruleAt(
            stage,
            PipelineStage.TRANSPORT_VALIDATION,
            FailureRule.Context(
                fields = setOf(null, DiagnosticField.RESPONSE_BODY_BYTES),
                identity = IdentityRequirement.REQUIRED_EXTERNAL,
                status = PresenceRequirement.REQUIRED,
            ),
        )

        RenGErrorCode.STORE_READ_FAILED -> ruleAt(
            stage,
            PipelineStage.STORE_READ,
            externalIdentityRule,
        )

        RenGErrorCode.STORE_WRITE_FAILED -> ruleAt(
            stage,
            PipelineStage.STORE_WRITE,
            externalIdentityRule,
        )

        RenGErrorCode.STORE_INTEGRITY_FAILED -> ruleAt(
            stage,
            PipelineStage.STORE_VALIDATION,
            externalResourceRule,
        )

        RenGErrorCode.RESOURCE_DECODE_FAILED -> when (stage) {
            PipelineStage.RESOURCE_DECODING -> externalResourceRule

            // A rendered basemap tile is decoded at DRAW rather than at RESOURCE_DECODING, and it is
            // not an external resource: RenG's own engine produced its bytes, and its identity is a
            // BASEMAP_TILE key (ADR 0018) with no resource class, which REQUIRED_EXTERNAL rejects.
            // Widening the RESOURCE_DECODING rule to admit it would weaken the guarantee every
            // consumer-supplied image failure already relies on, so this is a second, narrower pair
            // instead: same code, the stage it actually fires from, and an identity requirement that
            // still insists the failure names the tile it is about.
            PipelineStage.DRAW -> establishedResourceRule

            else -> null
        }

        RenGErrorCode.RESOURCE_PARSE_FAILED -> ruleAt(
            stage,
            PipelineStage.RESOURCE_PARSING,
            FailureRule.Context(
                fields = setOf(DiagnosticField.RESOURCE, DiagnosticField.ANIMATION_SELECTOR),
                identity = IdentityRequirement.OPTIONAL_EXTERNAL,
            ),
        )

        RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE -> ruleAt(
            stage,
            PipelineStage.RESOURCE_PARSING,
            externalResourceRule,
        )

        RenGErrorCode.SHADER_COMPILE_FAILED,
        RenGErrorCode.SHADER_LINK_FAILED,
        -> ruleAt(
            stage,
            PipelineStage.SHADER_COMPILATION,
            FailureRule.Context(
                fields = setOf(DiagnosticField.SHADER_PAIR),
                identity = IdentityRequirement.REQUIRED_GEOMETRY_PROGRAM,
            ),
        )

        RenGErrorCode.GPU_OPERATION_FAILED -> if (
            stage in setOf(
                PipelineStage.GPU_RESOURCE,
                PipelineStage.RENDER_TARGET,
                PipelineStage.DRAW,
                PipelineStage.RESOURCE_FREE,
                PipelineStage.RENDERER_CLOSE,
            )
        ) {
            FailureRule.Context(
                fields = setOf(null),
                identity = IdentityRequirement.OPTIONAL_ANY,
            )
        } else {
            null
        }

        RenGErrorCode.IDENTITY_COLLISION -> when (stage) {
            PipelineStage.FRAME_PLANNING -> FailureRule.Context(setOf(DiagnosticField.FRAME_IDENTITY))
            PipelineStage.RESOURCE_LOOKUP -> establishedResourceRule
            else -> null
        }

        // Two stages, one code. The engine draws the ground and plans the labels, and when its
        // failure carries nothing RenG can honestly restate, the only thing left to report is which
        // half of the frame stopped. `BASEMAP_RENDER` says the ground did not draw;
        // `LABEL_PREPARATION` says the label plan did not survive, which until this cycle RenG could
        // not say at all -- see `classifyEngineFailure`, whose three swept label codes moved here.
        RenGErrorCode.BASEMAP_RENDER_FAILED -> noDiagnosticAt(
            stage,
            PipelineStage.BASEMAP_RENDER,
            PipelineStage.LABEL_PREPARATION,
        )

        RenGErrorCode.UNROUTABLE_LABEL_SOURCE -> ruleAt(
            stage,
            PipelineStage.LABEL_PREPARATION,
            FailureRule.Context(setOf(DiagnosticField.RESOURCE)),
        )
    }

private val establishedResourceRule: FailureRule.Context = FailureRule.Context(
    fields = setOf(DiagnosticField.RESOURCE),
    identity = IdentityRequirement.REQUIRED_ANY,
)

private val externalResourceRule: FailureRule.Context = FailureRule.Context(
    fields = setOf(DiagnosticField.RESOURCE),
    identity = IdentityRequirement.REQUIRED_EXTERNAL,
)

private val externalIdentityRule: FailureRule.Context = FailureRule.Context(
    fields = setOf(null),
    identity = IdentityRequirement.REQUIRED_EXTERNAL,
)

private fun ruleAt(
    actualStage: PipelineStage,
    expectedStage: PipelineStage,
    rule: FailureRule,
): FailureRule? = if (actualStage == expectedStage) rule else null

private fun noDiagnosticAt(actualStage: PipelineStage, vararg allowedStages: PipelineStage): FailureRule? =
    if (actualStage in allowedStages) FailureRule.NoDiagnostic else null
