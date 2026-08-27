# Stand on deprecated OpenGL ES for iOS

RenG's two iOS targets get their GL through Apple's `OpenGLES.framework`, reached from the stock
Kotlin/Native `platform.gles3` and `platform.EAGL` klibs, with no cinterop, no third-party binary and no
change to `kmp/build.gradle.kts`. Apple deprecated that framework in iOS 12, in 2018. ADR 0010 made every
published target a permanent commitment, because removing one later breaks resolution for anyone who
adopted it — so two of RenG's six targets now rest on an API its vendor has told people to stop using, and
that is a decision rather than an accident. This ADR is the only place in the project where it is written
down.

**It has to be written down here, because the build will never say it.** Kotlin/Native's cinterop does not
translate Apple's `API_DEPRECATED` into Kotlin's `@Deprecated`. The C headers carry the annotation plainly: in
`iPhoneSimulator26.5.sdk`, `OpenGLES.framework/Headers/EAGL.h:53` and `:69` mark `EAGLSharegroup` and `EAGLContext` with
`OPENGLES_DEPRECATED(ios(2.0, 12.0), tvos(9.0, 12.0))`, which `OpenGLESAvailability.h:10` expands to
`API_DEPRECATED("OpenGLES API deprecated. …")`, and `ES3/gl.h:877` gives `glReadPixels` the same treatment.
By the time Kotlin sees it, it is gone: the `OpenGLES3` klib metadata dump for `ios_simulator_arm64`
contains **zero** occurrences of `Deprecated` across all 3,099 lines, and the `EAGL` dump's only
`@Deprecated` annotations are Kotlin/Native's own `"Use constructor instead"` markers on `init`/`alloc`. The
device and simulator dumps are byte-identical, so this is not a simulator artefact. `GLES_SILENCE_DEPRECATION`
is irrelevant, because there is no warning to silence. Reproduce it with
`~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21/bin/klib dump-metadata` against the `EAGL` and
`OpenGLES3` platform klibs; JetBrains documents no policy either way, so this is measured rather than cited.
The consequence is blunt: **no compilation of RenG will ever warn that iOS GL is deprecated**, so if the
fact is not in an ADR, in `CLAUDE.md` and in release notes, it is nowhere.

**It works, and that was measured rather than assumed.** On the simulator, `EAGLContext(kEAGLRenderingAPIOpenGLES3)`
yields a real `OpenGL ES 3.0` context on iOS 26.0, 26.2 and 26.5, each pinned by UDID, five of five spike
cases passing on every one. On an attached iPhone 12 (`iPhone13,2`) running iOS 26.4.2, the same code
reports `GL_RENDERER=Apple A14 GPU`, every one of the 91 `platform.gles3` entry points `IosGlBinding.kt`
imports resolves at link and at call, `runGlConformanceSuite(…, ShaderDialect.GLES)` passes all eight checks
with the *real* cross-dialect link and not the `SKIP_ON_LINUX_MESA_LINK_SEGFAULT` escape hatch Linux needs,
and `runModelReadbackSuite` passes all six pixel relationships unmodified with no tolerance change. Running
the roster is the only thing that proves anything here, because `openPlatformGlBinding()` returns
`GlBindingResult.Bound(IosGlBinding)` unconditionally (`IosGlBinding.kt:599`) — it cannot report a failure to
find the framework. Eight years after the deprecation, on the newest iOS anyone has, the answer is that it
functions exactly like the two targets that were never in doubt.

**What Apple has said, and what it has not.** `EAGLContext` is deprecated from iOS 12.0 with
`unavailable: false` on every platform, and has never been marked removed or obsoleted anywhere. The
framework ships in both the `iPhoneOS26.5` and `iPhoneSimulator26.5` SDKs and in the 26.0, 26.2 and 26.5
simulator runtimes. The iOS & iPadOS 26 release notes contain no occurrence of "OpenGL", "OpenGLES", "EAGL"
or "GLKit" at all. That is evidence of continued shipping and it is not a support statement; **absence of a
removal notice must not be written up as a commitment**, and this ADR deliberately does not. Apple's
recommendation is Metal and only Metal, and the migration material it provides is a sample project and
WWDC19 session 611 — there is no shim, no compatibility layer and no automated path.

**ANGLE was the alternative on the table, and the device measurement reframes it rather than settling it on
principle.** The iPhone reports `GL_VERSION=OpenGL ES 3.0 Metal - 104.1`: **Apple's own OpenGL ES on iOS is
already implemented over Metal.** Adopting ANGLE would therefore replace Apple's Metal translation with
Google's for an API Apple is already translating — a materially different proposition from "GLES is
unsupported on iOS and needs an emulation layer", which is how the option was framed before any of this was
measured. The remaining case for ANGLE is that it is not deprecated, and that is real; what it costs is
where it fails.

**And the sources on ANGLE-on-iOS conflict, which is itself the finding.** ANGLE's README gives Metal as
*complete* on iOS, footnoted "Metal supported on iOS 12+". ANGLE's own build documentation (DevSetup) opens
by saying you can build and run OpenGL ES applications on "Windows, Linux, Mac and Android" — iOS absent —
and its iOS section says this "is currently possible only from Chromium checkout", with
`target_environment = "simulator"`, `target_cpu = "x64"`, and only `angle_end2end_tests` and
`angle_white_box_tests` supported as build targets. Two official Google documents, two different answers.
The README describes backend feature coverage and the build docs describe what can actually be produced,
and only the second one is a delivery path. Neither is picked here in preference to the other; both are
recorded, because the next person to weigh this will otherwise read the README alone and conclude the
option is cheap.

**It is not cheap, and the size class is measurable.** ANGLE publishes no prebuilt binaries and no Maven,
CocoaPods, SwiftPM or XCFramework artifact; the output of a build is `libEGL`/`libGLESv2`, per target,
rebuilt on every bump. **Nobody has driven ANGLE from Kotlin/Native on any platform** — three separate
`gh search code` queries returned zero hits, and every Kotlin-to-ANGLE binding in public code is JVM
desktop, including Skiko's own. So RenG would be inventing the pattern rather than following one. The
nearest analogue is both the mechanism it would use and the price: JetBrains ships Skia to Kotlin/Native iOS
consumers through a cinterop klib, and `skiko-iosarm64-0.148.2.klib` is 57,634,037 bytes, carrying
`libskia.a` at 35,715,144 bytes among roughly 164 MB of static archives for one target. Adopting that shape
ends two properties RenG states today — no cinterop and no third-party native binary on any target (ADR 0009,
ADR 0022) — against a repository policy that forbids binary extensions outright
(`tools/check_repository_policy.py:263`), and a Maven-published klib does not carry a native binary into a
consumer's link step, so the consumer would have to link it, which breaks "RenG is a single coordinate you
resolve". None of that is an argument that ANGLE is wrong; it is the bill, and the bill buys nothing that is
currently blocked.

**So the cost of being wrong, stated plainly: if Apple removes `OpenGLES.framework`, two published targets
break, with no warning from any build, and RenG finds out when someone compiles against a new SDK — or when
a consumer does.** The fallback is a real cycle of work and not a patch: a Metal or an ANGLE backend written
behind `GlBinding`. What limits the blast radius is that the coupling is one production file,
`IosGlBinding.kt`, plus a test fixture, and that iOS context creation stays the consumer's responsibility in
production exactly as on every other target — RenG owns no window and draws onto a caller-supplied surface.
The seam is the mitigation, and it already exists. Nothing about the timing is predictable, and this ADR
claims no forecast; what it claims is that the risk is now recorded somewhere a person will read, since the
compiler has been measured never to record it.

Three limits on the evidence, stated rather than buried. Every reading here is iOS 26.x — the newest
published field report of GLES on an iOS simulator stops at 17.5, so this project is measuring two major
versions past anyone else's write-up. Exactly one device GPU, an A14, has run any of it. And Apple's own DTS
has acknowledged an `EXC_BAD_ACCESS` on *any* `glDrawElements`, on Apple silicon simulators only (developer
forums thread 756598, FB13850673); RenG's model draw path issues `binding.drawElements` and did not
reproduce it on iOS 26.0, 26.2 or 26.5, so for this configuration it does not occur — but it is retained
here as a live hazard for anyone testing against an older simulator runtime, because the threads are easy to
find and easy to misread as proof the iOS gate is unusable.

One smaller thing is undocumented rather than deprecated, and belongs on the record beside it. Apple
documents offscreen framebuffers with renderbuffer attachments and `glReadPixels`, and documents the
`CAEAGLLayer` path separately as an alternative, but never states that a context which never touches a
drawable is supported. RenG's iOS test fixture is exactly that context, and it works on four runtimes and
one device. Treat it as very likely and unproven-by-documentation. It is test-fixture territory only,
because in production the context is the consumer's.
