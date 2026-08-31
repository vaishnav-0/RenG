"""Tests for the frame-comparison gate's version classifier."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from version_bump_kind import classify, read_version  # noqa: E402


def properties(version: str) -> str:
    return f"# a comment\nGROUP=com.rohittp.reng\nVERSION_NAME={version}\nfoo=bar\n"


class ReadVersionTests(unittest.TestCase):
    def test_reads_the_single_declared_version(self) -> None:
        self.assertEqual((0, 4, 0), read_version(properties("0.4.0")))

    def test_two_declarations_are_unreadable_rather_than_first_wins(self) -> None:
        text = properties("0.4.0") + "VERSION_NAME=0.5.0\n"
        self.assertIsNone(read_version(text))

    def test_a_prerelease_is_unreadable_rather_than_truncated(self) -> None:
        # ADR 0013 publishes only canonical stable versions. Reading `0.5.0-beta.1` as `0.5.0` would
        # classify a bump the release resolver would refuse to act on.
        for version in ("0.5.0-beta.1", "0.5.0+build.7", "0.5", "v0.5.0", ""):
            with self.subTest(version=version):
                self.assertIsNone(read_version(properties(version)))


class ClassifyTests(unittest.TestCase):
    def test_each_component_is_named_by_the_position_that_moved(self) -> None:
        self.assertEqual("major", classify(properties("0.4.0"), properties("1.0.0")))
        self.assertEqual("minor", classify(properties("0.4.0"), properties("0.5.0")))
        self.assertEqual("patch", classify(properties("0.4.0"), properties("0.4.1")))

    def test_an_unchanged_version_is_none(self) -> None:
        self.assertEqual("none", classify(properties("0.4.0"), properties("0.4.0")))

    def test_a_downward_move_is_none_rather_than_a_bump(self) -> None:
        # A branch cut before a release can legitimately carry an older version than main. That is
        # not a bump, and running the corpus for it would be pure cost.
        self.assertEqual("none", classify(properties("0.5.0"), properties("0.4.0")))

    def test_a_higher_minor_with_a_lower_patch_is_still_a_minor_bump(self) -> None:
        # 0.4.7 -> 0.5.0 moves the minor up and the patch down. Comparing tuples handles this and
        # comparing components one at a time does not, which is why the ordering check is on tuples.
        self.assertEqual("minor", classify(properties("0.4.7"), properties("0.5.0")))

    def test_an_unreadable_version_declines_rather_than_guesses(self) -> None:
        for base, head in (
            (properties("0.4.0"), "nothing here"),
            ("nothing here", properties("0.5.0")),
            ("", ""),
        ):
            with self.subTest(head=head[:20]):
                self.assertEqual("none", classify(base, head))


if __name__ == "__main__":
    unittest.main()
