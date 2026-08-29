# Cycle G — the `Float` precision error, seen rather than predicted

Cycle G left one measured debt and one open owner decision: the globe-fixed formulation evaluates in
`Float` on the GPU, and a spike derived its positional error analytically as **0.008 logical pixels at zoom
10, 0.979 at 17, 2.557 at 18 and 45.8 at 22**. Those numbers came out of arithmetic. This is the first time
the thing has been looked at.

## The instrument, and why Mercator is the right control

A globe frame and a Mercator frame at the same camera should agree at high zoom: the sagitta of a
frame-sized quad is 27.9 logical pixels at zoom 4, 1.75 at zoom 8 and **0.44 at zoom 10**, falling further
above that. So above about zoom 12 the two projections are drawing the same picture, and **everything that
differs is precision** — Mercator does not pay this cost because it rebases per tile in `Double`.

Two things had to be held still before that comparison meant anything.

**The camera.** The storyboard drifts north-east, turns three quarters of a turn and pitches to 55 degrees
across its sweep. At zoom 22 the drift alone is over a kilometre, and a pitched globe shows a horizon a
Mercator frame cannot have. A first attempt without freezing these measured a divergence of 70 pixels at
frame 44 that had nothing to do with precision. `--static-camera` holds latitude, longitude, bearing and
pitch so that zoom is the only thing moving.

**The instrument itself.** A whole-frame alignment search found **nothing** at zoom 22 — best shift zero.
That is not absence of error; it is the wrong measurement. `globeFixedToCameraRelative` subtracts the
radius on the up axis, so **the camera's own anchor maps to the origin exactly** and the error is zero
there by construction. It is a radial distortion, and only a *local* search sees it.

## What it measures

Local horizontal shift of the globe frame against the Mercator frame, by region, on a static camera over
style 59:

| zoom | left edge | centre | right edge |
|---|---|---|---|
| 20.21 | +3 | **0** | −6 |
| 20.72 | −5 | **0** | −7 |
| 21.23 | +5 | **0** | −7 |
| 21.49 | −3 | **0** | +18 |
| 21.74 | −24 | **0** | **+47** |
| 22.00 | −21 | **0** | **+50** |

The centre is zero at every zoom, which is the construction being visible. The edge figure reaches **+50
pixels against the spike's predicted 45.8**, and the spread across the frame at zoom 22 is **71 pixels**.

**Below zoom 21 this instrument reads zero, and that is a limit of the instrument rather than a result.**
It searches integer pixel shifts, so the predicted 0.979 px at zoom 17 and 2.557 at 18 are beneath it. What
can be said is that the error is invisible to a pixel-aligned search until about zoom 21.5 and then arrives
very fast — consistent with an error doubling every zoom level.

## One artifact ruled out, which would have made a convincing and wrong illustration

At zoom 22 the globe shows a hard vertical discontinuity at screen centre, brown on one side and grey on
the other. It looks exactly like a projection tearing. **Mercator shows the identical seam**, so it is a
boundary in the ArcGIS World_Imagery source between differently-sourced tiles, and nothing to do with
RenG.

## The videos

Both sweep zoom 19 → 22 over 48 frames on a static camera, style 59, in `build/cycle-g-videos/`.

- **`precision-overlay.mp4`** — the globe's luminance in the red channel and Mercator's in green and blue,
  so the frame is neutral grey wherever the two agree and carries red/cyan fringing wherever they do not.
  At zoom 19 the frame is grey with faint fringes on high-contrast edges; by 21.9 the map-anchored pin at
  the centre is still perfectly neutral while the right half is visibly doubled. That contrast — an exact
  centre and a smeared edge in the same frame — is the whole shape of the defect in one picture.
- **`precision-side-by-side.mp4`** — globe and Mercator at 1920x540, for reading the two directly.

**A raw amplified difference video was built first and thrown away.** The mean absolute difference is
roughly constant across the sweep (15.2 at zoom 19, 17.1 at 21.9) because the imagery at these zooms is
smooth, so a small displacement differs everywhere and a large one differs no more. Difference measures
*disagreement*; the defect is *displacement*, and only an overlay shows displacement as displacement.

## What this does not settle

The decision is still the owner's, and this narrows it rather than making it. The error is invisible below
about zoom 20 on real imagery and severe by 22. The options are unchanged: a Mercator handover at high
zoom, a per-tile rebasing of the globe path, or accepting it. **No tuned constant ships either way**, and
nothing here is a gate — these are developer measurements, taken with a tool that is not in CI.
