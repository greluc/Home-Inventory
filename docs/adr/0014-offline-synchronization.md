# ADR-0014 — Full bidirectional sync with a three-way compare

**Status:** Accepted · **Date:** 2026-09-11

## Context

Capture happens in the cellar, the garage, the storage unit, on a ladder — in
other words exactly where there is no network. Full bidirectional offline
reconciliation is required.

## Options

| Option | For | Against |
|---|---|---|
| Online only | The simplest implementation | Capturing without a network fails — precisely the main use case |
| Read cache + an outbox for changes | Considerably simpler, covers most of daily use | No complete local inventory; conflicts resolvable only coarsely |
| **Full sync, three-way compare per field** | Full offline use; conflicts detected field by field; nothing is lost | The most expensive part of the system; high error potential; its own testing effort |
| CRDT (automatic convergence) | Converges without a user decision | Convergence is not the same as correctness: two different names merge into one that nobody chose · larger payloads · hard to debug · poorly manageable for a single developer |

## Decision

**Full bidirectional reconciliation** through a server-side change log with a
monotonic sequence per tenant, a **three-way compare** (base, local, server) with
**type-dependent resolution rules per field kind**, and a **conflict record** for
everything not resolvable automatically. Configuration and permissions are only
downloaded, never edited offline. In full:
[11 Offline Synchronization](../architecture/11-offline-synchronization.md).

## Why not CRDT

A CRDT guarantees that all devices reach **the same** state — not that it is the
**right** one. In an inventory system a silently merged value is worse than a
question: the user notices the error months later, when they cannot find the
item. The chosen approach detects conflicts reliably and puts the decision where
it belongs.

Where a data structure is conflict-free by nature (tags as a set, attachments,
maintenance entries as an event list, relative quantity changes), CRDT-like rules
are used **deliberately**. Just not everywhere.

## Consequences

- **The device must keep the base version.** Without it only "last writer wins"
  would remain — that is, silent data loss. It costs local storage and is worth
  it.
- `change_log` is the fastest-growing table; it is partitioned monthly with a
  retention of 30–365 days (default 90). A device with an older cursor performs a
  full seeding.
- Tombstones must live at least as long as the cursor retention.
- **Property-based tests are mandatory**, not optional: n simulated devices,
  random operation sequences, the invariant "no record disappears without a
  conflict record". The same suite runs against web and KMP.
- The implementation lands in **stage 3**. Until then the PWA works with a read
  cache and an outbox — which is simultaneously the fallback path should the full
  reconciliation prove too expensive (risk R2).
- Sync is part of the **versioned API contract**, not an add-on.
