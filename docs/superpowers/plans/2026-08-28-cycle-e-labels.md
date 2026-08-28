# Cycle E-labels — implementation plan

Authority: `docs/superpowers/specs/2026-08-28-cycle-e-labels-design.md`. That spec carries the reasoning
and the evidence; this plan carries the sequence, the per-task obligations, and what each task owes the
next. Where they disagree the spec wins.

Ledger: `.superpowers/sdd/2026-08-28-cycle-e-labels/progress.md`.

## Standing obligations for every task

- **Every task ends green.** Python suite, `check_repository_policy.py`, `checkKotlinAbi`,
  `:kmp:testAndroidHostTest`, `:kmp:macosArm64Test`, `:kmp:iosSimulatorArm64Test`. Counts summed from
  Gradle's JUnit XML, never from scrollback. Baseline entering the cycle is **1,134 / 1,174 / 1,160**.
- **`VERSION_NAME` stays `0.4.0`.** Frozen until E-terrain, G and J complete. Do not touch it.
- **Nothing is pushed.** `publish.yml` cuts a release on any non-documentation push to `main`, and a
  published coordinate is immutable under ADR 0013.
- **Typed failures carry redacted diagnostics.** Never forward a message, cause or `details` from an
  injected adapter or from the engine — they can carry signed URLs.
- **Never call the one-shot `acquireLabelCandidates(style, tiles, mode)` overload.** It plans and acquires
  inside one uninterruptible call, closing the window RenG must preregister in.
- **Assume the next vacuous check exists.** Twelve have been found across F-2, H and X2, every one at a
  symmetry point. Each task that adds a test says, in its report, what it broke to prove the test fails.
- **Style documents carry the owner's API key.** None is checked in; none is pasted into any report.

---

## Wave 1 — foundations, fully parallel

### Task 1 — `ResourceClass.BASEMAP_GLYPH_RANGE` and its routing

The twelfth constant. Measured at **8 lines across 6 files**; the spec's §6 names the sites and warns that
the Rentile-surface document's seven-site list **misses an eighth**, `ValueSupport.kt`'s `reportOrder`.

Also: `BasemapStyleManifest` reads `sprite` and `sources` but **not `glyphs`** — one line, reusing
`resolveHttpReference`, which is byte-for-byte the branch Rentile takes.

Breaks 6 existing tests. **One of those breaks is a gain** — it flips an engine glyph failure from opaque
`BASEMAP_RENDER_FAILED` to `RESOURCE_UNAVAILABLE`. Keep the gain; do not paper over it.

ABI: +1 line. Nothing else may move.

### Task 2 — retain the parsed sprite manifest

`spritePairIsJointlyValid` parses every entry's `x`/`y`/`width`/`height`/`pixelRatio` and returns a
**`Boolean`** — the same discard F-2 found in the class-gate runner. Return the parsed manifest instead.

Its current shape is load-bearing for its cache-policy KDoc; update that KDoc rather than leaving it
describing a function that no longer exists. Icons (task 12) block on this.

### Task 3 — forward projection

`(latitude, longitude, altitude) -> (pixelX, pixelY, w)`, in `internal/projection`, in `Double`, on the CPU.
**Nothing in `commonMain` does this today** — verified exhaustively. RenG can cast a pixel *to* the ground
and can build an MVP matrix, but the perspective divide and viewport transform exist only on the GPU.

`w` matters as much as the pixel: the projection matrix's bottom row is `(0, 0, -1, 0)`, so `w = -z_view`
and **its sign is the behind-camera test**. A label behind the camera or beyond the horizon must be rejected
before it is boxed.

Its own task with its own unit tests, because a wrong viewport transform is invisible in a rendered frame
until labels are already in the wrong place. Collision (task 8) and line placement (task 11) both depend on
it.

**Vacuity warning:** a fixture at the viewport centre, at zoom integer, pitch 0, latitude 0 is symmetric in
every axis and will not discriminate a transposed transform. Make every fixture asymmetric in both axes.

### Task 4 — `FramePlan.drawLabels`

`drawLabels: Boolean = true`, **fully orthogonal** to `drawBasemap` (E7). All four combinations legal,
including `drawBasemap = false, drawLabels = true`.

`drawBasemap` appears in 19 files, 67 occurrences, exactly one of which is the canonical encoder — follow it
as the template, and note the encoder is the one that must not be missed.

**Consequence for later tasks:** tile selection must run when `drawLabels` is true even if `drawBasemap` is
false, because `planLabelCandidates` takes its own tile list. Label tile selection and ground tile selection
become independently reachable.

### Task 5 — the three ADRs

Written before the code they govern, not after.

1. **Where an engine-derived stack sits** relative to the consumer's screen stack. ADR 0024 fixes
   map-then-screen and calls the screen regime "a single ordered stack"; it never contemplated a third,
   engine-derived one. Records the phase order of §5 and why labels are a fourth scene list rather than a
   third `DrawnThingReference` — 27 call sites, and the type would stop meaning "the *n*th entry of the
   caller's list".
2. **Fade as a fourth member of Frame History.** Carries what the `previousSelectedLod` precedent did not:
   the identity derivation under ADR 0018, and the self-bounding rule. Must state in as many words that
   collision resolves during `prepare()` and never during `draw()`, because that is exactly what a later
   implementer would "simplify".
3. **The engine-diagnostic boundary.** The first time RenG reports something the engine told it. Only code
   and severity cross; never `details`; never engine vocabulary in RenG's public API.

Plus a **second erratum on ADR 0016**. Its existing one reasons about why enumerating `GLYPH_RANGE` without
adopting the entry point would be wrong, and says nothing about the case that now exists. One clause is
already false: there are two `acquireLabelCandidates` overloads and the plan-taking one is what RenG calls.

---

## Wave 2 — the handover and the GPU seam

### Task 6 — the label handover through the firewall

Depends on task 1.

Open one operation → preregister draw routes and label-tile routes → `planLabelCandidates` → read
`glyphUrls` → `registerRoutes` again → `acquireLabelCandidates(plan)`.

**`glyphUrls` dies with `close()`.** Read the URLs before closing; the plan is otherwise reusable and
acquiring from it repeatedly yields equal batches.

Fix the two defects the handover spike found by running rather than reading:

- An unpreregistered glyph URL is refused by the **store** index, not the transport index, and surfaces as
  opaque `BASEMAP_RENDER_FAILED`. Give it a name.
- `GlyphTemplateMismatchException` and `LabelCandidatePlanClosedException` **escape unwrapped**, because
  `glyphUrls` is a plan method rather than a call through `engineCall`. An engine exception type crossing
  the public boundary breaches the sanitized-failure contract.

`glyphUrls` compares only **redacted** template forms, so it cannot catch a stale credential — that presents
as every glyph route preregistered and none matched. Handle it as a named failure, not a mystery.

### Task 7 — glyph atlas upload and residency

The atlas is PNG bytes whose **alpha channel is the SDF and whose RGB is forced opaque white**.

Neither sampler default is right: `IMAGE` premultiplies, `DATA` uses `GL_NEAREST`. An SDF wants
unpremultiplied bytes **and** linear filtering. `uploadTexture` already takes an explicit sampler that
overrides the default — `GlTextureUpload`'s own KDoc names this as "the intended additive fix". **No new
API.**

**Vacuity trap, stated in the spec and repeated here because it is subtle:** with white RGB, premultiplying
by alpha turns RGB into a copy of the alpha channel and destroys nothing the shader reads. A test asserting
"uploaded unpremultiplied" by comparing RGB **passes under both interpretations**. Assert the *sampler* —
read `glTexParameteri` from the call log, or read back a texel between two glyph cells where linear and
nearest differ.

Residency through `registerTexture`, which enrols it in the byte-budgeted LRU. Note the budget is now
512 MiB and tiles are its only other tenant.

### Task 8 — the label pipeline: SDF shader and quad batch

**Nothing in RenG batches anything.** Every existing draw is one call per logical object, and the sticker
pipeline's single reused unit quad with a per-object MVP uniform is the precedent labels must **not**
follow — it is one uniform upload and one draw call per quad.

New: a CPU-side vertex assembly that walks the frame's surviving labels and writes position, UV, colour and
halo per glyph corner into one array, then issues **one draw**.

**The GL seam needs nothing new.** `bufferData` already accepts a null payload for orphaning, and F-2's
`bindJointPalette` already runs a respecify-per-frame buffer through `bufferSubData` — that is the
precedent, **not** `uploadModelPrimitive`, which is `GL_STATIC_DRAW` and cached by key. Adding an entry
point costs four platform bindings plus the recording fake plus the roster test; do not.

Halo is mandatory — 33 of 34 styles. Whether halo-on and halo-off are separate programs is an
implementation call; F-2's `ModelShaderVariant` is the precedent if they are.

---

## Wave 3 — placement

### Task 9 — point placement, collision and priority

Depends on tasks 3 and 8.

Project each anchor, apply the pixel translate, place label-local quads. Then collision: `sortKey` and layer
priority ascending, larger wins; `boundingBox` expanded by `padding`; `overlap` in its two used states
(`never`, `always`) — **cooperative is out of scope, zero corpus occurrences**.

`translateAlignment` decides whether the pixel translate rotates with the map or stays viewport-fixed; 60
layers across 12 styles use it, so it is not hypothetical.

**Label collision is the first thing in RenG that exists to *drop* content.** Every other budget fails
preparation rather than dropping — `Tile Budget`'s rule is "RenG never drops required tiles". Say so
explicitly in the code, because it reads as a violation of the house rule otherwise.

**No public ceiling** (E5). Task 17 measures instead.

### Task 10 — scene integration and draw order

Labels as a **fourth scene list**, outside both order lists, at **phase 5** — after map-anchored stickers,
before the screen regime, depth test off. The shape `geometries` and `groundTiles` already use; costs zero
of the 27 `DrawnThingReference` call sites.

`Scene`'s bijection counts stickers and models only and must keep doing so.

### Task 11 — line placement

**The largest single piece of work in the cycle.** Rentile hands over the full source line as geographic
points, a tangent hint, `symbolSpacing`, `maxAngleDegrees` and `keepUpright` — and lays the glyph quads out
as a **horizontal row for all three placement modes**. `LabelLayout.kt` contains no reference to placement
at all.

RenG projects the line, walks it at `symbolSpacing`, distributes glyphs along it, enforces the bend ceiling,
and honours `keepUpright`. 375 layers across **31 of 34** styles.

---

## Wave 4 — icons, fade, reporting

### Task 12 — icons

Depends on task 2. Resolve `LabelIconRef.imageName` against the retained sprite manifest.

Ordering is load-bearing and easy to get wrong: **anchor, then offset, then rotate, then translate**, in the
declared frame. `icon-text-fit` is **out of scope** — those 110 layers draw their icon **unfitted**, which
the spec records as a known visible gap rather than a skipped icon.

Paint, `rotationDegrees`, `padding`, `optional`, `overlap`, `ignorePlacement`, `rotationAlignment`,
`pitchAlignment`, `avoidEdges` are in. 90% of icon layers also carry text, so icon and text placement
interact within one layer.

### Task 13 — fade

Depends on task 9. A fourth member of Frame History, following `previousSelectedLod` exactly: read by
planning, written on successful `prepare`, cleared by `clearFrameHistory()`.

**Self-bounding** — retain an entry only while its label is a current candidate or still mid-fade.

The **label identity** derivation under ADR 0018 is the hard part and the failure is silent both ways:
identities that never match make fade useless (everything pops anyway); identities that collide make a label
inherit another's opacity.

`prepareBatch` needs no special handling — it is `plans.map { prepare(it, accessMode) }`.

### Task 14 — failure and diagnostics

`RenGErrorCode` +1 for the unroutable-source failure, `PipelineStage` +1 naming the label stage, and the
`(code, stage)` allowlist entries `RenGException`'s `init` enforces.

This honours `EngineFailureClassification`'s standing instruction that its three swept label codes "move out
of here into shapes that name the label plan".

`DiagnosticCode.LABEL_CONTENT_EXCLUDED`, emitted **once per `prepare`** when the engine reports any label
exclusion. Code and severity only. **No engine vocabulary in RenG's public API.**

### Task 15 — the unroutable-source check

RenG derives routes from the style, so it knows its underivable set **before** calling. Rentile's
`throwAcquisitionFailures` covers the whole batch, so one unroutable source throws the entire plan and
per-layer granularity is not expressible.

Check before the call; fail the frame with the typed failure from task 14. Measured: **zero** corpus layers
hit this, so it is a safety net — but it must not surface as opaque `BASEMAP_RENDER_FAILED`.

---

## Wave 5 — verification

### Task 16 — the label readback suite

Fixture cost is **measured at 115 lines and 91 bytes**. Rentile's Wire-generated `Tile`, `Glyphs` and
`Glyph` types are public in its published KLIB ABI, so RenG's tests compile against them with **no
build-file change and no added dependency** — use them to cross-validate hand-encoded bytes.

Placement: routing assertions in `commonTest` (all four context-holding targets); candidate geometry and
atlas pixels in `nativeTest`, because the Android host JVM cannot load Rentile's Skia.

The seven assertions the spec lists, and the three vacuity traps it names. **The lower bound comes first**:
measure the suite against a no-op label draw and report how many cases fail. If they all pass, the suite is
upper-bound-only and worthless.

### Task 17 — measure candidate counts (E5)

Real candidate counts per frame, per style, across zooms and viewports. **No public ceiling ships until
these numbers exist.** Output is a research document, not a limit.

### Task 18 — the glyph-quad rasterisation probe

Glyph quads are small, so they *should* survive `Apple Software Renderer` where large ground quads
measurably do not. **That is a prediction, and the same class of prediction cost `0.3.0` a failed
publication.** Extend `measureLargeQuadRasterisation`'s shape to the label pass's own footprints and read
the number **before** any assertion depending on a pixel count is written.

### Task 19 — the harness pass

E8's gate. Run the visual harness over the corpus, look at the output, and record what was seen. The harness
found four defects the passing suite did not in E-basemap; this is the cycle whose output is meant to be
*read*, so this is not optional.

Record in the ledger that **legibility is unverified until Cycle J** — in the spec, in the suite's KDoc, and
in the cycle's completion record.

---

## Closing obligations

- `CLAUDE.md` and `HANDOFF.md` updated with what shipped, what it costs, and both limits of it.
- `CONTEXT.md` gains the label vocabulary, with `_Avoid_:` lists.
- `docs/decomposition.md`'s E-labels row updated — its gate says "legible", which this cycle does not claim.
- The ABI dump's delta is exactly five enum entries and one `FramePlan` parameter. Anything else is a defect.
