# Draw labels as a fourth scene list

ADR 0024 settled the order *between* RenG's two draw regimes: the map regime draws first, depth-tested
against the 3D scene, and the screen regime then composites on top as "a single ordered stack". Every
refinement since has stayed inside that frame — ADR 0025 fixed the map regime's internal order, ADR 0027
took its depth writes away, ADR 0030 gave them back to the model pass alone. All four share an assumption
none of them had reason to state: everything drawn is something the caller put in a `FramePlan`. Labels are
the first content that is not. They are derived by Rentile from the style and the tiles, and no entry in any
of the caller's three lists corresponds to one. ADR 0024 never answered where an engine-derived stack sits,
because until now there was no such thing; this ADR answers it.

**Labels are a fourth scene list, outside both order lists, drawn at phase 5 with the depth test off.** The
frame's phase order becomes:

```
map regime (depth tested)
  1  ground                   test, no write     (ADR 0027)
  2  geometries               test, no write     (ADR 0027)
  3  models                   test AND write     (ADR 0030)
  4  map-anchored stickers    test, no write     (ADR 0027)
  5  labels                   depth test OFF     <-- this ADR
screen regime (no depth)
  6  consumer screen-anchored stickers, by z     (ADR 0024)
```

So a consumer's screen-anchored sticker covers a label, and a label covers a map-anchored sticker. The
phase order is a relative order and not a claim that any earlier phase produced pixels: `drawLabels` is
orthogonal to `drawBasemap`, so phase 5 runs on a frame with no ground at all.

**The rejected alternative is a third `DrawnThingReference` member, and it deserves the space because it is
the design a reader arrives at first.** `DrawnThingReference`
(`internal/planning/MercatorSpatialPlanner.kt:22`) is a sealed interface with exactly two members,
`StickerAt(index)` and `ModelAt(index)`, and both mean one thing: the *n*th entry of the caller's own list.
A `LabelAt(index)` would not mean that. It would index a list the caller never wrote, whose length depends
on the style, the camera and which tiles resolved, and every `when` over the type — 27 call sites, measured
— would have to answer for a case most of them cannot have. `SceneContent`'s map loop and `drawScreenStack`
are both total over two members today, and `drawScreenStack` already carries one branch that exists only to
report a contract violation (ADR 0029's `SCREEN`-positioned model). A third
member would add a second such branch in most places it appeared, which is a type whose members are mostly
impossible wherever you look at it. Meanwhile the shape that costs nothing already exists twice over:
`geometries` and `groundTiles` are scene lists outside both order lists, drawn at fixed phases, and adding a
third of those touches zero of the 27 call sites. `Scene`'s bijection counts stickers and models only, and
keeps doing so — labels are outside it for the same reason they are outside the order lists.

**Batching forces the same answer independently, and this is the argument that would have decided it even
if the type had been free.** `drawScreenStack`'s KDoc says that binding the program per element means
interleaving two types in one stack "costs a program switch and nothing else". That is true of two
per-element pipelines, each of which issues one draw per object anyway, and it is false of a batch. The
label pass is the first thing in RenG that batches: it walks every surviving label, writes position, UV,
colour and halo per glyph corner into one array, and issues **one** draw. A program switch inside a batch is
a flush — the batch must be closed and re-emitted around the intruder. Put labels in the merged screen order
and one consumer sticker sitting between two labels splits the label batch in two, so the number of label
draw calls becomes a function of the consumer's plan rather than of the label content. A fourth list drawn
as its own phase has exactly one batch by construction.

**Phase 5 rather than 4 or 6 is a legibility ruling, and both neighbours are defensible.** A label is the
only content in a frame that carries its meaning by being *readable*; a half-covered pin is still a pin, and
a half-covered label is nothing. That argues for drawing labels late. Against drawing them last: the screen
regime is where a consumer puts the things they compose over the whole map — a HUD, an attribution badge, a
cursor — and a label painting over those would make the consumer's own overlay unreliable in a way they
cannot fix, since they do not control where labels land. Phase 5 gives the label the win over map content
and gives the consumer the win over the label, and both halves are things the losing party can do something
about.

**The depth test is off, and that is a scope decision rather than a mechanical one.** Under ADR 0030 the
model pass writes depth, so a label drawn with the test on would be clipped by any model in front of its
anchor — which is occlusion of labels by 3D content, explicitly undesigned and out of scope for this cycle.
Turning the test off is what defers that question instead of answering it badly. Two state obligations
follow, both from ADR 0027's rule that a pass owes its own state rather than inheriting one. Phase 4 leaves
`GL_DEPTH_TEST` **enabled**, so the label pass genuinely has to disable it — it is not merely restating
something already true, unlike the depth mask, which the model pass already turns off on its way out. And
the label pass writes no depth, so nothing about ADR 0027's billboard fix or ADR 0030's exit mask moves.
`drawScreenStack` then disables the test again for itself, idempotently, exactly as it does today.

**One membership question has to be answered in words, or two existing ADRs read as contradicted.** ADR
0027 requires that *every map-regime pass* enables `GL_DEPTH_TEST` and sets `glDepthMask(GL_FALSE)` for
itself, and ADR 0030 amends that to let exactly one pass — the model pass — write. The label pass does
neither: it disables the test. It is not an exception to those rules, because **labels are not a member of
the map regime**. They are a fourth scene list sitting between the two regimes, which is why they are
described by a phase number rather than by a regime, and both ADRs continue to describe every pass that is
in the map regime without amendment. What does still bind labels is the amended form of
`SceneContentTest.noDrawInAWholeSceneRunsWithDepthWritesOn`: exactly one phase in a whole scene enables
depth writes and it is the model pass, so a label pass that enabled a write would fail that test and should.
ADR 0025's and ADR 0027's "later-declared wins" is likewise a rule about the map regime's own members and is
untouched by this ADR; labels are not among them and simply paint after all of them.

Nothing about the GL seam or ADR 0023's Restore Set moves; `GL_DEPTH_TEST` and `GL_DEPTH_WRITEMASK` are
both captured and restored already. The contract is pinned by call-log assertion — labels draw at phase 5,
after the map-anchored stickers and before the screen stack's first bind, with depth testing disabled and
no depth write enabled anywhere in the pass.
