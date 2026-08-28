# The latitude precision spike — does G5's trig-free formulation actually hold?

Measured on 2026-08-28 on `g/spike-latitude`, branched from `feat/e-labels` at `ab2f05e`, on Apple M3 Max,
Kotlin 2.3.21, OpenJDK 26.0.2 and Kotlin/Native 2.3.21. This answers the arithmetic half of the question
`docs/superpowers/specs/2026-08-28-cycle-g-globe-design.md` §7 raises and
`docs/research/2026-08-28-g-globe-prior-art.md` finding 3 sources: *"GLSL ES gives the trigonometric
functions undefined precision, and the mercator→sphere conversion is nothing but trigonometry."*

Everything below is CPU arithmetic. **It measures formulations, not drivers.** See
"What this spike cannot settle" at the end, and read that section before quoting any number here in a
plan.

## Verdict

**G5's mitigation holds, but it is narrower than the design states and its stated rationale is wrong in
one place.** Three findings, in the order they matter:

1. **The identity is sound.** The tangent half-angle form reaches the sphere's Cartesian coordinates with
   `exp`, multiply, add and divide, and nothing else. It agrees with the naive form to
   **4.4 × 10⁻¹⁶ rad (2.8 nanometres)** across 200,134 asymmetric samples, checked against two further
   independent Double formulations.

2. **It buys immunity to the unbounded built-ins, and that is worth 1.66 m per ULP.** The naive path costs
   **1.66 metres of latitude per ULP of `atan` error**, worst case over the domain; the half-angle path is
   moved by that error by **exactly zero**, bit for bit. MapLibre's field-measured 200–300 m on
   Mali-G610/G710 therefore implies roughly **120–180 ULP** of `atan` error — about 7 mantissa bits gone,
   `atan` returning 17 good bits of 24. That is entirely plausible for a function the specification
   declines to bound, and it is what the mitigation removes.

3. **It buys nothing else, and two things the design implies are not true.** With correctly rounded
   built-ins the two forms are within **0.4 m** of each other over the whole domain, and near the equator
   they **agree to 2 micrometres** — because what destroys precision at the equator is the shared
   `PI - y*PI*2.0` prelude, one line *above* either identity, not the `- PI/2` the MapLibre source comment
   blames. And at `exp`'s own **specified** worst case of `3 + 2|x|` ULP the half-angle path is off by
   **8.7 m** and loses monotonicity as badly as the naive one. The mitigation converts an *unbounded* error
   into a *specified* one. It does not make the path exact, and it does not fix the equator.

**Recommendation for the plan:** adopt the half-angle form from the first commit, as G5 says — the
argument for it is finding 2 and it is decisive. But drop the equator/mantissa-bits rationale from the
cycle's prose, keep the runtime probe G5 already wants (it is the only thing that can see finding 3's
second half), and treat `precision highp float` and the choice of what coordinate the shader is handed as
**separate, additive mitigations that this formulation does not subsume**.

## 1. The derivation

RenG's normalised mercator y runs 0 at the north clip to 1 at the south
(`MercatorProjection.kt:46-50`):

```
y = (1 − asinh(tan φ) / π) / 2
```

so the quantity the shader can actually reconstruct is the **isometric latitude**

```
ψ := asinh(tan φ) = π(1 − 2y)                                          (1)
```

ψ is the inverse Gudermannian of φ, and `ψ = ln(tan(π/4 + φ/2))`, which inverts to the form the design
quotes:

```
φ = gd(ψ) = 2·atan(exp ψ) − π/2                                        (2)
```

Now set

```
t := exp ψ = tan(π/4 + φ/2)       and       θ := π/4 + φ/2,  so  φ = 2θ − π/2.
```

The tangent half-angle identities for the doubled angle 2θ, in terms of `t = tan θ`, are

```
sin 2θ = 2t / (1 + t²)            cos 2θ = (1 − t²) / (1 + t²)
```

and because φ is 2θ rotated by a quarter turn, the sine and cosine swap:

```
sin φ = sin(2θ − π/2) = −cos 2θ = (t² − 1) / (t² + 1)                  (3)
cos φ = cos(2θ − π/2) =  sin 2θ = 2t / (t² + 1)                        (4)
```

(3) and (4) are exactly what the sphere needs — `P = R·(cos φ·sin λ, sin φ, cos φ·cos λ)` — so **φ itself is
never formed**. `atan`, `sin` and `cos` leave the latitude path entirely, and `exp` is the only
transcendental left.

**An independent check on the algebra, not on the arithmetic.** Substituting `t = e^ψ` into (3) and (4):

```
(t² − 1)/(t² + 1) = (e^ψ − e^−ψ)/(e^ψ + e^−ψ) = tanh ψ
2t/(t² + 1)       = 2/(e^ψ + e^−ψ)            = sech ψ
```

and `sin(gd ψ) = tanh ψ`, `cos(gd ψ) = sech ψ` are the standard Gudermannian identities. So the rational
form is not a numerical approximation of anything — it *is* `(tanh ψ, sech ψ)`, written in the one
transcendental the specification bounds.

**That last clause is the whole point, and it is worth stating because the obvious simplification undoes
the spike.** Writing the shader as `tanh(psi)` and `1.0/cosh(psi)` is mathematically identical and
**precision-wise no better than the naive form**: GLSL ES 3.00 §4.5.1 gives the hyperbolic built-ins no
bound either. Only the rational expression in `exp` inherits a contract.

## 2. Method

`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/projection/LatitudePrecisionSpikeTest.kt`
(`g/spike-latitude`, commit `45f9c54`) is 12 checks and no GL. It models a shader rather than running one:

- **A quantiser.** `Format(mantissaBits, minNormalExponent, maxNormalExponent)` rounds a Double to an
  arbitrary IEEE binary format with round-half-to-even, exponent read out of the bit pattern.
  `float32` is (23, −126, 127); `mediump` is modelled as binary16, (10, −14, 15). **It is self-checked
  bit-for-bit against Kotlin's own `Double.toFloat()` on 280,008 values**, including negatives, both signed
  zeroes, subnormals and exact ties — because a harness whose arithmetic model is wrong measures nothing.
- **A modelled GPU.** Every arithmetic result is rounded to the format after every operation, and every
  built-in carries a settable error in ULPs *of its own result*. `exp` has the specification's `3 + 2|x|`
  ULP bound; `atan`, `sin`, `cos` have none, so their error is a sweep rather than a constant. Division
  carries its own knob (the spec allows 2.5 ULP).
- **Both paths, spelled as the shaders spell them.** Both share
  `psi = PI - mercator_y * PI * 2.0`, verbatim from MapLibre's
  `_projection_globe.vertex.glsl:71`, so the comparison isolates the identity and nothing else.
- **The error metric is where the vertex lands.** Each path yields a `(sin φ, cos φ)` pair; the latitude
  is recovered as `atan2` of that pair in full Double precision *outside* the model, and the error is
  `6378137 · |φ_computed − φ_true|` metres. Radial error is reported separately.

**Sampling is deliberately asymmetric.** 200,134 samples: a golden-ratio additive sequence over y (which
lands on no round number), plus geometric ladders with irrational multipliers running towards both clip
latitudes and towards the equator from both sides, down to 10⁻¹¹ degrees, plus eight named places. Exactly
100,000 of the 200,000 sequence samples are southern, and the ladders are balanced across both
hemispheres. **None lands on the equator or on either clip latitude.**

Those three points are measured in their own check and reported as proving nothing:

| symmetry point | naive | half-angle |
|---|---|---|
| equator | **0.000 m** | **0.000 m** |
| north clip | 0.911 m | 0.083 m |
| south clip | 0.604 m | 0.068 m |

The equator returns exactly zero from both forms. A comparison sampled there would have concluded the two
formulations are identical, and a comparison sampled only at the clips would have concluded the half-angle
form is 11× better. Neither is the answer.

**Every error figure below is identical on the JVM and on Kotlin/Native.** Of the 70 reported lines, 61
match character for character. The nine that differ are: four argmax *arguments* (which of several tied
latitudes won a maximum), three Double-reference maxima that differ by one ULP of Double at the 10⁻¹⁶ rad
— nanometre — level, and two figures agreeing to 9 and 12 significant figures respectively
(1.9345640428 vs 1.9345640435 m, and 1.66076106178800 vs 1.66076106178869 m per ULP). **No reported
error-in-metres figure differs at the printed precision.** The measurements are not an artefact of one
libm.

## 3. The identity, in Double

Three independent Double formulations against `atan(sinh ψ)` as reference, over all 200,134 samples:

| form | max disagreement |
|---|---|
| `2·atan(exp ψ) − π/2` | 4.44 × 10⁻¹⁶ rad (2.83 nm) |
| `(t²−1)/(t²+1), 2t/(t²+1)` | 4.44 × 10⁻¹⁶ rad (2.83 nm) |
| `tanh ψ, sech ψ` | 3.33 × 10⁻¹⁶ rad (2.12 nm) |

Two to three ULP of Double. **The derivation in §1 is correct.**

## 4. The headline: metres per ULP

Error injected into `atan`, `sin` and `cos` together, worst sign, float32, over the full domain. Latitude
error in metres:

| ULP of trig error | naive, worst over domain | half-angle, worst over domain | naive at 37.42° | naive at 78.33° |
|---|---|---|---|---|
| 0 | 2.625 | 2.273 | 0.032 | 0.284 |
| 1 | 3.864 | **2.273** | 1.406 | 1.833 |
| 4 | 8.582 | **2.273** | 6.255 | 6.313 |
| 16 | 28.45 | **2.273** | 25.509 | 24.386 |
| 64 | 108.0 | **2.273** | 101.602 | 96.602 |
| 256 | 426.3 | **2.273** | 407.266 | 385.476 |
| 1024 | 1700.6 | **2.273** | 1629.5 | 1540.7 |

The half-angle column does not move by one bit across the whole ladder — asserted bit-for-bit on all
200,134 samples at every step, with a companion assertion that the *naive* vector does move on more than
half the samples, so the harness cannot go inert and report a false immunity.

**Marginal slopes**, the deliverable figure:

| | metres of latitude per ULP of `atan`/`sin`/`cos` error |
|---|---|
| naive, worst case over the domain | **1.661** |
| naive at 78.33° | 1.505 |
| naive at 37.42° | 1.591 |
| naive at −17.42° | 1.055 |
| naive at −59.91° | 0.900 |
| naive at 0.72° | 0.761 |
| **half-angle, anywhere** | **0** |

The slope is analytic and checkable. Dominated by the `atan`, with a second-order contribution from the
`sin`/`cos` pair:

```
δφ ≈ 2k · ulp(atan t)  +  k · (cos φ · ulp(sin φ) − sin φ · ulp(cos φ))
```

The domain maximum sits just below **30°**, which is where `sin φ` crosses 0.5 and its ULP doubles while
`atan t` is already past 1.0 and has doubled too. Evaluating the formula there:
`2 · 2⁻²³ · R = 1.5207 m` plus `(0.8660 − 0.5) · 2⁻²⁴ · R = 0.1391 m`, total **1.6598 m/ULP** against
**1.6608** measured — 0.06%. (The argmax is reported at 29.988° rather than 30.000° because at 1024 ULP the
injected error itself pushes the *computed* value across the binade boundary; that is the model behaving
correctly, not drifting.)

**Against the field report.** Bisecting for the trig error that produces 250 m of latitude error gives
**150.0 ULP**. MapLibre's measured 200–300 m on Mali-G610/G710 maps to **120–180 ULP** — `atan` delivering
about 17 correct bits out of float32's 24. Nothing here says a Mali does that; it says that if one does,
this is the size of the consequence, and the half-angle path does not have it.

## 5. What the mitigation does *not* buy

### 5a. With correct rounding the two forms are nearly the same, and at the equator they are the same

float32, every built-in correctly rounded. "prelude" is the shared `PI - y*PI*2.0` line's own contribution;
"own" is each formulation measured against the exact latitude of its own quantised input, isolating it from
input quantisation; "total" includes that quantisation.

| band | prelude | naive own | half own | naive total | half total |
|---|---|---|---|---|---|
| \|lat\| < 0.001° | 0.652675 | **0.652675** | **0.652677** | 1.100 | 1.100 |
| 0.001–1° | 0.761653 | 1.591 | 1.250 | 2.334 | 2.041 |
| 1–30° | 0.780031 | 1.848 | 1.273 | 2.625 | 2.175 |
| 30–60° | 1.149 | 1.934 | 1.519 | 2.440 | 2.273 |
| 60–84° | 0.868547 | 1.766 | 1.081 | 2.082 | 1.531 |
| 84–85.05° | 0.213109 | 1.098 | 0.267812 | 1.096 | 0.349530 |

Worst formulation-only error over the domain: **naive 1.935 m at −46.57°, half-angle 1.519 m at −45.62°**.
Worst input-quantisation-only error: 1.194 m at −1.85°.

Two things follow.

**The half-angle form's advantage in exact arithmetic is 0.4 m, not orders of magnitude.** Its real
advantage is entirely finding 2 above.

**At the equator the two forms agree to 2 micrometres, and both equal the prelude exactly.** MapLibre's
in-source rationale — *"subtracts PI/2 from a value near PI/2, destroying most float32 mantissa bits near
the equator"* — describes a real cancellation, but it is not the binding one. `PI - y*PI*2.0` has already
quantised ψ onto a 2.38 × 10⁻⁷ rad grid one line earlier, and `dφ/dψ = cos φ = 1` at the equator, so both
identities inherit the same 0.65 m floor and neither can improve on it. **Cycle G should not repeat that
rationale**; the mitigation is justified by the unbounded built-ins alone.

### 5b. At `exp`'s own specified worst case the half-angle path is off by 8.7 m

`exp` is the one built-in both forms share, and the one the specification bounds, at `3 + 2|x|` ULP —
so at most `3 + 2π = 9.283` ULP here, since `|ψ| ≤ π`.

| injected `exp` error | naive, worst | half-angle, worst |
|---|---|---|
| 1 ULP | 2.508 m | 2.614 m |
| 9.283 ULP (spec bound) | 8.442 m | **8.668 m** |

Both forms move by the same law, which the spike verifies rather than assumes: a *relative* error ε in `t`
produces a latitude error of exactly

```
δφ = ε · cos φ
```

Driven at 4096 ULP so the prediction clears the float32 rounding floor, the worst deviation from that law
over 66,712 samples is **0.78%**. Its consequence: the half-angle path's specified worst case is
`9.283 · 2⁻²³ · cos φ = 1.11 × 10⁻⁶ rad = 7.06 m`, plus the ~1.5 m arithmetic floor — which is the 8.7 m
measured.

**So the honest statement of the guarantee is: the half-angle latitude path is as accurate as the driver's
`exp`, and no more.** It is bounded where the naive path is not, and a conforming driver may legally be
8.7 m out.

### 5c. `mediump` destroys both formulations, and the identity is irrelevant to that

Modelled as binary16, over the same samples:

| | worst latitude error |
|---|---|
| naive | 21,090 m |
| half-angle | 18,226 m |
| **input quantisation of y alone** | **9,768 m** |

Roughly 20 km either way, of which 9.8 km is the coordinate arriving quantised before any arithmetic
happens. This is prior art's third field report (`maplibre-gl-js#6916`, the globe atmosphere invisible on
Android under a 16-bit `mediump`) reproduced arithmetically. **`precision highp float;` is a separate,
independent and non-negotiable mitigation**, and no choice of identity substitutes for it.

## 6. The field symptom reproduced: distinct y values overlapping

MapLibre #7419's complaint is not an offset — *"some distinct latitude values will overlap"*. Sweeping
20,000 **consecutive representable float32** y values, with every built-in correctly rounded, counting how
many fail to move the computed latitude at all:

| latitude | ground step between consecutive y | prelude ties | naive ties | naive inversions | half-angle ties | half-angle inversions |
|---|---|---|---|---|---|---|
| 0.72° | 1.194 m | 4292 | 4292 | 0 | 4292 | 0 |
| −17.42° | 2.273 m | 0 | 0 | 0 | 0 | 0 |
| 37.42° | 0.951 m | 4292 | **7569** | 0 | 4292 | 0 |
| −59.91° | 1.189 m | 4292 | 4292 | 0 | 4292 | 0 |
| 78.33° | 0.121 m | 12146 | **18409** | 0 | 12146 | 0 |
| −84.61° | 0.223 m | 4291 | **14121** | 0 | 4291 | 0 |

The half-angle column equals the prelude column everywhere: it adds no tying of its own. The naive column
adds up to 10,000 more — at 78.33° it collapses 92% of consecutive distinct y values onto a shared
latitude, with no driver bug involved at all.

The 4292/19999 (21.5%) prelude floor is arithmetic, not mystery: `y·π` lands a grid of spacing `ulp(y)`
onto one of spacing `2·ulp(y)` or `4·ulp(y)` depending on where in its binade the product falls, and
π ≈ 3.14 < 4. At −17.42° the product lands in a binade where the factor is 2, and the tie count is exactly
0. Neither identity can beat that floor; it belongs to how the shader is handed its coordinate.

**Now with a built-in whose error varies point to point.** A constant bias is correctable — MapLibre
shipped and later deleted exactly such a correction — so what matters is non-smooth error. Modelled with a
deterministic hash of the argument at ±k ULP, at 37.42°, over the same 20,000 steps. **This is a stand-in
for a table-boundary discontinuity of the kind CesiumJS found on Intel HD 4600, not a measurement of any
driver:**

| noisy built-in | k | naive inversions | naive spread | half-angle inversions | half-angle spread |
|---|---|---|---|---|---|
| `atan`/`sin`/`cos` | 1 | **1134** | 3.45 m | **0** | 1.33 m |
| `atan`/`sin`/`cos` | 4 | 5577 | 9.44 m | **0** | 1.33 m |
| `atan`/`sin`/`cos` | 9.28 | 6895 | 19.53 m | **0** | 1.33 m |
| `exp` | 1 | 0 | 2.51 m | 0 | 1.89 m |
| `exp` | 4 | 2755 | 3.99 m | **3708** | 3.57 m |
| `exp` | 9.28 | 5246 | 7.03 m | **5824** | 6.64 m |

Two readings, and the second is the one to carry into the plan.

**One ULP of non-smooth `atan` error is already enough** to make 1,134 of 19,999 consecutive y values move
the wrong way in the naive form. The bug MapLibre saw does not need a badly broken driver; it needs a
driver whose `atan` error is not monotonic, which is what `kubapelc` says theirs is.

**And the half-angle form inverts too, if `exp` is non-smooth above about 1 ULP.** At `exp`'s spec bound it
inverts slightly *more often* than the naive form does. The mitigation's immunity is immunity to
*unspecified* precision, not to error as such. Every real `exp` implementation this spike is aware of is
near 1 ULP — the row where both forms are clean — but that is an assumption about implementations, not
something the specification grants.

## 7. Two smaller measurements

**Input quantisation is a shared floor, and it is parameterisation-dependent.** `δφ = 2π·cos φ·δy`, so with
a **global** float32 y the floor is up to 1.14 m; with a **tile-local** coordinate at zoom z it divides by
2^z:

| latitude | global float32 y | tile-local, z = 11 | tile-local, z = 16 |
|---|---|---|---|
| 0.72° | 0.597 m | 0.29 mm | 0.009 mm |
| −17.42° | 1.139 m | 0.56 mm | 0.017 mm |
| 37.42° | 0.474 m | 0.23 mm | 0.007 mm |
| −59.91° | 0.599 m | 0.29 mm | 0.009 mm |
| 78.33° | 0.060 m | 0.03 mm | 0.001 mm |

This is what G5's second mitigation — per-tile transforms computed in `Double` on the CPU and applied to a
shared grid — actually buys, and it is independent of which identity the shader uses. It also sets the
scale at which the choice stops mattering: at zoom 11 the metre-scale figures in §4 dwarf a sub-millimetre
input floor; the naive form's problem is not the coordinate.

**The rational form costs a little radial accuracy.** The `(sin φ, cos φ)` pair is not exactly unit after
rounding: worst radial error over the domain is **0.266 m for the naive form and 0.748 m for the
half-angle form**, at earth radius — 1.2 × 10⁻⁷ relative. It is an altitude error, not a lateral one, it is
sub-metre, and RenG's depth resolution at the camera is 0.025 logical pixels (§3 of the design), so nothing
here is threatened. It is recorded because it is the one metric where the half-angle form is worse, and a
future reader will otherwise find it and wonder.

## 8. What this spike cannot settle

**It is arithmetic on a CPU. It cannot tell you what a Mali driver does, and nothing in it should be read
as though it can.** Specifically:

- **No GPU executed any of this.** There is no shader, no GL context and no device in this spike. Every
  "GPU" figure is a model: correctly rounded IEEE arithmetic with a hand-injected ULP error. A real driver
  may be worse in ways a uniform ULP injection does not resemble — CesiumJS's Intel HD 4600 failure was a
  *discontinuity*, not a magnitude, and §6's hash model is a caricature of that rather than a measurement
  of it.
- **The 120–180 ULP figure is an inference, not an observation.** It is what the model requires to
  reproduce a number a third party measured on hardware nobody here has. Mali remains unmeasured on this
  project, on any target. The only Android GPU RenG has ever run on is an Adreno 830, the family that was
  *fine* in #7419.
- **Nothing here validates the `exp` assumption**, which §5b shows the whole mitigation rests on. A driver
  whose `exp` sits at the specification's `3 + 2|x|` bound with non-smooth error puts the half-angle form
  back in the failure mode — and no vendor publishes its transcendental accuracy. **This is the strongest
  argument for G5's third mitigation**, the runtime probe: the probe can see this, and this spike cannot.
- **The `mediump` model is binary16.** The GLSL ES 3.00 floor is looser — relative precision 2⁻¹⁰ over a
  smaller range — so a conforming implementation may be worse than the 20 km measured, not better.
- **It measures latitude only.** `spherical_x`, the longitude half, still goes through `sin`/`cos` in
  MapLibre's shader and the maintainers know it; nothing here says what that costs. Nor does it touch
  anything else Cycle G needs — subdivision cost, backface culling, the `clippedPhysicalPixelFootprint`
  rewrite, or the depth arithmetic §3 settles.
- **It says nothing about how the shader gets its coordinate.** §7's tile-local column is arithmetic on a
  hypothesis; RenG has not built a per-tile transform, and the numbers change if the parameterisation does.
- **The half-angle form's bit-for-bit immunity to trig error is true by construction.** It is asserted so
  that a future edit cannot silently reintroduce a trigonometric call, not because the assertion discovered
  anything. The measurement that carries weight is the naive form's slope beside it.

## 9. What the spike changed in the tree

One commit on `g/spike-latitude`, test-source-only: `45f9c54`, adding
`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/projection/LatitudePrecisionSpikeTest.kt`.
`kmp/api/kmp.klib.api` does not move and `:kmp:checkKotlinAbi` passes; `kmp/build.gradle.kts` and
`gradle/libs.versions.toml` are untouched; `check_repository_policy.py --root .` passes. On this checkout
`:kmp:testAndroidHostTest` runs **1,284 tests, 0 failures, 0 errors, 0 skips** (1,272 before the spike's
12), and `:kmp:macosArm64Test --tests LatitudePrecisionSpikeTest` passes all 12 with identical output.

This is spike code. If Cycle G wants any of it permanently, the part worth keeping is the quantiser and the
ULP-injection model — they are what a `measureTranscendentalAccuracy` probe in the idiom of
`measureLargeQuadRasterisation` would need for its CPU-side reference. The 200,134-sample sweeps are a
spike's diligence, not a suite's.
