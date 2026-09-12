#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Every relative link in the corpus resolves.

A dead link in a document that calls itself the single source of truth is worse than no
document: it reads as a pointer to something authoritative and goes nowhere.

**Why this is Python and not the four lines of shell it replaces.** The shell version ran
under ``set -o pipefail`` and piped ``grep`` into ``grep``, so a Markdown file containing
*no* relative links made the pipeline exit 1 and the whole gate fail — which is most of
``api/problems/`` and ``CODE_OF_CONDUCT.md``. It also reported the offending link from
inside a subshell, where the assignment that recorded the failure could not escape, so the
job failed without ever printing which link was dead. It had never passed.

**Case matters, and on the machine that writes these files it does not.** The corpus is
written on Windows, where ``docs/README.md`` and ``docs/readme.md`` are the same file, and
it is checked on Linux, where they are not. So a link is resolved against the list of paths
git tracks rather than against the filesystem, which makes the check as case-sensitive as
the runner is.
"""

from __future__ import annotations

import posixpath
import re
import subprocess
import sys

LINK = re.compile(r"\]\(([^)]+)\)")
EXTERNAL = ("http://", "https://", "#", "mailto:")

# Rendered by other tooling and not part of the corpus these rules govern.
SKIPPED_PREFIXES = ("design-system/", "website/")


def tracked_paths() -> tuple[set[str], set[str]]:
    """Every file git tracks, and every directory implied by one.

    :return: the file paths and the directory paths, both as git spells them
    """
    # Tracked, staged AND untracked-but-present. A link to a file that exists and
    # has not been `git add`ed yet is not a dead link, and reporting it as one makes
    # the gate fire on the one occasion somebody runs it before committing — which is
    # exactly when it is most useful. `--exclude-standard` keeps ignored files out, so
    # a link into a build directory still fails, here and on the runner alike.
    listing = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.split("\n")
    files = {line for line in listing if line}
    directories = set()
    for path in files:
        parts = path.split("/")
        for depth in range(1, len(parts)):
            directories.add("/".join(parts[:depth]))
    return files, directories


def main() -> int:
    """Checks every relative link in every Markdown file of the corpus.

    :return: 0 when each resolves, else 1
    """
    files, directories = tracked_paths()
    documents = [
        path
        for path in sorted(files)
        if path.endswith(".md") and not path.startswith(SKIPPED_PREFIXES)
    ]

    dead: list[tuple[str, str]] = []
    checked = 0
    for document in documents:
        directory = posixpath.dirname(document)
        try:
            with open(document, encoding="utf-8") as handle:
                text = handle.read()
        except OSError as unreadable:
            dead.append((document, f"could not be read: {unreadable}"))
            continue

        for raw in LINK.findall(text):
            if raw.startswith(EXTERNAL):
                continue
            target = raw.split("#", 1)[0]
            if not target:
                # A link to an anchor in the same document. Whether the anchor
                # exists is a different question, and not one a path can answer.
                continue
            checked += 1
            resolved = posixpath.normpath(posixpath.join(directory, target))
            if resolved not in files and resolved not in directories:
                dead.append((document, raw))

    if dead:
        print(f"{len(dead)} dead relative link(s):\n")
        for document, link in dead:
            print(f"  {document}\n      {link}")
        print(
            "\nThe path is resolved against what git tracks, so a link that works on a "
            "case-insensitive\nfilesystem and not on the runner fails here — which is the "
            "point."
        )
        return 1

    print(f"{checked} relative links across {len(documents)} documents, all resolving.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
