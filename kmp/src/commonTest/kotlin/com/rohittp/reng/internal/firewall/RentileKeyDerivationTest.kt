package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.resource.RentilePrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class RentileKeyDerivationTest {
    private val resolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256)

    /**
     * The eight [ResourceClass] values Rentile itself fetches and keys, each paired with the exact
     * Rentile [com.rohittp.rentile.ResourceClass] name its stable id is filed under. This is not a
     * sample of one -- the assertion right below proves this set, together with the four classes
     * [ownIdentityClasses] lists, exhausts every declared [ResourceClass]; a ninth class added to
     * either side without updating the other fails this test rather than passing on a stale subset.
     */
    private val engineKeyedClasses: Map<ResourceClass, String> = mapOf(
        ResourceClass.BASEMAP_TILE_JSON to "TILE_JSON",
        ResourceClass.BASEMAP_VECTOR_TILE to "VECTOR_TILE",
        ResourceClass.BASEMAP_RASTER_TILE to "RASTER_TILE",
        ResourceClass.BASEMAP_DEM_TILE to "DEM_TILE",
        ResourceClass.BASEMAP_SPRITE_JSON to "SPRITE_JSON",
        ResourceClass.BASEMAP_SPRITE_IMAGE to "SPRITE_IMAGE",
        ResourceClass.BASEMAP_GEO_JSON to "GEO_JSON",
        ResourceClass.BASEMAP_GLYPH_RANGE to "GLYPH_RANGE",
    )

    private val ownIdentityClasses: Set<ResourceClass> = setOf(
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.STICKER_IMAGE,
        ResourceClass.MODEL_GLB,
        ResourceClass.MODEL_TEXTURE,
    )

    @Test
    fun reproducesTheEngineDerivationForTheEightClassesItKeys() {
        assertEquals(8, engineKeyedClasses.size)
        assertEquals(
            ResourceClass.entries.toSet(),
            engineKeyedClasses.keys + ownIdentityClasses,
            "engineKeyedClasses plus ownIdentityClasses must exhaust every declared ResourceClass",
        )

        engineKeyedClasses.forEach { (resourceClass, engineClassName) ->
            val locator = ResourceLocator(
                "https://tiles.example/0/0/0.pbf?access_token=SECRET-$resourceClass&x=1",
            )
            val actual = resolver.resolve(locator, resourceClass)
            val expected = expectedEngineToken(locator.value, engineClassName)

            assertEquals(expected, actual, "class $resourceClass did not reproduce the engine derivation")
        }
    }

    @Test
    fun redactsOnlyTheEightAuthenticationParameterValues() {
        for (name in listOf("access_token", "apikey", "api_key", "key", "mtsid", "session", "session_id", "token")) {
            val redacted = redactAuthenticationQuery("https://h/p?$name=S&keep=1")
            assertEquals("https://h/p?$name=<redacted>&keep=1", redacted)
        }
        assertEquals("https://h/p?other=S", redactAuthenticationQuery("https://h/p?other=S"))
        assertEquals("https://h/p", redactAuthenticationQuery("https://h/p"))
        assertEquals("https://h/p?TOKEN=<redacted>", redactAuthenticationQuery("https://h/p?TOKEN=S"))
        assertEquals("https://h/p?a=1#frag", redactAuthenticationQuery("https://h/p?a=1#frag"))
    }

    @Test
    fun usesRenGsOwnIdentityForTheFourClassesTheEngineNeverKeys() {
        // Two stickers differing only in an auth token are distinct RenG resources and must not
        // collapse to one private key, or the whole preparation fails AMBIGUOUS_RESOURCE_ROUTE.
        val first = resolver.resolve(ResourceLocator("https://cdn/a.png?token=T1"), ResourceClass.STICKER_IMAGE)
        val second = resolver.resolve(ResourceLocator("https://cdn/a.png?token=T2"), ResourceClass.STICKER_IMAGE)
        assertNotEquals(first, second)

        for (klass in listOf(ResourceClass.BASEMAP_STYLE, ResourceClass.MODEL_GLB, ResourceClass.MODEL_TEXTURE)) {
            val a = resolver.resolve(ResourceLocator("https://cdn/x?key=A"), klass)
            val b = resolver.resolve(ResourceLocator("https://cdn/x?key=B"), klass)
            assertNotEquals(a, b, "$klass must not collapse on an authentication value")
        }
    }

    @Test
    fun sameLocatorAndClassAlwaysYieldsTheSameToken() {
        val locator = ResourceLocator("https://tiles.example/1/2/3.png")

        assertEquals(
            resolver.resolve(locator, ResourceClass.BASEMAP_RASTER_TILE),
            resolver.resolve(locator, ResourceClass.BASEMAP_RASTER_TILE),
        )
    }

    @Test
    fun independentlyConstructedResolversAgreeOnTheSameInput() {
        // ResourceKeyDeriver is stateless and referentially transparent, so two separately-constructed
        // instances provably agree on identical input; this resolver must match that discipline rather
        // than hiding state that would let two instances silently diverge.
        val locator = ResourceLocator("https://tiles.example/4/5/6.pbf?access_token=SECRET")
        val first = ProductionRentilePrivateKeyResolver(PureKotlinSha256)
        val second = ProductionRentilePrivateKeyResolver(PureKotlinSha256)

        assertEquals(
            first.resolve(locator, ResourceClass.BASEMAP_VECTOR_TILE),
            second.resolve(locator, ResourceClass.BASEMAP_VECTOR_TILE),
        )
        assertEquals(
            first.resolve(locator, ResourceClass.MODEL_GLB),
            second.resolve(locator, ResourceClass.MODEL_GLB),
        )
    }

    @Test
    fun theTokenIsRedactedInItsTextualRepresentation() {
        val token = resolver.resolve(ResourceLocator("https://cdn/a.png?token=SECRET"), ResourceClass.STICKER_IMAGE)
        assertFalse(token.toString().contains("SECRET"))
        assertFalse(token.toString().contains("cdn"))
    }

    private fun expectedEngineToken(url: String, engineClassName: String): RentilePrivateKey {
        val redactedUrl = redactAuthenticationQuery(url)
        val digest = PureKotlinSha256.digest(CanonicalBytes(redactedUrl.encodeToByteArray()))
        return RentilePrivateKey("$engineClassName:${digest.lowercaseHex}")
    }
}
