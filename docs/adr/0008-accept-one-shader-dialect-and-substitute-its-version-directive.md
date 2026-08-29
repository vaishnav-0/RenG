# Accept one shader dialect and substitute its version directive

A consumer writes every **Geometry** shader as a GLSL ES 3.00 body beginning with `#version 300 es`.
RenG detects the **Render Context**'s shading language at setup and, on a desktop OpenGL context,
replaces that one line with `#version 330 core` before compiling. Nothing else is added, removed, or
rewritten: no includes, no uniform preamble, no defines, no reordering. On a GLES context the source
compiles byte-for-byte as written. A source whose first non-blank, non-comment line is not
`#version 300 es` is rejected with a typed error rather than guessed at.

No single version directive compiles everywhere, and this is driver-confirmed rather than assumed. On
a headless Apple Silicon core-profile context reporting `4.1 Metal - 90.5`, `#version 300 es` fails
with "version '300' is not supported"; on the legacy 2.1 profile only GLSL 120 is accepted and both
`300 es` and `410 core` fail. But the shader *body* ports intact — a fragment shader using
`precision mediump float;`, `in`/`out`, `texture()`, `textureSize()`, `layout(location = …)`, and
integer uniforms compiles and links unchanged under a substituted directive. The incompatibility is one
line wide, so RenG substitutes one line.

"No injected preamble" does not mean "no interface". RenG documents a fixed set of attribute and
uniform *names*; a **Shader Pair** that wants the model-view-projection matrix or the quad's texture
coordinates declares them itself, exactly as it would in any other OpenGL program, and RenG binds them
by name when the compiled program actually declares them. A shader that declares none of them compiles
and draws; a shader that declares one gets it set. RenG never prepends a line to consumer source, and
the documented names are a contract the consumer opts into rather than text RenG inserts.

The substituted version is `330 core` rather than the `410` macOS reports, because GLSL 330 is the
desktop equivalent of GLSL ES 3.00 and supports everything the profile needs, so a Linux consumer
supplying a GL 3.3 context is served by the same source. This amends CLAUDE.md's description of
geometry shaders as "plain, fully self-contained OpenGL shaders": they remain self-contained in every
respect except the version line, which RenG owns. The alternatives were requiring consumers to supply
one variant per dialect, which duplicates every shader forever, and shipping ANGLE to make GLES3
universal on Apple, which adds per-target native binaries to the published artifact to avoid rewriting
a single line.

## Erratum, 2026-08-29 (Cycle G): a **Shader Pair** survives the globe unchanged, with two narrow breaks

A globe is the first thing RenG draws whose projection is **nonlinear**, and this ADR's promise is written
for a linear one: a consumer writes `gl_Position = uModelViewProjection * aPosition`, and a mercator→sphere
map cannot be expressed as a matrix. The obvious readings of that are that this ADR has to be superseded, or
that consumer geometries do not work on a globe. **Neither is the case, and the reason is worth recording
before the code lands.** RenG subdivides a `Geometry` into a grid and projects its vertices on the CPU, so
what reaches the vertex shader is still a set of positions and still a linear view-projection matrix. A
consumer's existing shader compiles and draws in both modes with no edit, no new declared name, and nothing
prepended. Everything in this ADR's body stands as written.

**What makes that available is a property of `Geometry` rather than a trick.** A `Geometry`'s four resolved
corners are always coplanar: the constructor fixes two distinct latitudes and two distinct longitudes
(`DrawnThings.kt:270-274`), `projectMercator` makes `x` a function of longitude alone and `z` a function of
altitude and latitude alone (`internal/projection/MercatorProjection.kt:43-52`), and `resolveGeometry`
builds the four corners from those two pairs (`internal/planning/GeometryResolver.kt:37-46`) — so the two
northern corners share one `z` and the two southern corners share another, and `z` is an affine function of
`y`. Four such points lie in a plane. Under a projective transform of a plane, an N×N grid lerped between
those corners interpolates every attribute to the same values the four-corner quad already produced, and the
grid's interior edges change no pixel's coverage under the rasterisation fill rule. Subdivision is therefore
output-neutral under mercator, up to floating-point rounding in the last bits, which is what lets RenG
subdivide in **both** modes and keep one code path instead of making the vertex count depend on the
projection mode. That "no mercator pixel moves" claim is the load-bearing one, and it is owed a measurement
rather than this paragraph's reasoning.

**Break one: `gl_VertexID` stops meaning what it did.** `drawGeometry` issues
`drawArrays(GL_TRIANGLE_STRIP, 0, 4)` today (`internal/gl/GeometryPipeline.kt:308`, with
`GEOMETRY_VERTEX_COUNT = 4`), so a shader reading `gl_VertexID` sees 0 through 3 and can identify the quad's
corners from it. After subdivision it sees an index into a grid whose size and ordering RenG chooses and may
change between versions. This ADR's rule — RenG binds a documented name only when the program declares it —
never covered `gl_VertexID`, because it is a language built-in rather than a RenG name, and
`RESERVED_SHADER_NAMES` cannot protect a name RenG does not own. A shader keying off it keeps compiling and
draws wrong, which is the same silent class of failure `GeometryPipeline`'s own SILENT-RENAME HAZARD note
describes. A consumer wanting corner identity should read `aTexCoord`, which subdivision interpolates
correctly by construction.

**Break two: `aPosition.z` means something different in the two modes.** Under mercator it carries altitude
and nothing else — the map plane is at zero, so a shader may read it as height above the ground and be
right. On a globe the same component carries altitude *and* the sphere's curvature together: a vertex at
altitude 0 away from the sub-camera point has a nonzero `z`, growing toward the limb. The magnitude is the
sagitta measured for this cycle — about 27.9 logical pixels across a frame-sized quad at zoom 4, 1.75 at
zoom 8, and 0.44 at zoom 10 — so the break is smooth and zoom-dependent rather than obvious, which is
exactly why it is written down. `aPosition` as a whole is unchanged in kind: it is still the camera-relative
position RenG hands the shader, and `uModelViewProjection` still turns it into clip space.

**A new documented uniform was rejected, and it deserves more space than the decision does, because it is
the option a reader arrives at first.** Handing the shader a uniform carrying the globe transform sounds
like a pure addition. It is not: naming a uniform means adding it to `RESERVED_SHADER_NAMES`
(`internal/gl/GeometryPipeline.kt:45-52`), and that set is enforced at **construction** —
`require(uniforms.keys.none { it in RESERVED_SHADER_NAMES })` and the same check over textures
(`DrawnThings.kt:275-280`) throw `IllegalArgumentException`. So growing the set turns a previously legal
`Geometry` into a constructor failure for any consumer already using that name, with a message about a
reserved name they have never heard of, on a value they have shipped. **That is a breaking change disguised
as an addition**, and it would arrive in a release whose notes say a projection was added. The second
objection is that it does not even buy what it was reached for: a uniform only helps if the consumer's
shader *uses* it, so every existing shader would still draw a flat quad on a sphere, and a shader written to
use it would no longer be one source serving both modes. The whole point of CPU projection is that the
consumer writes one shader and RenG absorbs the difference.

**Prelude injection — MapLibre's actual answer — was rejected for contradicting this ADR outright.**
MapLibre hands a custom layer a `vertexShaderPrelude` string per frame, described as *"the prelude code to
add to the vertex shader to access MapLibre's `projectTile` projection function. Depends on current
projection"*, and injects `projectTile` into its own shaders automatically. That is precisely the "no
includes, no uniform preamble" this ADR forbids, and it would have to be inserted *after* the version
directive, making a second source-rewriting rule where this ADR deliberately has one. The decisive objection
is not the contradiction, though — it is that the breakage would be **silent**. This ADR binds a documented
name only when the compiled program declares it, so a shader that ignores the injected prelude keeps
compiling, keeps linking and keeps drawing: a flat quad on a globe, no error, no warning, wrong pixels. The
failure mode this ADR was written to prevent is the exact failure mode MapLibre's answer would produce here.

Nothing else in the shader interface moves. `uModelViewProjection`, `uResolution`, `uFrameIndex` and
`aTexCoord` keep their meanings; `uGeometryBounds` stays informational, and its existing warning against
deriving a vertex position from it applies with more force on a sphere, where latitude is even further from
linear across a patch.
