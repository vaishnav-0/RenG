package com.rohittp.reng.internal.label

import com.rohittp.reng.internal.gl.ResolvedGlyphQuad
import com.rohittp.reng.internal.gl.ResolvedLabelPaint
import com.rohittp.rentile.LabelCandidateBatch

/**
 * ADR 0035's label fade: the **only** thing about labels that crosses a frame boundary.
 *
 * Which labels are placed is recomputed from scratch on every frame, purely from
 * `(candidates, camera, output size)` -- [placeLabels] carries nothing and remembers nothing. What is
 * carried is one number per label: how far through its fade it is. A placed label eases toward
 * opaque, an absent one eases back toward transparent, and [LabelIdentity] is what decides whether
 * the label in front of us is the one the number belongs to.
 *
 * **The ease advances per successful `prepare()`, and the consequence is stated rather than hidden:
 * a fade's duration in seconds is a function of the consumer's frame rate.** [LABEL_FADE_STEPS]
 * steps is 167 ms at 60 fps and 333 ms at 30 fps. `FramePlan` carries `frameIndex` and no wall-clock
 * time, and `prepare()` must not read a clock: a Prepared Frame may be drawn repeatedly and drawing
 * never changes history, so preparation has to be reproducible, and `runModelReadbackSuite`'s
 * repeatability case is the executable guard on that. A `FramePlan.timeSeconds` field was available
 * and was rejected in the ADR -- a `0.0` default would make fade silently inert for every consumer
 * who did not thread a clock, which is the worse failure of the two.
 *
 * **Collision resolves during `prepare()` and never during `draw()`, and this file is where that is
 * easiest to break.** Nothing here may be called from a draw path: a frame drawn twice would fade
 * twice, and the fade rate would become a function of how often the consumer redraws.
 *
 * **Fading out is state, not paint, and that is a limit worth knowing.** A label that loses its
 * place is not laid out, so there are no quads to draw it with, and ADR 0035 forbids the alternative
 * outright -- keeping last frame's geometry to fade it out with would be carrying placement across
 * frames, which is the thing the ADR rejects. So the decay below buys the case that actually
 * flickers: a label that oscillates in and out of placement resumes from where it left off rather
 * than restarting at zero, so a pop becomes a dip. A label gone for longer than the whole fade is
 * dropped and starts over.
 */
internal const val LABEL_FADE_STEPS: Int = 10

/**
 * One opacity per label, as a step count in `1..`[LABEL_FADE_STEPS] -- an integer rather than a
 * `Float` so that ten advances land exactly on 1.0 instead of near it, and so that two states
 * compare exactly.
 *
 * **Self-bounding, which is why no public ceiling is owed for it.** An entry survives a frame only
 * if its label was placed in that frame or is still mid-fade; an entry that reaches zero is dropped.
 * The map is therefore O(labels in view) plus a tail no longer than [LABEL_FADE_STEPS] frames of
 * churn, never O(labels ever seen), and a long pan cannot grow it. ADR 0035 states the rule as
 * "a current candidate or still mid-fade"; dropping at zero is *tighter* than that and loses
 * nothing, because zero is what an absent identity already reads as.
 */
internal class LabelFadeState internal constructor(private val steps: Map<LabelIdentity, Int>) {
    /** How many labels this state remembers. The self-bounding invariant is asserted on this. */
    internal val entryCount: Int
        get() = steps.size

    /** Zero for an identity this state has never seen, which is where every fade starts. */
    internal fun stepOf(identity: LabelIdentity): Int = steps[identity] ?: 0

    /** Every carried entry one step nearer zero, with the entries that reached it dropped. */
    internal fun decayed(): MutableMap<LabelIdentity, Int> {
        val next = HashMap<LabelIdentity, Int>(steps.size)
        for ((identity, step) in steps) {
            val decayed = step - 1
            if (decayed > 0) next[identity] = decayed
        }
        return next
    }

    internal companion object {
        /**
         * No label has ever been seen. This is what `clearFrameHistory()` restores, and ADR 0035's
         * reconciliation with the purity contract rests on it: after that call the render is a pure
         * function of the plan again.
         */
        internal val EMPTY: LabelFadeState = LabelFadeState(emptyMap())
    }
}

/**
 * One placed label and the opacity this frame draws it at, with [quads] already carrying it.
 *
 * [opacity] is the fade alone, in `[0, 1]`; the style's own `text-opacity` is already inside
 * [PlacedLabel]'s paints and the two are multiplied into [quads].
 */
internal class FadedLabel(
    val label: PlacedLabel,
    val opacity: Float,
    val quads: List<ResolvedGlyphQuad>,
)

/** This frame's faded labels and the Frame History entry the next frame reads. */
internal class LabelFadeAdvance(
    val labels: List<FadedLabel>,
    val nextState: LabelFadeState,
)

/**
 * Advances every carried fade by one step and returns [placed] with this frame's opacities.
 *
 * Pure, and its state argument is inert data: [previous] is read and never written, exactly as
 * `observeMercatorLod` takes `previousSelectedLod`. Calling this twice with the same arguments
 * returns the same answer twice, which is what lets the caller commit the result only after every
 * fallible step of `prepare()` has succeeded -- a `prepare` that failed or was cancelled advances no
 * label's opacity, exactly as it advances no LOD.
 *
 * [batch] is the batch [placed] came out of, or `null` on a frame that placed nothing at all -- one
 * with `drawLabels = false`, or one whose style declares no text. Every carried entry still decays
 * on such a frame, because a frame that draws no labels is a frame in which every label is absent.
 *
 * **Only the placed labels are hashed.** The retention rule needs no identity for an unplaced
 * candidate: such a candidate decays like any other absent label, and an entry that reaches zero is
 * dropped whether or not it is still a candidate. So the per-frame cost is O(labels placed) rather
 * than O(candidates), which matters because E5 ships no ceiling on the candidate count and task 17
 * has not measured one yet.
 *
 * **Two placed labels that derive one identity advance it once.** The raised step is computed from
 * [previous] rather than from the map being built, so a feature that a source duplicates -- or one
 * that a tile buffer repeats -- gets one entry, one advance, and the same opacity on both copies,
 * instead of a fade that runs twice as fast on the labels that happen to be duplicated.
 */
internal fun advanceLabelFade(
    previous: LabelFadeState,
    batch: LabelCandidateBatch?,
    placed: List<PlacedLabel>,
): LabelFadeAdvance {
    val next = previous.decayed()
    val faded = ArrayList<FadedLabel>(placed.size)
    for (label in placed) {
        val identity = batch?.let { deriveLabelIdentity(it, label.candidateIndex) }
        if (identity == null) {
            // A label whose identity this batch cannot describe is drawn exactly as every label was
            // drawn before fade existed. It joins no entry, so it cannot leak one either.
            faded += FadedLabel(label, UNFADED, label.quads)
            continue
        }
        val raised = minOf(previous.stepOf(identity) + 1, LABEL_FADE_STEPS)
        next[identity] = raised
        val opacity = raised.toFloat() / LABEL_FADE_STEPS.toFloat()
        faded += FadedLabel(label, opacity, fadedQuads(label.quads, opacity))
    }
    return LabelFadeAdvance(labels = faded, nextState = LabelFadeState(next))
}

/**
 * [quads] with [opacity] multiplied into each paint's own `opacity`, which is the field
 * `ResolvedLabelPaint` folds into both the text and the halo alpha at vertex assembly -- so one
 * multiplication fades the halo with the glyph rather than leaving a halo standing around a
 * disappearing letter.
 *
 * A fully faded-in label is returned untouched, which is the ordinary case once a camera settles.
 * The paints of one label are the same instance across its glyphs unless the text size changes
 * mid-label, so the faded copy is memoized on the source instance rather than rebuilt per glyph.
 */
private fun fadedQuads(quads: List<ResolvedGlyphQuad>, opacity: Float): List<ResolvedGlyphQuad> {
    if (opacity >= UNFADED) return quads
    var source: ResolvedLabelPaint? = null
    var faded: ResolvedLabelPaint? = null
    return quads.map { quad ->
        if (quad.paint !== source) {
            source = quad.paint
            faded = ResolvedLabelPaint(
                textColour = quad.paint.textColour,
                haloColour = quad.paint.haloColour,
                opacity = quad.paint.opacity * opacity,
                haloWidthPixels = quad.paint.haloWidthPixels,
                haloBlurPixels = quad.paint.haloBlurPixels,
                scale = quad.paint.scale,
            )
        }
        ResolvedGlyphQuad(
            cornersXy = quad.cornersXy,
            cornersUv = quad.cornersUv,
            paint = requireNotNull(faded) { "the faded paint is assigned before it is read" },
        )
    }
}

private const val UNFADED: Float = 1.0f
