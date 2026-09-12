<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `quota-exceeded`

**Type URI:** `https://home-inv.example/problems/quota-exceeded`  
**HTTP status:** `403`  
**Since:** stage 1

**Registered.** Published at this URI and stable. A client may branch on it, and it will not change meaning or status code without a new major version of the API (REQ-API-008).

## What it means

A tenant quota would be exceeded by this operation — item count, stored bytes, plugin count or API calls.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- `the current and the permitted amount (05 §5.1, REQ-TEN-009)`


## Where this is specified

- 05 §5.1: `403` with `type: .../quota-exceeded`, current and permitted amount
- REQ-TEN-009, 04 §4.3 `tenancy` (`QuotaGuard`)

