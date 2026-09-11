# ADR-0044 — `internal` is not a trust boundary: every datastore authenticates, and `web` leaves it

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [ADR-0042](0042-edge-is-not-internal.md), [ADR-0043](0043-blobstore-as-its-own-service.md),
[06 §6.7](../architecture/06-deployment-view.md), [06 §6.11](../architecture/06-deployment-view.md),
[12 §12.2](../architecture/12-security.md),
[`deploy/services.yaml`](../../deploy/services.yaml), `REQ-NFR-054`, `REQ-SEC-102`

## Context

The corpus is exhaustive about the outward boundary. Egress passes one proxy with a
per-plugin allowlist ([ADR-0027](0027-egress-enforcement.md)), every plugin has its own
segment ([ADR-0037](0037-per-plugin-network-segments.md)), the management port binds to
`internal` rather than merely going unpublished, and exactly two containers sit on a
non-internal segment ([ADR-0042](0042-edge-is-not-internal.md)).

Inside `internal` there was nothing at all.

**No datastore but PostgreSQL held a credential.** Searching the whole corpus —
[`deploy/services.yaml`](../../deploy/services.yaml), the variable table in
[06 §6.11](../architecture/06-deployment-view.md), the requirements — turns up
`db-password` and `db-migration-password` and no other. Not a Valkey password, not a
RabbitMQ user, not an OpenSearch client credential for `api` and `worker`, not an
identity on the `blobstore` gRPC port, and no TLS towards any of them.

Two of those are worse than a weakness:

- **RabbitMQ could not have worked.** Its built-in `guest` account is restricted to
  loopback connections. The generated stack would not have started, which is a
  functional defect the drift check could not catch because the credential was missing
  from the source it checks against.
- **`blobstore` holds every tenant's media** and had no access control beyond its port
  being on `internal` ([ADR-0043](0043-blobstore-as-its-own-service.md)). Its own
  contract — the `BlobStore` gRPC service — is served over mTLS with a pinned
  fingerprint by the *plugin* adapters (`REQ-SEC-056`). The in-deployment implementation
  of the same contract had nothing.

**And `web` was a full member of the segment.** The one container with a published port,
the one an attacker reaches first, could open `postgres:5432`, `valkey:6379` — which
holds every session — `opensearch:9200`, `rabbitmq:5672`, `blobstore:8100`, and `:8090`
on both core roles, the management listener whose whole protection is that it binds to
`internal`.

[ADR-0042](0042-edge-is-not-internal.md) and `REQ-SEC-102` defend this with *"neither
holds data, a credential or domain logic"*. That is true of what `web` **holds** and
says nothing about what it can **reach** — which is precisely the distinction
[ADR-0037](0037-per-plugin-network-segments.md) drew for plugins ("unpublished is not
unreachable") and nobody applied to the ingress. The connectivity suite asserts six
properties, all of them about plugins, and none about `web`.

## Options

| Option | For | Against |
|---|---|---|
| **Authenticate every datastore *and* move `web` off `internal`** | Reachability stops being authorisation; the ingress reaches the API and nothing else; the claim in `REQ-SEC-102` becomes true of reach as well as of contents | Four new secrets, one new segment, one new generated ACL file and one OpenSearch security configuration — and an exception to the `*_FILE` rule, below |
| Network only: `web` gets its own segment, datastores stay open | Small diff | RabbitMQ still does not start. And `api`, `worker` and `migrate` remain mutually unauthenticated on a segment whose membership will grow |
| Credentials only: `web` stays on `internal` | A compromised nginx sees ports, not data | Gives up the defence in depth that the same corpus insists on one segment over, for a container that needs exactly one neighbour |
| Declare `internal` trusted and document it | No work | It is a claim with no test, which is the shape [14 §14.3](../architecture/14-quality-risks-glossary.md) names as risk **R16** |

## Decision

### 1. `internal` is a network, not a trust boundary

Every service on it authenticates its callers:

| Service | Mechanism | Secret |
|---|---|---|
| `postgres` | password per role, unchanged | `db-password`, `db-migration-password` |
| `valkey` | ACL user `homeinv`; `default` **disabled** | `valkey-password` |
| `rabbitmq` | user `homeinv`; `guest` unusable anyway | `mq-password` |
| `opensearch` | client user `homeinv`, separate from `admin`, over TLS | `search-password`, `mtls-search` |
| `blobstore` | **gRPC over mTLS with a pinned fingerprint** — the same mechanism the plugin `BlobStore` adapters already use | `mtls-blobstore`, `mtls-core` |

### 2. `web` moves to a two-member segment

A new `frontend` segment holds exactly `web` and `api`. `web` is no longer on
`internal`; `api` is on both. The hop of [ADR-0042](0042-edge-is-not-internal.md) is
unchanged in behaviour and now has a segment of its own, so what the ingress can reach
is a property of the topology rather than of the reader's goodwill.

### 3. The URL signing key exists

`REQ-MED-010` requires signed, short-lived media URLs from stage 0 and
[08 §8.2](../architecture/08-api-contract.md) requires an opaque, **signed** pagination
cursor. Neither named a key, and the only key in the matrix was `jwt-key`, an identity
key. `url-signing-key` is added and is deliberately separate: a URL signature should not
be forgeable by anything that can mint a session token.

### 4. One exception to the `*_FILE` rule, and it is named

[06 §6.11](../architecture/06-deployment-view.md) rule 3 and `REQ-NFR-054` require every
secret to arrive as a **file**, never as an environment variable holding the value. The
OpenSearch image does not implement that convention: it reads
`OPENSEARCH_INITIAL_ADMIN_PASSWORD` and knows no `_FILE` variant — the convention belongs
to the Docker Official Images (postgres, mysql), not to every image. The matrix declared
`OPENSEARCH_INITIAL_ADMIN_PASSWORD_FILE`, a variable the image ignores, so the security
plugin would have started with no admin password while `DISABLE_INSTALL_DEMO_CONFIG` also
denied it certificates — and the health check called `https://localhost:9200`.

`REQ-NFR-054` therefore gains a **closed exception list**, in the shape
[07 §7.1](../architecture/07-data-model.md) uses for instance-wide tables: one entry,
OpenSearch, with the reason. The generator reads the secret file and injects the value.
A second entry needs a row there and a sentence saying why the image cannot read a file.

A wrapper image was rejected for the reason [ADR-0036](0036-scanner-egress.md) rejected
one for ClamAV: it would put our rebuild, scan and signature between an upstream image and
its own security configuration.

## Rationale

The argument is the one this project has already made twice. RLS exists because
application authorization can be wrong ([ADR-0003](0003-multi-tenancy.md)); per-plugin
segments exist because "no host mapping" is not "no neighbour"
([ADR-0037](0037-per-plugin-network-segments.md)). A flat, mutually-trusting `internal`
segment is the same assumption a third time, in the one place nobody looked — and it was
carrying the internet-facing container.

The cost is honest: four secrets, one segment, two generated configuration files. None of
it changes a resource budget, because no service is added.

## Consequences

- **`REQ-SEC-102` still holds and now means more.** Exactly two containers sit on a
  *non-internal* segment; `frontend` is internal, so the count is unchanged. What changes
  is that the sentence about `web` holding nothing is now also a sentence about what it
  can open.
- **The connectivity suite gains its first assertion about `web`**: from the `frontend`
  segment, `postgres`, `valkey`, `rabbitmq`, `opensearch`, `blobstore` and `:8090` on both
  core roles must all refuse. A segment flag is a claim; a refused connection is evidence.
- **`REQ-NFR-054` gains a closed exception list** with one entry.
- **New requirements**: `REQ-SEC-104` (datastore authentication), `REQ-SEC-105` (`web`
  reaches only `api`), `REQ-SEC-106` (the URL signing key).
- **A third database role appears**, `homeinv_housekeeping`, for the retention deletions
  of [ADR-0046](0046-truncatable-audit-chain.md) — `homeinv_app` has only `INSERT` and
  `SELECT` on `audit` and cannot be the role that enforces a retention period.
- **Operational cost**: five more values in the operator's secret store, and the
  installation guide gains a generation step for the two mTLS identities. The
  fingerprints are pinned in configuration, exactly as a plugin registration pins one, so
  no certificate authority is introduced.
- **13 §13.6 gains nothing.** A datastore that refuses an unauthenticated caller is not a
  new degradation level; a misconfigured credential is a startup failure, which
  `REQ-NFR-046` already covers.
