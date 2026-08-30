package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.GpuByteAccount
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.terrain.PaddedDemTexture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cycle E-terrain task 5, the GL half. What these assertions can prove and what they deliberately do
 * not, in [GlyphAtlasUploadTest]'s spirit:
 *
 * **The sampler is the load-bearing claim.** Premultiplying by an alpha of 255 is the identity, so a
 * "the bytes are unpremultiplied" assertion over an *opaque* DEM passes under both [TextureContent]
 * values and proves nothing at all. That is F-2's premultiplication-on-a-colour-type-2-PNG vacuity
 * exactly. [theTexelsReachTheGpuUntouchedAndTheFixtureProvesItCould] therefore uses a deliberately
 * translucent fixture and shows the two contents genuinely disagree on it, so the claim it makes
 * about the real path is a claim the assertion is capable of failing.
 *
 * `GL_NEAREST` is the assertion with a visible consequence, and it is what design section 3 makes
 * non-negotiable: a bilinear tap across a Mapbox channel carry decodes hundreds of metres wrong.
 */
class DemTextureUploadTest {

    // The real derivation is the acquiring caller's under ADR 0018; nothing here needs more than keys
    // that differ.
    private fun demKey(id: Int) =
        ResourceKey(ResourceKind.EXTERNAL, id.toString().repeat(64).take(64), ResourceClass.BASEMAP_DEM_TILE)

    @Test
    fun aDemIsSampledNearestInBothDirectionsAndClampedOnBothAxes() {
        val binding = RecordingGlBinding()

        uploadDemTexture(binding, GlObjectRegistry(), demKey(1), paddedDem())

        assertEquals(
            listOf(
                "texParameteri(0xDE1,0x2801,0x2600)", // GL_TEXTURE_MIN_FILTER = GL_NEAREST
                "texParameteri(0xDE1,0x2800,0x2600)", // GL_TEXTURE_MAG_FILTER = GL_NEAREST
                "texParameteri(0xDE1,0x2802,0x812F)", // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
                "texParameteri(0xDE1,0x2803,0x812F)", // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
            ),
            binding.log.filter { it.startsWith("texParameteri") },
            "GL_LINEAR blends R, G and B independently across a 24-bit carry and invents a height",
        )
        assertTrue(
            binding.log.none { it.startsWith("generateMipmap") },
            "a mipmap level of a packed integer is the elevation of nothing",
        )
    }

    /**
     * The DEM sampler is written out rather than taken from [defaultSamplerStateFor], and this is what
     * that buys: it already differs from one house default, and the other is a default whose filter
     * choice is documented as revisable per texture. Terrain cannot follow that wherever it goes.
     */
    @Test
    fun theDemSamplerIsPinnedRatherThanInheritedFromAHouseDefault() {
        assertNotEquals(
            defaultSamplerStateFor(TextureContent.IMAGE),
            DEM_TEXTURE_SAMPLER,
            "IMAGE's GL_LINEAR is the fix somebody will reach for when a hillside looks blocky",
        )
        assertEquals(GL_NEAREST, DEM_TEXTURE_SAMPLER.minFilter)
        assertEquals(GL_NEAREST, DEM_TEXTURE_SAMPLER.magFilter)
        assertEquals(GL_CLAMP_TO_EDGE, DEM_TEXTURE_SAMPLER.wrapS)
        assertEquals(GL_CLAMP_TO_EDGE, DEM_TEXTURE_SAMPLER.wrapT)
    }

    @Test
    fun theTexelsReachTheGpuUntouchedAndTheFixtureProvesItCould() {
        // An acquisition can now produce this: RenG stopped refusing a translucent DEM when Rentile
        // began handing over unpremultiplied texels, so the tile's R, G and B are the ones it packed
        // whatever its alpha is. The fixture is what makes the assertion below falsifiable -- over an
        // opaque DEM, DATA and IMAGE agree byte for byte and the claim would be empty.
        val translucent = paddedDem(alpha = 128)
        val demBinding = RecordingGlBinding()
        val premultiplyingBinding = RecordingGlBinding()

        uploadDemTexture(demBinding, GlObjectRegistry(), demKey(1), translucent)
        uploadTexture(premultiplyingBinding, translucent.image, TextureContent.IMAGE, DEM_TEXTURE_SAMPLER)

        assertEquals(
            translucent.image.rgbaSnapshot().toList(),
            demBinding.lastTexImageBytes(),
            "a DEM's RGB is a packed integer; scaling it by alpha rewrites every height silently",
        )
        assertNotEquals(
            premultiplyingBinding.lastTexImageBytes(),
            demBinding.lastTexImageBytes(),
            "the fixture separates the two contents, so the assertion above is one that can fail",
        )
    }

    @Test
    fun aResidentDemIsLeasedRatherThanDecodedAndUploadedAgain() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()

        val first = uploadDemTexture(binding, registry, demKey(1), paddedDem())
        val second = uploadDemTexture(binding, registry, demKey(1), paddedDem())

        assertEquals(first.handle, second.handle)
        assertEquals(
            1,
            binding.log.count { it.startsWith("texImage2D") },
            "a padded 512-square DEM is a megabyte, and a frame carries upwards of a hundred of them",
        )
        assertNotEquals(first.lease, second.lease, "both callers leave holding a lease of their own")
    }

    /**
     * The budget must count the padded bytes rather than the `N * N * 4` a DEM would have cost
     * unpadded: at 512 square the ring is another 8,208 bytes a tile, and design section 10 already
     * measured terrain at 391 MiB against a 512 MiB default with no headroom to spare on a rounding
     * error repeated 167 times.
     */
    @Test
    fun theBudgetCountsThePaddedBytesAsMeasuredRatherThanAsUnmeasurable() {
        val registry = GlObjectRegistry()
        val dem = paddedDem()

        uploadDemTexture(RecordingGlBinding(), registry, demKey(1), dem)

        val padded = (FIXTURE_INTERIOR + 2).toLong()
        assertEquals(
            GpuByteAccount.measured(padded * padded * 4L),
            registry.gpuByteAccount(demKey(1)),
            "registered through register() rather than registerTexture() it would report Unmeasurable",
        )
        assertNotEquals(
            GpuByteAccount.measured(FIXTURE_INTERIOR.toLong() * FIXTURE_INTERIOR.toLong() * 4L),
            registry.gpuByteAccount(demKey(1)),
            "the interior's own size is what an unpadded DEM would have cost",
        )
    }

    @Test
    fun aLeasedDemSurvivesThePressureOfItsOwnSiblings() {
        val binding = RecordingGlBinding()
        val demBytes = (FIXTURE_INTERIOR + 2).toLong() * (FIXTURE_INTERIOR + 2).toLong() * 4L
        val registry = GlObjectRegistry(residentTextureByteBudget = 2 * demBytes)

        val leased = uploadDemTexture(binding, registry, demKey(1), paddedDem())
        for (sibling in 2..3) {
            val handle = GlObjectHandle(GlObjectType.TEXTURE, 88 + sibling)
            registry.releaseLease(registry.registerTexture(demKey(sibling), handle, demBytes), binding)
        }

        assertEquals(
            leased.handle,
            registry.resident(demKey(1)),
            "eviction iterates unleased keys only, so the frame drawing with this DEM cannot lose it",
        )
    }

    private fun paddedDem(alpha: Int = 255): PaddedDemTexture {
        val size = FIXTURE_INTERIOR + 2
        val bytes = ByteArray(size * size * 4)
        for (texel in 0 until size * size) {
            bytes[texel * 4] = 1
            bytes[texel * 4 + 1] = (texel % 251).toByte()
            bytes[texel * 4 + 2] = (texel % 97).toByte()
            bytes[texel * 4 + 3] = alpha.toByte()
        }
        return PaddedDemTexture(
            interiorSizePx = FIXTURE_INTERIOR,
            image = DecodedImage(size, size, bytes),
            filledNeighbours = emptyMap(),
            contentKey = "dem-padded-v1|fixture",
        )
    }
}

private const val FIXTURE_INTERIOR: Int = 4
