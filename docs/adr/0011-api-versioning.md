# ADR-0011 — The major version in the URL, additive minor versions

**Status:** Accepted · **Date:** 2026-09-11

## Context

The API has to be under version control. Its clients are the web PWA, the KMP
apps (which can stay old on devices for a long time), third-party systems and
plugins. An app a user has not updated for a year must not fail without warning.

## Options

| Option | For | Against |
|---|---|---|
| **Major version in the path (`/api/v1`)** | Immediately visible, trivial to test, cache-friendly, obvious for third-party developers | Coarse-grained: one change versions everything along with it |
| Media type versioning (`Accept`) | Fine-grained per resource, URLs stay stable | Hard to test and cache, inconvenient for third-party developers, proxies behave inconsistently |
| Version as a header or a parameter | Flexible | Easy to forget, hard to document, hard to cache |
| No versioning, additive changes only | The simplest to maintain | A real break becomes unavoidable eventually, and then there is no path |

## Decision

**The major version in the path**, additive minor versions without a path change,
with this policy:

| Rule | Value |
|---|---|
| Break | A new major version `/api/v2`; `v1` stays available for **at least 12 months** |
| Plugin contract | Available in parallel for at least **6 months** |
| Announcement | `Deprecation` and `Sunset` headers (RFC 9745 / RFC 8594), a `Link` to the migration guide, the changelog |
| Shutdown | Only once measured usage is zero **or** the period has elapsed |
| Parallel operation | `v2` is an adapter onto the same application layer, not a second implementation |

What counts as a break and what does not is set out in full in
[08 §8.3](../architecture/08-api-contract.md).

## Rationale

The users of this API are predominantly people wiring something up themselves.
For them what matters is that the version is visible and that they see it in an
example call. Fine-grained versioning solves a problem that does not arise at
this rate of change, and costs clarity permanently.

## Consequences

- **Usage per endpoint, version and client is measured.** Without that
  measurement, a shutdown is a leap in the dark.
- Clients must **tolerate** unknown fields and unknown enum values. This is
  documented and implemented that way in the generated clients.
- Every client sends a `User-Agent` with product and version, so deprecations can
  be communicated to the right people.
- The apps show a notice when their contract version is deprecated, and refuse
  service only after the period has elapsed — never before.
- `oasdiff` in CI prevents a break from slipping into a minor version unnoticed.
