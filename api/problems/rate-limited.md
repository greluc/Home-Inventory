<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `rate-limited`

**Type URI:** `https://home-inv.example/problems/rate-limited`  
**HTTP status:** `429`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A per-user, per-tenant or per-IP rate limit was reached.

## Notes

Login and code resolution have their own, stricter limits and use this same token — the limit that was hit is an operational detail, not a contract.

CORRECTED 2026-09-12: this read `since: 1` while `ApiExceptionHandler` has emitted it since stage 0, because REQ-SEC-014's login throttle is a stage-0 requirement and answers with it. The general rate limit of REQ-SEC-064 is stage 1; the token is not, and `since` records where the condition first occurs.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- ``Retry-After` and the `RateLimit-*` headers (08 §8.2, REQ-SEC-064)`


## Where this is specified

- 08 §8.2 security table
- REQ-SEC-064

