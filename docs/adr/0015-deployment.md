# ADR-0015 — Docker Compose primary, Helm maintained as an equal

**Status:** Accepted, **partially superseded** · **Date:** 2026-09-11

> **Amended by [ADR-0021](0021-podman-quadlet.md)** (Podman with Quadlet is now
> supported as an equal) and **tightened by [ADR-0022](0022-rootless.md)**
> (rootless is mandatory, rootful Docker is no longer supported). The affected
> rows are marked below. Everything else stands unchanged.

## Context

The target environment is a Proxmox VM with Docker. The project is published
under the AGPL, and third parties should be able to run it. The full assessment
of all deployment options is in
[06 §6.9](../architecture/06-deployment-view.md).

## Decision

| Path | Status |
|---|---|
| **Docker Compose** | **Primary.** Supported, documented, tested in CI. → *Since [ADR-0022](0022-rootless.md) rootless only; since [ADR-0021](0021-podman-quadlet.md) an equal alongside Podman/Quadlet.* |
| **Helm chart** | Maintained as an equal, tested in CI against `kind` |
| Podman + Quadlet | ~~Documented as working, but not maintained separately~~ → **superseded by [ADR-0021](0021-podman-quadlet.md): supported as an equal, tested in CI, named first in the documentation** |
| Bare metal | **Not supported.** The expected third-party versions and environment variables are documented, nothing more. |
| Nomad | Not supported |

## Rationale

Compose fits the target environment, the expectations of self-hosters and the
available operational effort. The Helm chart is nevertheless maintained as an
equal, because it is the path to high availability and because it disciplines the
architecture: what runs in Kubernetes is necessarily stateless, cleanly
configured and has working health endpoints.

Bare metal is not supported because the application is not the problem —
PostgreSQL with `ltree`, OpenSearch, RabbitMQ and Valkey in matching versions
are. That shifts reproducibility problems onto the user and produces bug reports
nobody can recreate.

## Consequences

- **One container image** for `api` and `worker`, distinguished only by the
  Spring profile. That keeps the version matrix at one.
- Configuration exclusively through environment variables; secrets through files
  (`*_FILE`). **A missing secret aborts startup** — there is no generated default
  key.
- The application makes no assumptions about local files; all state lives in
  PostgreSQL, in the `BlobStore` or in Valkey.
- Three operating profiles (`minimal`, `standard`, `ha`) change only the active
  adapters, never the domain logic. `minimal` is tested in CI so that it does not
  decay.
- Containers ship hardened: `read_only`, without root privileges,
  `cap_drop: ALL`, `no-new-privileges`, with resource limits, referenced by digest
  and signed. → *Since [ADR-0022](0022-rootless.md) additionally **rootless on the
  host**, not merely non-root inside the container.*
- Three network segments (`edge`, `internal`, `plugins`); the core has **no**
  outbound route to the internet.
- Migrations are backward compatible (the three-step across three releases), so a
  rollback stays possible without restoring data.
