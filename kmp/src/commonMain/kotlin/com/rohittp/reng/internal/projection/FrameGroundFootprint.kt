package com.rohittp.reng.internal.projection

/**
 * Which ground a frame's camera can see, in whichever shape its projection mode expresses that.
 *
 * The two implementations are not two parameterisations of one algorithm and are deliberately not
 * made to look like one: [ClosedMercatorFootprint] is a clipped polygon on the Mercator plane and
 * [GlobeGroundFootprint] is an intersection of half-spaces on a sphere, which is the whole content
 * of Cycle G task 5. Nothing consumes this interface's members, because it has none — a frame's
 * footprint is used by exactly one caller, the tile selector for its own mode, and that caller holds
 * the concrete type.
 *
 * What it exists for is `internal.planning.FrameSpatialPlan`, which carries the footprint the frame
 * actually derived so that its "a selected tile set came from a real footprint" invariant can be
 * stated once for both modes rather than twice. It is `sealed` so a third projection cannot slip a
 * footprint into a plan without every site that has to decide seeing a compile error.
 */
internal sealed interface FrameGroundFootprint
