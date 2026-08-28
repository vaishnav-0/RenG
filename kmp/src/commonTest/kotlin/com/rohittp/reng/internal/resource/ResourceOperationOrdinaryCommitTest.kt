package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.internal.firewall.engineKeyedResourceClassOf
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationOrdinaryCommitTest {
    // This file's ORDINARY_CLASS_GATES is a hand-written mirror of production's own table, and every
    // table-driven test below trusts it. Binding the two here is what stops the mirror from drifting --
    // and, since the production table is what decides which classes RenG gates at all, it is also the
    // assertion that pins the ownership ruling: the eight engine-keyed classes answer `null`, so the
    // driver can never emit a class gate over one of them.
    @Test
    fun ordinaryClassGatesMatchProductionForEveryClassAndNameNoEngineKeyedOne() {
        ResourceClass.entries.forEach { resourceClass ->
            assertEquals(
                ORDINARY_CLASS_GATES[resourceClass],
                ordinaryResourceClassGates(resourceClass),
                resourceClass.name,
            )
        }
        assertEquals(ResourceClass.entries.toSet() - UNGATED_CLASSES, ORDINARY_CLASS_GATES.keys)
        UNGATED_CLASSES.forEach { ungated ->
            assertNull(ordinaryResourceClassGates(ungated), ungated.name)
        }
        // Bind the ruling to the firewall's own partition rather than to this file's hand-written table.
        // Without this the test compares two tables that could drift together; with it, any class the
        // firewall says the engine keys must have no driver gate, which is what the name claims.
        ResourceClass.entries.filter { engineKeyedResourceClassOf(it) != null }.forEach { engineKeyed ->
            assertNull(
                ordinaryResourceClassGates(engineKeyed),
                "${engineKeyed.name} is engine-keyed, so RenG's driver must declare no class gate for it",
            )
        }
    }

    @Test
    fun advancingPendingClassGatesEmitsOnlyTheFirstExactGate() {
        ORDINARY_CLASS_GATES.forEach { (resourceClass, gates) ->
            val driver = CommitDriver(singleRouteDefinition(resourceClass, ContentProvenance.TRANSPORT_200))
            driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
            val content = assertIs<PendingClassGates>(driver.record(0L).cursor).content
            val expectedActionId = ResourceActionId(driver.state.nextActionId)

            driver.event(AdvancePendingClassGates(0L))

            assertEquals(
                listOf(ValidateResourceClass(expectedActionId, 0L, content, gates.first())),
                driver.actions,
                resourceClass.name,
            )
            assertEquals(
                AwaitingClassGate(expectedActionId, 0L, content, 0, gates.first()),
                driver.record(0L).cursor,
                resourceClass.name,
            )
            assertNull(driver.outcome, resourceClass.name)
            assertFalse(driver.record(0L).visibilityInstalled, resourceClass.name)
        }
    }

    @Test
    fun advancingAMismatchedOrdinalOrUngatedClassIsRejectedWithoutChangingState() {
        val driver = CommitDriver(
            twoRouteDefinition(
                first = ResourceClass.MODEL_GLB,
                second = ResourceClass.STICKER_IMAGE,
                concurrency = 2,
            ),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        driver.beginLookup(1L)
        assertIs<SampleClock>(driver.actions.single())
        val before = driver.state

        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(before, AdvancePendingClassGates(1L))
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(before, AdvancePendingClassGates(7L))
        }
        assertIs<PendingClassGates>(before.routeRecords.single { it.ordinal == 0L }.cursor)
        assertIs<AwaitingClockSample>(before.routeRecords.single { it.ordinal == 1L }.cursor)

        val advanced = ResourceOperationStateMachine.transition(before, AdvancePendingClassGates(0L))
        assertIs<ValidateResourceClass>(advanced.actions.single())
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                requireNotNull(advanced.state),
                AdvancePendingClassGates(0L),
            )
        }

        UNGATED_CLASSES.forEach { ungated ->
            val ungatedDriver = CommitDriver(
                singleRouteDefinition(ungated, ContentProvenance.TRANSPORT_200),
            )
            ungatedDriver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
            assertFailsWith<IllegalArgumentException>(ungated.name) {
                ResourceOperationStateMachine.transition(
                    ungatedDriver.state,
                    AdvancePendingClassGates(0L),
                )
            }
        }
    }

    @Test
    fun transportContentPassesEveryOrderedGateThenWritesOnceThenInstalls() {
        ORDINARY_CLASS_GATES.forEach { (resourceClass, gates) ->
            listOf(ContentProvenance.TRANSPORT_200, ContentProvenance.TRANSPORT_304_MERGED)
                .forEach { provenance ->
                    val label = "${resourceClass.name}/$provenance"
                    val driver = CommitDriver(singleRouteDefinition(resourceClass, provenance))
                    driver.driveToPendingClassGates(0L, provenance)
                    val content = driver.passGates(0L, gates, label)

                    val write = assertIs<WriteStore>(driver.actions.single(), label)
                    assertEquals(driver.record(0L).registration.rawKey, write.rawKey, label)
                    assertEquals(content.stored, write.resource, label)
                    assertEquals(AwaitingStoreWrite(write.actionId, 0L, content), driver.record(0L).cursor, label)
                    assertFalse(driver.record(0L).visibilityInstalled, label)
                    assertTrue(driver.emitted.filterIsInstance<InstallVisibility>().isEmpty(), label)

                    driver.event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
                    val install = assertIs<InstallVisibility>(driver.actions.single(), label)
                    assertEquals(content, install.content, label)
                    assertEquals(
                        AwaitingVisibilityInstall(install.actionId, 0L, content),
                        driver.record(0L).cursor,
                        label,
                    )
                    assertFalse(driver.record(0L).visibilityInstalled, label)
                    assertNull(driver.outcome, label)

                    driver.event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded))
                    assertTrue(driver.record(0L).visibilityInstalled, label)
                    assertNull(driver.record(0L).cursor, label)
                    assertEquals(ResourceRouteStatus.RESOLVED, driver.record(0L).status, label)
                    assertEquals(
                        ResourceOperationOutcome.Success(
                            listOf(
                                OwnerResourceSet(
                                    ResourceOwnerId(FIRST_OWNER_ID),
                                    listOf(VisibleResource(content.resourceKey, content)),
                                ),
                            ),
                        ),
                        driver.outcome,
                        label,
                    )

                    assertEquals(gates.size, driver.emitted.filterIsInstance<ValidateResourceClass>().size, label)
                    assertEquals(1, driver.emitted.filterIsInstance<WriteStore>().size, label)
                    assertEquals(1, driver.emitted.filterIsInstance<InstallVisibility>().size, label)
                    driver.assertNoRecoveryActions(label)
                }
        }
    }

    @Test
    fun residentAndStoreContentInstallWithoutAnyWrite() {
        ORDINARY_CLASS_GATES.forEach { (resourceClass, gates) ->
            listOf(ContentProvenance.RESIDENT, ContentProvenance.STORE).forEach { provenance ->
                val label = "${resourceClass.name}/$provenance"
                val driver = CommitDriver(singleRouteDefinition(resourceClass, provenance))
                driver.driveToPendingClassGates(0L, provenance)
                val content = driver.passGates(0L, gates, label)
                assertEquals(provenance, content.provenance, label)

                val install = assertIs<InstallVisibility>(driver.actions.single(), label)
                assertEquals(content, install.content, label)
                driver.event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded))

                assertTrue(driver.record(0L).visibilityInstalled, label)
                assertIs<ResourceOperationOutcome.Success>(driver.outcome, label)
                assertTrue(driver.emitted.filterIsInstance<WriteStore>().isEmpty(), label)
                driver.assertNoRecoveryActions(label)
            }
        }
    }

    @Test
    fun aFailedGateOnStoreContentAlwaysSelectsStoreIntegrity() {
        ORDINARY_CLASS_GATES.forEach { (resourceClass, gates) ->
            gates.indices.forEach { failingIndex ->
                val label = "${resourceClass.name}/${gates[failingIndex]}"
                val driver = CommitDriver(singleRouteDefinition(resourceClass, ContentProvenance.STORE))
                driver.driveToPendingClassGates(0L, ContentProvenance.STORE)
                val content = driver.failGateAt(0L, gates, failingIndex, label)

                assertResourceFailure(
                    outcome = driver.outcome,
                    code = RenGErrorCode.STORE_INTEGRITY_FAILED,
                    stage = PipelineStage.STORE_VALIDATION,
                    expectedField = DiagnosticField.RESOURCE.wireName,
                    resourceClass = resourceClass,
                    resourceKey = content.resourceKey,
                    label = label,
                )
                assertTrue(driver.actions.isEmpty(), label)
                assertFalse(driver.record(0L).visibilityInstalled, label)
                assertTrue(driver.emitted.filterIsInstance<WriteStore>().isEmpty(), label)
                assertTrue(driver.emitted.filterIsInstance<CallTransport>().isEmpty(), label)
                assertTrue(driver.emitted.filterIsInstance<InstallVisibility>().isEmpty(), label)
                driver.assertNoRecoveryActions(label)
            }
        }
    }

    @Test
    fun aFailedGateOnTransportContentUsesTheExactGateMapping() {
        ORDINARY_CLASS_GATES.forEach { (resourceClass, gates) ->
            gates.indices.forEach { failingIndex ->
                val gate = gates[failingIndex]
                val label = "${resourceClass.name}/$gate"
                val driver = CommitDriver(singleRouteDefinition(resourceClass, ContentProvenance.TRANSPORT_200))
                driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
                val content = driver.failGateAt(0L, gates, failingIndex, label)
                val expected = expectedGateFailure(gate)

                assertResourceFailure(
                    outcome = driver.outcome,
                    code = expected.first,
                    stage = expected.second,
                    expectedField = DiagnosticField.RESOURCE.wireName,
                    resourceClass = resourceClass,
                    resourceKey = content.resourceKey,
                    label = label,
                )
                assertTrue(driver.actions.isEmpty(), label)
                assertFalse(driver.record(0L).visibilityInstalled, label)
                assertTrue(driver.emitted.filterIsInstance<WriteStore>().isEmpty(), label)
                assertTrue(driver.emitted.filterIsInstance<InstallVisibility>().isEmpty(), label)
                driver.assertNoRecoveryActions(label)
            }
        }
    }

    @Test
    fun cancelledGatesAndInstallsStayOpaqueAdapterCancellations() {
        val cancellation = CancellationSelection(CancellationCause.ADAPTER, CancellationId(41L))

        val gateDriver = CommitDriver(
            singleRouteDefinition(ResourceClass.MODEL_GLB, ContentProvenance.TRANSPORT_200),
        )
        gateDriver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        gateDriver.event(AdvancePendingClassGates(0L))
        val gate = assertIs<ValidateResourceClass>(gateDriver.actions.single())
        gateDriver.event(
            ResourceClassValidationCompleted(
                gate.actionId,
                SuppliedValidationOutcome.Cancelled(cancellation),
            ),
        )
        assertEquals(
            ResourceOperationOutcome.Cancelled(cancellation),
            gateDriver.outcome,
        )
        assertTrue(gateDriver.actions.isEmpty())
        assertFalse(gateDriver.record(0L).visibilityInstalled)

        val installDriver = CommitDriver(
            singleRouteDefinition(ResourceClass.STICKER_IMAGE, ContentProvenance.RESIDENT),
        )
        installDriver.driveToPendingClassGates(0L, ContentProvenance.RESIDENT)
        installDriver.passGates(0L, ORDINARY_CLASS_GATES.getValue(ResourceClass.STICKER_IMAGE), "install")
        val install = assertIs<InstallVisibility>(installDriver.actions.single())
        installDriver.event(
            VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Cancelled(cancellation)),
        )
        assertEquals(ResourceOperationOutcome.Cancelled(cancellation), installDriver.outcome)
        assertFalse(installDriver.record(0L).visibilityInstalled)

        listOf(CancellationCause.CALLER, CancellationCause.CANCEL_PREPARATIONS).forEach { cause ->
            val external = CancellationSelection(cause, CancellationId(5L))
            assertFailsWith<IllegalArgumentException> { SuppliedValidationOutcome.Cancelled(external) }
            assertFailsWith<IllegalArgumentException> { SuppliedInstallOutcome.Cancelled(external) }
        }
    }

    @Test
    fun storeWriteFailureIsSanitizedWhileItsCancellationRemainsCancellation() {
        val gates = ORDINARY_CLASS_GATES.getValue(ResourceClass.MODEL_TEXTURE)

        val failedDriver = CommitDriver(
            singleRouteDefinition(ResourceClass.MODEL_TEXTURE, ContentProvenance.TRANSPORT_200),
        )
        failedDriver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val content = failedDriver.passGates(0L, gates, "write-failure")
        val write = assertIs<WriteStore>(failedDriver.actions.single())
        failedDriver.event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Failed))

        assertResourceFailure(
            outcome = failedDriver.outcome,
            code = RenGErrorCode.STORE_WRITE_FAILED,
            stage = PipelineStage.STORE_WRITE,
            expectedField = null,
            resourceClass = ResourceClass.MODEL_TEXTURE,
            resourceKey = content.resourceKey,
            label = "write-failure",
        )
        assertFalse(failedDriver.record(0L).visibilityInstalled)
        assertTrue(failedDriver.emitted.filterIsInstance<InstallVisibility>().isEmpty())
        failedDriver.assertNoRecoveryActions("write-failure")

        val cancellation = CancellationSelection(CancellationCause.ADAPTER, CancellationId(87L))
        val cancelledDriver = CommitDriver(
            singleRouteDefinition(ResourceClass.MODEL_TEXTURE, ContentProvenance.TRANSPORT_200),
        )
        cancelledDriver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        cancelledDriver.passGates(0L, gates, "write-cancellation")
        val cancelledWrite = assertIs<WriteStore>(cancelledDriver.actions.single())
        cancelledDriver.event(
            StoreWriteCompleted(cancelledWrite.actionId, SuppliedCallOutcome.Cancelled(cancellation)),
        )

        assertEquals(ResourceOperationOutcome.Cancelled(cancellation), cancelledDriver.outcome)
        assertFalse(cancelledDriver.record(0L).visibilityInstalled)
    }

    @Test
    fun aFailedVisibilityInstallPropagatesItsSanitizedFailure() {
        val installFailure = FailureDescriptor(
            code = RenGErrorCode.GPU_OPERATION_FAILED,
            stage = PipelineStage.GPU_RESOURCE,
            diagnostic = failureContextDiagnostic(stage = PipelineStage.GPU_RESOURCE),
        )
        val driver = CommitDriver(
            singleRouteDefinition(ResourceClass.MODEL_TEXTURE, ContentProvenance.RESIDENT),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.RESIDENT)
        driver.passGates(0L, ORDINARY_CLASS_GATES.getValue(ResourceClass.MODEL_TEXTURE), "install-failure")
        val install = assertIs<InstallVisibility>(driver.actions.single())

        driver.event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Failed(installFailure)))

        assertEquals(installFailure, assertIs<ResourceOperationOutcome.Failure>(driver.outcome).failure)
        assertFalse(driver.record(0L).visibilityInstalled)
        assertTrue(driver.actions.isEmpty())
        driver.assertNoRecoveryActions("install-failure")
    }

    @Test
    fun ownerSetsComeOnlyFromAcknowledgedInstallsAndDeduplicateAtFirstTraversalOccurrence() {
        val fetched = registration("a", ResourceClass.MODEL_GLB, ResourceAccessMode.RELOAD)
        val resident = registration("b", ResourceClass.STICKER_IMAGE, ResourceAccessMode.NORMAL)
        val driver = CommitDriver(
            definitionOf(
                concurrency = 1,
                occurrences = listOf(
                    occurrence(1L, 10L, fetched),
                    occurrence(2L, 10L, fetched),
                    occurrence(3L, 10L, resident),
                    occurrence(4L, 20L, resident),
                ),
            ),
        )
        assertEquals(listOf(0L), driver.state.activeRouteOrdinals)

        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val fetchedContent = driver.passGates(
            0L,
            ORDINARY_CLASS_GATES.getValue(ResourceClass.MODEL_GLB),
            "fetched",
        )
        val write = assertIs<WriteStore>(driver.actions.single())
        driver.event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
        val fetchedInstall = assertIs<InstallVisibility>(driver.actions.single())
        driver.event(VisibilityInstallCompleted(fetchedInstall.actionId, SuppliedInstallOutcome.Succeeded))

        assertNull(driver.outcome)
        assertEquals(listOf(StartRoute(1L, resident)), driver.actions)

        driver.driveToPendingClassGates(1L, ContentProvenance.RESIDENT)
        val residentContent = driver.passGates(
            1L,
            ORDINARY_CLASS_GATES.getValue(ResourceClass.STICKER_IMAGE),
            "resident",
        )
        val residentInstall = assertIs<InstallVisibility>(driver.actions.single())
        driver.event(VisibilityInstallCompleted(residentInstall.actionId, SuppliedInstallOutcome.Succeeded))

        val fetchedVisible = VisibleResource(fetched.resourceKey, fetchedContent)
        val residentVisible = VisibleResource(resident.resourceKey, residentContent)
        assertEquals(
            ResourceOperationOutcome.Success(
                listOf(
                    OwnerResourceSet(ResourceOwnerId(10L), listOf(fetchedVisible, residentVisible)),
                    OwnerResourceSet(ResourceOwnerId(20L), listOf(residentVisible)),
                ),
            ),
            driver.outcome,
        )
        assertEquals(1, driver.emitted.filterIsInstance<WriteStore>().size)
        assertEquals(2, driver.emitted.filterIsInstance<InstallVisibility>().size)
        driver.assertNoRecoveryActions("owner sets")
    }

    @Test
    fun visibleResourceOwnerSetsAndSuccessAreStructuralAndFreshCopied() {
        val content = resolvedContent(registration("a", ResourceClass.STICKER_IMAGE, ResourceAccessMode.NORMAL))
        val other = resolvedContent(registration("b", ResourceClass.MODEL_GLB, ResourceAccessMode.NORMAL))
        val first = VisibleResource(content.resourceKey, content)
        val second = VisibleResource(other.resourceKey, other)
        assertEquals(first, VisibleResource(content.resourceKey, content))
        assertEquals(first.hashCode(), VisibleResource(content.resourceKey, content).hashCode())

        listOf(emptyList(), listOf(first), listOf(first, second)).forEach { resources ->
            val label = "size=${resources.size}"
            val ownerInput = resources.toMutableList()
            val ownerSet = OwnerResourceSet(ResourceOwnerId(3L), ownerInput)
            ownerInput.clear()
            assertEquals(OwnerResourceSet(ResourceOwnerId(3L), resources), ownerSet, label)
            assertEquals(
                OwnerResourceSet(ResourceOwnerId(3L), resources).hashCode(),
                ownerSet.hashCode(),
                label,
            )
            assertFreshCopy(ownerSet.resources, ownerSet.resources, resources, label)

            val setInput = mutableListOf(ownerSet)
            val success = ResourceOperationOutcome.Success(setInput)
            setInput.clear()
            assertEquals(
                ResourceOperationOutcome.Success(listOf(OwnerResourceSet(ResourceOwnerId(3L), resources))),
                success,
                label,
            )
            assertEquals(
                ResourceOperationOutcome.Success(listOf(OwnerResourceSet(ResourceOwnerId(3L), resources)))
                    .hashCode(),
                success.hashCode(),
                label,
            )
            assertFreshCopy(success.resourceSets, success.resourceSets, listOf(ownerSet), label)
        }

        val emptySuccess = ResourceOperationOutcome.Success(emptyList())
        assertEquals(ResourceOperationOutcome.Success(emptyList()), emptySuccess)
        assertFreshCopy(emptySuccess.resourceSets, emptySuccess.resourceSets, emptyList(), "empty owner sets")
    }

    @Test
    fun commitCursorsAndInstalledVisibilityAreValidatedAndBlockRetirement() {
        val driver = CommitDriver(
            singleRouteDefinition(ResourceClass.MODEL_TEXTURE, ContentProvenance.TRANSPORT_200),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val pendingState = driver.state
        val record = pendingState.routeRecords.single()
        val content = assertIs<PendingClassGates>(record.cursor).content

        driver.event(AdvancePendingClassGates(0L))
        val gateAction = assertIs<ValidateResourceClass>(driver.actions.single())
        val gateState = driver.state

        val refusedCompletion = assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                gateState,
                RouteCompleted(0L, ResourceRouteOutcome.Failure(storeReadFailure(content))),
            )
        }
        assertEquals("route completion requires no in-flight adapter action", refusedCompletion.message)

        assertFailsWith<IllegalArgumentException> {
            copyState(
                gateState,
                routeRecords = listOf(
                    routeRecord(record, AwaitingClassGate(gateAction.actionId, 0L, content, 0, WRONG_GATE)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                gateState,
                routeRecords = listOf(
                    routeRecord(record, AwaitingStoreWrite(gateAction.actionId, 1L, content)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                pendingState,
                routeRecords = listOf(
                    RouteRecord(
                        registration = record.registration,
                        joinedOccurrenceIds = record.joinedOccurrenceIds,
                        ordinal = 0L,
                        cursor = null,
                        status = ResourceRouteStatus.RUNNING,
                        lookup = null,
                        visibilityInstalled = true,
                    ),
                ),
            )
        }

        val residentDriver = CommitDriver(
            singleRouteDefinition(ResourceClass.STICKER_IMAGE, ContentProvenance.RESIDENT),
        )
        residentDriver.driveToPendingClassGates(0L, ContentProvenance.RESIDENT)
        val residentRecord = residentDriver.state.routeRecords.single()
        val residentContent = assertIs<PendingClassGates>(residentRecord.cursor).content
        assertFailsWith<IllegalArgumentException> {
            copyState(
                residentDriver.state,
                routeRecords = listOf(
                    routeRecord(residentRecord, AwaitingStoreWrite(ResourceActionId(50L), 0L, residentContent)),
                ),
            )
        }
    }

    @Test
    fun aDiscoveryParentInstallsItsOwnContentAndThenItsChildBeforeSucceeding() {
        val parent = discoveryOccurrence()
        val driver = CommitDriver(
            definitionOf(concurrency = 1, occurrences = listOf(parent)),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val parentContent = driver.passGates(
            0L,
            ORDINARY_CLASS_GATES.getValue(ResourceClass.MODEL_TEXTURE),
            "parent",
        )
        val parentWrite = assertIs<WriteStore>(driver.actions.single())
        driver.event(StoreWriteCompleted(parentWrite.actionId, SuppliedCallOutcome.Success(Unit)))
        val parentInstall = assertIs<InstallVisibility>(driver.actions.single())

        driver.event(VisibilityInstallCompleted(parentInstall.actionId, SuppliedInstallOutcome.Succeeded))

        assertTrue(driver.actions.isEmpty())
        assertNull(driver.outcome)
        assertTrue(driver.record(0L).visibilityInstalled)
        assertEquals(PendingChildDiscovery(0L, parentContent), driver.record(0L).cursor)
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(0L).status)
        assertEquals(listOf(0L), driver.state.activeRouteOrdinals)

        driver.event(RouteReadyForDiscovery(0L, parent.id))

        assertEquals(listOf(DiscoverChildren(0L, parent.id)), driver.actions)
        assertNull(driver.outcome)
        assertEquals(1L, driver.state.nextRetirementOrdinal)

        val child = discoveredChild()
        driver.event(
            ChildrenDiscovered(
                parent.id,
                listOf(DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(0), child)),
            ),
        )

        assertEquals(listOf(StartRoute(1L, child.registration)), driver.actions)
        driver.driveToPendingClassGates(1L, ContentProvenance.TRANSPORT_200)
        val childContent = driver.passGates(
            1L,
            ORDINARY_CLASS_GATES.getValue(ResourceClass.STICKER_IMAGE),
            "child",
        )
        val childWrite = assertIs<WriteStore>(driver.actions.single())
        driver.event(StoreWriteCompleted(childWrite.actionId, SuppliedCallOutcome.Success(Unit)))
        val childInstall = assertIs<InstallVisibility>(driver.actions.single())

        driver.event(VisibilityInstallCompleted(childInstall.actionId, SuppliedInstallOutcome.Succeeded))

        val success = assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        assertEquals(
            listOf(
                OwnerResourceSet(
                    ResourceOwnerId(FIRST_OWNER_ID),
                    listOf(
                        VisibleResource(parentContent.resourceKey, parentContent),
                        VisibleResource(childContent.resourceKey, childContent),
                    ),
                ),
            ),
            success.resourceSets,
        )
        assertTrue(driver.state.traversal.frontierStack.isEmpty())
        assertEquals(2L, driver.state.nextRetirementOrdinal)
        driver.assertNoRecoveryActions("discovery parent")
    }

    @Test
    fun aVisibilityInstallCursorRequiresItsAcknowledgedStoreWrite() {
        val driver = CommitDriver(
            singleRouteDefinition(ResourceClass.STICKER_IMAGE, ContentProvenance.TRANSPORT_200),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val content = driver.passGates(0L, listOf(ResourceClassGate.DECODE_PNG), "acknowledged write")
        val write = assertIs<WriteStore>(driver.actions.single())
        val writeState = driver.state
        val writeRecord = writeState.routeRecords.single()
        assertFalse(writeRecord.storeWriteAcknowledged)

        assertEquals(
            "visibility install cursor requires matching content after its required write",
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    writeState,
                    routeRecords = listOf(
                        routeRecord(writeRecord, AwaitingVisibilityInstall(write.actionId, 0L, content)),
                    ),
                )
            }.message,
        )

        driver.event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
        val install = assertIs<InstallVisibility>(driver.actions.single())
        val acknowledged = driver.record(0L)
        assertTrue(acknowledged.storeWriteAcknowledged)
        assertEquals(AwaitingVisibilityInstall(install.actionId, 0L, content), acknowledged.cursor)
        assertEquals(
            "Store write cursor requires matching content that must still be written",
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    driver.state,
                    routeRecords = listOf(
                        routeRecord(acknowledged, AwaitingStoreWrite(install.actionId, 0L, content)),
                    ),
                )
            }.message,
        )
        assertEquals(
            "class gate cursor requires matching unwritten content at its gate index",
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    driver.state,
                    routeRecords = listOf(
                        routeRecord(
                            acknowledged,
                            AwaitingClassGate(install.actionId, 0L, content, 0, ResourceClassGate.DECODE_PNG),
                        ),
                    ),
                )
            }.message,
        )

        driver.event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded))
        assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        assertTrue(driver.record(0L).storeWriteAcknowledged)
        assertEquals(1, driver.emitted.filterIsInstance<WriteStore>().size)

        val residentDriver = CommitDriver(
            singleRouteDefinition(ResourceClass.STICKER_IMAGE, ContentProvenance.RESIDENT),
        )
        residentDriver.driveToPendingClassGates(0L, ContentProvenance.RESIDENT)
        val residentContent = residentDriver.passGates(
            0L,
            listOf(ResourceClassGate.DECODE_PNG),
            "resident install",
        )
        val residentInstall = assertIs<InstallVisibility>(residentDriver.actions.single())
        assertEquals(
            AwaitingVisibilityInstall(residentInstall.actionId, 0L, residentContent),
            residentDriver.record(0L).cursor,
        )
        assertFalse(residentDriver.record(0L).storeWriteAcknowledged)
        assertEquals(
            "an acknowledged Store write requires selected content that must be written",
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    residentDriver.state,
                    routeRecords = listOf(
                        routeRecord(
                            residentDriver.record(0L),
                            requireNotNull(residentDriver.record(0L).cursor),
                            storeWriteAcknowledged = true,
                        ),
                    ),
                )
            }.message,
        )
    }

    @Test
    fun installedVisibilityAdmitsOnlyAPendingChildDiscoveryCursor() {
        val driver = CommitDriver(
            singleRouteDefinition(ResourceClass.MODEL_GLB, ContentProvenance.TRANSPORT_200),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val inFlightStates = mutableListOf(driver.state)
        driver.event(AdvancePendingClassGates(0L))
        val firstGate = assertIs<ValidateResourceClass>(driver.actions.single())
        inFlightStates += driver.state
        driver.event(ResourceClassValidationCompleted(firstGate.actionId, SuppliedValidationOutcome.Valid))
        val secondGate = assertIs<ValidateResourceClass>(driver.actions.single())
        driver.event(ResourceClassValidationCompleted(secondGate.actionId, SuppliedValidationOutcome.Valid))
        val write = assertIs<WriteStore>(driver.actions.single())
        inFlightStates += driver.state
        driver.event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
        val install = assertIs<InstallVisibility>(driver.actions.single())
        inFlightStates += driver.state

        inFlightStates.forEach { inFlight ->
            val record = inFlight.routeRecords.single()
            val cursor = requireNotNull(record.cursor)
            assertEquals(
                "installed visibility leaves only a child-discovery cursor in flight",
                assertFailsWith<IllegalArgumentException>(cursor::class.simpleName) {
                    copyState(
                        inFlight,
                        routeRecords = listOf(routeRecord(record, cursor, visibilityInstalled = true)),
                    )
                }.message,
                cursor::class.simpleName,
            )
        }

        driver.event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded))
        assertTrue(driver.record(0L).visibilityInstalled)
        assertNull(driver.record(0L).cursor)

        val parent = discoveryOccurrence()
        val discoveryDriver = CommitDriver(definitionOf(concurrency = 1, occurrences = listOf(parent)))
        discoveryDriver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val parentContent = discoveryDriver.passGates(
            0L,
            ORDINARY_CLASS_GATES.getValue(ResourceClass.MODEL_TEXTURE),
            "discovery parent",
        )
        val parentWrite = assertIs<WriteStore>(discoveryDriver.actions.single())
        discoveryDriver.event(StoreWriteCompleted(parentWrite.actionId, SuppliedCallOutcome.Success(Unit)))
        val parentInstall = assertIs<InstallVisibility>(discoveryDriver.actions.single())
        discoveryDriver.event(
            VisibilityInstallCompleted(parentInstall.actionId, SuppliedInstallOutcome.Succeeded),
        )

        val installedParent = discoveryDriver.record(0L)
        assertTrue(installedParent.visibilityInstalled)
        assertEquals(ResourceRouteStatus.RUNNING, installedParent.status)
        assertEquals(PendingChildDiscovery(0L, parentContent), installedParent.cursor)
        val readmitted = copyState(discoveryDriver.state).routeRecords.single()
        assertTrue(readmitted.visibilityInstalled)
        assertEquals(ResourceRouteStatus.RUNNING, readmitted.status)
        assertEquals(PendingChildDiscovery(0L, parentContent), readmitted.cursor)
    }

    @Test
    fun classGateCursorsMustNameTheirGateAtTheirOwnGateIndex() {
        val driver = CommitDriver(
            singleRouteDefinition(ResourceClass.MODEL_GLB, ContentProvenance.TRANSPORT_200),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val content = assertIs<PendingClassGates>(driver.record(0L).cursor).content
        val actionId = ResourceActionId(90L)
        val message = "a class gate cursor must name its class gate at its own gate index"

        listOf(
            0 to ResourceClassGate.VALIDATE_GLB_FEATURES,
            1 to ResourceClassGate.PARSE_GLB,
            2 to ResourceClassGate.VALIDATE_GLB_FEATURES,
            -1 to ResourceClassGate.PARSE_GLB,
        ).forEach { (gateIndex, gate) ->
            assertEquals(
                message,
                assertFailsWith<IllegalArgumentException>("$gateIndex/$gate") {
                    AwaitingClassGate(actionId, 0L, content, gateIndex, gate)
                }.message,
                "$gateIndex/$gate",
            )
        }

        driver.event(AdvancePendingClassGates(0L))
        val first = assertIs<ValidateResourceClass>(driver.actions.single())
        assertEquals(
            AwaitingClassGate(first.actionId, 0L, content, 0, ResourceClassGate.PARSE_GLB),
            driver.record(0L).cursor,
        )
        driver.event(ResourceClassValidationCompleted(first.actionId, SuppliedValidationOutcome.Valid))
        val second = assertIs<ValidateResourceClass>(driver.actions.single())
        assertEquals(
            AwaitingClassGate(second.actionId, 0L, content, 1, ResourceClassGate.VALIDATE_GLB_FEATURES),
            driver.record(0L).cursor,
        )
        driver.event(ResourceClassValidationCompleted(second.actionId, SuppliedValidationOutcome.Valid))
        assertIs<WriteStore>(driver.actions.single())
        assertEquals(2, driver.emitted.filterIsInstance<ValidateResourceClass>().size)
    }

    @Test
    fun aStoreWriteActionCarriesItsOwnCopyOfTheSelectedResource() {
        val driver = CommitDriver(
            singleRouteDefinition(ResourceClass.STICKER_IMAGE, ContentProvenance.TRANSPORT_200),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val content = driver.passGates(0L, listOf(ResourceClassGate.DECODE_PNG), "write copy")
        val write = assertIs<WriteStore>(driver.actions.single())

        assertEquals(content.stored, write.resource)
        assertNotSame(content.stored, write.resource)
        assertNotSame(
            requireNotNull(driver.record(0L).lookup?.selectedContent).stored,
            write.resource,
        )
    }

    @Test
    fun classGatesCannotAdvanceAfterATerminalSelection() {
        val driver = CommitDriver(
            twoRouteDefinition(
                first = ResourceClass.MODEL_GLB,
                second = ResourceClass.STICKER_IMAGE,
                concurrency = 2,
            ),
        )
        driver.driveToPendingClassGates(1L, ContentProvenance.TRANSPORT_200)
        val content = assertIs<PendingClassGates>(driver.record(1L).cursor).content
        driver.event(RouteCompleted(0L, ResourceRouteOutcome.Failure(storeReadFailure(content))))

        assertEquals(listOf(CancelRoute(1L)), driver.actions)
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(1L).status)
        assertIs<PendingClassGates>(driver.record(1L).cursor)
        val terminalState = driver.state

        assertEquals(
            "class gates cannot advance after terminal selection",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(terminalState, AdvancePendingClassGates(1L))
            }.message,
        )
        assertTrue(driver.emitted.filterIsInstance<ValidateResourceClass>().isEmpty())
    }

    @Test
    fun successRequiresEveryOrdinalRetiredAndEveryRouteResolved() {
        val driver = CommitDriver(
            singleRouteDefinition(ResourceClass.STICKER_IMAGE, ContentProvenance.RESIDENT),
        )
        driver.driveToPendingClassGates(0L, ContentProvenance.RESIDENT)
        driver.passGates(0L, listOf(ResourceClassGate.DECODE_PNG), "success guards")
        val install = assertIs<InstallVisibility>(driver.actions.single())
        val installState = driver.state
        val record = installState.routeRecords.single()

        val unretired = copyState(
            installState,
            routeRecords = installState.routeRecords + RouteRecord(
                registration = record.registration,
                joinedOccurrenceIds = emptyList(),
                ordinal = 1L,
                cursor = null,
                status = ResourceRouteStatus.RESOLVED,
            ),
            nextRouteOrdinal = 2L,
        )
        val unretiredTransition = ResourceOperationStateMachine.transition(
            unretired,
            VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded),
        )
        val unretiredState = requireNotNull(unretiredTransition.state)
        assertNull(unretiredTransition.outcome)
        assertEquals(1L, unretiredState.nextRetirementOrdinal)
        assertEquals(2L, unretiredState.nextRouteOrdinal)
        assertTrue(unretiredState.bufferedRouteOutcomes.isEmpty())
        assertTrue(unretiredState.activeRouteOrdinals.isEmpty())
        assertTrue(unretiredState.routeRecords.all { it.status == ResourceRouteStatus.RESOLVED })

        val unresolved = copyState(
            installState,
            routeRecords = installState.routeRecords + RouteRecord(
                registration = record.registration,
                joinedOccurrenceIds = emptyList(),
                ordinal = null,
                cursor = null,
                status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
            ),
        )
        val unresolvedTransition = ResourceOperationStateMachine.transition(
            unresolved,
            VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded),
        )
        val unresolvedState = requireNotNull(unresolvedTransition.state)
        assertNull(unresolvedTransition.outcome)
        assertEquals(unresolvedState.nextRouteOrdinal, unresolvedState.nextRetirementOrdinal)
        assertTrue(unresolvedState.bufferedRouteOutcomes.isEmpty())
        assertTrue(unresolvedState.activeRouteOrdinals.isEmpty())
        assertEquals(
            listOf(ResourceRouteStatus.RESOLVED, ResourceRouteStatus.BLOCKED_BY_COLLISION),
            unresolvedState.routeRecords.map(RouteRecord::status),
        )

        val settled = ResourceOperationStateMachine.transition(
            installState,
            VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded),
        )
        assertIs<ResourceOperationOutcome.Success>(settled.outcome)
    }

    @Test
    fun ownerResourceSetsKeepEveryDistinctVisibleResourceOfOneKey() {
        val driver = CommitDriver(sharedIdentityDefinition())
        driver.driveToPendingClassGates(0L, ContentProvenance.TRANSPORT_200)
        val transported = driver.passGates(0L, listOf(ResourceClassGate.DECODE_PNG), "transported")
        val write = assertIs<WriteStore>(driver.actions.single())
        driver.event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
        val transportedInstall = assertIs<InstallVisibility>(driver.actions.single())
        driver.event(VisibilityInstallCompleted(transportedInstall.actionId, SuppliedInstallOutcome.Succeeded))

        driver.driveToPendingClassGates(1L, ContentProvenance.RESIDENT)
        val resident = driver.passGates(1L, listOf(ResourceClassGate.DECODE_PNG), "resident")
        val residentInstall = assertIs<InstallVisibility>(driver.actions.single())
        driver.event(VisibilityInstallCompleted(residentInstall.actionId, SuppliedInstallOutcome.Succeeded))

        assertEquals(transported.resourceKey, resident.resourceKey)
        val success = assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        val ownerSet = success.resourceSets.single()
        assertEquals(ResourceOwnerId(FIRST_OWNER_ID), ownerSet.ownerId)
        assertEquals(
            listOf(
                VisibleResource(transported.resourceKey, transported),
                VisibleResource(resident.resourceKey, resident),
            ),
            ownerSet.resources,
        )
        assertEquals(
            listOf(ContentProvenance.TRANSPORT_200, ContentProvenance.RESIDENT),
            ownerSet.resources.map { it.content.provenance },
        )
    }

    @Test
    fun ownerResourceSetsAndSuccessRejectRepetitionAndStayShapeOnly() {
        val content = resolvedContent(registration("a", ResourceClass.STICKER_IMAGE, ResourceAccessMode.NORMAL))
        val other = resolvedContent(registration("b", ResourceClass.MODEL_GLB, ResourceAccessMode.NORMAL))
        val first = VisibleResource(content.resourceKey, content)
        val second = VisibleResource(other.resourceKey, other)
        val ownerId = ResourceOwnerId(3L)

        assertEquals(
            "an owner resource set must not repeat a visible resource",
            assertFailsWith<IllegalArgumentException> {
                OwnerResourceSet(ownerId, listOf(first, second, first))
            }.message,
        )
        val ownerSet = OwnerResourceSet(ownerId, listOf(first, second))
        assertEquals(
            "a successful outcome must carry one resource set per owner",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationOutcome.Success(listOf(ownerSet, OwnerResourceSet(ownerId, listOf(first))))
            }.message,
        )

        assertEquals("OwnerResourceSet(ownerId=$ownerId, resourceCount=2)", ownerSet.toString())
        assertEquals("Success(resourceSetCount=1)", ResourceOperationOutcome.Success(listOf(ownerSet)).toString())
        assertFalse(ownerSet.toString().contains(content.resourceKey.stableId))
        assertFalse(
            ResourceOperationOutcome.Success(listOf(ownerSet)).toString()
                .contains(other.resourceKey.stableId),
        )
    }
}

private const val FIRST_OWNER_ID: Long = 1L
private const val SAMPLE_EPOCH_MILLIS: Long = 100L

/** A real gate that is not MODEL_TEXTURE's gate at index 0, which is what `WRONG_GATE` has to mean. */
private val WRONG_GATE: ResourceClassGate = ResourceClassGate.PARSE_GLB

/**
 * Every class whose routes run no class gate at all, for either of the two structural reasons: the eight
 * the Rentile engine acquires and validates itself through RenG's firewall (RenG's driver only
 * preregisters their routes), and `BASEMAP_STYLE`, whose commit path is the style commit rather than
 * [AdvancePendingClassGates].
 */
private val UNGATED_CLASSES: Set<ResourceClass> = setOf(
    ResourceClass.BASEMAP_TILE_JSON,
    ResourceClass.BASEMAP_VECTOR_TILE,
    ResourceClass.BASEMAP_RASTER_TILE,
    ResourceClass.BASEMAP_DEM_TILE,
    ResourceClass.BASEMAP_GEO_JSON,
    ResourceClass.BASEMAP_STYLE,
    ResourceClass.BASEMAP_SPRITE_JSON,
    ResourceClass.BASEMAP_SPRITE_IMAGE,
    ResourceClass.BASEMAP_GLYPH_RANGE,
)

private val ORDINARY_CLASS_GATES: Map<ResourceClass, List<ResourceClassGate>> = mapOf(
    ResourceClass.STICKER_IMAGE to listOf(ResourceClassGate.DECODE_PNG),
    ResourceClass.MODEL_GLB to listOf(
        ResourceClassGate.PARSE_GLB,
        ResourceClassGate.VALIDATE_GLB_FEATURES,
    ),
    ResourceClass.MODEL_TEXTURE to listOf(ResourceClassGate.DECODE_PNG),
)

private fun expectedGateFailure(gate: ResourceClassGate): Pair<RenGErrorCode, PipelineStage> = when (gate) {
    ResourceClassGate.PARSE_GLB -> RenGErrorCode.RESOURCE_PARSE_FAILED to PipelineStage.RESOURCE_PARSING
    ResourceClassGate.DECODE_PNG -> RenGErrorCode.RESOURCE_DECODE_FAILED to PipelineStage.RESOURCE_DECODING
    ResourceClassGate.VALIDATE_GLB_FEATURES ->
        RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE to PipelineStage.RESOURCE_PARSING
}

private class CommitDriver(definition: ResourceOperationDefinition) {
    var state: ResourceOperationState.Running
    var actions: List<ResourceOperationAction>
    var outcome: ResourceOperationOutcome?
    val emitted: MutableList<ResourceOperationAction> = mutableListOf()

    init {
        val transition = ResourceOperationStateMachine.start(definition)
        state = requireNotNull(transition.state)
        actions = transition.actions
        outcome = transition.outcome
        emitted += transition.actions
    }

    fun beginLookup(ordinal: Long) {
        applyTransition(ResourceOperationStateMachine.beginLookup(state, ordinal))
    }

    fun event(event: ResourceOperationEvent) {
        applyTransition(ResourceOperationStateMachine.transition(state, event))
    }

    fun record(ordinal: Long): RouteRecord = state.routeRecords.single { it.ordinal == ordinal }

    fun assertNoRecoveryActions(label: String) {
        listOf("Retry", "Repair", "Remove", "Fallback").forEach { forbidden ->
            assertTrue(
                emitted.none { it::class.simpleName.orEmpty().contains(forbidden) },
                "$label/$forbidden",
            )
        }
    }

    private fun applyTransition(transition: ResourceOperationTransition) {
        state = requireNotNull(transition.state)
        actions = transition.actions
        outcome = transition.outcome
        emitted += transition.actions
    }
}

private fun CommitDriver.driveToPendingClassGates(ordinal: Long, provenance: ContentProvenance) {
    beginLookup(ordinal)
    val sample = assertIs<SampleClock>(actions.single())
    event(ClockSampled(sample.actionId, SAMPLE_EPOCH_MILLIS))
    when (provenance) {
        ContentProvenance.RESIDENT -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(
                ResidentObserved(observe.actionId, storedResource(freshUntil = SAMPLE_EPOCH_MILLIS + 1L)),
            )
        }
        ContentProvenance.STORE -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(ResidentObserved(observe.actionId, null))
            val read = assertIs<ReadStore>(actions.single())
            event(
                StoreReadCompleted(
                    read.actionId,
                    SuppliedCallOutcome.Success(storedResource(freshUntil = 1L)),
                ),
            )
        }
        ContentProvenance.TRANSPORT_200 -> {
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
            assertEquals("baseline-etag", call.request.metadata.ifNoneMatch)
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
    assertTrue(actions.isEmpty(), provenance.name)
    assertEquals(provenance, assertIs<PendingClassGates>(record(ordinal).cursor).content.provenance)
}

private fun CommitDriver.passGates(
    ordinal: Long,
    gates: List<ResourceClassGate>,
    label: String,
): ResolvedResourceContent {
    val content = assertIs<PendingClassGates>(record(ordinal).cursor, label).content
    event(AdvancePendingClassGates(ordinal))
    gates.forEach { gate ->
        val action = assertIs<ValidateResourceClass>(actions.single(), "$label/$gate")
        assertEquals(gate, action.gate, label)
        assertEquals(ordinal, action.ordinal, label)
        assertEquals(content, action.content, label)
        assertEquals(
            AwaitingClassGate(action.actionId, ordinal, content, gates.indexOf(gate), gate),
            record(ordinal).cursor,
            "$label/$gate",
        )
        event(ResourceClassValidationCompleted(action.actionId, SuppliedValidationOutcome.Valid))
    }
    return content
}

private fun CommitDriver.failGateAt(
    ordinal: Long,
    gates: List<ResourceClassGate>,
    failingIndex: Int,
    label: String,
): ResolvedResourceContent {
    val content = assertIs<PendingClassGates>(record(ordinal).cursor, label).content
    event(AdvancePendingClassGates(ordinal))
    gates.forEachIndexed { index, gate ->
        if (index > failingIndex) return content
        val action = assertIs<ValidateResourceClass>(actions.single(), "$label/$gate")
        assertEquals(gate, action.gate, label)
        val outcome = if (index == failingIndex) {
            SuppliedValidationOutcome.Failed
        } else {
            SuppliedValidationOutcome.Valid
        }
        event(ResourceClassValidationCompleted(action.actionId, outcome))
    }
    return content
}

private fun singleRouteDefinition(
    resourceClass: ResourceClass,
    provenance: ContentProvenance,
): ResourceOperationDefinition {
    val registration = registration("a", resourceClass, accessModeFor(provenance))
    return definitionOf(
        concurrency = 1,
        occurrences = listOf(occurrence(1L, FIRST_OWNER_ID, registration)),
    )
}

private fun discoveryOccurrence(): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(1L),
    ownerId = ResourceOwnerId(FIRST_OWNER_ID),
    registration = registration("a", ResourceClass.MODEL_TEXTURE, ResourceAccessMode.RELOAD),
    discoveryRequired = true,
    commitBinding = ResourceCommitBinding.Single,
)

private fun discoveredChild(): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(2L),
    ownerId = ResourceOwnerId(FIRST_OWNER_ID),
    registration = registration("b", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
    discoveryRequired = false,
    commitBinding = ResourceCommitBinding.Single,
)

private fun twoRouteDefinition(
    first: ResourceClass,
    second: ResourceClass,
    concurrency: Int,
): ResourceOperationDefinition = definitionOf(
    concurrency = concurrency,
    occurrences = listOf(
        occurrence(1L, 1L, registration("a", first, ResourceAccessMode.RELOAD)),
        occurrence(2L, 2L, registration("b", second, ResourceAccessMode.RELOAD)),
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

private fun accessModeFor(provenance: ContentProvenance): ResourceAccessMode = when (provenance) {
    ContentProvenance.RESIDENT -> ResourceAccessMode.NORMAL
    ContentProvenance.STORE -> ResourceAccessMode.CACHE_ONLY
    ContentProvenance.TRANSPORT_200 -> ResourceAccessMode.RELOAD
    ContentProvenance.TRANSPORT_304_MERGED -> ResourceAccessMode.NORMAL
}

private fun occurrence(
    id: Long,
    ownerId: Long,
    registration: ResourceRouteRegistration,
): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(id),
    ownerId = ResourceOwnerId(ownerId),
    registration = registration,
    discoveryRequired = false,
    commitBinding = ResourceCommitBinding.Single,
)

private fun registration(
    marker: String,
    resourceClass: ResourceClass,
    mode: ResourceAccessMode,
): ResourceRouteRegistration = ResourceRouteRegistration(
    route = ResourceRouteKey(
        accessMode = mode,
        locator = ResourceLocator("locator-$marker-${resourceClass.name}"),
        resourceClass = resourceClass,
        maximumResponseBytes = 4096L,
    ),
    resourceKey = ResourceKey(ResourceKind.EXTERNAL, marker.repeat(64), resourceClass),
    rawKey = RawResourceKey(marker.repeat(63) + "f", resourceClass),
    privateRentileKey = RentilePrivateKey("private-$marker-${resourceClass.name}"),
    canonicalBytes = CanonicalBytes("canonical-$marker-${resourceClass.name}".encodeToByteArray()),
)

private fun resolvedContent(registration: ResourceRouteRegistration): ResolvedResourceContent =
    ResolvedResourceContent(
        route = registration.route,
        resourceKey = registration.resourceKey,
        stored = storedResource(freshUntil = SAMPLE_EPOCH_MILLIS + 1L),
        provenance = ContentProvenance.RESIDENT,
    )

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

private fun storeReadFailure(content: ResolvedResourceContent): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.STORE_READ_FAILED,
    stage = PipelineStage.STORE_READ,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.STORE_READ,
        resourceClass = content.route.resourceClass,
        resourceKey = content.resourceKey,
    ),
)

private fun sharedIdentityDefinition(): ResourceOperationDefinition {
    val transported = registration("a", ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD)
    val resident = ResourceRouteRegistration(
        route = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = transported.route.locator,
            resourceClass = transported.route.resourceClass,
            maximumResponseBytes = transported.route.maximumResponseBytes,
        ),
        resourceKey = transported.resourceKey,
        rawKey = transported.rawKey,
        privateRentileKey = RentilePrivateKey("private-a-STICKER_IMAGE-resident"),
        canonicalBytes = transported.canonicalBytes,
    )
    return definitionOf(
        concurrency = 2,
        occurrences = listOf(
            occurrence(1L, FIRST_OWNER_ID, transported),
            occurrence(2L, FIRST_OWNER_ID, resident),
        ),
    )
}

private fun routeRecord(
    record: RouteRecord,
    cursor: ResourceRouteCursor,
    storeWriteAcknowledged: Boolean = record.storeWriteAcknowledged,
    visibilityInstalled: Boolean = record.visibilityInstalled,
): RouteRecord = RouteRecord(
    registration = record.registration,
    joinedOccurrenceIds = record.joinedOccurrenceIds,
    ordinal = record.ordinal,
    cursor = cursor,
    status = record.status,
    lookup = record.lookup,
    storeWriteAcknowledged = storeWriteAcknowledged,
    visibilityInstalled = visibilityInstalled,
)

private fun copyState(
    state: ResourceOperationState.Running,
    routeRecords: List<RouteRecord> = state.routeRecords,
    nextRouteOrdinal: Long = state.nextRouteOrdinal,
): ResourceOperationState.Running = ResourceOperationState.Running(
    definition = state.definition,
    occurrences = state.occurrences,
    routeRecords = routeRecords,
    privateRentileKeyClaims = state.privateRentileKeyClaims,
    identityRecords = state.identityRecords,
    transportLatches = state.transportLatches,
    nextActionId = state.nextActionId,
    traversal = state.traversal,
    nextRouteOrdinal = nextRouteOrdinal,
    activeRouteOrdinals = state.activeRouteOrdinals,
    nextRetirementOrdinal = state.nextRetirementOrdinal,
    bufferedRouteOutcomes = state.bufferedRouteOutcomes,
    startCeilingOrdinal = state.startCeilingOrdinal,
    terminalSelection = state.terminalSelection,
)

private fun assertResourceFailure(
    outcome: ResourceOperationOutcome?,
    code: RenGErrorCode,
    stage: PipelineStage,
    expectedField: String?,
    resourceClass: ResourceClass,
    resourceKey: ResourceKey,
    label: String,
) {
    val failure = assertIs<ResourceOperationOutcome.Failure>(outcome, label).failure
    assertEquals(code, failure.code, label)
    assertEquals(stage, failure.stage, label)
    val diagnostic = requireNotNull(failure.diagnostic) { label }
    assertEquals(expectedField, diagnostic.fieldName, label)
    assertEquals(resourceClass, diagnostic.resourceClass, label)
    assertEquals(resourceKey, diagnostic.resourceKey, label)
    assertNull(diagnostic.statusCode, label)
    assertNull(diagnostic.limit, label)
    assertNull(diagnostic.actual, label)
}

private fun <T> assertFreshCopy(first: List<T>, second: List<T>, expected: List<T>, label: String) {
    assertEquals(expected, first, label)
    assertEquals(expected, second, label)
    assertNotSame(first, second, label)
    @Suppress("UNCHECKED_CAST")
    (first as MutableList<T>).clear()
    assertEquals(expected, second, label)
}

private val CONTENT_BYTES: ByteArray = "abc".encodeToByteArray()
private const val CONTENT_DIGEST: String =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
