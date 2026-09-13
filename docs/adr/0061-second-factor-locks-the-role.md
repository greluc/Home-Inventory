# ADR-0061 — A role that requires a second factor is granted, and locked until the factor exists

**Status:** Accepted · **Date:** 2026-09-13

## Context

`REQ-AUTH-003` makes the second factor **mandatory** for `OWNER` and `ADMIN`, and
for any role that may read `sensitive` fields (`REQ-SEC-015`). Its acceptance was
written as *"assigning either role without one is rejected"*, and read literally
that is not implementable:

- **Creating a tenant makes an owner.** `REQ-TEN-002` lets an entitled account
  create one; the creator is its `OWNER` the moment it exists. Refusing the
  assignment would mean a tenant with no owner, which nobody can administer and
  nobody can delete.
- **The first owner is made by a one-shot.** `bootstrap` runs before anybody can
  reach the instance ([ADR-0053](0053-first-owner-as-a-one-shot.md)) and has
  nobody to ask for a code. An exception for it would exempt exactly the account
  that may do the most.
- **Every membership that predates this** would have to be either dropped or
  grandfathered, and a grandfathered `ADMIN` is a role the requirement says must
  not exist.

Accepting an `OWNER` invitation has the same shape one step further on: the
acceptance *creates* the account (`REQ-TEN-004`, proved by
`MembersAndInvitationsIT`), so it cannot already have an authenticator.

## Decision

**The role is granted. Using it is what waits.**

- The membership is written exactly as before. Nothing in `tenancy` asks about
  credentials, and no flow is refused for the lack of one.
- A session whose role requires a factor, held by an account with none, is
  refused **every request in that tenant** with `403` and the problem type
  `second-factor-missing`, whose detail says where to enrol.
- Two paths keep answering, because they are the way out:
  `/api/v1/auth/**` — signing in, signing out, and the enrolment itself — and
  `/api/v1/me/**`, so somebody locked out of one tenant can switch to another
  where they hold an ordinary role. `/api/v1/tenants/**` is deliberately **not**
  exempt: an owner who has not enrolled administers nobody.
- **Granting sensitive field visibility** to a role whose live members have no
  factor is refused with the same type, at the point the grant is written rather
  than in the controller, so a second caller cannot reach the write without it.
  The message says how many members, never which — the reasoning `REQ-SEC-016`
  applies to addresses, applied to credentials.

`REQ-AUTH-003`'s acceptance is amended to say this, because the implementation
must not quietly mean something the catalogue does not.

**Three ports carry it, and each runs the way the graph already runs.**
`authorization` decides which roles need a factor, so it declares what it needs:
`SecondFactorStatus`, implemented by `identity`, and `RoleHolders`, implemented
by `tenancy`. Both of those blocks already depend on `authorization`; a call in
the other direction would have closed a cycle.

## Consequences

- **A fresh deployment stops on the first request and says why.** The operator
  signs in, gets `second-factor-missing`, enrols, and carries on. The smoke
  journey does exactly that — it is the first thing it does after the login, and
  it generates its own codes in POSIX shell rather than adding a host
  prerequisite.
- **The web client gained a screen.** The login answers the challenge, and a
  locked session shows the enrolment rather than an error repeated on every
  panel.
- **Every integration test that acts as an owner enrols first.** A helper on the
  base class writes the confirmed credential, and the sign-in helper answers the
  challenge; the enrolment loop itself is proved once, in `SecondFactorIT`.
- **A locked owner is not a stranded one.** They can still sign out, read who
  they are, list their tenants and switch away. That is the difference between a
  lock and a dead end, and it is why the two exempt paths are named here rather
  than left to a filter.
- **A role that gains a sensitive field gains this requirement with it.** A
  tenant that grants `purchasePrice` to `MEMBER` has made `MEMBER` a protected
  role, and its members are refused until they enrol. That is a consequence
  somebody will meet without expecting it, which is why the grant is refused
  while any of them has no factor: the surprise lands on the administrator making
  the change rather than on the members.
