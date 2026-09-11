# ADR-0010 — REST and read-only GraphQL outward, gRPC for plugins

**Status:** Accepted · **Date:** 2026-09-11

## Context

The API is the contract for the web PWA, the KMP apps, third-party systems and
plugins. What is wanted is REST with OpenAPI, GraphQL in addition, and gRPC for
plugins and internals.

## Options

Considered: REST only · REST + GraphQL (both complete) · REST + read-only
GraphQL · REST outward and gRPC inward.

The real question is not **whether** GraphQL, but whether it may **write**.

| | For | Against |
|---|---|---|
| GraphQL with mutations | One path for everything; clients need only one surface | Authorization, validation, idempotency, concurrency control (`If-Match`), rate limiting and audit would have to be correct **twice**. That is exactly where holes appear: closed on one surface, forgotten on the other. |
| Read-only GraphQL | Halves the authorization surface; the security-critical mechanisms stay in one place | Clients need REST for writes |

## Decision

| Surface | Scope |
|---|---|
| **REST**, OpenAPI 3.1, `/api/v1` | Complete, read **and** write. The binding contract. |
| **GraphQL**, `/graphql` | **Queries only.** No mutations. |
| **gRPC**, `home_inv.plugin.v1` | The plugin contract in both directions; later also internal services |

Plus the load-bearing rule: **authorization and domain rules live exclusively in
the application layer.** The surfaces are pure adapters; an ArchUnit rule forbids
them database and repository access.

## Rationale

Three surfaces are manageable only if they decide nothing. Restricting GraphQL to
reading halves the area over which idempotency, `If-Match`, audit and rate
limiting must be correct — without giving up the benefit, which lies in flexible
reading of complex views anyway.

## Consequences

- GraphQL needs: a depth limit (10), cost analysis **before** execution,
  persisted queries in production, introspection disabled, a `DataLoader` for
  every relation, and a limit on identical aliases.
- Field visibility (`sensitive`) applies identically on both surfaces — verified
  by a shared test.
- Three contract checks in CI: `oasdiff`, GraphQL schema comparison,
  `buf breaking`.
- Generated clients: TypeScript (web) and Kotlin (KMP) from the same OpenAPI
  document. Hand-written clients do not exist.
- The extra effort is real and deliberately accepted (risk R6). The countermeasure
  is structural, not disciplinary: the surfaces **cannot** decide anything.
