package com.rohittp.reng.internal.planning

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.clippedPhysicalPixelFootprint
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.assertIs
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MercatorLodTest {
    @Test
    fun noHistoryChoosesNearestIntegerWithMidpointTiesDown() {
        val cases = listOf(
            0.0 to 0,
            0.499999999 to 0,
            0.5 to 0,
            0.500000001 to 1,
            1.499999999 to 1,
            1.5 to 1,
            1.500000001 to 2,
            21.5 to 21,
            21.500000001 to 22,
            22.0 to 22,
        )

        cases.forEach { (zoom, expected) ->
            assertEquals(LodObservation(expected), observeMercatorLod(zoom, previousSelectedLod = null))
        }
    }

    @Test
    fun hysteresisUsesOpenUpperAndClosedLowerThresholdsExactly() {
        assertEquals(LodObservation(10), observeMercatorLod(10.5, previousSelectedLod = 10))
        assertEquals(LodObservation(11), observeMercatorLod(10.500000001, previousSelectedLod = 10))
        assertEquals(LodObservation(10), observeMercatorLod(9.000000001, previousSelectedLod = 10))
        assertEquals(LodObservation(9), observeMercatorLod(9.0, previousSelectedLod = 10))
    }

    /**
     * The band that stops a zoom hovering on a boundary from thrashing the tile set is half a level
     * wide, exactly as wide as it was when it straddled the historyless boundary symmetrically. It
     * has only moved to the finer side of that boundary.
     */
    @Test
    fun hysteresisBandIsHalfALevelWideAndSitsEntirelyBelowTheHistorylessBoundary() {
        assertEquals(LodObservation(10), observeMercatorLod(10.05, previousSelectedLod = 10))
        assertEquals(LodObservation(11), observeMercatorLod(10.05, previousSelectedLod = 11))
        assertEquals(LodObservation(11), observeMercatorLod(10.6, previousSelectedLod = 10))
        assertEquals(LodObservation(11), observeMercatorLod(10.6, previousSelectedLod = 11))
    }

    /**
     * A remembered LOD is never coarser than the one this same zoom would have selected with no
     * history at all. A coarser LOD stretches one fixed 512-texel tile across more screen pixels,
     * and the measured width of a road edge tracks that stretch almost linearly, so hysteresis
     * spending any of its band on the coarse side is hysteresis spending it on blur.
     */
    @Test
    fun aRememberedLodIsNeverCoarserThanTheHistorylessSelection() {
        var step = 0
        while (step <= 440) {
            val zoom = step * 0.05
            val historyless = observeMercatorLod(zoom, previousSelectedLod = null).selectedLod
            for (previous in 0..22) {
                val remembered = observeMercatorLod(zoom, previousSelectedLod = previous).selectedLod
                assertTrue(
                    remembered >= historyless,
                    "zoom $zoom with history $previous chose $remembered, coarser than $historyless",
                )
            }
            step += 1
        }
    }

    @Test
    fun hysteresisRepeatsOneLevelRuleForMultiLevelJumpsAndStopsAtBounds() {
        assertEquals(LodObservation(22), observeMercatorLod(22.0, previousSelectedLod = 0))
        assertEquals(LodObservation(0), observeMercatorLod(0.0, previousSelectedLod = 22))
        assertEquals(LodObservation(5), observeMercatorLod(5.5, previousSelectedLod = 0))
        assertEquals(LodObservation(6), observeMercatorLod(5.500000001, previousSelectedLod = 0))
        assertEquals(LodObservation(22), observeMercatorLod(22.0, previousSelectedLod = 22))
        assertEquals(LodObservation(0), observeMercatorLod(0.0, previousSelectedLod = 0))
    }

    @Test
    fun nullHistoryAfterResetUsesNoHistoryRuleInsteadOfAStaleLod() {
        val beforeReset = observeMercatorLod(8.4, previousSelectedLod = 9)
        val afterReset = observeMercatorLod(8.4, previousSelectedLod = null)

        assertEquals(LodObservation(9), beforeReset)
        assertEquals(LodObservation(8), afterReset)
    }

    @Test
    fun lodObservationRemainsAvailableWhenNoTileSelectionIsRequested() {
        val observation = observeMercatorLod(12.75, previousSelectedLod = 12)

        assertEquals(LodObservation(13), observation)
    }

    /**
     * ADR 0065's no-op guarantee, and it compares the selections rather than their sizes: through 20
     * degrees of pitch the merge rule leaves exactly one band, so the banded path calls
     * `selectBasemapTiles` once, with the whole frame's footprint, at the frame's own LOD -- which is
     * the call it replaced. Equality here is what lets the ADR say nothing that ships today moves.
     */
    @Test
    fun everyPitchThroughTwentyDegreesStaysOnOneBandAndSelectsExactlyWhatOneLodWould() {
        for (tenths in 0..200) {
            val camera = phoneCamera(pitch = tenths / 10.0)
            assertEquals(1, groundLodBands(camera, SELECTED_LOD).size, "pitch ${tenths / 10.0}")
            assertEquals(
                selectBasemapTiles(clippedPhysicalPixelFootprint(camera), SELECTED_LOD, BUDGET),
                selectBandedBasemapTiles(camera, SELECTED_LOD, BUDGET),
                "pitch ${tenths / 10.0}",
            )
        }
    }

    /**
     * The headline of ADR 0065. At 66.75 degrees -- the steepest pitch the ground angle bound leaves
     * whole -- one LOD for the frame asks for more tiles than `maximumBasemapTileInstances` can even
     * be configured to allow, so the frame is `RESOURCE_LIMIT_EXCEEDED` rather than slow. The banded
     * selection draws it inside the *default* budget, with room to spare.
     */
    @Test
    fun aPitchThatOneLodCannotPlanAtAnyBudgetIsPlannedInsideTheDefaultOne() {
        val camera = phoneCamera(pitch = 66.75)

        val uniform = selectBasemapTiles(clippedPhysicalPixelFootprint(camera), SELECTED_LOD, LARGEST_CONFIGURABLE_BUDGET)
        assertIs<TileSelectionOutcome.OverBudget>(uniform)
        assertTrue(uniform.actual > LARGEST_CONFIGURABLE_BUDGET.toLong())

        val banded = assertIs<TileSelectionOutcome.Success>(
            selectBandedBasemapTiles(camera, SELECTED_LOD, DEFAULT_BUDGET),
        )
        assertTrue(banded.instances.size < DEFAULT_BUDGET / 4)
    }

    /**
     * The tile count stops growing with pitch, which is what `b = 1` buys and the only claim that
     * justifies the whole decomposition. Swept across every pitch the renderer accepts rather than
     * sampled, so a future change that reintroduces growth anywhere fails here rather than on a
     * device.
     */
    @Test
    fun theTileCountStaysBoundedAcrossEveryPitchTheRendererAccepts() {
        for (halves in 0..179) {
            val pitch = halves / 2.0
            val camera = phoneCamera(pitch = pitch)
            val banded = selectBandedBasemapTiles(camera, SELECTED_LOD, DEFAULT_BUDGET)
            assertIs<TileSelectionOutcome.Success>(banded, "pitch $pitch")
            assertTrue(banded.instances.size <= 200, "pitch $pitch selected ${banded.instances.size}")
        }
    }

    /**
     * Bands partition the frame and run coarse to fine. Both halves are load-bearing: abutting rows
     * are what make two bands' ground quads share an edge rather than overlap or leave a gap, and the
     * ordering is what makes a short band always merge into the finer neighbour and the finer tile
     * draw last where two bands' tiles do overlap.
     */
    @Test
    fun bandsAbutExactlyAndRunCoarseToFine() {
        for (pitch in listOf(0.0, 23.0, 45.0, 60.0, 66.75, 80.0)) {
            val bands = groundLodBands(phoneCamera(pitch), SELECTED_LOD)
            assertTrue(bands.isNotEmpty(), "pitch $pitch")
            for ((previous, next) in bands.zipWithNext()) {
                assertEquals(previous.lastRow + 1, next.firstRow, "pitch $pitch")
                assertTrue(next.lod > previous.lod, "pitch $pitch")
            }
            for (band in bands) assertTrue(band.firstRow <= band.lastRow, "pitch $pitch")
        }
    }

    /**
     * The merge rule, stated where breaking it is visible. At 20 degrees the unmerged decomposition
     * peels a band off the top that is far too short to pay for the tiles its own shared edge costs;
     * requiring a thirty-second of the frame collapses it back into the finer neighbour, and the
     * result is the single band the no-op guarantee above depends on.
     */
    @Test
    fun aBandTooShortToEarnItsOwnSelectionIsMergedIntoTheFinerNeighbour() {
        val camera = phoneCamera(pitch = 20.0)
        val bands = groundLodBands(camera, SELECTED_LOD)

        assertEquals(1, bands.size)
        assertEquals(SELECTED_LOD, bands.single().lod)
        assertTrue(bands.single().lastRow - bands.single().firstRow + 1 > FRAME_HEIGHT / 32)
    }

    /**
     * The rule's cosine is `q / sqrt(1 + v * v)`, which is `cos(theta)` only because
     * `theta = pitch + atan(v)`. That identity is the whole reason the decomposition can be a row
     * scan, and it is cheap enough to get subtly wrong -- the square root is worth at most 0.17 of a
     * level, so dropping it moves band edges rather than breaking anything loudly.
     *
     * So this derives the angle the other way, from `atan(v)` and a plain `cos`, reconstructing
     * `FOCAL_LENGTH_SCALE` locally the way `MercatorGroundFootprintTest` already does rather than
     * importing the constant the production path divides by. Two derivations of the same level, and
     * the pitches are chosen so that no band here is short enough for the merge rule to touch --
     * asserted, not assumed, so this cannot quietly start testing merged levels instead.
     */
    @Test
    fun everyBandEdgeAgreesWithTheGroundAngleDerivedIndependentlyFromTheRow() {
        val focalLengthScale = 1.0 + sqrt(2.0)
        for (pitch in listOf(45.0, 60.0)) {
            val pitchRadians = pitch * PI / 180.0
            val bands = groundLodBands(phoneCamera(pitch), SELECTED_LOD)
            assertTrue(bands.size > 1, "pitch $pitch")
            for (band in bands) {
                assertTrue(
                    band.lastRow - band.firstRow + 1 >= FRAME_HEIGHT / 32,
                    "pitch $pitch band ${band.lod} is short enough to have been merged",
                )
                for (row in listOf(band.firstRow, band.lastRow)) {
                    val v = (1.0 - 2.0 * (row + 0.5) / FRAME_HEIGHT) / focalLengthScale
                    val groundAngle = pitchRadians + atan(v)
                    val expected = SELECTED_LOD +
                        floor(1.5 * log2(cos(groundAngle) / cos(pitchRadians)) + 0.5).toInt()
                    assertEquals(expected, band.lod, "pitch $pitch row $row")
                }
            }
        }
    }

    /**
     * ADR 0066's trap, pinned where it is cheap to check. The first ground tile's level stopped being
     * the frame's the moment ADR 0065 banded the ground, and the only thing that noticed was a GPU
     * readback fixture on one platform -- a `GROUND_RELATIVE` sticker that painted no pixels because
     * terrain had built its surface out of the band furthest from the camera.
     *
     * So this states the disagreement directly: past the first split the coarsest band is strictly
     * coarser than the frame's own level, while the centre band still carries it. Anything that reads
     * a frame-level number off `groundInstances.first()` is wrong, and this is the assertion that says
     * so without a render context.
     */
    @Test
    fun theCoarsestBandIsNotTheFramesOwnLevelOnceTheGroundIsBanded() {
        val bands = groundLodBands(phoneCamera(pitch = 45.0), SELECTED_LOD)

        assertTrue(bands.first().lod < SELECTED_LOD)
        assertTrue(bands.any { it.lod == SELECTED_LOD })
        assertEquals(SELECTED_LOD, bands.single { it.firstRow <= CENTRE_ROW && CENTRE_ROW <= it.lastRow }.lod)
    }

    private fun phoneCamera(pitch: Double): ResolvedMercatorCamera =
        assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(
                Camera(0.0, 0.0, SELECTED_LOD.toDouble(), 0.0, pitch),
                OutputPixelSize(width = 1080, height = FRAME_HEIGHT),
            ),
        ).value

    private companion object {
        const val FRAME_HEIGHT: Int = 1920
        const val CENTRE_ROW: Int = FRAME_HEIGHT / 2
        const val SELECTED_LOD: Int = 20
        const val BUDGET: Int = 1_000_000
        const val DEFAULT_BUDGET: Int = 512
        const val LARGEST_CONFIGURABLE_BUDGET: Int = 4096
    }

}
