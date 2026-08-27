# The Rentile `0.6.0` bump spike — does the pin actually move green?

Measured on 2026-08-28 in a scratch worktree branched from `main` at `f997e1a` ("docs: withdraw Cycle I,
keeping ffmpeg as the harness assembly step"), on Apple M3 Max, Kotlin 2.3.21, Gradle 9.5.0. The worktree
was removed at the end; the primary checkout was never touched.

This answers the item `docs/research/2026-08-28-e-labels-rentile-surface.md` §8 flagged first and hardest:
*"No build was run and no test was executed … It is a strong inference, not a green build."* That document
read `main` at `6730722`; `f997e1a` is one docs-only commit later, so nothing between them touches the
question.

## Verdict

**The bump is safe to land as its own commit. Every gate that can be run on this machine passes, the public
ABI does not move, and no test count changes by one.** The `0.5.0 → 0.6.0` upgrade is, for a RenG that draws
no labels, an eight-line diff across three files and nothing else.

Two findings the research did not have. **The provenance gap §8 called unclosable is closed** — and for the
pinned `0.5.0` as well as for `0.6.0` — because Rentile publishes sources jars, which is a stronger
instrument than the ABI comparison §8 proposed. And **there is a sixth coupled place, ungated**: 20 lines of
prose across 10 Kotlin files name Rentile `0.5.0` or its release commit `d899cb2`, and a bump makes every
one of them false without any gate noticing.

The one thing this spike could **not** do is the 34-style harness comparison the research calls "the honest
verification" — no style URL is available in this environment. That remains open and is stated as such below.

## What ran, and what it says

| Gate | Result |
|---|---|
| `python3 -m unittest discover -s tools/tests` | **138 tests, OK** |
| `check_repository_policy.py --root .` | **passed** |
| `:kmp:checkKotlinAbi` | **passed**; `kmp/api/kmp.klib.api` did not move |
| `:kmp:testAndroidHostTest` | **1,123 tests, 0 failures, 0 errors, 0 skipped** |
| `:kmp:macosArm64Test` | **1,159 tests, 0 failures, 0 errors, 0 skipped** |
| `:kmp:iosSimulatorArm64Test` | **1,145 tests, 0 failures, 0 errors, 0 skipped** |
| `:kmp:compileKotlinLinuxX64`, `…LinuxArm64`, `:kmp:bundleAndroidMainAar` | **BUILD SUCCESSFUL** |
| `:kmp:compileKotlinIosArm64`, `…IosSimulatorArm64`, `:kmp:compileTestKotlinIosArm64` | **BUILD SUCCESSFUL** |
| `:kmp:publishAllPublicationsToLocalTestRepository` | **BUILD SUCCESSFUL**, all seven publications |
| consumer-smoke six-target compile against that local publish | **BUILD SUCCESSFUL** |

Counts are summed from Gradle's own JUnit XML under `kmp/build/test-results` (95, 99 and 98 XML files
respectively), not from scrollback. **All three match `CLAUDE.md`'s recorded Cycle H figures exactly** —
1,123 / 1,159 / 1,145 — so the bump neither adds, removes, nor skips a single test.

The results are fresh rather than up-to-date artefacts: the worktree was created at 02:30:35 and the XML was
written at 02:33:27 (Android), 02:35:57 (macOS) and 02:36:52 (iOS simulator). Gradle reported
`30 actionable tasks: 30 executed` and `12 actionable tasks: 8 executed, 4 up-to-date`, with no test task
marked `UP-TO-DATE`.

Resolution was confirmed rather than assumed:

```
+--- com.rohittp.rentile:kmp:0.6.0
|    \--- com.rohittp.rentile:kmp-macosarm64:0.6.0
|         +--- com.squareup.wire:wire-runtime:6.4.5
|         +--- com.squareup.okio:okio:3.18.1
```

and the locally published RenG POMs declare `com.rohittp.rentile:kmp-*:0.6.0`.

**The transitive dependency set does not move.** Comparing the published `.module` descriptors for
`kmp-macosarm64` at both versions gives an identical set — `okio 3.18.1`, `wire-runtime 6.4.5`,
`kotlin-stdlib 2.3.21`, `kotlinx-coroutines-core 1.11.0`, `kotlinx-serialization-json 1.11.0`,
`skiko 0.148.2`. No new transitive, no version drift, nothing for RenG's dependency policy to react to.

## The ABI question, answered by building

`kmp/api/kmp.klib.api` is `sha256 fecb289f29ecfbb419287f5b6aa918085574afb5b6958dd7cc2b3ccb2065e5f6` before
the bump and the same digest after `:kmp:checkKotlinAbi` ran against Rentile `0.6.0`. `git status` on
`kmp/api/` is clean. 889 lines, unchanged.

This is the research's §3 prediction confirmed by construction rather than by inference: the 29 removed
Rentile ABI lines live in `LabelCandidate`, `LabelIconRef` and `LabelLayerStyle`, RenG imports none of them,
and the compiler agrees.

## The five coupled places, and the fingerprints actually computed

The research's §7 correction table is accurate against the checker as it stands, and `HANDOFF.md`'s
description is stale in exactly the three ways §7 names. The five places, with the line numbers found:

1. `gradle/libs.versions.toml:7`
2. the **three** `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS["gradle/libs.versions.toml"]` values,
   `tools/check_repository_policy.py:267-271`
3. `base_versions["rentile"]`, `tools/check_repository_policy.py:1900`
4. the fixture catalog, `tools/tests/test_check_repository_policy.py:271`
5. the mutation test, `tools/tests/test_check_repository_policy.py:1447`

`kmp/build.gradle.kts` pins three fingerprints too, but none carries the Rentile version, so none moves —
also as §7 says.

**The three `0.6.0` fingerprint values the research offered are correct.** They were re-derived rather than
pasted, by running `_build_configuration_fingerprint` over every historical revision of the catalog with the
Rentile version token normalised. At `0.5.0` the recipe reproduces all three accepted values with
`unmatched expected: []`, which validates the method before it is trusted:

```
c79cc53420a717078f4f6b15aba2cd6120d9ab431496ea1c660b8e037354ed7e  from caa2e47  (ADR 0032 form, on disk)
7eb2e12a272e4b1eb7e48dc62e27eced1e76f873b84cb7ac036b67934de7d59f  from 0f385c7  (ADR 0019 coroutines form)
c53967f3a738a29f39817a93be0090ba96884c90b173591b021d32c2d30a71b9  from 100e583  (pre-Cycle-C form)
unmatched expected: []
```

and with the token set to `0.6.0`:

```
b9756bb7805cbdcd9c173835c961a50a6aa47ef0cd717137083c1c323d6c9e87  from caa2e47
174937a0a8c0da9da51654064fb15b4d491cfd3f38349424f4b39d2f6e91ad92  from 0f385c7
d6bc6efd37d55b0b9c46c9b72fa19f28350a102c80996dc26c4b89f794a8d795  from 100e583
```

**Identical to §7's three, character for character.** The checker accepts them and passes.

One detail the recipe surfaces that §7 does not mention: a fourth historical catalog form exists (first seen
`8b7ec1f`) whose fingerprint is `11660e41…` and is **invariant to the Rentile token**, because it predates
the Rentile entry. It is not in the accepted set and does not move. Harmless, but it means "every historical
revision" and "every accepted form" are not the same list.

### How the gate actually refuses, which is worth knowing before doing this under time pressure

Bumping only the catalog produces **two** refusals, not one, and they are textually identical:

```
gradle/libs.versions.toml:1:FORBIDDEN_CYCLE_B_DEPENDENCY:Cycle B :kmp dependencies must be exactly …
gradle/libs.versions.toml:1:FORBIDDEN_CYCLE_B_DEPENDENCY:Cycle B :kmp dependencies must be exactly …
```

Same code, same file, same line, same message — one from `base_versions`, one from the fingerprint. Nothing
in the output distinguishes them, and fixing one silently drops the count to one. That is not a defect, but
anyone bumping this expecting the message to name the offending place will be misled. Fixing `base_versions`
first and watching the count go 2 → 1 → 0 is the cheapest way to know which is which.

The mutation test fails **loudly**, not vacuously, if its fixture line is forgotten — verified by leaving it
at `0.5.0` against a `0.6.0` fixture and running the suite:

```
FAIL: test_cycle_b_dependency_catalog_is_exact_and_custom_catalogs_are_rejected
AssertionError: 'FORBIDDEN_CYCLE_B_DEPENDENCY' not found in set()
```

Given that seven vacuous checks were caught during F-2, this one was worth confirming rather than assuming.

### The whole diff

```
 gradle/libs.versions.toml                   |  2 +-
 tools/check_repository_policy.py            | 10 +++++-----
 tools/tests/test_check_repository_policy.py |  4 ++--
 3 files changed, 8 insertions(+), 8 deletions(-)
```

Of the ten lines in `check_repository_policy.py`, six are the three fingerprints, two are
`base_versions["rentile"]`, and two are a comment that says "rentile 0.5.0 bump" in prose.

## The two predicted consequences

**(a) `PreparedStyle.digest` changes, and nothing in RenG's suite notices.** Confirmed at the root: in the
*published bytes*, `RENDERER_SEMANTIC_VERSION` is `"rentile-renderer-3"` at `0.5.0` and
`"rentile-renderer-4"` at `0.6.0`. So every `basemapTileKey` is a new value.

No test fails, and the reason is structural rather than lucky. There are 21 64-character-hex literals in
RenG's test sources, and every one is a SHA-256 of a known input — `e3b0c442…` is `sha256("")`,
`ba7816bf…` is `sha256("abc")`, `9f86d081…` is `sha256("foo")` — belonging to `Sha256Test` and
`ResourceKeyDerivationTest`. **Not one is a Rentile-produced digest.** The 17 `basemapTileKey` call sites in
tests are all *relational*: `BasemapEngineHostTest` compares keys derived from two synthetic constants
`HOST_STYLE_DIGEST_A`/`_B`, and `RendererBasemapTileTest:61` uses a live `style.digest` on both sides of the
comparison, so both sides move together. `grep -rn 'rentile-renderer' kmp/src consumer-smoke/src` returns
nothing.

The consumer-facing half of the prediction stands and is untested here: a consumer's rendered-tile cache
goes cold exactly once after the bump.

**(b) The stricter comparison typing — not reachable by any gate on this machine.** See "What this did not
verify" below.

## What surprised me

### 1. A sixth coupled place, and no gate covers it

The research counts five coupled places, all gated. There is a sixth that is not gated at all: **20 lines
across 10 Kotlin files name Rentile `0.5.0` or the release commit `d899cb2` in prose.**

```
commonMain/…/basemap/BasemapStyleManifest.kt          2
commonMain/…/firewall/BasemapEngineHost.kt            1
commonMain/…/firewall/EngineFailureClassification.kt  2
commonMain/…/firewall/OperationRegistry.kt            3
commonMain/…/firewall/RentileKeyDerivation.kt         5
commonTest/…/basemap/BasemapStyleManifestTest.kt      1
commonTest/…/basemap/BasemapTileJsonSourceTest.kt     1
commonTest/…/firewall/BasemapEngineHostTest.kt        1
commonTest/…/firewall/EngineFailureClassificationTest.kt  2
commonTest/…/firewall/FirewallTest.kt                 2
```

The fingerprint mechanism is deliberately comment-insensitive and these are `.kt` files anyway, so **the
policy checker cannot see any of them** and the bump passes green with all 20 left false. The sharpest are
in `RentileKeyDerivation.kt`, whose KDoc says the derivation was verified "against the actual Rentile
0.2.0-through-0.5.0 derivation" and names "the `0.5.0` release commit `d899cb2` the version
`libs.versions.toml` pins" — a sentence that becomes a direct falsehood about the file next to it.
`BasemapEngineHostTest.kt:871` carries it into an assertion *message*:
`assertEquals(7, engineKeyed.size, "Rentile 0.5.0 fetches and keys exactly seven basemap classes")`. The
value 7 is still right; the sentence is not.

This matters more than tidiness, because `CLAUDE.md`'s own rule is "do not quote a Rentile version from
prose, including from this file." These are the prose. **A bump commit should move all 20, and no gate will
remind anyone to.**

The good news is that the *claim* those comments make extends cleanly. Comparing the published `0.5.0` and
`0.6.0` sources jars directly, every file RenG reproduces or depends on is byte-identical:

```
identical  internal/ContentIdentity.kt          identical  internal/SecretContext.kt
identical  Resources.kt                         identical  Exceptions.kt
identical  internal/raster/RasterResource.kt    identical  internal/mvt/VectorSource.kt
identical  internal/metadata/TileJsonResourceAcquirer.kt
identical  internal/raster/RasterResourceAcquirer.kt
identical  internal/mvt/VectorResourceAcquirer.kt
identical  internal/geojson/GeoJsonResourceAcquirer.kt
identical  internal/sprite/SpriteResourceAcquirer.kt
identical  internal/RawResourceStoreAccess.kt
```

So `0.2.0-through-0.5.0` can become `0.2.0-through-0.6.0` truthfully, and this is a stronger check than the
research's — it compares *published bytes* rather than observing that a git diff did not touch the paths.

### 2. The provenance gap is closed, for both versions, and cheaply

§8 says "nothing proves the published `0.6.0` bytes came from `87ccba2`" and suggests comparing the resolved
klib's ABI. **There is a better instrument: Rentile publishes sources jars.**
`https://maven.rohittp.com/com/rohittp/rentile/kmp/0.6.0/kmp-0.6.0-sources.jar` returns HTTP 200 (169,814
bytes). Unpacked it holds 39 Kotlin files under `commonMain/`; `87ccba2` has 36. The three extras —
`internal/mvt/Tile.kt`, `internal/glyph/Glyph.kt`, `internal/glyph/Glyphs.kt` — each begin
`// Code generated by Wire protocol buffer compiler, do not edit.` and are Wire codegen from
`kmp/build.gradle.kts`'s `wire { }` block, not checked in.

Of the 36 checked-in files: **36 identical, 0 differing.**

The same check against `d899cb2` for the currently pinned version: **36 identical, 0 differing.** So the
`0.5.0` gap §8 notes "has never been closed" is closed too, by the same command.

This is stronger than an ABI comparison, which only proves the public surface matches and would say nothing
about internal bytes RenG reproduces. It should replace §8's suggested check in any future upgrade.

Incidentally the same jars confirm the research's central `0.6.0` claims against published bytes rather than
against the sibling checkout: the place-name allowlist gate `if (sourceLayer !in PLACE_NAME_SOURCE_LAYERS)
return false` is at `StyleCompiler.kt:544` at `0.5.0` and **gone** at `0.6.0`, where the set survives only as
`LEGACY_PLACE_NAME_SOURCE_LAYERS` at `:2215`; and `SHELF_WIDTH_PX` is 4096 → 8192. Both exactly as §2 and §6
describe.

### 3. Nothing else broke, which is itself the finding

I went looking for an unpredicted breakage and did not find one. No new transitive dependency, no ABI
movement, no changed test count, no new skip, no compiler warning that was not already there, seven
publications and a six-target consumer compile both clean. For a bump that the research describes as "not a
point upgrade … a different surface," the absence is worth recording as a measurement rather than as
silence.

## What this did not verify

- **The 34-style harness comparison — the research's own "honest verification" — was not run, and could not
  be.** The harness needs a style URL carrying the owner's API key: `RENG_HARNESS_STYLE_URL` is unset here,
  `local.properties` does not exist, `Storyboard.kt` hardcodes no URL, and no style corpus is checked in.
  This is the gate that would catch §5.4's stricter `==` typing turning a preparing style into a failing one
  — the one real regression path in the release, and one RenG's suite structurally cannot see because RenG
  checks in no style documents. **What would settle it:** the owner runs
  `./gradlew -p consumer-smoke runHarness -PstyleUrl=<url>` across all 34 styles at `0.5.0` and again at
  `0.6.0`, comparing which styles prepare. Until that runs, "the bump breaks no style" is unmeasured, and
  this spike's green does not cover it.
- **`linuxX64Test` did not run** — Linux CI coverage, not a macOS-local gate. Ubuntu's llvmpipe job is also
  what keeps the ground-coverage readback case gated at all, so CI is doing work this machine cannot.
- **Neither device run was performed.** No Android emulator or phone and no provisioned iPhone was attached,
  so `:kmp:connectedAndroidDeviceTest` and `tools/run_ios_device_tests.py` are untouched by this spike, as
  they are by every other automated gate.
- **The fresh-Gradle-home `--refresh-dependencies` consumer smoke was not run.** The consumer compile used
  the normal Gradle home, which proves resolution and compilation but not a cold cache. Publication's own
  gate chain does that on CI.
- **No label call was executed.** This spike compiled and tested a RenG that never calls
  `planLabelCandidates`. §8's counting-stub spike — preregister, plan, read `glyphUrls`, preregister again,
  acquire, assert every URL seen was preregistered — remains the cheapest de-risking available and is still
  unrun. It is unrelated to whether the bump is safe, and entirely related to whether the cycle's design is.
- **The `0.6.0` migration document was still not audited**, and this spike adds nothing there.

## Recommendation

**Land the bump as its own commit, before the E-labels design is written**, exactly as the research
recommends and for the reason it gives: a bump landed alongside the feature turns any failure into "is it the
version or the feature?"

The commit should carry six things, not five: the five coupled places, **plus** the 20 prose lines in 10
Kotlin files. It should also update `RentileKeyDerivation.kt`'s range claim to `0.2.0-through-0.6.0`, which
the byte-identity check above now supports, and `HANDOFF.md`'s stale fingerprint section, which understates
the work in the three ways §7 names.

Recompute the three fingerprints at the moment of the bump rather than pasting the values above. They are
correct as of `f997e1a`, and any other token change to the catalog in between invalidates all three.

One caveat on the green: it is a *compile-and-test* green over a RenG that draws no labels and checks in no
styles. It says the upgrade costs no code change, which is what §8 asked. It does not say the upgrade renders
the same map, and only the harness can say that.
