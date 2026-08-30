# Cycle K — implementation plan

Authority is `docs/superpowers/specs/2026-08-31-cycle-k-frame-plan-corpus-design.md`. Read it before any
task. Where this plan and the design disagree, the design wins; where either and an ADR disagree, the newer
ADR wins.

## The shape of the cycle

Seven tasks in three waves. Wave 1 makes a `FramePlan` serialisable and is the only wave that touches
RenG's published surface. Wave 2 rebuilds the harness around it and writes the corpus. Wave 3 is the
comparison script, CI, and the human baseline review that gives every later run its meaning.

**The baseline review is the deliverable, not the script.** A comparison against a baseline nobody looked
at proves only that two commits agree.

---

## Standing obligations for every task

Earned by specific failures, every one of them in this repository.

- **Every task ends green.** Python suite, `check_repository_policy.py`, `checkKotlinAbi`,
  `:kmp:testAndroidHostTest`, `:kmp:macosArm64Test`, `:kmp:iosSimulatorArm64Test`.
- **Measure your own baseline by stashing.** Do not trust a count in this document.
- **Never read Gradle's verdict through a pipe.** `./gradlew … | tail` reports *tail's* status, so a
  failing build looks green. Capture to a file and grep `BUILD SUCCESSFUL`, or run unpiped and check `$?`.
- **An absent measurement is evidence.** Unmoved counts and missing JUnit XML both mean the tests did not
  run.
- **Mutation-test your own tests** and report **which single test caught each**. A mutation caught by every
  case is weaker evidence than one caught by exactly one.
- **A mutation that does not compile is not a caught mutation.**
- **Look for the symmetry point first.** E-terrain found more than fifteen vacuous checks and three were
  the same shape — a fixture where the thing under test is the identity. Before trusting a green fixture,
  ask what it is *not* varying.
- **Never commit a style URL or an API key**, in code, in a fixture, or in a commit message.

## Traps specific to this cycle

- **A serialised field that nothing round-trips is a field that silently does not exist.** The whole point
  of serialising `FramePlan` is that a corpus plan cannot under-specify a frame; a property missing an
  annotation reintroduces exactly the drift that mirror DTOs would have had.
- **`assertContentEquals` over a whole frame overflows Gradle's 1 MB service-message limit and loses the
  assertion.** Assert a difference count first.
- **A comparison that renders the same commit twice always passes.** Any test of the script must prove it
  fails on a genuine difference, or it is a test of nothing.

---

## Wave 1 — a Frame Plan becomes a document

### Task 1 — the ADRs

Two, written before the code they govern.

- **Take kotlinx-serialization-core as a first-party production dependency.** RenG's third, after ADR
  0019's coroutines. Must record: that it is `-core` only and therefore **format-neutral**, committing to
  field names rather than to a wire format; that RenG is one published coordinate (rentile ADR 0002), so
  every consumer on six targets carries it with no opt-out; that the serialization **compiler plugin** is
  part of the cost; and that the measured alternative — reusing the existing canonical TLV encoding — was
  rejected because it is unreadable and cross-language readers would have to be written by hand.
- **Compare frames exactly, and never with a tolerance.** Must record the measurement that makes exactness
  attainable (one machine, one driver, two commits) and the measurements that make a tolerance unusable:
  `Apple Software Renderer` at atan 1,687 / sin 170,439 / cos 117,441 ULP, against real defects found at
  1-of-16,384 and 14-of-589,824.

### Task 2 — `@Serializable`, pinned and gated

Annotate `FramePlan` and everything it reaches — `Camera`, `Placement`, `Vector3`, `Sticker`, `Model`,
`Geometry`, `ShaderPair`, `ShaderValue`, `ResourceLocator`, `AnimationTrack`, `AnimationSelector`, and the
enums `ProjectionMode`, `AnchoringMode`, `AltitudeMode`.

- **Every property carries an explicit `@SerialName`.** A Kotlin rename must not move the wire form.
- Catalogue the coordinate and the plugin; widen `_PERMITTED_NEW_DEPENDENCIES`; move
  `kmp/build.gradle.kts` and the catalog, and **all three accepted fingerprints of each** — the recompute
  recipe is in `HANDOFF.md` and these are token-stream digests, not whole-file ones.
- **This moves `kmp/api/kmp.klib.api`.** Report the exact diff; a `@Serializable` class gains a companion
  and a `serializer()`, so expect real movement and confirm every line is intended.

**Tests:** a round-trip of a representative plan is byte-identical; **a checked-in JSON fixture whose bytes
must not move**, in the shape ADR 0018 already uses for canonical digests; and a test that fails if any
public `FramePlan`-reachable property lacks a `@SerialName`, so the annotation cannot be forgotten on a
field added later.

**Mutations to run:** drop one `@SerialName`; rename a property without updating its `@SerialName`; drop
`@Serializable` from a nested type.

---

## Wave 2 — the harness reads documents

### Task 3 — config and plan file replace the flag pile

Replace `--style`, `--model`, `--frames`, `--no-basemap`, `--no-labels`, `--globe`, `--zoom`,
`--zoom-span`, `--static-camera` with `--config`, `--plans`, `--out`.

- A config maps to `RendererConfiguration` plus a **`styleId`**; the base URL comes from
  `local.properties` or `.env`, and `<base>/<styleId>` composes the URL. **A missing base fails loudly.**
- A plan file is a JSON array decoded straight into RenG's types — no mirror DTOs, which is the point of
  Task 2.
- The storyboard becomes **one plan file among the corpus** so the existing video flow keeps working.

**The old parser is where a false accusation against the renderer came from** — `parseArguments` advanced
`index += 2` unconditionally and read only even positions, so `--zoom` behind a valueless `--globe` was
silently ignored and the globe looked broken for hours. Whatever replaces it must make that class of
failure impossible: an unrecognised argument is an error, never a shrug.

**Tests:** an unknown argument fails; a missing style base fails with the variable's name; a plan file
round-trips into the same frames the storyboard produces today.

### Task 4 — the corpus

**~18 plans × 2–3 configs, one frame each.** Cover each feature once, and cover the interactions that have
actually broken here: a model behind a mountain; a sticker coplanar with terrain; a label on a ridge; the
globe's limb; tile seams; the polar cap; a draped geometry over relief; `GROUND_RELATIVE` against
`ABSOLUTE` in one frame.

Configs vary output size (1080p and 4K), `terrainShading`, and `styleId` across a raster style, a vector
style, and a terrain style.

**Name every plan for what it proves**, not for what it contains — a reviewer reads the name before the
image.

---

## Wave 3 — comparison, CI, and the review that gives it meaning

### Task 5 — `tools/compare_rendered_frames.py`

Standard library only, like every tool in `tools/`.

The flow, from the design: enumerate `(plan, config)`; render any missing baseline by creating a
**temporary worktree at `main`** and running the harness there; render the working tree; compare byte for
byte; report.

- missing in main, present here → **warning** (a new plan or config)
- missing here, present in main → **failure**, with an explicit flag to accept an intended removal
- stale baselines are **never cleared**; the failure names what to delete
- **gzipped PPM** on disk, compared decompressed — measured at 5.0×, 0.11 GiB for ~90 images at 1080p
- a difference reports plan, config, frame, pixel count, max channel delta, bounding box, both paths, and a
  difference image

**Tests, and the third is the one that matters:** the flow works end to end on a fixture; a missing
baseline triggers exactly one worktree render; **a genuine one-pixel difference fails**. A script tested
only against identical inputs is a script that has never been shown to work.

**Clean up the temporary worktree even when the render fails.** This session left worktrees behind twice.

### Task 6 — CI

A `pull_request` job, gated to PRs whose `VERSION_NAME` bumps **major or minor**. Compares PR head against
main on one macOS runner. Not on push-to-main, where the comparison is degenerate; not in `publish.yml`,
where render time is the cost this gating exists to avoid.

The style base URL is a repository secret. **Never echo it**, and follow the redaction rule the firewall
already lives by.

The suite's own output must say that CI's images are **a statement about change, never about
correctness**, because the hosted runner's rasteriser is one the project's own probe distrusts.

### Task 7 — the baseline review, and closing docs

Render the whole corpus and **look at every frame**. This is the deliverable: it is the only step that
establishes the current renderer is right, and every later comparison inherits its authority.

Record what was seen, as E8 requires — not that images exist, but what each shows and whether it is
correct.

Then: `CLAUDE.md`, `HANDOFF.md`, `CONTEXT.md` (**Frame Plan** gains its serialisable form), and
`docs/decomposition.md` — **withdraw J with its reasoning, in the shape Cycle I's withdrawal already uses,
and add K**.

---

## Closing obligations

- The ABI dump moves **once**, in Task 2, and by an amount that is reported and intended.
- Record what the cycle leaves owed, measured rather than suspected.
- The corpus images are **not** committed, and `.gitignore` must say so before the first render.
