# The published ceiling is where the horizon reaches the top of the frame

`Camera` validates `pitch` in `[0, 90)` and publishes nothing about which of those angles are worth
using. A consumer wiring a tilt gesture to a finger has no way to know where to stop, so it either
guesses or discovers the answer on a device. Geoanimator guessed, and its own constant's KDoc says so
outright -- its ceiling is "conservative rather than renderer-mandated", carried over from a fork this
renderer never shipped.

`Camera.MAXIMUM_GROUND_FILLING_PITCH_DEGREES` answers it: **the largest pitch at which the ground
still reaches every row of the output frame.**

## What it is not

It is not a domain limit and does not narrow one. `pitch` keeps the full `[0, 90)` range it has always
validated, and a camera steeper than this draws exactly as asked -- a document authored before this
constant existed, or a keyframe an interpolated track passes through on its way somewhere shallower,
stays legal and stays drawn. This exists for a caller that wants to stop *offering* a pitch, which is
a different question from whether one is admissible, and conflating the two would rewrite what every
steep keyframe already in a document means.

## 66.75, and why not 67.5

The field of view alone would say 67.5. The vertical half angle is exactly 22.5 degrees -- that is
what `FOCAL_LENGTH_SCALE = 1 + sqrt(2)` encodes, since it is `1 / tan(22.5)` -- so the horizon, at 90
degrees from the downward axis, arrives at the top row when the pitch reaches `90 - 22.5`. Below that
every row has ground under it as a matter of geometry.

But ADR 0064 stops trusting a ground ray at 89.25 degrees rather than at 90, because that is where
`q` gets small enough for `t` to stop meaning anything, and the top row reaches 89.25 three quarters
of a degree earlier. So the honest ceiling is the one the renderer will actually honour:

```kotlin
MAXIMUM_GROUND_ANGLE_DEGREES - atan(1.0 / FOCAL_LENGTH_SCALE) * DEGREES_PER_RADIAN
```

which is `89.25 - 22.5 = 66.75`, computed from the two live constants rather than written down. A
change to either moves this with it, and the identity is already pinned from the other side:
`theGroundAngleBoundFirstBitesAtTheMaximumAngleLessTheHalfFieldOfView` asserts that no row is rejected
at this pitch and that some row is rejected just above it.

## Against the number this replaces

The fork's constant of the same name evaluates to **38.82**, and the difference is not a retuning --
it is what the two are derived from. That one is `atan((M - 1) * FLS / M)` at a far-plane multiple of
1.5: the pitch at which a *clip placed 1.5 camera heights away* starts eating the frame. It was
answering "where does my far plane bite", and calling the answer a ceiling.

This one answers "where does the ground stop reaching the top of the frame", which is the question a
gesture actually has. Because ADR 0065 bounds the work in tile selection instead of by clipping,
there is no far plane left to bite, and the ceiling is nearly twice as steep with the frame still
whole at it rather than half empty well below it.

## Above it

RenG keeps drawing. The horizon enters the frame, the rows above it have no ground because there is
no ground there, and what shows through is whatever the consumer's surface was cleared to --
transparent black by default, since `GlFrameDrawer` clears to `(0, 0, 0, 0)` and RenG draws no sky.
That is a horizon rather than a defect, which is precisely the distinction the fork's far plane could
not make: its clip put a hard edge across ground that was still there. ADR 0068 gives a consumer
something deliberate to put above the horizon instead of the clear colour.
