<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `federated-identity-linked-elsewhere`

**Type URI:** `https://home-inv.example/problems/federated-identity-linked-elsewhere`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

That provider identity is already linked to another account here.

## Notes

A link is unique on `(issuer, subject)` instance-wide: two accounts on one foreign identity would make every later sign-in ambiguous, and an ambiguity resolved by taking the first row is an account takeover with an ordering bug in front of it. The way out is to unlink it on the account that holds it.

## Where this is specified

- REQ-AUTH-005, REQ-AUTH-006

