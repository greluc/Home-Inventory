<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `upload-offset-mismatch`

**Type URI:** `https://home-inv.example/problems/upload-offset-mismatch`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The bytes were offered at a place the upload is not at.

## Notes

The ordinary outcome of a broken connection and not a fault: a chunk was sent, the connection dropped somewhere in the middle, and neither side knows how much arrived. The response carries the authoritative offset in the `Upload-Offset` header, which is where the client continues from — asking rather than guessing is the whole difference between an upload that survives a network change and one that starts again. The status is the one the tus protocol names.

## Where this is specified

- REQ-MED-008, ADR-0084

