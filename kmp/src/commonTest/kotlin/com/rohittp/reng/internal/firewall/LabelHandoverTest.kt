package com.rohittp.reng.internal.firewall

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.rentile.GlyphRangeRef
import com.rohittp.rentile.GlyphTemplateMismatchException
import com.rohittp.rentile.LabelCandidatePlan
import com.rohittp.rentile.LabelCandidatePlanClosedException
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.RentileException
import com.rohittp.rentile.TileId
import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.ResourceClass as EngineResourceClass
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportRequestMetadata as EngineTransportRequestMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The Label handover across ADR 0016's firewall: one operation, two rounds of preregistration, and the
 * three failures the handover spike found by running rather than reading.
 *
 * **Everything here is about routing, which is why it lives in `commonTest`.** The closure, the second
 * preregistration round, the refusals and the url composition all run on `macosArm64`,
 * `iosSimulatorArm64` and the Android host JVM alike. The one thing that does not is the batch itself:
 * `acquireLabelCandidates` ends in Rentile's Skia glyph packer, which the Android host runtime cannot
 * load, so every case below either expects a failure or reads the acquisition through
 * [kotlin.runCatching] and asserts what the consumer's own adapters saw. `LabelHandoverBatchTest` in
 * `nativeTest` is where the batch's contents are read.
 *
 * That split is not a compromise: the routing half is the half ADR 0016 is about, and it is the half an
 * Android consumer most needs gated.
 */
class LabelHandoverTest {

    // ---- the closure, measured in both directions -------------------------------------------------

    /**
     * The assertion with actual power, and it is deliberately four assertions rather than one.
     *
     * A one-directional check passes against a superset, and "not a superset" is exactly the property
     * Rentile's `LabelCandidatePlan` claims ("not an estimate") and this whole handover depends on. So:
     * every url RenG preregistered was requested, every url requested was one RenG preregistered, each
     * exactly once, and the set itself equals a literal written out by hand. The literal is what stops
     * the case from passing vacuously -- an empty closure satisfies set equality in both directions --
     * and the fixture is asymmetric in both axes so that a closure returning one range per stack, one
     * range per feature, or the right count from the wrong blocks all fail.
     */
    @Test
    fun preregistersExactlyTheGlyphUrlsTheAcquisitionThenAsksFor() = runTest {
        val transport = LabelRecordingTransport()
        val host = basemapEngineHost(transport = transport, store = LabelRecordingStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                // The acquisition's own result is deliberately not read: Rentile packs the atlas through
                // Skia, which the Android host runtime cannot load, and the whole point of this case is
                // that it gates routing on that target too. Every fetch has already happened by the time
                // the packer runs, so the consumer's own recorder is complete either way.
                runCatching { host.acquireLabelHandover(host.labelStyle()) }

                val requestedGlyphUrls = transport.requestedGlyphUrls()
                assertEquals(
                    labelGlyphUrls().toSet(),
                    requestedGlyphUrls.toSet(),
                    "nothing preregistered went unfetched, and nothing unpreregistered was fetched",
                )
                assertEquals(
                    labelGlyphUrls().size,
                    requestedGlyphUrls.size,
                    "and each was fetched exactly once",
                )
                assertEquals(
                    List(labelGlyphUrls().size) { ResourceClass.BASEMAP_GLYPH_RANGE },
                    transport.requestedClasses().filter { it != ResourceClass.BASEMAP_VECTOR_TILE },
                    "every glyph exchange reaches the consumer under RenG's own glyph class",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * Planning acquires the label layers' vector tiles and freezes the closure without fetching a glyph
     * byte -- which is the entire reason the plan-taking overload exists and the one-shot
     * `acquireLabelCandidates(style, tiles, mode)` overload is never called.
     *
     * Observed through ordering rather than through a mid-call snapshot, because the two halves are one
     * method here: the tile is the *first* url and the only non-glyph one, so every glyph fetch happened
     * strictly after planning returned. It is fetched exactly once, which also rules out the shape where
     * acquisition re-plans.
     */
    @Test
    fun planningFetchesTheLabelTileAndNotOneGlyphByte() = runTest {
        val transport = LabelRecordingTransport()
        val host = basemapEngineHost(transport = transport, store = LabelRecordingStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                runCatching { host.acquireLabelHandover(host.labelStyle()) }
                val urls = transport.requestedUrls()
                assertEquals(LABEL_TILE_URL, urls.firstOrNull(), "the label tile is fetched before any glyph")
                assertEquals(1, urls.count { it == LABEL_TILE_URL }, "and exactly once")
                assertEquals(
                    labelGlyphUrls().toSet(),
                    urls.drop(1).toSet(),
                    "everything after it is a Glyph Range and nothing else",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * The empty closure, asserted as its own outcome rather than left to be indistinguishable from the
     * case above.
     *
     * A style with no `glyphs` key is legal and common, and Rentile short-circuits it *before* it
     * acquires the label tiles -- so nothing at all is fetched, not even the label tile. This is the
     * single most dangerous shape a label suite can have, because it satisfies every set-equality
     * assertion above vacuously and is one missing style key away at all times. Naming it as a distinct,
     * *zero-exchange* outcome is what makes the case above mean something.
     *
     * The batch itself is read in `nativeTest`: even an empty glyph set is packed into a 1x1 placeholder
     * atlas, and Skia is what encodes it.
     */
    @Test
    fun aStyleWithNoGlyphsKeyAcquiresNothingAtAll() = runTest {
        val transport = LabelRecordingTransport()
        val store = LabelRecordingStore()
        val host = basemapEngineHost(transport = transport, store = store)
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val style = host.labelStyle(LABEL_STYLE_JSON_WITHOUT_GLYPHS)
                runCatching { host.acquireLabelHandover(style, glyphTemplate = null) }
                assertEquals(emptyList(), transport.requestedUrls(), "no glyphs means no acquisition at all")
                assertEquals(emptyList(), store.readClasses(), "and no store lookup either")
            }
        } finally {
            host.close()
        }
    }

    // ---- failure 1: a Glyph Range the firewall refused --------------------------------------------

    /**
     * The refusal the handover spike measured, at the level it actually happens: an unrouted glyph
     * lookup meets the **store** index first, because Rentile's `GlyphResourceAcquirer` reads its raw
     * store before it touches transport.
     *
     * The refusal itself is complete and always was -- no byte reaches the consumer. What is new is that
     * the registry now *remembers* it, because Rentile's own store helper converts RenG's
     * `AMBIGUOUS_RESOURCE_ROUTE` into a causeless `ResourceStoreException` and the acquisition wraps that
     * in a `BatchRenderException`, leaving `classifyEngineFailure` nothing to report but the opaque
     * `BASEMAP_RENDER_FAILED`.
     *
     * The transport half is asserted too: in `RELOAD` mode Rentile skips its store read entirely, so the
     * transport index is the first gate a glyph url meets there, and an observation that only watched
     * the store would go blind on exactly that access mode.
     */
    @Test
    fun remembersEveryGlyphLookupTheFirewallRefused() = runTest {
        val registry = OperationRegistry(
            transport = LabelRecordingTransport(),
            store = LabelRecordingStore(),
            privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
        )
        // Two *different* Glyph Ranges, one per index. Probing both indices with one url would let
        // either recording alone satisfy the assertion below, which is the shape that hides a missing
        // one -- and the two indices are reached under different access modes, so both must record.
        val storeRefusedUrl = labelGlyphUrls()[0]
        val transportRefusedUrl = labelGlyphUrls()[1]

        val storeRefusal = assertFailsWith<RenGException> {
            registry.readStore(
                EngineRawResourceKey(redactedDigestOf(storeRefusedUrl), EngineResourceClass.GLYPH_RANGE),
            )
        }
        assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, storeRefusal.code)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, storeRefusal.stage)

        val transportRefusal = assertFailsWith<RenGException> {
            registry.executeTransport(
                EngineTransportRequest(
                    url = transportRefusedUrl,
                    resourceClass = EngineResourceClass.GLYPH_RANGE,
                    maxResponseBytes = 1L shl 20,
                    metadata = EngineTransportRequestMetadata(),
                ),
            )
        }
        assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, transportRefusal.code)

        assertEquals(
            setOf(redactedDigestOf(storeRefusedUrl), redactedDigestOf(transportRefusedUrl)),
            registry.refusedGlyphLookupDigests(),
            "both indices name the Glyph Range they refused, by its redacted-url digest",
        )
    }

    /**
     * Scoping, asserted rather than assumed: a refused lookup for any other class records nothing.
     *
     * The observation exists for one caller, and an observation nothing reads is bookkeeping that rots.
     * Widening it to every class is a separate decision, and this case is what would fail if someone
     * took it accidentally.
     */
    @Test
    fun remembersNothingAboutARefusalThatWasNotAGlyphRange() = runTest {
        val registry = OperationRegistry(
            transport = LabelRecordingTransport(),
            store = LabelRecordingStore(),
            privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
        )
        assertFailsWith<RenGException> {
            registry.readStore(EngineRawResourceKey(redactedDigestOf(LABEL_TILE_URL), EngineResourceClass.VECTOR_TILE))
        }
        assertEquals(emptySet(), registry.refusedGlyphLookupDigests())
    }

    /**
     * How a refused acquisition is read back. Both verdicts report the same public failure; they exist
     * because the two are different faults with different fixes, and a diagnosis with no name is the
     * mystery this task set out to remove.
     *
     * The mixed case is the one worth writing down: one unrecognised digest outranks any number of
     * recognised ones, because a run with a route-derivation defect in it should not send anyone
     * chasing a credential.
     */
    @Test
    fun readsARefusedAcquisitionBackAgainstWhatWasPreregistered() {
        val derived = labelGlyphUrls().map(::redactedDigestOf).toSet()
        val foreign = redactedDigestOf("https://elsewhere.example/Other/0-255.pbf")

        assertNull(
            classifyGlyphRouteRefusal(refusedDigests = emptySet(), preregisteredDigests = derived),
            "a failure with no refusal behind it belongs to something else entirely",
        )
        assertEquals(
            GlyphRouteRefusal.NOT_DERIVED,
            classifyGlyphRouteRefusal(refusedDigests = setOf(foreign), preregisteredDigests = derived),
        )
        assertEquals(
            GlyphRouteRefusal.CREDENTIAL_MISMATCH,
            classifyGlyphRouteRefusal(refusedDigests = derived, preregisteredDigests = derived),
        )
        assertEquals(
            GlyphRouteRefusal.NOT_DERIVED,
            classifyGlyphRouteRefusal(refusedDigests = derived + foreign, preregisteredDigests = derived),
            "one url RenG never derived outranks any number it did",
        )
    }

    // ---- failure 2: an engine exception type crossing RenG's boundary ------------------------------

    /**
     * `glyphUrls` is a method on the plan object rather than a call through the rasterizer, so nothing
     * about it passes through the firewall's classifying seam on its own -- and the spike watched both
     * of its exceptions cross RenG's public boundary as themselves.
     *
     * A `GlyphTemplateMismatchException` is given a name of its own rather than merely being sanitized:
     * the template RenG derived from the style document is not the one the engine compiled from the same
     * document, so RenG cannot say which resource any glyph url names -- which is
     * `AMBIGUOUS_RESOURCE_ROUTE` exactly. A `LabelCandidatePlanClosedException` would mean RenG read a
     * plan it had already closed, a defect of its own with nothing truthful to add, so it is sanitized
     * to the opaque basemap failure instead.
     *
     * Driven through fakes rather than through a real plan because the second case is unreachable by
     * construction: [BasemapEngineHost.acquireLabelCandidates] closes the plan in a `finally` and never
     * touches it again.
     */
    @Test
    fun keepsBothOfTheEnginesGlyphUrlExceptionsInsideTheFirewall() {
        val host = basemapEngineHost()
        try {
            val mismatch = assertFailsWith<RenGException> {
                host.glyphUrlsOf(ThrowingLabelCandidatePlan(GlyphTemplateMismatchException()), LABEL_GLYPH_TEMPLATE)
            }
            assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, mismatch.code)
            assertEquals(PipelineStage.RESOURCE_LOOKUP, mismatch.stage)
            // `assertFailsWith<RenGException>` is itself the boundary assertion: RenGException is not a
            // RentileException, so a `GlyphTemplateMismatchException` escaping as itself fails there.

            val closed = assertFailsWith<RenGException> {
                host.glyphUrlsOf(ThrowingLabelCandidatePlan(LabelCandidatePlanClosedException()), LABEL_GLYPH_TEMPLATE)
            }
            assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, closed.code)
            assertEquals(PipelineStage.BASEMAP_RENDER, closed.stage)
        } finally {
            host.close()
        }
    }

    /**
     * The same mismatch reached the way a consumer would reach it: a glyphs template whose *redacted*
     * form disagrees with the engine's. Nothing beyond the label tile is fetched, so the disagreement
     * costs one tile rather than three refused Glyph Ranges.
     */
    @Test
    fun namesATemplateWhoseRedactedFormDisagreesBeforeAnyGlyphIsFetched() = runTest {
        val transport = LabelRecordingTransport()
        val store = LabelRecordingStore()
        val host = basemapEngineHost(transport = transport, store = store)
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val failure = assertFailsWith<RenGException> {
                    host.acquireLabelHandover(host.labelStyle(), glyphTemplate = LABEL_FOREIGN_GLYPH_TEMPLATE)
                }
                assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.code)
                assertEquals(PipelineStage.RESOURCE_LOOKUP, failure.stage)
                assertEquals(listOf(LABEL_TILE_URL), transport.requestedUrls())
                assertEquals(
                    listOf(ResourceClass.BASEMAP_VECTOR_TILE),
                    store.readClasses(),
                    "the acquisition never started, so no Glyph Range was even looked up",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * The other half of the same disagreement: the engine froze a non-empty closure and RenG holds no
     * template at all. Named before a byte moves, because every url the acquisition would then compose
     * is one this firewall refuses -- spending three refusals to reach the same conclusion less clearly.
     */
    @Test
    fun namesANonEmptyClosureRenGHoldsNoTemplateFor() = runTest {
        val transport = LabelRecordingTransport()
        val host = basemapEngineHost(transport = transport, store = LabelRecordingStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val failure = assertFailsWith<RenGException> {
                    host.acquireLabelHandover(host.labelStyle(), glyphTemplate = null)
                }
                assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.code)
                assertEquals(listOf(LABEL_TILE_URL), transport.requestedUrls())
            }
        } finally {
            host.close()
        }
    }

    // ---- failure 3: the credential glyphUrls structurally cannot check ----------------------------

    /**
     * The one `glyphUrls` cannot catch, and the reason this task exists.
     *
     * Rentile compares only the **redacted** forms of the two templates, so a stale or wrong credential
     * agrees exactly where the check looks. The caller "gets back a plausible, non-empty list whose every
     * url is wrong", RenG preregisters all three, and the engine then acquires with *its* credential.
     *
     * **What that looks like through the firewall, measured here rather than predicted.** The store
     * index is keyed on the redacted digest, so it *matches*: the consumer's `Store` is asked for all
     * three Glyph Ranges. The transport index is keyed on the exact string, so it *does not*: not one
     * glyph url reaches the consumer's `Transport`, and no glyph bytes are written. Every glyph route
     * preregistered, and none matched.
     *
     * That corrects the handover spike, which measured the store index as the gate that fires and had no
     * fixture with a credential in it to see this second shape. Both are real; which one fires depends
     * on whether the redacted forms agree.
     *
     * Without the registry's refusal record this arrives as `BASEMAP_RENDER_FAILED` and nothing else --
     * a labelless map and a code that says only "the basemap did not render".
     */
    @Test
    fun namesAStaleCredentialThatOnlyTheAcquisitionCanSee() = runTest {
        val transport = LabelRecordingTransport()
        val store = LabelRecordingStore()
        val host = basemapEngineHost(transport = transport, store = store)
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val failure = assertFailsWith<RenGException> {
                    host.acquireLabelHandover(host.labelStyle(), glyphTemplate = LABEL_STALE_GLYPH_TEMPLATE)
                }
                assertEquals(
                    RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
                    failure.code,
                    "the firewall's own refusal, recovered from behind Rentile's causeless store wrapper",
                )
                assertEquals(PipelineStage.RESOURCE_LOOKUP, failure.stage)
                assertTrue(
                    failure.diagnostics.all { it.resourceKey == null },
                    "this code admits no identity, and RenG genuinely cannot say which range it lost",
                )
                assertEquals(
                    listOf(LABEL_TILE_URL),
                    transport.requestedUrls(),
                    "not one glyph url reached the consumer: the exact strings never matched",
                )
                assertEquals(
                    mapOf(
                        ResourceClass.BASEMAP_VECTOR_TILE to 1,
                        ResourceClass.BASEMAP_GLYPH_RANGE to 3,
                    ),
                    store.readClasses().groupingBy { it }.eachCount(),
                    "the store index matched all three: the redacted forms agree and only the credential differs",
                )
                assertEquals(
                    listOf(ResourceClass.BASEMAP_VECTOR_TILE),
                    store.writtenClasses(),
                    "and no glyph bytes were persisted",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * The label tile itself is firewalled exactly as every other tile is: unpreregistered, it fails the
     * frame closed rather than quietly producing no labels.
     *
     * It arrives opaque -- `BASEMAP_RENDER_FAILED` -- and that is the standing cost of Rentile's causeless
     * store wrapper for every class RenG does not observe refusals for. The label handover buys back the
     * glyph class alone; naming an unroutable label *source* is E-labels' own later task, and it does it
     * before the call rather than after.
     */
    @Test
    fun failsClosedWhenTheLabelTileRouteWasNeverDeclared() = runTest {
        val transport = LabelRecordingTransport()
        val host = basemapEngineHost(transport = transport, store = LabelRecordingStore())
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                val failure = assertFailsWith<RenGException> {
                    host.acquireLabelHandover(host.labelStyle(), labelTileRoutes = emptyList())
                }
                assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, failure.code)
                assertEquals(emptyList(), transport.requestedUrls(), "the refusal is complete")
            }
        } finally {
            host.close()
        }
    }
}

// ---- shared drivers -------------------------------------------------------------------------------

/** The fixture's style, compiled through the host so the handover reads a compilation it owns. */
internal suspend fun BasemapEngineHost.labelStyle(json: String = LABEL_STYLE_JSON) =
    preparedStyle(labelStyleKey, hostStyleRecord(json), LABEL_STYLE_BASE_URI)

/** One handover over the fixture, with every knob a case here needs to vary. */
internal suspend fun BasemapEngineHost.acquireLabelHandover(
    style: PreparedStyle,
    glyphTemplate: String? = LABEL_GLYPH_TEMPLATE,
    labelTileRoutes: List<ResourceRouteKey> = listOf(labelTileRoute),
): AcquiredLabelCandidates = acquireLabelCandidates(
    style = style,
    tiles = listOf(LABEL_TILE),
    glyphTemplate = glyphTemplate,
    labelTileRoutes = labelTileRoutes,
    limits = ResourceLimits(),
)

/** `sha256Hex(withRedactedAuthenticationQuery(url))`, the digest both firewall indices are keyed on. */
internal fun redactedDigestOf(url: String): String =
    PureKotlinSha256.digest(CanonicalBytes(redactAuthenticationQuery(url).encodeToByteArray())).lowercaseHex

/** A plan whose only behaviour is the exception [failure] its `glyphUrls` throws. */
private class ThrowingLabelCandidatePlan(private val failure: RentileException) : LabelCandidatePlan {
    override val tiles: List<TileId> = listOf(TileId(1, 0, 0))
    override val glyphClosure: List<GlyphRangeRef> = listOf(GlyphRangeRef("digest", 0))
    override val diagnostics: List<RenderDiagnostic> = emptyList()

    override fun glyphUrls(template: String): List<String> = throw failure

    override fun close() = Unit
}
