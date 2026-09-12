<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `cursor-expired`

**Type URI:** `https://home-inv.example/problems/cursor-expired`  
**HTTP status:** `410`  
**Since:** stage 3

**Registered.** Published at this URI and stable. A client may branch on it, and it will not change meaning or status code without a new major version of the API (REQ-API-008).

## What it means

The device's sync cursor is older than the tenant's `change_log` retention. The device must perform a full seeding.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- `no resumption hint — by design; the client restarts from `/sync/bootstrap``


## Where this is specified

- 11 §11.3: `410 Gone` with `type: …/cursor-expired` → the device performs a full seeding
- REQ-SYNC-009, 07 §7.6 (retention 30–365 days, default 90)

