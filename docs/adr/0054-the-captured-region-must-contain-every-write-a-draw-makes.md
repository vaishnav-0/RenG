# The captured region must contain every GL write a draw makes

ADR 0006 promises that RenG hands the context back exactly as it found it, and ADR 0023 defines the
Restore Set as the state RenG writes. `withCapturedGlState` implements both — inside `drawFrame`.

`performDraw` does GL work before it ever reaches `drawFrame`:

```
when (val resolved = resolveGroundTiles(frame, textureLeases)) {
    is GroundTilesResult.Resolved -> drawResolvedFrame(...)   // -> drawFrame -> withCapturedGlState
```

`resolveGroundTiles` uploads every ground tile that is not already resident, and `uploadRgba` ends
with `binding.bindTexture(GL_TEXTURE_2D, texture)` and never puts back what was there. Those binds
land on whatever unit the **host** left active, before RenG has captured anything.

## What that costs, measured rather than argued

Instrumented on one drawn basemap frame in this tree — four tiles, none resident — the call log
shows **4 of the frame's 24 `bindTexture` calls occur before the capture begins**, one per tile.

The sequence is then:

1. the host has its own texture bound on the active unit;
2. RenG uploads four tiles, leaving the fourth bound on that unit;
3. `captureGlState` records the unit as holding **RenG's** texture;
4. the frame draws;
5. `restoreGlState` faithfully restores RenG's texture.

The host's binding is gone, and RenG's promise is broken by the very code that exists to keep it.
The capture is not wrong about anything — it is simply asked the question too late.

`RecordingGlBinding` cannot catch this: its query answers come from static maps that its write
methods never mutate, so a capture/restore round trip over the fake passes whatever the writes did.
The test that does catch it asserts the **order** — that no texture is bound before the capture's
first query — which is a structural property the fake records faithfully.

## The boundary moves to where the draw begins

The captured region becomes `performDraw`'s GL work rather than `drawFrame`'s, so the uploads sit
inside it. Nothing about the Restore Set changes; only where it is taken.

`drawFrame` keeps capturing, because every other caller depends on it doing so — `GlFrameDrawerTest`
and, more importantly, `GlConformanceSuite`, which asserts a byte-exact round trip on real hardware
and is what caught ADR 0023's `glBindBufferBase` ordering bug. A `drawFrame` that stopped capturing
would leave that suite proving nothing.

So `withCapturedGlState` becomes **nestable, joining rather than re-capturing**: an inner call that
finds the state already captured runs its block and leaves both the capture and the restore to the
outer one. That is the same root/join shape ADR 0051 gave `withOperation` for the same reason — an
invariant that was true of one caller has to survive being called from inside another — and it costs
nothing, because the inner capture it replaces was pure duplication.

## What is deliberately not fixed here

`uploadRgba` still leaves its texture bound rather than restoring the previous binding itself. That
would be the other repair, and it is worse: it costs a `glGetIntegerv` per upload to learn what to
put back, and it would leave the general rule — *a draw's GL writes belong inside the captured
region* — unstated, so the next piece of pre-draw GL work would reintroduce exactly this defect.

The related exposure that `GL_UNPACK_ALIGNMENT` is normalised inside `drawFrame`, after these
uploads have already read whatever the host left, is real and is **not** addressed here. It is
narrow — RGBA8 rows are 4-byte aligned, so only an odd-width texture under a host alignment of 8
misreads — and it is a different decision about a different piece of state.
