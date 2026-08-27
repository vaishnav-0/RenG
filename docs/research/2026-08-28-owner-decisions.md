# Decisions waiting for the owner — E-labels and G

Assembled 2026-08-28 overnight against `main` at `f997e1a`, from eight preflight and spike documents plus
direct measurement. **Everything answerable from code, tests, or measurement was answered; nothing here was
guessed.** This file is the residue: the decisions that are genuinely the owner's because they are
preferences, trade-offs, or authorizations rather than facts.

Read Part 1 first — it retires several questions the research documents left open, so the decision list is
shorter than those documents imply.

**Supporting documents, all written this session:**

| Document | What it establishes |
|---|---|
| `2026-08-28-e-labels-rentile-surface.md` | what Rentile hands over, and the version argument |
| `2026-08-28-e-labels-renderer-gap.md` | what RenG must build to consume it |
| `2026-08-28-e-labels-api-adjudication.md` | the two above disagree; this adjudicates, and corrects both |
| `2026-08-28-e-labels-corpus-statistics.md` | what the 34 styles actually ask for, counted |
| `2026-08-28-e-labels-060-bump-spike.md` | the version bump, built and tested rather than reasoned |
| `2026-08-28-g-globe-projection-seam.md` | RenG's own projection seam and what a second mode costs |
| `2026-08-28-g-globe-prior-art.md` | how shipping globes actually work, sourced and graded |
| `2026-08-28-g-globe-rentile-tiles.md` | the supply side: tiles, seams, counts, budgets |

---

# Part 1 — What the night settled, so it is not re-litigated

**The Rentile `0.6.0` bump is safe, measured rather than inferred.** All locally runnable gates pass in a
scratch worktree: 138 Python tests, repository policy, `checkKotlinAbi`, every cross-target compile, the
AAR, all seven local publications, and a six-target consumer compile. Test counts from Gradle's own JUnit
XML are **1,123 / 1,159 / 1,145** with 0 failures, 0 errors, 0 skips — identical to Cycle H's baseline, so
nothing moved by a single test. `kmp/api/kmp.klib.api` is unchanged. The bump is an 8-line diff across 3
files.

**The bump's one named regression risk is not present in the corpus.** `0.6.0` tightens `==`/`!=` typing,
and in a fill or line layer a rejected construct fails the whole style. Across all 34 styles there are
2,824 modern-form comparisons and **zero** with statically heterogeneous operand types. Independently,
Rentile's own corpus run produced non-trivial mosaics for all 34 style ids one minute before the `0.6.0`
release commit was cut.

**The provenance gap is closed, for both versions, for the first time.** Rentile publishes sources jars;
all 36 checked-in `commonMain` files in published `0.6.0` are byte-identical to the release commit, and the
same check closes the never-closed `0.5.0` gap.

**The firewall problem that made labels "a cycle and not a task" is already solved** in the version RenG
pins. `planLabelCandidates` freezes the glyph closure without fetching a byte; `glyphUrls` returns the exact
URLs the acquisition will request. ADR 0016's exact-string preregistration survives intact, and
`BasemapEngineHost.registerRoutes` already exists for mid-invocation registration.

**The public ABI cost is one enum entry minimum, four entries and one `Int` likely** — materially less than
either research strand concluded. `ResourceClass` must grow because the firewall's transport route index is
keyed on `(url, EngineResourceClass)` and the enum correspondence is asserted injective by two tests, so no
existing constant can be repointed. `ResourceLimits` does **not** grow; nor do `FramePlan`, `ResourceKind`
or `Diagnostic`.

**`prepareBatch` imposes no extra requirement.** It is `plans.map { prepare(it, accessMode) }` — label state
placed where `previousSelectedLod` sits satisfies the batch rule for free.

**Two features can be cut from scope on evidence, not taste.** `SymbolOverlap.COOPERATIVE` is used **zero**
times across the corpus, and `text-variable-anchor` **zero** times (and Rentile does not support it).

**Rentile's tiles already align exactly** — measured across its shipped nine-tile mosaic, cross-seam
differences sit inside interior-column noise. Any globe seam would be RenG's defect.

**Rentile has no globe notion at all**, discarding a style's `projection` key outright.

**The glyph closure holds exactly — measured end to end, not read.** A spike drove one real handover
through RenG's own firewall at the pinned version: `plan.glyphUrls(template)` returned 3 URLs (2 font
stacks × 2 codepoint blocks, deliberately asymmetric), the consumer's `Transport` was then asked for
exactly those 3, each exactly once, and `planLabelCandidates` fetched **zero** glyph bytes. The
second, mid-invocation `registerRoutes` round is all the firewall needs. This was the cycle's central
claim and it is now measured rather than inferred.

**The fixture problem is not a problem.** The renderer-gap strand called it "the finding most likely to
change the cycle's task list — the fixture may cost more than the renderer", reasoning that RenG owns no
MVT or protobuf encoder and that `ModelFixtureBuilder`'s 400+ lines were the precedent. Measured: **115
lines and 91 bytes** for a hand-rolled protobuf writer plus one MVT layer and one glyph range. And there is
a **zero-cost route nobody had noticed** — Rentile's Wire-generated `Tile`, `Glyphs` and `Glyph` types are
public in its published KLIB ABI despite their `internal` package names, so RenG's test sources compile and
run against them on both macOS and the Android host with **no build-file change and no added dependency**.
The spike used it to cross-validate the hand-encoded bytes.

**A label suite can gate most of itself on `commonTest`.** Measured: `macosArm64Test` 4/4,
`iosSimulatorArm64Test` 4/4, `testAndroidHostTest` **3/4** — and the single Android host failure is the
atlas case on Skia's missing host library, which fails *after* every glyph range is fetched. So the
routing half — closure exactness, refusal, URL composition — gates across all four context-holding targets;
only candidate geometry and atlas pixels need `nativeTest`. Better than the strand feared, which assumed
the whole suite might be excluded.

**Adding the `ResourceClass` constant costs 8 lines across 6 files and moves exactly one ABI line**, needs
no `ResourceLimits` field, and breaks 6 existing tests — one of which is a *gain*, flipping an engine glyph
failure from opaque `BASEMAP_RENDER_FAILED` to `RESOURCE_UNAVAILABLE`.

**Two defects the cycle must fix, found by running it rather than reading it.** An unpreregistered glyph
URL is refused by the **store** index rather than the transport index — Rentile's glyph acquirer reads its
raw store first — so the refusal is complete (no byte reaches the consumer) but surfaces as opaque
`BASEMAP_RENDER_FAILED`, not the `AMBIGUOUS_RESOURCE_ROUTE` the preflight predicted. And
`GlyphTemplateMismatchException` and `LabelCandidatePlanClosedException` **escape RenG unwrapped**, because
`glyphUrls` is a plan method rather than a call through `engineCall` — which is a direct breach of RenG's
sanitized-failure contract, since an engine exception type crosses the public boundary. Both are forced
work, not decisions.

---

# Part 2 — Decisions

Each carries a recommendation. Where the cost of being wrong is asymmetric, that is said.

## E-labels

### E1. Bump the Rentile pin to `0.6.0`?

**Recommended: yes, as its own commit before the E-labels design is written.**

At the pinned version, only **21.7%** of the corpus's 1,220 text layers render — the rest sit on
source-layers outside a ten-name place-name allowlist. `0.6.0` unlocks `poi` (284 layers),
`transportation_name` (169 — road names), `water_name` (68), `road_label` (33). The median style renders
about a sixth of its own text today; one style renders 4.5%. Separately, **28 of 34** styles use an
expression operator inside a symbol layer that `0.5.0` cannot compile.

Building on `0.5.0` would also write the cycle against three data classes `0.6.0` then breaks.

**Cost of the bump:** an 8-line diff, all gates green, no ABI movement. One consequence: `PreparedStyle.digest`
changes, so every derived tile key is a new value and any consumer's rendered-tile cache goes cold once.

**One thing to fix while doing it:** the bump spike found a **sixth coupled place** no gate covers — 20 lines
of prose across 10 Kotlin files naming the old version and commit, including a `RentileKeyDerivation.kt`
KDoc that makes a false claim about the file beside it. The fingerprint mechanism is comment-blind, so a
bump passes green with all 20 left wrong.

### E2. What does "labels" mean in this cycle? **This is the largest decision here.**

The corpus, counted:

| feature | layers | styles |
|---|---:|---:|
| text | 1,220 | 33 / 34 |
| text halo | 1,095 | 33 / 34 |
| line / line-center placement | 375 | **31 / 34** |
| icons | 681 | **23 / 34** |
| …icons paired with text in the same layer | 611 (90%) | 21 / 34 |
| `icon-text-fit` | 110 | 13 / 34 |

**Recommended: text with halo, point *and* line placement; icons deferred to a follow-on.**

Halo is not optional — 33 of 34 styles use it, so the SDF shader must do it in v1. Line placement is in 31
of 34 styles and is what makes a map look labelled rather than annotated; deferring it means no road carries
its name.

Icons are the real question, and the honest framing differs from the research documents'. They treat icons
as a separable feature. The corpus says **90% of icon layers are the same layer as their text** — so
deferring icons does not split the corpus into a working part and a missing part. It renders 611 layers as
text floating where a symbol should have sat beside it, in 21 of 34 styles. That may still be right for a
first cycle, but it is not free, and it is worth deciding deliberately rather than by omission.

### E3. Where do labels sit in the draw order?

ADR 0024 settles map-regime-first, screen-regime-on-top, and calls the screen regime "a single ordered
stack". It says nothing about a third, **engine-derived** stack, and labels are exactly that — they have no
`FramePlan` entry to be indexed by.

- **(a) Above the consumer's screen stack.** Map text always wins.
- **(b) Below the consumer's screen stack.** Consumer content always wins.
- **(c) Last phase of the *map* regime — depth test off, after map-anchored stickers, before the screen
  regime.** *(Not raised by either research document.)*

**Recommended: (c).** It keeps ADR 0024's sentence literally true, keeps consumer screen stickers on top
where a consumer expects them, and puts labels above the content they annotate. ADR 0030 already gives the
map regime three depth phases; this is a fourth, depth-off one. It also costs **zero** of the 27 call sites
that making labels a third `DrawnThingReference` would touch, because it is the shape `geometries` and
`groundTiles` already use.

This needs an ADR either way.

### E4. Does label placement carry cross-frame state?

- **Option 1 — none.** Collision recomputed from scratch each frame. Purity contract untouched. **Labels
  flicker as the camera moves** — the exact failure analytical readback cannot report and the visual harness
  exists to catch.
- **Option 2 — full hysteresis**, as a fourth member of Frame History, following `previousSelectedLod`
  exactly: read by planning, written on successful `prepare`, cleared by the public `clearFrameHistory()`.
- **Option 4 — fade only.** Opacity animates; *which* labels are placed is recomputed each frame.

**Recommended: Option 2**, framed as extending **Frame History** rather than inventing a concept. The
precedent is real and already ships: `previousSelectedLod` is cross-frame state that visibly changes pixels
and was one of the four defects the visual harness caught.

**Forced regardless of choice:** collision resolves during `prepare()`, never during `draw()`, because a
Prepared Frame "may be drawn repeatedly" and "drawing never changes history". Worth knowing that this is
currently guarded by **no executable test** — `ModelReadbackSuite` case 4 looks like it tests draw
repeatability but calls `prepare()` fresh each time, so it compares two separately prepared frames.

### E5. If E4 is Option 2 — what bounds the placement memory?

Placement memory is O(labels) and content-addressed, where `previousSelectedLod` is one `Int`. RenG's house
rule for every other unbounded thing is an explicit ceiling that **fails closed**. But label collision
exists precisely to *drop* content, which is the opposite of `Tile Budget`'s "RenG never drops required
tiles".

**Recommended: a count ceiling on `RendererConfiguration` (where `maximumBasemapTileInstances` already
lives), evicting least-recently-seen rather than failing.** Failing a frame because too many labels were
remembered would be a worse outcome than forgetting the oldest. This breaks the house rule and the spec
should say so out loud.

### E6. A label layer whose source RenG could not derive a route for

RenG *defers* an underivable source rather than rejecting the style. At `0.6.0` a label layer can reference
a deferred source; Rentile will plan against it, RenG will have no route, and every label tile fails closed.
Rentile's own `LABEL_SOURCE_UNAVAILABLE` covers the mirror case, so the two failure sets differ.

**Recommended: skip that layer's labels and continue.** Rejecting the whole style over one unlabelable layer
is disproportionate; failing the frame is worse. But this is a degradation policy and degradation policies
are the owner's.

### E7. Does `drawBasemap = false` also suppress labels?

**Recommended: yes**, and with no new `FramePlan` field. Labels come from the basemap style, which
`CONTEXT.md` says is fixed for the renderer and never named by a Frame Plan. One sentence in the spec, no
ABI.

### E8. What is E-labels' gate?

`docs/decomposition.md` says *"labels legible and collision-free over a moving camera."* Analytical readback
can prove collision-free. **It cannot prove legible** — antialiasing quality, halo contrast, SDF iso-line
sharpness are all "how it looks" questions deferred to Cycle J.

**Recommended:** replace the gate with analytical readback plus an explicit harness look, and say plainly in
the spec that legibility is unverified until J. See **X1**, which offers a better answer.

### E9. Should RenG forward Rentile's diagnostics?

Largely dissolves if E1 is taken — the motivating case was 28 of 34 styles silently losing text to
uncompilable operators, and `0.6.0` compiles all five. It remains true that RenG reads no engine
diagnostics at all, so *any* future engine-side degradation is invisible to a consumer.

**Recommended: defer.** Not this cycle's problem once the pin moves.

### E10. Fixture strategy for the label test suite — **settled, no decision needed**

Resolved by running it. See Part 1's "The fixture problem is not a problem". Nothing here needs the owner.

## G — globe

### G1. What does `zoom` mean on a sphere? **Decide this first; everything else follows.**

RenG's LOD rule rests on `screenPixelsPerTexel = 2^(zoom−lod)`, an identity that holds only because a
mercator tile's output-pixel width is latitude-independent. On a sphere it fails by `1/cos²φ`.

Modelled against RenG's own camera arithmetic: 960×540 at latitude 82, zoom 6 goes from **4 tiles to 132 —
33×**. A phone viewport at latitude 82, zoom 8 goes from 24 to 686. Under a latitude-matched convention
(`z_eff = zoom − log₂ cos φ`) both collapse back to unchanged, peaking at 4× around zoom 3–5.

Prior art is split: MapLibre scales the globe by `1/cos(lat)`; Mapbox matches mercator at a fixed 45°. Both
have user-visible consequences and apologetic in-source comments.

**Recommended: the latitude-matched convention.** The naive one lands squarely in the silent thrash band of
**X2**.

### G2. Does the globe ground write depth?

Both web renderers clip the far hemisphere by overwriting `gl_Position.z` — a trick available only because
their depth buffer holds layer-ordering slices rather than perspective depth. It broke twice in the field
(Android drivers ordering `glDepthRange` and clipping differently; and against 3D content needing real Z).
CesiumJS has neither problem because its globe **writes depth**.

RenG's model pass has a real depth test under ADR 0030, so the web trick is unavailable. **If the globe
ground does not write depth, a model on the far side of the planet draws through it.**

**Recommended: the globe ground writes depth**, which reopens ADR 0027 for the ground exactly as ADR 0030
reopened it for models. Needs an ADR.

### G3. Globe at all zooms, or a mercator transition at high zoom?

Both shipping renderers transition. **Recommended: globe at all zooms for v1**, because the measured
sagitta of a frame-sized quad is 0.44 logical pixels at zoom 10 — the globe is already indistinguishable
from a plane there, so a transition buys little and costs a blend. Revisit if precision (**G4**) says
otherwise.

### G4. The consumer shader contract under a globe — the sharpest problem in the cycle

ADR 0008 is a **published** contract across three releases: a consumer supplies GLSL, RenG binds documented
names and substitutes nothing else. Under mercator the consumer writes `gl_Position = uMvp * aPosition`.
**A mercator→sphere map is nonlinear, so it cannot be expressed as a matrix.**

- **(a) RenG pre-projects vertices on the CPU** into globe space, leaving a linear view-projection matrix.
  The consumer's shader is **unchanged** and ADR 0008 survives intact. Costs per-frame CPU projection of a
  subdivided quad. Available because a `Geometry`'s four corners are always coplanar, so subdividing is
  output-neutral under mercator — RenG can subdivide in both modes and keep one contract.
- **(b) Inject a `projectTile` prelude** into consumer source, as MapLibre does. Breaks ADR 0008's
  "self-contained but for the version directive" promise.

**Recommended: (a).** RenG's own passes can project in-shader; consumer geometries need not use the same
technique, and preserving a published contract is worth CPU cycles on a handful of quads. Two narrow breaks
remain for an ADR 0008 erratum: `gl_VertexID`, and `aPosition.z`, which is pure altitude under mercator and
altitude-minus-curvature on a globe.

### G5. Is Mali worth measuring before G, and how?

GLSL ES grants trigonometric functions **undefined precision** — the spec is explicit, while specifying
`exp` at 3+2|x| ULP. Mercator→sphere is nothing but trigonometry. Three projects were bitten, most sharply
MapLibre on **Mali-G610/G710 with 200–300 m latitude error**. The surviving fix removes `atan`/`sin`/`cos`
from the latitude path entirely via tangent half-angle identities.

**RenG's only Android device evidence is Adreno — the family that was fine.**

**Recommended: yes, and in RenG's existing idiom.** MapLibre shipped a runtime `atan`-error probe, which is
a direct precedent for RenG's "measure the driver rather than name it" discipline that
`measureLargeQuadRasterisation` already established. But it needs a Mali device, which this project does not
have. **Owner input needed: is one obtainable?**

## Cross-cycle

### X1. Move Cycle J's mechanism earlier — before E-terrain and G?

Three consecutive cycles are specified to gate on golden baselines, and J delivers them **after** all three:
E-labels' "legible" (**E8**), E-terrain's "golden baselines with terrain", G's "golden baselines at both
projection modes".

The golden-image design spike found that RenG's corpus needs **no new CI job and no new publish step** — its
fixtures are in-source, its transport is a fake, it needs no network, and both workflows already run the
tasks it would live in, *before* upload, by construction.

**Recommended: land J's mechanism early — a small baseline harness plus one case — then grow the corpus per
cycle.** It is cheap, it is already positioned correctly in both workflows, and it gives three cycles the
gate they were specified to have. That spike's own §5.7 item 6 is a standing instruction to E-labels:
*"Text is not covered because there is none… false the moment labels ship. The tolerance must be re-argued
then, not inherited."*

### X2. Two public defaults contradict each other by 4×, today

`maximumBasemapTileInstances` defaults to **512** (range to 4096), declaring 512-tile frames legal.
`maximumResidentGpuTextureBytes` defaults to **128 MiB**, and a 512×512 RGBA8 tile is exactly 1 MiB — so
residency is exactly **128 tiles**.

Leased textures are correctly exempt from eviction, so a 129–512 tile frame draws fine, drops its excess the
moment the lease releases, and re-decodes and re-uploads it next frame. `evictOverBudget` emits **nothing** —
no exception, no diagnostic. Three regimes: ≤128 fits; **129–512 thrashes silently**; >512 fails cleanly.

Whether a pitched mercator camera reaches 129 tiles **today** is unmeasured, and I will not assert it
without a number — but the design plainly anticipates frames well past 128, since that is what the sibling
default permits. Under G's naive `zoom` convention (**G1**) it is reached easily.

**Recommended: treat as a live defect now rather than a G problem.** At minimum a diagnostic when the
working set exceeds the budget; ideally defaults that agree.

### X3. Release `0.4.0`?

Declared, gated and ready. `main` is 81 commits ahead of `origin/main`. Pushing is outward-facing and needs
explicit approval.

---

# Part 3 — Still unmeasured, and what would settle each

- **RenG's own composite path at `0.6.0`.** The bump is green on tests and Rentile's corpus renders all 34
  styles, but no RenG *harness* run has been done at `0.6.0`. **Settles it:** a harness pass over a few
  styles at the bumped pin. Needs a style URL, which is the owner's.
- **Whether mercator reaches 129 tiles today** (**X2**). **Settles it:** instrument
  `selectBasemapTiles` across a pitched, large-viewport camera sweep.
- **Mali** (**G5**). No device.
- **What a *real-viewport* label handover contains** — candidate counts, glyph range counts and atlas size
  for an actual style at an actual camera. The spike measured a synthetic fixture (3 ranges, 2 font stacks),
  which proves the mechanism but says nothing about scale. Rentile's own migration document records a worst
  case of 159 ranges in one plan, whose 8192×4357 atlas is 136 MiB decoded, so the scale question is real.
  **Settles it:** a harness run against one style, printing the plan's closure size and atlas dimensions.
  Needs a style URL.
- **Whether glyph-sized quads survive `Apple Software Renderer`.** Ground tiles measurably do not, which
  cost `0.3.0` a failed publication. Glyph quads are small so they *should* — that is a prediction.
  **Settles it:** extend `measureLargeQuadRasterisation`'s shape to the label pass's own footprints before
  writing any assertion that depends on a pixel count.
- **Draw repeatability** is guarded by no executable test (**E4**). **Settles it:** a readback case that
  draws one `PreparedFrame` twice and asserts bit-identity.
