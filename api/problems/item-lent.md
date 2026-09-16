<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `item-lent`

**Type URI:** `https://home-inv.example/problems/item-lent`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The item is out on loan, and what was asked cannot be done while somebody else has it.

## Notes

TWO requests get it and the caller does the same thing about both, which is why it is one token: lending something that is already out would open a second loan on one object, and trashing it would throw away the only record of who to ask for it back. A `409` and not a `422` -- nothing about the request is malformed and the identical one succeeds once the thing is back. Its own token rather than `validation-failed`, because no field is wrong and a client showing a field error would point at nothing. Registered with REQ-LIFE-005's implementation, when an item could first be out.

## Where this is specified

- REQ-LIFE-005: a lent item is recognisable as such and not deletable
- 07 §7.8 `inventory.loan`: at most one open loan per item

