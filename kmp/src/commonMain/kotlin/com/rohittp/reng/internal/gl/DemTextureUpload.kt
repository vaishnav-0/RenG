package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.terrain.PaddedDemTexture

/**
 * The sampler every DEM texture is uploaded under.
 *
 * **`GL_NEAREST` in both directions is a correctness requirement, not a preference, and the next
 * person to look at a blocky hillside will want to "fix" it by writing `GL_LINEAR` here.** Mapbox
 * packs 24 bits of elevation across R, G and B, so neighbouring texels differing by one metre can
 * differ by a full channel carry: green rolls `255 -> 0` as red increments. A bilinear tap blends the
 * channels *independently*, so it produces a triple that lies on no line between the two real
 * heights -- a blend halfway across that carry decodes hundreds of metres wrong, and it does it in
 * the smooth part of the terrain rather than at an obvious edge. Terrarium packs the same way with
 * red as the high byte. Design section 3 settles this: smoothness comes from mesh interpolation
 * between vertices placed on texel centres, and any further smoothing is explicit taps decoded
 * individually and averaged as *heights*, never as channels.
 *
 * `GL_CLAMP_TO_EDGE` on both axes because nothing here tiles, and because the ring is what a DEM has
 * instead of a wrap mode: [PaddedDemTexture] already carries one texel of each neighbour, so the only
 * sample that can reach the clamp is one outside the padded border, which no tile's window addresses.
 * `GL_REPEAT` would answer such a sample with the far side of the world.
 *
 * Deliberately not a mipmap minification filter, so [uploadTexture] generates no chain. A mipmap
 * level of a Mapbox DEM is a channel-wise average of packed integers, which is not the elevation of
 * anything -- the same argument [GLYPH_ATLAS_SAMPLER] makes about a distance field, one packing
 * tighter.
 *
 * This is `defaultSamplerStateFor(TextureContent.DATA)`'s exact value today, and it is written out
 * anyway. That default is documented as a house default whose filter choice may be revisited per
 * texture -- [GLYPH_ATLAS_SAMPLER] is that revision already -- and a DEM cannot follow it wherever it
 * goes. Stating the four values here is what makes a change to that default a compile-time no-op for
 * terrain rather than a silent hundreds-of-metres error.
 */
internal val DEM_TEXTURE_SAMPLER: TextureSamplerState = TextureSamplerState(
    minFilter = GL_NEAREST,
    magFilter = GL_NEAREST,
    wrapS = GL_CLAMP_TO_EDGE,
    wrapT = GL_CLAMP_TO_EDGE,
)

/**
 * Puts one assembled [PaddedDemTexture] on the GPU under [key] and hands back the texture plus the
 * [TextureLease] the caller must release exactly once, or reuses the one already resident.
 *
 * **[TextureContent.DATA], and this is the second half of the `GL_NEAREST` argument.** A DEM's RGB is
 * a packed integer, not a colour, so [uploadTexture]'s [TextureContent.IMAGE] premultiplication would
 * scale every channel by an alpha that means nothing here and rewrite every height -- silently, with
 * no error, exactly the failure `CONTEXT.md`'s **Terrain Sample** entry names when it requires the
 * samples to be bit-exact with no premultiplication, scaling or colour transform.
 * [PaddedDemTexture]'s texels reach the GPU byte for byte.
 *
 * **Residency, not a per-frame upload**, in [uploadGlyphAtlas]'s exact shape and for the reason the
 * ground tile budget already exists: a padded DEM at the common 512-square tile size is 514 by 514 by
 * four, a little over a megabyte, and design section 4 sizes a frame's terrain at the visible set
 * plus a perimeter ring -- upwards of a hundred of them. Re-uploading that per frame is the defect
 * the budget was written to prevent, and panning back over ground already seen must cost nothing.
 *
 * **Enrolled in the byte budget rather than exempt from it**: [GlObjectRegistry.registerTexture], so
 * a DEM's bytes count against [com.rohittp.reng.ResourceLimits.maximumResidentGpuTextureBytes] and
 * compete honestly with tiles and atlases. Design section 10 already measured what that means --
 * terrain roughly doubles texture residency, 391 MiB at X2's measured 167-tile worst case against a
 * 512 MiB default, which fits -- and left `RESIDENT_GPU_TEXTURES_OVER_BUDGET` to make the rest
 * observable. A DEM exempt from the budget would be the one tenant that could not be reclaimed.
 *
 * **[key] must cover the ring's provenance, not just the centre tile's bytes.** Deriving it is the
 * caller's job under ADR 0018 and this function neither derives nor validates one, but the input is
 * not free: [PaddedDemTexture.contentKey] is a complete content identity for these padded texels, and
 * a key derived from anything narrower -- the centre tile's own digest, its coordinate -- goes stale
 * the moment an absent neighbour arrives and serves a replicated ring to a frame that could have
 * closed the seam. That property's KDoc walks through the two frames.
 *
 * The byte size is [com.rohittp.reng.internal.image.DecodedImage.byteCount] of the **padded** image,
 * read off the array rather than recomputed from the interior size, so the budget counts the bytes
 * that actually exist rather than the `N * N * 4` a DEM would have cost unpadded.
 */
internal fun uploadDemTexture(
    binding: GlBinding,
    registry: GlObjectRegistry,
    key: ResourceKey,
    texture: PaddedDemTexture,
): LeasedTexture {
    registry.leaseResident(key)?.let { return it }
    val handle = GlObjectHandle(
        type = GlObjectType.TEXTURE,
        name = uploadTexture(binding, texture.image, TextureContent.DATA, DEM_TEXTURE_SAMPLER),
    )
    return LeasedTexture(
        handle = handle,
        lease = registry.registerTexture(key = key, handle = handle, byteSize = texture.image.byteCount.toLong()),
    )
}
