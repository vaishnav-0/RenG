package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportRequestMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.acceptValue
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.AcceleratedSha256

internal object ResourceOperationStateMachine {
    internal fun preRegister(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition {
        val occurrences = definition.staticOccurrences
        val occurrenceIds = mutableSetOf<ResourceOccurrenceId>()
        require(occurrences.all { occurrenceIds.add(it.id) }) {
            "static resource occurrence IDs must be unique"
        }

        val identityRecords = mutableListOf<CanonicalIdentityRecord>()
        val identityByStableId = mutableMapOf<String, CanonicalIdentityRecord>()
        val routeRecords = mutableListOf<RouteRecord>()
        val joinedOccurrenceIdsByRoute = mutableListOf<MutableList<ResourceOccurrenceId>>()
        val routeRecordIndexByRoute = mutableMapOf<ResourceRouteKey, Int>()
        val privateRentileKeyClaims = mutableListOf<PrivateRentileKeyClaim>()
        val claimIndexByPrivateKey = mutableMapOf<RentilePrivateKey, Int>()

        definition.resourceIdentities.forEach { attempted ->
            val established = identityByStableId[attempted.resourceKey.stableId]
            when {
                established == null -> {
                    identityRecords += attempted
                    identityByStableId[attempted.resourceKey.stableId] = attempted
                }
                established.canonicalBytes != attempted.canonicalBytes -> {
                    return failed(
                        definition = definition,
                        occurrences = occurrences,
                        routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRoute),
                        privateRentileKeyClaims = privateRentileKeyClaims,
                        identityRecords = identityRecords,
                        failure = identityCollisionFailure(established),
                    )
                }
            }
        }

        occurrences.forEach { occurrence ->
            val registration = occurrence.registration
            val joinedRouteIndex = routeRecordIndexByRoute[registration.route] ?: -1
            if (joinedRouteIndex >= 0) {
                joinedOccurrenceIdsByRoute[joinedRouteIndex] += occurrence.id
                return@forEach
            }

            val claimIndex = claimIndexByPrivateKey[registration.privateRentileKey] ?: -1
            if (claimIndex < 0) {
                claimIndexByPrivateKey[registration.privateRentileKey] = privateRentileKeyClaims.size
                privateRentileKeyClaims += PrivateRentileKeyClaim(
                    privateKey = registration.privateRentileKey,
                    firstRoute = registration.route,
                    usable = true,
                )
                routeRecordIndexByRoute[registration.route] = routeRecords.size
                routeRecords += preregisteredRouteRecord(occurrence)
                joinedOccurrenceIdsByRoute += mutableListOf(occurrence.id)
                return@forEach
            }

            val establishedClaim = privateRentileKeyClaims[claimIndex]
            privateRentileKeyClaims[claimIndex] = establishedClaim.copy(usable = false)
            routeRecords += RouteRecord(
                registration = registration,
                joinedOccurrenceIds = listOf(occurrence.id),
                ordinal = null,
                cursor = null,
                status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
            )
            joinedOccurrenceIdsByRoute += mutableListOf(occurrence.id)
            return failed(
                definition = definition,
                occurrences = occurrences,
                routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRoute),
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
                failure = ambiguousRouteFailure(),
            )
        }

        return ResourceOperationTransition(
            state = runningState(
                definition = definition,
                occurrences = occurrences,
                routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRoute),
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
            ),
            actions = emptyList(),
            outcome = null,
        )
    }

    internal fun start(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition {
        val preRegistered = preRegister(definition)
        if (preRegistered.outcome != null) return preRegistered

        val work = SchedulerWork(requireNotNull(preRegistered.state))
        work.setStaticContinuation(definition.staticOccurrences.map(ResourceOccurrence::id))
        work.releaseKnownTraversal()
        work.scheduleEligibleOccurrences()
        return work.toTransition()
    }

    internal fun beginLookup(
        state: ResourceOperationState.Running,
        ordinal: Long,
    ): ResourceOperationTransition {
        val work = SchedulerWork(state)
        work.beginLookup(ordinal)
        return work.toTransition()
    }

    internal fun transition(
        state: ResourceOperationState.Running,
        event: ResourceOperationEvent,
    ): ResourceOperationTransition {
        val work = SchedulerWork(state)
        when (event) {
            is ClockSampled -> work.clockSampled(event)
            is ResidentObserved -> work.residentObserved(event)
            is StoreReadCompleted -> work.storeReadCompleted(event)
            is TransportCompleted -> work.transportCompleted(event)
            is LatchedTransportReplayCompleted -> work.latchedTransportReplayCompleted(event)
            is AdvancePendingClassGates -> work.advancePendingClassGates(event)
            is ResourceClassValidationCompleted -> work.classValidationCompleted(event)
            is StoreWriteCompleted -> work.storeWriteCompleted(event)
            is VisibilityInstallCompleted -> work.visibilityInstallCompleted(event)
            is AdvancePendingSpriteCommit -> work.advancePendingSpriteCommit(event)
            is AdvancePendingStyleCommit -> work.advancePendingStyleCommit(event)
            is BasemapStyleValidationCompleted -> work.basemapStyleValidationCompleted(event)
            is BasemapStyleCompilationCompleted -> work.basemapStyleCompilationCompleted(event)
            is BasemapStyleWriteCompleted -> work.basemapStyleWriteCompleted(event)
            is BasemapStyleVisibilityInstallCompleted -> work.basemapStyleVisibilityInstallCompleted(event)
            is SpritePairValidationCompleted -> work.spritePairValidationCompleted(event)
            is SpriteMemberWriteCompleted -> work.spriteMemberWriteCompleted(event)
            is SpriteVisibilityInstallCompleted -> work.spriteVisibilityInstallCompleted(event)
            is RouteReadyForDiscovery -> work.routeReadyForDiscovery(event)
            is ChildrenDiscovered -> work.childrenDiscovered(event)
            is RouteCompleted -> work.routeCompleted(event)
            is ExternalCancellationRequested -> work.externalCancellationRequested(event)
            is CleanupCancellationObserved -> work.cleanupCancellationObserved(event)
        }
        return work.toTransition()
    }

    private class SchedulerWork(
        private val initial: ResourceOperationState.Running,
    ) {
        private val occurrences: MutableList<ResourceOccurrence> = initial.occurrences.toMutableList()
        private val occurrenceById: MutableMap<ResourceOccurrenceId, ResourceOccurrence> = mutableMapOf()
        private val routeRecords: MutableList<RouteRecord> = initial.routeRecords.toMutableList()
        private val joinedOccurrenceIdsByRouteIndex: MutableList<MutableList<ResourceOccurrenceId>> =
            mutableListOf()
        private val joinedOccurrenceIdSetsByRouteIndex: MutableList<MutableSet<ResourceOccurrenceId>> =
            mutableListOf()
        private val routeIndexByRoute: MutableMap<ResourceRouteKey, Int> = mutableMapOf()
        private val routeIndexByOrdinal: MutableMap<Long, Int> = mutableMapOf()
        private val privateRentileKeyClaims: MutableList<PrivateRentileKeyClaim> =
            initial.privateRentileKeyClaims.toMutableList()
        private val claimIndexByPrivateKey: MutableMap<RentilePrivateKey, Int> = mutableMapOf()
        private val identityRecords: MutableList<CanonicalIdentityRecord> = initial.identityRecords.toMutableList()
        private val identityByStableId: MutableMap<String, CanonicalIdentityRecord> = mutableMapOf()
        private val transportLatches: MutableList<TransportLatchRecord> = initial.transportLatches.toMutableList()
        private val transportLatchIndexByKey: MutableMap<TransportLatchKey, Int> = mutableMapOf()
        private val routeIndexByActionId: MutableMap<ResourceActionId, Int> = mutableMapOf()
        private val eligibleFifo: ArrayDeque<ResourceOccurrenceId> = ArrayDeque()
        private var staticContinuation: ResourceOccurrenceIdSlice = initial.traversal.staticSlice()
        private val frontierStack: MutableList<DiscoveryFrontier> =
            initial.traversal.frontierStack.toMutableList()
        private val activeRouteOrdinals: MutableList<Long> = initial.activeRouteOrdinals.toMutableList()
        private val bufferedRouteOutcomes: MutableList<BufferedRouteOutcome> =
            initial.bufferedRouteOutcomes.toMutableList()
        private val spriteCommitStates: MutableList<SpriteCommitState> =
            initial.spriteCommitStates.toMutableList()
        private val spriteStateIndexByGroup: MutableMap<SpriteGroupId, Int> = mutableMapOf()
        private val parkedRoutes: MutableList<ParkedRoute> = initial.parkedRoutes.toMutableList()
        private val styleCommitStates: MutableList<StyleCommitState> =
            initial.styleCommitStates.toMutableList()
        private val styleStateIndexByGroup: MutableMap<StyleGroupId, Int> = mutableMapOf()
        private val staticOccurrenceIds: Set<ResourceOccurrenceId> =
            initial.definition.staticOccurrences.mapTo(mutableSetOf(), ResourceOccurrence::id)
        private val actions: MutableList<ResourceOperationAction> = mutableListOf()
        private var nextActionId: Long = initial.nextActionId
        private var nextRouteOrdinal: Long = initial.nextRouteOrdinal
        private var nextRetirementOrdinal: Long = initial.nextRetirementOrdinal
        private var startCeilingOrdinal: Long? = initial.startCeilingOrdinal
        private var terminalSelection: ResourceTerminalSelection? = initial.terminalSelection

        init {
            initial.traversal.eligibleFifo.forEach(eligibleFifo::addLast)
            occurrences.forEach { occurrence ->
                require(occurrenceById.put(occurrence.id, occurrence) == null) {
                    "resource occurrence IDs must be unique"
                }
            }
            identityRecords.forEach { identity ->
                require(identityByStableId.put(identity.resourceKey.stableId, identity) == null) {
                    "canonical identity stable IDs must be unique"
                }
            }
            transportLatches.forEachIndexed { index, latch ->
                require(transportLatchIndexByKey.put(latch.key, index) == null) {
                    "Transport latch keys must be unique"
                }
            }
            privateRentileKeyClaims.forEachIndexed { index, claim ->
                require(claimIndexByPrivateKey.put(claim.privateKey, index) == null) {
                    "private Rentile key claims must be unique"
                }
            }
            routeRecords.forEachIndexed { index, record ->
                if (record.registration.route !in routeIndexByRoute) {
                    routeIndexByRoute[record.registration.route] = index
                }
                val joinedIds = record.joinedOccurrenceIds.toMutableList()
                val joinedIdSet = joinedIds.toMutableSet()
                require(joinedIds.size == joinedIdSet.size) {
                    "joined resource occurrence IDs must be unique"
                }
                require(joinedIds.all(occurrenceById::containsKey)) {
                    "joined resource occurrences must be registered"
                }
                joinedOccurrenceIdsByRouteIndex += joinedIds
                joinedOccurrenceIdSetsByRouteIndex += joinedIdSet
                record.ordinal?.let { ordinal ->
                    require(ordinal >= 0L && ordinal < nextRouteOrdinal) {
                        "route ordinals must have been assigned"
                    }
                    require(routeIndexByOrdinal.put(ordinal, index) == null) {
                        "route ordinals must be unique"
                    }
                }
                cursorActionId(record.cursor)?.let { actionId ->
                    require(routeIndexByActionId.put(actionId, index) == null) {
                        "route cursor action IDs must be unique"
                    }
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
            }

            require(activeRouteOrdinals.size <= initial.definition.maximumConcurrentRoutes) {
                "active routes must not exceed configured concurrency"
            }
            require(activeRouteOrdinals.toSet().size == activeRouteOrdinals.size) {
                "active route ordinals must be distinct"
            }
            val runningOrdinals = routeRecords.mapNotNull { record ->
                record.ordinal?.takeIf { record.status == ResourceRouteStatus.RUNNING }
            }.toSet()
            val parkedOrdinals = parkedRoutes.map(ParkedRoute::ordinal).toSet()
            require(parkedOrdinals.size == parkedRoutes.size) {
                "parked route ordinals must be distinct"
            }
            require(parkedOrdinals.none(activeRouteOrdinals::contains)) {
                "a parked route must not occupy active capacity"
            }
            require(activeRouteOrdinals.toSet() + parkedOrdinals == runningOrdinals) {
                "running routes must be exactly the active and parked route ordinals"
            }
            spriteCommitStates.forEachIndexed { index, group ->
                require(spriteStateIndexByGroup.put(group.groupId, index) == null) {
                    "sprite commit group IDs must be unique"
                }
            }
            styleCommitStates.forEachIndexed { index, group ->
                require(styleStateIndexByGroup.put(group.groupId, index) == null) {
                    "style commit group IDs must be unique"
                }
            }
        }

        fun setStaticContinuation(ids: List<ResourceOccurrenceId>) {
            require(staticContinuation.isEmpty) { "static traversal continuation must be initialized once" }
            staticContinuation = ResourceOccurrenceIdSlice.snapshot(ids)
        }

        fun beginLookup(ordinal: Long) {
            require(terminalSelection == null) { "lookup cannot begin after terminal selection" }
            val routeIndex = requireNotNull(routeIndexByOrdinal[ordinal]) {
                "lookup must name an assigned route"
            }
            val record = routeRecords[routeIndex]
            require(record.status == ResourceRouteStatus.RUNNING && ordinal in activeRouteOrdinals) {
                "lookup requires an active running route"
            }

            val cursor = record.cursor
            if (cursor is PendingClassGates) {
                val lookup = requireNotNull(record.lookup)
                val latchKey = requireNotNull(lookup.transportLatch) {
                    "only Transport-selected content has a latch to replay"
                }
                val latch = transportLatch(latchKey)
                val actionId = takeNextActionId()
                updateRouteRecord(
                    routeIndex,
                    cursor = AwaitingLatchedTransportReplay(actionId, ordinal, latchKey),
                )
                actions += ReplayLatchedTransport(actionId, ordinal, copyLatch(latch))
                return
            }

            require(cursor == null && record.lookup == null) {
                "lookup may begin only once before a closed latch replay"
            }
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingClockSample(actionId, ordinal),
                lookup = LookupProgress(
                    sampleEpochMillis = null,
                    resident = null,
                    staleBaseline = null,
                    storeReadStarted = false,
                    transportLatch = null,
                    selectedContent = null,
                ),
            )
            actions += SampleClock(actionId, ordinal)
        }

        fun clockSampled(event: ClockSampled) {
            val routeIndex = routeIndexForAction<AwaitingClockSample>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingClockSample
            val progress = requireNotNull(record.lookup)
            require(progress.sampleEpochMillis == null) { "route freshness may be sampled exactly once" }
            val sampled = progress.copy(sampleEpochMillis = event.sampleEpochMillis)
            if (record.registration.route.accessMode == ResourceAccessMode.RELOAD) {
                requestTransport(routeIndex, cursor.ordinal, sampled)
                return
            }

            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingResident(actionId, cursor.ordinal),
                lookup = sampled,
            )
            actions += ObserveResident(
                actionId = actionId,
                ordinal = cursor.ordinal,
                resourceKey = record.registration.resourceKey,
            )
        }

        fun residentObserved(event: ResidentObserved) {
            val routeIndex = routeIndexForAction<AwaitingResident>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingResident
            val progress = requireNotNull(record.lookup)
            val sample = requireNotNull(progress.sampleEpochMillis)
            val resident = event.resource?.let(::copyStored)

            when (record.registration.route.accessMode) {
                ResourceAccessMode.RELOAD -> error("reload must not observe resident content")
                ResourceAccessMode.CACHE_ONLY -> {
                    if (resident != null) {
                        selectContent(routeIndex, cursor.ordinal, progress.copy(resident = resident), resident, ContentProvenance.RESIDENT)
                    } else {
                        startStoreRead(routeIndex, cursor.ordinal, progress.copy(resident = null))
                    }
                }
                ResourceAccessMode.NORMAL -> {
                    if (resident != null && isFresh(resident, sample)) {
                        selectContent(routeIndex, cursor.ordinal, progress.copy(resident = resident), resident, ContentProvenance.RESIDENT)
                    } else {
                        startStoreRead(
                            routeIndex,
                            cursor.ordinal,
                            progress.copy(resident = resident),
                            staleBaseline = resident,
                        )
                    }
                }
            }
        }

        fun storeReadCompleted(event: StoreReadCompleted) {
            val routeIndex = routeIndexForAction<AwaitingStoreRead>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingStoreRead
            val progress = requireNotNull(record.lookup)
            val sample = requireNotNull(progress.sampleEpochMillis)

            when (val outcome = event.outcome) {
                SuppliedCallOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(storeReadFailure(record)),
                )
                is SuppliedCallOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
                is SuppliedCallOutcome.Success -> {
                    val supplied = outcome.value
                    if (supplied == null) {
                        if (record.registration.route.accessMode == ResourceAccessMode.CACHE_ONLY) {
                            completeLookupRoute(
                                routeIndex,
                                ResourceRouteOutcome.Failure(resourceUnavailableFailure(record)),
                            )
                        } else {
                            requestTransport(routeIndex, cursor.ordinal, progress)
                        }
                        return
                    }

                    val validated = copyValidStoredResource(
                        supplied,
                        record.registration.route.maximumResponseBytes,
                        AcceleratedSha256,
                    )
                    if (validated == null) {
                        completeLookupRoute(
                            routeIndex,
                            ResourceRouteOutcome.Failure(storeIntegrityFailure(record)),
                        )
                        return
                    }

                    when (record.registration.route.accessMode) {
                        ResourceAccessMode.RELOAD -> error("reload must not read Store content")
                        ResourceAccessMode.CACHE_ONLY -> selectContent(
                            routeIndex,
                            cursor.ordinal,
                            progress,
                            validated,
                            ContentProvenance.STORE,
                        )
                        ResourceAccessMode.NORMAL -> {
                            if (isFresh(validated, sample)) {
                                selectContent(
                                    routeIndex,
                                    cursor.ordinal,
                                    progress,
                                    validated,
                                    ContentProvenance.STORE,
                                )
                            } else {
                                requestTransport(
                                    routeIndex,
                                    cursor.ordinal,
                                    progress.copy(staleBaseline = validated),
                                )
                            }
                        }
                    }
                }
            }
        }

        fun transportCompleted(event: TransportCompleted) {
            val routeIndex = routeIndexForAction<AwaitingTransport>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingTransport
            require(cursor.latchKey !in transportLatchIndexByKey) {
                "consumer Transport may close a latch only once"
            }

            when (val outcome = event.outcome) {
                SuppliedCallOutcome.Failed -> {
                    addTransportLatch(TransportLatchRecord(cursor.latchKey, LatchedTransportOutcome.Failed))
                    completeLookupRoute(
                        routeIndex,
                        ResourceRouteOutcome.Failure(transportFailure(record)),
                    )
                }
                is SuppliedCallOutcome.Cancelled -> {
                    addTransportLatch(
                        TransportLatchRecord(
                            cursor.latchKey,
                            LatchedTransportOutcome.Cancelled(outcome.cancellation),
                        ),
                    )
                    completeLookupRoute(
                        routeIndex,
                        ResourceRouteOutcome.Cancelled(outcome.cancellation),
                    )
                }
                is SuppliedCallOutcome.Success -> {
                    val response = copyResponse(outcome.value)
                    addTransportLatch(
                        TransportLatchRecord(cursor.latchKey, LatchedTransportOutcome.Response(response)),
                    )
                    resolveLatchedResponse(routeIndex, cursor.ordinal, cursor.latchKey, response)
                }
            }
        }

        fun latchedTransportReplayCompleted(event: LatchedTransportReplayCompleted) {
            val routeIndex = routeIndexForAction<AwaitingLatchedTransportReplay>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingLatchedTransportReplay
            val latch = transportLatch(cursor.latchKey)
            when (val outcome = latch.outcome) {
                LatchedTransportOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(transportFailure(record)),
                )
                is LatchedTransportOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
                is LatchedTransportOutcome.Response -> resolveLatchedResponse(
                    routeIndex,
                    cursor.ordinal,
                    cursor.latchKey,
                    outcome.response,
                )
            }
        }

        fun advancePendingClassGates(event: AdvancePendingClassGates) {
            require(terminalSelection == null) { "class gates cannot advance after terminal selection" }
            val routeIndex = requireNotNull(routeIndexByOrdinal[event.ordinal]) {
                "class gate advancement must name an assigned route"
            }
            val record = routeRecords[routeIndex]
            require(record.status == ResourceRouteStatus.RUNNING && event.ordinal in activeRouteOrdinals) {
                "class gate advancement requires an active running route"
            }
            val cursor = record.cursor
            require(cursor is PendingClassGates && cursor.ordinal == event.ordinal) {
                "class gate advancement requires pending selected content at the same ordinal"
            }
            requestClassGate(routeIndex, event.ordinal, cursor.content, gateIndex = 0)
        }

        fun classValidationCompleted(event: ResourceClassValidationCompleted) {
            val routeIndex = routeIndexForAction<AwaitingClassGate>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val cursor = routeRecords[routeIndex].cursor as AwaitingClassGate

            when (val outcome = event.outcome) {
                SuppliedValidationOutcome.Valid -> {
                    val gates = ordinaryClassGates(cursor.content)
                    val nextGateIndex = cursor.gateIndex + 1
                    if (nextGateIndex < gates.size) {
                        requestClassGate(routeIndex, cursor.ordinal, cursor.content, nextGateIndex)
                    } else {
                        requestWriteOrVisibility(routeIndex, cursor.ordinal, cursor.content)
                    }
                }
                SuppliedValidationOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(classGateFailure(cursor.content, cursor.gate)),
                )
                is SuppliedValidationOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        fun storeWriteCompleted(event: StoreWriteCompleted) {
            val routeIndex = routeIndexForAction<AwaitingStoreWrite>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val cursor = routeRecords[routeIndex].cursor as AwaitingStoreWrite

            when (val outcome = event.outcome) {
                is SuppliedCallOutcome.Success -> {
                    updateRouteRecord(routeIndex, storeWriteAcknowledged = true)
                    requestVisibilityInstall(routeIndex, cursor.ordinal, cursor.content)
                }
                SuppliedCallOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(storeWriteFailure(cursor.content)),
                )
                is SuppliedCallOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        fun visibilityInstallCompleted(event: VisibilityInstallCompleted) {
            val routeIndex = routeIndexForAction<AwaitingVisibilityInstall>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val cursor = routeRecords[routeIndex].cursor as AwaitingVisibilityInstall

            when (val outcome = event.outcome) {
                SuppliedInstallOutcome.Succeeded -> {
                    if (ownsActiveDiscoveryFrontier(routeIndex)) {
                        // A discovery parent commits its own bytes first and only then announces its
                        // children: it stays running and active, holding its slot until
                        // RouteReadyForDiscovery retires it and emits DiscoverChildren.
                        updateRouteRecord(
                            routeIndex,
                            cursor = PendingChildDiscovery(cursor.ordinal, cursor.content),
                            visibilityInstalled = true,
                        )
                        return
                    }
                    updateRouteRecord(routeIndex, cursor = null, visibilityInstalled = true)
                    completeLookupRoute(routeIndex, ResourceRouteOutcome.Success)
                }
                is SuppliedInstallOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(outcome.failure),
                )
                is SuppliedInstallOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        fun advancePendingSpriteCommit(event: AdvancePendingSpriteCommit) {
            require(terminalSelection == null) { "sprite commit cannot advance after terminal selection" }
            val routeIndex = requireNotNull(routeIndexByOrdinal[event.ordinal]) {
                "sprite commit advancement must name an assigned route"
            }
            val record = routeRecords[routeIndex]
            require(record.status == ResourceRouteStatus.RUNNING && event.ordinal in activeRouteOrdinals) {
                "sprite commit advancement requires an active running route"
            }
            val cursor = record.cursor
            require(cursor is PendingClassGates && cursor.ordinal == event.ordinal) {
                "sprite commit advancement requires pending selected content at the same ordinal"
            }
            val ceiling = startCeilingOrdinal
            if (ceiling != null && event.ordinal < ceiling) {
                // A ceiling-prohibited member has no remaining commit work, so it stages no candidate:
                // its group must not host a validated candidate for a route that closes here.
                closeRouteWithoutRemainingWork(routeIndex, event.ordinal)
                return
            }

            val binding = spriteCommitBinding(routeIndex)
            val group = spriteCommitStateFor(binding.groupId, binding.member, event.ordinal)
            require(group.candidate(binding.member) == null) {
                "each sprite member supplies exactly one validated candidate"
            }
            require(group.jointValidationStatus == SpriteJointValidationStatus.WAITING) {
                "joint sprite validation may be requested only once"
            }
            val staged = group.withCandidate(binding.member, cursor.content)
            putSpriteCommitState(staged)

            if (
                staged.jsonCandidate != null &&
                staged.imageCandidate != null &&
                event.ordinal == staged.jsonOrdinal
            ) {
                requestSpritePairValidation(routeIndex, staged)
                return
            }
            parkRoute(routeIndex, event.ordinal, ParkedRouteBarrier.SpritePair(staged.groupId))
            startEligibleRoutes()
        }

        fun spritePairValidationCompleted(event: SpritePairValidationCompleted) {
            val routeIndex = routeIndexForAction<AwaitingSpritePairValidation>(event.actionId)
            val cursor = routeRecords[routeIndex].cursor as AwaitingSpritePairValidation
            if (finishActionAfterTerminal(routeIndex)) return
            val group = spriteCommitState(cursor.groupId)

            when (val outcome = event.outcome) {
                SpritePairValidationOutcome.Valid -> {
                    val validated = group.withJointValidationStatus(SpriteJointValidationStatus.VALID)
                    putSpriteCommitState(validated)
                    advanceSpriteWritesOrInstall(routeIndex, validated)
                }
                is SpritePairValidationOutcome.Failed -> {
                    putSpriteCommitState(
                        group.withJointValidationStatus(SpriteJointValidationStatus.FAILED),
                    )
                    val reported = requireNotNull(group.candidate(outcome.member)) {
                        "a reported sprite member requires its validated candidate"
                    }
                    closeSpriteMember(
                        routeIndex,
                        group,
                        outcome.member,
                        ResourceRouteOutcome.Failure(spritePairFailure(reported, outcome.kind)),
                    )
                }
                is SpritePairValidationOutcome.Cancelled -> {
                    putSpriteCommitState(
                        group.withJointValidationStatus(SpriteJointValidationStatus.FAILED),
                    )
                    closeSpriteOwner(
                        routeIndex,
                        group,
                        ResourceRouteOutcome.Cancelled(outcome.cancellation),
                    )
                }
            }
        }

        fun spriteMemberWriteCompleted(event: SpriteMemberWriteCompleted) {
            val routeIndex = routeIndexForAction<AwaitingSpriteMemberWrite>(event.actionId)
            val cursor = routeRecords[routeIndex].cursor as AwaitingSpriteMemberWrite
            require(cursor.groupId == event.groupId && cursor.member == event.member) {
                "sprite write acknowledgement must match its complete binding cursor"
            }
            if (finishActionAfterTerminal(routeIndex)) return
            val group = spriteCommitState(cursor.groupId)

            when (val outcome = event.outcome) {
                is SuppliedCallOutcome.Success -> {
                    val acknowledged = group.withAcknowledgedWrite(event.member)
                    putSpriteCommitState(acknowledged)
                    advanceSpriteWritesOrInstall(routeIndex, acknowledged)
                }
                SuppliedCallOutcome.Failed -> closeSpriteMember(
                    routeIndex,
                    group,
                    event.member,
                    ResourceRouteOutcome.Failure(storeWriteFailure(cursor.content)),
                )
                is SuppliedCallOutcome.Cancelled -> closeSpriteMember(
                    routeIndex,
                    group,
                    event.member,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        fun spriteVisibilityInstallCompleted(event: SpriteVisibilityInstallCompleted) {
            val routeIndex = routeIndexForAction<AwaitingSpriteVisibilityInstall>(event.actionId)
            val cursor = routeRecords[routeIndex].cursor as AwaitingSpriteVisibilityInstall
            require(cursor.groupId == event.groupId) {
                "sprite visibility acknowledgement must match its complete binding cursor"
            }
            if (finishActionAfterTerminal(routeIndex)) return
            val group = spriteCommitState(cursor.groupId)

            when (val outcome = event.outcome) {
                SuppliedInstallOutcome.Succeeded -> installSpriteVisibility(routeIndex, group)
                is SuppliedInstallOutcome.Failed -> closeSpriteOwner(
                    routeIndex,
                    group,
                    ResourceRouteOutcome.Failure(outcome.failure),
                )
                is SuppliedInstallOutcome.Cancelled -> closeSpriteOwner(
                    routeIndex,
                    group,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        fun advancePendingStyleCommit(event: AdvancePendingStyleCommit) {
            require(terminalSelection == null) { "style commit cannot advance after terminal selection" }
            val routeIndex = requireNotNull(routeIndexByOrdinal[event.ordinal]) {
                "style commit advancement must name an assigned route"
            }
            val record = routeRecords[routeIndex]
            require(record.status == ResourceRouteStatus.RUNNING && event.ordinal in activeRouteOrdinals) {
                "style commit advancement requires an active running route"
            }
            val cursor = record.cursor
            require(cursor is PendingClassGates && cursor.ordinal == event.ordinal) {
                "style commit advancement requires pending selected content at the same ordinal"
            }
            val binding = styleCommitBinding(routeIndex)
            require(styleStateIndexByGroup[binding.groupId] == null) {
                "basemap style validation may be requested only once"
            }

            val ceiling = startCeilingOrdinal
            if (ceiling != null && event.ordinal < ceiling) {
                closeRouteWithoutRemainingWork(routeIndex, event.ordinal)
                return
            }

            val referencingOwnerIds = styleReferencingOwnerIds(routeIndex)
            val staged = StyleCommitState(
                groupId = binding.groupId,
                ordinal = event.ordinal,
                stagedContent = cursor.content,
                compilationStatus = if (requiresStyleCompilation(cursor.content.provenance)) {
                    StyleCompilationStatus.WAITING
                } else {
                    StyleCompilationStatus.NOT_REQUIRED
                },
                referencingOwnerIds = referencingOwnerIds,
                ownersWithCompletedNonStyleWork = referencingOwnerIds.filter(::ownerNonStyleWorkComplete),
                writeAcknowledged = false,
                visible = false,
            )
            addStyleCommitState(staged)
            requestStyleValidation(routeIndex, staged)
        }

        fun basemapStyleValidationCompleted(event: BasemapStyleValidationCompleted) {
            val routeIndex = routeIndexForAction<AwaitingStyleValidation>(event.actionId)
            val cursor = routeRecords[routeIndex].cursor as AwaitingStyleValidation
            if (finishActionAfterTerminal(routeIndex)) return
            refreshStyleCommitStates()
            val style = styleCommitState(cursor.groupId)

            when (val outcome = event.outcome) {
                is BasemapStyleValidationOutcome.Valid -> {
                    if (closeStyleBelowStartCeiling(routeIndex, style)) return
                    // The manifest is stored here and handed straight back out on CompileBasemapStyle:
                    // it is the one thing carried from "what does this document say?" to "what may the
                    // engine ask for while compiling it?", and nothing else ever recomputes it.
                    putStyleCommitState(style.withStyleTimeRoutes(outcome.routes))
                    updateRouteRecord(
                        routeIndex,
                        cursor = PendingClassGates(cursor.ordinal, cursor.content),
                    )
                    parkRoute(routeIndex, cursor.ordinal, ParkedRouteBarrier.StyleChildren(style.groupId))
                    releaseStyleTraversalFrontier(routeIndex)
                }
                is BasemapStyleValidationOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(styleFailure(style.stagedContent, outcome.kind)),
                )
                is BasemapStyleValidationOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        fun basemapStyleCompilationCompleted(event: BasemapStyleCompilationCompleted) {
            val routeIndex = routeIndexForAction<AwaitingStyleCompilation>(event.actionId)
            val cursor = routeRecords[routeIndex].cursor as AwaitingStyleCompilation
            if (finishActionAfterTerminal(routeIndex)) return
            refreshStyleCommitStates()
            val style = styleCommitState(cursor.groupId)

            when (val outcome = event.outcome) {
                BasemapStyleCompilationOutcome.Succeeded -> {
                    val compiled = style.withCompilationStatus(StyleCompilationStatus.SUCCEEDED)
                    putStyleCommitState(compiled)
                    advanceStyleAfterCompilation(routeIndex, compiled, reschedule = true)
                }
                is BasemapStyleCompilationOutcome.Failed -> {
                    putStyleCommitState(style.withCompilationStatus(StyleCompilationStatus.FAILED))
                    completeLookupRoute(
                        routeIndex,
                        ResourceRouteOutcome.Failure(styleFailure(style.stagedContent, outcome.kind)),
                    )
                }
                // Forwarded verbatim rather than reclassified: the firewall already sanitized it and
                // already translated its identity out of Rentile's digest namespace, and no
                // StyleFailureKind describes "the engine asked for a url this invocation never routed".
                is BasemapStyleCompilationOutcome.EngineFailed -> {
                    putStyleCommitState(style.withCompilationStatus(StyleCompilationStatus.FAILED))
                    completeLookupRoute(routeIndex, ResourceRouteOutcome.Failure(outcome.failure))
                }
                is BasemapStyleCompilationOutcome.Cancelled -> {
                    putStyleCommitState(style.withCompilationStatus(StyleCompilationStatus.FAILED))
                    completeLookupRoute(
                        routeIndex,
                        ResourceRouteOutcome.Cancelled(outcome.cancellation),
                    )
                }
            }
        }

        fun basemapStyleWriteCompleted(event: BasemapStyleWriteCompleted) {
            val routeIndex = routeIndexForAction<AwaitingStyleWrite>(event.actionId)
            val cursor = routeRecords[routeIndex].cursor as AwaitingStyleWrite
            require(cursor.groupId == event.groupId) {
                "style write acknowledgement must match its complete binding cursor"
            }
            if (finishActionAfterTerminal(routeIndex)) return
            refreshStyleCommitStates()
            val style = styleCommitState(cursor.groupId)

            when (val outcome = event.outcome) {
                is SuppliedCallOutcome.Success -> {
                    val acknowledged = style.withWriteAcknowledged()
                    putStyleCommitState(acknowledged)
                    requestStyleWriteOrInstall(routeIndex, acknowledged)
                }
                SuppliedCallOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(storeWriteFailure(cursor.content)),
                )
                is SuppliedCallOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        fun basemapStyleVisibilityInstallCompleted(event: BasemapStyleVisibilityInstallCompleted) {
            val routeIndex = routeIndexForAction<AwaitingStyleVisibilityInstall>(event.actionId)
            val cursor = routeRecords[routeIndex].cursor as AwaitingStyleVisibilityInstall
            require(cursor.groupId == event.groupId) {
                "style visibility acknowledgement must match its complete binding cursor"
            }
            if (finishActionAfterTerminal(routeIndex)) return
            refreshStyleCommitStates()
            val style = styleCommitState(cursor.groupId)

            when (val outcome = event.outcome) {
                SuppliedInstallOutcome.Succeeded -> installStyleVisibility(routeIndex, style)
                is SuppliedInstallOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(outcome.failure),
                )
                is SuppliedInstallOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
            }
        }

        private fun requestStyleValidation(routeIndex: Int, style: StyleCommitState) {
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingStyleValidation(
                    actionId = actionId,
                    ordinal = style.ordinal,
                    groupId = style.groupId,
                    content = style.stagedContent,
                ),
            )
            actions += ValidateBasemapStyle(actionId, style.ordinal, style.groupId, style.stagedContent)
        }

        /**
         * A validated style announces **routes**, never occurrences, so it adds nothing to the
         * occurrence graph and its depth-first frontier closes empty right here. Popping it is not
         * bookkeeping that could be skipped: the frontier is what `discoveryRequired` opened to hold
         * the style's siblings back until the document had been read, and
         * [ParkedRouteBarrier.StyleChildren] cannot release while any frontier is open.
         *
         * A style occurrence that never declared `discoveryRequired` owns no frontier; it simply
         * rejoins scheduling.
         */
        private fun releaseStyleTraversalFrontier(routeIndex: Int) {
            val frontier = frontierStack.lastOrNull()
            val parentId = frontier?.parentOccurrenceId
            val ownsFrontier = parentId != null &&
                parentId in joinedOccurrenceIdSetsByRouteIndex[routeIndex] &&
                occurrence(parentId).discoveryRequired
            if (!ownsFrontier) {
                startEligibleRoutes()
                return
            }

            frontierStack[frontierStack.lastIndex] = requireNotNull(frontier).withChildren(emptyList())
            releaseKnownTraversal()
            scheduleEligibleOccurrences()
        }

        private fun resumeStyleChildren(routeIndex: Int, style: StyleCommitState) {
            if (style.compilationStatus == StyleCompilationStatus.NOT_REQUIRED) {
                advanceStyleAfterCompilation(routeIndex, style, reschedule = false)
                return
            }
            val requested = style.withCompilationStatus(StyleCompilationStatus.REQUESTED)
            putStyleCommitState(requested)
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingStyleCompilation(
                    actionId = actionId,
                    ordinal = style.ordinal,
                    groupId = style.groupId,
                    content = style.stagedContent,
                ),
            )
            actions += CompileBasemapStyle(
                actionId = actionId,
                ordinal = style.ordinal,
                groupId = style.groupId,
                content = style.stagedContent,
                routes = style.styleTimeRoutes,
            )
        }

        private fun advanceStyleAfterCompilation(
            routeIndex: Int,
            style: StyleCommitState,
            reschedule: Boolean,
        ) {
            if (styleOwnerBarrierComplete(style)) {
                requestStyleWriteOrInstall(routeIndex, style)
                return
            }
            if (closeStyleBelowStartCeiling(routeIndex, style)) return
            updateRouteRecord(
                routeIndex,
                cursor = PendingClassGates(style.ordinal, style.stagedContent),
            )
            parkRoute(routeIndex, style.ordinal, ParkedRouteBarrier.StyleOwners(style.groupId))
            if (reschedule) startEligibleRoutes()
        }

        private fun requestStyleWriteOrInstall(routeIndex: Int, style: StyleCommitState) {
            val actionId = takeNextActionId()
            if (style.requiresWrite && !style.writeAcknowledged) {
                updateRouteRecord(
                    routeIndex,
                    cursor = AwaitingStyleWrite(
                        actionId = actionId,
                        ordinal = style.ordinal,
                        groupId = style.groupId,
                        content = style.stagedContent,
                    ),
                )
                actions += WriteBasemapStyle(
                    actionId = actionId,
                    ordinal = style.ordinal,
                    groupId = style.groupId,
                    rawKey = routeRecords[routeIndex].registration.rawKey,
                    resource = copyStored(style.stagedContent.stored),
                )
                return
            }
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingStyleVisibilityInstall(
                    actionId = actionId,
                    ordinal = style.ordinal,
                    groupId = style.groupId,
                    content = style.stagedContent,
                    referencingOwnerIds = style.referencingOwnerIds,
                ),
            )
            actions += InstallBasemapStyleVisibility(
                actionId = actionId,
                ordinal = style.ordinal,
                groupId = style.groupId,
                content = style.stagedContent,
                referencingOwnerIds = style.referencingOwnerIds,
            )
        }

        private fun installStyleVisibility(routeIndex: Int, style: StyleCommitState) {
            require(activeRouteOrdinals.remove(style.ordinal)) {
                "style group work requires its active style route"
            }
            putStyleCommitState(style.withVisible())
            updateRouteRecord(
                routeIndex,
                cursor = null,
                status = ResourceRouteStatus.RESOLVED,
                visibilityInstalled = true,
            )
            bufferRouteOutcome(style.ordinal, ResourceRouteOutcome.Success)
            if (terminalSelection == null) startEligibleRoutes()
        }

        private fun closeStyleBelowStartCeiling(routeIndex: Int, style: StyleCommitState): Boolean {
            val ceiling = startCeilingOrdinal ?: return false
            if (style.ordinal >= ceiling) return false
            closeRouteWithoutRemainingWork(routeIndex, style.ordinal)
            return true
        }

        private fun styleCommitBinding(routeIndex: Int): ResourceCommitBinding.BasemapStyle {
            val binding = joinedOccurrenceIdsByRouteIndex[routeIndex]
                .map { occurrence(it).commitBinding }
                .distinct()
                .singleOrNull()
            require(binding is ResourceCommitBinding.BasemapStyle) {
                "style commit requires exactly one basemap style commit binding"
            }
            require(
                routeRecords[routeIndex].registration.route.resourceClass == ResourceClass.BASEMAP_STYLE,
            ) { "a style route must carry basemap style content" }
            return binding
        }

        private fun styleReferencingOwnerIds(routeIndex: Int): List<ResourceOwnerId> =
            joinedOccurrenceIdsByRouteIndex[routeIndex]
                .map { occurrence(it).ownerId }
                .distinct()

        private fun ownerNonStyleWorkComplete(ownerId: ResourceOwnerId): Boolean =
            occurrences.none { candidate ->
                candidate.ownerId == ownerId &&
                    candidate.commitBinding !is ResourceCommitBinding.BasemapStyle &&
                    !visibilityInstalled(candidate)
            }

        private fun visibilityInstalled(occurrence: ResourceOccurrence): Boolean {
            val routeIndex = routeIndex(occurrence.registration.route)
            return routeIndex >= 0 && routeRecords[routeIndex].visibilityInstalled
        }

        private fun styleOwnerBarrierComplete(style: StyleCommitState): Boolean =
            style.referencingOwnerIds.all(::ownerNonStyleWorkComplete)

        private fun styleChildrenComplete(): Boolean {
            if (frontierStack.isNotEmpty() || eligibleFifo.isNotEmpty()) return false
            return occurrences.all { candidate ->
                if (candidate.id in staticOccurrenceIds) return@all true
                val routeIndex = routeIndex(candidate.registration.route)
                routeIndex >= 0 &&
                    routeRecords[routeIndex].ordinal != null &&
                    routeRecords[routeIndex].status == ResourceRouteStatus.RESOLVED
            }
        }

        private fun refreshStyleCommitStates() {
            if (styleCommitStates.isEmpty()) return
            styleCommitStates.forEachIndexed { index, style ->
                if (style.visible) return@forEachIndexed
                val routeIndex = routeIndexByOrdinal[style.ordinal] ?: return@forEachIndexed
                val referencingOwnerIds = styleReferencingOwnerIds(routeIndex)
                styleCommitStates[index] = style.withOwners(
                    referencingOwnerIds = referencingOwnerIds,
                    ownersWithCompletedNonStyleWork = referencingOwnerIds
                        .filter(::ownerNonStyleWorkComplete),
                )
            }
        }

        private fun styleCommitState(groupId: StyleGroupId): StyleCommitState {
            val index = requireNotNull(styleStateIndexByGroup[groupId]) {
                "style commit work requires its group commit state"
            }
            return styleCommitStates[index]
        }

        private fun putStyleCommitState(style: StyleCommitState) {
            val index = requireNotNull(styleStateIndexByGroup[style.groupId]) {
                "style commit work requires its group commit state"
            }
            styleCommitStates[index] = style
        }

        private fun addStyleCommitState(style: StyleCommitState) {
            require(styleStateIndexByGroup[style.groupId] == null) {
                "style commit group IDs must be unique"
            }
            styleStateIndexByGroup[style.groupId] = styleCommitStates.size
            styleCommitStates += style
        }

        private fun requestSpritePairValidation(routeIndex: Int, group: SpriteCommitState) {
            val json = requireNotNull(group.jsonCandidate) {
                "joint sprite validation requires its JSON candidate"
            }
            val image = requireNotNull(group.imageCandidate) {
                "joint sprite validation requires its image candidate"
            }
            require(routeRecords[routeIndex].ordinal == group.jsonOrdinal) {
                "sprite group work belongs to its JSON member ordinal"
            }
            val actionId = takeNextActionId()
            putSpriteCommitState(group.withJointValidationStatus(SpriteJointValidationStatus.REQUESTED))
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingSpritePairValidation(
                    actionId = actionId,
                    groupId = group.groupId,
                    jsonOrdinal = group.jsonOrdinal,
                    imageOrdinal = group.imageOrdinal,
                    json = json,
                    image = image,
                ),
            )
            actions += ValidateSpritePair(actionId, group.groupId, json, image)
        }

        private fun advanceSpriteWritesOrInstall(routeIndex: Int, group: SpriteCommitState) {
            val nextMember = group.requiredMemberWrites.getOrNull(group.acknowledgedWrites.size)
            if (nextMember == null) {
                requestSpriteVisibilityInstall(routeIndex, group)
                return
            }
            val content = requireNotNull(group.candidate(nextMember)) {
                "a written sprite member requires its validated candidate"
            }
            val memberOrdinal = group.ordinalOf(nextMember)
            val memberRouteIndex = requireNotNull(routeIndexByOrdinal[memberOrdinal]) {
                "a written sprite member must name an assigned route"
            }
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingSpriteMemberWrite(
                    actionId = actionId,
                    groupId = group.groupId,
                    member = nextMember,
                    ordinal = memberOrdinal,
                    content = content,
                ),
            )
            actions += WriteSpriteMember(
                actionId = actionId,
                groupId = group.groupId,
                member = nextMember,
                ordinal = memberOrdinal,
                rawKey = routeRecords[memberRouteIndex].registration.rawKey,
                resource = copyStored(content.stored),
            )
        }

        private fun requestSpriteVisibilityInstall(routeIndex: Int, group: SpriteCommitState) {
            val json = requireNotNull(group.jsonCandidate) {
                "sprite visibility requires its JSON candidate"
            }
            val image = requireNotNull(group.imageCandidate) {
                "sprite visibility requires its image candidate"
            }
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingSpriteVisibilityInstall(actionId, group.groupId, json, image),
            )
            actions += InstallSpriteVisibility(actionId, group.groupId, json, image)
        }

        private fun installSpriteVisibility(routeIndex: Int, group: SpriteCommitState) {
            val imageRouteIndex = requireNotNull(routeIndexByOrdinal[group.imageOrdinal]) {
                "a sprite image member must name an assigned route"
            }
            require(activeRouteOrdinals.remove(group.jsonOrdinal)) {
                "sprite group work requires its active JSON owner"
            }
            require(parkedRoutes.removeAll { it.ordinal == group.imageOrdinal }) {
                "sprite visibility requires its parked image member"
            }
            putSpriteCommitState(group.withVisible())
            updateRouteRecord(
                routeIndex,
                cursor = null,
                status = ResourceRouteStatus.RESOLVED,
                visibilityInstalled = true,
            )
            updateRouteRecord(
                imageRouteIndex,
                cursor = null,
                status = ResourceRouteStatus.RESOLVED,
                visibilityInstalled = true,
            )
            bufferRouteOutcome(group.jsonOrdinal, ResourceRouteOutcome.Success)
            bufferRouteOutcome(group.imageOrdinal, ResourceRouteOutcome.Success)
            if (terminalSelection == null) startEligibleRoutes()
        }

        private fun closeSpriteMember(
            routeIndex: Int,
            group: SpriteCommitState,
            member: SpriteMember,
            outcome: ResourceRouteOutcome,
        ) {
            require(outcome !is ResourceRouteOutcome.Success) {
                "a closed sprite member reports its own non-success"
            }
            require(activeRouteOrdinals.remove(group.jsonOrdinal)) {
                "sprite group work requires its active JSON owner"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            val reportedOrdinal = group.ordinalOf(member)
            if (reportedOrdinal == group.jsonOrdinal) {
                bufferRouteOutcome(reportedOrdinal, outcome)
            } else {
                val reportedRouteIndex = requireNotNull(routeIndexByOrdinal[reportedOrdinal]) {
                    "a reported sprite member must name an assigned route"
                }
                require(parkedRoutes.removeAll { it.ordinal == reportedOrdinal }) {
                    "a reported later sprite member must be parked"
                }
                updateRouteRecord(reportedRouteIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
                bufferRouteOutcome(reportedOrdinal, outcome)
                bufferRouteOutcome(group.jsonOrdinal, ResourceRouteOutcome.Success)
            }
            if (terminalSelection == null) startEligibleRoutes()
        }

        private fun closeSpriteOwner(
            routeIndex: Int,
            group: SpriteCommitState,
            outcome: ResourceRouteOutcome,
        ) {
            require(activeRouteOrdinals.remove(group.jsonOrdinal)) {
                "sprite group work requires its active JSON owner"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            bufferRouteOutcome(group.jsonOrdinal, outcome)
            if (terminalSelection == null) startEligibleRoutes()
        }

        private fun closeRouteWithoutRemainingWork(routeIndex: Int, ordinal: Long) {
            require(activeRouteOrdinals.remove(ordinal)) {
                "closing an unstarted commit requires an active route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            bufferRouteOutcome(ordinal, ResourceRouteOutcome.Success)
            if (terminalSelection == null) startEligibleRoutes()
        }

        private fun parkRoute(routeIndex: Int, ordinal: Long, barrier: ParkedRouteBarrier) {
            require(routeRecords[routeIndex].status == ResourceRouteStatus.RUNNING) {
                "parking requires a running route"
            }
            require(cursorActionId(routeRecords[routeIndex].cursor) == null) {
                "a parked route must have no in-flight adapter action"
            }
            require(activeRouteOrdinals.remove(ordinal)) {
                "parking requires an active route"
            }
            val insertionPoint = parkedRoutes.indexOfFirst { it.ordinal > ordinal }
            val parked = ParkedRoute(ordinal, barrier)
            if (insertionPoint < 0) parkedRoutes += parked else parkedRoutes.add(insertionPoint, parked)
        }

        private fun resumeReadyParkedRoutes() {
            while (activeRouteOrdinals.size < initial.definition.maximumConcurrentRoutes) {
                if (terminalSelection != null) return
                val ceiling = startCeilingOrdinal
                val resumable = parkedRoutes
                    .filter { parked ->
                        (ceiling == null || parked.ordinal < ceiling) && isParkedBarrierReady(parked)
                    }
                    .minByOrNull(ParkedRoute::ordinal) ?: return
                val routeIndex = requireNotNull(routeIndexByOrdinal[resumable.ordinal]) {
                    "a parked route must name an assigned route"
                }
                parkedRoutes.remove(resumable)
                activeRouteOrdinals += resumable.ordinal
                when (val barrier = resumable.barrier) {
                    is ParkedRouteBarrier.SpritePair -> requestSpritePairValidation(
                        routeIndex,
                        spriteCommitState(barrier.groupId),
                    )
                    is ParkedRouteBarrier.StyleChildren -> resumeStyleChildren(
                        routeIndex,
                        styleCommitState(barrier.groupId),
                    )
                    is ParkedRouteBarrier.StyleOwners -> requestStyleWriteOrInstall(
                        routeIndex,
                        styleCommitState(barrier.groupId),
                    )
                }
            }
        }

        private fun isParkedBarrierReady(parked: ParkedRoute): Boolean =
            when (val barrier = parked.barrier) {
                is ParkedRouteBarrier.SpritePair -> {
                    val group = spriteCommitState(barrier.groupId)
                    parked.ordinal == group.jsonOrdinal &&
                        group.jsonCandidate != null &&
                        group.imageCandidate != null &&
                        group.jointValidationStatus == SpriteJointValidationStatus.WAITING
                }
                is ParkedRouteBarrier.StyleChildren -> {
                    val style = styleCommitState(barrier.groupId)
                    parked.ordinal == style.ordinal && styleChildrenComplete()
                }
                is ParkedRouteBarrier.StyleOwners -> {
                    val style = styleCommitState(barrier.groupId)
                    parked.ordinal == style.ordinal && styleOwnerBarrierComplete(style)
                }
            }

        private fun closeParkedRoutesBelow(ceiling: Long) {
            val closable = parkedRoutes
                .filter { it.ordinal < ceiling && !parkedRouteRetainedByGroupWork(it) }
                .map(ParkedRoute::ordinal)
                .sorted()
            if (closable.isEmpty()) return
            parkedRoutes.removeAll { it.ordinal in closable }
            closable.forEach { ordinal ->
                val routeIndex = requireNotNull(routeIndexByOrdinal[ordinal]) {
                    "a parked route must name an assigned route"
                }
                updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
                insertBufferedOutcome(ordinal, ResourceRouteOutcome.Success)
            }
        }

        /**
         * A parked route below a new start ceiling normally closes as arbitration success because it has
         * no remaining work. A parked group member is the exception: while its group's shared work owner
         * is still active with an in-flight adapter action, that owner will resolve this member itself —
         * through install, member failure, or owner cancellation — and its own outcome slot must stay
         * open, because that in-flight action can still report a lower-ordinal non-success that wins
         * arbitration. Style barriers park their group's only work owner, so they never qualify.
         */
        private fun parkedRouteRetainedByGroupWork(parked: ParkedRoute): Boolean =
            when (val barrier = parked.barrier) {
                is ParkedRouteBarrier.SpritePair -> {
                    val group = spriteCommitState(barrier.groupId)
                    parked.ordinal != group.jsonOrdinal &&
                        when (group.jointValidationStatus) {
                            SpriteJointValidationStatus.REQUESTED,
                            SpriteJointValidationStatus.VALID,
                            -> true
                            SpriteJointValidationStatus.WAITING,
                            SpriteJointValidationStatus.FAILED,
                            -> false
                        }
                }
                is ParkedRouteBarrier.StyleChildren,
                is ParkedRouteBarrier.StyleOwners,
                -> false
            }

        private fun discardParkedRoutes() {
            parkedRoutes.map(ParkedRoute::ordinal).forEach { ordinal ->
                val routeIndex = requireNotNull(routeIndexByOrdinal[ordinal]) {
                    "a parked route must name an assigned route"
                }
                updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            }
            parkedRoutes.clear()
        }

        private fun spriteCommitBinding(routeIndex: Int): ResourceCommitBinding.Sprite {
            val binding = joinedOccurrenceIdsByRouteIndex[routeIndex]
                .map { occurrence(it).commitBinding }
                .distinct()
                .singleOrNull()
            require(binding is ResourceCommitBinding.Sprite) {
                "sprite commit requires exactly one sprite commit binding"
            }
            require(
                routeRecords[routeIndex].registration.route.resourceClass ==
                    spriteMemberResourceClass(binding.member),
            ) { "a sprite member route must carry its member resource class" }
            return binding
        }

        private fun spriteCommitState(groupId: SpriteGroupId): SpriteCommitState {
            val index = requireNotNull(spriteStateIndexByGroup[groupId]) {
                "sprite commit work requires its group commit state"
            }
            return spriteCommitStates[index]
        }

        private fun putSpriteCommitState(group: SpriteCommitState) {
            val index = requireNotNull(spriteStateIndexByGroup[group.groupId]) {
                "sprite commit work requires its group commit state"
            }
            spriteCommitStates[index] = group
        }

        private fun spriteCommitStateFor(
            groupId: SpriteGroupId,
            member: SpriteMember,
            ordinal: Long,
        ): SpriteCommitState {
            spriteStateIndexByGroup[groupId]?.let { index ->
                val established = spriteCommitStates[index]
                require(established.ordinalOf(member) == ordinal) {
                    "a sprite member must keep its group member ordinal"
                }
                return established
            }

            val otherMember = spriteMemberOrder().single { it != member }
            val otherOrdinal = spriteMemberRouteOrdinal(groupId, otherMember)
            val created = SpriteCommitState(
                groupId = groupId,
                jsonOrdinal = if (member == SpriteMember.JSON) ordinal else otherOrdinal,
                imageOrdinal = if (member == SpriteMember.IMAGE) ordinal else otherOrdinal,
                jsonCandidate = null,
                imageCandidate = null,
                jointValidationStatus = SpriteJointValidationStatus.WAITING,
                acknowledgedWrites = emptyList(),
                visible = false,
            )
            spriteStateIndexByGroup[groupId] = spriteCommitStates.size
            spriteCommitStates += created
            return created
        }

        private fun spriteMemberRouteOrdinal(groupId: SpriteGroupId, member: SpriteMember): Long {
            val binding = ResourceCommitBinding.Sprite(groupId, member)
            val ordinals = occurrences
                .filter { it.commitBinding == binding }
                .map { routeIndex(it.registration.route) }
                .filter { it >= 0 }
                .mapNotNull { routeRecords[it].ordinal }
                .distinct()
            require(ordinals.size == 1) {
                "each sprite member requires exactly one assigned route ordinal"
            }
            return ordinals.single()
        }

        private fun requestClassGate(
            routeIndex: Int,
            ordinal: Long,
            content: ResolvedResourceContent,
            gateIndex: Int,
        ) {
            val gate = ordinaryClassGates(content)[gateIndex]
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingClassGate(actionId, ordinal, content, gateIndex, gate),
            )
            actions += ValidateResourceClass(actionId, ordinal, content, gate)
        }

        private fun requestWriteOrVisibility(
            routeIndex: Int,
            ordinal: Long,
            content: ResolvedResourceContent,
        ) {
            if (!requiresStoreWrite(content.provenance)) {
                requestVisibilityInstall(routeIndex, ordinal, content)
                return
            }
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingStoreWrite(actionId, ordinal, content),
            )
            actions += WriteStore(
                actionId = actionId,
                ordinal = ordinal,
                rawKey = routeRecords[routeIndex].registration.rawKey,
                resource = copyStored(content.stored),
            )
        }

        private fun requestVisibilityInstall(
            routeIndex: Int,
            ordinal: Long,
            content: ResolvedResourceContent,
        ) {
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingVisibilityInstall(actionId, ordinal, content),
            )
            actions += InstallVisibility(actionId, ordinal, content)
        }

        private fun ordinaryClassGates(content: ResolvedResourceContent): List<ResourceClassGate> =
            requireNotNull(ordinaryResourceClassGates(content.route.resourceClass)) {
                "ordinary class gates require a resource class RenG's own driver acquires and gates"
            }

        private fun startStoreRead(
            routeIndex: Int,
            ordinal: Long,
            progress: LookupProgress,
            staleBaseline: StoredRawResource? = null,
        ) {
            require(!progress.storeReadStarted) { "Store read may start at most once per route" }
            val actionId = takeNextActionId()
            val started = progress.copy(
                staleBaseline = staleBaseline,
                storeReadStarted = true,
            )
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingStoreRead(actionId, ordinal),
                lookup = started,
            )
            actions += ReadStore(
                actionId,
                ordinal,
                routeRecords[routeIndex].registration.rawKey,
            )
        }

        private fun requestTransport(
            routeIndex: Int,
            ordinal: Long,
            progress: LookupProgress,
        ) {
            require(progress.transportLatch == null) {
                "route may finalize Transport metadata only once"
            }
            val registration = routeRecords[routeIndex].registration
            val route = registration.route
            val baseline = progress.staleBaseline
            val etag = baseline?.metadata?.etag.takeIf { route.accessMode == ResourceAccessMode.NORMAL }
            val lastModified = baseline?.metadata?.lastModified
                .takeIf { route.accessMode == ResourceAccessMode.NORMAL && etag == null }
            val metadata = TransportRequestMetadata(
                ifNoneMatch = etag,
                ifModifiedSince = lastModified,
                accept = route.resourceClass.acceptValue,
            )
            val request = TransportRequest(
                locator = route.locator,
                resourceClass = route.resourceClass,
                maximumResponseBytes = route.maximumResponseBytes,
                metadata = metadata,
            )
            val latchKey = TransportLatchKey(
                route = route,
                ifNoneMatch = metadata.ifNoneMatch,
                ifModifiedSince = metadata.ifModifiedSince,
                accept = metadata.accept,
            )
            val actionId = takeNextActionId()
            val withLatch = progress.copy(transportLatch = latchKey)
            val closedLatchIndex = transportLatchIndexByKey[latchKey]
            if (closedLatchIndex == null) {
                updateRouteRecord(
                    routeIndex,
                    cursor = AwaitingTransport(actionId, ordinal, latchKey),
                    lookup = withLatch,
                )
                actions += CallTransport(actionId, ordinal, request, latchKey)
            } else {
                val latch = transportLatches[closedLatchIndex]
                updateRouteRecord(
                    routeIndex,
                    cursor = AwaitingLatchedTransportReplay(actionId, ordinal, latchKey),
                    lookup = withLatch,
                )
                actions += ReplayLatchedTransport(actionId, ordinal, copyLatch(latch))
            }
        }

        private fun resolveLatchedResponse(
            routeIndex: Int,
            ordinal: Long,
            latchKey: TransportLatchKey,
            response: TransportResponse,
        ) {
            val record = routeRecords[routeIndex]
            val progress = requireNotNull(record.lookup)
            val sample = requireNotNull(progress.sampleEpochMillis)
            val outcome = resolveTransportResponse(
                route = record.registration.route,
                resourceKey = record.registration.resourceKey,
                sampleEpochMillis = sample,
                staleBaseline = progress.staleBaseline,
                conditionalRequest = latchKey.ifNoneMatch != null || latchKey.ifModifiedSince != null,
                response = response,
                sha256 = AcceleratedSha256,
            )
            when (outcome) {
                is ResponseRuleOutcome.Failure -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(outcome.failure),
                )
                is ResponseRuleOutcome.Selected -> {
                    val selected = outcome.content
                    updateRouteRecord(
                        routeIndex,
                        cursor = PendingClassGates(ordinal, selected),
                        lookup = progress.copy(selectedContent = selected),
                    )
                }
            }
        }

        private fun selectContent(
            routeIndex: Int,
            ordinal: Long,
            progress: LookupProgress,
            stored: StoredRawResource,
            provenance: ContentProvenance,
        ) {
            val registration = routeRecords[routeIndex].registration
            val selected = ResolvedResourceContent(
                route = registration.route,
                resourceKey = registration.resourceKey,
                stored = copyStored(stored),
                provenance = provenance,
            )
            updateRouteRecord(
                routeIndex,
                cursor = PendingClassGates(ordinal, selected),
                lookup = progress.copy(selectedContent = selected),
            )
        }

        private fun completeLookupRoute(
            routeIndex: Int,
            outcome: ResourceRouteOutcome,
        ) {
            val record = routeRecords[routeIndex]
            val ordinal = requireNotNull(record.ordinal)
            require(record.status == ResourceRouteStatus.RUNNING) {
                "lookup completion requires a running route"
            }
            require(activeRouteOrdinals.remove(ordinal)) {
                "lookup completion requires an active route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            if (terminalSelection == null) {
                bufferRouteOutcome(ordinal, outcome)
                if (terminalSelection == null) startEligibleRoutes()
            }
        }

        private fun finishActionAfterTerminal(routeIndex: Int): Boolean {
            if (terminalSelection == null) return false
            val record = routeRecords[routeIndex]
            val ordinal = requireNotNull(record.ordinal)
            require(record.status == ResourceRouteStatus.RUNNING && activeRouteOrdinals.remove(ordinal)) {
                "terminal cleanup action must belong to an active route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            return true
        }

        private fun ownsActiveDiscoveryFrontier(routeIndex: Int): Boolean {
            val parentId = frontierStack.lastOrNull()?.parentOccurrenceId ?: return false
            return parentId in joinedOccurrenceIdSetsByRouteIndex[routeIndex] &&
                occurrence(parentId).discoveryRequired
        }

        fun routeReadyForDiscovery(event: RouteReadyForDiscovery) {
            val parent = occurrence(event.parentOccurrenceId)
            require(parent.discoveryRequired) { "route discovery readiness requires a discovery occurrence" }
            require(frontierStack.lastOrNull()?.parentOccurrenceId == parent.id) {
                "route discovery readiness must match the active depth-first frontier"
            }
            val routeIndex = routeIndex(parent.registration.route)
            require(routeIndex >= 0) { "route discovery readiness requires a registered route" }
            val record = routeRecords[routeIndex]
            require(record.ordinal == event.ordinal) { "route discovery readiness must match its ordinal" }
            require(record.status == ResourceRouteStatus.RUNNING) {
                "route discovery readiness requires a running route"
            }
            require(cursorActionId(record.cursor) == null) {
                "route discovery readiness requires no in-flight adapter action"
            }
            require(record.lookup == null || record.visibilityInstalled) {
                "route discovery readiness requires its own installed visibility"
            }
            require(activeRouteOrdinals.remove(event.ordinal)) {
                "route discovery readiness requires an active route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            bufferRouteOutcome(event.ordinal, ResourceRouteOutcome.Success)
            if (terminalSelection == null) {
                actions += DiscoverChildren(event.ordinal, parent.id)
                startEligibleRoutes()
            }
        }

        fun routeCompleted(event: RouteCompleted) {
            val routeIndex = routeIndexByOrdinal[event.ordinal]
            require(routeIndex != null) { "route completion must name an assigned route" }
            val record = routeRecords[routeIndex]
            require(record.status == ResourceRouteStatus.RUNNING) {
                "route completion requires a running route"
            }
            require(cursorActionId(record.cursor) == null) {
                "route completion requires no in-flight adapter action"
            }
            if (event.outcome is ResourceRouteOutcome.Success) {
                val frontierParentId = frontierStack.lastOrNull()?.parentOccurrenceId
                require(frontierParentId == null || frontierParentId !in record.joinedOccurrenceIds) {
                    "successful discovery route must complete through discovery readiness"
                }
            }
            require(activeRouteOrdinals.remove(event.ordinal)) {
                "route completion requires an active route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)

            if (terminalSelection == null) {
                bufferRouteOutcome(event.ordinal, event.outcome)
                if (terminalSelection == null) startEligibleRoutes()
            }
        }

        fun externalCancellationRequested(event: ExternalCancellationRequested) {
            if (terminalSelection != null) return
            terminalSelection = ResourceTerminalSelection.External(event.cancellation)
            discardParkedRoutes()
            cancelActiveRoutes(activeRouteOrdinals.toList())
        }

        fun cleanupCancellationObserved(event: CleanupCancellationObserved) {
            require(terminalSelection != null) {
                "cleanup cancellation requires a selected terminal"
            }
            require(activeRouteOrdinals.remove(event.ordinal)) {
                "cleanup cancellation must name an active route"
            }
            val routeIndex = routeIndexByOrdinal[event.ordinal]
            require(routeIndex != null) { "cleanup cancellation must name an assigned route" }
            require(routeRecords[routeIndex].status == ResourceRouteStatus.RUNNING) {
                "cleanup cancellation requires a running route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
        }

        fun childrenDiscovered(event: ChildrenDiscovered) {
            val frontier = frontierStack.lastOrNull()
            require(frontier?.parentOccurrenceId == event.parentOccurrenceId) {
                "discovered children must match the active depth-first frontier"
            }
            val parent = occurrence(event.parentOccurrenceId)
            require(parent.discoveryRequired) { "discovered children require a discovery occurrence" }
            val parentRouteIndex = routeIndex(parent.registration.route)
            require(parentRouteIndex >= 0) { "discovered children require a registered parent route" }
            require(routeRecords[parentRouteIndex].status == ResourceRouteStatus.RESOLVED) {
                "discovered children require a resolved parent route"
            }

            val children = event.children
            val dynamicIds = mutableSetOf<ResourceOccurrenceId>()
            require(children.all { child ->
                child.occurrence.id !in occurrenceById && dynamicIds.add(child.occurrence.id)
            }) { "discovered resource occurrence IDs must be unique" }

            children.forEach { child ->
                occurrences += child.occurrence
                occurrenceById[child.occurrence.id] = child.occurrence
            }
            frontierStack[frontierStack.lastIndex] = frontier.withChildren(
                children.map { it.occurrence.id },
            )
            releaseKnownTraversal()
            scheduleEligibleOccurrences()
        }

        fun releaseKnownTraversal() {
            while (true) {
                if (frontierStack.isNotEmpty()) {
                    val frontierIndex = frontierStack.lastIndex
                    val frontier = frontierStack[frontierIndex]
                    if (frontier.hasChildren) {
                        val childId = frontier.firstChild()
                        val remaining = frontier.afterFirstChild()
                        eligibleFifo.addLast(childId)
                        val child = occurrence(childId)
                        if (child.discoveryRequired) {
                            frontierStack[frontierIndex] = frontier.withoutChildren()
                            frontierStack += remaining.remainingChildrenAsWithheld(child.id)
                            return
                        }
                        frontierStack[frontierIndex] = remaining
                        continue
                    }

                    frontierStack.removeAt(frontierIndex)
                    restoreContinuation(frontier)
                    continue
                }

                if (staticContinuation.isEmpty) return
                val occurrenceId = staticContinuation.first()
                staticContinuation = staticContinuation.advance()
                eligibleFifo.addLast(occurrenceId)
                val occurrence = occurrence(occurrenceId)
                if (occurrence.discoveryRequired) {
                    val withheld = staticContinuation
                    staticContinuation = ResourceOccurrenceIdSlice.empty()
                    frontierStack += DiscoveryFrontier.unresolved(occurrence.id, withheld)
                    return
                }
            }
        }

        private fun restoreContinuation(frontier: DiscoveryFrontier) {
            if (frontierStack.isEmpty()) {
                require(staticContinuation.isEmpty) { "static traversal continuation must be singular" }
                staticContinuation = frontier.withheldSlice()
                return
            }

            val parentIndex = frontierStack.lastIndex
            frontierStack[parentIndex] = frontier.restoreWithheldInto(frontierStack[parentIndex])
        }

        fun scheduleEligibleOccurrences() {
            while (eligibleFifo.isNotEmpty() && terminalSelection == null) {
                val occurrenceId = eligibleFifo.removeFirst()
                makeOccurrenceEligible(occurrence(occurrenceId))
            }
            startEligibleRoutes()
        }

        private fun makeOccurrenceEligible(occurrence: ResourceOccurrence) {
            val identityCollision = identityCollision(occurrence.registration)
            if (identityCollision != null) {
                blockByCollision(occurrence, identityCollision)
                return
            }

            val existingRouteIndex = routeIndex(occurrence.registration.route)
            if (existingRouteIndex >= 0) {
                makeJoinedOccurrenceEligible(existingRouteIndex, occurrence)
                return
            }

            val ordinal = takeNextOrdinal()
            if (occurrence.registration.resourceKey.stableId !in identityByStableId) {
                val identity = CanonicalIdentityRecord(
                    occurrence.registration.resourceKey,
                    occurrence.registration.canonicalBytes,
                )
                identityRecords += identity
                identityByStableId[identity.resourceKey.stableId] = identity
            }
            val claimIndex = claimIndex(occurrence.registration.privateRentileKey)
            if (claimIndex >= 0) {
                val claim = privateRentileKeyClaims[claimIndex]
                val invalidatedEligibleOrdinal = claim.takeIf { it.usable }
                    ?.let { routeIndex(it.firstRoute) }
                    ?.takeIf { it >= 0 }
                    ?.let(routeRecords::get)
                    ?.takeIf { it.status == ResourceRouteStatus.ELIGIBLE }
                    ?.ordinal
                privateRentileKeyClaims[claimIndex] = claim.copy(usable = false)
                addRouteRecord(
                    RouteRecord(
                        registration = occurrence.registration,
                        joinedOccurrenceIds = listOf(occurrence.id),
                        ordinal = ordinal,
                        cursor = null,
                        status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
                    ),
                )
                val failureOutcome = ResourceRouteOutcome.Failure(ambiguousRouteFailure())
                bufferRouteOutcome(invalidatedEligibleOrdinal ?: ordinal, failureOutcome)
                return
            }

            claimIndexByPrivateKey[occurrence.registration.privateRentileKey] =
                privateRentileKeyClaims.size
            privateRentileKeyClaims += PrivateRentileKeyClaim(
                privateKey = occurrence.registration.privateRentileKey,
                firstRoute = occurrence.registration.route,
                usable = true,
            )
            addRouteRecord(
                RouteRecord(
                    registration = occurrence.registration,
                    joinedOccurrenceIds = listOf(occurrence.id),
                    ordinal = ordinal,
                    cursor = null,
                    status = ResourceRouteStatus.ELIGIBLE,
                ),
            )
        }

        private fun makeJoinedOccurrenceEligible(
            routeIndex: Int,
            occurrence: ResourceOccurrence,
        ) {
            if (joinedOccurrenceIdSetsByRouteIndex[routeIndex].add(occurrence.id)) {
                joinedOccurrenceIdsByRouteIndex[routeIndex] += occurrence.id
                // A style commit's referencing owners are derived from its route's joined occurrences,
                // so a new join redefines them here, not only when the scheduler next runs.
                refreshStyleCommitStates()
            }
            val record = routeRecords[routeIndex]
            if (record.ordinal == null) {
                updateRouteRecord(
                    routeIndex,
                    ordinal = takeNextOrdinal(),
                    status = ResourceRouteStatus.ELIGIBLE,
                )
                return
            }

            if (occurrence.discoveryRequired && record.status == ResourceRouteStatus.RESOLVED) {
                actions += DiscoverChildren(record.ordinal, occurrence.id)
            }
        }

        private fun identityCollision(registration: ResourceRouteRegistration): CanonicalIdentityRecord? {
            val established = identityByStableId[registration.resourceKey.stableId] ?: return null
            return established.takeIf { it.canonicalBytes != registration.canonicalBytes }
        }

        private fun blockByCollision(
            occurrence: ResourceOccurrence,
            established: CanonicalIdentityRecord,
        ) {
            val ordinal = takeNextOrdinal()
            addRouteRecord(
                RouteRecord(
                    registration = occurrence.registration,
                    joinedOccurrenceIds = listOf(occurrence.id),
                    ordinal = ordinal,
                    cursor = null,
                    status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
                ),
            )
            bufferRouteOutcome(
                ordinal,
                ResourceRouteOutcome.Failure(identityCollisionFailure(established)),
            )
        }

        private fun startEligibleRoutes() {
            if (terminalSelection != null) return
            refreshStyleCommitStates()
            resumeReadyParkedRoutes()
            startNotYetStartedRoutes()
        }

        private fun startNotYetStartedRoutes() {
            val ceiling = startCeilingOrdinal
            val candidates = routeRecords.withIndex()
                .filter { (_, record) ->
                    val claimIndex = claimIndexByPrivateKey[record.registration.privateRentileKey]
                    record.status == ResourceRouteStatus.ELIGIBLE &&
                        record.ordinal != null &&
                        claimIndex != null &&
                        privateRentileKeyClaims[claimIndex].usable &&
                        (ceiling == null || record.ordinal < ceiling)
                }
                .sortedBy { it.value.ordinal }

            for ((index, record) in candidates) {
                if (activeRouteOrdinals.size >= initial.definition.maximumConcurrentRoutes) break
                val ordinal = requireNotNull(record.ordinal)
                updateRouteRecord(index, status = ResourceRouteStatus.RUNNING)
                activeRouteOrdinals += ordinal
                actions += StartRoute(ordinal, record.registration)
            }
        }

        private fun bufferRouteOutcome(
            ordinal: Long,
            outcome: ResourceRouteOutcome,
        ) {
            require(ordinal >= nextRetirementOrdinal && ordinal < nextRouteOrdinal) {
                "route completion must name an unretired assigned ordinal"
            }
            insertBufferedOutcome(ordinal, outcome)
            if (outcome !is ResourceRouteOutcome.Success) {
                val ceiling = minOf(startCeilingOrdinal ?: ordinal, ordinal)
                startCeilingOrdinal = ceiling
                closeParkedRoutesBelow(ceiling)
            }
            retireBufferedPrefix()
        }

        private fun insertBufferedOutcome(
            ordinal: Long,
            outcome: ResourceRouteOutcome,
        ) {
            val insertionPoint = bufferedRouteOutcomes.binarySearch { buffered ->
                buffered.ordinal.compareTo(ordinal)
            }
            require(insertionPoint < 0) { "route outcome must be observed exactly once" }
            bufferedRouteOutcomes.add(
                index = -insertionPoint - 1,
                element = BufferedRouteOutcome(ordinal, outcome),
            )
        }

        private fun retireBufferedPrefix() {
            var retiredCount = 0
            while (terminalSelection == null && retiredCount < bufferedRouteOutcomes.size) {
                val retired = bufferedRouteOutcomes[retiredCount]
                if (retired.ordinal != nextRetirementOrdinal) break
                retiredCount += 1
                check(nextRetirementOrdinal < Long.MAX_VALUE) {
                    "route retirement ordinal space exhausted"
                }
                nextRetirementOrdinal += 1L
                when (retired.outcome) {
                    ResourceRouteOutcome.Success -> Unit
                    is ResourceRouteOutcome.Failure,
                    is ResourceRouteOutcome.Cancelled,
                    -> {
                        terminalSelection = ResourceTerminalSelection.Route(
                            retired.ordinal,
                            retired.outcome,
                        )
                        discardParkedRoutes()
                        cancelActiveRoutes(
                            activeRouteOrdinals.filter { it > retired.ordinal },
                        )
                    }
                }
            }
            if (retiredCount > 0) {
                val retained = bufferedRouteOutcomes.subList(
                    retiredCount,
                    bufferedRouteOutcomes.size,
                ).toList()
                bufferedRouteOutcomes.clear()
                bufferedRouteOutcomes.addAll(retained)
            }
        }

        private fun cancelActiveRoutes(ordinals: List<Long>) {
            ordinals.sorted().forEach { ordinal -> actions += CancelRoute(ordinal) }
        }

        private fun terminalOutcome(): ResourceOperationOutcome? {
            val selected = terminalSelection ?: return successOutcome()
            if (activeRouteOrdinals.isNotEmpty()) return null
            return when (selected) {
                is ResourceTerminalSelection.External ->
                    ResourceOperationOutcome.Cancelled(selected.cancellation)
                is ResourceTerminalSelection.Route -> when (val routeOutcome = selected.outcome) {
                    ResourceRouteOutcome.Success -> error("successful route cannot occupy the terminal slot")
                    is ResourceRouteOutcome.Failure -> ResourceOperationOutcome.Failure(routeOutcome.failure)
                    is ResourceRouteOutcome.Cancelled ->
                        ResourceOperationOutcome.Cancelled(routeOutcome.cancellation)
                }
            }
        }

        private fun successOutcome(): ResourceOperationOutcome? {
            if (activeRouteOrdinals.isNotEmpty()) return null
            if (nextRetirementOrdinal != nextRouteOrdinal) return null
            // Retained deliberately, though no admissible state distinguishes it from the line above:
            // outcomes buffer only at ordinals at or above nextRetirementOrdinal, so once every route
            // has retired the buffer is necessarily drained. It is kept as an explicit statement of the
            // success precondition rather than removed or covered by a vacuous test.
            if (bufferedRouteOutcomes.isNotEmpty()) return null
            if (eligibleFifo.isNotEmpty() || !staticContinuation.isEmpty || frontierStack.isNotEmpty()) {
                return null
            }
            if (routeRecords.any { it.status != ResourceRouteStatus.RESOLVED }) return null

            val visibleByRoute = visibleResourcesByRoute()
            if (occurrences.any { it.registration.route !in visibleByRoute }) return null
            return ResourceOperationOutcome.Success(deriveVisibleResourcesByOwner())
        }

        private fun visibleResourcesByRoute(): Map<ResourceRouteKey, VisibleResource> {
            val visibleByRoute = mutableMapOf<ResourceRouteKey, VisibleResource>()
            routeRecords.forEach { record ->
                if (!record.visibilityInstalled) return@forEach
                val content = requireNotNull(record.lookup?.selectedContent) {
                    "installed visibility requires selected content"
                }
                visibleByRoute[record.registration.route] =
                    VisibleResource(record.registration.resourceKey, content)
            }
            return visibleByRoute
        }

        private fun deriveVisibleResourcesByOwner(): List<OwnerResourceSet> {
            val visibleByRoute = visibleResourcesByRoute()
            if (visibleByRoute.isEmpty()) return emptyList()
            val ownerOrder = mutableListOf<ResourceOwnerId>()
            val resourcesByOwner = mutableMapOf<ResourceOwnerId, MutableList<VisibleResource>>()
            occurrences.forEach { occurrence ->
                val visible = visibleByRoute[occurrence.registration.route] ?: return@forEach
                val owned = resourcesByOwner.getOrPut(occurrence.ownerId) {
                    ownerOrder += occurrence.ownerId
                    mutableListOf()
                }
                if (visible !in owned) owned += visible
            }
            return ownerOrder.map { ownerId -> OwnerResourceSet(ownerId, resourcesByOwner.getValue(ownerId)) }
        }

        private fun addRouteRecord(record: RouteRecord): Int {
            val index = routeRecords.size
            routeRecords += record
            val joinedIds = record.joinedOccurrenceIds.toMutableList()
            joinedOccurrenceIdsByRouteIndex += joinedIds
            joinedOccurrenceIdSetsByRouteIndex += joinedIds.toMutableSet()
            if (record.registration.route !in routeIndexByRoute) {
                routeIndexByRoute[record.registration.route] = index
            }
            record.ordinal?.let { ordinal ->
                require(routeIndexByOrdinal.put(ordinal, index) == null) {
                    "route ordinals must be unique"
                }
            }
            cursorActionId(record.cursor)?.let { actionId ->
                require(routeIndexByActionId.put(actionId, index) == null) {
                    "route cursor action IDs must be unique"
                }
            }
            return index
        }

        private fun updateRouteRecord(
            index: Int,
            ordinal: Long? = routeRecords[index].ordinal,
            cursor: ResourceRouteCursor? = routeRecords[index].cursor,
            status: ResourceRouteStatus = routeRecords[index].status,
            lookup: LookupProgress? = routeRecords[index].lookup,
            storeWriteAcknowledged: Boolean = routeRecords[index].storeWriteAcknowledged,
            visibilityInstalled: Boolean = routeRecords[index].visibilityInstalled,
        ) {
            val previous = routeRecords[index]
            cursorActionId(previous.cursor)?.let { actionId ->
                require(routeIndexByActionId.remove(actionId) == index) {
                    "previous cursor action must remain indexed"
                }
            }
            if (previous.ordinal != ordinal) {
                previous.ordinal?.let(routeIndexByOrdinal::remove)
                ordinal?.let { assigned ->
                    require(routeIndexByOrdinal.put(assigned, index) == null) {
                        "route ordinals must be unique"
                    }
                }
            }
            cursorActionId(cursor)?.let { actionId ->
                require(routeIndexByActionId.put(actionId, index) == null) {
                    "route cursor action IDs must be unique"
                }
            }
            routeRecords[index] = RouteRecord(
                registration = previous.registration,
                joinedOccurrenceIds = joinedOccurrenceIdsByRouteIndex[index],
                ordinal = ordinal,
                cursor = cursor,
                status = status,
                lookup = lookup,
                storeWriteAcknowledged = storeWriteAcknowledged,
                visibilityInstalled = visibilityInstalled,
            )
        }

        private fun takeNextActionId(): ResourceActionId {
            check(nextActionId < Long.MAX_VALUE) { "resource action ID space exhausted" }
            val actionId = ResourceActionId(nextActionId)
            nextActionId += 1L
            return actionId
        }

        private inline fun <reified T : ResourceRouteCursor> routeIndexForAction(
            actionId: ResourceActionId,
        ): Int {
            val routeIndex = requireNotNull(routeIndexByActionId[actionId]) {
                "resource event must match a current action ID"
            }
            val cursor = routeRecords[routeIndex].cursor
            require(cursor is T && cursorActionId(cursor) == actionId) {
                "resource event must match its current cursor"
            }
            return routeIndex
        }

        private fun addTransportLatch(latch: TransportLatchRecord) {
            require(latch.key !in transportLatchIndexByKey) {
                "Transport latch may close only once"
            }
            val copied = copyLatch(latch)
            transportLatchIndexByKey[copied.key] = transportLatches.size
            transportLatches += copied
        }

        private fun transportLatch(key: TransportLatchKey): TransportLatchRecord {
            val index = requireNotNull(transportLatchIndexByKey[key]) {
                "latched replay requires a closed exact latch"
            }
            return transportLatches[index]
        }

        private fun takeNextOrdinal(): Long {
            val ordinal = nextRouteOrdinal
            check(ordinal < Long.MAX_VALUE) { "route ordinal space exhausted" }
            nextRouteOrdinal += 1L
            return ordinal
        }

        private fun occurrence(id: ResourceOccurrenceId): ResourceOccurrence =
            requireNotNull(occurrenceById[id]) { "resource occurrence must be registered exactly once" }

        private fun routeIndex(route: ResourceRouteKey): Int = routeIndexByRoute[route] ?: -1

        private fun claimIndex(privateKey: RentilePrivateKey): Int =
            claimIndexByPrivateKey[privateKey] ?: -1

        fun toTransition(): ResourceOperationTransition = ResourceOperationTransition(
            state = runningState(
                definition = initial.definition,
                occurrences = occurrences,
                routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRouteIndex),
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
                transportLatches = transportLatches,
                nextActionId = nextActionId,
                traversal = TraversalState.fromSlices(
                    eligibleFifo = eligibleFifo.toList(),
                    staticContinuation = staticContinuation,
                    frontierStack = frontierStack,
                ),
                nextRouteOrdinal = nextRouteOrdinal,
                activeRouteOrdinals = activeRouteOrdinals,
                nextRetirementOrdinal = nextRetirementOrdinal,
                bufferedRouteOutcomes = bufferedRouteOutcomes,
                startCeilingOrdinal = startCeilingOrdinal,
                terminalSelection = terminalSelection,
                spriteCommitStates = spriteCommitStates,
                parkedRoutes = parkedRoutes,
                styleCommitStates = styleCommitStates,
                visibleResourcesByOwner = deriveVisibleResourcesByOwner(),
            ),
            actions = actions,
            outcome = terminalOutcome(),
        )
    }

    private fun preregisteredRouteRecord(occurrence: ResourceOccurrence): RouteRecord = RouteRecord(
        registration = occurrence.registration,
        joinedOccurrenceIds = listOf(occurrence.id),
        ordinal = null,
        cursor = null,
        status = ResourceRouteStatus.PREREGISTERED,
    )

    private fun freezeRouteRecords(
        routeRecords: List<RouteRecord>,
        joinedOccurrenceIdsByRoute: List<List<ResourceOccurrenceId>>,
    ): List<RouteRecord> {
        require(routeRecords.size == joinedOccurrenceIdsByRoute.size) {
            "route records and joined occurrences must stay synchronized"
        }
        return routeRecords.mapIndexed { index, record ->
            RouteRecord(
                registration = record.registration,
                joinedOccurrenceIds = joinedOccurrenceIdsByRoute[index],
                ordinal = record.ordinal,
                cursor = record.cursor,
                status = record.status,
                lookup = record.lookup,
                storeWriteAcknowledged = record.storeWriteAcknowledged,
                visibilityInstalled = record.visibilityInstalled,
            )
        }
    }

    private fun failed(
        definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
        failure: FailureDescriptor,
    ): ResourceOperationTransition = ResourceOperationTransition(
        state = runningState(
            definition = definition,
            occurrences = occurrences,
            routeRecords = routeRecords,
            privateRentileKeyClaims = privateRentileKeyClaims,
            identityRecords = identityRecords,
        ),
        actions = emptyList(),
        outcome = ResourceOperationOutcome.Failure(failure),
    )

    private fun runningState(
        definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
        transportLatches: List<TransportLatchRecord> = emptyList(),
        nextActionId: Long = 1L,
        traversal: TraversalState = TraversalState(emptyList(), emptyList(), emptyList()),
        nextRouteOrdinal: Long = 0L,
        activeRouteOrdinals: List<Long> = emptyList(),
        nextRetirementOrdinal: Long = 0L,
        bufferedRouteOutcomes: List<BufferedRouteOutcome> = emptyList(),
        startCeilingOrdinal: Long? = null,
        terminalSelection: ResourceTerminalSelection? = null,
        spriteCommitStates: List<SpriteCommitState> = emptyList(),
        parkedRoutes: List<ParkedRoute> = emptyList(),
        styleCommitStates: List<StyleCommitState> = emptyList(),
        visibleResourcesByOwner: List<OwnerResourceSet> = emptyList(),
    ): ResourceOperationState.Running = ResourceOperationState.Running(
        definition = definition,
        occurrences = occurrences,
        routeRecords = routeRecords,
        privateRentileKeyClaims = privateRentileKeyClaims,
        identityRecords = identityRecords,
        transportLatches = transportLatches,
        nextActionId = nextActionId,
        traversal = traversal,
        nextRouteOrdinal = nextRouteOrdinal,
        activeRouteOrdinals = activeRouteOrdinals,
        nextRetirementOrdinal = nextRetirementOrdinal,
        bufferedRouteOutcomes = bufferedRouteOutcomes,
        startCeilingOrdinal = startCeilingOrdinal,
        terminalSelection = terminalSelection,
        spriteCommitStates = spriteCommitStates,
        parkedRoutes = parkedRoutes,
        styleCommitStates = styleCommitStates,
        visibleResourcesByOwner = visibleResourcesByOwner,
    )

    private fun ambiguousRouteFailure(): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
        ),
    )

    private fun identityCollisionFailure(
        established: CanonicalIdentityRecord,
    ): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.IDENTITY_COLLISION,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = established.resourceKey.resourceClass,
            resourceKey = established.resourceKey,
        ),
    )

    private fun resourceUnavailableFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.RESOURCE_UNAVAILABLE,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun storeReadFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_READ_FAILED,
        stage = PipelineStage.STORE_READ,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_READ,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun storeIntegrityFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_INTEGRITY_FAILED,
        stage = PipelineStage.STORE_VALIDATION,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_VALIDATION,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun storeIntegrityFailure(content: ResolvedResourceContent): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_INTEGRITY_FAILED,
        stage = PipelineStage.STORE_VALIDATION,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_VALIDATION,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = content.route.resourceClass,
            resourceKey = content.resourceKey,
        ),
    )

    private fun storeWriteFailure(content: ResolvedResourceContent): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_WRITE_FAILED,
        stage = PipelineStage.STORE_WRITE,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_WRITE,
            resourceClass = content.route.resourceClass,
            resourceKey = content.resourceKey,
        ),
    )

    private fun classGateFailure(
        content: ResolvedResourceContent,
        gate: ResourceClassGate,
    ): FailureDescriptor {
        if (content.provenance == ContentProvenance.STORE) return storeIntegrityFailure(content)
        return when (gate) {
            ResourceClassGate.PARSE_GLB -> resourceFormatFailure(
                content,
                RenGErrorCode.RESOURCE_PARSE_FAILED,
                PipelineStage.RESOURCE_PARSING,
            )
            ResourceClassGate.DECODE_PNG -> resourceFormatFailure(
                content,
                RenGErrorCode.RESOURCE_DECODE_FAILED,
                PipelineStage.RESOURCE_DECODING,
            )
            ResourceClassGate.VALIDATE_GLB_FEATURES -> resourceFormatFailure(
                content,
                RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
                PipelineStage.RESOURCE_PARSING,
            )
        }
    }

    private fun spritePairFailure(
        content: ResolvedResourceContent,
        kind: SpritePairFailureKind,
    ): FailureDescriptor {
        if (content.provenance == ContentProvenance.STORE) return storeIntegrityFailure(content)
        return when (kind) {
            SpritePairFailureKind.JSON_PARSE -> resourceFormatFailure(
                content,
                RenGErrorCode.RESOURCE_PARSE_FAILED,
                PipelineStage.RESOURCE_PARSING,
            )
            SpritePairFailureKind.IMAGE_DECODE -> resourceFormatFailure(
                content,
                RenGErrorCode.RESOURCE_DECODE_FAILED,
                PipelineStage.RESOURCE_DECODING,
            )
            SpritePairFailureKind.UNSUPPORTED_FEATURE -> resourceFormatFailure(
                content,
                RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
                PipelineStage.RESOURCE_PARSING,
            )
        }
    }

    private fun styleFailure(
        content: ResolvedResourceContent,
        kind: StyleFailureKind,
    ): FailureDescriptor {
        if (content.provenance == ContentProvenance.STORE) return storeIntegrityFailure(content)
        return when (kind) {
            StyleFailureKind.PARSE -> resourceFormatFailure(
                content,
                RenGErrorCode.RESOURCE_PARSE_FAILED,
                PipelineStage.RESOURCE_PARSING,
            )
            StyleFailureKind.UNSUPPORTED_FEATURE -> resourceFormatFailure(
                content,
                RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
                PipelineStage.RESOURCE_PARSING,
            )
        }
    }

    private fun resourceFormatFailure(
        content: ResolvedResourceContent,
        code: RenGErrorCode,
        stage: PipelineStage,
    ): FailureDescriptor = FailureDescriptor(
        code = code,
        stage = stage,
        diagnostic = failureContextDiagnostic(
            stage = stage,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = content.route.resourceClass,
            resourceKey = content.resourceKey,
        ),
    )

    private fun transportFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
        stage = PipelineStage.TRANSPORT,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.TRANSPORT,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun isFresh(stored: StoredRawResource, sampleEpochMillis: Long): Boolean =
        stored.metadata.freshUntilEpochMillis?.let { it > sampleEpochMillis } == true

    private fun cursorActionId(cursor: ResourceRouteCursor?): ResourceActionId? = when (cursor) {
        null,
        is PendingClassGates,
        is PendingChildDiscovery,
        -> null
        is AwaitingClockSample -> cursor.actionId
        is AwaitingResident -> cursor.actionId
        is AwaitingStoreRead -> cursor.actionId
        is AwaitingTransport -> cursor.actionId
        is AwaitingLatchedTransportReplay -> cursor.actionId
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

    private fun copyStored(stored: StoredRawResource): StoredRawResource = StoredRawResource(
        bytes = stored.byteSnapshot,
        contentDigest = stored.contentDigest,
        metadata = StoredRawResourceMetadata(
            contentType = stored.metadata.contentType,
            etag = stored.metadata.etag,
            lastModified = stored.metadata.lastModified,
            freshUntilEpochMillis = stored.metadata.freshUntilEpochMillis,
            storedAtEpochMillis = stored.metadata.storedAtEpochMillis,
        ),
    )

    private fun copyResponse(response: TransportResponse): TransportResponse = TransportResponse(
        statusCode = response.statusCode,
        body = response.body,
        metadata = TransportResponseMetadata(
            contentType = response.metadata.contentType,
            etag = response.metadata.etag,
            lastModified = response.metadata.lastModified,
            freshUntilEpochMillis = response.metadata.freshUntilEpochMillis,
        ),
    )

    private fun copyLatch(latch: TransportLatchRecord): TransportLatchRecord = TransportLatchRecord(
        key = latch.key,
        outcome = when (val outcome = latch.outcome) {
            LatchedTransportOutcome.Failed -> LatchedTransportOutcome.Failed
            is LatchedTransportOutcome.Cancelled -> LatchedTransportOutcome.Cancelled(outcome.cancellation)
            is LatchedTransportOutcome.Response -> LatchedTransportOutcome.Response(outcome.response)
        },
    )
}
