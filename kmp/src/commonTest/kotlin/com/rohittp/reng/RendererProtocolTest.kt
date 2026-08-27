package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RendererProtocolTest {
    @Test
    fun configurationRetainsInputsUsesExactDefaultsAndKeepsIdentitySemantics() {
        val outputPixelSize = OutputPixelSize(1920, 1080)
        val transport = Transport { error("test transport must not execute") }
        val store = TestStore()
        val basemapStyle = ResourceLocator("style")
        val resourceLimits = ResourceLimits(maximumModelGlbBytes = 1L)
        val diagnosticSink = DiagnosticSink { }

        val configuration = RendererConfiguration(
            outputPixelSize = outputPixelSize,
            transport = transport,
            store = store,
            basemapStyle = basemapStyle,
            resourceLimits = resourceLimits,
            diagnosticSink = diagnosticSink,
        )
        val equalInputsConfiguration = RendererConfiguration(
            outputPixelSize = outputPixelSize,
            transport = transport,
            store = store,
            basemapStyle = basemapStyle,
            resourceLimits = resourceLimits,
            diagnosticSink = diagnosticSink,
        )
        val defaults = RendererConfiguration(outputPixelSize, transport, store)

        assertSame(outputPixelSize, configuration.outputPixelSize)
        assertSame(transport, configuration.transport)
        assertSame(store, configuration.store)
        assertSame(basemapStyle, configuration.basemapStyle)
        assertSame(resourceLimits, configuration.resourceLimits)
        assertSame(diagnosticSink, configuration.diagnosticSink)
        assertNotEquals(configuration, equalInputsConfiguration)

        assertEquals(null, defaults.basemapStyle)
        assertEquals(ResourceLimits(), defaults.resourceLimits)
        assertEquals(512, defaults.maximumBasemapTileInstances)
        assertEquals(256, defaults.maximumPreparationBatchSize)
        assertEquals(8, defaults.maximumConcurrentResourceOperations)
        assertSame(DiagnosticSink.None, defaults.diagnosticSink)
    }

    @Test
    fun theDefaultGpuTextureBudgetHoldsTheDefaultTileCeilingWithoutThrashing() {
        val defaults = RendererConfiguration(
            OutputPixelSize(3840, 2160),
            Transport { error("test transport must not execute") },
            TestStore(),
        )

        // Stated as the relationship, never as a literal. `assertEquals(512 MiB, ...)` would pass
        // just as happily with the tile ceiling at 4096, which is the case it exists to catch: what
        // matters is that a frame drawing every tile RenG will plan for it fits the budget RenG
        // ships, so a level 4K camera cannot re-decode and re-upload a megabyte per tile per frame
        // under a configuration nobody touched.
        //
        // Greater-or-equal, not equal, because the budget covering *more* than the ceiling is not a
        // defect -- but it is exactly equal today, so a byte off in the default is a failure here.
        val canonicalTileBytes = 512L * 512L * 4L
        assertTrue(
            ResourceLimits().maximumResidentGpuTextureBytes >=
                defaults.maximumBasemapTileInstances * canonicalTileBytes,
            "the default budget (${ResourceLimits().maximumResidentGpuTextureBytes} bytes) must hold " +
                "the ${defaults.maximumBasemapTileInstances} canonical tiles the default ceiling admits",
        )
    }

    @Test
    fun configurationRejectsEveryNumericLimitOutsideItsExactRange() {
        val outputPixelSize = OutputPixelSize(1, 1)
        val transport = Transport { error("test transport must not execute") }
        val store = TestStore()

        listOf(0, 4097).forEach { invalidTileLimit ->
            assertFailsWith<IllegalArgumentException> {
                RendererConfiguration(
                    outputPixelSize,
                    transport,
                    store,
                    maximumBasemapTileInstances = invalidTileLimit,
                )
            }
        }
        listOf(0, 4097).forEach { invalidBatchLimit ->
            assertFailsWith<IllegalArgumentException> {
                RendererConfiguration(
                    outputPixelSize,
                    transport,
                    store,
                    maximumPreparationBatchSize = invalidBatchLimit,
                )
            }
        }
        listOf(0, 65).forEach { invalidConcurrency ->
            assertFailsWith<IllegalArgumentException> {
                RendererConfiguration(
                    outputPixelSize,
                    transport,
                    store,
                    maximumConcurrentResourceOperations = invalidConcurrency,
                )
            }
        }
    }

    @Test
    fun framebufferNamePreservesAllUnsignedValuesWithStructuralSemantics() {
        val defaultFramebuffer = FramebufferName(0u)
        val maximumFramebuffer = FramebufferName(UInt.MAX_VALUE)

        assertEquals(0u, defaultFramebuffer.value)
        assertEquals(defaultFramebuffer, FramebufferName(0u))
        assertEquals(defaultFramebuffer.hashCode(), FramebufferName(0u).hashCode())
        assertNotEquals(defaultFramebuffer, maximumFramebuffer)
        assertEquals(UInt.MAX_VALUE, maximumFramebuffer.value)
    }

    private suspend fun referenceRendererMethodSurface(
        renderer: Renderer,
        plan: FramePlan,
        preparedFrame: PreparedFrame,
        renderTarget: RenderTarget,
    ) {
        val prepared: PreparedFrame = renderer.prepare(plan)
        val preparedBatch: List<PreparedFrame> = renderer.prepareBatch(listOf(plan))
        val cancellation: Unit = renderer.cancelPreparations()
        val historyClear: Unit = renderer.clearFrameHistory()
        val report: ResourceReport = renderer.queryResources()
        val freeResult: ResourceFreeResult = renderer.freeResources()
        val gpuObjectsGone: Unit = renderer.notifyGpuObjectsGone()
        val contextAdoption: Unit = renderer.adoptCurrentRenderContext()
        val mintedTarget: RenderTarget = renderer.mintRenderTarget(FramebufferName(0u))
        val draw: Unit = renderer.draw(preparedFrame, renderTarget)
        val rendererClose: Unit = renderer.close()
        val rendererAutoCloseable: AutoCloseable = renderer

        val preparedFrameIndex: Long = preparedFrame.frameIndex
        val preparedFrameClose: Unit = preparedFrame.close()
        val preparedFrameAutoCloseable: AutoCloseable = preparedFrame
        val targetFramebufferName: FramebufferName = renderTarget.framebufferName

        consume(
            prepared,
            preparedBatch,
            cancellation,
            historyClear,
            report,
            freeResult,
            gpuObjectsGone,
            contextAdoption,
            mintedTarget,
            draw,
            rendererClose,
            rendererAutoCloseable,
            preparedFrameIndex,
            preparedFrameClose,
            preparedFrameAutoCloseable,
            targetFramebufferName,
        )
    }

    private fun consume(vararg values: Any?) {
        values.hashCode()
    }

    private class TestStore : Store {
        override suspend fun read(key: RawResourceKey): StoredRawResource? =
            error("test store must not read")

        override suspend fun write(key: RawResourceKey, resource: StoredRawResource): Unit =
            error("test store must not write")
    }
}
