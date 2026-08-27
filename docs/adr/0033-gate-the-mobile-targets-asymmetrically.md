# Gate the mobile targets asymmetrically

`iosSimulatorArm64Test` joins `ci.yml`'s `apple-publication` job, and no Android emulator joins CI at all.
Android's device gate is `:kmp:connectedAndroidDeviceTest` against attached hardware, and iOS's is
`tools/run_ios_device_tests.py` against an attached, provisioned iPhone. So one mobile target is gated by a
machine and the other by a person with a phone, and this ADR exists to say why, and to fix what a release
may claim as a result.

**The asymmetry reflects cost, not importance.** `macos-latest` is arm64 and already runs `macosArm64Test`
in the `apple-publication` job (`.github/workflows/ci.yml:70`, `:84`), so `iosSimulatorArm64Test` is one more
test task on a runner that is already provisioned, and it buys a second GLES dialect exercised under real
linkage. An Android emulator on `ubuntu-latest` needs KVM and costs minutes — and, the part that actually
decides it, **picks its rasteriser from AVD configuration rather than from a documented default.** Measured:
`Pixel_10_Pro_XL` booted with `hw.gpu.mode=auto` and no `-gpu` flag selected ANGLE-over-Vulkan-over-SwiftShader,
not the ANGLE-over-Metal that the emulator's own host libraries suggest — `lib64/gles_angle/libGLESv2.dylib`
is an arm64 Mach-O containing `ANGLE Metal Renderer`, `DisplayMtl` and `ContextMtl`. Google publishes no
GLES version for the emulator and by design cannot: the shipped `advancedFeatures.ini` documents that the
guest's maximum is computed from the host GL stack at boot (`GLESDynamicVersion`). A gate whose rasteriser is
chosen for it by the machine it happens to run on is a gate whose readings drift without anyone changing
RenG, which is the opposite of what a gate is for.

**The consequence this ADR exists to make impossible to miss: both automated rasterisers are software, so
the automated gate certifies logic and linkage, not rasterisation.** Hosted macOS runners have no GPU, so
`iosSimulatorArm64Test` in CI runs on `Apple Software Renderer` — the exact driver that failed `0.3.0`'s
first publication attempt. That is not an inference from the failure: the iOS 26.2 simulator runtime ships
exactly one OpenGL ES renderer bundle, `GLRendererFloat.bundle`, whose binary contains the literal string
`Apple Software Renderer`, and carries no hardware GL driver at all, where the host macOS stack has
`AppleMetalOpenGLRenderer.bundle` and vendor driver bundles beside it. The emulator's is SwiftShader. What
the automated gate therefore proves is that all 91 roster entry points resolve, that the cross-dialect link
succeeds, that GL state round-trips, that the shader dialect matrix compiles, that the offscreen composite
and lifecycle paths run — every one a real regression class, and collectively the whole reason to have the
gate. What it does not prove is that a mobile GPU draws the frame correctly. **A green CI run must not be
read as saying it does.**

**Only the manual device runs certify pixels, and the numbers are unambiguous about which reading to
trust.** `measureLargeQuadRasterisation` reports **3,040 mismatched pixels against a 512-pixel budget** on
the iOS simulator, on all three runtimes, and declares the driver untrustworthy; an Apple A14 reports **0**,
and an Adreno 830 reports **0**. The simulator is provably not a GPU proxy independently of the probe, too:
it reports `GL_SUBPIXEL_BITS = 10` and `GL_MAX_TEXTURE_SIZE = 4096` where both the A14 and an M3 Max report
4 and 16384 — it matches macOS's *software* path, not its Metal one. Three numbers this project's own
research first wrote down as "iOS constraints" turned out to be simulator artefacts, corrected in the
spike's own addendum. **The simulator is a logic gate and can never be a GPU proxy.**

One nuance, because "both are software" is the right summary and the wrong inference if it is read as "both
fail". The Android emulator's ANGLE-over-SwiftShader also reports **0** disagreeing pixels, with
`GL_SUBPIXEL_BITS = 4` and `GL_MAX_TEXTURE_SIZE = 8192`. Software rasterisation is not one behaviour: one of
the two automated rasterisers passes this probe and the other fails it. That does not promote the emulator
to a GPU proxy — it measured a backend it was handed rather than one anybody requested, and the next host
may hand it another. It means the probe measures the driver in front of it and nothing else, which is
exactly what it was built to do.

**What a skip means, stated so nobody reads one as a pass.** `measureLargeQuadRasterisation`
(`kmp/src/nativeTest/kotlin/com/rohittp/reng/BasemapReadbackSuite.kt:669`) draws the fixture's own four
ground-tile footprints at the fixture's real clip `w` and counts pixels disagreeing with the analytic
rectangle. When it finds the driver untrustworthy, **exactly one case skips out loud and the other four
still run**; a probe that cannot run fails rather than skips. That is `0.3.0`'s answer and this cycle
changes none of it — the response to a bad rasteriser is to confine the loss to the one case that depends on
rasterisation, never to loosen every budget until the bad driver passes. So a green `iosSimulatorArm64Test`
with a skipped ground-coverage case is the designed outcome rather than a defect, and it says nothing
whatever about whether the ground drew. Loosening the budgets instead was rejected once already, for
`0.3.0`, and for the reason that still holds: a tolerance wide enough to admit a dropped primitive admits
every real defect too.

**So what a release may claim.** All six targets publish at every release and this cycle changes none of
that; what it changes is which of them anyone has executed. `CLAUDE.md` already carries the standing rule —
"which of them anyone has actually executed belongs in release notes rather than being discovered by an
Android consumer" (`CLAUDE.md:118`) — and this is where that promise comes due, so it is stated as an
obligation and not as advice. The release notes must say, per target, what ran and on what: which suites CI
executed and on which rasteriser, naming the software renderer where one was used; which suites ran on named
hardware on a developer's machine, at a named commit, or did not run at all this cycle; and which targets
were compiled only. A release may claim that a target's logic and linkage are gated. It may claim pixels only
for drivers a run actually measured. It may never report a device result from a simulator run, and this ADR
exists partly because that particular substitution is the easy mistake, not the exotic one.

**Three costs, none of them hypothetical.** Android's gate is only as current as the last time somebody
plugged in a phone, and its failure mode is silence — the same failure mode that let a binding ship through
three releases unexecuted, moved one level up rather than removed. One GPU vendor per platform has been
measured, an A14 and an Adreno 830; Mali, PowerVR, Xclipse and older Adreno are unmeasured, and the probe is
the cheap way to keep each new device honest rather than a claim that they will agree. And the iOS device
run is fragile in a specific way: it needs a signed `.app`, a provisioning profile covering the device, and a
filtered launch, because an unfiltered run is SIGKILLed part-way through by the watchdog — the test binary has
no UI and never becomes responsive, which from outside looks like a white screen closing and is easy to
mistake for a crash in RenG. `tools/run_ios_device_tests.py` exists so that this is one command, on the
plain argument that a gate taking five manual steps is one nobody runs; if that tool rots, the iOS device
gate becomes exactly as unexecuted as the bindings it was built to watch.

Two alternatives were weighed and rejected. Gating both targets in CI fails on the emulator's rasteriser
being an AVD property rather than a decision, so the gate's meaning would change under it without a commit;
a job could pin a backend and print what it got, which is the cheapest possible guard, but pinning a
*software* backend buys a second reading of the thing the iOS simulator already certifies and still no GPU.
Gating neither in CI and running both by hand is symmetric and worse: it throws away the one automated
reading available for free, and this project's own history is the argument against trusting an unautomated
gate. Nothing here supersedes another ADR; it records what the gate covers and what it does not.
