# Reject a screen-positioned model

RenG's anchoring is per-property. One `Placement` carries a `positionMode`, a `rotationMode` and a
`scaleMode`, each independently `SCREEN` or `MAP`, and `CONTEXT.md` selects the draw regime from
`positionMode` alone. Every one of the eight combinations is resolved by `PlacementResolver` and tested
there. So `Model(placement = Placement(positionMode = SCREEN, …), …)` is a well-typed request a consumer
can already write, and F-2 has to answer it.

The answer is that it fails, at frame planning, before acquisition or drawing.

The reason is not a missing feature but an absent dimension. `screenOrthographicProjection`
(`internal/gl/SceneContent.kt:476`) is built from four literal rows, and its third is
`listOf(0.0, 0.0, 0.0, 0.0)` — every vertex arrives at clip `z = 0` regardless of its model-space depth.
ADR 0024 then draws the whole screen regime as an ordered stack with the depth test disabled, which in GL
also disables depth writes. A flat quad needs neither: its z-index decides its place in the stack and it
has no interior to occlude. A mesh has both. Drawn there, its back faces paint over its front faces, and
no depth test could recover them, because there is no depth to test — the projection discarded it before
rasterisation began.

Three answers were available.

**Support it** needs a new projection with a real z row *and* a per-object depth scheme, so that each
model self-occludes while still compositing against its neighbours purely by z-index, as ADR 0024
requires. That is a genuine design — it reopens ADR 0024's rule for one element type — and it is
speculative until a consumer asks for it. A screen-anchored model is a plausible thing to want: a HUD
element, a compass rose, a 3D cursor. It is also a thing nobody has asked for.

**Degrade it** — draw it, accept the self-occlusion, warn — is the worst of the three and was rejected
outright. A model showing its own interior does not read as a documented limit. It reads as a bug in
RenG, and the consumer's next hour goes into their asset rather than into our release notes.

**Reject it**, chosen, follows a precedent this codebase already set. `UNSUPPORTED_PROJECTION_MODE`
(`internal/planning/FramePlanningCore.kt:291`) exists because a renderer may not yet support a
well-typed `ProjectionMode`, and `CONTEXT.md` states its rule: preparation fails before acquisition or
drawing, and RenG never substitutes a different mode. The same shape applies here for the same reason,
and a sibling error code at `FRAME_PLANNING` says so in the closed vocabulary rather than through a
generic invalid-value failure.

What is rejected is narrow, and the narrowness is the point. Only a `SCREEN` **position** on a `Model`
fails. `SCREEN` rotation or `SCREEN` scale over a `MAP` position — the billboard pinned to a coordinate,
which is the case the per-property design exists to express — remains supported and already works:
`composeMapModelViewProjection` applies the direction transform in camera space, so the billboard falls
out naturally rather than being special-cased.

This decision is cheaply reversible, and it is recorded anyway. Adding support later is a compatible
change, because a failure becoming a success breaks nobody. What would not survive being left unwritten
is the reasoning: a future reader finds stickers working in a regime models are refused from, and the
difference between them — that one is flat and the other is not, and that the screen projection has no
z row — is not visible from the API. An ADR is cheaper than that rediscovery.

One consequence to accept now rather than argue later: models placed by geographic position and models
placed in screen space are not interchangeable, and a consumer wanting a HUD model must either wait for
this to be built or anchor it to a coordinate they keep under the camera. That is a real limitation and
the release notes should say so plainly, rather than leaving it to the exception message.
