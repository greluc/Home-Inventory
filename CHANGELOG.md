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
- A requirements catalogue with 405 numbered, testable requirements across
  functional, non-functional, security and privacy areas, assigned to four
  delivery stages.
- 35 architecture decision records, each with its alternatives and consequences —
  including the ones that shape everything else: a modular monolith rather than
  microservices, row-level security as a second line of defence, rootless as the
  only supported way to run it, and a plugin runtime that keeps third-party code
  in its own process.
- The design system, in [`design-system/`](design-system/), and it is **binding**:
  one token set driving both the web client and the apps, dark as the default
  appearance everywhere with light one click away, 48 components, self-hosted
  IBM Plex and Lucide icons, and a measured contrast ratio for every colour pair
  in both themes.
- A verified starter catalogue of label geometries
  ([`docs/reference/label-media.yaml`](docs/reference/label-media.yaml)), with a
  per-format flag distinguishing measured geometries from calculated ones.
- Project groundwork: licences, contribution and security policies, a code of
  conduct, issue and pull request templates, and the directory layout the
  implementation will fill.

### Changed

- **Every connection leaving the deployment is now made by a plugin.** Object
  storage on S3 or Nextcloud, outgoing mail, federated login, webhooks and push
  are no longer part of the core image. An installation using local file storage
  and no mail contacts nothing outside itself. The plugin runtime therefore
  arrives in stage 1 rather than stage 3.
- **The printed code is longer**: 10 characters plus a check symbol instead of 8
  plus one. At a million labels the old length produced a name clash roughly a
  third of the time, and printed labels cannot be recalled.
- **Uploads are scanned and served safely from the first release**, not from the
  second. Stage 0 already accepts documents, so it already needs both.

### Fixed

- `item_attr_index` had no column for the unit belonging to a number. A `money`
  or `quantity` attribute would have lost its currency or unit in the projection,
  and a total would have silently added EUR to USD. A `unit_value` column was
  added, and every aggregation now groups by it.
- The documentation claimed in seven places that the core never connects to the
  outside world, while five shipped features did exactly that. The claim is now
  true and is verified by a test rather than asserted.
- A foreign-key check in PostgreSQL ignores row-level security, so a record could
  have been made to point at another tenant's data despite the isolation rules.
  Every reference between records now carries the tenant.
- Offline synchronisation sent a device everything in its tenant, filtered only by
  tenant — not by what that user is allowed to see, and including fields marked
  sensitive. Both are now filtered per user.
- The content security policy blocked three of the features it was written to
  protect: plugin panels could not load, offline image caching could not run, and
  the camera fallback scanner could not start.
- The change log table could not be created at all: its primary key did not
  include the column it was partitioned by.
- The deployment matrix described a stack that could not start — every database
  and message broker image was pinned to a user ID that image does not have.
- Files identical across two tenants were stored once. Knowing a file's checksum
  was enough to attach it, and to inherit the other tenant's malware verdict.

[Unreleased]: https://github.com/greluc/Home-Inventory/commits/main
