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
- A requirements catalogue with 415 numbered, testable requirements across
  functional, non-functional, security and privacy areas, assigned to four
  delivery stages.
- 43 architecture decision records, each with its alternatives and consequences —
  including the ones that shape everything else: a modular monolith rather than
  microservices, row-level security as a second line of defence, rootless as the
  only supported way to run it, and a plugin runtime that keeps third-party code
  in its own process.
- The design system, in [`design-system/`](design-system/), and it is **binding**:
  one token set driving both the web client and the apps, dark as the default
  appearance everywhere with light one click away, 48 components, self-hosted
  IBM Plex and Lucide icons, and a measured contrast ratio for every colour pair
  in both themes.
- **Two registries that clients can program against**, alongside the label
  catalogue: the stable error codes an API response can carry, and the tokens that
  say a result was served in a degraded mode. Both were referred to as documented
  sets that did not exist.
- A project website for GitHub Pages ([`website/`](website/)): five pre-rendered
  pages that work with JavaScript switched off, dark by default, and loading
  nothing from any host but their own — which a CI gate checks rather than
  assumes.
- A verified starter catalogue of label geometries
  ([`docs/reference/label-media.yaml`](docs/reference/label-media.yaml)), with a
  per-format flag distinguishing measured geometries from calculated ones.
- Project groundwork: licences, contribution and security policies, a code of
  conduct, issue and pull request templates, and the directory layout the
  implementation will fill.

### Changed

- **Photos and documents now have somewhere to live.** The default media store —
  the only one a small installation has — had no volume anywhere in the
  deployment and no backup entry, so in that configuration uploads had nowhere to
  be written and nothing to be restored from. It is now a small service of its
  own, which also keeps the application containers stateless.
- **Full-text search promises what it can deliver at each stage.** The first
  release searches names and descriptions; notes, custom field values, tags and
  storage paths join it with the next one, in both the fast index and the
  fallback — so a small installation without the search server is not left behind.
- **Anyone allowed to see licence keys and similar secrets now needs a second
  factor.** Re-confirming with a second factor was already required before such a
  field is revealed, but only administrators were obliged to have one.
- **The browser is no longer asked to commit your whole domain.** The strict
  transport header ships without the `preload` flag: the protection it actually
  provides stays, while enrolling a domain in the browsers' permanent preload
  list — slow to undo, and binding on every other service you run under the same
  domain — becomes a step you take deliberately.
- **Storing a licence key has a defined format.** The encryption was decided; the
  exact bytes were not, and they cannot be changed once the first secret is
  written. Key rotation no longer means rewriting stored values.
- **Requests now pass through the web container without losing anything.** Size
  limits, timeouts, live-update streaming and the visitor's real IP address are
  specified across that hop — the last of which decides whether rate limiting and
  the audit log record the visitor or the server in front of them.
- **Dymo label geometries stay marked unverified, and now say why.** Dymo does not
  publish printable areas per label the way Brother does; the printer reads them
  from the roll. For those labels the calibration sheet is the path, and the
  software says so instead of implying a missing lookup.
- **The first release's item now has one description instead of four.** What
  fields a newly created item carries was written down differently in the
  requirements, the roadmap and the data model; notes, purchase details and
  condition arrive with the second release, where they always belonged.
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
- **The malware scanner can now actually update its signatures.** It was
  mandatory and fail-closed from the first release while sitting on a network with
  no way out, so it would have run forever on whatever signature set its image was
  built with. The outbound proxy now runs in every setup, including the smallest
  one, carrying a single fixed entry for the signature mirror.
- **Each plugin now runs on its own network.** They shared one before, which let
  any plugin read the server's internal metrics, stop the virus scanner — and with
  it every upload in the system — and use another plugin's outbound route. None of
  that needed a permission the operator had granted.
- **Labels need about 15 mm, not 10.** The QR code carries the item's UUID as well
  as its short code, so it is larger than the earlier figure assumed. The preview
  now computes the minimum label size from the actual code content and warns
  before a sheet is printed that will not scan.
- **A degraded search now says so in the response body** rather than in an HTTP
  header that was withdrawn from the specification in 2022 and that clients are
  told to ignore.
- **Database migrations run as their own step, once, and then exit.** The account
  that owns the tables is no longer mounted inside the long-running server, so a
  break-in there cannot reach it. The server checks that the schema matches before
  it accepts traffic, instead of changing it.
- **One browser security header is deliberately gone.** It protected against a
  class of attack this system has nothing to lose to, and its cost was that images
  and plugin panels could silently fail to load — including for plugin authors who
  had no way to know why.
- **The deployment now publishes one port instead of two**, and the application
  server publishes none at all: requests reach it through the web container. This
  came out of testing an assumption that turned out to be wrong — a container on a
  fully isolated network cannot accept an incoming connection, which would have
  made the first installation unreachable.
- **Label templates know how much of a label can actually be printed.** A Brother
  "29 × 90 mm" label offers 25.9 × 83.9 mm, and the print head is not centred on
  the narrow rolls. Both figures now come from the manufacturer's own reference, and
  the two Dymo formats are marked unverified until theirs do too.

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
