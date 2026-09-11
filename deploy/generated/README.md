# deploy/generated/

Files that have to be **inside** a container and cannot be an environment
variable — rendered from [`../services.yaml`](../services.yaml) by
[`../generate.py`](../generate.py), committed, and compared in CI like everything
else it generates.

| File | For | Source |
|---|---|---|
| `clamav-freshclam.conf` | the scanner's update settings | `services.clamav.egress`, plus the proxy's port and its deployment allowlist entry |
| `valkey-users.acl` | the Valkey ACL | `services.api.env.HOMEINV_VALKEY_USER` |
| `opensearch-internal_users.yml` | the OpenSearch accounts | the secret names those accounts use |

## Why they are delivered as secrets

A read-only root filesystem and the no-bind-mount rule
([ADR-0022](../../docs/adr/0022-rootless.md)) leave exactly one mechanism that
all three runtimes have for getting a file into a container: the secret mount —
`podman secret … type=mount`, a Docker secret, a Kubernetes `ConfigMap`.

Two of these are not secret at all. Only the delivery path is shared with one.

## The two with a placeholder in them

`valkey-users.acl` and `opensearch-internal_users.yml` contain `@SECRET:<name>@`
where a password belongs. No password is in this repository and none ever will
be, so what is generated is a **template**; [`../setup.sh`](../setup.sh)
substitutes the operator's secrets and writes the result into `../secrets/`,
which is git-ignored.

Everything that is a *decision* — which accounts exist, what they may do, that
Valkey's `default` user is off — is generated here and therefore checked. Only
the values that must not be checked in are left out.

## `clamav-freshclam.conf` has a fixture

[`../expected/clamav-freshclam.conf`](../expected/clamav-freshclam.conf) was
written **before** the generator existed, so that the generator had something to
be wrong against rather than something to invent. `generate.py` compares its own
output against it on every run; a renderer change that alters the scanner's
configuration fails immediately rather than at an operator's site.
