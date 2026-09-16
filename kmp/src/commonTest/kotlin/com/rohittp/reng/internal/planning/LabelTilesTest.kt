package com.rohittp.reng.internal.planning

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LabelTilesTest {
    /**
     * The whole mechanism in one claim, and it is identity rather than equality on purpose: the
     * retained handover is keyed on what rentile computes from this list, so returning a list that is
     * merely *equal* would be no better than returning a new one. `assertSame` is what says the key
     * cannot have moved.
     */
    @Test
    fun aSetThatStillCoversTheFrameIsReturnedVerbatim() {
        val previous = ringExpandedLabelTiles(listOf(tile(6, 10, 10)))
        val stillInside = listOf(tile(6, 10, 10), tile(6, 11, 10), tile(6, 10, 11))

        assertTrue(previous.containsAll(stillInside))
        assertSame(previous, observeLabelTiles(stillInside, previous))
    }

    /** And when it genuinely no longer covers the frame, the rebuild is wider than the frame needs. */
    @Test
    fun aSetThatNoLongerCoversTheFrameIsRebuiltOneTileWider() {
        val previous = ringExpandedLabelTiles(listOf(tile(6, 10, 10)))
        val movedOut = listOf(tile(6, 20, 20))

        val rebuilt = observeLabelTiles(movedOut, previous)

        assertTrue(rebuilt.containsAll(movedOut))
        assertEquals(9, rebuilt.size)
        assertTrue(rebuilt.containsAll(ringExpandedLabelTiles(movedOut)))
    }

    /**
     * The headline of ADR 0070, swept rather than sampled. A camera panning a tenth of a tile a frame
     * changes the ground's selection -- and therefore today's label request key -- on 96 of 120 frames;
     * the observed set changes on 13. The bounds are loose enough to survive a tile-selection change
     * and tight enough that losing the mechanism fails here.
     */
    @Test
    fun aSlowPanAcquiresFarLessOftenThanTheGroundSelectionChanges() {
        var previous: List<CanonicalBasemapTile>? = null
        var naive: Set<CanonicalBasemapTile>? = null
        var acquisitions = 0
        var groundChanges = 0
        var widest = 0

        for (frame in 0 until FRAMES) {
            val required = requiredTilesAt(frame)
            val observed = observeLabelTiles(required, previous)
            if (observed !== previous) acquisitions += 1
            previous = observed
            widest = maxOf(widest, observed.size)
            if (naive != required.toSet()) groundChanges += 1
            naive = required.toSet()
        }

        assertTrue(groundChanges >= 90, "the ground selection changed only $groundChanges times")
        assertTrue(acquisitions <= 20, "the label set was rebuilt $acquisitions times")
        assertTrue(acquisitions * 4 < groundChanges, "the mechanism must be worth its margin")
        // And the margin stays one tile: two rings measures 151 tiles here, and buys four fewer
        // rebuilds at a cost that more than cancels them.
        assertTrue(widest <= 100, "the widest observed set was $widest tiles")
    }

    /**
     * A set straddling the antimeridian must name the tiles actually on the other side of it, and a set
     * at the top of the world must not name a row above it. The two edges behave differently because
     * the world wraps in one axis and ends in the other.
     */
    @Test
    fun theRingWrapsAroundTheWorldButStopsAtThePole() {
        val span = 1 shl 4

        val atTheAntimeridian = ringExpandedLabelTiles(listOf(tile(4, 8, 0)))
        assertTrue(atTheAntimeridian.any { it.canonicalX == span - 1 }, "the ring must wrap west")
        assertEquals(9, atTheAntimeridian.size)

        val atThePole = ringExpandedLabelTiles(listOf(tile(4, 0, 5)))
        assertTrue(atThePole.none { it.tileY < 0 })
        assertEquals(6, atThePole.size, "a pole row has no northern neighbours")
    }

    /** A frame drawing no labels asks for nothing, and asking about nothing rebuilds nothing. */
    @Test
    fun aFrameWithNoRequiredTilesObservesNone() {
        assertEquals(emptyList(), observeLabelTiles(emptyList(), null))
        assertEquals(emptyList(), observeLabelTiles(emptyList(), listOf(tile(4, 1, 1))))
        assertEquals(emptyList(), ringExpandedLabelTiles(emptyList()))
    }

    private fun tile(lod: Int, tileY: Int, canonicalX: Int) =
        CanonicalBasemapTile(lod = lod, tileY = tileY, canonicalX = canonicalX)

    private fun requiredTilesAt(frame: Int): List<CanonicalBasemapTile> {
        val camera = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(
                Camera(0.0, frame * DEGREES_PER_FRAME, ZOOM, 0.0, PITCH),
                OutputPixelSize(width = 1080, height = 1920),
            ),
        ).value
        val selection = selectBandedBasemapTiles(camera, ZOOM.toInt(), 1_000_000)
        return assertIs<TileSelectionOutcome.Success>(selection).canonicalResources
    }

    private companion object {
        const val FRAMES: Int = 120
        const val ZOOM: Double = 14.0
        const val PITCH: Double = 45.0
        /** A tenth of a tile a frame: a constant screen speed, which is what an animation pans at. */
        val DEGREES_PER_FRAME: Double = 360.0 / (1 shl ZOOM.toInt()) / 10.0
    }
}
