# Cull the far hemisphere rather than depth-testing it

A globe hides half of itself, and every renderer that draws one has to decide how. **RenG removes the far
hemisphere geometrically: back-face culling for the ground, and a horizon test on the CPU for placements,
where placements already resolve. No draw starts writing depth, so ADR 0027 stands untouched and ADR 0030's
single exception for the model pass is unchanged.**

**Why culling is enough for the ground.** Ground patches on a sphere have consistent winding, so the far
hemisphere is not merely *usually* back-facing — it is exactly the back-facing set, by construction. The
limb is where the two sets meet, which is the same place the eye puts it. `drawFrame` already establishes
`frontFace(GL_CCW)` and `cullFace(GL_BACK)` for the whole scene (`internal/gl/GlFrameDrawer.kt:99-100`), so
the globe ground pass needs an enable and nothing else: no depth involvement, no per-fragment work, no new
uniform, and no state RenG was not already touching.

**The horizon test for placements is a different test, and it is not the sign of `w`.** A **Placement** is
an anchor rather than a patch with a winding, so culling cannot reach it; the test runs on the CPU where
`resolvePlacement` already resolves that anchor, which is where every other spatial decision in RenG happens
already. The quantity is a plane test against the limb — a point is visible exactly when it lies on the
camera's side of the plane through the horizon circle, which is a dot product and a comparison per
placement, needs no shader change, and is the same test the prior-art survey recommended. **What it is not
is `ScreenProjection`'s `w`, and the two must not be confused**, because both this cycle's specification and
its plan describe the horizon test as coming "from the sign of `w` that `ScreenProjection` already
computes". `w` is documented as *"exactly `-z_view`: the distance in front of the camera plane, in logical
pixels"* (`internal/projection/ScreenProjection.kt:35-38`), and a camera outside the sphere looking at it
has the entire planet in front of it — the far hemisphere included. An antipodal placement has a large
*positive* `w`. That field stays exactly what E-labels built it to be, the behind-near-plane guard every
placement path still needs, and it answers a question this ADR is not asking.

**The web renderers' trick is unavailable, and its unavailability is a ruling rather than a limitation.**
MapLibre and Mapbox both clip the far side by overwriting `gl_Position.z` with a distance from a plane
through the horizon, and letting the clipping hardware do the work — *"Since MapLibre uses the Z-buffer for
optimizing transparency drawing, filling it with custom values, we cannot use it for this purpose. Instead,
we compute a plane that intersects the horizons, and for each vertex we compute the distance from this plane
and store it in `gl_Position.z`"* (`developer-guides/globe.md`). They can spend `z` that way because their
depth buffer holds no perspective depth at all: `getDepthModeForSublayer` allocates each layer a constant
`glDepthRange` slice, `depth = 1 - ((1 + currentLayer) * numSublayers + n) * depthEpsilon`
(`src/render/painter.ts:496-500`). RenG's depth is real: reverse-Z perspective depth against a
one-logical-pixel near plane, tested by every map-regime pass and *written* by the model pass under ADR
0030. Hijacking `z` would delete the one thing ADR 0030 exists to provide.

**It has also broken twice in the field, on exactly the hardware RenG has never measured.** The trick does
not work on some Android devices — the far hemisphere bleeds through — and MapLibre's own guide names the
cause and the workaround: *"this approach does not work on some phones due to what is likely a driver bug,
which applies `glDepthRange` and clipping in the wrong order. So additionally, face culling is used for fill
and raster layers … and line layers … discard beyond-horizon pixels in the fragment shader."*
[#6047](https://github.com/maplibre/maplibre-gl-js/issues/6047) is still open and carries a device matrix:
Galaxy S24 and S22 correct, Galaxy S21, Galaxy S20 and Pixel 9 incorrect, in both Chrome and Firefox and
identical whether WebGL 1 or WebGL 2 is forced. The escape route from that bug leads straight into
[#6698](https://github.com/maplibre/maplibre-gl-js/issues/6698), also open, where a 3D custom layer
disappears around zoom 11 because the hijacked `z` leaves everything on the earth's surface at a clip depth
near 1 — which is why MapLibre ships two projection entry points, `projectTile` and `projectTileFor3D`,
and lets consumers discover the seam between them. Two things follow for RenG. ADR 0033 records that RenG's
only mobile GPU evidence is Adreno and Apple, so adopting a technique whose known failures are on
unmeasured Android drivers would be betting the ground on hardware nobody here has run. And MapLibre's own
fallback for the phones where the trick fails **is face culling** — this ADR takes the fallback and skips
the trick.

**Depth-writing was rejected rather than merely unavailable, and this is the alternative a future reader
will reach for.** CesiumJS has neither problem because its globe writes depth: `GlobeSurfaceTileProvider`'s
render state is `cull: {enabled: true}`, `depthTest: {enabled: true, func: LESS}`, over a block commented
literally `// Write color and depth`
(`packages/engine/Source/Scene/GlobeSurfaceTileProvider.js:470-492`). A Cesium billboard on the far side is
occluded by the planet the ordinary way, and a model on the far side is too. That is a real, shipping,
coherent answer, and RenG cannot take it, because ADR 0027 removed depth writing from the whole map regime
for two measured reasons that a sphere makes worse rather than better. Near-ties z-fight where exact ties do
not: ADR 0027 measured one coplanar `Geometry`'s pixel count across fifteen consecutive frames at 3609,
7026, 9468, 1257, 25, 0, 8607, 0, 6501, 2475, 4502, 8764, 8064, 3310, 12163 — twice erased outright, never
twice the same. And a map-anchored billboard is bisected by any surface that writes depth beneath it: a
41×40 pixel diamond rendered as 39×19. A depth-writing globe ground resurrects both for every altitude-0
thing standing on the planet.

**A globe makes the second defect strictly worse, which is the part worth writing down.** Under mercator the
ground is a plane, and there is exactly one camera — pitch 0 — at which every ground pixel carries the
billboard's own depth. That camera is why 942 tests passed over a defect that a video found immediately, and
ADR 0025 had recorded its bit-identical depth as evidence *for* the decision. On a sphere no such camera
exists: the surface curves away from the sub-camera point in every direction, at every pitch, so there is no
configuration where a screen-parallel quad and the ground beneath it agree. Taking Cesium's answer would
reintroduce ADR 0027's defect with the one case that used to hide it removed — a defect that is always
visible is better than one that hides, but it is still the defect ADR 0027 was written to delete.

**The accepted gap: content that straddles the limb is handled wrongly, and no fix is designed.** A
placement resolves to a single anchor, so the horizon test is one bit for a whole object. A tall model just
beyond the horizon is culled entirely rather than having its top drawn, and a model just inside it is drawn
entirely, including the parts the planet should hide. A `Geometry` spanning the limb has the same problem at
its own scale. This is accepted because every fix for it is one of the two things already rejected: real
per-fragment occlusion needs the ground to write depth, and per-vertex clipping is the `gl_Position.z`
hijack. A third option — discarding beyond-horizon fragments in the fragment shader, which MapLibre does for
its line layers — is available to RenG's *own* shaders and not to a `Geometry`'s, since ADR 0008 forbids
RenG adding anything to consumer source but the version directive. So a future closure is a deliberate
amendment to ADR 0027 in the shape ADR 0030 already used, not an oversight to be patched. A reader who finds
a clipped model at the limb should find this paragraph rather than rediscover it.

**One correction to the cycle's design specification, because it is load-bearing for whoever implements
this.** The spec records the cost as *"enabling face culling adds an entry to the GL Restore Set that ADR
0023 governs. RenG does not currently enable it."* Both halves are false on this tree. The Restore Set has
covered cull enable, mode and winding since ADR 0006 — ADR 0023 corrected the set without narrowing that
part, `CONTEXT.md`'s **Restore Set** entry says "cull enable, mode and winding", and
`internal/gl/GlStateSnapshot.kt:60-62,150-152,247-249` captures and restores all three. And RenG already
enables `GL_CULL_FACE`: `drawModels` disables it on entry and the per-primitive state enables it for every
material that is not `doubleSided` (`internal/gl/ModelPipeline.kt:625,668-671`), which is how ADR 0030's
mesh gets its back faces removed. **Nothing is added to the Restore Set by this ADR, and nothing new is
saved or restored.**

**What is genuinely new is ownership, and it follows ADR 0027's rule rather than inventing one.** ADR 0027
requires each pass to establish the state it depends on instead of inheriting it, which is why every pass
sets its own `depthMask(false)`. Culling has never been held to that rule: `drawGround` establishes program,
vertex array, blend, texture unit, depth test and depth mask and says nothing about culling
(`internal/gl/GroundPipeline.kt:175-185`), so it inherits whatever the caller left enabled — harmless today
only because `GROUND_QUAD` already winds counter-clockwise under `drawFrame`'s `GL_CCW`, which is luck the
globe should not depend on. **The ground pass therefore sets its cull state explicitly in both modes:
enabled on a globe, disabled under mercator.** Disabling rather than enabling under mercator is deliberate —
it keeps a mercator frame's pixels exactly where they are, so this cycle cannot move the projection it is
not changing.

**The rule stops at the ground, and one boundary matters enough to state.** The geometry pass must not
inherit the ground's enable. `drawGeometry` runs a consumer's own shader pair, and the winding of the
triangles that shader emits is the consumer's to decide: a shader that negates a coordinate or reorders its
corners inverts the front face, and culling would then delete a legal `Geometry` whose only sin is a sign,
silently and only in one projection mode. That is precisely the class of silent breakage ADR 0008 exists to
prevent, so the geometry pass's cull disable belongs beside its depth state in `SceneContent`, where the
same reasoning already put the depth calls that `drawGeometry` cannot make for itself. The label and icon
passes already disable culling for themselves and say why (`internal/gl/LabelPipeline.kt:363`,
`internal/gl/IconPipeline.kt:334`); the sticker pass does not, and inherits whatever the model pass's last
primitive left — a gap that predates this cycle and that the rule above is the general answer to.

Verification is analytical readback, matching Cycle E's gate and this cycle's: the far hemisphere is not
drawn, an antipodal placement produces no pixels, and the limb falls where the geometry says it does.
Curvature fidelity is not claimed here and stays Cycle J's.
