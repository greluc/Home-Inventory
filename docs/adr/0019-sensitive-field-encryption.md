# ADR-0019 — Per-tenant envelope encryption for sensitive fields

**Status:** Accepted · **Date:** 2026-09-11

## Context

Digital items carry licence keys, activation codes and credentials — data that is
directly worth money. The system is reachable from the internet and multi-tenant.
A database dump (a stolen backup, a SQL injection, a compromised database
account) must not expose those values.

## Options

| Option | For | Against |
|---|---|---|
| No encryption | Simple | A dump exposes everything |
| Volume encryption only | Operationally simple | Protects only against physical theft, not against SQL injection and not against a dump taken from the running system |
| **Per-tenant envelope encryption** | Protection even against a database dump; per-tenant key rotation; separation between database access and key access | Encrypted fields are not searchable and not sortable; losing the master key means losing the data |
| End-to-end at the client | The strongest protection | No server-side access, no enrichment, no export by the operator; key management across devices becomes a project of its own |

## Decision

**Envelope encryption**: a data key (DEK) per tenant, wrapped with a master key
(KEK) from the environment
(`HOMEINV_DATA_ENCRYPTION_MASTER_KEY_FILE`). The algorithm is **AES-256-GCM**
with additional authenticated data (AAD) composed of `tenantId`, `entityId` and
`fieldKey`.

Affected are all fields with `sensitive: true` in the type definition, plus
plugin settings of type `secret`.

## Rationale

The AAD is the decisive part: it means an encrypted value **cannot** be moved
into another record, another field or another tenant — decryption fails. Without
AAD, an attacker with write access could copy someone else's secret into a record
of their own and have it displayed.

End-to-end encryption was rejected because it makes export, enrichment and
operator-side recovery impossible — for an inventory system meant to preserve
values over years, that weighs more than the gain.

## Consequences

- **Encrypted fields are not searchable and not sortable.** They are not mirrored
  into `item_attr_index` and not handed to OpenSearch. This is visible in the UI
  when a field is marked `sensitive`.
- Display requires an explicit permission **and** the second factor again; every
  disclosure produces an audit entry.
- In sync, `sensitive` fields are **always** a conflict, never merged
  automatically. The web PWA does not store them locally at all, because
  IndexedDB is unencrypted.
- Key rotation: a tenant's DEK can be re-wrapped without touching the data. The
  KEK supports two active versions for a seamless rotation.
- **A lost master key means permanent loss of those fields.** The key is backed up
  separately from the data backup, in a different place. That sits at the top of
  the operations guide.
- Without a master key set, the application does **not** start. There is no
  generated substitute key.
