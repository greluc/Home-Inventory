# ADR-0054 — The malware scan runs in the worker, and the upload is answered before it

- **Status:** Accepted
- **Date:** 2026-09-12
- **Deciders:** @greluc
- **Amends:** [ADR-0024](0024-malware-scan.md). The scan, the fail-closed behaviour,
  the signature-age alert and the `scanner` segment all stand unchanged; what this
  record settles is *where* the scan runs, which ADR-0024 stated and the
  implementation contradicted, and what the client is told while it has not run yet.

## Context

ADR-0024 says, in its consequences: *"The scan runs **in the worker**, not in the
request path; the client receives the upload as a job resource with state."*
[06 §6.4](../architecture/06-deployment-view.md) says the same thing from the other
side — the `scanner` segment holds `clamav`, `worker` and `egress-proxy`, and
*"`api` is deliberately absent: the scan runs in the worker, never in the request
path"*.

The stage-0 implementation did the opposite. `UploadPipeline` called
`VirusScanner` inline, between the pixel check and the store, and
`DefaultMediaService` recorded `CLEAN` in the same transaction. The class Javadoc
argued for it: the caller is waiting for an answer they can act on, and nothing
reaches the store without a verdict, which is *stronger* than what `REQ-MED-013`
demands.

The argument was sound and the result did not work. `api` has no route to
`clamd` — that is the whole point of the segment — so **every upload in the
generated deployment answered `503`**, while every test in the JVM suite passed
against a stubbed scanner. It was found by running `deploy/smoke/journey.sh`
against a real stack, and it is precisely the failure class
[14 R16](../architecture/14-quality-risks-glossary.md) names: a guarantee written
in one document and delivered in another, with nothing comparing them.

`REQ-SEC-092` had drifted with it. It read *"a finding → the blob is discarded,
`422` … scanner unreachable → `503`"*, which are HTTP responses to the upload —
statuses an asynchronous scan cannot produce, in the same catalogue as an ADR
saying the scan is asynchronous.

## Decision

**The scan runs in the `worker`, on the message the upload publishes.** The
upload is answered `202 Accepted` with `Location: /api/v1/media/{id}`, and the
object is `PENDING_SCAN` until the worker has asked the scanner.

### What the client sees

| Moment | Answer |
|---|---|
| `POST /api/v1/media` | `202`, the `MediaView` with `scanState: PENDING_SCAN` and **no** URLs, and a `Location` header |
| `GET /api/v1/media/{id}` while there is no verdict | `503`, `type: …/scan-unavailable` |
| `GET /api/v1/media/{id}` after a finding | `422`, `type: …/malware-detected` |
| `GET /api/v1/media/{id}` once clean | `200` with the signed URLs |

`REQ-SEC-092`'s two status codes are kept, and they move from the `POST` to the
`GET`: that is where a client now learns the outcome. It is a deliberate
exception to the corpus's usual rule of answering `404` for anything a caller may
not see — the caller is inside the tenant and uploaded the file, so the status
reveals nothing they did not put there themselves.

### What replaces "nothing unscanned is ever stored"

That guarantee is gone, because the bytes must exist for the worker to scan them.
Three narrower ones replace it, and together they cover what it was for:

1. **Nothing unscanned is ever *retrievable*.** `MediaObject.isRetrievable()` is
   `CLEAN` and nothing else. No signed URL is minted for any other state, and
   `openVerified` checks the state again rather than relying on that — after this
   decision, "no URL was minted" would otherwise be the single thing between an
   unjudged blob and a client.
2. **For an image, the stored bytes are the re-encoding.** `REQ-SEC-041` already
   required it and it stays in the request path, because until the re-encoding
   exists the only bytes on hand are the ones that must not be stored. A polyglot
   payload therefore does not survive being stored, scan or no scan.
3. **A finding deletes the blob**, and the derivatives are produced only *after* a
   clean verdict — a thumbnail of an infected file would be a second blob under a
   different content address, which the deletion would not reach.

### When no verdict can be reached

`SCAN_FAILED`, and a retry that carries its own tenant.

The listener records `SCAN_FAILED` and republishes the event onto
`homeinv.media.scan-retry`, a queue with a five-minute TTL that dead-letters back
onto the media exchange. Twelve attempts, after which the object stays
`SCAN_FAILED`, unretrievable, and logged at `ERROR` for an operator.
`recordVerdict` therefore accepts a transition **out of** `SCAN_FAILED`: it is the
absence of a verdict rather than one, so the run that reaches the scanner later is
recording the first verdict, not overruling an earlier one. `CLEAN` and `INFECTED`
stay final.

**Why not a scheduled sweep**, which is what ADR-0024's *"a background run catches
the scan up"* suggests: it cannot be written. A run that asks *"which objects are
`SCAN_FAILED`?"* is a query across every tenant, and `homeinv_app` has no
`BYPASSRLS` and cannot enumerate tenants — a query with no `app.tenant_id` returns
zero rows, which is [ADR-0003](0003-multi-tenancy.md) working as specified. The
only place a tenant is available outside a request is the message that carried it,
so the retry carries it too. Giving the worker a role that could do the sweep
would undo the second line of tenant isolation to schedule a retry.

## Consequences

- **A client must poll.** The `web` client uploads, then follows `Location` until
  it is answered `200` or told why not. `202` with a job resource is the shape
  ADR-0024 named; this is the first endpoint in the product that has one.
- **A broker outage now delays *retrievability*, not merely thumbnails.** The
  event that gets an object judged is the same one that gets it derived. That is
  the fail-closed direction — an unjudged object stays unretrievable — and it is
  another reason `rabbitmq` is in every profile ([ADR-0051](0051-broker-in-stage-0.md)).
- **`api` does not join the `scanner` segment**, and 06 §6.4 stays true as
  written. The alternative — putting `api` on the segment and keeping the
  synchronous scan — was rejected: it would have put a sixty-second ClamAV round
  trip back on the request thread, which is what ADR-0051 moved the derivatives
  off it to avoid.
- **The JVM suite could not have caught this and still cannot.** `MediaScanIT`
  proves the handover with a scripted scanner; the real ClamAV, with a real EICAR
  file, is `deploy/smoke/journey.sh`, which is what `REQ-SEC-092`'s *"verified
  with an EICAR test file"* has always asked for and what now runs it.
- **`REQ-SEC-092` and `REQ-MED-013` are amended** to describe states and the
  retrieval status rather than an upload status, and `docs/reference/problem-types.yaml`
  moves `malware-detected` and `scan-unavailable` from the `POST` to the `GET`.
