# A backdrop a consumer draws itself

ADR 0068 gave a frame a backdrop and made it a repeating image. This gives it a second form: a
shader pair the consumer writes, drawn over the same full-frame quad, in the same slot, under the
same rule that the ground covers it wherever the ground draws.

## This supersedes one paragraph of ADR 0068

That record says, under *What it is not*: "Not a sky. There is no gradient, no atmosphere model, and
no fog." Every word of it was true of the image backdrop and stays true of the image backdrop. It is
no longer true of the feature, because a consumer can now write those things themselves.

The rest of 0068 is untouched and is what makes this cheap. The backdrop still needs no horizon
math, still draws first and full frame, still runs before the empty-scene check so a frame carrying
nothing else still paints, and `null` still means the pass does not run. A shader changes where the
pixels come from and nothing about when or where they land.

## Why this cycle and not the next one

`Backdrop` has never shipped. It has zero lines in the released `0.5.0` ABI dump and all sixteen of
its lines are new on this branch. Reshaping it today costs nothing, because nobody can be holding
it; reshaping it after `0.6.0` publishes is a breaking change to a released coordinate that ADR 0013
makes permanent.

That asymmetry is the entire argument for the timing. The feature is not urgent. The window is.

## A sealed interface, because the alternative permits a state that cannot mean anything

The cheaper change was a nullable `shaderPair` beside `image` on the existing data class. It is
wrong for the ordinary reason: a `Backdrop` carrying both, or neither, is constructible, and the
type then needs a runtime `require` to reject what it should not have been able to express. This
renderer spends a lot of effort keeping illegal states unrepresentable -- `GroundRayResult` is a
sealed hierarchy rather than a nullable distance, and `AnimationSelector` is sealed over a name and
an index rather than carrying one nullable field of each -- and a backdrop that is either a pattern
or a program is exactly that shape.

So `Backdrop` becomes sealed over `Pattern` and `Shader`. `Pattern` is the old data class with the
old two fields and the old behaviour, down to the `GL_REPEAT` sampler and the
`tileSizeLogicalPixels` arithmetic.

`Shader` is not a data class, and that is a redaction decision rather than a style one. A generated
`toString` prints every field, and one of these fields is the consumer's shader source. Every other
type in this codebase that holds shader text hides it -- `ShaderPair.toString()` is
`ShaderPair(<redacted>)`, `Geometry` redacts all three of its shader-bearing fields,
`ShaderProfilePlan` redacts its whole body, and a test asserts a secret in shader source never
reaches a log. A data class here would quietly undo all of it.

## Two uniforms, and a reserved prefix that is worth more than they are

RenG hands the shader `uResolution` and `uFrameIndex`, the names it already documents for consumer
geometry shaders. Nothing camera-derived: not pitch, not bearing, not the horizon's screen row. A
flat pattern needs none of them, and every name RenG documents is a name it must go on meaning the
same thing by.

The interesting half is what that leaves unbuilt. ADR 0008 records, and it is easy to miss, that
adding a documented uniform later is **a breaking change wearing an addition's clothes**: the new
name joins `RESERVED_SHADER_NAMES`, and a consumer already using it for their own uniform stops
being able to construct the object at all. So a minimal surface today buys a hostage tomorrow, and
"we can add the horizon later" is not free the way it sounds.

The answer is to reserve the room rather than fill it. `Backdrop.Shader` rejects any consumer
uniform or texture named in `RESERVED_SHADER_NAMES`, and additionally any name beginning
`rengBackdrop` -- which is already the prefix of RenG's own backdrop uniforms, and is now also the
space a horizon uniform can appear in later without breaking a consumer who never had the right to
that prefix anyway. Reserving a namespace costs one `startsWith` and can only be done before the
first consumer, which is now.

## The consumer writes both stages

`ShaderPair` is vertex and fragment together, and a backdrop pair is no exception. RenG could have
taken only a fragment shader and supplied the quad's vertex stage itself, which would make the
full-screen quad impossible to get wrong -- and it would mean injecting source the consumer did not
write, which ADR 0008 refuses outright and for good reasons that have not changed.

What RenG documents instead is the one thing it binds: a `vec2` clip-space position attribute at
`layout(location = 0)`, bound by location and never by name, exactly as the existing backdrop vertex
shader already declares it. A consumer who writes a vertex stage that puts the quad somewhere else
gets a backdrop somewhere else, which is the same deal every consumer geometry shader already has.

## A consumer's typo must not be reported as RenG's fault

This is the part that is a defect rather than a feature, and it is only reachable once a consumer
can supply the source.

`shaderProgramFailure` branches on the failing key's `kind`. A `GEOMETRY_PROGRAM` key yields
`SHADER_COMPILE_FAILED` or `SHADER_LINK_FAILED` at `PipelineStage.SHADER_COMPILATION`, naming the
`SHADER_PAIR` field. Every other kind -- including the `INTERNAL_PIPELINE` kind a backdrop pipeline
is keyed under -- collapses to `glOperationFailure` at `PipelineStage.GPU_RESOURCE`.

That branch was right when the only backdrop shader was RenG's own, because then a compile failure
genuinely was an internal fault. It becomes wrong the moment the text is the consumer's: a missing
semicolon in their fragment shader would be reported as RenG's GPU resource failing, sending whoever
reads the diagnostic to look at their driver. So the branch widens, and the diagnostic rule that
pins those two codes to `REQUIRED_GEOMETRY_PROGRAM` widens with it.

The pair is also scanned at frame planning, before any GL, the way a consumer geometry shader
already is -- so a source that is not a Shader Profile at all fails as `INVALID_VALUE` at
`FRAME_PLANNING` rather than surviving to the draw.

## One pipeline becomes many, and they are keyed by their source

The backdrop pipeline is a single nullable field created once at renderer setup, which is the right
shape for exactly one program. A consumer shader means a program per distinct `ShaderPair`, so the
backdrop joins `geometryPipelines` in taking a map keyed by `ResourceKey` and cleared, recreated and
deleted on the same three occasions.

The key needs no new machinery: `internalPipeline(role, shaderPair)` already hashes both sources
verbatim alongside the role's wire value, so two different backdrop shaders already derive two
different keys and the same shader reused across frames already derives one. `Pattern` keeps the
singleton, because there is still exactly one of RenG's own program.

The cost is the one `GlProgramCache` always had and never had to face here: it has no eviction, only
explicit removal. A consumer cycling through distinct shader sources grows it without bound. That is
recorded rather than fixed -- it is the same exposure `geometryPipelines` has carried since ADR 0008,
it wants one decision covering both, and inventing a second eviction policy for the smaller of the
two would be the wrong place to start.
