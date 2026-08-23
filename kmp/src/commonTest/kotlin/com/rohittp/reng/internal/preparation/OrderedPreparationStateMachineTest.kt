package com.rohittp.reng.internal.preparation

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.Model
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.Placement
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Sticker
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.planning.FramePlanningCore
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.PlannedFrameCore
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.resource.CancellationCause
import com.rohittp.reng.internal.resource.CancellationId
import com.rohittp.reng.internal.resource.CancellationSelection
import com.rohittp.reng.internal.resource.ContentProvenance
import com.rohittp.reng.internal.resource.OwnerResourceSet
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOwnerId
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.StyleGroupId
import com.rohittp.reng.internal.resource.VisibleResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrderedPreparationStateMachineTest {
    @Test
    fun emptyBatchFailsTheBeginBarrierWithoutAnyAction() {
        val history = committedHistory(frameIndex = 3L)
        val driver = Driver(environment(), history)

        val transition = driver.begin(emptyList())

        assertFailureOutcome(
            transition = transition,
            code = RenGErrorCode.INVALID_VALUE,
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = "plans",
        )
        assertEquals(OrderedPreparationState.Idle(history), transition.state)
        assertEquals(emptyList(), transition.actions)
        assertNull(transition.cursor)
        assertEquals(emptyList(), driver.actions)
    }

    @Test
    fun oversizedBatchFailsTheBeginBarrierWithItsLimitAndActualSize() {
        val driver = Driver(environment(maximumPreparationBatchSize = 2))

        val transition = driver.begin(listOf(plan(1L), plan(2L), plan(3L)))

        assertFailureOutcome(
            transition = transition,
            code = RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = "plans",
            limit = 2L,
            actual = 3L,
        )
        assertEquals(OrderedPreparationState.Idle(null), transition.state)
        assertEquals(emptyList(), driver.actions)

        val exact = Driver(environment(maximumPreparationBatchSize = 2)).begin(listOf(plan(1L), plan(2L)))
        assertNull(exact.outcome)
        assertEquals(1, exact.actions.size)
    }

    @Test
    fun nonIncreasingFrameIndicesFailTheOrderBarrier() {
        val equalIndices = Driver(environment()).begin(listOf(plan(5L), plan(5L)))
        val descendingIndices = Driver(environment()).begin(listOf(plan(5L), plan(4L)))
        val laterDescent = Driver(environment()).begin(listOf(plan(1L), plan(2L), plan(2L)))

        listOf(equalIndices, descendingIndices, laterDescent).forEach { transition ->
            assertFailureOutcome(
                transition = transition,
                code = RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = "frameIndex",
            )
            assertEquals(OrderedPreparationState.Idle(null), transition.state)
            assertEquals(emptyList(), transition.actions)
        }
    }

    @Test
    fun frameIndicesNotAboveCommittedHistoryFailTheOrderBarrier() {
        val history = committedHistory(frameIndex = 7L)

        val equalToHistory = Driver(environment(), history).begin(listOf(plan(7L), plan(9L)))
        val belowHistory = Driver(environment(), history).begin(listOf(plan(6L)))

        listOf(equalToHistory, belowHistory).forEach { transition ->
            assertFailureOutcome(
                transition = transition,
                code = RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = "frameIndex",
            )
            assertEquals(OrderedPreparationState.Idle(history), transition.state)
            assertEquals(emptyList(), transition.actions)
        }

        val aboveHistory = Driver(environment(), history).begin(listOf(plan(8L), plan(9L)))
        assertNull(aboveHistory.outcome)
        assertEquals(
            OrderedPreparationCursor.AwaitingPlanning(PreparationItemId(0L)),
            aboveHistory.cursor,
        )
    }

    @Test
    fun beginSnapshotsItsPlansDefensivelyAgainstLaterCallerMutation() {
        val driver = Driver(environment())
        val submitted = mutableListOf(plan(1L), plan(2L))
        val event = OrderedPreparationEvent.BeginBatch(
            invocationId = PreparationInvocationId(1L),
            plans = submitted,
            accessMode = ResourceAccessMode.NORMAL,
            environment = environment(),
        )

        assertNotSame(submitted, event.plans)
        assertNotSame(event.plans, event.plans)

        val transition = driver.submit(event)
        submitted.clear()
        submitted += plan(99L)

        val planning = assertIs<OrderedPreparationState.Planning>(transition.state)
        assertEquals(listOf(plan(1L), plan(2L)), planning.invocation.plans)
        assertNotSame(planning.invocation.plans, planning.invocation.plans)
        assertEquals(plan(1L), driver.requestFor(0L).plan)
    }

    @Test
    fun aSecondBeginIsRejectedWithPreparationInProgressBeforeEveryOtherBeginCheck() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(maximumPreparationBatchSize = 2), history)
        val first = driver.begin(listOf(plan(4L), plan(5L)))
        val activeState = first.state

        val emptySecond = driver.begin(emptyList())
        val oversizedSecond = driver.begin(listOf(plan(6L), plan(7L), plan(8L)))
        val disorderedSecond = driver.begin(listOf(plan(3L), plan(2L)))
        val singletonSecond = driver.submit(
            OrderedPreparationEvent.BeginSingleton(
                invocationId = PreparationInvocationId(9L),
                plan = plan(2L),
                accessMode = ResourceAccessMode.RELOAD,
                environment = environment(),
            ),
        )

        listOf(emptySecond, oversizedSecond, disorderedSecond, singletonSecond).forEach { transition ->
            assertFailureOutcome(
                transition = transition,
                code = RenGErrorCode.PREPARATION_IN_PROGRESS,
                stage = PipelineStage.FRAME_PREPARATION,
                fieldName = null,
            )
            assertEquals(activeState, transition.state)
            assertEquals(emptyList(), transition.actions)
            assertEquals(
                OrderedPreparationCursor.AwaitingPlanning(PreparationItemId(0L)),
                transition.cursor,
            )
        }
        assertEquals(1, driver.actions.size)
    }

    @Test
    fun aSecondBeginIsRejectedDuringResourceResolutionAndLeaseInstallation() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        driver.begin(listOf(plan(1L, stickers = listOf(sticker("one")))))
        driver.completePlanning(1)
        val resolving = driver.state

        val duringResources = driver.begin(listOf(plan(20L)))
        assertFailureOutcome(
            duringResources,
            RenGErrorCode.PREPARATION_IN_PROGRESS,
            PipelineStage.FRAME_PREPARATION,
            null,
        )
        assertEquals(resolving, duringResources.state)
        assertEquals(OrderedPreparationCursor.AwaitingResources, duringResources.cursor)

        driver.completeResources(driver.resourceSuccess())
        val installing = driver.state
        val duringInstalls = driver.begin(listOf(plan(21L)))
        assertFailureOutcome(
            duringInstalls,
            RenGErrorCode.PREPARATION_IN_PROGRESS,
            PipelineStage.FRAME_PREPARATION,
            null,
        )
        assertEquals(installing, duringInstalls.state)
        assertIs<OrderedPreparationCursor.AwaitingLeaseInstall>(duringInstalls.cursor)
    }

    @Test
    fun singletonBeginNormalizesToExactlyOneNonemptyAtomicBatchItem() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))

        val begun = driver.submit(
            OrderedPreparationEvent.BeginSingleton(
                invocationId = PreparationInvocationId(3L),
                plan = plan(6L, stickers = listOf(sticker("solo"))),
                accessMode = ResourceAccessMode.CACHE_ONLY,
                environment = environment(basemapStyle = STYLE_LOCATOR),
            ),
        )

        val planning = assertIs<OrderedPreparationState.Planning>(begun.state)
        assertEquals(listOf(plan(6L, stickers = listOf(sticker("solo")))), planning.invocation.plans)
        assertEquals(ResourceAccessMode.CACHE_ONLY, planning.invocation.accessMode)
        assertEquals(1, begun.actions.size)

        driver.completePlanning(1)
        driver.completeResources(driver.resourceSuccess())
        val success = driver.installAllLeases()

        assertEquals(1, success.frameSeeds.size)
        assertEquals(PreparationItemId(0L), success.frameSeeds.single().itemId)
        assertEquals(6L, success.frameSeeds.single().frameIndex)
        assertEquals(6L, assertIs<OrderedPreparationState.Idle>(driver.state).history?.frameIndex)
    }

    @Test
    fun singletonBeginRunsTheSameOrderBarrierAsABatch() {
        val history = committedHistory(frameIndex = 12L)
        val driver = Driver(environment(), history)

        val transition = driver.submit(
            OrderedPreparationEvent.BeginSingleton(
                invocationId = PreparationInvocationId(1L),
                plan = plan(12L),
                accessMode = ResourceAccessMode.NORMAL,
                environment = environment(),
            ),
        )

        assertFailureOutcome(
            transition,
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            "frameIndex",
        )
        assertEquals(OrderedPreparationState.Idle(history), transition.state)
    }

    @Test
    fun planningRunsSequentiallyWithCommittedThenImmediatePredecessorBaselines() {
        val history = committedHistory(frameIndex = 2L, selectedLod = 4)
        val environment = environment(basemapStyle = STYLE_LOCATOR)
        val driver = Driver(environment, history)
        val plans = listOf(
            plan(3L, stickers = listOf(sticker("first"))),
            plan(4L, stickers = listOf(sticker("second"))),
            plan(5L, stickers = listOf(sticker("third"))),
        )

        driver.begin(plans)
        assertEquals(listOf(PreparationItemId(0L)), driver.planningItemIds())
        val firstRequest = driver.requestFor(0L)
        assertEquals(plans[0], firstRequest.plan)
        assertEquals(history.encodedPlan, firstRequest.previousPlan)
        assertEquals(history.selectedLod, firstRequest.previousSelectedLod)
        assertEquals(environment.outputPixelSize, firstRequest.outputPixelSize)
        assertEquals(environment.basemapStyle, firstRequest.basemapStyle)
        assertEquals(environment.resourceLimits, firstRequest.resourceLimits)
        assertEquals(
            environment.maximumBasemapTileInstances,
            firstRequest.maximumBasemapTileInstances,
        )

        val firstPlanned = driver.completeOne(0L)
        assertEquals(
            listOf(PreparationItemId(0L), PreparationItemId(1L)),
            driver.planningItemIds(),
        )
        val secondRequest = driver.requestFor(1L)
        assertEquals(plans[1], secondRequest.plan)
        assertEquals(firstPlanned.encodedPlan, secondRequest.previousPlan)
        assertEquals(firstPlanned.spatialPlan.lodObservation.selectedLod, secondRequest.previousSelectedLod)

        val secondPlanned = driver.completeOne(1L)
        val thirdRequest = driver.requestFor(2L)
        assertEquals(plans[2], thirdRequest.plan)
        assertEquals(secondPlanned.encodedPlan, thirdRequest.previousPlan)
        assertEquals(secondPlanned.spatialPlan.lodObservation.selectedLod, thirdRequest.previousSelectedLod)
        assertEquals(
            listOf(PreparationItemId(0L), PreparationItemId(1L), PreparationItemId(2L)),
            driver.planningItemIds(),
        )
    }

    @Test
    fun noResourceActionIsEmittedUntilEveryPlanSucceeds() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L), plan(3L), plan(4L, stickers = listOf(sticker("late")))))

        driver.completeOne(0L)
        assertEquals(emptyList(), driver.resourceActions())
        driver.completeOne(1L)
        assertEquals(emptyList(), driver.resourceActions())

        val planningFailure = shaderFailure()
        val terminal = driver.submit(
            OrderedPreparationEvent.PlanningCompleted(
                itemId = PreparationItemId(2L),
                outcome = FramePlanningOutcome.Failure(planningFailure),
            ),
        )

        val outcome = assertIs<OrderedPreparationOutcome.Failure>(terminal.outcome)
        assertSame(planningFailure, outcome.failure)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertNull(terminal.cursor)
        assertEquals(emptyList(), driver.resourceActions())
        assertEquals(emptyList(), driver.leaseActions())
        assertEquals(3, driver.planningItemIds().size)
    }

    @Test
    fun aFirstItemPlanningFailurePreservesHistoryAndStartsNoResourceWork() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(), history)
        driver.begin(listOf(plan(2L), plan(3L)))

        val terminal = driver.submit(
            OrderedPreparationEvent.PlanningCompleted(
                itemId = PreparationItemId(0L),
                outcome = FramePlanningOutcome.Failure(shaderFailure()),
            ),
        )

        assertIs<OrderedPreparationOutcome.Failure>(terminal.outcome)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertEquals(1, driver.planningItemIds().size)
        assertEquals(emptyList(), driver.resourceActions())
    }

    @Test
    fun theResourceOperationCarriesExternalOccurrencesAndEveryCanonicalIdentity() {
        val environment = environment(basemapStyle = STYLE_LOCATOR, maximumConcurrentResourceOperations = 3)
        val driver = Driver(environment)
        driver.begin(
            listOf(
                plan(
                    1L,
                    stickers = listOf(sticker("one")),
                    models = listOf(model("robot", "skin")),
                    geometries = listOf(geometry("alpha")),
                ),
                plan(2L, stickers = listOf(sticker("two"))),
            ),
        )
        driver.completePlanning(2)

        assertEquals(1, driver.resourceActions().size)
        val definition = driver.definition()
        assertEquals(3, definition.maximumConcurrentRoutes)

        val expectedExternals = driver.planned.flatMap { planned ->
            planned.staticResourceTraversal.filterIsInstance<StaticResourceReference.External>()
        }
        val occurrences = definition.staticOccurrences
        assertEquals(expectedExternals.size, occurrences.size)
        assertEquals(
            (1..occurrences.size).map { ResourceOccurrenceId(it.toLong()) },
            occurrences.map { it.id },
        )
        val firstItemExternals = driver.planned[0]
            .staticResourceTraversal
            .filterIsInstance<StaticResourceReference.External>()
            .size
        assertEquals(
            List(firstItemExternals) { ResourceOwnerId(1L) } +
                List(occurrences.size - firstItemExternals) { ResourceOwnerId(2L) },
            occurrences.map { it.ownerId },
        )
        occurrences.forEachIndexed { index, occurrence ->
            val reference = expectedExternals[index]
            assertEquals(
                ResourceRouteKey(
                    accessMode = ResourceAccessMode.NORMAL,
                    locator = reference.locator,
                    resourceClass = reference.resourceClass,
                    maximumResponseBytes = reference.maximumResponseBytes,
                ),
                occurrence.registration.route,
            )
            assertEquals(reference.resourceKey, occurrence.registration.resourceKey)
            assertEquals(reference.rawKey, occurrence.registration.rawKey)
            assertEquals(reference.privateRentileKey, occurrence.registration.privateRentileKey)
            assertEquals(
                reference.canonicalIdentity.canonicalBytes,
                occurrence.registration.canonicalBytes,
            )
            val style = reference.resourceClass == ResourceClass.BASEMAP_STYLE
            assertEquals(style, occurrence.discoveryRequired)
            assertEquals(
                if (style) ResourceCommitBinding.BasemapStyle(StyleGroupId(1L)) else ResourceCommitBinding.Single,
                occurrence.commitBinding,
            )
        }

        val expectedIdentities = driver.planned.flatMap { planned ->
            planned.staticResourceTraversal.map { it.resourceKey to it.canonicalIdentity.canonicalBytes }
        }
        assertEquals(
            expectedIdentities,
            definition.resourceIdentities.map { it.resourceKey to it.canonicalBytes },
        )
        assertTrue(
            definition.resourceIdentities.any { record ->
                driver.planned[0].staticResourceTraversal
                    .filterIsInstance<StaticResourceReference.GeometryProgram>()
                    .any { it.resourceKey == record.resourceKey }
            },
        )
        assertTrue(
            occurrences.none { occurrence ->
                driver.planned[0].staticResourceTraversal
                    .filterIsInstance<StaticResourceReference.GeometryProgram>()
                    .any { it.resourceKey == occurrence.registration.resourceKey }
            },
        )
    }

    @Test
    fun reversedResourceCompletionStillInstallsLeasesInItemAndTraversalOrder() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        driver.begin(
            listOf(
                plan(1L, stickers = listOf(sticker("one")), models = listOf(model("robot", "skin"))),
                plan(2L, stickers = listOf(sticker("two"))),
            ),
        )
        driver.completePlanning(2)

        driver.completeResources(driver.resourceSuccess(reverseOwners = true, reverseResources = true))
        driver.installAllLeases()

        assertEquals(driver.expectedLeaseRequests(), driver.installedRequests())
        assertEquals(
            List(4) { PreparationItemId(0L) } + List(2) { PreparationItemId(1L) },
            driver.installedRequests().map { it.itemId },
        )
        assertEquals(listOf(0, 1, 2, 3, 0, 1), driver.installedRequests().map { it.traversalIndex })
        assertTrue(driver.installTransitionActionCounts().all { it <= 1 })
    }

    @Test
    fun eachInstallIsRequestedOneAtATimeAndAcknowledgedBeforeTheNext() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        driver.begin(listOf(plan(1L, stickers = listOf(sticker("one")))))
        driver.completePlanning(1)

        val started = driver.completeResources(driver.resourceSuccess())
        assertEquals(1, started.actions.size)
        val firstRequest = assertIs<OrderedPreparationAction.InstallLease>(started.actions.single()).request
        assertEquals(
            OrderedPreparationCursor.AwaitingLeaseInstall(firstRequest),
            started.cursor,
        )
        val installing = assertIs<OrderedPreparationState.InstallingLeases>(started.state)
        assertEquals(firstRequest, installing.pendingInstalls.first())
        assertEquals(emptyList(), installing.acknowledgedLeases)

        val next = driver.submit(
            OrderedPreparationEvent.LeaseInstallAcknowledged(firstRequest, LeaseId(1L)),
        )
        val stillInstalling = assertIs<OrderedPreparationState.InstallingLeases>(next.state)
        assertEquals(
            listOf(AcknowledgedLease(LeaseId(1L), firstRequest)),
            stillInstalling.acknowledgedLeases,
        )
        assertEquals(1, next.actions.size)
    }

    @Test
    fun installFailureReleasesAcknowledgedLeasesInReverseAcknowledgementOrderOnly() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")), models = listOf(model("robot")))))
        driver.completePlanning(1)
        driver.completeResources(driver.resourceSuccess())
        assertEquals(3, driver.expectedLeaseRequests().size)

        driver.acknowledgeInstall(1L)
        driver.acknowledgeInstall(2L)
        val failure = leaseInstallFailure()
        val thirdRequest = driver.pendingInstallRequest()
        val failed = driver.submit(OrderedPreparationEvent.LeaseInstallFailed(thirdRequest, failure))

        assertEquals(
            listOf(LeaseId(2L)),
            failed.actions.map { assertIs<OrderedPreparationAction.ReleaseLease>(it).lease.leaseId },
        )
        val rollingBack = assertIs<OrderedPreparationState.RollingBack>(failed.state)
        assertEquals(OrderedPreparationTerminal.Failure(failure), rollingBack.originalOutcome)

        driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(2L)))
        val terminal = driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(1L)))

        assertEquals(
            listOf(LeaseId(2L), LeaseId(1L)),
            driver.releasedLeaseIds(),
        )
        val outcome = assertIs<OrderedPreparationOutcome.Failure>(terminal.outcome)
        assertSame(failure, outcome.failure)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertNull(terminal.cursor)
        assertEquals(3, driver.installedRequests().size)
    }

    @Test
    fun aReleaseFailureIsRecordedWithoutReplacingTheOriginalTerminalOrRetrying() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")), models = listOf(model("robot")))))
        driver.completePlanning(1)
        driver.completeResources(driver.resourceSuccess())
        driver.acknowledgeInstall(1L)
        driver.acknowledgeInstall(2L)
        val failure = leaseInstallFailure()
        driver.submit(OrderedPreparationEvent.LeaseInstallFailed(driver.pendingInstallRequest(), failure))

        val afterReleaseFailure = driver.submit(OrderedPreparationEvent.LeaseReleaseFailed(LeaseId(2L)))
        val stillRollingBack = assertIs<OrderedPreparationState.RollingBack>(afterReleaseFailure.state)
        assertEquals(listOf(LeaseId(2L)), stillRollingBack.failedReleaseLeases)
        assertEquals(emptyList(), stillRollingBack.releasedLeases)
        assertNull(afterReleaseFailure.outcome)

        val terminal = driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(1L)))

        val outcome = assertIs<OrderedPreparationOutcome.Failure>(terminal.outcome)
        assertSame(failure, outcome.failure)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertEquals(listOf(LeaseId(2L), LeaseId(1L)), driver.releasedLeaseIds())
    }

    @Test
    fun totalSuccessCommitsOnlyTheFinalItemsPlanIndexAndLodAtomically() {
        val history = committedHistory(frameIndex = 1L, selectedLod = 6)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(4L, stickers = listOf(sticker("one"))), plan(9L)))
        driver.completePlanning(2)
        driver.completeResources(driver.resourceSuccess())
        val beforeCommit = assertIs<OrderedPreparationState.InstallingLeases>(driver.state)
        assertEquals(history, beforeCommit.invocation.initialHistory)

        driver.installAllLeases()

        val committed = assertNotNull(assertIs<OrderedPreparationState.Idle>(driver.state).history)
        assertEquals(9L, committed.frameIndex)
        assertEquals(driver.planned[1].encodedPlan, committed.encodedPlan)
        assertEquals(driver.planned[1].spatialPlan.lodObservation.selectedLod, committed.selectedLod)
        assertNotEquals(history, committed)
    }

    @Test
    fun frameSeedsAreFreshSameOrderSnapshotsWithTheirOwnCompleteLeaseSets() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        driver.begin(
            listOf(
                plan(1L, stickers = listOf(sticker("shared"))),
                plan(2L, stickers = listOf(sticker("shared"))),
            ),
        )
        driver.completePlanning(2)
        driver.completeResources(driver.resourceSuccess())
        val success = driver.installAllLeases()

        assertEquals(
            listOf(PreparationItemId(0L), PreparationItemId(1L)),
            success.frameSeeds.map { it.itemId },
        )
        assertEquals(listOf(1L, 2L), success.frameSeeds.map { it.frameIndex })
        assertEquals(driver.planned, success.frameSeeds.map { it.plannedFrame })
        assertNotSame(success.frameSeeds, success.frameSeeds)
        assertNotSame(success.frameSeeds.first().leases, success.frameSeeds.first().leases)

        val expectedByItem = driver.expectedLeaseRequests().groupBy { it.itemId }
        success.frameSeeds.forEach { seed ->
            assertEquals(
                assertNotNull(expectedByItem[seed.itemId]),
                seed.leases.map { it.request },
            )
            assertTrue(seed.leases.isNotEmpty())
        }
        assertEquals(
            driver.expectedLeaseRequests().size,
            success.frameSeeds.sumOf { it.leases.size },
        )
    }

    @Test
    fun eachItemLeasesASharedResourceSeparatelyAndDiscoveredChildrenFollowItsTraversal() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        driver.begin(
            listOf(
                plan(1L, stickers = listOf(sticker("shared"))),
                plan(2L, stickers = listOf(sticker("shared"))),
            ),
        )
        driver.completePlanning(2)
        val child = driver.discoveredChild("basemap-tile")
        driver.completeResources(driver.resourceSuccess(extraChildren = mapOf(0 to listOf(child))))
        val success = driver.installAllLeases()

        val sharedKey = driver.planned[0]
            .staticResourceTraversal
            .filterIsInstance<StaticResourceReference.External>()
            .first { it.resourceClass == ResourceClass.STICKER_IMAGE }
            .resourceKey
        val leasesForShared = success.frameSeeds.flatMap { seed ->
            seed.leases.filter { lease ->
                val resource = lease.request.resource
                resource is LeaseResource.External && resource.visible.resourceKey == sharedKey
            }
        }
        assertEquals(2, leasesForShared.size)
        assertEquals(
            listOf(PreparationItemId(0L), PreparationItemId(1L)),
            leasesForShared.map { it.request.itemId },
        )

        val firstSeedLeases = success.frameSeeds.first().leases
        val lastResource = firstSeedLeases.last().request.resource
        assertEquals(LeaseResource.External(child), lastResource)
    }

    @Test
    fun repeatedGeometryProgramsInOneItemAreLeasedOnceAsPlannedLogicalResources() {
        val driver = Driver(environment())
        driver.begin(
            listOf(plan(1L, geometries = listOf(geometry("same"), geometry("same"), geometry("other")))),
        )
        driver.completePlanning(1)
        assertEquals(emptyList(), driver.definition().staticOccurrences)

        driver.completeResources(ResourceOperationOutcome.Success(emptyList()))
        val success = driver.installAllLeases()

        val leased = success.frameSeeds.single().leases.map { it.request.resource }
        assertEquals(2, leased.size)
        assertTrue(leased.all { it is LeaseResource.PlannedLogical })
        assertEquals(leased.distinct(), leased)
    }

    @Test
    fun aBatchWithNoDependenciesCommitsHistoryWithoutInstallingAnyLease() {
        val driver = Driver(environment())
        driver.begin(listOf(plan(1L), plan(2L)))
        driver.completePlanning(2)

        val terminal = driver.completeResources(ResourceOperationOutcome.Success(emptyList()))

        val success = assertIs<OrderedPreparationOutcome.Success>(terminal.outcome)
        assertEquals(emptyList(), driver.leaseActions())
        assertTrue(success.frameSeeds.all { it.leases.isEmpty() })
        assertEquals(2L, assertNotNull(assertIs<OrderedPreparationState.Idle>(terminal.state).history).frameIndex)
    }

    @Test
    fun aResourceFailureCommitsNoHistoryAndInstallsNoLease() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")))))
        driver.completePlanning(1)

        val resourceFailure = resourceUnavailableFailure()
        val terminal = driver.completeResources(ResourceOperationOutcome.Failure(resourceFailure))

        val outcome = assertIs<OrderedPreparationOutcome.Failure>(terminal.outcome)
        assertSame(resourceFailure, outcome.failure)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertEquals(emptyList(), driver.leaseActions())
        assertNull(terminal.cursor)
    }

    @Test
    fun aResourceCancellationRetainsItsSelectionUnchangedAndCommitsNoHistory() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")))))
        driver.completePlanning(1)

        val selection = CancellationSelection(CancellationCause.ADAPTER, CancellationId(11L))
        val terminal = driver.completeResources(ResourceOperationOutcome.Cancelled(selection))

        val outcome = assertIs<OrderedPreparationOutcome.Cancelled>(terminal.outcome)
        assertEquals(selection, outcome.cancellation)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertEquals(emptyList(), driver.leaseActions())
    }

    @Test
    fun cancellationDuringResourceResolutionIsAnIdempotentSnapshotBarrier() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")))))
        driver.completePlanning(1)

        val first = CancellationSelection(CancellationCause.CANCEL_PREPARATIONS, CancellationId(4L))
        val requested = driver.submit(OrderedPreparationEvent.CancellationRequested(first))
        assertEquals(
            listOf(
                OrderedPreparationAction.RequestResourceCancellation(
                    invocationId = PreparationInvocationId(1L),
                    cancellation = first,
                ),
            ),
            requested.actions,
        )
        assertEquals(OrderedPreparationCursor.AwaitingResourceCancellation(first), requested.cursor)
        assertNull(requested.outcome)

        val second = CancellationSelection(CancellationCause.CALLER, CancellationId(5L))
        val repeated = driver.submit(OrderedPreparationEvent.CancellationRequested(second))
        assertEquals(emptyList(), repeated.actions)
        assertEquals(OrderedPreparationCursor.AwaitingResourceCancellation(first), repeated.cursor)
        assertNull(repeated.outcome)

        val arbitrated = CancellationSelection(CancellationCause.CANCEL_PREPARATIONS, CancellationId(4L))
        val terminal = driver.completeResources(ResourceOperationOutcome.Cancelled(arbitrated))
        assertEquals(
            OrderedPreparationOutcome.Cancelled(arbitrated),
            terminal.outcome,
        )
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
    }

    @Test
    fun aRequestedCancellationForbidsCommittingASuccessfulResourceOutcome() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")))))
        driver.completePlanning(1)
        val selection = CancellationSelection(CancellationCause.CANCEL_PREPARATIONS, CancellationId(7L))
        driver.submit(OrderedPreparationEvent.CancellationRequested(selection))

        val terminal = driver.completeResources(driver.resourceSuccess())

        assertEquals(OrderedPreparationOutcome.Cancelled(selection), terminal.outcome)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertEquals(emptyList(), driver.leaseActions())
    }

    @Test
    fun cancellationWithNoActiveInvocationIsAnIdempotentNoOpAndNotALatch() {
        val history = committedHistory(frameIndex = 3L)
        val driver = Driver(environment(), history)
        val selection = CancellationSelection(CancellationCause.CANCEL_PREPARATIONS, CancellationId(2L))

        val first = driver.submit(OrderedPreparationEvent.CancellationRequested(selection))
        val second = driver.submit(OrderedPreparationEvent.CancellationRequested(selection))

        listOf(first, second).forEach { transition ->
            assertEquals(OrderedPreparationState.Idle(history), transition.state)
            assertEquals(emptyList(), transition.actions)
            assertNull(transition.cursor)
            assertNull(transition.outcome)
        }

        val begun = driver.begin(listOf(plan(4L)))
        assertNull(begun.outcome)
        assertEquals(OrderedPreparationCursor.AwaitingPlanning(PreparationItemId(0L)), begun.cursor)
    }

    @Test
    fun cancellationDuringPlanningTerminatesWithoutResourceWorkOrHistoryCommit() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(), history)
        driver.begin(listOf(plan(2L), plan(3L)))
        driver.completeOne(0L)

        val selection = CancellationSelection(CancellationCause.CALLER, CancellationId(8L))
        val terminal = driver.submit(OrderedPreparationEvent.CancellationRequested(selection))

        assertEquals(OrderedPreparationOutcome.Cancelled(selection), terminal.outcome)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
        assertEquals(emptyList(), terminal.actions)
        assertEquals(emptyList(), driver.resourceActions())
    }

    @Test
    fun cancellationDuringLeaseInstallationRollsBackAcknowledgedLeases() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")), models = listOf(model("robot")))))
        driver.completePlanning(1)
        driver.completeResources(driver.resourceSuccess())
        driver.acknowledgeInstall(1L)

        val selection = CancellationSelection(CancellationCause.CANCEL_PREPARATIONS, CancellationId(9L))
        val rolling = driver.submit(OrderedPreparationEvent.CancellationRequested(selection))
        assertEquals(
            listOf(LeaseId(1L)),
            rolling.actions.map { assertIs<OrderedPreparationAction.ReleaseLease>(it).lease.leaseId },
        )
        val terminal = driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(1L)))

        assertEquals(OrderedPreparationOutcome.Cancelled(selection), terminal.outcome)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
    }

    @Test
    fun anInstallCancellationRollsBackAndKeepsItsSelection() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")), models = listOf(model("robot")))))
        driver.completePlanning(1)
        driver.completeResources(driver.resourceSuccess())
        driver.acknowledgeInstall(1L)
        val selection = CancellationSelection(CancellationCause.ADAPTER, CancellationId(12L))

        driver.submit(
            OrderedPreparationEvent.LeaseInstallCancelled(driver.pendingInstallRequest(), selection),
        )
        val terminal = driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(1L)))

        assertEquals(OrderedPreparationOutcome.Cancelled(selection), terminal.outcome)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
    }

    @Test
    fun clearingHistoryWhenIdleDropsOnlyTheOrderingDiffAndLodBaseline() {
        val history = committedHistory(frameIndex = 30L, selectedLod = 9)
        val driver = Driver(environment(), history)

        val cleared = driver.submit(OrderedPreparationEvent.ClearHistoryRequested)

        assertEquals(OrderedPreparationOutcome.HistoryCleared, cleared.outcome)
        assertEquals(OrderedPreparationState.Idle(null), cleared.state)
        assertEquals(emptyList(), cleared.actions)
        assertEquals(emptyList(), driver.leaseActions())
        assertNull(cleared.cursor)

        val begun = driver.begin(listOf(plan(1L)))
        assertNull(begun.outcome)
        val request = driver.requestFor(0L)
        assertNull(request.previousPlan)
        assertNull(request.previousSelectedLod)
    }

    @Test
    fun clearingHistoryDuringPreparationFailsWithoutDisturbingTheActiveInvocation() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")))))

        val duringPlanning = driver.submit(OrderedPreparationEvent.ClearHistoryRequested)
        assertRejectedClear(duringPlanning)
        assertEquals(OrderedPreparationCursor.AwaitingPlanning(PreparationItemId(0L)), duringPlanning.cursor)

        driver.completePlanning(1)
        val duringResources = driver.submit(OrderedPreparationEvent.ClearHistoryRequested)
        assertRejectedClear(duringResources)
        assertEquals(OrderedPreparationCursor.AwaitingResources, duringResources.cursor)

        driver.completeResources(driver.resourceSuccess())
        val installingState = driver.state
        val duringInstalls = driver.submit(OrderedPreparationEvent.ClearHistoryRequested)
        assertRejectedClear(duringInstalls)
        assertEquals(installingState, duringInstalls.state)

        val success = driver.installAllLeases()
        assertEquals(1, success.frameSeeds.size)
        assertEquals(2L, assertNotNull(assertIs<OrderedPreparationState.Idle>(driver.state).history).frameIndex)
    }

    @Test
    fun clearingHistoryDuringRollbackIsRejectedAndTheRollbackContinues() {
        val history = committedHistory(frameIndex = 1L)
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR), history)
        driver.begin(listOf(plan(2L, stickers = listOf(sticker("one")), models = listOf(model("robot")))))
        driver.completePlanning(1)
        driver.completeResources(driver.resourceSuccess())
        driver.acknowledgeInstall(1L)
        val failure = leaseInstallFailure()
        driver.submit(OrderedPreparationEvent.LeaseInstallFailed(driver.pendingInstallRequest(), failure))
        val rollingBack = driver.state

        val rejected = driver.submit(OrderedPreparationEvent.ClearHistoryRequested)

        assertRejectedClear(rejected)
        assertEquals(rollingBack, rejected.state)
        assertEquals(
            OrderedPreparationCursor.AwaitingLeaseRelease(
                AcknowledgedLease(LeaseId(1L), driver.installedRequests().first()),
            ),
            rejected.cursor,
        )

        val terminal = driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(1L)))
        assertSame(failure, assertIs<OrderedPreparationOutcome.Failure>(terminal.outcome).failure)
        assertEquals(OrderedPreparationState.Idle(history), terminal.state)
    }

    @Test
    fun outOfBandEventsAreRejectedWithoutStateChange() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        val planned = Driver(environment(basemapStyle = STYLE_LOCATOR)).let { helper ->
            helper.begin(listOf(plan(1L)))
            helper.completeOne(0L)
        }

        assertFailsWith<IllegalArgumentException> {
            driver.submit(
                OrderedPreparationEvent.PlanningCompleted(
                    itemId = PreparationItemId(0L),
                    outcome = FramePlanningOutcome.Success(planned),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            driver.submit(OrderedPreparationEvent.ResourcesCompleted(ResourceOperationOutcome.Success(emptyList())))
        }

        driver.begin(listOf(plan(1L), plan(2L)))
        assertFailsWith<IllegalArgumentException> {
            driver.submit(
                OrderedPreparationEvent.PlanningCompleted(
                    itemId = PreparationItemId(1L),
                    outcome = FramePlanningOutcome.Success(planned),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            driver.submit(OrderedPreparationEvent.ResourcesCompleted(ResourceOperationOutcome.Success(emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(1L)))
        }
    }

    @Test
    fun leaseAcknowledgementsMustMatchTheOutstandingRequest() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        driver.begin(listOf(plan(1L, stickers = listOf(sticker("one")), models = listOf(model("robot")))))
        driver.completePlanning(1)
        driver.completeResources(driver.resourceSuccess())
        val outstanding = driver.pendingInstallRequest()
        val other = driver.expectedLeaseRequests().last()
        assertNotEquals(outstanding, other)

        assertFailsWith<IllegalArgumentException> {
            driver.submit(OrderedPreparationEvent.LeaseInstallAcknowledged(other, LeaseId(1L)))
        }
        driver.acknowledgeInstall(1L)
        assertFailsWith<IllegalArgumentException> {
            driver.submit(OrderedPreparationEvent.LeaseReleaseAcknowledged(LeaseId(1L)))
        }
    }

    @Test
    fun protocolValuesSnapshotTheirListsAndKeepTextShapeOnly() {
        val driver = Driver(environment(basemapStyle = STYLE_LOCATOR))
        driver.begin(listOf(plan(1L, stickers = listOf(sticker("secret-locator")))))
        val planningState = assertIs<OrderedPreparationState.Planning>(driver.state)
        assertNotSame(planningState.plannedItems, planningState.plannedItems)
        assertFalse(planningState.toString().contains("secret-locator"))
        assertFalse(planningState.invocation.toString().contains("secret-locator"))

        driver.completePlanning(1)
        val resolving = assertIs<OrderedPreparationState.ResolvingResources>(driver.state)
        assertNotSame(resolving.plannedItems, resolving.plannedItems)
        assertFalse(resolving.toString().contains("secret-locator"))

        driver.completeResources(driver.resourceSuccess())
        val installing = assertIs<OrderedPreparationState.InstallingLeases>(driver.state)
        assertNotSame(installing.pendingInstalls, installing.pendingInstalls)
        assertNotSame(installing.acknowledgedLeases, installing.acknowledgedLeases)
        assertFalse(installing.toString().contains("secret-locator"))

        val success = driver.installAllLeases()
        assertFalse(success.toString().contains("secret-locator"))
        assertFalse(success.frameSeeds.first().toString().contains("secret-locator"))
    }

    @Test
    fun equalTracesProduceEqualStatesAndTransitions() {
        val plans = listOf(plan(1L, stickers = listOf(sticker("one"))))
        val first = Driver(environment(basemapStyle = STYLE_LOCATOR))
        val second = Driver(environment(basemapStyle = STYLE_LOCATOR))

        val firstBegin = first.begin(plans)
        val secondBegin = second.begin(plans)

        assertEquals(firstBegin.state, secondBegin.state)
        assertEquals(firstBegin.state.hashCode(), secondBegin.state.hashCode())
        assertEquals(firstBegin, secondBegin)
        assertEquals(firstBegin.hashCode(), secondBegin.hashCode())
        assertEquals(
            OrderedPreparationCursor.AwaitingPlanning(PreparationItemId(0L)),
            assertNotNull(firstBegin.cursor),
        )
        assertEquals(1, firstBegin.actions.size)
        assertNotSame(firstBegin.actions, firstBegin.actions)
        assertEquals(
            firstBegin.actions.map { assertIs<OrderedPreparationAction.RunPurePlanning>(it).itemId },
            secondBegin.actions.map { assertIs<OrderedPreparationAction.RunPurePlanning>(it).itemId },
        )
    }

    @Test
    fun committedHistoryRejectsAFrameIndexOrLodOutsideItsRange() {
        assertEquals(0, committedHistory(frameIndex = 0L, selectedLod = 0).selectedLod)
        assertEquals(22, committedHistory(frameIndex = 7L, selectedLod = 22).selectedLod)

        assertFailsWith<IllegalArgumentException> { committedHistory(frameIndex = 0L, selectedLod = -1) }
        assertFailsWith<IllegalArgumentException> { committedHistory(frameIndex = 0L, selectedLod = 23) }
        assertFailsWith<IllegalArgumentException> { committedHistory(frameIndex = -1L, selectedLod = 0) }
    }

    private fun assertRejectedClear(transition: OrderedPreparationTransition) {
        assertFailureOutcome(
            transition = transition,
            code = RenGErrorCode.PREPARATION_IN_PROGRESS,
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = null,
        )
        assertEquals(emptyList(), transition.actions)
    }

    private fun assertFailureOutcome(
        transition: OrderedPreparationTransition,
        code: RenGErrorCode,
        stage: PipelineStage,
        fieldName: String?,
        limit: Long? = null,
        actual: Long? = null,
    ) {
        val outcome = assertIs<OrderedPreparationOutcome.Failure>(transition.outcome)
        assertEquals(code, outcome.failure.code)
        assertEquals(stage, outcome.failure.stage)
        if (fieldName == null) {
            assertNull(outcome.failure.diagnostic)
            return
        }
        val diagnostic = assertNotNull(outcome.failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(stage, diagnostic.stage)
        assertEquals(fieldName, diagnostic.fieldName)
        assertEquals(limit, diagnostic.limit)
        assertEquals(actual, diagnostic.actual)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.statusCode)
    }

    private class Driver(
        private val environment: PreparationEnvironment,
        initialHistory: CommittedFrameHistory? = null,
    ) {
        private val planningCore = FramePlanningCore(
            frameEncoder = FramePlanCanonicalEncoder(PureKotlinSha256),
            frameIdentityRegistry = CanonicalIdentityRegistry(),
            resourceKeyDeriver = ResourceKeyDeriver(PureKotlinSha256),
            rentilePrivateKeyResolver = FakePrivateKeyResolver,
        )
        private val actionLog = mutableListOf<OrderedPreparationAction>()
        private val planningRequests = mutableListOf<Pair<PreparationItemId, FramePlanningRequest>>()
        private val installActionCounts = mutableListOf<Int>()
        private var nextInvocation = 1L

        var state: OrderedPreparationState = OrderedPreparationState.Idle(initialHistory)
            private set

        val planned = mutableListOf<PlannedFrameCore>()

        val actions: List<OrderedPreparationAction>
            get() = ArrayList(actionLog)

        fun submit(event: OrderedPreparationEvent): OrderedPreparationTransition {
            val transition = OrderedPreparationStateMachine.transition(state, event)
            state = transition.state
            actionLog += transition.actions
            transition.actions.filterIsInstance<OrderedPreparationAction.RunPurePlanning>().forEach {
                planningRequests += it.itemId to it.request
            }
            installActionCounts += transition.actions.count {
                it is OrderedPreparationAction.InstallLease
            }
            return transition
        }

        fun begin(plans: List<FramePlan>): OrderedPreparationTransition = submit(
            OrderedPreparationEvent.BeginBatch(
                invocationId = PreparationInvocationId(nextInvocation++),
                plans = plans,
                accessMode = ResourceAccessMode.NORMAL,
                environment = environment,
            ),
        )

        fun requestFor(itemIndex: Long): FramePlanningRequest = assertNotNull(
            planningRequests.lastOrNull { it.first == PreparationItemId(itemIndex) },
            "no pure planning action for item $itemIndex",
        ).second

        fun planningItemIds(): List<PreparationItemId> = planningRequests.map { it.first }

        fun completeOne(itemIndex: Long): PlannedFrameCore {
            val plannedFrame = assertIs<FramePlanningOutcome.Success>(
                planningCore.plan(requestFor(itemIndex)),
            ).planned
            planned += plannedFrame
            submit(
                OrderedPreparationEvent.PlanningCompleted(
                    itemId = PreparationItemId(itemIndex),
                    outcome = FramePlanningOutcome.Success(plannedFrame),
                ),
            )
            return plannedFrame
        }

        fun completePlanning(itemCount: Int) {
            for (index in 0 until itemCount) {
                completeOne(index.toLong())
            }
        }

        fun resourceActions(): List<OrderedPreparationAction.RunResourceOperation> =
            actionLog.filterIsInstance<OrderedPreparationAction.RunResourceOperation>()

        fun leaseActions(): List<OrderedPreparationAction> = actionLog.filter {
            it is OrderedPreparationAction.InstallLease || it is OrderedPreparationAction.ReleaseLease
        }

        fun definition(): ResourceOperationDefinition = assertNotNull(
            resourceActions().lastOrNull(),
            "no resource operation action",
        ).definition

        fun completeResources(outcome: ResourceOperationOutcome): OrderedPreparationTransition =
            submit(OrderedPreparationEvent.ResourcesCompleted(outcome))

        fun discoveredChild(token: String): VisibleResource = visibleResource(
            locator = ResourceLocator("$token.bin"),
            // A RenG-owned class deliberately: this fixture is arbitrary fodder for a lease-ordering
            // proof, and the driver never acquires an engine-keyed class (ADR 0003, ADR 0016).
            resourceClass = ResourceClass.MODEL_TEXTURE,
            maximumResponseBytes = DISCOVERED_CHILD_RESPONSE_BYTES,
        )

        fun resourceSuccess(
            reverseOwners: Boolean = false,
            reverseResources: Boolean = false,
            extraChildren: Map<Int, List<VisibleResource>> = emptyMap(),
        ): ResourceOperationOutcome.Success {
            val sets = planned.mapIndexed { index, plannedFrame ->
                val resources = plannedFrame.staticResourceTraversal
                    .filterIsInstance<StaticResourceReference.External>()
                    .map(::visibleResource)
                    .distinctBy { it.resourceKey } + (extraChildren[index] ?: emptyList())
                OwnerResourceSet(
                    ownerId = ResourceOwnerId(index + 1L),
                    resources = if (reverseResources) resources.reversed() else resources,
                )
            }
            return ResourceOperationOutcome.Success(if (reverseOwners) sets.reversed() else sets)
        }

        fun expectedLeaseRequests(): List<LeaseInstallRequest> {
            val requests = mutableListOf<LeaseInstallRequest>()
            planned.forEachIndexed { index, plannedFrame ->
                val itemId = PreparationItemId(index.toLong())
                val leased = mutableListOf<LeaseResource>()
                plannedFrame.staticResourceTraversal.forEach { reference ->
                    val resource = when (reference) {
                        is StaticResourceReference.External -> LeaseResource.External(visibleResource(reference))
                        is StaticResourceReference.GeometryProgram ->
                            LeaseResource.PlannedLogical(reference.resourceKey)
                    }
                    if (resource !in leased) {
                        leased += resource
                    }
                }
                leased.forEachIndexed { traversalIndex, resource ->
                    requests += LeaseInstallRequest(itemId, traversalIndex, resource)
                }
            }
            return requests
        }

        fun installedRequests(): List<LeaseInstallRequest> =
            actionLog.filterIsInstance<OrderedPreparationAction.InstallLease>().map { it.request }

        fun installTransitionActionCounts(): List<Int> = ArrayList(installActionCounts)

        fun releasedLeaseIds(): List<LeaseId> =
            actionLog.filterIsInstance<OrderedPreparationAction.ReleaseLease>().map { it.lease.leaseId }

        fun pendingInstallRequest(): LeaseInstallRequest = assertNotNull(
            actionLog.filterIsInstance<OrderedPreparationAction.InstallLease>().lastOrNull(),
            "no outstanding lease install action",
        ).request

        fun acknowledgeInstall(leaseId: Long): OrderedPreparationTransition = submit(
            OrderedPreparationEvent.LeaseInstallAcknowledged(pendingInstallRequest(), LeaseId(leaseId)),
        )

        fun installAllLeases(): OrderedPreparationOutcome.Success {
            var leaseId = 1L
            var last: OrderedPreparationTransition? = null
            while (state is OrderedPreparationState.InstallingLeases) {
                last = acknowledgeInstall(leaseId++)
            }
            return assertIs<OrderedPreparationOutcome.Success>(assertNotNull(last).outcome)
        }
    }

    private companion object {
        val STYLE_LOCATOR = ResourceLocator("basemap-style.json")

        fun environment(
            basemapStyle: ResourceLocator? = null,
            maximumPreparationBatchSize: Int = 256,
            maximumConcurrentResourceOperations: Int = 8,
        ): PreparationEnvironment = PreparationEnvironment(
            outputPixelSize = OutputPixelSize(100, 100),
            basemapStyle = basemapStyle,
            resourceLimits = ResourceLimits(),
            maximumBasemapTileInstances = 512,
            maximumPreparationBatchSize = maximumPreparationBatchSize,
            maximumConcurrentResourceOperations = maximumConcurrentResourceOperations,
        )

        fun plan(
            frameIndex: Long,
            stickers: List<Sticker> = emptyList(),
            models: List<Model> = emptyList(),
            geometries: List<Geometry> = emptyList(),
        ): FramePlan = FramePlan(
            frameIndex = frameIndex,
            camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
            stickers = stickers,
            models = models,
            geometries = geometries,
        )

        fun sticker(token: String): Sticker = Sticker(screenPlacement(), ResourceLocator("$token.png"))

        // MAP, not SCREEN: ADR 0029 refuses a SCREEN-positioned Model at frame planning, and this
        // state-machine test is not about draw regime.
        fun model(glb: String, texture: String? = null): Model = Model(
            placement = mapPlacement(),
            glb = ResourceLocator("$glb.glb"),
            texture = texture?.let { ResourceLocator("$it.png") },
        )

        fun geometry(token: String): Geometry = Geometry(
            topLeft = Vector3(20.0, 0.0, 0.0),
            bottomRight = Vector3(10.0, 1.0, 0.0),
            shaderPair = ShaderPair(
                vertexSource = "#version 300 es\n// $token-vertex",
                fragmentSource = "#version 300 es\n// $token-fragment",
            ),
        )

        fun screenPlacement(): Placement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(10.0, 20.0, 0.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )

        fun mapPlacement(): Placement = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(10.0, 20.0, 0.0),
            rotationMode = AnchoringMode.MAP,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.MAP,
            scale = 1.0,
        )

        fun committedHistory(frameIndex: Long, selectedLod: Int = 0): CommittedFrameHistory =
            CommittedFrameHistory(
                frameIndex = frameIndex,
                encodedPlan = historyEncodedPlan(frameIndex),
                selectedLod = selectedLod,
            )

        fun historyEncodedPlan(frameIndex: Long): EncodedFramePlan =
            FramePlanCanonicalEncoder(PureKotlinSha256).encode(
                FramePlan(frameIndex = frameIndex, camera = Camera(1.0, 1.0, 1.0, 0.0, 0.0)),
            )

        fun externalReference(
            token: String,
            resourceClass: ResourceClass,
        ): StaticResourceReference.External {
            val locator = ResourceLocator("$token.bin")
            val derived = ResourceKeyDeriver(PureKotlinSha256).external(resourceClass, locator)
            return StaticResourceReference.External(
                resourceClass = resourceClass,
                locator = locator,
                maximumResponseBytes = DISCOVERED_CHILD_RESPONSE_BYTES,
                resourceKey = derived.key,
                rawKey = assertNotNull(derived.rawKey),
                privateRentileKey = FakePrivateKeyResolver.resolve(locator, resourceClass),
                canonicalIdentity = derived.identity,
            )
        }

        fun visibleResource(reference: StaticResourceReference.External): VisibleResource = visibleResource(
            locator = reference.locator,
            resourceClass = reference.resourceClass,
            maximumResponseBytes = reference.maximumResponseBytes,
        )

        fun visibleResource(
            locator: ResourceLocator,
            resourceClass: ResourceClass,
            maximumResponseBytes: Long,
        ): VisibleResource {
            val resourceKey = ResourceKeyDeriver(PureKotlinSha256).external(resourceClass, locator).key
            return VisibleResource(
                resourceKey = resourceKey,
                content = ResolvedResourceContent(
                    route = ResourceRouteKey(
                        accessMode = ResourceAccessMode.NORMAL,
                        locator = locator,
                        resourceClass = resourceClass,
                        maximumResponseBytes = maximumResponseBytes,
                    ),
                    resourceKey = resourceKey,
                    stored = STORED_RESOURCE,
                    provenance = ContentProvenance.RESIDENT,
                ),
            )
        }

        fun shaderFailure(): FailureDescriptor = FailureDescriptor(
            code = RenGErrorCode.INVALID_VALUE,
            stage = PipelineStage.FRAME_PLANNING,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = DiagnosticField.SHADER_PAIR,
            ),
        )

        fun leaseInstallFailure(): FailureDescriptor = FailureDescriptor(
            code = RenGErrorCode.GPU_OPERATION_FAILED,
            stage = PipelineStage.GPU_RESOURCE,
            diagnostic = failureContextDiagnostic(stage = PipelineStage.GPU_RESOURCE),
        )

        fun resourceUnavailableFailure(): FailureDescriptor {
            val reference = externalReference("missing", ResourceClass.STICKER_IMAGE)
            return FailureDescriptor(
                code = RenGErrorCode.RESOURCE_UNAVAILABLE,
                stage = PipelineStage.RESOURCE_LOOKUP,
                diagnostic = failureContextDiagnostic(
                    stage = PipelineStage.RESOURCE_LOOKUP,
                    fieldName = DiagnosticField.RESOURCE,
                    resourceClass = reference.resourceClass,
                    resourceKey = reference.resourceKey,
                ),
            )
        }

        const val DISCOVERED_CHILD_RESPONSE_BYTES: Long = 1024L

        val STORED_RESOURCE: StoredRawResource = StoredRawResource(
            bytes = byteArrayOf(1, 2, 3),
            contentDigest = "a".repeat(64),
            metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
        )

        object FakePrivateKeyResolver : RentilePrivateKeyResolver {
            override fun resolve(
                locator: ResourceLocator,
                resourceClass: ResourceClass,
            ): RentilePrivateKey = RentilePrivateKey("${resourceClass.name}|${locator.value}")
        }
    }
}
