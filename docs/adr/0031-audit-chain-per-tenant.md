# ADR-0031 — The audit chain runs per tenant and is anchored in time

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [07 §7.8](../architecture/07-data-model.md), `REQ-SEC-070`

## Context

`REQ-SEC-070` and [07 §7.8](../architecture/07-data-model.md) require that every
audit entry carries the hash of its predecessor, so tampering is detectable. The
scope of "predecessor" was never stated, and the concurrency cost was never
examined. Both matter:

- **A chain is a serialisation point.** To compute `prev_hash`, a writer must read
  the current last entry and be certain no one else writes between the read and
  its own insert — a lock. And an audit entry is written in the **same transaction
  as every mutating operation** ([05 §5.1](../architecture/05-runtime-view.md)).
  A single global chain therefore serialises every write in the system against
  every other, across all tenants. With `REQ-CORE-011` (bulk operations up to 500
  entries) and CSV import in the picture, that is the write path's ceiling.
- **A global chain is a side channel.** Sequence positions are visible to whoever
  can read their own entries. Gaps between a tenant's own entries disclose how much
  other tenants are writing, and when. It is minor, and it is avoidable for free.
- **A global chain cannot be verified under RLS.** A tenant's verification run
  would have to read the predecessor, which may belong to another tenant and which
  the policy correctly withholds. Verification would need a `SECURITY DEFINER`
  detour for what should be an ordinary read.

## Options

| Option | Lock scope | Side channel | Verifiable by the tenant |
|---|---|---|---|
| One global chain | every write in the instance | yes | no, needs `SECURITY DEFINER` |
| One chain per tenant | writes of that tenant | no | yes |
| **One chain per tenant + periodic anchor** | writes of that tenant | no | yes, plus a cross-check the tenant cannot forge |
| No chain, rely on `INSERT`-only privileges | none | no | no — privileges protect against the application, not against someone with database access |

## Decision

**One hash chain per tenant, plus an hourly anchor.**

| Element | Rule |
|---|---|
| Chain | `prev_hash` refers to the previous entry **of the same tenant**. The first entry of a tenant chains from a fixed genesis value derived from the tenant ID |
| Lock scope | A per-tenant advisory lock held for the duration of the audit insert only. Tenants never block each other |
| Batching | A transaction that produces several audit entries (a bulk operation, an import) writes them as **one** chained run under a single lock acquisition, not one lock per entry |
| Anchor | Every hour a background task computes a Merkle root over all entries written in that window **across all tenants** and stores it as an `audit.chain_anchor` row — append-only, with its own predecessor hash |
| What the anchor buys | The per-tenant chains prove internal consistency. The anchor ties every tenant's chain to a shared, instance-wide record, so entries cannot be removed from a tenant's chain and re-chained without the anchor disagreeing |
| Verification | Two runs: per tenant (chain intact, readable under the tenant's own RLS context) and instance-wide (anchors intact, every anchor matches a recomputation over its window) |
| Retention | Anchors are never pruned. They are small — one row per hour, ~9 000 rows per year — and pruning them would remove exactly the evidence they exist for |

## Rationale

The chain is there to answer one question after an incident: *has this log been
altered?* A per-tenant chain answers it for the data anyone actually investigates,
and does so without making every write in the system queue behind every other.

The anchor closes the one hole a per-tenant chain leaves. Whoever can rewrite a
tenant's rows can also recompute that tenant's chain — the chain alone only proves
that nobody *careless* touched it. The hourly Merkle root is computed over all
tenants and chained to its own predecessor, so a rewrite would have to alter every
anchor since, which is the property the design wants and which a naive chain does
not actually provide either.

Anchoring to something outside the instance (a transparency log, a timestamping
authority) would be stronger still. It is deliberately **not** done: it would be
an outbound connection, which [ADR-0026](0026-core-outbound-via-plugins.md) has
just removed from the core. If it is ever wanted, it arrives as a plugin.

## Consequences

- **`REQ-SEC-070` is split in two**: the chain and the anchor are separately
  verifiable, so a failure names which one broke.
- **The write path keeps a lock, but a narrow one.** The relevant load test is a
  bulk operation of 500 items within one tenant, which now takes one lock instead
  of 500 — that case is added to the load suite.
- **`audit.chain_anchor` is a new instance-wide table without `tenant_id`**, which
  makes it the third such table alongside `identification.public_code` and
  `identification.label_base_url_usage`. The exception list in
  [07 §7.1](../architecture/07-data-model.md) covers it, and the RLS check reads
  that list rather than treating it as a violation.
- **The hourly anchor run joins the task list** in
  [13 §13.8](../architecture/13-operations-and-observability.md), with a missed
  window as a "look at it next time" alert — a gap in the anchors is not itself
  evidence of tampering, but it is evidence that the evidence is missing.
- **Restoring a single tenant** ([13 §13.9](../architecture/13-operations-and-observability.md))
  now has a defined effect on the chain: the restored chain is intact but its
  anchors will disagree from the restore point onward. The runbook says so and
  records the restore as an audited event, rather than leaving an operator to
  discover a "tampering" alert caused by their own recovery.
