<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `name-taken`

**Type URI:** `https://home-inv.example/problems/name-taken`  
**HTTP status:** `409`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A location's name is already carried by a live sibling. Names are unique among siblings, case-insensitively, so that the tree a person reads matches the tree the database holds.

## Notes

Registered 2026-09-12, during the stage-0 implementation, after `deploy/smoke/journey.sh` was run twice against one deployment and the second run was answered `500`: the partial unique index `location_sibling_name` was enforced by the migration and answered for nowhere. Distinct from `resource-exists`, which is about a client-chosen **id** — a client branching on the status has to tell "that identifier is taken" from "that name is taken", and only one of the two is fixed by choosing a different id. Mapping it to `validation-failed` was rejected for the reason written out under `resource-exists`.

## Where this is specified

- REQ-CORE-064: names are unique among siblings
- 07 §7.1: `location_sibling_name`, partial so a tombstone does not block re-use

