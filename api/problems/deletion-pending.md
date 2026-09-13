<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `deletion-pending`

**Type URI:** `https://home-inv.example/problems/deletion-pending`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The tenant has already been asked to be erased; the grace period is running.

## Notes

Not a second revocation token: two live ones would be two ways to withdraw one request, and withdrawing with the one somebody remembers would leave the other working.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- ``eraseAfter`, the instant the erasure begins (REQ-TEN-011)`


## Where this is specified

- REQ-TEN-011, 05 §5.9

