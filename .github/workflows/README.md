# .github/workflows/

CI and release workflows.

## Status

One workflow exists: [`pages.yml`](pages.yml), which publishes the project
website. It is here ahead of the others because it has something to do that does
not need a build — and because it carries a real gate: it greps the assembled
site for resources loaded from another host and fails, which is REQ-PRIV-015
enforced rather than asserted. It stays inert until `website/index.html` exists.

Everything else is still to come — there is no code to build yet. This file
records what those workflows will have to do, so that the gates are not invented
ad hoc later.

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
| **Headers** | The served CSP compared against an expected string |
| **Licensing** | No AGPL-incompatible dependency in the core; only permissive ones in the plugin API |
| **DCO + CLA** | Every commit signed off; the contributor has signed |
| **ADR back-links** | Every ADR named in another's `Amends:` carries the reciprocal note — see below |
| **Unbacked claims** | A hardening claim with no named verification next to it — see below |

## Two checks over the documentation, and why they are gates

Both run on Markdown alone. They need no build, no container and no code, so
they are the **first** two workflows this directory gets — they are the only
gates that can run today, and each one exists because a review found what it
would have caught.

### 1. The ADR back-link check (A4b)

**Rule.** For every `docs/adr/*.md`: parse the `**Amends:**` / `**Partially
supersedes:**` line and every `> **Amended by [ADR-nnnn]…**` note. Then assert

1. if ADR-A declares it amends ADR-B, **ADR-B carries the reciprocal note**;
2. every chapter section and `REQ-` id named in an `Amends:` line exists;
3. the Status column of [`docs/adr/README.md`](../../docs/adr/README.md) names
   every ADR that amends or supersedes that row.

**Why it is a gate and not a nicety.** The review of 2026-09-11 found three
defects that are one missing back-link each: `COEP` still listed as a served
header in two places because [ADR-0040](../../docs/adr/0040-no-cross-origin-isolation.md)'s
`Amends:` named four of the six places it reached;
[ADR-0015](../../docs/adr/0015-deployment.md) carrying a topology superseded three
times over with no forward pointer; and
[ADR-0034](../../docs/adr/0034-icon-set-and-no-third-party-hosts.md) describing a
CSP that is not the one in force. The relation is hand-maintained, and a
hand-maintained index is the thing that silently goes stale — which is risk
**R16** stated precisely.

### 2. The unbacked-claim check (A7)

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
