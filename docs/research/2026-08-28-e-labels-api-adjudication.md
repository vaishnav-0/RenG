# E-labels: adjudicating the public ABI, priced against source

**Date:** 2026-08-28. **Branch read:** `main`, HEAD `f997e1a` ("docs: withdraw Cycle I, keeping ffmpeg as the
harness assembly step"), working tree clean but for the two sibling research documents, which are untracked.
**Nothing was changed. No branch, no commit, no Kotlin touched.**

Both sibling documents were written against `6730722`. HEAD is one commit later and that commit is
documentation only (`git show --stat f997e1a` touches no `kmp/src`), so every source citation in either
document is still addressed to the right tree. Where a line number has moved anyway, it is because the
document cited it loosely, and that is called out below rather than silently corrected.

This document answers the questions the two siblings leave open **that source can answer**, and is
scrupulous about which it cannot. Every claim carries a `file:line` or a command and its output. Where the
answer is "I could not determine this," the closing section says what would settle it and frames it as a
decision rather than a research gap.

---

## 0. The adjudication, first

### The contradiction

`docs/research/2026-08-28-e-labels-renderer-gap.md` §5 is headed **"`ResourceClass` — no addition needed,
and adding one would be wrong"** and its summary says *"labels can be drawn with no public ABI addition at
all."* Its body then makes the claim conditional: whether `ResourceClass` grows *"is decided entirely by
whether the handover lets RenG enumerate the glyph URLs before the call — a Rentile-surface question,
answered in the sibling document."* The sibling answers **yes**.

### The resolution

**The sibling is right, the renderer-gap headline is inverted, and neither document states the decisive
reason.** The reason is not that enumerating URLs is *possible*; it is that the firewall's route index is
**keyed on a RenG `ResourceClass` translated into a Rentile one**, and no existing RenG constant translates
to `GLYPH_RANGE`. Three lines settle it:

```kotlin
// OperationRegistry.kt:767 — the transport index key is Rentile's own class
private data class TransportIndexKey(val url: String, val engineClass: EngineResourceClass)

// OperationRegistry.kt:325 — lookup uses the class Rentile stamps on its own request
val route = routeIndex.transportRoutes[TransportIndexKey(request.url, request.resourceClass)]
    ?: throw ambiguousRouteFailure()

// OperationRegistry.kt:787-788 — population goes the other way, through RenG's enum
private fun engineTransportClassFor(resourceClass: ResourceClass): EngineResourceClass? =
    if (resourceClass == ResourceClass.BASEMAP_STYLE) EngineResourceClass.STYLE
    else engineKeyedResourceClassOf(resourceClass)
```

`preregister` builds the index at `OperationRegistry.kt:259-261` by calling `engineTransportClassFor` on
each declared route. Rentile's glyph acquirer will call `execute(request)` with
`request.resourceClass == GLYPH_RANGE` (sibling §4, `GlyphResourceAcquirer.kt:133`). For that lookup to
hit, **some** RenG `ResourceClass` must map to `EngineResourceClass.GLYPH_RANGE` — and
`engineKeyedResourceClassOf` (`RentileKeyDerivation.kt:94-108`) maps eleven RenG constants onto seven
Rentile ones plus `null`, with `GLYPH_RANGE` reachable from none of them.

Nor can an existing constant be quietly repointed. The correspondence is asserted **injective** in two
places — `RentileKeyDerivationTest.kt:327-331` ("the engine-to-RenG resource class correspondence must stay
injective") and `BasemapEngineHostTest.kt:859-870` ("moving a class between these namespaces makes one
consumer resource answer to two keys") — so aliasing `BASEMAP_GEO_JSON` onto `GLYPH_RANGE` would break
GeoJSON's own routing and fail both tests.

**Therefore: the moment RenG routes one glyph byte, a new public `ResourceClass` constant is mechanically
forced.** Not preferred, not idiomatic — forced by the route index's key type. The renderer-gap document's
headline claim survives only in the counterfactual where RenG never calls `acquireLabelCandidates`, which
is not a live option for a cycle whose subject is drawing map text.

**A second correction, in the renderer-gap document's favour.** Its §5 quotes ADR 0016's erratum
(`docs/adr/0016-…:78-86`) as saying adding the row "would be exactly the failure the respike was run to rule
out." That quotation is accurate, but the erratum's own sentence carries its escape clause: the failure it
names is *"enumerating a class without adopting the entry point that reaches it."* E-labels adopts the entry
point. The erratum does not forbid the addition; it forbids the addition **without** the adoption. Read
whole, the two documents do not actually disagree about ADR 0016 — only about which branch of its
conditional the cycle is on.

### The exact ABI delta

| Public type | Moves? | Why | ABI lines |
|---|---|---|---|
| `ResourceClass` | **Yes, +1** | Forced by the route-index key type, above | +1 `enum entry` |
| `ResourceKind` | **No, by default** | The atlas has no `ResourceLocator`, so it cannot be an `EXTERNAL` key; `INTERNAL_PIPELINE` already covers RenG-owned GL objects. A decision, not a derivation — see Q1 | 0, or +1 if decided |
| `RenGErrorCode` | **+1, probably** | `GLYPH_TEMPLATE_MISMATCH` has no honest existing code. Not mechanically forced — see Q3 | +1 `enum entry` |
| `PipelineStage` | **+1, if the standing instruction is honoured** | `EngineFailureClassification.kt:132-138` is that instruction. Not mechanically forced | +1 `enum entry` |
| `ResourceLimits` | **No** | A count ceiling is the right shape and this is the wrong home — see Q2 | 0 |
| `RendererConfiguration` | **+1 `Int`, if a label ceiling is wanted** | Where every count ceiling already lives, and three ABI lines rather than five | +1 ctor param, +2 lines |
| `FramePlan` | **No** | No `drawLabels` — see Q6 | 0 |
| `Diagnostic` | **No** | `fieldName` validates against an internal enum invisible to the ABI | 0 |
| `DiagnosticCode` | **+1, only if engine-diagnostic forwarding lands** | And that motivation largely evaporates at Rentile `0.6.0` — see Q8 | 0 or +1 |

**Minimum forced delta: one enum entry.** **Likely delta: four enum entries and one `Int` constructor
parameter**, none of which touches a `componentN` or a `copy`. That is a materially smaller and cheaper
delta than "a twelfth `ResourceLimits` field," which is what the sibling's chain of reasoning arrives at.

---

## 1. Field by field against `kmp/api/kmp.klib.api`

### `ResourceClass` — moves, +1 entry

Eleven constants (`Resources.kt:39-51`), eleven `enum entry` lines at `kmp/api/kmp.klib.api:136-146`. A
twelfth adds exactly one line, inserted alphabetically. Enum entries are additive: no existing symbol's
mangled name changes.

**The mechanical fallout is seven else-less production `when`s, not six.** The sibling document lists six
edit sites plus the constant. A structural scan of every `when` block over `ResourceClass` in production
source sets finds **seven exhaustive else-less ones**:

| Site | Function | In sibling's list? |
|---|---|---|
| `internal/ValueSupport.kt:53-67` | `maximumBytesFor` | yes |
| `internal/ValueSupport.kt:70-84` | `acceptValue` | yes |
| **`internal/ValueSupport.kt:87-99`** | **`reportOrder`** | **no — missed** |
| `internal/firewall/RentileKeyDerivation.kt:95-108` | `engineKeyedResourceClassOf` | yes |
| `internal/identity/ResourceKeyDerivation.kt:257-269` | `wireValue` | yes |
| `internal/planning/FramePlanningCore.kt:262-276` | `isStaticDirect` | yes |
| `internal/resource/ResourceOperationProtocol.kt:641-657` | `ordinaryResourceClassGates` | yes |

`ResourceClass.reportOrder` (`ValueSupport.kt:86-99`) orders `queryResources`' report
(`ResourceReports.kt:105`). It is a compile error without a new arm, and it is a **public-behaviour**
decision, not a formality: it decides where glyph rows sort in a consumer's `ResourceReport`.

Three further `when`s mention `ResourceClass` and do **not** break: `OperationRegistry.kt:624-638`,
`:648-652` and `:839-843` all carry `else` arms. Two more —
`internal/basemap/BasemapStyleManifest.kt:1030-1035` and `ResourceOperationProtocol.kt:720-723` — are
`when`s over *other* enums (`BasemapSourceKind`, `SpriteMember`) that merely return `ResourceClass` values.

One of the `else`-bearing sites deserves a design sentence rather than an edit.
`passesClassSpecificReadValidation` (`OperationRegistry.kt:623-638`) ends `else -> true`, so a glyph record
read back from the consumer's Store gets no RenG-side validation. Its KDoc (`:629-637`) explains why that is
correct rather than lazy: only the sprite pair is terminal, because every other acquirer re-parses on a
store hit and self-heals. Rentile's glyph acquirer does exactly that — digest mismatch → `removeStore` →
refetch, non-terminal (sibling §4, `GlyphResourceAcquirer.kt:105-113`). **So the `else` is the right answer
for glyph, and the cycle should say so out loud rather than leave a reader to wonder whether a branch was
forgotten.**

**Forced test edits, five sites, four of which fail loudly:**

| Test | What breaks | Loud? |
|---|---|---|
| `ResourcesTest.kt:~48-62` | ordered literal list vs `ResourceClass.entries` | yes |
| `RentileKeyDerivationTest.kt:42-47` | `engineKeyedClasses.keys + ownIdentityClasses` must exhaust `ResourceClass.entries` | yes |
| `EngineFailureClassificationTest.kt:317-334` | asserts the unmapped set is **exactly** `[GLYPH_RANGE]`; routing it makes the set empty | yes |
| `ResourceOperationOrdinaryCommitTest.kt:44` | `ResourceClass.entries.toSet() - UNGATED_CLASSES == ORDINARY_CLASS_GATES.keys`; the new class must join `UNGATED_CLASSES` (`:989-998`) | yes |
| `ResourcesTest.kt:132-156` | the limit / accept / reportOrder table | **no — see below** |

`EngineFailureClassificationTest.kt:342-354` (`failsClosedOnAnEngineResourceClassRenGDoesNotRoute`) does not
break, but its *meaning* inverts: it currently proves that a `GLYPH_RANGE` acquisition or decode failure
becomes an unnamed `BASEMAP_RENDER_FAILED`. Once RenG routes the class, that is no longer the desired
behaviour and the test becomes a statement about a case that should not arise. **The sibling's list contains
neither of these two `EngineFailureClassificationTest` sites.**

**A vacuity finding, in the F-2 / Cycle-H house style.**
`ResourcesTest.resourceClassesMapToTheirLimitAcceptValuesAndReportOrder` (`:132-156`) iterates its own
eleven-row `expected` list and asserts `assertEquals(index, resourceClass.reportOrder)` where `index` is
the row's position **in `expected`**. Nothing in that test compares `expected.size` against
`ResourceClass.entries.size` — the only `ResourceClass.entries` reference in the file is at `:61`, in a
different test. So a twelfth constant absent from `expected` is **not detected here**: the loop still runs
eleven rows and passes. The compiler forces the three `when` arms to exist; nothing forces them to be
*right*, or forces this table to know the class exists. **The fixture is derived from the same list it
checks — precisely the symmetry-point shape F-2 caught seven of.** Adding
`assertEquals(ResourceClass.entries.size, expected.size)` costs one line and closes it, and that line should
land in this cycle whether or not the class does.

A second, milder one: `ResourceOperationLookupTest.everyResourceClassUsesItsFixedAcceptValue` (`:283-298`)
asserts `assertEquals(resourceClass.acceptValue, action.request.metadata.accept)` — comparing the property
to itself through the state machine. It proves the state machine *consults* `acceptValue`; it cannot detect
a wrong `acceptValue`. That is fine as a plumbing test, but it means the **only** assertion of the actual
string `"application/x-protobuf"` would be the vacuity-holed table above.

### `ResourceKind` — does not need to move, and the reason is sharper than "if in doubt, no"

Seven constants (`Resources.kt:53-61`), ABI lines `156-162`. The renderer-gap document's counsel is right
but under-argued. The load-bearing fact is that a glyph atlas has **no `ResourceLocator`**: it arrives
inside `LabelGlyphAtlas.pngBytes` on the batch Rentile returns, not through a RenG route. `CONTEXT.md`'s
**Resource Key** entry requires that "external roots include the exact **Resource Locator** and **Resource
Class**", so the atlas cannot be an `EXTERNAL` key at all, and `ResourceKind.EXTERNAL` is the only kind
that carries a `ResourceClass` (`Resources.kt`, and `CONTEXT.md`'s **Resource Kind**: "Only `EXTERNAL`
entries also carry a **Resource Class**").

That is what distinguishes the atlas from every precedent. `BASEMAP_TILE`, `MODEL_GEOMETRY` and
`MODEL_IMAGE` all name GPU allocations derived from something RenG fetched through a locator. The atlas is
not. `INTERNAL_PIPELINE` — "RenG-owned GL objects with no external locator" — is its natural home, and the
question reduces to whether a consumer should be able to `freeResources(ByKind(...))` the atlas *separately
from* the sticker and composite pipelines. That is a real decision, listed in §9.

**Correction to the renderer-gap document, carried forward:** its §0 notes that `CONTEXT.md`'s **Resource
Kind** entry lists four constants where the source has seven. Verified — `CONTEXT.md` names `EXTERNAL`,
`GEOMETRY_PROGRAM`, `INTERNAL_PIPELINE`, `OFFSCREEN_SURFACE` and stops, while `Resources.kt:53-61` has
seven. The drift is real and predates E-labels.

### `RenGErrorCode` — 31 constants, and the case for exactly one

Thirty-one constants (`Exceptions.kt:5-37`) — the renderer-gap document's count is right. Nothing
*mechanically* forces a new one; see Q3 for what does and does not.

### `PipelineStage` — 19 constants (`Diagnostics.kt:6-26`). See Q3.

### `ResourceLimits` — a twelfth field priced exactly

`ResourceLimits` is a `data class` of eleven `Long`s (`Resources.kt:69-90`). Its ABI block is
`kmp/api/kmp.klib.api:628-669`. Read whole, it is:

- **1** constructor line, `<init>(kotlin/Long = ..., × 11)` — `:629`
- **22** lines of `final val` + getter, alphabetically ordered — `:631-652`
- **11** `componentN()` lines — `:654-664`, ordered lexicographically (`component1`, `component10`,
  `component11`, `component2`, …)
- **1** `copy(kotlin/Long = ..., × 11)` line — `:665`
- **3** lines of `equals` / `hashCode` / `toString` — `:666-668`

A twelfth `Long` therefore costs **five ABI lines: two modified and three added.**

- modified: the constructor (11 → 12 `kotlin/Long`), and `copy` (11 → 12)
- added: one `final val` + its getter, and `component12()`

The renderer-gap document says a twelfth "moves all thirteen signatures." That is not what the dump does —
`component1`…`component11` keep both their meaning and their mangled names, because the field appends at the
end. The real cost is narrower and sharper than "thirteen signatures," and it is this: **the mangled names
of the constructor and of `copy` both change.** The dump spells them out —
`<init>(kotlin.Long;kotlin.Long;…×11){}` and `copy(kotlin.Long;…×11){}`. Source compatibility is untouched
(every parameter has a default), but those two klib symbols are replaced rather than added, and that is the
one genuinely *breaking* shape in the whole E-labels delta.

For contrast, `RendererConfiguration` (`Renderer.kt:18-60`, ABI `:570-590`) is a plain `public class` with
an explicit `init` and **no** `copy`, `componentN`, `equals`, `hashCode` or `toString` in the dump. A new
field there costs **three ABI lines: one modified (the constructor) and two added** (the `val` and its
getter) — and only one mangled symbol changes instead of two.

### `FramePlan` — seven constructor parameters, and no reason to add an eighth

`FramePlan.kt:10-18` — `frameIndex`, `camera`, `projectionMode`, `drawBasemap`, `stickers`, `models`,
`geometries`. Seven, as the renderer-gap document says; its citation `:14-22` points at the property block
rather than the constructor, which is a citation slip and not a count error. See Q6.

**One measurement corrected.** The document reports `drawBasemap` at "19 source and test files under
`kmp/src` and `consumer-smoke/src`, 67 occurrences total." Files: **19**, confirmed. Occurrences: **72
matching lines**, or **80** raw occurrences with `grep -o`. Neither is 67. The conclusion the number
supports — that a `drawLabels` flag would be a small mechanical change — is unaffected.

### `Diagnostic` — free, as claimed

`DiagnosticField` (`internal/DiagnosticFactories.kt:14-37`, 22 entries) appears **0 times** in
`kmp/api/kmp.klib.api`. `Diagnostic.fieldName` is a public `String?` validated against it
(`Diagnostics.kt:3-4`, `isAllowedDiagnosticFieldName` at `DiagnosticFactories.kt:93-94`). New field names
move no ABI. Verified, and the source says so itself at `DiagnosticFactories.kt:249-251`: "this allowlist is
private and invisible to `checkKotlinAbi`, so extending it costs nothing toward the public-ABI-freeze
constraint."

`DiagnosticCode` is a different matter: it is public with exactly three constants
(`Diagnostics.kt:34-38`), and it moves only if engine-diagnostic forwarding lands. See Q8.

---

## 2. `maximumBytesFor`, the glyph byte ceiling, and whether the reasoning holds

The sibling's chain is: adding the class forces an edit to `maximumBytesFor`, "which means `ResourceLimits`
gains a public field, since no existing limit fits a 1 MiB protobuf."

**The first half is right and load-bearing. The second half does not follow.**

### The byte ceiling is real, not dead code

I tested whether a glyph route would ever *consult* `maximumBytesFor` at runtime — because
`isStaticDirect` will return `false` for it, and the `FramePlanningCore.kt:217` call site is reached only by
static-direct external references. It would, through a different path, and the ceiling is enforced twice
over:

- **It is part of route identity.** `ResourceRouteKey` is
  `(accessMode, locator, resourceClass, maximumResponseBytes)` with `equals`/`hashCode` over all four
  (`ResourceOperationProtocol.kt:70-94`), and `OperationRegistry`'s own KDoc names that quadruple at `:66`.
  `requireNoCollision` (`:318-320`) throws `AMBIGUOUS_RESOURCE_ROUTE` when the same URL and class are
  preregistered twice with a *different* ceiling. So the number is not advisory; two disagreeing
  preregistrations of one glyph URL fail the frame closed.
- **It is enforced on the body.** `ResourceResponseRules.kt:89` — `if (body.size.toLong() >
  route.maximumResponseBytes)` → `responseLimitFailure`. The firewall's own paths read it at
  `OperationRegistry.kt:341, 376, 468`.

And the derivation site is already there: `BasemapStyleManifest.routeFor` (`:1054-1064`) builds every
basemap route as `maximumResponseBytes = limits.maximumBytesFor(resourceClass)`. Glyph routes derived
alongside the style's other routes would go through exactly that function.

**So RenG imposes its own outer ceiling on every engine-acquired class, independently of Rentile's.** That
is the established pattern, and glyph inherits it.

### Does an existing limit genuinely fit?

Every limit and its default (`Resources.kt:69-90`), against a Rentile `maxGlyphRangeBytes` default of
**1 MiB** (sibling §4, `Api.kt:634`):

| Field | Default | Currently serves | Fits a glyph range? |
|---|---|---|---|
| `maximumBasemapStyleBytes` | 8 MiB | `BASEMAP_STYLE` | numerically yes, semantically no |
| `maximumBasemapMetadataBytes` | 4 MiB | `BASEMAP_TILE_JSON`, `BASEMAP_SPRITE_JSON` | **numerically yes**, semantically no — both members are JSON documents |
| `maximumBasemapTileBytes` | 32 MiB | vector / raster / DEM tiles | yes, but 32× too generous and a glyph range is not a tile |
| `maximumBasemapSpriteImageBytes` | 32 MiB | sprite image | no |
| `maximumBasemapGeoJsonBytes` | 64 MiB | GeoJSON | no |
| `maximumStickerImageBytes` | 32 MiB | sticker PNG | no |
| `maximumModelGlbBytes` | 256 MiB | GLB | no |
| `maximumModelTextureBytes` | 32 MiB | model / geometry texture | no |
| `maximumDecodedImageBytes` | 64 MiB | decoded pixels, *not* a route ceiling | not a route ceiling at all |
| `maximumModelJsonChunkBytes` | 16 MiB | GLB JSON chunk, *not* a route ceiling | not a route ceiling at all |
| `maximumResidentGpuTextureBytes` | 128 MiB | GPU residency budget | not a route ceiling at all |

So: **`maximumBasemapMetadataBytes` at 4 MiB genuinely fits, numerically.** It is ≥ Rentile's own 1 MiB
ceiling, which is the direction that matters — a RenG ceiling *below* Rentile's would fail closed on a range
Rentile would have accepted, while one above defers to Rentile's own refusal.

### Is reuse wrong? The precedents cut both ways, and neither is decisive

The renderer-gap document cites F-2's `decodedCpuBytes` debt as the reason reuse is regretted. There is a
**counter**-precedent it does not cite, and it is more directly on point because it is a reuse of a
*`ResourceClass`* in this exact table. `FramePlanningCore.kt:249-252`:

> "Reuses `ResourceClass.MODEL_TEXTURE` rather than adding a new enum entry — this cycle's ABI is frozen,
> and a geometry consumer texture is exactly the same kind of resource (a directly-fetched, non-basemap
> texture payload) `MODEL_TEXTURE` already names; nothing about `isStaticDirect` or `maximumBytesFor`'s
> meaning depends on which drawn thing uses it."

That is F-1 reusing a class deliberately, with a written justification, and the justification is **sameness
of kind**. A glyph range shares no kind with a JSON metadata document: different media type, different
producer, different failure mode, different natural ceiling. The reuse precedent argues *against* reuse
here, on its own stated test.

Against that, the F-2 debt is a warning about reuse of a *limit* under sizing pressure, which is the same
hazard. Both point the same way: **do not reuse `maximumBasemapMetadataBytes`.**

### The stronger objection to a twelfth `ResourceLimits` field

`ResourceLimits` is **homogeneous**: eleven `Long`s, every one a byte budget, every one guarded by the same
`require(x in 1L..Int.MAX_VALUE.toLong())` (`Resources.kt:92-128`). And its newest member's own KDoc draws
the line the renderer-gap document's counter-proposal wants:

> "Deliberately independent of the Tile Budget (`maximumBasemapTileInstances`) — a tile count means a
> different number of bytes at every tile size and on every device, so deriving one from the other would
> hide the real cost from whoever configures it." (`Resources.kt:85-88`)

That is the codebase saying, in as many words, that **counts and bytes live apart, and the count lives on
`RendererConfiguration`.** `RendererConfiguration` holds all three existing count ceilings, each with its
own domain range: `maximumBasemapTileInstances` (1..4096), `maximumPreparationBatchSize` (1..4096),
`maximumConcurrentResourceOperations` (1..64) — `Renderer.kt:24-26, 40-48`.

**Assessment of the renderer-gap document's counter-proposal: it is correct, and better founded than it
knew.** A label or glyph-quad count ceiling belongs on `RendererConfiguration` because (a) that is where
every count ceiling already is, (b) `Resources.kt:85-88` explicitly separates counts from bytes, (c) it
bounds the thing that actually varies — a viewport can want 159 ranges at Tokyo z14 (sibling §6) while each
range stays small — and (d) it is three ABI lines and one changed mangled symbol instead of five and two.

### What the byte ceiling should then be

The two are not alternatives; they answer different questions, and only one of them is *forced*. The route
needs a `maximumResponseBytes` or it cannot be constructed at all (`ResourceRouteKey`'s `init` requires
`> 0`, `ResourceOperationProtocol.kt:76-78`). The count ceiling is optional.

Three ways to supply the byte number without a twelfth public field:

1. **An internal constant.** Precedent exists in the same layer:
   `SPRITE_IMAGE_DECODE_CEILING_BYTES` and `DEM_TILE_DECODE_CEILING_BYTES` (`OperationRegistry.kt:625, 653`)
   are internal ceilings with no public knob. They are decode ceilings rather than route ceilings, so the
   precedent is adjacent rather than exact.
2. **Reuse `maximumBasemapMetadataBytes`.** Fits numerically, wrong by the `FramePlanningCore.kt:249-252`
   sameness test, and inherits F-2's regretted shape.
3. **A twelfth `ResourceLimits` field.** Honest, consistent with every other engine-acquired class, and the
   most expensive of the three.

I could not determine which of these the owner wants; it is a genuine design choice and it is listed in §9.
What source *does* settle is that the sibling's "which means `ResourceLimits` gains a public field" is an
inference with at least two unexamined alternatives, and that the *count* ceiling the renderer-gap document
proposes does not belong on `ResourceLimits` under any of them.

---

## 3. `PipelineStage`, `failureRule`, and what the standing instruction costs

### The instruction, verbatim and verified

`internal/firewall/EngineFailureClassification.kt:132-142`:

```kotlin
// Rentile 0.5.0's three label-candidate codes. All three are raised only from
// `acquireLabelCandidates` and the `LabelCandidatePlan` it returns -- an entry point RenG
// does not call and does not expose, so none of them is reachable from `prepare`,
// `prepareBatch`, or `render`. They join the fail-closed bucket rather than getting a
// RenG code of their own precisely because RenG has no label work to attribute them to:
// inventing one would claim a labelling stage that does not exist. When RenG does draw
// labels, these three move out of here into shapes that name the label plan.
RentileErrorCode.FOREIGN_LABEL_CANDIDATE_PLAN,
RentileErrorCode.LABEL_CANDIDATE_PLAN_CLOSED,
RentileErrorCode.GLYPH_TEMPLATE_MISMATCH,
-> return basemapRenderFailure()
```

The enclosing `when (current.code)` at `:77` is the file's only `when` and has **no `else`** — verified by
`grep -n "when (\|else ->"`, which returns exactly one line. So it is exhaustive over `RentileErrorCode`.

### Which constants, and which are actually forced

**Nothing here is mechanically forced.** Moving the three codes out of the bucket is a decision the comment
recommends, not a compile error. Priced honestly, the three are not alike:

- **`FOREIGN_LABEL_CANDIDATE_PLAN`** and **`LABEL_CANDIDATE_PLAN_CLOSED`** are RenG-internal misuse: RenG
  would own the `LabelCandidatePlan`'s whole lifetime (the sibling §2 notes `glyphUrls` throws after
  `close()`, and that the plan must be read before closing). A consumer cannot cause either. These are
  `check`/`error` shapes, not consumer-facing error codes — giving them public codes would publish RenG's
  own bugs as part of its API.
- **`GLYPH_TEMPLATE_MISMATCH`** is the one that is genuinely consumer-visible and has no honest existing
  code. It fires when RenG's independently-composed `glyphs` template structurally disagrees with Rentile's
  (sibling §2, `DefaultBasemapRasterizer.kt:3553-3555`). None of the 31 existing codes names that: it is not
  `INVALID_VALUE` (nothing the consumer passed is invalid), not `RESOURCE_UNAVAILABLE`, not
  `UNSUPPORTED_RESOURCE_FEATURE`. **So the defensible reading of the standing instruction is one new
  `RenGErrorCode`, not three.**

A new `PipelineStage` is a separate decision from a new code, and the comment ties them: "inventing one
would claim a labelling stage that does not exist." If E-labels adds label planning, the stage exists and
the claim becomes true.

### `failureRule`, and whether every new pair needs a test

`failureRule(code, stage)` is a **`private fun`** at `internal/DiagnosticFactories.kt:222-469`, with 31
top-level arms (one per `RenGErrorCode`, else-less) and no ABI presence. `requireAllowedFailureContext`
(`:99-126`) turns a `null` rule into
`throw IllegalArgumentException("error code is not valid at this pipeline stage")`, and
`RenGException`'s `init` calls it (`Exceptions.kt:46-50`). So an unlisted `(code, stage)` pair cannot be
constructed at all.

**Yes, every new pair needs a test — and the enforcement is stronger than "needs".** Three assertions in
`DiagnosticsAndFailuresTest.kt` make it structural:

1. **`publicDiagnosticEnumsHaveTheSpecifiedMembersInOrder` (`:18-52`)** pins **both** enums as ordered
   literal lists — 19 stage names and 31 code names, spelled out. A new constant in either fails here first.
   `DiagnosticCode`'s three names are pinned in the same test.
2. **`failureFactoryAcceptsEveryAllowedFailureTableShape` (`:96-104`)** asserts three literal counts —
   `assertEquals(98, allowedFailureCases.size)`, `assertEquals(28, …{ !it.hasDiagnostic })`,
   `assertEquals(70, …{ it.hasDiagnostic })` — **and** `RenGErrorCode.entries.toSet() ==
   allowedFailureCases.map { it.code }.toSet()`. A new code with no `FailureCase` row fails the set
   equality; a new row fails all three counts until they are updated.
3. **`factorySystematicallyRejectsDisallowedStagesFieldsIdentitiesStatusesAndLimits` (`:106-140`)** walks
   the **closed cross-product** `RenGErrorCode.entries × PipelineStage.entries` — currently **31 × 19 =
   589** pairs — and for every pair not in the allowlist asserts `assertFailsWith<IllegalArgumentException>
   { renGFailure(code, stage) }`. A newly-*allowed* pair that the test's own table does not declare makes
   the construction succeed where the test demands a throw, so **it fails closed rather than silently
   widening.**

Adding one stage and one code takes that cross-product to **32 × 20 = 640**, i.e. 51 more pairs exercised.
This is the best-guarded corner of RenG's public surface and the cheapest to extend correctly.

**Correction:** the renderer-gap document says honouring the instruction "costs a public `PipelineStage`
entry. It also costs entries in `failureRule(code, stage)`". Both true, but it does not say that
`failureRule` itself is free of ABI — the source states that outright at
`DiagnosticFactories.kt:249-251` — nor that the enforcement is a closed cross-product rather than a
per-pair opt-in. The practical consequence is the opposite of the document's tone: the allowlist is the
cheap part, and the two public enum entries are the whole cost.

---

## 4. The `Scene` bijection, verified independently

### Both counts, re-measured

| Claim | Verified? |
|---|---|
| `SceneContentTest.kt` contains 33 `Scene(` constructions | **yes** — `grep -o "\bScene("` → 33 (`\b` correctly excludes `GltfScene(` at `:1324`) |
| 30 `mapOrder = ` / `screenOrder = ` argument occurrences | **yes** — 30 |
| 5 constructions pass both lists | **yes** — the `Scene(` at `:242, :266, :324, :426, :808`, whose argument pairs are `246/247`, `274/275`, `334/335`, `436/437`, `817/818` |
| **25 distinct test call sites pass an explicit literal order** | **yes** — arithmetic closes exactly: 5×2 + 20×1 = 30 |
| 2 production sites | count right, **characterisation wrong** — see below |
| 6 exhaustive `when` sites over `DrawnThingReference` | **yes**, and the list is exactly the document's |

The six `when` sites, re-derived: `MercatorSpatialPlanner.kt:272/273` (`typeOrder`), `:278/279`
(`sourceIndex`), `SceneContent.kt:194/195` (`available`), `:198/199` (`index`), `:360/361` (map split),
`:414/421` (`drawScreenStack`). All six are compile errors on a third member — the two planner ones are
expression `when`s, and the four in `SceneContent` are statement `when`s over a sealed type, which Kotlin
2.3.21 (`gradle/libs.versions.toml:4`) rejects as non-exhaustive.

### Correction: the two production sites are pass-throughs, not literal call sites

The document says of the 27 places that "None goes through a helper; every one names `StickerAt(n)` /
`ModelAt(n)` inline." That is true of the 25 test sites and **false of both production sites**:

- `RenGRenderer.kt:658-659` is **not a `Scene(` construction at all** — `Scene(` appears exactly once in
  `RenGRenderer.kt`, at `:1290`. Line 658-659 builds the internal `RenGPreparedFrame`, whose own
  `mapOrder`/`screenOrder` parameters are at `:263-264`, and it fills them from
  `planned.spatialPlan.mapEntries.map { it.reference }`.
- `RenGRenderer.kt:1290-1298` is the `Scene(` construction, and it forwards `frame.mapOrder` /
  `frame.screenOrder` verbatim.

Both are generic over `List<DrawnThingReference>` and name no constructor. **Under option (a) they need
zero edits.**

### Correction: option (a)'s forced cost is 7 places, not 27

The 25 test sites would **compile and pass unchanged**. A third `DrawnThingReference` member does not
invalidate an existing `Scene(…, mapOrder = listOf(StickerAt(0)))`, and if the bijection becomes
`referenced.size == stickers.size + models.size + labels.size`, every existing scene satisfies it with
`labels.size == 0`. So option (a)'s **forced** edits are:

- the 6 exhaustive `when` sites (compile errors),
- the bijection's arithmetic line (`SceneContent.kt:203`),
- plus the `Scene` constructor gaining a defaulted `labels` parameter.

Seven places. The 25 test sites are places a reviewer would *want* to extend with label coverage, and the
document is right that each is a hand-written literal — but calling them forced overstates option (a)'s cost
by roughly a factor of four, and the grilling should not choose between (a) and (b) on a number that is
wrong by that much. **(b) is still cheaper. It is not cheaper by an order of magnitude.**

The document also misses a 34th `Scene(` in the tree — `GlFrameDrawerTest.kt:114` — which passes only
`geometries` and therefore changes neither count nor conclusion.

### The load-bearing claim is confirmed exactly

**`geometries` and `groundTiles` genuinely sit outside both order lists and outside the bijection's
arithmetic.** Read directly from `SceneContent.kt:184-206`: `referenced` is populated only from `mapOrder`
and `screenOrder` (`:186-191`); the `available` and `index` `when`s handle only `StickerAt`/`ModelAt`
(`:193-200`); the final `require` is `referenced.size == stickers.size + models.size` (`:203-205`). Neither
`geometries` (`:175`) nor `groundTiles` (`:176`) appears anywhere in the block.

And the draw path matches: ground iterates `scene.groundTiles` directly at `:308-324`, geometries iterate
`scene.geometries` directly at `:326-349`, and only `:358-363` consults an order list. **A fourth scene list
drawn at a fixed phase is exactly the shape those two already have.**

---

## 5. The forward-projection gap, re-verified without truncation

### The negative, verified wider than the document verified it

```
$ grep -rn "outputPixelSize.width" kmp/src/commonMain | cat
SceneContent.kt:341            resolutionWidthPixels = …          (uResolution uniform)
SceneContent.kt:781            val width = …                      (screenOrthographicProjection)
CameraMatrices.kt:66           val aspect = …                     (projection matrix)
CameraMatrices.kt:106          require(pixelX in 0 until …)       (physicalPixelGroundRay)
CameraMatrices.kt:109          val width = …                      (physicalPixelGroundRay)
MercatorGroundFootprint.kt:43  val lastColumn = … - 1             (pixel → ground sweep)
$ grep -rn "outputPixelSize.width" kmp/src/commonMain | wc -l
6
```

Six, matching the document exactly, no `head` anywhere. I extended the check in two directions it did not
run:

```
$ grep -rn "outputPixelSize.height" kmp/src/commonMain | cat
SceneContent.kt:342, SceneContent.kt:782, MercatorGroundFootprint.kt:35,
CameraMatrices.kt:65, CameraMatrices.kt:66, CameraMatrices.kt:107, CameraMatrices.kt:110
$ grep -rni "perspectiveDivide\|viewportTransform\|toScreenPixel\|projectToScreen\|worldToScreen\|toNdc\|ndcTo\|screenPositionOf" kmp/src/commonMain | cat
(no output)
```

Seven `.height` sites, all the mirror halves of the same six functions. No projection vocabulary anywhere.
**The negative holds and is now verified across two axes and a vocabulary sweep rather than one grep.**

### The `w`-sign claim is exactly right

`CameraMatrices.kt:76-83` builds the projection matrix, and its bottom row at `:81` is
`listOf(0.0, 0.0, -1.0, 0.0)`. So `w_clip = -z_view`, and the sign of `w` is the behind-camera test. The
document's claim is verified verbatim.

### What such a function would be built from — named precisely

| Piece | Where | Role |
|---|---|---|
| `resolveMercatorCamera` | `CameraMatrices.kt:41-99` | produces `viewMatrix`, `projectionMatrix`, `mercatorAnchor`, `worldSizeLogicalPixels` |
| `resolveCameraRelativeMapPosition` | `PlacementResolver.kt:130-146` | `MercatorPosition` → camera-relative `DoubleVector3` in logical pixels, with the three GPU-representability guards already attached |
| `camera.viewMatrix` / `camera.projectionMatrix` | `ResolvedMercatorCamera` `:24-25` | the two matrices, kept in `Double` |
| `DoubleMatrix4.times` | `internal/math/DoubleLinearAlgebra.kt` | matrix composition |
| `composeMapModelViewProjection` | `SceneContent.kt:661-666` | the existing composition, but it narrows to `FloatArray` and is for drawing |

**The one piece that does not exist, and the reason is written down.** The only point-transform helper in
the tree is `SceneContent.kt:798-803`:

```kotlin
/**
 * Transforms [point] as a homogeneous `(x, y, z, 1)` column vector through this matrix, assuming
 * (as every affine matrix in this file is) that the bottom row is `(0, 0, 0, 1)` so `w` is always
 * exactly `1` and never needs a perspective divide.
 */
private fun DoubleMatrix4.transformAffinePoint(point: DoubleVector3): DoubleVector3
```

It is **`private`**, and its KDoc rules out the exact case a label anchor needs: a projection matrix whose
bottom row is `(0, 0, -1, 0)` is not affine, and `w` is not 1. There is also **no `DoubleVector4` anywhere
in `kmp/src`** (`grep -rn "DoubleVector4"` → no output), so there is no type to carry a homogeneous result.

So the new work is concretely: a `DoubleVector4` (or a small result type carrying `(x, y, z, w)`), a
non-affine `transformPoint`, the perspective divide, the viewport transform to `[0,width]×[0,height]` with
`y` downward per `CONTEXT.md`'s Screen Anchoring, and the `w > 0` rejection. The document's "maybe forty
lines in `internal/projection`" is a fair order of magnitude, and its recommendation that it be its own task
with its own unit tests is well founded — with one addition. **A vacuity warning for those tests:** a
forward-projection test taken at the camera's own ground anchor is a symmetry point. The anchor projects to
the viewport centre under *any* correct-or-transposed matrix, any focal length, and either sign convention
for `y`. So does a point on the view axis. The discriminating fixtures are off-axis, off-centre, at non-zero
pitch and bearing, and at least one **behind** the camera — and the round trip against
`physicalPixelGroundRay` (`CameraMatrices.kt:101-138`), which is the exact inverse and already tested,
is the strongest available check.

---

## 6. What `drawBasemap = false` does today, exactly

Six production sites, and they are not all the same kind of thing.

| Site | Behaviour when `drawBasemap = false` |
|---|---|
| `FramePlan.kt:14, 22, 44, 55, 64` | the field itself, and it participates in `FramePlan`'s `equals`/`hashCode` |
| `internal/identity/FramePlanCanonicalEncoding.kt:60` | encoded into the canonical frame identity, so two plans differing only here are different frames |
| `internal/planning/FramePlanningCore.kt:226` | `if (plan.drawBasemap && basemapStyle != null)` — **the style is not traversed as a resource reference**, so it is not fetched, not validated, not compiled |
| `internal/planning/MercatorSpatialPlanner.kt:146` | `if (plan.drawBasemap && basemapStyleConfigured)` — **no ground footprint is computed and no tiles are selected**; `footprint` and `tileSelection` stay `null` |
| `RenGRenderer.kt:648` | carried onto the internal `RenGPreparedFrame` |
| `RenGRenderer.kt:1181` | `if (frame.drawBasemap && configuration.basemapStyle == null && !basemapWarningEmitted)` — the warn-once `BASEMAP_NOT_CONFIGURED` diagnostic is **not** emitted, because the frame did not ask for a basemap |

**The one non-obvious behaviour, and it is documented and load-bearing.** LOD hysteresis advances *anyway*.
`MercatorSpatialPlanner.kt:142` computes `observeMercatorLod(plan.camera.zoom, previousSelectedLod)`
**before** the `drawBasemap` gate at `:146`, so a `drawBasemap = false` frame still selects and commits an
LOD. `CONTEXT.md:51-53` states this deliberately: *"Every successfully prepared Mercator plan advances
provisional LOD history even when no Basemap Style is configured or `drawBasemap` is false; those frames
select and acquire no tiles."*

So the flag's current meaning is precise: **`drawBasemap = false` suppresses the basemap's resources and its
draw, and nothing else.** It does not suppress camera resolution, LOD history, sticker/model/geometry
planning, or the frame's identity contribution.

That establishes the ground the design question stands on. Labels are derived from the same style the flag
already suppresses the fetching of — so at `drawBasemap = false` there is no compiled `PreparedStyle`
(`RenGRenderer.kt:484-495` says the field is *"Cleared rather than left standing on a `drawBasemap = false`
frame"*), and therefore no label layers to plan against and no glyph closure to enumerate. **Labels are
suppressed by `drawBasemap = false` today as a matter of mechanism, not policy.** Whether that should stay
implicit, or be stated in the spec, or be separable by a second flag, is a decision — see §9.

---

## 7. Cross-frame state, priced

### The two pieces, and where each is read, written and cleared

| | `previousEncodedPlan` | `previousSelectedLod` |
|---|---|---|
| declared | `RenGRenderer.kt:478` | `RenGRenderer.kt:479` |
| read | `:521` (into `FramePlanningRequest.previousPlan`) | `:522` (into `previousSelectedLod`) |
| written | `:641`, after every successful `prepare` | `:642`, same place |
| cleared | `:1044` | `:1045` |

`clearFrameHistory()` **is public** — `Renderer.kt:75`, `public fun clearFrameHistory(): Unit`, and it
appears in the ABI dump on the `Renderer` interface. Confirmed.

**Correction: `clearFrameHistory()` clears four things, not two.** `RenGRenderer.kt:1041-1052`:

```kotlin
previousEncodedPlan = null                                     // :1044
previousSelectedLod = null                                     // :1045
identityRegistry = CanonicalIdentityRegistry()                 // :1046
framePlanningCore = newFramePlanningCore(identityRegistry)     // :1047
```

The renderer-gap document cites `:1044-1045` and stops there. The same method also resets the canonical
identity registry and rebuilds the planning core around it. That matters twice over: any label placement
memory added to this block joins a reset that is already doing more than "forget two values," and
`CONTEXT.md:53-54` — which describes `clearFrameHistory()` as clearing "the structural-diff and LOD
baseline" — is itself now narrower than the code. A small documentation drift, worth fixing in passing.

**A third piece of renderer-lifetime state the document does not mention**: `basemapWarningEmitted`
(`RenGRenderer.kt:482`), whose KDoc says *"Once per renderer, never per frame."* It is **not** cleared by
`clearFrameHistory()`. It changes no pixels, so the document's "exactly two pieces that change pixels" is
defensible — but a label cycle that adds a warn-once diagnostic (a suppressed label, an unroutable glyph
template) inherits an existing precedent for renderer-lifetime, never-cleared diagnostic state, and should
decide deliberately which of the two patterns it is following.

### `prepareBatch`'s diff-baseline rule, in code — and this changes the answer

`CONTEXT.md:44-45`: *"Structural diffing uses the last successfully prepared plan as the first baseline and
each immediately preceding input plan as the next baseline within a batch; missing history is an empty
baseline."*

In code, `prepareBatch` is (`RenGRenderer.kt:817-833`):

```kotlin
override suspend fun prepareBatch(plans: List<FramePlan>, accessMode: ResourceAccessMode): List<PreparedFrame> {
    if (plans.size > configuration.maximumPreparationBatchSize) { throw … }
    return plans.map { prepare(it, accessMode) }
}
```

**There is no batch machinery.** It is a size check and a sequential fold over `prepare`. Since `prepare`
commits `previousEncodedPlan` and `previousSelectedLod` at `:641-642` on every success, "each immediately
preceding *input* plan" and "the last *committed* plan" are **the same object** inside a batch, by
construction.

The renderer-gap document's §4.3(c) says: *"Label hysteresis would have to follow the same rule — each
frame's placement memory being the previous input frame's outcome, not the last committed one — or a batch
would produce different labels than the same frames prepared one at a time. That is a real correctness
requirement with a real test shape, and it is the kind of thing that is easy to leave until it is
expensive."*

**That framing is inverted by the code.** The two are not alternatives to be reconciled; they are
identical, and a batch is *definitionally* N sequential `prepare` calls. So:

- Any label state placed where `previousSelectedLod` already is — read in planning, committed at the end of
  `prepare` — **satisfies the batch rule for free.** There is nothing to design and nothing to test beyond
  what `prepare` already guarantees.
- The hazard is the exact opposite of the one described: it arises only if the design *deviates* — e.g. a
  batch-wide placement pass, or placement memory committed somewhere other than `prepare`'s success path.
  That would be new machinery, and it is the thing to refuse rather than the thing to build.

This is worth stating plainly in the spec, because "batch semantics" reads like an open problem in the
document and is not one.

**One caveat I could not close from source.** `CONTEXT.md:43` says "History commits only when the whole
batch succeeds," and `CONTEXT.md:47` says "Failure or cancellation exposes no partial history." The code at
`:641-642` commits per-`prepare`, and `prepareBatch` has no rollback, so a batch that throws on its fifth
plan appears to leave the first four frames' history committed. Either the prose is describing an intent the
code does not implement, or "the whole batch" is doing work I have not located. It is not an E-labels
question, but a label placement memory would inherit whichever answer is true, so it is listed in §9.

### Draw repeatability — the document's conclusion is right, its evidence is not

`CONTEXT.md`'s **Prepared Frame** (`:106`) says a prepared frame "may be drawn repeatedly", and
**Frame History** (`:54`) ends "Drawing never changes history." Those two sentences do force collision to
resolve in `prepare()`.

But the renderer-gap document supports that with *"a test already in the tree that would catch it —
`ModelReadbackSuite`'s case 4 asserts two frames with identical inputs are bit-identical"*. That test does
not do what the sentence needs. `ModelReadbackSuite.render` (`:465-471`) calls `renderer.prepare(plan)`
**fresh on every invocation**:

```kotlin
fun render(plan: FramePlan): ReadFrame {
    clearTarget(binding, target)
    runBlocking { renderer.prepare(plan) }.use { prepared -> renderer.draw(prepared, renderTarget) }
    return readFrame(binding, target)
}
```

and `assertAnimationIsBoundToTime` (`:316-322`) compares two **separately prepared** frames at
`frameIndex = 5` and `6`. It is a *prepare*-determinism assertion, not a draw-repeatability one. Nothing in
`kmp/src/commonTest` draws one prepared frame twice and compares pixels — `RendererFactoryTest.kt:129`
(`repeat(3) { renderer.draw(frame, target) }`) draws repeatedly but asserts lifecycle, not bytes.

Two consequences, and the second is the interesting one:

1. Draw repeatability is asserted by `CONTEXT.md` and by nothing executable. **A label cycle that puts
   collision in `prepare()` should add the missing test** — draw one prepared frame twice, assert
   bit-identical — because that is the property being relied on and it is currently unguarded.
2. `ModelReadbackSuite` case 4 is evidence *against* unconstrained hysteresis rather than *for*
   prepare-time collision. Under Option 2, two consecutively prepared frames with identical plans may
   legitimately differ — which is exactly what that test forbids for models. **A label version of that
   assertion cannot be written in the same shape**, and the cycle must decide what the label analogue of
   "identical inputs, identical bytes" even means. The document's own §6.4 trap 2 gropes toward this
   (its three-frame A-B-A shape is right), but it does not notice that the existing suite's determinism
   idiom is the thing hysteresis breaks.

---

## 8. Corrections to the two documents

Collected, most consequential first. Corrections already given in place are cross-referenced rather than
repeated.

**1. (renderer-gap §5, headline) "Labels can be drawn with no public ABI addition at all" is inverted.**
A new `ResourceClass` is mechanically forced by the route index's key type. §0.

**2. (renderer-gap §5, "The one thing not in this list, and it is the biggest") The 20-of-34 style problem
largely dissolves at Rentile `0.6.0`.** The document calls it "not optional and not small" and attaches a
public `DiagnosticCode` decision to it. Its measurement — `HANDOFF.md:172-185`, 20 of 34 styles carry `>`
inside a `symbol` layer and lose their text silently at the pinned `0.5.0` — is verified and was correct
when written. But the sibling §5.2 shows Rentile `0.6.0` implements `!=`, `<`, `>`, `slice` **and**
`to-string` (`StyleExpression.kt:118, :120, :123`), gated in Rentile's own CI via
`tools/check_corpus_fidelity_policy.py`. **If the pin moves — which the sibling recommends on independent
grounds — the motivating case for engine-diagnostic forwarding evaporates.** The general gap survives (RenG
still reads no engine diagnostic, deliberately, per `EngineFailureClassification.kt:49-52`), but its
priority and its ABI consequence both drop sharply. The two documents were written in parallel and neither
could see this; it is the single most important cross-document synthesis available.

**3. (renderer-gap §4.3(c)) The batch-baseline hazard is not a hazard.** `prepareBatch` is
`plans.map { prepare(it, accessMode) }` (`RenGRenderer.kt:832`). §7.

**4. (renderer-gap §4.3(a)) `ModelReadbackSuite` case 4 does not test draw repeatability.** It re-prepares.
The conclusion stands on `CONTEXT.md` alone, and the property is currently unguarded by any test. §7.

**5. (sibling §4) The `ResourceClass` edit list is seven production sites, not six.**
`ValueSupport.kt:86-99` (`reportOrder`) is missing, and it is a public-behaviour decision. §1.

**6. (sibling §4) The edit list omits every forced test site.** Four fail loudly
(`ResourcesTest.kt:~48-62`, `RentileKeyDerivationTest.kt:42-47`,
`EngineFailureClassificationTest.kt:317-334`, `ResourceOperationOrdinaryCommitTest.kt:44`) and one is
silently vacuous (`ResourcesTest.kt:132-156`). §1.

**7. (sibling §4) "which means `ResourceLimits` gains a public field" is an inference with unexamined
alternatives.** The byte ceiling is real and enforced, but its *value* can come from an internal constant or
a reused limit; and the renderer-gap document's counter-proposal (a count ceiling on
`RendererConfiguration`) is better founded than either document argues, because `Resources.kt:85-88`
separates counts from bytes in as many words. §2.

**8. (renderer-gap §1.3) Option (a)'s forced cost is 7 places, not 27.** The 25 test sites compile and pass
unchanged; the two production sites are generic pass-throughs. §4.

**9. (renderer-gap §5, `ResourceLimits`) "Adding a twelfth moves all thirteen signatures" is not what the
dump does.** Five ABI lines, two modified. The real cost is that the constructor's and `copy`'s *mangled
names* both change — the one breaking shape in the delta. §1.

**10. (renderer-gap §4.2) `clearFrameHistory()` clears four things, not two**, and `CONTEXT.md:53-54` is
narrower than the code. §7.

**11. (renderer-gap §1.3) There is a 34th `Scene(` in the tree**, `GlFrameDrawerTest.kt:114`. It passes no
order and changes no conclusion.

**12. Minor citation slips, recorded so a spec author does not chase them.** `FramePlan`'s seven
constructor parameters are at `FramePlan.kt:10-18`, not `:14-22` (that is the property block).
`requireAllowedFailureContext` spans `DiagnosticFactories.kt:99-126`, not `:99-121`. `drawBasemap` occurs on
**72** matching lines (80 raw occurrences) across 19 files, not 67.

**13. Both documents cite `main` at `6730722`; HEAD is `f997e1a`.** Documentation-only, no source citation
affected — but a spec quoting either document should quote the newer commit.

---

## 9. What source could not settle — for the owner

Each of these is a decision, not a research gap. Where evidence bears on it, the evidence is named.

**1. Where the glyph route's byte ceiling comes from.** The route cannot exist without one
(`ResourceRouteKey.init`, `ResourceOperationProtocol.kt:76-78`), and it is part of route identity, so two
disagreeing preregistrations of one glyph URL fail the frame closed (`OperationRegistry.kt:318-320`). Three
options: an internal constant (adjacent precedent: `SPRITE_IMAGE_DECODE_CEILING_BYTES`,
`OperationRegistry.kt:625`); reuse `maximumBasemapMetadataBytes`, which fits numerically at 4 MiB but fails
the `FramePlanningCore.kt:249-252` sameness test; or a twelfth `ResourceLimits` field at five ABI lines and
two changed mangled symbols. **Settled by:** the owner choosing, on the principle that a public knob exists
only where a consumer has a reason to turn it.

**2. Whether a label or glyph-quad count ceiling exists at all, and on which type.** If it exists,
`RendererConfiguration` is the right home — `Resources.kt:85-88` separates counts from bytes explicitly, and
all three existing count ceilings live there (`Renderer.kt:24-26`). **Settled by:** deciding whether label
collision fails closed over budget (like the Tile Budget) or drops content (which the renderer-gap document
correctly flags as a break with "RenG never drops"). Those two answers want different types.

**3. Whether the glyph atlas gets a `ResourceKind`.** It has no `ResourceLocator`, so it cannot be an
`EXTERNAL` key, and `INTERNAL_PIPELINE` covers it. A new kind buys exactly one thing: the ability to
`queryResources`/`freeResources` the atlas separately from the sticker and composite pipelines.
**Settled by:** deciding whether that separability is a promise RenG wants to make.

**4. How many `RenGErrorCode`s the standing instruction actually costs.** Source shows the three Rentile
codes are not alike: two are RenG-internal misuse (RenG owns the plan's lifetime) and one,
`GLYPH_TEMPLATE_MISMATCH`, is consumer-visible with no honest existing code. **Settled by:** ruling on
whether RenG's public error vocabulary may name a failure only RenG's own bug can cause. My reading is no,
which makes the answer one code, not three.

**5. Whether `PipelineStage` gains a label stage.** Costs one enum entry, one literal in
`DiagnosticsAndFailuresTest.kt:18-52`, and 31 more cross-product pairs at `:106-140`. **Settled by:**
whether label planning is a stage a consumer should be able to see a failure attributed to.

**6. Whether `drawBasemap = false` should also suppress labels — and whether that needs saying.** Today it
does, mechanically: no style is traversed (`FramePlanningCore.kt:226`), no tiles are selected
(`MercatorSpatialPlanner.kt:146`), and `preparedBasemapStyle` is cleared (`RenGRenderer.kt:484-495`), so
there is nothing to plan labels from. **Settled by:** deciding whether "no basemap, but its labels" is a
use case. If it is not — and I can see no argument that it is — the spec needs one sentence and no field.

**7. Whether label placement hysteresis exists at all** (the renderer-gap document's Options 1–4). Source
settles two sub-questions and leaves the choice: the batch rule is free if the state sits where
`previousSelectedLod` sits (§7), and `clearFrameHistory()` already resets more than two values, so the
clearing half is one line at `RenGRenderer.kt:1046`. What source cannot settle is the bound and the label
identity derivation. **Settled by:** the owner, and it wants an ADR because the LOD precedent
(`MercatorLod.kt:29-36`) carried neither.

**8. What the label analogue of "identical inputs, identical bytes" is.** Under hysteresis,
`ModelReadbackSuite`'s determinism idiom (`:309-322`) cannot be reused, because two consecutively prepared
frames with identical plans may legitimately differ. **Settled by:** deciding the assertion before the
implementation, since the wrong one is vacuous in both directions — a test that clears history between
frames passes with the hysteresis code deleted, and one that does not clear it fails for correct code.

**9. Whether `prepareBatch` really has all-or-nothing history semantics.** `CONTEXT.md:43` says history
"commits only when the whole batch succeeds" and `:47` that failure "exposes no partial history", but
`prepareBatch` (`RenGRenderer.kt:832`) is an unguarded `map` over `prepare`, which commits at `:641-642`
per frame. Either the prose overstates the code or I have missed a rollback. **Settled by:** reading the
batch tests, or by the owner confirming which of the two is authoritative — a label placement memory
inherits the answer.

**10. Whether the Rentile pin moves before or during E-labels.** Not my question — the sibling makes the
case in its §7 — but two of my findings depend on it: correction 2 above (the 20-of-34 problem dissolving)
and every `LabelCandidate`-shaped assumption about what RenG receives. **Settled by:** the owner, and the
sibling's recommendation to bump as its own commit first is the one that keeps my adjudication stable.

---

## 10. What this document did not verify

- **Nothing was built and no test was run.** Every claim is source reading plus `git`, `grep`, `sed`,
  `awk` and one structural `when`-scanner run over the production source sets. The ABI arithmetic in §1 is
  read off `kmp/api/kmp.klib.api` and is a prediction about what `checkKotlinAbi` would emit, not an
  observation of it. **What would settle it:** add the enum entry in a scratch worktree and run
  `./gradlew --no-configuration-cache :kmp:checkKotlinAbi`.
- **No Rentile source was read.** Every statement about `glyphUrls`, `planLabelCandidates`, the closure's
  exactness, `maxGlyphRangeBytes` and the `0.6.0` operator set is taken from
  `docs/research/2026-08-28-e-labels-rentile-surface.md` with its citation carried through, not
  independently re-derived. Correction 2 in §8 rests entirely on that document's §5.2 being right.
- **The `when`-scanner covers production source sets only** (`commonMain`, `androidMain`, `iosMain`,
  `macosMain`, `linuxMain`, `nativeMain`). Exhaustive `when`s over `ResourceClass` in *test* sources would
  also break, and I enumerated those separately via `ResourceClass.entries` rather than by scanning — a
  test-local `when` that names all eleven constants without touching `entries` would have been missed.
- **The 589 → 640 cross-product arithmetic in §3 is arithmetic**, not a measured test-count delta. The
  three literal counts in `failureFactoryAcceptsEveryAllowedFailureTableShape` (98 / 28 / 70) are read from
  source and would each need recomputing at implementation time.
- **I did not price the label *drawing* work at all** — the SDF pipeline, the batch, the collision policy,
  the fixture problem. Those are the renderer-gap document's §§1–3 and §6, and nothing here revisits them
  except where a public-API consequence falls out.
- **`docs/research/2026-08-28-e-labels-rentile-surface.md`'s own §8 caveats are inherited whole**, in
  particular that nothing proves published Rentile `0.6.0` was built from `87ccba2` and that no label call
  has been run at runtime on any version.
