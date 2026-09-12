#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Renders one document per RFC 9457 problem type, from the registry.

A `type` URI is part of the contract: clients branch on it, so it changes like
the contract does (`REQ-API-003`). The URIs are also meant to be *dereferenceable*
— somebody who receives one should be able to open it and read what it means —
and that is what these documents are.

They are generated rather than written, for the same reason the OpenAPI document
is: `docs/reference/problem-types.yaml` is the registry, and a second
hand-maintained description of the same tokens would drift from it. Run with
``--check`` to compare instead of writing, which is what CI does.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - the message is the point
    sys.exit("PyYAML is required: pip install pyyaml")

REPOSITORY = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = REPOSITORY / "docs" / "reference" / "problem-types.yaml"
OUTPUT = REPOSITORY / "api" / "problems"

BANNER = (
    "<!--\n"
    "  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.\n"
    "\n"
    "  Run `python tools/render_problems.py` after changing the registry. CI\n"
    "  regenerates these and fails on any difference, so an edit here is lost and\n"
    "  noticed rather than lost and not (REQ-API-003).\n"
    "-->\n\n"
)

STATE = {
    "registered": (
        "**Registered.** Published at this URI and stable. A client may branch on it, and it "
        "will not change meaning or status code without a new major version of the API "
        "(REQ-API-008)."
    ),
    "assigned": (
        "**Assigned.** In use and stable within this product. It is not published to an external "
        "registry, which changes nothing for a client: the URI is fixed for the product rather "
        "than derived from a deployment's hostname, so branching on it works across instances."
    ),
    "pending": (
        "**Pending.** Proposed and not yet emitted by any response. Do not branch on it — it may "
        "still change."
    ),
}


def render(entry: dict, state: str, namespace: str) -> str:
    """Renders one problem type as Markdown.

    :param entry: the registry row
    :param state: which of the registry's three lists it came from
    :param namespace: the URI prefix every token hangs under
    :return: the document
    """
    token = entry["token"]
    lines = [
        BANNER,
        f"# `{token}`\n",
        f"**Type URI:** `{namespace}{token}`  ",
        f"**HTTP status:** `{entry['status']}`  ",
        f"**Since:** stage {entry['since']}\n",
        STATE[state] + "\n",
        "## What it means\n",
        entry["summary"].strip() + "\n",
    ]

    if entry.get("notes"):
        lines += ["## Notes\n", entry["notes"].strip() + "\n"]

    if entry.get("carries"):
        lines += [
            "## What the response carries\n",
            "Beyond the members RFC 9457 defines and the `traceId` every response in this API "
            "carries:\n",
        ]
        carries = entry["carries"]
        if isinstance(carries, list):
            lines += ["".join(f"- `{member}`\n" for member in carries) + "\n"]
        else:
            lines += [str(carries).strip() + "\n"]

    if entry.get("sources"):
        lines += [
            "## Where this is specified\n",
            "".join(f"- {source}\n" for source in entry["sources"]) + "\n",
        ]

    return "\n".join(lines)


def render_index(registry: dict) -> str:
    """Renders the index of every problem type.

    :param registry: the parsed registry
    :return: the index document
    """
    namespace = registry["namespace"]
    rows = []
    for state in ("registered", "assigned", "pending"):
        for entry in registry.get(state) or []:
            rows.append(
                f"| [`{entry['token']}`]({entry['token']}.md) | `{entry['status']}` | "
                f"{state} | {entry['summary'].strip().splitlines()[0]} |"
            )

    return (
        BANNER
        + "# Problem types\n\n"
        + "Every `type` URI this API can return, one document per token.\n\n"
        + "A client branches on the `type` and never on the `detail`: the URI is stable and the "
        + "prose is not, and the prose is translated. The URI is fixed for the **product** rather "
        + "than derived from a deployment's hostname, so a client that recognises "
        + f"`{namespace}not-found` recognises it from every instance (REQ-API-003).\n\n"
        + f"**Namespace:** `{namespace}`\n\n"
        + "| Token | Status | State | Summary |\n|---|---|---|---|\n"
        + "\n".join(rows)
        + "\n"
    )


def main() -> int:
    """Writes or checks the rendered documents.

    :return: the process exit code
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="compare instead of writing; exit non-zero on any difference")
    args = parser.parse_args()

    registry = yaml.safe_load(REGISTRY.read_text(encoding="utf-8"))
    namespace = registry["namespace"]

    outputs: dict[pathlib.Path, str] = {OUTPUT / "README.md": render_index(registry)}
    for state in ("registered", "assigned", "pending"):
        for entry in registry.get(state) or []:
            outputs[OUTPUT / f"{entry['token']}.md"] = render(entry, state, namespace)

    # A token removed from the registry leaves a document behind, and a stale one
    # is worse than none: it still resolves, and it still reads as current.
    OUTPUT.mkdir(parents=True, exist_ok=True)
    orphans = [path for path in OUTPUT.glob("*.md") if path not in outputs]

    drifted = []
    for path, content in outputs.items():
        current = path.read_text(encoding="utf-8") if path.exists() else ""
        if current != content:
            drifted.append(path)
            if not args.check:
                path.write_text(content, encoding="utf-8")

    if args.check:
        if drifted or orphans:
            print("These problem documents differ from the registry:")
            for path in drifted:
                print(f"  {path.relative_to(REPOSITORY)}")
            for path in orphans:
                print(f"  {path.relative_to(REPOSITORY)} (no longer in the registry)")
            print("\nRun `python tools/render_problems.py` and commit the result.")
            return 1
        print(f"{len(outputs)} problem documents match the registry.")
        return 0

    for path in orphans:
        path.unlink()
    print(f"Wrote {len(outputs)} problem documents; removed {len(orphans)} orphan(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
