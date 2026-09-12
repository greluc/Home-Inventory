<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `payload-too-large`

**Type URI:** `https://home-inv.example/problems/payload-too-large`  
**HTTP status:** `413`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The request body exceeds the JSON limit, or a bulk operation exceeds its entry limit.

## Notes

DECIDED 2026-09-11 as open point O24. 08 §8.2's status list did not include 413 and nothing else named one, so this sat in `pending` — while REQ-NFR-078 (stage 0) requires an oversized body to be rejected by `api` as application/problem+json and NOT by `web` as an nginx HTML page. A requirement due at stage 0 cannot depend on an undecided status code. 413 is RFC 9110 §15.5.14 and the only defensible answer; the status list in 08 §8.2 now carries it.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- `the limit that was exceeded, so a client can chunk rather than guess`


## Where this is specified

- 08 §8.2 security table: JSON max. 1 MB, bulk operations max. 500 entries
- REQ-SEC-065, REQ-NFR-078, RFC 9110 §15.5.14

