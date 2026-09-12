<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `resource-exists`

**Type URI:** `https://home-inv.example/problems/resource-exists`  
**HTTP status:** `409`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A creating `POST` supplied an `id` that already exists in this tenant with **different** content. The same id with the same content returns `200` instead, which is what makes a retried creation safe.

## Notes

Registered 2026-09-11, during the stage-0 implementation. The contract specified the same-content case and left the differing one unspecified, and `idempotency-key-conflict` does not cover it: that token is about the `Idempotency-Key` header, which is stage 1 (REQ-API-005), while client-chosen ids are stage 0 (REQ-CORE-001). Mapping it to `validation-failed` was rejected because a client branching on the status could then not tell a conflict from a bad field.

## Where this is specified

- REQ-CORE-001: creation with a client-generated ID returns `201`; the same ID with the same content returns `200`
- 08 §8 client-generated IDs, REQ-API-003

