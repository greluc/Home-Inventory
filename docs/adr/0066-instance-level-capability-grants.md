# ADR-0066 — An instance-level capability grant, for what the deployment owes an account

**Status:** Accepted · **Date:** 2026-09-16
**Amends:** [ADR-0065](0065-the-plugin-call-envelope.md) (the envelope gains a scope),
[09 §9.4](../architecture/09-extensibility-and-plugins.md) (the capability model gains
a second level), [07 §7.1](../architecture/07-data-model.md) (one more table on the
closed instance-wide list)

> **Amended by [ADR-0067](0067-breached-passwords-from-a-shipped-list.md)**: the
> instance-level resolution gains a **second** named caller. A password is chosen where
> there is often no tenant — at registration, and at a reset asked for from the login
> page — so the optional `PasswordBreachCheck` plugin is resolved the same way the
> account notifications are. The rule below still names its callers exhaustively; it now
> names two.

## Context

`REQ-NOTI-004` is unambiguous: **security-relevant account events are always
reported by e-mail and cannot be switched off by a user or a tenant
administrator.** Its acceptance names four of them — password change, a new
second factor, a new device, remote sign-out — and `REQ-SEC-018` adds the
password reset, which is asked for **at the login page**: no session, no tenant,
and for an address nobody has an account for, no account either.

Everything the delivery path needs is per tenant, and each piece says so on
purpose:

| Piece | What it requires | Where it says so |
|---|---|---|
| `notification.notification` | `tenant_id NOT NULL` | its DDL |
| `Notifications.raise` | `TenantContext.require()`, and it queues only for channels the person subscribed to | `DefaultNotifications` |
| `ExtensionRegistry.lookup` | a tenant, whose grants decide whether a plugin is available at all | [09 §9.4](../architecture/09-extensibility-and-plugins.md) |
| `CallContext` | a non-null tenant — *"a call for no tenant is a call the capability model cannot answer"* | `common.proto` and `plugin-api`, under [ADR-0065](0065-the-plugin-call-envelope.md) |

An account can be a member of several tenants, or of none: registration creates
an account before any tenant exists, and leaving one's last tenant leaves the
account behind. So the requirement and the mechanism contradicted each other for
exactly the accounts that need the mail most.

## Options

| Option | For | Against |
|---|---|---|
| Deliver through a tenant the account belongs to | No change to the contract or the capability model | An account with **no** membership gets nothing, so `REQ-NOTI-004`'s *always* acquires an exception — and a tenant administrator would see their members' security mail in the tenant's delivery history, which makes an account fact into tenant data |
| **A second, instance-level grant, and a call scope that carries it** | *Always* stays true without exception · the operator, who installs the plugin, is also who permits it · the change is additive on the wire and free before the contract is published | A second level in a model whose simplicity is a feature · one more table · a widening that must be kept narrow deliberately |
| Make every account a member of some tenant | The edge case disappears | Registration would have to create a tenant nobody asked for, and leaving one's last tenant would have to be forbidden or delete the account — a change to the domain model to fit a delivery detail |
| Send the mail from the core | No plugin involved | Forbidden by [ADR-0026](0026-core-outbound-via-plugins.md): the core has no outbound route, and this is not the exception that earns one |

## Decision

**The capability model gains a second level, and the call envelope says which
level a call was made under.**

1. **`plugins.instance_capability_grant`** — instance-wide, no `tenant_id`, one
   row per `(plugin_id, capability)`, written by the **instance operator**
   ([ADR-0057](0057-the-instance-operator.md)). It is on the closed list in
   [07 §7.1](../architecture/07-data-model.md).
2. **`CallScope`** in `home_inv.plugin.v1`: `CALL_SCOPE_TENANT` (the default and
   what every caller meant before the field existed) and `CALL_SCOPE_INSTANCE`.
   `tenant_id` is empty **exactly** when the scope is `INSTANCE`. Additive on the
   wire, so `buf breaking` is satisfied.
3. **`CallContext.forInstance(...)`** in `plugin-api`, with `tenantId() == null`
   in that shape, a derived `scope()` so two fields cannot disagree, and
   `requireTenantId()` for the ports that have nothing sensible to do without a
   tenant.
4. **`ExtensionRegistry.lookupForInstance(port)`** resolves a port from the
   instance-level grants, reads no tenant's grants and needs no tenant context.

## What this deliberately is not

It is **not** a way around a tenant's consent, and the reasons are structural
rather than promised:

- **An instance call carries no tenant context**, so every row-level policy in
  the database yields zero rows. A plugin calling back into the core under one
  reaches nothing that belongs to a tenant — the same mechanism that makes a
  missing context return no data rather than foreign data
  ([ADR-0003](0003-multi-tenancy.md)).
- **The two consents are separate.** A plugin every tenant has granted everything
  is still not available at instance level: the deployment's own obligations are
  not a tenant's to permit, and the reverse holds too.
- **Only one caller may use it.** `ArchitectureRulesTest` fails the build if
  anything outside the `notification` block calls `lookupForInstance`. A widening
  with one named user is a widening; a widening anyone may use is a second
  entrance.
- **The manifest still governs.** An instance grant for a capability the current
  manifest does not declare is a leftover, not a permission — the same rule the
  per-tenant path applies.

## Consequences

- `REQ-NOTI-004` becomes satisfiable **without an exception**, for an account in
  many tenants, one, or none.
- The account-level notification itself is stored instance-wide, in its own table
  with its own delivery bookkeeping — decided with the owner on 2026-09-16, and
  the reason the per-tenant queue was not simply relaxed: a tenant's delivery
  history should not contain another person's account mail.
- `CoreApi`, when it is built (`REQ-SEC-057`), must refuse every tenant-scoped
  operation on an instance call rather than choosing a tenant for it.
  `CallContext.requireTenantId()` is what it should call to do so.
- The plugin SDKs of stage 3 must carry the scope. It costs one enum in each
  while the contract is unpublished; after publication it would have cost a major
  version ([ADR-0011](0011-api-versioning.md)).
- An operator installing `plugin-smtp` now grants twice: once per tenant for that
  tenant's invitations and reminders, once for the instance so account security
  mail can go out. The administration surface has to say so, or the second grant
  is the one nobody makes and the symptom is silence.
