# RenG

RenG provides the vocabulary for a Kotlin Multiplatform 3D renderer that draws worlds on top of
[Rentile](https://rohittp.com/rentile/) basemap tiles into a surface the consumer owns. RenG draws
frames; it owns no window, no render loop, no capture path, and no encoder.

## Language

### The frame

**Frame Plan**:
A complete, self-contained definition of one frame's content, drawn by whichever renderer prepared it.
Its required non-negative `frameIndex` orders strict, history-aware preparation within that renderer;
`drawBasemap`, defaulting to true, may suppress ground for that frame, and `drawLabels`, also defaulting to
true, may suppress **Map Label**s. The two are fully orthogonal: all four pairings are legal, including a
frame that draws labels over no ground at all. Plan list inputs are snapshotted, and
every public list read returns a defensive copy whose platform mutation cannot change the plan, its equality,
or its canonical identity.
_Avoid_: Scene graph, render command, frame delta, mutation batch

**Camera**:
The view of a **Frame Plan**, with geographic latitude, unwrapped longitude, zoom, bearing, and pitch.
Latitude and longitude are finite degrees; latitude lies in `[-90, 90]`, while longitude preserves the
selected world copy and is never wrapped or clamped by RenG. `MERCATOR` preparation further requires
latitude in `[-85.0511287798066, 85.0511287798066]` and a derived world-copy index in
`[-16384, 16384]`; `copyIndex = floor((unwrappedLongitude + 180) / 360)`.

On a globe the winding count is **carried and inert**: a sphere has no seam and no world copies, so a camera
at longitude 400 sees exactly what one at 40 sees. The turns survive anyway, because they are what makes a
consumer's own interpolation continuous — a camera animated from 170 to 190 pans smoothly east in either
projection precisely because the number keeps counting, where a normalised field would ask the consumer to
handle the wrap that Mercator handles for them. A consequence worth knowing rather than rediscovering: two
globe frames differing only in winding render identically and still carry different frame identities, since
a **Frame Plan**'s identity is a digest of the plan and never of the picture.

Zoom is a finite fractional
value in `[0, 22]`; Mercator has `512` logical pixels at zoom zero and scales by `2^zoom`, independently of tile
image resolution. Bearing is finite clockwise degrees from true north in `[0, 360)`. Pitch is finite degrees
in `[0, 90)`, where `0` looks straight down and increasing values tilt toward the horizon. RenG uses a
fixed `45`-degree vertical field of view. Map-occluded projection uses a fixed one-logical-pixel near
plane and reverse-Z infinite-far depth.

**Zoom is projection-dependent, and it is the one public field whose meaning a globe changes without
changing its type.** Mercator's world size is `512 * 2^zoom` at every latitude. A globe's is
`512 * 2^(zoom − log2 cos latitude)` — the sphere scaled up by `1 / cos(latitude)`, so that the on-screen
ground scale at the camera's *own* latitude is the one Mercator would have shown at that zoom. The factor is
exactly `1` at the equator and `7.19` at latitude 82, so the same zoom names a different sphere at every
latitude and a consumer toggling **Projection Mode** sees the picture change: the two modes agree at the view
centre and nowhere else, and disagree visibly at every zoom low enough for curvature to read. What the
convention buys is tile cost. A globe scaled by `zoom` alone draws a Mercator tile only `cos(latitude)` of its
flat on-screen size and needs `1/cos²` times as many to cover one screen — modelled at 4 tiles going to 132
for a 960×540 viewport at latitude 82, zoom 6, and 24 going to 686 for a 1179×2556 one at latitude 82,
zoom 8, the second past the **Tile Budget**'s default, so the frame fails closed rather than merely rendering
slowly. The latitude-matched sphere returns both to their Mercator counts and peaks near `4x` around zoom 3–5,
where the whole globe is on screen. Consistent tile cost was chosen over consistent zoom semantics because the
alternative makes frames *fail* rather than look different; MapLibre ships this convention and Mapbox instead
matches Mercator at a fixed 45 degrees, stable in the middle and wrong at both ends. The LOD rule in
**Frame History** is unchanged and shared by both modes: the globe's `1 / cos` scale and a Mercator tile's
`cos` shrink on the sphere cancel exactly at the camera's own latitude, which is the latitude one per-frame
LOD is chosen at, so both modes select from the camera's `zoom` and never from the scaled exponent.
_Avoid_: Viewport, viewpoint, map camera, view state

**Projection Mode**:
How a **Frame Plan** projects geographic state, either `MERCATOR` or `GLOBE`. If a renderer does not yet
support the selected mode, preparation fails before acquisition or drawing; RenG never substitutes a
different mode.
_Avoid_: Anchoring mode, coordinate space, projection fallback

**Frame History**:
The renderer's clearable record of its last successfully prepared frame index, **Frame Plan**, selected
basemap tile LOD, and label fade. Only one prepare invocation runs at a time. A batch's indices must be
strictly increasing and above history; every item completes pure validation, projection, tile-budget, diff,
and resource-reference planning in index order before any resource work begins. Independent resource work
then runs in parallel.
History commits only when the whole batch succeeds, and returned prepared frames preserve input order.
Structural diffing uses the last successfully prepared plan as the first baseline and each immediately preceding
input plan as the next baseline within a batch; missing history is an empty baseline.
A diff may reuse content-keyed work, but every **Prepared Frame** leases the complete resource set derived
from its own plan. Failure or cancellation exposes no partial history, though valid acquired content may
remain cached. With prior selected integer LOD `L`, selection repeatedly increments while
`zoom >= L + 0.75` and repeatedly decrements while `zoom < L - 0.75`, bounded to `[0, 22]`; this defines
multi-level jumps as the same one-level hysteresis applied until stable. Without history, the nearest integer
LOD is selected with midpoint ties downward. Label fade is one opacity per label, easing toward opaque while
the label is placed and back toward transparent while it is not, advanced one step per successful prepare and
never by a draw; which labels are placed is recomputed from scratch every frame and is never carried (ADR
0035), so a fade's duration in seconds is a function of the consumer's frame rate. An entry is retained only
while its label is placed or still mid-fade. Every successfully prepared Mercator plan advances provisional
LOD history even when no Basemap Style is configured or `drawBasemap` is false; those frames select and acquire
no tiles. The context-free `clearFrameHistory()` clears the structural-diff, LOD and label-fade baseline,
permits a new sequence, and neither frees resources nor invalidates prepared frames. Drawing never changes
history.
_Avoid_: Cache, draw order, partial batch commit, previous-frame mutation

**Tile Budget**:
The renderer's maximum number of unwrapped basemap tile instances in one prepared frame at its selected
LOD. It defaults to `512`, is configurable from `1` through `4096`, and fails preparation
before acquisition when exceeded; RenG never drops required tiles.
_Avoid_: Cache size, tile limit fallback, resource retry budget

**Output Pixel Size**:
The renderer-lifetime positive integer width and height of every frame and target in physical output pixels.
It determines camera aspect ratio, screen anchoring, and basemap coverage and is not read from a framebuffer.
_Avoid_: Viewport, logical size, density-independent size, Render Target dimensions

**Renderer Configuration**:
The renderer-lifetime values fixed at setup: required **Output Pixel Size**, **Transport**, and **Store**;
optional **Basemap Style**, defaulting to none; **Resource Limits**, defaulting to their documented ceilings;
maximum basemap tile instances, defaulting to `512`; maximum preparation batch size, defaulting to `256`;
maximum concurrent resource operations, defaulting to `8`; and a non-throwing **Diagnostic** sink, defaulting
to none. The already-current **Render Context** is
passed separately to renderer creation and setup does not suspend; style acquisition occurs during frame
preparation.
_Avoid_: Frame Plan, render options, mutable settings

**Preparation Budget**:
The renderer's strict ordered-batch bounds: at most `maximumPreparationBatchSize` frames, default `256`
and configurable from `1` through `4096`, with at most `maximumConcurrentResourceOperations` independent
resource operations, default `8` and configurable from `1` through `64`.
_Avoid_: Coroutine dispatcher, unbounded async, transport retry count

**Resource Limits**:
The immutable configurable byte ceilings supplied at setup. Every value is bytes in
`[1, 2147483647]`. Encoded-response defaults are: basemap style `8 MiB`; basemap metadata (TileJSON and
sprite JSON) `4 MiB`; basemap vector/raster/DEM tile `32 MiB`; basemap sprite image `32 MiB`; basemap GeoJSON
`64 MiB`; sticker image `32 MiB`; model GLB `256 MiB`; and model texture `32 MiB`. A request carries its
selected ceiling and oversize resident, stored, or transported content fails before decode or use. Two
further ceilings bound work an encoded ceiling cannot: a decoded-image ceiling, decided from the declared
image dimensions before any pixel buffer is allocated rather than after decompression; and a model JSON-chunk
ceiling bounding a GLB's JSON chunk independently of the whole-GLB ceiling, because a parsed value tree costs
far more than its text. Structural depth bounds — JSON nesting and node-hierarchy depth — are fixed and not
configurable.
_Avoid_: Transport timeout, cache size, decoded-memory estimate, retry budget

**Basemap Style**:
The style document identified by one **Resource Locator**, acquired through the configured adapters, and
drawn by a renderer for its whole lifetime. It is fixed for that renderer and never named by a
**Frame Plan**; a renderer configured with no style draws no ground. A styled renderer also draws no ground
for a plan whose `drawBasemap` is false.
_Avoid_: Map style, theme, basemap, style profile

**Prepared Frame**:
A network-free, GL-free rendering input holding every resource one **Frame Plan** needs, produced
before any drawing occurs and owned by the renderer that prepared it. It may be drawn repeatedly until
its idempotent, context-free `close()` releases its resource leases; closing it performs no immediate GL
deletion. Renderer close invalidates it for drawing but does not change the harmless idempotence of its own
later `close()`.
_Avoid_: Frame buffer, render pass, draw queue, prepared batch

**Render Target**:
A renderer-minted identity for the caller-owned framebuffer RenG composites one finished frame into. It
publicly carries only `FramebufferName(UInt)`; its dimensions are the renderer's configured **Output Pixel
Size**, not a property of the target. Hidden renderer and context-generation identity make foreign and stale
targets invalid. Framebuffer zero names the default framebuffer; a nonzero name must still identify an existing
framebuffer, and every target must be framebuffer-complete when minted and when drawn. RenG never infers target
dimensions from GL.
_Avoid_: Surface, canvas, viewport, swapchain

**Render Context**:
The caller's OpenGL context, already current on the calling thread, that RenG issues every GL call
against. A renderer is affine to the exact context identity captured at setup, not merely to a share
group or to whichever context is current. GL-bound operations may move between threads with that exact
context, but the consumer serializes them. Preparation invocations are serialized for strict frame-index
history; one batch still performs independent resource work concurrently.
_Avoid_: Surface, device, GL session, EGL context

**Restore Set**:
The closed, documented set of GL state RenG reads before it draws and restores before it returns: the draw
and read framebuffer, renderbuffer, program, vertex array, array buffer, pixel unpack buffer and uniform
buffer bindings; the active texture unit and the texture and sampler binding on every unit RenG uses; blend
enable, separate factors, separate equations and colour; depth test enable, function, write mask and range;
cull enable, mode and winding; viewport; scissor enable and box; the colour write mask; the colour and depth
clear values; the unpack alignment, row length, skip rows and skip pixels and the pack alignment; and, on a
desktop **Render Context** only, the draw buffer and line smoothing. `GL_FRAMEBUFFER_SRGB` is set explicitly
and restored wherever it is queryable. The element array buffer binding is excluded because the vertex array
binding restores it. `GL_ACTIVE_TEXTURE` is captured first and reinstated last. The GL error queue is the one
piece of state RenG cannot restore: reading it clears it, so RenG drains it on entry, attributes any flag
found to the consumer, and consumes it.
_Avoid_: GL state cache, context reset, default state, state stack

**Renderer State**:
One of three terminally ordered owner states. `LIVE` has one adopted exact **Render Context**;
`AWAITING_CONTEXT_ADOPTION` follows **GPU Object Loss**, has no adopted context or live GL handle, and still
permits GL-free preparation and resource work; `CLOSED` owns no usable frame, target, resource, or context and
can never transition again. Adoption moves only `AWAITING_CONTEXT_ADOPTION` to `LIVE`; requesting adoption
while already live fails without inspecting or replacing the adopted context.
_Avoid_: Boolean closed flag, implicit context switch, recoverable close

**GPU Object Loss**:
The consumer's declaration that the renderer's GL objects no longer exist. `notifyGpuObjectsGone()` is
context-free, forgets handles without deleting them, retains CPU state, and invalidates prior render
targets. Further GL work requires explicit adoption of an already-current replacement **Render Context**.
_Avoid_: Free, close, context switch, automatic recovery

**Renderer Close**:
The idempotent non-suspending terminal operation that rejects new work, clears **Frame History**, invalidates
all owned **Prepared Frame**s and **Render Target**s, releases CPU state, and deletes live GL objects. Active
preparation makes close fail without changing state; the consumer first uses the suspending cancellation
barrier. Close requires the exact current **Render Context** only while live handles exist; after **GPU Object
Loss** or an earlier close, it is context-free. Once closed, renderer close, preparation cancellation, GPU-loss
notification, resource query/free, and Prepared Frame close remain harmless idempotent operations; query
returns an empty report and free returns an empty result. Preparation, history clearing, context adoption,
Render Target minting, and drawing fail `RENDERER_CLOSED`.
_Avoid_: Free resources, GPU object loss, frame close, reset

**Preparation Cancellation**:
The idempotent suspending snapshot barrier requested by `cancelPreparations()`. It cancels only the prepare
invocation active when called and returns after that invocation terminates; no active invocation is a no-op.
Cancellation remains an unwrapped `CancellationException` and commits no **Frame History**. Kotlin stack
recovery may provide a copy whose immediate cause is the original exception, so referential identity is not
part of the contract. Cancellation is not a persistent mode.
_Avoid_: Renderer close, cancel latch, frame close, partial batch commit

### Placement

**Vector3**:
A finite three-component numeric value named `x`, `y`, and `z`; the owning property and its
**Anchoring Mode** define what those components mean. Construction canonicalizes negative zero to
positive zero and rejects every non-finite component.
_Avoid_: Vector2, point, tuple, coordinate

**Placement**:
The resolved position, rotation, and scale of one drawn thing, each property carrying its own
**Anchoring Mode**.
_Avoid_: Transform, matrix, pose

**Anchoring Mode**:
Whether one placement property is resolved against the map (`MAP`) or against the screen (`SCREEN`).

A `SCREEN` **Placement** position on a **Model** is not implemented, and preparation fails on it before
acquisition or drawing rather than degrading (ADR 0029) — the same rule **Projection Mode** follows for a
mode a renderer does not yet support, and for the same reason: RenG never substitutes a different mode. The
screen regime composites an ordered stack with no depth test, and its projection carries no depth at all,
so a volumetric mesh drawn there would show its own back faces through its front ones. That is a silently
wrong picture rather than a missing one, which is the worse failure. Every other combination is supported,
including a `SCREEN` rotation or scale on a **Model** whose position is `MAP` — the billboard case.
_Avoid_: Coordinate space, reference frame, projection mode

**Rotation**:
A finite `Vector3` of canonical degrees in `[-180, 180)`, applied as right-handed fixed-axis X, then Y,
then Z rotations (extrinsic XYZ), with column-vector matrix `Rz * Ry * Rx`. Map-anchored rotation starts from
east/north/up axes at its geographic anchor and converts that anchor basis into the camera's local ENU basis;
screen-anchored rotation starts from screen-right/screen-up/toward-viewer axes.
_Avoid_: Quaternion, orientation matrix, intrinsic XYZ, moving-axis rotation

**Scale**:
A finite non-negative scalar converting asset-local units. Map-anchored scale is metres per local unit;
screen-anchored scale is output pixels per local unit. Sticker local dimensions are encoded-image pixels,
and Model local dimensions are GLB coordinates. Zero scale is valid. Planning requires the final converted
scale to remain finite as a GPU-bound Float and reports `fieldName=placement.scale` otherwise.
_Avoid_: Size, zoom, density-independent scale

**Screen Anchoring**:
Resolution against continuous output-pixel screen space. The output bounds are `[0, width]` by
`[0, height]`, and pixel centres lie at half-integer coordinates. `position.x` and `position.y` locate
the object's local origin from the top-left, with positive x rightward and positive y downward; each must
remain finite as a GPU-bound Float or planning reports `screenPosition.x` or `screenPosition.y`.
`position.z` is a CPU-side `Double` compositing z-index rather than depth; greater values composite on top.
Equal z-index values use stable plan order: stickers in list order, followed by models in list order, with later
entries on top.
_Avoid_: Overlay mode, 2D mode, billboard

**Map Anchoring**:
Resolution against map space, in which the drawn thing is depth-tested and occluded by the 3D scene.
A map position's `(x, y, z)` components mean `(latitude degrees, unwrapped longitude degrees, WGS84
ellipsoidal altitude metres)`. `MERCATOR` preparation applies the **Camera** latitude and world-copy bounds
to every map position and **Geometry** corner; out-of-domain values fail rather than clamp or wrap. Altitude
accepts any finite value, but planning rejects values that cannot remain finite through camera-relative Double
and GPU-bound Float conversion. Those failures report `mapPosition.altitude` or `geometry.altitude`; latitude
and world-copy failures retain their corresponding latitude or unwrapped-longitude field. Map-anchored
drawing is depth-tested throughout, in **three depth phases** and one draw order (ADRs 0025, 0027 and 0030).
Flat map-plane content — the **Basemap Tile** ground first, then **Geometry**s in list order — tests depth
and writes none, so it is occluded by anything that did write nearer depth and occludes nothing itself.
**Model**s in list order then test **and write**, because a mesh that writes no depth cannot occlude itself:
its own back faces show through its front ones. Map-anchored **Sticker**s in list order then test and write
none again, drawing after the models because a map-anchored sticker is a marker and a marker paints over
the scene it marks. The map regime's draw order is therefore ground, **Geometry**s, **Model**s,
map-anchored **Sticker**s, with later entries on top of anything that wrote no nearer depth. Depth
comparison is greater-or-equal, so a drawn thing at exactly the depth already drawn there is visible rather
than discarded; altitude-0 content over the ground is the ordinary case of that, and needs no tie-break of
its own now that nothing on the map plane writes depth to tie with. A **Model** is a real occluder, so a
screen-parallel billboard sharing space with one can still be cut along its own anchor row — the narrower
form of a defect ADR 0027 closed against the ground, accepted deliberately by ADR 0030.
_Avoid_: World mode, 3D mode, geo mode

**Draw Regime**:
One of the two distinct ordering-and-occlusion rules a frame draws under — screen-composited or
map-occluded.
_Avoid_: Layer, pass, stage

### Drawn things

**Sticker**:
A **Placement** paired with one PNG image identified by a **Resource Locator**, drawn as a centred local XY
quad whose width and height are the image's pixel dimensions, with +x right, +y up, and +z normal.
_Avoid_: Marker, pin, decal, sprite

**Model**:
A **Placement** paired with one GLB mesh locator, an optional base-colour texture override, and its
**Animation Track** states. Its authored GLB origin, axes, and local units are preserved unchanged before
Placement. GLB buffers and images must be embedded; external URI references are rejected. Without an
override it uses GLB-authored material colours and embedded textures; an override replaces every
rendered primitive's base-colour texture while preserving other material properties.
_Avoid_: Mesh, asset, actor, entity

**Animation Selector**:
Either the exact non-blank name or non-negative `Long` index of one animation in a GLB. Names are exact Unicode
scalar sequences: RenG performs no normalization and rejects isolated UTF-16 surrogates. A GLB catalog with
duplicate nonblank exact animation names is invalid. Preparation rejects a missing or out-of-range selector,
or different selectors that resolve to the same animation.
_Avoid_: Animation id, clip key, nullable name/index pair

**Animation Track**:
An **Animation Selector** together with one finite non-negative `timeSeconds` selected for this
**Frame Plan**. A model applies its animation tracks in list order; after parsing, positive-duration
animations sample `timeSeconds % durationSeconds`, while zero-duration animations sample time zero.
Missing selectors raise rather than substitute.
_Avoid_: Clip, timeline, animation state, keyframe, frame index, frames per second

**Geometry**:
A shader-painted geographic rectangle defined by opposite `topLeft` and `bottomRight` `Vector3` corners.
Top-left latitude is strictly north of bottom-right latitude; its unwrapped longitude is strictly west,
with a span no greater than `360` degrees. The northern edge uses `topLeft.z` altitude, the southern edge
uses `bottomRight.z`, and altitude interpolates north-to-south. A **Geometry** carries no **Placement**.
_Avoid_: Layer, overlay, custom layer, primitive

**Shader Pair**:
The `vertexSource` and `fragmentSource` shader sources a **Geometry** is painted with.
_Avoid_: Program, material, effect

**Shader Profile**:
The single accepted shader source dialect — a GLSL ES 3.00 body whose first directive physical line, after
trimming ASCII space/tab only, is exactly `#version 300 es`. Prefix lines may contain only ASCII space/tab and
`//` or non-nesting `/* ... */` comments and must end with LF, CRLF, or bare CR; comments cannot share the
directive line. A GLES context receives the original source unchanged. A desktop **Render Context** replaces
the directive physical line's complete non-terminator span with `#version 330 core`, preserves its terminator
and every other code unit, and performs no other substitution. A missing or different directive is
`INVALID_VALUE` at `FRAME_PLANNING`, not a driver compilation failure.
_Avoid_: Shader language, GLSL version, compatibility profile

**Shader Interface**:
The fixed attribute and uniform names RenG binds by name in a compiled **Shader Pair**, honouring the
**Shader Profile**'s substitution rule — ADR 0008 binds a documented name only when the compiled program
declares it, and never fails a shader for omitting one. Attributes: `aPosition` (`vec3`), `aTexCoord`
(`vec2`). Uniforms: `uModelViewProjection` (`mat4`); `uResolution` (`vec2`); `uGeometryBounds` (`vec4`,
west/south/east/north degrees); `uFrameIndex` (`uint`). `uGeometryBounds` is explicitly informational and
unsuitable for placement arithmetic — placement stays camera-relative and exact through `aPosition` and
`uModelViewProjection`, because Cycle B measured camera-relative Float error below 0.001 px and absolute
degrees packed into a 32-bit float would discard that precision. `uFrameIndex` is narrowed from **Frame
Plan**'s `frameIndex` (`Long`) because GLSL ES 3.00 has no 64-bit integer type, wrapping at roughly 2.3
years of continuous 60fps; `frameIndex` is an ordering key rather than a clock, so that wrap is not a
correctness hazard. See ADR 0024 for the hazard of renaming one of these names later.
_Avoid_: uniform preamble, injected include, varying, built-in attribute

**Terrain**:
The elevation the ground is displaced by, decoded by RenG from the DEM tiles Rentile acquires for the
style's `terrain` source. Terrain is **geometry, not appearance**: it moves the ground surface and
nothing else. Relief shading is the style's business — a style that wants it declares a `hillshade`
layer, which the engine draws into the tile image — with one exception, below.
_Avoid_: Elevation model, heightmap, DEM (that is the *source data*, not the drawn thing), relief,
hillshade (that is a style layer type and means shading, not displacement).

**Altitude Mode**:
Whether a **Placement**'s `position.z` is measured from the ellipsoid or from the **Terrain** beneath
it — `ABSOLUTE` or `GROUND_RELATIVE`. **`ABSOLUTE` is the default and the meaning the glossary already
carried**: altitude is ellipsoidal metres, so a caller who says zero over a plateau means sea level and
gets it. `GROUND_RELATIVE` is the opt-in that means the other thing. The two are identical wherever the
ground is flat, which is every style that declares no terrain.
_Avoid_: Clamp to ground, sea level (as a mode name), height mode, elevation offset.

**Terrain Shading**:
A **Renderer Configuration** option, **default off**, that shades the displaced ground from a normal
derived from the DEM. Off, the ground stays unlit exactly as ADR 0026 says, which is what the style
asked for: in the corpus the styles that wanted relief shading declared `hillshade` and the styles that
displace declined it. It exists because two of the six displacing styles are pure vector, whose flat
fills read as flat however far they move. It is a property of the renderer rather than of a **Frame
Plan**, so it cannot vary frame to frame.
_Avoid_: Hillshade (a style layer type), lighting (ADR 0026's light is a different, world-anchored
thing that shades **Model**s), relief.

**Basemap Tile**:
One canonical PNG tile acquired from Rentile and drawn as the ground beneath a frame. A canonical tile
may back multiple unwrapped world-copy draw instances when repeated Mercator worlds intersect the output.
Mercator selection traces physical output-pixel-centre rays, excludes non-downward sky/horizon samples, and
selects every closed tile cell intersecting the conservative closed finite ground footprint. Tile edges are
inclusive for coverage and canonical instances are deduplicated only after unwrapped draw instances and the
pre-acquisition **Tile Budget** are determined. A basemap tile carries no map text: the basemap engine
excludes text-only layers and strips text components rather than drawing them, and RenG does not draw them
either, because text baked into a ground texture is perspective-distorted under a pitched **Camera**. Map
text therefore needs style-evaluated label primitives from the engine and screen-space placement in RenG,
and neither exists yet.
_Avoid_: Output tile, source tile, map tile, raster

**Terrain Sample**:
Elevation acquired for the style-selected terrain source, encoded as eight-bit channels in one of the two
supported DEM encodings and validated for its declared encoding before use. RenG decodes and validates
terrain where it is acquired and displaces the ground with it where the ground is drawn; the tile's own
decoded samples must be bit-exact, with no premultiplication, scaling, or colour transform, because any of
those silently change elevations.
_Avoid_: Height map, DEM image, hillshade, terrain texture

**Scene Light**:
The single fixed light RenG shades **Models** by. It is world-anchored — its direction is fixed relative
to the map, not to the camera — at azimuth 335 degrees and elevation 45 degrees, with an ambient term so a
surface facing away from it stays readable rather than going black against a bright ground. The ambient and
diffuse terms are `0.35` and `0.65`, summing to one so a fully lit surface reaches its own colour exactly;
ADR 0026 committed to "an ambient term" without a value, and those are it. The azimuth is
the cartographic relief-shading convention, chosen because light from the north-west avoids the inversion
illusion that makes hills read as valleys; taking it means **Model** shading and terrain hillshading already
agree when the ground gains relief.

It is **RenG's own constant, not a consumer-visible feature**. No **Frame Plan** field configures it, and
none of the 34 map styles RenG is verified against declares a style-spec `light` object at all, so there is
nothing to derive it from and nothing to disagree with. World-anchoring is the load-bearing half: a
camera-anchored light makes a **Model**'s shading swim as the camera orbits, so the object reads as lit by
the viewer rather than by the world, which is wrong for something pinned to a coordinate. **Stickers**,
**Geometries** and the ground are unlit and unaffected — a **Geometry** is painted by its own shader pair,
and lighting it would contradict that.
_Avoid_: Sun, headlight, illumination, lighting model, shading mode

### Map labels

**Map Label**:
Text the basemap style asks for, drawn by RenG as screen-space primitives from a **Label Candidate** the
engine hands over. Rentile decides everything the style document says — which features are labelled, glyph
identity and metrics, label-local layout, the atlas — and RenG owns everything that needs a camera:
projecting the anchor, placing the quads, resolving **Label Collision**, and the draw. Labels are neither a
map-regime nor a screen-regime **Drawn Thing**; they are engine-derived, have no **Frame Plan** entry, and
draw in their own phase between the two regimes (ADR 0034).
_Avoid_: Text layer, annotation, caption, POI label, symbol

**Label Candidate**:
One labellable feature as the engine reports it: a geographic anchor, laid-out glyph quads in label-local
coordinates, per-feature paint, a collision box, and the style's placement intent. A candidate is an offer,
not a placement — whether it reaches the screen is **Label Collision**'s answer.
_Avoid_: Label instance, placed label, glyph run, symbol candidate

**Label Collision**:
The screen-space contest that decides which **Label Candidate**s are drawn, resolved during preparation and
never during drawing. Higher `symbol-sort-key` wins, then style layer order. **This is the only thing in
RenG that exists to drop content** — every other budget fails preparation rather than dropping, and the
**Tile Budget**'s rule that RenG never drops required tiles does not extend here.
_Avoid_: Label culling, overlap rejection, deconfliction, label budget

Note this is unrelated to the *resource-key* collision that `IDENTITY_COLLISION` reports, which is two sets
of canonical bytes claiming one digest. The two share a word and nothing else; prefer the qualified form
whenever both could be meant.

**Label Fade**:
The only label state carried between frames: an opacity per label, eased so a label entering or leaving does
not pop. *Which* labels are placed is recomputed from scratch every frame — there is no placement
hysteresis, because the corpus never asks for a second candidate position and incumbency would only let a
minor label block a major one. Fade lives in **Frame History**, advances once per successful preparation
rather than on a clock, and is cleared by `clearFrameHistory` (ADR 0035).
_Avoid_: Label animation, transition, hysteresis, label memory

### Resources

**Frame Identity**:
An internal versioned canonical binary encoding of one **Frame Plan**, identified as
`reng-frame-v1:<lowercase SHA-256>`. The encoded root begins `RNGC`, schema byte `1`, and frame-kind byte `1`;
strict tagged fields preserve every exact scalar, ordered list, duplicate, and canonicalized numeric bit under
ADR 0018. Frame and animation indices use unsigned 64-bit big-endian payloads, enum and shader-profile values
use unsigned 16-bit big-endian payloads, and canonically encoded strings reject isolated UTF-16 surrogates
before exact UTF-8 encoding. RenG retains and compares canonical bytes before sharing and fails
`IDENTITY_COLLISION` if one digest names different bytes. The key is not public API.
_Avoid_: Public frame id, cache key, caller id

**Resource Locator**:
A non-blank, opaque caller-supplied string identifying encoded resource bytes. RenG preserves its exact
text, redacts it from diagnostics, gives that text only to the injected **Transport**, and derives the
credential-free **Raw Resource** Store key from a canonical root containing the exact text and class. In every
canonical locator-valued field, the payload is the exact UTF-8 of this text rather than a nested tagged object;
an optional locator uses only its presence byte followed by those UTF-8 bytes when present. RenG never parses
it as a URL or opens it as a file path.
_Avoid_: URL, file path, URI, resource key

**Raw Resource**:
The exact encoded bytes of one thing RenG needs — a basemap resource proxied to Rentile, a sticker
image, a GLB mesh, or a model texture — before any decode or parse.
_Avoid_: Asset, blob, cache entry, decoded resource

**Resource Class**:
One of the eleven closed raw-resource kinds: `BASEMAP_STYLE`, `BASEMAP_TILE_JSON`,
`BASEMAP_VECTOR_TILE`, `BASEMAP_RASTER_TILE`, `BASEMAP_DEM_TILE`, `BASEMAP_SPRITE_JSON`,
`BASEMAP_SPRITE_IMAGE`, `BASEMAP_GEO_JSON`, `STICKER_IMAGE`, `MODEL_GLB`, or `MODEL_TEXTURE`.
The basemap classes map to Rentile only behind RenG's public boundary.
_Avoid_: Resource type, MIME type, content type

**Resource Kind**:
Which renderer-held allocation a lifecycle entry represents: `EXTERNAL`, `GEOMETRY_PROGRAM`,
`INTERNAL_PIPELINE`, or `OFFSCREEN_SURFACE`. Only `EXTERNAL` entries also carry a **Resource Class**.
_Avoid_: Resource Class, GL object type, cache tier

**Resource Key**:
A credential-free logical resource identity containing its **Resource Kind** and a lowercase SHA-256
stable id over ADR 0018's domain-separated canonical resource root. External roots include the exact
**Resource Locator** and **Resource Class**, though neither appears in the stable id's textual representation;
external keys also retain their Resource Class for typed selection. Geometry-program roots include the shader
profile version and exact sources. A key selects all resident or retired generations of that logical resource.
_Avoid_: Resource Locator, URL hash label, generation handle

**Resource Selector**:
An immutable request selecting all renderer-held resources, one **Resource Kind**, one external
**Resource Class**, or one **Resource Key** for query or free operations.
_Avoid_: Cache predicate, mutable filter, locator query

**Resource Generation**:
One concrete loaded instance of a **Resource Key**'s content, holding its exact raw bytes and record
metadata, its decoded or parsed product, and later its GPU allocations. Exactly one generation of a key is
current, and only the current generation satisfies a new **Resource Operation**. A generation superseded by
different content stays usable while leased and disappears when its last lease closes. Freeing retires every
generation of the matched keys: those with no lease go immediately, and the rest wait for their last lease.
A retired generation is never resurrected, even when identical bytes return, so a free result stays a
truthful account of what happened at that instant.
_Avoid_: Cache entry, version, revision, snapshot

**Lease**:
One live claim on one **Resource Generation**, held by a **Prepared Frame** for every distinct resource its
plan needs and by an in-flight draw for its own duration. A generation is never deleted while any lease is
open. Leases are counted, not owned exclusively: many prepared frames share one generation, and equal plans
prepared after **Frame History** is cleared take independent leases.
_Avoid_: Reference, handle, pin, checkout

**Resource Reload**:
The path that makes freeing safe. A freed **Resource Key** leaves a marker distinguishing it from one never
loaded, so the next **Resource Operation** naming that key acquires it again, installs a new current
**Resource Generation**, and emits exactly one `RESOURCE_RELOADED_AFTER_FREE` warning for that key. Reload
follows the same **Resource Access Mode** rules as any other operation and may be satisfied from the
**Store** without a network exchange; the warning reports that RenG had to reload, not that it refetched.
Markers live until **Renderer Close**.
_Avoid_: Cache miss, invalidation, eviction, refresh

**Resource Report**:
An immutable point-in-time account aggregated by **Resource Key**, with resident-generation count,
retired-generation count, lease count, and reload-required flag. Each entry and the report totals contain
exact `rawBytes` and `decodedCpuBytes`, nullable `knownGpuBytes`, and `hasUnknownGpuBytes`. Known GPU
bytes sum every exactly knowable allocation and are zero when no GPU allocation exists; they are null only
when GPU allocations exist but none is measurable. When known and unknown allocations coexist, the known
sum remains present and `hasUnknownGpuBytes` is true. Driver and program overhead is never estimated.
Free-operation totals count logical keys as matched, fully freed, deferred, or already free.
_Avoid_: Singular aggregate state, cache dump, resource map, diagnostic log

**Resource Operation**:
One preparation-scoped attempt to make one logical **Raw Resource** available under one **Resource Access
Mode**. A prelookup route identity contains access mode, exact **Resource Locator**, exhaustive **Resource
Class**, and response-byte limit; equal routes join one freshness sample, resident decision, Store read, and
outcome. After that lookup, complete allowlisted request metadata joins the route identity to form the final
Transport-latch identity. Static route occurrences preregister during batch planning only for joins and
collision detection, with no execution order or permission to start work. Content-discovered occurrences
register at deterministic depth-first discovery frontiers before work on that route; later occurrences stay
withheld until all earlier frontiers close. Distinct routes that collapse to one private Rentile Store key fail
`AMBIGUOUS_RESOURCE_ROUTE`; no call is made for the newly colliding route, though already completed parent
calls cannot be undone. A private Rentile Store key exists only for the seven **Resource Class**es Rentile
itself keys — TileJSON, vector tile, raster tile, DEM tile, sprite JSON, sprite image, and GeoJSON — and
reproduces Rentile's own derivation over the locator with authentication query values redacted. Basemap
Style, sticker image, model GLB, and model texture are never keyed by Rentile, so each takes a private key
derived from RenG's own canonical resource identity, which is injective in locator and class and therefore
never collides. Collision detection is uniform over routes; only the derivation differs by class.
The first consumer **Transport** outcome is latched for that identity until the operation ends, so concurrent
joins and repeated internal Rentile adapter calls replay the same complete response, sanitized failure, or
unwrapped cancellation outcome rather than performing another consumer exchange. A latch never outlives its
operation. The first logically eligible occurrence, not preregistration, assigns the shared route's traversal
ordinal; an earlier dynamic occurrence can therefore activate a route first seen in a later static segment.
Frontier withholding ensures no unknown earlier occurrence remains when an ordinal is assigned. Concurrent
outcomes retire in ordinal order, so the earliest route-terminal failure or adapter cancellation wins
independently of worker completion order. Caller or explicit preparation cancellation wins only when it claims
the invocation's terminal slot before that in-order outcome; cleanup cancellation never replaces an already
selected outcome.
_Avoid_: Renderer-lifetime request cache, retry, URL-only identity, unpartitioned single flight

**Resource Access Mode**:
Preparation policy selected once for a whole prepare invocation: `NORMAL`, `CACHE_ONLY`, or `RELOAD`.
`NORMAL` uses a fresh resident without reading Store. With no resident or a stale resident, it reads Store once;
a valid Store record supersedes the stale resident for that operation, while a null Store result leaves the
stale resident as baseline. A stale baseline sends ETag first, otherwise last-modified; with neither validator,
NORMAL sends an unconditional request that accepts only `200` and never falls back to the stale bytes.
`CACHE_ONLY` uses a valid resident regardless of freshness, otherwise reads Store once and uses valid stored
content regardless of freshness; missing content fails unavailable. `RELOAD` skips resident and Store reads,
sends no conditional metadata, accepts only a full-body success, and writes that content at its class boundary
before visibility. Resident- or Store-sourced content is never redundantly written. No mode adds retries,
repairs, redirects, status fallbacks, or byte ranges, and single-flight work is shared only within the same mode.
_Avoid_: Request header, retry policy, cache flag

**Transport**:
The consumer-supplied adapter RenG performs every bounded network exchange through, for its own resources
and for the ones it proxies to Rentile. Requests carry the exact **Resource Locator**, **Resource Class**,
selected **Resource Limits** byte ceiling, and only optional `ifNoneMatch`, `ifModifiedSince`, and `accept`
metadata; their textual representation redacts the locator and all metadata values. A conditional `NORMAL`
request sends exactly one validator, preferring a nonblank ETag over a nonblank last-modified value. Accept is
fixed by class: JSON, Mapbox vector tile, PNG, or GLB as specified by the Cycle B public contract. Responses
carry status, defensively copied bytes, and optional `contentType`, `etag`, `lastModified`, and
`freshUntilEpochMillis`. Consumer response constructors snapshot and redact but deliberately admit malformed
values; operation validation, not adapter-side construction, reports invalid status, bytes, metadata, or epochs
as a typed RenG failure. Valid optional text is a nonblank Unicode scalar sequence with no CR or LF;
isolated UTF-16 surrogates and negative optional epochs are invalid. Response validation always checks metadata
first and then branches on status. A `200` must be nonempty before its body is compared with the selected limit;
an empty `200` is invalid response and an oversized nonempty `200` is resource-limit exceeded. A `304` must
have an exactly empty body, with no limit comparison, and is valid only for a conditional `NORMAL` exchange with
valid stale baseline content. It preserves baseline bytes and digest, overrides each allowlisted metadata field
only when the response supplies a nonnull value, retains baseline metadata otherwise, sets stored-at to the
operation's one time sample, and passes the merged record to its class-specific write/visibility boundary.
Basemap Style may be staged privately for Rentile compilation before that write but cannot complete an item or
enter a Prepared Frame until the write succeeds. Every other status, including redirects and a `304` without
its baseline, fails. Byte ranges and arbitrary headers are outside the contract.
One **Resource Operation** performs at most one consumer exchange for each structural identity; Rentile's
repeated internal adapter calls are contained behind RenG and are not consumer retries.
_Avoid_: HTTP client, fetcher, network layer

**Store**:
The consumer-supplied persistent adapter RenG reads and writes **Raw Resource** bytes through; RenG owns no
persistent cache of its own. `RawResourceKey` contains only a credential-free stable id and **Resource
Class**. `StoredRawResource` contains defensively copied bytes, a caller-supplied digest, optional content type,
ETag, last-modified, and fresh-until epoch milliseconds, plus stored-at epoch milliseconds. Its consumer-facing
constructors snapshot and redact but admit malformed shape, digest, metadata, and epochs so operation validation
can report `STORE_INTEGRITY_FAILED / STORE_VALIDATION`. A valid record is nonempty, within the class's
**Resource Limits** ceiling, has an exactly 64-character lowercase hexadecimal SHA-256 digest matching its
bytes, has nonblank CR/LF-free Unicode-scalar optional text, and has non-negative optional and stored-at epochs.
A record is fresh only while `freshUntilEpochMillis > now` for the
**Resource Operation**'s single non-negative system epoch-millisecond sample; equality, an absent freshness
value, or an earlier value is stale. The **Store** exposes suspending read and write only. Shape-, digest-, metadata-, or class-format-invalid
records fail integrity validation and are never removed, bypassed, or repaired. RenG validates consumer
records before exposing them to Rentile and traps Rentile's private remove request without consumer mutation.
Valid stale records follow the selected **Resource Access Mode**. A fetched or `304`-metadata-refreshed record
reaches consumer write only after its class-specific validation boundary defined by ADR 0016; a resident or
Store-sourced record is not rewritten. Store-sourced Basemap Style bytes compile privately and may become
preparation-visible only after compilation and whole-batch success, without a redundant consumer write. Fetched
or metadata-refreshed Basemap Style may enter the same private staged Rentile input, but its one consumer write
waits for successful `Prefetched` compilation and completion of all other work for every referencing batch item;
only then can the style become preparation-visible. Every required write must succeed before content enters a
completed item or Prepared Frame.
_Avoid_: Cache, disk cache, repository

### Errors and diagnostics

**RenG Failure**:
One typed `RenGException` carrying a closed error code, granular pipeline stage, and immutable redacted
diagnostics. Its message is stable, its cause is always absent, and a `CancellationException` is never
wrapped as a **RenG Failure**. A non-increasing frame index or one not above committed history is
`PREPARATION_ORDER_VIOLATION` at `FRAME_PLANNING`. A second prepare is `PREPARATION_IN_PROGRESS` at
`FRAME_PREPARATION`; before any context validation, the same code rejects active-preparation interference
from history clearing, resource freeing, or renderer close at that operation's stage. Empty batches use
`INVALID_VALUE`; oversized batches and tile plans use `RESOURCE_LIMIT_EXCEEDED` with allowlisted `fieldName`,
`limit`, and `actual`. Public value/configuration constructor violations instead throw non-sensitive
`IllegalArgumentException` before renderer work; their implementation-defined messages are not caller
contracts. Unexpected transport statuses, integrity failures, and deferred GPU-deletion failures remain
distinct errors and never trigger fallback work. Every basemap engine failure is classified into RenG's own
closed code rather than wrapped: style failures reuse the parse and unsupported-feature codes, engine safety
limits reuse `RESOURCE_LIMIT_EXCEEDED`, engine store failures reuse the store codes, and a wrapped batch
failure is unwrapped to its primary. A failure to produce the ground image itself — rasterization or its
encoding — is `BASEMAP_RENDER_FAILED` at `BASEMAP_RENDER`, which also carries the engine states reachable
only if RenG mismanaged a handle it owns, so no untyped throwable escapes.
_Avoid_: Adapter exception, cause chain, message-based recovery

**Diagnostic**:
An immutable event containing only an allowlisted field name, **Resource Class**, credential-free resource
key, status code, limit, or actual value. A non-throwing consumer sink receives diagnostics, serialized
per renderer without thread affinity or a total order across otherwise concurrent operations; callbacks
run outside renderer locks and sink failures are swallowed. `RESOURCE_RELOADED_AFTER_FREE` is a sink-emitted
warning. `FAILURE_CONTEXT` is an error carried only in one `RenGException`, shares that exception's stage, and
is never emitted separately. No diagnostic contains a **Resource Locator**, adapter text, arbitrary field name,
or secret metadata.
_Avoid_: Log message, arbitrary metadata, exception cause

## Relationships

- One renderer draws exactly one **Basemap Style**, or none at all, at exactly one output pixel size
- One **Frame Plan** carries exactly one **Camera** and one **Projection Mode**
- `prepare(plan, accessMode = NORMAL)` is equivalent to one nonempty atomic batch item
- `prepareBatch(plans, accessMode = NORMAL)` snapshots a nonempty input List, applies one access mode to
  the whole invocation, enforces the configured batch limit and strict frame-index order, and returns one
  same-order defensive List only after complete success; that List is not renderer backing state
- One prepare invocation owns all of its **Resource Operation** route resolutions and latches. Equal prelookup
  routes in that invocation join one resident/Store decision; finalized equal exchange identities join one
  Transport outcome. Different routes or access modes never join, and no resolution survives the invocation
- Equal **Frame Plan** values may produce independent leases after history is cleared; `frameIndex` orders
  and identifies a plan but creates no resource dependency by itself
- Many **Prepared Frame**s may exist at once and be drawn in any order
- Drawing may overlap GL-free preparation. Resource query and Prepared Frame close may overlap either and
  linearize at renderer state boundaries; an in-flight draw holds its own temporary lease, so a concurrent
  Prepared Frame close cannot free resources out from under it
- The consumer serializes all GL-bound renderer calls. **GPU Object Loss** may overlap preparation but
  serializes with an in-flight GL call before it forgets handles
- For an operation they affect, error precedence is: active-preparation interference; `RENDERER_CLOSED`;
  `RENDER_CONTEXT_ADOPTION_REQUIRED`; Prepared Frame ownership/closed state; Render Target ownership/context
  generation; exact current-context validation; deferred deletion; then operation-specific validation/work
- Successful atomic preparation updates **Frame History**; cancellation, failure, and drawing do not
- A consumer may clear **Frame History** without freeing resources or invalidating prepared frames
- One **Prepared Frame** draws into any number of **Render Target**s, and drawing does not consume it
- One **Frame Plan** holds ordered lists of **Sticker**s, **Model**s, and **Geometry**s
- List order is frame content, and each repeated equal value is a distinct drawn thing
- Each **Sticker** and **Model** carries exactly one **Placement**
- A **Geometry** carries no **Placement**; its geographic bounds establish its map-space footprint
- A **Placement** resolves position, rotation, and scale independently, each under its own **Anchoring Mode**
- Position anchoring selects the whole drawn thing's **Draw Regime**
- Rotation and scale anchoring select their transform basis and units without changing the **Draw Regime**
- Every position/rotation/scale anchoring combination is valid
- Map-anchored rotation and scale use the drawn thing's map position when present, otherwise the
  **Camera** location; screen-anchored rotation is camera-facing and screen-anchored scale is constant
  in output pixels
- One **Model** carries an ordered list of zero or more **Animation Track**s, whose selectors must resolve
  to distinct GLB animations
- A violated uniqueness rule is an error; RenG never silently removes or repairs duplicates
- Numeric construction canonicalizes only negative zero; non-finite, out-of-range, and malformed values
  are errors and are never clamped, wrapped, or repaired
- Renderer-held resources remain resident without automatic LRU eviction until explicit free or
  **Renderer Close**
- Free retires leased generations without invalidating live **Prepared Frame**s; new preparation reloads a
  fresh generation and emits one warning
- A free result is a linearized point-in-time account. If free wins a race with the last lease release, it
  reports that generation deferred even if release immediately queues deletion; if release wins, free reports
  the generation fully freed. Resource query uses the same snapshot boundary
- Closing the last lease of a retired generation queues its live handles for deferred deletion. After
  exact-context validation and before operation-specific work, Render Target minting, drawing, resource
  freeing, and live-handle **Renderer Close** drain that queue; validation failure changes nothing
- Resource query, preparation, history clearing, cancellation, Prepared Frame close, and **GPU Object
  Loss** never drain deferred deletion. GPU Object Loss forgets queued handles without deletion, and a
  replacement context cannot delete handles from the lost context
- Deferred deletion failure is `GPU_OPERATION_FAILED` at the invoking operation's stage and aborts that
  operation before its primary work
- Every **Geometry** carries exactly one **Shader Pair**, written in the **Shader Profile**

## Example dialogue

> **Dev:** "If I prepare frames 1 through 30 up front, does preparing frame 30 evict what frame 1
> needs?"
> **Domain expert:** "No. A **Prepared Frame** holds every resource its **Frame Plan** needs for as
> long as it is alive, and identical resources are shared between them rather than duplicated."
>
> **Dev:** "So can I draw frame 12 before frame 5?"
> **Domain expert:** "Yes. A **Prepared Frame** is independent — nothing about it depends on the
> order frames were prepared or drawn in."
>
> **Dev:** "This **Sticker** has a `MAP` position and a `SCREEN` rotation. Which **Draw Regime**
> does it belong to?"
> **Domain expert:** "The map-occluded regime, because position anchoring selects the regime. Its
> screen-anchored rotation changes only its orientation, producing an occluded billboard."

## Flagged ambiguities

- "surface" was used for three distinct things — the caller's framebuffer (**Render Target**), the
  caller's GL context (**Render Context**), and the platform window handle (which RenG never
  touches). Resolved: RenG's API names the first two and has no term for the third.
- "frame" was used for both a rendered image and an animation sample. Resolved: `FramePlan.frameIndex`
  orders strict preparation, while an **Animation Track** selects `timeSeconds`; animation has no frame
  index or FPS contract.
- CLAUDE.md describes the render loop as taking "only a `FramePlan`". Resolved: the render loop
  takes a **Prepared Frame**; see ADR 0002. Preparation ordering is superseded by ADR 0014.
- CLAUDE.md describes geometry shaders as "plain, fully self-contained OpenGL shaders" with no
  RenG-injected preamble. Resolved: RenG substitutes the version directive and nothing else; see
  ADR 0008.
- "free" was used for both "release these resources, reload them on next access" and "these GPU
  objects no longer exist". Resolved: these are separate operations, because the first deletes GL
  objects and the second must not; see ADR 0007. Exact-context deletion enforcement is superseded by
  ADR 0015.
- Rentile 0.1.5 internally retries some tile exchanges and requests removal of invalid stored content.
  Resolved: those are private implementation calls behind an operation-scoped adapter firewall, not
  consumer retries or Store mutations; see ADR 0016.
- "Propagate cancellation unchanged" was read as requiring referentially identical exception objects.
  Resolved: cancellation is never translated into a RenG failure, but Kotlin stack recovery may copy the
  exception while retaining the original as its immediate cause.
- A Prepared Frame was described as independently live while renderer close promised to release everything.
  Resolved: renderer close invalidates every owned frame and target for use while their later cleanup remains
  harmless; see ADR 0017.
- Rentile's resource key removes selected credential-query values while RenG's locator is exact opaque content.
  Resolved: RenG hashes its own versioned canonical root containing the exact locator and redacts only textual
  representations and diagnostics; see ADR 0018.
