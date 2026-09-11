# ADR-0042 — A published port needs a non-internal segment; `web` becomes the ingress

**Status:** Accepted · **Date:** 2026-09-11
**Resolves:** A5 in [ADR-0000](0000-open-points.md) — **by measurement, and against the assumption**
**Amends:** [ADR-0027 §1](0027-egress-enforcement.md),
[06 §6.7](../architecture/06-deployment-view.md),
[`deploy/services.yaml`](../../deploy/services.yaml), `REQ-PRIV-003`

## Context

[ADR-0027 §1](0027-egress-enforcement.md) gave the core no outbound route by
marking **every** segment internal, including `edge`, and rested that on one
sentence:

> *"Inbound still works, because a published port is forwarded into the
> container's network namespace by the runtime and does not need a routed path
> outward."*

It was flagged as needing verification (**A5**) rather than assumed silently,
which was right — because it is **false**.

### What was measured

Docker 29.7.2, bridge networks, nginx-alpine, a published port and a control on
an otherwise identical non-internal network:

| Topology | `docker port` | Published port from the host | Container outbound |
|---|---|---|---|
| `--internal` network only | **empty — no mapping is created at all** | **unreachable** | blocked |
| ordinary network | mapping present | **HTTP 200** | **reaches the internet** |

The container was confirmed to be serving on its loopback inside, so the failure
is the mapping and not the workload. Two things follow, and the second is the
one that hurts:

1. **A published port on an internal network is not merely unreachable — it is
   never wired up.** There is no partial behaviour to tune.
2. **A non-internal segment is the only kind that publishes, and it always grants
   outbound.** So *"publishes a port"* and *"has no route out"* cannot both be
   true of the same container under Docker. The property ADR-0027 wanted is not
   available in the shape it wanted it.

### The same defect, one segment over

Tracing the first finding surfaced a second instance that nobody had flagged:
**the `egress-proxy` sat on `scanner` and the plugin segments, all of them
`internal: true`.** Measured, a container on internal segments only cannot reach
anything outside — which is correct for a plugin and fatal for the one component
whose entire job is to make the outbound call. The chokepoint had no route
through it.

That is not a consequence of this ADR; it was already true and would have been
discovered the first time `plugin-smtp` tried to send an invitation.

## Options

| Option | For | Against |
|---|---|---|
| Keep `edge` internal | No change | Measured: the application would not be reachable at all |
| A separate `ingress` container, dual-homed | Keeps `web` static | A fifth core container for a job `web` is already shaped to do |
| **`web` becomes the ingress**: dual-homed on `edge` and `internal`, publishing the one port and proxying to `api` | **Removes** a published port rather than adding a container · `api` and `worker` end up with **no** published port and no `edge` membership, which is stricter than today · the host firewall goes from two rules to one · `web` already serves the security headers ([ADR-0038](0038-csp-delivery-and-first-paint.md)), so the edge is one place | One more hop behind the operator's reverse proxy · `web` is on the request path for the API, not only for static files · `web` has an outbound route |
| Host networking | Sidesteps the bridge entirely | Forbidden: rootless, and `network_mode: host` is on the `forbidden` list (`REQ-SEC-084`) |

## Decision

### 1. `edge` is **not** internal, and only `web` is on it

```
edge        not internal   web                                      publishes 8080
egress      not internal   egress-proxy                             its route out
internal    internal       postgres valkey opensearch rabbitmq
                           api worker web migrate
scanner     internal       clamav worker egress-proxy
plugin-<id> internal       that plugin, api, worker, egress-proxy
```

`api` publishes **nothing** and is no longer on `edge`. `web` listens on 8080,
serves the bundle and the security headers, and proxies `/api`, `/graphql`,
`/c/`, `/.well-known` and the SSE stream to `api` over `internal`.

### 2. `egress-proxy` gets the one segment it always needed

A non-internal `egress` segment with exactly one member. That is what makes it a
chokepoint rather than a claim: it is the only container in the deployment on a
segment with a route out, apart from `web`.

### 3. Two containers have an outbound route, and both are named

`REQ-PRIV-003`'s test changes from *"every core container"* to a list with two
stated exceptions, because a test whose expected result is wrong for two members
gets weakened until it passes:

| Container | Outbound | Why it is acceptable |
|---|---|---|
| `api`, `worker` | **refused** | They hold the data, the database credentials and the domain logic. This is the assertion that matters, and it is unchanged |
| `egress-proxy` | reaches | It is the designed chokepoint. It holds no credentials, terminates no TLS, and applies an allowlist ([ADR-0027](0027-egress-enforcement.md)) |
| `web` | reaches | It holds no data, no secret and no domain logic — a byte forwarder and a static file server. It is the price the runtime charges for an inbound port |

**Measured end to end** before this was written, on the topology above:

```
host -> web:8080 -> api (inbound path)        HTTP 200
api    outbound                               blocked
plugin outbound                               blocked
egress-proxy outbound                         reaches
web    outbound                               reaches
plugin -> egress-proxy on its own segment     reachable
plugin -> api (the gRPC path)                 reachable
plugin A -> plugin B                          isolated
```

The last line also confirms
[ADR-0037](0037-per-plugin-network-segments.md) on the same runtime.

## Rationale

The alternative that keeps the old sentence true does not exist. What was
available was a choice about **where** the unavoidable outbound route sits, and
the answer this project has already given twice — for webhooks
([ADR-0026](0026-core-outbound-via-plugins.md)) and for plugin egress
([ADR-0027](0027-egress-enforcement.md)) — is: in a container that holds nothing.
`web` is that container. It has no database credential, no master key and no
domain logic; a compromise there yields a reverse proxy.

Folding the ingress into `web` rather than adding a fifth container is the part
that makes this cheaper than the status quo rather than more expensive: `api`
loses its published port and its `edge` membership, the host firewall loses a
rule, and the security headers, the static bundle and the inbound route end up in
one place instead of three.

**What this does not claim.** Only Docker was measured — Podman with `pasta` and
`kind` were not, because neither is installable in the environment this was
verified in. That does not soften the conclusion: the service matrix generates
**one** topology for all three runtimes, so a shape Docker cannot carry is a shape
the design cannot use, whatever Podman does. The Podman and `kind` runs remain as
CI work, and they now verify a topology that is known to work somewhere rather
than one that is known to work nowhere.

## Consequences

- **`REQ-PRIV-003` is amended** to name `api` and `worker` as the containers the
  network test asserts, and `web` and `egress-proxy` as the two with a route out,
  with the reason. A new requirement, `REQ-SEC-102`, holds the line that only
  those two may have one.
- **One published host port instead of two.** [06 §6.2](../architecture/06-deployment-view.md)'s
  `firewall-cmd` step loses a rule, and the note about opening 8080 *and* 8081
  goes with it.
- **`api` is no longer reachable from the host.** An operator who curls
  `localhost:8080/api/...` during a debugging session now goes through `web`,
  which is also what a real request does — the debugging path and the production
  path stop differing.
- **`web` is on the request path for every call**, so its resource limit rises
  from 128 MB and it gains the API's health check semantics. It stays stateless
  and horizontally scalable.
- **ADR-0027 §1's sentence is withdrawn**, not reinterpreted. It is marked in
  place so the reasoning that produced it stays readable.
- **A5 is closed**, and the risk it belonged to (R16 — a guarantee written in one
  document and delivered in another) gains its first worked example: the
  assumption was flagged, tested, and found false. The flagging is what made the
  difference; had it been written as settled, the first deployment would have been
  unreachable.
