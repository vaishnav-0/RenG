# Take a basemap tile's pixels, not its PNG, under a budget

RenG asks Rentile to rasterise a basemap tile, Rentile encodes the result as a PNG, and RenG decodes
that PNG back into pixels before anything samples it. Both halves of that round trip are pure
overhead: the bytes were already pixels inside the engine. Rentile 0.11.3 publishes `renderRaw`,
whose contract is the same drawing with only the encode skipped, and **RenG's ground path takes it —
but only while the pixels it is holding stay under a declared budget.**

**Nothing about tile identity moves, which is what makes this a performance change rather than a
behaviour change.** `RawRenderedTile` carries the same `contentKey` a `RenderedTile` does, and RenG
derives its own `ResourceKey` from the style digest, the canonical tile and the output size
(ADR 0018) rather than from anything the engine hands back. A tile rendered raw and the same tile
rendered as a PNG resolve to the same resident texture, so no cache is invalidated and no frame
draws a different picture because of this commit.

## Why a budget, and not simply the faster call

**A prepared frame retains its tile bytes until it is drawn, and that window is the caller's to
choose.** `RenGPreparedFrame` holds a `basemapTileSnapshot`, and `resolveGroundTiles` reads the bytes
out of it at draw time, because `prepare()` cannot upload: it is a `suspend` function that may resume
off the thread holding the render context, which is the same reason it cannot consult the GL object
registry. So the bytes live across prepare→draw, and a 512 px tile is roughly 100 KB encoded against
exactly 1 MiB raw.

That multiplier is about ten, and it lands on a number RenG does not control. One frame in flight is
unremarkable — a few dozen tiles, a few dozen mebibytes. But `prepareBatch` admits up to
`maximumPreparationBatchSize` plans, and a caller may hold every one of them open before drawing any.
Unbounded, the honest description of `renderRaw` here is "ten times the memory, multiplied by a
number the library does not choose", which is not a trade a library may make on a consumer's behalf.

This is not hypothetical, and the evidence is a sibling's. Travel Animator consumes Rentile directly
and made this exact change. Its first attempt held raw tiles resident with no budget and **grew to
1.6 GB before dying**; budgeting against the newly planned set instead reached **578 tiles / 318 MB
against a 256 MiB budget and threw `OutOfMemoryError` at frame 481 of 1817**; budgeting against
measured held bytes **deadlocked** at the first window boundary. What finally shipped was transient
RGBA bounded to roughly twelve tiles in flight, with the encoded form replacing it behind. The
durable sentence from that sequence is theirs: *a byte budget can only shrink the window; it cannot
shrink what is already held.*

RenG has no seam at which to make the pixels transient — prepare produces them, draw consumes them,
and nothing may run in between. So the budget here is applied at the only point where it can still
decide anything: **before the render call, against the bytes already outstanding.** A preparation
whose tiles would not fit asks for PNGs instead and pays the encode and the decode, which is exactly
what every release before this one did. The fallback is not a degraded mode; it is the old behaviour,
reached deliberately.

`ResourceLimits.maximumInFlightRawBasemapTileBytes` defaults to 64 MiB — sixty-four tiles at the
default 512 px output, comfortably more than any single frame's ground plane and far less than a
batch's worth. It sits between the existing `maximumBasemapTileBytes` (32 MiB) and
`maximumDecodedImageBytes` (256 MiB), and like every other field on that type it is a caller's to
raise.

## The premultiplication seam

**The pixels arrive premultiplied, and that is the one thing this change can get silently wrong.**
Rentile documents `renderRaw` as returning premultiplied N32, while RenG's `DecodedImage` is
documented, as an invariant of the type, as "tightly packed RGBA8, **unpremultiplied**, with no row
padding". `uploadTexture` reads that invariant and premultiplies for `TextureContent.IMAGE`, which is
right for every PNG that reaches it and wrong for these bytes. Premultiplying twice darkens partially
transparent texels and leaves opaque ones alone, so a basemap with an opaque background would look
almost correct — the worst available failure.

So raw tiles **do not pass through `DecodedImage` at all**. The alternative considered and rejected
was a third `TextureContent` variant that skips the premultiply: it works, but it puts premultiplied
bytes inside a carrier whose documented contract says they are not, and the next person to read that
KDoc would be reading a lie. Instead `uploadTexture`'s GL half is shared with a second entry point
that takes already-premultiplied bytes with a width and a height, so the two differ in exactly one
statement and each says in its name which side of it it is on. `GlyphAtlasUpload` already establishes
that a caller may separate how bytes are treated from which sampler they get; this separates how
bytes are treated from which type carries them, for the same reason.

## What is claimed, and what is not

**The measurement behind the direction is Travel Animator's, not RenG's.** On an OPPO CPH2629, over a
1817-frame, 1022-tile export, two runs per arm with the tile cache cleared before each, skipping the
encode took total export time from a mean of **260.5 s to 203.6 s (−21.8 %)** and the encode column
from **155 s to 0.00**; the two candidate runs differ by 1.1 s against a metric whose usual spread is
about ±30 s.

RenG's own figure is unmeasured and this ADR does not borrow that one. TA encodes at 512 px on two
raster workers inside a long export; RenG draws a different number of tiles per frame under a
different camera. What transfers is the mechanism and its sign: RenG performed an encode and a decode
per tile per prepare, and now performs neither while the budget holds. Anyone wanting a RenG number
should take it from a real device, not from this file.

**One observable behaviour does change, and it is a frame that used to fail and now draws.**
`maximumDecodedImageBytes` below one tile made every basemap frame fail `RESOURCE_DECODE_FAILED` at
`DRAW`, because every tile was decoded. On the raw path nothing is decoded, so that configuration now
renders. This is the limit meaning what it says rather than a hole in it — it bounds decoding, and
there is no decoding — but it is a real difference and it is recorded here rather than discovered.
`RendererGroundTextureBudgetTest` still pins the failure by holding the raw budget at one byte, so
the classification stays covered on the path that can still reach it.

`render` stays on Rentile's interface and stays used by RenG — it is the fallback above. Removing it
was never in question.
