<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `idempotency-key-conflict`

**Type URI:** `https://home-inv.example/problems/idempotency-key-conflict`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The `Idempotency-Key` was already used with a **different** payload. The same key with the same payload returns the original response instead.

## Where this is specified

- 08 §8.2 concurrency table: a repeat with a different payload → `409`
- 05 §5.1 error cases, REQ-API-005

