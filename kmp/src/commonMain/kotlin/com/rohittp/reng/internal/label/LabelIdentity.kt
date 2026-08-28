package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.containsOnlyUnicodeScalars
import com.rohittp.reng.internal.identity.CanonicalBinary
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.CanonicalRootKind
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.projectMercator
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateBatch
import kotlin.math.floor

/**
 * What makes two labels in two consecutive frames **the same label**, so that ADR 0035's fade has
 * something to carry across them.
 *
 * **The rule the field set below is derived from, stated once: an identity is what a label *is*,
 * never how it is drawn this frame.** Which layer declared it, where it sits on the earth and what
 * it says are properties of the feature; its colour, its halo, its text size, its opacity, its sort
 * key, its position in the batch, its glyphs' atlas cells **and the tile it was delivered in** are
 * properties of one frame's rendering of it, and every one of them can change between two frames
 * that a human would say drew the same label. That is the line, and both ways of crossing it fail
 * silently:
 *
 *  - **Too fine** -- admit one per-frame field and no identity ever matches its predecessor. Every
 *    label restarts its fade on every frame, everything pops exactly as it did before fade existed,
 *    and every test asserting an opacity is in `[0, 1]` still passes.
 *  - **Too coarse** -- drop a distinguishing field and two labels share one entry, so one inherits
 *    the other's opacity. That reads as a rendering bug rather than an identity bug, and it only
 *    shows up under a moving camera.
 *
 * **The source tile was in this field set until the visual harness measured what it cost, and taking
 * it out is a considered decision being overturned rather than an oversight being fixed.** Task 13's
 * argument was that at an LOD change the candidate set is replaced wholesale and vector geometry is
 * quantised per tile, so anchors differ across LODs anyway and nothing is lost by pinning the tile.
 * Every clause of that is true and the conclusion is still wrong: `observeMercatorLod` changes the
 * selected LOD whenever a camera merely zooms, so `sourceTile.z` renamed **every label in view** at
 * every crossing. The harness pass measured strong label ink collapsing to exactly zero at the
 * storyboard's three crossings, in both styles, with the layer then taking ten frames to return --
 * the file's own "too fine" failure, reached by argument instead of by measurement.
 *
 * **What the tile was doing, and why the anchor already does it.** The reading that keeps the tile is
 * that it separates same-named features in adjacent tiles, because a tile buffer repeats a feature
 * into its neighbours. Rentile does not hand that repetition over: `LabelCandidateAssembler` keeps a
 * feature only for the tile whose own `[0, extent)` window contains its anchor, in as many words --
 * "the window excludes it from every tile but the one that contains it" -- so a point or line anchor
 * reaches RenG once per requested zoom, attributed to the one tile that holds it. The two candidates
 * a batch really can carry for one feature are the two world copies of a wrapped viewport, and those
 * share a canonicalised `sourceTile` and so were never separated by this field either. The tile
 * separated nothing the anchor does not; it only pinned an LOD.
 *
 * **The anchor's own drift is a tolerance question and not an identity question, which is the part
 * Task 13 asserted without measuring.** Each zoom of a tile pyramid rounds the same true position to
 * its own grid, and the grids are nested, so one place's anchor at two adjacent LODs differs by at
 * most one tile-extent unit at the finer of the two. Measured on real vector tiles over San
 * Francisco: 70% to 85% of anchors move at all across a crossing, and the largest movement seen at
 * any of z12 -> z13, z13 -> z14 and z14 -> z15 was exactly half a coarse unit -- 1.19 m, 0.60 m and
 * 0.30 m. A metre is not a different place, so the anchor enters the identity as a cell rather than
 * as a coordinate: the Mercator world position floored onto a fixed 2^20-by-2^20 grid, which is one
 * tile-extent unit at zoom 8 and about 38 m at the equator. That grid re-derived all 43 of the
 * real cross-LOD pairs measured as the same cell, and merged 38 of the 4,320 anchors in the
 * densest z14 tile of downtown San Francisco -- 0.9%, every one of them a same-layer namesake
 * within 38 m, which is a shared opacity rather than a lost label.
 *
 * **Two residuals, recorded rather than papered over.** A grid has boundaries, so a place whose true
 * position sits within a re-rounding of one loses its identity at a crossing anyway; the rate is the
 * drift over the cell, which is about 2% at LOD 14 and rises as the LOD falls, reaching a coin toss
 * around LOD 9 where one tile-extent unit is itself 38 m. And no single cell size can serve both
 * ends of the zoom range -- the drift shrinks with 2^-zoom while the spacing of distinct labels
 * shrinks with it, so a cell that keeps continent labels stable would merge neighbouring POIs. This
 * one is sized for the zooms a map spends its time at and errs towards the finer failure, because
 * that is the one whose worst case is a label that fades in twice rather than two labels sharing an
 * opacity.
 *
 * **The rule admits one thing about a line label that looks like geometry and is not: which repeat
 * along the road this is.** A `line`-placed candidate is one feature that draws its name several
 * times, once every `symbol-spacing`, and every one of those repeats carries the same candidate --
 * the same layer, the same anchor, the same letters. Under the fields above they are
 * one label, which makes two instances of a road name share one opacity and fade as if they were
 * one. They are not one label: "Rue de Rivoli, 250 pixels along" and "Rue de Rivoli, 750 pixels
 * along" are two things a reader sees at once and can watch appear separately, so *where on the
 * feature the instance sits* is part of what it is, and [LineRepeat] is the field set's name for it.
 *
 * **The screen anchor is the tempting way to say the same thing and is the "too fine" failure
 * exactly.** `PlacedLabel.anchorPixelX` and `anchorPixelY` are what separate the repeats in the
 * placed list today, and they are the pixel the label landed on under one camera: pan by one pixel
 * and every one of them changes, so an identity built on them matches nothing in the next frame,
 * every label restarts its fade every frame, and the whole feature goes silently inert. The arc
 * distance is the same statement made in the units the walk itself steps in -- half a spacing in,
 * one spacing per repeat -- and the camera decides how many repeats fit on a run without deciding
 * the distance at which repeat *k* sits. That is the whole difference between the two, and it is
 * the difference between *where the instance sits on the road* and *where the road is on the
 * screen*.
 *
 * **`PlacedLabel.candidateIndex` is the one candidate for an identity that has to be refused, and
 * the refusal is the whole point of this file.** It is carried precisely so fade can find the
 * engine's own record of a placed label without an O(n) search, and it does that job here -- but it
 * indexes *this* batch. A batch is planned from the frame's own tile list, so a pan that loads one
 * tile and drops another renumbers every candidate after it, and even a stationary camera has no
 * promise from Rentile that two acquisitions order their candidates identically. An index is a
 * within-frame handle, and this file exists because a handle is not an identity.
 *
 * **The digest is deliberately not taken.** Every other identity in RenG ends in a SHA-256 because
 * it needs a *stable id* -- a string that reaches the consumer's store, names a cache entry, or is
 * reported. This one reaches nothing: it lives in one renderer's memory for as long as the label is
 * on screen and is never written, sent, or compared against anything derived elsewhere. Hashing
 * would spend a SHA-256 per placed label per frame to turn an exact comparison into one that can
 * collide, which is the wrong trade in both directions at once. ADR 0018's canonical encoding is
 * what is actually needed here, and it is used in full: the versioned prefix, the domain-separating
 * root kind, strictly increasing tags and exact byte forms.
 *
 * The field set is frozen the way every other root's is, but with one difference worth writing down:
 * it is **not** a compatibility contract with anything outside the running process, because nothing
 * outside the running process ever sees it. A later cycle that adds the icon (task 12) to the field
 * set costs exactly one restarted fade at the version boundary and nothing else.
 */
internal class LabelIdentity internal constructor(private val canonicalBytes: CanonicalBytes) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is LabelIdentity && canonicalBytes == other.canonicalBytes)

    override fun hashCode(): Int = canonicalBytes.hashCode()

    /** Redacted: a label's text is consumer-visible content and diagnostics never carry it. */
    override fun toString(): String = "LabelIdentity(<redacted>)"
}

/**
 * The identity of one placed label: [candidateIndex]'s candidate within [batch], as [lineRepeat]
 * places it, or `null` when this batch cannot describe one.
 *
 * **The two arguments after [batch] are `PlacedLabel`'s two identifying fields and deliberately not
 * the label itself.** Handing the whole `PlacedLabel` in would put `anchorPixelX` and `anchorPixelY`
 * within reach of a derivation whose entire job is to not use them; passing what a label *is*
 * leaves the pixels it landed on outside the function.
 *
 * `null` is never a frame failure and never a dropped label: a label with no identity is drawn at
 * full opacity, which is exactly what every label did before fade existed. It is returned for the
 * three shapes Rentile's own assembler cannot produce but its types still admit -- a candidate index
 * outside the batch, a `layerStyleIndex` naming no layer, and a glyph naming no atlas entry -- plus
 * a non-finite arc distance, which the canonical encoding refuses by design, and an anchor that is
 * non-finite or projects off the grid, which is refused here for the same reason. [placeLabels]
 * takes the same position for the same reason.
 */
internal fun deriveLabelIdentity(
    batch: LabelCandidateBatch,
    candidateIndex: Int,
    lineRepeat: LineRepeat?,
): LabelIdentity? {
    val candidate = batch.candidates.getOrNull(candidateIndex) ?: return null
    val layerId = batch.layerStyles.getOrNull(candidate.layerStyleIndex)?.layerId ?: return null
    // `exactUtf8` refuses an unpaired surrogate and `binary64` refuses a non-finite Double. Both are
    // reachable from a style document and from the engine respectively, so they are checked here
    // rather than caught: a derivation that throws is a frame that fails over a cosmetic ease. The
    // anchor's own check has become load-bearing rather than belt-and-braces now that it reaches a
    // cell index instead of a `binary64`: nothing downstream refuses a non-finite anchor, because
    // `Double.toLong()` maps every one of them onto cell zero and would hand a whole class of broken
    // labels one shared fade.
    if (!containsOnlyUnicodeScalars(layerId)) return null
    if (!candidate.latitude.isFinite() || !candidate.longitude.isFinite()) return null
    if (lineRepeat != null && !lineRepeat.anchorDistancePixels.isFinite()) return null
    val anchor = projectMercator(
        GeographicPosition(
            latitude = candidate.latitude,
            unwrappedLongitude = candidate.longitude,
            altitudeMetres = 0.0,
        ),
    )
    // No second finiteness check on the projected cell, deliberately: `tan`, `asinh` and the divisions
    // between them carry a finite latitude and longitude to a finite Mercator position for every
    // input, so the check above is what stops `floor(NaN).toLong()` from collapsing a non-finite
    // anchor onto cell zero. A guard here would be one no test could ever fail -- the mutation run
    // for this task confirmed exactly that, by deleting each of the two in turn and watching the
    // other keep the suite green.
    val cellX = anchor.x * ANCHOR_GRID_CELLS
    val cellY = anchor.y * ANCHOR_GRID_CELLS
    val codepoints = candidate.codepoints(batch) ?: return null

    return LabelIdentity(
        CanonicalBinary.root(CanonicalRootKind.LABEL) {
            field(LAYER_ID_TAG, CanonicalBinary.exactUtf8(layerId))
            field(ANCHOR_CELL_X_TAG, CanonicalBinary.i64(floor(cellX).toLong()))
            field(ANCHOR_CELL_Y_TAG, CanonicalBinary.i64(floor(cellY).toLong()))
            field(CODEPOINTS_TAG, CanonicalBinary.list(codepoints))
            // Omitted entirely rather than written as a sentinel when the candidate placed one
            // instance, so a point label and a `line-center` one encode exactly the bytes they did
            // before repeats had a field at all.
            if (lineRepeat != null) {
                field(LINE_RUN_INDEX_TAG, CanonicalBinary.u64(lineRepeat.runIndex.toLong()))
                field(LINE_ANCHOR_DISTANCE_TAG, CanonicalBinary.binary64(lineRepeat.anchorDistancePixels))
            }
        },
    )
}

/**
 * The label's text, as the Unicode scalars its glyphs draw and **not** as the atlas cells they read.
 *
 * `LabelGlyphQuad.entryIndex` points into the batch's own atlas, which Rentile packs per acquisition
 * -- so two frames drawing the same word can index different cells for it, and an identity built on
 * the index is the "too fine" failure in its purest form. The codepoint behind the cell is the same
 * number in both.
 *
 * `LabelGlyphEntry.fontStackDigest` is deliberately left out. It says which faces drew the text,
 * which is the same category of fact as the colour and the size: a style that resolves a different
 * font at a different zoom has not made this a different label. Nothing is lost to collision by
 * omitting it either -- one feature in one layer resolves one font stack.
 */
private fun LabelCandidate.codepoints(batch: LabelCandidateBatch): List<CanonicalBytes>? {
    val entries = batch.atlas.entries
    return glyphs.map { glyph ->
        val entry = entries.getOrNull(glyph.entryIndex) ?: return null
        CanonicalBinary.i64(entry.codepoint.toLong())
    }
}

/**
 * The identity's spatial resolution, as the base-two log of how many cells span the Mercator world
 * on each axis. 2^20 cells is one tile-extent unit at zoom 8 -- so every tile grid from zoom 8 up is
 * a refinement of it and a cell boundary is always a tile-unit boundary too -- and about 38 m at the
 * equator. The KDoc above records what it was measured against and the two ways it is imperfect.
 */
private const val ANCHOR_GRID_EXPONENT: Int = 20

private val ANCHOR_GRID_CELLS: Double = (1L shl ANCHOR_GRID_EXPONENT).toDouble()

private const val LAYER_ID_TAG: Int = 1
private const val ANCHOR_CELL_X_TAG: Int = 2
private const val ANCHOR_CELL_Y_TAG: Int = 3
private const val CODEPOINTS_TAG: Int = 4

/**
 * `u64` rather than `i64`: unlike the tile coordinates and the codepoints above, the run index is a
 * position in a list RenG built itself, so its non-negativity is RenG's own invariant and the
 * encoding's `require` is a real check on it rather than a way to fail a frame over one of Rentile's
 * unvalidated `Int`s.
 */
private const val LINE_RUN_INDEX_TAG: Int = 5

private const val LINE_ANCHOR_DISTANCE_TAG: Int = 6
