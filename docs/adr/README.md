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
| [0000](0000-open-points.md) | Open points | ongoing — **no open decision** |
| [0001](0001-backend-platform.md) | Java 25 and Spring Boot 4 as the backend platform | Accepted |
| [0002](0002-modular-monolith.md) | A modular monolith instead of microservices | Accepted |
| [0003](0003-multi-tenancy.md) | Multi-tenancy from the start, access by invitation | Accepted |
| [0004](0004-attribute-storage-model.md) | JSONB plus an application-maintained index side table | Accepted |
| [0005](0005-identity.md) | An own identity core with optional OIDC federation | Accepted |
| [0006](0006-plugin-runtime.md) | Two plugin runtimes behind one contract | Accepted |
| [0007](0007-media-storage.md) | A BlobStore port with filesystem, S3 and Nextcloud adapters | Partially superseded by 0026, amended by 0032, 0052 |
| [0008](0008-search.md) | OpenSearch as a derived read model | Accepted, amended by 0039, 0047 |
| [0009](0009-messaging-and-events.md) | RabbitMQ with a transactional outbox | Accepted, extended (O13) |
| [0010](0010-api-surfaces.md) | REST and read-only GraphQL outward, gRPC for plugins | Accepted |
| [0011](0011-api-versioning.md) | The major version in the URL, additive minor versions | Accepted |
| [0012](0012-web-frontend.md) | React with TypeScript and Vite as a PWA | Accepted, amended by 0038 |
| [0013](0013-mobile-apps.md) | Kotlin Multiplatform and Compose Multiplatform | Accepted |
| [0014](0014-offline-synchronisation.md) | Full bidirectional sync with a three-way compare | Accepted, extended (O14) |
| [0015](0015-deployment.md) | Docker Compose primary, Helm maintained as an equal | Partially superseded by 0021, 0022; its topology by 0036, 0037, 0042 |
| [0016](0016-identifiers.md) | UUIDv7 internally, a separate public short code | Partially superseded by 0030 |
| [0017](0017-persistence-access.md) | Spring Data JPA for aggregates, JdbcClient for dynamic queries | Accepted |
| [0018](0018-licensing.md) | AGPL-3.0-or-later for the core, Apache-2.0 for the plugin API | Accepted |
| [0019](0019-sensitive-field-encryption.md) | Per-tenant envelope encryption for sensitive fields | Accepted |
| [0020](0020-configuration-as-data.md) | Tenant configuration is synchronised domain data | Accepted |
| [0021](0021-podman-quadlet.md) | Podman with Quadlet as an equally supported way to run it | Accepted, amended by 0027 |
| [0022](0022-rootless.md) | Rootless as the mandatory basis for every way of running it | Accepted, amended by 0027 |
| [0023](0023-push-notifications.md) | Push through Firebase and APNs as a plugin, content-free payload | Amended by 0026, 0037 |
| [0024](0024-malware-scan.md) | A mandatory malware scan, fail-closed | Accepted, amended by 0036, 0037, 0054 |
| [0025](0025-money-representation.md) | An in-house `Money` value type instead of JavaMoney or Joda-Money | Accepted |
| [0026](0026-core-outbound-via-plugins.md) | Every outbound connection of the core moves into a plugin | Accepted, amended by 0037, 0042 |
| [0027](0027-egress-enforcement.md) | Egress enforcement: deny-all for the core, a proxy per plugin segment | Accepted, amended by 0036, 0037; §1 withdrawn by 0042 |
| [0028](0028-plugin-runtime-stage-1.md) | The plugin runtime moves to stage 1 | Accepted |
| [0029](0029-session-cookie-and-oidc-state.md) | `SameSite=Strict` stays; code resolution and OIDC state are decoupled | Accepted |
| [0030](0030-public-code-format.md) | The public code: 10 payload characters and a Damm check symbol | Accepted |
| [0031](0031-audit-chain-per-tenant.md) | The audit chain runs per tenant and is anchored in time | Accepted, amended by 0046, 0063 |
| [0032](0032-per-tenant-blob-addressing.md) | Blobs are content-addressed within a tenant, never across | Accepted |
| [0033](0033-dark-as-default-appearance.md) | Dark is the default appearance, on every platform | Accepted, amended by 0038 |
| [0034](0034-icon-set-and-no-third-party-hosts.md) | Lucide as the icon set, and no third-party host for anything | Accepted |
| [0035](0035-design-system.md) | The delivered design system is binding, and `tokens.json` is its single source | Accepted |
| [0036](0036-scanner-egress.md) | The malware scanner gets a route out; the egress proxy runs in every profile | Accepted |
| [0037](0037-per-plugin-network-segments.md) | One network segment per plugin, and the management port out of their reach | Accepted |
| [0038](0038-csp-delivery-and-first-paint.md) | The CSP is delivered by `web` with hashes; the theme is mirrored locally | Accepted, amended by 0040 |
| [0039](0039-degraded-response-signalling.md) | Degradation is signalled in the payload; the `Warning` header is dropped | Accepted |
| [0040](0040-no-cross-origin-isolation.md) | `Cross-Origin-Embedder-Policy` is dropped; `COOP` and `CORP` stay | Accepted |
| [0041](0041-migration-as-its-own-service.md) | Migration is its own one-shot service, on every runtime | Accepted |
| [0042](0042-edge-is-not-internal.md) | A published port needs a non-internal segment; `web` becomes the ingress | Accepted, amended by 0044 |
| [0043](0043-blobstore-as-its-own-service.md) | The filesystem `BlobStore` gets its own in-deployment service | Accepted, amended by 0044, 0050 |
| [0044](0044-internal-is-not-a-trust-boundary.md) | `internal` is not a trust boundary: every datastore authenticates, and `web` leaves it | Accepted |
| [0045](0045-wal-archive-volume.md) | The WAL archive is a volume of its own, or the recovery point objective is fiction | Accepted |
| [0046](0046-truncatable-audit-chain.md) | The audit chain is truncatable, and per-tenant retention has a mechanism | Accepted |
| [0047](0047-bilingual-search-vectors.md) | Two generated search vectors, German and English, instead of `simple` | Accepted |
| [0048](0048-no-managed-remote-folders.md) | No OpenProject-style managed folders at the remote storage | Accepted |
| [0049](0049-openapi-generated-from-the-implementation.md) | The OpenAPI document is generated from the implementation, with a drift check | Accepted |
| [0050](0050-blobstore-service-in-rust.md) | The `blobstore` service is written in Rust | Accepted |
| [0051](0051-broker-in-stage-0.md) | RabbitMQ moves to stage 0, because a stage-0 requirement needs the worker | Accepted |
| [0052](0052-one-stored-image-format.md) | Every stored image is AVIF, and the bytes that arrived are never stored | Accepted, amended by 0054 |
| [0053](0053-first-owner-as-a-one-shot.md) | The first owner is created by a one-shot service, not by an endpoint | Accepted |
| [0054](0054-the-scan-is-asynchronous.md) | The malware scan runs in the worker, and the upload is answered before it | Accepted |
| [0055](0055-five-first-class-plugin-sdks.md) | Five first-class plugin SDKs: Java, Kotlin, Rust, Python and Go | Accepted |
| [0056](0056-schema-validation-with-the-shipped-document.md) | Attributes are validated with the shipped schema document, and it resolves nothing remotely | Accepted |
| [0057](0057-the-instance-operator.md) | The instance operator is a flag on an account, not a role in a tenant | Accepted |
| [0058](0058-authz-schema-name.md) | The `authorization` block's schema is called `authz`, because the name is reserved | Accepted |
| [0059](0059-subtree-scope-has-two-lines.md) | The location scope holds in the application **and** in row-level security | Accepted |
| [0060](0060-the-erasure-runs-in-one-pass.md) | A tenant erasure is one ordered pass in the worker, not a choreography | Accepted |
| [0061](0061-second-factor-locks-the-role.md) | A role that requires a second factor is granted, and locked until the factor exists | Accepted |
| [0062](0062-passkeys-with-webauthn4j.md) | Passkeys are verified by webauthn4j, and no attestation is trusted | Accepted |
| [0063](0063-bulk-is-a-transaction-per-entry.md) | A bulk operation is a transaction per entry | Accepted |
| [0064](0064-the-ports-a-plugin-implements-are-apache.md) | The ports a plugin implements live in `plugin-api`, not in the core | Accepted, amended by 0067 |
| [0065](0065-the-plugin-call-envelope.md) | A plugin call is resolved to a port instance and wrapped in one envelope | Accepted, amended by 0066 |
| [0066](0066-instance-level-capability-grants.md) | An instance-level capability grant, for what the deployment owes an account | Accepted, amended by 0067 |
| [0067](0067-breached-passwords-from-a-shipped-list.md) | Breached passwords are checked against a shipped list, and a plugin may add to it | Accepted |
| [0068](0068-an-export-opens-what-its-requester-may-read.md) | An export opens a sealed value exactly as far as its requester may read it | Accepted |
