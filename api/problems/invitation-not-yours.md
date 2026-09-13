<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `invitation-not-yours`

**Type URI:** `https://home-inv.example/problems/invitation-not-yours`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The invited address already has an account, and the caller is not signed in as it.

## Notes

Holding the token proves the mailbox, which is enough to create the account the invitation names — nobody else exists to be harmed. It is not enough to attach a membership to an account that already belongs to somebody: a forwarded or intercepted link would otherwise put a stranger into a tenant they never joined. The way through is to sign in as the invited address.

## Where this is specified

- REQ-TEN-004, REQ-SEC-020 (no linking by address alone)

