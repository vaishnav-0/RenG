package com.rohittp.reng

import com.rohittp.reng.internal.acceptValue
import com.rohittp.reng.internal.freshCopy
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.reportOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcesTest {
    @Test
    fun resourceLocatorRejectsBlankAndIsolatedSurrogateText() {
        listOf("", " \t\n", "\uD800", "prefix\uDC00suffix").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { ResourceLocator(invalid) }
        }
    }

    @Test
    fun resourceLocatorUsesExactTextForEqualityAndRedactsItFromText() {
        val first = ResourceLocator("resource?credential=alpha")
        val equal = ResourceLocator("resource?credential=alpha")
        val different = ResourceLocator("resource?credential=beta")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
        assertEquals("ResourceLocator(<redacted>)", first.toString())
    }

    @Test
    fun resourceLocatorFailureMessageDoesNotExposeItsText() {
        val secret = "resource?credential=alpha"
        val exception = assertFailsWith<IllegalArgumentException> {
            ResourceLocator("$secret\uD800")
        }

        assertFalse(exception.message.orEmpty().contains(secret))
    }

    @Test
    fun resourceEnumsContainEveryClosedMemberInContractOrder() {
        assertEquals(
            listOf(
                ResourceClass.BASEMAP_STYLE,
                ResourceClass.BASEMAP_TILE_JSON,
                ResourceClass.BASEMAP_VECTOR_TILE,
                ResourceClass.BASEMAP_RASTER_TILE,
                ResourceClass.BASEMAP_DEM_TILE,
                ResourceClass.BASEMAP_SPRITE_JSON,
                ResourceClass.BASEMAP_SPRITE_IMAGE,
                ResourceClass.BASEMAP_GEO_JSON,
                ResourceClass.STICKER_IMAGE,
                ResourceClass.MODEL_GLB,
                ResourceClass.MODEL_TEXTURE,
                ResourceClass.BASEMAP_GLYPH_RANGE,
            ),
            ResourceClass.entries,
        )
        assertEquals(
            listOf(
                ResourceKind.EXTERNAL,
                ResourceKind.GEOMETRY_PROGRAM,
                ResourceKind.INTERNAL_PIPELINE,
                ResourceKind.OFFSCREEN_SURFACE,
                ResourceKind.BASEMAP_TILE,
                ResourceKind.MODEL_GEOMETRY,
                ResourceKind.MODEL_IMAGE,
            ),
            ResourceKind.entries,
        )
        assertEquals(
            listOf(
                ResourceAccessMode.NORMAL,
                ResourceAccessMode.CACHE_ONLY,
                ResourceAccessMode.RELOAD,
            ),
            ResourceAccessMode.entries,
        )
    }

    @Test
    fun resourceLimitsUseExactStructuralEqualityAndHashing() {
        val first = ResourceLimits()
        val equal = ResourceLimits()
        val different = ResourceLimits(maximumModelGlbBytes = 1L)

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
    }

    @Test
    fun copyHelpersReturnSnapshotsThatCannotAliasTheirSources() {
        val sourceList = mutableListOf("initial")
        val returnedList = freshListCopy(sourceList) as MutableList<String>
        sourceList[0] = "source mutation"
        assertEquals(listOf("initial"), returnedList)
        returnedList[0] = "returned mutation"
        assertEquals(listOf("source mutation"), sourceList)

        val sourceBytes = byteArrayOf(1, 2)
        val returnedBytes = sourceBytes.freshCopy()
        sourceBytes[0] = 9
        assertContentEquals(byteArrayOf(1, 2), returnedBytes)
        returnedBytes[1] = 8
        assertContentEquals(byteArrayOf(9, 2), sourceBytes)
    }

    @Test
    fun everyResourceLimitAcceptsBothInclusiveBounds() {
        LimitField.entries.forEach { field ->
            assertEquals(1L, limitsWith(field, 1L).valueFor(field))
            assertEquals(Int.MAX_VALUE.toLong(), limitsWith(field, Int.MAX_VALUE.toLong()).valueFor(field))
        }
    }

    @Test
    fun everyResourceLimitRejectsValuesOutsideInclusiveBounds() {
        LimitField.entries.forEach { field ->
            assertFailsWith<IllegalArgumentException> { limitsWith(field, 0L) }
            assertFailsWith<IllegalArgumentException> {
                limitsWith(field, Int.MAX_VALUE.toLong() + 1L)
            }
        }
    }

    /**
     * The table below is a hand-written expectation iterated in its own right, so without the size
     * assertion a twelfth [ResourceClass] would get **no** byte-ceiling, accept-header or
     * report-order coverage from the one test that exists to provide exactly that.
     *
     * The gap that guard closes is narrower than it first looks, and saying which half is covered
     * matters: `BasemapEngineHostTest`, `RentileKeyDerivationTest` and
     * `ResourceOperationOrdinaryCommitTest` all partition `ResourceClass.entries`, and
     * [resourceEnumsContainEveryClosedMemberInContractOrder] compares the whole list, so a new
     * constant's *existence* is already caught structurally in four places. What nothing caught is
     * its **mapping** — the three production `when`s in `internal/ValueSupport.kt` are exhaustive, so
     * a new constant must be given a ceiling, an accept header and a report order to compile at all,
     * and every one of those three answers could be wrong with no test disagreeing.
     */
    @Test
    fun resourceClassesMapToTheirLimitAcceptValuesAndReportOrder() {
        val limits = ResourceLimits()
        val expected = listOf(
            ResourceClass.BASEMAP_STYLE to Pair(8L * mib, "application/json"),
            ResourceClass.BASEMAP_TILE_JSON to Pair(4L * mib, "application/json"),
            ResourceClass.BASEMAP_VECTOR_TILE to Pair(32L * mib, "application/vnd.mapbox-vector-tile"),
            ResourceClass.BASEMAP_RASTER_TILE to Pair(32L * mib, "image/png"),
            ResourceClass.BASEMAP_DEM_TILE to Pair(32L * mib, "image/png"),
            ResourceClass.BASEMAP_SPRITE_JSON to Pair(4L * mib, "application/json"),
            ResourceClass.BASEMAP_SPRITE_IMAGE to Pair(32L * mib, "image/png"),
            ResourceClass.BASEMAP_GEO_JSON to Pair(64L * mib, "application/json"),
            ResourceClass.STICKER_IMAGE to Pair(32L * mib, "image/png"),
            ResourceClass.MODEL_GLB to Pair(256L * mib, "model/gltf-binary"),
            ResourceClass.MODEL_TEXTURE to Pair(32L * mib, "image/png"),
            // The one class whose ceiling is an internal constant rather than a ResourceLimits field:
            // GLYPH_RANGE_ROUTE_CEILING_BYTES, sized above the engine's own 1 MiB maxGlyphRangeBytes so
            // that Rentile's refusal is the operative one. A twelfth public limit was declined.
            ResourceClass.BASEMAP_GLYPH_RANGE to Pair(4L * mib, "application/x-protobuf"),
        )

        assertEquals(
            ResourceClass.entries.size,
            expected.size,
            "this table is every ResourceClass's only byte-ceiling, accept-header and report-order " +
                "coverage; a constant absent from it is silently unmapped here even though four " +
                "other tests would still catch its existence",
        )

        expected.forEachIndexed { index, (resourceClass, expectation) ->
            assertEquals(expectation.first, limits.maximumBytesFor(resourceClass))
            assertEquals(expectation.second, resourceClass.acceptValue)
            assertEquals(index, resourceClass.reportOrder)
        }
        ResourceKind.entries.forEachIndexed { index, resourceKind ->
            assertEquals(index, resourceKind.reportOrder)
        }
    }

    private fun limitsWith(field: LimitField, value: Long): ResourceLimits =
        when (field) {
            LimitField.BASEMAP_STYLE -> ResourceLimits(maximumBasemapStyleBytes = value)
            LimitField.BASEMAP_METADATA -> ResourceLimits(maximumBasemapMetadataBytes = value)
            LimitField.BASEMAP_TILE -> ResourceLimits(maximumBasemapTileBytes = value)
            LimitField.BASEMAP_SPRITE_IMAGE -> ResourceLimits(maximumBasemapSpriteImageBytes = value)
            LimitField.BASEMAP_GEO_JSON -> ResourceLimits(maximumBasemapGeoJsonBytes = value)
            LimitField.STICKER_IMAGE -> ResourceLimits(maximumStickerImageBytes = value)
            LimitField.MODEL_GLB -> ResourceLimits(maximumModelGlbBytes = value)
            LimitField.MODEL_TEXTURE -> ResourceLimits(maximumModelTextureBytes = value)
        }

    private fun ResourceLimits.valueFor(field: LimitField): Long =
        when (field) {
            LimitField.BASEMAP_STYLE -> maximumBasemapStyleBytes
            LimitField.BASEMAP_METADATA -> maximumBasemapMetadataBytes
            LimitField.BASEMAP_TILE -> maximumBasemapTileBytes
            LimitField.BASEMAP_SPRITE_IMAGE -> maximumBasemapSpriteImageBytes
            LimitField.BASEMAP_GEO_JSON -> maximumBasemapGeoJsonBytes
            LimitField.STICKER_IMAGE -> maximumStickerImageBytes
            LimitField.MODEL_GLB -> maximumModelGlbBytes
            LimitField.MODEL_TEXTURE -> maximumModelTextureBytes
        }

    private enum class LimitField {
        BASEMAP_STYLE,
        BASEMAP_METADATA,
        BASEMAP_TILE,
        BASEMAP_SPRITE_IMAGE,
        BASEMAP_GEO_JSON,
        STICKER_IMAGE,
        MODEL_GLB,
        MODEL_TEXTURE,
    }

    @Test
    fun decodedAndJsonChunkCeilingsHaveDocumentedDefaultsAndRanges() {
        val limits = ResourceLimits()
        assertEquals(256L * 1024L * 1024L, limits.maximumDecodedImageBytes)
        // Expressed as the engine's worst case rather than as the literal above, so it keeps holding
        // if either number moves: Rentile records 159 glyph ranges packed at 8192x4357, and decodePng
        // admits `maximumDecodedImageBytes / 4` pixels. A literal-only assertion would pass while the
        // atlas that motivated the raise stopped fitting.
        assertTrue(
            limits.maximumDecodedImageBytes / 4L >= 8192L * 4357L,
            "the decoded-image ceiling must admit the engine's largest recorded glyph atlas",
        )
        assertEquals(16L * 1024L * 1024L, limits.maximumModelJsonChunkBytes)

        assertFailsWith<IllegalArgumentException> { ResourceLimits(maximumDecodedImageBytes = 0L) }
        assertFailsWith<IllegalArgumentException> {
            ResourceLimits(maximumDecodedImageBytes = Int.MAX_VALUE.toLong() + 1L)
        }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maximumModelJsonChunkBytes = 0L) }
        assertFailsWith<IllegalArgumentException> {
            ResourceLimits(maximumModelJsonChunkBytes = Int.MAX_VALUE.toLong() + 1L)
        }
    }

    @Test
    fun basemapTileIsANonExternalResourceKind() {
        assertTrue(ResourceKind.BASEMAP_TILE in ResourceKind.entries)
        // Only EXTERNAL keys carry a resource class; this invariant must survive the new entry.
        assertFailsWith<IllegalArgumentException> {
            ResourceKey(ResourceKind.BASEMAP_TILE, "0".repeat(64), ResourceClass.BASEMAP_RASTER_TILE)
        }
    }

    private companion object {
        const val mib: Long = 1024L * 1024L
    }
}
