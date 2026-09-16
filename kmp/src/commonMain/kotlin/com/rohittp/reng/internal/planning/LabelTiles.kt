package com.rohittp.reng.internal.planning

/**
 * The tiles a frame asks the engine to plan label candidates over, deliberately **not** the tiles
 * its ground draws (ADR 0070).
 *
 * `BasemapEngineHost.labelCandidateRequestKey` is rentile's, over the sorted de-duplicated tile set,
 * and `RenGRenderer.labelHandover` retains exactly one answer under it. So the whole acquisition --
 * every label tile, every Glyph Range, a re-packed byte-identical atlas -- is paid again the moment
 * any single tile enters or leaves. Measured at 514-520 ms of a 605-610 ms `prepare()`, about 85%.
 *
 * Handing that key the ground's per-frame selection guarantees a miss on every frame a camera moves.
 * `HANDOFF.md` is right that "a moving camera evicts any bounded cache every frame", which is why
 * this file grows no cache and changes the question instead: **the set need not move with the
 * camera.** Between the two rules a panning camera asks the same question for many frames running:
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
 * **A perimeter, not a ninefold.** `CLAUDE.md` measures this same shape for the terrain
 * neighbourhood -- "the ring costs a perimeter, `4*sqrt(T) + 4`, **not** ninefold: +33% at 167
 * tiles" -- because most of a tile's eight neighbours are already in the set.
 *
 * `canonicalX` wraps at each level, so a set straddling the antimeridian names the tiles actually
 * there rather than tiles that do not exist. `tileY` does not: there is no ground past either pole,
 * so a row outside the level's span is dropped.
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
 * A wider margin buys fewer rebuilds and pays for each quadratically, so the total turns around
 * immediately. Over a 120-frame pan at a tenth of a tile a frame, pitch 45:
 *
 * | margin | acquisitions | widest set | tiles fetched |
 * |---|---|---|---|
 * | none, today | 96 | 27 | 2592 |
 * | **one tile** | **13** | 77 | **1001** |
 * | two tiles | 9 | 151 | 1359 |
 * | three tiles | 7 | 240 | 1680 |
 *
 * One tile is the minimum and also the best of the three; there is nothing to tune here.
 */
internal const val LABEL_TILE_RINGS: Int = 1
