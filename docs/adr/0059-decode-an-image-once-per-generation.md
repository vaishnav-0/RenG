# Decode an image once per generation, not once per frame

`acquireFrameResources` builds `decodedByKey` by calling `decodePng` on every image a frame
references — every sticker, every geometry-consumer texture, every model base-colour override —
freshly, on every `prepare()`. There is no memo.

The decoded pixels have exactly one consumer: `cachedTexture(key) { uploadTexture(binding, image, …) }`.
And `cachedTexture` returns the existing GL handle without calling its lambda when one is registered.

So from a frame's second appearance onward, **RenG decodes each image and then discards it**. The
bytes were already resident, the texture was already on the GPU, and the decode produced something
nothing reads.

## What it costs

Decoding an RGBA8 PNG in this tree, measured as a **lower bound** — the fixture's IDAT is deflate
*stored* blocks, so these figures contain no huffman decoding at all and a real compressed sticker
costs more:

| image | Kotlin/Native (macOS arm64, debug) | JVM |
|---|---|---|
| 256x256 | 3.8 ms | 1.1 ms |
| 512x512 | **14.3 ms** | 1.7 ms |
| 1024x1024 | **58.8 ms** | 5.3 ms |

A consumer's editor scrubbing a timeline of four 512x512 stickers pays 57 ms a frame on a native
build for pixels it throws away, against a frame budget of about 51 ms. The waste is larger than the
budget it is spent from.

## The field already exists, and ADR 0047 predicted this

`ResidentGeneration` carries `decoded: DecodedImage?`, and ADR 0047 said of it:

> `decodedCpuBytes` is charged at zero throughout, and that is accurate rather than an omission:
> every production install passes `decoded = null`. **The field exists for a decode path this cache
> does not yet have.**

This is that path. The claim that production always passes `null` stops being true, which is the
point — and nothing else about ADR 0047 changes, because it already charges `decoded?.byteCount`
against `maximumResidentCpuResourceBytes` and already has a test asserting a decoded image is charged
alongside its raw bytes.

## Why the cache and not the renderer

The obvious alternative is a renderer-level map, which is what the glyph atlas and the sprite atlas
already do — "the decode is memoized here instead, on the CPU side where it happens". For those two
it is right: there is exactly one of each per style, bounded by the style.

It is wrong here. There is no bound on how many images a consumer references, and decoded RGBA is
roughly four times the PNG it came from, so a renderer-lifetime map of them is an unbounded store of
the largest thing RenG holds — sitting beside the budget built to bound exactly that. Attaching the
decode to the generation puts it inside `maximumResidentCpuResourceBytes`, where eviction can reclaim
it and where `queryResources` can finally report a non-zero `decodedCpuBytes`.

It also gets the invalidation right for free. A generation is superseded when its content changes
(ADR 0007 makes a different image a different locator and therefore a different key), so a decode
attached to a generation can never outlive the bytes it was decoded from. A renderer-level map keyed
by `ResourceKey` would have had to be told.

## Half of this is a memo, and half is stopping the thing that defeated it

The memo alone fixed nothing measurable, and finding out why is the more valuable half of this ADR.

A resource whose server declares no freshness — no `freshUntilEpochMillis`, which is most fixtures
and plenty of real servers — is re-resolved from the consumer's `Store` on **every** frame. Only
content resolved as `ContentProvenance.RESIDENT` re-leases the generation already held;
everything else installs. So a second frame's sticker arrived as `STORE` content, installed a
**byte-identical twin** of the generation beside it, and found no decode attached, because the decode
was attached to the generation it had just superseded. Observed directly: two frames over one
sticker left `resident=2 retired=1`.

That churn costs more than a decode. It charges `maximumResidentCpuResourceBytes` twice for one
image, retires a generation nothing asked to retire, and — since ADR 0045 — keeps both alive for as
long as both frames are open. An editor holding a handful of prepared frames held a copy of every
sticker per frame.

So `observeAndTakeLease` gains a digest: a caller holding freshly resolved bytes asks for the
generation already here **if its content digest matches**, and installs only when it genuinely
differs. Same key and same digest is the same content; there is nothing a twin could express that the
original does not.

This does not contradict ADR 0007's "install never interns by content". That rule is about two
different keys never collapsing into one generation, and it still holds exactly. This is one key
declining to supersede itself with a copy of itself.

## Attaching after the fact, and the eviction that may follow

The decode cannot happen at install: the resource driver installs bytes and knows nothing about
images, and the decode needs `ResourceLimits` and a failure vocabulary that belong to the renderer.
So `ResidentGeneration.decoded` becomes attachable once, after install, and the cache charges the
bytes then.

Attaching can push the cache over budget, so it sweeps — and the sweep may evict the very generation
just attached to, if nothing leases it. That is correct rather than unfortunate: the caller holds the
decoded image it just produced and draws this frame with it; what eviction decides is only whether the
*next* frame decodes again. A budget that refused to bind on the largest thing in the cache would not
be a budget.

The attach is idempotent and first-writer-wins. Two preparations racing to decode the same generation
is possible — `prepare` is serialised by `preparationMutex`, so today it is not, but the cache has
never relied on its callers being serial — and both produce equal pixels, so keeping the first and
charging once is the only outcome that keeps the byte account true.
