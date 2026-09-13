package com.rohittp.reng

import kotlin.jvm.JvmInline

@JvmInline
public value class FramebufferName(public val value: UInt)

public sealed interface PreparedFrame : AutoCloseable {
    public val frameIndex: Long

    override fun close(): Unit
}

public sealed interface RenderTarget {
    public val framebufferName: FramebufferName
}

/**
 * Everything a renderer is fixed by for its whole life: where it draws, what it draws through, and
 * the handful of options that are properties of the renderer rather than of any one [FramePlan].
 *
 * **A plain class rather than a `data class`, deliberately**, so a new option costs one constructor
 * parameter and one property and no `copy`/`componentN` fallout on the public ABI.
 */
public class RendererConfiguration(
    outputPixelSize: OutputPixelSize,
    transport: Transport,
    store: Store,
    basemapStyle: ResourceLocator? = null,
    resourceLimits: ResourceLimits = ResourceLimits(),
    maximumBasemapTileInstances: Int = 512,
    maximumPreparationBatchSize: Int = 256,
    maximumConcurrentResourceOperations: Int = 8,
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
    terrainShading: Boolean = false,
) {
    public val outputPixelSize: OutputPixelSize
    public val transport: Transport
    public val store: Store
    public val basemapStyle: ResourceLocator?
    public val resourceLimits: ResourceLimits
    public val maximumBasemapTileInstances: Int
    public val maximumPreparationBatchSize: Int
    public val maximumConcurrentResourceOperations: Int
    public val diagnosticSink: DiagnosticSink

    /**
     * Whether the displaced ground is shaded from a normal derived from the elevation data, **off by
     * default**.
     *
     * **Off is the default because it is what the styles asked for.** MapLibre does not shade terrain
     * either: it displaces, and relief shading comes from a `hillshade` layer the basemap engine
     * draws into the tile image. Of the 34 styles RenG is verified against, the 3 that wanted relief
     * shading declared `hillshade` and the 6 that declare `terrain` declined it — two perfectly
     * disjoint sets. Inventing light a style never requested is the divergence RenG's firewall
     * posture exists to prevent, so off, the ground stays unlit exactly as ADR 0026 says and draws
     * the pixels three published releases drew.
     *
     * **It exists at all because two of those six styles are pure vector** — 10 and 55 fill layers,
     * no raster layer — and a flat fill reads as flat however far it moves. The other four carry
     * satellite imagery whose pixels already contain relief shading.
     *
     * On, the ground is lit by ADR 0026's one directional light — azimuth 335 degrees, elevation 45
     * degrees, with its ambient term — rather than by a second lighting concept, and **normalised so
     * that ground with no relief is left exactly alone**: turning this on changes the pixels of
     * slopes and of nothing else. It is a property of the renderer rather than of a [FramePlan], so
     * it cannot vary frame to frame — the ground's displacing program is compiled with or without it
     * at setup, which is what makes "off draws the same bytes" a fact about which program ran rather
     * than an argument about floating point.
     *
     * This is **not** the style's own `lights` block. That compiles to a flat colour multiplier
     * independent of `terrain` entirely; it tints, and it cannot make relief visible.
     */
    public val terrainShading: Boolean

    init {
        require(maximumBasemapTileInstances in 1..4096) {
            "maximumBasemapTileInstances must be within the supported range"
        }
        require(maximumPreparationBatchSize in 1..4096) {
            "maximumPreparationBatchSize must be within the supported range"
        }
        require(maximumConcurrentResourceOperations in 1..64) {
            "maximumConcurrentResourceOperations must be within the supported range"
        }

        this.outputPixelSize = outputPixelSize
        this.transport = transport
        this.store = store
        this.basemapStyle = basemapStyle
        this.resourceLimits = resourceLimits
        this.maximumBasemapTileInstances = maximumBasemapTileInstances
        this.maximumPreparationBatchSize = maximumPreparationBatchSize
        this.maximumConcurrentResourceOperations = maximumConcurrentResourceOperations
        this.diagnosticSink = diagnosticSink
        this.terrainShading = terrainShading
    }
}

public sealed interface Renderer : AutoCloseable {
    /**
     * [priority] chooses the engine's own resource-gate lane for this frame's basemap tiles (ADR
     * 0053). It orders nothing inside RenG, which still refuses a second concurrent preparation
     * rather than queueing it, and it reaches only the basemap: a style, a sticker and a model GLB
     * are acquired on RenG's driver path, where there is no lane to put them in.
     *
     * Declared last, and that is an ABI decision: every parameter before it is positional in shipped
     * consumer code.
     */
    public suspend fun prepare(
        plan: FramePlan,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
        priority: RenGRenderPriority = RenGRenderPriority.NORMAL,
    ): PreparedFrame

    /**
     * [priority] applies to every frame in [plans] alike. A batch is where a consumer most often
     * knows which frame it needs first, and the way to say so is two calls — the urgent frame, then
     * the rest — rather than a per-plan priority that would make one batch mean several engine
     * lanes at once.
     */
    public suspend fun prepareBatch(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
        priority: RenGRenderPriority = RenGRenderPriority.NORMAL,
    ): List<PreparedFrame>

    public suspend fun cancelPreparations(): Unit

    public fun clearFrameHistory(): Unit

    public fun queryResources(selector: ResourceSelector = ResourceSelector.All): ResourceReport

    /**
     * Every counter the basemap engine has reported to this renderer, as a running total since it
     * was created (ADR 0049). A closed renderer answers an empty report, exactly as
     * [queryResources] does.
     */
    public fun queryMetrics(): MetricReport

    public fun freeResources(selector: ResourceSelector = ResourceSelector.All): ResourceFreeResult

    public fun notifyGpuObjectsGone(): Unit

    public fun adoptCurrentRenderContext(): Unit

    public fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget

    public fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget): Unit

    override fun close(): Unit
}
