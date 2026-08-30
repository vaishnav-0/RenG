"""Tests for the frame comparison script.

**The one that matters is `test_a_single_differing_pixel_fails`.** A comparison tested only against
identical inputs has never been shown to detect anything, which is the exact shape of the vacuous
check this project keeps finding in its own suites; every other test here is scaffolding for that one.
"""

from __future__ import annotations

import gzip
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from compare_rendered_frames import (  # noqa: E402
    Pair,
    compare_all,
    compare_frames,
    enumerate_pairs,
    frames_in,
    main,
    read_ppm,
    render_missing_baselines,
)

WIDTH = 8
HEIGHT = 4


def ppm_bytes(fill: tuple[int, int, int] = (10, 20, 30)) -> bytes:
    header = f"P6\n{WIDTH} {HEIGHT}\n255\n".encode("ascii")
    return header + bytes(fill) * (WIDTH * HEIGHT)


def write_frame(directory: Path, name: str, payload: bytes) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / name
    path.write_bytes(gzip.compress(payload))
    return path


def corpus(root: Path, configs: list[str], plans: list[str]) -> tuple[Path, Path]:
    configs_dir, plans_dir = root / "configs", root / "plans"
    configs_dir.mkdir(parents=True, exist_ok=True)
    plans_dir.mkdir(parents=True, exist_ok=True)
    for name in configs:
        (configs_dir / f"{name}.json").write_text("{}")
    for name in plans:
        (plans_dir / f"{name}.json").write_text("[]")
    return configs_dir, plans_dir


class ReadPpmTests(unittest.TestCase):
    def test_reads_dimensions_and_body(self) -> None:
        with TemporaryDirectory() as directory:
            path = write_frame(Path(directory), "frame-0000.ppm.gz", ppm_bytes())
            width, height, body = read_ppm(path)
            self.assertEqual((WIDTH, HEIGHT), (width, height))
            self.assertEqual(WIDTH * HEIGHT * 3, len(body))

    def test_a_truncated_body_is_an_error_rather_than_a_short_read(self) -> None:
        with TemporaryDirectory() as directory:
            path = write_frame(Path(directory), "frame-0000.ppm.gz", ppm_bytes()[:-3])
            with self.assertRaises(ValueError):
                read_ppm(path)


class CompareFramesTests(unittest.TestCase):
    def test_identical_frames_report_nothing(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            a = write_frame(root / "a", "frame-0000.ppm.gz", ppm_bytes())
            b = write_frame(root / "b", "frame-0000.ppm.gz", ppm_bytes())
            self.assertIsNone(
                compare_frames(Pair("c", "p"), "frame-0000.ppm.gz", a, b, None),
            )

    def test_a_single_differing_pixel_is_found_and_located(self) -> None:
        # One pixel of 32, one channel, one unit. If a comparison cannot see this it cannot see
        # anything this project has ever shipped as a bug: the smallest real defect here moved
        # far more than one pixel, and a check that only passes on identical inputs proves nothing.
        with TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = ppm_bytes()
            mutated = bytearray(baseline)
            header = len(f"P6\n{WIDTH} {HEIGHT}\n255\n")
            pixel_index = 2 * WIDTH + 5          # x = 5, y = 2
            mutated[header + pixel_index * 3 + 1] += 1
            a = write_frame(root / "a", "frame-0000.ppm.gz", baseline)
            b = write_frame(root / "b", "frame-0000.ppm.gz", bytes(mutated))

            difference = compare_frames(
                Pair("c", "p"), "frame-0000.ppm.gz", a, b, root / "d" / "frame-0000.ppm.gz",
            )
            self.assertIsNotNone(difference)
            assert difference is not None
            self.assertEqual(1, difference.changed_pixels)
            self.assertEqual(WIDTH * HEIGHT, difference.total_pixels)
            self.assertEqual(1, difference.max_channel_delta)
            self.assertEqual((5, 2, 5, 2), difference.bounding_box)
            self.assertTrue(difference.difference_path.exists())

    def test_a_size_change_is_an_error_rather_than_a_difference(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            a = write_frame(root / "a", "frame-0000.ppm.gz", ppm_bytes())
            wide = f"P6\n{WIDTH * 2} {HEIGHT}\n255\n".encode("ascii") + bytes(3) * (WIDTH * 2 * HEIGHT)
            b = write_frame(root / "b", "frame-0000.ppm.gz", wide)
            with self.assertRaises(ValueError):
                compare_frames(Pair("c", "p"), "frame-0000.ppm.gz", a, b, None)


class EnumerateTests(unittest.TestCase):
    def test_every_config_is_crossed_with_every_plan(self) -> None:
        with TemporaryDirectory() as directory:
            configs, plans = corpus(Path(directory), ["x", "y"], ["a", "b", "c"])
            pairs = enumerate_pairs(configs, plans)
            self.assertEqual(6, len(pairs))
            self.assertEqual(Pair("x", "a"), pairs[0])
            self.assertEqual(len(pairs), len(set(pair.slug for pair in pairs)))


class BaselineRenderTests(unittest.TestCase):
    def test_missing_baselines_share_exactly_one_worktree(self) -> None:
        # A worktree is a full checkout. Rendering five missing baselines must create one, not five.
        created: list[str] = []
        rendered: list[str] = []

        def factory(path: Path, ref: str) -> Path:
            created.append(ref)
            path.mkdir(parents=True, exist_ok=True)
            return path

        def render(root: Path, pair: Pair, output: Path) -> None:
            rendered.append(pair.slug)
            write_frame(output, "frame-0000.ppm.gz", ppm_bytes())

        with TemporaryDirectory() as directory:
            root = Path(directory)
            pairs = [Pair("c", f"p{index}") for index in range(5)]
            worktrees = render_missing_baselines(
                root, pairs, root / "baseline", render, worktree_factory=factory,
            )
            self.assertEqual(1, worktrees)
            self.assertEqual(1, len(created))
            self.assertEqual(5, len(rendered))

    def test_nothing_missing_creates_no_worktree_at_all(self) -> None:
        def factory(path: Path, ref: str) -> Path:  # pragma: no cover - must never run
            raise AssertionError("a worktree was created with nothing to render")

        with TemporaryDirectory() as directory:
            root = Path(directory)
            pair = Pair("c", "p")
            write_frame(root / "baseline" / pair.slug, "frame-0000.ppm.gz", ppm_bytes())
            self.assertEqual(
                0,
                render_missing_baselines(
                    root, [pair], root / "baseline", lambda *_: None, worktree_factory=factory,
                ),
            )

    def test_the_worktree_is_cleaned_up_when_a_render_fails(self) -> None:
        # The failure path is the one that matters: a worktree left behind holds a lock the next run
        # trips over, and this session left two behind before the cleanup was written down.
        holders: list[Path] = []

        def factory(path: Path, ref: str) -> Path:
            path.mkdir(parents=True, exist_ok=True)
            holders.append(path)
            return path

        def render(root: Path, pair: Pair, output: Path) -> None:
            raise RuntimeError("the harness failed")

        with TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(RuntimeError):
                render_missing_baselines(
                    root, [Pair("c", "p")], root / "baseline", render, worktree_factory=factory,
                )
            self.assertEqual(1, len(holders))
            self.assertFalse(holders[0].exists(), "the temporary holder outlived the failure")


class CompareAllTests(unittest.TestCase):
    def _tree(self, root: Path, pair: Pair, baseline: bytes | None, current: bytes | None) -> None:
        if baseline is not None:
            write_frame(root / "baseline" / pair.slug, "frame-0000.ppm.gz", baseline)
        if current is not None:
            write_frame(root / "current" / pair.slug, "frame-0000.ppm.gz", current)

    def test_identical_trees_report_nothing_at_all(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            pair = Pair("c", "p")
            self._tree(root, pair, ppm_bytes(), ppm_bytes())
            differences, warnings, failures = compare_all(
                [pair], root / "baseline", root / "current", None,
            )
            self.assertEqual(([], [], []), (differences, warnings, failures))

    def test_a_pair_new_here_is_a_warning_not_a_failure(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            pair = Pair("c", "p")
            self._tree(root, pair, None, ppm_bytes())
            differences, warnings, failures = compare_all(
                [pair], root / "baseline", root / "current", None,
            )
            self.assertEqual([], differences)
            self.assertEqual([], failures)
            self.assertEqual(1, len(warnings))

    def test_a_pair_that_stopped_rendering_is_a_failure(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            pair = Pair("c", "p")
            self._tree(root, pair, ppm_bytes(), None)
            differences, warnings, failures = compare_all(
                [pair], root / "baseline", root / "current", None,
            )
            self.assertEqual([], differences)
            self.assertEqual(1, len(failures))
            self.assertIn("--accept-removals", failures[0])


class EndToEndTests(unittest.TestCase):
    """`main` over a fixture corpus, with both sides already rendered so no harness is needed."""

    def _fixture(self, root: Path, current_fill: tuple[int, int, int]) -> tuple[Path, Path, Path]:
        configs, plans = corpus(root, ["cfg"], ["plan"])
        renders = root / "renders"
        pair = Pair("cfg", "plan")
        write_frame(renders / "baseline" / pair.slug, "frame-0000.ppm.gz", ppm_bytes())
        write_frame(renders / "current" / pair.slug, "frame-0000.ppm.gz", ppm_bytes(current_fill))
        return configs, plans, renders

    def test_an_unchanged_corpus_exits_zero(self) -> None:
        with TemporaryDirectory() as directory:
            configs, plans, renders = self._fixture(Path(directory), (10, 20, 30))
            self.assertEqual(0, main([
                "--root", directory, "--configs", str(configs),
                "--plans", str(plans), "--renders", str(renders),
            ]))

    def test_a_changed_corpus_exits_one_and_writes_a_difference_image(self) -> None:
        with TemporaryDirectory() as directory:
            configs, plans, renders = self._fixture(Path(directory), (10, 21, 30))
            self.assertEqual(1, main([
                "--root", directory, "--configs", str(configs),
                "--plans", str(plans), "--renders", str(renders),
            ]))
            self.assertEqual(
                ["frame-0000.ppm.gz"],
                frames_in(renders / "difference" / "cfg__plan"),
            )

    def test_an_empty_corpus_is_an_error_rather_than_a_pass(self) -> None:
        # Zero pairs compared successfully is indistinguishable from a green run unless it is
        # refused, which is the "absent measurement is evidence" rule applied to this script.
        with TemporaryDirectory() as directory:
            root = Path(directory)
            configs, plans = corpus(root, [], [])
            self.assertEqual(2, main([
                "--root", directory, "--configs", str(configs),
                "--plans", str(plans), "--renders", str(root / "renders"),
            ]))


if __name__ == "__main__":
    unittest.main()
