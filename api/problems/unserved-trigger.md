<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `unserved-trigger`

**Type URI:** `https://home-inv.example/problems/unserved-trigger`  
**HTTP status:** `422`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A reminder rule names a trigger that nothing in this installation can answer.

## Notes

REQ-NOTI-003 names six triggers and two of them have no source: a stocktake is stage 2 and no licence expiry date is stored. A rule naming one is refused when it is WRITTEN, by the person who can still choose another -- accepted and then silently never firing is the failure a reminder feature cannot afford, and it would look like working software until the day somebody needed it. The same argument REQ-SRCH-008 makes about refusing an unparseable filter when it is saved rather than when it is run. A 422 and not a 409, because what is wrong is the value of a field. The detail names the triggers that do work, since picking one of them is the way out.

## Where this is specified

- REQ-NOTI-003: triggers at least warranty expiry, maintenance interval, return date, minimum stock, licence expiry, stocktake discrepancy
- REQ-NOTI-001: reminder rules are data, not code

