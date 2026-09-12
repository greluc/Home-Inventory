<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `validation-failed`

**Type URI:** `https://home-inv.example/problems/validation-failed`  
**HTTP status:** `422`  
**Since:** stage 0

**Registered.** Published at this URI and stable. A client may branch on it, and it will not change meaning or status code without a new major version of the API (REQ-API-008).

## What it means

The request was syntactically valid but violates a domain rule — most often an attribute that does not match its type version's JSON Schema.

## What the response carries

Beyond the members RFC 9457 defines and the `traceId` every response in this API carries:

- ``errors[]`, one entry per failing field, each with `pointer`, `code`, `message` and where applicable `expected` (08 §8.2)`


## Where this is specified

- 08 §8.2, the worked error example: `https://home-inv.example/problems/validation-failed`
- 05 §5.1 error cases: attribute violates the type definition → 422 with a field path
- 11 §11.3: a sync push result of `REJECTED` carries `problem.type` `…/validation-failed`
- REQ-CORE-005, REQ-API-003

