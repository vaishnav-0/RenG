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

internal fun planMercatorSpatial(
    plan: FramePlan,
    outputPixelSize: OutputPixelSize,
    previousSelectedLod: Int?,
    maximumBasemapTileInstances: Int,
    basemapStyleConfigured: Boolean,
): SpatialOutcome<FrameSpatialPlan> {
    val cameraOutcome = resolveMercatorCamera(plan.camera, outputPixelSize)
    if (cameraOutcome is SpatialOutcome.Failure) return cameraOutcome
    val camera = (cameraOutcome as SpatialOutcome.Success).value
    val lodObservation = observeMercatorLod(plan.camera.zoom, previousSelectedLod)

    var footprint: ClosedMercatorFootprint? = null
    var tileSelection: TileSelectionOutcome.Success? = null
    // E-labels task 8b: the footprint and the tile selection are the *frame's*, not the ground's.
    // `drawLabels` is fully orthogonal to `drawBasemap` (FramePlan.drawLabels), and the label handover
    // takes its own tile list -- `BasemapEngineHost.acquireLabelCandidates` is handed exactly these
    // canonical tiles -- so a `drawBasemap = false, drawLabels = true` frame needs this selection just
    // as much as a ground-drawing one does. Gating it on `drawBasemap` alone made that pairing, which
    // E7 declares legal, silently render nothing at all.
    //
    // Only the *ground draw itself* stays gated on `drawBasemap` alone; that gate lives at the single
    // point where RenGRenderer.prepare decides which canonical tiles the engine rasterizes. Nothing
    // here selects a tile the ground-drawing frame would not have selected: with `drawBasemap = true`
    // the condition is exactly what it was, so the three pairings that already worked are untouched.
    if ((plan.drawBasemap || plan.drawLabels) && basemapStyleConfigured) {
        footprint = clippedPhysicalPixelFootprint(camera)
        when (
            // ADR 0065: one LOD per band rather than one for the frame. `footprint` above stays
            // the whole frame's -- it is what `FrameSpatialPlan` carries and what the label handover
            // reasons about -- while the tiles come from the banded decomposition of the same rays.
            val selection = selectBandedBasemapTiles(
                camera = camera,
                selectedLod = lodObservation.selectedLod,
                maximumBasemapTileInstances = maximumBasemapTileInstances,
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
        FrameSpatialPlan(
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

internal fun basemapTileBudgetFailure(
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

internal fun screenPositionedModelFailure(): SpatialOutcome.Failure = SpatialOutcome.Failure(
    FailureDescriptor(
        code = RenGErrorCode.UNSUPPORTED_ANCHORING_MODE,
        stage = PipelineStage.FRAME_PLANNING,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.PLACEMENT_POSITION_MODE,
        ),
    ),
)

internal fun shaderProfileFailure(): SpatialOutcome.Failure = SpatialOutcome.Failure(
    FailureDescriptor(
        code = RenGErrorCode.INVALID_VALUE,
        stage = PipelineStage.FRAME_PLANNING,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.SHADER_PAIR,
        ),
    ),
)
