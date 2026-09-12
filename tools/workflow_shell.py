#!/usr/bin/env python3
# SPDX-FileCopyrightText: Lucas Greuloch
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Every ``run:`` block in every workflow is valid shell.

A workflow step is a shell script that nothing parses until a runner reaches it, which
makes a syntax error the most expensive kind of typo in this repository: it costs a push,
a queue and several minutes, and it reports itself as whatever step happened to be running.

Twice on 2026-09-12 a quoted heredoc inside a ``case`` branch ended a step with "syntax
error: unexpected end of file". YAML block scalars strip the block's base indentation, so
a terminator that looks aligned in the file arrives indented — and a quoted heredoc's
terminator must be at column zero. Neither the YAML parser nor the action linter sees that.
``bash -n`` does, in about a millisecond.

``${{ ... }}`` expressions are replaced with a harmless token first: they are GitHub's
syntax, not the shell's, and a step is checked for the shape of its script rather than for
what any particular expansion turns it into.
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import tempfile

WORKFLOWS = pathlib.Path(__file__).resolve().parent.parent / ".github" / "workflows"
EXPRESSION = re.compile(r"\$\{\{[^}]*\}\}")

# A token that is a single shell word wherever an expression can appear: a command name, an
# argument, part of a string. `x` would collide with a real command; this cannot.
PLACEHOLDER = "gha_expression_placeholder"


def shells(document: dict):
    """Every ``run:`` script in a workflow, with the step that carries it.

    :param document: the parsed workflow
    :return: ``(job name, step name, script)`` triples
    """
    for job_name, job in (document.get("jobs") or {}).items():
        for index, step in enumerate(job.get("steps") or []):
            script = step.get("run")
            if script:
                yield job_name, step.get("name", f"step {index + 1}"), script


def main() -> int:
    """Runs ``bash -n`` over every step of every workflow.

    :return: 0 when all of them parse, else 1
    """
    try:
        import yaml
    except ImportError:
        print("pyyaml is required: pip install pyyaml")
        return 1

    problems = []
    checked = 0
    for path in sorted(WORKFLOWS.glob("*.yml")):
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not document:
            continue
        for job, step, script in shells(document):
            checked += 1
            neutral = EXPRESSION.sub(PLACEHOLDER, script)
            with tempfile.NamedTemporaryFile(
                "w", suffix=".sh", delete=False, encoding="utf-8", newline="\n"
            ) as handle:
                handle.write(neutral)
                temporary = handle.name
            try:
                result = subprocess.run(
                    ["bash", "-n", temporary], capture_output=True, text=True, check=False
                )
            finally:
                pathlib.Path(temporary).unlink(missing_ok=True)
            if result.returncode != 0:
                problems.append((path.name, job, step, result.stderr.strip()))

    if problems:
        print(f"{len(problems)} workflow step(s) are not valid shell:\n")
        for workflow, job, step, message in problems:
            print(f"  {workflow} · {job} · {step}")
            for line in message.splitlines():
                print(f"      {line}")
        print(
            "\nA heredoc inside an indented block is the usual cause: YAML strips the "
            "block's\nbase indentation, and a quoted heredoc's terminator has to end up at "
            "column zero."
        )
        return 1

    print(f"{checked} workflow steps, all valid shell.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
