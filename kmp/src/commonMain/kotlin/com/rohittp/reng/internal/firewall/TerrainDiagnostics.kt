package com.rohittp.reng.internal.firewall

import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.terrainCoverageIncompleteDiagnostic
import com.rohittp.reng.internal.terrainUnavailableDiagnostic
import com.rohittp.reng.internal.terrain.DemNeighbourFill

/**
 * ADR 0041's two reports, and the only place RenG says out loud that a frame's ground is flatter than
 * its style asked for.
 *
 * **Terrain is the one basemap resource that degrades instead of failing a frame**, so the diagnostic
 * channel carries the whole of what a consumer gets: there is no exception to catch, no missing frame
 * to notice, and flat ground where terrain was expected is pixel-for-pixel indistinguishable from a
 * plateau that is genuinely flat. A consumer reading no diagnostics gets a plausible, wrong map, which
 * ADR 0041 accepts as the price of not losing a third more frames to transport flakes.
 *
 * **One call is one frame, and one frame emits at most one diagnostic.** That is ADR 0036's rule about
 * aggregate label exclusions applied to a second aggregate: a per-tile count is internal volume
 * crossing a public boundary, and a channel that emits forty entries for one ordinary frame teaches a
 * consumer to stop reading it. [reportTerrainDegradation] therefore takes the frame's whole ground-tile
 * list rather than a tile, and there is no per-tile entry point to misuse.
 *
 * **Neither report is a failure.** The frame prepared and it drew, so both are
 * [com.rohittp.reng.Diagnostic]s rather than [com.rohittp.reng.RenGException]s -- and both are
 * warnings rather than errors, because [com.rohittp.reng.DiagnosticSeverity.ERROR] is what a failed
 * frame's own context diagnostic carries and neither of these frames failed.
 */
internal fun reportTerrainDegradation(
    outcome: TerrainAcquisitionOutcome,
    groundTiles: List<CanonicalBasemapTile>,
    sink: DiagnosticSink,
) {
    when (outcome) {
        // A style with no `terrain` block asked for flat ground and got it. Reporting that would fire
        // on the 28 of 34 corpus styles that declare no terrain at all.
        TerrainAcquisitionOutcome.NotDeclared -> Unit

        // The whole ground drew flat, so the coverage count is not the report: every tile is missing,
        // and a number saying so would restate the code. `Degraded` is emitted whatever the frame's
        // ground tiles are, including none -- the style asked for terrain and RenG could not supply
        // it, which is true independently of how much ground the camera happened to see.
        is TerrainAcquisitionOutcome.Degraded -> sink.emit(terrainUnavailableDiagnostic())

        is TerrainAcquisitionOutcome.Acquired -> {
            val gaps = terrainCoverageGapCount(groundTiles, outcome)
            if (gaps > 0) sink.emit(terrainCoverageIncompleteDiagnostic(gaps.toLong()))
        }
    }
}

/**
 * How many of this frame's ground tiles drew flat although the style declared terrain.
 *
 * **The ring is deliberately not counted.** [TerrainAcquisitionOutcome.Acquired.absentTiles] is every
 * *requested* tile Rentile returned nothing for, and a request is the visible set **plus a perimeter
 * ring** ([terrainTileRequest]). A missing ring tile costs one replicated border texel on a tile that
 * still displaces from its own data; it is a seam artifact, not a flat tile, and counting it would
 * inflate every ordinary frame's number by the ring's `4*sqrt(T) + 4`. ADR 0041 says "the count of
 * tiles that drew flat", so the count is over [groundTiles].
 *
 * **[groundTiles] is reduced to distinct canonical tiles first.** A Mercator frame shows the same
 * canonical tile in as many world copies as the camera spans, and one absent DEM makes every one of
 * those copies draw flat -- but it is one tile, and a consumer deciding whether to raise a zoom or
 * change a source is counting tiles.
 */
internal fun terrainCoverageGapCount(
    groundTiles: List<CanonicalBasemapTile>,
    acquired: TerrainAcquisitionOutcome.Acquired,
): Int = groundTiles.distinct().count { terrainCoverageFillOf(it, acquired) == DemNeighbourFill.ABSENT }

/**
 * Why one ground tile has no elevation of its own, or `null` when it has some.
 *
 * **The answer is [DemNeighbourFill]'s, borrowed rather than restated**, because this is the same
 * question [com.rohittp.reng.internal.terrain.padDemTexture] answers one level down about a border
 * texel, and the two must not be allowed to disagree. Its [DemNeighbourFill.WORLD_EDGE] KDoc states
 * the obligation this function discharges: a tile north of +85.0511 degrees or south of -85.0511
 * degrees does not exist, never will, and is **not** a coverage gap -- counting one would make
 * `TERRAIN_COVERAGE_INCOMPLETE` a steady state on every frame whose visible set touches the top or
 * bottom row of the world, which is exactly the "teaches a consumer to stop reading it" failure the
 * once-per-frame rule exists to avoid.
 *
 * The existence test is [CanonicalBasemapTile.tileY] against `0 until 2^lod`, and `canonicalX` is not
 * in it: the world is a cylinder, so an out-of-range `x` wraps onto a tile that does exist, exactly as
 * [terrainTileRequest] and `padDemTexture` wrap it. The `lod` bound is `padDemTexture`'s own `0..30` --
 * far above the profile's ceiling of 22, and there for the same reason: it is what keeps `1L shl lod`
 * an honest shift rather than one Kotlin has masked to 63.
 */
internal fun terrainCoverageFillOf(
    tile: CanonicalBasemapTile,
    acquired: TerrainAcquisitionOutcome.Acquired,
): DemNeighbourFill? = when {
    !existsInWorld(tile) -> DemNeighbourFill.WORLD_EDGE
    acquired.demTileFor(tile) != null -> null
    else -> DemNeighbourFill.ABSENT
}

private fun existsInWorld(tile: CanonicalBasemapTile): Boolean =
    tile.lod in 0..MAXIMUM_TERRAIN_TILE_ZOOM &&
        tile.tileY.toLong() in 0L until (1L shl tile.lod)

private const val MAXIMUM_TERRAIN_TILE_ZOOM: Int = 30
