<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `federated-identity-unlinked`

**Type URI:** `https://home-inv.example/problems/federated-identity-unlinked`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The provider verified who you are and no account here is linked to that identity; sign in and link it from your account settings.

## Notes

The refusal REQ-AUTH-006 exists for. A verified address that matches an existing account is NOT permission to sign in as it, so an unlinked identity is refused even when the address is one this instance knows — and the message is the same either way, because a different one would be an account oracle.
An instance with `HOMEINV_REGISTRATION_MODE=open` may instead CREATE an account here (REQ-AUTH-004); this token is what the other two modes answer.

## Where this is specified

- REQ-AUTH-005, REQ-AUTH-006, REQ-SEC-020

