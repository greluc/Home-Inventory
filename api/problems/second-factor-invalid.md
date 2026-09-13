<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `second-factor-invalid`

**Type URI:** `https://home-inv.example/problems/second-factor-invalid`  
**HTTP status:** `401`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The code presented is not valid.

## Notes

ONE token for a wrong code, a code from a time step already spent, a recovery code already used and an account with no second factor at all — the treatment `invitation-unusable` gets, for the same reason. Telling them apart would say whether a guess was close, and "already used" would confirm that the code existed.

## Where this is specified

- REQ-AUTH-002, 12 §12.4

