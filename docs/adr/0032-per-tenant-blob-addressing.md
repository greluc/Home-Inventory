# ADR-0032 — Blobs are content-addressed **within a tenant**, never across

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [ADR-0007](0007-media-storage.md) — content addressing stays, its scope
shrinks.

## Context

[ADR-0007](0007-media-storage.md) made every blob content-addressed under
`sha256/<hash>`, globally, with reference counting deciding deletion.
[05 §5.2](../architecture/05-runtime-view.md) turned that into a short cut in the
upload flow:

> *A blob with this hash already exists → `200` + existing `mediaId` (no upload
> needed)*

while [07 §7.8](../architecture/07-data-model.md) gave `media.media_object` a
`UNIQUE(tenant_id, sha256)` — per tenant. Metadata was scoped, storage was not,
and the short cut was written without saying which of the two it consults.

Read globally, it has three consequences that were not intended:

1. **A cross-tenant existence oracle.** A tenant who knows a file's SHA-256 learns
   whether anyone on the instance holds it. For a named document — a specific
   invoice, a leaked PDF, a known photo — that is a real disclosure.
2. **Attachment without possession.** Knowing the hash is enough to obtain a
   `mediaId` and attach the file, without ever having had the bytes.
3. **It bypasses the mandatory scan.** [ADR-0024](0024-malware-scan.md) is
   *fail-closed*: "every upload is scanned before it becomes retrievable". A
   dedup hit uploads nothing, so nothing is scanned — the second tenant receives a
   blob whose scan result was decided under another tenant's signature database,
   possibly months earlier, and the interaction with `PENDING_SCAN` was undefined.

## Options

| Option | Storage | Oracle | Scan bypass | Offline catch-up stays free |
|---|---|---|---|---|
| Global addressing, keep the short cut | smallest | **yes** | **yes** | yes |
| Global addressing, no short cut (always upload, then reference) | smallest | no | no | **no** |
| **Per-tenant addressing** | duplicates across tenants | no | no | **yes** |

## Decision

**The blob path carries the tenant: `sha256/<tenantId>/<hash>`.** Reference
counting is per tenant. The dedup short cut in
[05 §5.2](../architecture/05-runtime-view.md) survives unchanged — it simply
consults the tenant's own namespace, which is what `UNIQUE(tenant_id, sha256)`
already implied.

| Property | Value |
|---|---|
| Deduplication | Within a tenant. Uploading the same photo twice costs storage once, which is the case that actually occurs |
| Across tenants | Two tenants holding the same file hold two copies. There is no shared object and no shared reference count |
| Scan | Every `(tenant, hash)` pair is scanned once. A hash known to another tenant grants nothing |
| `PENDING_SCAN` | Scoped to the tenant, so a blob awaiting a verdict in tenant A is invisible and unusable in tenant B |
| Deletion | A tenant's last reference removes that tenant's blob. No other tenant is consulted, which also removes a class of cross-tenant timing bug |

## Rationale

The short cut is worth keeping: it is what makes offline catch-up idempotent, and
it is the reason a photo sent twice from a flaky connection costs storage once
([11 §11.6](../architecture/11-offline-synchronisation.md)). The question is only
what namespace it consults.

Cross-tenant deduplication saves storage in exactly one situation — several
tenants holding byte-identical files — which for household and club inventories
is close to never. The realistic case is the *same* user uploading the same photo
twice, and that is entirely inside one tenant. So global addressing was paying
three security costs for a saving that does not materialise.

It also removes an awkward question the design never answered: when the blob is
shared but the scan verdict, the retention period and the erasure obligation are
per tenant, whose deletion actually deletes it? Under GDPR Art. 17 a tenant
erasure must remove the tenant's data ([05 §5.9](../architecture/05-runtime-view.md)),
and "the bytes remain because another tenant also has them" is a conversation
better not to have.

## Consequences

- **Storage grows in the multi-tenant case**, in proportion to genuinely duplicated
  files across tenants. The volume estimate in
  [07 §7.10](../architecture/07-data-model.md) is unchanged, because it was
  computed per tenant to begin with.
- **Tenant erasure gets simpler**, not harder: the tenant's blob prefix is removed
  wholesale, and the completion report per building block
  ([05 §5.9](../architecture/05-runtime-view.md)) no longer needs a reference count
  spanning tenants to be correct.
- **`REQ-MED-007` is rephrased** from "duplicates are stored once" to "duplicates
  within a tenant are stored once", with the acceptance test extended: the same
  file uploaded by two tenants must produce two independent objects, and the
  second upload must be scanned.
- **`REQ-MED-011`** (removal only when no link remains) is unchanged in wording and
  narrower in scope.
- **The `BlobStore` adapter contract test** gains a case: an adapter that collapses
  two tenants' identical content into one object fails. That applies to all three
  shipped adapters, and now also to the plugin adapters from
  [ADR-0026](0026-core-outbound-via-plugins.md).
