package com.rohittp.reng.internal.planning

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
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Sticker
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.diff.FrameStructuralDiff
import com.rohittp.reng.internal.diff.FrameStructuralDiffer
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.FramePlanSegment
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.Sha256Digest
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramePlanningCoreTest {
    @Test
    fun globeProjectionModeFailsBeforeSpatialPlanningAndAnyResourceRoute() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver)
        val globeWithInvalidCamera = framePlan(
            projectionMode = ProjectionMode.GLOBE,
            camera = Camera(90.0, 0.0, 0.0, 0.0, 0.0),
            stickers = listOf(sticker("globe-sticker")),
        )

        val globeOutcome = planningCore.plan(
            request(plan = globeWithInvalidCamera, basemapStyle = ResourceLocator("style-document")),
        )

        assertFailure(globeOutcome, RenGErrorCode.UNSUPPORTED_PROJECTION_MODE, "projectionMode")
        assertEquals(emptyList(), resolver.calls)

        val mercatorOutcome = planningCore.plan(
            request(
                plan = framePlan(
                    camera = Camera(90.0, 0.0, 0.0, 0.0, 0.0),
                    stickers = listOf(sticker("globe-sticker")),
                ),
                basemapStyle = ResourceLocator("style-document"),
            ),
        )
        assertFailure(mercatorOutcome, RenGErrorCode.INVALID_VALUE, "camera.latitude")

        val globeOverBudget = planningCore.plan(
            request(
                plan = framePlan(projectionMode = ProjectionMode.GLOBE),
                outputPixelSize = OutputPixelSize(1024, 1024),
                basemapStyle = ResourceLocator("style-document"),
                maximumBasemapTileInstances = 1,
            ),
        )
        assertFailure(globeOverBudget, RenGErrorCode.UNSUPPORTED_PROJECTION_MODE, "projectionMode")
        assertEquals(emptyList(), resolver.calls)
    }

    // ADR 0029, load-bearing at the full FramePlanningCore level: a check that fires after the
    // private key resolver -- the closest thing to "the transport has already been called" this
    // pure core exposes -- has already run would honour the letter of "before acquisition or
    // drawing" and not the decision. planMercatorSpatial's failure short-circuits plan() before
    // staticResourceTraversal ever runs, so the resolver must see zero calls, exactly like the
    // sibling GLOBE case above.
    @Test
    fun screenPositionedModelIsRefusedBeforeAnyResourceRoute() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver)
        val screenModel = Model(placement = screenPlacement(), glb = ResourceLocator("screen-model.glb"))

        val outcome = planningCore.plan(
            request(
                plan = framePlan(models = listOf(screenModel)),
                basemapStyle = ResourceLocator("style-document"),
            ),
        )

        assertFailure(outcome, RenGErrorCode.UNSUPPORTED_ANCHORING_MODE, "placement.positionMode")
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun cameraLatitudeIsValidatedBeforeItsUnwrappedLongitudeCopy() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver)

        val latitudeAndCopy = planningCore.plan(
            request(plan = framePlan(camera = Camera(90.0, 16385.0 * 360.0, 0.0, 0.0, 0.0))),
        )
        val copyOnly = planningCore.plan(
            request(plan = framePlan(camera = Camera(0.0, 16385.0 * 360.0, 0.0, 0.0, 0.0))),
        )

        assertFailure(latitudeAndCopy, RenGErrorCode.INVALID_VALUE, "camera.latitude")
        assertFailure(copyOnly, RenGErrorCode.INVALID_VALUE, "camera.unwrappedLongitude")
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun shaderProfileValidationFailsAtThePlanningBarrierBeforeAnyResourceRoute() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver)

        val outcome = planningCore.plan(
            request(
                plan = framePlan(
                    stickers = listOf(sticker("barrier-sticker")),
                    geometries = listOf(
                        geometry(
                            topLeft = Vector3(20.0, 0.0, 0.0),
                            bottomRight = Vector3(10.0, 1.0, 0.0),
                            shaderPair = ShaderPair("missing directive", validShader("fragment")),
                        ),
                    ),
                ),
                basemapStyle = ResourceLocator("style-document"),
            ),
        )

        assertFailure(outcome, RenGErrorCode.INVALID_VALUE, "shaderPair")
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun everySuccessfulMercatorPlanRecordsItsLodObservation() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver)

        val hysteresisWithoutBasemap = planSuccess(
            planningCore,
            request(
                plan = framePlan(camera = Camera(0.0, 0.0, 3.8, 0.0, 0.0), drawBasemap = false),
                outputPixelSize = OutputPixelSize(1024, 1024),
                basemapStyle = ResourceLocator("style-document"),
                maximumBasemapTileInstances = 1,
                previousSelectedLod = 2,
            ),
        )
        val withoutHistory = planSuccess(
            planningCore,
            request(plan = framePlan(camera = Camera(0.0, 0.0, 3.4, 0.0, 0.0), drawBasemap = false)),
        )
        val hysteresisWithBasemap = planSuccess(
            planningCore,
            request(
                plan = framePlan(camera = Camera(0.0, 0.0, 3.8, 0.0, 0.0), frameIndex = 7L),
                outputPixelSize = OutputPixelSize(1, 1),
                basemapStyle = ResourceLocator("style-document"),
                maximumBasemapTileInstances = 4096,
                previousSelectedLod = 2,
            ),
        )

        val heldAboveItsHistorylessSelection = planSuccess(
            planningCore,
            request(
                plan = framePlan(
                    frameIndex = 3L,
                    camera = Camera(0.0, 0.0, 2.4, 0.0, 0.0),
                    drawBasemap = false,
                ),
                previousSelectedLod = 3,
            ),
        )
        val sameZoomWithoutHistory = planSuccess(
            planningCore,
            request(
                plan = framePlan(
                    frameIndex = 4L,
                    camera = Camera(0.0, 0.0, 2.4, 0.0, 0.0),
                    drawBasemap = false,
                ),
            ),
        )

        assertEquals(4, hysteresisWithoutBasemap.spatialPlan.lodObservation.selectedLod)
        assertEquals(3, withoutHistory.spatialPlan.lodObservation.selectedLod)
        assertEquals(4, hysteresisWithBasemap.spatialPlan.lodObservation.selectedLod)
        assertNotNull(hysteresisWithBasemap.spatialPlan.tileSelection)
        assertEquals(3, heldAboveItsHistorylessSelection.spatialPlan.lodObservation.selectedLod)
        assertEquals(2, sameZoomWithoutHistory.spatialPlan.lodObservation.selectedLod)
    }

    @Test
    fun framePlanningRequestRejectsAnUnusableTileBudgetOrLodHistory() {
        assertFailsWith<IllegalArgumentException> {
            request(plan = framePlan(), maximumBasemapTileInstances = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            request(plan = framePlan(), maximumBasemapTileInstances = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            request(plan = framePlan(), previousSelectedLod = 23)
        }
        assertFailsWith<IllegalArgumentException> {
            request(plan = framePlan(), previousSelectedLod = -1)
        }
        assertEquals(0, request(plan = framePlan(), previousSelectedLod = 0).previousSelectedLod)
        assertEquals(22, request(plan = framePlan(), previousSelectedLod = 22).previousSelectedLod)
    }

    @Test
    fun suppressedBasemapPlansNoFootprintTileBudgetOrStyleRoute() {
        val configuredStyle = ResourceLocator("style-document")
        val cases = listOf(
            false to configuredStyle,
            true to null,
            false to null,
        )

        for ((drawBasemap, basemapStyle) in cases) {
            val resolver = RecordingPrivateKeyResolver()
            val planned = planSuccess(
                planningCore(resolver),
                request(
                    plan = framePlan(
                        camera = Camera(0.0, 0.0, 3.8, 0.0, 0.0),
                        drawBasemap = drawBasemap,
                        stickers = listOf(sticker("suppressed-sticker")),
                    ),
                    outputPixelSize = OutputPixelSize(1024, 1024),
                    basemapStyle = basemapStyle,
                    maximumBasemapTileInstances = 1,
                    previousSelectedLod = 2,
                ),
            )

            assertNull(planned.spatialPlan.footprint)
            assertNull(planned.spatialPlan.tileSelection)
            assertEquals(4, planned.spatialPlan.lodObservation.selectedLod)
            assertEquals(
                listOf(expectedExternal("suppressed-sticker", ResourceClass.STICKER_IMAGE)),
                planned.staticResourceTraversal,
            )
            assertEquals(
                listOf(ResourceLocator("suppressed-sticker") to ResourceClass.STICKER_IMAGE),
                resolver.calls,
            )
        }
    }

    @Test
    fun activeBasemapPlansItsExactTileBudgetAndLeadsTraversalWithTheStyle() {
        val limits = ResourceLimits(maximumBasemapStyleBytes = 4_096L, maximumStickerImageBytes = 8_192L)
        val resolver = RecordingPrivateKeyResolver()
        val planned = planSuccess(
            planningCore(resolver),
            request(
                plan = framePlan(stickers = listOf(sticker("active-sticker"))),
                outputPixelSize = OutputPixelSize(1024, 1024),
                basemapStyle = ResourceLocator("style-document"),
                resourceLimits = limits,
                maximumBasemapTileInstances = 3,
            ),
        )

        assertNotNull(planned.spatialPlan.footprint)
        val tileSelection = assertNotNull(planned.spatialPlan.tileSelection)
        assertEquals(3, tileSelection.instances.size)
        assertEquals(
            listOf(
                expectedExternal("style-document", ResourceClass.BASEMAP_STYLE, limits),
                expectedExternal("active-sticker", ResourceClass.STICKER_IMAGE, limits),
            ),
            planned.staticResourceTraversal,
        )
        val styleReference =
            assertIs<StaticResourceReference.External>(planned.staticResourceTraversal.first())
        assertEquals(4_096L, styleReference.maximumResponseBytes)
        assertEquals(ResourceClass.BASEMAP_STYLE, styleReference.resourceClass)

        val budgetResolver = RecordingPrivateKeyResolver()
        val overBudget = planningCore(budgetResolver).plan(
            request(
                plan = framePlan(stickers = listOf(sticker("active-sticker"))),
                outputPixelSize = OutputPixelSize(1024, 1024),
                basemapStyle = ResourceLocator("style-document"),
                resourceLimits = limits,
                maximumBasemapTileInstances = 2,
            ),
        )

        val failure = assertFailure(overBudget, RenGErrorCode.RESOURCE_LIMIT_EXCEEDED, "basemapTileInstances")
        val diagnostic = assertNotNull(failure.failure.diagnostic)
        assertEquals(2L, diagnostic.limit)
        assertEquals(3L, diagnostic.actual)
        assertEquals(emptyList(), budgetResolver.calls)
    }

    @Test
    fun plannedFrameCarriesEncodedSegmentsAndTheStructuralDiffAgainstItsPredecessor() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver)
        val referenceEncoder = FramePlanCanonicalEncoder(PureKotlinSha256)
        val predecessor = framePlan(frameIndex = 1L, camera = Camera(10.0, 20.0, 4.0, 0.0, 0.0))
        val successor = framePlan(frameIndex = 1L, camera = Camera(11.0, 20.0, 4.0, 0.0, 0.0))

        val withoutBaseline = planSuccess(planningCore, request(plan = predecessor))
        val withBaseline = planSuccess(
            planningCore,
            request(plan = successor, previousPlan = referenceEncoder.encode(predecessor)),
        )

        assertEquals(referenceEncoder.encode(predecessor), withoutBaseline.encodedPlan)
        assertEquals(referenceEncoder.encode(successor), withBaseline.encodedPlan)
        assertEquals(FrameStructuralDiff(FramePlanSegment.entries.toList()), withoutBaseline.structuralDiff)
        assertEquals(FrameStructuralDiff(listOf(FramePlanSegment.CAMERA)), withBaseline.structuralDiff)
        assertEquals(
            FrameStructuralDiffer.diff(referenceEncoder.encode(predecessor), referenceEncoder.encode(successor)),
            withBaseline.structuralDiff,
        )
    }

    @Test
    fun staticDirectTraversalKeepsStyleStickerModelGeometryOrderWithInputDuplicates() {
        val limits = ResourceLimits(
            maximumBasemapStyleBytes = 1_024L,
            maximumStickerImageBytes = 2_048L,
            maximumModelGlbBytes = 4_096L,
            maximumModelTextureBytes = 8_192L,
        )
        val texturedModel = model("model-glb", "model-texture")
        val untexturedModel = model("second-model-glb")
        val sharedShaderPair = shaderPair("shared")
        val resolver = RecordingPrivateKeyResolver()
        val planned = planSuccess(
            planningCore(resolver),
            request(
                plan = framePlan(
                    stickers = listOf(sticker("sticker-one"), sticker("sticker-two"), sticker("sticker-one")),
                    models = listOf(texturedModel, untexturedModel, texturedModel),
                    geometries = listOf(
                        geometry(Vector3(20.0, 0.0, 0.0), Vector3(10.0, 1.0, 0.0), sharedShaderPair),
                        geometry(Vector3(5.0, 2.0, 0.0), Vector3(-5.0, 3.0, 0.0), shaderPair("second")),
                        geometry(Vector3(4.0, 4.0, 0.0), Vector3(-6.0, 5.0, 0.0), sharedShaderPair),
                    ),
                ),
                basemapStyle = ResourceLocator("style-document"),
                resourceLimits = limits,
            ),
        )

        assertEquals(
            listOf(
                expectedExternal("style-document", ResourceClass.BASEMAP_STYLE, limits),
                expectedExternal("sticker-one", ResourceClass.STICKER_IMAGE, limits),
                expectedExternal("sticker-two", ResourceClass.STICKER_IMAGE, limits),
                expectedExternal("sticker-one", ResourceClass.STICKER_IMAGE, limits),
                expectedExternal("model-glb", ResourceClass.MODEL_GLB, limits),
                expectedExternal("model-texture", ResourceClass.MODEL_TEXTURE, limits),
                expectedExternal("second-model-glb", ResourceClass.MODEL_GLB, limits),
                expectedExternal("model-glb", ResourceClass.MODEL_GLB, limits),
                expectedExternal("model-texture", ResourceClass.MODEL_TEXTURE, limits),
                expectedGeometryProgram(sharedShaderPair),
                expectedGeometryProgram(shaderPair("second")),
                expectedGeometryProgram(sharedShaderPair),
            ),
            planned.staticResourceTraversal,
        )
    }

    @Test
    fun geometryConsumerTexturesAreTraversedSortedByNameRightAfterTheirGeometryProgram() {
        // Cycle F-1 Task 9b, item 4's first half: a Geometry's consumer textures must be traversed
        // during planning exactly like a sticker's image or a model's texture override, so a
        // consumer supplying one is not met with silence.
        val limits = ResourceLimits(maximumModelTextureBytes = 8_192L)
        val resolver = RecordingPrivateKeyResolver()
        val planned = planSuccess(
            planningCore(resolver),
            request(
                plan = framePlan(
                    geometries = listOf(
                        geometry(
                            Vector3(20.0, 0.0, 0.0),
                            Vector3(10.0, 1.0, 0.0),
                            shaderPair("textured"),
                            textures = linkedMapOf(
                                "uMaskB" to ResourceLocator("mask-b"),
                                "uMaskA" to ResourceLocator("mask-a"),
                            ),
                        ),
                    ),
                ),
                resourceLimits = limits,
            ),
        )

        assertEquals(
            listOf(
                expectedGeometryProgram(shaderPair("textured")),
                // Sorted by uniform name (uMaskA, uMaskB), regardless of map insertion order.
                expectedExternal("mask-a", ResourceClass.MODEL_TEXTURE, limits),
                expectedExternal("mask-b", ResourceClass.MODEL_TEXTURE, limits),
            ),
            planned.staticResourceTraversal,
        )
    }

    @Test
    fun privateKeyResolverRunsOncePerDistinctExternalRouteAndNeverForGeometryPrograms() {
        val sharedLocator = "shared-locator"
        val resolver = RecordingPrivateKeyResolver()
        val planned = planSuccess(
            planningCore(resolver),
            request(
                plan = framePlan(
                    stickers = listOf(sticker(sharedLocator), sticker(sharedLocator), sticker("other-sticker")),
                    models = listOf(model("model-glb", sharedLocator), model("model-glb", sharedLocator)),
                    geometries = listOf(
                        geometry(Vector3(20.0, 0.0, 0.0), Vector3(10.0, 1.0, 0.0), shaderPair("vertex-only")),
                    ),
                ),
                basemapStyle = ResourceLocator("style-document"),
            ),
        )

        assertEquals(
            listOf(
                ResourceLocator("style-document") to ResourceClass.BASEMAP_STYLE,
                ResourceLocator(sharedLocator) to ResourceClass.STICKER_IMAGE,
                ResourceLocator("other-sticker") to ResourceClass.STICKER_IMAGE,
                ResourceLocator("model-glb") to ResourceClass.MODEL_GLB,
                ResourceLocator(sharedLocator) to ResourceClass.MODEL_TEXTURE,
            ),
            resolver.calls,
        )

        val externals = planned.staticResourceTraversal.filterIsInstance<StaticResourceReference.External>()
        val stickerKeys = externals
            .filter { it.locator == ResourceLocator(sharedLocator) && it.resourceClass == ResourceClass.STICKER_IMAGE }
            .map { it.privateRentileKey }
        assertEquals(2, stickerKeys.size)
        assertEquals(stickerKeys[0], stickerKeys[1])
        assertNotEquals(
            RentilePrivateKey(privateKeyToken(sharedLocator, ResourceClass.MODEL_TEXTURE)),
            stickerKeys[0],
        )

        val geometryPrograms =
            planned.staticResourceTraversal.filterIsInstance<StaticResourceReference.GeometryProgram>()
        assertEquals(1, geometryPrograms.size)
        assertNull(geometryPrograms.single().rawKey)
        assertEquals(ResourceKind.GEOMETRY_PROGRAM, geometryPrograms.single().resourceKey.kind)
        assertTrue(externals.all { it.rawKey.stableId == it.resourceKey.stableId })
    }

    @Test
    fun distinctLocatorsMayCollapseToOneEqualPrivateKeyWithoutRentile() {
        val collapsedToken = "one-private-key"
        val resolver = RecordingPrivateKeyResolver(
            mapOf(
                ("first-sticker" to ResourceClass.STICKER_IMAGE) to collapsedToken,
                ("second-sticker" to ResourceClass.STICKER_IMAGE) to collapsedToken,
            ),
        )
        val planned = planSuccess(
            planningCore(resolver),
            request(
                plan = framePlan(stickers = listOf(sticker("first-sticker"), sticker("second-sticker"))),
            ),
        )

        val externals = planned.staticResourceTraversal.filterIsInstance<StaticResourceReference.External>()
        assertEquals(2, externals.size)
        assertEquals(2, resolver.calls.size)
        assertEquals(RentilePrivateKey(collapsedToken), externals[0].privateRentileKey)
        assertEquals(externals[0].privateRentileKey, externals[1].privateRentileKey)
        assertNotEquals(externals[0].locator, externals[1].locator)
        assertNotEquals(externals[0].rawKey, externals[1].rawKey)
        assertNotEquals(externals[0].resourceKey, externals[1].resourceKey)
    }

    @Test
    fun frameCanonicalCollisionFailsWithFrameIdentityOnlyAndKeepsTheFirstEntry() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver, encoder = constantDigestEncoder())
        val established = framePlan(frameIndex = 1L, stickers = listOf(sticker("established-sticker")))
        val colliding = framePlan(frameIndex = 2L, stickers = listOf(sticker("colliding-sticker")))

        val establishedOutcome = planSuccess(planningCore, request(plan = established))
        val collisionOutcome = planningCore.plan(request(plan = colliding))
        val callsAfterCollision = resolver.calls
        val replannedEstablished = planningCore.plan(request(plan = established))

        assertEquals(
            listOf(expectedExternal("established-sticker", ResourceClass.STICKER_IMAGE)),
            establishedOutcome.staticResourceTraversal,
        )
        val failure = assertFailure(collisionOutcome, RenGErrorCode.IDENTITY_COLLISION, "frameIdentity")
        val diagnostic = assertNotNull(failure.failure.diagnostic)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.statusCode)
        assertNull(diagnostic.limit)
        assertNull(diagnostic.actual)
        assertEquals(
            listOf(ResourceLocator("established-sticker") to ResourceClass.STICKER_IMAGE),
            callsAfterCollision,
        )
        assertIs<FramePlanningOutcome.Success>(replannedEstablished)
    }

    @Test
    fun noFailingPlanRegistersItsFrameIdentityOrResolvesAnyPrivateKey() {
        val failingRequests = listOf(
            request(plan = framePlan(projectionMode = ProjectionMode.GLOBE, frameIndex = 11L)),
            request(plan = framePlan(camera = Camera(90.0, 0.0, 0.0, 0.0, 0.0), frameIndex = 12L)),
            request(
                plan = framePlan(frameIndex = 13L),
                outputPixelSize = OutputPixelSize(1024, 1024),
                basemapStyle = ResourceLocator("style-document"),
                maximumBasemapTileInstances = 1,
            ),
            request(
                plan = framePlan(
                    frameIndex = 14L,
                    geometries = listOf(
                        geometry(
                            topLeft = Vector3(20.0, 0.0, 0.0),
                            bottomRight = Vector3(10.0, 1.0, 0.0),
                            shaderPair = ShaderPair("missing directive", "also missing directive"),
                        ),
                    ),
                ),
            ),
        )

        for (failingRequest in failingRequests) {
            val resolver = RecordingPrivateKeyResolver()
            val planningCore = planningCore(resolver, encoder = constantDigestEncoder())

            assertIs<FramePlanningOutcome.Failure>(planningCore.plan(failingRequest))
            assertEquals(emptyList(), resolver.calls)

            val afterFailure = planSuccess(
                planningCore,
                request(
                    plan = framePlan(frameIndex = 21L, stickers = listOf(sticker("later-sticker"))),
                ),
            )
            assertEquals(
                listOf(expectedExternal("later-sticker", ResourceClass.STICKER_IMAGE)),
                afterFailure.staticResourceTraversal,
            )
        }
    }

    @Test
    fun plannedFrameCoreSnapshotsItsTraversalAndUsesStructuralEquality() {
        val resolver = RecordingPrivateKeyResolver()
        val planned = planSuccess(
            planningCore(resolver),
            request(plan = framePlan(stickers = listOf(sticker("snapshot-sticker")))),
        )
        val first = expectedExternal("snapshot-sticker", ResourceClass.STICKER_IMAGE)
        val second = expectedExternal("other-sticker", ResourceClass.STICKER_IMAGE)
        val traversalInput = mutableListOf<StaticResourceReference>(first)
        val core = PlannedFrameCore(
            encodedPlan = planned.encodedPlan,
            structuralDiff = planned.structuralDiff,
            spatialPlan = planned.spatialPlan,
            staticResourceTraversal = traversalInput,
        )

        traversalInput[0] = second
        val firstRead = core.staticResourceTraversal
        assertEquals(listOf(first), firstRead)
        assertEquals(listOf(first), core.staticResourceTraversal)
        assertNotSame(firstRead, core.staticResourceTraversal)

        val structurallyEqual = PlannedFrameCore(
            encodedPlan = planned.encodedPlan,
            structuralDiff = planned.structuralDiff,
            spatialPlan = planned.spatialPlan,
            staticResourceTraversal = listOf(expectedExternal("snapshot-sticker", ResourceClass.STICKER_IMAGE)),
        )
        val different = PlannedFrameCore(
            encodedPlan = planned.encodedPlan,
            structuralDiff = planned.structuralDiff,
            spatialPlan = planned.spatialPlan,
            staticResourceTraversal = listOf(second),
        )

        assertEquals(structurallyEqual, core)
        assertEquals(structurallyEqual.hashCode(), core.hashCode())
        assertNotEquals(different, core)
        assertEquals(listOf(first), planned.staticResourceTraversal)

        val otherFrameIndex = planSuccess(
            planningCore(resolver),
            request(plan = framePlan(frameIndex = 41L, stickers = listOf(sticker("snapshot-sticker")))),
        )
        val otherCamera = planSuccess(
            planningCore(resolver),
            request(
                plan = framePlan(
                    frameIndex = 42L,
                    camera = Camera(0.0, 0.0, 5.0, 0.0, 0.0),
                    stickers = listOf(sticker("snapshot-sticker")),
                ),
            ),
        )
        val differentEncodedPlan = PlannedFrameCore(
            encodedPlan = otherFrameIndex.encodedPlan,
            structuralDiff = planned.structuralDiff,
            spatialPlan = planned.spatialPlan,
            staticResourceTraversal = listOf(first),
        )
        val differentStructuralDiff = PlannedFrameCore(
            encodedPlan = planned.encodedPlan,
            structuralDiff = FrameStructuralDiff(listOf(FramePlanSegment.CAMERA)),
            spatialPlan = planned.spatialPlan,
            staticResourceTraversal = listOf(first),
        )
        val differentSpatialPlan = PlannedFrameCore(
            encodedPlan = planned.encodedPlan,
            structuralDiff = planned.structuralDiff,
            spatialPlan = otherCamera.spatialPlan,
            staticResourceTraversal = listOf(first),
        )

        assertNotEquals(planned.encodedPlan, otherFrameIndex.encodedPlan)
        assertNotEquals(planned.structuralDiff, differentStructuralDiff.structuralDiff)
        assertNotEquals(planned.spatialPlan, otherCamera.spatialPlan)
        assertNotEquals(differentEncodedPlan, core)
        assertNotEquals(differentEncodedPlan.hashCode(), core.hashCode())
        assertNotEquals(differentStructuralDiff, core)
        assertNotEquals(differentStructuralDiff.hashCode(), core.hashCode())
        assertNotEquals(differentSpatialPlan, core)
        assertNotEquals(differentSpatialPlan.hashCode(), core.hashCode())
        assertNotEquals(different.hashCode(), core.hashCode())
    }

    @Test
    fun plannedFrameCoreRejectsATraversalContradictingItsSpatialPlan() {
        val resolver = RecordingPrivateKeyResolver()
        val planningCore = planningCore(resolver)
        val stickerOnly = planSuccess(
            planningCore,
            request(plan = framePlan(frameIndex = 31L, stickers = listOf(sticker("guard-sticker")))),
        )
        val withGeometry = planSuccess(
            planningCore,
            request(
                plan = framePlan(
                    frameIndex = 32L,
                    stickers = listOf(sticker("guard-sticker")),
                    geometries = listOf(
                        geometry(Vector3(20.0, 0.0, 0.0), Vector3(10.0, 1.0, 0.0), shaderPair("guard")),
                    ),
                ),
            ),
        )
        val activeBasemap = planSuccess(
            planningCore,
            request(
                plan = framePlan(frameIndex = 33L, stickers = listOf(sticker("guard-sticker"))),
                outputPixelSize = OutputPixelSize(1024, 1024),
                basemapStyle = ResourceLocator("style-document"),
            ),
        )
        val stickerReference = expectedExternal("guard-sticker", ResourceClass.STICKER_IMAGE)
        val styleReference = expectedExternal("style-document", ResourceClass.BASEMAP_STYLE)
        val guardProgram = expectedGeometryProgram(shaderPair("guard"))
        val otherProgram = expectedGeometryProgram(shaderPair("other"))

        assertFailsWith<IllegalArgumentException> {
            PlannedFrameCore(
                encodedPlan = withGeometry.encodedPlan,
                structuralDiff = withGeometry.structuralDiff,
                spatialPlan = withGeometry.spatialPlan,
                staticResourceTraversal = listOf(stickerReference),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFrameCore(
                encodedPlan = stickerOnly.encodedPlan,
                structuralDiff = stickerOnly.structuralDiff,
                spatialPlan = stickerOnly.spatialPlan,
                staticResourceTraversal = listOf(stickerReference, guardProgram),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFrameCore(
                encodedPlan = withGeometry.encodedPlan,
                structuralDiff = withGeometry.structuralDiff,
                spatialPlan = withGeometry.spatialPlan,
                staticResourceTraversal = listOf(stickerReference, otherProgram),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFrameCore(
                encodedPlan = stickerOnly.encodedPlan,
                structuralDiff = stickerOnly.structuralDiff,
                spatialPlan = stickerOnly.spatialPlan,
                staticResourceTraversal = listOf(styleReference, stickerReference),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFrameCore(
                encodedPlan = activeBasemap.encodedPlan,
                structuralDiff = activeBasemap.structuralDiff,
                spatialPlan = activeBasemap.spatialPlan,
                staticResourceTraversal = listOf(styleReference, styleReference, stickerReference),
            )
        }

        assertEquals(listOf(stickerReference, guardProgram), withGeometry.staticResourceTraversal)
        assertEquals(listOf(styleReference, stickerReference), activeBasemap.staticResourceTraversal)
    }

    @Test
    fun staticExternalReferencesAcceptOnlyTheStaticDirectResourceClasses() {
        val deriver = ResourceKeyDeriver(PureKotlinSha256)
        val staticDirect = setOf(
            ResourceClass.BASEMAP_STYLE,
            ResourceClass.STICKER_IMAGE,
            ResourceClass.MODEL_GLB,
            ResourceClass.MODEL_TEXTURE,
        )

        for (resourceClass in ResourceClass.entries) {
            val locator = ResourceLocator("static-direct-probe")
            val derived = deriver.external(resourceClass, locator)
            val construct = {
                StaticResourceReference.External(
                    resourceClass = resourceClass,
                    locator = locator,
                    maximumResponseBytes = 1L,
                    resourceKey = derived.key,
                    rawKey = requireNotNull(derived.rawKey),
                    privateRentileKey = RentilePrivateKey("private"),
                    canonicalIdentity = derived.identity,
                )
            }

            if (resourceClass in staticDirect) {
                assertEquals(resourceClass, construct().resourceClass)
            } else {
                assertFailsWith<IllegalArgumentException> { construct() }
            }
        }
    }

    @Test
    fun staticResourceReferenceRequiresConsistentDerivedKeys() {
        val deriver = ResourceKeyDeriver(PureKotlinSha256)
        val sticker = deriver.external(ResourceClass.STICKER_IMAGE, ResourceLocator("consistent-sticker"))
        val texture = deriver.external(ResourceClass.MODEL_TEXTURE, ResourceLocator("consistent-texture"))
        val program = deriver.geometryProgram(shaderPair("consistent"))

        assertFailsWith<IllegalArgumentException> {
            StaticResourceReference.External(
                resourceClass = ResourceClass.MODEL_TEXTURE,
                locator = ResourceLocator("consistent-sticker"),
                maximumResponseBytes = 1L,
                resourceKey = sticker.key,
                rawKey = requireNotNull(sticker.rawKey),
                privateRentileKey = RentilePrivateKey("private"),
                canonicalIdentity = sticker.identity,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StaticResourceReference.External(
                resourceClass = ResourceClass.MODEL_TEXTURE,
                locator = ResourceLocator("consistent-texture"),
                maximumResponseBytes = 1L,
                resourceKey = texture.key,
                rawKey = requireNotNull(sticker.rawKey),
                privateRentileKey = RentilePrivateKey("private"),
                canonicalIdentity = texture.identity,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StaticResourceReference.External(
                resourceClass = ResourceClass.MODEL_TEXTURE,
                locator = ResourceLocator("consistent-texture"),
                maximumResponseBytes = 0L,
                resourceKey = texture.key,
                rawKey = requireNotNull(texture.rawKey),
                privateRentileKey = RentilePrivateKey("private"),
                canonicalIdentity = texture.identity,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StaticResourceReference.External(
                resourceClass = ResourceClass.MODEL_TEXTURE,
                locator = ResourceLocator("consistent-texture"),
                maximumResponseBytes = 1L,
                resourceKey = texture.key,
                rawKey = requireNotNull(texture.rawKey),
                privateRentileKey = RentilePrivateKey("private"),
                canonicalIdentity = program.identity,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StaticResourceReference.GeometryProgram(
                shaderPair = shaderPair("consistent"),
                resourceKey = sticker.key,
                canonicalIdentity = sticker.identity,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StaticResourceReference.GeometryProgram(
                shaderPair = shaderPair("consistent"),
                resourceKey = program.key,
                canonicalIdentity = texture.identity,
            )
        }
    }

    private fun planningCore(
        resolver: RentilePrivateKeyResolver,
        registry: CanonicalIdentityRegistry = CanonicalIdentityRegistry(),
        encoder: FramePlanCanonicalEncoder = FramePlanCanonicalEncoder(PureKotlinSha256),
    ): FramePlanningCore = FramePlanningCore(
        frameEncoder = encoder,
        frameIdentityRegistry = registry,
        resourceKeyDeriver = ResourceKeyDeriver(PureKotlinSha256),
        rentilePrivateKeyResolver = resolver,
    )

    private fun constantDigestEncoder(): FramePlanCanonicalEncoder =
        FramePlanCanonicalEncoder { Sha256Digest(ByteArray(SHA256_DIGEST_BYTES) { CONSTANT_DIGEST_BYTE }) }

    private fun request(
        plan: FramePlan,
        outputPixelSize: OutputPixelSize = OutputPixelSize(100, 100),
        basemapStyle: ResourceLocator? = null,
        resourceLimits: ResourceLimits = ResourceLimits(),
        maximumBasemapTileInstances: Int = 512,
        previousPlan: EncodedFramePlan? = null,
        previousSelectedLod: Int? = null,
    ): FramePlanningRequest = FramePlanningRequest(
        plan = plan,
        outputPixelSize = outputPixelSize,
        basemapStyle = basemapStyle,
        resourceLimits = resourceLimits,
        maximumBasemapTileInstances = maximumBasemapTileInstances,
        previousPlan = previousPlan,
        previousSelectedLod = previousSelectedLod,
    )

    private fun framePlan(
        frameIndex: Long = 1L,
        camera: Camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
        projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
        drawBasemap: Boolean = true,
        stickers: List<Sticker> = emptyList(),
        models: List<Model> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = camera,
        projectionMode = projectionMode,
        drawBasemap = drawBasemap,
        stickers = stickers,
        models = models,
        geometries = geometries,
    )

    private fun planSuccess(
        planningCore: FramePlanningCore,
        request: FramePlanningRequest,
    ): PlannedFrameCore = assertIs<FramePlanningOutcome.Success>(planningCore.plan(request)).planned

    private fun assertFailure(
        outcome: FramePlanningOutcome,
        code: RenGErrorCode,
        fieldName: String,
    ): FramePlanningOutcome.Failure {
        val failure = assertIs<FramePlanningOutcome.Failure>(outcome)
        assertEquals(code, failure.failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.failure.stage)
        val diagnostic = assertNotNull(failure.failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.FRAME_PLANNING, diagnostic.stage)
        assertEquals(fieldName, diagnostic.fieldName)
        return failure
    }

    private fun expectedExternal(
        locator: String,
        resourceClass: ResourceClass,
        resourceLimits: ResourceLimits = ResourceLimits(),
    ): StaticResourceReference.External {
        val derived = ResourceKeyDeriver(PureKotlinSha256).external(resourceClass, ResourceLocator(locator))
        return StaticResourceReference.External(
            resourceClass = resourceClass,
            locator = ResourceLocator(locator),
            maximumResponseBytes = resourceLimits.maximumBytesFor(resourceClass),
            resourceKey = derived.key,
            rawKey = requireNotNull(derived.rawKey),
            privateRentileKey = RentilePrivateKey(privateKeyToken(locator, resourceClass)),
            canonicalIdentity = derived.identity,
        )
    }

    private fun expectedGeometryProgram(shaderPair: ShaderPair): StaticResourceReference.GeometryProgram {
        val derived = ResourceKeyDeriver(PureKotlinSha256).geometryProgram(shaderPair)
        return StaticResourceReference.GeometryProgram(
            shaderPair = shaderPair,
            resourceKey = derived.key,
            canonicalIdentity = derived.identity,
        )
    }

    private fun sticker(locator: String): Sticker = Sticker(screenPlacement(), ResourceLocator(locator))

    // MAP, not SCREEN: ADR 0029 refuses a SCREEN-positioned Model at frame planning, and this
    // helper's models are used throughout this file to exercise static resource traversal and
    // identity/dedup behaviour, none of which is about draw regime.
    private fun model(glb: String, texture: String? = null): Model = Model(
        placement = mapPlacement(),
        glb = ResourceLocator(glb),
        texture = texture?.let(::ResourceLocator),
    )

    private fun geometry(
        topLeft: Vector3,
        bottomRight: Vector3,
        shaderPair: ShaderPair,
        textures: Map<String, ResourceLocator> = emptyMap(),
    ): Geometry = Geometry(topLeft = topLeft, bottomRight = bottomRight, shaderPair = shaderPair, textures = textures)

    private fun shaderPair(suffix: String): ShaderPair = ShaderPair(
        vertexSource = validShader("$suffix-vertex"),
        fragmentSource = validShader("$suffix-fragment"),
    )

    private fun validShader(suffix: String): String = "#version 300 es\n// $suffix"

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

    private class RecordingPrivateKeyResolver(
        private val tokensByRoute: Map<Pair<String, ResourceClass>, String> = emptyMap(),
    ) : RentilePrivateKeyResolver {
        private val callSnapshot = mutableListOf<Pair<ResourceLocator, ResourceClass>>()

        val calls: List<Pair<ResourceLocator, ResourceClass>> get() = ArrayList(callSnapshot)

        override fun resolve(
            locator: ResourceLocator,
            resourceClass: ResourceClass,
        ): RentilePrivateKey {
            callSnapshot += locator to resourceClass
            val token = tokensByRoute[locator.value to resourceClass]
                ?: privateKeyToken(locator.value, resourceClass)
            return RentilePrivateKey(token)
        }
    }

    private companion object {
        const val SHA256_DIGEST_BYTES: Int = 32
        const val CONSTANT_DIGEST_BYTE: Byte = 0x5a

        fun privateKeyToken(locator: String, resourceClass: ResourceClass): String =
            "private-key:$locator:${resourceClass.name}"
    }
}
