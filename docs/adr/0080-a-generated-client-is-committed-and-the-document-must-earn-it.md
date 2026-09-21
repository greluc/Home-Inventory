<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0080 — A generated client is committed, and the document has to earn it

**Status:** Accepted · **Date:** 2026-09-21

**Relates to:** `REQ-API-002`, [ADR-0049](0049-openapi-generated-from-the-implementation.md)
(the document is generated from the implementation),
[ADR-0013](0013-mobile-apps.md) (Kotlin Multiplatform for the apps)

## Context

`REQ-API-002` asks for generated clients in TypeScript and Kotlin that compile in CI,
and ADR-0010 states the consequence plainly: *"hand-written clients do not exist"*. One
did — `web/src/api.ts`, 519 lines, whose own header said it was hand-written for stage 0
and that the generator was stage 1's job.

Three decisions had to be made, and the third turned out to be the interesting one.

## Decisions

### 1. Two generators, each in the toolchain of what it produces

| | TypeScript | Kotlin |
|---|---|---|
| Generator | `openapi-typescript` | `openapi-generator`, `--library multiplatform` |
| Output | **1 file, types only, no runtime** | 186 files in `commonMain`, Ktor + kotlinx-serialization |

Measured before deciding. `openapi-generator`'s `typescript-fetch` produced 144 files and
25 442 lines against `openapi-typescript`'s single file, brought a second error model
beside this project's `ApiError`, put all 133 operations in one class, and **did not
compile**: it names its parameter interfaces `<X>Request` and this API already has schemas
called `RoleRequest`, `RuleRequest`, `SavedSearchRequest` and two more, so import and local
declaration collided five times.

`openapi-typescript` is run through a pinned `npx` rather than installed, because it
peer-depends on TypeScript 5 and this client is on 7 — the same substitution `REQ-SEC-076`
records for `oxlint` against `typescript-eslint`. The output is committed, so the
application builds without the generator.

**Kotlin is multiplatform and not `jvm-okhttp`**, although the latter is smaller: ADR-0013
listed the generated client as preparation *"because the contract must be cleanly
generatable for Kotlin too"*, and a JVM client cannot be used from `commonMain`, which is
where the shared sync logic of those apps will live. Only the JVM target is built — an iOS
target needs a Mac, and CI is Linux.

### 2. The output is committed, and drift is a red build

The same treatment `api/openapi.yaml`, `deploy/generated/`, `web/nginx/default.conf` and
the persisted-query register already get. A contract change is then a diff in a pull
request beside the code that caused it, rather than something that happens inside a build
nobody reads. `client:check` and `tools/kotlin_client.py --check` run in CI.

### 3. `api.ts` stays, and shrinks to the three rules the document does not carry

Three things, and each of them is a decision this project made rather than a fact about
the API: the CSRF header, the RFC 9457 unwrapping into a typed `ApiError`, and
`credentials: "same-origin"`. Paths, parameters and response shapes come from the
document through `openapi-fetch`, so a mistyped path is now a compile error —
`scripts/contract.mjs`, which compared the hand-written client's path strings against the
document, was retired because the compiler does strictly more.

## What generating found

A document that had passed spectral, `oasdiff` and its own drift check for a stage turned
out to be wrong in ways only a generator notices:

- **Nine schema names were shared by two Java types each**, and springdoc names a schema
  after the simple class name, so one shape won and the other was lost silently. Four were
  live lies: `/api/v1/version` was documented as a *catalogue type version*, creating a
  custom role carried the body for *assigning* one, field visibility carried a visibility
  *rule*, and the insurance report carried the *valuation* report's shape.
  `SchemaNameTest` now refuses a new one.
- **No response schema said which properties it carries.** springdoc marks `required` from
  bean validation, which response records have none of, so every field in every generated
  client was optional. A record always serialises every component, so
  `RecordPropertiesArePresent` states it — on response schemas only, because on a request
  `required` means *the client must send it*.
- **`Problem` declared `additionalProperties`**, correctly (RFC 9457 allows extension
  members) and unusably: the Kotlin generator emitted a class extending
  `HashMap<String, Any>()()`. The schema now describes the six members every problem
  carries, and the extras stay documented per type under `api/problems/`.
- **`Problem.status` had a format and no type**, which becomes `Any?` — and then a
  kotlinx-serialization error.
- **One endpoint answered a JSON object with dynamic keys**, which is the one shape no
  generator can type; `GET /api/v1/field-visibility` answers a list of entries now, each
  carrying its own key.
- **The client sent `quantity` as a string** where the document says number. Jackson
  accepted it, so it worked and said nothing.

None of that was visible from the inside. **A generated client is a second reader of the
contract, and the first one with no room to be charitable** — every item above is now held by
a check: `SchemaNameTest`, `OpenApiDocumentIT`, `npm run client:check` and
`tools/kotlin_client.py --check`.

## Consequences

- Every controller now carries a chosen `@Tag`, because a generator puts one file per tag
  and a document with none produces a single class with 133 methods. The names are
  contract — a generated class is named after them — which is why they are not the class
  slugs springdoc would have inferred. `ArchitectureRulesTest` requires one.
- The repository gains a Kotlin toolchain, which ADR-0013 anticipated: *"the repository
  becomes multi-language … the build must carry that without friction"*. It is one
  subproject, excluded from the root's `java` plugin because Kotlin Multiplatform refuses
  to share a project with it.
- 186 generated Kotlin files and one 23 000-line TypeScript declaration are in the
  repository. That is the cost of reviewable contract changes, and it is the same trade
  every other generated artefact here already makes.
- **The document still says nothing about nullability.** `required` says a property is
  present; whether its value may be null is a separate assertion springdoc does not make,
  and inventing one from a Java type would claim "may be null" of an id that never is. A
  generated client therefore knows which fields arrive and not which may be empty — which
  is exactly what the hand-written client asserted before there was a generated one.
