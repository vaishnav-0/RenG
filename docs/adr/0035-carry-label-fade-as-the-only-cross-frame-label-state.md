# Carry label fade as the only cross-frame label state

RenG's second standing contract is that a `FramePlan` is a complete definition of on-screen state. Every
cross-frame mechanism this project has built so far honours it in the same way: decoded images, uploaded
textures, parsed GLBs, compiled shaders and resident tiles are all **caches**, and a cache changes how fast
a frame is produced, never how it looks. Label fade is not that. Two consecutive frames carrying the same
plan can paint a label at different opacities, and that is the whole point of it, so fade is the first thing
in RenG that has to be reconciled with the purity contract rather than fitting inside it.

**The precedent that permits it is `previousSelectedLod`, and it is a precedent rather than an analogy.**
LOD hysteresis is already cross-frame state that visibly changes pixels — with prior integer LOD `L`,
selection increments only while `zoom >= L + 0.75`, so the same plan selects a different tile set depending
on where the camera came from, and the frame is made of different tiles as a result. It survives the purity
contract on four properties, and fade adopts all four: it lives in preparation rather than in the draw
(`RenGRenderer.kt:481`); it is an input to a pure function rather than mutable state inside one
(`observeMercatorLod` takes it as a parameter); it commits only on a successful `prepare`
(`RenGRenderer.kt:644`, after every fallible step); and it is cleared by the public `clearFrameHistory()`
(`RenGRenderer.kt:1047`). That last property is the one that actually does the reconciling, and it is worth
stating as the rule rather than as a detail: **after `clearFrameHistory()` the render is a pure function of
the plan.** A consumer who needs determinism has a public call that gives it to them, and a consumer who
does not gets a map whose labels do not flicker.

**Only fade is carried. Placement is not.** Which labels survive collision is recomputed from scratch on
every frame, purely from `(candidates, camera, output size)`; the carried state is one opacity per label,
easing toward 1 while the label is placed and toward 0 while it is not. `CONTEXT.md`'s **Frame History**
names three things today — the last successfully prepared frame index, Frame Plan and selected basemap tile
LOD — and this makes fade the fourth.

**The rejected alternative is full placement hysteresis, and it deserves the space because every mature
label renderer is assumed to have it.** Incumbency means a label that won a slot keeps it against a
higher-priority newcomer, which is what stops labels trading places as the camera moves. The evidence that
RenG does not need it is that there is nothing here to be hysteretic *about*: `text-variable-anchor` appears
**zero** times across the 34-style corpus, and Rentile does not implement it either — Rentile's own
`StyleCompiler.kt` says so in as many words, that "choosing among variable anchors still requires viewport
collision state". So every label RenG receives arrives with exactly one candidate position. Incumbency over
a single-candidate
set cannot move a label; it can only change *which* label survives a collision, which means its entire
observable effect is priority inversion — a minor road name placed first blocking a city name that pans into
view, and a frame that gets less correct the longer the camera sits still. Neither MapLibre nor Mapbox ships
it, and that is the corroboration rather than the argument. Fade buys the visible half of what incumbency
promises, at the cost of one number per label instead of a placement ledger.

**Collision resolves during `prepare()` and never during `draw()`.** This sentence is stated in as many
words because it is exactly what a later implementer would simplify away — `draw()` has the camera and the
output size in front of it, and resolving there looks like a shortcut that saves a data structure. It is not
one. `CONTEXT.md` says a Prepared Frame "may be drawn repeatedly" and that "drawing never changes history",
so a frame drawn twice would fade twice, and the fade rate would become a function of how often the consumer
redraws rather than of anything RenG controls. Until the X2 fix that contract was held up by prose alone;
`runModelReadbackSuite`'s `drawingRepeats` case
(`kmp/src/commonTest/kotlin/com/rohittp/reng/ModelReadbackSuite.kt:120`) now prepares one frame and draws
that same instance twice, and it is the only arrangement in which "drawing changed history" is observable at
all. It is also not vacuous: both draws are sampled at a point whose expected colour a blank frame cannot
produce, so it fails against a no-op draw rather than passing on two identical empty frames.

**The state is self-bounding, which is why it needs no public ceiling.** An entry is retained only while its
label is either a current candidate or still mid-fade; a label that has finished fading out and is no longer
a candidate is dropped. So the map is O(labels in view) rather than O(labels ever seen), and it cannot grow
across a long pan the way a naive "remember every label" map would. This is a real invariant with a real
failure mode — an entry retained one frame too long is harmless, an entry retained forever is a leak that
only shows up in a session nobody runs in a test — so it is pinned rather than assumed.

**The part that fails silently in both directions is the label identity derivation, and it is owed under ADR
0018 rather than invented.** Fade only works if the same label in two consecutive frames derives the same
identity. Get it too fine and identities never match, so every label starts its fade from scratch on every
frame, everything pops exactly as it did before, and every test that merely asserts opacity is in `[0, 1]`
still passes. Get it too coarse and identities collide, so a label inherits another label's opacity — which
looks like a rendering bug rather than an identity bug, and looks like it only under a moving camera. Both
failures are invisible to any test that does not run at least three frames in an A/B/A shape, and a test
that clears history between the two frames tests the historyless path twice and passes with the fade code
deleted. ADR 0018's canonical-bytes machinery is where the derivation belongs, with its own root kind and
its own frozen field set, for the same reason every other identity in RenG lives there: a hash over whatever
fields were handy is a compatibility contract nobody wrote down.

**One input fade needs does not exist, and this ADR does not invent it.** `FramePlan` carries `frameIndex`,
a monotonically increasing `Long`, and no wall-clock time at all; `drawLabels` is the only field this
cycle's public API delta adds to it. So the ease advances per successful `prepare()`, and a fade's duration
in seconds is whatever the consumer's frame rate makes it. Reading a clock inside `prepare()` was available
and is rejected: it would make preparation non-reproducible, which costs the executable
guarantee the paragraph above just bought and makes every fade test time-dependent. A time-based ease is a
public time input on `FramePlan`, which is a decision of its own and not this cycle's.

`prepareBatch` needs no rule of its own. It is literally `plans.map { prepare(it, accessMode) }`
(`RenGRenderer.kt:834`), so state placed where `previousSelectedLod` sits inherits the batch behaviour for
free: fade commits at the same boundary the LOD does, on a `prepare` that succeeded, and a `prepare` that
failed or was cancelled advances no label's opacity exactly as it advances no LOD. The hazard here is
deviating from that precedent, not conforming to it — a fade committed anywhere else would be the one piece
of Frame History with its own commit rule.
