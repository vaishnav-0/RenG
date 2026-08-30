# Cycle E-terrain task 14 — the coplanar depth fight, measured before anything depended on it

ADR 0039 gave the displaced ground depth writes and quarantined one hazard into wave 2: **a
`GROUND_RELATIVE` placement at altitude 0 sits on the terrain, and its height comes from a CPU
`Double` lookup while the ground's comes from a GPU `Float` texture fetch.** The mitigation the ADR
named for measurement was a shared nearest-texel rule against the same padded texture, so both paths
read the identical texel and `GL_GEQUAL` lets the tie pass.

**The shared texel rule is necessary and it does not close the fight.** It fixes *where the content
sits* — it is worth 3.049 m of vertical error against a bilinear lookup's 10.375 m, and a full
reconstruction of the ground's own drawn surface takes that to 0.000 m — and it changes the z-fight
by nothing at all. With the height matched to the last bit of a `Double` — same texel rule, same
ground cell, same lattice, same triangulation — a copy of the terrain surface drawn through the
geometry pass still loses **about half its contested pixels**, on every rasteriser, at every camera.
What is left is the *path*, which is ADR 0027's original finding restated in new material: the same
surface drawn through the ground's own pipeline keeps **102,400 of 102,400** pixels at every camera on
every driver, and nothing short of that is whole without a lift.

## The instrument

Throwaway commonTest instrumentation, run from one temporary case in `MacosGlConformanceTest` and
one in `IosGlConformanceTest`, deleted before this document was committed. Nothing here is a gate;
the permanent gate is task 19's, and the recipe is at the bottom.

A 320x320 colour texture plus a `GL_DEPTH_COMPONENT24` renderbuffer — `OffscreenSurface`'s own
arrangement — cleared to `REVERSE_Z_FAR_DEPTH` with `GL_GEQUAL`, exactly as `drawFrame` sets it up.
The ground is a **3x3 block of real lod-13 tiles** drawn by the production `drawGround` at 64 cells a
side, which is what `terrainCellsPerTileSide(256, 512)` picks for this camera, so a ground cell spans
**four DEM texels** — production's own ratio, and the reason a texel rule and a surface rule are not
the same thing. The camera is a real `resolveMercatorCamera` at **45.4755 N, 8.6792 E**, the centre of
tile z13/4293/2931, so the anchor is the middle of the centre tile; latitude is deliberately not 0,
where Mercator's `1 / cos(latitude)` term is 1 and a dropped latitude term is invisible.

The DEM is a 256-texel Mapbox interior in a 258-texel padded square, every height round-tripped
through `demElevationMetres` so the fixture's truth and the texture's bytes are one number. Its relief
is **periodic with one tile** and its ring **wraps**, so the 3x3 block is seamless and no tile
boundary is a cliff. Measured: heights **561..1031 m**, at most **9 m** between adjacent texels and
**28 m** across a ground cell. Exaggeration is **1.5**, never 1 — all six corpus styles declare 1, and
at 1 an honoured exaggeration and a dropped one are the same picture.

The content is a 32-cell grid over half the centre tile, drawn through the production `drawGeometry`
with a solid-colour consumer shader pair, under the geometry phase's own depth state: test on, writes
off. Its node spacing equals the ground's, and by default its lattice is offset half a ground cell
east and a third of one south, because a real `Geometry` carries consumer lat/lons and lands wherever
it lands.

Every reading is `survivors / contested`, where **contested** is the set of pixels the content paints
with the ground absent *and* the ground paints with the content absent. Painting three frames rather
than one is what stops "the content is whole" being satisfied by a content that never drew.

## The instrument was wrong four times first

**One ground tile is not enough.** The first fixture drew the centre tile alone. At pitch 45 the
content mostly left it, and the positive control failed with it: content raised a clear 50 m above the
terrain kept only **3,715 of 6,906** pixels, which is a measurement of the fixture rather than of
RenG. The 3x3 block fixed it — raised-50 m now keeps 7,759 of 7,759 at pitch 45.

**A hard floor assertion threw the sweep away.** The first version asserted a contested-pixel floor
per case, so the first camera that painted nothing aborted the run and discarded every later reading.
It prints `INSUFFICIENT` instead.

**One case still measures nothing and is reported as such.** The flat control at pitch 55 paints zero
pixels. The camera sits `320 * (1 + sqrt(2)) / 2 = 386` logical pixels from its anchor, so at pitch 55
its eye is **222** logical pixels above the map plane and the flat control's own surface is at
**205** — the plane is level with the eye and leaves the frame. It is `n/a`, not 0. The same
arithmetic is why this fixture is a steep one at all: exaggerated to 1.5 its summits stand 231 logical
pixels tall against a 386-pixel camera, which magnifies the content enough to fill the frame at pitch
0 and puts the highest ground *above* the eye at pitch 55.

**A zoom-15 sweep was built and thrown away.** At zoom 15 the same terrain stands about **923**
logical pixels tall against that same 386-pixel camera, so the ground is behind the eye and
`groundPainted` is 0 for every arm. The zoom sweep runs at 11, 12 and 13 instead.

**And one floor is real rather than instrumental.** At pitch 55 the raised-50 m control keeps
15,104 of 15,159, because nearer terrain genuinely occludes about 0.4 per cent of it. No arm at that
camera can be read as clean below that floor.

## What it measures

`survivors / contested`, **Apple M3 Max** through CGL, at zoom 13. `maxErr` is how far the arm's own
CPU rule departs from the surface the ground draws, in metres, at the content's own nodes.

| arm | maxErr | pitch 0 | pitch 15 | pitch 30 / bearing 45 | pitch 45 / bearing 180 | pitch 55 / bearing 300 |
|---|---|---|---|---|---|---|
| **J** content drawn as a second ground tile | 0.000 | **102400/102400** | **102400/102400** | **102400/102400** | **102400/102400** | **102400/102400** |
| A flat DEM, height matched | 0.000 | 33600/102400 | 42560/102400 | 33207/78629 | 4480/5760 | n/a |
| A2 flat DEM, +0.01 m | 0.010 | **102400/102400** | **102400/102400** | 78616/78629 | 5760/5760 | n/a |
| B relief, bilinear DEM lookup | 10.375 | 45823/102400 | 48484/102340 | 41143/81557 | 2817/12725 | 17595/19917 |
| C relief, **shared nearest texel** | 3.049 | 61996/102400 | 57151/102347 | 43091/81585 | 5248/12571 | 16211/20115 |
| D relief, ground surface, geometry diagonal | 1.800 | 49882/102400 | 45582/102356 | 42176/81594 | 5467/12644 | 19084/19960 |
| E relief, **exact ground surface** | 0.000 | 53048/102400 | 49509/102355 | 48771/81591 | 6739/12677 | 18094/20029 |
| M E + lattice aligned to the ground's | 0.000 | 48759/102400 | 43325/101929 | 37573/82428 | 4803/13157 | 17069/17826 |
| P M + the ground's own triangulation | 0.000 | 50263/102400 | 48445/101929 | 37311/82428 | 4433/13176 | 6432/17826 |
| P3 P, +0.01 m | 0.010 | 96765/102400 | 96776/101929 | 77730/82428 | 12371/13176 | 17188/17826 |
| P2 P, +1 m | 1.000 | **102400/102400** | **101935/101935** | **82420/82420** | 12543/13065 | 17620/17737 |
| R E, drawn **before** the ground | 0.000 | 0/102400 | 0/102355 | 0/81591 | 0/12677 | 0/20029 |
| H control: E sunk 50 m | 50.000 | 0/102400 | 0/102227 | 0/81929 | 0/17867 | 0/24631 |
| I control: E raised 50 m | 50.000 | 102400/102400 | 102400/102400 | 81210/81210 | 7759/7759 | 15104/15159 |

`Apple Software Renderer` — reached twice, through CGL on macOS and through EAGL on the iOS simulator
— agrees with the M3 Max on every arm to within a per cent, and the two software runs agree with each
other to within 0.3 per cent, which is the honest reading of what the iOS simulator adds here: a
second **context API**, not a second rasteriser. The two places the drivers differ are worth naming.
The flat control at pitch 0 is 33,600/102,400 on the GPU and 47,495/102,400 on the software
rasteriser, and it is a **band** on the GPU (215 rows lost, 0 rows mixed) against a partial speckle in
software (239 rows lost, 97 mixed). At pitch 45 the flat control is 4,480/5,760 on the GPU and
322/5,760 in software.

## Where the residual actually lives

**Lift ladder** — arms E, F, G, S, K and L: the exact ground surface on the offset lattice, raised by
a constant. Pitch 0, zoom 13, M3 Max, out of 102,400 contested:

| lift | 0 | +0.25 m | +1 m | +2 m | +4 m | +16 m |
|---|---|---|---|---|---|---|
| survivors | 53048 | 63446 | 83589 | 95499 | 102370 | **102400** |

**Depth-quantum probe.** The content is drawn as a second ground tile — same program, same matrix,
same vertices, same DEM — with only its `exaggeration` multiplied by `1 + e`, so a known *relative*
height change and nothing else separates the two draws. Pitch 0, zoom 13, M3 Max:

| e | -1e-7 | -1e-6 | -1e-5 | -1e-4 | -1e-3 |
|---|---|---|---|---|---|
| height change | 0.14 mm | 1.4 mm | 14 mm | 0.14 m | 1.4 m |
| survivors | 60869 | 47289 | 5767 | **0** | **0** |

(`e = 0` is arm J itself, at 102,400.)

So a height change of **0.14 mm** already flips 41 per cent of the frame and **0.14 m** deletes all of
it: this depth buffer resolves well under a millimetre of terrain height at zoom 13. A tie is
something you construct on purpose, not something two computations fall into.

Put together, those two ladders decompose the loss:

- **The DEM sampling rule decides where the content sits, and nothing about the fight.** Bilinear
  misplaces it by 10.375 m, the shared nearest-texel rule by 3.049 m — because a ground cell spans
  four DEM texels and the drawn surface is the interpolation between the cell's *corner* texels, not
  the texel underneath the point — and reconstructing the ground's own cell takes it to 0.000 m. At
  zero lift all three lose about half the frame anyway.
- **Matching the surface exactly does not help at zero lift, and matching it exactly is what makes a
  small lift enough.** P at 0 keeps 50,263; P3 at one centimetre keeps 96,765; P2 at one metre keeps
  102,400 at pitch 0 through 30. The offset-lattice arm needs **4 m** to reach the same place. So the
  lattice and the triangulation are worth a factor of about four in the lift a drape needs, and
  nothing at all without one.
- **On a ground with no relief the residual is under a centimetre.** A2 at +0.01 m is whole at pitch
  0 and 15 and within 13 pixels of whole at pitch 30, at zoom 13 — though at zoom 11 the same
  centimetre is worth only 66 per cent, and +0.1 m is what is whole at zoom 11, 12 and 13 alike. The
  extra that relief costs is spent along the
  **crease lines**: every ground grid line is a fold in the surface, and a sub-pixel disagreement
  about which side of a fold a pixel is on is worth the fold's own height, which here reaches metres.
  That is why P3's remaining 5,635 lost pixels are spread over 293 rows rather than clumped.
- **The rest is the path, and the matrix is measurably not the part of it that matters.** Arm T draws
  the content through the geometry *program* but with the ground tile's own model-view-projection and
  tile-local vertices. It reports **53,048/102,400** at pitch 0, **49,509/102,355** at 15,
  **48,771/81,591** at 30, **6,739/12,677** at 45 and **18,094/20,029** at 55 — each figure identical
  to arm E's, which uses `projection * view` on camera-relative vertices. Swapping the matrix moves
  nothing. Arm J is the only arm that is whole, and it differs from every other arm in three ways at
  once — the ground's program, the ground's lattice, and a height its own vertex shader computes
  rather than one handed in as an attribute — so this spike narrows the residual to that bundle
  without splitting it further.

**One option is refuted rather than weighed.** Drawing ground-relative content **before** the ground
(arm R) keeps **0 of 102,400** pixels at every camera on every driver: under `GL_GEQUAL` the ground
wins the tie it is now drawn into, and it paints over the content everywhere.

## What this means for tasks 15 to 18

**Task 16's CPU lookup must reconstruct the ground's drawn surface, not a texel.** The shared
nearest-texel rule is the right *sampler*; it is not the right *reconstruction*. The rule that
measures 0.000 m is: find the ground grid cell containing the point at the frame's own
`groundCellsPerTileSide`, take the nearest-texel elevation at the cell's four corners with the
shader's exact `floor(source * interior)` rule, and interpolate across the **ground grid's own NE-SW
diagonal** (`groundGridIndices`: `(NW, SW, NE)` then `(NE, SW, SE)`). A test asserting only that "the
CPU lookup and the GPU fetch agree on a shared texel" — which is what the plan's task 16 asks for —
passes against a lookup that is still 3.049 m wrong where it is used.

**Task 17's draped `Geometry` is the only genuinely coplanar case, and it cannot be made clean by
matching alone.** Aligning the drape's lattice to the ground's and matching its triangulation is worth
a factor of four in the lift required — and the geometry grid's triangulation is the **opposite
diagonal** to the ground's today (`assembleGeometryGrid` emits `(NW, SW, SE)` then `(NW, SE, NE)`;
`groundGridIndices` emits `(NW, SW, NE)` then `(NE, SW, SE)`), so they differ by construction and
nobody wrote that down as a choice. Even matched, a lift is still needed: 1 m at zoom 11 to 13 in this
fixture, 0.01 m over relief-free ground. A lift in metres is the tuning parameter ADR 0027 refused to
bless; these numbers bound it rather than justifying it.

**The four options, each against its own number.**

- *The shared texel rule alone*, which is what ADR 0039 named. **Rejected**: 61,996/102,400 at pitch
  0, and it is not better than doing nothing (E: 53,048) by any margin that survives the camera sweep.
- *Drawing ground-relative content before the ground*. **Refuted**: 0/102,400, everywhere.
- *A lift*, which is the shader depth bias ADR 0027 rejected wearing different clothes. It **works**,
  at 1 m with the lattice and triangulation matched and 4 m without, and it is still a constant nobody
  can test the boundaries of — at zoom 11 a centimetre buys 66 per cent where at zoom 13 it buys 100.
- *Accepting the artefact*. **Rejected on ADR 0027's own evidence**: half a draped quad deleted, and
  redealt every frame as the camera moves, is the video defect ADR 0027 exists because of.

The recommendation is therefore to **avoid the coplanar copy** where the API allows it. A draped
`GROUND_RELATIVE` `Geometry` at altitude 0 is a second copy of the terrain surface, and the one arm
that is whole is the one that is not a second copy at all. A `Geometry` at any altitude a consumer
actually declares is not coplanar and none of this touches it. If the drape must exist, it needs the
lattice, the triangulation and a lift together, and its gate is a pixel count across a camera sweep
rather than an agreement test on one texel.

**Task 15's `AltitudeMode` is untouched by this.** The hazard belongs to the draw, not to the enum,
and nothing here argues against the two fields or the `ABSOLUTE` default.

**Task 18 has no coplanar hazard at all, and that is measured rather than assumed.** Both
`drawLabels` (`LabelPipeline.kt:362`) and `drawIcons` (`IconPipeline.kt:333`) call
`disable(GL_DEPTH_TEST)`. Labels are the screen regime (ADR 0034); terrain height moves a label's
anchor on screen and never meets a depth comparison. Task 18 owes accuracy, not stability.

**A `GROUND_RELATIVE` point placement is a smaller case than a drape and a differently shaped one.** A
model writes depth and has volume (ADR 0030), so it is not coplanar with anything. A map-anchored
sticker is a screen-parallel billboard carrying its anchor's single depth, and ADR 0039 already
accepts that terrain cuts it along the anchor row and records the billboard-versus-volume policy as
owed. Putting the anchor on the surface does not create that defect and does not worsen it.

## What this spike did not establish

**It measured one projection.** Mercator only. The globe ground displaces radially through
`GlobeGroundPipeline`, and a globe `Geometry` is already a CPU-projected grid with a third transform
factor of its own, so nothing here transfers to `GLOBE` without being run again.

**It measured one relief profile and one DEM encoding.** Mapbox, 256-texel interior, 9 m per texel
and 28 m per cell, exaggeration 1.5. The vertical errors of the bilinear and nearest-texel rules scale
with the relief per cell and the numbers above should be read as that profile's, not as constants.
Terrarium was not exercised.

**It did not explain the arithmetic.** The measured residual on a relief-free ground is under a
centimetre, and the decode's own `Float` error — `dot(channels, decode.xyz)` cancelling against
`-10000` — accounts for about a millimetre of it. The remaining factor of ten is not accounted for
here. It does not change any conclusion, because every conclusion above rests on a pixel count and on
the quantum probe, but it is not understood.

**It never read a depth value.** Everything is inferred from the depth test's own verdict through
colour readback, because the ground's fragment shader is production and could not be asked to encode
`gl_FragCoord.z`. The quantum probe is the closest thing to a direct depth measurement here, and it
is a bisection rather than a reading.

**It reached three drivers and two rasterisers.** Apple M3 Max through CGL, and `Apple Software
Renderer` through both CGL and EAGL. Neither llvmpipe nor any Android GPU ran it — llvmpipe is
Ubuntu CI's and this ran on a developer machine, and `androidDeviceTest` needs hardware attached.

**Nothing here is a gate.** No permanent test was added. Task 19 should build one from this fixture's
three-frame shape — content alone, ground alone, both, and count only the pixels both cover — with
the raised-50 m and sunk-50 m controls kept, because they are what caught the first fixture being
wrong.
