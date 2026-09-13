<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `role-escalation`

**Type URI:** `https://home-inv.example/problems/role-escalation`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

Somebody tried to grant, or withdraw, a role carrying permissions they do not hold themselves.

## Notes

Distinct from `forbidden`, which says the caller's role does not permit the operation at all. Here the caller may administer members and reached past their own ladder rung, which is a different thing to tell somebody and a different thing to see in a log.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- ``actorRole` and `targetRole`, so a client can say which grant was refused`


## Where this is specified

- REQ-TEN-010: nobody can grant permissions they do not hold themselves

