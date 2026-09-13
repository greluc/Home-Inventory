<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `invalid-move`

**Type URI:** `https://home-inv.example/problems/invalid-move`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A location cannot be moved where the request asks: into itself or into something it contains, or under a category that has said which categories it takes and did not name this one.

## Notes

ONE token for both, because the caller does the same thing about either: choose a different target. The depth ceiling is deliberately *not* here — it stays `validation-failed` carrying `maxDepth`, which is what creating a location too deep has always answered, and a client that already handles one needs no second branch for the other. Registered with REQ-CORE-043's implementation, when moving a subtree first became possible and the tree stopped being acyclic by construction.

## Where this is specified

- REQ-CORE-045: cycles in the tree are impossible
- REQ-CORE-047: a category may restrict its permitted child categories

