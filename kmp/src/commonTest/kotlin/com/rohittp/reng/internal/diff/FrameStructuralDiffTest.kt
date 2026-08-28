package com.rohittp.reng.internal.diff

import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.FramePlanSegment
import com.rohittp.reng.internal.identity.Sha256Digest
import com.rohittp.reng.internal.identity.Sha256Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class FrameStructuralDiffTest {
    @Test
    fun nullBaselineChangesAllEightSegmentsInTagOrder() {
        val current = FramePlanCanonicalEncoder().encode(minimalPlan(0))

        assertEquals(
            allSegments,
            FrameStructuralDiffer.diff(previous = null, current = current).changedSegments,
        )
    }

    @Test
    fun equalFramesHaveNoChangedSegments() {
        val encoder = FramePlanCanonicalEncoder()
        val previous = encoder.encode(minimalPlan(0))
        val current = encoder.encode(minimalPlan(0))

        assertEquals(emptyList(), FrameStructuralDiffer.diff(previous, current).changedSegments)
    }

    @Test
    fun diffComparesSegmentBytesRatherThanCollidingFrameHashes() {
        val encoder = FramePlanCanonicalEncoder(CONSTANT_SHA256)
        val previous = encoder.encode(minimalPlan(0))
        val current = encoder.encode(minimalPlan(1))

        assertEquals(previous.identity.digest, current.identity.digest)
        assertNotEquals(previous.segmentPayloads, current.segmentPayloads)
        assertEquals(
            listOf(FramePlanSegment.FRAME_INDEX),
            FrameStructuralDiffer.diff(previous, current).changedSegments,
        )
    }

    /**
     * `drawLabels` is not merely present in the tag order — it is the only segment two plans differing
     * only in that flag may report. A structural diff that missed it would return an empty change list
     * for two visibly different frames, which is the diff answering "nothing to redo".
     */
    @Test
    fun twoPlansDifferingOnlyByDrawLabelsChangeExactlyThatSegment() {
        val encoder = FramePlanCanonicalEncoder()
        val previous = encoder.encode(minimalPlan(0))
        val current = encoder.encode(minimalPlan(0, drawLabels = false))

        assertEquals(
            listOf(FramePlanSegment.DRAW_LABELS),
            FrameStructuralDiffer.diff(previous, current).changedSegments,
        )
    }

    @Test
    fun changedSegmentResultsSnapshotListsAndUseStructuralValueSemantics() {
        val input = mutableListOf(FramePlanSegment.CAMERA, FramePlanSegment.MODELS)
        val first = FrameStructuralDiff(input)
        val equal = FrameStructuralDiff(listOf(FramePlanSegment.CAMERA, FramePlanSegment.MODELS))
        val different = FrameStructuralDiff(listOf(FramePlanSegment.CAMERA))

        input.clear()
        (first.changedSegments as MutableList<FramePlanSegment>).clear()

        assertEquals(equal, first)
        assertEquals(equal.hashCode(), first.hashCode())
        assertNotEquals(different, first)
        assertEquals(listOf(FramePlanSegment.CAMERA, FramePlanSegment.MODELS), first.changedSegments)
        assertFalse(first.changedSegments === first.changedSegments)
    }

    private fun minimalPlan(frameIndex: Long, drawLabels: Boolean = true): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
        drawLabels = drawLabels,
    )

    private companion object {
        val allSegments: List<FramePlanSegment> = listOf(
            FramePlanSegment.FRAME_INDEX,
            FramePlanSegment.CAMERA,
            FramePlanSegment.PROJECTION_MODE,
            FramePlanSegment.DRAW_BASEMAP,
            FramePlanSegment.STICKERS,
            FramePlanSegment.MODELS,
            FramePlanSegment.GEOMETRIES,
            FramePlanSegment.DRAW_LABELS,
        )

        val CONSTANT_SHA256: Sha256Function = Sha256Function {
            Sha256Digest(ByteArray(32) { 0x5a })
        }
    }
}
