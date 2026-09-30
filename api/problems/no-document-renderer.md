<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `no-document-renderer`

**Type URI:** `https://home-inv.example/problems/no-document-renderer`  
**HTTP status:** `409`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

A document was asked for and nothing installed can render one.

## Notes

A 409 and not a 501. The report exists and its figures are served by the two endpoints beside this one, as data and as a table; what is missing is a DocumentRenderer plugin, which an operator installs. A 501 would say this application cannot do it, which is not what is wrong -- and an empty file would be a worse answer than a refusal somebody can act on. The detail says where the figures are.

## Where this is specified

- REQ-LIFE-016: an insurance report is producible as a PDF and as a table
- ADR-0070: a document is described, not programmed, and rendering it is a plugin's job

