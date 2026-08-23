import io
import contextlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import print_test_failures


FAILING_SUITE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="macosArm64Test" tests="1" failures="1">
  <testcase classname="com.rohittp.reng.SomeTest" name="itDraws">
    <failure message="the ground must cover the whole frame">stack line one</failure>
  </testcase>
  <system-out><![CDATA[RenG basemap readback: dialect=DESKTOP]]></system-out>
</testsuite>
"""

PASSING_SUITE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="macosArm64Test" tests="1" failures="0">
  <testcase classname="com.rohittp.reng.SomeTest" name="itDraws"/>
  <system-out><![CDATA[quiet]]></system-out>
</testsuite>
"""


def run(*arguments: str) -> tuple[int, str]:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = print_test_failures.main(["print_test_failures.py", *arguments])
    return code, buffer.getvalue()


class PrintTestFailuresTest(unittest.TestCase):
    def test_prints_the_message_and_the_standard_output_of_a_failing_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory) / "test-results"
            results.mkdir()
            (results / "TEST-suite.xml").write_text(FAILING_SUITE, encoding="utf-8")
            code, output = run(str(results))
        self.assertEqual(0, code)
        self.assertIn("FAILED com.rohittp.reng.SomeTest.itDraws", output)
        self.assertIn("the ground must cover the whole frame", output)
        self.assertIn("stack line one", output)
        self.assertIn("RenG basemap readback: dialect=DESKTOP", output)

    def test_says_so_when_nothing_failed(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory) / "test-results"
            results.mkdir()
            (results / "TEST-suite.xml").write_text(PASSING_SUITE, encoding="utf-8")
            code, output = run(str(results))
        self.assertEqual(0, code)
        self.assertIn("No failing test cases found", output)
        self.assertNotIn("quiet", output)

    def test_survives_a_malformed_report_and_a_missing_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory) / "test-results"
            results.mkdir()
            (results / "TEST-broken.xml").write_text("<testsuite", encoding="utf-8")
            code, output = run(str(results), str(Path(directory) / "absent"))
        self.assertEqual(0, code)
        self.assertIn("could not read", output)


if __name__ == "__main__":
    unittest.main()
