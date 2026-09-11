# api/

The **OpenAPI 3.1 document** — `openapi.yaml`.

## It is generated, committed, and checked against the code

`springdoc-openapi` produces the document from the running application;
`./gradlew openApiCheck` regenerates it and fails on any difference. Editing it by
hand fails that check, exactly like editing `deploy/compose/compose.yaml` or
`web/nginx/default.conf`.

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
| The document matches the implementation | `./gradlew openApiCheck` | It **is** the implementation, restated |
| It is valid and lints against a project ruleset | `spectral`, with `spectral.yaml` | With no author enforcing naming and status codes by hand, the ruleset is where those conventions live |
| No unannounced breaking change | `oasdiff` against the last release | Compares two documents; indifferent to how either was produced |
| Every `problem.type` a response emits is in the registry | a check against [`docs/reference/problem-types.yaml`](../docs/reference/problem-types.yaml) | `REQ-API-003` |
| The examples validate against their schemas | schema validation | — |

## What belongs here

- `openapi.yaml` — the document. **A build output that is committed**, so a contract
  change is reviewable as a diff next to the code that caused it.
- `spectral.yaml` — the lint ruleset.
- `problems/` — one document per RFC 9457 `type` URI, rendered from the registry.
  Clients branch on those URIs, so they are part of the contract and change like
  it.

## What does not

The GraphQL schema (read-only, served by the application, stage 1) and the protobuf
plugin contract — the latter lives in [`proto/`](../proto/) because it is versioned
and published separately.

## Clients

The TypeScript client in [`web/src/api.ts`](../web/src/api.ts) is **hand-written
through stage 0** and says so where it lives. `REQ-API-002` generates the
TypeScript and Kotlin clients from this document in stage 1, and that is when the
hand-written one goes.
