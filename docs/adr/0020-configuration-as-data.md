# ADR-0020 — Tenant configuration is synchronised domain data

**Status:** Accepted · **Date:** 2026-09-11

## Context

Item types, field definitions, location categories, value lists, label templates
and label geometries are **configurable while running**. At the same time clients
have to work offline and, while offline, validate input, render forms and preview
labels.

The question: is this configuration part of the deployment (files, migrations),
or is it ordinary domain data?

## Decision

**Ordinary domain data** — in tenant-scoped tables, editable through the normal
API, distributed to clients through the sync protocol (download only, see
[ADR-0014](0014-offline-synchronisation.md)), traceable in the audit log,
included in the tenant export.

From this follows the actual core of this decision:

> **The type system must not become a programming language.**

## What is permitted

| Permitted | Why it is harmless |
|---|---|
| Fields with a fixed data type from a closed list | Validatable, generatable, checkable offline |
| Constraints: required, range, regex, value lists, units | Declarative, expressible in JSON Schema |
| Visibility rules as a **simple condition** over another field of the same item (e.g. "show `warrantyUntil` when `hasWarranty` is true") | Evaluable without loops and without side effects |
| Inheritance between types, tightening only | Statically checkable |
| Label templates with field access, formatting and conditionals | Not Turing-complete |

## What is excluded

| Excluded | Why |
|---|---|
| Calculated fields with a formula language | It would be a language: evaluation order, cycles, time limits, access rights — and it would have to behave identically in Java, TypeScript and Kotlin |
| Rules with database or network access | Not evaluable offline, not securable |
| Arbitrary scripts in templates | Code execution from user input (risk R5) |
| Cross-field checks spanning several items | Not evaluable offline |

**Anything beyond that is a plugin.** There, the process boundary, capabilities,
deadlines and circuit breakers apply — precisely the protections an embedded
language would not have.

## Rationale

A configurable type system is the point at which inventory systems become
uncontrollable: first a calculated field, then a condition, then a loop — and
suddenly the configuration is code that nobody versions, tests or secures, but
that has to run identically in three runtimes. The line is therefore drawn
**now**, not later.

## Consequences

- Every type version yields a generated **JSON Schema (draft 2020-12)**. It
  applies at the API boundary and is additionally shipped to clients — the same
  check online as offline, in all three runtimes.
- Types are **versioned**. An item references a type **version**; a change
  devalues no existing data.
- A field is marked `deprecated` and hidden; the values remain. Final deletion is
  a separate administrative operation **with a preview of the affected set**.
- Configuration changes appear in the audit log with a before/after diff.
- The tenant export contains the configuration, so that a tenant can move
  entirely to another instance.
- Changing a field's searchability triggers a background run for
  `item_attr_index` and the search index — no migration, no DDL.
- Templates for common types (book, tool, appliance, furniture, clothing,
  software licence, document) ship with the product, but are only starting points
  and fully editable.
