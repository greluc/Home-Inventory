<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->


# `constraint-loosened`

**Type URI:** `https://home-inv.example/problems/constraint-loosened`  
**HTTP status:** `422`  
**Since:** stage 1

**Assigned.** In use and stable within this product. It is not published to an external registry, which changes nothing for a client: the URI is fixed for the product rather than derived from a deployment's hostname, so branching on it works across instances.

## What it means

An inheriting type widened a field it inherits. An inheriting type may tighten a field and may not loosen one.

## Notes

Named at publish time rather than when the field is written, because a draft is allowed to be incoherent while it is being edited; publishing is the moment it has to hold. The answer names the field and the property that was widened — `required`, `max`, `the pattern` — because "it loosens something" leaves a person to find it.

## Where this is specified

- REQ-CORE-024: an inheriting type may tighten fields but not loosen them

