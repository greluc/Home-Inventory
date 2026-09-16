# Documentation Map

This documentation is the **source of truth for the design** of Home Inventory.
It is carried along with every change in the same unit of work; a document that
contradicts the implementation is a defect and gets corrected immediately.

## How to read this

| If you want to … | Read … |
|---|---|
| understand **what** the system is and why | [01 Introduction and Goals](architecture/01-introduction-and-goals.md) |
| know **what is settled** and what is not | [02 Constraints and Context](architecture/02-constraints-and-context.md) · [ADR index](adr/) |
| see the **broad strokes** of the design | [03 Solution Strategy](architecture/03-solution-strategy.md) |
| know **what the system consists of** | [04 Building Block View](architecture/04-building-blocks.md) |
| see **how it runs** | [05 Runtime View](architecture/05-runtime-view.md) |
| know **where it runs** | [06 Deployment View](architecture/06-deployment-view.md) |
| the **data model** | [07 Data Model](architecture/07-data-model.md) |
| the **API contract** | [08 API Contract](architecture/08-api-contract.md) |
| the **plugin system** | [09 Extensibility](architecture/09-extensibility-and-plugins.md) |
| **QR codes, scanning, printing** | [10 Identification](architecture/10-identification-and-labels.md) |
| the **offline sync protocol** | [11 Offline Synchronisation](architecture/11-offline-synchronisation.md) |
| the **security concept** | [12 Security](architecture/12-security.md) |
| **operations, backup, monitoring** | [13 Operations](architecture/13-operations-and-observability.md) |
| **quality goals, risks, terms** | [14 Quality, Risks, Glossary](architecture/14-quality-risks-glossary.md) |
| **implementable requirements** | [Requirements catalogue](requirements/) |
| the **design system** | [Design system brief](design/design-system-brief.md) — the system itself is in [`design-system/`](../design-system/) |
| why the system is **not** Kubernetes-only and **not** microservices | [Kubernetes and microservices assessment](design/kubernetes-and-microservices-assessment.md) — assessed and closed on 2026-09-15 with the plan unchanged; no ADR is superseded |
| the **project website** | [`website/`](../website/), with the pre-render request in [design/](design/website-prerender-request.md) |
| the **error and degradation tokens** clients branch on | [`problem-types.yaml`](reference/problem-types.yaml) · [`degraded-reasons.yaml`](reference/degraded-reasons.yaml) |
| the **plugin states** an operator sees | [`plugin-health-states.yaml`](reference/plugin-health-states.yaml) |
| which **numbers and spellings are checked** rather than trusted | [`tracked-facts.yaml`](reference/tracked-facts.yaml) |

## Layout

```
docs/
├── architecture/     arc42-oriented system description (01–14)
├── adr/              architecture decisions, numbered consecutively
├── design/           the briefs behind the design system and the website, and
│                  assessments of proposals that have not become decisions — the
│                  design system itself is at ../design-system/ (source, not docs)
├── reference/        reference data: label geometries, plugin health states,
│                  the `problem.type` registry, the `degradedReason`
│                  registry, and the tracked facts and retired spellings
│                  a second document must not get wrong
└── requirements/     a verifiable requirements catalogue with stable IDs
```

## Conventions

- **Language:** **English** for documentation, decisions and every identifier in
  code, API, database and protocols. The UI ships German and English, with
  English as the fallback.
- **Diagrams:** Mermaid, inline in the Markdown. No separate tool, no binary
  artifacts, versionable as plain text.
- **IDs:** requirements (`REQ-*`) and decisions (`ADR-nnnn`) have stable numbers
  that are never reused. A dropped requirement is marked `Withdrawn` rather than
  deleted.
- **Cross-references:** architecture documents point at requirements and ADRs
  rather than duplicating them. What is written twice drifts.

## The basis of the decisions

The following choices were made up front and are binding on the whole design.
Each has its own ADR with rationale and alternatives. *This table stopped at 48 while the directory reached 55; it was brought level on 2026-09-12. The `adrCount` tracked fact counts files, not rows here, so nothing caught the gap — which is worth knowing before trusting a count to notice a missing index entry.*

| # | Subject | Decision | ADR |
|---|---|---|---|
| 1 | Backend platform | Java 25 + Spring Boot 4 | [0001](adr/0001-backend-platform.md) |
| 2 | System topology | A modular monolith + a plugin system | [0002](adr/0002-modular-monolith.md) |
| 3 | Multi-tenancy | Fully multi-tenant, access by invitation | [0003](adr/0003-multi-tenancy.md) |
| 4 | Attribute storage | JSONB + an application-maintained index side table | [0004](adr/0004-attribute-storage-model.md) |
| 5 | Identity | An own core + OIDC federation | [0005](adr/0005-identity.md) |
| 6 | Plugin runtime | In-process **and** out-of-process behind one contract | [0006](adr/0006-plugin-runtime.md) |
| 7 | Media storage | A `BlobStore` port: filesystem in the core, S3 / Nextcloud as plugins | [0007](adr/0007-media-storage.md), [0026](adr/0026-core-outbound-via-plugins.md), [0032](adr/0032-per-tenant-blob-addressing.md) |
| 8 | Search | OpenSearch as a derived read model | [0008](adr/0008-search.md) |
| 9 | Messaging | RabbitMQ + a transactional outbox | [0009](adr/0009-messaging-and-events.md) |
| 10 | API surfaces | REST + read-only GraphQL outward, gRPC for plugins | [0010](adr/0010-api-surfaces.md) |
| 11 | API versioning | The major version in the URL, additive minors, a sunset policy | [0011](adr/0011-api-versioning.md) |
| 12 | Web frontend | React + TypeScript + Vite as a PWA | [0012](adr/0012-web-frontend.md) |
| 13 | Apps | Kotlin Multiplatform + Compose Multiplatform | [0013](adr/0013-mobile-apps.md) |
| 14 | Offline | Full bidirectional sync | [0014](adr/0014-offline-synchronisation.md) |
| 15 | Deployment | Podman/Quadlet **and** Docker/Compose, both rootless; Helm as an equal | [0015](adr/0015-deployment.md), [0021](adr/0021-podman-quadlet.md) |
| 16 | Identifiers | UUIDv7 internally, a separate public code (10 characters + Damm) | [0016](adr/0016-identifiers.md), [0030](adr/0030-public-code-format.md) |
| 17 | Persistence access | Spring Data JPA + `JdbcClient` for dynamic queries | [0017](adr/0017-persistence-access.md) |
| 18 | Licence | AGPL-3.0-or-later | [0018](adr/0018-licensing.md) |
| 19 | Secrets in digital goods | Per-tenant envelope encryption | [0019](adr/0019-sensitive-field-encryption.md) |
| 20 | Configuration distribution | Tenant configuration as a synchronised aggregate | [0020](adr/0020-configuration-as-data.md) |
| 21 | Way of running it | Podman with Quadlet on a par with Docker Compose | [0021](adr/0021-podman-quadlet.md) |
| 22 | Rootless | Mandatory for **every** way of running it; rootful unsupported | [0022](adr/0022-rootless.md) |
| 23 | Push | Firebase/APNs/Web Push as plugins, a payload without inventory data | [0023](adr/0023-push-notifications.md), [0026](adr/0026-core-outbound-via-plugins.md) |
| 24 | Malware scan | ClamAV mandatory, fail-closed | [0024](adr/0024-malware-scan.md) |
| 25 | Money | An in-house `Money` value type; no JavaMoney, no Joda-Money | [0025](adr/0025-money-representation.md) |
| 26 | Outbound connections | **Every** external call leaves the core and becomes a plugin | [0026](adr/0026-core-outbound-via-plugins.md) |
| 27 | Egress enforcement | Deny-all for the core, an allowlisting proxy per plugin segment | [0027](adr/0027-egress-enforcement.md) |
| 28 | Stage plan | The plugin runtime is part of stage 1, not stage 3 | [0028](adr/0028-plugin-runtime-stage-1.md) |
| 29 | Web session | `SameSite=Strict`, with code resolution and OIDC state decoupled | [0029](adr/0029-session-cookie-and-oidc-state.md) |
| 30 | Printed code format | 10 payload characters plus a Damm check symbol, Crockford Base32 | [0030](adr/0030-public-code-format.md) |
| 31 | Audit chain | Per tenant, with an hourly instance-wide anchor | [0031](adr/0031-audit-chain-per-tenant.md) |
| 32 | Blob addressing | Content-addressed within a tenant, never across | [0032](adr/0032-per-tenant-blob-addressing.md) |
| 33 | Appearance | Dark by default everywhere; light is opt-in | [0033](adr/0033-dark-as-default-appearance.md) |
| 34 | Icons and assets | Lucide, self-hosted; no third-party host for anything | [0034](adr/0034-icon-set-and-no-third-party-hosts.md) |
| 35 | Design system | Binding, not a reference; `tokens.json` is the single source for web and apps | [0035](adr/0035-design-system.md) |
| 36 | Scanner egress | The egress proxy runs in every profile; the signature mirror is a fixed allowlist entry | [0036](adr/0036-scanner-egress.md) |
| 37 | Plugin isolation | One network segment per plugin; the management port bound to `internal` | [0037](adr/0037-per-plugin-network-segments.md) |
| 38 | CSP and first paint | Headers served by `web`, inline content authorised by hash, theme mirrored in `localStorage` | [0038](adr/0038-csp-delivery-and-first-paint.md) |
| 39 | Degradation signal | `meta.degraded` in the envelope; the obsolete `Warning` header is dropped | [0039](adr/0039-degraded-response-signalling.md) |
| 40 | Cross-origin isolation | Not pursued — `COEP` is dropped, `COOP` and `CORP` stay | [0040](adr/0040-no-cross-origin-isolation.md) |
| 41 | Migration | Its own one-shot service on every runtime; no long-running process holds DDL rights | [0041](adr/0041-migration-as-its-own-service.md) |
| 42 | Inbound topology | `edge` is not internal; `web` is the ingress and the only publisher | [0042](adr/0042-edge-is-not-internal.md) |
| 43 | Default media store | The filesystem `BlobStore` gets its own in-deployment service, so `api` and `worker` stay stateless | [0043](adr/0043-blobstore-as-its-own-service.md) |
| 44 | Inside the deployment | `internal` is a network, not a trust boundary: every datastore authenticates, and `web` sits on a two-member segment with `api` | [0044](adr/0044-internal-is-not-a-trust-boundary.md) |
| 45 | Recovery point | The WAL archive is its own volume — without it RPO ≤ 15 min is the last daily dump | [0045](adr/0045-wal-archive-volume.md) |
| 46 | Audit retention | The chain is truncatable and the truncation is recorded, so retention and tampering stop looking alike | [0046](adr/0046-truncatable-audit-chain.md) |
| 47 | Fallback full text | Two generated `tsvector` columns, German and English — `simple` does no stemming | [0047](adr/0047-bilingual-search-vectors.md) |
| 48 | Remote storage | No managed folders or permissions at the remote — the credential stays scoped to one folder | [0048](adr/0048-no-managed-remote-folders.md) |
| 49 | API contract | The OpenAPI document is generated from the implementation, and a drift check makes it true | [0049](adr/0049-openapi-generated-from-the-implementation.md) |
| 50 | `blobstore` language | Rust — a service that only moves bytes, with no runtime of its own to feed | [0050](adr/0050-blobstore-service-in-rust.md) |
| 51 | Broker stage | RabbitMQ moves to stage 0, because a stage-0 requirement names the worker as doing the work | [0051](adr/0051-broker-in-stage-0.md) |
| 52 | Stored image format | One format: every image is transcoded to AVIF, and the bytes that arrived are stored nowhere | [0052](adr/0052-one-stored-image-format.md) |
| 53 | First account | A one-shot service creates the first owner and tenant — not an endpoint strangers could reach | [0053](adr/0053-first-owner-as-a-one-shot.md) |
| 54 | Malware scan | In the worker, with the upload answered `202` — `api` has no route to `clamd` and never had | [0054](adr/0054-the-scan-is-asynchronous.md) |
| 55 | Plugin SDKs | Five, all first-class: Java, Kotlin, Rust, Python and Go | [0055](adr/0055-five-first-class-plugin-sdks.md) |
| 56 | Instance-level plugin grants | The capability model has a second level, so an account with no tenant still gets its security mail | [0066](adr/0066-instance-level-capability-grants.md) |
| 57 | Breached passwords | A list ships in the image, because the core may not call one; a plugin may add a live service | [0067](adr/0067-breached-passwords-from-a-shipped-list.md) |

Open points and outstanding work are collected in
[ADR-0000](adr/0000-open-points.md). **No decision is currently open** — the
review passes of 2026-09-11 raised nine (O18–O26), and all nine were decided the same day.
The last three were the conditions [`problem-types.yaml`](reference/problem-types.yaml)
had left `pending`, which is an open decision wearing a different hat: one of them,
the status code for an oversized payload, was load-bearing for a **stage-0**
requirement.

What remains there is outstanding **work**, of two kinds. **A5** (running the connectivity
suite under rootless Podman and `kind`) and **A6** (emitting the generated `freshclam.conf`
from the service matrix) need a stack to start and a generator to run, and neither exists
yet. The three documentation gates — the ADR back-link check (**A4b**), the restated-fact
check (**A12**) and the unbacked-claim check (**A7**), all specified in
[`.github/workflows/README.md`](../.github/workflows/README.md) — **need nothing**: they
read text, and since `pages.yml` that directory is live. A4b's and A12's rules have both
been run by hand and their findings closed, so both open clean. A5 is **not** an
unverified assumption: it was measured under Docker, found false, and the topology was
corrected ([ADR-0042](adr/0042-edge-is-not-internal.md)); what is outstanding is
confirming the corrected shape on the other two runtimes. **A2 is closed**, for Dymo as
well as Brother — Dymo publishes no per-label printable area at all, which is an answer
rather than a gap.
