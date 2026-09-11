# Home Inventory

**Repository:** <https://github.com/greluc/Home-Inventory>

> **Product name:** *Home Inventory*. Technical identifiers consistently use the
> short form: image `home-inv`, package root `de.greluc.homeinv`, protobuf
> `home_inv.plugin.v1`, environment prefix `HOMEINV_`, containers `homeinv-*`,
> plugin IDs `de.greluc.homeinv.plugin.*`. The **repository name is the one
> long-form exception**; the container image is therefore named explicitly, since
> derived from the repository it would read `home-inventory`.

A self-hosted inventory system for **physical and digital items** — with
type-dependent metadata, arbitrarily nested storage locations, photos, QR and
barcode identification, label printing, offline-capable apps, and a versioned,
extensible API.

The system is designed from the ground up as a **modular monolith with a plugin
ecosystem**: a core of strictly separated building blocks coupled only through
explicit ports and events, plus a versioned extension interface through which
scanners, printers, label formats, metadata sources and storage backends are
added — without touching the core.

## Status

**Design phase.** This repository currently contains architecture and
requirements only. There is no code yet.

## Documentation

Start here: **[docs/README.md](docs/README.md)** — the map over all documents.

| Area | Content |
|---|---|
| [Architecture](docs/architecture/) | An arc42-oriented description of the whole system |
| [Decisions (ADR)](docs/adr/) | Every load-bearing decision with its rationale and alternatives |
| [Requirements](docs/requirements/) | A numbered, verifiable catalogue — functional, non-functional, security |

## Technology at a glance

| Layer | Choice |
|---|---|
| Backend | Java 25 (LTS), Spring Boot 4, Spring Modulith, Gradle 9 (Kotlin DSL) |
| Storage | PostgreSQL 18 (row-level security, JSONB, `uuidv7()`) |
| Search | OpenSearch (a derived read model) |
| Messaging | RabbitMQ (quorum queues) + a transactional outbox in PostgreSQL |
| Media | A `BlobStore` port — adapters for filesystem, S3/MinIO and **Nextcloud (WebDAV)** |
| Web | React 19 + TypeScript + Vite, an installable PWA |
| Apps | Kotlin Multiplatform + Compose Multiplatform (Android, iOS) |
| API | REST (OpenAPI 3.1, `/api/v1`) · GraphQL (read-only) · gRPC (plugins, internal) |
| Identity | An own identity core (Argon2id, TOTP, passkeys) + OIDC federation |
| Operation | **Rootless**: Podman ≥ 5 + Quadlet *or* Docker + Compose v2 — both supported; the Helm chart is maintained as an equal |
| Distributions | Debian ≥ 13 · Ubuntu ≥ 26.04 LTS · Fedora ≥ 43 · RHEL, CentOS Stream, Rocky Linux, AlmaLinux ≥ 9.5 resp. ≥ 10 |
| Money | An in-house `Money` value type (`BigDecimal` + `java.util.Currency`) — no money library ([ADR-0025](docs/adr/0025-money-representation.md)) |
| Design | A binding design system, dark by default, one token source for web and apps ([ADR-0035](docs/adr/0035-design-system.md)) |
| Licence | AGPL-3.0-or-later (plugin API: Apache-2.0) |

## Repository layout

```
.
├── api/           OpenAPI 3.1 specification — the contract, written before the code
├── app/           the Spring Boot application (api + worker roles)
├── cla/           contributor licence agreements
├── deploy/        services.yaml (source of truth) → quadlet/ compose/ helm/
├── design-system/ the binding design system — tokens, components, fonts, icons
├── docs/          architecture, decisions, requirements, reference data
├── plugin-api/    the ports a plugin implements — Apache-2.0, not AGPL
├── plugin-sdk/    Java and Python SDKs, and the contract test suite
├── proto/         home_inv.plugin.v1 — the plugin contract
└── web/           the React PWA
```

Each directory carries a `README.md` explaining what belongs there, what does
not, and which stage fills it. Most are empty today — deliberately: the
architecture is finished, the implementation is not started.

## Taking part

| | |
|---|---|
| [Contributing](CONTRIBUTING.md) | What the project expects. Right now the most valuable contribution is finding a hole in the design. |
| [Code of Conduct](CODE_OF_CONDUCT.md) | Contributor Covenant 3.0 |
| [Security policy](SECURITY.md) | **Never open a public issue for a vulnerability** — use private reporting |
| [Changelog](CHANGELOG.md) | |
| [Known plugins](PLUGINS.md) | A curated list; not a distribution channel |

## Licence

**AGPL-3.0-or-later** for the core ([`LICENSE`](LICENSE)) — the network clause
matters here, because this is software that is run as a service.

**Apache-2.0** for the plugin API, the SDKs and the protobuf contract, so that
plugin authors may license their work however they wish. The separation is
structural: those modules have no dependency on core modules, and CI checks it
([ADR-0018](docs/adr/0018-licensing.md)).

Documentation is CC BY-SA 4.0. Per-file licensing follows
[REUSE](https://reuse.software); see [`REUSE.toml`](REUSE.toml).
