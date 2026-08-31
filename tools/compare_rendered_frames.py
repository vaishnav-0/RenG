#!/usr/bin/env python3
"""Compare frames rendered by this working tree against frames rendered by `main`.

Cycle K, and ADR 0043: the comparison is **exact**. Not because exactness is easy, but because a
tolerance wide enough to survive a *different machine* is far wider than any regression this project
has ever produced -- the software rasteriser's own defect is 3,005 wrong pixels of 15,876, roughly
three thousand times the signal a real change emits in the same frame. Comparing two commits on one
machine removes the driver, the libm and the rasteriser from the equation, and leaves RenG's own
source as the only variable. So one differing pixel is a difference, and the operator decides.

The flow:

  1. enumerate every (config, plan) pair in the corpus
  2. any pair with no baseline is rendered by checking `main` out into a **temporary worktree** and
     running the harness there -- once, for all missing pairs together
  3. render the working tree
  4. compare, byte for byte, decompressed

What each outcome means is a deliberate asymmetry:

  * **present here, missing in `main`** -- a warning. A new plan or config has nothing to compare to
    yet, which is the normal state of adding one.
  * **missing here, present in `main`** -- a failure. Something stopped rendering, and the one way
    that is legitimate is a deliberate removal, which `--accept-removals` states out loud.

**Stale baselines are never cleared automatically.** A baseline is expensive and deleting the wrong
one silently costs a re-render of the whole corpus; the operator deletes what they mean to delete,
and the report names the directory so they can. Re-running after a delete is always safe.
"""

from __future__ import annotations

import argparse
import gzip
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence

BASELINE_REF = "main"
FRAME_SUFFIX = ".ppm.gz"


@dataclass(frozen=True)
class Pair:
    """One corpus entry: a config crossed with a plan file."""

    config: str
    plan: str

    @property
    def slug(self) -> str:
        return f"{self.config}__{self.plan}"


@dataclass
class Difference:
    """What changed in one frame, in the terms an operator needs to judge it."""

    pair: Pair
    frame: str
    changed_pixels: int
    total_pixels: int
    max_channel_delta: int
    bounding_box: tuple[int, int, int, int]
    baseline_path: Path
    current_path: Path
    difference_path: Path | None = None

    def describe(self) -> str:
        left, top, right, bottom = self.bounding_box
        percent = self.changed_pixels * 100.0 / self.total_pixels if self.total_pixels else 0.0
        lines = [
            f"  {self.pair.config} / {self.pair.plan} / {self.frame}",
            f"    {self.changed_pixels} of {self.total_pixels} pixels differ ({percent:.4f}%), "
            f"max channel delta {self.max_channel_delta}",
            f"    bounding box x {left}..{right}, y {top}..{bottom}",
            f"    baseline {self.baseline_path}",
            f"    current  {self.current_path}",
        ]
        if self.difference_path is not None:
            lines.append(f"    difference {self.difference_path}")
        return "\n".join(lines)


def read_ppm(path: Path) -> tuple[int, int, bytes]:
    """Reads a binary P6 PPM, gzipped or not, and returns width, height and the RGB body.

    Hand-parsed because the header is three ASCII fields and a magic number, and because every tool
    in `tools/` is standard library only.
    """
    raw = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
    if not raw.startswith(b"P6"):
        raise ValueError(f"{path} is not a binary PPM")
    fields: list[bytes] = []
    offset = 2
    while len(fields) < 3:
        while offset < len(raw) and raw[offset : offset + 1].isspace():
            offset += 1
        if raw[offset : offset + 1] == b"#":
            while offset < len(raw) and raw[offset : offset + 1] not in (b"\n", b"\r"):
                offset += 1
            continue
        start = offset
        while offset < len(raw) and not raw[offset : offset + 1].isspace():
            offset += 1
        fields.append(raw[start:offset])
    offset += 1
    width, height, maximum = (int(field) for field in fields)
    if maximum != 255:
        raise ValueError(f"{path} is not 8-bit ({maximum})")
    body = raw[offset:]
    expected = width * height * 3
    if len(body) != expected:
        raise ValueError(f"{path} holds {len(body)} bytes of pixels, expected {expected}")
    return width, height, body


def compare_frames(
    pair: Pair,
    frame: str,
    baseline_path: Path,
    current_path: Path,
    difference_path: Path | None,
) -> Difference | None:
    """Byte-exact comparison, with a difference image written only when something differs."""
    baseline_width, baseline_height, baseline = read_ppm(baseline_path)
    current_width, current_height, current = read_ppm(current_path)
    if (baseline_width, baseline_height) != (current_width, current_height):
        raise ValueError(
            f"{pair.slug}/{frame}: {baseline_width}x{baseline_height} against "
            f"{current_width}x{current_height} -- a config changed size, so nothing can be compared",
        )
    if baseline == current:
        return None

    changed = 0
    max_delta = 0
    left, top, right, bottom = current_width, current_height, -1, -1
    highlight = bytearray(baseline)
    for index in range(baseline_width * baseline_height):
        offset = index * 3
        a = baseline[offset : offset + 3]
        b = current[offset : offset + 3]
        if a == b:
            continue
        changed += 1
        max_delta = max(max_delta, max(abs(x - y) for x, y in zip(a, b)))
        x, y = index % baseline_width, index // baseline_width
        left, right = min(left, x), max(right, x)
        top, bottom = min(top, y), max(bottom, y)
        # Magenta: a colour no map style produces, so a changed pixel reads as itself.
        highlight[offset : offset + 3] = b"\xff\x00\xff"

    if difference_path is not None:
        difference_path.parent.mkdir(parents=True, exist_ok=True)
        header = f"P6\n{baseline_width} {baseline_height}\n255\n".encode("ascii")
        difference_path.write_bytes(gzip.compress(header + bytes(highlight)))

    return Difference(
        pair=pair,
        frame=frame,
        changed_pixels=changed,
        total_pixels=baseline_width * baseline_height,
        max_channel_delta=max_delta,
        bounding_box=(left, top, right, bottom),
        baseline_path=baseline_path,
        current_path=current_path,
        difference_path=difference_path,
    )


def enumerate_pairs(configs: Path, plans: Path) -> list[Pair]:
    """Every config crossed with every plan file, in a stable order."""
    config_names = sorted(path.stem for path in configs.glob("*.json"))
    plan_names = sorted(path.stem for path in plans.glob("*.json"))
    return [Pair(config, plan) for config in config_names for plan in plan_names]


def frames_in(directory: Path) -> list[str]:
    if not directory.is_dir():
        return []
    return sorted(path.name for path in directory.glob(f"*{FRAME_SUFFIX}"))


def render_with_harness(root: Path, pair: Pair, output: Path) -> None:
    """Runs the harness for one pair, then gzips the PPMs it wrote.

    Frames are stored compressed because a corpus is mostly flat colour: measured at 5.0x, which
    turns roughly 90 1080p frames from 0.55 GiB into 0.11 GiB. They are compared decompressed, so
    compression is storage only and never part of the comparison.
    """
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        (
            "./gradlew", "--no-configuration-cache", "--quiet", "-p", "consumer-smoke", "runHarness",
            f"-Pconfig=corpus/configs/{pair.config}.json",
            f"-Pplans=corpus/plans/{pair.plan}.json",
            f"-Pout={output}",
        ),
        cwd=root,
        check=True,
    )
    for frame in sorted(output.glob("*.ppm")):
        (output / (frame.name + ".gz")).write_bytes(gzip.compress(frame.read_bytes()))
        frame.unlink()


def render_missing_baselines(
    root: Path,
    pairs: Sequence[Pair],
    baselines: Path,
    render: Callable[[Path, Pair, Path], None],
    worktree_factory: Callable[[Path, str], Path] | None = None,
) -> int:
    """Renders every missing baseline in **one** temporary worktree at `main`.

    One worktree for all of them, not one each: creating a worktree costs a full checkout, and the
    whole point of a baseline is that it is expensive. Returns how many worktrees were created, which
    is 0 when nothing is missing and 1 otherwise -- the number a test can actually assert on.
    """
    missing = [pair for pair in pairs if not frames_in(baselines / pair.slug)]
    if not missing:
        return 0

    factory = worktree_factory or _create_worktree
    worktree: Path | None = None
    holder = tempfile.mkdtemp(prefix="reng-baseline-")
    try:
        worktree = factory(Path(holder) / "main", BASELINE_REF)
        for pair in missing:
            print(f"  baseline: rendering {pair.slug} at {BASELINE_REF}")
            render(worktree, pair, baselines / pair.slug)
    finally:
        # Cleanup runs whether the render succeeded or not. A worktree left behind holds a lock in
        # `git worktree list` that the next run trips over, and this session left two behind before
        # this was written down.
        if worktree is not None:
            subprocess.run(
                ("git", "worktree", "remove", "--force", str(worktree)),
                cwd=root, check=False, capture_output=True,
            )
        shutil.rmtree(holder, ignore_errors=True)
    return 1


def _create_worktree(path: Path, ref: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ("git", "worktree", "add", "--detach", str(path), ref),
        check=True, capture_output=True,
    )
    return path


def compare_all(
    pairs: Sequence[Pair],
    baselines: Path,
    current: Path,
    differences_root: Path | None,
) -> tuple[list[Difference], list[str], list[str]]:
    """Compares every pair, returning differences, warnings and failures."""
    differences: list[Difference] = []
    warnings: list[str] = []
    failures: list[str] = []

    for pair in pairs:
        baseline_frames = frames_in(baselines / pair.slug)
        current_frames = frames_in(current / pair.slug)

        if not baseline_frames and current_frames:
            warnings.append(
                f"{pair.slug}: no baseline at {BASELINE_REF} -- a new plan or config has nothing "
                f"to compare to yet",
            )
            continue
        if baseline_frames and not current_frames:
            failures.append(
                f"{pair.slug}: rendered at {BASELINE_REF} and not here. If the removal is "
                f"intended, re-run with --accept-removals; otherwise this is a regression.",
            )
            continue
        if not baseline_frames and not current_frames:
            failures.append(f"{pair.slug}: rendered on neither side")
            continue

        for frame in sorted(set(baseline_frames) | set(current_frames)):
            if frame not in baseline_frames:
                warnings.append(f"{pair.slug}/{frame}: new frame, no baseline")
                continue
            if frame not in current_frames:
                failures.append(
                    f"{pair.slug}/{frame}: present at {BASELINE_REF} and missing here",
                )
                continue
            difference = compare_frames(
                pair=pair,
                frame=frame,
                baseline_path=baselines / pair.slug / frame,
                current_path=current / pair.slug / frame,
                difference_path=(
                    None if differences_root is None
                    else differences_root / pair.slug / frame
                ),
            )
            if difference is not None:
                differences.append(difference)

    return differences, warnings, failures


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--configs", type=Path, default=None)
    parser.add_argument("--plans", type=Path, default=None)
    parser.add_argument("--renders", type=Path, default=None)
    parser.add_argument(
        "--accept-removals", action="store_true",
        help="treat a pair that rendered at main and not here as intended, not as a regression",
    )
    parser.add_argument(
        "--no-difference-images", action="store_true",
        help="report differences without writing the magenta highlight images",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    root = arguments.root.resolve()
    configs = arguments.configs or root / "consumer-smoke/corpus/configs"
    plans = arguments.plans or root / "consumer-smoke/corpus/plans"
    renders = arguments.renders or root / "build/harness-renders"
    baselines = renders / "baseline"
    current = renders / "current"
    differences_root = None if arguments.no_difference_images else renders / "difference"

    pairs = enumerate_pairs(configs, plans)
    if not pairs:
        print(f"No corpus: {configs} x {plans} enumerates nothing.")
        return 2
    print(f"{len(pairs)} pairs: {len(set(p.config for p in pairs))} configs x "
          f"{len(set(p.plan for p in pairs))} plans")

    render_missing_baselines(root, pairs, baselines, render_with_harness)

    for pair in pairs:
        if not frames_in(current / pair.slug):
            print(f"  current: rendering {pair.slug}")
            render_with_harness(root, pair, current / pair.slug)

    differences, warnings, failures = compare_all(pairs, baselines, current, differences_root)

    if arguments.accept_removals:
        kept = [f for f in failures if "--accept-removals" not in f]
        accepted = len(failures) - len(kept)
        if accepted:
            print(f"\naccepted {accepted} removal(s) by request")
        failures = kept

    for warning in warnings:
        print(f"warning: {warning}")
    for failure in failures:
        print(f"FAILURE: {failure}")
    if differences:
        print(f"\n{len(differences)} frame(s) differ:")
        for difference in differences:
            print(difference.describe())

    print(
        f"\nA stale baseline is never cleared automatically. Delete "
        f"{baselines}/<pair> and re-run; re-rendering is always safe.",
    )

    if failures or differences:
        return 1
    print("every frame is byte-identical to " + BASELINE_REF)
    return 0


if __name__ == "__main__":
    sys.exit(main())
