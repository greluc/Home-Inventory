<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `scan-unavailable`

**Type URI:** `https://home-inv.example/problems/scan-unavailable`  
**HTTP status:** `503`  
**Since:** stage 0

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The malware scanner is unreachable or timed out. The upload is rejected and the blob stays `PENDING_SCAN` and unretrievable until a catch-up run clears it.

## Notes

This is the one `503` that is not a degradation but a refusal, and that is deliberate: the scan is **fail-closed** (ADR-0024). It is also the only degradation level in 13 §13.6 that blocks a user function entirely, which is why it is a problem type and not a `degradedReason`.

## Where this is specified

- ADR-0024: scanner unreachable → `503`
- REQ-SEC-092, REQ-MED-013

