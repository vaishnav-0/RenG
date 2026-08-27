import contextlib
import io
import plistlib
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run_ios_device_tests as tool


WILDCARD_PROFILE = {
    "Name": "iOS Team Provisioning Profile: *",
    "ExpirationDate": datetime(2099, 1, 1),
    "ProvisionedDevices": ["00008101-0006492C3E51001E", "00008030-000000000000001E"],
    "Entitlements": {
        "application-identifier": "ZG6QASWQ43.*",
        "com.apple.developer.team-identifier": "ZG6QASWQ43",
        "get-task-allow": True,
        "keychain-access-groups": ["ZG6QASWQ43.*", "com.apple.token"],
    },
}

SHIPPING_PROFILE = {
    "Name": "iOS Team Provisioning Profile: com.example.shipping",
    "ExpirationDate": datetime(2099, 1, 1),
    "ProvisionedDevices": ["00008101-0006492C3E51001E"],
    "Entitlements": {
        "application-identifier": "ZG6QASWQ43.com.example.shipping",
        "get-task-allow": True,
    },
}

PASSING_OUTPUT = """[==========] Running 5 tests from 1 test cases.
[----------] Global test environment set-up.
[----------] 5 tests from com.rohittp.reng.internal.gl.IosGlConformanceTest
[ RUN      ] com.rohittp.reng.internal.gl.IosGlConformanceTest.itAdopts
GL_RENDERER=Apple A14 GPU
[       OK ] com.rohittp.reng.internal.gl.IosGlConformanceTest.itAdopts (12 ms)
[----------] Global test environment tear-down
[==========] 5 tests from 1 test cases ran. (310 ms total)
[  PASSED  ] 5 tests.
"""

FAILING_OUTPUT = """[==========] Running 5 tests from 1 test cases.
[==========] 5 tests from 1 test cases ran. (410 ms total)
[  PASSED  ] 4 tests.
[  FAILED  ] 1 tests, listed below:
[  FAILED  ] com.rohittp.reng.internal.gl.IosGlConformanceTest.itDraws
 1 FAILED TESTS
"""

WATCHDOG_OUTPUT = """[==========] Running 1146 tests from 214 test cases.
[ RUN      ] com.rohittp.reng.SomethingTest.first
[       OK ] com.rohittp.reng.SomethingTest.first (1 ms)
[ RUN      ] com.rohittp.reng.SomethingTest.second
"""

EMPTY_FILTER_OUTPUT = """[==========] Running 0 tests from 0 test cases.
[==========] 0 tests from 0 test cases ran. (0 ms total)
[  PASSED  ] 0 tests.
"""


def profile_document(profile: dict) -> bytes:
    return plistlib.dumps(profile)


def verb(command):
    """The one word that identifies a command, whatever position it sits in.

    ``xcrun devicectl device install app`` puts its verb at index 3 and
    ``xcrun devicectl device process launch`` at index 4, so a fixed index reads ``app`` for
    half of them and quietly matches nothing.
    """
    if command[0] in {"security", "codesign"}:
        return command[0]
    return command[3] if command[3] in {"install", "uninstall"} else command[4]


def arguments(**overrides):
    parser = tool.build_parser()
    argv = [
        "--device",
        overrides.pop("device", "00008101-0006492C3E51001E"),
        "--profile",
        str(overrides.pop("profile", "profile.mobileprovision")),
        "--identity",
        overrides.pop("identity", "Apple Development: Someone (ABCDE12345)"),
        "--skip-link",
    ]
    for name, value in overrides.items():
        flag = "--" + name.replace("_", "-")
        if value is True:
            argv.append(flag)
        else:
            argv.extend([flag, str(value)])
    return parser.parse_args(argv)


class FakeRunner:
    """Answers the two commands whose output the tool reads, and records every call."""

    def __init__(self, profile=WILDCARD_PROFILE, launch_output=PASSING_OUTPUT, failures=()):
        self.profile = profile
        self.launch_output = launch_output
        self.failures = dict(failures)
        self.commands: list[list[str]] = []
        self.quieted: list[list[str]] = []

    def __call__(self, command, *, stream=False, quiet=False):
        self.commands.append(list(command))
        if quiet:
            self.quieted.append(list(command))
        for fragment, code in self.failures.items():
            if fragment in command:
                return tool.CommandResult(returncode=code, output="")
        if command[:2] == ["security", "cms"]:
            return tool.CommandResult(0, profile_document(self.profile).decode("utf-8"))
        if "launch" in command:
            return tool.CommandResult(0, self.launch_output)
        return tool.CommandResult(0, "")

    def command_with(self, fragment):
        for command in self.commands:
            if fragment in command:
                return command
        raise AssertionError(f"no command containing {fragment!r} in {self.commands}")


class FilterTest(unittest.TestCase):
    def test_an_empty_filter_is_refused_and_says_why(self):
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.check_filter("   ")
        message = str(raised.exception)
        self.assertIn("watchdog", message)
        self.assertIn(tool.DEFAULT_FILTER, message)

    def test_a_whole_module_filter_is_allowed_but_warned_about(self):
        warnings = tool.check_filter("*")
        self.assertEqual(1, len(warnings))
        self.assertIn("SIGKILL", warnings[0])

    def test_an_ordinary_filter_produces_no_warning(self):
        self.assertEqual((), tool.check_filter(tool.DEFAULT_FILTER))

    def test_the_default_filter_is_the_gl_tests_and_nothing_wider(self):
        parsed = tool.build_parser().parse_args(
            ["--device", "d", "--profile", "p", "--identity", "i"]
        )
        self.assertEqual("com.rohittp.reng.internal.gl.IosGlConformanceTest.*", parsed.filter)

    def test_the_help_explains_the_watchdog_where_someone_hitting_it_would_look(self):
        help_text = tool.build_parser().format_help()
        self.assertIn("watchdog", help_text)
        self.assertIn("white screen", help_text)
        self.assertIn("SIGKILL", help_text)


class InformationPropertyListTest(unittest.TestCase):
    def test_names_the_wrapped_executable_and_the_bundle(self):
        plist = tool.information_property_list("com.example.tests", "RenGDeviceTests", "14.0")
        self.assertEqual("RenGDeviceTests", plist["CFBundleExecutable"])
        self.assertEqual("com.example.tests", plist["CFBundleIdentifier"])
        self.assertEqual("14.0", plist["MinimumOSVersion"])

    def test_carries_the_keys_ios_installs_and_launches_on(self):
        plist = tool.information_property_list("com.example.tests", "RenGDeviceTests", "14.0")
        self.assertEqual("APPL", plist["CFBundlePackageType"])
        self.assertEqual(["iPhoneOS"], plist["CFBundleSupportedPlatforms"])
        self.assertEqual([1, 2], plist["UIDeviceFamily"])
        self.assertEqual({}, plist["UILaunchScreen"])

    def test_round_trips_through_plistlib(self):
        plist = tool.information_property_list("com.example.tests", "RenGDeviceTests", "15.1")
        self.assertEqual(plist, plistlib.loads(plistlib.dumps(plist)))


class ProvisioningProfileTest(unittest.TestCase):
    def test_reads_the_fields_the_run_depends_on(self):
        profile = tool.parse_provisioning_profile(profile_document(WILDCARD_PROFILE))
        self.assertEqual("iOS Team Provisioning Profile: *", profile.name)
        self.assertEqual("ZG6QASWQ43.*", profile.application_identifier)
        self.assertTrue(profile.is_wildcard)
        self.assertIn("00008101-0006492C3E51001E", profile.provisioned_devices)
        self.assertEqual(datetime(2099, 1, 1), profile.expiration)

    def test_rejects_a_document_that_is_not_a_property_list(self):
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.parse_provisioning_profile(b"not a plist at all")
        self.assertIn("property list", str(raised.exception))

    def test_rejects_a_profile_with_no_entitlements(self):
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.parse_provisioning_profile(profile_document({"Name": "empty"}))
        self.assertIn("Entitlements", str(raised.exception))

    def test_rejects_a_profile_with_no_application_identifier(self):
        document = profile_document({"Name": "odd", "Entitlements": {"get-task-allow": True}})
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.parse_provisioning_profile(document)
        self.assertIn("application-identifier", str(raised.exception))


class EntitlementsTest(unittest.TestCase):
    def test_narrows_a_wildcard_application_identifier_to_the_bundle_being_signed(self):
        profile = tool.parse_provisioning_profile(profile_document(WILDCARD_PROFILE))
        entitlements = tool.entitlements_for_bundle(profile, "com.rohittp.reng.devicetests")
        self.assertEqual(
            "ZG6QASWQ43.com.rohittp.reng.devicetests",
            entitlements["application-identifier"],
        )

    def test_leaves_a_concrete_application_identifier_alone(self):
        profile = tool.parse_provisioning_profile(profile_document(SHIPPING_PROFILE))
        entitlements = tool.entitlements_for_bundle(profile, "com.example.shipping")
        self.assertEqual(
            "ZG6QASWQ43.com.example.shipping", entitlements["application-identifier"]
        )

    def test_claims_no_entitlement_the_profile_does_not_grant(self):
        profile = tool.parse_provisioning_profile(profile_document(WILDCARD_PROFILE))
        entitlements = tool.entitlements_for_bundle(profile, "com.rohittp.reng.devicetests")
        self.assertEqual(set(WILDCARD_PROFILE["Entitlements"]), set(entitlements))
        for key, value in WILDCARD_PROFILE["Entitlements"].items():
            if key != "application-identifier":
                self.assertEqual(value, entitlements[key])

    def test_does_not_mutate_the_profile_it_was_given(self):
        profile = tool.parse_provisioning_profile(profile_document(WILDCARD_PROFILE))
        tool.entitlements_for_bundle(profile, "com.rohittp.reng.devicetests")
        self.assertEqual("ZG6QASWQ43.*", profile.entitlements["application-identifier"])


class ProfileCheckTest(unittest.TestCase):
    def profile(self, document=WILDCARD_PROFILE):
        return tool.parse_provisioning_profile(profile_document(document))

    def test_accepts_a_wildcard_profile_that_lists_the_device(self):
        notes = tool.check_profile(
            self.profile(), "00008101-0006492C3E51001E", "com.rohittp.reng.devicetests"
        )
        self.assertEqual((), notes)

    def test_matches_the_device_case_insensitively(self):
        notes = tool.check_profile(
            self.profile(), "00008101-0006492c3e51001e", "com.rohittp.reng.devicetests"
        )
        self.assertEqual((), notes)

    def test_refuses_a_profile_that_does_not_list_the_device(self):
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.check_profile(
                self.profile(), "00008110-001122334455667E", "com.rohittp.reng.devicetests"
            )
        message = str(raised.exception)
        self.assertIn("does not cover device", message)
        self.assertIn("allowProvisioningUpdates", message)

    def test_refuses_an_expired_profile(self):
        expired = dict(WILDCARD_PROFILE, ExpirationDate=datetime(2020, 5, 4))
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.check_profile(
                self.profile(expired), "00008101-0006492C3E51001E", "com.rohittp.reng.devicetests"
            )
        self.assertIn("expired on 2020-05-04", str(raised.exception))

    def test_refuses_to_install_over_the_app_a_shipping_profile_names(self):
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.check_profile(
                self.profile(SHIPPING_PROFILE),
                "00008101-0006492C3E51001E",
                "com.rohittp.reng.devicetests",
            )
        message = str(raised.exception)
        self.assertIn("com.example.shipping", message)
        self.assertIn("replace whatever application already owns it", message)

    def test_allows_a_shipping_profile_when_its_identifier_is_asked_for_deliberately(self):
        notes = tool.check_profile(
            self.profile(SHIPPING_PROFILE), "00008101-0006492C3E51001E", "com.example.shipping"
        )
        self.assertEqual((), notes)

    def test_notes_that_a_non_udid_device_could_not_be_checked(self):
        notes = tool.check_profile(
            self.profile(),
            "BCDAA279-451B-5236-8006-C2A12EB9EB70",
            "com.rohittp.reng.devicetests",
        )
        self.assertEqual(1, len(notes))
        self.assertIn("not a hardware UDID", notes[0])

    def test_notes_a_profile_that_lists_no_devices(self):
        distribution = dict(WILDCARD_PROFILE)
        distribution.pop("ProvisionedDevices")
        notes = tool.check_profile(
            self.profile(distribution),
            "00008101-0006492C3E51001E",
            "com.rohittp.reng.devicetests",
        )
        self.assertEqual(1, len(notes))
        self.assertIn("no provisioned devices", notes[0])


class CommandLineTest(unittest.TestCase):
    def test_the_link_command_disables_the_configuration_cache(self):
        self.assertEqual(
            ["./gradlew", "--no-configuration-cache", ":kmp:linkDebugTestIosArm64"],
            tool.link_command("./gradlew"),
        )

    def test_the_signature_is_bound_to_the_extracted_entitlements(self):
        command = tool.codesign_command(
            "Apple Development: Someone (ABCDE12345)",
            Path("build/ios-device-test/entitlements.plist"),
            Path("build/ios-device-test/RenGDeviceTests.app"),
        )
        self.assertEqual("codesign", command[0])
        self.assertIn("--force", command)
        self.assertIn("--timestamp=none", command)
        self.assertIn("--generate-entitlement-der", command)
        self.assertEqual(
            "build/ios-device-test/entitlements.plist",
            command[command.index("--entitlements") + 1],
        )
        self.assertEqual("Apple Development: Someone (ABCDE12345)", command[command.index("--sign") + 1])
        self.assertEqual("build/ios-device-test/RenGDeviceTests.app", command[-1])

    def test_the_install_command_names_the_device_and_the_bundle_directory(self):
        command = tool.install_command("UDID", Path("build/x/RenGDeviceTests.app"))
        self.assertEqual(["xcrun", "devicectl", "device", "install", "app"], command[:5])
        self.assertEqual("UDID", command[command.index("--device") + 1])
        self.assertEqual("build/x/RenGDeviceTests.app", command[-1])

    def test_the_launch_command_appends_the_filter_after_the_bundle_identifier(self):
        command = tool.launch_command("UDID", "com.example.tests", "a.b.C.*")
        self.assertEqual(["xcrun", "devicectl", "device", "process", "launch"], command[:5])
        self.assertIn("--console", command)
        self.assertIn("--terminate-existing", command)
        self.assertEqual(["com.example.tests", "--ktest_filter=a.b.C.*"], command[-2:])

    def test_the_uninstall_command_names_the_bundle_identifier(self):
        command = tool.uninstall_command("UDID", "com.example.tests")
        self.assertEqual(["xcrun", "devicectl", "device", "uninstall", "app"], command[:5])
        self.assertEqual("com.example.tests", command[-1])


class OutputSummaryTest(unittest.TestCase):
    def test_reads_a_passing_run(self):
        outcome = tool.summarise_test_output(PASSING_OUTPUT)
        self.assertEqual(5, outcome.ran)
        self.assertEqual(5, outcome.passed)
        self.assertEqual((), outcome.failures)
        tool.verdict(outcome, tool.DEFAULT_FILTER)

    def test_names_the_failing_test_and_not_the_header_line(self):
        outcome = tool.summarise_test_output(FAILING_OUTPUT)
        self.assertEqual(
            ("com.rohittp.reng.internal.gl.IosGlConformanceTest.itDraws",), outcome.failures
        )
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.verdict(outcome, tool.DEFAULT_FILTER)
        self.assertIn("IosGlConformanceTest.itDraws", str(raised.exception))

    def test_a_truncated_run_is_diagnosed_as_the_watchdog_rather_than_passing(self):
        outcome = tool.summarise_test_output(WATCHDOG_OUTPUT)
        self.assertFalse(outcome.saw_summary)
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.verdict(outcome, "*")
        message = str(raised.exception)
        self.assertIn("watchdog", message)
        self.assertIn("white screen", message)

    def test_a_filter_that_matched_nothing_is_a_failure_not_a_green_run(self):
        outcome = tool.summarise_test_output(EMPTY_FILTER_OUTPUT)
        self.assertEqual(0, outcome.ran)
        with self.assertRaises(tool.DeviceRunError) as raised:
            tool.verdict(outcome, "com.example.Typo.*")
        self.assertIn("matched no tests", str(raised.exception))


class RunTest(unittest.TestCase):
    @contextlib.contextmanager
    def workspace(self, **overrides):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "test.kexe"
            binary.write_bytes(b"\xcf\xfa\xed\xfe mach-o")
            profile = root / "wildcard.mobileprovision"
            profile.write_bytes(b"CMS blob")
            yield arguments(
                binary=binary,
                profile=profile,
                staging_directory=root / "staging",
                **overrides,
            ), root

    def test_runs_the_whole_recipe_in_order_and_uninstalls_afterwards(self):
        runner = FakeRunner()
        with self.workspace() as (options, _root):
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                code = tool.run_device_tests(options, runner)
        self.assertEqual(0, code)
        self.assertEqual(
            ["security", "codesign", "install", "launch", "uninstall"],
            [verb(command) for command in runner.commands],
        )
        self.assertIn("5 test(s) passed", buffer.getvalue())

    def test_stages_a_bundle_the_phone_can_install(self):
        runner = FakeRunner()
        with self.workspace() as (options, root):
            with contextlib.redirect_stdout(io.StringIO()):
                tool.run_device_tests(options, runner)
            application = root / "staging" / "RenGDeviceTests.app"
            self.assertTrue((application / "RenGDeviceTests").is_file())
            self.assertTrue((application / "embedded.mobileprovision").is_file())
            plist = plistlib.loads((application / "Info.plist").read_bytes())
            self.assertEqual("RenGDeviceTests", plist["CFBundleExecutable"])
            self.assertEqual("com.rohittp.reng.devicetests", plist["CFBundleIdentifier"])
            entitlements = plistlib.loads((root / "staging" / "entitlements.plist").read_bytes())
            self.assertEqual(
                "ZG6QASWQ43.com.rohittp.reng.devicetests",
                entitlements["application-identifier"],
            )
            self.assertTrue(entitlements["get-task-allow"])

    def test_does_not_print_the_profile_and_the_team_udids_it_lists(self):
        runner = FakeRunner()
        with self.workspace() as (options, _root):
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                tool.run_device_tests(options, runner)
        self.assertEqual([tool.profile_dump_command(options.profile)], runner.quieted)
        self.assertNotIn("00008030-000000000000001E", buffer.getvalue())

    def test_signs_the_bundle_against_the_entitlements_it_wrote(self):
        runner = FakeRunner()
        with self.workspace() as (options, root):
            with contextlib.redirect_stdout(io.StringIO()):
                tool.run_device_tests(options, runner)
        command = runner.command_with("--entitlements")
        self.assertEqual(
            str(root / "staging" / "entitlements.plist"),
            command[command.index("--entitlements") + 1],
        )

    def test_links_first_unless_told_not_to(self):
        runner = FakeRunner()
        with self.workspace() as (options, _root):
            options.skip_link = False
            options.gradle = "gradle"
            with contextlib.redirect_stdout(io.StringIO()):
                tool.run_device_tests(options, runner)
        self.assertEqual(tool.link_command("gradle"), runner.commands[0])

    def test_uninstalls_even_when_the_device_run_failed(self):
        runner = FakeRunner(launch_output=FAILING_OUTPUT)
        with self.workspace() as (options, _root):
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(tool.DeviceRunError):
                    tool.run_device_tests(options, runner)
        self.assertEqual("uninstall", verb(runner.commands[-1]))

    def test_keeps_the_application_installed_only_when_asked(self):
        runner = FakeRunner()
        with self.workspace(keep_installed=True) as (options, _root):
            with contextlib.redirect_stdout(io.StringIO()):
                with contextlib.redirect_stderr(io.StringIO()):
                    tool.run_device_tests(options, runner)
        self.assertNotIn("uninstall", [verb(command) for command in runner.commands])
        self.assertEqual("launch", verb(runner.commands[-1]))

    def test_reports_a_failing_install_without_launching(self):
        runner = FakeRunner(failures={"install": 3})
        with self.workspace() as (options, _root):
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(tool.DeviceRunError) as raised:
                    tool.run_device_tests(options, runner)
        self.assertIn("devicectl install failed with exit code 3", str(raised.exception))
        self.assertNotIn("launch", [verb(command) for command in runner.commands])


class MainTest(unittest.TestCase):
    def run_main(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = tool.main(argv)
        return code, out.getvalue() + err.getvalue()

    def test_a_missing_test_binary_names_the_gradle_task_rather_than_raising(self):
        with tempfile.TemporaryDirectory() as directory:
            code, output = self.run_main([
                "--device", "00008101-0006492C3E51001E",
                "--profile", str(Path(directory) / "absent.mobileprovision"),
                "--identity", "Apple Development: Someone (ABCDE12345)",
                "--binary", str(Path(directory) / "absent.kexe"),
                "--skip-link",
            ])
        self.assertEqual(1, code)
        self.assertIn("No linked iOS device test binary", output)
        self.assertIn(":kmp:linkDebugTestIosArm64", output)

    def test_a_missing_profile_says_where_profiles_live(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "test.kexe"
            binary.write_bytes(b"mach-o")
            code, output = self.run_main([
                "--device", "00008101-0006492C3E51001E",
                "--profile", str(Path(directory) / "absent.mobileprovision"),
                "--identity", "Apple Development: Someone (ABCDE12345)",
                "--binary", str(binary),
                "--skip-link",
            ])
        self.assertEqual(1, code)
        self.assertIn("No provisioning profile at", output)
        self.assertIn("Provisioning Profiles", output)

    def test_missing_required_arguments_report_rather_than_traceback(self):
        code, output = self.run_main(["--device", "00008101-0006492C3E51001E"])
        self.assertEqual(1, code)
        self.assertIn("Invalid command-line arguments", output)

    def test_an_empty_filter_is_refused_before_anything_is_installed(self):
        code, output = self.run_main([
            "--device", "00008101-0006492C3E51001E",
            "--profile", "unread.mobileprovision",
            "--identity", "Apple Development: Someone (ABCDE12345)",
            "--filter", "",
            "--skip-link",
        ])
        self.assertEqual(1, code)
        self.assertIn("watchdog", output)


if __name__ == "__main__":
    unittest.main()
