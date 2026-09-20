<!--
SPDX-FileCopyrightText: Lucas Greuloch
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR-0071 — The core answers plugins on one narrow channel, and it stays narrow

**Status:** Accepted · **Date:** 2026-09-20

**Amends:** [ADR-0037](0037-per-plugin-network-segments.md) (a second core listener, reachable
from plugin segments), [ADR-0065](0065-the-plugin-call-envelope.md) (a call in the other
direction)

**Depends on:** [ADR-0006](0006-plugin-runtime.md), [ADR-0070](0070-documents-are-described-not-programmed.md)

> **Amended by [ADR-0073](0073-a-plugin-is-configured-twice.md)**: the "no read here" rule
> below was immediately tested by a real need — a plugin's own settings — and it held. They
> travel in the call envelope instead, and this channel still has one method on it.

## Context

Every service in the plugin contract is served by a **plugin** and called by the **core**.
That direction is not an accident: [ADR-0037](0037-per-plugin-network-segments.md) puts each
plugin on its own segment, refuses plugin-to-plugin traffic, and `REQ-SEC-100` has the core's
port 8090 refusing every plugin segment. A plugin can be reached and can reach nothing.

[ADR-0070](0070-documents-are-described-not-programmed.md) made document rendering a plugin's
job, which immediately raises the question it does not answer: what about a **plugin** that
needs a document? Under the rules above it has three options, and all three are bad. Carry a
PDF library and a font pack of its own. Ask the operator to install a second copy of the
renderer inside it. Or produce no document.

The owner's decision on 2026-09-20 was the direct one: **give plugins a channel they may
call**, rather than the cheaper design in which a plugin returns a described document as its
own result and the core renders it afterwards. That is a real reversal of a deliberate
constraint, so what follows is mostly about keeping it small.

## Decision

**One service, served by the core, reachable by plugins, with one method on it today.**

`HostServices.RenderDocument` takes the document model of
[ADR-0070](0070-documents-are-described-not-programmed.md) and returns the rendered bytes.
The caller does not name a renderer: which one is installed is the operator's decision, and
a plugin that could choose would be a plugin that can probe what else is installed.

### What it is not, and this is the load-bearing half

**There is no read here.** No "give me the item I am working on", no "list this tenant's
places", and there will not be one. A plugin is given what it needs in the call it is
answering (`REQ-SEC-011`); an endpoint that let it fetch more would turn every capability
grant from a boundary into a starting point. Every method on this service takes what the
caller already holds and gives back something made from it — a transformation, never a
lookup.

That sentence is the acceptance criterion for anything added here later. A method that
answers a question the caller could not already answer does not belong on this service.

### Three gates, and none of them is the network alone

1. **A separate listener on a separate port.** Port 8090 still refuses every plugin segment
   (`REQ-SEC-100`); this is a different port — 8091 in `deploy/services.yaml` — serving this
   service and nothing else. It is deliberately **not** bound to one segment the way 8090 is:
   there is no single segment to bind it to, because `api` sits on every plugin segment and
   each of them is its own network. The listener is **off unless `HOMEINV_PLUGIN_HOST_PORT`
   names it**, so a deployment that runs no plugin asking the core for anything opens no
   socket here at all.
2. **mTLS in both directions.** The caller presents the certificate whose fingerprint the
   operator pinned when registering the plugin — the same fingerprint the core already pins
   when calling it. An unknown certificate is refused before a method is dispatched, and
   *reachability is not authorisation* here as much as anywhere else
   ([ADR-0044](0044-internal-is-not-a-trust-boundary.md)).
3. **A capability, granted per tenant.** `host:render-document`, like every other capability
   (`REQ-PLG-005`). Without it the call is refused and logged. A missing grant and a missing
   renderer are the **same** answer — `FAILED_PRECONDITION` — because a plugin that could
   tell them apart would have a way to enumerate what a tenant has consented to.

### The identity in the call is the caller's, not a borrowed one

The core knows which plugin is calling from the certificate, and which tenant from the call
envelope. It does **not** accept a tenant id the caller asserts: that would be the oldest
multi-tenancy mistake in the book, arriving through a door that was opened for PDFs.

## Options

| Option | A plugin can produce a document | New direction in the contract | New surface to defend |
|---|---|---|---|
| **A narrow host service** | yes, mid-work | yes | one port, one method, mTLS, a capability |
| A plugin returns a described document as its result | yes, at the end of its own call | no | none |
| A PDF library in each plugin | yes | no | every plugin's fonts and CVEs, and no consistency between documents |
| Nothing | no | no | none |

The second option was the recommendation and was not chosen. It is genuinely cheaper and it
is genuinely weaker: a plugin that discovers halfway through its work that it needs a
document has to restructure itself around returning one, and a plugin whose job is *not*
producing documents — one that sends a monthly summary somewhere, say — has no result to
return a document in at all.

## Consequences

- **A constraint ADR-0037 set deliberately is now qualified**, and that record is amended
  rather than quietly contradicted. Plugin-to-plugin traffic stays refused; what changed is
  that the core answers on one port, and the document a plugin gets back may have been
  rendered by another plugin it never addressed and cannot name.
- **The blast radius of a compromised plugin grows by exactly one method.** It can ask for a
  document to be rendered from content it already had. It cannot read, cannot write, cannot
  enumerate and cannot reach another plugin.
- **Every later method needs this record amended**, with the "takes what the caller already
  holds" test applied in writing. The service is narrow because it is kept narrow, not
  because it started small.
- **The three gates are proved by `HostChannelIT`**, against the real listener over a real
  TLS socket with certificates generated when the test runs: a certificate no registration
  pins gets no connection, a plugin without the capability is refused, a grant in one tenant
  is not a grant in another, and the refusal for a missing grant is **the same sentence** as
  the one for a missing renderer. *This bullet said the connectivity suite would gain those
  cases, and it was wrong on 2026-09-20 when it was written: that suite opens TCP sockets
  with `nc` from one container to another, which can prove a network shape and cannot present
  a client certificate or read a gRPC status. The one case that does belong there — a plugin
  segment reaches 8091 and no other core port — needs a plugin container in
  `deploy/services.yaml`, and there is none yet (`REQ-PLG-013`); it lands with the first one,
  in the same unit of work.*
