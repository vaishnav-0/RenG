package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.containsOnlyUnicodeScalars
import com.rohittp.reng.internal.identity.CanonicalBinary
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.CanonicalRootKind
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelCandidateBatch

/**
 * What makes two labels in two consecutive frames **the same label**, so that ADR 0035's fade has
 * something to carry across them.
 *
 * **The rule the field set below is derived from, stated once: an identity is what a label *is*,
 * never how it is drawn this frame.** Which layer declared it, which tile it came out of, where it
 * sits on the earth and what it says are properties of the feature; its colour, its halo, its text
 * size, its opacity, its sort key, its position in the batch and its glyphs' atlas cells are
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
 * The identity of [candidateIndex]'s label within [batch], or `null` when this batch cannot describe
 * one.
 *
 * `null` is never a frame failure and never a dropped label: a label with no identity is drawn at
 * full opacity, which is exactly what every label did before fade existed. It is returned for the
 * three shapes Rentile's own assembler cannot produce but its types still admit -- a candidate index
 * outside the batch, a `layerStyleIndex` naming no layer, and a glyph naming no atlas entry -- plus
 * a non-finite anchor, which the canonical encoding refuses by design. [placeLabels] takes the same
 * position for the same reason.
 */
internal fun deriveLabelIdentity(batch: LabelCandidateBatch, candidateIndex: Int): LabelIdentity? {
    val candidate = batch.candidates.getOrNull(candidateIndex) ?: return null
    val layerId = batch.layerStyles.getOrNull(candidate.layerStyleIndex)?.layerId ?: return null
    // `exactUtf8` refuses an unpaired surrogate and `binary64` refuses a non-finite Double. Both are
    // reachable from a style document and from the engine respectively, so they are checked here
    // rather than caught: a derivation that throws is a frame that fails over a cosmetic ease.
    if (!containsOnlyUnicodeScalars(layerId)) return null
    if (!candidate.latitude.isFinite() || !candidate.longitude.isFinite()) return null
    val codepoints = candidate.codepoints(batch) ?: return null

    return LabelIdentity(
        CanonicalBinary.root(CanonicalRootKind.LABEL) {
            field(LAYER_ID_TAG, CanonicalBinary.exactUtf8(layerId))
            field(SOURCE_TILE_Z_TAG, CanonicalBinary.i64(candidate.sourceTile.z.toLong()))
            field(SOURCE_TILE_X_TAG, CanonicalBinary.i64(candidate.sourceTile.x.toLong()))
            field(SOURCE_TILE_Y_TAG, CanonicalBinary.i64(candidate.sourceTile.y.toLong()))
            field(LATITUDE_TAG, CanonicalBinary.binary64(candidate.latitude))
            field(LONGITUDE_TAG, CanonicalBinary.binary64(candidate.longitude))
            field(CODEPOINTS_TAG, CanonicalBinary.list(codepoints))
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

private const val LAYER_ID_TAG: Int = 1
private const val SOURCE_TILE_Z_TAG: Int = 2
private const val SOURCE_TILE_X_TAG: Int = 3
private const val SOURCE_TILE_Y_TAG: Int = 4
private const val LATITUDE_TAG: Int = 5
private const val LONGITUDE_TAG: Int = 6
private const val CODEPOINTS_TAG: Int = 7
