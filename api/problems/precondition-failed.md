<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `precondition-failed`

**Type URI:** `https://home-inv.example/problems/precondition-failed`  
**HTTP status:** `412`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The `If-Match` entity tag does not match the resource's current version.

## Where this is specified

- 08 §8.2: a mismatch → `412`
- REQ-API-004

