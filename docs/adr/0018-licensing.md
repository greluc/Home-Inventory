# ADR-0018 — AGPL-3.0-or-later for the core, Apache-2.0 for the plugin API

**Status:** Accepted · **Date:** 2026-09-11

## Context

The project will be published. At the same time a plugin ecosystem should emerge
that third parties take part in.

## Decision

| Component | Licence |
|---|---|
| Core (backend, frontend, apps, Helm, Compose, Quadlet) | **AGPL-3.0-or-later** |
| `homeinv-plugin-api`, `homeinv-plugin-sdk-*`, `home_inv.plugin.v1` (protobuf), `homeinv-plugin-testkit` | **Apache-2.0** |
| Documentation | CC BY-SA 4.0 |
| Example plugins and example configurations | Apache-2.0 |

## Rationale

**AGPL for the core**, because the project is operated as a network service:
without the network clause somebody could extend the core, offer it as a service
and give nothing back. That is precisely the case the AGPL was written for.

**Apache-2.0 for the plugin API**, because the AGPL would otherwise risk reaching
into plugins that compile against it. A plugin author must be free to choose
their licence — including proprietary, for example for a hardware integration.
Apache-2.0's explicit patent grant is an additional advantage over MIT.

The separation is enforced **structurally**: the plugin API lives in its own
modules with its own licence file, has **no** dependency on core modules, and a
CI check ensures that. Without that separation the promise would be worthless.

## Consequences

- `LICENSE` (AGPL-3.0) at the repository root, `LICENSE` (Apache-2.0) in every
  plugin API module, REUSE-compliant per-file marking (SPDX headers).
- **Dependency checking in CI:** no dependency with an AGPL-incompatible licence
  in the core; in the plugin API module permissive licences only. A violation
  fails the build.
- The AGPL requires that users of the network service can obtain the source.
  Implementation: a link in the UI to `https://github.com/greluc/Home-Inventory`
  and the exact version (commit hash), reachable from every installation.
- Contributions require **DCO** (`git commit -s`) **and a CLA**. The DCO attests
  provenance, the CLA grants the rights that make a later relicensing or a
  commercial dual licence possible at all. The consequences that come with it: a
  noticeable barrier for contributors, an automated signing flow in the pull
  request (a CLA assistant), a durable record of signatures, and a separate path
  for contributions made on behalf of an employer.
- `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` and a maintained
  `CHANGELOG.md` are part of publication.
- A CycloneDX SBOM per release, so operators can audit their supply chain.
- The AGPL may deter contributors (risk R12). The separated plugin API and a
  clear explanation in `CONTRIBUTING.md` are the countermeasure.
