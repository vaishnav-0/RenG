package com.rohittp.reng

import com.rohittp.reng.internal.reportOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceReportsTest {
    @Test
    fun resourceKeyRequiresExactStableIdAndKindClassPairing() {
        val stableId = stableId('a')
        val external = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.STICKER_IMAGE)
        val equalExternal = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.STICKER_IMAGE)
        val nonExternal = ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableId, null)

        assertEquals(ResourceKind.EXTERNAL, external.kind)
        assertEquals(stableId, external.stableId)
        assertEquals(ResourceClass.STICKER_IMAGE, external.resourceClass)
        assertEquals(external, equalExternal)
        assertEquals(external.hashCode(), equalExternal.hashCode())
        assertNotEquals(external, nonExternal)

        assertFailsWith<IllegalArgumentException> {
            ResourceKey(ResourceKind.EXTERNAL, stableId, null)
        }
        ResourceKind.entries.filter { it != ResourceKind.EXTERNAL }.forEach { kind ->
            assertEquals(null, ResourceKey(kind, stableId, null).resourceClass)
            assertFailsWith<IllegalArgumentException> {
                ResourceKey(kind, stableId, ResourceClass.MODEL_GLB)
            }
        }

        listOf(
            "",
            stableId.dropLast(1),
            "${stableId}a",
            stableId.replaceRange(0, 1, "A"),
            stableId.replaceRange(0, 1, "g"),
        ).forEach { invalidStableId ->
            val exception = assertFailsWith<IllegalArgumentException> {
                ResourceKey(ResourceKind.EXTERNAL, invalidStableId, ResourceClass.STICKER_IMAGE)
            }
            if (invalidStableId.isNotEmpty()) {
                assertFalse(exception.message.orEmpty().contains(invalidStableId))
            }
        }
    }

    @Test
    fun resourceKeyAndSelectorsUseStructuralEqualityAndRedactedText() {
        val stableId = stableId('b')
        val key = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.MODEL_GLB)
        val equalKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.MODEL_GLB)
        val selectors = listOf(
            ResourceSelector.All,
            ResourceSelector.ByKind(ResourceKind.GEOMETRY_PROGRAM),
            ResourceSelector.ByClass(ResourceClass.MODEL_GLB),
            ResourceSelector.ByKey(key),
        )

        assertEquals(ResourceSelector.All, ResourceSelector.All)
        assertEquals(
            ResourceSelector.ByKind(ResourceKind.GEOMETRY_PROGRAM),
            ResourceSelector.ByKind(ResourceKind.GEOMETRY_PROGRAM),
        )
        assertEquals(
            ResourceSelector.ByClass(ResourceClass.MODEL_GLB),
            ResourceSelector.ByClass(ResourceClass.MODEL_GLB),
        )
        assertEquals(ResourceSelector.ByKey(key), ResourceSelector.ByKey(equalKey))
        assertFalse(ResourceSelector.ByKind(ResourceKind.EXTERNAL) == ResourceSelector.ByClass(ResourceClass.MODEL_GLB))

        assertTrue(key.toString().contains(ResourceKind.EXTERNAL.name))
        assertTrue(key.toString().contains(ResourceClass.MODEL_GLB.name))
        assertRedacted(key.toString(), stableId)
        selectors.forEach { selector -> assertRedacted(selector.toString(), stableId) }
    }

    @Test
    fun resourceUsageAcceptsKnownAndUnknownGpuCombinationsAtByteBounds() {
        val noGpu = ResourceUsage(0L, 0L, 0L, false)
        val allKnown = ResourceUsage(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, false)
        val allUnknown = ResourceUsage(0L, 0L, null, true)
        val partial = ResourceUsage(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, true)

        assertEquals(noGpu, ResourceUsage(0L, 0L, 0L, false))
        assertEquals(noGpu.hashCode(), ResourceUsage(0L, 0L, 0L, false).hashCode())
        assertNotEquals(noGpu, allKnown)
        assertEquals(Long.MAX_VALUE, allKnown.knownGpuBytes)
        assertEquals(null, allUnknown.knownGpuBytes)
        assertTrue(allUnknown.hasUnknownGpuBytes)
        assertTrue(partial.hasUnknownGpuBytes)

        assertFailsWith<IllegalArgumentException> { ResourceUsage(-1L, 0L, 0L, false) }
        assertFailsWith<IllegalArgumentException> { ResourceUsage(0L, -1L, 0L, false) }
        assertFailsWith<IllegalArgumentException> { ResourceUsage(0L, 0L, -1L, false) }
        assertFailsWith<IllegalArgumentException> { ResourceUsage(0L, 0L, null, false) }
    }

    @Test
    fun reportEntryRequiresNonnegativeCountsAtEveryBoundary() {
        val entry = ResourceReportEntry(
            key = externalKey('c', ResourceClass.MODEL_TEXTURE),
            residentGenerationCount = 0,
            retiredGenerationCount = Int.MAX_VALUE,
            leaseCount = Int.MAX_VALUE,
            reloadRequired = true,
            usage = ResourceUsage(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, true),
        )
        val equalEntry = ResourceReportEntry(
            key = externalKey('c', ResourceClass.MODEL_TEXTURE),
            residentGenerationCount = 0,
            retiredGenerationCount = Int.MAX_VALUE,
            leaseCount = Int.MAX_VALUE,
            reloadRequired = true,
            usage = ResourceUsage(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, true),
        )

        assertEquals(entry, equalEntry)
        assertEquals(entry.hashCode(), equalEntry.hashCode())
        assertFailsWith<IllegalArgumentException> { entryWith(residentGenerationCount = -1) }
        assertFailsWith<IllegalArgumentException> { entryWith(retiredGenerationCount = -1) }
        assertFailsWith<IllegalArgumentException> { entryWith(leaseCount = -1) }
    }

    @Test
    fun reportSortsAndSnapshotsEntriesWithStructuralEquality() {
        val entries = mutableListOf(
            entryWith(key = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, stableId('f'), null)),
            entryWith(key = ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableId('e'), null)),
            entryWith(key = externalKey('d', ResourceClass.MODEL_GLB)),
            entryWith(key = externalKey('c', ResourceClass.BASEMAP_STYLE)),
            entryWith(key = ResourceKey(ResourceKind.INTERNAL_PIPELINE, stableId('b'), null)),
            entryWith(key = externalKey('a', ResourceClass.BASEMAP_STYLE)),
        )
        val totals = ResourceUsage(3L, 2L, 1L, false)
        val report = ResourceReport(entries, totals)
        entries.clear()

        assertEquals(
            listOf(
                externalKey('a', ResourceClass.BASEMAP_STYLE),
                externalKey('c', ResourceClass.BASEMAP_STYLE),
                externalKey('d', ResourceClass.MODEL_GLB),
                ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableId('e'), null),
                ResourceKey(ResourceKind.INTERNAL_PIPELINE, stableId('b'), null),
                ResourceKey(ResourceKind.OFFSCREEN_SURFACE, stableId('f'), null),
            ),
            report.entries.map { it.key },
        )
        val returnedEntries = report.entries
        assertFalse(returnedEntries === report.entries)
        assertTrue(returnedEntries is MutableList<ResourceReportEntry>)
        returnedEntries.clear()
        assertEquals(6, report.entries.size)

        val equalReport = ResourceReport(report.entries.reversed(), ResourceUsage(3L, 2L, 1L, false))
        assertEquals(report, equalReport)
        assertEquals(report.hashCode(), equalReport.hashCode())
        assertNotEquals(report, ResourceReport(report.entries, ResourceUsage(3L, 2L, 2L, false)))
        assertRedacted(report.toString(), stableId('a'), stableId('b'), stableId('c'), stableId('d'), stableId('e'), stableId('f'))
    }

    @Test
    fun theTwoNewKindsSortAfterEveryExistingOneInAReport() {
        assertEquals(5, ResourceKind.MODEL_GEOMETRY.reportOrder)
        assertEquals(6, ResourceKind.MODEL_IMAGE.reportOrder)
        ResourceKind.entries
            .filter { it != ResourceKind.MODEL_GEOMETRY && it != ResourceKind.MODEL_IMAGE }
            .forEach { existing -> assertTrue(existing.reportOrder < ResourceKind.MODEL_GEOMETRY.reportOrder) }

        val entries = mutableListOf(
            entryWith(key = ResourceKey(ResourceKind.MODEL_IMAGE, stableId('a'), null)),
            entryWith(key = ResourceKey(ResourceKind.MODEL_GEOMETRY, stableId('b'), null)),
            entryWith(key = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, stableId('c'), null)),
            entryWith(key = externalKey('d', ResourceClass.STICKER_IMAGE)),
        )
        val report = ResourceReport(entries, ResourceUsage(0L, 0L, 0L, false))
        entries.clear()

        assertEquals(
            listOf(
                externalKey('d', ResourceClass.STICKER_IMAGE),
                ResourceKey(ResourceKind.OFFSCREEN_SURFACE, stableId('c'), null),
                ResourceKey(ResourceKind.MODEL_GEOMETRY, stableId('b'), null),
                ResourceKey(ResourceKind.MODEL_IMAGE, stableId('a'), null),
            ),
            report.entries.map { it.key },
        )
    }

    @Test
    fun freeResultRequiresNonnegativeCategoryCountsThatSumToMatches() {
        val empty = ResourceFreeResult(0, 0, 0, 0)
        val result = ResourceFreeResult(3, 1, 1, 1)
        val maximum = ResourceFreeResult(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0)

        assertEquals(empty, ResourceFreeResult(0, 0, 0, 0))
        assertEquals(empty.hashCode(), ResourceFreeResult(0, 0, 0, 0).hashCode())
        assertNotEquals(empty, result)
        assertEquals(Int.MAX_VALUE, maximum.matchedKeys)

        assertFailsWith<IllegalArgumentException> { ResourceFreeResult(-1, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { ResourceFreeResult(0, -1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { ResourceFreeResult(0, 0, -1, 0) }
        assertFailsWith<IllegalArgumentException> { ResourceFreeResult(0, 0, 0, -1) }
        assertFailsWith<IllegalArgumentException> { ResourceFreeResult(1, 0, 0, 0) }
    }

    private fun entryWith(
        key: ResourceKey = externalKey('a', ResourceClass.STICKER_IMAGE),
        residentGenerationCount: Int = 0,
        retiredGenerationCount: Int = 0,
        leaseCount: Int = 0,
    ): ResourceReportEntry =
        ResourceReportEntry(
            key = key,
            residentGenerationCount = residentGenerationCount,
            retiredGenerationCount = retiredGenerationCount,
            leaseCount = leaseCount,
            reloadRequired = false,
            usage = ResourceUsage(0L, 0L, 0L, false),
        )

    private fun externalKey(stableIdCharacter: Char, resourceClass: ResourceClass): ResourceKey =
        ResourceKey(ResourceKind.EXTERNAL, stableId(stableIdCharacter), resourceClass)

    private fun stableId(character: Char): String = character.toString().repeat(64)

    private fun assertRedacted(text: String, vararg sensitiveValues: String) {
        sensitiveValues.forEach { sensitiveValue ->
            assertFalse(text.contains(sensitiveValue), "text leaked $sensitiveValue: $text")
        }
    }
}
