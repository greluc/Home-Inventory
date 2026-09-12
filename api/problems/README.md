<!--
  GENERATED FROM docs/reference/problem-types.yaml — DO NOT EDIT.

  Run `python tools/render_problems.py` after changing the registry. CI
  regenerates these and fails on any difference, so an edit here is lost and
  noticed rather than lost and not (REQ-API-003).
-->

# Problem types

Every `type` URI this API can return, one document per token.

A client branches on the `type` and never on the `detail`: the URI is stable and the prose is not, and the prose is translated. The URI is fixed for the **product** rather than derived from a deployment's hostname, so a client that recognises `https://home-inv.example/problems/not-found` recognises it from every instance (REQ-API-003).

**Namespace:** `https://home-inv.example/problems/`

| Token | Status | State | Summary |
|---|---|---|---|
| [`validation-failed`](validation-failed.md) | `422` | registered | The request was syntactically valid but violates a domain rule — most often an attribute that does not match its type version's JSON Schema. |
| [`quota-exceeded`](quota-exceeded.md) | `403` | registered | A tenant quota would be exceeded by this operation — item count, stored bytes, plugin count or API calls. |
| [`cursor-expired`](cursor-expired.md) | `410` | registered | The device's sync cursor is older than the tenant's `change_log` retention. The device must perform a full seeding. |
| [`malformed-request`](malformed-request.md) | `400` | assigned | The request could not be parsed, or an unknown field was present. |
| [`unauthenticated`](unauthenticated.md) | `401` | assigned | No valid credential was presented. |
| [`forbidden`](forbidden.md) | `403` | assigned | The caller is authenticated and the resource is one they may know exists, but the action is not permitted. |
| [`not-found`](not-found.md) | `404` | assigned | The resource does not exist, **or** exists and is not visible to this caller. One token, deliberately, for both. |
| [`idempotency-key-conflict`](idempotency-key-conflict.md) | `409` | assigned | The `Idempotency-Key` was already used with a **different** payload. The same key with the same payload returns the original response instead. |
| [`resource-exists`](resource-exists.md) | `409` | assigned | A creating `POST` supplied an `id` that already exists in this tenant with **different** content. The same id with the same content returns `200` instead, which is what makes a retried creation safe. |
| [`name-taken`](name-taken.md) | `409` | assigned | A location's name is already carried by a live sibling. Names are unique among siblings, case-insensitively, so that the tree a person reads matches the tree the database holds. |
| [`precondition-failed`](precondition-failed.md) | `412` | assigned | The `If-Match` entity tag does not match the resource's current version. |
| [`precondition-required`](precondition-required.md) | `428` | assigned | A mutating request on a single resource arrived without `If-Match`. There is no blind overwrite. |
| [`rate-limited`](rate-limited.md) | `429` | assigned | A per-user, per-tenant or per-IP rate limit was reached. |
| [`malware-detected`](malware-detected.md) | `422` | assigned | The malware scan found something. The blob is deleted from the store and the object is `INFECTED`, permanently unretrievable and never derived from. |
| [`scan-unavailable`](scan-unavailable.md) | `503` | assigned | There is no verdict for this object yet — it is `PENDING_SCAN`, or the scanner could not be reached and it is `SCAN_FAILED`. Either way it is unretrievable, and the retry queue will ask again (ADR-0054). |
| [`payload-too-large`](payload-too-large.md) | `413` | assigned | The request body exceeds the JSON limit, or a bulk operation exceeds its entry limit. |
| [`method-not-allowed`](method-not-allowed.md) | `405` | assigned | The path exists and does not support this method. |
| [`not-acceptable`](not-acceptable.md) | `406` | assigned | The caller's `Accept` header allows no representation this endpoint can produce. |
| [`unsupported-media-type`](unsupported-media-type.md) | `415` | assigned | The request's `Content-Type` is not one this endpoint reads. |
| [`internal-error`](internal-error.md) | `500` | assigned | Something failed that the application does not have a specific answer for. The request may be repeatable; nothing about what went wrong is disclosed. |
| [`plugin-unavailable`](plugin-unavailable.md) | `503` | assigned | A plugin the requested ACTION depends on cannot be reached — its circuit is open, its deadline expired, or it is disabled. |
| [`tenant-inaccessible`](tenant-inaccessible.md) | `403` | assigned | The tenant is suspended, or pending deletion within its grace period. One token for both states, deliberately. |
