package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.residentGpuTexturesOverBudgetDiagnostic
import com.rohittp.reng.internal.resourceReloadedAfterFreeDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsAndFailuresTest {
    @Test
    fun publicDiagnosticEnumsHaveTheSpecifiedMembersInOrder() {
        assertEquals(
            listOf(
                "CONFIGURATION", "FRAME_PLANNING", "FRAME_PREPARATION", "RESOURCE_LOOKUP",
                "STORE_READ", "STORE_VALIDATION", "TRANSPORT", "TRANSPORT_VALIDATION",
                "STORE_WRITE", "RESOURCE_DECODING", "RESOURCE_PARSING", "SHADER_COMPILATION",
                "GPU_RESOURCE", "RENDER_TARGET", "DRAW", "RESOURCE_FREE", "RENDERER_CLOSE",
                "CONTEXT_ADOPTION", "BASEMAP_RENDER",
            ),
            PipelineStage.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "INVALID_VALUE", "RESOURCE_LIMIT_EXCEEDED", "UNSUPPORTED_PROJECTION_MODE",
                "UNSUPPORTED_ANCHORING_MODE",
                "PREPARATION_ORDER_VIOLATION", "PREPARATION_IN_PROGRESS", "RENDERER_CLOSED",
                "RENDER_CONTEXT_ADOPTION_REQUIRED", "NO_CURRENT_RENDER_CONTEXT",
                "DIFFERENT_CURRENT_RENDER_CONTEXT", "UNSUPPORTED_RENDER_CONTEXT",
                "FOREIGN_PREPARED_FRAME", "PREPARED_FRAME_CLOSED", "FOREIGN_RENDER_TARGET",
                "STALE_RENDER_TARGET", "INVALID_RENDER_TARGET", "AMBIGUOUS_RESOURCE_ROUTE",
                "RESOURCE_UNAVAILABLE", "TRANSPORT_EXECUTION_FAILED", "INVALID_TRANSPORT_RESPONSE",
                "STORE_READ_FAILED", "STORE_WRITE_FAILED", "STORE_INTEGRITY_FAILED",
                "RESOURCE_DECODE_FAILED", "RESOURCE_PARSE_FAILED", "UNSUPPORTED_RESOURCE_FEATURE",
                "SHADER_COMPILE_FAILED", "SHADER_LINK_FAILED", "GPU_OPERATION_FAILED",
                "IDENTITY_COLLISION", "BASEMAP_RENDER_FAILED",
            ),
            RenGErrorCode.entries.map { it.name },
        )
        assertEquals(listOf("INFO", "WARNING", "ERROR"), DiagnosticSeverity.entries.map { it.name })
        assertEquals(
            listOf(
                "RESOURCE_RELOADED_AFTER_FREE", "FAILURE_CONTEXT", "BASEMAP_NOT_CONFIGURED",
                "RESIDENT_GPU_TEXTURES_OVER_BUDGET",
            ),
            DiagnosticCode.entries.map { it.name },
        )
    }

    @Test
    fun diagnosticFieldAllowlistHasExactlyTheSpecifiedWireNames() {
        assertEquals(
            listOf(
                "plans", "frameIndex", "projectionMode", "camera.latitude",
                "camera.unwrappedLongitude", "mapPosition.latitude",
                "mapPosition.unwrappedLongitude", "mapPosition.altitude", "screenPosition.x",
                "screenPosition.y", "placement.positionMode", "placement.scale", "geometry.latitude",
                "geometry.unwrappedLongitude", "geometry.altitude", "basemapTileInstances",
                "responseBodyBytes", "resource", "frameIdentity", "animationSelector",
                "shaderPair", "renderTarget",
            ),
            DiagnosticField.entries.map { it.wireName },
        )

        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.FAILURE_CONTEXT,
                severity = DiagnosticSeverity.ERROR,
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = "locator=https://example.test/signed-url",
            )
        }
    }

    @Test
    fun failureMessageCauseAndDiagnosticAreFixed() {
        val diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.FRAME_INDEX,
        )
        val failure = renGFailure(
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            diagnostic,
        )

        assertEquals("RenG failure: PREPARATION_ORDER_VIOLATION at FRAME_PLANNING", failure.message)
        assertNull(failure.cause)
        assertEquals(listOf(diagnostic), failure.diagnostics)
    }

    @Test
    fun failureFactoryAcceptsEveryAllowedFailureTableShape() {
        assertEquals(98, allowedFailureCases.size)
        assertEquals(28, allowedFailureCases.count { !it.hasDiagnostic })
        assertEquals(70, allowedFailureCases.count { it.hasDiagnostic })
        assertEquals(RenGErrorCode.entries.toSet(), allowedFailureCases.map { it.code }.toSet())

        allowedFailureCases.forEach(::assertFailureTableOutcome)
    }

    @Test
    fun factorySystematicallyRejectsDisallowedStagesFieldsIdentitiesStatusesAndLimits() {
        val allowedNoDiagnostic = allowedFailureCases.filter { !it.hasDiagnostic }.toSet()
        RenGErrorCode.entries.forEach { code ->
            PipelineStage.entries.forEach { stage ->
                val candidate = FailureCase(code, stage)
                if (candidate in allowedNoDiagnostic) {
                    assertFailureTableOutcome(candidate)
                } else {
                    assertFailsWith<IllegalArgumentException> {
                        renGFailure(code, stage)
                    }
                }
            }
        }

        allowedFailureCases.filter { it.hasDiagnostic }.forEach { expected ->
            fieldsForStage(expected.stage).forEach { field ->
                assertFailureTableOutcome(expected.copy(field = field))
            }
            IdentityShape.entries.forEach { identity ->
                assertFailureTableOutcome(expected.copy(identity = identity))
            }
            listOf(false, true).forEach { hasStatus ->
                assertFailureTableOutcome(expected.copy(hasStatus = hasStatus))
            }
            LimitShape.entries.forEach { limitShape ->
                assertFailureTableOutcome(expected.copy(limitShape = limitShape))
            }
            PipelineStage.entries.forEach { stage ->
                assertFailureTableOutcome(expected.copy(stage = stage))
            }
        }
    }

    @Test
    fun identityCollisionAcceptsAnyEstablishedKeyButAmbiguousRouteHasNoIdentity() {
        IdentityShape.entries.filter { it != IdentityShape.NONE }.forEach { identity ->
            assertFailureTableOutcome(
                failureContext(
                    code = RenGErrorCode.IDENTITY_COLLISION,
                    stage = PipelineStage.RESOURCE_LOOKUP,
                    field = DiagnosticField.RESOURCE,
                    identity = identity,
                ),
            )
        }

        IdentityShape.entries.filter { it != IdentityShape.NONE }.forEach { identity ->
            assertFailsWith<IllegalArgumentException> {
                renGFailure(
                    RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
                    PipelineStage.RESOURCE_LOOKUP,
                    diagnosticFor(
                        failureContext(
                            code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
                            stage = PipelineStage.RESOURCE_LOOKUP,
                            field = DiagnosticField.RESOURCE,
                            identity = identity,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun diagnosticValidatesStatusLimitsAndEstablishedResourceIdentity() {
        val externalKey = resourceKey(IdentityShape.EXTERNAL)!!
        val geometryProgramKey = resourceKey(IdentityShape.GEOMETRY_PROGRAM)!!

        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.TRANSPORT, statusCode = 200)
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.FRAME_PLANNING, limit = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.FRAME_PLANNING, actual = 2L)
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.RESOURCE_LOOKUP, resourceClass = ResourceClass.STICKER_IMAGE)
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                resourceClass = ResourceClass.MODEL_GLB,
                resourceKey = externalKey,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                resourceClass = ResourceClass.STICKER_IMAGE,
                resourceKey = geometryProgramKey,
            )
        }
    }

    @Test
    fun reloadDiagnosticHasStableWarningShapeAndSafeStructuralText() {
        val key = resourceKey(IdentityShape.EXTERNAL)!!
        val diagnostic = resourceReloadedAfterFreeDiagnostic(key)

        assertEquals(DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE, diagnostic.code)
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, diagnostic.stage)
        assertEquals(null, diagnostic.fieldName)
        assertEquals(ResourceClass.STICKER_IMAGE, diagnostic.resourceClass)
        assertEquals(key, diagnostic.resourceKey)
        assertEquals(null, diagnostic.statusCode)
        assertEquals(null, diagnostic.limit)
        assertEquals(null, diagnostic.actual)
        assertEquals(diagnostic, resourceReloadedAfterFreeDiagnostic(key))
        assertEquals(diagnostic.hashCode(), resourceReloadedAfterFreeDiagnostic(key).hashCode())
        assertRedacted(diagnostic.toString(), key.stableId)

        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE,
                severity = DiagnosticSeverity.WARNING,
                stage = PipelineStage.RESOURCE_LOOKUP,
                resourceClass = ResourceClass.STICKER_IMAGE,
            )
        }
    }

    @Test
    fun residentGpuTextureBudgetDiagnosticCarriesOnlyTheTwoNumbersAndRefusesAnythingElse() {
        val diagnostic = residentGpuTexturesOverBudgetDiagnostic(
            residentBytes = 175_112_192L,
            budgetBytes = 134_217_728L,
        )

        assertEquals(DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET, diagnostic.code)
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals(PipelineStage.DRAW, diagnostic.stage)
        assertEquals(134_217_728L, diagnostic.limit, "the limit is the consumer's own budget")
        assertEquals(175_112_192L, diagnostic.actual, "and the actual is what stayed resident against it")
        assertEquals(null, diagnostic.fieldName)
        assertEquals(null, diagnostic.resourceClass)
        assertEquals(null, diagnostic.resourceKey)
        assertEquals(null, diagnostic.statusCode)

        // An error, a stage other than the one it fires from, or a resource identity it has no
        // business naming are all unconstructible rather than merely discouraged.
        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET,
                severity = DiagnosticSeverity.ERROR,
                stage = PipelineStage.DRAW,
                limit = 1L,
                actual = 2L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET,
                severity = DiagnosticSeverity.WARNING,
                stage = PipelineStage.GPU_RESOURCE,
                limit = 1L,
                actual = 2L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET,
                severity = DiagnosticSeverity.WARNING,
                stage = PipelineStage.DRAW,
                resourceKey = resourceKey(IdentityShape.EXTERNAL),
                resourceClass = ResourceClass.STICKER_IMAGE,
                limit = 1L,
                actual = 2L,
            )
        }
    }

    @Test
    fun aResidencyAtOrUnderItsBudgetCannotBeReportedAsOverIt() {
        // The off-by-one, stated where it cannot be got wrong twice: the type refuses to hold the
        // claim at all, so an emitter that miscounted by one byte fails loudly rather than warning
        // about a residency that fits.
        assertFailsWith<IllegalArgumentException> {
            residentGpuTexturesOverBudgetDiagnostic(residentBytes = 4L, budgetBytes = 4L)
        }
        assertFailsWith<IllegalArgumentException> {
            residentGpuTexturesOverBudgetDiagnostic(residentBytes = 3L, budgetBytes = 4L)
        }
        // And one byte the other way is a legitimate report.
        assertEquals(5L, residentGpuTexturesOverBudgetDiagnostic(residentBytes = 5L, budgetBytes = 4L).actual)

        // Numbers are required at all: a bare over-budget warning naming no figures says nothing a
        // consumer can act on.
        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.RESIDENT_GPU_TEXTURES_OVER_BUDGET,
                severity = DiagnosticSeverity.WARNING,
                stage = PipelineStage.DRAW,
            )
        }
    }

    @Test
    fun exceptionSnapshotsAtMostOneDiagnosticAndUsesIdentityEquality() {
        val diagnostic = failureContextDiagnostic(PipelineStage.FRAME_PLANNING, DiagnosticField.FRAME_INDEX)
        val supplied = mutableListOf(diagnostic)
        val first = RenGException(
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            supplied,
        )
        supplied.clear()
        val returned = first.diagnostics
        assertFalse(returned === first.diagnostics)
        assertTrue(returned is MutableList<Diagnostic>)
        returned.clear()
        assertEquals(listOf(diagnostic), first.diagnostics)
        assertNotEquals(
            first,
            RenGException(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                listOf(diagnostic),
            ),
        )
        assertTrue(first === first)
        assertEquals(emptyList(), RenGException(RenGErrorCode.RENDERER_CLOSED, PipelineStage.DRAW).diagnostics)
        assertFailsWith<IllegalArgumentException> {
            RenGException(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                listOf(diagnostic, diagnostic),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RenGException(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PREPARATION,
                listOf(diagnostic),
            )
        }
    }

    @Test
    fun sinkNoneAndFailureDescriptorsRetainOnlySanitizedContext() {
        DiagnosticSink.None.emit(resourceReloadedAfterFreeDiagnostic(resourceKey(IdentityShape.EXTERNAL)!!))

        val diagnostic = failureContextDiagnostic(PipelineStage.FRAME_PLANNING, DiagnosticField.FRAME_INDEX)
        val descriptor = FailureDescriptor(
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            diagnostic,
        )
        assertEquals(
            descriptor,
            FailureDescriptor(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                diagnostic,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            FailureDescriptor(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PREPARATION,
                diagnostic,
            )
        }
    }

    @Test
    fun basemapRenderFailureCarriesItsOwnStage() {
        val failure = RenGException(RenGErrorCode.BASEMAP_RENDER_FAILED, PipelineStage.BASEMAP_RENDER)
        assertEquals("RenG failure: BASEMAP_RENDER_FAILED at BASEMAP_RENDER", failure.message)
        assertNull(failure.cause)
        assertEquals(emptyList(), failure.diagnostics)
    }

    @Test
    fun diagnosticAndFailurePathsRedactEstablishedStableIds() {
        val secretStableId = stableId('f')
        val key = ResourceKey(ResourceKind.EXTERNAL, secretStableId, ResourceClass.STICKER_IMAGE)
        val diagnosticFailure = assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.FAILURE_CONTEXT,
                severity = DiagnosticSeverity.ERROR,
                stage = PipelineStage.RESOURCE_LOOKUP,
                fieldName = DiagnosticField.RESOURCE.wireName,
                resourceClass = ResourceClass.MODEL_GLB,
                resourceKey = key,
            )
        }
        val factoryFailure = assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                DiagnosticField.RESOURCE,
                ResourceClass.MODEL_GLB,
                key,
            )
        }
        val failure = renGFailure(
            RenGErrorCode.RESOURCE_UNAVAILABLE,
            PipelineStage.RESOURCE_LOOKUP,
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                DiagnosticField.RESOURCE,
                ResourceClass.STICKER_IMAGE,
                key,
            ),
        )

        assertRedacted(diagnosticFailure.message.orEmpty(), secretStableId)
        assertRedacted(factoryFailure.message.orEmpty(), secretStableId)
        assertRedacted(failure.toString(), secretStableId)
        assertRedacted(failure.diagnostics.single().toString(), secretStableId)
    }

    private fun assertFailureTableOutcome(candidate: FailureCase) {
        val execute = { renGFailure(candidate.code, candidate.stage, diagnosticFor(candidate)) }
        if (candidate in allowedFailureCases) {
            val failure = execute()
            assertEquals(candidate.code, failure.code)
            assertEquals(candidate.stage, failure.stage)
            assertEquals(candidate.hasDiagnostic, failure.diagnostics.isNotEmpty())
        } else {
            assertFailsWith<IllegalArgumentException> { execute() }
        }
    }

    private fun diagnosticFor(candidate: FailureCase): Diagnostic? {
        if (!candidate.hasDiagnostic) return null
        val key = resourceKey(candidate.identity)
        val (limit, actual) = when (candidate.limitShape) {
            LimitShape.NONE -> null to null
            LimitShape.EXCEEDED -> 1L to 2L
            LimitShape.EQUAL -> 1L to 1L
            LimitShape.UNDER -> 2L to 1L
        }
        return failureContextDiagnostic(
            stage = candidate.stage,
            fieldName = candidate.field,
            resourceClass = key?.resourceClass,
            resourceKey = key,
            statusCode = if (candidate.hasStatus) 200 else null,
            limit = limit,
            actual = actual,
        )
    }

    private fun fieldsForStage(stage: PipelineStage): Set<DiagnosticField?> =
        allowedFieldsByStage[stage].orEmpty() + null

    private fun resourceKey(identity: IdentityShape): ResourceKey? = when (identity) {
        IdentityShape.NONE -> null
        IdentityShape.EXTERNAL -> ResourceKey(
            ResourceKind.EXTERNAL,
            stableId('a'),
            ResourceClass.STICKER_IMAGE,
        )
        IdentityShape.GEOMETRY_PROGRAM -> ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableId('b'), null)
        IdentityShape.INTERNAL_PIPELINE -> ResourceKey(ResourceKind.INTERNAL_PIPELINE, stableId('c'), null)
        IdentityShape.OFFSCREEN_SURFACE -> ResourceKey(ResourceKind.OFFSCREEN_SURFACE, stableId('d'), null)
    }

    private fun stableId(character: Char): String = character.toString().repeat(64)

    private fun assertRedacted(text: String, vararg sensitiveValues: String) {
        sensitiveValues.forEach { sensitiveValue ->
            assertFalse(text.contains(sensitiveValue), "text leaked $sensitiveValue: $text")
        }
    }

    private data class FailureCase(
        val code: RenGErrorCode,
        val stage: PipelineStage,
        val hasDiagnostic: Boolean = false,
        val field: DiagnosticField? = null,
        val identity: IdentityShape = IdentityShape.NONE,
        val hasStatus: Boolean = false,
        val limitShape: LimitShape = LimitShape.NONE,
    )

    private enum class IdentityShape {
        NONE,
        EXTERNAL,
        GEOMETRY_PROGRAM,
        INTERNAL_PIPELINE,
        OFFSCREEN_SURFACE,
    }

    private enum class LimitShape {
        NONE,
        EXCEEDED,
        EQUAL,
        UNDER,
    }

    private companion object {
        private fun noDiagnostic(code: RenGErrorCode, stage: PipelineStage): FailureCase =
            FailureCase(code, stage)

        private fun failureContext(
            code: RenGErrorCode,
            stage: PipelineStage,
            field: DiagnosticField? = null,
            identity: IdentityShape = IdentityShape.NONE,
            hasStatus: Boolean = false,
            limitShape: LimitShape = LimitShape.NONE,
        ): FailureCase = FailureCase(code, stage, true, field, identity, hasStatus, limitShape)

        private val allowedFieldsByStage: Map<PipelineStage, Set<DiagnosticField>> = mapOf(
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
            PipelineStage.DRAW to setOf(DiagnosticField.RESOURCE),
        )

        private val allowedFailureCases: List<FailureCase> = buildList {
            add(noDiagnostic(RenGErrorCode.INVALID_VALUE, PipelineStage.CONTEXT_ADOPTION))
            addAll(
                listOf(
                    PipelineStage.FRAME_PREPARATION,
                    PipelineStage.FRAME_PLANNING,
                    PipelineStage.RESOURCE_FREE,
                    PipelineStage.RENDERER_CLOSE,
                ).map { noDiagnostic(RenGErrorCode.PREPARATION_IN_PROGRESS, it) },
            )
            addAll(
                listOf(
                    PipelineStage.FRAME_PREPARATION,
                    PipelineStage.FRAME_PLANNING,
                    PipelineStage.CONTEXT_ADOPTION,
                    PipelineStage.RENDER_TARGET,
                    PipelineStage.DRAW,
                ).map { noDiagnostic(RenGErrorCode.RENDERER_CLOSED, it) },
            )
            addAll(
                listOf(PipelineStage.RENDER_TARGET, PipelineStage.DRAW).map {
                    noDiagnostic(RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED, it)
                },
            )
            addAll(
                listOf(
                    PipelineStage.CONTEXT_ADOPTION,
                    PipelineStage.RENDER_TARGET,
                    PipelineStage.DRAW,
                    PipelineStage.RESOURCE_FREE,
                    PipelineStage.RENDERER_CLOSE,
                ).map { noDiagnostic(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, it) },
            )
            addAll(
                listOf(
                    PipelineStage.RENDER_TARGET,
                    PipelineStage.DRAW,
                    PipelineStage.RESOURCE_FREE,
                    PipelineStage.RENDERER_CLOSE,
                ).map { noDiagnostic(RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT, it) },
            )
            addAll(
                listOf(PipelineStage.CONTEXT_ADOPTION, PipelineStage.CONFIGURATION).map {
                    noDiagnostic(RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT, it)
                },
            )
            add(noDiagnostic(RenGErrorCode.FOREIGN_PREPARED_FRAME, PipelineStage.DRAW))
            add(noDiagnostic(RenGErrorCode.PREPARED_FRAME_CLOSED, PipelineStage.DRAW))
            add(noDiagnostic(RenGErrorCode.FOREIGN_RENDER_TARGET, PipelineStage.RENDER_TARGET))
            add(noDiagnostic(RenGErrorCode.STALE_RENDER_TARGET, PipelineStage.RENDER_TARGET))

            addAll(
                listOf(
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
                ).map { failureContext(RenGErrorCode.INVALID_VALUE, PipelineStage.FRAME_PLANNING, it) },
            )
            addAll(
                listOf(DiagnosticField.PLANS, DiagnosticField.BASEMAP_TILE_INSTANCES).map {
                    failureContext(
                        RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                        PipelineStage.FRAME_PLANNING,
                        it,
                        limitShape = LimitShape.EXCEEDED,
                    )
                },
            )
            add(
                failureContext(
                    RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    PipelineStage.TRANSPORT_VALIDATION,
                    DiagnosticField.RESPONSE_BODY_BYTES,
                    IdentityShape.EXTERNAL,
                    hasStatus = true,
                    limitShape = LimitShape.EXCEEDED,
                ),
            )
            add(failureContext(RenGErrorCode.UNSUPPORTED_PROJECTION_MODE, PipelineStage.FRAME_PLANNING, DiagnosticField.PROJECTION_MODE))
            add(failureContext(RenGErrorCode.UNSUPPORTED_ANCHORING_MODE, PipelineStage.FRAME_PLANNING, DiagnosticField.PLACEMENT_POSITION_MODE))
            add(failureContext(RenGErrorCode.PREPARATION_ORDER_VIOLATION, PipelineStage.FRAME_PLANNING, DiagnosticField.FRAME_INDEX))
            add(failureContext(RenGErrorCode.INVALID_RENDER_TARGET, PipelineStage.RENDER_TARGET, DiagnosticField.RENDER_TARGET))
            add(failureContext(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, PipelineStage.RESOURCE_LOOKUP, DiagnosticField.RESOURCE))
            add(failureContext(RenGErrorCode.RESOURCE_UNAVAILABLE, PipelineStage.RESOURCE_LOOKUP, DiagnosticField.RESOURCE, IdentityShape.EXTERNAL))
            add(failureContext(RenGErrorCode.TRANSPORT_EXECUTION_FAILED, PipelineStage.TRANSPORT, identity = IdentityShape.EXTERNAL))
            addAll(
                listOf(null, DiagnosticField.RESPONSE_BODY_BYTES).map {
                    failureContext(
                        RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
                        PipelineStage.TRANSPORT_VALIDATION,
                        it,
                        IdentityShape.EXTERNAL,
                        hasStatus = true,
                    )
                },
            )
            add(failureContext(RenGErrorCode.STORE_READ_FAILED, PipelineStage.STORE_READ, identity = IdentityShape.EXTERNAL))
            add(failureContext(RenGErrorCode.STORE_WRITE_FAILED, PipelineStage.STORE_WRITE, identity = IdentityShape.EXTERNAL))
            add(failureContext(RenGErrorCode.STORE_INTEGRITY_FAILED, PipelineStage.STORE_VALIDATION, DiagnosticField.RESOURCE, IdentityShape.EXTERNAL))
            add(failureContext(RenGErrorCode.RESOURCE_DECODE_FAILED, PipelineStage.RESOURCE_DECODING, DiagnosticField.RESOURCE, IdentityShape.EXTERNAL))
            // A rendered basemap tile is decoded at DRAW and is not an external resource: its
            // identity is a BASEMAP_TILE key with no resource class, so the RESOURCE_DECODING pair's
            // REQUIRED_EXTERNAL rule cannot express it. The DRAW pair still requires *an* identity,
            // so a decode failure that names no tile stays rejected.
            IdentityShape.entries.filter { it != IdentityShape.NONE }.forEach { identity ->
                add(
                    failureContext(
                        RenGErrorCode.RESOURCE_DECODE_FAILED,
                        PipelineStage.DRAW,
                        DiagnosticField.RESOURCE,
                        identity,
                    ),
                )
            }
            listOf(DiagnosticField.RESOURCE, DiagnosticField.ANIMATION_SELECTOR).forEach { field ->
                add(failureContext(RenGErrorCode.RESOURCE_PARSE_FAILED, PipelineStage.RESOURCE_PARSING, field))
                add(failureContext(RenGErrorCode.RESOURCE_PARSE_FAILED, PipelineStage.RESOURCE_PARSING, field, IdentityShape.EXTERNAL))
            }
            add(failureContext(RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE, PipelineStage.RESOURCE_PARSING, DiagnosticField.RESOURCE, IdentityShape.EXTERNAL))
            listOf(RenGErrorCode.SHADER_COMPILE_FAILED, RenGErrorCode.SHADER_LINK_FAILED).forEach { code ->
                add(failureContext(code, PipelineStage.SHADER_COMPILATION, DiagnosticField.SHADER_PAIR, IdentityShape.GEOMETRY_PROGRAM))
            }
            listOf(
                PipelineStage.GPU_RESOURCE,
                PipelineStage.RENDER_TARGET,
                PipelineStage.DRAW,
                PipelineStage.RESOURCE_FREE,
                PipelineStage.RENDERER_CLOSE,
            ).forEach { stage ->
                IdentityShape.entries.forEach { identity ->
                    add(failureContext(RenGErrorCode.GPU_OPERATION_FAILED, stage, identity = identity))
                }
            }
            add(failureContext(RenGErrorCode.IDENTITY_COLLISION, PipelineStage.FRAME_PLANNING, DiagnosticField.FRAME_IDENTITY))
            IdentityShape.entries.filter { it != IdentityShape.NONE }.forEach { identity ->
                add(failureContext(RenGErrorCode.IDENTITY_COLLISION, PipelineStage.RESOURCE_LOOKUP, DiagnosticField.RESOURCE, identity))
            }
            add(noDiagnostic(RenGErrorCode.BASEMAP_RENDER_FAILED, PipelineStage.BASEMAP_RENDER))
        }
    }
}
