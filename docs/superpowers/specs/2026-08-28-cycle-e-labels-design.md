# Cycle E-labels — map text drawn from Rentile label candidates

**Design specification.** Authority for the implementation plan that follows it. Every decision below was
taken in a grilling on 2026-08-28 against measured evidence; where a decision reversed an earlier
recommendation, the evidence that reversed it is named.

## Provenance

Eight preflight documents, 6,464 lines, all written 2026-08-28:

| Document | Establishes |
|---|---|
| `docs/research/2026-08-28-e-labels-rentile-surface.md` | what Rentile hands over, and the version argument |
| `docs/research/2026-08-28-e-labels-renderer-gap.md` | what RenG must build to consume it |
| `docs/research/2026-08-28-e-labels-api-adjudication.md` | adjudicates the two above, correcting both |
| `docs/research/2026-08-28-e-labels-corpus-statistics.md` | what the 34-style corpus actually asks for, counted |
| `docs/research/2026-08-28-e-labels-060-bump-spike.md` | the version bump, built and tested |
| `docs/research/2026-08-28-e-labels-handover-spike.md` | one real handover, driven end to end |
| `docs/research/2026-08-28-owner-decisions.md` | the decision list this spec answers |
| `docs/research/2026-08-21-golden-image-gate-design.md` | the standing instruction on text and tolerance |

Read the adjudication before the two documents it adjudicates: both contain claims it overturns.

---

## 1. What this cycle delivers

Map text, drawn by RenG as screen-space primitives from Rentile's `LabelCandidate`s. Text shaping, line
breaking, glyph metrics and the atlas are Rentile's; everything that needs a camera is RenG's.

**In scope** (E2):

- Text with **halo** — 1,095 layers in 33 of 34 corpus styles. Not optional; the SDF shader does halo in v1.
- **Point and line placement** — `symbol-placement: point`, `line` and `line-center`. Line placement is 375
  layers in **31 of 34** styles.
- **Collision and priority** — `symbol-sort-key` (680 layers, 28 styles) and layer priority.
- **`text-translate`** and `translateAlignment` (81 and 60 layers).
- **Icons** at their anchor with full paint, rotation and `optional`/`overlap` semantics — 681 layers in 23
  styles, **90% of them in the same layer as their text**.
- **Sprite manifest retention**, a prerequisite for icons (§6).
- **Fade** in and out across frames (E4).
- `FramePlan.drawLabels`, fully orthogonal to `drawBasemap` (E7).

**Out of scope, by decision:**

- **`icon-text-fit`** (E2) — 110 layers in 13 styles. The first and hardest step of the icon ordering
  contract, and the one whose errors analytical readback cannot see. Those layers draw their icon
  **unfitted**; the spec records this as a known visible gap rather than skipping the icon, because a
  missing symbol reads as a bug and a wrongly-sized one reads as a style issue.
- **`text-variable-anchor`** — zero occurrences in the corpus, and Rentile does not support it.
- **Cooperative overlap** — `SymbolOverlap.COOPERATIVE` has zero occurrences. The corpus uses only `never`
  (79 text, 49 icon) and `always` (2 text, 5 icon).
- **Occlusion of labels by 3D content.** Undesigned and not attempted.
- **Legibility verification** — deferred to Cycle J (E8, §8).

---

## 2. The version, and what it bought

**Rentile is pinned at `0.6.0`, landed as its own commit before this spec** (E1). At the previous pin only
**265 of 1,220** text-bearing symbol layers (21.7%) sat on a source-layer the engine admitted, because label
layers were restricted to a ten-name place-name allowlist. `0.6.0` deletes it, unlocking `poi` (284 layers),
`transportation_name` (169 — road names), `water_name` (68), `poi_station` (48) and `road_label` (33). It
also implements the five expression operators **28 of 34** styles use inside a symbol layer.

The bump moved **six** coupled places, not the five `HANDOFF.md` documented — the sixth being 20 lines of
prose across 10 Kotlin files that no gate covers, because the fingerprint mechanism is comment-blind.

**What `0.6.0` did not fix, and this cycle inherits:** `internal/glyph/ScriptSupport.kt` is byte-identical
between the two versions. Hebrew, Arabic, the Brahmic and South-East Asian abugidas — 23 script ranges —
still produce no glyph quads. E9 exists because of this.

---

## 3. The seam

**Rentile decides** everything the style document says, evaluated per feature: which features are labels,
glyph identity and metrics (cell rect, bearings, advance, at a 24-pixel SDF em with a 3-pixel buffer inside
the cell), label-local layout with `text-anchor` and offsets already applied, the glyph atlas as PNG bytes
whose **alpha channel is the signed distance field and whose RGB is forced opaque white**, per-feature paint
(`color` and `haloColor` moved onto the candidate at `0.6.0`, so a per-layer colour cache is wrong), and the
collision *inputs* — bounding box, padding, overlap, `sortKey`, `avoidEdges`, `zOrder`, layer priority.

**RenG owns** everything that needs a camera: projecting the geographic anchor to a pixel, applying the
pixel translate, placing label-local quads relative to that anchor, **collision and priority resolution**,
**line placement in its entirety**, resolving `LabelIconRef.imageName` to sprite pixels, and the draw.

**Line placement is entirely RenG's, and this is the largest single piece of work in the cycle.** Rentile
hands over the full source line as geographic points, a tangent hint, `symbolSpacing`, `maxAngleDegrees` and
`keepUpright` — but `LabelLayout.kt` contains no reference to placement at all, so **the glyph quads are
laid out as a horizontal row for all three placement modes**. Projecting the line, walking it at
`symbolSpacing`, distributing glyphs along it and enforcing the bend ceiling is new.

---

## 4. The firewall (measured, not inferred)

`planLabelCandidates(style, tiles, resourceAccess)` acquires the label layers' **vector tiles only** and
freezes the glyph closure without fetching a byte. `LabelCandidatePlan.glyphUrls(template)` then returns the
**exact URL strings** the following acquisition will request, composed by Rentile's own substitution from
the caller's own template.

**Measured end to end** by the handover spike: `glyphUrls` returned 3 URLs across 2 font stacks and 2
codepoint blocks, the consumer's `Transport` was asked for exactly those 3, each exactly once, and planning
fetched zero glyph bytes. ADR 0016's exact-string preregistration survives intact.

The sequence is: open one operation → preregister the draw routes and the label-layer tile routes → call
`planLabelCandidates` → read `glyphUrls` → call `registerRoutes` again with them → call
`acquireLabelCandidates(plan)`. `BasemapEngineHost.registerRoutes` already exists for mid-invocation
registration, for the analogous style-route reason. **`glyphUrls` dies with `close()`**, so the URLs must be
read before the plan is closed.

**Never call the one-shot `acquireLabelCandidates(style, tiles, mode)` overload** — it plans and acquires
inside one uninterruptible call, which is precisely the window RenG needs to preregister in.

Two defects the spike found by running rather than reading, both of which this cycle must fix:

1. An unpreregistered glyph URL is refused by the **store** index, not the transport index, because
   Rentile's glyph acquirer reads its raw store first. The refusal is complete — no byte reaches the
   consumer — but surfaces as opaque `BASEMAP_RENDER_FAILED` rather than a route failure.

   **Corrected during Task 6, and the correction is the useful half.** *Which* index refuses depends on
   *why*. The store index is keyed on the **redacted** digest and the transport index on the **exact** URL —
   so an unpreregistered route is caught at the store, while a **stale credential passes the store gate and
   is refused at transport**. Measured: the consumer's `Store` is asked for all three glyph ranges, its
   `Transport` sees none, and no glyph bytes are written. The handover spike could not see this because its
   fixture carried no credential; it listed the case as unmeasured. The two refusals share one public code
   and are distinguished internally.
2. `GlyphTemplateMismatchException` and `LabelCandidatePlanClosedException` **escape RenG unwrapped**,
   because `glyphUrls` is a plan method rather than a call through `engineCall`. An engine exception type
   crossing the public boundary breaches the sanitized-failure contract.

`glyphUrls` compares only **redacted** template forms, so it cannot catch a stale credential — a caller
passing one "gets back a plausible, non-empty list whose every URL is wrong". Under the firewall that
presents as every glyph route preregistered and none matched.

---

## 5. Draw order (E3) — needs an ADR

Labels are **engine-derived**: they have no `FramePlan` entry, so they cannot be a `DrawnThingReference`,
whose two members both mean "the *n*th entry of the caller's own list". Making them a third member would
touch **27 call sites** and change what the type means; making them a **fourth scene list**, the shape
`geometries` and `groundTiles` already use, costs **zero**.

It is also forced by batching: `drawScreenStack` binds a program per element, and its KDoc's claim that
interleaving "costs a program switch and nothing else" is true for two per-element pipelines and **false for
a batch**, where a switch costs a flush.

**The frame's phase order becomes:**

```
map regime (depth tested throughout, per ADR 0027)
  1  ground              test, no write
  2  geometries          test, no write
  3  models              test AND write     (ADR 0030)
  4  map-anchored stickers   test, no write

  5  LABELS              depth test OFF     <-- new, and OUTSIDE both regimes

screen regime (no depth)
  6  consumer screen-anchored stickers, by z
```

**Labels sit between the two regimes rather than inside either.** ADR 0027 says *every* map-regime pass
enables `GL_DEPTH_TEST` and sets `glDepthMask(GL_FALSE)`, and ADR 0030 amends that for the model pass alone.
A label pass that *disables* the test would read as a third exception if labels were called part of the map
regime — so they are not. Stating membership this way is what keeps ADR 0034 from contradicting ADR 0027;
putting labels inside the map regime would require superseding 0027 rather than adding an ADR.

So a consumer's screen sticker covers a label, and a label covers a map-anchored sticker. **ADR 0024 never
answered where an engine-derived stack sits; this ADR answers it.**

---

## 6. Public API delta

`VERSION_NAME` is **frozen at `0.4.0`** until E-terrain, G and J are all complete, so additions accumulate
under one unreleased number.

| Type | Change | Why |
|---|---|---|
| `ResourceClass` | **+1** `BASEMAP_GLYPH_RANGE` | forced: the transport route index is keyed on `(url, EngineResourceClass)` and the enum correspondence is asserted **injective** by two tests, so no existing constant can be repointed |
| `FramePlan` | **+1** `drawLabels: Boolean = true` | E7, fully orthogonal to `drawBasemap` |
| `DiagnosticCode` | **+1** `LABEL_CONTENT_EXCLUDED` | E9 |
| `RenGErrorCode` | **+1** for the unroutable-source failure | E6 |
| `PipelineStage` | **+1** naming the label stage | honours `EngineFailureClassification`'s standing instruction that its three swept label codes "move out of here into shapes that name the label plan" |
| `ResourceLimits` | **none** | measured: no field needed. The adjudication broke the chain that concluded otherwise |
| `ResourceKind` | **none** | `INTERNAL_PIPELINE` already covers RenG-owned GL objects with no external locator |

Adding `BASEMAP_GLYPH_RANGE` measures at **8 lines across 6 files** and breaks 6 existing tests, one of
which is a *gain* — it flips an engine glyph failure from opaque `BASEMAP_RENDER_FAILED` to
`RESOURCE_UNAVAILABLE`. The seven-site edit list in the Rentile-surface document **misses an eighth**,
`ValueSupport.kt`'s `reportOrder`.

**Sprite manifest retention is a prerequisite, not a detail.** `spritePairIsJointlyValid` parses every
entry's geometry and returns a **`Boolean`** — the same shape F-2 found when the class-gate runner discarded
its parsed `GltfDocument`. Icons cannot resolve `imageName` to pixels until it returns something richer, and
its current shape is load-bearing for its cache-policy KDoc.

---

## 7. Cross-frame state (E4) — needs an ADR

**Collision resolves during `prepare()` and never during `draw()`.** A Prepared Frame "may be drawn
repeatedly" and "drawing never changes history". This is now guarded by an **executable test** — the
repeatability case landed with the X2 fix and was proven against an injected state leak the existing suite
does not catch.

**The only cross-frame state is fade.** Which labels are placed is recomputed from scratch every frame,
purely from `(candidates, camera, output size)`. Opacity eases toward 1 for placed labels and toward 0 for
absent ones.

The evidence that chose fade over full placement hysteresis: **`text-variable-anchor` is used zero times in
the corpus**, so every label has exactly one candidate position and there is no anchor choice to be
hysteretic about. Incumbency would add only priority inversion — a minor road name placed first blocking a
city name that pans into view — which is why neither MapLibre nor Mapbox ships it.

Fade follows `previousSelectedLod` exactly: read by planning, written on successful `prepare`, cleared by
the public `clearFrameHistory()`. `prepareBatch` needs no special rule — it is literally
`plans.map { prepare(it, accessMode) }`, so state placed where `previousSelectedLod` sits satisfies the
batch rule for free; the hazard is deviating, not conforming.

**The state is self-bounding** and needs no public ceiling: an entry is retained only while its label is
either a current candidate or still mid-fade, so the map is O(labels in view), not O(labels ever seen).

**Fade advances per successful `prepare()`, over a duration RenG owns as an internal constant.** `FramePlan`
carries `frameIndex` and no wall-clock time, and `prepare()` cannot read a clock without destroying the
determinism this whole section rests on — the repeatability guard added by the X2 fix would catch it.

The consequence is stated rather than hidden: **a fade's duration in seconds is a function of the consumer's
frame rate.** A ten-step fade is 167 ms at 60 fps and 333 ms at 30 fps. That is accepted for a cosmetic ease.

The rejected alternative was a `FramePlan.timeSeconds` field, following `AnimationTrack.timeSeconds`, where
the consumer already supplies time rather than RenG reading a clock. It is more correct and was rejected
anyway: defaulting to `0.0` means a consumer who does not thread a clock gets a fade that never advances —
labels pop exactly as if the feature were absent, silently. That is the failure mode `CLAUDE.md` names, and
frame-count fade works correctly with no consumer action at all. The duration constant is RenG's own, on the
same footing as ADR 0026's scene light: not a consumer-visible feature.

**Still owed:** a **label identity** derivation under ADR 0018. Get it wrong and fade is either useless
(identities never match, everything pops anyway) or wrong (identities collide, a label inherits another's
opacity).

---

## 8. Verification (E8)

**Gate: the analytical readback suite, plus a recorded harness pass. Legibility is declared unverified
until Cycle J**, in the spec and in the suite's own KDoc.

The fixture problem is **solved and cheap**, contrary to the renderer-gap document's assessment that it
"may cost more than the renderer": measured at **115 lines and 91 bytes**. Rentile's Wire-generated `Tile`,
`Glyphs` and `Glyph` types are public in its published KLIB ABI despite their `internal` package names, so
RenG's test sources compile against them with **no build-file change and no added dependency**.

**Target reachability, measured — and the line sits one step earlier than the handover spike reported.**
`LabelCandidateAssembler.emptyBatch` packs a 1×1 placeholder atlas through Skia, so **even a no-glyphs
style's batch cannot be read on the Android host**. The split is "everything except *reading the batch*",
not "everything except candidate geometry and atlas pixels".

So the routing half — closure exactness, both refusal paths, URL composition, preregistration rounds —
gates on `commonTest` across all four context-holding targets. Anything that touches the returned
`LabelCandidateBatch` goes in `nativeTest`.

Assertions the suite owes:

- **A lower bound** — something drew at all, measured against a no-op draw the way `ModelReadbackSuite`
  measured its six cases. Without it, upper-bound assertions pass on a blank frame.
- **Two glyph cells carrying different atlas texels** — one sample cannot distinguish a correct texture from
  one with constant coordinates.
- **Coverage bounded** by an analytically projected box.
- **Collision genuinely rejects** — two overlapping labels producing fewer pixels than either alone, with
  the survivor being the higher-priority one. The case with real discriminating power.
- **Fade**, as a three-frame A/B/A shape. A test clearing history between two frames tests the historyless
  path twice and passes with the fade code deleted.
- **Line placement** — glyph centres lying near the projected line.
- **Regime and depth** — labels draw with depth off, at phase 5, by call-log assertion.

**Vacuity traps specific to this cycle**, per the standard that has now caught twelve:

- A collision test with two labels at equal priority passes under either ordering rule. Give them different
  priorities **and** assert the specific survivor.
- The atlas is white-RGB-with-SDF-in-alpha, so premultiplication is the identity on it. A test asserting
  "uploaded unpremultiplied" by comparing RGB passes under both interpretations. **Assert the sampler**, not
  the bytes.
- **A guard that fires first masks every rule beneath it** — Cycle H's most recent catch. If the suite opens
  with a rasterisation probe or a "no symbol layers, skip" check, a mutation to placement code is masked.

**Unmeasured and owed as a task (E5):** real candidate counts per frame, per style, across zooms and
viewports. No public ceiling ships until those numbers exist — RenG has just spent a fix on two defaults
chosen without measurement that disagreed by 4×, and a wrong default is worse than an absent one.

**A prediction that is not a measurement:** glyph quads are small, so they should survive
`Apple Software Renderer` where large ground quads measurably do not. That prediction cost `0.3.0` a failed
publication once. Extend `measureLargeQuadRasterisation`'s shape to the label pass's own footprints before
writing any assertion that depends on a pixel count.

---

## 9. Failure and reporting

**E6 — an unroutable label source fails the frame.** RenG derives routes from the style, so it knows its
underivable set *before* calling. Rentile's `planLabelCandidates` calls `throwAcquisitionFailures` over the
whole batch, so one unroutable source throws the entire plan and per-layer granularity is not expressible.
The check happens **before** the call, raising a typed failure that **names the label plan** rather than
surfacing as opaque `BASEMAP_RENDER_FAILED`.

Measured: **zero** of the corpus's 1,220 text-bearing symbol layers reference a source RenG cannot route, so
this is a safety net rather than a live path.

**E9 — one aggregate diagnostic.** `DiagnosticCode.LABEL_CONTENT_EXCLUDED`, emitted once per `prepare` when
the engine reports any label exclusion. It says *some labels are missing here*; it says nothing about which
script, which layer, or anything from engine `details`, which ADR 0016 forbids forwarding because they are
free-form and can carry signed URLs. **No engine vocabulary enters RenG's public API** — that coupling is
what ADRs 0016 and 0017 exist to prevent.

---

## 10. ADRs this cycle must write

1. **Where an engine-derived stack sits** relative to the consumer's screen stack (§5). ADR 0024 left it
   open; this closes it.
2. **Fade as a fourth member of Frame History** (§7), carrying what the LOD precedent did not: the identity
   derivation and the self-bounding rule.
3. **The engine-diagnostic boundary** (§9) — the first time RenG reports something the engine told it, and
   the rule that only code and severity cross, never `details`, and never engine vocabulary.

ADR 0016 also needs a **second erratum**: its existing one reasons about why enumerating `GLYPH_RANGE`
without adopting the entry point would be wrong, and has nothing to say about the case that now exists.
One clause is already wrong — there are two `acquireLabelCandidates` overloads, and the plan-taking one is
what RenG calls.

---

## 11. What this cycle does not claim

- Labels are not verified legible. Only Cycle J can do that.
- Complex scripts produce no text and RenG cannot fix it downstream — it only stops being silent.
- `icon-text-fit` layers draw unfitted icons; the plate will be the wrong size, not absent.
- No label content is occluded by 3D scene content.
- Nothing is measured on a real mobile GPU. The mobile targets run in simulation only, per ADR 0033.
