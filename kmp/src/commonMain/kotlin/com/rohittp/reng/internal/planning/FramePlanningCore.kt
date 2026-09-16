package com.rohittp.reng.internal.planning

import com.rohittp.reng.Backdrop
import com.rohittp.reng.FramePlan
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.backdropForCore
import com.rohittp.reng.geometriesForCore
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.diff.FrameStructuralDiff
import com.rohittp.reng.internal.diff.FrameStructuralDiffer
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.HashedCanonicalBytes
import com.rohittp.reng.internal.identity.IdentityRegistration
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.modelsForCore
import com.rohittp.reng.stickersForCore

internal data class FramePlanningRequest(
    val plan: FramePlan,
    val outputPixelSize: OutputPixelSize,
    val basemapStyle: ResourceLocator?,
    val resourceLimits: ResourceLimits,
    val maximumBasemapTileInstances: Int,
    val previousPlan: EncodedFramePlan?,
    val previousSelectedLod: Int?,
) {
    init {
        require(maximumBasemapTileInstances > 0) { "maximumBasemapTileInstances must be positive" }
        require(previousSelectedLod == null || previousSelectedLod in MINIMUM_MERCATOR_LOD..MAXIMUM_MERCATOR_LOD) {
            "previousSelectedLod must be within the Mercator LOD range"
        }
    }
}

internal sealed interface StaticResourceReference {
    val resourceKey: ResourceKey
    val rawKey: RawResourceKey?
    val canonicalIdentity: HashedCanonicalBytes

    data class External(
        val resourceClass: ResourceClass,
        val locator: ResourceLocator,
        val maximumResponseBytes: Long,
        override val resourceKey: ResourceKey,
        override val rawKey: RawResourceKey,
        val privateRentileKey: RentilePrivateKey,
        override val canonicalIdentity: HashedCanonicalBytes,
    ) : StaticResourceReference {
        init {
            require(maximumResponseBytes > 0L) { "maximum response bytes must be positive" }
            require(resourceClass.isStaticDirect) {
                "external references require a static direct resource class"
            }
            require(resourceKey.kind == ResourceKind.EXTERNAL) {
                "external references require an external resource key"
            }
            require(resourceKey.resourceClass == resourceClass && rawKey.resourceClass == resourceClass) {
                "external reference keys must carry the reference resource class"
            }
            require(rawKey.stableId == resourceKey.stableId) {
                "external raw and resource keys must share their stable id"
            }
            require(resourceKey.stableId == canonicalIdentity.digest.lowercaseHex) {
                "external stable id must be its canonical identity digest"
            }
        }
    }

    data class GeometryProgram(
        val shaderPair: ShaderPair,
        override val resourceKey: ResourceKey,
        override val canonicalIdentity: HashedCanonicalBytes,
    ) : StaticResourceReference {
        override val rawKey: RawResourceKey? = null

        init {
            require(resourceKey.kind == ResourceKind.GEOMETRY_PROGRAM) {
                "geometry program references require a geometry program resource key"
            }
            require(resourceKey.stableId == canonicalIdentity.digest.lowercaseHex) {
                "geometry program stable id must be its canonical identity digest"
            }
        }
    }
}

internal class PlannedFrameCore(
    val encodedPlan: EncodedFramePlan,
    val structuralDiff: FrameStructuralDiff,
    val spatialPlan: FrameSpatialPlan,
    staticResourceTraversal: List<StaticResourceReference>,
) {
    private val staticResourceTraversalSnapshot: List<StaticResourceReference> =
        freshListCopy(staticResourceTraversal)

    init {
        val plannedGeometries = spatialPlan.geometries
        val geometryPrograms =
            staticResourceTraversalSnapshot.filterIsInstance<StaticResourceReference.GeometryProgram>()
        require(geometryPrograms.size == plannedGeometries.size) {
            "geometry program traversal entries and planned geometries must have equal size"
        }
        for (index in geometryPrograms.indices) {
            require(geometryPrograms[index].shaderPair == plannedGeometries[index].shaderPair) {
                "geometry program traversal entries and planned geometries must correspond by index"
            }
        }
        val basemapStyleRoutes = staticResourceTraversalSnapshot.count { reference ->
            reference is StaticResourceReference.External &&
                reference.resourceClass == ResourceClass.BASEMAP_STYLE
        }
        require(basemapStyleRoutes <= 1) {
            "at most one basemap style may be traversed"
        }
        require(basemapStyleRoutes == 0 || spatialPlan.tileSelection != null) {
            "a traversed basemap style requires a planned tile selection"
        }
    }

    val staticResourceTraversal: List<StaticResourceReference>
        get() = freshListCopy(staticResourceTraversalSnapshot)

    override fun equals(other: Any?): Boolean =
        other is PlannedFrameCore &&
            encodedPlan == other.encodedPlan &&
            structuralDiff == other.structuralDiff &&
            spatialPlan == other.spatialPlan &&
            staticResourceTraversalSnapshot == other.staticResourceTraversalSnapshot

    override fun hashCode(): Int {
        var result = encodedPlan.hashCode()
        result = 31 * result + structuralDiff.hashCode()
        result = 31 * result + spatialPlan.hashCode()
        result = 31 * result + staticResourceTraversalSnapshot.hashCode()
        return result
    }
}

internal sealed interface FramePlanningOutcome {
    data class Success(val planned: PlannedFrameCore) : FramePlanningOutcome

    data class Failure(val failure: FailureDescriptor) : FramePlanningOutcome
}

internal class FramePlanningCore(
    private val frameEncoder: FramePlanCanonicalEncoder,
    private val frameIdentityRegistry: CanonicalIdentityRegistry,
    private val resourceKeyDeriver: ResourceKeyDeriver,
    private val rentilePrivateKeyResolver: RentilePrivateKeyResolver,
) {
    internal fun plan(request: FramePlanningRequest): FramePlanningOutcome {
        val plan = request.plan
        // Cycle G task 10: this `when` used to be `GLOBE -> return unsupportedProjectionModeFailure()`.
        // It is spelled as an exhaustive dispatch to two sibling planners rather than as one planner
        // taking a mode, because the globe's footprint and tile selection are not the Mercator ones
        // reparameterised -- see `planGlobeSpatial` and `selectGlobeTiles` for what actually differs.
        val spatialPlanner = when (plan.projectionMode) {
            ProjectionMode.GLOBE -> ::planGlobeSpatial
            ProjectionMode.MERCATOR -> ::planMercatorSpatial
        }
        val spatialOutcome = spatialPlanner(
            plan,
            request.outputPixelSize,
            request.previousSelectedLod,
            request.maximumBasemapTileInstances,
            request.basemapStyle != null,
        )
        if (spatialOutcome is SpatialOutcome.Failure) {
            return FramePlanningOutcome.Failure(spatialOutcome.failure)
        }
        val spatialPlan = (spatialOutcome as SpatialOutcome.Success).value

        val encodedPlan = frameEncoder.encode(plan)
        val structuralDiff = FrameStructuralDiffer.diff(request.previousPlan, encodedPlan)
        if (frameIdentityRegistry.register(encodedPlan.identity) is IdentityRegistration.Collision) {
            return frameIdentityCollisionFailure()
        }

        return FramePlanningOutcome.Success(
            PlannedFrameCore(
                encodedPlan = encodedPlan,
                structuralDiff = structuralDiff,
                spatialPlan = spatialPlan,
                staticResourceTraversal = staticResourceTraversal(request),
            ),
        )
    }

    private fun staticResourceTraversal(
        request: FramePlanningRequest,
    ): List<StaticResourceReference> {
        val plan = request.plan
        val references = ArrayList<StaticResourceReference>()
        val privateKeysByRoute = HashMap<Pair<ResourceLocator, ResourceClass>, RentilePrivateKey>()

        fun external(locator: ResourceLocator, resourceClass: ResourceClass) {
            val derived = resourceKeyDeriver.external(resourceClass, locator)
            val privateRentileKey = privateKeysByRoute.getOrPut(locator to resourceClass) {
                rentilePrivateKeyResolver.resolve(locator, resourceClass)
            }
            references += StaticResourceReference.External(
                resourceClass = resourceClass,
                locator = locator,
                maximumResponseBytes = request.resourceLimits.maximumBytesFor(resourceClass),
                resourceKey = derived.key,
                rawKey = requireNotNull(derived.rawKey),
                privateRentileKey = privateRentileKey,
                canonicalIdentity = derived.identity,
            )
        }

        val basemapStyle = request.basemapStyle
        // E-labels task 8b: labels come *from* the style, so the style is traversed when either draw
        // switch is set, not when `drawBasemap` alone is. The condition is deliberately the same
        // expression `planMercatorSpatial` gates its tile selection on: [PlannedFrameCore]'s own
        // "a traversed basemap style requires a planned tile selection" invariant fails closed if the
        // two ever drift apart, so widening one without the other is caught rather than shipped.
        if ((plan.drawBasemap || plan.drawLabels) && basemapStyle != null) {
            external(basemapStyle, ResourceClass.BASEMAP_STYLE)
        }
        for (sticker in plan.stickersForCore()) {
            external(sticker.image, ResourceClass.STICKER_IMAGE)
        }
        // ADR 0068. Its own class rather than STICKER_IMAGE: RenGRenderer pairs that class's
        // references with plan.stickers by index, and a backdrop among them is an off-by-one in
        // every sticker's texture.
        when (val backdrop = plan.backdropForCore()) {
            null -> Unit
            is Backdrop.Pattern -> external(backdrop.image, ResourceClass.BACKDROP_IMAGE)
            // ADR 0071. Its textures traverse, its program does not, and the asymmetry is the same
            // by-index trap ADR 0068 avoided one list over: `geometryPrograms` is required to have
            // exactly one entry per planned geometry, so a backdrop's program among them is an
            // off-by-one in every geometry's program. The backdrop compiles its own from the
            // ShaderPair it already carries.
            //
            // MODEL_TEXTURE rather than BACKDROP_IMAGE, because that class is the wrapping
            // GL_REPEAT pattern sampler and these are ordinary clamped consumer textures. Safe to
            // share, unlike the program list: `externalImageReference` re-derives a MODEL_TEXTURE
            // key from the locator rather than reading the traversal back by position.
            is Backdrop.Shader ->
                for ((_, locator) in backdrop.textures.entries.sortedBy { it.key }) {
                    external(locator, ResourceClass.MODEL_TEXTURE)
                }
        }
        for (model in plan.modelsForCore()) {
            external(model.glb, ResourceClass.MODEL_GLB)
            model.texture?.let { texture -> external(texture, ResourceClass.MODEL_TEXTURE) }
        }
        for (geometry in plan.geometriesForCore()) {
            val derived = resourceKeyDeriver.geometryProgram(geometry.shaderPair)
            references += StaticResourceReference.GeometryProgram(
                shaderPair = geometry.shaderPair,
                resourceKey = derived.key,
                canonicalIdentity = derived.identity,
            )
            // Cycle F-1 Task 9b: a Geometry's consumer textures are external, static-direct
            // resources exactly like a sticker's image or a model's texture override, so they must
            // be traversed here too -- otherwise a consumer supplying one gets silence, no fetch and
            // no error. Sorted by name for the same determinism reason RenGRenderer/drawGeometry
            // sort their own texture-unit assignment: a fixed, non-locale-sensitive iteration order
            // (plain String comparison) so the same document always traverses the same way. Reuses
            // ResourceClass.MODEL_TEXTURE rather than adding a new enum entry -- this cycle's ABI is
            // frozen, and a geometry consumer texture is exactly the same kind of resource (a
            // directly-fetched, non-basemap texture payload) MODEL_TEXTURE already names; nothing
            // about isStaticDirect or maximumBytesFor's meaning depends on which drawn thing uses it.
            for ((_, locator) in geometry.textures.entries.sortedBy { it.key }) {
                external(locator, ResourceClass.MODEL_TEXTURE)
            }
        }
        return references
    }
}

private val ResourceClass.isStaticDirect: Boolean
    get() = when (this) {
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.STICKER_IMAGE,
        ResourceClass.BACKDROP_IMAGE,
        ResourceClass.MODEL_GLB,
        ResourceClass.MODEL_TEXTURE,
        -> true
        ResourceClass.BASEMAP_TILE_JSON,
        ResourceClass.BASEMAP_VECTOR_TILE,
        ResourceClass.BASEMAP_RASTER_TILE,
        ResourceClass.BASEMAP_DEM_TILE,
        ResourceClass.BASEMAP_SPRITE_JSON,
        ResourceClass.BASEMAP_SPRITE_IMAGE,
        ResourceClass.BASEMAP_GEO_JSON,
        ResourceClass.BASEMAP_GLYPH_RANGE,
        -> false
    }

private fun frameIdentityCollisionFailure(): FramePlanningOutcome.Failure = FramePlanningOutcome.Failure(
    FailureDescriptor(
        code = RenGErrorCode.IDENTITY_COLLISION,
        stage = PipelineStage.FRAME_PLANNING,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.FRAME_IDENTITY,
        ),
    ),
)

private const val MINIMUM_MERCATOR_LOD: Int = 0
private const val MAXIMUM_MERCATOR_LOD: Int = 22
