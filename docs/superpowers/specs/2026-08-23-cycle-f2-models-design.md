# Cycle F-2 — models with textures and animation

**Status:** drafted 2026-08-23 from a grilling session; awaiting owner approval.

This cycle draws GLB models: textured, animated, and deformed by their own baked skinning. Everything it
admits or refuses was decided against **the consumer's own 41 models**, not against glTF's breadth — the
measurements are in `docs/research/2026-08-22-consumer-model-corpus-check.md`.

Map labels, terrain and the globe projection are out of scope. Labels are now their own cycle, `E-labels`,
unblocked since Rentile `0.5.0` shipped `LabelCandidatePlan`.

## What ships

Accessor and BIN-chunk decoding; per-resource vertex, index and array-object handling; node transform
composition and animation sampling; vertex skinning with joint matrices in a uniform buffer; embedded
texture decode with correct sampler state; a model draw pass in the map regime with real occlusion; and
the two defects this cycle is folding in by owner decision.

## The gap this cycle actually closes

The structural half of glTF is already built and the numeric half has never been started. `parseGltf`
produces a genuine scene graph — accessors, buffer views, primitives with attribute maps, node TRS,
scenes, animations with channels and samplers, materials, images, samplers — and the only production
caller reduces it to `Valid`/`Failed` and drops it (`internal/driver/ClassGateRunner.kt`). **No code in
RenG has ever read a byte of the BIN chunk**; `parseGltf` takes `binChunkLength` and never the bytes.

So this is not a parser cycle. It is a decode, upload and draw cycle standing on a parser that already
answers the right questions.

## Public API additions

This cycle grows the ABI, which every recent cycle has avoided. Two additions are unavoidable:

- **`ResourceKind` entries for GPU model objects**, because RenG's contract is that it "exposes API to
  query and free the resources it holds", and model buffers are resources it holds. A model that cannot
  appear in `queryResources()` or be released by `free()` would be the first resource class exempt from a
  promise the renderer already makes.
- **A `RenGErrorCode` for an unsupported anchoring mode**, at `FRAME_PLANNING`, so ADR 0029's rejection
  speaks in the closed vocabulary rather than through a generic invalid-value failure.

The MVP release is internal and breaking is accepted, so the growth is affordable. It should still be the
whole of it: nothing else this cycle adds is public.

## Decisions

### Skinning ships; morph targets do not

**25 of the 41 consumer models declare a skin, but only 7 are truly vertex-skinned** — the two elephants
at 112 joints, three motorbikes at 44, two dinosaurs at 38. The other 18 carry a `skins` array **no node
references**: exporter leftovers. RenG already rejects on `node.skin != null` rather than on the array's
presence, so those 18 pass today; had the check been written the other way, 18 drawable models would have
been refused for dead data.

**Zero of the 41 carry a morph target**, so deferring them is measured rather than assumed. `MORPH_TARGET`
stays a rejection.

### Joint matrices go in a uniform buffer, not a uniform array

Joint counts reach **112**. GLSL ES 3.00 guarantees only 256 vec4 vertex uniforms — 64 `mat4` before
anything else RenG binds — so a uniform array would refuse the elephants. A uniform buffer's guaranteed
16 KB holds 256 `mat4` and covers the corpus with headroom. Capping joints was considered and rejected on
the same measurement: the cap that fits a uniform array is below what the consumer already ships.

### Extra UV and colour sets are ignored, not rejected

`TEXCOORD_1` alone rejects **14 models, and is the sole reason for 10 of them**, for carrying data RenG
never reads — it samples one texture, and `baseColorTexture.texCoord` is `0` in every material in the
corpus. This is the largest win per unit of work in the cycle and arguably a bug rather than a feature.
Widening a subset is a compatible change under ADR 0021's own reasoning, so it can land first.

### `alphaMode: BLEND` is supported

6 materials across 6 models. A per-model two-pass split: opaque with depth writes, then blended sorted
back-to-front with writes off. Rejecting would undo the gain this cycle buys; drawing them opaque is the
silently-wrong option this project keeps declining.

### JPEG stays unsupported

One model carries a JPEG texture. A decoder RenG does not have is not worth building for a single asset,
and the cheaper fix is upstream. It stays rejected by a named code rather than silently, so the reason is
legible when someone hits it.

Reaching **40 of 41 models**, the exception being that one, by decision rather than oversight.

### Sampler state is a trap, not a detail

Every sampler in the corpus asks for `LINEAR_MIPMAP_LINEAR`. **Honouring that filter without generating
mipmaps leaves the texture incomplete, and an incomplete texture samples black** — the identical failure a
previous cycle hit on sticker textures. Generate the chain or clamp the filter; doing neither renders
black models that read as a shader bug.

41 samplers omit `wrapS`/`wrapT`, which the specification defines as `REPEAT`, while `uploadTexture`
hardcodes `CLAMP_TO_EDGE` and `GL_REPEAT` is absent from `GlTokens.kt`. `doubleSided` is true on 109 of
111 materials, which happens to match today's behaviour — `drawFrame` sets a cull mode but never enables
`GL_CULL_FACE` — so only the 2 single-sided materials need it turned on.

### Models are shaded by one fixed light RenG owns

ADR 0026: directional, azimuth 335°, elevation 45°, world-anchored, with an ambient term. It is RenG's
constant and not a consumer-visible feature, so the eventual public lighting API supersedes a written
decision rather than silently changing every render. Stickers, geometries and the ground stay unlit.

### A `SCREEN`-positioned model is refused

ADR 0029, which currently exists as a decision with **no code behind it**. This cycle implements it: a
typed failure at `FRAME_PLANNING`, before acquisition or drawing, never substituting. Only `SCREEN`
*position* is refused; `SCREEN` rotation or scale over a `MAP` position — the billboard — already works.

### Two carried defects are folded in by owner decision

**The map regime must regain intra-regime occlusion.** ADR 0027 has no map-regime draw writing depth,
which is correct for flat quads and billboards and wrong for a mesh: a model has to occlude and be
occluded, including by other models. Reopening that ruling is this cycle's, and it supersedes or amends
ADR 0027 rather than working around it.

**The composite double-multiplies alpha.** `drawFrame` blends already-premultiplied offscreen content with
`GL_SRC_ALPHA`. Invisible under an opaque basemap; wrong the moment a consumer composites RenG over their
own background. Unrelated to models, folded here because it is small and lives in the same pass.

### The draw order is computed twice, and the wrong copy wins

`MercatorSpatialPlanner` already computes the correct heterogeneous stack **including models** — z, then
sticker-before-model, then source index — and `RenGRenderer` discards it and re-sorts in the GL layer.
That reproduces the rule today only because a stickers-only scene makes stable order equal source order.
Models break the coincidence. Use the plan the pure core already produces.

`StickerWorld`/`drawStickers` also owns both regimes inside one drawn-thing type; models make the screen
stack span two programs, so it has to move up.

### Verification is analytical readback, not golden images

Pixel verification is deferred to Cycle J by owner decision
(`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`), so this cycle's
gate matches E-basemap's: relationships asserted over a real GL context on the two targets that can hold
one, with no stored baselines.

What it must assert, chosen because each catches a specific trap this cycle can produce:

- **The model is not black** — the mipmap-incompleteness trap, directly.
- **Skinning deforms** — the same model at two animation times produces different pixels, and differs in
  the region a joint moves. This is the only assertion distinguishing real skinning from a rig that loads
  and does nothing.
- **Animation is bound to time, not frame count** — identical `timeSeconds` gives identical pixels.
- **Occlusion is real** — a model occludes the ground it stands on, and a model behind another is occluded,
  which also exercises the reopened depth rule.
- **The texture is applied** — an interior sample matches a known texel from the embedded image, not the
  material's base-colour factor.
- **Coverage is bounded** — non-clear pixels fall inside the projected bounds, catching a runaway
  transform or an exploded joint matrix.

Two lessons from E-basemap's suite bind here. Fixtures must be **asymmetric**: a symmetric camera cannot
catch a transposed index, and this project shipped a suite of exact-URL assertions that could not catch a
transposition for exactly that reason. And assertions must be **portable**: GitHub's hosted macOS runners
have no GPU and report `Apple Software Renderer`, whose rasteriser is measurably different, so an exact
pixel count is not a portable assertion. State the smallest defect each tolerance still detects.

The suite cannot say a model looks *right* — a subtly wrong skinning weight, an inverted normal, an
off-by-a-little bind pose all pass. That is Cycle J's, and this cycle should say so rather than implying
its gate is stronger than it is.

## Carried in, and owed

`docs/research/2026-08-21-f2-gltf-capability-gap.md` and `…-f2-draw-regime-and-occlusion.md` are this
cycle's findings of record. The transform pipeline genuinely resolves position, rotation and scale
independently by mode, with all eight combinations tested — that is not a shortcut to unpick.

Known-unfixed and inherited rather than introduced: `SCREEN` scale on a `MAP` position is
perspective-divided, contradicting `CONTEXT.md`'s "output pixels per local unit"; and the GLB tightening
left seven named follow-ups (sampler `output.count` versus `input.count`, `min`/`max` presence, duplicate
`(node, path)` animation channels, sampler filter and wrap enums, `baseColorTexture.texCoord != 0`,
unbounded `buffers[i>0].byteLength`, and strictly-increasing animation inputs — the last belonging to the
decoder this cycle builds).
