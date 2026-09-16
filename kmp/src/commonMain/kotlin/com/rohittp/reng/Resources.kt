package com.rohittp.reng

import com.rohittp.reng.internal.requireUnicodeScalars

@kotlinx.serialization.Serializable
public enum class AnchoringMode {
    @kotlinx.serialization.SerialName("MAP")
    MAP,
    @kotlinx.serialization.SerialName("SCREEN")
    SCREEN,
}

@kotlinx.serialization.Serializable(with = ResourceLocatorSerializer::class)
public class ResourceLocator(value: String) {
    public val value: String

    init {
        val validatedValue = requireUnicodeScalars(value, "resourceLocator", nonBlank = true)
        this.value = validatedValue
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceLocator && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ResourceLocator(<redacted>)"
}

public data class OutputPixelSize(
    public val width: Int,
    public val height: Int,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(width.toLong() * height.toLong() <= Int.MAX_VALUE.toLong()) {
            "output pixel count exceeds the maximum"
        }
    }
}

public enum class ResourceClass {
    BASEMAP_STYLE,
    BASEMAP_TILE_JSON,
    BASEMAP_VECTOR_TILE,
    BASEMAP_RASTER_TILE,
    BASEMAP_DEM_TILE,
    BASEMAP_SPRITE_JSON,
    BASEMAP_SPRITE_IMAGE,
    BASEMAP_GEO_JSON,
    STICKER_IMAGE,
    MODEL_GLB,
    MODEL_TEXTURE,

    /**
     * One 256-codepoint block of one font stack, as fontnik/Mapbox protobuf, fetched by the Rentile
     * engine's glyph acquirer while it resolves label candidates. Appended after the model classes
     * rather than grouped with the other `BASEMAP_` constants so that no existing constant's ordinal,
     * wire value or report order moves: both of the latter are explicit tables that a reordering would
     * silently renumber.
     */
    BASEMAP_GLYPH_RANGE,

    /**
     * One repeating image a frame paints behind everything it draws (ADR 0068). Appended after
     * [BASEMAP_GLYPH_RANGE] for the reason that constant's own note gives -- no existing ordinal,
     * wire value or report-order entry moves, and both of the latter are explicit tables a
     * reordering would silently renumber.
     *
     * It carries a sticker image's byte ceiling, accept value and `DECODE_PNG` gate, because it is
     * the same kind of bytes. What it deliberately does not share is `STICKER_IMAGE`'s *list*:
     * `RenGRenderer` pairs that class's references with `FramePlan.stickers` by index, and a
     * backdrop among them is an off-by-one in every sticker's texture.
     */
    BACKDROP_IMAGE,
}

public enum class ResourceKind {
    EXTERNAL,
    GEOMETRY_PROGRAM,
    INTERNAL_PIPELINE,
    OFFSCREEN_SURFACE,
    BASEMAP_TILE,
    MODEL_GEOMETRY,
    MODEL_IMAGE,
}

public enum class ResourceAccessMode {
    NORMAL,
    CACHE_ONLY,
    RELOAD,
}

public data class ResourceLimits(
    public val maximumBasemapStyleBytes: Long = 8L * 1024L * 1024L,
    public val maximumBasemapMetadataBytes: Long = 4L * 1024L * 1024L,
    public val maximumBasemapTileBytes: Long = 32L * 1024L * 1024L,
    public val maximumBasemapSpriteImageBytes: Long = 32L * 1024L * 1024L,
    public val maximumBasemapGeoJsonBytes: Long = 64L * 1024L * 1024L,
    public val maximumStickerImageBytes: Long = 32L * 1024L * 1024L,
    public val maximumModelGlbBytes: Long = 256L * 1024L * 1024L,
    public val maximumModelTextureBytes: Long = 32L * 1024L * 1024L,
    /**
     * The decoded-pixel ceiling every image RenG expands is measured against. `decodePng` enforces it
     * as `pixelCount > maximumDecodedImageBytes / 4`, so this value divided by four is a pixel count.
     *
     * **Raised from 64 MiB to 256 MiB by Cycle E-labels, because the engine packs glyph atlases this
     * decoder would otherwise refuse.** Rentile's worst recorded case is 159 glyph ranges in one plan
     * at 8192x4357 -- 35,684,864 pixels, about 136 MiB -- where the old ceiling admitted roughly 73
     * ranges and a denser multilingual viewport failed the whole frame `RESOURCE_DECODE_FAILED`
     * rather than degrading. 256 MiB clears that worst case by 1.88x.
     *
     * Still **shared** with every raster RenG decodes -- sticker images, model textures, basemap
     * tiles -- which is Cycle F-2's recorded debt rather than a decision taken here: a consumer
     * tightening this for rasters can still starve the atlas.
     */
    public val maximumDecodedImageBytes: Long = 256L * 1024L * 1024L,
    public val maximumModelJsonChunkBytes: Long = 16L * 1024L * 1024L,
    /**
     * The byte budget for resident GPU texture memory: everything an unleased upload -- for
     * example a basemap tile with no live [com.rohittp.reng.internal.gl.GlObjectRegistry] lease
     * left on it -- may keep occupying on the GPU before least-recently-used eviction reclaims it.
     * A texture still leased by a live Prepared Frame is never evicted, even past this budget: this
     * bounds what MAY stay resident, never what MUST. Deliberately independent of the Tile Budget
     * (`maximumBasemapTileInstances`) -- a tile count means a different number of bytes at every tile
     * size and on every device, so deriving one from the other would hide the real cost.
     *
     * The two *defaults* are nonetheless sized to agree: 512 canonical 512x512 RGBA8 tiles are
     * exactly 512 MiB, so a frame at the default tile ceiling cannot thrash against the default
     * budget. They sat 4x apart until a level 3840x2160 camera was measured reaching 167 tiles. This
     * is an eviction threshold rather than a reservation, so the larger default costs nothing until a
     * frame needs it.
     */
    public val maximumResidentGpuTextureBytes: Long = 512L * 1024L * 1024L,
    /**
     * How many bytes of **raw, not-yet-uploaded** basemap tile pixels every open Prepared Frame may
     * hold between them before a preparation stops asking Rentile for pixels and asks for PNGs
     * instead (ADR 0044).
     *
     * A prepared frame retains its tile bytes until it is drawn, because `prepare()` cannot upload:
     * it may resume off the thread holding the render context. Raw is roughly ten times encoded -- a
     * 512x512 tile is 1 MiB of RGBA8 against about 100 KB as a PNG -- so skipping the encode trades
     * CPU for a residency the *caller* sizes, by choosing how many frames it prepares before drawing.
     *
     * Above this the ground path takes the encode and the decode, which is what every release before
     * ADR 0044 did: the fallback is the old behaviour reached deliberately, not a degraded mode.
     *
     * The default is 64 tiles at the default 512 px output -- more than any single frame's ground
     * plane, far less than a batch's worth.
     *
     * **Declared last, and that is an ABI decision.** Every field before it is positional in shipped
     * consumer code; appending one with a default leaves all of it compiling, where inserting one
     * would silently re-bind every positional construction.
     */
    public val maximumInFlightRawBasemapTileBytes: Long = 64L * 1024L * 1024L,
    /**
     * How many bytes of **encoded, unleased** consumer resources may stay resident on the CPU before
     * least-recently-used eviction reclaims them (ADR 0047).
     *
     * Bounds what `acquireFrameResources` installs: the compiled style document, every sticker and
     * geometry-consumer image, every model GLB. **Not** rendered basemap tiles, which are never
     * installed in that cache -- those are bounded by [maximumResidentGpuTextureBytes] and, in
     * flight, by [maximumInFlightRawBasemapTileBytes].
     *
     * A resource still leased by a live Prepared Frame is never evicted, even past this budget: this
     * bounds what **may** stay resident, never what **must**.
     *
     * The default sits below [maximumDecodedImageBytes], a per-image ceiling rather than a total,
     * and below the GPU budget: these are encoded bytes, and a consumer holding more than this in
     * resources nothing is drawing is holding something it is not using.
     *
     * Declared last, for the ABI reason [maximumInFlightRawBasemapTileBytes] already gives.
     */
    public val maximumResidentCpuResourceBytes: Long = 128L * 1024L * 1024L,
    /**
     * How many basemap tiles one `prepare()` may rasterise before it starts drawing the rest from
     * resident ancestors instead (ADR 0057).
     *
     * **Defaults to no limit, and with no limit nothing changes**: every frame rasterises every tile
     * it selected, as every release before this one did. The trade it offers -- a frame sooner, at a
     * lower resolution, for a few frames -- is a decision about an application's feel, and RenG is
     * not in a position to make it for anyone.
     *
     * A tile with no resident ancestor is rasterised whatever this says: the budget bounds the work a
     * frame **adds** and can never make it draw nothing.
     *
     * Refinement needs no background work: a tile this frame skipped is still not resident, so the
     * next `prepare()` selects and rasterises it under that frame's own budget. An animation loop
     * gets coarse ground immediately and sharper over the next few frames; a consumer preparing one
     * still should leave this unset.
     *
     * `Int.MAX_VALUE` means unlimited and is the default. Zero is admissible and means "rasterise
     * nothing that has an ancestor", which is the most aggressive form of the same trade.
     *
     * Declared last, for the ABI reason [maximumInFlightRawBasemapTileBytes] already gives.
     */
    public val maximumTilesRasterisedPerFrame: Int = Int.MAX_VALUE,
) {
    init {
        val minimum = 1L
        val maximum = Int.MAX_VALUE.toLong()
        require(maximumBasemapStyleBytes in minimum..maximum) {
            "maximumBasemapStyleBytes must be within the supported range"
        }
        require(maximumBasemapMetadataBytes in minimum..maximum) {
            "maximumBasemapMetadataBytes must be within the supported range"
        }
        require(maximumBasemapTileBytes in minimum..maximum) {
            "maximumBasemapTileBytes must be within the supported range"
        }
        require(maximumBasemapSpriteImageBytes in minimum..maximum) {
            "maximumBasemapSpriteImageBytes must be within the supported range"
        }
        require(maximumBasemapGeoJsonBytes in minimum..maximum) {
            "maximumBasemapGeoJsonBytes must be within the supported range"
        }
        require(maximumStickerImageBytes in minimum..maximum) {
            "maximumStickerImageBytes must be within the supported range"
        }
        require(maximumModelGlbBytes in minimum..maximum) {
            "maximumModelGlbBytes must be within the supported range"
        }
        require(maximumModelTextureBytes in minimum..maximum) {
            "maximumModelTextureBytes must be within the supported range"
        }
        require(maximumDecodedImageBytes in minimum..maximum) {
            "maximumDecodedImageBytes must be within the supported range"
        }
        require(maximumModelJsonChunkBytes in minimum..maximum) {
            "maximumModelJsonChunkBytes must be within the supported range"
        }
        require(maximumResidentGpuTextureBytes in minimum..maximum) {
            "maximumResidentGpuTextureBytes must be within the supported range"
        }
        // Zero is admissible here and nowhere else on this type: it is how a consumer says "never
        // hold raw pixels", which is a coherent choice (every preparation takes the PNG path) and
        // not the degenerate "no budget at all" that a zero means for every other field above.
        require(maximumInFlightRawBasemapTileBytes in 0L..maximum) {
            "maximumInFlightRawBasemapTileBytes must be within the supported range"
        }
        // Zero is admissible for the same reason it is above: it says "keep nothing unleased", which
        // is coherent -- every resource is evicted the moment its last frame closes.
        require(maximumResidentCpuResourceBytes in 0L..maximum) {
            "maximumResidentCpuResourceBytes must be within the supported range"
        }
        // Zero is admissible for the same reason the two byte budgets above admit it: it is a
        // coherent choice ("rasterise nothing that has an ancestor"), not the degenerate absence of
        // a budget.
        require(maximumTilesRasterisedPerFrame >= 0) {
            "maximumTilesRasterisedPerFrame must not be negative"
        }
    }
}
