# ADR-0027 — Egress enforcement: deny-all for the core, a proxy per plugin segment

**Status:** Accepted · **Date:** 2026-09-11
**Depends on:** [ADR-0026](0026-core-outbound-via-plugins.md)
**Amends:** [ADR-0021](0021-podman-quadlet.md), [ADR-0022](0022-rootless.md) — one
service is added to the topology.

> **Amended by [ADR-0036](0036-scanner-egress.md)**: the proxy runs in **every**
> profile, not only `standard` and `ha`, and its allowlist has a second, closed
> source — a fixed deployment list for in-deployment services that need a named
> external target and have no manifest. Today that list holds one entry, the
> ClamAV signature mirror.
>
> **§1 is withdrawn by [ADR-0042](0042-edge-is-not-internal.md), on measurement.**
> The sentence *"a published port is forwarded into the container's network
> namespace by the runtime and does not need a routed path outward"* is false: a
> container on an `internal` network gets **no port mapping at all**, and the only
> kind of network that publishes always grants outbound. `edge` is therefore not
> internal, `web` is the ingress, and `api` publishes nothing. The same trace found
> that the `egress-proxy` below sat only on internal segments — the chokepoint had
> no route through it — and gave it a non-internal `egress` segment. Flagging this
> as A5 rather than asserting it is what made the difference.
>
> **Amended by [ADR-0037](0037-per-plugin-network-segments.md)**: §2's "the proxy
> identifies the caller by source address" and §3's per-plugin TCP port are
> replaced by something stronger. Each plugin has its own segment, the proxy has
> one interface per segment, and the caller is identified by the interface the
> connection arrived on. On the single shared segment assumed below, plugin A
> could have used plugin B's forwarding port.

## Context

Three requirements demand that a plugin reaches **only** the hosts named in its
manifest, *enforced in the network rather than in code*, and verified by a CI
connectivity test: `REQ-SEC-055`, `REQ-NOTI-007`, `REQ-ENR-009`.
[`deploy/services.yaml`](../../deploy/services.yaml) stated the same as a comment
on the `plugins` network.

**None of the three runtimes can express that.** This was not noticed when the
requirements were written:

| Runtime | What it can express | What it cannot |
|---|---|---|
| Podman / `netavark` | A network is `Internal=true` or it is not | Per-container egress; any hostname rule |
| Docker Compose | `internal: true` or not | The same |
| Kubernetes `NetworkPolicy` | Egress by CIDR (`ipBlock`) and by selector | **Hostnames.** FQDN policies are a Cilium/Calico extension, not part of the API |

Hostnames are also what the manifests actually contain — `openlibrary.org`,
`services.dnb.de`, `fcm.googleapis.com`. Those resolve to large, rotating CDN
ranges, so a CIDR rewrite is not a workaround; it is a different and much weaker
requirement.

Since [ADR-0026](0026-core-outbound-via-plugins.md) moved every external call into
a plugin, this is no longer a detail of the plugin system. It is the enforcement
point for the whole system's outbound behaviour.

## Options

| Option | For | Against |
|---|---|---|
| **A forward proxy per plugin segment** | Identical under Podman, Docker and Kubernetes · works on hostnames, which is what manifests contain · yields an access log per plugin, so `REQ-ENR-009` ("inspectable in the UI which data goes to which host") is answerable from real data instead of from the manifest · testable in CI by pointing a plugin at a host it did not declare | One more container and one more thing to configure · speaks HTTP and `CONNECT`, **not SMTP** |
| `nftables` on the host | No extra container | Needs root on the host, against the "service user without sudo" rule · hostnames resolved once at setup time break silently when DNS changes · cannot be reproduced under Kubernetes, so one of the three supported paths would be unverified |
| CIDR allowlists instead of hostnames | Expressible with Kubernetes `NetworkPolicy` alone | Not expressible under Podman/Docker at all, and unusable for any target behind a CDN — which is most of them |
| Drop the network guarantee, enforce in the SDK | No infrastructure | A plugin is untrusted code. An allowlist a plugin enforces on itself is documentation, not a control. It would hollow out Zone 4 → 5 in [12 §12.2](../architecture/12-security.md) |

## Decision

**Two rules, one mechanism.**

### 1. The core has no route out — deny-all, by topology

`app-api` and `app-worker` sit on `internal` and `plugins` only. Neither segment
has a gateway to anything outside the deployment. There is no allowlist for the
core because there is nothing on it: after
[ADR-0026](0026-core-outbound-via-plugins.md), the core has no external target
left.

The `edge` segment is therefore reduced to *"the segment whose ports are
published"* and is marked internal as well. Inbound still works, because a
published port is forwarded into the container's network namespace by the runtime
and does not need a routed path outward.

> **Open for verification during implementation:** that port publishing behaves as
> described on an internal network under **rootless Podman with `pasta`** and
> under **rootless Docker**. This is the same class of item as `UserNS=auto` in
> [06 §6.5](../architecture/06-deployment-view.md) and is tracked in
> [ADR-0000](0000-open-points.md) as **A5**. If a runtime turns out not to support
> it, the fallback is a minimal ingress container on a non-internal segment that
> forwards to the core on an internal one — the property is kept, the topology
> changes.

### 2. Plugins reach only their declared hosts — through an egress proxy

One `egress-proxy` container in the `plugins` segment. Plugin containers get no
gateway of their own; their only route outward is the proxy, handed to them as
`HTTPS_PROXY`/`HTTP_PROXY` by the generated unit.

| Property | Value |
|---|---|
| Allowlist source | Generated from the `capabilities[].hosts` entries of every **granted** plugin manifest — the manifest stays the single declaration, as `REQ-SEC-055` requires. Plus one closed deployment list for services that are part of the deployment and have no manifest, today holding only the signature mirror ([ADR-0036](0036-scanner-egress.md)) |
| Granularity | Per plugin: the proxy has one interface per plugin segment and identifies the caller by **the interface the connection arrived on**, so plugin A cannot use plugin B's allowance ([ADR-0037](0037-per-plugin-network-segments.md); this read "by source address" while all plugins shared one segment, which was weaker than it sounded) |
| Protocol | HTTP and `CONNECT`, which covers every HTTPS target. Non-HTTP targets go through the TCP mode in §3 |
| On a denied host | The connection is refused and logged with plugin ID, target and time — the log is the source for the UI view `REQ-ENR-009` requires |
| Reload | On a capability grant or revocation, without restarting plugins |
| CI test | A plugin attempts a host it did not declare and must fail; a core container attempts any external host and must fail |

### 3. Non-HTTP targets: the proxy also forwards TCP

*Decided 2026-09-11, resolving open point **O11**.*

An HTTP forward proxy does not speak SMTP, and `plugin-smtp` from
[ADR-0026](0026-core-outbound-via-plugins.md) is stage-1 infrastructure
([ADR-0028](0028-plugin-runtime-stage-1.md)) — so this could not stay open.

**The same container gains a plain TCP forwarding mode.** A manifest may declare a
target as `host:port` with a protocol other than HTTP; the proxy then listens on a
per-plugin local port and forwards bytes to exactly that destination. Since
[ADR-0037](0037-per-plugin-network-segments.md) that port is bound to **that
plugin's segment interface only** — on the shared segment assumed here, any
plugin could have connected to it and reached another plugin's declared
destination, `plugin-smtp`'s mail server included.

| Property | Value |
|---|---|
| What is forwarded | Bytes, nothing else. STARTTLS on 587 and implicit TLS on 465 both pass through unchanged, because the proxy never looks inside |
| Allowlist | One destination per declared entry, `host:port` exact. There is no wildcard and no "any port on this host" |
| Logging | The same access log as the HTTP path: plugin, destination, outcome. `REQ-ENR-009` and the operator view read one source, not two |
| Not done | No protocol awareness, no MTA behaviour, no queuing. Retry and dead-lettering already exist in `notification` ([04](../architecture/04-building-blocks.md)) and are not duplicated here |

**Why this rather than the alternatives.** A separate TCP forwarder would have
meant two allowlist sources, two logs and two sets of CI connectivity tests for
one property. A minimal MTA would have been the largest attack surface of the
three and would have duplicated retry logic the notification block already owns.
Giving `plugin-smtp` its own unrestricted segment would have dropped target
enforcement precisely for the plugin that carries invitation tokens — the one
place where it is least defensible.

The cost is honest and is accepted: the proxy is no longer an off-the-shelf HTTP
forward proxy but a small component of ours with two modes. It stays small, and
its TCP mode is the simpler of the two.

## Rationale

The proxy is chosen over the host firewall for a reason that outweighs the extra
container: **it is the only candidate that behaves identically on all three
supported ways of running the system.** A mechanism that works under Podman and
Docker but not under `kind` would leave one of three CI paths unverified — which
is the exact failure mode risk R13 describes, and which the service matrix exists
to prevent.

It also turns a claim into evidence. `REQ-ENR-009` promises the tenant can see
"which data goes to which host". From a manifest that is a statement of intent;
from a proxy access log it is a record of what happened.

## Consequences

- **One more container** (~32 MB) in **every** profile — including `minimal`,
  which has no plugins but does have a scanner that needs its signatures
  ([ADR-0036](0036-scanner-egress.md)). Added to the sizing in
  [06 §6.7](../architecture/06-deployment-view.md).
- **The proxy is a chokepoint.** Its failure stops every plugin's external call at
  once. That is acceptable — the failure is visible, per-plugin degradation is
  already designed for ([13 §13.6](../architecture/13-operations-and-observability.md)),
  and the alternative is no enforcement at all. It is added to the degradation
  table as its own row.
- **The proxy sees plaintext metadata, never plaintext content.** With `CONNECT`
  it observes host and port; TLS terminates at the real target. It is explicitly
  **not** a TLS-intercepting proxy — that would put every plugin's credentials
  within reach of one container.
- **`REQ-SEC-055` gains an implementable acceptance criterion** and stops being a
  requirement that no runtime can satisfy.
- **The core's "no outbound" property becomes a CI test, not a claim**: every core
  container, every external host, expected result *refused*.
- Plugin authors must tolerate a proxy in the environment. The SDKs set it up for
  them; the contract test suite verifies that a plugin honours `HTTPS_PROXY`
  rather than opening raw sockets.
