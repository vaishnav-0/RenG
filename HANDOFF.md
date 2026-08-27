# RenG handoff — 2026-08-23

The recovery point for whoever picks RenG up next. **RenG draws a map.** `createRenderer` is published
API, `VERSION_NAME` is `0.3.0`, and the released library renders a real Rentile ground from a
caller-supplied style.

**Three releases are public and all three are proven, not assumed.**

| Version | Source commit | Carries | CI / publication runs |
|---|---|---|---|
| `0.1.0` | `af92901b2ef045078b855a6b47533bc95aca6886` | Cycle A | `31968682132` / `31968682290` |
| `0.2.0` | `a2cbe6a965247f221f7e279a962b40306baac21b` | Cycles B, C, D, F-1 | `32314778617` / `32314778594` |
| `0.3.0` | `49cc1d5843ba40ac866f96d00123ca07b662f6fc` | E-basemap | **not recorded anywhere in this repository** |

Each completion record at `com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json` verifies
anonymously over `https://maven.rohittp.com`. `0.3.0`'s names source commit `49cc1d5…` and manifest digest
`49101f2d5ac66f96d60d6ea580b173bf051da8fa58b680d74fe50476cf4cdbf1`; aggregate metadata lists all three
versions with `0.3.0` as `<latest>` and `<release>`; all seven publications resolve over public HTTP with
no credentials. `0.3.0`'s version was declared explicitly in `6e7c023` rather than left to the resolver,
which would have advanced to `0.2.1` and understated the release. Its run IDs were absent from every file,
commit message and ledger; they are recorded here **from direct observation at release time** — CI
`32621399551` and publication `32621399566`, both against `49cc1d5` — alongside an anonymous check of the
completion record and all seven POMs. They are written down because they were watched, not recovered
afterwards: a later reader should treat a run ID that appears without that provenance as unverified, and
should not call the GitHub API and then record the answer as though it had always been here.

The first `0.3.0` attempt, run `32597181882` on `806007d`, **failed closed** before any R2 step — every
one of them is `skipped` in that run — which is why `0.3.0` was retried at the same version rather than
bumped.

**`main` is five commits ahead of `origin/main`, and the difference is code.** `origin/main` is exactly
`49cc1d5`, the released commit. Local `main` is `00464c2` and adds ADR 0028's per-role GLB accessor gates
(`24f36f0`, merged by `fd8bb00`) and the Rentile `0.5.0` bump (`0f385c7`, merged by `00464c2`); of the
five, only `2976825` is documentation. `publish.yml` ignores `docs/**`, `**/*.md` and `LICENSE` and nothing
else, so **pushing `main` as it stands cuts a release**, and the resolver will select `0.3.1` because
`0.3.0` has a valid completion record. Decide that deliberately in the same push, or do not push.

**Housekeeping is done.** Two worktrees (`RenG` on `main`, `reng-rentile-050` on `feat/rentile-0-5-0`), two
local branches, one remote branch. The working tree is clean.

## Read first

1. `CLAUDE.md` — repository constraints, purity contract, six targets, commands, publication rules.
2. `CONTEXT.md` — canonical vocabulary. Read it before naming anything.
3. `docs/adr/0001`–`0028` — newer ADRs override older prose. The two newest: **0027** supersedes ADR 0025's
   depth-*write* ruling (no map-regime draw writes depth; declaration order becomes the whole rule inside
   the regime rather than only its tie-break) and **0028** narrows ADR 0021's flat accessor subset to a
   per-role one. ADR 0025 carries an in-place amendment banner pointing at the sentence 0027 overturns;
   ADR 0016 carries an appended erratum for Rentile's ninth engine class.
4. `docs/decomposition.md` — the cycle sequence and each cycle's gates.
5. `docs/superpowers/specs/` and `docs/superpowers/plans/` — the A, B, C, D, F-1 and E-basemap pairs are
   now all historical decision records. Cycle C's plan in particular is **wider than what shipped** (tasks
   1–13 and 15 shipped in `0.2.0`, five travelled to E-basemap and one to E-terrain), so read the plan for
   what a task number covers and this document for what shipped.
6. `.superpowers/sdd/2026-08-20-cycle-e-basemap/progress.md` — the basemap cycle's per-task ledger, and the
   densest record of why that cycle looks the way it does. **Two warnings.** It is git-ignored
   (`.gitignore:18`), so it is evidence rather than committed record and a clean checkout loses it. And it
   **stops before the cycle's last three fixes** — its final heading still reads "STILL BLOCKING THE
   RELEASE: 25 of 48 frames fail", which `a29ee8d` closed. For those three, the commit messages are the
   record.
7. `docs/research/` — the findings documents. Read the relevant one before touching the area it covers.
   Newest first: the glyph-closure request to Rentile (**partly superseded — see "E-labels" below**), the
   consumer model corpus check, the Rentile upgrade-and-labels measurement (**taken against `0.4.0`, and
   the pin is now `0.5.0`**), the golden-image gate design, and the F-2 glTF-capability and
   draw-regime/occlusion spikes.

Approved specifications and plans are not reopened without repository-owner review.

## `0.3.0` failed closed once, and the cause was the runner's driver

Worth a section because it cost a cycle and because the failure mode is not obvious.

The first attempt failed on the hosted macOS runner with `kotlin.AssertionError at null:-1` as the entire
diagnostic, and passed on every developer machine. **A hosted GitHub macOS runner has no GPU: it runs
`Apple Software Renderer`, whose rasteriser drops quads that reach far outside the viewport** — which is
the shape every basemap ground tile has. Selecting that renderer locally through `kCGLPFARendererID`
reproduces it exactly: 3,005 of 15,876 interior pixels untouched, the south-east tile gone and the
south-west tile cut on a straight diagonal. It is not RenG. Replaying `drawGround`'s own four matrices with
a solid-colour shader in **about eighty lines of C, with no RenG code in the process**, reproduces it, and
the same draw is correct on Apple's GPU and on Linux llvmpipe, which is why the Ubuntu job passed. The
behaviour is deterministic but erratic in the projection's clip `w`, so no camera or frame size dodges it.
*(The C program is not in this repository and was never committed; that claim is testimony recorded in
`8daef8e`'s message and in `BasemapReadbackSuite.kt:646-650`, not an artifact you can re-run.)*

**The suite now measures the driver instead of naming it.** `measureLargeQuadRasterisation`
(`kmp/src/nativeTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt:669`, called from `:100`) compiles its
own pass-through program, draws the fixture's own four ground-tile footprints at the fixture's real clip
`w` and NDC depth, and counts pixels disagreeing with the analytic rectangle: **0 on Apple M3 Max, 2,112 on
`Apple Software Renderer`**, against a 512-pixel boundary budget. When the probe distrusts the driver
exactly one case skips out loud — `assertGroundCoversTheFrameInTheFixturesOwnArrangement`, whose every
assertion is "the ground reached this pixel" — and the other four of five still run and still pass. That
case keeps its coverage on Ubuntu's llvmpipe job. **A probe that cannot run fails rather than skips**, so
the gate cannot be lost quietly. `theBasemapReadbackSuitePassesOnAppleSoftwareRenderer`
(`kmp/src/macosTest/.../MacosGlConformanceTest.kt:51`) runs the whole suite against Apple's CPU rasteriser
from a developer machine by requesting `kCGLRendererGenericFloatID`, so "it passes locally" means something
again. **This retires the Cycle D note that said the hosted runner's no-GPU fallback was verified nowhere.**

Every exact pixel count in the suite now carries a budget derived from the geometry that produces it, not
tuned until a run passed: the ground may miss 256 interior pixels (two tile seams, one pixel of crack
each), so the smallest loss it still detects is 1.6% of the frame against the 100% and 13% it exists to
catch; a coplanar geometry may lose 1% of its ground-covered pixels against the 100% and 99% erasures ADR
0027 removed. Both measured at zero deviation on three rasterisers.

**And Gradle never prints a Kotlin/Native assertion message** — the console gets the exception class and
location and nothing else, which is why the failure said `null:-1`. `tools/print_test_failures.py` walks
Gradle's own JUnit XML and prints what the console withheld; it runs as an `if: failure()` step on all four
test-running jobs across both workflows, beside an `upload-artifact` of the reports. It is a diagnostic and
never fails.

Nothing reached R2 — the failing task precedes the exact-key collision check, the upload, and the
conditional record write — so the same version was retried. That is ADR 0013 working, for the second time.

## What F-2 inherits — read this before planning it

Five things are open, three of them found by looking at rendered frames rather than by any test. **Two are
folded into F-2 by owner decision.** Items 1 and 3 are recorded nowhere in the committed tree — only in the
git-ignored cycle ledger — which is why they appear here in full rather than as a pointer.

**1. The composite double-multiplies alpha. Folded into F-2.** `GlFrameDrawer.kt:107` sets
`blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` for the pass that
draws the offscreen colour attachment onto the caller's framebuffer — but that attachment already holds
**premultiplied** content by construction: `GlTextureUpload.kt:57-59` premultiplies every
`TextureContent.IMAGE` at upload, and both `GroundPipeline.kt:179` and `StickerPipeline.kt:194` draw into
the offscreen with the matching premultiplied `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`. So RGB is multiplied by
alpha twice. Note the asymmetry that gives the bug away: the *alpha* source factor at `:107` is already
`GL_ONE` and is correct; only the RGB factor is wrong, which is the fingerprint of a straight-alpha
function pasted where a premultiplied one belongs. Invisible under an opaque basemap; wrong the moment a
consumer composites RenG's output over their own background. A second, distinct hole in the same story:
`drawGeometry` establishes **no** blend state of its own (`SceneContent.kt:190-193` says so deliberately,
because it runs a consumer's shader pair), so consumer geometry output is not reliably premultiplied
either.

**2. ADR 0027 left the map regime with no intra-regime occlusion, and F-2 reopens it.** The ADR states the
cost in its own text: *"Map-regime content can no longer occlude anything."* That is correct for flat quads
and billboards, and it is the right trade for the two defects it closed. It is not correct for a model: a
model has volume, must occlude and be occluded, cannot self-occlude without depth writes, and cannot be cut
by a hill or by another model. Declaration order is the only ordering left. The ADR anticipates this —
*"a model pipeline that writes depth is a deliberate amendment to this ADR rather than an oversight"* — and
`SceneContentTest.noDrawInAWholeSceneRunsWithDepthWritesOn` walks the **whole** call log rather than
checking three pipelines, so a model pass that writes depth fails an existing test by design. **F-2 must
amend the ADR, not just call `depthMask(true)`.** The one production `depthMask(true)` in the tree is
`GlFrameDrawer.kt:86`, which exists only so the per-frame depth clear takes effect.

**3. The pitched far field is worse after the LOD fix, and the remedy needs an ADR.** Measured at 53.3°
pitch: 8.48 ground pixels per screen pixel, so **5.1 texels per screen pixel before the fix and 10.2
after**. The 10.2 is arithmetic rather than a second measurement — the fix moves that frame one LOD finer
and halves `screenPixelsPerTexel` from its 1.663 peak, so the far-field ratio doubles. The structural cause
is that **LOD selection is pitch-blind**: `observeMercatorLod(zoom, previousSelectedLod)`
(`MercatorLod.kt:46-49`) takes no pitch, so one level serves near and far field alike. The remedy is
mipmaps plus either a shader-side LOD bias or anisotropic filtering — a naive `LINEAR_MIPMAP_LINEAR` would
undo the sharpness fix in the near field, and GLES 3.0 has no `GL_TEXTURE_LOD_BIAS`. Two facts make that
larger than it sounds: no mipmap chain is generated anywhere (`generateMipmap` is on the seam and
implemented on all four platforms with **no production caller**, and
`GlTextureUploadTest.neitherContentKindGeneratesAMipmapChain` pins that), and `GL_TEXTURE_LOD_BIAS` is a
*float* texture parameter while the seam has only `TEX_PARAMETERI`/`SAMPLER_PARAMETERI` — so setting it
means widening the seam across four platform bindings, not adding a constant. A never-magnify rule is one
constant away (0 of 48 frames magnifying) but renders 26% thinner than styled, roughly doubles tile count,
and pushes the far field to 20 texels per pixel.

**4. `SCREEN` scale on a `MAP`-positioned placement is perspective-divided**, contradicting `CONTEXT.md:210`
— *"screen-anchored scale is output pixels per local unit"*. `PlacementResolver.kt:110-114` passes a
`SCREEN` scale through raw, but the draw regime is chosen from `positionMode` alone
(`PlacementResolver.kt:52-85`), so a `MAP` position sends it down `composeMapModelViewProjection`, where
`SceneContent.kt:384-391` applies the scale in camera space and `camera.projectionMatrix`'s bottom row
(`CameraMatrices.kt:81`, `0, 0, -1, 0`) then divides by `w`. The identity holds at exactly one depth. The
screen path never has this problem because `screenOrthographicProjection` (`SceneContent.kt:479-486`) has
bottom row `0, 0, 0, 1`. Measured: a 64px image at scale 1.0 renders 41px at one frame and 28px at another.
`CONTEXT.md:198` explicitly sanctions the mixed case, so this is an unresolved semantic rather than
obviously a bug — but F-2 is where somebody first sees it, on a model at altitude or under pitch.
Tracked at `docs/research/2026-08-21-f2-draw-regime-and-occlusion.md:188-197`.

**5. New, and E-labels' problem: 20 of 34 verified styles silently lose their map text.** Rentile's
`compileNode` supports 24 operators and lacks `!=`, `<`, `>` and `slice`; measured across the 34 styles
Rentile is verified against, **20 carry one of them — `>` in all 20 — and every occurrence is inside a
`symbol` layer** (`docs/research/2026-08-22-rentile-glyph-closure-request.md:139-140`). At the pinned
`0.5.0` this **degrades rather than failing**: `StyleCompiler.kt` catches the preparation exception, the
layer's `textProgram` becomes `null`, the descriptor survives, and an **INFO** `UNSUPPORTED_TEXT_CONSTRUCT`
diagnostic is emitted — Rentile's own ADR 0026 rule that no style which prepared before may fail to prepare
after. **But RenG reads no Rentile diagnostic anywhere.** That is deliberate, not an oversight:
`EngineFailureClassification.kt:50-52` records that the engine's `diagnostics` are never read because
`RenderDiagnostic` carries free-form `details` that ADR 0016's redaction rule forbids forwarding. RenG
holds the `PreparedStyle` (`BasemapEngineHost.kt:278`, stored at `:289`) and never touches its
`diagnostics`; it also passes `MetricsSink.None`. **So those 20 styles are in that state today and no
consumer can find out.** Surfacing it is a design decision — forwarding `code` and `severity` without
`details`, say — not a one-line change.

> **RenG's own record of this is out of date, and now says so.**
> `docs/research/2026-08-22-rentile-glyph-closure-request.md:150-157` has a heading reading "Why it is a
> total outage rather than a degradation", on the reasoning that `failRetained` throws and that Rentile
> `0.2.0` does not compile symbol layers at all. Both premises were true when written and neither holds at
> the pinned `0.5.0`. That document now carries a dated erratum recording the change; read the section
> above it as history. Note also that there is **no symbol-layer style fixture anywhere under `kmp/src/`**,
> so RenG's own suite detects neither the old behaviour nor the new one.

## The Rentile pin is `0.5.0`

Bumped in `0f385c7` from `0.2.0`. RenG adopts the pin and **none** of the feature: it still never calls
`acquireLabelCandidates`, never enumerates `GLYPH_RANGE`, and still passes `TileSubstitutionPolicy.Disabled`
with its own three-value `ResourceAccessMode`, so Rentile's new `CACHE_SUBSTITUTE_THEN_NETWORK` is
unreachable. `kmp/api/kmp.klib.api` did not move.

**`RentileErrorCode` is no longer unchanged** — an earlier version of this document said it was, on the
strength of a `0.4.0` spike. `0.5.0` appends three codes, none removed or reordered:
`FOREIGN_LABEL_CANDIDATE_PLAN`, `LABEL_CANDIDATE_PLAN_CLOSED`, `GLYPH_TEMPLATE_MISMATCH`. All three broke
`EngineFailureClassification`'s deliberately `else`-less `when`, which is the firewall working as designed,
and all three join the fail-closed `BASEMAP_RENDER_FAILED` / `BASEMAP_RENDER` bucket with no diagnostic —
because RenG has no label stage to attribute them to and inventing one would claim work it has not built.
The second break: `mapsEveryEngineResourceClassOntoARenGResourceClass` asserted no engine class maps to
`null`, which `GLYPH_RANGE` now does; the unmapped set is now asserted to be exactly `GLYPH_RANGE`, and
that test's injectivity assertion had to change too, because once a `null` enters the map, counting
distinct values against the entry count passes for free.

**The byte-for-byte reproduction was re-proved by hashing, not by reasoning**, against Rentile `d899cb2`
(`VERSION_NAME=0.5.0`). Of the 21 files RenG reproduces or depends on, 19 are byte-identical to `0.2.0`,
including `ContentIdentity.kt`, `raster/RasterResource.kt`, `mvt/VectorSource.kt`,
`metadata/TileJsonResourceAcquirer.kt`, `SecretContext.kt` and all four acquirers (raster, vector, TileJSON,
GeoJSON). The two that differ are accounted for: `Exceptions.kt` (the three appended codes) and
`sprite/SpriteResourceAcquirer.kt`, which changed only by extracting its three store wrappers into a shared
`RawResourceStoreAccess.kt` with identical messages — **`appendSpriteExtension` is byte-identical inside
that refactored file**. This record lives only in `0f385c7`'s commit message; no research document covers
`0.5.0`, and `docs/research/2026-08-21-rentile-upgrade-and-labels.md` is a `0.4.0` measurement.

### A version lives in five coupled places, and the fingerprints are not what this document used to say

All five must move in one commit or `check_repository_policy.py` fails closed:

1. `gradle/libs.versions.toml`
2. and 3. **both** `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` entries for that catalog
   (`tools/check_repository_policy.py:245-246`)
4. `base_versions["rentile"]` (`tools/check_repository_policy.py:1849`) — a local declared at `:1845`
   inside **`check_dependencies`** (`:1633`), *not* inside `_dependency_name_policy_token`, whatever the
   checker's own comment at `:242-243` says
5. two fixtures in `tools/tests/test_check_repository_policy.py` (`:271` and `:1249`)

**Those fingerprints are SHA-256 over a token stream the checker itself produces, not whole-file SHA-256.**
Earlier text here said whole-file, and anyone following it would recompute the wrong value.
`_build_configuration_fingerprint` (`tools/check_repository_policy.py:841`) tokenizes through `_toml_tokens`
or `_kotlin_fingerprint_tokens` and feeds `_token_stream_fingerprint` (`:831`). The mechanism is therefore
insensitive to comments and whitespace — reformatting a build file needs no bump; changing a token does.
There is no dedicated command; recompute by importing the private function:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -c "
import sys, pathlib
sys.path.insert(0, 'tools')
from check_repository_policy import _build_configuration_fingerprint as f
for r in ('build.gradle.kts','gradle/libs.versions.toml','kmp/build.gradle.kts','settings.gradle.kts'):
    p = pathlib.Path(r); print(f(p, p.read_text(encoding='utf-8')), r)
"
```

**The trap that command does not solve:** `gradle/libs.versions.toml` and `kmp/build.gradle.kts` each pin
**two** accepted fingerprints — the current form and the pre-Cycle-C coroutines-free form. Only one can be
recomputed from disk; the other must be reconstructed by stripping the coroutines lines first. Both carry
the Rentile version, so both move on every bump.

## E-basemap, as released in `0.3.0`

Its authority is `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md` and
`docs/superpowers/plans/2026-08-20-cycle-e-basemap.md`.

**What it ships.** The Rentile firewall and its production private-key resolver, in `internal/firewall/` —
`FirewallTransport`, `FirewallStore`, `OperationRegistry`, `RentileKeyDerivation`,
`EngineFailureClassification`, `BasemapEngineHost`. One basemap engine per renderer, with rendered-tile
identity derived by RenG's own `basemapTileKey` under ADR 0018 rather than by a structural triple match.
Sprite-pair and basemap-style commits, with a rendezvous on the firewall write path so a half-written
sprite pair cannot poison a record. Pure basemap route derivation from a real compiled style, with an
underivable source *deferred* rather than the whole style rejected. Style compilation bound to the exact
bytes the frame is committing, and a refusal when a compiled style's bytes are not the resident ones. The
frame's style manifest parsed once and cached by content digest instead of twice. Tiles rendered through
the engine the frame compiled, decoded and uploaded at draw rather than at prepare, so a tile whose GL
texture is still resident costs neither. Sources that declare their tiles **by reference** through a
TileJSON document, which turned out not to be an edge case at all: across the owner's 34-style corpus, 96
sources use the reference form against 2 inline, and all 34 need at least one — before that landed, the
ground drew for none of them. And the ground itself, in `internal/gl/GroundPipeline.kt`, with each
instance's unwrapped x read so N Mercator world copies land in N places while sharing one texture.

**Public API growth is exactly one field**: `ResourceLimits.maximumResidentGpuTextureBytes`, appended last
so no `componentN` shifted, defaulting to 128 MiB (a canonical 512×512×4 tile is exactly 1 MiB). It was
**inert for most of the cycle** — `RendererFactory` constructed `GlObjectRegistry()` with the class default,
so the public knob had no effect whatsoever — and the ground-draw task found it while wiring texture
leases. It is now threaded from the caller's configuration at `RendererFactory.kt:115`. A public field
whose only test constructs the registry directly is a field with no wiring; that is the shape to watch for.

**Four defects were found by rendering frames and looking at them, not by the passing suite.** All four are
fixed and released; they are kept here for the shape, because it is the cycle's main lesson.

1. **A blank basemap.** 45 of 48 frames a completely flat rectangle of clear colour, 100% undrawn, every
   one reporting `BASEMAP_RENDER_FAILED`, while the test suite was green. The diagnosis that looked
   obvious — memory visibility on `OperationRegistry`'s route indices — was wrong, and measuring found the
   better answer: Rentile's rasterizer reads `currentCoroutineContext().job` on every entry point, the
   harness resumed RenG's suspend `prepare` from `EmptyCoroutineContext` (legal Kotlin), so `prepareTiles`
   threw `IllegalStateException("Current context doesn't contain Job")`. That also explained the symptom
   the wrong theory was built on: with no job the cancellation link is never made, so the worker runs
   detached and calls back into a *later* frame's genuinely empty registry. The race was real too and was
   reproduced independently (12/12 JVM runs failed before, 0/15 after); a fifth unguarded index,
   `spriteMemberRoutes`, was found whose comment claimed it needed no guard for the same wrong reason. All
   five now live in one immutable `RouteIndex` snapshot behind `@Volatile`, writers serialised, readers
   lock-free. Fixed in `7fb4a75`.
2. **A map-anchored billboard bisected at any nonzero pitch**, and **3. a coplanar altitude-0 `Geometry`
   z-fighting the ground.** One mechanism closed both — ADR 0027 — and a measurement is what killed the two
   plausible alternatives: with `drawBasemap = false` the billboard is *still* cut in half, because the
   consumer's own altitude-0 `Geometry` writes the same plane depth, which ruled out anything scoped to the
   ground; and both `glPolygonOffset` and a shader-side bias displace by a bounded, slope-scaled amount
   while the billboard's deficit grows with its own screen height, needing a factor near 32. Fixed in
   `1281f74`. Both were invisible to 942 passing tests because every fixture used a **pitch-0 camera** —
   the one camera where the two surfaces land on bit-identical window depth. ADR 0025 had verified that
   bit-identity and recorded it as evidence *for* its decision; it was also exactly what hid the gap.
4. **A soft ground, traced to LOD hysteresis lagging toward coarser.** `screenPixelsPerTexel` is exactly
   `2^(zoom - lod)`; the ground was magnifying on 33 of 48 frames, peak 1.663. The ±0.75 hysteresis band
   lagged, and on zoom-in lag is always toward coarser, so the same camera rendered at two sharpnesses
   depending on how it got there (at zoom 11.713: historyless LOD 12, remembered LOD 11 — a factor of two).
   Hysteresis may now only err toward *more* detail; band width is unchanged so thrash protection is
   unchanged. 14 of 44 basemap frames got sharper, mean −29.5%, best −40.5%; the other 30 byte-identical.
   Fixed in `212e8ce`, and it is what left the far field worse (see F-2 item 3).

A fifth, of the same class, was found on the owner's *real* styles after the first four: a tile server
answers `204 No Content` with a zero-byte body for every tile of a source with no data there — MapTiler's
`ocean` source does it for every inland tile — and the firewall's `writeStore` refused those zero bytes as
an **integrity** violation, throwing `STORE_WRITE_FAILED` out of Rentile's own acquirer, cancelling the
whole batch scope, and failing the entire basemap for that frame behind an opaque `BASEMAP_RENDER_FAILED`.
It is not an integrity violation: the latched-digest check above it already proved these are the bytes
RenG's own transport returned, so an empty body is a **content** verdict and now declines the cache rather
than failing. 23 of 48 frames drew before, 48 of 48 after. Fixed in `a29ee8d`. Every genuinely-integrity
rejection still throws.

**The gate is analytical readback, with no stored baselines** — 128×128 through the public API
(`createRenderer` → `prepare` → `draw`), the whole frame read back, and relationships asserted: no interior
pixel is the target's own colour, four named samples carry four fixture colours, the four quadrant means
stand in a strict order, and `drawBasemap = false` leaves the frame untouched. ADR 0027 added two sweeps —
a coplanar quad across five pitch-and-bearing pairs requiring that every pixel the quad paints alone and
the ground covers alone is still the quad's colour in the combined frame, and a billboard at five pitches
required to match its pitch-0 pixel count exactly. The camera is the asymmetric `(-55, -135)` zoom-4 one
already proved disjoint from its own transpose, and each tile's 2×2 source has four distinct texels so a
u-flip or v-flip shows a decoy. This catches the entire quiet-and-plausible class — transposed tile index,
wrong LOD, flipped texture, silently empty ground, dead `drawBasemap` flag — and tells nobody whether the
result *looks* right. That is Cycle J's job, and **all pixel verification is deferred to Cycle J by owner
decision** (`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`).

**Verified where, and only where.** Last measured in this checkout: **931 Android host / 966 `macosArm64`,
no failures, none skipped**; 84 Python tests and the repository policy check pass. Basemap rendering itself
is exercised on `macosArm64Test` and `linuxX64Test` only, and that is measured rather than scheduled: those
are the only two test tasks that can hold a GL context, and **Android's host tests cannot execute Rentile's
Skia rasterization at all**, which was established with error codes rather than assumed. All six targets
publish at every release; which of them anyone has executed belongs in release notes.

**Not in this cycle, and not implemented:** terrain (Cycle C's task 20 travels to E-terrain), map labels,
models with textures and animation, the globe projection, and golden images.

### The visual harness

**It exists, it surfaced every one of the five defects above — four of them counted as such in the merge
commit, plus the `204` one it found later on the owner's real styles — and it is why `0.3.0` draws.** It
lives in
`consumer-smoke/src/macosArm64Main/kotlin/com/rohittp/reng/smoke/harness/` — in the standalone consumer
rather than its own directory, because `tools/check_repository_policy.py:1270-1287` permits Kotlin source
in exactly two places, `kmp/src` and `consumer-smoke`. It is `macosArm64`-only; `commonMain` keeps the
six-target resolution proof and shares nothing with it.

Run it as `./gradlew -p consumer-smoke runHarness -PstyleUrl=<url>` (or set `RENG_HARNESS_STYLE_URL`); the
style URL carries the owner's API key, which is why no style is checked in and why URLs are redacted before
printing. It renders a fixed 48-frame storyboard at 960×540 (`Storyboard.kt:25`, with frames 30..32 as
`drawBasemap = false` negative cases) into `build/harness-frames/frame-%04d.ppm`, and prints an `ffmpeg`
line rather than running it. Binary PPM rather than PNG because RenG owns a decoder and no encoder (ADR
0020), and writing an encoder to look at a picture is the wrong trade. A failed frame is still written,
deliberately: a gap in the numbering would hide it from `ffmpeg`, and a silently skipped frame is the one
outcome a visual harness must never produce. The context is headless CGL core-profile through the stock
`platform.OpenGLCommon` and `platform.OpenGL3` klibs with **no cinterop at all**, reporting
`Apple M3 Max | 4.1 Metal - 90.5`.

**Across all 34 styles the consumer ships it rendered 1630 of 1632 frames**, the two losses being transport
timeouts reported precisely rather than swallowed. That figure exists in exactly one place in the
repository — the body of merge commit `806007d` — and nowhere in any file.

Two cautions. The `ffmpeg`/libx264/`yuv420p` assembly step is **lossy**, and has already once misled a
judgement about image sharpness; compare at `crf 12` / `yuv444p`, or on the PPM frames directly. And
`check_targets` counts a target only when the identifier is followed by `(`, so `macosArm64 { … }` fails
`TARGET_SET` — the harness uses `macosArm64().binaries { executable("harness") { … } }`.

## Cycles B, C and D, as released in `0.2.0`

These are released and closed. They are kept for the measurements, defects and lessons that produced them,
and pruned of everything that has since been fixed — the earlier per-cycle test counts are gone with it,
because the only current figure is the one in the E-basemap section above.

**Cycle B — pure core.** Public immutable values, protocols and sanitized failures, canonical identities and
SHA-256, spatial and diff planning, and pure lifecycle, resource and preparation reducers driven entirely by
supplied values. Six of eight independent reviews returned changes required — seven major and thirteen minor
findings, all fixed. Two were serious and both lived in code whose own full suite passed: a discovery parent
never recorded installed visibility, so any plan carrying a discovery source produced no outcome at all and
a style waiting on that owner parked forever (install and discovery readiness had been mutually exclusive
terminals, yet such a parent needs both, and two reviewers found it independently from different symptoms);
and arbitration closed a parked sprite member while its group owner still had work in flight, after which
the group wrote into a resolved route and the reducer threw. Most of the rest were one shape: values whose
constructors accepted self-contradictory combinations the reducer never produced. **When a reducer's own
state type is the boundary that makes an illegal state impossible, write the invariant into the type, not
only into the paths that build it.** Four smaller findings closed in code — `ShaderProfilePlan` now carries
five `init` invariants relating its declared span to the actual source, and rejects seven contradictory
constructions with no source text in the message; `TransportResponse` and `StoredRawResource` expose
internal non-copying snapshot accessors that removed the redundant copies from a path that made seven
copies of a successful 200; the plan's missing `AdvancePendingSpriteCommit`/`AdvancePendingStyleCommit`
were specified; and two unkillable guards were kept with comments explaining why, so a future reader does
not delete them as dead code. **Still open and deliberate:** letting RenG retain the consumer's own
`TransportResponse` would remove two more copies, but ADR 0016 says the outcome is latched as a
*defensively copied* response, and retaining the consumer's object makes identity observable through `===`.
That is an ADR 0016 amendment, not a cleanup.

**Cycle C — resource layer.** Tasks 1–13 and 15 of a 21-task plan: the coroutines dependency and Task 2's
five-declaration public surface growth; the inflate/CRC-32 seam; the PNG container walk, CRC validation and
unfiltering into canonical unpremultiplied RGBA8; strict UTF-8 and a hand-written JSON reader; the GLB
container scan, the glTF document parser, and the `PARSE_GLB`/`VALIDATE_GLB_FEATURES` gates; the resident
cache (generations, leases, reload markers); the resource driver's class gates, Store writes and visibility
installs; and cancellation propagated unwrapped. The other six tasks turned out to be basemap work and
travelled to E-basemap (14, 16, 17, 18, 19) and E-terrain (20). **No gate/class combination throws any
more** — `ResourceClassGate` is down to `DECODE_PNG`, `PARSE_GLB` and `VALIDATE_GLB_FEATURES`, and the
basemap cycle deleted the rest outright (`924f557`) rather than implementing them, because the Rentile
engine acquires and validates those classes itself through RenG's firewall and RenG's driver never routes
one. Two notes worth keeping: splitting the cycle across worktrees put Task 10's cache on a
different branch from Task 12's consumer and the canonical GLB parser out of reach of Task 13, producing
two stand-in implementations that had to be discarded rather than merged alongside the real ones; and the
`CancelRoute` gap that Cycle C left open (unreachable then, because `Renderer` had no implementation) was
closed in F-1's first task at `ResourceActionExecutor.kt:140` — unreachable public ABI text is not a licence
to leave a crash in place once something can reach it.

**Cycle D — GL foundation.** The internal GL seam and four platform implementations (one each in `iosMain`,
`macosMain`, `linuxMain`, `androidMain`, per ADR 0022's measured source-set visibility rather than ADR
0009's original claim); runtime shading-language dialect detection off `GL_SHADING_LANGUAGE_VERSION` alone,
never the target platform; the offscreen colour-and-depth surface and its composite pass; the corrected GL
restore set (ADR 0023, superseding ADR 0006); shader compilation with version-directive substitution and a
program cache; the lifecycle driver feeding real GL facts to Cycle B's unmodified pure reducer; and the GL
conformance suite. **Count `GlEntryPoint` rather than quoting a number** — it was 84 when Cycle D closed and
is 87 now. The seam's central design problem from research is resolved as shipped: `Int` names and enums,
`Boolean`/`BooleanArray`, `IntArray`, `FloatArray`, `Int` buffer sizes, `ByteArray?` payloads, and one
`String` shader source, because Android exposes exactly `glShaderSource(int, String)` with no count or
length array.

Three Cycle D facts are still live constraints:

- **A Mesa driver defect forces a Linux-wide skip of one deliberate negative check.** Mesa 25.2.8 SIGSEGVs
  inside `libgallium` during `glLinkProgram` whenever a process holds two or more EGL contexts with at
  least one GLES-profile context and performs a cross-`#version`-dialect link — order-independent,
  reproducible from a RenG-free C program, absent on Mesa 23.2.1. Production never performs that link; only
  the conformance suite's "a mismatched `#version` must fail" check does. The owner accepted skipping it on
  Linux for every dialect and driver, through an internal `CrossDialectLinkPolicy` defaulting to
  `EXERCISE_LINK` with only `LinuxGlConformanceTest` opting into the bypass — 0/20 crashes, down from
  15/15. A ready-to-file upstream report exists at `docs/research/2026-08-19-mesa-bug-report-draft.md` and
  has **not** been filed.
- **The macOS CGL fixture is therefore the sole real proof that `#version` substitution is load-bearing.**
  Apple's driver reports `4.1 Metal - 90.5` and does not advertise `GL_ARB_ES3_compatibility`, so the
  cross-dialect negative check runs unskipped there and passes. A refactor that widens that fixture's
  capability gate returns the substitution claim to unproven everywhere.
- **`GlProgramCache`'s key deliberately omits the shader dialect.** That is safe only because
  `forgetWithoutDeleting()` calls both `GlObjectRegistry.forgetEverything()` and `GlProgramCache.forgetAll()`
  on every context adoption, every GPU-object-loss event, and renderer close, and a reviewer confirmed those
  three call sites are exhaustive. A fourth transition that can leave an adopted context behind must add the
  same invalidation, or the cache serves a program compiled under one dialect to a context running another.

### The scheduler cost is measured, unfixed, and shipped in two releases

Carried from Cycle B's extrapolation and measured in Cycle C by `ResourceOperationScaleBenchmarkTest`,
which drives many *distinct* sticker routes (one occurrence each, no joining) through
`ResourceOperationStateMachine.start` / `beginLookup` / `transition` to a real `Success`. Three clean runs
on a quiet Apple Silicon host:

| routes | run 1 | run 2 | run 3 |
|---|---|---|---|
| 64  | 503 ms   | 498 ms   | 501 ms   |
| 128 | 1838 ms  | 1724 ms  | 1705 ms  |
| 256 | 7274 ms  | 6975 ms  | 6824 ms  |
| 512 | 33622 ms | 29052 ms | 29610 ms |

Successive-doubling ratios of roughly 3.5–4.6 — at or above the quadratic signature, not the linear one
(which the existing 4096-occurrence-on-one-route test already shows for the joined case). **The shape is a
Θ(routes + occurrences) rebuild floor paid by every event**, times roughly nine events per route: the
reducer rebuilds all derived indexes, re-copies every route record and re-validates the whole state per
transition. **This table measures that floor alone.** Two further costs are extrapolations and are *not*
in it, because every route here takes a unique owner, every occurrence uses `ResourceCommitBinding.Single`,
and the payload is four bytes: the style-owner barrier is O(owners × occurrences), which bites because a
256-frame batch binds all 256 owners to one `StyleGroupId`; and `OwnerResourceSet` calls `toSet()` on every
transition while `StoredRawResource.hashCode()` does a full `contentHashCode()` byte scan, so every event
re-hashes the complete payload of every already-installed resource — on the order of 100 GB per frame at
512 tiles of 50 KB. A real mixed frame costs more than the table, not less.

**Thirty seconds of pure reducer CPU per frame, before a byte is decoded, is not a viable production
number at the shipped 512-tile default**, and the 4096 maximum is minutes. Neither remedy has been started:
caching `StoredRawResource`'s hash at construction is the cheap win for re-hashing, and the per-event
rebuild is the honest fix for the floor. **Cycle C did not fix it, `0.2.0` shipped it, and the basemap
cycle did not fix it either.** What changed is only the guard: the original 50-second wall-clock ceiling was
calibrated on a developer M3 Max, a hosted runner could not meet it, and that is what blocked the first
`0.2.0` push. `c67349a` replaced it with an assertion on the machine-independent scaling ratio — 6.0× on
the largest pair, between the quadratic signature and the cubic one
(`ResourceOperationScaleBenchmarkTest.kt:115-123`) — and dropped the top route count from 512 to 256 for
roughly a quarter of the wall-clock cost per build. Every measurement is still printed, because those
numbers are what the eventual optimisation has to beat.

One harness note: a 2026-08-19 review found the benchmark's own driver had stacked a second avoidable
O(routes²) cost on the reducer's, re-scanning the full route list after every action. That scan is gone —
the driver names the ordinal directly off the `CallTransport` action that parks it, in O(1) — and
re-measuring reproduced the same at-or-above-quadratic ratios, confirming the reducer produced the table.

### What five adversarial passes over the PNG path found

PNG decode is RenG's primary hostile-input surface — Rentile hands back encoded tile bytes even for vector
basemaps, and stickers and model textures are consumer-supplied. It took five separate passes to get clean,
and **every single pass found something**: an uncaught out-of-range palette index (self-reported); a
declared width/height at or past `2^31` sign-extending through signed 32-bit arithmetic to bypass the size
ceiling before `NegativeArraySizeException` (reviewer, unprompted); the *ceiling check itself* overflowing,
because `width * height * 4` as a `Long` product can wrap past `Long.MAX_VALUE` even after both dimensions
are individually bounded (dedicated adversarial audit); two missing chunk-ordering rules, a duplicate `tRNS`
and a `PLTE` accepted after `IDAT` (a second independent audit of the fix for the third); and two
silent-wrong-result gaps surfaced while building the fuzz harness. Four crash classes and two
silent-wrong-result gaps, found by four different routes, and **the base rate of one new defect per pass
did not visibly decline until the fifth** — worth remembering before declaring any hostile-input surface
clean after one review. `PngFuzzTest` is the permanent regression coverage: 300,000 deterministic inputs
(six valid seeds × 50,000 mutations, a from-scratch fixed-seed xorshift32, twelve structured mutation kinds
including IHDR corruption with the CRC recomputed so mutations reach deep validation), asserting `decodePng`
never throws, in under half a second on both hosts. By construction it does not cover the two
silent-wrong-result gaps, which have dedicated fixtures instead.

### GLB feature subset — corpus-checked, then narrowed by ADR 0028

`docs/research/2026-08-19-glb-feature-subset-corpus-check.md` ran the real, unmodified
`scanGlb`/`parseGltf`/`validateGltfFeatures` pipeline over `KhronosGroup/glTF-Sample-Assets`. Its erratum
of 2026-08-22 corrects the headline: the upstream corpus is **119** files and **53** are supported, not
118/52. Every rejection (JPEG textures, required extensions, skin attributes, a `KHR_animation_pointer`
target, morph targets, `CUBICSPLINE` interpolation) traces to a decision ADR 0021 already makes
deliberately, and no row moved from reject to accept. The re-run was performed twice, before and after the
accessor-constraint tightening (`24f36f0`, fifteen new guards across both gates), with **zero files
changing verdict** — so 53 holds for both the released pipeline and the tightened one. That branch merged
as **ADR 0028**; it had claimed `0025`, which the basemap cycle had already taken. Two branches numbering an
ADR in parallel is the normal outcome of parallel work, and the number is cheap to move only until
something cites it.

### Pre-implementation spike findings that are still load-bearing

- **PNG.** Own the container in common Kotlin and delegate only decompression and checksum: bundled `zlib`
  on the five native targets, `java.util.zip` on Android. Skia was rejected on measured behaviour, not
  weight — it silently truncates sixteen-bit grayscale to eight bits, silently accepts a stream truncated
  mid-image-data with no end marker, collapses a checksum mismatch and a corrupt payload into one opaque
  message, and is not faster. Repairing malformed input contradicts RenG's contract outright.
- **GLB.** Padding is not verifiable at the container layer, because a chunk's declared length includes its
  padding, so a padding policy is an explicit choice. And parsing must tolerate an accessor without a buffer
  view, so the feature gate reports an unsupported feature rather than the parse gate reporting malformed
  input — a compressed model is unsupported, not corrupt.
- **The JSON reader was required, not chosen.** The serialization library is resolved transitively but is
  compile-visible only on the native targets, and the repository policy forbids declaring it. Measured
  allocation behaviour also shows the JSON chunk needs its own ceiling separate from the model byte limit,
  since a chunk at that limit would demand several gigabytes.
- **Rentile's surface** (`docs/research/2026-08-18-cycle-c-rentile-surface.md`). The adapter must absorb a
  `remove` that RenG's own store does not have. Rentile always sends null conditional headers, so every
  conditional and accept decision is RenG's. Its retry is bounded to one extra call on specific statuses
  with a clamped delay, and it rethrows cancellation unchanged. And `RawResourceKey.toString()` prints its
  identifier in the clear, so a Rentile key must never reach a RenG diagnostic.

## What remains: F-2, E-labels, E-terrain, H, G, I, J

**Order, as it stands after two reorders.** The 2026-08-19 reorder split Cycle F into **F-1** (stickers,
geometries, the renderer factory — the MVP, released in `0.2.0`) and **F-2** (models), and split Cycle E so
its basemap half could run right after the MVP. The 2026-08-22 changes split **E-labels** out of F-2 into
its own cycle and moved **H** ahead of **G**. Existing cycle letters stay bound to their existing content so
no prior reference breaks.

```
E-basemap (0.3.0) ──► F-2 models ──► release ──► E-labels ──► E-terrain ──► H platforms ──► G globe ──► I harness ──► J corpus
```

`docs/decomposition.md` carries the same order with each cycle's gates, and is the document to update if it
moves again.

**F-2 — models with textures and animation.** Read "What F-2 inherits" above first; two of those five are
folded in by owner decision. Four spikes are in `docs/research/`, and the scope is measured rather than
argued: against the consumer's own 41-model catalogue RenG accepts 23 today, 33 if extra UV and colour sets
were ignored, and 40 if skinning were supported — so ignoring `TEXCOORD_1`/`COLOR_1` is the largest win per
unit of work. Skinning needs joint matrices in a uniform buffer rather than a uniform array (joint counts
reach 112, and GLSL ES 3.00 guarantees only 64 `mat4` of vertex uniforms, while a uniform buffer's
guaranteed 16 KB holds 256). Morph targets stay unsupported because the corpus has zero, and the
interpolation subset is already exactly right: 1443 `STEP`, 490 `LINEAR`, no `CUBICSPLINE` anywhere. One
JPEG-textured model stays rejected loudly rather than motivating a JPEG decoder. **ADR 0026** already
settles the light: one directional, world-anchored light at azimuth 335° and elevation 45° with an ambient
term, chosen so model shading and future terrain hillshading agree by construction, with stickers,
geometries and the ground left unlit. A screen-positioned model is **rejected** rather than drawn wrong,
because `screenOrthographicProjection` has an all-zero z row and every vertex collapses to clip z = 0 —
note that decision is recorded in `cbdfe10` and here, and has **neither an ADR file nor a rejection in
code** yet. F-2's gate is analytical readback, not golden baselines.

**E-labels — map text as RenG's own screen-space primitives. No longer blocked.** This document previously
said it was blocked on Rentile architecturally, because glyph-range URLs are data-dependent — the range set
comes from text inside decoded vector tiles, and `text-font` can itself be a data-driven expression — so
ADR 0016's exact-string preregistration structurally could not cover them. **Rentile `0.5.0` shipped the
answer**, and more than was asked for. `BasemapRasterizer.planLabelCandidates(style, tiles, resourceAccess)`
acquires and evaluates the label tiles and freezes the glyph closure *without acquiring any of it*,
returning a `LabelCandidatePlan` whose `glyphClosure: List<GlyphRangeRef>` is documented as "exactly the
Glyph Ranges `acquireLabelCandidates` will request from this plan — not a superset and not an estimate",
sorted, de-duplicated and stable across runs; `glyphUrls(template)` composes the concrete URLs through
Rentile's own substitution so RenG never re-derives them, throwing `GlyphTemplateMismatchException` on a
template that disagrees. `acquireLabelCandidates(plan)` then consumes that same frozen plan, so the closure
cannot under-approximate the acquisition that follows it. `GlyphRangeRef` is `(fontStackDigest, rangeStart)`
— identity only, no URL, no credential — with the raw font stack deliberately withheld. The
`FOREIGN_LABEL_CANDIDATE_PLAN` / `LABEL_CANDIDATE_PLAN_CLOSED` / `GLYPH_TEMPLATE_MISMATCH` codes RenG now
buckets fail-closed are these entry points' codes, and they move out of that bucket when labels land.

What is still genuinely new work: an SDF shader with halo bands (the atlas is RGBA8, white RGB, alpha as
SDF, so `decodePng`, `GlTextureUpload`, `ResidentCache`, the screen-regime sticker path and
`MercatorProjection` all get reused); viewport-wide collision and priority, which is ADR-worthy because the
policy and its frame-to-frame hysteresis are undesigned; batched quad drawing; occlusion against the 3D
scene, given ADR 0024 grants the screen regime no depth read; resolving `LabelIconRef.imageName` without a
public Rentile sprite atlas; and **the operator gap in F-2 item 5 above, which is E-labels' to carry** —
20 of 34 styles lose their text silently today, and RenG cannot see the diagnostic that says so.
`docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md:45` names a type `LabelPrimitive` that exists
nowhere in Rentile; the shipped type is `LabelCandidate`.

**E-terrain.** Displaces the mercator ground with the terrain Cycle C's deferred task 20 acquires; nothing
before it consumes elevation. Ground radiance, which Rentile evaluates from the style and hands over as a
literal, belongs with the same work. Behind models because terrain was already deferred once for having no
consumer, while models have consumers waiting. ADR 0027's terrain argument survives correctly narrowed:
terrain is a displaced surface that writes depth as its own decision, and the map regime keeps testing
against it, because 0027 removed the writes and not the test.

**H — Android and iOS bring-up.** The one cycle no continuous integration can cover, and it is **now ahead
of the globe**: every cycle adds GL surface that two of the six published targets have never executed, and
that gap compounds. A draft pull request borrows macOS and Linux hardware, but not a device; Android GL
remains manual.

**G — globe projection.** The second projection mode, re-projecting mercator tiles and every placement, so
that it re-projects a complete scene rather than a partial one.

**I — macOS harness — withdrawn 2026-08-28.** The harness pulled forward into the basemap cycle (see "The
visual harness") is the whole of it. `FramePlan` JSON and a self-contained AVFoundation encoder were
withdrawn on the argument that the harness is verification code, so an encoder inside it is bug surface that
can produce a misleading video and cast doubt on the renderer; `ffmpeg` stays, and its lossiness stays a
documented hazard rather than an engineered-away one. **`FramePlan` serialization is therefore no longer an
unowned prerequisite** — it had exactly one consumer and that consumer is gone, and Cycle J's corpus is
in-source Kotlin fixtures rather than documents, so RenG adds neither a public serialization surface nor the
dependency the repository policy would refuse. Reversing this is cheap and compatible if a consumer ever
needs plan documents. See `docs/decomposition.md` under "I — macOS harness — withdrawn".

**J — golden-image corpus.** The home of all pixel verification deferred from E-basemap, F-2 and beyond. Its
design is spiked (`docs/research/2026-08-21-golden-image-gate-design.md`) and two findings shrink the work:
**no new CI job and no new publish step are needed**, because both workflows already run the only two tasks
that can hold a context and `publish.yml` runs `linuxX64Test` and `macosArm64Test` before the R2 preflight;
and storage is *forced* rather than chosen — Base64 PNG constants in `commonTest`, with precedent at
`PngDecoderTest.kt:323-336`, because the policy checker forbids any `.kt` outside `kmp/src`. Three hard
problems remain. **Attribution:** Rentile draws the basemap's content through Skia and RenG only composites
it, so a frame baseline conflates "what RenG did with the tile" with "what Skia produced as the tile", and a
Rentile or Skia bump fails every basemap case at once, indistinguishably from a RenG regression — the
recommended answer is a separate decoded-tile gate at the firewall boundary. **Keying:** baselines need the
reported renderer string, not the target, since a hosted macOS runner renders through software while a
developer's machine renders through Metal — as `0.3.0`'s failed attempt demonstrated at some cost.
**Thresholds:** a zero-tolerance digest gate *is* the 50-second ceiling that blocked `0.2.0`, a tolerance
calibrated on the two software renderers CI has is an absolute threshold in disguise, and failing on an
unrecognised renderer signature blocks releases on non-regressions.

## Other open items, by how much they cost the next cycle

Most of these are recorded only in the git-ignored ledger, which is why they are here. Nothing below is a
regression; they are known, deliberate or parked.

**F-2 will meet these directly.**

- **The screen-regime ordering rule is implemented twice, and only one implementation handles models.**
  `MercatorSpatialPlanner.kt:171` sorts with the correct heterogeneous comparator at `:244-259`;
  `RenGRenderer` discards that plan and the GL layer re-sorts at `StickerPipeline.kt:205`. Stickers-only
  makes stable order equal source order, so the two coincidentally agree today. Models break the
  coincidence.
- **`StickerWorld`/`drawStickers` owns both regimes inside one drawn-thing type** (`StickerPipeline.kt:163-206`).
  Models make the screen stack span two programs, so the regime split has to move up into `SceneContent`.
- **`GL_CULL_FACE` is never enabled.** `GlFrameDrawer.kt:93` sets the mode and `:102` disables the state for
  the composite; nothing enables it, so inherited state decides. `glDepthRange` and geometry blend state are
  inherited the same way.
- **`Model` is public ABI with no draw path** (`kmp/api/kmp.klib.api:492-500`, `FramePlan.models` at `:457`).
  The planner consumes it; `SceneContent.draw` never mentions it. Stated on the served site only.
- **Premultiplied alpha meets PBR.** `uploadTexture` premultiplies `TextureContent.IMAGE` — correct for
  filtering, wrong for lighting arithmetic on a base-colour texture. Needs un-premultiplication in the
  shader or a third `TextureContent` kind. ADR 0026's lighting is what makes it live.
- **`uploadTexture` cannot express glTF sampler state** — clamp-only and mipmap-free
  (`GlTextureUpload.kt:78-85`). `GL_REPEAT`, `GL_MIRRORED_REPEAT`, `GL_UNSIGNED_SHORT` and the mipmap
  filters are all absent from `GlTokens.kt`. A model with tiled UVs renders visibly wrong three levels away
  from the symptom.
- **Mixed anchoring modes are untested at the matrix layer.** Every `Placement` in `SceneContentTest` and
  `StickerPipelineTest` uses `SCREEN` rotation and `SCREEN` scale, and
  `mapAnchoredStickersAtDifferentPositionsShareTheSameRotationScaleBlock`
  (`SceneContentTest.kt:275-296`) asserts an invariant that is true *only* under `SCREEN` rotation —
  extending it means writing a different assertion, not extending that one.

**Firewall and resource-layer items, parked deliberately.**

- **`renderTiles` throws a bare `error()` outside `engineCall`** (`BasemapEngineHost.kt:471`) — an untyped
  throw escaping the one file whose whole KDoc contract is that no `RentileException` escapes it.
- **`prepareBatch` opens one registry per plan with no latch sharing** (`RenGRenderer.kt:588`), because
  `withOperation` lives inside `prepare`. An N-frame batch over one style gets N registries.
- **Host binding fields are mutated without a lock from a non-suspending method**, so a `close()` racing a
  `prepare()` could double-release; `compiledStyle` has the identical exposure.
- **Metadata is validated on the write path but not the transport path** (`OperationRegistry.kt:435`
  declines a bad write). RenG still hands the consumer's `contentType`/`etag`/`lastModified` to the engine
  unvalidated, for it to echo back. Validating on the transport path fixes the cause rather than the
  symptom.
- **An atlas RenG cannot decode but Rentile can is never cached**, so both sprite members refetch on every
  `prepare()`, forever — the deliberate outcome of the decline-to-cache ruling. The gap is real: RenG's PNG
  walk rejects `bitDepth != 8`, interlacing, unknown critical chunks and trailing bytes after `IEND`, so a
  sprite atlas that `oxipng`/`optipng` bit-depth-reduced to a 4-bit palette — a default-on optimisation —
  renders in Rentile and hard-fails in RenG.
- **Rentile's `MAX_SPRITE_ENTRIES` and `maxRasterDimensionPx` are deliberately not mirrored**, so RenG
  caches pairs the engine will reject.
- **A latched cancellation is permanent per route for the registry's lifetime**, so a later healthy joiner
  replays a `CancellationException` it never earned. The lifetime was bounded to one preparation
  invocation; the semantics inside that invocation were never re-ruled.
- **The two key namespaces are disjoint incidentally, not structurally.** The pinned test asserts a static
  class→namespace table, which stays true when child discovery makes the runtime property false — the guard
  reads stronger than it is. A second read and write per basemap resource would violate the published
  one-exchange contract invisibly, which makes the one-exchange fix a **blocking prerequisite** of child
  discovery.
- **Rentile's foreign digest in diagnostics silently fails to correlate**: a consumer passing that
  `stableId` to `ResourceSelector.ByKey` gets an empty selection rather than an error, and the
  `BASEMAP_STYLE` row emits a digest RenG computes nowhere at all.
- **`RasterSample.immediateChildren`/`.ancestor` compose URLs unreachable only because the host passes
  `TileSubstitutionPolicy.Disabled`** — a version-and-configuration-pinned assumption that deserves a header
  sentence of its own.
- **`prepareTiles` still falls back on a default access mode** (`BasemapEngineHost.kt:453`, `?: NORMAL`)
  where ADR 0016 wants it bound.
- **A freed style generation is reloaded but not recompiled, and no warning is emitted** — which narrows the
  `CLAUDE.md`-level "accessing a freed resource reloads it and emits a warning" rule.
- **No test pins which of the seven basemap classes are compile-time and which are tile-time**, so a Rentile
  upgrade that turns the one-invocation rule from structural into load-bearing would not fail loudly. The
  pin has now moved twice.
- **`GlObjectRegistry.defer` is lease-safe but unreachable** — no production caller, five call sites all in
  tests, `GpuLedger.deferredDeletions` built empty and never appended.

**Dead vocabulary that reads as an unimplemented feature.** The child-traversal types
(`DiscoveredResourceChild`, `ChildrenDiscovered`, `DiscoverChildren`) have no producer and should be deleted
or they will look like unfinished work forever; they survive because
`ResourceOperationSchedulingTest` alone holds about 102 references. `OrderedPreparationStateMachine` is a
complete abstraction for a different architecture, referenced nowhere in `commonMain`, and was ruled
"bypass, do not revive". `ParkedRouteBarrier.SpritePair` / `requestSpriteVisibilityInstall` exist in the
reducer and are not wired into the runtime driver.

**Documentation debts.** `docs/decomposition.md`'s E-terrain and G gate rows still say "Golden baselines"
while the note beneath says pixel verification is deferred to Cycle J; that was left deliberately for the
owner. And `docs/research/2026-08-21-golden-image-gate-design.md`'s file:line citations are stale against
`main`.

## Environment notes

- **Android SDK Platform 37.0** is the package `platforms;android-37.0`, not `platforms;android-37`, and on
  older `cmdline-tools` it resolves only from the canary channel:
  `sdkmanager --sdk_root="$ANDROID_HOME" --channel=3 "platforms;android-37.0" "build-tools;37.0.0"`. Point
  untracked `local.properties` at it with `sdk.dir=…`. Without the SDK, AGP fails at configuration time and
  **every** Gradle task is blocked, including `linuxX64Test`.
- **All Kotlin/Native platform klibs, including the Apple ones, are present on a Linux host** under
  `~/.konan/kotlin-native-prebuilt-linux-x86_64-<version>/klib/platform/`. Apple bindings can therefore be
  inspected without a Mac: `klib dump-metadata <path>` (`klib contents` does not exist).
- **A real GL context is available on Linux.** Install `libegl1 libegl-mesa0 libgles2` after
  `apt-get update`, then create a surfaceless context via `EGL_PLATFORM_SURFACELESS_MESA` — no display
  server needed. Both an ES 3.2 and a 4.5 core profile context are reachable on llvmpipe.
- **Gradle reports `UP-TO-DATE` for unchanged test tasks.** A green build off cached test tasks is not
  evidence; pass `--rerun-tasks` when a run is meant to prove something.
- **The workflow parse command in `CLAUDE.md` uses the macOS Ruby 2.6 positional form**, which modern
  Psych rejects with `wrong number of arguments`. On Ruby 3 use
  `YAML.safe_load(File.read(path), aliases: true)`.
- **Borrowing Apple hardware:** push a temporary branch and open a **draft** pull request. `ci.yml` runs on
  every pull request and its `apple-publication` job covers `macosArm64Test`, both iOS targets, local
  publication and the clean six-target consumer. `publish.yml` triggers only on push to `main` or explicit
  dispatch, so a pull request cannot consume a version or reach R2. Such branches are disposable; say so in
  the pull request body.
- **A Finder visit can break the release gate.** `.DS_Store` produced a Unicode error naming no file until
  `6e7c023` added it, and `.gradle/`, to `.gitignore`.

Spike code is deliberately throwaway and lives outside the repository. The findings documents in
`docs/research/` are the durable record; if a spike needs re-running, they say what it did.

## Publication boundary

Pushing a development branch is a recovery checkpoint, not permission to merge, dispatch publication,
upload to R2, or claim a public release. Three releases are public and immutable: `0.1.0` from
`af92901b…`, `0.2.0` from `a2cbe6a9…`, and `0.3.0` from `49cc1d58…`. None can be overwritten, deleted,
reused or skipped; a partial release is recovered by an explicit upward `VERSION_NAME` change and nothing
else. Do not infer that any outward gate passed without an observed workflow result — and note that
`0.3.0`'s run IDs were recorded only because someone watched them, which is exactly the gap that makes
such an inference tempting.

**The unpushed commits on `main` are unreleased.** Pushing them publishes `0.3.1` unless `VERSION_NAME`
says otherwise in the same push.
