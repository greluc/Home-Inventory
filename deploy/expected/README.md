# `deploy/expected/` — the artifacts the generator must produce

[`../services.yaml`](../services.yaml) is the source of truth for the deployment
topology. The Quadlet units, `compose.yaml` and the per-service config files are
**generated** from it, and the Helm chart is validated against it
([ADR-0021](../../docs/adr/0021-podman-quadlet.md), `REQ-NFR-063`).

This directory holds the **expected output** for artifacts whose exact content is
already decided but whose generator does not exist yet. Each file is a fixture:
CI compares the generator's output against it byte for byte, and a difference
fails the build in either direction — a generator that drifts, or a fixture
somebody edited instead of changing the source.

It exists because of guiding principle 3 in
[01 §1.5](../../docs/architecture/01-introduction-and-goals.md) — *contracts
before implementations*. Writing the artifact down now gives the generator
something to be wrong against; describing it in prose would give it something to
invent.

| File | Source | Consumed by | Decided in |
|---|---|---|---|
| [`clamav-freshclam.conf`](clamav-freshclam.conf) | `services.clamav.egress` | `clamav`, mounted read-only at `/etc/clamav/freshclam.conf` | [ADR-0036](../../docs/adr/0036-scanner-egress.md), A6 |

## Rules

- **Never hand-edit a fixture to make a build pass.** The fixture is the
  specification; if the generator disagrees, one of the two is wrong and the
  question is which — that is the point of comparing them.
- **A fixture changes only together with its source** in `services.yaml`, in the
  same unit of work, exactly like the generated artifacts themselves.
- **Nothing secret lands here.** These files are world-readable and are
  configuration, not credentials. A value that cannot be written without a secret
  is not generated into a fixture — it is a secret reference.
