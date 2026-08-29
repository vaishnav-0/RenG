package com.rohittp.reng.internal.projection

import com.rohittp.reng.internal.math.DoubleVector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * The unit-sphere direction of a normalised Mercator coordinate: `(cos phi cos lambda,
 * cos phi sin lambda, sin phi)`, unit length to within a few ULP of `Double`. Cycle G's
 * `(latitude, longitude, altitude) -> unit sphere -> world` starts here; no GL is involved and
 * nothing in this file knows what a shader is.
 *
 * ## Why the input is a Mercator coordinate rather than a latitude
 *
 * Rentile has no notion of a globe and hands RenG square Mercator raster tiles, so a globe vertex is
 * parameterised by normalised Mercator `(x, y)` — a ground-tile corner has no latitude at all, only
 * a `y`. Routing placements through the same coordinate is what keeps a placement and the ground it
 * stands on derived from one formulation rather than two that can drift apart.
 *
 * [mercatorY] must lie in `[0, 1]`, which is exactly [isWithinMercatorPlanningSupport]'s own `y`
 * range, so any position that has passed [validateMercatorMapPosition] or one of its siblings
 * qualifies. The bound is enforced rather than clamped: outside it `exp psi` overflows and the
 * rational form below returns `NaN`, and a `NaN` direction would propagate silently through every
 * consumer of this function. A caller that has not validated its position is the defect being
 * reported.
 *
 * [mercatorX] is unbounded and **wrapped**, because on a sphere Mercator's world copies collapse:
 * `CONTEXT.md` records that a camera at longitude 400 sees exactly what one at longitude 40 sees.
 * The wrap is a precision requirement as well as a semantic one — `sin` and `cos` of an argument
 * near RenG's `+/-16384` copy limit have lost most of their mantissa to argument reduction — and
 * `x - floor(x)` is [mercatorCopyIndex]'s own reduction applied to `x` instead of to degrees.
 *
 * ## The latitude path, and exactly what it guarantees
 *
 * `y` carries the **isometric latitude** `psi = PI * (1 - 2y)`, and the sphere needs only
 * `(sin phi, cos phi)`, never `phi` itself. With `t = exp psi`:
 *
 * ```
 * sin phi = (t^2 - 1) / (t^2 + 1)          cos phi = 2t / (t^2 + 1)
 * ```
 *
 * — the tangent half-angle identities, which are exactly `(tanh psi, sech psi)` written in the one
 * transcendental the GLSL ES 3.00 specification bounds. `atan`, `sin` and `cos` leave the latitude
 * path entirely. Spelling it `tanh(psi)` and `1.0 / cosh(psi)` instead would be mathematically
 * identical and **precision-wise no better than the naive form**: section 4.5.1 gives the hyperbolic
 * built-ins no bound either.
 *
 * `docs/research/2026-08-28-g-latitude-precision-spike.md` measured this over 200,134 asymmetric
 * float32 samples. The naive `2 * atan(exp psi) - PI / 2` costs **1.661 metres of latitude per ULP
 * of `atan`/`sin`/`cos` error**, worst case over the domain; this form is moved by that error by
 * **exactly zero**, bit for bit, out to 1024 ULP, where the naive form is 1,700 metres out.
 * Inverting the measurement, MapLibre's field-observed 200-300 metres on Mali-G610/G710 implies
 * roughly 120-180 ULP of `atan` error. `LatitudePrecisionSpikeTest` is that measurement, kept.
 *
 * **Three things the spike established that a reader here must not be allowed to over-read.**
 *
 * 1. **The guarantee is only as good as `exp`.** At `exp`'s own *specified* `3 + 2|x|` ULP bound —
 *    at most `3 + 2 * PI = 9.283` ULP here, since `|psi| <= PI` — this path is **8.7 metres** out
 *    and inverts monotonicity slightly *more* often than the naive one. It converts an **unbounded**
 *    error into a **specified** one; it does not make the path exact. Only a runtime probe on a real
 *    driver can check that `exp` assumption, and that is Cycle G's task 11 rather than this file's.
 * 2. **MapLibre's stated reason for its own fix is wrong, and RenG must not inherit it.** Its source
 *    blames subtracting `PI / 2` from a value near `PI / 2` for equatorial precision loss.
 *    Measurement shows the culprit is the shared `PI - y * PI * 2.0` prelude one line *above* either
 *    identity: it has already quantised `psi` onto a 2.38 x 10^-7 rad grid, and
 *    `d phi / d psi = cos phi = 1` at the equator, so both identities inherit the same 0.65 metre
 *    float32 floor and the two forms agree there to **2 micrometres**. What actually lifts that floor
 *    is handing the shader a *tile-local* coordinate rather than a global one, dividing the floor by
 *    `2^zoom`. This function spells the prelude as `PI * (1.0 - 2.0 * y)` — one rounding rather than
 *    the shader's three — because in `Double` it can afford to, and because it is the reference a
 *    driver probe will be compared against.
 * 3. **`precision highp float` is an independent, non-negotiable requirement of any shader written
 *    against this file.** Modelled as binary16, `mediump` costs about **20 km** of latitude either
 *    way, **9.8 km of it before any arithmetic happens at all**. No choice of identity rescues it.
 *
 * A fourth thing, honestly: in `Double` the two forms agree to 2.8 nanometres, so **nothing measured
 * on the CPU can tell them apart**. The half-angle form is used here for permanence rather than for
 * `Double` accuracy — so that the CPU reference and the eventual shader are one expression, and so
 * that a later edit reintroducing trigonometry into the latitude path is a visible change to this
 * file rather than an invisible one.
 *
 * ## The frame the world half produces
 *
 * **Logical pixels, never ECEF metres.** A globe invites a metric frame, and taking that invitation
 * destroys the depth buffer: under RenG's reverse-Z projection window depth is `near / distance`
 * with `near` fixed at one logical pixel ([NEAR_DISTANCE_LOGICAL_PIXELS]) against a 24-bit
 * fixed-point buffer, so the resolvable step at the camera's own distance is **0.025 logical
 * pixels** in RenG's existing unit against **2.4 x 10^6 metres** in ECEF metres with a one-metre
 * near plane at earth radius. Settled by arithmetic rather than by preference
 * (`docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` section 3).
 *
 * [projectGlobe]'s output frame is **globe-fixed**, not camera-relative: the sphere's centre is the
 * origin and the axes are [wgs84LocalFrame]'s ECEF axes — `+z` through the north pole, `+x` through
 * `(0 degrees N, 0 degrees E)` and `+y` through `(0 degrees N, 90 degrees E)`. Turning that into the
 * camera-relative east/north/up logical pixels [ResolvedMercatorCamera]'s view matrix expects is the
 * camera's job, not this file's.
 */
internal fun unitSphereDirection(mercatorX: Double, mercatorY: Double): DoubleVector3 {
    require(mercatorY >= 0.0 && mercatorY <= 1.0) {
        "mercatorY is outside the Mercator domain"
    }

    val isometricLatitude = PI * (1.0 - 2.0 * mercatorY)
    val tangentHalfAngle = exp(isometricLatitude)
    val tangentSquared = tangentHalfAngle * tangentHalfAngle
    val denominator = tangentSquared + 1.0
    val sineLatitude = (tangentSquared - 1.0) / denominator
    val cosineLatitude = 2.0 * tangentHalfAngle / denominator

    val longitudeRadians = PI * (2.0 * (mercatorX - floor(mercatorX)) - 1.0)

    return DoubleVector3(
        x = cosineLatitude * cos(longitudeRadians),
        y = cosineLatitude * sin(longitudeRadians),
        z = sineLatitude,
    )
}

/**
 * The globe's radius in logical pixels, for a Mercator world of [worldSizeLogicalPixels] on a side.
 *
 * The sphere's equator is as long as the Mercator world is wide, so `2 * PI * R = worldSize`. What
 * [worldSizeLogicalPixels] itself should be on a globe is Cycle G's task 2 and task 4 — the
 * latitude-matched convention scales it by `1 / cos(latitude)` — and is deliberately not settled
 * here.
 */
internal fun globeRadiusLogicalPixels(worldSizeLogicalPixels: Double): Double =
    worldSizeLogicalPixels / (2.0 * PI)

/**
 * Metres to logical pixels on a globe of [radiusLogicalPixels].
 *
 * **A sphere has no Mercator distortion, so there is no `1 / cos(latitude)` here and there must
 * never be one.** [projectMercator] divides altitude by `cos(latitude)`, and `PlacementResolver`'s
 * map scale divides by it again; both are Mercator's own area distortion, and both must be dropped
 * when the same quantity is expressed on a globe. Copying either makes a quantity **2x too large at
 * latitude 60** while agreeing **exactly at the equator**, which is precisely where a fixture
 * naturally gets written (`docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` section 8).
 *
 * The scale is the equatorial one, so it agrees with Mercator's *at the equator* by construction:
 * [WORLD_CIRCUMFERENCE_METRES] is `2 * PI * WGS84_SEMI_MAJOR_AXIS_METRES`, hence
 * `R / a == worldSize / C`.
 */
internal fun globeMetresToLogicalPixels(radiusLogicalPixels: Double): Double =
    radiusLogicalPixels / WGS84_SEMI_MAJOR_AXIS_METRES

/**
 * A normalised Mercator coordinate plus an altitude in metres, placed in the globe-fixed world.
 *
 * Altitude is radial and uniform: the point sits at `R + altitude * R / a` from the sphere's centre
 * along its own [unitSphereDirection]. The [MercatorPosition.z] that [projectMercator] would have
 * computed for the same position is **deliberately unused** — it is `altitude / (C * cos latitude)`,
 * and that cosine is the distortion [globeMetresToLogicalPixels] documents as forbidden here.
 */
internal fun projectGlobe(
    mercatorX: Double,
    mercatorY: Double,
    altitudeMetres: Double,
    radiusLogicalPixels: Double,
): DoubleVector3 {
    val direction = unitSphereDirection(mercatorX, mercatorY)
    val radial = radiusLogicalPixels + altitudeMetres * globeMetresToLogicalPixels(radiusLogicalPixels)
    return direction * radial
}

/**
 * `(latitude, longitude, altitude)` -> unit sphere -> world, the whole chain.
 *
 * Composes [projectMercator] rather than restating it, so the globe re-projects exactly the
 * coordinates the basemap tiles are cut in and the two cannot disagree about where a latitude is.
 * It does **not** validate: [position] is expected to have passed [validateMercatorMapPosition] or a
 * sibling already, and an unvalidated latitude beyond the Mercator clip trips
 * [unitSphereDirection]'s domain requirement rather than returning a plausible wrong answer.
 */
internal fun projectGlobe(position: GeographicPosition, radiusLogicalPixels: Double): DoubleVector3 {
    val mercator = projectMercator(position)
    return projectGlobe(
        mercatorX = mercator.x,
        mercatorY = mercator.y,
        altitudeMetres = position.altitudeMetres,
        radiusLogicalPixels = radiusLogicalPixels,
    )
}
