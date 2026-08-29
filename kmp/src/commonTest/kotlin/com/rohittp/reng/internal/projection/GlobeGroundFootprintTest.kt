package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The globe's ground footprint, and the two things it has to be: **correct against an independent
 * derivation** and **not the Mercator footprint's shape**.
 *
 * Every camera here sits at a latitude where `cos(latitude)` is not 1, because the latitude-matched
 * convention that sizes the sphere is invisible at the equator — and every visibility fixture is
 * chosen so that the camera sees *some* of the globe and not *all* of it. A camera whose frame is
 * entirely ground cannot tell a limb bug from a missing limb, and one that sees nothing cannot tell
 * anything from anything.
 */
class GlobeGroundFootprintTest {
    /**
     * The reason [clippedPhysicalPixelFootprint]'s shape does not survive, stated as a measurement
     * rather than as prose.
     *
     * Under Mercator a pixel's hit-or-miss verdict is a function of its **row** alone —
     * [physicalPixelGroundRay] decides it on `q`, which contains no `u` — which is what makes the
     * column-0 row scan sound and makes the four unchecked corner casts safe. On a sphere the limb
     * curves in both axes, and this is a camera where the leftmost pixel of a row misses the globe
     * while a pixel further along the *same row* hits it. Run the Mercator algorithm's own steps
     * here and the corner cast is a `ClassCastException`; that is why nothing in
     * `GlobeGroundFootprint` casts a ray at all.
     */
    @Test
    fun aScreenRowCanMissTheGlobeAtItsEdgeAndHitItInItsMiddle() {
        val camera = globeCamera(HARNESS_SIZE, latitude = 41.5, zoom = 0.0, pitch = 55.0)

        var rowsWithBothVerdicts = 0
        var provingRow = -1
        for (pixelY in 0 until camera.outputPixelSize.height) {
            val leftHits = physicalPixelGlobeRay(camera, 0, pixelY) is GlobeRayResult.Hit
            var middleHits = false
            for (pixelX in 0 until camera.outputPixelSize.width step 8) {
                if (physicalPixelGlobeRay(camera, pixelX, pixelY) is GlobeRayResult.Hit) {
                    middleHits = true
                    break
                }
            }
            if (leftHits != middleHits) {
                rowsWithBothVerdicts += 1
                if (provingRow < 0) provingRow = pixelY
            }
        }

        assertTrue(
            rowsWithBothVerdicts > 0,
            "the fixture must contain a row whose edge and interior disagree, or it proves nothing",
        )
        // And the disagreement is not one stray row: the limb crosses the frame over a long span.
        assertTrue(
            rowsWithBothVerdicts > 40,
            "only $rowsWithBothVerdicts rows disagreed, too few to be the limb curving",
        )
        // The same camera under Mercator: every row's verdict is the whole row's verdict.
        val flat = mercatorCamera(HARNESS_SIZE, latitude = 41.5, zoom = 0.0, pitch = 55.0)
        for (pixelY in 0 until flat.outputPixelSize.height) {
            val leftHits = physicalPixelGroundRay(flat, 0, pixelY) is GroundRayResult.Hit
            val rightHits =
                physicalPixelGroundRay(flat, flat.outputPixelSize.width - 1, pixelY) is GroundRayResult.Hit
            assertEquals(leftHits, rightHits, "Mercator row $pixelY must not depend on the column")
        }
    }

    /**
     * The footprint against [physicalPixelGlobeRay], which solves a quadratic in the camera's own
     * east/north/up frame and shares no arithmetic with the plane extraction here. A pixel centre
     * that hits the globe is inside the frame by construction, so its ground point must be inside
     * the footprint — and the two agreeing is a check on the frustum rows, their signs and the
     * matrix multiplication order all at once.
     */
    @Test
    fun everyPixelRayThatHitsTheGlobeLandsInsideTheFootprint() {
        for (fixture in VISIBILITY_FIXTURES) {
            val camera = fixture.resolve()
            val footprint = globeGroundFootprint(camera)
            var hits = 0
            for (pixelY in 0 until camera.outputPixelSize.height step 7) {
                for (pixelX in 0 until camera.outputPixelSize.width step 7) {
                    val ray = physicalPixelGlobeRay(camera, pixelX, pixelY)
                    if (ray !is GlobeRayResult.Hit) continue
                    hits += 1
                    val direction = unitSphereDirection(ray.point.x, ray.point.y.coerceIn(0.0, 1.0))
                    assertTrue(
                        footprint.containsUnitDirection(direction),
                        "${fixture.name}: pixel ($pixelX, $pixelY) hit ground the footprint excludes",
                    )
                }
            }
            assertTrue(hits > 100, "${fixture.name} produced only $hits ground hits")
        }
    }

    /**
     * The converse, which is the half that catches a footprint that admits too much. A surface
     * direction is inside the footprint exactly when it projects onto the viewport in front of the
     * near plane **and** is not hidden by the planet — three independent statements, two of them
     * from files this one does not touch ([projectCameraRelativeLogicalPosition] and
     * [isBeyondGlobeHorizon]).
     *
     * Samples within a logical pixel of any of those boundaries are skipped rather than asserted:
     * the footprint is deliberately inclusive by [1e-12] on the unit sphere, and a fixture that
     * happened to straddle an edge would be measuring rounding rather than geometry.
     */
    @Test
    fun aDirectionIsInsideTheFootprintExactlyWhenItProjectsOntoTheViewportAndIsNotHiddenByThePlanet() {
        for (fixture in VISIBILITY_FIXTURES) {
            val camera = fixture.resolve()
            val footprint = globeGroundFootprint(camera)
            val width = camera.outputPixelSize.width.toDouble()
            val height = camera.outputPixelSize.height.toDouble()
            var insideCount = 0
            var outsideCount = 0

            for (direction in sampledDirections(camera)) {
                run {
                    val position = direction * camera.radiusLogicalPixels
                    val hidden = isBeyondGlobeHorizon(
                        cameraPositionFromGlobeCentre = camera.eyeGlobeFixed,
                        globeFixedPosition = position,
                        radiusLogicalPixels = camera.radiusLogicalPixels,
                    )
                    val horizonMargin = requireNotNull(
                        globeLimbPlane(camera.eyeGlobeFixed, camera.radiusLogicalPixels),
                    ).signedDistanceLogicalPixels(position) / camera.radiusLogicalPixels
                    if (abs(horizonMargin) <= UNIT_SPHERE_GUARD) return@run

                    val contained = footprint.containsUnitDirection(direction)
                    val projected = projectCameraRelativeLogicalPosition(
                        camera,
                        cameraRelativeLogicalPosition(camera, position),
                    )
                    if (projected is ScreenProjection.OutsideSupportedDomain) return@run
                    val depth = when (projected) {
                        is ScreenProjection.Projected -> projected.w
                        is ScreenProjection.BehindNearPlane -> projected.w
                        else -> return@run
                    }
                    val nearMargin = (depth - NEAR_DISTANCE_LOGICAL_PIXELS) / camera.radiusLogicalPixels
                    if (abs(nearMargin) <= UNIT_SPHERE_GUARD) return@run

                    if (projected !is ScreenProjection.Projected) {
                        assertTrue(
                            !contained,
                            "${fixture.name}: ground inside the near plane was admitted as visible",
                        )
                        outsideCount += 1
                        return@run
                    }
                    val pixelMargin = minOf(
                        projected.pixelX,
                        width - projected.pixelX,
                        projected.pixelY,
                        height - projected.pixelY,
                    )
                    if (abs(pixelMargin) <= VIEWPORT_GUARD_LOGICAL_PIXELS) return@run

                    val visible = pixelMargin > 0.0 && !hidden
                    assertEquals(
                        visible,
                        contained,
                        "${fixture.name}: direction $direction " +
                            "pixel (${projected.pixelX}, ${projected.pixelY}) hidden=$hidden",
                    )
                    if (visible) insideCount += 1 else outsideCount += 1
                }
            }
            assertTrue(insideCount > 0, "${fixture.name} saw no visible sample: nothing was discriminated")
            assertTrue(outsideCount > 0, "${fixture.name} saw no hidden sample: nothing was discriminated")
        }
    }

    /**
     * ADR 0038's point, measured on the footprint rather than on the horizon test alone.
     *
     * The antipodal surface point is the most thoroughly hidden place on the planet, and it is also
     * the one that projects to the exact **centre of the frame** with the **largest** positive `w`
     * any ground point can have. Every viewport test, every near-plane test and every sign-of-`w`
     * test passes on it. Only the limb half-space excludes it, so deleting that half-space from
     * [globeGroundFootprint] leaves a footprint that admits the far side of the world while looking
     * entirely correct.
     */
    @Test
    fun theAntipodeProjectsToTheCentreOfTheFrameWithALargePositiveWAndIsStillExcluded() {
        val camera = globeCamera(HARNESS_SIZE, latitude = 61.0, zoom = 0.0, longitude = 23.0)
        val footprint = globeGroundFootprint(camera)
        val antipode = -camera.anchorUp
        val position = antipode * camera.radiusLogicalPixels
        val projected = projectCameraRelativeLogicalPosition(
            camera,
            cameraRelativeLogicalPosition(camera, position),
        ) as ScreenProjection.Projected

        assertEquals(HARNESS_SIZE.width / 2.0, projected.pixelX, 1e-6)
        assertEquals(HARNESS_SIZE.height / 2.0, projected.pixelY, 1e-6)
        assertTrue(projected.w > 0.0, "the antipode is in front of the camera plane, not behind it")
        assertEquals(
            2.0 * camera.radiusLogicalPixels + camera.cameraDistanceLogicalPixels,
            projected.w,
            1e-6,
            "and its w -- the whole diameter plus the camera's own height -- is the largest any " +
                "ground point carries",
        )

        assertTrue(!footprint.containsUnitDirection(antipode), "the far side must not be visible ground")
        assertTrue(
            footprint.containsUnitDirection(camera.anchorUp),
            "the sub-camera point must be visible, or the exclusion is vacuous",
        )
        // The near-side point closest to the limb is visible and the far-side one adjacent to it is
        // not, so the exclusion is the limb rather than "far things are excluded".
        val limb = requireNotNull(globeLimbPlane(camera.eyeGlobeFixed, camera.radiusLogicalPixels))
        val limbAngle = acos(limb.offsetLogicalPixels / camera.radiusLogicalPixels)
        assertTrue(
            footprint.containsUnitDirection(rotatedFromAnchor(camera, limbAngle - 1e-6)),
            "just inside the limb must be visible",
        )
        assertTrue(
            !footprint.containsUnitDirection(rotatedFromAnchor(camera, limbAngle + 1e-6)),
            "just outside the limb must not be",
        )
    }

    /**
     * Ground that the frustum contains and the near plane excludes, which is not a corner case at a
     * grazing camera: at pitch 89.99 and zoom 14 the eye stands a quarter of a logical pixel off the
     * surface, and **11,200 of 28,800 sampled pixels** look at ground closer to it than the near
     * plane. Those pixels draw nothing, so the ground behind them is not visible ground, and a
     * footprint that admitted it would be claiming a tile is visible on the strength of pixels the
     * pipeline discards.
     *
     * [physicalPixelGlobeRay] is the independent witness: it near-clips a *pixel* ray, so a
     * [GlobeRayResult.NearClipped] result is the statement "the frustum contains ground inside the
     * near plane" made without reference to any half-space.
     */
    @Test
    fun groundTheFrustumContainsButTheNearPlaneExcludesIsNotVisibleGround() {
        val camera = globeCamera(HARNESS_SIZE, latitude = 31.0, zoom = 14.0, pitch = 89.99)
        val footprint = globeGroundFootprint(camera)

        var nearClippedPixels = 0
        for (pixelY in 0 until HARNESS_SIZE.height step 3) {
            for (pixelX in 0 until HARNESS_SIZE.width step 3) {
                if (physicalPixelGlobeRay(camera, pixelX, pixelY) is GlobeRayResult.NearClipped) {
                    nearClippedPixels += 1
                }
            }
        }
        assertTrue(
            nearClippedPixels > 1000,
            "only $nearClippedPixels near-clipped pixels: the fixture does not reach the near plane",
        )

        var behindNearPlane = 0
        var justBeyondAndVisible = 0
        for (direction in sampledDirections(camera)) {
            val logical = cameraRelativeLogicalPosition(camera, direction * camera.radiusLogicalPixels)
            when (val projected = projectCameraRelativeLogicalPosition(camera, logical)) {
                is ScreenProjection.BehindNearPlane -> {
                    if (projected.w > 0.0) {
                        behindNearPlane += 1
                        assertTrue(
                            !footprint.containsUnitDirection(direction),
                            "ground $projected in front of the camera but inside the near plane " +
                                "was admitted as visible",
                        )
                    }
                }

                is ScreenProjection.Projected -> {
                    val inViewport = projected.pixelX in 0.0..HARNESS_SIZE.width.toDouble() &&
                        projected.pixelY in 0.0..HARNESS_SIZE.height.toDouble()
                    if (inViewport && projected.w < 4.0 * NEAR_DISTANCE_LOGICAL_PIXELS) {
                        justBeyondAndVisible += 1
                        assertTrue(
                            footprint.containsUnitDirection(direction),
                            "ground just beyond the near plane at $projected was excluded",
                        )
                    }
                }

                ScreenProjection.OutsideSupportedDomain -> Unit
            }
        }
        assertTrue(behindNearPlane > 0, "no sample landed inside the near plane")
        assertTrue(
            justBeyondAndVisible > 0,
            "no sample landed just beyond it, so the exclusion could be the side planes instead",
        )
    }

    /**
     * The feasibility decision's **completeness**, on configurations a camera never produces.
     *
     * Maximising one constraint over a non-empty feasible set lands in one of three places: that
     * constraint's own normal, a point where two boundary circles meet, or the point of one circle
     * nearest the objective. The third family looks redundant and is not: two caps *larger* than a
     * hemisphere can meet in a band whose boundary is two disjoint circles, with no corner anywhere
     * and neither cap's centre inside. Deleting that family moves no tile at any camera measured in
     * this file, so this is the only case that would ever see it.
     */
    @Test
    fun theFeasibilityDecisionFindsARegionWithNoCornersAndNoFeasibleCentres() {
        val band = listOf(
            GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, 1.0), -0.9),
            GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, -1.0), -0.9),
        )
        // Neither centre is feasible and the two circles never meet, yet everything between them is.
        assertTrue(band.none { candidate -> band.all { it.admits(candidate.normal) } })
        assertTrue(anyFeasibleGlobeDirection(band), "the band between two large caps is non-empty")

        val nested = listOf(
            GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, 1.0), 0.99),
            GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, 1.0), 0.2),
        )
        assertTrue(anyFeasibleGlobeDirection(nested), "the smaller of two nested caps is non-empty")

        val disjoint = listOf(
            GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, 1.0), 0.5),
            GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, -1.0), 0.5),
        )
        assertTrue(!anyFeasibleGlobeDirection(disjoint), "two opposed caps share nothing")

        val impossible = listOf(GlobeGroundHalfSpace(DoubleVector3(1.0, 0.0, 0.0), 1.0 + 1e-9))
        assertTrue(!anyFeasibleGlobeDirection(impossible), "a cap beyond the sphere is empty")

        // Three caps whose pairwise corners are all infeasible except the ones bounding the answer.
        val corner = listOf(
            GlobeGroundHalfSpace(DoubleVector3(1.0, 0.0, 0.0), 0.4),
            GlobeGroundHalfSpace(DoubleVector3(0.0, 1.0, 0.0), 0.4),
            GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, 1.0), 0.4),
        )
        assertTrue(anyFeasibleGlobeDirection(corner), "the octant the three caps share is non-empty")
        assertTrue(
            !anyFeasibleGlobeDirection(
                corner + GlobeGroundHalfSpace(DoubleVector3(-1.0, -1.0, -1.0) * (1.0 / sqrt(3.0)), 0.1),
            ),
            "and adding its own antipodal cap empties it",
        )
    }

    /**
     * The exact maximum of a linear functional over a spherical rectangle, against a brute-force
     * scan of the same rectangle.
     *
     * The rectangles deliberately include one that **straddles the antimeridian** and normals that
     * sit on the far side of it. That is the case a `coerceIn` clamp gets wrong — it picks the
     * numerically nearer endpoint rather than the angularly nearer one and reports a maximum that is
     * too **small**, which in the selector means excluding a tile that shows ground.
     */
    @Test
    fun theSphericalRectangleMaximumDominatesAndIsAttainedOverTheWholeRectangle() {
        for (rectangle in RECTANGLES) {
            for (normal in PROBE_NORMALS) {
                val closedForm = maximumOverSphericalRectangle(
                    normal = normal,
                    latitudeCentre = rectangle.latitudeCentre,
                    latitudeHalfWidth = rectangle.latitudeHalfWidth,
                    longitudeCentre = rectangle.longitudeCentre,
                    longitudeHalfWidth = rectangle.longitudeHalfWidth,
                )
                var sampledMaximum = Double.NEGATIVE_INFINITY
                for (latitudeIndex in 0..SAMPLES) {
                    val latitude = rectangle.latitudeCentre +
                        rectangle.latitudeHalfWidth * (2.0 * latitudeIndex / SAMPLES - 1.0)
                    for (longitudeIndex in 0..SAMPLES) {
                        val longitude = rectangle.longitudeCentre +
                            rectangle.longitudeHalfWidth * (2.0 * longitudeIndex / SAMPLES - 1.0)
                        val value = normal.dot(
                            DoubleVector3(
                                x = cos(latitude) * cos(longitude),
                                y = cos(latitude) * sin(longitude),
                                z = sin(latitude),
                            ),
                        )
                        if (value > sampledMaximum) sampledMaximum = value
                    }
                }
                assertTrue(
                    closedForm >= sampledMaximum - 1e-12,
                    "${rectangle.name}: sampled $sampledMaximum exceeded the closed form $closedForm",
                )
                assertTrue(
                    closedForm - sampledMaximum < 1e-4,
                    "${rectangle.name}: closed form $closedForm is ${closedForm - sampledMaximum} " +
                        "above the attainable $sampledMaximum",
                )
            }
        }
    }

    /**
     * The same maximum against the bound every renderer reaches for first — the support function of
     * the patch's axis-aligned bounding box — measured where it decides something.
     *
     * The box is a legitimate bound and a useless one here: run the *same* per-plane rejection with
     * the box's support in place of [maximumOverSphericalRectangle] and the measured harness camera
     * keeps strictly more cells, because the box's corners sit above the surface and outside the
     * patch's own longitude span. This is the case that says the closed form is load-bearing rather
     * than an optimisation.
     */
    @Test
    fun theBoundingBoxSupportBoundKeepsCellsTheExactMaximumRejects() {
        val camera = globeCamera(HARNESS_SIZE, latitude = 82.0, zoom = 6.0)
        val footprint = globeGroundFootprint(camera)
        val tileCount = 64

        var exactlyKept = 0
        var boxKept = 0
        for (tileY in 0 until tileCount) {
            for (tileX in 0 until tileCount) {
                val minimumX = tileX.toDouble() / tileCount
                val maximumX = (tileX + 1).toDouble() / tileCount
                val minimumY = tileY.toDouble() / tileCount
                val maximumY = (tileY + 1).toDouble() / tileCount
                if (footprint.mayAdmitMercatorCell(minimumX, maximumX, minimumY, maximumY)) {
                    exactlyKept += 1
                }
                val box = boundingBox(minimumX, maximumX, minimumY, maximumY)
                if (footprint.halfSpaces.all { box.supports(it.normal) >= it.offset - 1e-12 }) {
                    boxKept += 1
                }
            }
        }

        assertTrue(exactlyKept > 0, "the exact per-plane test must keep something")
        assertTrue(
            boxKept > exactlyKept,
            "the box kept $boxKept cells and the exact maximum kept $exactlyKept: " +
                "the case does not discriminate",
        )
    }

    /**
     * A cell test is not a sampled cell test, and this is the cell that proves it. At the measured
     * harness camera the ground stops 0.8% of a tile short of column 30, so column 30's rectangle
     * meets no visible ground — while **each of the five camera half-spaces individually** admits
     * part of it, because the part that clears the western side plane lies north of the top one.
     */
    @Test
    fun aCellCanClearEveryHalfSpaceSeparatelyAndStillShowNoGround() {
        val camera = globeCamera(HARNESS_SIZE, latitude = 82.0, zoom = 6.0)
        val footprint = globeGroundFootprint(camera)
        val tileCount = 64.0

        assertTrue(
            footprint.mayAdmitMercatorCell(30.0 / tileCount, 31.0 / tileCount, 4.0 / tileCount, 5.0 / tileCount),
            "the per-plane test must admit column 30, or the exact test has nothing to correct",
        )
        assertTrue(
            !footprint.admitsMercatorCell(30.0 / tileCount, 31.0 / tileCount, 4.0 / tileCount, 5.0 / tileCount),
            "column 30 shows no ground and must be excluded",
        )
        for (tileX in 31..32) {
            assertTrue(
                footprint.admitsMercatorCell(
                    tileX / tileCount,
                    (tileX + 1) / tileCount,
                    4.0 / tileCount,
                    5.0 / tileCount,
                ),
                "column $tileX shows ground and must be admitted",
            )
        }
    }

    private companion object {
        val HARNESS_SIZE = OutputPixelSize(960, 540)
        const val SAMPLES = 240
        const val GLOBAL_SAMPLES = 96
        const val LOCAL_ANGLE_SAMPLES = 37
        const val LOCAL_AZIMUTH_SAMPLES = 31
        const val BOX_SAMPLES = 24
        const val NEAR_FAN_DECADES = 12
        /**
         * Samples nearer a boundary than this are skipped rather than asserted, because the
         * footprint is deliberately inclusive by `1e-12` on the unit sphere and a sample straddling
         * an edge would be measuring rounding. **On the unit sphere, not in logical pixels**: at the
         * grazing fixture the eye stands a quarter of a logical pixel off the surface, so the entire
         * visible cap is within 0.25 logical pixels of its own limb plane and an absolute guard in
         * pixels would skip every sample the case exists to make.
         */
        const val UNIT_SPHERE_GUARD = 1e-9
        const val VIEWPORT_GUARD_LOGICAL_PIXELS = 1e-3

        val VISIBILITY_FIXTURES = listOf(
            GlobeVisibilityFixture("whole globe in frame, latitude 61", HARNESS_SIZE, 61.0, 0.0, 0.0, 0.0),
            GlobeVisibilityFixture("limb crossing the frame, pitched", HARNESS_SIZE, 41.5, 0.0, 0.0, 55.0),
            GlobeVisibilityFixture("high latitude, ground fills the frame", HARNESS_SIZE, 82.0, 6.0, 0.0, 0.0),
            GlobeVisibilityFixture("southern, rotated and pitched", HARNESS_SIZE, -47.3, 2.0, 137.0, 38.0),
            GlobeVisibilityFixture("phone viewport across the antimeridian", OutputPixelSize(1179, 2556), 63.0, 1.0, 0.0, 0.0, longitude = 179.4),
            GlobeVisibilityFixture("grazing, ground inside the near plane", HARNESS_SIZE, 31.0, 14.0, 0.0, 89.99),
        )

        val RECTANGLES = listOf(
            SphericalRectangle("northern, narrow", 82.4 * PI / 180.0, 0.39 * PI / 180.0, -8.4 * PI / 180.0, 2.8125 * PI / 180.0),
            SphericalRectangle("southern, wide", -47.0 * PI / 180.0, 11.0 * PI / 180.0, 71.0 * PI / 180.0, 22.5 * PI / 180.0),
            SphericalRectangle("straddling the antimeridian", -3.0 * PI / 180.0, 8.0 * PI / 180.0, PI - 0.05, 0.09),
            SphericalRectangle("a whole hemisphere of longitude", 17.0 * PI / 180.0, 40.0 * PI / 180.0, 0.0, PI / 2.0),
        )

        val PROBE_NORMALS = listOf(
            DoubleVector3(1.0, 0.0, 0.0),
            DoubleVector3(0.0, 0.0, 1.0),
            DoubleVector3(0.0, 0.0, -1.0),
            normalised(-0.93, 0.21, 0.31),
            normalised(0.11, -0.97, -0.19),
            normalised(-0.32, -0.41, 0.85),
            normalised(0.57, 0.62, -0.54),
        )

        fun normalised(x: Double, y: Double, z: Double): DoubleVector3 {
            val vector = DoubleVector3(x, y, z)
            return vector * (1.0 / sqrt(vector.dot(vector)))
        }

        fun globeCamera(
            size: OutputPixelSize,
            latitude: Double,
            zoom: Double,
            longitude: Double = 0.0,
            bearing: Double = 0.0,
            pitch: Double = 0.0,
        ): ResolvedGlobeCamera {
            val outcome = resolveGlobeCamera(
                Camera(latitude, longitude, zoom, bearing, pitch),
                size,
            )
            return (outcome as SpatialOutcome.Success).value
        }

        fun mercatorCamera(
            size: OutputPixelSize,
            latitude: Double,
            zoom: Double,
            pitch: Double,
        ): ResolvedMercatorCamera {
            val outcome = resolveMercatorCamera(
                Camera(latitude, 0.0, zoom, 0.0, pitch),
                size,
            )
            return (outcome as SpatialOutcome.Success).value
        }

        /** A direction [angle] radians away from the sub-camera point, in the camera's own north. */
        fun rotatedFromAnchor(camera: ResolvedGlobeCamera, angle: Double): DoubleVector3 =
            camera.anchorUp * cos(angle) + camera.anchorNorth * sin(angle)

        /** The axis-aligned bounding box of a Mercator cell's spherical rectangle, sampled densely
         * enough that it is a true bound. */
        fun boundingBox(
            minimumX: Double,
            maximumX: Double,
            minimumY: Double,
            maximumY: Double,
        ): AxisAlignedBox {
            var lowest = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
            var highest = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
            for (rowIndex in 0..BOX_SAMPLES) {
                val mercatorY = minimumY + (maximumY - minimumY) * rowIndex / BOX_SAMPLES
                for (columnIndex in 0..BOX_SAMPLES) {
                    val mercatorX = minimumX + (maximumX - minimumX) * columnIndex / BOX_SAMPLES
                    val direction = unitSphereDirection(mercatorX, mercatorY)
                    val components = doubleArrayOf(direction.x, direction.y, direction.z)
                    for (axis in 0..2) {
                        if (components[axis] < lowest[axis]) lowest[axis] = components[axis]
                        if (components[axis] > highest[axis]) highest[axis] = components[axis]
                    }
                }
            }
            return AxisAlignedBox(lowest, highest)
        }

        /**
         * Directions over the whole sphere **and** over the camera's own neighbourhood.
         *
         * A global latitude/longitude grid alone is not a test at high zoom: at latitude 82 and
         * zoom 6 the visible ground is 10 degrees of longitude by 0.8 of latitude, which a grid
         * coarse enough to cover the sphere steps straight over — the case then asserts nothing
         * while passing. The local grid is scaled to the frame's own angular radius, so every
         * fixture samples both sides of its own visible boundary.
         */
        fun sampledDirections(camera: ResolvedGlobeCamera): List<DoubleVector3> {
            val directions = ArrayList<DoubleVector3>(GLOBAL_SAMPLES * GLOBAL_SAMPLES + 1200)
            for (latitudeIndex in 0..GLOBAL_SAMPLES) {
                val latitude = -PI / 2.0 + PI * latitudeIndex / GLOBAL_SAMPLES
                for (longitudeIndex in 0 until GLOBAL_SAMPLES) {
                    val longitude = -PI + 2.0 * PI * longitudeIndex / GLOBAL_SAMPLES
                    directions += DoubleVector3(
                        x = cos(latitude) * cos(longitude),
                        y = cos(latitude) * sin(longitude),
                        z = sin(latitude),
                    )
                }
            }

            val frameRadiusLogicalPixels = 0.5 * kotlin.math.hypot(
                camera.outputPixelSize.width.toDouble(),
                camera.outputPixelSize.height.toDouble(),
            )
            val maximumAngle = minOf(
                PI,
                4.0 * frameRadiusLogicalPixels / camera.radiusLogicalPixels,
            )
            for (angleIndex in 0..LOCAL_ANGLE_SAMPLES) {
                val angle = maximumAngle * angleIndex / LOCAL_ANGLE_SAMPLES
                directions += fanAbout(camera.anchorUp, camera.anchorNorth, angle)
            }

            // ...and a geometric fan about the point of the sphere nearest the eye, spanning six
            // orders of magnitude of angle. A near-plane boundary sits about one logical pixel from
            // that point, which on a zoom-14 sphere is 6 x 10^-7 radians -- four decades below
            // anything a linear grid over the frame reaches, and the only place a camera grazing
            // the surface can be caught admitting ground it cannot draw.
            val subEye = normalisedVector(camera.eyeGlobeFixed)
            var decade = 0
            while (decade <= NEAR_FAN_DECADES) {
                val angle = maximumAngle * 10.0.pow(-decade / 2.0)
                directions += fanAbout(subEye, camera.anchorNorth, angle)
                decade += 1
            }
            return directions
        }

        /** Unit directions at [angle] from [axis], all the way round it. */
        fun fanAbout(
            axis: DoubleVector3,
            towards: DoubleVector3,
            angle: Double,
        ): List<DoubleVector3> {
            val projected = towards - axis * axis.dot(towards)
            val first = if (sqrt(projected.dot(projected)) > 1e-9) {
                normalisedVector(projected)
            } else {
                normalisedVector(axis.cross(DoubleVector3(0.0, 0.0, 1.0)))
            }
            val second = axis.cross(first)
            val along = axis * cos(angle)
            return List(LOCAL_AZIMUTH_SAMPLES) { azimuthIndex ->
                val azimuth = 2.0 * PI * azimuthIndex / LOCAL_AZIMUTH_SAMPLES
                along + (first * cos(azimuth) + second * sin(azimuth)) * sin(angle)
            }
        }

        fun normalisedVector(vector: DoubleVector3): DoubleVector3 =
            vector * (1.0 / sqrt(vector.dot(vector)))
    }
}

private class GlobeVisibilityFixture(
    val name: String,
    val size: OutputPixelSize,
    val latitude: Double,
    val zoom: Double,
    val bearing: Double,
    val pitch: Double,
    val longitude: Double = 0.0,
) {
    fun resolve(): ResolvedGlobeCamera {
        val outcome = resolveGlobeCamera(
            Camera(latitude, longitude, zoom, bearing, pitch),
            size,
        )
        return (outcome as SpatialOutcome.Success).value
    }
}

private class SphericalRectangle(
    val name: String,
    val latitudeCentre: Double,
    val latitudeHalfWidth: Double,
    val longitudeCentre: Double,
    val longitudeHalfWidth: Double,
)

private class AxisAlignedBox(private val lowest: DoubleArray, private val highest: DoubleArray) {
    /** The box's support in a direction: the largest `normal . point` over the box. */
    fun supports(normal: DoubleVector3): Double {
        val components = doubleArrayOf(normal.x, normal.y, normal.z)
        var total = 0.0
        for (axis in 0..2) {
            total += maxOf(components[axis] * lowest[axis], components[axis] * highest[axis])
        }
        return total
    }
}
