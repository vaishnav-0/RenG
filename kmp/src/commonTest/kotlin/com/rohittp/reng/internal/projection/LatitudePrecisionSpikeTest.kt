package com.rohittp.reng.internal.projection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle G preflight spike (G5) — is the tangent-half-angle latitude path actually sound and precise
 * enough to replace `2*atan(exp(y)) - PI/2` on a GPU whose trigonometric functions the GLSL ES
 * specification refuses to bound?
 *
 * This is CPU arithmetic. It measures formulations, not drivers. See
 * docs/research/2026-08-28-g-latitude-precision-spike.md.
 *
 * **Kept unaltered by Cycle G task 1, as the justification for the shape of
 * [unitSphereDirection].** Nothing here imports the production code — the model quantises to float32
 * and injects ULP errors into individual built-ins, which `Double` arithmetic cannot express — so
 * this file is the *argument* for the tangent half-angle form and `GlobeProjectionTest` is the
 * check on the code that adopts it. Read them together: in `Double` the two formulations agree to
 * 2.8 nanometres, so nothing in `GlobeProjectionTest` can tell them apart, and the reason RenG
 * spells the latitude path without trigonometry is measured only here.
 */
class LatitudePrecisionSpikeTest {

    // ---------------------------------------------------------------- constants

    private val earthRadiusMetres = 6378137.0
    private val clipLatitudeDegrees = 85.0511287798066

    // ------------------------------------------------------- exact Double references

    /** Isometric latitude (inverse Gudermannian of the latitude) for RenG's normalised mercator y. */
    private fun isometricLatitude(y: Double): Double = PI * (1.0 - 2.0 * y)

    /** Reference latitude. `atan(sinh)` is the best-conditioned of the three equivalent Double forms. */
    private fun exactLatitude(y: Double): Double = atan(sinh(isometricLatitude(y)))

    private fun latitudeDegreesToY(degrees: Double): Double {
        val radians = degrees * PI / 180.0
        return (1.0 - asinh(tan(radians)) / PI) / 2.0
    }

    // ------------------------------------------------------- reduced-precision model

    /**
     * An IEEE-754 binary format described by its stored mantissa bits and its minimum normal
     * exponent. `float32` is (23, -126, 127); GLSL ES `mediump` in practice is binary16,
     * (10, -14, 15). The GLSL ES 3.00 floor for `mediump` is looser still: relative precision 2^-10.
     */
    private inner class Format(
        val name: String,
        val mantissaBits: Int,
        val minNormalExponent: Int,
        val maxNormalExponent: Int,
    ) {
        fun ulpAt(value: Double): Double {
            if (value == 0.0) return powerOfTwo(minNormalExponent - mantissaBits)
            return powerOfTwo(max(binaryExponent(value), minNormalExponent) - mantissaBits)
        }

        fun quantise(value: Double): Double {
            if (value == 0.0 || !value.isFinite()) return value
            val e = binaryExponent(value)
            if (e > maxNormalExponent) return if (value > 0.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            val step = powerOfTwo(max(e, minNormalExponent) - mantissaBits)
            val rounded = roundHalfEven(value / step) * step
            if (rounded == 0.0) return if (value < 0.0) -0.0 else 0.0
            if (binaryExponent(rounded) > maxNormalExponent) {
                return if (value > 0.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
            }
            return rounded
        }
    }

    /** Exact for -1022 <= exponent <= 1023, which covers every value this spike produces. */
    private fun powerOfTwo(exponent: Int): Double = Double.fromBits((exponent + 1023).toLong() shl 52)

    /** floor(log2(|value|)), read straight out of the Double's exponent field. */
    private fun binaryExponent(value: Double): Int {
        val biased = ((value.toRawBits() ushr 52) and 0x7FFL).toInt()
        if (biased == 0) return floor(log2(abs(value))).toInt()
        return biased - 1023
    }

    private fun roundHalfEven(x: Double): Double {
        val f = floor(x)
        val fraction = x - f
        return when {
            fraction > 0.5 -> f + 1.0
            fraction < 0.5 -> f
            else -> if (f % 2.0 == 0.0) f else f + 1.0
        }
    }

    private val float32 = Format("float32 (highp)", 23, -126, 127)
    private val float16 = Format("binary16 (mediump)", 10, -14, 15)

    /**
     * A modelled shader evaluation. Every arithmetic result is rounded to [format]; every built-in
     * carries a settable error in ULPs of its own result.
     *
     * `exp` is the only transcendental with a specified bound: GLSL ES 3.00 Table 4.5 gives it
     * `3 + 2*|x|` ULP. `atan`, `sin` and `cos` have no bound at all, which is why [trigUlp] is a
     * sweep rather than a constant.
     */
    private inner class Gpu(
        val format: Format,
        val expUlp: Double = 0.0,
        val trigUlp: Double = 0.0,
        val divisionUlp: Double = 0.0,
        val noisy: Boolean = false,
    ) {
        fun r(v: Double): Double = format.quantise(v)

        private fun withError(exact: Double, ulps: Double, seed: Double): Double {
            if (ulps == 0.0) return r(exact)
            val scale = if (noisy) hashNoise(seed) else 1.0
            return r(exact + ulps * scale * format.ulpAt(exact))
        }

        fun expOp(x: Double): Double = withError(exp(x), expUlp, x)
        fun atanOp(x: Double): Double = withError(atan(x), trigUlp, x)
        fun sinOp(x: Double): Double = withError(sin(x), trigUlp, x + 1.0)
        fun cosOp(x: Double): Double = withError(cos(x), trigUlp, x + 2.0)
        fun div(a: Double, b: Double): Double = withError(a / b, divisionUlp, a)

        /** `PI - mercator_y * PI * 2.0`, exactly as MapLibre's shader spells it. */
        fun psi(quantisedY: Double): Double {
            val pi = r(PI)
            return r(pi - r(r(quantisedY * pi) * 2.0))
        }

        /** Candidate: tangent half-angle. `exp`, multiply, add, divide. No trigonometry. */
        fun halfAngleSinCos(quantisedY: Double): DoubleArray {
            val t = expOp(psi(quantisedY))
            val t2 = r(t * t)
            val denom = r(t2 + 1.0)
            val sinPhi = div(r(t2 - 1.0), denom)
            val cosPhi = div(r(2.0 * t), denom)
            return doubleArrayOf(sinPhi, cosPhi)
        }

        /** Reference: the textbook inverse, then sin/cos of it. Three unbounded built-ins. */
        fun naiveSinCos(quantisedY: Double): DoubleArray {
            val t = expOp(psi(quantisedY))
            val phi = r(r(2.0 * atanOp(t)) - r(PI / 2.0))
            return doubleArrayOf(sinOp(phi), cosOp(phi))
        }

        /** Latitude the vertex actually lands at, recovered outside the shader in full precision. */
        fun recoveredLatitude(sinCos: DoubleArray): Double = atan2(sinCos[0], sinCos[1])
    }

    private fun hashNoise(x: Double): Double {
        var h = x.toRawBits()
        h = h xor (h ushr 30)
        h *= 6364136223846793005L
        h = h xor (h ushr 27)
        h *= 2685821657736338717L
        h = h xor (h ushr 31)
        return (h ushr 11).toDouble() / 9007199254740992.0 * 2.0 - 1.0
    }

    // ------------------------------------------------------------------ samples

    /**
     * Asymmetric, dense, both hemispheres, and deliberately never landing on the equator or on
     * either clip latitude — both are symmetry points at which every formulation here agrees
     * trivially. Those two points are measured separately, in [symmetryPointsAreMeasuredSeparately].
     */
    private fun sampleYs(): DoubleArray {
        val out = ArrayList<Double>(220_000)
        var u = 0.123456789012345
        repeat(200_000) {
            u += 0.6180339887498949
            if (u >= 1.0) u -= 1.0
            out.add(u)
        }
        var d = 40.0
        while (d > 1e-11) {
            out.add(latitudeDegreesToY(d))
            out.add(latitudeDegreesToY(-d * 0.7237))
            d *= 0.37
        }
        var g = 40.0
        while (g > 1e-11) {
            out.add(latitudeDegreesToY(clipLatitudeDegrees - g))
            out.add(latitudeDegreesToY(-(clipLatitudeDegrees - g * 0.5311)))
            g *= 0.41
        }
        for (lat in listOf(51.5074, -33.8688, 1.3521, -0.1807, 64.1466, -54.8019, 78.2232, -12.0464)) {
            out.add(latitudeDegreesToY(lat))
        }
        return out.filter { it > 0.0 && it < 1.0 && it != 0.5 }.toDoubleArray()
    }

    private val samples by lazy { sampleYs() }
    private val exactLatitudes by lazy { DoubleArray(samples.size) { exactLatitude(samples[it]) } }

    private fun bandOf(latitudeRadians: Double): Int {
        val degrees = abs(latitudeRadians) * 180.0 / PI
        return when {
            degrees < 0.001 -> 0
            degrees < 1.0 -> 1
            degrees < 30.0 -> 2
            degrees < 60.0 -> 3
            degrees < 84.0 -> 4
            else -> 5
        }
    }

    private val bandNames = arrayOf("|lat|<0.001", "0.001-1", "1-30", "30-60", "60-84", "84-85.05")

    private fun say(line: String) = println("[G5] $line")

    // ------------------------------------------------------------------- tests

    @Test
    fun theQuantiserReproducesIeeeFloat32Rounding() {
        val f = float32
        var checked = 0
        var u = 0.31830988618379069
        repeat(20_000) {
            u += 0.6180339887498949
            if (u >= 1.0) u -= 1.0
            for (scale in listOf(1e-45, 1e-38, 1e-8, 1.0, 3.0, 1e8, 1e30)) {
                for (sign in listOf(1.0, -1.0)) {
                    val v = sign * u * scale
                    val expected = v.toFloat().toDouble()
                    val actual = f.quantise(v)
                    assertEquals(
                        expected,
                        actual,
                        "quantise disagreed with Float rounding at $v (expected $expected, got $actual)",
                    )
                    checked++
                }
            }
        }
        for (v in listOf(1.0, 0.5, 0.9999999999, 1.0000001, 16777217.0, -16777217.0, 1e-46, 5.9e-39)) {
            assertEquals(v.toFloat().toDouble(), f.quantise(v), "quantise disagreed at $v")
            checked++
        }
        say("quantiser self-check: $checked values agree bit-for-bit with Kotlin's Double->Float")
    }

    @Test
    fun theHalfAngleIdentityHoldsExactlyInDoublePrecision() {
        // Three independent Double formulations of the same quantity, plus the closed form the
        // half-angle rational is: sin(gd(psi)) = tanh(psi), cos(gd(psi)) = sech(psi).
        var worstNaive = 0.0
        var worstNaiveAt = 0.0
        var worstRational = 0.0
        var worstRationalAt = 0.0
        var worstTanh = 0.0
        var worstTanhAt = 0.0
        for (i in samples.indices) {
            val y = samples[i]
            val psi = isometricLatitude(y)
            val reference = exactLatitudes[i]

            val naive = 2.0 * atan(exp(psi)) - PI / 2.0
            val t = exp(psi)
            val t2 = t * t
            val rational = atan2((t2 - 1.0) / (t2 + 1.0), 2.0 * t / (t2 + 1.0))
            val hyperbolic = atan2(tanh(psi), 1.0 / cosh(psi))

            if (abs(naive - reference) > worstNaive) { worstNaive = abs(naive - reference); worstNaiveAt = reference }
            if (abs(rational - reference) > worstRational) { worstRational = abs(rational - reference); worstRationalAt = reference }
            if (abs(hyperbolic - reference) > worstTanh) { worstTanh = abs(hyperbolic - reference); worstTanhAt = reference }
        }
        val deg = 180.0 / PI
        say("Double identity check over ${samples.size} asymmetric samples:")
        say("  2*atan(exp(psi))-PI/2 vs atan(sinh(psi)): max ${worstNaive} rad " +
            "(${worstNaive * earthRadiusMetres} m) at lat ${worstNaiveAt * deg}")
        say("  (t^2-1)/(t^2+1),2t/(t^2+1) vs reference:  max ${worstRational} rad " +
            "(${worstRational * earthRadiusMetres} m) at lat ${worstRationalAt * deg}")
        say("  tanh(psi),sech(psi)        vs reference:  max ${worstTanh} rad " +
            "(${worstTanh * earthRadiusMetres} m) at lat ${worstTanhAt * deg}")
        assertTrue(worstNaive < 5e-15, "naive Double form drifted: $worstNaive rad")
        assertTrue(worstRational < 5e-15, "half-angle Double form drifted: $worstRational rad")
        assertTrue(worstTanh < 5e-15, "hyperbolic Double form drifted: $worstTanh rad")
    }

    @Test
    fun symmetryPointsAreMeasuredSeparatelyAndProveNothing() {
        val gpu = Gpu(float32)
        for ((label, y) in listOf("equator" to 0.5, "north clip" to 0.0, "south clip" to 1.0)) {
            val yq = gpu.r(y)
            val naive = gpu.recoveredLatitude(gpu.naiveSinCos(yq))
            val half = gpu.recoveredLatitude(gpu.halfAngleSinCos(yq))
            val exact = exactLatitude(y)
            say("symmetry point $label: naive err ${abs(naive - exact) * earthRadiusMetres} m, " +
                "half-angle err ${abs(half - exact) * earthRadiusMetres} m")
        }
        say("the equator and both clip latitudes are excluded from every maximum reported below")
    }

    @Test
    fun withPerfectlyRoundedBuiltinsFloat32StillCostsMetres() {
        val gpu = Gpu(float32)
        val naiveTotal = DoubleArray(6)
        val halfTotal = DoubleArray(6)
        val naiveOwn = DoubleArray(6)
        val halfOwn = DoubleArray(6)
        val preludeOwn = DoubleArray(6)
        var naiveWorst = 0.0
        var naiveWorstLat = 0.0
        var halfWorst = 0.0
        var halfWorstLat = 0.0
        var inputWorst = 0.0
        var inputWorstLat = 0.0

        for (i in samples.indices) {
            val y = samples[i]
            val yq = gpu.r(y)
            val idealLatitude = exactLatitudes[i]
            val quantisedInputLatitude = exactLatitude(yq)
            val band = bandOf(idealLatitude)

            val naive = gpu.recoveredLatitude(gpu.naiveSinCos(yq))
            val half = gpu.recoveredLatitude(gpu.halfAngleSinCos(yq))
            // What the shared prelude alone costs: the latitude of the shader's rounded psi,
            // evaluated exactly, against the latitude of the shader's rounded y evaluated exactly.
            val prelude = abs(atan(sinh(gpu.psi(yq))) - quantisedInputLatitude) * earthRadiusMetres
            if (prelude > preludeOwn[band]) preludeOwn[band] = prelude

            val nTotal = abs(naive - idealLatitude) * earthRadiusMetres
            val hTotal = abs(half - idealLatitude) * earthRadiusMetres
            val nOwn = abs(naive - quantisedInputLatitude) * earthRadiusMetres
            val hOwn = abs(half - quantisedInputLatitude) * earthRadiusMetres
            val inputOnly = abs(quantisedInputLatitude - idealLatitude) * earthRadiusMetres

            if (nTotal > naiveTotal[band]) naiveTotal[band] = nTotal
            if (hTotal > halfTotal[band]) halfTotal[band] = hTotal
            if (nOwn > naiveOwn[band]) naiveOwn[band] = nOwn
            if (hOwn > halfOwn[band]) halfOwn[band] = hOwn
            if (nOwn > naiveWorst) { naiveWorst = nOwn; naiveWorstLat = idealLatitude }
            if (hOwn > halfWorst) { halfWorst = hOwn; halfWorstLat = idealLatitude }
            if (inputOnly > inputWorst) { inputWorst = inputOnly; inputWorstLat = idealLatitude }
        }

        val deg = 180.0 / PI
        say("float32, correctly rounded built-ins. Metres of latitude error, by band.")
        say("  band            prelude    naive-own  half-own   naive-total half-total")
        for (b in 0 until 6) {
            say("  ${bandNames[b].padEnd(14)}  ${fmt(preludeOwn[b])}  ${fmt(naiveOwn[b])}  ${fmt(halfOwn[b])}  " +
                "${fmt(naiveTotal[b])}  ${fmt(halfTotal[b])}")
        }
        say("  worst formulation-only error: naive ${naiveWorst} m at lat ${naiveWorstLat * deg}, " +
            "half-angle ${halfWorst} m at lat ${halfWorstLat * deg}")
        say("  worst input-quantisation-only error: ${inputWorst} m at lat ${inputWorstLat * deg}")
        assertTrue(halfWorst < naiveWorst, "half-angle was not better: $halfWorst vs $naiveWorst")
        assertTrue(naiveWorst < 10.0, "naive form exceeded 10 m with perfect built-ins: $naiveWorst")
    }

    @Test
    fun injectedTrigonometricErrorCostsMetresInTheNaiveFormOnly() {
        val ladder = listOf(0.0, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0, 1024.0)
        val probeLatitudes = listOf(0.7183, -17.4213, 37.4219, -59.9127, 78.3311)
        say("float32. Injected error in atan/sin/cos, worst-sign. Latitude error in metres.")
        say("  ULP    naive-max  (at lat)     half-max   " + probeLatitudes.joinToString("  ") { "n@" + it })
        var previousHalf: DoubleArray? = null
        var previousNaiveVector: DoubleArray? = null
        for (ulps in ladder) {
            var naiveMax = 0.0
            var naiveMaxLat = 0.0
            var halfMax = 0.0
            val halfVector = DoubleArray(samples.size)
            val naiveVector = DoubleArray(samples.size)
            for (sign in listOf(1.0, -1.0)) {
                val gpu = Gpu(float32, trigUlp = ulps * sign)
                for (i in samples.indices) {
                    val yq = gpu.r(samples[i])
                    val ideal = exactLatitudes[i]
                    val n = abs(gpu.recoveredLatitude(gpu.naiveSinCos(yq)) - ideal) * earthRadiusMetres
                    val h = abs(gpu.recoveredLatitude(gpu.halfAngleSinCos(yq)) - ideal) * earthRadiusMetres
                    if (n > naiveMax) { naiveMax = n; naiveMaxLat = ideal }
                    if (h > halfMax) halfMax = h
                    if (sign == 1.0) {
                        halfVector[i] = gpu.halfAngleSinCos(yq)[0]
                        naiveVector[i] = gpu.naiveSinCos(yq)[0]
                    }
                }
            }
            val probes = probeLatitudes.map { lat ->
                val gpu = Gpu(float32, trigUlp = ulps)
                val yq = gpu.r(latitudeDegreesToY(lat))
                abs(gpu.recoveredLatitude(gpu.naiveSinCos(yq)) - exactLatitude(yq)) * earthRadiusMetres
            }
            say("  ${ulps.toInt().toString().padStart(5)}  ${fmt(naiveMax)}  " +
                "(${(naiveMaxLat * 180.0 / PI)})  ${fmt(halfMax)}  " +
                probes.joinToString("  ") { fmt(it) })
            if (previousHalf != null) {
                for (i in halfVector.indices) {
                    assertEquals(
                        previousHalf!![i],
                        halfVector[i],
                        "the half-angle path changed when trig error was injected at index $i " +
                            "- it must contain no trigonometric call",
                    )
                }
            }
            if (previousNaiveVector != null) {
                var moved = 0
                for (i in naiveVector.indices) if (naiveVector[i] != previousNaiveVector!![i]) moved++
                assertTrue(
                    moved > samples.size / 2,
                    "injected trig error barely moved the naive path ($moved of ${samples.size}) - " +
                        "the harness would be measuring nothing",
                )
            }
            previousNaiveVector = naiveVector
            previousHalf = halfVector
            if (ulps == 1024.0) {
                say("  slope, worst case over the domain: ${naiveMax / 1024.0} m per ULP of trig error")
                for ((index, lat) in probeLatitudes.withIndex()) {
                    say("  slope at lat $lat: ${probes[index] / 1024.0} m per ULP")
                }
            }
        }
    }

    @Test
    fun theTrigonometricErrorThatReproducesTheFieldReport() {
        // MapLibre #7419 measured 200-300 m of latitude error on Mali-G610/G710. What atan/sin/cos
        // inaccuracy, in ULPs of float32, does the naive formulation need to produce that?
        var low = 0.0
        var high = 4096.0
        repeat(34) {
            val mid = (low + high) / 2.0
            val gpu = Gpu(float32, trigUlp = mid)
            var worst = 0.0
            for (i in samples.indices step 7) {
                val yq = gpu.r(samples[i])
                val e = abs(gpu.recoveredLatitude(gpu.naiveSinCos(yq)) - exactLatitudes[i]) * earthRadiusMetres
                if (e > worst) worst = e
            }
            if (worst < 250.0) low = mid else high = mid
        }
        say("naive form reaches 250 m of latitude error at ${(low + high) / 2.0} ULP of atan/sin/cos error")
        val gpuAtOne = Gpu(float32, trigUlp = 1.0)
        var oneUlp = 0.0
        for (i in samples.indices) {
            val yq = gpuAtOne.r(samples[i])
            val e = abs(gpuAtOne.recoveredLatitude(gpuAtOne.naiveSinCos(yq)) - exactLatitudes[i]) * earthRadiusMetres
            if (e > oneUlp) oneUlp = e
        }
        say("naive form's worst absolute error at 1 ULP of trig error: ${oneUlp} m " +
            "(the marginal slope is reported by the ladder test)")
        assertTrue(low > 8.0, "the model reproduces 250 m at implausibly little trig error: $low ULP")
    }

    @Test
    fun exponentialErrorMovesBothFormsIdenticallyAndByThePredictedAmount() {
        // The one built-in both forms share is exp, and it is the one the specification does bound:
        // 3 + 2|x| ULP, so at most 3 + 2*PI = 9.28 ULP here.
        for (ulps in listOf(1.0, 3.0 + 2.0 * PI)) {
            val gpu = Gpu(float32, expUlp = ulps)
            var worstNaive = 0.0
            var worstHalf = 0.0
            for (i in samples.indices) {
                val yq = gpu.r(samples[i])
                val ideal = exactLatitudes[i]
                val n = abs(gpu.recoveredLatitude(gpu.naiveSinCos(yq)) - ideal) * earthRadiusMetres
                val h = abs(gpu.recoveredLatitude(gpu.halfAngleSinCos(yq)) - ideal) * earthRadiusMetres
                if (n > worstNaive) worstNaive = n
                if (h > worstHalf) worstHalf = h
            }
            say("exp error ${ulps} ULP: naive max ${fmt(worstNaive)} m, half-angle max ${fmt(worstHalf)} m " +
                "(both within the float32 rounding floor measured above)")
        }

        // A relative error eps in t must move the latitude by exactly eps*cos(lat). At 1 ULP that
        // prediction is buried under the float32 rounding floor, so drive it well clear of it and
        // measure the *difference* the injection makes rather than the absolute error.
        val large = 4096.0
        val clean = Gpu(float32)
        val perturbed = Gpu(float32, expUlp = large)
        var worstMiss = 0.0
        var worstAt = 0.0
        var worstShift = 0.0
        var checked = 0
        for (i in samples.indices step 3) {
            val yq = clean.r(samples[i])
            val ideal = exactLatitudes[i]
            val naiveShift = abs(
                perturbed.recoveredLatitude(perturbed.naiveSinCos(yq)) -
                    clean.recoveredLatitude(clean.naiveSinCos(yq)),
            )
            val halfShift = abs(
                perturbed.recoveredLatitude(perturbed.halfAngleSinCos(yq)) -
                    clean.recoveredLatitude(clean.halfAngleSinCos(yq)),
            )
            val t = exp(clean.psi(yq))
            val predicted = large * float32.ulpAt(t) / t * cos(ideal)
            if (predicted <= 0.0) continue
            checked++
            for (shift in listOf(naiveShift, halfShift)) {
                val miss = abs(shift - predicted) / predicted
                if (miss > worstMiss) { worstMiss = miss; worstAt = ideal; worstShift = shift }
            }
            if (halfShift * earthRadiusMetres > worstShift && worstMiss == 0.0) worstShift = halfShift
        }
        say("exp sensitivity law d(lat) = eps*cos(lat), driven at ${large.toInt()} ULP over $checked samples: " +
            "worst deviation ${worstMiss * 100.0}% at lat ${worstAt * 180.0 / PI} " +
            "(shift there ${worstShift * earthRadiusMetres} m)")
        assertTrue(worstMiss < 0.05, "measured exp sensitivity did not match eps*cos(lat): off by ${worstMiss * 100}%")
    }

    @Test
    fun mediumPrecisionDestroysBothFormulations() {
        val gpu = Gpu(float16)
        var worstNaive = 0.0
        var worstHalf = 0.0
        var worstInput = 0.0
        for (i in samples.indices) {
            val y = samples[i]
            val yq = gpu.r(y)
            if (!yq.isFinite()) continue
            val ideal = exactLatitudes[i]
            val n = abs(gpu.recoveredLatitude(gpu.naiveSinCos(yq)) - ideal) * earthRadiusMetres
            val h = abs(gpu.recoveredLatitude(gpu.halfAngleSinCos(yq)) - ideal) * earthRadiusMetres
            val q = abs(exactLatitude(yq) - ideal) * earthRadiusMetres
            if (n > worstNaive) worstNaive = n
            if (h > worstHalf) worstHalf = h
            if (q > worstInput) worstInput = q
        }
        say("binary16 (mediump): naive max ${fmt(worstNaive)} m, half-angle max ${fmt(worstHalf)} m, " +
            "input quantisation alone ${fmt(worstInput)} m")
        assertTrue(worstHalf > 1000.0, "mediump half-angle was unexpectedly good: $worstHalf m")
        assertTrue(worstInput > 1000.0, "mediump input quantisation was unexpectedly good: $worstInput m")
    }

    @Test
    fun consecutiveFloat32MercatorYValuesCollapseOntoOneLatitudeInTheNaiveForm() {
        // MapLibre #7419's symptom is not an offset: "some distinct latitude values will overlap".
        // Sweep consecutive representable float32 y values and count how many fail to move the
        // computed latitude at all, with every built-in correctly rounded.
        val gpu = Gpu(float32)
        say("consecutive float32 mercator-y steps, correctly rounded built-ins:")
        say("  latitude   ground step  psi ties  naive ties  naive inversions  half ties  half inversions")
        for (lat in listOf(0.7183, -17.4213, 37.4219, -59.9127, 78.3311, -84.6122)) {
            val y0 = gpu.r(latitudeDegreesToY(lat))
            val step = float32.ulpAt(y0)
            val count = 20_000
            var naiveTies = 0
            var naiveInversions = 0
            var halfTies = 0
            var halfInversions = 0
            var psiTies = 0
            var previousPsi = Double.NaN
            var previousNaive = Double.NaN
            var previousHalf = Double.NaN
            var groundStep = 0.0
            var previousExact = Double.NaN
            for (i in 0 until count) {
                val y = y0 + i * step
                val p = gpu.psi(y)
                val n = gpu.recoveredLatitude(gpu.naiveSinCos(y))
                val h = gpu.recoveredLatitude(gpu.halfAngleSinCos(y))
                val e = exactLatitude(y)
                if (i > 0 && p == previousPsi) psiTies++
                previousPsi = p
                if (i > 0) {
                    if (n == previousNaive) naiveTies++ else if (n > previousNaive) naiveInversions++
                    if (h == previousHalf) halfTies++ else if (h > previousHalf) halfInversions++
                    groundStep = abs(e - previousExact) * earthRadiusMetres
                }
                previousNaive = n
                previousHalf = h
                previousExact = e
            }
            say("  ${lat.toString().padEnd(10)} ${fmt(groundStep)}  ${psiTies.toString().padStart(8)}  " +
                "${naiveTies.toString().padStart(10)}  ${naiveInversions.toString().padStart(16)}  " +
                "${halfTies.toString().padStart(9)}  ${halfInversions.toString().padStart(15)}")
        }
        say("  'psi ties' is the shared `PI - y*PI*2.0` prelude losing resolution before either")
        say("  formulation is reached; it is a floor neither identity can beat.")
    }

    @Test
    fun nonSmoothBuiltinErrorBreaksMonotonicityAndASingleCorrectionCannotFixIt() {
        // A constant bias is correctable (MapLibre shipped and then deleted exactly such a
        // correction). An error that varies point to point is not. This models the second case with
        // a deterministic hash; it is a stand-in for a table-boundary discontinuity, not a
        // measurement of any driver.
        val lat = 37.4219
        val count = 20_000
        say("non-smooth built-in error, deterministic +/- k ULP, at lat $lat:")
        say("  source     k ULP   inversions  spread(m)")
        for ((label, build) in listOf<Pair<String, (Double) -> Gpu>>(
            "atan/sin/cos" to { k -> Gpu(float32, trigUlp = k, noisy = true) },
            "exp" to { k -> Gpu(float32, expUlp = k, noisy = true) },
        )) {
            for (k in listOf(1.0, 4.0, 9.28)) {
                val gpu = build(k)
                val y0 = gpu.r(latitudeDegreesToY(lat))
                val step = float32.ulpAt(y0)
                var naiveInversions = 0
                var halfInversions = 0
                var naiveTies = 0
                var halfTies = 0
                var naiveSpread = 0.0
                var halfSpread = 0.0
                var previousNaive = Double.NaN
                var previousHalf = Double.NaN
                for (i in 0 until count) {
                    val y = y0 + i * step
                    val e = exactLatitude(y)
                    val n = gpu.recoveredLatitude(gpu.naiveSinCos(y))
                    val h = gpu.recoveredLatitude(gpu.halfAngleSinCos(y))
                    naiveSpread = max(naiveSpread, abs(n - e) * earthRadiusMetres)
                    halfSpread = max(halfSpread, abs(h - e) * earthRadiusMetres)
                    if (i > 0) {
                        if (n > previousNaive) naiveInversions++ else if (n == previousNaive) naiveTies++
                        if (h > previousHalf) halfInversions++ else if (h == previousHalf) halfTies++
                    }
                    previousNaive = n
                    previousHalf = h
                }
                say("  ${label.padEnd(12)} ${k}   naive inv $naiveInversions tie $naiveTies of ${count - 1} " +
                    "spread ${fmt(naiveSpread)}   half-angle inv $halfInversions tie $halfTies " +
                    "spread ${fmt(halfSpread)}")
            }
        }
    }

    @Test
    fun theInputQuantisationFloorIsSharedAndParameterisationDependent() {
        // d(lat) = 2*PI*cos(lat)*d(y). Neither formulation can beat it; it is a property of how the
        // shader is handed its coordinate, which is a separate decision from which identity it uses.
        say("input quantisation floor, d(lat) = 2*PI*cos(lat)*d(y):")
        for (lat in listOf(0.7183, -17.4213, 37.4219, -59.9127, 78.3311)) {
            val y = latitudeDegreesToY(lat)
            val yq = float32.quantise(y)
            val halfUlp = float32.ulpAt(yq) / 2.0
            val predicted = 2.0 * PI * cos(lat * PI / 180.0) * halfUlp * earthRadiusMetres
            val measured = abs(exactLatitude(yq) - exactLatitude(y)) * earthRadiusMetres
            say("  lat $lat: global float32 y -> up to ${fmt(predicted)} m (measured at this point ${fmt(measured)} m); " +
                "tile-local at z=11 -> ${fmt(predicted / 2048.0)} m; z=16 -> ${fmt(predicted / 65536.0)} m")
            assertTrue(measured <= predicted * 1.001, "measured floor exceeded the analytic bound")
        }
    }

    @Test
    fun theSphereRadiusStaysUnitInBothForms() {
        val gpu = Gpu(float32)
        var worstNaive = 0.0
        var worstHalf = 0.0
        for (i in samples.indices) {
            val yq = gpu.r(samples[i])
            val n = gpu.naiveSinCos(yq)
            val h = gpu.halfAngleSinCos(yq)
            worstNaive = max(worstNaive, abs(sqrt(n[0] * n[0] + n[1] * n[1]) - 1.0) * earthRadiusMetres)
            worstHalf = max(worstHalf, abs(sqrt(h[0] * h[0] + h[1] * h[1]) - 1.0) * earthRadiusMetres)
        }
        say("radial (altitude) error from the sin/cos pair: naive ${fmt(worstNaive)} m, half-angle ${fmt(worstHalf)} m")
        assertTrue(worstNaive < 5.0 && worstHalf < 5.0, "radius drifted: $worstNaive / $worstHalf")
    }

    private fun fmt(v: Double): String {
        val s = when {
            v == 0.0 -> "0"
            abs(v) >= 1000.0 -> ((v * 10.0).toLong() / 10.0).toString()
            abs(v) >= 1.0 -> ((v * 1000.0).toLong() / 1000.0).toString()
            else -> {
                var scale = 1.0
                var digits = 0
                while (abs(v) * scale < 1000.0 && digits < 12) { scale *= 10.0; digits++ }
                ((v * scale * 100.0).toLong() / 100.0).toString() + "e-" + digits
            }
        }
        return s.padEnd(11)
    }
}
