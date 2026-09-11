# ADR-0008 — OpenSearch as a derived read model

**Status:** Accepted · **Date:** 2026-09-11

## Context

Search has to cover names, descriptions, notes, attribute values, tags and
location paths, with facets over type, category, tag, location subtree and value
ranges. Target size: 1 M items per tenant, p95 under 500 ms. Target environment:
a Proxmox VM with ≥ 8 GB RAM.

## Options

| Option | For | Against |
|---|---|---|
| PostgreSQL FTS + `pg_trgm` | No additional service, transactionally consistent with the data, one backup | Facets are awkward, typo tolerance is weaker, relevance ranking is limited |
| Meilisearch | Very good typo tolerance and instant search, ~100 MB RAM | Weaker at complex aggregations; a smaller ecosystem |
| **OpenSearch** | The most capable queries and aggregations, a good basis for later reporting, Apache-2.0 | ≥ 1.5 GB RAM, a JVM to operate, its own version maintenance, a second point of failure |

## Decision

**OpenSearch** as the primary `SearchIndex` adapter, with **PostgreSQL FTS as a
shipped fallback adapter**. The index is a **derived read model**; hits are always
re-loaded from PostgreSQL.

## Rationale

The more capable option was chosen deliberately, because the target environment
has the resources and because the faceting and aggregation capabilities will also
carry later reporting (value per room, age distribution, expiry overviews). The
port keeps the decision reversible and makes the `minimal` profile possible
without OpenSearch.

## Why "derived, not authoritative"

Three reasons that together shape the architecture:

1. **Security.** The index returns IDs only. Re-loading from PostgreSQL passes
   through RLS and field visibility — a stale or wrongly filtered index cannot
   breach a tenant boundary and cannot expose `sensitive` fields.
2. **Correctness.** A stale index can at most show one hit too many or too few,
   never wrong content.
3. **Availability.** If OpenSearch fails, the PostgreSQL adapter takes over; the
   response carries `Warning: 199` and the UI shows the degraded state.

## Consequences

- OpenSearch is **not backed up** — it is fully rebuildable from PostgreSQL. The
  rebuild is rehearsed quarterly.
- Index lag (target p95 < 2 s) is a monitored metric.
- Immediately after a write the detail view always reads from PostgreSQL — the
  user sees their own change at once.
- Index schema changes go through a new index with an alias switch, without a
  search outage.
- Tenant separation in the index is additionally enforced by a mandatory
  `tenantId` filter on every query, verified by a test.
- The resource footprint is the largest single item in the stack. The `minimal`
  profile without OpenSearch stays runnable and is tested in CI (risk R7).
