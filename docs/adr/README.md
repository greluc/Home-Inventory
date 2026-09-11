# Architecture Decision Records (ADR)

Every load-bearing decision is recorded here with its **context, alternatives,
decision and consequences**. An ADR is never rewritten when the decision changes
— a new one supersedes it. That keeps it traceable why something was once right.

## Format

```
# ADR-nnnn — Title
Status: Proposed | Accepted | Superseded by ADR-mmmm | Withdrawn
Date: YYYY-MM-DD
## Context       What forces the decision
## Options       What was considered, with pros and cons
## Decision      What applies
## Rationale     Why this one and not the other
## Consequences  What follows from it, including the unpleasant parts
```

## Index

| ADR | Title | Status |
|---|---|---|
| [0000](0000-open-points.md) | Open points | ongoing — **one open (O15)** |
| [0001](0001-backend-platform.md) | Java 25 and Spring Boot 4 as the backend platform | Accepted |
| [0002](0002-modular-monolith.md) | A modular monolith instead of microservices | Accepted |
| [0003](0003-multi-tenancy.md) | Multi-tenancy from the start, access by invitation | Accepted |
| [0004](0004-attribute-storage-model.md) | JSONB plus an application-maintained index side table | Accepted |
| [0005](0005-identity.md) | An own identity core with optional OIDC federation | Accepted |
| [0006](0006-plugin-runtime.md) | Two plugin runtimes behind one contract | Accepted |
| [0007](0007-media-storage.md) | A BlobStore port with filesystem, S3 and Nextcloud adapters | Partially superseded by 0026, amended by 0032 |
| [0008](0008-search.md) | OpenSearch as a derived read model | Accepted, amended by 0039 |
| [0009](0009-messaging-and-events.md) | RabbitMQ with a transactional outbox | Accepted, extended (O13) |
| [0010](0010-api-surfaces.md) | REST and read-only GraphQL outward, gRPC for plugins | Accepted |
| [0011](0011-api-versioning.md) | The major version in the URL, additive minor versions | Accepted |
| [0012](0012-web-frontend.md) | React with TypeScript and Vite as a PWA | Accepted, amended by 0038 |
| [0013](0013-mobile-apps.md) | Kotlin Multiplatform and Compose Multiplatform | Accepted |
| [0014](0014-offline-synchronisation.md) | Full bidirectional sync with a three-way compare | Accepted, extended (O14) |
| [0015](0015-deployment.md) | Docker Compose primary, Helm maintained as an equal | Partially superseded by 0021, 0022 |
| [0016](0016-identifiers.md) | UUIDv7 internally, a separate public short code | Partially superseded by 0030 |
| [0017](0017-persistence-access.md) | Spring Data JPA for aggregates, JdbcClient for dynamic queries | Accepted |
| [0018](0018-licensing.md) | AGPL-3.0-or-later for the core, Apache-2.0 for the plugin API | Accepted |
| [0019](0019-sensitive-field-encryption.md) | Per-tenant envelope encryption for sensitive fields | Accepted |
| [0020](0020-configuration-as-data.md) | Tenant configuration is synchronised domain data | Accepted |
| [0021](0021-podman-quadlet.md) | Podman with Quadlet as an equally supported way to run it | Accepted |
| [0022](0022-rootless.md) | Rootless as the mandatory basis for every way of running it | Accepted |
| [0023](0023-push-notifications.md) | Push through Firebase and APNs as a plugin, content-free payload | Amended by 0026 |
| [0024](0024-malware-scan.md) | A mandatory malware scan, fail-closed | Accepted, amended by 0036 |
| [0025](0025-money-representation.md) | An in-house `Money` value type instead of JavaMoney or Joda-Money | Accepted |
| [0026](0026-core-outbound-via-plugins.md) | Every outbound connection of the core moves into a plugin | Accepted |
| [0027](0027-egress-enforcement.md) | Egress enforcement: deny-all for the core, a proxy per plugin segment | Accepted, amended by 0036, 0037 |
| [0028](0028-plugin-runtime-stage-1.md) | The plugin runtime moves to stage 1 | Accepted |
| [0029](0029-session-cookie-and-oidc-state.md) | `SameSite=Strict` stays; code resolution and OIDC state are decoupled | Accepted |
| [0030](0030-public-code-format.md) | The public code: 10 payload characters and a Damm check symbol | Accepted |
| [0031](0031-audit-chain-per-tenant.md) | The audit chain runs per tenant and is anchored in time | Accepted |
| [0032](0032-per-tenant-blob-addressing.md) | Blobs are content-addressed within a tenant, never across | Accepted |
| [0033](0033-dark-as-default-appearance.md) | Dark is the default appearance, on every platform | Accepted, amended by 0038 |
| [0034](0034-icon-set-and-no-third-party-hosts.md) | Lucide as the icon set, and no third-party host for anything | Accepted |
| [0035](0035-design-system.md) | The delivered design system is binding, and `tokens.json` is its single source | Accepted |
| [0036](0036-scanner-egress.md) | The malware scanner gets a route out; the egress proxy runs in every profile | Accepted |
| [0037](0037-per-plugin-network-segments.md) | One network segment per plugin, and the management port out of their reach | Accepted |
| [0038](0038-csp-delivery-and-first-paint.md) | The CSP is delivered by `web` with hashes; the theme is mirrored locally | Accepted |
| [0039](0039-degraded-response-signalling.md) | Degradation is signalled in the payload; the `Warning` header is dropped | Accepted |
| [0040](0040-no-cross-origin-isolation.md) | `Cross-Origin-Embedder-Policy` is dropped; `COOP` and `CORP` stay | Accepted |
| [0041](0041-migration-as-its-own-service.md) | Migration is its own one-shot service, on every runtime | Accepted |
| [0042](0042-edge-is-not-internal.md) | A published port needs a non-internal segment; `web` becomes the ingress | Accepted |
