# ADR-0037 — One network segment per plugin, and the management port out of their reach

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [ADR-0027](0027-egress-enforcement.md),
[ADR-0026](0026-core-outbound-via-plugins.md),
[09 §9.5](../architecture/09-extensibility-and-plugins.md),
[13 §13.3](../architecture/13-operations-and-observability.md),
[`deploy/services.yaml`](../../deploy/services.yaml)

> **[ADR-0026](0026-core-outbound-via-plugins.md) was added to this line on 2026-09-11**:
> its *"What stays in the core"* table still placed the datastores on a `plugins` segment
> that this ADR removed. A missing back-link, same as the one in
> [ADR-0042](0042-edge-is-not-internal.md).

> **Amended by [ADR-0071](0071-the-core-answers-plugins-on-one-channel.md)**: the core
> gains a **second listener**, on its own port, which plugin segments may reach. Everything
> below stands: plugin-to-plugin traffic is still refused, and port 8090 still refuses every
> plugin segment. What changed is that one narrow service answers there — a plugin may ask
> for a document to be rendered from content it already holds, and for nothing else.

## Context

[09 §9.5](../architecture/09-extensibility-and-plugins.md) promises every plugin
*"its own network segment"*. The topology delivered one shared `plugins` segment
holding every plugin container, `clamav`, `egress-proxy`, and — because they must
speak gRPC to plugins — `api` and `worker`. Three consequences follow that the
promise was written to prevent, and none of them needs a capability grant:

1. **The management port is reachable from Zone 4.**
   [13 §13.3](../architecture/13-operations-and-observability.md) argues that
   `{ container: 8090, host: null }` makes "not publicly reachable" *"a property
   of the topology, not a reverse-proxy rule an operator has to remember to
   write"*. It is — against the host and the internet. It is not against a
   container sharing the segment. `api` and `worker` both listen on 8090 inside,
   and both sit on `plugins`, so any plugin can read `/actuator/prometheus`,
   including the per-tenant domain metrics that section lists (items, locations,
   bytes per tenant).

2. **`clamd` is reachable from Zone 4.** ClamAV sat on `plugins`. Its TCP
   interface takes commands from anything that can connect. A plugin with no
   capability at all could exhaust or stop it — and the scan is **fail-closed**
   ([ADR-0024](0024-malware-scan.md)), so that blocks every upload in the
   instance.

3. **Per-plugin egress granularity was not actually per-plugin.**
   [ADR-0027 §2](0027-egress-enforcement.md) identifies the caller *"by source
   address"*, and §3 gives non-HTTP targets *"a per-plugin local port"* on the
   proxy. On one shared segment, plugin A can connect to the port belonging to
   plugin B and reach B's declared destination — for `plugin-smtp`, the mail
   server that carries invitation tokens. Source-address identity on a shared L2
   segment is the weakest link in a design that otherwise refuses to derive trust
   from a connection ([09 §9.6](../architecture/09-extensibility-and-plugins.md):
   *"No trust is derived from the connection alone"*).

The three are one problem: **Zone 4 was a shared room, and the trust boundary
drawn in [12 §12.2](../architecture/12-security.md) assumed private ones.**

## Options

| Option | Closes ① | Closes ② | Closes ③ | Cost |
|---|---|---|---|---|
| Bind the management port to the internal interface only | yes | no | no | one property |
| That, plus move `clamav` to its own segment | yes | yes | no | one property, one network |
| **One segment per plugin, plus both of the above** | yes | yes | **structurally** | *n* networks, generated; the proxy gains an interface per plugin |
| Authenticate plugins to the proxy with a per-plugin credential | no | no | yes | a credential to issue, rotate and revoke — and it re-introduces exactly the "trust from a token the caller presents" shape the capability model deliberately re-checks server-side |

## Decision

**Each installed plugin gets its own `internal` network segment.** The generator
creates one per plugin from the service matrix, named `plugin-<id>`. Each segment
contains exactly four members:

| Member | Why it is there |
|---|---|
| the plugin container | it is the segment's subject |
| `api` | the core calls the plugin over gRPC/mTLS |
| `worker` | the same, from the worker role |
| `egress-proxy` | the plugin's only route outward |

**No plugin shares a segment with another plugin.** Two plugins cannot reach each
other at all — not over the network, and already not at host level, because each
has its own UID range (`REQ-SEC-088`).

### The scanner gets its own segment too

`clamav` moves off the plugin segments entirely onto `scanner`, holding `worker`,
`clamav` and `egress-proxy`. `api` is **not** on it: the scan runs in the worker
and never in the request path ([ADR-0024](0024-malware-scan.md)), and `api`
learns the scanner's state from the dependency health model
([13 §13.5](../architecture/13-operations-and-observability.md)) rather than by
speaking to `clamd` itself. `egress-proxy` is there for `freshclam`
([ADR-0036](0036-scanner-egress.md)).

### The management endpoints bind to one interface

`management.server.address` is bound to the `internal` segment's address. The
Prometheus endpoint and the health endpoints are then reachable from `internal`
— where a monitoring stack sits — and from nowhere else. `host: null` stays; it
was never wrong, only insufficient.

> The distinction is worth keeping in one sentence, because it is the mistake
> this ADR exists to correct: **an unpublished port is hidden from the host, not
> from a neighbour.**

### Per-plugin egress becomes an interface property

The proxy now has one interface per plugin segment. It identifies the caller by
**which interface the connection arrived on**, not by the source address it
claims, and binds each per-plugin TCP forwarding port to that interface alone.
[ADR-0027](0027-egress-enforcement.md)'s per-plugin granularity stops being an
assumption about a shared segment and becomes a property of the topology — the
same move this project makes everywhere else, from RLS to rootlessness.

### Kubernetes

One `NetworkPolicy` per plugin instead of one for the segment, alongside the
`ServiceAccount` each plugin already has
([06 §6.8](../architecture/06-deployment-view.md)). The default stays `deny-all`.

## Rationale

The cheapest option — bind the management port and move the scanner — closes the
two holes that exist today and leaves the third as a rule the proxy enforces
about a segment it does not control. That is the shape
[ADR-0027](0027-egress-enforcement.md) already rejected once, for the same
reason: *"an allowlist a plugin enforces on itself is documentation, not a
control"*. Source-address identity on a segment shared with the callers is a
milder version of the same mistake.

One segment per plugin costs *n* generated networks and nothing else. There is no
new component, no new credential, no new failure mode — the service matrix
already generates networks, and a plugin is already a unit the matrix knows.
Against that, it makes a promise that is currently written down and false become
true, and it lets the proxy stop reasoning about who is calling.

**What it does not do**, stated plainly: it does not isolate plugins from the
core. `api` and `worker` are on every plugin segment by necessity, so a plugin
still reaches whatever the core listens on. That is why the management port has
to be bound as well — the segmentation moves the problem, and the binding closes
it. Isolating the core's gRPC listener from its HTTP listener is a further step
and is deliberately not taken: the gRPC surface is mTLS-authenticated and
capability-checked per call ([09 §9.6](../architecture/09-extensibility-and-plugins.md)),
which is the protection that layer is supposed to carry.

## Consequences

- **[09 §9.5](../architecture/09-extensibility-and-plugins.md) becomes accurate.**
  "Its own network segment" was aspirational; it is now the topology.
- **The service matrix gains a network per plugin**, generated, so
  [`deploy/services.yaml`](../../deploy/services.yaml) stays the single source and
  the drift check (`REQ-NFR-063`) covers them. A hand-written plugin unit that
  joins a foreign segment fails the comparison.
- **Two new requirements:** `REQ-SEC-098` (per-plugin segment; a plugin reaches
  no other plugin, no scanner and no management endpoint) and `REQ-SEC-099` (the
  management endpoints are bound to `internal` and are unreachable from any
  plugin segment). Both are verified by the connectivity test that
  [ADR-0027](0027-egress-enforcement.md) already requires, extended rather than
  duplicated.
- **`REQ-SEC-054` widens** from "reaches neither PostgreSQL nor OpenSearch,
  RabbitMQ or Valkey" to include the scanner, the management endpoints and every
  other plugin.
- **The connectivity test gains cases**, and they are cheap: two plugins are
  started, each tries the other, the scanner, and `:8090` on both core roles;
  every attempt must fail.
- **`UserNS=auto` and the network segmentation are now two independent
  boundaries** around the same unit, which is what `REQ-SEC-088` always implied.
  Where `UserNS=auto` turns out not to be viable rootless
  ([06 §6.5](../architecture/06-deployment-view.md)), the network boundary still
  holds — they no longer fail together.
- **A5 in [ADR-0000](0000-open-points.md) is unchanged in substance and larger in
  scope**: port publishing from an internal segment now has to hold for *n*
  segments rather than three. The fallback named there — an ingress container on a
  non-internal segment — is unaffected, because it concerns `edge` only.
