# Use versioned canonical bytes for content identities

Frame and resource identities use a dependency-free canonical binary encoding rather than data-class hashes,
JSON, platform serialization, or Rentile's credential-redacted URL key. Every root begins ASCII `RNGC`, schema
version byte `1`, and one root-kind byte: frame `1`, external resource `2`, geometry program `3`, internal
pipeline `4`, or offscreen surface `5`. A root or nested object is a strictly increasing sequence of unique
fields encoded as unsigned 16-bit big-endian tag, unsigned 32-bit big-endian payload length, and payload.
The Cycle B specification owns the permanent field-tag table for every Frame Plan field.

Strings are exact UTF-8 with no Unicode, URL, or path normalization. Enums have explicitly declared unsigned
16-bit wire values. Booleans are one byte. Fixed integers are big-endian. A Double is finite IEEE-754 binary64
in big-endian order after negative zero is canonicalized to positive zero. Lists carry an unsigned 32-bit
count and each element's unsigned 32-bit byte length; order and duplicates remain content. Optional fields
carry an explicit presence byte, so later fields never change position when a value is absent. Malformed,
duplicate, out-of-order, truncated, or overflowing encodings fail rather than decode or allocate.

A Frame Identity is `reng-frame-v1:` plus lowercase SHA-256 over the complete frame root. A Resource Key's
stable id is lowercase SHA-256 over its resource root. The external-resource preimage includes the external
kind, exhaustive Resource Class, and exact Resource Locator. Geometry-program identity includes the shader
profile version and exact vertex and fragment sources, including whitespace. Pipeline and offscreen roots
are domain-separated now, but their owning cycles must freeze their descriptor fields before those resource
kinds can appear; the throwaway descriptor fields used to prove root separation are not product contracts.

The registry retains a defensive copy of canonical bytes beside every digest. Equal bytes may share work. If
one digest names different bytes, RenG fails `IDENTITY_COLLISION` without replacing the first entry or sharing
state. This check remains mandatory even though a SHA-256 collision is not expected in normal operation.

A pure common-Kotlin SHA-256 implementation was selected because the six published targets have no shared
platform crypto API and adding a crypto dependency solely for internal identities is unnecessary. Known-answer
vectors and the complete 1,431-byte representative Frame Plan encoding produced the same identity on Android
host and macOS, while all six target sources compiled and the public ABI remained free of platform-crypto and
Rentile types.

JSON was rejected because equivalent parser/writer settings, escaping, and number formatting would become an
implicit compatibility contract. Platform hashes were rejected because they are not collision-resistant or
cross-target stable. Rentile's key was rejected because it deliberately removes credential query values,
while RenG's exact opaque Resource Locator is content identity and only diagnostics—not identity—redact it.

## Erratum, 2026-08-28 (Cycle E-labels): the Frame Plan tag table gains an eighth row

This ADR states above that the Cycle B specification owns the permanent field-tag table for every Frame
Plan field. That table pinned tags 1 through 7 — `FRAME_INDEX(1)`, `CAMERA(2)`, `PROJECTION_MODE(3)`,
`DRAW_BASEMAP(4)`, `STICKERS(5)`, `MODELS(6)`, `GEOMETRIES(7)`. Cycle E-labels adds `FramePlan.drawLabels`
and therefore an eighth: **`DRAW_LABELS(8)`**.

It is recorded here rather than in the Cycle B specification because that specification is a historical
decision record, and this project appends errata rather than rewriting them. A reader looking for the
current table should read the Cycle B specification's seven rows together with this erratum's eighth.

**Tag 8 rather than tag 5, and the difference matters.** `drawLabels` is declared beside `drawBasemap` in
`FramePlan`'s constructor, where it belongs for a reader, but it is *encoded last*. Taking tag 5 — the
position that mirrors the declaration order — would have renumbered `STICKERS`, `MODELS` and `GEOMETRIES`,
and this ADR's requirement that a root's fields appear in strictly increasing tag order makes renumbering a
change to every previously computed Frame Plan identity. The tags are permanent; declaration order is not
part of the contract and never was. So the two orders deliberately differ, and the enum carries a comment
saying why, because a future reader will otherwise read the mismatch as an oversight and "fix" it.

**The tag table is append-only, and this is the first time that has been exercised.** A field removed in
some later cycle retires its tag rather than freeing it for reuse.

The two checked-in canonical fixtures each grow by the same seven bytes for the new segment — the minimal
plan from 141 to 148, the representative one from 1,471 to 1,478. Their digests were re-derived outside
RenG with an independent SHA-256 implementation, validated by first reproducing the two existing digests
byte-exactly, rather than copied from what the changed code emitted. A digest taken from the code under
test proves only that the code agrees with itself.

## Erratum, 2026-08-30 (Cycle E-terrain): two nested tag tables grow, and one had already drifted

ADR 0040 gives `Placement` and `Geometry` an `AltitudeMode` each, and a Frame Identity that ignored it
would serve a `GROUND_RELATIVE` frame the cached `ABSOLUTE` one. So both nested objects gain a field, and
both take **the next free tag and encode last**, for the reason the 2026-08-28 erratum gives at the root:
tags are permanent, a root's fields must appear in strictly increasing tag order, and renumbering is
therefore a change to every previously computed identity. **`Placement` gains tag 7**, after `scale(6)`.
**`Geometry` gains tag 6.**

**On `Geometry` the declaration order and the tag order happen to agree, and that is luck rather than
policy.** The field is declared last on the public type too — but for an unrelated reason. `Geometry` is a
`data class`, so a field declared between `bottomRight` and `shaderPair`, where it reads best, would
renumber `component3` through `component5` and silently re-bind every consumer that destructures one. Two
different constraints, both satisfied by "last". `Placement` is not a data class and has no `componentN`,
but its six-argument constructor is positional in three shipped releases' worth of code, so the seventh
parameter is appended for the same practical reason.

**Cycle F-1 had already taken `Geometry` tags 4 and 5 without recording them anywhere.** The Cycle B
specification pins `Geometry` as `topLeft=1/bottomRight=2/shaderPair=3`; `uniforms(4)` and `textures(5)`
arrived with consumer uniforms and textures and appear only in the encoder's own source. The current table
is therefore `topLeft=1, bottomRight=2, shaderPair=3, uniforms=4, textures=5, altitudeMode=6` — read the
Cycle B specification's three rows together with this paragraph, not on their own. Nothing is wrong with
the bytes; the *record* was incomplete, which is exactly the failure mode an append-only table exists to
prevent.

The minimal checked-in fixture does not move at all — 148 bytes, digest unchanged — because its sticker,
model and geometry lists are empty and the new fields live inside those. The representative one grows by
**48 bytes, 1,478 to 1,526**: four `Placement`s (two stickers and two models) and two `Geometry`s, each
paying one 8-byte field. Its digest was re-derived outside RenG with Python's `hashlib`, by a script that
first reproduced both tracked digests byte-exactly and round-tripped both fixtures through its own
parser — so the expectation is not a transcription of what the changed code emitted.
