package com.rohittp.reng

import com.rohittp.reng.internal.freshListCopy

@kotlinx.serialization.Serializable
public enum class ProjectionMode {
    @kotlinx.serialization.SerialName("MERCATOR")
    MERCATOR,
    @kotlinx.serialization.SerialName("GLOBE")
    GLOBE,
}

@kotlinx.serialization.Serializable(with = FramePlanSerializer::class)
public class FramePlan(
    frameIndex: Long,
    camera: Camera,
    projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
    drawBasemap: Boolean = true,
    drawLabels: Boolean = true,
    stickers: List<Sticker> = emptyList(),
    models: List<Model> = emptyList(),
    geometries: List<Geometry> = emptyList(),
    /**
     * What this frame paints behind everything else it draws, or `null` for none (ADR 0068).
     *
     * **`null` is not a degraded backdrop, it is the absence of the pass**, and it is what every
     * frame written before this field existed means: the surface keeps being cleared to transparent
     * black, so a consumer compositing RenG's output over its own background does not start
     * receiving opaque pixels because it upgraded.
     *
     * **Declared last, and that is an ABI decision** -- the same one `ResourceLimits` records for its
     * own trailing fields: every parameter before it is positional in shipped consumer code.
     */
    backdrop: Backdrop? = null,
) {
    public val frameIndex: Long
    public val camera: Camera
    public val projectionMode: ProjectionMode
    public val drawBasemap: Boolean

    /**
     * Whether this frame draws the basemap's labels.
     *
     * **Fully orthogonal to [drawBasemap].** All four pairings are legal frames, including
     * `drawBasemap = false` with `drawLabels = true` — labels over a caller-drawn background is a
     * frame RenG supports rather than a contradiction it refuses. Nothing validates the pair, and no
     * error code names any combination of the two.
     */
    public val drawLabels: Boolean
    private val stickerSnapshot: ArrayList<Sticker>
    private val modelSnapshot: ArrayList<Model>
    private val geometrySnapshot: ArrayList<Geometry>
    public val stickers: List<Sticker>
        get() = freshListCopy(stickerSnapshot)
    public val models: List<Model>
        get() = freshListCopy(modelSnapshot)
    public val geometries: List<Geometry>
        get() = freshListCopy(geometrySnapshot)
    public val backdrop: Backdrop?

    init {
        require(frameIndex >= 0L) { "frameIndex must be non-negative" }
        val validatedCamera = camera
        val validatedProjectionMode = projectionMode
        val stickerCopy = ArrayList(stickers)
        val modelCopy = ArrayList(models)
        val geometryCopy = ArrayList(geometries)

        this.frameIndex = frameIndex
        this.camera = validatedCamera
        this.projectionMode = validatedProjectionMode
        this.drawBasemap = drawBasemap
        this.drawLabels = drawLabels
        this.stickerSnapshot = stickerCopy
        this.modelSnapshot = modelCopy
        this.geometrySnapshot = geometryCopy
        this.backdrop = backdrop
    }

    override fun equals(other: Any?): Boolean =
        other is FramePlan &&
            frameIndex == other.frameIndex &&
            camera == other.camera &&
            projectionMode == other.projectionMode &&
            drawBasemap == other.drawBasemap &&
            drawLabels == other.drawLabels &&
            stickerSnapshot == other.stickerSnapshot &&
            modelSnapshot == other.modelSnapshot &&
            geometrySnapshot == other.geometrySnapshot &&
            backdrop == other.backdrop

    override fun hashCode(): Int {
        var result = frameIndex.hashCode()
        result = 31 * result + camera.hashCode()
        result = 31 * result + projectionMode.hashCode()
        result = 31 * result + drawBasemap.hashCode()
        result = 31 * result + drawLabels.hashCode()
        result = 31 * result + stickerSnapshot.hashCode()
        result = 31 * result + modelSnapshot.hashCode()
        result = 31 * result + geometrySnapshot.hashCode()
        result = 31 * result + backdrop.hashCode()
        return result
    }
}

internal fun FramePlan.stickersForCore(): List<Sticker> = stickers

internal fun FramePlan.modelsForCore(): List<Model> = models

internal fun FramePlan.geometriesForCore(): List<Geometry> = geometries

internal fun FramePlan.backdropForCore(): Backdrop? = backdrop
