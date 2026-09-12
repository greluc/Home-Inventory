<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `not-acceptable`

**Type URI:** `https://home-inv.example/problems/not-acceptable`  
**HTTP status:** `406`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The caller's `Accept` header allows no representation this endpoint can produce.

## Notes

Every endpoint answers `application/json`, and errors `application/problem+json` — which RFC 9457 §3 makes acceptable to a client that asked for JSON, and which is sent regardless, because an error that cannot be represented is still an error the caller has to be told about.

## Where this is specified

- RFC 9110 §15.5.7
- RFC 9457 §3
- REQ-API-003

