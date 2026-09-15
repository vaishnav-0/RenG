# The ground is bounded by the horizon, not by a far plane

`physicalPixelGroundRay` has no upper bound on how far a ground hit may be. `q = cos(pitch) - v *
sin(pitch)` is the only gate, and it rejects a ray only once it points at or above the horizon.
Immediately below the horizon `q` is a small positive number, `t = d * cos(pitch) / q` is enormous,
and the `Hit` is returned with a Mercator point arbitrarily far from the anchor.

Nothing downstream recovers from that. `clippedPhysicalPixelFootprint` clips the resulting quad only
to numerical support -- `x in [-16384, 16385]` world copies -- which is thousands of copies of the
world, so `selectBasemapTiles` counts an absurd number of instances and returns `OverBudget`, and
`MercatorSpatialPlanner` turns that into `RESOURCE_LIMIT_EXCEEDED` at `FRAME_PLANNING`, which
`prepare()` throws. Steeper still and a vertex goes non-finite, `clipPolygon` returns `Empty`, and
the map is silently blank instead. **Today's steep-pitch behaviour is a thrown frame or a blank one,
not a slow one**, and that is the thing being fixed.

## Two places to put the bound, and they are not equivalent

A ray that runs away can be stopped by capping `t` -- how far the hit is -- or by capping the angle
`theta` the ray makes with the downward axis. The fork's `bb19585` capped `t`, at
`FAR_PLANE_DISTANCE_MULTIPLE = 1.5` camera heights. This ADR caps `theta`, at 89.25 degrees, which is
`maxMercatorHorizonAngle` adopted from MapLibre GL JS (`src/geo/projection/mercator_utils.ts`) with
attribution.

The difference is not stylistic; it is two orders of magnitude of frame:

- **Capping `t` at 1.5 camera heights takes the ground away from most of the frame.** The fork's own
  measurement, in its own KDoc: at pitch 89.9 the clip had eaten 947 of 1920 rows -- just under half
  the frame -- and the onset is at 38.82 degrees, shrinking the drawn region in a near-linear line
  from there. Its KDoc is honest about this, calling it "a large, increasing fraction of the frame"
  and noting no fade is implemented.
- **Capping `theta` at 89.25 degrees takes away a sliver, and only past 66.75 degrees.** Because the
  vertical half field of view is exactly 22.5 degrees, and `theta = pitch + atan(v)` exactly, the top
  row's angle is `pitch + 22.5`. So the cap first binds at `89.25 - 22.5 = 66.75` degrees of pitch,
  and not one row earlier. Measured across the sweep: 0.00% of frame height clipped at 66.75, 0.62%
  at 67.0, 1.60% at 67.4.

A viewer cannot see the second one. A viewer cannot miss the first one.

## Why the exponent of the problem is not the exponent of the fix

The fork chose `1.5` because a larger multiple costs tiles "roughly with the square of the multiple",
so it was trading frame area against tile count with one number. That trade is real, but it is only
forced if **one** constant has to do both jobs -- stop the ray diverging, and bound the work. It does
not. ADR 0065 bounds the work in tile selection, where the cost actually lives, which leaves this
constant free to be set on the only question it should answer: how close to the true horizon can the
ground be drawn before the arithmetic stops being trustworthy.

So `MAXIMUM_GROUND_ANGLE_DEGREES` is deliberately set as close to 90 as is safe rather than as close
to the camera as is affordable. MapLibre's own comment on the same constant states both halves of
that: it "must be less than 90 to prevent errors", and "shouldn't be too close to 90, or the distance
to the horizon will become very large, unnecessarily increasing the number of tiles needed to render
the map." With ADR 0065 in place the second half is no longer this constant's problem, and 89.25 is
inherited unchanged rather than retuned, because nothing here is evidence for moving it.

## The test, and why it carries no trigonometry

`theta <= MAXIMUM_GROUND_ANGLE_DEGREES` is applied without computing `theta`. From the same algebra
that gives `theta = pitch + atan(v)`:

```
tan(theta) = (sin(pitch) + v * cos(pitch)) / q
```

and `q > 0` has already been established by the horizon branch above, so multiplying through
preserves the sense:

```
sin(pitch) + v * cos(pitch) <= q * TANGENT_OF_MAXIMUM_GROUND_ANGLE
```

One multiply and one compare, against a `tan` evaluated once at file scope. Both `sin(pitch)` and
`cos(pitch)` are already in hand -- they are `camera.cameraUp.z` and `camera.cameraBack.z`, read back
out of the resolved basis exactly as the existing code reads them, so this introduces no second
source of truth for the pitch.

The comparison is **closed**, matching `exactHorizonQZeroAndNearBoundaryTOneUseClosedClassifications`
and the near-plane boundary, which are both closed. This matters at exactly one camera: at pitch
66.75 the top row's `theta` is 89.25 to the last bit, and the two sides of the inequality agree to
within 7.1e-15 -- one ULP. A closed comparison admits that row, so the ceiling the next ADR publishes
is reached with the frame still whole rather than one row short.

## What this deliberately does not do

It does not bound the tile count. At 66.7 degrees of pitch a uniform-LOD selection still asks for
85.3 times the tiles a level frame asks for, and this ADR changes that number by nothing at all --
the rays it rejects were the ones already being rejected as `HorizonOrSky` a fraction of a degree
later. That is ADR 0065's job, and until it lands this constant is a guard against non-finite
arithmetic rather than a performance fix.

It also introduces no fade. The ground stops at a hard line, and what is behind it is whatever the
consumer's surface was cleared to -- transparent black, since `GlFrameDrawer` clears to
`(0, 0, 0, 0)` and RenG draws no sky. ADR 0069 gives a consumer something to put there.
