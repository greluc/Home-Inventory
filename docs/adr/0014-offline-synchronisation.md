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
[11 Offline Synchronisation](../architecture/11-offline-synchronisation.md).

## Why not CRDT

A CRDT guarantees that all devices reach **the same** state — not that it is the
**right** one. In an inventory system a silently merged value is worse than a
question: the user notices the error months later, when they cannot find the
item. The chosen approach detects conflicts reliably and puts the decision where
it belongs.

Where a data structure is conflict-free by nature (tags as a set, attachments,
maintenance entries as an event list, relative quantity changes), CRDT-like rules
are used **deliberately**. Just not everywhere.

### The quantity rule needs intent, and the protocol now carries it

*Decided 2026-09-11, resolving open point **O14**.*

The resolution rule for consumables reads "add the deltas when both sides changed
relatively". The **deltas** are derivable without any protocol support — the
three-way compare already holds base, local and server, so `10 → 13` against
`10 → 9` gives `10 + 3 − 1 = 12`. What is *not* derivable is the **intent**:
"set it to 12" and "take two out" produce identical absolute values, and they
deserve opposite merge behaviour. A stocktake correction must win outright; a
withdrawal must accumulate.

A change to a numeric field therefore carries `intent: SET | ADJUST`:

| Intent | Meaning | Merge with a concurrent change |
|---|---|---|
| `ADJUST` | "I took two out" | Deltas are summed: `base + Δlocal + Δserver` |
| `SET` | "I counted, there are twelve" | Treated as an ordinary scalar — two concurrent `SET`s conflict |
| `SET` vs `ADJUST` | A count and a withdrawal crossed | The `SET` wins as the base, the `ADJUST` is applied to it. Someone who has just counted is the better authority, and the withdrawal still needs to be accounted for |

The client derives the intent from the interaction, not from a dialog: the plus and
minus controls and a scan in `LEND`/`RETURN` mode emit `ADJUST`; typing a number
into the quantity field and the stocktake correction screen emit `SET`.

**Why not derive deltas unconditionally**, which would have needed no protocol
change at all: it silently corrupts the case the rule exists to protect. A user who
counts the shelf and enters 12 while someone else withdraws one offline would end
up with 11 — the withdrawal applied twice, once in the count they just took and
once as a delta. That is a wrong number arrived at quietly, which is the failure
class this whole chapter is built to avoid.

Sync is stage 3, so the wire format is not published yet and this costs nothing
today. After publication it would have been a contract change.

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
