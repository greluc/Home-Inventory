<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0077 — A call may carry a setting the tenant did not configure

**Status:** Accepted · **Date:** 2026-09-21

**Amends:** [ADR-0073](0073-a-plugin-is-configured-twice.md) — its rule *"what a tenant
decides arrives in the call envelope"* stands; what did not survive contact with the first
real case is the assumption that every such value belongs to the **tenant**.

## Context

ADR-0073 divided a plugin's configuration in two: the **operator** configures the
container through its environment, the **tenant** configures the integration through
values the core resolves and sends in the call envelope. The envelope is filled in one
place — `PluginResilience` — so no caller can forget to, and so one tenant's settings
cannot reach a call made for another.

That filling **replaced** whatever settings the caller had put in the context. While every
setting belonged to the tenant that was right and even protective: a core caller had no
business inventing one.

`plugin-webhook` is the first plugin where a value belongs to something **narrower than a
tenant**. Its `signingSecret` is what a receiver verifies each delivery with, and a tenant
can have several receivers — a fulfilment system, a spreadsheet, a colleague's home
automation. One secret for the tenant means **every receiver it configured can forge a
delivery to every other one**, because each of them holds the key the others' signatures
are made with. That is not a theoretical ordering problem: the whole point of signing a
webhook is that the receiver can tell a genuine delivery from one somebody else sent.

The same shape will recur. Any per-resource credential — a second object store, a printer
with its own token — is a value the tenant owns and the *resource* identifies.

## Options

| Option | What a plugin sees | What it costs |
|---|---|---|
| **Merge, caller wins** | one settings map, as before | The envelope grows one merge and one capability check. Chosen |
| Keep one secret per tenant | one settings map | The forgery above, in a feature whose reason for existing is authenticity |
| A second parameter on the port | settings **and** per-call settings | Every port method changes, the protobuf contract changes, and every plugin author has to understand a distinction that is the core's problem |
| Store the per-target secret as a tenant setting under a composed key | one map, with keys like `signingSecret.<uuid>` | A plugin would have to know how the core names things, and the manifest could not declare the key at all |

## Decision

**The envelope merges: the tenant's configuration is the base, and what the core caller put
in the context is laid over it.**

Three things hold it in place.

1. **Only the core can fill a context.** A plugin never constructs one; it receives one.
   So "the caller" here is always our own code, never foreign code.
2. **The tenant in the context still decides whose settings are read.** The merge changes
   which value wins for a key, never which tenant's row is opened. A per-call value cannot
   carry anything across a tenant boundary, because the caller already had to be acting for
   that tenant to have it.
3. **The same capability gates both.** `core:setting:read` is what a tenant grants for its
   configuration to be sent at all (ADR-0073). A per-call value is still the tenant's
   configuration, so `PluginSettings.maySendSettings` is asked before anything the caller
   brought is merged. Withdrawing the grant stops a webhook being signed, and therefore
   stops it being sent — which is the behaviour a tenant withdrawing it expects.

The secret itself is stored beside the target in `notification.webhook_target`, sealed with
the envelope encryption of [ADR-0019](0019-sensitive-field-encryption.md) and bound to the target's
id and its field key, so it cannot be moved to another target or another tenant and still
open. It is written and never read back to a person: the view type has no field for it.

## Consequences

- `plugin-webhook`'s manifest declares **no** `settings:` entry and still declares
  `core:setting:read`. A `signingSecret` field per tenant would have been a form that
  changes nothing.
- A tenant with several receivers can rotate one secret without touching the others. It
  could not before, because there was one.
- `PluginResilience` performs one extra capability check per call that carries a per-call
  setting — a read against our own database, outside the plugin's bulkhead and circuit
  breaker, exactly as the settings read already was.
- **A per-call setting silently overriding a tenant's configured one is now possible.**
  That is the price of the merge, and it is bounded by there being one caller that uses it.
  If a second appears, the rule to keep is this one: a per-call value must belong to the
  **thing being acted on**, never be a way for a caller to decide something the tenant
  configured.
