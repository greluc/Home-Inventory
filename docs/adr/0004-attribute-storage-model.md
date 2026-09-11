# ADR-0004 — JSONB plus an application-maintained index side table

**Status:** Accepted · **Date:** 2026-09-11

## Context

Item types and location categories have fields **configured at runtime**. What is
wanted is the storage model for the values. It must allow filtering, sorting and
reporting over arbitrary fields, fit the full bidirectional offline sync
([ADR-0014](0014-offline-synchronisation.md)), and stay maintainable over years.

## Options

| Option | For | Against |
|---|---|---|
| **A — JSONB only** | Adding a field is a data row, not DDL · an item is one row · historisation and sync trivial · sparse data costs nothing · GIN for containment | Type safety only in the application · no foreign keys out of attributes · range queries and sorting need an expression index per field |
| **B — JSONB + generated columns (runtime DDL)** | The query performance of real columns | `ADD COLUMN … GENERATED … STORED` forces a table rewrite under `ACCESS EXCLUSIVE` · the app role would need permanent DDL rights (a security problem) · schema drift between instances · a column limit |
| **C — EAV** | Real types, constraints, foreign keys · an index per field without DDL · fine-grained sync merges | A join with pivoting on every read · high storage overhead per value · sync must reconstruct the aggregate from 1+n rows |
| **D — JSONB + an application-maintained index side table** | Index-backed filtering and sorting like B and C · **no** runtime DDL, **no** DDL rights, **no** schema drift · sync stays as in A (one aggregate = one row) · reachable additively from A | Write amplification (n index rows per change) · a rebuild path must exist and be tested · still no foreign keys out of attributes |

## Decision

**Variant D.** `inventory.item.attributes` (JSONB) is the source of truth. A fixed
table `inventory.item_attr_index` mirrors the fields marked `searchable`,
`sortable` or `facetable`, maintained by the application in the same transaction.
Details in [07 §7.3](../architecture/07-data-model.md).

## Rationale

The decisive factor is the **full bidirectional sync**: the unit of replication
should be the *item* aggregate. A self-contained JSONB block makes the change
log, the three-way compare and the wire format considerably simpler than 1+n
scattered rows (C). Variant B is ruled out on security grounds: permanent DDL
rights for the application role substantially enlarge the blast radius of a SQL
injection and therefore run against Q1. D delivers B's performance without B's
price.

## Consequences

- The application **must** maintain the side table correctly. A nightly sampling
  reconciliation reports deviations as a metric; a `REINDEX_ATTRIBUTES` run
  rebuilds it completely — through the same code path as a normal write, so there
  is no second truth.
- Changing a field's searchability triggers a background run, not a migration.
- Attributes cannot reference other tables by foreign key. Reference fields
  (`reference`) store a UUID; the application checks existence, and a nightly run
  reports orphaned references.
- Reporting over attributes goes through OpenSearch aggregations or through
  `item_attr_index`, not through JSONB scans.
- **A soft-deleted item leaves the projection.** `REQ-CORE-009` makes deletion two-stage,
  so `deleted_at` is set long before any row is removed and the side table's
  `ON DELETE CASCADE` never fires. The projection therefore deletes the item's rows when
  `deleted_at` is set and rebuilds them on restore, in the same transaction as the state
  change. The alternative — keeping the rows and filtering on a join back to `item` in
  every query — was rejected: it makes correctness depend on every future query
  remembering, and `REQ-CORE-013` calls this path *transactionally exact*. This was
  unstated until 2026-09-11, and unstated meant trashed items answered attribute filters.
- Staging: stage 0 gets by with A (JSONB only). The step to D is **additive** and
  requires no data migration.
