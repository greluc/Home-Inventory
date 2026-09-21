<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `federated-address-unverified`

**Type URI:** `https://home-inv.example/problems/federated-address-unverified`  
**HTTP status:** `403`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The provider did not confirm the address it reported, so no account can be created from it.

## Notes

An unverified address is a claim about somebody else: a provider that lets a person type any address and hands it on unverified would otherwise create an account here in that person's name. The core treats an unverified address as absent, which is what the `IdentityProvider` port's own contract says.
It refuses only the CREATION. An identity already linked to an account signs in whatever the address says, because the link is on `(issuer, subject)`.

## Where this is specified

- REQ-AUTH-004, REQ-AUTH-006

