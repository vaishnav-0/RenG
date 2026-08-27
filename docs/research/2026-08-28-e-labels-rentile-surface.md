# E-labels preflight: what Rentile hands over, and on what terms

**Date:** 2026-08-28. **Branch read:** `main`, HEAD `6730722` ("Merge Cycle H — Android and iOS bring-up"),
clean tree. **Question asked:** for the cycle that draws map text, what does Rentile give RenG, what does
it withhold, and does the pin have to move first?

This is a research document, not a decision record and not a plan. Nothing was changed in either
repository. Every claim below is **read** from source at a named commit, **measured** by a command quoted
in place, or explicitly flagged **unverified**. It is written to be argued with during the grilling that
follows, so where a fact could decide the cycle's shape it is given with a file and line rather than
summarised.

It deliberately does not survey RenG's own rendering side — the SDF shader, the batch, the collision
policy, the regime question. A sibling agent has that half.

---

## 0. Provenance

| Input | Value |
|---|---|
| RenG worktree | `/Users/rohittp/Data/Other/RenG`, branch `main`, HEAD `6730722`, clean. Nothing modified. |
| Pin under review | `gradle/libs.versions.toml:7` → `rentile = "0.5.0"` |
| Rentile checkout | `/Users/rohittp/Data/Other/rentile`, branch `main`, HEAD `87ccba2` ("Release 0.6.0 label and icon fidelity"), clean tree, `gradle.properties:10` → `VERSION_NAME=0.6.0`. Read only. |
| Rentile `0.5.0` source range | `e423a78` (bumps `VERSION_NAME` to `0.5.0`) through `d899cb2`. `RentileKeyDerivation.kt:23` names `d899cb2` as the pinned commit; between `e423a78` and `d899cb2` the only production change is **10 lines of KDoc** on `LabelCandidatePlan` (`git diff e423a78..d899cb2 -- kmp/src/commonMain`), so the choice of endpoint does not matter to anything below. |
| Rentile `0.6.0` source | `87ccba2`, a single squashed release commit: 32 files, +4,976 / −659. The whole `0.5.0 → 0.6.0` range is **8 commits**, 34 files, +5,261 / −673; the other seven are corpus tests and docs. |
| Published Rentile versions (measured) | `curl https://maven.rohittp.com/com/rohittp/rentile/kmp/maven-metadata.xml` → `0.1.4, 0.1.5, 0.2.0, 0.3.0, 0.4.0, 0.5.0, 0.6.0`; `<latest>` and `<release>` both `0.6.0`; `lastUpdated 20260823134311`. |
| Six-target availability at `0.6.0` (measured) | `kmp-android`, `kmp-iosarm64`, `kmp-iossimulatorarm64`, `kmp-macosarm64`, `kmp-linuxx64`, `kmp-linuxarm64` and the aggregate `kmp` POM all return HTTP `200` anonymously. |
| Documents this one supersedes in part | `docs/research/2026-08-21-rentile-upgrade-and-labels.md` §3 (written against `0.4.0`) and `docs/research/2026-08-22-rentile-glyph-closure-request.md` (written against `0.2.0`, with a `2026-08-23` erratum written against `0.5.0`). |

---

## Summary: the three facts most likely to decide this cycle's shape

**1. The firewall problem is solved, and it was solved in the version RenG already pins.** The
`0.4.0` spike called glyph routing "one architectural blocker … the single largest reason labels are a
cycle and not a task," and offered three shapes, all of which diluted ADR 0016. None is needed.
`BasemapRasterizer.planLabelCandidates(style, tiles, resourceAccess)` acquires the label *tiles* and
freezes the closure **without fetching one glyph byte** (`Api.kt:779`, implementation
`DefaultBasemapRasterizer.kt:542-598` — the only acquisition in that body is `vectorAcquirer.acquire`);
`LabelCandidatePlan.glyphUrls(template)` then returns **the exact URL strings** the following acquisition
will request, composed by Rentile's own substitution (`Api.kt:553`, implementation
`DefaultBasemapRasterizer.kt:3543-3559`, which calls the same `GlyphResourceAcquirer.resolveUrl` the
acquirer calls at `DefaultBasemapRasterizer.kt:614`). RenG already has the mechanism to consume that:
`BasemapEngineHost.registerRoutes` (`:189-196`) exists precisely to add routes to an invocation that is
already open, for exactly the analogous reason — style routes are not knowable until the style has been
read. Exact-string preregistration survives intact. **This changes the cycle from "redesign the firewall"
to "add a resource class and a second preregistration round."**

**2. Rentile `0.6.0` is not a point upgrade of the label surface; it is a different surface, and it is
the only version in which labels are worth building.** At the pinned `0.5.0`, label layers are restricted
to a **ten-name allowlist of place-name source layers** (`StyleCompiler.kt:544`,
`PLACE_NAME_SOURCE_LAYERS`) — country, state, city, town, island, place. No roads, no POIs, no water. At
`0.6.0` that allowlist is deleted and *every visible text-bearing vector symbol layer* is admitted
(`StyleCompiler.kt:546-558`; the old set survives only as `LEGACY_PLACE_NAME_SOURCE_LAYERS` at `:2215`,
used to decide which source failures stay strict). `0.6.0` also implements `symbol-placement: line` and
`line-center`, cooperative overlap, per-feature paint, and the paired-icon contract — and it implements
the four expression operators the glyph-closure request asked for, plus `to-string`
(`StyleExpression.kt:118, :120, :123`). Building E-labels on `0.5.0` would ship a map labelled with city
names and nothing else, against an API whose three central data classes `0.6.0` then breaks.

**3. The upgrade breaks nothing RenG compiles today, and the whole `0.6.0` ABI removal set is confined
to three types RenG has never referenced.** `git diff e423a78..87ccba2 -- kmp/api/kmp.klib.api` removes
**29 lines**, every one of them a member of `LabelCandidate`, `LabelIconRef`, or `LabelLayerStyle`.
`Exceptions.kt` (and therefore `RentileErrorCode`, which `EngineFailureClassification` exhausts with no
`else`) and `Resources.kt` (`ResourceClass`, `ResourceTransport`, `RawResourceStore`) are **byte-identical**
between `e423a78` and `87ccba2`. RenG imports none of the three changed classes — its complete Rentile
import set is 49 symbols and contains no `Label*` value type. The cost of the bump is therefore the
five-coupled-places dance in §7, not a code change.

Two things that follow from those and are worth flagging early: `PreparedStyle.digest` **changes** at
`0.6.0` (`RENDERER_SEMANTIC_VERSION` moves `rentile-renderer-3` → `rentile-renderer-4`,
`StyleCompiler.kt:2103` vs `:2213`), so every RenG identity derived from it — `basemapTileKey` — is a new
value; and `0.6.0` tightens comparison typing in a way that can turn a style that prepares today into one
that fails to prepare (§5.3), which is the one genuine upgrade risk in the release.

---

## 1. The version situation

RenG pins `rentile = "0.5.0"` (`gradle/libs.versions.toml:7`). The newest published version is `0.6.0`,
whose commit subject is "Release 0.6.0 label and icon fidelity". Nobody in this repository has evaluated
it: `grep -rn "0\.6\.0" docs/ HANDOFF.md CLAUDE.md` finds nothing about Rentile.

Rentile ships a migration document for it — `docs/migrations/0.6.0.md`, 288 lines — which is the single
most useful artefact in this preflight and should be read alongside this one. It is Rentile's own account;
everything below that matters was re-derived from source rather than taken from it, and where the two
differ this document says so.

**Rentile has no completion-record scheme.** `com/rohittp/rentile/kmp/0.6.0/rentile-release-completion-v1.json`
returns 404, as does the `0.5.0` equivalent; `ls /Users/rohittp/Data/Other/rentile/tools/` contains only
`check_corpus_fidelity_policy.py`, `check_coverage_manifest.py` and `native-corpus`. ADR 0013's machinery
is RenG's own invention and Rentile does not run it. So **nothing proves the published `0.6.0` bytes came
from `87ccba2`**; the evidence is circumstantial and consistent — the release commit is dated
2026-08-23 13:02:50 UTC and `maven-metadata.xml` records `lastUpdated 20260823134311`, forty minutes
later — and there is no second candidate commit, since `87ccba2` is `main`'s head and the only commit
declaring `0.6.0`. §8 says what would settle it.

### The `0.5.0 → 0.6.0` diff, in one table

`git diff --stat e423a78..87ccba2 -- kmp/src/commonMain` is nine files:

| File | Δ | What moved |
|---|---|---|
| `Api.kt` | +191 | Six new enums, `LabelLinePoint`, and the three broken data classes |
| `Diagnostics.kt` | +64 | `LABEL_SOURCE_UNAVAILABLE` added; `ICON_FEATURE_SKIPPED` gains a second producer; `LINE_PLACEMENT_LABEL_EXCLUDED` demoted to legacy |
| `internal/DefaultBasemapRasterizer.kt` | +481 | Label planning over the widened layer set |
| `internal/glyph/GlyphAtlasPacker.kt` | +16 | `SHELF_WIDTH_PX` 4096 → **8192** |
| `internal/glyph/LabelCandidateAssembler.kt` | +598 | Line placement, per-feature evaluation, paired icons |
| `internal/glyph/LabelLayout.kt` | +2 | A comment (`64 x 256` → `256 x 256`) |
| `internal/style/CompiledStyle.kt` | +109 | Widened compiled label layer |
| `internal/style/StyleCompiler.kt` | +472 | Layer admission, the operators' reachable constructs, `RENDERER_SEMANTIC_VERSION` |
| `internal/style/StyleExpression.kt` | +151 | `!=`, `<`, `>`, `slice`, `to-string`, stricter comparison typing |

Not changed: `Exceptions.kt`, `Resources.kt`, `internal/glyph/ScriptSupport.kt`,
`internal/glyph/GlyphRangeDecoder.kt`, `internal/glyph/GlyphResourceAcquirer.kt`, and every file RenG
reproduces byte-for-byte in `BasemapStyleManifest.kt` and `RentileKeyDerivation.kt`. **The reproduction
verification the `0.4.0` spike performed at file granularity does not need redoing for this range** — the
diff simply does not touch those files. (This is a weaker claim than that spike's: it is "the files did
not change" rather than "the files changed and were re-compared." For this range the former is enough.)

---

## 2. The entry point (question 1)

`acquireLabelCandidates` is still the name, and `LabelCandidatePlan` is still the name. Both were correct
at `0.5.0` and both are unchanged at `0.6.0`. The full label surface of `BasemapRasterizer`, at
`87ccba2:kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt` (`0.5.0` line numbers in brackets):

```kotlin
// :753  [:646]
public fun labelLayerDescriptors(style: PreparedStyle): List<LabelLayerDescriptor>

// :756  [:649]
public suspend fun acquireLabelTiles(
    style: PreparedStyle, tiles: List<TileId>,
    resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
): List<ValidatedMvtTile>

// :771  [:664]
public fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String

// :779  [:672]
public suspend fun planLabelCandidates(
    style: PreparedStyle, tiles: List<TileId>,
    resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
): LabelCandidatePlan

// :789  [:682]
public suspend fun acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch

// :798  [:691]
public suspend fun acquireLabelCandidates(
    style: PreparedStyle, tiles: List<TileId>,
    resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
): LabelCandidateBatch
```

and the plan itself (`Api.kt:517-559`, `0.5.0` `:415-453`):

```kotlin
public interface LabelCandidatePlan : AutoCloseable {
    public val tiles: List<TileId>
    public val glyphClosure: List<GlyphRangeRef>
    public fun glyphUrls(template: String): List<String>
    public val diagnostics: List<RenderDiagnostic>
    override fun close()          // idempotent, non-blocking, non-throwing
}

public data class GlyphRangeRef(          // :493
    public val fontStackDigest: String,
    public val rangeStart: Int,           // 0, 256, 512, …
)
```

**What each call actually fetches**, read from `DefaultBasemapRasterizer.kt` rather than from KDoc:

| Call | Acquires | Evidence |
|---|---|---|
| `labelLayerDescriptors` | nothing | pure accessor over `compiledStyle.labelLayers` |
| `acquireLabelTiles` | `VECTOR_TILE` only | — |
| `labelCandidateRequestKey` | nothing, pre-network | `Api.kt:762-770` |
| `planLabelCandidates` | `VECTOR_TILE` only — the label layers' sources at `source.sampleFor(tile)` | `:567-580`; the body's only acquirer call is `vectorAcquirer.acquire(sample, resourceAccess)` at `:574` |
| `acquireLabelCandidates(plan)` | `GLYPH_RANGE` only | `:610-623`, `glyphAcquirer.acquire(...)` at `:614` |
| `acquireLabelCandidates(style, tiles, mode)` | both, as `plan` then `acquire` then `plan.close()` in a `finally` | `:630-641` |

The three-line body of the one-shot overload is the whole reason RenG should not call it: it plans and
acquires inside one uninterruptible call, which is precisely the window RenG needs to preregister in.

**The closure is exact, not a superset.** `glyphClosure` and the acquisition both read
`assembly.requiredRanges` — the same frozen list, on the same plan object (`:3528-3534` for the closure,
`:611` for the acquisition). Rentile's own KDoc states the property this rests on (`Api.kt:522-527`):
"Exactly the Glyph Ranges [acquireLabelCandidates] will request from this plan — not a superset and not an
estimate." The design note at `:504-506` explains why a plan rather than a second query: "tile bytes can
legitimately change between two acquisitions," so an independent closure query *could* under-approximate.
The glyph-closure request document said under-approximation is fatal for RenG and over-approximation is
free; Rentile gave exactness by construction instead.

**`glyphUrls` is credential-symmetric and fails loudly.** It takes the caller's own copy of the resolved
`glyphs` template, compares only the **redacted** forms, and throws `GlyphTemplateMismatchException` when
they disagree (`:3553-3555`). The URLs it returns substitute the *caller's* template, so RenG's credential
is the one that reaches RenG's own transport — Rentile never emits its copy. Two consequences worth
carrying into design:

- The check cannot catch a stale or wrong credential, only a structurally different template. Rentile's
  own KDoc says so (`Api.kt:541-545`): a caller passing an already-redacted or stale credential "gets back
  a plausible, non-empty list whose every URL is wrong for that reason alone." Under RenG's firewall that
  presents as every glyph route preregistered and none matched — a total, silent label outage, since
  `executeTransport` would then throw `AMBIGUOUS_RESOURCE_ROUTE` on each fetch.
- It returns an empty list, **without checking the template**, when the style resolves no `glyphs` key
  (`:3545-3546`). A style with no glyphs is legitimate and produces an empty batch with a
  `GLYPH_RANGE_UNAVAILABLE` INFO diagnostic (`:554-565`), not a failure.

**`glyphUrls` is the one member `close()` kills.** `tiles`, `glyphClosure` and `diagnostics` are computed
at construction and survive close; `glyphUrls` throws `LabelCandidatePlanClosedException` afterwards
(`:3543-3544`, `:3567-3568`). So RenG must read the URLs before it closes the plan, and the plan is
reusable — "acquiring from one plan repeatedly yields equal batches" (`Api.kt:508`).

---

## 3. The seam: what Rentile decides, what RenG owns (question 2)

At `0.6.0`, `LabelCandidateBatch` is `(candidates, layerStyles, atlas, contentKey, diagnostics)`
(`Api.kt:477-483`).

### Rentile decides

Everything the *style document* says, evaluated per feature:

- **Which features are labels at all.** Filter evaluation, zoom ranges, `text-field` formatting, text
  transform, font stack resolution (including a data-driven `text-font`).
- **Glyph identity and metrics.** `LabelGlyphEntry` (`Api.kt:193-200`) carries cell rect, provider
  bearings `left`/`top`, `advance`, `fontStackDigest`, `codepoint` — all in atlas pixels at a 24-pixel SDF
  em, with the 3-pixel SDF buffer inside the cell. The KDoc at `:165-183` is unusually careful about the
  buffered/unbuffered distinction and about `top` being measured from the **line's ascender**, not a
  baseline, and warns that `top` is positive for glyphs whose ink rises above the ascender.
- **Layout.** `LabelGlyphQuad` (`:253-257`) gives label-local cell corners with `text-anchor`, the
  effective em offset (`text-radial-offset` when positive, else `text-offset`), line breaking and
  per-line row positioning already applied. `scale` is `text-size / 24`.
- **The atlas.** `LabelGlyphAtlas` (`:203-230`) is `pngBytes` + `width`/`height` + `contentKey` + entries.
  RGB is forced to opaque white and the alpha channel *is* the signed distance field —
  `GlyphAtlasPacker.kt:209-223`, whose comment says this matches "the convention SDF sprites already use."
  **The shelf width is now 8192, not 4096** (`GlyphAtlasPacker.kt`, `SHELF_WIDTH_PX`), so any consumer
  assumption of a 4096 maximum is wrong at `0.6.0`.
- **Paint, per feature.** At `0.6.0` `color` and `haloColor` moved from `LabelLayerStyle` onto
  `LabelCandidate` (`:446`, `:448`), packed `0xAARRGGBB`, "because newly admitted road, water, POI, and
  other label layers use data-driven colors" (`:290-293`).
- **Collision *inputs*.** `boundingBox` (label-local, union of cell extents expanded by `text-padding`,
  `:275-278`), `padding`, `overlap`, `ignorePlacement`, `sortKey`, `avoidEdges`, `zOrder`,
  `LabelLayerStyle.priority` (style layer order, ascending, larger wins).

### Rentile leaves to RenG

Everything that needs a camera. The seam is drawn exactly at projection:

- **Position.** The only position on a candidate is geographic — `longitude`, `latitude` (`:411-412`).
  Nothing is projected and nothing is in screen coordinates.
- **`text-translate`.** Carried as `translateX`/`translateY` in **pixels**, applied to the *projected*
  anchor, not scaled by `text-size` (`:452-472`). At `0.6.0` it comes with `translateAlignment:
  SymbolAlignment` (`:473`), so RenG must decide whether it rotates with the map or stays viewport-fixed.
- **Collision, priority and ordering.** Rentile computes no placement. `SymbolOverlap.COOPERATIVE` is now
  a distinct third state (`:308-312`) and its negotiation — "blocked only by an already placed `NEVER`
  symbol," per Rentile's own migration sketch — is RenG's to implement. `SymbolZOrder` (`:322-326`) adds
  `AUTO`/`SOURCE`/`VIEWPORT_Y` ordering intent on top of layer priority.
- **Line placement, entirely.** `LabelPlacement.LINE` and `LINE_CENTER` (`:301-305`) hand RenG the full
  source line as geographic points (`line: List<LabelLinePoint>`, `:415`), a tangent hint
  (`rotationDegrees`, "clockwise from screen-space east", `:417`), a repeat distance (`symbolSpacing`,
  `:419`), a bend ceiling (`maxAngleDegrees`, `:426`) and `keepUpright` (`:420`). **The glyph quads are
  not bent to the line.** `LabelLayout.kt` at `87ccba2` contains no reference to placement at all
  (`grep -n "LabelPlacement\|placement"` returns nothing), so quads are laid out as a horizontal row for
  all three placements. Projecting the line, walking it at `symbolSpacing`, distributing glyphs along it
  and enforcing `maxAngleDegrees` is new RenG work and is the largest single item `0.6.0` adds to this
  cycle's scope.
- **Icon pixels.** `LabelIconRef.imageName` is "an opaque lookup key into sprite resources owned and
  resolved by the consumer; Rentile does not expose a public sprite atlas" (`:356-357`). Unchanged from
  `0.5.0` in ownership, expanded enormously in contract: `0.6.0` removes `anchorOffsetX/Y` (they could not
  survive `icon-text-fit` changing the icon's dimensions) and adds `anchor: LabelIconAnchor`, full paint
  (`color`, `opacity`, `haloColor`, `haloWidth`, `haloBlur`), `rotationDegrees`, `padding`, `optional`,
  `overlap`, `ignorePlacement`, `rotationAlignment`, `pitchAlignment`, `keepUpright`, `avoidEdges`,
  `textFit` and `textFitPadding` (`:371-395`). The ordering the KDoc prescribes — fit, then anchor, then
  offset, then rotate, then translate in the declared frame — is load-bearing and easy to get wrong.
- **Occlusion.** Unchanged and still undesigned: nothing in the batch says whether a label's anchor is
  behind a hill.

**Two seam facts that were not true a version ago and change how much RenG must build.** `0.6.0` moved
text colour onto the candidate, so a per-layer colour cache is wrong. And `overlap` replaced
`allowOverlap`, so the collision policy has three states rather than two, with the third requiring RenG to
consult the symbol it collided with.

---

## 4. `GLYPH_RANGE` and the firewall (question 3)

### What it is, and how it is requested

Rentile's ninth and still-last `ResourceClass` (`Resources.kt:4-14`, byte-identical between `0.5.0` and
`0.6.0`). One request is one 256-codepoint block of one font stack, as fontnik/Mapbox protobuf.

| Property | Value at `0.6.0` | Where |
|---|---|---|
| URL | `template.replace("{fontstack}", pctEncoded).replace("{range}", "$start-${start+255}")` | `GlyphResourceAcquirer.kt:230-232` |
| Font-stack encoding | RFC 3986 unreserved plus `,`; everything else percent-encoded over UTF-8 | `:239-247`, with a comment naming the `#`/`?`/`../` injections it exists to stop |
| Template origin | style root `glyphs`; absolute `http(s)` passes through, otherwise `resolveHttpReference(baseUri, ref)`, then `secretContext.protectUrl` | `StyleCompiler.kt:151-159` |
| `accept` | `application/x-protobuf` | `GlyphResourceAcquirer.kt:133` |
| Byte ceiling | `ResourceLimits.maxGlyphRangeBytes`, default **1 MiB** | `Api.kt:634` |
| Batch ceiling | `ResourceLimits.maxGlyphRangesPerBatch`, default **256** (was 64 at `0.5.0`) | `Api.kt:649` |
| Store key | `sha256Hex(url.withRedactedAuthenticationQuery())` paired with `GLYPH_RANGE` | `GlyphResourceAcquirer.kt:65, :104` |
| Write ordering | after the byte check, **before** decode — DEM-like | `:97-145` |
| Corruption | non-terminal: read → digest mismatch → `removeStore` → refetch | `:105-113` |
| `CACHE_ONLY` | throws `ResourceAcquisitionException` on the first uncached range; never touches transport | `:115-123` |

Note `protectUrl` (`SecretContext.kt:23-37`) only *wraps* the URL — it appends nothing. The glyph template
carries whatever credential the style document carried and no more, which is why RenG can compose a
matching template from the same bytes.

### What RenG must change

Six `when`s over RenG's own `ResourceClass` are `else`-less, so adding a twelfth constant (call it
`BASEMAP_GLYPH_RANGE`) forces exactly these edits and no others — verified by reading each:

| Place | Change |
|---|---|
| `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt:39-51` | the constant itself — **a public ABI addition**, so `kmp/api/kmp.klib.api` moves and the version bump must reflect it |
| `internal/firewall/RentileKeyDerivation.kt:94-108` | `BASEMAP_GLYPH_RANGE -> RentileResourceClass.GLYPH_RANGE`. `rengResourceClassOf` (`:130-135`) inverts this automatically and needs no edit. |
| `internal/ValueSupport.kt:52-67` | `maximumBytesFor` — which means **`ResourceLimits` gains a public field**, since no existing limit fits a 1 MiB protobuf |
| `internal/ValueSupport.kt:69-84` | `acceptValue` → `"application/x-protobuf"` |
| `internal/identity/ResourceKeyDerivation.kt:256-269` | `wireValue -> 12` |
| `internal/resource/ResourceOperationProtocol.kt:640-657` | `ordinaryResourceClassGates` → `null`; the engine acquires and decodes it, exactly as for the other seven engine-keyed classes |
| `internal/planning/FramePlanningCore.kt:261-275` | `isStaticDirect -> false` |

`OperationRegistry` itself needs **no structural change**. `engineTransportClassFor` (`:788`) delegates to
`engineKeyedResourceClassOf`, so both the transport index (`url`, engine class) and the store index
(`sha256Hex(redacted url)`, engine class) populate from the same one-line addition. `preregister`
(`:244-305`) already merges into the current snapshot rather than replacing it, and already treats an
identical repeat as idempotent and a conflicting one as `AMBIGUOUS_RESOURCE_ROUTE` (`:318-320`).

`EngineFailureClassification.kt:139-141` currently sweeps `FOREIGN_LABEL_CANDIDATE_PLAN`,
`LABEL_CANDIDATE_PLAN_CLOSED` and `GLYPH_TEMPLATE_MISMATCH` into `BASEMAP_RENDER_FAILED`, with a comment
at `:132-138` that says exactly what to do here: "When RenG does draw labels, these three move out of here
into shapes that name the label plan." That is a task, not a discovery.

### The one thing RenG must reproduce, and it is small

RenG's `BasemapStyleManifest` reads `sprite` (`:721`) and `sources` (`:875-890`, over every member of
`root["sources"]`) but **not `glyphs`** — `grep -n glyphs` over that file returns nothing. Adding it is
one line using machinery already present and already verified verbatim: `resolveHttpReference`
(`:540-568`) returns an absolute `http(s)` reference unchanged at `:541`, which is byte-for-byte the same
branch Rentile takes at `StyleCompiler.kt:154`. And `redactAuthenticationQuery` (`:159-175`) is the
redaction `glyphUrls` compares against.

Because RenG enumerates *all* declared sources rather than only those a draw layer names, the label
layers' `VECTOR_TILE` routes are already derivable — with one caveat in the next paragraph.

### Three genuinely open questions the design must settle

1. **Mid-invocation preregistration.** The sequence is: open one operation, preregister the draw routes and
   the label-layer tile routes, call `planLabelCandidates`, read `glyphUrls`, call `registerRoutes` again
   with the glyph URLs, call `acquireLabelCandidates(plan)`. ADR 0016's wording is "before the engine
   runs"; `registerRoutes` already bends that once for style-derived routes and documents why
   (`BasemapEngineHost.kt:175-188`). Whether the glyph round is the same bend or a different one is an ADR
   question, not a code question. Nothing about the mechanism has to change either way.
2. **Underivable sources.** RenG's route derivation *defers* a source it cannot derive
   (`DeclaredSources.underivable`) rather than rejecting the style. At `0.6.0` a label layer can reference
   a source RenG deferred; Rentile will happily plan against it, RenG will have no route, and every label
   tile fails closed with `AMBIGUOUS_RESOURCE_ROUTE`. Rentile's own new `LABEL_SOURCE_UNAVAILABLE`
   (`Diagnostics.kt`) covers the mirror-image case — *Rentile* cannot resolve the source — so the two
   failure sets are not the same set. Which tiles RenG asks for, and what it does when a label layer's
   source is one it deferred, is undesigned.
3. **The tile set.** `planLabelCandidates` takes its own `tiles` list. If it is not identical to the
   render tile set, the label tile URLs are a different set of routes, and `labelCandidateRequestKey`
   deliberately does not canonicalise `x`, so antimeridian world copies key apart.

### Does ADR 0016's erratum still describe the situation?

Mostly, and one clause is now wrong.

Still accurate: the `accept` value, the DEM-like write-before-decode ordering, the non-terminal
remove-then-refetch on digest mismatch, and the closing statement that the firewall's posture toward an
unenumerated class is to fail closed rather than special-case.

**Now wrong:** "It is reachable only through a new entry point, `acquireLabelCandidates(style, tiles,
resourceAccess)`, never through `prepare`, `prepareBatch`, or `render`." Since `0.5.0` there are two
overloads of `acquireLabelCandidates`, and the plan-taking one (`Api.kt:789`) is the one RenG would
actually call. The "never through prepare/prepareBatch/render" half remains true and is the load-bearing
half.

**Now incomplete:** the erratum reasons entirely about why enumerating `GLYPH_RANGE` without adopting the
entry point would be wrong. It has nothing to say about the case that now exists — adopting the entry
point — and in particular does not record that `planLabelCandidates` fetches `VECTOR_TILE` under the same
firewall. When E-labels lands, ADR 0016 needs a second erratum or a successor, not an edit.

---

## 5. What `0.6.0` delivered against RenG's glyph closure request (question 4)

### 5.1 The closure: delivered, and larger than the ask

Verified above (§2). The request asked for `(fontStack, rangeStart)` pairs and said composing URLs was
RenG's job; Rentile returned `GlyphRangeRef(fontStackDigest, rangeStart)` — identity only, the raw stack
deliberately withheld because `text-font` may be data-driven (`Api.kt:488-491`) — **and** `glyphUrls`,
which composes the URLs itself. The request's three "constraints that must hold" all hold: it fetches
nothing beyond the label tiles the acquisition needs anyway; it is deterministic and stable across runs
(`Api.kt:523-526`); and it cannot under-approximate, because both sides read one frozen list. This
landed in `0.5.0` and is unchanged in `0.6.0`; the erratum on the request document already recorded it and
is accurate.

### 5.2 The four operators: now present. Five, in fact.

The previous session's note that `!=`, `<`, `>` and `slice` were still absent was **correct for the pinned
version and is now obsolete.**

```
0.5.0  StyleExpression.kt:118   "==", "<=", ">=" -> compileComparison(operator, arguments)
0.6.0  StyleExpression.kt:118   "==", "!=", "<", "<=", ">", ">=" -> compileComparison(operator, arguments)
0.6.0  StyleExpression.kt:120   "slice" -> compileSlice(arguments)
0.6.0  StyleExpression.kt:123   "to-string" -> ToStringExpression(...)
```

with the evaluators at `0.6.0` `:464-469` and `compileSlice` at `:178-190`. `to-string` was not asked for
and came along. Rentile's coverage manifest records all five as `"evaluated"`
(`compatibility/rentile-v1-coverage.json`, keys `expression-greater-than`, `expression-not-equal`,
`expression-slice`, `expression-to-string`), and `tools/check_corpus_fidelity_policy.py:22-32` makes those
capability names a required part of the omission policy — so the claim is gated in Rentile's own CI, not
just asserted.

### 5.3 What a style using one does *today*, at the pinned `0.5.0` — and it is worse than "refuses"

Read from `StyleCompiler.kt` at `e423a78`, and the answer differs by layer type. This is the detail the
glyph-closure request got right for `0.2.0` and that its erratum only half-corrected.

- **In a symbol layer: silently drops that layer's text.** `compileLabelTextProgram` throws
  `StylePreparationException`; the caller catches it at `:242-259`, emits an **INFO**
  `UNSUPPORTED_TEXT_CONSTRUCT` diagnostic, sets `textProgram = null`, and keeps the descriptor. The style
  prepares. The basemap draws. The labels for that layer simply never exist.
- **In a fill, line, or icon layer: kills the whole style.** The same exception reaches the per-layer
  catch at `:465-476`, which records an **ERROR**-severity `UNSUPPORTED_RETAINED_CONSTRUCT`; `:479-484`
  then throws if any diagnostic is `ERROR`. Preparation fails and there is no basemap at all.

So the request document's "total outage" description is still exactly right for non-symbol layers at the
pinned version, and its erratum's "silently" is exactly right for symbol layers. Both halves are true; they
apply to different layer types.

**The consequence for RenG is that its own suite cannot see either.** `grep -rn '"symbol"' kmp/src`
returns **zero** matches — there is no symbol-layer style fixture anywhere in RenG's sources or tests. The
erratum said this and it is still true. Building E-labels means building that fixture family from nothing.

### 5.4 What `0.6.0` did *not* deliver, and one thing it made stricter

- **`text-variable-anchor` is still unsupported, deliberately.** `StyleCompiler.kt:1242` at `0.6.0`:
  "`text-anchor` with `text-radial-offset` is supported; choosing among variable anchors still requires
  viewport collision state." That is a correct division — choosing among candidate anchors is placement,
  which is RenG's — but it means RenG receives one fixed anchor and cannot implement variable-anchor
  placement even if it wants to.
- **Complex scripts are still excluded.** `internal/glyph/ScriptSupport.kt` is **unchanged** between
  `e423a78` and `87ccba2` (`git diff --stat` on that path is empty). Hebrew, Arabic, the Brahmic and
  South-East Asian abugidas and the rest still report `COMPLEX_SCRIPT_LABEL_EXCLUDED` and produce no
  quads. RenG inherits that and cannot fix it downstream.
- **Comparison typing got stricter, and this can newly break a style.** `0.5.0` skipped `requireComparable`
  entirely for `==` (`StyleExpression.kt:159-162`); `0.6.0` applies it to both operands and adds a
  same-type check (`:161-169`), with `==`/`!=` accepting only null/boolean/number/string/value
  (`:336-351`). A malformed or oddly-typed `==` that `0.5.0` accepted permissively now fails compilation —
  and by §5.3's second bullet, in a fill or line layer that is a style that stops preparing. Rentile's
  migration document states this outright. **This is the one real regression risk in the upgrade, and
  nothing in RenG's suite would catch it**, because RenG checks in no styles at all.
- **Nothing was said about a public sprite atlas, because RenG explicitly did not ask.** `0.6.0` confirms
  the answer: `LabelIconRef` "remains a reference, not a bitmap transport." RenG resolving `imageName`
  from the sprite pair it already fetches and validates stands as the plan.

---

## 6. Every `0.4.0` spike claim in §3.1–3.5 that no longer holds (question 5)

The spike is `docs/research/2026-08-21-rentile-upgrade-and-labels.md`. Sections and line numbers below are
its own. "Still holds" rows are omitted; §3.1's account of `acquireLabelCandidates`' five-step body,
§3.2's URL composition and store-key table, and §3.3's description of `LabelGlyphQuad`, `LabelBox` and the
buffered-cell convention are all still accurate.

| Spike claim | What it is now | Proof |
|---|---|---|
| §3.1 "The entry point" is `labelCandidateRequestKey` + `acquireLabelCandidates(style, tiles, mode)` | There are **four** label entry points; the one that matters is the `planLabelCandidates` / `acquireLabelCandidates(plan)` pair. | `Api.kt:753, :756, :771, :779, :789, :798` |
| §3.1 "It omits the glyph closure … a fact that turns out to be the crux for RenG" | Still true *of `labelCandidateRequestKey`*, and no longer the crux: the closure is available from the plan. | `Api.kt:766-769` vs `:528` |
| §3.2 batch limit "`maxGlyphRangesPerBatch`, default 64" | **256.** | `Api.kt:649` |
| §3.2 "All of this matches the `0.3.0` respike's measurements and is unchanged at `0.4.0`" | Still unchanged for URL, encoding, accept, store key, write ordering, corruption and the BMP ceiling; only the batch limit moved. | `GlyphResourceAcquirer.kt` unchanged in the `0.5.0→0.6.0` diff |
| §3.3 `LabelLayerStyle` carries `color` and `haloColor` | **Removed.** `LabelLayerStyle` is now `(layerId, zoom, priority)`; both colours moved to `LabelCandidate`. | `Api.kt:294-298` vs `0.5.0` `:293-299`; `:446`, `:448` |
| §3.3 `LabelCandidate` carries `allowOverlap` | **Replaced** by `overlap: SymbolOverlap` with a third state. | `Api.kt:441`, `:308-312` |
| §3.3 "carries a geographic anchor and everything else in label-local coordinates" | Still true of the geometry, but the candidate now also carries a full **geographic line**. | `Api.kt:415` |
| §3.4 item 5, "`LabelIconRef.imageName` … `anchorOffsetX`/`anchorOffsetY`" | `anchorOffsetX/Y` **removed**; `anchor: LabelIconAnchor` plus 17 further fields added. Ownership of sprite pixels unchanged. | `Api.kt:371-395` |
| §3.4 item 7, "**The firewall cannot preregister glyph routes**" and its three unattractive shapes | **False since `0.5.0`.** `glyphUrls` returns the exact URLs before any glyph fetch; exact-string preregistration is preserved. The "Rentile change" option in that list is the one that happened. | `Api.kt:553`, `DefaultBasemapRasterizer.kt:3543-3559` |
| §3.4 item 7's premise, "the range set is not knowable before the call" | Still true *before* `planLabelCandidates`; false after it. | `DefaultBasemapRasterizer.kt:567-597` |
| §3.5 "`symbol-placement: line` and `line-center` produce no candidates … no label follows a road. Point anchors only." | **Both implemented.** `LINE_PLACEMENT_LABEL_EXCLUDED` survives as a legacy code only. | `Api.kt:301-305`; `Diagnostics.kt` diff |
| §3.5 "`text-overlap: cooperative` collapses to `allowOverlap = false` and is **not recoverable**" | **Recoverable.** `SymbolOverlap.COOPERATIVE` is a distinct value. | `Api.kt:308-312` |
| §3.5 "`text-translate-anchor: viewport` is excluded … everything RenG receives is map-anchored" | **Carried**, as `LabelCandidate.translateAlignment`. | `Api.kt:473`; `StyleCompiler.kt:1161-1162` |
| §3.5 "one un-fetchable range fails the whole batch. With `maxGlyphRangesPerBatch = 64` and a measured worst case of 15 ranges" | All-or-error still holds. The measurement is superseded: Rentile's migration document records **159 ranges for Outdoor at Tokyo z14**, whose 8192×4357 atlas is 136.16 MiB decoded, and says a trial ceiling of 128 failed that valid plan. | `docs/migrations/0.6.0.md`; `GlyphAtlasPacker.kt` |
| §3.5 "Astral codepoints … dropped per-character" and the 23 excluded script ranges | **Unchanged.** | `ScriptSupport.kt` byte-identical across the range |
| §1.4 "`RENDERER_SEMANTIC_VERSION` is `rentile-renderer-3` at both … a caller's output-tile cache stays valid" | **`rentile-renderer-4` at `0.6.0`.** `PreparedStyle.digest` changes for the same style document, so `basemapTileKey` yields new values. Not a correctness break — RenG derives its own tile identity from that digest and both sides move together — but every cached tile identity is cold after the bump. | `StyleCompiler.kt:2103` vs `:2213` |
| §1.5(b) "the repository-policy gate, in **three** coupled places" | **Five**, and the catalog now pins **three** accepted fingerprints rather than two. See §7. | `tools/check_repository_policy.py:267-271` |
| §4, the cycle-order discrepancy | Settled: `CLAUDE.md` records E-labels as its own cycle, split on 2026-08-22, with H before G. Not re-litigated here. | — |
| §4's erratum, "`LabelPrimitive` does not exist in Rentile at any version" | Still true. | — |

---

## 7. Is upgrading the pin required, or merely available? (question 6)

**Required, on the evidence, and the argument is about scope rather than mechanics.**

E-labels *can* be built against `0.5.0`. The closure API is there, the firewall story works, `GLYPH_RANGE`
routes the same way. What it would produce is a map labelled with countries, states, cities, towns,
islands and archipelagos — because `PLACE_NAME_SOURCE_LAYERS` (`StyleCompiler.kt:544` at `e423a78`) is a
ten-name allowlist and everything outside it contributes no candidates. No street names. No POIs. No
labels following a road, because line placement is excluded. And the cycle would be written against
`LabelCandidate`, `LabelIconRef` and `LabelLayerStyle` in the shapes `0.6.0` then breaks — 29 removed ABI
lines, all in those three classes.

Against that, the cost of moving:

**No Kotlin changes.** `Exceptions.kt` and `Resources.kt` are byte-identical between `e423a78` and
`87ccba2`, so `EngineFailureClassification`'s `else`-less `when` over `RentileErrorCode` still compiles and
neither `ResourceTransport` nor `RawResourceStore` moves. RenG's complete Rentile import set (49 symbols,
`grep -rhn "^import com.rohittp.rentile" kmp/src | sort -u`) contains no `Label*` value type; the only
label symbols it touches are the three exceptions `EngineFailureClassification` already classifies. No file
RenG reproduces byte-for-byte appears in the `0.5.0 → 0.6.0` commonMain diff.

**Five coupled places, and the checker has moved since `HANDOFF.md` described it.** `HANDOFF.md:223-255`
is right that the fingerprints are SHA-256 over a token stream rather than whole-file digests, and its
recompute command works — but three of its details are stale, all in the direction of understating the
work:

| `HANDOFF.md` says | As the checker stands |
|---|---|
| fingerprints at `tools/check_repository_policy.py:245-246` | `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` is at `:256`; the catalog's entry is `:267-271` |
| `base_versions["rentile"]` at `:1849` | `:1900`, inside `check_dependencies` (`:1659`), which is where `HANDOFF.md` correctly says it lives |
| the catalog pins "**two** accepted fingerprints … only one can be recomputed from disk" | **three**, since Cycle H's ADR 0032 device-test form (`:262-263`). `kmp/build.gradle.kts` likewise pins three, but those do **not** carry the Rentile version — only the catalog's three move on a bump. |
| "the other must be reconstructed by stripping the coroutines lines first" | Unnecessary. All three are reproducible from git history by taking the historical `gradle/libs.versions.toml` and substituting the Rentile version token. |

That last row is worth stating as a recipe, because it turns the trap into arithmetic. Running the
fingerprint function over every historical revision of the catalog with the Rentile token normalised to
`0.5.0` reproduces all three accepted values exactly, with no residue:

```
c79cc53420a717078f4f6b15aba2cd6120d9ab431496ea1c660b8e037354ed7e  from caa2e47  (ADR 0032 form, on disk)
7eb2e12a272e4b1eb7e48dc62e27eced1e76f873b84cb7ac036b67934de7d59f  from 0f385c7  (ADR 0019 coroutines form)
c53967f3a738a29f39817a93be0090ba96884c90b173591b021d32c2d30a71b9  from 100e583  (pre-Cycle-C form)
unmatched expected: []
```

and the same computation with the token set to `0.6.0` gives the three values a bump would need:

```
b9756bb7805cbdcd9c173835c961a50a6aa47ef0cd717137083c1c323d6c9e87  from caa2e47
174937a0a8c0da9da51654064fb15b4d491cfd3f38349424f4b39d2f6e91ad92  from 0f385c7
d6bc6efd37d55b0b9c46c9b72fa19f28350a102c80996dc26c4b89f794a8d795  from 100e583
```

These are offered as a working recipe, not as a value to paste blind: recompute them at the moment of the
bump, because any other token change to the catalog in between invalidates all three. The remaining two
places are `base_versions["rentile"]` (`:1900`) and the two test fixtures,
`tools/tests/test_check_repository_policy.py:271` (a fixture catalog) and `:1447` (a mutation test that
rewrites `rentile = "0.5.0"` to `9.9.9`).

**What upgrading costs beyond that, and it is not nothing.** Two consequences follow from `0.6.0` even for
a RenG that draws no labels:

1. **`PreparedStyle.digest` changes**, so every `basemapTileKey` is a new value. Correctness is unaffected —
   both sides of the derivation move together — but any RenG test pinning a digest literal fails, and a
   consumer's rendered-tile cache goes cold once.
2. **A style that prepares today can stop preparing.** §5.4's stricter comparison typing reaches fill and
   line layers, where a rejected construct is an ERROR that fails the whole style
   (`StyleCompiler.kt:479-484`). Rentile gates this against its own rolling map-catalog corpus
   (`tools/check_corpus_fidelity_policy.py`, `compatibility/rentile-v1-coverage.json`), which is real
   evidence — but it is evidence about Rentile's corpus, not about the 34 styles RenG's visual harness
   ships. **RenG's own suite cannot detect it**, because RenG checks in no style documents. The honest
   verification for the bump is a harness run across all 34 styles before and after, comparing which
   styles prepare — the same instrument that found four defects the passing suite missed in E-basemap.

**Recommendation, offered for argument.** Bump the pin to `0.6.0` as its own commit before the E-labels
design is written, not during it — the same sequencing argument the `0.4.0` spike made and the owner took:
a bump landed alongside the feature turns any failure into "is it the version or the feature?" The
difference from last time is that the verification is not just a test run. It is a test run **plus** a
34-style harness pass, because §5.4 names a real regression path that RenG's tests structurally cannot see.

---

## 8. What this document did not verify

- **No build was run and no test was executed.** The claim that `0.6.0` compiles against RenG unchanged
  rests on: `Exceptions.kt` and `Resources.kt` byte-identical (diffed); the 29 removed ABI lines all
  belonging to three types RenG does not import (ABI-diffed and import-grepped); no implemented interface
  gaining a member (`ResourceTransport`/`RawResourceStore` are in the unchanged `Resources.kt`). It is a
  strong inference, not a green build. **What would settle it:** bump the catalog in a scratch worktree and
  run `./gradlew --no-configuration-cache :kmp:checkKotlinAbi :kmp:testAndroidHostTest :kmp:macosArm64Test`.
- **Nothing proves published `0.6.0` was built from `87ccba2`.** Rentile has no completion-record scheme,
  so the link between the source read here and the bytes a bump would resolve is circumstantial (§1).
  **What would settle it:** resolve `com.rohittp.rentile:kmp:0.6.0` into a scratch Gradle home and compare
  the resolved klib's ABI against `git show 87ccba2:kmp/api/kmp.klib.api`. The same gap exists for the
  pinned `0.5.0` and has never been closed; the strongest evidence there is that RenG's own suite compiles
  against three exception classes that only exist in `0.5.0` source.
- **No runtime measurement of any label call.** Everything about `planLabelCandidates`, `glyphUrls` and the
  closure's exactness is read from Rentile's source and KDoc. No counting stub was run against `0.5.0` or
  `0.6.0`, the way `docs/research/2026-08-19-rentile-030-counting-stub-respike.md` was run against `0.3.0`.
  **What would settle it, and what I would do first in this cycle:** a counting-stub spike that opens one
  `BasemapEngineHost` operation, preregisters label-tile routes, calls `planLabelCandidates`, reads
  `glyphUrls`, preregisters those, calls `acquireLabelCandidates(plan)`, and asserts that every URL the
  transport saw was preregistered and every preregistered glyph URL was requested. That single spike would
  convert §2's central claim from "read" to "measured", and it is the cheapest de-risking available.
- **The corpus impact is unquantified.** How many of the 34 styles the harness ships gain labels at
  `0.6.0`, how many font stacks a real viewport needs, and whether any of them trips the stricter
  comparison typing are all unmeasured here — the style URLs carry the owner's key and none is checked in.
  Rentile's `compatibility/rentile-v1-coverage.json` is evidence about *Rentile's* corpus, which overlaps
  RenG's but is not it. **What would settle it:** a harness run, per style, recording whether the style
  prepares and how many candidates and ranges a representative viewport produces.
- **The `0.6.0` migration document was read but not audited.** Where its claims mattered they were
  re-derived from source and are cited that way; the paired-icon arithmetic (`icon-text-fit` before anchor
  before offset before rotation before translate) and the cooperative-collision sketch were **not**
  independently checked against `LabelCandidateAssembler.kt`.
- **The `0.4.0` spike's §1.1 file-granularity reproduction check was not repeated.** It was not needed for
  this range — none of those files appears in the `0.5.0 → 0.6.0` diff — but that is a weaker guarantee
  than re-comparing them, and it says nothing about the `0.4.0 → 0.5.0` step, which `0f385c7` handled and
  which is outside this document's range.
- **RenG's rendering side is deliberately absent.** The SDF shader, the per-glyph batch, screen-space
  collision and priority policy, occlusion against the 3D scene, and where an engine-derived label stack
  sits relative to consumer stickers under ADRs 0024/0027/0030 are all still open, and none of them is
  answered here.
