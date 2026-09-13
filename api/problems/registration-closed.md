<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `registration-closed`

**Type URI:** `https://home-inv.example/problems/registration-closed`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

This instance creates no accounts; the invitation is good, but the address behind it has none.

## Notes

`HOMEINV_REGISTRATION_MODE=closed` (REQ-AUTH-004). The invitation is NOT consumed: somebody who already has an account can accept the same one, and the way out is an account from the operator. Its own token rather than a plain `forbidden`, because what the caller should do about it is specific and the refusal is about the instance rather than about them.

## Where this is specified

- REQ-AUTH-004, 12 §12.4

