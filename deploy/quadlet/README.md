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

The `.container`, `.volume` and `.network` files belong in
`~/.config/containers/systemd/` of the unprivileged service user; the
`homeinv-<profile>.target` files belong in `~/.config/systemd/user/`, because
they are ordinary systemd units and Quadlet reads only its own directory.
`deploy/setup.sh podman` puts each where it goes. Requires **Podman ≥ 5.0**; the
version matrix per distribution and the host prerequisites are in
[06 §6.2 and §6.3](../../docs/architecture/06-deployment-view.md).

```bash
deploy/setup.sh podman minimal        # installs everything and starts the profile

systemctl --user daemon-reload
systemctl --user start homeinv-minimal.target
journalctl --user -u homeinv-api -f
```

A target rather than a service: `systemctl --user start homeinv-api` starts `api`
and the six units `api` requires, which leaves `web` down — and `web` holds the
only published port in the deployment. The target names every service in the
profile, so it is reached when the deployment is running and not before.

## Three things that bite

| | |
|---|---|
| **`loginctl enable-linger`** | Without it the services end at logout and do not start at boot. By far the most common rootless mistake. |
| **cgroup v2 delegation** | Without delegated `memory` and `cpu` controllers, the resource limits in the `[Service]` sections silently do nothing. |
| **`SecurityLabelDisable=true`** | Makes every SELinux problem disappear, and a layer of defence with it. Never used here; CI rejects the key. An SELinux labelling problem means there is a bind mount that should not exist. |

## Status

Empty. Generated once there is an image to run.
