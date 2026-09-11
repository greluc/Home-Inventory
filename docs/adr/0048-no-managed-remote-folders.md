# ADR-0048 — No OpenProject-style managed folders at the remote storage

**Status:** Accepted · **Date:** 2026-09-11

## Context

OpenProject integrates with Nextcloud in a way worth looking at before we build
our own `plugin-blobstore-nextcloud`: it creates a folder per project and keeps
the permissions on it in step with the project's membership, automatically. The
question put to this ADR was whether we can build the same and whether it makes
sense.

**How OpenProject does it.** Two Nextcloud apps are required — the
`integration_openproject` app and **Team Folders** (formerly Group Folders). A
dedicated Nextcloud system user `OpenProject` is created, put in a group of the
same name, made **group administrator**, and given advanced permissions on a team
folder. Authentication is two-way OAuth 2.0: each product is an authorization
server for the other. Background workers create the folders and set per-user
permissions from project membership, and recurring synchronisation runs reconcile
them, reporting failures as named health states with a daily digest to the
administrator.

It is a good design for the problem it solves. It is not our problem.

## Decision

**We do not manage folders or permissions at the remote storage.** The
`BlobStore` adapters write into one location they were given, with a credential
scoped to that location, and touch nothing else. ADR-0007's model stands
unchanged: for Nextcloud, an app password with access to exactly one folder
([REQ-SEC-052](../requirements/03-security-and-privacy.md)).

Three reasons, strongest first.

**1. There are no folders to manage.** [ADR-0032](0032-per-tenant-blob-addressing.md)
put blobs at `sha256/<tenantId>/<hash>`. That is content addressing: the names are
hashes and the shape is flat by design. A folder tree mirroring locations would
have nothing meaningful to hold unless every photo were materialised a second
time under a human-readable name — which means two copies of every byte, two
truths about deletion, and a reference count that has to be right for both. The
feature does not sit on top of our storage model; it replaces it.

**2. It costs the security property we chose on purpose.** OpenProject's system
user must create groups and team folders and set other people's permissions —
administrative rights at the remote, in all but name. Ours holds an app password
for one folder. That is not one step further along the same axis; it is the
opposite direction. A compromised plugin container today reaches one folder. With
managed folders it would reach the Nextcloud instance. [ADR-0026](0026-core-outbound-via-plugins.md)
moved outbound calls into plugins precisely so that the blast radius of one is
small, and this would undo that for the largest one.

**3. The traffic only goes one way.** OpenProject's integration earns its
complexity because people *author* documents in Nextcloud and link them to work
packages; the remote is a place where work happens. Inventory photos are created
in the app and looked at in the app. Nobody writes an inventory photo in
Nextcloud.

There is also a fourth reason that would be decisive on its own if the first
three were not: permission synchronisation requires that our users **be** users
at the remote. OpenProject's works because both products resolve the same
identities. Ours are invited into tenants and are not Nextcloud accounts; the
tenant is an internal concept enforced by `tenant_id` and RLS
([ADR-0003](0003-multi-tenancy.md)) that Nextcloud has no way to represent.

Worth noting: OpenProject offers a second mode — *an existing folder with
manually managed permissions.* That is approximately what we already decided.

## What was taken instead

The integration's **health-status catalogue** is several years of production
experience with exactly our situation: a system depending on a remote store it
does not own. It has been adapted into
[`docs/reference/plugin-health-states.yaml`](../reference/plugin-health-states.yaml)
and made binding by [REQ-NFR-079](../requirements/02-non-functional.md).

This filled a real gap rather than adding a nicety. [13 §13.11](../architecture/13-operations-and-observability.md)
already required the operator view to show "the plugin list with **state**", and
13 §13.7 already metered plugin health every minute — but nothing said what a
state was. The registry is the missing vocabulary.

Two states in it are ours and have no OpenProject counterpart:

- **`EGRESS_DENIED`** — the `egress-proxy` refused the host because the manifest
  allowlist does not cover it ([ADR-0027](0027-egress-enforcement.md)). It is the
  state most likely to be misdiagnosed: it presents as a connection failure and
  is an authorization decision, so the fix is in our own configuration rather
  than on the remote or in the network. OpenProject has no equivalent because it
  has no egress proxy.
- **`CONTRACT_MISMATCH`** — their integration versions two products together;
  ours versions a contract that third parties implement.

The second thing taken is a rule rather than a vocabulary. OpenProject states
that its setup "expects either all entities to exist with correct permissions or
none to exist — partial states cause errors." That is now
[REQ-PLG-015](../requirements/01-functional.md): an outbound plugin verifies its
target completely before it serves and **refuses service on a partial state**
rather than working partially. The failure it prevents is the expensive one —
writes accepted against the half of a target that works, discovered when someone
tries to read them back.

One of their observations is recorded without becoming a rule: a misconfigured
proxy or an untrusted certificate chain makes the integration "fail silently".
That is why `DESTINATION_UNREACHABLE` exists as a state an operator can see,
with the note that the check belongs **inside the plugin container** — the plugin
segment has its own view of the network by design, so a workstation proving the
host is reachable proves nothing.

## Consequences

**Good.** The credential stays scoped to one folder, so the security argument of
ADR-0026 survives contact with the largest outbound integration we have. The
operator gains a defined vocabulary for plugin failure that did not exist
yesterday, and it is grounded in somebody else's production experience rather
than in our guesses about what breaks.

**Costly.** An operator who wants their inventory photos browsable in Nextcloud
does not get that, and will not. The bytes are there but the names are hashes.
If that turns out to matter, the honest answer is an **export** — a deliberate,
one-directional materialisation under human-readable names, with its own
decision about what happens when it goes stale — and not a permission-managed
mirror pretending to be live.

**Unchanged.** Everything in ADR-0007 and ADR-0026 about how the Nextcloud
adapter works. This ADR removes a possibility that was never adopted; it revises
nothing.

## Alternatives considered

**Managed folders as OpenProject does them.** Rejected for the three reasons
above.

**A read-only mirror tree beside the content-addressed blobs.** Rejected as the
worst of both: a second copy of every byte, with no permission management to show
for it, and a synchronisation problem on every delete. If a browsable copy is
ever wanted, an export is the honest shape — see above.

**Managed folders for single-tenant installations only.** Rejected. It makes the
security model depend on a deployment mode, so the small-instance path would be
the one with administrative credentials at the remote — exactly backwards — and
it would fork the plugin into two behaviours to test.
