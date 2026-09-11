# ADR-0002 — A modular monolith instead of microservices

**Status:** Accepted · **Date:** 2026-09-11

## Context

High modularity, clear interfaces and later extensibility through fixed
interfaces are required. The question of microservices was raised explicitly. The
full assessment with all criteria is in
[03 Solution Strategy](../architecture/03-solution-strategy.md).

## Options

| Option | For | Against |
|---|---|---|
| **Modular monolith with a plugin system** | One transaction for the interwoven core · the lowest operational effort · startable locally in minutes · the smallest attack surface · modularity through machine verification instead of network boundaries | Requires discipline; boundaries decay without verification · all blocks fail together |
| Hybrid: core plus a few separate services | Hard isolation of the compute-heavy parts | Distributed flows without a current reason; multiplied operational effort |
| Full microservices | Independent scaling and delivery | *Create item* touches six blocks in one transaction — distributed that becomes a saga with compensation logic · one developer, so there is no coordination problem to solve · operational and security effort without a return |

## Decision

A **modular monolith** with 18 machine-verified building blocks, coupling
exclusively through published `api` packages and domain events, plus an
**isolated plugin runtime** for foreign code.

## Rationale

Microservices solve problems here that do not exist (team coordination,
technology diversity, independent scaling) and create problems that are real
(distributed transactions in the consistency-critical core, multiplied
operational effort for one person, a larger attack surface). Spring Modulith and
ArchUnit deliver the disciplining effect of service boundaries — without the
price.

## Consequences

- **CI must enforce the boundaries.** Without automatic verification a modular
  monolith decays. These checks are not optional and not switchable off (risk R3
  in [14](../architecture/14-quality-risks-glossary.md)).
- Six blocks are cut so that they can be extracted; a trigger is named for each.
  Seven stay together permanently.
- Scaling happens through multiple instances of the same artifact in the `api`
  and `worker` roles, not through splitting.
- The size of the shared kernel `platform` is monitored — it is the typical point
  of decay.
- An outage affects all blocks. That is accepted: the availability target is
  99 %, not 99.99 %.
