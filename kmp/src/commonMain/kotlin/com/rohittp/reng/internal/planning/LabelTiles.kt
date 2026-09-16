package com.rohittp.reng.internal.planning

/**
 * The tiles a frame asks the engine to plan label candidates over, which are deliberately **not** the
 * tiles its ground draws.
 *
 * `BasemapEngineHost.labelCandidateRequestKey` is rentile's, computed over the sorted de-duplicated
 * tile set, and `RenGRenderer.labelHandover` retains exactly one answer under it. So the whole label
 * acquisition -- every label tile, every Glyph Range, and a re-packed byte-identical atlas -- is paid
 * again the moment any single tile enters or leaves the set. Measured at 514-520 ms of a 605-610 ms
 * `prepare()`, about 85% of it.
 *
 * Handing that key the ground's exact per-frame selection guarantees a miss on every frame a camera
 * moves at all. `HANDOFF.md` records the conclusion drawn from that -- "a moving camera evicts any
 * bounded cache every frame" -- and it is correct about a *bounded cache*, which is why this file
 * grows no cache. It changes the question instead: **the set does not have to move with the camera.**
 *
 * Two rules, and between them a panning camera asks the same question for many frames running:
 *
 * - a set that still covers what this frame needs is returned **verbatim**, so the request key is
 *   bit-identical and the retained handover answers;
 * - a set that no longer covers it is rebuilt one tile wider than necessary, so the next rebuild is
 *   many frames away rather than the next one.
 */
internal fun observeLabelTiles(
    required: List<CanonicalBasemapTile>,
    previous: List<CanonicalBasemapTile>?,
): List<CanonicalBasemapTile> {
    if (required.isEmpty()) return emptyList()
    if (previous != null) {
        val held = previous.toHashSet()
        if (required.all { it in held }) return previous
    }
    return ringExpandedLabelTiles(required)
}

/**
 * [tiles] plus the ring of tiles immediately around them, at each tile's own level.
 *
 * **The ring is a perimeter, not a ninefold.** `CLAUDE.md` already measures this exact shape for the
 * terrain neighbourhood -- "the ring costs a perimeter, `4*sqrt(T) + 4`, **not** ninefold: +33% at 167
 * tiles" -- because the overwhelming majority of a tile's eight neighbours are already in the set. It
 * is what the hysteresis above spends to buy its hits, and one tile is the smallest amount of it that
 * can be spent.
 *
 * `canonicalX` wraps around the world at each level, because a set straddling the antimeridian must
 * name the tiles actually on the other side of it rather than tiles that do not exist. `tileY` does
 * not wrap: there is no ground above the north edge or below the south one, so a row outside the
 * level's span is dropped.
 */
internal fun ringExpandedLabelTiles(
    tiles: List<CanonicalBasemapTile>,
): List<CanonicalBasemapTile> {
    if (tiles.isEmpty()) return emptyList()
    val expanded = LinkedHashSet<CanonicalBasemapTile>(tiles.size * 2)
    for (tile in tiles) {
        val span = 1 shl tile.lod
        for (rowOffset in -LABEL_TILE_RINGS..LABEL_TILE_RINGS) {
            val tileY = tile.tileY + rowOffset
            if (tileY < 0 || tileY >= span) continue
            for (columnOffset in -LABEL_TILE_RINGS..LABEL_TILE_RINGS) {
                val canonicalX = ((tile.canonicalX + columnOffset) % span + span) % span
                expanded += CanonicalBasemapTile(lod = tile.lod, tileY = tileY, canonicalX = canonicalX)
            }
        }
    }
    return expanded.toList()
}

/**
 * How many tiles of margin a rebuilt label set carries, and **one** is measured rather than assumed.
 *
 * A wider margin buys fewer rebuilds and pays for each of them quadratically, so the total work turns
 * around immediately. Over a 120-frame pan at a tenth of a tile a frame, pitch 45, counting
 * acquisitions and the tiles each one names:
 *
 * | margin | acquisitions | widest set | tiles fetched |
 * |---|---|---|---|
 * | none, today | 96 | 27 | 2592 |
 * | **one tile** | **13** | 77 | **1001** |
 * | two tiles | 9 | 151 | 1359 |
 * | three tiles | 7 | 240 | 1680 |
 *
 * One tile is the minimum and it is also the best of the three; there is nothing to tune here.
 */
internal const val LABEL_TILE_RINGS: Int = 1
