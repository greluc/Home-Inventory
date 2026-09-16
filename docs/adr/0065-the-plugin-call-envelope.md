# ADR-0065 — A plugin call is resolved to a port instance and wrapped in one envelope

**Status:** Accepted · **Date:** 2026-09-14

**Depends on:** [ADR-0026](0026-core-outbound-via-plugins.md),
[ADR-0028](0028-plugin-runtime-stage-1.md), [ADR-0064](0064-the-ports-a-plugin-implements-are-apache.md)

> **Amended by [ADR-0066](0066-instance-level-capability-grants.md)**: the envelope's
> `CallContext` gains a **scope**, and a call may be made for the instance rather than for
> a tenant. Everything below stands — an instance call is resolved, wrapped and bounded
> exactly like a tenant call; what changes is that `tenant_id` is empty in the one shape
> where the grant came from the instance operator rather than from a tenant, which is what
> lets `REQ-NOTI-004` reach an account that belongs to no tenant.

## Context

`REQ-PLG-002` makes out-of-process plugins over gRPC with mTLS the default for
third-party code, and `REQ-PLG-007` requires **every** plugin call to carry a
deadline, a payload limit, a bulkhead and a circuit breaker. [04 §4.4](../architecture/04-building-blocks.md)
says the `plugins` block publishes `ExtensionRegistry.lookup(Port.class, tenantId)`.

Three things follow that the chapters leave open, and each of them decides code in
every block that ever calls a plugin.

## Decisions

### 1. `lookup` returns an instance of the `plugin-api` port, not a channel

A block that calls a plugin receives a
`de.greluc.homeinv.plugin.api.port.NotificationChannel` — the Apache-2.0
interface of [ADR-0064](0064-the-ports-a-plugin-implements-are-apache.md) — and
never a `ManagedChannel` or a generated stub.

| Option | Adapters per port | In-process (REQ-PLG-003) |
|---|---|---|
| **A port instance** | two: stub → port, port → the block's own port | the same interface, loaded rather than dialled |
| A channel with an identity | one | a second code path **in every block** |
| Both, by runtime kind | one and a half | every block knows both, and the rare one is untested |

The second option is what `GrpcBlobStore` does today and is cheaper by one class
per port. It was not taken: in-process plugins are a requirement
(`REQ-PLG-003`, `REQ-SEC-058`), and under it every calling block would carry a
branch for them. A branch that runs only in the configuration nobody enables is a
branch that does not work.

### 2. The envelope is a proxy around the port, not a gRPC interceptor

Two of the four protections are gRPC's own and are set on the stub: the deadline
(`withDeadlineAfter`, per call, because a deadline set once on a long-lived stub
counts from when the stub was made) and the payload limit
(`maxInboundMessageSize`, 8 MiB as 09 §9.5 names — gRPC's own default of 4 MiB is
passed by a rendered label sheet).

The bulkhead and the circuit breaker are not about bytes but about **calls**, and
a gRPC interceptor sees an asynchronous call lifecycle where "may I make another
one" is awkward to ask. Wrapping the port interface in a dynamic proxy asks it
once, for every port, and covers an in-process plugin too, which an interceptor
could not reach at all.

**`ExtensionRegistry` hands out nothing unwrapped**, so there is no instance on which a
caller could forget the envelope — which is what makes `REQ-PLG-007`'s "every plugin call"
a property of the code rather than of everybody's diligence.

### 3. Resilience4j, not a hand-written breaker

Apache-2.0, the core libraries rather than the Spring Boot starter — the starter
binds to a Boot version and annotates beans, and what this needs is an instance
**per plugin**, which is a registry lookup and not an annotation.
`resilience4j-micrometer` puts the breaker's state and the pool's occupancy in the
metrics endpoint an operator already reads (13 §13.7), which 09 §9.5 asks for and
which a hand-written breaker would have had to grow.

**Only a failure that says the plugin is broken counts towards the breaker.** A
resolver answering "I do not know this code" and a channel answering "that is not
an address" have both worked; a breaker that counted them would open on a plugin
doing its job, and a metadata resolver declines most codes it is offered.
`UNAVAILABLE`, `DEADLINE_EXCEEDED` and `INTERNAL` count; `UNSUPPORTED`,
`NOT_FOUND`, `INVALID_ARGUMENT` and `DENIED` are answers.

### 4. A plugin is available to a tenant once it has one grant

Registered, not disabled, and **this tenant has granted at least one of the
capabilities the manifest declares**. Each capability is then checked where it is
exercised: in the core when the plugin calls back (`REQ-SEC-057`), in the egress
proxy when it opens a connection ([ADR-0027](0027-egress-enforcement.md)).

Requiring *every* declared capability was the alternative and contradicts the
decision recorded for `REQ-PLG-006` on the same day: a manifest that asks for more
leaves what was granted granted, and the plugin carries on. Under the stricter
rule a plugin's own update would disable it for every tenant until somebody had
time to look — which is the outcome that decision exists to prevent.

## Consequences

- **The limits are the operator's.** `spec.resources.timeoutSeconds` in a manifest
  is a request: a plugin may ask for **less** than the operator allows and get it,
  and may not ask for more. The manifest block is optional, and a manifest without
  one gets the default — which is the defect the first run of `PluginRuntimeIT`
  found, in the shape of a null.
- **A plugin that cannot be reached is skipped, not raised.** Another plugin may
  implement the same port and be answering, and a caller asking for a notification
  channel should get the one that works.
- **A lookup must run as the tenant it names.** Grants are read under row-level
  security, which answers to `app.tenant_id` and not to an argument, so a lookup
  for tenant B inside a context running as A would return A's plugins under B's
  name. `DefaultExtensionRegistry` refuses that rather than letting it happen: the
  parameter 04 §4.4 specifies stays, and is checked.
- **The breaker is per plugin, not per port.** A plugin is one process, and a
  process that is down is down for every port it serves.
- **`PluginCircuitOpened` is raised on the transition**, not per failed call: one
  event per outage is what an operator can act on, and a failing plugin must not
  fill the event log with the evidence of its own failure. It is not externalised
   — a plugin has no business being told the core gave up on it, and a durable
  queue would replay an outage that is over.
- **The certificate is pinned per plugin**, from the registration, and a plugin
  registered without a fingerprint is not called at all (`REQ-SEC-056`).
  `PluginRuntimeIT` proves it against a certificate this deployment's own CA
  signed — a valid member of the deployment that is still not this plugin.
