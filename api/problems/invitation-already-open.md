<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `invitation-already-open`

**Type URI:** `https://home-inv.example/problems/invitation-already-open`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

This tenant already has an unused invitation for the address, or the person at that address is already a member of it.

## Notes

Two live invitations to one address would be two working tokens, and withdrawing the one an administrator remembers would leave the other one open. Registered with REQ-TEN-004's implementation.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- ``invitationId`, when the conflict is an open invitation, so a client can offer to withdraw it`


## Where this is specified

- REQ-TEN-004: invitations are single-use, time-limited and bound to an address

