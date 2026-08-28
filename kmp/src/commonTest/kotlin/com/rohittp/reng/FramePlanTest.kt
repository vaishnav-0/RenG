package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame

class FramePlanTest {
    @Test
    fun framePlanCopiesInputAndEveryGetterResult() {
        val sticker = Sticker(screenPlacement(), ResourceLocator("sticker"))
        val input = mutableListOf(sticker, sticker)
        val plan = FramePlan(1, camera(), stickers = input)
        input.clear()
        val returned = plan.stickers as MutableList<Sticker>
        returned.clear()
        assertEquals(listOf(sticker, sticker), plan.stickers)
        assertEquals(listOf(sticker, sticker), plan.stickersForCore())
    }

    @Test
    fun framePlanDefaultsAreExact() {
        val plan = FramePlan(0, camera())

        assertEquals(ProjectionMode.MERCATOR, plan.projectionMode)
        assertEquals(true, plan.drawBasemap)
        assertEquals(true, plan.drawLabels)
        assertEquals(emptyList(), plan.stickers)
        assertEquals(emptyList(), plan.models)
        assertEquals(emptyList(), plan.geometries)
    }

    /**
     * `drawLabels` is orthogonal to `drawBasemap`: all four pairings construct, none is refused, and
     * each is a distinct plan. Asserting the four are pairwise **different** rather than "a plan
     * carrying `drawLabels = false` reads back `false`" is deliberate — the read-back holds for a
     * field nothing else looks at.
     *
     * The comparison is an explicit pairwise loop rather than `distinct()`, and that is not a style
     * choice: `distinct()` fills a `HashSet`, which only calls `equals` on entries that already share
     * a bucket, so four distinct hash codes make it keep all four even when `equals` ignores
     * `drawLabels` outright. Measured — the `distinct()` form passed against exactly that mutation.
     */
    @Test
    fun allFourDrawBasemapAndDrawLabelsPairingsAreLegalAndDistinct() {
        val pairings = listOf(false to false, false to true, true to false, true to true)
        val plans = pairings.map { (basemap, labels) ->
            FramePlan(0, camera(), drawBasemap = basemap, drawLabels = labels)
        }

        assertEquals(pairings, plans.map { it.drawBasemap to it.drawLabels })
        for (first in plans.indices) {
            for (second in first + 1 until plans.size) {
                assertNotEquals(
                    plans[first],
                    plans[second],
                    "${pairings[first]} and ${pairings[second]} must not be the same plan",
                )
            }
        }
        // The one pair that differs in drawLabels alone, so hashCode cannot be reading drawBasemap.
        assertNotEquals(plans[2].hashCode(), plans[3].hashCode())
    }

    @Test
    fun framePlanRequiresANonNegativeIndex() {
        assertFailsWith<IllegalArgumentException> { FramePlan(-1, camera()) }
    }

    @Test
    fun framePlanPreservesDuplicatesAndOrderAcrossAllDrawnThingLists() {
        val sticker = Sticker(screenPlacement(), ResourceLocator("sticker"))
        val model = Model(screenPlacement(), ResourceLocator("model"))
        val geometry = Geometry(
            Vector3(1.0, 2.0, 3.0),
            Vector3(0.0, 3.0, 4.0),
            ShaderPair("vertex", "fragment"),
        )
        val plan = FramePlan(
            1,
            camera(),
            stickers = listOf(sticker, sticker),
            models = listOf(model, model),
            geometries = listOf(geometry, geometry),
        )

        assertEquals(listOf(sticker, sticker), plan.stickers)
        assertEquals(listOf(model, model), plan.models)
        assertEquals(listOf(geometry, geometry), plan.geometries)
        assertEquals(listOf(model, model), plan.modelsForCore())
        assertEquals(listOf(geometry, geometry), plan.geometriesForCore())
    }

    @Test
    fun framePlanCopiesEveryListResultIncludingEmptyAndSingleton() {
        val sticker = Sticker(screenPlacement(), ResourceLocator("sticker"))
        val model = Model(screenPlacement(), ResourceLocator("model"))
        val geometry = Geometry(
            Vector3(1.0, 2.0, 3.0),
            Vector3(0.0, 3.0, 4.0),
            ShaderPair("vertex", "fragment"),
        )

        val empty = FramePlan(1, camera())
        val singleton = FramePlan(
            2,
            camera(),
            stickers = listOf(sticker),
            models = listOf(model),
            geometries = listOf(geometry),
        )

        assertGetterIsIndependent(emptyList<Sticker>(), empty.stickers) { empty.stickers }
        assertGetterIsIndependent(listOf(sticker), singleton.stickers) { singleton.stickers }
        assertGetterIsIndependent(emptyList<Model>(), empty.models) { empty.models }
        assertGetterIsIndependent(listOf(model), singleton.models) { singleton.models }
        assertGetterIsIndependent(emptyList<Geometry>(), empty.geometries) { empty.geometries }
        assertGetterIsIndependent(listOf(geometry), singleton.geometries) { singleton.geometries }
        assertGetterIsIndependent(emptyList<Sticker>(), empty.stickersForCore()) { empty.stickersForCore() }
        assertGetterIsIndependent(listOf(sticker), singleton.stickersForCore()) { singleton.stickersForCore() }
        assertGetterIsIndependent(emptyList<Model>(), empty.modelsForCore()) { empty.modelsForCore() }
        assertGetterIsIndependent(listOf(model), singleton.modelsForCore()) { singleton.modelsForCore() }
        assertGetterIsIndependent(emptyList<Geometry>(), empty.geometriesForCore()) { empty.geometriesForCore() }
        assertGetterIsIndependent(listOf(geometry), singleton.geometriesForCore()) { singleton.geometriesForCore() }
    }

    @Test
    fun framePlanSnapshotsKeepEqualityAndHashStableAfterMutation() {
        val sticker = Sticker(screenPlacement(), ResourceLocator("sticker"))
        val model = Model(screenPlacement(), ResourceLocator("model"))
        val geometry = Geometry(
            Vector3(1.0, 2.0, 3.0),
            Vector3(0.0, 3.0, 4.0),
            ShaderPair("vertex", "fragment"),
        )
        val stickers = mutableListOf(sticker)
        val models = mutableListOf(model)
        val geometries = mutableListOf(geometry)
        val plan = FramePlan(1, camera(), stickers = stickers, models = models, geometries = geometries)
        val equal = FramePlan(
            1,
            camera(),
            stickers = listOf(sticker),
            models = listOf(model),
            geometries = listOf(geometry),
        )
        val hashCode = plan.hashCode()

        stickers.clear()
        models.clear()
        geometries.clear()
        (plan.stickers as MutableList<Sticker>).clear()
        (plan.models as MutableList<Model>).clear()
        (plan.geometries as MutableList<Geometry>).clear()

        assertEquals(equal, plan)
        assertEquals(hashCode, plan.hashCode())
        assertEquals(listOf(sticker), plan.stickersForCore())
        assertEquals(listOf(model), plan.modelsForCore())
        assertEquals(listOf(geometry), plan.geometriesForCore())
    }

    private fun <T> assertGetterIsIndependent(
        contents: List<T>,
        first: List<T>,
        readAgain: () -> List<T>,
    ) {
        (first as MutableList<T>).clear()
        val second = readAgain()

        assertEquals(contents, second)
        assertNotSame(first, second)
    }

    private fun camera(): Camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0)

    private fun screenPlacement(): Placement =
        Placement(
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            1.0,
        )
}
