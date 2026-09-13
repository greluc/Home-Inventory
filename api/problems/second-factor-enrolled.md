<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `second-factor-enrolled`

**Type URI:** `https://home-inv.example/problems/second-factor-enrolled`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The account already has a confirmed second factor.

## Notes

Enrolling again would replace a working authenticator with an unproven one, and somebody who then lost the new QR code would be locked out with a valid app on their phone. The way out is to remove the old one, which asks for a code.

## Where this is specified

- REQ-AUTH-002

