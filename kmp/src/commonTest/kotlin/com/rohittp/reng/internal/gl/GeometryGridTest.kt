package com.rohittp.reng.internal.gl

import com.rohittp.reng.Camera
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.WGS84_SEMI_MAJOR_AXIS_METRES
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle G task 9: a `Geometry` subdivided and CPU-projected, so a consumer's shader pair survives a
 * globe unchanged.
 *
 * The pixel half of this task's gate is `runGeometrySubdivisionReadbackSuite`, which measures on a
 * real driver the one claim nothing here can: that subdividing under Mercator moves no pixel. What
 * this file measures is everything upstream of the rasteriser — the coplanarity the whole design
 * rests on, the vertex and triangle counts, and the two things the camera bounds.
 */
class GeometryGridTest {

    // --- the claim the design rests on, verified against GeometryResolver rather than quoted -----

    /**
     * ADR 0008's erratum says a `Geometry`'s four resolved corners are **always coplanar**, and
     * everything else follows from it: coplanar corners are what make a lerped grid reproduce the
     * four-corner quad exactly, which is what lets RenG subdivide under Mercator at all.
     *
     * The fixture is chosen so the claim is not true by accident. Both altitudes are non-zero **and
     * different from each other**, so the quad is genuinely tilted out of the `z = 0` plane rather
     * than lying in it — a zero-altitude fixture would pass against any implementation that put four
     * points on the map plane, which is most wrong ones. The latitude is high enough that
     * `projectMercator`'s own `1 / cos(latitude)` altitude scaling differs measurably between the
     * northern and southern edges.
     *
     * The perturbation is the discriminating half: the identical measurement, applied to the
     * identical quad with one corner lifted by a hundredth of a logical pixel, must report a
     * departure four orders of magnitude larger. Without it this case would pass against a
     * measurement that always returns zero.
     */
    @Test
    fun theFourResolvedCornersAreCoplanarAndTheMeasurementCanTellWhenTheyAreNot() {
        val camera = mercatorCamera(zoom = 12.0)
        val corners = resolvedCorners(tiltedGeometry(), camera)

        val departure = planeDeparture(corners[0], corners[1], corners[3], corners[2])
        println("RenG geometry grid: resolved-corner plane departure = $departure logical pixels")
        assertTrue(
            departure < 1e-6,
            "ADR 0008's erratum: a Geometry's four resolved corners are coplanar, measured $departure",
        )

        val lifted = corners[2] + DoubleVector3(0.0, 0.0, 0.01)
        val liftedDeparture = planeDeparture(corners[0], corners[1], corners[3], lifted)
        assertTrue(
            liftedDeparture > 1e-3,
            "the same measurement must see a corner lifted out of the plane, measured $liftedDeparture",
        )
    }

    /**
     * The consequence: every interior node of a Mercator grid lies in that same plane, because it is
     * a convex combination of four points that already do. Measured over all 81 nodes of an
     * eight-cell grid, in the `Float` the driver actually receives.
     *
     * The tolerance is a hundredth of a logical pixel rather than zero because the grid stores
     * `Float`, and the globe case below is what stops that tolerance being a licence: the identical
     * measurement on the identical geometry, projected onto a sphere, reports tens of pixels.
     */
    @Test
    fun everyMercatorGridNodeLiesInThePlaneOfTheFourCorners() {
        val camera = mercatorCamera(zoom = 12.0)
        val geometry = tiltedGeometry()
        val corners = resolvedCorners(geometry, camera)
        val grid = (geometryGrid(geometry, camera, cellsPerSide = 8) as SpatialOutcome.Success).value

        val worst = grid.nodes().maxOf { planeDeparture(corners[0], corners[1], corners[3], it) }
        println("RenG geometry grid: worst Mercator node plane departure = $worst logical pixels")
        assertTrue(worst < 0.01, "subdivision must not move a Mercator vertex off the quad, measured $worst")
    }

    /**
     * The same measurement on a globe, at the zoom and span where curvature genuinely reads.
     *
     * The spec's own sagitta numbers say a frame-sized quad bows 27.9 logical pixels at zoom 4 and
     * 0.44 at zoom 10, so a fixture above about zoom 12 would report a globe and a tangent plane as
     * the same picture. This one is at zoom 2.5, spans 30 degrees of longitude and 20 of latitude,
     * and sits at latitude 55 — nowhere near the equator, where a sphere and a plane agree best.
     * The zoom is the one at which the whole quad still fits in the frame, so no cell of it is
     * pruned and the departure is measured over the entire grid rather than over a survivor set.
     */
    @Test
    fun aGlobeGridBendsWhereAMercatorGridStaysFlat() {
        val geometry = curvedGeometry()
        val globe = globeCamera(zoom = WHOLE_GEOMETRY_IN_VIEW_ZOOM, latitude = 55.0)
        val grid = (geometryGrid(geometry, globe, cellsPerSide = 8) as SpatialOutcome.Success).value

        val worst = grid.nodes().maxOf {
            planeDeparture(grid.nodeAt(0f, 0f), grid.nodeAt(1f, 0f), grid.nodeAt(0f, 1f), it)
        }
        println("RenG geometry grid: worst globe node plane departure = $worst logical pixels")
        assertTrue(
            worst > 10.0,
            "at zoom 4 a 30-degree quad must bow visibly off its own corner plane, measured $worst",
        )
    }

    // --- a single-quad "grid" is not a grid ------------------------------------------------------

    @Test
    fun aGridsVertexAndTriangleCountsAreTheGridsOwn() {
        val camera = mercatorCamera(zoom = 12.0)
        val geometry = tiltedGeometry()

        val one = (geometryGrid(geometry, camera, cellsPerSide = 1) as SpatialOutcome.Success).value
        assertEquals(4, one.vertexCount)
        assertEquals(2, one.triangleCount)

        val eight = (geometryGrid(geometry, camera, cellsPerSide = 8) as SpatialOutcome.Success).value
        assertEquals(81, eight.vertexCount, "an eight-cell grid has (8 + 1)^2 nodes, each emitted once")
        assertEquals(128, eight.triangleCount, "and two triangles for each of its 64 cells")

        val thirtyTwo = (geometryGrid(geometry, camera, cellsPerSide = 32) as SpatialOutcome.Success).value
        assertEquals(33 * 33, thirtyTwo.vertexCount)
        assertEquals(2 * 32 * 32, thirtyTwo.triangleCount)
    }

    /**
     * The grid's boundary nodes are `resolveGeometry`'s own corners, bit for bit.
     *
     * **This case cannot see which lerp spelling produced them, and that was measured rather than
     * assumed.** Swapping the two-term form for `start + (end - start) * t` survives every test in
     * the module: the two disagree by at most one `Double` ULP, and `Double`'s ULP is 2^-29 of
     * `Float`'s, so zero narrowed components differ over both fixtures at every granularity. The
     * mutant is equivalent in what reaches a driver, and the exact form is kept because it is exact
     * by construction for every input rather than by luck on the inputs measured.
     *
     * What this case does catch is anything that reorders or re-derives a corner rather than passing
     * it through — swapping the emitted texture coordinates fails here, because a node is identified
     * by its uv and nothing else.
     */
    @Test
    fun aGridsCornerNodesAreTheResolvedCornersBitExactly() {
        val camera = mercatorCamera(zoom = 12.0)
        val geometry = tiltedGeometry()
        val corners = resolvedCorners(geometry, camera)
        val grid = (geometryGrid(geometry, camera, cellsPerSide = 8) as SpatialOutcome.Success).value

        // Looked up by texture coordinate rather than by index: the grid is emitted in the order the
        // quadtree descent that prunes invisible cells happens to reach its leaves, and its uv is
        // the only thing that identifies a node.
        listOf(
            Triple(0f, 0f, 0), Triple(1f, 0f, 1), Triple(1f, 1f, 2), Triple(0f, 1f, 3),
        ).forEach { (u, v, corner) ->
            val node = grid.nodeAt(u, v)
            assertEquals(corners[corner].x.toFloat(), node.x.toFloat(), "node ($u, $v) x")
            assertEquals(corners[corner].y.toFloat(), node.y.toFloat(), "node ($u, $v) y")
            assertEquals(corners[corner].z.toFloat(), node.z.toFloat(), "node ($u, $v) z")
        }
    }

    // --- the camera decides the density, in one expression, in both modes ------------------------

    /**
     * A plane does not depart from itself, so the criterion evaluates to zero under Mercator and a
     * Mercator frame lands on one cell — not by a projection-mode test, but by the same expression
     * the globe uses with an infinite curvature radius.
     *
     * A continent-sized geometry at zoom 2 is included because it is the case that would expose a
     * criterion secretly keyed to the quad's size rather than to the ground's curvature.
     */
    @Test
    fun aMercatorFrameSubdividesToOneCellBecauseAPlaneDoesNotDepartFromItself() {
        assertEquals(0.0, geometryCellDeviationLogicalPixels(Double.POSITIVE_INFINITY, 1e9))
        assertEquals(1, geometryCellsPerSide(tiltedGeometry(), mercatorCamera(zoom = 12.0)))
        assertEquals(1, geometryCellsPerSide(curvedGeometry(), mercatorCamera(zoom = 2.0)))
        assertEquals(1, geometryCellsPerSide(bandGeometry(), mercatorCamera(zoom = 2.0)))
    }

    /**
     * On a globe the same expression is the globe ground's own criterion, and it rises with both the
     * quad's angular span and the camera's zoom — the two things that make a chord miss a sphere by
     * more logical pixels.
     */
    @Test
    fun aGlobeSubdividesByTheGroundsOwnCriterionAndRisesWithSpanAndZoom() {
        val camera = globeCamera(zoom = 4.0, latitude = 55.0)
        val small = geometryCellsPerSide(tiltedGeometry(), camera)
        val large = geometryCellsPerSide(curvedGeometry(), camera)
        val zoomedIn = geometryCellsPerSide(curvedGeometry(), globeCamera(zoom = 8.0, latitude = 55.0))

        assertEquals(1, small, "a hundred-metre quad is flat enough for one cell on a sphere too")
        assertTrue(large > 1, "a 30-degree quad at zoom 4 is not, measured $large")
        assertTrue(zoomedIn > large, "and needs more cells at zoom 8: $zoomedIn against $large")

        // The criterion is the ground's, called rather than restated -- so the two cannot disagree
        // about how curved the world is.
        assertEquals(
            globeGroundCellDeviationLogicalPixels(camera.radiusLogicalPixels, 0.25),
            geometryCellDeviationLogicalPixels(camera.radiusLogicalPixels, 0.25 * camera.radiusLogicalPixels),
        )
    }

    // --- the camera bounds the extent too --------------------------------------------------------

    /**
     * A `Geometry` is a region and a globe projects it faithfully, so a 360-degree span is a closed
     * band around the planet rather than a degeneracy — nothing is rejected and nothing is clamped.
     * What is bounded is the **work**: at the granularity such a band asks for, all of it would be
     * 32,768 triangles a frame, most of them behind the limb and all of them discarded by the
     * far-hemisphere cull after being paid for.
     *
     * The assertion is two-sided on purpose. Too many triangles means the camera is not bounding
     * anything; **zero** means the band was culled away entirely, which is the failure that looks
     * like a success.
     */
    @Test
    fun aThreeHundredAndSixtyDegreeBandCostsWhatTheCameraCanSee() {
        val camera = globeCamera(zoom = 4.0, latitude = 55.0)
        val cellsPerSide = geometryCellsPerSide(bandGeometry(), camera)
        val grid = (geometryGrid(bandGeometry(), camera) as SpatialOutcome.Success).value
        val everyCell = 2 * cellsPerSide * cellsPerSide

        println(
            "RenG geometry grid: a 360-degree band at zoom 4 costs ${grid.triangleCount} triangles " +
                "of $everyCell, from ${grid.vertexCount} vertices",
        )
        assertTrue(cellsPerSide > 1, "the band is subdivided at all")
        assertTrue(grid.triangleCount > 0, "and the visible part of it is still drawn")
        assertTrue(
            grid.triangleCount < everyCell / 4,
            "the camera must bound the work: ${grid.triangleCount} of $everyCell is not a bound",
        )
    }

    /** Nothing behind the planet is paid for at all. */
    @Test
    fun aGeometryOnTheFarSideOfThePlanetCostsNothing() {
        val camera = globeCamera(zoom = 0.0, latitude = 0.0, longitude = 0.0)
        val antipodal = Geometry(
            topLeft = Vector3(10.0, 170.0, 0.0),
            bottomRight = Vector3(-10.0, 190.0, 0.0),
            shaderPair = minimalGeometryShaderPair(),
        )

        val grid = (geometryGrid(antipodal, camera) as SpatialOutcome.Success).value

        assertEquals(0, grid.triangleCount, "an antipodal geometry is behind the limb in every cell")
        assertEquals(0, grid.vertexCount, "and not one of its vertices is worth projecting")
    }

    /** And nothing the camera is looking straight at is dropped. */
    @Test
    fun aGeometryTheCameraFullyContainsKeepsEveryCell() {
        val camera = globeCamera(zoom = WHOLE_GEOMETRY_IN_VIEW_ZOOM, latitude = 55.0)

        val grid = (geometryGrid(curvedGeometry(), camera, cellsPerSide = 4) as SpatialOutcome.Success).value

        assertEquals(2 * 4 * 4, grid.triangleCount, "every cell of a fully visible geometry survives")
        assertEquals(5 * 5, grid.vertexCount)
    }

    /**
     * The pruning is a surface test relaxed to the radial band the geometry occupies, and this is why
     * it has to be.
     *
     * At zoom 0 over the equator the surface limb sits 83.3 degrees from the anchor, so a geometry at
     * longitude 85 is behind the planet — at altitude zero. Raise it by one earth radius and it
     * clears the limb by nearly three degrees while staying well inside the frustum. Culling it
     * against the surface footprint would punch a hole in the frame at one camera angle only, which
     * is exactly the class of defect that survives every unit suite.
     *
     * The altitude-zero twin is the discriminating half: without it this case would pass against a
     * pruner that never prunes anything.
     */
    @Test
    fun aGeometryRaisedClearOfTheSurfaceLimbIsNotCulledWithTheSurface() {
        val camera = globeCamera(zoom = 0.0, latitude = 0.0, longitude = 0.0)
        fun beyondTheLimbAt(altitudeMetres: Double) = Geometry(
            topLeft = Vector3(0.5, 84.5, altitudeMetres),
            bottomRight = Vector3(-0.5, 85.5, altitudeMetres),
            shaderPair = minimalGeometryShaderPair(),
        )

        val onTheSurface =
            (geometryGrid(beyondTheLimbAt(0.0), camera, cellsPerSide = 2) as SpatialOutcome.Success).value
        val raised = (
            geometryGrid(beyondTheLimbAt(WGS84_SEMI_MAJOR_AXIS_METRES), camera, cellsPerSide = 2)
                as SpatialOutcome.Success
            ).value

        assertEquals(0, onTheSurface.triangleCount, "at altitude zero this geometry is behind the limb")
        assertTrue(
            raised.triangleCount > 0,
            "raised by an earth radius it clears the limb and must still draw",
        )
    }

    // --- failure parity ---------------------------------------------------------------------------

    /**
     * A geometry outside the Mercator domain fails identically in both modes, with the same code, the
     * same stage and the same diagnostic field, because both arms run `validateMercatorGeometryPosition`
     * over the same four corners in the same order. A globe arm that validated in its own order would
     * report a different field for the same mistake.
     */
    @Test
    fun bothModesRejectAnOutOfDomainGeometryIdentically() {
        val beyondTheMercatorClip = Geometry(
            topLeft = Vector3(88.0, 10.0, 0.0),
            bottomRight = Vector3(80.0, 20.0, 0.0),
            shaderPair = minimalGeometryShaderPair(),
        )

        val mercator = geometryGrid(beyondTheMercatorClip, mercatorCamera(zoom = 4.0)) as SpatialOutcome.Failure
        val globe = geometryGrid(beyondTheMercatorClip, globeCamera(zoom = 4.0, latitude = 55.0))
            as SpatialOutcome.Failure

        assertEquals(RenGErrorCode.INVALID_VALUE, mercator.failure.code)
        assertEquals(mercator.failure.code, globe.failure.code)
        assertEquals(mercator.failure.stage, globe.failure.stage)
        assertEquals(mercator.failure.diagnostic, globe.failure.diagnostic)
    }

    // --- the drape (cycle E-terrain task 17) -----------------------------------------------------

    /**
     * **A draped cell folds on [groundGridIndices]' own diagonal and an undraped one does not**, which
     * is the agreement task 14's spike found missing and the reason [GROUND_DRAPE_LIFT_METRES] is four
     * metres rather than sixteen.
     *
     * The undraped order is the one the four-corner triangle strip shipped with in `0.3.0`, and it is
     * deliberately left alone: `runGeometrySubdivisionReadbackSuite` requires a one-cell Mercator grid
     * to reproduce that strip **byte for byte**, and flipping it globally moves 39 bytes of it on
     * `Apple M3 Max`. A drape has drawn no released frame, so it pays nothing to adopt the ground's.
     *
     * Compared at one cell a side because that is the granularity at which the two grids number their
     * nodes identically -- the geometry grid emits nodes through a dedupe map in quadtree order and
     * `groundGridIndices` walks a dense row-major lattice, and above one cell those two numberings
     * diverge for reasons that have nothing to do with the diagonal.
     */
    @Test
    fun aDrapedCellFoldsOnTheGroundsDiagonalAndAnUndrapedOneKeepsTheShippedOne() {
        val camera = mercatorCamera(zoom = 12.0)
        val geometry = tiltedGeometry()
        val draped = (
            geometryGrid(geometry, camera, cellsPerSide = 1, drape = { _, _ -> 0.0 })
                as SpatialOutcome.Success
            ).value
        val undraped = (geometryGrid(geometry, camera, cellsPerSide = 1) as SpatialOutcome.Success).value

        assertEquals(
            groundGridIndices(1).toList(),
            draped.triangleIndices.toList(),
            "a draped cell must fold exactly where the ground folds",
        )
        assertEquals(
            listOf<Short>(0, 2, 3, 0, 3, 1),
            undraped.triangleIndices.toList(),
            "an undraped cell keeps the north-west to south-east fold the shipped strip used",
        )
    }

    /**
     * **What a drape actually does to a Mercator node: it raises it by its own latitude's metres.**
     *
     * A constant drape of [DRAPE_RISE_METRES] must put the quad's corners exactly where declaring that
     * altitude puts them -- which is the claim that the drape and `projectMercator` spend a metre the
     * same way, and the reason a ground-relative placement needs no conversion of its own.
     *
     * **And the interior must not be the lerp between them**, which is the half a corners-only
     * assertion cannot see. `1 / cos(latitude)` is convex, so a node halfway down a quad spanning two
     * latitudes sits *below* the straight line between the corners' rises; a drape that lerped the
     * corners' heights -- which is what the undraped grid does with a declared altitude -- would land
     * on that line instead. [drapeBandGeometry] spans 60 degrees of latitude, off the equator, so the
     * gap is pixels rather than a rounding.
     */
    @Test
    fun aMercatorDrapeRaisesEachNodeByItsOwnLatitudesMetres() {
        val camera = mercatorCamera(zoom = 3.0)
        val flat = drapeBandGeometry()
        val declared = Geometry(
            topLeft = Vector3(flat.topLeft.x, flat.topLeft.y, DRAPE_RISE_METRES),
            bottomRight = Vector3(flat.bottomRight.x, flat.bottomRight.y, DRAPE_RISE_METRES),
            shaderPair = flat.shaderPair,
        )
        val draped = (
            geometryGrid(flat, camera, cellsPerSide = 4, drape = { _, _ -> DRAPE_RISE_METRES })
                as SpatialOutcome.Success
            ).value
        val lerped = (geometryGrid(declared, camera, cellsPerSide = 4) as SpatialOutcome.Success).value

        listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (u, v) ->
            assertEquals(
                lerped.nodeAt(u, v).z,
                draped.nodeAt(u, v).z,
                absoluteTolerance = 1.0e-3,
                message = "a constant drape must put the corner at ($u, $v) where declaring the " +
                    "altitude puts it",
            )
        }

        val middleDraped = draped.nodeAt(0f, 0.5f).z
        val middleLerped = lerped.nodeAt(0f, 0.5f).z
        println(
            "RenG geometry grid: a $DRAPE_RISE_METRES m drape raises the band's middle node to " +
                "$middleDraped logical pixels where lerping its corners reaches $middleLerped",
        )
        assertTrue(
            middleLerped - middleDraped > MINIMUM_CONVEXITY_LOGICAL_PIXELS,
            "the drape must apply 1 / cos(latitude) at each node rather than lerping the corners'; " +
                "the middle node sits $middleDraped against the lerp's $middleLerped",
        )
    }

    /**
     * The globe spends a drape's metres exactly where it spends a declared altitude's -- radially, and
     * by the same fraction everywhere -- so a constant drape and a constant declared altitude agree at
     * **every** node rather than only at the corners.
     *
     * That difference from the Mercator case is the point: a sphere has no `1 / cos(latitude)`, and
     * copying Mercator's term here would be twice too large at latitude 60.
     */
    @Test
    fun aGlobeDrapeIsTheDeclaredAltitudeAtEveryNode() {
        val camera = globeCamera(zoom = WHOLE_GEOMETRY_IN_VIEW_ZOOM, latitude = 55.0)
        val flat = curvedGeometry()
        val declared = Geometry(
            topLeft = Vector3(flat.topLeft.x, flat.topLeft.y, DRAPE_RISE_METRES),
            bottomRight = Vector3(flat.bottomRight.x, flat.bottomRight.y, DRAPE_RISE_METRES),
            shaderPair = flat.shaderPair,
        )
        val draped = (
            geometryGrid(flat, camera, cellsPerSide = 4, drape = { _, _ -> DRAPE_RISE_METRES })
                as SpatialOutcome.Success
            ).value
        val lifted = (geometryGrid(declared, camera, cellsPerSide = 4) as SpatialOutcome.Success).value
        val unlifted = (geometryGrid(flat, camera, cellsPerSide = 4) as SpatialOutcome.Success).value

        assertEquals(lifted.vertexCount, draped.vertexCount)
        var worst = 0.0
        var movement = 0.0
        for (index in 0 until draped.vertexCount) {
            val a = draped.interleavedVertices
            val b = lifted.interleavedVertices
            val c = unlifted.interleavedVertices
            for (component in 0 until 3) {
                val offset = index * GEOMETRY_VERTEX_COMPONENT_COUNT + component
                worst = maxOf(worst, abs(a[offset].toDouble() - b[offset].toDouble()))
                movement = maxOf(movement, abs(b[offset].toDouble() - c[offset].toDouble()))
            }
        }
        println("RenG geometry grid: globe drape worst node disagreement $worst, rise $movement")
        assertTrue(
            movement > MINIMUM_GLOBE_RISE_LOGICAL_PIXELS,
            "the fixture must be able to see $DRAPE_RISE_METRES metres at all; it moved $movement",
        )
        assertEquals(0.0, worst, absoluteTolerance = 1.0e-9)
    }

    /**
     * A drape is subdivided at the **ground's** node spacing, and an undraped geometry is not.
     *
     * Under Mercator a plane does not depart from itself, so [geometryCellsPerSide] answers one cell
     * for any quad -- which is right for a flat quad and is precisely the shape ADR 0040 replaces for a
     * drape, four corners on the terrain and a chord between them.
     */
    @Test
    fun aDrapesGranularityFollowsTheGroundsNodeSpacing() {
        val geometry = tiltedGeometry()
        val camera = mercatorCamera(zoom = 12.0)
        assertEquals(1, geometryCellsPerSide(geometry, camera), "a Mercator plane asks for one cell")

        // The quad spans 0.0016 degrees of longitude, which at LOD 12's 512-pixel tiles is
        // 0.0016 / (360 / 4096) * 512 = 9.3 logical pixels; against a ground at 64 cells a tile -- one
        // node every 8 pixels -- that is two cells, rounded up to the grid's power of two.
        assertEquals(
            2,
            drapeCellsPerSide(
                geometry = geometry,
                worldSizeLogicalPixels = camera.worldSizeLogicalPixels,
                groundCellsPerTileSide = 64,
                selectedLod = 12,
            ),
        )
        // A coarser ground asks for fewer, so the answer follows the ground rather than the quad.
        assertEquals(
            1,
            drapeCellsPerSide(
                geometry = geometry,
                worldSizeLogicalPixels = camera.worldSizeLogicalPixels,
                groundCellsPerTileSide = 4,
                selectedLod = 12,
            ),
        )
        // And it is capped by the grid's own 16-bit index ceiling rather than by the arithmetic.
        assertEquals(
            MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE,
            drapeCellsPerSide(
                geometry = bandGeometry(),
                worldSizeLogicalPixels = camera.worldSizeLogicalPixels,
                groundCellsPerTileSide = 64,
                selectedLod = 12,
            ),
        )
    }
}

// --- fixtures and measurements -------------------------------------------------------------------

/**
 * A hundred-metre quad at latitude 55 with **two different non-zero altitudes**.
 *
 * Both halves of that matter. A zero-altitude fixture lies in the `z = 0` plane, where coplanarity is
 * true of any four points a broken resolver could produce; two equal altitudes lie in a plane
 * parallel to it, which is barely better. Different altitudes at a high latitude make the quad
 * genuinely tilted, and make `projectMercator`'s `1 / cos(latitude)` altitude scaling differ between
 * the northern and southern edges.
 */
private fun tiltedGeometry(): Geometry = Geometry(
    topLeft = Vector3(55.0009, 12.0000, 240.0),
    bottomRight = Vector3(55.0000, 12.0016, 60.0),
    shaderPair = minimalGeometryShaderPair(),
)

/** Thirty degrees of longitude by twenty of latitude, centred on latitude 55 — the span and place
 * where a sphere and a tangent plane are visibly different pictures. */
private fun curvedGeometry(): Geometry = Geometry(
    topLeft = Vector3(65.0, -3.0, 0.0),
    bottomRight = Vector3(45.0, 27.0, 0.0),
    shaderPair = minimalGeometryShaderPair(),
)

/** The whole planet as one closed band: 360 degrees of longitude, which the constructor has always
 * allowed and which this cycle deliberately did not start rejecting. */
private fun bandGeometry(): Geometry = Geometry(
    topLeft = Vector3(60.0, -180.0, 0.0),
    bottomRight = Vector3(-60.0, 180.0, 0.0),
    shaderPair = minimalGeometryShaderPair(),
)

/**
 * The zoom at which [curvedGeometry] fits inside a 512-pixel frame on a globe.
 *
 * A camera sits a fixed 618 logical pixels above the surface whatever the zoom, so the patch it can
 * see is `2 * 618 * tan(22.5 degrees) / radius` radians wide — about 13 degrees at zoom 4 and 37 at
 * zoom 2.5. Cases that need the whole quad unpruned have to take the second one, and a case written
 * at zoom 4 measures a survivor set rather than a grid.
 */
private const val WHOLE_GEOMETRY_IN_VIEW_ZOOM: Double = 2.5

/**
 * 100 kilometres, which at zoom 3 is about ten logical pixels: big enough that the convexity of
 * `1 / cos(latitude)` across [drapeBandGeometry] is several pixels rather than a rounding, and never
 * zero, where a drape and its absence are the same grid.
 */
private const val DRAPE_RISE_METRES: Double = 100_000.0

/**
 * Measured at 4.9 logical pixels between the drape's own per-node rise and the lerp of its corners'
 * across [drapeBandGeometry] at zoom 3; two is under half of it.
 */
private const val MINIMUM_CONVEXITY_LOGICAL_PIXELS: Double = 2.0

/** A floor on the globe fixture's own sensitivity: 100 km is about 12 logical pixels at zoom 2.5. */
private const val MINIMUM_GLOBE_RISE_LOGICAL_PIXELS: Double = 4.0

/**
 * A band from latitude 70 to latitude 10, deliberately **not** symmetric about the equator.
 *
 * [bandGeometry] runs from +60 to -60, whose middle node sits at latitude 0 -- where `1 / cos` is
 * exactly 1 and whose two corners carry the identical scale, so a lerp of the corners is a constant
 * and the case would be reading one symmetry point against another. This band's two corners scale by
 * 2.92 and 1.02 and its interior sits at none of the three.
 */
private fun drapeBandGeometry(): Geometry = Geometry(
    topLeft = Vector3(70.0, -40.0, 0.0),
    bottomRight = Vector3(10.0, 40.0, 0.0),
    shaderPair = minimalGeometryShaderPair(),
)

private fun minimalGeometryShaderPair(): ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n",
    fragmentSource = "#version 300 es\nprecision highp float;\nout vec4 rengOut;\n" +
        "void main() {\n    rengOut = vec4(1.0);\n}\n",
)

private fun mercatorCamera(zoom: Double): ResolvedMercatorCamera = (
    resolveMercatorCamera(
        camera = Camera(latitude = 55.0, unwrappedLongitude = 12.0, zoom = zoom, bearing = 23.0, pitch = 35.0),
        outputPixelSize = OutputPixelSize(width = 512, height = 512),
    ) as SpatialOutcome.Success
    ).value

private fun globeCamera(
    zoom: Double,
    latitude: Double,
    longitude: Double = 12.0,
): ResolvedGlobeCamera = (
    resolveGlobeCamera(
        camera = Camera(
            latitude = latitude,
            unwrappedLongitude = longitude,
            zoom = zoom,
            bearing = 0.0,
            pitch = 0.0,
        ),
        outputPixelSize = OutputPixelSize(width = 512, height = 512),
    ) as SpatialOutcome.Success
    ).value

private fun resolvedCorners(geometry: Geometry, camera: ResolvedMercatorCamera): List<DoubleVector3> =
    (resolveGeometry(geometry, camera) as SpatialOutcome.Success).value.cornersClockwiseFromTopLeft

/** The grid's vertex positions, in the order they were emitted. */
private fun GeometryGrid.nodes(): List<DoubleVector3> = (0 until vertexCount).map { index ->
    val offset = index * GEOMETRY_VERTEX_COMPONENT_COUNT
    DoubleVector3(
        interleavedVertices[offset].toDouble(),
        interleavedVertices[offset + 1].toDouble(),
        interleavedVertices[offset + 2].toDouble(),
    )
}

/** The node carrying exactly the texture coordinate ([u], [v]), which is the only thing that
 * identifies a node: the grid is emitted in quadtree-descent order rather than row-major. */
private fun GeometryGrid.nodeAt(u: Float, v: Float): DoubleVector3 {
    for (index in 0 until vertexCount) {
        val offset = index * GEOMETRY_VERTEX_COMPONENT_COUNT
        if (interleavedVertices[offset + 3] == u && interleavedVertices[offset + 4] == v) {
            return DoubleVector3(
                interleavedVertices[offset].toDouble(),
                interleavedVertices[offset + 1].toDouble(),
                interleavedVertices[offset + 2].toDouble(),
            )
        }
    }
    throw AssertionError("no grid node at ($u, $v)")
}

/** How far [point] sits off the plane through [first], [second] and [third], in logical pixels. */
private fun planeDeparture(
    first: DoubleVector3,
    second: DoubleVector3,
    third: DoubleVector3,
    point: DoubleVector3,
): Double {
    val normal = (second - first).cross(third - first)
    val length = sqrt(normal.dot(normal))
    require(length > 0.0) { "a degenerate quad has no plane to depart from" }
    return abs(normal.dot(point - first)) / length
}
