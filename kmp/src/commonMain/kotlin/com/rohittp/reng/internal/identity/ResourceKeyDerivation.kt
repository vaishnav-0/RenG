package com.rohittp.reng.internal.identity

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.gl.InternalPipelineRole
import com.rohittp.reng.internal.gl.OffscreenSurfaceDescriptor
import com.rohittp.reng.internal.planning.CanonicalBasemapTile

internal data class DerivedResourceKey(
    val key: ResourceKey,
    val rawKey: RawResourceKey?,
    val identity: HashedCanonicalBytes,
)

internal class ResourceKeyDeriver(
    private val sha256: Sha256Function = PureKotlinSha256,
) {
    internal fun external(
        resourceClass: ResourceClass,
        locator: ResourceLocator,
    ): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.EXTERNAL_RESOURCE) {
                field(1, CanonicalBinary.u16(ResourceKind.EXTERNAL.wireValue))
                field(2, CanonicalBinary.u16(resourceClass.wireValue))
                field(3, CanonicalBinary.exactUtf8(locator.value))
            },
        )
        val stableId = identity.digest.lowercaseHex
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = stableId,
                resourceClass = resourceClass,
            ),
            rawKey = RawResourceKey(
                stableId = stableId,
                resourceClass = resourceClass,
            ),
            identity = identity,
        )
    }

    internal fun geometryProgram(shaderPair: ShaderPair): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.GEOMETRY_PROGRAM) {
                field(1, CanonicalBinary.u16(ResourceKind.GEOMETRY_PROGRAM.wireValue))
                field(2, CanonicalBinary.u16(GEOMETRY_SHADER_PROFILE_WIRE_VALUE))
                field(3, CanonicalBinary.exactUtf8(shaderPair.vertexSource))
                field(4, CanonicalBinary.exactUtf8(shaderPair.fragmentSource))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.GEOMETRY_PROGRAM,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    internal fun internalPipeline(
        role: InternalPipelineRole,
        shaderPair: ShaderPair,
    ): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.INTERNAL_PIPELINE) {
                field(1, CanonicalBinary.u16(ResourceKind.INTERNAL_PIPELINE.wireValue))
                field(2, CanonicalBinary.u16(role.wireValue))
                field(3, CanonicalBinary.u16(GEOMETRY_SHADER_PROFILE_WIRE_VALUE))
                field(4, CanonicalBinary.exactUtf8(shaderPair.vertexSource))
                field(5, CanonicalBinary.exactUtf8(shaderPair.fragmentSource))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.INTERNAL_PIPELINE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    internal fun offscreenSurface(descriptor: OffscreenSurfaceDescriptor): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.OFFSCREEN_SURFACE) {
                field(1, CanonicalBinary.u16(ResourceKind.OFFSCREEN_SURFACE.wireValue))
                field(2, CanonicalBinary.u64(descriptor.widthPixels.toLong()))
                field(3, CanonicalBinary.u64(descriptor.heightPixels.toLong()))
                field(4, CanonicalBinary.u16(descriptor.colourFormat.wireValue))
                field(5, CanonicalBinary.u16(descriptor.depthFormat.wireValue))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.OFFSCREEN_SURFACE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    /**
     * The identity of one **rendered** basemap tile: the PNG a Rentile engine draws for one canonical
     * tile of one prepared style at one output size. Deliberately RenG's own canonical root (ADR 0018)
     * and never `BasemapRasterizer.outputRequestKey`, which is convenient, available at the same
     * boundary, and wrong to index with: it is Rentile's key, governed by Rentile's derivation rules, so
     * an engine release that changed it would silently invalidate RenG's entire rendered-tile cache with
     * no signal at either boundary. Rentile's own `contentKey` and substitution provenance are worth
     * storing beside the bytes, as Rentile's KDoc advises -- just never as the index.
     *
     * Keyed on a [CanonicalBasemapTile] rather than a [com.rohittp.reng.internal.planning.BasemapTileInstance]
     * because `BasemapTileSelector` already emits `canonicalResources` separately from `instances`: a
     * canonical tile is post-world-copy-dedup by construction (CONTEXT.md's **Basemap Tile**), so N
     * unwrapped draw instances of one tile share exactly one rendered-tile resource and one engine
     * render. Keying on `unwrappedX` or `instanceCopy` instead would re-render the same ground once per
     * visible world copy.
     *
     * [styleDigest] is `PreparedStyle.digest` -- the engine's own compiled-style identity, which is a
     * *content* input here rather than an identity namespace: two different styles must not share a
     * rendered tile. [outputSize] is the tile's own rendered pixel size, not the frame's.
     */
    internal fun basemapTile(
        styleDigest: String,
        tile: CanonicalBasemapTile,
        outputSize: OutputPixelSize,
    ): DerivedResourceKey {
        require(tile.lod >= 0 && tile.tileY >= 0 && tile.canonicalX >= 0) {
            "a canonical basemap tile has non-negative coordinates"
        }
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.BASEMAP_TILE) {
                field(1, CanonicalBinary.u16(ResourceKind.BASEMAP_TILE.wireValue))
                field(2, CanonicalBinary.exactUtf8(styleDigest))
                field(3, CanonicalBinary.u64(tile.lod.toLong()))
                field(4, CanonicalBinary.u64(tile.tileY.toLong()))
                field(5, CanonicalBinary.u64(tile.canonicalX.toLong()))
                field(6, CanonicalBinary.u64(outputSize.width.toLong()))
                field(7, CanonicalBinary.u64(outputSize.height.toLong()))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.BASEMAP_TILE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    /**
     * The identity of one **glTF primitive's** GPU geometry (vertex/index buffers) inside one GLB: the
     * mesh-and-primitive pair a parsed document names, scoped to the model that owns it. There is no
     * [ResourceLocator] here -- a primitive is not independently fetchable, it only exists nested inside
     * an already-resolved GLB -- so this can never call [external], and the resulting [ResourceKey]
     * carries a null [ResourceClass] exactly as [geometryProgram] and [basemapTile] do.
     *
     * [modelKey] namespaces the derivation the way [basemapTile] is namespaced by `styleDigest`: it must
     * be the model's own `EXTERNAL`/[ResourceClass.MODEL_GLB] key, fed in via its `stableId` rather than
     * re-deriving anything from the GLB bytes here. Two glTF documents that happen to declare an
     * identical mesh at the same index therefore still key to different geometries, because they hang
     * off different model keys; the same model's two primitives differ because [meshIndex] or
     * [primitiveIndex] differs.
     */
    internal fun modelGeometry(
        modelKey: ResourceKey,
        meshIndex: Int,
        primitiveIndex: Int,
    ): DerivedResourceKey {
        require(modelKey.kind == ResourceKind.EXTERNAL && modelKey.resourceClass == ResourceClass.MODEL_GLB) {
            "model geometry derivation requires the model's external GLB resource key"
        }
        require(meshIndex >= 0) { "meshIndex must be non-negative" }
        require(primitiveIndex >= 0) { "primitiveIndex must be non-negative" }

        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.MODEL_GEOMETRY) {
                field(1, CanonicalBinary.u16(ResourceKind.MODEL_GEOMETRY.wireValue))
                field(2, CanonicalBinary.exactUtf8(modelKey.stableId))
                field(3, CanonicalBinary.u64(meshIndex.toLong()))
                field(4, CanonicalBinary.u64(primitiveIndex.toLong()))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.MODEL_GEOMETRY,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    /**
     * The identity of one **embedded** glTF image inside one GLB. An embedded image is packed straight
     * into the binary chunk by its document index, never referenced by URI, so it has no
     * [ResourceLocator] and therefore no [ResourceClass]: it cannot use [external] at all. As with
     * [modelGeometry], [modelKey] must be the model's own `EXTERNAL`/[ResourceClass.MODEL_GLB] key, and
     * it is fed in via its `stableId`; [imageIndex] is the glTF document's own image index.
     */
    internal fun modelImage(modelKey: ResourceKey, imageIndex: Int): DerivedResourceKey {
        require(modelKey.kind == ResourceKind.EXTERNAL && modelKey.resourceClass == ResourceClass.MODEL_GLB) {
            "model image derivation requires the model's external GLB resource key"
        }
        require(imageIndex >= 0) { "imageIndex must be non-negative" }

        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.MODEL_IMAGE) {
                field(1, CanonicalBinary.u16(ResourceKind.MODEL_IMAGE.wireValue))
                field(2, CanonicalBinary.exactUtf8(modelKey.stableId))
                field(3, CanonicalBinary.u64(imageIndex.toLong()))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.MODEL_IMAGE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    private fun derive(canonicalBytes: CanonicalBytes): HashedCanonicalBytes = HashedCanonicalBytes(
        digest = sha256.digest(canonicalBytes),
        canonicalBytes = canonicalBytes,
    )
}

private val ResourceKind.wireValue: Int
    get() = when (this) {
        ResourceKind.EXTERNAL -> 1
        ResourceKind.GEOMETRY_PROGRAM -> 2
        ResourceKind.INTERNAL_PIPELINE -> 3
        ResourceKind.OFFSCREEN_SURFACE -> 4
        ResourceKind.BASEMAP_TILE -> 5
        ResourceKind.MODEL_GEOMETRY -> 6
        ResourceKind.MODEL_IMAGE -> 7
    }

private val ResourceClass.wireValue: Int
    get() = when (this) {
        ResourceClass.BASEMAP_STYLE -> 1
        ResourceClass.BASEMAP_TILE_JSON -> 2
        ResourceClass.BASEMAP_VECTOR_TILE -> 3
        ResourceClass.BASEMAP_RASTER_TILE -> 4
        ResourceClass.BASEMAP_DEM_TILE -> 5
        ResourceClass.BASEMAP_SPRITE_JSON -> 6
        ResourceClass.BASEMAP_SPRITE_IMAGE -> 7
        ResourceClass.BASEMAP_GEO_JSON -> 8
        ResourceClass.STICKER_IMAGE -> 9
        ResourceClass.MODEL_GLB -> 10
        ResourceClass.MODEL_TEXTURE -> 11
        ResourceClass.BASEMAP_GLYPH_RANGE -> 12
    }

private const val GEOMETRY_SHADER_PROFILE_WIRE_VALUE: Int = 1
