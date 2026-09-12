#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The ADR back-link check — A4b in ADR-0000, specified in .github/workflows/README.md.

An ADR is never rewritten when a decision changes: a later one amends or supersedes it and
the older one carries a note saying so. That relation is hand-maintained in two directions
and in a third place, the index in ``docs/adr/README.md`` — and a hand-maintained index is
exactly the thing that goes stale while still reading as authoritative. That is risk R16,
stated precisely.

Three assertions, which are the three the specification writes out:

1. **Reciprocity.** If ADR-A declares it amends or supersedes ADR-B, then ADR-B carries the
   reciprocal note. Running this rule by hand on 2026-09-11 found five ADRs that did not.
2. **Targets exist.** Every chapter section and ``REQ-`` id named on an ``Amends:`` line
   resolves — a file that is there, and an identifier the requirements catalogue defines.
   An amendment aimed at a requirement that has been renumbered amends nothing.
3. **The index agrees.** The Status cell of each row in ``docs/adr/README.md`` names every
   ADR that amends or supersedes it. Four rows did not, on the same day.

Run it with no arguments; it prints what is wrong and exits non-zero, or says it is clean.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADR = ROOT / "docs" / "adr"
INDEX = ADR / "README.md"

# `**Amends:** [ADR-0015](0015-deployment.md), the *Podman* row — …` and the
# `**Partially supersedes:**` and `**Supersedes:**` forms, with or without the
# list marker some records put in front. Only the FIRST line matters: the prose
# that follows is deliberately outside the declaration (see ADR-0021).
DECLARATION = re.compile(
    r"^\s*(?:[-*]\s*)?\*\*(Amends|Supersedes|Partially supersedes):\*\*(.*)$",
    re.IGNORECASE,
)

# The reciprocal note, in whatever words the record uses. Three phrasings are in
# the corpus already — "Amended by [ADR-0044]", "Superseded in part by [ADR-0026]"
# and "Part 2 superseded by [ADR-0030]" — and forcing them into one shape would
# mean editing records that exist to state history. So the rule is structural
# rather than lexical: a bolded span that names an ADR and says it amended or
# superseded this one. What it still catches is the defect it is for — no mention
# of the amending ADR at all.
#
# The link must follow the word "by". Without that, ADR-0036's
# "**[ADR-0027] §2 is amended**" — which declares the opposite direction — reads
# as ADR-0027 having amended ADR-0036, and the check reports a defect in the index
# that is not there. Direction is the whole content of this relation.
BOLD_SPAN = re.compile(r"\*\*([^*]+)\*\*")
AMENDMENT_WORD = re.compile(r"amend|supersed", re.IGNORECASE)
BY_LINK = re.compile(r"by\s*\[ADR-(\d{4})\]", re.IGNORECASE)

ADR_LINK = re.compile(r"\[ADR-(\d{4})\]")
REQ_ID = re.compile(r"REQ-[A-Z]+-\d+")
# `[07 §7.8](../architecture/07-data-model.md)` — the link target is what is checked;
# the section number is prose a link cannot verify.
DOC_LINK = re.compile(r"\[[^\]]+\]\((\.\./[^)]+\.md)\)")

# `| [0024](0024-malware-scan.md) | … | Accepted, amended by 0036, 0037, 0054 |`
INDEX_ROW = re.compile(r"^\|\s*\[(\d{4})\]\([^)]*\)\s*\|[^|]*\|([^|]*)\|")


def adr_files() -> list[pathlib.Path]:
    """Every numbered ADR, README excluded.

    :return: the files, in numeric order
    """
    return sorted(p for p in ADR.glob("[0-9][0-9][0-9][0-9]-*.md"))


def number_of(path: pathlib.Path) -> str:
    """The four-digit number an ADR file name starts with.

    :param path: the ADR file
    :return: its number, as the four characters it is written with
    """
    return path.name[:4]


def declarations(text: str) -> list[tuple[str, str]]:
    """Every amendment or supersession an ADR declares about itself.

    Only the declaration line is read. An ADR that discusses another in its prose is not
    declaring anything about it, and treating it as one would make the reciprocity rule
    unusable — which is why ADR-0021's mention of ADR-0022 was moved off the line.

    :param text: the ADR's full text
    :return: one ``(relation, rest of the line)`` pair per declaration
    """
    found = []
    for line in text.splitlines():
        match = DECLARATION.match(line)
        if match:
            found.append((match.group(1).lower(), match.group(2)))
    return found


def backlinks(text: str) -> set[str]:
    """Every ADR this one records as having amended or superseded it.

    :param text: the ADR's full text
    :return: the numbers named in a bolded span that says so
    """
    found = set()
    for span in BOLD_SPAN.findall(text):
        if AMENDMENT_WORD.search(span):
            found.update(BY_LINK.findall(span))
    return found


def main() -> int:
    """Runs the three assertions.

    :return: 0 when the relation verifies in both directions and in the index, else 1
    """
    texts = {number_of(p): p.read_text(encoding="utf-8") for p in adr_files()}
    known_requirements = set()
    for name in ("01-functional.md", "02-non-functional.md", "03-security-and-privacy.md"):
        known_requirements.update(
            REQ_ID.findall((ROOT / "docs" / "requirements" / name).read_text(encoding="utf-8"))
        )

    problems: list[str] = []

    # Who declares what about whom, and who admits it.
    declared: dict[str, set[str]] = {n: set() for n in texts}
    for number, text in texts.items():
        for _relation, rest in declarations(text):
            for target in ADR_LINK.findall(rest):
                if target == number:
                    continue
                declared[number].add(target)

    admitted: dict[str, set[str]] = {number: backlinks(text) for number, text in texts.items()}

    # 1. Reciprocity.
    for source, targets in declared.items():
        for target in sorted(targets):
            if target not in texts:
                problems.append(f"ADR-{source} amends ADR-{target}, which does not exist")
            elif source not in admitted[target]:
                problems.append(
                    f"ADR-{source} declares it amends ADR-{target}, and ADR-{target} carries "
                    f"no reciprocal 'Amended by [ADR-{source}]' note"
                )

    # 2. The targets of an amendment exist.
    for number, text in texts.items():
        for _relation, rest in declarations(text):
            for requirement in REQ_ID.findall(rest):
                if requirement not in known_requirements:
                    problems.append(
                        f"ADR-{number} amends {requirement}, which the requirements "
                        f"catalogue does not define"
                    )
            for link in DOC_LINK.findall(rest):
                if not (ADR / link).resolve().exists():
                    problems.append(f"ADR-{number} amends {link}, which does not exist")

    # 3. The index names every amendment.
    index_status: dict[str, str] = {}
    for line in INDEX.read_text(encoding="utf-8").splitlines():
        match = INDEX_ROW.match(line)
        if match:
            index_status[match.group(1)] = match.group(2)

    for number in sorted(texts):
        if number not in index_status:
            problems.append(f"ADR-{number} has no row in docs/adr/README.md")
            continue
        status = index_status[number]
        for amender in sorted(admitted[number]):
            # The index writes them unpadded — "amended by 0036, 0037" — so both
            # spellings are accepted rather than one being imposed on the prose.
            if amender not in status and amender.lstrip("0") not in status:
                problems.append(
                    f"docs/adr/README.md row {number} does not name ADR-{amender}, "
                    f"which ADR-{number} records as amending it"
                )

    if problems:
        print(f"{len(problems)} problem(s) in the ADR amendment relation:\n")
        for problem in problems:
            print(f"  {problem}")
        print(
            "\nAn ADR is never rewritten; the relation is what makes a superseded one "
            "readable\nwithout being misleading. Fix the missing side rather than the "
            "declaration."
        )
        return 1

    print(
        f"{len(texts)} ADRs: every amendment is reciprocal, every target exists, "
        f"and the index names them all."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
