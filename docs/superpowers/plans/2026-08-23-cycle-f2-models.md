# Cycle F-2 — Models with Textures and Animation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw GLB models — textured, animated, and deformed by their own baked skinning — inside the map regime, with real occlusion.

**Architecture:** The structural half of glTF is already built and the numeric half has never been started. `parseGltf` produces a real scene graph and no code in RenG has ever read a byte of the BIN chunk. So a new pure package, `internal/model/`, decodes accessors, composes node transforms, samples animation and assembles a draw-ready model; `internal/gl/ModelPipeline.kt` uploads and draws it; and `RenGRenderer` wires the two together the way it already wires stickers and the ground.

**Tech Stack:** Kotlin Multiplatform, six targets, `com.rohittp.rentile:kmp` for the basemap beneath.

**Spec:** `docs/superpowers/specs/2026-08-23-cycle-f2-models-design.md`

## Global Constraints

- Keep exactly six targets. Keep `explicitApi()`. This cycle grows the public ABI by **exactly three enum entries**: `RenGErrorCode.UNSUPPORTED_ANCHORING_MODE`, `ResourceKind.MODEL_GEOMETRY`, `ResourceKind.MODEL_IMAGE`. Nothing else may appear in the `kmp.klib.api` diff.
- Add no new Gradle subproject and no cinterop definition on any target. `internal/model/` is a package, not a module (rentile ADR 0002).
- Standard library plus `kotlinx-coroutines` only, alongside `com.rohittp.rentile:kmp`. `kotlinx.serialization`, `okio` and any other third-party dependency are FORBIDDEN.
- **RenG is pure.** No network I/O, no persistent cache. Embedded GLB buffers and images only; an external or `data:` URI is already rejected and stays rejected.
- **Never forward messages or causes from injected adapters** — they can carry signed URLs.
- Keep cancellation as an unwrapped `CancellationException`, checked and rethrown before any generic catch.
- No retries, repairs, or fallbacks.
- Every Gradle invocation passes `--no-configuration-cache`.
- **A model shader is RenG-internal and must not extend the documented consumer shader interface.** `RESERVED_SHADER_NAMES` (`internal/gl/GeometryPipeline.kt:45`) is the public contract ADR 0008 fixes; every model shader name is `reng`-prefixed and private, exactly as `rengSticker*` and `rengGround*` are.
- **`RecordingGlBinding.getUniformLocation` returns `-1` for undeclared names**, so a test that forgets to declare a name sees zero calls and **can pass while asserting nothing**. Declare every name you expect bound. Never assert `assertEquals(before, captureGlState(...))` against that fake — it cannot fail.
- Every test in this plan is written against the call-record shape `RecordingGlBinding` already has. Where a snippet's accessor names differ from the file's, **adapt the assertion, never the fake** — the fake's shape is shared by every existing GL test.
- Fixtures must be **asymmetric** and assertions **portable**. GitHub's hosted macOS runners have no GPU and report `Apple Software Renderer`; an exact pixel count is not a portable claim. State the smallest defect each tolerance still detects.
- `ResidentCache` uses a **non-reentrant** spinlock: never call a `locked{}` method from inside the lock.

## Execution order

Five tasks have no dependency on each other and may run in parallel first: **1, 3, 7, 11, 13**.
Then **2** (after 1), **4** (after 2), **5** (after 3), **8** (after 7), **15** (after 11 — same ABI diff).
Then **6** (after 4, 5), **9** (after 2, 4).
Then **10** (after 7, 8, 9), then **12**, then **14** — 12 and 14 both rewrite `SceneContent.kt` and must be
sequential, not parallel — then **16** (after 6, 10, 12, 14, 15).
Then **17** (after 16), **18** (after 17).

Tasks 1, 3, 7, 11 and 13 touch disjoint files and are the natural first parallel wave.

---

## Task 1: Ignore extra UV and colour sets instead of rejecting them

`TEXCOORD_1` alone rejects **14 of the consumer's 41 models, and is the sole reason for 10 of them**
(`docs/research/2026-08-22-consumer-model-corpus-check.md`), for carrying data RenG never reads: it samples
one texture, and `baseColorTexture.texCoord` is `0` in every material in the corpus. This is the largest win
per unit of work in the cycle. Widening a subset is a compatible change under ADR 0021's own reasoning, so it
lands first and alone.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfFeatures.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GltfFeaturesTest.kt`

**Interfaces:**
- Produces: `GltfUnsupported.TEXTURE_COORDINATE_SET`; `SUPPORTED_ATTRIBUTE_SEMANTICS` replaced by a predicate
  admitting `TEXCOORD_n` and `COLOR_n` for every non-negative `n`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun anExtraTextureCoordinateSetIsIgnoredRatherThanRejected() {
    val document = documentWithPrimitiveAttributes(
        "POSITION" to vec3Float(), "TEXCOORD_0" to vec2Float(), "TEXCOORD_1" to vec2Float(),
    )
    assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(document))
}

@Test fun anExtraColourSetIsIgnoredRatherThanRejected() {
    val document = documentWithPrimitiveAttributes(
        "POSITION" to vec3Float(), "COLOR_0" to vec4Float(), "COLOR_1" to vec4Float(),
    )
    assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(document))
}

@Test fun anIgnoredSetIsNotFormatCheckedBecauseRenGNeverBindsIt() {
    // TEXCOORD_1 as an unnormalized MAT4 of bytes is nonsense RenG will never read.
    val document = documentWithPrimitiveAttributes(
        "POSITION" to vec3Float(), "TEXCOORD_1" to accessor(type = "MAT4", componentType = 5120),
    )
    assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(document))
}

@Test fun anApplicationSpecificSemanticIsStillRejected() {
    val document = documentWithPrimitiveAttributes("POSITION" to vec3Float(), "_BATCHID" to vec3Float())
    assertEquals(GltfFeatureResult.Unsupported(GltfUnsupported.ATTRIBUTE_SEMANTIC), validateGltfFeatures(document))
}

@Test fun aBaseColourTextureNamingASecondCoordinateSetIsRejected() {
    val document = documentWithBaseColourTexture(texCoord = 1)
    assertEquals(GltfFeatureResult.Unsupported(GltfUnsupported.TEXTURE_COORDINATE_SET), validateGltfFeatures(document))
}
```

The third test is the load-bearing one and the easiest to get wrong: "ignored" must mean *not format-checked*,
not "format-checked and then discarded". A set RenG never reads cannot have a format RenG cannot bind.

- [ ] **Step 2: Run and confirm each fails for the stated reason, then implement**

Replace `SUPPORTED_ATTRIBUTE_SEMANTICS` with `private fun isSupportedSemantic(name: String): Boolean`
admitting exactly `POSITION`, `NORMAL`, `TANGENT`, and `TEXCOORD_`/`COLOR_` followed by a canonical
non-negative decimal integer with no leading zero (`TEXCOORD_01` is not a glTF semantic and stays rejected).
`bindsAttribute` keeps its per-role table for the five semantics RenG reads and returns `true` — admits
without checking — for every ignored set, with a KDoc sentence saying why.

`TEXTURE_COORDINATE_SET` covers a material whose `pbrMetallicRoughness.baseColorTexture.texCoord` is not `0`:
RenG binds `TEXCOORD_0` and only `TEXCOORD_0`, so a material asking for another set has no correct render.
This is one of ADR 0028's seven named follow-ups, closed here.

- [ ] **Step 3: Mutation-check**

Make `isSupportedSemantic` return `true` unconditionally and confirm the `_BATCHID` test fails. A widening
that widens to everything is not the widening this task asks for.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(kmp): ignore the UV and colour sets RenG never reads"
```

---

## Task 2: Skins, and the owed structural rules

**25 of the 41 consumer models declare a skin, but only 7 are truly vertex-skinned** — two elephants at 112
joints, three motorbikes at 44, two dinosaurs at 38. The other 18 carry a `skins` array no node references.
RenG already rejects on `node.skin != null` rather than on the array's presence, so those 18 pass today;
this task keeps that and admits the 7.

It also closes three more of ADR 0028's owed follow-ups, because they are all in these two files and a second
pass over them costs another review round.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfDocument.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfParse.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfFeatures.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GltfParseTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GltfFeaturesTest.kt`

**Interfaces:**
- Consumes: Task 1's `isSupportedSemantic`.
- Produces: `GltfSkin(inverseBindMatrices: Int?, joints: List<Int>, skeleton: Int?)`, `GltfDocument.skins`,
  `MAXIMUM_SKIN_JOINTS`, `GltfReject.ANIMATION_SAMPLER_COUNTS`, `GltfReject.DUPLICATE_ANIMATION_CHANNEL_TARGET`,
  `GltfUnsupported.MULTIPLE_SKIN_INFLUENCE_SETS`, `GltfUnsupported.SKIN_JOINT_COUNT`,
  `GltfUnsupported.SKINNED_PRIMITIVE_ATTRIBUTES`, `GltfUnsupported.SAMPLER_STATE`. Removes
  `GltfUnsupported.SKIN`.

- [ ] **Step 1: Write the failing parse tests**

```kotlin
@Test fun aSkinIsRetainedWithItsJointsAndInverseBindMatrices() {
    val parsed = parseGltf(json(SKINNED_DOCUMENT), binChunkLength = 4096L, maximumNodeDepth = 128)
    val document = (parsed as GltfParseResult.Parsed).document
    assertEquals(listOf(1, 2, 3), document.skins.single().joints)
    assertEquals(0, document.skins.single().inverseBindMatrices)
}

@Test fun aSkinJointNamingNoNodeIsMalformed() { … GltfReject.INDEX_OUT_OF_RANGE … }

@Test fun aSkinWithNoJointsIsMalformed() { … GltfReject.SIZE_FIELD_OUT_OF_RANGE … }

@Test fun anAnimationSamplerWhoseOutputCountDisagreesWithItsInputIsMalformed() {
    // input.count = 4 keyframe times, output.count = 3 values: undecidable.
    … GltfReject.ANIMATION_SAMPLER_COUNTS …
}

@Test fun twoChannelsInOneAnimationDrivingTheSameNodeAndPathAreMalformed() {
    … GltfReject.DUPLICATE_ANIMATION_CHANNEL_TARGET …
}
```

`SIZE_FIELD_OUT_OF_RANGE`'s KDoc gains a clause for an empty `skin.joints`: it is the same class of fault it
already names — a declared size outside the schema's own range — and the consumer's next action is identical.

- [ ] **Step 2: Write the failing feature tests**

```kotlin
@Test fun aSkinnedNodeIsNoLongerRejected() {
    assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(skinnedDocument(joints = 38)))
}

@Test fun aSecondInfluenceSetIsRejected() {
    // JOINTS_1/WEIGHTS_1 means more than four influences per vertex; RenG binds one set.
    assertEquals(
        GltfFeatureResult.Unsupported(GltfUnsupported.MULTIPLE_SKIN_INFLUENCE_SETS),
        validateGltfFeatures(skinnedDocument(influenceSets = 2)),
    )
}

@Test fun aRigBeyondTheUniformBlockCapacityIsRejected() {
    assertEquals(
        GltfFeatureResult.Unsupported(GltfUnsupported.SKIN_JOINT_COUNT),
        validateGltfFeatures(skinnedDocument(joints = MAXIMUM_SKIN_JOINTS + 1)),
    )
}

@Test fun aSkinnedNodeWhoseMeshCarriesNoWeightsIsRejected() {
    assertEquals(
        GltfFeatureResult.Unsupported(GltfUnsupported.SKINNED_PRIMITIVE_ATTRIBUTES),
        validateGltfFeatures(skinnedDocument(attributes = listOf("POSITION", "JOINTS_0"))),
    )
}

@Test fun theEighteenExporterLeftoverSkinsStillPass() {
    // A `skins` array no node references is exporter debris, not a skinned model.
    assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(documentWithUnreferencedSkin()))
}

@Test fun aSamplerFilterOutsideTheGlEnumerationIsRejected() {
    assertEquals(GltfFeatureResult.Unsupported(GltfUnsupported.SAMPLER_STATE), validateGltfFeatures(
        documentWithSampler(minFilter = 1234),
    ))
}
```

The last-but-one test is the one that would be silently lost. Rejecting on `document.skins.isNotEmpty()`
instead of on `node.skin != null` would refuse 18 drawable models for dead data — the exact inversion the
corpus check found RenG had already avoided.

- [ ] **Step 3: Implement**

`MAXIMUM_SKIN_JOINTS = 256`. GLES 3.0 and GL 3.3 both guarantee `GL_MAX_UNIFORM_BLOCK_SIZE` of at least
16384 bytes, which is exactly 256 `mat4`. The corpus's largest rig is 112, so this is 2.3× headroom against
the largest asset the consumer actually ships. Capping lower was rejected on the same measurement that
rejected a uniform *array*: any cap that fits a uniform array is below what the consumer already ships.

Per-role accessor formats for the two new semantics, added to `bindsAttribute` and enforced only when the
attribute is actually bound:
- `JOINTS_0`: `VEC4`, `UNSIGNED_BYTE` or `UNSIGNED_SHORT`, **not** `normalized`.
- `WEIGHTS_0`: `VEC4`, plain float or normalized `UNSIGNED_BYTE`/`UNSIGNED_SHORT`.
- `skin.inverseBindMatrices`: `MAT4` plain float, checked in `validateAccessors`' neighbourhood.

`SAMPLER_STATE` covers a `magFilter` outside `{9728, 9729}`, a `minFilter` outside
`{9728, 9729, 9984, 9985, 9986, 9987}`, or a `wrapS`/`wrapT` outside `{33071, 33648, 10497}`. Rejected rather
than clamped: substituting a filter silently changes the picture, which is the fallback ADR 0021 refuses
everywhere else.

Delete `GltfUnsupported.SKIN` and its KDoc paragraph. The check-ordering note it carried — `SKIN` before
`ATTRIBUTE_SEMANTIC` so a skinned mesh is diagnosed by "remove the skin" — is obsolete now that skins are
admitted, and leaving it would document behaviour that no longer exists.

**Two rejections stay exactly as they are, by decision rather than omission.** `MORPH_TARGET` stays, because
**zero of the 41 consumer models carry one** — deferring them is measured, not assumed. `IMAGE_MEDIA_TYPE`
stays, so the one model with a JPEG texture is refused by a named code rather than silently: a decoder RenG
does not have is not worth building for a single asset, and the cheaper fix is upstream. That is the whole of
why this cycle reaches **40 of 41 models** rather than 41. Neither enum entry is touched by this task; both
get a sentence in their KDoc saying they were re-decided here, so a later reader does not read them as
unfinished.

- [ ] **Step 4: Mutation-check the joint cap**

Raise `MAXIMUM_SKIN_JOINTS` past the test's value and confirm `aRigBeyondTheUniformBlockCapacityIsRejected`
fails. A cap the GL layer will later rely on for its array size must be enforced here or the overflow becomes
a driver crash instead of a typed refusal.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(kmp): admit vertex skinning and close four owed GLB rules"
```

---

## Task 3: A quaternion, its slerp, and two matrix inverses

`LINEAR` rotation sampling is spherical linear interpolation of quaternions, and glTF node rotations are
quaternions. `DoubleMatrix3` offers only Euler `rotationXyzDegrees`, which is the wrong parameterization,
and nothing in `internal/math/` inverts a matrix. Skinning needs both inverses: `mat3` for the normal matrix
and `mat4` for the skeleton-root term of a joint matrix.

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/math/DoubleQuaternion.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/math/DoubleLinearAlgebra.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/math/DoubleQuaternionTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/math/DoubleLinearAlgebraTest.kt`

**Interfaces:**
- Produces: `DoubleQuaternion(x, y, z, w)` with `normalized()`, `toRotationMatrix(): DoubleMatrix3`,
  `companion.slerp(from, to, t): DoubleQuaternion`; `DoubleMatrix3.inverse(): DoubleMatrix3?`;
  `DoubleMatrix4.inverseAffine(): DoubleMatrix4?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aQuaternionRotationMatrixMatchesTheEquivalentEulerRotation() {
    val ninetyAboutZ = DoubleQuaternion(0.0, 0.0, sin(PI / 4), cos(PI / 4))
    assertMatrixNear(DoubleMatrix3.rotationZDegrees(90.0), ninetyAboutZ.toRotationMatrix(), 1e-12)
}

@Test fun slerpTakesTheShortestArcWhenTheDotProductIsNegative() {
    val from = DoubleQuaternion(0.0, 0.0, 0.0, 1.0)
    val to = DoubleQuaternion(0.0, 0.0, -sin(PI / 4), -cos(PI / 4))   // 90 deg about -z, spelled the long way
    val half = DoubleQuaternion.slerp(from, to, 0.5)
    // The shortest arc is 45 degrees about +z, not 135 the other way.
    assertMatrixNear(DoubleMatrix3.rotationZDegrees(45.0), half.toRotationMatrix(), 1e-9)
}

@Test fun slerpFallsBackToNormalizedLerpForNearlyParallelInputs() {
    val from = DoubleQuaternion(0.0, 0.0, 0.0, 1.0)
    val to = DoubleQuaternion(1e-9, 0.0, 0.0, 1.0).normalized()
    val half = DoubleQuaternion.slerp(from, to, 0.5)
    assertTrue(half.toRotationMatrix()[0, 0].isFinite(), "a vanishing sin(theta) must not divide by zero")
}

@Test fun slerpAtTheEndpointsReturnsTheEndpoints() { … t = 0.0 and t = 1.0, exactly … }

@Test fun anAffineInverseUndoesATranslateRotateScale() {
    val m = translate(3.0, -4.0, 5.0) * DoubleMatrix4.fromRotation(rotationZDegrees(37.0)) * scale(2.0, 0.5, 3.0)
    assertMatrixNear(DoubleMatrix4.identity, m * m.inverseAffine()!!, 1e-10)
}

@Test fun anAffineInverseOfASingularTransformIsNull() {
    assertNull((scale(1.0, 0.0, 1.0)).inverseAffine(), "a zero-scale node has no inverse and must say so")
}
```

The shortest-arc test is the one that fails silently in production: a rig whose keyframes cross the
quaternion double-cover renders the model spinning the long way round between two frames, which reads as an
animation authoring bug rather than a renderer bug.

- [ ] **Step 2: Implement**

`slerp` negates `to` when `from · to < 0`, and falls back to normalized lerp when `|from · to| > 0.9995`.
`inverseAffine` inverts the 3×3 block by adjugate over determinant and applies `-inv * translation`; both
inverses return `null` rather than an infinity when `|det|` is below `1e-12`. A `null` is what lets the
caller fail typed instead of uploading a matrix of `NaN`.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(kmp): add a quaternion, its slerp, and affine matrix inverses"
```

---

## Task 4: Read the BIN chunk

**No code in RenG has ever read a byte of the BIN chunk**; `parseGltf` takes `binChunkLength` and never the
bytes. This is the numeric half's foundation.

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/model/AccessorReader.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/model/AccessorReaderTest.kt`

**Interfaces:**
- Consumes: `GltfDocument`, `GltfAccessor`, `GltfBufferView`, `GlbScan.Admitted.binChunk`.
- Produces:
  - `internal class BinChunk(private val bytes: ByteArray, private val range: IntRange)`
  - `internal fun BinChunk.readFloatElements(document: GltfDocument, accessorIndex: Int): FloatArray?`
  - `internal fun BinChunk.readIndices(document: GltfDocument, accessorIndex: Int): ModelIndices?`
  - `internal fun BinChunk.readMatrices(document: GltfDocument, accessorIndex: Int): List<DoubleMatrix4>?`
  - `internal class ModelIndices(val shorts: ShortArray?, val ints: IntArray?, val glComponentType: Int, val count: Int)`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aTightlyPackedVec3FloatAccessorReadsItsComponentsInOrder() { … }

@Test fun anInterleavedAccessorRespectsItsBufferViewStride() {
    // One view, stride 20: POSITION at offset 0, TEXCOORD_0 at offset 12.
    assertContentEquals(floatArrayOf(0f, 1f, 2f, 10f, 11f, 12f), chunk.readFloatElements(document, POSITION))
    assertContentEquals(floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f), chunk.readFloatElements(document, TEXCOORD))
}

@Test fun aNormalizedUnsignedShortTexCoordDequantizesToTheUnitRange() {
    // 0 -> 0.0, 65535 -> 1.0, 32767 -> 32767/65535
    assertEquals(1.0f, chunk.readFloatElements(document, accessor)!![1], 1e-7f)
}

@Test fun aNormalizedSignedShortRotationDequantizesWithTheSpecificationsClamp() {
    // glTF: max(c / 32767, -1), so -32768 clamps to exactly -1.0 rather than -1.000031.
    assertEquals(-1.0f, chunk.readFloatElements(document, accessor)!![0])
}

@Test fun unsignedByteIndicesAreWidenedToUnsignedShort() {
    val indices = chunk.readIndices(document, accessor)!!
    assertEquals(GL_UNSIGNED_SHORT, indices.glComponentType, "byte indices are not drawable on desktop core")
    assertContentEquals(shortArrayOf(0, 1, 2), indices.shorts)
}

@Test fun unsignedIntIndicesStayThirtyTwoBit() { … GL_UNSIGNED_INT, ints … }

@Test fun anAccessorWhoseSpanFallsOutsideTheActualBinChunkReturnsNull() {
    // parseGltf proved the span fits the *declared* buffer length. This proves it fits the real bytes.
    assertNull(shortChunk.readFloatElements(document, accessor))
}

@Test fun aMatrixAccessorReadsGltfColumnMajorOrder() {
    // glTF stores MAT4 column-major; a row-major read transposes every inverse bind matrix silently.
    assertEquals(13.0, chunk.readMatrices(document, accessor)!!.single()[0, 3])
}
```

The last test is the one an asymmetric fixture exists for. A transposed inverse bind matrix produces a model
that is *deformed plausibly* rather than obviously broken, and a symmetric fixture cannot tell the two apart —
the same trap this project already shipped once, in a suite of exact-URL assertions.

- [ ] **Step 2: Implement**

Element stride is `bufferView.byteStride ?: (componentBytes * componentsPerElement)`. Every read starts at
`bufferView.byteOffset + accessor.byteOffset` and is bounds-checked against the **actual** chunk range, not
the declared buffer length: `parseGltf` validated the declaration, and a truncated BIN chunk is a different
fault it never saw. Dequantization follows the specification's own table exactly, including the signed clamp.

`UNSIGNED_BYTE` indices widen to `ShortArray`. glTF permits them; desktop GL core profiles do not draw them.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(kmp): read typed elements out of a GLB BIN chunk"
```

---

## Task 5: Compose node transforms

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/model/NodeTransforms.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/model/NodeTransformsTest.kt`

**Interfaces:**
- Consumes: `DoubleQuaternion`, `GltfDocument`, `GltfNode`.
- Produces: `internal data class NodeTrs(val translation: DoubleVector3, val rotation: DoubleQuaternion, val scale: DoubleVector3)`;
  `internal fun localNodeTrs(node: GltfNode): NodeTrs?` (`null` for a `matrix` node);
  `internal fun localNodeTransform(node: GltfNode, override: NodeTrs?): DoubleMatrix4`;
  `internal fun composeGlobalTransforms(document: GltfDocument, overrides: Map<Int, NodeTrs>): List<DoubleMatrix4?>`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aTrsNodeComposesTranslateTimesRotateTimesScale() {
    // Order matters: T*R*S scales in local axes, S*R*T does not. Use a non-uniform scale and a
    // non-zero rotation so the two orders differ.
}

@Test fun aMatrixNodeIsReadColumnMajorStraightFromTheDocument() { … }

@Test fun aChildTransformIsTheParentTimesItsOwnLocal() { … }

@Test fun aNodeOutsideTheDefaultSceneHasNoGlobalTransform() {
    assertNull(composeGlobalTransforms(document, emptyMap())[unreachableNode])
}

@Test fun anAnimationOverrideReplacesOnlyTheChannelsItDrives() {
    // A track driving only `rotation` must leave the node's authored translation and scale alone.
}
```

- [ ] **Step 2: Implement**

A pre-order walk of `defaultScene ?: scenes.single()`. `validateNodeGraph` already proved the graph is a set
of disjoint strict trees with bounded depth, so the walk terminates without its own cycle guard — say so in
the KDoc rather than adding a redundant one.

`composeGlobalTransforms` returns one entry per document node, `null` for a node the default scene does not
reach. Nodes outside the scene are legal glTF and draw nothing.

**Precision decision, made deliberately rather than by accident.** Node transforms compose in `Double` and
narrow once, where the camera-space model matrix already narrows. `SceneContent`'s KDoc makes a precision
promise about camera-relative values; a model's node transforms are model-local and small, so `Float` would
have been defensible — but composing in `Double` costs nothing measurable per model and keeps one rule
("narrow once, at the uniform") rather than two.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(kmp): compose glTF node transforms into global matrices"
```

---

## Task 6: Resolve and sample animation

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/model/ModelAnimation.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/model/ModelAnimationTest.kt`

**Interfaces:**
- Consumes: `BinChunk`, `NodeTrs`, `DoubleQuaternion.slerp`, `AnimationSelector`, `AnimationTrack`.
- Produces:
  - `internal sealed interface AnimationResolution { data class Resolved(val indices: List<Int>); data object Missing; data object Duplicate }`
  - `internal fun resolveAnimationSelectors(document: GltfDocument, tracks: List<AnimationTrack>): AnimationResolution`
  - `internal fun animationDurationSeconds(document: GltfDocument, bin: BinChunk, animationIndex: Int): Double?`
  - `internal fun sampleAnimationTracks(document, bin, tracks, resolved): Map<Int, NodeTrs>?`

- [ ] **Step 1: Write the failing resolution tests**

`CONTEXT.md`: "Preparation rejects a missing or out-of-range selector, or different selectors that resolve to
the same animation."

```kotlin
@Test fun aNameSelectorResolvesByExactUnicodeScalarMatch() { … no normalization, no trimming … }
@Test fun anIndexSelectorBeyondTheCatalogueIsMissing() { … AnimationResolution.Missing … }
@Test fun twoSelectorsNamingOneAnimationAreDuplicate() {
    // Name("Walk") and Index(0) both resolving to animation 0.
    assertEquals(AnimationResolution.Duplicate, resolveAnimationSelectors(document, tracks))
}
```

- [ ] **Step 2: Write the failing sampling tests**

```kotlin
@Test fun aPositiveDurationAnimationWrapsTimeByItsDuration() {
    // CONTEXT.md: `timeSeconds % durationSeconds`. Duration is the largest input across ALL of that
    // animation's samplers, which is why it cannot be known without reading the BIN chunk.
    assertEquals(sampleAt(0.5), sampleAt(2.5), "duration 2.0: t=2.5 must equal t=0.5")
}

@Test fun aZeroDurationAnimationSamplesTimeZero() { … }

@Test fun eachSamplerClampsIndependentlyToItsOwnInputRange() {
    // An animation whose first keyframe is at t=10 holds that value from t=0, per the specification,
    // and a sibling sampler starting at t=0 does not.
}

@Test fun stepInterpolationTakesTheEarlierKeyframe() { … }

@Test fun linearRotationInterpolatesSphericallyRatherThanComponentwise() {
    // 0 and 180 degrees about z, halfway: slerp gives 90 degrees; a componentwise lerp gives a
    // degenerate near-zero quaternion that normalizes to something else entirely.
}

@Test fun laterTracksWinWhenTwoTracksDriveTheSameNodeAndPath() {
    // CONTEXT.md: "A model applies its animation tracks in list order."
}

@Test fun aChannelWithNoTargetNodeIsSkipped() { … legal glTF, defined as a no-op … }

@Test fun aNonIncreasingKeyframeInputSequenceIsRejected() {
    // The seventh of ADR 0028's owed follow-ups, and it belongs here because it needs the bytes.
    assertNull(sampleAnimationTracks(document, binWithUnsortedInputs, tracks, resolved))
}

@Test fun aNodeTargetedByAChannelMustCarryTrsRatherThanAMatrix() {
    // Already GltfReject.ANIMATED_NODE_MATRIX at parse; asserted here so the sampler's assumption is
    // pinned to the gate that guarantees it rather than left implicit.
}
```

- [ ] **Step 3: Implement**

Binary-search the `input` accessor for the bracketing pair. Clamp the wrapped time into
`[firstInput, lastInput]` **per sampler**. `STEP` takes the earlier output; `LINEAR` lerps componentwise for
`translation`/`scale` and slerps for `rotation`. Normalized-integer rotation outputs are dequantized by
Task 4 before slerp, so nothing here re-implements that.

Return `null` on any content fault the parse gate could not see — a non-increasing input sequence, a read
that falls outside the real BIN chunk. The caller turns `null` into a typed
`RESOURCE_PARSE_FAILED` at `RESOURCE_PARSING`.

- [ ] **Step 4: Mutation-check the wrap**

Replace `timeSeconds % durationSeconds` with `timeSeconds` and confirm the wrap test fails. Then remove the
per-sampler clamp and confirm the independent-clamp test fails. Both are rules whose absence produces a
plausible animation rather than a broken one.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(kmp): resolve animation selectors and sample tracks at a time"
```

---

## Task 7: Grow the GL seam for uniform buffers, and add the missing tokens

Four entry points and eleven tokens. Each is trivial individually and each touches **four platform bindings
plus `GlEntryPoint` plus the recording fake plus the roster test**, so they land in one task rather than
being rediscovered four times.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlEntryPoint.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlTokens.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshot.kt`
- Modify: `kmp/src/androidMain/.../AndroidGlBinding.kt`, `iosMain/.../IosGlBinding.kt`,
  `linuxMain/.../LinuxGlBinding.kt`, `macosMain/.../MacosGlBinding.kt`
- Modify: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBinding.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlEntryPointRosterTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshotTest.kt`

**Interfaces:**
- Produces on `GlBinding`:
  - `fun bindBufferBase(target: Int, index: Int, buffer: Int)`
  - `fun getUniformBlockIndex(program: Int, name: String): Int`
  - `fun uniformBlockBinding(program: Int, blockIndex: Int, bindingPoint: Int)`
  - `fun getIntegeri_v(pname: Int, index: Int, out: IntArray)`
- Produces tokens: `GL_UNSIGNED_SHORT` `0x1403`, `GL_REPEAT` `0x2901`, `GL_MIRRORED_REPEAT` `0x8370`,
  `GL_NEAREST_MIPMAP_NEAREST` `0x2700`, `GL_LINEAR_MIPMAP_NEAREST` `0x2701`,
  `GL_NEAREST_MIPMAP_LINEAR` `0x2702`, `GL_LINEAR_MIPMAP_LINEAR` `0x2703`,
  `GL_MAX_UNIFORM_BLOCK_SIZE` `0x8A30`, `GL_MAX_VERTEX_UNIFORM_BLOCKS` `0x8A2B`, `GL_FRONT` `0x0404`,
  `GL_CW` `0x0900`.
- Produces: `RENG_JOINT_UNIFORM_BINDING_POINT: Int = 0` and its capture/restore in `GlStateSnapshot`.

- [ ] **Step 1: Update the roster test first**

```kotlin
@Test fun rosterHasExactlyNinetyOneEntryPoints() { assertEquals(91, GlEntryPoint.entries.size) }

@Test fun theRosterContainsTheFourUniformBlockEntryPointsSkinningNeeds() {
    val cNames = GlEntryPoint.entries.map { it.cName }
    assertTrue("glBindBufferBase" in cNames)
    assertTrue("glGetUniformBlockIndex" in cNames)
    assertTrue("glUniformBlockBinding" in cNames)
    assertTrue("glGetIntegeri_v" in cNames)
}
```

`everyCNameIsDistinctAndWellFormed` asserts `name.all { it in 'a'..'z' || 'A'..'Z' || '0'..'9' }`, and
`glGetIntegeri_v` contains an underscore. Widen that character class to admit `_` and say why in the test —
`glGetIntegeri_v` is the C name, and renaming it to pass a test would make the roster lie about the symbol
it resolves.

- [ ] **Step 2: Extend the Restore Set, and prove it**

`glBindBufferBase(GL_UNIFORM_BUFFER, n, b)` writes **both** the generic binding — already captured as
`GL_UNIFORM_BUFFER_BINDING` — and the indexed binding at point `n`, which is not captured at all. That
indexed binding is caller state RenG is about to clobber.

This is **applying** ADR 0023, not amending it: the Restore Set is defined as the state RenG writes, and
RenG is newly writing this. Capture and restore the indexed `GL_UNIFORM_BUFFER_BINDING` at
`RENG_JOINT_UNIFORM_BINDING_POINT` via `getIntegeri_v`.

```kotlin
@Test fun theIndexedUniformBufferBindingIsCapturedAndRestored() {
    val binding = RecordingGlBinding().apply { indexedUniformBuffer[0] = 77 }
    withCapturedGlState(binding, profile, FRAME_TEXTURE_UNIT_COUNT) {
        binding.bindBufferBase(GL_UNIFORM_BUFFER, 0, 5)
    }
    assertEquals(77, binding.indexedUniformBuffer[0], "a caller's own UBO binding must survive a RenG frame")
}
```

- [ ] **Step 3: Implement the four entry points on all four platform bindings and the fake**

`RecordingGlBinding` records each call and models the indexed binding array, so Step 2's test can fail.

- [ ] **Step 4: Confirm the token values against a second source**

Every added token must be checked against the Khronos registry value, not against another RenG constant.
`tokensThatDifferBetweenDialectsAreNotFolded` gains the four mipmap filters and `GL_REPEAT`.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(kmp): add uniform-block entry points and the tokens models need"
```

---

## Task 8: Sampler state that does not render black

**Every sampler in the consumer's corpus asks for `LINEAR_MIPMAP_LINEAR`.** Honouring that filter without
generating mipmaps leaves the texture incomplete, and **an incomplete texture samples black** — the identical
failure a previous cycle hit on sticker textures. 41 samplers omit `wrapS`/`wrapT`, which the specification
defines as `REPEAT`, while `uploadTexture` hardcodes `CLAMP_TO_EDGE`.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlTextureUpload.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlTextureUploadTest.kt`

**Interfaces:**
- Consumes: Task 7's tokens; `GltfSampler`.
- Produces: `internal data class TextureSamplerState(val minFilter: Int, val magFilter: Int, val wrapS: Int, val wrapT: Int)`;
  `internal fun gltfSamplerState(sampler: GltfSampler?): TextureSamplerState`;
  `uploadTexture(binding, image, content, sampler: TextureSamplerState = defaultFor(content))`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aMipmapMinificationFilterGeneratesTheChainItRequires() {
    uploadTexture(binding, image, TextureContent.IMAGE, TextureSamplerState(
        minFilter = GL_LINEAR_MIPMAP_LINEAR, magFilter = GL_LINEAR, wrapS = GL_REPEAT, wrapT = GL_REPEAT,
    ))
    assertTrue(binding.calls.any { it.entryPoint == GlEntryPoint.GENERATE_MIPMAP },
        "a mipmap filter with no chain is an incomplete texture and samples black")
}

@Test fun aNonMipmapFilterGeneratesNoChain() { … no GENERATE_MIPMAP call … }

@Test fun theChainIsGeneratedAfterTheLevelZeroUpload() {
    // Order is load-bearing: generateMipmap before texImage2D builds a chain from nothing.
    assertTrue(indexOf(TEX_IMAGE_2D) < indexOf(GENERATE_MIPMAP))
}

@Test fun anAbsentGltfSamplerTakesTheSpecificationsOwnDefaults() {
    // The spec's default wrap is REPEAT; the spec leaves filters undefined and RenG picks LINEAR.
    assertEquals(TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT), gltfSamplerState(null))
}

@Test fun everyExistingCallerKeepsClampToEdgeAndNoChain() {
    // Stickers, ground and consumer data textures must be bit-identical to before this task.
}
```

- [ ] **Step 2: Implement**

The new parameter defaults to exactly today's behaviour per content kind — `GL_LINEAR`/`GL_CLAMP_TO_EDGE`
for `IMAGE`, `GL_NEAREST`/`GL_CLAMP_TO_EDGE` for `DATA` — so the existing three call sites are untouched and
the last test proves it. `GltfSampler.wrapS`/`wrapT` already default to `10497` at parse, and Task 2 already
refused any enum outside the GL set, so `gltfSamplerState` maps straight through with no validation of its own.

Update `uploadTexture`'s KDoc: the paragraph asserting "No mipmap chain is generated — an explicit non-mipmap
minification filter is the correct fix on its own" is now true only of the default, and leaving it would
document the opposite of what the function does.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(kmp): honour glTF sampler state, mipmap chain included"
```

---

## Task 9: Assemble a decoded model

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/model/ModelDecode.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/model/ModelDecodeTest.kt`

**Interfaces:**
- Consumes: `scanGlb`, `parseGltf`, `validateGltfFeatures`, `BinChunk`, `decodePng`, `TextureSamplerState`.
- Produces:
  - `internal class DecodedModel(val primitives: List<DecodedPrimitive>, val drawItems: List<ModelDrawItem>, val images: List<DecodedImage>, val skins: List<DecodedSkin>, val document: GltfDocument, val bin: BinChunk, val decodedCpuBytes: Long)`
  - `internal class DecodedPrimitive(val positions: FloatArray, val normals: FloatArray?, val texCoords: FloatArray?, val colours: FloatArray?, val joints: FloatArray?, val weights: FloatArray?, val indices: ModelIndices, val material: ResolvedMaterial)`
  - `internal class ModelDrawItem(val nodeIndex: Int, val meshIndex: Int, val primitiveIndex: Int, val skinIndex: Int?)`
  - `internal class ResolvedMaterial(val baseColourFactor: FloatArray, val baseColourImageIndex: Int?, val baseColourSampler: TextureSamplerState, val alphaMode: String, val alphaCutoff: Float, val doubleSided: Boolean)`
  - `internal class DecodedSkin(val jointNodes: List<Int>, val inverseBindMatrices: List<DoubleMatrix4>, val skeletonRoot: Int?)`
  - `internal sealed interface ModelDecodeResult { Success | Malformed | Unsupported | TooLarge }`
  - `internal fun decodeModel(bytes: ByteArray, limits: ResourceLimits): ModelDecodeResult`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun everyMeshBearingNodeOfTheDefaultSceneBecomesADrawItem() { … including two nodes sharing one mesh … }

@Test fun aPrimitiveWithNoMaterialTakesGltfsOwnDefaultMaterial() {
    // glTF defines it: white base colour, metallic 1, roughness 1, OPAQUE, single-sided.
    // RenG had no representation of it at all before this task.
    assertContentEquals(floatArrayOf(1f, 1f, 1f, 1f), primitive.material.baseColourFactor)
    assertEquals("OPAQUE", primitive.material.alphaMode)
    assertFalse(primitive.material.doubleSided)
}

@Test fun aBaseColourTextureResolvesThroughMaterialTextureImageToADecodedImage() {
    // Four hops, all parsed and index-checked today and none of them executed:
    // material -> pbrMetallicRoughness.baseColorTexture -> textures[i].source -> images[j].bufferView.
    assertEquals(0, primitive.material.baseColourImageIndex)
    assertEquals(2, model.images[0].width)
}

@Test fun aTextureWithNoSamplerTakesTheSpecificationDefaults() { … gltfSamplerState(null) … }

@Test fun aVec3ColourAttributeIsWidenedToVec4WithOpaqueAlpha() { … }

@Test fun anUnindexedPrimitiveGetsAGeneratedIndexRun() {
    // One code path downstream, not two: every primitive draws with drawElements.
    assertContentEquals(shortArrayOf(0, 1, 2), primitive.indices.shorts)
}

@Test fun decodedCpuBytesCountsTheExpandedArraysRatherThanTheStoredOnes() {
    // De-interleaving and dequantizing costs up to four times the stored bytes, and
    // ResourceUsage.decodedCpuBytes is supposed to carry the expanded figure.
    assertTrue(model.decodedCpuBytes > storedGeometryBytes)
}

@Test fun aModelWhoseExpandedFormExceedsTheDecodedImageBudgetIsTooLarge() { … }

@Test fun aContainerFaultAParseFaultAndAFeatureFaultAreThreeDistinctResults() { … }
```

- [ ] **Step 2: Implement**

**RenG de-interleaves.** Each attribute becomes its own tightly packed `FloatArray` and, later, its own VBO.
The alternative — one VBO per buffer view with stride and offset bookkeeping — is what the seam supports and
is not what this cycle should spend its review budget on: one packed array per attribute removes a whole
class of stride bugs, and the cost is bounded because external URIs are rejected, so total geometry is
bounded by `maximumModelGlbBytes`. The up-to-4× expansion is real and is exactly what `decodedCpuBytes`
reports.

**Joints are decoded to `FloatArray` and bound as `GL_FLOAT`,** not as an integer attribute. A joint index of
111 is exact in `Float`, `glVertexAttribPointer` converts integer client data to float for free, and the
shader indexes with `int(...)`. This is why `vertexAttribIPointer` is absent from Task 7's seam growth: it is
not needed, and adding an entry point to four platform bindings for nothing is the kind of cost this project
counts.

Unindexed primitives get a generated `0..n-1` index run so the draw path has exactly one shape.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(kmp): assemble a draw-ready model from GLB bytes"
```

---

## Task 10: The model pipeline

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/ModelPipeline.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneLight.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/CompositePipeline.kt` (`InternalPipelineRole`)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/ModelPipelineTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/SceneLightTest.kt`

**Interfaces:**
- Consumes: Tasks 7, 8, 9; `GlProgramCache`, `ResourceKeyDeriver.internalPipeline`.
- Produces:
  - `internal data class ModelShaderVariant(val skinned: Boolean, val hasBaseColourTexture: Boolean, val masked: Boolean)`
  - `internal fun allModelShaderVariants(): List<ModelShaderVariant>`
  - `internal fun createModelPipeline(binding, dialect, cache, variant): ModelPipelineResult`
  - `internal class ModelPipeline(val key: ResourceKey, val program: Int, …uniform locations…, val jointBlockIndex: Int)`
  - `internal class UploadedPrimitive(val vertexArray: Int, val buffers: List<Int>, val indexBuffer: Int, val indexCount: Int, val indexType: Int)`
  - `internal fun uploadModelPrimitive(binding, primitive: DecodedPrimitive): UploadedPrimitive`
  - `internal fun deleteUploadedPrimitive(binding, uploaded: UploadedPrimitive)`
  - `internal class ResolvedModelPrimitive(val uploaded: UploadedPrimitive, val modelViewProjection: FloatArray, val normalMatrix: FloatArray, val material: ResolvedMaterial, val baseColourTexture: Int?, val jointMatrices: FloatArray?, val reverseWinding: Boolean)`
  - `internal class ResolvedModel(val primitives: List<ResolvedModelPrimitive>)` — the model-side twin of
    `ResolvedSticker`, produced by `SceneContent` and consumed by `drawModels`
  - `internal fun drawModels(binding, pipelines: Map<ModelShaderVariant, ModelPipeline>, models: List<ResolvedModel>, lightDirectionCameraSpace: FloatArray)`
  - `internal val SCENE_LIGHT_DIRECTION_ENU: DoubleVector3`; `internal const val SCENE_LIGHT_AMBIENT: Float`;
    `internal const val SCENE_LIGHT_DIFFUSE: Float`
  - `internal fun sceneLightDirectionCameraSpace(camera: ResolvedMercatorCamera): FloatArray`
- `InternalPipelineRole` gains `MODEL(4)`. The wire value is part of a canonical identity (ADR 0018), so it
  is **appended**, never renumbered.

- [ ] **Step 1: Write the failing light tests**

ADR 0026 fixes the direction; this task fixes the two numbers the ADR left to implementation and records them.

```kotlin
@Test fun theLightPointsFromTheNorthWestAtFortyFiveDegrees() {
    // Azimuth 335 deg clockwise from north, elevation 45 deg, as a unit direction *toward* the light
    // in east/north/up: (sin(az)cos(el), cos(az)cos(el), sin(el)).
    val d = SCENE_LIGHT_DIRECTION_ENU
    assertEquals(-0.29886, d.x, 1e-5); assertEquals(0.64086, d.y, 1e-5); assertEquals(0.70711, d.z, 1e-5)
    assertEquals(1.0, sqrt(d.dot(d)), 1e-12)
}

@Test fun theAmbientAndDiffuseTermsSumToOneSoAFullyLitSurfaceReachesItsOwnColour() {
    assertEquals(1.0f, SCENE_LIGHT_AMBIENT + SCENE_LIGHT_DIFFUSE, 1e-6f)
}

@Test fun theLightStaysPutInTheWorldAsTheCameraOrbits() {
    // World-anchored (ADR 0026): the camera-space direction must rotate with bearing, which is exactly
    // what keeps the lit side of a model facing the same compass direction.
    val north = sceneLightDirectionCameraSpace(cameraAt(bearing = 0.0))
    val east = sceneLightDirectionCameraSpace(cameraAt(bearing = 90.0))
    assertFalse(north.contentEquals(east), "a camera-anchored light would give the same vector at both")
}
```

`SCENE_LIGHT_AMBIENT = 0.35f`, `SCENE_LIGHT_DIFFUSE = 0.65f`. ADR 0026 committed to "an ambient term" without
a value; these are it, and the plan records them here so they are one decision in one place rather than a
constant discovered in a shader.

`sceneLightDirectionCameraSpace` builds the view basis from `camera.right`/`cameraUp`/`cameraBack` exactly as
`PlacementResolver` already does, and multiplies. Those three vectors are expressed in the camera anchor's
ENU frame, so multiplying by the basis converts a world-ENU direction into camera space — which is precisely
world anchoring, since the light vector itself never changes.

- [ ] **Step 2: Write the failing pipeline tests**

```kotlin
@Test fun theVariantCountIsBoundedAtEight() {
    assertEquals(8, allModelShaderVariants().size, "skinned x textured x masked, and nothing else")
}

@Test fun everyModelShaderNameIsRengPrefixedAndNoneIsReserved() {
    modelShaderNames().forEach {
        assertTrue(it.startsWith("reng"))
        assertFalse(it in RESERVED_SHADER_NAMES, "a model shader must not grow the public shader contract")
    }
}

@Test fun aSkinnedVariantBindsItsJointBlockToRengsOwnBindingPoint() {
    assertTrue(binding.calls.any { it.entryPoint == GlEntryPoint.UNIFORM_BLOCK_BINDING })
}

@Test fun anUnskinnedVariantDeclaresNoJointBlockAtAll() {
    // A 16 KB uniform block on every static model is a cost with no purpose.
}

@Test fun aPrimitiveUploadsOneBufferPerPresentAttributeAndNoneForAbsentOnes() { … }

@Test fun aSingleSidedMaterialEnablesCullingAndADoubleSidedOneDisablesIt() {
    // doubleSided is true on 109 of 111 corpus materials, which happens to match today's behaviour --
    // drawFrame sets a cull mode but never enables GL_CULL_FACE -- so only the 2 need this.
}

@Test fun aMirroredNodeTransformReversesTheWindingOrder() {
    // glTF requires it when the global transform's determinant is negative. Missing it produces
    // inside-out models with no error at all.
    assertTrue(binding.calls.any { it.entryPoint == GlEntryPoint.FRONT_FACE && it.args == listOf(GL_CW) })
}

@Test fun blendedPrimitivesDrawAfterOpaqueOnesAndSortedBackToFront() {
    // 6 materials across 6 corpus models. Two passes per model: opaque with depth writes, then
    // blended sorted back-to-front with writes off.
}

@Test fun anOpaquePrimitiveWritesAlphaOneSoTheCompositeDoesNotFadeIt() {
    // The offscreen surface is RGBA and the composite blends by alpha.
}
```

- [ ] **Step 3: Implement**

One `ShaderPair` per variant, each authored as `#version 300 es` and compiled through
`ShaderProfilePlan.sourceFor` exactly as the sticker and ground pairs are, so each variant gets its own
`ResourceKey` from `internalPipeline(InternalPipelineRole.MODEL, pair)` and `GlProgramCache` handles the rest.

Attributes: `rengModelPosition` (`vec3`), `rengModelNormal` (`vec3`), `rengModelTexCoord` (`vec2`),
`rengModelColour` (`vec4`), `rengModelJoints` (`vec4`), `rengModelWeights` (`vec4`).
Uniforms: `rengModelViewProjection` (`mat4`), `rengModelNormalMatrix` (`mat4`, of which the shader takes
`mat3(...)` — that is why Task 7 adds no `uniformMatrix3fv`), `rengModelBaseColourFactor` (`vec4`),
`rengModelBaseColourTexture` (`sampler2D`), `rengModelAlphaCutoff` (`float`),
`rengModelLightDirection` (`vec3`), `rengModelAmbient` (`float`).
Uniform block: `RengModelJoints { mat4 rengModelJointMatrices[256]; }`, bound at
`RENG_JOINT_UNIFORM_BINDING_POINT`.

`createModelPipeline` reads `GL_MAX_UNIFORM_BLOCK_SIZE` once and fails with
`glOperationFailure(PipelineStage.GPU_RESOURCE, key)` if the context reports below 16384 — a typed refusal
rather than a link error whose message RenG must not forward.

Joint matrix for joint `i` of a skin attached to node `n`:
`inverseAffine(global[n]) * global[jointNodes[i]] * inverseBindMatrices[i]`. A `null` from `inverseAffine` is
a typed failure, never an identity substitution.

- [ ] **Step 4: Mutation-check the two silent ones**

Remove the winding reversal and confirm the mirrored-transform test fails. Remove the opaque alpha write and
confirm its test fails. Both produce a picture that looks like a *content* problem rather than a renderer
problem, which is why they get mutation checks and the others do not.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(kmp): compile, upload and draw glTF primitives"
```

---

## Task 11: Refuse a `SCREEN`-positioned model

ADR 0029 currently exists as a decision with **no code behind it**. This task implements it.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/Exceptions.kt` (`RenGErrorCode`)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/DiagnosticFactories.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/MercatorSpatialPlanner.kt`
- Modify: `kmp/api/kmp.klib.api` (regenerated, reviewed as a diff)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/MercatorSpatialPlannerTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/DiagnosticsAndFailuresTest.kt`

**Interfaces:**
- Produces: `RenGErrorCode.UNSUPPORTED_ANCHORING_MODE`; `DiagnosticField.PLACEMENT_POSITION_MODE`
  (`"placement.positionMode"`), allowlisted at `FRAME_PLANNING`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aScreenPositionedModelIsRefusedAtFramePlanning() {
    val outcome = planMercatorSpatial(planWithScreenPositionedModel(), …)
    val failure = (outcome as SpatialOutcome.Failure).failure
    assertEquals(RenGErrorCode.UNSUPPORTED_ANCHORING_MODE, failure.code)
    assertEquals(PipelineStage.FRAME_PLANNING, failure.stage)
    assertEquals("placement.positionMode", failure.diagnostic?.fieldName)
}

@Test fun aScreenPositionedStickerIsStillAccepted() {
    // Only a Model's SCREEN *position* is refused. This is the test that stops the check
    // being written one level too high.
}

@Test fun aScreenRotationOrScaleOverAMapPositionedModelIsStillAccepted() {
    // The billboard case, which CONTEXT.md explicitly keeps supported.
}

@Test fun theRefusalHappensBeforeAnyAcquisition() {
    // ADR 0029: "before acquisition or drawing, never substituting."
    assertEquals(0, transport.calls.size)
}
```

- [ ] **Step 2: Implement**

The check goes in `planMercatorSpatial`'s `plan.modelsForCore()` loop, before `resolveDrawnThing`. Its shape
follows `unsupportedProjectionModeFailure` exactly — the same reason, one level down: RenG never substitutes
a mode it does not implement.

`UNSUPPORTED_ANCHORING_MODE` is `ruleAt(stage, FRAME_PLANNING, FailureRule.Context(setOf(PLACEMENT_POSITION_MODE)))`,
the same one-line shape `UNSUPPORTED_PROJECTION_MODE` already has.

- [ ] **Step 3: Regenerate and review the ABI**

Expected: exactly one new `RenGErrorCode` entry. Anything else in that diff is out of scope.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(kmp): refuse a screen-positioned model, per ADR 0029"
```

---

## Task 12: ADR 0030 — models write depth inside the map regime

ADR 0027 has **no map-regime draw writing depth**, which is correct for flat quads and billboards and wrong
for a mesh: without depth writes a model's own triangles all pass the test against whatever is behind them
and paint in submission order, so back faces show through front ones. Back-face culling hides that for a
closed convex mesh and not for anything else.

**Files:**
- Create: `docs/adr/0030-let-models-write-depth-inside-the-map-regime.md`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt`
- Modify: `docs/adr/0027-let-the-map-regime-test-depth-without-writing-it.md` (a superseded-in-part note)
- Modify: `CONTEXT.md`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/SceneContentTest.kt`

**Interfaces:**
- Consumes: Task 10's `ResolvedModel`, `drawModels`, `ModelPipeline`.
- Produces: the map regime's three depth phases and its extended draw order, and the model pass that carries
  them — `internal class SceneModel(val placement: Placement, val model: DecodedModel, val nodeTransforms: List<DoubleMatrix4?>, val jointMatricesBySkin: Map<Int, List<DoubleMatrix4>>, val uploaded: Map<Pair<Int, Int>, UploadedPrimitive>, val imageTextures: List<Int>, val overrideTexture: Int?)`
  in `SceneContent.kt` beside `SceneSticker`, `Scene.models`, and a `modelPipelines` constructor parameter on
  `SceneContent`. The depth phases cannot be tested without a model pass to put in the middle one, so the two
  land together rather than as a pass with no rule and a rule with no pass.

- [ ] **Step 1: Write the ADR**

Follow the house form — `NNNN-imperative-title.md`, a few paragraphs of prose, no template headings.

It must say four things. That the map regime now has **three depth phases**: flat map-plane content (the
ground, then each `Geometry`) tests and does not write; **models test and write**; map-anchored stickers test
and do not write. That the map-regime order becomes **ground, geometries, models, map-anchored stickers** —
ADR 0025's order with models inserted before the stickers, because a map-anchored sticker is a marker and a
marker paints over the scene. That this supersedes ADR 0027 **for the model pass only**, and ADR 0027's
reasoning stands unchanged for everything else. And — stated rather than discovered — that a billboard
sharing space with a model can still be cut along its anchor row, exactly as ADR 0027 describes for the
ground, because a model is now a real occluder. That is a narrower version of a defect ADR 0027 closed, and
it is accepted: a mesh that cannot self-occlude is a strictly worse picture than a marker clipped by a car
it is standing on.

- [ ] **Step 2: Write the failing tests**

```kotlin
@Test fun theMapRegimeDrawsGroundThenGeometriesThenModelsThenMapAnchoredStickers() { … call-log order … }

@Test fun onlyTheModelPassEnablesDepthWrites() {
    // depthMask(true) appears exactly once in the map regime, and it is inside the model pass.
}

@Test fun theModelPassRestoresDepthMaskFalseBeforeTheStickersDraw() {
    // Otherwise ADR 0027's billboard fix silently stops working the moment a model is in the plan.
}
```

The third test is the one that would be forgotten and would reintroduce the exact defect ADR 0027 exists to
close, in every frame carrying both a model and a billboard.

- [ ] **Step 3: Implement, and update the two documents**

ADR 0027 gains one paragraph at its end saying which part ADR 0030 supersedes. `CONTEXT.md`'s
**Map Anchoring** / draw-regime prose gains the three phases. An ADR that supersedes another and leaves the
other silent is how a reader ends up following the wrong one.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(kmp): let models write depth inside the map regime, per ADR 0030"
```

---

## Task 13: The composite stops double-multiplying alpha

`drawFrame` blends already-premultiplied offscreen content with `GL_SRC_ALPHA`. Invisible under an opaque
basemap; wrong the moment a consumer composites RenG over their own background. Unrelated to models, folded
here because it is small and lives in the same pass.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawer.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun theCompositeBlendsPremultipliedSourceRatherThanMultiplyingAlphaTwice() {
    drawFrame(binding, profile, surface, composite, target)
    val call = binding.calls.last { it.entryPoint == GlEntryPoint.BLEND_FUNC_SEPARATE }
    assertEquals(listOf(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA), call.args,
        "the offscreen colour attachment is already premultiplied; GL_SRC_ALPHA multiplies it again")
}
```

- [ ] **Step 2: Implement**

One argument changes: `GL_SRC_ALPHA` becomes `GL_ONE`. `drawFrame`'s KDoc gains a sentence saying why —
every producer into that surface (`drawStickers`, `drawGround`, and now `drawModels`) writes premultiplied
colour, so the composite must not premultiply a second time.

- [ ] **Step 3: Commit**

```bash
git commit -m "fix(kmp): stop the composite pass multiplying alpha twice"
```

---

## Task 14: Delete the duplicated regime split and z-sort

`MercatorSpatialPlanner` already splits every drawn thing into its regime and sorts the screen stack — z,
then sticker-before-model, then source index — and `RenGRenderer` discards both and re-derives them in the
GL layer, by re-resolving each `Placement` at draw time and re-sorting inside `drawStickers`. Two copies of
one documented rule, one of them unit-tested and one of them not.

**Ruling — the spec's stated reason for this task does not survive ADR 0029, and the task does.** The spec
says "Models break the coincidence" in the *screen* stack. They cannot: Task 11 refuses a `SCREEN`-positioned
model, so the screen stack stays stickers-only for this whole cycle and the planner's merged order and
`drawStickers`' stable sort agree on every input that can exist. The break is in the **map** regime instead —
which now has models in it, and whose phase order ADR 0030 fixes as ground, geometries, models, map-anchored
stickers. That order is deliberately **not** the planner's `mapEntries` order, which is stickers-then-models
by declaration; consuming `mapEntries` verbatim as a draw order would paint a car over the pin standing in
front of it. So `SceneContent` takes the planner's answer to *which regime each thing is in and in what order
within its type*, and applies ADR 0030's phase order over it. Recorded here because the spec sentence reads
as licence to thread one list straight through, and doing that would be a rendering defect.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/StickerPipeline.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/RenGRenderer.kt`
- Modify: `docs/superpowers/specs/2026-08-23-cycle-f2-models-design.md` (correct the superseded sentence)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/SceneContentTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/StickerPipelineTest.kt`

**Interfaces:**
- Consumes: `MercatorSpatialPlan.mapEntries`, `.screenEntries`, `DrawnThingReference`, and Task 12's phase
  order, `SceneModel` and `Scene.models`.
- Produces: `Scene` gains `mapOrder: List<DrawnThingReference>` and `screenOrder: List<DrawnThingReference>`.
  `StickerWorld` loses `screenAnchored`; `drawStickers` draws the map half only, in the order it is given.
  `SceneContent` gains `drawScreenStack`, which walks `screenOrder` and switches programs per element rather
  than batching by type.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun theRegimeSplitComesFromThePlannerRatherThanFromASecondResolution() {
    // A sticker whose Placement the fake planner reports as SCREEN must composite even though a
    // second resolution of the same Placement would put it in the map regime. The two can only
    // disagree when there are two of them, which is the point.
    val scene = sceneWithPlannerSplit(screenOrder = listOf(StickerAt(0)), mapOrder = emptyList())
    SceneContent(camera, scene, stickerPipeline, groundPipeline, modelPipelines).draw(binding)
    assertTrue(binding.calls.none { it.entryPoint == GlEntryPoint.ENABLE && it.args == listOf(GL_DEPTH_TEST) })
}

@Test fun theScreenStackFollowsThePlannersOrderRatherThanTheStickerListOrder() {
    // Planner order [1, 0] against declaration order [0, 1]: the sort must not be re-derived here.
    val scene = sceneWithPlannerSplit(screenOrder = listOf(StickerAt(1), StickerAt(0)))
    assertEquals(listOf(TEXTURE_ONE, TEXTURE_ZERO), boundTexturesInDrawOrder(binding))
}

@Test fun theMapRegimeAppliesAdr0030PhaseOrderRatherThanThePlannersDeclarationOrder() {
    // mapOrder is [StickerAt(0), ModelAt(0)] by declaration; the draw must be model, then sticker.
    assertTrue(indexOfFirstModelDraw(binding) < indexOfFirstStickerDraw(binding))
}

@Test fun perTypeDeclarationOrderInsideTheMapRegimeIsStillThePlannersOwn() {
    // Two stickers, planner order [1, 0]: the map half keeps that order too.
}

@Test fun drawStickersNoLongerSortsAnything() {
    // The screen half is gone and the map half preserves what it is handed. A sort left here would be
    // the second copy this task exists to delete.
    val world = StickerWorld(mapAnchored = listOf(stickerAtZ(9.0), stickerAtZ(1.0)))
    drawStickers(binding, pipeline, world)
    assertEquals(listOf(9.0, 1.0), drawnZOrder(binding))
}
```

The first test is the load-bearing one, and it is the only shape that can fail: with one authority the two
answers are the same value, so a test that feeds consistent inputs proves nothing about which authority was
consulted. Feed it inputs where a second resolution would disagree.

- [ ] **Step 2: Implement**

`RenGPreparedFrame` gains the two order lists, snapshotted at `prepare()` from the planner's own output.
`SceneContent` keeps re-resolving each `Placement` at draw time through `requireResolvedAtDrawTime` for its
*matrices* — that reasoning is sound and unchanged — but no longer reads `resolved.drawRegime` or
`resolved.screenCompositeZ` to decide regime or order. Threading the resolved placements themselves would be
a larger change with its own precision argument; this task deletes a duplicate rather than relitigating a
working one.

`StickerWorld`'s KDoc loses its screen-regime paragraph and gains a pointer to `SceneContent.drawScreenStack`.
Leaving that paragraph would document a regime the class no longer owns.

- [ ] **Step 3: Check the spec still says what this task implements**

The spec's "The draw order is computed twice" section was corrected at plan time to the ruling above; the
`Modify:` entry for it is there so a reviewer sees the two were reconciled, not so it is edited again. If the
implementation lands somewhere different from what that section now says, correct the section rather than
leaving it wrong — a spec left saying the superseded thing is how the next reader reintroduces the defect.

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(kmp): one authority for the regime split and the screen order"
```

---

## Task 15: `ResourceKind` entries for GPU model objects

RenG's contract is that it "exposes API to query and free the resources it holds", and model buffers and
embedded images are resources it holds. A model that could not appear in `queryResources()` or be released by
`free()` would be the first resource class exempt from a promise the renderer already makes.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt` (`reportOrder`)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/CanonicalBinary.kt` (`CanonicalRootKind`)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt`
- Modify: `kmp/api/kmp.klib.api`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivationTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/ResourceReportsTest.kt`

**Interfaces:**
- Produces: `ResourceKind.MODEL_GEOMETRY`, `ResourceKind.MODEL_IMAGE`;
  `CanonicalRootKind.MODEL_GEOMETRY(7)`, `CanonicalRootKind.MODEL_IMAGE(8)`;
  `ResourceKeyDeriver.modelGeometry(modelKey: ResourceKey, meshIndex: Int, primitiveIndex: Int)`;
  `ResourceKeyDeriver.modelImage(modelKey: ResourceKey, imageIndex: Int)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun aModelGeometryKeyIsAFunctionOfItsModelMeshAndPrimitive() {
    assertNotEquals(deriver.modelGeometry(glb, 0, 0).key, deriver.modelGeometry(glb, 0, 1).key)
    assertNotEquals(deriver.modelGeometry(glbA, 0, 0).key, deriver.modelGeometry(glbB, 0, 0).key)
}

@Test fun anEmbeddedImageIsKeyedByItsModelAndIndexBecauseItHasNoLocator() {
    // An embedded GLB image has no ResourceLocator and therefore no ResourceClass, so it cannot use
    // ResourceKeyDeriver.external at all.
    assertNull(deriver.modelImage(glb, 0).key.resourceClass)
    assertNull(deriver.modelImage(glb, 0).rawKey)
}

@Test fun theTwoNewKindsSortAfterEveryExistingOneInAReport() { … reportOrder 5 and 6 … }

@Test fun everyExistingKeyDigestIsUnchangedByTheTwoNewKinds() {
    // The wire value is part of a canonical identity (ADR 0018): renumbering silently changes the
    // ResourceKey of every resource RenG has ever derived. There is no accessor for the mapping --
    // it is a private extension -- so the guard is the digest, which is the thing that must not move.
    // `ResourceKeyDerivationTest` already pins these; confirm every one still passes unedited, and
    // append the two new kinds' own digests beside them rather than editing an existing expectation.
}
```

- [ ] **Step 2: Implement, regenerate and review the ABI**

Expected: exactly two new `ResourceKind` entries. Together with Task 11's error code, that is the whole of
this cycle's ABI growth, and the `kmp.klib.api` diff must show exactly three added lines' worth of enum
entries and nothing else.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(kmp): give model GPU objects a resource kind and an identity"
```

---

## Task 16: Wire models through the renderer

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/RenGRenderer.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/RendererFactory.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistry.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/RendererProtocolTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistryTest.kt`

**Interfaces:**
- Consumes: Tasks 6, 9, 10, 12, 14, 15.
- Produces: `internal class PreparedModel(val placement: Placement, val glbKey: ResourceKey, val model: DecodedModel, val overrideTexture: PreparedGeometryTexture?, val nodeTransforms: List<DoubleMatrix4?>, val jointMatricesBySkin: Map<Int, List<DoubleMatrix4>>)`;
  `RenGPreparedFrame.models`; `GlObjectRegistry.handlesOfType(key: ResourceKey, type: GlObjectType): List<GlObjectHandle>`.
  `SceneModel` is Task 12's and is assembled here, not defined here.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun preparingAFrameWithAModelAcquiresItsGlbAndItsOverrideTexture() {
    // MODEL_GLB and MODEL_TEXTURE are already traversed by staticResourceTraversal and have never
    // been acquired. This is where that gap closes.
    assertEquals(setOf(GLB_URL, TEXTURE_URL), transport.requestedUrls)
}

@Test fun aMissingAnimationSelectorFailsAtResourceParsingWithItsOwnField() {
    // The allowlist puts ANIMATION_SELECTOR at RESOURCE_PARSING and NOT at FRAME_PLANNING, which is a
    // real ordering constraint: the check cannot live in FramePlanningCore.
    val failure = assertFailsWith<RenGException> { renderer.prepare(planWithMissingSelector()) }
    assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, failure.code)
    assertEquals(PipelineStage.RESOURCE_PARSING, failure.stage)
    assertEquals("animationSelector", failure.diagnostics.single().fieldName)
}

@Test fun twoSelectorsResolvingToOneAnimationFailTheSameWay() { … }

@Test fun theSamePreparedFrameDrawnTwiceUploadsItsPrimitivesOnce() {
    // Keyed by ResourceKeyDeriver.modelGeometry, the same upload-once-by-key discipline stickers use.
}

@Test fun anOverrideReplacesEveryPrimitivesBaseColourTextureAndKeepsEverythingElse() {
    // CONTEXT.md: "an override replaces every rendered primitive's base-colour texture while
    // preserving other material properties."
    assertEquals(overrideTexture, primitive.boundTexture)
    assertContentEquals(authoredFactor, primitive.material.baseColourFactor)
}

@Test fun anOverrideOnAPrimitiveWithNoTextureCoordinatesDrawsTheFactorAlone() {
    // "Replaces" cannot add a texture to a primitive that has no TEXCOORD_0 to sample it with.
}

@Test fun modelGpuObjectsAppearInAResourceReportAndAreReleasedByFree() { … }

@Test fun contextLossForgetsModelBuffersWithoutDeletingThem() { … ADR 0007/0015 … }
```

- [ ] **Step 2: Implement**

`prepare()` gains a model arm alongside the sticker arm: acquire (the traversal already emits both
references), `decodeModel`, `resolveAnimationSelectors`, `sampleAnimationTracks`, `composeGlobalTransforms`,
and the per-skin joint matrices — all synchronous, all before the suspend function returns, so a caller
mutating anything afterwards changes nothing, exactly as `PreparedGeometry` already guarantees.

Animation sampling happens at `prepare()`, not at draw. `AnimationTrack.timeSeconds` is part of the
`FramePlan`, so the sampled pose is a pure function of the plan — computing it at draw time would make the
same prepared frame produce different pixels on a second draw, which the frame's own canonical identity
already promises it cannot.

**Decode once per prepared frame, and say so.** `PARSE_GLB` and `VALIDATE_GLB_FEATURES` each independently
re-scan and re-parse the container, so `decodeModel` here is the third parse of the same bytes. That is
accepted for this cycle and recorded rather than waved away: a parsed-model residency belongs with
`ResidentGeneration.decoded`, which is `null` in production for images too, and fixing one without the other
would be half a fix. Measure the parse cost for the corpus's largest GLB and record the figure in the task
report, the way the basemap style's double-parse was measured before it was fixed.

`drawResolvedFrame` gains the model arm: upload each primitive once by `modelGeometry` key, each embedded
image once by `modelImage` key, build `SceneModel`, and hand it to the `Scene`.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(kmp): acquire, decode and draw a model through the renderer"
```

---

## Task 17: The analytical readback gate

Pixel verification is deferred to Cycle J by owner decision, so this cycle's gate matches E-basemap's:
relationships asserted over a real GL context on the two targets that can hold one, with no stored baselines.

**Files:**
- Create: `kmp/src/nativeTest/kotlin/com/rohittp/reng/ModelReadbackSuite.kt`
- Create: `kmp/src/nativeTest/kotlin/com/rohittp/reng/ModelFixtureBuilder.kt`
- Modify: `kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl/MacosGlConformanceTest.kt`
- Modify: `kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/LinuxGlConformanceTest.kt`

**Interfaces:**
- Consumes: the whole cycle, through the **public** API.
- Produces: `internal fun runModelReadbackSuite(binding, probe, dialect)`.

- [ ] **Step 1: Build the fixture builder**

A GLB is a 12-byte header plus a JSON chunk plus a BIN chunk, and RenG owns no encoder, so the fixture is
assembled byte by byte in the test. `ModelFixtureBuilder` produces:
- a two-triangle asymmetric wedge with distinct per-corner texture coordinates,
- a 4×4 embedded PNG whose sixteen texels are all different,
- one skin with two joints and a rotation animation on the second,
- a second, taller mesh for the occlusion case.

**Asymmetric in every axis.** A symmetric fixture cannot catch a transposed inverse bind matrix, and this
project already shipped a suite of exact-URL assertions blind to a transposition for exactly that reason.

- [ ] **Step 2: Write the six assertions the spec names**

Each exists because it catches a specific trap this cycle can produce.

```kotlin
// 1. The model is not black -- the mipmap-incompleteness trap, directly.
assertTrue(interiorSamples.any { it.luminance > BLACK_THRESHOLD },
    "an incomplete texture samples black, and a black model reads as a shader bug")

// 2. Skinning deforms. The only assertion distinguishing real skinning from a rig that loads and does
//    nothing: the same model at two animation times must differ, AND must differ in the region the
//    moving joint governs.
assertTrue(differingPixels(frameAtZero, frameAtHalf) > SKIN_DEFORMATION_FLOOR)
assertTrue(differingPixelsInside(jointRegion) > 0)

// 3. Animation is bound to time, not frame count.
assertContentEquals(frameAtHalf, frameAtHalfAgainWithADifferentFrameIndex)

// 4. Occlusion is real, which also exercises ADR 0030's reopened depth rule.
assertTrue(groundPixelsBehindTheModel == 0)
assertTrue(pixelsOfTheFartherModelInsideTheNearerOnesSilhouette == 0)

// 5. The texture is applied -- an interior sample matches a known texel from the embedded image,
//    not the material's base-colour factor. The fixture makes the two deliberately far apart.
assertNear(EXPECTED_TEXEL, sampleAt(INTERIOR_POINT), COLOUR_TOLERANCE)

// 6. Coverage is bounded -- non-clear pixels fall inside the projected bounds, catching a runaway
//    transform or an exploded joint matrix.
assertEquals(0, nonClearPixelsOutside(projectedBounds), "a model outside its own bounds means a bad matrix")
```

- [ ] **Step 3: State every tolerance's floor**

Beside each budget, write the smallest defect it still detects — `SKIN_DEFORMATION_FLOOR` is one to two
orders of magnitude below what a working rig produces and above what driver rounding produces, and the task
report must say which numbers those are on both drivers it runs on.

Reuse `measureLargeQuadRasterisation`'s pattern where a case cannot survive a software rasteriser: skip that
one case out loud and run the rest, rather than tolerating every budget into meaninglessness. A probe that
cannot run fails rather than skips.

- [ ] **Step 4: Say what the suite does not catch, in its KDoc**

A subtly wrong skinning weight, an inverted normal, an off-by-a-little bind pose all pass. That is Cycle J's,
and this file should say so rather than implying its gate is stronger than it is.

- [ ] **Step 5: Run on both targets and commit**

```bash
./gradlew --no-configuration-cache :kmp:macosArm64Test --tests "com.rohittp.reng.*Model*"
git commit -m "test(kmp): gate models on analytical readback over a real context"
```

---

## Task 18: The harness draws a model, and the records catch up

**Files:**
- Modify: `consumer-smoke/src/macosArm64Main/kotlin/com/rohittp/reng/smoke/harness/Storyboard.kt`
- Modify: `CLAUDE.md`, `CONTEXT.md`, `docs/decomposition.md`
- Modify: `docs/adr/0021-*.md` and `docs/adr/0028-*.md` (errata for the rules this cycle changed)

- [ ] **Step 1: Extend the storyboard**

The visual harness found four defects the passing suite did not, and it is the reason `0.3.0` draws. Add
model frames covering: a static textured model on the ground, the same model animated across frames, a
skinned model, a `BLEND` material over the basemap, and one negative case — a `SCREEN`-positioned model,
which must fail with `UNSUPPORTED_ANCHORING_MODE` and be reported rather than swallowed.

The storyboard needs a GLB the harness can fetch. Use the corpus URL the owner already supplied
(`docs/research/2026-08-22-consumer-model-corpus-check.md` names it); do not check a GLB into the repository,
for the same reason no style is checked in.

- [ ] **Step 2: Render and watch it**

```bash
./gradlew -p consumer-smoke runHarness -PstyleUrl=<url>
```

Compare at `crf 12` / `yuv444p`, or on the PPM frames directly — the default `yuv420p` assembly is lossy and
has already once misled a judgement about image sharpness.

- [ ] **Step 3: Update the records**

`CLAUDE.md`'s repository-state section gains what this cycle shipped, with the test counts actually measured
in the checkout. `CONTEXT.md` gains the model draw phases from Task 12 and the light constants from Task 10.
`docs/decomposition.md` marks F-2 done and records what it did not do — morph targets, JPEG, and the one
consumer model that stays rejected because of it.

ADR 0021 and ADR 0028 each gain a short erratum naming the rules this cycle changed: the widened attribute
subset, admitted skins, and which of ADR 0028's seven owed follow-ups are now closed (five: sampler
`output.count` vs `input.count`, duplicate `(node, path)` channels, sampler filter and wrap enums,
`baseColorTexture.texCoord != 0`, and strictly-increasing animation inputs) and which two remain, with why.
`min`/`max` presence stays open because RenG never reads either, and `buffers[i>0].byteLength` is moot under
`MULTIPLE_BUFFERS`.

- [ ] **Step 4: Run every locally compilable gate**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi :kmp:testAndroidHostTest :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 :kmp:bundleAndroidMainAar \
  :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64 :kmp:macosArm64Test
```

`linuxX64Test` is Linux CI coverage, not a macOS-local gate — do not claim it ran.

- [ ] **Step 5: Commit**

```bash
git commit -m "docs: record what Cycle F-2 shipped, and what it still owes"
```
