# E-labels: what the 34-style corpus actually asks for

Measured 2026-08-28 against `main` at `f997e1a`, by fetching all 34 style documents of the owner's map
corpus and counting. Every number below is the output of a script over those documents, not an estimate.

**No style document, URL, credential or tile template appears in this file, and none was written into the
repository.** The documents were fetched to the session scratchpad, analysed there, and only aggregate
counts brought back. A style document carries the owner's API key in its `sources`, `glyphs` and `sprite`
templates; that is the same class of secret as a signed URL under ADR 0016's redaction rule.

The corpus is the 34 map ids Rentile verifies against, taken from Rentile's own corpus run
(`build/reports/rentile-corpus-0.6.0-final7/mosaics`, 136 mosaics = 34 ids × 4 zooms): 17, 49, 51–65,
67–73, 76–81, 83–86. All 34 fetched HTTP 200 and parsed.

This document exists because the two E-labels preflight strands both had to reason about scope without
these numbers. `docs/research/2026-08-28-e-labels-rentile-surface.md` §8 lists "the corpus impact is
unquantified" among the things it could not verify. This quantifies it.

---

## Summary: the three numbers that decide the cycle's scope

**1. The Rentile bump is the difference between a labelled map and a place-name map — 78% of the corpus's
text lives outside the version RenG pins.** Of 1,220 symbol layers carrying text, only **265 (21.7%)** sit
on a source-layer in Rentile `0.5.0`'s ten-name place-name allowlist. The other **955 (78.3%)** need
`0.6.0`. The median style renders about a sixth of its own text at the pinned version, and one style
renders 4.5% of it.

**2. Line placement and icons are both pervasive, not fringe.** Line placement appears in **31 of 34
styles**; icons in **23 of 34**, and **90% of icon layers are the same layer as their text**. Neither can
be deferred by declaring it rare, because neither is rare. Whether they are deferred anyway is a scope
decision, but it has to be made knowing that deferring icons means 611 layers draw their text without the
symbol beside it.

**3. One planned piece of work is unnecessary: `cooperative` overlap is used exactly zero times.** The
corpus's `text-overlap` and `icon-overlap` values are only `never` (79 and 49) and `always` (2 and 5).
Rentile `0.6.0` introduced `SymbolOverlap.COOPERATIVE` as a distinct third state, and the surface research
correctly flagged that its negotiation — "blocked only by an already placed `NEVER` symbol" — is RenG's to
implement. **No style in this corpus asks for it.** So can `text-variable-anchor`, which appears **zero**
times and which Rentile does not support anyway.

---

## 1. The corpus, structurally

| | total | mean per style | styles using |
|---|---:|---:|---:|
| layers | 4,787 | 140.8 | 34 |
| symbol layers | 1,290 | 37.9 | 33 |
| …carrying text | 1,220 | 35.9 | 33 |
| …carrying an icon | 681 | 20.0 | 23 |
| …carrying **both** | 611 | 18.0 | 21 |
| …icon with no text | 70 | 2.1 | 21 |

One style of the 34 has no symbol layer at all. Symbol layers are **27% of all layers** in the corpus, so
labels are not a garnish on these styles — they are a quarter of what the style describes.

## 2. What the version bump actually buys

Rentile `0.5.0` admits a label layer only if its `source-layer` is one of ten names
(`StyleCompiler.kt`, `PLACE_NAME_SOURCE_LAYERS`): `place`, `continent_label`, `country_label`,
`country_disputed_label`, `state_label`, `city_label`, `town_label`, `place_label`, `island_label`,
`archipelago_label`. `0.6.0` deletes the allowlist and admits every visible text-bearing vector symbol
layer, keeping the ten names only as `LEGACY_PLACE_NAME_SOURCE_LAYERS` to decide which source failures stay
strict.

| | text layers | share |
|---|---:|---:|
| on a legacy place-name source-layer — renders at `0.5.0` | 265 | 21.7% |
| on any other source-layer — needs `0.6.0` | 955 | **78.3%** |

**The source-layers `0.6.0` unlocks, by layer count:**

| count | source-layer | what it is |
|---:|---|---|
| 284 | `poi` | points of interest |
| 169 | `transportation_name` | **road names** |
| 68 | `water_name` | |
| 48 | `poi_station` | |
| 40 | `water_label` | |
| 33 | `road_label` | **road names**, the other schema |
| 26 | `mountain_peak` | |
| 24 | `aeroway` | |
| 21 | `water_centroid` | |
| 21 | `landuse` | |
| 16 | `waterway` | |
| 14 | `aerodrome_label` | |
| 12 | `pathway_label` | |
| 12 | `poi_public` | |
| 12 | `housenumber` | |

Per style, the share of a style's own text layers that render at `0.5.0`:

- **worst:** style 86 at 4.5%; then 77, 84, 79, 55, 58, 68, 71 all between 14.3% and 15.2%.
- **best:** styles 60 and 81 at 100%, style 61 at 80%.
- **31 of 34** styles have at least one text layer that only `0.6.0` can render.
- **0 styles** lose *all* text at `0.5.0` — every style keeps its place names. The degradation is uniform
  and partial rather than total, which is exactly the shape that is easy to miss by eye.

**Separately, 28 of 34 styles use an expression operator inside a symbol layer that `0.5.0` cannot
compile:**

| operator | styles using it in a symbol layer |
|---|---:|
| `!=` | 23 / 34 |
| `>` | 22 / 34 |
| `<` | 13 / 34 |
| `slice` | 5 / 34 |
| `to-string` | 5 / 34 |
| **any of the five** | **28 / 34** |

This refines the figure RenG has been carrying. `HANDOFF.md` and
`docs/research/2026-08-22-rentile-glyph-closure-request.md:139-140` record "20 of 34 styles carry one of
these operators — `>` in all 20". Measured now: `>` is in **22**, and the union of the five is **28**. The
earlier figure was measured against a different Rentile version's operator set and is not wrong so much as
superseded.

Under `0.5.0` these degrade silently — a symbol layer whose text program fails to compile keeps its
descriptor, emits an INFO diagnostic and simply produces no text — which RenG cannot see, because it reads
no engine diagnostics. Under `0.6.0` all five compile.

## 3. Feature usage, which is the scope decision

| layers | styles | feature | consequence for the cycle |
|---:|---:|---|---|
| 1,235 | 33 / 34 | **data-driven `text-font`** | the font stack is chosen per feature; this is why the handover carries a `fontStackDigest` rather than a raw stack |
| 1,095 | 33 / 34 | **text halo** | the SDF shader must do halo; it is not an optional second pass |
| 735 | 31 / 34 | data-driven `text-field` | |
| 680 | 28 / 34 | `symbol-sort-key` | collision priority is real and per-feature |
| 375 | 31 / 34 | **line / line-center placement** | 300 `line`, 26 `line-center`, 49 data-driven |
| 110 | 13 / 34 | `icon-text-fit` | the icon contract's hardest field |
| 93 | 20 / 34 | data-driven `text-color` | vindicates `0.6.0` moving colour onto the candidate |
| 81 | 20 / 34 | `text-translate` | |
| 60 | 12 / 34 | `text-translate-anchor` | forces the map-rotates-with vs viewport-fixed decision |
| 50 | 19 / 34 | `symbol-z-order` | |
| 41 | 18 / 34 | data-driven `text-halo-color` | |
| **0** | **0 / 34** | **`text-variable-anchor`** | Rentile does not support it and nothing asks for it |

`symbol-placement` across all 1,290 symbol layers: **915 `point`**, **300 `line`**, **26 `line-center`**,
**49 data-driven**.

**Overlap and placement negotiation**, by layer:

| count | key | |
|---:|---|---|
| 349 | `icon-allow-overlap` | |
| 194 | `text-allow-overlap` | |
| 167 | `text-optional` | |
| 81 | `text-overlap` | literal values: **79 `never`, 2 `always`** |
| 63 | `icon-optional` | |
| 54 | `icon-overlap` | literal values: **49 `never`, 5 `always`** |
| 51 | `text-ignore-placement` | |
| 15 | `icon-ignore-placement` | |

**`cooperative` appears zero times in either key.** That is the finding that removes work rather than
adding it.

## 4. Icons, since they are the biggest scope question

| count | |
|---:|---|
| 681 | layers with an `icon-image` |
| 611 | of those **also carry text** (89.7%) |
| 504 | literal `icon-image` |
| 177 | data-driven `icon-image` |
| 86 | use `icon-text-fit` |
| 23 | styles with at least one icon layer |

The 90% pairing is the number that matters. Icons and text are overwhelmingly **the same layer**, not
parallel families of layers — so "text only, icons later" does not split the corpus into a part that works
and a part that does not. It renders 611 layers as text floating where a symbol should have been beside it,
in 21 of 34 styles. That may still be the right call for a first cycle; it is not a *free* call, and the
research documents' framing of icons as a separable feature is not what the corpus shows.

## 5. Fonts, for the glyph budget

**85 distinct font stacks** are referenced across the corpus. The most common:

| layers | stack |
|---:|---|
| 454 | `Noto Sans Regular` |
| 266 | `Open Sans Semi Bold`, `Noto Sans Bold` |
| 226 | `Open Sans Regular`, `Noto Sans Regular` |
| 204 | `Roboto Regular`, `Noto Sans Regular` |
| 114 | `Roboto Italic`, `Noto Sans Italic` |
| 112 | `Open Sans Semi Bold`, `Noto Sans Regular` |
| 90 | `Open Sans Semi Bold Italic`, `Noto Sans Italic` |
| 84 | `Open Sans Bold`, `Noto Sans Bold` |

85 stacks is a corpus-wide figure, not a per-frame one — a single viewport touches a handful. But it bears
on `maxGlyphRangesPerBatch` (default 256 at `0.6.0`, raised from 64) and on whatever residency bound RenG
gives its atlas, because the *identity* space is 85 stacks × up to 256 ranges, and the atlas Rentile builds
for one plan is one image. Rentile's own migration document records a worst case of 159 ranges in a single
plan, whose 8192×4357 atlas is 136 MiB decoded — so an atlas byte budget is not theoretical.

## 6. The bump's one named regression risk, checked against the corpus and not found

`docs/research/2026-08-28-e-labels-rentile-surface.md` §5.4 names exactly one genuine regression in the
upgrade: `0.6.0` applies `requireComparable` to **both** operands of `==`/`!=` and adds a same-type check,
where `0.5.0` skipped the check entirely for `==`. In a fill or line layer a rejected construct is an
ERROR-severity diagnostic that fails the **whole style**, so a style that prepares today could stop
preparing. That document, and the bump spike, both record that RenG's own suite structurally cannot see
this, because RenG checks in no style documents.

Checked here directly. Across all 34 styles there are **2,824** `==`/`!=` comparisons in modern expression
form — 1,694 in symbol layers, 848 in line, 271 in fill, 11 in fill-extrusion. Of those, the number whose
two operand types are statically determinable **and different** is:

> **zero, in any layer type, in any of the 34 styles.**

Together with Rentile's own `0.6.0` corpus run producing non-trivial mosaics for all 34 ids one minute
before the release commit, that is two independent lines of evidence that no style in this corpus stops
preparing at `0.6.0`. Neither is a RenG harness run, so neither says anything about RenG's *composite*
path; that remains the open item.

### The trap this check walked into first, recorded because it is the reusable part

The first version of this measurement reported **564 heterogeneous comparisons in fatal layer types**,
concentrated in style 51 — which looked immediately credible, because Rentile's release-night reports
contain two dedicated `style-51` corpus runs, exactly as if 51 had been the problem style.

It was wrong. The corpus's filters are overwhelmingly **legacy filter syntax**, where the second element is
a *field name* rather than a value:

```
["all", ["==", "class", "river"], ["!=", "brunnel", "tunnel"], ["!=", "intermittent", 1]]
```

`"class"` is a field name and `1` is a value, so a naive reader sees "string compared to number" and counts
a type error that does not exist. 3,867 of the corpus's filters are legacy-form `all`, against 491
top-level `==`. Excluding legacy form — by requiring the first operand to itself be an expression array —
takes the count from 564 to 0.

The general shape is the one this project keeps rediscovering: **a measurement that confirms a suspicion
you already hold deserves more scepticism than one that contradicts it**, not less. The `style-51`
coincidence made the wrong number more believable, not less.

## 7. What this document did not measure

- **Nothing was rendered.** These are counts over style documents, not over frames. How many candidates a
  real viewport produces, how many glyph ranges it needs, and how large the resulting atlas is are all
  still unmeasured — they need a running handover, which is a separate spike.
- **Whether every counted layer actually produces candidates.** A layer's `filter`, zoom range and
  `text-field` expression may yield nothing at a given camera. These counts are what the style *declares*,
  which is the right measure for scope and the wrong one for performance.
- **Whether `0.6.0` renders these styles identically to `0.5.0`.** Rentile's own corpus run produced
  mosaics for all 34 ids at `0.6.0` one minute before the release commit, which is strong evidence that no
  style stops preparing; it is not evidence about RenG's composite path.
- **The two schema families were not reconciled.** Both `transportation_name`/`poi` (OpenMapTiles) and
  `road_label`/`place_label` (Mapbox Streets) appear, so the corpus spans at least two vector schemas.
  Nothing in RenG or Rentile keys on schema, and nothing here checks whether that matters.
