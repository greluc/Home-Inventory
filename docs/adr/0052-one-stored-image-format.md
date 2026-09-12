# ADR-0052 — Every stored image is AVIF, and the bytes that arrived are never stored

**Status:** Accepted · **Date:** 2026-09-12

**Amends:** [ADR-0007](0007-media-storage.md),
[04 §4.1](../architecture/04-building-blocks.md), `REQ-MED-003`, `REQ-MED-005`

> **Amended by [ADR-0054](0054-the-scan-is-asynchronous.md), the same day.** Two
> sentences below describe the upload as holding a ClamAV round trip. It does not
> any more — the scan moved to the worker — so the re-encode is now the *only*
> thing on that thread, which strengthens the argument for keeping it there rather
> than weakening it. The upload's own bytes are still scanned and still deleted
> with the spool file; what changed is when.
>
> **The AVIF decision cost one deployment fix rather than an amendment.** Ubuntu
> 26.04, which `eclipse-temurin:25-jre` resolves to, packages libheif's AVIF
> *decoder* and none of its encoders, so `heifsave` accepted every request and
> failed at the last step. The runtime image is pinned to noble with
> `libheif-plugin-aomenc` instead of the format being changed.

## Context

Three stage-0 requirements describe what happens to an uploaded image, and
together they leave one question open:

| | |
|---|---|
| `REQ-SEC-041` | *"Every image is **re-encoded**; embedded payloads are destroyed in the process."* |
| `REQ-MED-003` | HEIC is *"accepted and transcoded to AVIF on ingest; it is never stored in its original form"*. Acceptance: **no stored blob has a HEIC magic number** |
| `REQ-MED-005` | `thumb` 200 px, `preview` 1024 px, `full` re-encoded at most 4096 px |

Re-encoded **into what**? The permitted upload formats are JPEG, PNG, WebP, AVIF
and HEIC, and nothing said which of them a re-encoded image comes out as.

The question is not academic, because two of its answers are wrong in ways that
only show up later:

- **Preserve each input's format** — a JPEG stays a JPEG. Then the store holds
  four image formats, four decoder paths have to be kept safe, and each format
  keeps its own metadata conventions. The thing `REQ-SEC-041` removes is exactly
  what a format carries along with its pixels.
- **Store the original and re-encode only on the way out.** Cheaper on upload and
  it makes `REQ-MED-003`'s acceptance criterion impossible: a HEIC upload leaves a
  HEIC blob in the store, whatever is served from it.

## Decision

**Every image is transcoded to AVIF during the upload, and the transcoded file is
what gets stored.** The bytes that arrived are written nowhere.

| | |
|---|---|
| Format | AVIF, quality 60, all metadata stripped |
| Longest edge | 4096 px — this *is* the `full` variant |
| Stored under | its own SHA-256, so `sha256` on `media_object` addresses the re-encoding |
| The upload's own bytes | deleted with the spool file; the AVIF is what the worker scans (ADR-0054) |
| `thumb` and `preview` | derived afterwards in the worker ([ADR-0051](0051-broker-in-stage-0.md)) |

Documents — PDF, TXT, CSV — are stored as they arrived. There is no re-encoding
that makes a PDF safer without also making it a different document, and the
serving side refuses to render one inline in any case (`REQ-SEC-045`).

## Rationale

**AVIF over the alternatives** is the smaller half of this decision and comes to
one format, whichever it is: one decoder path, one metadata convention, one
quality setting to reason about. AVIF is chosen because the design already
requires HEIC to become it, because it is on the permitted list, and because it
is the format that costs the least to store — which matters on a home server
holding a decade of photographs.

**Doing it synchronously** is the half worth defending, because it puts an AVIF
encode on the request thread, and [ADR-0051](0051-broker-in-stage-0.md) moved the
*other* derivatives off that thread for precisely that reason. *This paragraph
also named a ClamAV round trip on the same thread; since
[ADR-0054](0054-the-scan-is-asynchronous.md) there is none, and the encode is the
only thing an upload waits for.*

It is unavoidable here and nowhere else: until the re-encoding exists, the only
bytes on hand are the ones that must not be stored. An asynchronous `full` means
either storing the upload until the worker gets to it — which fails
`REQ-MED-003`'s acceptance criterion outright — or holding it somewhere that is
not the store, which is a second, unmanaged place where unprocessed uploads live.

## Consequences

- **A client receives `image/avif` for every image**, whatever it uploaded. The
  API says so in `media_type`, and the OpenAPI document carries it.
- **AVIF decoding is assumed.** Every browser that this design's CSP and
  `trusted-types` requirements already assume has supported AVIF for years; a
  client that cannot decode it could not run the application either.
- **Upload latency rises** by one encode. It is bounded by the 4096-pixel ceiling
  and is paid once per upload, against a scan that is already on the same path.
- **Deduplication happens on the re-encoding**, not on the upload. Two uploads of
  the same photograph still store one blob, because the encode is deterministic
  for the same input and settings. Two *different* files that re-encode to the
  same AVIF also store one, which is correct and slightly surprising.
- **`REQ-MED-003`'s acceptance criterion becomes testable**, and is tested: the
  assertion is on the bytes in the store, not on the type the code reported about
  them.
- **The original is not retained.** 04 §4.1 said *"the original optionally
  retained"*; at stage 0 the option is not taken, and taking it later would need
  a second column and a per-tenant setting rather than a code change here.
