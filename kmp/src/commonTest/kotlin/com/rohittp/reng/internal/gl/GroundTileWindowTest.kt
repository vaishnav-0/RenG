package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ADR 0057's arithmetic. Every value here is a power of two or an integer multiple of one, so these
 * are exact equalities and not approximations — which is why no tolerance appears in this file, in
 * `GroundTileWindow`, or in the shaders that read it.
 */
class GroundTileWindowTest {
    @Test
    fun anAncestorIsAShiftInEachAxis() {
        val tile = CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 5)

        assertEquals(CanonicalBasemapTile(lod = 3, tileY = 5, canonicalX = 2), ancestorOf(tile, 1))
        assertEquals(CanonicalBasemapTile(lod = 2, tileY = 2, canonicalX = 1), ancestorOf(tile, 2))
        assertEquals(CanonicalBasemapTile(lod = 0, tileY = 0, canonicalX = 0), ancestorOf(tile, 4))
    }

    @Test
    fun thereIsNothingAboveTheRoot() {
        val tile = CanonicalBasemapTile(lod = 2, tileY = 3, canonicalX = 1)

        assertNull(ancestorOf(tile, 3), "a tile has no ancestor above LOD 0")
        assertNull(ancestorOf(CanonicalBasemapTile(lod = 0, tileY = 0, canonicalX = 0), 1))
    }

    @Test
    fun theWindowIsWhereTheTileSitsInsideThatAncestor() {
        // Tile (4, y=11, x=5) inside its parent: low bit of each axis, at half scale.
        assertEquals(
            GroundTileWindow(scale = 0.5f, offsetU = 0.5f, offsetV = 0.5f),
            windowWithin(CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 5), 1),
        )
        // Two levels up: the low two bits, at quarter scale. x = 5 = 0b0101 -> 1; y = 11 = 0b1011 -> 3.
        assertEquals(
            GroundTileWindow(scale = 0.25f, offsetU = 0.25f, offsetV = 0.75f),
            windowWithin(CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 5), 2),
        )
    }

    @Test
    fun aWindowCoversExactlyItsTileAndNothingBeside() {
        // The property that matters to a shader: the window's span is its scale, and the far edge
        // lands exactly on the next tile's near edge with no overlap and no gap.
        //
        // Both neighbours must be inside the SAME ancestor for that to mean anything — x = 4 and
        // x = 5 share one at two levels up (low bits 0 and 1), where x = 3 and x = 4 do not.
        val window = windowWithin(CanonicalBasemapTile(lod = 3, tileY = 6, canonicalX = 4), 2)
        val neighbourU = windowWithin(CanonicalBasemapTile(lod = 3, tileY = 6, canonicalX = 5), 2)

        assertEquals(0.25f, window.scale)
        assertEquals(0.0f, window.offsetU)
        assertEquals(window.offsetU + window.scale, neighbourU.offsetU, "no overlap and no gap in u")
        assertEquals(
            ancestorOf(CanonicalBasemapTile(lod = 3, tileY = 6, canonicalX = 4), 2),
            ancestorOf(CanonicalBasemapTile(lod = 3, tileY = 6, canonicalX = 5), 2),
            "the two tiles this compares must share the ancestor the windows are into",
        )
    }

    @Test
    fun anExactTileGetsTheIdentity() {
        // The shader applies the window unconditionally, so the identity has to be a real value
        // rather than a branch: uv * 1 + 0 is uv.
        assertEquals(1.0f, GroundTileWindow.WHOLE.scale)
        assertEquals(0.0f, GroundTileWindow.WHOLE.offsetU)
        assertEquals(0.0f, GroundTileWindow.WHOLE.offsetV)
    }
}
