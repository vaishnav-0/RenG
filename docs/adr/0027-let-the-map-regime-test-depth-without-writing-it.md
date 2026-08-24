# Let the map regime test depth without writing it

ADR 0025 changed the depth comparison to `GL_GEQUAL` and fixed the map regime's draw order, so that a
coplanar altitude-0 thing over the basemap ground would pass the depth test instead of being silently
deleted, with the later-drawn thing winning the tie. It kept the ground's depth writes on, and rejected
suppressing them on the reasoning that terrain will need the ground to occlude what is buried in it. That
rule is right about exact ties and says nothing at all about near ones, and near ones are what a moving
camera produces. Both halves of the gap were found by watching a rendered video, not by a test.

**The first defect: a coplanar `Geometry` z-fights the ground, badly.** A ground tile's depth is computed as
`projection * view * mapSpaceModel`, while a `Geometry` corner's is `projection * view` applied to a corner
that `resolveGeometry` already made camera-relative. Both describe the same plane and neither is wrong, but
they are different floating-point products, so at altitude 0 they differ by an epsilon whose *sign changes
as the camera moves*. `GL_GEQUAL` passes the equality and rejects the epsilon in one direction, so the quad
passes on some pixels and fails on others, differently every frame. Measured over a real map style, one
translucent grid quad's pixel count across fifteen consecutive frames ran 3609, 7026, 9468, 1257, 25, 0,
8607, 0, 6501, 2475, 4502, 8764, 8064, 3310, 12163 — twice erased outright, and never twice the same.

**The second defect: a map-anchored billboard is bisected at any nonzero pitch.** A map-anchored sticker
with `SCREEN` rotation is `CONTEXT.md`'s billboard, a screen-parallel quad pinned to a coordinate. Its
model matrix is composed in camera space at the anchor's view-space position, so every fragment of the quad
carries the *anchor's* single depth, while the map plane beneath it has depth varying down the screen.
Below the anchor row the plane is nearer than the billboard, so those fragments lose. The result is a hard,
exactly horizontal cut through the anchor: a 41x40 pixel diamond rendered as 39x19, at every pitch except
zero. ADR 0025's own text predicted this and called it correct — "under a pitched camera a screen-parallel
sticker quad is only coplanar with the ground at its anchor point, which is correct" — and in the picture
it is not correct, it is a marker cut in half.

Both defects were invisible to 942 passing tests for the same reason: every fixture used a **pitch-0
camera**, which is the one camera where the two surfaces land on bit-identical window depth. ADR 0025
verified that bit-identity and recorded it as evidence *for* the decision. It was also exactly what hid the
decision's gap.

**This ADR supersedes ADR 0025's depth-write ruling: no draw in the map regime writes depth. Every
map-regime pass — the ground, each `Geometry`, and each map-anchored sticker — enables `GL_DEPTH_TEST` and
sets `glDepthMask(GL_FALSE)` for itself before it draws.** ADR 0025's other two rulings stand unchanged:
the comparison is still `GL_GEQUAL`, and the order is still the ground, then `FramePlan.geometries` in list
order, then map-anchored stickers in list order. What changes is that the order is now the *whole* of the
rule inside the map regime rather than only its tie-break. A consumer could previously rely on
"later-declared wins an exact tie"; they may now rely on "later-declared wins", which is both stronger and
the thing they thought the first sentence said.

One mechanism closes both defects, and that is the main argument for it. There is nothing left to near-tie
with once nothing on the map plane writes depth, and a billboard cannot lose to a surface that wrote no
depth to lose to. The two defects looked like separate bugs — one a precision problem, one a geometry
problem — and they are one consequence of flat map-plane content acting as an occluder.

Three alternatives were weighed against pixels rather than against reasoning.

**`glPolygonOffset` on the ground pass** is the textbook answer to coplanar decals and was reconsidered
here with the earlier objection to it explicitly withdrawn. It fixes the first defect and cannot fix the
second: polygon offset displaces a surface by a *slope-scaled* amount, roughly one pixel's worth of depth
gradient per unit of factor, while the billboard's deficit grows with the billboard's own screen height —
covering a 32-pixel half-height would need a factor near 32, which destroys real occlusion everywhere else.
It also costs a wider GL seam across four platform bindings plus the recording fake and the entry-point
roster, and an amendment to ADR 0023's Restore Set for `GL_POLYGON_OFFSET_FILL` and the offset parameters.
A larger change that fixes half the problem loses to a smaller one that fixes all of it.

**A depth bias in the ground's own shader** has the same shape and the same ceiling: it is a cheaper way to
displace the ground and it displaces it by a bounded amount, so it fails the billboard for the same reason.
It has a second problem ADR 0025 already recorded against the equivalent altitude offset — with a 24-bit
reverse-Z buffer the resolvable step grows with distance, so a fixed bias is a tuning parameter whose
boundaries nobody can test.

**Suppressing only the ground's depth writes** is what ADR 0025 rejected, and it turns out to be
insufficient rather than merely costly. Measured directly: with `drawBasemap = false` and a consumer
`Geometry` at altitude 0 still in the plan, the billboard is bisected exactly as before, because the
`Geometry` writes the same plane depth the ground would have. Any fix scoped to the ground alone leaves the
second defect standing in the most ordinary frame a consumer writes.

The cost is real and is stated here rather than discovered later. **Map-regime content can no longer
occlude anything.** Three consequences follow. A `Geometry` declared below altitude 0 now paints over the
ground instead of being hidden by it — for a flat ground plane that is a data error more often than an
occlusion request, but it is a behaviour change. Two `Geometry`s at different altitudes no longer resolve by
depth; they resolve by declaration order, which is at least defined and predictable where the near-tie
behaviour they had was neither. And a `Geometry` whose two corners carry different altitudes is a tilted
quad with genuine 3D extent, and it gives up the occlusion a tilted quad could reasonably expect. That last
one is the sharpest edge of this decision and the first thing to revisit if a consumer hits it.

ADR 0025's terrain argument survives, correctly narrowed. Terrain is a displaced surface with real
elevation, drawn by a pipeline that does not exist yet; when it lands it writes depth as its own decision
and the map regime keeps testing against it, because this ADR removes the writes and not the test. The flat
ground at altitude 0 was never that surface, and — decisively — terrain would not have saved the second
defect anyway: a screen-parallel billboard intersects *any* ground surface it stands on, displaced or flat,
so a billboard-versus-surface policy is owed regardless of whether the surface writes depth. Models are the
same conversation: a model has volume, and a model pipeline that writes depth is a deliberate amendment to
this ADR rather than an oversight, which is why `SceneContentTest.noDrawInAWholeSceneRunsWithDepthWritesOn`
walks the whole call log instead of checking three pipelines one at a time.

Nothing about the GL seam or ADR 0023 moves. `glDepthMask` is already on the seam, `GL_DEPTH_WRITEMASK` is
already in the Restore Set, and `GlStateSnapshot` already captures and restores it, so a caller's own depth
write mask comes back exactly as it was found. `drawFrame` still leaves the mask **on** around its own
per-frame depth clear, which is why each pass owes its own `depthMask(false)` rather than inheriting one.

The contract is pinned in pixels, on a real driver, at cameras ADR 0025 never used. `BasemapReadbackSuite`
gains two sweeps. The first draws a coplanar altitude-0 `Geometry` three times per camera — alone, ground
alone, and both — across five pitch-and-bearing pairs, and requires that **every** pixel the quad paints on
its own and the ground covers on its own is still the quad's colour in the combined frame; a count would let
a quad that loses one region and gains another cancel out to a pass. The second renders a map-anchored
billboard at five pitches and requires its pixel count to match the pitch-0 count, which is a legitimate
equality rather than an approximation because the camera orbits its ground anchor at a fixed distance, so
the billboard's model-view-projection matrix is *identical* at every pitch in the sweep and only the ground
behind it differs. Both fail on the pre-ADR code and both name the defect in their failure text: the first
reported 184 of 2099 ground-covered pixels deleted at pitch 15, the second reported 512 pixels against 1024.

## Superseded in part, 2026-08-23: ADR 0030 lets a model write depth

This ADR's blanket rule — no map-regime draw writes depth — **no longer holds for the model pass**, which
tests and writes. ADR 0030 makes that amendment, and this ADR anticipated it in the paragraph above: "a
model has volume, and a model pipeline that writes depth is a deliberate amendment to this ADR rather than
an oversight." Everything else here stands unchanged: the ground and each `Geometry` still write no depth,
map-anchored stickers still write no depth, the comparison is still `GL_GEQUAL`, and each pass still owes
its own `depthMask(false)` because `drawFrame` leaves the mask on around its depth clear.

The map regime's order gains models between the geometries and the map-anchored stickers, so it reads:
ground, geometries, models, map-anchored stickers.

The billboard defect this ADR closed returns in a narrower form — a billboard sharing space with a *model*
can still be cut along its anchor row, because a model is now a real occluder. ADR 0030 accepts that
explicitly and records why. Read that ADR before concluding this one was wrong.
