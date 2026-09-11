# 01 — Introduction and Goals

## 1.1 Purpose

`home-inv` records and manages **inventory** — physical objects as much as digital
goods — together with their metadata, their photos, their storage locations and
their history. It runs on a self-hosted server, is operated through a web
frontend, and in the long run through native apps as well.

Three properties shape the design:

1. **Type-dependent metadata.** There is no such thing as *the* item. A book has
   an ISBN, a tool has a serial number, a software licence has a key and a seat
   count. Which fields a type carries is **configured at runtime**, not coded in.
   The same holds for locations: a room, a moving box and a vehicle carry
   different details.
2. **Identification on the object.** Every item carries a UUID; physical items
   additionally carry a printed code. Generating, printing and scanning those
   codes are **replaceable building blocks**, not fixed parts of the core.
3. **Extensibility by third parties.** New code formats, new printers, new label
   geometries, new metadata sources and new storage backends are built as
   **plugins against a versioned contract** — without touching the core.

## 1.2 Functional scope

The scope is informed by [Homebox](https://homebox.software) (household, photos,
labels, maintenance log) and [InvenTree](https://inventree.org) (type system,
barcodes, plugin architecture, label printing), and goes beyond both in digital
goods, multi-tenancy and offline operation.

### Core capabilities

| Area | Scope |
|---|---|
| **Items** | Physical and digital; master data, type-dependent fields, quantities, serial numbers, relations (accessory, part of, replacement), bundles and sets, consumables with minimum stock |
| **Type system** | Item types and location categories with freely definable fields, inheritance, versioning, editable while running |
| **Locations** | Arbitrarily deep tree (building → room → shelf → box → compartment), categories with their own fields, mobile locations (vehicle, moving box) |
| **Tags** | Free, tenant-wide tagging across type and location |
| **Media** | Photos, invoices, manuals, warranty certificates; thumbnails, primary image, metadata stripping |
| **Identification** | UUID per item, plus a public short code; QR generation, scanning (camera, handheld scanner), resolution |
| **Labels** | Templates, sheet and roll geometry (Avery Zweckform among others), print jobs, printer plugins |
| **Enrichment** | Resolution of ISBN, EAN/GTIN, MPN and others through replaceable resolvers |
| **Lifecycle** | Purchase, warranty, maintenance log, lending and return, sale, disposal, trash |
| **Reporting** | Total value per location, type and tag; expiry and maintenance reminders; stocktake mode; moving mode |
| **Collaboration** | Tenants, memberships, invitations, roles, audit log |
| **Interfaces** | REST, GraphQL (read-only), gRPC (plugins), import/export, webhooks |

### Explicitly out of scope

- Enterprise resource planning: purchasing, supplier management, bills of
  material, production orders, accounting. (InvenTree covers that; `home-inv`
  does not.)
- Point of sale, shipping, customs, serial-number tracking across supply chains.
- Document management with full-text OCR as a discipline in its own right.
- A printer driver stack of our own — printers are attached through plugins only.

## 1.3 Quality goals

These seven goals are prioritised. Where they conflict, the lower number wins.
Measurable scenarios for them are in
[14 Quality, Risks, Glossary](14-quality-risks-glossary.md).

| # | Goal | Why | How it is measured |
|---|---|---|---|
| **Q1** | **Security** | The system is reachable from the internet, separates tenants and executes foreign plugin code. A mistake here cannot be undone. | No cross-tenant visibility even with deliberately faulty application logic (RLS proof); a plugin without a granted capability reaches no data |
| **Q2** | **Modularity** | A building block must be understandable, testable and replaceable without knowing the others. | Spring Modulith verification passes without violation; no block touches another's internal types; every block testable on its own |
| **Q3** | **Extensibility** | New code formats, printers, metadata sources and storage backends must be addable without changing the core. | A new barcode plugin runs without a single line of core change; a reference plugin in CI proves it |
| **Q4** | **Maintainability** | The system is meant to be maintained by very few people over years. | Domain logic test coverage ≥ 80 %; every module boundary guarded by an architecture test; documentation complete and current |
| **Q5** | **Data integrity** | Inventory data is hard to reconstruct; offline sync raises the risk of silent loss. | No data loss on conflict — every discarded version is recoverable; restorability of backups is verified automatically |
| **Q6** | **Usability under real conditions** | Capture happens in the cellar, in the garage, on a ladder — one-handed and without a network. | New item via scan in ≤ 3 interactions; fully usable without a network connection |
| **Q7** | **Scalability** | The inventory grows over years; the architecture must not fail at some size. | 1 M items per tenant: search p95 < 500 ms, detail view p95 < 150 ms |

**Deliberately subordinate:** minimal resource consumption. The target
environment is a Proxmox VM with ≥ 8 GB RAM; a slim footprint is not traded
against Q1–Q4. Switchable profiles exist for small installations (see
[06 Deployment View](06-deployment-view.md)), but they do not drive the design.

## 1.4 Stakeholders and expectations

| Role | What they expect from the architecture |
|---|---|
| **Operator / developer** | Maintainable alone over years; decisions documented and traceable; changes stay locally contained |
| **Tenant administrator** | Configure types, fields, location categories and roles without a developer |
| **Capturing users** | Fast capture and retrieval; works offline; scanning instead of typing |
| **Read-only users and guests** | See what they are allowed to see — and nothing beyond it |
| **Plugin authors (third parties)** | A stable, documented, versioned contract; clearly bounded permissions; developable locally |
| **Other operators (AGPL users)** | Reproducibly deployable, secure by default, operational tasks documented |
| **Data subjects (GDPR)** | Access, export and erasure are designed in, not improvised afterwards |

## 1.5 Guiding principles

These sentences decide the doubtful cases during implementation.

1. **Secure by default.** A fresh installation is hardened without further
   action. Relaxations are explicit, logged decisions by the operator.
2. **The core knows no special cases.** Wherever a specific technology, device
   vendor or format appears, it belongs behind a port.
3. **Contracts before implementations.** OpenAPI, protobuf and event schemas are
   written first and checked for breaking changes in CI.
4. **The database is the truth.** Search index, caches and projections are
   derived and rebuildable at any time; their failure degrades the system, it
   does not destroy anything.
5. **Nothing is lost silently.** Deletion is two-stage, conflicts are retained,
   every mutating action is traceable.
6. **A building block that speaks only through events and ports is a service
   later.** The option is kept open; the complexity is not brought forward.

## 1.6 References

- Requirements: [catalogue](../requirements/)
- Constraints and system context: [02](02-constraints-and-context.md)
- Why a modular monolith and not microservices:
  [03 Solution Strategy](03-solution-strategy.md)
