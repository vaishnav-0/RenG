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
    private val sha256: Sha256Function = AcceleratedSha256,
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

    /**
     * The identity of one **packed glyph atlas**: the single texture a Rentile
     * `LabelCandidateBatch` hands over, assembled out of the Glyph Ranges the firewall fetched for it.
     *
     * **Keyed on [atlasContentKey] alone, and that is a content input rather than an index.** The
     * distinction [basemapTile] draws applies here too and lands the other way round: RenG must never
     * *index* by an engine key, because an engine release that changed its derivation would silently
     * invalidate the whole cache -- but feeding one in as content is exactly what `styleDigest` already
     * does for a rendered tile. `LabelGlyphAtlas.contentKey` is a digest over the packed glyph set, its
     * metrics, its decoded SDF bitmaps and the atlas dimensions, so it is a complete description of the
     * texture's bytes, and two frames whose atlases carry the same glyphs genuinely are the same
     * upload.
     *
     * **No style digest, deliberately.** A rendered tile takes one because two styles paint the same
     * ground differently; an atlas does not, because the same font stack packed for two styles is the
     * same pixels. Adding one would only stop two styles sharing a texture they could have shared.
     *
     * The resulting key is `EXTERNAL`/[ResourceClass.BASEMAP_GLYPH_RANGE] rather than a kind of its
     * own: [com.rohittp.reng.ResourceKind] is public API and an atlas is the GPU residency of exactly
     * that class of bytes. There is no [RawResourceKey] -- nothing fetches an atlas.
     */
    internal fun glyphAtlas(atlasContentKey: String): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.GLYPH_ATLAS) {
                field(1, CanonicalBinary.u16(ResourceKind.EXTERNAL.wireValue))
                field(2, CanonicalBinary.u16(ResourceClass.BASEMAP_GLYPH_RANGE.wireValue))
                field(3, CanonicalBinary.exactUtf8(atlasContentKey))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = identity.digest.lowercaseHex,
                resourceClass = ResourceClass.BASEMAP_GLYPH_RANGE,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    /**
     * The identity of one **sprite atlas**: the image half of the sprite pair a style declares, as the
     * firewall proxied it.
     *
     * **Keyed on the encoded bytes, which is the only content input available.** [glyphAtlas] can key on
     * `LabelGlyphAtlas.contentKey` because the engine publishes one; a sprite pair has no such digest,
     * and the url it was fetched from never leaves the engine's own acquisition, so there is nothing
     * else to be the identity of. Hashing the bytes is therefore not a fallback: it is a *complete*
     * description of the texture, which is exactly what the key is required to be.
     *
     * **It is paid once per parsed pair, not once per frame**, because the parsed manifest is retained
     * with the label handover and the renderer memoises this derivation against that same retention.
     * A frame served from the retained handover derives nothing.
     *
     * `EXTERNAL`/[ResourceClass.BASEMAP_SPRITE_IMAGE] rather than a kind of its own, for [glyphAtlas]'s
     * reason: [ResourceKind] is public API and this is the GPU residency of exactly that class of bytes.
     * There is no [RawResourceKey] -- the engine, not RenG, owns the fetch and the Store record.
     */
    internal fun spriteAtlas(atlasPngBytes: ByteArray): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.SPRITE_ATLAS) {
                field(1, CanonicalBinary.u16(ResourceKind.EXTERNAL.wireValue))
                field(2, CanonicalBinary.u16(ResourceClass.BASEMAP_SPRITE_IMAGE.wireValue))
                field(3, CanonicalBinary.opaqueBytes(atlasPngBytes))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = identity.digest.lowercaseHex,
                resourceClass = ResourceClass.BASEMAP_SPRITE_IMAGE,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    /**
     * The identity of one **padded DEM texture**: the `(N+2)` square RGBA8 a ground tile samples its
     * elevation from, assembled by `padDemTexture` out of a centre tile and its eight neighbours.
     *
     * **[paddedContentKey] must be `PaddedDemTexture.contentKey` and must not be the centre tile's
     * own digest, and this is the one place that can enforce it by construction.** A texture keyed
     * on the centre alone is stale the moment an absent neighbour arrives: the centre's bytes did
     * not change, so the second frame hits the resident texture and draws the *replicated* ring --
     * a crack at that edge in a frame that had everything it needed to close it, for as long as the
     * texture stays resident. That property's own KDoc walks through the two frames; this signature
     * is what makes the correct input the convenient one.
     *
     * **No tile coordinate and no encoding, deliberately.** Two centres with identical bytes and
     * identical neighbour bytes assemble byte-identical textures and should share one upload; a
     * coordinate would split them for no gain, and the encoding decides what a triple *means* in the
     * shader without changing a texel here.
     *
     * `EXTERNAL`/[ResourceClass.BASEMAP_DEM_TILE] rather than a kind of its own, on [glyphAtlas]'s
     * reasoning: [ResourceKind] is public API and this is the GPU residency of exactly that class of
     * bytes. There is no [RawResourceKey] -- Rentile owns the DEM fetch and the Store record.
     */
    internal fun paddedDemTexture(paddedContentKey: String): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.DEM_TEXTURE) {
                field(1, CanonicalBinary.u16(ResourceKind.EXTERNAL.wireValue))
                field(2, CanonicalBinary.u16(ResourceClass.BASEMAP_DEM_TILE.wireValue))
                field(3, CanonicalBinary.exactUtf8(paddedContentKey))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = identity.digest.lowercaseHex,
                resourceClass = ResourceClass.BASEMAP_DEM_TILE,
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
