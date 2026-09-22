<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `export-not-ready`

**Type URI:** `https://home-inv.example/problems/export-not-ready`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The export archive was asked for before it had been built.

## Notes

A 409 and not a 404: the job is there and the caller is looking at the right thing, it is simply not finished. A 404 would send somebody looking for a job they can see in their own list. The detail names the state instead, because "still running" and "it failed" lead a caller somewhere different -- one waits, the other asks again. Registered with REQ-PORT-005's implementation, when an export first became a job rather than a response.

## Where this is specified

- REQ-PORT-005: export runs asynchronously as a job resource with progress
- 08 §8.1: /export-jobs is a resource

