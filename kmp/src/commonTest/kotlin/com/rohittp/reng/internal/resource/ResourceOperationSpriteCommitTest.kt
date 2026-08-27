package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationSpriteCommitTest {
    @Test
    fun bothCandidatesAreRequiredBeforeAnyJointValidationWriteOrVisibility() {
        val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        val json = driver.candidate(JSON_ORDINAL)

        driver.advanceSpriteCommit(JSON_ORDINAL)

        assertTrue(driver.actions.isEmpty())
        val staged = driver.group()
        assertEquals(json, staged.jsonCandidate)
        assertNull(staged.imageCandidate)
        assertEquals(SpriteJointValidationStatus.WAITING, staged.jointValidationStatus)
        assertTrue(staged.acknowledgedWrites.isEmpty())
        assertFalse(staged.visible)
        assertEquals(listOf(ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))), driver.parked)
        assertEquals(listOf(IMAGE_ORDINAL), driver.state.activeRouteOrdinals)
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(JSON_ORDINAL).status)
        assertFalse(driver.record(JSON_ORDINAL).visibilityInstalled)
        assertNull(driver.outcome)
        assertTrue(driver.state.bufferedRouteOutcomes.isEmpty())
        assertEquals(0L, driver.state.nextRetirementOrdinal)
        driver.assertNoSpriteCommitWork("json only")
        driver.assertNoRecoveryActions("json only")

        val parkedState = driver.state
        assertEquals(
            "route completion requires an active route",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(
                    parkedState,
                    RouteCompleted(JSON_ORDINAL, ResourceRouteOutcome.Success),
                )
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                parkedState,
                AdvancePendingSpriteCommit(JSON_ORDINAL),
            )
        }

        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        assertTrue(driver.emitted.filterIsInstance<ValidateSpritePair>().isEmpty())

        driver.advanceSpriteCommit(IMAGE_ORDINAL)
        assertIs<ValidateSpritePair>(driver.actions.single())
    }

    @Test
    fun advancementRequiresAnActiveSpriteBoundRouteAndNeverTheOrdinaryClassGateEvent() {
        val ordinaryDriver = SpriteDriver(
            definitionOf(
                concurrency = 1,
                occurrences = ordinaryOccurrence(9L, 9L, '9'),
            ),
        )
        ordinaryDriver.driveToPendingCandidate(0L, ContentProvenance.TRANSPORT_200)
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                ordinaryDriver.state,
                AdvancePendingSpriteCommit(0L),
            )
        }

        val spriteDriver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        spriteDriver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                spriteDriver.state,
                AdvancePendingClassGates(JSON_ORDINAL),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                spriteDriver.state,
                AdvancePendingSpriteCommit(7L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                spriteDriver.state,
                AdvancePendingSpriteCommit(IMAGE_ORDINAL),
            )
        }
        assertIs<PendingClassGates>(spriteDriver.record(JSON_ORDINAL).cursor)
    }

    @Test
    fun everyProvenanceCombinationWritesOnlyTransportedMembersInJsonThenImageOrder() {
        ContentProvenance.entries.forEach { jsonProvenance ->
            ContentProvenance.entries.forEach { imageProvenance ->
                val label = "$jsonProvenance/$imageProvenance"
                val driver = SpriteDriver(
                    spriteGroupDefinition(
                        concurrency = 2,
                        jsonProvenance = jsonProvenance,
                        imageProvenance = imageProvenance,
                    ),
                )
                driver.driveToPendingCandidate(JSON_ORDINAL, jsonProvenance)
                driver.advanceSpriteCommit(JSON_ORDINAL)
                driver.driveToPendingCandidate(IMAGE_ORDINAL, imageProvenance)
                val json = driver.candidate(JSON_ORDINAL)
                val image = driver.candidate(IMAGE_ORDINAL)

                driver.advanceSpriteCommit(IMAGE_ORDINAL)

                val validation = assertIs<ValidateSpritePair>(driver.actions.single(), label)
                assertEquals(GROUP_ONE, validation.groupId, label)
                assertEquals(json, validation.json, label)
                assertEquals(image, validation.image, label)
                assertEquals(
                    AwaitingSpritePairValidation(
                        validation.actionId,
                        GROUP_ONE,
                        JSON_ORDINAL,
                        IMAGE_ORDINAL,
                        json,
                        image,
                    ),
                    driver.record(JSON_ORDINAL).cursor,
                    label,
                )
                assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals, label)
                assertEquals(
                    listOf(ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
                    driver.parked,
                    label,
                )
                assertEquals(SpriteJointValidationStatus.REQUESTED, driver.group().jointValidationStatus, label)

                driver.event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))
                assertEquals(SpriteJointValidationStatus.VALID, driver.group().jointValidationStatus, label)

                val expectedWrites = listOf(SpriteMember.JSON to json, SpriteMember.IMAGE to image)
                    .filter { (_, content) -> requiresStoreWrite(content.provenance) }
                assertEquals(expectedWrites.map { it.first }, driver.group().requiredMemberWrites, label)

                expectedWrites.forEachIndexed { index, (member, content) ->
                    val memberLabel = "$label/$member"
                    val write = assertIs<WriteSpriteMember>(driver.actions.single(), memberLabel)
                    assertEquals(GROUP_ONE, write.groupId, memberLabel)
                    assertEquals(member, write.member, memberLabel)
                    assertEquals(driver.ordinalOf(member), write.ordinal, memberLabel)
                    assertEquals(driver.record(write.ordinal).registration.rawKey, write.rawKey, memberLabel)
                    assertEquals(content.stored, write.resource, memberLabel)
                    assertEquals(
                        AwaitingSpriteMemberWrite(write.actionId, GROUP_ONE, member, write.ordinal, content),
                        driver.record(JSON_ORDINAL).cursor,
                        memberLabel,
                    )
                    assertEquals(
                        expectedWrites.take(index).map { it.first },
                        driver.group().acknowledgedWrites,
                        memberLabel,
                    )
                    assertFalse(driver.group().visible, memberLabel)
                    assertTrue(
                        driver.emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty(),
                        memberLabel,
                    )
                    driver.event(
                        SpriteMemberWriteCompleted(
                            write.actionId,
                            GROUP_ONE,
                            member,
                            SuppliedCallOutcome.Success(Unit),
                        ),
                    )
                }

                val install = assertIs<InstallSpriteVisibility>(driver.actions.single(), label)
                assertEquals(GROUP_ONE, install.groupId, label)
                assertEquals(json, install.json, label)
                assertEquals(image, install.image, label)
                assertEquals(
                    AwaitingSpriteVisibilityInstall(install.actionId, GROUP_ONE, json, image),
                    driver.record(JSON_ORDINAL).cursor,
                    label,
                )
                assertEquals(expectedWrites.map { it.first }, driver.group().acknowledgedWrites, label)
                assertFalse(driver.group().visible, label)
                assertNull(driver.outcome, label)

                driver.event(
                    SpriteVisibilityInstallCompleted(install.actionId, GROUP_ONE, SuppliedInstallOutcome.Succeeded),
                )

                assertTrue(driver.group().visible, label)
                assertTrue(driver.parked.isEmpty(), label)
                listOf(JSON_ORDINAL, IMAGE_ORDINAL).forEach { ordinal ->
                    assertTrue(driver.record(ordinal).visibilityInstalled, "$label/$ordinal")
                    assertNull(driver.record(ordinal).cursor, "$label/$ordinal")
                    assertEquals(ResourceRouteStatus.RESOLVED, driver.record(ordinal).status, "$label/$ordinal")
                }
                assertEquals(2L, driver.state.nextRetirementOrdinal, label)
                assertEquals(
                    ResourceOperationOutcome.Success(
                        listOf(
                            OwnerResourceSet(
                                ResourceOwnerId(FIRST_OWNER_ID),
                                listOf(
                                    VisibleResource(json.resourceKey, json),
                                    VisibleResource(image.resourceKey, image),
                                ),
                            ),
                        ),
                    ),
                    driver.outcome,
                    label,
                )
                assertEquals(expectedWrites.size, driver.emitted.filterIsInstance<WriteSpriteMember>().size, label)
                assertEquals(1, driver.emitted.filterIsInstance<ValidateSpritePair>().size, label)
                assertEquals(1, driver.emitted.filterIsInstance<InstallSpriteVisibility>().size, label)
                assertTrue(driver.emitted.filterIsInstance<WriteStore>().isEmpty(), label)
                assertTrue(driver.emitted.filterIsInstance<InstallVisibility>().isEmpty(), label)
                assertTrue(driver.emitted.filterIsInstance<ValidateResourceClass>().isEmpty(), label)
                driver.assertNoRecoveryActions(label)
            }
        }
    }

    @Test
    fun reversedMemberCompletionKeepsOneOwnerAndTheSameJsonBeforeImageWriteOrder() {
        val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)

        driver.advanceSpriteCommit(IMAGE_ORDINAL)
        assertTrue(driver.actions.isEmpty())
        assertEquals(
            listOf(ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
            driver.parked,
        )
        assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals)
        driver.assertNoSpriteCommitWork("image only")

        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)

        val validation = assertIs<ValidateSpritePair>(driver.actions.single())
        assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals)
        assertEquals(
            listOf(ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
            driver.parked,
        )
        driver.event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))

        val firstWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(SpriteMember.JSON, firstWrite.member)
        assertEquals(JSON_ORDINAL, firstWrite.ordinal)
        driver.event(
            SpriteMemberWriteCompleted(
                firstWrite.actionId,
                GROUP_ONE,
                SpriteMember.JSON,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val secondWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(SpriteMember.IMAGE, secondWrite.member)
        assertEquals(IMAGE_ORDINAL, secondWrite.ordinal)
        driver.event(
            SpriteMemberWriteCompleted(
                secondWrite.actionId,
                GROUP_ONE,
                SpriteMember.IMAGE,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val install = assertIs<InstallSpriteVisibility>(driver.actions.single())
        driver.event(
            SpriteVisibilityInstallCompleted(install.actionId, GROUP_ONE, SuppliedInstallOutcome.Succeeded),
        )
        assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        assertEquals(
            listOf(SpriteMember.JSON, SpriteMember.IMAGE),
            driver.emitted.filterIsInstance<WriteSpriteMember>().map(WriteSpriteMember::member),
        )
    }

    @Test
    fun anImageWriteFailureAfterTheJsonAcknowledgementKeepsTheOrphanAndInstallsNoAtlas() {
        val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        val json = driver.driveValidatedPair()
        val image = driver.candidate(IMAGE_ORDINAL)

        val jsonWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(SpriteMember.JSON, jsonWrite.member)
        driver.event(
            SpriteMemberWriteCompleted(
                jsonWrite.actionId,
                GROUP_ONE,
                SpriteMember.JSON,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val imageWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(SpriteMember.IMAGE, imageWrite.member)

        driver.event(
            SpriteMemberWriteCompleted(
                imageWrite.actionId,
                GROUP_ONE,
                SpriteMember.IMAGE,
                SuppliedCallOutcome.Failed,
            ),
        )

        assertResourceFailure(
            outcome = driver.outcome,
            code = RenGErrorCode.STORE_WRITE_FAILED,
            stage = PipelineStage.STORE_WRITE,
            expectedField = null,
            resourceClass = ResourceClass.BASEMAP_SPRITE_IMAGE,
            resourceKey = image.resourceKey,
            label = "image write failure",
        )
        assertEquals(
            ResourceTerminalSelection.Route(
                IMAGE_ORDINAL,
                ResourceRouteOutcome.Failure(assertIs<ResourceOperationOutcome.Failure>(driver.outcome).failure),
            ),
            driver.state.terminalSelection,
        )
        assertEquals(listOf(SpriteMember.JSON), driver.group().acknowledgedWrites)
        assertFalse(driver.group().visible)
        assertEquals(json, driver.group().jsonCandidate)
        listOf(JSON_ORDINAL, IMAGE_ORDINAL).forEach { ordinal ->
            assertFalse(driver.record(ordinal).visibilityInstalled, "$ordinal")
            assertNull(driver.record(ordinal).cursor, "$ordinal")
            assertEquals(ResourceRouteStatus.RESOLVED, driver.record(ordinal).status, "$ordinal")
        }
        assertTrue(driver.parked.isEmpty())
        assertEquals(
            listOf(SpriteMember.JSON),
            driver.emitted.filterIsInstance<WriteSpriteMember>().map(WriteSpriteMember::member).distinct()
                .take(1),
        )
        assertEquals(2, driver.emitted.filterIsInstance<WriteSpriteMember>().size)
        assertTrue(driver.emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        driver.assertNoRecoveryActions("image write failure")
    }

    @Test
    fun aJsonWriteFailureStopsBeforeAnyImageWriteOrInstall() {
        val driver = SpriteDriver(
            spriteGroupDefinition(
                concurrency = 2,
                jsonProvenance = ContentProvenance.TRANSPORT_200,
                imageProvenance = ContentProvenance.TRANSPORT_200,
            ),
        )
        val json = driver.driveValidatedPair()
        val jsonWrite = assertIs<WriteSpriteMember>(driver.actions.single())

        driver.event(
            SpriteMemberWriteCompleted(
                jsonWrite.actionId,
                GROUP_ONE,
                SpriteMember.JSON,
                SuppliedCallOutcome.Failed,
            ),
        )

        assertResourceFailure(
            outcome = driver.outcome,
            code = RenGErrorCode.STORE_WRITE_FAILED,
            stage = PipelineStage.STORE_WRITE,
            expectedField = null,
            resourceClass = ResourceClass.BASEMAP_SPRITE_JSON,
            resourceKey = json.resourceKey,
            label = "json write failure",
        )
        assertEquals(1, driver.emitted.filterIsInstance<WriteSpriteMember>().size)
        assertTrue(driver.group().acknowledgedWrites.isEmpty())
        assertFalse(driver.group().visible)
        assertTrue(driver.parked.isEmpty())
        assertTrue(driver.emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        driver.assertNoRecoveryActions("json write failure")
    }

    @Test
    fun pairFailureKindsMapAtTheReportedMembersRouteOrdinal() {
        val expectations = listOf(
            Triple(SpriteMember.JSON, SpritePairFailureKind.JSON_PARSE, RenGErrorCode.RESOURCE_PARSE_FAILED),
            Triple(SpriteMember.IMAGE, SpritePairFailureKind.IMAGE_DECODE, RenGErrorCode.RESOURCE_DECODE_FAILED),
            Triple(
                SpriteMember.JSON,
                SpritePairFailureKind.UNSUPPORTED_FEATURE,
                RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
            ),
            Triple(
                SpriteMember.IMAGE,
                SpritePairFailureKind.UNSUPPORTED_FEATURE,
                RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
            ),
        )
        // The same hand-written-table-over-an-enum guard X2 closed in `ResourcesTest` and
        // `CanonicalBinaryTest`: this table is the only place a pair failure kind's reported error
        // code is asserted, so a fourth kind added without a row here would map to anything at all
        // and nothing would disagree. Both dimensions are pinned, since the member is half the claim.
        assertEquals(
            SpritePairFailureKind.entries.toSet(),
            expectations.map { it.second }.toSet(),
            "a pair failure kind absent from this table has its error code asserted nowhere",
        )
        assertEquals(
            SpriteMember.entries.toSet(),
            expectations.map { it.first }.toSet(),
            "a sprite member absent from this table is never the reported one",
        )
        expectations.forEach { (member, kind, code) ->
            val label = "$member/$kind"
            val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
            driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
            driver.advanceSpriteCommit(JSON_ORDINAL)
            driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
            driver.advanceSpriteCommit(IMAGE_ORDINAL)
            val validation = assertIs<ValidateSpritePair>(driver.actions.single(), label)
            val reported = driver.candidate(driver.ordinalOf(member))

            driver.event(
                SpritePairValidationCompleted(
                    validation.actionId,
                    SpritePairValidationOutcome.Failed(member, kind),
                ),
            )

            assertResourceFailure(
                outcome = driver.outcome,
                code = code,
                stage = if (kind == SpritePairFailureKind.IMAGE_DECODE) {
                    PipelineStage.RESOURCE_DECODING
                } else {
                    PipelineStage.RESOURCE_PARSING
                },
                expectedField = DiagnosticField.RESOURCE.wireName,
                resourceClass = spriteMemberResourceClass(member),
                resourceKey = reported.resourceKey,
                label = label,
            )
            assertEquals(
                ResourceTerminalSelection.Route(
                    driver.ordinalOf(member),
                    ResourceRouteOutcome.Failure(
                        assertIs<ResourceOperationOutcome.Failure>(driver.outcome).failure,
                    ),
                ),
                driver.state.terminalSelection,
                label,
            )
            assertEquals(SpriteJointValidationStatus.FAILED, driver.group().jointValidationStatus, label)
            assertFalse(driver.group().visible, label)
            assertTrue(driver.parked.isEmpty(), label)
            assertTrue(driver.emitted.filterIsInstance<WriteSpriteMember>().isEmpty(), label)
            assertTrue(driver.emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty(), label)
            assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty(), label)
            driver.assertNoRecoveryActions(label)
        }
    }

    @Test
    fun aStoreSourcedReportedMemberOverridesEveryPairFailureKind() {
        listOf(
            SpriteMember.JSON to SpritePairFailureKind.JSON_PARSE,
            SpriteMember.JSON to SpritePairFailureKind.UNSUPPORTED_FEATURE,
            SpriteMember.IMAGE to SpritePairFailureKind.IMAGE_DECODE,
            SpriteMember.IMAGE to SpritePairFailureKind.UNSUPPORTED_FEATURE,
        ).forEach { (member, kind) ->
            val label = "$member/$kind"
            val driver = SpriteDriver(
                spriteGroupDefinition(
                    concurrency = 2,
                    jsonProvenance = if (member == SpriteMember.JSON) {
                        ContentProvenance.STORE
                    } else {
                        ContentProvenance.TRANSPORT_200
                    },
                    imageProvenance = if (member == SpriteMember.IMAGE) {
                        ContentProvenance.STORE
                    } else {
                        ContentProvenance.RESIDENT
                    },
                ),
            )
            val jsonProvenance = if (member == SpriteMember.JSON) {
                ContentProvenance.STORE
            } else {
                ContentProvenance.TRANSPORT_200
            }
            val imageProvenance = if (member == SpriteMember.IMAGE) {
                ContentProvenance.STORE
            } else {
                ContentProvenance.RESIDENT
            }
            driver.driveToPendingCandidate(JSON_ORDINAL, jsonProvenance)
            driver.advanceSpriteCommit(JSON_ORDINAL)
            driver.driveToPendingCandidate(IMAGE_ORDINAL, imageProvenance)
            driver.advanceSpriteCommit(IMAGE_ORDINAL)
            val validation = assertIs<ValidateSpritePair>(driver.actions.single(), label)
            val reported = driver.candidate(driver.ordinalOf(member))
            assertEquals(ContentProvenance.STORE, reported.provenance, label)

            driver.event(
                SpritePairValidationCompleted(
                    validation.actionId,
                    SpritePairValidationOutcome.Failed(member, kind),
                ),
            )

            assertResourceFailure(
                outcome = driver.outcome,
                code = RenGErrorCode.STORE_INTEGRITY_FAILED,
                stage = PipelineStage.STORE_VALIDATION,
                expectedField = DiagnosticField.RESOURCE.wireName,
                resourceClass = spriteMemberResourceClass(member),
                resourceKey = reported.resourceKey,
                label = label,
            )
            assertTrue(driver.emitted.filterIsInstance<WriteSpriteMember>().isEmpty(), label)
            assertTrue(driver.emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty(), label)
            assertFalse(driver.group().visible, label)
            driver.assertNoRecoveryActions(label)
        }
    }

    @Test
    fun jointConsistencyFailuresAttributeToTheFirstTraversalJsonMember() {
        val transportDriver = SpriteDriver(
            spriteGroupDefinition(
                concurrency = 2,
                jsonProvenance = ContentProvenance.TRANSPORT_304_MERGED,
                imageProvenance = ContentProvenance.STORE,
            ),
        )
        transportDriver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_304_MERGED)
        transportDriver.advanceSpriteCommit(JSON_ORDINAL)
        transportDriver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.STORE)
        transportDriver.advanceSpriteCommit(IMAGE_ORDINAL)
        val transportValidation = assertIs<ValidateSpritePair>(transportDriver.actions.single())
        val transportJson = transportDriver.candidate(JSON_ORDINAL)

        transportDriver.event(
            SpritePairValidationCompleted(
                transportValidation.actionId,
                SpritePairValidationOutcome.Failed(SpriteMember.JSON, SpritePairFailureKind.JSON_PARSE),
            ),
        )

        assertResourceFailure(
            outcome = transportDriver.outcome,
            code = RenGErrorCode.RESOURCE_PARSE_FAILED,
            stage = PipelineStage.RESOURCE_PARSING,
            expectedField = DiagnosticField.RESOURCE.wireName,
            resourceClass = ResourceClass.BASEMAP_SPRITE_JSON,
            resourceKey = transportJson.resourceKey,
            label = "atlas bounds",
        )
        assertEquals(
            ResourceTerminalSelection.Route(
                JSON_ORDINAL,
                ResourceRouteOutcome.Failure(
                    assertIs<ResourceOperationOutcome.Failure>(transportDriver.outcome).failure,
                ),
            ),
            transportDriver.state.terminalSelection,
        )
        assertTrue(transportDriver.emitted.filterIsInstance<WriteSpriteMember>().isEmpty())

        val storeDriver = SpriteDriver(
            spriteGroupDefinition(
                concurrency = 2,
                jsonProvenance = ContentProvenance.STORE,
                imageProvenance = ContentProvenance.TRANSPORT_200,
            ),
        )
        storeDriver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.STORE)
        storeDriver.advanceSpriteCommit(JSON_ORDINAL)
        storeDriver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        storeDriver.advanceSpriteCommit(IMAGE_ORDINAL)
        val storeValidation = assertIs<ValidateSpritePair>(storeDriver.actions.single())
        val storeJson = storeDriver.candidate(JSON_ORDINAL)

        storeDriver.event(
            SpritePairValidationCompleted(
                storeValidation.actionId,
                SpritePairValidationOutcome.Failed(SpriteMember.JSON, SpritePairFailureKind.JSON_PARSE),
            ),
        )

        assertResourceFailure(
            outcome = storeDriver.outcome,
            code = RenGErrorCode.STORE_INTEGRITY_FAILED,
            stage = PipelineStage.STORE_VALIDATION,
            expectedField = DiagnosticField.RESOURCE.wireName,
            resourceClass = ResourceClass.BASEMAP_SPRITE_JSON,
            resourceKey = storeJson.resourceKey,
            label = "store atlas bounds",
        )
        assertTrue(storeDriver.emitted.filterIsInstance<WriteSpriteMember>().isEmpty())
        assertFalse(storeDriver.group().visible)

        listOf(
            SpriteMember.IMAGE to SpritePairFailureKind.JSON_PARSE,
            SpriteMember.JSON to SpritePairFailureKind.IMAGE_DECODE,
        ).forEach { (member, kind) ->
            assertFailsWith<IllegalArgumentException>("$member/$kind") {
                SpritePairValidationOutcome.Failed(member, kind)
            }
        }
    }

    @Test
    fun spriteValidationWriteAndInstallCancellationsStayOpaqueAdapterCancellations() {
        val cancellation = CancellationSelection(CancellationCause.ADAPTER, CancellationId(73L))

        val validationDriver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        validationDriver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        validationDriver.advanceSpriteCommit(JSON_ORDINAL)
        validationDriver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        validationDriver.advanceSpriteCommit(IMAGE_ORDINAL)
        val validation = assertIs<ValidateSpritePair>(validationDriver.actions.single())
        validationDriver.event(
            SpritePairValidationCompleted(
                validation.actionId,
                SpritePairValidationOutcome.Cancelled(cancellation),
            ),
        )
        assertEquals(ResourceOperationOutcome.Cancelled(cancellation), validationDriver.outcome)
        assertFalse(validationDriver.group().visible)
        assertTrue(validationDriver.parked.isEmpty())
        assertTrue(validationDriver.emitted.filterIsInstance<CancelRoute>().isEmpty())

        val writeDriver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        writeDriver.driveValidatedPair()
        val write = assertIs<WriteSpriteMember>(writeDriver.actions.single())
        writeDriver.event(
            SpriteMemberWriteCompleted(
                write.actionId,
                GROUP_ONE,
                SpriteMember.JSON,
                SuppliedCallOutcome.Cancelled(cancellation),
            ),
        )
        assertEquals(ResourceOperationOutcome.Cancelled(cancellation), writeDriver.outcome)
        assertTrue(writeDriver.emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty())

        val installDriver = SpriteDriver(
            spriteGroupDefinition(
                concurrency = 2,
                jsonProvenance = ContentProvenance.RESIDENT,
                imageProvenance = ContentProvenance.RESIDENT,
            ),
        )
        installDriver.driveValidatedPair(
            jsonProvenance = ContentProvenance.RESIDENT,
            imageProvenance = ContentProvenance.RESIDENT,
        )
        val install = assertIs<InstallSpriteVisibility>(installDriver.actions.single())
        installDriver.event(
            SpriteVisibilityInstallCompleted(
                install.actionId,
                GROUP_ONE,
                SuppliedInstallOutcome.Cancelled(cancellation),
            ),
        )
        assertEquals(ResourceOperationOutcome.Cancelled(cancellation), installDriver.outcome)
        assertFalse(installDriver.group().visible)
        assertFalse(installDriver.record(JSON_ORDINAL).visibilityInstalled)
        assertFalse(installDriver.record(IMAGE_ORDINAL).visibilityInstalled)

        listOf(CancellationCause.CALLER, CancellationCause.CANCEL_PREPARATIONS).forEach { cause ->
            assertFailsWith<IllegalArgumentException> {
                SpritePairValidationOutcome.Cancelled(CancellationSelection(cause, CancellationId(4L)))
            }
        }
    }

    @Test
    fun aFailedSpriteInstallPropagatesItsSanitizedFailureWithoutVisibility() {
        val installFailure = FailureDescriptor(
            code = RenGErrorCode.GPU_OPERATION_FAILED,
            stage = PipelineStage.GPU_RESOURCE,
            diagnostic = failureContextDiagnostic(stage = PipelineStage.GPU_RESOURCE),
        )
        val driver = SpriteDriver(
            spriteGroupDefinition(
                concurrency = 2,
                jsonProvenance = ContentProvenance.STORE,
                imageProvenance = ContentProvenance.RESIDENT,
            ),
        )
        driver.driveValidatedPair(
            jsonProvenance = ContentProvenance.STORE,
            imageProvenance = ContentProvenance.RESIDENT,
        )
        val install = assertIs<InstallSpriteVisibility>(driver.actions.single())
        assertTrue(driver.emitted.filterIsInstance<WriteSpriteMember>().isEmpty())

        driver.event(
            SpriteVisibilityInstallCompleted(
                install.actionId,
                GROUP_ONE,
                SuppliedInstallOutcome.Failed(installFailure),
            ),
        )

        assertEquals(installFailure, assertIs<ResourceOperationOutcome.Failure>(driver.outcome).failure)
        assertEquals(
            ResourceTerminalSelection.Route(JSON_ORDINAL, ResourceRouteOutcome.Failure(installFailure)),
            driver.state.terminalSelection,
        )
        assertFalse(driver.group().visible)
        assertFalse(driver.record(JSON_ORDINAL).visibilityInstalled)
        assertFalse(driver.record(IMAGE_ORDINAL).visibilityInstalled)
        assertTrue(driver.parked.isEmpty())
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        driver.assertNoRecoveryActions("install failure")
    }

    @Test
    fun parkedCandidatesReleaseCapacityAndTheReadyPairResumesBeforeAnyNewRoute() {
        val driver = SpriteDriver(
            definitionOf(
                concurrency = 1,
                occurrences = spriteOccurrences(
                    groupId = GROUP_ONE,
                    ownerId = FIRST_OWNER_ID,
                    firstOccurrenceId = 1L,
                    jsonMarker = 'a',
                    imageMarker = 'b',
                    jsonProvenance = ContentProvenance.TRANSPORT_200,
                    imageProvenance = ContentProvenance.TRANSPORT_200,
                ) + ordinaryOccurrence(3L, SECOND_OWNER_ID, 'c'),
            ),
        )
        assertEquals(listOf(StartRoute(JSON_ORDINAL, driver.record(JSON_ORDINAL).registration)), driver.actions)
        assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals)

        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)

        assertEquals(listOf(StartRoute(IMAGE_ORDINAL, driver.record(IMAGE_ORDINAL).registration)), driver.actions)
        assertEquals(listOf(IMAGE_ORDINAL), driver.state.activeRouteOrdinals)
        assertEquals(listOf(ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))), driver.parked)
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(JSON_ORDINAL).status)
        assertEquals(0L, driver.state.nextRetirementOrdinal)
        assertEquals(ResourceRouteStatus.ELIGIBLE, driver.record(2L).status)

        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(IMAGE_ORDINAL)

        val validation = assertIs<ValidateSpritePair>(driver.actions.single())
        assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals)
        assertEquals(listOf(ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))), driver.parked)
        assertEquals(ResourceRouteStatus.ELIGIBLE, driver.record(2L).status)
        assertTrue(driver.emitted.filterIsInstance<StartRoute>().none { it.ordinal == 2L })

        driver.event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))
        val jsonWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals)
        driver.event(
            SpriteMemberWriteCompleted(
                jsonWrite.actionId,
                GROUP_ONE,
                SpriteMember.JSON,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val imageWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals)
        driver.event(
            SpriteMemberWriteCompleted(
                imageWrite.actionId,
                GROUP_ONE,
                SpriteMember.IMAGE,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val install = assertIs<InstallSpriteVisibility>(driver.actions.single())
        assertEquals(listOf(JSON_ORDINAL), driver.state.activeRouteOrdinals)
        assertEquals(0L, driver.state.nextRetirementOrdinal)

        driver.event(
            SpriteVisibilityInstallCompleted(install.actionId, GROUP_ONE, SuppliedInstallOutcome.Succeeded),
        )

        assertEquals(2L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.state.bufferedRouteOutcomes.isEmpty())
        assertTrue(driver.parked.isEmpty())
        assertEquals(listOf(StartRoute(2L, driver.record(2L).registration)), driver.actions)
        assertEquals(listOf(2L), driver.state.activeRouteOrdinals)
        assertNull(driver.outcome)

        driver.driveToPendingCandidate(2L, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingClassGates(2L))
        val ordinaryGate = assertIs<ValidateResourceClass>(driver.actions.single())
        assertEquals(ResourceClassGate.DECODE_PNG, ordinaryGate.gate)
        driver.event(ResourceClassValidationCompleted(ordinaryGate.actionId, SuppliedValidationOutcome.Valid))
        val ordinaryWrite = assertIs<WriteStore>(driver.actions.single())
        driver.event(StoreWriteCompleted(ordinaryWrite.actionId, SuppliedCallOutcome.Success(Unit)))
        val ordinaryInstall = assertIs<InstallVisibility>(driver.actions.single())
        driver.event(
            VisibilityInstallCompleted(ordinaryInstall.actionId, SuppliedInstallOutcome.Succeeded),
        )

        assertEquals(3L, driver.state.nextRetirementOrdinal)
        assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        driver.assertNoRecoveryActions("bounded capacity")
    }

    @Test
    fun readyParkedOrdinalsResumeLowestReadyFirstAndStayUnretiredUntilOrdinalOrderAllows() {
        val driver = SpriteDriver(
            definitionOf(
                concurrency = 1,
                occurrences = listOf(
                    spriteOccurrence(1L, FIRST_OWNER_ID, GROUP_ONE, SpriteMember.JSON, 'a'),
                    spriteOccurrence(2L, SECOND_OWNER_ID, GROUP_TWO, SpriteMember.JSON, 'c'),
                    spriteOccurrence(3L, SECOND_OWNER_ID, GROUP_TWO, SpriteMember.IMAGE, 'd'),
                    spriteOccurrence(4L, FIRST_OWNER_ID, GROUP_ONE, SpriteMember.IMAGE, 'b'),
                ),
            ),
        )

        driver.driveToPendingCandidate(0L, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(0L)
        assertEquals(listOf(1L), driver.state.activeRouteOrdinals)

        driver.driveToPendingCandidate(1L, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(1L)
        assertEquals(listOf(2L), driver.state.activeRouteOrdinals)
        assertEquals(
            listOf(
                ParkedRoute(0L, ParkedRouteBarrier.SpritePair(GROUP_ONE)),
                ParkedRoute(1L, ParkedRouteBarrier.SpritePair(GROUP_TWO)),
            ),
            driver.parked,
        )

        driver.driveToPendingCandidate(2L, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(2L)

        val secondValidation = assertIs<ValidateSpritePair>(driver.actions.single())
        assertEquals(GROUP_TWO, secondValidation.groupId)
        assertEquals(listOf(1L), driver.state.activeRouteOrdinals)
        assertEquals(
            listOf(
                ParkedRoute(0L, ParkedRouteBarrier.SpritePair(GROUP_ONE)),
                ParkedRoute(2L, ParkedRouteBarrier.SpritePair(GROUP_TWO)),
            ),
            driver.parked,
        )
        assertEquals(ResourceRouteStatus.ELIGIBLE, driver.record(3L).status)

        driver.completeValidatedPair(secondValidation, GROUP_TWO, 1L, 2L)

        assertEquals(0L, driver.state.nextRetirementOrdinal)
        assertEquals(
            listOf(
                BufferedRouteOutcome(1L, ResourceRouteOutcome.Success),
                BufferedRouteOutcome(2L, ResourceRouteOutcome.Success),
            ),
            driver.state.bufferedRouteOutcomes,
        )
        assertEquals(listOf(ParkedRoute(0L, ParkedRouteBarrier.SpritePair(GROUP_ONE))), driver.parked)
        assertEquals(listOf(StartRoute(3L, driver.record(3L).registration)), driver.actions)
        assertEquals(listOf(3L), driver.state.activeRouteOrdinals)

        driver.driveToPendingCandidate(3L, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(3L)

        val firstValidation = assertIs<ValidateSpritePair>(driver.actions.single())
        assertEquals(GROUP_ONE, firstValidation.groupId)
        assertEquals(listOf(0L), driver.state.activeRouteOrdinals)

        driver.completeValidatedPair(firstValidation, GROUP_ONE, 0L, 3L)

        assertEquals(4L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.state.bufferedRouteOutcomes.isEmpty())
        assertTrue(driver.parked.isEmpty())
        assertEquals(
            ResourceOperationOutcome.Success(
                listOf(
                    OwnerResourceSet(
                        ResourceOwnerId(FIRST_OWNER_ID),
                        listOf(driver.visible(0L), driver.visible(3L)),
                    ),
                    OwnerResourceSet(
                        ResourceOwnerId(SECOND_OWNER_ID),
                        listOf(driver.visible(1L), driver.visible(2L)),
                    ),
                ),
            ),
            driver.outcome,
        )
        driver.assertNoRecoveryActions("lowest ready first")
    }

    @Test
    fun aBufferedFailureClosesLowerParkedSpriteWorkSoOrdinalRetirementReachesIt() {
        val driver = SpriteDriver(
            definitionOf(
                concurrency = 1,
                occurrences = listOf(
                    spriteOccurrence(1L, FIRST_OWNER_ID, GROUP_ONE, SpriteMember.JSON, 'a'),
                    ordinaryOccurrence(2L, SECOND_OWNER_ID, 'c').single(),
                    spriteOccurrence(3L, FIRST_OWNER_ID, GROUP_ONE, SpriteMember.IMAGE, 'b'),
                ),
            ),
        )
        driver.driveToPendingCandidate(0L, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(0L)
        assertEquals(listOf(ParkedRoute(0L, ParkedRouteBarrier.SpritePair(GROUP_ONE))), driver.parked)
        assertEquals(listOf(1L), driver.state.activeRouteOrdinals)
        assertEquals(ResourceRouteStatus.ELIGIBLE, driver.record(2L).status)
        val blockingFailure = failure(RenGErrorCode.TRANSPORT_EXECUTION_FAILED)

        driver.event(RouteCompleted(1L, ResourceRouteOutcome.Failure(blockingFailure)))

        assertEquals(ResourceOperationOutcome.Failure(blockingFailure), driver.outcome)
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Failure(blockingFailure)),
            driver.state.terminalSelection,
        )
        assertEquals(2L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.parked.isEmpty())
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(0L).status)
        assertNull(driver.record(0L).cursor)
        assertFalse(driver.record(0L).visibilityInstalled)
        assertEquals(ResourceRouteStatus.ELIGIBLE, driver.record(2L).status)
        assertTrue(driver.state.activeRouteOrdinals.isEmpty())
        assertFalse(driver.group().visible)
        assertEquals(SpriteJointValidationStatus.WAITING, driver.group().jointValidationStatus)
        driver.assertNoSpriteCommitWork("closed parked lower ordinal")
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<StartRoute>().none { it.ordinal == 2L })
        driver.assertNoRecoveryActions("closed parked lower ordinal")
    }

    @Test
    fun aBufferedFailureLeavesAParkedMemberWhoseGroupOwnerStillHasWorkInFlight() {
        val driver = SpriteDriver(
            definitionOf(
                concurrency = 2,
                occurrences = spriteOccurrences(
                    groupId = GROUP_ONE,
                    ownerId = FIRST_OWNER_ID,
                    firstOccurrenceId = 1L,
                    jsonMarker = 'a',
                    imageMarker = 'b',
                    jsonProvenance = ContentProvenance.TRANSPORT_200,
                    imageProvenance = ContentProvenance.TRANSPORT_200,
                ) + ordinaryOccurrence(3L, SECOND_OWNER_ID, 'c'),
            ),
        )
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(IMAGE_ORDINAL)
        val validation = assertIs<ValidateSpritePair>(driver.actions.single())
        assertEquals(listOf(ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))), driver.parked)
        assertEquals(listOf(2L, JSON_ORDINAL), driver.state.activeRouteOrdinals)
        val blockingFailure = failure(RenGErrorCode.TRANSPORT_EXECUTION_FAILED)

        driver.event(RouteCompleted(2L, ResourceRouteOutcome.Failure(blockingFailure)))

        assertEquals(2L, driver.state.startCeilingOrdinal)
        assertEquals(
            listOf(ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
            driver.parked,
        )
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(IMAGE_ORDINAL).status)
        assertEquals(0L, driver.state.nextRetirementOrdinal)
        assertNull(driver.state.terminalSelection)
        assertNull(driver.outcome)
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())

        driver.event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))
        val jsonWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(SpriteMember.JSON, jsonWrite.member)
        driver.event(
            SpriteMemberWriteCompleted(
                jsonWrite.actionId,
                GROUP_ONE,
                SpriteMember.JSON,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val imageWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        assertEquals(SpriteMember.IMAGE, imageWrite.member)
        driver.event(
            SpriteMemberWriteCompleted(
                imageWrite.actionId,
                GROUP_ONE,
                SpriteMember.IMAGE,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val install = assertIs<InstallSpriteVisibility>(driver.actions.single())

        driver.event(SpriteVisibilityInstallCompleted(install.actionId, GROUP_ONE, SuppliedInstallOutcome.Succeeded))

        assertEquals(ResourceOperationOutcome.Failure(blockingFailure), driver.outcome)
        assertEquals(
            ResourceTerminalSelection.Route(2L, ResourceRouteOutcome.Failure(blockingFailure)),
            driver.state.terminalSelection,
        )
        assertEquals(3L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.parked.isEmpty())
        assertTrue(driver.state.activeRouteOrdinals.isEmpty())
        assertTrue(driver.group().visible)
        assertTrue(driver.record(JSON_ORDINAL).visibilityInstalled)
        assertTrue(driver.record(IMAGE_ORDINAL).visibilityInstalled)
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(JSON_ORDINAL).status)
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(IMAGE_ORDINAL).status)
        assertEquals(1, driver.emitted.filterIsInstance<InstallSpriteVisibility>().size)
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        driver.assertNoRecoveryActions("parked member under in-flight group work")
    }

    @Test
    fun aBufferedFailureAlsoClosesAnActiveLowerMemberThatWouldOtherwisePark() {
        val driver = SpriteDriver(
            definitionOf(
                concurrency = 2,
                occurrences = spriteOccurrences(
                    groupId = GROUP_ONE,
                    ownerId = FIRST_OWNER_ID,
                    firstOccurrenceId = 1L,
                    jsonMarker = 'a',
                    imageMarker = 'b',
                    jsonProvenance = ContentProvenance.TRANSPORT_200,
                    imageProvenance = ContentProvenance.TRANSPORT_200,
                ) + ordinaryOccurrence(3L, SECOND_OWNER_ID, 'c'),
            ),
        )
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        assertEquals(listOf(StartRoute(2L, driver.record(2L).registration)), driver.actions)
        val blockingFailure = failure(RenGErrorCode.RESOURCE_UNAVAILABLE)

        driver.event(RouteCompleted(2L, ResourceRouteOutcome.Failure(blockingFailure)))

        assertEquals(2L, driver.state.startCeilingOrdinal)
        assertTrue(driver.parked.isEmpty())
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(JSON_ORDINAL).status)
        assertEquals(1L, driver.state.nextRetirementOrdinal)
        assertNull(driver.outcome)

        driver.advanceSpriteCommit(IMAGE_ORDINAL)

        assertEquals(ResourceOperationOutcome.Failure(blockingFailure), driver.outcome)
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(IMAGE_ORDINAL).status)
        assertNull(driver.record(IMAGE_ORDINAL).cursor)
        assertFalse(driver.record(JSON_ORDINAL).visibilityInstalled)
        assertFalse(driver.record(IMAGE_ORDINAL).visibilityInstalled)
        assertFalse(driver.group().visible)
        driver.assertNoSpriteCommitWork("ceiling closes both members")
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        driver.assertNoRecoveryActions("ceiling closes both members")
    }

    @Test
    fun parkedHigherOrdinalsNeedNoCancellationWhileActiveHigherRoutesStillDo() {
        val routeFailure = failure(RenGErrorCode.STORE_READ_FAILED)
        val routeDriver = spriteWorkUnderLowerOrdinaryRoute()
        val validation = assertIs<ValidateSpritePair>(routeDriver.actions.single())
        assertEquals(listOf(ParkedRoute(2L, ParkedRouteBarrier.SpritePair(GROUP_ONE))), routeDriver.parked)
        assertEquals(listOf(0L, 1L), routeDriver.state.activeRouteOrdinals)

        routeDriver.event(RouteCompleted(0L, ResourceRouteOutcome.Failure(routeFailure)))

        assertEquals(listOf(CancelRoute(1L)), routeDriver.actions)
        assertTrue(routeDriver.parked.isEmpty())
        assertEquals(ResourceRouteStatus.RESOLVED, routeDriver.record(2L).status)
        assertNull(routeDriver.record(2L).cursor)
        assertFalse(routeDriver.record(2L).visibilityInstalled)
        assertEquals(listOf(1L), routeDriver.state.activeRouteOrdinals)
        assertNull(routeDriver.outcome)

        routeDriver.event(
            SpritePairValidationCompleted(
                validation.actionId,
                SpritePairValidationOutcome.Cancelled(
                    CancellationSelection(CancellationCause.ADAPTER, CancellationId(11L)),
                ),
            ),
        )
        assertEquals(ResourceOperationOutcome.Failure(routeFailure), routeDriver.outcome)
        assertTrue(routeDriver.state.activeRouteOrdinals.isEmpty())
        assertEquals(1, routeDriver.emitted.filterIsInstance<CancelRoute>().size)

        val externalDriver = spriteWorkUnderLowerOrdinaryRoute()
        val externalCancellation = CancellationSelection(CancellationCause.CALLER, CancellationId(19L))

        externalDriver.event(ExternalCancellationRequested(externalCancellation))

        assertEquals(listOf(CancelRoute(0L), CancelRoute(1L)), externalDriver.actions)
        assertTrue(externalDriver.parked.isEmpty())
        assertEquals(ResourceRouteStatus.RESOLVED, externalDriver.record(2L).status)
        assertNull(externalDriver.outcome)
        externalDriver.event(CleanupCancellationObserved(0L))
        externalDriver.event(CleanupCancellationObserved(1L))
        assertEquals(ResourceOperationOutcome.Cancelled(externalCancellation), externalDriver.outcome)
        assertEquals(2, externalDriver.emitted.filterIsInstance<CancelRoute>().size)
    }

    @Test
    fun spriteAcknowledgementsMustMatchTheirCompleteBindingCursorAndBlockRetirement() {
        val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(IMAGE_ORDINAL)
        val validation = assertIs<ValidateSpritePair>(driver.actions.single())
        val validationState = driver.state

        assertEquals(
            "route completion requires no in-flight adapter action",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(
                    validationState,
                    RouteCompleted(JSON_ORDINAL, ResourceRouteOutcome.Success),
                )
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                validationState,
                SpritePairValidationCompleted(
                    ResourceActionId(validation.actionId.value + 100L),
                    SpritePairValidationOutcome.Valid,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                validationState,
                SpriteMemberWriteCompleted(
                    validation.actionId,
                    GROUP_ONE,
                    SpriteMember.JSON,
                    SuppliedCallOutcome.Success(Unit),
                ),
            )
        }
        assertIs<AwaitingSpritePairValidation>(validationState.routeRecords.first().cursor)

        driver.event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))
        val write = assertIs<WriteSpriteMember>(driver.actions.single())
        val writeState = driver.state

        assertEquals(
            "route completion requires no in-flight adapter action",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(
                    writeState,
                    RouteCompleted(JSON_ORDINAL, ResourceRouteOutcome.Success),
                )
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                writeState,
                SpriteMemberWriteCompleted(
                    write.actionId,
                    GROUP_TWO,
                    SpriteMember.JSON,
                    SuppliedCallOutcome.Success(Unit),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                writeState,
                SpriteMemberWriteCompleted(
                    write.actionId,
                    GROUP_ONE,
                    SpriteMember.IMAGE,
                    SuppliedCallOutcome.Success(Unit),
                ),
            )
        }

        driver.event(
            SpriteMemberWriteCompleted(
                write.actionId,
                GROUP_ONE,
                SpriteMember.JSON,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val imageWrite = assertIs<WriteSpriteMember>(driver.actions.single())
        driver.event(
            SpriteMemberWriteCompleted(
                imageWrite.actionId,
                GROUP_ONE,
                SpriteMember.IMAGE,
                SuppliedCallOutcome.Success(Unit),
            ),
        )
        val install = assertIs<InstallSpriteVisibility>(driver.actions.single())
        val installState = driver.state

        assertEquals(
            "route completion requires no in-flight adapter action",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(
                    installState,
                    RouteCompleted(JSON_ORDINAL, ResourceRouteOutcome.Success),
                )
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                installState,
                SpriteVisibilityInstallCompleted(
                    install.actionId,
                    GROUP_TWO,
                    SuppliedInstallOutcome.Succeeded,
                ),
            )
        }
        assertIs<AwaitingSpriteVisibilityInstall>(installState.routeRecords.first().cursor)
    }

    @Test
    fun spriteCommitStateAndParkedRoutesAreValidatedAndFreshCopied() {
        val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        val json = driver.candidate(JSON_ORDINAL)
        val image = driver.candidate(IMAGE_ORDINAL)
        val parkedState = driver.state

        val writeInput = mutableListOf(SpriteMember.JSON)
        val group = SpriteCommitState(
            groupId = GROUP_ONE,
            jsonOrdinal = JSON_ORDINAL,
            imageOrdinal = IMAGE_ORDINAL,
            jsonCandidate = json,
            imageCandidate = image,
            jointValidationStatus = SpriteJointValidationStatus.VALID,
            acknowledgedWrites = writeInput,
            visible = false,
        )
        writeInput.clear()
        assertEquals(listOf(SpriteMember.JSON), group.acknowledgedWrites)
        assertNotSame(group.acknowledgedWrites, group.acknowledgedWrites)
        assertEquals(listOf(SpriteMember.JSON, SpriteMember.IMAGE), group.requiredMemberWrites)
        assertEquals(SpriteMember.IMAGE, group.memberAt(IMAGE_ORDINAL))
        assertEquals(
            SpriteCommitState(
                GROUP_ONE,
                JSON_ORDINAL,
                IMAGE_ORDINAL,
                json,
                image,
                SpriteJointValidationStatus.VALID,
                listOf(SpriteMember.JSON),
                false,
            ),
            group,
        )
        assertEquals(
            SpriteCommitState(
                GROUP_ONE,
                JSON_ORDINAL,
                IMAGE_ORDINAL,
                json,
                image,
                SpriteJointValidationStatus.VALID,
                listOf(SpriteMember.JSON),
                false,
            ).hashCode(),
            group.hashCode(),
        )
        assertFalse(group.toString().contains(CONTENT_DIGEST))
        assertTrue(group.toString().contains("jsonCandidatePresent=true"))

        assertFailsWith<IllegalArgumentException> {
            SpriteCommitState(
                GROUP_ONE,
                IMAGE_ORDINAL,
                JSON_ORDINAL,
                image,
                json,
                SpriteJointValidationStatus.WAITING,
                emptyList(),
                false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpriteCommitState(
                GROUP_ONE,
                JSON_ORDINAL,
                IMAGE_ORDINAL,
                json,
                null,
                SpriteJointValidationStatus.VALID,
                emptyList(),
                false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpriteCommitState(
                GROUP_ONE,
                JSON_ORDINAL,
                IMAGE_ORDINAL,
                json,
                image,
                SpriteJointValidationStatus.VALID,
                listOf(SpriteMember.IMAGE),
                false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpriteCommitState(
                GROUP_ONE,
                JSON_ORDINAL,
                IMAGE_ORDINAL,
                json,
                image,
                SpriteJointValidationStatus.VALID,
                listOf(SpriteMember.JSON),
                true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpriteCommitState(
                GROUP_ONE,
                JSON_ORDINAL,
                IMAGE_ORDINAL,
                image,
                image,
                SpriteJointValidationStatus.WAITING,
                emptyList(),
                false,
            )
        }

        val stagedGroup = SpriteCommitState(
            groupId = GROUP_ONE,
            jsonOrdinal = JSON_ORDINAL,
            imageOrdinal = IMAGE_ORDINAL,
            jsonCandidate = json,
            imageCandidate = null,
            jointValidationStatus = SpriteJointValidationStatus.WAITING,
            acknowledgedWrites = emptyList(),
            visible = false,
        )
        val groupInput = mutableListOf(stagedGroup)
        val parkedInput = mutableListOf(ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE)))
        val copied = copyState(parkedState, spriteCommitStates = groupInput, parkedRoutes = parkedInput)
        groupInput.clear()
        parkedInput.clear()
        assertEquals(listOf(stagedGroup), copied.spriteCommitStates)
        assertEquals(
            listOf(ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
            copied.parkedRoutes,
        )
        assertNotSame(copied.spriteCommitStates, copied.spriteCommitStates)
        assertNotSame(copied.parkedRoutes, copied.parkedRoutes)

        assertFailsWith<IllegalArgumentException> {
            copyState(parkedState, spriteCommitStates = listOf(stagedGroup, stagedGroup))
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(parkedState, spriteCommitStates = listOf(stagedGroup), parkedRoutes = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                parkedState,
                spriteCommitStates = listOf(stagedGroup),
                parkedRoutes = listOf(
                    ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE)),
                    ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                parkedState,
                spriteCommitStates = emptyList(),
                parkedRoutes = listOf(ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                parkedState,
                spriteCommitStates = listOf(
                    SpriteCommitState(
                        GROUP_ONE,
                        JSON_ORDINAL,
                        IMAGE_ORDINAL,
                        image,
                        null,
                        SpriteJointValidationStatus.WAITING,
                        emptyList(),
                        false,
                    ),
                ),
                parkedRoutes = listOf(ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
            )
        }
    }

    @Test
    fun spriteCursorsRequireTheirGroupStateAndJsonOwnerOrdinal() {
        val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        driver.driveValidatedPair()
        val write = assertIs<WriteSpriteMember>(driver.actions.single())
        val writeState = driver.state
        val json = driver.candidate(JSON_ORDINAL)
        val image = driver.candidate(IMAGE_ORDINAL)
        val group = driver.group()

        assertFailsWith<IllegalArgumentException> {
            copyState(writeState, spriteCommitStates = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                writeState,
                routeRecords = writeState.routeRecords.map { record ->
                    if (record.ordinal == JSON_ORDINAL) {
                        routeRecord(
                            record,
                            AwaitingSpriteMemberWrite(
                                write.actionId,
                                GROUP_ONE,
                                SpriteMember.IMAGE,
                                IMAGE_ORDINAL,
                                image,
                            ),
                        )
                    } else {
                        record
                    }
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                writeState,
                spriteCommitStates = listOf(group.withJointValidationStatus(SpriteJointValidationStatus.REQUESTED)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AwaitingSpriteMemberWrite(
                write.actionId,
                GROUP_ONE,
                SpriteMember.IMAGE,
                IMAGE_ORDINAL,
                json,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AwaitingSpritePairValidation(write.actionId, GROUP_ONE, JSON_ORDINAL, IMAGE_ORDINAL, image, json)
        }
        assertFailsWith<IllegalArgumentException> {
            InstallSpriteVisibility(write.actionId, GROUP_ONE, image, json)
        }
        assertFailsWith<IllegalArgumentException> {
            WriteSpriteMember(
                write.actionId,
                GROUP_ONE,
                SpriteMember.IMAGE,
                IMAGE_ORDINAL,
                driver.record(JSON_ORDINAL).registration.rawKey,
                image.stored,
            )
        }
    }

    @Test
    fun aCeilingProhibitedSpriteMemberStagesNoValidatedCandidate() {
        val driver = spriteGroupWithHigherOrdinaryRoute()
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        val json = driver.candidate(JSON_ORDINAL)
        assertEquals(json, driver.group().jsonCandidate)
        val blocking = failure(RenGErrorCode.RESOURCE_UNAVAILABLE)

        driver.event(RouteCompleted(2L, ResourceRouteOutcome.Failure(blocking)))
        assertEquals(2L, driver.state.startCeilingOrdinal)

        driver.advanceSpriteCommit(IMAGE_ORDINAL)

        assertNull(driver.group().imageCandidate)
        assertEquals(SpriteJointValidationStatus.WAITING, driver.group().jointValidationStatus)
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(IMAGE_ORDINAL).status)
        assertNull(driver.record(IMAGE_ORDINAL).cursor)
        assertFalse(driver.group().visible)
        assertEquals(ResourceOperationOutcome.Failure(blocking), driver.outcome)
        driver.assertNoSpriteCommitWork("ceiling-prohibited member")
        driver.assertNoRecoveryActions("ceiling-prohibited member")
    }

    @Test
    fun aSpriteCommitGroupMustNameItsOwnBoundMemberRoutes() {
        val driver = spriteGroupWithHigherOrdinaryRoute()
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        val parkedState = driver.state
        val json = driver.candidate(JSON_ORDINAL)

        assertEquals(
            "a sprite member ordinal must name its own bound member route",
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    parkedState,
                    spriteCommitStates = listOf(
                        SpriteCommitState(
                            groupId = GROUP_ONE,
                            jsonOrdinal = JSON_ORDINAL,
                            imageOrdinal = 2L,
                            jsonCandidate = json,
                            imageCandidate = null,
                            jointValidationStatus = SpriteJointValidationStatus.WAITING,
                            acknowledgedWrites = emptyList(),
                            visible = false,
                        ),
                    ),
                )
            }.message,
        )
    }

    @Test
    fun spriteCommitOrderIsNeverTheSpriteMemberEnumOrdinalOrder() {
        assertEquals(listOf(SpriteMember.JSON, SpriteMember.IMAGE), spriteMemberOrder())
        assertEquals(listOf(SpriteMember.IMAGE, SpriteMember.JSON), SpriteMember.entries.toList())
        assertNotEquals(SpriteMember.entries.toList(), spriteMemberOrder())

        val driver = SpriteDriver(spriteGroupDefinition(concurrency = 2))
        driver.driveValidatedPair()
        assertEquals(listOf(SpriteMember.JSON, SpriteMember.IMAGE), driver.group().requiredMemberWrites)
    }

    @Test
    fun aReadyParkedBarrierAtTheStartCeilingIsNotResumed() {
        val driver = spriteGroupWithHigherOrdinaryRoute()
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        driver.driveToPendingCandidate(IMAGE_ORDINAL, ContentProvenance.TRANSPORT_200)
        val ceilinged = copyState(driver.state, startCeilingOrdinal = JSON_ORDINAL)

        val transition = ResourceOperationStateMachine.transition(
            ceilinged,
            AdvancePendingSpriteCommit(IMAGE_ORDINAL),
        )

        val state = requireNotNull(transition.state)
        assertTrue(transition.actions.isEmpty())
        assertNull(transition.outcome)
        assertEquals(
            listOf(
                ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE)),
                ParkedRoute(IMAGE_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE)),
            ),
            state.parkedRoutes,
        )
        val group = state.spriteCommitStates.single()
        assertEquals(SpriteJointValidationStatus.WAITING, group.jointValidationStatus)
        assertTrue(state.bufferedRouteOutcomes.isEmpty())
        assertEquals(
            listOf(ResourceRouteStatus.RUNNING, ResourceRouteStatus.RUNNING),
            listOf(JSON_ORDINAL, IMAGE_ORDINAL).map { ordinal ->
                state.routeRecords.single { it.ordinal == ordinal }.status
            },
        )
    }

    @Test
    fun aParkedRouteExactlyAtTheStartCeilingStaysParked() {
        val driver = spriteGroupWithHigherOrdinaryRoute()
        driver.driveToPendingCandidate(JSON_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.advanceSpriteCommit(JSON_ORDINAL)
        val ceilinged = copyState(driver.state, startCeilingOrdinal = JSON_ORDINAL)
        val blocking = failure(RenGErrorCode.STORE_READ_FAILED)

        val transition = ResourceOperationStateMachine.transition(
            ceilinged,
            RouteCompleted(2L, ResourceRouteOutcome.Failure(blocking)),
        )

        val state = requireNotNull(transition.state)
        assertEquals(
            listOf(ParkedRoute(JSON_ORDINAL, ParkedRouteBarrier.SpritePair(GROUP_ONE))),
            state.parkedRoutes,
        )
        assertEquals(ResourceRouteStatus.RUNNING, state.routeRecords.single { it.ordinal == JSON_ORDINAL }.status)
        assertEquals(0L, state.nextRetirementOrdinal)
        assertEquals(listOf(2L), state.bufferedRouteOutcomes.map(BufferedRouteOutcome::ordinal))
        assertNull(state.terminalSelection)
        assertNull(transition.outcome)
    }
}

private const val FIRST_OWNER_ID: Long = 1L
private const val SECOND_OWNER_ID: Long = 2L
private const val SAMPLE_EPOCH_MILLIS: Long = 100L
private const val JSON_ORDINAL: Long = 0L
private const val IMAGE_ORDINAL: Long = 1L
private val GROUP_ONE: SpriteGroupId = SpriteGroupId(1L)
private val GROUP_TWO: SpriteGroupId = SpriteGroupId(2L)

private class SpriteDriver(definition: ResourceOperationDefinition) {
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

    val parked: List<ParkedRoute>
        get() = state.parkedRoutes

    fun beginLookup(ordinal: Long) {
        applyTransition(ResourceOperationStateMachine.beginLookup(state, ordinal))
    }

    fun event(event: ResourceOperationEvent) {
        applyTransition(ResourceOperationStateMachine.transition(state, event))
    }

    fun advanceSpriteCommit(ordinal: Long) {
        event(AdvancePendingSpriteCommit(ordinal))
    }

    fun record(ordinal: Long): RouteRecord = state.routeRecords.single { it.ordinal == ordinal }

    fun candidate(ordinal: Long): ResolvedResourceContent =
        requireNotNull(record(ordinal).lookup?.selectedContent)

    fun visible(ordinal: Long): VisibleResource {
        val content = candidate(ordinal)
        return VisibleResource(content.resourceKey, content)
    }

    fun group(groupId: SpriteGroupId = GROUP_ONE): SpriteCommitState =
        state.spriteCommitStates.single { it.groupId == groupId }

    fun ordinalOf(member: SpriteMember, groupId: SpriteGroupId = GROUP_ONE): Long =
        group(groupId).ordinalOf(member)

    fun assertNoSpriteCommitWork(label: String) {
        assertTrue(emitted.filterIsInstance<ValidateSpritePair>().isEmpty(), "$label/validate")
        assertTrue(emitted.filterIsInstance<WriteSpriteMember>().isEmpty(), "$label/write")
        assertTrue(emitted.filterIsInstance<InstallSpriteVisibility>().isEmpty(), "$label/install")
    }

    fun assertNoRecoveryActions(label: String) {
        listOf("Retry", "Repair", "Remove", "Fallback", "Rollback").forEach { forbidden ->
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

private fun SpriteDriver.driveToPendingCandidate(ordinal: Long, provenance: ContentProvenance) {
    beginLookup(ordinal)
    val sample = assertIs<SampleClock>(actions.single())
    event(ClockSampled(sample.actionId, SAMPLE_EPOCH_MILLIS))
    when (provenance) {
        ContentProvenance.RESIDENT -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(ResidentObserved(observe.actionId, storedResource(freshUntil = SAMPLE_EPOCH_MILLIS + 1L)))
        }
        ContentProvenance.STORE -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(ResidentObserved(observe.actionId, null))
            val read = assertIs<ReadStore>(actions.single())
            event(
                StoreReadCompleted(read.actionId, SuppliedCallOutcome.Success(storedResource(freshUntil = 1L))),
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

private fun SpriteDriver.driveValidatedPair(
    jsonProvenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
    imageProvenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
): ResolvedResourceContent {
    driveToPendingCandidate(JSON_ORDINAL, jsonProvenance)
    advanceSpriteCommit(JSON_ORDINAL)
    driveToPendingCandidate(IMAGE_ORDINAL, imageProvenance)
    advanceSpriteCommit(IMAGE_ORDINAL)
    val validation = assertIs<ValidateSpritePair>(actions.single())
    event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))
    return candidate(JSON_ORDINAL)
}

private fun SpriteDriver.completeValidatedPair(
    validation: ValidateSpritePair,
    groupId: SpriteGroupId,
    jsonOrdinal: Long,
    imageOrdinal: Long,
) {
    event(SpritePairValidationCompleted(validation.actionId, SpritePairValidationOutcome.Valid))
    listOf(SpriteMember.JSON to jsonOrdinal, SpriteMember.IMAGE to imageOrdinal).forEach { (member, ordinal) ->
        val write = assertIs<WriteSpriteMember>(actions.single())
        assertEquals(member, write.member)
        assertEquals(ordinal, write.ordinal)
        assertEquals(groupId, write.groupId)
        event(SpriteMemberWriteCompleted(write.actionId, groupId, member, SuppliedCallOutcome.Success(Unit)))
    }
    val install = assertIs<InstallSpriteVisibility>(actions.single())
    assertEquals(groupId, install.groupId)
    event(SpriteVisibilityInstallCompleted(install.actionId, groupId, SuppliedInstallOutcome.Succeeded))
}

private fun spriteWorkUnderLowerOrdinaryRoute(): SpriteDriver {
    val driver = SpriteDriver(
        definitionOf(
            concurrency = 2,
            occurrences = ordinaryOccurrence(1L, SECOND_OWNER_ID, 'c') + listOf(
                spriteOccurrence(2L, FIRST_OWNER_ID, GROUP_ONE, SpriteMember.JSON, 'a'),
                spriteOccurrence(3L, FIRST_OWNER_ID, GROUP_ONE, SpriteMember.IMAGE, 'b'),
            ),
        ),
    )
    driver.driveToPendingCandidate(1L, ContentProvenance.TRANSPORT_200)
    driver.advanceSpriteCommit(1L)
    driver.driveToPendingCandidate(2L, ContentProvenance.TRANSPORT_200)
    driver.advanceSpriteCommit(2L)
    return driver
}

private fun spriteGroupWithHigherOrdinaryRoute(): SpriteDriver = SpriteDriver(
    definitionOf(
        concurrency = 2,
        occurrences = spriteOccurrences(
            groupId = GROUP_ONE,
            ownerId = FIRST_OWNER_ID,
            firstOccurrenceId = 1L,
            jsonMarker = 'a',
            imageMarker = 'b',
            jsonProvenance = ContentProvenance.TRANSPORT_200,
            imageProvenance = ContentProvenance.TRANSPORT_200,
        ) + ordinaryOccurrence(3L, SECOND_OWNER_ID, 'c'),
    ),
)

private fun spriteGroupDefinition(
    concurrency: Int,
    jsonProvenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
    imageProvenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
): ResourceOperationDefinition = definitionOf(
    concurrency = concurrency,
    occurrences = spriteOccurrences(
        groupId = GROUP_ONE,
        ownerId = FIRST_OWNER_ID,
        firstOccurrenceId = 1L,
        jsonMarker = 'a',
        imageMarker = 'b',
        jsonProvenance = jsonProvenance,
        imageProvenance = imageProvenance,
    ),
)

private fun spriteOccurrences(
    groupId: SpriteGroupId,
    ownerId: Long,
    firstOccurrenceId: Long,
    jsonMarker: Char,
    imageMarker: Char,
    jsonProvenance: ContentProvenance,
    imageProvenance: ContentProvenance,
): List<ResourceOccurrence> = listOf(
    spriteOccurrence(firstOccurrenceId, ownerId, groupId, SpriteMember.JSON, jsonMarker, jsonProvenance),
    spriteOccurrence(firstOccurrenceId + 1L, ownerId, groupId, SpriteMember.IMAGE, imageMarker, imageProvenance),
)

private fun spriteOccurrence(
    id: Long,
    ownerId: Long,
    groupId: SpriteGroupId,
    member: SpriteMember,
    marker: Char,
    provenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(id),
    ownerId = ResourceOwnerId(ownerId),
    registration = registration(marker, spriteMemberResourceClass(member), accessModeFor(provenance)),
    discoveryRequired = false,
    commitBinding = ResourceCommitBinding.Sprite(groupId, member),
)

private fun ordinaryOccurrence(
    id: Long,
    ownerId: Long,
    marker: Char,
    provenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
): List<ResourceOccurrence> = listOf(
    ResourceOccurrence(
        id = ResourceOccurrenceId(id),
        ownerId = ResourceOwnerId(ownerId),
        registration = registration(marker, ResourceClass.STICKER_IMAGE, accessModeFor(provenance)),
        discoveryRequired = false,
        commitBinding = ResourceCommitBinding.Single,
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

private fun registration(
    marker: Char,
    resourceClass: ResourceClass,
    mode: ResourceAccessMode,
): ResourceRouteRegistration = ResourceRouteRegistration(
    route = ResourceRouteKey(
        accessMode = mode,
        locator = ResourceLocator("locator-$marker-${resourceClass.name}"),
        resourceClass = resourceClass,
        maximumResponseBytes = 4096L,
    ),
    resourceKey = ResourceKey(ResourceKind.EXTERNAL, marker.toString().repeat(64), resourceClass),
    rawKey = RawResourceKey(marker.toString().repeat(63) + "f", resourceClass),
    privateRentileKey = RentilePrivateKey("private-$marker-${resourceClass.name}"),
    canonicalBytes = CanonicalBytes("canonical-$marker-${resourceClass.name}".encodeToByteArray()),
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

private fun failure(code: RenGErrorCode): FailureDescriptor {
    val stage = when (code) {
        RenGErrorCode.TRANSPORT_EXECUTION_FAILED -> PipelineStage.TRANSPORT
        RenGErrorCode.STORE_READ_FAILED -> PipelineStage.STORE_READ
        RenGErrorCode.RESOURCE_UNAVAILABLE -> PipelineStage.RESOURCE_LOOKUP
        else -> error("unsupported sprite commit failure marker")
    }
    val resourceClass = ResourceClass.STICKER_IMAGE
    return FailureDescriptor(
        code = code,
        stage = stage,
        diagnostic = failureContextDiagnostic(
            stage = stage,
            fieldName = DiagnosticField.RESOURCE.takeIf { code == RenGErrorCode.RESOURCE_UNAVAILABLE },
            resourceClass = resourceClass,
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, "e".repeat(64), resourceClass),
        ),
    )
}

private fun routeRecord(
    record: RouteRecord,
    cursor: ResourceRouteCursor,
): RouteRecord = RouteRecord(
    registration = record.registration,
    joinedOccurrenceIds = record.joinedOccurrenceIds,
    ordinal = record.ordinal,
    cursor = cursor,
    status = record.status,
    lookup = record.lookup,
    visibilityInstalled = record.visibilityInstalled,
)

private fun copyState(
    state: ResourceOperationState.Running,
    routeRecords: List<RouteRecord> = state.routeRecords,
    spriteCommitStates: List<SpriteCommitState> = state.spriteCommitStates,
    parkedRoutes: List<ParkedRoute> = state.parkedRoutes,
    startCeilingOrdinal: Long? = state.startCeilingOrdinal,
): ResourceOperationState.Running = ResourceOperationState.Running(
    definition = state.definition,
    occurrences = state.occurrences,
    routeRecords = routeRecords,
    privateRentileKeyClaims = state.privateRentileKeyClaims,
    identityRecords = state.identityRecords,
    transportLatches = state.transportLatches,
    nextActionId = state.nextActionId,
    traversal = state.traversal,
    nextRouteOrdinal = state.nextRouteOrdinal,
    activeRouteOrdinals = state.activeRouteOrdinals,
    nextRetirementOrdinal = state.nextRetirementOrdinal,
    bufferedRouteOutcomes = state.bufferedRouteOutcomes,
    startCeilingOrdinal = startCeilingOrdinal,
    terminalSelection = state.terminalSelection,
    spriteCommitStates = spriteCommitStates,
    parkedRoutes = parkedRoutes,
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

private val CONTENT_BYTES: ByteArray = "abc".encodeToByteArray()
private const val CONTENT_DIGEST: String =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
