# ADR-0059 — The location scope is enforced in the application *and* in row-level security

**Status:** Accepted · **Date:** 2026-09-13

## Context

`REQ-TEN-007` confines a role to part of the location tree, and its acceptance is
blunt: *"a user scoped to 'garage' sees nothing beyond it"*.

[12 §12.5](../architecture/12-security.md) lays out five layers and assigns this
one to layer **3**: "object scope — checked on the **loaded** object: does it
belong to the tenant, does it lie in the permitted location subtree", with layer
5, row-level security, guarding the tenant boundary.

Read literally, that means every query that returns a place or an item filters
itself. There are a dozen such queries today and there will be more; the one that
forgets is not a defect that shows up as a wrong answer but as somebody seeing
a room they were confined out of. Nothing generic can catch it — the tenant
boundary has `TenantIsolationProofIT`, which enumerates every table from the
catalogue and would fail on a new one, and no equivalent exists for "every query
filtered by a subtree", because a query is not something a catalogue lists.

The same argument was made once before, for the tenant boundary itself, and
[ADR-0003](0003-multi-tenancy.md) answered it: "RLS is the only way to guarantee
tenant separation **independently of the application logic**… a forgotten `WHERE`
clause then returns zero rows instead of foreign data."

## Decision

**Both layers, and the application layer stays exactly where 12 §12.5 puts it.**

- **Layer 3 is unchanged.** The services check the loaded object and refuse with
  a `404` — the answer a caller gets for anything outside what they may see
  (`REQ-SEC-025`). That is what produces a comprehensible answer.
- **Layer 5 gains a second session setting**, `app.location_scope`, holding the
  scope's **`ltree` path**, and the policies on `locations.location` and
  `inventory.item` compare against it. That is what makes a forgotten filter
  return nothing rather than somebody else's room.

The setting carries a *path* rather than the id the membership stores, because a
policy that resolved an id would do it per row. It is resolved from the id at the
start of every transaction, beside `app.tenant_id` and by the same mechanism, so
a subtree that was re-parented since the session began is read correctly — the
path is looked up, never remembered.

**An unresolvable scope yields nothing.** A scope id naming a place that was
deleted, or that belongs to another tenant, sets the path to a label no tree
contains rather than to the empty string. The empty string means "no scope" and
would fail *open*, which is the one outcome this may not have —
`LocationScopeIT` asserts it directly, with a scope pointing at a place the
tenant does not have.

`inventory.item` has no path of its own, so its policy asks
`locations.location` for one. That is a statement in one block's migration naming
another block's schema, which [07 §7.9](../architecture/07-data-model.md) forbids
with a documented exception list — and this goes on it, beside the composite
foreign keys that are there for the same kind of reason.

## Consequences

- **A scoped session reads nothing outside its subtree, even through a query
  nobody remembered to filter.** That is the whole point, and `LocationScopeIT`
  asserts it directly: a scoped session, the ordinary service methods with no
  scope argument anywhere, and an answer holding only the subtree.
- **An item with no place is invisible to a scoped session.** A digital item is in
  nobody's garage. This is a consequence of the policy and of layer 3 agreeing,
  and it is stated here because it is the one part of the behaviour somebody
  would otherwise call a bug.
- **A scoped read costs an index lookup per item row**, from the policy's
  `EXISTS`. An unscoped session pays nothing: the setting is empty and the
  condition short-circuits before the subquery is considered.
- 12 §12.5's table gains a line. Layer 3 is still described as it was; what
  changes is that layer 5 is no longer only about tenants, and a reader who
  believed the subtree lived in one place would have been half right.
- `app.location_scope` joins `app.tenant_id` as a setting the transaction manager
  publishes, and it is cleared the same way — written as the empty string rather
  than skipped, so a pooled connection cannot carry a previous request's scope
  into the next one.
