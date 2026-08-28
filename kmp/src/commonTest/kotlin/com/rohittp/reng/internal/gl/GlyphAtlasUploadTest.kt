package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.GpuByteAccount
import com.rohittp.reng.internal.image.DecodedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cycle E-labels task 7. What these assertions are allowed to prove, and what they deliberately do
 * not: the *sampler* is the load-bearing claim here, not the bytes.
 *
 * The atlas is white RGB with the signed distance field in alpha, so premultiplying it rewrites RGB
 * into a copy of alpha and destroys nothing a distance-field shader reads -- it reads alpha, which
 * premultiplication never touches. A test asserting "uploaded unpremultiplied" by comparing colour
 * channels is therefore true but nearly empty, and at any opaque texel it is exactly empty: it
 * passes under both [TextureContent] values. [theAtlasBytesAreUnpremultipliedAndTheFixtureProvesIt]
 * demonstrates that symmetry rather than falling into it.
 *
 * The choice that has a visible consequence is `GL_LINEAR` over
 * `defaultSamplerStateFor(TextureContent.DATA)`'s `GL_NEAREST`, and it is asserted twice: here from
 * the recording binding's own `glTexParameteri` log, and on a real driver by
 * `runGlyphAtlasSamplerReadback`, which reads back a pixel between two atlas texels where linear and
 * nearest genuinely disagree.
 */
class GlyphAtlasUploadTest {
    // The real derivation belongs to whoever acquires the atlas (ADR 0018); nothing here needs more
    // than two keys that differ.
    private val atlasKey =
        ResourceKey(ResourceKind.EXTERNAL, "a".repeat(64), ResourceClass.BASEMAP_GLYPH_RANGE)

    @Test
    fun theAtlasIsSampledLinearlyRatherThanWithTheDataDefaultsNearest() {
        val binding = RecordingGlBinding()

        uploadGlyphAtlas(binding, GlObjectRegistry(), atlasKey, atlas())

        assertEquals(
            listOf(
                "texParameteri(0xDE1,0x2801,0x2601)", // GL_TEXTURE_MIN_FILTER = GL_LINEAR
                "texParameteri(0xDE1,0x2800,0x2601)", // GL_TEXTURE_MAG_FILTER = GL_LINEAR
                "texParameteri(0xDE1,0x2802,0x812F)", // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
                "texParameteri(0xDE1,0x2803,0x812F)", // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
            ),
            binding.log.filter { it.startsWith("texParameteri") },
            "GL_NEAREST quantises a distance field to the texel grid and takes the antialiasing with it",
        )
        assertTrue(
            binding.log.none { it.startsWith("generateMipmap") },
            "a mipmap level of a distance field is an average of distances, which is no glyph's outline",
        )
    }

    // The sampler the DATA default would have given, spelled out so the two are visibly different
    // objects rather than a claim in a comment. IMAGE's default filters are the same as the atlas's;
    // it is IMAGE's premultiplication, not its sampler, that makes it wrong here.
    @Test
    fun neitherContentDefaultIsWhatAnAtlasNeeds() {
        assertNotEquals(
            defaultSamplerStateFor(TextureContent.DATA),
            GLYPH_ATLAS_SAMPLER,
            "the DATA default an atlas is uploaded under filters GL_NEAREST",
        )
        assertEquals(
            defaultSamplerStateFor(TextureContent.IMAGE),
            GLYPH_ATLAS_SAMPLER,
            "the filters an atlas wants are IMAGE's; only IMAGE's premultiplication is unwanted",
        )
    }

    @Test
    fun theAtlasBytesAreUnpremultipliedAndTheFixtureProvesIt() {
        val image = atlas()
        val atlasBinding = RecordingGlBinding()
        val premultiplyingBinding = RecordingGlBinding()

        uploadGlyphAtlas(atlasBinding, GlObjectRegistry(), atlasKey, image)
        uploadTexture(premultiplyingBinding, image, TextureContent.IMAGE)

        assertEquals(image.rgbaSnapshot().toList(), atlasBinding.lastTexImageBytes())
        // The trap, made visible instead of stepped in: at an opaque white texel the two uploads
        // agree byte for byte, so an assertion sited there discriminates nothing at all.
        assertEquals(
            premultiplyingBinding.lastTexImageBytes().subList(OPAQUE_TEXEL, OPAQUE_TEXEL + RGBA_CHANNELS),
            atlasBinding.lastTexImageBytes().subList(OPAQUE_TEXEL, OPAQUE_TEXEL + RGBA_CHANNELS),
            "premultiplying white by alpha 255 is the identity -- both contents pass a check taken here",
        )
        assertNotEquals(
            premultiplyingBinding.lastTexImageBytes().subList(PARTIAL_TEXEL, PARTIAL_TEXEL + RGBA_CHANNELS),
            atlasBinding.lastTexImageBytes().subList(PARTIAL_TEXEL, PARTIAL_TEXEL + RGBA_CHANNELS),
            "only a partly transparent texel separates the two, and even there the shader reads alpha",
        )
    }

    @Test
    fun theAtlasEntersTheByteBudgetAsMeasuredBytesRatherThanAsUnmeasurable() {
        val registry = GlObjectRegistry()
        val image = atlas()

        uploadGlyphAtlas(RecordingGlBinding(), registry, atlasKey, image)

        assertEquals(
            GpuByteAccount.measured(image.byteCount.toLong()),
            registry.gpuByteAccount(atlasKey),
            "an atlas registered through register() rather than registerTexture() would report Unmeasurable",
        )
        assertEquals(
            ATLAS_BYTES,
            image.byteCount.toLong(),
            "the fixture's own size, pinned so the budget assertions below mean something",
        )
    }

    @Test
    fun aLeasedAtlasIsNeverEvictedByTilePressure() {
        val binding = RecordingGlBinding()
        // Room for the atlas and one tile of the same size, so the second tile forces an eviction.
        val registry = GlObjectRegistry(residentTextureByteBudget = 2 * ATLAS_BYTES)
        val leased = uploadGlyphAtlas(binding, registry, atlasKey, atlas())

        registry.releaseLease(registry.registerTexture(tileKey(0), tileHandle(0), ATLAS_BYTES), binding)
        registry.releaseLease(registry.registerTexture(tileKey(1), tileHandle(1), ATLAS_BYTES), binding)

        assertEquals(
            leased.handle,
            registry.resident(atlasKey),
            "eviction iterates unleased keys only, so the frame drawing with the atlas cannot lose it",
        )
        assertNull(registry.resident(tileKey(0)), "the least recently used unleased tile went instead")
        assertEquals(tileHandle(1), registry.resident(tileKey(1)))
    }

    @Test
    fun anUnleasedAtlasIsAnOrdinaryTenantOfTheSameBudget() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 2 * ATLAS_BYTES)
        val leased = uploadGlyphAtlas(binding, registry, atlasKey, atlas())
        registry.releaseLease(leased.lease, binding)

        // The atlas is now the most recently used unleased entry. Two tiles push past the budget,
        // and the second of them arrives after the atlas has become the oldest candidate.
        registry.releaseLease(registry.registerTexture(tileKey(0), tileHandle(0), ATLAS_BYTES), binding)
        assertEquals(leased.handle, registry.resident(atlasKey), "one tile still fits beside it")

        registry.releaseLease(registry.registerTexture(tileKey(1), tileHandle(1), ATLAS_BYTES), binding)

        assertNull(
            registry.resident(atlasKey),
            "an atlas the budget could never reclaim would be a leak, not a residency",
        )
        assertTrue(
            binding.deletedNames.contains(leased.handle.name),
            "and its GL texture is deleted rather than merely forgotten",
        )
    }

    @Test
    fun aResidentAtlasIsLeasedAgainRatherThanReuploaded() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        val first = uploadGlyphAtlas(binding, registry, atlasKey, atlas())
        registry.releaseLease(first.lease, binding)
        val uploadsAfterFirst = binding.log.count { it.startsWith("texImage2D") }

        val second = uploadGlyphAtlas(binding, registry, atlasKey, atlas())

        assertEquals(first.handle, second.handle, "the atlas already on the GPU is the one to draw with")
        assertEquals(
            uploadsAfterFirst,
            binding.log.count { it.startsWith("texImage2D") },
            "an atlas re-uploaded every frame is the defect the ground tile budget exists to prevent",
        )
        // The reuse hands back its own lease, which protects the reused texture exactly as the
        // upload's did: releasing it is what marks the atlas most-recently-used.
        registry.releaseLease(second.lease, binding)
        assertEquals(first.handle, registry.resident(atlasKey))
    }
}

/**
 * A four-by-two stand-in for an atlas, shaped like one rather than merely sized like one: RGB is
 * opaque white everywhere and the alpha channel carries the distance field. Deliberately asymmetric
 * along both axes, and deliberately carrying a texel that is neither fully opaque nor fully
 * transparent -- an all-opaque fixture cannot tell a premultiplied upload from a bit-exact one.
 */
private fun atlas(): DecodedImage {
    val alphas = intArrayOf(
        0, 96, 191, 255,
        32, 128, 224, 64,
    )
    val rgba = ByteArray(alphas.size * RGBA_CHANNELS)
    alphas.forEachIndexed { texel, alpha ->
        val at = texel * RGBA_CHANNELS
        rgba[at] = OPAQUE_WHITE
        rgba[at + 1] = OPAQUE_WHITE
        rgba[at + 2] = OPAQUE_WHITE
        rgba[at + 3] = alpha.toByte()
    }
    return DecodedImage(width = 4, height = 2, rgba = rgba)
}

private const val RGBA_CHANNELS: Int = 4
private const val OPAQUE_WHITE: Byte = -1
private const val ATLAS_BYTES: Long = 4L * 2L * 4L

/** Byte offsets of the fixture's alpha-255 texel and of one of its partly transparent texels. */
private const val OPAQUE_TEXEL: Int = 3 * RGBA_CHANNELS
private const val PARTIAL_TEXEL: Int = 1 * RGBA_CHANNELS

private fun tileKey(index: Int): ResourceKey =
    ResourceKey(ResourceKind.BASEMAP_TILE, index.toString().repeat(64).take(64), null)

private fun tileHandle(index: Int): GlObjectHandle = GlObjectHandle(GlObjectType.TEXTURE, 900 + index)
