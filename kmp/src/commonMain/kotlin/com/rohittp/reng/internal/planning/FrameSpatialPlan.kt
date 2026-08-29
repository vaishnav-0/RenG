package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.FrameGroundFootprint
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import com.rohittp.reng.internal.shader.ShaderProfilePlan

internal sealed interface DrawnThingReference {
    data class StickerAt(val index: Int) : DrawnThingReference

    data class ModelAt(val index: Int) : DrawnThingReference
}

internal data class ResolvedDrawnThing(
    val reference: DrawnThingReference,
    val placement: ResolvedPlacement,
)

/**
 * Everything one frame's spatial planning decided, in whichever projection mode decided it.
 *
 * **One class rather than two, and the two mode-dependent fields say why.** [camera] is a
 * [ResolvedFrameCamera] and [footprint] a [FrameGroundFootprint]: both are sealed over the two
 * modes, so a reader who needs to know which mode produced a plan asks the camera and gets an
 * exhaustive `when` rather than a boolean that can disagree with the rest of the plan. Everything
 * else here — the LOD observation, the tile selection, the draw-regime split, the resolved
 * geometries and their shader profiles — is the *same kind of value* in both modes, derived
 * differently, which is exactly the shape that wants one type and two producers
 * ([planMercatorSpatial] and [planGlobeSpatial]) rather than two types.
 *
 * The two producers are genuinely different functions rather than one parameterised by a projection,
 * for the reason Cycle G's plan records about the algorithms underneath them: a globe's visible
 * ground is not the Mercator row scan with a projection substituted, and its tile selection is not
 * "rows times an x-interval" at all.
 */
internal class FrameSpatialPlan(
    val camera: ResolvedFrameCamera,
    val lodObservation: LodObservation,
    val footprint: FrameGroundFootprint?,
    val tileSelection: TileSelectionOutcome.Success?,
    mapEntries: List<ResolvedDrawnThing>,
    screenEntries: List<ResolvedDrawnThing>,
    geometries: List<ResolvedGeometry>,
    shaderProfiles: List<Pair<ShaderProfilePlan, ShaderProfilePlan>>,
) {
    private val mapEntrySnapshot: ArrayList<ResolvedDrawnThing>
    private val screenEntrySnapshot: ArrayList<ResolvedDrawnThing>
    private val geometrySnapshot: ArrayList<ResolvedGeometry>
    private val shaderProfileSnapshot: ArrayList<Pair<ShaderProfilePlan, ShaderProfilePlan>>

    init {
        mapEntrySnapshot = ArrayList(mapEntries)
        screenEntrySnapshot = ArrayList(screenEntries)
        geometrySnapshot = ArrayList(geometries)
        shaderProfileSnapshot = ArrayList(shaderProfiles)

        require((footprint == null) == (tileSelection == null)) {
            "footprint and tileSelection must be jointly absent or present"
        }
        require(geometrySnapshot.size == shaderProfileSnapshot.size) {
            "geometries and shaderProfiles must have equal size"
        }
        require(mapEntrySnapshot.all { it.placement.drawRegime == DrawRegime.MAP_OCCLUDED }) {
            "mapEntries must contain only map-occluded placements"
        }
        require(screenEntrySnapshot.all { it.placement.drawRegime == DrawRegime.SCREEN_COMPOSITED }) {
            "screenEntries must contain only screen-composited placements"
        }
        for (index in 1 until screenEntrySnapshot.size) {
            require(
                screenCompositingOrder.compare(
                    screenEntrySnapshot[index - 1],
                    screenEntrySnapshot[index],
                ) <= 0,
            ) {
                "screenEntries must be in ascending screen compositing order"
            }
        }
        val resolvedReferences = HashSet<DrawnThingReference>()
        for (entry in mapEntrySnapshot) {
            require(resolvedReferences.add(entry.reference)) {
                "each drawn thing must resolve to exactly one draw-regime entry"
            }
        }
        for (entry in screenEntrySnapshot) {
            require(resolvedReferences.add(entry.reference)) {
                "each drawn thing must resolve to exactly one draw-regime entry"
            }
        }
        for (index in geometrySnapshot.indices) {
            val shaderPair = geometrySnapshot[index].shaderPair
            val profiles = shaderProfileSnapshot[index]
            require(
                shaderPair.vertexSource == profiles.first.originalSource &&
                    shaderPair.fragmentSource == profiles.second.originalSource,
            ) {
                "geometry and shader profile sources must correspond by index"
            }
        }
    }

    val mapEntries: List<ResolvedDrawnThing>
        get() = ArrayList(mapEntrySnapshot)
    val screenEntries: List<ResolvedDrawnThing>
        get() = ArrayList(screenEntrySnapshot)
    val geometries: List<ResolvedGeometry>
        get() = ArrayList(geometrySnapshot)
    val shaderProfiles: List<Pair<ShaderProfilePlan, ShaderProfilePlan>>
        get() = ArrayList(shaderProfileSnapshot)

    override fun equals(other: Any?): Boolean =
        other is FrameSpatialPlan &&
            camera == other.camera &&
            lodObservation == other.lodObservation &&
            footprint == other.footprint &&
            tileSelection == other.tileSelection &&
            mapEntrySnapshot == other.mapEntrySnapshot &&
            screenEntrySnapshot == other.screenEntrySnapshot &&
            geometrySnapshot == other.geometrySnapshot &&
            shaderProfileSnapshot.structurallyEquals(other.shaderProfileSnapshot)

    override fun hashCode(): Int {
        var result = camera.hashCode()
        result = 31 * result + lodObservation.hashCode()
        result = 31 * result + (footprint?.hashCode() ?: 0)
        result = 31 * result + (tileSelection?.hashCode() ?: 0)
        result = 31 * result + mapEntrySnapshot.hashCode()
        result = 31 * result + screenEntrySnapshot.hashCode()
        result = 31 * result + geometrySnapshot.hashCode()
        result = 31 * result + shaderProfileSnapshot.structuralHashCode()
        return result
    }
}

internal val screenCompositingOrder: Comparator<ResolvedDrawnThing> =
    compareBy<ResolvedDrawnThing> { requireNotNull(it.placement.screenCompositeZ) }
        .thenBy { it.reference.typeOrder }
        .thenBy { it.reference.sourceIndex }

private val DrawnThingReference.typeOrder: Int
    get() = when (this) {
        is DrawnThingReference.StickerAt -> 0
        is DrawnThingReference.ModelAt -> 1
    }

private val DrawnThingReference.sourceIndex: Int
    get() = when (this) {
        is DrawnThingReference.StickerAt -> index
        is DrawnThingReference.ModelAt -> index
    }

internal fun appendByDrawRegime(
    entry: ResolvedDrawnThing,
    mapEntries: MutableList<ResolvedDrawnThing>,
    screenEntries: MutableList<ResolvedDrawnThing>,
) {
    when (entry.placement.drawRegime) {
        DrawRegime.MAP_OCCLUDED -> mapEntries += entry
        DrawRegime.SCREEN_COMPOSITED -> screenEntries += entry
    }
}

private fun List<Pair<ShaderProfilePlan, ShaderProfilePlan>>.structurallyEquals(
    other: List<Pair<ShaderProfilePlan, ShaderProfilePlan>>,
): Boolean {
    if (size != other.size) return false
    return indices.all { index ->
        this[index].first.structurallyEquals(other[index].first) &&
            this[index].second.structurallyEquals(other[index].second)
    }
}

private fun List<Pair<ShaderProfilePlan, ShaderProfilePlan>>.structuralHashCode(): Int {
    var result = 1
    for ((vertex, fragment) in this) {
        val pairHash = 31 * vertex.structuralHashCode() + fragment.structuralHashCode()
        result = 31 * result + pairHash
    }
    return result
}

private fun ShaderProfilePlan.structurallyEquals(other: ShaderProfilePlan): Boolean =
    originalSource == other.originalSource &&
        directiveStartUtf16 == other.directiveStartUtf16 &&
        directiveEndExclusiveUtf16 == other.directiveEndExclusiveUtf16

private fun ShaderProfilePlan.structuralHashCode(): Int {
    var result = originalSource.hashCode()
    result = 31 * result + directiveStartUtf16
    result = 31 * result + directiveEndExclusiveUtf16
    return result
}
