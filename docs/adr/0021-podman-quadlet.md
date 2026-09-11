# ADR-0021 — Podman with Quadlet as an equally supported way to run it

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [ADR-0015](0015-deployment.md), the *Podman* row — classified there as
"not maintained separately".

The remaining decisions of ADR-0015 stand, except where
[ADR-0022](0022-rootless.md) tightens them — a statement about ADR-0015, not a second
entry on the `Amends:` line above, which is why it sits outside it.

> **Amended by [ADR-0027](0027-egress-enforcement.md)**: one service is added to the
> topology, the `egress-proxy`. Back-link added 2026-09-11 — it was the reciprocal of an
> `Amends:` that had been declared for months and never recorded here, which is the gate
> **A4b** exists to catch.

## Context

ADR-0015 made Docker Compose the only supported container path and documented
Podman merely as "also works". Two reasons argue for raising Podman with Quadlet
to the same rank:

1. **Rootless has become mandatory** ([ADR-0022](0022-rootless.md)). Podman is
   designed rootless and daemonless; with Docker, rootless is a retrofitted path
   that works but is less travelled.
2. **Quadlet replaces the daemon with systemd.** On a Linux VM with systemd that
   is the more natural form: dependencies, restart rules, resource limits, logs
   and start order come from the init system — the very tool the operator uses
   for every other service on the machine anyway.

## Options

| Option | For | Against |
|---|---|---|
| Compose only (as in ADR-0015) | One artifact to maintain, the widest adoption | Presupposes a daemon; rootless Docker is the less travelled path |
| Podman/Quadlet only | No daemon, systemd integration, rootless by design | Shuts out the large group of Docker users; Quadlet needs Podman ≥ 4.4 |
| **Both as equals** | The operator picks what they know; the safer variant is named first | **Two artifacts for the same topology** — a drift hazard |
| `podman-compose` as a bridge | One artifact for both | Covers Compose only incompletely; gives away precisely the systemd advantages that motivate choosing Podman |

## Decision

**Podman ≥ 5.0 rootless with Quadlet and rootless Docker with Compose v2 are both
supported, documented and CI-tested standard paths.** Podman is named first in the
documentation, because it needs no daemon.

`podman-compose` is **not** used — whoever chooses Podman gets Quadlet.

## Drift protection

Without a countermeasure, two descriptions of the same topology diverge.
Therefore:

1. **One service matrix** (`deploy/services.yaml`) is the source of truth: image
   with digest, role, ports, networks, volumes, environment keys, secrets,
   resource limits, health check, dependencies.
2. **Quadlet units and `compose.yaml` are generated from it.** Both are flat and
   mechanically derivable. A hand-written deviation fails a CI comparison.
3. The Helm chart is not generated but **validated** against the same matrix.
4. **The same smoke suite** runs against all three ways of running it.

Without points 1 and 2 this decision would not be tenable — two hand-maintained
topology descriptions are a matter of time, not of intent.

## Consequences

- **Minimum version Podman 5.0** (Quadlet from 4.4, `pasta` as the default
  network from 5.0). Verified on 2026-09-11 across two package families: Debian 13
  (5.4.2), Ubuntu 26.04 (5.7.0), Fedora 43–45 (5.8.4–6.1.0), RHEL/CentOS
  Stream/Rocky/AlmaLinux **9.5+ and 10** (5.0 resp. 5.6). Unsuitable: Debian 12
  (4.3.1), Ubuntu 22.04 (3.4.4). Limited (Quadlet yes, `pasta` no): Ubuntu 24.04
  and the RHEL family 9.4 and older. The full matrix is in
  [06 §6.2](../architecture/06-deployment-view.md).
- **On the RHEL family Podman follows the minor release**, because container
  tools come from a rolling AppStream there. The floor is therefore stated as a
  minor release (9.5 resp. 10), not as a Podman number.
- **`uidmap`, `passt` and `dbus-user-session` are only `Recommends` of Debian's
  `podman`.** An install with `--no-install-recommends` yields no `newuidmap` and
  therefore no rootless operation. They are installed explicitly and verified at
  startup — together with `aardvark-dns`, without which containers do not resolve
  each other by name. On the RHEL family the same components are hard
  dependencies or part of the base install; there `firewalld` has to be opened
  instead.
- **SELinux (`enforcing` on the RHEL family) causes no work here**, because the
  rule "runtime-managed volumes only, no bind mounts" from
  [ADR-0022](0022-rootless.md) already applies — Podman labels named volumes
  correctly itself. The same rule thus solves two different problems on two
  families. **`SecurityLabelDisable=true` is never used**; CI rejects the key.
- The Quadlet units live **in the repository**, not only on the host, and are part
  of the versioned deployment.
- Start order through `Requires=`/`After=` with `Notify=healthy` — more reliable
  than Compose `depends_on` with a health check.
- Logs go to **journald** under Podman; the operations documentation gives the
  right commands for each way of running it.
- Resource limits come from the `[Service]` section (cgroup v2). That requires
  delegation of the `memory` and `cpu` controllers; the setup script checks it and
  aborts otherwise.
- `AutoUpdate=disabled` on all units: images are digest-pinned, an update is
  always a deliberate act.
- Volumes are managed exclusively by the runtime; backup goes through
  `podman volume export` or a helper container, **never** by copying out of the
  storage directory (the files belong to the `subuid` range).
- Extra effort in CI: the smoke suite runs twice. That is the price of both paths
  actually working rather than merely being claimed to.
