#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The unbacked-claim check — A7 in ADR-0000, specified in .github/workflows/README.md.

[14 §14.3] already writes the rule — *"a property with no test is a claim"* — and names its
own early warning sign: *"a document says 'enforced' or 'cannot' and no test name appears
next to it."* That is greppable, and until now nothing grepped it.

**How it stays usable.** A bare regex over ten thousand lines of deliberately emphatic prose
produces noise on day one and is switched off by week two. So this ships with a **baseline**,
the way a lint suppression list does: ``tools/unbacked-claims-baseline.txt`` records what is
unbacked today, the gate fails only on something **new**, and the list only ever shrinks.
That makes the check survivable, which matters more here than making it complete.

A claim counts as backed when the same paragraph — or the same table cell, which is one line —
names a verification: a ``REQ-`` id, an ADR, a named CI check or script, or a test class.

``--update`` rewrites the baseline. Do that when a claim is deliberately added without a
test, never to silence a failure you have not read.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "tools" / "unbacked-claims-baseline.txt"

# What the corpus says when it is promising a property rather than describing one.
# Taken from 14 §14.3's own list, plus the three spellings that turned out to carry
# the same weight in practice.
CLAIM = re.compile(
    r"\b("
    r"enforced|enforces|cannot|can never|must never|never happens|"
    r"fails the build|is refused|are refused|is impossible|impossible|"
    r"guaranteed|guarantees|always yields|machine-enforced"
    r")\b",
    re.IGNORECASE,
)

# What counts as naming the thing that checks it.
BACKING = re.compile(
    r"("
    r"REQ-[A-Z]+-\d+"              # a requirement, which carries an acceptance criterion
    r"|ADR-\d{4}"                  # a decision, which carries its consequences
    r"|\b\w+(?:Test|IT)\b"         # a test class: ArchitectureRulesTest, MediaScanIT
    r"|\.github/workflows/"        # a named workflow
    r"|tools/\w+\.py"              # a named gate script
    r"|gradlew\s+\S+"              # a named Gradle task
    r"|deploy/smoke/\w+\.sh"       # the smoke suites
    r")"
)

# Prose lives in docs/. The requirements catalogue is excluded: every row there IS a
# requirement with an acceptance criterion beside it, so the rule would match itself
# on every line. ADRs are in scope — a decision that promises a property and names no
# check is exactly what R16 describes.
SCANNED = ("docs/architecture", "docs/adr", "docs/design", "docs/reference")
SKIPPED_NAMES = {"0000-open-points.md"}


def paragraphs(text: str):
    """Splits a document into the units a claim and its backing must share.

    A table row is its own unit: the cells of one row are one statement with its evidence
    beside it, and the row above has nothing to do with it. Everything else is separated by
    blank lines, which is what a paragraph is in Markdown.

    :param text: the document
    :return: ``(first line number, block)`` pairs
    """
    blocks = []
    buffer: list[str] = []
    start = 1
    for number, line in enumerate(text.splitlines(), start=1):
        if line.startswith("|"):
            if buffer:
                blocks.append((start, "\n".join(buffer)))
                buffer = []
            blocks.append((number, line))
            start = number + 1
            continue
        if line.strip():
            if not buffer:
                start = number
            buffer.append(line)
        elif buffer:
            blocks.append((start, "\n".join(buffer)))
            buffer = []
    if buffer:
        blocks.append((start, "\n".join(buffer)))
    return blocks


def findings() -> list[str]:
    """Every claim in the scanned prose that names nothing that checks it.

    :return: one ``path:line:claim`` key per finding, sorted
    """
    found = []
    for directory in SCANNED:
        base = ROOT / directory
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.md")):
            if path.name in SKIPPED_NAMES:
                continue
            relative = path.relative_to(ROOT).as_posix()
            for line_number, block in paragraphs(path.read_text(encoding="utf-8")):
                if BACKING.search(block):
                    continue
                match = CLAIM.search(block)
                if match:
                    found.append(f"{relative}:{line_number}:{match.group(1).lower()}")
    return sorted(found)


def main() -> int:
    """Compares the findings against the baseline.

    :return: 0 when nothing new is unbacked, else 1
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--update",
        action="store_true",
        help="rewrite the baseline from the current corpus",
    )
    arguments = parser.parse_args()

    current = findings()

    if arguments.update:
        BASELINE.write_text(
            "# GENERATED by tools/unbacked_claims.py --update.\n"
            "#\n"
            "# Claims the corpus makes today without naming what checks them (A7, R16).\n"
            "# The gate fails on anything NOT in this list, so the list only shrinks. Adding\n"
            "# a line here is a decision to promise something and not test it; removing one\n"
            "# is the point of the file.\n"
            + "".join(f"{line}\n" for line in current),
            encoding="utf-8",
            newline="\n",
        )
        print(f"baseline rewritten: {len(current)} unbacked claim(s) recorded")
        return 0

    known = set()
    if BASELINE.exists():
        known = {
            line.strip()
            for line in BASELINE.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")
        }

    new = [line for line in current if line not in known]
    if new:
        print(f"{len(new)} claim(s) with nothing named that checks them:\n")
        for line in new:
            path, number, word = line.rsplit(":", 2)
            print(f"  {path}:{number} — says “{word}” and names no requirement,")
            print("      ADR, test or check in the same paragraph")
        print(
            "\n14 §14.3: a property with no test is a claim. Either name the thing that\n"
            "verifies it, or add the line to tools/unbacked-claims-baseline.txt\n"
            "deliberately — `python tools/unbacked_claims.py --update` after reading it."
        )
        return 1

    gone = len(known) - len([line for line in current if line in known])
    note = f", {gone} fewer than the baseline" if gone > 0 else ""
    print(f"No new unbacked claim ({len(current)} in the baseline{note}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
