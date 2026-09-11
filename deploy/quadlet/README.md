# deploy/quadlet/

Podman rootless — systemd units (`.container`, `.network`, `.volume`).

> ## Generated. Do not edit by hand.
>
> These files come from [`../services.yaml`](../services.yaml). A hand-written
> change here fails the drift check in CI, and would be overwritten on the next
> generation anyway. Edit the matrix instead.

## Why Podman is named first

There is **no daemon**. `systemd --user` starts, supervises and stops the
containers directly, so the permanently running attack surface of a root daemon
does not exist. Dependencies, restart rules, resource limits and logs come from
the init system — the same tool the operator already uses for every other service
on the machine ([ADR-0021](../../docs/adr/0021-podman-quadlet.md)).

## Installing

Units belong in `~/.config/containers/systemd/` of the unprivileged service user.
Requires **Podman ≥ 5.0**; the version matrix per distribution and the host
prerequisites are in
[06 §6.2 and §6.3](../../docs/architecture/06-deployment-view.md).

```bash
systemctl --user daemon-reload
systemctl --user start homeinv-api
journalctl --user -u homeinv-api -f
```

## Three things that bite

| | |
|---|---|
| **`loginctl enable-linger`** | Without it the services end at logout and do not start at boot. By far the most common rootless mistake. |
| **cgroup v2 delegation** | Without delegated `memory` and `cpu` controllers, the resource limits in the `[Service]` sections silently do nothing. |
| **`SecurityLabelDisable=true`** | Makes every SELinux problem disappear, and a layer of defence with it. Never used here; CI rejects the key. An SELinux labelling problem means there is a bind mount that should not exist. |

## Status

Empty. Generated once there is an image to run.
