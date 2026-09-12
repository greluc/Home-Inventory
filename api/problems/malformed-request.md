<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `malformed-request`

**Type URI:** `https://home-inv.example/problems/malformed-request`  
**HTTP status:** `400`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The request could not be parsed, or an unknown field was present.

## Notes

`REQ-SEC-029` makes unknown fields a REJECTION rather than something ignored, so this covers more than a JSON syntax error.

## Where this is specified

- 08 §8.2 status list: `400` syntax
- REQ-SEC-029

