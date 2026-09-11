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
| the **offline sync protocol** | [11 Offline Synchronization](architecture/11-offline-synchronization.md) |
| the **security concept** | [12 Security](architecture/12-security.md) |
| **operations, backup, monitoring** | [13 Operations](architecture/13-operations-and-observability.md) |
| **quality goals, risks, terms** | [14 Quality, Risks, Glossary](architecture/14-quality-risks-glossary.md) |
| **implementable requirements** | [Requirements catalogue](requirements/) |
| the **design system** | [Design system brief](design/design-system-brief.md) |

## Layout

```
docs/
├── architecture/     arc42-oriented system description (01–14)
├── adr/              architecture decisions, numbered consecutively
├── design/           the design system brief, and later the delivered system
├── reference/        reference data (label geometries)
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
Each has its own ADR with rationale and alternatives.

| # | Subject | Decision | ADR |
|---|---|---|---|
| 1 | Backend platform | Java 25 + Spring Boot 4 | [0001](adr/0001-backend-platform.md) |
| 2 | System topology | A modular monolith + a plugin system | [0002](adr/0002-modular-monolith.md) |
| 3 | Multi-tenancy | Fully multi-tenant, access by invitation | [0003](adr/0003-multi-tenancy.md) |
| 4 | Attribute storage | JSONB + an application-maintained index side table | [0004](adr/0004-attribute-storage-model.md) |
| 5 | Identity | An own core + OIDC federation | [0005](adr/0005-identity.md) |
| 6 | Plugin runtime | In-process **and** out-of-process behind one contract | [0006](adr/0006-plugin-runtime.md) |
| 7 | Media storage | A `BlobStore` port: filesystem / S3 / Nextcloud | [0007](adr/0007-media-storage.md) |
| 8 | Search | OpenSearch as a derived read model | [0008](adr/0008-search.md) |
| 9 | Messaging | RabbitMQ + a transactional outbox | [0009](adr/0009-messaging-and-events.md) |
| 10 | API surfaces | REST + read-only GraphQL outward, gRPC for plugins | [0010](adr/0010-api-surfaces.md) |
| 11 | API versioning | The major version in the URL, additive minors, a sunset policy | [0011](adr/0011-api-versioning.md) |
| 12 | Web frontend | React + TypeScript + Vite as a PWA | [0012](adr/0012-web-frontend.md) |
| 13 | Apps | Kotlin Multiplatform + Compose Multiplatform | [0013](adr/0013-mobile-apps.md) |
| 14 | Offline | Full bidirectional sync | [0014](adr/0014-offline-synchronization.md) |
| 15 | Deployment | Podman/Quadlet **and** Docker/Compose, both rootless; Helm as an equal | [0015](adr/0015-deployment.md), [0021](adr/0021-podman-quadlet.md) |
| 16 | Identifiers | UUIDv7 internally, a separate public code | [0016](adr/0016-identifiers.md) |
| 17 | Persistence access | Spring Data JPA + `JdbcClient` for dynamic queries | [0017](adr/0017-persistence-access.md) |
| 18 | Licence | AGPL-3.0-or-later | [0018](adr/0018-licensing.md) |
| 19 | Secrets in digital goods | Per-tenant envelope encryption | [0019](adr/0019-sensitive-field-encryption.md) |
| 20 | Configuration distribution | Tenant configuration as a synchronised aggregate | [0020](adr/0020-configuration-as-data.md) |
| 21 | Way of running it | Podman with Quadlet on a par with Docker Compose | [0021](adr/0021-podman-quadlet.md) |
| 22 | Rootless | Mandatory for **every** way of running it; rootful unsupported | [0022](adr/0022-rootless.md) |
| 23 | Push | Firebase/APNs as a plugin, a payload without inventory data | [0023](adr/0023-push-notifications.md) |
| 24 | Malware scan | ClamAV mandatory, fail-closed | [0024](adr/0024-malware-scan.md) |
| 25 | Money | An in-house `Money` value type; no JavaMoney, no Joda-Money | [0025](adr/0025-money-representation.md) |

Open points and outstanding work are collected in
[ADR-0000](adr/0000-open-points.md).
