package com.rohittp.reng

import com.rohittp.reng.internal.firewall.AcquiredDemTile
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.DemTexels
import com.rohittp.reng.internal.terrain.DemTileCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Which ground tiles a frame can actually displace, decided where ADR 0041's diagnostic can see
 * it.**
 *
 * Task 13's harness pass found a frame that fetched every DEM it asked for, discarded every one of
 * them, drew flat ground and reported nothing -- because the decision was taken during the *draw*,
 * where the only consequence of declining a tile was a missing map entry, while the diagnostic fired
 * during `prepare()` from the acquisition outcome, which had succeeded. Now that Rentile hands over
 * decoded texels, every input to that decision exists at preparation, so it is taken once, here, and
 * `resolveDemTextures` pads exactly what [PreparedTerrain.elevatedTiles] names.
 *
 * The cases below are the refusals that survive matching: a source that is not the request's own
 * ancestor. A DEM whose texels disagree with the style's declared `tileSizePx` never reaches this
 * class at all -- `TerrainAcquisition.matchDemTiles` drops it, and `TerrainAcquisitionTest` and
 * `RendererTerrainTest` are where that is held.
 */
class PreparedTerrainTest {

    /**
     * `demTileWindowFor` refuses a source that is not the requested tile's ancestor, and a requested
     * tile with no window has no sub-rectangle of the DEM to sample -- so it draws flat and must be
     * counted. The pairing is deliberately absurd rather than marginal: `(z=3, x=0, y=0)` is not an
     * ancestor of `(z=3, x=5, y=2)` at the same zoom.
     *
     * The second tile is an ordinary matched pair, and it is what stops this passing for an
     * `elevatedTiles` that is always empty.
     */
    @Test
    fun aTileWhoseSourceIsNotItsAncestorIsNotElevated() {
        val stranded = CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5)
        val ordinary = CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 6)
        val terrain = preparedTerrain(
            mapOf(
                stranded to demTile(
                    requested = DemTileCoordinate(z = 3, x = 5, y = 2),
                    source = DemTileCoordinate(z = 3, x = 0, y = 0),
                ),
                ordinary to demTile(
                    requested = DemTileCoordinate(z = 3, x = 6, y = 2),
                    source = DemTileCoordinate(z = 3, x = 6, y = 2),
                ),
            ),
        )

        assertFalse(stranded in terrain.elevatedTiles, "no window onto the DEM is no elevation")
        assertTrue(ordinary in terrain.elevatedTiles, "and the matched pair still displaces")
        assertEquals(2, terrain.demTiles.size, "both were acquired; only one is usable")
    }

    /**
     * Under overzoom several requested tiles are answered from one ancestor, so the padded texture is
     * assembled once per **source** tile. Two requests sharing a source must therefore contribute one
     * entry and two elevated tiles -- keying the texels by the requested tile instead pads the same
     * image twice and uploads it twice under two different content keys.
     */
    @Test
    fun requestsSharingOneSourceContributeOneImageAndTwoElevatedTiles() {
        val first = CanonicalBasemapTile(lod = 4, tileY = 4, canonicalX = 8)
        val second = CanonicalBasemapTile(lod = 4, tileY = 4, canonicalX = 9)
        val source = DemTileCoordinate(z = 2, x = 2, y = 1)
        val terrain = preparedTerrain(
            mapOf(
                first to demTile(DemTileCoordinate(z = 4, x = 8, y = 4), source),
                second to demTile(DemTileCoordinate(z = 4, x = 9, y = 4), source),
            ),
        )

        assertEquals(setOf(first, second), terrain.elevatedTiles)
        assertEquals(setOf(source), terrain.texelsBySource.keys)
    }

    private fun preparedTerrain(demTiles: Map<CanonicalBasemapTile, AcquiredDemTile>): PreparedTerrain =
        PreparedTerrain(
            encoding = DemEncoding.MAPBOX,
            tileSizePx = TILE_SIZE_PX,
            exaggeration = 1.0,
            demTiles = demTiles,
        )

    private fun demTile(requested: DemTileCoordinate, source: DemTileCoordinate): AcquiredDemTile =
        AcquiredDemTile(
            requestedTile = requested,
            sourceTile = source,
            encoding = DemEncoding.MAPBOX,
            texels = DemTexels(
                image = DecodedImage(TILE_SIZE_PX, TILE_SIZE_PX, ByteArray(TILE_SIZE_PX * TILE_SIZE_PX * 4)),
                contentDigest = "digest-$source",
            ),
        )
}

private const val TILE_SIZE_PX: Int = 4
