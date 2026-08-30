# Take kotlinx-serialization-core for Frame Plan documents

`FramePlan` and every public type it reaches carry `@Serializable`, RenG declares
`org.jetbrains.kotlinx:kotlinx-serialization-core` as its own dependency with the version recorded
in RenG's version catalogue, `kmp/build.gradle.kts` applies the
`org.jetbrains.kotlin.plugin.serialization` compiler plugin, and `tools/check_repository_policy.py`
is amended to permit exactly that one coordinate and exactly that one plugin while continuing to
forbid every other library and plugin it lists. **This is the third first-party production
dependency RenG has ever taken** — after Rentile itself and ADR 0019's coroutines — and the fourth
coordinate overall counting ADR 0032's test-only runner. Each of those has an ADR for the same
reason this one does: a coordinate is permanent, and the argument gets easier every time it is made.

**It is `-core` only, and that is the whole shape of the commitment.** The `@Serializable`
annotation, the `KSerializer` interface and the serializers the plugin generates all live in
`kotlinx-serialization-core`; JSON, CBOR and ProtoBuf are three separate artifacts that RenG does
not take. So what RenG publishes is a **description of its own types — field names, shapes,
nullability and defaults — and no wire format at all.** The harness reads JSON because a developer
has to be able to open a corpus plan and hand-edit a camera; a rendering server can put the same
plans on the network as ProtoBuf; an Android client can write CBOR into a data store. All three come
from one set of annotations, and the format dependency is chosen by whoever needs it rather than
imposed by RenG on everyone.

**The size research is worth recording because it inverts the reasoning that usually settles this.**
Measured on a realistic 600-frame plan array — ten seconds at 60 fps, two stickers, a model and a
geometry per frame:

| encoding | 600 frames |
|---|---|
| JSON, pretty | 2.2 MiB |
| JSON, compact | 1.4 MiB |
| canonical binary TLV, from the 1,526 B/frame ADR 0018's representative fixture actually measures | 0.9 MiB |
| **JSON, compact + gzip** | **37 KiB** |

**Compression beats the binary format by 24×.** The reason is specific rather than general: **41% of
the payload is byte-identical GLSL repeated in every frame**, sitting on top of enormous inter-frame
redundancy in cameras and placements. A binary encoding saves 1.6× *per frame* and can do nothing
about repetition *across* frames; a compressor saves 40× precisely because the repetition is what it
eats. A compressed binary format would beat compressed text only slightly. So size does not decide
the format here, and once size stops deciding, **debuggability decides** — which is why the harness
reads JSON and why RenG itself names no format.

**Reusing RenG's own canonical TLV encoding was the serious alternative and it was genuinely
attractive.** `internal/identity/FramePlanCanonicalEncoding.kt` already encodes every `FramePlan`
field to tagged bytes; ADR 0018 already makes the field-tag table permanent and append-only, with
two errata's worth of practice at extending it; and it needs no dependency, no plugin and no policy
amendment. It is rejected on two counts. The first is that **it is unreadable in a log, in a diff
and in a review** — a corpus plan is a document a human writes, reads and argues about, and a
tag-length-value stream is none of those things. The second is measured rather than argued:
**`CanonicalBinary` is write-only.** Every one of its eleven functions encodes, there is no
`CanonicalFieldReader`, and there is no decode path anywhere in `kmp/src`; ADR 0018's sentence about malformed
encodings failing "rather than decode or allocate" describes a property of the *format*, not shipped
code, and its own errata re-derive fixture digests with a Python parser written outside RenG.
Adopting TLV therefore means writing the decoder RenG has never had, and then every cross-language
consumer writes it again by hand. `kotlinx-serialization-core` is the reader as well as the writer,
in every language that has a JSON, CBOR or ProtoBuf library.

**None of this reverses ADR 0018, and the distinction matters.** That ADR rejected JSON *for content
identity*, because equivalent parser settings, escaping and number formatting would become an
implicit compatibility contract behind a digest. Identity keeps its canonical bytes and keeps its
permanent tag table; this decision is about a **document a human authors and a tool transports**,
which is a different job with a different failure mode. The two encodings coexist deliberately, and
a `FramePlan` that round-trips through JSON must still produce the same Frame Identity, because
identity is computed from the reconstructed object and not from the transport bytes.

**Mirror DTOs in the harness were rejected, and the cycle's own history is the argument.** A
hand-written `HarnessFramePlan` needs no dependency at all and keeps RenG's ABI untouched — and it
reintroduces exactly the drift that serialising the real types eliminates. `Placement.altitudeMode`
landed in `e0de84d` on 2026-08-30, **the day before this cycle's design was written**; a mirror DTO
built the week before would have decoded a corpus plan, dropped the field on the floor, rendered an
`ABSOLUTE` frame where the document said `GROUND_RELATIVE`, and **passed**. A corpus exists to make
a frame fully specified by its document. A DTO layer is a second place for a field to not exist.

**Field names become public API, and `checkKotlinAbi` cannot see them.** The ABI dump records the
Kotlin property name, so renaming `zoom` moves the dump and is reviewed; it records nothing about a
`@SerialName` string, so **changing the wire name while keeping the Kotlin name moves nothing and is
caught by no gate**. Two obligations follow, and they are the price of the decision rather than a
nicety. Every serialised property carries an **explicit `@SerialName`**, so the wire form is never a
by-product of a Kotlin identifier. And a **checked-in JSON fixture must round-trip
byte-identically**, in exactly the shape ADR 0018 already uses for the two canonical-identity
fixtures whose digests must not move — that fixture is the gate, and a diff to it is a public-API
change whatever the ABI dump says. A test that fails when any `FramePlan`-reachable public property
lacks a `@SerialName` closes the third hole, which is a field added later by someone who did not
read this.

**Every consumer carries it, on all six targets, with no opt-out — and that is structural rather
than lazy.** RenG is deliberately one published coordinate (rentile ADR 0002: KMP publication does
not fold unpublished project dependencies into the aggregate artifact, so an extra Gradle module
would break the single-coordinate guarantee). There is no `:reng-serialization` to make this
optional and there cannot be one without giving up the property that makes RenG resolvable from a
single line. What softens it is measured: **Rentile `0.7.0`'s own aggregate POM already declares
`kotlinx-serialization-json 1.11.0` at `runtime` scope**, and JSON depends on core, so the artifact
is already on every RenG consumer's runtime classpath today. What changes is that it becomes
compile-visible and that **RenG owns the version** instead of inheriting whatever a Rentile patch
release moves it to — the same reasoning, on the same evidence, that ADR 0019 gave for not relying
on the transitive copy of coroutines. The licence is Apache-2.0, so the licence check is unaffected,
as it was for the previous two.

**The policy gate refuses this change in five places, not the three the design predicted, and every
one is measured.** `_FORBIDDEN_DEPENDENCY` (`tools/check_repository_policy.py:64`) rejects the word
**`serialization`** by name, case-insensitively — the rule was written to keep exactly this out — so
`_PERMITTED_NEW_DEPENDENCIES` (`:72`) widens from one coordinate to two.
`_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` (`:256`) pins `gradle/libs.versions.toml` and
`kmp/build.gradle.kts` at **three accepted token-stream forms each**, and a fourth must be added to
both; these are digests over the token stream `_build_configuration_fingerprint` derives, **not
whole-file SHA-256**, whatever the older prose says, and the three historical forms cannot be
recomputed from disk because they are fixtures in `tools/tests/test_check_repository_policy.py`.
Then the plugin, which the cycle's design and plan both underprice: `_EXPECTED_PLUGIN_BLOCKS`
(`:126`) pins the **exact token sequence** of the `plugins { }` block in `build.gradle.kts`,
`kmp/build.gradle.kts` and `settings.gradle.kts`, and `_PLUGIN_ACCESSORS` (`:146`) lists the three
permitted `libs.plugins.*` accessors; both must name the serialization plugin. And if the root
`build.gradle.kts` declares it `apply false` in the shape the other three plugins use, that file's
fingerprint set — which today holds **exactly one** accepted form — gains a second.

**The plugin is a genuinely new kind of cost, though not in the way "a first plugin" would
suggest.** Three Gradle plugins are already applied. What is new is a plugin that **generates code
into the published artifact**: `serializer()` methods and `Companion` objects that appear in
`kmp/api/kmp.klib.api` and ship in every klib and the AAR. A build-orchestration plugin that
vanishes at runtime and a compiler plugin that writes public members are different commitments, and
only the second can change what a consumer links against. One thing offsets it: the plugin is
versioned in lockstep with Kotlin, so the catalogue's `[plugins]` entry can point its `version.ref` at
the existing `kotlin` row, and only the runtime coordinate needs a `[versions]` row of its own — one
fewer version to drift.

**The cost is that a gate built to fail closed is one notch shorter again, and that is accepted
rather than unnoticed.** ADR 0019 amended a single named coordinate rather than the rule's
principle; ADR 0032 did the same thing one scope over and said plainly that the argument gets easier
each time. This is the third, and the honest reading is unchanged: the record of *having made the
argument* is the point. The rule stays exact. `serialization` is still a forbidden word for every
artifact but the one named; `-json`, `-cbor` and `-protobuf` are all still refused; the plugin
allowlist still names every plugin it admits; and a fourth production dependency still meets a
closed door and still needs an ADR explaining why it opened.
