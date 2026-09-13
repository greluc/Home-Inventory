<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `invitation-unusable`

**Type URI:** `https://home-inv.example/problems/invitation-unusable`  
**HTTP status:** `410`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The invitation cannot be used: no such token, or it has been used, withdrawn or has run out.

## Notes

ONE token for all four conditions, deliberately. Distinguishing them would let whoever holds a link learn that somebody was invited to this instance, which is exactly what the single use is meant to end rather than advertise. `410` rather than `404` because the caller followed a link meant for them and "this no longer works" is true of every one of the four.

## Where this is specified

- REQ-TEN-004: reuse is rejected

