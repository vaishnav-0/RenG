# Let a model write depth inside the map regime

ADR 0027 removed depth writes from every map-regime draw. It was right, and it was right for surfaces: two
defects shipped past 942 passing tests because flat map-plane content acted as an occluder, and removing the
occluder is what made a coplanar `Geometry` stop tearing itself apart and a billboard stop being sliced in
half along its own anchor row. That ADR closes by naming the exception it expected: "a model has volume, and
a model pipeline that writes depth is a deliberate amendment to this ADR rather than an oversight." This is
that amendment.

**A mesh that writes no depth cannot occlude itself.** Every triangle passes the test against whatever is
behind it and paints in submission order, so back faces show through front ones and a near surface is
overwritten by a far one declared later. Back-face culling hides this for a closed convex mesh and for
nothing else — and 109 of the 111 materials in the consumer's own corpus are `doubleSided`, which turns
culling off entirely. The picture is not subtly wrong; a car renders with its far door drawn over its near
one. Painter's order is a complete rule for a flat quad, whose fragments all carry one depth, and it is not a
rule at all for a surface that folds over itself.

**So the map regime gets three depth phases rather than one policy.** Flat map-plane content — the ground,
then each `Geometry` in list order — tests depth and writes none, exactly as ADR 0027 requires. Models then
test **and write**. Map-anchored stickers then test and write none, again as ADR 0027 requires. The draw
order becomes **ground, geometries, models, map-anchored stickers**: ADR 0025's order with models inserted
before the stickers. Every other clause of ADR 0027 stands unchanged, including the reason each pass sets
`depthMask(false)` for itself rather than inheriting it — `drawFrame` leaves the mask on around its own
per-frame depth clear, so a pass that assumes it is off is assuming something no caller guarantees.

Models go before map-anchored stickers rather than after because a map-anchored sticker is a marker, and a
marker paints over the scene it marks. Put the model last and it paints over every pin standing in front of
it, which is the more common frame and the more obviously wrong one. This also means the model pass owes a
`depthMask(false)` on the way out, before the stickers draw. Forgetting it would silently reinstate ADR
0027's billboard defect in every frame carrying both a model and a billboard — the mask is frame state, not
pass state, and the sticker pass has no reason to distrust what it inherits.

**The cost is real and is accepted rather than discovered.** A model is now an occluder, so a billboard
sharing space with one can still be cut along its anchor row: the billboard's quad is screen-parallel and
carries the anchor's single depth, while the model beside it has depth varying across the screen, so the
half below the anchor loses. That is the same geometry ADR 0027 diagnosed against the ground, in a narrower
place — it needs a model, not merely a map. It is accepted because the alternative is worse in a way that is
easy to compare: a marker clipped by the car it stands on is a wrong picture of one object, and a car
rendered inside out is a wrong picture of the object the frame is about. Nothing here reopens ADR 0027's
rejection of `glPolygonOffset` or of a shader depth bias; both fail the billboard for the reasons measured
there, and both would fail it here too.

The narrower defect is not closed and no fix is designed. Closing it needs a billboard-versus-volume policy
— which ADR 0027 already observed is owed regardless, since "a screen-parallel billboard intersects *any*
ground surface it stands on, displaced or flat." Recording it here means the next person to see a clipped pin
finds the reason rather than rediscovering it.

Nothing about the GL seam or ADR 0023's Restore Set moves. `glDepthMask` is on the seam and
`GL_DEPTH_WRITEMASK` is in the Restore Set already.

One existing test changes meaning, and it should be read as the amendment landing rather than as a
regression: `SceneContentTest.noDrawInAWholeSceneRunsWithDepthWritesOn` walks a whole scene's call log
asserting that nothing enables depth writes. ADR 0027 wrote it that way deliberately, "instead of checking
three pipelines one at a time", precisely so that a model pass could not slip past it unnoticed. It now
becomes an assertion that **exactly one** phase writes depth, that the phase is the model pass, and that the
mask is off again before the stickers draw.

Verification is analytical readback on the two targets that can hold a GL context, matching Cycle E's gate
and this cycle's spec: a model occludes the ground it stands on, and a model behind another is occluded by
it. Pixel verification remains Cycle J's.
