package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.image.DecodedImage

/**
 * The sampler a glyph atlas is uploaded under, and the reason [uploadTexture] takes an explicit
 * `sampler` at all.
 *
 * **Neither [defaultSamplerStateFor] answer fits an atlas, and they fail it in opposite
 * directions.** The atlas Rentile hands over is PNG bytes whose alpha channel *is* the signed
 * distance field and whose RGB is forced opaque white.
 * - [TextureContent.IMAGE]'s default has the right filters but premultiplies, which on white RGB
 *   rewrites every colour channel into a copy of alpha. That is invisible to a shader reading only
 *   the alpha channel -- which is exactly what makes it dangerous to assert against -- but it means
 *   the bytes on the GPU are no longer the bytes the engine produced, and any later reader of the
 *   colour channels would be reading RenG's arithmetic rather than the engine's atlas.
 * - [TextureContent.DATA] leaves the bytes alone but samples `GL_NEAREST`, which destroys the whole
 *   point of a distance field: a glyph edge is recovered by interpolating the distance across the
 *   texel grid and thresholding it, so nearest sampling quantises the field to texel granularity
 *   and the antialiasing goes with it. `defaultSamplerStateFor`'s own KDoc names this exact cost
 *   and names "a per-texture filter choice" as "the intended additive fix if that cost turns out to
 *   matter, not an oversight". It turned out to matter, and this is that fix -- no new
 *   [TextureContent] constant, no new [GlBinding] entry point (each of those costs four platform
 *   bindings plus the recording fake plus the roster test).
 *
 * So an atlas takes [TextureContent.DATA]'s bytes with [TextureContent.IMAGE]'s filters.
 *
 * Wrapping is `GL_CLAMP_TO_EDGE` on both axes for a reason the atlas makes sharper than any other
 * texture RenG uploads: nothing here tiles, and under `GL_REPEAT` a linear sample taken at the
 * texture's boundary blends in the opposite edge -- which in a packed atlas is some unrelated
 * glyph's distance field, not merely a stray colour.
 *
 * Deliberately not a mipmap minification filter, so [uploadTexture] generates no chain. A mipmap
 * level of a distance field is an average of distances, which is not the distance field of anything;
 * it is a different (and wrong) glyph outline rather than a blurrier version of the right one.
 */
internal val GLYPH_ATLAS_SAMPLER: TextureSamplerState = TextureSamplerState(
    minFilter = GL_LINEAR,
    magFilter = GL_LINEAR,
    wrapS = GL_CLAMP_TO_EDGE,
    wrapT = GL_CLAMP_TO_EDGE,
)

/**
 * Puts [image] -- one decoded glyph atlas -- on the GPU under [key] and hands back the texture plus
 * the [TextureLease] the caller must release exactly once, or reuses the one already resident.
 *
 * **Residency, not a per-frame upload**, and the shape is deliberately the ground path's:
 * [GlObjectRegistry.leaseResident] first, upload and [GlObjectRegistry.registerTexture] only on a
 * miss, and either way the caller leaves holding a lease. An atlas is the single largest texture a
 * labelled frame touches and every frame drawing labels needs the same one, so re-uploading it per
 * frame would be the same defect the ground tile budget exists to prevent, at a larger size and a
 * higher frequency.
 *
 * **Enrolled in the byte budget rather than exempt from it.** [GlObjectRegistry.registerTexture],
 * not [GlObjectRegistry.register], so the atlas's bytes are counted in
 * [com.rohittp.reng.ResourceLimits.maximumResidentGpuTextureBytes] (512 MiB by default) and are
 * visible through `queryResources` as measured bytes rather than `GpuByteAccount.Unmeasurable`. Tiles
 * were that budget's only tenant until now; an atlas is its second, and the two compete honestly.
 * The alternative -- an atlas that never counts and never evicts -- would make the reported total
 * understate the GPU by however many atlases a style's font stacks require.
 *
 * A live lease is what keeps the atlas safe from eviction for the duration of the draw that needs
 * it, exactly as a ground tile's lease does: eviction iterates only unleased keys, so a frame
 * cannot lose the atlas it is drawing with to a sibling tile's pressure. Once released the atlas is
 * an ordinary least-recently-used tenant, evictable like any other -- a texture the budget can
 * never reclaim is a leak, and an atlas is too large to make an exception of.
 *
 * The byte size is [DecodedImage.byteCount], which for Cycle C's canonical RGBA8 decode is the
 * `width * height * 4` a ground tile registers, read off the array rather than recomputed so the
 * budget counts the bytes that actually exist.
 *
 * [key] is the caller's: deriving a canonical identity for an atlas is the acquiring caller's job
 * under ADR 0018, and this function neither derives nor validates one.
 */
internal fun uploadGlyphAtlas(
    binding: GlBinding,
    registry: GlObjectRegistry,
    key: ResourceKey,
    image: DecodedImage,
): LeasedTexture {
    registry.leaseResident(key)?.let { return it }
    val handle = GlObjectHandle(
        type = GlObjectType.TEXTURE,
        name = uploadTexture(binding, image, TextureContent.DATA, GLYPH_ATLAS_SAMPLER),
    )
    return LeasedTexture(
        handle = handle,
        lease = registry.registerTexture(key = key, handle = handle, byteSize = image.byteCount.toLong()),
    )
}
