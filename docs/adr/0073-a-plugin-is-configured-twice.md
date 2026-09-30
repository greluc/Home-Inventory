<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0073 — A plugin is configured twice: by its operator and by each tenant

**Status:** Accepted · **Date:** 2026-09-20

**Amends:** [ADR-0071](0071-the-core-answers-plugins-on-one-channel.md) (the reason the host
channel does not grow a second method)

**Depends on:** [ADR-0019](0019-sensitive-field-encryption.md),
[ADR-0065](0065-the-plugin-call-envelope.md), [ADR-0072](0072-first-party-plugins-live-here.md)

## Context

[09 §9.3](../architecture/09-extensibility-and-plugins.md) has shown a `settings:` block in
every published manifest example since the chapter was written, `REQ-PLG-004` names settings
as part of what a manifest brings, and `core:setting:read` has been in the capability set for
as long. **None of it was connected to anything.** There was no table, no endpoint, no field
on the wire and no code — a plugin had no way to obtain a setting, and a tenant had no way to
give it one.

That is invisible until a plugin needs to be told something, which is exactly what
[ADR-0072](0072-first-party-plugins-live-here.md) then scheduled five of. Every one of them
needs configuration: an SMTP host and its credentials, an S3 endpoint and its keys, a
Nextcloud URL, an OIDC issuer and client secret, a webhook signing key.

The obvious repair — a second method on the host channel, `GetSettings` — is refused by
[ADR-0071](0071-the-core-answers-plugins-on-one-channel.md)'s own admission test: *a method
that answers a question the caller could not already answer does not belong on this service.*
Settings are precisely that. So the question is not how a plugin **fetches** configuration
but where configuration **lives**.

## Decision

**Two kinds of configuration, two mechanisms, and the line between them is who decides.**

| | Who decides | Where it lives | How the plugin gets it |
|---|---|---|---|
| **Instance configuration** | the operator, once for the deployment | `deploy/services.yaml` → the container's environment and mounted secrets | it is simply *there*, like every other service's credentials |
| **Tenant settings** | each tenant administrator | `plugins.plugin_setting`, per tenant, secrets sealed | in the **call envelope**, on every call |

**A manifest describes what a tenant may set; a container describes what an operator must
set.** The `settings:` block of a manifest is therefore, from now on, exactly the per-tenant
list — the SMTP host never appears in one.

### Why the operator's half never passes through the core

Because it does not have to, and everything that passes through the core is something the
core then holds. An SMTP password in `plugins.plugin_setting` is a credential of the
*deployment* sitting in a *tenant's* row, readable by whoever can reach that tenant's data
key. In the container it is a mounted secret, with the same handling as the database
password, and the core never sees it at all.

It also matches what a plugin already is: a container an operator declares (`REQ-PLG-013`).
Configuring it like one is the smaller of the two designs, not the larger.

### Why the tenant's half travels in the envelope

Because the alternative is a read on the host channel, and ADR-0071 refuses it. Putting the
settings in [ADR-0065](0065-the-plugin-call-envelope.md)'s envelope adds no surface at all:
the plugin already receives a `CallContext` on every call, the core already knows which tenant
the call is for, and nothing new can be *asked*.

Three consequences are taken deliberately:

- **A secret is sealed at rest** with the envelope encryption of
  [ADR-0019](0019-sensitive-field-encryption.md) — tenant, plugin and setting key bound into
  the ciphertext — and opened only as the call goes out, over mutual TLS, to the plugin whose
  id is in that ciphertext's own AAD. It is never returned by an API, never logged, never in
  a problem document.
- **`core:setting:read` changes meaning and keeps its name.** It no longer means "the plugin
  may fetch its settings" — nothing fetches — but **the core may send them**. Without the
  grant a plugin receives an empty map, which keeps `REQ-PLG-005`'s sentence true for the one
  kind of value a tenant is most likely to mind.
- **The manifest decides what may be stored.** A key it does not declare is refused, as is a
  value outside a declared `enum`'s list or of the wrong declared type. Storing one would
  leave it waiting to become live the day an update declared it — the same mistake as
  consenting to a capability nobody asked for (`REQ-PLG-006`).

## Options

| Option | New surface | Where a deployment's credentials end up | Per-tenant configuration |
|---|---|---|---|
| **Both, split by who decides** | a map on the envelope | in the container | yes |
| Container only | none | in the container | no |
| Envelope only | a map on the envelope | in the core, per tenant | yes |
| A `GetSettings` on the host channel | a read method | in the core | yes |

"Container only" is the smallest and was not chosen because a tenant's own webhook signing
key has nowhere to go in it. "Envelope only" would put the deployment's SMTP password in a
tenant's row for no gain. The fourth contradicts ADR-0071 in writing and would need it
amended; the first three do not.

## Consequences

- **`plugins.plugin_setting`** exists, per tenant, with row-level security like every other
  table, and `PluginSettings` is the block's published way to read and write it.
- **The envelope carries a map** — `CallContext.settings`, `map<string, string> settings = 5`
  in `common.proto`. Additive on the wire, so `buf breaking` passes; the Java record gains a
  component and keeps a four-argument constructor so that existing callers compile.
- **It is filled in one place**: `PluginResilience`, the envelope every plugin call already
  goes through, before the bulkhead and before the breaker — reading our own database must
  not occupy a plugin's concurrency slot or count towards opening its circuit.

  > **Amended by [ADR-0077](0077-a-call-may-carry-a-setting-the-tenant-did-not-configure.md)**
  > — the one place is still one place, and what it does there changed on 2026-09-21. It
  > **replaced** whatever the caller had put in the context, on the assumption that every
  > setting belongs to the tenant; `plugin-webhook`'s signing secret belongs to one
  > **target**, because one secret for a tenant lets every receiver it configured forge a
  > delivery to every other one. The tenant's configuration is now the base and the core
  > caller's is laid over it, under the same `core:setting:read` grant.
- **A tenant administrator gets a permission of their own**, `plugins:setting:write`, separate
  from consent: consenting says foreign code may touch this tenant's data at all, configuring
  says what it should do once it may.
- **Every first-party plugin's `README` states which half is which** for that plugin, because
  the split is only useful if an operator can see where a given value goes.
