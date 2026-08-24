package com.rohittp.reng.internal.model

import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.internal.glb.GltfAnimation
import com.rohittp.reng.internal.glb.GltfDocument
import com.rohittp.reng.internal.math.DoubleQuaternion
import com.rohittp.reng.internal.math.DoubleVector3

/** The one `interpolation` that holds a keyframe's value until the next one instead of blending
 * toward it. `LINEAR` is glTF's default and the only other value `validateGltfFeatures` admits --
 * `CUBICSPLINE` is refused as [com.rohittp.reng.internal.glb.GltfUnsupported.INTERPOLATION] -- so
 * "not `STEP`" is a complete statement of the remaining case rather than a fallback. */
private const val STEP_INTERPOLATION = "STEP"

/** Components in a `translation` or `scale` keyframe, and in a `rotation` one. */
private const val VECTOR_COMPONENTS = 3
private const val QUATERNION_COMPONENTS = 4

/**
 * The three node properties an animation channel can drive, carrying the component count of the
 * sampler output that drives them. A resolved [AnimatedPath] is what lets the dispatch below be
 * total with no `else` branch guessing at a path no table knows -- the same shape
 * [BinChunk]'s own `ComponentFormat` uses, for the same reason.
 *
 * `weights` is absent because morph-target animation is outside RenG's subset
 * ([com.rohittp.reng.internal.glb.GltfUnsupported.ANIMATION_TARGET_PATH]).
 */
private enum class AnimatedPath(val componentsPerKeyframe: Int) {
    TRANSLATION(VECTOR_COMPONENTS),
    ROTATION(QUATERNION_COMPONENTS),
    SCALE(VECTOR_COMPONENTS),
}

private fun animatedPath(targetPath: String): AnimatedPath? = when (targetPath) {
    "translation" -> AnimatedPath.TRANSLATION
    "rotation" -> AnimatedPath.ROTATION
    "scale" -> AnimatedPath.SCALE
    else -> null
}

/**
 * What one [com.rohittp.reng.Model]'s list of [AnimationTrack]s resolves to against the GLB's own
 * animation catalogue.
 *
 * [Missing] and [Duplicate] are separate outcomes because `CONTEXT.md` states two separate rules --
 * "Preparation rejects a missing or out-of-range selector, **or** different selectors that resolve
 * to the same animation" -- even though the caller reports both as one error code. Keeping them
 * apart is what lets a test say which rule fired.
 */
internal sealed interface AnimationResolution {
    /** One animation index per track, in the track list's own order. */
    data class Resolved(val indices: List<Int>) : AnimationResolution

    data object Missing : AnimationResolution

    data object Duplicate : AnimationResolution
}

/**
 * Each of [tracks]' selectors resolved to an index into `document.animations`, in track order.
 *
 * A [AnimationSelector.Name] matches by exact Unicode scalar sequence: no normalization, no
 * trimming, no case folding, exactly as `CONTEXT.md` defines the term. `parseGltf` already rejects
 * a catalogue with duplicate non-blank names ([com.rohittp.reng.internal.glb.GltfReject]
 * `DUPLICATE_ANIMATION_NAME`), so the first exact match is the only match.
 *
 * A [AnimationSelector.Index] is a non-negative `Long` -- the public type's own invariant -- and is
 * [AnimationResolution.Missing] rather than an arithmetic accident when it names no animation,
 * however far past `Int` range it reaches.
 */
internal fun resolveAnimationSelectors(document: GltfDocument, tracks: List<AnimationTrack>): AnimationResolution {
    val indices = ArrayList<Int>(tracks.size)

    for (track in tracks) {
        val index = when (val selector = track.animation) {
            is AnimationSelector.Name ->
                document.animations.indexOfFirst { it.name == selector.value }.takeIf { it >= 0 }

            is AnimationSelector.Index ->
                selector.value.takeIf { it >= 0L && it < document.animations.size.toLong() }?.toInt()
        } ?: return AnimationResolution.Missing
        indices += index
    }

    if (indices.toSet().size != indices.size) return AnimationResolution.Duplicate
    return AnimationResolution.Resolved(indices)
}

/**
 * How long `document.animations[animationIndex]` runs: the largest keyframe time across *every* one
 * of its samplers, not just the one a caller happens to look at first. Two samplers of one animation
 * may cover different spans, and the animation is over only when the last of them is.
 *
 * This is why duration needs [bin]. The document declares how many keyframes a sampler has and never
 * what times they hold, so the answer is only in the BIN chunk's bytes.
 *
 * `null` on any content fault -- an animation index naming no animation, a keyframe accessor that
 * cannot be read, or a keyframe sequence that is not strictly increasing. An animation with no
 * samplers at all has duration `0.0`, which is not a fault: it drives nothing.
 */
internal fun animationDurationSeconds(document: GltfDocument, bin: BinChunk, animationIndex: Int): Double? {
    val animation = document.animations.getOrNull(animationIndex) ?: return null
    return durationOf(bin.readEverySamplersKeyframeTimes(document, animation) ?: return null)
}

/**
 * Every [tracks] entry sampled at its own time and merged into one complete [NodeTrs] per animated
 * node, ready to hand to [composeGlobalTransforms] as its `overrides`. Nodes no channel targets are
 * absent from the map and compose from their own authored transform.
 *
 * [resolved] must be [resolveAnimationSelectors]' result over the same [document] and [tracks];
 * anything else is `null` rather than a partial sampling of whichever entries happened to line up.
 *
 * Three rules from `CONTEXT.md` and the specification decide what a track contributes:
 *
 * - **Time wraps by duration.** A positive-duration animation samples `timeSeconds % durationSeconds`
 *   and a zero-duration one samples time zero, which is also what keeps the modulo away from a
 *   division by zero.
 * - **Each sampler clamps into its own `[first, last]` keyframe range, independently.** A sampler
 *   whose first keyframe is at `t = 10` holds that value from `t = 0`, and a sibling sampler
 *   starting at `t = 0` does not; the same holds at the far end. The clamp is never against the
 *   animation's overall span, which would silently stretch one sampler over another's range.
 * - **Tracks apply in list order.** A later track driving the same node and path overwrites an
 *   earlier one; a later track driving a *different* path of the same node copies the earlier
 *   override forward rather than discarding it.
 *
 * A track driving only one of a node's three components still produces a complete [NodeTrs], by
 * copying the other two from [localNodeTrs] -- the contract [NodeTrs]' own KDoc states. That read is
 * `!!` because `parseGltf` refuses a `matrix` node any channel targets
 * ([com.rohittp.reng.internal.glb.GltfReject.ANIMATED_NODE_MATRIX]), which is the gate that makes
 * the authored TRS guaranteed to exist here.
 *
 * A channel with no `target.node` is legal glTF, specified as a no-op, and skipped silently. A
 * channel that *does* name a node, and the sampler it names, are both indexed without a bound check
 * of their own: `parseGltf` resolves every index reference against the array it indexes into before
 * a [GltfDocument] exists at all.
 *
 * `null` on any content fault, exactly as [BinChunk]'s own readers report one: a keyframe or output
 * accessor that cannot be read, a keyframe sequence that is not strictly increasing, an output run
 * that does not hold one value per keyframe, or a target path outside the three RenG plays. The
 * caller turns `null` into a typed `RESOURCE_PARSE_FAILED`; this file never throws and never repairs.
 */
internal fun sampleAnimationTracks(
    document: GltfDocument,
    bin: BinChunk,
    tracks: List<AnimationTrack>,
    resolved: AnimationResolution.Resolved,
): Map<Int, NodeTrs>? {
    // `require`, not a `null` return: a [resolved] that does not correspond to [tracks] is a caller
    // bug, and this function's `null` means "this GLB's animation data is unreadable", which the
    // caller reports as `RESOURCE_PARSE_FAILED`. Conflating the two would blame a perfectly good
    // model for a wiring mistake one layer up -- the same distinction
    // `internal.gl.requireResolvedAtDrawTime` draws between a content failure and a contract
    // violation. Every other exit from this function stays a `null`.
    require(resolved.indices.size == tracks.size) {
        "resolved animation indices must correspond one-to-one with the tracks they were resolved from"
    }
    val overrides = LinkedHashMap<Int, NodeTrs>()

    for ((position, track) in tracks.withIndex()) {
        val animation = document.animations.getOrNull(resolved.indices[position]) ?: return null
        val keyframeTimes = bin.readEverySamplersKeyframeTimes(document, animation) ?: return null
        val duration = durationOf(keyframeTimes)
        val time = if (duration > 0.0) track.timeSeconds % duration else 0.0

        for (channel in animation.channels) {
            val nodeIndex = channel.targetNode ?: continue
            val path = animatedPath(channel.targetPath) ?: return null
            val sampler = animation.samplers[channel.sampler]
            val times = keyframeTimes[channel.sampler]
            val values = bin.readFloatElements(document, sampler.output) ?: return null
            if (values.size != times.size * path.componentsPerKeyframe) return null

            val span = spanAt(times, time)
            val holdsEarlier = sampler.interpolation == STEP_INTERPOLATION || span.lower == span.upper
            val base = overrides[nodeIndex] ?: localNodeTrs(document.nodes[nodeIndex])!!
            overrides[nodeIndex] = when (path) {
                AnimatedPath.TRANSLATION -> base.copy(translation = vectorOver(span, values, holdsEarlier))
                AnimatedPath.SCALE -> base.copy(scale = vectorOver(span, values, holdsEarlier))
                AnimatedPath.ROTATION -> base.copy(rotation = rotationOver(span, values, holdsEarlier))
            }
        }
    }

    return overrides
}

/**
 * Every sampler of [animation]'s keyframe times, in sampler order, or `null` when any of them cannot
 * be read. Read once per track and shared by the duration and the sampling, because both questions
 * are about the same bytes.
 */
private fun BinChunk.readEverySamplersKeyframeTimes(
    document: GltfDocument,
    animation: GltfAnimation,
): List<FloatArray>? {
    val keyframeTimes = ArrayList<FloatArray>(animation.samplers.size)
    for (sampler in animation.samplers) keyframeTimes += readKeyframeTimes(document, sampler.input) ?: return null
    return keyframeTimes
}

/**
 * One sampler's `input` accessor read as keyframe times, or `null` when it cannot be read as one.
 *
 * The strictly-increasing rule is enforced here and nowhere earlier because it is the one animation
 * fault that needs the bytes: `PARSE_GLB` compares a sampler's two accessor *counts* and never sees
 * the times themselves. A sequence that repeats or goes backwards has no bracketing pair for a time
 * between them -- the answer would depend on which way a search happened to fall.
 *
 * A keyframe time must also be finite. `NaN` is unordered against everything including itself and so
 * fails the comparison already; an infinity passes it while leaving the fraction between two
 * keyframes undefined (`inf / inf`), which would surface as a `NaN` transform far downstream of the
 * keyframe that caused it -- the same reasoning [com.rohittp.reng.internal.math.DoubleMatrix3.inverse]
 * gives for refusing a near-singular matrix rather than dividing by it.
 */
private fun BinChunk.readKeyframeTimes(document: GltfDocument, accessorIndex: Int): FloatArray? {
    val times = readFloatElements(document, accessorIndex) ?: return null
    if (times.isEmpty()) return null
    for (index in times.indices) {
        if (!times[index].isFinite()) return null
        if (index > 0 && !(times[index] > times[index - 1])) return null
    }
    return times
}

/** The largest keyframe time anywhere in the animation. An animation with no samplers runs for no
 * time at all, which the caller then samples at time zero. */
private fun durationOf(keyframeTimes: List<FloatArray>): Double =
    keyframeTimes.maxOfOrNull { it[it.size - 1].toDouble() } ?: 0.0

/**
 * Where a sampled time falls in one sampler's keyframes: the two bracketing keyframe indices and how
 * far between them the time sits. [lower] equals [upper] when the time is at or outside one end of
 * the sampler's own range, which is how the per-sampler clamp is expressed -- a clamped time is
 * *held* at that keyframe rather than extrapolated past it.
 */
private class KeyframeSpan(val lower: Int, val upper: Int, val fraction: Double)

private fun spanAt(times: FloatArray, time: Double): KeyframeSpan {
    val last = times.size - 1
    if (time <= times[0].toDouble()) return KeyframeSpan(0, 0, 0.0)
    if (time >= times[last].toDouble()) return KeyframeSpan(last, last, 0.0)

    val lower = bracketingKeyframe(times, time)
    val lowerTime = times[lower].toDouble()
    val upperTime = times[lower + 1].toDouble()
    return KeyframeSpan(lower, lower + 1, (time - lowerTime) / (upperTime - lowerTime))
}

/**
 * The largest keyframe index whose time is at or before [time], by binary search. Called only when
 * [time] lies strictly inside `times`' own range, so the answer is always in `0..times.size - 2` and
 * `lower + 1` always addresses a keyframe.
 */
private fun bracketingKeyframe(times: FloatArray, time: Double): Int {
    var low = 0
    var high = times.size - 2
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (times[middle].toDouble() <= time) low = middle else high = middle - 1
    }
    return low
}

private fun vectorOver(span: KeyframeSpan, values: FloatArray, holdsEarlier: Boolean): DoubleVector3 {
    val lower = vectorAt(values, span.lower)
    if (holdsEarlier) return lower
    return lower + (vectorAt(values, span.upper) - lower) * span.fraction
}

/**
 * A rotation channel interpolates on the sphere, never component by component: a componentwise blend
 * of two orientations more than a little apart lands off the unit sphere and, renormalized, at the
 * wrong angle -- a quarter of the way from 0 to 180 degrees it gives about 36.9 degrees instead of
 * 45. [DoubleQuaternion.slerp] also owns the shortest-arc sign fix and the near-parallel fallback.
 *
 * A keyframe's own quaternion is returned exactly as authored when the time lands on or outside it;
 * only an interpolated one is renormalized, by `slerp` itself. Nothing here rescales a stored
 * quaternion, because the zero quaternion has no direction to normalize toward and this file never
 * throws.
 */
private fun rotationOver(span: KeyframeSpan, values: FloatArray, holdsEarlier: Boolean): DoubleQuaternion {
    val lower = quaternionAt(values, span.lower)
    if (holdsEarlier) return lower
    return DoubleQuaternion.slerp(lower, quaternionAt(values, span.upper), span.fraction)
}

private fun vectorAt(values: FloatArray, keyframe: Int): DoubleVector3 {
    val first = keyframe * VECTOR_COMPONENTS
    return DoubleVector3(values[first].toDouble(), values[first + 1].toDouble(), values[first + 2].toDouble())
}

/** glTF stores a rotation keyframe as `[x, y, z, w]`, the same order [DoubleQuaternion]'s own
 * constructor takes, so the four values pass straight through with no reordering. */
private fun quaternionAt(values: FloatArray, keyframe: Int): DoubleQuaternion {
    val first = keyframe * QUATERNION_COMPONENTS
    return DoubleQuaternion(
        x = values[first].toDouble(),
        y = values[first + 1].toDouble(),
        z = values[first + 2].toDouble(),
        w = values[first + 3].toDouble(),
    )
}
