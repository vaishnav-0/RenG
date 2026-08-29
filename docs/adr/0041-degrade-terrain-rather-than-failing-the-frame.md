# Degrade terrain rather than failing the frame

**When terrain cannot be acquired, RenG draws the ground flat and emits a diagnostic. It does not fail the
frame.** That is a deliberate divergence from every other basemap resource, which fails the frame the moment
acquisition fails, and it is confined to terrain by an argument that does not generalise.

**The default it diverges from is not a design intention nobody has tested — it has been watched.** During
Cycle G's harness pass a single ArcGIS raster tile timed out with `urlsession error -1001`, which reached
the consumer as `PREPARE FAILED RESOURCE_UNAVAILABLE at RESOURCE_LOOKUP` and cost frame 38 of a 48-frame
globe render, written out as a frame of pure clear colour rather than swallowed
(`docs/research/2026-08-29-g-harness-pass.md`). The same transport-flake class cost the 34-style corpus run
2 of 1632 frames. That behaviour is correct and stays: the frame really could not be drawn as specified, and
reporting precisely is better than a quietly wrong picture. The measurement is of the failure *mode* — one
flaky request destroying a whole frame — and the tile that timed out was a raster tile, not a DEM. What
terrain contributes is the count.

**And terrain contributes a lot of count.** Closing DEM seams needs a one-texel border from each
neighbour, so the acquired set is the visible set plus a perimeter ring: `4√T + 4` extra tiles, which is
**+45% at 93 tiles and +33% at 167** — X2's two measured worst cases for the same viewport. A frame survives
only if every request in it survives, so a third more requests moves the frame-loss rate by about a third.
Paying that to gain nothing is the part worth refusing: the picture terrain's absence produces is not a
missing picture.

**That is the principle, and it is what makes this a line rather than a slope. Terrain is the only basemap
resource whose absence has a fully defined rendering that RenG has already shipped.** There is no
style-minus-one-layer and no eighty-percent-of-a-tile; a frame that cannot get its style or its tiles has no
defined appearance and failing is the only honest answer. A frame that cannot get its DEM has one: the flat
ground RenG has drawn since `0.3.0`, which was the whole product for a release. Degrading to a state that
shipped is not the same act as inventing a fallback.

**Two failure shapes reach RenG, and they get two answers.**

**Absence.** Tiles below the source's `minimumZoom` or outside its `bounds` are dropped by Rentile through
`mapNotNull` — no diagnostic, no exception, a shorter list — and `TerrainSourceDescriptor` carries
`sourceId`, `encoding`, `tileSizePx`, `minimumZoom` and `maximumZoom` and **no `bounds`**, so RenG cannot
predict which tiles will vanish. It can only observe them afterwards. Those tiles draw flat, and one
diagnostic per frame names the count. The mechanism that makes this observable at all is that results are
matched by `requestedTile`, **never by list index or by assuming the returned list is as long as the
requested one** — index matching against a short list shifts every tile after the first gap, which turns a
missing picture into a wrong one, and a wrong one that looks entirely plausible.

**Error.** One failing DEM tile fails the whole call — Rentile's `throwAcquisitionFailures` offers no
per-tile degradation — and RenG's own contract forbids retries and repairs. So the whole ground draws flat
and one diagnostic says so. Under ADR 0039 that frame's ground also writes no depth, because its ground is
not displaced, which means **a wholly degraded frame is byte-identical to a terrain-off frame** rather than
merely similar to one. That is a test, not a hope, and it is the strongest thing this decision can be held
to.

**Two `DiagnosticCode` constants, `TERRAIN_COVERAGE_INCOMPLETE` and `TERRAIN_UNAVAILABLE`, each emitted at
most once per frame.** Once per frame and not once per tile, on exactly ADR 0036's reasoning about aggregate
label exclusions: a per-tile count is internal volume crossing a public boundary, and a channel that emits
forty entries for one ordinary frame teaches a consumer to stop reading it. The coverage code carries the
count of tiles that drew flat, because a consumer's response to two missing tiles and to a hundred is
different. Neither is a failure — the frame prepared and it drew — so both are diagnostics rather than
typed exceptions, and the ABI dump moves by exactly these two lines.

**Four costs, and the first is the one that will be used to reopen this decision.** Flat ground where
terrain was expected **masquerades as real land**: a plateau that is genuinely flat and a coverage gap that
draws flat are the same pixels, and only the diagnostic distinguishes them. A consumer who reads no
diagnostics gets a plausible, wrong map instead of a loud failure. That is precisely what failing the frame
would have prevented, and it is the price of not losing a third more frames to transport flakes.

Second, a flat tile beside a displaced one produces a **cliff at the shared edge** — the coverage boundary
is visible, and reads as a rendering artifact rather than as an absence. The seam machinery cannot help
here, because there is no neighbouring data to agree with. Task 13's harness pass is told to look at exactly
this and report what it sees.

Third, ground-relative content over a gap sits at sea level, because `GROUND_RELATIVE` resolves against
whatever ground the frame has (ADR 0040).

Fourth, a source whose `minimumZoom` sits above the frame's zoom drops **everything, every frame**, so
`TERRAIN_COVERAGE_INCOMPLETE` becomes a steady state at low zoom rather than an event. That is accepted
because it is true and because RenG has no way to say it more cheaply — the descriptor exposes the zoom
range, but not the bounds that produce the other half of the same silence, so predicting the drop is not
available even in principle.

**This opens no general licence to degrade.** The style, raster tiles, sprites, glyph ranges, GLBs and
consumer textures all still fail the frame, and none of them acquires a claim on this reasoning by
resembling terrain. A second degrading resource is a new decision made under this ADR's actual rule —
**absence has a defined picture RenG has already drawn** — and not an application of a precedent already
set.
