package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failureContextDiagnostic
import java.util.AbstractList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmDefensiveCopyTest {
    @Test
    fun emptySingletonAndMultiElementListsAreFreshJvmCopies() {
        (0..2).forEach { count ->
            val tracks = List(count) { animationTrack(it) }
            val model = model(animationTracks = tracks)
            val equalModel = model(animationTracks = tracks)
            assertFreshMutableListCopies(
                owner = model,
                expected = tracks,
                mutationElement = animationTrack(9),
                getter = Model::animationTracks,
                assertEquality = { assertEquals(equalModel, model) },
            )

            val stickers = List(count) { sticker(it) }
            val stickerPlan = framePlan(stickers = stickers)
            val equalStickerPlan = framePlan(stickers = stickers)
            assertFreshMutableListCopies(
                owner = stickerPlan,
                expected = stickers,
                mutationElement = sticker(9),
                getter = FramePlan::stickers,
                assertEquality = { assertEquals(equalStickerPlan, stickerPlan) },
            )

            val models = List(count) { model(ResourceLocator("smoke:model:$it")) }
            val modelPlan = framePlan(models = models)
            val equalModelPlan = framePlan(models = models)
            assertFreshMutableListCopies(
                owner = modelPlan,
                expected = models,
                mutationElement = model(ResourceLocator("smoke:model:9")),
                getter = FramePlan::models,
                assertEquality = { assertEquals(equalModelPlan, modelPlan) },
            )

            val geometries = List(count) { geometry(it) }
            val geometryPlan = framePlan(geometries = geometries)
            val equalGeometryPlan = framePlan(geometries = geometries)
            assertFreshMutableListCopies(
                owner = geometryPlan,
                expected = geometries,
                mutationElement = geometry(9),
                getter = FramePlan::geometries,
                assertEquality = { assertEquals(equalGeometryPlan, geometryPlan) },
            )

            val diagnostics = List(count) { failureDiagnostic() }
            val exception = exceptionWithDiagnostics(diagnostics)
            val distinctException = exceptionWithDiagnostics(diagnostics)
            assertFreshMutableListCopies(
                owner = exception,
                expected = diagnostics,
                mutationElement = failureDiagnostic(),
                getter = RenGException::diagnostics,
                assertEquality = {
                    assertSame(exception, exception)
                    assertNotEquals(distinctException, exception)
                },
            )

            val entries = List(count) { reportEntry(it) }
            val report = ResourceReport(entries, emptyUsage(), emptyResidency())
            val equalReport = ResourceReport(entries.reversed(), emptyUsage(), emptyResidency())
            assertFreshMutableListCopies(
                owner = report,
                expected = report.entries,
                mutationElement = reportEntry(9),
                getter = ResourceReport::entries,
                assertEquality = { assertEquals(equalReport, report) },
            )
        }
    }

    @Test
    fun zeroLengthAndNonemptyTransportBodiesAreIndependentGetterResults() {
        listOf(byteArrayOf(), byteArrayOf(1, 2)).forEach { supplied ->
            val expected = supplied.copyOf()
            val response = TransportResponse(200, supplied, TransportResponseMetadata())
            val equalResponse = TransportResponse(200, expected, TransportResponseMetadata())
            supplied.fill(99)
            val initialHash = response.hashCode()

            val first = response.body
            val second = response.body
            assertNotSame(first, second)
            if (first.isNotEmpty()) {
                first[0] = 42
            }

            assertContentEquals(expected, second)
            assertContentEquals(expected, response.body)
            assertEquals(equalResponse, response)
            assertEquals(initialHash, response.hashCode())
        }
    }

    @Test
    fun zeroLengthAndNonemptyStoredBytesAreIndependentGetterResults() {
        listOf(byteArrayOf(), byteArrayOf(3, 4)).forEach { supplied ->
            val expected = supplied.copyOf()
            val metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L)
            val resource = StoredRawResource(supplied, "digest", metadata)
            val equalResource = StoredRawResource(expected, "digest", metadata)
            supplied.fill(99)
            val initialHash = resource.hashCode()

            val first = resource.bytes
            val second = resource.bytes
            assertNotSame(first, second)
            if (first.isNotEmpty()) {
                first[0] = 42
            }

            assertContentEquals(expected, second)
            assertContentEquals(expected, resource.bytes)
            assertEquals(equalResource, resource)
            assertEquals(initialHash, resource.hashCode())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <O : Any, T> assertFreshMutableListCopies(
        owner: O,
        expected: List<T>,
        mutationElement: T,
        getter: (O) -> List<T>,
        assertEquality: () -> Unit,
    ) {
        val initialHash = owner.hashCode()
        assertEquality()

        val first = getter(owner) as MutableList<T>
        val second = getter(owner) as MutableList<T>
        assertNotSame(first, second)
        first.add(mutationElement)
        assertEquals(expected.size + 1, first.size)
        first.clear()
        assertTrue(first.isEmpty())
        assertEquals(expected, second)
        second.add(mutationElement)
        assertEquals(expected.size + 1, second.size)
        second.clear()
        assertTrue(second.isEmpty())

        assertEquals(expected, getter(owner))
        assertEquals(initialHash, owner.hashCode())
        assertEquality()
    }

    private fun framePlan(
        stickers: List<Sticker> = emptyList(),
        models: List<Model> = emptyList(),
        geometries: List<Geometry> = emptyList(),
    ): FramePlan =
        FramePlan(
            frameIndex = 0L,
            camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
            stickers = stickers,
            models = models,
            geometries = geometries,
        )

    private fun placement(): Placement =
        Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(0.5, 0.5, 0.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )

    private fun sticker(index: Int): Sticker =
        Sticker(placement(), ResourceLocator("smoke:sticker:$index"))

    private fun animationTrack(index: Int): AnimationTrack =
        AnimationTrack(AnimationSelector.Index(index.toLong()), index.toDouble())

    private fun model(
        glb: ResourceLocator = ResourceLocator("smoke:model"),
        animationTracks: List<AnimationTrack> = emptyList(),
    ): Model = Model(placement(), glb, animationTracks = animationTracks)

    private fun geometry(index: Int): Geometry =
        Geometry(
            topLeft = Vector3(1.0, index.toDouble(), 0.0),
            bottomRight = Vector3(0.0, index.toDouble() + 0.5, 0.0),
            shaderPair = ShaderPair("#version 300 es\nvoid main() {}", "#version 300 es\nvoid main() {}"),
        )

    private fun failureDiagnostic(): Diagnostic =
        failureContextDiagnostic(PipelineStage.FRAME_PLANNING, DiagnosticField.FRAME_INDEX)

    private fun exceptionWithDiagnostics(diagnostics: List<Diagnostic>): RenGException {
        if (diagnostics.isEmpty()) {
            return RenGException(RenGErrorCode.RENDERER_CLOSED, PipelineStage.DRAW)
        }
        val constructorInput =
            if (diagnostics.size == 1) diagnostics else sizeOneListThatIterates(diagnostics)
        return RenGException(
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            constructorInput,
        )
    }

    private fun <T> sizeOneListThatIterates(elements: List<T>): List<T> =
        object : AbstractList<T>() {
            override val size: Int = 1

            override fun get(index: Int): T = elements[index]

            override fun iterator(): MutableIterator<T> = elements.toMutableList().iterator()
        }

    private fun reportEntry(index: Int): ResourceReportEntry =
        ResourceReportEntry(
            key = ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = index.toString(16).repeat(64),
                resourceClass = ResourceClass.STICKER_IMAGE,
            ),
            residentGenerationCount = index,
            retiredGenerationCount = 0,
            leaseCount = 0,
            reloadRequired = false,
            usage = emptyUsage(),
        )

    private fun emptyUsage(): ResourceUsage = ResourceUsage(0L, 0L, 0L, false)

    private fun emptyResidency(): ResourceResidency = ResourceResidency(0L, 0L, 0L, 0L)
}
