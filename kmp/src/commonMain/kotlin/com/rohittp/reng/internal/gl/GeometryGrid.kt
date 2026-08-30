package com.rohittp.reng.internal.gl

import com.rohittp.reng.Geometry
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.gpuRepresentabilityFailure
import com.rohittp.reng.internal.planning.isGpuRepresentable
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.GlobeGroundFootprint
import com.rohittp.reng.internal.projection.GlobeGroundHalfSpace
import com.rohittp.reng.internal.projection.MercatorPosition
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.WGS84_SEMI_MAJOR_AXIS_METRES
import com.rohittp.reng.internal.projection.WORLD_CIRCUMFERENCE_METRES
import com.rohittp.reng.internal.projection.globeCameraRelativePosition
import com.rohittp.reng.internal.projection.globeGroundFootprint
import com.rohittp.reng.internal.projection.projectMercator
import com.rohittp.reng.internal.projection.validateMercatorGeometryPosition
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cosh

/**
 * A `Geometry` subdivided into a grid whose vertices RenG has already projected, in exactly the
 * interleaved layout [drawGeometry] uploads: `x, y, z, u, v` per vertex, camera-relative logical
 * pixels then texture coordinate.
 *
 * ## Why a grid exists at all (ADR 0008's erratum, and the spec's section 6)
 *
 * A `Geometry` is painted by a **consumer-supplied** shader pair, and under Mercator the consumer
 * writes `gl_Position = uModelViewProjection * aPosition`. A Mercator-to-sphere map is **nonlinear**
 * and therefore cannot be expressed as a matrix, so on a globe one of three things has to give: the
 * consumer's shader, the matrix, or the vertex count. RenG gives up the vertex count — it subdivides
 * the quad and projects the grid's vertices on the CPU, leaving the view-projection linear. A
 * consumer's existing shader compiles and draws in both modes with no edit, no new declared name and
 * nothing prepended. The two narrow breaks that costs — `gl_VertexID` stops enumerating four corners,
 * and `aPosition.z` carries altitude *and* curvature on a globe — are recorded in ADR 0008's erratum
 * rather than repaired.
 *
 * ## Why subdividing is safe to do in **both** modes
 *
 * A `Geometry`'s four resolved corners are always **coplanar**, and that is a property of
 * `resolveGeometry` rather than a coincidence: `projectMercator` makes Mercator `x` a function of
 * longitude alone and `z` a function of latitude and altitude alone, and `resolveGeometry` builds the
 * four corners from two latitudes and two longitudes, so the two northern corners share one `z` and
 * the two southern corners share another and `z` is affine in `y`. Four such points lie in a plane.
 * Under a projective map of a plane, an N x N grid lerped between those corners interpolates every
 * attribute to the values the four-corner quad already produced, and the interior edges change no
 * pixel's coverage under the rasterisation fill rule. `GeometryGridTest` asserts the coplanarity
 * rather than quoting it, and `runGeometrySubdivisionReadbackSuite` measures the "no pixel moves"
 * half in pixels on a real driver, because that is the claim protecting three shipped releases.
 *
 * ## What the vertex count actually depends on
 *
 * **The camera, in one expression, in both modes** — never a `when (projectionMode)`.
 * [geometryCellsPerSide] is a function of the ground's curvature radius and the quad's arc length in
 * logical pixels, and both arms supply those from their own resolved camera. The arc expression is
 * literally identical (`span * worldSizeLogicalPixels`, since arc `= R * theta` and `theta = 2 * PI *
 * span`); the curvature radius is [ResolvedGlobeCamera.radiusLogicalPixels] on a globe and **infinite**
 * on a plane, because a plane does not depart from itself. So a Mercator frame lands on one cell and
 * pays exactly what it paid in `0.3.0`, not by a mode test but because the criterion evaluates to zero
 * there. That the grid is *structurally* the same in both modes is the point ADR 0008's erratum makes;
 * that it costs nothing extra under Mercator is what keeps a shipped path shipped.
 *
 * ## What the extent depends on
 *
 * **The camera as well** (spec section 10.2). A `Geometry` is a lat/lon-bounded *region* and a globe
 * projects that region faithfully: a geometry covering India covers India, and a 360-degree span
 * covers the planet as a closed band with no seam. Nothing about the declared span is rejected or
 * clamped — `DrawnThings.kt`'s invariant is untouched. What is bounded is the **work**: a 360-degree
 * span would otherwise subdivide and project a planet's worth of grid every frame, most of it behind
 * the limb and all of it discarded by the far-hemisphere cull *after* being paid for. So the globe arm
 * descends the geometry's own domain as a quadtree to one uniform depth and prunes any sub-cell the
 * camera cannot see, against the same [GlobeGroundFootprint] that chooses the frame's basemap tiles.
 * Same shape as the ground: a tile set is chosen from the camera, not from the world.
 *
 * **The Mercator arm prunes nothing, deliberately.** Its grid is one cell, so there is nothing to
 * prune; and a cull is the one part of subdivision that is not provably output-neutral, which is
 * exactly the property three shipped releases depend on.
 *
 * **A consumer shader that moves its vertices sees the pruning.** ADR 0008 hands the shader positions
 * and a matrix and says nothing about what it does with them, so a shader that displaces `aPosition`
 * far from where RenG put it can be handed a grid pruned for where RenG put it. That is inherent in
 * bounding the work by the camera at all, and it is why the pruning is a *conservative* half-space
 * test — it drops a cell only when a plane the frame itself imposes excludes the whole cell — rather
 * than anything screen-space and tight.
 */
internal class GeometryGrid(
    val cellsPerSide: Int,
    val interleavedVertices: FloatArray,
    val triangleIndices: ShortArray,
) {
    init {
        require(cellsPerSide >= 1) { "a geometry grid has at least one cell a side" }
        require(interleavedVertices.size % GEOMETRY_VERTEX_COMPONENT_COUNT == 0) {
            "a geometry grid vertex carries exactly $GEOMETRY_VERTEX_COMPONENT_COUNT components"
        }
        require(triangleIndices.size % 3 == 0) { "a geometry grid is made of whole triangles" }
    }

    val vertexCount: Int get() = interleavedVertices.size / GEOMETRY_VERTEX_COMPONENT_COUNT

    val triangleCount: Int get() = triangleIndices.size / 3
}

/**
 * How far a chord spanning one grid cell departs from the ground it stands in for, in logical pixels.
 *
 * The finite-radius arm is [globeGroundCellDeviationLogicalPixels] — the globe ground's own criterion,
 * called rather than restated, so the ground and a geometry standing on it can never disagree about
 * how curved the world is. It is reparameterised from a cell *angle* to a cell *arc* for one reason:
 * an angle has no meaning on a plane, an arc does, and it is the arc that lets the same expression
 * serve a curvature radius of infinity.
 *
 * **An infinite radius is a plane, and a plane departs from itself by nothing.** That is what makes a
 * Mercator geometry one cell without a projection-mode test anywhere in this file. It is a limit
 * rather than a special case: `R * (1 - cos(s / 2R)) + s^2 / 16R` tends to zero as `R` grows for a
 * fixed arc `s`, and it is spelled as an early return only because IEEE evaluates the limit as
 * `inf * 0 = NaN`.
 */
internal fun geometryCellDeviationLogicalPixels(
    groundCurvatureRadiusLogicalPixels: Double,
    cellArcLogicalPixels: Double,
): Double {
    require(groundCurvatureRadiusLogicalPixels > 0.0) { "a ground curvature radius is positive" }
    require(cellArcLogicalPixels >= 0.0) { "a cell arc is not negative" }
    if (groundCurvatureRadiusLogicalPixels == Double.POSITIVE_INFINITY) return 0.0
    if (cellArcLogicalPixels == 0.0) return 0.0
    return globeGroundCellDeviationLogicalPixels(
        radiusLogicalPixels = groundCurvatureRadiusLogicalPixels,
        cellAngleRadians = cellArcLogicalPixels / groundCurvatureRadiusLogicalPixels,
    )
}

/**
 * How many cells a side a quad of [spanArcLogicalPixels] needs so that no cell departs from a ground
 * of [groundCurvatureRadiusLogicalPixels] by more than [toleranceLogicalPixels].
 *
 * A power of two, at most [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE], for the same two reasons the
 * ground's is: a 16-bit index cannot address a wider grid, and the quadtree descent that prunes
 * invisible cells needs a depth to descend to. The tolerance is the ground's too — half a logical
 * pixel cannot move a sample point into a different pixel, and rounding up to a power of two beats it
 * by another two to four times.
 */
internal fun geometryCellsPerSide(
    groundCurvatureRadiusLogicalPixels: Double,
    spanArcLogicalPixels: Double,
    toleranceLogicalPixels: Double = GLOBE_GROUND_DEVIATION_TOLERANCE_LOGICAL_PIXELS,
): Int {
    require(toleranceLogicalPixels > 0.0) { "a deviation tolerance is positive" }
    var cells = 1
    while (cells < MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE &&
        geometryCellDeviationLogicalPixels(
            groundCurvatureRadiusLogicalPixels,
            spanArcLogicalPixels / cells.toDouble(),
        ) > toleranceLogicalPixels
    ) {
        cells *= 2
    }
    return cells
}

/** [geometryCellsPerSide] for [geometry] under a plane, which is always one cell — see the class KDoc. */
internal fun geometryCellsPerSide(geometry: Geometry, camera: ResolvedMercatorCamera): Int =
    geometryCellsPerSide(
        groundCurvatureRadiusLogicalPixels = Double.POSITIVE_INFINITY,
        spanArcLogicalPixels = geometrySpanArcLogicalPixels(geometry, camera.worldSizeLogicalPixels),
    )

/** [geometryCellsPerSide] for [geometry] on the sphere [camera] orbits. */
internal fun geometryCellsPerSide(geometry: Geometry, camera: ResolvedGlobeCamera): Int =
    geometryCellsPerSide(
        groundCurvatureRadiusLogicalPixels = camera.radiusLogicalPixels,
        spanArcLogicalPixels = geometrySpanArcLogicalPixels(geometry, camera.worldSizeLogicalPixels),
    )

/**
 * How much a **draped** node stands above the ellipsoid, in metres, at one normalised Mercator
 * position.
 *
 * It is a metre count rather than a height in logical pixels because the two projections spend it
 * differently — Mercator by `1 / cos(latitude)` per vertex, a sphere radially and uniformly — and
 * because ADR 0040 states a `GROUND_RELATIVE` altitude in metres. Zero is the honest answer where the
 * frame drew no displaced ground: ADR 0040 resolves `GROUND_RELATIVE` as `ABSOLUTE` there, which is
 * exactly "add nothing".
 *
 * The lift a coplanar drape needs is folded in by whoever builds this, not by its callers — see
 * [com.rohittp.reng.internal.gl.GROUND_DRAPE_LIFT_METRES].
 */
internal fun interface GroundDrape {
    fun altitudeMetresAt(mercatorX: Double, mercatorY: Double): Double
}

/**
 * **Four metres, and every digit of it is a measurement rather than a taste.**
 *
 * A `GROUND_RELATIVE` `Geometry` at altitude 0 is a second copy of the terrain surface drawn through
 * a different pipeline, and ADR 0027's original defect is exactly what two pipelines agreeing about a
 * surface produce: a quad erased and redealt frame by frame. ADR 0039 quarantined the case into wave
 * 2 and named a mitigation -- a shared nearest-texel rule -- which task 14's spike then refuted, at
 * 61,996 survivors of a 102,400-pixel probe against 53,048 for doing nothing at all. Its 2026-08-30
 * erratum records the refutation and the replacement: **a lift is what works**, and nothing else
 * measured does.
 *
 * The spike's lift ladder, at pitch 0, zoom 13, Apple M3 Max, on the **offset lattice** -- a drape
 * whose node spacing equals the ground's but whose nodes land wherever the consumer's declared
 * corners put them:
 *
 * | lift | 0 | +0.25 m | +1 m | +2 m | **+4 m** | +16 m |
 * |---|---|---|---|---|---|---|
 * | survivors of 102,400 | 53,048 | 63,446 | 83,589 | 95,499 | **102,370** | 102,400 |
 *
 * **Why not one metre.** One metre is the *snapped* lattice's number -- the spike's arm P2, whose
 * nodes sit on the ground's own grid lines. RenG cannot have that: a `Geometry`'s corners are two
 * latitudes and two longitudes a consumer wrote down, and snapping the mesh to a lattice means moving
 * the rectangle. What RenG can have, and [drapeCellsPerSide] takes, is the ground's node *spacing* on
 * an offset phase, which is arm E's condition exactly -- so arm E's ladder is the one that applies
 * and 4 m is where it reaches 99.97 per cent.
 *
 * **Why not sixteen.** 16 m is the first whole reading, and a lift is drawn rather than notional: at
 * zoom 13 and latitude 45 a metre is about 0.15 logical pixels, so 4 m stands about half a pixel off
 * the ground and 16 m about two and a half. The 30 pixels 4 m leaves are 0.03 per cent of the probe,
 * spread along crease lines, against a defect that erases half a quad.
 *
 * **What this constant cannot be tested at the boundaries of, stated rather than discovered.** A lift
 * in metres is a fixed *height* against a depth budget that scales with zoom: the same spike measured
 * a centimetre buying 100 per cent of a relief-free ground at zoom 13 and 66 per cent at zoom 11, so
 * the direction of the error is known and its size at an arbitrary camera is not. This is the shader
 * depth bias ADR 0027 rejected wearing different clothes, chosen here because ADR 0039's erratum
 * measured the alternatives -- the shared texel rule, and drawing the drape before the ground at
 * **0** survivors under every camera -- and both are worse.
 *
 * It applies to a drape and to nothing else. A map-anchored sticker is a billboard carrying one
 * depth and a model has volume and writes its own; neither is a second copy of the terrain, and
 * lifting them would move content off the surface it asked to stand on.
 */
internal const val GROUND_DRAPE_LIFT_METRES: Double = 4.0

/**
 * How finely a draped [geometry] must be subdivided to follow the ground rather than to cut a chord
 * through it: **the ground's own node spacing**, rounded up to the grid's power-of-two shape.
 *
 * A `Geometry` under Mercator is one cell ([geometryCellsPerSide] on an infinite curvature radius),
 * which is the whole of `0.3.0`'s cost model and is exactly wrong for a drape — four corners on the
 * terrain and a flat quad between them is the "cutting through the ridge" picture ADR 0040 exists to
 * replace. On a globe the curvature claim is real but is about the sphere rather than about the
 * relief, so it is reconciled by `max` here for `groundCellsPerTileSide`'s reason: one number, chosen
 * by whichever claim is finer.
 *
 * **Matching the ground's spacing is not matching the ground's lattice, and the difference is
 * measured.** A consumer's `Geometry` carries the consumer's own lat/lons, so its nodes land wherever
 * they land inside the ground's cells; task 14's spike calls that the offset lattice and measures a
 * drape on it needing a **four metre** lift where one snapped to the ground's own grid lines needs
 * one. RenG cannot snap a declared rectangle to a lattice without moving the rectangle, so the offset
 * lattice is what [GROUND_DRAPE_LIFT_METRES] is sized against.
 */
internal fun drapeCellsPerSide(
    geometry: Geometry,
    worldSizeLogicalPixels: Double,
    groundCellsPerTileSide: Int,
    selectedLod: Int,
): Int {
    require(groundCellsPerTileSide >= 1) { "a ground grid has at least one cell a side" }
    require(selectedLod >= 0) { "a tile LOD is not negative" }
    val tileSideLogicalPixels = worldSizeLogicalPixels / (1L shl selectedLod).toDouble()
    if (!tileSideLogicalPixels.isFinite() || tileSideLogicalPixels <= 0.0) return 1
    val spanLogicalPixels = geometrySpanArcLogicalPixels(geometry, worldSizeLogicalPixels)
    if (!spanLogicalPixels.isFinite() || spanLogicalPixels <= 0.0) return 1
    val wanted = ceil(spanLogicalPixels * groundCellsPerTileSide.toDouble() / tileSideLogicalPixels)
    if (!wanted.isFinite() || wanted <= 1.0) return 1
    var cells = 1
    while (cells < MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE && cells.toDouble() < wanted) cells *= 2
    return cells
}

/**
 * [geometry] as a grid on the Mercator plane.
 *
 * Every node is a bilinear interpolation of the four corners [resolveGeometry] already produced —
 * never a re-projection of an interior latitude — which is the whole of the output-neutrality claim:
 * the corners are coplanar, so the lerped interior lies in the plane the four-corner quad already
 * rasterised. `resolveGeometry` is called rather than reimplemented so a geometry that fails to
 * resolve fails identically, with the identical diagnostic field, whether or not it is subdivided.
 *
 * [cellsPerSide] defaults to what the camera implies, which on a plane is one. It is overridable
 * because the readback gate has to force a subdivision the production path will never choose in order
 * to measure that it changes no pixel.
 */
internal fun geometryGrid(
    geometry: Geometry,
    camera: ResolvedMercatorCamera,
    cellsPerSide: Int = geometryCellsPerSide(geometry, camera),
    drape: GroundDrape? = null,
): SpatialOutcome<GeometryGrid> {
    val resolvedOutcome = resolveGeometry(geometry, camera)
    if (resolvedOutcome is SpatialOutcome.Failure) return resolvedOutcome
    val corners = (resolvedOutcome as SpatialOutcome.Success).value.cornersClockwiseFromTopLeft
    val topLeft = corners[0]
    val topRight = corners[1]
    val bottomRight = corners[2]
    val bottomLeft = corners[3]

    if (drape == null) {
        return assembleGeometryGrid(
            cellsPerSide = cellsPerSide,
            admitsCell = { _, _, _, _ -> true },
            nodePosition = { u, v ->
                SpatialOutcome.Success(
                    lerp(lerp(topLeft, topRight, u), lerp(bottomLeft, bottomRight, u), v),
                )
            },
        )
    }

    val boundsOutcome = validatedGeometryMercatorBounds(geometry)
    if (boundsOutcome is SpatialOutcome.Failure) return boundsOutcome
    val bounds = (boundsOutcome as SpatialOutcome.Success).value
    val logicalPixelsPerEquatorialMetre = camera.worldSizeLogicalPixels / WORLD_CIRCUMFERENCE_METRES

    return assembleGeometryGrid(
        cellsPerSide = cellsPerSide,
        foldsOnTheGroundsDiagonal = true,
        admitsCell = { _, _, _, _ -> true },
        nodePosition = { u, v ->
            val flat = lerp(lerp(topLeft, topRight, u), lerp(bottomLeft, bottomRight, u), v)
            val mercatorY = lerp(bounds.northY, bounds.southY, v)
            // `cosh(PI * (1 - 2y))` is `1 / cos(latitude)` written in the frame the ground's own
            // vertex shader writes it in, so the drape's rise and the ground's are the same
            // expression evaluated at the same point rather than two derivations that agree today.
            val rise = drape.altitudeMetresAt(lerp(bounds.westX, bounds.eastX, u), mercatorY) *
                logicalPixelsPerEquatorialMetre * cosh(PI * (1.0 - 2.0 * mercatorY))
            val draped = DoubleVector3(flat.x, flat.y, flat.z + rise)
            if (!isGpuRepresentable(draped.z)) {
                gpuRepresentabilityFailure(DiagnosticField.GEOMETRY_ALTITUDE)
            } else {
                SpatialOutcome.Success(draped)
            }
        },
    )
}

/**
 * [geometry] as a grid on the sphere [camera] orbits, pruned to what [camera] can see.
 *
 * Each node is projected through [globeCameraRelativePosition] from a Mercator coordinate lerped
 * across the quad — Mercator `x` in `u`, Mercator `y` in `v`, and **altitude in metres** in `v`. That
 * parameterisation is chosen to agree with the Mercator arm's rather than to be natural on a sphere:
 * `CONTEXT.md` specifies that altitude interpolates north to south, and Mercator `y` is affine in the
 * isometric latitude the globe ground's own grid interpolates, so a geometry and the ground beneath it
 * are parameterised identically and a cross-mode comparison compares the same points.
 *
 * The four corners are validated first, in `resolveGeometry`'s own order, so that an out-of-domain
 * geometry fails with the same code, stage and diagnostic field in both modes.
 */
internal fun geometryGrid(
    geometry: Geometry,
    camera: ResolvedGlobeCamera,
    cellsPerSide: Int = geometryCellsPerSide(geometry, camera),
    drape: GroundDrape? = null,
): SpatialOutcome<GeometryGrid> {
    val boundsOutcome = validatedGeometryMercatorBounds(geometry)
    if (boundsOutcome is SpatialOutcome.Failure) return boundsOutcome
    val bounds = (boundsOutcome as SpatialOutcome.Success).value

    val footprint = geometryVisibilityFootprint(
        camera = camera,
        northAltitudeMetres = geometry.topLeft.z,
        southAltitudeMetres = geometry.bottomRight.z,
    )

    return assembleGeometryGrid(
        cellsPerSide = cellsPerSide,
        foldsOnTheGroundsDiagonal = drape != null,
        // The descent hands out the cell's own `(u, v)` corners, so they are lerped into Mercator
        // coordinates here: the footprint decides in the frame the basemap tiles are cut in, which
        // is what lets a geometry and the ground beneath it be culled by one machine.
        admitsCell = { westU, eastU, northV, southV ->
            footprint.mayAdmitMercatorCell(
                minimumX = lerp(bounds.westX, bounds.eastX, westU),
                maximumX = lerp(bounds.westX, bounds.eastX, eastU),
                minimumY = lerp(bounds.northY, bounds.southY, northV).coerceIn(bounds.northY, bounds.southY),
                maximumY = lerp(bounds.northY, bounds.southY, southV).coerceIn(bounds.northY, bounds.southY),
            )
        },
        nodePosition = { u, v ->
            val mercatorX = lerp(bounds.westX, bounds.eastX, u)
            // The convex combination below can leave the Mercator domain by a single ULP when both
            // endpoints sit on it, and `unitSphereDirection` refuses a `y` outside [0, 1] rather than
            // returning a plausible wrong answer. This is that ULP and nothing wider: it clamps to the
            // geometry's OWN declared band, so it cannot hide a coordinate that is actually out of
            // range.
            val mercatorY = lerp(bounds.northY, bounds.southY, v).coerceIn(bounds.northY, bounds.southY)
            val position = globeCameraRelativePosition(
                camera = camera,
                mercatorX = mercatorX,
                mercatorY = mercatorY,
                // A sphere displaces radially and by the same fraction everywhere, so the drape's
                // metres go in where the declared altitude's do rather than being converted twice --
                // `projectGlobe` owns the one conversion and a second one here would be the
                // `1 / cos(latitude)` term `globeMetresToLogicalPixels` forbids on a globe.
                altitudeMetres = lerp(bounds.northAltitudeMetres, bounds.southAltitudeMetres, v) +
                    (drape?.altitudeMetresAt(mercatorX, mercatorY) ?: 0.0),
            )
            if (!isGpuRepresentable(position.x)) {
                gpuRepresentabilityFailure(DiagnosticField.GEOMETRY_UNWRAPPED_LONGITUDE)
            } else if (!isGpuRepresentable(position.y)) {
                gpuRepresentabilityFailure(DiagnosticField.GEOMETRY_LATITUDE)
            } else if (!isGpuRepresentable(position.z)) {
                gpuRepresentabilityFailure(DiagnosticField.GEOMETRY_ALTITUDE)
            } else {
                SpatialOutcome.Success(position)
            }
        },
    )
}

/**
 * The camera's visible-ground half-spaces, **relaxed to the radial band the geometry occupies**.
 *
 * [globeGroundFootprint] normalises every offset onto the unit sphere by dividing by the ground
 * radius, so its verdicts are about points sitting exactly on the surface. A geometry at altitude does
 * not: raise it and it clears the limb sooner, drop it and it clears later. A point at radius `k * R`
 * satisfies `n . u >= offset / k`, and `offset / k` is monotonic in `k`, so admitting the whole band
 * means taking the offset at whichever end of it is weakest. `k = 1 + altitude / a` — the radial scale
 * is `R / a` metres per logical pixel, so the *ratio* depends on altitude alone and not on the zoom.
 *
 * Without this, a geometry raised above the ground near the limb would be culled while visibly on
 * screen — a hole that only appears at one camera angle, which is the class of defect a readback case
 * exists to catch. A degenerate band (a geometry below the planet's own centre) drops every constraint
 * rather than inverting one, which fails towards drawing.
 */
private fun geometryVisibilityFootprint(
    camera: ResolvedGlobeCamera,
    northAltitudeMetres: Double,
    southAltitudeMetres: Double,
): GlobeGroundFootprint {
    val footprint = globeGroundFootprint(camera)
    val scales = listOf(northAltitudeMetres, southAltitudeMetres)
        .map { 1.0 + it / WGS84_SEMI_MAJOR_AXIS_METRES }
        .filter { it.isFinite() && it > 0.0 }
        .map { 1.0 / it }
    if (scales.isEmpty()) return GlobeGroundFootprint(emptyList())
    return GlobeGroundFootprint(
        footprint.halfSpaces.map { halfSpace ->
            GlobeGroundHalfSpace(
                normal = halfSpace.normal,
                offset = scales.minOf { halfSpace.offset * it },
            )
        },
    )
}

/**
 * The one grid assembly both modes share.
 *
 * The quadtree descent is what makes the extent camera-bounded without the cost of the extent it
 * rejects: a subtree the camera cannot see is dropped at the level it becomes invisible, so a
 * 360-degree geometry costs its visible cells and the frontier around them rather than
 * `cellsPerSide^2`. It descends to **one uniform depth** rather than stopping early where a coarse
 * cell would do, because two neighbouring cells subdivided differently leave a T-junction and a
 * sliver of background through it — MapLibre's most expensive globe bug, and still a comment in its
 * source.
 *
 * A node is projected **once** however many of the four cells around it survive, and only if one
 * does. That is the second half of the same economy, and it is why the vertices are emitted through a
 * dedupe map rather than as a dense `(cellsPerSide + 1)^2` array.
 *
 * ## The diagonal, which is two rules because one of them is three releases old
 *
 * By default a cell folds on its **north-west to south-east** diagonal, which is the diagonal the
 * four-corner triangle strip this grid replaced already used (`bottomLeft, bottomRight, topLeft,
 * topRight` strips into a shared `bottomRight`-`topLeft` edge). Nobody ever wrote that choice down —
 * it arrived with the strip — and [groundGridIndices] folds the *other* way, so a `Geometry` and the
 * ground beneath it have disagreed about the diagonal since the ground got a grid. Task 14's spike
 * measured what that disagreement costs a drape
 * (`docs/research/2026-08-30-e-terrain-coplanar-depth-spike.md`): matching the ground's lattice *and*
 * its triangulation takes the lift a whole coplanar drape needs from four metres to one.
 *
 * [foldsOnTheGroundsDiagonal] therefore switches a cell to `(NW, SW, NE)` then `(NE, SW, SE)` —
 * [groundGridIndices]' own order — and **only a drape sets it.** Flipping it for every geometry was
 * measured rather than reasoned about, and it is not free: `runGeometrySubdivisionReadbackSuite`'s
 * one-cell case compares the grid against that shipped four-corner strip and requires **byte**
 * identity, and the flip moves **39 pixels on `Apple M3 Max` and 8 on `Apple Software Renderer`,
 * with the coverage of every one of them unchanged.** That is the shape the projective argument
 * predicts — the two triangulations of a plane cover exactly the same pixels, and what moves is which
 * triangle a pixel on the fold belongs to and therefore how its attributes interpolate — but it is
 * still a change to a frame three published releases have drawn, for no gain to those frames. A
 * draped `Geometry` has drawn no released frame at all, so it pays nothing to adopt the ground's
 * diagonal and the undraped path keeps the one it shipped with.
 *
 * On a globe the four nodes of a cell are genuinely not coplanar, so there the two diagonals describe
 * two different surfaces rather than one surface cut two ways — another reason the switch is per-grid
 * rather than global.
 */
private fun assembleGeometryGrid(
    cellsPerSide: Int,
    /** Whether the cell whose grid coordinates are `(westU, eastU, northV, southV)` may be seen. */
    admitsCell: (Double, Double, Double, Double) -> Boolean,
    nodePosition: (Double, Double) -> SpatialOutcome<DoubleVector3>,
    foldsOnTheGroundsDiagonal: Boolean = false,
): SpatialOutcome<GeometryGrid> {
    require(cellsPerSide >= 1 && cellsPerSide <= MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE) {
        "a geometry grid needs between one and $MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE cells a side"
    }
    require(cellsPerSide and (cellsPerSide - 1) == 0) {
        "a geometry grid's cells a side is a power of two"
    }

    val nodesPerSide = cellsPerSide + 1
    val vertices = ArrayList<Float>(nodesPerSide * nodesPerSide * GEOMETRY_VERTEX_COMPONENT_COUNT)
    val indices = ArrayList<Short>(cellsPerSide * cellsPerSide * 6)
    val emitted = HashMap<Int, Int>()
    var failure: SpatialOutcome.Failure? = null

    fun node(column: Int, row: Int): Int {
        val key = row * nodesPerSide + column
        emitted[key]?.let { return it }
        val u = column.toDouble() / cellsPerSide.toDouble()
        val v = row.toDouble() / cellsPerSide.toDouble()
        val position = when (val outcome = nodePosition(u, v)) {
            is SpatialOutcome.Failure -> {
                if (failure == null) failure = outcome
                return -1
            }
            is SpatialOutcome.Success -> outcome.value
        }
        val index = vertices.size / GEOMETRY_VERTEX_COMPONENT_COUNT
        vertices += position.x.toFloat()
        vertices += position.y.toFloat()
        vertices += position.z.toFloat()
        vertices += u.toFloat()
        vertices += v.toFloat()
        emitted[key] = index
        return index
    }

    fun emitCell(column: Int, row: Int) {
        val northWest = node(column, row)
        val northEast = node(column + 1, row)
        val southWest = node(column, row + 1)
        val southEast = node(column + 1, row + 1)
        if (failure != null) return
        if (foldsOnTheGroundsDiagonal) {
            indices += northWest.toShort()
            indices += southWest.toShort()
            indices += northEast.toShort()
            indices += northEast.toShort()
            indices += southWest.toShort()
            indices += southEast.toShort()
        } else {
            indices += northWest.toShort()
            indices += southWest.toShort()
            indices += southEast.toShort()
            indices += northWest.toShort()
            indices += southEast.toShort()
            indices += northEast.toShort()
        }
    }

    fun descend(level: Int, column: Int, row: Int) {
        if (failure != null) return
        val cellsAtLevel = 1 shl level
        val west = column.toDouble() / cellsAtLevel.toDouble()
        val east = (column + 1).toDouble() / cellsAtLevel.toDouble()
        val north = row.toDouble() / cellsAtLevel.toDouble()
        val south = (row + 1).toDouble() / cellsAtLevel.toDouble()
        if (!admitsCell(west, east, north, south)) return
        if (cellsAtLevel == cellsPerSide) {
            emitCell(column, row)
            return
        }
        for (childRow in 0..1) {
            for (childColumn in 0..1) {
                descend(level + 1, column * 2 + childColumn, row * 2 + childRow)
            }
        }
    }

    descend(0, 0, 0)
    failure?.let { return it }

    return SpatialOutcome.Success(
        GeometryGrid(
            cellsPerSide = cellsPerSide,
            interleavedVertices = FloatArray(vertices.size) { vertices[it] },
            triangleIndices = ShortArray(indices.size) { indices[it] },
        ),
    )
}

/**
 * The geometry's Mercator box and altitudes, with every corner validated in `resolveGeometry`'s own
 * order so the globe arm and the Mercator arm reject the identical geometries identically.
 */
private fun validatedGeometryMercatorBounds(geometry: Geometry): SpatialOutcome<GeometryMercatorBounds> {
    val geographicCorners = listOf(
        GeographicPosition(geometry.topLeft.x, geometry.topLeft.y, geometry.topLeft.z),
        GeographicPosition(geometry.topLeft.x, geometry.bottomRight.y, geometry.topLeft.z),
        GeographicPosition(geometry.bottomRight.x, geometry.bottomRight.y, geometry.bottomRight.z),
        GeographicPosition(geometry.bottomRight.x, geometry.topLeft.y, geometry.bottomRight.z),
    )
    val projected = ArrayList<MercatorPosition>(4)
    for (corner in geographicCorners) {
        val outcome = validateMercatorGeometryPosition(corner)
        if (outcome is SpatialOutcome.Failure) return outcome
        projected += (outcome as SpatialOutcome.Success).value
    }
    return SpatialOutcome.Success(
        GeometryMercatorBounds(
            westX = projected[0].x,
            eastX = projected[2].x,
            northY = projected[0].y,
            southY = projected[2].y,
            northAltitudeMetres = geometry.topLeft.z,
            southAltitudeMetres = geometry.bottomRight.z,
        ),
    )
}

private class GeometryMercatorBounds(
    val westX: Double,
    val eastX: Double,
    val northY: Double,
    val southY: Double,
    val northAltitudeMetres: Double,
    val southAltitudeMetres: Double,
)

/**
 * The quad's larger side as an arc in logical pixels.
 *
 * `arc = R * theta` and `theta = 2 * PI * span` in normalised Mercator units, so the arc is
 * `span * worldSizeLogicalPixels` and the radius cancels — which is why this one expression serves a
 * plane, where a spherical angle would mean nothing. The larger of the two sides is taken because a
 * cell is square in the grid's own `(u, v)` and the criterion has to hold for its worst axis.
 */
private fun geometrySpanArcLogicalPixels(geometry: Geometry, worldSizeLogicalPixels: Double): Double {
    val longitudeSpan = (geometry.bottomRight.y - geometry.topLeft.y) / 360.0
    val latitudeSpan = mercatorYSpan(geometry)
    val span = if (longitudeSpan > latitudeSpan) longitudeSpan else latitudeSpan
    return span * worldSizeLogicalPixels
}

private fun mercatorYSpan(geometry: Geometry): Double {
    val north = projectMercator(
        GeographicPosition(geometry.topLeft.x, geometry.topLeft.y, 0.0),
    ).y
    val south = projectMercator(
        GeographicPosition(geometry.bottomRight.x, geometry.bottomRight.y, 0.0),
    ).y
    return south - north
}

private fun lerp(start: Double, end: Double, t: Double): Double = start * (1.0 - t) + end * t

/**
 * The two-term form, because only this one returns **both** endpoints exactly by construction: at
 * `t = 1` the other spelling returns `start + (end - start)`, which is `end` only up to a rounding.
 *
 * **It is exact by construction rather than by measurement, and the difference matters to how this
 * comment is written.** Replacing both spellings with `start + (end - start) * t` was run as a
 * mutation and **survived all 1,490 tests**, so it is recorded here as an equivalent mutant rather
 * than as a gap: over the two fixtures at every granularity from 1 to 32 cells a side, **zero** of
 * up to 3,267 narrowed `Float` components differ between the two forms. The two can disagree by one
 * `Double` ULP, and `Double`'s ULP is 2^-29 of `Float`'s, so what reaches a driver is the same
 * bytes. Keeping this form buys exactness that holds for every input rather than for the ones
 * measured; it does not buy a pixel, and a comment claiming otherwise would be false.
 */
private fun lerp(start: DoubleVector3, end: DoubleVector3, t: Double): DoubleVector3 =
    start * (1.0 - t) + end * t
