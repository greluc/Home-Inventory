# ADR-0026 — Every outbound connection of the core moves into a plugin

**Status:** Accepted · **Date:** 2026-09-11
**Partially supersedes:** [ADR-0007](0007-media-storage.md) — the S3 and Nextcloud
adapters are no longer core adapters.
**Amends:** [ADR-0023](0023-push-notifications.md) — Web Push was described there
as a core channel that avoids third-party infrastructure. It does not.
**Resolves:** the contradiction recorded as the central finding of the 2026-09-11
architecture review.

> **Amended by [ADR-0037](0037-per-plugin-network-segments.md)**: there is no shared
> `plugins` segment any more. Each plugin has its own, and `clamd` sits on `scanner`.
> The *"What stays in the core"* table below is corrected in place.
>
> **Amended by [ADR-0042](0042-edge-is-not-internal.md)**: the consequence below read
> *"**no** core container reaches **any** external host, verified in CI"*, and that is
> false of two of them by design — `web` publishes the single host port and
> `egress-proxy` is the chokepoint. The test is against `api` and `worker`
> (`REQ-PRIV-003`, `REQ-SEC-102`). It is corrected in place, and it survived here
> because ADR-0042's `Amends:` line did not name this ADR — the missing back-link that
> **A4b** exists to catch.

## Context

`REQ-PRIV-003`, [12 §12.2](../architecture/12-security.md),
[ADR-0015](0015-deployment.md), [ADR-0023](0023-push-notifications.md) and
[ADR-0025](0025-money-representation.md) all state the same rule:

> The core has **no outbound route to the internet**; external calls go through
> plugins only.

**The rule was false against our own design.** Five shipped features in the core
open outbound connections:

| Feature | Where it was declared | Target |
|---|---|---|
| `BlobStore` adapters `s3` and `nextcloud` | [ADR-0007](0007-media-storage.md), `REQ-MED-009` | a remote host |
| `MailSender` in `identity` and `tenancy` | [04 §4.3](../architecture/04-building-blocks.md) | an SMTP server |
| `OidcClient` | `REQ-AUTH-005`, [09 §9.2](../architecture/09-extensibility-and-plugins.md) "OIDC (shipped)" | an identity provider |
| Webhook delivery | `REQ-API-010`, `REQ-NOTI-002` "webhook (shipped)" | **an arbitrary tenant-supplied URL** |
| Web Push / VAPID | [09 §9.2](../architecture/09-extensibility-and-plugins.md) "(shipped)" | `fcm.googleapis.com`, `web.push.apple.com`, Mozilla's push service |

Two consequences made this more than a wording problem:

- **`REQ-NOTI-007` was unsatisfiable.** Its acceptance criterion reads "the core
  cannot reach `fcm.googleapis.com`" — the exact host that shipped VAPID Web Push
  contacts for a Chrome client.
- **`REQ-SEC-034` and `REQ-API-010` contradicted each other.** "URLs from user
  input … **never** fetched server-side" against "webhooks with a signature,
  retries and a delivery log". Webhook delivery *is* a server-side fetch of a
  user-supplied URL — the textbook SSRF surface, and the only place in the design
  where a tenant can point the core at an address of their choosing. SSRF did not
  appear in the STRIDE table at all.

The rule was also **load-bearing elsewhere**: [ADR-0025](0025-money-representation.md)
rejects JavaMoney specifically because its conversion modules schedule background
fetches to ECB and IMF endpoints. An architecture that argues that way about a
library while shipping five outbound features of its own is not credible.

## Options

| Option | For | Against |
|---|---|---|
| **Qualify the rule to an egress allowlist** | Honest and cheap; keeps SMTP, OIDC and remote storage in the core where they are simplest | The rule stops being absolute. "Only configured targets" is a weaker property, and every future feature argues for one more entry |
| **Hold the rule; every outbound leaves the core** | The rule becomes true, testable and absolute. One mechanism (the plugin boundary) protects every external call: capability grant, target list, deadline, circuit breaker, audit | Invitations and password resets depend on a plugin. Forces the plugin runtime forward in the stage plan ([ADR-0028](0028-plugin-runtime-stage-1.md)) |
| **Drop the rule** | No work | Gives up a property four other decisions lean on, including [ADR-0025](0025-money-representation.md) |

## Decision

**The rule is held, and it is made true.** Every connection from `app-api` or
`app-worker` to a host outside the deployment becomes an out-of-process plugin.

### What leaves the core

| Port | Shipped implementation becomes | Plugin |
|---|---|---|
| `BlobStore` | `s3`, `nextcloud` | `plugin-blobstore-s3`, `plugin-blobstore-nextcloud` |
| `MailSender` | SMTP | `plugin-smtp` |
| `IdentityProvider` | OIDC | `plugin-oidc` |
| `NotificationChannel` | webhook, Web Push/VAPID | `plugin-webhook`, `plugin-webpush` |

### What stays in the core

| Stays | Why |
|---|---|
| `BlobStore` adapter `filesystem` | Opens no socket. Remains the default and the only storage a `minimal` installation needs |
| PostgreSQL, Valkey, OpenSearch, RabbitMQ, ClamAV | Parts of the deployment, on `internal` resp. `scanner` ([ADR-0037](0037-per-plugin-network-segments.md) — this row said "the `plugins` segment", which no longer exists). "No outbound route" means no route **out of the deployment**, and this ADR says so explicitly so the distinction is not re-litigated later. Being *inside* the deployment does not make them unauthenticated: every one of them holds a credential since [ADR-0044](0044-internal-is-not-a-trust-boundary.md) |

### What follows for webhooks

`REQ-SEC-034` keeps its absolute form — the core never fetches a user-supplied
URL. Webhook delivery moves to `plugin-webhook`, and with it the SSRF exposure
moves from Zone 2 to Zone 4, where the process boundary, the network segment, the
deadline and the circuit breaker already are. The signature scheme becomes part
of the plugin contract rather than of the REST contract
([ADR-0027](0027-egress-enforcement.md) covers how its target list is enforced).

## Rationale

The decisive argument is not purity. It is that **one boundary is cheaper to get
right than five**.

Held as written, every external call in the system — a metadata lookup, a photo
upload to Nextcloud, an invitation mail, a webhook to a tenant's own endpoint —
passes the same four controls: an explicit capability grant per tenant, a declared
target list enforced in the network, a deadline with a circuit breaker, and an
audit entry naming the plugin as actor. Qualified into an allowlist, the core
would keep a second, weaker path with none of those, and every future integration
would argue to join it. The plugin architecture exists for exactly this case; the
only reason four of these five features were not plugins is that they were written
down before the rule was.

The price is real and is not hidden: a `minimal` installation cannot send an
invitation without the SMTP plugin running. That cost is paid in
[ADR-0028](0028-plugin-runtime-stage-1.md).

## Consequences

- **The plugin runtime moves to stage 1.** SMTP is needed for invitations
  (`REQ-TEN-004`), password reset (`REQ-SEC-018`) and security notifications
  (`REQ-NOTI-004`), all stage 1. See [ADR-0028](0028-plugin-runtime-stage-1.md).
- **`REQ-NOTI-007` becomes satisfiable**, and its acceptance test becomes the one
  against **`api` and `worker`**: neither reaches any external host, verified in CI.
  *(This read "no core container … any external host" until 2026-09-11, which is a test
  with two members whose expected result is wrong — `web` and `egress-proxy` reach out by
  design, [ADR-0042](0042-edge-is-not-internal.md), `REQ-SEC-102`.)*
- **SSRF enters the threat model** as a Zone 4 risk in
  [12 §12.3](../architecture/12-security.md), not as an accepted Zone 2 exposure.
- **Boundary ⑥ (Zone 2 → 5) disappears** from the trust boundary diagram in
  [12 §12.2](../architecture/12-security.md). The Nextcloud app password
  (`REQ-SEC-052`) becomes a secret of `plugin-blobstore-nextcloud`, not of the
  core — which also narrows what a core compromise yields.
- **The `minimal` profile's default is now genuinely local**: filesystem storage,
  no mail. An operator who wants neither Nextcloud nor SMTP runs no plugin at all.
- **Media upload gains a hop** in the `standard` profile: worker → plugin →
  Nextcloud instead of worker → Nextcloud. Risk R11 (WebDAV is slow per file)
  gets slightly worse; the countermeasures named there — local derivative cache,
  asynchronous upload, batching — are unchanged and now sit on the plugin side.
- **Web Push is no longer described as avoiding third parties.** It avoids
  *an account* with Google and Apple; it does not avoid *a connection* to their
  push endpoints. [ADR-0023](0023-push-notifications.md) is corrected accordingly,
  and its three-opt-in model now applies to Web Push too.
- Enforcement of all of this is a separate decision:
  [ADR-0027](0027-egress-enforcement.md).
