# ADR-0049 — The OpenAPI document is generated from the implementation, and a drift check makes it true

**Status:** Accepted · **Date:** 2026-09-11

**Amends:** `REQ-API-001`, [`api/README.md`](../../api/README.md),
[08 §8.1](../architecture/08-api-contract.md)

## Context

The project committed itself to **specification-first** in the design phase:

> The specification is written first; server stubs, the TypeScript client for the
> web PWA, the Kotlin client for the apps, and the documentation are **generated**
> from it. Hand-written clients do not exist in this project.
> — [`api/README.md`](../../api/README.md), before this decision

`REQ-API-001` carried the same promise as its acceptance criterion (*"server stubs
and clients are generated, not written"*), and it is a **stage-0** requirement.

Stage 0 is now implemented. What actually exists is six endpoints written by hand,
a TypeScript client written by hand, and **no OpenAPI document at all** — `api/`
holds a README describing a pipeline nobody built. That is the state every
specification-first intention reaches when the specification is not on the path to
a running program: it is the artefact with no consumer, so it is the artefact that
is skipped, and then the one that is wrong.

The reason is worth naming rather than glossing, because it decides which way this
goes. Specification-first earns its cost when **several parties agree a contract
before any of them implements it** — a client team and a server team, or a
published contract with external authors. This repository has neither at stage 0:
one person writes both sides, and the external contract that genuinely needs
agreeing ahead of its implementations is the **plugin** contract in
[`proto/`](../../proto/), which is spec-first and stays that way
([ADR-0011](0011-api-versioning.md), [ADR-0028](0028-plugin-runtime-stage-1.md)).

What specification-first buys here is therefore a **second hand-maintained copy of
one fact**, and this corpus has already written down what happens to those:
[`docs/reference/tracked-facts.yaml`](../reference/tracked-facts.yaml) exists
because five of them had diverged by the time anybody counted.

Meanwhile the **gates** listed in `api/README.md` are the part with the value, and
not one of them depends on who wrote the document:

| Gate | Needs a hand-written document? |
|---|---|
| The document is valid and lints against a project ruleset (`spectral`) | no |
| No unannounced breaking change (`oasdiff` against the last release) | no |
| Every `problem.type` a response emits is in the registry | no |
| The examples validate against their schemas | no |
| The implementation matches the document | **it is the document** |

## Options

| Option | For | Against |
|---|---|---|
| **Specification-first as written** — hand-write `openapi.yaml`, generate Spring interfaces and the TypeScript client | The contract is reviewable before it is implemented · the document cannot be shaped by an accident of Jackson's defaults | The document is a second source for one fact, with a person as the synchronisation mechanism · every endpoint gains a generated interface layer whose only purpose is to prove the two agree · it is what was promised and, after a full stage, not what was done |
| Specification-first for the server only, client hand-written until stage 1 | Half the cost of the above | Keeps the expensive half. The drift risk is in the server contract, not in the client |
| **Code-first with `springdoc`, plus a drift check** | The document has no independent existence to drift into · every gate above still runs, on a document that is by construction what the server does · one artefact, one review | The contract can no longer be reviewed before it is implemented · the document's shape follows the Java types unless annotations say otherwise · `oasdiff` now reports breaks *after* they are written rather than before |

## Decision

**`springdoc-openapi` generates `api/openapi.yaml` from the running application. The
file is committed, and CI regenerates it and compares.**

That is the same mechanism this repository already uses twice, and for the same
reason both times:

| Generated, committed, drift-checked | From | Check |
|---|---|---|
| `deploy/quadlet/*`, `deploy/compose/compose.yaml` | `deploy/services.yaml` | `python deploy/generate.py --check` |
| `web/nginx/default.conf` | the built web bundle | `npm run csp:check` |
| **`api/openapi.yaml`** | **the application** | **`./gradlew openApiCheck`** |

A committed generated file is what makes the contract **reviewable in a pull
request** — the half of specification-first worth keeping. A reviewer sees the
contract change as a diff, next to the code that caused it, and a change nobody
intended shows up as a diff nobody expected.

`spectral` keeps its place and gains importance: with no author to enforce naming,
status codes and response shapes by hand, the ruleset is where those conventions
now live.

## Rationale

The promise that was broken here was not *"the document is written by a human"*. It
was **"the document and the implementation agree"**, and specification-first was one
way of getting there — the way that works when the two are produced by different
people. Generation plus a drift check is the other way, and it is the one whose
failure mode is a **red build** rather than a document that quietly stops being
true.

The honest cost is design review. Under specification-first, a badly shaped
endpoint is caught in the document before it is built; here it is caught in the
diff after. At stage 0, with one author and no published contract, that trade is
affordable. It stops being affordable when the contract is published — and by then
the surface is `home_inv.plugin.v1`, which is protobuf, spec-first, and governed by
[ADR-0011](0011-api-versioning.md)'s six-month rule.

## Consequences

- **`REQ-API-001`'s acceptance criterion changes**, and this ADR is the prior
  approval that `CLAUDE.md` requires before a requirement may be contradicted. The
  requirement is amended in the same unit of work, not afterwards.
- **`api/README.md` is rewritten.** It described a pipeline that does not exist and
  a rule (*"hand-written clients do not exist in this project"*) that stage 0
  breaks in the same repository.
- **The TypeScript client stays hand-written through stage 0.** `REQ-API-002` is a
  stage-1 requirement and generates the TypeScript and Kotlin clients from this
  document together; that is when `web/src/api.ts` goes. It is marked as
  provisional where it lives.
- **`api/openapi.yaml` is a build output that is committed.** Editing it by hand
  fails the drift check, exactly like editing `compose.yaml` or
  `web/nginx/default.conf`.
- **One more CI job**, and one more reason the build needs a database: the document
  is generated from a started application context.
- **`proto/` is untouched.** The plugin contract is written first and published; the
  distinction this ADR draws is between an internal contract with one author and an
  external contract with many, and it is drawn deliberately rather than by omission.
