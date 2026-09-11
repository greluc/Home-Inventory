# ADR-0003 — Multi-tenancy from the start, access by invitation

**Status:** Accepted · **Date:** 2026-09-11

## Context

The instance is reachable from the internet. Users are to be able to create **any
number of tenants** (private, club, workshop, a house move). The software should
be built so that it could be run as a public service; the operator's own instance,
however, admits invited users only.

Retrofitting multi-tenancy means touching every table, every query, every index
and every test. This decision is practically irreversible.

## Options

| Option | For | Against |
|---|---|---|
| **One schema, `tenant_id` everywhere, RLS** | One migration path · one connection pool · straightforward instance-wide reporting · RLS as an independent second line of defence | A mistake in one policy affects everyone · reporting must keep the tenant filter in mind |
| A schema per tenant | Very strong separation, simple single-tenant export | Migrations across n schemas · a connection pool per schema · breaks down at many tenants · awkward Spring/JPA support |
| A database per tenant | The strongest separation, the simplest single-tenant restore | Operationally untenable for a single operator · resource consumption per tenant |
| No multi-tenancy | The simplest model | Contradicts the requirement; retrofitting would be a rewrite |

## Decision

**One schema per building block, `tenant_id` in every domain table, PostgreSQL
row-level security with `FORCE`, the tenant context set through
`SET LOCAL app.tenant_id`.** Registration by invitation
(`HOMEINV_REGISTRATION_MODE=invite_only` by default); an entitled user may create
any number of tenants, bounded by a quota.

## Rationale

RLS is the only way to guarantee tenant separation **independently of the
application logic**. Since foreign plugin code and three API surfaces are in
play, a second line is not a luxury but a precondition for Q1. A forgotten
`WHERE` clause then returns zero rows instead of foreign data.

## Consequences

- The application role has **no** `BYPASSRLS`, **no** table ownership and **no**
  DDL rights. Migrations run under a separate role, and the retention deletions of
  [ADR-0046](0046-truncatable-audit-chain.md) under a third, `homeinv_housekeeping` —
  because `REQ-SEC-069` denies the application `DELETE` on the audit log and something
  still has to enforce a retention period.
- `SET LOCAL` is transaction-scoped; a test ensures no connection returns to the
  pool with a tenant still set.
- An automated isolation proof covers **every** table. A new table without RLS
  fails the build.
- Cross-tenant administration goes through explicit, logged `SECURITY DEFINER`
  functions — never `BYPASSRLS`.
- Quotas (item count, bytes, tenants per user, API calls) exist from the start,
  because with open tenant creation they are abuse protection and do not work as
  a retrofit.
- Restoring a single tenant is more work than with separate databases. The path
  is documented and rehearsed
  ([13 §13.9](../architecture/13-operations-and-observability.md)).
