# ADR-0039 — Degradation is signalled in the payload; the `Warning` header is dropped

**Status:** Accepted · **Date:** 2026-09-11
**Amends:** [08 §8.2](../architecture/08-api-contract.md),
[05 §5.6](../architecture/05-runtime-view.md),
[13 §13.6](../architecture/13-operations-and-observability.md),
[ADR-0008](0008-search.md), `REQ-SRCH-006`

## Context

The corpus signals two different things with the HTTP `Warning` header:

| Use | Where |
|---|---|
| `Warning: 199 "degraded search"` — the search fell back to PostgreSQL | [ADR-0008](0008-search.md), [05 §5.6](../architecture/05-runtime-view.md), [13 §13.6](../architecture/13-operations-and-observability.md), `REQ-SRCH-006` |
| `Warning: 299 - "Endpoint … is deprecated"` — alongside `Deprecation` and `Sunset` | [08 §8.2](../architecture/08-api-contract.md) |

**`Warning` was obsoleted by RFC 9111 §5.5 (June 2022).** The field is gone from
the HTTP caching specification, generators are told not to emit it and recipients
are told to ignore it. Building a documented contract on it now means building on
something the specification has already removed — and `REQ-SRCH-006` makes it an
*acceptance criterion*, so it would be implemented and tested into place.

Both uses also have a better-specified neighbour already in the design:

- The response envelope in [08 §8.2](../architecture/08-api-contract.md) already
  carries `"meta": { "degraded": false, "took": 41 }`. Every degraded response in
  this system is a JSON body — there is no degradation path that returns a bare
  status.
- Deprecation already ships `Deprecation` (RFC 9745), `Sunset` (RFC 8594) and a
  `Link` with `rel="deprecation"`. The `Warning: 299` line restates, in prose,
  what three standardised fields say in machine-readable form.

## Options

| Option | For | Against |
|---|---|---|
| Keep `Warning` | No change | Obsolete since 2022. Intermediaries may strip it, clients are told to ignore it, and the project would be specifying a header it cannot rely on reaching anyone |
| Invent a project header (`X-Homeinv-Degraded`) | Visible without parsing a body | A custom header is a second contract to version and document, for information the body already carries — and every client here parses the body anyway |
| **Signal in the envelope; drop the header entirely** | One place, already specified, already versioned with the API, already visible to the generated clients | A client that only reads headers learns nothing. There is no such client in this system |

## Decision

### 1. Degradation is a field in the response envelope

```jsonc
{
  "data": [ … ],
  "page": { … },
  "meta": {
    "degraded": true,
    "degradedReason": "search-fallback",   // stable, documented token
    "took": 137
  }
}
```

`meta.degraded` already existed and was a boolean nobody set. It is now the
contract. `degradedReason` is a stable token from a documented, extensible set —
clients branch on it the way they branch on `problem.type`, never on prose. A new
token is a **minor** change, exactly like a new `problem.type` value
([08 §8.3](../architecture/08-api-contract.md)).

The first token is `search-fallback`. Others arrive with the degradation levels
they describe ([13 §13.6](../architecture/13-operations-and-observability.md)).

### 2. `Warning: 299` on deprecated endpoints simply goes

`Deprecation`, `Sunset` and `Link; rel="deprecation"` stay. They carry the same
information, in fields that are current, parseable and already documented in
[ADR-0011](0011-api-versioning.md).

### 3. Degradation is never signalled by a status code

`503` stays reserved for *"this will not work"*, not *"this worked less well"*. A
degraded search returns `200` with `meta.degraded: true`, because the results are
correct — only fewer facets and a different ranking
([ADR-0008](0008-search.md): *"a stale index can at most show one hit too many or
too few, never wrong content"*).

## Rationale

The substantive point is not that `Warning` is deprecated — it is that the
information never belonged in a header here. Every consumer of a degraded
response in this system is a generated client reading a typed envelope
([ADR-0011](0011-api-versioning.md): *"Hand-written clients do not exist"*). Those
clients get `meta.degraded` as a field on a type, checked by the compiler. They
would have got `Warning` as a string they had to remember to look at, through an
intermediary that is entitled to drop it.

The header was also the only part of the degradation story that lived outside the
contract the CI checks. `oasdiff` guards the envelope; nothing guarded the header.

## Consequences

- **`REQ-SRCH-006` changes** its acceptance criterion from `Warning: 199` to
  `meta.degraded: true` with `degradedReason: "search-fallback"`.
- **A new requirement, `REQ-API-012`**, states the envelope contract so that
  future degradation levels signal the same way instead of each inventing
  something.
- **[08 §8.2](../architecture/08-api-contract.md)'s deprecation example loses its
  `Warning` line** — and, in the same pass, gains dates that respect the
  12-month rule the section itself states. The example previously showed 28 days
  between `Deprecation` and `Sunset`.
- **The `meta` object becomes part of the OpenAPI schema in earnest**, so a
  missing `degraded` field is a contract break rather than an omission.
- **The UI requirement is unchanged.** `REQ-SRCH-006` always said the UI shows
  the state; only the wire mechanism moves.
