# ADR-0046 — The audit chain is truncatable, and per-tenant retention has a mechanism

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [ADR-0031](0031-audit-chain-per-tenant.md),
[07 §7.6](../architecture/07-data-model.md), [07 §7.8](../architecture/07-data-model.md),
[13 §13.8](../architecture/13-operations-and-observability.md),
`REQ-SEC-069`, `REQ-SEC-070`, `REQ-SEC-096`, `REQ-PRIV-010`

## Context

Three decisions were each sound and could not all be carried out.

1. **Retention is per tenant.** `REQ-PRIV-010` makes `change_log` 30–365 days and the
   audit log 180 days – 5 years, configurable per tenant within those bounds, and
   [13 §13.8](../architecture/13-operations-and-observability.md) lists a daily
   *"enforce per-tenant retention periods"* run.
2. **Pruning is by partition detach.** Both tables are partitioned **monthly and
   instance-wide** ([07 §7.6](../architecture/07-data-model.md),
   [07 §7.8](../architecture/07-data-model.md)), and the same task list prunes
   `change_log` by *"detach partitions beyond the retention period"*.
3. **The audit log is append-only and chained.** `REQ-SEC-069` gives the application role
   only `INSERT` and `SELECT`. `REQ-SEC-070` chains every entry to its predecessor within
   the tenant, `REQ-SEC-096` anchors every hour across all tenants, and
   [ADR-0031](0031-audit-chain-per-tenant.md) states the property plainly: *"removing or
   altering an entry makes the affected anchor and every later one disagree."*

(1) and (2) contradict each other: a monthly partition holds every tenant's rows, so it
can only be detached once the **longest**-retaining tenant's window has elapsed. A tenant
choosing 30 days does not get 30 days; it gets whatever the most conservative tenant on
the instance chose, up to a year.

(1) and (3) contradict each other harder. Enforcing a 180-day audit retention means
deleting rows — which `homeinv_app` cannot do, and which by ADR-0031's own design is
indistinguishable from tampering. The instance would raise its own tampering alert, every
day, as a consequence of honouring a privacy requirement.

Neither collision was written down anywhere. The two runs sat in the same task list with
no stated relationship.

## Options

| Option | Per-tenant retention | Chain still proves something | Cost |
|---|---|---|---|
| **Row deletion by a dedicated role + a truncation marker + marked anchors** | yes | yes, from the truncation point forward | A third database role, one new table, a verification rule |
| Audit is never deleted; retention governs visibility and export only | no — and says so | untouched | Collides with data minimisation ([12 §12.11](../architecture/12-security.md)) and with what a tenant means by "retention period" |
| Partition by tenant, then by time | yes, by detach | untouched | A partition per tenant, created on demand, with open tenant creation ([ADR-0003](0003-multi-tenancy.md)) — hundreds of partitions and a new failure mode at tenant creation |
| Leave it | no | yes | The daily run either does nothing or raises a tampering alert. Both are worse than a decision |

## Decision

### 1. Two mechanisms, and the boundary between them is stated

| Mechanism | Scope | What it removes |
|---|---|---|
| **Partition detach** | instance-wide, monthly | A partition is detached only once **every** tenant's retention has elapsed for its whole range — i.e. at the instance maximum. This is bulk reclamation, not retention enforcement |
| **Row deletion** | per tenant, daily | The retention run deletes that tenant's rows inside live partitions, under `homeinv_housekeeping`. This is what actually enforces `REQ-PRIV-010` |

Stating it this way removes the trap: detach is an optimisation that happens to free
space, and no tenant's guarantee depends on it.

### 2. The chain is truncatable, explicitly

A new append-only table records where each tenant's verifiable chain legitimately begins:

```sql
CREATE TABLE audit.chain_truncation (
    tenant_id      uuid        NOT NULL,
    truncated_at   timestamptz NOT NULL DEFAULT now(),
    oldest_seq     bigint      NOT NULL,   -- the oldest entry still retained
    oldest_hash    bytea       NOT NULL,   -- its entry_hash, the new chain start
    removed_count  bigint      NOT NULL,
    reason         text        NOT NULL,   -- 'retention' | 'tenant-erasure'
    PRIMARY KEY (tenant_id, truncated_at)
);
```

Chain verification for a tenant starts at the newest `chain_truncation` row rather than at
the genesis value, and treats `oldest_hash` as the ancestor of `oldest_seq`. A chain with
no truncation row still verifies to genesis, exactly as before.

### 3. Anchors of pruned windows are marked, never recomputed

`audit.chain_anchor` gains a `pruned boolean NOT NULL DEFAULT false`. When a retention run
removes entries from a window, that window's anchor is marked `pruned` — the row and its
Merkle root stay, and stay chained to their neighbours, but the verification run stops
expecting a recomputation over a window whose contents are gone.

**What is lost is stated rather than glossed:** a pruned window's anchor no longer proves
what was in it, only that an anchor was computed and that it has not been altered since.
What still holds, and is the property worth having, is that the **anchor chain itself** is
unbroken and instance-wide, so a rewrite of history still has to alter every anchor since —
including the pruned ones, which is exactly as hard as before.

### 4. Erasure uses the same mechanism

Tenant erasure ([05 §5.9](../architecture/05-runtime-view.md)) already leaves a
pseudonymised audit entry about the erasure itself. It now also writes a
`chain_truncation` row with `reason = 'tenant-erasure'`, so that the tenant's removal from
the chain is recorded by the same mechanism that records a routine truncation. One
mechanism, two reasons.

## Rationale

The chain exists to answer *"has this log been altered?"*. A truncation marker does not
weaken that answer; it makes the question well-posed, by separating *"entries are missing
and we recorded why"* from *"entries are missing"*. Without the marker, honouring a
retention period and being attacked produce the same evidence — which is the worse of the
two failure modes, because it trains the operator to ignore the alert.

Row deletion rather than tenant partitioning is chosen because the alternative creates a
partition per tenant on a system that lets an entitled user create any number of them
([ADR-0003](0003-multi-tenancy.md)), and turns tenant creation into a DDL operation — under
a role no long-running service is allowed to hold ([ADR-0041](0041-migration-as-its-own-service.md)).

The third role is the unavoidable part. `REQ-SEC-069` is right that the application must
not be able to delete audit rows, and something must. `homeinv_housekeeping` has `DELETE`
on exactly the retention-governed tables, no DDL, no `BYPASSRLS`, and is used by the
housekeeping runs alone.

## Consequences

- **A third database role**, `homeinv_housekeeping`, in
  [07 §7.5](../architecture/07-data-model.md) beside `homeinv_app`, `homeinv_migrator` and
  `homeinv_readonly`. `REQ-SEC-002`'s privilege check covers it: no `BYPASSRLS`, no DDL,
  no ownership.
- **A new table**, `audit.chain_truncation`, append-only, and on rule 4's closed list in
  [07 §7.1](../architecture/07-data-model.md) as a record of an event — no `version`, no
  audit columns.
- **`audit.chain_anchor` gains `pruned`.** It stays instance-wide and stays on rule 2's
  closed list.
- **`REQ-SEC-070` and `REQ-SEC-096` gain the truncation case**, and the verification run
  gains a third outcome beside intact and broken: *truncated, with a recorded marker*.
- **`REQ-PRIV-010` gains a mechanism** and stops being a requirement with a task list and
  no way to carry it out.
- **[13 §13.8](../architecture/13-operations-and-observability.md) says which run does
  what**, so "prune `change_log`" and "enforce per-tenant retention periods" are no longer
  two entries that appear to do the same thing.
- **A tenant's chain verification reads one more row.** That is the whole runtime cost.
