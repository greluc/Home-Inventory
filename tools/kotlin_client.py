#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Generates the Kotlin Multiplatform client from the OpenAPI document (REQ-API-002).

WHY MULTIPLATFORM AND NOT PLAIN KOTLIN

[ADR-0013](../docs/adr/0013-mobile-apps.md) chose Kotlin Multiplatform for the
apps and listed "OpenAPI with a generated Kotlin client in CI" as the preparation
that follows from it, *"because the contract must be cleanly generatable for
Kotlin too"*. A `jvm-okhttp` client compiles just as well and cannot be used from
`commonMain`, which is where the sync logic those apps share will live -- so it
would prove the contract generates and not that it generates into the place it is
needed.

WHY THE OUTPUT IS COMMITTED

The same reason `api/openapi.yaml`, `deploy/generated/`, `nginx/default.conf`,
the persisted-query register and the TypeScript client are: a contract change is
then a diff in a pull request, beside the code that caused it. `./gradlew build`
compiles it without the generator ever running.

WHAT IS KEPT AND WHAT IS THROWN AWAY

Only `src/commonMain`. The generator also writes a `build.gradle.kts`, a
`settings.gradle.kts`, a wrapper and a README, all describing a standalone
project -- this one is a subproject of the root build, whose build file names the
same dependencies through the version catalogue (REQ-NFR-029) and builds the JVM
target alone, because an iOS target needs a Mac and CI is Linux.

USAGE

    python tools/kotlin_client.py            # regenerate
    python tools/kotlin_client.py --check     # fail if the committed client is stale
"""

from __future__ import annotations

import filecmp
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCUMENT = ROOT / "api" / "openapi.yaml"
CLIENT = ROOT / "api" / "clients" / "kotlin" / "src" / "commonMain"

# Pinned exactly. The output is committed, so a generator that changed its
# formatting between two contributors would show up as a diff nobody made.
GENERATOR = "@openapitools/openapi-generator-cli@2.25.2"
GENERATOR_VERSION = "7.25.0"


def generate(into: Path) -> Path:
    """Runs the generator into a scratch directory and returns its commonMain."""
    npx = "npx.cmd" if sys.platform == "win32" else "npx"
    subprocess.run(
        [
            npx, "--yes", GENERATOR, "generate",
            "-i", str(DOCUMENT),
            "-g", "kotlin",
            "--library", "multiplatform",
            "--additional-properties",
            "dateLibrary=kotlinx-datetime,packageName=de.greluc.homeinv.client",
            "-o", str(into),
            "--skip-validate-spec",
        ],
        check=True,
        shell=sys.platform == "win32",
        stdout=subprocess.DEVNULL,
        env={**__import__("os").environ, "OPENAPI_GENERATOR_VERSION": GENERATOR_VERSION},
    )
    return into / "src" / "commonMain"


def differences(left: Path, right: Path) -> list[str]:
    """Every path under either tree whose contents differ, relative to the trees."""
    changed: list[str] = []
    left_files = {p.relative_to(left) for p in left.rglob("*") if p.is_file()}
    right_files = {p.relative_to(right) for p in right.rglob("*") if p.is_file()}
    for missing in sorted(left_files - right_files):
        changed.append(f"only generated: {missing.as_posix()}")
    for extra in sorted(right_files - left_files):
        changed.append(f"only committed: {extra.as_posix()}")
    for shared in sorted(left_files & right_files):
        if not filecmp.cmp(left / shared, right / shared, shallow=False):
            changed.append(f"differs: {shared.as_posix()}")
    return changed


def main() -> int:
    """Regenerates the client, or reports how the committed one differs."""
    checking = "--check" in sys.argv
    with tempfile.TemporaryDirectory() as scratch:
        fresh = generate(Path(scratch))

        if checking:
            if not CLIENT.exists():
                print("The Kotlin client is not committed. Run `python tools/kotlin_client.py`.")
                return 1
            changed = differences(fresh, CLIENT)
            if changed:
                print("The committed Kotlin client does not match api/openapi.yaml:")
                for line in changed[:20]:
                    print("  " + line)
                if len(changed) > 20:
                    print(f"  ... and {len(changed) - 20} more")
                print("Run `python tools/kotlin_client.py` and commit the result.")
                return 1
            files = sum(1 for _ in CLIENT.rglob("*.kt"))
            print(f"The committed Kotlin client matches the document ({files} files).")
            return 0

        if CLIENT.exists():
            shutil.rmtree(CLIENT)
        CLIENT.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(fresh, CLIENT)
        files = sum(1 for _ in CLIENT.rglob("*.kt"))
        print(f"Wrote {CLIENT.relative_to(ROOT).as_posix()} with {files} file(s).")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
