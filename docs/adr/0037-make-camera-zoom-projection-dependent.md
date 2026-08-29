# Make `Camera.zoom` projection-dependent

**Under `GLOBE`, RenG scales the globe by `1 / cos(latitude)`, so everything RenG derives from zoom is
derived from `z_eff = zoom − log₂ cos(latitude)` rather than from the field's own value.** `Camera.zoom`
keeps its type, its `[0, 22]` range, its name, and exactly the number the consumer set. What changes is what
that number means on screen: the same zoom shows a different ground scale, and therefore selects a different
basemap LOD, in the two projection modes. A public field whose meaning depends on a sibling field is the
kind of thing a future reader takes for a bug, so it is recorded here as a decision rather than left in the
arithmetic.

**The identity that forces it.** RenG's LOD rule rests on `screenPixelsPerTexel = 2^(zoom − selectedLod)`
(`internal/planning/MercatorLod.kt:11-27`), and that identity holds only because a mercator tile's
*output-pixel* width does not depend on latitude — mercator inflates the ground and the tile by the same
factor, so they cancel. On a sphere they do not cancel: a tile at latitude φ covers a ground square `cos φ`
the side of an equatorial one, so a globe showing the same on-screen ground scale needs `1/cos²φ` more of
them. Modelled against RenG's own camera arithmetic in `docs/research/2026-08-28-g-globe-rentile-tiles.md` —
a transcription validated by reproducing `FramePlanningCoreTest.kt:285`'s asserted count exactly, so the
model is checked against the planner rather than against a whiteboard:

| camera | mercator | naive globe | latitude-matched |
|---|---:|---:|---:|
| 960×540, latitude 82, zoom 6 | 4 tiles | **132** | 4 |
| 1179×2556 phone, latitude 82, zoom 8 | 24 tiles | **686** | 24 |

Under the chosen convention both collapse back to their mercator counts, and the peak cost of a globe frame
is about **4×** a mercator one, around zoom 3–5, where 24 tiles becomes 96.

**686 is past the end of the road rather than merely expensive, and that asymmetry is the whole argument.**
`RendererConfiguration.maximumBasemapTileInstances` defaults to 512 (`Renderer.kt:24`), so the naive reading
does not make a high-latitude phone frame slow — it makes the frame **fail closed** with a resource-limit
failure, at a latitude a consumer reaches by panning. Consistent tile cost was therefore chosen over
consistent zoom semantics: one convention makes the map look different between modes, and the other makes
frames stop rendering. Those are not comparable costs, and the choice does not need a tie-break beyond that.

**The accepted cost, stated plainly rather than discovered.** A consumer who toggles `projectionMode` on an
unchanged `Camera` sees the map jump scale, and the jump grows with distance from the equator. A consumer
who pans north at constant `zoom` raises `z_eff`, which raises the selected LOD, which asks the engine for
finer tiles — so a style rule gated on zoom switches on or off during a pan the consumer thinks is
scale-preserving. MapLibre ships exactly this convention — `getGlobeRadiusPixels(worldSize, lat) = worldSize
/ (2π) / cos(lat)`, with the source comment *"We want zoom levels to be consistent between globe and flat
views… For this reason we scale the globe up when map center is nearer to the poles"*
(`src/geo/projection/globe_utils.ts:83-89`) — and its users filed the consequence as
[maplibre-gl-js#5471](https://github.com/maplibre/maplibre-gl-js/issues/5471), still open: country labels
disappear as the camera pans toward the poles, because the effective zoom climbs past the rule that draws
them. The reporter's expectation, *"the map style should remain consistent across latitude when the globe is
the same size in the window"*, is reasonable and is not what this convention delivers. RenG draws labels of
its own now (ADR 0034), so that report describes RenG's future and not only MapLibre's present.

**One thing that deliberately does not move is frame identity.** A Frame Plan's identity digests the plan
and never the picture (ADR 0018), and the plan carries `Camera.zoom`, not `z_eff`. Two frames with the same
camera hash the same in both modes, exactly as two globe frames differing only in longitude winding do.

**The rejected alternative is Mapbox's, and it deserves the space because it is the moderate-sounding
choice.** Mapbox matches mercator's scale at one fixed latitude: `GLOBE_SCALE_MATCH_LATITUDE = 45`, with an
unusually candid comment — *"The choice of latitude is a bit arbitrary. Different choices will match
mercator more closely in different views. 45 is a good enough choice because: it's half way from the pole to
the equator; matches most middle latitudes reasonably well; biases towards increasing size rather than
decreasing… Changing this value will change how large a globe is rendered and could affect end users"*
(`src/geo/projection/globe_constants.ts:8-21`). What it buys is a zoom that means one thing everywhere: no
scale jump on a pan, no zoom-gated style rule flickering with latitude, and a single number a consumer can
reason about. What it costs is that the match is exact at exactly one latitude and wrong at every other one
in both directions — the equator renders `1/cos 45° ≈ 1.41×` larger than mercator at the same zoom — and,
decisively, that it does not touch the growth at all. Dividing by a constant multiplies the tile count by
`cos² 45° = ½` at *every* latitude, so the `1/cos²φ` divergence toward the pole survives intact and the
fail-closed frame simply returns a few degrees further north. **Mapbox can afford that because its globe
does not exist where the divergence bites**: it transitions to mercator between `GLOBE_ZOOM_THRESHOLD_MIN =
5` and `GLOBE_ZOOM_THRESHOLD_MAX = 6`, so the convention only ever runs at zooms whose tile counts are small
anyway. RenG's globe runs at every zoom by decision, and inherits the fixed match's weakness without
inheriting the transition that hides it. Taking Mapbox's constant here would be taking half of a design.

**Per-tile LOD was rejected as out of proportion rather than as wrong.** It is the answer that removes the
dilemma instead of trading it off: each tile picks its own LOD from its own latitude, tile cost stays
bounded, and `zoom` keeps one meaning in both modes. But `LodObservation(selectedLod: Int)`
(`MercatorLod.kt:5`) commits RenG to one LOD per frame **in its type**, and that type is threaded through
ordered preparation (`internal/preparation/OrderedPreparationProtocol.kt:45`), the frame history that LOD
hysteresis reads back, and both label identity and label fade (`internal/label/LabelIdentity.kt:36`,
`internal/label/LabelFade.kt:101`), besides every test that asserts a single selected LOD. That is a change
to what the planner *returns*, made in the cycle that first draws a sphere. It also stays available forever,
because none of it is public: adopting per-tile LOD in a later cycle would make `zoom` mean the same thing
in both modes again, which is a compatible change in the direction a consumer would welcome. This ADR is not
a commitment against it.

**What pins the convention is a count, and the fixture must not sit at the symmetry point.** The two
conventions — naive and latitude-matched — agree exactly at the equator, which is the latitude a tile-count
fixture naturally gets written at. A case that fails without this decision is a tile count taken at a
non-trivial latitude; anything written at latitude 0 passes under either reading and proves nothing.

## Erratum, 2026-08-29: `z_eff` is the scale exponent, and the LOD rule is untouched

This ADR's accepted-cost paragraph says a consumer panning north at constant `zoom` "raises `z_eff`, which
raises the selected LOD, which asks the engine for finer tiles". **That does not happen, and implementing it
would have been a defect rather than a cost.** Found while implementing the convention and verified
independently.

`z_eff` is the **scale exponent** — it sizes the sphere, whose equatorial circumference is
`512 · 2^z_eff` logical pixels, which is Mercator's world size grown by `1/cos φ`. It is not an input to LOD
selection. On a sphere a Mercator tile at latitude φ covers a ground square only `cos φ` the side of an
equatorial one, so a tile's *on-screen* size is

```
512 · 2^(z_eff − lod) · cos φ  =  512 · 2^(zoom − lod)
```

**The two latitude factors cancel exactly**, at the camera's own latitude — which is the latitude at which
one per-frame LOD is chosen. So `screenPixelsPerTexel` is the same function of `zoom` in both modes, and
`observeMercatorLod` needs no globe arm at all. Verified across latitudes 0 through 85.0511 and several
zoom/LOD pairs; the identity is exact rather than approximate.

**Feeding `z_eff` to `observeMercatorLod` is the reading this formula invites, and it is worse than the
naive convention it replaces** — LOD 9 instead of 6, and 154 modelled tiles against the naive scale's 136.
The trap is named in `MercatorLod.kt`'s KDoc so the next reader meets it before writing it.

Two consequences follow. **A style rule gated on zoom does not flicker during a pan**, because the selected
LOD does not move with latitude — the paragraph above claimed otherwise. And **the on-screen ground scale at
the view centre is identical in both projections**, so "the map jumps on toggle" is true away from the
centre and at any zoom where curvature reads, and *false at the centre*. `CONTEXT.md`'s **Camera** entry
carries the precise form.

What survives unaltered is this ADR's decision and its central argument: the convention is latitude-matched,
the naive alternative makes high-latitude frames **fail closed** rather than render slowly, and `Camera.zoom`
remains projection-dependent in what it shows.

---

## Erratum, 2026-08-29 — the high-zoom transition is decided, and the answer is no transition

Section 5 of the cycle's design deferred whether the globe should hand over to Mercator at high zoom.
Task 7 then measured that **subdivision cost is not what argues for it**: granularity derived from the
camera halves every second zoom level to a single quad above zoom 11, so the expense the transition was
meant to avoid does not arise. What remained was precision. The globe-fixed formulation evaluates in
`Float` on the GPU, where Mercator rebases per tile in `Double`.

That was then measured on screen rather than left as arithmetic
(`docs/research/2026-08-29-g-float-precision-measured.md`). Against a Mercator control at the same static
camera — a fair control above about zoom 12, where the sagitta has fallen under half a pixel and the two
projections draw the same picture — the globe's local displacement is **zero at the frame centre at every
zoom**, because `globeFixedToCameraRelative` subtracts the radius on the up axis and the anchor maps to the
origin exactly. It reaches **+50 logical pixels at the frame edge at zoom 22**, against the spike's
predicted 45.8, spreading 71 pixels across the frame. It is invisible to a pixel-aligned search below about
zoom 21.5.

**The owner's decision, on seeing it: the error is acceptable and RenG does not transition.** No Mercator
handover at high zoom, and no per-tile rebasing of the globe path. The globe stays one formulation at every
zoom, which is the property that makes it simple to reason about, and the cost is a smear at the frame edge
in the last zoom level or so of the supported range.

Reversing this is compatible and cheap if it ever matters: a handover is additive, and nothing in the
public API forecloses it. What must not happen quietly is the *third* option — shipping a tuned constant
that transitions at some hand-picked zoom without measuring it — which this record exists to prevent.
