package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.rentile.ResourceClass as RentileResourceClass

/**
 * Derives the private key ADR 0016's firewall latches Rentile requests under, for real, against the
 * actual Rentile 0.2.0-through-0.5.0 derivation -- the placeholder `DeterministicRentilePrivateKeyResolver`
 * this replaces (removed once `RenGRenderer` was rewired onto this class, basemap task 17) only ever
 * needed process-local determinism, never agreement with Rentile's own cache keys.
 *
 * Rentile's raw-store paths (`VectorResourceAcquirer`, `RasterResourceAcquirer`,
 * `TileJsonResourceAcquirer`, `GeoJsonResourceAcquirer`, `SpriteResourceAcquirer`, all of which back
 * onto `RasterResourceAcquirer` for `DEM_TILE`) key every entry by
 * `sha256Hex(url.withRedactedAuthenticationQuery())` paired with Rentile's own [RentileResourceClass] --
 * verified by diffing Rentile's `ContentIdentity.kt` directly between its `0.2.0` release commit
 * (`2d0a5bf`) and the `0.5.0` release commit `d899cb2` the version `libs.versions.toml` pins --
 * byte-identical across every release in that range, so the
 * scheme below is not inferred from any single measurement run. RenG must reproduce that exact
 * derivation for the seven [ResourceClass] values Rentile itself fetches and keys, or RenG's own
 * diffing and eviction bookkeeping silently stops matching Rentile's actual cache entries: **a
 * permanent, unannounced cache miss, never a thrown failure** -- there is no failure surface for a
 * wrong-but-well-formed key to trip.
 *
 * The remaining four classes never reach Rentile's transport at all: [ResourceClass.BASEMAP_STYLE] has
 * no Rentile raw-store write (ADR 0016 -- style compiles privately and is never Rentile-cache-keyed),
 * and [ResourceClass.STICKER_IMAGE], [ResourceClass.MODEL_GLB], and [ResourceClass.MODEL_TEXTURE] are
 * fetched through RenG's own configured `Transport`/`Store`, never through Rentile's shared cache. For
 * those four, matching Rentile's derivation would be actively wrong: two resources differing only in a
 * credential (e.g. two stickers with different signed tokens) are distinct RenG resources, and
 * Rentile's redaction would collapse them into one key, corrupting preparation with
 * `AMBIGUOUS_RESOURCE_ROUTE`. Those four instead derive from RenG's own canonical resource identity
 * ([ResourceKeyDeriver.external]), which is already proven injective in locator and class.
 *
 * Rentile 0.3.0's ninth class, `GLYPH_RANGE` -- still the ninth and last at the pinned `0.5.0` -- is
 * deliberately absent from [engineKeyedResourceClassOf]:
 * it is reachable only through `acquireLabelCandidates`, which RenG never calls this cycle. The `when`
 * below is exhaustive over RenG's own [ResourceClass] -- an eleven-value enum this dependency's version
 * does not change -- not over [RentileResourceClass], so **this does not, and cannot, force a
 * compile-time update when a future Rentile release adds a class**: `RentileResourceClass` gaining a
 * ninth constant compiles this file unchanged, and the new constant simply stays unreachable from
 * either branch below. What *would* fail this file's compilation is Rentile renaming or removing one of
 * the seven constants already named here, since each is referenced by exact constant, not by ordinal or
 * string. Containment against a class this table doesn't route (`GLYPH_RANGE` included) is a runtime
 * property instead: [com.rohittp.reng.internal.firewall.OperationRegistry] fails closed on any
 * unrecognised Transport url or Store key, whether or not that class has a branch here at all.
 */
internal class ProductionRentilePrivateKeyResolver(
    private val sha256: Sha256Function,
) : RentilePrivateKeyResolver {
    private val ownIdentityDeriver = ResourceKeyDeriver(sha256)

    override fun resolve(
        locator: ResourceLocator,
        resourceClass: ResourceClass,
    ): RentilePrivateKey {
        val engineClassName = engineKeyedResourceClassOf(resourceClass)?.name
        val token = if (engineClassName != null) {
            val redactedUrl = redactAuthenticationQuery(locator.value)
            "$engineClassName:${sha256Hex(redactedUrl)}"
        } else {
            val ownStableId = ownIdentityDeriver.external(resourceClass, locator).identity.digest.lowercaseHex
            "${resourceClass.name}:$ownStableId"
        }
        return RentilePrivateKey(token)
    }

    private fun sha256Hex(value: String): String =
        sha256.digest(CanonicalBytes(value.encodeToByteArray())).lowercaseHex
}

/**
 * The seven [ResourceClass] values Rentile itself fetches, stores, and keys, mapped to the exact
 * [RentileResourceClass] Rentile pairs with its `sha256Hex(withRedactedAuthenticationQuery(url))`
 * stable id. Referencing Rentile's own enum constants (rather than duplicating its member names as
 * literal strings) means a future rename or removal in Rentile fails this file's compilation instead of
 * silently deriving a key Rentile never asks for.
 *
 * [ResourceClass.BASEMAP_STYLE], [ResourceClass.STICKER_IMAGE], [ResourceClass.MODEL_GLB], and
 * [ResourceClass.MODEL_TEXTURE] return `null`: the engine never keys them, so [resolve] falls through
 * to RenG's own canonical identity for those four instead.
 *
 * `internal` rather than file-private because [OperationRegistry][com.rohittp.reng.internal.firewall.OperationRegistry]
 * (Cycle E basemap task 17) reuses this exact table to know which classes Rentile's own
 * `RawResourceStore` ever keys at all -- a store-side answer must fail closed for the four classes
 * this returns `null` for, since the engine's `RawResourceStore` never sees them.
 */
internal fun engineKeyedResourceClassOf(resourceClass: ResourceClass): RentileResourceClass? =
    when (resourceClass) {
        ResourceClass.BASEMAP_TILE_JSON -> RentileResourceClass.TILE_JSON
        ResourceClass.BASEMAP_VECTOR_TILE -> RentileResourceClass.VECTOR_TILE
        ResourceClass.BASEMAP_RASTER_TILE -> RentileResourceClass.RASTER_TILE
        ResourceClass.BASEMAP_DEM_TILE -> RentileResourceClass.DEM_TILE
        ResourceClass.BASEMAP_SPRITE_JSON -> RentileResourceClass.SPRITE_JSON
        ResourceClass.BASEMAP_SPRITE_IMAGE -> RentileResourceClass.SPRITE_IMAGE
        ResourceClass.BASEMAP_GEO_JSON -> RentileResourceClass.GEO_JSON
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.STICKER_IMAGE,
        ResourceClass.MODEL_GLB,
        ResourceClass.MODEL_TEXTURE,
        -> null
    }

/**
 * The same RenG-to-Rentile resource-class correspondence [engineKeyedResourceClassOf] encodes, read in the
 * other direction: given the [RentileResourceClass] an engine failure reports, which [ResourceClass] is
 * RenG talking about? Derived from that single mapping rather than restating it, so the correspondence
 * cannot drift between the key RenG derives for a resource and the class RenG names when acquiring it
 * fails.
 *
 * [RentileResourceClass.STYLE] is the one entry that is not recoverable by inversion, and it is spelled
 * out here rather than left to fail closed. [engineKeyedResourceClassOf] answers a narrower question --
 * "does the engine key this class in its own raw store?" -- and returns `null` for
 * [ResourceClass.BASEMAP_STYLE] because RenG deliberately keys the style itself (see the KDoc there). The
 * correspondence is nonetheless real, and reachable: Rentile's `DefaultBasemapRasterizer` throws
 * `ResourceAcquisitionException(resourceClass = STYLE, ...)` on both style transport paths whenever the
 * style arrives as `StyleInput.Remote`, and RenG's own `RendererConfiguration.basemapStyle` is a
 * [com.rohittp.reng.ResourceLocator]. Dropping it would turn an ordinary "the style would not fetch" into
 * an opaque `BASEMAP_RENDER_FAILED`.
 *
 * `null` for anything else -- a ninth class a future Rentile adds, `GLYPH_RANGE` first among them -- is
 * the fail-closed answer: the caller reports a failure that names no resource rather than guessing at one.
 */
internal fun rengResourceClassOf(engineResourceClass: RentileResourceClass): ResourceClass? =
    if (engineResourceClass == RentileResourceClass.STYLE) {
        ResourceClass.BASEMAP_STYLE
    } else {
        ResourceClass.entries.firstOrNull { engineKeyedResourceClassOf(it) == engineResourceClass }
    }

private val AUTHENTICATION_QUERY_PARAMETER_NAMES: Set<String> = setOf(
    "access_token",
    "apikey",
    "api_key",
    "key",
    "mtsid",
    "session",
    "session_id",
    "token",
)

/**
 * Rewrites every query parameter whose **lowercased** name is one of Rentile's eight authentication
 * parameter names to `<name>=<redacted>` (preserving the parameter's original-case name), leaves every
 * other parameter and the fragment untouched, and returns the url unchanged when it carries no query
 * component at all. Byte-for-byte the same rewrite as Rentile's private
 * `com.rohittp.rentile.internal.withRedactedAuthenticationQuery` -- confirmed by reading that source at
 * Rentile's `0.2.0` release commit (`2d0a5bf`) and diffing it against the pinned `0.5.0` release
 * commit `d899cb2` -- unchanged across that range. Any
 * divergence here changes the hash input for all seven engine-keyed classes and silently breaks their
 * key agreement with Rentile.
 */
internal fun redactAuthenticationQuery(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url

    val fragmentStart = url.indexOf('#', startIndex = queryStart + 1).let { if (it < 0) url.length else it }
    val prefix = url.substring(0, queryStart + 1)
    val query = url.substring(queryStart + 1, fragmentStart)
    val suffix = url.substring(fragmentStart)

    val redactedQuery = query.split('&').joinToString("&") { parameter ->
        val separator = parameter.indexOf('=')
        val name = if (separator < 0) parameter else parameter.substring(0, separator)
        if (name.lowercase() in AUTHENTICATION_QUERY_PARAMETER_NAMES) "$name=<redacted>" else parameter
    }

    return prefix + redactedQuery + suffix
}
