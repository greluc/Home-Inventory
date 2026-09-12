# deploy/

Everything needed to run Home Inventory.

| Path | What it is |
|---|---|
| [`services.yaml`](services.yaml) | **The source of truth** for the deployment topology |
| [`quadlet/`](quadlet/) | Podman rootless — systemd units, **generated** |
| [`compose/`](compose/) | Docker rootless — `compose.yaml`, **generated** |
| [`generated/`](generated/) | Files that must be *inside* a container — **generated** |
| [`images/`](images/) | The one derived image: PostgreSQL with the role scripts baked in |
| [`postgres/initdb/`](postgres/initdb/) | The role definitions the whole isolation design rests on |
| [`expected/`](expected/) | Fixtures the generator is compared against |
| [`helm/`](helm/) | Kubernetes — hand-written, **validated** against the matrix |
| [`setup.sh`](setup.sh) | **The one command**: host checks, secrets, templates, `.env`, and up |
| [`smoke/`](smoke/) | What only a running stack can prove — segments and the journey |

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

## Getting one running

```sh
./deploy/setup.sh check       # the host prerequisites, changing nothing
./deploy/setup.sh docker      # rootless Docker with Compose v2
./deploy/setup.sh podman      # rootless Podman with Quadlet
```

It generates the secrets it does not already find — never overwriting one — and
writes `compose/.env`. **Set `HOMEINV_BOOTSTRAP_EMAIL` in that file before the
first start**: the one-shot `bootstrap` service creates the first owner with it
([ADR-0053](../docs/adr/0053-first-owner-as-a-one-shot.md)), once, and afterwards
finds the account and does nothing. The password is in
`secrets/bootstrap-password`; read it once and store it somewhere that is not this
directory.

## What a running stack is checked against

`smoke/` holds the two things a description cannot establish, both run by
[`.github/workflows/smoke.yml`](../.github/workflows/smoke.yml) under **both**
runtimes:

| Script | What it proves |
|---|---|
| [`connectivity.sh`](smoke/connectivity.sh) | The segments refuse what the matrix says they refuse. A flag in a file is a claim; a refused connection is evidence ([ADR-0044](../docs/adr/0044-internal-is-not-a-trust-boundary.md)) |
| [`journey.sh`](smoke/journey.sh) | An item can be created, photographed, stored and found again — through the published port, with a real malware scanner. Stage 0's definition of done |

## Status

`generate.py` renders 30 files from the matrix and CI compares every one of them.
Image digests still read `TODO` on purpose — a tag in their place would quietly
defeat the digest-pinning rule, and they are filled in when the first images are
published.
