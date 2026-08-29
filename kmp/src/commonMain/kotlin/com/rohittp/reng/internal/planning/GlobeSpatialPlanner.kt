package com.rohittp.reng.internal.planning

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.FramePlan
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.Placement
import com.rohittp.reng.geometriesForCore
import com.rohittp.reng.internal.projection.GlobeGroundFootprint
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.globeGroundFootprint
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.shader.ShaderProfilePlan
import com.rohittp.reng.internal.shader.scanShaderProfile
import com.rohittp.reng.modelsForCore
import com.rohittp.reng.stickersForCore

/**
 * [planMercatorSpatial]'s globe arm: the same [FrameSpatialPlan], derived on a sphere.
 *
 * ## The three substitutions, and the one that is deliberately *not* made
 *
 * The camera is [resolveGlobeCamera]'s, the footprint is [globeGroundFootprint]'s and the tiles are
 * [selectGlobeTiles]'s — each a sibling of the Mercator function rather than a reparameterisation of
 * it, for the reasons those three files record.
 *
 * **The LOD is not substituted at all, and that is a measured correction rather than an omission.**
 * [observeMercatorLod] is called here with the camera's plain [com.rohittp.reng.Camera.zoom], never
 * with [ResolvedGlobeCamera.effectiveZoom]. `z_eff` sizes the sphere so that the ground scale at the
 * camera's own latitude matches Mercator's; a Mercator tile at latitude phi covers a ground square
 * `cos phi` the side of an equatorial one, so its on-screen size is
 * `512 * 2^(z_eff - lod) * cos phi = 512 * 2^(zoom - lod)` and **the latitude factors cancel
 * exactly** at the one latitude a per-frame LOD is chosen for. Feeding `effectiveZoom` here is the
 * reading the formula invites and is *worse* than the naive convention — LOD 9 instead of 6, 154
 * modelled tiles against 136 — which is why `MercatorLod.kt`'s KDoc names the trap and this call
 * site is written to be read against it.
 *
 * ## What the horizon does and does not decide here
 *
 * [resolveGlobePlacement] answers two questions at once, and this function keeps only one of them.
 * The **draw regime** is [Placement.positionMode]'s answer and is what a spatial plan carries; the
 * **horizon verdict** is deliberately dropped, because `internal.gl.Scene` requires every sticker
 * and every model to be named exactly once by one of the two order lists, and a plan that silently
 * omitted a hidden placement would be indistinguishable from a caller that forgot to build the
 * orders at all. The cull therefore happens where the frame is drawn — `SceneContent` re-resolves
 * every placement against this same camera and skips the ones the planet stands in front of — which
 * is the same place, and the same re-resolution, that Mercator has always used.
 */
internal fun planGlobeSpatial(
    plan: FramePlan,
    outputPixelSize: OutputPixelSize,
    previousSelectedLod: Int?,
    maximumBasemapTileInstances: Int,
    basemapStyleConfigured: Boolean,
): SpatialOutcome<FrameSpatialPlan> {
    val cameraOutcome = resolveGlobeCamera(plan.camera, outputPixelSize)
    if (cameraOutcome is SpatialOutcome.Failure) return cameraOutcome
    val camera = (cameraOutcome as SpatialOutcome.Success).value
    val lodObservation = observeMercatorLod(plan.camera.zoom, previousSelectedLod)

    var footprint: GlobeGroundFootprint? = null
    var tileSelection: TileSelectionOutcome.Success? = null
    // Gated exactly as the Mercator arm gates it, including E-labels' `drawBasemap || drawLabels`:
    // the selection is the frame's rather than the ground's, and the label handover is handed these
    // same canonical tiles.
    if ((plan.drawBasemap || plan.drawLabels) && basemapStyleConfigured) {
        footprint = globeGroundFootprint(camera)
        when (
            val selection = selectGlobeTiles(
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
        val outcome = resolveGlobeDrawnThing(DrawnThingReference.StickerAt(index), sticker.placement, camera)
        if (outcome is SpatialOutcome.Failure) return outcome
        appendByDrawRegime((outcome as SpatialOutcome.Success).value, mapEntries, screenEntries)
    }
    for ((index, model) in plan.modelsForCore().withIndex()) {
        // ADR 0029 is a statement about the screen projection, which carries no z row in either
        // projection mode, so a SCREEN-positioned Model is refused here on exactly the Mercator
        // arm's terms.
        if (model.placement.positionMode == AnchoringMode.SCREEN) {
            return screenPositionedModelFailure()
        }
        val outcome = resolveGlobeDrawnThing(DrawnThingReference.ModelAt(index), model.placement, camera)
        if (outcome is SpatialOutcome.Failure) return outcome
        appendByDrawRegime((outcome as SpatialOutcome.Success).value, mapEntries, screenEntries)
    }
    screenEntries.sortWith(screenCompositingOrder)

    val geometries = ArrayList<ResolvedGeometry>()
    val shaderProfiles = ArrayList<Pair<ShaderProfilePlan, ShaderProfilePlan>>()
    for (geometry in plan.geometriesForCore()) {
        val geometryOutcome = resolveGlobeGeometry(geometry, camera)
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

private fun resolveGlobeDrawnThing(
    reference: DrawnThingReference,
    placement: Placement,
    camera: ResolvedGlobeCamera,
): SpatialOutcome<ResolvedDrawnThing> = when (val outcome = resolveGlobePlacement(placement, camera)) {
    is SpatialOutcome.Failure -> outcome
    is SpatialOutcome.Success ->
        SpatialOutcome.Success(ResolvedDrawnThing(reference, outcome.value.placement))
}
