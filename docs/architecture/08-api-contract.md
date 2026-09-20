# 08 — API Contract

## 8.1 Three surfaces, one set of domain logic

```mermaid
graph LR
    R["REST /api/v1<br/>OpenAPI 3.1"] --> AS
    G["GraphQL /graphql<br/>read-only"] --> AS
    P["gRPC<br/>home_inv.plugin.v1"] --> AS
    AS["<b>Application layer</b><br/>use cases<br/>authorization<br/>transaction boundary"] --> D["Domain logic"]
```

**The rule without which three surfaces would be dangerous:** authorization and
domain rules live **exclusively** in the application layer. The surfaces
translate protocol, nothing more. A new surface therefore cannot open a security
hole — it can only invoke things that are already checked. An ArchUnit rule
forbids database and repository access from the access blocks.

| Surface | For whom | Scope |
|---|---|---|
| **REST** | Web PWA, apps, third-party systems, automation | Complete: read and write. The binding, versioned contract. |
| **GraphQL** | Demanding views and reporting | **Read-only.** No mutations — see [ADR-0010](../adr/0010-api-surfaces.md). |
| **gRPC** | Plugins, future internal services | The plugin contract in both directions |

## 8.2 REST

### Design principles

| Principle | Implementation |
|---|---|
| Specification first | `api/openapi.yaml` is the source. Server stubs, the TypeScript client, the Kotlin client and the documentation are **generated** from it. Hand-written clients do not exist. |
| Resources, not actions | `/items`, `/locations`, `/item-types`. Where an operation is not a resource, it becomes one: `/print-jobs`, `/stocktakes`, `/import-jobs`, `/scans`. |
| Predictability | The same pagination, the same filter syntax, the same error format, the same sort parameters **everywhere**. |
| Clients name themselves | Every client sends `X-Home-Inv-Client: <product>/<version>` — `web/1.4.2`, `android/2.0.0`. A **header and not `User-Agent`**, because a browser sets that one itself and a page cannot override it on `fetch`, so [ADR-0011](../adr/0011-api-versioning.md)'s arrangement could not be honoured by the main client (found 2026-09-20). The server tags its usage metric with the **product alone**, so a release does not become its own time series, and an unrecognised product is `other` — the header is chosen by the caller, and a free tag would let any request invent a series. Omitting it is not an error: a script gets counted as `other` (`REQ-API-009`) |
| No surprises in the status code | `200/201/202/204` · `400` syntax · `401` not authenticated · `403` authenticated but not permitted · `404` not present **or not visible** · `405` method not allowed · `406` not acceptable · `409` conflict · `412` precondition failed · **`413` payload too large** · `415` unsupported content type · `422` domain-invalid · `428` precondition required · `429` rate limit · **`500` unexpected** · `503` degraded. *`413` and `428` were missing from this list while [`problem-types.yaml`](../reference/problem-types.yaml) needed both — `428` from the ETag rule below, and `413` from the limits in the security table, which is why that condition sat in the registry's `pending` list with no status code (O24). `500`, `405`, `406` and `415` were missing too, and their absence was not cosmetic: every one of them is reachable today — `POST /api/v1/items/{id}` is a 405 — and without a token each left as Spring Boot's own error JSON rather than as problem details, on the paths a client can least anticipate (corrected 2026-09-12).*

### Resource overview

```
/api/v1
├── /auth        /login /logout /refresh · /password-reset {,/complete}
│             /mfa  (answer a login) · /mfa/enrolment · /mfa/totp {,/confirmation,/removal}
│             /mfa/recovery-codes · /mfa/step-up  (prove it again)
│             /mfa/passkeys {,/challenge,/confirmation,{id}/removal}
│             /authorize /token /revoke /clients      (OAuth 2.1, see below)
├── /.well-known /oauth-authorization-server /jwks.json
├── /me          profile, devices, settings · /tenants (memberships) · /tenant (switch)
│             /sessions {handle}   what is signed in, and ending one of them
├── /tenants     {id}/members {id}/invitations {id}/quotas {id}/settings {id}/export
│             {id}/service-accounts {id}   machine tokens (REQ-AUTH-010)
├── /instance    /accounts {id}/entitlements · /operators · /tenants/{id}/quotas
│             /erasures {tenantId}  what an erasure removed, per block
│                                                      (the instance operator only)
├── /invitations {token}/accept          (no session: this is how an account is made)
├── /tenant-revocations {token}          (no session: the erasure is what removed it)
├── /roles       {id}   tenant-owned roles and the permissions they add
├── /field-visibility   which roles read which sensitive fields
├── /catalog     /item-types /location-categories /versions /fields /value-lists
│                /type-templates {key}/import   (the eight of REQ-CORE-030)
│                {id}/child-categories   (what a category takes under it)
├── /items       {id} {id}/attachments {id}/relations {id}/maintenance
│                {id}/loans {id}/history {id}/codes {id}/bundle {id}/bundles
│                · /items/bulk
├── /locations   {id} {id}/children {id}/items {id}/move {id}/seal {id}/tags
├── /tags        {id} /tags/merge
├── /media       /uploads (tus) {id} {id}/variants
├── /codes       /resolve  {code}  (public resolution: /c/{code})
├── /scans       scan sessions and individual scans
├── /label-templates  /label-media  /print-jobs
├── /enrichment  /proposals {id}/accept {id}/reject  /resolvers
├── /saved-searches    named queries usable as smart lists (REQ-SRCH-008)
├── /stocktakes  {id}/scans {id}/report
├── /sync        /pull /push /conflicts /devices
├── /notifications  /rules /subscriptions
├── /import-jobs /export-jobs /mapping-profiles
├── /plugins     {id} {id}/capabilities/{capability}   (PUT to consent, DELETE to withdraw)
│                {id}/health {id}/enable {id}/disable
├── /audit       log queries
└── /webhooks    delivery targets and delivery attempts
```

Non-CRUD operations use the form `POST /resource/{id}/action` — `{id}/archive`,
`{id}/publish`, `{id}/restore`, `{id}/move`. *This read `POST /resource/{id}:action`
(a colon, as in Google AIP) until 2026-09-13, by which time a dozen implemented
endpoints used the sub-path and none used a colon; the code is right and the
sentence was wrong.* A verb at the end of a path is distinguishable from a
sub-resource by what it is — an imperative, never a plural — and the colon buys
that distinction at the cost of a character half of every HTTP toolchain escapes
in a path.

> **`identity` is an OAuth 2.1 authorization server, not just a login endpoint.**
> `REQ-AUTH-007` and `REQ-SEC-019` require the apps to authenticate through
> Authorization Code + PKCE in the system browser, with no password in the app —
> which needs `/authorize`, `/token`, a metadata document and a JWKS endpoint.
> They were specified without ever appearing in this list, which made `identity`
> look like a session service. It is not, and the difference is a substantial part
> of that building block. Clients are registered by the operator
> (`/auth/clients`); there is no dynamic client registration, because every client
> here is one the operator installed.

### Querying, pagination, sorting

```
GET /api/v1/items
  ?q=drill                              full text
  &filter=type:power-tool               structured filter, repeatable
  &filter=attr.purchasePrice:gte:100:EUR
  &filter=location:subtree:{uuid}
  &filter=tag:in:broken,repair
  &facet=type,tag,location              counts beside the rows, comma-separated
  &sort=-updatedAt                      minus = descending; one key
  &fields=id,name,primaryPhoto          sparse fields
  &cursor=eyJ2IjoxLCJrIjoi…             cursor, never an offset
  &limit=50                             max. 200
```

**A range over a dimensioned field names its unit, and is refused without
one.** `item_attr_index` keeps `unit_value` beside `num_value` precisely so that
a total never adds euros to dollars ([ADR-0025](../adr/0025-money-representation.md));
a comparison is a subtraction with the sign thrown away, so it inherits the rule.
The example above read `filter=attr.purchasePrice:gte:100` until 2026-09-14 and
was wrong: "over 100" across currencies is a number nobody asked for, and the
answer it would return looks right. Equality needs no unit — no amount of euros
equals an amount of dollars either — and a unit on a field that has no dimension
is refused too, rather than dropped. All four refusals answer `422`, naming the
field.

**`in` widens, a second filter narrows.** Within one filter the values are an
"or" — `tag:in:broken,repair` is either — and across filters they are an "and",
so `filter=tag:broken&filter=tag:heavy` is both. That distinction is what makes a
multi-select facet behave the way people expect it to, and it is why the two
spellings are not interchangeable.

**Three of the four dimensions belong to other building blocks**, and none of
them is read by joining a foreign schema. A type key is `catalog`'s, a tag
assignment is `tagging`'s and the tree is `locations`'; `search` resolves each
through that block's published port and hands the engine nothing but ids
([ADR-0002](../adr/0002-modular-monolith.md)). `type:` names the key rather than
an id because that is what a person writes, and it matches items written against
**every** version of that type — an item written last year is still a power tool.
`tag:` names the tag the way a person wrote it, case-insensitively, and follows a
merge: once "broken" has been merged into "heavy", a saved filter for `broken`
still finds the items rather than returning an empty list that looks like an
answer (`REQ-CORE-063`). A value nothing matches — a type key nobody used, a tag
nobody created — returns no rows rather than an error, because that is the true
answer to the question.

**One sort key, and it is refused rather than reduced.** `REQ-SRCH-004` asks
for "sorting over `sortable` fields, ascending and descending"; the example above
showed two keys until 2026-09-14, and `sort=-updatedAt,name` now answers `422`. A
caller who asks for two orderings and silently receives one holds a list that is
wrong in a way nothing tells them about. Underneath whatever is chosen sits
`(created_at, id)` as the tie-break, because a name is no more unique than a
timestamp and a page boundary on a non-unique key is how keyset pagination
repeats or loses a row.

**Rows without the sorted value come last in both directions** — not the
database's default, which puts them first when descending. Somebody who clicks a
column heading twice should not be handed a page of items that do not have the
field at all. And a **money or quantity field orders by its unit before its
amount**: without that, 90 USD would precede 100 EUR and the list would assert a
ranking that does not exist without an exchange rate, which ADR-0025 keeps out of
the core.

**Cursor pagination exclusively.** Offsets are wrong on mutable data (skipped and
duplicated rows) and slow at large offsets. The cursor is opaque, signed, and
carries the sort key plus a filter checksum — a cursor with changed filters is
rejected instead of silently returning wrong results.

```jsonc
{
  "data": [ … ],
  "page": { "nextCursor": "…", "hasMore": true, "estimatedTotal": 1284 },
  "meta": {
    "degraded": false,          // true when a derived store is unavailable
    "degradedReason": null,     // a stable token, e.g. "search-fallback"
    "took": 41
  },
  "facets": [                   // only when `facet=` asked for them
    { "dimension": "tag",
      "buckets": [ { "value": "defekt", "count": 4 }, { "value": "neu", "count": 12 } ] }
  ]
}
```

**Facets are the fourth member, and usually absent.** Counts are neither rows,
nor where the next rows are, nor how the answer was produced — `meta` says how a
response came about and would stop meaning that if it also carried content. So
`facet=type,tag,location` adds a `facets` array, and every listing that was not
asked for counts omits the member entirely. `facets` is the **one** member this
API leaves out when it is null; everywhere else a null member is written out as
`null`. The difference carries meaning only here: absent is "nobody asked",
`[]` is "counted, and nothing matched", and a client that cannot tell those apart
shows an empty sidebar where there should be none — `ItemFacetIT` asserts both
states, so the distinction is checked and not merely asserted here. *This section claimed until
2026-09-14 that the serialiser omits every null member. It does not, and never
did; the sentence has been corrected to what the API actually sends.*

A bucket's `value` is the token a `filter` takes back — a type key, a tag name, a
location id, an attribute's stored value — so a client turns a count into a
narrower list without a round trip to learn what anything is called. Buckets come
largest first, ties broken by value so the order is stable between two identical
requests, and at most fifty per dimension: a tenant with four hundred tags would
otherwise put four hundred numbers into a response for a sidebar showing a dozen.

**A facet is counted without its own filter.** With `filter=tag:defekt` active
the tag facet still counts every tag, because the sidebar exists to show where
one could click next and a count of the thing already clicked is not that. Every
*other* dimension's filters do narrow it, so the numbers still describe the list
on the screen. That makes "facet counts agree with the results" true of a
dimension nobody filtered by and deliberately untrue of one somebody did, which
is what `REQ-SRCH-002`'s acceptance now says.

**`location` is the exception**: a tree is drilled into by descending, not by
looking at siblings. So the location facet keeps its own filter and uses it to
set the level — without one it counts per root of the tree, and under
`filter=location:subtree:X` it counts per direct child of X, each carrying its
whole subtree. Items lying directly in X are in none of X's children and are in
no bucket, because the counts must not add up to more than the list.

**`category` is the type above the type** (`catalog.item_type.parent_id`). There
is no separate item category — the only table called *category* describes a
location — and a type at the root of the tree is in no category and contributes
no bucket. **`condition` is a tag group**, not a dimension of its own: the tags
carry the group, so the tag facet already counts it and a client renders that
group as its own block. Both were settled on 2026-09-14; `REQ-SRCH-002` listed
them without either being defined anywhere.

**The envelope is every collection's, not the search's.** All seventeen paged
responses carry it, through one `platform.Page<T>` rather than a record per
listing (implemented 2026-09-14, with the owner). The alternative — adding `meta`
only to the one listing that can degrade — was considered and rejected: two shapes
in one API is the drift this chapter exists to prevent.

`estimatedTotal` is explicitly an estimate — an exact total over a million rows
costs more than it is worth — and it is **absent rather than wrong** where nobody
counted, which today is everywhere. `took` is filled in at the edge, because no
use case can see routing, deserialisation and authorisation time.

**A null member is written out, and `facets` is the one exception.** The example
above spells `"degradedReason": null` out because that is what the API sends —
`degradedReason`, `estimatedTotal` and `took` are present and null rather than
absent. Only `facets` is omitted when it has no value, for the reason given
above. *This paragraph said the opposite until 2026-09-14, of an omission that
was never configured; the code is right and the sentence was wrong.*

**Which engine answers is configuration, not discovery.** `HOMEINV_SEARCH_ENGINE`
picks between the two shipped engines and `minimal` leaves it unset, so an
installation that never had OpenSearch is not reported as degraded — it is
small. `meta.degraded` means the engine this installation asked for could not
answer and the other one did, which is a different thing and the only one worth
a token ([ADR-0008](../adr/0008-search.md), `REQ-SRCH-005`).

**With OpenSearch, a query is answered in two steps.** OpenSearch matches the
text — stemmed, ranked, across name, description, notes, attribute values, tags
and the location path — and `item_attr_index` then narrows, sorts and pages
within what it found. That split is [07 §7.3](07-data-model.md)'s, and
`REQ-CORE-013` is why: filters and sorting are **transactionally exact**, and an
index that lags by design cannot be. A caller sees no difference beyond the ranking, which is the point of a
port.

**`meta.degraded` is the contract for degradation**, not a header
([ADR-0039](../adr/0039-degraded-response-signalling.md)). `degradedReason` is a
stable token from a documented set — clients branch on it the way they branch on
`problem.type`, never on prose — and a new token is a minor change. Degradation
never changes the status code: a degraded search returns `200`, because its
results are correct, only poorer. `503` stays reserved for "this will not work".

### Concurrency and idempotency

| Mechanism | Rule |
|---|---|
| **ETag / If-Match** | Every single resource returns `ETag: "<version>"` — the row's optimistic-lock version, quoted, and **not** a hash of the body: a body is redacted per caller ([12 §12.5](12-security.md)), so a hash would differ between two people looking at the same unchanged row. Strong, therefore, never weak. Every write on a single resource **requires** `If-Match` — `PUT`, `PATCH`, `DELETE` **and the verb-paths** (`{id}/move`, `{id}/restore`, `{id}/permanently`), because what the rule protects is the resource and not the method. A missing header → `428 Precondition Required`; a mismatch → `412`, carrying `expectedVersion` and `currentVersion` so a client can say what happened. **`If-Match: *` is refused with `428`**, which is a deliberate departure from RFC 9110: there the wildcard asserts only that the resource exists, which for an update means "overwrite whatever is there" — the blind overwrite this row forbids. `428` is the right status for saying which precondition the server wants (RFC 6585). Every clause here is checked by `EntityTagIT`, over HTTP, on a change, a deletion and a move. *The verb-path and wildcard sentences were added 2026-09-13 with the implementation; without them the rule had a hole a client could drive through.* |
| **Idempotency-Key** | Every creating `POST` accepts `Idempotency-Key`. Key plus payload hash are written to **PostgreSQL in the same transaction as the record they protect** and retained 24 h ([ADR-0009](../adr/0009-messaging-and-events.md)) — so there is no window in which the entity exists and the key does not. A repeat with the same payload → the original response; with a different payload → `409`, and the same for a key sent to a **different endpoint**, because a key is spent once rather than once per path. What is hashed is the **parsed** body and not the bytes: a client retrying through a different HTTP library may reformat its JSON, and that is the one case this mechanism exists to serve. Indispensable for mobile clients on unreliable networks, which is why it may not depend on a cache. Checked by `IdempotencyIT`. |
| **Client-generated IDs** | The client may supply `id` (UUIDv7). If it already exists with the same content, the result is `200` instead of `201`. A prerequisite for offline creation. |
| **Bulk operations** | `POST /items/bulk` handles up to 500 entries, **partially successful**, with a status line per entry and an idempotency key per entry. **One operation over a selection** — move, tag, change type or delete — with its target stated once and the entries naming only the items. `200` when every entry was applied, `207` when at least one was not (decided 2026-09-13): the code says whether to read the body closely, the body says what happened. A line has the fields of an RFC 9457 problem — `status`, `type`, `title`, `detail` — and is not one: the response succeeded, its media type is `application/json`, and there is one `traceId` for the request rather than one per entry. **Each entry is its own transaction** ([ADR-0063](../adr/0063-bulk-is-a-transaction-per-entry.md)); an entry that fails leaves nothing behind, its key included, so a client resending the whole selection retries exactly what failed. Faults of the *call* rather than of an item — no entries, more than 500, the same item twice, a missing target — are a `422` and change nothing. |

### Error format (RFC 9457)

```jsonc
{
  "type": "https://home-inv.example/problems/validation-failed",
  "title": "The attributes do not match the type definition",
  "status": 422,
  "detail": "2 fields are invalid.",
  "instance": "/api/v1/items",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "errors": [
    { "pointer": "/attributes/isbn",
      "code": "pattern",
      "message": "Not a valid ISBN-13.",
      "expected": "^97[89][0-9]{10}$" },
    { "pointer": "/attributes/purchasePrice/currency",
      "code": "enum",
      "message": "Unknown currency code." }
  ]
}
```

**The set of `type` values is a registry, not a convention.** It lives at
[`docs/reference/problem-types.yaml`](../reference/problem-types.yaml), is
generated into the OpenAPI document, and is guarded by `oasdiff`: a new token is
a minor change, a removed one or a changed status code is breaking
([8.3](#83-versioning)). The companion registry for `meta.degradedReason` is
[`degraded-reasons.yaml`](../reference/degraded-reasons.yaml). Until 2026-09-11
neither set was enumerated anywhere — three tokens appeared in worked examples
and a client author had no list to program against.

**Rules:** `type` is a stable, documented URI — **fixed for the product, not per
deployment**, or a client talking to two instances could not branch on it at all.
Clients branch on it, not on the text. `title`/`detail` are localised (`Accept-Language`). `traceId` connects the
response to the server log. Error messages **never** reveal whether a foreign
resource exists, and never contain internal paths, SQL or stack traces.

### Security-related headers and rules

| Rule | Implementation |
|---|---|
| Authentication | `Authorization: Bearer <JWT>` (short-lived, 10 min) for apps and third-party systems. For the web a `__Host-` session cookie with `Secure`, `HttpOnly`, **`SameSite=Strict`** plus a CSRF token for state-changing calls — and, scoped to `/c` only, a second `__Secure-` cookie with `SameSite=Lax` that exists for one purpose: so a QR scan from a foreign camera app recognises the signed-in user instead of bouncing them to the login page. It grants no authority of its own ([ADR-0029](../adr/0029-session-cookie-and-oidc-state.md)). |
| CORS | The configured frontend origin only; never `*`, never a reflection of the request origin. |
| Rate limiting | Per user, per tenant and per IP; the response is `429` with `Retry-After` and `RateLimit-*` headers. Login attempts and code resolution have their own, stricter limits. |
| Payload limits | JSON max. 1 MB, bulk operations max. 500 entries, uploads through the dedicated tus path with its own limit. The JSON limit is enforced by `JsonBodyLimitFilter` **before the body is read** when a `Content-Length` is declared, and while it is read when the request is chunked — a header alone would put the limit one header away from not applying. |
| Timeouts | A server-side ceiling of 30 s per request; long operations are `202` plus a job resource, never a long-held connection. It is enforced where a request can actually block rather than by a watchdog: `statement_timeout = 30s` on the database roles (set in [`deploy/postgres/initdb/00-roles.sql`](../../deploy/postgres/initdb/00-roles.sql), so a client cannot forget it), `lock_timeout` lower again because waiting for a lock is waiting for another transaction, and Tomcat's `connection-timeout` and `keep-alive-timeout` for a client that sends or reads slowly. A servlet container cannot interrupt a thread that is computing; what it can do is refuse to wait for a query, a lock or a socket, which is every way a request in this application waits. |

## 8.3 Versioning

Four contracts are versioned **separately**, because they evolve at different
speeds. `REQ-CON-004` names its four as *the application, the REST API, the
plugin contract and the event schemas*; this table lists the four **contracts**
and so has GraphQL in the application's place — the application's own version is
the artefact's, and GraphQL deliberately has none, which its row says. *The
spelling in the last row was `de.greluc.homeinv.item.created.v1` until
2026-09-16 and no event had ever been called that: the annotations use Modulith's
`target::key` form, and they carried no version at all. The keys gained one and
this row was corrected to what the code says.*

| Contract | Scheme | Breaking-change check |
|---|---|---|
| REST API | `/api/v1`, SemVer inside the OpenAPI document | `oasdiff` in CI, every change against the last release |
| GraphQL | No version, fields get deprecated | Schema comparison in CI |
| Plugin / gRPC contract | `home_inv.plugin.v1` | `buf breaking` in CI |
| Event schemas | `homeinv.inventory::item-created.v1` — Spring Modulith's `target::key` form, which is what the broker binds on, with the version in the **key** so a consumer can route on it and two versions can run side by side | `EventSchemaVersionTest` holds the shape; a schema comparison in CI holds the content |

### What counts as breaking

| Allowed in a minor version | Breaking — requires `/api/v2` |
|---|---|
| New optional fields in responses | Removing or renaming a field |
| New optional parameters | Making a parameter required |
| New endpoints | Removing an endpoint, changing a path |
| New enum values **in responses** (clients must tolerate unknown ones — this is documented) | Requiring new enum values in **requests** |
| New `problem.type` values | Changing the status code of an existing case |
| Loosening limits | Tightening limits |

### Deprecation

```http
Deprecation: @1780358400
Sunset: Wed, 02 Jun 2027 00:00:00 GMT
Link: <https://…/docs/migration/v1-to-v2>; rel="deprecation"
```

`Deprecation` (RFC 9745) names the moment the endpoint was declared deprecated —
here 2026-06-02 — and `Sunset` (RFC 8594) the moment it stops answering. **The
gap is twelve months**, per the table below; the example previously showed 28
days and contradicted the rule three lines under it. There is deliberately no
`Warning: 299` line: RFC 9111 §5.5 obsoleted that field in 2022, and the three
headers above already carry the same information in parseable form
([ADR-0039](../adr/0039-degraded-response-signalling.md)).

| Rule | Value |
|---|---|
| Minimum period between deprecation and shutdown | **12 months** for the main API, 6 months for the plugin contract |
| Parallel operation | `v1` and `v2` run side by side; `v2` is an adapter onto the same application layer, not a second implementation |
| Visibility | Usage is collected as `homeinv.api.requests`, tagged by endpoint template, version, client product and outcome — shutdown happens only when usage is zero or the period has elapsed (`REQ-API-009`, `ApiUsageMetricIT`). *This row said “per tenant and client”; the metric carries **no tenant**, deliberately — a per-tenant series times every endpoint is the cardinality that takes Prometheus down, and the per-tenant count that does exist is `REQ-TEN-009`'s quota in Valkey, which is a different thing for a different purpose.* |
| Announcement | Changelog, the three headers above, a notice in the UI for tenant administrators. **Not** a `Warning` header — this row still named one three lines under the paragraph that drops it ([ADR-0039](../adr/0039-degraded-response-signalling.md), `REQ-API-012`) |

## 8.4 GraphQL

**Read-only.** The rationale is in [ADR-0010](../adr/0010-api-surfaces.md): two
writing surfaces mean duplicated authorization, validation and idempotency logic
— the main source of holes that get closed on one surface and forgotten on the
other.

```graphql
type Query {
  item(id: ID!): Item
  items(filter: ItemFilter, sort: [ItemSort!], first: Int, after: String): ItemConnection!
  location(id: ID!): Location
  locationTree(rootId: ID, depth: Int = 3): [Location!]!
  itemTypes: [ItemType!]!
  savedSearch(id: ID!): SavedSearchResult
  stats(groupBy: StatsDimension!, filter: ItemFilter): [StatsBucket!]!
}

type Item {
  id: ID!
  name: String!
  type: ItemType!
  location: Location
  tags: [Tag!]!
  attributes: JSON!               # filtered by the role's field visibility
  photos(first: Int = 3): [Media!]!
  relations: [ItemRelation!]!
  history(first: Int = 20): RevisionConnection!
}
```

| Safeguard | Value |
|---|---|
| Depth limit | max. 10 — which is **below** the default maximum location depth of 12 ([04 `locations`](04-building-blocks.md)). A deep tree therefore cannot be walked to its leaves in one query; `locationTree(rootId:, depth:)` is the intended path and pages by subtree. The mismatch is deliberate: raising the limit to 12 would raise the cost ceiling of every other query too |
| Cost analysis | A budget per query; field costs declared; exceeding it → rejection **before** execution |
| Persisted queries | In production only registered queries; free-form queries only for authenticated administrators and in development |
| Introspection | Disabled in production (the schema ships with the documentation) |
| N+1 | `DataLoader` for every relation, guarded by a test |
| Field visibility | The same rule as in REST — `sensitive` fields are removed per role, not masked |
| Alias abuse | A limit on identical aliases per query |

## 8.5 gRPC — plugins and internal services

```protobuf
syntax = "proto3";
package home_inv.plugin.v1;

// The core calls the plugin
service CodeFormatPlugin {
  rpc Describe (DescribeRequest) returns (PluginDescription);
  rpc Claims   (ClaimsRequest)   returns (ClaimsResponse);
  rpc Parse    (ParseRequest)    returns (ParseResponse);
  rpc Render   (RenderRequest)   returns (RenderResponse);
}

service MetadataResolverPlugin {
  rpc Describe (DescribeRequest) returns (PluginDescription);
  rpc Supports (SupportsRequest) returns (SupportsResponse);
  rpc Resolve  (ResolveRequest)  returns (ResolveResponse);
}

service PrintTargetPlugin {
  rpc Describe     (DescribeRequest)     returns (PluginDescription);
  rpc ListPrinters (ListPrintersRequest) returns (ListPrintersResponse);
  rpc Print        (PrintRequest)        returns (stream PrintProgress);
}

// The plugin calls the core — narrowly bounded, capability-checked
service CoreApi {
  rpc GetItem      (GetItemRequest)      returns (ItemView);
  rpc UpdateItem   (UpdateItemRequest)   returns (ItemView);
  rpc EmitEvent    (EmitEventRequest)    returns (EmitEventResponse);
  rpc GetSetting   (GetSettingRequest)   returns (SettingValue);
  rpc StoreBlob    (stream BlobChunk)    returns (BlobRef);
}
```

| Rule | Value |
|---|---|
| Transport | gRPC over mTLS. Every plugin has its own certificate; the fingerprint is recorded in the registration. |
| Deadline | Every call has a deadline (default 10 s, configurable per port). No call without one. |
| Payload limit | 8 MB per message; anything larger goes through `StoreBlob` as a stream. |
| Capability check | **Every** `CoreApi` call re-checks the granted capability and the tenant scope — no trust derived from the connection. |
| Tooling | `buf` for generation, `buf lint` and `buf breaking` against the published state in CI |
| Backward compatibility | Field numbers are never reused; removed fields are marked `reserved` |

## 8.6 Public code resolution

A special case: the URL on the label must work from **any** camera app, so it has
to be reachable without authentication.

```
GET /c/{code}
```

| Case | Behaviour |
|---|---|
| Authenticated, permitted | Redirect to the item view |
| Not authenticated | Redirect to login with a return target — **without** any information about the item. A signed-in user arriving by cross-site navigation *is* recognised, through the `__Secure-` cookie scoped to `/c` ([ADR-0029](../adr/0029-session-cookie-and-oidc-state.md)); without it `SameSite=Strict` would send every scan from a foreign camera app to the login page |
| Unknown code | A neutral page, "this code is not assigned" — identical to the response for a missing permission |
| Legacy hostnames | `/c/{code}` is also accepted under the bases listed in `HOMEINV_LEGACY_BASE_URLS`, so labels keep working after a domain change ([10 §10.2.1](10-identification-and-labels.md)) |
| Rate limiting | Strict per IP with increasing delay. The code carries **50 bits** of entropy, so at 10⁶ issued codes a random guess hits with probability ≈ 1 : 1.1 · 10⁹ — that is what makes rate limiting sufficient rather than merely helpful ([ADR-0030](../adr/0030-public-code-format.md)). |
| Logging | Every access to an unknown code is counted; conspicuous patterns raise an alert. |

Additionally there is an **offline mode**: if the QR code carries the item UUID in
the fragment alongside the URL (`…/c/7Q2M4X9KD2F#i=<uuid>`), the installed PWA or app
can resolve the item from the local store without a network. Browsers do **not**
send the fragment to the server, so it leaks nothing.

## 8.7 Contract enforcement in CI

| Check | Tool | Fails the build |
|---|---|---|
| OpenAPI is valid and complete | `spectral` with a project ruleset | yes |
| No unannounced breaking change | `oasdiff` against the last release state | yes |
| Implementation matches the specification | Generated stubs + response validation in integration tests | yes |
| Examples in the specification are valid | Schema validation of the examples | yes |
| GraphQL schema unbroken | Schema comparison | yes |
| Protobuf unbroken | `buf breaking` | yes |
| Generated clients compile | TS `tsc`, Kotlin build | yes |
| Event schemas unbroken | Schema comparison | yes |
| Every endpoint has an authorization test | A dedicated rule: an endpoint declaring none of `@RequiresPermission`, `@RequiresEntitlement` ([ADR-0057](../adr/0057-the-instance-operator.md)) and an explicit `@PublicEndpoint` marker fails the build, in `ArchitectureRulesTest` and again in `PermissionInterceptor` at run time | yes |
