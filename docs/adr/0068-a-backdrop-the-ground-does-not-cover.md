# A backdrop the ground does not cover

ADR 0067 publishes a ceiling at 66.75 degrees and says the renderer keeps drawing above it: the
horizon enters the frame and the rows above it carry no ground, because there is none there. What
shows through is whatever the surface was cleared to, and `GlFrameDrawer` clears to `(0, 0, 0, 0)`.

Transparent composites, so this is not a hole. But a consumer who wants something deliberate above
the horizon has no way to say so, and the alternative RenG does not have is a sky.

## It needs no horizon math, and that is the whole design

A sky pass has to know where the horizon is. This does not. The backdrop is drawn **first, full
frame, behind everything**: wherever the ground draws it covers the backdrop, and wherever the ground
does not draw the backdrop is what remains. The horizon is simply where the ground stops, and nothing
computes it.

That is why this is a quad and a sampler rather than a pass with its own geometry. It also means the
backdrop is correct for every frame, not only steep ones -- a frame whose basemap failed to load, or
one drawn with `drawBasemap = false`, gets the same treatment for the same reason, with no special
case anywhere.

Drawn before the ground rather than composited after it, because the ground is opaque where it draws.
Painting first costs one full-screen quad and no per-fragment test; compositing afterwards would cost
a test on every ground fragment to find out whether it had been covered.

## What it is not

Not a sky. There is no gradient, no atmosphere model, and no fog: it does not rotate with bearing, it
does not change with pitch, and the ground meets it at a hard line. A fade would mean a distance term
in both ground fragment shaders, whose source text is pinned character-for-character by string
assertions in `GroundPipelineTest`, `GlobeGroundPipelineTest` and `GroundShadingTest` -- and a fade
was explicitly not what was asked for. A repeating pattern was.

**`null` is today's behaviour exactly.** The clear stays `(0, 0, 0, 0)` and the pass does not run. A
consumer compositing RenG's output over its own background must not start receiving opaque pixels
because it upgraded, so the backdrop is something a frame opts into and never a default.

## Its own resource class, not a sticker's

`BACKDROP_IMAGE` is appended after `BASEMAP_GLYPH_RANGE`, for the reason that constant's own KDoc
already gives: no existing ordinal, wire value or report-order entry moves, and both of the latter are
explicit tables a reordering would silently renumber.

Reusing `STICKER_IMAGE` was the cheaper option and is wrong. `RenGRenderer` filters the traversal for
that class and pairs the result with `plan.stickers` **by index**, under a `check` that the two counts
match. A backdrop in that list is an off-by-one in every sticker's texture, caught by an assertion if
the counts happen to differ and silent if they do not. The class carries the same byte ceiling, the
same `image/png` accept value and the same `DECODE_PNG` gate as a sticker image, because it is the
same kind of bytes; what it does not share is the list.

## Tiled in logical pixels

`Backdrop.tileSizeLogicalPixels` says how far the pattern repeats, rather than expressing the repeat
as a multiple of the image's own dimensions. A consumer choosing a pattern should not have to know how
many pixels it happens to be encoded at in order to control how big it looks, and every other size
this renderer takes from a consumer is already in logical pixels. The sampler is `GL_REPEAT` on both
axes -- the first texture RenG uploads that wraps, which is exactly why it cannot borrow the sticker
pipeline's sampler and has its own.
