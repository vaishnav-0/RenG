package com.rohittp.reng.internal.basemap

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonReject
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.StyleFailureKind

/*
 * Reads a basemap style document and derives, purely, the exact resource routes the Rentile engine
 * will later request while compiling that style and while preparing a set of tiles from it.
 *
 * **Why this exists at all.** Rentile's `PreparedStyle` exposes only `digest`, `policy` and
 * `diagnostics` (`Api.kt:766-770` at the pinned `0.7.0` release commit `ca192ab`); tile URL templates,
 * zoom ranges, scheme and bounds are deliberately private. RenG therefore cannot ask the engine what it
 * is about to fetch, and ADR 0016's firewall matches an engine request by **exact string equality**
 * against preregistered routes (`OperationRegistry.executeTransport`). Everything below is consequently
 * a re-implementation of private Rentile logic, kept in agreement with it by nothing but a pinned
 * dependency version and this file's tests. The failure mode of a disagreement is loud
 * (`AMBIGUOUS_RESOURCE_ROUTE`) rather than silent, which is the better of the two available outcomes --
 * but it presents as a total outage, so *reproducing* Rentile matters far more than being independently
 * correct. Nothing here may be "improved".
 *
 * **The version-pinned assumption.** At `2d0a5bf` no credential and no session is composed into any
 * url: `RentileConfiguration.sessionProvider`/`credentialProvider` have no reader in the whole engine,
 * and `BasemapEngineHost` additionally passes `None` for both. Every url below is therefore a pure
 * function of the style document and the style's own base URI. A future Rentile that started composing
 * a session parameter would break every preregistered route at once.
 *
 * **Both source forms are supported, in two phases.** A source declares its tile templates either inline
 * (`"tiles": [...]`) or by reference (`"url": ".../tiles.json"`, a TileJSON document). The reference form
 * is not the rare case an earlier ruling in this cycle assumed: across the 34 map styles Rentile is
 * verified for, 96 sources use it and 2 use the inline form, and **all 34 need at least one of them** --
 * so an inline-only reader derives no ground for any of them.
 *
 * What makes the reference form tractable is that only half of it is unknown ahead of time. The
 * *document* url is `source["url"]` resolved against the style's own locator, so it preregisters like any
 * other route ([styleTimeRoutes] emits it as `BASEMAP_TILE_JSON`). Only the *tile* routes derived from
 * that document are unknowable in advance -- and the document arrives during `prepare(style)`, because
 * Rentile wires `TileJsonResourceAcquirer` into `StyleCompiler` (`DefaultBasemapRasterizer.kt:177-182`),
 * strictly before any tile is named in `prepareBatch`. RenG therefore reads that document's bytes as the
 * firewall answers the route it already preregistered for it -- `OperationRegistry` observes them *after*
 * its allowlist lookup, never instead of one -- parses them with [parseBasemapTileJson], and folds the
 * result back in with [completeBasemapStyleManifest] before the tile phase opens. A source completed that
 * way becomes an ordinary [BasemapStyleSource] and every composition below applies to it unchanged.
 *
 * A document that never arrives, will not parse, or yields no usable template degrades **its own source**
 * -- one more [BasemapSourceUnderivableReason] -- and never the style, for the reason given below.
 *
 * **Over-registration, deliberately.** Which layers are active at a zoom, whether the sprite is fetched
 * at all, and which sources a retained layer actually references are all decided inside Rentile's own
 * layer compilation. RenG models none of that: it registers the sprite pair whenever `sprite` is a
 * string and every routable source in `sources`, for every tile. An unused preregistered route is never
 * answered and costs one SHA-256 in `OperationRegistry.preregister`; a missing one fails closed. Source
 * `bounds` are, for the same reason, deliberately not read: they only ever *suppress* requests.
 *
 * **A source RenG cannot derive is deferred, not fatal.** Every per-source condition -- an unusable
 * TileJSON document among them -- records a `BasemapSourceUnderivableReason` against that source
 * and contributes no route, instead of rejecting the whole style. Deferring costs nothing when the
 * source is unused: `StyleCompiler` has no loop over `sources` at all, and `compileVectorSource` /
 * `compileRasterSource` are reached only per referencing layer, so an unreferenced source is never
 * compiled and never fetched. When the source *is* used, the firewall refuses it at the moment it is
 * actually needed, and that refusal is not silent: `OperationRegistry.executeTransport` throws
 * `ambiguousRouteFailure()` with no fallback branch, which Rentile wraps and `classifyEngineFailure`
 * maps to `RESOURCE_UNAVAILABLE` at `RESOURCE_LOOKUP` -- naming a class this cycle preregisters no
 * route for, which makes it an attributable fingerprint rather than an anonymous outage.
 *
 * Rejecting the whole style upfront was strictly worse on both counts. It is no more precise:
 * `styleFailure` names the *style's* own key and class, never the offending source. And for a style
 * served from the consumer's own cache it is actively misleading, because `styleFailure`'s first line
 * collapses STORE-provenance content into `STORE_INTEGRITY_FAILED` at `STORE_VALIDATION` -- so one
 * unused `url` source would present as cache corruption and blame the consumer's store. The rule the
 * firewall already follows applies here too: be strict only where strictness cannot break a working
 * flow (`writeStore` is strict about a missing latch precisely because Rentile 0.7.0 cannot produce
 * one; `removeStore` is permissive about an unregistered key).
 *
 * Document-level faults stay fatal, because a document that will not parse yields no routes at all and
 * deferring would turn a precise parse failure into a total outage under a worse code. So does a
 * malformed `terrain` block, which is not a divergence at all: `compileTerrainSource`
 * (`StyleCompiler.kt:474-495`) throws on both of those conditions unconditionally.
 *
 * **Where RenG's reader is stricter than Rentile's.** Every known divergence runs in one direction --
 * RenG refuses a document Rentile would have read -- so each can only produce a loud typed failure,
 * never a wrong url. This list is *not* claimed exhaustive; it is what has actually been checked:
 *  - **Duplicate member names.** RenG rejects; kotlinx keeps the last occurrence. Reported under its own
 *    `STYLE_JSON_DUPLICATE_MEMBER_NAME` so it does not read as a corrupt download.
 *  - **Invalid UTF-8.** Rentile decodes with `bytes.decodeToString()` (`StyleCompiler.kt:84`), the
 *    *replacing* variant, so a malformed sequence becomes U+FFFD and the style still compiles. RenG's
 *    five `UTF8_*` reject codes fail the document instead.
 *  - **Number spelling.** kotlinx keeps an unquoted number token as an opaque literal, so
 *    `"maxzoom": 014` reads as `14` there while RenG's reader rejects it as `LEADING_ZERO`. The same
 *    holds for `BAD_FRACTION`, `BAD_EXPONENT` and `NON_FINITE_NUMBER`.
 *  - **Nesting depth.** RenG bounds it at `STYLE_JSON_MAXIMUM_DEPTH`; kotlinx imposes no ceiling.
 *
 * **A second version-and-configuration-pinned assumption**, alongside the credential one above.
 * `RasterSample.immediateChildren` and `RasterSample.ancestor` (`raster/RasterResource.kt:87-111`)
 * compose tile urls this file never emits. They are unreachable only because `BasemapEngineHost` passes
 * `TileSubstitutionPolicy.Disabled`, which makes `validateSubstitutionAllowance`
 * (`DefaultBasemapRasterizer.kt:663-681`) throw as soon as any plan failure exists, before
 * `substituteRaster` / `substituteVector` (`:786`, `:846`) can run. Enabling substitution would put
 * live urls outside this file's coverage, and would have to extend the derivation in the same change.
 */

/** A source's tile-Y direction. Rentile: `internal.style.TileScheme`. */
internal enum class BasemapTileScheme { XYZ, TMS }

/**
 * The four style source types RenG routes, each fixing both the [ResourceClass] its tiles carry and the
 * shape of the sampling. Every other declared source type (`image`, `video`, `raster-array`, ...) is
 * ignored outright: Rentile refuses to compile a layer that references one, so it never fetches one.
 */
internal enum class BasemapSourceKind { VECTOR, RASTER, RASTER_DEM, GEO_JSON }

/**
 * Every **document-level** reason a style yields no manifest at all, paired with the [StyleFailureKind]
 * the resource layer already maps onto a sanitized RenG failure -- `PARSE` becomes
 * `RESOURCE_PARSE_FAILED` and `UNSUPPORTED_FEATURE` becomes `UNSUPPORTED_RESOURCE_FEATURE`, both at
 * `RESOURCE_PARSING` (`ResourceOperationStateMachine.styleFailure`). `PARSE` means "this is not a style
 * document RenG can read"; `UNSUPPORTED_FEATURE` means "it reads, and uses something outside RenG's
 * supported subset".
 *
 * These are fatal because none of them leaves RenG with a document to derive *any* route from, so
 * deferring one would convert a precise parse failure into a total outage under a worse code. A fault
 * confined to a single source is not here -- see [BasemapSourceUnderivableReason].
 */
internal enum class BasemapStyleReject(val kind: StyleFailureKind) {
    STYLE_JSON_MALFORMED(StyleFailureKind.PARSE),

    /** RenG's reader is stricter than Rentile's here; see this file's header. */
    STYLE_JSON_DUPLICATE_MEMBER_NAME(StyleFailureKind.PARSE),
    STYLE_ROOT_NOT_OBJECT(StyleFailureKind.PARSE),
    SOURCES_NOT_OBJECT(StyleFailureKind.PARSE),
    STYLE_VERSION_UNSUPPORTED(StyleFailureKind.UNSUPPORTED_FEATURE),

    /** Not a divergence: `StyleCompiler.compileTerrainSource` (`:474-495`) throws on both of these. */
    TERRAIN_NOT_OBJECT(StyleFailureKind.PARSE),
    TERRAIN_SOURCE_NOT_STRING(StyleFailureKind.PARSE),

    /**
     * **A divergence, and a deliberate one.** Rentile ignores `terrain.exaggeration` entirely, so
     * RenG parses and owns it (design section 10) and is therefore the only party that can refuse a
     * bad one. `CONTEXT.md`'s house rule is that an out-of-domain value **fails rather than clamps
     * or wraps**: a style asking to exaggerate terrain by `"lots"` has asked for a picture with no
     * definition, and silently substituting `1` would draw a flat-looking map the author never
     * requested.
     *
     * **No finiteness check accompanies this, and its absence is deliberate rather than an
     * oversight.** [com.rohittp.reng.internal.json.parseJson] rejects a non-finite number token
     * outright (`JsonReader.kt:244`, `JsonReject.NON_FINITE_NUMBER`), so a `JsonValue.Real` reaching
     * here is finite by construction and a guard would be code no document could execute.
     *
     * The *range* is not constrained. Any finite multiple is a legal answer, negative included --
     * ADR 0037 reaffirmed that no tuned constant ships, and any bound here would be one.
     */
    TERRAIN_EXAGGERATION_NOT_NUMBER(StyleFailureKind.PARSE),
}

/**
 * Every reason one declared source contributes no route, while the rest of the style derives normally.
 *
 * None of these is a failure. RenG cannot tell a referenced source from an unreferenced one without
 * modelling layer retention, which it deliberately does not do, and Rentile never touches an
 * unreferenced source -- so rejecting the style over one would break a flow that works. When the source
 * *is* referenced, the firewall refuses the url at the moment the engine asks for it, loudly and
 * attributably; see this file's header for the whole argument. The reason is carried on
 * [BasemapStyleManifest.underivableSources] rather than discarded so that a later task can surface it
 * as a diagnostic, and so that the eventual firewall refusal has a documented, greppable cause.
 */
internal enum class BasemapSourceUnderivableReason {
    SOURCE_NOT_OBJECT,

    /**
     * Both forms at once. Not a divergence: `StyleCompiler.compileVectorSource` (`:1025`) and
     * `compileRasterSource` (`:1300`) both refuse "cannot declare both url and tiles", and both test
     * `"tiles" in source` -- key presence, not type -- so an empty or non-array `tiles` conflicts too.
     */
    SOURCE_DECLARES_URL_AND_TILES,
    SOURCE_TILES_NOT_STRINGS,
    SOURCE_TILES_EMPTY,
    SOURCE_REFERENCE_UNRESOLVABLE,
    SOURCE_SCHEME_UNSUPPORTED,
    SOURCE_ZOOM_NOT_INTEGER,
    SOURCE_ZOOM_RANGE_INVALID,
    SOURCE_TILE_SIZE_NOT_INTEGER,
    SOURCE_TILE_SIZE_UNSUPPORTED,
    GEO_JSON_DATA_NOT_STRING,

    /**
     * The source named a TileJSON document and no bytes for it reached RenG at all -- the engine never
     * asked for it (no retained layer references the source, which is the common case for the
     * 17 attribution-shaped and otherwise unused sources in a real style), or the exchange failed.
     */
    TILE_JSON_UNOBSERVED,

    // The document arrived and could not be turned into templates. Each mirrors one `failDecode` in
    // Rentile's `TileJsonResourceAcquirer.parseOrThrow`, kept distinct for the same reason the style
    // reader keeps its own distinct: the reason is the only thing a later diagnostic can carry.
    TILE_JSON_MALFORMED,
    TILE_JSON_ROOT_NOT_OBJECT,

    /** RenG's reader is stricter than kotlinx here, exactly as it is for the style document. */
    TILE_JSON_DUPLICATE_MEMBER_NAME,
    TILE_JSON_VERSION_NOT_STRING,
    TILE_JSON_VERSION_UNSUPPORTED,
    TILE_JSON_TILES_NOT_STRINGS,
    TILE_JSON_TILES_EMPTY,
    TILE_JSON_REFERENCE_UNRESOLVABLE,
    TILE_JSON_SCHEME_NOT_STRING,
    TILE_JSON_SCHEME_UNSUPPORTED,
    TILE_JSON_ZOOM_NOT_INTEGER,
    TILE_JSON_ZOOM_RANGE_INVALID,
    TILE_JSON_TILE_SIZE_NOT_INTEGER,
    TILE_JSON_TILE_SIZE_UNSUPPORTED,
}

/** One declared source RenG could not derive an exact url for, and why. */
internal data class UnderivableBasemapSource(
    val sourceId: String,
    val reason: BasemapSourceUnderivableReason,
)

/**
 * One source tile a style source will be asked for. Rentile's `RasterSample`/`VectorTileSample` carry
 * the output tile and the child offsets too; only these three fields take part in url composition, so
 * only these three are reproduced.
 */
internal data class BasemapTileSample(val sourceZ: Int, val sourceX: Int, val sourceY: Int)

/** One routable source of a style, with every field url composition needs already resolved. */
internal class BasemapStyleSource(
    val sourceId: String,
    val kind: BasemapSourceKind,
    tileTemplates: List<String>,
    /** The resolved absolute GeoJSON document url; non-null exactly for [BasemapSourceKind.GEO_JSON]. */
    val geoJsonReference: String?,
    val scheme: BasemapTileScheme,
    val minZoom: Int,
    val maxZoom: Int,
    /** `null` for a vector or GeoJSON source; 64, 256 or 512 for a raster or DEM one. */
    val tileSizePixels: Int?,
) {
    private val templateSnapshot: List<String> = freshListCopy(tileTemplates)

    /** Absolute, already resolved against the style's base URI, in declaration order. */
    val tileTemplates: List<String> get() = freshListCopy(templateSnapshot)

    internal val templateCount: Int get() = templateSnapshot.size

    internal fun templateAt(index: Int): String = templateSnapshot[index]

    /** Redacted: a tile template is a url and may carry a credential. */
    override fun toString(): String =
        "BasemapStyleSource(kind=$kind, scheme=$scheme, minZoom=$minZoom, " +
            "maxZoom=$maxZoom, templates=${templateSnapshot.size})"
}

/**
 * One declared source whose tile templates live in a TileJSON document rather than in the style: the
 * `"url": ".../tiles.json"` form. It carries the resolved document url -- which [styleTimeRoutes] routes
 * immediately -- plus every field the style itself declared, kept **unresolved and nullable** because
 * Rentile combines each with the document's own value rather than letting either side simply win. See
 * [completeBasemapStyleManifest] for that combination.
 */
internal class BasemapTileJsonSource(
    val sourceId: String,
    val kind: BasemapSourceKind,
    /** Absolute, already resolved against the style's base URI. Rentile's `resolveSourceReference`. */
    val documentUrl: String,
    /** `source["scheme"]`; `null` when the style declares none, in which case the document's applies. */
    val declaredScheme: BasemapTileScheme?,
    val declaredMinZoom: Int?,
    val declaredMaxZoom: Int?,
    /** `source["tileSize"]`/`["tile-size"]`, already checked against the compatibility profile. */
    val declaredTileSizePixels: Int?,
    /** 22 for a vector source, 30 for a raster or DEM one: the ceiling Rentile applies when the style is silent. */
    val defaultMaximumZoom: Int,
) {
    /** Redacted: [documentUrl] is a url and may carry a credential. */
    override fun toString(): String =
        "BasemapTileJsonSource(sourceId=$sourceId, kind=$kind, scheme=$declaredScheme, " +
            "minZoom=$declaredMinZoom, maxZoom=$declaredMaxZoom)"
}

/** Everything a style document says that decides which routes the engine will ask for. */
internal class BasemapStyleManifest(
    /** The style's own locator; every relative reference in the document resolves against it. */
    val baseUri: String,
    /**
     * The resolved sprite base, before [appendSpriteExtension] adds `.json`/`.png`. `null` when the
     * style declares no `sprite`, declares it in array form, or declares a relative reference that
     * cannot be resolved -- all three are cases in which Rentile fetches no sprite at all.
     */
    val spriteBase: String?,
    /**
     * The style's resolved `glyphs` template -- `{fontstack}`/`{range}` still unsubstituted -- or `null`
     * when the style declares none or declares a relative reference that cannot be resolved.
     *
     * RenG keeps its own copy rather than reading Rentile's, because `LabelCandidatePlan.glyphUrls`
     * substitutes the template *the caller passes it*: the credential that reaches RenG's transport is
     * the one in this string. Resolved through [resolveHttpReference], which returns an absolute
     * `http(s)` reference unchanged -- byte-for-byte the branch Rentile's `StyleCompiler` takes.
     */
    val glyphTemplate: String?,
    sources: List<BasemapStyleSource>,
    tileJsonSources: List<BasemapTileJsonSource>,
    underivableSources: List<UnderivableBasemapSource>,
    /** `root["terrain"]["source"]`, or `null` when the style declares no terrain. */
    val terrainSourceId: String?,
    /**
     * `root["terrain"]["exaggeration"]`, or the specification's default of `1.0`.
     *
     * RenG's own, because Rentile ignores the property: `compileTerrainSource` reads `source` and
     * nothing else, so nothing in the engine's descriptor carries it and the displacement RenG
     * performs is the only place it can be honoured.
     */
    val terrainExaggeration: Double,
) {
    private val sourceSnapshot: List<BasemapStyleSource> = freshListCopy(sources)
    private val tileJsonSourceSnapshot: List<BasemapTileJsonSource> = freshListCopy(tileJsonSources)
    private val underivableSnapshot: List<UnderivableBasemapSource> = freshListCopy(underivableSources)

    /** In declaration order, restricted to the four [BasemapSourceKind]s RenG routes. */
    val sources: List<BasemapStyleSource> get() = freshListCopy(sourceSnapshot)

    /**
     * The declared sources still waiting on a TileJSON document, in declaration order. Each contributes a
     * `BASEMAP_TILE_JSON` style-time route and no tile route; [completeBasemapStyleManifest] turns those
     * whose document arrived into ordinary [sources] and leaves this list empty.
     */
    val tileJsonSources: List<BasemapTileJsonSource> get() = freshListCopy(tileJsonSourceSnapshot)

    /**
     * The declared sources RenG could not derive an exact url for, in declaration order, and why. Each
     * contributes no route; none of them fails the style. Empty for a style RenG derives completely.
     */
    val underivableSources: List<UnderivableBasemapSource> get() = freshListCopy(underivableSnapshot)

    /** Redacted: [baseUri], [spriteBase] and [glyphTemplate] are urls and may carry a credential. */
    override fun toString(): String =
        "BasemapStyleManifest(sources=${sourceSnapshot.size}, " +
            "tileJson=${tileJsonSourceSnapshot.size}, " +
            "underivable=${underivableSnapshot.size}, sprite=${spriteBase != null}, " +
            "glyphs=${glyphTemplate != null}, " +
            "terrain=${terrainSourceId != null})"
}

internal sealed interface BasemapStyleManifestOutcome {
    class Derived(val manifest: BasemapStyleManifest) : BasemapStyleManifestOutcome

    data class Rejected(val reason: BasemapStyleReject) : BasemapStyleManifestOutcome {
        val kind: StyleFailureKind get() = reason.kind
    }
}

/**
 * Parses [styleBytes] -- the style document RenG already acquired under its own `BASEMAP_STYLE` route --
 * into the manifest [styleTimeRoutes] and [tileTimeRoutes] derive from. Pure: no I/O, no clock, no
 * randomness, and never throws for any input.
 *
 * [baseUri] is the style's own locator (`RendererConfiguration.basemapStyle`), which is what Rentile
 * receives as `StyleInput.Prefetched.baseUri` and what it resolves every relative reference against.
 *
 * Rejects only the document-level faults in [BasemapStyleReject]. A fault confined to one source is
 * recorded on [BasemapStyleManifest.underivableSources] and the rest of the style derives normally.
 */
internal fun deriveBasemapStyleManifest(styleBytes: ByteArray, baseUri: String): BasemapStyleManifestOutcome =
    try {
        BasemapStyleManifestOutcome.Derived(readStyleManifest(styleBytes, baseUri))
    } catch (signal: StyleRejectSignal) {
        BasemapStyleManifestOutcome.Rejected(signal.reason)
    }

/**
 * The routes the engine fetches while *compiling* the style: the sprite JSON and image pair, one document
 * per GeoJSON source, and one TileJSON document per `url`-form source. Rentile touches exactly these
 * classes inside `prepare` (`StyleCompiler.compile` -> `SpriteResourceAcquirer.acquire` at `:126-133`,
 * `compileVectorSource`'s GeoJSON branch at `:1007-1010`, and `resolveTileJson` at `:1029` / `:1304`).
 *
 * The `BASEMAP_TILE_JSON` routes are what make the reference form work at all: they are declared here,
 * before compilation, so the document RenG needs in order to derive that source's *tile* routes is one
 * the firewall is already willing to answer. Nothing observes bytes for a route this function did not
 * emit.
 */
internal fun styleTimeRoutes(
    manifest: BasemapStyleManifest,
    accessMode: ResourceAccessMode,
    limits: ResourceLimits,
): List<ResourceRouteKey> {
    val routes = ArrayList<ResourceRouteKey>()
    manifest.spriteBase?.let { base ->
        routes += routeFor(appendSpriteExtension(base, ".json"), ResourceClass.BASEMAP_SPRITE_JSON, accessMode, limits)
        routes += routeFor(appendSpriteExtension(base, ".png"), ResourceClass.BASEMAP_SPRITE_IMAGE, accessMode, limits)
    }
    manifest.sources.forEach { source ->
        source.geoJsonReference?.let { reference ->
            routes += routeFor(reference, ResourceClass.BASEMAP_GEO_JSON, accessMode, limits)
        }
    }
    manifest.tileJsonSources.forEach { pending ->
        routes += routeFor(pending.documentUrl, ResourceClass.BASEMAP_TILE_JSON, accessMode, limits)
    }
    return routes.distinct()
}

/**
 * The raster, vector and DEM tile routes for [tiles]. Rentile touches exactly these classes inside
 * `prepareBatch` (`DefaultBasemapRasterizer.planRasterResources` at `:540-572` and `planVectorResources`
 * at `:574-609`); `render` performs no adapter call at all.
 *
 * Every `raster-dem` source is expanded over its 3x3 neighbourhood, because a hillshade layer samples
 * one (`:552-557` -> `RasterResource.neighbor`) and RenG does not model whether the style has one. The
 * centre sample is a member of that neighbourhood, so a DEM source reached only through `terrain`
 * (which samples the centre alone) is covered by the same expansion.
 *
 * [tiles] are `CanonicalBasemapTile`s from `selectBasemapTiles`, whose LOD range is `0..22` and whose
 * `canonicalX` is already canonical -- the `floorMod` below is Rentile's own and is therefore an
 * identity for such a tile, kept only so this stays a literal port.
 */
internal fun tileTimeRoutes(
    manifest: BasemapStyleManifest,
    tiles: List<CanonicalBasemapTile>,
    accessMode: ResourceAccessMode,
    limits: ResourceLimits,
): List<ResourceRouteKey> {
    val routes = ArrayList<ResourceRouteKey>()
    tiles.forEach { tile ->
        manifest.sources.forEach { source ->
            val resourceClass = tileResourceClassOf(source.kind) ?: return@forEach
            val centre = basemapTileSampleFor(source, tile) ?: return@forEach
            demNeighbourhoodOrSelf(source, centre).forEach { sample ->
                routes += routeFor(basemapTileUrl(source, sample), resourceClass, accessMode, limits)
            }
        }
    }
    return routes.distinct()
}

/**
 * The `BASEMAP_GLYPH_RANGE` routes for [urls] -- the exact strings
 * `com.rohittp.rentile.LabelCandidatePlan.glyphUrls` just returned, in the order it returned them.
 *
 * The third route-derivation function, and the one that derives nothing: [styleTimeRoutes] and
 * [tileTimeRoutes] both *compose* urls by re-implementing private Rentile logic, whereas the engine's
 * own frozen Glyph Closure composes these and hands them over. What is left is the mechanical part --
 * pairing each url with [ResourceClass.BASEMAP_GLYPH_RANGE] and the one ceiling that class carries --
 * and it lives here rather than in the firewall so that all three route derivations sit together and
 * share one `routeFor`; a route built by hand somewhere else is a route whose ceiling can drift, and the
 * ceiling is part of route identity.
 *
 * `distinct()` for the same reason its two siblings have it, though the engine's closure is already
 * duplicate-free: two identical preregistrations are idempotent in
 * `com.rohittp.reng.internal.firewall.OperationRegistry.preregister` anyway, so this only trims work.
 */
internal fun glyphRangeRoutes(
    urls: List<String>,
    accessMode: ResourceAccessMode,
    limits: ResourceLimits,
): List<ResourceRouteKey> =
    urls.map { url -> routeFor(url, ResourceClass.BASEMAP_GLYPH_RANGE, accessMode, limits) }.distinct()

/**
 * The four facts a TileJSON document contributes to url composition. Rentile's `ResolvedTileJson`
 * (`metadata/TileJsonResourceAcquirer.kt:33-42`) carries two more -- `bounds` and the two digests -- and
 * neither takes part in composing a url: `bounds` only ever *suppresses* requests, which RenG
 * deliberately does not model, and the digests are Rentile's own cache identity.
 */
internal class BasemapTileJsonDocument(
    tileTemplates: List<String>,
    val scheme: BasemapTileScheme,
    val minZoom: Int,
    val maxZoom: Int,
    /** 64, 256 or 512 when the document declares one; `null` when it does not. */
    val tileSizePixels: Int?,
) {
    private val templateSnapshot: List<String> = freshListCopy(tileTemplates)

    /** Absolute, already resolved against the **document's own** url, in declaration order. */
    val tileTemplates: List<String> get() = freshListCopy(templateSnapshot)

    /** Redacted: a tile template is a url and may carry a credential. */
    override fun toString(): String =
        "BasemapTileJsonDocument(scheme=$scheme, minZoom=$minZoom, maxZoom=$maxZoom, " +
            "templates=${templateSnapshot.size})"
}

internal sealed interface BasemapTileJsonOutcome {
    class Parsed(val document: BasemapTileJsonDocument) : BasemapTileJsonOutcome

    data class Rejected(val reason: BasemapSourceUnderivableReason) : BasemapTileJsonOutcome
}

/**
 * Rentile's `TileJsonResourceAcquirer.parseOrThrow` (`:161-243`), reproduced over RenG's own JSON reader
 * and restricted to the fields that compose a url. Pure, and never throws for any input: an unusable
 * document degrades the one source that named it.
 *
 * [documentUrl] is the exact url the document was fetched from, and it is the base every `tiles` entry
 * resolves against -- **not** the style's locator. Rentile passes `baseUrl = url` into
 * `resolveHttpReference` at `:180`, and the two bases routinely differ in host (a style on one CDN naming
 * a TileJSON on a tile API is the ordinary shape in the verified corpus), so resolving against the style
 * composes a well-formed url no route covers.
 *
 * The known divergences from kotlinx are the same four this file's header lists for the style document --
 * duplicate member names, invalid UTF-8, number spelling, and nesting depth -- and run in the same
 * direction: RenG refuses a document Rentile would have read, degrading the source rather than composing
 * a wrong url. `tilejson` version, `tiles`, `minzoom`, `maxzoom`, `scheme` and `tileSize` are read exactly
 * as Rentile reads them, defaults included; `bounds` is not read at all (see [tileTimeRoutes]).
 */
internal fun parseBasemapTileJson(documentBytes: ByteArray, documentUrl: String): BasemapTileJsonOutcome =
    try {
        BasemapTileJsonOutcome.Parsed(readTileJsonDocument(documentBytes, documentUrl))
    } catch (signal: SourceUnderivableSignal) {
        BasemapTileJsonOutcome.Rejected(signal.reason)
    }

/**
 * Folds every observed TileJSON document back into [manifest], turning each `url`-form source whose
 * document arrived and parsed into an ordinary [BasemapStyleSource] that [tileTimeRoutes] composes urls
 * for exactly as it does for an inline one, and recording every other one as underivable.
 *
 * [documents] is keyed by the exact [BasemapTileJsonSource.documentUrl] the route was preregistered
 * under. A source with no entry is [BasemapSourceUnderivableReason.TILE_JSON_UNOBSERVED] -- the ordinary
 * outcome for a source no retained layer references, since Rentile compiles a source only per referencing
 * layer and so never fetches an unused one.
 *
 * **Where the two sides of a field disagree, Rentile's own preference is reproduced, and it is not "one
 * side wins".** From `StyleCompiler.compileVectorSource` (`:1036-1060`) and `compileRasterSource`
 * (`:1311-1342`):
 *  - **templates** -- the document's replace the style's outright (`resolvedTileJson?.tileTemplates ?:
 *    inlineTemplates`), and a source declaring both forms never reaches here at all.
 *  - **scheme** -- the *style's* wins when it declares one, else the document's, else `xyz`.
 *  - **minzoom** -- `maxOf(style ?: 0, document)`: the tighter floor.
 *  - **maxzoom** -- `minOf(style ?: default, document)`: the tighter ceiling. Note that the style's own
 *    declared value is *not* additionally clamped to the kind's default here, unlike the inline form,
 *    because Rentile's `minOf` has the document's value as its other operand rather than the default.
 *    Note too that a document declaring no `maxzoom` defaults to **22**, so a raster source whose own
 *    default is 30 is still capped at 22 by an otherwise silent document.
 *  - **tileSize** -- the style's, else the document's, else 512. Both inputs were already checked against
 *    the compatibility profile where they were read, so the combined value needs no further check.
 *  - **bounds** -- read by Rentile from either side and deliberately not modelled here at all.
 *
 * Idempotent: the returned manifest has no [BasemapStyleManifest.tileJsonSources] left, so completing it
 * again adds nothing and duplicates nothing.
 */
internal fun completeBasemapStyleManifest(
    manifest: BasemapStyleManifest,
    documents: Map<String, BasemapTileJsonOutcome>,
): BasemapStyleManifest {
    val pending = manifest.tileJsonSources
    if (pending.isEmpty()) return manifest
    val resolved = ArrayList<BasemapStyleSource>(manifest.sources)
    val underivable = ArrayList<UnderivableBasemapSource>(manifest.underivableSources)
    pending.forEach { source ->
        when (val outcome = documents[source.documentUrl]) {
            null -> underivable += UnderivableBasemapSource(
                source.sourceId,
                BasemapSourceUnderivableReason.TILE_JSON_UNOBSERVED,
            )

            is BasemapTileJsonOutcome.Rejected ->
                underivable += UnderivableBasemapSource(source.sourceId, outcome.reason)

            is BasemapTileJsonOutcome.Parsed -> try {
                resolved += combineTileJsonSource(source, outcome.document)
            } catch (signal: SourceUnderivableSignal) {
                underivable += UnderivableBasemapSource(source.sourceId, signal.reason)
            }
        }
    }
    return BasemapStyleManifest(
        baseUri = manifest.baseUri,
        spriteBase = manifest.spriteBase,
        glyphTemplate = manifest.glyphTemplate,
        sources = resolved,
        tileJsonSources = emptyList(),
        underivableSources = underivable,
        terrainSourceId = manifest.terrainSourceId,
        terrainExaggeration = manifest.terrainExaggeration,
    )
}

/**
 * Rentile's `resolveHttpReference` (`metadata/TileJsonResourceAcquirer.kt:292-320`), ported verbatim.
 *
 * **This is not RFC 3986 and must not be made to be.** It strips the base's query *and* fragment before
 * joining, splices only the scheme for a `//`-prefixed reference (ignoring the base's own origin),
 * returns an already-absolute `http(s)` reference completely unnormalised, and drops every empty path
 * segment -- so a trailing slash disappears and a `//host` reference with no path gains one. Rewriting
 * any of that "correctly" silently breaks every relative reference in a style.
 *
 * Returns `null` when [baseUrl] carries no `://`, or begins with one; Rentile treats that as an
 * unresolvable reference.
 */
internal fun resolveHttpReference(baseUrl: String, reference: String): String? {
    if (reference.startsWith("https://") || reference.startsWith("http://")) return reference
    val schemeEnd = baseUrl.indexOf("://")
    if (schemeEnd <= 0) return null
    val originStart = schemeEnd + 3
    val pathStart = baseUrl.indexOf('/', originStart).let { if (it < 0) baseUrl.length else it }
    val origin = baseUrl.substring(0, pathStart).substringBefore('?')
    val basePath = baseUrl.substring(pathStart).substringBefore('?').substringBefore('#')
    val combined = when {
        reference.startsWith("//") -> baseUrl.substring(0, schemeEnd + 1) + reference
        reference.startsWith('/') -> origin + reference
        else -> origin + basePath.substringBeforeLast('/', missingDelimiterValue = "") + "/" + reference
    }
    val absoluteSchemeEnd = combined.indexOf("://")
    val absolutePathStart = combined.indexOf('/', absoluteSchemeEnd + 3).let { if (it < 0) combined.length else it }
    val absoluteOrigin = combined.substring(0, absolutePathStart)
    val pathAndQuery = combined.substring(absolutePathStart)
    val path = pathAndQuery.substringBefore('?').substringBefore('#')
    val suffix = pathAndQuery.removePrefix(path)
    val normalized = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
            else -> normalized += segment
        }
    }
    return absoluteOrigin + "/" + normalized.joinToString("/") + suffix
}

/**
 * Rentile's `appendSpriteExtension` (`sprite/SpriteResourceAcquirer.kt:279-287`), ported verbatim: the
 * extension is inserted before the query and the fragment, locating `#` first and `?` second.
 *
 * It **appends unconditionally** -- it does not drop an existing extension -- so a `sprite` base already
 * ending in `.json` yields `....json.json`, and that doubled url is the one the engine requests.
 */
internal fun appendSpriteExtension(baseUrl: String, extension: String): String {
    val fragmentIndex = baseUrl.indexOf('#').let { if (it < 0) baseUrl.length else it }
    val withoutFragment = baseUrl.substring(0, fragmentIndex)
    val fragment = baseUrl.substring(fragmentIndex)
    val queryIndex = withoutFragment.indexOf('?').let { if (it < 0) withoutFragment.length else it }
    val path = withoutFragment.substring(0, queryIndex)
    val query = withoutFragment.substring(queryIndex)
    return path + extension + query + fragment
}

/**
 * Rentile's `CompiledRasterSource.sampleFor` / `CompiledVectorSource.sampleFor`
 * (`raster/RasterResource.kt:40-60`, `mvt/VectorSource.kt:73-93` -- byte-identical to each other),
 * ported verbatim, minus the `bounds` test RenG deliberately does not model.
 *
 * The trap here is `sourceZ = min(tile.z, maxZoom)`: past a source's maxzoom the url's zoom is **not**
 * the LOD RenG selected, and neither are its x and y -- they are divided by the child scale. Getting it
 * wrong preregisters a route the engine never asks for and omits the one it does.
 */
internal fun basemapTileSampleFor(source: BasemapStyleSource, tile: CanonicalBasemapTile): BasemapTileSample? {
    if (source.templateCount == 0) return null
    if (tile.lod < source.minZoom) return null
    val outputDimension = 1L shl tile.lod
    val canonicalOutputX = tile.canonicalX.toLong().floorModOf(outputDimension)
    val sourceZ = minOf(tile.lod, source.maxZoom)
    val zoomDelta = tile.lod - sourceZ
    val childScale = 1 shl zoomDelta
    return BasemapTileSample(
        sourceZ = sourceZ,
        sourceX = (canonicalOutputX / childScale).toInt(),
        sourceY = tile.tileY / childScale,
    )
}

/**
 * Rentile's `RasterSample.tileUrl` / `VectorTileSample.tileUrl` (`raster/RasterResource.kt:62-75`,
 * `mvt/VectorSource.kt:95-108`), ported verbatim.
 *
 * Two traps. `templateIndex` is a **hash round-robin**, `floorMod(sourceZ * 31 + sourceX * 17 +
 * sourceY, templates.size)` -- any other distribution picks the wrong host from a multi-template source
 * and every tile fails closed. And `{y}` and `{-y}` are **two different substitutions**: `{y}` is the
 * scheme-dependent request y, `{-y}` is always the flipped y, so under `xyz` they differ. The
 * substitution order below is Rentile's own and is preserved.
 */
internal fun basemapTileUrl(source: BasemapStyleSource, sample: BasemapTileSample): String {
    val dimension = 1L shl sample.sourceZ
    val templateIndex = (sample.sourceZ.toLong() * 31L + sample.sourceX * 17L + sample.sourceY)
        .floorModOf(source.templateCount.toLong())
        .toInt()
    val template = source.templateAt(templateIndex)
    val requestY = when (source.scheme) {
        BasemapTileScheme.XYZ -> sample.sourceY
        BasemapTileScheme.TMS -> (dimension - 1L - sample.sourceY).toInt()
    }
    return template
        .replace("{z}", sample.sourceZ.toString())
        .replace("{x}", sample.sourceX.toString())
        .replace("{y}", requestY.toString())
        .replace("{-y}", (dimension - 1L - sample.sourceY).toString())
}

/**
 * Rentile's `RasterSample.neighbor` (`raster/RasterResource.kt:77-85`), ported verbatim: y is
 * **clipped** to the zoom's tile range (a neighbour off the top or bottom of the world does not exist),
 * x is **wrapped** with `floorMod` (the world is cylindrical).
 */
internal fun basemapTileSampleNeighbour(
    sample: BasemapTileSample,
    deltaX: Int,
    deltaY: Int,
): BasemapTileSample? {
    val dimension = 1L shl sample.sourceZ
    val neighbourY = sample.sourceY.toLong() + deltaY
    if (neighbourY !in 0 until dimension) return null
    return sample.copy(
        sourceX = (sample.sourceX.toLong() + deltaX).floorModOf(dimension).toInt(),
        sourceY = neighbourY.toInt(),
    )
}

// ---- internals -----------------------------------------------------------------------------------

/** Internal control-flow signal, unwound by [deriveBasemapStyleManifest]; never seen outside this file. */
private class StyleRejectSignal(val reason: BasemapStyleReject) : RuntimeException()

private fun rejectStyle(reason: BasemapStyleReject): Nothing = throw StyleRejectSignal(reason)

/**
 * Internal control-flow signal for a fault confined to one source, unwound into an
 * [UnderivableBasemapSource] by whichever of the three loops raised it -- [readSources] while reading the
 * style, [parseBasemapTileJson] while reading one TileJSON document, and [completeBasemapStyleManifest]
 * while combining the two. Never escapes any of them, and never reaches [deriveBasemapStyleManifest]'s
 * own catch: unlike [StyleRejectSignal] it is not a failure at all.
 */
private class SourceUnderivableSignal(val reason: BasemapSourceUnderivableReason) : RuntimeException()

private fun skipSource(reason: BasemapSourceUnderivableReason): Nothing = throw SourceUnderivableSignal(reason)

/**
 * A style document nests only as deep as its expressions do. Rentile's kotlinx reader imposes no
 * ceiling at all, so any ceiling here is RenG's own; it is set well past anything a hand-written or
 * generated style reaches so that it bounds a hostile document rather than a real one.
 */
private const val STYLE_JSON_MAXIMUM_DEPTH: Int = 256

private val SUPPORTED_TILE_SIZES: Set<Int> = setOf(64, 256, 512)

private const val VECTOR_DEFAULT_MAXIMUM_ZOOM: Int = 22
private const val RASTER_DEFAULT_MAXIMUM_ZOOM: Int = 30
private const val MAXIMUM_SOURCE_ZOOM: Int = 30
private const val DEFAULT_RASTER_TILE_SIZE: Int = 512

/**
 * A TileJSON document nests only as deep as its `vector_layers` and `tilestats` blocks do -- the deepest
 * in the verified corpus reaches six. Bounded by the same ceiling the style document uses, and for the
 * same reason: Rentile's kotlinx reader imposes none, so this bounds a hostile document, not a real one.
 */
private const val TILE_JSON_MAXIMUM_DEPTH: Int = STYLE_JSON_MAXIMUM_DEPTH

/** `parseOrThrow` :192 / :195 -- the defaults a document that declares neither zoom bound falls back to. */
private const val TILE_JSON_DEFAULT_MINIMUM_ZOOM: Int = 0
private const val TILE_JSON_DEFAULT_MAXIMUM_ZOOM: Int = 22

private fun readStyleManifest(styleBytes: ByteArray, baseUri: String): BasemapStyleManifest {
    val root = when (val parsed = parseJson(styleBytes, 0, styleBytes.size, STYLE_JSON_MAXIMUM_DEPTH)) {
        is JsonParse.Failed -> rejectStyle(
            if (parsed.reason == JsonReject.DUPLICATE_MEMBER_NAME) {
                BasemapStyleReject.STYLE_JSON_DUPLICATE_MEMBER_NAME
            } else {
                BasemapStyleReject.STYLE_JSON_MALFORMED
            },
        )

        is JsonParse.Parsed -> parsed.value as? JsonValue.Obj
            ?: rejectStyle(BasemapStyleReject.STYLE_ROOT_NOT_OBJECT)
    }
    if (!declaresStyleVersionEight(root.members["version"])) {
        rejectStyle(BasemapStyleReject.STYLE_VERSION_UNSUPPORTED)
    }
    val declaredSources = readSources(root, baseUri)
    return BasemapStyleManifest(
        baseUri = baseUri,
        // Only the string form resolves: Rentile ignores the array form (StyleCompiler.kt:1646, :1687),
        // and an unresolvable relative reference leaves its atlas unresolved rather than composing a url.
        spriteBase = (root.members["sprite"] as? JsonValue.Text)?.value?.let { resolveHttpReference(baseUri, it) },
        glyphTemplate = (root.members["glyphs"] as? JsonValue.Text)?.value?.let { resolveHttpReference(baseUri, it) },
        sources = declaredSources.routable,
        tileJsonSources = declaredSources.tileJsonBacked,
        underivableSources = declaredSources.underivable,
        terrainSourceId = readTerrainSourceId(root),
        terrainExaggeration = readTerrainExaggeration(root),
    )
}

/**
 * The body of [parseBasemapTileJson]. Throws [SourceUnderivableSignal] rather than returning, so that the
 * shape mirrors [readSource] exactly -- the caller converts it into a reason against the one source that
 * named this document.
 */
private fun readTileJsonDocument(documentBytes: ByteArray, documentUrl: String): BasemapTileJsonDocument {
    val root = when (
        val parsed = parseJson(documentBytes, 0, documentBytes.size, TILE_JSON_MAXIMUM_DEPTH)
    ) {
        is JsonParse.Failed -> skipSource(
            if (parsed.reason == JsonReject.DUPLICATE_MEMBER_NAME) {
                BasemapSourceUnderivableReason.TILE_JSON_DUPLICATE_MEMBER_NAME
            } else {
                BasemapSourceUnderivableReason.TILE_JSON_MALFORMED
            },
        )

        is JsonParse.Parsed -> parsed.value as? JsonValue.Obj
            ?: skipSource(BasemapSourceUnderivableReason.TILE_JSON_ROOT_NOT_OBJECT)
    }

    // `parseOrThrow` :167-174. The member is optional; when present it must be a string, and only the
    // 2.x and 3.x families are supported.
    root.members["tilejson"]?.let { declared ->
        val version = (declared as? JsonValue.Text)?.value
            ?: skipSource(BasemapSourceUnderivableReason.TILE_JSON_VERSION_NOT_STRING)
        if (!version.startsWith("2.") && !version.startsWith("3.")) {
            skipSource(BasemapSourceUnderivableReason.TILE_JSON_VERSION_UNSUPPORTED)
        }
    }

    // `parseOrThrow` :175-186. Every entry resolves against the DOCUMENT's url, and an empty or absent
    // array is a decode failure there rather than an empty template list.
    val templates = (root.members["tiles"] as? JsonValue.Arr)?.elements.orEmpty().map { element ->
        val reference = (element as? JsonValue.Text)?.value
            ?: skipSource(BasemapSourceUnderivableReason.TILE_JSON_TILES_NOT_STRINGS)
        resolveHttpReference(documentUrl, reference)
            ?: skipSource(BasemapSourceUnderivableReason.TILE_JSON_REFERENCE_UNRESOLVABLE)
    }
    if (templates.isEmpty()) skipSource(BasemapSourceUnderivableReason.TILE_JSON_TILES_EMPTY)

    val minZoom = root.members["minzoom"]?.let { declared ->
        intPrimitiveOrNull(declared) ?: skipSource(BasemapSourceUnderivableReason.TILE_JSON_ZOOM_NOT_INTEGER)
    } ?: TILE_JSON_DEFAULT_MINIMUM_ZOOM
    val maxZoom = root.members["maxzoom"]?.let { declared ->
        intPrimitiveOrNull(declared) ?: skipSource(BasemapSourceUnderivableReason.TILE_JSON_ZOOM_NOT_INTEGER)
    } ?: TILE_JSON_DEFAULT_MAXIMUM_ZOOM
    if (minZoom !in 0..MAXIMUM_SOURCE_ZOOM || maxZoom !in minZoom..MAXIMUM_SOURCE_ZOOM) {
        skipSource(BasemapSourceUnderivableReason.TILE_JSON_ZOOM_RANGE_INVALID)
    }

    val scheme = when (val declaredScheme = root.members["scheme"]) {
        null -> BasemapTileScheme.XYZ
        else -> when ((declaredScheme as? JsonValue.Text)?.value) {
            null -> skipSource(BasemapSourceUnderivableReason.TILE_JSON_SCHEME_NOT_STRING)
            "xyz" -> BasemapTileScheme.XYZ
            "tms" -> BasemapTileScheme.TMS
            else -> skipSource(BasemapSourceUnderivableReason.TILE_JSON_SCHEME_UNSUPPORTED)
        }
    }

    val tileSize = (root.members["tileSize"] ?: root.members["tile-size"])?.let { declared ->
        intPrimitiveOrNull(declared) ?: skipSource(BasemapSourceUnderivableReason.TILE_JSON_TILE_SIZE_NOT_INTEGER)
    }
    if (tileSize != null && tileSize !in SUPPORTED_TILE_SIZES) {
        skipSource(BasemapSourceUnderivableReason.TILE_JSON_TILE_SIZE_UNSUPPORTED)
    }

    return BasemapTileJsonDocument(
        tileTemplates = templates,
        scheme = scheme,
        minZoom = minZoom,
        maxZoom = maxZoom,
        tileSizePixels = tileSize,
    )
}

/**
 * One `url`-form source combined with the document it named, exactly as `compileVectorSource` /
 * `compileRasterSource` combine them. See [completeBasemapStyleManifest] for the field-by-field argument;
 * this is that paragraph in code.
 */
private fun combineTileJsonSource(
    source: BasemapTileJsonSource,
    document: BasemapTileJsonDocument,
): BasemapStyleSource {
    val minZoom = maxOf(source.declaredMinZoom ?: 0, document.minZoom)
    val maxZoom = minOf(source.declaredMaxZoom ?: source.defaultMaximumZoom, document.maxZoom)
    if (minZoom !in 0..MAXIMUM_SOURCE_ZOOM || maxZoom !in minZoom..MAXIMUM_SOURCE_ZOOM) {
        skipSource(BasemapSourceUnderivableReason.SOURCE_ZOOM_RANGE_INVALID)
    }
    return BasemapStyleSource(
        sourceId = source.sourceId,
        kind = source.kind,
        tileTemplates = document.tileTemplates,
        geoJsonReference = null,
        scheme = source.declaredScheme ?: document.scheme,
        minZoom = minZoom,
        maxZoom = maxZoom,
        tileSizePixels = if (source.kind == BasemapSourceKind.VECTOR) {
            null
        } else {
            source.declaredTileSizePixels ?: document.tileSizePixels ?: DEFAULT_RASTER_TILE_SIZE
        },
    )
}

/**
 * Rentile reads `version` as `root["version"]?.asPrimitive()?.intOrNull != 8` (`StyleCompiler.kt:94-96`),
 * and kotlinx's `intOrNull` parses the primitive's *content* -- so a string-spelled `"8"` passes there
 * and must pass here. A fractional or exponential token never does, because its content does not parse
 * as an `Int`.
 */
private fun declaresStyleVersionEight(value: JsonValue?): Boolean = intPrimitiveOrNull(value) == 8

/** Rentile's `JsonPrimitive.intOrNull`, over RenG's own parsed tree. */
private fun intPrimitiveOrNull(value: JsonValue?): Int? = when (value) {
    is JsonValue.Integer -> if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        value.value.toInt()
    } else {
        null
    }

    is JsonValue.Text -> value.value.toIntOrNull()
    else -> null
}

private class DeclaredSources(
    val routable: List<BasemapStyleSource>,
    val tileJsonBacked: List<BasemapTileJsonSource>,
    val underivable: List<UnderivableBasemapSource>,
)

/** What one declared source turned out to be: routable now, routable once its document arrives, or neither. */
private sealed interface DeclaredSource {
    class Routable(val source: BasemapStyleSource) : DeclaredSource

    class TileJsonBacked(val source: BasemapTileJsonSource) : DeclaredSource
}

/**
 * Reads every declared source, partitioning them into the ones RenG can compose exact urls for now, the
 * ones waiting on a TileJSON document, and the ones it cannot route at all. A per-source fault is caught
 * here and recorded; only a `sources` member that is not an object at all is fatal, because that leaves
 * no source to record a reason against.
 */
private fun readSources(root: JsonValue.Obj, baseUri: String): DeclaredSources {
    val declared = root.members["sources"] ?: return DeclaredSources(emptyList(), emptyList(), emptyList())
    val sources = declared as? JsonValue.Obj ?: rejectStyle(BasemapStyleReject.SOURCES_NOT_OBJECT)
    val routable = ArrayList<BasemapStyleSource>()
    val tileJsonBacked = ArrayList<BasemapTileJsonSource>()
    val underivable = ArrayList<UnderivableBasemapSource>()
    sources.members.forEach { (sourceId, value) ->
        try {
            when (val read = readSource(sourceId, value, baseUri)) {
                null -> Unit
                is DeclaredSource.Routable -> routable += read.source
                is DeclaredSource.TileJsonBacked -> tileJsonBacked += read.source
            }
        } catch (signal: SourceUnderivableSignal) {
            underivable += UnderivableBasemapSource(sourceId, signal.reason)
        }
    }
    return DeclaredSources(routable, tileJsonBacked, underivable)
}

private fun readSource(sourceId: String, value: JsonValue, baseUri: String): DeclaredSource? {
    val source = value as? JsonValue.Obj ?: skipSource(BasemapSourceUnderivableReason.SOURCE_NOT_OBJECT)
    return when ((source.members["type"] as? JsonValue.Text)?.value) {
        "vector" -> readTileSource(sourceId, source, baseUri, BasemapSourceKind.VECTOR, VECTOR_DEFAULT_MAXIMUM_ZOOM)
        "raster" -> readTileSource(sourceId, source, baseUri, BasemapSourceKind.RASTER, RASTER_DEFAULT_MAXIMUM_ZOOM)
        "raster-dem" -> readTileSource(
            sourceId,
            source,
            baseUri,
            BasemapSourceKind.RASTER_DEM,
            RASTER_DEFAULT_MAXIMUM_ZOOM,
        )

        "geojson" -> DeclaredSource.Routable(readGeoJsonSource(sourceId, source, baseUri))
        else -> null
    }
}

private fun readTileSource(
    sourceId: String,
    source: JsonValue.Obj,
    baseUri: String,
    kind: BasemapSourceKind,
    defaultMaximumZoom: Int,
): DeclaredSource {
    // Rentile reads the reference as a *string* primitive, so a non-string `url` is simply absent to it
    // (StyleCompiler.kt:1024, :1299) and must be absent here too -- including for the conflict test below,
    // which Rentile only reaches when the reference is a string.
    val tileJsonReference = (source.members["url"] as? JsonValue.Text)?.value
    if (tileJsonReference != null && source.members.containsKey("tiles")) {
        skipSource(BasemapSourceUnderivableReason.SOURCE_DECLARES_URL_AND_TILES)
    }

    // Read in the same order for both forms, so a malformed member is reported identically whichever form
    // the source uses. For the `url` form each stays nullable: the document supplies the other operand,
    // and `completeBasemapStyleManifest` is where the two are combined.
    val declaredScheme = when (val scheme = source.members["scheme"]) {
        null -> null
        else -> when ((scheme as? JsonValue.Text)?.value) {
            "xyz" -> BasemapTileScheme.XYZ
            "tms" -> BasemapTileScheme.TMS
            else -> skipSource(BasemapSourceUnderivableReason.SOURCE_SCHEME_UNSUPPORTED)
        }
    }
    val declaredMinZoom = declaredZoom(source, "minzoom")
    val declaredMaxZoom = declaredZoom(source, "maxzoom")
    val declaredTileSize = if (kind == BasemapSourceKind.VECTOR) null else readTileSize(source)

    if (tileJsonReference != null) {
        return DeclaredSource.TileJsonBacked(
            BasemapTileJsonSource(
                sourceId = sourceId,
                kind = kind,
                documentUrl = resolveHttpReference(baseUri, tileJsonReference)
                    ?: skipSource(BasemapSourceUnderivableReason.SOURCE_REFERENCE_UNRESOLVABLE),
                declaredScheme = declaredScheme,
                declaredMinZoom = declaredMinZoom,
                declaredMaxZoom = declaredMaxZoom,
                declaredTileSizePixels = declaredTileSize,
                defaultMaximumZoom = defaultMaximumZoom,
            ),
        )
    }

    val declaredTemplates = (source.members["tiles"] as? JsonValue.Arr)?.elements.orEmpty()
    val templates = declaredTemplates.map { element ->
        val template = (element as? JsonValue.Text)?.value ?: skipSource(BasemapSourceUnderivableReason.SOURCE_TILES_NOT_STRINGS)
        resolveHttpReference(baseUri, template) ?: skipSource(BasemapSourceUnderivableReason.SOURCE_REFERENCE_UNRESOLVABLE)
    }
    if (templates.isEmpty()) skipSource(BasemapSourceUnderivableReason.SOURCE_TILES_EMPTY)

    val minZoom = maxOf(declaredMinZoom ?: 0, 0)
    val maxZoom = minOf(declaredMaxZoom ?: defaultMaximumZoom, defaultMaximumZoom)
    if (minZoom !in 0..MAXIMUM_SOURCE_ZOOM || maxZoom !in minZoom..MAXIMUM_SOURCE_ZOOM) {
        skipSource(BasemapSourceUnderivableReason.SOURCE_ZOOM_RANGE_INVALID)
    }

    return DeclaredSource.Routable(
        BasemapStyleSource(
            sourceId = sourceId,
            kind = kind,
            tileTemplates = templates,
            geoJsonReference = null,
            scheme = declaredScheme ?: BasemapTileScheme.XYZ,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileSizePixels = if (kind == BasemapSourceKind.VECTOR) {
                null
            } else {
                declaredTileSize ?: DEFAULT_RASTER_TILE_SIZE
            },
        ),
    )
}

private fun declaredZoom(source: JsonValue.Obj, member: String): Int? {
    val declared = source.members[member] ?: return null
    return intPrimitiveOrNull(declared) ?: skipSource(BasemapSourceUnderivableReason.SOURCE_ZOOM_NOT_INTEGER)
}

/** `null` when the source declares no tile size at all; the declared value otherwise, already profiled. */
private fun readTileSize(source: JsonValue.Obj): Int? {
    val declared = source.members["tileSize"] ?: source.members["tile-size"] ?: return null
    val tileSize = intPrimitiveOrNull(declared) ?: skipSource(BasemapSourceUnderivableReason.SOURCE_TILE_SIZE_NOT_INTEGER)
    if (tileSize !in SUPPORTED_TILE_SIZES) skipSource(BasemapSourceUnderivableReason.SOURCE_TILE_SIZE_UNSUPPORTED)
    return tileSize
}

private fun readGeoJsonSource(sourceId: String, source: JsonValue.Obj, baseUri: String): BasemapStyleSource {
    // An inline `"data": { ... }` GeoJSON document is legitimate MapLibre, and Rentile refuses it
    // (StyleCompiler.kt:1007-1008). RenG says so with its own code rather than emitting no route.
    val reference = (source.members["data"] as? JsonValue.Text)?.value
        ?: skipSource(BasemapSourceUnderivableReason.GEO_JSON_DATA_NOT_STRING)
    return BasemapStyleSource(
        sourceId = sourceId,
        kind = BasemapSourceKind.GEO_JSON,
        tileTemplates = emptyList(),
        geoJsonReference = resolveHttpReference(baseUri, reference)
            ?: skipSource(BasemapSourceUnderivableReason.SOURCE_REFERENCE_UNRESOLVABLE),
        // A GeoJSON source produces no tile url at all (StyleCompiler.kt:1013-1020); these three are the
        // values Rentile compiles it with, carried only so the value stays total.
        scheme = BasemapTileScheme.XYZ,
        minZoom = 0,
        maxZoom = VECTOR_DEFAULT_MAXIMUM_ZOOM,
        tileSizePixels = null,
    )
}

private fun readTerrainSourceId(root: JsonValue.Obj): String? {
    val declared = root.members["terrain"] ?: return null
    val terrain = declared as? JsonValue.Obj ?: rejectStyle(BasemapStyleReject.TERRAIN_NOT_OBJECT)
    return (terrain.members["source"] as? JsonValue.Text)?.value
        ?: rejectStyle(BasemapStyleReject.TERRAIN_SOURCE_NOT_STRING)
}

/**
 * `root["terrain"]["exaggeration"]`, defaulting to the style specification's own `1.0` when the
 * member is absent -- and to `1.0` for a style with no `terrain` block at all, where the value is
 * never read.
 *
 * All six corpus styles that declare terrain declare exactly `1`, which is the reason this is worth
 * a KDoc: `1` is the value at which an honoured exaggeration and an ignored one draw the same
 * picture, so nothing in the corpus can tell the two apart and no test may assert at it.
 */
private fun readTerrainExaggeration(root: JsonValue.Obj): Double {
    val terrain = root.members["terrain"] as? JsonValue.Obj ?: return DEFAULT_TERRAIN_EXAGGERATION
    val declared = terrain.members["exaggeration"] ?: return DEFAULT_TERRAIN_EXAGGERATION
    return when (declared) {
        is JsonValue.Integer -> declared.value.toDouble()
        is JsonValue.Real -> declared.value
        else -> rejectStyle(BasemapStyleReject.TERRAIN_EXAGGERATION_NOT_NUMBER)
    }
}

/** The style specification's own default, which is what a `terrain` block with no member means. */
private const val DEFAULT_TERRAIN_EXAGGERATION: Double = 1.0

private fun tileResourceClassOf(kind: BasemapSourceKind): ResourceClass? = when (kind) {
    BasemapSourceKind.VECTOR -> ResourceClass.BASEMAP_VECTOR_TILE
    BasemapSourceKind.RASTER -> ResourceClass.BASEMAP_RASTER_TILE
    BasemapSourceKind.RASTER_DEM -> ResourceClass.BASEMAP_DEM_TILE
    BasemapSourceKind.GEO_JSON -> null
}

/**
 * The 3x3 neighbourhood a hillshade layer samples, in Rentile's own iteration order (`deltaY` outer,
 * `deltaX` inner -- `DefaultBasemapRasterizer.kt:553-558`), or the single centre sample for every other
 * source kind.
 */
private fun demNeighbourhoodOrSelf(
    source: BasemapStyleSource,
    centre: BasemapTileSample,
): List<BasemapTileSample> =
    if (source.kind != BasemapSourceKind.RASTER_DEM) {
        listOf(centre)
    } else {
        (-1..1).flatMap { deltaY ->
            (-1..1).mapNotNull { deltaX -> basemapTileSampleNeighbour(centre, deltaX, deltaY) }
        }
    }

private fun routeFor(
    url: String,
    resourceClass: ResourceClass,
    accessMode: ResourceAccessMode,
    limits: ResourceLimits,
): ResourceRouteKey = ResourceRouteKey(
    accessMode = accessMode,
    locator = ResourceLocator(url),
    resourceClass = resourceClass,
    maximumResponseBytes = limits.maximumBytesFor(resourceClass),
)

private fun Long.floorModOf(divisor: Long): Long {
    val remainder = this % divisor
    return if (remainder < 0) remainder + divisor else remainder
}
