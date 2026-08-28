# The E-labels harness pass — a human look at drawn text

Run 2026-08-29 on branch `el/t19-harness`, from `ddeb750` plus this task's own harness change, on an
Apple M3 Max (macOS 26.5.2). The Render Context the harness reports is
`vendor=Apple | renderer=Apple M3 Max | version=4.1 Metal - 90.5 | glsl=4.10`. `VERSION_NAME` is `0.4.0`,
published to `build/local-maven` and consumed by the standalone `consumer-smoke` build; Rentile is pinned at
`0.6.0`, Kotlin 2.3.21, AGP 9.3.1.

This is task 19 of `docs/superpowers/plans/2026-08-28-cycle-e-labels.md` — half of the cycle's gate under
E8. The other half is the analytical readback suite. This half is a *human look*, and the sections below
report what was on the screen rather than what the suite asserts.

**No style document, URL, credential or tile template appears in this file, and none was written into the
repository.** The two styles the owner supplied are called **style 59** and **style 86** throughout, the
same convention `docs/research/2026-08-28-e-labels-corpus-statistics.md` uses. Both documents were fetched
to the session scratchpad, and only aggregate counts came back.

---

## Legibility is unverified until Cycle J, and this document is not a regression gate

**A human looked at 192 frames once. Nothing here can catch a regression tomorrow.** Pixel verification is
deferred to Cycle J by owner decision, so no frame in this pass is stored, hashed, or compared against
anything; nothing in CI reproduces it; and a later change that makes every label unreadable would pass the
whole suite and this document would not notice. Where a sentence below says text "is readable", it means
*one person read it on one machine on one day*, and it must not be read as a claim RenG can defend.

What **is** gated is the analytical readback suite, which proves relationships — that a label's ink lands
inside the box the placement computed, that two placed labels' boxes do not intersect, that fade opacity is
in `[0, 1]`. Legibility is not a relationship, which is exactly why this pass exists and exactly why it
cannot replace the suite.

---

## Verdict

**Text draws, it lands on its features, it does not overlap, it follows roads, it is haloed, and it eases
in and out.** Every property the task set out to look at was visible in the frames, and at 960×540 the
larger labels are comfortably readable.

**One defect found, and it is the kind that only a moving camera shows: every level-of-detail change
restarts every label's fade, so the map's whole text layer blinks out and takes ten frames to come back.**
It reproduced on both styles, at all three LOD crossings of the storyboard, measured rather than eyeballed.
Details in "The defect" below.

**Three things that looked wrong and were not**, each checked and dismissed with evidence rather than
assumed: apparent overprinted labels, the absence of icons, and the 55-second frames at the end of style
86's sequence. Also in a section of their own, because the next reader will see all three.

---

## What was run

The loop is `CLAUDE.md`'s, unmodified:

```bash
./gradlew --no-configuration-cache :kmp:publishAllPublicationsToLocalTestRepository
./gradlew --no-configuration-cache -p consumer-smoke runHarness   # RENG_HARNESS_STYLE_URL in the environment
```

The style url went in through `RENG_HARNESS_STYLE_URL` rather than `-PstyleUrl=` so that it never appeared
on a command line. Four runs, 48 frames each, 192 frames total, all four writing 48 of 48 with zero failed
frames:

| run | wall clock | transport | store | diagnostics |
|---|---|---|---|---|
| style 59, labels on | 1m 59s | 254 requests | 113 entries, 1,205 reads (1,092 hits), 254 writes | none |
| style 59, labels off | 1m 31s | 195 requests | — | none |
| style 86, labels on | 11m 39s | 308 requests | 129 entries, 1,526 reads (1,359 hits), 270 writes | `LABEL_CONTENT_EXCLUDED` INFO ×10, WARNING ×1 |
| style 86, labels off | 10m 48s | 300 requests | 124 entries, 1,309 reads (1,147 hits), 262 writes | none |

The only transport failures in either style were three HTTP 204 responses for ocean vector tiles in style
86 — reported precisely by the harness, with the api key redacted, and not swallowed. No frame failed to
prepare or draw in any run.

### The `-PnoLabels` switch this task added

`consumer-smoke`'s harness gained one flag: `-PnoLabels` renders the same camera path with
`drawLabels = false` on every frame (`--no-labels` on the binary). It exists because *"is there any label
ink in this frame, and how much"* cannot be answered by looking — small pale text over a busy satellite
raster is invisible to a census of mean RGB and ambiguous to the eye. Subtracting an unlabelled run from a
labelled one answers it exactly, and it is how every number in "The defect" below was obtained.

It earns its place twice over: it is also the only consumer-side exercise of task 4's new public
`FramePlan.drawLabels` field, and it confirmed something worth knowing on its own — see "Icons draw no ink"
below.

Nothing in `kmp/` was touched by this task.

---

## Style 59 — the simple case

A raster ground under six symbol layers (country, state, city, town/village, water, and one `line`-placed
road layer). Over San Francisco at zoom 11.5 → 14 this yields between zero and three labels per frame.

- **Text draws, on its feature, and is sharp.** "San Francisco" sits on the city, white with a dark halo,
  crisp at 960×540 with clean kerning and no double-imaging. Judged on the PPM frames at 5× nearest-neighbour
  magnification, never on an encoded video, exactly as `CLAUDE.md` warns.
- **Halo works, in one pass.** The dark ring around the white fill is even on all sides and does not
  detach or ghost at any size seen.
- **A line-placed label follows its road.** "Sacramento Street" is rotated to the street's screen direction
  under a bearing of 172° and a pitch of 38°, and it stays on the road as both change. The streets under it
  are straight, so this pass shows *rotation* along a line and does not exercise per-glyph *bending*; no
  curved road label appeared in either style at these zooms.
- **Fade eases rather than pops.** "San Francisco" appears at frame 4 as a faint ghost and reaches full
  opacity around frame 12 — ten frames, matching `LABEL_FADE_STEPS`.
- **Labels draw with `drawBasemap = false`.** Frames 30–32 carry no ground at all and still show
  "San Francisco" and "Sacramento Street" over the harness's clear colour. E7's orthogonality is visible,
  not just asserted.
- **Very few road labels at zoom 14.** The last frames of the sequence show one label where a street grid
  is fully in view. Whether that is Rentile's candidate set or RenG's collision is not distinguishable from
  the frames alone, and it is recorded here as an observation rather than a defect.

## Style 86 — the stress case

Three vector sources, 366 layers, 170 symbol layers, **157 carrying text, 96 of them `line`-placed, 91
carrying an `icon-image`**, and — worth recording — **zero using `icon-text-fit`**, so this cycle's
`icon-text-fit` exclusion does not bite anywhere in this style. 68 of the text layers ask for a text size of
10.67 px.

- **Dense text, drawn and placed.** Thirty-odd labels per frame at zoom 13: neighbourhood names, transit
  stations, POIs, in the styles' own colours (black, red, pale grey), each on its feature.
- **Collision holds.** Across every frame inspected — including the densest, frame 28 with 8,847 pixels of
  strong label ink — no two labels overlap. Isolating label ink by subtracting the unlabelled run makes this
  easy to see, and it is the cleanest confirmation of the property the analytical suite proves.
- **Multi-line labels wrap and stay legible**: "San Francisco Transbay development", "Lower Nob Hill
  Apartment Hotel District", "4th and Brannan station" all break onto two or three centred lines.
- **Frames 30–32 with no basemap are the best view of the label pass in the whole run** — thirty labels on
  a flat green field, nothing else but the storyboard's stickers and geometry. Anyone reviewing this cycle
  should look at those three frames first.
- **A label covers a map-anchored sticker, and a screen-anchored sticker covers a label.** Both are visible
  in the frames and both are ADR 0034's phase order (labels at 5, consumer screen-anchored stickers at 6).
- **Small text is soft.** At the 10.67 px most of this style asks for, glyphs are chunky and a wide halo
  eats into the fill; over the busy raster ground, several such labels are hard to read. The constants in
  `LabelPipeline.kt` are MapLibre's (`EDGE_GAMMA`, the halo blur factor, the 24 px SDF em with a 3 px
  buffer) and the smoothstep band is divided by the glyph's own scale, so this may be faithful rather than
  wrong — but it is where legibility will be decided, and it is the reason the Cycle J caveat above is not a
  formality.
- **`LABEL_CONTENT_EXCLUDED` fired on 11 of 48 frames** (10 INFO, 1 WARNING). ADR 0036's aggregate
  diagnostic is doing its job — the engine excluded some label content on roughly a quarter of this style's
  frames, and RenG said so once per frame instead of per item. Which frames is not recoverable from the
  harness's summary; a `--verbose` run would attribute them.

---

## The defect: every LOD change restarts every label's fade

**What is seen:** on a camera that zooms smoothly, the map's entire text layer vanishes for a frame or two
and then fades back in over ten frames. In style 59 this means frames with *no text at all* between frames
that carry three labels; in style 86 it means the whole label layer dropping to a ghost.

**Measured, by subtracting the unlabelled run from the labelled one and counting pixels whose colour moved
by more than 64 in any channel** — i.e. opaque label ink:

| frame | 0 | 3 | 12 | 18 | **19** | 20 | 25 | 28 | 33 | 37 | **38** | 40 | 44 | 47 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| style 59 | 0 | 13 | 1,048 | 1,044 | **0** | 0 | 583 | 1,138 | 1,241 | 1,026 | **0** | 0 | 245 | 536 |
| style 86 | 0 | 150 | 3,506 | 4,629 | **0** | 0 | 6,138 | 8,847 | 7,524 | 6,707 | **0** | 66 | 4,030 | 4,749 |

The sequence is a sawtooth in both styles: a ten-frame ramp to full opacity, an instantaneous collapse to
zero, another ramp. The collapses are at frames **1, 19 and 38** — and the storyboard's zoom is
`11.5 + 2.5 · t`, so:

| frame | zoom | `ceil(zoom - 0.5)` |
|---|---|---|
| 0 | 11.500 | 11 |
| 1 | 11.553 | **12** |
| 18 | 12.457 | 12 |
| 19 | 12.511 | **13** |
| 37 | 13.468 | 13 |
| 38 | 13.521 | **14** |

Every collapse is a LOD change under `observeMercatorLod`, and there are exactly three of each. Three for
three, in two styles, is not a coincidence.

**Why:** `deriveLabelIdentity` (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/label/LabelIdentity.kt`)
puts `candidate.sourceTile.z`, `.x` and `.y` into the identity. When the selected LOD changes, every label
in view is served by a different tile, so every identity changes, so `LabelFadeState` has no entry for any
of them and every label restarts at step 0. The file's own rule — "an identity is what a label *is*, never
how it is drawn this frame" — is the right rule; the source tile is on the wrong side of it. A place name
is the same place name whether the z13 tile or the z14 tile delivered it, and the tile is a property of
*this frame's* acquisition in exactly the way the file warns about. It is the "too fine" failure the KDoc
predicts, one field short of the version it rejects.

**Why it is not fatal, and why it should still be fixed:** ADR 0035's fade is deliberately the only
cross-frame label state, and a wrong identity costs nothing but the ease. But the ease is the whole feature,
and a map that zooms — which is what maps do — spends a large part of its time in the ramp. Counted over
this storyboard's 48 frames, strong label ink sits **below half of that run's own maximum on 25 frames in
style 59 and 33 in style 86**, and is **exactly zero on 8 and 7 frames respectively**. A consumer zooming
continuously would see text that rarely reaches full opacity.

**What a fix has to answer** (not decided here): dropping the tile from the identity makes two *different*
features with the same name at the same coordinate in two tiles collide into one entry, which is the
"too coarse" failure the same KDoc describes. Latitude, longitude, layer id and codepoints may already be
enough to separate them; that is a judgement for whoever takes the task, and it wants a test that fails
before the change — the cheapest one is two frames whose only difference is a zoom that crosses a LOD
boundary, asserting the fade step survives it.

---

## Three things that look like defects and are not

**Apparent overprinted labels.** Several places in the ink-difference masks look like two labels drawn on
top of each other — "Chinatown" at frame 28, "Exploratorium" at frame 45. They are not. Putting the
labelled and unlabelled frames side by side at 10× shows a single label each time; the mask counts every
pixel the label's halo and antialiasing changed, and over a mottled satellite raster that reads as a smear.
**The ink mask is the wrong instrument for judging overlap, and the side-by-side is the right one** — worth
knowing, because the mask is convincing and wrong.

**Icons claim space and draw no ink.** Confirmed and expected: task 22 is unwritten. What the `-PnoLabels`
run settled is the other half of it — with `drawLabels = false`, style 86 renders **no text whatsoever** and
**every icon is still there** (pins, trees, transit markers). Those symbols are Rentile's own rasteriser
drawing into the tile underneath, not RenG's. All text in every labelled frame is RenG's own.

**The 55-second frames.** Style 86's frames 38–47 take 49–61 s each against 2.4–5.3 s for frames 1–37. That
is not the label path: the unlabelled run takes 49–59 s on the same frames. It is tile work at zoom 13.5+
with 50° of pitch, and the label path is invisible inside it.

---

## What the label path costs, measured

Comparing the labelled and unlabelled runs frame by frame (wall clock between frame writes):

- **Ordinary frames, style 86, zoom 11.5–13.5:** labelled 2.4–5.3 s, unlabelled 1.9–4.6 s. Labels add
  roughly **0.4–0.9 s per frame**.
- **Frames 30–32, `drawBasemap = false`, zoom ≈ 13.06:** labelled 0.73–0.81 s, unlabelled **0.02 s**. With
  no ground competing, the label path's own cost is **~0.72–0.79 s per frame** — tile selection, the
  handover, placement, collision, atlas upload and the draw, none of it retained across frames.
- **High-zoom pitched frames, 38–47:** labelled 49–61 s, unlabelled 49–59 s. Indistinguishable.
- **Style 59** (six symbol layers): whole run 1m 59s labelled against 1m 31s unlabelled — about **0.6 s per
  frame** of labels across 48 frames.

These are whole-frame wall-clock figures from a harness that also fetches over the network, so they are an
upper bound on `prepare()`'s label share and not a substitute for the cycle's own measurement — task 17
measured `prepare()` directly on a stationary camera at 514–520 ms for labels alone against 93–102 ms for
the ground, and the harness's ~0.75 s sits where a network round trip on top of that would put it. They do say
one thing plainly: **the label path costs about three quarters of a second per frame on a 157-text-layer
style with nothing retained across frames**, which is the residency work's argument in the form a consumer
would feel it.

---

## How to reproduce

```bash
./gradlew --no-configuration-cache :kmp:publishAllPublicationsToLocalTestRepository
RENG_HARNESS_STYLE_URL=<style url> ./gradlew --no-configuration-cache -p consumer-smoke runHarness
RENG_HARNESS_STYLE_URL=<style url> ./gradlew --no-configuration-cache -p consumer-smoke runHarness -PnoLabels
```

Keep the two runs' frames in separate directories, then difference them per pixel to isolate label ink.
**Judge sharpness on the PPM frames directly**, or encode at `-crf 12 -pix_fmt yuv444p`; the default
`ffmpeg` line the harness prints is `yuv420p` and lossy, and text is precisely where that misleads.
