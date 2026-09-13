# ADR-0056 — Attributes are validated with the shipped schema document

**Status:** Accepted · **Date:** 2026-09-13

## Context

[ADR-0020](0020-configuration-as-data.md) makes every type version generate a
**JSON Schema (draft 2020-12)**, and `REQ-CORE-027` states its acceptance as:

> A client validates offline with the same result as the server.

The server therefore has to decide what "the same result" means in code. Two
readings were available:

1. **Validate the document.** Load the generated schema and run a JSON Schema
   implementation over the attribute set — the same bytes the client receives.
2. **Validate the definitions.** Walk `catalog.field_definition` in Java and
   check each value against its declared type and constraints, generating the
   schema separately for clients.

The second is dependency-free and the rules are a closed list of sixteen data
types. It is also two implementations of one rule set, in two languages, that
have to agree for the acceptance criterion to hold — and they would be checked
against each other by nothing.

## Decision

**The server validates against the generated document**, with
[`com.networknt:json-schema-validator`](https://github.com/networknt/json-schema-validator)
(Apache-2.0), pinned in the version catalogue.

**`SchemaValidatorsConfig` forbids resolving a `$ref` that is not already in
hand.** The library can fetch a remote schema over HTTP, and `api` and `worker`
have no outbound route to anything — [ADR-0026](0026-core-outbound-via-plugins.md)
and rule 5 of `CLAUDE.md` — so that ability is switched off rather than left to
the fact that our generated schemas happen not to use one. A tenant's type
definition reaches this code as data; a `$ref` in it must not become a socket.

This is the same test [ADR-0025](0025-money-representation.md) applied to
JavaMoney and failed it on: a library that schedules a network fetch of its own
accord does not belong in the core. The difference is that here the behaviour is
configurable, and the configuration is part of this decision rather than a
detail below it.

Validation beyond the schema — that a `reference` points at a row of this tenant,
that a `quantity` unit is one the field declares, that a `secret` is writable by
this caller — stays in the application: 07 §7.3 calls those "domain rules JSON
Schema cannot express", and they are not expressible in any implementation.
`REQ-CORE-005` is satisfied by both halves together, and the half that is a
schema is the half this ADR is about.

## Consequences

- The generated schema is **load-bearing**, not documentation. A generator bug is
  a validation bug in the same commit, which is the point: the two cannot drift.
  `CatalogSchemaTest` is written that way deliberately — every case generates a
  document and then asks the validator what it makes of a value, so neither half
  can be right on its own.
- `catalog` gains one third-party runtime dependency. It is Apache-2.0, which
  the core's AGPL-3.0-or-later permits, and it is on the licence allowlist that
  CI checks.
- Clients pick their own implementation. `web` uses one in TypeScript and the
  Kotlin Multiplatform apps one in Kotlin; all three read the same document, and
  none of them re-implements the rules.
- A schema that fails to compile is a **refusal to publish the version**, not a
  runtime surprise: the generator's output is validated as a schema at publish
  time, so a tenant cannot save a type whose schema nothing can load.
- The 64 KiB attribute-set limit and the field-key pattern stay in the database
  `CHECK` (07 §7.3, validation place three). They are not the schema's job: they
  hold for a write that never reaches the application.
