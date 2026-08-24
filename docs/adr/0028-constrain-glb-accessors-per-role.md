# Constrain GLB accessors per role

ADR 0021 fixed the supported GLB subset before anything decoded a byte of it, and it closes with the
argument that makes this ADR necessary: widening the accepted subset later is a compatible change and
narrowing it is not. Its accept list names the component types `5120`–`5123`, `5125` and `5126` as one
flat set across every accessor. That is the right granularity for a parser deciding whether accessor
arithmetic is decidable, and the wrong one for a renderer deciding what it binds. The specification
itself constrains by role: a `POSITION` is a `VEC3` of floats, an `indices` accessor is an unsigned
`SCALAR`, a keyframe time is a float `SCALAR`, a quaternion output is a `VEC4`. Under the flat rule a
document with a `SCALAR`/`BYTE` `POSITION`, a `MAT4` `TEXCOORD_0`, or a `VEC3` rotation output passed
both gates as fully supported, and the renderer would have had no defined behaviour for any of them.
Every accessor now carries the constraint its role implies. This narrows what the gates accept, which is
exactly why it lands before the first release that draws a model rather than after.

Where a rule lands follows ADR 0021's own split, which is about which true statement the consumer is
told. Two of the new rules are unconditional specification violations no extension relaxes, so they are
`PARSE_GLB` malformation: an `indices` accessor that is not an unnormalized unsigned `SCALAR`, and a
`TRIANGLES` primitive whose index count — or vertex count, when it is not indexed — is not a multiple of
three. So is a `matrix` on a node an animation channel targets, which the specification forbids because
the channel writes one of translation, rotation or scale and a baked matrix has none of the three to
write into; that is a contradiction between two parts of one document, not a feature RenG declines to
draw. The per-semantic *attribute* and *animation sampler* formats go the other way, to
`VALIDATE_GLB_FEATURES`, for one specific reason: `KHR_mesh_quantization` makes several of those
combinations specification-legal, `EXTENSION_REQUIRED` is checked first and unconditionally, and ADR 0021
already warns that reporting malformation for a file whose real problem is an unsupported extension tells
the consumer the wrong thing. A quantized asset is still diagnosed by the extension it declares.

A primitive with no `POSITION` is also a feature refusal rather than malformation, and that is a
correction to how the rule was originally proposed. ADR 0021 already lists `POSITION` as required, but the
specification does not: it says a client "SHOULD skip" such a primitive, and it contemplates an extension
supplying positions by other means. RenG refuses instead of skipping, because drawing part of a model and
reporting success is the same silent fallback ADR 0021 rejects for an unresolvable `uri`. So the document
is valid and RenG cannot draw it, which is precisely what the second gate exists to say.

Separately, and not a subset question at all, `PARSE_GLB` now proves the arithmetic it already claimed to
prove. A JSON integer is signed, and every span check in that gate is a "does it fit" comparison, so a
negative `byteOffset`, `byteLength`, `byteStride` or `count` made a computed span *smaller* and passed
every test while addressing bytes before the start of its buffer view; an `accessor.count` of zero, whose
schema minimum is one, did the same through `count - 1`. Large non-negative values did it the other way,
by overflowing `Long` into a negative sum. Both are now refused, the span and buffer-view fit checks are
stated as subtractions and a division that cannot overflow, and an accessor's `min`/`max` must carry one
value per component — a wrong-length `max` previously switched the declared-index-value check off
silently rather than failing. None of this widens or narrows the subset; it is the guarantee
`GltfDocument` always documented, made true before anything slices a byte range out of consumer-supplied
bytes.

The Khronos sample corpus was re-run across all of it, the same measurement
`docs/research/2026-08-19-glb-feature-subset-corpus-check.md` records. No asset changed verdict: the
same count is supported before and after, with every rejection landing on the same code as before. That
is the evidence these rules close holes rather than move the boundary, and it is the check to repeat if
any of them is revised.

## Erratum, 2026-08-24: five of the seven owed follow-ups are closed, two are not

This ADR left seven named follow-ups. Cycle F-2 closed five of them, and the two that remain are open by
decision rather than by omission.

**Closed.** An animation sampler whose `output.count` disagrees with its `input.count` is now
`GltfReject.ANIMATION_SAMPLER_COUNTS` — honouring `CUBICSPLINE`'s three-values-per-keyframe form, so a legal
cubic-spline asset is still refused as an unsupported *feature* rather than reported as a corrupt file, which
would have blurred ADR 0021's split. Two channels in one animation driving the same `(node, path)` is
`GltfReject.DUPLICATE_ANIMATION_CHANNEL_TARGET`. Sampler filter and wrap values outside the specification's
own enumerations are `GltfUnsupported.SAMPLER_STATE`, refused rather than clamped. A
`baseColorTexture.texCoord` other than `0` is `GltfUnsupported.TEXTURE_COORDINATE_SET`, because RenG binds
`TEXCOORD_0` and only `TEXCOORD_0`. And keyframe `input` values that are not strictly increasing are refused
by the decoder, which is where that check has to live because it needs the BIN chunk's bytes — as does the
one it grew alongside, a non-finite keyframe time, since `NaN` is caught by the ordering comparison but
`+Infinity` passes it and turns the interpolation fraction into `inf/inf`.

**Still open, and why.** `min`/`max` *presence* on a `POSITION` accessor is a specification `MUST` that RenG
does not enforce, because RenG never reads either: requiring them would reject assets that draw perfectly for
data no draw call touches, which is the same mistake the extra-UV-set rejection turned out to be. And an
unbounded `buffers[i > 0].byteLength` is moot rather than fixed — `GltfUnsupported.MULTIPLE_BUFFERS` refuses
any document declaring a second buffer at all, so there is no `i > 0` to bound.

One rule this ADR's own vocabulary gained: a referenced skin's `inverseBindMatrices` accessor takes
`GltfUnsupported.SKIN_ACCESSOR_FORMAT` rather than `ATTRIBUTE_FORMAT`. An inverse bind matrix is not a vertex
attribute, and this file's stated principle — written for `COMPONENT_TYPE` against `ACCESSOR_TYPE` — is that
codes easy to confuse by name may not share one, because conflating them sends a consumer debugging by code
name to the wrong field.

And one silent repair, found while wiring the model pipeline and closed on the spot: `alphaMode` was an
unvalidated free-form string, so a misspelt or future value fell through to the `OPAQUE` default and rendered
a transparent material solid with nothing said. The specification types it as an enumeration of exactly
three, so a fourth is `GltfReject.ALPHA_MODE` at `PARSE_GLB`. An absent member is untouched: there, `OPAQUE`
is the specification's own default rather than a guess.
