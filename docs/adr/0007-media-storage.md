# ADR-0007 — A BlobStore port with filesystem, S3 and Nextcloud adapters

**Status:** Accepted · **Date:** 2026-09-11

## Context

Photos and documents are the largest share of the data (on the order of 700 GB at
1 M items). The operator runs a Nextcloud instance and wants to use it as
storage. Other operators have a filesystem or S3.

## Options

| Option | For | Against |
|---|---|---|
| Filesystem only | The simplest operation, the simplest backup | Blocks horizontal scaling without a shared volume |
| S3/MinIO only | Uniform, stateless app containers, good scaling | One more service even for the smallest installation |
| The database (bytea / large objects) | One backup artifact, transactional consistency | Bloats the database massively, poor performance, unusable at this data volume |
| **A port with several adapters** | Every operator uses what they have; switching is configuration | Three adapters to maintain and test |

## Decision

The **`BlobStore` port** with three shipped adapters:

| Adapter | Use |
|---|---|
| `filesystem` | Default, small installations, development |
| `s3` | S3-compatible (MinIO, Garage, Backblaze) for scaling and Kubernetes |
| `nextcloud` | WebDAV against an existing Nextcloud — **the path this installation takes** |

## Rationale

Media storage is the textbook case for a port: several real, genuinely different
implementations behind a narrow interface (`put`, `get`, `delete`, `exists`,
`presignedUrl`). The Nextcloud integration is a user requirement with real value
— photos end up in a store that is backed up, synchronised and shared anyway.

## Consequences

- All blobs are **content-addressed** (`sha256/<hash>`). That makes uploads
  idempotent, saves storage on duplicates, and is the prerequisite for offline
  catch-up.
- Reference counting decides deletion; a weekly run removes unreferenced blobs
  **with a grace period**, never immediately.
- **WebDAV is slower per file than a local filesystem** (risk R11). Countermeasures:
  a local cache for derivatives, asynchronous uploads, batching. Switching to S3
  is a configuration change plus a data move.
- Nextcloud access exclusively through an **app password with access to exactly
  one folder** — never the main password.
- Delivery to clients only through signed, short-lived URLs; no adapter places
  files in a publicly reachable directory.
- One adapter contract test applies to all three equally, so they behave
  identically — including on errors, partial uploads and unusual characters.
