# deploy/compose/

Docker rootless — `compose.yaml`.

> ## Generated. Do not edit by hand.
>
> This comes from [`../services.yaml`](../services.yaml). A hand-written change
> fails the drift check in CI. Edit the matrix instead.

## Rootless Docker, not rootful

```bash
dockerd-rootless-setuptool.sh install
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/docker.sock
systemctl --user enable --now docker
```

Rootful Docker is **not** a supported way to run this
([ADR-0022](../../docs/adr/0022-rootless.md)). The daemon runs as `root`, and
whoever reaches its socket is effectively `root` on the host — for a system that
is reachable from the internet and executes third-party plugin code, that is a
blast radius with no justification, especially when rootless costs nothing in
function.

## What this file deliberately never contains

`privileged`, `cap_add`, `pid: host`, `network_mode: host`, `user: root`, a
mounted `docker.sock`, a port below 1024, or a bind mount into a host directory.
CI checks for each of them, and a finding fails the build (REQ-SEC-084).

## Differences from rootful that operators notice

| | |
|---|---|
| Ports below 1024 | Not bindable — hence 8080, with TLS at the reverse proxy |
| Storage | `~/.local/share/docker`, not `/var/lib/docker` |
| `docker.sock` | Under `$XDG_RUNTIME_DIR`, and no longer root-equivalent |
| Network performance | Slightly lower; irrelevant at this load |

## Status

Empty. Generated once there is an image to run.
