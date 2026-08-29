package com.rohittp.reng.internal.projection

import com.rohittp.reng.internal.math.DoubleVector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The globe's counterpart to [clippedPhysicalPixelFootprint]: which ground a globe camera can see.
 *
 * ## Why this is not [ClosedMercatorFootprint] with a different projection substituted
 *
 * [clippedPhysicalPixelFootprint] scans **column 0** for the band of screen rows whose ray meets the
 * ground, then casts four corner rays **unchecked** and clips the resulting quadrilateral to the
 * Mercator support box. Both halves of that are sound only on a plane. A horizontal ground plane is
 * crossed or missed according to the sign of one number that depends on the screen *row* alone
 * ([physicalPixelGroundRay]'s `q`), so column 0's verdict is every column's verdict and the corner
 * casts cannot fail. **On a sphere the limb curves in both screen axes**: a row whose left pixel
 * passes the limb can have a middle pixel that hits, so the row scan admits rows whose corners miss
 * and `groundHit`'s cast throws, and the four-corner quadrilateral stops covering a ground region
 * whose boundary is an arc rather than a straight edge. Substituting [physicalPixelGlobeRay] for
 * [physicalPixelGroundRay] fixes neither half — the *shape* of the algorithm is the plane
 * assumption, not its arithmetic.
 *
 * ## What replaces it
 *
 * A ray is not cast at all. The visible ground is the set of surface directions satisfying every one
 * of at most six half-spaces, each of which RenG already computes for another purpose:
 *
 * - the **limb plane** ([globeLimbPlane], `C . P >= R^2` normalised), which is what makes the far
 *   hemisphere invisible. Nothing else can: an antipodal surface point is in front of the camera
 *   plane and projects to the centre of the frame, so the frustum admits it (ADR 0038,
 *   [GlobeLimbPlane]);
 * - the **four side planes of the view frustum**, read straight off [globeFixedViewProjection] —
 *   the very matrix a globe draw path uploads, so a disagreement between what is selected and what
 *   is drawn is not expressible;
 * - the **near plane**, `w >= NEAR_DISTANCE_LOGICAL_PIXELS`, which is the same guard
 *   [projectCameraRelativeLogicalPosition] applies in the forward direction.
 *
 * A basemap tile is a Mercator cell, and a Mercator cell on the sphere is a **spherical rectangle**
 * — itself the intersection of four half-spaces, two parallels and two meridians. So "does this tile
 * show any visible ground" is one question about ten half-spaces and a unit sphere, asked exactly
 * ([admitsMercatorCell]) rather than sampled. There is no row band, no x-interval per row and no
 * conservative neighbour padding, which is what lets a footprint that wraps the antimeridian or caps
 * a pole need no special case at all: those are the cases where "rows times an x-interval" stops
 * being a description of the region, and this is not that description.
 *
 * ## Units and frame
 *
 * Every normal here is unit length and every offset is expressed **on the unit sphere**: a surface
 * position `P = R * u` satisfies `a . P >= b` exactly when `a/|a| . u >= b/(|a| * R)`, and the
 * division is done once, when the footprint is built. Logical pixels never appear below that point,
 * which keeps the arithmetic conditioned identically at zoom 0 and at zoom 22, where the radius
 * spans ten orders of magnitude.
 */
internal class GlobeGroundFootprint internal constructor(
    halfSpaces: List<GlobeGroundHalfSpace>,
) {
    private val cameraHalfSpaces: List<GlobeGroundHalfSpace> = halfSpaces.toList()

    /** The camera half-spaces, unit-normalised, in [projectGlobe]'s globe-fixed frame. */
    val halfSpaces: List<GlobeGroundHalfSpace> get() = ArrayList(cameraHalfSpaces)

    /**
     * Whether the surface point in unit direction [direction] is visible: in front of the near
     * plane, inside all four side planes, and on the camera's side of the limb.
     *
     * A non-finite component fails every comparison and so is rejected rather than surviving, the
     * same direction [GlobeLimbPlane.isBeyondHorizon] and [projectCameraRelativeLogicalPosition]
     * fail in: a `NaN` that survived here would go on to select a tile that covers nothing.
     */
    fun containsUnitDirection(direction: DoubleVector3): Boolean =
        cameraHalfSpaces.all { it.admits(direction) }

    /**
     * A cheap **conservative** verdict on a Mercator cell: false only when some single half-space
     * excludes the whole cell, which no cell that shows visible ground can do.
     *
     * The maximum of a linear functional over a spherical rectangle is available in closed form
     * ([maximumOverSphericalRectangle]), so this is exact per plane and loose only where several
     * planes cut the same cell near a frustum corner — each of them admitting a different part of
     * it. That residual is why [admitsMercatorCell] exists; this is what makes the tile-quadtree
     * descent cheap enough to run from LOD 0 every frame.
     */
    fun mayAdmitMercatorCell(
        minimumX: Double,
        maximumX: Double,
        minimumY: Double,
        maximumY: Double,
    ): Boolean = mayAdmit(mercatorCell(minimumX, maximumX, minimumY, maximumY))

    /**
     * Whether the Mercator cell `[minimumX, maximumX] x [minimumY, maximumY]` shows any visible
     * ground — the exact question, decided rather than sampled.
     *
     * A sampling test is the wrong tool here and the measured margins say why: at the harness
     * camera of `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.2 (960x540, latitude 82,
     * zoom 6) the visible ground reaches to within **0.8% of a tile** of its neighbouring column,
     * and on the phone camera to within **3% of a tile** of its neighbouring row. A grid sampler
     * fine enough to resolve that is more expensive than deciding it, and a grid sampler coarser
     * than that silently returns a different tile count from the flat map's.
     */
    fun admitsMercatorCell(
        minimumX: Double,
        maximumX: Double,
        minimumY: Double,
        maximumY: Double,
    ): Boolean {
        val cell = mercatorCell(minimumX, maximumX, minimumY, maximumY)
        if (!mayAdmit(cell)) return false
        if (cell.representativeDirections().any(::containsUnitDirection)) return true
        return anyFeasibleDirection(cameraHalfSpaces + cell.halfSpaces())
    }

    private fun mayAdmit(cell: MercatorCell): Boolean = cameraHalfSpaces.all { halfSpace ->
        val maximum = maximumOverSphericalRectangle(
            normal = halfSpace.normal,
            latitudeCentre = cell.latitudeCentre,
            latitudeHalfWidth = cell.latitudeHalfWidth,
            longitudeCentre = cell.longitudeCentre,
            longitudeHalfWidth = cell.longitudeHalfWidth,
        )
        maximum >= halfSpace.offset - ADMISSION_TOLERANCE
    }
}

/**
 * One half-space of the globe's ground footprint, as `normal . u >= offset` for a **unit** `u` on
 * the sphere and a **unit** [normal].
 */
internal class GlobeGroundHalfSpace internal constructor(
    val normal: DoubleVector3,
    val offset: Double,
) {
    fun admits(direction: DoubleVector3): Boolean =
        normal.dot(direction) >= offset - ADMISSION_TOLERANCE
}

/**
 * The visible ground of [camera], built from the pieces that already decide visibility elsewhere
 * rather than from a second derivation of the same geometry.
 *
 * The frustum comes out of [globeFixedViewProjection] by the standard row combinations of a
 * clip-space test — `x >= -w` is `(row0 + row3) . (P, 1) >= 0` and so on — so the planes are
 * literally the ones the uploaded matrix imposes. The limb comes out of [globeLimbPlane], which is
 * absent exactly when that function returns `null`: a camera at or inside the surface hides nothing,
 * and culling everything there would empty the map rather than draw its far side.
 *
 * A half-space whose normal degenerates to zero length, or whose offset is not a finite number, is
 * dropped rather than admitted as a `NaN` test. Every such case is a camera that could not have been
 * resolved, and dropping the constraint fails in the direction of drawing too much.
 */
internal fun globeGroundFootprint(camera: ResolvedGlobeCamera): GlobeGroundFootprint {
    val viewProjection = globeFixedViewProjection(camera)
    val radius = camera.radiusLogicalPixels
    val halfSpaces = ArrayList<GlobeGroundHalfSpace>(SIDE_PLANE_COUNT + 2)

    for (row in 0..1) {
        for (sign in doubleArrayOf(1.0, -1.0)) {
            // `sign * row_k + row_3 >= 0` is the pair of clip-space side tests on axis k.
            halfSpaces.addIfUsable(
                normalX = sign * viewProjection[row, 0] + viewProjection[3, 0],
                normalY = sign * viewProjection[row, 1] + viewProjection[3, 1],
                normalZ = sign * viewProjection[row, 2] + viewProjection[3, 2],
                planeOffset = -(sign * viewProjection[row, 3] + viewProjection[3, 3]),
                radiusLogicalPixels = radius,
            )
        }
    }
    halfSpaces.addIfUsable(
        normalX = viewProjection[3, 0],
        normalY = viewProjection[3, 1],
        normalZ = viewProjection[3, 2],
        planeOffset = NEAR_DISTANCE_LOGICAL_PIXELS - viewProjection[3, 3],
        radiusLogicalPixels = radius,
    )

    val limb = globeLimbPlane(camera.eyeGlobeFixed, radius)
    if (limb != null) {
        halfSpaces.addIfUsable(
            normalX = limb.normal.x,
            normalY = limb.normal.y,
            normalZ = limb.normal.z,
            planeOffset = limb.offsetLogicalPixels,
            radiusLogicalPixels = radius,
        )
    }
    return GlobeGroundFootprint(halfSpaces)
}

private fun MutableList<GlobeGroundHalfSpace>.addIfUsable(
    normalX: Double,
    normalY: Double,
    normalZ: Double,
    planeOffset: Double,
    radiusLogicalPixels: Double,
) {
    val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
    if (!(length > 0.0) || !length.isFinite()) return
    val offset = planeOffset / (length * radiusLogicalPixels)
    if (!offset.isFinite()) return
    this += GlobeGroundHalfSpace(
        normal = DoubleVector3(normalX / length, normalY / length, normalZ / length),
        offset = offset,
    )
}

/**
 * The **exact** maximum of `normal . u` over the spherical rectangle
 * `|latitude - latitudeCentre| <= latitudeHalfWidth`, `|longitude - longitudeCentre| <=
 * longitudeHalfWidth`, for unit `u` and unit [normal].
 *
 * Not a bound over a bounding volume, and the difference decides tile counts. Writing
 * `normal . u = cos(latitude) * A * cos(longitude - longitudeTarget) + normal.z * sin(latitude)`
 * with `A = hypot(normal.x, normal.y)`, the inner maximisation over longitude is independent of
 * latitude — `cos(latitude) >= 0` and `A >= 0` over the whole Mercator domain, so the same longitude
 * wins at every latitude. Substituting its value collapses the outer problem to
 * `rho * cos(latitude - latitudeTarget)`, one more nearest-angle clamp. Both clamps are the same
 * wrapped one, [angularGapToInterval].
 *
 * The alternative every renderer reaches for first — an axis-aligned bounding box around the
 * patch — overestimates this by the box's own corners, which at the measured harness camera is
 * about 50 logical pixels against a 4-pixel margin: enough to select a tile column that shows no
 * ground at all.
 */
internal fun maximumOverSphericalRectangle(
    normal: DoubleVector3,
    latitudeCentre: Double,
    latitudeHalfWidth: Double,
    longitudeCentre: Double,
    longitudeHalfWidth: Double,
): Double {
    val horizontalLength = hypot(normal.x, normal.y)
    val longitudeTarget = atan2(normal.y, normal.x)
    val longitudeGap = angularGapToInterval(longitudeTarget, longitudeCentre, longitudeHalfWidth)
    val horizontal = horizontalLength * cos(longitudeGap)

    val magnitude = hypot(horizontal, normal.z)
    if (magnitude == 0.0) return 0.0
    val latitudeTarget = atan2(normal.z, horizontal)
    val latitudeGap = angularGapToInterval(latitudeTarget, latitudeCentre, latitudeHalfWidth)
    return magnitude * cos(latitudeGap)
}

/**
 * The angular distance from [target] to the closed arc of half-width [halfWidth] about [centre], in
 * `[0, PI]`.
 *
 * The wrap is load-bearing rather than defensive: a naive `coerceIn` picks the wrong endpoint
 * whenever the target and the arc straddle the antimeridian — a longitude of `-179` degrees is
 * adjacent to `+179`, not 358 degrees away — and picking the wrong endpoint **under**estimates the
 * maximum, which would exclude a tile that shows ground.
 */
private fun angularGapToInterval(target: Double, centre: Double, halfWidth: Double): Double {
    if (halfWidth >= PI) return 0.0
    val offset = abs(wrappedToHalfTurn(target - centre))
    return min(PI, max(0.0, offset - halfWidth))
}

private fun wrappedToHalfTurn(angle: Double): Double {
    val turns = angle / (2.0 * PI)
    return angle - 2.0 * PI * round(turns)
}

/**
 * Whether any unit direction satisfies every one of [constraints] — spherical convex feasibility,
 * decided by enumerating the only places an answer can hide.
 *
 * Maximise any one constraint's own functional over the feasible set. The maximiser exists whenever
 * the set is non-empty, and exactly three things can be true of it: **no** other constraint is
 * active, so it is that constraint's own normal; **one** is active, so it is the point of that
 * constraint's circle nearest the objective ([extremeOnCircle]); or **two or more** are active, so
 * it lies on two circles at once ([circleIntersections]). Enumerating those three families is
 * therefore complete, and the middle family is not optional — dropping it misses a feasible region
 * bounded by two disjoint circles with no corner between them, which is what an intersection of two
 * caps larger than a hemisphere looks like.
 *
 * Cost is cubic in the constraint count and the count is at most ten, but the call is reached only
 * for cells that survive [GlobeGroundFootprint.mayAdmitMercatorCell] *and* whose sampled
 * representatives are all outside — the perimeter of the visible ground rather than its area.
 */
private fun anyFeasibleDirection(constraints: List<GlobeGroundHalfSpace>): Boolean {
    for (constraint in constraints) {
        if (satisfiesAll(constraints, constraint.normal)) return true
    }
    for (first in constraints.indices) {
        for (second in constraints.indices) {
            if (first == second) continue
            val extreme = extremeOnCircle(constraints[second], constraints[first].normal)
            if (extreme != null && satisfiesAll(constraints, extreme)) return true
            if (second > first) {
                for (candidate in circleIntersections(constraints[first], constraints[second])) {
                    if (satisfiesAll(constraints, candidate)) return true
                }
            }
        }
    }
    return false
}

private fun satisfiesAll(
    constraints: List<GlobeGroundHalfSpace>,
    direction: DoubleVector3,
): Boolean = constraints.all { it.admits(direction) }

/** The point of [circle]'s boundary circle maximising `objective . u`, or `null` when that circle
 * does not exist. When the objective is parallel to the circle's axis every point is equally
 * extreme, so an arbitrary one is returned rather than nothing. */
private fun extremeOnCircle(
    circle: GlobeGroundHalfSpace,
    objective: DoubleVector3,
): DoubleVector3? {
    val axis = circle.normal
    val height = circle.offset
    if (!(abs(height) <= 1.0)) return null
    val radius = sqrt(max(0.0, 1.0 - height * height))
    val tangential = objective - axis * axis.dot(objective)
    val tangentialLength = sqrt(tangential.dot(tangential))
    val direction = if (tangentialLength > CIRCLE_DEGENERACY_TOLERANCE) {
        tangential * (1.0 / tangentialLength)
    } else {
        anyPerpendicular(axis)
    }
    return axis * height + direction * radius
}

/** The at most two unit directions lying on both constraints' boundary circles. */
private fun circleIntersections(
    first: GlobeGroundHalfSpace,
    second: GlobeGroundHalfSpace,
): List<DoubleVector3> {
    val alignment = first.normal.dot(second.normal)
    val determinant = 1.0 - alignment * alignment
    if (determinant <= CIRCLE_DEGENERACY_TOLERANCE) return emptyList()

    val firstWeight = (first.offset - second.offset * alignment) / determinant
    val secondWeight = (second.offset - first.offset * alignment) / determinant
    val base = first.normal * firstWeight + second.normal * secondWeight
    val remainder = 1.0 - base.dot(base)
    if (remainder < 0.0) return emptyList()

    val axis = first.normal.cross(second.normal)
    val scale = sqrt(remainder / determinant)
    val offset = axis * scale
    return listOf(base + offset, base - offset)
}

private fun anyPerpendicular(axis: DoubleVector3): DoubleVector3 {
    val seed = if (abs(axis.z) < 0.5) DoubleVector3(0.0, 0.0, 1.0) else DoubleVector3(1.0, 0.0, 0.0)
    val perpendicular = axis.cross(seed)
    val length = sqrt(perpendicular.dot(perpendicular))
    return perpendicular * (1.0 / length)
}

/**
 * One Mercator cell as the sphere sees it: a latitude interval and a longitude interval, both from
 * [unitSphereDirection] rather than from a second latitude formulation, so a cell's corner and the
 * ground vertex drawn at that corner cannot disagree.
 */
private class MercatorCell(
    val latitudeCentre: Double,
    val latitudeHalfWidth: Double,
    val longitudeCentre: Double,
    val longitudeHalfWidth: Double,
    val northSine: Double,
    val southSine: Double,
) {
    /**
     * The cell as half-spaces: two parallels, and — unless the cell spans every longitude, which
     * only the single LOD 0 cell does — two meridians. `n . u = cos(latitude) * sin(longitude -
     * bound)`, so a meridian bound is a plane through the polar axis and the pair of them is an
     * intersection exactly while the span does not exceed half a turn, which every LOD from 1
     * downward satisfies.
     */
    fun halfSpaces(): List<GlobeGroundHalfSpace> {
        val cell = ArrayList<GlobeGroundHalfSpace>(4)
        cell += GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, 1.0), southSine)
        cell += GlobeGroundHalfSpace(DoubleVector3(0.0, 0.0, -1.0), -northSine)
        if (longitudeHalfWidth < PI - FULL_LONGITUDE_TOLERANCE) {
            val west = longitudeCentre - longitudeHalfWidth
            val east = longitudeCentre + longitudeHalfWidth
            cell += GlobeGroundHalfSpace(DoubleVector3(-sin(west), cos(west), 0.0), 0.0)
            cell += GlobeGroundHalfSpace(DoubleVector3(sin(east), -cos(east), 0.0), 0.0)
        }
        return cell
    }

    /**
     * Five directions inside the cell — its centre and its four corners — tried before the exact
     * decision because a cell in the interior of the visible ground contains all five, and the
     * interior is most of what a frame selects.
     */
    fun representativeDirections(): List<DoubleVector3> {
        val latitudes = doubleArrayOf(
            latitudeCentre,
            latitudeCentre - latitudeHalfWidth,
            latitudeCentre + latitudeHalfWidth,
        )
        val longitudes = doubleArrayOf(
            longitudeCentre,
            longitudeCentre - longitudeHalfWidth,
            longitudeCentre + longitudeHalfWidth,
        )
        val directions = ArrayList<DoubleVector3>(5)
        directions += direction(latitudes[0], longitudes[0])
        for (latitudeIndex in 1..2) {
            for (longitudeIndex in 1..2) {
                directions += direction(latitudes[latitudeIndex], longitudes[longitudeIndex])
            }
        }
        return directions
    }

    private fun direction(latitude: Double, longitude: Double): DoubleVector3 {
        val cosineLatitude = cos(latitude)
        return DoubleVector3(
            x = cosineLatitude * cos(longitude),
            y = cosineLatitude * sin(longitude),
            z = sin(latitude),
        )
    }
}

private fun mercatorCell(
    minimumX: Double,
    maximumX: Double,
    minimumY: Double,
    maximumY: Double,
): MercatorCell {
    // `unitSphereDirection` at Mercator x = 0.5 is longitude 0, so its `x` is cos(latitude) and its
    // `z` is sin(latitude): task 1's own latitude path, reused rather than restated.
    val north = unitSphereDirection(HALF_WORLD, minimumY)
    val south = unitSphereDirection(HALF_WORLD, maximumY)
    val northLatitude = atan2(north.z, north.x)
    val southLatitude = atan2(south.z, south.x)
    val westLongitude = 2.0 * PI * (minimumX - HALF_WORLD)
    val eastLongitude = 2.0 * PI * (maximumX - HALF_WORLD)
    return MercatorCell(
        latitudeCentre = 0.5 * (northLatitude + southLatitude),
        latitudeHalfWidth = 0.5 * (northLatitude - southLatitude),
        longitudeCentre = 0.5 * (westLongitude + eastLongitude),
        longitudeHalfWidth = 0.5 * (eastLongitude - westLongitude),
        northSine = north.z,
        southSine = south.z,
    )
}

private const val SIDE_PLANE_COUNT: Int = 4
private const val HALF_WORLD: Double = 0.5

/**
 * Inclusive slack on every half-space test, on the unit sphere. It is chosen to fail towards
 * drawing: a tile admitted by a sliver narrower than this is drawn and covers nothing visible,
 * whereas a tile excluded by one is a hole in the ground. Four orders of magnitude above `Double`'s
 * own relative resolution and four below the tightest measured margin in
 * `docs/research/2026-08-28-g-globe-rentile-tiles.md` §3.2, which is 8 x 10^-3 of a tile.
 */
private const val ADMISSION_TOLERANCE: Double = 1e-12
private const val CIRCLE_DEGENERACY_TOLERANCE: Double = 1e-12
private const val FULL_LONGITUDE_TOLERANCE: Double = 1e-9
