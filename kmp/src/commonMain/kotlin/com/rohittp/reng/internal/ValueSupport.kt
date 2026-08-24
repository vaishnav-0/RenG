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
