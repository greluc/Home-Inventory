<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `federated-address-taken`

**Type URI:** `https://home-inv.example/problems/federated-address-taken`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

This instance creates accounts, and the address the provider verified already has one; sign in with it and link the provider deliberately.

## Notes

Only reachable on an `open` instance, where the alternative would have been to create a second account on an address that already has one. Linking the existing account instead is a decision for the person who owns it, taken while signed in and re-confirmed (REQ-AUTH-006) — never a consequence of somebody arriving with a token that carries the same string.

## Where this is specified

- REQ-AUTH-004, REQ-AUTH-006

