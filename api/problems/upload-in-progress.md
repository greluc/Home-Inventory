<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `upload-in-progress`

**Type URI:** `https://home-inv.example/problems/upload-in-progress`  
**HTTP status:** `423`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

Another request is already writing to this upload.

## Notes

tus asks a server to refuse a second concurrent write to one upload, and the reason is not tidiness: two appends that both read the offset before either writes are both at the right place and both write, leaving a file of the right length and the wrong bytes — which nothing notices until the digest at the very end. The refusal comes from the blob store, which is one process holding one volume and therefore the only place a lock covers every caller.

## Where this is specified

- REQ-MED-008, ADR-0084

