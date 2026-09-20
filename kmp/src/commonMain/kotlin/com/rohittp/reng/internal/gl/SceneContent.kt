package com.rohittp.reng.internal.gl

import com.rohittp.reng.AltitudeMode
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.Placement
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.math.DoubleMatrix3
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.model.DecodedModel
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.BasemapTileQuad
import com.rohittp.reng.internal.planning.DrawRegime
import com.rohittp.reng.internal.planning.DrawnThingReference
import com.rohittp.reng.internal.planning.ResolvedGeometry
import com.rohittp.reng.internal.planning.ResolvedPlacement
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveBasemapTileQuad
import com.rohittp.reng.internal.planning.resolveGlobePlacement
import com.rohittp.reng.internal.planning.resolvePlacement
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.WORLD_CIRCUMFERENCE_METRES
import com.rohittp.reng.internal.projection.globeMetresToLogicalPixels
import com.rohittp.reng.internal.terrain.DemTileWindow
import com.rohittp.reng.internal.terrain.GroundSurface
import com.rohittp.reng.internal.terrain.frameGroundCellsPerTileSide
import com.rohittp.reng.internal.renGFailure

/**
 * One sticker still carrying its raw, unresolved [Placement] plus the GL texture name its image
 * already uploaded to (Task 4's `uploadTexture`, run once by whoever assembles a [SceneContent]).
 * [SceneContent] resolves [placement] against the frame's camera at draw time — see the class KDoc
 * for why that does not repeat the mistake ADR 0024 warns against.
 *
 * [imageWidthPixels] and [imageHeightPixels] are the uploaded image's own pixel dimensions
 * (`internal.image.DecodedImage.width`/`.height`) — `CONTEXT.md`: a Sticker draws "as a centred
 * local XY quad whose width and height are the image's pixel dimensions." Defaulting both to `1`
 * keeps every pre-existing caller (and test) that has no image dimensions to report compiling
 * unchanged, at exactly the unit-quad size this cycle shipped before Task 9b's fix.
 */
internal class SceneSticker(
    val placement: Placement,
    val texture: Int,
    val imageWidthPixels: Int = 1,
    val imageHeightPixels: Int = 1,
)

/**
 * One consumer [Geometry] paired with the [GeometryPipeline] already compiled — and cached by
 * shader pair — for its own [Geometry.shaderPair]. Compiling and caching that program is
 * `GlProgramCache` bookkeeping that belongs to whoever assembles a [SceneContent] once per frame
 * (Cycle F-1 Task 9's renderer), not to [SceneContent] itself.
 *
 * [consumerUniforms] and [consumerTextures] are the [PreparedFrame][com.rohittp.reng.PreparedFrame]-time
 * SNAPSHOTS of [geometry]'s own [Geometry.uniforms] and [Geometry.textures] — never the live `Map`
 * references [geometry] itself carries. Reading `geometry.uniforms`/`geometry.textures` directly at
 * draw time would be a second, later read of the same caller-owned object beyond
 * `FramePlanningCore.plan()`'s single synchronous read, exactly the gap `Geometry`'s own KDoc warns
 * about: a consumer mutating one of those maps between `prepare()` and `draw()` would then render
 * content differing from what the frame's canonical identity already hashed.
 * `com.rohittp.reng.RenGPreparedFrame` takes both snapshots once, at `prepare()` time.
 *
 * [consumerUniforms] deliberately has NO default, even though `geometry.uniforms` would compile as
 * one. "Unreachable in the current codebase" is a property of today's call graph, not a language
 * guarantee — the same reasoning `internal.gl.requireResolvedAtDrawTime`'s typed-conversion applies
 * to a provably-unreachable failure applies here to a merely-currently-single call site: a future
 * second production constructor of [SceneGeometry] (a preview path, a batch or dry-run renderer)
 * that forgot to pass this parameter would silently reintroduce the exact live-map hazard this class
 * exists to close, with the compiler offering no resistance. Every caller, test included, must pass
 * it explicitly.
 *
 * [consumerTextures] carries each consumer sampler name's ALREADY-UPLOADED GL texture object name —
 * not a [com.rohittp.reng.internal.image.DecodedImage] — because upload-and-cache-by-`ResourceKey`
 * (Task 9b's texture-lifetime fix) happens one layer up, in whoever assembles a [SceneContent], the
 * same layer [SceneSticker.texture] already establishes the pattern for. Its default of `emptyMap()`
 * carries no equivalent hazard — it never reads any live reference, so a caller with no consumer
 * textures at all may still omit it safely.
 */
internal class SceneGeometry(
    val geometry: Geometry,
    val pipeline: GeometryPipeline,
    val consumerUniforms: Map<String, ShaderValue>,
    val consumerTextures: Map<String, Int> = emptyMap(),
)

/**
 * One unwrapped basemap tile draw instance paired with the GL texture its rendered PNG already
 * uploaded to (whoever assembles a [SceneContent] runs the decode, upload and residency lease once
 * per frame, exactly as it does for [SceneSticker.texture]).
 *
 * [instance] is carried raw and resolved here at draw time — the same shape [SceneSticker] uses for
 * its [Placement], and for the same reason ([SceneContent]'s class KDoc): the resolution is a pure
 * function of [instance] and the frame's camera. N world copies of one canonical tile appear as N
 * entries sharing one [texture], because
 * [com.rohittp.reng.internal.firewall.basemapTileKey]'s instance overload projects the copy away
 * while [com.rohittp.reng.internal.planning.resolveBasemapTileQuad] keeps it.
 */
internal class SceneGroundTile(
    val instance: BasemapTileInstance,
    val texture: Int,
    /**
     * Which part of [texture] this tile occupies (ADR 0057).
     *
     * [GroundTileWindow.WHOLE] for a tile drawn from its own texture, which is every tile unless the
     * consumer set a per-frame rasterisation budget and this one was drawn from an ancestor instead.
     * Declared with a default so the many construction sites that draw exact tiles say nothing about
     * it, and the one that does not says it explicitly.
     */
    val colourWindow: GroundTileWindow = GroundTileWindow.WHOLE,
    /**
     * This tile's own padded DEM texture and the sub-rectangle of it this tile occupies, or `null`
     * when the frame declares no terrain or Rentile returned no DEM for this canonical tile.
     *
     * **`null` is a picture rather than an error** (ADR 0041): the tile draws the flat ground RenG
     * has drawn since `0.3.0`, one diagnostic per frame names how many tiles did so, and the frame
     * still renders. N world copies of one canonical tile share one entry here exactly as they share
     * one [texture], because a DEM is acquired per canonical tile.
     *
     * [com.rohittp.reng.internal.terrain.DemTileWindow] rather than four floats, because the
     * rectangle is exact in `Double` — a power-of-two `childScale` with integer offsets — and the
     * two sides of a shared edge are bit-identical only while it stays that way. The narrowing to
     * `Float` happens once, in [drawGroundPhase], at the same place every other narrowing in this
     * file happens.
     */
    val elevation: SceneTileDem? = null,
)

/** One ground tile's DEM: the uploaded padded texture, and which part of it this tile reads. */
internal class SceneTileDem(val demTexture: Int, val window: DemTileWindow)

/**
 * What a frame's terrain is, once acquisition, padding and upload have all happened: how to
 * read a texel, how big the source DEM is, how far to exaggerate it, and how finely terrain alone
 * would like the ground subdivided.
 *
 * **[cellsPerTileSide] is terrain's *claim*, not the frame's answer.** [SceneContent] reconciles it
 * with the globe's curvature claim through
 * [com.rohittp.reng.internal.terrain.frameGroundCellsPerTileSide], because a frame draws its whole
 * ground at one granularity or a sliver of background shows between two tiles that disagree.
 * Carrying the claim rather than the answer is what keeps that reconciliation in one place -- one
 * place `RenGRenderer.prepare` now reaches too, to put a label anchor on this surface.
 *
 * Non-null exactly when the frame's style declared a `terrain` block RenG agreed with, including
 * when every one of its tiles turned out to have no DEM: the decode and the exaggeration are
 * properties of the source rather than of the coverage.
 */
internal class SceneTerrain(
    decode: FloatArray,
    val interiorSizePx: Int,
    val exaggeration: Double,
    val cellsPerTileSide: Int,
    /**
     * The same displaced surface, readable on the CPU, or `null` when nothing in this frame asked a
     * question of it.
     *
     * **It is built per frame and only on demand**, because a frame that asks nothing of the surface
     * needs no tile index. Rentile `0.7.0` hands over decoded texels and [GroundSurface] keeps their
     * immutable [com.rohittp.reng.internal.image.DecodedImage] references directly, so constructing
     * this surface copies no DEM raster.
     *
     * **It is built during `prepare()` and carried here, not derived at draw time**, because the
     * label placement pass rode this exact object before the frame existed -- ADR 0035 puts label
     * collision inside `prepare()`. Two constructions would be two indexes and two answers to which
     * of the frame's tiles displace, even though both reference the same pixels.
     */
    val surface: GroundSurface? = null,
) {
    /** [com.rohittp.reng.internal.gl.demDecodeCoefficients] for the source's own encoding. */
    val decode: FloatArray = decode.copyOf()

    init {
        require(decode.size == 4) { "a DEM decode carries three weights and an offset" }
        require(interiorSizePx > 0) { "a DEM tile has a positive interior" }
        require(cellsPerTileSide >= 1) { "a ground grid has at least one cell a side" }
    }
}

/**
 * One model still carrying its raw, unresolved [Placement], plus everything the layer above already
 * derived for it once per frame: the decoded GLB, its node hierarchy composed for this frame's
 * animation state, its joint palettes, its uploaded GPU geometry, and its textures.
 *
 * [SceneContent] resolves [placement] against the frame's camera at draw time, exactly as it does
 * for a [SceneSticker] and for the same reason (see the class KDoc): the resolution is a pure
 * function of [placement] and the camera. Everything else here is *not* pure — decoding, node
 * composition, skinning, uploading and texture residency all belong to whoever assembles a
 * [SceneContent] (Task 16's renderer), which is why they arrive already done.
 *
 * [nodeTransforms] is index-parallel with `model.document.nodes`, as
 * [com.rohittp.reng.internal.model.composeGlobalTransforms] returns it, and is `null` at a node the
 * default scene never reaches. A draw item at such a node has no place in the world and is dropped
 * rather than drawn at the origin.
 *
 * [jointMatricesBySkin] is keyed by the skin index a [com.rohittp.reng.internal.model.ModelDrawItem]
 * names. A skin absent from the map draws its mesh statically — legal glTF, and the same fallback
 * [modelShaderVariantFor] already makes for joints and weights present with no palette.
 *
 * [uploaded] is keyed by `(meshIndex, primitiveIndex)`, the same key
 * [com.rohittp.reng.internal.model.DecodedModel.primitiveFor] resolves, so two draw items
 * instancing one mesh share one [UploadedPrimitive] rather than uploading it twice.
 *
 * [imageTextures] is index-parallel with `model.images`, and [overrideTexture] is `Model.texture`'s
 * already-uploaded name. `CONTEXT.md` states the override as replacing "every rendered primitive's
 * base-colour texture while preserving other material properties", so it wins over the authored
 * image wherever it is present — including on a primitive whose material names no texture at all.
 */
internal class SceneModel(
    val placement: Placement,
    val model: DecodedModel,
    val nodeTransforms: List<DoubleMatrix4?>,
    val jointMatricesBySkin: Map<Int, List<DoubleMatrix4>>,
    val uploaded: Map<Pair<Int, Int>, UploadedPrimitive>,
    val imageTextures: List<Int>,
    val overrideTexture: Int?,
)

/**
 * Everything one frame needs [SceneContent] to draw. [outputPixelSize] and [frameIndex] feed the
 * documented `uResolution` / `uFrameIndex` uniforms directly; they play no part in placement
 * resolution itself, since [camera] already folded the output size into its projection matrix.
 *
 * [groundTiles] is empty whenever the frame draws no basemap, no style is configured, or the frame
 * selected no tile.
 *
 * **[labels] is the fourth scene list, and it is outside [mapOrder] and [screenOrder] on purpose
 * (ADR 0034).** Labels are engine-derived: no entry in any of the caller's three `FramePlan` lists
 * corresponds to one, so a label cannot be named by a [DrawnThingReference], whose two members both
 * mean "the *n*th entry of the caller's own list". [geometries] and [groundTiles] are already that
 * shape — a list drawn at a fixed phase, named by no reference — and this is the third of them. The
 * bijection below therefore keeps counting [stickers] and [models] and nothing else; labels are
 * outside it for exactly the reason they are outside the two order lists.
 *
 * A [LabelBatch] carries its own atlas texture, and [outputPixelSize] is the label pass's only
 * per-frame uniform, so [SceneContent] builds the [LabelWorld] from this list and this class's own
 * [outputPixelSize] rather than carrying a second copy of the frame's size.
 *
 * **[icons] is the other half of phase 5, and it is a separate list because it is a separate
 * texture.** An icon and its text are one symbol, but a batch is per atlas texture and a sprite
 * atlas is not a glyph atlas, so the two halves cannot share a draw however much they share a
 * meaning. Keeping them as two lists is what makes the phase's internal order -- every icon, then
 * every glyph -- a property of the phase rather than of how a frame's symbols happen to be
 * interleaved; see [drawLabelPhase].
 *
 * **[mapOrder] and [screenOrder] are the planner's answer, carried rather than re-derived.**
 * `MercatorSpatialPlanner` splits every drawn thing into its draw regime (from `Placement`'s
 * `positionMode`, `CONTEXT.md`) and sorts the screen stack by `screenCompositeZ`, then
 * sticker-before-model, then source index — once, at `FRAME_PLANNING`, where it is unit-tested.
 * [SceneContent] used to discard both answers and re-derive them from a second resolution of each
 * [Placement] plus a second sort inside `drawStickers`. Two copies of one documented rule is one
 * too many, and the copy in the GL layer was the untested one, so these two lists are now the only
 * authority: [stickers] and [models] say *what* there is to draw, and these say *where each one
 * goes and in what order*.
 *
 * [mapOrder] is **not** a draw order on its own. It is the planner's `mapEntries` order, which is
 * stickers-then-models by declaration; ADR 0030 fixes the map regime's phase order as ground,
 * geometries, models, map-anchored stickers, and consuming this list verbatim would paint a car
 * over the pin standing in front of it. [SceneContent] takes each type's relative order from here
 * and applies ADR 0030's phase order over it. [screenOrder] *is* a draw order, complete — ADR 0024
 * makes the screen regime one ordered stack across every drawn-thing type.
 *
 * The two lists are required to be a **bijection** onto [stickers] and [models]: every reference in
 * range, no reference twice, and every sticker and every model named exactly once. A drawn thing is
 * in exactly one regime, so anything else is a caller that built the two halves inconsistently —
 * including a caller that simply forgot to pass an order at all, which would otherwise draw a
 * frame's stickers and models silently missing rather than reporting anything.
 */
internal class Scene(
    val outputPixelSize: OutputPixelSize,
    val frameIndex: Long,
    val stickers: List<SceneSticker> = emptyList(),
    val geometries: List<SceneGeometry> = emptyList(),
    val groundTiles: List<SceneGroundTile> = emptyList(),
    /**
     * This frame's terrain, or `null` when its style declared none — which is 28 of the corpus's 34
     * styles and every frame of three published releases.
     */
    val terrain: SceneTerrain? = null,
    val models: List<SceneModel> = emptyList(),
    val labels: List<LabelBatch> = emptyList(),
    val icons: List<IconBatch> = emptyList(),
    mapOrder: List<DrawnThingReference> = emptyList(),
    screenOrder: List<DrawnThingReference> = emptyList(),
) {
    val mapOrder: List<DrawnThingReference> = mapOrder.toList()
    val screenOrder: List<DrawnThingReference> = screenOrder.toList()

    init {
        val referenced = HashSet<DrawnThingReference>()
        for (reference in this.mapOrder) {
            require(referenced.add(reference)) { "each drawn thing may appear in exactly one draw order" }
        }
        for (reference in this.screenOrder) {
            require(referenced.add(reference)) { "each drawn thing may appear in exactly one draw order" }
        }
        for (reference in referenced) {
            val available = when (reference) {
                is DrawnThingReference.StickerAt -> stickers.size
                is DrawnThingReference.ModelAt -> models.size
            }
            val index = when (reference) {
                is DrawnThingReference.StickerAt -> reference.index
                is DrawnThingReference.ModelAt -> reference.index
            }
            require(index in 0 until available) { "a draw order may only reference a drawn thing the scene carries" }
        }
        // Stickers and models only, and ADR 0034 keeps it that way: `labels` is engine-derived, has
        // no `FramePlan` entry to be the n-th of, and is named by neither order list. Adding it to
        // this sum would make every label-bearing scene fail construction.
        require(referenced.size == stickers.size + models.size) {
            "every sticker and every model must appear in exactly one of mapOrder and screenOrder"
        }
    }
}

/**
 * The [GlFrameContent] Cycle D always left a seam for: its own KDoc says "Cycle D draws no frame
 * content of its own; Cycle E replaces this with the real scene draw." This is that replacement.
 *
 * **Ordering (ADRs 0024, 0025, 0027, 0030 and 0034).** The map regime draws first, depth-tested; the
 * screen regime then composites on top with depth testing off. Within the map regime the order is
 * fixed as: the **ground** first, then every [Geometry] (map-anchored by definition — `CONTEXT.md`
 * says "A Geometry carries no Placement") in `FramePlan.geometries` order, then every **model**, then
 * every map-anchored sticker. That is ADR 0025's order with ADR 0030's models inserted before the
 * stickers, because a map-anchored sticker is a marker and a marker paints over the scene it marks.
 * ADR 0034 then adds **phase 5, the labels**, between the two regimes:
 *
 * ```
 * map regime (depth tested throughout, ADR 0027)
 *   1 ground (test, AND write when displaced, ADR 0039)   2 geometries
 *   3 models (test AND write, ADR 0030)                   4 map-anchored stickers
 *   5 LABELS   depth test OFF   <-- ADR 0034, and OUTSIDE both regimes
 * screen regime (no depth)
 *   6 consumer screen-anchored stickers, by z (ADR 0024)
 * ```
 *
 * **Labels are not a member of the map regime, and saying so is what keeps ADR 0034 from
 * contradicting ADRs 0027 and 0030.** ADR 0027 requires *every map-regime pass* to enable
 * `GL_DEPTH_TEST` and set `glDepthMask(GL_FALSE)`, and ADR 0030 amends that for the model pass alone.
 * [beginLabelPass] *disables* the test, which would read as a third exception if the label pass were
 * inside the map regime. It is not: it is a fourth scene list drawn between the regimes, described by
 * a phase number rather than by a regime name, so both of those ADRs continue to describe every pass
 * that *is* in the map regime without amendment. So a consumer's screen-anchored sticker covers a
 * label, and a label covers a map-anchored sticker. What still binds the label pass is
 * `SceneContentTest.exactlyOnePhaseWritesDepthItIsTheModelPassAndTheMaskIsOffAgainOnTheWayOut`:
 * exactly one phase in a whole scene enables depth writes and it is the model pass. The label pass
 * enables none, and the test off is genuinely *needed* here rather than merely restated — phase 4
 * leaves `GL_DEPTH_TEST` enabled behind it, unlike the depth mask, which the model pass already
 * turned off on its way out.
 *
 * **The phase order is a relative order, not a claim that any earlier phase produced pixels.** The
 * label pass is orthogonal to the basemap (`FramePlan.drawLabels` against `FramePlan.drawBasemap`),
 * so phase 5 runs on a frame with no ground at all — which is why [Scene.labels] joins the
 * nothing-to-draw guard below rather than being reachable only past a ground tile.
 *
 * **Which regime each drawn thing is in, and its order within its own type, is
 * [Scene.mapOrder]/[Scene.screenOrder] — never re-derived here.** `MercatorSpatialPlanner` computes
 * both at `FRAME_PLANNING`; this class used to compute them a second time, from a second resolution
 * of every [Placement] plus a second sort inside `drawStickers`, and the second copy was the untested
 * one. [draw] now splits [Scene.mapOrder] by type — preserving each type's own relative order — and
 * applies ADR 0030's phase order over that, because [Scene.mapOrder] is stickers-then-models by
 * declaration and is a regime membership answer rather than a draw order. [drawScreenStack] walks
 * [Scene.screenOrder] straight through, because that one *is* a complete draw order.
 *
 * Each [Placement] is still re-resolved here for its **matrices**, which is a different claim and
 * still a sound one — see [requireResolvedAtDrawTime]. What no longer happens is reading
 * `ResolvedPlacement.drawRegime` or `.screenCompositeZ` back out of that resolution to decide regime
 * or order. The `require`s inside [composeMapCameraSpaceModel] and [composeScreenModelViewProjection]
 * stay, and change meaning slightly: they are now the cross-check that the planner's regime and the
 * placement's own resolution agree, and they fail loudly rather than letting the two silently diverge.
 *
 * That order used to be arbitrary and is now load-bearing. `drawFrame` tests `GL_GEQUAL`, not
 * `GL_GREATER` (ADR 0025), and the map regime has **four depth phases** rather than one policy:
 *
 * - **The ground tests depth, and writes it exactly when this frame's ground is displaced**
 *   (ADR 0039, superseding ADR 0027 for this pass alone, as ADR 0030 already did for the model
 *   pass). Terrain that cannot occlude is not terrain: a model standing behind a ridge would paint
 *   straight over it. The condition is the frame's rather than the tile's, and it is not a hedge —
 *   an unconditional write revives ADR 0027's coplanar defect in the 28 of 34 corpus styles that
 *   declare no terrain, and buys nothing in any of them, because a flat ground has no relief to
 *   occlude with. [drawGround] and [drawGlobeGround] own the decision, because they are the only
 *   passes that know which of a frame's tiles carry a DEM.
 * - **Each [Geometry] tests depth and writes none** (ADR 0027). ADR 0025's tie-break
 *   closed only the bit-identical case, and the two defects that actually shipped were near-ties — a
 *   coplanar `Geometry` tearing itself apart frame to frame as the epsilon between two different
 *   matrix products changed sign, and a billboard bisected along its anchor row by a ground plane
 *   whose depth varies down the screen. Flat map-plane content acting as an occluder was the single
 *   cause of both. Among the passes that write nothing, later declared wins, full stop, not merely
 *   on an exact tie.
 * - **Models test *and* write** (ADR 0030, which supersedes ADR 0027 for this pass alone). A mesh
 *   that writes no depth cannot occlude itself: every triangle passes the test against whatever is
 *   behind it and paints in submission order, so back faces show through front ones. Back-face
 *   culling hides that for a closed convex mesh and for nothing else, and 109 of the 111 materials in
 *   the consumer's own corpus are `doubleSided`. [drawModels] owns both halves of this phase — writes
 *   on for the opaque primitives, off again for the blended ones — and leaves the mask **off** behind
 *   it unconditionally, which is what the sticker pass after it depends on.
 * - **Map-anchored stickers test and write none** (ADR 0027 again, unchanged).
 *
 * The map regime still *tests* depth throughout, so content that genuinely wrote nearer depth — a
 * model since ADR 0030, and a displaced ground since ADR 0039 — occludes what is drawn after it. The
 * ground goes first because it is the backdrop everything else paints onto. ADR 0030 records the
 * cost it accepts: a billboard sharing space with a model can still be cut along its own anchor row,
 * exactly as ADR 0027 describes against the ground, because a model is a real occluder again.
 *
 * **Why resolving [Placement]/[Geometry] here does not put spatial-failure handling inside a GL
 * draw call.** Cycle F-1 Tasks 5 and 6 pushed placement and geometry resolution out of
 * [drawStickers]/[drawGeometry] because [resolvePlacement]/[geometryGrid] can fail and a GL draw
 * call has no way to surface that failure. That resolution still happens exactly once, at
 * `FRAME_PLANNING`, inside `internal.planning.planMercatorSpatial` — whoever assembles a
 * [SceneContent] (Task 9's renderer) only does so with a [Geometry]/[Placement] plus [camera] pair
 * that has *already* resolved successfully during `prepare()`. Both resolver functions are pure and
 * deterministic in [camera] and their one domain argument, so calling them again here on the
 * identical inputs cannot newly fail — it is exactly as safe as threading `FRAME_PLANNING`'s
 * resolved objects all the way into this GL layer would have been, without growing that seam before
 * Task 9 fixes what a prepared frame actually retains. A resolution failure reaching [draw] is
 * therefore a caller contract violation, not a legitimate runtime outcome, and is reported as a
 * typed [com.rohittp.reng.RenGException] (`INVALID_VALUE` at `DRAW`) via [requireResolvedAtDrawTime]
 * rather than silently swallowed, drawn wrong, or thrown as a bare untyped exception.
 *
 * **Precision.** [composeMapModelViewProjection], [composeScreenModelViewProjection], and
 * [composeGeometryViewProjection] multiply [ResolvedMercatorCamera]'s and [ResolvedPlacement]'s
 * `Double` matrices and vectors throughout, narrowing to `Float` only in the column-major array
 * each hands to [GlBinding.uniformMatrix4fv]. Geometry vertex positions come from
 * [ResolvedGeometry.cornersClockwiseFromTopLeft] untouched but for that same last-step narrowing:
 * this class never derives a vertex position from [Geometry.topLeft] / [Geometry.bottomRight]
 * degrees directly, which is what would discard Cycle B's sub-0.001px camera-relative precision.
 *
 * **Why [modelPipelines] may default to an empty map where [SceneGeometry.consumerUniforms] may not.**
 * That field has no default because omitting it would silently draw a geometry with none of its
 * consumer's uniforms — a wrong picture reported as a correct one. Omitting a model pipeline cannot
 * be silent: [drawModels] resolves each primitive's variant through `requireNotNull`, so a frame with
 * models and no pipelines fails loudly at the first primitive rather than drawing anything at all. A
 * frame with no models needs no pipelines, and making every such caller pass an empty map buys
 * nothing.
 *
 * [labelPipeline] is nullable on the same terms and for the same reason: a frame with no labels needs
 * none, and a frame *with* labels and none supplied hits a `requireNotNull` at phase 5 rather than
 * silently drawing a labelless map that looks finished.
 *
 * **[camera] is the frame's projection mode, and it is the only place that mode appears here.**
 * `ResolvedFrameCamera` is sealed over the two modes, so every decision that differs between them —
 * which ground entry point draws the basemap, which resolver a `Placement` goes through, which
 * `geometryGrid` overload subdivides a `Geometry` — is an exhaustive `when` on this one value. This
 * class used to take a separate `projectionMode: ProjectionMode` parameter defaulting to
 * `MERCATOR`, which was the honest shape while `FramePlanningCore` still refused `GLOBE` and no
 * globe camera could reach here; carrying both now would be two authorities on one rule, and the
 * one that could disagree with the pixels is the parameter.
 *
 * **[globeGroundPipeline] is required exactly when a globe frame carries ground tiles**, and is
 * `null` otherwise on [labelPipeline]'s terms: a mercator renderer must not pay for a program it
 * will never use, and a globe frame that reaches the ground pass without one hits a `requireNotNull`
 * rather than silently drawing a flat map.
 */
internal class SceneContent(
    private val camera: ResolvedFrameCamera,
    private val scene: Scene,
    private val stickerPipeline: StickerPipeline,
    private val groundPipeline: GroundPipeline,
    private val modelPipelines: Map<ModelShaderVariant, ModelPipeline> = emptyMap(),
    private val labelPipeline: LabelPipeline? = null,
    private val iconPipeline: IconPipeline? = null,
    private val globeGroundPipeline: GlobeGroundPipeline? = null,
    private val backdrop: ResolvedBackdrop? = null,
) : GlFrameContent {

    /**
     * The granularity this frame's whole ground is drawn at, reconciled once.
     *
     * [drawGroundPhase] used to compute this twice, once per projection arm, and it is hoisted
     * because [groundSurface] must answer against the *same* number: the CPU lookup finds the ground
     * grid cell containing a point, and a lookup at terrain's unreconciled claim would find the wrong
     * cell on every globe frame whose curvature asks for more cells than the DEM does.
     *
     * `1` with no ground tiles, where nothing reads it: the globe's curvature claim needs a LOD and a
     * frame with no ground has none.
     *
     * **The reconciliation itself is [frameGroundCellsPerTileSide]'s** rather than written here,
     * because `RenGRenderer.prepare` needs the identical number to put a label anchor on this
     * surface and a second `when` over the camera is a second chance to answer differently.
     */
    private val frameGroundCells: Int = frameGroundCellsPerTileSide(
        camera = camera,
        selectedLod = scene.groundTiles.firstOrNull()?.instance?.lod,
        terrainCells = scene.terrain?.cellsPerTileSide ?: 1,
    )

    /**
     * The surface a `GROUND_RELATIVE` altitude is measured from, or `null` when this frame drew none.
     *
     * `null` is ADR 0040's stated cost -- "where terrain is absent or degraded, `GROUND_RELATIVE`
     * resolves against the flat ground and therefore means `ABSOLUTE`" -- and it is why every frame
     * RenG has ever drawn is unchanged by this cycle rather than merely believed to be: a style with
     * no `terrain` block has no [SceneTerrain] at all, and a frame with no ground tiles has nothing
     * to ride.
     */
    private val groundSurface: GroundSurface? = scene.terrain?.surface
        ?.takeIf { scene.groundTiles.isNotEmpty() && !it.isEmpty }

    override fun draw(binding: GlBinding) {
        // ADR 0068, and it is deliberately the first thing here -- ahead of the emptiness check
        // below as well as ahead of the ground. A frame carrying nothing but a backdrop is a frame
        // that draws its backdrop; returning early on an empty *scene* would make the one case a
        // consumer is most likely to try first, a backdrop with `drawBasemap = false`, silently
        // paint nothing.
        if (backdrop != null) {
            drawBackdrop(
                binding = binding,
                backdrop = backdrop,
                resolutionWidthPixels = scene.outputPixelSize.width.toFloat(),
                resolutionHeightPixels = scene.outputPixelSize.height.toFloat(),
                frameIndex = scene.frameIndex,
            )
        }

        if (scene.groundTiles.isEmpty() &&
            scene.geometries.isEmpty() &&
            scene.models.isEmpty() &&
            scene.stickers.isEmpty() &&
            scene.labels.isEmpty() &&
            scene.icons.isEmpty()
        ) {
            return
        }

        if (scene.groundTiles.isNotEmpty()) {
            binding.enable(GL_DEPTH_TEST)
            // No `depthMask` here, and its absence is ADR 0039's. The ground's mask is now a
            // *decision* -- on in a displaced frame, off in a flat one -- and [drawGround] and
            // [drawGlobeGround] are the two places that can make it, because they are the only ones
            // that know which of this frame's tiles carry a DEM. A `depthMask(false)` at this level
            // would be a second authority that is wrong in every terrain frame and immediately
            // overwritten in all of them: harmless in pixels, and exactly the kind of line a later
            // reader takes for the rule.
            drawGroundPhase(binding)
        }

        if (scene.geometries.isNotEmpty()) {
            // ADR 0027: the map regime tests depth and writes none. `drawGeometry` runs a
            // consumer's own shader pair, so unlike `drawGround` and `drawStickers` it establishes
            // no pipeline state of its own -- the depth state for the geometry pass is set here,
            // beside the `GL_DEPTH_TEST` enable that has always lived here.
            //
            // This `depthMask(false)` stopped being one of three identical redundant calls when
            // ADR 0039 landed: the pass immediately before it now leaves the mask **on** in every
            // displaced frame, so deleting this line puts a consumer's shader pair into the depth
            // buffer.
            binding.enable(GL_DEPTH_TEST)
            binding.depthMask(false)
            // ADR 0038: the geometry pass must not inherit the ground's cull enable. The ground
            // enables culling on a globe and draws immediately before this, and the winding of the
            // triangles a consumer's shader pair emits is the consumer's own: a shader that negates
            // a coordinate or reorders its corners inverts the front face, and culling would then
            // delete a legal `Geometry` whose only sin is a sign, silently and only in one
            // projection mode. That is the class of silent breakage ADR 0008 exists to prevent, so
            // the disable belongs here beside the depth state, for the same reason the depth state
            // does: `drawGeometry` cannot make these calls for itself.
            binding.disable(GL_CULL_FACE)
            val geometryViewProjection = when (camera) {
                is ResolvedMercatorCamera -> composeGeometryViewProjection(camera)
                is ResolvedGlobeCamera -> composeGeometryViewProjection(camera)
            }
            for (sceneGeometry in scene.geometries) {
                // Subdivided and CPU-projected, in both projection modes, so that the matrix above
                // stays linear and a consumer's shader pair survives a globe unchanged (ADR 0008's
                // 2026-08-29 erratum). Under Mercator the camera implies a single cell, so this
                // resolves the same four corners `resolveGeometry` always did and the frame is the
                // one `0.3.0` drew.
                val drape = groundDrapeFor(sceneGeometry.geometry)
                val cellsPerSide = geometryCellsPerSideFor(sceneGeometry.geometry, drape)
                val grid = when (camera) {
                    is ResolvedMercatorCamera ->
                        geometryGrid(sceneGeometry.geometry, camera, cellsPerSide, drape)

                    is ResolvedGlobeCamera ->
                        geometryGrid(sceneGeometry.geometry, camera, cellsPerSide, drape)
                }.requireResolvedAtDrawTime()
                drawGeometry(
                    binding = binding,
                    pipeline = sceneGeometry.pipeline,
                    grid = grid,
                    modelViewProjection = geometryViewProjection,
                    resolutionWidthPixels = scene.outputPixelSize.width.toFloat(),
                    resolutionHeightPixels = scene.outputPixelSize.height.toFloat(),
                    boundsWestSouthEastNorthDegrees = sceneGeometry.geometry.boundsWestSouthEastNorth(),
                    frameIndex = scene.frameIndex,
                    consumerUniforms = sceneGeometry.consumerUniforms,
                    consumerTextures = sceneGeometry.consumerTextures,
                )
            }
        }

        // ADR 0030's phase order over the planner's per-type order. `Scene.mapOrder` is
        // stickers-then-models by declaration and is deliberately NOT consumed as a draw order:
        // drawing it verbatim would paint a car over the pin standing in front of it. What is taken
        // from it is which stickers and models are map-anchored at all, and the relative order of
        // each type among its own kind.
        val mapModels = ArrayList<SceneModel>()
        val mapStickers = ArrayList<SceneSticker>()
        for (reference in scene.mapOrder) {
            when (reference) {
                is DrawnThingReference.StickerAt -> mapStickers += scene.stickers[reference.index]
                is DrawnThingReference.ModelAt -> mapModels += scene.models[reference.index]
            }
        }

        if (mapModels.isNotEmpty()) {
            // ADR 0030: models are the one map-regime pass that tests AND writes depth, because a
            // mesh has to occlude itself. `drawModels` owns the whole phase -- the depth enable, the
            // mask on for its opaque primitives, and the mask off again on the way out -- so nothing
            // here sets depth state of its own. That exit mask matters: it is what keeps ADR 0027's
            // billboard fix working for the map-anchored stickers drawn immediately after.
            drawModels(
                binding = binding,
                pipelines = modelPipelines,
                models = mapModels.mapNotNull { model ->
                    drawTimePlacement(model.placement)?.let { resolveModel(model, camera, it) }
                },
                lightDirectionCameraSpace = sceneLightDirectionCameraSpace(camera),
            )
        }

        if (mapStickers.isNotEmpty()) {
            // ADR 0038 records a pre-existing gap here and this cycle deliberately does not close it:
            // `drawStickers` sets no cull state, so it inherits whatever the model pass's last
            // non-`doubleSided` primitive left -- and now, on a globe with no models in the frame, the
            // ground's own enable. Nothing moves either way, because `STICKER_QUAD` is `GROUND_QUAD`'s
            // winding exactly, so a sticker is front-facing under `drawFrame`'s `GL_CCW`. That is the
            // same luck ADR 0038 removed from the ground rather than a second argument for keeping it,
            // and closing it belongs with whichever cycle owns the sticker pass.
            drawStickers(
                binding,
                stickerPipeline,
                StickerWorld(mapStickers.mapNotNull { mapAnchoredSticker(it) }),
            )
        }

        drawLabelPhase(binding)
        drawScreenStack(binding)
    }

    /**
     * ADR 0034's phase 5: every label the placement pass left standing, drawn after the whole map
     * regime and before the screen regime's first bind, with the depth test off.
     *
     * **This sits here, between [drawStickers] and [drawScreenStack], and the position is the whole
     * contract.** A label carries its meaning by being *readable* — a half-covered pin is still a pin
     * and a half-covered label is nothing — so it wins over every map-regime pass. It loses to the
     * screen regime, because that is where a consumer puts a HUD, an attribution badge or a cursor,
     * and a label painting over those would make the consumer's own overlay unreliable in a way they
     * cannot fix: they do not control where labels land. Both halves of that are things the losing
     * party can do something about, which is why phase 5 rather than 4 or 6.
     *
     * **One batch, and the phase is what guarantees it.** [drawLabels] issues one `glDrawElements`
     * per [LabelBatch] however many glyphs the batch carries, and a program switch inside a batch is
     * a flush. Merging labels into [Scene.screenOrder] would let one consumer sticker landing between
     * two labels split the batch in two, making the label draw count a function of the consumer's
     * plan rather than of the label content. A fourth list drawn as its own phase has exactly one
     * batch by construction.
     *
     * The depth state belongs to [beginLabelPass] rather than to this method, which follows phases 3
     * and 4 — [drawModels] and [drawStickers] each own their own — rather than phases 1 and 2, whose
     * depth state is set at this level because [drawGeometry] runs a consumer's shader pair and
     * establishes none of its own. The label pass runs a program RenG wrote, so it owns its state:
     * the `disable(GL_DEPTH_TEST)` that phase 4 genuinely leaves it needing (`drawStickers` enables
     * the test for itself), and no depth write at all, so nothing about ADR 0027's billboard fix or
     * ADR 0030's exit mask moves. [drawScreenStack] then disables the test again for itself,
     * idempotently, exactly as it did before this phase existed.
     */
    private fun drawLabelPhase(binding: GlBinding) {
        drawIconPass(binding)
        if (scene.labels.isEmpty()) return
        val pipeline = requireNotNull(labelPipeline) {
            "a scene carrying labels must be drawn with a label pipeline"
        }
        drawLabels(binding, pipeline, LabelWorld(scene.outputPixelSize, scene.labels))
    }

    /**
     * Phase 5's first half: **every icon, before every glyph.**
     *
     * An icon and its text are one symbol, and MapLibre draws a symbol's icon beneath its text
     * because a plate is a backdrop for a name -- a name half-covered by its own shield is
     * unreadable, and a shield half-covered by its own name is still a shield. RenG takes the same
     * order for the same reason.
     *
     * **It is stated as a phase order rather than a per-symbol one, and that is the batch's
     * doing.** A batch is per atlas texture, glyphs and sprites are two different textures, and one
     * program switch inside a batch is a flush. Interleaving symbol by symbol -- icon, its text,
     * next icon, next text -- would cost a texture bind and a program bind per symbol and would make
     * the label pass's draw count a function of how many symbols carry icons. Two draws, whole-phase
     * ordered, is the only shape under which "icons beneath text" survives batching at all. The cost
     * is real and stated: one symbol's icon is beneath *every* symbol's text, not only its own, so
     * two symbols overlapping enough for it to matter would show the far label's glyphs over the
     * near symbol's plate. Collision exists precisely to keep that pair off the screen together.
     *
     * The pass owns its own GL state ([beginIconPass]) exactly as [drawLabels] does, so this runs
     * straight after phase 4's stickers with the depth test still on and turns it off itself.
     */
    private fun drawIconPass(binding: GlBinding) {
        if (scene.icons.isEmpty()) return
        val pipeline = requireNotNull(iconPipeline) {
            "a scene carrying icons must be drawn with an icon pipeline"
        }
        drawIcons(binding, pipeline, IconWorld(scene.outputPixelSize, scene.icons))
    }

    /**
     * ADR 0024's screen regime: one ordered stack across every drawn-thing type, composited on top
     * of the whole map regime with depth testing off. The order is [Scene.screenOrder] exactly —
     * `MercatorSpatialPlanner` already applied `CONTEXT.md`'s rule (greater `position.z` composites
     * on top, ties keep stable plan order with stickers before models), and re-sorting it here is
     * the duplicate this method exists instead of.
     *
     * **It walks the merged order rather than a sticker list, even though the stack is stickers-only
     * today.** ADR 0029 refuses a `SCREEN`-positioned [com.rohittp.reng.Model] at frame planning, so
     * no model can legitimately reach here — but building the stack out of `Scene.stickers` would
     * hard-code that refusal into the loop's *shape*, and the day ADR 0029 is revisited the merge
     * would have to be reinvented. A model reaching here is a contract violation and is reported as
     * one, exactly as [resolveModel] reports the same violation from the map side.
     *
     * The program is bound per **element** rather than once per type, so interleaving two types in
     * one stack costs a program switch and nothing else. With one type it binds once.
     */
    private fun drawScreenStack(binding: GlBinding) {
        if (scene.screenOrder.isEmpty()) return

        binding.disable(GL_DEPTH_TEST)
        var boundProgram: Int? = null
        for (reference in scene.screenOrder) {
            when (reference) {
                is DrawnThingReference.StickerAt -> {
                    if (boundProgram != stickerPipeline.program) {
                        beginStickerPass(binding, stickerPipeline)
                        boundProgram = stickerPipeline.program
                    }
                    drawOneSticker(binding, stickerPipeline, screenCompositedSticker(scene.stickers[reference.index]))
                }
                is DrawnThingReference.ModelAt -> throw IllegalArgumentException(
                    "ADR 0029 refuses a SCREEN-positioned Model before drawing; one reached the screen stack",
                )
            }
        }
    }

    /**
     * Phase 1, at whichever of the two ground entry points this frame's projection mode names.
     *
     * **There are two of them and a globe frame must reach the second.** [drawGround] draws one shared,
     * subdivided grid per tile with a per-tile matrix; [drawGlobeGround] draws one shared,
     * subdivided grid per tile with a per-tile `vec4` of edges and one matrix for the whole frame.
     * Routing a globe frame to the first would draw a tangent plane — which is not obviously wrong on
     * screen at high zoom, and is exactly the kind of defect that survives a look at the output — so
     * the choice is an exhaustive `when` over the sealed camera rather than a flag.
     *
     * Every one of `drawGlobeGround`'s inputs is derived here from what the tiles already carry:
     * `globeGroundTileEdges` from each instance's own `(lod, tileY, unwrappedX)`, and the frame's one
     * granularity from the camera and that same LOD. The tiles all sit at one LOD because
     * `selectGlobeTiles` emits one, which is the premise `globeGroundCellsPerTileSide` is written
     * against: two meshes sharing a sphere edge at different granularities leave a sliver of
     * background between them.
     */
    private fun drawGroundPhase(binding: GlBinding) {
        val terrain = scene.terrain
        val dem = terrain?.let {
            GroundDemUniforms(
                decode = it.decode,
                interiorSizePx = it.interiorSizePx,
                exaggeration = it.exaggeration.toFloat(),
            )
        }
        when (camera) {
            is ResolvedMercatorCamera -> drawGround(
                binding = binding,
                pipeline = groundPipeline,
                tiles = scene.groundTiles.map { tile ->
                    ResolvedGroundTile(
                        modelViewProjection = composeGroundModelViewProjection(
                            camera,
                            resolveBasemapTileQuad(tile.instance, camera),
                        ),
                        texture = tile.texture,
                        colourWindow = tile.colourWindow,
                        elevation = tile.elevation?.let { demTile ->
                            MercatorGroundTileDem(
                                dem = groundTileDemOf(demTile, tile.instance.lod),
                                mercatorY = mercatorTileYEdges(tile.instance.lod, tile.instance.tileY),
                            )
                        },
                    )
                },
                // Mercator has no curvature claim on the granularity, so terrain's is the whole
                // answer -- and `groundCellsPerTileSide` is still where it is spent, so the cap and
                // the reconciliation rule live in one place for both projections.
                cellsPerTileSide = frameGroundCells,
                elevation = dem?.let {
                    MercatorGroundElevationFrame(
                        dem = it,
                        // `projectMercator`'s own altitude scale with its `1 / cos(latitude)` term
                        // left out, because the vertex shader applies that per vertex -- see
                        // [TERRAIN_GROUND_VERTEX_SOURCE].
                        equatorialLogicalPixelsPerMetre =
                            (camera.worldSizeLogicalPixels / WORLD_CIRCUMFERENCE_METRES).toFloat(),
                    )
                },
            )

            is ResolvedGlobeCamera -> drawGlobeGround(
                binding = binding,
                pipeline = requireNotNull(globeGroundPipeline) {
                    "a globe frame carrying ground tiles must be drawn with the globe ground pipeline"
                },
                // A top- or bottom-row tile also paints the cap above or below it, because web
                // Mercator ends at 85.0511 degrees and a sphere shows the hole that leaves. At lod 0
                // one tile is both rows and draws both caps, which is why these are two independent
                // conditions rather than a `when`.
                tiles = scene.groundTiles.flatMap { tile ->
                    val edges = globeGroundTileEdges(
                        lod = tile.instance.lod,
                        tileY = tile.instance.tileY.toLong(),
                        unwrappedX = tile.instance.unwrappedX,
                    )
                    val tileDem = tile.elevation?.let { groundTileDemOf(it, tile.instance.lod) }
                    buildList {
                        add(
                            ResolvedGlobeGroundTile(
                                edges = edges,
                                texture = tile.texture,
                                colourWindow = tile.colourWindow,
                                elevation = tileDem,
                            ),
                        )
                        if (tile.instance.tileY.toLong() == 0L) {
                            add(
                                ResolvedGlobeGroundTile(
                                    edges = globeGroundPolarCapEdges(edges, north = true),
                                    texture = tile.texture,
                                    // The cap carries the same window as the tile it hangs from: its
                                    // collapsed v lands on the window's own edge, which is the tile's
                                    // last row inside the ancestor rather than the ancestor's.
                                    colourWindow = tile.colourWindow,
                                    uvV = NORTH_POLAR_CAP_UV_V,
                                    elevation = tileDem?.let { polarCapDem(it, north = true) },
                                ),
                            )
                        }
                        if (tile.instance.tileY.toLong() == (1L shl tile.instance.lod) - 1L) {
                            add(
                                ResolvedGlobeGroundTile(
                                    edges = globeGroundPolarCapEdges(edges, north = false),
                                    texture = tile.texture,
                                    colourWindow = tile.colourWindow,
                                    uvV = SOUTH_POLAR_CAP_UV_V,
                                    elevation = tileDem?.let { polarCapDem(it, north = false) },
                                ),
                            )
                        }
                    }
                },
                unitSphereToClip = composeGlobeGroundUnitSphereToClip(camera),
                // The frame's one granularity: the globe's curvature claim and terrain's, reconciled
                // by `max` and capped by the grid's own 16-bit-index ceiling. Two meshes sharing an
                // edge on the sphere must be subdivided identically, so this is one number for the
                // whole ground and never one per tile.
                cellsPerTileSide = frameGroundCells,
                elevation = dem?.let {
                    GlobeGroundElevationFrame(
                        dem = it,
                        // `globeMetresToLogicalPixels(R) / R`, which is `1 / a` and independent of
                        // zoom -- written as the quotient so it follows the one function that owns
                        // the globe's metre scale. A sphere has no `1 / cos(latitude)`.
                        radialMultiplePerMetre = (
                            globeMetresToLogicalPixels(camera.radiusLogicalPixels) /
                                camera.radiusLogicalPixels
                            ).toFloat(),
                    )
                },
            )
        }
    }

    /**
     * One [SceneTileDem] narrowed to the four `Float`s [GroundTileDem] hands the shader, plus the
     * one metric a shaded ground needs and a displacing one does not.
     *
     * **[lod] is the *requested* tile's, never the DEM's source LOD.** The number answers "how many
     * metres of ground does one grid unit of this tile span", and the grid belongs to the tile being
     * drawn; under overzoom the source DEM is coarser and its own side would be too large by exactly
     * `childScale`, which would flatten every slope by the same factor.
     */
    private fun groundTileDemOf(tile: SceneTileDem, lod: Int): GroundTileDem = GroundTileDem(
        demTexture = tile.demTexture,
        window = floatArrayOf(
            tile.window.uMinimum.toFloat(),
            tile.window.uMaximum.toFloat(),
            tile.window.vMinimum.toFloat(),
            tile.window.vMaximum.toFloat(),
        ),
        tileSideMetres = (WORLD_CIRCUMFERENCE_METRES / (1L shl lod).toDouble()).toFloat(),
    )

    /**
     * A polar cap wedge's DEM window: its tile's own, with `v` collapsed onto the edge the cap hangs
     * from, exactly as [NORTH_POLAR_CAP_UV_V] collapses the colour texture's.
     *
     * The cap covers the world above +85.0511 degrees or below -85.0511, which web Mercator does not
     * tile at all, so no DEM texel describes it. The honest elevation there is the one at the tile's
     * own last row, continued outward -- the same answer [padDemTexture]'s `WORLD_EDGE` fill gives
     * the ring for the same region, so the cap and the tile it hangs from meet at one height.
     */
    private fun polarCapDem(tile: GroundTileDem, north: Boolean): GroundTileDem {
        val v = if (north) tile.window[2] else tile.window[3]
        return GroundTileDem(
            demTexture = tile.demTexture,
            window = floatArrayOf(tile.window[0], tile.window[1], v, v),
            tileSideMetres = tile.tileSideMetres,
        )
    }

    /**
     * One [Placement] resolved against this frame's camera at draw time, or `null` when the globe is
     * standing in front of it.
     *
     * **`null` is not a failure and is not a viewport test.** ADR 0038 culls the far hemisphere, and
     * a placement on it projects to a perfectly ordinary pixel — an antipodal one lands dead centre
     * with a large positive `w`, because `w` is the distance in front of the *camera plane* and a
     * camera outside the sphere has the whole planet in front of it. Nothing about the projected
     * value distinguishes it, which is why `resolveGlobePlacement` answers the limb-plane question
     * separately and this is where that answer is spent.
     *
     * **The cull is here rather than in the plan** because `Scene` requires every sticker and every
     * model to be named exactly once by `mapOrder` or `screenOrder`: a plan that dropped a hidden
     * placement would be indistinguishable from a caller that forgot to build the orders, which is
     * the one thing that check exists to catch. `planGlobeSpatial` therefore keeps every entry and
     * discards the horizon verdict, and this re-resolution — the same re-resolution Mercator has
     * always done here, on the same purity argument — recovers it.
     *
     * Under Mercator this is `resolvePlacement` and nothing else, so no mercator pixel moves.
     */
    private fun drawTimePlacement(rawPlacement: Placement): ResolvedPlacement? {
        val placement = groundResolved(rawPlacement)
        return when (camera) {
            is ResolvedMercatorCamera -> resolvePlacement(placement, camera).requireResolvedAtDrawTime()
            is ResolvedGlobeCamera -> {
                val resolved = resolveGlobePlacement(placement, camera).requireResolvedAtDrawTime()
                if (resolved.beyondHorizon) null else resolved.placement
            }
        }
    }

    /**
     * ADR 0040's `GROUND_RELATIVE`, spent: the declared altitude becomes an offset above the surface
     * the ground drew under the anchor in this same frame.
     *
     * **The whole of the resolution is an addition in metres, and that is not a shortcut.** Both
     * projections already carry altitude in metres all the way to their own conversion --
     * `projectMercator`'s `altitude / (C * cos latitude)` and `projectGlobe`'s `R + altitude * R / a`
     * -- and both of those conversions are the *same expression* the corresponding ground vertex
     * shader applies to its DEM metres. So adding here puts the anchor on the ground in both modes
     * with no mode-specific arithmetic, and a second conversion would be the drift ADR 0039's erratum
     * warns about wearing a helpful face.
     *
     * **No lift.** [GROUND_DRAPE_LIFT_METRES] exists for a surface drawn coplanar with the ground; a
     * sticker is a screen-parallel billboard carrying one depth and a model has volume and writes its
     * own, so neither is a second copy of the terrain. Task 14's spike says so in as many words, and
     * lifting them would move a pin off the ridge it was asked to stand on to fix a defect it does
     * not have.
     *
     * `GROUND_RELATIVE` on a `SCREEN` position cannot reach here: `Placement`'s own `require` refuses
     * that pairing at construction, which is why this reads the mode and never the anchoring.
     */
    private fun groundResolved(placement: Placement): Placement {
        if (placement.altitudeMode != AltitudeMode.GROUND_RELATIVE) return placement
        val surface = groundSurface ?: return placement
        val metres = surface.elevationMetresBeneath(
            latitude = placement.position.x,
            unwrappedLongitude = placement.position.y,
            cellsPerTileSide = frameGroundCells,
        ) ?: return placement
        // Rebuilt rather than copied: `Placement` is a hand-written class with an `init` contract and
        // no `copy`, and every field is carried across so that a field added later fails to compile
        // here instead of being silently dropped from every ground-relative placement.
        return Placement(
            positionMode = placement.positionMode,
            position = Vector3(placement.position.x, placement.position.y, placement.position.z + metres),
            rotationMode = placement.rotationMode,
            rotation = placement.rotation,
            scaleMode = placement.scaleMode,
            scale = placement.scale,
            altitudeMode = placement.altitudeMode,
        )
    }

    /**
     * The drape a `GROUND_RELATIVE` [Geometry] rides, or `null` for every other geometry and every
     * frame with no drawn terrain.
     *
     * **[GROUND_DRAPE_LIFT_METRES] is added here rather than by the caller** so that the one place
     * that decides a geometry is coplanar with the ground is the one place that pays for it, and so
     * that a node over a coverage gap -- where the lookup answers `null` -- gets neither the surface
     * nor the lift, which is what keeps the gap flat at exactly the altitude the consumer declared.
     */
    private fun groundDrapeFor(geometry: Geometry): GroundDrape? {
        if (geometry.altitudeMode != AltitudeMode.GROUND_RELATIVE) return null
        val surface = groundSurface ?: return null
        return GroundDrape { mercatorX, mercatorY ->
            surface.elevationMetresAt(mercatorX, mercatorY, frameGroundCells)
                ?.plus(GROUND_DRAPE_LIFT_METRES)
                ?: 0.0
        }
    }

    /**
     * How many cells a [Geometry] is subdivided into: the camera's own answer, raised to the ground's
     * node spacing when the geometry is draped.
     *
     * An undraped geometry gets exactly what it got in `0.3.0` -- one cell under Mercator, the
     * curvature count on a globe -- because [drapeCellsPerSide] is consulted only through a non-null
     * drape.
     */
    private fun geometryCellsPerSideFor(geometry: Geometry, drape: GroundDrape?): Int {
        val fromCamera = when (camera) {
            is ResolvedMercatorCamera -> geometryCellsPerSide(geometry, camera)
            is ResolvedGlobeCamera -> geometryCellsPerSide(geometry, camera)
        }
        if (drape == null) return fromCamera
        val lod = scene.groundTiles.firstOrNull()?.instance?.lod ?: return fromCamera
        val worldSize = when (camera) {
            is ResolvedMercatorCamera -> camera.worldSizeLogicalPixels
            is ResolvedGlobeCamera -> camera.worldSizeLogicalPixels
        }
        return maxOf(
            fromCamera,
            drapeCellsPerSide(
                geometry = geometry,
                worldSizeLogicalPixels = worldSize,
                groundCellsPerTileSide = frameGroundCells,
                selectedLod = lod,
            ),
        )
    }

    /**
     * One map-anchored sticker's draw instance. [composeMapCameraSpaceModel]'s own `require` is what
     * catches a planner regime that contradicts the placement's resolution, which is the only way
     * these two can now disagree.
     */
    private fun mapAnchoredSticker(sticker: SceneSticker): ResolvedSticker? {
        val resolved = drawTimePlacement(sticker.placement) ?: return null
        return ResolvedSticker(
            modelViewProjection = composeMapModelViewProjection(camera, resolved, sticker.localDimensions()),
            texture = sticker.texture,
        )
    }

    /** One screen-composited sticker's draw instance; see [mapAnchoredSticker] for the regime cross-check. */
    private fun screenCompositedSticker(sticker: SceneSticker): ResolvedSticker {
        val resolved = requireNotNull(drawTimePlacement(sticker.placement)) {
            "a screen-composited placement is never behind the globe's limb"
        }
        return ResolvedSticker(
            modelViewProjection = composeScreenModelViewProjection(
                scene.outputPixelSize,
                resolved,
                sticker.localDimensions(),
            ),
            texture = sticker.texture,
        )
    }
}

/**
 * `CONTEXT.md`: a Sticker draws "as a centred local XY quad whose width and height are the image's
 * pixel dimensions" — so the image's own dimensions scale the unit quad BEFORE the placement's own
 * (map-metres-per-unit or screen-pixels-per-unit) scale is applied, exactly the way
 * `affineModelMatrix`'s per-axis scale composes.
 */
private fun SceneSticker.localDimensions(): DoubleVector3 =
    DoubleVector3(imageWidthPixels.toDouble(), imageHeightPixels.toDouble(), 1.0)

/**
 * Turns one [SceneModel] into the flat list of primitive instances [drawModels] draws, resolving
 * [SceneModel.placement] against [camera] exactly as the sticker path does.
 *
 * **The node transform goes between the placement and the vertex.** A model's placement produces a
 * single camera-space model matrix for the whole asset ([composeMapCameraSpaceModel], with the
 * `(1, 1, 1)` local dimensions `CONTEXT.md` prescribes — "Model local dimensions are GLB
 * coordinates", so there is no per-asset pixel size to pre-scale by the way a sticker's image has).
 * Each draw item's own node then contributes its already-composed global transform on the right, so
 * a vertex travels `projection * placement * node`. That product stays `Double` end to end and
 * narrows once, in [toColumnMajorFloatArray] — composing the node transform onto an already-narrowed
 * placement matrix would throw away the precision the rest of this file exists to preserve.
 *
 * **Three draw items are dropped rather than drawn wrong**, each for a reason the types already
 * state. A node the default scene never reaches has no global transform, and inventing one would put
 * the mesh at the origin. A `(mesh, primitive)` pair with no uploaded geometry has nothing to draw.
 * And a singular camera-space model matrix has no normal matrix ([modelNormalMatrix] returns `null`
 * rather than substituting the identity), which is exactly the case a zero [Placement.scale]
 * produces — `CONTEXT.md` calls zero scale valid, and a zero-volume model correctly paints nothing.
 *
 * [ResolvedModelPrimitive.reverseWinding] is taken from the *node's* global transform rather than
 * from the full camera-space product, which is glTF's own rule and is equivalent here: a placement
 * contributes a proper rotation and a non-negative scalar scale, so it can never flip handedness.
 */
private fun resolveModel(
    sceneModel: SceneModel,
    camera: ResolvedFrameCamera,
    resolved: ResolvedPlacement,
): ResolvedModel {
    // ADR 0029 refuses a SCREEN-positioned Model at frame planning -- both spatial planners return
    // `screenPositionedModelFailure()` before acquisition -- so one cannot legitimately reach a draw.
    require(resolved.drawRegime == DrawRegime.MAP_OCCLUDED) {
        "ADR 0029 refuses a SCREEN-positioned Model before drawing; one reached SceneContent"
    }
    val placementModel = composeMapCameraSpaceModel(camera, resolved)
    val model = sceneModel.model

    val primitives = ArrayList<ResolvedModelPrimitive>(model.drawItems.size)
    for (item in model.drawItems) {
        val nodeTransform = sceneModel.nodeTransforms.getOrNull(item.nodeIndex) ?: continue
        val uploaded = sceneModel.uploaded[item.meshIndex to item.primitiveIndex] ?: continue
        val cameraSpaceModel = placementModel * nodeTransform
        val normalMatrix = modelNormalMatrix(cameraSpaceModel) ?: continue
        val material = model.primitiveFor(item).material
        primitives += ResolvedModelPrimitive(
            uploaded = uploaded,
            modelViewProjection = (camera.projectionMatrix * cameraSpaceModel).toColumnMajorFloatArray(),
            normalMatrix = normalMatrix,
            material = material,
            baseColourTexture = sceneModel.overrideTexture
                ?: material.baseColourImageIndex?.let { sceneModel.imageTextures.getOrNull(it) },
            jointMatrices = item.skinIndex
                ?.let { sceneModel.jointMatricesBySkin[it] }
                ?.let { packJointMatrices(it) },
            reverseWinding = nodeTransform.linearDeterminant() < 0.0,
        )
    }
    return ResolvedModel(primitives)
}

/**
 * The determinant of this affine matrix's upper-left 3x3 linear block, which is the only part of it
 * that can reverse a triangle's winding. Its *sign* is all any caller reads; the magnitude is never
 * compared against a tolerance, because a near-zero determinant is a degeneracy
 * [modelNormalMatrix] already refuses on its own terms.
 */
private fun DoubleMatrix4.linearDeterminant(): Double =
    this[0, 0] * (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) -
        this[0, 1] * (this[1, 0] * this[2, 2] - this[1, 2] * this[2, 0]) +
        this[0, 2] * (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0])

/**
 * Unwraps a draw-time re-resolution (of a [Placement], [Geometry], or [com.rohittp.reng.Camera])
 * that a reviewer established CANNOT legitimately fail: only per-object resolution is re-derived at
 * draw time (the camera itself arrives already resolved), and every resolver this calls
 * ([resolvePlacement], [geometryGrid], [com.rohittp.reng.internal.projection.resolveMercatorCamera])
 * is pure and deterministic in its inputs, which are themselves immutable
 * (`com.rohittp.reng.RenGPreparedFrame` snapshots every mutable input before this ever runs — see
 * `Geometry.uniforms`/`.textures`'s KDoc). A [SpatialOutcome.Failure] reaching here is therefore a
 * caller contract violation, not a legitimate runtime outcome.
 *
 * **Typed, not [error].** This used to throw a bare, untyped `IllegalStateException` via [error] —
 * which contradicted RenG's typed-failure contract at exactly the boundary `drawFrame`'s
 * `try`/`finally` cannot shield a consumer from (state restoration still runs, but the exception
 * still escapes to the caller of `Renderer.draw`).
 *
 * **`RenGErrorCode.INVALID_VALUE` at [PipelineStage.DRAW], not `GPU_OPERATION_FAILED`.** A
 * resolution failure is semantically an invalid-value fault: [resolvePlacement], [geometryGrid],
 * and [com.rohittp.reng.internal.projection.resolveMercatorCamera] all report their OWN internal
 * failures as `INVALID_VALUE` at `FRAME_PLANNING`, so this reuses the SAME code, only relocated to
 * the stage it fires from here. `GPU_OPERATION_FAILED` ([glOperationFailure]) is
 * [GlErrorQueue]'s own wrapper for a genuine `glGetError()` result; reporting it here would send a
 * consumer to inspect their GL state when the actual fault is in their `FramePlan`. Extending
 * `internal.requireAllowedFailureContext`/`failureRule`'s allowlist to permit `INVALID_VALUE` at
 * `DRAW` is a private-file change invisible to `checkKotlinAbi` and costs no more than the reused
 * code it replaces — the no-new-public-ABI constraint never required picking the wrong code, it
 * only made the reused one the path of least resistance.
 *
 * `internal`, not `private`, specifically so a test can drive a synthetic
 * [SpatialOutcome.Failure] directly and assert the resulting shape without needing to construct an
 * actually-unreachable end-to-end scenario. Shared by [com.rohittp.reng.RenGRenderer]'s own
 * draw-time camera re-resolution, which used to duplicate this exact `error(...)` pattern.
 */
internal fun <T> SpatialOutcome<T>.requireResolvedAtDrawTime(): T = when (this) {
    is SpatialOutcome.Success -> value
    is SpatialOutcome.Failure -> throw renGFailure(
        code = RenGErrorCode.INVALID_VALUE,
        stage = PipelineStage.DRAW,
        failureContext = failureContextDiagnostic(stage = PipelineStage.DRAW),
    )
}

/**
 * The informational `uGeometryBounds` payload: west, south, east, north in degrees, straight from
 * [Geometry]'s own construction-time-validated corners (`topLeft.x > bottomRight.x` and
 * `topLeft.y < bottomRight.y`). Never fed into a vertex position — see [UNIFORM_GEOMETRY_BOUNDS].
 */
private fun Geometry.boundsWestSouthEastNorth(): FloatArray = floatArrayOf(
    topLeft.y.toFloat(),
    bottomRight.x.toFloat(),
    bottomRight.y.toFloat(),
    topLeft.x.toFloat(),
)

/**
 * A geometry carries no [Placement] (`CONTEXT.md`), so its vertices need no per-object model
 * matrix — [geometryGrid] already resolved and projected every grid vertex directly into
 * camera-relative logical pixels. The uniform documented as `uModelViewProjection` is therefore
 * exactly the camera's own view-projection for a geometry, and it stays a plain linear matrix on a
 * globe too, because the nonlinearity was spent on the CPU before any vertex was handed over.
 */
internal fun composeGeometryViewProjection(camera: ResolvedMercatorCamera): FloatArray =
    (camera.projectionMatrix * camera.viewMatrix).toColumnMajorFloatArray()

/**
 * The inverse of that same product, column-major, or `null` when the camera does not invert
 * (ADR 0072) -- what `rengBackdropInverseViewProjection` carries to a backdrop shader.
 *
 * Its own named function rather than three lines at the call site, for the reason
 * `resolvedBackdropFor` is one: the factor order is the whole content, `view * projection` is a
 * perfectly valid matrix that draws a perfectly plausible wrong grid, and no GL call log can tell
 * the two apart. Naming it puts the order somewhere a test can reach.
 *
 * Takes [ResolvedFrameCamera] rather than either concrete camera because both modes build these
 * two matrices from the same two functions. What differs is the ground, not the matrix: under
 * Mercator it is the plane `z = 0`, under a globe that plane is only the sphere's tangent at the
 * anchor -- see ADR 0072.
 */
internal fun composeBackdropInverseViewProjection(camera: ResolvedFrameCamera): FloatArray? =
    (camera.projectionMatrix * camera.viewMatrix).inverse()?.toColumnMajorFloatArray()

/**
 * The same matrix for a globe camera, and it is the same two factors in the same order.
 *
 * It is **not** [com.rohittp.reng.internal.projection.globeFixedViewProjection], which the globe
 * ground uploads: that one carries the globe-fixed-to-camera-relative transform as a third factor
 * because the ground's vertex shader emits a unit sphere direction. A `Geometry`'s vertices are
 * projected on the CPU and arrive already camera-relative — [geometryGrid] applies exactly that
 * third factor itself, in `Double`, before narrowing — so folding it in here would apply it twice.
 *
 * That is the whole of ADR 0008 surviving the globe: what a consumer's shader receives is still a
 * position and still a linear view-projection, because the nonlinearity was spent before the vertex
 * was handed over. Nothing in production reaches this yet — a `PreparedFrame` carries no projection
 * mode and `FramePlanningCore` still refuses `GLOBE` — and closing that seam is task 10's.
 */
internal fun composeGeometryViewProjection(camera: ResolvedGlobeCamera): FloatArray =
    (camera.projectionMatrix * camera.viewMatrix).toColumnMajorFloatArray()

/**
 * Composes one ground tile's model-view-projection matrix.
 *
 * The model matrix is expressed in **map** space — `Translate(centre) * Scale(side, side, 1)` on the
 * `z = 0` plane — and [ResolvedMercatorCamera.viewMatrix] is applied after it, deliberately unlike
 * [composeMapModelViewProjection], which applies its rotation and scale in *camera* space. A sticker
 * quad is oriented by its own resolved [ResolvedPlacement.directionTransform] and faces the camera
 * when that transform is the identity; a ground tile lies flat on the map whatever the camera's
 * pitch, so its local axes are map axes and it must be transformed before the view, not after.
 *
 * Every term stays `Double` until the single narrowing in [toColumnMajorFloatArray], and the quad
 * arrives already camera-relative from
 * [com.rohittp.reng.internal.planning.resolveBasemapTileQuad], so no absolute Mercator coordinate is
 * ever narrowed to `Float`.
 */
internal fun composeGroundModelViewProjection(
    camera: ResolvedMercatorCamera,
    quad: BasemapTileQuad,
): FloatArray {
    val mapSpaceModel = DoubleMatrix4.fromRows(
        listOf(
            listOf(quad.sideLogicalPixels, 0.0, 0.0, quad.centreXLogicalPixels),
            listOf(0.0, quad.sideLogicalPixels, 0.0, quad.centreYLogicalPixels),
            listOf(0.0, 0.0, 1.0, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )
    return (camera.projectionMatrix * camera.viewMatrix * mapSpaceModel).toColumnMajorFloatArray()
}

/**
 * Composes a map-anchored sticker or model's model-view-projection matrix from
 * [PlacementResolver][com.rohittp.reng.internal.planning.resolvePlacement]'s output and
 * [CameraMatrices][com.rohittp.reng.internal.projection.resolveMercatorCamera]'s output.
 *
 * [ResolvedPlacement.directionTransform] is already expressed as the rotation to apply directly in
 * *camera space* (`PlacementResolver` pre-multiplies out the view matrix's own rotation via the
 * anchor's and the camera's local ENU bases), so the anchor's position is first carried into view
 * space through [camera]'s view matrix, and [ResolvedPlacement.directionTransform] /
 * [ResolvedPlacement.logicalScale] are applied there — never re-multiplied against the view matrix's
 * rotation a second time, which would double-apply it.
 *
 * [localDimensions] scales the local unit quad's x/y/z axes independently BEFORE
 * [ResolvedPlacement.logicalScale] and [ResolvedPlacement.directionTransform] apply — see
 * [affineModelMatrix]'s per-axis (per-column) scale. It defaults to `(1, 1, 1)`, i.e. no local
 * pre-scale at all, which reproduces this function's pre-Task-9b behaviour bit-for-bit. A sticker's
 * caller passes its decoded image's own pixel dimensions here (`CONTEXT.md`: "a centred local XY
 * quad whose width and height are the image's pixel dimensions"); nothing else in this cycle has a
 * local size of its own to report.
 */
internal fun composeMapModelViewProjection(
    camera: ResolvedFrameCamera,
    placement: ResolvedPlacement,
    localDimensions: DoubleVector3 = DoubleVector3(1.0, 1.0, 1.0),
): FloatArray = (camera.projectionMatrix * composeMapCameraSpaceModel(camera, placement, localDimensions))
    .toColumnMajorFloatArray()

/**
 * The camera-space model matrix half of [composeMapModelViewProjection], kept `Double` and separate
 * because two callers need it at different points in the chain.
 *
 * A sticker wants the whole product straight away and [composeMapModelViewProjection] gives it that.
 * A model needs the matrix itself, twice over: each of its nodes composes its own global transform
 * onto the right of it before the projection applies ([resolveModel]), and [modelNormalMatrix]
 * documents its argument as the **camera-space** model matrix specifically, because
 * [sceneLightDirectionCameraSpace] delivers the light there and a diffuse term computed between two
 * different spaces is wrong in a way no assertion about either space alone would notice.
 *
 * See [composeMapModelViewProjection] for what each term means and why the rotation and scale are
 * applied in camera space rather than re-multiplied against the view matrix.
 *
 * **Both functions take a [ResolvedFrameCamera] rather than a Mercator one, and neither needed a
 * globe arm to do it.** They read only [ResolvedFrameCamera.viewMatrix] and
 * [ResolvedFrameCamera.projectionMatrix], which are the same two expressions in both projection
 * modes, and their other argument is a [ResolvedPlacement] whose `logicalPosition` is already
 * camera-relative east/north/up logical pixels — the frame
 * `internal.planning.resolveCameraRelativeMapPosition` produces under Mercator and
 * `internal.planning.resolveGlobePlacement` produces on a globe. Every mode-specific step was spent
 * before a placement got here, which is what lets one sticker and one model matrix path serve both.
 */
internal fun composeMapCameraSpaceModel(
    camera: ResolvedFrameCamera,
    placement: ResolvedPlacement,
    localDimensions: DoubleVector3 = DoubleVector3(1.0, 1.0, 1.0),
): DoubleMatrix4 {
    require(placement.drawRegime == DrawRegime.MAP_OCCLUDED) {
        "composeMapCameraSpaceModel requires a map-occluded placement"
    }
    val viewSpaceAnchor = camera.viewMatrix.transformAffinePoint(placement.logicalPosition)
    return affineModelMatrix(
        rotation = placement.directionTransform,
        scale = localDimensions * placement.logicalScale,
        translation = viewSpaceAnchor,
    )
}

/**
 * Composes a screen-anchored sticker or model's model-view-projection matrix directly in output
 * pixel space — `CONTEXT.md`'s Screen Anchoring never involves [camera]'s view or projection
 * matrix, since it is "resolution against continuous output-pixel screen space," not map space.
 *
 * [ResolvedPlacement.logicalPosition] is already `(position.x, position.y, 0)` in that pixel space
 * (positive x rightward, positive y downward, per `CONTEXT.md`). [ResolvedPlacement.directionTransform]
 * and every drawn thing's local axes (`CONTEXT.md`: "+x right, +y up, +z normal" for a Sticker) use
 * screen-right/screen-up/toward-viewer axes instead, so [SCREEN_ROTATION_ROW_SIGN] flips the y and z
 * rows of the rotation-and-scale block — never the translation — to reconcile the two conventions
 * before the result is combined with [screenOrthographicProjection].
 *
 * [localDimensions] is the same per-axis local pre-scale [composeMapModelViewProjection] documents;
 * it defaults to `(1, 1, 1)`, reproducing this function's pre-Task-9b behaviour bit-for-bit.
 */
internal fun composeScreenModelViewProjection(
    outputPixelSize: OutputPixelSize,
    placement: ResolvedPlacement,
    localDimensions: DoubleVector3 = DoubleVector3(1.0, 1.0, 1.0),
): FloatArray {
    require(placement.drawRegime == DrawRegime.SCREEN_COMPOSITED) {
        "composeScreenModelViewProjection requires a screen-composited placement"
    }
    val pixelSpaceModel = affineModelMatrix(
        rotation = placement.directionTransform,
        scale = localDimensions * placement.logicalScale,
        translation = placement.logicalPosition,
        rotationScaleRowSign = SCREEN_ROTATION_ROW_SIGN,
    )
    val modelViewProjection = screenOrthographicProjection(outputPixelSize) * pixelSpaceModel
    return modelViewProjection.toColumnMajorFloatArray()
}

/** Flips the y and z rows of a rotation-and-scale block; see [composeScreenModelViewProjection]. */
private val SCREEN_ROTATION_ROW_SIGN: DoubleVector3 = DoubleVector3(1.0, -1.0, -1.0)

/**
 * Builds `Translate(translation) * Rotate(rotation) * Scale(scale)` as a single row-major 4x4
 * matrix, applying [rotationScaleRowSign] to each row of the rotation-and-scale block and [scale]'s
 * three components to each COLUMN of it — never [translation], which is always carried through
 * unchanged. Per-column scaling is what makes [scale] anisotropic: `R * diag(scale.x, scale.y,
 * scale.z)` scales local axis `j` by `scale`'s `j`th component before rotation, exactly the local
 * pre-scale [composeMapModelViewProjection] and [composeScreenModelViewProjection] document. When
 * `scale.x == scale.y == scale.z` (every caller before Task 9b's sticker-sizing fix), this reduces
 * to the previous scalar behaviour bit-for-bit, since scaling every column by the same factor is
 * exactly what multiplying the whole row by that scalar already did.
 */
private fun affineModelMatrix(
    rotation: DoubleMatrix3,
    scale: DoubleVector3,
    translation: DoubleVector3,
    rotationScaleRowSign: DoubleVector3 = DoubleVector3(1.0, 1.0, 1.0),
): DoubleMatrix4 = DoubleMatrix4.fromRows(
    listOf(
        listOf(
            rotationScaleRowSign.x * rotation[0, 0] * scale.x,
            rotationScaleRowSign.x * rotation[0, 1] * scale.y,
            rotationScaleRowSign.x * rotation[0, 2] * scale.z,
            translation.x,
        ),
        listOf(
            rotationScaleRowSign.y * rotation[1, 0] * scale.x,
            rotationScaleRowSign.y * rotation[1, 1] * scale.y,
            rotationScaleRowSign.y * rotation[1, 2] * scale.z,
            translation.y,
        ),
        listOf(
            rotationScaleRowSign.z * rotation[2, 0] * scale.x,
            rotationScaleRowSign.z * rotation[2, 1] * scale.y,
            rotationScaleRowSign.z * rotation[2, 2] * scale.z,
            translation.z,
        ),
        listOf(0.0, 0.0, 0.0, 1.0),
    ),
)

/**
 * Maps continuous output-pixel screen space — `[0, width]` by `[0, height]`, positive x rightward,
 * positive y downward (`CONTEXT.md`'s Screen Anchoring) — to clip space. `z` is fixed at `0`, safely
 * inside `[-1, 1]`, because the screen regime always draws with depth testing disabled
 * ([drawStickers]) and never needs a meaningful depth value.
 */
private fun screenOrthographicProjection(outputPixelSize: OutputPixelSize): DoubleMatrix4 {
    val width = outputPixelSize.width.toDouble()
    val height = outputPixelSize.height.toDouble()
    return DoubleMatrix4.fromRows(
        listOf(
            listOf(2.0 / width, 0.0, 0.0, -1.0),
            listOf(0.0, -2.0 / height, 0.0, 1.0),
            listOf(0.0, 0.0, 0.0, 0.0),
            listOf(0.0, 0.0, 0.0, 1.0),
        ),
    )
}

/**
 * Transforms [point] as a homogeneous `(x, y, z, 1)` column vector through this matrix, assuming
 * (as every affine matrix in this file is) that the bottom row is `(0, 0, 0, 1)` so `w` is always
 * exactly `1` and never needs a perspective divide.
 */
private fun DoubleMatrix4.transformAffinePoint(point: DoubleVector3): DoubleVector3 = DoubleVector3(
    x = this[0, 0] * point.x + this[0, 1] * point.y + this[0, 2] * point.z + this[0, 3],
    y = this[1, 0] * point.x + this[1, 1] * point.y + this[1, 2] * point.z + this[1, 3],
    z = this[2, 0] * point.x + this[2, 1] * point.y + this[2, 2] * point.z + this[2, 3],
)

/** This matrix's elements in the column-major order [GlBinding.uniformMatrix4fv] expects. */
internal fun DoubleMatrix4.toColumnMajorFloatArray(): FloatArray = FloatArray(16) { index ->
    val row = index % 4
    val column = index / 4
    this[row, column].toFloat()
}
