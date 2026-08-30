# Cycle K — the frame-plan corpus and the branch-comparison harness

A **Frame Plan** becomes a serialisable document. The harness stops taking a growing pile of ad-hoc flags
and instead takes one initialisation config plus a file of frame plans. A script renders the same corpus
from `main` and from the working branch and compares the images **byte for byte**.

This cycle **replaces Cycle J**, which is withdrawn. Everything below was settled by grilling on
2026-08-31 or measured on this checkout.

---

## 1. Why the golden-image corpus was the wrong instrument

J's design (`docs/research/2026-08-21-golden-image-gate-design.md`, 769 lines) is careful and its
reasoning is sound. Its problem is the thing it was fighting: **cross-machine determinism, which RenG
cannot have.** Its §3.2 lists seven divergence sources and can only answer four of them with "absorb it
with a tolerance".

The measurements since make that worse rather than better. Cycle G's trig probe found
`Apple Software Renderer` — the driver every hosted macOS runner uses — evaluating transcendentals at
**atan 1,687 ULP, sin 170,439, cos 117,441**, which put a naive latitude formulation **7,118 m** from
where a `Double` reference says. A globe frame's geometry passes through exactly those functions. No
tolerance both admits that and catches a real defect.

**Comparing two commits on one machine dissolves the whole problem.** Same driver, same libm, same
rasteriser: the only variable left is RenG's own source. Exactness stops being unattainable and becomes
the natural setting.

**A correction to this document's first draft.** It justified exactness with "defects found at 1 pixel of
16,384 and 14 of 589,824". **Those two figures trace to no record in this repository** — the frame sizes
are real (`BASEMAP_READBACK_PIXELS` squared, and the terrain suites' 768²) but the counts were not measured
and should not have been written as though they were. The argument stands on a number that *is* recorded:
the same software rasteriser drops **3,005 of 15,876 interior pixels** on a large quad
(`CLAUDE.md`, the `0.3.0` failure), so the noise a cross-machine tolerance must admit is roughly **3,000×**
the signal a real regression produces in the same units and the same frame. Exactness is not a preference;
it is the only setting in which that ratio does not swallow the signal.

J's preflight (`docs/research/2026-08-30-j-corpus-preflight.md`) also recorded that J's central premise had
inverted: it argued for stored baselines partly because nothing else asserted pixels, and **thirteen
analytical readback suites** have landed since. J is withdrawn; its two documents stay as the record of
why.

## 2. What a Frame Plan becomes

**`FramePlan` and everything it holds gain `@Serializable`, and RenG depends on
`kotlinx-serialization-core` only.**

**Format-neutral, deliberately.** The annotation and the generated serializers live in `-core`; JSON, CBOR
and ProtoBuf are separate artifacts. So RenG commits to **field names and shapes, not to a wire format**:
the harness reads JSON because a developer must be able to read and hand-edit a frame plan, a rendering
server can put ProtoBuf on the network, and an Android client can write CBOR to a data store — all from
one set of annotations, with the format dependency chosen by whoever needs it.

**This is a public commitment and is priced as one — and the first draft under-priced it.** The policy
checker's `_FORBIDDEN_DEPENDENCY` rejects the word **`serialization`** by name, case-insensitively: the rule
was written to keep exactly this out, and only an explicit allowlist entry admits it, which is the same
mechanism ADR 0019 used for coroutines. Beyond `_PERMITTED_NEW_DEPENDENCIES` and the three accepted
fingerprints each on the catalog and `kmp/build.gradle.kts`, there are **two further refusal points**:
`_EXPECTED_PLUGIN_BLOCKS` pins the exact token sequence of every `plugins { }` block, and `_PLUGIN_ACCESSORS`
lists the permitted `libs.plugins.*` accessors. A sixth is conditional — `build.gradle.kts` holds exactly
**one** accepted fingerprint form. **Budget five or six edits, not three.**

What is new is not "a plugin rather than a coordinate" — three Gradle plugins already apply here. It is a
**compiler** plugin that generates public members (`serializer()`, a `Companion`) into the published
artifact and therefore into the ABI dump. It is versioned in lockstep with Kotlin, so the catalog's
`[plugins]` entry reuses the existing `kotlin` version row.

**And the consumer cost is smaller than first written.** Rentile `0.7.0` — which RenG already pins —
declares `kotlinx-serialization-json 1.11.0` at `runtime` scope in its aggregate POM, so **no consumer's
resolution graph gains an artifact**. What changes is RenG owning a version it previously inherited. RenG
remains one published coordinate (rentile ADR 0002), so there is still no opt-out; there is simply nothing
new to opt out of.

**Why JSON was compared and not simply assumed.** Measured on a realistic 600-frame plan array — ten
seconds at 60 fps, two stickers, a model and a geometry per frame:

| encoding | 600 frames |
|---|---|
| JSON, pretty | 2.2 MiB |
| JSON, compact | 1.4 MiB |
| canonical binary TLV (from the measured 1,526 B/frame) | 0.9 MiB |
| **JSON, compact + gzip** | **37 KiB** |

**Compression beats the binary format by 24×**, because **41% of the payload is byte-identical GLSL
repeated every frame** on top of enormous inter-frame redundancy. A binary format saves 1.6× per frame;
compression saves 40× across frames, and a compressed binary format would beat compressed text only
slightly. Once size stops deciding, debuggability decides — and that is why the *harness* reads JSON while
RenG commits to no format at all.

**The field names are pinned and gated.** Every serialised property carries an explicit `@SerialName`, so
a Kotlin rename cannot silently change the wire form. A checked-in fixture must round-trip byte-identically,
which is exactly the shape ADR 0018 already uses for canonical-identity fixtures whose digests must not
move.

**One thing deliberately not solved.** The 41% GLSL duplication is a property of `FramePlan` being *a
complete definition of on-screen state* — each plan must stand alone, so it must repeat its shaders. A
document format could hold a shared pool and materialise complete plans on read. That is a document-level
design and is out of scope.

## 3. The harness takes a config and a plan file

Today's harness carries `--style`, `--model`, `--frames`, `--no-basemap`, `--no-labels`, `--globe`,
`--zoom`, `--zoom-span`, `--static-camera`, `--verbose` — a flag per feature, growing every cycle, and one
of them has already produced a false accusation against the renderer (the `index += 2` stride bug, recorded
in `CLAUDE.md`). That surface is replaced:

```
harness --config <config.json> --plans <plans.json> --out <dir>
```

- **A config is initialisation only**, and maps to `RendererConfiguration`: output pixel size, resource
  limits, the three maximums, `terrainShading`, and a **`styleId`**.
- **A plan file is a JSON array of `FramePlan`s**, decoded straight into RenG's own types.

**The style secret stays out of the repository.** A config names a `styleId`; the base URL lives in
`local.properties` or `.env` — already untracked and machine-specific by convention — and the harness
composes `<base>/<styleId>`. A missing base fails loudly rather than rendering something misleading.

The storyboard does not disappear; it becomes one plan file among the corpus, so the existing video flow
keeps working.

## 4. The comparison script

`tools/compare_rendered_frames.py`, standard library only, like every other tool in `tools/`.

```
for each (plan, config):
    if the baseline images are missing:
        create a temporary worktree at main, render there, copy the images in
    render the same (plan, config) from the working tree
    compare byte for byte
```

- **Missing in main, present here** → a **warning**: a new plan or config the developer added. Reviewed,
  not failed.
- **Missing here, present in main** → a **failure**, since a plan that stopped rendering is the defect this
  exists to catch. An explicit flag lets a developer say the removal was intended.
- **Stale baselines are never cleared.** The filesystem records when each was written; deleting one is
  always safe and forces a re-render, and the failure output says which set to delete.
- **Images are never committed.** They live in a gitignored directory.

**Storage is gzipped PPM.** The harness keeps writing plain PPM — putting an encoder inside verification
code is exactly what Cycle I withdrew, and a PPM is a header and raw bytes — and the script compresses for
storage with Python's standard `gzip`. Measured on a real rendered frame: **5.0×**, 0.01 s to compress and
0.001 s to decompress, taking ~90 images from 0.52 GiB to **0.11 GiB** at 1080p and from 2.09 GiB to
**0.42 GiB** at 4K. Comparison is on the decompressed bytes and is therefore exact.

**A difference is reported so it can be reviewed in seconds**: plan, config and frame; how many pixels
differ; the maximum channel delta; the bounding box of the change; and paths to both images plus a
difference image.

## 5. The corpus

**Roughly eighteen plans across two or three configs, one frame each** — about ninety images, reviewable in
one sitting, which is the constraint that sets the size: the baseline is established by a human looking at
every frame and declaring it correct.

Coverage is chosen twice over: **each feature once**, and **the interactions that have actually broken in
this project**, which are known and specific — a model behind a mountain, a sticker coplanar with terrain,
a label on a ridge, the globe's limb, tile seams, and the polar cap.

## 6. CI

**On `pull_request`, gated to PRs that bump `VERSION_NAME`'s major or minor.** Ordinary pull requests stay
fast; a release PR pays for a full render of both sides. It compares **PR head against main**, which is the
same thing the local flow does.

It is deliberately **not** on push-to-main: there, the working branch *is* main and the comparison is
degenerate. It is deliberately not in `publish.yml` either, because render time in the publish path is the
cost this gating exists to avoid.

CI has no baseline cache, so it renders both sides every run. The hosted macOS runner gives
`Apple Software Renderer`, whose rasteriser the project's own probe distrusts for some cases — which does
**not** harm an A/B comparison, since both sides use the same renderer, but does mean **CI's images are a
statement about change, never about correctness**.

## 7. What this cycle does not do

- **No cross-machine baselines.** That was J and it is withdrawn.
- **No tolerance, no perceptual metric.** A difference is a difference, reported for review.
- **No encoder in the harness.** PPM out, `ffmpeg` and `gzip` outside.
- **No shared-pool document format**, however tempting the 41% duplication makes it.
- **No claim that a green comparison means correct** — only that nothing changed. Correctness is what the
  human baseline review and the thirteen analytical readback suites are for.
