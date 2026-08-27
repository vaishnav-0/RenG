# E-labels: the gap between RenG's renderer and drawing map text

Research only. No code was changed, no branch was created, nothing was committed. Every claim below
carries a `file:line` citation or a command and its output, against `main` at `6730722` (Cycle H merged;
Cycle F-2 merged at `63528ce`; `VERSION_NAME=0.4.0`; Rentile pinned at `0.5.0` in
`gradle/libs.versions.toml:7`).

**Scope.** This document answers *what RenG must build to consume a label handover*. It deliberately does
not measure Rentile. Where a fact belongs to the engine's surface it is named as belonging there and left
to `docs/research/2026-08-28-e-labels-rentile-surface.md`.

## Summary answer

**Labels do not fit RenG's existing drawn-thing machinery, and the reason is structural rather than
incidental: every drawn thing RenG can currently draw is named by an index into a `FramePlan` list.**
`DrawnThingReference` is a sealed interface with exactly two members, `StickerAt(index)` and
`ModelAt(index)`, both of which mean "the *n*th entry of the caller's own list"
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/MercatorSpatialPlanner.kt:22-26`). A label
has no plan entry to be the *n*th of. It is derived from the style, the camera and the tiles — three things
the plan names indirectly at most — so a `LabelAt(i)` reference would index a list RenG itself synthesised,
which is a different claim from what the other two make and would quietly change what the type means.

That structural mismatch is the single most consequential finding, and it propagates: `Scene`'s
construction-time bijection is stated as *"every sticker and every model must appear in exactly one of
mapOrder and screenOrder"* and enforced as
`require(referenced.size == stickers.size + models.size)`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt:203-205`). Labels satisfy that
requirement most cheaply by being **outside** both orders — a third scene list nothing references — which is
exactly the question ADR 0024 left open and never answered: where an engine-derived stack sits relative to
the consumer-supplied screen stack.

Three further conclusions, each measured rather than reasoned:

1. **The screen stack's per-element loop is the wrong shape for batched text.** `drawScreenStack` binds a
   program per *element* and its KDoc says interleaving two types "costs a program switch and nothing else"
   (`SceneContent.kt:404-406, 407-428`). For a batched text pass a program switch costs a **batch flush**,
   not nothing. One draw call per sticker is what `drawOneSticker` does today
   (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/StickerPipeline.kt:224-236`), and it is the
   precedent labels must *not* follow.

2. **The GL seam needs nothing new for a dynamic vertex buffer, and does need two entry points for
   instancing.** `bufferData` already accepts a null payload for orphaning
   (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt:68`) and F-2 already runs a
   respecify-per-frame buffer through `bufferSubData`
   (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/ModelPipeline.kt:405, 730-736`). `uploadModelPrimitive`
   is **not** that precedent — it is `GL_STATIC_DRAW`, uploaded once and cached
   (`ModelPipeline.kt:476-511`). The measured cost of an entry point is `90088a6`: four of them, 12 files,
   242 insertions.

3. **Nothing in `commonMain` projects a geographic position to an output pixel.** Every use of
   `outputPixelSize.width` in production source is one of six, and none of them is a forward projection
   (verified exhaustively, without `head`, below). Collision needs precisely that function, in `Double`, on
   the CPU, at planning time — and it is new.

Public API is the one place the news is good: **labels can be drawn with no public ABI addition at all,
provided the cycle accepts three specific consequences**, spelled out in §5.

---

## 0. What moved since the prose was written

`CLAUDE.md` and `docs/research/2026-08-21-rentile-upgrade-and-labels.md` both lag the tree. Corrected here
because §1–§6 depend on the corrections.

**F-2 is merged into `main`, not sitting on `feat/f2-models`.**

```
$ git merge-base --is-ancestor feat/f2-models HEAD && echo "f2 IS merged into main"
f2 IS merged into main
$ git log --oneline HEAD ^49cc1d5 | wc -l
      80
```

`CLAUDE.md`'s "Cycle F-2 is complete, unreleased, and lives on `feat/f2-models`" paragraph and its
"`main` itself is `9a43b14`, ten ahead of the released `49cc1d5`" are both stale.

**The exact public ABI delta since the released `0.3.0` is three enum entries, and they are not the three
`CLAUDE.md` implies.**

```
$ git diff 49cc1d5843ba40ac866f96d00123ca07b662f6fc HEAD -- kmp/api/kmp.klib.api
+    enum entry UNSUPPORTED_ANCHORING_MODE   (RenGErrorCode)
+    enum entry MODEL_GEOMETRY               (ResourceKind)
+    enum entry MODEL_IMAGE                  (ResourceKind)
$ git diff 63528ce HEAD --stat -- kmp/api/kmp.klib.api
(no output — Cycle H moved no ABI)
```

`ResourceKind.BASEMAP_TILE` predates F-2; it arrived in `b5aaed8` ("add the five public declarations Cycle
C requires"). `CONTEXT.md`'s **Resource Kind** entry still lists four constants — `EXTERNAL`,
`GEOMETRY_PROGRAM`, `INTERNAL_PIPELINE`, `OFFSCREEN_SURFACE` — where the source has seven
(`kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt:53-61`). That is a documentation drift E-labels
should fix in passing, not a finding about labels.

**§3.4 of the 2026-08-21 labels research is stale in one specific row.** Its "Mechanical, reusing what
already exists" table says labels may "Draw quads in the screen regime" by reusing
"`internal/gl/StickerPipeline`'s `screenAnchored` path". That path no longer exists. F-2 Task 14 deleted
`StickerWorld.screenAnchored` and moved the screen regime into `SceneContent.drawScreenStack`;
`StickerWorld` now carries only `mapAnchored` (`StickerPipeline.kt:163-165`), and its own KDoc records the
move: *"The screen regime is no longer this class's."* (`StickerPipeline.kt:156-161`). Everything §3.4 says
about that row has to be re-derived against `drawScreenStack`, which §1 does.

The rest of §3.4 holds. Its seven-item "new work" list is still the right list; this document measures each
item against the tree as it now is.

---

## 1. The screen regime as it now stands, and what a text primitive must supply

### 1.1 The two authorities

`MercatorSpatialPlanner` is the sole authority on regime membership and screen order, and `SceneContent`
consumes its answer rather than re-deriving it. That is F-2 Task 14's change and it is stated twice, in
`Scene`'s KDoc (`SceneContent.kt:148-169`) and `SceneContent`'s (`SceneContent.kt:220-231`), both of which
record that the GL layer used to compute both a second time and that the second copy was the untested one.

The planner's output is two lists of `ResolvedDrawnThing(reference, placement)`
(`MercatorSpatialPlanner.kt:28-31`), split by `placement.drawRegime` (`:216-224`) and, for the screen half,
sorted by:

```kotlin
private val screenCompositingOrder: Comparator<ResolvedDrawnThing> =
    compareBy<ResolvedDrawnThing> { requireNotNull(it.placement.screenCompositeZ) }
        .thenBy { it.reference.typeOrder }
        .thenBy { it.reference.sourceIndex }
```

(`MercatorSpatialPlanner.kt:265-269`). Three of those four expressions are hostile to labels:

- **`screenCompositeZ`** is `requireNotNull`, and it is non-null exactly when the regime is
  `SCREEN_COMPOSITED` — `ResolvedPlacement`'s own `init` enforces the biconditional
  (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/PlacementResolver.kt:29-33`). Its value is
  `placement.position.z`, the caller's z-index (`PlacementResolver.kt:82`). **A label has no caller z.**
- **`typeOrder`** is a two-arm `when` returning 0 for stickers and 1 for models
  (`MercatorSpatialPlanner.kt:270-274`).
- **`sourceIndex`** is a two-arm `when` returning the plan-list index (`:276-280`).

### 1.2 What entering `drawScreenStack` actually costs

```kotlin
private fun drawScreenStack(binding: GlBinding) {
    if (scene.screenOrder.isEmpty()) return
    binding.disable(GL_DEPTH_TEST)
    var boundProgram: Int? = null
    for (reference in scene.screenOrder) {
        when (reference) {
            is DrawnThingReference.StickerAt -> {
                if (boundProgram != stickerPipeline.program) {
                    beginStickerPass(binding, stickerPipeline)
                    boundProgram = stickerPipeline.program
                }
                drawOneSticker(binding, stickerPipeline, screenCompositedSticker(scene.stickers[reference.index]))
            }
            is DrawnThingReference.ModelAt -> throw IllegalArgumentException(...)
        }
    }
}
```

(`SceneContent.kt:407-428`.) To enter this loop a text primitive must supply, concretely:

| Required | Real type | Where the sticker gets it |
|---|---|---|
| A `DrawnThingReference` subtype | `sealed interface DrawnThingReference` | `MercatorSpatialPlanner.kt:22-26` |
| An entry in `Scene.screenOrder` | `List<DrawnThingReference>` | `SceneContent.kt:179, 182` |
| An indexable scene list | `Scene.stickers: List<SceneSticker>` | `SceneContent.kt:175` |
| A program to bind and a per-element draw | `beginStickerPass` / `drawOneSticker` | `StickerPipeline.kt:207, 224` |
| A composited z to sort by | `ResolvedPlacement.screenCompositeZ: Double?` | `PlacementResolver.kt:28` |

The KDoc's own justification for the per-element bind is that *"interleaving two types in one stack costs a
program switch and nothing else. With one type it binds once."* (`SceneContent.kt:404-406`). **That
sentence is true for two per-element pipelines and false for a batch.** A batched text pass accumulates
quads into one buffer and issues one draw; a program switch in the middle of the stack forces the batch to
flush at that point. If labels enter this loop as elements, either the loop becomes flush-aware or every
interleaved sticker splits the text into another draw call. That is a real design decision, not an
implementation detail, and it is the first thing the grilling should settle.

### 1.3 What `Scene`'s bijection costs

```kotlin
require(referenced.size == stickers.size + models.size) {
    "every sticker and every model must appear in exactly one of mapOrder and screenOrder"
}
```

(`SceneContent.kt:203-205`, preceded by the no-duplicate check at `:186-191` and the in-range check at
`:192-202`.) Its KDoc states the intent: a caller that "simply forgot to pass an order at all … would
otherwise draw a frame's stickers and models silently missing rather than reporting anything"
(`SceneContent.kt:165-169`).

There are **two** ways to satisfy it with labels, and they differ in cost by an order of magnitude.

**(a) Labels become a third `DrawnThingReference`.** Six exhaustive `when` sites break at compile time,
which is the good failure:

```
$ grep -rn "is DrawnThingReference\.\|is StickerAt\|is ModelAt" kmp/src
MercatorSpatialPlanner.kt:272,273   (typeOrder)
MercatorSpatialPlanner.kt:278,279   (sourceIndex)
SceneContent.kt:194,195             (Scene init: available)
SceneContent.kt:198,199             (Scene init: index)
SceneContent.kt:360,361             (draw: map split)
SceneContent.kt:414,421             (drawScreenStack)
```

Plus the bijection's arithmetic (`stickers.size + models.size` becomes a three-term sum), plus every
`Scene` construction. Measured:

```
$ grep -o "\bScene(" kmp/src/commonTest/.../SceneContentTest.kt | wc -l
      33
$ grep -c "mapOrder = \|screenOrder = " kmp/src/commonTest/.../SceneContentTest.kt
      30
```

Thirty argument occurrences across 33 `Scene(` constructions, of which five constructions pass both lists
(lines 246/247, 274/275, 334/335, 436/437, 817/818) — so **25 distinct test call sites pass an explicit
literal order**, plus two production sites (`RenGRenderer.kt:658-659` in `prepare`, `:1297-1298` in the
draw path). None goes through a helper; every one names `StickerAt(n)` / `ModelAt(n)` inline. Adding a
third arm is mechanical but touches 27 places, and each is a place a reviewer must check for a wrong index
rather than a compile error.

**(b) Labels are a fourth scene list, outside both orders.** `Scene` already carries `geometries` and
`groundTiles` this way — neither appears in `mapOrder`, and the bijection counts only stickers and models
(`SceneContent.kt:174-177, 203-205`). Geometries draw at a fixed position in the map regime's phase order
(`SceneContent.kt:326-349`), not by reference. Labels drawn as one batch at a fixed position in the screen
regime is exactly the same shape, and it costs **zero** of those 27 sites.

The price of (b) is that labels then have no ordering relationship with screen-anchored stickers other than
"always before" or "always after" — which is precisely the rule ADR 0024 never wrote down. ADR 0024 fixes
*map-then-screen*; it says nothing about a third, engine-derived stack, and the 2026-08-21 research already
flagged this as ADR-worthy (§3.4 item 6). Nothing in the tree has answered it since.

**Recommendation for the grilling, not a decision:** (b) is the honest shape, because labels genuinely are
not plan-indexed and (a) would make `DrawnThingReference` mean two different things. But (b) needs the ADR
first, because "labels always on top of consumer stickers" and "labels always beneath" are both defensible
and only one can be right.

---

## 2. What has no analogue at all

Exhaustive, in the order a frame would need them. "Nearest existing thing" means code that could be reused
or copied; "new" means nothing in the tree does this.

### 2.1 Glyph atlas texture management — **nearly free, with one sharp edge**

**Nearest existing thing: `uploadTexture` plus `GlObjectRegistry.registerTexture`.** The decode side is
free — `decodePng` yields canonical RGBA8 (`internal/image/DecodedImage.kt:6`), hardened by five
adversarial passes. The residency side is free — `registerTexture(key, handle, byteSize)` returns a
`TextureLease` and enrols the texture in the byte-budgeted LRU
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistry.kt:93-103`), and `leaseResident`
is its reuse half (`:125-131`).

**The sharp edge is the sampler, and it is already written down as an accepted cost:**

```kotlin
internal fun defaultSamplerStateFor(content: TextureContent): TextureSamplerState = when (content) {
    TextureContent.IMAGE -> TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
    TextureContent.DATA -> TextureSamplerState(GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
}
```

(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlTextureUpload.kt:46-49`.) Its KDoc names the
exact problem, unprompted, before labels existed:

> "The accepted cost is that a signed-distance field wants linear filtering and loses its antialiasing
> under this rule; a per-texture filter choice — letting a consumer opt a specific data texture into linear
> — is the intended additive fix if that cost turns out to matter, not an oversight."
> (`GlTextureUpload.kt:42-45`)

`TextureContent.IMAGE` premultiplies alpha into RGB (`:122`, `premultiplyAlpha` at `:163-176`);
`TextureContent.DATA` uploads bit-exact. **Neither default is right for a glyph atlas**, which wants
unpremultiplied bytes *and* linear filtering. The good news: it needs no new API. `uploadTexture` already
takes an explicit sampler that overrides the default:

```kotlin
internal fun uploadTexture(
    binding: GlBinding,
    image: DecodedImage,
    content: TextureContent,
    sampler: TextureSamplerState = defaultSamplerStateFor(content),
): Int
```

(`GlTextureUpload.kt:114-119`.) So `uploadTexture(binding, atlas, TextureContent.DATA,
TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE))` is the whole answer, and it
is the "intended additive fix" that KDoc already sanctions.

**A vacuity warning, in the spirit of the seven-plus-two.** If the atlas convention is white RGB with the
payload in alpha, then `premultiplyAlpha` — which multiplies RGB by A and leaves A untouched
(`GlTextureUpload.kt:163-175`) — turns RGB into a *copy* of the alpha channel and destroys nothing the
shader reads. A test that asserts "the atlas was uploaded unpremultiplied" by comparing RGB **passes under
both `IMAGE` and `DATA`** if it samples where alpha is 255, and passes under neither interpretation
meaningfully anywhere else, because the only channel that matters is the one premultiplication never
touches. The real assertion is about the *sampler*, not the bytes: read `glTexParameteri` from the call log,
or read back a texel between two glyph cells where linear and nearest differ.

### 2.2 A text quad batch — **new, and the single largest piece of drawing work**

**Nothing in the tree batches anything.** Every draw in RenG is one call per logical object:

| Pass | Draw shape | Citation |
|---|---|---|
| Sticker | `drawArrays(GL_TRIANGLE_STRIP, 0, 4)` per sticker, MVP uniform per sticker, texture bind per sticker | `StickerPipeline.kt:224-236` |
| Ground | one draw per tile instance | `GroundPipeline.kt` via `SceneContent.kt:311-324` |
| Geometry | one draw per `Geometry`, consumer program each | `SceneContent.kt:333-348` |
| Model | `drawElements(GL_TRIANGLES, …)` per primitive | `ModelPipeline.kt:705-710` |

The sticker pipeline allocates exactly one vertex buffer for the whole renderer, a unit quad, reused for
every sticker in every frame (`StickerPipeline.kt:48-53`, and its KDoc says so at `:16-18`). Per-instance
variation travels entirely through `uniformMatrix4fv`. **That design does not scale to thousands of glyph
quads** — it is one uniform upload and one draw call per quad — and it is the precedent labels must break
from, not follow.

What is genuinely new: a CPU-side vertex assembly step that walks the frame's surviving labels, writes
position/UV/colour per glyph corner into one array, and issues one draw. Nothing in RenG builds geometry
per frame today except the joint palette (§3.2).

### 2.3 SDF or bitmap sampling — **new shader, no analogue**

`STICKER_FRAGMENT_SOURCE` is `texture(rengStickerTexture, rengStickerUv)` and nothing else
(`StickerPipeline.kt:31-39`). There is no `smoothstep`, no iso-line, no second band, and no colour uniform
anywhere in RenG's own shaders. `GroundPipeline` and `CompositePipeline` are the same shape. The model
shader is the only one with any shading maths at all, and it is a Lambert term against one fixed direction
(`internal/gl/SceneLight.kt`, ADR 0026).

The GL seam already carries `uniform1f`, `uniform2f`, `uniform4f` (`GlBinding.kt:99-102`), so halo width,
halo blur, colour and halo colour are expressible without touching it. Colour unpacking from a packed
integer is CPU-side arithmetic. Nothing here needs a new entry point; it needs a new pipeline file of
roughly `StickerPipeline.kt`'s size (245 lines) plus a variant story if halo-off and halo-on are separate
programs — F-2's `ModelShaderVariant` is the precedent for that, and it cost eight variants and 43 KB of
`ModelPipeline.kt`.

### 2.4 Per-glyph positioning — **partially new**

If the handover ships laid-out quads in label-local coordinates (the 2026-08-21 measurement says it does;
confirm against the sibling document), then shaping, line breaking and advance accumulation are not RenG's.
What remains RenG's is: project the geographic anchor to a screen pixel, apply the pixel translate, and
place each local quad relative to that. Two of those three are trivial. The first is not — see §2.6.

### 2.5 Collision and priority — **new, and the reason labels are a cycle**

Nothing in RenG has ever compared two drawn things for screen-space overlap. The closest thing is
`BasemapTileSelector`, which intersects tile cells against a ground footprint
(`internal/planning/BasemapTileSelector.kt`) — a different problem in a different space, and it produces a
set rather than an ordering with rejections.

`Tile Budget` is the nearest *policy* analogue: a bound that fails preparation rather than dropping content
(`CONTEXT.md`, **Tile Budget**: "fails preparation before acquisition when exceeded; RenG never drops
required tiles"). **Label collision is the opposite policy — it exists to drop content** — and that is a
genuine break with how every other budget in RenG behaves. It deserves a sentence in the cycle's spec
saying so out loud, because "RenG never drops" is otherwise a house rule.

### 2.6 Projecting an anchor to a pixel — **new, and I could not find it**

Verified without truncation:

```
$ grep -rn "outputPixelSize.width" kmp/src/commonMain | cat
SceneContent.kt:341           resolutionWidthPixels = ...      (uResolution uniform)
SceneContent.kt:781           val width = ...                  (screenOrthographicProjection)
MercatorGroundFootprint.kt:43 val lastColumn = ... - 1         (pixel → ground sweep)
CameraMatrices.kt:66          val aspect = ...                 (projection matrix)
CameraMatrices.kt:106         require(pixelX in 0 until ...)   (physicalPixelGroundRay)
CameraMatrices.kt:109         val width = ...                  (physicalPixelGroundRay)
```

Six sites, none a forward projection. RenG can cast a **pixel to the ground**
(`physicalPixelGroundRay`, `CameraMatrices.kt:101`) and can build a **camera-relative logical position**
(`resolveCameraRelativeMapPosition`, `PlacementResolver.kt:128-144`) and a **model-view-projection matrix**
(`composeMapModelViewProjection`, `SceneContent.kt:661`). What it cannot do is take a `(latitude,
longitude, altitude)` and return `(pixelX, pixelY, w)` — the perspective divide and the viewport transform
exist only inside the GPU. Collision needs that on the CPU, in `Double`, and it needs the `w` too: a label
behind the camera or beyond the horizon must be rejected before it is boxed, and `camera.projectionMatrix`'s
bottom row is `(0, 0, -1, 0)` (`CameraMatrices.kt:81`), so `w = -z_view` and its sign is the test.

This is maybe forty lines of new code in `internal/projection`, and it is the one piece that both collision
and per-glyph positioning depend on. It should be its own task with its own unit tests, because a wrong
viewport transform is invisible in a rendered frame until labels are already in the wrong place.

### 2.7 Frame-to-frame collision state — **see §4**

### 2.8 Resolving a label's icon reference to sprite pixels — **new, and the parsed manifest is thrown away**

RenG fetches, jointly validates and commits the sprite pair, and `spritePairIsJointlyValid` parses every
entry's `x`/`y`/`width`/`height`/`pixelRatio` before returning a **`Boolean`**
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/OperationRegistry.kt:894-900`,
`spriteEntryFitsAtlas` at `:901-917`). The parsed manifest is discarded exactly the way F-2 found
`GltfDocument` discarded by `RenGClassGateRunner`. Retaining it is a small change to a validation-shaped
function that currently has no reason to return anything richer — but it *is* a change, and the function's
current shape is load-bearing for its cache-policy KDoc (`:871-891`).

---

## 3. The GL seam

`GlBinding` has exactly 91 entry points, and the roster test asserts the number literally:

```
$ grep -c "^    fun " kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt
91
```

```kotlin
@Test fun rosterHasExactlyNinetyOneEntryPoints() {
    assertEquals(91, GlEntryPoint.entries.size)
}
```

(`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlEntryPointRosterTest.kt:8-10`.)

### 3.1 The measured cost of one entry point

F-2's uniform-block addition is the exact precedent and it is one commit:

```
$ git show --stat 90088a6
 AndroidGlBinding.kt          16 +
 GlBinding.kt                 27 +
 GlEntryPoint.kt              10 +
 GlStateSnapshot.kt           36 +
 GlTokens.kt                  12 +
 GlConformanceSuite.kt         2 +-
 GlEntryPointRosterTest.kt    22 ++-
 GlStateSnapshotTest.kt       17 +
 RecordingGlBinding.kt        27 +
 IosGlBinding.kt              24 +
 LinuxGlBinding.kt            31 ++-
 MacosGlBinding.kt            24 +
 12 files changed, 242 insertions(+), 6 deletions(-)
```

Four entry points, twelve files, 242 lines — **roughly 60 lines and 12 file touches per entry point**, and
that count *includes* a Restore Set amendment (`GlStateSnapshot.kt`, 36 lines) which not every entry point
needs. `GlEntryPoint`'s own comment records the ordering constraint: new entries are appended after
`FINISH` "so every existing `GlEntryPoint.ordinal` — which `LinuxGlBinding` indexes its resolved
function-pointer table by — stays unchanged" (`GlEntryPoint.kt:98-102`).

### 3.2 Is `uploadModelPrimitive` the right precedent? **No — and the right one is 250 lines below it.**

The brief asks whether F-2's "only dynamic-geometry path" is the right precedent. Read closely, it is not
one:

```kotlin
binding.bufferData(GL_ARRAY_BUFFER, bytes.size, bytes, GL_STATIC_DRAW)
...
binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indexBytes.size, indexBytes, GL_STATIC_DRAW)
```

(`ModelPipeline.kt:492, 501`.) `uploadModelPrimitive` is **static**: it builds a VAO once per primitive,
keyed by `(meshIndex, primitiveIndex)`, and `RenGRenderer` caches the result by `ResourceKey` so "a second
draw of the same prepared frame — or a later frame over the same GLB — reuses what is already on the GPU
rather than uploading it again" (`RenGRenderer.kt:1316-1321`). It is dynamic only in the sense that the
*contents* come from a parsed file rather than a constant. Copying it for text would upload one VAO per
label per frame, which is worse than the sticker path, not better.

**The actual per-frame respecification precedent is the joint palette**, and it is the right one:

```kotlin
// once, at pipeline creation:
binding.bufferData(GL_UNIFORM_BUFFER, MODEL_JOINT_BLOCK_BYTES, null, GL_DYNAMIC_DRAW)   // :405

// per draw, per primitive:
private fun bindJointPalette(pipeline: ModelPipeline, palette: FloatArray?) {
    if (palette == null || pipeline.jointBuffer == 0) return
    val bytes = littleEndianBytes(palette)
    binding.bindBuffer(GL_UNIFORM_BUFFER, pipeline.jointBuffer)
    binding.bufferSubData(GL_UNIFORM_BUFFER, 0, bytes.size, bytes)
    binding.bindBufferBase(GL_UNIFORM_BUFFER, RENG_JOINT_UNIFORM_BINDING_POINT, pipeline.jointBuffer)
}
```

(`ModelPipeline.kt:405, 730-736`.) A fixed-capacity buffer allocated once with a **null payload** and
`GL_DYNAMIC_DRAW`, refilled per frame with `bufferSubData`. That is exactly a batched text VBO's shape, and
it means **the answer to "what does batched text drawing need that is absent" is: for the buffer path,
nothing.**

### 3.3 What is genuinely absent

| Capability | Present? | Citation / consequence |
|---|---|---|
| Buffer orphaning (`glBufferData` with null) | **Yes** | `GlBinding.kt:68` — `data: ByteArray?` |
| Dynamic refill (`glBufferSubData`) | **Yes** | `GlBinding.kt:69`, used at `ModelPipeline.kt:734` |
| `GL_DYNAMIC_DRAW` token | **Yes** | `GlTokens.kt:70` |
| Indexed triangles for quads | **Yes** | `drawElements` `GlBinding.kt:137`; `GL_UNSIGNED_SHORT` `GlTokens.kt:49` |
| Normalized ubyte vertex colour | **Yes** | `vertexAttribPointer(..., normalized, ...)` `GlBinding.kt:75-77`; `GL_UNSIGNED_BYTE` `GlTokens.kt:48` |
| **Instanced drawing** | **No** | no `glDrawArraysInstanced`, `glDrawElementsInstanced`, or `glVertexAttribDivisor` anywhere — `grep -rn "instanced\|Instanced\|vertexAttribDivisor" kmp/src` returns two KDoc prose hits and no code |
| **Buffer mapping** | **No** | no `glMapBufferRange` / `glUnmapBuffer`; and the seam is `ByteArray`-shaped throughout, so a mapped pointer does not fit `GlBinding`'s idiom at all |
| **Integer vertex attributes** | **No** | no `glVertexAttribIPointer`; a packed `uint` colour attribute is unreachable, normalized float is not |
| **Primitive restart** | **No** | no `GL_PRIMITIVE_RESTART_FIXED_INDEX` token; enabling it would be an **ADR 0023 Restore Set amendment**, because it is an enable cap RenG would then be writing |

**Recommendation for the grilling.** Indexed triangles into one orphaned, `bufferSubData`-refilled VBO
needs **zero** new entry points and zero Restore Set movement. Instancing would save one third of the
vertex writes (4 corners instead of 6) at a cost of two entry points ≈ 120 lines across 12 files, plus a
divisor that is VAO state and therefore restored by the existing vertex-array capture. On the evidence, the
non-instanced path should be the plan and instancing should be a measured follow-up, not a premise.

### 3.4 The one real cost of the seam's shape, and it is not an entry point

`bufferSubData` takes a non-null `ByteArray` and no source offset (`GlBinding.kt:69`), and the only
float-to-bytes helper allocates:

```kotlin
internal fun littleEndianBytes(values: FloatArray): ByteArray {
    val bytes = ByteArray(values.size * Float.SIZE_BYTES)
    ...  // four byte stores per float, in a Kotlin loop
}
```

(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/CompositePipeline.kt:67-79`.) There is no
`FloatArray` overload of `bufferData` or `bufferSubData` on any of the five implementations:

```
$ grep -rn "fun bufferSubData\|fun bufferData" kmp/src/commonMain kmp/src/androidMain kmp/src/macosMain
GlBinding.kt:68, 69   AndroidGlBinding.kt:214, 218   MacosGlBinding.kt:340, 348
```

So every frame, a batched text upload pays one full-size heap allocation plus four byte stores per float,
in interpreted-ish Kotlin, on Kotlin/Native. For scale: F-2's joint block is
`MAXIMUM_MODEL_JOINTS * 16 * 4` = 256 × 64 = **16,384 bytes** per skinned primitive per frame
(`ModelPipeline.kt:123, 126`) — and F-2 already measured a per-model per-frame cost of ~3 ms on an M3 Max,
roughly 18% of a 60 Hz budget, for a *different* reason (three GLB re-parses). A text batch is
`quads × 6 vertices × floats-per-vertex × 4` bytes; at 2,000 quads and six floats per vertex that is
**288 KB allocated and 288,000 byte stores per frame**, and nothing in the current seam lets you avoid
either.

**This is worth measuring in the cycle rather than assuming.** The fix, if it is needed, is a
`FloatArray` overload on `bufferSubData` — one entry point's worth of files, no new GL symbol, no roster
change, since it is the *same* `glBufferSubData` with a different Kotlin signature. I could not measure the
real per-frame cost here because I have no label corpus and did not run a build; **what would settle it** is
a microbenchmark of `littleEndianBytes` over a 72,000-element `FloatArray` on `macosArm64`, compared against
the 60 Hz budget.

---

## 4. Text is frame-to-frame stateful, and a `FramePlan` is a complete definition of on-screen state

This is the question most likely to need an ADR, and the tension is sharper than the 2026-08-21 research's
one-sentence version of it. Here is where it actually bites.

### 4.1 What the contract says, exactly

`CLAUDE.md`: *"A `FramePlan` is a complete definition of on-screen state. Callers do not issue incremental
mutations. Frame-to-frame reuse — of decoded images, uploaded textures, parsed GLBs, compiled shaders,
tiles — is entirely RenG's internal concern, invisible in the API."*

`CONTEXT.md`, **Frame Plan**: *"A complete, self-contained definition of one frame's content, drawn by
whichever renderer prepared it."*

`CONTEXT.md`, **Frame History**: *"The renderer's clearable record of its last successfully prepared frame
index, **Frame Plan**, and selected basemap tile LOD."*

The word doing the work in the purity contract is **reuse**. Every cross-frame thing `CLAUDE.md` enumerates
is a cache: it changes *how fast* a frame is produced, never *what it looks like*. Label hysteresis is not
that. Two identical `FramePlan`s in sequence would render differently depending on what was on screen
before — which is the precise thing the sentence exists to forbid.

### 4.2 The precedent is not zero, and it is stronger than it first looks

RenG already carries cross-frame state that **changes pixels**, and it carries exactly two pieces of it:

```kotlin
private var previousEncodedPlan: EncodedFramePlan? = null   // RenGRenderer.kt:478
private var previousSelectedLod: Int? = null                // RenGRenderer.kt:479
```

Both are read into planning (`:521-522`), both are committed only on success (`:641-642`), and both are
cleared by the public `clearFrameHistory()` (`:1044-1045`).

`previousSelectedLod` is the one that matters. It feeds `observeMercatorLod(zoom, previousSelectedLod)`
(`internal/planning/MercatorLod.kt:46-71`), and its KDoc is unusually explicit that the visible consequence
was reasoned about rather than tolerated:

> "The historyless rule is round-to-nearest … Hysteresis exists so a zoom hovering on a boundary does not
> thrash the tile set, but a symmetric band spends half of itself *coarser* than that rule — and coarser is
> the blurry direction. `UPPER_HYSTERESIS_OFFSET` therefore sits exactly on the historyless boundary and
> the whole 0.5-level band is spent below it: **a remembered LOD may only ever be finer than the LOD this
> same zoom would have selected from scratch, never coarser.**" (`MercatorLod.kt:29-36`)

And `CLAUDE.md` records that one of the four defects the visual harness caught, and the passing suite did
not, was "a soft image from LOD hysteresis" — that is, this state's *visible* effect, discovered by looking
at pixels.

**What the precedent establishes, stated as four rules:**

1. Cross-frame state that changes the picture is **permitted**, and has shipped in a public release.
2. It lives in **preparation**, never in drawing. `previousSelectedLod` is read by
   `FramePlanningCore.plan` and committed at the end of `prepare` (`RenGRenderer.kt:641-642`).
3. It is a **pure function's second argument**: `observeMercatorLod(zoom, previous)` is total,
   deterministic, and testable without a renderer. The state itself is inert data.
4. It is **clearable by the consumer** through `clearFrameHistory()`, which `CONTEXT.md` defines as
   permitting "a new sequence" while freeing nothing and invalidating no prepared frame.

Rule 4 is what actually reconciles hysteresis with the purity contract: the consumer has a documented way
to say "forget everything and treat the next plan as the first", and after that call the render *is* a pure
function of the plan.

### 4.3 Where it bites, and this is the part the precedent does **not** cover

**(a) `draw()` is repeatable, and collision at draw time would break that.** `CONTEXT.md`, **Prepared
Frame**: *"It may be drawn repeatedly until its idempotent, context-free `close()` releases its resource
leases."* And **Frame History**: *"Drawing never changes history."* `Renderer.draw` takes a `PreparedFrame`
and a `RenderTarget` and returns `Unit` (`Renderer.kt:87`).

If label collision resolves at draw time against mutable state, then drawing one prepared frame twice
produces two different pictures, and that is a direct contract violation with a test already in the tree
that would catch it — `ModelReadbackSuite`'s case 4 asserts two frames with identical inputs are
**bit-identical**, with no tolerance, on the reasoning that "any difference at all is state leaking between
frames" (`kmp/src/commonTest/kotlin/com/rohittp/reng/ModelReadbackSuite.kt:311-320`). **Collision must
therefore resolve during `prepare()`, and the surviving placement must be frozen into the
`PreparedFrame`.** That is not a preference; it is forced by two documented sentences and one existing
assertion.

**(b) The state is O(labels), not O(1), and it is content-addressed.** `previousSelectedLod` is one `Int`.
Label placement memory is a map from label identity to (placed / suppressed / fade phase / chosen
position), sized by what was on screen. Three consequences worth naming before the design:

- **Unbounded growth.** A pan across a city accumulates identities for every label ever seen unless the
  memory is bounded. RenG's answer for every other unbounded thing is an explicit configured ceiling that
  fails closed (`Tile Budget`, `Preparation Budget`, every `ResourceLimits` field). A placement memory with
  no ceiling would be the first unbounded renderer-lifetime allocation in the codebase.
- **Identity.** ADR 0018 governs canonical identities and RenG derives everything from
  domain-separated canonical bytes. A label's identity is not obviously derivable — two frames at different
  zooms may or may not carry "the same" label. This needs deciding, and getting it wrong makes hysteresis
  either useless (identities never match, so nothing is remembered) or wrong (identities collide, so a
  label inherits another's placement).
- **`clearFrameHistory()` must clear it**, by rule 4 above, and that is the cheap part —
  `RenGRenderer.kt:1041-1047` is where it goes, beside the two existing lines.

**(c) A prepared batch complicates it.** `prepareBatch` prepares up to 256 frames with strictly increasing
indices, and `CONTEXT.md` says "Structural diffing uses the last successfully prepared plan as the first
baseline and each immediately preceding input plan as the next baseline within a batch". Label hysteresis
would have to follow the same rule — each frame's placement memory being the previous *input* frame's
outcome, not the last *committed* one — or a batch would produce different labels than the same frames
prepared one at a time. That is a real correctness requirement with a real test shape, and it is the kind of
thing that is easy to leave until it is expensive.

**(d) Frame identity does not cover it, and that is fine but must be said.** `FramePlanCanonicalEncoder`
hashes the plan and only the plan (`internal/identity/FramePlanCanonicalEncoding.kt:60` encodes
`drawBasemap`, and nothing in that file reads renderer state). So two frames with the same canonical
identity can already render differently today, under LOD hysteresis. Labels widen that, they do not
introduce it — but the cycle should say so explicitly rather than let a reader discover that identity is a
plan digest and not a picture digest.

### 4.4 The options, honestly weighed

**Option 1 — no cross-frame state at all.** Collision resolved from scratch every frame, deterministically,
from `(candidates, camera, output size)` alone. Purity contract untouched; `clearFrameHistory` unaffected;
`draw` stays repeatable for free; every readback assertion of the "identical inputs, identical bytes" shape
keeps working. **Cost:** labels flicker as the camera moves, which is the exact failure the visual harness
exists to catch and which no analytical readback would ever report. This is honest but probably ships a
visibly worse map.

**Option 2 — hysteresis as a fourth member of Frame History.** Follow `previousSelectedLod` exactly:
placement memory read by planning, written on successful `prepare`, cleared by `clearFrameHistory()`,
consumed by a pure function `placeLabels(candidates, camera, previousPlacement) -> Placement`. Extends a
concept `CONTEXT.md` already defines rather than inventing one. **Cost:** the three problems in §4.3(b) —
bounding, identity, batch semantics — all have to be solved, and none is solved today.

**Option 3 — a public opt-in.** A `FramePlan` or `RendererConfiguration` field that turns hysteresis on.
**Cost:** public ABI, and it puts a rendering-quality knob in a plan that is supposed to describe content.
It also does not actually resolve the tension — it just makes the consumer responsible for it. Note that
`CONTEXT.md` explicitly refuses this shape for the scene light: *"It is RenG's own constant, not a
consumer-visible feature."* The same argument applies here.

**Option 4 — fade-in only, no placement hysteresis.** Opacity animates over frames; *which* labels are
placed is recomputed from scratch. Smaller state (one float per identity), and the flicker it fixes is the
appearance/disappearance kind rather than the position kind. Still needs identity and bounding.

**Recommendation for the grilling:** Option 2, framed as an extension of **Frame History** rather than as a
new concept, with the ADR carrying three things the LOD precedent did not have to carry — the bound, the
identity derivation, and the batch rule. And the ADR should say in as many words that collision resolves
during `prepare()` and never during `draw()`, because §4.3(a) makes that non-negotiable and it is exactly
the sort of thing a later implementer would "simplify" by moving into the draw path.

---

## 5. The public API

**Labels can be drawn with no public ABI addition at all.** Enumerated field by field:

### `ResourceClass` — **no addition needed, and adding one would be wrong**

Eleven constants (`Resources.kt:39-51`). The engine's own glyph class is deliberately not among them: ADR
0016's erratum states that `GLYPH_RANGE` "stays deliberately outside this firewall … adding a row for a
class RenG never routes would be exactly the failure the respike was run to rule out"
(`docs/adr/0016-…:78-85`). Whether that erratum's reasoning survives labels is the sibling document's
question — it turns on whether RenG routes glyph bytes itself or the engine does.

What is certain from RenG's side: **the firewall fails closed on any URL it did not preregister**, with no
fallback branch:

```kotlin
suspend fun executeTransport(request: EngineTransportRequest): EngineTransportResponse {
    val route = routeIndex.transportRoutes[TransportIndexKey(request.url, request.resourceClass)]
        ?: throw ambiguousRouteFailure()
```

(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/OperationRegistry.kt:324-326`;
`preregister` at `:244-303`.) If a glyph URL must reach the consumer's `Transport`, it must be
preregistered, and `ResourceClass` is what `preregister` keys on
(`ResourceRouteKey(locator, resourceClass, …)`). So **whether `ResourceClass` grows is decided entirely by
whether the handover lets RenG enumerate the glyph URLs before the call** — a Rentile-surface question,
answered in the sibling document. `HANDOFF.md:586-601` records RenG's own current belief that `0.5.0`
shipped exactly that closure API. If it did, `ResourceClass` gains one entry (`BASEMAP_GLYPH_RANGE`) and
that is a public ABI change; if RenG never routes those bytes, it gains nothing.

### `ResourceKind` — **one entry, or zero**

Seven constants (`Resources.kt:53-61`). The glyph atlas is a GPU texture, and F-2's precedent for this
exact situation is `MODEL_IMAGE`: a kind exists so `queryResources` / `freeResources` can select it, and so
`ResourceReport` can attribute its bytes. Whether labels need one depends on whether a consumer should be
able to free the atlas independently. **If in doubt, no** — `INTERNAL_PIPELINE` already covers RenG-owned
GL objects with no external locator, and `CLAUDE.md`'s own warning stands: "A public field with no wiring
is the failure mode worth remembering here."

### `RenGErrorCode` — **probably one, and it is the same shape as F-2's**

Thirty-one constants (`Exceptions.kt:5-37`). F-2 added `UNSUPPORTED_ANCHORING_MODE` for exactly one
situation: a legal-looking input RenG refuses rather than draws wrong (ADR 0029). Labels have a candidate
of the same shape — a style whose text RenG cannot render — but they also have a *degradation* case, which
is different and is the harder question. See below.

### `PipelineStage` — **the pointed one**

Nineteen constants (`Diagnostics.kt:6-26`). `EngineFailureClassification` buckets Rentile `0.5.0`'s three
label codes into `BASEMAP_RENDER_FAILED` at `BASEMAP_RENDER`, with the reason written into the code:

> "They join the fail-closed bucket rather than getting a RenG code of their own precisely because RenG has
> no label work to attribute them to: inventing one would claim a labelling stage that does not exist. When
> RenG does draw labels, these three move out of here into shapes that name the label plan."
> (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/EngineFailureClassification.kt:132-142`)

**That comment is a standing instruction to E-labels, and honouring it costs a public `PipelineStage`
entry.** It also costs entries in `failureRule(code, stage)`, the `(code, stage)` allowlist that
`RenGException`'s `init` enforces
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/DiagnosticFactories.kt:222-…`, called from
`Exceptions.kt:48` via `requireAllowedFailureContext` at `DiagnosticFactories.kt:99-121`).

### `Diagnostic.fieldName` — **free**

`fieldName` is a public `String?` validated against an **internal** enum (`DiagnosticField`,
`DiagnosticFactories.kt:14-37`, 22 entries; allowlist at `:93-97`). New field names are an internal-only
change and move no ABI. Confirmed: `DiagnosticField` does not appear in `kmp/api/kmp.klib.api`.

### `ResourceLimits` — **one field, and it is expensive**

Eleven `Long`s (`Resources.kt:69-90`). Its ABI shape is an 11-argument constructor plus `copy` plus
`component1..component11` (`kmp/api/kmp.klib.api:629, 654-665`). Adding a twelfth moves all thirteen
signatures — that is exactly the "mechanical constructor/`copy`/`component11` fallout" `CLAUDE.md` records
for `maximumResidentGpuTextureBytes`. Whether labels need one is a real question with a real answer
already sitting in F-2's owed list: `decodedCpuBytes` already shares `maximumDecodedImageBytes` with
rasters instead of taking its own limit, and a glyph atlas would make that sharing worse, not better. **A
label-count or glyph-quad ceiling is a stronger candidate than a byte ceiling**, because it bounds the
thing that actually varies, and it is the shape `maximumBasemapTileInstances` already uses (an `Int` on
`RendererConfiguration`, `Renderer.kt:24`, not on `ResourceLimits`).

### `FramePlan` — **the interesting one, and the answer is probably "no field"**

`FramePlan` has seven constructor parameters (`FramePlan.kt:14-22`). The obvious addition is a
`drawLabels: Boolean = true` beside `drawBasemap`. Its measured cost is small — `drawBasemap` appears in
19 source and test files under `kmp/src` and `consumer-smoke/src`, 67 occurrences total, of which exactly
one is the canonical encoder (`FramePlanCanonicalEncoding.kt:60`).

But it should probably not be added, for the reason `CONTEXT.md` gives about the **Basemap Style**: the
style "is fixed for that renderer and never named by a **Frame Plan**", and labels come from the same
style. A `drawLabels` flag on the plan says labels are per-frame content; a
`RendererConfiguration` field says they are a renderer-lifetime property of the styled basemap, which is
what they actually are. `drawBasemap = false` already suppresses the ground for a frame; whether it should
also suppress that frame's labels is a design question worth one sentence in the spec and no new field.

### The one thing not in this list, and it is the biggest

**20 of 34 verified styles silently lose their map text today, and RenG cannot see the diagnostic that says
so.** `HANDOFF.md:172-186` records the measurement: 20 of the 34 styles carry a `>` operator inside a
`symbol` layer, the pinned engine degrades rather than failing, and it reports that as an INFO diagnostic —
which RenG never reads, deliberately, because `EngineFailureClassification.kt:49-52` records that engine
diagnostics carry free-form `details` that ADR 0016's redaction rule forbids forwarding.

So a consumer of E-labels will run a style, see no text, and have no way to learn why. **Fixing that is a
public API decision** — forwarding a Rentile diagnostic `code` and `severity` without `details` means a new
`DiagnosticCode` entry at minimum, and a decision about whether RenG's diagnostic vocabulary may name an
engine concept at all. `HANDOFF.md:607` assigns it to E-labels in as many words. It is not optional and it
is not small.

---

## 6. The verification story

Both readback suites are in `commonTest` now and are reached from all four context-holding targets:

```
$ grep -rn "runBasemapReadbackSuite\|runModelReadbackSuite" kmp/src | grep -v "internal fun run"
LinuxGlConformanceTest.kt:58, 74      (GLES,    llvmpipe)
MacosGlConformanceTest.kt:53, 91      (DESKTOP, Apple M3 Max / Apple Software Renderer)
IosGlConformanceTest.kt:157, 183      (GLES,    Apple Software Renderer in CI)
AndroidGlConformanceTest.kt:117, 130  (GLES,    device / SwiftShader)
```

### 6.1 What analytical readback can assert about text

The two suites' own KDocs are the best available statement of the method
(`BasemapReadbackSuite.kt:37-93`, `ModelReadbackSuite.kt:23-77`). Mapped onto labels, the assertable claims
are:

- **Something drew at all.** `assertTheModelDrawsAndIsNotBlack` is the lower bound
  (`ModelReadbackSuite.kt:222-237`), and its KDoc records *why* it exists separately: measured, by making
  `drawModels` a no-op, "four cases failed and two passed", because two of the six are upper-bound
  assertions that a blank frame satisfies (`ModelReadbackSuite.kt:63-69`). A label suite owes the same
  lower bound and owes the same measurement.
- **The atlas is sampled and each glyph carries its own texels.** Directly analogous to
  `assertTheTextureIsApplied`, which samples each triangle's projected *centroid* and relies on the fixture's
  base-colour factor being white so that a dropped texture reads back 215/255 away
  (`ModelReadbackSuite.kt:248-264`). For text, the equivalent is sampling the centre of two glyph cells
  whose atlas texels differ — **two, not one, because "a single sample cannot tell a correct texture from
  one whose coordinates are constant"** (`ModelReadbackSuite.kt:243-245`).
- **Coverage is bounded.** `assertCoverageIsBounded` (`ModelReadbackSuite.kt:383-402`) generalises exactly:
  every drawn pixel falls inside a box projected analytically from the label's own quad extents. This
  catches a runaway anchor projection, which is §2.6's risk.
- **Collision actually rejects.** Two labels placed to overlap must produce *fewer* drawn pixels than the
  two drawn independently, and the survivor must be the higher-priority one — checkable by colouring the
  two differently and asserting the loser's colour is absent. This is the one label-specific assertion with
  real discriminating power, and it is analogous to `assertOcclusionIsReal`'s second half, which is
  deliberately built so that "the two outcomes are opposite, which is what makes this a test rather than a
  demonstration" (`ModelReadbackSuite.kt:339-342`).
- **Determinism.** Two frames at identical inputs must be **bit-identical**, exactly as
  `assertAnimationIsBoundToTime` requires (`ModelReadbackSuite.kt:311-320`). Under §4's Option 2 this
  becomes the *hysteresis* test rather than a purity test: two frames after `clearFrameHistory()` must be
  identical, and two frames without it may legitimately differ.
- **Regime and depth.** That labels draw with depth testing off, after the whole map regime — the
  call-log shape `SceneContentTest` already uses, e.g. its depth-mask window assertion at
  `SceneContentTest.kt:378-396`.

### 6.2 What it cannot assert, stated plainly

- **Legibility.** The cycle's own success criterion in `docs/decomposition.md:50` is *"Labels legible and
  collision-free over a moving camera."* Analytical readback can prove collision-free. **It cannot prove
  legible.** Antialiasing quality, halo contrast, the SDF iso-line's sharpness, whether the smoothstep
  width tracks the label scale — every one of those is a "how it looks" question and every one is Cycle J's
  by the deferral at
  `docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`.
- **Correct glyph order and correct text.** Nothing readback can assert distinguishes "HELLO" from "OLLEH"
  unless the fixture's glyph cells are individually distinguishable by colour, which requires authoring an
  atlas where each cell is a flat distinct value — and a flat-value cell has no distance field, so it tests
  positioning while destroying the thing that makes it SDF. **Those two properties cannot be tested by the
  same fixture.**
- **Sub-pixel placement.** `BasemapReadbackSuite` already excludes it by name (`:63-67`), for the reason
  its budget paragraph gives: an exact pixel count is a claim about a driver's fill rule
  (`BasemapReadbackSuite.kt:84-93`).
- **Anything on the two rasterisers that drop primitives.** Hosted macOS runners and the iOS simulator both
  report `Apple Software Renderer` (ADR 0033), and `measureLargeQuadRasterisation`
  (`BasemapReadbackSuite.kt:669`) exists because that rasteriser measurably drops large quads. Glyph quads
  are small, so they should survive where ground tiles do not — but that is a *prediction*, and it is the
  kind of prediction that cost `0.3.0` a failed publication. **What would settle it:** run a probe of the
  label pass's own quad footprints, in the same shape as the existing one, before writing any assertion
  that depends on the count.

### 6.3 The fixture problem, and it is the largest verification risk

**RenG has no vector-tile fixture and no glyph fixture anywhere, and it cannot easily author one.**

`BasemapReadbackSuite`'s fixture style is raster:

```
"sources":{"s":{"type":"raster","tiles":["$STYLE_TILE_TEMPLATE"],"tileSize":512}},
```

(`BasemapReadbackSuite.kt:1116-1118`), served by `ReadbackTransport` (`:1129`) from base64 PNG constants
(`:1168-1171`). `BasemapEngineRenderTest`, the one test that genuinely drives Rentile's rasteriser, also
uses a raster route (`kmp/src/nativeTest/…/BasemapEngineRenderTest.kt:27`, `hostRasterRoute`). Every
`"type":"vector"` string in the test tree is in a **URL-derivation** test — `BasemapTileJsonSourceTest`,
`BasemapRouteDerivationCostTest` — where the tile bytes are never fetched.

A label readback test needs, at minimum: a style with a `symbol` layer, a vector tile carrying a text
feature, and a glyph range. RenG owns a PNG decoder and a JSON reader and **no MVT encoder and no protobuf
encoder**. `ModelFixtureBuilder.kt` is the precedent for authoring binary fixtures by hand — it assembles
GLBs "byte by byte because RenG owns a glTF decoder and no encoder — there is nothing in the tree to write one of these with" (`ModelFixtureBuilder.kt:8-9`) — and it is 400+ lines for
a format RenG already parses. Doing the same for MVT and glyph protobuf means writing encoders for two
formats RenG has never read.

There is a second constraint on top: `BasemapEngineRenderTest` lives in `nativeTest` rather than
`commonTest` because "Rentile rasterizes through Skia, and this project's `androidHostTest` runtime
resolves … fails with `LibraryLoadException` and every rasterizing assertion would be vacuous"
(`BasemapEngineRenderTest.kt:12-17`). Whether Rentile's label path has the same dependency is the sibling
document's question; if it does, a label readback suite **cannot** live in `commonTest` the way Cycle H just
put the other two, and the Android host suite loses it entirely.

**This is the finding most likely to change the cycle's task list**: the fixture may cost more than the
renderer.

### 6.4 Two vacuity traps specific to labels

Following the standard set by F-2's seven and Cycle H's two — every one a fixture or assertion sitting at a
symmetry point:

1. **A collision test with two labels at the same priority.** If both carry the same `sortKey` and the same
   layer priority, the tie-break is whatever the sort is stable on, and the test passes under *either*
   ordering rule. Same class of defect as F-2's "screen-order check at unequal z, which passes under either
   authority". Give the two labels different priorities **and** assert the specific survivor.
2. **A hysteresis test that clears history between the two frames.** Under §4's Option 2, `prepare` commits
   placement memory on success — so a test that calls `clearFrameHistory()` between frames is testing the
   historyless path twice and would pass with the hysteresis code deleted. The discriminating shape is
   three frames: A, B, and A again, asserting that the third differs from the first.

And the general one, from Cycle H's most recent catch: **a guard that fires first masks every rule beneath
it.** If a label suite opens with a rasterisation probe or a "style has no symbol layers, skip" check, a
mutation to the placement code will be masked by the probe. Every skip must be measured against a
deliberately broken build, exactly as `ModelReadbackSuite` measured its six cases by making `drawModels` a
no-op.

---

## 7. What this document did not verify

- **Nothing was built or run.** No Gradle invocation, no test execution. Every claim is source reading plus
  `git`, `grep` and `wc`. The 1,123 / 1,159 / 1,145 test counts named in the brief are taken on trust from
  Cycle H's record, not re-measured here.
- **No Rentile source was read**, by instruction. Every statement about the handover's shape is either
  quoted from RenG's own documents (`HANDOFF.md`, the 2026-08-21 research) with its provenance named, or
  deferred to `docs/research/2026-08-28-e-labels-rentile-surface.md`. In particular, whether
  `ResourceClass` must grow (§5) and whether a label test can live in `commonTest` (§6.3) both turn on
  facts this document does not own.
- **The per-frame vertex-upload cost in §3.4 is arithmetic, not a measurement.** What would settle it: a
  `macosArm64` microbenchmark of `littleEndianBytes` at 72,000 floats against the 16.6 ms frame budget.
- **The claim that glyph-sized quads survive `Apple Software Renderer` is a prediction, not a
  measurement.** What would settle it: extend `measureLargeQuadRasterisation`'s shape to the label pass's
  own footprints and read the number.
- **The 34-style corpus was not re-examined.** The "20 of 34 lose their text" figure in §5 is
  `HANDOFF.md:172-177`'s, which cites
  `docs/research/2026-08-22-rentile-glyph-closure-request.md:139-140`; that document carries its own dated
  erratum saying its surrounding section is now history.
- **`docs/research/2026-08-28-e-labels-rentile-surface.md` was not read or written**; it belongs to the
  concurrent agent.
