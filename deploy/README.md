# deploy/

Everything needed to run Home Inventory.

| Path | What it is |
|---|---|
| [`services.yaml`](services.yaml) | **The source of truth** for the deployment topology |
| [`quadlet/`](quadlet/) | Podman rootless — systemd units, **generated** |
| [`compose/`](compose/) | Docker rootless — `compose.yaml`, **generated** |
| [`helm/`](helm/) | Kubernetes — hand-written, **validated** against the matrix |

## The one rule

`services.yaml` is edited; the Quadlet units and `compose.yaml` are not. They are
generated from it, and a hand-written deviation fails the drift check in CI.

The reason is risk R13: two hand-maintained descriptions of the same topology
diverge — and the divergence shows up at an operator's site, on the runtime that
was not tested. The Helm chart is too different in shape to generate, so it is
validated instead: same services, same environment keys, same limits.

## Rootless, without exception

No supported path runs a container daemon or a container as `root` on the host
([ADR-0022](../docs/adr/0022-rootless.md)). That is not a hardening preference;
several things in the design follow from it, including why the application
listens on 8080 and why there are no bind mounts anywhere in this directory.

Host prerequisites, the per-distribution differences and the version matrix:
[06 Deployment View](../docs/architecture/06-deployment-view.md).

## Status

Seed. The matrix reflects the architecture; nothing generates from it yet,
because there is nothing to deploy. Image digests read `TODO` on purpose — a tag
in their place would quietly defeat the digest-pinning rule.
