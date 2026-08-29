# Cycle G — the harness pass

Task 13's record of what the visual harness actually showed, as the plan's standing obligation E8
requires. Written from measurements and from looking at frames, not from what was hoped.

The two styles are the owner's and their urls carry an api key, so they are named **style 59** (a raster
ground, 6 text layers, 1 line-placed, no icons — the simple case) and **style 86** (three vector sources,
157 text layers, 96 line-placed, 78 carrying icons — the stress case). Neither url appears in this
repository.

## The pass found a defect in the harness before it found anything in the renderer

The globe frames filled the viewport and were **byte-identical at zoom 0, 3 and 14**, while Mercator
responded to the same flag normally. That is the exact signature of a projection ignoring its camera, and
it was diagnosed as one for some time before it was diagnosed correctly.

Two independent measurement failures produced it, and they reinforced each other:

1. **`parseArguments` advanced `index += 2` unconditionally**, so it inspected only even argument
   positions. `--globe` is a valueless flag emitted *before* `--zoom`, which pushed `--zoom` onto an odd
   index the loop never examined — silently, because an unmatched argument is not an error there. Every
   globe run rendered at the storyboard's default zoom of **11.5**, which is precisely the zoom at which
   this cycle's work is invisible: the sphere is far larger than the viewport and its curvature is entirely
   off-screen, so a correct globe and a tangent plane are the same picture. The measured sagitta says the
   same thing from the other side — 0.44 logical pixels of bow at zoom 10.
2. **The statistic used to judge the frames counted pixels that were not white.** The harness clears to an
   `UNDRAWN` sentinel of `(0, 96, 32)` so that "RenG drew nothing here" reads as itself; a not-white test
   counts that sentinel as drawn. It reported a **99.7%-full** viewport for a frame that was **98.9%
   empty**.

The renderer was correct throughout. The lesson is recorded in `CLAUDE.md`: prefer the harness's own
`undrawn %` over any ad-hoc statistic computed against a guessed background, and confirm the harness passed
the renderer what you think it did before believing a frame accuses it.

**A third instance of the same class appeared in the render script itself**, which is worth recording
because it shows how ordinary the failure is. Under `zsh` an unquoted `$3` is *not* word-split, so a
function called with `"-Pglobe -Pzoom=1.5"` handed Gradle one token, which it read as a property named
`globe -Pzoom`. The `globe` property was therefore never set and the pass rendered Mercator while being
labelled globe. It was caught by comparing the two passes' frames byte-for-byte rather than by reading the
log, which reported 48 frames and 0 failures either way.

## The globe is a globe, measured

With zoom reaching the camera, against style 59 at frame 0:

| zoom | undrawn | what that is |
|---|---|---|
| 0 | 94.3% | the sphere occupies 5.7% of the frame |
| 2 | 53.8% | |
| 5 | 0.0% | the sphere exceeds the viewport |
| 11.5 | 0.0% | the storyboard default — reproduces the earlier byte pattern exactly, which is what confirmed where those runs had been sitting |

At zoom 0 the silhouette measures **180 logical pixels across horizontally and 180 vertically** — a circle —
against an analytic tangent-cone prediction of **180.2 px** from `R = 103.4`, an eye distance of `651.9`
and a 45-degree vertical field of view. Agreement to 0.2 px, in a quantity nothing in the suite asserts.

## What was seen, looking at frames

- **The sphere reads as a sphere.** Circular silhouette, correct limb, no distortion at the edge.
- **Tiles meet without seams.** No sliver of background appears between them at any granularity observed,
  which is the failure the one-granularity-per-frame rule exists to prevent.
- **Placements land.** The map-anchored pin sits on the west coast of North America where its coordinate
  puts it, in both projections; the screen-anchored sticker stays pinned to the top-left corner.
- **Labels draw on the globe and land on their features** — legible at zoom 2 on style 59.
- **The strongest correctness signal in the whole pass is an accident of the storyboard.** Late in the
  59-globe sweep, on a pitched and rotated sphere, the map-anchored cyan pin sits **exactly on style 59's
  own "San Francisco" label**. The pin's position comes from a `Placement` resolved through the globe
  placement path; the label's comes from the engine's label placement through an entirely different path.
  Nothing arranges for them to agree, and they do — with Sacramento, Eureka, Reno, Salt Lake City, Seattle
  and Vancouver all sitting correctly around them.
- **The pitched globe is the case worth looking at, and it holds up.** Mid-sweep, with bearing near 166
  degrees and the pitch ramp well past its midpoint, the limb curves across the frame as a smooth horizon,
  the continent is projected with visible curvature toward it, and haloed country labels — GUATEMALA, CUBA,
  UNITED STATES — sit upright on their features rather than following the sphere's surface, which is the
  correct viewport-aligned behaviour. Beyond the limb is the harness's clear colour and nothing else, so
  the far hemisphere is being culled rather than drawn and overdrawn.

## One finding, and it is not a RenG defect

**The polar cap has no imagery.** Web Mercator source tiles end at ±85.0511°, so a cap around each pole has
no tile to sample and RenG draws nothing there. Measured on style 59 at zoom 2: **783 sentinel pixels
enclosed by the sphere across 24 rows**, a notch 14 px wide at the frame edge widening to 56 px. It is
inherent to Mercator tiles on a sphere rather than anything this cycle did — a flat Mercator map simply
never shows the region — but it is a visible artifact a consumer will meet the first time they tilt a globe
toward a pole, and it is recorded nowhere else. What should fill it is an owner decision, not a defect
report.

## Style 86, the stress case

Three vector sources, 157 text layers, 96 line-placed, 78 carrying icons. It renders a full vector map:
roads, buildings, water, POI icons, the consumer's own geometry quad painted by its shader pair, and a
dense label set — "Mission Bay", "Rincon Hill", "West SoMa", "North Beach", "Russian Hill", "Fisherman's
Wharf", "Pacific Heights" among many others — all **upright under a rotated camera**, which is the correct
viewport-aligned text behaviour rather than a bug.

It is also **five and a half times slower than the raster style**: 11m 4s for 48 frames against 1m 48s for
style 59, on an M3 Max. That is a measurement, not a complaint, and it is the first time the label stack's
cost has been observed at corpus scale.

**`icon-text-fit` is out of scope by decision and style 86 uses it**, so its fitted icons draw at the wrong
size rather than being absent. That is E-labels' recorded debt showing up on screen exactly where the
corpus statistics said it would, not a new finding.

## The negative frames now prove label orthogonality, which they did not set out to do

The storyboard's frames 30-32 set `drawBasemap = false` and leave `drawLabels = true`, because the owner's
E-labels decision made the two **completely orthogonal**. The consequence is visible and worth recording:
those frames measure **93.1% undrawn on style 86 against 96.5% on style 59**, and the whole of the
difference is label ink — 157 text layers against 6.

Looking at one of them directly: the ground is gone, the frame is the harness's sentinel colour, and the
labels are still there and still sitting where their features would be — "Financial District", "Nob Hill",
"North Beach", "Fisherman's Wharf" — beside the geometry grid and both stickers. That is the orthogonality
decision working as specified, demonstrated by a frame that exists for an unrelated reason.

## The four videos

`build/cycle-g-videos/`, 960x540, 48 frames each at 12 fps, **labels on** throughout. Each is assembled
twice from the same PPM frames: `<name>.mp4` at `crf 12` / `yuv420p`, which any player opens, and
`<name>-444.mp4` at `crf 12` / `yuv444p`, which is the copy to judge sharpness on. Neither is a default
encode, because the assembly step is lossy and has already once misled a judgement about image sharpness.

| video | frames | failed | wall clock |
|---|---|---|---|
| `59-mercator` | 48 | 0 | 1m 48s |
| `59-globe` | 48 | 0 | 2m 35s |
| `86-mercator` | 48 | 0 | 11m 04s |
| `86-globe` | 48 | 0 | 3m 04s |

**`59-globe` needed a second run.** Its first attempt lost frame 38 to `urlsession error -1001` — a
timeout fetching one ArcGIS raster tile — which the harness reported as
`PREPARE FAILED RESOURCE_UNAVAILABLE at RESOURCE_LOOKUP` and wrote as a frame of pure clear colour rather
than swallowing. That is the same transport-flake class that cost the 34-style run 2 of 1632 frames. The
retry came back 48 of 48.

**A globe is cheaper than a Mercator map at the same style, and by a lot.** Style 86 takes **11m 04s** in
Mercator at the storyboard's zoom 11.5 and **3m 04s** on a globe at base zoom 1.5. That is not a globe
optimisation — it is the zoom. The comparison worth making is at matched zoom, and it is recorded below.

## What was not verified, stated plainly

- **Curvature fidelity.** Nothing here measures whether the sphere's shape is *right* beyond its
  silhouette radius and the absence of seams. That is Cycle J's, by owner decision.
- **The videos were judged by looking at individual PPM frames**, not by watching the encodes frame by
  frame. A defect confined to a frame not inspected would not have been caught.
- **Only two styles.** 34 are available; the corpus-scale pass was E-labels' and is not repeated here.

## One suspected globe artifact, measured away

Style 86 on a globe shows a **speckled texture across land and water** that is not present in the same
style's Mercator video, which is rendered at the storyboard's zoom 11.5. The obvious reading is that the
globe's UV sampling is doing something to the style's fill patterns.

It is not. Rendering **Mercator at the globe's own base zoom of 1.5**, over a full 48-frame sweep so that
`t = index / (FRAME_COUNT - 1)` puts frame 25 at the same zoom, bearing and pitch as the globe's frame 25,
produces the **same speckle**, the same label set and the same icon markers on a flat map. The texture is
style 86's own pattern fills evaluated at low zoom, and the globe adds nothing to it.

**The first attempt at this control was wrong and would have proved nothing.** It passed `-PframeCount=26`
so the run would be quick, which changes `FRAME_COUNT` and therefore changes `t` — frame 25 of 26 is
`t = 1.0`, at zoom 4.0 and bearing 270, against the globe frame's `t = 0.532`, zoom 2.83 and bearing 143.6.
Two frames at different cameras would have differed for reasons having nothing to do with the question.
A control has to hold everything but the one variable, and a storyboard whose parameters are derived from
the frame *count* makes that easy to get wrong.
