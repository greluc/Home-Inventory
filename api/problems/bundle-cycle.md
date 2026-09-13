<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `bundle-cycle`

**Type URI:** `https://home-inv.example/problems/bundle-cycle`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

Putting that item into that bundle would make the bundle contain itself — directly, or through a chain of bundles.

## Notes

Its own token rather than `invalid-move`, which says the same thing about the tree of PLACES. A client acts on the two in different screens — one is where things are kept, the other what a thing is part of — and a shared token would have to be told apart by reading the detail text, which is what a stable `type` exists to avoid. Registered with REQ-CORE-007's implementation. An item may be in several bundles, so the loop can be long and is visible from neither end of the request that would close it; the check is a reachability walk and the answer names no intermediate step, because the way out is the same whatever the chain was.

## Where this is specified

- REQ-CORE-007: an item can be a bundle containing other items
- 04 §4.4: a bundle does not contain itself

