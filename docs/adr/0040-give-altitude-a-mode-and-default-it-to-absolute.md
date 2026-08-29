# Give altitude a mode and default it to absolute

**`AltitudeMode = ABSOLUTE | GROUND_RELATIVE`, carried by `Placement` and — because a `Geometry` carries no
`Placement` — by `Geometry` as well, defaulting to `ABSOLUTE`.** Under `ABSOLUTE` an altitude is a height
above the ellipsoid, which is what it has always been. Under `GROUND_RELATIVE` it is an offset above the
terrain surface RenG drew under it in that same frame. The public cost is one enum, two fields, and the
mechanical `copy`/`componentN` fallout on two data classes.

**The default is not a preference; the glossary already decided it.** `CONTEXT.md:265` defines a map
position's components as `(latitude degrees, unwrapped longitude degrees, WGS84 ellipsoidal altitude
metres)`. A ground-relative default would make that sentence false for every caller who had not opted out,
and would quietly redefine a term the whole domain model is written in. A consumer who writes zero over a
plateau means sea level and gets sea level; `GROUND_RELATIVE` is the opt-in that means the other thing.

**Nothing already shipped changes behaviour, and that is checkable rather than asserted.** The two modes
are identical wherever the ground is flat, which is every frame of every published release and 28 of the 34
styles RenG is verified against — only 6 declare a top-level `terrain` block. So the ABI grows and no pixel
moves, and the test that says so is that every existing plan renders byte-identically, not that the diff
looks additive.

**`Geometry` needs its own field because `CONTEXT.md:323` says it carries no `Placement`.** One mode
governs both of its corners rather than one per corner: the glossary already defines the northern edge as
`topLeft.z`, the southern as `bottomRight.z`, and altitude as interpolating north-to-south between them, and
a rectangle with one corner pinned to the ellipsoid and the other riding a ridge is a shape nobody asked
for. Under `GROUND_RELATIVE` that interpolated altitude becomes an offset above a surface sampled at every
subdivided vertex rather than only at the two corners — which is the difference between a quad draped over
a ridge and a quad cutting through it.

**A fourth `AnchoringMode` was rejected, and it is the tempting answer.** RenG already has
`AnchoringMode = SCREEN | MAP` and already resolves it per property, so `GROUND` looks like a third space to
add beside them. It is not one. Anchoring chooses the space a property resolves in, and it is legal on all
three of position, rotation and scale — so a `GROUND` constant would be writable as `rotationMode` and
`scaleMode`, where it means nothing, and the type would accept nonsense that RenG would then have to reject
at planning. Altitude mode modifies **one component of a map position**, and only that. Worse, folding it
into `AnchoringMode` would change what values already in consumers' code mean, where a separate field
defaulting to `ABSOLUTE` changes nothing.

**An `elevationAt(latitude, longitude)` query was rejected as circular, and it deserves the space because
it is what a consumer would ask for.** The consumer wants a number so it can put the number in the plan
itself. But elevation comes from resident DEM tiles, residency comes from the camera, and the camera comes
from the plan the consumer is trying to build. RenG can only answer from the residency of the frame it has
already drawn — so the query is right while the camera is still and **wrong exactly when the camera moves**,
which is the one condition under which anybody is watching. It also contradicts two contracts at once. A
`FramePlan` is a complete definition of on-screen state, and a plan whose altitudes were computed from a
previous frame's cache is not complete, it is a differential against a state the consumer cannot see. And
frame-to-frame reuse of decoded and uploaded resources is RenG's internal concern, *invisible in the API*;
an elevation query makes internal residency into the consumer's problem and makes the meaning of a plan
depend on what RenG happened to be holding. A plan-declared mode has neither problem: the plan says "on the
ground", and RenG resolves it against the tiles that frame actually has, in the frame that draws it.

**Having the consumer bake in its own elevation was rejected for a narrower reason.** It is available today
and always will be, and nothing here forbids it. But RenG performs no network I/O and owns no cache, so the
consumer would be fetching DEM tiles RenG is fetching anyway, decoding them with a second implementation of
two encodings, and producing a surface that disagrees with the one on screen wherever the two disagree about
resolution, overzoom or exaggeration. Two sources of truth for one surface is a worse failure than a stale
one, because it looks correct.

**Four costs, stated here rather than discovered later.** First, resolution happens on the **CPU at prepare
time**, because label collision resolves in `prepare()` and never in `draw()`, so a decoded DEM has to exist
CPU-side as well as in the vertex shader's texture. Second, decoding every visible tile for that purpose
would be about **224 MiB** against a `maximumDecodedImageBytes` of 256 MiB that is already shared with every
raster — F-2's debt, unchanged — so the decode is sparse: only tiles containing a ground-relative placement
or a label anchor. Third, the CPU lookup and the GPU fetch are a `Double` path and a `Float` path, and ADR
0039 records what that costs where ground-relative content sits coplanar with the surface it is riding.

Fourth, and the one most likely to surprise someone: **where terrain is absent or degraded,
`GROUND_RELATIVE` resolves against the flat ground and therefore means `ABSOLUTE`.** A pin meant to stand on
a ridge stands at sea level, and the frame's coverage diagnostic is the only thing that says so. That is
silent, and it is accepted, because the alternative is failing the frame over a coverage gap — which ADR
0041 rejects on its own measured grounds.

**What this does not do.** It gives no altitude mode to `Camera`: the camera's own arithmetic stays
terrain-free by decision this cycle, with no collision, no clamping and no elevation-aware LOD. And it adds
no query of any kind to the public API. The whole of a consumer's access to terrain height is the sentence
"this thing is on the ground", said in the plan that draws it.
