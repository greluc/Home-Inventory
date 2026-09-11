# ADR-0022 — Rootless as the mandatory basis for every way of running it

**Status:** Accepted · **Date:** 2026-09-11
**Tightens:** [ADR-0015](0015-deployment.md) — rootful Docker is thereby no longer
a supported path.

## Context

The system is reachable from the internet, separates tenants and executes foreign
plugin code ([12 Security](../architecture/12-security.md)). Until now the design
only required that the **processes inside the container** do not run as `root`
(`user: 10001`). The container daemon itself still ran as `root`.

Those are two different things, and conflating them is common:

| | What it prevents | What it does not prevent |
|---|---|---|
| Non-root **inside** the container | That an application bug may do anything inside the container | That an **escape** from the container lands as `root` on the host |
| Rootless **on the host** | That an escape has more privileges than an ordinary user | Kernel vulnerabilities |

With rootful Docker the daemon socket is additionally root-equivalent: whoever
reaches it can start a privileged container with the host filesystem.

## Options

| Option | For | Against |
|---|---|---|
| Non-root inside the container only (the previous state) | Simple, runs everywhere | An escape leads to `root` on the host; a root daemon as a permanent attack surface |
| Rootless **recommended**, rootful still supported | The lowest barrier for operators | The convenient path would stay the unsafe one — and that is the one people pick |
| **Rootless mandatory for every way of running it** | The blast radius of an escape is confined to an unprivileged user; no root daemon; the socket is not root-equivalent | Host setup required (`subuid`, `linger`, cgroup delegation); no ports below 1024; UID mapping on volumes |
| Additionally gVisor or Kata Containers | Even stronger isolation | Considerable operational effort and a performance cost for a load that does not justify it |

## Decision

**Rootless is mandatory.** Both levels apply simultaneously and without
exception:

1. **Non-root inside the container** — every shipped service runs as
   `10001:10001`, with `read_only`, `cap_drop: ALL`, `no-new-privileges` and
   resource limits.
2. **Rootless on the host** — rootless Podman (the default) or rootless Docker,
   under a dedicated unprivileged service user without `sudo` rights. Under
   Kubernetes: `runAsNonRoot`, the `restricted` profile, and `hostUsers: false`
   wherever the cluster version supports it.

**Rootful Docker and rootful Podman are no longer supported ways to run this.**

## Rationale

The gain is concrete, not theoretical: an escape from a plugin container — the
part of the system that executes foreign code — lands not as `root` on the VM but
as the user `homeinv` without `sudo`. It sees no other system services, no host
configuration and no other users. With rootful Docker the same escape would be a
complete takeover of the machine.

The price is one-off setup effort that can be scripted. With quality goal **Q1**,
that is a clear trade.

**Stated honestly, what rootless does not do:** the kernel remains the shared
boundary, and user namespaces themselves have had vulnerabilities. Rootless
prevents no application bug — it bounds what follows from one. It replaces
neither the network segmentation nor the plugins' capability model; it adds a
third, independent layer to both.

## Consequences

| Consequence | How it is handled |
|---|---|
| **No ports below 1024** | The application listens on 8080; TLS is terminated by the external reverse proxy. That was the topology anyway — rootless makes it mandatory. A `sysctl ip_unprivileged_port_start` is **not** required. |
| **UID mapping on volumes** | Runtime-managed volumes only, no bind mounts into host directories. The mapping mode is fixed per service, documented, and guarded by a "restart against an existing volume" test. |
| **Backup works differently** | `podman volume export` or a helper container instead of `cp` out of the storage directory — otherwise ownership is lost on restore. |
| **Host setup required** | `subuid`/`subgid`, `loginctl enable-linger`, cgroup v2 delegation. Scripted in the installation guide; the script **checks** the prerequisites and aborts with a clear message rather than running without resource limits. |
| **No access to host devices** | A label printer on a USB port is not readily reachable rootless. Network printers are the supported case; for USB devices the documentation describes the path through a `udev` rule and group membership. |
| **Slightly lower network performance** | User-space networking instead of a kernel bridge. Irrelevant at this load; with `pasta` (Podman ≥ 5) close to the rootful figure anyway. |
| **CI must test rootless** | A test that only runs rootful proves nothing. The smoke suite runs rootless under both runtimes. |
| **A plugin demanding privileges is not supported** | Stated in the plugin SDK and in the contract tests. |

## Verification

Binding in CI, each of them failing the build:

- No service description contains `privileged`, `cap_add`, `pid: host`,
  `network_mode: host`, `user: root`, `SecurityLabelDisable=true`, a mounted
  container socket, or a port below 1024.
- Every service sets `read_only`, `cap_drop: ALL`, `no-new-privileges`, a non-root
  user and resource limits.
- The smoke suite runs rootless under Podman **and** under Docker.
- For Kubernetes: `runAsNonRoot`, `allowPrivilegeEscalation: false`,
  `readOnlyRootFilesystem`, `seccompProfile: RuntimeDefault` in every manifest.
