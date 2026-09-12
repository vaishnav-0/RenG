package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.driver.PreparationDriver
import com.rohittp.reng.internal.driver.RenGClassGateRunner
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlBindingResult
import com.rohittp.reng.internal.gl.GlLifecycleDriver
import com.rohittp.reng.internal.gl.GlObjectRegistry
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.RenderContextAdoption
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.adoptRenderContext
import com.rohittp.reng.internal.gl.openPlatformGlBinding
import com.rohittp.reng.internal.metrics.EngineMetricRecorder
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.renGFailure
import kotlin.time.TimeSource

/**
 * Builds a [Renderer] over the caller's already-current Render Context.
 *
 * **Synchronous and throwing, deliberately.** Setup captures the already-current context's identity,
 * queries its profile, allocates RenG's own offscreen surface and internal draw pipelines, and
 * constructs the renderer — all synchronous GL work with no suspend point. It **records**
 * [RendererConfiguration.basemapStyle] and performs no consumer exchange at all: a setup that fetched
 * would have to suspend, and a library whose defining claim is purity must not perform network I/O in
 * its constructor. `prepare` returns a [PreparedFrame] directly and therefore must throw rather than
 * return a sealed result, so this factory throws [RenGException] too, for consistency rather than
 * taste — two failure idioms in one small API is the worse cost.
 *
 * Every failure this function can throw carries no driver-identifying text: [RenGException] and its
 * [Diagnostic] have no free-text field, and nothing here ever reads `GL_VENDOR`/`GL_RENDERER`/the
 * shading-language string into anything but [com.rohittp.reng.internal.gl.RenderContextProfile], which
 * this function never surfaces.
 */
public fun createRenderer(configuration: RendererConfiguration): Renderer {
    val binding = when (val result = openPlatformGlBinding()) {
        is GlBindingResult.Bound -> result.binding
        is GlBindingResult.Unsupported -> throw result.failure.toException()
    }
    return createRenderer(configuration, binding, ProductionRenderContextProbe)
}

/**
 * The real construction path, parameterized over [binding] and [probe] so a test can drive it with
 * [com.rohittp.reng.internal.gl.RecordingGlBinding] and an arbitrary [RenderContextProbe] rather than
 * a real platform binding. The public [createRenderer] above is the only production caller.
 *
 * **Leak discipline.** If any step after the first allocation fails, everything already created is
 * deleted before this function throws. [com.rohittp.reng.internal.createInternalGlState] already
 * carries that discipline for the offscreen surface and the two internal pipelines it allocates
 * together; nothing before it in this function allocates a GL object at all (adopting a context reads
 * it but changes nothing on a rejected context — see [adoptRenderContext]'s own KDoc), so there is
 * nothing else this function could leak.
 */
internal fun createRenderer(
    configuration: RendererConfiguration,
    binding: GlBinding,
    probe: RenderContextProbe,
): Renderer {
    val identity: RenderContextIdentity = probe.currentContextIdentity()
        ?: throw renGFailure(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, PipelineStage.CONTEXT_ADOPTION)

    val profile = when (val adoption = adoptRenderContext(binding)) {
        is RenderContextAdoption.Adopted -> adoption.profile
        is RenderContextAdoption.Rejected -> throw adoption.failure.toException()
    }

    val programs = GlProgramCache()
    val glState = when (
        val result = createInternalGlState(binding, profile, programs, configuration)
    ) {
        is InternalGlStateResult.Created -> result.state
        is InternalGlStateResult.Failed -> throw result.failure.toException()
    }

    val residentCache = ResidentCache(
        residentByteBudget = configuration.resourceLimits.maximumResidentCpuResourceBytes,
    )

    // The renderer's one long-lived Rentile engine (ADR 0016). Constructed here rather than lazily
    // because setup is where every renderer-lifetime resource is fixed (ADR 0012) and because building it
    // performs no I/O and no suspension at all; it is closed by RenGRenderer.close() below, alongside the
    // resident cache, and its close() is not GL-scoped so it is untouched by ADR 0015's exact-context rule.
    // One recorder for the renderer's lifetime, shared by the host that feeds it and the renderer
    // that reports it -- the counters are cumulative since this renderer was created (ADR 0049), so
    // a second instance anywhere would split the totals in half without saying so.
    val metricRecorder = EngineMetricRecorder()

    val basemapEngineHost = BasemapEngineHost(
        transport = configuration.transport,
        store = configuration.store,
        cache = residentCache,
        metricRecorder = metricRecorder,
    )
    val preparationDriver = PreparationDriver(
        transport = configuration.transport,
        store = configuration.store,
        cache = residentCache,
        classGateRunner = RenGClassGateRunner(configuration.resourceLimits),
        basemapEngineHost = basemapEngineHost,
        resourceLimits = configuration.resourceLimits,
        maximumConcurrentOperations = configuration.maximumConcurrentResourceOperations,
        clock = monotonicMillisClock,
    )

    // Shared with RenGRenderer below -- Task 9b's texture-lifetime fix caches uploaded sticker and
    // geometry-consumer textures in this exact registry, so the SAME instance GlLifecycleDriver
    // forgets-without-deleting on context loss (ADR 0007/0015) must be the one RenGRenderer caches
    // into and deletes from on close(); two separate GlObjectRegistry instances here would silently
    // desynchronize that lifecycle.
    //
    // The byte budget is threaded from the caller's own ResourceLimits rather than left at
    // GlObjectRegistry's default. Defaulting it here made `maximumResidentGpuTextureBytes` a
    // documented public knob with no effect at all -- every renderer used the default 128 MB
    // whatever the consumer configured -- which is precisely the shape of bug the basemap ground's
    // one-megabyte-per-tile residency turns from theoretical into expensive.
    val objectRegistry = GlObjectRegistry(configuration.resourceLimits.maximumResidentGpuTextureBytes)

    val driver = GlLifecycleDriver(
        binding = binding,
        probe = probe,
        registry = objectRegistry,
        programs = programs,
        initialSnapshot = RendererLifecycleSnapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 0L,
            preparationActive = false,
            gpuLedger = GpuLedger(hasLiveGpuObjects = true, deferredDeletions = emptyList()),
        ),
        initialContext = identity,
        initialProfile = profile,
    )

    return RenGRenderer(
        configuration = configuration,
        binding = binding,
        driver = driver,
        preparationDriver = preparationDriver,
        residentCache = residentCache,
        basemapEngineHost = basemapEngineHost,
        programs = programs,
        glObjectRegistry = objectRegistry,
        metricRecorder = metricRecorder,
        initialGlState = glState,
    )
}

/**
 * RenG never creates, makes current, or destroys a Render Context and never references CGL, EAGL,
 * EGL, `NSOpenGLContext`, or `ANativeWindow` (ADR 0001) — a constraint this cycle's own factory is
 * bound by exactly as every earlier cycle was. There is therefore no production-safe way to ask any
 * of the six published platforms "is a context current, and which one" without touching one of those
 * APIs: [com.rohittp.reng.internal.gl.RenderContextIdentity]'s own KDoc records that a real pointer
 * identity is obtainable only in the two conformance fixtures Cycle D built for exactly this reason
 * (`CglCoreProfileContext`, `SurfacelessEglContext`), never in production source.
 *
 * Production therefore trusts the documented precondition instead: a caller obtains a [Renderer] only
 * by handing [createRenderer] an already-current context, and RenG has no reliable way to observe
 * that context changing later either (ADR 0007) — only the caller can know that, which is exactly why
 * [Renderer.notifyGpuObjectsGone] and [Renderer.adoptCurrentRenderContext] exist as explicit,
 * caller-driven operations rather than something RenG polls for. This probe reports a single fixed
 * identity for as long as the process runs, so [Renderer.close]/[Renderer.freeResources]'s "exact
 * context" check reduces to "has the caller declared the context gone since adoption," which is
 * exactly the fact RenG can actually know.
 *
 * The [RenGErrorCode.NO_CURRENT_RENDER_CONTEXT] and
 * [RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT] typed failures this probe can never organically
 * produce in production are still real, reachable code — through the internal
 * [createRenderer] overload above, which a test drives with an arbitrary [RenderContextProbe] to
 * prove the wiring is correct regardless of what any future, more capable probe might report.
 */
internal object ProductionRenderContextProbe : RenderContextProbe {
    private val identity = RenderContextIdentity(1L)

    override fun currentContextIdentity(): RenderContextIdentity = identity
}

/**
 * A monotonic, process-relative millisecond clock for [PreparationDriver]'s `ClockSampled` action.
 * Not wall-clock time — [kotlin.time.TimeSource.Monotonic] carries no epoch — which is fine for what
 * that action needs: comparing samples taken within one process's lifetime against each other, never
 * against a persisted timestamp from a previous run.
 */
private val processStart = TimeSource.Monotonic.markNow()
private val monotonicMillisClock: () -> Long = { processStart.elapsedNow().inWholeMilliseconds }
