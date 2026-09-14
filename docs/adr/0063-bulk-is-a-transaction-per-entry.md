# ADR-0063 — A bulk operation is a transaction per entry

**Status:** Accepted · **Date:** 2026-09-13

**Amends:** [ADR-0031](0031-audit-chain-per-tenant.md), the *Batching* row and the
consequence that follows from it — a bulk operation of 500 entries takes 500 lock
acquisitions on the tenant's audit chain, not one.

## Context

`REQ-CORE-011` asks for bulk operations over items — move, tag, change type,
delete — with **up to 500 entries per call and partial success, a status line per
entry**. [08 §8.2](../architecture/08-api-contract.md) adds an idempotency key per
entry.

[ADR-0031](0031-audit-chain-per-tenant.md) made a statement about the same feature
from the other side. The audit chain is per tenant, `prev_hash` forces a lock, and
its batching rule reads: *"A transaction that produces several audit entries (a
bulk operation, an import) writes them as **one** chained run under a single lock
acquisition, not one lock per entry."* Its consequences section names the load case
in as many words: *"a bulk operation of 500 items within one tenant, which now
takes one lock instead of 500"*.

**Partial success is not what a transaction does.** Every use case in the
application layer is `@Transactional`. One that throws while *participating* in an
enclosing transaction marks that transaction rollback-only, so a loop that catches
the exception, reports 499 successes and commits gets `UnexpectedRollbackException`
at the commit and loses every one of them. Catching is not enough: each entry needs
a rollback boundary of its own.

## Options

| Option | Rollback scope | ADR-0031's lock rule | Partial success |
|---|---|---|---|
| One transaction, savepoint per entry | one entry | held | **not available on JPA** — see below |
| **One transaction per entry** | one entry | broken — 500 acquisitions | complete, database failures included |
| Validate all, then one transaction | the whole call | held | only what validation can see |
| One transaction, no boundary per entry | the whole call | held | none — the commit throws |

**The savepoint option was chosen first and then found to be unbuildable.** Three
findings, in the order they appeared:

1. Spring installs a savepoint manager on a JPA transaction only when the
   dialect's transaction data implements `SavepointManager`. In Spring 7 neither
   `HibernateJpaDialect.SessionTransactionData` nor
   `DefaultJpaDialect.FlushModeTransactionData` does, so
   `PROPAGATION_NESTED` fails with `NestedTransactionNotSupportedException:
   JpaDialect does not support savepoints` — every time, whatever
   `setNestedTransactionAllowed` says.
2. Supplying one would not have been enough. A participating `@Transactional`
   method that throws reaches `JpaTransactionObject.setRollbackOnly()`, which
   calls `EntityTransaction.setRollbackOnly()` — **irreversible in JPA**. The
   enclosing commit then fails no matter what the savepoint did.
3. A failure that reaches the database poisons the session's transaction anyway.
   Hibernate marks the transaction rollback-only on a failed flush, and no
   savepoint held by Spring unwinds that.

Spring's own documentation says as much, and it was right: *"JPA itself does not
support nested transactions. Hence, do not expect JPA access code to semantically
participate in a nested transaction."*

## Decision

**One transaction per entry** (with the repository owner, 2026-09-13, after the
savepoint option was tried and failed).

| Element | Rule |
|---|---|
| Boundary | `BulkEntryRunner.apply` is `@Transactional(propagation = REQUIRES_NEW)`. A propagation needs a proxy, which is why it is its own bean rather than a method on the loop |
| The loop | `DefaultBulkItemOperations.apply` opens **no** transaction. An enclosing one would be suspended and resumed 500 times, hold a second connection for the whole call, and commit nothing |
| Tenant context | Re-established per entry, because `TenantAwareTransactionManager.doBegin` runs for every new transaction. The context itself is a thread-local and is unaffected by the boundary |
| Idempotency | Per entry, written inside that entry's transaction. An entry that fails therefore did **not** spend its key, so a client resending the whole selection retries exactly the entries that failed |
| The cap | 500, as `REQ-SEC-065` and 08 §8.2 say. Load-bearing rather than advisory: 500 entries are 500 transactions, each taking a connection from the pool in turn |
| What is not an entry's fault | An empty list, more than 500 entries, the same item twice, or a missing target fail the **call** with `422`. Five hundred identical status lines are not an answer |
| The permission | Decided once, before the first entry, from the operation: `inventory:item:update` for move and change of type, `tagging:tag:assign` for tag, `inventory:item:delete` for delete |

The status code follows from the same decision and is written down in
[08 §8.2](../architecture/08-api-contract.md): `200` when every entry was applied,
`207` when at least one was not.

## What this does to ADR-0031

Its batching rule stands **for a transaction that produces several audit entries**
— an import that genuinely runs as one, a cascade, a tenant erasure. It no longer
stands for a bulk operation, because a bulk operation is no longer one transaction.
When `change_log` is built, a 500-entry bulk call will take 500 chained runs and
500 acquisitions of that tenant's advisory lock.

That is acceptable, and the reason is in ADR-0031's own analysis of why the chain
is per tenant: the lock serialises **one tenant's** writes against each other and
never against another tenant's. A bulk call serialises that tenant's writes anyway
— it is 500 sequential changes made by one person. What the per-tenant chain was
chosen to avoid was a global serialisation point, and that is untouched.

The load case moves rather than disappears: the suite should measure 500
sequential transactions each taking the lock, and the number to watch is the
per-call duration against the 30-second request ceiling, not contention between
tenants.

## Consequences

- **A bulk call is not atomic, and was never meant to be.** `REQ-CORE-011` asks
  for partial success in as many words. A caller reads the body to learn which
  half happened; the status code only says whether to read it closely.
- **An entry that fails leaves nothing behind** — including the
  `Idempotency-Key` it would have spent. That is what makes "resend the whole
  selection" the correct client behaviour after a dropped connection.
- **Failure modes are per entry and typed.** `404`, `412`, `422`, `409` and, for
  anything unplanned, `500` — each as a line in the body rather than as the
  response's own status. The tokens come from
  [`problem-types.yaml`](../reference/problem-types.yaml); there is no second
  vocabulary for bulk.
- **A missing item in a bulk tag is that entry's `404`.** `tagging` may not read
  `inventory`'s schema, so an assignment naming a missing item arrived as a
  foreign-key violation and was answered `500` where a `404` is plainly meant.
  This decision shipped with a read of the item in `BulkEntryRunner` to work
  around it, and noted that the single-item endpoints had the same defect.
  *Both are gone as of 2026-09-14: `tagging` declares `TaggableTargets` and the
  blocks that own the rows answer it, so all six tag paths say `404` for a target
  that is not there — `TagTargetsIT` holds them to it — and the workaround in
  `BulkEntryRunner` has been removed.*
- **`PROPAGATION_NESTED` is unusable in this application and should not be
  reached for.** The finding above is the reason, and it is recorded here rather
  than as a comment somebody deletes.
- **Something larger than 500 needs a different shape.** A future import path
  wanting more takes `202` and a job resource (08 §8.2), not a bigger number
  here.
