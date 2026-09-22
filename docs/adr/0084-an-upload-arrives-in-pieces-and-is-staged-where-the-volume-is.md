<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0084 — An upload arrives in pieces, and is staged where the volume is

**Status:** Accepted · **Date:** 2026-09-22

**Amends:** [ADR-0043](0043-blobstore-as-its-own-service.md) — the in-deployment store gains four
optional methods and a second directory, because an upload that is not finished has no content
address and cannot be addressed the way that decision addresses a blob.

## Context

`REQ-MED-008` asks for uploads that are **resumable (tus) and survive network changes**, with one
acceptance criterion: *"an interrupted upload is continued, not restarted"*.
[`05 §5.2`](../architecture/05-runtime-view.md) had drawn the flow when the chapter was written —
create the upload, send the bytes in pieces, continue where the last piece stopped — and nothing
implemented it. Every upload was one `multipart/form-data` request, so a connection that dropped at
90 % cost the whole file, again, from zero. On the connection a phone has while standing in front
of a shelf, that is the difference between a photograph arriving and a photograph never arriving.

What the drawn flow did not say is **where the half-arrived bytes live**, and that is the decision.
Three facts constrain it:

1. **`api` is stateless by requirement** (`REQ-NFR-008`) and may be more than one process. An upload
   begun against one replica has to be continuable against another, so the local disk is not an
   option — not as an optimisation, as a correctness matter.
2. **A half-arrived file has no content address.** `BlobStore` addresses everything by
   `sha256/<tenantId>/<hash>` ([ADR-0032](0032-per-tenant-blob-addressing.md)) and verifies the
   digest on write, which is exactly what an unfinished object cannot offer.
3. **The digest of what arrives is not the digest of what is stored.** Every image is re-encoded to
   AVIF ([ADR-0052](0052-one-stored-image-format.md)) and its EXIF stripped, so the bytes that are
   stored are produced from the bytes that arrive.

## Decision

### The bytes wait in the `blobstore` service

Four methods are added to the `BlobStore` contract — `AppendStaged`, `HeadStaged`, `GetStaged`,
`DeleteStaged` — addressing a staged upload by `staged/<tenantId>/<uploadId>` instead of by content.
They are **optional**: the core calls them on the in-deployment store and on nothing else, and a
plugin may answer all four with `UNIMPLEMENTED`. A tenant that granted S3 or Nextcloud
([ADR-0074](0074-granting-a-store-routes-and-does-not-move.md)) receives the **finished** blob
through `Put`, exactly as before. Pushing an unfinished object into somebody's own bucket would
leave litter there that only this deployment knows how to clean up.

### The offset is the staged file's length, and is stored nowhere else

`media.upload_session` holds what the store does not know — who the upload is for, what it will hang
on, when it stops being worth keeping — and **no offset column**. A number kept in two places
disagrees after a crash, and the one on disk is the one that is true. Every `HEAD` asks the store.

### One append at a time, refused in the store

tus asks a server to prevent two concurrent writes to one upload, and the reason is not tidiness:
two appends that both read the length before either writes are both at the right offset and both
write, leaving a file of the right length and the wrong bytes — which nothing notices until the
digest at the very end. The lock is in the `blobstore` service, because that is one process holding
one volume and therefore the only place a lock covers **every** caller rather than only the ones
that agreed to take it. It surfaces as `423 Locked`.

### Both entrances end in one pipeline

When the last byte arrives, the staged file is read back and handed to the same
`MediaService.upload` the one-shot path uses: the same size check, magic-byte detection, EXIF
stripping, transcoding, deduplication and scan. `ResumableUploadIT.twoEntrancesOnePipeline` holds
it there by uploading identical bytes through both and asserting they are **one** stored object,
which content addressing can only produce if both ran the same steps.

Reading the bytes back over the network is the price. The store could have promoted the staged file
into the content-addressed slot without a transfer, and that would have saved a copy and lost the
only chance to look at what arrived — a store that, by contract, does not know what a blob is
cannot sniff its magic bytes or re-encode it, which is what `REQ-MED-004` and
[ADR-0052](0052-one-stored-image-format.md) require of every upload.

### The one-shot upload stays

`POST /api/v1/media` remains for a small file in one request. A 2 MB photograph does not need three
round trips, and two entrances to one pipeline is a design rather than a duplication — which is
true only for as long as the test above passes.

## Options

| Option | Why not |
|---|---|
| **Staged in the `blobstore` service** | Chosen |
| Staged in PostgreSQL as chunk rows | No contract change, transactional, and it puts media bytes in the database — the one thing the data model says about blobs is that they are not in it, because a million photographs is about 700 GB. Every chunk would also travel through WAL and into every backup |
| Staged on `api`'s local disk | Contradicts `REQ-NFR-008` outright: the upload could only be continued against the replica that began it, and the deployment has no way to route it there |
| A tus server library | Each brings a storage abstraction that would have to be taught about the `blobstore` service, per-tenant addressing and the scan; the core protocol is four methods and a handful of headers. A dependency also appears in nine licence notices (`REQ-CON-013`) and in the bundle's SBOM |
| Raise the 25 MB ceiling while we are here | Considered with the owner and **declined**: `REQ-MED-003` permits images, PDF, TXT and CSV — no video — so a higher ceiling would serve only very large scans, and clamd's `INSTREAM` default is 25 MB, the very figure `REQ-SEC-037` already fixes. Raising it would mean a generated `clamd.conf`, a re-examination of the scanner's memory limit and an amendment to a security requirement, for a file nobody can upload |

## Consequences

- **A staged upload occupies the volume until it completes or expires**, for at most two hours
  (`HOMEINV_UPLOAD_LIFETIME_HOURS`). A quarter-hourly sweep removes what nobody came back for,
  bytes first and the row only if that succeeded — the opposite order to the completion's, and right
  for the same reason: here the row is the only record of where the bytes are.
- **The size limit is now checked before a byte arrives**, which is what `REQ-SEC-037` asks for and
  what the one-shot path can only approximate by cutting the stream off mid-read. The resumable path
  is the stricter of the two.
- **The web client uploads in 4 MB pieces** and resumes from the server's offset after a failure,
  with no new dependency: `web/src/upload.ts` is the protocol and nothing else.
- **`05 §5.2` was wrong and is corrected.** It had the client declaring the file's SHA-256 at
  creation so that a duplicate needed no upload at all. A client does not know that digest before
  reading the file, and — decisively — the digest of what it sends is not the digest of what is
  stored, because the image is re-encoded on the way in. Deduplication happens after the pipeline
  instead, where it still costs the tenant no second copy.
- **Two defects were found while building this**, both in code that predates it: a Spring Data
  `@Query` method runs outside a transaction, so `app.tenant_id` is never set and row-level security
  answers it with no rows — which made a sweep remove nothing and would have made an upload
  unfindable in production; and `new Response(null, {status: 0})`, the client's sentinel for a
  connection that vanished, is not constructible, so the error path would itself have thrown.
