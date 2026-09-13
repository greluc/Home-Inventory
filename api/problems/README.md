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
| [`type-key-taken`](type-key-taken.md) | `409` | assigned | A key the tenant chose for a type, a location category, a field or a value is already in use for the same kind of thing. |
| [`version-frozen`](version-frozen.md) | `409` | assigned | A published type version was edited. A published version is a snapshot: the items written against it carry its schema, and changing it would change what they mean. |
| [`constraint-loosened`](constraint-loosened.md) | `422` | assigned | An inheriting type widened a field it inherits. An inheriting type may tighten a field and may not loosen one. |
| [`name-taken`](name-taken.md) | `409` | assigned | A location's name is already carried by a live sibling. Names are unique among siblings, case-insensitively, so that the tree a person reads matches the tree the database holds. |
| [`invalid-move`](invalid-move.md) | `409` | assigned | A location cannot be moved where the request asks: into itself or into something it contains, or under a category that has said which categories it takes and did not name this one. |
| [`invitation-already-open`](invitation-already-open.md) | `409` | assigned | This tenant already has an unused invitation for the address, or the person at that address is already a member of it. |
| [`last-owner`](last-owner.md) | `409` | assigned | The change would leave the tenant without an owner — the last one cannot be demoted or removed. |
| [`invitation-unusable`](invitation-unusable.md) | `410` | assigned | The invitation cannot be used: no such token, or it has been used, withdrawn or has run out. |
| [`invitation-not-yours`](invitation-not-yours.md) | `403` | assigned | The invited address already has an account, and the caller is not signed in as it. |
| [`role-escalation`](role-escalation.md) | `403` | assigned | Somebody tried to grant, or withdraw, a role carrying permissions they do not hold themselves. |
| [`second-factor-stale`](second-factor-stale.md) | `403` | assigned | The operation needs the second factor proved again; the code goes to `POST /api/v1/auth/mfa/step-up`. |
| [`registration-closed`](registration-closed.md) | `403` | assigned | This instance creates no accounts; the invitation is good, but the address behind it has none. |
| [`second-factor-missing`](second-factor-missing.md) | `403` | assigned | The role this session holds requires a second factor and the account has none; the way out is to enrol one. |
| [`second-factor-required`](second-factor-required.md) | `401` | assigned | The password was accepted and the account is protected by a second factor; the code goes to `POST /api/v1/auth/mfa`. |
| [`second-factor-invalid`](second-factor-invalid.md) | `401` | assigned | The code presented is not valid. |
| [`second-factor-enrolled`](second-factor-enrolled.md) | `409` | assigned | The account already has a confirmed second factor. |
| [`deletion-pending`](deletion-pending.md) | `409` | assigned | The tenant has already been asked to be erased; the grace period is running. |
| [`revocation-unusable`](revocation-unusable.md) | `410` | assigned | The revocation link cannot be used: no such token, already used, or the grace period is over. |
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
