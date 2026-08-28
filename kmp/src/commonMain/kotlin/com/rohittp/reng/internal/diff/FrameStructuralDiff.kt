package com.rohittp.reng.internal.diff

import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanSegment

internal class FrameStructuralDiff(
    changedSegments: List<FramePlanSegment>,
) {
    private val changedSegmentSnapshot: List<FramePlanSegment> = ArrayList(changedSegments)

    val changedSegments: List<FramePlanSegment>
        get() = ArrayList(changedSegmentSnapshot)

    override fun equals(other: Any?): Boolean =
        other is FrameStructuralDiff && changedSegmentSnapshot == other.changedSegmentSnapshot

    override fun hashCode(): Int = changedSegmentSnapshot.hashCode()
}

internal object FrameStructuralDiffer {
    internal fun diff(
        previous: EncodedFramePlan?,
        current: EncodedFramePlan,
    ): FrameStructuralDiff {
        if (previous == null) {
            return FrameStructuralDiff(segmentsInTagOrder)
        }

        val previousPayloads = previous.segmentPayloads
        val currentPayloads = current.segmentPayloads
        require(previousPayloads.size == segmentsInTagOrder.size)
        require(currentPayloads.size == segmentsInTagOrder.size)
        return FrameStructuralDiff(
            segmentsInTagOrder.filterIndexed { index, _ ->
                previousPayloads[index] != currentPayloads[index]
            },
        )
    }

    private val segmentsInTagOrder: List<FramePlanSegment> = listOf(
        FramePlanSegment.FRAME_INDEX,
        FramePlanSegment.CAMERA,
        FramePlanSegment.PROJECTION_MODE,
        FramePlanSegment.DRAW_BASEMAP,
        FramePlanSegment.STICKERS,
        FramePlanSegment.MODELS,
        FramePlanSegment.GEOMETRIES,
        FramePlanSegment.DRAW_LABELS,
    )
}
