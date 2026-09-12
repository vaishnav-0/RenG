# Travel Animator's performance findings, applied to RenG

**2026-09-12.** What this branch takes from a sibling consumer of Rentile, what it deliberately does
not take, and what is blocked on a decision this branch does not make.

## Why a sibling's findings are worth reading

Travel Animator is a map-animation app that **does not use RenG**. It rasterises its basemap through
Rentile directly (its ADR 0010, "rentile owns basemap rasterization") and draws everything else in
its own OpenGL ES renderer. So it is not a consumer whose complaints RenG must answer — it is the
other library-scale renderer in the building, solving the same problems against the same tile engine,
and it has spent a year measuring them on real phones.

Between its tags `4.0.1` (2025-09-24) and `5.0.2` (2026-09-10) — 3235 commits — it ran a structured
performance programme with acceptance runs on three named devices. Its findings were read out of
twelve performance ADRs, five findings documents and the 248-file shared renderer at the commit its
`5.0.2` pins (`1ab68da9`). Every number quoted below is theirs, measured on their stack; **none of
them is a RenG measurement**, and the difference is stated wherever one is quoted.

The connective tissue is Rentile. TA drove a year of fixes down into it and pins **0.11.3**. RenG's
`main` pinned **0.7.0**.

## What landed here

### The Rentile bump, 0.7.0 → 0.11.3

Four minors, and **no source change** — all 31 Rentile symbols RenG imports are present and unchanged
in shape at 0.11.3. The full suite passes, including the real-GL readback suites on
`macosArm64Test` and `iosSimulatorArm64Test`.

What the bump makes reachable, in TA's order of discovery: `renderRaw` (0.8.2), a header-only
`RawResourceStore.metadata` (0.10.0), Rentile-owned style revalidation (0.10.1), `RenderPriority`
on the render calls (0.11.0/0.11.1), a sprite-atlas per-draw decode fix (0.11.2), and further
per-draw decode work plus tile retirement and a per-origin budget (0.11.3).

**A trap worth recording:** the version lives in five coupled places, and **all four** accepted
catalog fingerprints move, not just the one on disk. Every accepted form is a fixture shape derived
from `VERSION_CATALOG` in `tools/tests/test_check_repository_policy.py`, so they are recomputed by
fingerprinting the four fixture forms, never by editing one hash. The in-repo comment said "two older
forms" against four hashes; it now says three and explains the recompute.

### Ground tiles render to pixels, not PNG (ADR 0044)

Rentile encoded every tile and RenG decoded it again on the next call that touched it. `renderRaw`
skips both halves — same drawing, same `contentKey`, so tile identity and the resident texture cache
are untouched.

TA's measurement, on an OPPO CPH2629 over a 1817-frame, 1022-tile export, two runs per arm with the
tile cache cleared before each: total export **260.5 s → 203.6 s (−21.8 %)**, and the encode column
**155 s → 0.00**. The two candidate runs differ by 1.1 s against a metric whose usual spread is about
±30 s. Their preview improved alongside — frames presented 892 → 905, worst gap 8667 ms → 4332 ms.

**RenG's own figure is unmeasured and this branch does not borrow theirs.** What transfers is the
mechanism and its sign: RenG performed an encode and a decode per tile per prepare and now performs
neither, while the budget holds.

Two catches shaped the implementation, and both are in ADR 0044:

- **The pixels arrive premultiplied.** `DecodedImage` is contractually *unpremultiplied* and
  `uploadTexture(IMAGE)` premultiplies. Double-premultiplying darkens partially transparent texels
  and leaves opaque ones alone, so a basemap with an opaque background looks *almost* right. Raw
  tiles therefore never travel in `DecodedImage`; they upload through a second entry point sharing
  every GL statement except the premultiply.
- **A prepared frame retains its tile bytes until it is drawn**, because `prepare()` cannot upload.
  Raw is roughly ten times encoded, and `prepareBatch` admits up to `maximumPreparationBatchSize`
  frames open at once. So the saving is taken only while outstanding raw bytes stay under
  `ResourceLimits.maximumInFlightRawBasemapTileBytes` (64 MiB by default, sixty-four tiles at 512 px),
  and PNGs are asked for above it — the pre-0044 path, reached deliberately.

  This bound is not a precaution. TA tried it without one: **1.6 GB before dying**; then budgeted
  against the newly planned set and hit **578 tiles / 318 MB against a 256 MiB budget and an
  `OutOfMemoryError` at frame 481 of 1817**; then budgeted against measured held bytes and
  **deadlocked** at the first window boundary. Their durable sentence: *a byte budget can only shrink
  the window; it cannot shrink what is already held.*

**One observable behaviour changed.** `maximumDecodedImageBytes` below one tile used to fail every
basemap frame with `RESOURCE_DECODE_FAILED` at `DRAW`; on the raw path nothing is decoded, so it now
renders. That is the limit meaning what it says, but it is a real difference.
`RendererGroundTextureBudgetTest` keeps pinning the classification by holding the raw budget at one
byte so the frame takes the encoded path.

### A consumer uniform's location is asked for once per pipeline

`drawGeometry` asked the driver for every consumer uniform *and* sampler location on every draw. A
location depends on nothing but the linked program and the name, and a `GeometryPipeline` owns its
program until `deleteGeometryPipeline` takes both, so the answer cannot change. It is now resolved on
the first draw that names it and memoised. A consumer program declaring forty-six uniforms — a
Geoanimator region program does — saves forty-six driver string lookups per instance per frame.

Negative locations are memoised too: that is the "do not bind" answer for a name the program never
declared, and it is as permanent as a positive one. A memo storing only successes would re-ask
forever for exactly the names that never resolve, which has its own test.

TA's equivalent lookup measured **~6 µs each, roughly 20k times a second** at a ~95 % hit rate, and
their note on it is the general lesson: "almost none of its cost was measurement: it was a key
allocation, a string hash and an LRU remove-and-reinsert."

### A geometry's grid packs into reused scratch

Each draw allocated two fresh `ByteArray`s sized to that draw's grid — eighty bytes on Mercator,
about a megabyte per geometry per frame on the globe and drape paths, discarded immediately. Both now
pack into scratch the pipeline keeps and grows by doubling.

This is safe only because `bufferData` takes an **explicit byte count** that every platform actual
forwards to `glBufferData` while pinning from index zero, so a scratch longer than the draw uploads
exactly the prefix just packed. A test draws large then small and asserts both the count and the
bytes beneath it; passing the array's length instead fails it.

**Not done, deliberately:** splitting that call into `bufferData(null)` + `bufferSubData`.
`LabelPipeline`'s KDoc records that the pair exists for *orphaning*, not to avoid reallocation, and a
single `bufferData` with data already orphans. The split would add a call for nothing.

### The per-draw map copies are gone

`drawGeometry` took a defensive `.toMap()` of both consumer maps per call, guarding against a
structural mutation no production caller can perform: `PreparedGeometry.uniformsSnapshot` is already a
prepare-time copy, and the texture map is built fresh per frame. The obligation now sits with the
caller, where it is documented.

## What was not taken, and why

These are the ones worth reading before anyone proposes them again.

| Candidate | Why not |
|---|---|
| **Header-only `Store.metadata` probe** | Two independent blockers. `RawResourceMetadata` carries no content digest, and RenG's firewall digest-validates every store hit (`OperationRegistry.readStore` → `copyValidStoredResource`), so a metadata-only answer cannot be validated without bypassing ADR 0016. And `metadata()`'s only caller inside Rentile is `RawResourceWarm`, reached solely from `warmRawResources` — a read-ahead path RenG never enters. It becomes worth adding when RenG gains one. |
| **`RenderPriority`** | RenG prepares one frame at a time, so every tile is urgent. Rentile's own KDoc: a caller that marks everything `URGENT` "has one FIFO queue again". It earns its keep beside read-ahead or provisional tiles, not before. |
| **Reserved scratch texture unit for uploads** | The hazard is latent, not live: every drawing pipeline pairs `activeTexture` with `bindTexture` 1:1. And TA binds on the *highest* unit the driver offers, while RenG restores only `FRAME_TEXTURE_UNIT_COUNT` (15) units in `withCapturedGlState` — so adopting it as written would clobber a host binding RenG never restores, breaking ADR 0006/0023. If ever adopted, the scratch unit must sit inside the captured range. |
| **Unbind before delete, `glFinish` at teardown** | No live case. `deleteGeometryPipeline` is called only from `close()`, immediately before the context goes away, and GL unbinds a deleted texture from every binding point by specification, so `evictOverBudget` needs no help. TA's case was repeated replay teardowns inside one long-lived context, which RenG has no equivalent of. |
| **`texSubImage2D` into pre-allocated storage** | RenG content-addresses its textures, so changed content is a different `ResourceKey` and a new texture object. There is no same-size re-upload path for this to accelerate, and `GlBinding` would need a new member plus four actuals. |
| **Capability = extension string *and* entry point** | TA's rule guards extension functions resolved via `eglGetProcAddress`. RenG binds none; its only capability probe gates core `GL_FRAMEBUFFER_SRGB`. |
| **Shadowed GL state** | Applicable and valuable, but a cycle rather than a commit — it touches every pipeline. **Precondition:** `GlFrameDrawer` wraps every frame in `withCapturedGlState`, so a shadow must be seeded from that capture and invalidated by the restore, or the first write after a restore is skipped as redundant. TA documents the mirror image of this trap. |
| **Thread-affinity assertion** | Needs a new `expect` for thread identity plus actuals on six targets. Diagnostic rather than performance; ADR 0015 already states the rule in prose. |
| **Wire `MetricsSink`** | Not a wiring change but a public API decision. `RentileMetric` and RenG's `Diagnostic` are different vocabularies, and `check_repository_policy.py` forbids any `com.rohittp.rentile` type in the ABI dump — so RenG must define its own metric vocabulary and translate. |
| **`prepareBatch` sharing** | Viable and unblocked, but substantial: `HANDOFF.md` records that it "opens one registry per plan with no latch sharing", so sharing changes preparation lifetime and identity. |

### The two largest wins are deferred, not dismissed

Both are cycles in their own right, and both are recorded here so the size is a decision rather than
a discovery.

**Provisional tiles and a drawability rule.** TA's single biggest result: present a frame when it is
*drawable* — every raster slot holding either the exact tile or an already-resident ancestor within
two zoom levels, drawn magnified through a UV sub-rectangle and replaced in place when the exact tile
lands. Measured across three devices: cold first frame **11.2 / 11.7 / 11.4 s → 3.8 / 3.9 / 4.6 s**,
cold frames presented **1048 / 1004 / 962 → 1348 / 1399 / 1308** against a full rate of about 1440,
export unchanged.

The obvious objection is that RenG's firewall passes `TileSubstitutionPolicy.Disabled` at every call,
on the stated rule that RenG "performs no repeated consumer exchanges, retries, repairs, or
fallbacks". **That objection does not actually apply**: TA's ADR 0022 is explicit that a provisional
tile is *not* an ADR 0010 substitution — it is never persisted, never budgeted, and involves no extra
exchange, because it is the consumer redrawing a texture it already holds at a different UV rect. So
it could live in RenG's own ground pipeline with Rentile substitution still disabled. What it does
change is **what a frame is allowed to present**, which is a charter question and belongs in its own
ADR, not in a performance PR.

**Splitting origins by buffer lifetime, and memoising geometry grids.** `geometryGrid` runs inside
`SceneContent.draw` and produces **camera-relative** vertices, so no grid can be cached across frames:
the origin moves every frame. TA hit the same coupling and escaped it by splitting the rule by buffer
lifetime — the camera ground point for per-frame content, a **tile-local origin baked at upload** for
persistent buffers, "so they never need re-uploading when the camera moves". That is what makes their
mesh memo legal, and it is worth **843–1083 ms against a 5–10 ms upload** on a projection switch.
They also name the trap: a camera-following origin "would invalidate that memo every frame and hand
back the whole saving".

For RenG this changes the vertex contract for every persistent buffer, so it is a cycle with an ADR,
not a commit. Note the scoping: RenG's Mercator path passes `groundCurvatureRadiusLogicalPixels =
POSITIVE_INFINITY`, so a Mercator geometry is a 1x1 grid of four vertices and gains nothing — the
memo pays on the globe and drape paths, where a grid reaches 128 cells a side.

### One item is blocked on a commit this branch does not carry

**A CPU byte budget and LRU on `ResidentCache` would be inert today.** The cache's rule is that a
retired generation with no outstanding lease is dropped immediately and *there is no automatic
eviction of a leased one*. `ResourceActionExecutor` takes a lease per prepared resource through
`installAndTakeLease`, and nothing on `main` releases it — `closePreparedFrame` still only calls
`markClosed()`. So everything is permanently leased and an eviction policy would have nothing it is
allowed to reclaim.

Releasing a prepared frame's leases when it closes is the fork's `a1e91d6`, deliberately out of scope
for this branch. **It is a prerequisite for the memory work, not an alternative to it**, and the
eviction counters that would make a cache's behaviour legible depend on the same thing.

### What the negative results save

TA ran experiments RenG does not need to repeat:

- **Whole-set tile read-ahead is catastrophic.** Warming the complete session tile set gave 42/49/69
  frames against 296/305/357, and jank of 50/40.8/33.3 % against 3.4/2.6/2.2 %, on a OnePlus CPH2649.
  The cause is ordering, not volume: "it spent the contended connection budget on tiles for frame 900
  while frame 70 was on screen." Any read-ahead RenG gains must be ordered against the cursor.
- **Resident raw RGBA under a byte budget does not work** — the three failures quoted above.
- **Worker-summed counters are not wall clock.** Their `encode_ms` sums across concurrent workers;
  reading 147 s of encode CPU as 147 s of export time overstated a prize by the worker count.

## Environment notes for anyone reproducing this

- `tools/check_repository_policy.py` needs Python ≥ 3.11 for `tomllib`. macOS ships 3.9.
- Apple targets need `DEVELOPER_DIR` pointed at a full Xcode; `xcode-select -p` returning
  CommandLineTools makes Kotlin/Native's `xcrun` probe fail at `linkDebugTest*`.
- `linuxX64Test` is Linux-CI only and was not run locally; nothing here claims it was.
