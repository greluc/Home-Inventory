<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `method-not-allowed`

**Type URI:** `https://home-inv.example/problems/method-not-allowed`  
**HTTP status:** `405`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The path exists and does not support this method.

## Notes

ADDED 2026-09-12, with `not-acceptable` and `unsupported-media-type`. All three are produced by Spring's own request matching before a handler runs, and all three are reachable today: `POST /api/v1/items/{id}` is a 405. Without a token each of them was an error response with a shape of its own, which is precisely what `REQ-API-003` forbids.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- ``Allow`, naming the methods the path does support (RFC 9110 §15.5.6)`


## Where this is specified

- RFC 9110 §15.5.6
- REQ-API-003

