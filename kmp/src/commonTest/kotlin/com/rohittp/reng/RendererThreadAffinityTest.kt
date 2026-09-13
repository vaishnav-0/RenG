package com.rohittp.reng

import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * ADR 0056: a renderer may be used from one thread at a time, and the thread may change only across
 * `adoptCurrentRenderContext()`.
 *
 * RenG cannot observe which context is current, so this is the only part of ADR 0015's "current on
 * the calling thread" it can enforce at all. It is a necessary condition, never a sufficient one.
 */
class RendererThreadAffinityTest {
    @Test
    fun aGlCallFromAnotherThreadIsRefusedRatherThanIssued() = runTest {
        val renderer = threadTestRenderer()

        val failure = withContext(Dispatchers.Default) {
            assertFailsWith<RenGException> { renderer.mintRenderTarget(FramebufferName(0u)) }
        }

        // Before this, the call went through and issued GL against whatever context -- if any -- was
        // current on that thread: a black frame or a driver crash, not a failure a consumer can act on.
        assertEquals(RenGErrorCode.RENDER_CONTEXT_THREAD_CHANGED, failure.code)
        assertEquals(PipelineStage.RENDER_TARGET, failure.stage)
        renderer.close()
    }

    @Test
    fun drawingFromAnotherThreadIsRefusedRatherThanIssued() = runTest {
        val renderer = threadTestRenderer()
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(FramePlan(frameIndex = 0L, camera = styleCamera()))

        // The entry point that matters most: everything a frame does to the GPU happens here, so a
        // draw on the wrong thread is the whole defect ADR 0056 is about. Preparing is deliberately
        // *not* guarded -- it is suspend and may resume anywhere -- so the frame crossing threads is
        // fine and only the draw is refused.
        val failure = withContext(Dispatchers.Default) {
            assertFailsWith<RenGException> { renderer.draw(frame, target) }
        }

        assertEquals(RenGErrorCode.RENDER_CONTEXT_THREAD_CHANGED, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        frame.close()
        renderer.close()
    }

    @Test
    fun freeingResourcesFromAnotherThreadIsRefused() = runTest {
        val renderer = threadTestRenderer()

        // freeResources deletes GL objects, which is one of the three operations ADR 0015 names.
        val failure = withContext(Dispatchers.Default) {
            assertFailsWith<RenGException> { renderer.freeResources() }
        }

        assertEquals(RenGErrorCode.RENDER_CONTEXT_THREAD_CHANGED, failure.code)
        assertEquals(PipelineStage.RESOURCE_FREE, failure.stage)
        renderer.close()
    }

    @Test
    fun preparingFromAnotherThreadIsNotRefused() = runTest {
        val renderer = threadTestRenderer()

        // Asserted, not assumed. `prepare` is suspend and "may legally be resumed from any context";
        // BasemapEngineHostTest has a whole case built on that. Guarding it would take the property
        // away, so this pins that it was not guarded by accident.
        val frame = withContext(Dispatchers.Default) {
            renderer.prepare(FramePlan(frameIndex = 0L, camera = styleCamera()))
        }

        frame.close()
        renderer.close()
    }

    @Test
    fun theOriginalThreadIsUnaffectedByTheRefusal() = runTest {
        val renderer = threadTestRenderer()
        withContext(Dispatchers.Default) {
            assertFailsWith<RenGException> { renderer.mintRenderTarget(FramebufferName(0u)) }
        }

        // "Either failure leaves renderer state unchanged" (ADR 0015). The refusal must not have
        // moved the renderer's idea of where it lives.
        renderer.mintRenderTarget(FramebufferName(0u))
        renderer.close()
    }

    /**
     * The declaration ADR 0056 is built around — and the price of it, asserted rather than described.
     *
     * Adoption is not reachable from a live renderer: ADR 0015 routes to it only through declared
     * loss. So moving a renderer to another thread costs every GPU object it holds, even though
     * moving a GL context loses none of them. That is the cost the ADR accepts, and this case is
     * where it is visible.
     */
    @Test
    fun movingToAnotherThreadCostsADeclaredLossAndThenWorks() = runTest {
        val renderer = threadTestRenderer()

        withContext(Dispatchers.Default) {
            // Adopting straight away is refused: the lifecycle has no edge from live to adopted.
            assertFailsWith<RenGException> { renderer.adoptCurrentRenderContext() }

            renderer.notifyGpuObjectsGone()
            renderer.adoptCurrentRenderContext()

            // Now this thread owns the renderer, and the work it refused a moment ago goes through.
            renderer.mintRenderTarget(FramebufferName(0u))
        }
    }

    @Test
    fun adoptingOnANewThreadMovesTheRendererRatherThanWideningIt() = runTest {
        val renderer = threadTestRenderer()
        withContext(Dispatchers.Default) {
            renderer.notifyGpuObjectsGone()
            renderer.adoptCurrentRenderContext()
        }

        // A move, not a widening: the thread the renderer was created on is now the wrong one.
        val failure = assertFailsWith<RenGException> { renderer.mintRenderTarget(FramebufferName(0u)) }
        assertEquals(RenGErrorCode.RENDER_CONTEXT_THREAD_CHANGED, failure.code)
    }

    @Test
    fun notifyingGpuObjectsGoneIsAllowedFromAnywhere() = runTest {
        val renderer = threadTestRenderer()

        // ADR 0015 makes this "idempotent, issues no GL call". A call that touches no context cannot
        // be on the wrong thread for one, and it is the escape hatch a consumer needs to close a
        // renderer from a shutdown hook.
        withContext(Dispatchers.Default) { renderer.notifyGpuObjectsGone() }
        withContext(Dispatchers.Default) { renderer.close() }
    }
}


private fun threadTestRenderer(): Renderer = createRenderer(
    RendererConfiguration(
        outputPixelSize = OutputPixelSize(16, 16),
        transport = StyleTransport(),
        store = RecordingStyleStore(),
        basemapStyle = null,
    ),
    threadTestBinding(),
    RenderContextProbe { RenderContextIdentity(1L) },
)

private fun threadTestBinding(): RecordingGlBinding = styleGlBinding()
