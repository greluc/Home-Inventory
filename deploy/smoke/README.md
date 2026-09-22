# deploy/smoke/

What a running stack proves and a description cannot.

Three scripts, run against a stack that `deploy/setup.sh` has just started:

| Script | What it proves |
|---|---|
| [`connectivity.sh`](connectivity.sh) | The segments refuse what `services.yaml` says they refuse, every store demands a credential, and `api` and `worker` reach nothing outside the deployment (`REQ-SEC-099`/`102`/`104`/`105`, `REQ-PRIV-003`, `REQ-NFR-014`/`067`) |
| [`journey.sh`](journey.sh) | An item is created, photographed, stored and found again through the published port, with a real malware scanner |
| [`totp.sh`](totp.sh) | The second factor a journey needs, generated the way a phone would |

A flag in a file is a claim; a refused connection is evidence. That distinction
is [ADR-0044](../../docs/adr/0044-internal-is-not-a-trust-boundary.md)'s, and it
is why these exist rather than another grep over `services.yaml`.

## What CI runs

[`smoke.yml`](../../.github/workflows/smoke.yml) runs all three under **rootless
Podman** and **rootless Docker** as a matrix, on the hosted runners' Ubuntu image
(`REQ-NFR-051`, `REQ-NFR-059`, `REQ-NFR-064`).

## What CI cannot run, and is therefore a release check

**A RHEL-family host with SELinux `enforcing`.** SELinux is a policy of the
**host kernel**: a container cannot switch it on, and the hosted runners are
Ubuntu — so no job there can prove anything about `enforcing`, however it is
written. `REQ-NFR-064` was amended on 2026-09-20, with the owner's approval, to
ask CI for what CI can do and to make this a **release check with a recorded
result** instead.

Run it before a release, on a Fedora, CentOS Stream or RHEL host:

```bash
getenforce                     # must print: Enforcing
./deploy/setup.sh --runtime podman --profile minimal
./deploy/smoke/connectivity.sh podman && ./deploy/smoke/journey.sh podman
```

Then record **the date, the host's distribution and version, the output of
`getenforce`, and the outcome** in the release notes for that version. An
unrecorded run did not happen: the point of the check is the evidence, and
`REQ-NFR-064`'s acceptance now names this file.

What it is looking for is one failure mode in particular: a volume the container
cannot write because it carries no SELinux label. This deployment is expected to
be immune — it uses runtime-managed volumes and **no bind mounts**
([ADR-0022](../../docs/adr/0022-rootless.md),
[06 §6.2](../../docs/architecture/06-deployment-view.md)), and Podman labels
those itself — and "expected to be immune" is exactly the kind of statement that
is worth running once per release rather than believing.

`SecurityLabelDisable=true` makes every SELinux problem disappear, together with
a layer of defence. It is not used here, and [`ci.yml`](../../.github/workflows/ci.yml)
fails on it. A labelling problem means a bind mount that should not exist.

## `kind`

Also `REQ-NFR-064`, also stage 1, and not yet wired: the Helm chart it would run
against is empty ([ADR-0015](../../docs/adr/0015-deployment.md),
[06 §6.8](../../docs/architecture/06-deployment-view.md)). It lands with the
chart, in the same unit of work, because a `kind` job without one would assert
nothing.
