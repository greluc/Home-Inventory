# ADR-0009 — RabbitMQ with a transactional outbox

**Status:** Accepted · **Date:** 2026-09-11

## Context

Building blocks should be decoupled through events, background work (image
derivatives, indexing, enrichment, notifications) should happen outside the
request path, and out-of-process plugins should be able to receive events. No
event may be lost, and none may exist without the corresponding data change.

## Options

| Option | For | Against |
|---|---|---|
| Spring Modulith outbox only, in-process dispatch | No additional service, transactionally safe | No delivery to external consumers; no scaling across process boundaries |
| Redis/Valkey streams | No further service if Valkey runs anyway | Weaker delivery guarantees, less convenient error handling and dead-lettering |
| NATS JetStream | Very lightweight, replay built in | Less widespread, weaker routing for fan-out to many plugins |
| **RabbitMQ** | Mature routing (topic exchanges), dead-lettering, delayed retry, a management UI, quorum queues, client libraries in many languages for plugin authors | ~250 MB RAM, Erlang to operate, its own version maintenance |

## Decision

**RabbitMQ** (quorum queues) for delivery, with a **transactional outbox in
PostgreSQL** as the source. A relay publishes incomplete outbox entries and marks
them done only after the broker acknowledges.

## Rationale

RabbitMQ **cannot** take part in the database transaction. Without an outbox
there would be exactly two failure modes, both unacceptable: a data change
without an event (the search index misses it forever) or an event without a data
change (consumers work on phantoms). The outbox solves this without a distributed
transaction. RabbitMQ's routing fits the fan-out to many plugins with differing
interests.

## Consequences

- **At-least-once delivery.** Every consumer is idempotent, deduplicating on the
  event ID. This is not negotiable and is tested.
- The outbox backlog is **the most important operational metric** — when it backs
  up, every derived store goes stale.
- Topology: one topic exchange per building block, routing key
  `<block>.<event>.<version>`; one quorum queue per consumer with a dead-letter
  queue and retry with growing delay.
- Events carry a CloudEvents 1.0 envelope with `tenantId`, `traceId` and the
  schema version. Event schemas are checked for breaking changes in CI.
- Events contain **no** `sensitive` fields and no binary data — only references.
- A RabbitMQ outage affects **no** read or write operation; only derived data
  goes stale until the broker returns.
- Valkey stays responsible for cache, sessions and rate limiting, not for events
  — separate jobs, separate failure consequences.
- **Nor for idempotency records** (*decided 2026-09-11, open point O13*). They were
  filed under Valkey with a 24-hour TTL, while
  [13 §13.6](../architecture/13-operations-and-observability.md) rated a Valkey
  outage as harmless — "sessions invalid, re-login". Both could not be true: losing
  the records means a retry after a network drop creates a duplicate, which is the
  exact failure `REQ-API-005` exists to prevent, for the exact clients it was
  written for.

  The `Idempotency-Key` and its payload hash are therefore written to **PostgreSQL,
  in the same transaction as the record they protect**. That is the same argument
  the outbox rests on, applied to the same problem: two stores cannot be made
  consistent by hoping, and here they need not be — the key and the entity share one
  `COMMIT`, so there is no window in which one exists without the other. Valkey may
  still cache the lookup; it is never the source.
