<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `plugin-unavailable`

**Type URI:** `https://home-inv.example/problems/plugin-unavailable`  
**HTTP status:** `503`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A plugin the requested ACTION depends on cannot be reached — its circuit is open, its deadline expired, or it is disabled.

## Notes

DECIDED 2026-09-11 as open point O25, and the decision is the boundary rather than the code. The registry noted that both a problem type and a `degradedReason` were defensible, and they are — for different cases:

  * the ACTION cannot be performed (print to a printer plugin, deliver through a
    channel plugin, store through a BlobStore plugin) -> this token, 503. Nothing
    happened, and the client must be told so;
  * the action SUCCEEDED and something derived from it is poorer (no enrichment
    proposals, an upload queued rather than stored remotely) -> a 200 with
    meta.degraded and the matching degradedReason in degraded-reasons.yaml.

Stated that way the two registries stop overlapping: a problem type means the request did not do what it said; a degradedReason means it did, less well. The `scan-unavailable` entry above is the same shape, and is the reason ADR-0024 makes it a refusal rather than a degradation.

## Where this is specified

- 05 §5.8: the circuit breaker fails immediately and visibly instead of piling up
- 13 §13.6, one-plugin row; ADR-0006, REQ-PLG-007

