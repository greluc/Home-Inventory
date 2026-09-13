# ADR-0060 — A tenant erasure is one ordered pass in the worker, not a choreography

**Status:** Accepted · **Date:** 2026-09-13

## Context

[05 §5.9](../architecture/05-runtime-view.md) drew the second half of an erasure
as a choreography: once the grace period elapses, `tenancy` publishes
`TenantDeletionConfirmed` on RabbitMQ, every block consumes it and deletes its
share, each reports completion back, and the certificate is written when all of
them have.

`REQ-TEN-011`'s acceptance is blunt — *"an erasure certificate is produced"* —
and `REQ-PRIV-005` is Article 17: the erasure has to be complete and it has to be
evidenced.

A choreography has a middle. "Three of seven blocks have reported" is a state the
system can sit in indefinitely: a consumer that was down, a message that was
dropped, a block deployed a release later. Nothing in that model says which state
a tenant is in, and the tenant is by then already inaccessible, so nobody is
looking. It also needs machinery that only exists for it — a completion table, a
per-block idempotency key, a reconciliation for reports that never arrive — for
an operation that takes seconds and happens once in a tenant's life.

The blocks are not independent of each other either, which the first
implementation discovered the hard way: an item references a place and a type
version, a membership references the location a member is confined to and the
role definition they hold. Seven consumers reacting to one message in whatever
order they happen to be scheduled is seven foreign keys deciding whether the
erasure completes.

## Decision

**The erasure is one synchronous pass in the `worker`, hourly, block by block.**

- **Each block declares where it sits.** `TenantErasure.order()` is the position,
  and every implementation names the foreign key that fixes it: `media`,
  `tagging`, `inventory`, `tenancy`, `locations`, `catalog`, `authorization`,
  `audit`. Two blocks claiming the same position fail startup rather than
  producing an order that depends on the classpath scan.
- **Each block runs in its own transaction, inside the tenant's own context.**
  The policies scope every delete, so a `WHERE` clause that was wrong removes
  nothing rather than somebody else's rows — the second line of
  [ADR-0003](0003-multi-tenancy.md), on the one operation where it matters most.
  A block that fails leaves the blocks before it done rather than rolling back
  the whole run.
- **Nothing marks the tenant finished until every block has reported.** The
  tenant row is tombstoned as the *last* write of the run, and
  `tenancy.tenants_due_for_erasure` skips a tombstoned row. A run interrupted
  halfway is therefore still due, and the next sweep resumes it: a block with
  nothing left to remove reports zero rather than failing.
- **The evidence outlives its subject.** `tenancy.erasure_certificate` is
  instance-wide — the sixth entry on the closed list in
  [07 §7.1](../architecture/07-data-model.md) — because a tenant-scoped
  certificate would be removed by the run that writes it. It is written once,
  never updated, and read through `/api/v1/instance/erasures`.
- **The audit log stays**, and the certificate says so in as many words.
  `REQ-SEC-069` gives the application `INSERT` and `SELECT` on it and nothing
  more; the log is removed by the retention run under `homeinv_housekeeping`
  within the tenant's own retention period (`REQ-PRIV-010`). A block that
  quietly skipped it would leave a certificate implying the log had gone.

The port `TenantErasure` lives in `platform`, not in `tenancy`. Eight blocks
implement it and one of them is `authorization`, which `tenancy` already depends
on for what a role may grant — a port owned by `tenancy` closed a cycle between
the two the moment `RoleErasure` implemented it. The shared kernel's rule is that
a type goes in when several blocks need it and it holds no decision
([04 §4.3](../architecture/04-building-blocks.md)); this is an interface and a
record, and the decision about what an erasure does stays with the runner.

## Consequences

- **05 §5.9's diagram is corrected rather than annotated.** It described a design
  that was never built; leaving it would be a document that reads as
  authoritative and is not.
- **The `worker` carries the run, not `api`.** An erasure walks eight schemas and
  a request has a thirty-second ceiling (`REQ-SEC-065`). It is `@Profile("worker")`
  for a second reason too: two processes sweeping would pick up the same tenant
  and erase it twice in parallel, and while the certificate insert is idempotent,
  the hour spent is not.
- **A failure is per tenant, not per run.** One tenant whose erasure throws is
  logged and the sweep continues; the next one resumes it from wherever it
  stopped.
- **Blobs are not removed here.** They live behind a port and are reclaimed by the
  orphan sweep of [13 §13.8](../architecture/13-operations-and-observability.md),
  which already removes what no reference points at, with a grace period. Deleting
  them inside the transaction would mean a rollback leaving rows that point at
  bytes that are gone — the one direction this must not fail in.
- **A block added later has to declare a position**, and a reviewer has to think
  about which foreign keys it is on the wrong side of. That is friction, and it is
  the friction that replaces a reconciliation nobody would have written.
- **The broker is not involved in an erasure at all.** `TenantDeletionConfirmed`
  is not published, and no block consumes it. What the broker still carries is
  everything 13 §13.8 and [ADR-0009](0009-messaging-and-events.md) say it does.
