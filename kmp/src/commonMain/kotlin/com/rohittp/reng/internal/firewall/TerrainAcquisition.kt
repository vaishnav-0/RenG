package com.rohittp.reng.internal.firewall

import com.rohittp.reng.RenGException
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.AcceleratedSha256
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.DemTexels
import com.rohittp.reng.internal.terrain.DemTileCoordinate
import com.rohittp.reng.internal.terrain.demTexelsOf
import com.rohittp.rentile.GroundRadianceDescriptor
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.TerrainDemEncoding
import com.rohittp.rentile.TerrainSourceDescriptor
import com.rohittp.rentile.TileId
import com.rohittp.rentile.ValidatedDemTile

/**
 * Cycle C's task 20, finally: the style's terrain descriptor, its ground radiance, and the DEM tiles for
 * one frame -- the visible set **plus a one-tile perimeter ring**.
 *
 * **This class acquires, translates and matches. It decodes nothing and draws nothing.**
 * [com.rohittp.reng.internal.terrain] turns the encoded bytes into heights and
 * [com.rohittp.reng.internal.gl] displaces the ground with them; what lives here is the half that has to
 * cross ADR 0016's firewall, which is the half with the traps in it.
 *
 * **Nothing Rentile-shaped leaves this file.** `TerrainDemEncoding` becomes
 * [com.rohittp.reng.internal.terrain.DemEncoding] and `TileId` becomes
 * [com.rohittp.reng.internal.terrain.DemTileCoordinate] here, and `ValidatedDemTile.texels` becomes
 * [com.rohittp.reng.internal.terrain.DemTexels] here, which is the same boundary
 * [engineKeyedResourceClassOf] and [BasemapEngineHost.engineTileIdOf] already draw: an engine type is
 * named in `internal/firewall` and nowhere else, so terrain sampling and terrain drawing never import the
 * engine's vocabulary to do arithmetic.
 *
 * **The pixels cross already decoded, and RenG decodes no DEM anywhere.** Rentile decodes a DEM to
 * validate it and, since `0.7.0`, keeps the result; RenG used to re-decode the encoded bytes with its own
 * PNG decoder, and five of the corpus's six terrain styles serve **WebP**, so every one of those tiles
 * fetched 200 and was silently thrown away. The encoded bytes are therefore **not** carried onward: they
 * had exactly one consumer, that decode, and holding a megabyte a tile for nothing is not free at a
 * hundred tiles a frame.
 *
 * **The ring is why this is not a one-line call.** A DEM grid is edge-*exclusive*: texel `i` is centred at
 * `(i + 0.5)/N`, so a tile boundary sits half a texel outside the outermost texel centre and is
 * represented by no texel in either tile. Two ground tiles displaced from their own DEMs alone therefore
 * disagree at every shared edge. Closing that needs one texel of each neighbour, so the acquisition is the
 * visible set's 8-neighbourhood -- `4*sqrt(T) + 4` extra tiles, about +45% at 93 tiles and +33% at 167
 * (design section 4). It costs no extra preregistration: see [terrainTileRequest].
 *
 * **Four things Rentile does that this class exists to absorb.**
 *
 * 1. [TerrainSourceDescriptor.sourceId] is `sha256Hex` of the style's source id, not the source id
 *    (`StyleCompiler.kt:1770`), so [terrainSource] hashes before it compares. Comparing the two strings
 *    directly makes every style disagree with itself and terrain silently never draws.
 * 2. **Results can be shorter than the request, silently.** Tiles below the source's `minimumZoom` or
 *    outside its `bounds` are dropped through `mapNotNull` -- no diagnostic, no exception -- and
 *    [TerrainSourceDescriptor] exposes no `bounds`, so RenG cannot predict which. [matchDemTiles]
 *    therefore matches on [ValidatedDemTile.requestedTile] and never on position: index matching against a
 *    short list shifts every tile after the first gap, turning a missing picture into a plausible wrong
 *    one (ADR 0041).
 * 3. **One failing tile fails the whole call** (`throwAcquisitionFailures`), and RenG's contract forbids a
 *    retry. Under ADR 0041 that does not fail the frame: [acquire] answers
 *    [TerrainAcquisitionOutcome.Degraded], the ground draws flat, and a diagnostic says so. Terrain is the
 *    only basemap resource whose absence has a picture RenG has already shipped, which is what makes this a
 *    line rather than a slope.
 * 4. [groundRadiance] **is not about terrain at all** -- see its own KDoc.
 *
 * Nothing here reads an engine message or an engine cause: every failure arrives already sanitized by
 * [BasemapEngineHost.engineCall] and is carried, not re-read (ADR 0016).
 */
internal class TerrainAcquisition(
    private val host: BasemapEngineHost,
    private val sha256: Sha256Function = AcceleratedSha256,
) {

    /**
     * The `terrain` source, once RenG and the engine have been confirmed to mean the same one.
     *
     * **The comparison is the point of this method.** Both sides read `root["terrain"]["source"]` from the
     * same bytes -- RenG into [BasemapStyleManifest.terrainSourceId], Rentile into a compiled source whose
     * public [TerrainSourceDescriptor.sourceId] is `sha256Hex` of that same string. They agree today, and
     * the check is what makes a future divergence loud instead of invisible: RenG's DEM routes are derived
     * from *its* reading of the document, so a frame where the two disagree is a frame whose ring is
     * preregistered for one source while the engine fetches another.
     *
     * [TerrainSourceOutcome.Disagreed] rather than a thrown failure, because ADR 0041's rule reaches this
     * case too: the style asked for terrain and RenG cannot supply it, which has a defined picture.
     */
    fun terrainSource(style: PreparedStyle, manifest: BasemapStyleManifest): TerrainSourceOutcome {
        val descriptor = host.terrainSourceDescriptor(style)
        val styleSourceId = manifest.terrainSourceId
        if (descriptor == null) {
            return if (styleSourceId == null) TerrainSourceOutcome.NotDeclared else TerrainSourceOutcome.Disagreed
        }
        if (styleSourceId == null) return TerrainSourceOutcome.Disagreed
        if (descriptor.sourceId != sha256Hex(styleSourceId)) return TerrainSourceOutcome.Disagreed
        return TerrainSourceOutcome.Declared(terrainSourceOf(descriptor, styleSourceId))
    }

    /**
     * The style's ground radiance, or `null` when it declares no complete supported ground-light pair.
     *
     * **Independent of terrain, and exposed here only because both come off the same compiled style.**
     * Rentile compiles it from the style's top-level `lights` array (`internal/style/GroundLight.kt`),
     * never from `terrain`: a style with lights and no terrain yields one, and a style with terrain and no
     * lights yields `null`. It is a flat colour multiplier -- it tints, and it cannot make relief visible,
     * so it is not terrain shading and must not be mistaken for it (design section 7).
     *
     * **Its three components are gamma re-encoded over a sum, so the range is `[0, 2^(1/2.2)]`, roughly
     * `[0, 1.366]`, and is deliberately not clamped here.** A component above `1.0` is a legal answer from
     * a style whose ambient and directional lights are both at full intensity, not a defect and not
     * something to correct on the way past; with Rentile's own defaults the value is about `0.969`, which
     * is near-white rather than white and is worth knowing before a faint tint is read as a bug.
     */
    fun groundRadiance(style: PreparedStyle): GroundRadianceDescriptor? = host.groundRadianceDescriptor(style)

    /**
     * One frame's terrain: the source, and a DEM tile for as much of the visible set and its perimeter
     * ring as Rentile actually returned.
     *
     * Never throws for an acquisition failure and never fails the frame (ADR 0041). It does let a
     * [kotlinx.coroutines.CancellationException] through unwrapped, because a cancelled frame is not a
     * degraded frame -- only [RenGException] is caught below, and cancellation is not one.
     */
    suspend fun acquire(
        style: PreparedStyle,
        manifest: BasemapStyleManifest,
        visibleTiles: List<CanonicalBasemapTile>,
    ): TerrainAcquisitionOutcome {
        val source = when (val outcome = terrainSource(style, manifest)) {
            TerrainSourceOutcome.NotDeclared -> return TerrainAcquisitionOutcome.NotDeclared
            TerrainSourceOutcome.Disagreed -> return TerrainAcquisitionOutcome.Degraded(
                reason = TerrainDegradationReason.SOURCE_DISAGREEMENT,
                failure = null,
            )
            is TerrainSourceOutcome.Declared -> outcome.source
        }

        val requested = terrainTileRequest(visibleTiles)
        // A frame with no ground tiles has no DEM to ask for, and asking anyway would spend one engine
        // operation to be told so. The outcome is still Acquired: nothing was requested, so nothing is
        // absent, and there is nothing for ADR 0041's coverage diagnostic to report.
        if (requested.isEmpty()) {
            return TerrainAcquisitionOutcome.Acquired(source, emptyList(), emptyMap())
        }

        val results = try {
            host.acquireTerrainTiles(style, requested)
        } catch (failure: RenGException) {
            return TerrainAcquisitionOutcome.Degraded(
                reason = TerrainDegradationReason.ACQUISITION_FAILED,
                failure = failure,
            )
        }
        return TerrainAcquisitionOutcome.Acquired(
            source,
            requested,
            matchDemTiles(requested, results, source.tileSizePx),
        )
    }

    /**
     * Pairs each requested tile with the DEM tile Rentile returned **for that tile**, dropping every
     * request it returned nothing for.
     *
     * `internal` rather than private because this is where this class's hardest guarantee lives and it is
     * the one seam a test can drive with a deliberately short, deliberately reordered result list without
     * needing a rasteriser: [ValidatedDemTile] is an ordinary public Rentile class with a public
     * constructor.
     *
     * A result naming a tile that was never requested is dropped rather than reported. Rentile derives
     * every `requestedTile` from the list it was handed -- `sampleFor` copies the argument into
     * `outputTile` -- so an unrequested one cannot arise, and inventing a failure for it would be a second
     * opinion about the engine's own bookkeeping.
     *
     * **A result whose texels disagree with [tileSizePx] is dropped in exactly the same way**, and that
     * placement is the point rather than a convenience: ADR 0041 already counts a requested tile with no
     * entry here, so a DEM that arrives unusable becomes *absent* in the one sense the coverage
     * diagnostic already reports. Rentile bounds a DEM's dimensions against its own policy ceiling and
     * never against the `tileSizePx` its own `TerrainSourceDescriptor` declares, which is why this is
     * still RenG's check to make.
     */
    internal fun matchDemTiles(
        requested: List<CanonicalBasemapTile>,
        results: List<ValidatedDemTile>,
        tileSizePx: Int,
    ): Map<CanonicalBasemapTile, AcquiredDemTile> {
        if (results.isEmpty()) return emptyMap()
        val byRequestedTile = results.associateBy { it.requestedTile }
        val matched = LinkedHashMap<CanonicalBasemapTile, AcquiredDemTile>(requested.size)
        requested.forEach { tile ->
            val dem = byRequestedTile[host.engineTileIdOf(tile)] ?: return@forEach
            acquiredDemTileOf(dem, tileSizePx)?.let { matched[tile] = it }
        }
        return matched
    }

    private fun sha256Hex(value: String): String =
        sha256.digest(CanonicalBytes(value.encodeToByteArray())).lowercaseHex
}

/**
 * The engine's terrain descriptor in RenG's own vocabulary, paired with the style's own source id.
 *
 * [TerrainSourceDescriptor.sourceId] is deliberately dropped: it is `sha256Hex(styleSourceId)`, an
 * identity in Rentile's namespace whose one job -- proving that RenG and the engine read the same
 * `terrain.source` -- is discharged by [TerrainAcquisition.terrainSource] before this is built. Carrying
 * it onward would only invite a second comparison against the wrong string.
 */
private fun terrainSourceOf(descriptor: TerrainSourceDescriptor, styleSourceId: String): TerrainSource =
    TerrainSource(
        styleSourceId = styleSourceId,
        encoding = demEncodingOf(descriptor.encoding),
        tileSizePx = descriptor.tileSizePx,
        minimumZoom = descriptor.minimumZoom,
        maximumZoom = descriptor.maximumZoom,
    )

/**
 * One engine DEM result in RenG's own vocabulary, or `null` when its texels are not the square the
 * style's own `TerrainSourceDescriptor` declared -- see [TerrainAcquisition.matchDemTiles].
 *
 * `dem.texels` is the engine's `DemTexels`: tightly packed RGBA8, four bytes a texel in R, G, B, A
 * order, rows top-down from the tile's north edge, never premultiplied and with no colour-space
 * conversion. That is byte-for-byte RenG's own canonical decoded form, which is why
 * [com.rohittp.reng.internal.terrain.demTexelsOf] copies rather than converts.
 */
private fun acquiredDemTileOf(dem: ValidatedDemTile, tileSizePx: Int): AcquiredDemTile? {
    val texels = demTexelsOf(
        width = dem.texels.width,
        height = dem.texels.height,
        rgba = dem.texels.rgba,
        tileSizePx = tileSizePx,
        contentDigest = dem.contentDigest,
    ) ?: return null
    return AcquiredDemTile(
        requestedTile = demTileCoordinateOf(dem.requestedTile),
        sourceTile = demTileCoordinateOf(dem.sourceTile),
        encoding = demEncodingOf(dem.encoding),
        texels = texels,
    )
}

/**
 * `TerrainDemEncoding` to [DemEncoding], in a `when` with no `else` and over Rentile's enum rather than
 * RenG's.
 *
 * That direction is the whole point, and it is [classifyEngineFailure]'s rule applied to a second engine
 * enum: a third packing in a future Rentile fails **this file's compilation** instead of being quietly
 * swept onto `MAPBOX`, where it would decode every height wrong and never say so.
 */
private fun demEncodingOf(encoding: TerrainDemEncoding): DemEncoding = when (encoding) {
    TerrainDemEncoding.MAPBOX -> DemEncoding.MAPBOX
    TerrainDemEncoding.TERRARIUM -> DemEncoding.TERRARIUM
}

/**
 * Rentile's `TileId` to [DemTileCoordinate], the inbound half of the mapping
 * [BasemapEngineHost.engineTileIdOf] performs outbound.
 *
 * `x` is copied rather than canonicalised, deliberately: a world-wrapped request is one of the two cases
 * that make `requestedTile` and `sourceTile` differ at all, and `demTileWindowFor` canonicalises it
 * itself while checking the ancestor agreement. Canonicalising here would erase the input that check is
 * made against.
 */
private fun demTileCoordinateOf(tile: TileId): DemTileCoordinate =
    DemTileCoordinate(z = tile.z, x = tile.x, y = tile.y)

/**
 * The visible set followed by its one-tile perimeter ring: every tile 8-adjacent to a visible tile that is
 * not itself visible.
 *
 * **The corners are in it deliberately.** A padded DEM border needs its four corner texels as much as its
 * four edges, and the diagonal neighbour is the only tile that carries them. `4*sqrt(T) + 4` is the count
 * for a square block, which is what design section 4 sizes the cost from.
 *
 * **`y` is clipped and `x` wraps**, exactly as Rentile's own `RasterSample.neighbor` does it: a neighbour
 * off the top or bottom of the world does not exist, while the world is a cylinder in `x`. The clip is not
 * cosmetic -- Rentile's `validateTile` throws `InvalidTileIdException` for a `y` outside `0 until 2^z`, and
 * under `throwAcquisitionFailures` that one tile would fail the entire frame's terrain.
 *
 * **This widens the acquisition without widening preregistration.** `tileTimeRoutes` already declares the
 * 3x3 neighbourhood of every visible tile for every `raster-dem` source, and every tile below is a member
 * of one of those neighbourhoods. That still holds under overzoom: `sampleFor` divides a canonical `x` and
 * `y` by one `childScale` for the whole frame, so a request that moves by one tile moves the source sample
 * by at most one in each axis, and `x` wraps on both sides by `floorMod`.
 *
 * Each `lod` is expanded against its own world dimension, so a mixed-LOD list stays correct even though the
 * ground selects one LOD per frame. Order is deterministic: the visible tiles as given, then the ring in
 * discovery order.
 */
internal fun terrainTileRequest(visibleTiles: List<CanonicalBasemapTile>): List<CanonicalBasemapTile> {
    if (visibleTiles.isEmpty()) return emptyList()
    val visible = visibleTiles.distinct()
    val seen = LinkedHashSet(visible)
    val ring = ArrayList<CanonicalBasemapTile>()
    visible.forEach { tile ->
        val dimension = 1L shl tile.lod
        for (deltaY in -1..1) {
            val neighbourY = tile.tileY.toLong() + deltaY
            if (neighbourY !in 0 until dimension) continue
            for (deltaX in -1..1) {
                val neighbour = CanonicalBasemapTile(
                    lod = tile.lod,
                    tileY = neighbourY.toInt(),
                    canonicalX = floorMod(tile.canonicalX.toLong() + deltaX, dimension).toInt(),
                )
                if (seen.add(neighbour)) ring += neighbour
            }
        }
    }
    return visible + ring
}

/**
 * The style's terrain source in RenG's own vocabulary: what the engine's `TerrainSourceDescriptor` says,
 * with its encoding translated and its digest identity dropped.
 *
 * [minimumZoom] and [maximumZoom] are the *source's* zoom range, not the frame's. The first is one of the
 * two reasons a requested tile silently vanishes from an acquisition (ADR 0041); the second is what makes
 * a request an overzoom of an ancestor, which is what
 * [com.rohittp.reng.internal.terrain.demTileWindowFor] exists to resolve. [tileSizePx] is the size the
 * style declares rather than the size the image turns out to be -- Rentile never compares the two, which
 * is why [TerrainAcquisition.matchDemTiles] does, on the way past.
 */
internal class TerrainSource(
    /** The style's own `terrain.source` id, un-hashed. */
    val styleSourceId: String,
    val encoding: DemEncoding,
    val tileSizePx: Int,
    val minimumZoom: Int,
    val maximumZoom: Int,
) {
    override fun toString(): String =
        "TerrainSource(source=$styleSourceId, encoding=$encoding, tileSizePx=$tileSizePx, " +
            "zoom=$minimumZoom..$maximumZoom)"
}

/**
 * One acquired DEM tile: already decoded by the engine, already in RenG's vocabulary, and already
 * agreed with the size the style declared.
 *
 * [requestedTile] and [sourceTile] differ exactly when the request overzooms the source or wraps the
 * world, and the pair is what [com.rohittp.reng.internal.terrain.demTileWindowFor] turns into the
 * sub-rectangle to sample.
 *
 * **The engine's encoded bytes are deliberately not here.** Their only consumer was RenG's own PNG
 * decode, which `0.7.0` removed the need for; keeping them would retain a megabyte a tile, a hundred
 * tiles a frame, for a decode that no longer happens. Rentile still holds them as the value to hash
 * for cache identity, and [DemTexels.contentDigest] is that identity carried onward.
 */
internal class AcquiredDemTile(
    val requestedTile: DemTileCoordinate,
    val sourceTile: DemTileCoordinate,
    val encoding: DemEncoding,
    val texels: DemTexels,
) {
    /** The tile's edge length rather than its texels: a megabyte of DEM payload is not a `toString`. */
    override fun toString(): String =
        "AcquiredDemTile(requested=$requestedTile, source=$sourceTile, encoding=$encoding, " +
            "sizePx=${texels.image.width})"
}

/** Which `terrain` source this frame has, or why it has none. */
internal sealed interface TerrainSourceOutcome {

    /** The style declares no `terrain` block. Flat ground is exactly what it asked for. */
    data object NotDeclared : TerrainSourceOutcome

    /** RenG and the engine name the same source, and [source] states it in RenG's own vocabulary. */
    class Declared(val source: TerrainSource) : TerrainSourceOutcome

    /** They do not name the same source -- or only one of them names one at all. */
    data object Disagreed : TerrainSourceOutcome
}

/** Why a frame's ground draws flat although its style asked for terrain (ADR 0041). */
internal enum class TerrainDegradationReason {

    /** `acquireTerrainTiles` threw: one failing DEM tile fails the whole call, and RenG does not retry. */
    ACQUISITION_FAILED,

    /** RenG's manifest and the engine's compiled style do not name the same `terrain` source. */
    SOURCE_DISAGREEMENT,
}

/** What one frame's terrain acquisition produced. */
internal sealed interface TerrainAcquisitionOutcome {

    /** No `terrain` in the style. Not a degradation and nothing to report. */
    data object NotDeclared : TerrainAcquisitionOutcome

    /**
     * DEM tiles for some -- possibly none, possibly all -- of what was requested.
     *
     * [absentTiles] is what Rentile silently dropped, and it is the count ADR 0041's
     * `TERRAIN_COVERAGE_INCOMPLETE` reports once per frame. A non-empty [absentTiles] is not a failure:
     * those tiles draw flat and the rest displace.
     */
    class Acquired(
        val source: TerrainSource,
        requestedTiles: List<CanonicalBasemapTile>,
        demTiles: Map<CanonicalBasemapTile, AcquiredDemTile>,
    ) : TerrainAcquisitionOutcome {
        private val requestedSnapshot: List<CanonicalBasemapTile> = ArrayList(requestedTiles)
        private val demSnapshot: Map<CanonicalBasemapTile, AcquiredDemTile> = LinkedHashMap(demTiles)

        /** The visible set and its perimeter ring, in the order they were asked for. */
        val requestedTiles: List<CanonicalBasemapTile> get() = ArrayList(requestedSnapshot)

        /** Requested tiles Rentile returned nothing for, in request order. */
        val absentTiles: List<CanonicalBasemapTile>
            get() = requestedSnapshot.filter { it !in demSnapshot }

        val acquiredTileCount: Int get() = demSnapshot.size

        fun demTileFor(tile: CanonicalBasemapTile): AcquiredDemTile? = demSnapshot[tile]

        override fun toString(): String =
            "Acquired(requested=${requestedSnapshot.size}, acquired=${demSnapshot.size})"
    }

    /**
     * The whole ground draws flat, and ADR 0041's `TERRAIN_UNAVAILABLE` says so.
     *
     * [failure] is the sanitized RenG failure the firewall already produced, carried rather than re-read:
     * it names a code, a stage and at most a redacted resource identity, and never an engine message or
     * cause. It is `null` for [TerrainDegradationReason.SOURCE_DISAGREEMENT], which is RenG's own
     * disagreement with itself rather than anything the engine reported.
     */
    class Degraded(
        val reason: TerrainDegradationReason,
        val failure: RenGException?,
    ) : TerrainAcquisitionOutcome {
        override fun toString(): String = "Degraded(reason=$reason, code=${failure?.code}, stage=${failure?.stage})"
    }
}

/** `x` wraps around the world; Kotlin's `%` does not, and a negative remainder is not a tile. */
private fun floorMod(value: Long, divisor: Long): Long {
    val remainder = value % divisor
    return if (remainder < 0L) remainder + divisor else remainder
}
