<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `last-owner`

**Type URI:** `https://home-inv.example/problems/last-owner`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

The change would leave the tenant without an owner — the last one cannot be demoted or removed.

## Notes

A tenant with no owner is stranded rather than degraded: nobody can invite into it, promote anybody or delete it, and the instance operator cannot either, because operating the instance grants nothing inside a tenant (ADR-0057). The way out is to make somebody else an owner first.

## Where this is specified

- REQ-TEN-005, REQ-TEN-011, ADR-0057

