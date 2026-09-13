# ADR-0057 — The instance operator is a flag on an account, not a role in a tenant

**Status:** Accepted · **Date:** 2026-09-13

## Context

Several stage-1 requirements need somebody who is **above every tenant** and
whose authority no tenant grants:

- `REQ-TEN-002` — "an **entitled** user can create any number of tenants". The
  entitlement is not something a tenant can hand out; a tenant that could would
  be granting the right to create a second one.
- `REQ-SEC-072` — impersonation ("view as user") "is available **only to the
  instance operator**".
- [13 §13.9](../architecture/13-operations-and-observability.md) — creating,
  suspending and resuming tenants, and setting quotas "per tenant; instance-wide
  defaults".
- `REQ-PLG-013` and [09 §9.8](../architecture/09-extensibility-and-plugins.md) —
  a plugin is installed by the operator, never from inside the running system.

The corpus used the words "the operator" and "the administration UI" throughout
and never said what either **is**. `authorization.Role` has six values and every
one of them is a role *within* a tenant; `OWNER` is the top of that ladder and
stops exactly at the tenant boundary, which is the point of the ladder.

Three shapes were available.

| | Grant is | Impersonation | Change takes effect |
|---|---|---|---|
| A flag on the account | a row, audited | possible | immediately |
| A second one-shot service, like `bootstrap` | a container the operator runs | needs its own answer later | on the next run |
| An environment allowlist of addresses | a redeploy | not expressible | on restart |

The one-shot service is what [ADR-0053](0053-first-owner-as-a-one-shot.md) chose
for creating the very first account, and its reasoning is good: "creating an
owner is the most privileged action this system performs and it has no business
living in the process that serves the internet." That reasoning holds for
**bootstrapping**, which happens once, before anybody can log in. It does not
stretch to impersonation, which is an interactive flow inside a session, nor to
a quota an operator adjusts while somebody waits.

## Decision

**The instance operator is `identity.app_user.instance_operator`, a boolean on
the account**, and the operator's own actions live under `/api/v1/instance/**`.

- The **bootstrap account gets it** ([ADR-0053](0053-first-owner-as-a-one-shot.md)):
  a fresh instance must have exactly one person who can grant anything, and the
  one-shot service is where that person is already created. Every further
  operator is granted by an existing one.
- **It is not a `Role` and not a permission**, and it is deliberately not on the
  `authorization` ladder. Those six roles are evaluated against a tenant context;
  this flag is read where there is none, or where the context belongs to a tenant
  the operator is not a member of.
- **`/api/v1/instance/**` is the only place it is honoured.** No tenant-scoped
  endpoint reads it, and holding it grants nothing inside a tenant: an operator
  who is not a member of a tenant still reads zero of its rows, because
  row-level security answers to `app.tenant_id` and not to who asked. Reaching a
  tenant's data is what impersonation is *for*, and impersonation is a separate,
  audited, time-limited act with its own record — not a side effect of the flag.
- **Every operator action writes an audit entry** naming the operator, the
  action and its target, and **re-confirmation of the second factor** is required
  for each of them (`REQ-SEC-021`, `REQ-AUTH-011`). Until the second factor
  itself exists, the endpoints require the flag and write the entry; the
  re-confirmation is wired in with `REQ-AUTH-002` in the same stage, and no
  operator endpoint ships to a release without it.
- **Two entitlements are separate from it**: `may_create_tenants` and the
  per-user tenant limit are their own columns, granted **by** an operator rather
  than being the same thing as being one. An operator sets them; holding the
  flag does not imply holding them, so an operator who administers an instance
  does not silently accumulate tenants of their own.

## Rationale

The alternative that was genuinely close is the one-shot service, and what
decides against it is `REQ-SEC-072`: impersonation is a flow with a beginning, a
second factor, a time limit, an audit entry and a banner the affected user sees.
None of that is expressible as a container that runs and exits. Having two
mechanisms — a service for entitlements and something else for impersonation —
would mean two answers to "who is the operator", and the second one would be the
one nobody reviews.

The environment allowlist fails for a plainer reason: it cannot be audited. An
address appearing in a variable says nothing about who put it there or when, and
`REQ-SEC-068` requires every mutating action to produce an entry with an actor.

## Consequences

- `identity.app_user` gains three columns — `instance_operator`,
  `may_create_tenants` and `tenant_limit` — and `identity` therefore gains
  facts that are neither authentication nor tenant data. That is where they
  belong: all three are properties of a **person on this instance**, and the
  table is the one instance-wide table there is
  ([07 §7.1](../architecture/07-data-model.md)).
- `identity` still knows no tenants and no permissions ([ADR-0005](0005-identity.md)).
  It **stores** the three columns and evaluates none of them: `authorization`
  declares the `AccountEntitlements` port and answers with it, which is the same
  direction every other cross-block question runs in and what keeps the two
  acyclic.
- **The flag is not in the session.** `AuthenticatedUser` does not carry it, and
  that is the difference between an entitlement and a role: a role is established
  at login and travels with the principal, while an entitlement is granted by an
  operator to somebody who may be signed in while it happens. It is read from the
  account on every call, so a grant takes effect at once rather than at the
  granted person's next sign-in — which is what the table above promises.
- An operator locking themselves out is possible and is **not** guarded against
  in code: the last operator can clear their own flag, and deciding in the
  application which operator is the last one is a question it cannot answer while
  somebody else is deleting an account. The recovery is the one-shot service that
  created the first account, run again with the same address. It restores the flag
  **only when no operator is left**: doing so unconditionally would mean an
  instance that has deliberately moved operatorship elsewhere gets it handed back
  to the bootstrap address on every redeploy, silently.
- The administration surface every chapter refers to now has an address:
  `/api/v1/instance/**` in the API, and the operator area of the web client.
- **The one-shot `bootstrap` service now depends on this block**, because the
  first account has to be made an operator and nobody else can do it. That
  service runs with the database credentials and nothing else
  ([ADR-0053](0053-first-owner-as-a-one-shot.md)), so everything it reaches must
  need no other secret — a constraint that was discovered the hard way on the day
  this was written, when a paged operator listing pulled in the URL signing key
  and a fresh deployment stopped at "Error starting ApplicationContext". The
  listing lives in its own port for that reason, and `BootstrapIsolationIT` now
  fails the build rather than the smoke suite failing the deployment.
