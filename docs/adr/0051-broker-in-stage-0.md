# ADR-0051 — RabbitMQ moves to stage 0, because a stage-0 requirement needs the worker

**Status:** Accepted · **Date:** 2026-09-11

**Amends:** [04 Roadmap](../requirements/04-roadmap-and-stages.md),
[04 §4.1](../architecture/04-building-blocks.md),
[`deploy/services.yaml`](../../deploy/services.yaml), `REQ-MED-005`,
`REQ-NFR-009`

## Context

Three statements in the corpus were each defensible and could not all be true at
once:

| Where | What it says |
|---|---|
| `REQ-MED-005`, **stage 0** | *"Images are re-encoded server-side; thumbnails at 200 px, 1024 px and max 4096 px are generated."* Acceptance: **"All derivatives present after upload"** |
| [04 §4.1](../architecture/04-building-blocks.md) | Derivatives are *"generated **asynchronously in the worker**"* |
| [04 Roadmap](../requirements/04-roadmap-and-stages.md) | Stage 0 **does not include** RabbitMQ |

`api` and `worker` are separate processes ([04 §4.1](../architecture/04-building-blocks.md):
*"the same container image with a different role"*). Without a broker there is no
mechanism by which one hands work to the other. The `outbox.event_publication`
table exists from migration `V8` and Spring Modulith's registry is per-process; it
records what a process published, it does not deliver across one.

So stage 0 as specified had a mandatory feature with a named execution site and no
route to it. Left alone, it resolves itself the way such things do: the derivatives
get generated in the request thread, the `worker` ships in the `minimal` profile
with a health check and no work, and [04 §4.1](../architecture/04-building-blocks.md)
becomes a sentence about the future written in the present tense.

## Options

| Option | For | Against |
|---|---|---|
| **Synchronously in the upload request** | No broker · the acceptance criterion is literally true — the derivatives exist when the response is written · matches how the mandatory scan already works | Puts three `libvips` invocations on a request thread already holding a ClamAV round trip, so the upload latency budget is spent twice over · `worker` ships in every profile with nothing to do · [04 §4.1](../architecture/04-building-blocks.md) has to be rewritten to say the opposite of what it says |
| **The worker polls the database** — `SELECT … FOR UPDATE SKIP LOCKED` over media lacking derivatives | No broker · keeps the work in the worker · a well-understood pattern | Builds a second, temporary delivery mechanism that stage 1 immediately replaces, and a queue in a database is the thing a broker is · "present after upload" becomes "present shortly after upload", so the acceptance criterion needs rewording anyway · the poll interval is a latency floor nobody can tune away |
| **RabbitMQ moves to stage 0** | The worker has its designed job from the first day · the outbox path stage 1 depends on is exercised by a stage-0 feature instead of arriving wholesale later · no throwaway mechanism | `minimal` gains a container and its memory · the roadmap's *"not included"* line is amended · the broker's credential, its queue topology and its failure modes all become stage-0 concerns |

## Decision

**RabbitMQ moves into stage 0 and into the `minimal` profile**, and derivative
generation is the first thing that travels over it: `api` writes the media object
and publishes `MediaObjectStored`; `worker` consumes it and produces `thumb`,
`preview` and `full`.

`REQ-MED-005`'s acceptance criterion is restated to match what asynchronous
generation can promise — the derivatives exist after the pipeline completes, and
the media object is not servable until they do. That is not a weakening: a variant
URL is only ever offered for a variant that exists, which is what
`REQ-MED-013`'s `PENDING_SCAN` rule already establishes for the bytes themselves.

## Rationale

The alternative that survives scrutiny longest is the synchronous one, and it is
rejected for a reason that only shows up in the deployment: an upload would then
hold a request thread across a ClamAV round trip **and** three subprocess
invocations, with a 60-second scanner timeout already on the same path
(`REQ-SEC-092`). That is a request whose worst case is minutes, on the profile with
the least hardware, and the visible symptom is a mobile client that appears to hang
while photographing — the flow stage 0 exists to make work.

The database-polling option is rejected on a different ground. It is not wrong; it
is a **second delivery mechanism with a known removal date**, and the cost of
building, testing and then deleting it is close to the cost of the broker it
imitates. What is actually being bought by deferring RabbitMQ is a smaller
`minimal` profile — and that is worth measuring rather than assuming, which is why
the memory sums are recomputed in the same unit of work rather than estimated.

The counterweight is accepted plainly: stage 0 gets larger, and the roadmap's
promise of the smallest possible MVP gets a little less small. It buys the property
the roadmap claims for every stage — *"each is usable and deployable on its own"* —
for the one feature where stage 0 otherwise had a requirement with no mechanism.

## Consequences

- **`rabbitmq` joins the `minimal` profile** in
  [`deploy/services.yaml`](../../deploy/services.yaml). The profile memory
  reservation and limit are **recomputed from the file**, not estimated, and the
  new figures land in [06 §6.7](../architecture/06-deployment-view.md), `REQ-NFR-009`
  and [`tracked-facts.yaml`](../reference/tracked-facts.yaml) together.
- **`REQ-MED-005`'s acceptance criterion is restated**, and the roadmap's *"Not
  included: … RabbitMQ"* line is corrected. This ADR is the prior approval
  `CLAUDE.md` requires for both.
- **The outbox is load-bearing from stage 0.** `outbox.event_publication` stops
  being a table the schema has and nothing writes; `REQ-NFR-013`'s at-least-once
  delivery and the idempotent-consumer rule apply to the derivative worker from the
  first day.
- **The broker's credential is stage-0 work** — it already was:
  [ADR-0044](0044-internal-is-not-a-trust-boundary.md) put `mq-password` on `api`
  and `worker` precisely because retrofitting one onto a running RabbitMQ means a
  queue migration.
- **One more failure mode in the stage-0 runbook**: broker unreachable. The upload
  still succeeds and the media object still stores — the derivative is derived data
  and `CLAUDE.md` rule 10 applies, *derived stores may fail, never lie*. A variant
  that has not been produced is not offered, and the outbox redelivers when the
  broker returns.
- **`worker` gains its first real consumer**, so its health check, its resource
  limits and its network memberships are exercised at stage 0 rather than asserted.
