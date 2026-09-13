<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `second-factor-required`

**Type URI:** `https://home-inv.example/problems/second-factor-required`  
**HTTP status:** `401`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The password was accepted and the account is protected by a second factor; the code goes to `POST /api/v1/auth/mfa`.

## Notes

A 401 like a wrong password, and its own token because it is the one case where the client does something other than ask for the password again. Saying this much costs nothing: whoever sees it has already presented the right password for the address. The half-done login lives in the session for five minutes and is consumed by the first answer, right or wrong.

## Where this is specified

- REQ-AUTH-002, 12 §12.4

