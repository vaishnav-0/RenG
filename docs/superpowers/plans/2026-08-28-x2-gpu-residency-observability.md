# X2 — make GPU residency observable, and stop the report lying

Not a cycle. A defect fix, instructed by the owner ahead of E-labels and G, plus two unguarded-contract
gaps found in the same area. Authority is `docs/research/2026-08-28-tile-residency-headroom.md` and
`docs/research/2026-08-28-owner-decisions.md` **X2**.

## The defect, measured

`RendererConfiguration.maximumBasemapTileInstances` defaults to **512** (`Renderer.kt:24`), declaring a
512-tile frame legal. `ResourceLimits.maximumResidentGpuTextureBytes` defaults to **128 MiB**
(`Resources.kt:90`), and a 512×512 RGBA8 tile is exactly 1,048,576 bytes — so residency is exactly **128
tiles**. Leased textures are correctly exempt from eviction, so a frame between the two draws fine, drops
its excess when the lease releases, and re-decodes and re-uploads it next frame.

A **3840×2160 display at pitch 0** reaches **167 tiles** — 39 into that band with the camera level. LOD
hysteresis is what does it: the same viewport peaks at 93 with no LOD history and 167 with one frame of it,
so the default budget sits between RenG's own two worst cases.

Nothing can observe it. `evictOverBudget` emits nothing; no `DiagnosticCode` concerns residency; and
`ResidentCache.toReportEntry` hardcodes `knownGpuBytes = 0L, hasUnknownGpuBytes = false`
(`ResidentCache.kt:234-235`), so `queryResources` **affirmatively reports zero GPU bytes while 167 MiB are
resident**.

`ResourceUsage` was designed for honesty and the implementation ignores it. Its own `init` says
`require(knownGpuBytes != null || hasUnknownGpuBytes)` — "unknown GPU bytes must be declared when known GPU
bytes are absent" (`ResourceReports.kt:44-46`). `null` + `true` is how the type says *I do not know*. The
current code instead asserts positive knowledge of zero, which passes the `require` and states a falsehood.

## Scope

**In:** tasks 1, 2, 3, 4, 5 below.

**Task 3 was initially deferred to the owner and then decided by them mid-execution: raise
`maximumResidentGpuTextureBytes` to 512 MiB to match.** The pair now agrees at 512 tiles instead of
disagreeing by 4×. Lowering `maximumBasemapTileInstances` to 128 instead was rejected because a 4K pitch-0
camera reaches 167 tiles and would then **fail closed on a frame it currently draws**, which is worse than
thrashing. The change is low-risk because the budget is an *eviction threshold, not a reservation* — a
higher ceiling allocates nothing until frames genuinely need it.

The field's KDoc argues the two limits are "deliberately independent … deriving one from the other would
hide the real cost from whoever configures it". That reasoning survives and the sentence stays: the
*fields* remain independent, and only the *default* is sized so the *default* tile ceiling cannot thrash.

A construction-time consistency check was considered and **rejected** even after the defaults were
reconciled: it would fire for any consumer who raises the tile ceiling without raising the budget, which is
a legitimate configuration, and the runtime diagnostic in task 2 already covers the case that matters.

---

## Task 1 — `queryResources` tells the truth about GPU bytes

`GlObjectRegistry` keys `textureByteSizes` by `ResourceKey`, the same key report entries use, so the real
figure is reachable — it is simply never asked for.

A three-way rule, because the current code is not always wrong:

| key state | `knownGpuBytes` | `hasUnknownGpuBytes` |
|---|---|---|
| has a budget-tracked texture | its byte size | `false` |
| has live GL objects but no tracked bytes | `null` | `true` |
| has no live GL objects | `0L` | `false` |

The middle row is the one that matters and is not merely pedantic: `registerTexture` has exactly **one**
call site (basemap tiles), while stickers, geometry textures, model textures and model buffers all take the
unbudgeted `register` path. Those bytes are genuinely unknown to this layer, and a consumer raising the
limit to cover their models is today adjusting a number that does not count them. Saying so is the point.

- Add read-only accessors to `GlObjectRegistry` — do not expose the mutable maps.
- `ResidentCache.report` takes a lookup rather than reaching for a registry, keeping the cache free of GL
  knowledge. Its existing `totalUsage()` aggregation already handles `null` correctly
  (`knownGpuBytes ?: 0L`, `hasUnknownGpuBytes = any { … }`) and should not need changing — **verify that
  rather than assuming it**.
- Wire at `RenGRenderer.queryResources` (`:1058-1065`).

**Tests:** one per row of that table, plus an aggregate mixing all three — the aggregate is the one that
catches a total that silently drops unknown entries.

## Task 2 — a diagnostic when residency cannot be brought under budget

`evictOverBudget` (`GlObjectRegistry.kt:276-288`) loops while over budget and stops when it runs out of
unleased candidates. Reaching that second exit with the total still over budget is exactly the thrash
condition, and it is currently silent.

- One new `DiagnosticCode` constant. **This is a public ABI addition** — `DiagnosticCode` has three
  constants today; expect `kmp/api/kmp.klib.api` to move by one line and treat that as intended.
- `evictOverBudget` must not acquire a diagnostic sink — it is called from GL-side bookkeeping. Return the
  post-eviction state and let the caller, which already has the sink, emit.
- Emit at most once per frame, not per texture: a 167-tile frame must not produce 39 diagnostics.

**Tests:** fires when the working set genuinely exceeds the budget; does **not** fire at exactly the budget
(the off-by-one is the whole risk); does not fire when eviction succeeds; fires once, not N times.

## Task 4 — close a vacuous test guard

`ResourcesTest.resourceClassesMapToTheirLimitAcceptValuesAndReportOrder` (`:132-156`) iterates its own
11-row `expected` list rather than `ResourceClass.entries`, so a twelfth constant gets **no** byte-ceiling,
accept-header or report-order coverage from the test that exists to provide exactly that.

Narrower than "goes undetected" — `BasemapEngineHostTest`, `RentileKeyDerivationTest` and
`ResourceOperationOrdinaryCommitTest` all partition `ResourceClass.entries` and would catch a missing
constant structurally. The gap is its *mapping*, not its existence.

One line: assert `expected.size == ResourceClass.entries.size` before the loop. E-labels adds a twelfth
constant, so this should land ahead of it either way.

## Task 5 — guard draw repeatability, which nothing currently does

`CONTEXT.md` says a Prepared Frame "may be drawn repeatedly" and "drawing never changes history".
`ModelReadbackSuite` case 4 looks like it guards this and does not: `render()` calls `prepare()` fresh each
time (`:465-471`), so it compares two separately *prepared* frames. The contract has no executable guard.

Add a readback case that prepares **once** and draws **twice**, asserting bit-identity.

This is load-bearing beyond hygiene: decision **E4** turns on collision resolving in `prepare()` and never
in `draw()`, and that argument currently rests on prose alone.

**Vacuity check, per the standard:** confirm the new case *fails* against a deliberately broken build —
a draw path mutating state between draws — exactly as `ModelReadbackSuite` measured its six cases by making
`drawModels` a no-op. A repeatability assertion that cannot fail is the purest form of the trap this
project keeps finding.

---

## Gates

Full local set from `CLAUDE.md`: Python suite, repository policy, `checkKotlinAbi`, Android host,
`macosArm64Test`, `iosSimulatorArm64Test`, both Linux compiles, the AAR. Counts from Gradle's JUnit XML,
not scrollback. Baseline is **1,123 / 1,159 / 1,145**, 0 failures.

`kmp/api/kmp.klib.api` is expected to move by exactly one line (task 2's diagnostic constant). Any other
movement is a defect.

## Execution

Tasks 1+2 share files and run as one unit. Tasks 4+5 are disjoint from them and from each other and run in
parallel. No worktrees: the file sets do not overlap.
