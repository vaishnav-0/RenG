# Take androidx.test:runner for device tests

RenG declares `androidx.test:runner` for its new `androidDeviceTest` source set, with the version recorded
in RenG's version catalogue, and `tools/check_repository_policy.py` gains a third dependency scope with its
own exact allowlist — `kotlin("test")`, `libs.kotlinx.coroutines.test`, `libs.androidx.test.runner` — while
continuing to forbid every other library it lists in every other scope. **This is the second third-party
dependency RenG has ever taken.** ADR 0019 recorded the first and argued it rather than assuming it; this
ADR exists for the same reason, because a coordinate is permanent and the second exception is what makes the
third easy.

**What it buys is a device gate at all.** Three lines in `kmp/build.gradle.kts` —

```kotlin
withDeviceTest {
    instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
```

— cause AGP 9.3.1 to register `compileAndroidDeviceTest`, `packageAndroidDeviceTest`,
`connectedAndroidDeviceTest` and about forty supporting tasks. `instrumentationRunner` is a property of
AGP's `KotlinMultiplatformAndroidDeviceTest`, and the class it names lives in `androidx.test:runner`. The
result, measured on `spike/h-android-gles` against a OnePlus CPH2649 (Adreno 830, Android 16, `arm64-v8a`):
the whole `commonTest` body runs on the phone, **1,125 tests, 0 failures, 0 skipped**, and
`AndroidGlBinding.kt` executes for the first time — 419 lines that had shipped in every release since
`0.2.0` without a single one of them ever running anywhere, on a device, an emulator or CI. That is the
thing this cycle exists to fix, and it does not exist without the runner.

**There is no standard-library path, and that was measured too, not inferred.** An `androidDeviceTest`
source set with no dependencies of its own does not even resolve `kotlin.test` —
`Unresolved reference 'test'` — so the source set needs a `dependencies { }` block regardless, and AGP
requires an instrumentation runner on the classpath for an instrumented test to run at all. The alternative
is not "write it by hand"; the alternative is having no device gate, which leaves Android's binding in
exactly the state that motivated the cycle. That is what makes this different from ADR 0019's rejected
`kotlin.coroutines.startCoroutine` option: there was a hand-written design to weigh there, and there is none
here.

**It ships in nothing.** `androidDeviceTest` is a test source set. AGP compiles it into a separate test APK
through `packageAndroidDeviceTest`, which `connectedAndroidDeviceTest` installs on a device; the published
Android artifact is the AAR that `bundleAndroidMainAar` produces from `androidMain`. `kmp/api/kmp.klib.api` is byte-identical across the change and `:kmp:checkKotlinAbi` passes.
No consumer resolves this coordinate and no consumer's dependency graph moves — which is a stronger claim
than ADR 0019 could make for coroutines, which is a production `implementation` and does reach consumers.
Whatever `androidx.test:runner` brings transitively is confined to that test APK for the same reason. The
runner is Apache-2.0 (the AOSP header on `androidx/test/runner/AndroidJUnitRunner.java` in
`runner-1.7.0-sources.jar`), so the licence check is unaffected, as it was for coroutines.

**The policy gate is widened explicitly, and that is the half worth arguing.** The checker refuses this
change three ways, all of them measured rather than predicted. `kmp/build.gradle.kts` is one of four files
pinned in `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` (`tools/check_repository_policy.py:241`) with exactly
two accepted forms, and any token change moves it. `check_dependencies` (`:1638`) asserts
`len(dependency_calls) == len(main_calls) + len(test_calls)`, where those two are scoped to `commonMain` and
`commonTest` alone — so **any `dependencies { }` call anywhere else in the file is refused by construction**,
before the coordinate is even examined. And the coordinate is not in the catalogue. The spike reproduced
exactly two violations, at `:1` and `:54`, which is the same rule stated twice.

**Exempting test source sets wholesale was the obvious fix and it is rejected.** The project polices
`commonTest` deliberately today: `_PERMITTED_NEW_TEST_DEPENDENCIES` (`:73`) names exactly one coordinate,
`libs.kotlinx.coroutines.test`, and `_FORBIDDEN_DEPENDENCY` (`:64`) rejects every other library by name,
including any *other* coroutines artifact. A blanket exemption for test scopes would admit this dependency
and every one after it, and nothing would catch the next — which will look every bit as reasonable as this
one does, and will arrive without an ADR because no gate stopped to ask for one. So the widening is a third
scope in the same shape as the second, with its own closed allowlist and the total widened to
`main + test + device`. The rule stays exact: it still names every permitted coordinate, and a fourth
dependency in `androidDeviceTest` is still refused, as is any dependency in any other source set. Those two
refusals are worth asserting in the checker's own tests, because a widening that admits anything is not this
widening.

One recorded trap, because recomputing the wrong thing is the known failure here. Those fingerprints are
**not** whole-file SHA-256 digests, whatever older prose and the checker's own comment say —
`_build_configuration_fingerprint` (`:846`) tokenises first, so comments and whitespace are free and tokens
are not. Both accepted forms must move together, and the second cannot be recomputed from disk; `HANDOFF.md`
carries the derivation and the command.

**The cost is that a gate built to fail closed is now one notch shorter than it was, and that is accepted
rather than unnoticed.** ADR 0019 put it as "the forbidden-dependency rule exists because dependencies are
permanent, and one exception invites the next", and amended a single named coordinate rather than the
rule's principle. This does the same thing a second time, one scope over. The honest reading is that the
argument gets easier each time it is made, so the record of *having made it* is the point: the next
dependency still meets a closed door, still has to be named in an allowlist, and still needs an ADR
explaining why the door opened.
