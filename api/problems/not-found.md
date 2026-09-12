<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `not-found`

**Type URI:** `https://home-inv.example/problems/not-found`  
**HTTP status:** `404`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The resource does not exist, **or** exists and is not visible to this caller. One token, deliberately, for both.

## Notes

**This entry is a security control and must not be split.** `REQ-SEC-025` requires foreign or invisible objects to be indistinguishable from absent ones, and `REQ-IDENT-014` requires the same of `/c/{code}`: an unknown code and a missing permission must give the same answer. Two tokens here would turn the error body into the existence oracle the status code was chosen to avoid. A CI test asserts the two cases produce byte-identical responses.

## Where this is specified

- 08 §8.2: `404` not present **or not visible**
- 05 §5.1: location belongs to another tenant → `404`, **not** `403`, so as not to reveal existence
- REQ-SEC-025, REQ-IDENT-014

