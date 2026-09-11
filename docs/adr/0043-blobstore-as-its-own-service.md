# ADR-0043 — The filesystem `BlobStore` gets its own in-deployment service

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [04 §4.1](../architecture/04-building-blocks.md),
[06 §6.7](../architecture/06-deployment-view.md),
[06 §6.10](../architecture/06-deployment-view.md),
[06 §6.13](../architecture/06-deployment-view.md),
[09 §9.2](../architecture/09-extensibility-and-plugins.md),
[`deploy/services.yaml`](../../deploy/services.yaml), `REQ-MED-009`

## Context

`filesystem` is the **default** `BlobStore` adapter and the **only** media
storage a `minimal` installation has ([ADR-0007](0007-media-storage.md),
[ADR-0026](0026-core-outbound-via-plugins.md): it stays in the core precisely
because it opens no socket).

A review on 2026-09-11 traced where its bytes actually go, and the answer was
nowhere:

| Where it should have appeared | What was there |
|---|---|
| [`deploy/services.yaml`](../../deploy/services.yaml) `volumes:` | `pgdata`, `osdata`, `mqdata`, `kvdata`, `cvddata` — **no blob volume** |
| `api` / `worker` mounts | none, `readOnlyRootFilesystem: true`, `tmpfs: ["/tmp"]` |
| [06 §6.7](../architecture/06-deployment-view.md) Persistence column | `api` **none**, `worker` **none** |
| [06 §6.13](../architecture/06-deployment-view.md) backup table | a row for *"Blobs (Nextcloud)"* and **none for the filesystem store** |

Meanwhile [04 §4.1](../architecture/04-building-blocks.md) draws the `BlobStore`
inside the **Stateful** group, and [06 §6.3](../architecture/06-deployment-view.md)
lists it as one of the three places state lives. Both are right about what it
is; the topology gave it no place to be it.

The consequence, had this shipped: in `minimal` — the profile for a small home
server, and the one stage 0 builds — every photo and every document would have
had nowhere to be written, and nothing to be restored from. That is quality goal
**Q5** (data integrity), failing in the profile with the fewest moving parts.

## Options

| Option | For | Against |
|---|---|---|
| A volume `blobdata`, mounted into `api` **and** `worker` | The smallest change — one volume, two mounts | **Breaks `REQ-NFR-008`.** Two `api` instances cannot share a local filesystem, so the application layer stops being horizontally scalable exactly where the default adapter is in use. It would also make `api` and `worker` stateful, contradicting [04 §4.1](../architecture/04-building-blocks.md) and [06 §6.7](../architecture/06-deployment-view.md) rather than fixing them |
| Make `filesystem` a plugin like `s3` and `nextcloud` | One mechanism for every `BlobStore` | Contradicts [ADR-0026](0026-core-outbound-via-plugins.md) on its own terms: what makes those two plugins is that they **leave the deployment**. This one does not. It would also make the default storage depend on the plugin runtime, which is stage 1 — while media upload is stage 0 |
| **A small in-deployment `blobstore` service holding the volume** | `api` and `worker` stay stateless and `REQ-NFR-008` survives · the volume has one owner, one UID mapping and one backup row · works unchanged in `ha` | One more container (~32 MB) and one more hop on the media path |
| Require S3 from the start | No local-storage problem at all | Forces a second service on the smallest installation — the exact barrier [ADR-0007](0007-media-storage.md) rejected, and it would leave `minimal` unable to store a photo without external infrastructure |

## Decision

**A `blobstore` service on the `internal` segment, holding the `blobdata`
volume.** The `filesystem` adapter **stays in the core** and talks to it over the
same gRPC `BlobStore` contract the `plugin-blobstore-*` plugins implement.

| Property | Value |
|---|---|
| Segment | `internal` only — no published port, no route out of the deployment |
| Port | 8100, gRPC, never published to the host |
| Volume | `blobdata`, runtime-managed, at `/var/lib/homeinv/blobs` |
| Layout | `sha256/<tenantId>/<hash>` — unchanged ([ADR-0032](0032-per-tenant-blob-addressing.md)) |
| Profiles | all three |
| Resources | 32 MB reserved, 128 MB limit |

### It is infrastructure, not a plugin

The distinction matters, and the design already has the precedent:

> `clamd` is a container in the deployment; the **`VirusScanner` adapter is
> in-core**; ClamAV is not a plugin, holds no manifest, needs no capability
> grant and gets no tenant consent — because it opens nothing outside the
> deployment ([ADR-0024](0024-malware-scan.md), [09 §9.2](../architecture/09-extensibility-and-plugins.md)).

`blobstore` is the same shape one port over. [ADR-0026](0026-core-outbound-via-plugins.md)
draws its line at connections that **leave the deployment**, and states so
explicitly so the distinction is not re-litigated: *"PostgreSQL, Valkey,
OpenSearch, RabbitMQ, ClamAV … are parts of the deployment."* `blobstore` joins
that list. It therefore does **not** sit on a plugin segment, carries no
manifest, and is not reachable by any plugin.

## Rationale

The deciding constraint is `REQ-NFR-008`, and it is not a detail — a stateless
application layer is what makes the `ha` profile, the rolling upgrade in
[06 §6.12](../architecture/06-deployment-view.md) and the Kubernetes `Deployment`
in [06 §6.8](../architecture/06-deployment-view.md) work at all. Mounting a
shared volume into `api` would have bought the smaller diff and paid for it with
the property three other chapters lean on.

The honest counterweight: for a single-instance `minimal` installation the extra
container buys nothing that a mount would not have bought, and costs a hop. It is
accepted because the alternative is a topology that is correct only until someone
runs a second `api` — and that failure appears as *media that exist on one
instance and 404 on the other*, which is the hardest class of bug to attribute.

## Consequences

- **One more container in every profile** (~32 MB reserved). The budget figures
  in [06 §6.7](../architecture/06-deployment-view.md), `REQ-NFR-009` and
  [`deploy/services.yaml`](../../deploy/services.yaml) include it.
- **`blobdata` gains a backup row** in [06 §6.13](../architecture/06-deployment-view.md),
  which the filesystem store never had. It is backed up through the runtime like
  every other volume — never by copying out of the storage directory, because the
  files belong to the `subuid` range ([ADR-0022](0022-rootless.md)).
- **One hop on the media path**, inside `internal`. Negligible against the
  WebDAV hop risk R11 already accepts for `standard`.
- **`api` and `worker` stay stateless**, and [04 §4.1](../architecture/04-building-blocks.md)'s
  Persistence column becomes true rather than aspirational.
- **It runs in every profile**, including `standard`, where media may be routed
  to `plugin-blobstore-nextcloud`. Print job artifacts ([10 §10.6](../architecture/10-identification-and-labels.md))
  and anything written before a plugin is granted still land here, and a
  profile-conditional service is not something the service matrix can express.
- **A new required variable**, `HOMEINV_BLOBSTORE_ENDPOINT`, defaulting to
  `blobstore:8100`. It is deployment configuration, not a secret.
- The `BlobStore` adapter contract test of [ADR-0007](0007-media-storage.md) now
  runs against **four** implementations rather than three, and the in-deployment
  one is the reference the others are compared to.
