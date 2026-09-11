# ADR-0017 — Spring Data JPA for aggregates, JdbcClient for dynamic queries

**Status:** Accepted · **Date:** 2026-09-11

## Context

Data access has two very different faces: cleanly bounded aggregates with
invariants (`Item`, `Location`), and dynamically assembled queries over arbitrary
attributes (`item_attr_index`), tree operations (`ltree`) and reporting.

## Options

| Option | For | Against |
|---|---|---|
| JPA/Hibernate only | One tool, aggregates and lifecycle well modelled, JSONB through `@JdbcTypeCode(SqlTypes.JSON)` | Dynamic queries through the Criteria API are hard to read; `ltree` and JSONB operators need native SQL anyway |
| jOOQ only | Type-safe SQL, excellent for dynamic queries | No aggregate lifecycle; more manual work on the write path; code generation in the build |
| JPA **and** jOOQ | Both strengths | Two query models, two mental models, two caches — too much for a single developer |
| **JPA + `JdbcClient`** | JPA for the write path and aggregates; `JdbcClient` (Spring 6.1+) for dynamic queries with ordinary SQL | No compile-time type safety on the query path |

## Decision

**Spring Data JPA with Hibernate 7** for aggregates and everything that writes;
**`JdbcClient` with hand-written SQL** for dynamic queries, tree operations and
reporting.

## Rationale

The missing compile-time type safety on the query path is offset by integration
tests against a real PostgreSQL instance (Testcontainers) — which are needed for
`ltree` and JSONB in any case. Two full-blown persistence tools side by side
would by contrast be permanent extra work.

## Consequences

- **Parameterised queries only.** Dynamic SQL is built exclusively through a
  checked builder whose field and sort names come from an **allowlist** (derived
  from `field_definition`), never from user input. An ArchUnit rule forbids string
  concatenation in SQL.
- Entities never leave their building block. Only `*View` types go outward — that
  prevents accidental lazy loading outside the transaction and the leaking of
  internal fields.
- Hibernate's second-level cache stays **off**. With RLS and tenant-bound data it
  is a source of bugs; caching happens deliberately in Valkey with a
  tenant-scoped key.
- `open-in-view` is off.
- Every repository method has an integration test against a real database; H2 or
  other substitute databases are **not** used — they behave differently from
  production on JSONB, `ltree` and RLS.
- Migrations: Flyway. Hibernate never generates schema (`ddl-auto: validate`).
