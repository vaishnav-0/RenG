package com.rohittp.reng.internal.planning

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.FramePlan
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.Placement
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.geometriesForCore
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.projection.ClosedMercatorFootprint
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.clippedPhysicalPixelFootprint
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import com.rohittp.reng.internal.shader.ShaderProfilePlan
import com.rohittp.reng.internal.shader.scanShaderProfile
import com.rohittp.reng.modelsForCore
import com.rohittp.reng.stickersForCore

internal sealed interface DrawnThingReference {
    data class StickerAt(val index: Int) : DrawnThingReference

    data class ModelAt(val index: Int) : DrawnThingReference
}

internal data class ResolvedDrawnThing(
    val reference: DrawnThingReference,
    val placement: ResolvedPlacement,
)

internal class MercatorSpatialPlan(
    val camera: ResolvedMercatorCamera,
    val lodObservation: LodObservation,
    val footprint: ClosedMercatorFootprint?,
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
        other is MercatorSpatialPlan &&
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

internal fun planMercatorSpatial(
    plan: FramePlan,
    outputPixelSize: OutputPixelSize,
    previousSelectedLod: Int?,
    maximumBasemapTileInstances: Int,
    basemapStyleConfigured: Boolean,
): SpatialOutcome<MercatorSpatialPlan> {
    val cameraOutcome = resolveMercatorCamera(plan.camera, outputPixelSize)
    if (cameraOutcome is SpatialOutcome.Failure) return cameraOutcome
    val camera = (cameraOutcome as SpatialOutcome.Success).value
    val lodObservation = observeMercatorLod(plan.camera.zoom, previousSelectedLod)

    var footprint: ClosedMercatorFootprint? = null
    var tileSelection: TileSelectionOutcome.Success? = null
    if (plan.drawBasemap && basemapStyleConfigured) {
        footprint = clippedPhysicalPixelFootprint(camera)
        when (
            val selection = selectBasemapTiles(
                footprint = footprint,
                lod = lodObservation.selectedLod,
                maximumInstances = maximumBasemapTileInstances,
            )
        ) {
            is TileSelectionOutcome.OverBudget -> return basemapTileBudgetFailure(selection)
            is TileSelectionOutcome.Success -> tileSelection = selection
        }
    }

    val mapEntries = ArrayList<ResolvedDrawnThing>()
    val screenEntries = ArrayList<ResolvedDrawnThing>()
    for ((index, sticker) in plan.stickersForCore().withIndex()) {
        val outcome = resolveDrawnThing(DrawnThingReference.StickerAt(index), sticker.placement, camera)
        if (outcome is SpatialOutcome.Failure) return outcome
        appendByDrawRegime((outcome as SpatialOutcome.Success).value, mapEntries, screenEntries)
    }
    for ((index, model) in plan.modelsForCore().withIndex()) {
        // ADR 0029: a SCREEN-positioned Model is refused before acquisition or drawing, never
        // substituted. screenOrthographicProjection (internal/gl/SceneContent.kt) has no z row at
        // all, so a volumetric mesh drawn there would show its back faces through its front ones --
        // a silently wrong picture rather than a missing one. Only a Model's SCREEN *position* is
        // refused: a SCREEN-positioned Sticker is flat and has no interior to occlude, and a SCREEN
        // rotation or scale over a MAP position is the billboard ADR 0029 explicitly keeps working.
        if (model.placement.positionMode == AnchoringMode.SCREEN) {
            return screenPositionedModelFailure()
        }
        val outcome = resolveDrawnThing(DrawnThingReference.ModelAt(index), model.placement, camera)
        if (outcome is SpatialOutcome.Failure) return outcome
        appendByDrawRegime((outcome as SpatialOutcome.Success).value, mapEntries, screenEntries)
    }
    screenEntries.sortWith(screenCompositingOrder)

    val geometries = ArrayList<ResolvedGeometry>()
    val shaderProfiles = ArrayList<Pair<ShaderProfilePlan, ShaderProfilePlan>>()
    for (geometry in plan.geometriesForCore()) {
        val geometryOutcome = resolveGeometry(geometry, camera)
        if (geometryOutcome is SpatialOutcome.Failure) return geometryOutcome
        val vertexProfile = scanShaderProfile(geometry.shaderPair.vertexSource) ?: return shaderProfileFailure()
        val fragmentProfile = scanShaderProfile(geometry.shaderPair.fragmentSource) ?: return shaderProfileFailure()
        geometries += (geometryOutcome as SpatialOutcome.Success).value
        shaderProfiles += vertexProfile to fragmentProfile
    }

    return SpatialOutcome.Success(
        MercatorSpatialPlan(
            camera = camera,
            lodObservation = lodObservation,
            footprint = footprint,
            tileSelection = tileSelection,
            mapEntries = mapEntries,
            screenEntries = screenEntries,
            geometries = geometries,
            shaderProfiles = shaderProfiles,
        ),
    )
}

private fun resolveDrawnThing(
    reference: DrawnThingReference,
    placement: Placement,
    camera: ResolvedMercatorCamera,
): SpatialOutcome<ResolvedDrawnThing> = when (val outcome = resolvePlacement(placement, camera)) {
    is SpatialOutcome.Failure -> outcome
    is SpatialOutcome.Success -> SpatialOutcome.Success(ResolvedDrawnThing(reference, outcome.value))
}

private fun appendByDrawRegime(
    entry: ResolvedDrawnThing,
    mapEntries: MutableList<ResolvedDrawnThing>,
    screenEntries: MutableList<ResolvedDrawnThing>,
) {
    when (entry.placement.drawRegime) {
        DrawRegime.MAP_OCCLUDED -> mapEntries += entry
        DrawRegime.SCREEN_COMPOSITED -> screenEntries += entry
    }
}

private fun basemapTileBudgetFailure(
    overBudget: TileSelectionOutcome.OverBudget,
): SpatialOutcome.Failure = SpatialOutcome.Failure(
    FailureDescriptor(
        code = RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
        stage = PipelineStage.FRAME_PLANNING,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.BASEMAP_TILE_INSTANCES,
            limit = overBudget.limit.toLong(),
            actual = overBudget.actual,
        ),
    ),
)

private fun screenPositionedModelFailure(): SpatialOutcome.Failure = SpatialOutcome.Failure(
    FailureDescriptor(
        code = RenGErrorCode.UNSUPPORTED_ANCHORING_MODE,
        stage = PipelineStage.FRAME_PLANNING,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.PLACEMENT_POSITION_MODE,
        ),
    ),
)

private fun shaderProfileFailure(): SpatialOutcome.Failure = SpatialOutcome.Failure(
    FailureDescriptor(
        code = RenGErrorCode.INVALID_VALUE,
        stage = PipelineStage.FRAME_PLANNING,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.SHADER_PAIR,
        ),
    ),
)

private val screenCompositingOrder: Comparator<ResolvedDrawnThing> =
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
