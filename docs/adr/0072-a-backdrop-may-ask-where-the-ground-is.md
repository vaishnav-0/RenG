# A backdrop may ask where the ground is

ADR 0071 gave a backdrop shader `uResolution` and `uFrameIndex` and nothing else, and said why:
a flat pattern needs no camera, and every name RenG documents is a name it must go on meaning the
same thing by. This adds one more, `rengBackdropInverseViewProjection`, because a backdrop that is
fixed to the *map* rather than to the screen cannot be written without it.

## The consumer cannot compute this, and that is the whole argument

A screen-space backdrop has everything it needs: `gl_FragCoord` says where the pixel is, and a
pattern in that space is a pattern taped to the glass. Making the pattern lie on the ground instead
requires undoing the projection, and the projection is ours.

A consumer *could* rebuild it. `resolveMercatorCamera` is a pure function of the public `Camera` and
the output size, so nothing is hidden. What that buys is roughly two hundred and fifty lines of
projection arithmetic which must stay bit-compatible with this library across every repin, and the
failure mode when it drifts is not an exception -- the grid slides against the map, which reads as a
rendering bug rather than a version mismatch. The reverse-Z infinite-far projection has already
changed once inside the window a consumer has been tracking it.

So the division is: RenG hands over the part that moves, and the consumer keeps the part that does
not. A grid also needs a world anchor, and that stays the consumer's, because
`(longitude + 180) / 360` and `512 * 2^zoom` are standard Web Mercator and will outlive any matrix
in this file. Handing over the matrix is not the beginning of handing over the camera.

## One name, and it costs no ABI at all

`Backdrop.Shader` does not change. Not a field, not a constructor, not a line of the KLIB dump --
the uniform is bound by name on whichever stage declares it, exactly as `uResolution` is, and a
shader that declares nothing still draws.

That is ADR 0071's reserved prefix being spent as intended. `rengBackdropInverseViewProjection`
begins with `rengBackdrop`, so it was already refused as a consumer uniform name from the day the
prefix landed; nobody can be holding it, and no validation rule has to change to take it. Reserving
a namespace before the first consumer cost one `startsWith` and is why this record adds a
capability rather than a breaking change.

## What the matrix is, and the space it lands in

`inverse(projectionMatrix * viewMatrix)`, for the frame being drawn, column-major -- the inverse of
exactly the product `composeGeometryViewProjection` already uploads to a geometry.

It lands in **camera-relative logical pixels, on the anchor's east/north/up axes**. Camera-relative
is deliberate and is not a limitation being worked around: absolute Mercator coordinates do not
survive narrowing to `Float`, which is why every vertex this renderer hands a shader is already
relative to the camera.

Depth follows the projection: reverse-Z with an infinite far plane, so window depth is exactly
`near / distance` and the horizon is depth zero rather than depth one. A consumer unprojecting two
depths to build a ray should take two depths that are not both degenerate; unprojecting at zero
alone yields a direction, not a point.

## Under a globe it is the same matrix and a different ground

This is the question worth answering precisely rather than waving at, because the tempting answers
are both wrong.

"Mercator-only" is wrong. Both modes call the same `cameraViewMatrix` and the same
`cameraProjectionMatrix` -- the globe deliberately shares the projection unchanged, because a metric
frame would destroy the depth buffer -- so the product inverts identically and lands in the same
camera-relative logical pixels.

"It just works" is also wrong, and it is the dangerous one. What differs is not the matrix but the
ground. Under Mercator the ground is the plane `z = 0`. Under a globe it is a sphere of
`radiusLogicalPixels` centred at `(0, 0, -radiusLogicalPixels)` in that same space, whose tangent
plane at the anchor *is* `z = 0`. So a consumer intersecting `z = 0` under a globe gets an answer
that is exact at the anchor and increasingly wrong toward the limb, rather than an error.

The uniform is therefore correct in both modes and the plane intersection is not. Said here, and
said in the KDoc, so a consumer can choose the tangent-plane approximation knowingly or refuse to
draw -- which is a decision they are in a position to make and this renderer is not.

## The inverse is general, not derived by hand

This projection inverts in closed form. Its bottom row is `[0, 0, -1, 0]` and the whole matrix is
sparse enough to write the inverse out in four lines, which is faster than any general routine and
exactly as correct -- today.

It is not what this adds, and the reason is the same reason the uniform exists at all. The point of
handing a consumer this matrix is that RenG absorbs projection changes on their behalf; a
hand-derived inverse would absorb them by silently returning the wrong answer the next time the
projection moves. `DoubleMatrix4.inverse()` is Gauss-Jordan with partial pivoting and tracks
whatever the matrix becomes. `inverseAffine` stays where it is, because a view matrix really is
affine and a projection really is not -- feeding this product to it would have inverted a matrix
whose bottom row it assumes.

It returns `null` on a singular matrix, as `inverseAffine` does, and the backdrop then binds no
uniform: a frame whose camera cannot be inverted is not a frame worth failing over, and a shader
declaring the name simply receives nothing, which is the same thing ADR 0008 already says happens
to a name RenG does not set.
