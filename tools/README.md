# tools/

The gates that read the corpus rather than the code.

Each is a plain Python script with no dependency beyond `pyyaml`, runs in under a
second, and is wired into [`ci.yml`](../.github/workflows/ci.yml). They are here
and not in `.github/` because a gate you can only run by pushing is a gate nobody
runs before pushing.

| Script | What it refuses | Origin |
|---|---|---|
| [`tracked_facts.py`](tracked_facts.py) | A fact stated twice and now disagreeing with itself — ADR counts, requirements per area, profile memory sums — plus every retired spelling outside the places allowed to quote it (`REQ-CON-015`) | A12 in [ADR-0000](../docs/adr/0000-open-points.md) |
| [`adr_links.py`](adr_links.py) | An amendment recorded in one direction only: ADR-A says it amends ADR-B and ADR-B does not say so, an amendment aimed at a requirement or chapter that does not exist, or an index row that omits one | A4b in [ADR-0000](../docs/adr/0000-open-points.md) |
| [`unbacked_claims.py`](unbacked_claims.py) | A **new** passage promising a property — *enforced*, *cannot*, *is refused*, *guaranteed* — without naming a requirement, ADR, test or check in the same paragraph | A7 in [ADR-0000](../docs/adr/0000-open-points.md), risk **R16** |
| [`render_problems.py`](render_problems.py) | A `problem.type` document that has drifted from [`problem-types.yaml`](../docs/reference/problem-types.yaml), which is the registry | A8 in [ADR-0000](../docs/adr/0000-open-points.md) |

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

## Stage

Stage 0, and they are all written. They arrived in the order the corpus needed
them, which is the order the risks were found in rather than any plan:
`render_problems.py` and `tracked_facts.py` on 2026-09-11, `adr_links.py` and
`unbacked_claims.py` on 2026-09-12 — the last two closing the two entries
[ADR-0000](../docs/adr/0000-open-points.md) still carried as outstanding stage-0
work.
