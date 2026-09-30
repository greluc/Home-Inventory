# tools/

The gates that read the corpus rather than the code, and the two generators whose
output is committed.

Each is a plain Python script with no dependency beyond `pyyaml`, runs in under a
second — `notices.py` and `kotlin_client.py` excepted, which read build output and
take a few — and is wired into [`ci.yml`](../.github/workflows/ci.yml). They are
here and not in `.github/` because a gate you can only run by pushing is a gate
nobody runs before pushing.

| Script | What it refuses | Origin |
|---|---|---|
| [`tracked_facts.py`](tracked_facts.py) | A fact stated twice and now disagreeing with itself — ADR counts, requirements per area, profile memory sums — plus every retired spelling outside the places allowed to quote it (`REQ-CON-015`) | A12 in [ADR-0000](../docs/adr/0000-open-points.md) |
| [`adr_links.py`](adr_links.py) | An amendment recorded in one direction only: ADR-A says it amends ADR-B and ADR-B does not say so, an amendment aimed at a requirement or chapter that does not exist, or an index row that omits one | A4b in [ADR-0000](../docs/adr/0000-open-points.md) |
| [`unbacked_claims.py`](unbacked_claims.py) | A **new** passage promising a property — *enforced*, *cannot*, *is refused*, *guaranteed* — without naming a requirement, ADR, test or check in the same paragraph | A7 in [ADR-0000](../docs/adr/0000-open-points.md), risk **R16** |
| [`dead_links.py`](dead_links.py) | A relative link that resolves to nothing — resolved against what git tracks, so a wrong-case link fails here and not only on the Linux runner | Replaced a shell step on 2026-09-12 that had never passed |
| [`workflow_shell.py`](workflow_shell.py) | A `run:` block that is not valid shell — a heredoc terminator that YAML indentation moved off column zero, most often | Two of those shipped on 2026-09-12 |
| [`render_problems.py`](render_problems.py) | A `problem.type` document that has drifted from [`problem-types.yaml`](../docs/reference/problem-types.yaml), which is the registry | A8 in [ADR-0000](../docs/adr/0000-open-points.md) |
| [`notices.py`](notices.py) | A licence notice that no longer describes what its artifact carries, and — with a message naming each one — any component in it that can be attributed to no licence text at all (`REQ-CON-013`) | [ADR-0083](../docs/adr/0083-the-notice-travels-inside-the-artifact.md) |

## The one with a baseline

`unbacked_claims.py` is the only one that ships with a list of things it tolerates:
[`unbacked-claims-baseline.txt`](unbacked-claims-baseline.txt), 106 entries on the
day it was written. That is deliberate and is what makes it survivable — a bare
regex over ten thousand lines of deliberately emphatic prose fails everywhere on
day one and is switched off by week two.

**The list only shrinks.** Removing a line means a claim gained a test; adding one
is a decision to promise something and not check it, and should be as uncomfortable
as it sounds. `python tools/unbacked_claims.py --update` rewrites it — after
reading what changed, never to silence a failure.

## The one that reads build output

`notices.py` is the exception to the first sentence above: it reads the **artifacts**
— the jars inside the boot jar, the packages Rollup put into the chunks, the crates
in the registry — rather than the corpus, because the question it answers is what a
distributed artifact carries and no document knows that. Run it after building the
thing you are asking about; with no arguments it does every artifact that has been
built and says which it skipped, and `--check` is what CI runs.

Its two data directories are **third-party text kept verbatim** and are never edited
to fit this project's style (`CLAUDE.md`, the second carve-out of `REQ-CON-014`):
`notices/licences/` holds the canonical text of a licence whose wording is the same
for everyone under it, and `notices/overrides/` the notice of a component that
publishes none inside its own artifact — each with the URL it was taken from and the
date it was read.

## Stage

Stage 0, and they are all written. They arrived in the order the corpus needed
them, which is the order the risks were found in rather than any plan:
`render_problems.py` and `tracked_facts.py` on 2026-09-11, `adr_links.py` and
`unbacked_claims.py` on 2026-09-12 — the last two closing the two entries
[ADR-0000](../docs/adr/0000-open-points.md) still carried as outstanding stage-0
work.
