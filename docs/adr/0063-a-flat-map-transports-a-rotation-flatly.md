# A flat map transports a rotation flatly

`resolvePlacement` resolves an `AnchoringMode.MAP` rotation by composing
`viewBasis * cameraEnu^T * anchorEnu * localRotation`, where the two middle terms are WGS84
east-north-up bases at the camera's ground anchor and at the thing's own anchor. That product
transports the rotation from one point on the **sphere** to another.

`GlobePlacementResolver`'s KDoc calls this deliberate, and says of the Mercator case that it "makes a
distant model tilt as though the earth were round". On a globe it is exactly right. This file's
camera is a `ResolvedMercatorCamera`, which draws a flat map, and there it is wrong.

## Why it is wrong on a plane, and by how much

North is straight up at every point of a Mercator frame. That is what the projection *is*: meridians
are parallel vertical lines. So the correct transport between two anchors on that map is the
**identity**, and carrying a spherical one instead rotates a thing by the angle between two ENU bases
that the projection has already flattened away.

The error is zero at the centre of the view and grows with angular distance from it. Measured at
latitude 20 with the camera level, against a model that never moved:

| separation from the camera's anchor | drift | drawn area |
|---|---|---|
| 10° | 4° | — |
| 20° | 12° | — |
| 40° | **34°** | **−37%** |

The model visibly tips over while nothing else in the frame does, because nothing else in the frame
is oriented through this term — the ground, the tiles and the labels are all placed by the projection
itself, which has no such term in it.

**This is the common case for the consumers this renderer has, not an edge of the envelope.** An
authoring tool frames a whole continent in a single shot; at that width a marker near the edge is
tens of degrees from the anchor, which is the bottom row of that table rather than the top.

## The fix is a deletion

Dropping the two bases leaves `viewBasis * localRotation`: bearing- and pitch-correct, and
position-independent, which is precisely what "flat map" means. Nothing replaces them, because on a
plane there is nothing for them to do.

`GlobePlacementResolver` is untouched and keeps the spherical composition, where the earth really is
round and the transport really is not the identity. The two resolvers now differ in exactly the way
the two projections differ, which is easier to defend than one shared formula that is right in one of
them.

## Why this is in a performance branch

It is not a performance change and does not pretend to be. It is here because it is a **prerequisite
for the consumer that motivated the rest of this work**: their fork carries this fix, and adopting
this branch without it would trade a set of measured speedups for a visibly tipped model on every
wide frame. Shipping a branch a consumer cannot actually move to is not shipping it.

Ported from that fork's `fb4e24a`, which shipped with no test. The tests below are written here.
