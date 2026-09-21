<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `federated-flow-unknown`

**Type URI:** `https://home-inv.example/problems/federated-flow-unknown`  
**HTTP status:** `410`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The sign-in this callback belongs to is unknown, expired or already finished; start again from the sign-in page.

## Notes

The `state` parameter carries a single-use handle to server-side flow state (ADR-0029), and this is what a replayed callback gets — which is the property REQ-AUTH-005 is verified by. Also what an expired one gets, after ten minutes. 410 rather than 400: the handle was well-formed and is gone, and the caller's way out is to begin again rather than to correct anything.
It says nothing about whether the handle ever existed. A token that distinguished "never seen" from "already spent" would let somebody probe for sign-ins in flight.

## Where this is specified

- REQ-AUTH-005, ADR-0029

