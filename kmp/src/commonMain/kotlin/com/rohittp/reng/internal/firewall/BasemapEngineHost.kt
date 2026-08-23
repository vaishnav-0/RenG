package com.rohittp.reng.internal.firewall

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.basemap.BasemapStyleManifestOutcome
import com.rohittp.reng.internal.basemap.BasemapTileJsonOutcome
import com.rohittp.reng.internal.basemap.parseBasemapTileJson
import com.rohittp.reng.internal.basemap.deriveBasemapStyleManifest
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.ResourceAccessMode as RenGResourceAccessMode
import com.rohittp.rentile.BasemapRasterizer
import com.rohittp.rentile.CredentialProvider
import com.rohittp.rentile.MapSessionProvider
import com.rohittp.rentile.MetricsSink
import com.rohittp.rentile.PreparedBatch
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.RawResourceStore as EngineRawResourceStore
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.Rentile
import com.rohittp.rentile.RentileClock
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.ResourceAccessMode as EngineResourceAccessMode
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.ResourceTransport as EngineResourceTransport
import com.rohittp.rentile.StoredRawResource as EngineStoredRawResource
import com.rohittp.rentile.StyleInput
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileSubstitutionPolicy
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportResponse as EngineTransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope

/**
 * The one place in RenG that constructs and drives a real Rentile engine (ADR 0016, ADR 0017).
 *
 * **Ownership.** One renderer owns one long-lived [BasemapRasterizer], created here at setup with no
 * I/O and no suspension: `Rentile.create` is not `suspend`, allocates no thread (its scope's
 * `Dispatchers.Default` spawns nothing until work is submitted), and touches neither adapter. The
 * engine's [close] is **not** GL-scoped, so it is entirely independent of the exact-current-context rule
 * governing RenG's own GL deletion (ADRs 0007/0015) — this class deletes no GL object at all.
 *
 * **Firewall lifetime.** ADR 0016 says the operation registry "is discarded when the invocation
 * terminates; it is neither a renderer-lifetime response cache nor permission to share work across
 * access modes," while the engine's own adapters are fixed for the engine's whole life. Those two facts
 * cannot both hold if [RentileConfiguration.transport] literally *is* a `FirewallTransport` bound to one
 * registry, so this class supplies the indirection ADR 0016's own wording implies: the configuration's
 * adapters are fixed for the engine's lifetime and route every call to the [FirewallTransport] /
 * [FirewallStore] of whichever preparation invocation is currently open ([withOperation]). Outside any
 * invocation they fail closed with the same sanitized `AMBIGUOUS_RESOURCE_ROUTE` an unrecognised route
 * gets, because an engine call with no active invocation is exactly an exchange RenG never planned.
 *
 * That choice also settles the question `SuspendJoin` raises: a latched cancellation (or failure) never
 * evicts, so it replays for the registry's whole lifetime. Bounding that lifetime to one invocation is
 * what keeps a later, healthy preparation from inheriting a cancellation it never earned.
 *
 * **Failures.** Every engine call routes through [engineCall]: no `RentileException` escapes this file,
 * `CancellationException` propagates unwrapped, and a [RenGException] the firewall itself raised is
 * preserved rather than reclassified. Descriptors leaving here have their identity translated out of
 * Rentile's digest namespace and into RenG's own — see [translateForeignIdentity].
 *
 * **Content policy.** [TileSubstitutionPolicy.Disabled] and the operation's access mode are passed
 * explicitly at every call, never left to the dependency's defaults: RenG "performs no repeated consumer
 * exchanges, retries, repairs, or fallbacks", and tile substitution is exactly such a fallback, so a
 * future Rentile release must not be able to turn one on for us by changing a default.
 */
internal class BasemapEngineHost(
    transport: Transport,
    store: Store,
    private val cache: ResidentCache,
    private val tileOutputSizePixels: Int = RenderOptions.DEFAULT_OUTPUT_SIZE_PX,
    private val sha256: Sha256Function = PureKotlinSha256,
    private val privateKeyResolver: RentilePrivateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
) : AutoCloseable {

    private val consumerTransport: Transport = transport
    private val consumerStore: Store = store
    private val renderOptions = RenderOptions(outputSizePx = tileOutputSizePixels)
    private val tileOutputSize = OutputPixelSize(tileOutputSizePixels, tileOutputSizePixels)

    /** The one preparation invocation currently allowed to reach the consumer, or `null` between them. */
    private var activeOperation: FirewallOperation? = null

    private var compiledStyle: CompiledStyleBinding? = null

    private var derivedManifest: StyleManifestBinding? = null

    private var closed: Boolean = false

    val isClosed: Boolean get() = closed

    private val engine: BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = RoutedEngineTransport(),
            rawResourceStore = RoutedEngineStore(),
            // Stated rather than defaulted, for the same reason as TileSubstitutionPolicy.Disabled below:
            // RenG supplies no credential and no session of its own, and a future Rentile whose defaults
            // changed must not be able to start composing one into a url the firewall never preregistered.
            sessionProvider = MapSessionProvider.None,
            credentialProvider = CredentialProvider.None,
            clock = RentileClock.System,
            metricsSink = MetricsSink.None,
        ),
    )

    /**
     * Opens exactly one preparation invocation: a fresh [OperationRegistry] preregistered with [routes],
     * installed as the engine's answer path for the duration of [block], and discarded when [block]
     * terminates however it terminates.
     *
     * [accessMode] binds the invocation's mode explicitly rather than reconstructing it from a Rentile
     * key, which Rentile's callbacks do not carry at all (ADR 0016). Every route must already agree with
     * it, since one registry is never shared across modes.
     *
     * **[block] runs inside a [coroutineScope], which is what gives the invocation a [Job] of its own.**
     * That is not tidiness: Rentile's `DefaultBasemapRasterizer.operation` reads
     * `currentCoroutineContext().job` on **every** rasterizer entry point, so a caller whose context
     * carries no `Job` makes each of them throw `IllegalStateException("Current context doesn't contain
     * Job in it")` — which reaches [classifyEngineFailure] as a non-`RentileException` and becomes the
     * opaque `BASEMAP_RENDER_FAILED`. A `suspend` function may legally be resumed from any context,
     * including `EmptyCoroutineContext`, so RenG's own public `prepare` must not depend on the consumer
     * happening to have launched it from a scope. Style compilation never showed this because it runs
     * inside [com.rohittp.reng.internal.driver.PreparationDriver.run], which already opens its own
     * `coroutineScope`; tile preparation and rendering run in this block and did not.
     *
     * The second thing the `Job` buys is containment, and it is the more important of the two. Rentile's
     * `operation` starts its work as `scope.async` in the engine's own long-lived scope *before* it reads
     * the caller's job, and links the two with `invokeOnCompletion`. With no job to link, the failure
     * escapes while that worker keeps running unowned and uncancellable — still calling back into
     * whichever [OperationRegistry] is active by the time it gets there, which is a *later* invocation's.
     * That is how a Job-less caller turned one failure into a stream of `AMBIGUOUS_RESOURCE_ROUTE`
     * refusals attributed to registries that had done nothing wrong.
     */
    suspend fun <T> withOperation(
        accessMode: RenGResourceAccessMode,
        routes: List<ResourceRouteKey> = emptyList(),
        block: suspend () -> T,
    ): T {
        check(activeOperation == null) { "a basemap engine host drives one preparation invocation at a time" }
        val registry = OperationRegistry(
            transport = consumerTransport,
            store = consumerStore,
            privateKeyResolver = privateKeyResolver,
            sha256 = sha256,
        )
        activeOperation = FirewallOperation(accessMode, registry)
        return try {
            registerRoutes(routes)
            coroutineScope { block() }
        } finally {
            activeOperation = null
        }
    }

    /**
     * Declares [routes] on the invocation that is already open.
     *
     * Preregistration cannot all happen when the invocation opens, because the routes a style's
     * compilation will make the engine ask for are not knowable until the style document has been read —
     * and reading it is itself work RenG's driver performs *inside* the invocation. This is therefore the
     * incremental half of [withOperation]'s initial list, delegating to the same
     * [OperationRegistry.preregister]: idempotent for an identical repeat (two occurrences joining one
     * route), and a hard `AMBIGUOUS_RESOURCE_ROUTE` for a genuinely conflicting one.
     *
     * Declaring a route with no invocation open is the same fault [RoutedEngineTransport] reports one
     * level later — an exchange RenG never planned — so it fails the same way, rather than silently
     * accruing routes into a registry that does not exist.
     */
    suspend fun registerRoutes(routes: List<ResourceRouteKey>) {
        requireOpen()
        val operation = activeOperation ?: throw unplannedEngineExchangeFailure()
        require(routes.all { it.accessMode == operation.accessMode }) {
            "an operation-scoped registry is never shared across access modes"
        }
        operation.registry.preregister(routes)
    }

    /**
     * The compiled [PreparedStyle] for [styleKey], compiled lazily on first use from **[stored] — the
     * content the caller is committing on this frame** — and bound to that content's digest, so a later
     * call over byte-identical content reuses it.
     *
     * **Why the caller's content and not the resident generation's.** This runs strictly *before* the
     * style's own visibility install: the pure core emits `CompileBasemapStyle` and only afterwards
     * `InstallBasemapStyleVisibility`, deliberately, because a style that fails to compile must never
     * become visible. At this point, therefore, the resident generation still carries the *previous*
     * frame's bytes — so on the one frame where a consumer's style document changes, reading either the
     * bytes or the digest from the lease compiles a document the frame is not committing, while route
     * derivation (which runs after the install) describes the one it is. The two halves of the frame then
     * disagree about which style they are on, and the frame does not merely look stale: it fails closed,
     * because the engine asks for the superseded style's urls and the firewall refuses every one of them.
     *
     * **The key and the compiled bytes move together**, and both come from [stored]. Keying on [stored]'s
     * digest while still compiling `lease.generation.stored.bytes` would be strictly worse than either
     * option: it records a compilation of the old document under the *new* document's identity, which is
     * then served for the new bytes on every later frame rather than correcting itself.
     *
     * **Why content, not generation identity.** Binding the compilation to the resident *generation
     * object* looks equivalent and is not, for the same ordering reason plus one more:
     * [ResidentCache.installAndTakeLease] always retires the current generation and installs a fresh one,
     * so the driver's install action replaces whatever generation this call observed, and every later
     * lookup would miss — recompiling the style, and with it re-running the engine's entire
     * sprite/TileJSON/GeoJSON acquisition through the consumer's own adapters, once per frame forever.
     * Nothing about "accessing a freed resource reloads it" requires recompiling identical bytes; that
     * rule is about residency, and residency is restored below whether or not the compilation is reused.
     *
     * **The lease is residency, not the source of truth for what to compile.** It is taken atomically
     * with the observation or the install ([ResidentCache.observeAndTakeLease] /
     * [ResidentCache.installAndTakeLease]), never as a separate `current()`-then-`takeLease()` pair: a
     * freshly installed generation sits at `leaseCount == 0` in the gap between those two calls, where a
     * racing `free()` can drop it out of the cache's bookkeeping entirely. Exactly one lease is held at a
     * time, and each call moves it onto whichever generation is current when the call arrives.
     *
     * A binding can therefore outlive, or briefly precede, the residency of the bytes it names: on the
     * frame the document changes, the compilation is bound to [stored]'s digest while the generation
     * leased here still holds the previous bytes, and if anything downstream fails before
     * `InstallBasemapStyleVisibility` runs those bytes never become resident at all. That is harmless,
     * and harmless *because* the key is the content digest: the binding is reused only when a later call
     * commits that same content again, which is exactly when reusing it is correct. Residency is the
     * cache's business, not the compilation's.
     *
     * The bytes are handed to the engine as [StyleInput.Prefetched], never [StyleInput.Remote]: RenG has
     * already acquired them through its own driver under its own key, and a `Remote` style would make the
     * engine fetch them a second time.
     */
    suspend fun preparedStyle(
        styleKey: ResourceKey,
        stored: StoredRawResource,
        baseUri: String?,
    ): PreparedStyle {
        requireOpen()
        val contentDigest = stored.contentDigest
        val lease = cache.observeAndTakeLease(styleKey)
            ?: cache.installAndTakeLease(styleKey, stored, decoded = null)

        val existing = compiledStyle
        if (existing != null && existing.styleKey == styleKey && existing.contentDigest == contentDigest) {
            cache.releaseLease(existing.lease)
            // The TileJSON documents travel with the compilation they were fetched for: no compilation,
            // no `resolveTileJson` call, so this invocation observed nothing to replace them with.
            compiledStyle = CompiledStyleBinding(
                styleKey,
                contentDigest,
                lease,
                existing.prepared,
                existing.tileJsonDocuments,
            )
            return existing.prepared
        }

        releaseCompiledStyle()
        // The lease is taken before compilation, and released again if compilation fails, so a style that
        // will not compile does not pin a generation for the renderer's whole life. It is a residency
        // claim on the style key, not the source of the bytes below: those are the caller's, which is the
        // whole point -- see this method's KDoc.
        val prepared = try {
            engineCall {
                engine.prepare(
                    StyleInput.Prefetched(
                        bytes = stored.bytes,
                        canonicalIdentity = styleKey.stableId,
                        baseUri = baseUri,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            cache.releaseLease(lease)
            throw failure
        }
        compiledStyle = CompiledStyleBinding(styleKey, contentDigest, lease, prepared, harvestTileJsonDocuments())
        return prepared
    }

    /**
     * Parses every TileJSON document this invocation's firewall observed while the compilation above ran.
     *
     * Harvested here rather than read at tile time because the observation is invocation-scoped and the
     * compilation is not: a `RESIDENT`-provenance frame emits no `CompileBasemapStyle` at all, so its
     * invocation calls `resolveTileJson` never, observes nothing, and would derive no tile route for a
     * `url`-form source -- failing closed on every tile of an otherwise perfectly cached style. Binding
     * the documents to the compilation instead is what makes them last exactly as long as the templates
     * Rentile itself derived from them, which is the compiled style's own lifetime.
     *
     * Parsed once, on the frame that fetched them, rather than on every frame that draws: a production
     * TileJSON carries `vector_layers` and `tilestats` blocks far larger than the routing fields RenG
     * keeps, and re-reading them per frame would repeat the cost the style manifest's own binding exists
     * to avoid.
     */
    private suspend fun harvestTileJsonDocuments(): Map<String, BasemapTileJsonOutcome> {
        val registry = activeOperation?.registry ?: return emptyMap()
        val observed = registry.observedTileJsonDocuments()
        if (observed.isEmpty()) return emptyMap()
        return observed.mapValues { (documentUrl, bytes) -> parseBasemapTileJson(bytes, documentUrl) }
    }

    /**
     * The TileJSON documents behind [styleKey]'s retained compilation, keyed by the exact url each was
     * fetched from, or empty when this host holds no compilation of [contentDigest].
     *
     * Gated on the content digest for the same reason [currentPreparedStyle] is: the routes a frame
     * derives and the program it draws with must describe one document, and a compilation retained for
     * bytes that never became resident describes another. Empty is the honest answer there, and it
     * degrades every `url`-form source rather than composing a url from a superseded document.
     */
    fun tileJsonDocuments(styleKey: ResourceKey, contentDigest: String?): Map<String, BasemapTileJsonOutcome> =
        compiledStyle
            ?.takeIf { it.styleKey == styleKey && contentDigest != null && it.contentDigest == contentDigest }
            ?.tileJsonDocuments
            .orEmpty()

    /**
     * The [PreparedStyle] this host is currently holding for [styleKey] **and for [contentDigest]**, or
     * `null` if it holds none matching both. Performs no engine call, no cache call and no consumer
     * exchange whatsoever.
     *
     * This exists because the pure core emits no `CompileBasemapStyle` at all on a `RESIDENT`-provenance
     * frame (`requiresStyleCompilation(RESIDENT)` is false) — correctly, since resident bytes were
     * compiled when they were installed. The renderer therefore cannot take the frame's style from the
     * compile action, and reads back the host's own retained compilation instead. Retained across
     * invocations on purpose: the compilation belongs to the engine's lifetime, not to one preparation.
     *
     * **Why the digest is a parameter rather than a fact this host looks up.** "Resident bytes were
     * compiled when they were installed" is the premise, and it can be false: [preparedStyle] runs before
     * `InstallBasemapStyleVisibility`, so a frame whose compilation succeeded and whose *install* never
     * ran — the style's own Store write failing is enough — leaves a compilation retained here for bytes
     * that are not resident and may never be. A later `RESIDENT`-provenance frame would then be handed
     * that compilation while `RenGRenderer.renderBasemapTiles` derives its routes from the older bytes
     * that *are* resident, and the frame fails closed on every tile at once, exactly as a mismatched
     * compile does. Matching on the key alone cannot see that; matching on the content the caller says is
     * resident can, and declining is honest: this host holds no compilation of those bytes.
     *
     * The caller supplies the digest because the caller is already reading the resident generation for
     * the manifest, and because this method's "no cache call" property is worth keeping — the point is
     * that both halves of the frame are then keyed off *one* observation of what is resident, rather than
     * two that can disagree. A `null` [contentDigest] (nothing resident under this key) matches nothing.
     */
    fun currentPreparedStyle(styleKey: ResourceKey, contentDigest: String?): PreparedStyle? =
        compiledStyle
            ?.takeIf { it.styleKey == styleKey && contentDigest != null && it.contentDigest == contentDigest }
            ?.prepared

    /**
     * The [BasemapStyleManifest] for [styleKey] -- the routes the engine will ask for while compiling the
     * style and while preparing tiles from it -- **bound to the content it was derived from**, so a later
     * call over byte-identical content reuses it rather than reading the document again.
     *
     * **Why this cache exists.** `deriveBasemapStyleManifest` runs `parseJson` over the *whole* style
     * document, and a basemap frame needs the manifest twice: once in the driver's own
     * `ValidateBasemapStyle`, to declare the style-time routes, and once again in
     * `RenGRenderer.renderBasemapTiles`, to declare the tile-time ones. Those two are the same pure
     * function of the same two inputs, so the second was pure duplication -- measured at 1.9 ms per parse
     * for a 248 KB production-shaped style on the JVM and 25.8 ms on an unoptimized native test binary
     * (`internal.basemap.BasemapRouteDerivationCostTest`), on every basemap frame. The alternative was to
     * widen the pure core's protocol so `ValidateBasemapStyle` carried its manifest out; this is the
     * ownership decision that was taken instead, and it sits here rather than in the renderer because the
     * host is the thing whose lifetime already spans frames.
     *
     * **Why content, not generation identity** -- and why the lease is taken first, atomically, and moved
     * onto the current generation on reuse: identically to [preparedStyle], whose KDoc states the whole
     * argument. The short form is that a style the consumer's transport does not declare fresh is
     * re-resolved every frame and installed as a *fresh generation of identical bytes*, so a binding to
     * the generation object would miss on every frame after the first and reparse the document forever,
     * invisibly.
     *
     * **Here, and only here, the resident generation is genuinely authoritative for the bytes.** That is
     * a fact about this method's one caller rather than a rule the codebase holds:
     * `RenGRenderer.renderBasemapTiles` runs *after* the driver's `InstallBasemapStyleVisibility` and
     * takes [stored] straight out of [ResidentCache.current], so the leased generation and the caller's
     * copy are the same document and reading either is the same read. [preparedStyle] runs *before* that
     * install, where the same claim is false by construction — it is exactly the defect that made it
     * compile the caller's content instead. The two methods reach the same conclusion for different
     * reasons; neither justifies the other.
     *
     * A [BasemapStyleManifestOutcome.Rejected] outcome is returned exactly as derived and **not** bound:
     * a document RenG cannot read is not a result to remember, and binding one would either serve a
     * refusal as though it were an answer or leave the previous document's manifest standing for bytes it
     * did not come from. The derivation itself is pure and never throws for any input, so unlike
     * [preparedStyle] there is no failure path here to release the lease on -- only the rejection one.
     *
     * Not `suspend`, unlike [preparedStyle]: this reaches no engine and no consumer adapter, and every
     * [ResidentCache] call it makes is ordinary non-suspending code (that class linearizes on
     * [kotlinx.coroutines.sync.Mutex.tryLock] rather than by suspending). It does read and parse a whole
     * style document on a miss, which is real CPU on the caller's own dispatcher -- but that is exactly
     * as true of the driver's `ValidateBasemapStyle`, and `suspend` would not change it.
     */
    fun styleManifest(
        styleKey: ResourceKey,
        stored: StoredRawResource,
        baseUri: String,
    ): BasemapStyleManifestOutcome {
        requireOpen()
        val lease = cache.observeAndTakeLease(styleKey)
            ?: cache.installAndTakeLease(styleKey, stored, decoded = null)
        val contentDigest = lease.generation.stored.contentDigest

        val existing = derivedManifest
        if (existing != null &&
            existing.styleKey == styleKey &&
            existing.baseUri == baseUri &&
            existing.contentDigest == contentDigest
        ) {
            cache.releaseLease(existing.lease)
            derivedManifest = StyleManifestBinding(styleKey, baseUri, contentDigest, lease, existing.manifest)
            return BasemapStyleManifestOutcome.Derived(existing.manifest)
        }

        releaseStyleManifest()
        // The leased generation's own bytes rather than the caller's copy, which here are the same
        // document: this method's one caller reads `stored` from ResidentCache.current() after the
        // driver's visibility install. See the KDoc -- the compilation beside this one runs before that
        // install and so must read the caller's content.
        val outcome = deriveBasemapStyleManifest(lease.generation.stored.bytes, baseUri)
        if (outcome !is BasemapStyleManifestOutcome.Derived) {
            cache.releaseLease(lease)
            return outcome
        }
        derivedManifest = StyleManifestBinding(styleKey, baseUri, contentDigest, lease, outcome.manifest)
        return outcome
    }

    /**
     * Acquires everything [tiles] need — through the firewall, so through this invocation's preregistered
     * routes and nothing else — and returns a network-free batch. Substitution is disabled explicitly.
     */
    suspend fun prepareTiles(style: PreparedStyle, tiles: List<CanonicalBasemapTile>): PreparedBasemapTiles {
        requireOpen()
        val distinct = tiles.distinct()
        val batch = engineCall {
            engine.prepareBatch(
                style = style,
                tiles = distinct.map(::engineTileIdOf),
                options = renderOptions,
                resourceAccess = engineAccessModeOf(activeOperation?.accessMode ?: RenGResourceAccessMode.NORMAL),
                substitutionPolicy = TileSubstitutionPolicy.Disabled,
            )
        }
        return PreparedBasemapTiles(batch, style, distinct)
    }

    /**
     * Draws [prepared]'s tiles. Performs no adapter call whatsoever: everything was acquired by
     * [prepareTiles], which is the whole point of Rentile's prepare/render split.
     */
    suspend fun renderTiles(prepared: PreparedBasemapTiles): List<RenderedBasemapTile> {
        requireOpen()
        val styleDigest = prepared.style.digest
        val batch = engineCall { engine.render(prepared.batch) }
        val tilesById = prepared.tiles.associateBy(::engineTileIdOf)
        return batch.tiles.map { rendered ->
            val tile = tilesById[rendered.id]
                ?: error("the engine rendered a tile this batch never asked for")
            RenderedBasemapTile(
                key = basemapTileKey(styleDigest, tile, tileOutputSize, sha256),
                tile = tile,
                pngBytes = rendered.pngBytes,
                contentKey = rendered.contentKey,
                substitutions = prepared.batch.substitutions[rendered.id].orEmpty(),
            )
        }
    }

    /** RenG's own identity for the rendered tile [tile] of [style] at this host's tile output size. */
    fun renderedTileKey(style: PreparedStyle, tile: CanonicalBasemapTile): ResourceKey =
        basemapTileKey(style.digest, tile, tileOutputSize, sha256)

    /**
     * The same identity for one unwrapped draw [instance], which the world-copy-projecting overload of
     * [basemapTileKey] reduces to its canonical tile. Asked of the host rather than derived by the
     * caller so that [tileOutputSize] -- an engine render option, not a renderer configuration value --
     * stays owned in exactly one place; a caller that guessed it would derive a key naming a tile the
     * engine never rendered.
     */
    fun renderedTileKey(styleDigest: String, instance: BasemapTileInstance): ResourceKey =
        basemapTileKey(styleDigest, instance, tileOutputSize, sha256)

    /**
     * Idempotent. Releases the compiled style's lease, then closes the engine — whose own `close()` is
     * documented idempotent, non-blocking, and non-throwing, and needs no GL context of any kind.
     */
    override fun close() {
        if (closed) return
        closed = true
        releaseCompiledStyle()
        releaseStyleManifest()
        activeOperation = null
        engine.close()
    }

    // ---- internals -------------------------------------------------------------------------------

    private fun releaseCompiledStyle() {
        compiledStyle?.let { binding -> cache.releaseLease(binding.lease) }
        compiledStyle = null
    }

    private fun releaseStyleManifest() {
        derivedManifest?.let { binding -> cache.releaseLease(binding.lease) }
        derivedManifest = null
    }

    private fun requireOpen() {
        if (closed) throw basemapRenderFailure().toException()
    }

    private fun engineTileIdOf(tile: CanonicalBasemapTile): TileId =
        TileId(z = tile.lod, x = tile.canonicalX, y = tile.tileY)

    /**
     * Exhaustive over RenG's own mode enum rather than defaulted, so a mode RenG adds later fails this
     * file's compilation instead of silently arriving at the engine as `NORMAL`.
     */
    private fun engineAccessModeOf(mode: RenGResourceAccessMode): EngineResourceAccessMode = when (mode) {
        RenGResourceAccessMode.NORMAL -> EngineResourceAccessMode.NORMAL
        RenGResourceAccessMode.CACHE_ONLY -> EngineResourceAccessMode.CACHE_ONLY
        RenGResourceAccessMode.RELOAD -> EngineResourceAccessMode.RELOAD
    }

    /**
     * The one seam every engine call passes through.
     *
     * A [CancellationException] propagates unwrapped ([classifyEngineFailure] rethrows it before
     * classifying anything), and is asserted on by type rather than identity everywhere downstream
     * because Kotlin's stack recovery may hand back a copy carrying the original as its immediate cause.
     *
     * A [RenGException] is rethrown as-is, so that a firewall failure which reaches this seam unwrapped
     * keeps its precise code, stage, and resource rather than being reclassified: [classifyEngineFailure]
     * would see a non-`RentileException` and answer the opaque `BASEMAP_RENDER_FAILED`. Stated honestly,
     * this branch is defensive and **no test covers it**, because Rentile 0.5.0 wraps every adapter
     * throwable at every call site it has — `RasterResourceAcquirer` turns a store read fault into its own
     * `ResourceStoreException` and a transport fault into its own `ResourceAcquisitionException`, dropping
     * the cause in both — so a RenG failure raised inside an adapter arrives here already converted. That
     * conversion is a genuine loss this branch cannot recover; it exists so that a Rentile release which
     * stops wrapping, or a path that never did, does not silently downgrade a good failure.
     */
    private inline fun <T> engineCall(block: () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rengFailure: RenGException) {
            throw rengFailure
        } catch (@Suppress("TooGenericExceptionCaught") engineFailure: Throwable) {
            throw translateForeignIdentity(classifyEngineFailure(engineFailure)).toException()
        }

    /**
     * Rewrites a classified engine failure's resource identity out of **Rentile's** namespace and into
     * RenG's own.
     *
     * [classifyEngineFailure] can only report the digest Rentile gave it — `sha256Hex(redacted url)` —
     * because it holds no locator. That digest is well-formed, credential-free, and completely wrong as
     * an identity a consumer can act on: passed to `ResourceSelector.ByKey` it produces a **silent empty
     * selection**, not an error. This host does hold the locators, by way of the invocation's own
     * [OperationRegistry], so it is where the translation belongs.
     *
     * When the digest names no preregistered route the descriptor is returned untouched. That is the
     * least-bad answer available: the failure rules that admit an identity here require one
     * (`REQUIRED_EXTERNAL`), so the key cannot simply be dropped, and collapsing the whole failure to
     * `BASEMAP_RENDER_FAILED` would throw away a truthful code, stage, and resource class to hide one
     * untranslatable field.
     *
     * [com.rohittp.reng.ResourceClass.BASEMAP_STYLE] is the sub-case worth naming explicitly, because
     * `ProductionRentilePrivateKeyResolver` deliberately does *not* use Rentile's digest for the style —
     * it uses RenG's own canonical identity — so nothing in RenG's key derivation ever computes the
     * digest a style failure would carry. It is translatable all the same, and is translated here:
     * Rentile's `acquireRemoteStyle` reports exactly `sha256Hex(withRedactedAuthenticationQuery(url))`,
     * the same scheme as the seven engine-keyed classes, and [OperationRegistry] indexes style routes by
     * that digest even though the engine's raw store never sees a style. In RenG's own usage the case is
     * additionally unreachable: styles are handed to the engine as [StyleInput.Prefetched], so
     * `acquireRemoteStyle` never runs.
     */
    private fun translateForeignIdentity(descriptor: FailureDescriptor): FailureDescriptor {
        val diagnostic = descriptor.diagnostic ?: return descriptor
        val engineKey = diagnostic.resourceKey ?: return descriptor
        val resourceClass = engineKey.resourceClass ?: return descriptor
        val registry = activeOperation?.registry ?: return descriptor
        val rengKey = registry.renGResourceKeyForEngineDigest(resourceClass, engineKey.stableId)
            ?: return descriptor
        if (rengKey == engineKey) return descriptor

        return FailureDescriptor(
            code = descriptor.code,
            stage = descriptor.stage,
            diagnostic = failureContextDiagnostic(
                stage = diagnostic.stage,
                fieldName = DiagnosticField.RESOURCE,
                resourceClass = resourceClass,
                resourceKey = rengKey,
            ),
        )
    }

    /** One open preparation invocation: its access mode, its registry, and the firewall adapters over it. */
    private class FirewallOperation(
        val accessMode: RenGResourceAccessMode,
        val registry: OperationRegistry,
    ) {
        val transport: EngineResourceTransport = FirewallTransport(registry)
        val store: EngineRawResourceStore = FirewallStore(registry)
    }

    private inner class RoutedEngineTransport : EngineResourceTransport {
        override suspend fun execute(request: EngineTransportRequest): EngineTransportResponse {
            val operation = activeOperation ?: throw unplannedEngineExchangeFailure()
            return operation.transport.execute(request)
        }
    }

    private inner class RoutedEngineStore : EngineRawResourceStore {
        override suspend fun read(key: EngineRawResourceKey): EngineStoredRawResource? {
            val operation = activeOperation ?: throw unplannedEngineExchangeFailure()
            return operation.store.read(key)
        }

        override suspend fun write(key: EngineRawResourceKey, resource: EngineStoredRawResource) {
            val operation = activeOperation ?: throw unplannedEngineExchangeFailure()
            operation.store.write(key, resource)
        }

        override suspend fun remove(key: EngineRawResourceKey) {
            // Private and terminal whether or not an invocation is open (ADR 0016): RenG's own Store has
            // no remove operation at all, so there is nothing to forward and nothing to fail closed on.
            activeOperation?.store?.remove(key)
        }
    }

    /**
     * One compilation, the exact content digest it was compiled from, and a lease held for as long as the
     * binding is. [contentDigest] rather than the [Lease]'s own generation is what decides reuse — see
     * [preparedStyle], which is also why the lease and the digest can describe *different* documents for
     * one frame: on the frame a style is edited the lease is on the generation still resident, while the
     * digest is the one being committed. The lease is residency bookkeeping, not a record of what was
     * compiled.
     */
    private class CompiledStyleBinding(
        val styleKey: ResourceKey,
        val contentDigest: String,
        val lease: Lease,
        val prepared: PreparedStyle,
        /**
         * Every TileJSON document the engine fetched while compiling [prepared], parsed, by url. The
         * compiled style already holds the tile templates these yielded and will not fetch them again, so
         * they belong to its lifetime rather than to the invocation that observed them.
         */
        val tileJsonDocuments: Map<String, BasemapTileJsonOutcome>,
    )

    /**
     * One derived manifest, the exact content digest and base URI it was derived from, and the lease
     * keeping that content resident. The base URI takes part because every relative reference in the
     * document resolves against it, so the same bytes under a different locator are a different manifest.
     */
    private class StyleManifestBinding(
        val styleKey: ResourceKey,
        val baseUri: String,
        val contentDigest: String,
        val lease: Lease,
        val manifest: BasemapStyleManifest,
    )
}

/**
 * One prepared, fully-acquired basemap batch, plus the RenG tiles it was asked for. [close] is
 * idempotent, non-blocking, and non-throwing, because Rentile documents `PreparedBatch.close()` as
 * exactly that.
 */
internal class PreparedBasemapTiles(
    internal val batch: PreparedBatch,
    internal val style: PreparedStyle,
    internal val tiles: List<CanonicalBasemapTile>,
) : AutoCloseable {
    override fun close() {
        batch.close()
    }
}

/** One rendered ground tile: RenG's own identity, the encoded pixels, and the engine's own provenance. */
internal class RenderedBasemapTile(
    val key: ResourceKey,
    val tile: CanonicalBasemapTile,
    val pngBytes: ByteArray,
    val contentKey: String,
    val substitutions: List<ResourceSubstitution>,
)

/**
 * RenG's own canonical identity (ADR 0018) for one rendered basemap tile. See
 * [ResourceKeyDeriver.basemapTile] for why this is derived rather than taken from
 * `BasemapRasterizer.outputRequestKey`, and why it keys on the canonical tile rather than on an
 * unwrapped world-copy instance.
 */
internal fun basemapTileKey(
    styleDigest: String,
    tile: CanonicalBasemapTile,
    outputSize: OutputPixelSize,
    sha256: Sha256Function = PureKotlinSha256,
): ResourceKey = ResourceKeyDeriver(sha256).basemapTile(styleDigest, tile, outputSize).key

/**
 * The same identity for one **unwrapped draw instance**: the world copy is projected away before the key
 * is derived, so every instance of one canonical tile shares one rendered-tile resource and one engine
 * render. Deliberately drops [BasemapTileInstance.unwrappedX] and [BasemapTileInstance.instanceCopy] --
 * keying on either would re-render identical ground once per visible Mercator world copy, which is the
 * opposite of what `BasemapTileSelector` emitting `canonicalResources` separately from `instances` is for.
 */
internal fun basemapTileKey(
    styleDigest: String,
    instance: BasemapTileInstance,
    outputSize: OutputPixelSize,
    sha256: Sha256Function = PureKotlinSha256,
): ResourceKey = basemapTileKey(
    styleDigest = styleDigest,
    tile = CanonicalBasemapTile(
        lod = instance.lod,
        tileY = instance.tileY,
        canonicalX = instance.canonicalX,
    ),
    outputSize = outputSize,
    sha256 = sha256,
)

/**
 * An engine exchange with no preparation invocation open at all. Reported as the same sanitized
 * `AMBIGUOUS_RESOURCE_ROUTE` [OperationRegistry] uses for an unrecognised url or store key, because it is
 * the same fault seen one level earlier: an exchange RenG never planned.
 */
private fun unplannedEngineExchangeFailure(): RenGException = FailureDescriptor(
    code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
    stage = PipelineStage.RESOURCE_LOOKUP,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.RESOURCE_LOOKUP,
        fieldName = DiagnosticField.RESOURCE,
    ),
).toException()

/** The failure for work asked of an already-closed host: the ground did not draw, and nothing more. */
private fun basemapRenderFailure(): FailureDescriptor =
    FailureDescriptor(code = RenGErrorCode.BASEMAP_RENDER_FAILED, stage = PipelineStage.BASEMAP_RENDER)
