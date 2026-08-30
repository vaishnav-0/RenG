package com.rohittp.reng.internal.firewall

import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.failure.toException
import com.rohittp.rentile.BatchRenderException
import com.rohittp.rentile.ForeignPreparedBatchException
import com.rohittp.rentile.ForeignLabelCandidatePlanException
import com.rohittp.rentile.ForeignPreparedStyleException
import com.rohittp.rentile.GlyphTemplateMismatchException
import com.rohittp.rentile.InvalidTileIdException
import com.rohittp.rentile.LabelCandidatePlanClosedException
import com.rohittp.rentile.PngEncodingException
import com.rohittp.rentile.PreparedBatchClosedException
import com.rohittp.rentile.RasterizationException
import com.rohittp.rentile.RasterizerClosedException
import com.rohittp.rentile.RentileErrorCode
import com.rohittp.rentile.RentileException
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StylePreparationException
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileNotInPreparedBatchException
import com.rohittp.rentile.TileSubstitutionException
import com.rohittp.rentile.TileSubstitutionLimitException
import com.rohittp.rentile.TileSubstitutionStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import com.rohittp.rentile.PipelineStage as RentilePipelineStage
import com.rohittp.rentile.ResourceClass as RentileResourceClass

/**
 * The firewall's failure half. Every assertion here is a contract about what a consumer is allowed to
 * observe after Rentile fails, so each one is written against Rentile's real 0.7.0 exception surface --
 * the exemplars below are constructed with the exact constructor signatures Rentile publishes, so a
 * signature change in a future Rentile breaks this test's compilation rather than silently reclassifying.
 */
class EngineFailureClassificationTest {
    private val acquisitionDigest = "a".repeat(64)
    private val decodeDigest = "b".repeat(64)
    private val substitutionDigest = "c".repeat(64)
    private val credentialBearingMessage =
        "GET https://tiles.example.test/v1/0/0/0.pbf?access_token=SIGNED-SECRET-9f3 failed"

    private fun acquisitionFailure(
        resourceClass: RentileResourceClass = RentileResourceClass.VECTOR_TILE,
        sanitizedResourceId: String = acquisitionDigest,
        message: String = credentialBearingMessage,
    ): ResourceAcquisitionException = ResourceAcquisitionException(
        message = message,
        resourceClass = resourceClass,
        sanitizedResourceId = sanitizedResourceId,
        statusCode = 403,
        retryAfterMillis = 1_000L,
    )

    private fun decodeFailure(
        resourceClass: RentileResourceClass = RentileResourceClass.RASTER_TILE,
        sanitizedResourceId: String = decodeDigest,
        message: String = credentialBearingMessage,
    ): ResourceDecodeException = ResourceDecodeException(
        message = message,
        resourceClass = resourceClass,
        sanitizedResourceId = sanitizedResourceId,
    )

    /**
     * One exemplar per [RentileErrorCode], paired with the exact triple the firewall must produce. The
     * three wrapping codes carry an identity-bearing primary failure precisely so that "unwrapped, then
     * classified" is distinguishable from "collapsed to `BASEMAP_RENDER_FAILED`" -- the two would be
     * indistinguishable if the exemplars wrapped an opaque failure.
     */
    private fun expectations(): Map<RentileErrorCode, ExpectedClassification> = mapOf(
        RentileErrorCode.STYLE_PREPARATION_FAILED to ExpectedClassification(
            failure = StylePreparationException(credentialBearingMessage),
            code = RenGErrorCode.RESOURCE_PARSE_FAILED,
            stage = PipelineStage.RESOURCE_PARSING,
            diagnosticPresent = true,
        ),
        RentileErrorCode.RESOURCE_ACQUISITION_FAILED to ExpectedClassification(
            failure = acquisitionFailure(),
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnosticPresent = true,
        ),
        RentileErrorCode.RESOURCE_DECODE_FAILED to ExpectedClassification(
            failure = decodeFailure(),
            code = RenGErrorCode.RESOURCE_DECODE_FAILED,
            stage = PipelineStage.RESOURCE_DECODING,
            diagnosticPresent = true,
        ),
        RentileErrorCode.RESOURCE_STORE_FAILED to ExpectedClassification(
            failure = ResourceStoreException(credentialBearingMessage),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.SAFETY_LIMIT_EXCEEDED to ExpectedClassification(
            failure = SafetyLimitException(
                message = credentialBearingMessage,
                limitName = "maximumTiles",
                limit = 4L,
                observed = 9L,
                stage = RentilePipelineStage.RESOURCE_PLANNING,
            ),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.RASTERIZATION_FAILED to ExpectedClassification(
            failure = RasterizationException(credentialBearingMessage),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.PNG_ENCODING_FAILED to ExpectedClassification(
            failure = PngEncodingException(credentialBearingMessage),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.PREPARED_BATCH_CLOSED to ExpectedClassification(
            failure = PreparedBatchClosedException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.RASTERIZER_CLOSED to ExpectedClassification(
            failure = RasterizerClosedException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.FOREIGN_PREPARED_STYLE to ExpectedClassification(
            failure = ForeignPreparedStyleException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.FOREIGN_PREPARED_BATCH to ExpectedClassification(
            failure = ForeignPreparedBatchException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.INVALID_TILE_ID to ExpectedClassification(
            failure = InvalidTileIdException(TileId(3, 1, 2)),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.TILE_NOT_IN_PREPARED_BATCH to ExpectedClassification(
            failure = TileNotInPreparedBatchException(TileId(3, 1, 2)),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.TILE_SUBSTITUTION_LIMIT_EXCEEDED to ExpectedClassification(
            failure = TileSubstitutionLimitException(
                maximumSubstitutedTiles = 1,
                requiredSubstitutedTiles = 4,
                primaryFailure = acquisitionFailure(),
                affectedTiles = listOf(TileId(3, 1, 2)),
            ),
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnosticPresent = true,
        ),
        RentileErrorCode.TILE_SUBSTITUTION_FAILED to ExpectedClassification(
            failure = TileSubstitutionException(
                tile = TileId(3, 1, 2),
                resourceClass = RentileResourceClass.VECTOR_TILE,
                sanitizedResourceId = substitutionDigest,
                attemptedStrategies = listOf(TileSubstitutionStrategy.ANCESTOR),
                primaryFailure = acquisitionFailure(),
                substitutionFailures = emptyList(),
            ),
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnosticPresent = true,
        ),
        RentileErrorCode.BATCH_RENDER_FAILED to ExpectedClassification(
            failure = BatchRenderException(
                message = credentialBearingMessage,
                primaryFailure = decodeFailure(),
            ),
            code = RenGErrorCode.RESOURCE_DECODE_FAILED,
            stage = PipelineStage.RESOURCE_DECODING,
            diagnosticPresent = true,
        ),
        // Rentile's three label-candidate codes, all raised only from `acquireLabelCandidates` and the
        // `LabelCandidatePlan` it returns -- the entry point RenG now calls. Same fail-closed code as
        // the ground's, because each of them is a RenG defect the engine cannot describe any further,
        // and a stage that says which half of the frame stopped. The stage is the whole delta, and it
        // is what the older comment here promised the label cycle would do.
        RentileErrorCode.FOREIGN_LABEL_CANDIDATE_PLAN to ExpectedClassification(
            failure = ForeignLabelCandidatePlanException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.LABEL_PREPARATION,
            diagnosticPresent = false,
        ),
        RentileErrorCode.LABEL_CANDIDATE_PLAN_CLOSED to ExpectedClassification(
            failure = LabelCandidatePlanClosedException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.LABEL_PREPARATION,
            diagnosticPresent = false,
        ),
        RentileErrorCode.GLYPH_TEMPLATE_MISMATCH to ExpectedClassification(
            failure = GlyphTemplateMismatchException(credentialBearingMessage),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.LABEL_PREPARATION,
            diagnosticPresent = false,
        ),
    )

    @Test
    fun classifiesEveryEngineErrorCodeItsOwnWay() {
        val expectations = expectations()
        assertEquals(
            RentileErrorCode.entries.toSet(),
            expectations.keys,
            "every RentileErrorCode must have an asserted classification; a code Rentile adds must fail here",
        )

        RentileErrorCode.entries.forEach { engineCode ->
            val expected = assertNotNull(expectations[engineCode])
            assertEquals(engineCode, expected.failure.code, "exemplar for $engineCode carries the wrong code")

            val descriptor = classifyEngineFailure(expected.failure)

            assertEquals(expected.code, descriptor.code, "$engineCode mapped to the wrong RenG code")
            assertEquals(expected.stage, descriptor.stage, "$engineCode mapped to the wrong pipeline stage")
            assertEquals(
                expected.diagnosticPresent,
                descriptor.diagnostic != null,
                "$engineCode produced the wrong diagnostic presence",
            )
        }
    }

    @Test
    fun carriesOnlyTheEngineIdentityForTheTwoClassesThatExposeOne() {
        val acquisition = assertNotNull(classifyEngineFailure(acquisitionFailure()).diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, acquisition.code)
        assertEquals(DiagnosticSeverity.ERROR, acquisition.severity)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, acquisition.stage)
        assertEquals("resource", acquisition.fieldName)
        assertEquals(ResourceClass.BASEMAP_VECTOR_TILE, acquisition.resourceClass)
        assertEquals(ResourceKind.EXTERNAL, assertNotNull(acquisition.resourceKey).kind)
        assertEquals(acquisitionDigest, assertNotNull(acquisition.resourceKey).stableId)
        assertNull(acquisition.statusCode, "RESOURCE_UNAVAILABLE at RESOURCE_LOOKUP forbids a status code")
        assertNull(acquisition.limit)
        assertNull(acquisition.actual)

        val decode = assertNotNull(classifyEngineFailure(decodeFailure()).diagnostic)
        assertEquals(PipelineStage.RESOURCE_DECODING, decode.stage)
        assertEquals("resource", decode.fieldName)
        assertEquals(ResourceClass.BASEMAP_RASTER_TILE, decode.resourceClass)
        assertEquals(ResourceKind.EXTERNAL, assertNotNull(decode.resourceKey).kind)
        assertEquals(decodeDigest, assertNotNull(decode.resourceKey).stableId)
        assertNull(decode.statusCode)
    }

    /**
     * A style preparation failure names no resource, because Rentile's one [StylePreparationException]
     * exposes none. It still carries a diagnostic: `RESOURCE_PARSE_FAILED` at `RESOURCE_PARSING` is a
     * `FailureRule.Context`, and every such rule requires one -- `IdentityRequirement.OPTIONAL_EXTERNAL`
     * makes the resource *identity* optional, not the diagnostic. This asserts the emptiest legal shape,
     * so a later change that started naming a resource here would have to justify where it came from.
     */
    @Test
    fun namesNoResourceForAStylePreparationFailure() {
        val descriptor = classifyEngineFailure(StylePreparationException(credentialBearingMessage))

        val diagnostic = assertNotNull(descriptor.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.RESOURCE_PARSING, diagnostic.stage)
        assertEquals("resource", diagnostic.fieldName)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.statusCode)
        assertNull(diagnostic.limit)
    }

    /**
     * Every engine resource class either has a RenG counterpart or fails closed, and which of the two
     * it does is asserted per class rather than in aggregate.
     *
     * This test read `mapped.filterValues { it == null }` against `emptyList()` while Rentile had eight
     * classes and RenG routed all eight. Rentile 0.3.0 added a ninth, `GLYPH_RANGE`, which RenG did not
     * route, so the unmapped set became exactly `GLYPH_RANGE`. Cycle E-labels routes it --
     * [ResourceClass.BASEMAP_GLYPH_RANGE] exists because the firewall's route index is keyed on Rentile's
     * own class and nothing translated to `GLYPH_RANGE` -- so the unmapped set is empty again, and
     * [namesTheGlyphRangeResourceOnAnEngineGlyphFailure] asserts what the mapping buys. A tenth class a
     * future Rentile adds still breaks this test, which is the whole point of it; the fail-closed half of
     * the contract stays proven by [failsClosedWhenTheEngineIdentityIsNotADigest], which reaches the same
     * `basemapRenderFailure()` return through the other guard in `externalResourceFailure`.
     *
     * The injectivity assertion counts **mapped** values only. With a `null` in the map the old
     * `mapped.values.toSet().size == entries.size` form still passed -- eight distinct classes plus
     * `null` is nine distinct values for nine entries -- so it would have gone on reporting success
     * while measuring nothing. Counting the non-null values against the mapped keys keeps it honest: it
     * fails if two engine classes ever collapse onto one RenG class, which is exactly the failure a
     * repointed existing constant would have been.
     */
    @Test
    fun mapsEveryEngineResourceClassOntoARenGResourceClass() {
        val mapped = RentileResourceClass.entries.associateWith { rengResourceClassOf(it) }
        val unmapped = mapped.filterValues { it == null }.keys.toList()
        val routed = mapped.filterValues { it != null }

        assertEquals(
            emptyList(),
            unmapped,
            "RenG routes every Rentile 0.7.0 resource class; an unmapped one a future Rentile adds " +
                "must fail closed rather than being guessed at",
        )
        assertEquals(
            routed.size,
            routed.values.toSet().size,
            "the engine-to-RenG resource class correspondence must stay injective",
        )
        assertEquals(ResourceClass.BASEMAP_STYLE, rengResourceClassOf(RentileResourceClass.STYLE))
        assertEquals(ResourceClass.BASEMAP_GEO_JSON, rengResourceClassOf(RentileResourceClass.GEO_JSON))
        assertEquals(
            ResourceClass.BASEMAP_GLYPH_RANGE,
            rengResourceClassOf(RentileResourceClass.GLYPH_RANGE),
        )
    }

    /**
     * What routing `GLYPH_RANGE` buys, asserted rather than assumed: an engine glyph failure used to
     * classify as an opaque [RenGErrorCode.BASEMAP_RENDER_FAILED] naming no resource, because
     * `rengResourceClassOf` returned `null` for it. Adding [ResourceClass.BASEMAP_GLYPH_RANGE] inverts
     * for free, so the failure now names the resource and the stage it actually failed at.
     *
     * Asserted on both identity-bearing engine failures, because both reach `externalResourceFailure`
     * and the two take different codes and different stages -- reading only the acquisition one would
     * pass with the decode arm still opaque.
     */
    @Test
    fun namesTheGlyphRangeResourceOnAnEngineGlyphFailure() {
        val acquisition = classifyEngineFailure(
            acquisitionFailure(resourceClass = RentileResourceClass.GLYPH_RANGE),
        )
        assertEquals(RenGErrorCode.RESOURCE_UNAVAILABLE, acquisition.code)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, acquisition.stage)
        assertEquals(ResourceClass.BASEMAP_GLYPH_RANGE, acquisition.diagnostic?.resourceClass)
        assertEquals(acquisitionDigest, acquisition.diagnostic?.resourceKey?.stableId)
        assertEquals(ResourceKind.EXTERNAL, acquisition.diagnostic?.resourceKey?.kind)

        val decode = classifyEngineFailure(decodeFailure(resourceClass = RentileResourceClass.GLYPH_RANGE))
        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, decode.code)
        assertEquals(PipelineStage.RESOURCE_DECODING, decode.stage)
        assertEquals(ResourceClass.BASEMAP_GLYPH_RANGE, decode.diagnostic?.resourceClass)
        assertEquals(decodeDigest, decode.diagnostic?.resourceKey?.stableId)
    }

    @Test
    fun dropsTheEngineMessageAndEveryCredentialItCarries() {
        listOf(acquisitionFailure(), decodeFailure(), StylePreparationException(credentialBearingMessage))
            .forEach { engineFailure ->
                val descriptor = classifyEngineFailure(engineFailure)
                val rendered = descriptor.toString() + "|" + descriptor.toException().message

                assertTrue(
                    !rendered.contains("SIGNED-SECRET-9f3"),
                    "a credential from the engine message reached the RenG failure: $rendered",
                )
                assertTrue(
                    !rendered.contains("tiles.example.test"),
                    "a host from the engine message reached the RenG failure: $rendered",
                )
                assertNull(
                    descriptor.toException().cause,
                    "the engine failure must not be forwarded as a cause",
                )
            }
    }

    @Test
    fun rethrowsCancellationUnwrapped() {
        val cancellation = CancellationException("frame preparation cancelled")

        val thrown = assertFailsWith<CancellationException> { classifyEngineFailure(cancellation) }

        assertSame(cancellation, thrown)
    }

    @Test
    fun classifiesAnEngineFailureThatMerelyHasACancellationCause() {
        val engineFailure = ResourceDecodeException(
            message = credentialBearingMessage,
            resourceClass = RentileResourceClass.DEM_TILE,
            sanitizedResourceId = decodeDigest,
            cause = CancellationException("engine-internal cancellation"),
        )

        val descriptor = classifyEngineFailure(BatchRenderException("batch failed", engineFailure))

        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, descriptor.code)
        assertEquals(ResourceClass.BASEMAP_DEM_TILE, assertNotNull(descriptor.diagnostic).resourceClass)
    }

    @Test
    fun classifiesAWrappedFailureUpToTheUnwrapDepthCap() {
        val descriptor = classifyEngineFailure(wrappedInBatchFailures(depth = 7, innermost = decodeFailure()))

        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, descriptor.code)
        assertEquals(PipelineStage.RESOURCE_DECODING, descriptor.stage)
    }

    @Test
    fun failsClosedPastTheUnwrapDepthCap() {
        val descriptor = classifyEngineFailure(wrappedInBatchFailures(depth = 8, innermost = decodeFailure()))

        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, descriptor.code)
        assertEquals(PipelineStage.BASEMAP_RENDER, descriptor.stage)
        assertNull(descriptor.diagnostic)
    }

    @Test
    fun failsClosedForAThrowableTheEngineDidNotDeclare() {
        val descriptor = classifyEngineFailure(IllegalStateException(credentialBearingMessage))

        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, descriptor.code)
        assertEquals(PipelineStage.BASEMAP_RENDER, descriptor.stage)
        assertNull(descriptor.diagnostic)
        assertTrue(!descriptor.toString().contains("SIGNED-SECRET-9f3"))
    }

    @Test
    fun failsClosedWhenTheEngineIdentityIsNotADigest() {
        listOf("", "not-a-digest", "A".repeat(64), "a".repeat(63), "a".repeat(65)).forEach { malformed ->
            val descriptor = classifyEngineFailure(acquisitionFailure(sanitizedResourceId = malformed))

            assertEquals(
                RenGErrorCode.BASEMAP_RENDER_FAILED,
                descriptor.code,
                "a malformed engine identity must fail closed rather than throw: '$malformed'",
            )
            assertNull(descriptor.diagnostic)
        }
    }

    private fun wrappedInBatchFailures(depth: Int, innermost: RentileException): RentileException {
        var wrapped = innermost
        repeat(depth) {
            wrapped = BatchRenderException(message = credentialBearingMessage, primaryFailure = wrapped)
        }
        return wrapped
    }

    private data class ExpectedClassification(
        val failure: RentileException,
        val code: RenGErrorCode,
        val stage: PipelineStage,
        val diagnosticPresent: Boolean,
    )
}
