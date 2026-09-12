<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `unsupported-media-type`

**Type URI:** `https://home-inv.example/problems/unsupported-media-type`  
**HTTP status:** `415`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The request's `Content-Type` is not one this endpoint reads.

## Notes

About the header, not about the bytes. An upload whose *content* is a type this system does not store is `validation-failed` with a `422`, decided by `MediaTypeDetector` after sniffing — a different condition with a different answer, and deliberately a different token (REQ-MED-004).

## Where this is specified

- RFC 9110 §15.5.16
- REQ-API-003

