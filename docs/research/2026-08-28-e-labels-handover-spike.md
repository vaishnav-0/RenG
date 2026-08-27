# The E-labels handover spike — is Rentile's glyph closure exact, and where can a label test live?

Measured on 2026-08-28 in a scratch worktree branched from `main` at `f997e1a`, on Apple M3 Max, Kotlin
2.3.21, against the **pinned** Rentile — `gradle/libs.versions.toml:7` reads `rentile = "0.5.0"` and that is
what every number below ran against. Nothing was committed and the worktree has been removed; the primary
checkout's only change is this file. (`main` advanced to `4d243ba` mid-spike, when the two sibling
preflight documents landed; nothing this spike measured is affected, since that commit is documentation
only.)

This is the spike `docs/research/2026-08-28-e-labels-rentile-surface.md` §8 nominated as *"what would settle
it, and what I would do first in this cycle … the cheapest de-risking available"*, and it is also the
answer to `docs/research/2026-08-28-e-labels-renderer-gap.md` §6.3, which called the fixture *"the finding
most likely to change the cycle's task list: the fixture may cost more than the renderer."*

## Verdict

**The closure is exact, measured in both directions, and ADR 0016's exact-string firewall survives labels
with a second `registerRoutes` round and nothing else.** `plan.glyphUrls(template)` returned three URLs;
the consumer's `Transport` was then asked for exactly those three, each exactly once, all under
`ResourceClass.BASEMAP_GLYPH_RANGE`. No fourth URL, no URL that was not preregistered, and nothing
preregistered went unfetched. `planLabelCandidates` fetched the label tile and **not one glyph byte**.

**The fixture did not cost more than the renderer. It cost 115 lines and 91 bytes** — and there is a second
route that costs nothing at all, because Rentile's Wire-generated `Tile` and `Glyphs` classes are public in
its published KLIB ABI and RenG's test sources can already reach them with no added dependency. Both routes
were executed, and they agree.

**A label test can gate on `commonTest`, but only three-quarters of one.** The closure, the firewall
refusal and the URL composition all run on `macosArm64`, `iosSimulatorArm64` **and the Android host JVM**.
The atlas does not: `acquireLabelCandidates` packs and PNG-encodes through Skia, and on `testAndroidHostTest`
that dies with the same `LibraryLoadException: Cannot find libskiko-macos-arm64.dylib.sha256` that keeps
`BasemapEngineRenderTest` in `nativeTest`. **The split is not at the call, it is inside it**: every glyph
range is fetched and decoded before Skia is touched, so the Android host can assert everything about
*routing* and nothing about *pixels*.

One prediction in the preflight is wrong, and it matters. An unpreregistered glyph URL does **not** surface
as `AMBIGUOUS_RESOURCE_ROUTE`.

## What actually ran

`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/LabelHandoverSpikeTest.kt`, 452 lines, four
cases, plus `WireRouteProbe.kt`, 56 lines, one case.

| task | LabelHandoverSpikeTest | WireRouteProbeTest |
|---|---|---|
| `:kmp:macosArm64Test` | 4 tests, 0 failures | 1 test, 0 failures |
| `:kmp:iosSimulatorArm64Test` | 4 tests, 0 failures | not run |
| `:kmp:testAndroidHostTest` | 4 tests, **1 failure** — `theAtlasIsWhiteRgbWithTheDistanceFieldInAlpha` | 1 test, 0 failures |
| `:kmp:linuxX64Test` | not run — Linux, and this is a macOS machine | — |
| `:kmp:connectedAndroidDeviceTest` | not run — no device attached | — |

The full macOS suite ran too, at **1,164 tests with 6 failures**, and all six are named in "What the enum
addition costs" below. 1,164 is `CLAUDE.md`'s recorded 1,159 plus this spike's five, which is the
arithmetic working out rather than a coincidence worth trusting on its own.

## The measurement

One `BasemapEngineHost` operation, opened with the label layer's vector-tile route and nothing else:

```
SPIKE fixture bytes: mvt=91 glyph(0-255)=514 glyph(256-511)=275 style=572
SPIKE planLabelCandidates fetched: [https://tiles.example/l/1/0/0.pbf]
SPIKE glyphClosure size=3 ranges=[0, 256, 0]
SPIKE glyphUrls (3): [https://glyphs.example/Spike%20Sans%20Regular/0-255.pbf,
                      https://glyphs.example/Spike%20Sans%20Regular/256-511.pbf,
                      https://glyphs.example/Spike%20Serif%20Regular/0-255.pbf]
SPIKE transport saw 4 urls, 3 of them glyph
SPIKE glyph classes: 3
SPIKE candidates=2 layerStyles=2
SPIKE atlas 70x16 png=154B entries=5
```

and the three assertions that make it a test rather than a demonstration:

| claim | assertion | result |
|---|---|---|
| planning fetches no glyph | `assertEquals(listOf(vectorTileUrl), requestedUrls)` after `planLabelCandidates` | holds |
| nothing unpreregistered was fetched | every glyph URL the transport saw is in `glyphUrls`' result | holds |
| nothing preregistered went unfetched | `assertEquals(urls.toSet(), requestedGlyphUrls.toSet())` | holds |
| and each exactly once | `assertEquals(urls.size, requestedGlyphUrls.size)` | holds |

Rentile's KDoc claim — "not a superset and not an estimate" — is therefore measured, not read. The
`glyphClosure` list (`[0, 256, 0]`) and the URL list are the same three entries in the same order, which is
what §2 of the surface document predicted from the fact that both read one frozen `assembly.requiredRanges`.

### The fixture is deliberately asymmetric, and that is the point

Two symbol layers over two different place-name source layers, with **two different font stacks**, and text
spanning **two 256-codepoint blocks**:

- `place`, `text-font: ["Spike Sans Regular"]`, feature `name` = `AĀ` — U+0041 in block `0-255`, U+0100 in
  block `256-511`
- `town_label`, `text-font: ["Spike Serif Regular"]`, feature `name` = `B` — block `0-255`

Three `(stack, block)` pairs from two features. A closure that returned one range per stack, or one range
per feature, or the right count from the wrong blocks, fails. This is the vacuity discipline `CLAUDE.md`
asks for: a one-glyph, one-stack fixture would have passed under at least three wrong implementations.

The style must also carry `"glyphs"` at its root, or `planLabelCandidates` short-circuits to an empty
closure with a `GLYPH_RANGE_UNAVAILABLE` INFO diagnostic and every assertion above passes vacuously. **That
is the single most dangerous shape a label suite can have**, and it is one missing style key away at all
times. The lower-bound case — `assertEquals(3, urls.size)` against a literal expected set — is what stops
it, and it should survive into the cycle for that reason alone.

### The refusal, and the preflight's one wrong prediction

`docs/research/2026-08-28-e-labels-renderer-gap.md` §5 and the surface document §2 both expect an
unpreregistered glyph URL to raise `AMBIGUOUS_RESOURCE_ROUTE` out of `OperationRegistry.executeTransport`.
Skip the second `registerRoutes` round and what actually happens is:

```
SPIKE raw engine failure: BatchRenderException: Multiple resource acquisitions failed
SPIKE   caused by: ResourceStoreException: Raw glyph cache read failed
SPIKE refusal code=BASEMAP_RENDER_FAILED stage=BASEMAP_RENDER
```

Two things are wrong with the prediction, and only one of them is cosmetic.

**The gate that fires is the store index, not the transport index.** Rentile's `GlyphResourceAcquirer`
reads its raw store before it touches transport, so the first firewall lookup an unrouted glyph URL meets
is `StoreIndexKey(sha256Hex(redacted url), GLYPH_RANGE)`. The refusal is real and complete — the assertion
that no glyph URL ever reached the consumer's `Transport` holds — but a cycle that only preregisters the
transport half, or that tests only the transport half, would be testing the second gate to fire.

**The failure is opaque.** `ResourceStoreException` and `BatchRenderException` carry no resource class, so
`EngineFailureClassification` has nothing to name and produces `BASEMAP_RENDER_FAILED` at `BASEMAP_RENDER`.
A consumer whose style resolves a glyph URL RenG failed to derive gets a labelless map and a failure code
that says only "the basemap did not render". Whatever E-labels does about
`EngineFailureClassification.kt:132-142`'s standing instruction, this is the case it has to name.

### Two Rentile exceptions escape RenG unwrapped

`glyphUrls` is a method on the plan object, not a call through `BasemapEngineHost.engineCall`, so nothing
sanitizes what it throws:

```
SPIKE mismatched template -> GlyphTemplateMismatchException
SPIKE after close(): glyphUrls -> LabelCandidatePlanClosedException, glyphClosure size=3
```

Both are Rentile types crossing RenG's public surface uncaught. `EngineFailureClassification` already knows
both names — it sweeps them into `BASEMAP_RENDER_FAILED` — but only for exceptions that pass through a
classifying call, and this one does not. Either the plan never leaves the firewall package, or reading its
URLs goes through `engineCall` like everything else.

The second line also confirms the ordering constraint: `glyphUrls` is the one member `close()` kills, and
`glyphClosure` survives it. RenG must read the URLs before closing the plan.

## What the handover actually contains

From the fixture above, printed rather than reasoned about:

```
SPIKE candidates=2 layerStyles=2
SPIKE atlas 70x16 png=154B entries=5
SPIKE candidate layerStyleIndex=0 lon=-90.0 lat=66.51326044311186 quads=2
SPIKE candidate layerStyleIndex=1 lon=-90.0 lat=66.51326044311186 quads=1
SPIKE atlas entry cp=65  cell=(0,0,14x16)  left=1 top=-12 advance=12
SPIKE atlas entry cp=66  cell=(14,0,14x16) left=1 top=-12 advance=12
SPIKE atlas entry cp=256 cell=(28,0,14x16) left=1 top=-12 advance=12
SPIKE atlas entry cp=65  cell=(42,0,14x16) left=1 top=-12 advance=12
```

Four facts worth carrying into the design, none of which is obvious from the source:

1. **The candidate has no layer id.** It carries `layerStyleIndex`, an index into the batch's own
   `layerStyles` list. RenG's collision and priority work reads the layer through that indirection.
2. **Atlas entries are keyed by `(fontStackDigest, codepoint)`, not by codepoint.** Three distinct
   codepoints across two font stacks produced **five** entries: `65` appears twice, at cell x=0 and cell
   x=42. A quad's `entryIndex` is the only thing that disambiguates them, and an atlas lookup by codepoint
   would silently draw the wrong stack's glyph.
3. **The cell is the *buffered* extent.** A glyph declared 8×10 occupies a 14×16 cell — the 3-pixel SDF
   buffer on every side, which `GlyphRangeDecoder.BUFFER_PX` fixes. UV derivation that uses the declared
   width would be 6 pixels short in each axis.
4. **The anchor is geographic and nothing else.** `lon=-90.0, lat=66.513…` is the centre of tile
   `z1/x0/y0`, which is where the fixture put the point. Nothing is projected. §2.6 of the renderer-gap
   document is right that RenG owes a forward projection, and it owes it in `Double`, on the CPU.

### The atlas convention is confirmed, by decoding it with RenG's own decoder

```
SPIKE decoded atlas 70x16
SPIKE nonWhiteRgbPixels=0 distinctAlphaValues=[64 … 191]
```

`decodePng(batch.atlas.pngBytes, …)` returns a 70×16 RGBA8 image in which **every** pixel's RGB is
`(255, 255, 255)` and the alpha channel carries the payload — the fixture's own 64…191 ramp, bit-exact, so
the packer neither premultiplied nor resampled it. That settles the upload question the renderer-gap
document raised at §2.1: the atlas wants `TextureContent.DATA` for the bytes and `GL_LINEAR` for the
sampler, and `uploadTexture`'s explicit `sampler` parameter already permits exactly that combination.

Note the vacuity trap the same section names is real here and this test does **not** escape it: with RGB
forced to white, `premultiplyAlpha` is the identity wherever alpha is 255 and a *lossy* copy elsewhere, so
`nonWhiteRgbPixels == 0` would still pass if the atlas were uploaded as `IMAGE`. The assertion above is
about Rentile's output, not about RenG's upload. The upload assertion has to read `glTexParameteri` from
the call log.

## What the fixture actually cost

**115 lines**, from the `// ---- hand-encoded protobuf` banner to the end of the file, producing a 91-byte
vector tile and two glyph ranges of 514 and 275 bytes. That is the whole bill. The renderer-gap document's
fear — "writing encoders for two formats RenG has never read" — overestimates it, because neither fixture
needs an *encoder*: it needs the six protobuf constructs these two schemas use.

The core is 40 lines and has no format knowledge in it at all:

```kotlin
private class ProtoBuffer {
    val out = mutableListOf<Byte>()
    fun varint(value: Long): ProtoBuffer { /* 7 bits per byte, LSB first, MSB = continue */ }
    fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())
    fun varintField(field: Int, value: Long) = tag(field, 0).varint(value)
    fun bytesField(field: Int, value: ByteArray) { tag(field, 2).varint(value.size.toLong()); … }
    fun stringField(field: Int, value: String) = bytesField(field, value.encodeToByteArray())
    fun messageField(field: Int, build: ProtoBuffer.() -> Unit) = bytesField(field, ProtoBuffer().apply(build).bytes())
    fun packedVarints(field: Int, values: List<Long>) = bytesField(field, /* one run of varints */)
}
private fun zigZag(value: Int): Long = ((value shl 1) xor (value shr 31)).toLong()
```

On top of that, one MVT layer is fifteen lines:

```kotlin
private fun ProtoBuffer.mvtLayer(name: String, text: String) {
    varintField(15, 2L)                       // version
    stringField(1, name)                      // name
    messageField(2) {                         // features
        varintField(1, 1L)                    //   id
        packedVarints(2, listOf(0L, 0L))      //   tags: keys[0] -> values[0]
        varintField(3, 1L)                    //   type = POINT
        packedVarints(4, listOf(command(1, 1), zigZag(2048), zigZag(2048)))
    }
    stringField(3, "name")                    // keys[0]
    messageField(4) { stringField(1, text) }  // values[0].string_value
    varintField(5, 4096L)                     // extent
}
```

and one glyph range is about the same. `command(id, count) = (count shl 3) or id`; `9` is `MoveTo` with a
count of one. The anchor at `(2048, 2048)` of a 4096 extent lands mid-tile, which is what
`LabelCandidateAssembler`'s window test requires — a point outside `[0, extent)` is attributed to a
different tile and silently produces no candidate, which is the second-most-likely way to write a vacuous
label fixture.

### And there is a route that costs nothing, which nobody had noticed

Rentile generates both schemas with Square Wire from `kmp/src/commonMain/proto/{vector_tile,glyphs}.proto`.
Wire emits ordinary **public** Kotlin classes, and `explicitApi` does not reach generated sources, so
`Tile`, `Tile.Layer`, `Tile.Feature`, `Tile.Value`, `Glyphs`, `Glyphs.Fontstack` and `Glyph` — and their
`ADAPTER`s — are all in Rentile's published `kmp/api/kmp.klib.api`, at lines 420–460 and 587 of that file
at `87ccba2`. Their *packages* say `internal`; their *visibility* does not.

`WireRouteProbe.kt` measures whether that is reachable from here, and it is:

```kotlin
import com.rohittp.rentile.internal.mvt.Tile
import com.rohittp.rentile.internal.glyph.Glyphs

val decoded = Tile.ADAPTER.decode(LABEL_MVT_BYTES)
```

compiles and runs on `macosArm64Test` **and** `testAndroidHostTest` with **no change to
`kmp/build.gradle.kts` and no added dependency** — Rentile's `implementation("com.squareup.wire:wire-runtime")`
is transitively visible to RenG's test compilation on both. So route 1 of the brief's three is not "reuse
Rentile's fixtures", it is better: **reuse Rentile's own generated encoders**, and author a vector tile with
a constructor call.

The probe also cross-validates the hand-written bytes, which is the only independent check available that
the hand encoder is right: `Tile.ADAPTER.decode(LABEL_MVT_BYTES)` yields exactly two layers named `place`
and `town_label`, version 2, extent 4096, `keys = ["name"]`, `values[0].string_value = "AĀ"`,
`type = POINT`, `geometry = [9, 4096, 4096]`, `tags = [0, 0]`; and re-encoding through Wire gives 91 bytes,
the same length the hand encoder produced.

**The three routes, priced.**

| route | cost | what it buys | what it costs |
|---|---|---|---|
| 1 — Rentile's Wire types | 0 lines, 0 dependencies, **measured working** | correct by construction; readable fixtures; both schemas | RenG's tests would depend on three `com.rohittp.rentile.internal.*` types that are public only by accident of codegen. Rentile could make them `internal` in any release and RenG's suite stops compiling — with no ABI-check warning, because they are Rentile's ABI, not RenG's. |
| 2 — hand-encoded | 115 lines, **measured working** | no coupling to anything; the `ModelFixtureBuilder` precedent exactly | a reviewer must read protobuf wire format to review it; a wrong varint is a decode failure a long way from its cause |
| 3 — real bytes from a live style | unmeasured | realism | the style URL carries the owner's key, the tile bytes are somebody's copyrighted extract, and a checked-in blob is unreviewable |

**Recommendation, offered for argument, not as a decision.** Route 2 for what ships and route 1 as a
review-time cross-check, which is exactly the shape run here. The two together cost 171 lines and the
second one caught nothing — but it would have, and a fixture no one can independently verify is how the
seven vacuous checks of F-2 happened.

## Which targets can run it, and where the line falls

The renderer-gap document asked whether a label suite could live in `commonTest` the way Cycle H arranged
for the other two readback suites. The answer is **partly**, and the line is not where either document
guessed.

`acquireLabelCandidates` ends in `GlyphAtlasPacker`, which calls `org.jetbrains.skia.Image.makeRaster` and
`encodeToData(EncodedImageFormat.PNG)` (`GlyphAtlasPacker.kt:229, :235` at `e423a78`). On the Android host
JVM that is fatal, and the sanitized failure hides why, so the spike temporarily printed the raw throwable
inside `BasemapEngineHost.engineCall`:

```
SPIKE raw engine failure: NoClassDefFoundError: Could not initialize class org.jetbrains.skia.Image
SPIKE   caused by: ExceptionInInitializerError: Exception org.jetbrains.skiko.LibraryLoadException:
        Cannot find libskiko-macos-arm64.dylib.sha256, proper native dependency missing.
        [in thread "DefaultDispatcher-worker-4 @coroutine#13"]
```

That is the same host-Skia gap `BasemapEngineRenderTest`'s KDoc documents, reaching a second call.

**But the failure lands after every fetch.** On the Android host, the same run printed
`SPIKE transport saw 4 urls, 3 of them glyph` before the acquisition failed, so the closure assertions all
executed and all passed there. Restructuring the case to capture the acquisition result rather than let it
throw is what turns one Android-host failure into three Android-host passes:

```kotlin
val acquired = runCatching { host.acquireLabelCandidates(plan) }
val requested = transport.requestedUrls()
assertEquals(urls.toSet(), requested.filter { it != vectorTileUrl }.toSet())
```

so the cycle's suite splits cleanly:

| what a label test asserts | `commonTest`, all four executable targets? |
|---|---|
| the glyph closure, in both directions | **yes** — measured on macOS, iOS simulator, Android host |
| the firewall refuses an unpreregistered glyph URL | **yes** — measured on all three |
| `glyphUrls` composition, template mismatch, `close()` semantics | **yes** — measured on all three |
| candidate geometry, layer styles, atlas dimensions, atlas pixels | **no** — Skia. `nativeTest`, beside `BasemapEngineRenderTest` |

That is a better outcome than "the label suite goes to `nativeTest`", and it is worth arranging
deliberately: the routing half is the half ADR 0016 is about, and it is the half Android consumers most
need gated.

## What the enum addition costs

The spike added `ResourceClass.BASEMAP_GLYPH_RANGE` because the firewall's transport and store indices are
both keyed by the Rentile class RenG's own class maps to, and without the constant there is nothing to
preregister. The surface document §4 predicted seven edit sites. It is **eight lines across six files**,
and one of the seven predicted sites was not needed:

```
Resources.kt                              1 +   the constant
internal/ValueSupport.kt                  3 +   maximumBytesFor, acceptValue, reportOrder
internal/identity/ResourceKeyDerivation.kt 1 +  wireValue -> 12
internal/resource/ResourceOperationProtocol.kt 1 +  ordinaryResourceClassGates -> null
internal/planning/FramePlanningCore.kt    1 +   isStaticDirect -> false
internal/firewall/RentileKeyDerivation.kt 1 +   -> RentileResourceClass.GLYPH_RANGE
```

`reportOrder` in `ValueSupport.kt` is the site §4 missed. **No new `ResourceLimits` field was needed** —
the route's ceiling was set to the existing `maximumBasemapMetadataBytes` (4 MiB), comfortably above
Rentile's own 1 MiB `maxGlyphRangeBytes`, and everything worked. Whether the cycle wants a
`maximumBasemapGlyphRangeBytes` of its own is a design question, not a mechanical requirement, and
`CLAUDE.md`'s warning about a public field with no wiring applies.

`:kmp:checkKotlinAbi` fails with exactly one line of movement:

```
137a138
>     enum entry BASEMAP_GLYPH_RANGE // com.rohittp.reng/ResourceClass.BASEMAP_GLYPH_RANGE|null[0]
```

and exactly **six existing tests** fail, each of which is an assumption written down on purpose:

| test | what it asserted |
|---|---|
| `ResourcesTest.resourceEnumsContainEveryClosedMemberInContractOrder` | the eleven-constant contract order |
| `BasemapEngineHostTest.partitionsTheConsumerStoreIntoTwoDisjointKeyNamespaces` | "Rentile 0.5.0 fetches and keys exactly seven basemap classes. Expected <7>, actual <8>." |
| `ResourceOperationOrdinaryCommitTest.ordinaryClassGatesMatchProductionForEveryClassAndNameNoEngineKeyedOne` | the ungated set |
| `RentileKeyDerivationTest.reproducesTheEngineDerivationForTheSevenClassesItKeys` | engine-keyed ∪ own-identity exhausts `ResourceClass` |
| `EngineFailureClassificationTest.mapsEveryEngineResourceClassOntoARenGResourceClass` | "GLYPH_RANGE is the one engine class RenG does not route" |
| `EngineFailureClassificationTest.failsClosedOnAnEngineResourceClassRenGDoesNotRoute` | expected `BASEMAP_RENDER_FAILED`, got `RESOURCE_UNAVAILABLE` |

That last row is the interesting one and it is a *gain*, not a chore: once RenG routes `GLYPH_RANGE`,
`rengResourceClassOf` inverts it for free and an engine glyph acquisition failure stops being opaque and
starts naming the resource. Six failing tests is the honest price of a decision the codebase wrote down
six times, and every one of them is a two-line edit that also records the new decision.

## What this spike did not measure

- **It ran against `0.5.0` only.** The closure API is byte-identical at `0.6.0` per the surface document's
  diff, but nothing here executed against `0.6.0`, and `0.6.0` widens the label layer set enormously — the
  same fixture would admit different layers there. The sibling agent's bump spike owns that.
- **RenG derived none of these routes.** Every route was preregistered by hand from a literal. Whether
  `BasemapStyleManifest` — which does not read `glyphs` at all today (`grep -n glyphs` over that file
  returns nothing) — can derive the glyph template, and what happens when a label layer names a source
  RenG deferred as underivable, are both untouched. Those are the surface document's §4 open questions 2
  and 3 and they remain open.
- **No `linuxX64Test` run.** This is a macOS machine. Ubuntu's llvmpipe job is the one that would say
  whether the Skia split behaves the same on Kotlin/Native Linux — it should, since Kotlin/Native links
  Skia in, but that is an inference.
- **No `connectedAndroidDeviceTest` run.** No device was attached. Rentile's AAR ships
  `libskiko-android-arm64.so`, so the atlas half is *expected* to run there and the host-JVM failure is
  about the host JVM, not about Android — but that is exactly the claim `CLAUDE.md` warns against making
  without hardware.
- **The credential half of `glyphUrls`' symmetry is untested.** The mismatch path is measured
  (`GlyphTemplateMismatchException`); the dangerous path is not, because this fixture's style carries no
  credential. The surface document's warning stands unverified: a caller passing an already-redacted or
  stale template "gets back a plausible, non-empty list whose every URL is wrong for that reason alone",
  which under this firewall is a silent total label outage. **What would settle it:** the same spike with
  `?key=…` in the style's `glyphs` template and a deliberately stale copy passed to `glyphUrls`.
- **Nothing was drawn.** No GL context was created, no quad was rasterised, and the atlas was decoded but
  never uploaded. Every rendering question in `docs/research/2026-08-28-e-labels-renderer-gap.md` §1–§4 is
  exactly as open as it was.
- **`plan.diagnostics` was never read**, so the INFO diagnostics a real style would emit — and which RenG
  deliberately does not forward — went unexamined.
- **One candidate per layer is not a load test.** Two candidates, five atlas entries and a 70×16 atlas say
  nothing about the 159-range, 8192×4357, 136 MiB atlas Rentile's own migration document records for
  Outdoor at Tokyo z14. The batch ceiling, the residency cost and the upload cost are all unmeasured.
- **The spike's own edits were reverted with the worktree.** The 8-line enum addition, the 22-line
  `BasemapEngineHost` accessor pair and the 508 lines of test are gone; this document is the record. The
  `println` inside `engineCall` that produced the raw-throwable traces was spike-only debugging and must
  never ship — it prints exactly what ADR 0016's redaction rule exists to suppress.
