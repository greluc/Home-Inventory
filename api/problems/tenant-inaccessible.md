<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `tenant-inaccessible`

**Type URI:** `https://home-inv.example/problems/tenant-inaccessible`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The tenant is suspended, or pending deletion within its grace period. One token for both states, deliberately.

## Notes

DECIDED 2026-09-11 as open point O26. Two tokens would tell a caller which of the two states a tenant is in, and that is an existence-and-status oracle over a boundary the rest of the design closes carefully (REQ-SEC-025, REQ-IDENT-014, and the `not-found` entry above, which must not be split for the same reason). A member of the tenant learns the state from the administration UI, where they are already authenticated and entitled to it; the API says only that the tenant cannot be acted on.
403 rather than 404: the caller is a member and the tenant's existence is not a secret from them. 503 was rejected — suspension is not a transient condition and Retry-After would be a lie.

## Where this is specified

- 04 §4.3 `tenancy`: Tenant states; 05 §5.9: access blocked immediately
- REQ-TEN-011, REQ-SEC-082

