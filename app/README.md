# app/

The Spring Boot application — the whole server side of Home Inventory.

**One artifact, two roles.** `api` and `worker` are the same container image with
a different Spring profile. That keeps the version matrix at one and makes it
impossible for the two to drift apart
([04 Building Blocks](../docs/architecture/04-building-blocks.md)).

## The layout inside

This is a **modular monolith**, so the package structure is the architecture. The
cut follows the domain, not the technology — there are no global
controller/service/repository layers, but 18 building blocks each with its own
internal layering:

```
de.greluc.homeinv.<block>/
├── api/              published — the only part other blocks may look at
├── domain/           internal — aggregates, invariants, no framework imports
├── application/      internal — use cases, transaction boundary, authorization
└── infrastructure/   internal — JPA, event dispatch, ports to other blocks
```

The blocks: `platform`, `identity`, `tenancy`, `authorization`, `catalog`,
`inventory`, `locations`, `tagging`, `media`, `identification`, `labeling`,
`enrichment`, `search`, `sync`, `notification`, `portability`, `audit`,
`plugins` — plus the access layer (`rest`, `graphql`, `grpc`, `events-stream`),
which contains no domain logic and makes no authorization decision.

## The rules CI enforces here

These are not style preferences. Each fails the build:

1. `domain` imports nothing from `application`, `infrastructure` or Spring
2. A block imports from another only out of its `api` package
3. No block touches another block's database schema
4. No cycle between blocks
5. Entities never leave their block — only `*View` types go outward
6. Every endpoint has a permission check, or an explicit `@PublicEndpoint` marker
7. Every new table carries `tenant_id` and RLS with `FORCE`

Spring Modulith and ArchUnit do the checking. Without them a modular monolith
decays into a large ball of mud within a year — that is the single biggest risk
to this design (R3), and mechanical verification is the only countermeasure that
has ever worked.

## Status

Empty. Stage 0 starts here:
[the roadmap](../docs/requirements/04-roadmap-and-stages.md).
