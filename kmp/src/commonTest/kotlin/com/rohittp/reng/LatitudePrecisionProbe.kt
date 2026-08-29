package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_ARRAY_BUFFER
import com.rohittp.reng.internal.gl.GL_BLEND
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_CULL_FACE
import com.rohittp.reng.internal.gl.GL_DEPTH_TEST
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FLOAT
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_STATIC_DRAW
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlProgramResult
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.compileShaderProgram
import com.rohittp.reng.internal.gl.littleEndianBytes
import com.rohittp.reng.internal.projection.WGS84_SEMI_MAJOR_AXIS_METRES
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cycle G task 11's probe: what this driver's transcendental functions actually do to RenG's
 * latitude path, measured in ULP, in metres and in inversions — on the real driver, in the real
 * shader stage, with no device the project does not own.
 *
 * ## Why a runtime probe is the only instrument that can answer this
 *
 * GLSL ES 3.00 section 4.5.1 grants `atan`, `sin` and `cos` **undefined precision** while specifying
 * `exp` at `3 + 2|x|` ULP, and a Mercator-to-sphere projection is nothing but trigonometry. Three
 * projects were bitten by that, most sharply MapLibre on Mali-G610/G710 with **200-300 metres** of
 * latitude error. RenG's answer, adopted by task 1 and documented on `unitSphereDirection`, is the
 * trig-free tangent half-angle path: `sin phi = (t^2 - 1) / (t^2 + 1)`, `cos phi = 2t / (t^2 + 1)`
 * with `t = exp psi`, which removes `atan`, `sin` and `cos` from the latitude computation entirely.
 *
 * **That converts an unbounded error into a specified one; it does not make the path exact.**
 * `docs/research/2026-08-28-g-latitude-precision-spike.md` section 5b measured the half-angle path at
 * **8.7 metres** when `exp` sits at its own specified `3 + 2|x|` bound, and section 6 measured it
 * inverting *more* often than the naive form when that `exp` error is non-smooth. The whole
 * mitigation therefore rests on an assumption about `exp` that no specification grants and no vendor
 * publishes — and the spike says so in as many words: *"Nothing here validates the `exp` assumption
 * ... This is the strongest argument for G5's third mitigation, the runtime probe: the probe can see
 * this, and this spike cannot."* This file is that probe.
 *
 * ## What it measures
 *
 * A sweep of **[SAMPLES_PER_BASE] consecutive representable float32 isometric latitudes** at each of
 * [BASE_LATITUDES_DEGREES] — [TOTAL_SAMPLES] samples in all — is handed to the driver one per vertex,
 * and the driver's own bits come back one per pixel. From those bits:
 *
 * - the ULP error of `exp`, `atan`, `sin` and `cos` against a `Double` reference, and **whether the
 *   driver's `exp` is monotone** across consecutive inputs;
 * - the latitude error in metres of both the half-angle and the naive formulation, recovered as
 *   `atan2(sin phi, cos phi)` in `Double` outside the driver and compared against `atan(sinh psi)`;
 * - the **number of monotonicity inversions** of each formulation across the sweep.
 *
 * **Monotonicity is the point, not magnitude.** MapLibre #7419's complaint is not an offset — it is
 * *"some distinct latitude values will overlap"* — and the spike measured a single ULP of *non-smooth*
 * `atan` error inverting **1,134 of 19,999** consecutive float32 values under the naive form and
 * **zero** under the half-angle one. A probe that reported only a worst-case error in metres would
 * miss that entirely, because 1 ULP of `atan` is worth only 1.66 metres and looks like nothing.
 *
 * ## What it does not measure, stated so nobody reads more into a green run
 *
 * - **It cannot fail a driver into being fixed.** Per the design (section 7), *the probe reports and
 *   skips; it does not gate*: a hard budget on trigonometric accuracy would be a number chosen
 *   without ever having measured a Mali. Every number below is printed. The only budget asserted is
 *   the GLSL ES specification's own `exp` bound, and even that is asserted only where the driver has
 *   already been measured to be inside it.
 * - **It measures the vertex stage**, because that is where `GlobeGroundPipeline`'s latitude path
 *   runs. On hardware whose vertex and fragment units evaluate transcendentals differently, nothing
 *   here says anything about the fragment stage.
 * - **It measures latitude only.** The longitude half still calls `sin` and `cos` on the real
 *   pipeline; this probe reports what those two built-ins cost in ULP, but not what a longitude error
 *   costs on screen.
 * - **It is not built on the code it guards.** The program compiled here is this file's own, the
 *   half-angle expression is written out again rather than imported from `GlobeGroundPipeline`, and
 *   the `Double` reference is `atan(sinh psi)` rather than `unitSphereDirection` — so a regression in
 *   either would make this fail rather than move the goalposts with it. Task 18 of the last cycle is
 *   why: a guard built on the pipeline it protects fires on that pipeline's own regressions and then
 *   masks them.
 * - **It stores no baseline and compares no image.**
 */
internal fun runLatitudePrecisionProbeSuite(binding: GlBinding, dialect: ShaderDialect) {
    val target = createLatitudeProbeTarget(binding)
    val samples = latitudeProbeSamples()
    val probe = LatitudeProbeProgram.create(binding, dialect, target, samples)
    try {
        // A probe that cannot run fails rather than skips. If the driver did not hand back the exact
        // inputs it was given, nothing below measures a transcendental function -- it measures a
        // broken point rasterisation or a lossy varying -- and silently declaring the driver
        // untrustworthy would take every case here off CI without anyone noticing.
        assertTheDriverReturnsTheExactInputsItWasGiven(probe, samples)

        val measured = measureLatitudePrecision(probe, samples)
        println(
            "RenG latitude precision probe: driver=${binding.getString(GL_RENDERER)} " +
                "dialect=$dialect " + measured.describe(),
        )

        // Always run: these say what this probe can see, on this driver, and they are what stops
        // every "0 inversions" below from being the report of a counter that counts nothing.
        assertInjectedTrigonometricErrorMovesTheNaivePathAndNotTheHalfAngleOne(probe, samples, measured)
        assertTheHalfAngleGuaranteeIsOnlyAsGoodAsExp(probe, samples, measured)

        if (measured.expIsSmooth) {
            assertTheHalfAnglePathIsMonotonicWhereTheFormatResolvesIt(measured)
        } else {
            println(
                "RenG latitude precision probe SKIPPED [the half-angle path is monotonic] " +
                    "this driver's exp is not monotone across inputs this format separates " +
                    "(${measured.expInversions} of ${measured.resolvedComparisons} inverted), so " +
                    "an inversion in the latitude path would measure the driver rather than RenG's " +
                    "formulation. Every other case still ran.",
            )
        }

        if (measured.expIsWithinSpecification) {
            assertTheHalfAnglePathLandsWhereDoubleSays(measured)
        } else {
            println(
                "RenG latitude precision probe SKIPPED [the half-angle path lands where Double " +
                    "says] this driver's exp is ${measured.expWorstUlps} ULP out, past its own " +
                    "specified bound of $SPECIFIED_EXP_ULP_BOUND, so the metres below are the " +
                    "driver's exp rather than RenG's arithmetic. Every other case still ran.",
            )
        }
    } finally {
        probe.destroy()
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.deleteFramebuffers(1, intArrayOf(target.framebuffer))
        binding.deleteTextures(1, intArrayOf(target.texture))
    }
}

/**
 * The probe's own integrity check, and the one thing here that fails rather than skips.
 *
 * Every measurement below is a comparison against a `Double` reference computed from the input the
 * driver was *believed* to have. This asserts it actually had it: the [LatitudeProbeChannel.INPUT]
 * channel echoes the vertex attribute straight back through the pack-and-read path, so a misplaced
 * point, a lossy varying, an sRGB-encoded target or a driver that dithers an exactly representable
 * byte all show up here as a bit difference rather than as a mysterious two-metre error later.
 */
private fun assertTheDriverReturnsTheExactInputsItWasGiven(
    probe: LatitudeProbeProgram,
    samples: LatitudeProbeSamples,
) {
    val echoed = probe.read(LatitudeProbeChannel.INPUT, trigUlps = 0f, expUlps = 0f)
    var firstDisagreement = -1
    var disagreements = 0
    for (index in 0 until TOTAL_SAMPLES) {
        if (echoed[index].toRawBits() != samples.isometricLatitude[index].toRawBits()) {
            if (firstDisagreement < 0) firstDisagreement = index
            disagreements += 1
        }
    }
    assertEquals(
        0,
        disagreements,
        "the probe must be handed back the exact float32 it uploaded, or it measures the transport " +
            "rather than the driver's arithmetic: $disagreements of $TOTAL_SAMPLES disagree, first " +
            "at index $firstDisagreement",
    )
}

/**
 * The spike's section 6 finding, reproduced on this driver rather than on a model of one.
 *
 * Non-smooth error is injected into `atan`, `sin` and `cos` — and into nothing else — by perturbing
 * each result by a deterministic hash of its own argument, the spike's stand-in for a table-boundary
 * discontinuity of the kind CesiumJS found on an Intel HD 4600. A constant bias would be correctable
 * and MapLibre shipped and then deleted exactly such a correction, so the error that matters is the
 * non-smooth kind. Three things must follow, and each discriminates something the others do not:
 *
 * - **The half-angle formulation does not move one bit**, at either injected magnitude. True by
 *   construction — that path contains no trigonometric call to perturb — and the spike says as much.
 *   It is asserted so that an edit reintroducing `atan`, `sin` or `cos` into the latitude path shows
 *   up here as a red test rather than as a silent loss of the guarantee.
 * - **One ULP already makes the naive path invert more often.** This is MapLibre #7419's reported
 *   symptom, on this driver, without a badly broken driver: one ULP is nothing, and it is enough.
 * - **[CONTROL_TRIG_ULPS] ULP inverts the naive path at separations the format resolves, and puts it
 *   metres out.** This is the load-bearing half: it proves that on *this* driver the resolved
 *   inversion counter and the metre measurement both respond, so that the zeroes they report for the
 *   half-angle path are measurements rather than an inert counter and a broken comparison.
 */
private fun assertInjectedTrigonometricErrorMovesTheNaivePathAndNotTheHalfAngleOne(
    probe: LatitudeProbeProgram,
    samples: LatitudeProbeSamples,
    uninjected: LatitudePrecision,
) {
    val gentle = probe.measurePaths(samples, trigUlps = 1f, expUlps = 0f)
    val severe = probe.measurePaths(samples, trigUlps = CONTROL_TRIG_ULPS, expUlps = 0f)
    println(
        "RenG latitude precision probe, non-smooth trigonometric error injected: " +
            "at 1 ULP naive [${gentle.naive.describe()}]; " +
            "at ${CONTROL_TRIG_ULPS.toInt()} ULP naive [${severe.naive.describe()}]; " +
            "half-angle unmoved at both",
    )

    listOf("one ULP" to gentle, "${CONTROL_TRIG_ULPS.toInt()} ULP" to severe).forEach { (name, run) ->
        assertTrue(
            run.halfAngle.isBitIdenticalTo(uninjected.halfAngle),
            "the half-angle latitude path calls no trigonometric function, so injecting $name of " +
                "atan/sin/cos error must not move one bit of it: " +
                "${run.halfAngle.bitDifferencesAgainst(uninjected.halfAngle)} of $TOTAL_SAMPLES " +
                "sine or cosine values moved",
        )
    }
    assertTrue(
        gentle.naive.rawInversions > uninjected.naive.rawInversions,
        "one ULP of non-smooth atan/sin/cos error must make the naive latitude path invert more " +
            "often on this driver, or this probe cannot see the symptom it exists for: " +
            "${uninjected.naive.rawInversions} inversions became ${gentle.naive.rawInversions} of " +
            "${samples.rawComparisons}",
    )
    assertTrue(
        severe.naive.resolvedInversions > 0,
        "at ${CONTROL_TRIG_ULPS.toInt()} ULP the naive path must swap latitudes this format " +
            "separates, or the resolved inversion counter is measuring nothing on this driver: " +
            "${severe.naive.resolvedInversions} of ${samples.resolvedComparisons} resolved " +
            "comparisons inverted",
    )
    assertTrue(
        severe.naive.worstMetres >= uninjected.naive.worstMetres + CONTROL_NAIVE_GROWTH_METRES,
        "at ${CONTROL_TRIG_ULPS.toInt()} ULP the injection itself must cost the naive path tens of " +
            "metres, which is a claim about the injection rather than about the driver's own error: " +
            "${uninjected.naive.worstMetres} metres became ${severe.naive.worstMetres}, a growth of " +
            "${severe.naive.worstMetres - uninjected.naive.worstMetres} against a floor of " +
            "$CONTROL_NAIVE_GROWTH_METRES",
    )
}

/**
 * The honest limit of the mitigation, measured rather than quoted.
 *
 * The half-angle path's whole guarantee is that its one transcendental is the one the specification
 * bounds. Injecting exactly that specified bound — `3 + 2|psi|`, at most [SPECIFIED_EXP_ULP_BOUND]
 * here since `|psi| <= PI` — must move the path by metres, on this driver, or the sentence "the
 * guarantee is only as good as `exp`" in `unitSphereDirection`'s KDoc is decoration.
 *
 * Asserted as a *growth* over the uninjected measurement rather than against an absolute floor,
 * because the uninjected figure is the driver's own arithmetic and differs between rasterisers. The
 * spike predicts `delta phi = epsilon * cos phi`, which is 7.06 metres at this bound; the growth
 * asserted is well under a third of that.
 */
private fun assertTheHalfAngleGuaranteeIsOnlyAsGoodAsExp(
    probe: LatitudeProbeProgram,
    samples: LatitudeProbeSamples,
    uninjected: LatitudePrecision,
) {
    val injected = probe.measurePaths(samples, trigUlps = 0f, expUlps = SPECIFIED_EXP_ULP_BOUND)
    println(
        "RenG latitude precision probe, exp driven to its specified $SPECIFIED_EXP_ULP_BOUND ULP " +
            "bound: half-angle [${injected.halfAngle.describe()}] naive [${injected.naive.describe()}]",
    )
    assertTrue(
        injected.halfAngle.worstMetres >= uninjected.halfAngle.worstMetres + EXP_GROWTH_FLOOR_METRES,
        "driving exp to its own specified bound must move the half-angle path by metres, because " +
            "the whole guarantee rests on exp: ${uninjected.halfAngle.worstMetres} metres became " +
            "${injected.halfAngle.worstMetres}, a growth of " +
            "${injected.halfAngle.worstMetres - uninjected.halfAngle.worstMetres} against a floor " +
            "of $EXP_GROWTH_FLOOR_METRES",
    )
}

/**
 * The case task 11 exists for: distinct latitudes the output format can tell apart must not swap.
 *
 * Runs only where the driver's own `exp` has been measured monotone across the same inputs, because
 * the implication runs that way and no further: a monotone `exp` makes `t`, `t^2`, `t^2 - 1` and
 * `t^2 + 1` monotone, and `(t^2 - 1) / (t^2 + 1)` is `1 - 2 / (t^2 + 1)`, which increases. A resolved
 * inversion on a driver whose `exp` is monotone is therefore that driver's *divide* — a real finding
 * — while one on a driver whose `exp` is not is the `exp`, which is why that case stands down out
 * loud instead of failing.
 *
 * **Resolved rather than raw**, and the difference is the whole care of this case.
 * [LatitudePath.rawInversions] is nonzero on a perfectly good driver: at one ULP of `psi` near the
 * poles a float32 `sin phi` moves by less than half its own ULP, and a divide implemented as a
 * reciprocal multiply — legal at GLSL ES 3.00's 2.5 ULP — reorders pairs the truth separates by 1.5
 * ULP. Measured on `Apple M3 Max`: 799 raw inversions of 16,376 and **zero** once the comparison is
 * restricted to pairs the format separates by [RESOLVABLE_SEPARATION_ULPS]. Asserting the raw count
 * were zero would have been a false claim about float32; tolerating an arbitrary count would have
 * been a budget nobody derived.
 */
private fun assertTheHalfAnglePathIsMonotonicWhereTheFormatResolvesIt(measured: LatitudePrecision) {
    assertTrue(
        measured.resolvedComparisons >= MINIMUM_RESOLVED_COMPARISONS,
        "the sweep must resolve enough distinct latitudes for a zero inversion count to mean " +
            "something: only ${measured.resolvedComparisons} of $TOTAL_SAMPLES samples cleared " +
            "$RESOLVABLE_SEPARATION_ULPS ULP of separation",
    )
    assertEquals(
        0,
        measured.halfAngle.resolvedInversions,
        "latitudes this format separates by $RESOLVABLE_SEPARATION_ULPS ULP must not come back " +
            "swapped: ${measured.halfAngle.resolvedInversions} of " +
            "${measured.resolvedComparisons} resolved comparisons inverted, the first at " +
            "${measured.halfAngle.firstResolvedInversionDegrees} degrees",
    )
}

/**
 * Magnitude, against the specification's own arithmetic rather than against a tuned number.
 *
 * [MAXIMUM_SPECIFIED_LATITUDE_ERROR_METRES] is derived in its own KDoc from `exp`'s `3 + 2|x|` bound
 * and the float32 floor of the rational form, so it is the same figure on every driver and on none
 * of them is it a guess about Mali. It runs only where `exp` has been measured inside that bound; on
 * a driver outside it these metres would be the driver's `exp` and the case stands down out loud.
 */
private fun assertTheHalfAnglePathLandsWhereDoubleSays(measured: LatitudePrecision) {
    assertTrue(
        measured.halfAngle.worstMetres <= MAXIMUM_SPECIFIED_LATITUDE_ERROR_METRES,
        "with exp inside its specified bound the half-angle path must land inside the error that " +
            "bound implies: ${measured.halfAngle.worstMetres} metres at " +
            "${measured.halfAngle.worstLatitudeDegrees} degrees, against " +
            "$MAXIMUM_SPECIFIED_LATITUDE_ERROR_METRES",
    )
}

// ---- the measurement ------------------------------------------------------------------------------

/**
 * Everything the probe learned about one driver, as numbers rather than as a verdict.
 *
 * The two `is...` properties are the only places a threshold decides anything, they are both about
 * `exp`, and each governs exactly one case above. Every other figure is printed.
 */
internal class LatitudePrecision(
    val expWorstUlps: Long,
    val expInversions: Int,
    val arithmeticWorstUlps: Long,
    val atanWorstUlps: Long,
    val sineWorstUlps: Long,
    val cosineWorstUlps: Long,
    val halfAngle: LatitudePath,
    val naive: LatitudePath,
    val resolvedComparisons: Int,
) {
    /**
     * Monotone across the sweep — the assumption the whole mitigation rests on, and the one the
     * spike could not check because it had no driver.
     */
    val expIsSmooth: Boolean get() = expInversions == 0

    /** Inside `3 + 2|x|` ULP, which for `|psi| <= PI` is [SPECIFIED_EXP_ULP_BOUND]. */
    val expIsWithinSpecification: Boolean get() = expWorstUlps <= SPECIFIED_EXP_ULP_BOUND.toLong()

    fun describe(): String =
        "exp $expWorstUlps ULP with $expInversions resolved inversions, atan $atanWorstUlps ULP, " +
            "sin $sineWorstUlps ULP, cos $cosineWorstUlps ULP, the half-angle arithmetic after exp " +
            "$arithmeticWorstUlps ULP, over $TOTAL_SAMPLES samples and $resolvedComparisons " +
            "resolved comparisons; half-angle [${halfAngle.describe()}]; naive [${naive.describe()}]"
}

/**
 * One formulation's behaviour over the sweep, with monotonicity counted twice on purpose.
 *
 * [rawInversions] is every consecutive pair of representable inputs, which is the finest question
 * that can be asked and is **not** a defect count: a float32 `sin phi` cannot separate two isometric
 * latitudes one ULP apart near the poles, and a driver whose divide is a reciprocal-multiply rather
 * than a correctly rounded division reorders them. Both are measurements of the format and the
 * driver rather than of RenG.
 *
 * [resolvedInversions] counts only pairs the output format separates by
 * [RESOLVABLE_SEPARATION_ULPS] ULP or more, which is the question that has a right answer: GLSL ES
 * 3.00 allows a division 2.5 ULP of error, so two results can legally swap only if the truth
 * separates them by under five, and eight leaves margin over that. **That** is what a Mali-shaped
 * `atan` failure violates, by two orders of magnitude.
 */
internal class LatitudePath(
    private val name: String,
    val worstMetres: Double,
    val worstLatitudeDegrees: Double,
    val rawInversions: Int,
    val rawTies: Int,
    val resolvedInversions: Int,
    val firstResolvedInversionDegrees: Double,
    private val sine: FloatArray,
    private val cosine: FloatArray,
) {
    fun isBitIdenticalTo(other: LatitudePath): Boolean = bitDifferencesAgainst(other) == 0

    fun bitDifferencesAgainst(other: LatitudePath): Int = (0 until TOTAL_SAMPLES).count {
        sine[it].toRawBits() != other.sine[it].toRawBits() ||
            cosine[it].toRawBits() != other.cosine[it].toRawBits()
    }

    fun describe(): String =
        "worst $worstMetres m at $worstLatitudeDegrees deg, $resolvedInversions resolved " +
            "inversions, $rawInversions raw inversions and $rawTies raw ties"

    override fun toString(): String = "$name ${describe()}"
}

/** Both formulations, read back under one pair of injection settings. */
private class LatitudePaths(val halfAngle: LatitudePath, val naive: LatitudePath)

private fun measureLatitudePrecision(
    probe: LatitudeProbeProgram,
    samples: LatitudeProbeSamples,
): LatitudePrecision {
    val exponential = probe.read(LatitudeProbeChannel.EXPONENTIAL, 0f, 0f)
    val halfAngleSine = probe.read(LatitudeProbeChannel.HALF_ANGLE_SINE, 0f, 0f)
    val arcTangent = probe.read(LatitudeProbeChannel.NAIVE_ARC_TANGENT, 0f, 0f)
    val naiveLatitude = probe.read(LatitudeProbeChannel.NAIVE_LATITUDE, 0f, 0f)
    val naiveSine = probe.read(LatitudeProbeChannel.NAIVE_SINE, 0f, 0f)
    val naiveCosine = probe.read(LatitudeProbeChannel.NAIVE_COSINE, 0f, 0f)
    val paths = probe.measurePaths(samples, 0f, 0f)

    var expWorst = 0L
    var atanWorst = 0L
    var sineWorst = 0L
    var cosineWorst = 0L
    var arithmeticWorst = 0L
    for (index in 0 until TOTAL_SAMPLES) {
        val psi = samples.isometricLatitude[index].toDouble()
        expWorst = maxOf(expWorst, ulpDistance(exponential[index], exp(psi).toFloat()))
        val t = exponential[index]
        atanWorst = maxOf(atanWorst, ulpDistance(arcTangent[index], atan(t.toDouble()).toFloat()))
        val phi = naiveLatitude[index].toDouble()
        sineWorst = maxOf(sineWorst, ulpDistance(naiveSine[index], sin(phi).toFloat()))
        cosineWorst = maxOf(cosineWorst, ulpDistance(naiveCosine[index], cos(phi).toFloat()))
        arithmeticWorst = maxOf(arithmeticWorst, ulpDistance(halfAngleSine[index], halfAngleSineOf(t)))
    }

    var expInversions = 0
    samples.forEachResolvedPair { first, second ->
        if (exponential[second] < exponential[first]) expInversions += 1
    }

    return LatitudePrecision(
        expWorstUlps = expWorst,
        expInversions = expInversions,
        arithmeticWorstUlps = arithmeticWorst,
        atanWorstUlps = atanWorst,
        sineWorstUlps = sineWorst,
        cosineWorstUlps = cosineWorst,
        halfAngle = paths.halfAngle,
        naive = paths.naive,
        resolvedComparisons = samples.resolvedComparisons,
    )
}

/**
 * The half-angle sine the driver *should* have produced from the `t` it actually produced, in
 * correctly rounded float32.
 *
 * Comparing the driver's own answer against this isolates the multiply, subtract, add and divide
 * from `exp`, which is the only way to tell a bad transcendental from a divide implemented as a
 * reciprocal multiply. Kotlin's `Float` arithmetic is IEEE-754 binary32 with round-to-nearest on
 * both the JVM and Kotlin/Native, so this is the correctly rounded chain by construction.
 */
private fun halfAngleSineOf(t: Float): Float {
    val tangentSquared = t * t
    return (tangentSquared - 1f) / (tangentSquared + 1f)
}

/**
 * The distance between two float32 values in ULP of the format, over IEEE-754's total order.
 *
 * Bit patterns alone would be wrong across zero and backwards for negatives, which sign-magnitude
 * orders the other way; `sin phi` and `cos phi` both take both signs over this sweep.
 */
private fun ulpDistance(measured: Float, reference: Float): Long =
    abs(totallyOrderedKey(measured) - totallyOrderedKey(reference))

private fun totallyOrderedKey(value: Float): Long {
    val bits = value.toRawBits()
    val magnitude = (bits and 0x7fffffff).toLong()
    return if (bits < 0) -magnitude else magnitude
}

// ---- the sweep ------------------------------------------------------------------------------------

/**
 * The eight latitudes swept, and every choice defends against a case that would pass while saying
 * nothing.
 *
 * - **Neither the equator nor a pole.** Both are symmetry points of this calculation: the spike
 *   measured *both* formulations at exactly 0.000 metres on the equator, so a probe sampling there
 *   would have concluded the two are identical, and one sampling only at the clip latitudes would
 *   have concluded the half-angle form is eleven times better. Neither is the answer.
 * - **Both hemispheres, and no round numbers.** `sin phi` changes sign across the equator and `t`
 *   crosses 1 — and it is right there, where `t^2` is near 1, that `t^2 - 1` cancels, which this
 *   probe measures at 31 ULP on `Apple M3 Max`. A northern-only sweep never sees it.
 * - **Four bases inside 40 degrees and four outside**, because the two halves of the domain fail
 *   differently: `d(sin phi) / d psi` is `cos^2 phi`, which is 0.95 at 13 degrees and 0.0095 at 84.4,
 *   so a float32 sine resolves an ULP of `psi` a hundred times better near the equator than near the
 *   clip.
 */
private val BASE_LATITUDES_DEGREES: DoubleArray = doubleArrayOf(
    0.72, -3.11, -17.42, 37.42, -59.91, 78.33, 84.37, -84.61,
)

/**
 * Each base is swept twice, and the two halves ask different questions.
 *
 * **The fine half steps by one representable float32 of `psi`** — the finest question that can be
 * asked, six millimetres of ground at 0.72 degrees and 31 centimetres at 78.33 — and it is where
 * [LatitudePath.rawInversions] and [LatitudePath.rawTies] come from. Those two are measurements of
 * the format and the driver rather than defect counts: near the poles a float32 `sin phi` moves by
 * less than half its own ULP per step, so ties are arithmetic, and a divide implemented as a
 * reciprocal multiply reorders pairs the truth separates by under two ULP.
 *
 * **The resolved half advances until the reference sine has moved [RESOLVABLE_SEPARATION_ULPS] ULP**,
 * which is the question that has a right answer, and it is where [LatitudePath.resolvedInversions]
 * comes from. How many consecutive inputs that takes is a function of latitude and of nothing else —
 * about eight at 0.72 degrees and two hundred at 84.37 — so a *fixed* stride would have been either
 * vacuous at the equator or unresolvable at the pole, and filtering the fine half instead would have
 * left the two polar bases contributing nine comparisons each out of a thousand. Generating the
 * samples this way gives every base an equal say.
 */
private const val SAMPLES_PER_HALF: Int = 1024

private const val SAMPLES_PER_BASE: Int = SAMPLES_PER_HALF * 2

internal const val LATITUDE_PROBE_PIXELS: Int = 128

private const val TOTAL_SAMPLES: Int = LATITUDE_PROBE_PIXELS * LATITUDE_PROBE_PIXELS

private class LatitudeProbeSamples(
    val isometricLatitude: FloatArray,
    val referenceLatitude: DoubleArray,
) {
    /** Consecutive pairs inside one base's fine half; the joins between halves are not consecutive. */
    val rawComparisons: Int = BASE_LATITUDES_DEGREES.size * (SAMPLES_PER_HALF - 1)

    val resolvedComparisons: Int = BASE_LATITUDES_DEGREES.size * (SAMPLES_PER_HALF - 1)

    inline fun forEachRawPair(action: (Int, Int) -> Unit) {
        forEachPairInHalf(0, action)
    }

    inline fun forEachResolvedPair(action: (Int, Int) -> Unit) {
        forEachPairInHalf(SAMPLES_PER_HALF, action)
    }

    inline fun forEachPairInHalf(halfOffset: Int, action: (Int, Int) -> Unit) {
        BASE_LATITUDES_DEGREES.indices.forEach { base ->
            val start = base * SAMPLES_PER_BASE + halfOffset
            for (offset in 0 until SAMPLES_PER_HALF - 1) action(start + offset, start + offset + 1)
        }
    }
}

private fun latitudeProbeSamples(): LatitudeProbeSamples {
    check(BASE_LATITUDES_DEGREES.size * SAMPLES_PER_BASE == TOTAL_SAMPLES) {
        "the sweep must fill the probe's target exactly"
    }
    val isometric = FloatArray(TOTAL_SAMPLES)
    val reference = DoubleArray(TOTAL_SAMPLES)
    BASE_LATITUDES_DEGREES.forEachIndexed { base, degrees ->
        val origin = asinh(tan(degrees * PI / 180.0)).toFloat()
        var fine = origin
        var resolved = origin
        for (offset in 0 until SAMPLES_PER_HALF) {
            val index = base * SAMPLES_PER_BASE + offset
            store(isometric, reference, index, fine)
            store(isometric, reference, index + SAMPLES_PER_HALF, resolved)
            fine = nextUpFloat(fine)
            resolved = nextResolvedFloat(resolved)
        }
    }
    return LatitudeProbeSamples(isometric, reference)
}

private fun store(isometric: FloatArray, reference: DoubleArray, index: Int, value: Float) {
    isometric[index] = value
    // atan(sinh psi) is the Gudermannian written in neither of the two formulations under test, so a
    // defect the two share cannot hide inside the reference.
    reference[index] = atan(sinh(value.toDouble()))
}

/**
 * The next `psi` whose true `sin phi` the float32 format separates from this one by
 * [RESOLVABLE_SEPARATION_ULPS] ULP.
 *
 * Walked rather than computed from `cos^2 phi`, so that the guarantee is exact at every sample
 * instead of approximate at most of them. The walk stays inside the Mercator domain by construction:
 * the widest excursion is at 84.37 degrees, where a thousand of these steps reach 84.66 and the clip
 * is at 85.0511.
 */
private fun nextResolvedFloat(value: Float): Float {
    val separationFloor = RESOLVABLE_SEPARATION_ULPS * float32Ulp(sin(atan(sinh(value.toDouble()))))
    val here = sin(atan(sinh(value.toDouble())))
    var candidate = nextUpFloat(value)
    while (abs(sin(atan(sinh(candidate.toDouble()))) - here) < separationFloor) {
        candidate = nextUpFloat(candidate)
    }
    return candidate
}

/** The float32 ULP at [value], for stating a separation in units the output format can hold. */
private fun float32Ulp(value: Double): Double {
    val magnitude = abs(value).toFloat()
    if (magnitude == 0f) return Float.MIN_VALUE.toDouble()
    return (Float.fromBits(magnitude.toRawBits() + 1) - magnitude).toDouble()
}

private fun asinh(value: Double): Double = ln(value + sqrt(value * value + 1.0))

/** The next larger representable float32, over IEEE-754's total order rather than over bit patterns. */
private fun nextUpFloat(value: Float): Float {
    val bits = value.toRawBits()
    return if (bits >= 0) Float.fromBits(bits + 1) else Float.fromBits(bits - 1)
}

// ---- the driver-side program ----------------------------------------------------------------------

private enum class LatitudeProbeChannel {
    INPUT,
    EXPONENTIAL,
    HALF_ANGLE_SINE,
    HALF_ANGLE_COSINE,
    NAIVE_ARC_TANGENT,
    NAIVE_LATITUDE,
    NAIVE_SINE,
    NAIVE_COSINE,
}

private class LatitudeProbeTarget(val framebuffer: Int, val texture: Int)

/**
 * One point per sample, one pixel per point, and the driver's own float32 bits packed into the four
 * bytes of that pixel.
 *
 * **Why points and not a full-screen quad.** The latitude path this probe exists to check runs in
 * `GlobeGroundPipeline`'s *vertex* shader, and on hardware whose two stages evaluate transcendentals
 * differently a fragment-stage probe would measure the wrong unit. One point per sample is the
 * cheapest way to get one vertex-shader evaluation per readable pixel, and a `flat` varying carries
 * its result to the fragment stage without interpolating it.
 *
 * **Why bytes and not a float target.** A float-renderable colour attachment is an extension on
 * GLES 3.0; four bytes of an ordinary `GL_RGBA8` target are not. `k / 255` is exactly representable
 * on the way out and rounds back to `k` on the way in, which
 * [assertTheDriverReturnsTheExactInputsItWasGiven] verifies rather than assumes.
 */
private class LatitudeProbeProgram(
    private val binding: GlBinding,
    private val target: LatitudeProbeTarget,
    private val program: Int,
    private val vertexArray: Int,
    private val vertexBuffer: Int,
    private val channelLocation: Int,
    private val trigUlpsLocation: Int,
    private val expUlpsLocation: Int,
    private val columnsLocation: Int,
) {
    fun read(channel: LatitudeProbeChannel, trigUlps: Float, expUlps: Float): FloatArray {
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer)
        binding.viewport(0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
        binding.disable(GL_SCISSOR_TEST)
        binding.disable(GL_DEPTH_TEST)
        binding.disable(GL_CULL_FACE)
        binding.disable(GL_BLEND)
        // Dithering may only choose between the two representable values bracketing an incoming one,
        // so an exactly representable byte survives it -- but a probe whose whole output is exact
        // bytes should not be relying on that reading of the specification.
        binding.disable(GL_DITHER)
        binding.colorMask(true, true, true, true)
        binding.clearColor(0f, 0f, 0f, 0f)
        binding.clear(GL_COLOR_BUFFER_BIT)

        binding.useProgram(program)
        binding.bindVertexArray(vertexArray)
        if (channelLocation >= 0) binding.uniform1i(channelLocation, channel.ordinal)
        if (trigUlpsLocation >= 0) binding.uniform1f(trigUlpsLocation, trigUlps)
        if (expUlpsLocation >= 0) binding.uniform1f(expUlpsLocation, expUlps)
        if (columnsLocation >= 0) binding.uniform1i(columnsLocation, LATITUDE_PROBE_PIXELS)
        binding.drawArrays(GL_POINTS, 0, TOTAL_SAMPLES)

        val pixels = ByteArray(TOTAL_SAMPLES * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, target.framebuffer)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
        binding.readPixels(
            0, 0, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS,
            GL_RGBA, GL_UNSIGNED_BYTE, pixels,
        )
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.bindVertexArray(0)
        binding.useProgram(0)

        val values = FloatArray(TOTAL_SAMPLES)
        for (index in 0 until TOTAL_SAMPLES) {
            val at = index * 4
            val bits = ((pixels[at].toInt() and 0xff) shl 24) or
                ((pixels[at + 1].toInt() and 0xff) shl 16) or
                ((pixels[at + 2].toInt() and 0xff) shl 8) or
                (pixels[at + 3].toInt() and 0xff)
            values[index] = Float.fromBits(bits)
        }
        return values
    }

    fun measurePaths(
        samples: LatitudeProbeSamples,
        trigUlps: Float,
        expUlps: Float,
    ): LatitudePaths = LatitudePaths(
        halfAngle = summarise(
            "half-angle",
            samples,
            read(LatitudeProbeChannel.HALF_ANGLE_SINE, trigUlps, expUlps),
            read(LatitudeProbeChannel.HALF_ANGLE_COSINE, trigUlps, expUlps),
        ),
        naive = summarise(
            "naive",
            samples,
            read(LatitudeProbeChannel.NAIVE_SINE, trigUlps, expUlps),
            read(LatitudeProbeChannel.NAIVE_COSINE, trigUlps, expUlps),
        ),
    )

    fun destroy() {
        binding.deleteVertexArrays(1, intArrayOf(vertexArray))
        binding.deleteBuffers(1, intArrayOf(vertexBuffer))
        binding.deleteProgram(program)
    }

    companion object {
        fun create(
            binding: GlBinding,
            dialect: ShaderDialect,
            target: LatitudeProbeTarget,
            samples: LatitudeProbeSamples,
        ): LatitudeProbeProgram {
            // A probe that cannot run fails rather than skips.
            val vertexPlan = requireNotNull(scanShaderProfile(PROBE_VERTEX_SOURCE)) {
                "the latitude probe's vertex source must scan"
            }
            val fragmentPlan = requireNotNull(scanShaderProfile(PROBE_FRAGMENT_SOURCE)) {
                "the latitude probe's fragment source must scan"
            }
            val program = when (
                val result = compileShaderProgram(binding, dialect, PROBE_KEY, vertexPlan, fragmentPlan)
            ) {
                is GlProgramResult.Linked -> result.program
                is GlProgramResult.Failed -> throw AssertionError(
                    "the latitude precision probe's $dialect program did not link on this driver",
                )
            }

            val names = IntArray(1)
            binding.genVertexArrays(1, names)
            val vertexArray = names[0]
            binding.genBuffers(1, names)
            val vertexBuffer = names[0]
            binding.bindVertexArray(vertexArray)
            binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
            val attribute = littleEndianBytes(samples.isometricLatitude)
            binding.bufferData(GL_ARRAY_BUFFER, attribute.size, attribute, GL_STATIC_DRAW)
            binding.enableVertexAttribArray(0)
            binding.vertexAttribPointer(0, 1, GL_FLOAT, false, 4, 0)
            binding.bindVertexArray(0)

            return LatitudeProbeProgram(
                binding = binding,
                target = target,
                program = program,
                vertexArray = vertexArray,
                vertexBuffer = vertexBuffer,
                channelLocation = binding.getUniformLocation(program, "rengProbeChannel"),
                trigUlpsLocation = binding.getUniformLocation(program, "rengProbeTrigonometricUlps"),
                expUlpsLocation = binding.getUniformLocation(program, "rengProbeExponentialUlps"),
                columnsLocation = binding.getUniformLocation(program, "rengProbeColumns"),
            )
        }
    }
}

/**
 * Turns one formulation's readback into numbers, and deliberately answers its two questions with two
 * different quantities.
 *
 * **Magnitude is measured on the recovered latitude**, `atan2(sin phi, cos phi)` in `Double` outside
 * anything the driver touched. `atan2` of the pair rather than `asin` of the sine, for the reason the
 * spike gives: near the clip latitudes the sine saturates and carries almost none of `phi`, while the
 * pair carries all of it — and the pair is what the shader emits, so this is where the vertex lands.
 *
 * **Monotonicity is measured on the sine alone**, which is the shader's own `sineLatitude` and the
 * sphere's own `z`. Two reasons, and the second is the one that matters:
 *
 * - `sin` is strictly increasing over `(-PI/2, PI/2)`, so ordering by `sin phi` *is* ordering by
 *   latitude. Nothing is given up by not forming `phi`.
 * - `cos phi` is **not** monotone in latitude — it peaks at the equator — so an `atan2` recovery
 *   inherits the cosine's rounding as apparent inversions that no reordering of latitudes explains.
 *   Measured: the `atan2` recovery reports inversions on `Apple M3 Max` at 37.4 degrees, where a step
 *   of consecutive representable `psi` moves the latitude by 4.7e-8 radians and the float32 pair can
 *   only resolve about 7.5e-8. That is the output format, not a driver swapping two latitudes, and a
 *   probe that called it one would have shipped a tolerance invented to hide it.
 */
private fun summarise(
    name: String,
    samples: LatitudeProbeSamples,
    sine: FloatArray,
    cosine: FloatArray,
): LatitudePath {
    var worstMetres = 0.0
    var worstIndex = 0
    for (index in 0 until TOTAL_SAMPLES) {
        val recovered = atan2(sine[index].toDouble(), cosine[index].toDouble())
        val metres = WGS84_SEMI_MAJOR_AXIS_METRES * abs(recovered - samples.referenceLatitude[index])
        if (metres > worstMetres) {
            worstMetres = metres
            worstIndex = index
        }
    }
    var rawInversions = 0
    var rawTies = 0
    samples.forEachRawPair { first, second ->
        when {
            sine[second] < sine[first] -> rawInversions += 1
            sine[second] == sine[first] -> rawTies += 1
        }
    }
    var resolvedInversions = 0
    var firstResolvedInversion = -1
    samples.forEachResolvedPair { first, second ->
        if (sine[second] < sine[first]) {
            resolvedInversions += 1
            if (firstResolvedInversion < 0) firstResolvedInversion = first
        }
    }
    return LatitudePath(
        name = name,
        worstMetres = worstMetres,
        worstLatitudeDegrees = samples.referenceLatitude[worstIndex] * 180.0 / PI,
        rawInversions = rawInversions,
        rawTies = rawTies,
        resolvedInversions = resolvedInversions,
        firstResolvedInversionDegrees = if (firstResolvedInversion < 0) {
            Double.NaN
        } else {
            samples.referenceLatitude[firstResolvedInversion] * 180.0 / PI
        },
        sine = sine,
        cosine = cosine,
    )
}

private fun createLatitudeProbeTarget(binding: GlBinding): LatitudeProbeTarget {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, LATITUDE_PROBE_PIXELS, LATITUDE_PROBE_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the latitude probe's readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return LatitudeProbeTarget(framebuffer, texture)
}

// ---- the budgets, all derived ---------------------------------------------------------------------

/**
 * `exp`'s own specified bound, `3 + 2|x|` ULP (GLSL ES 3.00 section 4.5.1), evaluated at this
 * probe's widest argument: `|psi| <= PI` over the Mercator domain, so `3 + 2 * PI = 9.283`.
 */
private const val SPECIFIED_EXP_ULP_BOUND: Float = 9.283f

/**
 * What [SPECIFIED_EXP_ULP_BOUND] implies for the half-angle path, in metres, plus the float32 floor
 * of the rational form itself.
 *
 * The spike verified `delta phi = epsilon * cos phi` to 0.78% over 66,712 samples, so
 * `9.283 * 2^-23 * 6378137 = 7.06` metres of `exp` contribution, on top of the 1.52 metres the
 * formulation's own float32 arithmetic costs with every built-in correctly rounded — 8.58 metres, and
 * the spike measured 8.668. Rounded up to leave room for a divide at the specification's own 2.5 ULP,
 * which this probe reports separately rather than folding in.
 *
 * **It is a specification-derived bound and not a budget for a Mali**, which nobody here has
 * measured. A driver outside it fails nothing: the case that uses it stands down and prints the
 * number instead.
 */
private const val MAXIMUM_SPECIFIED_LATITUDE_ERROR_METRES: Double = 12.0

/**
 * How far apart two true latitudes must be, in ULP of the float32 sine, before this probe will call
 * a swap between them a defect.
 *
 * GLSL ES 3.00 section 4.5.1 allows a division 2.5 ULP of error and gives `a + b`, `a - b` and
 * `a * b` correct rounding, so two conforming results can legally come back reordered only when the
 * truth separates them by under five ULP. Eight is that with margin, and it is three orders of
 * magnitude finer than the failure being hunted: MapLibre's field-observed 200-300 metres on
 * Mali-G610/G710 is 120-180 ULP of `atan`, which reorders latitudes thousands of ULP apart.
 */
private const val RESOLVABLE_SEPARATION_ULPS: Double = 8.0

/**
 * A floor on how many comparisons the resolved walk must find, so that "zero inversions" cannot be
 * the report of an empty comparison.
 *
 * The resolved half of every base contributes [SAMPLES_PER_HALF] samples by construction, so the
 * sweep yields one comparison for every two samples. A quarter of [TOTAL_SAMPLES] is half of that,
 * and it fails loudly if the sample generator is ever changed into something that does not resolve.
 */
private const val MINIMUM_RESOLVED_COMPARISONS: Int = TOTAL_SAMPLES / 4

/** Injected into `atan`, `sin` and `cos` for the control; the naive path's slope is 1.661 m/ULP. */
private const val CONTROL_TRIG_ULPS: Float = 64f

/**
 * A floor on what [CONTROL_TRIG_ULPS] must *add* to the naive path, at half the 106 metres the spike's
 * marginal slope predicts.
 *
 * A growth rather than an absolute figure, and the reason is a measurement: on
 * `Apple Software Renderer` the naive path is already **7,116 metres** out before anything is
 * injected, so an absolute floor would have been satisfied by that driver's own `sin` rather than by
 * the injection, and the case would have proved nothing on the one driver where it matters most.
 */
private const val CONTROL_NAIVE_GROWTH_METRES: Double = 50.0

/**
 * How far driving `exp` to its specified bound must move the half-angle path, against the 7.06 metres
 * the same law predicts. Deliberately under a third of it: the claim is that the dependence exists
 * and is metre-scale, not that a particular driver lands on a particular number.
 */
private const val EXP_GROWTH_FLOOR_METRES: Double = 2.0

// ---- tokens this probe needs and the binding does not name ----------------------------------------

private const val GL_POINTS: Int = 0x0000
private const val GL_DITHER: Int = 0x0BD0

private val PROBE_KEY: ResourceKey =
    ResourceKey(ResourceKind.INTERNAL_PIPELINE, "1".repeat(64), null)

/**
 * Both formulations, in one program, written out here rather than imported from
 * `GlobeGroundPipeline` — a guard built on the code it guards moves with that code's regressions.
 *
 * `rengProbeInject` perturbs a result by a deterministic hash of its own argument, which is the
 * spike's stand-in for a table-boundary discontinuity rather than a measurement of any driver: a
 * constant bias is correctable and MapLibre shipped and then deleted exactly such a correction, so
 * the error that matters is the non-smooth kind. The hash is integer arithmetic on the argument's
 * bits, deliberately not the usual `fract(sin(x) * 43758.5453)` — a probe of trigonometric accuracy
 * must not seed itself from a trigonometric function.
 */
private val PROBE_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "layout(location = 0) in float rengProbeIsometricLatitude;\n" +
        "uniform int rengProbeChannel;\n" +
        "uniform int rengProbeColumns;\n" +
        "uniform float rengProbeTrigonometricUlps;\n" +
        "uniform float rengProbeExponentialUlps;\n" +
        "flat out vec4 rengProbeBytes;\n" +
        "uint rengProbeHash(uint seed) {\n" +
        "    uint h = seed;\n" +
        "    h ^= h >> 16u;\n" +
        "    h *= 2146121005u;\n" +
        "    h ^= h >> 15u;\n" +
        "    h *= 2221713035u;\n" +
        "    h ^= h >> 16u;\n" +
        "    return h;\n" +
        "}\n" +
        "float rengProbeInject(float value, float ulps, float argument, uint salt) {\n" +
        "    if (ulps == 0.0) return value;\n" +
        "    uint span = 2u * uint(ulps) + 1u;\n" +
        "    int offset = int(rengProbeHash(floatBitsToUint(argument) ^ salt) % span) - int(ulps);\n" +
        "    uint bits = floatBitsToUint(value);\n" +
        "    int magnitude = int(bits & 2147483647u) + offset;\n" +
        "    if (magnitude < 0) magnitude = 0;\n" +
        "    return uintBitsToFloat((bits & 2147483648u) | uint(magnitude));\n" +
        "}\n" +
        "vec4 rengProbePack(float value) {\n" +
        "    uint bits = floatBitsToUint(value);\n" +
        "    return vec4(float((bits >> 24u) & 255u), float((bits >> 16u) & 255u),\n" +
        "                float((bits >> 8u) & 255u), float(bits & 255u)) / 255.0;\n" +
        "}\n" +
        "void main() {\n" +
        "    float psi = rengProbeIsometricLatitude;\n" +
        "    float t = rengProbeInject(exp(psi), rengProbeExponentialUlps, psi, 7u);\n" +
        "    float tangentSquared = t * t;\n" +
        "    float denominator = tangentSquared + 1.0;\n" +
        "    float halfAngleSine = (tangentSquared - 1.0) / denominator;\n" +
        "    float halfAngleCosine = 2.0 * t / denominator;\n" +
        "    float arcTangent = rengProbeInject(atan(t), rengProbeTrigonometricUlps, t, 1u);\n" +
        "    float phi = 2.0 * arcTangent - 1.5707963267948966;\n" +
        "    float naiveSine = rengProbeInject(sin(phi), rengProbeTrigonometricUlps, phi, 2u);\n" +
        "    float naiveCosine = rengProbeInject(cos(phi), rengProbeTrigonometricUlps, phi, 3u);\n" +
        "    float value = psi;\n" +
        "    if (rengProbeChannel == 1) { value = t; }\n" +
        "    else if (rengProbeChannel == 2) { value = halfAngleSine; }\n" +
        "    else if (rengProbeChannel == 3) { value = halfAngleCosine; }\n" +
        "    else if (rengProbeChannel == 4) { value = arcTangent; }\n" +
        "    else if (rengProbeChannel == 5) { value = phi; }\n" +
        "    else if (rengProbeChannel == 6) { value = naiveSine; }\n" +
        "    else if (rengProbeChannel == 7) { value = naiveCosine; }\n" +
        "    rengProbeBytes = rengProbePack(value);\n" +
        "    float column = float(gl_VertexID % rengProbeColumns);\n" +
        "    float row = float(gl_VertexID / rengProbeColumns);\n" +
        "    float side = float(rengProbeColumns);\n" +
        "    gl_Position = vec4((column + 0.5) / side * 2.0 - 1.0,\n" +
        "                       (row + 0.5) / side * 2.0 - 1.0, 0.0, 1.0);\n" +
        "    gl_PointSize = 1.0;\n" +
        "}\n"

private val PROBE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "flat in vec4 rengProbeBytes;\n" +
        "layout(location = 0) out vec4 rengProbeColour;\n" +
        "void main() {\n" +
        "    rengProbeColour = rengProbeBytes;\n" +
        "}\n"
