# Cycle J preflight — what three cycles did to the golden-image design

**J already has a design.** `docs/research/2026-08-21-golden-image-gate-design.md` is 769 lines and its
core is still right: per-`(rendererString, dialect)` baselines, tolerance rather than a digest, no
cross-platform pixel equality. This document does not restate it. It records **what changed underneath it**
between 2026-08-21 and 2026-08-30, because E-labels, G and E-terrain all landed in that window and the
design predates every one of them.

Everything below is measured on this checkout or quoted from a record, not estimated.

---

## 1. Its central precondition has been met, comprehensively

§1.2 of the design is titled *"The analytical readback: designed, not landed"*. It has landed, thirteen
times over:

```
BasemapReadbackSuite          GlobeFrameReadbackSuite       GroundDepthReadback
GeometrySubdivisionReadback   GlobeGroundReadbackSuite      GroundDisplacementReadback
GroundAnchorReadbackSuite     GroundCullReadbackSuite       GroundShadingReadback
ModelReadbackSuite            TerrainFrameReadbackSuite     LatitudePrecisionProbe
                                                            VertexTextureFetchProbe
```

The design names **none** of them, because on 2026-08-21 none existed. The suites total **4,981 tests**
across three targets today, against 1,123 when the design was written.

**This is the single biggest change to J's premise.** The design argued for a golden-image gate partly
because nothing else asserted pixels. Now a great deal does — analytically, with tolerances derived from
geometry rather than from stored images, and measured against deliberately broken builds. J's question is
no longer "how do we assert pixels at all" but **"what does a stored baseline catch that thirteen
analytical suites do not?"** That question is not answered here and should open the grilling.

## 2. Two of the design's own divergence sources got much worse, and one is now quantified

§3.2(e) and (f) bound floating-point and shader-precision divergence with "tolerance". Cycle G then
**measured** the shader half on the very driver CI depends on. `Apple Software Renderer` evaluates
transcendentals at **atan 1,687 ULP, sin 170,439 ULP, cos 117,441 ULP** — and the practical consequence
was a naive latitude formulation landing **7,118 m** from where a `Double` reference says, against the
half-angle form's **0.680 m**.

That is not a tolerance question any more. A **globe** frame's geometry passes through shader `exp`, `sin`,
`cos` and `atan`, so two renderers do not merely differ in the last place — they can differ by kilometres
of ground. The design's rule (*never compare across renderers*) survives intact and is if anything
vindicated; what does **not** survive is any assumption that a single tolerance number can serve both
projections. **A globe baseline and a Mercator baseline are different instruments.**

## 3. Three new content sources the design never considered

**Labels (E-labels).** Glyph coverage is rasterised by **Rentile, through Skia**, not by RenG. Its output
is therefore a function of a third-party library's version, and RenG pins that library. §5.5 anticipated
"the Rentile/Skia problem needs its own seam" without knowing labels would land there. A baseline
containing text is keyed on the Rentile pin as surely as on the renderer string, and this cycle moved that
pin twice (`0.6.0`, then `0.7.0`).

**Fade (E-labels, ADR 0035).** Label fade is the **only cross-frame state RenG carries**. A corpus that
renders one frame per plan gets an unfaded or partially-faded frame depending on what preceded it, so a
corpus frame must either pin the fade state or exclude labels. Neither is in the design.

**Terrain (E-terrain).** A terrain frame's pixels depend on **DEM tiles fetched from a live source**. The
corpus cannot depend on the network, so terrain baselines need fixture DEMs — which exist (`DEM_RIDGE_PNG`
and friends) but only inside test sources. Note also that RenG no longer decodes DEMs at all: since
Rentile `0.7.0` the texels arrive decoded, so **the pixels a terrain baseline captures depend on Skia's
image decode**, which is the same seam as labels.

## 4. Two suites already stand down, and a baseline must not paper over it

Measured on this checkout, four printed stand-downs on the drivers CI actually runs:

| target | case |
|---|---|
| `macosArm64Test` | `the ground covers the frame` |
| `macosArm64Test` | `the two modes agree at the centre and part at the edge: Mercator ground coverage` |
| `iosSimulatorArm64Test` | both of the above |

These are the rasterisation probe distrusting `Apple Software Renderer`, which drops large quads reaching
far outside the viewport. **A golden-image gate on a driver whose rasteriser the project already distrusts
would be recording that driver's defects as the baseline.** The design's §5.2 chooses targets without
knowing this; J must decide whether a stood-down case may still contribute a baseline, and the answer is
probably no.

## 5. What has *improved* for J

- **`GL_PACK_ALIGNMENT`** — §3.2(g) asks for it to be set explicitly in the readback helper. Every
  readback suite written since does exactly that. Latent risk closed.
- **A harness that writes frames** already exists and is proven: 960×540 binary PPM, `ffmpeg`-assembled,
  and it has now found defects in four separate cycles. J's corpus renderer may not need to be new code.
- **Determinism has a live guard.** `ModelReadbackSuite` case 4 and X2's task 5 assert a prepared frame
  drawn twice is bit-identical, and E-labels' collision determinism is asserted across two `prepare()`s.
  A corpus rests on exactly that property and it is now gated rather than assumed.

## 6. What this preflight does NOT establish

- **Whether J is still worth its cost**, given §1. That is the first grilling question, not a foregone
  conclusion.
- **Any measurement of baseline size or repository impact.** The design's §4 reasons about where baselines
  may live under `check_repository_policy.py`; the policy has since grown a third dependency scope and a
  third fingerprint form, and nobody has re-read §4 against it.
- **Whether the corpus should carry the globe at all**, given §2. A globe baseline may be pinned so tightly
  to one driver's transcendentals that it gates nothing portable.
- **Anything about Android or a real mobile GPU.** ADR 0033 still governs, and no GPU beyond Adreno and
  Apple has ever been measured.
