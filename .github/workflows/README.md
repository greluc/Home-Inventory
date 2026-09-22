# .github/workflows/

CI and release workflows.

## Status

Four workflows exist: [`ci.yml`](ci.yml) (build, test, architecture rules, the API
contract, the deployment descriptions, the application image, the web client and the
documentation rules), [`smoke.yml`](smoke.yml) (the rootless matrix — Podman and Docker),
[`security.yml`](security.yml) and [`pages.yml`](pages.yml), which publishes the project
website and carries a real gate of its own: it greps the assembled site for resources
loaded from another host and fails, which is `REQ-PRIV-015` verified rather than asserted.

**All three documentation gates below are implemented**, in [`tools/`](../../tools/) so
that they can be run before pushing rather than only by pushing: `adr_links.py` (A4b),
`unbacked_claims.py` (A7) and `tracked_facts.py` (A12). The sections below are kept as the
record of what each was specified to do — and A4b is worth re-reading, because writing it
found four defects that the same rule run by hand had not.

*This section said "One workflow exists … there is no application to build yet" until
2026-09-12, by which point there were four workflows and an application. A status section
that goes stale is the failure mode the gates below exist to catch, in the file that
describes them.*

## The gates, already decided

These come from the requirements catalogue, not from habit. Each fails the build:

| Area | Check |
|---|---|
| **Module boundaries** | Spring Modulith + ArchUnit — no cycles, no reaching past an `api` package, no framework imports in `domain`, no entity leaving its block |
| **Tenant isolation** | An automated proof across **every** table; a new table without RLS fails |
| **Authorization** | Every endpoint has a permission check and negative tests for `403` and `404` |
| **Contracts** | `oasdiff` (REST), `buf breaking` (plugins), schema comparison (GraphQL, events) |
| **Deployment** | No `privileged`, `cap_add`, host network, mounted socket, port < 1024, or `SecurityLabelDisable` — across Compose, Quadlet **and** Helm |
| **Drift** | The generated artifacts match `deploy/services.yaml` |
| **Secrets** | `gitleaks` |
| **Supply chain** | Dependency and container scanning; high or critical fails |
| **SAST** | CodeQL, SpotBugs with `find-sec-bugs`, ESLint security |
| **Headers** | The served CSP compared against an expected string — including that `trusted-types` is present and `COEP` is absent |
| **Datastore auth** | Every store in the deployment refuses an unauthenticated connection, and `web` reaches `api` and nothing else (REQ-SEC-104/105) |
| **Recovery point** | The weekly restore rehearsal recovers to a timestamp **between** two dumps, which is the only thing that tests REQ-NFR-014 |
| **Design-system adherence** | `adherence.oxlintrc.json` at `error`, plus the token drift check and the contrast recomputation (REQ-NFR-075/076/077) |
| **Language** | No German-language document outside the two-entry carve-out of REQ-CON-012 |
| **Licensing** | No AGPL-incompatible dependency in the core; only permissive ones in the plugin API |
| **Licence notices** | Every one of the nine distributed artifacts carries a notice that still describes what it holds, regenerated from the built artifact and compared (`tools/notices.py --check`, REQ-CON-013) |
| **DCO + CLA** | Every commit signed off; the contributor has signed |
| **ADR back-links** | Every ADR named in another's `Amends:` carries the reciprocal note — see below |
| **Restated facts** | Every number a second document repeats is recomputed; every retired spelling fails — see below |
| **Unbacked claims** | A hardening claim with no named verification next to it — see below |

## Three checks over the documentation, and why they are gates

All three run on text alone — Markdown, YAML, HTML, JSON. They need no build, no
container and no application code, so they are the **first** gates this directory gets
beyond `pages.yml`, and each one exists because a review found what it would have caught.

Two of them ship **strict**, the third with a **baseline**, and the difference is not a
matter of taste. A4b and A12 compare things that either match or do not — a reciprocal
link is present or absent, a recomputed number equals its restatement or does not — so
they have no false positives and both were run by hand until the corpus passed them. A7
greps ten thousand lines of deliberately emphatic prose for words like *enforced*, which
is a judgement, so it needs a baseline or it is switched off by week two.

### 1. The ADR back-link check (A4b)

**Rule.** For every `docs/adr/*.md`: parse the `**Amends:**` / `**Partially
supersedes:**` line and every `> **Amended by [ADR-nnnn]…**` note. Then assert

1. if ADR-A declares it amends ADR-B, **ADR-B carries the reciprocal note**;
2. every chapter section and `REQ-` id named in an `Amends:` line exists;
3. the Status column of [`docs/adr/README.md`](../../docs/adr/README.md) names
   every ADR that amends or supersedes that row.

**Why it is a gate and not a nicety.** Running the rule by hand on 2026-09-11 found
**nine** omissions — five missing reciprocal notes and four index rows that did not name an
amendment their own ADR records. All are fixed, so this gate opens clean instead of with a
backlog. The review of the same day had already found three defects that are one missing
back-link each: `COEP` still listed as a served
header in two places because [ADR-0040](../../docs/adr/0040-no-cross-origin-isolation.md)'s
`Amends:` named four of the six places it reached;
[ADR-0015](../../docs/adr/0015-deployment.md) carrying a topology superseded three
times over with no forward pointer; and
[ADR-0034](../../docs/adr/0034-icon-set-and-no-third-party-hosts.md) describing a
CSP that is not the one in force. The relation is hand-maintained, and a
hand-maintained index is the thing that silently goes stale — which is risk
**R16** stated precisely.

### 2. The restated-fact check (A12)

**Rule.** Read [`docs/reference/tracked-facts.yaml`](../../docs/reference/tracked-facts.yaml)
and assert two things over the scope it declares:

1. **Computed facts.** Each entry carries the expression that derives it from the
   repository and a pattern that finds restatements of it. Recompute; then every capture
   of that pattern in scope must equal the computed value. The patterns name the **unit**
   — `<n> decision records`, not a bare `<n>` — which is what keeps this free of false
   positives
   without a file allowlist, and a file allowlist would be its own drift: the next
   document to restate a fact would not be on it.
2. **Retired literals.** Spellings a decision replaced. `forbidden` entries must not
   appear at all; `named` entries are withdrawn but still named on purpose by the
   documents that record the withdrawal, so each carries the paths where that is the
   point and fails on a **new** path.

`docs/adr/**` is out of scope by construction. An ADR is the record of what was once
decided — `README.md` in that directory says it is never rewritten — so a superseded
spelling inside one is the point rather than a defect. One structural exemption beats
several dozen per-file ones, and it is the exemption that cannot be abused: an ADR states
history, it does not instruct.

**Why it is a gate and not a nicety.** Because every instance below was already in the
repository, and none of them needed judgement to find — only arithmetic that nothing was
doing:

- the two profile memory sums were stated **four times with three different values**, one
  of them the superseded convention that folded three plugins into one total and not the
  other;
- the requirement count read **415** in two files after it was 419;
- the **ADR count** went out on the published front page and in two meta descriptions four
  behind the directory it describes, one day after the difference appeared — the website
  restates the corpus and nothing diffs it;
- a **component count** sat in ADR-0035 and the CHANGELOG and matched nothing countable —
  neither the number of modules nor the number of exports, which differ;
- the printed public code appeared as **`7Q2-M4X-9KD`** in 13 design-system files and 5
  website locations — the spelling ADR-0030 replaced, in the one identifier this product
  prints onto physical labels, while that ADR's own consequence claimed the corpus now
  carried one string "everywhere".

**It ships strict, with no baseline** — and that is the difference from the check below.
A computed fact either equals its restatement or does not; a retired literal is by
definition a string nobody should be writing. Both have **no false positives by
construction**. The rule was run against the corpus before it was written down: 254 files,
zero failures, and three deliberately injected defects — a wrong count, a retired literal,
a withdrawn header on a page not on its allowlist — each caught. A gate that opens with a
backlog teaches people to ignore it.

**What it deliberately does not do** is verify restated *prose*. That a marketing
paragraph still describes the architecture correctly is not mechanically decidable. The
structural answer to that half is a rule rather than a gate: **a secondary artefact links
instead of restating wherever it can**. What cannot be linked — a marketing page needs its
own sentences — is exactly what the numbers and identifiers above cover, because those are
the part of a restatement that goes stale silently.

### 3. The unbacked-claim check (A7)

**Rule.** [14 §14.3](../../docs/architecture/14-quality-risks-glossary.md) already
writes the rule — *"a property with no test is a claim"* — and its own early
warning sign is *"a document says 'enforced' or 'cannot' and no test name appears
next to it."* That is greppable, and nothing greps it.

Scan `docs/**` for hardening language — *enforced*, *cannot*, *must never*,
*fails the build*, *is refused*, *is impossible*, *guaranteed* — and require the
same table cell or paragraph to name a verification: a `REQ-` id, an ADR, or a
named CI check.

**It ships with a baseline**, in the way a lint suppression list does. A bare
regex over 10 000 lines of deliberately emphatic prose would produce noise on day
one and be switched off by week two. The baseline records what is unbacked today;
the gate fails on anything **new**, and the list only shrinks. That makes the
check survivable, which matters more here than making it complete.

## The matrix that matters

The smoke suite runs against **both** container runtimes, rootless, and against
`kind` — and does so once on a Debian-family and once on a RHEL-family
distribution with SELinux `enforcing` (REQ-NFR-064).

A test that only passes rootful proves nothing. A change that works under only
one runtime is a bug that surfaces at an operator's site, on the runtime nobody
tested.

## Workflow hardening

Actions pinned by commit SHA, top-level `permissions: contents: read`, no
`pull_request_target` on code, and no secrets exposed to pull requests from
forks. Release signing keys live in a protected environment, never in the
workflow file.
