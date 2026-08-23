package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.glb.GltfSampler
import com.rohittp.reng.internal.image.DecodedImage

/**
 * What an uploaded texture is *for*, which decides whether GL upload may premultiply it.
 *
 * The distinction is by purpose, not by file format: [IMAGE] and [DATA] both decode identically
 * through Cycle C's PNG decoder into the same [DecodedImage] shape, and differ only here, at upload.
 * Expressing the decision in this type means a caller cannot get it wrong by silently omitting a
 * documented convention — the compiler forces a choice.
 */
internal enum class TextureContent { IMAGE, DATA }

/**
 * The four `glTexParameteri` values [uploadTexture] sets on a texture: the minification and
 * magnification filters, and the `S`/`T` wrap modes. Grouping them lets a caller — currently only
 * [gltfSamplerState], mapping a glTF model's own sampler — express a complete sampler as one value
 * instead of four positional `Int` parameters a caller could transpose without the compiler noticing.
 */
internal data class TextureSamplerState(val minFilter: Int, val magFilter: Int, val wrapS: Int, val wrapT: Int)

/**
 * The sampler [uploadTexture] applies when [content] carries no glTF sampler of its own: stickers,
 * the ground, and a consumer's geometry data textures all upload without ever naming a
 * [TextureSamplerState], so this is the sampler they have always gotten, kept exact so this default
 * can change for glTF models without moving underneath any of them.
 *
 * GL's own default minification filter is `GL_NEAREST_MIPMAP_LINEAR`, which requires a mipmap chain;
 * a texture with none is *mipmap-incomplete* and samples black on a real driver. GL's default wrap
 * mode is `GL_REPEAT`, which under linear filtering samples the opposite edge at a texture's
 * boundary, bleeding the far side into every sticker edge. Both kinds get `GL_CLAMP_TO_EDGE` on both
 * wrap axes instead: nothing at this call site tiles a texture, and clamping is what makes the
 * edge-bleed fix take effect.
 *
 * The two content kinds get different filters, by owner decision:
 * - [TextureContent.IMAGE] gets `GL_LINEAR` for both minification and magnification. Linear filtering
 *   is exactly what [uploadTexture]'s premultiplication step exists to make correct.
 * - [TextureContent.DATA] gets `GL_NEAREST` for both, because nearest never invents a value:
 *   interpolating between an index of 3 and an index of 7 yields a meaningless index of 5. The
 *   accepted cost is that a signed-distance field wants linear filtering and loses its antialiasing
 *   under this rule; a per-texture filter choice — letting a consumer opt a specific data texture
 *   into linear — is the intended additive fix if that cost turns out to matter, not an oversight.
 */
internal fun defaultSamplerStateFor(content: TextureContent): TextureSamplerState = when (content) {
    TextureContent.IMAGE -> TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
    TextureContent.DATA -> TextureSamplerState(GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
}

/**
 * Maps [sampler]'s glTF fields straight through to a [TextureSamplerState], performing no
 * validation of its own: `GltfFeatures` already rejects any filter or wrap enum outside the GL set
 * during parsing, so every value here is already a legal `glTexParameteri` argument by the time it
 * reaches this function.
 *
 * A `null` [sampler] — a glTF texture reference with no `sampler` index — takes the specification's
 * own defaults: `wrapS`/`wrapT` default to `REPEAT` per the glTF specification (also
 * [GltfSampler]'s own parse-time default), and the specification leaves both filters unspecified, for
 * which RenG picks `GL_LINEAR`. A non-null [sampler] with `magFilter`/`minFilter` still absent — legal
 * per glTF, since only `wrapS`/`wrapT` are mandatory — falls back to that same `GL_LINEAR` per field,
 * independently of the other.
 */
internal fun gltfSamplerState(sampler: GltfSampler?): TextureSamplerState {
    if (sampler == null) return TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT)
    return TextureSamplerState(
        minFilter = sampler.minFilter ?: GL_LINEAR,
        magFilter = sampler.magFilter ?: GL_LINEAR,
        wrapS = sampler.wrapS,
        wrapT = sampler.wrapT,
    )
}

private fun isMipmapMinificationFilter(minFilter: Int): Boolean = minFilter == GL_NEAREST_MIPMAP_NEAREST ||
    minFilter == GL_LINEAR_MIPMAP_NEAREST ||
    minFilter == GL_NEAREST_MIPMAP_LINEAR ||
    minFilter == GL_LINEAR_MIPMAP_LINEAR

/**
 * Uploads [image]'s current unpremultiplied RGBA8 bytes as a `GL_TEXTURE_2D` and returns its object name.
 *
 * [TextureContent.IMAGE] premultiplies alpha into RGB before upload; [TextureContent.DATA] uploads
 * [image]'s bytes bit-exact. This is a filtering correctness fix, not a blend-arithmetic convenience:
 * GL's bilinear filter resamples the texture's stored bytes before any fragment shader ever sees a
 * sampled value, so an unpremultiplied transparent texel — which carries arbitrary, meaningless RGB —
 * bleeds that RGB into the visible result at any edge a filtered sample crosses. Map-anchored stickers
 * under a pitched camera always filter, since only scale exactly 1.0 avoids it. Premultiplying in the
 * fragment shader cannot fix this, because filtering has already happened by the time the shader runs.
 *
 * A consumer data texture — a boundary mask, a signed-distance field, or any other payload packed
 * across RGBA channels — must never take this path: a multiply destroys it silently, with no error.
 * `CONTEXT.md` sets this same precedent for terrain samples, which must stay bit-exact.
 *
 * [image] is never mutated by either path: [DecodedImage.rgbaSnapshot] already returns a fresh copy on
 * every read, and premultiplication (when it happens at all) runs on that copy. Cycle C's canonical
 * decoded form stays unpremultiplied.
 *
 * [sampler] sets the four `glTexParameteri` calls this upload always makes, and defaults to exactly
 * today's behaviour for [content] — see [defaultSamplerStateFor] — so stickers, the ground, and
 * consumer data textures, none of which ever pass this parameter, are unaffected by its existence.
 * glTF models pass [gltfSamplerState]'s mapping of the model's own sampler instead.
 *
 * A mipmap chain is generated, with `glGenerateMipmap`, **only** when `sampler.minFilter` names one
 * of the four mipmap minification filters — `GL_NEAREST_MIPMAP_NEAREST`,
 * `GL_LINEAR_MIPMAP_NEAREST`, `GL_NEAREST_MIPMAP_LINEAR`, or `GL_LINEAR_MIPMAP_LINEAR`. Honouring
 * such a filter without generating the chain it requires leaves the texture mipmap-incomplete, which
 * samples black on a real driver — the identical failure an earlier cycle hit on sticker textures
 * with GL's own default filter. Generating the chain always, for every filter, would change sampling
 * behaviour and cost memory for no benefit when nothing asked for it, so an explicit non-mipmap
 * minification filter — every default in [defaultSamplerStateFor] — still generates none. The
 * generate call is issued after the level-zero `glTexImage2D` upload, never before: a chain built
 * from no level-zero image is built from nothing.
 */
internal fun uploadTexture(
    binding: GlBinding,
    image: DecodedImage,
    content: TextureContent,
    sampler: TextureSamplerState = defaultSamplerStateFor(content),
): Int {
    val bytes = image.rgbaSnapshot()
    val uploadBytes = when (content) {
        TextureContent.IMAGE -> premultiplyAlpha(bytes)
        TextureContent.DATA -> bytes
    }

    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        target = GL_TEXTURE_2D,
        level = 0,
        internalFormat = GL_RGBA8,
        width = image.width,
        height = image.height,
        border = 0,
        format = GL_RGBA,
        type = GL_UNSIGNED_BYTE,
        pixels = uploadBytes,
    )

    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, sampler.minFilter)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, sampler.magFilter)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, sampler.wrapS)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, sampler.wrapT)

    if (isMipmapMinificationFilter(sampler.minFilter)) {
        binding.generateMipmap(GL_TEXTURE_2D)
    }

    return texture
}

/**
 * Premultiplies tightly packed RGBA8 [bytes] into a fresh array; [bytes] itself is never written to.
 *
 * Rounding is pinned to round-half-up: `(component * alpha + 127) / 255`, not the truncating
 * `(component * alpha) / 255`. The two differ whenever the product does not divide 255 evenly — for
 * example `137 * 137`, where truncation yields 73 and this rule yields 74 — and an unpinned choice is
 * exactly the kind of thing that silently diverges between platforms. Alpha itself is carried through
 * untouched.
 */
private fun premultiplyAlpha(bytes: ByteArray): ByteArray {
    val premultiplied = bytes.copyOf()
    var pixelStart = 0
    while (pixelStart < premultiplied.size) {
        val alpha = premultiplied[pixelStart + 3].toInt() and 0xFF
        for (channel in 0 until 3) {
            val index = pixelStart + channel
            val component = premultiplied[index].toInt() and 0xFF
            premultiplied[index] = ((component * alpha + 127) / 255).toByte()
        }
        pixelStart += 4
    }
    return premultiplied
}
