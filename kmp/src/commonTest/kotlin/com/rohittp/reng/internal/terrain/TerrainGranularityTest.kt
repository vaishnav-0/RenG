package com.rohittp.reng.internal.terrain

import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.internal.gl.MAXIMUM_GROUND_CELLS_PER_TILE_SIDE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle E-terrain task 6: the one granularity a frame's whole ground is drawn at.
 *
 * Every case here is arithmetic over two ceilings, which is the whole point — the rule was written
 * so that it needs no DEM texel to decide, and a test needing one would mean it had failed at that.
 */
class TerrainGranularityTest {
    /**
     * The DEM's texel count is a ceiling, because subdividing past it resamples the same texel.
     *
     * Taken at a screen size far above the DEM's so that the DEM is unambiguously the binding
     * constraint; the companion case below swaps which one binds, and between them no single input
     * can be deleted without a failure.
     */
    @Test
    fun theDemsOwnResolutionIsACeiling() {
        assertEquals(16, terrainCellsPerTileSide(demTileSizePx = 16, tileSideLogicalPixels = 4096.0))
        assertEquals(32, terrainCellsPerTileSide(demTileSizePx = 32, tileSideLogicalPixels = 4096.0))
    }

    /** The screen is the other ceiling: a cell smaller than a logical pixel is invisible. */
    @Test
    fun theScreenSizeIsTheOtherCeiling() {
        assertEquals(8, terrainCellsPerTileSide(demTileSizePx = 512, tileSideLogicalPixels = 8.0))
        assertEquals(4, terrainCellsPerTileSide(demTileSizePx = 512, tileSideLogicalPixels = 7.9))
    }

    /**
     * Rounding is **down**, and this is the case that says so.
     *
     * `7.9` above and `31` here both sit between powers of two. Rounding up would give 8 and 32;
     * rounding down gives 4 and 16. Both inputs are ceilings on what can be seen, so overshooting is
     * waste — unlike the globe's curvature rule, which rounds up because falling short there is
     * visible error.
     */
    @Test
    fun aCeilingBetweenTwoPowersRoundsDownRatherThanUp() {
        assertEquals(16, terrainCellsPerTileSide(demTileSizePx = 31, tileSideLogicalPixels = 4096.0))
        assertEquals(16, terrainCellsPerTileSide(demTileSizePx = 4096, tileSideLogicalPixels = 31.0))
    }

    /**
     * The budget cap binds before either data ceiling does, at the sizes the corpus actually serves.
     *
     * A 256-texel DEM — which is what the corpus's one explicit `tileSize` declares — drawn at a
     * 512-logical-pixel tile would ask for 256 cells from both ceilings. It gets 64, and the whole
     * reason is the arithmetic in `MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE`'s KDoc: 256 cells at X2's
     * measured 167 tiles is 11,030,183 vertices a frame.
     */
    @Test
    fun theBudgetCapBindsAtCorpusTileSizes() {
        assertEquals(
            MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE,
            terrainCellsPerTileSide(demTileSizePx = 256, tileSideLogicalPixels = 512.0),
        )
        assertEquals(
            MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE,
            terrainCellsPerTileSide(demTileSizePx = 512, tileSideLogicalPixels = 512.0),
        )
    }

    /** A tile smaller than one cell still gets one; a grid has no zeroth granularity. */
    @Test
    fun aTileTooSmallToSubdivideStillGetsOneCell() {
        assertEquals(1, terrainCellsPerTileSide(demTileSizePx = 1, tileSideLogicalPixels = 4096.0))
        assertEquals(1, terrainCellsPerTileSide(demTileSizePx = 512, tileSideLogicalPixels = 1.5))
    }

    /** Every result is a power of two, because `groundGrid`'s cache is bounded only if the keys are. */
    @Test
    fun everyGranularityIsAPowerOfTwoWithinTheBudget() {
        for (dem in listOf(1, 3, 17, 64, 100, 256, 512, 1024)) {
            for (screen in listOf(1.0, 5.0, 33.0, 128.0, 999.0, 4096.0)) {
                val cells = terrainCellsPerTileSide(dem, screen)
                assertTrue(
                    cells in 1..MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE,
                    "granularity $cells for dem=$dem screen=$screen is outside the budget",
                )
                assertEquals(
                    0,
                    cells and (cells - 1),
                    "granularity $cells for dem=$dem screen=$screen is not a power of two",
                )
                assertTrue(
                    cells <= dem && cells <= screen,
                    "granularity $cells exceeds a ceiling it was given (dem=$dem screen=$screen)",
                )
            }
        }
    }

    /**
     * Terrain and curvature are reconciled by `max`, and this is the case that distinguishes it from
     * every other reconciliation.
     *
     * Deliberately asymmetric in both directions. `min` would return the smaller in both rows and
     * `max` returns the larger in both, so a single row could not tell them apart; and "prefer
     * curvature" or "prefer terrain" each match exactly one row. Only `max` matches both.
     */
    @Test
    fun theTwoClaimsOnTheGranularityAreReconciledByTakingTheLarger() {
        assertEquals(32, groundCellsPerTileSide(curvatureCells = 32, terrainCells = 4))
        assertEquals(32, groundCellsPerTileSide(curvatureCells = 4, terrainCells = 32))
        assertEquals(8, groundCellsPerTileSide(curvatureCells = 8, terrainCells = 8))
    }

    /**
     * The grid's hard limit caps the reconciled number, and terrain's softer budget does not.
     *
     * A globe at zoom 0 legitimately wants 128 for curvature and holds few enough tiles to afford it,
     * so capping the pair at terrain's 64 would make the globe's limb faceted in exactly the frames
     * where curvature is most visible. The cap that survives is the 16-bit index's.
     */
    @Test
    fun theGridsOwnLimitCapsThePairRatherThanTerrainsBudget() {
        assertEquals(
            MAXIMUM_GROUND_CELLS_PER_TILE_SIDE,
            groundCellsPerTileSide(curvatureCells = MAXIMUM_GROUND_CELLS_PER_TILE_SIDE, terrainCells = 1),
        )
        assertTrue(
            MAXIMUM_GROUND_CELLS_PER_TILE_SIDE > MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE,
            "this case is vacuous unless the grid's limit is the looser of the two",
        )
    }

    /**
     * A frame with no terrain asks for one cell, which is what makes 28 of the corpus's 34 styles
     * draw exactly the ground they drew before this cycle.
     */
    @Test
    fun aFrameWithoutTerrainAsksForASingleCell() {
        assertEquals(
            1,
            terrainCellsPerTileSide(
                terrain = null,
                projectionMode = ProjectionMode.MERCATOR,
                zoom = 14.0,
                latitude = 46.5,
                selectedLod = 14,
            ),
        )
    }

    /**
     * The camera-driven arm agrees with the arithmetic arm, so the convenience overload cannot drift
     * from the rule it is a convenience for.
     *
     * At `zoom == selectedLod` a Mercator tile covers exactly 512 logical pixels, which with a
     * 256-texel DEM puts the budget cap in charge — the corpus's own commonest shape.
     */
    @Test
    fun theCameraDrivenArmAgreesWithTheArithmeticOne() {
        val cells = terrainCellsPerTileSide(
            terrain = TerrainGranularityInputs(demTileSizePx = 256),
            projectionMode = ProjectionMode.MERCATOR,
            zoom = 14.0,
            latitude = 46.5,
            selectedLod = 14,
        )
        assertEquals(terrainCellsPerTileSide(demTileSizePx = 256, tileSideLogicalPixels = 512.0), cells)
        assertEquals(MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE, cells)
    }

    /**
     * Zooming out below the selected LOD shrinks a tile on screen and the granularity falls with it.
     *
     * Four levels below the LOD is a 32-logical-pixel tile, where the screen ceiling — not the DEM's
     * 256 texels and not the budget — is what binds.
     */
    @Test
    fun granularityFallsAsATileShrinksOnScreen() {
        val coarse = terrainCellsPerTileSide(
            terrain = TerrainGranularityInputs(demTileSizePx = 256),
            projectionMode = ProjectionMode.MERCATOR,
            zoom = 10.0,
            latitude = 46.5,
            selectedLod = 14,
        )
        assertEquals(32, coarse)
        assertTrue(coarse < MAXIMUM_TERRAIN_CELLS_PER_TILE_SIDE, "a shrunken tile must ask for less")
    }
}
