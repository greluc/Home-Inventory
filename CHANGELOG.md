# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Four things are versioned **separately**, because they move at different speeds
([08 §8.3](docs/architecture/08-api-contract.md)): the application, the REST API,
the plugin contract and the event schemas. Where an entry affects one of the
latter three, it says so.

Entries are short — one to three sentences on *what* changed and *why it matters
to the user*. Design rationale belongs in an ADR, not here.

## [Unreleased]

The project is in its design phase; nothing is released. This section records the
groundwork so that the first release has a history rather than a single "initial
commit".

### Added

- The architecture as 14 arc42-oriented chapters, covering the building blocks,
  the runtime and deployment views, the data model, the API contract, the plugin
  system, identification and labels, offline synchronisation, security and
  operations.
- A requirements catalogue with 390 numbered, testable requirements across
  functional, non-functional, security and privacy areas, assigned to four
  delivery stages.
- 25 architecture decision records, each with its alternatives and consequences —
  including the ones that shape everything else: a modular monolith rather than
  microservices, row-level security as a second line of defence, rootless as the
  only supported way to run it, and a plugin runtime that keeps third-party code
  in its own process.
- A verified starter catalogue of label geometries
  ([`docs/reference/label-media.yaml`](docs/reference/label-media.yaml)), with a
  per-format flag distinguishing measured geometries from calculated ones.
- Project groundwork: licences, contribution and security policies, a code of
  conduct, issue and pull request templates, and the directory layout the
  implementation will fill.

### Fixed

- `item_attr_index` had no column for the unit belonging to a number. A `money`
  or `quantity` attribute would have lost its currency or unit in the projection,
  and a total would have silently added EUR to USD. A `unit_value` column was
  added, and every aggregation now groups by it.

[Unreleased]: https://github.com/greluc/Home-Inventory/commits/main
