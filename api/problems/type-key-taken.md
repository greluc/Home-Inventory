<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `type-key-taken`

**Type URI:** `https://home-inv.example/problems/type-key-taken`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A key the tenant chose for a type, a location category, a field or a value is already in use for the same kind of thing.

## Notes

Distinct from `name-taken`, which is about a location's name among its siblings, and from `resource-exists`, which is about a client-chosen id. A key is neither: it is the stable identifier a tenant gives a definition, it appears inside every item that uses the field, and it is unique per tenant rather than per parent. A client branching on the status has to be able to tell "pick another key" from "pick another id".

## Where this is specified

- REQ-CORE-020: types are creatable and editable in the running system
- 07 §7.3: `UNIQUE (tenant_id, key)` on every definition table

