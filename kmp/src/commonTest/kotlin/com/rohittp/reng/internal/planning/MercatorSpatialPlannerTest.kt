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
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Sticker
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.projection.ClosedMercatorFootprint
import com.rohittp.reng.internal.projection.MERCATOR_MAXIMUM_LATITUDE_DEGREES
import com.rohittp.reng.internal.projection.MercatorGroundPoint
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import com.rohittp.reng.internal.shader.ShaderProfilePlan
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MercatorSpatialPlannerTest {
    @Test
    fun inactiveBasemapStillObservesLodWithoutFootprintSelectionOrBudgetWork() {
        val exactLatitude = 31.1234567890123
        val exactLongitude = 444363.4567890123
        val camera = Camera(
            latitude = exactLatitude,
            unwrappedLongitude = exactLongitude,
            zoom = 3.8,
            bearing = 17.0,
            pitch = 23.0,
        )
        val outputPixelSize = OutputPixelSize(1024, 1024)
        val cases = listOf(
            false to true,
            true to false,
            false to false,
        )

        for ((drawBasemap, basemapStyleConfigured) in cases) {
            val spatialPlan = planSuccess(
                plan = framePlan(camera = camera, drawBasemap = drawBasemap),
                outputPixelSize = outputPixelSize,
                previousSelectedLod = 2,
                maximumBasemapTileInstances = 1,
                basemapStyleConfigured = basemapStyleConfigured,
            )

            assertEquals(4, spatialPlan.lodObservation.selectedLod)
            assertNull(spatialPlan.footprint)
            assertNull(spatialPlan.tileSelection)
            assertEquals(exactLatitude.toBits(), spatialPlan.camera.geographicGroundAnchor.latitude.toBits())
            assertEquals(
                exactLongitude.toBits(),
                spatialPlan.camera.geographicGroundAnchor.unwrappedLongitude.toBits(),
            )
        }

        val active = planSuccess(
            plan = framePlan(camera = camera, drawBasemap = true),
            outputPixelSize = OutputPixelSize(1, 1),
            previousSelectedLod = 2,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = true,
        )
        assertNotNull(active.footprint)
        assertNotNull(active.tileSelection)
    }

    @Test
    fun activeBasemapPreservesEmptyPointSegmentAndPolygonFootprintPaths() {
        val empty = planSuccess(
            plan = framePlan(
                camera = Camera(
                    latitude = -MERCATOR_MAXIMUM_LATITUDE_DEGREES,
                    unwrappedLongitude = 0.0,
                    zoom = 0.0,
                    bearing = 0.0,
                    pitch = 89.0,
                ),
            ),
            outputPixelSize = OutputPixelSize(2, 2),
            maximumBasemapTileInstances = 16,
            basemapStyleConfigured = true,
        )
        val point = planSuccess(
            plan = framePlan(),
            outputPixelSize = OutputPixelSize(1, 1),
            maximumBasemapTileInstances = 16,
            basemapStyleConfigured = true,
        )
        val segment = planSuccess(
            plan = framePlan(),
            outputPixelSize = OutputPixelSize(1, 3),
            maximumBasemapTileInstances = 16,
            basemapStyleConfigured = true,
        )
        val polygon = planSuccess(
            plan = framePlan(),
            outputPixelSize = OutputPixelSize(2, 2),
            maximumBasemapTileInstances = 16,
            basemapStyleConfigured = true,
        )

        assertEquals(ClosedMercatorFootprint.Empty, empty.footprint)
        assertTrue(requireNotNull(empty.tileSelection).instances.isEmpty())
        assertTrue(requireNotNull(empty.tileSelection).canonicalResources.isEmpty())
        assertIs<ClosedMercatorFootprint.Point>(point.footprint)
        assertIs<ClosedMercatorFootprint.Segment>(segment.footprint)
        assertIs<ClosedMercatorFootprint.Polygon>(polygon.footprint)
        assertNotNull(point.tileSelection)
        assertNotNull(segment.tileSelection)
        assertNotNull(polygon.tileSelection)
    }

    @Test
    fun activeBasemapMapsExactOverBudgetCountToSanitizedFramePlanningFailure() {
        val outcome = planMercatorSpatial(
            plan = framePlan(),
            outputPixelSize = OutputPixelSize(1024, 1024),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = true,
        )

        val failure = assertFailure(outcome, RenGErrorCode.RESOURCE_LIMIT_EXCEEDED, "basemapTileInstances")
        val diagnostic = requireNotNull(failure.failure.diagnostic)
        assertEquals(1L, diagnostic.limit)
        assertEquals(3L, diagnostic.actual)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.statusCode)
    }

    @Test
    fun positionAnchoringAloneSelectsRegimeAndMapEntriesKeepPlanOrderAndDuplicates() {
        val mapStickerPlacement = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(10.0, 20.0, 30.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(5.0, 6.0, 7.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 8.0,
        )
        val screenStickerPlacement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(100.0, 200.0, 3.0),
            rotationMode = AnchoringMode.MAP,
            rotation = Vector3(8.0, 9.0, 10.0),
            scaleMode = AnchoringMode.MAP,
            scale = 11.0,
        )
        val mapSticker = sticker(mapStickerPlacement, "map-sticker")
        val screenSticker = sticker(screenStickerPlacement, "screen-sticker")
        // ADR 0029: a SCREEN-positioned Model is refused, so both fixture models here are
        // MAP-positioned -- distinguished by rotation/scale mode instead, which is enough on its
        // own to keep demonstrating that regime selection follows positionMode alone.
        val anotherMapModelPlacement = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(30.0, 40.0, 2.0),
            rotationMode = AnchoringMode.MAP,
            rotation = Vector3(-8.0, -9.0, -10.0),
            scaleMode = AnchoringMode.MAP,
            scale = 12.0,
        )
        val mapModelPlacement = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(-10.0, -20.0, -30.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(-5.0, -6.0, -7.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 13.0,
        )
        val spatialPlan = planSuccess(
            plan = framePlan(
                stickers = listOf(mapSticker, screenSticker, mapSticker),
                models = listOf(
                    model(anotherMapModelPlacement, "another-map-model"),
                    model(mapModelPlacement, "map-model"),
                    model(mapModelPlacement, "map-model-duplicate"),
                ),
                drawBasemap = false,
            ),
            outputPixelSize = OutputPixelSize(640, 480),
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = true,
        )

        assertEquals(
            listOf(
                DrawnThingReference.StickerAt(0),
                DrawnThingReference.StickerAt(2),
                DrawnThingReference.ModelAt(0),
                DrawnThingReference.ModelAt(1),
                DrawnThingReference.ModelAt(2),
            ),
            spatialPlan.mapEntries.map(ResolvedDrawnThing::reference),
        )
        assertEquals(
            listOf(DrawnThingReference.StickerAt(1)),
            spatialPlan.screenEntries.map(ResolvedDrawnThing::reference),
        )
        assertEquals(
            resolvePlacement(mapStickerPlacement, spatialPlan.camera).successValue(),
            spatialPlan.mapEntries[0].placement,
        )
        assertEquals(
            resolvePlacement(screenStickerPlacement, spatialPlan.camera).successValue(),
            spatialPlan.screenEntries[0].placement,
        )
        assertEquals(DrawRegime.MAP_OCCLUDED, spatialPlan.mapEntries[0].placement.drawRegime)
        assertEquals(DrawRegime.SCREEN_COMPOSITED, spatialPlan.screenEntries[0].placement.drawRegime)
    }

    // ADR 0029 leaves only Stickers reachable in the screen-composited regime through the real
    // planning pipeline, so this no longer demonstrates a sticker-before-model tiebreak (that
    // property of screenCompositingOrder is still covered directly, via construction, by
    // spatialPlanConstructorRejectsScreenEntriesOutsideDocumentedCompositingOrder below).
    @Test
    fun screenEntriesSortByZThenSourceIndex() {
        val duplicateZeroSticker = sticker(screenPlacement(-0.0), "zero-sticker")
        val spatialPlan = planSuccess(
            plan = framePlan(
                drawBasemap = false,
                stickers = listOf(
                    sticker(screenPlacement(2.0), "top-sticker"),
                    duplicateZeroSticker,
                    sticker(screenPlacement(1.0), "middle-sticker"),
                    duplicateZeroSticker,
                    sticker(screenPlacement(-1.0), "bottom-sticker"),
                ),
            ),
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )

        assertEquals(
            listOf(
                DrawnThingReference.StickerAt(4),
                DrawnThingReference.StickerAt(1),
                DrawnThingReference.StickerAt(3),
                DrawnThingReference.StickerAt(2),
                DrawnThingReference.StickerAt(0),
            ),
            spatialPlan.screenEntries.map(ResolvedDrawnThing::reference),
        )
        assertEquals(
            listOf(-1.0, 0.0, 0.0, 1.0, 2.0),
            spatialPlan.screenEntries.map { requireNotNull(it.placement.screenCompositeZ) },
        )
        for (entry in spatialPlan.screenEntries.drop(1).take(2)) {
            assertEquals(0.0.toBits(), requireNotNull(entry.placement.screenCompositeZ).toBits())
        }
    }

    @Test
    fun geometriesAndShaderProfilesRemainPairedInGeometryAndCornerOrder() {
        val first = geometry(
            topLeft = Vector3(20.0, 30.0, 100.0),
            bottomRight = Vector3(10.0, 40.0, 50.0),
            vertexSuffix = "first-vertex",
            fragmentSuffix = "first-fragment",
        )
        val second = geometry(
            topLeft = Vector3(5.0, 50.0, 25.0),
            bottomRight = Vector3(-5.0, 60.0, 10.0),
            vertexSuffix = "second-vertex",
            fragmentSuffix = "second-fragment",
        )
        val spatialPlan = planSuccess(
            plan = framePlan(drawBasemap = false, geometries = listOf(first, second)),
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )

        assertEquals(
            listOf(
                resolveGeometry(first, spatialPlan.camera).successValue(),
                resolveGeometry(second, spatialPlan.camera).successValue(),
            ),
            spatialPlan.geometries,
        )
        assertEquals(
            listOf(
                first.shaderPair.vertexSource to first.shaderPair.fragmentSource,
                second.shaderPair.vertexSource to second.shaderPair.fragmentSource,
            ),
            spatialPlan.shaderProfiles.map { (vertex, fragment) ->
                vertex.originalSource to fragment.originalSource
            },
        )
        val firstCorners = spatialPlan.geometries[0].cornersClockwiseFromTopLeft
        assertEquals(4, firstCorners.size)
        assertEquals(
            resolveGeometry(first, spatialPlan.camera).successValue().cornersClockwiseFromTopLeft,
            firstCorners,
        )
    }

    @Test
    fun cameraBudgetAndDrawnThingFailuresUseDeterministicPlanningPrecedence() {
        val invalidScreenX = screenPlacement(z = 0.0, x = Double.MAX_VALUE)
        // positionMode = MAP so this fixture still exercises INVALID_VALUE/"placement.scale" (the
        // concern this test predates ADR 0029) rather than tripping the newer
        // UNSUPPORTED_ANCHORING_MODE check when reused on a Model below.
        val invalidScale = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(0.0, 0.0, 0.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = Double.MAX_VALUE,
        )
        val cameraFirst = planMercatorSpatial(
            plan = framePlan(
                camera = Camera(90.0, 16385.0 * 360.0, 0.0, 0.0, 0.0),
                drawBasemap = false,
                stickers = listOf(sticker(invalidScreenX, "invalid")),
            ),
            outputPixelSize = OutputPixelSize(100, 100),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )
        assertFailure(cameraFirst, RenGErrorCode.INVALID_VALUE, "camera.latitude")

        val budgetFirst = planMercatorSpatial(
            plan = framePlan(
                stickers = listOf(sticker(invalidScreenX, "invalid")),
            ),
            outputPixelSize = OutputPixelSize(1024, 1024),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = true,
        )
        assertFailure(budgetFirst, RenGErrorCode.RESOURCE_LIMIT_EXCEEDED, "basemapTileInstances")

        val stickerFirst = planMercatorSpatial(
            plan = framePlan(
                drawBasemap = false,
                stickers = listOf(
                    sticker(invalidScreenX, "first-invalid-sticker"),
                    sticker(invalidScale, "second-invalid-sticker"),
                ),
                models = listOf(model(invalidScale, "invalid-model")),
            ),
            outputPixelSize = OutputPixelSize(100, 100),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )
        assertFailure(stickerFirst, RenGErrorCode.INVALID_VALUE, "screenPosition.x")

        val modelFirst = planMercatorSpatial(
            plan = framePlan(
                drawBasemap = false,
                stickers = listOf(sticker(screenPlacement(0.0), "valid-sticker")),
                models = listOf(
                    model(invalidScale, "first-invalid-model"),
                    model(invalidScreenX, "second-invalid-model"),
                ),
            ),
            outputPixelSize = OutputPixelSize(100, 100),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )
        assertFailure(modelFirst, RenGErrorCode.INVALID_VALUE, "placement.scale")
    }

    // ADR 0029: a SCREEN-positioned Model is refused at frame planning, before acquisition or
    // drawing, never substituted -- the screen projection has no z row at all, so a volumetric mesh
    // drawn there would show its back faces through its front ones.
    @Test
    fun aScreenPositionedModelIsRefusedAtFramePlanning() {
        val outcome = planMercatorSpatial(
            plan = framePlan(
                drawBasemap = false,
                models = listOf(model(screenPlacement(0.0), "screen-model")),
            ),
            outputPixelSize = OutputPixelSize(100, 100),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )

        assertFailure(outcome, RenGErrorCode.UNSUPPORTED_ANCHORING_MODE, "placement.positionMode")
    }

    // Only a Model's SCREEN *position* is refused -- a Sticker is flat and has no interior to
    // occlude, so it keeps working in the screen-composited regime exactly as before. This is the
    // test that stops the check being written one level too high (on Placement rather than Model).
    @Test
    fun aScreenPositionedStickerIsStillAccepted() {
        val spatialPlan = planSuccess(
            plan = framePlan(
                drawBasemap = false,
                stickers = listOf(sticker(screenPlacement(0.0), "screen-sticker")),
            ),
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )

        assertEquals(
            listOf(DrawnThingReference.StickerAt(0)),
            spatialPlan.screenEntries.map(ResolvedDrawnThing::reference),
        )
    }

    // The billboard case CONTEXT.md and ADR 0029 both explicitly keep supported: a MAP position with
    // SCREEN rotation/scale falls out of composeMapModelViewProjection naturally, so it must not be
    // caught by a check that only looks at positionMode.
    @Test
    fun aScreenRotationOrScaleOverAMapPositionedModelIsStillAccepted() {
        val billboardPlacement = Placement(
            positionMode = AnchoringMode.MAP,
            position = Vector3(10.0, 20.0, 30.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(1.0, 2.0, 3.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 4.0,
        )
        val spatialPlan = planSuccess(
            plan = framePlan(
                drawBasemap = false,
                models = listOf(model(billboardPlacement, "billboard-model")),
            ),
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )

        assertEquals(
            listOf(DrawnThingReference.ModelAt(0)),
            spatialPlan.mapEntries.map(ResolvedDrawnThing::reference),
        )
    }

    // ADR 0029: "before acquisition or drawing, never substituting." planMercatorSpatial has no
    // transport of its own -- real acquisition only happens later, in
    // FramePlanningCore.staticResourceTraversal, which runs only once planMercatorSpatial succeeds.
    // So proving this check preempts resolveDrawnThing/resolvePlacement -- the only per-model work
    // this loop performs, and the exact site an invalid placement.scale would otherwise be caught at
    // -- proves the refusal happens before FramePlanningCore could ever reach acquisition for this
    // frame. If the check instead ran after resolveDrawnThing, this would surface
    // INVALID_VALUE/"placement.scale" (resolvePlacement's own validation) rather than
    // UNSUPPORTED_ANCHORING_MODE.
    @Test
    fun theRefusalHappensBeforeAnyAcquisition() {
        val screenModelWithAnOtherwiseInvalidScale = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(0.0, 0.0, 0.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = Double.MAX_VALUE,
        )
        val outcome = planMercatorSpatial(
            plan = framePlan(
                drawBasemap = false,
                models = listOf(model(screenModelWithAnOtherwiseInvalidScale, "poison-model")),
            ),
            outputPixelSize = OutputPixelSize(100, 100),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )

        assertFailure(outcome, RenGErrorCode.UNSUPPORTED_ANCHORING_MODE, "placement.positionMode")
    }

    @Test
    fun eachGeometryResolvesThenScansBeforeTheNextGeometry() {
        val invalidShaderFirst = geometry(
            topLeft = Vector3(20.0, 0.0, 0.0),
            bottomRight = Vector3(10.0, 1.0, 0.0),
            vertexSuffix = "invalid-profile",
            fragmentSuffix = "valid",
        ).copy(
            shaderPair = ShaderPair("missing directive", validShader("valid-fragment")),
        )
        val invalidGeometrySecond = Geometry(
            topLeft = Vector3(86.0, 2.0, 0.0),
            bottomRight = Vector3(10.0, 3.0, 0.0),
            shaderPair = ShaderPair(validShader("valid-vertex"), validShader("valid-fragment")),
        )
        val firstOutcome = planMercatorSpatial(
            plan = framePlan(
                drawBasemap = false,
                geometries = listOf(invalidShaderFirst, invalidGeometrySecond),
            ),
            outputPixelSize = OutputPixelSize(100, 100),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )
        assertFailure(firstOutcome, RenGErrorCode.INVALID_VALUE, "shaderPair")

        val invalidGeometryFirst = invalidGeometrySecond.copy(
            topLeft = Vector3(86.0, 4.0, 0.0),
            bottomRight = Vector3(10.0, 5.0, 0.0),
            shaderPair = ShaderPair("missing directive", "also missing directive"),
        )
        val secondOutcome = planMercatorSpatial(
            plan = framePlan(
                drawBasemap = false,
                geometries = listOf(invalidGeometryFirst, invalidShaderFirst),
            ),
            outputPixelSize = OutputPixelSize(100, 100),
            previousSelectedLod = null,
            maximumBasemapTileInstances = 1,
            basemapStyleConfigured = false,
        )
        assertFailure(secondOutcome, RenGErrorCode.INVALID_VALUE, "geometry.latitude")
    }

    @Test
    fun spatialPlanConstructorAllowsInactiveAndActiveEmptyBasemapStates() {
        val inactive = spatialPlanValue(
            footprint = null,
            tileSelection = null,
        )
        val emptySelection = TileSelectionOutcome.Success(emptyList(), emptyList())
        val activeEmpty = spatialPlanValue(
            footprint = ClosedMercatorFootprint.Empty,
            tileSelection = emptySelection,
        )

        assertNull(inactive.footprint)
        assertNull(inactive.tileSelection)
        assertEquals(ClosedMercatorFootprint.Empty, activeEmpty.footprint)
        assertEquals(emptySelection, activeEmpty.tileSelection)
    }

    @Test
    fun spatialPlanConstructorRejectsFootprintSelectionPresenceMismatches() {
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(
                footprint = ClosedMercatorFootprint.Empty,
                tileSelection = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(
                footprint = null,
                tileSelection = TileSelectionOutcome.Success(emptyList(), emptyList()),
            )
        }
    }

    @Test
    fun spatialPlanConstructorRejectsGeometryProfileCardinalityMismatches() {
        val camera = resolvedCamera()
        val geometry = geometry(
            topLeft = Vector3(10.0, 0.0, 0.0),
            bottomRight = Vector3(0.0, 1.0, 0.0),
            vertexSuffix = "cardinality-vertex",
            fragmentSuffix = "cardinality-fragment",
        )
        val resolvedGeometry = resolveGeometry(geometry, camera).successValue()
        val profiles = requireNotNull(scanShaderProfile(geometry.shaderPair.vertexSource)) to
            requireNotNull(scanShaderProfile(geometry.shaderPair.fragmentSource))

        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(
                camera = camera,
                geometries = listOf(resolvedGeometry),
                shaderProfiles = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(
                camera = camera,
                geometries = emptyList(),
                shaderProfiles = listOf(profiles),
            )
        }
    }

    @Test
    fun spatialPlanConstructorRequiresEachGeometryToMatchProfileSourcesAtTheSameIndex() {
        val camera = resolvedCamera()
        val first = geometry(
            topLeft = Vector3(20.0, 0.0, 0.0),
            bottomRight = Vector3(10.0, 1.0, 0.0),
            vertexSuffix = "first-source-vertex",
            fragmentSuffix = "first-source-fragment",
        )
        val second = geometry(
            topLeft = Vector3(5.0, 2.0, 0.0),
            bottomRight = Vector3(-5.0, 3.0, 0.0),
            vertexSuffix = "second-source-vertex",
            fragmentSuffix = "second-source-fragment",
        )
        val resolved = listOf(
            resolveGeometry(first, camera).successValue(),
            resolveGeometry(second, camera).successValue(),
        )
        val firstProfiles = requireNotNull(scanShaderProfile(first.shaderPair.vertexSource)) to
            requireNotNull(scanShaderProfile(first.shaderPair.fragmentSource))
        val secondProfiles = requireNotNull(scanShaderProfile(second.shaderPair.vertexSource)) to
            requireNotNull(scanShaderProfile(second.shaderPair.fragmentSource))

        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(
                camera = camera,
                geometries = resolved,
                shaderProfiles = listOf(secondProfiles, firstProfiles),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(
                camera = camera,
                geometries = resolved,
                shaderProfiles = listOf(
                    firstProfiles.first to secondProfiles.second,
                    secondProfiles,
                ),
            )
        }
    }

    @Test
    fun spatialPlanConstructorRejectsEntriesPlacedInTheWrongDrawRegime() {
        val camera = resolvedCamera()
        val mapEntry = ResolvedDrawnThing(
            reference = DrawnThingReference.StickerAt(7),
            placement = resolvePlacement(mapPlacement(), camera).successValue(),
        )
        val screenEntry = ResolvedDrawnThing(
            reference = DrawnThingReference.ModelAt(11),
            placement = resolvePlacement(screenPlacement(2.0), camera).successValue(),
        )

        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(camera = camera, mapEntries = listOf(screenEntry))
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(camera = camera, screenEntries = listOf(mapEntry))
        }
        val valid = spatialPlanValue(
            camera = camera,
            mapEntries = listOf(mapEntry),
            screenEntries = listOf(screenEntry),
        )
        assertEquals(listOf(mapEntry), valid.mapEntries)
        assertEquals(listOf(screenEntry), valid.screenEntries)
    }

    @Test
    fun spatialPlanConstructorRejectsScreenEntriesOutsideDocumentedCompositingOrder() {
        val camera = resolvedCamera()
        val lowSticker = screenEntry(DrawnThingReference.StickerAt(0), z = 1.0, camera = camera)
        val highSticker = screenEntry(DrawnThingReference.StickerAt(1), z = 2.0, camera = camera)
        val tiedEarlierSticker = screenEntry(DrawnThingReference.StickerAt(2), z = 5.0, camera = camera)
        val tiedLaterSticker = screenEntry(DrawnThingReference.StickerAt(3), z = 5.0, camera = camera)
        val tiedModel = screenEntry(DrawnThingReference.ModelAt(2), z = 5.0, camera = camera)

        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(camera = camera, screenEntries = listOf(highSticker, lowSticker))
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(camera = camera, screenEntries = listOf(tiedModel, tiedLaterSticker))
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(camera = camera, screenEntries = listOf(tiedLaterSticker, tiedEarlierSticker))
        }

        val ordered = listOf(lowSticker, highSticker, tiedEarlierSticker, tiedLaterSticker, tiedModel)
        assertEquals(ordered, spatialPlanValue(camera = camera, screenEntries = ordered).screenEntries)
    }

    @Test
    fun spatialPlanConstructorRejectsOneDrawnThingResolvedIntoMoreThanOneEntry() {
        val camera = resolvedCamera()
        val mapSticker = mapEntry(DrawnThingReference.StickerAt(4), camera)
        val screenSticker = screenEntry(DrawnThingReference.StickerAt(4), z = 1.0, camera = camera)

        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(
                camera = camera,
                mapEntries = listOf(mapSticker),
                screenEntries = listOf(screenSticker),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(camera = camera, mapEntries = listOf(mapSticker, mapSticker))
        }
        assertFailsWith<IllegalArgumentException> {
            spatialPlanValue(camera = camera, screenEntries = listOf(screenSticker, screenSticker))
        }

        val duplicateValuesAtDistinctIndices = listOf(
            mapEntry(DrawnThingReference.StickerAt(0), camera),
            mapEntry(DrawnThingReference.StickerAt(2), camera),
            mapEntry(DrawnThingReference.ModelAt(0), camera),
            mapEntry(DrawnThingReference.ModelAt(2), camera),
        )
        assertEquals(
            duplicateValuesAtDistinctIndices,
            spatialPlanValue(camera = camera, mapEntries = duplicateValuesAtDistinctIndices).mapEntries,
        )
        assertEquals(
            listOf(mapSticker, mapEntry(DrawnThingReference.ModelAt(4), camera)),
            spatialPlanValue(
                camera = camera,
                mapEntries = listOf(mapSticker, mapEntry(DrawnThingReference.ModelAt(4), camera)),
            ).mapEntries,
        )
    }

    @Test
    fun spatialPlanValidatesItsOwnSnapshotsRatherThanTheCallerSuppliedLists() {
        val camera = resolvedCamera()
        val screenSticker = screenEntry(DrawnThingReference.StickerAt(0), z = 1.0, camera = camera)
        val mapSticker = mapEntry(DrawnThingReference.StickerAt(0), camera)

        val spatialPlan = spatialPlanValue(
            camera = camera,
            screenEntries = ShiftingReadList(listOf(screenSticker, mapSticker)),
        )

        assertEquals(listOf(screenSticker), spatialPlan.screenEntries)
        assertEquals(
            DrawRegime.SCREEN_COMPOSITED,
            spatialPlan.screenEntries.single().placement.drawRegime,
        )
    }

    @Test
    fun spatialPlanSnapshotsConstructorListsReturnsFreshCopiesAndUsesStructuralEquality() {
        val camera = resolvedCamera()
        val mapEntry = ResolvedDrawnThing(
            DrawnThingReference.StickerAt(0),
            resolvePlacement(mapPlacement(), camera).successValue(),
        )
        val screenEntry = ResolvedDrawnThing(
            DrawnThingReference.ModelAt(0),
            resolvePlacement(screenPlacement(1.0), camera).successValue(),
        )
        val geometry = geometry(
            topLeft = Vector3(10.0, 0.0, 0.0),
            bottomRight = Vector3(0.0, 1.0, 0.0),
            vertexSuffix = "vertex",
            fragmentSuffix = "fragment",
        )
        val resolvedGeometry = resolveGeometry(geometry, camera).successValue()
        val vertexProfile = requireNotNull(scanShaderProfile(geometry.shaderPair.vertexSource))
        val fragmentProfile = requireNotNull(scanShaderProfile(geometry.shaderPair.fragmentSource))
        val mapInput = mutableListOf(mapEntry)
        val screenInput = mutableListOf(screenEntry)
        val geometryInput = mutableListOf(resolvedGeometry)
        val profileInput = mutableListOf(vertexProfile to fragmentProfile)
        val footprint = ClosedMercatorFootprint.Point(MercatorGroundPoint(0.5, 0.5))
        val selection = TileSelectionOutcome.Success(
            instances = listOf(BasemapTileInstance(0, 0, 0L, 0, 0)),
            canonicalResources = listOf(CanonicalBasemapTile(0, 0, 0)),
        )
        val spatialPlan = MercatorSpatialPlan(
            camera = camera,
            lodObservation = LodObservation(0),
            footprint = footprint,
            tileSelection = selection,
            mapEntries = mapInput,
            screenEntries = screenInput,
            geometries = geometryInput,
            shaderProfiles = profileInput,
        )

        mapInput[0] = screenEntry
        screenInput[0] = mapEntry
        geometryInput.clear()
        val foreignProfile = requireNotNull(scanShaderProfile(validShader("foreign-profile")))
        profileInput[0] = foreignProfile to foreignProfile
        val firstMapRead = spatialPlan.mapEntries
        val firstScreenRead = spatialPlan.screenEntries
        val firstGeometryRead = spatialPlan.geometries
        val firstProfileRead = spatialPlan.shaderProfiles
        assertEquals(listOf(mapEntry), firstMapRead)
        assertEquals(listOf(screenEntry), firstScreenRead)
        assertEquals(listOf(resolvedGeometry), firstGeometryRead)
        assertEquals(listOf(vertexProfile to fragmentProfile), firstProfileRead)
        assertEquals(listOf(mapEntry), spatialPlan.mapEntries)
        assertEquals(listOf(screenEntry), spatialPlan.screenEntries)
        assertEquals(listOf(resolvedGeometry), spatialPlan.geometries)
        assertEquals(listOf(vertexProfile to fragmentProfile), spatialPlan.shaderProfiles)
        assertNotSame(firstMapRead, spatialPlan.mapEntries)
        assertNotSame(firstScreenRead, spatialPlan.screenEntries)
        assertNotSame(firstGeometryRead, spatialPlan.geometries)
        assertNotSame(firstProfileRead, spatialPlan.shaderProfiles)

        val structurallyEqual = MercatorSpatialPlan(
            camera = camera.copy(),
            lodObservation = LodObservation(0),
            footprint = ClosedMercatorFootprint.Point(MercatorGroundPoint(0.5, 0.5)),
            tileSelection = TileSelectionOutcome.Success(
                instances = listOf(BasemapTileInstance(0, 0, 0L, 0, 0)),
                canonicalResources = listOf(CanonicalBasemapTile(0, 0, 0)),
            ),
            mapEntries = listOf(mapEntry.copy()),
            screenEntries = listOf(screenEntry.copy()),
            geometries = listOf(
                ResolvedGeometry(resolvedGeometry.cornersClockwiseFromTopLeft, geometry.shaderPair.copy()),
            ),
            shaderProfiles = listOf(
                requireNotNull(scanShaderProfile(geometry.shaderPair.vertexSource)) to
                    requireNotNull(scanShaderProfile(geometry.shaderPair.fragmentSource)),
            ),
        )
        val different = MercatorSpatialPlan(
            camera = camera,
            lodObservation = LodObservation(1),
            footprint = footprint,
            tileSelection = selection,
            mapEntries = listOf(mapEntry),
            screenEntries = listOf(screenEntry),
            geometries = listOf(resolvedGeometry),
            shaderProfiles = listOf(vertexProfile to fragmentProfile),
        )

        assertEquals(structurallyEqual, spatialPlan)
        assertEquals(structurallyEqual.hashCode(), spatialPlan.hashCode())
        assertNotEquals(different, spatialPlan)
    }

    private fun framePlan(
        camera: Camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
        drawBasemap: Boolean = true,
        stickers: List<Sticker> = emptyList(),
        models: List<Model> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): FramePlan = FramePlan(
        frameIndex = 1L,
        camera = camera,
        drawBasemap = drawBasemap,
        stickers = stickers,
        models = models,
        geometries = geometries,
    )

    private fun planSuccess(
        plan: FramePlan,
        outputPixelSize: OutputPixelSize = OutputPixelSize(100, 100),
        previousSelectedLod: Int? = null,
        maximumBasemapTileInstances: Int,
        basemapStyleConfigured: Boolean,
    ): MercatorSpatialPlan = assertIs<SpatialOutcome.Success<MercatorSpatialPlan>>(
        planMercatorSpatial(
            plan = plan,
            outputPixelSize = outputPixelSize,
            previousSelectedLod = previousSelectedLod,
            maximumBasemapTileInstances = maximumBasemapTileInstances,
            basemapStyleConfigured = basemapStyleConfigured,
        ),
    ).value

    private fun spatialPlanValue(
        camera: ResolvedMercatorCamera = resolvedCamera(),
        footprint: ClosedMercatorFootprint? = null,
        tileSelection: TileSelectionOutcome.Success? = null,
        mapEntries: List<ResolvedDrawnThing> = emptyList(),
        screenEntries: List<ResolvedDrawnThing> = emptyList(),
        geometries: List<ResolvedGeometry> = emptyList(),
        shaderProfiles: List<Pair<ShaderProfilePlan, ShaderProfilePlan>> = emptyList(),
    ): MercatorSpatialPlan = MercatorSpatialPlan(
        camera = camera,
        lodObservation = LodObservation(0),
        footprint = footprint,
        tileSelection = tileSelection,
        mapEntries = mapEntries,
        screenEntries = screenEntries,
        geometries = geometries,
        shaderProfiles = shaderProfiles,
    )

    private fun screenEntry(
        reference: DrawnThingReference,
        z: Double,
        camera: ResolvedMercatorCamera,
    ): ResolvedDrawnThing =
        ResolvedDrawnThing(reference, resolvePlacement(screenPlacement(z), camera).successValue())

    private fun mapEntry(
        reference: DrawnThingReference,
        camera: ResolvedMercatorCamera,
    ): ResolvedDrawnThing =
        ResolvedDrawnThing(reference, resolvePlacement(mapPlacement(), camera).successValue())

    private fun resolvedCamera(): ResolvedMercatorCamera =
        assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(
                camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
                outputPixelSize = OutputPixelSize(100, 100),
            ),
        ).value

    private fun screenPlacement(
        z: Double,
        x: Double = 10.0,
        y: Double = 20.0,
    ): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(x, y, z),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun mapPlacement(): Placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = Vector3(0.0, 0.0, 0.0),
        rotationMode = AnchoringMode.MAP,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.MAP,
        scale = 1.0,
    )

    private fun sticker(placement: Placement, locator: String): Sticker =
        Sticker(placement, ResourceLocator(locator))

    private fun model(placement: Placement, locator: String): Model =
        Model(placement, ResourceLocator(locator))

    private fun geometry(
        topLeft: Vector3,
        bottomRight: Vector3,
        vertexSuffix: String,
        fragmentSuffix: String,
    ): Geometry = Geometry(
        topLeft = topLeft,
        bottomRight = bottomRight,
        shaderPair = ShaderPair(
            vertexSource = validShader(vertexSuffix),
            fragmentSource = validShader(fragmentSuffix),
        ),
    )

    private fun validShader(suffix: String): String = "#version 300 es\n// $suffix"

    private fun SpatialOutcome<ResolvedPlacement>.successValue(): ResolvedPlacement =
        assertIs<SpatialOutcome.Success<ResolvedPlacement>>(this).value

    private fun SpatialOutcome<ResolvedGeometry>.successValue(): ResolvedGeometry =
        assertIs<SpatialOutcome.Success<ResolvedGeometry>>(this).value

    private fun assertFailure(
        outcome: SpatialOutcome<*>,
        code: RenGErrorCode,
        fieldName: String,
    ): SpatialOutcome.Failure {
        val failure = assertIs<SpatialOutcome.Failure>(outcome)
        assertEquals(code, failure.failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.failure.stage)
        val diagnostic = requireNotNull(failure.failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.FRAME_PLANNING, diagnostic.stage)
        assertEquals(fieldName, diagnostic.fieldName)
        return failure
    }

    private class ShiftingReadList(
        private val readsInOrder: List<ResolvedDrawnThing>,
    ) : AbstractList<ResolvedDrawnThing>() {
        private var reads = 0

        override val size: Int get() = 1

        override fun get(index: Int): ResolvedDrawnThing {
            val value = readsInOrder[minOf(reads, readsInOrder.size - 1)]
            reads += 1
            return value
        }
    }
}
