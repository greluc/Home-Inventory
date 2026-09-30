# api/

The **OpenAPI 3.1 document** — [`openapi.yaml`](openapi.yaml) — and the clients
generated from it.

```
api/
├── openapi.yaml        generated from the running application, committed (ADR-0049)
├── spectral.yaml       the ruleset it is linted against
├── problems/           one document per `problem.type`, rendered from the registry
└── clients/kotlin/     the generated Kotlin Multiplatform client (REQ-API-002)
```

The **TypeScript** client is not here but in [`web/src/generated/`](../web/src/generated/),
beside the only thing that consumes it and inside the toolchain that regenerates it
(`npm run client:generate`). The Kotlin one is here because it is a Gradle subproject
of its own, `:api-client-kotlin`, with no consumer in this repository yet — the apps of
[ADR-0013](../docs/adr/0013-mobile-apps.md) are its reason for existing, and it is
compiled in CI so the contract is proved to generate into the place they will use it
from. Both are **committed and drift-checked**; see
[ADR-0080](../docs/adr/0080-a-generated-client-is-committed-and-the-document-must-earn-it.md).

## It is generated, committed, and checked against the code

`springdoc-openapi` produces the document from the running application, and
`OpenApiDocumentIT` compares it with the committed copy on every test run.
Editing it by hand fails that check, exactly like editing
`deploy/compose/compose.yaml` or `web/nginx/default.conf`.

**The document endpoint is off in every deployment.** `springdoc.api-docs.enabled`
is `false` outside the test profile, and Swagger UI is off everywhere: serving
either would be a second HTTP surface on an internet-facing application,
describing every endpoint it has, for a document that is already in this
directory.

This directory used to state the opposite — the document written first, server
stubs and clients generated from it. That was decided in the design phase and not
done: after a full stage there were six hand-written endpoints, a hand-written
client, and no document at all.
[ADR-0049](../docs/adr/0049-openapi-generated-from-the-implementation.md) records
why the direction changed and what it costs. The short version: what mattered was
never that a person typed the document, it was that the document and the
implementation agree — and generation with a drift check reaches that with a red
build instead of a discipline.

**The plugin contract is not affected.** [`proto/`](../proto/) is written first and
published, because it is agreed with authors who do not implement it. The
distinction is between an internal contract with one author and an external one
with many, and it is drawn on purpose.

## The gates

| Check | Tool | Why it still works on a generated document |
|---|---|---|
| The document matches the implementation | `./gradlew :app:test` | It **is** the implementation, restated: `OpenApiDocumentIT` reads it from a running context and compares |
| It is valid and lints against a project ruleset | `spectral`, with [`spectral.yaml`](spectral.yaml) | With no author enforcing naming and status codes by hand, the ruleset is where those conventions live |
| No unannounced breaking change | `oasdiff` against the last release | Compares two documents; indifferent to how either was produced |
| Every `problem.type` a response emits is in the registry | a check against [`docs/reference/problem-types.yaml`](../docs/reference/problem-types.yaml) | `REQ-API-003` |
| The examples validate against their schemas | schema validation | — |

## What belongs here

- [`openapi.yaml`](openapi.yaml) — the document. **A build output that is
  committed**, so a contract change is reviewable as a diff next to the code that
  caused it. `./gradlew updateOpenApi` regenerates it; `./gradlew :app:test` fails
  while it and the code disagree.
- [`spectral.yaml`](spectral.yaml) — the lint ruleset. It carries more weight
  here than in a specification-first project: with no author enforcing naming and
  status codes by hand, this file is where those conventions live.
- [`problems/`](problems/README.md) — one document per RFC 9457 `type` URI,
  rendered from [the registry](../docs/reference/problem-types.yaml) by
  `tools/render_problems.py`. Clients branch on those URIs, so they are part of
  the contract and change like it — and the URIs are meant to be dereferenceable,
  which is what these documents are for.

## What does not

The GraphQL schema (read-only, served by the application, stage 1) and the protobuf
plugin contract — the latter lives in [`proto/`](../proto/) because it is versioned
and published separately.

## Clients

The TypeScript client in [`web/src/api.ts`](../web/src/api.ts) is **hand-written
through stage 0** and says so where it lives. `REQ-API-002` generates the
TypeScript and Kotlin clients from this document in stage 1, and that is when the
hand-written one goes.
