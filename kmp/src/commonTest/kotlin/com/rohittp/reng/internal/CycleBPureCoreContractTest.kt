package com.rohittp.reng.internal

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.Model
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.Placement
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Sticker
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.lifecycle.AdoptionContextFact
import com.rohittp.reng.internal.lifecycle.DeferredDeletion
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.ExactContextFact
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.RendererLifecycleAction
import com.rohittp.reng.internal.lifecycle.RendererLifecycleCursor
import com.rohittp.reng.internal.lifecycle.RendererLifecycleObservation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererLifecycleStateMachine
import com.rohittp.reng.internal.lifecycle.RendererLifecycleTransition
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.planning.FramePlanningCore
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.PlannedFrameCore
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.preparation.CommittedFrameHistory
import com.rohittp.reng.internal.preparation.LeaseId
import com.rohittp.reng.internal.preparation.LeaseInstallRequest
import com.rohittp.reng.internal.preparation.LeaseResource
import com.rohittp.reng.internal.preparation.OrderedPreparationAction
import com.rohittp.reng.internal.preparation.OrderedPreparationCursor
import com.rohittp.reng.internal.preparation.OrderedPreparationEvent
import com.rohittp.reng.internal.preparation.OrderedPreparationOutcome
import com.rohittp.reng.internal.preparation.OrderedPreparationState
import com.rohittp.reng.internal.preparation.OrderedPreparationStateMachine
import com.rohittp.reng.internal.preparation.OrderedPreparationTransition
import com.rohittp.reng.internal.preparation.PreparationEnvironment
import com.rohittp.reng.internal.preparation.PreparationInvocationId
import com.rohittp.reng.internal.preparation.PreparationItemId
import com.rohittp.reng.internal.resource.AdvancePendingClassGates
import com.rohittp.reng.internal.resource.AdvancePendingSpriteCommit
import com.rohittp.reng.internal.resource.AdvancePendingStyleCommit
import com.rohittp.reng.internal.resource.AwaitingClassGate
import com.rohittp.reng.internal.resource.AwaitingVisibilityInstall
import com.rohittp.reng.internal.resource.BasemapSourceMember
import com.rohittp.reng.internal.resource.BasemapStyleCompilationCompleted
import com.rohittp.reng.internal.resource.BasemapStyleCompilationOutcome
import com.rohittp.reng.internal.resource.BasemapStyleValidationCompleted
import com.rohittp.reng.internal.resource.BasemapStyleValidationOutcome
import com.rohittp.reng.internal.resource.BasemapStyleVisibilityInstallCompleted
import com.rohittp.reng.internal.resource.BasemapStyleWriteCompleted
import com.rohittp.reng.internal.resource.BufferedRouteOutcome
import com.rohittp.reng.internal.resource.CallTransport
import com.rohittp.reng.internal.resource.CancelRoute
import com.rohittp.reng.internal.resource.CancellationCause
import com.rohittp.reng.internal.resource.CancellationId
import com.rohittp.reng.internal.resource.CancellationSelection
import com.rohittp.reng.internal.resource.CanonicalIdentityRecord
import com.rohittp.reng.internal.resource.ChildrenDiscovered
import com.rohittp.reng.internal.resource.CleanupCancellationObserved
import com.rohittp.reng.internal.resource.ClockSampled
import com.rohittp.reng.internal.resource.CompileBasemapStyle
import com.rohittp.reng.internal.resource.ContentProvenance
import com.rohittp.reng.internal.resource.DiscoverChildren
import com.rohittp.reng.internal.resource.DiscoveredResourceChild
import com.rohittp.reng.internal.resource.ExternalCancellationRequested
import com.rohittp.reng.internal.resource.InstallBasemapStyleVisibility
import com.rohittp.reng.internal.resource.InstallSpriteVisibility
import com.rohittp.reng.internal.resource.InstallVisibility
import com.rohittp.reng.internal.resource.ObserveResident
import com.rohittp.reng.internal.resource.OwnerResourceSet
import com.rohittp.reng.internal.resource.ParkedRoute
import com.rohittp.reng.internal.resource.ParkedRouteBarrier
import com.rohittp.reng.internal.resource.PendingChildDiscovery
import com.rohittp.reng.internal.resource.PendingClassGates
import com.rohittp.reng.internal.resource.PrivateRentileKeyClaim
import com.rohittp.reng.internal.resource.ReadStore
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.ReplayLatchedTransport
import com.rohittp.reng.internal.resource.ResidentObserved
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceChildTraversal
import com.rohittp.reng.internal.resource.ResourceClassGate
import com.rohittp.reng.internal.resource.ResourceClassValidationCompleted
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrence
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOperationAction
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationEvent
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOperationState
import com.rohittp.reng.internal.resource.ResourceOperationStateMachine
import com.rohittp.reng.internal.resource.ResourceOperationTransition
import com.rohittp.reng.internal.resource.ResourceOwnerId
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.ResourceRouteOutcome
import com.rohittp.reng.internal.resource.ResourceRouteRegistration
import com.rohittp.reng.internal.resource.ResourceRouteStatus
import com.rohittp.reng.internal.resource.ResourceTerminalSelection
import com.rohittp.reng.internal.resource.RouteCompleted
import com.rohittp.reng.internal.resource.RouteReadyForDiscovery
import com.rohittp.reng.internal.resource.RouteRecord
import com.rohittp.reng.internal.resource.SampleClock
import com.rohittp.reng.internal.resource.SpriteCommitState
import com.rohittp.reng.internal.resource.SpriteGroupId
import com.rohittp.reng.internal.resource.SpriteJointValidationStatus
import com.rohittp.reng.internal.resource.SpriteMember
import com.rohittp.reng.internal.resource.SpriteMemberWriteCompleted
import com.rohittp.reng.internal.resource.SpritePairFailureKind
import com.rohittp.reng.internal.resource.SpritePairValidationCompleted
import com.rohittp.reng.internal.resource.SpritePairValidationOutcome
import com.rohittp.reng.internal.resource.SpriteVisibilityInstallCompleted
import com.rohittp.reng.internal.resource.StartRoute
import com.rohittp.reng.internal.resource.StoreReadCompleted
import com.rohittp.reng.internal.resource.StoreWriteCompleted
import com.rohittp.reng.internal.resource.StyleCommitState
import com.rohittp.reng.internal.resource.StyleCompilationStatus
import com.rohittp.reng.internal.resource.StyleFailureKind
import com.rohittp.reng.internal.resource.StyleGroupId
import com.rohittp.reng.internal.resource.SuppliedCallOutcome
import com.rohittp.reng.internal.resource.SuppliedInstallOutcome
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome
import com.rohittp.reng.internal.resource.TransportCompleted
import com.rohittp.reng.internal.resource.ValidateBasemapStyle
import com.rohittp.reng.internal.resource.ValidateResourceClass
import com.rohittp.reng.internal.resource.ValidateSpritePair
import com.rohittp.reng.internal.resource.VisibilityInstallCompleted
import com.rohittp.reng.internal.resource.WriteBasemapStyle
import com.rohittp.reng.internal.resource.WriteSpriteMember
import com.rohittp.reng.internal.resource.WriteStore
import com.rohittp.reng.internal.resource.ordinaryResourceClassGates
import com.rohittp.reng.internal.resource.requiresStoreWrite
import com.rohittp.reng.internal.resource.spriteMemberResourceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-engine trace proof for the Cycle B pure core. Every trace composes the shipped pure reducers —
 * [FramePlanningCore], [ResourceOperationStateMachine], [OrderedPreparationStateMachine], and
 * [RendererLifecycleStateMachine] — by hand, with fake supplied outcomes only. No orchestrator, factory,
 * adapter, Rentile call, decoder, parser, cache, render context, or GL object exists anywhere in this file.
 */
class CycleBPureCoreContractTest {
    @Test
    fun aCompleteBatchTraceCommitsSameOrderSeedsHistoryIdentityAndLodWithoutLeakingLists() {
        val resolver = RecordingPrivateKeyResolver()
        val history = committedHistory(frameIndex = 1L)
        val preparation = PreparationDriver(environment(), history, resolver)
        val firstPlan = plan(
            frameIndex = 4L,
            stickers = listOf(sticker("alpha")),
            geometries = listOf(geometry("g0")),
        )
        val secondPlan = plan(
            frameIndex = 9L,
            camera = Camera(10.0, 20.0, 3.0, 0.0, 0.0),
            stickers = listOf(sticker("alpha")),
            models = listOf(model("mesh", "skin")),
            geometries = listOf(geometry("g1")),
        )
        val submitted = mutableListOf(firstPlan, secondPlan)

        val begun = preparation.begin(submitted)
        submitted.clear()
        submitted += plan(frameIndex = 99L)

        assertEquals(
            OrderedPreparationCursor.AwaitingPlanning(PreparationItemId(0L)),
            begun.cursor,
        )
        val planning = assertIs<OrderedPreparationState.Planning>(begun.state)
        assertEquals(listOf(firstPlan, secondPlan), planning.invocation.plans)
        assertNotSame(planning.invocation.plans, planning.invocation.plans)
        assertEquals(history.encodedPlan, preparation.requestFor(0L).previousPlan)
        assertEquals(history.selectedLod, preparation.requestFor(0L).previousSelectedLod)

        val firstItem = preparation.planItem(0L)
        assertTrue(preparation.resourceActions().isEmpty(), "resources wait for the planning barrier")
        assertEquals(firstItem.encodedPlan, preparation.requestFor(1L).previousPlan)
        assertEquals(
            firstItem.spatialPlan.lodObservation.selectedLod,
            preparation.requestFor(1L).previousSelectedLod,
        )
        val secondItem = preparation.planItem(1L)
        assertEquals(
            OrderedPreparationCursor.AwaitingResources,
            requireNotNull(preparation.lastTransition).cursor,
        )

        val definition = preparation.definition()
        val plannedExternals = (firstItem.staticResourceTraversal + secondItem.staticResourceTraversal)
            .filterIsInstance<StaticResourceReference.External>()
        val occurrences = definition.staticOccurrences
        assertEquals(4, plannedExternals.size)
        assertEquals(4, occurrences.size, "only external references become resource occurrences")
        assertEquals(listOf(1L, 2L, 2L, 2L), occurrences.map { it.ownerId.value })
        occurrences.forEachIndexed { index, occurrence ->
            val reference = plannedExternals[index]
            val registration = occurrence.registration
            assertEquals(reference.resourceKey, registration.resourceKey, "identity $index")
            assertEquals(reference.rawKey, registration.rawKey, "consumer raw key $index")
            assertEquals(reference.privateRentileKey, registration.privateRentileKey, "private key $index")
            assertEquals(reference.canonicalIdentity.canonicalBytes, registration.canonicalBytes)
            assertEquals(
                reference.canonicalIdentity.digest.lowercaseHex,
                registration.resourceKey.stableId,
                "canonical identity $index",
            )
            assertEquals(registration.resourceKey.stableId, registration.rawKey.stableId)
            assertEquals(ResourceKind.EXTERNAL, registration.resourceKey.kind)
            assertEquals("RentilePrivateKey(<redacted>)", registration.privateRentileKey.toString())
            assertEquals(ResourceAccessMode.NORMAL, registration.route.accessMode)
        }
        assertEquals(
            occurrences[0].registration.route,
            occurrences[1].registration.route,
            "both items share one sticker route across two owners",
        )
        assertEquals(
            occurrences[0].registration.privateRentileKey,
            occurrences[1].registration.privateRentileKey,
        )
        assertNotEquals(
            occurrences[0].registration.privateRentileKey,
            occurrences[2].registration.privateRentileKey,
        )
        assertEquals(
            listOf(
                ResourceLocator("alpha.png") to ResourceClass.STICKER_IMAGE,
                ResourceLocator("alpha.png") to ResourceClass.STICKER_IMAGE,
                ResourceLocator("mesh.glb") to ResourceClass.MODEL_GLB,
                ResourceLocator("skin.png") to ResourceClass.MODEL_TEXTURE,
            ),
            resolver.calls,
            "geometry programs never resolve a private Rentile key",
        )
        assertEquals(6, definition.resourceIdentities.size)
        assertEquals(
            2,
            definition.resourceIdentities.count { it.resourceKey.kind == ResourceKind.GEOMETRY_PROGRAM },
            "geometry program identities are collision-checked without any Store or Transport work",
        )

        val resources = ResourceDriver(definition)
        assertEquals(listOf(0L, 1L, 2L), resources.state.activeRouteOrdinals)
        assertEquals(
            listOf(occurrences[0].id, occurrences[1].id),
            resources.record(0L).joinedOccurrenceIds,
            "one route joins both items' shared sticker occurrences",
        )
        assertEquals(5, resources.state.identityRecords.size, "equal identities collapse to one record")

        resources.driveOrdinaryRoute(0L, ContentProvenance.RESIDENT)
        resources.driveOrdinaryRoute(1L, ContentProvenance.STORE)
        resources.driveOrdinaryRoute(2L, ContentProvenance.TRANSPORT_200)

        assertEquals(
            listOf(
                ResourceClassGate.DECODE_PNG,
                ResourceClassGate.PARSE_GLB,
                ResourceClassGate.VALIDATE_GLB_FEATURES,
                ResourceClassGate.DECODE_PNG,
            ),
            resources.emitted.filterIsInstance<ValidateResourceClass>().map { it.gate },
        )
        assertEquals(
            listOf(2L),
            resources.emitted.filterIsInstance<WriteStore>().map { it.ordinal },
            "only transported content is written back",
        )
        assertEquals(3L, resources.state.nextRetirementOrdinal)
        val resourceSuccess = assertIs<ResourceOperationOutcome.Success>(resources.outcome)
        assertEquals(listOf(1L, 2L), resourceSuccess.resourceSets.map { it.ownerId.value })
        val stickerVisible = resourceSuccess.resourceSets[0].resources.single()
        val secondOwnerVisible = resourceSuccess.resourceSets[1].resources
        assertEquals(3, secondOwnerVisible.size)
        assertEquals(stickerVisible, secondOwnerVisible[0])
        assertEquals(
            listOf(
                ContentProvenance.RESIDENT,
                ContentProvenance.STORE,
                ContentProvenance.TRANSPORT_200,
            ),
            secondOwnerVisible.map { it.content.provenance },
        )

        val installing = preparation.submit(OrderedPreparationEvent.ResourcesCompleted(resourceSuccess))
        val expectedInstalls = listOf(
            LeaseInstallRequest(PreparationItemId(0L), 0, LeaseResource.External(stickerVisible)),
            LeaseInstallRequest(
                PreparationItemId(0L),
                1,
                LeaseResource.PlannedLogical(programKey(firstItem)),
            ),
            LeaseInstallRequest(PreparationItemId(1L), 0, LeaseResource.External(secondOwnerVisible[0])),
            LeaseInstallRequest(PreparationItemId(1L), 1, LeaseResource.External(secondOwnerVisible[1])),
            LeaseInstallRequest(PreparationItemId(1L), 2, LeaseResource.External(secondOwnerVisible[2])),
            LeaseInstallRequest(
                PreparationItemId(1L),
                3,
                LeaseResource.PlannedLogical(programKey(secondItem)),
            ),
        )
        assertEquals(
            OrderedPreparationCursor.AwaitingLeaseInstall(expectedInstalls.first()),
            installing.cursor,
        )

        val settled = preparation.installAllLeases()

        assertEquals(expectedInstalls, preparation.installs())
        assertEquals(listOf(1, 1, 1, 1, 1, 1), preparation.installActionCounts())
        assertTrue(preparation.releases().isEmpty(), "a complete success rolls nothing back")
        val outcome = assertIs<OrderedPreparationOutcome.Success>(settled.outcome)
        val seeds = outcome.frameSeeds
        assertEquals(listOf(PreparationItemId(0L), PreparationItemId(1L)), seeds.map { it.itemId })
        assertEquals(listOf(4L, 9L), seeds.map { it.frameIndex })
        assertEquals(firstItem, seeds[0].plannedFrame)
        assertEquals(secondItem, seeds[1].plannedFrame)
        assertEquals(listOf(LeaseId(1L), LeaseId(2L)), seeds[0].leases.map { it.leaseId })
        assertEquals(
            listOf(LeaseId(3L), LeaseId(4L), LeaseId(5L), LeaseId(6L)),
            seeds[1].leases.map { it.leaseId },
        )
        assertEquals(listOf(0, 1), seeds[0].leases.map { it.request.traversalIndex })
        assertEquals(listOf(0, 1, 2, 3), seeds[1].leases.map { it.request.traversalIndex })
        val idle = assertIs<OrderedPreparationState.Idle>(settled.state)
        assertEquals(
            CommittedFrameHistory(
                frameIndex = 9L,
                encodedPlan = secondItem.encodedPlan,
                selectedLod = secondItem.spatialPlan.lodObservation.selectedLod,
            ),
            idle.history,
            "history commits once, atomically, from the batch's final item",
        )
        assertNotEquals(history, idle.history)

        assertFreshList(outcome.frameSeeds, outcome.frameSeeds, seeds)
        assertFreshList(seeds[1].leases, seeds[1].leases, seeds[1].leases)
        assertFreshList(definition.staticOccurrences, definition.staticOccurrences, occurrences)
        assertFreshList(
            secondItem.staticResourceTraversal,
            secondItem.staticResourceTraversal,
            secondItem.staticResourceTraversal,
        )
        assertFreshList(
            resourceSuccess.resourceSets,
            resourceSuccess.resourceSets,
            resourceSuccess.resourceSets,
        )
    }

    @Test
    fun anInvalidSecondPlanEmitsZeroResourceActions() {
        val resolver = RecordingPrivateKeyResolver()
        val history = committedHistory(frameIndex = 1L)
        val preparation = PreparationDriver(environment(), history, resolver)
        preparation.begin(
            listOf(
                plan(frameIndex = 4L, stickers = listOf(sticker("alpha"))),
                // A GLOBE plan whose camera is outside Mercator's latitude clip. It carried no
                // camera of its own until Cycle G task 10, when GLOBE stopped being the invalidity:
                // the mode now reaches `planGlobeSpatial`, which shares the Mercator domain, so the
                // plan has to be invalid for a reason a globe frame can actually have.
                plan(
                    frameIndex = 9L,
                    camera = Camera(90.0, 0.0, 0.0, 0.0, 0.0),
                    projectionMode = ProjectionMode.GLOBE,
                    stickers = listOf(sticker("beta")),
                ),
            ),
        )

        val firstItem = preparation.planItem(0L)
        assertEquals(1, firstItem.staticResourceTraversal.size, "the first item really planned")
        assertEquals(
            listOf(ResourceLocator("alpha.png") to ResourceClass.STICKER_IMAGE),
            resolver.calls,
        )
        assertEquals(
            listOf(PreparationItemId(0L), PreparationItemId(1L)),
            preparation.planningItemIds(),
            "planning advanced to the second item",
        )
        assertTrue(preparation.resourceActions().isEmpty())

        val settled = preparation.failItem(1L)

        val failure = assertIs<OrderedPreparationOutcome.Failure>(settled.outcome)
        assertEquals(RenGErrorCode.INVALID_VALUE, failure.failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.failure.stage)
        assertNull(settled.cursor)
        assertEquals(OrderedPreparationState.Idle(history), settled.state, "history is untouched")
        assertTrue(preparation.resourceActions().isEmpty(), "an invalid plan runs zero resource work")
        assertTrue(preparation.installs().isEmpty())
        assertTrue(preparation.releases().isEmpty())
        assertTrue(
            preparation.emitted.none {
                it is OrderedPreparationAction.RequestResourceCancellation
            },
        )
        assertEquals(
            listOf(ResourceLocator("alpha.png") to ResourceClass.STICKER_IMAGE),
            resolver.calls,
            "the rejected plan resolves no private key",
        )
    }

    @Test
    fun oneSuppliedPrivateKeyFailsAmbiguousBeforeWorkWhileSharedRawKeysDoNot() {
        val collapsed = "one-supplied-private-key"
        val resolver = RecordingPrivateKeyResolver(
            mapOf(
                ("alpha.png" to ResourceClass.STICKER_IMAGE) to collapsed,
                ("beta.png" to ResourceClass.STICKER_IMAGE) to collapsed,
            ),
        )
        val preparation = PreparationDriver(environment(), null, resolver)
        preparation.begin(
            listOf(plan(frameIndex = 2L, stickers = listOf(sticker("alpha"), sticker("beta")))),
        )
        preparation.planItem(0L)
        val ambiguous = preparation.definition().staticOccurrences
        assertEquals(2, ambiguous.size)
        assertNotEquals(ambiguous[0].registration.route, ambiguous[1].registration.route)
        assertNotEquals(ambiguous[0].registration.rawKey, ambiguous[1].registration.rawKey)
        assertNotEquals(ambiguous[0].registration.resourceKey, ambiguous[1].registration.resourceKey)
        assertEquals(
            ambiguous[0].registration.privateRentileKey,
            ambiguous[1].registration.privateRentileKey,
        )

        val transition = ResourceOperationStateMachine.start(preparation.definition())

        val failure = assertIs<ResourceOperationOutcome.Failure>(transition.outcome)
        assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.failure.code)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, failure.failure.stage)
        assertEquals(DiagnosticField.RESOURCE.wireName, failure.failure.diagnostic?.fieldName)
        assertTrue(transition.actions.isEmpty(), "ambiguity fails before any route starts")
        val blocked = requireNotNull(transition.state)
        assertEquals(0L, blocked.nextRouteOrdinal)
        assertTrue(blocked.routeRecords.all { it.ordinal == null })
        assertEquals(
            listOf(ResourceRouteStatus.PREREGISTERED, ResourceRouteStatus.BLOCKED_BY_COLLISION),
            blocked.routeRecords.map(RouteRecord::status),
        )
        assertTrue(blocked.privateRentileKeyClaims.none(PrivateRentileKeyClaim::usable))
        val settled = preparation.submit(OrderedPreparationEvent.ResourcesCompleted(failure))
        assertEquals(
            OrderedPreparationOutcome.Failure(failure.failure),
            settled.outcome,
            "the ambiguity terminal reaches the preparation reducer unchanged",
        )
        assertTrue(preparation.installs().isEmpty())

        val sharedRawKey = RawResourceKey(hexId("shared-raw-key"), ResourceClass.STICKER_IMAGE)
        val firstRoute = registration(
            marker = "raw-first",
            resourceClass = ResourceClass.STICKER_IMAGE,
            accessMode = ResourceAccessMode.RELOAD,
            rawKey = sharedRawKey,
        )
        val secondRoute = registration(
            marker = "raw-second",
            resourceClass = ResourceClass.STICKER_IMAGE,
            accessMode = ResourceAccessMode.RELOAD,
            rawKey = sharedRawKey,
        )
        assertEquals(firstRoute.rawKey, secondRoute.rawKey)
        assertNotEquals(firstRoute.privateRentileKey, secondRoute.privateRentileKey)

        val sharedRaw = ResourceOperationStateMachine.start(
            definitionOf(
                concurrency = 2,
                occurrences = listOf(
                    occurrence(1L, 1L, firstRoute),
                    occurrence(2L, 2L, secondRoute),
                ),
            ),
        )

        assertNull(sharedRaw.outcome, "equal consumer raw keys alone are never ambiguous")
        assertEquals(
            listOf(StartRoute(0L, firstRoute), StartRoute(1L, secondRoute)),
            sharedRaw.actions,
        )
        assertTrue(requireNotNull(sharedRaw.state).privateRentileKeyClaims.all(PrivateRentileKeyClaim::usable))
    }

    @Test
    fun aDynamicChildJoiningALaterPreregisteredRouteTakesTheEarlierOrdinal() {
        val parent = occurrence(
            id = 1L,
            ownerId = 1L,
            registration = registration("parent", ResourceClass.MODEL_TEXTURE, ResourceAccessMode.RELOAD),
            discoveryRequired = true,
        )
        val sharedStatic = occurrence(
            id = 2L,
            ownerId = 1L,
            registration = registration("shared", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
        )
        val tailStatic = occurrence(
            id = 3L,
            ownerId = 1L,
            registration = registration("tail", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
        )
        val dynamicChild = occurrence(id = 4L, ownerId = 2L, registration = sharedStatic.registration)
        val driver = ResourceDriver(
            definitionOf(concurrency = 4, occurrences = listOf(parent, sharedStatic, tailStatic)),
        )

        assertEquals(listOf(StartRoute(0L, parent.registration)), driver.actions)
        assertTrue(
            driver.state.routeRecords.filter { it.registration.route != parent.registration.route }
                .all { it.ordinal == null && it.status == ResourceRouteStatus.PREREGISTERED },
            "later static routes stay preregistered behind the discovery frontier",
        )

        driver.driveToPendingContent(0L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingClassGates(0L))
        val gate = assertIs<ValidateResourceClass>(driver.actions.single())
        assertEquals(ResourceClassGate.DECODE_PNG, gate.gate)
        driver.event(ResourceClassValidationCompleted(gate.actionId, SuppliedValidationOutcome.Valid))
        val write = assertIs<WriteStore>(driver.actions.single())
        assertEquals(parent.registration.rawKey, write.rawKey)
        driver.event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
        assertTrue(driver.record(0L).storeWriteAcknowledged)
        val install = assertIs<InstallVisibility>(driver.actions.single())
        driver.event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded))

        assertTrue(driver.actions.isEmpty(), "a discovery parent announces nothing before readiness")
        assertEquals(
            PendingChildDiscovery(0L, driver.candidate(0L)),
            driver.record(0L).cursor,
            "the parent installs its own content and stays running",
        )
        assertTrue(driver.record(0L).visibilityInstalled)
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(0L).status)
        assertEquals(0L, driver.state.nextRetirementOrdinal)

        driver.event(RouteReadyForDiscovery(0L, parent.id))

        assertEquals(listOf(DiscoverChildren(0L, parent.id)), driver.actions)
        assertEquals(1L, driver.state.nextRetirementOrdinal)

        driver.event(
            ChildrenDiscovered(
                parent.id,
                listOf(
                    DiscoveredResourceChild(
                        traversal = ResourceChildTraversal.BasemapSource(
                            "alpha",
                            BasemapSourceMember.Metadata,
                        ),
                        occurrence = dynamicChild,
                    ),
                ),
            ),
        )

        val sharedRecord = driver.state.routeRecords
            .single { it.registration.route == sharedStatic.registration.route }
        val tailRecord = driver.state.routeRecords
            .single { it.registration.route == tailStatic.registration.route }
        assertEquals(1L, sharedRecord.ordinal, "the dynamic child claims the earlier ordinal")
        assertEquals(2L, tailRecord.ordinal)
        assertEquals(listOf(sharedStatic.id, dynamicChild.id), sharedRecord.joinedOccurrenceIds)
        assertEquals(
            listOf(
                StartRoute(1L, sharedStatic.registration),
                StartRoute(2L, tailStatic.registration),
            ),
            driver.actions,
        )

        driver.driveOrdinaryRoute(1L, ContentProvenance.TRANSPORT_200)
        driver.driveOrdinaryRoute(2L, ContentProvenance.TRANSPORT_200)

        val success = assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        assertEquals(listOf(1L, 2L), success.resourceSets.map { it.ownerId.value })
        assertEquals(3L, driver.state.nextRetirementOrdinal)
    }

    @Test
    fun theEarliestOrdinalFailureBeatsReverseCompletionAndReachesPreparation() {
        val driver = ResourceDriver(threeRouteDefinition(concurrency = 3))
        val laterFailure = routeFailure(
            RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
            PipelineStage.TRANSPORT,
            "later-ordinal",
        )
        val earlierFailure = routeFailure(
            RenGErrorCode.STORE_READ_FAILED,
            PipelineStage.STORE_READ,
            "earlier-ordinal",
        )
        assertEquals(listOf(0L, 1L, 2L), driver.state.activeRouteOrdinals)

        driver.event(RouteCompleted(2L, ResourceRouteOutcome.Failure(laterFailure)))
        assertNull(driver.outcome)
        assertEquals(2L, driver.state.startCeilingOrdinal)
        assertEquals(0L, driver.state.nextRetirementOrdinal)

        driver.event(RouteCompleted(1L, ResourceRouteOutcome.Failure(earlierFailure)))
        assertNull(driver.outcome)
        assertEquals(1L, driver.state.startCeilingOrdinal)
        assertEquals(
            listOf(
                BufferedRouteOutcome(1L, ResourceRouteOutcome.Failure(earlierFailure)),
                BufferedRouteOutcome(2L, ResourceRouteOutcome.Failure(laterFailure)),
            ),
            driver.state.bufferedRouteOutcomes,
        )

        driver.event(RouteCompleted(0L, ResourceRouteOutcome.Success))

        val failure = assertIs<ResourceOperationOutcome.Failure>(driver.outcome)
        assertEquals(earlierFailure, failure.failure, "the earliest ordinal wins, not the first observed")
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Failure(earlierFailure)),
            driver.state.terminalSelection,
        )
        assertEquals(2L, driver.state.nextRetirementOrdinal)

        val preparation = preparationAwaitingResources()
        val settled = preparation.submit(OrderedPreparationEvent.ResourcesCompleted(failure))

        assertEquals(OrderedPreparationOutcome.Failure(earlierFailure), settled.outcome)
        assertEquals(
            OrderedPreparationState.Idle(preparation.initialHistory),
            settled.state,
            "a failed batch never commits history",
        )
        assertTrue(preparation.installs().isEmpty())
    }

    @Test
    fun everyStoreSourcedGateFailureIsStoreIntegrityWithoutTransportWriteOrRemoval() {
        val ordinary = ResourceDriver(
            definitionOf(
                concurrency = 1,
                occurrences = listOf(
                    occurrence(
                        1L,
                        1L,
                        registration(
                            "store-ordinary",
                            ResourceClass.STICKER_IMAGE,
                            ResourceAccessMode.CACHE_ONLY,
                        ),
                    ),
                ),
            ),
        )
        ordinary.driveToPendingContent(0L, ContentProvenance.STORE)
        ordinary.event(AdvancePendingClassGates(0L))
        val ordinaryGate = assertIs<ValidateResourceClass>(ordinary.actions.single())
        assertEquals(ResourceClassGate.DECODE_PNG, ordinaryGate.gate)
        val ordinaryContent = ordinary.candidate(0L)
        ordinary.event(
            ResourceClassValidationCompleted(ordinaryGate.actionId, SuppliedValidationOutcome.Failed),
        )
        assertStoreIntegrity(ordinary, ordinaryContent, "ordinary class gate")

        val sprite = ResourceDriver(
            definitionOf(
                concurrency = 2,
                occurrences = listOf(
                    spriteOccurrence(
                        1L,
                        OWNER_ONE,
                        SPRITE_GROUP,
                        SpriteMember.JSON,
                        "store-json",
                        ResourceAccessMode.CACHE_ONLY,
                    ),
                    spriteOccurrence(
                        2L,
                        OWNER_ONE,
                        SPRITE_GROUP,
                        SpriteMember.IMAGE,
                        "store-image",
                        ResourceAccessMode.CACHE_ONLY,
                    ),
                ),
            ),
        )
        sprite.driveToPendingContent(0L, ContentProvenance.STORE)
        sprite.event(AdvancePendingSpriteCommit(0L))
        sprite.driveToPendingContent(1L, ContentProvenance.STORE)
        sprite.event(AdvancePendingSpriteCommit(1L))
        val pair = assertIs<ValidateSpritePair>(sprite.actions.single())
        val jsonContent = sprite.candidate(0L)
        assertEquals(jsonContent, pair.json)
        assertEquals(sprite.candidate(1L), pair.image)
        assertEquals(
            SpriteJointValidationStatus.REQUESTED,
            sprite.group(SPRITE_GROUP).jointValidationStatus,
        )
        sprite.event(
            SpritePairValidationCompleted(
                pair.actionId,
                SpritePairValidationOutcome.Failed(SpriteMember.JSON, SpritePairFailureKind.JSON_PARSE),
            ),
        )
        assertStoreIntegrity(sprite, jsonContent, "sprite pair gate")
        assertFalse(sprite.group(SPRITE_GROUP).visible)

        val styleValidation = storeStyleDriver()
        val validation = assertIs<ValidateBasemapStyle>(styleValidation.actions.single())
        val styleContent = styleValidation.candidate(0L)
        styleValidation.event(
            BasemapStyleValidationCompleted(
                validation.actionId,
                BasemapStyleValidationOutcome.Failed(StyleFailureKind.PARSE),
            ),
        )
        assertStoreIntegrity(styleValidation, styleContent, "style validation gate")
        assertFalse(styleValidation.style(STYLE_GROUP).visible)

        val styleCompilation = storeStyleDriver()
        val secondValidation = assertIs<ValidateBasemapStyle>(styleCompilation.actions.single())
        val compiledContent = styleCompilation.candidate(0L)
        styleCompilation.event(
            BasemapStyleValidationCompleted(
                secondValidation.actionId,
                BasemapStyleValidationOutcome.Valid(emptyList()),
            ),
        )
        val compile = assertIs<CompileBasemapStyle>(styleCompilation.actions.single())
        styleCompilation.event(
            BasemapStyleCompilationCompleted(
                compile.actionId,
                BasemapStyleCompilationOutcome.Failed(StyleFailureKind.UNSUPPORTED_FEATURE),
            ),
        )
        assertStoreIntegrity(styleCompilation, compiledContent, "style compilation gate")
        assertEquals(
            StyleCompilationStatus.FAILED,
            styleCompilation.style(STYLE_GROUP).compilationStatus,
        )
        assertFalse(styleCompilation.style(STYLE_GROUP).visible)
    }

    @Test
    fun reverseAdapterCancellationsPropagateTheLowerRetiredOpaqueId() {
        val driver = ResourceDriver(threeRouteDefinition(concurrency = 3))
        val higher = CancellationSelection(CancellationCause.ADAPTER, CancellationId(77L))
        val lower = CancellationSelection(CancellationCause.ADAPTER, CancellationId(42L))

        driver.event(RouteCompleted(2L, ResourceRouteOutcome.Cancelled(higher)))
        assertNull(driver.outcome)
        assertEquals(2L, driver.state.startCeilingOrdinal)

        driver.event(RouteCompleted(1L, ResourceRouteOutcome.Cancelled(lower)))
        assertNull(driver.outcome)
        assertEquals(
            listOf(1L, 2L),
            driver.state.bufferedRouteOutcomes.map(BufferedRouteOutcome::ordinal),
        )

        driver.event(RouteCompleted(0L, ResourceRouteOutcome.Success))

        val cancelled = assertIs<ResourceOperationOutcome.Cancelled>(driver.outcome)
        assertEquals(lower, cancelled.cancellation)
        assertEquals(42L, cancelled.cancellation.id.value)
        assertEquals(CancellationCause.ADAPTER, cancelled.cancellation.cause)
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Cancelled(lower)),
            driver.state.terminalSelection,
        )

        val preparation = preparationAwaitingResources()
        val settled = preparation.submit(OrderedPreparationEvent.ResourcesCompleted(cancelled))

        val outcome = assertIs<OrderedPreparationOutcome.Cancelled>(settled.outcome)
        assertEquals(lower, outcome.cancellation)
        assertEquals(42L, outcome.cancellation.id.value)
        assertEquals(CancellationCause.ADAPTER, outcome.cancellation.cause)
        assertEquals(OrderedPreparationState.Idle(preparation.initialHistory), settled.state)
        assertTrue(preparation.installs().isEmpty())
    }

    @Test
    fun externalCancellationClaimsTheTerminalBeforeAnUnretiredFailureAndKeepsItsOwnId() {
        val driver = ResourceDriver(threeRouteDefinition(concurrency = 3))
        val unretiredFailure = routeFailure(
            RenGErrorCode.RESOURCE_UNAVAILABLE,
            PipelineStage.RESOURCE_LOOKUP,
            "unretired",
            DiagnosticField.RESOURCE,
        )
        val caller = CancellationSelection(CancellationCause.CALLER, CancellationId(909L))

        driver.event(RouteCompleted(1L, ResourceRouteOutcome.Failure(unretiredFailure)))
        assertNull(driver.state.terminalSelection, "the failure is buffered but unretired")
        assertEquals(0L, driver.state.nextRetirementOrdinal)

        driver.event(ExternalCancellationRequested(caller))

        assertEquals(ResourceTerminalSelection.External(caller), driver.state.terminalSelection)
        assertEquals(listOf(CancelRoute(0L), CancelRoute(2L)), driver.actions)
        assertEquals(
            listOf(BufferedRouteOutcome(1L, ResourceRouteOutcome.Failure(unretiredFailure))),
            driver.state.bufferedRouteOutcomes,
        )

        driver.event(CleanupCancellationObserved(0L))
        assertNull(driver.outcome)
        driver.event(CleanupCancellationObserved(2L))

        val cancelled = assertIs<ResourceOperationOutcome.Cancelled>(driver.outcome)
        assertEquals(caller, cancelled.cancellation)
        assertEquals(909L, cancelled.cancellation.id.value)
        assertEquals(CancellationCause.CALLER, cancelled.cancellation.cause)
        assertEquals(0L, driver.state.nextRetirementOrdinal, "the failure never retired")

        val preparation = preparationAwaitingResources()
        val requested = preparation.submit(OrderedPreparationEvent.CancellationRequested(caller))
        assertEquals(
            listOf(
                OrderedPreparationAction.RequestResourceCancellation(
                    invocationId = PreparationInvocationId(1L),
                    cancellation = caller,
                ),
            ),
            requested.actions,
        )
        assertEquals(OrderedPreparationCursor.AwaitingResourceCancellation(caller), requested.cursor)

        val settled = preparation.submit(OrderedPreparationEvent.ResourcesCompleted(cancelled))

        val outcome = assertIs<OrderedPreparationOutcome.Cancelled>(settled.outcome)
        assertEquals(caller, outcome.cancellation)
        assertEquals(909L, outcome.cancellation.id.value)
        assertTrue(preparation.installs().isEmpty())
    }

    @Test
    fun concurrencyOneParksSpriteAndStyleBarriersAndResumesLowestReadyFirst() {
        val spriteJson = spriteOccurrence(1L, OWNER_ONE, SPRITE_GROUP, SpriteMember.JSON, "atlas-json")
        val spriteImage = spriteOccurrence(2L, OWNER_ONE, SPRITE_GROUP, SpriteMember.IMAGE, "atlas-image")
        val styleRegistration = registration(
            marker = "style",
            resourceClass = ResourceClass.BASEMAP_STYLE,
            accessMode = ResourceAccessMode.RELOAD,
        )
        val styleOwnerOne = ResourceOccurrence(
            id = ResourceOccurrenceId(3L),
            ownerId = ResourceOwnerId(OWNER_ONE),
            registration = styleRegistration,
            discoveryRequired = true,
            commitBinding = ResourceCommitBinding.BasemapStyle(STYLE_GROUP),
        )
        val styleOwnerTwo = ResourceOccurrence(
            id = ResourceOccurrenceId(4L),
            ownerId = ResourceOwnerId(OWNER_TWO),
            registration = styleRegistration,
            discoveryRequired = false,
            commitBinding = ResourceCommitBinding.BasemapStyle(STYLE_GROUP),
        )
        val ownerTwoSticker = occurrence(
            id = 5L,
            ownerId = OWNER_TWO,
            registration = registration("owner-two", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
        )
        // A second resource of the style's own owner, traversed after the style. It used to be a
        // discovered child of the style; a validated style now announces a route manifest instead, and
        // its siblings are ordinary static resources ordered against it by the owner barrier.
        val ownerOneSibling = occurrence(
            id = 6L,
            ownerId = OWNER_ONE,
            registration = registration(
                "owner-one-sibling",
                ResourceClass.MODEL_TEXTURE,
                ResourceAccessMode.RELOAD,
            ),
        )
        val driver = ResourceDriver(
            definitionOf(
                concurrency = 1,
                occurrences = listOf(
                    spriteJson,
                    spriteImage,
                    styleOwnerOne,
                    styleOwnerTwo,
                    ownerOneSibling,
                    ownerTwoSticker,
                ),
            ),
        )
        assertEquals(listOf(StartRoute(0L, spriteJson.registration)), driver.actions)

        driver.driveToPendingContent(0L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingSpriteCommit(0L))

        assertEquals(
            listOf(ParkedRoute(0L, ParkedRouteBarrier.SpritePair(SPRITE_GROUP))),
            driver.parked,
            "the sprite JSON member parks on its pair barrier",
        )
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(0L).status)
        assertEquals(0L, driver.state.nextRetirementOrdinal, "a parked route never retires")
        assertEquals(listOf(1L), driver.state.activeRouteOrdinals, "capacity goes to the pair member")
        assertEquals(listOf(StartRoute(1L, spriteImage.registration)), driver.actions)

        driver.driveToPendingContent(1L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingSpriteCommit(1L))

        val pairValidation = assertIs<ValidateSpritePair>(driver.actions.single())
        assertEquals(
            listOf(0L),
            driver.state.activeRouteOrdinals,
            "the lowest ready parked route resumes before any new eligible route starts",
        )
        assertEquals(
            listOf(ParkedRoute(1L, ParkedRouteBarrier.SpritePair(SPRITE_GROUP))),
            driver.parked,
        )
        assertEquals(ResourceRouteStatus.ELIGIBLE, driver.record(2L).status)
        assertTrue(driver.emitted.filterIsInstance<StartRoute>().none { it.ordinal == 2L })

        driver.completeSpritePair(pairValidation, SPRITE_GROUP, jsonOrdinal = 0L, imageOrdinal = 1L)

        assertEquals(2L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.parked.isEmpty())
        assertEquals(listOf(2L), driver.state.activeRouteOrdinals)

        driver.driveToPendingContent(2L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingStyleCommit(2L))
        val styleValidation = assertIs<ValidateBasemapStyle>(driver.actions.single())
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE), ResourceOwnerId(OWNER_TWO)),
            driver.style(STYLE_GROUP).referencingOwnerIds,
            "both bound style occurrences reference the one style route",
        )
        assertEquals(
            emptyList(),
            driver.style(STYLE_GROUP).ownersWithCompletedNonStyleWork,
            "the sprite owner still has its own sibling resource outstanding",
        )

        driver.event(
            BasemapStyleValidationCompleted(
                styleValidation.actionId,
                BasemapStyleValidationOutcome.Valid(emptyList()),
            ),
        )

        // A route manifest leaves no child to wait for, so the children barrier is satisfied in the
        // very transition it is entered and the style keeps its slot straight through to compilation.
        val compilation = assertIs<CompileBasemapStyle>(driver.actions.single())
        assertTrue(driver.parked.isEmpty())
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(2L).status)
        assertEquals(2L, driver.state.nextRetirementOrdinal, "the compiling style never retires")
        assertEquals(listOf(2L), driver.state.activeRouteOrdinals, "the style keeps the single slot")
        assertEquals(ResourceRouteStatus.ELIGIBLE, driver.record(3L).status)
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE), ResourceOwnerId(OWNER_TWO)),
            driver.style(STYLE_GROUP).referencingOwnerIds,
        )

        driver.event(
            BasemapStyleCompilationCompleted(compilation.actionId, BasemapStyleCompilationOutcome.Succeeded),
        )

        assertEquals(
            listOf(ParkedRoute(2L, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))),
            driver.parked,
            "the style parks on its owner barrier",
        )
        assertEquals(
            emptyList(),
            driver.style(STYLE_GROUP).ownersWithCompletedNonStyleWork,
            "neither owner has installed all of its non-style work yet",
        )
        assertEquals(listOf(3L), driver.state.activeRouteOrdinals, "capacity goes to the owner route")
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        assertEquals(2L, driver.state.nextRetirementOrdinal)

        driver.driveOrdinaryRoute(3L, ContentProvenance.TRANSPORT_200)
        assertTrue(
            driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty(),
            "one owner's work is not the whole barrier",
        )
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE)),
            driver.style(STYLE_GROUP).ownersWithCompletedNonStyleWork,
        )

        driver.driveOrdinaryRoute(4L, ContentProvenance.TRANSPORT_200)

        val styleWrite = assertIs<WriteBasemapStyle>(driver.actions.single())
        assertEquals(listOf(2L), driver.state.activeRouteOrdinals)
        driver.event(
            BasemapStyleWriteCompleted(styleWrite.actionId, STYLE_GROUP, SuppliedCallOutcome.Success(Unit)),
        )
        val styleInstall = assertIs<InstallBasemapStyleVisibility>(driver.actions.single())
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE), ResourceOwnerId(OWNER_TWO)),
            styleInstall.referencingOwnerIds,
        )
        driver.event(
            BasemapStyleVisibilityInstallCompleted(
                styleInstall.actionId,
                STYLE_GROUP,
                SuppliedInstallOutcome.Succeeded,
            ),
        )

        assertEquals(5L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.state.activeRouteOrdinals.isEmpty())
        assertTrue(driver.parked.isEmpty())
        assertTrue(driver.style(STYLE_GROUP).visible)
        assertTrue(driver.group(SPRITE_GROUP).visible)
        assertEquals(1, driver.maximumActiveRoutes, "every action stayed inside one concurrency slot")
        val success = assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE), ResourceOwnerId(OWNER_TWO)),
            success.resourceSets.map(OwnerResourceSet::ownerId),
        )
        assertEquals(4, success.resourceSets[0].resources.size)
        assertEquals(2, success.resourceSets[1].resources.size)
    }

    @Test
    fun aTransportedStyleWritesAndInstallsOnlyAfterCompilationAndEveryOwnersInstalledWork() {
        val styleRegistration = registration(
            marker = "barrier-style",
            resourceClass = ResourceClass.BASEMAP_STYLE,
            accessMode = ResourceAccessMode.RELOAD,
        )
        val driver = ResourceDriver(
            definitionOf(
                concurrency = 3,
                occurrences = listOf(
                    ResourceOccurrence(
                        id = ResourceOccurrenceId(1L),
                        ownerId = ResourceOwnerId(OWNER_ONE),
                        registration = styleRegistration,
                        discoveryRequired = false,
                        commitBinding = ResourceCommitBinding.BasemapStyle(STYLE_GROUP),
                    ),
                    ResourceOccurrence(
                        id = ResourceOccurrenceId(2L),
                        ownerId = ResourceOwnerId(OWNER_TWO),
                        registration = styleRegistration,
                        discoveryRequired = false,
                        commitBinding = ResourceCommitBinding.BasemapStyle(STYLE_GROUP),
                    ),
                    occurrence(
                        3L,
                        OWNER_ONE,
                        registration("barrier-one", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
                    ),
                    occurrence(
                        4L,
                        OWNER_TWO,
                        registration("barrier-two", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
                    ),
                ),
            ),
        )

        driver.driveToPendingContent(0L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingStyleCommit(0L))
        val validation = assertIs<ValidateBasemapStyle>(driver.actions.single())
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE), ResourceOwnerId(OWNER_TWO)),
            driver.style(STYLE_GROUP).referencingOwnerIds,
        )
        assertTrue(driver.style(STYLE_GROUP).ownersWithCompletedNonStyleWork.isEmpty())
        driver.event(
            BasemapStyleValidationCompleted(
                validation.actionId,
                BasemapStyleValidationOutcome.Valid(emptyList()),
            ),
        )
        val compilation = assertIs<CompileBasemapStyle>(driver.actions.single())
        assertNoStyleWriteOrInstall(driver, "after validation")

        driver.driveOrdinaryRoute(1L, ContentProvenance.TRANSPORT_200)
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE)),
            driver.style(STYLE_GROUP).ownersWithCompletedNonStyleWork,
        )
        assertNoStyleWriteOrInstall(driver, "one owner complete, compilation in flight")

        driver.driveToPendingContent(2L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingClassGates(2L))
        val secondOwnerGate = assertIs<ValidateResourceClass>(driver.actions.single())
        driver.event(
            ResourceClassValidationCompleted(secondOwnerGate.actionId, SuppliedValidationOutcome.Valid),
        )
        val secondOwnerWrite = assertIs<WriteStore>(driver.actions.single())
        driver.event(StoreWriteCompleted(secondOwnerWrite.actionId, SuppliedCallOutcome.Success(Unit)))
        val secondOwnerInstall = assertIs<InstallVisibility>(driver.actions.single())
        assertIs<AwaitingVisibilityInstall>(driver.record(2L).cursor)
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE)),
            driver.style(STYLE_GROUP).ownersWithCompletedNonStyleWork,
            "the owner barrier is derived from installed visibility, not from commit progress",
        )

        driver.event(
            BasemapStyleCompilationCompleted(compilation.actionId, BasemapStyleCompilationOutcome.Succeeded),
        )

        assertEquals(
            StyleCompilationStatus.SUCCEEDED,
            driver.style(STYLE_GROUP).compilationStatus,
        )
        assertEquals(
            listOf(ParkedRoute(0L, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))),
            driver.parked,
        )
        assertNoStyleWriteOrInstall(driver, "compiled with one owner still uninstalled")

        driver.event(VisibilityInstallCompleted(secondOwnerInstall.actionId, SuppliedInstallOutcome.Succeeded))

        val write = assertIs<WriteBasemapStyle>(driver.actions.single())
        assertEquals(ResourceClass.BASEMAP_STYLE, write.rawKey.resourceClass)
        assertEquals(
            listOf(ResourceOwnerId(OWNER_ONE), ResourceOwnerId(OWNER_TWO)),
            driver.style(STYLE_GROUP).ownersWithCompletedNonStyleWork,
        )
        assertFalse(driver.record(0L).visibilityInstalled)
        driver.event(BasemapStyleWriteCompleted(write.actionId, STYLE_GROUP, SuppliedCallOutcome.Success(Unit)))
        val install = assertIs<InstallBasemapStyleVisibility>(driver.actions.single())
        assertTrue(driver.style(STYLE_GROUP).writeAcknowledged)
        driver.event(
            BasemapStyleVisibilityInstallCompleted(
                install.actionId,
                STYLE_GROUP,
                SuppliedInstallOutcome.Succeeded,
            ),
        )

        assertEquals(
            listOf(
                StyleCommitStep.VALIDATE,
                StyleCommitStep.COMPILE,
                StyleCommitStep.WRITE,
                StyleCommitStep.INSTALL,
            ),
            driver.styleCommitSteps(),
        )
        assertTrue(driver.style(STYLE_GROUP).visible)
        assertIs<ResourceOperationOutcome.Success>(driver.outcome)
    }

    @Test
    fun aBufferedFailureClosesLowerParkedCommitOnlyWorkWithoutVisibility() {
        val driver = ResourceDriver(
            definitionOf(
                concurrency = 1,
                occurrences = listOf(
                    spriteOccurrence(1L, OWNER_ONE, SPRITE_GROUP, SpriteMember.JSON, "deadlock-json"),
                    occurrence(
                        2L,
                        OWNER_TWO,
                        registration("deadlock-other", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
                    ),
                    spriteOccurrence(3L, OWNER_ONE, SPRITE_GROUP, SpriteMember.IMAGE, "deadlock-image"),
                ),
            ),
        )
        val blockingFailure = routeFailure(
            RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
            PipelineStage.TRANSPORT,
            "blocking",
        )

        driver.driveToPendingContent(0L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingSpriteCommit(0L))

        assertEquals(
            listOf(ParkedRoute(0L, ParkedRouteBarrier.SpritePair(SPRITE_GROUP))),
            driver.parked,
        )
        assertEquals(
            0L,
            driver.state.nextRetirementOrdinal,
            "retirement is blocked at the parked commit-only ordinal",
        )
        assertEquals(listOf(1L), driver.state.activeRouteOrdinals)
        assertEquals(
            driver.candidate(0L),
            driver.group(SPRITE_GROUP).jsonCandidate,
            "the parked member really staged its validated candidate",
        )
        assertNull(driver.group(SPRITE_GROUP).imageCandidate)

        driver.event(RouteCompleted(1L, ResourceRouteOutcome.Failure(blockingFailure)))

        assertEquals(ResourceOperationOutcome.Failure(blockingFailure), driver.outcome)
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Failure(blockingFailure)),
            driver.state.terminalSelection,
        )
        assertEquals(2L, driver.state.nextRetirementOrdinal, "ordinal retirement reached the terminal")
        assertTrue(driver.parked.isEmpty())
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(0L).status)
        assertNull(driver.record(0L).cursor)
        assertFalse(
            driver.record(0L).visibilityInstalled,
            "closing parked commit-only work never installs visibility",
        )
        assertFalse(driver.group(SPRITE_GROUP).visible)
        assertEquals(
            SpriteJointValidationStatus.WAITING,
            driver.group(SPRITE_GROUP).jointValidationStatus,
        )
        assertTrue(driver.emitted.filterIsInstance<ValidateSpritePair>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<WriteSpriteMember>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<StartRoute>().none { it.ordinal == 2L })
    }

    @Test
    fun aFailedThirdLeaseInstallReleasesOnlyTheTwoAcknowledgedLeasesInReverse() {
        val history = committedHistory(frameIndex = 2L)
        val preparation = PreparationDriver(environment(), history, RecordingPrivateKeyResolver())
        preparation.begin(
            listOf(
                plan(
                    frameIndex = 5L,
                    stickers = listOf(sticker("alpha")),
                    models = listOf(model("mesh", "skin")),
                ),
            ),
        )
        val planned = preparation.planItem(0L)
        assertEquals(3, planned.staticResourceTraversal.size)
        val definition = preparation.definition()
        val resources = ResourceDriver(definition)
        resources.driveOrdinaryRoute(0L, ContentProvenance.RESIDENT)
        resources.driveOrdinaryRoute(1L, ContentProvenance.RESIDENT)
        resources.driveOrdinaryRoute(2L, ContentProvenance.RESIDENT)
        val resourceSuccess = assertIs<ResourceOperationOutcome.Success>(resources.outcome)

        val installing = preparation.submit(
            OrderedPreparationEvent.ResourcesCompleted(resourceSuccess),
        )
        assertEquals(
            3,
            assertIs<OrderedPreparationState.InstallingLeases>(installing.state).pendingInstalls.size,
        )
        preparation.acknowledgeInstall(11L)
        preparation.acknowledgeInstall(22L)
        val outstanding = preparation.installs().last()
        assertEquals(2, outstanding.traversalIndex, "the third install is the outstanding request")
        val installFailure = gpuFailure()

        val rollingBack = preparation.submit(
            OrderedPreparationEvent.LeaseInstallFailed(outstanding, installFailure),
        )

        val firstRelease = assertIs<OrderedPreparationState.RollingBack>(rollingBack.state)
        assertEquals(
            listOf(LeaseId(22L), LeaseId(11L)),
            firstRelease.pendingReleases.map { it.leaseId },
            "rollback releases the acknowledged leases in reverse",
        )
        assertTrue(firstRelease.releasedLeases.isEmpty())
        assertTrue(firstRelease.failedReleaseLeases.isEmpty())
        assertEquals(
            listOf(1, 0),
            firstRelease.pendingReleases.map { it.request.traversalIndex },
        )

        val secondRelease = preparation.submit(
            OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(22L)),
        )
        val rolling = assertIs<OrderedPreparationState.RollingBack>(secondRelease.state)
        assertEquals(listOf(LeaseId(22L)), rolling.releasedLeases)
        assertEquals(listOf(LeaseId(11L)), rolling.pendingReleases.map { it.leaseId })

        val settled = preparation.submit(
            OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(11L)),
        )

        assertEquals(OrderedPreparationOutcome.Failure(installFailure), settled.outcome)
        assertEquals(
            OrderedPreparationState.Idle(history),
            settled.state,
            "a rolled-back batch never commits history",
        )
        assertEquals(listOf(LeaseId(22L), LeaseId(11L)), preparation.releases())
        assertEquals(
            listOf(0, 1),
            preparation.releasedRequests().map { it.traversalIndex }.reversed(),
        )
        assertTrue(
            preparation.releasedRequests().none { it.traversalIndex == 2 },
            "the failed third install holds no lease to release",
        )
    }

    @Test
    fun lifecycleDeletionAcknowledgementRemovesTheSuccessfulPrefixBeforeALaterFailure() {
        val deletions = listOf(deletion(1L), deletion(2L), deletion(3L))
        val snapshot = RendererLifecycleSnapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 7L,
            preparationActive = false,
            gpuLedger = GpuLedger(hasLiveGpuObjects = true, deferredDeletions = deletions),
        )

        val begun = RendererLifecycleStateMachine.begin(
            snapshot,
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        )
        assertEquals(listOf(RendererLifecycleAction.ObserveExactCurrentContext), begun.actions)

        val exact = RendererLifecycleStateMachine.resume(
            requireNotNull(begun.cursor),
            RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
        )
        assertEquals(listOf(RendererLifecycleAction.DeleteDeferred(deletions[0])), exact.actions)
        assertEquals(deletions, exact.snapshot.gpuLedger.deferredDeletions)

        val afterFirst = acknowledgeDeletion(exact, DeletionId(1L))
        assertEquals(listOf(RendererLifecycleAction.DeleteDeferred(deletions[1])), afterFirst.actions)
        assertEquals(deletions.drop(1), afterFirst.snapshot.gpuLedger.deferredDeletions)

        val afterSecond = acknowledgeDeletion(afterFirst, DeletionId(2L))
        assertEquals(listOf(RendererLifecycleAction.DeleteDeferred(deletions[2])), afterSecond.actions)
        assertEquals(deletions.drop(2), afterSecond.snapshot.gpuLedger.deferredDeletions)

        val deletionFailure = gpuFailure()
        val failed = RendererLifecycleStateMachine.resume(
            requireNotNull(afterSecond.cursor),
            RendererLifecycleObservation.DeferredDeletionFailed(DeletionId(3L), deletionFailure),
        )

        assertEquals(RendererLifecycleOutcome.Failed(deletionFailure), failed.outcome)
        assertNull(failed.cursor)
        assertTrue(failed.actions.isEmpty())
        assertEquals(
            deletions.drop(2),
            failed.snapshot.gpuLedger.deferredDeletions,
            "the acknowledged prefix is gone and only the failed deletion remains queued",
        )
        assertTrue(failed.snapshot.gpuLedger.hasLiveGpuObjects)
        assertEquals(RendererOwnerState.LIVE, failed.snapshot.ownerState)
        assertEquals(7L, failed.snapshot.contextGeneration)
    }

    @Test
    fun lifecycleCloseAndAdoptionDecisionsAreIndependentOfPreparationHistory() {
        val committed = successfulPreparationIdle()
        val rejected = OrderedPreparationStateMachine.transition(
            OrderedPreparationState.Idle(null),
            OrderedPreparationEvent.BeginBatch(
                invocationId = PreparationInvocationId(9L),
                plans = emptyList(),
                accessMode = ResourceAccessMode.NORMAL,
                environment = environment(),
            ),
        )
        val empty = assertIs<OrderedPreparationState.Idle>(rejected.state)
        assertNotEquals(committed, empty)
        assertNull(empty.history)
        assertEquals(9L, requireNotNull(committed.history).frameIndex)

        val quietLive = RendererLifecycleSnapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 1L,
            preparationActive = false,
            gpuLedger = GpuLedger(hasLiveGpuObjects = false, deferredDeletions = emptyList()),
        )
        val closeWithHistory = RendererLifecycleStateMachine.begin(
            quietLive,
            RendererLifecycleOperation.CloseRenderer,
        )
        val closeWithoutHistory = RendererLifecycleStateMachine.begin(
            quietLive,
            RendererLifecycleOperation.CloseRenderer,
        )
        assertEquals(closeWithHistory.actions, closeWithoutHistory.actions)
        assertEquals(closeWithHistory.cursor, closeWithoutHistory.cursor)
        assertEquals(
            listOf(
                RendererLifecycleAction.ExecutePermittedOperation(
                    RendererLifecycleOperation.CloseRenderer,
                ),
            ),
            closeWithHistory.actions,
        )
        val closed = RendererLifecycleStateMachine.resume(
            requireNotNull(closeWithHistory.cursor),
            RendererLifecycleObservation.PermittedOperationSucceeded,
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, closed.outcome)
        assertEquals(RendererOwnerState.CLOSED, closed.snapshot.ownerState)

        val adoptable = RendererLifecycleSnapshot(
            ownerState = RendererOwnerState.AWAITING_CONTEXT_ADOPTION,
            contextGeneration = 2L,
            preparationActive = false,
            gpuLedger = GpuLedger(hasLiveGpuObjects = false, deferredDeletions = emptyList()),
        )
        val adoptFirst = RendererLifecycleStateMachine.begin(
            adoptable,
            RendererLifecycleOperation.AdoptCurrentRenderContext,
        )
        val adoptSecond = RendererLifecycleStateMachine.begin(
            adoptable,
            RendererLifecycleOperation.AdoptCurrentRenderContext,
        )
        assertEquals(adoptFirst.actions, adoptSecond.actions)
        assertEquals(
            listOf(RendererLifecycleAction.ObserveAdoptableCurrentContext),
            adoptFirst.actions,
        )
        val adopted = RendererLifecycleStateMachine.resume(
            requireNotNull(adoptFirst.cursor),
            RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.SUPPORTED),
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, adopted.outcome)
        assertEquals(RendererOwnerState.LIVE, adopted.snapshot.ownerState)

        val liveGpuObjects = RendererLifecycleStateMachine.begin(
            quietLive.copy(
                gpuLedger = GpuLedger(hasLiveGpuObjects = true, deferredDeletions = emptyList()),
            ),
            RendererLifecycleOperation.CloseRenderer,
        )
        assertEquals(
            listOf(RendererLifecycleAction.ObserveExactCurrentContext),
            liveGpuObjects.actions,
            "close decisions do move with genuine lifecycle facts",
        )
        val activePreparation = RendererLifecycleStateMachine.begin(
            quietLive.copy(preparationActive = true),
            RendererLifecycleOperation.CloseRenderer,
        )
        val blocked = assertIs<RendererLifecycleOutcome.Failed>(activePreparation.outcome)
        assertEquals(RenGErrorCode.PREPARATION_IN_PROGRESS, blocked.failure.code)
        assertEquals(PipelineStage.RENDERER_CLOSE, blocked.failure.stage)

        val cleared = OrderedPreparationStateMachine.transition(
            committed,
            OrderedPreparationEvent.ClearHistoryRequested,
        )
        assertEquals(OrderedPreparationOutcome.HistoryCleared, cleared.outcome)
        assertEquals(OrderedPreparationState.Idle(null), cleared.state)
        assertEquals(9L, requireNotNull(committed.history).frameIndex, "lifecycle work never touched it")
    }

    private fun successfulPreparationIdle(): OrderedPreparationState.Idle {
        val preparation = PreparationDriver(environment(), null, RecordingPrivateKeyResolver())
        preparation.begin(listOf(plan(frameIndex = 9L, stickers = listOf(sticker("alpha")))))
        val definition = preparation.definitionAfterPlanning(1)
        val resources = ResourceDriver(definition)
        resources.driveOrdinaryRoute(0L, ContentProvenance.RESIDENT)
        val success = assertIs<ResourceOperationOutcome.Success>(resources.outcome)
        preparation.submit(OrderedPreparationEvent.ResourcesCompleted(success))
        val settled = preparation.installAllLeases()
        assertIs<OrderedPreparationOutcome.Success>(settled.outcome)
        return assertIs<OrderedPreparationState.Idle>(settled.state)
    }

    private fun preparationAwaitingResources(): PreparationDriver {
        val preparation = PreparationDriver(
            environment(),
            committedHistory(frameIndex = 1L),
            RecordingPrivateKeyResolver(),
        )
        preparation.begin(listOf(plan(frameIndex = 6L, stickers = listOf(sticker("alpha")))))
        preparation.planItem(0L)
        assertEquals(1, preparation.resourceActions().size)
        return preparation
    }

    private fun storeStyleDriver(): ResourceDriver {
        val driver = ResourceDriver(
            definitionOf(
                concurrency = 1,
                occurrences = listOf(
                    ResourceOccurrence(
                        id = ResourceOccurrenceId(1L),
                        ownerId = ResourceOwnerId(OWNER_ONE),
                        registration = registration(
                            "store-style",
                            ResourceClass.BASEMAP_STYLE,
                            ResourceAccessMode.CACHE_ONLY,
                        ),
                        discoveryRequired = false,
                        commitBinding = ResourceCommitBinding.BasemapStyle(STYLE_GROUP),
                    ),
                ),
            ),
        )
        driver.driveToPendingContent(0L, ContentProvenance.STORE)
        driver.event(AdvancePendingStyleCommit(0L))
        return driver
    }

    private fun assertStoreIntegrity(
        driver: ResourceDriver,
        content: ResolvedResourceContent,
        label: String,
    ) {
        val failure = assertIs<ResourceOperationOutcome.Failure>(driver.outcome, label).failure
        assertEquals(RenGErrorCode.STORE_INTEGRITY_FAILED, failure.code, label)
        assertEquals(PipelineStage.STORE_VALIDATION, failure.stage, label)
        val diagnostic = requireNotNull(failure.diagnostic) { label }
        assertEquals(PipelineStage.STORE_VALIDATION, diagnostic.stage, label)
        assertEquals(DiagnosticField.RESOURCE.wireName, diagnostic.fieldName, label)
        assertEquals(content.route.resourceClass, diagnostic.resourceClass, label)
        assertEquals(content.resourceKey, diagnostic.resourceKey, label)
        assertEquals(ContentProvenance.STORE, content.provenance, label)
        driver.assertNoTransportWriteOrRemoval(label)
    }

    private fun assertNoStyleWriteOrInstall(driver: ResourceDriver, label: String) {
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty(), "$label/write")
        assertTrue(
            driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty(),
            "$label/install",
        )
        assertFalse(driver.style(STYLE_GROUP).writeAcknowledged, "$label/acknowledged")
        assertFalse(driver.style(STYLE_GROUP).visible, "$label/visible")
    }

    private fun acknowledgeDeletion(
        transition: RendererLifecycleTransition,
        deletionId: DeletionId,
    ): RendererLifecycleTransition = RendererLifecycleStateMachine.resume(
        requireNotNull(transition.cursor),
        RendererLifecycleObservation.DeferredDeletionAcknowledged(deletionId),
    )
}

private const val OWNER_ONE: Long = 1L
private const val OWNER_TWO: Long = 2L
private const val SAMPLE_EPOCH_MILLIS: Long = 100L
private val SPRITE_GROUP: SpriteGroupId = SpriteGroupId(1L)
private val STYLE_GROUP: StyleGroupId = StyleGroupId(1L)
private val CONTENT_BYTES: ByteArray = "abc".encodeToByteArray()
private const val CONTENT_DIGEST: String =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

private enum class StyleCommitStep { VALIDATE, COMPILE, WRITE, INSTALL }

private class RecordingPrivateKeyResolver(
    private val tokensByRoute: Map<Pair<String, ResourceClass>, String> = emptyMap(),
) : RentilePrivateKeyResolver {
    private val callLog = mutableListOf<Pair<ResourceLocator, ResourceClass>>()

    val calls: List<Pair<ResourceLocator, ResourceClass>>
        get() = ArrayList(callLog)

    override fun resolve(
        locator: ResourceLocator,
        resourceClass: ResourceClass,
    ): RentilePrivateKey {
        callLog += locator to resourceClass
        val token = tokensByRoute[locator.value to resourceClass]
            ?: "private-key:${locator.value}:${resourceClass.name}"
        return RentilePrivateKey(token)
    }
}

private class PreparationDriver(
    private val environment: PreparationEnvironment,
    val initialHistory: CommittedFrameHistory?,
    resolver: RentilePrivateKeyResolver,
) {
    private val planningCore = FramePlanningCore(
        frameEncoder = FramePlanCanonicalEncoder(PureKotlinSha256),
        frameIdentityRegistry = CanonicalIdentityRegistry(),
        resourceKeyDeriver = ResourceKeyDeriver(PureKotlinSha256),
        rentilePrivateKeyResolver = resolver,
    )
    private val actionLog = mutableListOf<OrderedPreparationAction>()
    private val planningRequests = mutableListOf<Pair<PreparationItemId, FramePlanningRequest>>()
    private val installCounts = mutableListOf<Int>()
    private val releasedRequestLog = mutableListOf<LeaseInstallRequest>()

    var state: OrderedPreparationState = OrderedPreparationState.Idle(initialHistory)
        private set

    var lastTransition: OrderedPreparationTransition? = null
        private set

    val emitted: List<OrderedPreparationAction>
        get() = ArrayList(actionLog)

    fun submit(event: OrderedPreparationEvent): OrderedPreparationTransition {
        val transition = OrderedPreparationStateMachine.transition(state, event)
        state = transition.state
        lastTransition = transition
        actionLog += transition.actions
        transition.actions.filterIsInstance<OrderedPreparationAction.RunPurePlanning>().forEach {
            planningRequests += it.itemId to it.request
        }
        transition.actions.filterIsInstance<OrderedPreparationAction.ReleaseLease>().forEach {
            releasedRequestLog += it.lease.request
        }
        installCounts += transition.actions.count { it is OrderedPreparationAction.InstallLease }
        return transition
    }

    fun begin(plans: List<FramePlan>): OrderedPreparationTransition = submit(
        OrderedPreparationEvent.BeginBatch(
            invocationId = PreparationInvocationId(1L),
            plans = plans,
            accessMode = ResourceAccessMode.NORMAL,
            environment = environment,
        ),
    )

    fun requestFor(itemIndex: Long): FramePlanningRequest =
        requireNotNull(planningRequests.lastOrNull { it.first == PreparationItemId(itemIndex) }) {
            "no pure planning action for item $itemIndex"
        }.second

    fun planningItemIds(): List<PreparationItemId> = planningRequests.map { it.first }

    fun planItem(itemIndex: Long): PlannedFrameCore {
        val planned = assertIs<FramePlanningOutcome.Success>(
            planningCore.plan(requestFor(itemIndex)),
        ).planned
        submit(
            OrderedPreparationEvent.PlanningCompleted(
                itemId = PreparationItemId(itemIndex),
                outcome = FramePlanningOutcome.Success(planned),
            ),
        )
        return planned
    }

    fun failItem(itemIndex: Long): OrderedPreparationTransition {
        val failure = assertIs<FramePlanningOutcome.Failure>(planningCore.plan(requestFor(itemIndex)))
        return submit(
            OrderedPreparationEvent.PlanningCompleted(
                itemId = PreparationItemId(itemIndex),
                outcome = failure,
            ),
        )
    }

    fun resourceActions(): List<OrderedPreparationAction.RunResourceOperation> =
        actionLog.filterIsInstance<OrderedPreparationAction.RunResourceOperation>()

    fun definition(): ResourceOperationDefinition =
        requireNotNull(resourceActions().lastOrNull()) { "no resource operation action" }.definition

    fun definitionAfterPlanning(itemCount: Int): ResourceOperationDefinition {
        for (index in 0 until itemCount) {
            planItem(index.toLong())
        }
        return definition()
    }

    fun installs(): List<LeaseInstallRequest> =
        actionLog.filterIsInstance<OrderedPreparationAction.InstallLease>().map { it.request }

    fun installActionCounts(): List<Int> = installCounts.filter { it > 0 }

    fun releases(): List<LeaseId> =
        actionLog.filterIsInstance<OrderedPreparationAction.ReleaseLease>().map { it.lease.leaseId }

    fun releasedRequests(): List<LeaseInstallRequest> = ArrayList(releasedRequestLog)

    fun acknowledgeInstall(leaseId: Long): OrderedPreparationTransition = submit(
        OrderedPreparationEvent.LeaseInstallAcknowledged(installs().last(), LeaseId(leaseId)),
    )

    fun installAllLeases(): OrderedPreparationTransition {
        var leaseId = 1L
        var last: OrderedPreparationTransition? = null
        while (state is OrderedPreparationState.InstallingLeases) {
            last = acknowledgeInstall(leaseId)
            leaseId += 1L
        }
        return requireNotNull(last) { "no lease install to acknowledge" }
    }
}

private class ResourceDriver(definition: ResourceOperationDefinition) {
    var state: ResourceOperationState.Running
        private set
    var actions: List<ResourceOperationAction>
        private set
    var outcome: ResourceOperationOutcome?
        private set
    val emitted: MutableList<ResourceOperationAction> = mutableListOf()
    var maximumActiveRoutes: Int = 0
        private set

    init {
        val transition = ResourceOperationStateMachine.start(definition)
        state = requireNotNull(transition.state)
        actions = transition.actions
        outcome = transition.outcome
        emitted += transition.actions
        maximumActiveRoutes = state.activeRouteOrdinals.size
    }

    val parked: List<ParkedRoute>
        get() = state.parkedRoutes

    fun beginLookup(ordinal: Long) {
        apply(ResourceOperationStateMachine.beginLookup(state, ordinal))
    }

    fun event(event: ResourceOperationEvent) {
        apply(ResourceOperationStateMachine.transition(state, event))
    }

    fun record(ordinal: Long): RouteRecord = state.routeRecords.single { it.ordinal == ordinal }

    fun candidate(ordinal: Long): ResolvedResourceContent =
        requireNotNull(record(ordinal).lookup?.selectedContent)

    fun group(groupId: SpriteGroupId): SpriteCommitState =
        state.spriteCommitStates.single { it.groupId == groupId }

    fun style(groupId: StyleGroupId): StyleCommitState =
        state.styleCommitStates.single { it.groupId == groupId }

    fun styleCommitSteps(): List<StyleCommitStep> = emitted.mapNotNull { action ->
        when (action) {
            is ValidateBasemapStyle -> StyleCommitStep.VALIDATE
            is CompileBasemapStyle -> StyleCommitStep.COMPILE
            is WriteBasemapStyle -> StyleCommitStep.WRITE
            is InstallBasemapStyleVisibility -> StyleCommitStep.INSTALL
            else -> null
        }
    }

    fun assertNoTransportWriteOrRemoval(label: String) {
        assertTrue(
            emitted.none { it is CallTransport || it is ReplayLatchedTransport },
            "$label/transport",
        )
        assertTrue(
            emitted.none { it is WriteStore || it is WriteSpriteMember || it is WriteBasemapStyle },
            "$label/write",
        )
        assertTrue(
            emitted.none { action ->
                val name = action::class.simpleName.orEmpty()
                listOf("Remove", "Delete", "Evict", "Repair", "Retry").any(name::contains)
            },
            "$label/removal",
        )
    }

    private fun apply(transition: ResourceOperationTransition) {
        state = requireNotNull(transition.state)
        actions = transition.actions
        outcome = transition.outcome
        emitted += transition.actions
        maximumActiveRoutes = maxOf(maximumActiveRoutes, state.activeRouteOrdinals.size)
    }
}

private fun ResourceDriver.driveToPendingContent(ordinal: Long, provenance: ContentProvenance) {
    beginLookup(ordinal)
    val sample = assertIs<SampleClock>(actions.single())
    event(ClockSampled(sample.actionId, SAMPLE_EPOCH_MILLIS))
    val accessMode = record(ordinal).registration.route.accessMode
    when (provenance) {
        ContentProvenance.RESIDENT -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(
                ResidentObserved(
                    observe.actionId,
                    storedResource(freshUntil = SAMPLE_EPOCH_MILLIS + 1L),
                ),
            )
        }

        ContentProvenance.STORE -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(ResidentObserved(observe.actionId, null))
            val read = assertIs<ReadStore>(actions.single())
            val freshUntil = if (accessMode == ResourceAccessMode.CACHE_ONLY) {
                1L
            } else {
                SAMPLE_EPOCH_MILLIS + 1L
            }
            event(
                StoreReadCompleted(
                    read.actionId,
                    SuppliedCallOutcome.Success(storedResource(freshUntil = freshUntil)),
                ),
            )
        }

        ContentProvenance.TRANSPORT_200 -> {
            if (accessMode == ResourceAccessMode.NORMAL) {
                val observe = assertIs<ObserveResident>(actions.single())
                event(ResidentObserved(observe.actionId, null))
                val read = assertIs<ReadStore>(actions.single())
                event(StoreReadCompleted(read.actionId, SuppliedCallOutcome.Success(null)))
            }
            val call = assertIs<CallTransport>(actions.single())
            event(
                TransportCompleted(
                    call.actionId,
                    SuppliedCallOutcome.Success(
                        TransportResponse(
                            statusCode = 200,
                            body = CONTENT_BYTES,
                            metadata = TransportResponseMetadata(etag = "fetched-etag"),
                        ),
                    ),
                ),
            )
        }

        ContentProvenance.TRANSPORT_304_MERGED -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(
                ResidentObserved(
                    observe.actionId,
                    storedResource(etag = "baseline-etag", freshUntil = SAMPLE_EPOCH_MILLIS),
                ),
            )
            val read = assertIs<ReadStore>(actions.single())
            event(StoreReadCompleted(read.actionId, SuppliedCallOutcome.Success(null)))
            val call = assertIs<CallTransport>(actions.single())
            event(
                TransportCompleted(
                    call.actionId,
                    SuppliedCallOutcome.Success(
                        TransportResponse(
                            statusCode = 304,
                            body = byteArrayOf(),
                            metadata = TransportResponseMetadata(
                                freshUntilEpochMillis = SAMPLE_EPOCH_MILLIS + 500L,
                            ),
                        ),
                    ),
                ),
            )
        }
    }
    assertTrue(actions.isEmpty(), "$ordinal/${provenance.name}")
    val pending = assertIs<PendingClassGates>(record(ordinal).cursor, "$ordinal/${provenance.name}")
    assertEquals(provenance, pending.content.provenance, "$ordinal/${provenance.name}")
}

private fun ResourceDriver.driveOrdinaryRoute(ordinal: Long, provenance: ContentProvenance) {
    driveToPendingContent(ordinal, provenance)
    val resourceClass = record(ordinal).registration.route.resourceClass
    val gates = requireNotNull(ordinaryResourceClassGates(resourceClass))
    val content = candidate(ordinal)
    event(AdvancePendingClassGates(ordinal))
    gates.forEachIndexed { index, gate ->
        val action = assertIs<ValidateResourceClass>(actions.single(), "$ordinal/$gate")
        assertEquals(gate, action.gate, "$ordinal/$gate")
        assertEquals(content, action.content, "$ordinal/$gate")
        assertEquals(
            AwaitingClassGate(action.actionId, ordinal, content, index, gate),
            record(ordinal).cursor,
            "$ordinal/$gate",
        )
        event(ResourceClassValidationCompleted(action.actionId, SuppliedValidationOutcome.Valid))
    }
    if (requiresStoreWrite(provenance)) {
        val write = assertIs<WriteStore>(actions.single(), "$ordinal/write")
        assertEquals(record(ordinal).registration.rawKey, write.rawKey, "$ordinal/write")
        event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
        assertTrue(record(ordinal).storeWriteAcknowledged, "$ordinal/write acknowledgement")
    } else {
        assertFalse(record(ordinal).storeWriteAcknowledged, "$ordinal/no write")
    }
    val install = assertIs<InstallVisibility>(actions.single(), "$ordinal/install")
    assertEquals(content, install.content, "$ordinal/install")
    event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded))
    assertTrue(record(ordinal).visibilityInstalled, "$ordinal/installed")
}

private fun ResourceDriver.completeSpritePair(
    validation: ValidateSpritePair,
    groupId: SpriteGroupId,
    jsonOrdinal: Long,
    imageOrdinal: Long,
) {
    event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))
    listOf(SpriteMember.JSON to jsonOrdinal, SpriteMember.IMAGE to imageOrdinal).forEach { member ->
        val write = assertIs<WriteSpriteMember>(actions.single())
        assertEquals(member.first, write.member)
        assertEquals(member.second, write.ordinal)
        assertEquals(groupId, write.groupId)
        event(
            SpriteMemberWriteCompleted(
                write.actionId,
                groupId,
                member.first,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
    }
    val install = assertIs<InstallSpriteVisibility>(actions.single())
    event(SpriteVisibilityInstallCompleted(install.actionId, groupId, SuppliedInstallOutcome.Succeeded))
}

private fun threeRouteDefinition(concurrency: Int): ResourceOperationDefinition = definitionOf(
    concurrency = concurrency,
    occurrences = listOf(
        occurrence(1L, 1L, registration("first", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD)),
        occurrence(2L, 2L, registration("second", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD)),
        occurrence(3L, 3L, registration("third", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD)),
    ),
)

private fun definitionOf(
    concurrency: Int,
    occurrences: List<ResourceOccurrence>,
): ResourceOperationDefinition = ResourceOperationDefinition(
    maximumConcurrentRoutes = concurrency,
    staticOccurrences = occurrences,
    resourceIdentities = occurrences
        .map { CanonicalIdentityRecord(it.registration.resourceKey, it.registration.canonicalBytes) }
        .distinctBy { it.resourceKey.stableId },
)

private fun occurrence(
    id: Long,
    ownerId: Long,
    registration: ResourceRouteRegistration,
    discoveryRequired: Boolean = false,
): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(id),
    ownerId = ResourceOwnerId(ownerId),
    registration = registration,
    discoveryRequired = discoveryRequired,
    commitBinding = ResourceCommitBinding.Single,
)

private fun spriteOccurrence(
    id: Long,
    ownerId: Long,
    groupId: SpriteGroupId,
    member: SpriteMember,
    marker: String,
    accessMode: ResourceAccessMode = ResourceAccessMode.RELOAD,
): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(id),
    ownerId = ResourceOwnerId(ownerId),
    registration = registration(
        marker = marker,
        resourceClass = spriteMemberResourceClass(member),
        accessMode = accessMode,
    ),
    discoveryRequired = false,
    commitBinding = ResourceCommitBinding.Sprite(groupId, member),
)

private fun registration(
    marker: String,
    resourceClass: ResourceClass,
    accessMode: ResourceAccessMode,
    rawKey: RawResourceKey = RawResourceKey(hexId("raw:$marker:${resourceClass.name}"), resourceClass),
    privateToken: String = "private:$marker:${resourceClass.name}",
): ResourceRouteRegistration = ResourceRouteRegistration(
    route = ResourceRouteKey(
        accessMode = accessMode,
        locator = ResourceLocator("locator-$marker-${resourceClass.name}"),
        resourceClass = resourceClass,
        maximumResponseBytes = 4096L,
    ),
    resourceKey = ResourceKey(
        kind = ResourceKind.EXTERNAL,
        stableId = hexId("key:$marker:${resourceClass.name}"),
        resourceClass = resourceClass,
    ),
    rawKey = rawKey,
    privateRentileKey = RentilePrivateKey(privateToken),
    canonicalBytes = CanonicalBytes("canonical:$marker:${resourceClass.name}".encodeToByteArray()),
)

private fun hexId(text: String): String =
    PureKotlinSha256.digest(CanonicalBytes(text.encodeToByteArray())).lowercaseHex

private fun storedResource(
    etag: String? = null,
    freshUntil: Long? = null,
): StoredRawResource = StoredRawResource(
    bytes = CONTENT_BYTES,
    contentDigest = CONTENT_DIGEST,
    metadata = StoredRawResourceMetadata(
        contentType = null,
        etag = etag,
        lastModified = null,
        freshUntilEpochMillis = freshUntil,
        storedAtEpochMillis = 1L,
    ),
)

private fun routeFailure(
    code: RenGErrorCode,
    stage: PipelineStage,
    marker: String,
    fieldName: DiagnosticField? = null,
): FailureDescriptor {
    val resourceClass = ResourceClass.STICKER_IMAGE
    return FailureDescriptor(
        code = code,
        stage = stage,
        diagnostic = failureContextDiagnostic(
            stage = stage,
            fieldName = fieldName,
            resourceClass = resourceClass,
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, hexId(marker), resourceClass),
        ),
    )
}

private fun gpuFailure(): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.GPU_OPERATION_FAILED,
    stage = PipelineStage.GPU_RESOURCE,
    diagnostic = failureContextDiagnostic(stage = PipelineStage.GPU_RESOURCE),
)

private fun environment(): PreparationEnvironment = PreparationEnvironment(
    outputPixelSize = OutputPixelSize(100, 100),
    basemapStyle = null,
    resourceLimits = ResourceLimits(),
    maximumBasemapTileInstances = 512,
    maximumPreparationBatchSize = 8,
    maximumConcurrentResourceOperations = 8,
)

private fun committedHistory(frameIndex: Long): CommittedFrameHistory = CommittedFrameHistory(
    frameIndex = frameIndex,
    encodedPlan = FramePlanCanonicalEncoder(PureKotlinSha256).encode(
        FramePlan(frameIndex = frameIndex, camera = Camera(1.0, 1.0, 1.0, 0.0, 0.0)),
    ),
    selectedLod = 0,
)

private fun plan(
    frameIndex: Long,
    camera: Camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
    projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
    stickers: List<Sticker> = emptyList(),
    models: List<Model> = emptyList(),
    geometries: List<Geometry> = emptyList(),
): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = camera,
    projectionMode = projectionMode,
    stickers = stickers,
    models = models,
    geometries = geometries,
)

private fun sticker(token: String): Sticker = Sticker(screenPlacement(), ResourceLocator("$token.png"))

// MAP, not SCREEN: ADR 0029 refuses a SCREEN-positioned Model at frame planning, and this trace
// test is not about draw regime.
private fun model(glb: String, texture: String? = null): Model = Model(
    placement = mapPlacement(),
    glb = ResourceLocator("$glb.glb"),
    texture = texture?.let { ResourceLocator("$it.png") },
)

private fun geometry(token: String): Geometry = Geometry(
    topLeft = Vector3(20.0, 0.0, 0.0),
    bottomRight = Vector3(10.0, 1.0, 0.0),
    shaderPair = ShaderPair(
        vertexSource = "#version 300 es\n// $token-vertex",
        fragmentSource = "#version 300 es\n// $token-fragment",
    ),
)

private fun screenPlacement(): Placement = Placement(
    positionMode = AnchoringMode.SCREEN,
    position = Vector3(10.0, 20.0, 0.0),
    rotationMode = AnchoringMode.SCREEN,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.SCREEN,
    scale = 1.0,
)

private fun mapPlacement(): Placement = Placement(
    positionMode = AnchoringMode.MAP,
    position = Vector3(10.0, 20.0, 0.0),
    rotationMode = AnchoringMode.MAP,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.MAP,
    scale = 1.0,
)

private fun programKey(planned: PlannedFrameCore): ResourceKey = planned.staticResourceTraversal
    .filterIsInstance<StaticResourceReference.GeometryProgram>()
    .single()
    .resourceKey

private fun deletion(id: Long): DeferredDeletion = DeferredDeletion(DeletionId(id), null)

private fun <T> assertFreshList(first: List<T>, second: List<T>, expected: List<T>) {
    assertEquals(expected, first)
    assertEquals(expected, second)
    assertNotSame(first, second)
    @Suppress("UNCHECKED_CAST")
    (first as MutableList<T>).clear()
    assertEquals(expected, second)
}
