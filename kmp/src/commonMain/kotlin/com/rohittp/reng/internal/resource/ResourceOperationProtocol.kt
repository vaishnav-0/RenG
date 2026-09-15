package com.rohittp.reng.internal.resource

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.requireUnicodeScalars
import kotlin.jvm.JvmInline

internal enum class CancellationCause {
    CALLER,
    CANCEL_PREPARATIONS,
    ADAPTER,
}

@JvmInline
internal value class CancellationId(val value: Long) {
    init {
        require(value > 0L) { "cancellation ID must be positive" }
    }
}

internal data class CancellationSelection(
    val cause: CancellationCause,
    val id: CancellationId,
)

@JvmInline
internal value class ResourceOwnerId(val value: Long) {
    init {
        require(value > 0L) { "resource owner ID must be positive" }
    }
}

@JvmInline
internal value class ResourceOccurrenceId(val value: Long) {
    init {
        require(value > 0L) { "resource occurrence ID must be positive" }
    }
}

@JvmInline
internal value class SpriteGroupId(val value: Long) {
    init {
        require(value > 0L) { "sprite group ID must be positive" }
    }
}

@JvmInline
internal value class StyleGroupId(val value: Long) {
    init {
        require(value > 0L) { "style group ID must be positive" }
    }
}

@JvmInline
internal value class ResourceActionId(val value: Long) {
    init {
        require(value > 0L) { "resource action ID must be positive" }
    }
}

internal class ResourceRouteKey(
    val accessMode: ResourceAccessMode,
    val locator: ResourceLocator,
    val resourceClass: ResourceClass,
    val maximumResponseBytes: Long,
) {
    init {
        require(maximumResponseBytes > 0L) { "maximum response bytes must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceRouteKey &&
            accessMode == other.accessMode &&
            locator == other.locator &&
            resourceClass == other.resourceClass &&
            maximumResponseBytes == other.maximumResponseBytes

    override fun hashCode(): Int {
        var result = accessMode.hashCode()
        result = 31 * result + locator.hashCode()
        result = 31 * result + resourceClass.hashCode()
        result = 31 * result + maximumResponseBytes.hashCode()
        return result
    }

    override fun toString(): String =
        "ResourceRouteKey(" +
            "accessMode=$accessMode, " +
            "resourceClass=$resourceClass, " +
            "maximumResponseBytes=$maximumResponseBytes)"
}

internal class TransportLatchKey(
    val route: ResourceRouteKey,
    val ifNoneMatch: String?,
    val ifModifiedSince: String?,
    val accept: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is TransportLatchKey &&
            route == other.route &&
            ifNoneMatch == other.ifNoneMatch &&
            ifModifiedSince == other.ifModifiedSince &&
            accept == other.accept

    override fun hashCode(): Int {
        var result = route.hashCode()
        result = 31 * result + (ifNoneMatch?.hashCode() ?: 0)
        result = 31 * result + (ifModifiedSince?.hashCode() ?: 0)
        result = 31 * result + (accept?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "TransportLatchKey(" +
            "route=$route, " +
            "ifNoneMatchPresent=${ifNoneMatch != null}, " +
            "ifModifiedSincePresent=${ifModifiedSince != null}, " +
            "acceptPresent=${accept != null})"
}

internal enum class ContentProvenance {
    RESIDENT,
    STORE,
    TRANSPORT_200,
    TRANSPORT_304_MERGED,
}

internal data class ResolvedResourceContent(
    val route: ResourceRouteKey,
    val resourceKey: ResourceKey,
    val stored: StoredRawResource,
    val provenance: ContentProvenance,
) {
    init {
        require(resourceKey.resourceClass == route.resourceClass) {
            "resolved content must match its route resource class"
        }
    }
}

internal data class LookupProgress(
    val sampleEpochMillis: Long?,
    val resident: StoredRawResource?,
    val staleBaseline: StoredRawResource?,
    val storeReadStarted: Boolean,
    val transportLatch: TransportLatchKey?,
    val selectedContent: ResolvedResourceContent?,
) {
    init {
        require(sampleEpochMillis == null || sampleEpochMillis >= 0L) {
            "freshness sample must be non-negative"
        }
        if (sampleEpochMillis == null) {
            require(
                resident == null && staleBaseline == null && !storeReadStarted &&
                    transportLatch == null && selectedContent == null,
            ) { "lookup work requires a freshness sample" }
        }
        require(staleBaseline == null || storeReadStarted) {
            "a stale baseline requires a started Store read"
        }
    }
}

internal sealed interface SuppliedCallOutcome<out T> {
    data class Success<T>(val value: T) : SuppliedCallOutcome<T>

    data object Failed : SuppliedCallOutcome<Nothing>

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedCallOutcome<Nothing> {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied call cancellation must originate from an adapter"
            }
        }
    }
}

internal sealed interface SuppliedValidationOutcome {
    data object Valid : SuppliedValidationOutcome

    data object Failed : SuppliedValidationOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedValidationOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied validation cancellation must originate from an adapter"
            }
        }
    }
}

internal sealed interface SuppliedInstallOutcome {
    data object Succeeded : SuppliedInstallOutcome

    data class Failed(val failure: FailureDescriptor) : SuppliedInstallOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedInstallOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied install cancellation must originate from an adapter"
            }
        }
    }
}

internal sealed interface LatchedTransportOutcome {
    data class Response(val response: TransportResponse) : LatchedTransportOutcome

    data object Failed : LatchedTransportOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : LatchedTransportOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "latched cancellation must originate from an adapter"
            }
        }
    }
}

internal data class TransportLatchRecord(
    val key: TransportLatchKey,
    val outcome: LatchedTransportOutcome,
)

internal data class ResourceRouteRegistration(
    val route: ResourceRouteKey,
    val resourceKey: ResourceKey,
    val rawKey: RawResourceKey,
    val privateRentileKey: RentilePrivateKey,
    val canonicalBytes: CanonicalBytes,
)

/**
 * Sprite atlas members. The declaration order is deliberately not the commit order: commit order comes
 * only from [spriteMemberRank], so an enum ordinal can never stand in for it.
 */
internal enum class SpriteMember {
    IMAGE,
    JSON,
}

internal sealed interface ResourceCommitBinding {
    data object Single : ResourceCommitBinding

    data class Sprite(
        val groupId: SpriteGroupId,
        val member: SpriteMember,
    ) : ResourceCommitBinding

    data class BasemapStyle(val groupId: StyleGroupId) : ResourceCommitBinding
}

internal data class ResourceOccurrence(
    val id: ResourceOccurrenceId,
    val ownerId: ResourceOwnerId,
    val registration: ResourceRouteRegistration,
    val discoveryRequired: Boolean,
    val commitBinding: ResourceCommitBinding,
)

internal sealed interface ResourceChildTraversal {
    data class BasemapSprite(val member: SpriteMember) : ResourceChildTraversal

    class BasemapSource(
        val sourceId: String,
        val member: BasemapSourceMember,
    ) : ResourceChildTraversal {
        init {
            requireUnicodeScalars(sourceId, "source ID", nonBlank = false)
        }

        override fun equals(other: Any?): Boolean =
            other is BasemapSource && sourceId == other.sourceId && member == other.member

        override fun hashCode(): Int = 31 * sourceId.hashCode() + member.hashCode()

        override fun toString(): String =
            when (member) {
                BasemapSourceMember.Metadata -> "BasemapSource(member=Metadata)"
                is BasemapSourceMember.Tile -> "BasemapSource(member=Tile)"
            }
    }

    data class DeclaredArray(val index: Int) : ResourceChildTraversal {
        init {
            require(index >= 0) { "declared array index must be non-negative" }
        }
    }

    class ObjectMember(
        val exactKey: String,
    ) : ResourceChildTraversal {
        init {
            requireUnicodeScalars(exactKey, "object member key", nonBlank = false)
        }

        override fun equals(other: Any?): Boolean = other is ObjectMember && exactKey == other.exactKey

        override fun hashCode(): Int = exactKey.hashCode()

        override fun toString(): String = "ObjectMember"
    }
}

internal sealed interface BasemapSourceMember {
    data object Metadata : BasemapSourceMember

    data class Tile(
        val lod: Int,
        val tileY: Int,
        val canonicalX: Int,
    ) : BasemapSourceMember {
        init {
            require(lod >= 0) { "tile LOD must be non-negative" }
            require(tileY >= 0) { "tile Y must be non-negative" }
            require(canonicalX >= 0) { "canonical tile X must be non-negative" }
        }
    }
}

internal data class DiscoveredResourceChild(
    val traversal: ResourceChildTraversal,
    val occurrence: ResourceOccurrence,
)

internal class ResourceOccurrenceIdSlice private constructor(
    private val backing: List<ResourceOccurrenceId>,
    internal val startIndex: Int,
) {
    init {
        require(startIndex in 0..backing.size) { "resource occurrence slice start must be in bounds" }
    }

    internal val backingSize: Int
        get() = backing.size

    internal val isEmpty: Boolean
        get() = startIndex == backing.size

    internal fun first(): ResourceOccurrenceId {
        check(!isEmpty) { "resource occurrence slice must not be empty" }
        return backing[startIndex]
    }

    internal fun advance(): ResourceOccurrenceIdSlice {
        check(!isEmpty) { "resource occurrence slice must not be empty" }
        return ResourceOccurrenceIdSlice(backing, startIndex + 1)
    }

    internal fun freshValues(): List<ResourceOccurrenceId> =
        ArrayList(backing.subList(startIndex, backing.size))

    internal companion object {
        internal fun snapshot(values: List<ResourceOccurrenceId>): ResourceOccurrenceIdSlice =
            ResourceOccurrenceIdSlice(freshListCopy(values), 0)

        internal fun empty(): ResourceOccurrenceIdSlice = ResourceOccurrenceIdSlice(emptyList(), 0)
    }
}

internal class DiscoveryFrontier private constructor(
    val parentOccurrenceId: ResourceOccurrenceId,
    private val childOccurrenceIdSlice: ResourceOccurrenceIdSlice,
    private val withheldContinuationSlice: ResourceOccurrenceIdSlice,
) {
    internal constructor(
        parentOccurrenceId: ResourceOccurrenceId,
        childOccurrenceIds: List<ResourceOccurrenceId>,
        withheldContinuation: List<ResourceOccurrenceId>,
    ) : this(
        parentOccurrenceId = parentOccurrenceId,
        childOccurrenceIdSlice = ResourceOccurrenceIdSlice.snapshot(childOccurrenceIds),
        withheldContinuationSlice = ResourceOccurrenceIdSlice.snapshot(withheldContinuation),
    )

    val childOccurrenceIds: List<ResourceOccurrenceId>
        get() = childOccurrenceIdSlice.freshValues()

    val withheldContinuation: List<ResourceOccurrenceId>
        get() = withheldContinuationSlice.freshValues()

    internal val withheldBackingSize: Int
        get() = withheldContinuationSlice.backingSize

    internal val withheldStartIndex: Int
        get() = withheldContinuationSlice.startIndex

    internal val hasChildren: Boolean
        get() = !childOccurrenceIdSlice.isEmpty

    internal fun firstChild(): ResourceOccurrenceId = childOccurrenceIdSlice.first()

    internal fun afterFirstChild(): DiscoveryFrontier = DiscoveryFrontier(
        parentOccurrenceId = parentOccurrenceId,
        childOccurrenceIdSlice = childOccurrenceIdSlice.advance(),
        withheldContinuationSlice = withheldContinuationSlice,
    )

    internal fun withoutChildren(): DiscoveryFrontier = DiscoveryFrontier(
        parentOccurrenceId = parentOccurrenceId,
        childOccurrenceIdSlice = ResourceOccurrenceIdSlice.empty(),
        withheldContinuationSlice = withheldContinuationSlice,
    )

    internal fun withChildren(childOccurrenceIds: List<ResourceOccurrenceId>): DiscoveryFrontier =
        DiscoveryFrontier(
            parentOccurrenceId = parentOccurrenceId,
            childOccurrenceIdSlice = ResourceOccurrenceIdSlice.snapshot(childOccurrenceIds),
            withheldContinuationSlice = withheldContinuationSlice,
        )

    internal fun remainingChildrenAsWithheld(
        nestedParentOccurrenceId: ResourceOccurrenceId,
    ): DiscoveryFrontier = DiscoveryFrontier(
        parentOccurrenceId = nestedParentOccurrenceId,
        childOccurrenceIdSlice = ResourceOccurrenceIdSlice.empty(),
        withheldContinuationSlice = childOccurrenceIdSlice,
    )

    internal fun restoreWithheldInto(parent: DiscoveryFrontier): DiscoveryFrontier {
        require(!parent.hasChildren) { "frontier continuation must be singular" }
        return DiscoveryFrontier(
            parentOccurrenceId = parent.parentOccurrenceId,
            childOccurrenceIdSlice = withheldContinuationSlice,
            withheldContinuationSlice = parent.withheldContinuationSlice,
        )
    }

    internal fun withheldSlice(): ResourceOccurrenceIdSlice = withheldContinuationSlice

    internal companion object {
        internal fun unresolved(
            parentOccurrenceId: ResourceOccurrenceId,
            withheldContinuation: ResourceOccurrenceIdSlice,
        ): DiscoveryFrontier = DiscoveryFrontier(
            parentOccurrenceId = parentOccurrenceId,
            childOccurrenceIdSlice = ResourceOccurrenceIdSlice.empty(),
            withheldContinuationSlice = withheldContinuation,
        )
    }
}

internal class TraversalState private constructor(
    eligibleFifo: List<ResourceOccurrenceId>,
    private val staticContinuationSlice: ResourceOccurrenceIdSlice,
    frontierStack: List<DiscoveryFrontier>,
) {
    internal constructor(
        eligibleFifo: List<ResourceOccurrenceId>,
        staticContinuation: List<ResourceOccurrenceId>,
        frontierStack: List<DiscoveryFrontier>,
    ) : this(
        eligibleFifo = eligibleFifo,
        staticContinuationSlice = ResourceOccurrenceIdSlice.snapshot(staticContinuation),
        frontierStack = frontierStack,
    )

    private val eligibleFifoSnapshot: List<ResourceOccurrenceId> = freshListCopy(eligibleFifo)
    private val frontierStackSnapshot: List<DiscoveryFrontier> = freshListCopy(frontierStack)

    val eligibleFifo: List<ResourceOccurrenceId>
        get() = freshListCopy(eligibleFifoSnapshot)

    val staticContinuation: List<ResourceOccurrenceId>
        get() = staticContinuationSlice.freshValues()

    val frontierStack: List<DiscoveryFrontier>
        get() = freshListCopy(frontierStackSnapshot)

    internal fun staticSlice(): ResourceOccurrenceIdSlice = staticContinuationSlice

    internal companion object {
        internal fun fromSlices(
            eligibleFifo: List<ResourceOccurrenceId>,
            staticContinuation: ResourceOccurrenceIdSlice,
            frontierStack: List<DiscoveryFrontier>,
        ): TraversalState = TraversalState(
            eligibleFifo = eligibleFifo,
            staticContinuationSlice = staticContinuation,
            frontierStack = frontierStack,
        )
    }
}

internal class ResourceOperationDefinition(
    val maximumConcurrentRoutes: Int,
    staticOccurrences: List<ResourceOccurrence>,
    resourceIdentities: List<CanonicalIdentityRecord>,
) {
    private val staticOccurrenceSnapshot: List<ResourceOccurrence> = freshListCopy(staticOccurrences)
    private val resourceIdentitySnapshot: List<CanonicalIdentityRecord> = freshListCopy(resourceIdentities)

    init {
        require(maximumConcurrentRoutes > 0) { "maximum concurrent routes must be positive" }
    }

    val staticOccurrences: List<ResourceOccurrence>
        get() = freshListCopy(staticOccurrenceSnapshot)

    val resourceIdentities: List<CanonicalIdentityRecord>
        get() = freshListCopy(resourceIdentitySnapshot)
}

internal enum class ResourceRouteStatus {
    PREREGISTERED,
    ELIGIBLE,
    RUNNING,
    RESOLVED,
    BLOCKED_BY_COLLISION,
}

internal sealed interface ResourceRouteCursor

internal data class AwaitingClockSample(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingResident(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingStoreRead(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latchKey: TransportLatchKey,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingLatchedTransportReplay(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latchKey: TransportLatchKey,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class PendingClassGates(
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

/**
 * A discovery occurrence's route that has completed every class gate, any required write, and its
 * visibility install, and now awaits only its own discovery announcement. The route stays running and
 * assigned so that [RouteReadyForDiscovery] remains the single retirement path that emits
 * [DiscoverChildren].
 */
internal data class PendingChildDiscovery(
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

/**
 * The checks RenG itself performs over content its **own** driver acquired. No member exists for a check
 * the Rentile engine owns: the engine acquires all eight engine-keyed basemap classes through RenG's
 * firewall, so no TileJSON, vector tile, GeoJSON, DEM or glyph range ever reaches a class gate
 * (ADR 0003's split, ADR 0016's firewall).
 *
 * Not every check RenG owns is a member here. DEM terrain-encoding validation is RenG's under ADR 0016,
 * but it belongs to the firewall's write path rather than to a driver class gate, because the driver
 * never holds a DEM tile's bytes. "One member per check RenG owns" would be the wrong summary.
 */
internal enum class ResourceClassGate {
    DECODE_PNG,
    PARSE_GLB,
    VALIDATE_GLB_FEATURES,
}

/**
 * The ordered class gates RenG runs over a resolved route's content, or `null` for a class whose routes
 * this function does not gate at all.
 *
 * `null` is the answer for two different reasons, and both are structural rather than unfinished:
 *
 * - **The engine acquires it.** `BASEMAP_TILE_JSON`, `BASEMAP_VECTOR_TILE`, `BASEMAP_RASTER_TILE`,
 *   `BASEMAP_DEM_TILE`, `BASEMAP_GEO_JSON`, `BASEMAP_SPRITE_JSON`, `BASEMAP_SPRITE_IMAGE` and
 *   `BASEMAP_GLYPH_RANGE` are keyed to
 *   the Rentile engine, which fetches and validates them itself through RenG's firewall
 *   ([com.rohittp.reng.internal.firewall.OperationRegistry]); RenG's driver only preregisters their
 *   routes. They cannot become driver routes either: only
 *   [com.rohittp.reng.internal.planning.StaticResourceReference.External] becomes a
 *   [ResourceOccurrence], and its own `init` requires a static-direct class, which none of the eight is.
 *   Re-gating them here would also fetch and validate one logical resource twice under two different
 *   stable ids, one per key space (ADR 0016).
 * - **Its commit path is not the ordinary one.** `BASEMAP_STYLE` commits through the style-commit path
 *   rather than through [AdvancePendingClassGates].
 *
 * That leaves the three classes RenG genuinely decodes and parses for itself.
 */
internal fun ordinaryResourceClassGates(resourceClass: ResourceClass): List<ResourceClassGate>? =
    when (resourceClass) {
        ResourceClass.STICKER_IMAGE -> listOf(ResourceClassGate.DECODE_PNG)
        ResourceClass.BACKDROP_IMAGE -> listOf(ResourceClassGate.DECODE_PNG)
        ResourceClass.MODEL_TEXTURE -> listOf(ResourceClassGate.DECODE_PNG)
        ResourceClass.MODEL_GLB -> listOf(
            ResourceClassGate.PARSE_GLB,
            ResourceClassGate.VALIDATE_GLB_FEATURES,
        )
        ResourceClass.BASEMAP_TILE_JSON,
        ResourceClass.BASEMAP_VECTOR_TILE,
        ResourceClass.BASEMAP_RASTER_TILE,
        ResourceClass.BASEMAP_DEM_TILE,
        ResourceClass.BASEMAP_GEO_JSON,
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.BASEMAP_SPRITE_JSON,
        ResourceClass.BASEMAP_SPRITE_IMAGE,
        ResourceClass.BASEMAP_GLYPH_RANGE,
        -> null
    }

internal fun requiresStoreWrite(provenance: ContentProvenance): Boolean =
    when (provenance) {
        ContentProvenance.RESIDENT,
        ContentProvenance.STORE,
        -> false
        ContentProvenance.TRANSPORT_200,
        ContentProvenance.TRANSPORT_304_MERGED,
        -> true
    }

internal data class AwaitingClassGate(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
    val gateIndex: Int,
    val gate: ResourceClassGate,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        require(
            ordinaryResourceClassGates(content.route.resourceClass)?.getOrNull(gateIndex) == gate,
        ) { "a class gate cursor must name its class gate at its own gate index" }
    }
}

internal data class AwaitingStoreWrite(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingVisibilityInstall(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal enum class SpriteJointValidationStatus {
    WAITING,
    REQUESTED,
    VALID,
    FAILED,
}

internal enum class SpritePairFailureKind {
    JSON_PARSE,
    IMAGE_DECODE,
    UNSUPPORTED_FEATURE,
}

internal fun spriteMemberOrder(): List<SpriteMember> = SpriteMember.entries.sortedBy(::spriteMemberRank)

internal fun spriteMemberResourceClass(member: SpriteMember): ResourceClass =
    when (member) {
        SpriteMember.JSON -> ResourceClass.BASEMAP_SPRITE_JSON
        SpriteMember.IMAGE -> ResourceClass.BASEMAP_SPRITE_IMAGE
    }

internal enum class StyleCompilationStatus {
    NOT_REQUIRED,
    WAITING,
    REQUESTED,
    SUCCEEDED,
    FAILED,
}

internal enum class StyleFailureKind {
    PARSE,
    UNSUPPORTED_FEATURE,
}

internal fun requiresStyleCompilation(provenance: ContentProvenance): Boolean =
    when (provenance) {
        ContentProvenance.RESIDENT -> false
        ContentProvenance.STORE,
        ContentProvenance.TRANSPORT_200,
        ContentProvenance.TRANSPORT_304_MERGED,
        -> true
    }

internal sealed interface ParkedRouteBarrier {
    data class SpritePair(val groupId: SpriteGroupId) : ParkedRouteBarrier

    data class StyleChildren(val groupId: StyleGroupId) : ParkedRouteBarrier

    data class StyleOwners(val groupId: StyleGroupId) : ParkedRouteBarrier
}

internal data class ParkedRoute(
    val ordinal: Long,
    val barrier: ParkedRouteBarrier,
) {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal class SpriteCommitState(
    val groupId: SpriteGroupId,
    val jsonOrdinal: Long,
    val imageOrdinal: Long,
    val jsonCandidate: ResolvedResourceContent?,
    val imageCandidate: ResolvedResourceContent?,
    val jointValidationStatus: SpriteJointValidationStatus,
    acknowledgedWrites: List<SpriteMember>,
    val visible: Boolean,
) {
    private val acknowledgedWriteSnapshot: List<SpriteMember> = freshListCopy(acknowledgedWrites)

    init {
        require(jsonOrdinal >= 0L) { "route ordinal must be non-negative" }
        require(imageOrdinal > jsonOrdinal) { "a sprite JSON member must traverse before its image member" }
        spriteMemberOrder().forEach { member ->
            val candidate = candidate(member)
            require(candidate == null || candidate.route.resourceClass == spriteMemberResourceClass(member)) {
                "a sprite candidate must match its member resource class"
            }
        }
        if (jsonCandidate == null || imageCandidate == null) {
            require(jointValidationStatus == SpriteJointValidationStatus.WAITING) {
                "joint sprite validation requires both validated candidates"
            }
        }
        val required = requiredMemberWrites
        require(
            acknowledgedWriteSnapshot.size <= required.size &&
                acknowledgedWriteSnapshot == required.take(acknowledgedWriteSnapshot.size),
        ) { "acknowledged sprite writes must be the ordered required-write prefix" }
        require(
            acknowledgedWriteSnapshot.isEmpty() ||
                jointValidationStatus == SpriteJointValidationStatus.VALID,
        ) { "sprite member writes require successful joint validation" }
        require(
            !visible ||
                (
                    jointValidationStatus == SpriteJointValidationStatus.VALID &&
                        acknowledgedWriteSnapshot == required
                    ),
        ) { "sprite visibility requires every required member write" }
    }

    val acknowledgedWrites: List<SpriteMember>
        get() = freshListCopy(acknowledgedWriteSnapshot)

    internal val requiredMemberWrites: List<SpriteMember>
        get() {
            if (jsonCandidate == null || imageCandidate == null) return emptyList()
            return spriteMemberOrder().filter { member ->
                requiresStoreWrite(requireNotNull(candidate(member)).provenance)
            }
        }

    internal fun candidate(member: SpriteMember): ResolvedResourceContent? =
        when (member) {
            SpriteMember.JSON -> jsonCandidate
            SpriteMember.IMAGE -> imageCandidate
        }

    internal fun ordinalOf(member: SpriteMember): Long =
        when (member) {
            SpriteMember.JSON -> jsonOrdinal
            SpriteMember.IMAGE -> imageOrdinal
        }

    internal fun memberAt(ordinal: Long): SpriteMember? =
        spriteMemberOrder().firstOrNull { ordinalOf(it) == ordinal }

    internal fun withCandidate(
        member: SpriteMember,
        content: ResolvedResourceContent,
    ): SpriteCommitState = copySprite(
        jsonCandidate = if (member == SpriteMember.JSON) content else jsonCandidate,
        imageCandidate = if (member == SpriteMember.IMAGE) content else imageCandidate,
    )

    internal fun withJointValidationStatus(status: SpriteJointValidationStatus): SpriteCommitState =
        copySprite(jointValidationStatus = status)

    internal fun withAcknowledgedWrite(member: SpriteMember): SpriteCommitState =
        copySprite(acknowledgedWrites = acknowledgedWriteSnapshot + member)

    internal fun withVisible(): SpriteCommitState = copySprite(visible = true)

    private fun copySprite(
        jsonCandidate: ResolvedResourceContent? = this.jsonCandidate,
        imageCandidate: ResolvedResourceContent? = this.imageCandidate,
        jointValidationStatus: SpriteJointValidationStatus = this.jointValidationStatus,
        acknowledgedWrites: List<SpriteMember> = this.acknowledgedWriteSnapshot,
        visible: Boolean = this.visible,
    ): SpriteCommitState = SpriteCommitState(
        groupId = groupId,
        jsonOrdinal = jsonOrdinal,
        imageOrdinal = imageOrdinal,
        jsonCandidate = jsonCandidate,
        imageCandidate = imageCandidate,
        jointValidationStatus = jointValidationStatus,
        acknowledgedWrites = acknowledgedWrites,
        visible = visible,
    )

    override fun equals(other: Any?): Boolean =
        other is SpriteCommitState &&
            groupId == other.groupId &&
            jsonOrdinal == other.jsonOrdinal &&
            imageOrdinal == other.imageOrdinal &&
            jsonCandidate == other.jsonCandidate &&
            imageCandidate == other.imageCandidate &&
            jointValidationStatus == other.jointValidationStatus &&
            acknowledgedWriteSnapshot == other.acknowledgedWriteSnapshot &&
            visible == other.visible

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + jsonOrdinal.hashCode()
        result = 31 * result + imageOrdinal.hashCode()
        result = 31 * result + (jsonCandidate?.hashCode() ?: 0)
        result = 31 * result + (imageCandidate?.hashCode() ?: 0)
        result = 31 * result + jointValidationStatus.hashCode()
        result = 31 * result + acknowledgedWriteSnapshot.hashCode()
        result = 31 * result + visible.hashCode()
        return result
    }

    override fun toString(): String =
        "SpriteCommitState(" +
            "groupId=$groupId, " +
            "jsonOrdinal=$jsonOrdinal, " +
            "imageOrdinal=$imageOrdinal, " +
            "jsonCandidatePresent=${jsonCandidate != null}, " +
            "imageCandidatePresent=${imageCandidate != null}, " +
            "jointValidationStatus=$jointValidationStatus, " +
            "acknowledgedWrites=$acknowledgedWriteSnapshot, " +
            "visible=$visible)"
}

internal data class AwaitingSpritePairValidation(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val jsonOrdinal: Long,
    val imageOrdinal: Long,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(jsonOrdinal >= 0L) { "route ordinal must be non-negative" }
        require(imageOrdinal > jsonOrdinal) { "a sprite JSON member must traverse before its image member" }
        requireSpriteMemberClasses(json, image)
    }
}

internal data class AwaitingSpriteMemberWrite(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val member: SpriteMember,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        require(content.route.resourceClass == spriteMemberResourceClass(member)) {
            "a sprite member write must carry its member resource class"
        }
        require(requiresStoreWrite(content.provenance)) {
            "only transported sprite members are written"
        }
    }
}

internal data class AwaitingSpriteVisibilityInstall(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        requireSpriteMemberClasses(json, image)
    }
}

internal class StyleCommitState(
    val groupId: StyleGroupId,
    val ordinal: Long,
    val stagedContent: ResolvedResourceContent,
    val compilationStatus: StyleCompilationStatus,
    referencingOwnerIds: List<ResourceOwnerId>,
    ownersWithCompletedNonStyleWork: List<ResourceOwnerId>,
    val writeAcknowledged: Boolean,
    val visible: Boolean,
    /**
     * The routes this style's compilation will make the engine ask for, carried from validation to
     * compilation. Empty until [BasemapStyleValidationOutcome.Valid] supplies them — a style that has
     * not been validated yet has no manifest, and a validated style that declares no sprite and no
     * GeoJSON source honestly has an empty one.
     */
    styleTimeRoutes: List<ResourceRouteKey> = emptyList(),
) {
    private val referencingOwnerIdSnapshot: List<ResourceOwnerId> = freshListCopy(referencingOwnerIds)
    private val completedOwnerIdSnapshot: List<ResourceOwnerId> =
        freshListCopy(ownersWithCompletedNonStyleWork)
    private val styleTimeRouteSnapshot: List<ResourceRouteKey> = freshListCopy(styleTimeRoutes)

    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(stagedContent)
        require(referencingOwnerIdSnapshot.isNotEmpty()) {
            "a style commit requires at least one referencing owner"
        }
        require(referencingOwnerIdSnapshot.toSet().size == referencingOwnerIdSnapshot.size) {
            "referencing style owner IDs must be distinct"
        }
        require(
            completedOwnerIdSnapshot ==
                referencingOwnerIdSnapshot.filter(completedOwnerIdSnapshot.toSet()::contains) &&
                completedOwnerIdSnapshot.toSet().size == completedOwnerIdSnapshot.size,
        ) { "completed style owners must be a referencing-owner subsequence" }
        require(
            when (compilationStatus) {
                StyleCompilationStatus.NOT_REQUIRED -> !compilationRequired
                StyleCompilationStatus.WAITING,
                StyleCompilationStatus.REQUESTED,
                StyleCompilationStatus.SUCCEEDED,
                StyleCompilationStatus.FAILED,
                -> compilationRequired
            },
        ) { "style compilation status must agree with its staged provenance" }
        require(!writeAcknowledged || (requiresWrite && compilationSettled)) {
            "an acknowledged style write requires written provenance after compilation"
        }
        require(!visible || (compilationSettled && writeAcknowledged == requiresWrite)) {
            "style visibility requires settled compilation and its required write"
        }
        require(!visible || completedOwnerIdSnapshot.size == referencingOwnerIdSnapshot.size) {
            "style visibility requires every referencing owner's completed non-style work"
        }
    }

    val referencingOwnerIds: List<ResourceOwnerId>
        get() = freshListCopy(referencingOwnerIdSnapshot)

    val ownersWithCompletedNonStyleWork: List<ResourceOwnerId>
        get() = freshListCopy(completedOwnerIdSnapshot)

    val styleTimeRoutes: List<ResourceRouteKey>
        get() = freshListCopy(styleTimeRouteSnapshot)

    internal val compilationRequired: Boolean
        get() = requiresStyleCompilation(stagedContent.provenance)

    internal val compilationSettled: Boolean
        get() = compilationStatus == StyleCompilationStatus.SUCCEEDED ||
            compilationStatus == StyleCompilationStatus.NOT_REQUIRED

    internal val requiresWrite: Boolean
        get() = requiresStoreWrite(stagedContent.provenance)

    internal val allReferencingOwnersComplete: Boolean
        get() = completedOwnerIdSnapshot.size == referencingOwnerIdSnapshot.size

    internal fun withCompilationStatus(status: StyleCompilationStatus): StyleCommitState =
        copyStyle(compilationStatus = status)

    internal fun withStyleTimeRoutes(routes: List<ResourceRouteKey>): StyleCommitState =
        copyStyle(styleTimeRoutes = routes)

    internal fun withOwners(
        referencingOwnerIds: List<ResourceOwnerId>,
        ownersWithCompletedNonStyleWork: List<ResourceOwnerId>,
    ): StyleCommitState = copyStyle(
        referencingOwnerIds = referencingOwnerIds,
        ownersWithCompletedNonStyleWork = ownersWithCompletedNonStyleWork,
    )

    internal fun withWriteAcknowledged(): StyleCommitState = copyStyle(writeAcknowledged = true)

    internal fun withVisible(): StyleCommitState = copyStyle(visible = true)

    private fun copyStyle(
        compilationStatus: StyleCompilationStatus = this.compilationStatus,
        referencingOwnerIds: List<ResourceOwnerId> = this.referencingOwnerIdSnapshot,
        ownersWithCompletedNonStyleWork: List<ResourceOwnerId> = this.completedOwnerIdSnapshot,
        writeAcknowledged: Boolean = this.writeAcknowledged,
        visible: Boolean = this.visible,
        styleTimeRoutes: List<ResourceRouteKey> = this.styleTimeRouteSnapshot,
    ): StyleCommitState = StyleCommitState(
        groupId = groupId,
        ordinal = ordinal,
        stagedContent = stagedContent,
        compilationStatus = compilationStatus,
        referencingOwnerIds = referencingOwnerIds,
        ownersWithCompletedNonStyleWork = ownersWithCompletedNonStyleWork,
        writeAcknowledged = writeAcknowledged,
        visible = visible,
        styleTimeRoutes = styleTimeRoutes,
    )

    override fun equals(other: Any?): Boolean =
        other is StyleCommitState &&
            groupId == other.groupId &&
            ordinal == other.ordinal &&
            stagedContent == other.stagedContent &&
            compilationStatus == other.compilationStatus &&
            referencingOwnerIdSnapshot == other.referencingOwnerIdSnapshot &&
            completedOwnerIdSnapshot == other.completedOwnerIdSnapshot &&
            writeAcknowledged == other.writeAcknowledged &&
            visible == other.visible &&
            styleTimeRouteSnapshot == other.styleTimeRouteSnapshot

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + ordinal.hashCode()
        result = 31 * result + stagedContent.hashCode()
        result = 31 * result + compilationStatus.hashCode()
        result = 31 * result + referencingOwnerIdSnapshot.hashCode()
        result = 31 * result + completedOwnerIdSnapshot.hashCode()
        result = 31 * result + writeAcknowledged.hashCode()
        result = 31 * result + visible.hashCode()
        result = 31 * result + styleTimeRouteSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "StyleCommitState(" +
            "groupId=$groupId, " +
            "ordinal=$ordinal, " +
            "compilationStatus=$compilationStatus, " +
            "referencingOwnerCount=${referencingOwnerIdSnapshot.size}, " +
            "completedOwnerCount=${completedOwnerIdSnapshot.size}, " +
            "writeAcknowledged=$writeAcknowledged, " +
            "visible=$visible, " +
            "styleTimeRouteCount=${styleTimeRouteSnapshot.size})"
}

internal data class AwaitingStyleValidation(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(content)
    }
}

internal data class AwaitingStyleCompilation(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(content)
        require(requiresStyleCompilation(content.provenance)) {
            "only uncompiled style content is compiled"
        }
    }
}

internal data class AwaitingStyleWrite(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(content)
        require(requiresStoreWrite(content.provenance)) { "only transported style content is written" }
    }
}

internal class AwaitingStyleVisibilityInstall(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
    referencingOwnerIds: List<ResourceOwnerId>,
) : ResourceRouteCursor {
    private val referencingOwnerIdSnapshot: List<ResourceOwnerId> = freshListCopy(referencingOwnerIds)

    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(content)
        requireReferencingStyleOwners(referencingOwnerIdSnapshot)
    }

    val referencingOwnerIds: List<ResourceOwnerId>
        get() = freshListCopy(referencingOwnerIdSnapshot)

    override fun equals(other: Any?): Boolean =
        other is AwaitingStyleVisibilityInstall &&
            actionId == other.actionId &&
            ordinal == other.ordinal &&
            groupId == other.groupId &&
            content == other.content &&
            referencingOwnerIdSnapshot == other.referencingOwnerIdSnapshot

    override fun hashCode(): Int {
        var result = actionId.hashCode()
        result = 31 * result + ordinal.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + referencingOwnerIdSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "AwaitingStyleVisibilityInstall(" +
            "actionId=$actionId, " +
            "ordinal=$ordinal, " +
            "groupId=$groupId, " +
            "referencingOwnerCount=${referencingOwnerIdSnapshot.size})"
}

private fun requireBasemapStyleContent(content: ResolvedResourceContent) {
    require(content.route.resourceClass == ResourceClass.BASEMAP_STYLE) {
        "a style commit requires basemap style content"
    }
}

private fun requireReferencingStyleOwners(referencingOwnerIds: List<ResourceOwnerId>) {
    require(referencingOwnerIds.isNotEmpty()) {
        "a style commit requires at least one referencing owner"
    }
    require(referencingOwnerIds.toSet().size == referencingOwnerIds.size) {
        "referencing style owner IDs must be distinct"
    }
}

private fun requireSpriteMemberClasses(
    json: ResolvedResourceContent,
    image: ResolvedResourceContent,
) {
    require(json.route.resourceClass == ResourceClass.BASEMAP_SPRITE_JSON) {
        "a sprite pair must carry sprite JSON content"
    }
    require(image.route.resourceClass == ResourceClass.BASEMAP_SPRITE_IMAGE) {
        "a sprite pair must carry sprite image content"
    }
}

internal class RouteRecord(
    val registration: ResourceRouteRegistration,
    joinedOccurrenceIds: List<ResourceOccurrenceId>,
    val ordinal: Long?,
    val cursor: ResourceRouteCursor?,
    val status: ResourceRouteStatus,
    val lookup: LookupProgress? = null,
    val storeWriteAcknowledged: Boolean = false,
    val visibilityInstalled: Boolean = false,
) {
    private val joinedOccurrenceIdSnapshot: List<ResourceOccurrenceId> = freshListCopy(joinedOccurrenceIds)

    val joinedOccurrenceIds: List<ResourceOccurrenceId>
        get() = freshListCopy(joinedOccurrenceIdSnapshot)
}

internal data class PrivateRentileKeyClaim(
    val privateKey: RentilePrivateKey,
    val firstRoute: ResourceRouteKey,
    val usable: Boolean,
)

internal data class CanonicalIdentityRecord(
    val resourceKey: ResourceKey,
    val canonicalBytes: CanonicalBytes,
)

internal sealed interface ResourceRouteOutcome {
    data object Success : ResourceRouteOutcome

    data class Failure(val failure: FailureDescriptor) : ResourceRouteOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : ResourceRouteOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "route cancellation must originate from an adapter"
            }
        }
    }
}

internal data class BufferedRouteOutcome(
    val ordinal: Long,
    val outcome: ResourceRouteOutcome,
) {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal sealed interface ResourceTerminalSelection {
    data class Route(
        val ordinal: Long,
        val outcome: ResourceRouteOutcome,
    ) : ResourceTerminalSelection {
        init {
            require(ordinal >= 0L) { "route ordinal must be non-negative" }
            require(outcome !is ResourceRouteOutcome.Success) {
                "route terminal selection must be a failure or cancellation"
            }
        }
    }

    data class External(
        val cancellation: CancellationSelection,
    ) : ResourceTerminalSelection {
        init {
            require(
                cancellation.cause == CancellationCause.CALLER ||
                    cancellation.cause == CancellationCause.CANCEL_PREPARATIONS,
            ) { "external terminal must originate from caller or cancelPreparations" }
        }
    }
}

internal sealed interface ResourceOperationEvent

internal data class ClockSampled(
    val actionId: ResourceActionId,
    val sampleEpochMillis: Long,
) : ResourceOperationEvent {
    init {
        require(sampleEpochMillis >= 0L) { "freshness sample must be non-negative" }
    }
}

internal data class ResidentObserved(
    val actionId: ResourceActionId,
    val resource: StoredRawResource?,
) : ResourceOperationEvent

internal data class StoreReadCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<StoredRawResource?>,
) : ResourceOperationEvent

internal data class TransportCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<TransportResponse>,
) : ResourceOperationEvent

internal data class LatchedTransportReplayCompleted(
    val actionId: ResourceActionId,
) : ResourceOperationEvent

internal data class AdvancePendingClassGates(
    val ordinal: Long,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ResourceClassValidationCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedValidationOutcome,
) : ResourceOperationEvent

internal data class StoreWriteCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<Unit>,
) : ResourceOperationEvent

internal data class VisibilityInstallCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedInstallOutcome,
) : ResourceOperationEvent

internal data class AdvancePendingSpriteCommit(
    val ordinal: Long,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal sealed interface SpritePairValidationOutcome {
    data object Valid : SpritePairValidationOutcome

    data class Failed(
        val member: SpriteMember,
        val kind: SpritePairFailureKind,
    ) : SpritePairValidationOutcome {
        init {
            require(
                when (kind) {
                    SpritePairFailureKind.JSON_PARSE -> member == SpriteMember.JSON
                    SpritePairFailureKind.IMAGE_DECODE -> member == SpriteMember.IMAGE
                    SpritePairFailureKind.UNSUPPORTED_FEATURE -> true
                },
            ) { "a sprite pair failure kind must match its reported member" }
        }
    }

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SpritePairValidationOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied sprite validation cancellation must originate from an adapter"
            }
        }
    }
}

internal data class SpritePairValidationCompleted(
    val actionId: ResourceActionId,
    val outcome: SpritePairValidationOutcome,
) : ResourceOperationEvent

internal data class SpriteMemberWriteCompleted(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val member: SpriteMember,
    val outcome: SuppliedCallOutcome<Unit>,
) : ResourceOperationEvent

internal data class SpriteVisibilityInstallCompleted(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val outcome: SuppliedInstallOutcome,
) : ResourceOperationEvent

internal data class AdvancePendingStyleCommit(
    val ordinal: Long,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal sealed interface BasemapStyleValidationOutcome {
    /**
     * The style reads, and here is the **route manifest** its compilation will make the Rentile engine
     * ask for: the sprite pair and every GeoJSON document, derived purely from the style document by
     * `deriveBasemapStyleManifest`/`styleTimeRoutes`.
     *
     * These are a *preregistration* manifest, not resources RenG fetches. RenG keys only its own four
     * classes (ADR 0016); the eight engine-keyed ones are acquired by the engine itself and are admitted
     * only because the firewall recognises the exact url. Modelling them as occurrences instead would
     * have to exempt them from six independent invariants that assume every occurrence becomes a
     * fetched, visibility-installed route — most decisively the one that makes operation success
     * impossible unless every occurrence's route carries `visibilityInstalled` — and would buy nothing,
     * since [com.rohittp.reng.internal.firewall.OperationRegistry.preregister] already performs the
     * private-key collision detection an occurrence would add.
     *
     * A route list is not a set of children, so this outcome no longer produces a
     * [DiscoveredResourceChild]. Nothing in production does; see [DiscoverChildren].
     */
    class Valid(
        routes: List<ResourceRouteKey>,
    ) : BasemapStyleValidationOutcome {
        private val routeSnapshot: List<ResourceRouteKey> = freshListCopy(routes)

        init {
            require(routeSnapshot.toSet().size == routeSnapshot.size) {
                "a style route manifest must not repeat a route"
            }
        }

        val routes: List<ResourceRouteKey>
            get() = freshListCopy(routeSnapshot)

        override fun equals(other: Any?): Boolean = other is Valid && routeSnapshot == other.routeSnapshot

        override fun hashCode(): Int = routeSnapshot.hashCode()

        /** Redacted: a route carries a locator, and a locator can carry a credential. */
        override fun toString(): String = "Valid(routeCount=${routeSnapshot.size})"
    }

    data class Failed(
        val kind: StyleFailureKind,
    ) : BasemapStyleValidationOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : BasemapStyleValidationOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied style validation cancellation must originate from an adapter"
            }
        }
    }
}

internal sealed interface BasemapStyleCompilationOutcome {
    data object Succeeded : BasemapStyleCompilationOutcome

    data class Failed(
        val kind: StyleFailureKind,
    ) : BasemapStyleCompilationOutcome

    /**
     * Compilation failed for a reason that is **not** a property of the style document: the firewall
     * refused a url RenG did not preregister, a consumer adapter the engine reached through it failed,
     * a byte ceiling was exceeded. [failure] is the descriptor
     * [com.rohittp.reng.internal.firewall.BasemapEngineHost] already sanitized and already translated
     * into RenG's own identity namespace, and it is forwarded verbatim — the same asymmetry
     * [SuppliedInstallOutcome.Failed] carries, and for the same reason: collapsing it into a
     * [StyleFailureKind] would report a firewall or transport fault as `RESOURCE_PARSE_FAILED`, which
     * is a lie about a document that parses perfectly well.
     */
    data class EngineFailed(
        val failure: FailureDescriptor,
    ) : BasemapStyleCompilationOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : BasemapStyleCompilationOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied style compilation cancellation must originate from an adapter"
            }
        }
    }
}

internal data class BasemapStyleValidationCompleted(
    val actionId: ResourceActionId,
    val outcome: BasemapStyleValidationOutcome,
) : ResourceOperationEvent

internal data class BasemapStyleCompilationCompleted(
    val actionId: ResourceActionId,
    val outcome: BasemapStyleCompilationOutcome,
) : ResourceOperationEvent

internal data class BasemapStyleWriteCompleted(
    val actionId: ResourceActionId,
    val groupId: StyleGroupId,
    val outcome: SuppliedCallOutcome<Unit>,
) : ResourceOperationEvent

internal data class BasemapStyleVisibilityInstallCompleted(
    val actionId: ResourceActionId,
    val groupId: StyleGroupId,
    val outcome: SuppliedInstallOutcome,
) : ResourceOperationEvent

internal class ChildrenDiscovered(
    val parentOccurrenceId: ResourceOccurrenceId,
    children: List<DiscoveredResourceChild>,
) : ResourceOperationEvent {
    private val childSnapshot: List<DiscoveredResourceChild> =
        freshListCopy(children).sortedWith(resourceChildComparator)

    init {
        for (index in 1 until childSnapshot.size) {
            require(
                resourceChildComparator.compare(childSnapshot[index - 1], childSnapshot[index]) != 0,
            ) { "discovered child traversal descriptors must be distinguishable" }
        }
    }

    val children: List<DiscoveredResourceChild>
        get() = freshListCopy(childSnapshot)
}

internal data class RouteReadyForDiscovery(
    val ordinal: Long,
    val parentOccurrenceId: ResourceOccurrenceId,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class RouteCompleted(
    val ordinal: Long,
    val outcome: ResourceRouteOutcome,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ExternalCancellationRequested(
    val cancellation: CancellationSelection,
) : ResourceOperationEvent {
    init {
        require(
            cancellation.cause == CancellationCause.CALLER ||
                cancellation.cause == CancellationCause.CANCEL_PREPARATIONS,
        ) { "external cancellation must originate from caller or cancelPreparations" }
    }
}

internal data class CleanupCancellationObserved(
    val ordinal: Long,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal sealed interface ResourceOperationAction

internal data class SampleClock(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ObserveResident(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val resourceKey: ResourceKey,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ReadStore(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val rawKey: RawResourceKey,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class CallTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val request: TransportRequest,
    val latchKey: TransportLatchKey,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        require(
            latchKey.route.locator == request.locator &&
                latchKey.route.resourceClass == request.resourceClass &&
                latchKey.route.maximumResponseBytes == request.maximumResponseBytes,
        ) { "Transport request must match its latch route" }
        require(request.metadata.ifNoneMatch == latchKey.ifNoneMatch) {
            "Transport request ETag must match its latch"
        }
        require(request.metadata.ifModifiedSince == latchKey.ifModifiedSince) {
            "Transport request last-modified value must match its latch"
        }
        require(request.metadata.accept == latchKey.accept) {
            "Transport request accept value must match its latch"
        }
    }
}

internal data class ReplayLatchedTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latch: TransportLatchRecord,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ValidateResourceClass(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
    val gate: ResourceClassGate,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class WriteStore(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val rawKey: RawResourceKey,
    val resource: StoredRawResource,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class InstallVisibility(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ValidateSpritePair(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceOperationAction {
    init {
        requireSpriteMemberClasses(json, image)
    }
}

internal data class WriteSpriteMember(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val member: SpriteMember,
    val ordinal: Long,
    val rawKey: RawResourceKey,
    val resource: StoredRawResource,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        require(rawKey.resourceClass == spriteMemberResourceClass(member)) {
            "a sprite member write must name its member Store key class"
        }
    }
}

internal data class InstallSpriteVisibility(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceOperationAction {
    init {
        requireSpriteMemberClasses(json, image)
    }
}

internal data class ValidateBasemapStyle(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(content)
    }
}

/**
 * Compile [content] through the Rentile engine, having first preregistered [routes] — the manifest
 * [BasemapStyleValidationOutcome.Valid] derived from this exact document — on the invocation's firewall.
 * The two travel together because the engine's own sprite and GeoJSON acquisition happens *inside* the
 * compilation: a route preregistered afterwards is preregistered too late, and one never preregistered
 * makes the compilation fail closed as `AMBIGUOUS_RESOURCE_ROUTE`.
 */
internal class CompileBasemapStyle(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
    routes: List<ResourceRouteKey> = emptyList(),
) : ResourceOperationAction {
    private val routeSnapshot: List<ResourceRouteKey> = freshListCopy(routes)

    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(content)
        require(requiresStyleCompilation(content.provenance)) {
            "only uncompiled style content is compiled"
        }
        require(routeSnapshot.toSet().size == routeSnapshot.size) {
            "a style route manifest must not repeat a route"
        }
    }

    val routes: List<ResourceRouteKey>
        get() = freshListCopy(routeSnapshot)

    override fun equals(other: Any?): Boolean =
        other is CompileBasemapStyle &&
            actionId == other.actionId &&
            ordinal == other.ordinal &&
            groupId == other.groupId &&
            content == other.content &&
            routeSnapshot == other.routeSnapshot

    override fun hashCode(): Int {
        var result = actionId.hashCode()
        result = 31 * result + ordinal.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + routeSnapshot.hashCode()
        return result
    }

    /** Redacted: a route carries a locator, and a locator can carry a credential. */
    override fun toString(): String =
        "CompileBasemapStyle(" +
            "actionId=$actionId, " +
            "ordinal=$ordinal, " +
            "groupId=$groupId, " +
            "routeCount=${routeSnapshot.size})"
}

internal data class WriteBasemapStyle(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val rawKey: RawResourceKey,
    val resource: StoredRawResource,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        require(rawKey.resourceClass == ResourceClass.BASEMAP_STYLE) {
            "a style write must name its basemap style Store key class"
        }
    }
}

internal class InstallBasemapStyleVisibility(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
    referencingOwnerIds: List<ResourceOwnerId>,
) : ResourceOperationAction {
    private val referencingOwnerIdSnapshot: List<ResourceOwnerId> = freshListCopy(referencingOwnerIds)

    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        requireBasemapStyleContent(content)
        requireReferencingStyleOwners(referencingOwnerIdSnapshot)
    }

    val referencingOwnerIds: List<ResourceOwnerId>
        get() = freshListCopy(referencingOwnerIdSnapshot)

    override fun equals(other: Any?): Boolean =
        other is InstallBasemapStyleVisibility &&
            actionId == other.actionId &&
            ordinal == other.ordinal &&
            groupId == other.groupId &&
            content == other.content &&
            referencingOwnerIdSnapshot == other.referencingOwnerIdSnapshot

    override fun hashCode(): Int {
        var result = actionId.hashCode()
        result = 31 * result + ordinal.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + referencingOwnerIdSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "InstallBasemapStyleVisibility(" +
            "actionId=$actionId, " +
            "ordinal=$ordinal, " +
            "groupId=$groupId, " +
            "referencingOwnerCount=${referencingOwnerIdSnapshot.size})"
}

internal data class StartRoute(
    val ordinal: Long,
    val registration: ResourceRouteRegistration,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

/**
 * **Unreachable in production, deliberately — not an unfinished feature.**
 *
 * Generic route-driven child discovery. It is emitted only from [RouteReadyForDiscovery], and the one
 * resource class that ever declared `discoveryRequired` — the basemap style — cannot emit that event:
 * a style route retires through its own visibility install instead. Since
 * [BasemapStyleValidationOutcome.Valid] now yields a *route manifest* rather than
 * [DiscoveredResourceChild]ren, nothing in RenG produces a child occurrence at all, and nothing is
 * planned to: the eight engine-keyed classes are acquired by the Rentile engine through the firewall,
 * never by RenG's own driver (ADR 0016).
 *
 * This mechanism, [ChildrenDiscovered], [RouteReadyForDiscovery], [DiscoveredResourceChild],
 * [ResourceChildTraversal], [BasemapSourceMember] and [DiscoveryFrontier]'s child half are retained
 * because the pure core's own test suite exercises them as a complete, correct abstraction, and
 * deleting them is a separate change with a large blast radius. `ResourceOccurrence.discoveryRequired`
 * is **not** part of that set: it is still load-bearing, because it is what pushes the style's frontier
 * and so what keeps the style's route ahead of its siblings in traversal order.
 */
internal data class DiscoverChildren(
    val ordinal: Long,
    val parentOccurrenceId: ResourceOccurrenceId,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class CancelRoute(
    val ordinal: Long,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

private val resourceChildComparator: Comparator<DiscoveredResourceChild> = Comparator { first, second ->
    compareChildTraversal(first.traversal, second.traversal)
}

private fun compareChildTraversal(
    first: ResourceChildTraversal,
    second: ResourceChildTraversal,
): Int {
    val rankComparison = childTraversalRank(first).compareTo(childTraversalRank(second))
    if (rankComparison != 0) return rankComparison

    return when (first) {
        is ResourceChildTraversal.BasemapSprite -> compareSpriteMembers(
            first.member,
            (second as ResourceChildTraversal.BasemapSprite).member,
        )
        is ResourceChildTraversal.BasemapSource -> compareBasemapSources(
            first,
            second as ResourceChildTraversal.BasemapSource,
        )
        is ResourceChildTraversal.DeclaredArray ->
            first.index.compareTo((second as ResourceChildTraversal.DeclaredArray).index)
        is ResourceChildTraversal.ObjectMember -> compareUnsignedUtf8(
            first.exactKey,
            (second as ResourceChildTraversal.ObjectMember).exactKey,
        )
    }
}

private fun childTraversalRank(traversal: ResourceChildTraversal): Int =
    when (traversal) {
        is ResourceChildTraversal.BasemapSprite -> 0
        is ResourceChildTraversal.BasemapSource -> 1
        is ResourceChildTraversal.DeclaredArray -> 2
        is ResourceChildTraversal.ObjectMember -> 3
    }

private fun compareSpriteMembers(first: SpriteMember, second: SpriteMember): Int =
    spriteMemberRank(first).compareTo(spriteMemberRank(second))

private fun spriteMemberRank(member: SpriteMember): Int =
    when (member) {
        SpriteMember.JSON -> 0
        SpriteMember.IMAGE -> 1
    }

private fun compareBasemapSources(
    first: ResourceChildTraversal.BasemapSource,
    second: ResourceChildTraversal.BasemapSource,
): Int {
    val sourceComparison = compareUnsignedUtf8(first.sourceId, second.sourceId)
    if (sourceComparison != 0) return sourceComparison
    return compareBasemapSourceMembers(first.member, second.member)
}

private fun compareBasemapSourceMembers(
    first: BasemapSourceMember,
    second: BasemapSourceMember,
): Int {
    val rankComparison = basemapSourceMemberRank(first).compareTo(basemapSourceMemberRank(second))
    if (rankComparison != 0) return rankComparison
    if (first is BasemapSourceMember.Metadata) return 0

    first as BasemapSourceMember.Tile
    second as BasemapSourceMember.Tile
    return first.lod.compareTo(second.lod)
        .takeIf { it != 0 }
        ?: first.tileY.compareTo(second.tileY).takeIf { it != 0 }
        ?: first.canonicalX.compareTo(second.canonicalX)
}

private fun basemapSourceMemberRank(member: BasemapSourceMember): Int =
    when (member) {
        BasemapSourceMember.Metadata -> 0
        is BasemapSourceMember.Tile -> 1
    }

private fun compareUnsignedUtf8(first: String, second: String): Int {
    val firstBytes = first.encodeToByteArray()
    val secondBytes = second.encodeToByteArray()
    val sharedSize = minOf(firstBytes.size, secondBytes.size)
    for (index in 0 until sharedSize) {
        val comparison = (firstBytes[index].toInt() and 0xff).compareTo(secondBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return firstBytes.size.compareTo(secondBytes.size)
}

internal sealed interface ResponseRuleOutcome {
    data class Selected(val content: ResolvedResourceContent) : ResponseRuleOutcome

    data class Failure(val failure: FailureDescriptor) : ResponseRuleOutcome
}

internal data class VisibleResource(
    val resourceKey: ResourceKey,
    val content: ResolvedResourceContent,
) {
    init {
        require(resourceKey == content.resourceKey) {
            "a visible resource must carry its own content identity"
        }
    }
}

internal class OwnerResourceSet(
    val ownerId: ResourceOwnerId,
    resources: List<VisibleResource>,
) {
    private val resourceSnapshot: List<VisibleResource> = freshListCopy(resources)

    init {
        require(resourceSnapshot.toSet().size == resourceSnapshot.size) {
            "an owner resource set must not repeat a visible resource"
        }
    }

    val resources: List<VisibleResource>
        get() = freshListCopy(resourceSnapshot)

    override fun equals(other: Any?): Boolean =
        other is OwnerResourceSet && ownerId == other.ownerId && resourceSnapshot == other.resourceSnapshot

    override fun hashCode(): Int = 31 * ownerId.hashCode() + resourceSnapshot.hashCode()

    override fun toString(): String =
        "OwnerResourceSet(ownerId=$ownerId, resourceCount=${resourceSnapshot.size})"
}

internal sealed interface ResourceOperationOutcome {
    class Success(
        resourceSets: List<OwnerResourceSet>,
    ) : ResourceOperationOutcome {
        private val resourceSetSnapshot: List<OwnerResourceSet> = freshListCopy(resourceSets)

        init {
            require(
                resourceSetSnapshot.mapTo(mutableSetOf(), OwnerResourceSet::ownerId).size ==
                    resourceSetSnapshot.size,
            ) { "a successful outcome must carry one resource set per owner" }
        }

        val resourceSets: List<OwnerResourceSet>
            get() = freshListCopy(resourceSetSnapshot)

        override fun equals(other: Any?): Boolean =
            other is Success && resourceSetSnapshot == other.resourceSetSnapshot

        override fun hashCode(): Int = resourceSetSnapshot.hashCode()

        override fun toString(): String = "Success(resourceSetCount=${resourceSetSnapshot.size})"
    }

    data class Failure(val failure: FailureDescriptor) : ResourceOperationOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : ResourceOperationOutcome
}

internal sealed interface ResourceOperationState {
    class Running(
        val definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
        transportLatches: List<TransportLatchRecord> = emptyList(),
        val nextActionId: Long = 1L,
        val traversal: TraversalState = TraversalState(emptyList(), emptyList(), emptyList()),
        val nextRouteOrdinal: Long = 0L,
        activeRouteOrdinals: List<Long> = emptyList(),
        val nextRetirementOrdinal: Long = 0L,
        bufferedRouteOutcomes: List<BufferedRouteOutcome> = emptyList(),
        val startCeilingOrdinal: Long? = null,
        val terminalSelection: ResourceTerminalSelection? = null,
        spriteCommitStates: List<SpriteCommitState> = emptyList(),
        parkedRoutes: List<ParkedRoute> = emptyList(),
        styleCommitStates: List<StyleCommitState> = emptyList(),
        visibleResourcesByOwner: List<OwnerResourceSet> = emptyList(),
    ) : ResourceOperationState {
        private val occurrenceSnapshot: List<ResourceOccurrence> = freshListCopy(occurrences)
        private val routeRecordSnapshot: List<RouteRecord> = freshListCopy(routeRecords)
        private val privateRentileKeyClaimSnapshot: List<PrivateRentileKeyClaim> =
            freshListCopy(privateRentileKeyClaims)
        private val identityRecordSnapshot: List<CanonicalIdentityRecord> = freshListCopy(identityRecords)
        private val transportLatchSnapshot: List<TransportLatchRecord> = freshListCopy(transportLatches)
        private val activeRouteOrdinalSnapshot: List<Long> = freshListCopy(activeRouteOrdinals)
        private val bufferedRouteOutcomeSnapshot: List<BufferedRouteOutcome> =
            freshListCopy(bufferedRouteOutcomes)
        private val spriteCommitStateSnapshot: List<SpriteCommitState> = freshListCopy(spriteCommitStates)
        private val parkedRouteSnapshot: List<ParkedRoute> = freshListCopy(parkedRoutes)
        private val styleCommitStateSnapshot: List<StyleCommitState> = freshListCopy(styleCommitStates)
        private val visibleResourcesByOwnerSnapshot: List<OwnerResourceSet> =
            freshListCopy(visibleResourcesByOwner)

        init {
            require(nextRouteOrdinal >= 0L) { "next route ordinal must be non-negative" }
            require(nextActionId > 0L) { "next action ID must be positive" }
            require(nextRetirementOrdinal in 0L..nextRouteOrdinal) {
                "next retirement ordinal must not exceed assigned route ordinals"
            }

            val occurrenceById = mutableMapOf<ResourceOccurrenceId, ResourceOccurrence>()
            require(occurrenceSnapshot.all { occurrenceById.put(it.id, it) == null }) {
                "resource occurrence IDs must be unique"
            }
            val occurrenceIds = occurrenceById.keys
            val identityStableIds = mutableSetOf<String>()
            require(identityRecordSnapshot.all { identityStableIds.add(it.resourceKey.stableId) }) {
                "canonical identity stable IDs must be unique"
            }
            val privateKeys = mutableSetOf<RentilePrivateKey>()
            require(privateRentileKeyClaimSnapshot.all { privateKeys.add(it.privateKey) }) {
                "private Rentile key claims must be unique"
            }
            val transportLatchKeys = mutableSetOf<TransportLatchKey>()
            require(transportLatchSnapshot.all { transportLatchKeys.add(it.key) }) {
                "Transport latch keys must be unique"
            }

            val spriteStateByGroup = mutableMapOf<SpriteGroupId, SpriteCommitState>()
            require(
                spriteCommitStateSnapshot.all { group ->
                    spriteStateByGroup.put(group.groupId, group) == null
                },
            ) { "sprite commit group IDs must be unique" }
            val styleStateByGroup = mutableMapOf<StyleGroupId, StyleCommitState>()
            require(
                styleCommitStateSnapshot.all { group ->
                    styleStateByGroup.put(group.groupId, group) == null
                },
            ) { "style commit group IDs must be unique" }
            val visibleOwnerIds = mutableSetOf<ResourceOwnerId>()
            require(visibleResourcesByOwnerSnapshot.all { visibleOwnerIds.add(it.ownerId) }) {
                "visible resource owner IDs must be unique"
            }

            val assignedOrdinals = mutableSetOf<Long>()
            val runningOrdinals = mutableSetOf<Long>()
            val registeredRoutes = mutableSetOf<ResourceRouteKey>()
            val cursorActionIds = mutableSetOf<ResourceActionId>()
            val selectedContentByOrdinal = mutableMapOf<Long, ResolvedResourceContent>()
            val visibleOrdinals = mutableSetOf<Long>()
            val cursorByOrdinal = mutableMapOf<Long, ResourceRouteCursor>()
            val joinedOccurrenceIdsByOrdinal = mutableMapOf<Long, List<ResourceOccurrenceId>>()
            routeRecordSnapshot.forEach { record ->
                registeredRoutes += record.registration.route
                val joinedIds = record.joinedOccurrenceIds
                require(joinedIds.toSet().size == joinedIds.size && joinedIds.all(occurrenceIds::contains)) {
                    "joined resource occurrence IDs must be unique and registered"
                }
                record.ordinal?.let { ordinal ->
                    require(ordinal >= 0L && ordinal < nextRouteOrdinal && assignedOrdinals.add(ordinal)) {
                        "route ordinals must be unique and assigned below the next ordinal"
                    }
                    if (record.status == ResourceRouteStatus.RUNNING) runningOrdinals += ordinal
                    record.lookup?.selectedContent?.let { selectedContentByOrdinal[ordinal] = it }
                    if (record.visibilityInstalled) visibleOrdinals += ordinal
                    record.cursor?.let { cursorByOrdinal[ordinal] = it }
                    joinedOccurrenceIdsByOrdinal[ordinal] = joinedIds
                }
                require(
                    when (record.status) {
                        ResourceRouteStatus.PREREGISTERED -> record.ordinal == null
                        ResourceRouteStatus.ELIGIBLE,
                        ResourceRouteStatus.RUNNING,
                        ResourceRouteStatus.RESOLVED,
                        -> record.ordinal != null
                        ResourceRouteStatus.BLOCKED_BY_COLLISION -> true
                    },
                ) { "route status must agree with ordinal assignment" }

                val lookup = record.lookup
                lookup?.transportLatch?.let { latchKey ->
                    require(latchKey.route == record.registration.route) {
                        "lookup latch must belong to its route"
                    }
                }
                lookup?.selectedContent?.let { selected ->
                    require(
                        selected.route == record.registration.route &&
                            selected.resourceKey == record.registration.resourceKey,
                    ) { "selected content must belong to its registered route" }
                }
                require(!record.visibilityInstalled || lookup?.selectedContent != null) {
                    "installed visibility requires selected content"
                }
                require(
                    !record.storeWriteAcknowledged ||
                        lookup?.selectedContent?.provenance?.let(::requiresStoreWrite) == true,
                ) { "an acknowledged Store write requires selected content that must be written" }
                require(
                    !record.visibilityInstalled ||
                        record.cursor == null ||
                        record.cursor is PendingChildDiscovery,
                ) { "installed visibility leaves only a child-discovery cursor in flight" }

                record.cursor?.let { cursor ->
                    val ordinal = requireNotNull(record.ordinal) {
                        "a route cursor requires an assigned ordinal"
                    }
                    require(record.status == ResourceRouteStatus.RUNNING) {
                        "a route cursor requires a running route"
                    }
                    val declaredOrdinal = cursorOrdinal(cursor)
                    require(declaredOrdinal == null || declaredOrdinal == ordinal) {
                        "route cursor ordinal must match its record"
                    }
                    cursorActionId(cursor)?.let { actionId ->
                        require(actionId.value < nextActionId && cursorActionIds.add(actionId)) {
                            "cursor action IDs must be unique and allocated below the next action ID"
                        }
                    }
                    when (cursor) {
                        is AwaitingClockSample -> require(
                            lookup != null && lookup.sampleEpochMillis == null,
                        ) { "clock cursor requires an unsampled lookup" }
                        is AwaitingResident -> require(lookup?.sampleEpochMillis != null) {
                            "resident cursor requires a freshness sample"
                        }
                        is AwaitingStoreRead -> require(
                            lookup?.sampleEpochMillis != null && lookup.storeReadStarted,
                        ) { "Store cursor requires a sampled started read" }
                        is AwaitingTransport -> require(
                            lookup?.transportLatch == cursor.latchKey &&
                                cursor.latchKey !in transportLatchKeys,
                        ) { "Transport cursor requires one open matching latch" }
                        is AwaitingLatchedTransportReplay -> require(
                            lookup?.transportLatch == cursor.latchKey &&
                                cursor.latchKey in transportLatchKeys,
                        ) { "replay cursor requires one closed matching latch" }
                        is PendingClassGates -> require(
                            lookup?.selectedContent == cursor.content,
                        ) { "pending class gates require matching selected content" }
                        is PendingChildDiscovery -> require(
                            lookup?.selectedContent == cursor.content &&
                                record.visibilityInstalled &&
                                record.joinedOccurrenceIds.any { joinedId ->
                                    occurrenceById[joinedId]?.discoveryRequired == true
                                },
                        ) { "pending child discovery requires an installed discovery occurrence" }
                        is AwaitingClassGate -> require(
                            lookup?.selectedContent == cursor.content &&
                                ordinaryResourceClassGates(cursor.content.route.resourceClass)
                                    ?.getOrNull(cursor.gateIndex) == cursor.gate &&
                                !record.storeWriteAcknowledged,
                        ) { "class gate cursor requires matching unwritten content at its gate index" }
                        is AwaitingStoreWrite -> require(
                            lookup?.selectedContent == cursor.content &&
                                requiresStoreWrite(cursor.content.provenance) &&
                                !record.storeWriteAcknowledged,
                        ) { "Store write cursor requires matching content that must still be written" }
                        is AwaitingVisibilityInstall -> require(
                            lookup?.selectedContent == cursor.content &&
                                (
                                    !requiresStoreWrite(cursor.content.provenance) ||
                                        record.storeWriteAcknowledged
                                    ),
                        ) { "visibility install cursor requires matching content after its required write" }
                        is AwaitingSpritePairValidation -> {
                            val group = spriteGroupForCursor(spriteStateByGroup, cursor.groupId, ordinal)
                            require(
                                group.jointValidationStatus == SpriteJointValidationStatus.REQUESTED &&
                                    cursor.jsonOrdinal == group.jsonOrdinal &&
                                    cursor.imageOrdinal == group.imageOrdinal &&
                                    cursor.json == group.jsonCandidate &&
                                    cursor.image == group.imageCandidate,
                            ) { "sprite validation cursor must match its requested group candidates" }
                        }
                        is AwaitingSpriteMemberWrite -> {
                            val group = spriteGroupForCursor(spriteStateByGroup, cursor.groupId, ordinal)
                            require(
                                group.jointValidationStatus == SpriteJointValidationStatus.VALID &&
                                    cursor.ordinal == group.ordinalOf(cursor.member) &&
                                    cursor.content == group.candidate(cursor.member) &&
                                    group.requiredMemberWrites
                                        .getOrNull(group.acknowledgedWrites.size) == cursor.member,
                            ) { "sprite write cursor must match the next required member write" }
                        }
                        is AwaitingSpriteVisibilityInstall -> {
                            val group = spriteGroupForCursor(spriteStateByGroup, cursor.groupId, ordinal)
                            require(
                                group.jointValidationStatus == SpriteJointValidationStatus.VALID &&
                                    cursor.json == group.jsonCandidate &&
                                    cursor.image == group.imageCandidate &&
                                    group.acknowledgedWrites == group.requiredMemberWrites &&
                                    !group.visible,
                            ) { "sprite install cursor requires every acknowledged member write" }
                        }
                        is AwaitingStyleValidation -> {
                            val group = styleGroupForCursor(styleStateByGroup, cursor.groupId, ordinal)
                            require(
                                lookup?.selectedContent == cursor.content &&
                                    group.stagedContent == cursor.content &&
                                    !group.writeAcknowledged &&
                                    !group.visible &&
                                    (
                                        group.compilationStatus == StyleCompilationStatus.WAITING ||
                                            group.compilationStatus == StyleCompilationStatus.NOT_REQUIRED
                                        ),
                            ) { "style validation cursor requires its unvalidated staged content" }
                        }
                        is AwaitingStyleCompilation -> {
                            val group = styleGroupForCursor(styleStateByGroup, cursor.groupId, ordinal)
                            require(
                                lookup?.selectedContent == cursor.content &&
                                    group.stagedContent == cursor.content &&
                                    group.compilationStatus == StyleCompilationStatus.REQUESTED &&
                                    !group.writeAcknowledged &&
                                    !group.visible,
                            ) { "style compilation cursor requires its requested staged content" }
                        }
                        is AwaitingStyleWrite -> {
                            val group = styleGroupForCursor(styleStateByGroup, cursor.groupId, ordinal)
                            require(
                                lookup?.selectedContent == cursor.content &&
                                    group.stagedContent == cursor.content &&
                                    group.compilationSettled &&
                                    !group.writeAcknowledged &&
                                    !group.visible &&
                                    group.allReferencingOwnersComplete,
                            ) { "style write cursor requires compilation and every referencing owner" }
                        }
                        is AwaitingStyleVisibilityInstall -> {
                            val group = styleGroupForCursor(styleStateByGroup, cursor.groupId, ordinal)
                            require(
                                lookup?.selectedContent == cursor.content &&
                                    group.stagedContent == cursor.content &&
                                    group.compilationSettled &&
                                    group.writeAcknowledged == group.requiresWrite &&
                                    !group.visible &&
                                    group.allReferencingOwnersComplete &&
                                    cursor.referencingOwnerIds == group.referencingOwnerIds,
                            ) { "style install cursor requires the complete referencing owner set" }
                        }
                    }
                }
                if (record.status == ResourceRouteStatus.RUNNING && lookup != null) {
                    require(record.cursor != null) { "started lookup requires a route cursor" }
                }
            }
            require(transportLatchSnapshot.all { it.key.route in registeredRoutes }) {
                "Transport latches must belong to registered routes"
            }
            require(assignedOrdinals.size.toLong() == nextRouteOrdinal) {
                "assigned route ordinals must form the complete contiguous range"
            }

            require(activeRouteOrdinalSnapshot.size <= definition.maximumConcurrentRoutes) {
                "active routes must not exceed configured concurrency"
            }
            val activeOrdinalSet = activeRouteOrdinalSnapshot.toSet()
            require(activeOrdinalSet.size == activeRouteOrdinalSnapshot.size) {
                "active route ordinals must be distinct"
            }
            val parkedOrdinals = mutableSetOf<Long>()
            require(
                parkedRouteSnapshot.all { parked ->
                    parked.ordinal >= nextRetirementOrdinal &&
                        parked.ordinal < nextRouteOrdinal &&
                        parkedOrdinals.add(parked.ordinal)
                },
            ) { "parked route ordinals must be distinct unretired assigned ordinals" }
            require(
                parkedRouteSnapshot.zipWithNext().all { (lower, higher) ->
                    lower.ordinal < higher.ordinal
                },
            ) { "parked routes must be ordered by ascending ordinal" }
            require(parkedOrdinals.none(activeOrdinalSet::contains)) {
                "a parked route must not occupy active capacity"
            }
            require(activeOrdinalSet + parkedOrdinals == runningOrdinals) {
                "running routes must be exactly the active and parked route ordinals"
            }
            require(
                parkedRouteSnapshot.all { parked ->
                    cursorByOrdinal[parked.ordinal]?.let(::cursorActionId) == null
                },
            ) { "a parked route must have no in-flight adapter action" }
            require(terminalSelection == null || parkedRouteSnapshot.isEmpty()) {
                "a selected terminal discards every parked route"
            }

            var previousBufferedOrdinal: Long? = null
            require(bufferedRouteOutcomeSnapshot.all { buffered ->
                val previous = previousBufferedOrdinal
                val valid = buffered.ordinal >= nextRetirementOrdinal &&
                    buffered.ordinal < nextRouteOrdinal &&
                    (previous == null || previous < buffered.ordinal)
                previousBufferedOrdinal = buffered.ordinal
                valid
            }) { "buffered route outcomes must be strictly ascending unretired assigned ordinals" }
            val bufferedOrdinals = bufferedRouteOutcomeSnapshot.mapTo(mutableSetOf()) { it.ordinal }
            require(activeOrdinalSet.none(bufferedOrdinals::contains)) {
                "active routes cannot already have buffered outcomes"
            }
            require(parkedOrdinals.none(bufferedOrdinals::contains)) {
                "parked routes cannot already have buffered outcomes"
            }

            parkedRouteSnapshot.forEach { parked ->
                when (val barrier = parked.barrier) {
                    is ParkedRouteBarrier.SpritePair -> {
                        val group = requireNotNull(spriteStateByGroup[barrier.groupId]) {
                            "a parked sprite barrier requires its group commit state"
                        }
                        val member = requireNotNull(group.memberAt(parked.ordinal)) {
                            "a parked sprite barrier must name one of its member ordinals"
                        }
                        require(group.candidate(member) != null) {
                            "a parked sprite member requires its validated candidate"
                        }
                    }
                    is ParkedRouteBarrier.StyleChildren -> {
                        val group = parkedStyleGroup(styleStateByGroup, barrier.groupId, parked.ordinal)
                        require(
                            !group.visible &&
                                !group.writeAcknowledged &&
                                (
                                    group.compilationStatus == StyleCompilationStatus.WAITING ||
                                        group.compilationStatus == StyleCompilationStatus.NOT_REQUIRED
                                    ),
                        ) { "a style parked for children awaits its compilation" }
                    }
                    is ParkedRouteBarrier.StyleOwners -> {
                        val group = parkedStyleGroup(styleStateByGroup, barrier.groupId, parked.ordinal)
                        require(group.compilationSettled && !group.visible) {
                            "a style parked for owners has already settled its compilation"
                        }
                    }
                }
            }

            styleCommitStateSnapshot.forEach { group ->
                require(group.ordinal in assignedOrdinals) {
                    "a style commit ordinal must name an assigned route"
                }
                require(selectedContentByOrdinal[group.ordinal] == group.stagedContent) {
                    "a style commit stages its route's selected content"
                }
                require(group.visible == (group.ordinal in visibleOrdinals)) {
                    "style visibility must match its route"
                }
                val joinedIds = joinedOccurrenceIdsByOrdinal[group.ordinal].orEmpty()
                require(
                    joinedIds.all { joinedId ->
                        occurrenceById[joinedId]?.commitBinding ==
                            ResourceCommitBinding.BasemapStyle(group.groupId)
                    },
                ) { "a style commit must be bound by every occurrence of its route" }
                // An installed style keeps the owner set its install carried; only a still-deciding
                // commit must agree with its route's current occurrences.
                require(
                    group.visible ||
                        group.referencingOwnerIds ==
                        joinedIds.mapNotNull { occurrenceById[it]?.ownerId }.distinct(),
                ) { "a style commit's referencing owners must be its route's bound occurrence owners" }
            }

            spriteCommitStateSnapshot.forEach { group ->
                spriteMemberOrder().forEach { member ->
                    val ordinal = group.ordinalOf(member)
                    require(ordinal in assignedOrdinals) {
                        "sprite member ordinals must name assigned routes"
                    }
                    require(
                        joinedOccurrenceIdsByOrdinal[ordinal].orEmpty().any { joinedId ->
                            occurrenceById[joinedId]?.commitBinding ==
                                ResourceCommitBinding.Sprite(group.groupId, member)
                        },
                    ) { "a sprite member ordinal must name its own bound member route" }
                    val candidate = group.candidate(member)
                    require(candidate == null || selectedContentByOrdinal[ordinal] == candidate) {
                        "a sprite candidate must be its route's selected content"
                    }
                    require(group.visible == (ordinal in visibleOrdinals)) {
                        "sprite visibility must cover both member routes"
                    }
                }
            }

            startCeilingOrdinal?.let { ceiling ->
                require(ceiling >= 0L && ceiling < nextRouteOrdinal) {
                    "start ceiling must name an assigned route ordinal"
                }
            }
            if (terminalSelection is ResourceTerminalSelection.Route) {
                require(terminalSelection.outcome !is ResourceRouteOutcome.Success) {
                    "selected route terminal must be a failure or cancellation"
                }
                require(terminalSelection.ordinal < nextRetirementOrdinal) {
                    "selected route terminal must already be retired"
                }
                require(activeOrdinalSet.all { it > terminalSelection.ordinal }) {
                    "selected route terminal may clean up only higher active routes"
                }
            }
        }

        val occurrences: List<ResourceOccurrence>
            get() = freshListCopy(occurrenceSnapshot)

        val routeRecords: List<RouteRecord>
            get() = freshListCopy(routeRecordSnapshot)

        val privateRentileKeyClaims: List<PrivateRentileKeyClaim>
            get() = freshListCopy(privateRentileKeyClaimSnapshot)

        val identityRecords: List<CanonicalIdentityRecord>
            get() = freshListCopy(identityRecordSnapshot)

        val transportLatches: List<TransportLatchRecord>
            get() = freshListCopy(transportLatchSnapshot)

        val activeRouteOrdinals: List<Long>
            get() = freshListCopy(activeRouteOrdinalSnapshot)

        val bufferedRouteOutcomes: List<BufferedRouteOutcome>
            get() = freshListCopy(bufferedRouteOutcomeSnapshot)

        val spriteCommitStates: List<SpriteCommitState>
            get() = freshListCopy(spriteCommitStateSnapshot)

        val parkedRoutes: List<ParkedRoute>
            get() = freshListCopy(parkedRouteSnapshot)

        val styleCommitStates: List<StyleCommitState>
            get() = freshListCopy(styleCommitStateSnapshot)

        val visibleResourcesByOwner: List<OwnerResourceSet>
            get() = freshListCopy(visibleResourcesByOwnerSnapshot)
    }
}

private fun styleGroupForCursor(
    styleStateByGroup: Map<StyleGroupId, StyleCommitState>,
    groupId: StyleGroupId,
    ordinal: Long,
): StyleCommitState {
    val group = requireNotNull(styleStateByGroup[groupId]) {
        "a style cursor requires its group commit state"
    }
    require(group.ordinal == ordinal) { "style group work belongs to its style route ordinal" }
    return group
}

private fun parkedStyleGroup(
    styleStateByGroup: Map<StyleGroupId, StyleCommitState>,
    groupId: StyleGroupId,
    ordinal: Long,
): StyleCommitState {
    val group = requireNotNull(styleStateByGroup[groupId]) {
        "a parked style barrier requires its group commit state"
    }
    require(group.ordinal == ordinal) { "a parked style barrier must name its style route ordinal" }
    return group
}

private fun spriteGroupForCursor(
    spriteStateByGroup: Map<SpriteGroupId, SpriteCommitState>,
    groupId: SpriteGroupId,
    ordinal: Long,
): SpriteCommitState {
    val group = requireNotNull(spriteStateByGroup[groupId]) {
        "a sprite cursor requires its group commit state"
    }
    require(group.jsonOrdinal == ordinal) {
        "sprite group work belongs to its JSON member ordinal"
    }
    return group
}

private fun cursorOrdinal(cursor: ResourceRouteCursor): Long? = when (cursor) {
    is AwaitingClockSample -> cursor.ordinal
    is AwaitingResident -> cursor.ordinal
    is AwaitingStoreRead -> cursor.ordinal
    is AwaitingTransport -> cursor.ordinal
    is AwaitingLatchedTransportReplay -> cursor.ordinal
    is PendingClassGates -> cursor.ordinal
    is PendingChildDiscovery -> cursor.ordinal
    is AwaitingClassGate -> cursor.ordinal
    is AwaitingStoreWrite -> cursor.ordinal
    is AwaitingVisibilityInstall -> cursor.ordinal
    is AwaitingStyleValidation -> cursor.ordinal
    is AwaitingStyleCompilation -> cursor.ordinal
    is AwaitingStyleWrite -> cursor.ordinal
    is AwaitingStyleVisibilityInstall -> cursor.ordinal
    is AwaitingSpritePairValidation,
    is AwaitingSpriteMemberWrite,
    is AwaitingSpriteVisibilityInstall,
    -> null
}

private fun cursorActionId(cursor: ResourceRouteCursor): ResourceActionId? = when (cursor) {
    is AwaitingClockSample -> cursor.actionId
    is AwaitingResident -> cursor.actionId
    is AwaitingStoreRead -> cursor.actionId
    is AwaitingTransport -> cursor.actionId
    is AwaitingLatchedTransportReplay -> cursor.actionId
    is PendingClassGates -> null
    is PendingChildDiscovery -> null
    is AwaitingClassGate -> cursor.actionId
    is AwaitingStoreWrite -> cursor.actionId
    is AwaitingVisibilityInstall -> cursor.actionId
    is AwaitingSpritePairValidation -> cursor.actionId
    is AwaitingSpriteMemberWrite -> cursor.actionId
    is AwaitingSpriteVisibilityInstall -> cursor.actionId
    is AwaitingStyleValidation -> cursor.actionId
    is AwaitingStyleCompilation -> cursor.actionId
    is AwaitingStyleWrite -> cursor.actionId
    is AwaitingStyleVisibilityInstall -> cursor.actionId
}

internal class ResourceOperationTransition(
    val state: ResourceOperationState.Running?,
    actions: List<ResourceOperationAction>,
    val outcome: ResourceOperationOutcome?,
) {
    private val actionSnapshot: List<ResourceOperationAction> = freshListCopy(actions)

    val actions: List<ResourceOperationAction>
        get() = freshListCopy(actionSnapshot)
}
