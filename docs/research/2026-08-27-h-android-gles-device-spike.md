# The Android GLES device spike — does `AndroidGlBinding.kt` work?

Measured on 2026-08-27 on `spike/h-android-gles`, branched from `main` at `41f7bfb`, on Apple M3 Max
against a **OnePlus CPH2649, Android 16 (SDK 36), `arm64-v8a`, Adreno (TM) 830** attached over USB as
`adb` serial `ce29ab63`. Kotlin 2.3.21, AGP 9.3.1, Gradle 9.5.0, Rentile pinned at `0.5.0`.

This answers the question `docs/research/2026-08-24-h-android-measurements.md` closed on: *"`kmp/src`
contains `androidHostTest` and no device-test source set at all … `AndroidGlBinding.kt` has therefore
never been executed anywhere — not on a device, not on an emulator, not in CI — while shipping in every
release since `0.2.0`."*

## Verdict

**It works, on the first attempt, with no change to a single line of production code.** An
`androidDeviceTest` source set is a real Gradle source set that AGP 9.3.1 already knows how to build,
install and run; `AndroidGlBinding` drives a stock `EGL14` pbuffer GLES 3 context on Adreno 830; all 91
roster entry points resolve; the GL conformance suite passes unmodified at `ShaderDialect.GLES` with the
real cross-dialect link; and — the result nobody expected — **the entire `commonTest` suite runs on the
phone**, 1,125 tests, 0 failures, 0 skipped.

Two further results settle open questions the measurements document flagged as the likeliest sources of
late surprise:

- **The rasterisation probe reports 0 pixels of disagreement on Adreno.** No tolerance in the readback
  suites needs widening for Android.
- **Rentile's Skia loads and rasterises on the device.** `libskiko-android-arm64.so` is real on a phone,
  and the "Rentile does not work on Android" reading is a fact about the *host JVM* only. Re-measured here,
  both halves.

The cost of the Android half is therefore **not** a rendering problem. It is a build-configuration problem
with exactly two sharp edges, one of which is silent, and a repository-policy problem. All three are below.

## What actually runs

```
:kmp:connectedAndroidDeviceTest   1125 tests, 0 failures, 0 skipped   (OnePlus CPH2649, Adreno 830)
:kmp:connectedAndroidDeviceTest   1125 tests, 0 failures, 0 skipped   (Pixel_10_Pro_XL emulator, ANGLE)
:kmp:testAndroidHostTest          1124 tests, 0 failures, 0 skipped
:kmp:macosArm64Test               1159 tests, 0 failures, 0 skipped   (unchanged)
:kmp:checkKotlinAbi               passes; kmp/api/kmp.klib.api did not move
:kmp:compileKotlinIosArm64 / IosSimulatorArm64 / LinuxX64 / LinuxArm64 / bundleAndroidMainAar   all pass
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .   FAILS, deliberately
```

The device totals are Gradle's own JUnit XML at
`kmp/build/outputs/androidTest-results/connected/androidMain/`, whose suite element reads
`tests='1125' failures='0' errors='0' skipped='0'`.

## Step 1 — the source set, and the two edges that fight you

The task is **`:kmp:connectedAndroidDeviceTest`** ("Installs and runs the tests for androidMain on
connected devices"), not `androidConnectedCheck`. Three lines in `kmp/build.gradle.kts` create it:

```kotlin
withDeviceTest {
    instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
```

AGP 9.3.1 then registers the whole instrumented pipeline — `compileAndroidDeviceTest`,
`packageAndroidDeviceTest`, `connectedAndroidDeviceTest` and about forty supporting tasks. Nothing had to
be invented.

### Edge 1: the source set needs its own dependencies, and `androidx.test:runner` is one of them

An empty `androidDeviceTest` source set does not see `kotlin.test`:

```
e: .../AndroidGlSpikeTest.kt:3:15 Unresolved reference 'test'.
```

so it needs its own `dependencies { }` block. The runner class named above comes from
`androidx.test:runner` (`1.7.0` here), which resolves from the `google()` repository `settings.gradle.kts`
already declares. This is the block the repository policy checker refuses outright; see Step 6.

### Edge 2 — the silent one: an explicit `dependsOn` un-wires every Kotlin/Native target

`androidDeviceTest` does **not** see `commonTest`. Without an explicit edge, the spike's own files fail with
35 unresolved references — `runGlConformanceSuite`, `CONFORMANCE_SURFACE_PIXELS`, `basemapEngineHost`,
`CountingHostTransport`, `hostRasterRoute` and the rest. So reusing the existing suites *requires*
`dependsOn(commonTest.get())`.

Taking that edge produces two warnings, and the second one is a lie about its own severity:

```
⚠️ Default Kotlin Hierarchy Template Not Applied Correctly
The Default Kotlin Hierarchy Template was not applied to 'project ':kmp'':
Explicit .dependsOn() edges were configured for the following source sets:
[androidDeviceTest]

⚠️ Invalid Source Set Dependency Across Trees
Kotlin Source Set 'androidDeviceTest' can't depend on 'commonTest' as they are from
different Source Set Trees.
```

The second warning is wrong on the facts — the edge works, and `commonTest`'s `internal` declarations
resolve from `androidDeviceTest` — but the first is a live grenade. Disabling the default hierarchy template
un-wires `iosMain`, `nativeMain`, `linuxMain` and `macosMain`, and **every Kotlin/Native compilation then
fails**:

```
e: .../internal/gl/PlatformGlBinding.kt:21:10 Expected openPlatformGlBinding has no actual
   declaration in module <commonMain> for Native
e: .../internal/image/Inflate.kt:19:10 Expected InflateStream has no actual declaration in
   module <commonMain> for Native
e: .../internal/image/Inflate.kt:24:10 Expected crc32 has no actual declaration in module
   <commonMain> for Native
```

**The Android half of the build stays entirely green while this is true.** `compileAndroidDeviceTest`,
`connectedAndroidDeviceTest` and `testAndroidHostTest` all pass; nothing hints at it until somebody compiles
an Apple or Linux target. On this machine it surfaced only when `:kmp:checkKotlinAbi` pulled
`compileKotlinIosArm64` in.

The fix is one line in the `kotlin { }` block:

```kotlin
applyDefaultHierarchyTemplate()
```

With it, `compileAndroidDeviceTest`, `compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`,
`compileKotlinLinuxX64`, `compileKotlinLinuxArm64`, `bundleAndroidMainAar` and `checkKotlinAbi` all pass
together, and `macosArm64Test` is unchanged at 1159/0/0.

**A Cycle H task must call this out as a gate, not as trivia.** The failure is silent on the platform being
worked on and loud on four platforms nobody is looking at.

## Step 2 — the headline: what Adreno 830 says

`kmp/src/androidDeviceTest/kotlin/com/rohittp/reng/internal/gl/PbufferEglContext.kt` is 105 lines of stock
`android.opengl.EGL14`: `eglGetDisplay(EGL_DEFAULT_DISPLAY)`, `eglInitialize`,
`eglBindAPI(EGL_OPENGL_ES_API)`, an `EGL_PBUFFER_BIT` / `EGL_OPENGL_ES3_BIT_KHR` config with an 8/8/8/8
colour and 24-bit depth, a 512×512 pbuffer, `EGL_CONTEXT_CLIENT_VERSION = 3`, `eglMakeCurrent`. No
cinterop, no NDK, no third-party anything. Like every other fixture in this repository it leaves the
default framebuffer alone; the suites render into FBOs they create themselves.

Linux's `SurfacelessEglContext` genuinely could not be transliterated, and the measurements document was
right about why: `EGL_KHR_surfaceless_context` is on neither AOSP's mandatory nor its recommended list.
(Adreno 830 happens to advertise `GL_OES_surfaceless_context` — see the extension list below — but "this
phone has it" is not "Android has it", and the pbuffer costs nothing.)

From the device's own stdout, via `adb -s ce29ab63 logcat -d -s System.out`:

```
EGL 1.5 pbuffer 512x512 is current
GL_VERSION=OpenGL ES 3.2 V@0800.74 (GIT@a97e8c1cc4, I1c372a512c, 1778232424) (Date:05/08/26)
GL_RENDERER=Adreno (TM) 830
GL_VENDOR=Qualcomm
GL_SHADING_LANGUAGE_VERSION=OpenGL ES GLSL ES 3.20
GL_SUBPIXEL_BITS=4
GL_MAX_TEXTURE_SIZE=16384
125 extensions
```

`GL_SUBPIXEL_BITS = 4` and `GL_MAX_TEXTURE_SIZE = 16384` are the same numbers real Apple hardware reports —
the A14 and the M3 Max both give 4 and 16384. The two software rasterisers give 10 and 4096. **Adreno sits
with the hardware, which is the first thing worth knowing about it.**

`adoptRenderContext` reports:

```
adopted dialect=GLES renderer=Adreno (TM) 830 version=GlVersion(major=3, minor=2)
maxTextureSize=16384 maxColorAttachments=8 maxCombinedTextureImageUnits=96
```

The full 125 extensions:

```
GL_AMD_compressed_ATC_texture GL_ANDROID_extension_pack_es31a GL_ARM_shader_framebuffer_fetch_depth_stencil
GL_EXT_EGL_image_array GL_EXT_EGL_image_external_wrap_modes GL_EXT_EGL_image_storage
GL_EXT_EGL_image_storage_compression GL_EXT_YUV_target GL_EXT_blend_func_extended
GL_EXT_blit_framebuffer_params GL_EXT_buffer_storage GL_EXT_clip_control GL_EXT_clip_cull_distance
GL_EXT_color_buffer_float GL_EXT_color_buffer_half_float GL_EXT_copy_image GL_EXT_debug_label
GL_EXT_debug_marker GL_EXT_depth_clamp GL_EXT_discard_framebuffer GL_EXT_disjoint_timer_query
GL_EXT_draw_buffers_indexed GL_EXT_external_buffer GL_EXT_float_blend GL_EXT_fragment_invocation_density
GL_EXT_fragment_shading_rate GL_EXT_fragment_shading_rate_attachment
GL_EXT_fragment_shading_rate_primitive GL_EXT_geometry_shader GL_EXT_gpu_shader5 GL_EXT_memory_object
GL_EXT_memory_object_fd GL_EXT_multisampled_render_to_texture GL_EXT_multisampled_render_to_texture2
GL_EXT_polygon_offset_clamp GL_EXT_primitive_bounding_box GL_EXT_protected_textures
GL_EXT_read_format_bgra GL_EXT_render_snorm GL_EXT_robustness GL_EXT_sRGB GL_EXT_sRGB_write_control
GL_EXT_shader_framebuffer_fetch GL_EXT_shader_implicit_conversions GL_EXT_shader_io_blocks
GL_EXT_shader_non_constant_global_initializers GL_EXT_tessellation_shader GL_EXT_texture_border_clamp
GL_EXT_texture_buffer GL_EXT_texture_compression_astc_decode_mode GL_EXT_texture_compression_bptc
GL_EXT_texture_compression_rgtc GL_EXT_texture_compression_s3tc GL_EXT_texture_compression_s3tc_srgb
GL_EXT_texture_cube_map_array GL_EXT_texture_filter_anisotropic GL_EXT_texture_format_BGRA8888
GL_EXT_texture_format_sRGB_override GL_EXT_texture_mirror_clamp_to_edge GL_EXT_texture_norm16
GL_EXT_texture_sRGB_R8 GL_EXT_texture_sRGB_RG8 GL_EXT_texture_sRGB_decode
GL_EXT_texture_storage_compression GL_EXT_texture_type_2_10_10_10_REV GL_IMG_texture_filter_cubic
GL_KHR_blend_equation_advanced GL_KHR_blend_equation_advanced_coherent GL_KHR_debug GL_KHR_no_error
GL_KHR_robust_buffer_access_behavior GL_KHR_texture_compression_astc_hdr
GL_KHR_texture_compression_astc_ldr GL_KHR_texture_compression_astc_sliced_3d
GL_NV_shader_noperspective_interpolation GL_OES_EGL_image GL_OES_EGL_image_external
GL_OES_EGL_image_external_essl3 GL_OES_EGL_sync GL_OES_compressed_ETC1_RGB8_texture GL_OES_depth24
GL_OES_depth_texture GL_OES_depth_texture_cube_map GL_OES_element_index_uint GL_OES_framebuffer_object
GL_OES_get_program_binary GL_OES_packed_depth_stencil GL_OES_rgb8_rgba8 GL_OES_sample_shading
GL_OES_sample_variables GL_OES_shader_image_atomic GL_OES_shader_multisample_interpolation
GL_OES_standard_derivatives GL_OES_surfaceless_context GL_OES_texture_3D GL_OES_texture_compression_astc
GL_OES_texture_float GL_OES_texture_float_linear GL_OES_texture_half_float
GL_OES_texture_half_float_linear GL_OES_texture_npot GL_OES_texture_stencil8
GL_OES_texture_storage_multisample_2d_array GL_OES_texture_view GL_OES_vertex_array_object
GL_OES_vertex_half_float GL_OVR_multiview GL_OVR_multiview2
GL_OVR_multiview_multisampled_render_to_texture GL_QCOM_YUV_texture_gather GL_QCOM_alpha_test
GL_QCOM_frame_extrapolation GL_QCOM_motion_estimation GL_QCOM_render_sRGB_R8_RG8
GL_QCOM_render_shared_exponent GL_QCOM_shader_framebuffer_fetch_noncoherent
GL_QCOM_shader_framebuffer_fetch_rate GL_QCOM_shading_rate GL_QCOM_texture_foveated
GL_QCOM_texture_foveated2 GL_QCOM_texture_foveated_subsampled_layout GL_QCOM_texture_lod_bias
GL_QCOM_tiled_rendering GL_QCOM_validate_shader_binary GL_QCOM_ycbcr_degamma
```

`GL_EXT_sRGB_write_control` — the one extension `RenderContextProfile` records by name — is present.

## Step 3 — RenG's own binding

`openPlatformGlBinding()` returns `GlBindingResult.Bound(AndroidGlBinding)` unconditionally
(`AndroidGlBinding.kt:419`), exactly as the iOS binding does, so only running the roster proves anything.
Two things establish it:

- **`GlEntryPoint.entries.size == 91`**, and `adoptRenderContext(binding)` drives the binding for version,
  renderer, extension set and three `GL_MAX_*` limits and requires a clean error flag. It returns `Adopted`
  with `dialect=GLES`.
- The conformance suite's `entry-point-inventory` check actually *calls* VAOs, samplers, `texStorage2D`,
  two framebuffers, `drawBuffers`, `readBuffer`, `blitFramebuffer` and the matching deletes and requires no
  unexpected error flag. It passes.

Android's binding is the most direct of the four — `GLES30 extends GLES20`, one import, explicit `0`
offsets, `ByteBuffer` wrappers on four payload calls — and it turns out to be correct as written. **Nothing
in `AndroidGlBinding.kt` needed a fix.** That is a genuine and slightly surprising result for 419 lines
that had never been executed.

## Step 4 — the GL conformance suite

`runGlConformanceSuite(binding, fixture.probe, ShaderDialect.GLES)` passes, unmodified, with all eight
checks and **no relaxed policy** — notably not `CrossDialectLinkPolicy.SKIP_ON_LINUX_MESA_LINK_SEGFAULT`,
so Adreno exercises the real cross-dialect link, as iOS and macOS do:

```
conformance renderer=Adreno (TM) 830 / OpenGL ES 3.2 V@0800.74 (GIT@a97e8c1cc4, I1c372a512c,
1778232424) (Date:05/08/26) sl=OpenGL ES GLSL ES 3.20
checks=[context-adoption, entry-point-inventory, error-queue, state-round-trip,
        shader-dialect-matrix, model-shader-variants, offscreen-composite, lifecycle]
```

Android is the **third** GLES target under this suite, after Linux's ES context and iOS. The dialect
machinery needed no new work, again.

## Step 5 — the rasterisation probe: 0 pixels

`measureLargeQuadRasterisation` is `private` in `kmp/src/nativeTest/.../BasemapReadbackSuite.kt:669`, and
`androidDeviceTest` is a JVM source set that cannot see a Kotlin/Native one at any visibility. The iOS
spike widened three declarations to `internal` and that sufficed, because `iosTest` is itself Native; **that
route does not exist for Android.** So the probe, its four `ProbeRectangle` footprints, `PROBE_CLIP_W`,
`PROBE_NDC_DEPTH`, the two shader sources and `createReadbackTarget` were copied into
`kmp/src/androidDeviceTest/kotlin/com/rohittp/reng/internal/gl/LargeQuadRasterisationSpikeTest.kt` — 217
lines, verbatim but for the added per-rectangle `println`. Everything they call in turn — `scanShaderProfile`,
`compileShaderProgram`, `littleEndianBytes`, the GL tokens — is `commonMain` and needed nothing.

The device's answer:

```
probe north-west tile: 0 mismatched pixels
probe north-east tile: 0 mismatched pixels
probe south-west tile: 0 mismatched pixels
probe south-east tile: 0 mismatched pixels
driver=Adreno (TM) 830 worstRectangle= mismatchedPixels=0 budget=512 isTrustworthy=true
```

| | Adreno 830 | Apple A14 | M3 Max | `Apple Software Renderer` | iOS Simulator | ANGLE/SwiftShader |
|---|---|---|---|---|---|---|
| large off-screen quad probe | **0 px** | 0 px | 0 px | 2,112 px | 3,040 px | **0 px** |
| `GL_SUBPIXEL_BITS` | **4** | 4 | 4 | 10 | 10 | **4** |
| `GL_MAX_TEXTURE_SIZE` | **16384** | 16384 | 16384 | — | 4096 | **8192** |

**The measurements document's "single most likely source of a late surprise" did not materialise.** It
predicted that the readback tolerances, derived against three rasterisers, might not survive ANGLE. They
survive both ANGLE and Adreno with zero disagreement. No tolerance needs widening for Android.

What this probe does *not* prove is that `BasemapReadbackSuite` and `ModelReadbackSuite` pass on Android —
those are `nativeTest` files and were not run. See "What moving them would entail" below.

## Step 6 — Rentile's Skia loads and rasterises on the device

Three separate measurements, because the claim is worth being careful about.

**RenG's own engine path.** `kmp/src/nativeTest/.../BasemapEngineRenderTest.kt` was copied verbatim into
`androidDeviceTest` (only the class name changed — every helper it uses is already in `commonTest`). It
passes, and prints:

```
SKIA rendered 3605 PNG bytes on this device
```

That is Rentile compiling a style, preparing a raster tile through the firewall and rendering it to an
encoded PNG, with RenG's own canonical tile identity, on the phone.

**Skia itself, driven directly.** `org.jetbrains.skia` is an `implementation` dependency of Rentile, so it
is on the runtime classpath but not the compile classpath; the spike reaches it by reflection.
`Surface.Companion.makeRasterN32Premul(64, 64)` is a JNI entry point that cannot return without the native
library:

```
SKIA direct surface=Surface(_ptr=0x-4bffff82729d2180, refCount=1)
SKIA direct encoded 871 PNG bytes
SKIA nativeLibraryDirectories=/data/app/~~JSfAdMmYyx7Z…/lib/arm64,
                              /data/app/~~JSfAdMmYyx7Z…/base.apk!/lib/arm64-v8a
```

A real native pointer, a circle drawn, an image snapshotted and PNG-encoded by Skia.

**The host control, run in this checkout today.** The same reflective call from `androidHostTest`:

```
HOST skia outcome=FAILURE java.lang.ExceptionInInitializerError ->
  org.jetbrains.skiko.LibraryLoadException: Cannot find libskiko-macos-arm64.dylib.sha256,
  proper native dependency missing.
```

So `CLAUDE.md`'s "Android host tests … cannot execute Rentile's Skia rasterization at all" is **confirmed
and correctly scoped**: the host JVM looks for a *macOS* skiko binary that the Android AAR does not and
should not carry. **The limitation is about the host JVM, not about Android**, exactly as the measurements
document predicted, and now measured on both sides.

### One trap, recorded because it produced a false negative here

The test APK's merged manifest carries `android:extractNativeLibs="false"`, so the `.so` is mmap'd straight
out of the APK. `/proc/self/maps` therefore names `…/base.apk` and **never**
`libskiko-android-arm64.so`. Grepping the process maps for `skiko` returns nothing on a device where Skia is
demonstrably loaded — which is what happened on the first attempt here, and it looked for a minute like a
refutation. The loader's own `nativeLibraryDirectories` is the honest place to look.

## The emulator, as a second reading — and one correction

`Pixel_10_Pro_XL` was booted headless with **no `-gpu` flag at all** (`hw.gpu.mode=auto` in its
`config.ini`), and the full 1,125-test suite passes there too. Its application-level EGL context reports:

```
EGL 1.4 pbuffer 512x512 is current
GL_VERSION=OpenGL ES 3.1 (OpenGL ES 3.1.0 (ANGLE 2.1.1 git hash: fbf66f49c7cc))
GL_RENDERER=Android Emulator OpenGL ES Translator (ANGLE (Google, Vulkan 1.3.0
            (SwiftShader Device (LLVM 10.0.0) (0x0000C0DE)), SwiftShader driver-5.0.0))
GL_VENDOR=Google (Google Inc. (Google))
GL_SHADING_LANGUAGE_VERSION=OpenGL ES GLSL ES 3.10
GL_SUBPIXEL_BITS=4
GL_MAX_TEXTURE_SIZE=8192
52 extensions
```

Two things follow.

**The measurements document's open caveat is closed.** It said: *"that this specific string describes what
a RenG instrumented test would see has not been verified — verifying it needs an instrumented test, and
there is none."* There is one now, and the answer is yes: an application EGL context reports the same
translator, the same ANGLE build and the same SwiftShader backend that `dumpsys SurfaceFlinger` reported.

**One correction to that document.** It records that the emulator's *default* host backend on Apple silicon
is ANGLE-over-Metal, and that `-gpu swiftshader_indirect` was load-bearing for its SwiftShader reading. On
this machine today, with `hw.gpu.mode=auto` and no flag, `Pixel_10_Pro_XL` selects
**ANGLE-over-Vulkan-over-SwiftShader**. Whether that is an AVD-level setting, an emulator-version change or
a host-capability probe was not chased. **A Cycle H job must therefore read the renderer string it actually
got rather than assume which backend a flag selected** — which the spike test does, and which is the
cheapest possible guard.

## What the repository policy checker demands

By owner ruling this spike goes around the checker rather than satisfying it, so this is a statement of the
bill, not work done. `PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .` reports
exactly:

```
kmp/build.gradle.kts:1:FORBIDDEN_CYCLE_B_DEPENDENCY:Cycle B :kmp dependencies must be exactly commonMain
  implementation(libs.rentile.kmp) and commonTest implementation(kotlin("test"))
kmp/build.gradle.kts:54:FORBIDDEN_CYCLE_B_DEPENDENCY:Cycle B :kmp dependencies must be exactly commonMain
  implementation(libs.rentile.kmp) and commonTest implementation(kotlin("test"))
```

Two distinct rules, at two lines:

- **`:1` is the fingerprint.** `kmp/build.gradle.kts` is one of four files pinned in
  `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS` (`tools/check_repository_policy.py:241`) with exactly two
  accepted token-stream digests. Any token change — `withDeviceTest`, `applyDefaultHierarchyTemplate`, the
  new source-set block — moves it. These are **not** whole-file SHA-256 digests;
  `_build_configuration_fingerprint` (`:846`) tokenises first, so comments and whitespace are free and
  tokens are not. `HANDOFF.md:223-253` gives the derivation and the recompute command, plus the trap that
  the second accepted digest cannot be recomputed from disk.
- **`:54` is `check_dependencies` (`:1638`).** It asserts
  `len(dependency_calls) == len(main_calls) + len(test_calls)` where `main_calls` and `test_calls` are
  scoped to `commonMain` and `commonTest` alone. **Any `dependencies { }` call anywhere else in the file is
  refused by construction**, so an `androidDeviceTest` block declaring `androidx.test:runner` cannot pass as
  the rule is written.

**The smallest honest permanent change**, stated so a Cycle H task does not have to rediscover it, is
three-part and none of it is a loosening of the *spirit* of the rule:

1. Recompute both accepted fingerprints for `kmp/build.gradle.kts` (the current form and the pre-Cycle-C
   coroutines-free form, the latter reconstructed by stripping the coroutines lines) and replace them.
2. Give `check_dependencies` an explicit third scope — an `androidDeviceTest` range checked against a
   `_PERMITTED_DEVICE_TEST_DEPENDENCIES` allowlist in the same shape as `_PERMITTED_NEW_TEST_DEPENDENCIES`
   (`:73`) — and widen the total to `main + test + device`. That keeps the rule exact rather than merely
   relaxing it: it still names every permitted coordinate.
3. Add the two fixtures in `tools/tests/test_check_repository_policy.py` that the new scope needs.

Nothing else in the checker objects. `_production_kotlin_sources` (`:1937`) already excludes any source-set
directory whose first path component ends in `test`, so `androidDeviceTest/**` is invisible to the
production scans, and `_unexpected_build_logic_file` (`:1275`) already permits `.kt` anywhere under
`kmp/src`. **No ABI movement**: `kmp/api/kmp.klib.api` is byte-identical and `:kmp:checkKotlinAbi` passes.

## What was not checked, and what would settle it

- **`BasemapReadbackSuite` and `ModelReadbackSuite` on Android.** Both live in `kmp/src/nativeTest/`, a
  Kotlin/Native source set an Android JVM test cannot see. Moving them is not a file move: they are shaped
  around `nativeTest`-only affordances and one of them, the basemap suite, needs the Rentile engine to
  rasterise, which — per Step 6 — now demonstrably works on a device. The honest options are (a) copy, as
  this spike did with the probe, which duplicates ~1,500 lines and guarantees drift; (b) move both suites to
  `commonTest` and let every target that has a context call them, which is the shape the conformance suite
  already has and is almost certainly right; or (c) leave them native-only and accept that Android's gate is
  the conformance suite plus the rasterisation probe. **Option (b) is the one worth costing**, and it is a
  Cycle H task in its own right, not a line item. The probe result above says nothing about it would need a
  tolerance change.
- **A second GPU vendor.** Every device number here is one Adreno 830 on one Android 16 build. Mali,
  PowerVR, Xclipse and older Adreno are unmeasured. The probe is the cheap way to keep that honest per
  device.
- **`Pixel_4_XL_API_29`** was not booted. API 29 against `minSdk = 30` would not install this APK anyway.
- **CI.** `ci.yml`'s two jobs are hosted Ubuntu and macOS runners with no device and no emulator. Whether
  `connectedAndroidDeviceTest` can be gated in CI at all — through AGP's Gradle Managed Devices, which
  `withDeviceTest {}` brings with it — was not attempted here. Given the emulator result above, it is
  plausible and worth a task.
- **Whether the whole `commonTest` suite *should* run on a device.** It does, in 11–30 seconds, and that is
  a nice accident rather than a decision. Nothing about running 1,100 pure-logic tests on a phone earns its
  place in CI; the Android-specific value is the ~10 tests that touch GL and Skia.

## What the spike changed in the tree

Five commits on `spike/h-android-gles`, all test-source-only except the build file:

- `0fe0926` — `withDeviceTest {}`, the `androidDeviceTest` dependency block, the `EGL14` pbuffer fixture and
  the three GL measurements.
- `af327da` — the copied rasterisation probe and the copied Skia rasterisation test.
- `6db9d63` — the direct reflective Skia probe and the `androidHostTest` control.
- `8f0d7b1` — `applyDefaultHierarchyTemplate()`, and the record of what it fixes.
- this document.

**`kmp/src/commonMain/` is untouched.** No production code changed anywhere. This is spike code: the
`androidDeviceTest` tests should be reshaped into a proper `AndroidGlConformanceTest` matching
`LinuxGlConformanceTest`'s structure, the two copied files should be resolved by the source-set decision
above rather than left as copies, and the policy checker should be changed as described rather than
side-stepped, when Cycle H is scoped.

## Verdict on cost

The Android half of Cycle H is **cheaper than the iOS half was**, and for a reason worth stating plainly:
iOS needed a spike to discover *which klib* held `EAGLContext`, and needed a signed `.app`, a provisioning
profile and a watchdog workaround to run a single test binary on hardware. Android needs three lines of
Gradle, one `EGL14` fixture, and `adb`. The tooling is a solved problem that AGP already ships.

What it actually costs is: one build-configuration decision with a silent trap
(`applyDefaultHierarchyTemplate()`), one repository-policy change of about thirty lines plus fixtures, and
one genuine design question — where the readback suites should live so that more than two targets can run
them. **None of it is a rendering problem, because `AndroidGlBinding.kt` was already correct.**
