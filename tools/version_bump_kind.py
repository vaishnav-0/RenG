#!/usr/bin/env python3
"""Classifies the change between two `VERSION_NAME` values as major, minor, patch or none.

Cycle K uses this to gate the frame comparison, which is the most expensive job in CI: it renders
the corpus twice on a macOS runner. A patch is a fix that should not move a pixel it did not mean to,
but the cost of proving that on every patch PR is not worth paying, and the owner can always run the
comparison locally. A major or minor bump is where the renderer's behaviour is expected to change,
and where a comparison is worth its runtime.

**Anything unparseable is `none`.** A gate that cannot read a version must not guess that an
expensive job is wanted; it declines, visibly, and the operator runs the comparison by hand.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Sequence

_VERSION = re.compile(r"^\s*VERSION_NAME\s*=\s*(?P<version>[^\s#]+)\s*$", re.MULTILINE)
_SEMANTIC = re.compile(r"^(?P<major>\d+)\.(?P<minor>\d+)\.(?P<patch>\d+)$")


def read_version(text: str) -> tuple[int, int, int] | None:
    """The single `VERSION_NAME` in a `gradle.properties`, or `None` if it is absent or not stable.

    A pre-release or build-metadata suffix reads as `None` rather than as its numeric prefix: those
    are not versions this repository publishes (ADR 0013 takes only canonical stable versions), and
    silently truncating one would classify a bump the resolver would refuse.
    """
    matches = _VERSION.findall(text)
    if len(matches) != 1:
        return None
    semantic = _SEMANTIC.match(matches[0].strip())
    if semantic is None:
        return None
    return (
        int(semantic.group("major")),
        int(semantic.group("minor")),
        int(semantic.group("patch")),
    )


def classify(base: str, head: str) -> str:
    """`major`, `minor`, `patch` or `none` for the move from `base` to `head`."""
    before, after = read_version(base), read_version(head)
    if before is None or after is None or after <= before:
        return "none"
    if after[0] != before[0]:
        return "major"
    if after[1] != before[1]:
        return "minor"
    return "patch"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--head", type=Path, required=True)
    arguments = parser.parse_args(argv)
    base = arguments.base.read_text(encoding="utf-8") if arguments.base.is_file() else ""
    head = arguments.head.read_text(encoding="utf-8") if arguments.head.is_file() else ""
    print(classify(base, head))
    return 0


if __name__ == "__main__":
    sys.exit(main())
