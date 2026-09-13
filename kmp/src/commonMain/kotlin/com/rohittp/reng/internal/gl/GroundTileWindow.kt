package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.planning.CanonicalBasemapTile

/**
 * The sub-rectangle of an ancestor tile that one tile occupies, as `uv * scale + offset` (ADR 0057).
 *
 * The identity window — scale 1, offset 0 — is what a tile drawn from its own texture gets, so the
 * ground shaders apply this on every tile and carry no branch and no second variant.
 *
 * Every value here is exact in binary floating point: [scale] is `1 / 2^k` and each offset is an
 * integer over the same power of two, so no tolerance appears anywhere in this file or the shaders
 * that read it.
 */
internal class GroundTileWindow(
    val scale: Float,
    val offsetU: Float,
    val offsetV: Float,
) {
    override fun equals(other: Any?): Boolean =
        other is GroundTileWindow &&
            scale == other.scale &&
            offsetU == other.offsetU &&
            offsetV == other.offsetV

    override fun hashCode(): Int = (31 * (31 * scale.hashCode() + offsetU.hashCode())) + offsetV.hashCode()

    override fun toString(): String = "GroundTileWindow(scale=$scale, offsetU=$offsetU, offsetV=$offsetV)"

    companion object {
        /** A tile drawn from its own texture: the whole of it. */
        val WHOLE: GroundTileWindow = GroundTileWindow(scale = 1.0f, offsetU = 0.0f, offsetV = 0.0f)
    }
}

/**
 * The tile [levels] levels above [tile], or `null` when there is none.
 *
 * `CanonicalBasemapTile` is `(lod, tileY, canonicalX)`, so an ancestor is a shift in each axis — the
 * same arithmetic `DemTileWindow` already performs for an ancestor DEM, which is why neither needs a
 * projection or a world-wrap correction here: a canonical x is already wrapped.
 */
internal fun ancestorOf(tile: CanonicalBasemapTile, levels: Int): CanonicalBasemapTile? {
    require(levels > 0) { "an ancestor is at least one level up" }
    if (levels > tile.lod) return null
    return CanonicalBasemapTile(
        lod = tile.lod - levels,
        tileY = tile.tileY shr levels,
        canonicalX = tile.canonicalX shr levels,
    )
}

/**
 * Where [tile] sits inside its ancestor [levels] levels up.
 *
 * The low [levels] bits of each axis are the tile's position within that ancestor, and the scale is
 * `1 / 2^levels`. Both are exact: a power of two and an integer multiple of it.
 */
internal fun windowWithin(tile: CanonicalBasemapTile, levels: Int): GroundTileWindow {
    require(levels > 0) { "an ancestor is at least one level up" }
    val span = 1 shl levels
    val mask = span - 1
    val scale = 1.0f / span.toFloat()
    return GroundTileWindow(
        scale = scale,
        offsetU = (tile.canonicalX and mask).toFloat() * scale,
        offsetV = (tile.tileY and mask).toFloat() * scale,
    )
}
