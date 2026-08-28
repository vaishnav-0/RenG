package com.rohittp.reng.internal

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits

internal fun canonicalDouble(value: Double, field: String): Double {
    val finiteValue = requireFinite(value, field)
    return if (finiteValue == 0.0) 0.0 else finiteValue
}

internal fun requireFinite(value: Double, field: String): Double {
    require(value.isFinite()) { "$field must be finite" }
    return value
}

internal fun canonicalFloat(value: Float, field: String): Float {
    val finiteValue = requireFiniteFloat(value, field)
    return if (finiteValue == 0.0f) 0.0f else finiteValue
}

internal fun requireFiniteFloat(value: Float, field: String): Float {
    require(value.isFinite()) { "$field must be finite" }
    return value
}

internal fun containsOnlyUnicodeScalars(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val unit = value[index]
        if (unit.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index += 2
            continue
        }
        if (unit.isLowSurrogate()) return false
        index += 1
    }
    return true
}

internal fun requireUnicodeScalars(value: String, field: String, nonBlank: Boolean): String {
    require(!nonBlank || value.isNotBlank()) { "$field must not be blank" }
    require(containsOnlyUnicodeScalars(value)) { "$field must contain Unicode scalar values" }
    return value
}

internal fun <T> freshListCopy(values: List<T>): List<T> = ArrayList(values)

internal fun ByteArray.freshCopy(): ByteArray = copyOf()

/**
 * The route ceiling for a single [ResourceClass.BASEMAP_GLYPH_RANGE] response, deliberately **not** a
 * twelfth [ResourceLimits] field.
 *
 * A route cannot be constructed without a `maximumResponseBytes`
 * ([com.rohittp.reng.internal.resource.ResourceRouteKey]'s `init` requires it positive) and the number is
 * part of route identity, so two disagreeing preregistrations of one glyph url fail the frame closed. It
 * still does not want a public knob: the ceiling a consumer would actually turn is the engine's own
 * `maxGlyphRangeBytes`, which defaults to 1 MiB and is what refuses an oversized range. RenG's number is
 * the outer bound that keeps RenG from *being* the binding constraint on a range Rentile would have
 * accepted -- four times the engine's default, so the engine's refusal is the operative one at its
 * default and at any modest raise of it.
 *
 * Reusing `maximumBasemapMetadataBytes` (also 4 MiB) was rejected on the sameness test the one
 * deliberate class reuse in this codebase states for itself (`FramePlanningCore`'s geometry-texture
 * comment, which reuses `MODEL_TEXTURE` because a geometry texture is *the same kind of resource*): a
 * glyph range shares no kind with a JSON metadata document -- different media type, different producer,
 * different failure mode -- and F-2's `decodedCpuBytes` is the standing debt that reusing a limit under
 * sizing pressure creates.
 */
internal const val GLYPH_RANGE_ROUTE_CEILING_BYTES: Long = 4L * 1024L * 1024L

internal fun ResourceLimits.maximumBytesFor(resourceClass: ResourceClass): Long =
    when (resourceClass) {
        ResourceClass.BASEMAP_STYLE -> maximumBasemapStyleBytes
        ResourceClass.BASEMAP_TILE_JSON,
        ResourceClass.BASEMAP_SPRITE_JSON,
        -> maximumBasemapMetadataBytes
        ResourceClass.BASEMAP_VECTOR_TILE,
        ResourceClass.BASEMAP_RASTER_TILE,
        ResourceClass.BASEMAP_DEM_TILE,
        -> maximumBasemapTileBytes
        ResourceClass.BASEMAP_SPRITE_IMAGE -> maximumBasemapSpriteImageBytes
        ResourceClass.BASEMAP_GEO_JSON -> maximumBasemapGeoJsonBytes
        ResourceClass.STICKER_IMAGE -> maximumStickerImageBytes
        ResourceClass.MODEL_GLB -> maximumModelGlbBytes
        ResourceClass.MODEL_TEXTURE -> maximumModelTextureBytes
        ResourceClass.BASEMAP_GLYPH_RANGE -> GLYPH_RANGE_ROUTE_CEILING_BYTES
    }

internal val ResourceClass.acceptValue: String
    get() = when (this) {
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.BASEMAP_TILE_JSON,
        ResourceClass.BASEMAP_SPRITE_JSON,
        ResourceClass.BASEMAP_GEO_JSON,
        -> "application/json"
        ResourceClass.BASEMAP_VECTOR_TILE -> "application/vnd.mapbox-vector-tile"
        ResourceClass.BASEMAP_RASTER_TILE,
        ResourceClass.BASEMAP_DEM_TILE,
        ResourceClass.BASEMAP_SPRITE_IMAGE,
        ResourceClass.STICKER_IMAGE,
        ResourceClass.MODEL_TEXTURE,
        -> "image/png"
        ResourceClass.MODEL_GLB -> "model/gltf-binary"
        ResourceClass.BASEMAP_GLYPH_RANGE -> "application/x-protobuf"
    }

internal val ResourceClass.reportOrder: Int
    get() = when (this) {
        ResourceClass.BASEMAP_STYLE -> 0
        ResourceClass.BASEMAP_TILE_JSON -> 1
        ResourceClass.BASEMAP_VECTOR_TILE -> 2
        ResourceClass.BASEMAP_RASTER_TILE -> 3
        ResourceClass.BASEMAP_DEM_TILE -> 4
        ResourceClass.BASEMAP_SPRITE_JSON -> 5
        ResourceClass.BASEMAP_SPRITE_IMAGE -> 6
        ResourceClass.BASEMAP_GEO_JSON -> 7
        ResourceClass.STICKER_IMAGE -> 8
        ResourceClass.MODEL_GLB -> 9
        ResourceClass.MODEL_TEXTURE -> 10
        ResourceClass.BASEMAP_GLYPH_RANGE -> 11
    }

internal val ResourceKind.reportOrder: Int
    get() = when (this) {
        ResourceKind.EXTERNAL -> 0
        ResourceKind.GEOMETRY_PROGRAM -> 1
        ResourceKind.INTERNAL_PIPELINE -> 2
        ResourceKind.OFFSCREEN_SURFACE -> 3
        ResourceKind.BASEMAP_TILE -> 4
        ResourceKind.MODEL_GEOMETRY -> 5
        ResourceKind.MODEL_IMAGE -> 6
    }
