<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `precondition-required`

**Type URI:** `https://home-inv.example/problems/precondition-required`  
**HTTP status:** `428`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A mutating request on a single resource arrived without `If-Match`. There is no blind overwrite.

## Where this is specified

- 08 §8.2: a missing header → `428 Precondition Required`
- REQ-API-004

