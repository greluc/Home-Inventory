<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `revocation-unusable`

**Type URI:** `https://home-inv.example/problems/revocation-unusable`  
**HTTP status:** `410`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The revocation link cannot be used: no such token, already used, or the grace period is over.

## Notes

ONE token for all three, the same treatment `invitation-unusable` gets and for the same reason: telling them apart would let whoever holds a link learn that a tenant here was asked to be erased.

## Where this is specified

- REQ-TEN-011, 05 §5.9

