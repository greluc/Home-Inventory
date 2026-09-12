<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `forbidden`

**Type URI:** `https://home-inv.example/problems/forbidden`  
**HTTP status:** `403`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The caller is authenticated and the resource is one they may know exists, but the action is not permitted.

## Notes

**Not** used for a foreign or invisible resource — that is `not-found` below, and the distinction is a security property, not a style choice (`REQ-SEC-025`).

## Where this is specified

- 08 §8.2: `403` authenticated but not permitted
- REQ-SEC-026

