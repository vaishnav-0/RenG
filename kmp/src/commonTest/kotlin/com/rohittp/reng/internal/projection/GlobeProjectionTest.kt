package com.rohittp.reng.internal.projection

import com.rohittp.reng.internal.math.DoubleVector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cycle G task 1: `(latitude, longitude, altitude) -> unit sphere -> world`.
 *
 * **Every case here is written against a symmetry-point hazard**, because the equator and the two
 * clip latitudes are symmetry points of this entire calculation. At the equator every formulation
 * agrees trivially — a hemisphere-swapped `psi`, an east/west-mirrored longitude and Mercator's own
 * `1 / cos(latitude)` altitude distortion all land on exactly the right answer there — and near
 * `+/-85.05` the values saturate. So the sweep below lands on **no** round latitude, **no** round
 * longitude, neither clip and not the equator, and it is balanced across both hemispheres. The
 * three points it avoids are measured on their own in
 * [theSymmetryPointsAreMeasuredSeparatelyAndProveLittle], which says what each of them cannot catch.
 *
 * The precision of the *formulation* is not measured here: it is measured in
 * [LatitudePrecisionSpikeTest], which models a float32 GPU. In `Double` the tangent half-angle form
 * and the naive `2 * atan(exp psi) - PI / 2` agree to 2.8 nanometres, so no assertion in this file
 * can tell them apart, and none pretends to.
 *
 * **Measured against nine deliberate breakages of `GlobeProjection.kt`, rather than reviewed.** Each
 * was applied on its own and the suite run; every one was caught, and five by exactly one case.
 *
 * | breakage | cases that failed |
 * |---|---|
 * | longitude wrap dropped | [mercatorWorldCopiesCollapseOntoOneSpherePoint], [theAntimeridianIsContinuous] |
 * | altitude divided by `cos(latitude)` | [altitudeIsRadialAndUniformRatherThanMercatorsCosineDivided] |
 * | `(sin phi, cos phi)` pair swapped | 4, all but the four altitude, radius, unit-length and domain cases |
 * | `psi` sign flipped | [latitudeIncreasesStrictlyAsMercatorYDecreases] and 2 others |
 * | longitude mirrored east/west | [theUnitSphereMatchesAnIndependentSphericalConstructionAcrossAnAsymmetricSweep] |
 * | radius `worldSize / PI` | [theGlobeRadiusMakesTheEquatorAsLongAsTheMercatorWorld] |
 * | denominator `t^2 + 2` | 4, including [theUnitSphereDirectionStaysUnitLength] |
 * | altitude added in metres | [altitudeIsRadialAndUniformRatherThanMercatorsCosineDivided] |
 * | domain guard removed | [anOutOfDomainMercatorYIsRejectedRatherThanReturningNaN] |
 *
 * Two negatives from that run are worth as much as the catches. [theUnitSphereDirectionStaysUnitLength]
 * fired for the denominator alone and stayed green for the swapped pair, the flipped `psi` and the
 * mirrored longitude, which is exactly what its own KDoc claims. And
 * [theSymmetryPointsAreMeasuredSeparatelyAndProveLittle] stayed green for the mirrored longitude, the
 * cosine-divided altitude and the wrong radius — it sits on the prime meridian, at the equator and at
 * zero altitude, so it is blind to all three by construction.
 */
class GlobeProjectionTest {

    // ------------------------------------------------------------ independent reference

    /**
     * The sphere point of a geodetic latitude and longitude, built from `sin` and `cos` of the
     * angles themselves.
     *
     * Deliberately shares nothing with [unitSphereDirection]: no Mercator `y`, no isometric
     * latitude, no `exp`, and no wrap of a normalised `x`. The sweep feeds the production chain a
     * latitude and this function the same latitude, so agreement exercises
     * `latitude -> asinh(tan) -> y -> psi -> exp -> (sin phi, cos phi)` end to end rather than
     * comparing two spellings of its second half.
     */
    private fun referenceDirection(latitudeDegrees: Double, longitudeDegrees: Double): DoubleVector3 {
        val latitude = latitudeDegrees * PI / 180.0
        val longitude = longitudeDegrees * PI / 180.0
        return DoubleVector3(
            x = cos(latitude) * cos(longitude),
            y = cos(latitude) * sin(longitude),
            z = sin(latitude),
        )
    }

    /** Great-circle separation of two unit directions, in metres at earth radius. */
    private fun separationMetres(first: DoubleVector3, second: DoubleVector3): Double {
        val cross = first.cross(second)
        val sine = sqrt(cross.dot(cross))
        return WGS84_SEMI_MAJOR_AXIS_METRES * atan2(sine, first.dot(second))
    }

    private fun length(vector: DoubleVector3): Double = sqrt(vector.dot(vector))

    private fun normalise(vector: DoubleVector3): DoubleVector3 = vector * (1.0 / length(vector))

    /** The latitude a normalised Mercator `y` denotes, by the best-conditioned `Double` form. */
    private fun latitudeDegreesOf(mercatorY: Double): Double =
        atan(sinh(PI * (1.0 - 2.0 * mercatorY))) * 180.0 / PI

    // ---------------------------------------------------------------------- samples

    private data class Sample(val latitudeDegrees: Double, val longitudeDegrees: Double)

    /**
     * 20,032 samples that land on nothing round.
     *
     * Latitudes come from a golden-ratio additive sweep over Mercator `y` — which visits both
     * hemispheres uniformly and never repeats a binade pattern — plus geometric ladders with
     * irrational ratios running toward the equator from both sides and toward each clip latitude
     * separately, down to `1e-9` degrees of either. Longitudes come from a second, differently
     * seeded irrational sweep over the full `[-180, 180)` range, so no sample pairs a round latitude
     * with a round longitude and none sits on the prime meridian or the antimeridian.
     */
    private fun samples(): List<Sample> {
        val out = ArrayList<Sample>(20_100)
        var y = 0.1234567890123457
        var longitude = -173.31719387755102
        fun advance() {
            y += 0.6180339887498949
            if (y >= 1.0) y -= 1.0
            longitude += 141.4213562373095
            if (longitude >= 180.0) longitude -= 360.0
        }
        repeat(20_000) {
            advance()
            if (y > 0.0 && y < 1.0 && y != 0.5) out.add(Sample(latitudeDegreesOf(y), longitude))
        }
        var toEquator = 41.7
        while (toEquator > 1e-9) {
            advance()
            out.add(Sample(toEquator, longitude))
            advance()
            out.add(Sample(-toEquator * 0.7237, longitude))
            toEquator *= 0.37
        }
        var toClip = 41.7
        while (toClip > 1e-9) {
            advance()
            out.add(Sample(MERCATOR_MAXIMUM_LATITUDE_DEGREES - toClip, longitude))
            advance()
            out.add(Sample(-(MERCATOR_MAXIMUM_LATITUDE_DEGREES - toClip * 0.5311), longitude))
            toClip *= 0.41
        }
        return out
    }

    private val sweep by lazy { samples() }

    private fun say(line: String) = println("[G1] $line")

    // ------------------------------------------------------------------------ cases

    /**
     * The whole chain against an independent spherical construction.
     *
     * Catches a hemisphere-swapped `psi`, an east/west-mirrored longitude, a `sin`/`cos` swap in the
     * half-angle pair and a wrong denominator. It cannot catch anything about altitude (every sample
     * is at zero) or about world copies (every longitude is already canonical).
     */
    @Test
    fun theUnitSphereMatchesAnIndependentSphericalConstructionAcrossAnAsymmetricSweep() {
        var worst = 0.0
        var worstSample = sweep.first()
        var northernChecked = 0
        var southernChecked = 0
        for (sample in sweep) {
            val produced = projectGlobe(
                position = GeographicPosition(sample.latitudeDegrees, sample.longitudeDegrees, 0.0),
                radiusLogicalPixels = 1.0,
            )
            val reference = referenceDirection(sample.latitudeDegrees, sample.longitudeDegrees)
            val separation = separationMetres(produced, reference)
            if (separation > worst) {
                worst = separation
                worstSample = sample
            }
            if (sample.latitudeDegrees > 0.0) northernChecked++ else southernChecked++
        }

        say("sweep ${sweep.size} samples ($northernChecked north, $southernChecked south)")
        say(
            "worst chain-vs-reference separation ${worst}m at lat ${worstSample.latitudeDegrees} " +
                "lon ${worstSample.longitudeDegrees}",
        )
        assertTrue(northernChecked > 9_000, "the sweep must cover the northern hemisphere densely")
        assertTrue(southernChecked > 9_000, "the sweep must cover the southern hemisphere densely")
        assertTrue(worst < 1.0e-6, "worst separation was ${worst}m")
    }

    /**
     * The direction is a unit vector, so [projectGlobe]'s radial scale is the only thing setting
     * distance from the sphere's centre.
     *
     * Narrow on purpose: swapping the half-angle pair, flipping `psi` and mirroring the longitude
     * all preserve the norm, so this case is blind to every one of them and catches only a broken
     * `t^2 + 1` denominator.
     */
    @Test
    fun theUnitSphereDirectionStaysUnitLength() {
        var worst = 0.0
        var worstSample = sweep.first()
        for (sample in sweep) {
            val position = GeographicPosition(sample.latitudeDegrees, sample.longitudeDegrees, 0.0)
            val mercator = projectMercator(position)
            val deviation = abs(length(unitSphereDirection(mercator.x, mercator.y)) - 1.0)
            if (deviation > worst) {
                worst = deviation
                worstSample = sample
            }
        }

        say("worst radial deviation ${worst * WGS84_SEMI_MAJOR_AXIS_METRES}m at lat ${worstSample.latitudeDegrees}")
        assertTrue(worst < 1.0e-14, "worst radial deviation was $worst")
    }

    /**
     * Mercator's world copies collapse on a sphere: `CONTEXT.md` records that a camera at longitude
     * 400 sees exactly what one at longitude 40 sees.
     *
     * The sweep's longitudes are all canonical, so `x - floor(x)` is the identity there and the sweep
     * is blind to the wrap being dropped; this is the only case that catches it *as a distance*.
     * ([theAntimeridianIsContinuous] also fires, but only through its exact-equality assertion on the
     * two spellings of `+/-180`, where the difference is the sign of a `1.2e-16` sine.) The far copy
     * is carried to RenG's `+/-16384` limit, where `x - floor(x)` has spent fourteen bits of
     * `Double`'s mantissa on the copy index; the residue is asserted rather than assumed.
     */
    @Test
    fun mercatorWorldCopiesCollapseOntoOneSpherePoint() {
        val latitude = -37.81361
        val base = GeographicPosition(latitude, 40.31702, 0.0)
        val cases = listOf(
            "one turn east" to base.copy(unwrappedLongitude = base.unwrappedLongitude + 360.0),
            "one turn west" to base.copy(unwrappedLongitude = base.unwrappedLongitude - 360.0),
            "16383 turns east" to base.copy(unwrappedLongitude = base.unwrappedLongitude + 360.0 * 16383.0),
            "16383 turns west" to base.copy(unwrappedLongitude = base.unwrappedLongitude - 360.0 * 16383.0),
        )
        val reference = projectGlobe(base, 1.0)

        var worst = 0.0
        for ((name, position) in cases) {
            val separation = separationMetres(projectGlobe(position, 1.0), reference)
            say("copy '$name' separation ${separation}m")
            if (separation > worst) worst = separation
        }

        assertTrue(worst < 1.0e-5, "worst world-copy separation was ${worst}m")
    }

    /**
     * The antimeridian is continuous: crossing it moves a point by the distance the longitudes
     * differ by, not by half a world.
     *
     * Taken at latitude `-37.8`, where `cos(latitude)` is neither 1 nor 0, so the expected chord
     * carries the latitude and a case that ignored it would show.
     */
    @Test
    fun theAntimeridianIsContinuous() {
        val latitude = -37.81361
        val step = 0.0002
        val west = projectGlobe(GeographicPosition(latitude, 180.0 - step, 0.0), 1.0)
        val east = projectGlobe(GeographicPosition(latitude, -180.0 + step, 0.0), 1.0)
        val expected = 2.0 * step * PI / 180.0 * cos(latitude * PI / 180.0) * WGS84_SEMI_MAJOR_AXIS_METRES
        val measured = separationMetres(west, east)

        say("antimeridian step measured ${measured}m expected ${expected}m")
        assertTrue(abs(measured - expected) < 1.0e-6, "measured ${measured}m against expected ${expected}m")

        val plusOneEighty = projectGlobe(GeographicPosition(latitude, 180.0, 0.0), 1.0)
        val minusOneEighty = projectGlobe(GeographicPosition(latitude, -180.0, 0.0), 1.0)
        assertEquals(minusOneEighty, plusOneEighty, "the two spellings of the antimeridian are one point")
    }

    /**
     * Altitude is radial and uniform on a sphere — **no `1 / cos(latitude)`**.
     *
     * That factor is Mercator's own distortion, and `projectMercator` applies it to altitude while
     * `PlacementResolver` applies it to map scale. Copying either onto a globe is wrong by
     * `1 / cos(latitude)`: **2x at latitude 60**, and **exactly right at the equator**, which is
     * where a fixture naturally gets written. So every assertion here is taken at latitude `61.72`
     * and `-27.31`, and the equatorial reading is computed only to show that it cannot discriminate.
     */
    @Test
    fun altitudeIsRadialAndUniformRatherThanMercatorsCosineDivided() {
        val radius = 4096.0
        val altitude = 8848.86
        val metresPerLogicalPixel = radius / WGS84_SEMI_MAJOR_AXIS_METRES
        val expected = radius + altitude * metresPerLogicalPixel

        for (latitude in listOf(61.72, -53.41, 0.0)) {
            val position = GeographicPosition(latitude, 103.85, altitude)
            val raised = projectGlobe(position, radius)
            val ground = projectGlobe(position.copy(altitudeMetres = 0.0), radius)
            val mercatorSpelling = radius +
                projectMercator(position).z * (2.0 * PI * radius)
            say(
                "lat $latitude radial ${length(raised)} expected $expected " +
                    "mercator-spelling $mercatorSpelling",
            )
            if (latitude == 0.0) continue

            assertTrue(
                abs(length(raised) - expected) < 1.0e-9,
                "radial distance at latitude $latitude was ${length(raised)} against $expected",
            )
            assertTrue(
                separationMetres(normalise(raised), normalise(ground)) < 1.0e-9,
                "raising a position must not move it laterally",
            )
            assertTrue(
                abs(mercatorSpelling - expected) > 0.5 * (expected - radius),
                "the Mercator spelling must differ at latitude $latitude, or this case proves nothing",
            )
        }

        val equatorial = GeographicPosition(0.0, 103.85, altitude)
        val equatorialMercatorSpelling = radius + projectMercator(equatorial).z * (2.0 * PI * radius)
        assertTrue(
            abs(equatorialMercatorSpelling - length(projectGlobe(equatorial, radius))) < 1.0e-6,
            "the two spellings must agree at the equator — that is why no assertion above is taken there",
        )
    }

    /**
     * The globe's equator is as long as the Mercator world is wide, which is what makes the two
     * modes the same size on screen at the camera's own latitude, and what makes
     * [globeMetresToLogicalPixels] agree with Mercator's metre scale at the equator.
     */
    @Test
    fun theGlobeRadiusMakesTheEquatorAsLongAsTheMercatorWorld() {
        for (zoom in listOf(0.0, 3.7, 11.31, 21.93)) {
            val worldSize = 512.0 * 2.0.pow(zoom)
            val radius = globeRadiusLogicalPixels(worldSize)
            val circumference = 2.0 * PI * radius
            assertTrue(
                abs(circumference - worldSize) / worldSize < 1.0e-15,
                "at zoom $zoom the equator measured $circumference against a world of $worldSize",
            )

            val globeScale = globeMetresToLogicalPixels(radius)
            val mercatorScale = worldSize / WORLD_CIRCUMFERENCE_METRES
            say("zoom $zoom globe m->px $globeScale mercator m->px at equator $mercatorScale")
            assertTrue(
                abs(globeScale - mercatorScale) / mercatorScale < 1.0e-15,
                "at zoom $zoom the globe scale $globeScale disagreed with Mercator's $mercatorScale",
            )
        }
    }

    /**
     * Latitude is strictly increasing as Mercator `y` decreases, over consecutive samples spanning
     * the whole domain.
     *
     * A hemisphere-swapped `psi` is still monotonic — decreasing rather than increasing — so the
     * direction of the ordering is the assertion, not the fact of it. This is also the shape the
     * Cycle G task 11 probe measures on a driver: the field symptom in MapLibre #7419 is distinct
     * latitudes *overlapping*, not an offset, and magnitude alone would miss it.
     */
    @Test
    fun latitudeIncreasesStrictlyAsMercatorYDecreases() {
        val ys = (1..4001).map { it / 4002.0 }
        var previous = unitSphereDirection(0.31830988618379069, ys.last()).z
        var checked = 0
        for (index in ys.size - 2 downTo 0) {
            val current = unitSphereDirection(0.31830988618379069, ys[index]).z
            assertTrue(
                current > previous,
                "sin(latitude) must increase as y decreases: at y=${ys[index]} got $current after $previous",
            )
            previous = current
            checked++
        }

        say("monotonic over $checked consecutive steps, ends ${unitSphereDirection(0.5, ys.first()).z}")
        assertTrue(checked == 4000, "expected 4000 comparisons, made $checked")
    }

    /**
     * An out-of-domain Mercator `y` is refused rather than turned into a `NaN` direction.
     *
     * `exp(psi)` overflows outside `[0, 1]` and the rational form then evaluates `inf / inf`. A
     * `NaN` direction compares false against every bound, so it would survive a visibility cull and
     * a horizon test alike and reach the GPU as a hole in the world.
     */
    @Test
    fun anOutOfDomainMercatorYIsRejectedRatherThanReturningNaN() {
        for (y in listOf(-1.0e-9, 1.0 + 1.0e-9, -3.7, 4.2, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("y=$y must be refused") {
                unitSphereDirection(0.61, y)
            }
        }

        assertFailsWith<IllegalArgumentException>("an unvalidated pole must be refused") {
            projectGlobe(GeographicPosition(90.0, 11.3, 0.0), 1.0)
        }

        for (y in listOf(0.0, 1.0e-12, 0.3183, 1.0 - 1.0e-12, 1.0)) {
            val direction = unitSphereDirection(0.61, y)
            assertTrue(direction.z.isFinite() && abs(length(direction) - 1.0) < 1.0e-15, "y=$y must project")
        }
    }

    /**
     * The equator, the two clip latitudes and the two spellings of the antimeridian, measured on
     * their own and reported — **and each with the thing it cannot catch stated**.
     *
     * The equator is where a hemisphere-swapped `psi`, an east/west-mirrored longitude at the prime
     * meridian and Mercator's `1 / cos(latitude)` altitude factor all give exactly the right answer.
     * The clip latitudes are where the sphere is *cut*: RenG's globe has a hole at each pole,
     * because `y` never reaches a pole, and a case that expected `sin(latitude) = 1` at `y = 0`
     * would be asserting a sphere RenG does not draw.
     */
    @Test
    fun theSymmetryPointsAreMeasuredSeparatelyAndProveLittle() {
        val equator = unitSphereDirection(0.5, 0.5)
        say("equator z=${equator.z} x=${equator.x} y=${equator.y}")
        assertEquals(0.0, equator.z, "the equator is the plane z = 0, exactly")

        val clipSine = sin(MERCATOR_MAXIMUM_LATITUDE_DEGREES * PI / 180.0)
        val north = unitSphereDirection(0.25, 0.0)
        val south = unitSphereDirection(0.25, 1.0)
        say("north clip z=${north.z} south clip z=${south.z} against sin(clip)=$clipSine")
        assertTrue(abs(north.z - clipSine) < 1.0e-15, "the north cap is cut at the clip latitude")
        assertTrue(abs(south.z + clipSine) < 1.0e-15, "the south cap is cut at the clip latitude")
        assertTrue(north.z < 1.0 && south.z > -1.0, "RenG's globe has a hole at each pole")

        val mirroredEquator = unitSphereDirection(0.5, 0.5)
        assertEquals(
            0.0,
            mirroredEquator.y,
            "at the prime meridian an east/west mirror is invisible — which is why the sweep avoids it",
        )
    }
}
