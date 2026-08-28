package com.rohittp.reng.internal.firewall

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportRequestMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.RawResourceKey as RenGRawResourceKey
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.freshCopy
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.PngScan
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.image.scanPng
import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.TransportLatchKey
import com.rohittp.reng.internal.driver.validatesDemTerrainEncoding
import com.rohittp.reng.internal.resource.copyValidStoredResource
import com.rohittp.reng.internal.resource.isValidMetadata
import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.RawResourceMetadata as EngineRawResourceMetadata
import com.rohittp.rentile.ResourceClass as EngineResourceClass
import com.rohittp.rentile.StoredRawResource as EngineStoredRawResource
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportResponse as EngineTransportResponse
import com.rohittp.rentile.TransportResponseMetadata as EngineTransportResponseMetadata
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ADR 0016's operation-scoped firewall state: the routes one active preparation invocation
 * preregistered, and the joined answers those routes have produced so far for Rentile's own
 * `ResourceTransport`/`RawResourceStore` callbacks. [FirewallTransport] and [FirewallStore] are thin
 * adapters over this class; this is where the actual join/latch/validate work happens.
 *
 * One instance lives exactly as long as one preparation invocation: "discarded when the invocation
 * terminates" (ADR 0016), never a renderer-lifetime cache and never shared across access modes.
 * [BasemapEngineHost.withOperation] is what enforces that lifetime against the renderer's one long-lived
 * Rentile engine, and it is also what makes this class's never-evicting [SuspendJoin] safe: a latched
 * cancellation or failure replays for the registry's whole lifetime, so bounding that lifetime to one
 * invocation is what stops a later, healthy preparation inheriting an outcome it never earned.
 *
 * [preregister] is the setup step a caller (a later task's driver) uses to declare, before Rentile is
 * ever invoked, every `(accessMode, locator, resourceClass, maximumResponseBytes)` route this
 * invocation may need. [executeTransport], [readStore], [writeStore], and [removeStore] are the
 * answer paths Rentile's own adapters call into.
 *
 * ## Concurrency
 *
 * **Every answer path here runs on a thread this object's writer does not control.** Rentile's
 * `DefaultBasemapRasterizer.operation` runs each engine call as `scope.async { ... }` in the *engine's
 * own* long-lived `Dispatchers.Default` scope rather than as a child of the caller's coroutine, and it
 * fans a tile batch out at concurrency eight (ADR 0016's 256-tile batch). Two consequences follow, and
 * both are load-bearing for everything below. Answer paths for different routes genuinely run in
 * parallel with each other. And a worker can outlive the call that started it -- when the caller's own
 * coroutine fails before awaiting, the `async` is already running -- so it can still be calling into a
 * registry while a *later* invocation's driver is preregistering into one.
 *
 * Because of that, every piece of mutable state in this file is enumerated and given exactly one
 * owner. Nothing here may be a bare `mutableMapOf` reachable from an answer path:
 *
 *  - the five route indices -- one immutable [RouteIndex] snapshot behind a `@Volatile` reference;
 *    writers serialize on [routeIndexMutex], readers take no lock at all;
 *  - `lastTransportDigestByRoute` -- `lastTransportDigestMutex`;
 *  - `observedTileJsonByUrl` -- `tileJsonObservationMutex`;
 *  - each of [transportJoin], [storeReadJoin], [storeWriteJoin] and [spritePairJoin] -- that
 *    `SuspendJoin`'s own mutex, over its own `inFlight` map;
 *  - [spriteRendezvous]'s slot table and each slot's fields -- that object's own mutex; a slot's
 *    `arrived` is a `CompletableDeferred`, which is safe on its own.
 *
 * Everything else this class holds is immutable or stateless: `transport` and `store` are the
 * consumer's own objects, and `resourceKeyDeriver`, `privateKeyResolver` and `sha256` are pure
 * functions over their arguments with no fields that change.
 *
 * **The lock graph is a set of leaves, and stays one.** No lock in this file is ever acquired while
 * another is held, so no cycle can exist and the order of acquisition never matters. Three facts keep
 * that true: `SuspendJoin.run` releases its own mutex *before* running the block, so every lock taken
 * inside a block is taken with nothing held; every critical section here is pure bookkeeping, with
 * `complete`/`await` and every consumer call outside it; and the route indices are read with no lock
 * whatsoever, which is what lets a lookup sit anywhere -- including inside a join block -- without
 * ever nesting.
 */
internal class OperationRegistry(
    private val transport: Transport,
    private val store: Store,
    private val privateKeyResolver: RentilePrivateKeyResolver,
    private val sha256: Sha256Function = PureKotlinSha256,
) {
    private val resourceKeyDeriver = ResourceKeyDeriver(sha256)

    // Every preregistered route, indexed five ways, as ONE immutable [RouteIndex] snapshot behind a
    // volatile reference. [preregister] replaces that reference; every answer path reads it. A route
    // absent from the relevant index fails closed rather than being forwarded -- this is the whole
    // point of the firewall, so a reader that cannot see a route the writer already declared is a
    // total, silent basemap outage rather than a slightly stale lookup.
    //
    // **Why a snapshot and not five plain maps.** The maps were plain `mutableMapOf`s, written on the
    // coroutine that prepares and read from Rentile's own `Dispatchers.Default` workers with no
    // synchronization and no safe-publication barrier of any kind. Preregistration is *incremental*
    // ([BasemapEngineHost.registerRoutes] exists precisely so a style commit can declare the routes it
    // only discovered mid-invocation), and Rentile's `DefaultBasemapRasterizer.operation` runs each
    // engine call in the engine's *own* long-lived scope rather than as a child of the caller -- so a
    // worker outliving the call that started it can genuinely read these maps while a later invocation
    // is still writing them. That is a data race, and an unsynchronized `HashMap` read racing a
    // structural insertion is entitled to answer `null` for a key that is present.
    //
    // **Why this shape.** Readers are the hot side -- one lookup per engine transport call and two per
    // engine store call, over ADR 0016's 256-tile batch -- and they must stay non-suspending so that
    // [renGResourceKeyForEngineDigest] can keep its plain (non-`suspend`) signature for
    // [BasemapEngineHost]'s failure-translation path. A single volatile read of a fully built,
    // never-again-mutated snapshot gives them the happens-before edge with no lock at all, which also
    // makes the leaf-lock rule below trivially true on the read side: readers hold nothing.
    // [routeIndexMutex] serializes writers alone, so two concurrent [preregister] batches cannot lose
    // each other's routes the way a bare read-modify-write of a volatile would.
    //
    // The five indices are: by the private Rentile key (collision detection only -- ADR 0016's "detects
    // static private Rentile key collisions"); by the exact (url, engine class) pair Transport requests
    // carry; by the (stableId, engine class) pair Store keys carry; by the
    // `sha256Hex(withRedactedAuthenticationQuery(url))` digest Rentile embeds in every
    // `ResourceAcquisitionException.sanitizedResourceId` and `ResourceDecodeException.sanitizedResourceId`,
    // read in reverse -- [BasemapEngineHost] needs that because the digest is a FOREIGN namespace (a
    // consumer handed one as a `ResourceSelector.ByKey` gets a silent empty selection rather than an
    // error) and this registry is the only place holding both the locator and the digest derived from
    // it, indexed one class wider than the store index because [ResourceClass.BASEMAP_STYLE] has no
    // Rentile raw-store entry yet `acquireRemoteStyle` still reports `sha256Hex(redacted url)` on a
    // style transport failure; and by sprite member, so [approveSpriteMemberWrite] can name a member's
    // sibling without guessing.
    private val routeIndexMutex = Mutex()

    @Volatile
    private var routeIndex: RouteIndex = RouteIndex.EMPTY

    // The digest of the most recent successfully latched Transport response for a route, so a
    // subsequent Store write can be checked against what the engine actually fetched (ADR 0016: "a
    // Rentile write callback may perform the consumer write only after RenG verifies that it matches
    // the latched response"). Written from inside a SuspendJoin.run block, which releases its own
    // Mutex before the block runs (concurrent routes' Transport calls genuinely run in parallel --
    // ADR 0016's 256-tile batch at concurrency eight) and read from writeStore, which races those same
    // writes for other routes -- so this map needs its own guard, distinct from transportJoin's.
    private val lastTransportDigestMutex = Mutex()
    private val lastTransportDigestByRoute = mutableMapOf<ResourceRouteKey, String>()

    private suspend fun recordLatchedTransportDigest(route: ResourceRouteKey, digest: String) {
        lastTransportDigestMutex.withLock { lastTransportDigestByRoute[route] = digest }
    }

    private suspend fun latchedTransportDigestFor(route: ResourceRouteKey): String? =
        lastTransportDigestMutex.withLock { lastTransportDigestByRoute[route] }

    // The bytes of every BASEMAP_TILE_JSON document this invocation answered, by the exact url the route
    // was preregistered under. RenG cannot ask the engine what a TileJSON said -- `PreparedStyle` exposes
    // no template, zoom range or scheme -- so the only way to derive that source's tile routes is to read
    // the document as it passes, and this is the one place both an answered Transport response and an
    // answered Store hit are already in hand.
    //
    // **This is an observation, never an answer path.** Both call sites sit strictly *after* the route
    // lookup that would otherwise have thrown `ambiguousRouteFailure()`, take the route object that
    // lookup returned, and add no lookup, no consumer call and no fallback of their own. An unregistered
    // url still fails closed before `Transport.execute`, and nothing is recorded for it -- there is no
    // route to record it against.
    //
    // Its own guard, for the same reason `lastTransportDigestByRoute` has one: both writers run inside a
    // `SuspendJoin` block, which releases its own Mutex before running, so concurrent routes genuinely
    // race here (ADR 0016's 256-tile batch at concurrency eight).
    private val tileJsonObservationMutex = Mutex()
    private val observedTileJsonByUrl = mutableMapOf<String, ByteArray>()

    /**
     * Records the bytes just answered for [route], if and only if it is a TileJSON route.
     *
     * A later observation for one url replaces an earlier one, which is exactly the engine's own
     * preference order: `TileJsonResourceAcquirer.acquire` reads its raw store first and falls through to
     * the transport when that hit does not parse, so the transport's bytes are the ones it actually used.
     */
    private suspend fun observeTileJsonDocument(route: ResourceRouteKey, bytes: ByteArray) {
        if (route.resourceClass != ResourceClass.BASEMAP_TILE_JSON) return
        tileJsonObservationMutex.withLock { observedTileJsonByUrl[route.locator.value] = bytes.freshCopy() }
    }

    /**
     * Every TileJSON document this invocation answered, by url. A copy, taken under the same lock the
     * writers take, so the reader gets a happens-before edge on all of them.
     */
    suspend fun observedTileJsonDocuments(): Map<String, ByteArray> =
        tileJsonObservationMutex.withLock { LinkedHashMap(observedTileJsonByUrl) }

    // Every Glyph Range lookup this invocation REFUSED, by the redacted-url digest the refusal named.
    //
    // **Why this observation exists, and why only for this one class.** The firewall's own refusal is
    // precise -- `AMBIGUOUS_RESOURCE_ROUTE`, "an exchange RenG never planned" -- and Rentile throws it
    // away. `GlyphResourceAcquirer.acquireRaw` reads its raw store first, and Rentile's `readStore`
    // helper converts whatever the store threw into its own `ResourceStoreException` with **no cause**;
    // `acquireLabelCandidates` then wraps that in a `BatchRenderException`. What arrives at
    // [BasemapEngineHost]'s classifying seam therefore carries `RESOURCE_STORE_FAILED` and no resource
    // class at all, which [classifyEngineFailure] can only report as the opaque `BASEMAP_RENDER_FAILED`:
    // a consumer whose glyph routes RenG failed to derive gets a labelless map and a failure code that
    // says only "the basemap did not render". This map is how RenG recovers what it already knew.
    //
    // Scoped to `GLYPH_RANGE` rather than kept for every class, because the label handover is the one
    // caller that reads it, and an observation nothing reads is bookkeeping that can rot. The same
    // lossy wrapping does apply to Rentile's raster, vector and TileJSON acquirers; widening this is a
    // separate decision with its own caller, not a freebie.
    //
    // Written only on the refusal path, which is by construction the path that then throws, so this
    // costs one enum comparison per engine lookup and nothing else. Its own guard, for the same reason
    // `lastTransportDigestByRoute` has one: concurrent routes genuinely race here.
    private val refusedGlyphLookupMutex = Mutex()
    private val refusedGlyphLookupDigests = mutableSetOf<String>()

    /**
     * Records a refused engine lookup when it named a Glyph Range, and does nothing otherwise.
     *
     * [redactedDigest] is `sha256Hex(withRedactedAuthenticationQuery(url))` in both callers -- a store
     * key carries it directly as its `stableId`, and a transport request is reduced to it here -- so the
     * set this fills is directly comparable against the digests of the routes RenG preregistered. That
     * comparison is what separates the two failures this observation exists to tell apart: a digest RenG
     * never preregistered means RenG derived no route for that Glyph Range at all, while a digest RenG
     * *did* preregister means the redacted forms agree and the exact strings do not -- a credential the
     * engine composes and RenG's copy of the template does not carry.
     */
    private suspend fun recordRefusedEngineLookup(engineResourceClass: EngineResourceClass, redactedDigest: String) {
        if (engineResourceClass != EngineResourceClass.GLYPH_RANGE) return
        refusedGlyphLookupMutex.withLock { refusedGlyphLookupDigests += redactedDigest }
    }

    /**
     * The redacted-url digest of every Glyph Range lookup this invocation refused. A copy, taken under
     * the same lock the writer takes, so the reader gets a happens-before edge on all of them.
     */
    suspend fun refusedGlyphLookupDigests(): Set<String> =
        refusedGlyphLookupMutex.withLock { LinkedHashSet(refusedGlyphLookupDigests) }

    private val transportJoin = SuspendJoin<TransportLatchKey, EngineTransportResponse>()
    private val storeReadJoin = SuspendJoin<ResourceRouteKey, EngineStoredRawResource?>()
    private val storeWriteJoin = SuspendJoin<ResourceRouteKey, Unit>()

    // ADR 0016: "RenG jointly prevalidates the complete sprite JSON-and-PNG pair before writing either
    // fetched member." Per-member validation cannot see the one check that actually matters here --
    // whether each atlas entry's rect lies inside the atlas image -- and Rentile writes each member
    // *before* compiling the pair, so without this a pair RenG can already prove will never compile is
    // persisted into a consumer Store that has no remove operation, and fails identically on every later
    // prepare(). [spriteRendezvous] carries each member's arrival; [spritePairJoin] runs the joint
    // verdict exactly once per pair and replays it to whichever member did not run it. What it latches
    // is that pair's parsed [SpriteAtlasManifest] rather than a bare verdict -- `null` is the verdict
    // "declined" -- so the geometry every entry declared survives the gate that proved it, bounded by
    // this registry's own operation lifetime and by the one sprite pair a style may declare.
    private val spriteRendezvous = SpriteRendezvous()
    private val spritePairJoin = SuspendJoin<SpriteGroupKey, SpriteAtlasManifest?>()

    private val spriteManifestMutex = Mutex()
    private var latchedSpriteManifest: SpriteAtlasManifest? = null

    /**
     * The sprite atlas this invocation's style declared, as [spritePairJointManifest] parsed it, or
     * `null` when this invocation never held both members of a pair or the pair was not jointly valid.
     *
     * **It is the only route by which `LabelIconRef.imageName` becomes atlas pixels.** Rentile exposes
     * no public sprite atlas -- the name is an opaque key into resources the consumer owns -- so a
     * consumer that wants to draw an icon has to have kept the manifest it proxied. The gate above
     * parses one anyway to decide whether the pair may be cached, which is why this costs nothing
     * beyond a reference.
     *
     * A copy taken under the same lock the writer takes, exactly as [refusedGlyphLookupDigests] is, so
     * the reader gets a happens-before edge on it. Read after the invocation's work has completed and
     * before its [OperationRegistry] is discarded (ADR 0016): the manifest lives for one preparation,
     * which is the same lifetime as the frame that uses it.
     */
    suspend fun spriteAtlasManifest(): SpriteAtlasManifest? =
        spriteManifestMutex.withLock { latchedSpriteManifest }

    /**
     * Runs the pair's joint gate exactly once per group and keeps what it returned.
     *
     * Both callers reach the same latch: [approveSpriteMemberWrite], which needs the verdict, and
     * [observeSpritePairFromStore], which needs nothing and exists only so that a pair served entirely
     * out of the consumer's Store still produces a manifest. Without the second one an icon would
     * resolve on the first frame after a cold start and on no frame after that, which is the failure
     * mode a reader would blame on placement rather than on caching.
     */
    private suspend fun jointSpriteManifest(
        member: SpriteMemberKey,
        json: SpriteMemberContent,
        image: SpriteMemberContent,
    ): SpriteAtlasManifest? {
        val manifest = spritePairJoin.run(member.group) { spritePairJointManifest(json.bytes, image.bytes) }
        if (manifest != null) spriteManifestMutex.withLock { latchedSpriteManifest = manifest }
        return manifest
    }

    /**
     * Declares the static prelookup routes this invocation may need. Idempotent for an identical
     * repeat registration (two occurrences joining the same route); throws if a second, genuinely
     * different route would collide with an already-registered one on any of the five indices --
     * every such collision is exactly the "two different resources answered by one engine call"
     * failure mode ADR 0016 exists to rule out, so it is caught here rather than at answer time.
     *
     * **A batch, not one route at a time**, and that is a cost decision rather than a convenience.
     * Publishing an immutable snapshot means each call copies the whole index, so registering a
     * 512-instance tile plan one route at a time would be quadratic in the plan size for no benefit;
     * every caller already holds a list ([BasemapEngineHost.registerRoutes] and
     * [BasemapEngineHost.withOperation] both take one).
     *
     * **All-or-nothing.** A collision throws before anything is published, so a batch that fails
     * leaves the index exactly as it was rather than half-applied. That is strictly safer than the
     * partial application it replaces: a half-applied batch would let an engine call this registry has
     * already decided to refuse be answered for whichever routes happened to land first.
     *
     * `suspend` because the write must serialize against other writers, and [routeIndexMutex] is the
     * only lock in this file's discipline that can do that. Every caller is already suspending.
     */
    suspend fun preregister(routes: List<ResourceRouteKey>) {
        if (routes.isEmpty()) return
        routeIndexMutex.withLock {
            val current = routeIndex
            val byPrivateKey = current.byPrivateKey.toMutableMap()
            val transportRoutes = current.transportRoutes.toMutableMap()
            val storeRoutes = current.storeRoutes.toMutableMap()
            val byEngineDigest = current.byEngineDigest.toMutableMap()
            val spriteMemberRoutes = current.spriteMemberRoutes.toMutableMap()

            for (route in routes) {
                val privateKey = privateKeyResolver.resolve(route.locator, route.resourceClass)
                requireNoCollision(byPrivateKey[privateKey], route)
                byPrivateKey[privateKey] = route

                val transportClass = engineTransportClassFor(route.resourceClass)
                if (transportClass != null) {
                    val transportIndex = TransportIndexKey(route.locator.value, transportClass)
                    requireNoCollision(transportRoutes[transportIndex], route)
                    transportRoutes[transportIndex] = route
                }

                val storeClass = engineKeyedResourceClassOf(route.resourceClass)
                // Computed once and shared by the two indices below, and not at all for the three
                // classes the engine never touches (sticker, GLB, model texture) -- a pure-Kotlin
                // SHA-256 per route adds up across a 512-instance tile plan.
                val expectedStableId = if (storeClass != null || transportClass != null) {
                    redactedLocatorHex(route.locator.value)
                } else {
                    null
                }
                if (storeClass != null) {
                    val storeIndex = StoreIndexKey(requireNotNull(expectedStableId), storeClass)
                    requireNoCollision(storeRoutes[storeIndex], route)
                    storeRoutes[storeIndex] = route
                }

                if (transportClass != null) {
                    val digestIndex = EngineDigestKey(requireNotNull(expectedStableId), route.resourceClass)
                    requireNoCollision(byEngineDigest[digestIndex], route)
                    byEngineDigest[digestIndex] = route
                }

                spriteMemberKeyOf(route)?.let { member ->
                    requireNoCollision(spriteMemberRoutes[member], route)
                    spriteMemberRoutes[member] = route
                }
            }

            // The one publication. Every map above was built from a private copy and is handed over
            // here without another reference to it surviving, so the snapshot a reader observes is
            // complete and never mutated again -- which is exactly what makes the volatile read on the
            // answer paths sufficient.
            routeIndex = RouteIndex(
                byPrivateKey = byPrivateKey,
                transportRoutes = transportRoutes,
                storeRoutes = storeRoutes,
                byEngineDigest = byEngineDigest,
                spriteMemberRoutes = spriteMemberRoutes,
            )
        }
    }

    /**
     * Translates one of Rentile's own `sanitizedResourceId` digests back into RenG's canonical
     * [ResourceKey] for the route that digest names, or `null` when this invocation preregistered no
     * such route. `null` is the honest answer, not a fallback: a digest this registry cannot place
     * belongs to a resource this invocation never routed.
     */
    fun renGResourceKeyForEngineDigest(resourceClass: ResourceClass, engineDigest: String): ResourceKey? {
        val route = routeIndex.byEngineDigest[EngineDigestKey(engineDigest, resourceClass)] ?: return null
        return renGResourceKeyFor(route)
    }

    private fun requireNoCollision(existing: ResourceRouteKey?, route: ResourceRouteKey) {
        if (existing != null && existing != route) throw ambiguousRouteFailure()
    }

    // ---- Transport ------------------------------------------------------------------------------

    suspend fun executeTransport(request: EngineTransportRequest): EngineTransportResponse {
        val route = routeIndex.transportRoutes[TransportIndexKey(request.url, request.resourceClass)]
            ?: run {
                recordRefusedEngineLookup(request.resourceClass, redactedLocatorHex(request.url))
                throw ambiguousRouteFailure()
            }
        val latchKey = TransportLatchKey(
            route = route,
            ifNoneMatch = request.metadata.ifNoneMatch,
            ifModifiedSince = request.metadata.ifModifiedSince,
            accept = request.metadata.accept,
        )
        return transportJoin.run(latchKey) {
            try {
                val response = transport.execute(
                    TransportRequest(
                        locator = route.locator,
                        resourceClass = route.resourceClass,
                        // The route's own ceiling always wins; the engine's own number in `request` is
                        // never trusted for this (ADR 0016).
                        maximumResponseBytes = route.maximumResponseBytes,
                        metadata = TransportRequestMetadata(
                            ifNoneMatch = request.metadata.ifNoneMatch,
                            ifModifiedSince = request.metadata.ifModifiedSince,
                            accept = request.metadata.accept,
                        ),
                    ),
                )
                recordLatchedTransportDigest(route, sha256Hex(response.bodySnapshot))
                // Only a success body is the document: Rentile's own acquirer refuses anything outside
                // 200..299 before it parses, so recording another status would hand the derivation bytes
                // the engine itself never read.
                if (response.statusCode in 200..299) {
                    observeTileJsonDocument(route, response.bodySnapshot)
                }
                response.toEngineResponse()
            } catch (cancellation: CancellationException) {
                // Deliberately NOT latched as a contentless sprite member: a cancelled route is a
                // cancelled invocation, and a sibling parked at the rendezvous must observe that as its
                // own cancellation rather than as a silent decline.
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                markSpriteMemberWithoutContent(route)
                throw transportFailure(route).toException()
            }
        }
    }

    // ---- Store ----------------------------------------------------------------------------------

    suspend fun readStore(key: EngineRawResourceKey): EngineStoredRawResource? {
        val route = routeIndex.storeRoutes[StoreIndexKey(key.stableId, key.resourceClass)]
            ?: run {
                recordRefusedEngineLookup(key.resourceClass, key.stableId)
                throw ambiguousRouteFailure()
            }
        return storeReadJoin.run(route) {
            try {
                val stored = store.read(RenGRawResourceKey(stableId = key.stableId, resourceClass = route.resourceClass))
                val validated = stored?.let { copyValidStoredResource(it, route.maximumResponseBytes, sha256) }
                if (validated == null || !passesClassSpecificReadValidation(route.resourceClass, validated)) {
                    // A store miss and a "digest-consistent but the engine could never use it" record
                    // are answered identically: null. Sprite's own acquirer validates only size and
                    // digest on a hit and never parses, so a record it accepts but cannot use would be
                    // permanently unrecoverable inside it (ADR 0016) -- the firewall must never let
                    // such a record reach it as if it were a genuine hit.
                    null
                } else {
                    // The mixed case the rendezvous would otherwise deadlock on: Rentile's sprite
                    // acquirer returns a store hit's bytes without ever writing them, so the sibling's
                    // write would wait for an arrival that is never coming. The firewall performed this
                    // read itself and therefore holds the very bytes the pair must be validated against,
                    // so a validated hit contributes the member here. The result is deliberately
                    // ignored: a conflicting contribution is a write-path concern, and this path's
                    // contract is to answer the engine's read honestly.
                    contributeSpriteMember(route, validated)
                    // A store hit is the whole document too, and for TileJSON it is the common path once
                    // a consumer's Store has one: Rentile's acquirer parses a valid hit and never
                    // transports. Observing only the transport would leave a cached style deriving no
                    // tile route at all.
                    observeTileJsonDocument(route, validated.bytes)
                    validated.toEngineStoredRawResource()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                // A member whose own store read failed threw out of Rentile's `acquireRaw` before it
                // could fetch, so it can never reach a write: release its sibling rather than park it.
                markSpriteMemberWithoutContent(route)
                throw storeReadFailure(route).toException()
            }
        }
    }

    suspend fun writeStore(key: EngineRawResourceKey, resource: EngineStoredRawResource) {
        val route = routeIndex.storeRoutes[StoreIndexKey(key.stableId, key.resourceClass)]
            ?: run {
                recordRefusedEngineLookup(key.resourceClass, key.stableId)
                throw ambiguousRouteFailure()
            }
        // Digest self-consistency and the byte ceiling are NOT checked here: `copyValidStoredResource`
        // below enforces both, and throws the identical failure. Checking them twice cost a second
        // pure-Kotlin SHA-256 over the same bytes on every engine store write.
        // ADR 0016 permits a Rentile write callback to reach the consumer "only after RenG verifies that
        // it matches the latched response". A route with NO latched transport response has nothing to
        // verify against, so it is refused outright rather than skipping verification: Rentile 0.6.0
        // cannot produce such a write -- every raw-store write sits immediately after a transport on the
        // same key -- but the firewall's whole premise is that the engine is untrusted, and "no latch"
        // was previously the one shape that walked straight through.
        val latchedDigest = latchedTransportDigestFor(route) ?: throw integrityRefusal(route)
        if (latchedDigest != resource.contentDigest) {
            throw integrityRefusal(route)
        }

        // Metadata validity is a CONTENT verdict, not an integrity one, so it declines rather than
        // throwing. The engine echoes back the consumer's own `contentType`/`etag`/`lastModified`
        // verbatim, so rejecting them says nothing about whether the engine fetched these bytes -- and a
        // consumer adapter written as `etag = headers["ETag"].orEmpty()`, an ordinary idiom, yields a
        // blank string that these rules reject. Throwing there would fail the whole acquisition and kill
        // any style whose sprite atlas is required, which is exactly the dead-style chain the decline
        // split exists to remove. The read path already treats the identical record as a graceful miss.
        val renGMetadata = resource.metadata.toRenGMetadata()
        if (!isValidMetadata(renGMetadata)) return

        val bytes = resource.bytes
        // An empty body is a CONTENT verdict too, and it is the one `copyValidStoredResource` below
        // cannot be allowed to answer, because it answers every rejection identically and this one is
        // provably not the engine's fault: the latched-digest check immediately above already proved
        // these are exactly the bytes RenG's own transport returned for this route.
        //
        // Empty bodies are ordinary in production basemaps. A tile server answers `204 No Content` for
        // every tile of a source that has no data there -- MapTiler's `ocean` source does it for every
        // inland tile, and the same holds for contour, hillshade and terrain sources at their edges --
        // and an empty vector tile is a well-formed empty protobuf message that Rentile renders as an
        // empty tile. Refusing the write instead threw `STORE_WRITE_FAILED` out of Rentile's own
        // acquirer, which cancelled the whole batch scope, so a single 204 anywhere in a frame's tile
        // cover failed the entire basemap for that frame.
        //
        // Declining is the same legal answer the metadata and class-specific gates already give: the
        // engine's `write` returns `Unit`, its result is never read, and the engine proceeds with the
        // bytes it already holds. Nothing is cached, which costs nothing -- `copyValidStoredResource`
        // is the read path's validator too, so an empty record would be answered as a miss anyway.
        if (bytes.isEmpty()) {
            storeWriteJoin.run(route) { markSpriteMemberWithoutContent(route) }
            return
        }

        // What remains IS integrity: `copyValidStoredResource` re-derives SHA-256 over the bytes, so a
        // rejection here means the engine kept the latched digest and presented different bytes.
        val validated = copyValidStoredResource(
            StoredRawResource(
                bytes = bytes,
                contentDigest = resource.contentDigest,
                metadata = renGMetadata,
            ),
            route.maximumResponseBytes,
            sha256,
        ) ?: throw integrityRefusal(route)

        storeWriteJoin.run(route) {
            // Inside the join, not before it, for two reasons. The join releases its own Mutex before
            // running this block, so parking here holds no lock and blocks no other route. And the
            // content verdict becomes this route's latched write outcome, so the engine's later attempt
            // on the same key replays it instead of re-deciding or parking a second time.
            if (!shouldCacheValidatedWrite(route, validated)) return@run
            try {
                store.write(
                    RenGRawResourceKey(stableId = key.stableId, resourceClass = route.resourceClass),
                    validated,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                throw storeWriteFailure(route).toException()
            }
        }
    }

    /**
     * The one class of write refusal that stays fatal: **firewall integrity** -- the engine presenting
     * bytes RenG cannot prove it fetched. An unresolved route, a route with no latched transport
     * response, a body that does not match the latched response, and a record RenG's own rules reject
     * are all statements about the engine, not about the content, so each is a loud sanitized failure.
     *
     * The side effect these owe the rendezvous is unchanged: a member refused here can never contribute,
     * so its sibling must be released rather than left parked on an arrival that is now impossible. That
     * release is latched for the registry's lifetime exactly like every other outcome here -- within one
     * invocation "the engine's second attempt is not a consumer retry" (ADR 0016) -- which for a sprite
     * member also makes the refusal itself sticky: a member latched without content can no longer
     * contribute, so a second write on that member declines too. Every check above is a function of the
     * route and the bytes the engine just fetched, so a second attempt could only differ by presenting
     * different bytes for one member, which is the collision this file exists to refuse.
     */
    private suspend fun integrityRefusal(route: ResourceRouteKey): RenGException {
        markSpriteMemberWithoutContent(route)
        return storeWriteFailure(route).toException()
    }

    /**
     * The other class of write outcome: a **content verdict**, which declines to cache and returns
     * normally rather than failing the engine's acquisition.
     *
     * The reasoning, which is specific to what a refusal actually costs. A refused write becomes
     * `ResourceStoreException` inside Rentile's `SpriteResourceAcquirer.writeStore`, cancels the
     * `coroutineScope` both members run in, and escapes `acquire`. `StyleCompiler` degrades that only on
     * the `resolveOptionalSpriteAtlas` branch; `resolveRequiredSpriteAtlas` catches nothing at all, and
     * it is the branch chosen whenever any visible layer carries a `*-pattern` paint property or any
     * `symbol` layer has `icon-image` without meaningful `text-field` -- that is, for essentially every
     * real basemap style. So for the sprite pair a throwing gate has exactly two outcomes, and neither is
     * good: content Rentile *also* rejects dies in its own `compile` moments later regardless, so the
     * refusal only changes store state while downgrading a typed decode failure into a misleading
     * `STORE_WRITE_FAILED`; content Rentile *accepts* becomes a permanently broken style. There is no
     * third case, and the deltas are not hypothetical -- RenG's PNG container walk refuses bit depths
     * other than eight, interlacing, unknown critical chunks and trailing bytes, while Rentile's sprite
     * path reads only the signature and the `IHDR` dimensions and hands the raw bytes to Skia, which
     * decodes all of those. An atlas that `oxipng` or `optipng` reduced to a four-bit palette is an
     * ordinary, default-on optimisation that Rentile renders.
     *
     * Declining is a legal answer to the engine: Rentile's `RawResourceStore.write` returns `Unit` and
     * its result is never read, so a normal return is indistinguishable from a successful write, and the
     * engine proceeds with the bytes it already holds in memory.
     *
     * ADR 0016 is satisfied rather than bent. Its three obligations here -- prevalidate the pair "before
     * writing either fetched member", let no atlas "become visible unless both succeed", and require
     * terrain validation of "a fetched DEM write" -- all constrain RenG's write and visibility boundary,
     * which is exactly what this gate still enforces: no unverified byte reaches the consumer Store, and
     * neither member is written when the pair fails. None of them constrains the engine's own
     * acquisition, and none of them could: the bytes reached Rentile through [executeTransport] long
     * before any write callback, so no `writeStore` outcome can stop the engine compiling them *without
 * also aborting the whole acquisition* -- which on a required sprite atlas kills the style outright,
 * and on an optional one hides an atlas Rentile renders correctly. Suppressing the atlas alone is
 * not something this seam can do.
     */
    private suspend fun shouldCacheValidatedWrite(route: ResourceRouteKey, validated: StoredRawResource): Boolean {
        // Gap the read path already closed, applied to the write it was always missing from, and ADR
        // 0016's DEM terrain obligation, which has no engine-side equivalent at all.
        if (!passesClassSpecificWriteValidation(route.resourceClass, validated)) {
            markSpriteMemberWithoutContent(route)
            return false
        }
        return approveSpriteMemberWrite(route, validated)
    }

    /**
     * ADR 0016's joint sprite prevalidation, as a suspending rendezvous between the pair's two member
     * routes: this member publishes its own validated bytes, waits for its sibling's, and then both
     * replay one joint verdict, so both members are cached or neither is. Returns `true` for every other
     * class, which has no pair to assemble.
     *
     * Four ways a sibling can fail to arrive, all of which decline rather than hang:
     *  - **never preregistered** -- declined immediately, before any wait, since this invocation can
     *    never produce the arrival;
     *  - **its fetch or its store read failed** -- that path latched it without content, which completes
     *    the wait with `null`;
     *  - **its own write was refused or declined** -- same latch;
     *  - **the invocation is cancelled** -- the wait is an ordinary suspension point, so cancellation
     *    surfaces here unwrapped and is latched as this route's write outcome. Cancellation is the one
     *    thing that still propagates out of this function, because it is control flow rather than a
     *    verdict about content.
     *
     * There is deliberately no timeout. Rentile launches both members' `acquireRaw` calls inside one
     * `coroutineScope` and awaits both, so a genuine failure in one cancels the other rather than
     * leaving it parked, and its `SingleFlight` independently cancels the shared work once its last
     * waiter detaches -- two independent releases, neither of which needs a clock. A wall-clock bound
     * could not tell a sibling whose fetch is merely slow from one that will never come, so it would
     * only convert a slow consumer transport into a spurious decline.
     */
    private suspend fun approveSpriteMemberWrite(route: ResourceRouteKey, validated: StoredRawResource): Boolean {
        val member = spriteMemberKeyOf(route) ?: return true
        val sibling = SpriteMemberKey(member.group, siblingSpriteClassOf(member.resourceClass))
        if (routeIndex.spriteMemberRoutes[sibling] == null) return false

        val mine = SpriteMemberContent(validated.contentDigest, validated.bytes)
        // A second, different content for one member inside one invocation means the consumer Store and
        // the engine's fetch disagree about what this member is. The written bytes are provably the ones
        // the engine fetched (the latched-digest check above already proved that), so this is not the
        // engine lying and it is not an integrity refusal -- it is a pair RenG cannot decide, so it
        // declines to cache and leaves whatever the Store already holds alone.
        if (!spriteRendezvous.contribute(member, mine)) return false
        val theirs = spriteRendezvous.awaitContent(sibling) ?: return false

        val jsonMember = if (member.resourceClass == ResourceClass.BASEMAP_SPRITE_JSON) mine else theirs
        val imageMember = if (member.resourceClass == ResourceClass.BASEMAP_SPRITE_IMAGE) mine else theirs
        // Latched per pair, so both members reach the same verdict and neither re-derives it -- and what
        // is latched is the pair's parsed manifest rather than a bare verdict, so the geometry is parsed
        // exactly once per pair. This path reads only whether one came back.
        return jointSpriteManifest(member, jsonMember, imageMember) != null
    }

    private suspend fun contributeSpriteMember(route: ResourceRouteKey, validated: StoredRawResource) {
        val member = spriteMemberKeyOf(route) ?: return
        spriteRendezvous.contribute(member, SpriteMemberContent(validated.contentDigest, validated.bytes))
        observeSpritePairFromStore(member, validated)
    }

    /**
     * Runs the joint gate for a pair whose members both came out of the consumer's Store, so that
     * [spriteAtlasManifest] has something to hand back on a frame that fetched nothing.
     *
     * **It peeks at the sibling and never waits for it**, which is the whole of why it is safe here.
     * [approveSpriteMemberWrite] may park at the rendezvous because a write is always preceded by a
     * fetch that its sibling's fetch is running concurrently with; a store *read* has no such
     * guarantee -- Rentile may serve one member from the Store and fetch the other, or read them one
     * after the other -- so a read that parked could wait for an arrival that only its own return
     * would cause. Peeking makes the *later* of the two contributors compute the manifest and the
     * earlier one do nothing, which covers every interleaving without a wait.
     *
     * Nothing here can fail the read: a `null` sibling, a `null` manifest and a pair that never
     * completes are all simply an invocation with no icon geometry.
     */
    private suspend fun observeSpritePairFromStore(member: SpriteMemberKey, validated: StoredRawResource) {
        val sibling = SpriteMemberKey(member.group, siblingSpriteClassOf(member.resourceClass))
        val theirs = spriteRendezvous.peekContent(sibling) ?: return
        val mine = SpriteMemberContent(validated.contentDigest, validated.bytes)
        val jsonMember = if (member.resourceClass == ResourceClass.BASEMAP_SPRITE_JSON) mine else theirs
        val imageMember = if (member.resourceClass == ResourceClass.BASEMAP_SPRITE_IMAGE) mine else theirs
        jointSpriteManifest(member, jsonMember, imageMember)
    }

    /** Latches a sprite member as one that will contribute no content, releasing a parked sibling. */
    private suspend fun markSpriteMemberWithoutContent(route: ResourceRouteKey) {
        spriteMemberKeyOf(route)?.let { spriteRendezvous.markWithoutContent(it) }
    }

    /**
     * Private and terminal (ADR 0016): performs no consumer removal, no repair, and no follow-on
     * exchange, whether or not [key] matches a preregistered route. RenG's own [Store] has no remove
     * operation at all, so there is nothing to forward even if this looked one up.
     */
    fun removeStore(key: EngineRawResourceKey) {
        // Intentionally empty.
    }

    // ---- validation / translation ----------------------------------------------------------------

    private fun passesClassSpecificReadValidation(resourceClass: ResourceClass, stored: StoredRawResource): Boolean =
        when (resourceClass) {
            ResourceClass.BASEMAP_SPRITE_IMAGE ->
                decodePng(stored.bytes, SPRITE_IMAGE_DECODE_CEILING_BYTES) is PngDecodeResult.Success
            ResourceClass.BASEMAP_SPRITE_JSON ->
                parseJson(stored.bytes, 0, stored.bytes.size, SPRITE_JSON_MAXIMUM_DEPTH) is JsonParse.Parsed
            // Deliberate, and the reason is specific rather than "nothing else needs checking". Rentile's
            // raster, vector, TileJSON and GeoJSON acquirers each re-run their own bounded parser or
            // decoder on a store hit and remove-then-refetch when it fails (e.g. `RasterResourceAcquirer`
            // at Rentile 0.6.0), so a bad record in one of those classes self-heals on the next access and
            // is never terminal. The sprite pair is the one exception -- its acquirer checks only size and
            // digest on a hit and never parses -- which is exactly why ADR 0016 records only the sprite
            // pair as terminal and why only it earns a class-specific gate here. Widening this branch
            // would buy nothing and would turn a self-healing engine record into a hard RenG refusal.
            else -> true
        }

    /**
     * Everything [passesClassSpecificReadValidation] proves, plus the one obligation ADR 0016 places on
     * writes alone: *"A fetched DEM write additionally requires RenG's terrain encoding validation."*
     * Rentile's DEM path is `RasterResourceAcquirer`, which reaches its raw-store write after generic
     * bounded image validation only, so nothing but this checks that a DEM tile is actually terrain-
     * encoded before it lands in the consumer's Store.
     */
    private fun passesClassSpecificWriteValidation(resourceClass: ResourceClass, stored: StoredRawResource): Boolean =
        when (resourceClass) {
            ResourceClass.BASEMAP_DEM_TILE ->
                validatesDemTerrainEncoding(stored.bytes, DEM_TILE_DECODE_CEILING_BYTES)
            else -> passesClassSpecificReadValidation(resourceClass, stored)
        }

    private fun redactedLocatorHex(url: String): String = sha256Hex(redactAuthenticationQuery(url))

    private fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

    private fun sha256Hex(bytes: ByteArray): String = sha256.digest(CanonicalBytes(bytes)).lowercaseHex

    private fun TransportResponse.toEngineResponse(): EngineTransportResponse = EngineTransportResponse(
        statusCode = statusCode,
        body = body,
        metadata = EngineTransportResponseMetadata(
            contentType = metadata.contentType,
            etag = metadata.etag,
            lastModified = metadata.lastModified,
            cacheControl = null,
            expiresAtEpochMillis = metadata.freshUntilEpochMillis,
            vary = emptyList(),
            retryAfterMillis = null,
            redirectLocation = null,
            wireByteCount = null,
        ),
    )

    private fun StoredRawResource.toEngineStoredRawResource(): EngineStoredRawResource = EngineStoredRawResource(
        bytes = bytes,
        contentDigest = contentDigest,
        metadata = EngineRawResourceMetadata(
            contentType = metadata.contentType,
            etag = metadata.etag,
            lastModified = metadata.lastModified,
            freshUntilEpochMillis = metadata.freshUntilEpochMillis,
            storedAtEpochMillis = metadata.storedAtEpochMillis,
        ),
    )

    private fun EngineRawResourceMetadata.toRenGMetadata(): StoredRawResourceMetadata = StoredRawResourceMetadata(
        contentType = contentType,
        etag = etag,
        lastModified = lastModified,
        freshUntilEpochMillis = freshUntilEpochMillis,
        storedAtEpochMillis = storedAtEpochMillis,
    )

    // ---- failures ---------------------------------------------------------------------------------
    // No adapter message, cause, locator, or engine key ever reaches these -- every diagnostic below
    // carries only an enum-valued resourceClass and RenG's own canonical resourceKey (a different
    // hash from anything Rentile derives), never the engine's own stableId.

    private fun renGResourceKeyFor(route: ResourceRouteKey) =
        resourceKeyDeriver.external(route.resourceClass, route.locator).key

    private fun ambiguousRouteFailure(): RenGException = FailureDescriptor(
        code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
        ),
    ).toException()

    private fun transportFailure(route: ResourceRouteKey): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
        stage = PipelineStage.TRANSPORT,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.TRANSPORT,
            resourceClass = route.resourceClass,
            resourceKey = renGResourceKeyFor(route),
        ),
    )

    private fun storeReadFailure(route: ResourceRouteKey): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_READ_FAILED,
        stage = PipelineStage.STORE_READ,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_READ,
            resourceClass = route.resourceClass,
            resourceKey = renGResourceKeyFor(route),
        ),
    )

    private fun storeWriteFailure(route: ResourceRouteKey): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_WRITE_FAILED,
        stage = PipelineStage.STORE_WRITE,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_WRITE,
            resourceClass = route.resourceClass,
            resourceKey = renGResourceKeyFor(route),
        ),
    )
}

/**
 * One complete, immutable view of everything [OperationRegistry.preregister] has declared so far,
 * indexed the five ways the answer paths ask for it. Built entirely inside
 * [OperationRegistry.preregister]'s critical section and published by a single volatile write, so a
 * reader either sees a whole batch or none of it and never observes a half-built map.
 *
 * The maps are typed `Map`, are never handed out, and are never written after construction. That is
 * the property the volatile publication rests on: a snapshot's contents cannot change under a reader,
 * so the one happens-before edge the volatile read establishes covers all of it.
 */
private class RouteIndex(
    val byPrivateKey: Map<RentilePrivateKey, ResourceRouteKey>,
    val transportRoutes: Map<TransportIndexKey, ResourceRouteKey>,
    val storeRoutes: Map<StoreIndexKey, ResourceRouteKey>,
    val byEngineDigest: Map<EngineDigestKey, ResourceRouteKey>,
    val spriteMemberRoutes: Map<SpriteMemberKey, ResourceRouteKey>,
) {
    companion object {
        val EMPTY = RouteIndex(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

/** The exact `(url, engine resource class)` pair Rentile's own [EngineTransportRequest] carries. */
private data class TransportIndexKey(val url: String, val engineClass: EngineResourceClass)

/** The exact `(stableId, engine resource class)` pair Rentile's own [EngineRawResourceKey] carries. */
private data class StoreIndexKey(val stableId: String, val engineClass: EngineResourceClass)

/**
 * `(Rentile's sanitizedResourceId, RenG resource class)`. Keyed by RenG's own [ResourceClass] rather
 * than Rentile's, because the caller reaches this having already translated the engine's class through
 * [rengResourceClassOf] -- and because that translation is where `STYLE` -> `BASEMAP_STYLE` is spelled
 * out at all.
 */
private data class EngineDigestKey(val engineDigest: String, val resourceClass: ResourceClass)

/**
 * Which of Rentile's own [EngineResourceClass] values every declared RenG [ResourceClass] maps to on
 * the Transport side. Unlike [engineKeyedResourceClassOf] (Store-side, and `null` for
 * [ResourceClass.BASEMAP_STYLE] since the engine's `RawResourceStore` never sees style), Transport
 * *does* see style -- Rentile fetches style bytes to compile privately but never writes them to its
 * raw store (ADR 0016) -- so this mapping is a strict superset of one more class.
 */
private fun engineTransportClassFor(resourceClass: ResourceClass): EngineResourceClass? =
    if (resourceClass == ResourceClass.BASEMAP_STYLE) EngineResourceClass.STYLE else engineKeyedResourceClassOf(resourceClass)

/**
 * A join keyed by [K]: the first caller for a given key actually runs [block] and latches its
 * outcome (success, sanitized failure, or an unwrapped [CancellationException] -- [block] is
 * responsible for producing that shape, this class only replays whatever escapes or is returned);
 * every other caller -- concurrent or sequential, including the engine's own private retry -- replays
 * that exact outcome without running [block] again. Never removes a key once latched: within one
 * operation-scoped registry, "the engine's second attempt is not a consumer retry" (ADR 0016) holds
 * for the lifetime of the registry, not just for the duration of the first call.
 */
private class SuspendJoin<K : Any, V> {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        var owner = false
        val deferred = mutex.withLock {
            inFlight.getOrPut(key) {
                owner = true
                CompletableDeferred()
            }
        }
        if (!owner) return deferred.await()
        return try {
            val result = block()
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        }
    }
}

/**
 * The two members of one sprite atlas, identified by the base url Rentile derived both from. Rentile's
 * `SpriteResourceAcquirer` composes `<base>.json` and `<base>.png` through its own
 * `appendSpriteExtension` (extension before the query and fragment, not after), so stripping that
 * class's extension back off a member's locator recovers the base the two share. Rentile 0.6.0 admits
 * exactly one string-valued `sprite` reference per style, so one invocation holds at most one pair --
 * this derivation's job is to confirm two routes belong together, not to disambiguate among several.
 */
private data class SpriteGroupKey(val base: String)

private data class SpriteMemberKey(val group: SpriteGroupKey, val resourceClass: ResourceClass)

/** One member's validated bytes, immutable once contributed. */
private class SpriteMemberContent(val digest: String, val bytes: ByteArray)

private fun spriteMemberKeyOf(route: ResourceRouteKey): SpriteMemberKey? {
    val extension = when (route.resourceClass) {
        ResourceClass.BASEMAP_SPRITE_JSON -> SPRITE_JSON_EXTENSION
        ResourceClass.BASEMAP_SPRITE_IMAGE -> SPRITE_IMAGE_EXTENSION
        else -> return null
    }
    return SpriteMemberKey(SpriteGroupKey(spriteBaseUrl(route.locator.value, extension)), route.resourceClass)
}

private fun siblingSpriteClassOf(resourceClass: ResourceClass): ResourceClass =
    if (resourceClass == ResourceClass.BASEMAP_SPRITE_JSON) {
        ResourceClass.BASEMAP_SPRITE_IMAGE
    } else {
        ResourceClass.BASEMAP_SPRITE_JSON
    }

/**
 * The exact inverse of Rentile's `appendSpriteExtension`: split the fragment, split the query, drop
 * [extension] from the end of the path alone, and put the query and fragment back. A locator whose path
 * does not end in [extension] is returned unchanged, which simply groups it with itself -- it then finds
 * no sibling and its write is refused, rather than being silently paired with an unrelated route.
 */
private fun spriteBaseUrl(url: String, extension: String): String {
    val fragmentIndex = url.indexOf('#').let { if (it < 0) url.length else it }
    val withoutFragment = url.substring(0, fragmentIndex)
    val fragment = url.substring(fragmentIndex)
    val queryIndex = withoutFragment.indexOf('?').let { if (it < 0) withoutFragment.length else it }
    val path = withoutFragment.substring(0, queryIndex)
    val query = withoutFragment.substring(queryIndex)
    val base = if (path.endsWith(extension)) path.dropLast(extension.length) else path
    return base + query + fragment
}

/**
 * One atlas entry as the manifest declared it: a rect already proved non-degenerate and wholly inside
 * the atlas image, and the `pixelRatio` Rentile's compiler would read for it -- which for an absent or
 * unreadable one is the 1.0 Rentile itself falls back to, recorded here rather than left for every later
 * reader to re-derive.
 */
internal data class SpriteAtlasEntry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixelRatio: Double,
    /**
     * The entry's `sdf` member, defaulting to `false` exactly as Rentile's own reader does.
     *
     * **It decides whether `icon-color` and `icon-halo-*` mean anything at all.** Rentile tints a
     * sprite under `BlendMode.SRC_IN` when this is set and passes a `null` colour filter otherwise, so
     * a consumer that tinted every sprite would repaint artwork the style never asked to recolour.
     * Defaulted so that this file's own fixtures and every existing caller keep their arity, and read
     * rather than assumed because an unread flag here becomes a wrong picture two layers away.
     */
    val sdf: Boolean = false,
)

/**
 * A jointly valid sprite pair's contents: the atlas image's own dimensions, read from its `IHDR`, and
 * every entry the manifest named, keyed by that name and in the manifest's own member order. It exists
 * only for a pair that passed [spritePairJointManifest]'s checks, so every rect it holds is already known
 * to lie inside [atlasWidth] x [atlasHeight].
 */
internal data class SpriteAtlasManifest(
    val atlasWidth: Int,
    val atlasHeight: Int,
    val entries: Map<String, SpriteAtlasEntry>,
)

/**
 * Runs the cross-member checks Rentile's own `SpriteResourceAcquirer.compile` performs -- and only those
 * that are unconditional and independent of Rentile's configuration -- then hands back what the pair
 * actually contained. The checks: the manifest is an object of entry objects, each entry carries integer
 * `x`/`y`/`width`/`height`, each rect is non-degenerate and lies wholly inside the atlas image, a present
 * `pixelRatio` is finite and positive, and no entry carries the `stretchX`/`stretchY`/`content` fields
 * Rentile refuses.
 *
 * **`null` is the whole of "not jointly valid", and it is a cache verdict rather than a failure.** This
 * function throws nothing and reports nothing; a `null` means one member's bytes lost the pair its cache,
 * exactly as the `false` it replaces did. Nullable rather than a two-case result type precisely so the
 * write path is not made to care about the payload -- [approveSpriteMemberWrite] asks only whether a
 * manifest came back -- and because `null`-means-declined is already this seam's idiom, as in
 * [spriteMemberKeyOf] and [SpriteRendezvous.awaitContent]. **The returned manifest is the point**: this
 * geometry was always parsed here and always discarded, and an icon name cannot be resolved to atlas
 * pixels without it.
 *
 * An empty manifest object is jointly valid and yields an empty [SpriteAtlasManifest.entries] --
 * vacuously, since there is no entry left to fail a check -- so a reader resolving a name against a
 * manifest must distinguish "no such entry" from "no manifest", and must not read a non-null return as
 * evidence that any entry exists.
 *
 * Rentile's two limit-shaped checks are deliberately not mirrored, and **not** because RenG cannot know
 * them -- it can, exactly, at the pinned version: `MAX_SPRITE_ENTRIES` is a hardcoded `100_000` in
 * `SpriteResourceAcquirer`'s `private companion object`, and `maxRasterDimensionPx` defaults to `8192`
 * in a `ResourceLimits` that [BasemapEngineHost] never overrides. They are omitted on coupling grounds.
 * Copying either number binds this gate's cache policy to a value that can move without any compile-time
 * signal -- a minor Rentile release changing the constant, or a later RenG change passing custom
 * `resourceLimits` -- and the two ways of being wrong are not symmetric. Omitting them caches a pair that
 * cannot compile: wasted bytes on a style that fails for its own reasons either way. Mirroring them and
 * being stale in the strict direction declines to cache a pair the engine happily uses, which is a
 * permanent, unannounced refetch of both members on every prepare, for a style that works. Bytes on a
 * broken style cost less than repeated network on a working one, and an atlas above either bound is far
 * outside anything a real basemap style ships.
 *
 * That omission was a caching decision, and it now has a second consequence worth stating plainly, since
 * a returned manifest can be read where a cache verdict could not: **a manifest is not a promise that
 * Rentile compiled this atlas.** A pair above either omitted bound returns a manifest here and still
 * fails the engine's own compile, so a reader that needs pixels must take them from the atlas image it
 * decoded, never from this having been non-null.
 *
 * Only the image's IHDR is needed, so this scans the container ([scanPng]) rather than decoding it; the
 * member gate already proved the same bytes decode in full.
 */
internal fun spritePairJointManifest(jsonBytes: ByteArray, imageBytes: ByteArray): SpriteAtlasManifest? {
    val header = (scanPng(imageBytes) as? PngScan.Admitted)?.header ?: return null
    val parsed = parseJson(jsonBytes, 0, jsonBytes.size, SPRITE_JSON_MAXIMUM_DEPTH) as? JsonParse.Parsed
    val root = parsed?.value as? JsonValue.Obj ?: return null
    val entries = LinkedHashMap<String, SpriteAtlasEntry>(root.members.size)
    for ((name, entry) in root.members) {
        // One bad entry still rejects the whole pair, as the `all` this replaces did: Rentile compiles
        // the manifest as a unit, so a pair with one unusable entry is a pair that cannot compile.
        entries[name] = spriteEntryWithinAtlas(entry, header.width, header.height) ?: return null
    }
    return SpriteAtlasManifest(atlasWidth = header.width, atlasHeight = header.height, entries = entries)
}

/** The entry's own geometry when it passes every check above, `null` when it does not. */
private fun spriteEntryWithinAtlas(entry: JsonValue, imageWidth: Int, imageHeight: Int): SpriteAtlasEntry? {
    val members = (entry as? JsonValue.Obj)?.members ?: return null
    if (members.keys.any { it in UNSUPPORTED_SPRITE_ENTRY_FIELDS }) return null
    val x = spriteEntryInt(members["x"]) ?: return null
    val y = spriteEntryInt(members["y"]) ?: return null
    val width = spriteEntryInt(members["width"]) ?: return null
    val height = spriteEntryInt(members["height"]) ?: return null
    if (x < 0 || y < 0 || width <= 0 || height <= 0) return null
    // Widened deliberately: two in-range Ints can overflow their sum, and an overflowed rect would
    // compare as comfortably inside the atlas.
    if (x.toLong() + width.toLong() > imageWidth.toLong()) return null
    if (y.toLong() + height.toLong() > imageHeight.toLong()) return null
    // Rentile reads `sdf` through `booleanOrNull` and falls back to false, so an absent or
    // unreadable member is the ordinary case rather than a rejection -- most sprites are artwork.
    val sdf = (members["sdf"] as? JsonValue.Bool)?.value == true
    // Absent, or present but unreadable as a number, is not a rejection: Rentile falls back to 1.0 in
    // both cases, so refusing here would refuse a pair the engine compiles. That fallback is recorded on
    // the entry rather than left implicit, so a later reader sees the ratio the engine would have used.
    val pixelRatio = spriteEntryDouble(members["pixelRatio"])
        ?: return SpriteAtlasEntry(x, y, width, height, ABSENT_SPRITE_PIXEL_RATIO, sdf)
    if (!pixelRatio.isFinite() || pixelRatio <= 0.0) return null
    return SpriteAtlasEntry(x, y, width, height, pixelRatio, sdf)
}

/** Rentile reads these through `JsonPrimitive.intOrNull`, which parses a quoted primitive's content
 *  exactly as it parses a bare one, so a string-spelled integer is admitted here for the same reason. */
private fun spriteEntryInt(value: JsonValue?): Int? = when (value) {
    is JsonValue.Integer -> if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.value.toInt() else null
    is JsonValue.Text -> value.value.toIntOrNull()
    else -> null
}

private fun spriteEntryDouble(value: JsonValue?): Double? = when (value) {
    is JsonValue.Integer -> value.value.toDouble()
    is JsonValue.Real -> value.value
    is JsonValue.Text -> value.value.toDoubleOrNull()
    else -> null
}


/**
 * The sprite pair's arrival board: each member is latched exactly once, either with the validated bytes
 * that arrived for it or as one that will contribute none, and a waiter suspends until its member
 * reaches one of those two states. Never evicts, for the same reason [SuspendJoin] never does -- the
 * registry it lives in is discarded when the invocation terminates (ADR 0016), so a latched "no content"
 * can never be inherited by a later, healthy preparation.
 *
 * Concurrency, stated because this file has already had one unsynchronised map on a genuinely concurrent
 * answer path: every read and write of [slots] and of a slot's own fields happens under [mutex], the
 * suspending wait happens with **no** lock held (the deferred is captured under the lock and awaited
 * outside it), and [mutex] is a leaf -- no other lock is ever acquired while it is held, and it is only
 * ever acquired from a [SuspendJoin] block, which has already released its own mutex. Each slot's
 * [Slot.arrived] is completed by exactly one caller: the one whose critical section performed the
 * transition, which completes it after leaving the lock.
 */
private class SpriteRendezvous {
    private val mutex = Mutex()
    private val slots = mutableMapOf<SpriteMemberKey, Slot>()

    /**
     * Publishes [content] for [member]. Returns `false` -- and publishes nothing -- when this member is
     * already latched with different content, or already latched as contributing none.
     */
    suspend fun contribute(member: SpriteMemberKey, content: SpriteMemberContent): Boolean {
        var signal: CompletableDeferred<Unit>? = null
        val accepted = mutex.withLock {
            val slot = slots.getOrPut(member) { Slot() }
            val existing = slot.content
            when {
                existing != null -> existing.digest == content.digest
                slot.withoutContent -> false
                else -> {
                    slot.content = content
                    signal = slot.arrived
                    true
                }
            }
        }
        signal?.complete(Unit)
        return accepted
    }

    /** Latches [member] as one that will contribute no content, releasing any sibling waiting on it. */
    suspend fun markWithoutContent(member: SpriteMemberKey) {
        var signal: CompletableDeferred<Unit>? = null
        mutex.withLock {
            val slot = slots.getOrPut(member) { Slot() }
            if (slot.content == null && !slot.withoutContent) {
                slot.withoutContent = true
                signal = slot.arrived
            }
        }
        signal?.complete(Unit)
    }

    /**
     * [member]'s content if it has already arrived, without waiting for it if it has not.
     *
     * The non-blocking counterpart of [awaitContent], for a caller that may not park -- see
     * [OperationRegistry.observeSpritePairFromStore]. A `null` covers both "not latched yet" and
     * "latched as contributing none", because neither gives the caller a pair to work with.
     */
    suspend fun peekContent(member: SpriteMemberKey): SpriteMemberContent? =
        mutex.withLock { slots[member]?.content }

    /** Suspends until [member] is latched either way; `null` means no content will ever arrive. */
    suspend fun awaitContent(member: SpriteMemberKey): SpriteMemberContent? {
        val slot = mutex.withLock { slots.getOrPut(member) { Slot() } }
        slot.arrived.await()
        return mutex.withLock { slot.content }
    }

    private class Slot {
        val arrived = CompletableDeferred<Unit>()
        var content: SpriteMemberContent? = null
        var withoutContent: Boolean = false
    }
}

/** What Rentile's own compiler reads for an entry that declares no readable `pixelRatio`. */
private const val ABSENT_SPRITE_PIXEL_RATIO: Double = 1.0

private const val SPRITE_JSON_EXTENSION = ".json"
private const val SPRITE_IMAGE_EXTENSION = ".png"

/** The three entry fields Rentile's sprite compiler rejects outright rather than ignoring. */
private val UNSUPPORTED_SPRITE_ENTRY_FIELDS: Set<String> = setOf("stretchX", "stretchY", "content")


/** Generous enough for any DEM tile; only used to decide whether a fetched DEM record is terrain-encoded
 *  at all before writing it, never to size an actual buffer. */
private const val DEM_TILE_DECODE_CEILING_BYTES: Long = 64L * 1024L * 1024L

/** Generous enough for any sticker/sprite-sized image; only used to decide whether a sprite image
 *  record decodes at all before answering the engine's read, never to size an actual buffer. */
private const val SPRITE_IMAGE_DECODE_CEILING_BYTES: Long = 64L * 1024L * 1024L

/** Sprite JSON documents are small, flat atlas manifests; this is a generous nesting ceiling used
 *  only to prove the document parses at all before answering the engine's read. */
private const val SPRITE_JSON_MAXIMUM_DEPTH: Int = 64
