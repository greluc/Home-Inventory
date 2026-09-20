<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `item-state`

**Type URI:** `https://home-inv.example/problems/item-state`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The item is in a state where what was asked cannot be done -- it has been sold, disposed of or trashed.

## Notes

SEPARATE from `item-lent`, because a caller acts differently on the two: "somebody has it" is answered by asking for it back, "you sold it in March" is not answered at all. A client forced to read the detail text to tell them apart would be doing the work a stable `type` exists to remove -- the argument `bundle-cycle` makes against sharing `invalid-move`. Registered with REQ-LIFE-007's implementation, when an item first had a state it could not come back from.

## Where this is specified

- REQ-LIFE-007: sale and disposal change the state
- 04 §4.4: ACTIVE -> LENT -> ACTIVE, ACTIVE -> SOLD/DISPOSED

