<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `internal-error`

**Type URI:** `https://home-inv.example/problems/internal-error`  
**HTTP status:** `500`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

Something failed that the application does not have a specific answer for. The request may be repeatable; nothing about what went wrong is disclosed.

## Notes

ADDED 2026-09-12. `REQ-API-003` requires one error format on every endpoint, and until this existed an unexpected exception left through Spring Boot's own `/error` handler as `{"timestamp": ..., "error": ...}` — a second, undocumented shape, on the one path a client is least able to anticipate.

The `detail` is a fixed sentence. An exception message is written for an operator and regularly contains a query, a path or an identifier, and the `traceId` is what connects the caller's report to the line that has all of it (`REQ-NFR-042`).

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- ``traceId`, and nothing else — the operator's log line is the detail`


## Where this is specified

- 08 §8.2: every error is problem+json
- REQ-API-003, REQ-NFR-042

