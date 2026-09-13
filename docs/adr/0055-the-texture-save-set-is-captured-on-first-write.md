# The texture save-set is captured on first write, not guessed in advance

`captureGlState` walks `FRAME_TEXTURE_UNIT_COUNT` texture units, making each active and querying its
2D binding and its sampler. That constant is 15, because `drawGeometry` binds one unit per consumer
texture up to `MAXIMUM_CONSUMER_TEXTURES`, and `GlFrameDrawer`'s own KDoc records why it is a fixed
maximum rather than a per-frame count:

> Capturing only `COMPOSITE_TEXTURE_UNIT_COUNT` left units 1..14 clobbered and never restored — a
> shipped ADR 0006/0023 violation. This is deliberately a fixed maximum rather than a per-frame count
> threaded down from the caller: a count derived from the frame's own content is one more thing a
> future pass can forget to widen.

That reasoning is right, and this ADR does not argue with it. It removes the premise instead.

## What the fixed set costs, measured

One drawn basemap frame in this tree makes **258 GL calls: 68 queries and 190 writes**. The
fifteen-unit loop is **30 of the queries and 62 of the writes** — 44% and 33% — and it costs exactly
that on every frame regardless of what the frame draws, because it is a constant, not a consequence.

The same frame touches **two** units: unit 0 for the tile colour and unit 1 for the DEM.

## Capture the unit the first time the frame writes to it

A `GlBinding` that delegates every method but three — `activeTexture`, `bindTexture`, `bindSampler` —
knows which unit is about to be overwritten at the moment it is about to be overwritten, which is the
last moment its old value is still there and the first moment anyone knows it matters. It saves that
unit's binding and sampler then, once, and the restore replays only what it saved.

The frame above costs 1 query for the active unit plus 2 per touched unit — **5 queries and 7
writes**, against 30 and 62. About 81 of the frame's 258 GL calls, **31%**, and the half it removes
is the query half, which is the expensive one: `glGetIntegerv` can force driver synchronisation where
a bind is a pointer write.

## This is stronger than the fixed set, not weaker

The fixed maximum guards against a future pass binding a unit nobody remembered to capture, and it
guards against it **only up to 14**. A pass that binds unit 15 is unprotected, silently, exactly as
units 1..14 were before the violation that constant was written to fix. `MAXIMUM_CONSUMER_TEXTURES`
is itself a cap RenG enforces on consumers, so today no pass can exceed it — but that is a second
invariant holding the first one up, and neither is checked where the binding actually happens.

Capturing on first write has no maximum to get wrong. A pass that binds unit 15, or unit 31, is
covered by construction, because the save happens in the same call that does the overwriting. The
property becomes local: *every unit RenG writes is saved immediately before the write*, which can be
read off one small file instead of held together by a constant and a consumer-facing cap agreeing.

`FRAME_TEXTURE_UNIT_COUNT` and its ceiling can then go, and with them the possibility of them
disagreeing.

## Two ways this could be got wrong, and what makes them safe

**A binding that escapes the wrapper is invisible to it.** Every pass takes its `GlBinding` as a
parameter, and ADR 0054 already moves the capture to `performDraw`, so the wrapper is installed
before the first GL call of a draw and is the only binding anything downstream ever sees. Nothing in
RenG stores a `GlBinding`; they are all passed down the call stack.

**`glBindSampler` names a unit without making it active.** Saving that unit then requires making it
active, querying, and switching back — two extra writes. RenG always binds a sampler to the unit it
has just made active, so this path is not taken in practice, but it is implemented rather than
asserted, because "RenG only ever does X with texture units" is precisely the class of claim this ADR
exists to stop relying on.

Deleting a texture unbinds it from every unit, which the fixed capture would have papered over and
this does not. It does not need to: a texture RenG deletes is a texture RenG created, GL names are
unique per context, and a host cannot have had RenG's name bound. The units the host owns are
untouched by RenG's deletions.
