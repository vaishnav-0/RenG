package com.rohittp.reng.internal.terrain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DemTileWindowTest {
    /**
     * Rentile's own fixture, reproduced rather than paraphrased:
     * `RasterResourceTest.childAndAncestorSamplesPreserveTheRequestedOutputWindow`
     * (`rentile/kmp/src/commonTest/.../internal/raster/RasterResourceTest.kt:19-51`) requests
     * `(z=4, x=13, y=10)` and takes the `z=2` ancestor, asserting `sourceX=3`, `sourceY=2`,
     * `childScale=4`, `childX=1`, `childY=2`.
     *
     * By hand: `childScale = 1 shl (4 - 2) = 4`; `13 / 4 = 3` and `10 / 4 = 2` name the ancestor;
     * `13 % 4 = 1` and `10 % 4 = 2` are the offsets inside it. So the requested tile is the second
     * column and the third row of the ancestor's four-by-four grid of descendants, which is
     * `u` in `[0.25, 0.5]` and `v` in `[0.5, 0.75]`.
     *
     * The `v` bounds are the half of this that Rentile's fixture cannot check for RenG, because
     * Rentile keeps `childY` and never turns it into a coordinate: `vMinimum` is the **northern** edge
     * because XYZ `y` and PNG rows both increase southward. A flipped `v` would put this window at
     * `[0.25, 0.5]` instead and read the wrong row of the DEM.
     */
    @Test
    fun reproducesRentilesOwnAncestorFixture() {
        val window = demTileWindowFor(
            requested = DemTileCoordinate(z = 4, x = 13, y = 10),
            source = DemTileCoordinate(z = 2, x = 3, y = 2),
        )

        assertNotNull(window)
        assertEquals(4, window.childScale)
        assertEquals(1, window.childX)
        assertEquals(2, window.childY)
        assertEquals(0.25, window.uMinimum, 0.0)
        assertEquals(0.5, window.uMaximum, 0.0)
        assertEquals(0.5, window.vMinimum, 0.0, "v increases southward, so the minimum is the northern edge")
        assertEquals(0.75, window.vMaximum, 0.0)
    }

    /**
     * The world is cylindrical, so a request for `x = -1` at `z = 2` is a request for the tile at
     * `x = 3`, and Rentile canonicalises with `floorMod` before dividing
     * (`RasterResource.kt:38`). Kotlin's `%` keeps its dividend's sign, so replacing `floorMod`
     * with `%` breaks both halves of this test in different ways, which is why both are here:
     *
     * - At `zoomDelta = 0` the ancestor *is* the canonical tile: `floorMod(-1, 4) = 3` matches the
     *   source tile at `x = 3`, whereas `-1 % 4 = -1` matches nothing and this returns null.
     * - At `zoomDelta = 2`, `floorMod(-3, 16) = 13`: `x = -3` addresses the world copy immediately
     *   west of the canonical one, so it is the fixture above — the same ancestor, the same window.
     *   With `%`, `-3 % 16 = -3` divides (truncating toward zero) to an ancestor at `x = 0`, so this
     *   again returns null; and had the ancestor happened to agree, the offset would have been the
     *   negative `-3`, which `DemTileWindow` refuses to construct.
     */
    @Test
    fun canonicalisesAWrappedNegativeXBeforeMatchingTheAncestor() {
        val sameZoom = demTileWindowFor(
            requested = DemTileCoordinate(z = 2, x = -1, y = 1),
            source = DemTileCoordinate(z = 2, x = 3, y = 1),
        )
        assertNotNull(sameZoom, "x = -1 at z = 2 is the tile at x = 3")
        assertEquals(DemTileWindow(childScale = 1, childX = 0, childY = 0), sameZoom)

        val overzoomed = demTileWindowFor(
            requested = DemTileCoordinate(z = 4, x = -3, y = 10),
            source = DemTileCoordinate(z = 2, x = 3, y = 2),
        )
        assertEquals(
            DemTileWindow(childScale = 4, childX = 1, childY = 2),
            overzoomed,
            "x = -3 at z = 4 canonicalises to x = 13, so the window is the fixture's own",
        )
    }

    /**
     * Overzoom depth is unbounded below the compatibility profile's `maximumOutputZoom = 22`, so a
     * source with `maxzoom: 12` reaches `childScale = 1 shl 10 = 1024` — the whole ground tile inside
     * one DEM texel, with no diagnostic anywhere.
     *
     * By hand, for an ancestor at `(z=12, x=1000, y=500)`: its descendants at `z = 22` run from
     * `x = 1000 * 1024 = 1_024_000` to `1_024_000 + 1023`, so `x = 1_025_023` is the easternmost
     * (`childX = 1023`) and `y = 500 * 1024 = 512_000` is the northernmost (`childY = 0`). Both
     * coordinates are inside `z = 22`'s `4_194_304`-tile world. The window is therefore
     * `u` in `[1023/1024, 1]` and `v` in `[0, 1/1024]`, both exact in `Double` because 1024 is a power
     * of two, and `childScale` stays positive — a `1 shl` that had overflowed would show up here as a
     * negative or zero scale rather than as a wrong-but-plausible number.
     */
    @Test
    fun reachesAThousandFoldOverzoomWithoutOverflowing() {
        val window = demTileWindowFor(
            requested = DemTileCoordinate(z = 22, x = 1_025_023, y = 512_000),
            source = DemTileCoordinate(z = 12, x = 1000, y = 500),
        )

        assertNotNull(window)
        assertEquals(1024, window.childScale)
        assertEquals(1023, window.childX)
        assertEquals(0, window.childY)
        assertEquals(0.9990234375, window.uMinimum, 0.0, "1023/1024 exactly")
        assertEquals(1.0, window.uMaximum, 0.0)
        assertEquals(0.0, window.vMinimum, 0.0)
        assertEquals(0.0009765625, window.vMaximum, 0.0, "1/1024 exactly")
    }

    /**
     * The identity case, which is what almost every frame actually gets: a source whose `maxzoom`
     * covers the requested zoom answers with the requested tile itself, and the window is the whole
     * tile. `childScale = 1 shl 0 = 1`, so both offsets are zero and both axes span `[0, 1]`.
     */
    @Test
    fun aSourceAtTheRequestedZoomCoversTheWholeTile() {
        val window = demTileWindowFor(
            requested = DemTileCoordinate(z = 10, x = 512, y = 300),
            source = DemTileCoordinate(z = 10, x = 512, y = 300),
        )

        assertNotNull(window)
        assertEquals(DemTileWindow(childScale = 1, childX = 0, childY = 0), window)
        assertEquals(0.0, window.uMinimum, 0.0)
        assertEquals(1.0, window.uMaximum, 0.0)
        assertEquals(0.0, window.vMinimum, 0.0)
        assertEquals(1.0, window.vMaximum, 0.0)
    }

    /**
     * A source tile that is not the requested tile's ancestor is a tile RenG cannot sample, and ADR
     * 0041 says such a tile draws flat rather than being guessed at. Three ways to not be the
     * ancestor, each a null: the wrong row of the right zoom, the wrong column, and a "source" deeper
     * than the request — which `acquireTerrainTiles` cannot produce, since `sourceZ` is
     * `min(requested.z, maxZoom)`, and which would make `1 shl (requested.z - source.z)` a negative
     * shift count if it were ever admitted.
     */
    @Test
    fun refusesASourceTileThatIsNotTheRequestedTilesAncestor() {
        val requested = DemTileCoordinate(z = 4, x = 13, y = 10)
        assertNull(demTileWindowFor(requested, DemTileCoordinate(z = 2, x = 3, y = 3)))
        assertNull(demTileWindowFor(requested, DemTileCoordinate(z = 2, x = 2, y = 2)))
        assertNull(demTileWindowFor(requested, DemTileCoordinate(z = 5, x = 26, y = 20)))
    }

    /**
     * `y` does not wrap — the world has no tiles north of `y = 0` or south of `y = 2^z - 1`, which is
     * why Rentile's own `neighbor` clips `y` and wraps only `x`. A request outside that range is
     * refused rather than carried into the offset arithmetic, where a negative `y` would make
     * `y % childScale` negative and `DemTileWindow`'s own `require` throw. Refusing is the behaviour
     * terrain's degrade-never-fail rule needs; throwing from a window computation is not.
     */
    @Test
    fun refusesARequestedYOutsideTheWorld() {
        assertNull(demTileWindowFor(DemTileCoordinate(z = 4, x = 13, y = -2), DemTileCoordinate(z = 2, x = 3, y = 0)))
        assertNull(demTileWindowFor(DemTileCoordinate(z = 2, x = 1, y = 4), DemTileCoordinate(z = 2, x = 1, y = 4)))
    }
}
