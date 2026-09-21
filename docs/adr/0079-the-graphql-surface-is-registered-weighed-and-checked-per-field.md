<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0079 — The GraphQL surface is registered, weighed, and checked field by field

**Status:** Accepted · **Date:** 2026-09-21

**Relates to:** `REQ-API-006`, [ADR-0010](0010-api-surfaces.md) (GraphQL is read-only),
[08 §8.4](../architecture/08-api-contract.md)

## Context

[08 §8.4](../architecture/08-api-contract.md) specified the surface in 2026-09 down to a
schema sketch and a table of seven safeguards, and three of them named a property without
naming a mechanism. Each of the three is the whole difference between a safeguard and a
line in a table.

1. **"In production only registered queries."** Where the register lives decides what the
   safeguard is worth.
2. **"Cost analysis: field costs declared."** Declared where, and by whom.
3. **"Field visibility: the same rule as in REST."** REST checks one endpoint per request.
   A GraphQL request is as many resolvers as the client asked for, each reaching a
   different block — so what does a caller get when they lack the permission for **one**
   field of a query?

## Decisions

### 1. The register comes from the build, beside the client

`tools/persisted_queries.py` reads every `.graphql` document under `web/src`, hashes it
with SHA-256 and writes the list into the application's resources. "Only registered
queries" therefore means "the queries this release ships with", and **there is no
registration surface at run time**.

| Rejected | Why |
|---|---|
| A registration endpoint | Whoever may query may then register, which is the safeguard removing itself |
| Automatic persisted queries (the Apollo pattern) | The same thing wearing a hash: the first request carries the query and the server remembers it |
| A table per tenant | A table, a permission and a screen, for a list that changes when the client changes and at no other time |

Free-form queries — and introspection, which is the same permission in a different shape —
are for **administrators**, as 08 §8.4 says, configurable to `NEVER` or, for development,
`ANYONE`. Introspection is refused per **request** rather than by hiding the fields from
the schema: graphql-java's field visibility is schema-wide, and this has to say yes to one
caller and no to the next. `GraphQlGuardIT` drives all three — a registered query answered,
an unregistered one refused whoever asks, and introspection refused — against the settings
a deployment uses rather than the ones the tests are comfortable with.

*Today the list is empty, because `web/` speaks REST. That is not a gap left open: the
surface answers administrators until a client with queries in it exists, and `QueryGuard`
says so at startup rather than failing quietly.*

### 2. A weight is declared in the schema, beside the field

`directive @cost(weight: Int!)`, applied to a field definition, read by a
`FieldComplexityCalculator` before execution. A field that declares none costs one, and a
field taking `first` multiplies its children by the rows asked for.

The alternative was graphql-java's built-in formula, where every field costs one. It needs
nothing declared and says nothing either: a field that runs a full-text search costs what
an `id` costs, and the budget stops being about load. A weight in the SDL is what 08 §8.4
means by *declared*, and it is visible to anybody holding the schema.

*The formula is `weight + children × rows`, not `(weight + children) × rows`. The second
was written first and made an ordinary two-level query cost 13 100 against a budget of
5 000 — a limit that refuses the normal case is a limit somebody raises until it refuses
nothing.*

### 3. A missing permission costs a field, not the query

Every resolver declares what it needs with the same `@RequiresPermission` a REST endpoint
carries; `GraphQlPermissions` enforces it and `ArchitectureRulesTest` refuses a resolver
without one. A caller lacking the permission for one field gets `null` **for that field**
and an entry in `errors`, and the rest of the query answers.

Refusing the whole query was the alternative and is worse in the way that matters: a member
asking for items and, in passing, their history would get nothing rather than the items, so
a client could only ever ask for what it was certain of — which turns a query language back
into a fixed set of endpoints.

The id in an error is not a disclosure: a resolver refuses before it reads, and what a
caller learns is that the field exists, which the schema already told them.

## Consequences

- **The depth limit cannot currently be reached.** Nothing in the schema recurses — no
  `Location.children`, no `Item.relatedItem` — so ten levels is unreachable by a valid
  query. The limit stays, because the schema grows, and `GraphQlGuardIT` lowers it to three
  to prove the mechanism instead of building a query nobody can write.
- **`filter` is the REST filter grammar**, a list of strings, not the `ItemFilter` input
  object the sketch drew. A structured input would be a second grammar for one concept,
  parsed by a second parser, diverging on the first operator added to only one of them.
- **`ItemType` has no `name`.** What a person sees is a multilingual label and therefore
  tenant data, exactly as a field's label is.
- **Two nested fields are batched and four are not**, and the reason is the same one that
  makes this surface safe: `tags`, `photos`, `relations` and `history` each carry a
  per-target visibility check in the block that owns them, and a batch read that skipped it
  would move a visibility decision into the adapter, which ADR-0010 forbids. They are
  bounded by the cost budget instead, and a batch read that keeps the check is the
  improvement to make.
- **Thread-locals now travel with the work.** A `DataLoader` dispatch does not run on the
  request's thread, and `CallerContext.require()` threw on a request that plainly had a
  caller. `platform.ContextPropagation` registers an accessor per context with
  `io.micrometer:context-propagation`, which Reactor and Spring for GraphQL already use.
  Neither context gained a public setter.
