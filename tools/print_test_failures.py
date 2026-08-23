#!/usr/bin/env python3
"""Print Gradle test failures, with their messages, to the build log.

Why this exists. Kotlin/Native test failures reach the Gradle console as a single line --
``kotlin.AssertionError at null:-1`` -- with the assertion's message stripped out. RenG's
``0.3.0`` publication failed closed on a hosted macOS runner and that line was the entire
diagnostic available: the message, the standard output and the HTML report all stayed on a
runner nobody can log into. This walks the JUnit XML Gradle already writes and prints what the
console withheld.

It is a diagnostic, not a gate. It never fails: a job that reaches this step has already
failed, and a reporting error must not change how it failed.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

STANDARD_OUTPUT_CHARACTER_LIMIT = 40000
DETAIL_CHARACTER_LIMIT = 8000


def _text(value: str | None, limit: int) -> str:
    trimmed = (value or "").strip()
    if len(trimmed) <= limit:
        return trimmed
    return trimmed[:limit] + f"\n... [{len(trimmed) - limit} more characters in the uploaded report]"


def _report(path: Path) -> bool:
    try:
        root = ElementTree.parse(path).getroot()
    except (ElementTree.ParseError, OSError) as error:
        print(f"  (could not read {path}: {error})")
        return False

    problems = [
        (case, problem)
        for case in root.iter("testcase")
        for problem in list(case.iter("failure")) + list(case.iter("error"))
    ]
    if not problems:
        return False

    print(f"\n=== {path}")
    for case, problem in problems:
        name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
        print(f"\n--- FAILED {name}")
        message = _text(problem.get("message"), DETAIL_CHARACTER_LIMIT)
        if message:
            print(message)
        detail = _text(problem.text, DETAIL_CHARACTER_LIMIT)
        if detail and detail != message:
            print(detail)

    for output in root.iter("system-out"):
        printed = _text(output.text, STANDARD_OUTPUT_CHARACTER_LIMIT)
        if printed:
            print(f"\n--- standard output for {path.name}")
            print(printed)
    return True


def main(argv: list[str]) -> int:
    roots = [Path(argument) for argument in argv[1:]] or [Path("kmp/build/test-results")]
    found = False
    for root in roots:
        if not root.exists():
            continue
        candidates = sorted(root.rglob("*.xml")) if root.is_dir() else [root]
        for candidate in candidates:
            found = _report(candidate) or found
    if not found:
        print(f"No failing test cases found under: {', '.join(str(root) for root in roots)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
