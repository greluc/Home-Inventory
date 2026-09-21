<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0074 — Granting a storage plugin routes the next bytes and moves none of the old ones

**Status:** Accepted · **Date:** 2026-09-21

**Amends:** [ADR-0007](0007-media-storage.md) (*"switching is configuration"* — this record
says what switching does to what is already stored)

**Depends on:** [ADR-0026](0026-core-outbound-via-plugins.md),
[ADR-0032](0032-per-tenant-blob-addressing.md),
[ADR-0043](0043-blobstore-as-its-own-service.md),
[ADR-0064](0064-the-ports-a-plugin-implements-are-apache.md),
[ADR-0065](0065-the-plugin-call-envelope.md)

## Context

`REQ-MED-009` says storage is replaceable through the `BlobStore` port, and every part of
that sentence existed except the one that does the replacing. The port is declared in
`plugin-api`, a manifest may name it under `implements`, the capability set has the entry —
and there was **no `PortAdapter<BlobStore>`**. A tenant that installed an S3 plugin would
have seen it registered, granted and listed, and every photograph would still have gone into
the deployment's own store. Nothing said so, because nothing asked.

Wiring the adapter forces a second question that ADR-0007 never had to answer, because a
core adapter was chosen once per deployment and a plugin is granted **per tenant, at any
time**: a tenant that has been uploading for a year grants an S3 plugin this afternoon. What
happens to the year?

## Options

| Option | A photograph from last year | An upload today | The tenant's own bucket |
|---|---|---|---|
| **Route writes, read from both** | still served | goes to the plugin | holds everything from today |
| Route everything, no fallback | **404, silently** | goes to the plugin | holds everything from today |
| Copy on grant | served after the copy | goes to the plugin | holds everything |
| Keep writing to both | served | written twice | a partial copy nobody asked for |

"Route everything" is the shortest to write and turns a configuration change into data loss:
the bytes are still on disk, the tenant's gallery is full of holes, and the only symptom is a
missing picture.

"Copy on grant" moves what can be hundreds of gigabytes across somebody else's network
because an administrator ticked a box. There is no request to report its progress in, a
failure halfway leaves the same blob in two places with nothing recording which is current,
and the operation has to survive a restart to be honest — that is a feature, not a side
effect of a grant.

"Keep writing to both" spends the tenant's storage twice, for ever, to avoid one extra
`Head` call on a read.

## Decision

**A grant routes; it never moves.**

| Operation | With no storage plugin granted | With one granted |
|---|---|---|
| `store` | the deployment's store | **the plugin, and only the plugin** |
| `open` / `exists` | the deployment's store | the plugin, then the deployment's store |
| `delete` | the deployment's store | **both**, the plugin first |

Three properties carry the weight:

- **A write never falls back.** A plugin that refuses or cannot be reached fails the upload.
  Writing the bytes into the deployment's store instead would put a tenant's data somewhere
  that tenant chose not to put it, and would do it at exactly the moment nobody is watching.
- **A read always falls back**, including when the plugin is unreachable. A store that is
  down is a reason to look in the other place, not a reason to answer "gone". This cannot
  return the wrong bytes: the address is the content hash **within the tenant**
  ([ADR-0032](0032-per-tenant-blob-addressing.md)), so a blob found under it is that blob.
- **A delete removes from both.** The blob may predate the grant, and a deletion that cleared
  one place while the other kept the bytes is the single direction `REQ-PRIV-004` does not
  allow.

The choice is made **per call**, from the tenant the call is for, by asking the extension
registry ([ADR-0065](0065-the-plugin-call-envelope.md)). Not cached: a grant is withdrawn in
the administration surface, and the next upload belongs where the tenant says now.

## Rationale

The in-deployment `blobstore` service is not a plugin and is not reached through the port
([ADR-0043](0043-blobstore-as-its-own-service.md)) — it opens nothing outward, which is the
line [ADR-0026](0026-core-outbound-via-plugins.md) draws. So the two stores are two roles,
and they are two **types**: `BlobStore`, which `media` asks for and which answers "where do
this tenant's bytes go", and `DeploymentBlobStore`, which is this deployment's own service.
One interface with two implementations would make every injection point ambiguous, and
resolving that with `@Primary` in two places is how a test ends up exercising a different
store from the one production uses — which is precisely what the test fixture did before this
change, and why the gap was invisible for as long as it was.

## Consequences

- **`TenantBlobStore` is the only `BlobStore` bean**, and `GrpcBlobStore` implements
  `DeploymentBlobStore`. The integration fixture substitutes the *deployment's* store, so
  every test in the suite now runs through the routing.
- **`BlobStoreAdapter` streams in both directions**: 256 KiB chunks on write, and a pipe on
  read so that a blocking-stub iterator becomes an `InputStream` without collecting a 32 MB
  photograph in memory first.
- **A blob store call runs as the blob's tenant.** Grants are read under row-level security,
  which answers to `app.tenant_id` and not to an argument, so the ambient context is what
  decides which grants come back. `TenantBlobStore` refuses a call made without a context or
  as another tenant instead of quietly resolving somebody else's plugin. Every caller in
  `media` already established one; the single place that did not was an assertion in `MediaScanIT`.
- **A read of an older blob costs one extra round trip** — a `Head` to the plugin that
  answers "no" — and that is the price of the first property in the table above.
- **The `BlobStore` contract carries the call envelope**, added 2026-09-21 and additive
  on the wire. It was absent while the only implementation was the in-deployment service,
  which needs nothing beyond the address — and a store whose bucket and keys a TENANT
  may choose ([ADR-0073](0073-a-plugin-is-configured-twice.md)) cannot be told which
  tenant it is acting for without one. `blob.tenant_id` keeps its own meaning: it is
  part of the address, not the envelope, and a store that finds the two disagreeing
  refuses the call rather than choosing one of them.
- **A tenant's two stores stay two stores.** Nothing in this record moves a blob, and nothing
  in the system reports a blob as being in the wrong place, because both places are right.
- **`TenantBlobStoreIT`** holds all four rows of the decision table against a real plugin over
  a real TLS socket, including the one a tenant would otherwise find: a photograph uploaded
  before the grant is still readable after it.
