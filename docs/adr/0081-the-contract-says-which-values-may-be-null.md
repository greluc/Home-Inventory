<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0081 — The contract says which values may be null

**Status:** Accepted · **Date:** 2026-09-21

**Amends:** [ADR-0080](0080-a-generated-client-is-committed-and-the-document-must-earn-it.md)
— its last consequence read *"the document still says nothing about nullability"*, and
that was a gap left open, not a decision. It is closed here, the same day, because a gap
that is written down is still a gap.

## Context

ADR-0080 gave the document the first half of what a consumer needs: `required`, which says
a property is **present**. A record serialises every component, so that is true of every
response property and was worth stating.

It is not the other half. `required` says the key is there; it says nothing about whether
its value is `null`. A generated client reading only that believes an item's `description`
is a string, and finds out otherwise at run time — on somebody's screen, not in a build.

The information existed. Every one of these components carries a Javadoc sentence saying
so: *"free text, may be `null`"*, *"where it is; `null` for a digital item"*. **Prose
reaches a reader and no generator.**

## Options

| Option | What the document would say | Why not |
|---|---|---|
| **`@Nullable` on the component, read by a `PropertyCustomizer`** | exactly the 83 components that can be null | Chosen. The annotation is explicit, checkable by a compiler and by SpotBugs, and it improves the Java as well as the document |
| Every reference type is nullable | "may be null" of every id that never is | Mechanically true and useless: a client would null-check everything, which is the same as checking nothing |
| Parse the Javadoc | the same 83 today | A contract that changes when somebody rewords a sentence |
| Leave it | nothing | What ADR-0080 recorded, and what this replaces |

## Decision

**`jakarta.annotation.Nullable` on the record component, published as JSON Schema's
`type: [x, "null"]`.**

Jakarta's and not JSpecify's, although Spring Framework 7 brings the latter and it is the
more modern choice: JSpecify's `@Nullable` is `TYPE_USE` only, so on a record component it
annotates the *type* and not the field, the accessor or the constructor parameter — and
those are where swagger's property resolver looks. Written with JSpecify first, the
document came back unchanged twice before that was the explanation.

OpenAPI 3.1 is JSON Schema, so the spelling is a type **set** and not 3.0's
`nullable: true`. A property that is a `$ref` is left alone: "this object or null" needs a
`oneOf` wrapper that takes the property's description with it and that the generators
handle differently.

The Javadoc sentences stay. A sentence explaining *when* a value is absent is not a
duplicate of an annotation saying *that* it can be.

## Consequences

- **76 properties** now declare it, and the generated clients say it: TypeScript
  `description: string | null`, Kotlin `val description: kotlin.String?` **and**
  `@Required` — the two facts, separately, which is the point. Both clients are regenerated
  and compiled in CI (`npm run client:check`, `tools/kotlin_client.py --check`,
  `./gradlew build`), so a property that changes here and not there is a red build.
- **SpotBugs immediately found 13 places** where the core reads one of these without
  checking. All thirteen were the same shape — `x() == null ? … : x().y()`, a null check
  followed by a second call to the same accessor, which an analyser cannot know returns
  the same value. Correct code, and it now binds the value once, which is what it should
  have said. *That is the annotation earning its place on the first day: it is not
  documentation, it is something a tool can act on.*
- A component that becomes nullable later has to be annotated, and nothing forces that —
  the same limitation every annotation-based contract has. What there is instead is a
  compiler and an analyser that will complain at the first dereference, which is more than
  the Javadoc ever did.
- `jakarta.annotation-api` is now a **declared** dependency rather than a transitive one,
  because the published contract is generated from it.
