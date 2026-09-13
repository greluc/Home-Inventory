<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `second-factor-stale`

**Type URI:** `https://home-inv.example/problems/second-factor-stale`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The operation needs the second factor proved again; the code goes to `POST /api/v1/auth/mfa/step-up`.

## Notes

REQ-AUTH-011 asks for the factor again before granting permissions, deleting a tenant and exporting; fifteen minutes from the last accepted code, whether at the login or at the step-up. Its own token beside `second-factor-missing` because the two ask for different things: setting an authenticator up, and entering a code from the one that is there. Reading a sensitive field is on the same list and is NOT answered with this: the field is removed and the answer stays 200, because a list with one sensitive column would otherwise become unreadable.

## Where this is specified

- REQ-AUTH-011, 12 §12.4

