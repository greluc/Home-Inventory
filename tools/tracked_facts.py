#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Checks the facts the corpus states twice (REQ-CON-015).

A fact stated twice is checked, not trusted. Everything this script compares is
recorded once in ``docs/reference/tracked-facts.yaml`` with the expression that
computes it; every restatement of it anywhere in scope is compared against the
computed value, and a retired literal fails wherever it is not explicitly
allowed.

Why a script and not a review: five of these had already drifted by the time
anybody counted. The requirement count read 415 in two files after it was 419;
"44 decision records" was published on the front page one day after there were
48; the printed public code appeared in eighteen places in a spelling ADR-0030
had replaced. None of that needed judgement to find. It needed arithmetic, and
nothing was doing it.

Run with no arguments to check. Exit code 1 means a fact and its restatement
disagree, or a retired literal came back.
"""

from __future__ import annotations

import pathlib
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - the message is the point
    sys.exit("PyYAML is required: pip install pyyaml")

REPOSITORY = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = REPOSITORY / "docs" / "reference" / "tracked-facts.yaml"


def load_registry() -> dict:
    """Reads the registry.

    :return: the parsed registry
    """
    return yaml.safe_load(REGISTRY.read_text(encoding="utf-8"))


def files_in_scope(registry: dict) -> list[pathlib.Path]:
    """Every file the check reads.

    ``docs/adr/**`` is excluded by construction rather than by an allowlist. An
    ADR is the record of what was once decided, so a superseded spelling inside
    one is the point rather than a defect — ADR-0016 deliberately keeps a
    year-zero code format under a header saying it is superseded. One structural
    exemption beats several dozen per-file ones, and it is the exemption that
    cannot be abused: an ADR states history, it does not instruct.

    :param registry: the parsed registry
    :return: the files, sorted, as repository-relative paths
    """
    scope = registry["scope"]
    suffixes = {"." + kind for kind in scope["fileTypes"]}
    excluded = [pattern.rstrip("*").rstrip("/") for pattern in scope["exclude"]]

    found: set[pathlib.Path] = set()
    for pattern in scope["include"]:
        for path in REPOSITORY.glob(pattern.strip('"')):
            if path.is_file() and path.suffix in suffixes:
                found.add(path)

    def is_excluded(path: pathlib.Path) -> bool:
        relative = path.relative_to(REPOSITORY).as_posix()
        return any(relative == rule or relative.startswith(rule + "/") or relative == rule.rstrip("/")
                   for rule in excluded)

    return sorted(path for path in found if not is_excluded(path))


# ---------------------------------------------------------------------------
# The computations. Each one is the `compute:` expression of a registry entry,
# written out - because a `compute:` string is prose and a reader has to be able
# to check that the code does what it says.
# ---------------------------------------------------------------------------
def compute(registry: dict) -> dict[str, object]:
    """Recomputes every tracked fact from the repository.

    :param registry: the parsed registry
    :return: fact id to its current value
    """
    adr_files = sorted((REPOSITORY / "docs" / "adr").glob("[0-9][0-9][0-9][0-9]-*.md"))

    requirement_rows: list[str] = []
    for name in ("01-functional.md", "02-non-functional.md", "03-security-and-privacy.md"):
        path = REPOSITORY / "docs" / "requirements" / name
        requirement_rows += [line for line in path.read_text(encoding="utf-8").splitlines()
                             if line.startswith("| REQ-")]

    per_area: dict[str, int] = {}
    for row in requirement_rows:
        area = row.split("-")[1]
        per_area[area] = per_area.get(area, 0) + 1

    components = sorted((REPOSITORY / "design-system" / "components").glob("*/*.jsx"))
    exports: set[str] = set()
    # PascalCase, which is what the registry counts: a name that starts uppercase
    # AND contains a lowercase letter. `STATUS`, `SCAN_LABELS` and
    # `CONFLICT_LABELS` are exported constants rather than components, and
    # counting them would give 58 where the design system has 55.
    pascal_case = r"([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)"
    for module in components:
        source = module.read_text(encoding="utf-8")
        exports.update(re.findall(r"export\s+(?:default\s+)?function\s+" + pascal_case, source))
        exports.update(re.findall(r"export\s+const\s+" + pascal_case, source))

    permissions = yaml.safe_load(
        (REPOSITORY / "docs" / "reference" / "permissions.yaml").read_text(encoding="utf-8"))
    health_states = yaml.safe_load(
        (REPOSITORY / "docs" / "reference" / "plugin-health-states.yaml").read_text(encoding="utf-8"))

    # REQ-NFR-024: the shared kernel's size is monitored. Counted as types rather
    # than as lines, because a line count moves with a rewritten comment and the
    # question being asked is "how much has accumulated in here", which is a
    # question about things.
    platform_types = sorted((REPOSITORY / "app" / "src" / "main" / "java" / "de" / "greluc"
                             / "homeinv" / "platform").glob("*.java"))

    return {
        "adrCount": len(adr_files),
        "platformTypes": len(platform_types),
        "requirementTotal": len(requirement_rows),
        "requirementsPerArea": per_area,
        "architectureChapters": len(sorted((REPOSITORY / "docs" / "architecture").glob("*.md"))),
        "componentModules": len(components),
        "componentExports": len(exports),
        "componentGroups": len([d for d in (REPOSITORY / "design-system" / "components").iterdir()
                                if d.is_dir()]),
        "specimenCards": len(sorted((REPOSITORY / "design-system" / "guidelines").glob("*.html"))),
        "lucideIcons": len(sorted((REPOSITORY / "design-system" / "assets" / "icons").glob("*.svg"))),
        "buildingBlocks": None,          # prose table; the registry's value is authoritative
        "pluginHealthStates": len(health_states["states"]),
        "permissionCount": len(permissions["permissions"]),
        "profileMemory": profile_memory(),
        "contrastRatios": None,          # delegated to REQ-NFR-077's own gate
    }


def profile_memory() -> dict[str, str]:
    """The memory sums per profile, from the service matrix.

    The conventions are the trap rather than the arithmetic: ``migrate`` is
    one-shot and is not in the sum, and plugins are the separate per-plugin adder
    of REQ-NFR-009 and are not in it either.

    :return: the four figures, as the one-decimal strings the prose uses
    """
    matrix = yaml.safe_load((REPOSITORY / "deploy" / "services.yaml").read_text(encoding="utf-8"))

    def mib(quantity) -> int:
        if quantity is None:
            return 0
        match = re.match(r"^(\d+)(Mi|Gi)$", str(quantity))
        return int(match.group(1)) * (1 if match.group(2) == "Mi" else 1024)

    sums: dict[str, str] = {}
    for profile in ("minimal", "standard"):
        reservation = limit = 0
        for service in matrix["services"].values():
            if profile not in (service.get("profiles") or []):
                continue
            if service.get("lifecycle") == "oneShot":
                continue
            if service.get("role") == "plugin":
                # The docstring's second convention, which had nothing to skip
                # until 2026-09-20: `REQ-NFR-009` states a profile's base and a
                # SEPARATE per-plugin adder ("+ 64 MB per plugin"), and folding a
                # plugin into the base would make every restatement of the base
                # wrong while the adder still stood beside it.
                continue
            resources = service.get("resources") or {}
            reservation += mib(resources.get("memoryReservation"))
            limit += mib(resources.get("memoryLimit"))
        sums[profile + "Reservation"] = f"{reservation / 1024:.1f}"
        sums[profile + "Limit"] = f"{limit / 1024:.1f}"
    return sums


# ---------------------------------------------------------------------------
# The checks
# ---------------------------------------------------------------------------
def check_computed(registry: dict, current: dict, files: list[pathlib.Path]) -> list[str]:
    """Compares every restatement of a tracked fact against its computed value.

    :param registry: the parsed registry
    :param current: the recomputed values
    :param files: the files in scope
    :return: one message per failure
    """
    failures: list[str] = []

    for entry in registry["computed"]:
        fact = entry["id"]
        pattern = entry.get("pattern")
        recorded = entry.get("value")
        computed = current.get(fact)

        # 1. The registry's own value must equal the recomputed one. A registry
        #    that drifts from the repository is the failure this whole file is
        #    about, one level up.
        if computed is not None and recorded != computed:
            failures.append(
                f"{fact}: tracked-facts.yaml records {recorded!r}, the repository has {computed!r}")
            continue

        if not pattern:
            continue

        expected = {str(recorded)} if not isinstance(recorded, dict) else {
            str(value) for value in recorded.values()}

        context = entry.get("context")
        applies_to = entry.get("appliesTo")

        for path in files:
            relative = path.relative_to(REPOSITORY).as_posix()
            if applies_to and relative not in applies_to:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for match in re.finditer(pattern, text):
                if context:
                    window = text[max(0, match.start() - 80):match.end() + 80]
                    if not re.search(context, window, re.IGNORECASE):
                        continue
                captured = next((group for group in match.groups() if group), None)
                if captured is None or captured in expected:
                    continue
                if isinstance(recorded, dict) and fact == "requirementsPerArea":
                    area = match.group(1)
                    if area not in recorded:
                        continue
                    if str(recorded[area]) != match.group(2):
                        failures.append(
                            f"{relative}:{line_of(text, match.start())}: {fact} says "
                            f"{area} {match.group(2)}, the repository has {recorded[area]}")
                    continue
                failures.append(
                    f"{relative}:{line_of(text, match.start())}: {fact} restated as "
                    f"{captured!r}; the computed value is {sorted(expected)}")
    return failures


def check_retired(registry: dict, files: list[pathlib.Path]) -> list[str]:
    """Fails on a spelling a decision replaced.

    :param registry: the parsed registry
    :param files: the files in scope
    :return: one message per occurrence
    """
    failures: list[str] = []
    retired = registry["retired"]

    for entry in retired.get("forbidden") or []:
        pattern = entry.get("pattern") or re.escape(entry["literal"])
        for path in files:
            text = path.read_text(encoding="utf-8", errors="replace")
            for match in re.finditer(pattern, text):
                relative = path.relative_to(REPOSITORY).as_posix()
                failures.append(
                    f"{relative}:{line_of(text, match.start())}: retired by {entry['by']} — "
                    f"{match.group(0)!r}, replaced by {entry.get('replacedBy')!r}")

    for entry in retired.get("named") or []:
        allowed = set(entry["allowedIn"])
        for path in files:
            relative = path.relative_to(REPOSITORY).as_posix()
            if relative in allowed:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            match = re.search(entry["pattern"], text)
            if match:
                failures.append(
                    f"{relative}:{line_of(text, match.start())}: {match.group(0)!r} was withdrawn "
                    f"by {entry['by']}. It may be NAMED only where its withdrawal is documented; "
                    f"a new path means it came back rather than being described.")
    return failures


def line_of(text: str, offset: int) -> int:
    """The one-based line an offset falls on.

    :param text: the file content
    :param offset: a character offset into it
    :return: the line number
    """
    return text.count("\n", 0, offset) + 1


def main() -> int:
    """Runs both checks.

    :return: the process exit code
    """
    registry = load_registry()
    files = files_in_scope(registry)
    current = compute(registry)

    failures = check_computed(registry, current, files) + check_retired(registry, files)

    if failures:
        print(f"{len(failures)} tracked fact(s) disagree with the repository:\n")
        for failure in failures:
            print(f"  {failure}")
        print("\nEither the repository changed and tracked-facts.yaml has not, or a restatement "
              "is stale. Both are the same bug: a fact stated twice (REQ-CON-015).")
        return 1

    print(f"{len(registry['computed'])} tracked facts and "
          f"{len(registry['retired'].get('forbidden') or [])} retired literals check out "
          f"across {len(files)} files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
