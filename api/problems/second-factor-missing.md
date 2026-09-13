<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `second-factor-missing`

**Type URI:** `https://home-inv.example/problems/second-factor-missing`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The role this session holds requires a second factor and the account has none; the way out is to enrol one.

## Notes

A 403 rather than a 401: the caller is authenticated, and what is missing is not a credential for this request but an authenticator on the account. REQ-AUTH-003 makes the factor mandatory for OWNER and ADMIN and for any role that reads a sensitive field; the membership is still granted — refusing it would leave a tenant nobody owns, because creating one makes an owner and the first owner of an instance is made by a one-shot with nobody to ask for a code. It is also the answer to granting sensitive field visibility to a role whose members have none, where the detail says how many and never who. `/api/v1/auth/**` and `/api/v1/me/**` keep answering, so the way out and the switch to another tenant are always reachable.

## Where this is specified

- REQ-AUTH-003, 12 §12.4

