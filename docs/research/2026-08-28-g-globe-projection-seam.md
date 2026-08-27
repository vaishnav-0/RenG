# G: RenG's own projection seam, and what a second mode costs

Research only. No Kotlin was changed, no branch was cut, nothing was committed. Every claim carries a
`file:line` citation against `main` at `f997e1a1abd3d5ff3d546c458645ddbae95789d8` — the merge of Cycle H,
with F-2 already in. Where I could not settle something, §10 says so and says what would settle it.

Scope: **RenG's own source only.** External prior art and Rentile's tile surface are two sibling
documents and are not touched here.

---

## Summary answer

**One. The refusal is one line; the thing behind it is not one function.** `ProjectionMode.GLOBE` is
already public, already canonically encoded with wire value 2, and already refused — at exactly one place,
`FramePlanningCore.plan`'s opening `when`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/FramePlanningCore.kt:169-171`). It is a
genuine fail-closed gate, not an inert field of the `maximumResidentGpuTextureBytes` kind: it fires before
spatial planning, before tile budgeting and before any resource route is derived, and two tests pin exactly
that ordering (`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/FramePlanningCoreTest.kt:46-90`).
But `ResolvedMercatorCamera` — the type that lands the moment that `when` falls through — is a concrete
`data class` (`internal/projection/CameraMatrices.kt:16-27`) threaded through eight files and roughly
twenty signatures, and mercator is baked into the **shape** of two algorithms rather than merely into their
arithmetic. `clippedPhysicalPixelFootprint` scans column 0 for its admissible row band and then casts four
corner rays to `GroundRayResult.Hit` unchecked (`internal/projection/MercatorGroundFootprint.kt:33-63`);
that is only sound because a ray's hit/miss verdict on a horizontal plane depends on the screen *row*
alone — `q = cosinePitch - v * sinePitch` with `v` derived from `screenY` only
(`internal/projection/CameraMatrices.kt:114-122`). On a sphere the visible ground is bounded by the limb,
which is a curve in both screen axes, so that cast throws and the four-corner rectangle is not a
conservative cover. Substituting a projection function is not enough; the footprint and tile-selection
algorithms are the work.

**Two. ADR 0008 survives a globe, and the reason it survives is that subdividing a `Geometry` is
output-neutral under mercator.** A `Geometry` is drawn today as exactly four vertices,
`drawArrays(GL_TRIANGLE_STRIP, 0, 4)` (`internal/gl/GeometryPipeline.kt:308`), and a four-corner chord
across a sphere sinks below the surface by a sagitta that is **27.8 logical pixels at zoom 4 and 163 at
zoom 0** for a full-frame quad (computed below, §5.4). So the globe needs subdivision the consumer never
asked for. The good news is that a `Geometry`'s four mercator corners are always **coplanar** — two
distinct mercator `x`, two distinct mercator `y`, and an altitude that depends only on which of the two
latitudes a corner carries (`internal/projection/MercatorProjection.kt:43-53`,
`internal/planning/GeometryResolver.kt:41-46`) — so subdividing that quad by lerping its four corners
changes no pixel under mercator, up to float rounding. **RenG can therefore subdivide in both modes and
keep one contract**, and a consumer shader that transforms `aPosition` and samples by `aTexCoord` keeps
working untouched. Two things do break, and both are narrow and nameable: a shader reading `gl_VertexID`
(legal GLSL ES 3.00, and ADR 0008 accepts arbitrary consumer source), and the meaning of `aPosition.z`,
which under mercator is purely altitude and under a globe becomes altitude minus curvature. The cost of
the *other* answer — a new uniform telling the shader which mode it is in — is not the silent-rename
hazard ADR 0024 warns about but a **loud** one: `RESERVED_SHADER_NAMES` is enforced at `Geometry`
construction (`kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt:275-280`), so growing it turns a
previously legal `Geometry` into an `IllegalArgumentException`.

**Three. Half the globe's mathematics is already in the tree, already exercised, and used today for one
narrow purpose.** `wgs84LocalFrame` (`internal/projection/MercatorProjection.kt:78-106`) computes a real
WGS84 ECEF position and an east/north/up basis on the ellipsoid, with flattening and prime-vertical radius,
and folds the world copy away first (`:150-151`). Its only production caller is the map-anchored rotation
path (`internal/planning/PlacementResolver.kt:104-106`) and the world-anchored light
(`internal/gl/SceneLight.kt:87-97`) — nothing in RenG has ever used it to *place* anything. It is exactly
the primitive a globe needs. Alongside it, the depth scheme survives a globe on one condition: window depth
under RenG's reverse-Z projection is exactly `near / distance` with `near` fixed at **one logical pixel**
(`internal/projection/CameraMatrices.kt:141`), into a 24-bit **fixed-point** buffer
(`internal/gl/OffscreenSurface.kt:19, 58`). Expressed in logical pixels, a globe's resolvable depth step at
the camera's own distance is 0.025 logical pixels and everything is fine. Expressed in ECEF metres with a
one-metre near plane, the step at earth radius is **2.4 million metres**. The unit system is the decision.

---

## 1. What `projectionMode` is today

### 1.1 The enum, and its public shape

```kotlin
public enum class ProjectionMode {
    MERCATOR,
    GLOBE,
}
```

`kmp/src/commonMain/kotlin/com/rohittp/reng/FramePlan.kt:5-8`. Two constants, `GLOBE` second. It has been
public since Cycle B — the Cycle B design already writes it out verbatim
(`docs/superpowers/specs/2026-08-17-cycle-b-public-api-pure-core-design.md:39`) and already rules that
"`GLOBE` remains a valid public value but Cycle B planning fails `UNSUPPORTED_PROJECTION_MODE` before
resource work; Globe implementation belongs to Cycle G" (`:664-665`).

The ABI dump:

```
kmp/api/kmp.klib.api:72  final enum class com.rohittp.reng/ProjectionMode : kotlin/Enum<com.rohittp.reng/ProjectionMode> {
kmp/api/kmp.klib.api:73      enum entry GLOBE
kmp/api/kmp.klib.api:74      enum entry MERCATOR
```

plus `entries`/`valueOf`/`values` at `:76-80`, the `FramePlan` constructor parameter at `:449`, and the
`projectionMode` getter at `:461-462`. **`FramePlan`'s parameter carries a default**
(`FramePlan.kt:13`, `projectionMode: ProjectionMode = ProjectionMode.MERCATOR`), so a consumer who never
mentions the enum is on mercator and stays there.

### 1.2 Every place the value is read

Exhaustively, from a whole-tree grep for `ProjectionMode|projectionMode|GLOBE` across `*.kt` and `*.api`:

| Site | What it does |
|---|---|
| `FramePlan.kt:13, 21, 36, 43` | constructor default, property, pass-through |
| `FramePlan.kt:54, 63` | `equals` / `hashCode` — a globe plan is a different plan |
| `internal/identity/FramePlanCanonicalEncoding.kt:59, 240-243` | `CanonicalBinary.u16(plan.projectionMode.wireValue)`; `MERCATOR -> 1`, `GLOBE -> 2` |
| `internal/planning/FramePlanningCore.kt:169-171` | **the only branch on the value anywhere**: `GLOBE -> return unsupportedProjectionModeFailure()` |
| `internal/planning/FramePlanningCore.kt:289-298` | that failure: `UNSUPPORTED_PROJECTION_MODE` at `FRAME_PLANNING`, `fieldName=projectionMode` |
| `internal/DiagnosticFactories.kt:17, 275-279` | the diagnostic field name, and the closed rule binding this code to that stage and that one field |
| `consumer-smoke/.../ConsumerProof.kt:158-159, 371` | the published-coordinate proof references both constants and reads the property |

So the answer to "is the second constant already declared, and does anything branch on it" is: **yes, and
exactly one thing branches on it, and that branch is a refusal.** It is not inert in the way
`ResourceLimits.maximumResidentGpuTextureBytes` was inert for most of E-basemap — that field was
constructed away and had no effect at all; this one changes behaviour the moment it is set, and the change
is a typed failure before any work happens.

### 1.3 What the refusal is worth, precisely

Three properties are already pinned by tests, and G inherits all three as things it must not silently lose:

- **It fires before spatial planning and before any resource route.**
  `FramePlanningCoreTest.globeProjectionModeFailsBeforeSpatialPlanningAndAnyResourceRoute`
  (`:46-59`) asserts the code and field; the sibling at `:75-81` submits a globe plan that is *also* over
  the tile budget and requires `UNSUPPORTED_PROJECTION_MODE` rather than `RESOURCE_LIMIT_EXCEEDED`, which
  is what makes "before" mean something.
- **A globe plan in a batch fails the batch** — `FramePlanningCoreTest.kt:533`.
- **A globe plan hashes differently** — `FramePlanCanonicalEncodingTest.kt:67` uses the `PROJECTION_MODE`
  segment with a `GLOBE` plan to prove the field reaches the canonical bytes, and
  `CycleBPureCoreContractTest.kt:399` covers the same at the contract level.

The diagnostic rule is closed: `UNSUPPORTED_PROJECTION_MODE` may only ever be raised at `FRAME_PLANNING`
with exactly `{projectionMode}` as its context (`internal/DiagnosticFactories.kt:275-279`). That matters
for §7 — a globe that ships *partially* cannot reuse this code for a sub-case at a later stage.

---

## 2. The projection seam

### 2.1 `internal/projection/` in full — three files, 540 lines

| Entry point | Takes | Returns | Called by |
|---|---|---|---|
| `projectMercator` (`MercatorProjection.kt:43`) | `GeographicPosition(lat, unwrappedLon, altMetres)` | `MercatorPosition(x, y, z)` — `x` in world copies, `y` in `[0,1]`, `z` altitude in world-circumference units scaled by `1/cos φ` | `validateMercatorPosition` (same file, `:129`) — nothing else |
| `mercatorCopyIndex` (`:55`) | unwrapped longitude | `floor((lon + 180) / 360)` | `validateMercatorPosition:120`, `canonicalWgs84LongitudeDegrees:151` |
| `isWithinMercatorPlanningSupport` (`:58`) | `(x, y)` | `Boolean`, `x ∈ [-16384, 16385]`, `y ∈ [0,1]` | `validateMercatorPosition:130`, `MercatorGroundFootprint.clipPoint:66` |
| `validateMercatorCamera` / `validateMercatorMapPosition` / `validateMercatorGeometryPosition` (`:66/:72/:75`) | a `GeographicPosition` | `SpatialOutcome<MercatorPosition>` — success or a typed `INVALID_VALUE` naming a specific field | `resolveMercatorCamera:50`, `PlacementResolver:52`, `GeometryResolver:50` |
| `wgs84LocalFrame` (`:78`) | a `GeographicPosition` | `Wgs84LocalFrame(ecefPosition, basisEastNorthUp)` on the real ellipsoid | `PlacementResolver:104-105` only (plus tests) |
| `resolveMercatorCamera` (`CameraMatrices.kt:41`) | `Camera`, `OutputPixelSize` | `SpatialOutcome<ResolvedMercatorCamera>` — anchor, world size, three basis vectors, camera distance, view matrix, projection matrix, geographic ground anchor | `MercatorSpatialPlanner:139`, `RenGRenderer:1500-1501` (draw-time re-resolution) |
| `physicalPixelGroundRay` (`CameraMatrices.kt:101`) | resolved camera, integer pixel | `GroundRayResult.Hit(point, q, t)` / `HorizonOrSky` / `NearClipped` | `MercatorGroundFootprint:36, 63` |
| `clippedPhysicalPixelFootprint` (`MercatorGroundFootprint.kt:30`) | resolved camera | `ClosedMercatorFootprint` — `Empty` / `Point` / `Segment` / `Polygon` in mercator `(x, y)` | `MercatorSpatialPlanner:147` |

### 2.2 Is there one place a globe would be substituted?

**Substituting `projectMercator` is not enough, and there are two distinct reasons why.**

*Reason one: `ResolvedMercatorCamera` is a concrete type, not an interface.* It is a
`data class` (`CameraMatrices.kt:16-27`) and appears in the signatures of `physicalPixelGroundRay`,
`clippedPhysicalPixelFootprint`, `resolvePlacement`, `resolveCameraRelativeMapPosition`, `resolveGeometry`,
`resolveBasemapTileQuad`, `SceneContent`'s constructor, `resolveModel`, `composeGeometryViewProjection`,
`composeGroundModelViewProjection`, `composeMapModelViewProjection`, `composeMapCameraSpaceModel` and
`sceneLightDirectionCameraSpace` — every one of them named in the grep at `internal/gl/SceneContent.kt:292,
488, 608, 627, 662, 683`, `internal/gl/SceneLight.kt:87`, `internal/planning/BasemapTileQuad.kt:46`,
`GeometryResolver.kt:39`, `PlacementResolver.kt:38, 132`, `MercatorSpatialPlanner.kt:211`. It is also a
field of `MercatorSpatialPlan` (`MercatorSpatialPlanner.kt:34`) which is a field of `PlannedFrameCore`
(`FramePlanningCore.kt:106`), so the draw side receives it structurally. Several of these are genuinely
projection-agnostic once given a camera (the light basis, the screen projection); several are not.

*Reason two, and the sharper one: two algorithms are mercator-shaped, not merely mercator-parameterised.*

**`clippedPhysicalPixelFootprint` (`MercatorGroundFootprint.kt:30-56`).** It finds the first and last
admissible screen rows by tracing rays down **column 0 only** (`:35-40`), then takes ground hits at the
four corners of that row band with an **unchecked cast** to `GroundRayResult.Hit` (`:58-63`). That is sound
under mercator and only under mercator: `physicalPixelGroundRay` computes
`q = cosinePitch - v * sinePitch` where `v = eta / FOCAL_LENGTH_SCALE` and `eta = 1 - 2*screenY/height`
(`CameraMatrices.kt:114-122`), so the horizon/sky and near-clip verdicts depend on the screen **row** and
nothing else. A horizontal plane has that property; a sphere does not. On a globe, whether a ray hits
depends on both screen axes — a corner of the row band can look past the limb into space while the middle
of the same row hits — so `groundHit` throws a `ClassCastException` and the four-corner rectangle stops
being a conservative cover of the visible ground. The whole `ClosedMercatorFootprint` vocabulary
(`Empty`/`Point`/`Segment`/`Polygon`, `:6-28`) plus its Sutherland–Hodgman clipping against
`x ∈ [-16384, 16385]`, `y ∈ [0, 1]` (`:103-163, 218-221`) is a mercator-plane object. A sphere's visible
ground is a spherical cap intersected with a frustum; it has no representation in this vocabulary.

**`selectBasemapTiles` (`BasemapTileSelector.kt:46-114`).** It scales the footprint by `2^lod`, walks
candidate rows, and projects each row strip onto an `x` interval (`:164-223`). Everything about it —
"rows", "an x interval per row", `unwrappedX`, `canonicalX = unwrappedX - copy * tileCount` — is the
mercator grid's own topology. It also assumes tiles are **square** and uniformly scaled, stated outright in
`BasemapTileQuad`'s KDoc: "a square in both axes because a Mercator tile *is* square in Mercator space: one
tile spans `1 / 2^lod` in each axis, and both axes scale by the same `worldSizeLogicalPixels`"
(`BasemapTileQuad.kt:11-15`). On a globe a tile is a curved patch whose screen size varies across its own
extent.

**`observeMercatorLod` (`MercatorLod.kt:46-70`).** Its whole argument is that
`screenPixelsPerTexel = 2^(zoom - selectedLod)` is one number for the frame (`:16-20`). That is true because
mercator's scale is uniform across the plane. On a globe the ratio varies from its value at the sub-camera
point to near-zero at the limb, so one global LOD is either wasteful in the middle or blurry at the edge.
Two hysteresis constants, both calibrated on measured road-edge widths from the visual harness (`:22-27`),
are calibrated for the uniform case.

### 2.3 Lat/lon → world conversions found outside `internal/projection/`

A whole-tree grep for `latitude|longitude` outside that package finds **exactly two arithmetic sites** and
the rest is validation, naming and diagnostics:

1. **`PlacementResolver.kt:110-114`.**
   ```kotlin
   AnchoringMode.MAP -> placement.scale * camera.worldSizeLogicalPixels /
       (WORLD_CIRCUMFERENCE_METRES * cos(geographicAnchor.latitude.degreesToRadians()))
   ```
   This converts CONTEXT.md's "map-anchored scale is metres per local unit" into logical pixels, and the
   `cos(latitude)` is **exactly mercator's scale-distortion factor**. On a globe there is no scale
   distortion, so the correct globe conversion drops the cosine entirely. **Copying this line into a globe
   path makes every map-scaled object too large by `1/cos φ` — 2× at latitude 60, 5.8× at latitude 80** —
   and it would be silently wrong, because at the equator the two formulas agree exactly. Latitude 0 is a
   symmetry point of this line; see §9.
2. **`GeometryResolver.kt:52-53`** and **`PlacementResolver.kt:48-49`** unpack `Vector3(x, y, z)` into
   `GeographicPosition(latitude, unwrappedLongitude, altitudeMetres)`. That is a naming convention, not a
   conversion, but it is where the public `Vector3` acquires geographic meaning and G touches both.

Everything else is a boundary check (`SpatialValues.kt:52`, `DrawnThings.kt:270-274`), a diagnostic field
name (`internal/DiagnosticFactories.kt:18-29`), or the canonical encoding of the raw doubles
(`FramePlanCanonicalEncoding.kt:80-81`). **The conversion itself really is contained.** It is the two
algorithms above that are not.

### 2.4 One more mercator assumption, at the draw seam

`composeGroundModelViewProjection` (`SceneContent.kt:626-639`) builds a ground tile's model matrix in
**map** space and applies the view matrix after it, deliberately unlike every other pass, because "a ground
tile lies flat on the map whatever the camera's pitch, so its local axes are map axes" (`:614-619`). That
sentence is a mercator statement. On a globe a tile's local axes are the ENU frame at the tile's own
centre, and the model matrix becomes `ENU(tileCentre)` rather than a translate-and-scale on `z = 0`.
`wgs84LocalFrame` already produces exactly that basis.

---

## 3. What each drawn thing assumes is flat

Walking ADR 0030's order: ground, geometries, models, map-anchored stickers.

### 3.1 The ground — the deepest assumption, and it is in the shader

The ground is **one quad per tile instance, four vertices, no subdivision, and no `z` attribute at all**:

```glsl
layout(location = 0) in vec2 rengGroundPosition;
...
gl_Position = rengGroundModelViewProjection * vec4(rengGroundPosition, 0.0, 1.0);
```

`internal/gl/GroundPipeline.kt:26, 32`. The vertex buffer is `GROUND_QUAD`, four `(x, y, u, v)` records of a
unit square (`:63-68`), and the draw is `drawArrays(GL_TRIANGLE_STRIP, 0, 4)` (`:197`). **The attribute is
`vec2` and the third component is a literal `0.0`, so the ground shader cannot express a curved tile even
if the model matrix could put it somewhere useful.**

A globe therefore changes:
- the attribute from `vec2` to `vec3`, and the buffer from four vertices to an `N × N` grid of positions on
  the sphere;
- the model matrix from `Translate(centre) * Scale(side, side, 1)` on `z = 0`
  (`SceneContent.kt:630-637`) to a per-tile ENU frame;
- the tile *count* per frame, since a globe shows a hemisphere at low zoom where mercator shows a trapezoid;
- and the LOD story, per §2.2.

The `v = 0` at `+y` convention (`GroundPipeline.kt:52-58`) — "row zero of a rendered basemap tile is its
**north** edge" — survives unchanged, since a subdivided patch still has a north edge. The comment there is
worth reading before touching the quad: flipping `v` "is completely invisible on a solid-coloured tile and
destroys a real map".

### 3.2 Geometries — a four-corner chord across a sphere

`resolveGeometry` (`GeometryResolver.kt:37-76`) builds exactly four `GeographicPosition`s from
`topLeft`/`bottomRight`, validates each, and resolves each to camera-relative logical pixels.
`ResolvedGeometry` requires exactly four corners (`:22`), `cornersToFloatArray` narrows exactly four
(`SceneContent.kt:579-588`), `drawGeometry` requires exactly twelve floats (`GeometryPipeline.kt:243-245`),
and the draw is a four-vertex strip (`:308`). The count is asserted in four places; it is a contract, not
an accident.

Under a globe those four corners are still on the sphere, but the two triangles between them are chords —
they pass **through** the globe. §5.4 computes the sagitta. This is §4's subject.

### 3.3 Models — the one path that is already nearly right

A map-anchored model resolves to a single anchor point and a direction transform:

```kotlin
val viewSpaceAnchor = camera.viewMatrix.transformAffinePoint(placement.logicalPosition)
return affineModelMatrix(rotation = placement.directionTransform, scale = ..., translation = viewSpaceAnchor)
```

`SceneContent.kt:690-695`. And `placement.directionTransform` for a `MAP` rotation is already
`viewBasis * cameraENUᵀ * anchorENU * localRotation` (`PlacementResolver.kt:96-107`) — **a real local
tangent frame at the anchor's own geographic position, built from `wgs84LocalFrame`.** That is exactly what
a globe needs, and it is already there, already tested (`PlacementResolverTest.kt:86-89, 156-159, 233`).
What changes for a globe is only `logicalPosition` — the anchor's camera-relative 3D offset — plus the
scale factor of §2.3. A model is a rigid body at a point; a sphere does not curve a point.

Two caveats. `sceneLightDirectionCameraSpace` (`SceneLight.kt:87-97`) expresses ADR 0026's world-anchored
light in the **camera anchor's** ENU frame, so under a globe two models a thousand kilometres apart are lit
from directions that differ by that much of the earth's curvature while sharing one camera-space light
vector. Under mercator that is exact by construction; under a globe it is an approximation whose error
grows with visible extent, and it is worth deciding rather than inheriting. Second, `wgs84LocalFrame`'s ENU
basis at a pole is degenerate in longitude (§9).

### 3.4 Map-anchored stickers — unaffected, except for the thing ADR 0030 already accepted

A sticker is a centred unit quad scaled by its image dimensions and placed by the same
`composeMapModelViewProjection` a model uses (`SceneContent.kt:433-439, 661-666`). Like a model, it is a
rigid quad at a point. The billboard case — `MAP` position with `SCREEN` rotation — is exactly a
screen-parallel quad, which is projection-independent. Nothing about the sphere touches this path.

What *does* change is the surrounding depth argument. ADR 0027 removed depth writes from the whole map
regime because flat map-plane content near-tied with itself; ADR 0030 gave the model pass its writes back.
A globe's ground is no longer flat, and a coplanar altitude-0 `Geometry` is no longer coplanar with
anything — the near-tie that ADR 0027 measured (a quad "losing up to 100% of its pixels between consecutive
frames") stops being a *tie* and becomes a genuine depth difference of the sagitta. Whether that helps or
hurts is not obvious and I did not measure it; it is the kind of thing the cycle should measure early
rather than discover.

---

## 4. ADR 0008's consumer shader contract — the centre

### 4.1 What the contract actually is, in code

ADR 0008 (`docs/adr/0008-accept-one-shader-dialect-and-substitute-its-version-directive.md`) fixes six
names and one rule: *"RenG documents a fixed set of attribute and uniform names … and RenG binds them by
name when the compiled program actually declares them. A shader that declares none of them compiles and
draws; a shader that declares one gets it set. RenG never prepends a line to consumer source."*

The six, at `internal/gl/GeometryPipeline.kt:24-42`:

| Name | Type | What it carries today | Citation |
|---|---|---|---|
| `aPosition` | `vec3` | camera-relative logical pixels, per corner | `SceneContent.kt:579-588`, `GeometryPipeline.kt:128-139` |
| `aTexCoord` | `vec2` | `(0,1)`/`(1,1)`/`(0,0)`/`(1,0)`, north at `v=0` | `GeometryPipeline.kt:335-351` |
| `uModelViewProjection` | `mat4` | `projection * view` — a geometry carries no model matrix | `SceneContent.kt:602-609` |
| `uResolution` | `vec2` | output pixel size | `SceneContent.kt:341-342` |
| `uGeometryBounds` | `vec4` | west/south/east/north **degrees**, explicitly informational | `SceneContent.kt:590-600`, `GeometryPipeline.kt:29-41` |
| `uFrameIndex` | `uint` | `frameIndex` narrowed, wrapping at 2³² | `GeometryPipeline.kt:288-290` |

The binding mechanism is the part that decides G's options. A uniform whose name the program does not
declare resolves to a negative location and setting it is a silent GL no-op — `GeometryPipeline` records
whatever `getUniformLocation` returned at creation (`:161-164`) and `drawGeometry` guards every set on
`>= 0` (`:273-290`). An **attribute** is different and the code says so: "enabling a negative vertex attrib
index is a genuine GL error rather than a harmless no-op, so the guard here is load-bearing for
correctness" (`:124-127`).

Two further facts that bear directly:

- **The six names are a closed reserved set enforced at construction.** `Geometry`'s `init` rejects a
  consumer `uniforms` or `textures` key that collides with any of them (`DrawnThings.kt:275-280`).
- **ADR 0024 records the rename hazard**: renaming one of the six is *silent*, because a consumer shader
  keeps compiling and keeps drawing with that value never set again.

### 4.2 Does a consumer shader written for mercator still mean anything under a globe?

**Mostly yes, and the exceptions are enumerable.**

- `uResolution`, `uFrameIndex` — unchanged. Neither is geographic.
- `uGeometryBounds` — **unchanged, and this is the one uniform the globe leaves entirely alone**, because
  it carries absolute degrees straight from `Geometry`'s own construction-validated corners
  (`SceneContent.kt:595-600`) and degrees are degrees in either projection. Its KDoc already forbids
  deriving a vertex position from it, for reasons (Float precision, mercator latitude not being linear in
  screen space) that are *more* true under a globe, not less.
- `aTexCoord` — unchanged in range and in meaning: `0..1` across the geometry, north at `v = 0`. Subdivision
  preserves that provided RenG assigns the interior values bilinearly.
- `uModelViewProjection` — unchanged in *role*. It is `projection * view` and a geometry has no model matrix
  because its corners arrive camera-relative (`SceneContent.kt:602-609`). That stays true on a globe.
- **`aPosition` — the one that changes meaning.** Its *type* and *units* are unchanged (a `vec3` of
  camera-relative logical pixels), and a shader whose vertex stage is
  `gl_Position = uModelViewProjection * vec4(aPosition, 1.0)` is completely unaffected. But under mercator
  the components are exactly `(east, north, up)` offsets with `up` being **altitude alone**
  (`PlacementResolver.kt:137-141`, where `z = projected.z * worldSizeLogicalPixels` and
  `projected.z = altitudeMetres / (circumference * cos φ)`). Under a globe, a point 270 logical pixels north
  of the camera anchor at altitude 0 has a **negative** `z` — the sphere curves away from the tangent plane.
  So `aPosition.z` stops being "altitude in logical pixels" and becomes "altitude minus curvature". A shader
  that extrudes by `aPosition.z`, thresholds on it, or colours by it draws something different. That is a
  real semantic break in a documented attribute, and it is the sharpest one in the cycle.

### 4.3 Does the quad need subdivision the consumer never asked for?

Yes, and the magnitude is not marginal. §5.4's table: for a geometry spanning the visible frame, the chord
sags below the sphere by **163 logical pixels at zoom 0, 27.9 at zoom 4, 1.75 at zoom 8, and 0.44 at zoom
10**. Below about zoom 10 it is sub-pixel and nobody would see it; above zoom 5 it is a gross misplacement.
And because ADR 0027 leaves the ground and geometries writing no depth, the failure is not disappearance —
the quad still paints over the ground — it is that **the quad's interior lands where the chord is rather
than where the geography is**, so its texture slides off the map by up to the sagitta. Silent, and
scale-dependent, which is the worst combination.

### 4.4 If RenG subdivides, does the consumer's shader still work unchanged?

**Under mercator, subdivision is output-neutral, and that is what makes one contract possible.**

A `Geometry`'s four resolved corners are always **coplanar**. `Geometry` requires
`topLeft.x > bottomRight.x` and `topLeft.y < bottomRight.y` (`DrawnThings.kt:272-273`), and
`resolveGeometry` builds the four corners as
`(latN, lonW, zN), (latN, lonE, zN), (latS, lonE, zS), (latS, lonW, zS)`
(`GeometryResolver.kt:41-46`). `projectMercator` makes `x` depend only on longitude and both `y` and `z`
depend only on latitude and altitude (`MercatorProjection.kt:45-51`). So the four points have two distinct
`x`, two distinct `y`, and a `z` that is a function of `y` alone — a planar rectangle in the camera-relative
frame, possibly tilted. Perspective-correct interpolation of a linear function over a plane is exact
regardless of how the plane is triangulated, so **splitting that quad into an `N × N` grid by lerping the
four corners produces the same pixels, up to float rounding.**

That yields a concrete implementation constraint worth writing down before it becomes a defect: **interior
vertices must be produced by lerping the four resolved corners, not by re-projecting intermediate
latitudes.** Mercator `y` is nonlinear in latitude, so a re-projected mid-latitude lands at a different
`y` than the corner midpoint, which changes the quad's shape *and* makes CONTEXT.md's "altitude interpolates
north-to-south" ambiguous between "linear in latitude" and "linear in mercator y". Today the rasteriser
answers that question (linear in mercator `y`), and only corner-lerping preserves the answer.

Two things break under subdivision, and both are narrow:

1. **`gl_VertexID`.** It exists in GLSL ES 3.00, ADR 0008 accepts arbitrary consumer source, and RenG has no
   way to know a shader reads it. A shader that switches on `gl_VertexID` to pick a corner colour, or one
   that draws something only at vertex 0, silently changes. There is no detection and no warning available;
   the only honest response is release notes.
2. **Per-vertex cost.** A consumer's vertex shader runs `N²` times instead of 4. For a heavy vertex stage
   that is a performance change, not a correctness one.

### 4.5 The four answers, and what each costs

**(a) Do not subdivide.** Zero API change, zero doc change, zero shader change. Wrong above roughly zoom 10
for a frame-sized geometry, in a way that is invisible in a test that samples the quad's centre and grows
with the geometry's own span. Cheap and dishonest.

**(b) RenG subdivides, in both modes, with the interface untouched.** *Recommended.* One contract, one code
path, mercator output unchanged (§4.4), and `aPosition`/`aTexCoord`/`uModelViewProjection` keep their
documented types and roles. Costs: `gl_VertexID`; per-vertex cost; a subdivision-density decision RenG has
never had to make; and `drawGeometry`'s buffer path grows from a fixed 80-byte upload
(`GeometryPipeline.kt:266-271, 360`) to a variable one. `GEOMETRY_CORNER_FLOAT_COUNT` and the four `require`s
around it all move.

**(c) Subdivide only under `GLOBE`.** Strictly worse than (b) for the same money. A consumer who tested
under mercator ships a shader that behaves differently under globe, and the difference is exactly the one
they could not have tested for. It also means two vertex-assembly paths where (b) has one.

**(d) Add a uniform so the shader can know.** Something like `uProjectionMode` or a `uTangentBasis`. Adding
a *uniform* is genuinely non-breaking at the GL level — a shader that does not declare it gets a negative
location and nothing is bound (`GeometryPipeline.kt:161-164`), which is ADR 0008's whole point. But
**`RESERVED_SHADER_NAMES` is a closed set enforced at `Geometry` construction**
(`GeometryPipeline.kt:45-52`, `DrawnThings.kt:275-280`), so adding a name makes a previously legal
`Geometry` throw `IllegalArgumentException` for any consumer who happened to use that name for a uniform or
texture of their own. That is a **loud** break rather than ADR 0024's silent one, which is much better —
but it is still a break in a published contract, across three releases, and it is a permanent widening of
the reserved set.

### 4.6 So: can a globe honour ADR 0008?

**Yes — option (b) honours it, and the ADR's own words are what make that true.** ADR 0008 promises a fixed
set of *names* bound when declared, no injected preamble, and one substituted version line. Subdivision
touches none of those. It does not add a name, does not inject text, and does not change a type.

What it does do is change the *values* of one attribute in a way the ADR never contemplated, because the
ADR was written when there was one projection. So the honest answer has three parts:

1. **The mechanism survives.** No new name is needed for the globe to draw correctly.
2. **`aPosition.z`'s documented meaning does not survive**, and `CONTEXT.md`'s Shader Interface entry —
   which today says placement "stays camera-relative and exact through `aPosition` and
   `uModelViewProjection`" — needs an amendment saying what the third component means under each mode. That
   is documentation and an ADR erratum, not ABI.
3. **The vertex count stops being four**, and since that is asserted in four places in RenG's own code and
   is observable to a consumer through `gl_VertexID`, it should be stated in the ADR rather than discovered.

My recommendation is (b) plus an ADR 0008 erratum (the ADR-erratum pattern F-2 used for ADRs 0021 and 0028),
recording exactly those two changes. No new uniform, and therefore no growth of `RESERVED_SHADER_NAMES`.

---

## 5. Numerical precision

### 5.1 Where `Double` lives and where `Float` begins

RenG keeps every spatial quantity in `Double` and crosses to `Float` at a small, enumerable set of sites. A
whole-tree grep for `.toFloat()` in `commonMain` returns twenty-one hits; discounting the model-decode ones
(`internal/model/AccessorReader.kt:313-317`, `ModelDecode.kt:411-419` — those are glTF's own `Float` data,
not RenG's transform), the spatial crossings are:

| Site | What crosses | Magnitude today |
|---|---|---|
| `SceneContent.kt:805-809` `toColumnMajorFloatArray` | every model-view-projection matrix element | translations are camera-relative; the largest is `cameraDistanceLogicalPixels`, `height * (1+√2) / 2` (`CameraMatrices.kt:65`) — 651.8 for a 540-tall output |
| `SceneContent.kt:579-588` `cornersToFloatArray` | a geometry's four camera-relative corners | bounded by `isGpuRepresentable` only (finiteness), but in practice a few hundred logical pixels for anything on screen |
| `SceneContent.kt:595-600` `boundsWestSouthEastNorth` | `uGeometryBounds` — absolute degrees | ±180; deliberately imprecise, and the KDoc says why |
| `SceneLight.kt:96` | the light direction | a unit vector |
| `ModelPipeline.kt:764, 817` | normal matrix, joint matrices | model-local |
| `SceneContent.kt:341-342` | `uResolution` | output pixels |

The guard is `isGpuRepresentable(value) = value.isFinite() && value.toFloat().isFinite()`
(`internal/planning/GpuRepresentability.kt:9`) — **a finiteness check, not a magnitude check.** It is applied
to every camera-relative map position component (`PlacementResolver.kt:142-144`), to the resolved scale
(`:115-117`), and to screen positions (`:70-75`).

The KDoc at `SceneContent.kt:573-577` states the invariant this achieves: the corner narrowing "is the only
place a resolved corner's `Double` components lose precision, and it is a direct per-component `toFloat()`
narrowing — never a recomputation from degrees." `composeGroundModelViewProjection` makes the same claim for
the ground: "no absolute Mercator coordinate is ever narrowed to `Float`" (`:621-624`). Cycle B measured
camera-relative Float error below 0.001 px, cited at `GeometryPipeline.kt:32-35`.

### 5.2 Why the existing pattern generalises

**The structural property that saves RenG today — differencing in `Double` before scaling and narrowing — is
exactly the property a globe needs, and it is already the code's shape.**

`resolveCameraRelativeMapPosition` (`PlacementResolver.kt:130-146`) computes
`(projected.x - camera.mercatorAnchor.x) * worldSizeLogicalPixels` — the subtraction happens first, in
`Double`, and only the small result is scaled. `resolveBasemapTileQuad` goes further and documents why: it
subtracts in *tile* units rather than mercator units, "which keeps both operands at the same small magnitude
near the camera, where `(mercatorCentreX - anchorX) * worldSize` would difference two numbers whose magnitude
is set by the world-copy index (up to 16 384) before scaling up by a world size that reaches 2³¹ logical
pixels at LOD 22. Both forms are correct; this one loses about four orders of magnitude less"
(`BasemapTileQuad.kt:32-36`).

A globe formulated the naive way puts vertices at ECEF ~6.4 × 10⁶ m. Measured: **`Float`'s ulp at 6 378 137
is exactly 0.5 m.** Half-metre quantisation at street zoom is visible jitter, and it is the classic globe
defect. A globe formulated the way RenG already formulates mercator — differences taken in `Double`,
narrowed only after — never sees that number.

The failure mode to watch is the *matrix*, not the vertex. If a globe's model matrix carries a
globe-centre-to-tile translation of ~6.4 × 10⁶, that translation crosses at `SceneContent.kt:808` and the
0.5 m ulp arrives anyway even though every vertex was camera-relative. `composeMapCameraSpaceModel` already
avoids this by carrying the anchor into **view** space before building the model matrix
(`SceneContent.kt:690-695`), so the translation is camera-relative by construction. A globe ground path must
do the same rather than following `composeGroundModelViewProjection`'s map-space form.

### 5.3 Units: the decision that settles both precision questions

`worldSizeLogicalPixels = 512 * 2^zoom` (`CameraMatrices.kt:89`) and everything downstream is in logical
pixels. If the globe keeps that system — a sphere of radius `worldSizeLogicalPixels / 2π` — then:

| zoom | world size (lp) | globe radius (lp) |
|---|---|---|
| 0 | 512 | 81.5 |
| 4 | 8 192 | 1 303.8 |
| 10 | 524 288 | 83 443 |
| 16 | 3.36 × 10⁷ | 5.34 × 10⁶ |
| 22 | 2.15 × 10⁹ | 3.42 × 10⁸ |

Absolute globe-centre coordinates are unusable at high zoom (`Float` ulp at 3.42 × 10⁸ is 32 logical pixels),
which is the same conclusion as ECEF metres and for the same reason. Camera-relative is not optional in
either unit system; it is the whole mechanism. Given camera-relative, the logical-pixel system has one
decisive advantage over metres, in the next subsection.

### 5.4 Reverse-Z: what it buys and what it does not

RenG clears depth to `0`, tests `GL_GEQUAL`, and uses a projection whose near plane maps to `+1` and infinity
to `-1` (`GlFrameDrawer.kt:14-19, 95-98`; matrix rows at `CameraMatrices.kt:76-83`). Working the algebra
through those literal rows, with `n = NEAR_DISTANCE_LOGICAL_PIXELS = 1.0` (`CameraMatrices.kt:141`):

```
z_clip = z_eye + 2n,  w_clip = -z_eye
ndc_z  = (z_eye + 2n) / (-z_eye)
window = (ndc_z + 1) / 2 = n / (-z_eye)  =  near / distance
```

**Window depth is exactly `near / distance`.** The depth attachment is `GL_DEPTH_COMPONENT24`
(`internal/gl/OffscreenSurface.kt:19, 58`) — **fixed point, not float.** That matters: reverse-Z's famous
precision win comes from pairing a `1/z` distribution with a *floating-point* buffer's exponent
concentration near zero. With a fixed-point buffer the resolvable step is a uniform `2⁻²⁴` in window depth,
so the resolvable step in distance is `distance² / near × 2⁻²⁴`. What reverse-Z buys here is not extra
precision; it is that `near` is the only tuning knob and there is no far plane to lose precision against.
ADR 0025 says the same thing in prose: "with a 24-bit fixed-point reverse-Z buffer the resolvable depth step
grows with the square of distance".

Computed:

| formulation | distance | resolvable depth step |
|---|---|---|
| logical pixels, `near = 1 lp` | 651.8 lp (the camera's own distance) | 0.0253 lp |
| logical pixels, `near = 1 lp` | 10 000 lp | 5.96 lp |
| ECEF metres, `near = 1 m` | 6.378 × 10⁶ m (earth radius) | **2.42 × 10⁶ m** |
| ECEF metres, `near = 1 m` | 1 000 m | 0.0596 m |

**So the depth scheme survives a globe if and only if the globe stays in logical pixels with a one-logical-
pixel near plane.** At zoom 0 the globe's radius is 81.5 lp and the camera sits 651.8 lp away, so the whole
sphere lives between 570 and 733 logical pixels of the camera and the resolvable step across it is ~0.032
lp. The far hemisphere is 2R behind the near one — an enormous depth separation — so back-face resolution is
trivial. Nothing about a globe stresses this buffer, in these units. In metres it is unusable without
changing `near`, and `near` is documented as part of the Camera contract (`CONTEXT.md`, Camera: "a fixed
one-logical-pixel near plane and reverse-Z infinite-far depth").

### 5.5 How much curvature there actually is, per zoom

The visible ground at pitch 0 is exactly the output height in logical pixels: the camera sits
`height × (1+√2) / 2` away and the half-FOV tangent is `1 / (1+√2)`, so the half-extent at the anchor plane
is `height / 2`. Taking a 540-tall output and a globe of radius `512 × 2^zoom / 2π`, the sagitta of the
visible arc — the distance the chord sinks below the sphere at the centre of the frame — is:

| zoom | visible arc | sagitta (logical px) |
|---|---|---|
| 0 | 360° | 163.0 |
| 2 | 94.9° | 105.6 |
| 4 | 23.7° | 27.86 |
| 6 | 5.93° | 6.99 |
| 8 | 1.48° | 1.75 |
| 9 | 0.74° | 0.874 |
| 10 | 0.37° | 0.437 |
| 12 | 0.093° | 0.109 |
| 14 | 0.023° | 0.027 |

**Curvature falls below one output pixel at about zoom 9 and below half a pixel at zoom 10.** That number is
load-bearing twice over: it sets how coarse a subdivision can be at each zoom (§4), and it says that **any
cross-mode agreement gate above roughly zoom 10 is vacuous** (§8, §9).

---

## 6. Longitude wrapping

### 6.1 What consumes `unwrappedLongitude`, and what "unwrapped" buys under mercator

`Camera.unwrappedLongitude` is validated only for finiteness at construction — the range check is on
latitude alone (`SpatialValues.kt:46-52`). CONTEXT.md is explicit that "longitude preserves the selected
world copy and is never wrapped or clamped by RenG."

Consumers, in order:

1. **`projectMercator`** turns it into `x = (lon + 180) / 360` (`MercatorProjection.kt:45`), so `x` is
   literally "which world copy, plus the fraction across it". `mercatorCopyIndex = floor((lon + 180) / 360)`
   (`:55-56`) names the copy, and `validateMercatorPosition` requires the copy index in `[-16384, 16384]`
   and the projected `x` in `[-16384, 16385]` (`:120-132, 25-28`).
2. **`wgs84LocalFrame` folds the copy away** — `canonicalWgs84LongitudeDegrees(lon) = lon - 360 × copyIndex`
   (`:150-151`) — because an ENU basis is a property of the point, not of which copy of the world you
   reached it through. `MercatorProjectionTest.kt:110` pins that: longitude 45 and longitude 405 give the
   same frame.
3. **`selectBasemapTiles`** produces both an `unwrappedX` and a `canonicalX = unwrappedX - copy × tileCount`
   (`BasemapTileSelector.kt:80-95`), and deduplicates the canonical set (`:99-112`).
4. **`resolveBasemapTileQuad` uses `unwrappedX`**, and its KDoc calls that "the one place in the whole
   basemap path where the Mercator world copy still matters" (`BasemapTileQuad.kt:25-30`): the firewall's
   tile key deliberately projects the copy away so N visible copies share one acquisition, one engine render
   and one GL texture (`internal/firewall/BasemapEngineHost.kt:720-736`,
   `internal/identity/ResourceKeyDerivation.kt:127-148`), and placement is the one concern that must put
   those copies in N different places.
5. **`Geometry` requires `topLeft.y < bottomRight.y` and a span ≤ 360°** (`DrawnThings.kt:273-274`), which
   only makes sense on an unwrapped line.

So "unwrapped" buys three concrete things under mercator: continuous panning across the antimeridian with no
jump in any value; the ability to pin content to a *specific* copy of the world when several are on screen;
and a single scalar that names both the point and the copy, which is what lets the tile selector deduplicate
acquisitions while keeping N placements.

### 6.2 What it means on a sphere

**All three vanish, and one of them turns into a defect.**

- *Continuity across the antimeridian.* Free on a globe. There is no seam to cross, so nothing needs
  unwrapping to stay continuous.
- *Pinning to a world copy.* Meaningless. There is one world. `mercatorCopyIndex`'s `[-16384, 16384]` bound
  and the `[-16384, 16385]` support box (`MercatorProjection.kt:25-28`) have no globe analogue, and
  `BasemapTileInstance.unwrappedX` / `instanceCopy` (`BasemapTileSelector.kt:10-11`) become a mercator-only
  distinction: on a globe every tile is drawn once.
- *One scalar naming point and copy.* This is the one that bites. **Under `GLOBE`, longitude 10 and longitude
  370 are the same place and must produce identical pixels — but they are different `FramePlan`s.**
  `FramePlan.equals` compares cameras by value (`FramePlan.kt:52`, `SpatialValues.kt:68-74`) and
  `FramePlanCanonicalEncoding` hashes the raw `binary64` bits of `camera.unwrappedLongitude`
  (`:80-81`). So two visually identical globe frames get different frame identities, and
  `FrameStructuralDiffer` (`FramePlanningCore.kt:187`) reports a camera change where there is none. Nothing
  is *wrong* — RenG never claims plan equality implies pixel equality — but every content-keyed reuse
  decision downstream is being made on a key that carries a mercator-only distinction.

There is a mirror-image case: under mercator, panning from longitude 179.9 to 180.1 crosses a copy boundary
and changes `x` by a whole world; under a globe it is a 0.2° rotation. Any diff or LOD heuristic tuned on
mercator's magnitude of change will read that as a large motion on a globe and a small one on mercator, or
vice versa.

The vocabulary already half-anticipates this. CONTEXT.md's Camera entry scopes the copy-index rule
explicitly — "**`MERCATOR` preparation** further requires latitude in `[-85.05…]` … and a derived world-copy
index in `[-16384, 16384]`" — and the Frame History entry says "every successfully prepared **Mercator** plan
advances provisional LOD history." Both sentences already say "Mercator" where they could have said "RenG".
Whoever wrote them was leaving room.

---

## 7. The public API cost

### 7.1 What genuinely has to be added

**Possibly nothing.** `ProjectionMode.GLOBE` already exists, `FramePlan.projectionMode` already carries it,
`UNSUPPORTED_PROJECTION_MODE` already exists, and every geographic field a globe needs — latitude, unwrapped
longitude, altitude in metres, zoom, bearing, pitch — is already there and already typed as `Double`. G can
be, like Cycle H, a cycle that grows `kmp/api/kmp.klib.api` by zero bytes. Whether it *should* be is §7.3.

### 7.2 Fields a globe makes meaningless or wrong, rather than merely insufficient

This is the category the brief asks for, and there are four entries. Three are survivable; one is not.

**(a) `Geometry` with a 360° longitude span — a shape that exists in mercator and does not exist on a
sphere.** `DrawnThings.kt:274` explicitly permits `bottomRight.y - topLeft.y == 360.0`. Under mercator that
is one full world copy: a legitimate rectangle. Under a globe, the two "vertical" edges of that rectangle
are the *same meridian*, so the four corners collapse to two distinct 3D points per latitude and the
four-vertex triangle strip degenerates to a sliver. The correct globe shape is a spherical zone — an
annulus — which the four-corner representation cannot express at all, subdivided or not. **This is the only
existing public invariant a globe makes wrong rather than insufficient.** Options: refuse a full-wrap
geometry under `GLOBE` (a typed failure, following ADR 0029's own precedent, which explicitly cites
`UNSUPPORTED_PROJECTION_MODE` as its model — but the diagnostic rule at
`internal/DiagnosticFactories.kt:275-279` binds `UNSUPPORTED_PROJECTION_MODE` to `fieldName=projectionMode`
only, so a geometry-span refusal needs `INVALID_VALUE` with `geometry.unwrappedLongitude`, which already
exists at `:28`); or represent a wrapping geometry as a closed ring rather than a strip, which is a
subdivision-topology change rather than an API one. I did not settle which; it is a real design question and
it should not be discovered during implementation.

**(b) `Camera.zoom`'s documented meaning is explicitly mercator's.** CONTEXT.md: "Mercator has `512` logical
pixels at zoom zero and scales by `2^zoom`, independently of tile image resolution", implemented at
`CameraMatrices.kt:89`. On a globe that definition still *computes*, but what it means to a consumer — "how
far in am I" — needs a stated globe reading, because at zoom 0 a globe of radius 81.5 logical pixels is a
small ball on a 540-tall output while mercator's zoom 0 fills the frame. This is a CONTEXT.md and ADR
amendment, not an ABI change, and it is the single most consumer-visible difference between the two modes.

**(c) `Camera.unwrappedLongitude`'s "unwrapped" stops buying anything** — §6. The field stays, the name stays
honest, and the identity consequence is documented rather than fixed.

**(d) `Camera.latitude`'s range goes the *other* way — and this one is a gift, not a cost.** The public type
already admits `[-90, 90]` (`SpatialValues.kt:52`) while mercator refuses beyond ±85.0511287798066
(`MercatorProjection.kt:23, 112-117`). `Geometry` is the same: `[-90, 90]` at construction
(`DrawnThings.kt:270-271`), ±85.05 at planning. **The public API has been sized for a globe since Cycle B
and only the globe can make it honest.** A globe camera at latitude 89 needs no ABI change at all; it just
stops being refused.

### 7.3 Per-type audit

| Type | Change |
|---|---|
| `FramePlan` | none. `projectionMode` exists, is defaulted, is encoded, is diffed |
| `Camera` | none to the ABI. Two documented meanings move (§7.2b, §7.2c); one range stops being over-permissive (§7.2d) |
| `Placement` | none. Per-property anchoring is projection-independent; `MAP` scale's internal conversion changes (§2.3) while its documented meaning — metres per local unit — is *preserved* by that change |
| `ResourceLimits` | none required. `maximumBasemapTileInstances` lives on `RendererConfiguration` rather than here (`kmp.klib.api:571`) and its default of 512 may need re-measuring for a hemisphere, but that is a default, not a field |
| `RenGErrorCode` | none required, and possibly one *deletion pressure* to resist: `UNSUPPORTED_PROJECTION_MODE` becomes dead if the globe is complete. It must stay — removing a public enum constant is a break, and ADR 0029 already reuses the *pattern* it established |
| `ResourceClass` | none. Tile identity is `(lod, tileY, canonicalX)` (`ResourceKeyDerivation.kt:139-148`), which is projection-independent — RenG still fetches mercator tiles and re-projects them |
| `Geometry` | possibly one refusal (§7.2a). No field |

**The honest summary is that G's public-API cost is close to zero and its documentation cost is large.** That
is an unusual shape for this project and it deserves to be argued rather than celebrated: a cycle that
changes what an existing field *means* without changing its type gets no help at all from
`checkKotlinAbi`, which is exactly the review that would have caught it.

---

## 8. The gate

### 8.1 The specified gate does not exist and will not exist in time

`docs/decomposition.md:53` gives G's gate as "Golden baselines at both projection modes". `docs/decomposition.md:34`
puts G **before** J in the chain, and J is the golden-image corpus. The same file states the deferral at
`:63-70`: "**Pixel verification is deferred to Cycle J** by owner decision", with E-basemap and F-2 gating on
analytical readback instead. So G as specified gates on a mechanism that arrives one cycle later. The gate
row needs replacing, not interpreting.

### 8.2 What analytical readback *can* assert about a globe

Everything E-basemap's suite asserts today (`kmp/src/commonTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt`)
plus four assertions a globe makes available that mercator does not, all of them exact relationships rather
than tuned numbers:

1. **The limb.** A sphere seen in perspective has a computable silhouette. Every pixel outside it must be the
   clear colour and a sample well inside must not be — the same "no interior pixel is the clear colour"
   invariant the suite already uses (`BasemapReadbackSuite.kt:49`), but with a *boundary that has a closed
   form*. Mercator's ground has no analogue: it fills the frame or it does not. This is the single strongest
   globe-only assertion available.
2. **Antipodal invisibility.** A map-anchored sticker at the camera's antipode must contribute zero pixels; the
   same sticker at the sub-camera point must contribute many. This is an exact, driver-independent
   relationship and it is the cheapest possible proof that the globe is a solid rather than a texture on a
   plane. It has no mercator analogue at all.
3. **Antimeridian continuity.** The suite's own non-coverage list names "the antimeridian seam and world-copy
   dedup (the fixture camera straddles neither)" (`:65-66`). On a globe a camera at longitude 180 shows two
   tiles that are adjacent by construction, so "no clear-colour pixel between two samples straddling the
   antimeridian" is exact. Under mercator the same assertion is about copy arithmetic; under a globe it is
   about the sphere being closed.
4. **Cross-mode agreement at a stated zoom.** Draw the same camera, same style, same drawn things under both
   modes at a zoom low enough that curvature is measurable, and assert a *bounded disagreement* rather than
   equality. This is the project's own established pattern for a machine-independent gate — the scaling-ratio
   assertion that replaced a wall-clock ceiling
   (`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationScaleBenchmarkTest.kt:53-60`,
   quoted at length in `docs/research/2026-08-21-golden-image-gate-design.md:677-700`) — applied to pixels.

### 8.3 What it cannot assert

- **Whether the sphere looks like a sphere.** Every assertion above passes for a globe that is subdivided
  four ways and visibly faceted.
- **Subdivision density.** A limb test passes at any density, because the limb is set by the silhouette
  vertices; a centre-sample test passes at any density, because the centre is on the surface either way. The
  error a coarse subdivision produces is *between* the vertices, which is exactly where §5.4 says the sagitta
  lives — so a density gate needs a sample deliberately placed at a patch centre, and that sample point has
  to be derived from the subdivision the implementation chose, which makes it derived-from-the-thing-under-
  test. §9 says why that is dangerous.
- **Anything about the two device targets.** ADR 0033's asymmetry stands: `androidDeviceTest` and the iOS
  device run are manual and not in CI, and neither has met a real GPU.
- **The iOS simulator's ground case.** `runBasemapReadbackSuite` already skips its ground-coverage case there
  because `measureLargeQuadRasterisation` (`BasemapReadbackSuite.kt:669`) measures 3 040 disagreeing pixels
  against a 512-pixel budget. A globe's ground is *more* large-quad geometry, not less, so any new
  ground-coverage case inherits that skip and Ubuntu's llvmpipe job is again the only thing gating it.

### 8.4 Recommendation

**G's gate should read: "Analytical readback at both projection modes, including a cross-mode agreement case
at a zoom where curvature exceeds one output pixel."** Concretely, four cases added to the existing
`runBasemapReadbackSuite` shape — shared body in `commonTest`, already reached by `macosArm64Test`,
`linuxX64Test` and `iosSimulatorArm64Test`:

1. **Limb.** A globe frame at zoom 2 or below; assert clear outside the computed silhouette and non-clear at
   named interior samples, with the count of disagreeing boundary pixels reported against a stated budget
   derived from the silhouette's own length — following the suite's rule that "every count here carries a
   stated budget, and every budget is derived rather than tuned" (`:84-89`).
2. **Antipode.** One sticker at the sub-camera point, one at the antipode; assert the second contributes zero
   pixels and the first contributes more than a stated floor. The floor is what stops it passing vacuously
   because nothing drew at all — the same guard `:476` already applies to the billboard case.
3. **Antimeridian continuity**, per §8.2.3.
4. **Cross-mode agreement at zoom ≤ 8**, where §5.4 puts the sagitta at 1.75 logical pixels or more. Assert
   that the two modes' frames differ by *more* than a floor (proving the globe is not secretly a plane) and
   *less* than a ceiling (proving it is the same map). Both bounds derived from the sagitta table, both
   printed on every run.

Plus one **negative** case, because every one of the four passes vacuously without it: the same globe plan
with `drawBasemap = false` must leave the frame untouched, exactly as
`assertDrawBasemapFalseLeavesTheFrameUntouched` (`:244-250`) does for mercator, and for the reason its own
KDoc gives — it "is also what stops the other three passing vacuously".

If golden baselines are wanted anyway, `docs/research/2026-08-21-golden-image-gate-design.md:722-769` already
costs the cheapest useful version, and its §5.7 non-coverage list is the honest statement of what a baseline
would and would not add. Nothing in G changes that analysis.

---

## 9. Which globe fixtures would be vacuous

F-2 caught seven vacuous checks and Cycle H two more, every one at a symmetry point. **A sphere is radially
symmetric and an ellipsoid is symmetric about its axis, so this cycle is unusually rich in them.** Here are
the ones I can name from the code as it stands.

**Already in the tree, and worth reading as a warning.**
`MercatorProjectionTest.wgs84FrameUsesSpecifiedFlatteningAndCanonicalLongitude` (`:107-120`) uses
`GeographicPosition(45.0, 45.0, 1000.0)` and asserts an ECEF of
`(3194919.1450605746, 3194919.145060574, 4488055.515647106)` — **`x` and `y` are the same number**, because
`x = (N+h)cos φ cos λ` and `y = (N+h)cos φ sin λ` and `cos 45° = sin 45°`. Swapping `cos λ` and `sin λ` in
`wgs84LocalFrame` is invisible in that fixture. It is caught, but only by its sibling case at
`(0.0, 90.0, 0.0)` (`:72-92`), where `x = 0` and `y = R`. That is the pattern exactly: a fixture at a
symmetry point, rescued by a neighbour that happens not to be. A globe adds many more of these and there is
no guarantee a rescuing neighbour exists.

**Camera at latitude 0, longitude 0.** ECEF is `(R, 0, 0)` and the ENU basis is a signed permutation matrix
(`MercatorProjectionTest.kt:66-70` shows exactly that). Every axis transposition and sign error in the
ECEF/ENU construction survives it.

**Camera at latitude 0 with bearing 0 and pitch 0.** The globe projects to a circle centred on the frame.
Mirroring in `x`, mirroring in `y`, transposing `x` and `y`, and *any* rotation about the view axis are all
invisible. This is the most natural globe fixture anyone would write and it is the worst one available. The
project already learned this once for mercator — the readback suite's camera is the asymmetric
`(-55, -135)` zoom-4 one specifically because "a symmetric camera cannot catch an x/y transposition, and
this cycle has already shipped one suite of exact-url assertions that could not, because every fixture
happened to be symmetric" (`BasemapReadbackSuite.kt:195-198`).

**A tile at the prime meridian, or at `canonicalX = 0`.** Same as above one level down. `BasemapEngineHostTest`
already records that "every other tile fixture in this repo is symmetric — `HOST_RASTER_TILE` is
`(x = 0, y = 0)`" (`:612`) and adds an asymmetric one to fix it.

**A placement at either pole.** `wgs84LocalFrame`'s east vector is `(-sin λ, cos λ, 0)`
(`MercatorProjection.kt:94`), which at `φ = ±90°` is still a unit vector but is entirely determined by a
longitude that names no distinct point. The existing test asserts the north-pole basis using longitude 0
(`MercatorProjectionTest.kt:94-98`); at longitude 90 the same physical point gets a different basis. A model
at the pole therefore *spins* as its longitude changes with no change in position — that is the specification
applied literally, like F-2's all-zero skin weights, and a fixture at the pole cannot distinguish a
deliberate convention from an accident.

**A placement or geometry corner at exactly `±85.0511287798066`.** `projectMercator` **special-cases** that
value to `y = 0` / `y = 1` (`MercatorProjection.kt:46-48`) rather than computing it. A cross-mode fixture at
exactly that latitude compares a globe computation against a mercator *branch*, which is not the comparison
anyone intended.

**A cross-mode comparison above zoom 10.** §5.4: the sagitta is 0.44 logical pixels at zoom 10 and 0.027 at
zoom 14. **A globe implementation that is secretly a tangent plane passes any cross-mode assertion above
about zoom 12.** This is the cycle's most likely vacuous check, because high zoom is where the two modes
*should* agree and where a developer would naturally reach for a comparison.

**A geometry span of exactly 180° or exactly 360°.** Both are symmetry points of the wrap arithmetic; 360°
is also the degenerate case of §7.2a, so a fixture there tests a shape the globe cannot draw rather than the
shape it can.

**A subdivision density derived from the thing under test.** F-2's first caught vacuity was "a cap whose
fixture was derived from the mutated constant". If a subdivision test picks its sample point from the
implementation's own patch layout, it is exactly that defect. The sample must be placed from the *geometry's*
own extent — a stated fraction of its span — not from whatever grid the code chose.

**Altitude 0 everywhere.** With every drawn thing on the sphere's surface, a wrong globe radius scales the
whole scene uniformly and moves nothing relative to anything else. At least one fixture needs a nonzero
altitude, and one needs two different altitudes, or the radius is unpinned.

---

## 10. What I could not establish

- **Whether one global LOD is acceptable on a globe.** §2.2 shows `observeMercatorLod`'s uniform-ratio
  argument does not hold, but I did not measure how bad it gets. What would settle it: compute
  `screenPixelsPerTexel` at the sub-camera point and at the limb for a globe at a few zooms, the same way
  `MercatorLod.kt:22-27` derives its own numbers from measured road-edge widths on the visual harness.
- **Whether the default tile budget of 512 survives a hemisphere.** The budget is a
  `RendererConfiguration` field and the failure is a typed `RESOURCE_LIMIT_EXCEEDED` before acquisition
  (`MercatorSpatialPlanner.kt:155, 228-241`), so exceeding it fails loudly rather than degrading. But I did
  not compute the visible tile count for a globe at each zoom, because it depends on the LOD answer above.
- **Whether a full-wrap `Geometry` should be refused or represented as a ring** (§7.2a). Both are defensible;
  the choice is a design decision, not a fact I could read out of the tree.
- **What zoom means on a globe** (§7.2b). CONTEXT.md's definition is mercator's and I can say it needs
  replacing; I cannot say what should replace it without knowing what consumers expect, and that is a
  question for the owner rather than for the source.
- **Whether ADR 0027's near-tie problem improves or worsens under a globe** (§3.4). The sagitta turns a tie
  into a genuine depth difference, which sounds like an improvement, but the ground and geometries both still
  write no depth so the change may be invisible. What would settle it: render a coplanar altitude-0
  `Geometry` over a globe ground at several pitches, exactly as
  `BasemapReadbackSuite`'s post-ADR-0027 sweep already does for mercator.
- **How much error the single camera-anchored light direction accumulates across a globe frame** (§3.3).
  Computable from the visible arc in §5.4 — at zoom 2 the frame spans 95° of the earth, so the ENU frames at
  the two edges differ by that much — but whether it is visible in shading is a rendering question I did not
  test.
- **Anything about Rentile, tile availability, or how a mercator tile should be sampled onto a sphere.** Out
  of remit by instruction; the sibling document owns it.
- **Whether any consumer shader in the wild reads `gl_VertexID`** (§4.4). Unknowable from this repository.
  The consumer-facing consequence is real regardless and belongs in release notes.
- **Whether `Float` precision is actually adequate for a globe's subdivided ground vertices.** §5.2 argues
  structurally that camera-relative differencing carries over, and the arithmetic in §5.3 supports it, but
  nothing in this repository has ever narrowed a curved surface's vertices to `Float`. What would settle it:
  a spike that builds one subdivided globe tile at zoom 16 through the existing `Double` path, narrows it,
  and measures the round-trip error in output pixels — the same shape as Cycle B's "camera-relative Float
  error below 0.001 px" measurement cited at `GeometryPipeline.kt:32-35`.
