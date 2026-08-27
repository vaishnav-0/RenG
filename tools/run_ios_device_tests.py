#!/usr/bin/env python3
"""Run RenG's Kotlin/Native iOS test binary on an attached iPhone, in one command.

Why this exists. Kotlin/Native gives every other target a Gradle test task; the iOS device
target gets none. ``:kmp:linkDebugTestIosArm64`` produces
``kmp/build/bin/iosArm64/debugTest/test.kexe``, an arm64 Mach-O, and stops there, because iOS
will not execute a bare Mach-O. Getting it to run means wrapping it as an application bundle,
signing that bundle against a provisioning profile covering the device, installing it, and
launching it with the console attached -- five manual steps and a provisioning profile. A gate
that takes five manual steps is one nobody runs, which would make "the device asserts
everything" a claim rather than a practice.

**The filter is not a convenience.** Launched unfiltered the binary runs every test in the
module -- roughly 1,150 of them. The application has no UI, never becomes responsive, and
iOS's watchdog SIGKILLs it part-way through. From the outside that looks like a white screen
that closes, which reads as a crash in RenG and is not one. Scoped to the GL tests the run
finishes in about 310 ms and exits 0, so the filter defaults to the GL tests and an empty one
is refused. A pattern that would select the whole module is allowed, loudly: refusing a
deliberate choice is not this tool's job, but letting someone walk into the watchdog silently
is exactly what it exists to prevent.

The entitlements are extracted from the provisioning profile itself rather than written here,
so the signature can never claim more than the profile grants. An install the profile does not
cover then fails as a provisioning error instead of as a silent capability mismatch. The only
narrowing applied is the one Xcode applies: a wildcard ``application-identifier`` becomes the
concrete bundle identifier being signed.

It is a helper, not a gate. Nothing in CI runs it: it needs a physical phone, a signing
identity and a provisioning profile, all three of which differ per developer and none of which
can be checked in.
"""

from __future__ import annotations

import argparse
import plistlib
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Sequence

DEFAULT_TEST_BINARY = Path("kmp/build/bin/iosArm64/debugTest/test.kexe")
DEFAULT_STAGING_DIRECTORY = Path("build/ios-device-test")
DEFAULT_BUNDLE_IDENTIFIER = "com.rohittp.reng.devicetests"
DEFAULT_FILTER = "com.rohittp.reng.internal.gl.IosGlConformanceTest.*"
DEFAULT_MINIMUM_OS_VERSION = "14.0"
DEFAULT_GRADLE = "./gradlew"

APPLICATION_NAME = "RenGDeviceTests"
LINK_TASK = ":kmp:linkDebugTestIosArm64"

# An iOS hardware UDID is either 8 hex + "-" + 16 hex (iPhone X and later) or 40 hex.
# ``devicectl`` also accepts a CoreDevice UUID or a device name, which look like neither and
# which a provisioning profile's device list cannot be checked against.
_HARDWARE_UDID = re.compile(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}$|^[0-9A-Fa-f]{40}$")

# The Kotlin/Native test runner's default logger is GTEST-shaped.
_RAN_SUMMARY = re.compile(r"^\[=+\]\s+(\d+) tests? from (\d+) test cases? ran\.")
_PASSED_SUMMARY = re.compile(r"^\[\s*PASSED\s*\]\s+(\d+) tests?\.")
_FAILED_LINE = re.compile(r"^\[\s*FAILED\s*\]\s+(.*)$")


class DeviceRunError(Exception):
    """Anything that stops the run, reported as one sentence rather than as a traceback."""


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    output: str


Runner = Callable[..., CommandResult]


@dataclass(frozen=True)
class ProvisioningProfile:
    name: str
    application_identifier: str
    entitlements: dict
    provisioned_devices: tuple[str, ...]
    expiration: datetime | None

    @property
    def is_wildcard(self) -> bool:
        return self.application_identifier.endswith(".*")

    @property
    def granted_bundle_identifier(self) -> str:
        return self.application_identifier.partition(".")[2]


@dataclass(frozen=True)
class TestOutcome:
    ran: int | None
    passed: int | None
    failures: tuple[str, ...]

    @property
    def saw_summary(self) -> bool:
        return self.ran is not None or self.passed is not None


def link_command(gradle: str) -> list[str]:
    return [gradle, "--no-configuration-cache", LINK_TASK]


def profile_dump_command(profile: Path) -> list[str]:
    return ["security", "cms", "-D", "-i", str(profile)]


def codesign_command(identity: str, entitlements: Path, application: Path) -> list[str]:
    return [
        "codesign",
        "--force",
        "--sign",
        identity,
        "--entitlements",
        str(entitlements),
        "--timestamp=none",
        "--generate-entitlement-der",
        str(application),
    ]


def install_command(device: str, application: Path) -> list[str]:
    return [
        "xcrun",
        "devicectl",
        "device",
        "install",
        "app",
        "--device",
        device,
        str(application),
    ]


def launch_command(device: str, bundle_identifier: str, test_filter: str) -> list[str]:
    return [
        "xcrun",
        "devicectl",
        "device",
        "process",
        "launch",
        "--device",
        device,
        "--console",
        "--terminate-existing",
        bundle_identifier,
        f"--ktest_filter={test_filter}",
    ]


def uninstall_command(device: str, bundle_identifier: str) -> list[str]:
    return [
        "xcrun",
        "devicectl",
        "device",
        "uninstall",
        "app",
        "--device",
        device,
        bundle_identifier,
    ]


def information_property_list(
    bundle_identifier: str,
    executable_name: str,
    minimum_os_version: str,
) -> dict:
    """The smallest ``Info.plist`` iOS will install and launch.

    ``UILaunchScreen`` is present and empty on purpose: without it an application built against
    a modern SDK is treated as a compatibility-mode app. The bundle carries no UI regardless --
    the executable is a test binary that prints to stdout and exits.
    """
    return {
        "CFBundleDevelopmentRegion": "en",
        "CFBundleExecutable": executable_name,
        "CFBundleIdentifier": bundle_identifier,
        "CFBundleInfoDictionaryVersion": "6.0",
        "CFBundleName": executable_name,
        "CFBundlePackageType": "APPL",
        "CFBundleShortVersionString": "1.0",
        "CFBundleSupportedPlatforms": ["iPhoneOS"],
        "CFBundleVersion": "1",
        "MinimumOSVersion": minimum_os_version,
        "UIDeviceFamily": [1, 2],
        "UILaunchScreen": {},
    }


def parse_provisioning_profile(document: bytes) -> ProvisioningProfile:
    try:
        parsed = plistlib.loads(document)
    except Exception:
        raise DeviceRunError(
            "Could not read the provisioning profile: 'security cms -D' did not return a "
            "property list"
        ) from None
    if not isinstance(parsed, dict):
        raise DeviceRunError("Provisioning profile does not contain a property list dictionary")

    entitlements = parsed.get("Entitlements")
    if not isinstance(entitlements, dict):
        raise DeviceRunError("Provisioning profile carries no Entitlements dictionary")

    identifier = entitlements.get("application-identifier")
    if not isinstance(identifier, str) or "." not in identifier:
        raise DeviceRunError(
            "Provisioning profile carries no usable application-identifier entitlement"
        )

    devices = parsed.get("ProvisionedDevices") or ()
    expiration = parsed.get("ExpirationDate")
    return ProvisioningProfile(
        name=str(parsed.get("Name", "<unnamed>")),
        application_identifier=identifier,
        entitlements=entitlements,
        provisioned_devices=tuple(str(device) for device in devices),
        expiration=expiration if isinstance(expiration, datetime) else None,
    )


def entitlements_for_bundle(profile: ProvisioningProfile, bundle_identifier: str) -> dict:
    """The profile's own entitlements, with only Xcode's wildcard narrowing applied.

    Nothing is added. A wildcard ``application-identifier`` (``TEAMID.*``) is replaced by the
    concrete ``TEAMID.<bundle identifier>`` being signed, which is a narrowing: the signature
    still cannot claim anything the profile does not grant.
    """
    entitlements = dict(profile.entitlements)
    identifier = entitlements.get("application-identifier")
    if isinstance(identifier, str) and identifier.endswith(".*"):
        entitlements["application-identifier"] = identifier[:-1] + bundle_identifier
    return entitlements


def check_profile(
    profile: ProvisioningProfile,
    device: str,
    bundle_identifier: str,
    now: datetime | None = None,
) -> tuple[str, ...]:
    """Fail before touching the phone on the three things that produce opaque install errors."""
    notes: list[str] = []
    moment = now or datetime.now()

    if profile.expiration is not None and profile.expiration <= moment:
        raise DeviceRunError(
            f"Provisioning profile '{profile.name}' expired on {profile.expiration:%Y-%m-%d}; "
            "open Xcode once with the device attached to have it renewed"
        )

    if not profile.is_wildcard and profile.granted_bundle_identifier != bundle_identifier:
        raise DeviceRunError(
            f"Provisioning profile '{profile.name}' grants only the bundle identifier "
            f"'{profile.granted_bundle_identifier}', but this run is configured for "
            f"'{bundle_identifier}'. Installing under the profile's own identifier would "
            "replace whatever application already owns it on the device. Use a wildcard "
            f"development profile, or pass --bundle-id {profile.granted_bundle_identifier} "
            "deliberately."
        )

    if not profile.provisioned_devices:
        notes.append(
            f"profile '{profile.name}' lists no provisioned devices, so device coverage was "
            "not checked"
        )
    elif not _HARDWARE_UDID.fullmatch(device):
        notes.append(
            f"--device '{device}' is not a hardware UDID, so its coverage by profile "
            f"'{profile.name}' was not checked"
        )
    elif not any(device.lower() == known.lower() for known in profile.provisioned_devices):
        raise DeviceRunError(
            f"Provisioning profile '{profile.name}' does not cover device {device}; it lists "
            f"{len(profile.provisioned_devices)} other device(s). Attach the phone and build "
            "any project once with 'xcodebuild -allowProvisioningUpdates' to have Xcode add it."
        )

    return tuple(notes)


def check_filter(test_filter: str) -> tuple[str, ...]:
    """Refuse an empty filter, and warn loudly about one that selects the whole module."""
    if not test_filter.strip():
        raise DeviceRunError(
            "An empty --filter runs every test in the module, which iOS's watchdog SIGKILLs "
            "part-way through because the application never becomes responsive. Pass a "
            f"pattern; the default is '{DEFAULT_FILTER}'."
        )
    if test_filter.strip() in {"*", "*.*", "**"}:
        return (
            f"--filter '{test_filter}' selects every test in the module. iOS's watchdog "
            "SIGKILLs an unresponsive application part-way through, so expect a truncated run "
            "rather than a verdict.",
        )
    return ()


def summarise_test_output(output: str) -> TestOutcome:
    """Read the verdict out of the Kotlin/Native GTEST logger's own summary lines."""
    ran: int | None = None
    passed: int | None = None
    failures: list[str] = []

    for line in output.splitlines():
        stripped = line.strip()
        ran_match = _RAN_SUMMARY.match(stripped)
        if ran_match:
            ran = int(ran_match.group(1))
            continue
        passed_match = _PASSED_SUMMARY.match(stripped)
        if passed_match:
            passed = int(passed_match.group(1))
            continue
        failed_match = _FAILED_LINE.match(stripped)
        if failed_match:
            detail = failed_match.group(1).strip()
            if detail and not detail.endswith("listed below:"):
                failures.append(detail)

    return TestOutcome(ran=ran, passed=passed, failures=tuple(failures))


def verdict(outcome: TestOutcome, test_filter: str) -> None:
    """Turn an outcome into either silence or one explained failure."""
    if outcome.failures:
        listed = "\n".join(f"  {failure}" for failure in outcome.failures)
        raise DeviceRunError(f"{len(outcome.failures)} test(s) failed on the device:\n{listed}")
    if not outcome.saw_summary:
        raise DeviceRunError(
            "The test binary printed no run summary, so it never reached the end of its run. "
            "That is what iOS's watchdog looks like: an application with no UI never becomes "
            "responsive and is SIGKILLed part-way through, which from outside looks like a "
            "white screen that closes. Narrow --filter and run again."
        )
    if not outcome.ran:
        raise DeviceRunError(
            f"--filter '{test_filter}' matched no tests, so the device asserted nothing. "
            "A run that selects nothing exits 0 and proves nothing; check the pattern."
        )


def run_command(command: Sequence[str], *, stream: bool = False, quiet: bool = False) -> CommandResult:
    """Run one command, echoing it and -- unless it is ``quiet`` -- its output.

    The provisioning profile dump is ``quiet``: it is a property list naming every device on the
    team, and printing forty other people's UDIDs into a build log to read one field back is not
    a trade this tool makes.
    """
    print("+ " + " ".join(shlex.quote(part) for part in command), flush=True)
    if not stream:
        completed = subprocess.run(command, capture_output=True, text=True, errors="replace")
        output = completed.stdout + completed.stderr
        if output.strip() and not quiet:
            print(output.rstrip(), flush=True)
        return CommandResult(returncode=completed.returncode, output=output)

    lines: list[str] = []
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
        bufsize=1,
    )
    assert process.stdout is not None
    for line in process.stdout:
        print(line.rstrip("\n"), flush=True)
        lines.append(line)
    return CommandResult(returncode=process.wait(), output="".join(lines))


def _require(result: CommandResult, what: str) -> CommandResult:
    if result.returncode != 0:
        raise DeviceRunError(f"{what} failed with exit code {result.returncode}")
    return result


def stage_application(
    binary: Path,
    profile: Path,
    staging_directory: Path,
    bundle_identifier: str,
    minimum_os_version: str,
) -> Path:
    """Assemble the ``.app`` the phone will be given, from scratch every time."""
    application = staging_directory / f"{APPLICATION_NAME}.app"
    if application.exists():
        shutil.rmtree(application)
    application.mkdir(parents=True)

    executable = application / APPLICATION_NAME
    shutil.copy2(binary, executable)
    executable.chmod(0o755)

    with (application / "Info.plist").open("wb") as handle:
        plistlib.dump(
            information_property_list(bundle_identifier, APPLICATION_NAME, minimum_os_version),
            handle,
        )

    shutil.copy2(profile, application / "embedded.mobileprovision")
    return application


def run_device_tests(arguments: argparse.Namespace, runner: Runner = run_command) -> int:
    warnings = list(check_filter(arguments.filter))

    if not arguments.skip_link:
        gradle = Path(arguments.gradle)
        if gradle.name != arguments.gradle and not gradle.exists():
            raise DeviceRunError(
                f"No Gradle wrapper at '{arguments.gradle}'; run from the repository root, "
                "pass --gradle, or pass --skip-link to use an already linked binary"
            )
        _require(runner(link_command(arguments.gradle)), f"Gradle {LINK_TASK}")

    binary: Path = arguments.binary
    if not binary.is_file():
        raise DeviceRunError(
            f"No linked iOS device test binary at '{binary}'. Link it with "
            f"'./gradlew --no-configuration-cache {LINK_TASK}', or point --binary at one."
        )

    profile: Path = arguments.profile
    if not profile.is_file():
        raise DeviceRunError(
            f"No provisioning profile at '{profile}'. Xcode-managed profiles live in "
            "'~/Library/Developer/Xcode/UserData/Provisioning Profiles'; one covering this "
            "device is created by building any project once with "
            "'xcodebuild -allowProvisioningUpdates'."
        )

    dump = _require(
        runner(profile_dump_command(profile), quiet=True),
        "Reading the provisioning profile",
    )
    parsed = parse_provisioning_profile(dump.output.encode("utf-8", errors="replace"))
    warnings.extend(check_profile(parsed, arguments.device, arguments.bundle_id))

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)

    application = stage_application(
        binary=binary,
        profile=profile,
        staging_directory=arguments.staging_directory,
        bundle_identifier=arguments.bundle_id,
        minimum_os_version=arguments.minimum_os_version,
    )
    entitlements = arguments.staging_directory / "entitlements.plist"
    with entitlements.open("wb") as handle:
        plistlib.dump(entitlements_for_bundle(parsed, arguments.bundle_id), handle)

    _require(runner(codesign_command(arguments.identity, entitlements, application)), "codesign")
    _require(runner(install_command(arguments.device, application)), "devicectl install")

    try:
        launched = runner(
            launch_command(arguments.device, arguments.bundle_id, arguments.filter),
            stream=True,
        )
        outcome = summarise_test_output(launched.output)
        verdict(outcome, arguments.filter)
        if launched.returncode != 0:
            raise DeviceRunError(
                f"devicectl launch exited with code {launched.returncode} even though every "
                "reported test passed"
            )
    finally:
        if arguments.keep_installed:
            print(
                f"note: leaving {arguments.bundle_id} installed; remove it with "
                f"'{' '.join(uninstall_command(arguments.device, arguments.bundle_id))}'",
                file=sys.stderr,
            )
        else:
            runner(uninstall_command(arguments.device, arguments.bundle_id))

    print(f"{outcome.passed} test(s) passed on device {arguments.device}")
    return 0


class _ArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise DeviceRunError(f"Invalid command-line arguments: {message}")


def build_parser() -> argparse.ArgumentParser:
    parser = _ArgumentParser(
        prog="run_ios_device_tests.py",
        description=(
            "Link, wrap, sign, install and run RenG's iOS test binary on an attached iPhone."
        ),
        epilog=(
            "Why --filter has a default, and why it may not be empty:\n"
            "  An unfiltered run executes every test in the module. The application has no UI,\n"
            "  never becomes responsive, and iOS's watchdog SIGKILLs it part-way through --\n"
            "  from outside, a white screen that closes, which reads as a crash in RenG and is\n"
            "  not one. Scoped to the GL tests the run finishes in about 310 ms and exits 0.\n"
            "  The other tests are pure logic already covered on four targets; running them on\n"
            "  a phone buys nothing and is exactly what trips the watchdog.\n"
            "\n"
            "The signature claims exactly what the profile grants: entitlements are extracted\n"
            "from the profile rather than written here, so an install the profile does not\n"
            "cover fails as a provisioning error rather than as a silent capability mismatch.\n"
            "\n"
            "The application is uninstalled when the run ends, pass or fail."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--device",
        required=True,
        help="hardware UDID, CoreDevice identifier or name of the attached iPhone",
    )
    parser.add_argument(
        "--profile",
        required=True,
        type=Path,
        help="path to a .mobileprovision covering the device and the bundle identifier",
    )
    parser.add_argument(
        "--identity",
        required=True,
        help=(
            "codesigning identity, by name or SHA-1, as "
            "'security find-identity -v -p codesigning' lists it"
        ),
    )
    parser.add_argument(
        "--filter",
        default=DEFAULT_FILTER,
        help=f"--ktest_filter pattern for the run (default: {DEFAULT_FILTER}); see below",
    )
    parser.add_argument(
        "--bundle-id",
        default=DEFAULT_BUNDLE_IDENTIFIER,
        help=f"bundle identifier to install under (default: {DEFAULT_BUNDLE_IDENTIFIER})",
    )
    parser.add_argument(
        "--binary",
        default=DEFAULT_TEST_BINARY,
        type=Path,
        help=f"linked test binary (default: {DEFAULT_TEST_BINARY})",
    )
    parser.add_argument(
        "--staging-directory",
        default=DEFAULT_STAGING_DIRECTORY,
        type=Path,
        help=f"where the .app is assembled (default: {DEFAULT_STAGING_DIRECTORY})",
    )
    parser.add_argument(
        "--minimum-os-version",
        default=DEFAULT_MINIMUM_OS_VERSION,
        help=(
            "MinimumOSVersion for the generated Info.plist "
            f"(default: {DEFAULT_MINIMUM_OS_VERSION})"
        ),
    )
    parser.add_argument(
        "--gradle",
        default=DEFAULT_GRADLE,
        help=f"Gradle wrapper used to link the binary (default: {DEFAULT_GRADLE})",
    )
    parser.add_argument(
        "--skip-link",
        action="store_true",
        help=f"use the existing binary instead of running {LINK_TASK}",
    )
    parser.add_argument(
        "--keep-installed",
        action="store_true",
        help="leave the application on the phone instead of uninstalling it afterwards",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    try:
        arguments = parser.parse_args(argv)
        return run_device_tests(arguments)
    except DeviceRunError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
