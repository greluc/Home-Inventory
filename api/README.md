# api/

The **OpenAPI 3.1 specification** — `openapi.yaml` will live here.

## This directory is the contract, not a description of one

The specification is written first; server stubs, the TypeScript client for the
web PWA, the Kotlin client for the apps, and the documentation are **generated**
from it. Hand-written clients do not exist in this project
([08 API Contract](../docs/architecture/08-api-contract.md)).

That ordering is what makes the CI gates possible:

| Check | Tool |
|---|---|
| The specification is valid and complete | `spectral`, with a project ruleset |
| No unannounced breaking change | `oasdiff` against the last release |
| The implementation matches the specification | Generated stubs + response validation in integration tests |
| The examples in it are valid | Schema validation of the examples |
| Generated clients compile | `tsc`, the Kotlin build |

## What belongs here

- `openapi.yaml` — the specification
- `spectral.yaml` — the lint ruleset
- `problems/` — the stable `type` URIs for RFC 9457 problem details, one document
  per problem type. Clients branch on these URIs, so they are part of the
  contract and change like it.

## What does not

The GraphQL schema (read-only, served by the application) and the protobuf
plugin contract — the latter lives in [`proto/`](../proto/) because it is
versioned and published separately.

## Status

Empty. The specification is written alongside the first endpoints in stage 0.
