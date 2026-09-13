<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `version-frozen`

**Type URI:** `https://home-inv.example/problems/version-frozen`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A published type version was edited. A published version is a snapshot: the items written against it carry its schema, and changing it would change what they mean.

## Notes

The answer carries the version id, so a client can offer the one thing that does work — starting a new draft from it. `409` rather than `422`: nothing about the request is malformed, and the same body against a draft succeeds.

## Where this is specified

- REQ-CORE-025: types are versioned; an item references a type version
- ADR-0020: a type change devalues no existing data

