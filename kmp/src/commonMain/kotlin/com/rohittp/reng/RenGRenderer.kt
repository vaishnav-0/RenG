package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.basemap.BasemapStyleManifestOutcome
import com.rohittp.reng.internal.basemap.completeBasemapStyleManifest
import com.rohittp.reng.internal.basemap.tileTimeRoutes
import com.rohittp.reng.internal.basemapNotConfiguredDiagnostic
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.driver.PreparationDriver
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.firewall.AcquiredDemTile
import com.rohittp.reng.internal.firewall.AcquiredLabelCandidates
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.reng.internal.firewall.TerrainAcquisition
import com.rohittp.reng.internal.firewall.TerrainAcquisitionOutcome
import com.rohittp.reng.internal.firewall.reportLabelContentExclusions
import com.rohittp.reng.internal.firewall.reportTerrainDegradation
import com.rohittp.reng.internal.gl.CompositePipeline
import com.rohittp.reng.internal.gl.CompositePipelineResult
import com.rohittp.reng.internal.gl.GeometryPipeline
import com.rohittp.reng.internal.gl.GeometryPipelineResult
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlLifecycleDriver
import com.rohittp.reng.internal.gl.GlObjectHandle
import com.rohittp.reng.internal.gl.GlObjectRegistry
import com.rohittp.reng.internal.gl.GlObjectType
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.GlobeGroundPipeline
import com.rohittp.reng.internal.gl.GlobeGroundPipelineResult
import com.rohittp.reng.internal.gl.GpuTextureResidency
import com.rohittp.reng.internal.gl.withCapturedGlState
import com.rohittp.reng.internal.thread.CallingThread
import com.rohittp.reng.internal.thread.currentCallingThread
import com.rohittp.reng.internal.thread.isCurrentThread
import com.rohittp.reng.internal.gl.GroundPipeline
import com.rohittp.reng.internal.gl.GroundPipelineResult
import com.rohittp.reng.internal.gl.IconBatch
import com.rohittp.reng.internal.gl.IconPipeline
import com.rohittp.reng.internal.gl.IconPipelineResult
import com.rohittp.reng.internal.gl.LabelBatch
import com.rohittp.reng.internal.gl.LabelPipeline
import com.rohittp.reng.internal.gl.LabelPipelineResult
import com.rohittp.reng.internal.gl.ModelPipeline
import com.rohittp.reng.internal.gl.ModelPipelineResult
import com.rohittp.reng.internal.gl.ModelShaderVariant
import com.rohittp.reng.internal.gl.OffscreenSurface
import com.rohittp.reng.internal.gl.OffscreenSurfaceResult
import com.rohittp.reng.internal.gl.RenderContextProfile
import com.rohittp.reng.internal.gl.ResolvedIconQuad
import com.rohittp.reng.internal.gl.Scene
import com.rohittp.reng.internal.gl.SceneContent
import com.rohittp.reng.internal.gl.SceneGeometry
import com.rohittp.reng.internal.gl.SceneGroundTile
import com.rohittp.reng.internal.gl.SceneModel
import com.rohittp.reng.internal.gl.SceneSticker
import com.rohittp.reng.internal.gl.SceneTerrain
import com.rohittp.reng.internal.gl.SceneTileDem
import com.rohittp.reng.internal.gl.StickerPipeline
import com.rohittp.reng.internal.gl.StickerPipelineResult
import com.rohittp.reng.internal.gl.TextureContent
import com.rohittp.reng.internal.gl.TextureLease
import com.rohittp.reng.internal.gl.TextureSamplerState
import com.rohittp.reng.internal.gl.UploadedPrimitive
import com.rohittp.reng.internal.gl.allModelShaderVariants
import com.rohittp.reng.internal.gl.createCompositePipeline
import com.rohittp.reng.internal.gl.createGeometryPipeline
import com.rohittp.reng.internal.gl.createGlobeGroundPipeline
import com.rohittp.reng.internal.gl.createGroundPipeline
import com.rohittp.reng.internal.gl.createIconPipeline
import com.rohittp.reng.internal.gl.createLabelPipeline
import com.rohittp.reng.internal.gl.createModelPipeline
import com.rohittp.reng.internal.gl.createOffscreenSurface
import com.rohittp.reng.internal.gl.createStickerPipeline
import com.rohittp.reng.internal.gl.defaultSamplerStateFor
import com.rohittp.reng.internal.gl.deleteCompositePipeline
import com.rohittp.reng.internal.gl.deleteGeometryPipeline
import com.rohittp.reng.internal.gl.deleteGlObjects
import com.rohittp.reng.internal.gl.deleteGlobeGroundPipeline
import com.rohittp.reng.internal.gl.deleteGroundPipeline
import com.rohittp.reng.internal.gl.deleteIconPipeline
import com.rohittp.reng.internal.gl.deleteLabelPipeline
import com.rohittp.reng.internal.gl.deleteModelPipeline
import com.rohittp.reng.internal.gl.deleteOffscreenSurface
import com.rohittp.reng.internal.gl.deleteStickerPipeline
import com.rohittp.reng.internal.gl.demDecodeCoefficients
import com.rohittp.reng.internal.gl.drawFrame
import com.rohittp.reng.internal.gl.jointMatricesForSkin
import com.rohittp.reng.internal.gl.offscreenSurfaceDescriptorFor
import com.rohittp.reng.internal.gl.requireResolvedAtDrawTime
import com.rohittp.reng.internal.gl.uploadDemTexture
import com.rohittp.reng.internal.gl.uploadGlyphAtlas
import com.rohittp.reng.internal.gl.uploadModelPrimitive
import com.rohittp.reng.internal.gl.uploadSpriteAtlas
import com.rohittp.reng.internal.gl.uploadPremultipliedTexture
import com.rohittp.reng.internal.gl.uploadTexture
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.AcceleratedSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.label.FadedLabel
import com.rohittp.reng.internal.label.LabelFadeState
import com.rohittp.reng.internal.label.LabelGroundElevation
import com.rohittp.reng.internal.label.advanceLabelFade
import com.rohittp.reng.internal.label.placeLabels
import com.rohittp.reng.internal.metrics.EngineMetricRecorder
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.PreparedFrameFact
import com.rohittp.reng.internal.lifecycle.RenderTargetFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.model.AnimationResolution
import com.rohittp.reng.internal.model.DecodedModel
import com.rohittp.reng.internal.model.ModelDecodeResult
import com.rohittp.reng.internal.model.composeGlobalTransforms
import com.rohittp.reng.internal.model.decodeModel
import com.rohittp.reng.internal.model.resolveAnimationSelectors
import com.rohittp.reng.internal.model.sampleAnimationTracks
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.planning.DrawnThingReference
import com.rohittp.reng.internal.planning.FramePlanningCore
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.preparation.buildResourceOperationDefinition
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.residentGpuTexturesOverBudgetDiagnostic
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.DemTexels
import com.rohittp.reng.internal.terrain.DemTileCoordinate
import com.rohittp.reng.internal.terrain.GroundSurface
import com.rohittp.reng.internal.terrain.GroundSurfaceTile
import com.rohittp.reng.internal.terrain.TerrainGranularityInputs
import com.rohittp.reng.internal.terrain.canPadDemTexture
import com.rohittp.reng.internal.terrain.demTileWindowFor
import com.rohittp.reng.internal.terrain.frameGroundCellsPerTileSide
import com.rohittp.reng.internal.terrain.padDemTexture
import com.rohittp.reng.internal.terrain.terrainCellsPerTileSide
import com.rohittp.rentile.PreparedStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

// This file lives in the `com.rohittp.reng` package rather than under `internal/` on disk because
// Renderer, PreparedFrame, and RenderTarget are all `sealed`, and Kotlin requires a sealed type's
// direct implementers to share its package (not merely its module). Every declaration below stays
// `internal` visibility regardless -- none of it appears in the ABI dump.

/**
 * One [Sticker]'s [Placement] paired with its decoded, not-yet-uploaded image and the [ResourceKey]
 * [FramePlanningCore]'s static traversal already derived for it — the same key
 * [RenGRenderer.performDraw]'s texture cache uses so a repeated draw of an unchanged sticker image
 * reuses its GL texture (Task 9b item 1) instead of re-uploading it every frame.
 */
internal class PreparedSticker(
    internal val placement: Placement,
    internal val resourceKey: ResourceKey,
    internal val image: DecodedImage,
)

/**
 * One [Geometry] consumer texture already fetched and decoded at `prepare()` time, paired with the
 * [ResourceKey] its sampler name resolved to — the same shape [PreparedSticker] establishes for a
 * sticker's image, reused here so [RenGRenderer.performDraw]'s texture cache covers both draw paths
 * identically.
 */
internal class PreparedGeometryTexture(
    internal val resourceKey: ResourceKey,
    internal val image: DecodedImage,
)

/**
 * One [Geometry] with both of Task 9b item 3's snapshots already taken at `prepare()` time:
 * [uniformsSnapshot] is a `.toMap()` copy of [Geometry.uniforms] and [consumerTextures] replaces
 * [Geometry.textures]' locators with their already-fetched, already-decoded content — never the
 * caller's own live `Map` references. [geometry] itself is retained only for its immutable
 * `topLeft`/`bottomRight`/`shaderPair` fields, which carry no such live-reference hazard.
 */
internal class PreparedGeometry(
    internal val geometry: Geometry,
    internal val uniformsSnapshot: Map<String, ShaderValue>,
    internal val consumerTextures: Map<String, PreparedGeometryTexture>,
)

/**
 * One [Model] with every CPU-side derivation this frame needs already done, synchronously, inside
 * `RenGRenderer.prepare()`: the GLB decoded, its animation selectors resolved, its tracks sampled at
 * their own `timeSeconds`, its node hierarchy composed against that sampled pose, and one joint
 * palette per skin any draw item names.
 *
 * **Animation is sampled here rather than at draw time, and that is a correctness claim rather than a
 * scheduling one.** [AnimationTrack.timeSeconds] is part of the [FramePlan], so the sampled pose is a
 * pure function of the plan the frame's canonical identity already hashed. Sampling at draw would make
 * a second `draw()` of the same prepared frame able to produce different pixels from the first, which
 * that identity already promises it cannot — the same guarantee [PreparedGeometry] makes for a
 * consumer's own live `uniforms`/`textures` maps.
 *
 * [glbKey] is the model's `EXTERNAL`/[ResourceClass.MODEL_GLB] key, retained because it is what
 * `ResourceKeyDeriver.modelGeometry` and `.modelImage` namespace their own derivations by — the draw
 * path keys every uploaded primitive and every embedded image off it, so two frames over the same GLB
 * upload each of them exactly once (ADR 0018).
 *
 * [overrideTexture] is [Model.texture], already fetched and decoded, reusing [PreparedGeometryTexture]
 * because a model's base-colour override and a geometry's consumer texture are the same thing: an
 * external PNG paired with the key it was acquired under. `null` when the model declares none.
 *
 * Nothing here is GL-shaped. Uploading the primitives and the images needs a live render context and
 * therefore happens in [RenGRenderer.performDraw], exactly as it does for a sticker's image.
 */
internal class PreparedModel(
    internal val placement: Placement,
    internal val glbKey: ResourceKey,
    internal val model: DecodedModel,
    internal val overrideTexture: PreparedGeometryTexture?,
    internal val nodeTransforms: List<DoubleMatrix4?>,
    internal val jointMatricesBySkin: Map<Int, List<DoubleMatrix4>>,
)

/**
 * One unwrapped basemap tile draw instance, already paired at `prepare()` time with the
 * [ResourceKey] naming the rendered tile it draws.
 *
 * The pairing is derived here, once per frame, rather than at draw time, for the same reason
 * [PreparedSticker] carries its own key: identity derivation (ADR 0018) is `prepare()`'s job, it
 * costs a SHA-256 per **canonical** tile rather than per instance when memoized as it is below, and
 * doing it here lets one `check` prove every instance names a tile this frame actually rendered
 * instead of leaving a lookup miss to be discovered inside a GL draw call.
 *
 * N world copies of one canonical tile produce N entries sharing one [resourceKey] -- that is
 * exactly what `basemapTileKey`'s instance overload is for.
 */
internal class PreparedGroundInstance(
    internal val instance: BasemapTileInstance,
    internal val resourceKey: ResourceKey,
)

/**
 * One frame's terrain: everything the ground needs to displace itself, decoded but not yet padded and
 * not yet on the GPU.
 *
 * **Nothing here decodes anything, and until Rentile `0.7.0` that sentence was the opposite.** The
 * encoded bytes used to be carried to the draw and inflated there by RenG's own PNG decoder, which
 * cost a decode of every DEM in every frame's request -- the visible set plus its perimeter ring --
 * even when every padded texture was already resident, and which reached exactly **one** of the six
 * corpus styles that ask for terrain, because the other five serve WebP. The engine now hands over
 * the pixels it decoded to validate the tile, so that debt is not paid down but deleted: there is no
 * decode left to skip.
 *
 * **What is still per-frame is the padding**, because `uploadDemTexture` consults residency only
 * after it is handed an assembled `PaddedDemTexture`. The repair `PaddedDemTexture.contentKey`'s own
 * KDoc names -- composing that key from the nine digests, which are known here, before assembling
 * anything -- is now fully available and is not taken in this task.
 *
 * [demTiles] is keyed by **requested** canonical tile, which is how the acquisition matched it
 * (ADR 0041: never by list index). Its `sourceTile` may differ -- an overzoom or a world wrap -- and
 * [texelsBySource] is therefore keyed by the source, since several requested tiles can share one DEM
 * image and one padded texture.
 */
internal class PreparedTerrain(
    internal val encoding: DemEncoding,
    internal val tileSizePx: Int,
    internal val exaggeration: Double,
    demTiles: Map<CanonicalBasemapTile, AcquiredDemTile>,
) {
    private val demSnapshot: Map<CanonicalBasemapTile, AcquiredDemTile> = LinkedHashMap(demTiles)

    /**
     * One entry per distinct DEM image, which is what a padded texture is built around: several
     * requested tiles overzooming into one ancestor arrive as several entries carrying the identical
     * texels and the identical digest.
     */
    internal val texelsBySource: Map<DemTileCoordinate, DemTexels> =
        LinkedHashMap<DemTileCoordinate, DemTexels>().apply {
            demSnapshot.values.forEach { dem -> if (dem.sourceTile !in this) put(dem.sourceTile, dem.texels) }
        }

    /**
     * Every ground tile this frame can actually displace, and the whole of what ADR 0041's coverage
     * diagnostic counts against.
     *
     * **This is the answer the draw would otherwise have reached alone and silently.** A tile
     * displaces only if it has a window onto its source ([demTileWindowFor]) and a source that can be
     * padded ([canPadDemTexture]); both are pure functions of what this class already holds, so the
     * frame can know its own coverage during `prepare()`, where the diagnostic fires, instead of
     * discovering it at draw time where nothing was listening. Task 13's harness pass printed
     * `diagnostics: none` for a frame that discarded every DEM it fetched, and this is why it could.
     *
     * **[canPadDemTexture] excludes nothing today, and is here on purpose.** Every tile that reached
     * this class came through `TerrainAcquisition.matchDemTiles`, which admits only a positive square
     * of exactly [tileSizePx]; a window then bounds the source's zoom below the padding's own bound
     * and the texels are keyed by the very source tiles being asked about. So all three of
     * [padDemTexture]'s refusals are already unreachable from here -- by construction, which is
     * precisely the kind of agreement that stops holding without saying so. Naming the probe means a
     * refusal added to [padDemTexture] later reaches the diagnostic instead of reaching the draw's
     * silence, which is the defect this whole task exists to close.
     *
     * `resolveDemTextures` pads and uploads exactly these tiles, so the report and the picture cannot
     * disagree.
     */
    internal val elevatedTiles: Set<CanonicalBasemapTile> =
        demSnapshot.filterValues { dem ->
            demTileWindowFor(dem.requestedTile, dem.sourceTile) != null &&
                canPadDemTexture(dem.sourceTile, texelsBySource)
        }.keys

    /** Every acquired DEM, visible tiles and ring alike, in request order. */
    internal val demTiles: Map<CanonicalBasemapTile, AcquiredDemTile> get() = LinkedHashMap(demSnapshot)

    /**
     * The same terrain as a CPU-readable surface, or `null` when nothing at [lod] displaces.
     *
     * **It is assembled from [elevatedTiles] and [demTileWindowFor], which is `resolveDemTextures`'
     * own pair**, so the surface a `GROUND_RELATIVE` altitude rides and the surface the GPU draws are
     * built from one answer about which tiles displace and one answer about where each one samples.
     * The two could otherwise disagree tile by tile, and the picture of that disagreement -- content
     * riding a surface the ground is not drawing -- looks exactly like a lookup that is merely
     * imprecise.
     *
     * [texelsBySource] is handed over whole rather than filtered to the displacing tiles, because the
     * ring is what a padded lookup at a tile's far edge reads and a filtered map would replicate the
     * tile's own edge there instead -- reopening, for the drape alone, the seam
     * [com.rohittp.reng.internal.terrain.padDemTexture] closed for the ground.
     */
    internal fun groundSurface(lod: Int): GroundSurface? {
        val tiles = LinkedHashMap<DemTileCoordinate, GroundSurfaceTile>(elevatedTiles.size)
        elevatedTiles.forEach { tile ->
            if (tile.lod != lod) return@forEach
            val dem = demSnapshot[tile] ?: return@forEach
            val window = demTileWindowFor(dem.requestedTile, dem.sourceTile) ?: return@forEach
            tiles[DemTileCoordinate(z = tile.lod, x = tile.canonicalX, y = tile.tileY)] =
                GroundSurfaceTile(source = dem.sourceTile, window = window)
        }
        if (tiles.isEmpty()) return null
        return GroundSurface(
            lod = lod,
            interiorSizePx = tileSizePx,
            exaggeration = exaggeration,
            encoding = encoding,
            tiles = tiles,
            texelsBySource = texelsBySource,
        )
    }

    internal fun demTileFor(tile: CanonicalBasemapTile): AcquiredDemTile? = demSnapshot[tile]

    override fun toString(): String =
        "PreparedTerrain(encoding=$encoding, tileSizePx=$tileSizePx, exaggeration=$exaggeration, " +
            "dem=${demSnapshot.size}, elevated=${elevatedTiles.size})"
}

/**
 * One frame's labels, everything CPU-shaped about them already done: placed, collided, faded, and
 * with the atlas they sample already decoded to canonical RGBA8.
 *
 * **Every derivation here happened during `prepare()`, and ADR 0035 requires that rather than merely
 * permitting it.** Collision resolves once per preparation and never during a draw, because a frame
 * drawn twice must not fade twice; and the fade this carries was folded into `previousLabelFade`
 * at the same moment, so these quads are already this frame's answer rather than an input to one.
 *
 * What is *not* done is the upload: the atlas reaches the GPU in [RenGRenderer.drawResolvedFrame],
 * under [atlasKey], on exactly the terms a ground tile's texture does — `prepare()` holds no render
 * context, and an atlas already resident from an earlier frame must cost neither a decode nor an
 * upload. [atlas] is retained for the miss.
 */
internal class PreparedLabelFrame(
    internal val atlasKey: ResourceKey,
    internal val atlas: DecodedImage,
    labels: List<FadedLabel>,
    /**
     * This frame's icons, already faded, or empty when no symbol kept one — because the style
     * declares no sprite, because no icon resolved against the manifest, or because collision took
     * every one of them.
     *
     * **Flattened here rather than left inside [labels], and that is what makes the draw's phase
     * order expressible.** An icon is drawn out of the sprite atlas and a glyph out of the glyph
     * atlas, so the two halves of one symbol are two batches whatever the data structure says; a
     * draw that walked [labels] would have to re-separate them per frame. They are in
     * [com.rohittp.reng.internal.label.placeLabels]' own order, so the flattening preserves exactly
     * the precedence the glyph half is drawn in.
     *
     * The fade is applied at this point rather than by `advanceLabelFade`, which knows only about
     * glyph quads: [FadedLabel.opacity] is one number per *symbol*, so multiplying it into the icon
     * here is applying the same number to the same symbol's other half, not a second fade.
     */
    icons: List<ResolvedIconQuad> = emptyList(),
    /**
     * The sprite atlas those icons sample and the identity it is resident under, or `null` when
     * [icons] is empty. Retained for the upload's miss on exactly [atlas]'s terms — `prepare()` holds
     * no render context, and an atlas already on the GPU must cost neither a decode nor an upload.
     */
    internal val spriteAtlasKey: ResourceKey? = null,
    internal val spriteAtlas: DecodedImage? = null,
) {
    private val labelSnapshot: List<FadedLabel> = ArrayList(labels)
    private val iconSnapshot: List<ResolvedIconQuad> = ArrayList(icons)

    init {
        require(iconSnapshot.isEmpty() == (spriteAtlasKey == null)) {
            "an icon can only be drawn out of an atlas, and an atlas is only retained for icons"
        }
        require((spriteAtlasKey == null) == (spriteAtlas == null)) {
            "a retained sprite atlas is its bytes and its identity together"
        }
    }

    /** This frame's icons, in the order they are drawn — see the constructor parameter. */
    internal val icons: List<ResolvedIconQuad> get() = ArrayList(iconSnapshot)

    /**
     * This frame's surviving labels, lowest priority first — [placeLabels]' own order, which the
     * label pass consumes verbatim so that where two labels do share pixels the one that would have
     * won the collision paints last.
     */
    internal val labels: List<FadedLabel> get() = ArrayList(labelSnapshot)
}

/**
 * The concrete [PreparedFrame] this cycle's factory produces. Deliberately thin: it retains exactly
 * what [RenGRenderer.draw] needs to assemble a [Scene] — the raw [Camera] value, each sticker's
 * already-decoded image, and each geometry's already-snapshotted uniforms/textures — and nothing
 * GL-shaped, since GPU texture upload and geometry-pipeline compilation both need a live render
 * context and therefore happen in [RenGRenderer.draw], not here.
 *
 * **Task 9b item 3, closed here.** [geometries] no longer retains the exact [Geometry] instances
 * [FramePlan] carried with their live `uniforms`/`textures` `Map` references — every [PreparedGeometry]
 * in [geometries] carries a fixed `.toMap()` snapshot of `uniforms` and fully resolved, already-decoded
 * `consumerTextures`, both taken once, synchronously, inside `RenGRenderer.prepare()`. A consumer
 * mutating their own `uniforms`/`textures` map after `prepare()` returns can no longer change what a
 * later `draw()` on this same frame renders, closing the divergence [Geometry]'s own KDoc used to
 * document as an open gap.
 */
internal class RenGPreparedFrame(
    internal val owner: RenGRenderer,
    override val frameIndex: Long,
    internal val camera: Camera,
    /**
     * Which projection the plan asked for, carried because [camera] alone cannot say: [Camera]'s
     * five fields mean the same thing in both modes and it is the *mode* that decides whether they
     * describe a plane or a sphere (ADR 0037). A Prepared Frame used to carry no projection mode at
     * all, which was honest while `FramePlanningCore` refused `GLOBE`; without it the draw would
     * have to guess, and the guess it would make is the one that draws a globe frame flat.
     */
    internal val projectionMode: ProjectionMode,
    internal val drawBasemap: Boolean,
    /**
     * Carried beside [drawBasemap] because a Prepared Frame records the whole plan's draw switches,
     * not the subset the current draw happens to consult, and orthogonal to [drawBasemap] rather than
     * implied by it. The draw does not consult it: by the time a frame exists, `prepare()` has already
     * answered the flag by leaving [labels] `null`, and re-reading a switch whose consequence is
     * already decided is how two authorities on one rule get created.
     */
    internal val drawLabels: Boolean,
    stickers: List<PreparedSticker>,
    geometries: List<PreparedGeometry>,
    /**
     * This frame's models, each already decoded, posed and skinned for exactly this frame — see
     * [PreparedModel] for why every one of those derivations happened during `prepare()` rather than
     * being deferred to the draw.
     */
    models: List<PreparedModel>,
    /**
     * This frame's ground, already rendered by the Rentile engine and already named by RenG's own
     * canonical identity (ADR 0018) — one entry per **canonical** tile, so N Mercator world copies of one
     * tile share one entry, one engine render and one later texture upload. Empty whenever the frame
     * draws no basemap, no style is configured, or the frame selected no tile.
     *
     * The bytes are either the engine's PNG or its already-premultiplied pixels, whichever ADR 0044's
     * budget admitted when this frame was prepared. Either way they are uploaded in
     * [RenGRenderer.performDraw] rather than here, because a tile whose GL texture is still resident
     * from an earlier frame must cost nothing: `prepare()` has no render context, and touching every
     * tile of every frame unconditionally is precisely the per-frame cliff the resident texture budget
     * exists to avoid.
     */
    basemapTiles: List<RenderedBasemapTile>,
    /**
     * Where this frame's ground goes — one entry per **unwrapped** draw instance, so N Mercator world
     * copies of one canonical tile are N placements sharing one entry in [basemapTiles]. Empty exactly
     * when [basemapTiles] is empty.
     */
    groundInstances: List<PreparedGroundInstance> = emptyList(),
    /**
     * This frame's terrain, or `null` when its style declared none, when RenG and the engine did not
     * name the same source, or when the acquisition failed — the last two being ADR 0041's two
     * degradations, which have already been reported by the time a frame exists and leave the ground
     * flat here.
     */
    internal val terrain: PreparedTerrain? = null,
    /**
     * The CPU-readable copy of the surface this frame's ground draws, or `null` when nothing in the
     * frame asked where the ground is — see `RenGRenderer.groundSurfaceFor` for that condition.
     *
     * **It is built during `prepare()` and carried rather than derived at draw time, and that is the
     * one thing about it worth reading twice.** Labels are placed and collided inside `prepare()`
     * (ADR 0035), so their anchors have already ridden this exact object by the time a frame exists;
     * a `GROUND_RELATIVE` sticker, model or geometry rides it during the draw. Deriving it twice
     * would be two `rgbaSnapshot` copies of every source DEM and two independent answers to which of
     * this frame's tiles displace — and the picture of that disagreement is content riding a surface
     * the ground is not drawing, which looks exactly like a lookup that is merely imprecise.
     */
    internal val groundSurface: GroundSurface? = null,
    /**
     * This frame's labels, or `null` when it drew none — `drawLabels = false`, no configured style, no
     * selected tile, a style that declares no text, or a frame every candidate of which lost its place.
     *
     * Unlike [basemapTiles] this is a *result* rather than raw material: ADR 0035 puts collision and
     * fade inside `prepare()`, so by the time a frame exists its labels are already decided. The draw
     * uploads the atlas and issues the batch; it places nothing.
     */
    internal val labels: PreparedLabelFrame? = null,
    /**
     * Which draw regime each of this frame's drawn things is in, and in what order — taken straight
     * off `MercatorSpatialPlan.mapEntries` / `.screenEntries` at `prepare()` time and carried, never
     * re-derived at draw time.
     *
     * The GL layer used to throw both answers away and rebuild them from a second resolution of every
     * `Placement` plus a second sort inside `drawStickers`, which made the planner's unit-tested split
     * and screen-compositing sort decorative. Snapshotting them here is what makes the planner the one
     * authority: `internal.gl.Scene` consumes these lists and derives neither.
     *
     * [mapOrder] is the planner's declaration order (stickers, then models) and is a statement of
     * regime membership rather than a draw order — ADR 0030's phase order is applied over it by
     * `SceneContent`. [screenOrder] is already sorted into `CONTEXT.md`'s compositing order and is a
     * complete draw order.
     */
    mapOrder: List<DrawnThingReference> = emptyList(),
    screenOrder: List<DrawnThingReference> = emptyList(),
    /**
     * Every resident-cache lease this frame's own preparation took, which `close()` releases exactly once.
     *
     * `CONTEXT.md` defines a lease as held "by a **Prepared Frame** for every distinct resource its plan
     * needs", and this is where that ownership actually lives: the preparation driver collects the leases
     * it takes into the list handed here. Without it every lease a preparation took stayed outstanding for
     * the renderer's whole life, so `freeResources()` could only ever report a matched key deferred and a
     * superseded generation was never reclaimed -- an unbounded leak for a consumer that prepares many
     * frames.
     */
    leases: List<Lease> = emptyList(),
) : PreparedFrame {
    private val stickerSnapshot: List<PreparedSticker> = ArrayList(stickers)
    private val geometrySnapshot: List<PreparedGeometry> = ArrayList(geometries)
    private val modelSnapshot: List<PreparedModel> = ArrayList(models)
    private val basemapTileSnapshot: List<RenderedBasemapTile> = ArrayList(basemapTiles)
    private val groundInstanceSnapshot: List<PreparedGroundInstance> = ArrayList(groundInstances)
    private val mapOrderSnapshot: List<DrawnThingReference> = ArrayList(mapOrder)
    private val screenOrderSnapshot: List<DrawnThingReference> = ArrayList(screenOrder)
    private val leaseSnapshot: MutableList<Lease> = ArrayList(leases)

    internal val stickers: List<PreparedSticker> get() = ArrayList(stickerSnapshot)
    internal val geometries: List<PreparedGeometry> get() = ArrayList(geometrySnapshot)
    internal val models: List<PreparedModel> get() = ArrayList(modelSnapshot)
    internal val basemapTiles: List<RenderedBasemapTile> get() = ArrayList(basemapTileSnapshot)
    internal val groundInstances: List<PreparedGroundInstance> get() = ArrayList(groundInstanceSnapshot)
    internal val mapOrder: List<DrawnThingReference> get() = ArrayList(mapOrderSnapshot)
    internal val screenOrder: List<DrawnThingReference> get() = ArrayList(screenOrderSnapshot)

    /**
     * How many bytes of raw, not-yet-uploaded tile pixels this frame holds (ADR 0044); zero for a
     * frame whose tiles are encoded.
     *
     * Derived from the tiles rather than passed in beside them, so the figure the renderer subtracts
     * at [RenGRenderer.closePreparedFrame] cannot disagree with what this frame actually carries.
     */
    internal val rawTileBytes: Long = basemapTileSnapshot.sumOf { tile ->
        when (val pixels = tile.pixels) {
            is BasemapTilePixels.Raw -> pixels.rgba.size.toLong()
            is BasemapTilePixels.Encoded -> 0L
        }
    }

    internal var closed: Boolean = false
        private set

    /**
     * Hands the leases over, once. A second call returns nothing, which is what keeps `close()` idempotent
     * as `CONTEXT.md` requires: releasing one lease twice is a caller error [Lease] rejects outright.
     */
    internal fun takeLeases(): List<Lease> {
        val taken = ArrayList(leaseSnapshot)
        leaseSnapshot.clear()
        return taken
    }

    internal fun markClosed() {
        closed = true
    }

    override fun close() {
        owner.closePreparedFrame(this)
    }
}

/**
 * Everything one `prepare()` acquired through the consumer's adapters: the decoded images the frame's
 * stickers and geometry textures need, and the ground the engine rendered for it. Two results rather
 * than one because they are two different kinds of thing — a `Map` keyed for lookup by the caller, and
 * an ordered list carried whole onto the prepared frame.
 */
private class FrameAcquisition(
    val decodedImagesByKey: Map<ResourceKey, DecodedImage>,
    val basemapTiles: List<RenderedBasemapTile>,
    /**
     * The compiled style [basemapTiles] were rendered from, or `null` when this frame rendered none.
     * Carried out of acquisition rather than read back off the renderer, because `basemapTileKey` is a
     * function of it and `preparedBasemapStyle` describes the *most recently* prepared frame — which,
     * for a frame prepared earlier and drawn later, is a different style entirely.
     */
    val basemapStyleDigest: String?,
    /**
     * This frame's engine-derived label candidates, or `null` when the frame asked for no labels, had
     * no style configured, or selected no tile to ask about. The batch is handed on unplaced: placement
     * and collision are `prepare()`'s next step, and both are pure functions of it and the camera.
     */
    val labelCandidates: AcquiredLabelCandidates? = null,
    /**
     * The sprite atlas manifest this frame's style declared, as the firewall's own joint sprite gate
     * parsed it, or `null` when this invocation held no jointly valid pair.
     *
     * **Carried out of acquisition because it cannot be read afterwards.** The `OperationRegistry`
     * holding it is discarded when the invocation terminates (ADR 0016), which is also what guarantees
     * one frame's icons can never resolve against another frame's atlas. It is the only route by which
     * `LabelIconRef.imageName` -- an opaque key into resources Rentile deliberately does not expose --
     * becomes atlas geometry.
     */
    val spriteAtlas: SpriteAtlasManifest? = null,
    /**
     * What this frame's terrain acquisition produced, or `null` when the frame rendered no ground at
     * all and therefore asked for none.
     *
     * Carried out of acquisition for [basemapStyleDigest]'s reason and one stronger: DEM tiles are
     * acquired inside the invocation, through its preregistered routes, and the registry that
     * authorised them is discarded when it ends (ADR 0016). It is `NotDeclared` for the 28 of 34
     * corpus styles with no `terrain` block, which is not a degradation and reports nothing.
     */
    val terrain: TerrainAcquisitionOutcome? = null,
    /**
     * The style's own `terrain.exaggeration`, carried beside [terrain] because the two are read from
     * two different documents and only meet here: the multiplier is RenG's reading of the style JSON
     * ([com.rohittp.reng.internal.basemap.BasemapStyleManifest.terrainExaggeration]), while
     * everything else about the source is the engine's compiled descriptor. Rentile ignores the
     * property entirely, so nothing in the descriptor could carry it.
     */
    val terrainExaggeration: Double = 1.0,
)

/**
 * One Label handover held for reuse by a later frame, under the key Rentile itself published for the
 * purpose ([BasemapEngineHost.labelCandidateRequestKey]).
 *
 * **The sprite manifest travels with it, and leaving it behind would be a visible defect rather than a
 * missed optimisation.** [FrameAcquisition.spriteAtlas] can only be read from inside the invocation that
 * proxied the sprite pair, and on a frame whose style was compiled earlier the Label acquisition is the
 * only thing left that makes the engine ask for it. Reusing the batch without it would therefore hand
 * every cached frame a `null` atlas and silently drop the frame's icons — a cache that changed how the
 * map looks, which is the one thing a cache may not do.
 */
private class RetainedLabelHandover(
    val requestKey: String,
    val candidates: AcquiredLabelCandidates,
    val spriteAtlas: SpriteAtlasManifest?,
)

/** One decoded glyph atlas held under the [ResourceKey] `ResourceKeyDeriver.glyphAtlas` named it with. */
private class RetainedGlyphAtlas(
    val key: ResourceKey,
    val image: DecodedImage,
)

/**
 * One decoded sprite atlas, the [SpriteAtlasManifest] it was decoded out of, and the [ResourceKey]
 * `ResourceKeyDeriver.spriteAtlas` named it with.
 *
 * [manifest] is held for reference identity alone -- it is the memo's key, never read for content --
 * which is what makes a cached label handover cost neither a decode nor a digest of the atlas.
 */
private class RetainedSpriteAtlas(
    val manifest: SpriteAtlasManifest,
    val key: ResourceKey,
    val image: DecodedImage,
)

/** The concrete [RenderTarget] [RenGRenderer.mintRenderTarget] produces. */
internal class RenGRenderTarget(
    internal val owner: RenGRenderer,
    override val framebufferName: FramebufferName,
    internal val mintedAtGeneration: Long,
) : RenderTarget

/** Every GL object [createInternalGlState] allocates, bundled so the factory and re-adoption share one shape. */
internal class InternalGlState(
    val offscreenSurface: OffscreenSurface,
    val compositePipeline: CompositePipeline,
    val stickerPipeline: StickerPipeline,
    val groundPipeline: GroundPipeline,
    val labelPipeline: LabelPipeline,
    val iconPipeline: IconPipeline,
)

internal sealed interface InternalGlStateResult {
    data class Created(val state: InternalGlState) : InternalGlStateResult

    data class Failed(val failure: FailureDescriptor) : InternalGlStateResult
}

/**
 * Allocates the offscreen surface plus RenG's own composite and sticker pipelines — every piece of
 * GL state the renderer holds independent of any one frame's content.
 *
 * Follows [createOffscreenSurface]'s own leak-discipline shape: every allocation is attempted
 * unconditionally, and every result is checked at one point at the end, rather than checking after
 * each step and returning early. If any allocation failed, every allocation that DID succeed is
 * deleted before the first failure is reported, so a partial construction never leaks a GL object.
 */
internal fun createInternalGlState(
    binding: GlBinding,
    profile: RenderContextProfile,
    programs: GlProgramCache,
    // The whole configuration rather than the two fields it reads, deliberately: the ground's
    // displacing program is compiled shaded or unshaded from `terrainShading` here and nowhere else,
    // and a second setup parameter is a second thing a caller can forget to pass on.
    configuration: RendererConfiguration,
): InternalGlStateResult {
    val deriver = ResourceKeyDeriver()
    val surfaceDescriptor = offscreenSurfaceDescriptorFor(configuration.outputPixelSize)
    val surfaceKey = deriver.offscreenSurface(surfaceDescriptor).key

    val surfaceResult = createOffscreenSurface(binding, profile, surfaceKey, surfaceDescriptor)
    val compositeResult = createCompositePipeline(binding, profile.dialect, programs, deriver)
    val stickerResult = createStickerPipeline(binding, profile.dialect, programs, deriver)
    val groundResult = createGroundPipeline(
        binding,
        profile.dialect,
        programs,
        deriver,
        terrainShading = configuration.terrainShading,
    )
    val labelResult = createLabelPipeline(binding, profile.dialect, programs, deriver)
    val iconResult = createIconPipeline(binding, profile.dialect, programs, deriver)

    val surface = (surfaceResult as? OffscreenSurfaceResult.Created)?.surface
    val composite = (compositeResult as? CompositePipelineResult.Created)?.pipeline
    val sticker = (stickerResult as? StickerPipelineResult.Created)?.pipeline
    val ground = (groundResult as? GroundPipelineResult.Created)?.pipeline
    val label = (labelResult as? LabelPipelineResult.Created)?.pipeline
    val icon = (iconResult as? IconPipelineResult.Created)?.pipeline

    if (surface != null && composite != null && sticker != null && ground != null &&
        label != null && icon != null
    ) {
        return InternalGlStateResult.Created(
            InternalGlState(surface, composite, sticker, ground, label, icon),
        )
    }

    surface?.let { deleteOffscreenSurface(binding, it) }
    composite?.let { deleteCompositePipeline(binding, programs, it) }
    sticker?.let { deleteStickerPipeline(binding, programs, it) }
    ground?.let { deleteGroundPipeline(binding, programs, it) }
    label?.let { deleteLabelPipeline(binding, programs, it) }
    icon?.let { deleteIconPipeline(binding, programs, it) }

    val failure = (surfaceResult as? OffscreenSurfaceResult.Failed)?.failure
        ?: (compositeResult as? CompositePipelineResult.Failed)?.failure
        ?: (stickerResult as? StickerPipelineResult.Failed)?.failure
        ?: (groundResult as? GroundPipelineResult.Failed)?.failure
        ?: (labelResult as? LabelPipelineResult.Failed)?.failure
        ?: (iconResult as? IconPipelineResult.Failed)?.failure
        ?: error("createInternalGlState: no result failed despite an incomplete allocation set")
    return InternalGlStateResult.Failed(failure)
}

/**
 * The one concrete [Renderer]. Wires Cycle C's resource driver (frame planning plus static resource
 * acquisition), Cycle D's GL foundation (lifecycle state machine, offscreen surface, composite draw),
 * and this cycle's scene draw ([SceneContent]) into the renderer the public factory hands out.
 *
 * **Scope carried by Task 9a.** `createRenderer`, this class, lifecycle delegation to
 * [GlLifecycleDriver], `drawBasemap` warn-and-degrade, and wiring [SceneContent] into [drawFrame].
 *
 * **Scope carried by Task 9b, closing every gap 9a left**: texture caching and deletion through
 * [glObjectRegistry] (a repeated draw of an unchanged sticker or consumer texture now reuses its GL
 * texture — see [cachedTexture] — and [close] deletes every cached one); [RenGPreparedFrame]
 * snapshotting each [Geometry]'s `uniforms`/`textures` maps in [prepare] rather than retaining the
 * caller's own (see [PreparedGeometry]); sticker quad sizing from each [DecodedImage]'s own
 * dimensions (see [SceneSticker]'s `imageWidthPixels`/`imageHeightPixels`); populating
 * [SceneGeometry.consumerTextures] by fetching and decoding a [Geometry]'s consumer textures in
 * [prepare] alongside its stickers (see [acquireExternalImages] and [geometryTextureReference]); and
 * converting the untyped `error(...)` a draw-time resolution failure used to throw into a typed
 * [RenGException] (see [resolveFrameCamera] and `internal.gl.requireResolvedAtDrawTime`).
 *
 * **Why `preparationActive` on [GlLifecycleDriver]'s own snapshot cannot serialize [prepare] calls
 * across coroutines.** [GlLifecycleDriver.run] is a single synchronous call: `BeginPreparation`
 * flips `preparationActive` true, invokes this class's executor callback synchronously, and flips it
 * back false before `run` returns — all within that one call. A second, later
 * `driver.run(BeginPreparation, ...)` from a concurrent coroutine sees `preparationActive` already
 * false again, so it observes no contention no matter how long the first [prepare] call's actual
 * suspend work (resource fetch, decode) takes. [preparationMutex] is this class's own, genuinely
 * cross-suspend guard for that; `driver.run(BeginPreparation, ...)` is still called on every
 * [prepare] because it is the thing that reports `RENDERER_CLOSED` correctly.
 *
 * **Why [cancelPreparations] calls [preparationDriver] directly rather than relying solely on
 * [GlLifecycleDriver]'s own `CancelPreparations` operation.** For the same reason: that operation
 * only issues a preparation-cancellation action when `preparationActive` is observed true, which —
 * per the note above — it never is by the time a separate `cancelPreparations()` call reaches it.
 * `driver.run(CancelPreparations, ...)` is still called for state-machine consistency, but the actual
 * cancellation is [preparationDriver]'s own `cancel()`, called unconditionally alongside it. This is
 * also what first makes the [ResourceOperationOutcome.Cancelled] path — and the `CancelRoute` handling
 * it depends on — reachable through the public API: see [acquireStickerImages]'s KDoc.
 */
internal class RenGRenderer(
    private val configuration: RendererConfiguration,
    private val binding: GlBinding,
    private val driver: GlLifecycleDriver,
    private val preparationDriver: PreparationDriver,
    private val residentCache: ResidentCache,
    private val basemapEngineHost: BasemapEngineHost,
    private val programs: GlProgramCache,
    private val glObjectRegistry: GlObjectRegistry,
    private val metricRecorder: EngineMetricRecorder,
    initialGlState: InternalGlState,
) : Renderer {

    private val preparationMutex: Mutex = Mutex()
    private val geometryKeyDeriver: ResourceKeyDeriver = ResourceKeyDeriver()

    /**
     * Cycle C's task 20, reached at last: the frame's terrain descriptor and its DEM ring, acquired
     * across the firewall.
     *
     * Constructed here rather than injected because it holds nothing -- it is a translation layer
     * over [basemapEngineHost], which is the thing with state -- and because a renderer that never
     * meets a `terrain` block never calls it.
     */
    private val terrainAcquisition: TerrainAcquisition = TerrainAcquisition(basemapEngineHost)
    private val geometryPipelines: MutableMap<ResourceKey, GeometryPipeline> = mutableMapOf()

    /**
     * Every model program, compiled together on the first frame that carries a [Model] and never
     * before, keyed by the variant that selects it.
     *
     * **All eight at once, rather than only the variants this frame needs.**
     * `internal.gl.modelShaderVariantFor` is the one authority on which of the eight draws a primitive,
     * and its three answers depend on the primitive's *uploaded* attribute set, its joint palette and
     * its alpha mode. Asking that question here would mean a second copy of that rule outside the file
     * that owns it — the same duplication `Scene.mapOrder` exists to have removed — so this pays for
     * eight programs instead. **Not at setup**, though: a renderer that never draws a model must not
     * pay sixteen shader compilations for one, and `createModelPipeline` refuses a context whose
     * `GL_MAX_UNIFORM_BLOCK_SIZE` is below the guaranteed 16384, which would otherwise turn a context
     * that can draw everything else into a renderer that cannot be constructed at all.
     */
    private val modelPipelines: MutableMap<ModelShaderVariant, ModelPipeline> = mutableMapOf()

    /**
     * Every glTF primitive whose vertex and index buffers are already on the GPU, keyed by
     * `ResourceKeyDeriver.modelGeometry` — the identity that namespaces `(meshIndex, primitiveIndex)`
     * by the model's own GLB key (ADR 0018), so two frames over one GLB upload each primitive once and
     * two GLBs that happen to declare an identical mesh at the same index still do not collide.
     *
     * The map is here rather than in [glObjectRegistry] because an [UploadedPrimitive] is more than its
     * handles: it also carries the index count, the index type and which attributes the primitive
     * actually has, none of which a `GlObjectHandle` can express. Its handles are registered in the
     * registry as well, so [close] deletes them and `notifyGpuObjectsGone()` forgets them exactly as it
     * does a texture's.
     */
    private val uploadedPrimitives: MutableMap<ResourceKey, UploadedPrimitive> = mutableMapOf()

    /**
     * Reproduces Rentile's actual `sha256Hex(withRedactedAuthenticationQuery(url))` key for the seven
     * classes Rentile itself fetches and keys, and RenG's own canonical identity for the four it does
     * not (basemap task 16). This is a pure, stateless function of `(locator, resourceClass)`, so one
     * shared instance is correct for every call this renderer makes across every frame preparation.
     */
    private val rentilePrivateKeyResolver = ProductionRentilePrivateKeyResolver(AcceleratedSha256)

    private var offscreenSurface: OffscreenSurface? = initialGlState.offscreenSurface
    private var compositePipeline: CompositePipeline? = initialGlState.compositePipeline
    private var stickerPipeline: StickerPipeline? = initialGlState.stickerPipeline
    private var groundPipeline: GroundPipeline? = initialGlState.groundPipeline

    /**
     * ADR 0034's phase 5, allocated at setup with the composite, sticker and ground pipelines rather
     * than lazily with the model ones. There is exactly one label program with no variants, it needs
     * nothing of the context the other three do not, and `FramePlan.drawLabels` defaults to `true` —
     * so none of the three reasons `modelPipelines` is lazy (sixteen compilations, a
     * `GL_MAX_UNIFORM_BLOCK_SIZE` refusal, and a renderer that may never draw one) applies here.
     */
    private var labelPipeline: LabelPipeline? = initialGlState.labelPipeline

    /**
     * Phase 5's first half, allocated beside the label pipeline and on the same terms: one program,
     * no variants, and a `FramePlan.drawLabels` that defaults to `true`. A style declaring no sprite
     * pair simply never hands it a batch.
     */
    private var iconPipeline: IconPipeline? = initialGlState.iconPipeline

    /**
     * ADR 0038's globe ground, allocated lazily with the model pipelines rather than eagerly with the
     * five above.
     *
     * `FramePlan.projectionMode` defaults to `MERCATOR`, so a renderer that never draws a globe must
     * never compile the globe ground program — the same reason `modelPipelines` is lazy, and the
     * opposite of `labelPipeline`'s (`drawLabels` defaults to `true`). It is created on the first
     * globe frame that actually carries ground tiles, forgotten on a lost context exactly as the
     * model pipelines are, and deleted by `close()`.
     */
    private var globeGroundPipeline: GlobeGroundPipeline? = null

    private var identityRegistry: CanonicalIdentityRegistry = CanonicalIdentityRegistry()
    private var framePlanningCore: FramePlanningCore = newFramePlanningCore(identityRegistry)
    private var previousEncodedPlan: EncodedFramePlan? = null
    private var previousSelectedLod: Int? = null

    /**
     * ADR 0035's label fade: the fourth member of Frame History, on exactly [previousSelectedLod]'s
     * terms — read by preparation, fed to a pure function as inert data, committed only after every
     * fallible step of a `prepare` has succeeded, and cleared by the public `clearFrameHistory()`.
     * `prepareBatch` needs no rule of its own because it is `plans.map { prepare(it, accessMode) }`,
     * so a fade commits at the same boundary a LOD does.
     *
     * **What it is not is a cache.** Every other cross-frame mechanism in this renderer — decoded
     * images, uploaded textures, parsed GLBs, compiled shaders, resident tiles — changes how fast a
     * frame is produced and never how it looks. This one changes pixels: two frames carrying the same
     * plan can paint a label at different opacities, which is the whole point of it, and
     * `clearFrameHistory()` is the public call that takes a consumer back to a render that is a pure
     * function of the plan.
     */
    private var previousLabelFade: LabelFadeState = LabelFadeState.EMPTY

    /**
     * The Label handover this renderer is holding, and the exact opposite kind of thing to
     * [previousLabelFade] above: **a cache**, changing how fast a frame is produced and never how it
     * looks. It is therefore not a fifth member of Frame History and is deliberately not cleared by
     * `clearFrameHistory()` — see [labelHandover] for the whole decision.
     *
     * One entry, on [BasemapEngineHost.compiledStyle]'s own terms rather than as a map: a camera that
     * moves changes its tile set every frame and would evict any bounded cache continuously, while a
     * camera that sits still — the case measured at 514–520 ms of `prepare()` per frame, ~85% of it,
     * for a batch that was byte-identical all three times — needs exactly one. A second entry would buy
     * only an oscillation between two tile sets, which no consumer produces and no measurement asked
     * for.
     */
    private var retainedLabelHandover: RetainedLabelHandover? = null

    /**
     * The glyph atlas of [retainedLabelHandover]'s batch, decoded once rather than on every frame that
     * keeps a label.
     *
     * **Keyed by the atlas's own content, not by the handover's request key**, and the two are genuinely
     * different questions. `ResourceKeyDeriver.glyphAtlas` names the atlas by the digest Rentile packed
     * it under, so two tile sets whose label text happens to need the identical glyphs share this entry
     * even though their handover keys differ — a pan along a repetitive coastline is exactly that shape.
     * Keying it off the handover instead would throw the decode away on every invalidation and re-do
     * 122 ms of work (measured, 16 ranges, 6.88 MB) to arrive at the same pixels.
     *
     * Bounded by `ResourceLimits.maximumDecodedImageBytes` — the same ceiling `decodeGlyphAtlas` already
     * enforces — because that is what a `DecodedImage` of an atlas can weigh at all. One is held, for
     * the same reason one handover is.
     */
    private var retainedGlyphAtlas: RetainedGlyphAtlas? = null

    /**
     * The sprite atlas of [retainedLabelHandover]'s manifest, decoded once rather than on every frame
     * that draws an icon.
     *
     * **Memoised against the manifest *instance*, which is a stronger statement than it looks.** The
     * manifest is only ever built by `spritePairJointManifest`, once per proxied sprite pair, and is
     * then carried by [RetainedLabelHandover] for as long as that handover answers; so an identical
     * reference means literally the same bytes, decoded from the same fetch. A fresh manifest over
     * byte-identical bytes misses this and pays one decode, but still derives the same
     * `ResourceKeyDeriver.spriteAtlas` key and therefore re-uses the texture already on the GPU.
     *
     * The alternative -- keying it by the derived key, as [retainedGlyphAtlas] is -- would have to
     * derive that key, and deriving it hashes the whole encoded atlas. Doing that once per frame to
     * avoid a decode once per sprite pair is the wrong way round.
     */
    private var retainedSpriteAtlas: RetainedSpriteAtlas? = null

    /** Once per renderer, never per frame — see the design spec's `drawBasemap` decision. */
    private var basemapWarningEmitted: Boolean = false

    /**
     * Raw tile pixels held by every open Prepared Frame, in bytes (ADR 0044).
     *
     * Raised when a preparation takes `renderRaw`, lowered by exactly the closing frame's own
     * [RenGPreparedFrame.rawTileBytes]. A plain `var` because it is touched only where every other
     * piece of this class's mutable state is: inside a preparation, which `preparationMutex` admits
     * one of at a time, and at [closePreparedFrame], which runs on the owning thread (ADR 0015).
     */
    private var outstandingRawTileBytes: Long = 0L

    /**
     * Every tile this `prepareBatch` has already rasterised, or `null` outside one (ADR 0052).
     *
     * ADR 0046's residency filter cannot see inside a batch, because nothing in a batch is drawn and
     * so no texture becomes resident until the consumer draws: without this, every frame
     * re-rasterises every tile the frame before it just rendered. Measured at three frames over one
     * camera: twelve renders for four distinct tiles.
     *
     * Batch-scoped and nothing wider. A renderer-lifetime memo would be an unbounded store of raw
     * pixels beside the two budgets that already govern them, and outside a batch there is a draw
     * between frames, which is what makes the residency filter sufficient there.
     */
    private var batchRenderedTiles: MutableMap<ResourceKey, RenderedBasemapTile>? = null

    /**
     * The thread this renderer was created on, or last adopted a context on (ADR 0056).
     *
     * A `var` because `adoptCurrentRenderContext` moves it: adoption is where "a new exact identity
     * and context generation" is established (ADR 0015), and the calling thread is part of that
     * identity.
     */
    private var contextThread: CallingThread = currentCallingThread()

    /**
     * Refuses a GL-touching call that arrived on a thread nothing declared (ADR 0056).
     *
     * RenG cannot observe which context is current — `RendererFactory` explains at length why not —
     * but it can observe the thread, and a context current on one thread is not current on another.
     * This is a necessary condition, never a sufficient one: passing it proves nothing about the
     * context, and failing it proves the context cannot be right.
     *
     * Raised before any state change, so the call can be retried from the correct thread, or after
     * `adoptCurrentRenderContext()` on the new one.
     */
    private fun requireContextThread(stage: PipelineStage) {
        if (contextThread.isCurrentThread()) return
        throw renGFailure(RenGErrorCode.RENDER_CONTEXT_THREAD_CHANGED, stage)
    }

    /**
     * The engine gate lane the most recent tile render was forwarded under (ADR 0053).
     *
     * A narrow window onto the host's own observation rather than exposing the host itself: the
     * engine reports a priority nowhere, so this is the only point at which the thread-through from
     * `prepare`'s parameter to the engine call can be checked at all.
     */
    internal val lastForwardedRenderPriority: RenGRenderPriority?
        get() = basemapEngineHost.lastForwardedRenderPriority

    /**
     * The compiled basemap style of the **most recently prepared frame**, or `null` when that frame drew
     * neither the basemap nor its labels (or when no preparation has succeeded yet). Since E-labels task
     * 8b a `drawBasemap = false, drawLabels = true` frame holds its style here too: labels come from the
     * style, so that pairing acquires and compiles one. Cleared rather than left standing on a frame that
     * asked for neither, so that a reader never has to cross-check the plan to know whether
     * this belongs to the frame in front of it — "the last style compiled" is a subtly different claim
     * and would be a trap for Cycle E-C3, which consumes this. Nothing draws with it: the ground and
     * the Label handover both take the style from the acquisition that produced it rather than from
     * here, for exactly the reason the paragraph above gives.
     *
     * Read back from [basemapEngineHost] rather than taken from the driver's compile action, because a
     * `RESIDENT`-provenance frame emits no compile action at all.
     */
    internal var preparedBasemapStyle: PreparedStyle? = null
        private set

    private fun newFramePlanningCore(registry: CanonicalIdentityRegistry): FramePlanningCore = FramePlanningCore(
        frameEncoder = FramePlanCanonicalEncoder(),
        frameIdentityRegistry = registry,
        resourceKeyDeriver = ResourceKeyDeriver(),
        rentilePrivateKeyResolver = rentilePrivateKeyResolver,
    )

    // ---- Preparation --------------------------------------------------------------------------

    override suspend fun prepare(
        plan: FramePlan,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
    ): PreparedFrame {
        if (!preparationMutex.tryLock()) {
            throw renGFailure(RenGErrorCode.PREPARATION_IN_PROGRESS, PipelineStage.FRAME_PREPARATION)
        }
        // Every lease this preparation takes is recorded here and handed to the frame it produces, which
        // releases them on close(). A preparation that never reaches a frame -- a driver failure, a
        // cancellation, a decode fault, a planning check -- holds leases no frame will ever close, so the
        // `finally` below releases exactly those rather than leaving them outstanding for the renderer's
        // whole life. Anchored here, at the one place that knows whether a frame took ownership, rather
        // than at each of the throw sites between the driver run and the frame.
        val leases = mutableListOf<Lease>()
        var prepared: RenGPreparedFrame? = null
        try {
            val beginOutcome = driver.run(RendererLifecycleOperation.BeginPreparation) { null }
            if (beginOutcome is RendererLifecycleOutcome.Failed) throw beginOutcome.failure.toException()

            val planningOutcome = framePlanningCore.plan(
                FramePlanningRequest(
                    plan = plan,
                    outputPixelSize = configuration.outputPixelSize,
                    basemapStyle = configuration.basemapStyle,
                    resourceLimits = configuration.resourceLimits,
                    maximumBasemapTileInstances = configuration.maximumBasemapTileInstances,
                    previousPlan = previousEncodedPlan,
                    previousSelectedLod = previousSelectedLod,
                ),
            )
            val planned = when (planningOutcome) {
                is FramePlanningOutcome.Success -> planningOutcome.planned
                is FramePlanningOutcome.Failure -> throw planningOutcome.failure.toException()
            }

            // Read once. `FramePlan.models` hands back a fresh copy of its own snapshot on every
            // access, so re-reading it below would be three copies of one immutable list rather than
            // three chances to observe a different one -- but naming it once is also what keeps the
            // traversal check below comparing against the same list the models are built from.
            val planModels = plan.models

            // FramePlanningCore.staticResourceTraversal() walks plan.stickersForCore() in order,
            // emitting exactly one External reference per sticker, so zipping the two lists back
            // together by position is safe.
            val stickerImageReferences = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .filter { it.resourceClass == ResourceClass.STICKER_IMAGE }
            check(stickerImageReferences.size == plan.stickers.size) {
                "sticker image traversal must have exactly one entry per sticker"
            }

            // FramePlanningCore.staticResourceTraversal() emits exactly one MODEL_GLB entry per
            // plan model, in plan order, and nothing else in the traversal carries that class -- so
            // unlike a MODEL_TEXTURE (which a Geometry's own consumer textures reuse) these can be
            // read straight back out of the traversal by position, exactly as the sticker images
            // above are.
            val modelGlbReferences = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .filter { it.resourceClass == ResourceClass.MODEL_GLB }
            check(modelGlbReferences.size == planModels.size) {
                "model GLB traversal must have exactly one entry per model"
            }
            // A Model.texture, on the other hand, traverses under the same reused MODEL_TEXTURE class
            // a Geometry's consumer textures do, so it is derived independently here for exactly the
            // reason `geometryTextureReference` documents rather than parsed back out by position.
            val modelTextureReferences: List<StaticResourceReference.External?> =
                planModels.map { model -> model.texture?.let { externalImageReference(it) } }

            // Task 9b item 4: a Geometry's consumer textures are fetched and decoded here too, one
            // geometry at a time, sorted by sampler name -- the exact same order
            // FramePlanningCore.staticResourceTraversal() now traverses them in, computed
            // independently through geometryKeyDeriver rather than re-parsed out of the traversal
            // list. Both derivations are the same pure function of (resourceClass, locator), so they
            // always agree -- the same "recompute, don't consume the traversal" pattern this
            // function already uses for a geometry's PROGRAM key (see the `draw` section below).
            val geometryTextureReferencesByGeometry: List<List<Pair<String, StaticResourceReference.External>>> =
                plan.geometries.map { geometry ->
                    geometry.textures.entries.sortedBy { it.key }.map { (name, locator) ->
                        name to externalImageReference(locator)
                    }
                }

            // The style is the one traversal entry that is neither a sticker nor a texture: it is
            // acquired, validated, compiled through the engine, written and installed by the resource
            // driver's own basemap-style commit verbs, and never decoded as an image.
            val styleReference = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .singleOrNull { it.resourceClass == ResourceClass.BASEMAP_STYLE }

            val imageReferences = stickerImageReferences +
                modelTextureReferences.filterNotNull() +
                geometryTextureReferencesByGeometry.flatten().map { it.second }
            // Post-world-copy-dedup by construction: `canonicalResources` is what BasemapTileSelector
            // emits separately from `instances` precisely so N visible copies of one tile are one
            // acquisition, one engine render and one identity. Since E-labels task 8b the selection is
            // non-null whenever the plan draws a basemap *or* its labels and a style is configured
            // (planMercatorSpatial), which is also exactly when `styleReference` is non-null.
            //
            // **This is the ground draw's own gate, and the only one.** These are the tiles the engine
            // rasterizes into the PNGs the ground is textured from, so a frame that draws no basemap
            // hands over none: `renderBasemapTiles` is skipped, `basemapStyleDigest` stays null, and
            // `groundInstances` therefore returns empty below. `drawLabels` deliberately does not widen
            // it -- the label handover takes the canonical tiles from the spatial plan and fetches its
            // own vector tiles through the engine, and nothing about a label needs the ground's raster.
            val groundCanonicalTiles = if (plan.drawBasemap) {
                planned.spatialPlan.tileSelection?.canonicalResources.orEmpty()
            } else {
                emptyList()
            }
            // **The label draw's own gate, and the same selection the ground reads.** Deriving a second
            // tile list here would be a defect rather than a duplication: `labelCandidateRequestKey`
            // deliberately does not canonicalise `x`, so a tile set that differs from this one by a
            // world copy is a different set of routes, a different cache key and a different set of
            // candidates. Task 8b is what makes one selection reachable from both switches -- since
            // that split the selection exists on a `drawBasemap = false, drawLabels = true` frame,
            // which is exactly the pairing that used to plan no tiles at all.
            val labelCanonicalTiles = if (plan.drawLabels) {
                planned.spatialPlan.tileSelection?.canonicalResources.orEmpty()
            } else {
                emptyList()
            }
            val acquired = acquireFrameResources(
                styleReference = styleReference,
                imageReferences = imageReferences,
                modelGlbReferences = modelGlbReferences,
                canonicalTiles = groundCanonicalTiles,
                labelTiles = labelCanonicalTiles,
                accessMode = accessMode,
                priority = priority,
                leaseSink = leases,
            )
            val decodedByKey = acquired.decodedImagesByKey

            // Hoisted above the label block rather than assembled with the frame below, because both
            // are what a label anchor needs to know where the ground is: `groundInstances` is the one
            // answer to "did this frame draw a ground at all, and at which LOD", and `preparedTerrain`
            // is the one answer to "which of its tiles displace". Both are pure functions of
            // `acquired` and of the plan, so hoisting them moves work rather than meaning.
            val groundInstances = groundInstances(
                instances = planned.spatialPlan.tileSelection?.instances.orEmpty(),
                renderedTiles = acquired.basemapTiles,
                styleDigest = acquired.basemapStyleDigest,
            )
            val terrain = preparedTerrain(acquired)
            // **One surface per frame, built here and spent in two places at two different times.**
            // A label anchor rides it during this `prepare()`, because ADR 0035 puts collision here
            // and never in a draw; a `GROUND_RELATIVE` sticker, model or geometry rides it at draw
            // time through `sceneTerrain`. Building it twice would be two `rgbaSnapshot` copies of
            // every source DEM in the frame -- and, worse, two objects that could disagree about
            // which tiles displace the day the two construction sites drift apart.
            val groundSurface = groundSurfaceFor(
                plan = plan,
                planModels = planModels,
                hasLabelCandidates = acquired.labelCandidates?.batch?.candidates?.isNotEmpty() == true,
                groundInstances = groundInstances,
                terrain = terrain,
            )

            val stickers = plan.stickers.zip(stickerImageReferences) { sticker, reference ->
                PreparedSticker(
                    placement = sticker.placement,
                    resourceKey = reference.resourceKey,
                    image = requireNotNull(decodedByKey[reference.resourceKey]) {
                        "a successful sticker acquisition must decode every traversed image"
                    },
                )
            }

            // Task 9b item 3: both of Geometry's live Map references are snapshotted right here,
            // synchronously, before this suspend function ever returns -- uniforms via .toMap(),
            // textures by replacing every ResourceLocator with its already-fetched, already-decoded
            // content. Neither snapshot can be affected by a consumer mutating their own map after
            // prepare() returns.
            val geometries = plan.geometries.mapIndexed { index, geometry ->
                val consumerTextures = geometryTextureReferencesByGeometry[index].associate { (name, reference) ->
                    name to PreparedGeometryTexture(
                        resourceKey = reference.resourceKey,
                        image = requireNotNull(decodedByKey[reference.resourceKey]) {
                            "a successful geometry-texture acquisition must decode every traversed image"
                        },
                    )
                }
                PreparedGeometry(
                    geometry = geometry,
                    uniformsSnapshot = geometry.uniforms.toMap(),
                    consumerTextures = consumerTextures,
                )
            }

            // Every model-shaped derivation happens right here, synchronously, before this suspend
            // function returns: decode, selector resolution, sampling, node composition and the joint
            // palettes. See [PreparedModel] for why the animation pose in particular cannot be left
            // to the draw.
            val models = planModels.mapIndexed { index, model ->
                prepareModel(model, modelGlbReferences[index], modelTextureReferences[index], decodedByKey)
            }

            // The label path's second and third steps, in the order ADR 0034 and ADR 0035 fix them.
            // `placeLabels` resolves point placement, collision and priority against the same resolved
            // camera the planner already derived -- not a second resolution of the raw `Camera` -- and
            // returns the survivors lowest-priority-first. `advanceLabelFade` then folds in the
            // previous frame's opacities by `LabelIdentity` and is committed below beside the LOD, on
            // the LOD's exact terms: only once every fallible step above has succeeded.
            //
            // A `null` batch is the honest input on a frame that asked for no labels, and it is not a
            // placeholder for a real one: a frame that places no label is a frame in which every label
            // is absent, so every carried entry decays toward zero and the map empties itself.
            val labelBatch = acquired.labelCandidates?.batch
            val placedLabels = if (labelBatch == null) {
                emptyList()
            } else {
                placeLabels(
                    planned.spatialPlan.camera,
                    labelBatch,
                    acquired.spriteAtlas,
                    labelGroundElevation(
                        plan = plan,
                        resolvedCamera = planned.spatialPlan.camera,
                        surface = groundSurface,
                        terrain = terrain,
                        selectedLod = groundInstances.firstOrNull()?.instance?.lod,
                    ),
                )
            }
            val labelFade = advanceLabelFade(
                previous = previousLabelFade,
                batch = labelBatch,
                placed = placedLabels,
            )
            // The atlas is decoded only for a frame that actually kept a label. An empty batch still
            // carries one -- Skia will not encode a zero-dimension image, so Rentile packs a 1x1
            // placeholder -- and decoding, keying and uploading that would be a texture no draw ever
            // samples.
            val labels = if (labelBatch == null || labelFade.labels.isEmpty()) {
                null
            } else {
                val atlasKey = geometryKeyDeriver.glyphAtlas(labelBatch.atlas.contentKey).key
                // Both halves of every surviving symbol, faded from the one number the fade produced
                // for it. `advanceLabelFade` faded the glyph quads; the icon quad is faded here
                // because it never reached that function -- see `PreparedLabelFrame.icons`.
                val icons = labelFade.labels.mapNotNull { faded ->
                    faded.label.icon?.quad?.fadedBy(faded.opacity)
                }
                // Decoded only for a frame that actually kept an icon. A style can declare a sprite
                // pair that every one of its icons then fails to resolve against, and decoding an
                // atlas nothing samples is the same waste the glyph atlas's own guard avoids.
                val sprites = if (icons.isEmpty()) null else residentSpriteAtlas(acquired.spriteAtlas)
                PreparedLabelFrame(
                    atlasKey = atlasKey,
                    atlas = residentGlyphAtlas(atlasKey, labelBatch.atlas.pngBytes),
                    labels = labelFade.labels,
                    icons = if (sprites == null) emptyList() else icons,
                    spriteAtlasKey = sprites?.key,
                    spriteAtlas = sprites?.image,
                )
            }

            // ADR 0041's two reports, and the only place RenG says out loud that a frame's ground is
            // flatter than its style asked for. Once per frame, never once per tile, and never a
            // failure: the frame prepared and it will draw. Over the frame's own ground tiles rather
            // than over the request, so the perimeter ring -- which costs a replicated border texel
            // and not a flat tile -- does not inflate the count.
            //
            // The terrain is prepared *before* the report rather than inside the frame below, because
            // the count is over the tiles that will actually displace and only the prepared terrain
            // knows which those are. Handing this the acquisition outcome instead is the defect Task
            // 13's harness pass caught: a frame that acquired every DEM and could use none of them
            // reported nothing at all. Task 18 moved the preparation itself further up still -- a
            // label anchor rides the same surface and is placed above -- and left the report here, so
            // a frame's diagnostics keep the order they were emitted in before labels rode anything.
            acquired.terrain?.let { outcome ->
                reportTerrainDegradation(
                    outcome = outcome,
                    groundTiles = groundCanonicalTiles,
                    elevatedTiles = terrain?.elevatedTiles.orEmpty(),
                    sink = configuration.diagnosticSink,
                )
            }

            previousEncodedPlan = planned.encodedPlan
            previousSelectedLod = planned.spatialPlan.lodObservation.selectedLod
            previousLabelFade = labelFade.nextState

            // Bound to a local as well as to `prepared`, so the two statements below read the frame
            // without depending on a smart cast of a `var` the `finally` also reads (ADR 0045).
            val frame = RenGPreparedFrame(
                owner = this,
                frameIndex = plan.frameIndex,
                camera = plan.camera,
                projectionMode = plan.projectionMode,
                drawBasemap = plan.drawBasemap,
                drawLabels = plan.drawLabels,
                stickers = stickers,
                geometries = geometries,
                models = models,
                basemapTiles = acquired.basemapTiles,
                groundInstances = groundInstances,
                terrain = terrain,
                groundSurface = groundSurface,
                labels = labels,
                mapOrder = planned.spatialPlan.mapEntries.map { it.reference },
                screenOrder = planned.spatialPlan.screenEntries.map { it.reference },
                leases = leases,
            )
            prepared = frame
            // Counted once the frame exists, never at the render call: a preparation that threw
            // after rendering its tiles leaves nothing outstanding for a close that never comes
            // (ADR 0044). The same condition governs the lease release in the `finally` below, which
            // is why both hang off `prepared` being non-null rather than off the driver's outcome.
            outstandingRawTileBytes += frame.rawTileBytes
            return frame
        } finally {
            if (prepared == null) releaseLeases(leases)
            preparationMutex.unlock()
        }
    }

    /**
     * Decodes the glyph atlas the engine packed for this frame, to the same canonical RGBA8 every
     * other image in this renderer becomes.
     *
     * **The failure is the one the identical bytes already have elsewhere**, not a new policy: an
     * engine-produced PNG that `decodePng` will not take is `RESOURCE_DECODE_FAILED` at
     * `RESOURCE_DECODING`, exactly as a sticker or geometry texture is a few lines up in
     * [acquireFrameResources], and exactly as a rendered ground tile is at draw time. What is
     * genuinely reachable here is the budget rather than a malformed container: an atlas shares
     * [ResourceLimits.maximumDecodedImageBytes] with every raster RenG decodes, and a style whose
     * font stacks pack past it fails the frame rather than drawing text without its glyphs.
     */
    /**
     * [decodeGlyphAtlas], but paid once per distinct atlas rather than once per frame that keeps a label.
     *
     * **The decode is the atlas's real per-frame cost, and the upload never was.** `uploadGlyphAtlas`
     * has leased a resident texture by key since task 20, so the GPU half was already right; what was
     * not is that `prepare()` decoded 6.88 MB of PNG -- 120-130 ms measured, 24% of the whole label
     * path -- to produce a [DecodedImage] the upload then threw away on every frame after the first.
     * `prepare()` cannot consult [glObjectRegistry] to find out whether it needs one: it is a `suspend`
     * function that may resume off the thread holding the render context, and the registry belongs to
     * the draw. So the decode is memoized here instead, on the CPU side where it happens.
     *
     * [key] is the atlas's content identity, so a hit means the same bytes rather than the same request,
     * and a `RESIDENT` handover that repacked an identical atlas hits it too.
     */
    private fun residentGlyphAtlas(key: ResourceKey, pngBytes: ByteArray): DecodedImage {
        retainedGlyphAtlas?.takeIf { it.key == key }?.let { return it.image }
        val decoded = decodeGlyphAtlas(pngBytes)
        retainedGlyphAtlas = RetainedGlyphAtlas(key, decoded)
        return decoded
    }

    /**
     * [manifest]'s atlas image, decoded and named, or `null` when there is no manifest to decode.
     *
     * **A `null` manifest with icons in hand is unreachable rather than merely unlikely**, because
     * `resolveIcon` returns `null` for every icon when the manifest is absent -- an icon exists only
     * if a manifest resolved it. It is expressed as a nullable rather than a `requireNotNull` so that
     * a future path which decouples the two loses its icons instead of failing a frame over them.
     *
     * The decode is memoised against the manifest instance; see [retainedSpriteAtlas]. The key is
     * derived on the same miss, which is the only place the encoded atlas is hashed.
     */
    private fun residentSpriteAtlas(manifest: SpriteAtlasManifest?): RetainedSpriteAtlas? {
        if (manifest == null) return null
        retainedSpriteAtlas?.takeIf { it.manifest === manifest }?.let { return it }
        val key = geometryKeyDeriver.spriteAtlas(manifest.atlasPngBytes).key
        val decoded = decodeSpriteAtlas(manifest.atlasPngBytes)
        return RetainedSpriteAtlas(manifest, key, decoded).also { retainedSpriteAtlas = it }
    }

    /**
     * The sprite atlas, decoded to the same canonical RGBA8 every other image in this renderer
     * becomes, failing exactly as [decodeGlyphAtlas] does over the identical class of bytes.
     *
     * **What is reachable here is the budget, not a malformed container.** The firewall's own sprite
     * member gate already decoded these exact bytes in full before the pair was allowed to be
     * cached, at a ceiling of its own; `maximumDecodedImageBytes` is a consumer-settable limit that
     * can legitimately be lower. A style whose sprite sheet packs past the consumer's own ceiling
     * fails the frame rather than drawing a map with its icons quietly missing -- the same choice the
     * glyph atlas makes over the same limit, and for the same reason: a silent half-drawn symbol is
     * the failure this whole task exists to close.
     */
    private fun decodeSpriteAtlas(pngBytes: ByteArray): DecodedImage =
        when (val decoded = decodePng(pngBytes, configuration.resourceLimits.maximumDecodedImageBytes)) {
            is PngDecodeResult.Success -> decoded.image
            else -> throw RenGException(
                code = RenGErrorCode.RESOURCE_DECODE_FAILED,
                stage = PipelineStage.RESOURCE_DECODING,
                diagnostics = listOf(
                    failureContextDiagnostic(
                        stage = PipelineStage.RESOURCE_DECODING,
                        fieldName = DiagnosticField.RESOURCE,
                        resourceClass = ResourceClass.BASEMAP_SPRITE_IMAGE,
                    ),
                ),
            )
        }

    private fun decodeGlyphAtlas(pngBytes: ByteArray): DecodedImage =
        when (val decoded = decodePng(pngBytes, configuration.resourceLimits.maximumDecodedImageBytes)) {
            is PngDecodeResult.Success -> decoded.image
            else -> throw RenGException(
                code = RenGErrorCode.RESOURCE_DECODE_FAILED,
                stage = PipelineStage.RESOURCE_DECODING,
                diagnostics = listOf(
                    failureContextDiagnostic(
                        stage = PipelineStage.RESOURCE_DECODING,
                        fieldName = DiagnosticField.RESOURCE,
                        resourceClass = ResourceClass.BASEMAP_GLYPH_RANGE,
                    ),
                ),
            )
        }

    /**
     * This frame's terrain in the form the draw needs, or `null` when it has none to draw with.
     *
     * **`Degraded` collapses to `null` here rather than being carried**, because by this point ADR
     * 0041's report has already been emitted and the two degradations have exactly one remaining
     * consequence: the whole ground draws flat, which is what a `null` terrain means. Carrying the
     * reason onward would put a second authority on that decision inside the draw.
     *
     * An `Acquired` outcome with **no** DEM tiles is deliberately *not* collapsed. Its per-frame
     * uniforms — the decode, the interior size, the exaggeration — are properties of the source
     * rather than of the coverage, so the frame keeps them, every tile draws flat because each one
     * individually has no DEM, and the coverage diagnostic has already named the count. Collapsing
     * would make the frame's granularity fall back to the flat rule as a side effect of a coverage
     * gap, which is a different picture from the one the style asked for.
     */
    /**
     * The CPU-readable copy of the surface this frame's ground will draw, or `null` when nothing in
     * the frame asked where the ground is.
     *
     * **This is the whole of what the design's "sparse CPU decode" becomes.** §6 argued that a CPU
     * elevation lookup must decode only the tiles containing ground-relative content, because decoding
     * the visible set would be about 224 MiB against a `maximumDecodedImageBytes` shared with every
     * raster. Rentile `0.7.0` deleted that premise -- `ValidatedDemTile.texels` arrives decoded and
     * `PreparedTerrain` already holds it -- so the only cost left to be sparse about is
     * [GroundSurface]'s one `rgbaSnapshot` per source image, and the sparsity that matters is *per
     * frame* rather than per tile: a frame that asks nothing pays nothing, and a frame that asks pays
     * for the ground it is riding, which is the ground it already drew.
     *
     * **[hasLabelCandidates] is Task 18's widening of that condition, and it is the batch's own
     * candidate list rather than `drawLabels`.** Every label anchor rides this surface, so a frame
     * with labels asks the same question a `GROUND_RELATIVE` sticker does -- but a frame whose style
     * declares no symbol layer, or whose tiles carry no labelled feature, has no anchor to place and
     * must keep paying nothing.
     *
     * **What it does cost is one `rgbaSnapshot` per source DEM on every terrain frame that draws
     * text**, where before this task only a `GROUND_RELATIVE` placement paid it. At a 512-texel DEM
     * that is a mebibyte a tile over the visible set and its perimeter ring. The copy is
     * [GroundSurface]'s own decision -- taking it once is what stops a per-anchor lookup from being a
     * per-anchor mebibyte -- and removing it means giving `DecodedImage` an indexed read, which is a
     * wider change than this task and is recorded rather than made.
     *
     * **[selectedLod] comes from the ground instances rather than from the plan**, for `sceneTerrain`'s
     * reason: both tile selectors emit one LOD per frame, and a frame with no ground instances drew no
     * ground at all -- which is ADR 0040's "absent terrain resolves `GROUND_RELATIVE` as `ABSOLUTE`",
     * and is why a `drawBasemap = false` frame's labels sit at sea level however steep the world is.
     * That case cannot even arise from an acquisition: terrain is acquired only for a frame whose
     * canonical tile list is non-empty, which is exactly a frame that draws its basemap.
     */
    private fun groundSurfaceFor(
        plan: FramePlan,
        planModels: List<Model>,
        hasLabelCandidates: Boolean,
        groundInstances: List<PreparedGroundInstance>,
        terrain: PreparedTerrain?,
    ): GroundSurface? {
        if (terrain == null) return null
        val selectedLod = groundInstances.firstOrNull()?.instance?.lod ?: return null
        val ridesTheGround = hasLabelCandidates ||
            plan.geometries.any { it.altitudeMode == AltitudeMode.GROUND_RELATIVE } ||
            plan.stickers.any { it.placement.altitudeMode == AltitudeMode.GROUND_RELATIVE } ||
            planModels.any { it.placement.altitudeMode == AltitudeMode.GROUND_RELATIVE }
        if (!ridesTheGround) return null
        return terrain.groundSurface(selectedLod)
    }

    /**
     * [GroundSurface] bound to the one granularity this frame's ground is drawn at, in the shape the
     * label placement pass consumes — or `null` when this frame drew no displaced ground under
     * anything.
     *
     * **The granularity is `SceneContent`'s own, reached through the same
     * [frameGroundCellsPerTileSide] and given the same three inputs.** A lookup at any other number
     * finds a different ground cell and therefore a different height, and the picture of that is a
     * label sitting slightly off the hill it names — which reads as an imprecise lookup rather than
     * as a disagreement about the grid.
     *
     * **The `null` a lookup can still answer becomes zero here**, which is ADR 0040 in one line: where
     * the frame drew no displaced ground under an anchor, a ground-relative height is nothing at all
     * and the label stays exactly where it has always been.
     *
     * **No lift.** `GROUND_DRAPE_LIFT_METRES` exists for content drawn coplanar with the ground and
     * fighting it for depth; Task 14's spike measured that labels have no such fight — `drawLabels`
     * and `drawIcons` both disable the depth test outright — so a lift here would move a name off its
     * own feature to fix a defect it does not have.
     */
    private fun labelGroundElevation(
        plan: FramePlan,
        resolvedCamera: ResolvedFrameCamera,
        surface: GroundSurface?,
        terrain: PreparedTerrain?,
        selectedLod: Int?,
    ): LabelGroundElevation? {
        if (surface == null || terrain == null || surface.isEmpty) return null
        val lod = selectedLod ?: return null
        val cellsPerTileSide = frameGroundCellsPerTileSide(
            camera = resolvedCamera,
            selectedLod = lod,
            terrainCells = terrainCellsPerTileSide(
                terrain = TerrainGranularityInputs(terrain.tileSizePx),
                projectionMode = plan.projectionMode,
                zoom = plan.camera.zoom,
                latitude = plan.camera.latitude,
                selectedLod = lod,
            ),
        )
        return LabelGroundElevation { latitude, unwrappedLongitude ->
            surface.elevationMetresBeneath(latitude, unwrappedLongitude, cellsPerTileSide) ?: 0.0
        }
    }

    private fun preparedTerrain(acquired: FrameAcquisition): PreparedTerrain? {
        val outcome = acquired.terrain as? TerrainAcquisitionOutcome.Acquired ?: return null
        val demTiles = LinkedHashMap<CanonicalBasemapTile, AcquiredDemTile>()
        outcome.requestedTiles.forEach { tile ->
            outcome.demTileFor(tile)?.let { demTiles[tile] = it }
        }
        return PreparedTerrain(
            encoding = outcome.source.encoding,
            tileSizePx = outcome.source.tileSizePx,
            exaggeration = acquired.terrainExaggeration,
            demTiles = demTiles,
        )
    }

    /**
     * Releases leases no [RenGPreparedFrame] will ever own, tolerating a cache that has already dropped
     * the generation they name -- [ResidentCache.closeAll] clears its own bookkeeping without consulting
     * any outstanding lease, so a release arriving after the renderer closed is a real, harmless ordering
     * rather than a fault to propagate out of a `finally`.
     */
    private fun releaseLeases(leases: List<Lease>) {
        leases.forEach { lease -> runCatching { residentCache.releaseLease(lease) } }
    }

    /**
     * Pairs every unwrapped draw [instances] entry with the rendered tile it draws, by deriving RenG's
     * own [BasemapEngineHost.renderedTileKey] for it (ADR 0018) rather than by matching its
     * `(lod, tileY, canonicalX)` triple against [renderedTiles] structurally. The two agree by
     * construction, and that is the point: deriving the key here means the draw path resolves a tile
     * through the same identity the acquisition path named it with, so a divergence between them fails
     * this `check` loudly instead of drawing the wrong ground.
     *
     * The derivation is memoized per **canonical** tile, not performed per instance: the instance
     * overload projects the world copy away, so every copy of one tile derives the identical key, and a
     * frame at the 512-instance budget would otherwise pay 512 SHA-256 hashes for at most a few dozen
     * distinct answers.
     *
     * Returns empty whenever there is no basemap to place: no style configured, or no tile selected.
     *
     * **It used to return empty whenever no tile was *rendered*, and that is no longer the same
     * condition.** `renderBasemapTiles` now asks the engine only for tiles whose texture is not already
     * resident, so a frame that pans back over ground it has drawn before legitimately renders nothing
     * and still has ground to place. Keying this on the rendered list dropped the ground entirely on
     * exactly those frames — caught by `MacosGlConformanceTest`'s coplanar-geometry case, which found
     * a quad with no ground beneath it and called itself vacuous rather than passing.
     */
    private fun groundInstances(
        instances: List<BasemapTileInstance>,
        renderedTiles: List<RenderedBasemapTile>,
        styleDigest: String?,
    ): List<PreparedGroundInstance> {
        if (instances.isEmpty() || styleDigest == null) return emptyList()
        val renderedKeys = renderedTiles.mapTo(HashSet()) { it.key }
        val keyByCanonicalTile = HashMap<CanonicalBasemapTile, ResourceKey>(renderedTiles.size)
        return instances.map { instance ->
            val canonical = CanonicalBasemapTile(
                lod = instance.lod,
                tileY = instance.tileY,
                canonicalX = instance.canonicalX,
            )
            val key = keyByCanonicalTile.getOrPut(canonical) {
                basemapEngineHost.renderedTileKey(styleDigest, instance)
            }
            // Rendered this frame, or already on the GPU from an earlier one. Both are ways for the
            // draw to have pixels; only naming neither is a defect.
            check(key in renderedKeys || glObjectRegistry.resident(key) != null) {
                "every selected tile instance must name a tile this frame rendered or one already resident"
            }
            PreparedGroundInstance(instance = instance, resourceKey = key)
        }
    }

    /**
     * Derives the same [StaticResourceReference.External] shape
     * `FramePlanningCore.staticResourceTraversal()`'s own `external(...)` helper produces for a
     * [ResourceClass.MODEL_TEXTURE], independently — both are the same pure function of
     * `(ResourceClass.MODEL_TEXTURE, locator)`, so they always agree without this function needing
     * to parse the traversal list back apart by position.
     *
     * That position-parse is unavailable here specifically, and only here: exactly one traversal entry
     * exists per plan sticker and exactly one per plan model's GLB, so both of those *are* read back by
     * position — but a geometry's own consumer textures and a [Model.texture] override traverse under
     * the same reused [ResourceClass.MODEL_TEXTURE], so telling one from the other by position would
     * mean re-deriving the traversal's own interleaving here. Recomputing the key cannot drift.
     */
    private fun externalImageReference(locator: ResourceLocator): StaticResourceReference.External {
        val derived = geometryKeyDeriver.external(ResourceClass.MODEL_TEXTURE, locator)
        return StaticResourceReference.External(
            resourceClass = ResourceClass.MODEL_TEXTURE,
            locator = locator,
            maximumResponseBytes = configuration.resourceLimits.maximumBytesFor(ResourceClass.MODEL_TEXTURE),
            resourceKey = derived.key,
            rawKey = requireNotNull(derived.rawKey),
            privateRentileKey = rentilePrivateKeyResolver.resolve(locator, ResourceClass.MODEL_TEXTURE),
            canonicalIdentity = derived.identity,
        )
    }

    /**
     * One [Model] turned into the [PreparedModel] the draw path consumes: the acquired GLB decoded,
     * its [AnimationTrack]s resolved and sampled, its node hierarchy composed against that pose, and
     * one joint palette per skin a draw item names.
     *
     * **This is the third parse of the same bytes in this prepared frame, and that is recorded rather
     * than fixed.** `PARSE_GLB` and `VALIDATE_GLB_FEATURES` each independently re-scan and re-parse the
     * container inside the resource driver, and [decodeModel] parses it once more here. `decodeModel`'s
     * own KDoc carries the measurement — 0.85 ms of a 3.6 ms total, 24%, over a real 133 KB model — and
     * the reason not to fix it in this cycle: the fix is a parsed-model residency belonging beside
     * `ResidentGeneration.decoded`, which is `null` in production for images too, and half of that fix
     * is worse than none of it.
     *
     * **Every failure below is typed and names the GLB it is about.** A [ModelDecodeResult] failure
     * separates into the three codes those classes already mean: a structural or BIN-chunk fault is
     * `RESOURCE_PARSE_FAILED`, a feature outside ADR 0021's subset is `UNSUPPORTED_RESOURCE_FEATURE`,
     * and an expanded form that will not fit `ResourceLimits.maximumDecodedImageBytes` is
     * `RESOURCE_DECODE_FAILED` — the same code, at the same stage, this class's own PNG path already
     * reports for an image that overruns the identical budget.
     *
     * A missing, out-of-range or duplicated [AnimationSelector] reports `RESOURCE_PARSE_FAILED` at
     * [PipelineStage.RESOURCE_PARSING] with `fieldName = animationSelector`. That field is allowlisted
     * at `RESOURCE_PARSING` and deliberately **not** at `FRAME_PLANNING`, which is a real ordering
     * constraint rather than a stylistic one: resolving a selector needs the GLB's own animation
     * catalogue, which does not exist until the bytes have been acquired, so the check cannot live in
     * `FramePlanningCore` at all.
     *
     * A `null` from [jointMatricesForSkin] is a failure here rather than a static draw. Its three
     * `null`s are a singular skinned-node transform, a joint node the default scene never reaches, and
     * a skin over [com.rohittp.reng.internal.gl.MAXIMUM_MODEL_JOINTS] joints; each could only be
     * papered over by inventing a pose, and posing a rig arbitrarily is a silently wrong picture.
     * [SceneModel]'s "a skin absent from the map draws its mesh statically" covers the *other* case —
     * a mesh instanced at a node that names no skin at all — which never enters this loop.
     */
    private fun prepareModel(
        model: Model,
        glbReference: StaticResourceReference.External,
        textureReference: StaticResourceReference.External?,
        decodedByKey: Map<ResourceKey, DecodedImage>,
    ): PreparedModel {
        val glbKey = glbReference.resourceKey
        val bytes = residentCache.current(glbKey)?.stored?.bytes
            ?: error("a successful resource operation must leave its content resident")
        val decoded = when (val result = decodeModel(bytes, configuration.resourceLimits)) {
            is ModelDecodeResult.Success -> result.model
            ModelDecodeResult.Malformed -> throw modelParseFailure(glbKey)
            ModelDecodeResult.Unsupported -> throw modelUnsupportedFailure(glbKey)
            ModelDecodeResult.TooLarge -> throw modelDecodeBudgetFailure(glbKey)
        }

        val tracks = model.animationTracks
        val resolution = when (val resolved = resolveAnimationSelectors(decoded.document, tracks)) {
            is AnimationResolution.Resolved -> resolved
            AnimationResolution.Missing, AnimationResolution.Duplicate -> throw animationSelectorFailure(glbKey)
        }
        val sampled = sampleAnimationTracks(decoded.document, decoded.bin, tracks, resolution)
            ?: throw modelParseFailure(glbKey)
        val nodeTransforms = composeGlobalTransforms(decoded.document, sampled)

        val jointMatricesBySkin = LinkedHashMap<Int, List<DoubleMatrix4>>()
        for (item in decoded.drawItems) {
            val skinIndex = item.skinIndex ?: continue
            if (skinIndex in jointMatricesBySkin) continue
            val skin = decoded.skins.getOrNull(skinIndex) ?: throw modelParseFailure(glbKey)
            jointMatricesBySkin[skinIndex] = jointMatricesForSkin(skin, nodeTransforms, item.nodeIndex)
                ?: throw modelParseFailure(glbKey)
        }

        return PreparedModel(
            placement = model.placement,
            glbKey = glbKey,
            model = decoded,
            overrideTexture = textureReference?.let { reference ->
                PreparedGeometryTexture(
                    resourceKey = reference.resourceKey,
                    image = requireNotNull(decodedByKey[reference.resourceKey]) {
                        "a successful model-texture acquisition must decode every traversed image"
                    },
                )
            },
            nodeTransforms = nodeTransforms,
            jointMatricesBySkin = jointMatricesBySkin,
        )
    }

    override suspend fun prepareBatch(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
    ): List<PreparedFrame> {
        if (plans.size > configuration.maximumPreparationBatchSize) {
            throw RenGException(
                code = RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                stage = PipelineStage.FRAME_PLANNING,
                diagnostics = listOf(
                    failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PLANNING,
                        fieldName = DiagnosticField.PLANS,
                        limit = configuration.maximumPreparationBatchSize.toLong(),
                        actual = plans.size.toLong(),
                    ),
                ),
            )
        }
        if (plans.isEmpty()) return emptyList()
        // One firewall invocation for the whole batch (ADR 0051): every frame's own `withOperation`
        // joins this root instead of opening a registry of its own, so the batch shares one route
        // index and one set of single-flight latches. Measured at three frames over one camera: 14
        // engine resource requests before, against 6 for a single frame.
        //
        // `prepare` still takes `preparationMutex` per frame, which is correct and not redundant:
        // this shares the firewall invocation, it does not make a batch concurrent. The frames run
        // in order exactly as they did when this was `plans.map { prepare(it, accessMode) }`.
        return basemapEngineHost.withSharedOperation(accessMode) {
            // Opened here and cleared however the batch ends (ADR 0052), so a batch that throws
            // part-way leaves no rendered pixels behind for the next one to reuse under a style it
            // may not be rendering any more.
            batchRenderedTiles = mutableMapOf()
            try {
                plans.map { prepare(it, accessMode, priority) }
            } finally {
                batchRenderedTiles = null
            }
        }
    }

    /**
     * Acquires everything one frame plan asked for that is not already in hand: the configured basemap
     * style ([styleReference], when the plan draws one), every traversed external image
     * ([imageReferences] — a sticker's, a model's base-colour override, or a geometry consumer
     * texture's), every model GLB ([modelGlbReferences]), and the ground itself ([canonicalTiles]).
     * Returns the decoded images by key plus the rendered ground.
     *
     * **Two of those are acquired and not decoded here, for two different reasons.** A style is not an
     * image at all: it is validated, compiled through the engine, written and installed by the resource
     * driver's own basemap-style commit verbs. A GLB is not an image either, and its decode needs the
     * `ResourceLimits` and the failure vocabulary [prepareModel] owns — so this function's job for a
     * model ends at leaving its bytes resident, which is exactly what [prepareModel] then reads.
     *
     * Returns nothing and performs no adapter call at all when there is nothing to acquire, which is
     * what keeps `prepare()` on a plan with no stickers, no models, no geometry consumer textures, and
     * no configured basemap honestly free of consumer exchange.
     *
     * **One owner for the whole frame.** The occurrence set comes from [buildResourceOperationDefinition],
     * which assigns one [ResourceOwnerId] per preparation item rather than one per reference. That is
     * not a detail: it is the entire content of ADR 0016's style-owner barrier, which orders the style's
     * Store write behind the completion of every other resource the same items referenced. An owner per
     * reference gives the style an owner with no other work, and the barrier becomes vacuously true.
     *
     * **The firewall invocation spans the whole driver run**, because the engine's own sprite, TileJSON
     * and GeoJSON acquisition happens inside the style's compilation, which happens inside the driver.
     * Opening it afterwards would leave every one of those exchanges with no registry to be recognised
     * by, and each would fail closed as `AMBIGUOUS_RESOURCE_ROUTE`.
     *
     * **It also spans the tile phase**, for the reason ADR 0016 gives rather than for mechanical
     * necessity: one operation registry belongs to one preparation invocation. The style's routes are
     * knowable only once the document has been read (inside the driver) and the tiles' only once it has
     * been compiled, so both phases register incrementally into the one registry this invocation owns.
     *
     * A [ResourceOperationOutcome.Cancelled] outcome — reached when one route's adapter call observes
     * its own cancellation while a sibling route is still active — is rethrown as a genuine
     * [CancellationException] rather than a [RenGException], consistent with keeping cancellation
     * unwrapped throughout this codebase.
     */
    @Suppress("LongParameterList")
    private suspend fun acquireFrameResources(
        styleReference: StaticResourceReference.External?,
        imageReferences: List<StaticResourceReference.External>,
        modelGlbReferences: List<StaticResourceReference.External>,
        canonicalTiles: List<CanonicalBasemapTile>,
        labelTiles: List<CanonicalBasemapTile>,
        accessMode: ResourceAccessMode,
        /** The engine gate lane this frame's tiles are rendered in (ADR 0053). */
        priority: RenGRenderPriority,
        /**
         * Collects the lease of every generation this acquisition installs or re-observes, so the frame
         * this preparation is building can release them on `close()`. Owned by [prepare], which is also
         * what releases them when no frame ends up owning them.
         */
        leaseSink: MutableList<Lease>,
    ): FrameAcquisition {
        val references = listOfNotNull(styleReference) + imageReferences + modelGlbReferences
        if (references.isEmpty()) {
            preparedBasemapStyle = null
            return FrameAcquisition(emptyMap(), emptyList(), basemapStyleDigest = null)
        }

        val definition = buildResourceOperationDefinition(
            traversalsByItem = listOf(references),
            accessMode = accessMode,
            maximumConcurrentRoutes = minOf(
                configuration.maximumConcurrentResourceOperations,
                references.size,
            ),
        )

        var basemapTiles: List<RenderedBasemapTile> = emptyList()
        var basemapStyleDigest: String? = null
        var terrain: TerrainAcquisitionOutcome? = null
        var terrainExaggeration = 1.0
        var labelCandidates: AcquiredLabelCandidates? = null
        // Read inside the invocation and carried out of it, because the registry that holds it is
        // discarded when the invocation ends (ADR 0016). It is the manifest of the sprite pair this
        // frame's own style declared, and the only way an icon's `imageName` becomes atlas geometry.
        var spriteAtlas: SpriteAtlasManifest? = null
        val outcome = basemapEngineHost.withOperation(accessMode) {
            val driven = preparationDriver.run(definition, leaseSink)
            if (driven is ResourceOperationOutcome.Success) {
                // Read back rather than taken from the compile action: on a RESIDENT-provenance frame
                // the pure core emits no CompileBasemapStyle at all, so there is no action to take it
                // from. The host retains the compilation across invocations, so this is the one place
                // the frame's style is obtainable on every frame alike. A frame that traversed no style
                // -- since E-labels task 8b, one that drew neither the basemap nor its labels -- clears
                // it, so this always describes the frame just prepared.
                // Asked for by content, not by key alone: a compilation whose visibility install never
                // ran -- the style's own Store write failing is enough -- would otherwise be handed back
                // here for bytes that are not resident, and paired with routes renderBasemapTiles derives
                // from the bytes that are. The host declines that, so both halves of the frame are keyed
                // off this one observation of what is resident.
                val style = if (styleReference == null) {
                    null
                } else {
                    basemapEngineHost.currentPreparedStyle(
                        styleKey = styleReference.resourceKey,
                        contentDigest = residentCache.current(styleReference.resourceKey)?.stored?.contentDigest,
                    )
                }
                preparedBasemapStyle = style
                if (styleReference != null && style != null) {
                    // Both halves read one manifest. It is bound to the style's content digest, so the
                    // second call is a lookup rather than a second parse of a 248 KB document -- see
                    // `BasemapEngineHost.styleManifest`.
                    val manifest = if (canonicalTiles.isEmpty() && labelTiles.isEmpty()) {
                        null
                    } else {
                        completedStyleManifest(styleReference)
                    }
                    if (manifest != null && canonicalTiles.isNotEmpty()) {
                        basemapTiles =
                            renderBasemapTiles(manifest, style, canonicalTiles, accessMode, priority)
                        basemapStyleDigest = style.digest
                        // Inside the invocation, on the routes `tileTimeRoutes` already preregistered
                        // for every `raster-dem` source (the 3x3 neighbourhood, which the ring is a
                        // subset of at any overzoom). Never throws for an acquisition failure: ADR
                        // 0041 makes terrain the one basemap resource that degrades to a picture RenG
                        // has already shipped rather than costing the frame.
                        terrain = terrainAcquisition.acquire(style, manifest, canonicalTiles)
                        terrainExaggeration = manifest.terrainExaggeration
                    }
                    if (manifest != null && labelTiles.isNotEmpty()) {
                        val handover = labelHandover(manifest, style, labelTiles, accessMode)
                        labelCandidates = handover.candidates
                        // Taken from the handover rather than read here, because on a frame that reused
                        // a retained one nothing in this invocation ever asked the engine for the sprite
                        // pair -- so the registry holds no manifest and reading it now would answer
                        // `null` for a style that declares one. See [RetainedLabelHandover].
                        spriteAtlas = handover.spriteAtlas
                    }
                }
            }
            driven
        }
        when (outcome) {
            is ResourceOperationOutcome.Success -> Unit
            is ResourceOperationOutcome.Failure -> throw outcome.failure.toException()
            is ResourceOperationOutcome.Cancelled ->
                throw CancellationException("resource preparation observed a route cancellation")
        }

        val decodedByKey = imageReferences.associate { reference ->
            val generation = residentCache.current(reference.resourceKey)
                ?: error("a successful resource operation must leave its content resident")
            // Decoded once per generation, not once per frame (ADR 0059). The only consumer of these
            // pixels is `cachedTexture`'s upload lambda, which does not run once a texture is
            // registered -- so before this, every frame after a sticker's first decoded its PNG and
            // threw the result away. Measured at 14.3 ms for one 512x512 image on a native build,
            // against a consumer frame budget of about 51 ms.
            generation.decoded?.let { return@associate reference.resourceKey to it }
            val stored = generation.stored
            val image = when (
                val decoded = decodePng(stored.bytes, configuration.resourceLimits.maximumDecodedImageBytes)
            ) {
                is PngDecodeResult.Success -> decoded.image
                else -> throw RenGException(
                    code = RenGErrorCode.RESOURCE_DECODE_FAILED,
                    stage = PipelineStage.RESOURCE_DECODING,
                    diagnostics = listOf(
                        failureContextDiagnostic(
                            stage = PipelineStage.RESOURCE_DECODING,
                            fieldName = DiagnosticField.RESOURCE,
                            resourceClass = reference.resourceClass,
                            resourceKey = reference.resourceKey,
                        ),
                    ),
                )
            }
            // Charged to maximumResidentCpuResourceBytes here, which is what keeps this memo bounded
            // and what finally makes `queryResources`' decodedCpuBytes a number rather than a zero.
            residentCache.attachDecoded(generation, image)
            reference.resourceKey to image
        }
        return FrameAcquisition(
            decodedByKey,
            basemapTiles,
            basemapStyleDigest,
            labelCandidates,
            spriteAtlas,
            terrain,
            terrainExaggeration,
        )
    }

    /**
     * This frame's style, read as the route-composition manifest both the ground and the labels need.
     * Called from **inside** the invocation that compiled the style, so every phase deriving routes
     * from it shares one operation registry (ADR 0016).
     *
     * **One manifest, two consumers, and a cache that makes the second read free.** The ground
     * ([renderBasemapTiles]) and the Label handover ([acquireLabelCandidates]) both compose urls out
     * of this, over the same tile selection, and E-labels task 8b made either of them reachable without
     * the other. Resolving it once per frame and passing it to whichever halves run is what keeps the
     * two from drifting -- and is why this returns the manifest rather than doing anything with it.
     *
     * The manifest is asked of the engine host rather than carried out of the driver. It is the same pure
     * function of the same two inputs the driver's own `ValidateBasemapStyle` ran — the style bytes and
     * the style's own locator, which is exactly what Rentile receives as `StyleInput.Prefetched.baseUri`
     * — so the two derivations cannot disagree. The bytes are read from the resident generation here,
     * which is sound *because this runs after* the driver's `InstallBasemapStyleVisibility`: what is
     * resident by now is this frame's own document. The compilation runs before that install, where the
     * resident generation is still the previous frame's, and so compiles the content the driver hands it
     * instead — see `BasemapEngineHost.preparedStyle`.
     *
     * **A basemap frame used to parse its style document twice** — once in `ValidateBasemapStyle` and
     * once again here — which was pure duplication measured at 1.9 ms per parse for a 248 KB
     * production-shaped style on the JVM (`internal.basemap.BasemapRouteDerivationCostTest`), on every
     * frame. [BasemapEngineHost.styleManifest] now binds the derivation to the style's content digest
     * beside the compilation it already binds that way, so a frame over byte-identical content reads the
     * document once. Preregistration itself stays uncached, and deliberately: it is immaterial (a
     * realistic 40-tile frame names 150 routes for a few ms of CPU, less than one of the 150 tile fetches
     * it then performs), and it is the one part of this that genuinely varies per frame.
     *
     * A rejected manifest is unreachable: the driver validated these exact bytes on this exact frame and
     * would have failed the operation otherwise, so reaching it means RenG's own derivation is not a
     * function — a defect, not a consumer condition.
     *
     * **The manifest is then completed with the TileJSON documents the compilation observed.** A
     * `url`-form source — 96 of the 98 tile sources across the verified style corpus — names a TileJSON
     * document rather than a template, and that document is fetched during `engine.prepare`, strictly
     * before this point. `completeBasemapStyleManifest` folds it back in so the source composes its tile
     * urls exactly as an inline one does; a source whose document never arrived stays degraded and
     * contributes nothing at all, rather than guessing a template that would fail closed anyway.
     */
    private fun completedStyleManifest(styleReference: StaticResourceReference.External): BasemapStyleManifest {
        val stored = residentCache.current(styleReference.resourceKey)?.stored
            ?: error("a successful style commit must leave the style document resident")
        val manifest = when (
            val derived = basemapEngineHost.styleManifest(
                styleKey = styleReference.resourceKey,
                stored = stored,
                baseUri = styleReference.locator.value,
            )
        ) {
            is BasemapStyleManifestOutcome.Derived -> derived.manifest
            is BasemapStyleManifestOutcome.Rejected ->
                error("the resource driver already validated this exact style document")
        }
        // The manifest the driver derived names each `url`-form source's TileJSON *document*, not its
        // tiles. Completing it here folds in the documents the firewall observed while the engine
        // compiled this exact content -- the same content digest both halves are keyed off -- so a
        // `url`-form source composes its tile urls exactly as an inline one does. A source whose document
        // never arrived stays degraded and contributes nothing, rather than guessing a template.
        return completeBasemapStyleManifest(
            manifest,
            basemapEngineHost.tileJsonDocuments(styleReference.resourceKey, stored.contentDigest),
        )
    }

    /**
     * Declares the tile routes [manifest] says this frame's ground will make the engine ask for, then
     * acquires and draws it. Runs inside the style's own invocation, as [completedStyleManifest] does.
     */
    private suspend fun renderBasemapTiles(
        manifest: BasemapStyleManifest,
        style: PreparedStyle,
        canonicalTiles: List<CanonicalBasemapTile>,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
    ): List<RenderedBasemapTile> {
        // Only the tiles whose rendered texture is not already resident are sent to the engine.
        //
        // This completes an intent the rest of this class already states rather than introducing a new
        // one. `RenGPreparedFrame.basemapTiles` says a tile's bytes are decoded and uploaded at draw
        // "because a tile whose GL texture is still resident from an earlier frame must cost neither",
        // and `GlObjectRegistry` says an unleased budget-tracked texture survives losing its lease "so
        // a pan back over the same tile costs nothing". Both were true of the decode and the upload and
        // neither was true of the *rasterisation*: every frame asked the engine for every visible tile,
        // rendered it to PNG, and then threw the PNG away at draw for any tile already on the GPU.
        //
        // Measured on an Apple M2 release build through a consumer harness, one camera prepared three
        // times over a one-source production style: 703, 714, 740 ms, with 11 vector tiles re-fetched
        // per prepare. On a Snapdragon 8 Gen 3 a frame cost 895-2256 ms of which 863-2105 ms was this
        // call, against 17-151 ms to draw and read back the result — so a four-and-a-half second
        // timeline played about four frames.
        //
        // The identity is asked of the host rather than derived here, because `tileOutputSize` is an
        // engine render option and `renderedTileKey`'s own KDoc says a caller that guessed it would
        // name a tile the engine never rendered.
        //
        // One assumption, stated because it is the first cross-phase read of the registry: this runs
        // during preparation and the registry is otherwise touched only under a draw, so a consumer
        // that draws one frame while preparing another would be racing a LinkedHashMap. Every RenG
        // consumer today prepares and draws in sequence on the thread owning the GL context, which is
        // what `prepare() has no render context` already implies about how these phases interleave.
        // Routes for EVERY visible tile, never only the missing ones, and this is the one place the
        // residency filter must not reach.
        //
        // `tileTimeRoutes` walks `tiles x manifest.sources`, and a `raster-dem` source expands each
        // tile through `demNeighbourhoodOrSelf` — so this frame's whole DEM neighbourhood is derived
        // from the same list. A resident *colour* texture says nothing about whether the DEM beneath
        // it is acquired: the two are tracked separately, colour in `GlObjectRegistry` and terrain in
        // `PreparedTerrain`. Narrowing this to `missing` starves terrain of its routes, and on a
        // camera whose colour tiles are all resident it registers none at all.
        //
        // That is not hypothetical. It is what the terrain and ground-anchor readback suites caught
        // when this filter first landed here: eight of nine terrain cases failed with the ground
        // displacing by exactly zero pixels. The upstream commit could not have seen it — it was
        // written against a base with no terrain at all (ADR 0041 came later).
        basemapEngineHost.registerRoutes(
            tileTimeRoutes(
                manifest = manifest,
                tiles = canonicalTiles,
                accessMode = accessMode,
                limits = configuration.resourceLimits,
            ),
        )

        // Two places an answer to "does the engine need to render this again" can already live: a
        // resident GL texture (ADR 0046), and this batch's own memo (ADR 0052). The second exists
        // because the first cannot see inside a batch, where nothing has been drawn yet.
        //
        // A memo hit is carried into THIS frame's own tile list rather than left with the frame that
        // rendered it. A batch says nothing about the order its frames are drawn in, or whether they
        // are all drawn, so a frame that pointed at a sibling's texture would answer
        // RESOURCE_UNAVAILABLE for a tile the batch definitely rendered. Sharing the immutable
        // RenderedBasemapTile keeps every frame self-sufficient and still holds one copy of the
        // pixels: RenGPreparedFrame snapshots the list it is given, never the arrays inside it.
        val memo = batchRenderedTiles
        val alreadyRendered = ArrayList<RenderedBasemapTile>()
        val missing = ArrayList<CanonicalBasemapTile>(canonicalTiles.size)
        for (tile in canonicalTiles) {
            val key = basemapEngineHost.renderedTileKey(style, tile)
            if (glObjectRegistry.resident(key) != null) continue
            val remembered = memo?.get(key)
            if (remembered != null) alreadyRendered += remembered else missing += tile
        }
        if (missing.isEmpty()) return alreadyRendered
        // The batch owns engine-side resources and is closed as soon as the pixels are in hand: nothing
        // downstream of here reads it, because rendering is where a PreparedBatch's whole purpose ends.
        // `missing`, not `canonicalTiles`, on both halves: ADR 0044's raw budget is charged for the
        // tiles this frame actually rasterises rather than every tile it draws, which is where that
        // budget belongs now that a resident tile is never asked for (ADR 0046).
        val freshlyRendered = basemapEngineHost.prepareTiles(style, missing).use { prepared ->
            basemapEngineHost.renderTiles(
                prepared,
                asRawPixels = rawTilesFit(prepared.tiles.size),
                priority = priority,
            )
        }
        if (memo != null) freshlyRendered.forEach { memo[it.key] = it }
        return if (alreadyRendered.isEmpty()) freshlyRendered else alreadyRendered + freshlyRendered
    }

    /**
     * Whether [tileCount] tiles of raw pixels still fit under
     * [ResourceLimits.maximumInFlightRawBasemapTileBytes], counting what open Prepared Frames already
     * hold (ADR 0044).
     *
     * The estimate is exact rather than a guess: Rentile renders square tiles at the configured
     * output size, so a tile is `size * size * 4` bytes and the whole batch is that times its tile
     * count, all of it known before the render call. Answering `false` costs the frame an encode and
     * a decode -- the behaviour of every release before ADR 0044 -- and never fails it.
     */
    private fun rawTilesFit(tileCount: Int): Boolean {
        val side = basemapEngineHost.tileOutputSizePixels.toLong()
        val perTile = side * side * RGBA_BYTES_PER_PIXEL
        val requested = perTile * tileCount.toLong()
        return outstandingRawTileBytes + requested <=
            configuration.resourceLimits.maximumInFlightRawBasemapTileBytes
    }

    /**
     * Hands this frame's tiles to the Label handover, from inside the same invocation the style was
     * compiled in. Everything hard about the sequence -- the two rounds of preregistration, reading
     * `glyphUrls` before the plan is closed, closing it in a `finally`, and the three failures it
     * names -- belongs to [BasemapEngineHost.acquireLabelCandidates]; this supplies the two things
     * only the renderer holds and calls it once.
     *
     * **The label tile routes are the ground's own derivation over the label tile set.** A label layer
     * reads the vector tiles of the same sources the style declares, so [tileTimeRoutes] is already
     * the right function -- there is no second, label-specific route shape. On a frame that draws both,
     * the ground registered these same routes moments ago and this round is idempotent
     * ([OperationRegistry.preregister] takes an identical redeclaration without complaint); on a
     * `drawBasemap = false, drawLabels = true` frame it is the only round there is, which is precisely
     * why the handover registers them itself rather than trusting a caller to have done it.
     *
     * [BasemapStyleManifest.glyphTemplate] is RenG's own resolved copy and must stay so: `glyphUrls`
     * substitutes the caller's template, so the credential that reaches RenG's `Transport` is the one
     * in this string rather than one the engine composed. A style declaring no `glyphs` passes `null`,
     * which is a failure only if the engine nevertheless froze a non-empty closure -- the handover's
     * own judgement, not this call site's.
     *
     * ---
     *
     * **And it is called once per *tile set*, not once per frame.** Measured on a real context with a
     * stationary camera, the label path was 514-520 ms of a 605-610 ms `prepare()` -- ~85% of it, and
     * 5.5x the ground path over the same 17 tiles -- because nothing was retained: three acquisitions of
     * an identical tile set asked the consumer's `Transport` for all 17 label tiles and all 16 Glyph
     * Ranges every single time (17 -> 34 -> 51 and 16 -> 32 -> 48) and re-packed a **byte-identical**
     * atlas, the same `contentKey` all three times.
     *
     * **The key is [BasemapEngineHost.labelCandidateRequestKey] and RenG derives nothing of its own.**
     * Rentile publishes that key for precisely this cache, computed before any network from its own
     * compiled-style digest, the sorted de-duplicated tile identities, and a private label-semantics
     * version it bumps whenever a change to label evaluation, text layout or glyph packing would alter a
     * batch this key would otherwise leave looking unchanged. That last input is unreachable from here,
     * which is exactly why a key RenG derived itself would be wrong: it would be a second opinion about
     * someone else's cache validity, and it would keep looking valid on the day the engine's own answer
     * moved. What the key deliberately omits -- credentials, sessions, validators, the Glyph Closure --
     * is what makes it a *request* identity; [com.rohittp.rentile.LabelCandidateBatch.contentKey] is the
     * answer's, and RenG stores the atlas under it separately.
     *
     * **This is a cache and never Frame History, and `clearFrameHistory()` therefore leaves it alone.**
     * ADR 0035 draws the line in as many words: every cross-frame mechanism in this renderer except the
     * fade -- decoded images, uploaded textures, parsed GLBs, compiled shaders, resident tiles -- "changes
     * how fast a frame is produced, never how it looks", and this one is squarely on that side. Two
     * consecutive frames over one tile set draw the same labels whether or not the batch behind them was
     * re-fetched, so clearing it would make a history call into a cache-invalidation call as well, and
     * `CONTEXT.md`'s Frame History entry already says the opposite twice: `clearFrameHistory()` "neither
     * frees resources nor invalidates prepared frames", and its own `_Avoid_` list names "Cache". The
     * resident cache, which suppresses far more consumer traffic than this does, is likewise untouched
     * by it; `freeResources` and `close()` are the calls that own caches.
     *
     * **What it holds contradicts no GL opinion, because it holds no GL object.** The batch is an
     * immutable Rentile value -- candidates, layer styles, atlas PNG bytes, a content key, diagnostics --
     * with no `close()` and nothing engine-side behind it, unlike a `PreparedBatch`. The atlas's *texture*
     * lives where every other texture does, in [glObjectRegistry] under the content-derived key
     * `ResourceKeyDeriver.glyphAtlas`, so `freeResources`, LRU eviction, `notifyGpuObjectsGone` and
     * `close()` all keep exactly the authority over it they already had: a frame that lost the texture
     * re-uploads it from [RetainedGlyphAtlas], and a frame that lost both decodes it again from bytes
     * that are still correct. `close()` drops both retentions as the CPU state they are.
     */
    private suspend fun labelHandover(
        manifest: BasemapStyleManifest,
        style: PreparedStyle,
        labelTiles: List<CanonicalBasemapTile>,
        accessMode: ResourceAccessMode,
    ): RetainedLabelHandover {
        val requestKey = basemapEngineHost.labelCandidateRequestKey(style, labelTiles)
        val handover = retainedLabelHandover?.takeIf { it.requestKey == requestKey } ?: run {
            val acquired = basemapEngineHost.acquireLabelCandidates(
                style = style,
                tiles = labelTiles,
                glyphTemplate = manifest.glyphTemplate,
                labelTileRoutes = tileTimeRoutes(
                    manifest = manifest,
                    tiles = labelTiles,
                    accessMode = accessMode,
                    limits = configuration.resourceLimits,
                ),
                limits = configuration.resourceLimits,
            )
            // Committed only once the acquisition has fully succeeded, exactly as the LOD and the fade
            // are. A throw leaves the previous entry standing, which is correct rather than merely
            // convenient: it is keyed on a request this frame is not making, so it can only ever be
            // served to a later frame that asks the same question again.
            RetainedLabelHandover(
                requestKey = requestKey,
                candidates = acquired,
                spriteAtlas = basemapEngineHost.spriteAtlasManifest(),
            ).also { retainedLabelHandover = it }
        }
        // ADR 0036's "once per prepare", and this call site is the whole of the mechanism: the
        // handover runs once per prepare, so an aggregate emitted beside it does too, however many
        // exclusions the batch reported and however many layers or tiles they came from. The batch's
        // own diagnostics never travel further than this line -- `reportLabelContentExclusions`
        // returns nothing, and what it emits is RenG's own code with a severity and no other field.
        //
        // **Deliberately outside the `run` block**, so a frame served from the retained handover reports
        // exactly what a frame that re-acquired it would. The aggregate describes the content this frame
        // is drawing, not the exchange that fetched it, and a consumer whose warning appeared on frame 0
        // and vanished on frame 1 would be reading RenG's cache state rather than its own style.
        reportLabelContentExclusions(handover.candidates.batch.diagnostics, configuration.diagnosticSink)
        return handover
    }

    override suspend fun cancelPreparations() {
        val outcome = driver.run(RendererLifecycleOperation.CancelPreparations) { null }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
        preparationDriver.cancel()
    }

    override fun clearFrameHistory() {
        val outcome = driver.run(RendererLifecycleOperation.ClearFrameHistory) { operation ->
            if (operation == RendererLifecycleOperation.ClearFrameHistory) {
                previousEncodedPlan = null
                previousSelectedLod = null
                previousLabelFade = LabelFadeState.EMPTY
                // `retainedLabelHandover`, `retainedGlyphAtlas` and `retainedSpriteAtlas` are
                // deliberately NOT cleared here,
                // and the omission is a decision rather than an oversight: they are caches, this call
                // clears history, and CONTEXT.md says a history clear "neither frees resources nor
                // invalidates prepared frames". ADR 0035's rule -- after `clearFrameHistory()` the render
                // is a pure function of the plan -- still holds, because neither of them can change what
                // a frame looks like. `labelHandover` carries the full argument.
                identityRegistry = CanonicalIdentityRegistry()
                framePlanningCore = newFramePlanningCore(identityRegistry)
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }

    // ---- Resource lifecycle ---------------------------------------------------------------------

    override fun queryResources(selector: ResourceSelector): ResourceReport {
        val outcome = driver.run(RendererLifecycleOperation.QueryResources(selector)) { null }
        return when (outcome) {
            RendererLifecycleOutcome.EmptyResourceResult -> emptyResourceReport()
            // The cache owns raw bytes and decoded pixels; the registry owns GL handles and is the
            // only thing that can answer for GPU bytes. Passing the lookup rather than the registry
            // is what keeps the cache free of GL knowledge -- and it is why the report can now say
            // "I do not know" about a sticker's texture instead of the literal zero it used to
            // assert while that texture was resident.
            RendererLifecycleOutcome.Succeeded, RendererLifecycleOutcome.NoOp ->
                residentCache.report(selector, glObjectRegistry::gpuByteAccount)
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
        }
    }

    override fun queryMetrics(): MetricReport {
        val outcome = driver.run(RendererLifecycleOperation.QueryMetrics) { null }
        return when (outcome) {
            // A closed renderer answers empty rather than its final totals. The counters are alive
            // only while the renderer is, and reporting a frozen history would make a monotonic
            // series look as though it had simply stopped moving -- ADR 0049.
            RendererLifecycleOutcome.EmptyResourceResult -> MetricReport(emptyMap())
            RendererLifecycleOutcome.Succeeded, RendererLifecycleOutcome.NoOp -> metricRecorder.snapshot()
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
        }
    }

    override fun freeResources(selector: ResourceSelector): ResourceFreeResult {
        requireContextThread(PipelineStage.RESOURCE_FREE)
        var result: ResourceFreeResult? = null
        val outcome = driver.run(RendererLifecycleOperation.FreeResources(selector)) { operation ->
            if (operation is RendererLifecycleOperation.FreeResources) {
                result = residentCache.free(operation.selector)
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        return when (outcome) {
            RendererLifecycleOutcome.EmptyResourceResult, RendererLifecycleOutcome.NoOp ->
                ResourceFreeResult(matchedKeys = 0, fullyFreedKeys = 0, deferredKeys = 0, alreadyFreeKeys = 0)
            RendererLifecycleOutcome.Succeeded ->
                requireNotNull(result) { "a successful free must have run its permitted operation" }
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
        }
    }

    override fun notifyGpuObjectsGone() {
        // NotifyGpuObjectsGone never reaches the permitted-operation executor (see GlLifecycleDriver /
        // RendererLifecycleStateMachine: it routes through AwaitRenderCallQuiescence instead, and
        // GlLifecycleDriver.forgetWithoutDeleting() already handles the registry and program cache on
        // its own). Because `registry` there is this exact `glObjectRegistry` instance (constructed
        // once, in RendererFactory, and threaded into both GlLifecycleDriver and this class), every
        // cachedTexture()-registered sticker/geometry-consumer texture is ALSO forgotten there,
        // without an extra line here -- forgotten, never deleted (ADR 0007/0015): the GL handles are
        // already gone with the lost context, so there is nothing left to validly delete, and the
        // decoded CPU-side content those handles cached stays resident and valid regardless. This
        // class's own GL-object fields are outside both of those, so they are forgotten here,
        // unconditionally -- idempotent to call even when already forgotten.
        driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null }
        offscreenSurface = null
        compositePipeline = null
        stickerPipeline = null
        groundPipeline = null
        labelPipeline = null
        iconPipeline = null
        globeGroundPipeline = null
        geometryPipelines.clear()
        // The model pipelines and every uploaded primitive are forgotten on exactly the same terms and
        // in exactly the same place as the geometry pipelines above: the joint uniform buffers, the
        // vertex arrays and the vertex/index buffers all died with the context, so there is nothing
        // valid left to delete -- while the DecodedModel every one of them was uploaded from is CPU-side
        // and survives untouched, so the next draw re-uploads without re-fetching or re-parsing.
        modelPipelines.clear()
        uploadedPrimitives.clear()
    }

    override fun adoptCurrentRenderContext() {
        // Sets rather than checks: this call is the declaration that the context now lives
        // here, so it is the one entry point a thread change is allowed to arrive on.
        contextThread = currentCallingThread()
        val outcome = driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        when (outcome) {
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
            RendererLifecycleOutcome.Succeeded -> {
                val profile = requireNotNull(driver.profile) {
                    "a successful adoption must have recorded a profile"
                }
                when (
                    val recreated = createInternalGlState(binding, profile, programs, configuration)
                ) {
                    is InternalGlStateResult.Created -> {
                        offscreenSurface = recreated.state.offscreenSurface
                        compositePipeline = recreated.state.compositePipeline
                        stickerPipeline = recreated.state.stickerPipeline
                        groundPipeline = recreated.state.groundPipeline
                        labelPipeline = recreated.state.labelPipeline
                        iconPipeline = recreated.state.iconPipeline
                    }

                    is InternalGlStateResult.Failed -> {
                        // Nothing was actually recreated: push the machine back to
                        // AWAITING_CONTEXT_ADOPTION rather than leaving it LIVE with no offscreen
                        // surface, then report the real failure.
                        driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null }
                        throw recreated.failure.toException()
                    }
                }
            }

            RendererLifecycleOutcome.NoOp, RendererLifecycleOutcome.EmptyResourceResult -> Unit
        }
    }

    override fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget {
        requireContextThread(PipelineStage.RENDER_TARGET)
        val outcome = driver.run(RendererLifecycleOperation.MintRenderTarget(framebufferName)) { null }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
        return RenGRenderTarget(this, framebufferName, driver.snapshot.contextGeneration)
    }

    // ---- Drawing ----------------------------------------------------------------------------------

    override fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget) {
        requireContextThread(PipelineStage.DRAW)
        val frameFact = when {
            preparedFrame !is RenGPreparedFrame || preparedFrame.owner !== this -> PreparedFrameFact.Foreign
            preparedFrame.closed -> PreparedFrameFact.OwnedClosed
            else -> PreparedFrameFact.OwnedOpen
        }
        val targetFact = when {
            renderTarget !is RenGRenderTarget || renderTarget.owner !== this -> RenderTargetFact.Foreign
            renderTarget.mintedAtGeneration != driver.snapshot.contextGeneration -> RenderTargetFact.Stale
            else -> RenderTargetFact.OwnedCurrent(renderTarget.framebufferName)
        }

        val outcome = driver.run(RendererLifecycleOperation.Draw(frameFact, targetFact)) { operation ->
            val drawOperation = operation as? RendererLifecycleOperation.Draw ?: unexpectedOperation(operation)
            val ownedTarget = drawOperation.target as RenderTargetFact.OwnedCurrent
            performDraw(preparedFrame as RenGPreparedFrame, ownedTarget.framebufferName)
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }

    /**
     * Runs as the `Draw` operation's permitted-operation executor, which only ever runs once the
     * state machine has already proved the exact context is current and the target framebuffer is
     * complete. Everything here is therefore real GL work: uploading each sticker's decoded image,
     * compiling (or reusing) each distinct geometry program, and drawing the assembled [Scene]
     * through [drawFrame].
     */
    private fun performDraw(frame: RenGPreparedFrame, framebufferName: FramebufferName): FailureDescriptor? {
        if (frame.drawBasemap && configuration.basemapStyle == null && !basemapWarningEmitted) {
            basemapWarningEmitted = true
            configuration.diagnosticSink.emit(basemapNotConfiguredDiagnostic())
        }

        val profile = requireNotNull(driver.profile) { "drawing requires an adopted profile" }
        val surface = requireNotNull(offscreenSurface) { "drawing requires an offscreen surface" }
        val composite = requireNotNull(compositePipeline) { "drawing requires the composite pipeline" }
        val sticker = requireNotNull(stickerPipeline) { "drawing requires the sticker pipeline" }
        val ground = requireNotNull(groundPipeline) { "drawing requires the ground pipeline" }
        val label = requireNotNull(labelPipeline) { "drawing requires the label pipeline" }
        val icon = requireNotNull(iconPipeline) { "drawing requires the icon pipeline" }

        val resolvedCamera = resolveFrameCamera(frame.camera, frame.projectionMode)

        // Every ground texture lease this draw takes is released in the `finally` below -- on the
        // failure returns inside, and on a throw out of drawFrame. An unreleased lease is permanently
        // exempt from eviction (`GlObjectRegistry.evictOverBudget` iterates only unleased keys), so a
        // leaked one is a texture the byte budget can never reclaim for the renderer's whole life.
        val textureLeases = ArrayList<TextureLease>(frame.basemapTiles.size + 1)
        var unrelievedResidency: GpuTextureResidency? = null
        val failure = try {
            // The captured region opens HERE, not inside drawFrame (ADR 0054). `resolveGroundTiles`
            // uploads every ground tile that is not already resident, and an upload ends by binding
            // its new texture without putting back what was there — on whatever unit the host left
            // active. Capturing after that recorded RenG's own texture as if it were the host's and
            // faithfully restored it, losing the host's binding: measured at four of a frame's
            // twenty-four texture binds landing before the capture began.
            //
            // `drawFrame` still opens a region of its own for every other caller, and finds this one
            // already open and joins it.
            withCapturedGlState(binding, profile) { captured ->
                when (val resolved = resolveGroundTiles(frame, textureLeases, captured)) {
                    is GroundTilesResult.Failed -> resolved.failure
                    is GroundTilesResult.Resolved -> drawResolvedFrame(
                        frame = frame,
                        framebufferName = framebufferName,
                        profile = profile,
                        surface = surface,
                        composite = composite,
                        sticker = sticker,
                        ground = ground,
                        label = label,
                        icon = icon,
                        resolvedCamera = resolvedCamera,
                        sceneGroundTiles = resolved.tiles,
                        textureLeases = textureLeases,
                        binding = captured,
                    )
                }
            }
        } finally {
            unrelievedResidency = releaseTextureLeases(textureLeases)
        }
        // Only on a frame that actually drew. A failing draw reports its own typed failure, and
        // reaching into the consumer's sink while that failure is on its way out would add noise to
        // the one moment they are least able to use it -- and, from the `finally` above, could
        // replace the failure entirely with whatever a throwing sink raises.
        if (failure == null) {
            unrelievedResidency?.let { residency ->
                configuration.diagnosticSink.emit(
                    residentGpuTexturesOverBudgetDiagnostic(
                        residentBytes = residency.residentBytes,
                        budgetBytes = residency.budgetBytes,
                    ),
                )
            }
        }
        return failure
    }

    /**
     * Releases every texture lease this draw took -- its ground tiles and, since E-labels, its glyph
     * atlas -- and reports the worst residency that eviction could not bring under budget, or `null` if
     * it always could.
     *
     * **One reading per frame, not one per texture.** Each release runs an eviction pass, so a frame
     * 39 tiles past the budget reaches the "out of unleased candidates, still over budget" exit 39
     * times. Those are 39 observations of one condition, and the consumer needs the condition, not the
     * arithmetic: this collapses them to the single worst reading, and `performDraw` emits at most one
     * diagnostic from it. The maximum rather than the first, though the two coincide today -- within
     * one draw nothing registers a texture after the release loop begins, so the resident total only
     * falls -- because "the worst it got" is what the reading claims to be, and that should not
     * quietly depend on an ordering property of a loop somewhere else.
     */
    private fun releaseTextureLeases(leases: List<TextureLease>): GpuTextureResidency? {
        var worst: GpuTextureResidency? = null
        for (lease in leases) {
            val residency = glObjectRegistry.releaseLease(lease, binding)
            if (!residency.overBudget) continue
            if (worst == null || residency.residentBytes > worst.residentBytes) worst = residency
        }
        return worst
    }

    /**
     * The rest of one draw, once its ground textures are in hand: upload each sticker's image, compile
     * or reuse each distinct geometry program, and hand the assembled [Scene] to [drawFrame].
     *
     * Split out of [performDraw] only so that the ground leases [performDraw] holds are released by one
     * `finally` covering every exit from this body, including its own failure returns.
     */
    @Suppress("LongParameterList")
    private fun drawResolvedFrame(
        frame: RenGPreparedFrame,
        framebufferName: FramebufferName,
        profile: RenderContextProfile,
        surface: OffscreenSurface,
        composite: CompositePipeline,
        sticker: StickerPipeline,
        ground: GroundPipeline,
        label: LabelPipeline,
        icon: IconPipeline,
        resolvedCamera: ResolvedFrameCamera,
        sceneGroundTiles: List<SceneGroundTile>,
        textureLeases: MutableList<TextureLease>,
        /** The captured binding (ADR 0054), shadowing the renderer's own for this draw. */
        binding: GlBinding,
    ): FailureDescriptor? {
        val sceneStickers = frame.stickers.map { preparedSticker ->
            val texture = cachedTexture(preparedSticker.resourceKey) {
                uploadTexture(binding, preparedSticker.image, TextureContent.IMAGE)
            }
            SceneSticker(
                placement = preparedSticker.placement,
                texture = texture,
                imageWidthPixels = preparedSticker.image.width,
                imageHeightPixels = preparedSticker.image.height,
            )
        }

        val sceneGeometries = ArrayList<SceneGeometry>(frame.geometries.size)
        for (preparedGeometry in frame.geometries) {
            val geometry = preparedGeometry.geometry
            val key = geometryKeyDeriver.geometryProgram(geometry.shaderPair).key
            val pipeline = geometryPipelines[key] ?: when (
                val result = createGeometryPipeline(binding, profile.dialect, programs, geometry.shaderPair)
            ) {
                is GeometryPipelineResult.Created -> result.pipeline.also { geometryPipelines[key] = it }
                is GeometryPipelineResult.Failed -> return result.failure
            }
            val consumerTextures = preparedGeometry.consumerTextures.mapValues { (_, texture) ->
                cachedTexture(texture.resourceKey) { uploadTexture(binding, texture.image, TextureContent.DATA) }
            }
            sceneGeometries += SceneGeometry(
                geometry = geometry,
                pipeline = pipeline,
                consumerUniforms = preparedGeometry.uniformsSnapshot,
                consumerTextures = consumerTextures,
            )
        }

        val preparedModels = frame.models
        if (preparedModels.isNotEmpty() && modelPipelines.isEmpty()) {
            allModelShaderVariants().forEach { variant ->
                when (val result = createModelPipeline(binding, profile.dialect, programs, variant)) {
                    is ModelPipelineResult.Created -> modelPipelines[variant] = result.pipeline
                    is ModelPipelineResult.Failed -> return result.failure
                }
            }
        }
        val sceneModels = ArrayList<SceneModel>(preparedModels.size)
        for (preparedModel in preparedModels) {
            sceneModels += sceneModel(preparedModel)
        }

        // The planner's own lists are handed to the Scene whole. Two `filterIsInstance` calls used to
        // stand here, dropping every ModelAt reference so a FramePlan carrying a Model would render
        // nothing rather than fail Scene's bijection check -- correct for exactly as long as no
        // SceneModel reached the Scene, and a silent un-drawing of every model the moment one did.
        val scene = Scene(
            outputPixelSize = configuration.outputPixelSize,
            frameIndex = frame.frameIndex,
            stickers = sceneStickers,
            geometries = sceneGeometries,
            groundTiles = sceneGroundTiles,
            terrain = sceneTerrain(frame),
            models = sceneModels,
            labels = sceneLabels(frame, textureLeases),
            icons = sceneIcons(frame, textureLeases),
            mapOrder = frame.mapOrder,
            screenOrder = frame.screenOrder,
        )
        // Compiled on the first globe frame that actually has ground to draw, and never on a
        // mercator one: a `Failed` here is this draw's failure, exactly as a model pipeline's is a
        // few lines above.
        val globeGround = if (resolvedCamera is ResolvedGlobeCamera && sceneGroundTiles.isNotEmpty()) {
            globeGroundPipeline ?: when (
                val result = createGlobeGroundPipeline(
                    binding,
                    profile.dialect,
                    programs,
                    terrainShading = configuration.terrainShading,
                )
            ) {
                is GlobeGroundPipelineResult.Created -> result.pipeline.also { globeGroundPipeline = it }
                is GlobeGroundPipelineResult.Failed -> return result.failure
            }
        } else {
            null
        }

        val content = SceneContent(
            camera = resolvedCamera,
            scene = scene,
            stickerPipeline = sticker,
            groundPipeline = ground,
            modelPipelines = modelPipelines,
            labelPipeline = label,
            iconPipeline = icon,
            globeGroundPipeline = globeGround,
        )

        return drawFrame(
            binding = binding,
            profile = profile,
            surface = surface,
            composite = composite,
            targetFramebuffer = framebufferName,
            content = content,
        )
    }

    /**
     * This frame's terrain in the form the ground pass consumes, or `null` when it has none.
     *
     * **The granularity is derived here rather than in the GL layer because this is where the camera
     * is legible.** `terrainCellsPerTileSide` needs the plan's own zoom, latitude, projection mode
     * and selected LOD; `SceneContent` holds a resolved camera whose zoom has already been spent
     * into matrices. What crosses is terrain's *claim* on the granularity — `SceneContent`
     * reconciles it with the globe's curvature claim, because a frame draws its whole ground at one
     * granularity or a sliver of background shows between two tiles that disagree.
     *
     * The LOD is read off the first ground instance rather than from the plan: both tile selectors
     * emit one LOD per frame, which is the premise the whole one-granularity rule rests on, and a
     * frame with no ground instances returns `null` above before reaching here.
     */
    private fun sceneTerrain(frame: RenGPreparedFrame): SceneTerrain? {
        val terrain = frame.terrain ?: return null
        val selectedLod = frame.groundInstances.firstOrNull()?.instance?.lod ?: return null
        return SceneTerrain(
            decode = demDecodeCoefficients(terrain.encoding),
            interiorSizePx = terrain.tileSizePx,
            exaggeration = terrain.exaggeration,
            cellsPerTileSide = terrainCellsPerTileSide(
                terrain = TerrainGranularityInputs(terrain.tileSizePx),
                projectionMode = frame.projectionMode,
                zoom = frame.camera.zoom,
                latitude = frame.camera.latitude,
                selectedLod = selectedLod,
            ),
            // Built during `prepare()` and carried, never rebuilt here: a label anchor rode this
            // exact object before the frame existed, and a second construction at draw time would be
            // a second answer to "which of this frame's tiles displace".
            surface = frame.groundSurface,
        )
    }

    /**
     * ADR 0034's fourth scene list, assembled from what `prepare()` already decided.
     *
     * **One batch, because a frame's text shares one atlas.** A [LabelBatch] is per *texture* -- the
     * one thing a single `glDrawElements` cannot vary -- and every glyph a `LabelCandidateBatch` hands
     * over is packed into that batch's single atlas. So this flattens every surviving label's quads
     * into one draw, in [PreparedLabelFrame.labels] order, which is [placeLabels]' own
     * lowest-priority-first order: where two labels legitimately share pixels, the one that would have
     * won the collision is the one that paints last. Nothing here sorts, places or fades anything --
     * all three happened during `prepare()`, and ADR 0035 requires that rather than merely allowing it.
     *
     * **The atlas is resident, not re-uploaded.** [uploadGlyphAtlas] leases what is already on the GPU
     * and uploads only on a miss, exactly as a ground tile does, and the lease joins the same list
     * [performDraw]'s `finally` releases. That lease is what makes the atlas unevictable for the
     * duration of this draw: a frame cannot lose the texture it is sampling to a sibling tile's
     * pressure, and after the release it is an ordinary least-recently-used tenant of the same
     * [com.rohittp.reng.ResourceLimits.maximumResidentGpuTextureBytes] budget the tiles compete for.
     */
    private fun sceneLabels(
        frame: RenGPreparedFrame,
        textureLeases: MutableList<TextureLease>,
    ): List<LabelBatch> {
        val prepared = frame.labels ?: return emptyList()
        val atlas = uploadGlyphAtlas(binding, glObjectRegistry, prepared.atlasKey, prepared.atlas)
        textureLeases += atlas.lease
        return listOf(
            LabelBatch(
                atlasTexture = atlas.handle.name,
                quads = prepared.labels.flatMap { it.quads },
            ),
        )
    }

    /**
     * Phase 5's icon half, assembled from what `prepare()` already decided.
     *
     * **One batch, because a style declares at most one sprite pair.** An [IconBatch] is per texture
     * for [LabelBatch]'s reason, and every icon in a frame resolved against the one manifest the
     * firewall retained, so every icon samples the one atlas. The quads are handed over in
     * [PreparedLabelFrame.icons] order, which is the placement pass's own -- nothing here sorts,
     * places or fades anything.
     *
     * **The atlas is resident, not re-uploaded**, and its lease joins the same list [performDraw]'s
     * `finally` releases, exactly as the glyph atlas's does. It is enrolled in the same
     * [com.rohittp.reng.ResourceLimits.maximumResidentGpuTextureBytes] budget, so a sprite atlas, a
     * glyph atlas and the ground's tiles compete honestly rather than one of them being exempt.
     */
    private fun sceneIcons(
        frame: RenGPreparedFrame,
        textureLeases: MutableList<TextureLease>,
    ): List<IconBatch> {
        val prepared = frame.labels ?: return emptyList()
        val key = prepared.spriteAtlasKey ?: return emptyList()
        val image = requireNotNull(prepared.spriteAtlas) {
            "a prepared frame naming a sprite atlas must carry its pixels"
        }
        val atlas = uploadSpriteAtlas(binding, glObjectRegistry, key, image)
        textureLeases += atlas.lease
        return listOf(IconBatch(atlasTexture = atlas.handle.name, quads = prepared.icons))
    }

    /**
     * One [PreparedModel] with everything GL-shaped in hand: its primitives uploaded, its embedded
     * images uploaded, and its override — if it has one — uploaded too.
     *
     * **Uploaded once, by key, exactly as a sticker's image is.** A primitive is keyed by
     * `ResourceKeyDeriver.modelGeometry` and an embedded image by `.modelImage`, both namespaced by the
     * model's own GLB key, so a second draw of the same prepared frame — or a later frame over the same
     * GLB — reuses what is already on the GPU rather than uploading it again. Two draw items instancing
     * one mesh derive one key and therefore share one [UploadedPrimitive], which is what
     * [SceneModel.uploaded]'s `(meshIndex, primitiveIndex)` key means.
     *
     * **[SceneModel.imageTextures] stays index-parallel with `model.images`, including the entries an
     * override makes unreachable.** Uploading only the images some material names — or none at all when
     * an override replaces every one of them — would be cheaper and would break that contract, and the
     * draw path indexes this list by a material's own `baseColourImageIndex`. A GLB's embedded images
     * are already decoded in CPU memory by the time this runs; the residency saved is not worth a list
     * whose indices mean something different from what its type says.
     *
     * Each image takes the sampler of the first material that names it, and glTF's own default
     * otherwise. One image sampled by two textures with two different samplers therefore gets the first
     * — a real ambiguity in keying a GPU texture by image index rather than by texture index, and the
     * one `ResourceKeyDeriver.modelImage` is defined in terms of.
     *
     * The override uploads as [TextureContent.IMAGE], not `DATA`, and so do the embedded images: the
     * textured model fragment shader un-premultiplies the texel it samples, so a texture uploaded
     * without premultiplication renders every partially transparent surface too dark.
     */
    private fun sceneModel(preparedModel: PreparedModel): SceneModel {
        val decoded = preparedModel.model
        val uploaded = HashMap<Pair<Int, Int>, UploadedPrimitive>(decoded.drawItems.size)
        for (item in decoded.drawItems) {
            val meshPrimitive = item.meshIndex to item.primitiveIndex
            if (meshPrimitive in uploaded) continue
            val key = geometryKeyDeriver
                .modelGeometry(preparedModel.glbKey, item.meshIndex, item.primitiveIndex)
                .key
            uploaded[meshPrimitive] = uploadedPrimitives.getOrPut(key) {
                uploadModelPrimitive(binding, decoded.primitiveFor(item)).also { primitive ->
                    glObjectRegistry.register(key, primitiveHandles(primitive))
                }
            }
        }

        val samplersByImage = HashMap<Int, TextureSamplerState>()
        for (primitive in decoded.primitives) {
            val imageIndex = primitive.material.baseColourImageIndex ?: continue
            samplersByImage.getOrPut(imageIndex) { primitive.material.baseColourSampler }
        }
        val imageTextures = decoded.images.mapIndexed { imageIndex, image ->
            val key = geometryKeyDeriver.modelImage(preparedModel.glbKey, imageIndex).key
            cachedTexture(key) {
                uploadTexture(
                    binding = binding,
                    image = image,
                    content = TextureContent.IMAGE,
                    sampler = samplersByImage[imageIndex] ?: defaultSamplerStateFor(TextureContent.IMAGE),
                )
            }
        }

        return SceneModel(
            placement = preparedModel.placement,
            model = decoded,
            nodeTransforms = preparedModel.nodeTransforms,
            jointMatricesBySkin = preparedModel.jointMatricesBySkin,
            uploaded = uploaded,
            imageTextures = imageTextures,
            overrideTexture = preparedModel.overrideTexture?.let { override ->
                cachedTexture(override.resourceKey) {
                    uploadTexture(binding, override.image, TextureContent.IMAGE)
                }
            },
        )
    }

    private sealed interface GroundTilesResult {
        class Resolved(val tiles: List<SceneGroundTile>) : GroundTilesResult

        class Failed(val failure: FailureDescriptor) : GroundTilesResult
    }

    /**
     * One freshly uploaded ground texture and the dimensions it was uploaded at.
     *
     * The dimensions travel with the name because the two pixel forms learn them differently -- a
     * PNG only once decoded, raw pixels from the engine's own `widthPx`/`heightPx` -- and the GPU
     * byte figure the registry is charged must be the uploaded texture's, not either input's.
     */
    private class UploadedGroundTile(val name: Int, val widthPx: Int, val heightPx: Int)

    /**
     * Turns this frame's ground instances into drawable tiles, decoding and uploading only what is not
     * already on the GPU.
     *
     * **Residency, not a per-frame upload.** A tile whose GL texture is still registered is neither
     * decoded nor uploaded again -- [GlObjectRegistry.leaseResident] hands back the texture already on
     * the GPU. That is the whole point of the byte budget: panning back over ground already seen must
     * cost nothing, and a 512x512 tile costs a megabyte of decode plus a megabyte of upload every time
     * it is missed.
     *
     * **Leases, and why they are draw-scoped.** Both branches leave this loop holding a lease, and they
     * have to: a lease is what "an in-flight draw holds for its own duration" means, and a tile this
     * draw reuses is as much in use as one it just uploaded. A texture with an open lease is
     * structurally unreachable to eviction ([GlObjectRegistry] iterates only its unleased keys), so
     * nothing this frame needs can be evicted by a sibling tile part-way through the same draw --
     * "exceeding the budget because a live frame still needs a tile is the correct outcome; breaking a
     * drawable frame to honour a cache limit is not". Releasing the lease is also what marks the tile
     * most-recently-used, so the reuse branch needs no separate touch. The lease is released by
     * [performDraw]'s `finally` rather than held for the prepared frame's lifetime, because eviction
     * issues GL deletes and ADR 0015 requires the exact context to be current for those -- which is
     * guaranteed inside a `Draw` operation and guaranteed by nothing at `PreparedFrame.close()`. Between
     * draws the tile stays resident, unleased, up to the budget.
     *
     * A tile is uploaded as [TextureContent.IMAGE]: a basemap tile is an image, its alpha is a coverage
     * value, and it is filtered under every pitched camera, which is exactly what premultiplication
     * exists to make correct.
     */
    private fun resolveGroundTiles(
        frame: RenGPreparedFrame,
        groundLeases: MutableList<TextureLease>,
        /** The captured binding (ADR 0054): uploads here overwrite texture units the host owns. */
        binding: GlBinding,
    ): GroundTilesResult {
        val groundInstances = frame.groundInstances
        if (groundInstances.isEmpty()) return GroundTilesResult.Resolved(emptyList())

        val renderedByKey = frame.basemapTiles.associateBy { it.key }
        val texturesByKey = HashMap<ResourceKey, Int>(renderedByKey.size)
        val elevationByTile = resolveDemTextures(frame.terrain, groundLeases)
        val tiles = ArrayList<SceneGroundTile>(groundInstances.size)
        for (groundInstance in groundInstances) {
            val key = groundInstance.resourceKey
            val cached = texturesByKey[key]
            val elevation = elevationByTile[canonicalTileOf(groundInstance.instance)]
            if (cached != null) {
                tiles += SceneGroundTile(
                    instance = groundInstance.instance,
                    texture = cached,
                    elevation = elevation,
                )
                continue
            }
            val reused = glObjectRegistry.leaseResident(key)
            val texture = if (reused != null) {
                groundLeases += reused.lease
                reused.handle.name
            } else {
                // Not `requireNotNull` any more. Preparation now skips the engine for a tile whose
                // texture was already resident, so a ground instance names *either* a rendered tile
                // or a resident texture — and the `leaseResident` above is what distinguishes them.
                // The one path that reaches here with neither is a texture evicted between this
                // frame's preparation and its draw, which the byte budget can do to an unleased
                // entry. That is a frame this renderer cannot honour, and refusing it is right;
                // crashing the consumer for a cache decision it never made is not (ADR 0046).
                val rendered = renderedByKey[key]
                    ?: return GroundTilesResult.Failed(basemapTileEvictedFailure(key))
                // Exhaustive, with no `else`: a third pixel form must fail to compile here rather
                // than reach a driver as an unhandled tile (ADR 0044).
                val uploaded = when (val pixels = rendered.pixels) {
                    is BasemapTilePixels.Encoded -> {
                        val image = when (
                            val decoded = decodePng(
                                pixels.pngBytes,
                                configuration.resourceLimits.maximumDecodedImageBytes,
                            )
                        ) {
                            is PngDecodeResult.Success -> decoded.image
                            else -> return GroundTilesResult.Failed(basemapTileDecodeFailure(key))
                        }
                        UploadedGroundTile(
                            name = uploadTexture(binding, image, TextureContent.IMAGE),
                            widthPx = image.width,
                            heightPx = image.height,
                        )
                    }
                    // `uploadPremultipliedTexture`, never `uploadTexture`: these bytes are already
                    // premultiplied and the other entry point would do it twice.
                    is BasemapTilePixels.Raw -> UploadedGroundTile(
                        name = uploadPremultipliedTexture(
                            binding,
                            pixels.rgba,
                            pixels.widthPx,
                            pixels.heightPx,
                        ),
                        widthPx = pixels.widthPx,
                        heightPx = pixels.heightPx,
                    )
                }
                groundLeases += glObjectRegistry.registerTexture(
                    key = key,
                    handle = GlObjectHandle(GlObjectType.TEXTURE, uploaded.name),
                    byteSize = uploaded.widthPx.toLong() * uploaded.heightPx.toLong() *
                        RGBA_BYTES_PER_PIXEL,
                )
                uploaded.name
            }
            texturesByKey[key] = texture
            tiles += SceneGroundTile(
                instance = groundInstance.instance,
                texture = texture,
                elevation = elevation,
            )
        }
        return GroundTilesResult.Resolved(tiles)
    }

    /**
     * Decodes, pads and uploads this frame's DEMs, and answers which padded texture and which window
     * each **canonical** ground tile samples.
     *
     * **It decides nothing about coverage.** Every tile it pads is one
     * [PreparedTerrain.elevatedTiles] already named, which is what ADR 0041's diagnostic was counted
     * over during `prepare()`; a tile whose texels disagreed with the descriptor's `tileSizePx`,
     * whose source is not its request's ancestor, or whose source never arrived was excluded there
     * and reported there. A ground tile with no entry here draws flat beside its displaced
     * neighbours, and it has already been counted. Nothing here throws and nothing here retries
     * (ADR 0041).
     *
     * **The texture is the *source* tile's, and the window is what makes several requests share it.**
     * Under overzoom `sampleFor` answers many requested tiles from one DEM image, so the padded
     * texture is assembled once per source tile, keyed by content, and `demTileWindowFor` gives each
     * requested tile its own sub-rectangle of it. The ring the acquisition paid for is what
     * guarantees the source tile's own eight neighbours are present to be padded with.
     *
     * **The texels arrive already decoded** (Rentile `0.7.0`), so this function's name is now half a
     * lie kept for the two steps it still performs: RenG decodes no DEM anywhere, and the per-frame
     * inflate of every tile in the request -- ring included, resident or not -- is gone with it.
     *
     * **The key is [PaddedDemTexture.contentKey], never the centre tile's digest**, which is the one
     * trap in this function and is invisible when got wrong: a centre-keyed texture hits residency in
     * the frame after an absent neighbour arrives and serves the replicated ring to a frame that
     * could have closed the seam. `ResourceKeyDeriver.paddedDemTexture` takes the composed key by
     * signature so the correct input is the only one that type-checks.
     *
     * Every upload leaves a lease in [groundLeases], released by [performDraw]'s `finally` with the
     * ground textures': a DEM competes for `maximumResidentGpuTextureBytes` on exactly the terms a
     * tile does, and an unreleased lease is a texture the budget could never reclaim.
     */
    private fun resolveDemTextures(
        terrain: PreparedTerrain?,
        groundLeases: MutableList<TextureLease>,
    ): Map<CanonicalBasemapTile, SceneTileDem> {
        if (terrain == null) return emptyMap()
        val elevated = terrain.elevatedTiles
        if (elevated.isEmpty()) return emptyMap()

        val texelsBySource = terrain.texelsBySource
        val texturesBySource = HashMap<DemTileCoordinate, Int>(texelsBySource.size)
        val resolved = LinkedHashMap<CanonicalBasemapTile, SceneTileDem>(elevated.size)
        elevated.forEach { tile ->
            val dem = terrain.demTileFor(tile) ?: return@forEach
            val window = demTileWindowFor(dem.requestedTile, dem.sourceTile) ?: return@forEach
            val texture = texturesBySource.getOrElse(dem.sourceTile) {
                val padded = padDemTexture(dem.sourceTile, texelsBySource) ?: return@forEach
                val key = geometryKeyDeriver.paddedDemTexture(padded.contentKey).key
                val leased = uploadDemTexture(binding, glObjectRegistry, key, padded)
                groundLeases += leased.lease
                leased.handle.name.also { texturesBySource[dem.sourceTile] = it }
            }
            resolved[tile] = SceneTileDem(demTexture = texture, window = window)
        }
        return resolved
    }

    private fun canonicalTileOf(instance: BasemapTileInstance): CanonicalBasemapTile =
        CanonicalBasemapTile(lod = instance.lod, tileY = instance.tileY, canonicalX = instance.canonicalX)

    /**
     * Task 9b item 1: the texture-lifetime fix. Looks [key] up in [glObjectRegistry] first — the
     * SAME registry [driver]'s own `forgetWithoutDeleting()` clears on context loss and on
     * [notifyGpuObjectsGone] (ADR 0007/0015: forgotten, not deleted, since the GL handles are already
     * gone and there is nothing valid left to delete) — and only calls [upload] on a genuine cache
     * miss, registering the freshly uploaded name under [key] so the NEXT draw of the same
     * [ResourceKey] (an unchanged sticker image or geometry consumer texture) reuses it instead of
     * calling [uploadTexture] (and therefore `genTextures`) again. [close] deletes every handle this
     * ever registers; nothing here calls a GL delete directly, mirroring how [geometryPipelines] is
     * cached by [ResourceKey] and deleted only in [close] / forgotten only in [notifyGpuObjectsGone].
     */
    private fun cachedTexture(key: ResourceKey, upload: () -> Int): Int {
        val existing = glObjectRegistry.handlesOfType(key, GlObjectType.TEXTURE).firstOrNull()
        if (existing != null) return existing.name
        val name = upload()
        glObjectRegistry.register(key, listOf(GlObjectHandle(GlObjectType.TEXTURE, name)))
        return name
    }

    /**
     * Resolves [camera] against the fixed output pixel size a second time (the first was inside
     * `FramePlanningCore.plan()`'s own spatial-planning call, at `prepare()` time), through whichever
     * of the two resolvers [projectionMode] names — the same `when` `FramePlanningCore` runs, and the
     * reason [RenGPreparedFrame] carries a projection mode at all. This mirrors
     * [SceneContent]'s own re-resolution of `Placement`/`Geometry` at draw time and accepts the same
     * redundant-but-safe reasoning: both resolvers are pure and deterministic in their two
     * inputs, [camera] cannot have changed since `prepare()` validated it (it is `RenGPreparedFrame`'s
     * own immutable field, not the caller's live [FramePlan]), and `configuration.outputPixelSize` is
     * fixed for the renderer's whole lifetime (ADR 0012). A failure here is therefore a caller
     * contract violation rather than a legitimate runtime outcome — exactly
     * `internal.gl.requireResolvedAtDrawTime`'s own reasoning — and (Task 9b) is now reported through
     * that exact same shared function, as a typed [RenGException] (`INVALID_VALUE` at `DRAW`)
     * rather than the bare `error(...)` this used to throw directly.
     */
    private fun resolveFrameCamera(
        camera: Camera,
        projectionMode: ProjectionMode,
    ): ResolvedFrameCamera = when (projectionMode) {
        ProjectionMode.MERCATOR ->
            resolveMercatorCamera(camera, configuration.outputPixelSize).requireResolvedAtDrawTime()

        ProjectionMode.GLOBE ->
            resolveGlobeCamera(camera, configuration.outputPixelSize).requireResolvedAtDrawTime()
    }

    // ---- Prepared-frame lifecycle -----------------------------------------------------------------

    /**
     * [RenGPreparedFrame.takeLeases] hands the frame's leases over once, so a second `close()` releases
     * nothing -- which is what keeps this idempotent, as `CONTEXT.md` requires, given that a [Lease]
     * rejects a double release outright. Releasing after `markClosed()` keeps the order honest: the frame
     * stops being drawable before its content stops being pinned.
     */
    internal fun closePreparedFrame(frame: RenGPreparedFrame) {
        val fact = if (frame.closed) PreparedFrameFact.OwnedClosed else PreparedFrameFact.OwnedOpen
        val outcome = driver.run(RendererLifecycleOperation.ClosePreparedFrame(fact)) { operation ->
            if (operation is RendererLifecycleOperation.ClosePreparedFrame) {
                // Inside the transition, and only on the open-to-closed one: `close()` is documented
                // idempotent, so a second call arrives with `fact` already `OwnedClosed` and must not
                // subtract this frame's raw bytes a second time (ADR 0044).
                if (fact == PreparedFrameFact.OwnedOpen) {
                    outstandingRawTileBytes -= frame.rawTileBytes
                }
                frame.markClosed()
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
        releaseLeases(frame.takeLeases())
    }

    // ---- Close --------------------------------------------------------------------------------

    override fun close() {
        // Guarded only while there is something to delete. ADR 0015: "After declared loss there is
        // nothing to delete, so close is context-free" -- and a close that is context-free is
        // thread-free for the same reason, since the thread only ever stood in for the context. A
        // close with live objects still deletes them, which is the operation that rule was written
        // for, so that one is guarded.
        if (driver.snapshot.gpuLedger.hasLiveGpuObjects) requireContextThread(PipelineStage.RENDERER_CLOSE)
        val outcome = driver.run(RendererLifecycleOperation.CloseRenderer) { operation ->
            if (operation == RendererLifecycleOperation.CloseRenderer) {
                offscreenSurface?.let { deleteOffscreenSurface(binding, it) }
                compositePipeline?.let { deleteCompositePipeline(binding, programs, it) }
                stickerPipeline?.let { deleteStickerPipeline(binding, programs, it) }
                groundPipeline?.let { deleteGroundPipeline(binding, programs, it) }
                // Deleted here on `geometryPipelines`' terms rather than the registry's: the pipeline
                // owns its program and every cached grid's vertex array and buffers directly, none of
                // which is registered under a ResourceKey.
                globeGroundPipeline?.let { deleteGlobeGroundPipeline(binding, programs, it) }
                labelPipeline?.let { deleteLabelPipeline(binding, programs, it) }
                iconPipeline?.let { deleteIconPipeline(binding, programs, it) }
                geometryPipelines.values.forEach { deleteGeometryPipeline(binding, programs, it) }
                geometryPipelines.clear()
                // The model pipelines are deleted here and the uploaded primitives are not, and the
                // asymmetry is deliberate: `deleteModelPipeline` owns a joint uniform buffer that is
                // registered nowhere, whereas every vertex array and buffer an uploaded primitive holds
                // IS registered under its own `modelGeometry` key, so the registry sweep below deletes
                // it exactly once. Calling `deleteUploadedPrimitive` here as well would delete each of
                // them twice.
                modelPipelines.values.forEach { deleteModelPipeline(binding, programs, it) }
                modelPipelines.clear()
                uploadedPrimitives.clear()
                // Task 9b item 1: every sticker/geometry-consumer texture cachedTexture() has ever
                // registered gets deleted here, exactly once, on close -- the same ADR 0007/0015
                // "close() deletes" half geometryPipelines already establishes above. The registry's
                // own `forgetEverything()` still runs afterwards (GlLifecycleDriver.applyTerminal,
                // once this operation succeeds and the machine reaches CLOSED), which is harmless
                // here since every handle is already gone by then.
                glObjectRegistry.liveKeys().forEach { key -> deleteGlObjects(binding, glObjectRegistry.handles(key)) }
                offscreenSurface = null
                compositePipeline = null
                stickerPipeline = null
                groundPipeline = null
                labelPipeline = null
                iconPipeline = null
                globeGroundPipeline = null
                residentCache.closeAll()
                // CONTEXT.md's Close entry says a close "releases CPU state", and these two are exactly
                // that and nothing more: an immutable Rentile batch and one decoded atlas, no GL handle
                // between them, so unlike everything above they need no context and can never fail. They
                // are dropped here rather than in `clearFrameHistory()` because they are caches -- see
                // [labelHandover] for why that distinction is the whole decision.
                retainedLabelHandover = null
                retainedGlyphAtlas = null
                retainedSpriteAtlas = null
                // The renderer owns exactly one Rentile engine (ADR 0016), so closing the renderer closes
                // it. Its close() is idempotent and, unlike everything above it here, not GL-scoped -- so
                // it neither needs nor consults the exact current context (ADRs 0007/0015).
                basemapEngineHost.close()
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }
}

/** RGBA8, the one decoded form Cycle C produces and the one GL upload format RenG uses. */
private const val RGBA_BYTES_PER_PIXEL: Long = 4L

/**
 * Every GL object one [UploadedPrimitive] holds, as registry handles: its vertex array, one buffer per
 * attribute the primitive actually carries, and its index buffer.
 *
 * The index buffer is listed separately and deliberately, for the reason `deleteUploadedPrimitive`
 * gives: deleting a vertex array frees the vertex array object alone and never the buffers it recorded
 * bindings to, so an index buffer left off this list is a permanent leak with no GL error to find it by.
 */
private fun primitiveHandles(primitive: UploadedPrimitive): List<GlObjectHandle> =
    buildList(primitive.buffers.size + 2) {
        add(GlObjectHandle(GlObjectType.VERTEX_ARRAY, primitive.vertexArray))
        primitive.buffers.forEach { add(GlObjectHandle(GlObjectType.BUFFER, it)) }
        add(GlObjectHandle(GlObjectType.BUFFER, primitive.indexBuffer))
    }

/**
 * A GLB whose bytes are not the glTF 2.0 binary RenG can draw, named by the model it is about.
 *
 * Reachable for two disjoint reasons. `PARSE_GLB` and `VALIDATE_GLB_FEATURES` already refused a
 * container or structural fault during acquisition, so what survives to `decodeModel` and still reports
 * [ModelDecodeResult.Malformed] is precisely the class of fault those gates cannot see — `parseGltf`
 * receives the BIN chunk's *length* and never its bytes, so an accessor overrunning the chunk actually
 * delivered, an index naming no vertex, or an attribute disagreeing with `POSITION`'s count all arrive
 * here. Unreadable animation data and an unposeable rig report the same way, and for the same reason:
 * both are only knowable from those same bytes.
 */
private fun modelParseFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.RESOURCE_PARSE_FAILED,
    stage = PipelineStage.RESOURCE_PARSING,
    diagnostics = listOf(
        failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_PARSING,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = key,
        ),
    ),
)

/**
 * A well-formed GLB asking for something outside ADR 0021's supported subset. Distinct from
 * [modelParseFailure] because the two send a consumer to two different fixes — re-export the asset
 * against RenG's subset, rather than repair a broken file.
 */
private fun modelUnsupportedFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
    stage = PipelineStage.RESOURCE_PARSING,
    diagnostics = listOf(
        failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_PARSING,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = key,
        ),
    ),
)

/**
 * A drawable GLB whose expanded CPU form does not fit `ResourceLimits.maximumDecodedImageBytes`.
 * `RESOURCE_DECODE_FAILED` at `RESOURCE_DECODING`, the same code at the same stage a sticker PNG
 * overrunning that identical budget already reports — the fault is in the caller's own limit or in the
 * asset's size, and reporting it as a parse failure would send them to look at the file's structure.
 */
private fun modelDecodeBudgetFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.RESOURCE_DECODE_FAILED,
    stage = PipelineStage.RESOURCE_DECODING,
    diagnostics = listOf(
        failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_DECODING,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = key,
        ),
    ),
)

/**
 * `CONTEXT.md`'s two selector rules, reported as one code with its own field: "Preparation rejects a
 * missing or out-of-range selector, or different selectors that resolve to the same animation."
 *
 * `fieldName = animationSelector` rather than `resource` because the fault is in the [FramePlan]'s own
 * [AnimationSelector] and not in the GLB — the model is perfectly good, and it is named here only so a
 * consumer with several models knows which one's catalogue the selector missed. The field is
 * allowlisted at [PipelineStage.RESOURCE_PARSING] and nowhere earlier, which is the allowlist recording
 * an ordering fact: nothing can resolve a selector before the catalogue it resolves against exists.
 */
private fun animationSelectorFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.RESOURCE_PARSE_FAILED,
    stage = PipelineStage.RESOURCE_PARSING,
    diagnostics = listOf(
        failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_PARSING,
            fieldName = DiagnosticField.ANIMATION_SELECTOR,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = key,
        ),
    ),
)

/**
 * A rendered basemap tile that will not decode, named by the tile it is about.
 *
 * Reported at [PipelineStage.DRAW] rather than at `RESOURCE_DECODING` because that is genuinely where
 * it happens -- see [RenGRenderer.resolveGroundTiles] for why the decode is deferred to the draw -- and
 * with `RESOURCE_DECODE_FAILED` rather than `GPU_OPERATION_FAILED` because the fault is in the bytes or
 * in `ResourceLimits.maximumDecodedImageBytes`, not in the caller's GL state.
 */
/**
 * A ground tile that was resident when the frame was prepared and gone by the time it was drawn.
 *
 * Distinct from a decode failure on purpose: nothing failed to decode, and reporting it as a decode
 * would send whoever reads the diagnostic looking at the bytes, which are fine and simply gone.
 * `RESOURCE_UNAVAILABLE` is the existing code that says what is true: the resource this frame needs is
 * not there. No new enum value, so the published ABI is untouched — a capacity condition pointing at
 * `maximumResidentGpuTextureBytes` does not warrant widening a public enum.
 */
private fun basemapTileEvictedFailure(key: ResourceKey): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.RESOURCE_UNAVAILABLE,
    stage = PipelineStage.DRAW,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.DRAW,
        fieldName = DiagnosticField.RESOURCE,
        resourceKey = key,
    ),
)

private fun basemapTileDecodeFailure(key: ResourceKey): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.RESOURCE_DECODE_FAILED,
    stage = PipelineStage.DRAW,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.DRAW,
        fieldName = DiagnosticField.RESOURCE,
        resourceKey = key,
    ),
)

private fun emptyResourceReport(): ResourceReport = ResourceReport(
    entries = emptyList(),
    totals = ResourceUsage(rawBytes = 0L, decodedCpuBytes = 0L, knownGpuBytes = 0L, hasUnknownGpuBytes = false),
    // Zeroes throughout, including the cumulative eviction counters. This is the same "not
    // answering questions" the empty entry list already means, not a claim that nothing was ever
    // evicted -- ADR 0048.
    cpuResidency = ResourceResidency(
        residentBytes = 0L,
        budgetBytes = 0L,
        evictedKeyCount = 0L,
        evictedBytes = 0L,
    ),
)

private fun unexpectedOperation(operation: RendererLifecycleOperation): Nothing =
    error("RenGRenderer's executor received an operation its caller did not request: $operation")
